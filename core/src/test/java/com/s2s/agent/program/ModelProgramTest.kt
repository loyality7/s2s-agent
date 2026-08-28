package com.s2s.agent.program

import com.s2s.mobile.pipeline.ChatMessage
import com.s2s.mobile.pipeline.GenerationOverrides
import com.s2s.mobile.pipeline.LanguageModel
import com.s2s.mobile.pipeline.TokenSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelProgramTest {

    private class FakeLanguageModel(private val reply: String? = null, private val failWith: String? = null) : LanguageModel {
        var lastMessages: List<ChatMessage> = emptyList()
            private set

        override fun initialize(): Result<Unit> = Result.success(Unit)
        override fun generate(messages: List<ChatMessage>, sink: TokenSink, overrides: GenerationOverrides?) {
            lastMessages = messages
            if (failWith != null) {
                sink.onError(failWith)
                return
            }
            sink.onToken(reply.orEmpty())
            sink.onComplete()
        }
        override fun cancel() {}
        override fun resetContext() {}
        override fun trimMemory() {}
        override fun release() {}
    }

    // A representative program: classify a user utterance as one of two intents —
    // exactly the shape the prompt's example list describes (typed input, model
    // call, structured output), used here only to prove the primitive, not
    // shipped as a real Jarvis feature.
    private data class IntentInput(val utterance: String)
    private enum class Intent { QUESTION, COMMAND, UNKNOWN }

    private fun intentProgram() = modelProgram<IntentInput, Intent>(
        buildMessages = { input -> listOf(ChatMessage("system", "Classify as QUESTION or COMMAND"), ChatMessage("user", input.utterance)) },
        parseOutput = { _, raw ->
            when {
                raw.contains("QUESTION") -> Intent.QUESTION
                raw.contains("COMMAND") -> Intent.COMMAND
                else -> Intent.UNKNOWN
            }
        },
    )

    @Test
    fun `program builds messages from typed input and parses typed output`() {
        val llm = FakeLanguageModel(reply = "COMMAND")
        val program = intentProgram()

        val result = program.run(llm, IntentInput("turn off the lights"))

        assertEquals(Intent.COMMAND, result)
        assertTrue(llm.lastMessages.any { it.content.contains("turn off the lights") })
    }

    @Test
    fun `unparseable output falls back to the program's own default case`() {
        val llm = FakeLanguageModel(reply = "I'm not sure what you mean")
        val program = intentProgram()

        assertEquals(Intent.UNKNOWN, program.run(llm, IntentInput("mumble")))
    }

    @Test
    fun `generation failure surfaces as ModelProgramException not a silent default`() {
        val llm = FakeLanguageModel(failWith = "backend unavailable")
        val program = intentProgram()

        try {
            program.run(llm, IntentInput("anything"))
            fail("expected ModelProgramException")
        } catch (e: ModelProgramException) {
            assertEquals("backend unavailable", e.message)
        }
    }

    @Test
    fun `same program runs against different LanguageModel instances without changes`() {
        val program = intentProgram()
        val llmA = FakeLanguageModel(reply = "QUESTION")
        val llmB = FakeLanguageModel(reply = "COMMAND")

        assertEquals(Intent.QUESTION, program.run(llmA, IntentInput("what time is it")))
        assertEquals(Intent.COMMAND, program.run(llmB, IntentInput("set a timer")))
    }

    @Test
    fun `custom generation overrides are honored via the modelProgram builder`() {
        var capturedOverrides: GenerationOverrides? = null
        val llm = object : LanguageModel {
            override fun initialize(): Result<Unit> = Result.success(Unit)
            override fun generate(messages: List<ChatMessage>, sink: TokenSink, overrides: GenerationOverrides?) {
                capturedOverrides = overrides
                sink.onToken("QUESTION")
                sink.onComplete()
            }
            override fun cancel() {}
            override fun resetContext() {}
            override fun trimMemory() {}
            override fun release() {}
        }
        val program = modelProgram<IntentInput, Intent>(
            overrides = GenerationOverrides(temperature = 0f),
            buildMessages = { listOf(ChatMessage("user", it.utterance)) },
            parseOutput = { _, raw -> if (raw.contains("QUESTION")) Intent.QUESTION else Intent.UNKNOWN },
        )

        program.run(llm, IntentInput("test"))

        assertEquals(0f, capturedOverrides?.temperature)
    }
}
