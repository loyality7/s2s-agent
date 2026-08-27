package com.s2s.agent.policy

/** Whether a tool call may run without asking the user first. */
enum class ConfirmationDecision { EXECUTE, REQUIRE_CONFIRMATION, REJECT }

/**
 * Consequential-action boundary. Deliberately generic — the current
 * `ToolDefinition` contract (speech-to-speech-mobile) carries no risk/capability
 * metadata, so a host expresses its own policy by tool name/pattern here
 * rather than the harness inventing per-tool rules. See s2s-agent's README
 * for the "no risk metadata in core" limitation this works around.
 */
fun interface ConfirmationPolicy {
    fun decide(toolName: String, arguments: Map<String, String>): ConfirmationDecision

    companion object {
        val ALWAYS_EXECUTE = ConfirmationPolicy { _, _ -> ConfirmationDecision.EXECUTE }
    }
}
