package com.s2s.agent.agent

/**
 * Host-facing execution events for one [AgentTask]. Safe metadata only — no
 * chain-of-thought, no raw model reasoning.
 */
sealed interface AgentEvent {
    val taskId: String

    data class TaskStarted(override val taskId: String) : AgentEvent
    data class GenerationStarted(override val taskId: String, val step: Int) : AgentEvent
    data class GenerationCompleted(override val taskId: String, val step: Int) : AgentEvent
    data class ToolCallStarted(override val taskId: String, val toolName: String, val callId: String) : AgentEvent
    data class ToolCallCompleted(
        override val taskId: String,
        val toolName: String,
        val callId: String,
        val isError: Boolean,
    ) : AgentEvent
    data class ConfirmationRequired(override val taskId: String, val toolName: String, val callId: String) : AgentEvent
    data class TaskCompleted(override val taskId: String, val response: String) : AgentEvent
    data class TaskFailed(override val taskId: String, val message: String) : AgentEvent
    data class TaskCancelled(override val taskId: String) : AgentEvent
}
