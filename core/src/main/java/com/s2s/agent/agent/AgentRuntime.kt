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
import com.s2s.agent.verify.VerificationOutcome
import com.s2s.agent.verify.VerificationRequest
import com.s2s.agent.verify.VerificationVerdict
import com.s2s.agent.verify.Verifier
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
    /**
     * Optional independent check on a consequential tool's observable result.
     * Applied only to calls the host's [confirmationPolicy] already flagged
     * as [ConfirmationDecision.REQUIRE_CONFIRMATION] — the harness does not
     * invent its own notion of "consequential" beyond what the host already
     * decided. Never asked "did this succeed?" of the model itself; the
     * model's own [AgentDecision.FinalResponse] text is never treated as
     * proof a prior tool call worked. Null (the default) means no
     * verification runs — a tool's [com.s2s.mobile.pipeline.ToolResult.isError]
     * remains the only signal, same as before this existed.
     */
    private val verifier: Verifier? = null,
) {
    private val toolCoordinator = ToolCoordinator(tools, context)
    private val middlewareChain = MiddlewareChain(middleware)
    private val listeners = CopyOnWriteArrayList<(AgentEvent) -> Unit>()
    private val callSequence = AtomicInteger(0)

    /** Live cancellation flags for currently-running tasks — not persisted; a resumed task starts uncancelled. */
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    /**
     * WIP=1 per session: the taskId currently executing for a given
     * sessionId, if any. Harness-enforced, not model-requested — a second
     * [run] call for a session that already has one in flight is rejected
     * rather than silently interleaving two loops against the same
     * [ContextEngine]/[S2SEngine], which would corrupt turn ordering.
     */
    private val activeTaskBySession = ConcurrentHashMap<String, String>()

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
        val sessionId = engine.sessionId
        val taskId = UUID.randomUUID().toString()

        val previous = activeTaskBySession.putIfAbsent(sessionId, taskId)
        check(previous == null) {
            "Session $sessionId already has task $previous running — WIP=1 per session; wait for it to finish, pause, or fail before starting another"
        }

        try {
            cancelFlags[taskId] = AtomicBoolean(false)

            var task = AgentTask(
                taskId = taskId,
                sessionId = sessionId,
                objective = request,
                state = AgentState.RUNNING,
                createdAtMs = System.currentTimeMillis(),
                visibleToolNames = skills?.findRelevant(request)?.requiredTools,
            )
            taskStore.createTask(task)
            emit(AgentEvent.TaskStarted(task.taskId))

            context.addUser(request)
            val result = loop(task)
            if (result.state != AgentState.WAITING_FOR_CONFIRMATION) activeTaskBySession.remove(sessionId, taskId)
            return result
        } catch (e: Throwable) {
            activeTaskBySession.remove(sessionId, taskId)
            throw e
        }
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
        activeTaskBySession[stored.sessionId] = taskId

        try {
            val call = stored.pendingToolCall ?: error("Task $taskId has no pending tool call to resume")
            val (afterTool, toolResult) = executeTool(stored.copy(state = AgentState.EXECUTING_TOOL, pendingToolCall = null, pendingCallId = null), call)
            val (task, failed) = applyToolResult(afterTool, call, toolResult)
            if (failed) {
                activeTaskBySession.remove(stored.sessionId, taskId)
                return task
            }

            val verified = verifyIfConfigured(task, call, toolResult)
            if (verified.isTerminal()) {
                activeTaskBySession.remove(stored.sessionId, taskId)
                return verified
            }

            val result = loop(verified)
            if (result.state != AgentState.WAITING_FOR_CONFIRMATION) activeTaskBySession.remove(stored.sessionId, taskId)
            return result
        } catch (e: Throwable) {
            activeTaskBySession.remove(stored.sessionId, taskId)
            throw e
        }
    }

    /**
     * Runs [toolResult] through [retryPolicy] the same way a generation
     * failure is judged — a failed tool call is not silently ignored by the
     * loop just because the model might notice from context. Returns the
     * updated task and whether it landed terminal (already checkpointed and
     * [AgentEvent.TaskFailed] emitted in that case).
     */
    private fun applyToolResult(task: AgentTask, call: ToolCall, toolResult: com.s2s.mobile.pipeline.ToolResult): Pair<AgentTask, Boolean> {
        if (!toolResult.isError) return task to false

        return when (retryPolicy.decide(task.retryCount, FailureKind.APPLICATION_ERROR, null)) {
            RetryDecision.RETRY -> {
                val retried = task.copy(retryCount = task.retryCount + 1, updatedAtMs = System.currentTimeMillis())
                taskStore.checkpointTask(retried)
                retried to false
            }
            RetryDecision.FALLBACK, RetryDecision.ASK_USER, RetryDecision.FAIL -> {
                val failed = task.copy(
                    state = AgentState.FAILED,
                    lastError = "Tool '${call.name}' failed: ${toolResult.output}",
                    updatedAtMs = System.currentTimeMillis(),
                )
                taskStore.checkpointTask(failed)
                emit(AgentEvent.TaskFailed(failed.taskId, failed.lastError!!))
                failed to true
            }
        }
    }

    /**
     * Independent check on a tool that just executed after confirmation —
     * only reached for calls the host already flagged as consequential (see
     * [verifier]'s doc). Returns [task] unchanged when [verifier] is null or
     * the outcome is [VerificationVerdict.VERIFIED]; otherwise fails the task
     * with the verifier's own reason, never the model's opinion.
     */
    private fun verifyIfConfigured(task: AgentTask, call: ToolCall, toolResult: com.s2s.mobile.pipeline.ToolResult): AgentTask {
        val v = verifier ?: return task
        val outcome: VerificationOutcome = v.verify(
            VerificationRequest(
                objective = task.objective,
                actionDescription = "Called tool '${call.name}' with arguments ${call.arguments}",
                result = toolResult.output,
                successCriteria = emptyList(),
            ),
        )
        if (outcome.verdict == VerificationVerdict.VERIFIED) return task

        val failed = task.copy(
            state = AgentState.FAILED,
            lastError = "Verification failed for tool '${call.name}': ${outcome.reason}",
            updatedAtMs = System.currentTimeMillis(),
        )
        taskStore.checkpointTask(failed)
        emit(AgentEvent.TaskFailed(failed.taskId, failed.lastError!!))
        return failed
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
        activeTaskBySession.remove(stored.sessionId, taskId)
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

                    val (afterTool, toolResult) = executeTool(task, call)
                    val (updated, failed) = applyToolResult(afterTool, call, toolResult)
                    task = updated
                    if (failed) return task
                }
            }
        }
    }

    /**
     * Two-step tool decision: before the main (free-text) generation call,
     * ask a grammar-constrained "does this need a tool" question via
     * [LanguageModel.generateStructured]. Real-device tool-calling testing
     * showed the single-call free-text `{"tool": ...}` protocol lets a small
     * model emit malformed/truncated JSON with nothing structurally
     * preventing it — this adds a reliable, schema-constrained decision
     * before that ever happens, without forcing every ordinary conversational
     * reply through JSON (a schema can't express "or just talk normally").
     *
     * Returns null when the model isn't equipped to answer (backend doesn't
     * support [LanguageModel.generateStructured] — its default fails, which
     * is the expected, non-error case for e.g. a remote HTTP backend), or
     * decided no tool is needed — either way the caller falls through to the
     * ordinary single-call generation path unchanged, which still has its own
     * malformed-tool-call guard as a second line of defense.
     */
    private fun classifyToolDecision(task: AgentTask, messages: List<ChatMessage>): AgentDecision? {
        val schema = toolCoordinator.structuredToolDecisionSchema(task.visibleToolNames)
        val startedAt = System.currentTimeMillis()
        val result = languageModel.generateStructured(messages, schema)
        tracer.record(
            TraceRecord(
                taskId = task.taskId,
                sessionId = task.sessionId,
                kind = TraceKind.GENERATION,
                timestampMs = startedAt,
                durationMs = System.currentTimeMillis() - startedAt,
                status = if (result.isSuccess) "ok" else "unsupported-or-error",
                retryCount = task.retryCount,
            ),
        )
        val decisionText = result.getOrNull() ?: return null
        val call = toolCoordinator.toolCallFromStructuredDecision(decisionText) ?: return null
        return AgentDecision.ToolInvocation(call)
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

        // Only classify before the FIRST tool call of this task, not after
        // one has already run — once a tool result is in context, the next
        // generation's job is to produce the final answer FROM that result,
        // never to classify (and potentially re-invoke) a tool again. Gating
        // on toolCallCount, not stepCount, correctly still allows a second
        // classify+call for a genuinely different follow-up request in the
        // same task if one is ever added, without ever double-firing for the
        // "answer using the tool result" step of the current tool.
        if (updated.toolCallCount == 0 && toolCoordinator.availableTools().isNotEmpty()) {
            classifyToolDecision(updated, rawMessages)?.let { return updated to it }
        }

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
        return when {
            call != null -> updated to AgentDecision.ToolInvocation(call)
            toolCoordinator.looksLikeMalformedToolCall(text) -> {
                // Real-device evidence: a malformed/truncated tool-call JSON
                // fragment was previously spoken aloud verbatim here, because
                // a failed parse fell straight through to FinalResponse. This
                // is never recorded via context.addAssistant() either — it
                // was never a real assistant turn, and recording it would
                // teach the model its own broken output was accepted.
                updated to AgentDecision.Failure(
                    "Model produced a malformed tool call: $text",
                    FailureKind.INVALID_ARGUMENTS,
                )
            }
            else -> {
                context.addAssistant(text)
                updated to AgentDecision.FinalResponse(text)
            }
        }
    }

    private fun executeTool(task: AgentTask, call: ToolCall): Pair<AgentTask, com.s2s.mobile.pipeline.ToolResult> {
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
        return updated to result
    }
}
