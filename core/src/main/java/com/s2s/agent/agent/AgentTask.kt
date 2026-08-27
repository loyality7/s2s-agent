package com.s2s.agent.agent

import com.s2s.mobile.pipeline.ToolCall

/**
 * Durable work state for one unit of agent execution — distinct from a
 * speech turn (one user utterance). "Find the cheapest flight and tell me if
 * I should book it" is one speech turn but may run several LLM/tool steps
 * under one [AgentTask]; "keep watching the price" spawns a task that
 * outlives the turn, the conversation, and possibly the process.
 *
 * Immutable snapshot + `with*` copies rather than a mutable class: this is
 * exactly what [com.s2s.agent.task.TaskStore] persists, and a value type
 * round-trips through JSON without extra bookkeeping. No transcript (that's
 * [com.s2s.mobile.pipeline.ContextEngine]'s job), no long-term memory, no
 * hidden reasoning — only what's needed to resume execution.
 */
data class AgentTask(
    val taskId: String,
    val sessionId: String,
    val objective: String,
    val state: AgentState = AgentState.IDLE,
    val stepCount: Int = 0,
    val toolCallCount: Int = 0,
    val createdAtMs: Long,
    val updatedAtMs: Long = createdAtMs,
    /** Set only while [state] is [AgentState.WAITING_FOR_CONFIRMATION]. */
    val pendingToolCall: ToolCall? = null,
    val pendingCallId: String? = null,
    /** Set only while [state] is [AgentState.FAILED]. */
    val lastError: String? = null,
    val retryCount: Int = 0,
    /** Tool names exposed to the model for this task — the skill-gated progressive-disclosure narrowing, set once at task start. Null means "no skill matched, expose every registered tool". */
    val visibleToolNames: Set<String>? = null,
) {
    fun isTerminal(): Boolean = state == AgentState.COMPLETED ||
        state == AgentState.FAILED ||
        state == AgentState.CANCELLED
}
