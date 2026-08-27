package com.s2s.agent.agent

import androidx.test.core.app.ApplicationProvider
import com.s2s.agent.policy.ConfirmationDecision
import com.s2s.agent.policy.ConfirmationPolicy
import com.s2s.agent.policy.ExecutionBudget
import com.s2s.agent.policy.RetryPolicy
import com.s2s.agent.task.InMemoryTaskStore
import com.s2s.agent.task.TaskStore
import com.s2s.mobile.S2SEngine
import com.s2s.mobile.config.AudioConfig
import com.s2s.mobile.config.ModelPaths
import com.s2s.mobile.config.S2SConfig
import com.s2s.mobile.pipeline.AudioInput
import com.s2s.mobile.pipeline.SpeechRecognizer
import com.s2s.mobile.pipeline.SpeechSynthesizer
import com.s2s.mobile.pipeline.ToolDefinition
import com.s2s.mobile.pipeline.Transcript
import com.s2s.mobile.pipeline.Voice
import com.s2s.mobile.pipeline.VoiceActivityDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentRuntimeTest {

    private class FakeVad : VoiceActivityDetector {
        override val frameSize = 512
        override fun initialize() = Result.success(Unit)
        override fun accept(frame: FloatArray) = false
        override fun reset() {}
        override fun release() {}
    }

    private class FakeRecognizer : SpeechRecognizer {
        override fun initialize() = Result.success(Unit)
        override fun accept(frame: FloatArray): Transcript = Transcript.Nothing
        override fun reset() {}
        override fun release() {}
    }

    private class FakeSynthesizer : SpeechSynthesizer {
        override val sampleRate = 16000
        override val voices = listOf(Voice(0, "test"))
        val synthesizedTexts = mutableListOf<String>()
        override fun initialize() = Result.success(Unit)
        override fun selectVoice(voiceId: Int) {}
        override fun synthesize(text: String, keepGoing: () -> Boolean, onChunk: (FloatArray) -> Unit) {
            synthesizedTexts += text
            if (keepGoing()) onChunk(FloatArray(160) { 0.1f })
        }
        override fun release() {}
    }

    private class FakeMic : AudioInput {
        override val sampleRate = 16000
        override val frameSize = 512
        override fun start(onFrame: (FloatArray) -> Unit): Boolean = true
        override fun stop() {}
    }

    private suspend fun engine(llm: FakeLanguageModel, history: FakeContextEngine, synth: FakeSynthesizer): S2SEngine {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val e = S2SEngine(
            context,
            S2SConfig(
                models = ModelPaths(vadModel = "vad", sttDir = "stt", llmModel = "llm", ttsDir = "tts"),
                audio = AudioConfig(manageForegroundService = false, manageAudioFocus = false),
                warmUpOnInit = false,
            ),
            languageModel = llm,
            history = history,
            vad = FakeVad(),
            recognizer = FakeRecognizer(),
            synthesizer = synth,
            microphone = FakeMic(),
        )
        e.initialize().getOrThrow()
        return e
    }

    private fun calculatorTool() = ToolDefinition(name = "calculator", description = "adds numbers")

    private fun runtime(
        e: S2SEngine,
        llm: FakeLanguageModel,
        history: FakeContextEngine,
        tools: FakeTools,
        taskStore: TaskStore = InMemoryTaskStore(),
        budget: ExecutionBudget = ExecutionBudget(),
        retryPolicy: RetryPolicy = RetryPolicy.boundedRetry(2),
        confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.ALWAYS_EXECUTE,
    ) = AgentRuntime(e, llm, history, tools, taskStore, budget, retryPolicy, confirmationPolicy)

    @Test
    fun `simple answer takes exactly one generation`() = runBlocking {
        val llm = FakeLanguageModel(mutableListOf("2 + 2 is 4."))
        val history = FakeContextEngine("system")
        val synth = FakeSynthesizer()
        val e = engine(llm, history, synth)
        val rt = runtime(e, llm, history, FakeTools())

        val task = rt.run("what is 2 + 2?")
        Thread.sleep(200)

        assertEquals(AgentState.COMPLETED, task.state)
        assertEquals(1, llm.generateCallCount)
        assertTrue(synth.synthesizedTexts.any { it.contains("4") })
    }

    @Test
    fun `one tool call then final answer`() = runBlocking {
        val llm = FakeLanguageModel(
            mutableListOf(
                """{"tool": "calculator", "arguments": {}}""",
                "The result is 7.",
            ),
        )
        val history = FakeContextEngine("system")
        val synth = FakeSynthesizer()
        val e = engine(llm, history, synth)
        val tools = FakeTools().apply { register(calculatorTool()) { _, _ -> "7" } }
        val rt = runtime(e, llm, history, tools)

        val task = rt.run("add these numbers")
        Thread.sleep(200)

        assertEquals(AgentState.COMPLETED, task.state)
        assertEquals(2, llm.generateCallCount)
        assertEquals(1, tools.executedCalls.size)
        assertTrue(synth.synthesizedTexts.any { it.contains("7") })
    }

    @Test
    fun `multiple sequential tool calls`() = runBlocking {
        val llm = FakeLanguageModel(
            mutableListOf(
                """{"tool": "toolA", "arguments": {}}""",
                """{"tool": "toolB", "arguments": {}}""",
                "Done with both.",
            ),
        )
        val history = FakeContextEngine("system")
        val synth = FakeSynthesizer()
        val e = engine(llm, history, synth)
        val tools = FakeTools().apply {
            register(ToolDefinition("toolA", "a")) { _, _ -> "resultA" }
            register(ToolDefinition("toolB", "b")) { _, _ -> "resultB" }
        }
        val rt = runtime(e, llm, history, tools)

        val task = rt.run("do A then B")
        Thread.sleep(200)

        assertEquals(AgentState.COMPLETED, task.state)
        assertEquals(3, llm.generateCallCount)
        assertEquals(listOf("toolA", "toolB"), tools.executedCalls.map { it.name })
    }

    @Test
    fun `tool failure is recorded and generation continues`() = runBlocking {
        val llm = FakeLanguageModel(
            mutableListOf(
                """{"tool": "calculator", "arguments": {}}""",
                "The tool failed, here's what I know anyway.",
            ),
        )
        val history = FakeContextEngine("system")
        val synth = FakeSynthesizer()
        val e = engine(llm, history, synth)
        val tools = FakeTools().apply {
            register(calculatorTool()) { _, _ -> "unused" }
            failNext = true
        }
        val rt = runtime(e, llm, history, tools)

        val task = rt.run("calculate something")
        Thread.sleep(200)

        assertEquals(AgentState.COMPLETED, task.state)
        assertTrue(history.toolResults.single().second.contains("boom"))
    }

    @Test
    fun `LLM failure with no-retry policy fails the task`() = runBlocking {
        val llm = FakeLanguageModel(mutableListOf()).apply { failNextWith = "backend crashed" }
        val history = FakeContextEngine("system")
        val synth = FakeSynthesizer()
        val e = engine(llm, history, synth)
        val rt = runtime(e, llm, history, FakeTools(), retryPolicy = RetryPolicy.NEVER_RETRY)

        val task = rt.run("hello")
        Thread.sleep(200)

        assertEquals(AgentState.FAILED, task.state)
    }

    @Test
    fun `retry policy retries generation on failure`() = runBlocking {
        val llm = FakeLanguageModel(mutableListOf("finally, an answer")).apply { failNextWith = "transient" }
        val history = FakeContextEngine("system")
        val synth = FakeSynthesizer()
        val e = engine(llm, history, synth)
        val rt = runtime(e, llm, history, FakeTools(), retryPolicy = RetryPolicy.boundedRetry(2))

        val task = rt.run("hello")
        Thread.sleep(200)

        assertEquals(AgentState.COMPLETED, task.state)
        assertEquals(2, llm.generateCallCount)
    }

    @Test
    fun `max steps budget stops runaway loop`() = runBlocking {
        val replies = MutableList(20) { """{"tool": "calculator", "arguments": {}}""" }
        val llm = FakeLanguageModel(replies)
        val history = FakeContextEngine("system")
        val synth = FakeSynthesizer()
        val e = engine(llm, history, synth)
        val tools = FakeTools().apply { register(calculatorTool()) { _, _ -> "1" } }
        val rt = runtime(e, llm, history, tools, budget = ExecutionBudget(maxSteps = 3, maxToolCalls = 100))

        val task = rt.run("loop forever")
        Thread.sleep(200)

        assertEquals(AgentState.FAILED, task.state)
        assertTrue(task.stepCount <= 3)
    }

    @Test
    fun `max tool calls budget stops runaway loop`() = runBlocking {
        val replies = MutableList(20) { """{"tool": "calculator", "arguments": {}}""" }
        val llm = FakeLanguageModel(replies)
        val history = FakeContextEngine("system")
        val synth = FakeSynthesizer()
        val e = engine(llm, history, synth)
        val tools = FakeTools().apply { register(calculatorTool()) { _, _ -> "1" } }
        val rt = runtime(e, llm, history, tools, budget = ExecutionBudget(maxSteps = 100, maxToolCalls = 2))

        val task = rt.run("loop forever")
        Thread.sleep(200)

        assertEquals(AgentState.FAILED, task.state)
        assertTrue(task.toolCallCount <= 2)
    }

    @Test
    fun `cancel reaches LanguageModel cancel`() = runBlocking {
        val llm = FakeLanguageModel(mutableListOf("won't be seen"))
        val history = FakeContextEngine("system")
        val synth = FakeSynthesizer()
        val e = engine(llm, history, synth)
        val rt = runtime(e, llm, history, FakeTools())

        rt.cancel("some-task-id")

        assertEquals(1, llm.cancelCallCount)
    }

    @Test
    fun `session isolation keeps two tasks on distinct session ids`() = runBlocking {
        val llmA = FakeLanguageModel(mutableListOf("answer A"))
        val historyA = FakeContextEngine("system")
        val synthA = FakeSynthesizer()
        val engineA = engine(llmA, historyA, synthA)
        val runtimeA = runtime(engineA, llmA, historyA, FakeTools())

        val llmB = FakeLanguageModel(mutableListOf("answer B"))
        val historyB = FakeContextEngine("system")
        val synthB = FakeSynthesizer()
        val engineB = engine(llmB, historyB, synthB)
        val runtimeB = runtime(engineB, llmB, historyB, FakeTools())

        val taskA = runtimeA.run("A")
        val taskB = runtimeB.run("B")
        Thread.sleep(200)

        assertFalse(taskA.sessionId == taskB.sessionId)
    }

    @Test
    fun `tool result is recorded in context before next generation`() = runBlocking {
        val llm = FakeLanguageModel(
            mutableListOf(
                """{"tool": "calculator", "arguments": {}}""",
                "final",
            ),
        )
        val history = FakeContextEngine("system")
        val synth = FakeSynthesizer()
        val e = engine(llm, history, synth)
        val tools = FakeTools().apply { register(calculatorTool()) { _, _ -> "42" } }
        val rt = runtime(e, llm, history, tools)

        rt.run("calc")
        Thread.sleep(200)

        assertTrue(history.toolResults.any { it.second == "42" })
    }

    @Test
    fun `final response reaches speakAssistantText not raw tool call JSON`() = runBlocking {
        val llm = FakeLanguageModel(
            mutableListOf(
                """{"tool": "calculator", "arguments": {}}""",
                "The spoken final answer.",
            ),
        )
        val history = FakeContextEngine("system")
        val synth = FakeSynthesizer()
        val e = engine(llm, history, synth)
        val tools = FakeTools().apply { register(calculatorTool()) { _, _ -> "1" } }
        val rt = runtime(e, llm, history, tools)

        rt.run("calc")
        Thread.sleep(200)

        assertFalse(synth.synthesizedTexts.any { it.contains("\"tool\"") })
        assertTrue(synth.synthesizedTexts.any { it.contains("spoken final answer") })
    }

    @Test
    fun `confirmation required pauses task without executing the tool`() = runBlocking {
        val llm = FakeLanguageModel(mutableListOf("""{"tool": "calculator", "arguments": {}}"""))
        val history = FakeContextEngine("system")
        val synth = FakeSynthesizer()
        val e = engine(llm, history, synth)
        val tools = FakeTools().apply { register(calculatorTool()) { _, _ -> "1" } }
        val store = InMemoryTaskStore()
        val rt = runtime(
            e, llm, history, tools, taskStore = store,
            confirmationPolicy = ConfirmationPolicy { _, _ -> ConfirmationDecision.REQUIRE_CONFIRMATION },
        )

        val task = rt.run("calculate")
        Thread.sleep(200)

        assertEquals(AgentState.WAITING_FOR_CONFIRMATION, task.state)
        assertTrue(tools.executedCalls.isEmpty())
        assertEquals(AgentState.WAITING_FOR_CONFIRMATION, store.getTask(task.taskId)?.state)
    }

    @Test
    fun `resumeTask executes the pending tool and completes`() = runBlocking {
        val llm = FakeLanguageModel(
            mutableListOf(
                """{"tool": "calculator", "arguments": {}}""",
                "The result is 9.",
            ),
        )
        val history = FakeContextEngine("system")
        val synth = FakeSynthesizer()
        val e = engine(llm, history, synth)
        val tools = FakeTools().apply { register(calculatorTool()) { _, _ -> "9" } }
        val store = InMemoryTaskStore()
        val rt = runtime(
            e, llm, history, tools, taskStore = store,
            confirmationPolicy = ConfirmationPolicy { _, _ -> ConfirmationDecision.REQUIRE_CONFIRMATION },
        )

        val paused = rt.run("calculate")
        Thread.sleep(100)
        assertEquals(AgentState.WAITING_FOR_CONFIRMATION, paused.state)

        val resumed = rt.resumeTask(paused.taskId)
        Thread.sleep(200)

        assertEquals(AgentState.COMPLETED, resumed.state)
        assertEquals(1, tools.executedCalls.size)
        assertTrue(synth.synthesizedTexts.any { it.contains("9") })
    }

    @Test
    fun `resumeTask works from a task restored from the store alone`() = runBlocking {
        val llm = FakeLanguageModel(
            mutableListOf(
                """{"tool": "calculator", "arguments": {}}""",
                "Restored and done.",
            ),
        )
        val history = FakeContextEngine("system")
        val synth = FakeSynthesizer()
        val e = engine(llm, history, synth)
        val tools = FakeTools().apply { register(calculatorTool()) { _, _ -> "3" } }
        val store = InMemoryTaskStore()
        val rt = runtime(
            e, llm, history, tools, taskStore = store,
            confirmationPolicy = ConfirmationPolicy { _, _ -> ConfirmationDecision.REQUIRE_CONFIRMATION },
        )
        val paused = rt.run("calculate")
        Thread.sleep(100)

        // Simulate "process restarted": new runtime instance, same store — the
        // pending tool call must have traveled entirely through the persisted
        // AgentTask, not any in-memory state of the original runtime.
        val rt2 = runtime(e, llm, history, tools, taskStore = store)
        val resumed = rt2.resumeTask(paused.taskId)
        Thread.sleep(200)

        assertEquals(AgentState.COMPLETED, resumed.state)
    }

    @Test
    fun `rejectConfirmation fails the task without executing the tool`() = runBlocking {
        val llm = FakeLanguageModel(mutableListOf("""{"tool": "calculator", "arguments": {}}"""))
        val history = FakeContextEngine("system")
        val synth = FakeSynthesizer()
        val e = engine(llm, history, synth)
        val tools = FakeTools().apply { register(calculatorTool()) { _, _ -> "1" } }
        val store = InMemoryTaskStore()
        val rt = runtime(
            e, llm, history, tools, taskStore = store,
            confirmationPolicy = ConfirmationPolicy { _, _ -> ConfirmationDecision.REQUIRE_CONFIRMATION },
        )

        val paused = rt.run("calculate")
        Thread.sleep(100)
        val rejected = rt.rejectConfirmation(paused.taskId)

        assertEquals(AgentState.FAILED, rejected.state)
        assertTrue(tools.executedCalls.isEmpty())
    }

    @Test
    fun `confirmation reject policy fails immediately`() = runBlocking {
        val llm = FakeLanguageModel(mutableListOf("""{"tool": "calculator", "arguments": {}}"""))
        val history = FakeContextEngine("system")
        val synth = FakeSynthesizer()
        val e = engine(llm, history, synth)
        val tools = FakeTools().apply { register(calculatorTool()) { _, _ -> "1" } }
        val rt = runtime(
            e, llm, history, tools,
            confirmationPolicy = ConfirmationPolicy { _, _ -> ConfirmationDecision.REJECT },
        )

        val task = rt.run("calculate")
        Thread.sleep(200)

        assertEquals(AgentState.FAILED, task.state)
        assertTrue(tools.executedCalls.isEmpty())
    }

    @Test
    fun `task checkpoints exist in the store after completion`() = runBlocking {
        val llm = FakeLanguageModel(mutableListOf("final answer"))
        val history = FakeContextEngine("system")
        val synth = FakeSynthesizer()
        val e = engine(llm, history, synth)
        val store = InMemoryTaskStore()
        val rt = runtime(e, llm, history, FakeTools(), taskStore = store)

        val task = rt.run("hi")
        Thread.sleep(200)

        val stored = store.getTask(task.taskId)
        assertEquals(AgentState.COMPLETED, stored?.state)
    }
}
