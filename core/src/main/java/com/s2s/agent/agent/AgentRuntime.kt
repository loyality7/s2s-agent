package com.s2s.agent.agent

import com.s2s.agent.middleware.MiddlewareChain
import com.s2s.agent.policy.ConfirmationDecision
import com.s2s.agent.policy.ConfirmationPolicy
import com.s2s.agent.policy.ExecutionBudget
import com.s2s.agent.policy.FailureKind
import com.s2s.agent.policy.RetryDecision
import com.s2s.agent.policy.RetryPolicy
import com.s2s.agent.skill.SkillRegistry
import com.s2s.agent.task.TaskStore
import com.s2s.agent.tool.ToolCoordinator
import com.s2s.agent.trace.AgentTracer
import com.s2s.agent.trace.NoopTracer
import com.s2s.agent.trace.TraceKind
import com.s2s.agent.trace.TraceRecord
import com.s2s.mobile.S2SEngine
import com.s2s.mobile.pipeline.ChatMessage
import com.s2s.mobile.pipeline.ContextEngine
import com.s2s.mobile.pipeline.LanguageModel
import com.s2s.mobile.pipeline.TokenSink
import com.s2s.mobile.pipeline.ToolCall
import com.s2s.mobile.pipeline.ToolContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Agent Harness execution loop: MODEL DECIDES + HARNESS ENABLES + POLICY
 * CONSTRAINS + TOOLS EXECUTE + CONTEXT PERSISTS. Explicit iterative loop, no
 * recursion (`generate() -> executeTool() -> generate()` nesting never
 * happens — every step returns control to [run]/[resumeTask]'s `while`).
 *
 * Every mutable execution state lives in the [AgentTask] snapshot persisted
 * to [taskStore] at each checkpoint, not on this class — this instance is
 * safe to reuse across sessions and tasks, and a task can be resumed after
 * process death because nothing it needs lives only in memory.
 *
 * Model/context/tools/engine are supplied by the host — this class has no
 * opinion on which implementation is in use, and imports no concrete
 * provider (`Llama*`, `Sqlite*`, `ToolRegistry`, etc.) anywhere.
 */
class AgentRuntime(
    private val engine: S2SEngine,
    private val languageModel: LanguageModel,
    private val context: ContextEngine,
    tools: com.s2s.mobile.pipeline.Tools,
    private val taskStore: TaskStore,
    private val budget: ExecutionBudget = ExecutionBudget(),
    private val retryPolicy: RetryPolicy = RetryPolicy.boundedRetry(2),
    private val confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.ALWAYS_EXECUTE,
    middleware: List<com.s2s.agent.middleware.AgentMiddleware> = emptyList(),
    private val tracer: AgentTracer = NoopTracer,
    /** Optional — when set, [findRelevant] narrows tool exposure to the matched skill's [com.s2s.agent.skill.SkillMetadata.requiredTools] instead of the full catalog. */
    private val skills: SkillRegistry? = null,
) {
    private val toolCoordinator = ToolCoordinator(tools, context)
    private val middlewareChain = MiddlewareChain(middleware)
    private val listeners = CopyOnWriteArrayList<(AgentEvent) -> Unit>()
    private val callSequence = AtomicInteger(0)

    /** Live cancellation flags for currently-running tasks — not persisted; a resumed task starts uncancelled. */
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    fun addListener(listener: (AgentEvent) -> Unit) {
        listeners += listener
    }

    private fun emit(event: AgentEvent) {
        listeners.forEach { it(event) }
    }

    fun cancel(taskId: String) {
        cancelFlags[taskId]?.set(true)
        languageModel.cancel()
    }

    private fun isCancelled(taskId: String) = cancelFlags[taskId]?.get() == true

    /**
     * Starts a new task and runs it to completion, a terminal failure,
     * cancellation, a budget limit, or [AgentState.WAITING_FOR_CONFIRMATION].
     * Blocks the calling thread — same contract as [LanguageModel.generate];
     * run it on a worker thread. Persists a checkpoint via [taskStore] at
     * every meaningful boundary, so a process death mid-task leaves a
     * resumable, not corrupted, record.
     */
    fun run(request: String): AgentTask {
        val taskId = UUID.randomUUID().toString()
        cancelFlags[taskId] = AtomicBoolean(false)

        var task = AgentTask(
            taskId = taskId,
            sessionId = engine.sessionId,
            objective = request,
            state = AgentState.RUNNING,
            createdAtMs = System.currentTimeMillis(),
            visibleToolNames = skills?.findRelevant(request)?.requiredTools,
        )
        taskStore.createTask(task)
        emit(AgentEvent.TaskStarted(task.taskId))

        context.addUser(request)
        return loop(task)
    }

    /**
     * Resumes a task previously left in [AgentState.WAITING_FOR_CONFIRMATION]
     * (including one restored from [taskStore] after process death — the
     * pending tool call travels with the persisted [AgentTask], not this
     * runtime instance).
     */
    fun resumeTask(taskId: String): AgentTask {
        val stored = taskStore.getTask(taskId) ?: error("No such task: $taskId")
        check(stored.state == AgentState.WAITING_FOR_CONFIRMATION) {
            "Task $taskId is not waiting for confirmation (state=${stored.state})"
        }
        cancelFlags.getOrPut(taskId) { AtomicBoolean(false) }

        val call = stored.pendingToolCall ?: error("Task $taskId has no pending tool call to resume")
        val afterTool = executeTool(stored.copy(state = AgentState.EXECUTING_TOOL, pendingToolCall = null, pendingCallId = null), call)
        return loop(afterTool)
    }

    /** Marks a pending confirmation rejected and fails the task — no tool execution happens. */
    fun rejectConfirmation(taskId: String): AgentTask {
        val stored = taskStore.getTask(taskId) ?: error("No such task: $taskId")
        check(stored.state == AgentState.WAITING_FOR_CONFIRMATION) {
            "Task $taskId is not waiting for confirmation (state=${stored.state})"
        }
        val failed = stored.copy(
            state = AgentState.FAILED,
            lastError = "User rejected confirmation for '${stored.pendingToolCall?.name}'",
            updatedAtMs = System.currentTimeMillis(),
        )
        taskStore.checkpointTask(failed)
        emit(AgentEvent.TaskFailed(failed.taskId, failed.lastError!!))
        return failed
    }

    /** The one iterative loop every entry point (new task, resumed task) funnels into. */
    private fun loop(initial: AgentTask): AgentTask {
        var task = initial

        while (true) {
            if (isCancelled(task.taskId)) {
                context.dropLastUserIfUnanswered()
                task = task.copy(state = AgentState.CANCELLED, updatedAtMs = System.currentTimeMillis())
                taskStore.checkpointTask(task)
                emit(AgentEvent.TaskCancelled(task.taskId))
                return task
            }
            if (task.stepCount >= budget.maxSteps ||
                task.toolCallCount >= budget.maxToolCalls ||
                System.currentTimeMillis() - task.createdAtMs > budget.maxDurationMs
            ) {
                task = task.copy(
                    state = AgentState.FAILED,
                    lastError = "Execution budget exceeded",
                    updatedAtMs = System.currentTimeMillis(),
                )
                taskStore.checkpointTask(task)
                emit(AgentEvent.TaskFailed(task.taskId, task.lastError!!))
                return task
            }

            val (nextTask, decision) = generateOnce(task)
            task = nextTask

            when (decision) {
                is AgentDecision.FinalResponse -> {
                    task = task.copy(state = AgentState.COMPLETED, updatedAtMs = System.currentTimeMillis())
                    taskStore.checkpointTask(task)
                    engine.speakAssistantText(decision.text)
                    emit(AgentEvent.TaskCompleted(task.taskId, decision.text))
                    return task
                }

                is AgentDecision.Failure -> {
                    when (retryPolicy.decide(task.retryCount, decision.kind, decision.cause)) {
                        RetryDecision.RETRY -> {
                            task = task.copy(retryCount = task.retryCount + 1, updatedAtMs = System.currentTimeMillis())
                            taskStore.checkpointTask(task)
                            continue
                        }
                        RetryDecision.FALLBACK, RetryDecision.ASK_USER, RetryDecision.FAIL -> {
                            task = task.copy(
                                state = AgentState.FAILED,
                                lastError = decision.message,
                                updatedAtMs = System.currentTimeMillis(),
                            )
                            taskStore.checkpointTask(task)
                            emit(AgentEvent.TaskFailed(task.taskId, decision.message))
                            return task
                        }
                    }
                }

                is AgentDecision.ToolInvocation -> {
                    if (isCancelled(task.taskId)) {
                        task = task.copy(state = AgentState.CANCELLED, updatedAtMs = System.currentTimeMillis())
                        taskStore.checkpointTask(task)
                        emit(AgentEvent.TaskCancelled(task.taskId))
                        return task
                    }

                    val call = middlewareChain.beforeTool(decision.call)
                    val confirmation = confirmationPolicy.decide(call.name, call.arguments)

                    if (confirmation == ConfirmationDecision.REJECT) {
                        task = task.copy(
                            state = AgentState.FAILED,
                            lastError = "Tool '${call.name}' rejected by policy",
                            updatedAtMs = System.currentTimeMillis(),
                        )
                        taskStore.checkpointTask(task)
                        emit(AgentEvent.TaskFailed(task.taskId, task.lastError!!))
                        return task
                    }

                    if (confirmation == ConfirmationDecision.REQUIRE_CONFIRMATION) {
                        val callId = "${task.taskId}-${callSequence.incrementAndGet()}"
                        task = task.copy(
                            state = AgentState.WAITING_FOR_CONFIRMATION,
                            pendingToolCall = call,
                            pendingCallId = callId,
                            updatedAtMs = System.currentTimeMillis(),
                        )
                        taskStore.checkpointTask(task)
                        emit(AgentEvent.ConfirmationRequired(task.taskId, call.name, callId))
                        // Real pause: the loop returns control to the caller here.
                        // A host resumes later via resumeTask()/rejectConfirmation(),
                        // which re-enters this same loop — including after process
                        // death, since `task` was just persisted above.
                        return task
                    }

                    task = executeTool(task, call)
                }
            }
        }
    }

    private fun generateOnce(task: AgentTask): Pair<AgentTask, AgentDecision> {
        var updated = task.copy(
            stepCount = task.stepCount + 1,
            state = AgentState.RUNNING,
            updatedAtMs = System.currentTimeMillis(),
        )
        taskStore.checkpointTask(updated)
        emit(AgentEvent.GenerationStarted(updated.taskId, updated.stepCount))
        val startedAt = System.currentTimeMillis()

        val toolPrompt = toolCoordinator.promptSectionFor(updated.visibleToolNames)
        val rawMessages: List<ChatMessage> = context.messages(extraSystem = toolPrompt)
        val messages = middlewareChain.beforeModel(rawMessages)

        val reply = StringBuilder()
        var failure: AgentDecision.Failure? = null
        val done = CountDownLatch(1)

        languageModel.generate(
            messages,
            object : TokenSink {
                override fun onToken(text: String) {
                    reply.append(text)
                }

                override fun onComplete() = done.countDown()

                override fun onError(message: String, cause: Throwable?) {
                    val kind = if (isCancelled(task.taskId)) FailureKind.CANCELLED else FailureKind.APPLICATION_ERROR
                    failure = AgentDecision.Failure(message, kind, cause)
                    done.countDown()
                }
            },
        )
        done.await()

        val durationMs = System.currentTimeMillis() - startedAt
        emit(AgentEvent.GenerationCompleted(updated.taskId, updated.stepCount))
        tracer.record(
            TraceRecord(
                taskId = updated.taskId,
                sessionId = updated.sessionId,
                kind = TraceKind.GENERATION,
                timestampMs = startedAt,
                durationMs = durationMs,
                status = if (failure == null) "ok" else "error",
                errorType = failure?.kind?.name,
                retryCount = updated.retryCount,
            ),
        )

        failure?.let { return updated to it }

        val text = middlewareChain.afterModel(reply.toString())
        val call = toolCoordinator.parse(text)
        return if (call != null) {
            updated to AgentDecision.ToolInvocation(call)
        } else {
            context.addAssistant(text)
            updated to AgentDecision.FinalResponse(text)
        }
    }

    private fun executeTool(task: AgentTask, call: ToolCall): AgentTask {
        val callId = task.pendingCallId ?: "${task.taskId}-${callSequence.incrementAndGet()}"
        val toolContext = ToolContext(sessionId = task.sessionId, turnId = task.taskId, callId = callId)

        var updated = task.copy(
            state = AgentState.EXECUTING_TOOL,
            toolCallCount = task.toolCallCount + 1,
            updatedAtMs = System.currentTimeMillis(),
        )
        taskStore.checkpointTask(updated)
        emit(AgentEvent.ToolCallStarted(updated.taskId, call.name, callId))
        val startedAt = System.currentTimeMillis()

        val result = middlewareChain.afterTool(toolCoordinator.execute(call, toolContext))

        tracer.record(
            TraceRecord(
                taskId = updated.taskId,
                sessionId = updated.sessionId,
                kind = TraceKind.TOOL_CALL,
                timestampMs = startedAt,
                durationMs = System.currentTimeMillis() - startedAt,
                toolName = call.name,
                callId = callId,
                status = if (result.isError) "error" else "ok",
            ),
        )
        emit(AgentEvent.ToolCallCompleted(updated.taskId, call.name, callId, result.isError))
        return updated
    }
}
