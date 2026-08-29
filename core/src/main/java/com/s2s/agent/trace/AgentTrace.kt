package com.s2s.agent.trace

/**
 * One structured trace record: task → step → generation/tool → result.
 * Execution metadata only — never chain-of-thought or raw model reasoning,
 * matching [com.s2s.agent.agent.AgentEvent]'s same rule.
 */
data class TraceRecord(
    val taskId: String,
    val sessionId: String,
    val kind: TraceKind,
    val timestampMs: Long,
    val durationMs: Long? = null,
    val toolName: String? = null,
    val callId: String? = null,
    val status: String? = null,
    val errorType: String? = null,
    val retryCount: Int = 0,
)

enum class TraceKind { GENERATION, TOOL_CALL, FINAL_RESPONSE }

fun interface AgentTracer {
    fun record(trace: TraceRecord)
}

val NoopTracer = AgentTracer { }
