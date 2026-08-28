package com.s2s.agent.program

import com.s2s.mobile.pipeline.ChatMessage
import com.s2s.mobile.pipeline.GenerationOverrides
import com.s2s.mobile.pipeline.LanguageModel
import com.s2s.mobile.pipeline.TokenSink
import java.util.concurrent.CountDownLatch

/**
 * A reusable, typed model operation — the DSPy "signature" idea translated
 * to Kotlin: instead of a raw prompt string scattered at a call site, a
 * [ModelProgram] declares its input, its instructions, and how to turn the
 * model's raw text into a structured [Output].
 *
 * This is deliberately a thin wrapper around [LanguageModel.generate], not a
 * replacement for it — [LanguageModel] remains the only low-level model
 * interface. A `ModelProgram` is what a caller reaches for when the same
 * kind of model call (build a prompt from typed input, parse typed output)
 * would otherwise be duplicated or inlined as a raw string at multiple call
 * sites. `AgentRuntime`'s own generation step is NOT rewritten to use this —
 * it has exactly one call site and no reuse pressure, so wrapping it here
 * would be exactly the "abstraction without a use case" this whole
 * architecture explicitly avoids. `ModelProgram` exists for the next
 * reusable model operation (intent classification, entity extraction,
 * confirmation-required decision, summarization, skill selection) — each of
 * those is a genuinely repeatable (input shape, instructions, output shape)
 * triple, unlike the agent loop's own single generation call.
 */
fun interface ModelProgram<Input, Output> {
    /** Runs this program against [languageModel] for [input], returning the parsed [Output]. Blocks the calling thread, same contract as [LanguageModel.generate] itself. */
    fun run(languageModel: LanguageModel, input: Input): Output
}

/**
 * Builds a [ModelProgram] from three plain functions — no interface to
 * implement, no base class to extend. Most programs are exactly this shape:
 * turn [Input] into messages, run the model once, parse the raw reply into
 * [Output].
 */
fun <Input, Output> modelProgram(
    overrides: GenerationOverrides? = null,
    buildMessages: (Input) -> List<ChatMessage>,
    parseOutput: (Input, String) -> Output,
): ModelProgram<Input, Output> = ModelProgram { languageModel, input ->
    val messages = buildMessages(input)
    val reply = StringBuilder()
    var error: String? = null
    val done = CountDownLatch(1)

    languageModel.generate(
        messages,
        object : TokenSink {
            override fun onToken(text: String) {
                reply.append(text)
            }
            override fun onComplete() = done.countDown()
            override fun onError(message: String, cause: Throwable?) {
                error = message
                done.countDown()
            }
        },
        overrides,
    )
    done.await()

    error?.let { throw ModelProgramException(it) }
    parseOutput(input, reply.toString())
}

/** Thrown when the underlying [LanguageModel.generate] call fails — the caller decides whether that's retryable, same as any other model-call failure. */
class ModelProgramException(message: String) : Exception(message)
