package com.s2s.agent.policy

/** Bounds on one task's execution, guarding against runaway loops. */
data class ExecutionBudget(
    val maxSteps: Int = 8,
    val maxToolCalls: Int = 8,
    val maxDurationMs: Long = 60_000,
)
