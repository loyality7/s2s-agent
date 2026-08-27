package com.s2s.agent.agent

import com.s2s.mobile.pipeline.ChatMessage
import com.s2s.mobile.pipeline.GenerationOverrides
import com.s2s.mobile.pipeline.LanguageModel
import com.s2s.mobile.pipeline.TokenSink
import com.s2s.mobile.pipeline.ToolCall
import com.s2s.mobile.pipeline.ToolContext
import com.s2s.mobile.pipeline.ToolDefinition
import com.s2s.mobile.pipeline.ToolFunction
import com.s2s.mobile.pipeline.ToolResult
import com.s2s.mobile.pipeline.Tools
import java.util.concurrent.ConcurrentHashMap

/** Scripted, one-shot-per-call reply queue — a test drains it exactly once per generateOnce(). */
class FakeLanguageModel(private val scriptedReplies: MutableList<String>) : LanguageModel {
    var generateCallCount = 0
        private set
    var cancelCallCount = 0
        private set

    /** Consumed on the NEXT generate() call, then cleared. */
    var failNextWith: String? = null

    /** Messages passed to the most recent generate() call. */
    var lastMessagesSeen: List<ChatMessage> = emptyList()
        private set

    override fun initialize(): Result<Unit> = Result.success(Unit)

    override fun generate(messages: List<ChatMessage>, sink: TokenSink, overrides: GenerationOverrides?) {
        generateCallCount++
        lastMessagesSeen = messages
        val err = failNextWith
        if (err != null) {
            failNextWith = null
            sink.onError(err)
            return
        }
        val reply = if (scriptedReplies.isNotEmpty()) scriptedReplies.removeAt(0) else "no more scripted replies"
        sink.onToken(reply)
        sink.onComplete()
    }

    override fun cancel() {
        cancelCallCount++
    }

    override fun resetContext() {}
    override fun trimMemory() {}
    override fun release() {}
}

class FakeContextEngine(systemPrompt: String) : com.s2s.mobile.pipeline.ContextEngine {
    private var system = systemPrompt
    val history = mutableListOf<ChatMessage>()
    val toolResults = mutableListOf<Pair<String, String>>()

    override fun addUser(text: String) {
        history += ChatMessage("user", text)
    }

    override fun replaceLastUser(text: String) {
        val idx = history.indexOfLast { it.role == "user" }
        if (idx >= 0) history[idx] = ChatMessage("user", text)
    }

    override fun addAssistant(text: String) {
        history += ChatMessage("assistant", text)
    }

    override fun dropLastUserIfUnanswered() {
        val last = history.lastOrNull() ?: return
        if (last.role == "user") history.removeAt(history.size - 1)
    }

    override fun addToolResult(name: String, output: String) {
        toolResults += name to output
        history += ChatMessage("tool", "$name: $output")
    }

    override fun messages(extraSystem: String?): List<ChatMessage> {
        val sys = if (extraSystem != null) "$system\n$extraSystem" else system
        return listOf(ChatMessage("system", sys)) + history
    }

    override fun setSystemPrompt(prompt: String) {
        system = prompt
    }

    override fun clear() {
        history.clear()
    }

    override fun toJson(): String = ""
    override fun fromJson(json: String) {}
}

class FakeTools : Tools {
    private val defs = ConcurrentHashMap<String, ToolDefinition>()
    private val functions = ConcurrentHashMap<String, ToolFunction>()

    val executedCalls = mutableListOf<ToolCall>()
    val contexts = mutableListOf<ToolContext>()
    var failNext = false

    override val definitions: List<ToolDefinition> get() = defs.values.toList()

    override fun register(definition: ToolDefinition, function: ToolFunction) {
        defs[definition.name] = definition
        functions[definition.name] = function
    }

    override fun unregister(name: String) {
        defs.remove(name)
        functions.remove(name)
    }

    override fun promptSection(): String? = if (defs.isEmpty()) null else "tools available"

    override fun parse(text: String): ToolCall? {
        val start = text.indexOf('{')
        if (start < 0) return null
        if (!defs.keys.any { text.contains(it) }) return null
        val name = defs.keys.first { text.contains(it) }
        return ToolCall(name, emptyMap())
    }

    override fun execute(call: ToolCall, context: ToolContext): ToolResult {
        executedCalls += call
        contexts += context
        if (failNext) {
            failNext = false
            return ToolResult(call.name, "boom", isError = true)
        }
        val fn = functions[call.name] ?: return ToolResult(call.name, "no such tool", isError = true)
        return ToolResult(call.name, fn(context, call.arguments))
    }
}
