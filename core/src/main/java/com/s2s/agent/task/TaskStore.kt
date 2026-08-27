package com.s2s.agent.task

import com.s2s.agent.agent.AgentTask

/**
 * Durable AgentTask work-state store — the backbone for background/long-running
 * tasks (§18/§6 of the harness spec) and for confirmation pause/resume.
 *
 * Stores execution state (status, progress, pending action, retry info) only —
 * never the conversation transcript ([com.s2s.mobile.pipeline.ContextEngine]'s
 * job) and never long-term memory. [AgentRuntime][com.s2s.agent.agent.AgentRuntime]
 * depends on this interface, never a concrete storage technology, so the
 * implementation can be swapped (in-memory for tests, file-backed on Android,
 * something else later) without the runtime changing.
 */
interface TaskStore {
    fun createTask(task: AgentTask)
    fun getTask(taskId: String): AgentTask?
    fun updateTask(task: AgentTask)
    fun listTasks(sessionId: String? = null): List<AgentTask>
    fun deleteTask(taskId: String)

    /** Persists [task] as its current checkpoint — same as [updateTask], named for call-site clarity at execution boundaries. */
    fun checkpointTask(task: AgentTask) = updateTask(task)
}
