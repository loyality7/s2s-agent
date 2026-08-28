package com.s2s.agent.tool

import com.s2s.mobile.pipeline.ContextEngine
import com.s2s.mobile.pipeline.ToolCall
import com.s2s.mobile.pipeline.ToolContext
import com.s2s.mobile.pipeline.ToolDefinition
import com.s2s.mobile.pipeline.ToolResult
import com.s2s.mobile.pipeline.Tools

/**
 * Agent-level tool management around the existing [Tools] contract — dispatch,
 * progressive tool exposure, and oversized-result offloading. Does not
 * reimplement [Tools]/`ToolRegistry`; wraps whatever implementation the host
 * supplies.
 *
 * Progressive disclosure: [promptSectionFor] can be given a smaller
 * `visibleToolNames` set (typically supplied by [com.s2s.agent.skill.SkillRegistry]
 * for the active skill) instead of dumping every registered tool's schema into
 * every prompt.
 */
class ToolCoordinator(
    private val tools: Tools,
    private val context: ContextEngine,
    /** A tool result whose output is at least this many characters gets offloaded rather than inlined. */
    private val inlineSizeLimit: Int = 4_000,
) {
    /** All registered tool definitions — full catalog, before any skill-based narrowing. */
    fun availableTools(): List<ToolDefinition> = tools.definitions

    /**
     * Prompt fragment for only [visibleToolNames] (or every registered tool,
     * if null) — the progressive-disclosure seam a [com.s2s.agent.skill.Skill]
     * or policy can narrow.
     */
    fun promptSectionFor(visibleToolNames: Set<String>? = null): String? {
        if (visibleToolNames == null) return tools.promptSection()
        if (visibleToolNames.isEmpty()) return null
        // Tools.promptSection() has no per-name filter in the published contract,
        // so filtering happens on the definitions list directly rather than
        // asking the underlying Tools implementation to do it.
        val visible = tools.definitions.filter { it.name in visibleToolNames }
        if (visible.isEmpty()) return null
        return buildString {
            appendLine("You can call these tools:")
            visible.forEach { d ->
                append("- ").append(d.name).append(": ").append(d.description).appendLine()
            }
            appendLine(
                "To use one, reply with nothing but " +
                    "{\"tool\": \"<name>\", \"arguments\": {\"<key>\": \"<value>\"}}. " +
                    "Otherwise answer normally in speech.",
            )
        }
    }

    fun parse(text: String): ToolCall? = tools.parse(text)

    /**
     * JSON Schema for the classify step's structured output — see
     * [com.s2s.agent.agent.AgentRuntime]'s two-step tool-decision flow.
     * `tool`/`arguments` are only meaningful when `needsTool` is true; the
     * schema can't express "either this shape or plain text" (JSON Schema
     * constrains valid JSON, it doesn't opt out of JSON entirely) — that's
     * exactly why this is a SEPARATE classify call from the main generation,
     * not a replacement for it. Field names (`tool`/`arguments`) deliberately
     * match [Tools.parse]'s existing expected shape so the same parser
     * handles both the free-text and structured paths — no second JSON
     * parser needed. When [visibleToolNames] narrows the catalog (skill-gated
     * progressive disclosure), only those tool names are valid enum values
     * here — the model is never offered a tool it can't currently see.
     */
    fun structuredToolDecisionSchema(visibleToolNames: Set<String>? = null): String {
        val names = (visibleToolNames?.let { names -> tools.definitions.filter { it.name in names } } ?: tools.definitions)
            .map { it.name }
        val toolSchema = if (names.isEmpty()) {
            "\"type\": \"string\""
        } else {
            "\"type\": \"string\", \"enum\": [${names.joinToString(",") { "\"$it\"" }}]"
        }
        return """
            {
              "type": "object",
              "properties": {
                "needsTool": { "type": "boolean" },
                "tool": { $toolSchema },
                "arguments": { "type": "object" }
              },
              "required": ["needsTool"]
            }
        """.trimIndent()
    }

    /**
     * Turns the classify step's structured JSON reply into a [ToolCall] —
     * null if the JSON says `needsTool: false`, or if [parse] (the existing,
     * tested `{"tool": ..., "arguments": ...}` parser) can't find a real
     * registered tool in it. Defensive: grammar-constrained decoding with an
     * enum should make an unregistered tool name impossible, but a caller
     * must never trust that absolutely.
     */
    fun toolCallFromStructuredDecision(json: String): ToolCall? {
        if (!json.contains("\"needsTool\"") || json.contains("\"needsTool\": false") || json.contains("\"needsTool\":false")) {
            return null
        }
        return parse(json)
    }

    /**
     * True when [text] looks like an attempted tool call that [parse] could
     * not turn into a [ToolCall] — a malformed/truncated `{"tool": ...}`
     * blob, not genuine conversational text. Real-device evidence: a small
     * local model occasionally emits an incomplete or malformed tool-call
     * JSON fragment (e.g. `{"tool":"calculate","` with no closing brace);
     * [Tools.parse] correctly returns null for it (it genuinely isn't valid
     * JSON), but [com.s2s.agent.agent.AgentRuntime] must not then treat that
     * null as "this is the final answer" and speak the raw fragment aloud —
     * exactly what happened before this check existed. A conservative
     * heuristic (starts with `{` and mentions `"tool"`) rather than a full
     * parse: false positives just cost one wasted retry, false negatives
     * would let broken JSON reach TTS again.
     */
    fun looksLikeMalformedToolCall(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("{") && trimmed.contains("\"tool\"")
    }

    /**
     * Runs [call], then records the result in [context] — inline via
     * [ContextEngine.addToolResult] if small, or as a reference if
     * [ToolResult.output] is at least [inlineSizeLimit] characters. Large
     * output still gets a real record via [ContextEngine.addToolResult]
     * (context owns storage/retrieval, this class does not duplicate it) —
     * only the copy handed back to the caller is truncated to a summary.
     */
    fun execute(call: ToolCall, toolContext: ToolContext): ToolResult {
        val result = tools.execute(call, toolContext)
        if (result.output.length < inlineSizeLimit) {
            context.addToolResult(result.name, result.output)
            return result
        }

        val summary = result.output.take(inlineSizeLimit) + "… (${result.output.length} chars total, truncated)"
        context.addToolResult(result.name, result.output)
        return result.copy(output = summary)
    }
}
