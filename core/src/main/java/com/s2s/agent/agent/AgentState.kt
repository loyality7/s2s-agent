package com.s2s.agent.agent

/**
 * Explicit execution states for one [AgentTask]. Every state a real
 * transition can land in and nothing else — no states kept "for symmetry".
 */
enum class AgentState {
    IDLE,
    RUNNING,
    EXECUTING_TOOL,
    WAITING_FOR_CONFIRMATION,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}
