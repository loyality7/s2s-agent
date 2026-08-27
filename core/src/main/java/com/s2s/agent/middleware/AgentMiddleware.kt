package com.s2s.agent.middleware

import com.s2s.mobile.pipeline.ChatMessage
import com.s2s.mobile.pipeline.ToolCall
import com.s2s.mobile.pipeline.ToolResult

/**
 * Narrow interception points around the two things that actually cost
 * tokens/time: a model call and a tool call. Deliberately not a general
 * event-bus/hook-for-everything framework — no hooks for TTS, cancellation,
 * or anything speech-layer, since those belong to `speech-to-speech-mobile`,
 * not here.
 *
 * All methods default to a no-op passthrough, so a middleware only overrides
 * what it actually needs (context compression, logging, tool filtering,
 * policy checks).
 */
interface AgentMiddleware {
    fun beforeModel(messages: List<ChatMessage>): List<ChatMessage> = messages
    fun afterModel(rawOutput: String): String = rawOutput
    fun beforeTool(call: ToolCall): ToolCall = call
    fun afterTool(result: ToolResult): ToolResult = result
}

/** Runs a list of [AgentMiddleware] in order, each seeing the previous one's output. */
class MiddlewareChain(private val middleware: List<AgentMiddleware>) {
    fun beforeModel(messages: List<ChatMessage>): List<ChatMessage> =
        middleware.fold(messages) { acc, m -> m.beforeModel(acc) }

    fun afterModel(rawOutput: String): String =
        middleware.fold(rawOutput) { acc, m -> m.afterModel(acc) }

    fun beforeTool(call: ToolCall): ToolCall =
        middleware.fold(call) { acc, m -> m.beforeTool(acc) }

    fun afterTool(result: ToolResult): ToolResult =
        middleware.fold(result) { acc, m -> m.afterTool(acc) }
}
