package com.s2s.agent.verify

/** Narrow input to one verification — not the full conversation, not chain-of-thought. */
data class VerificationRequest(
    val objective: String,
    val actionDescription: String,
    val result: String,
    val successCriteria: List<String>,
)

enum class VerificationVerdict { VERIFIED, NOT_VERIFIED, RETRY, ASK_USER }

data class VerificationOutcome(val verdict: VerificationVerdict, val reason: String)

/**
 * Checks a completed action against explicit criteria — never "does this
 * seem right?" self-review. A verifier does not need the history of how the
 * artifact was built, only whether [VerificationRequest.result] satisfies
 * [VerificationRequest.successCriteria].
 *
 * Two implementations ship here: [DeterministicVerifier] for criteria that
 * are plain substring/state checks (no model call needed — cheapest,
 * correct choice whenever it applies), and a model-backed one is left to the
 * host to supply via [ModelVerifier] since it needs a [com.s2s.mobile.pipeline.LanguageModel]
 * reference the core module has no opinion on constructing.
 */
fun interface Verifier {
    fun verify(request: VerificationRequest): VerificationOutcome
}

/**
 * Checks that [VerificationRequest.result] contains every criterion string —
 * covers the common case ("recipient correct", "message ID returned") without
 * a model call. Falls through to [VerificationVerdict.NOT_VERIFIED], never
 * [VerificationVerdict.RETRY]/[VerificationVerdict.ASK_USER] — those require
 * judgment this class doesn't have.
 */
object DeterministicVerifier : Verifier {
    override fun verify(request: VerificationRequest): VerificationOutcome {
        val missing = request.successCriteria.filterNot { request.result.contains(it, ignoreCase = true) }
        return if (missing.isEmpty()) {
            VerificationOutcome(VerificationVerdict.VERIFIED, "All criteria present in result")
        } else {
            VerificationOutcome(VerificationVerdict.NOT_VERIFIED, "Missing: ${missing.joinToString(", ")}")
        }
    }
}

/**
 * Model-backed verification for criteria too fuzzy for substring matching.
 * Uses a single [com.s2s.mobile.pipeline.LanguageModel.generate] call with a
 * narrow prompt containing only [VerificationRequest] fields — never the
 * conversation history, never chain-of-thought.
 */
class ModelVerifier(
    private val languageModel: com.s2s.mobile.pipeline.LanguageModel,
) : Verifier {
    override fun verify(request: VerificationRequest): VerificationOutcome {
        val prompt = buildString {
            appendLine("Objective: ${request.objective}")
            appendLine("Action taken: ${request.actionDescription}")
            appendLine("Result: ${request.result}")
            appendLine("Success criteria:")
            request.successCriteria.forEach { appendLine("- $it") }
            appendLine("Reply with exactly one word: VERIFIED, NOT_VERIFIED, RETRY, or ASK_USER.")
        }

        val reply = StringBuilder()
        val done = java.util.concurrent.CountDownLatch(1)
        languageModel.generate(
            listOf(com.s2s.mobile.pipeline.ChatMessage("system", prompt)),
            object : com.s2s.mobile.pipeline.TokenSink {
                override fun onToken(text: String) {
                    reply.append(text)
                }
                override fun onComplete() = done.countDown()
                override fun onError(message: String, cause: Throwable?) = done.countDown()
            },
        )
        done.await()

        val verdict = VerificationVerdict.entries.firstOrNull { reply.toString().contains(it.name) }
            ?: VerificationVerdict.NOT_VERIFIED
        return VerificationOutcome(verdict, reply.toString().trim())
    }
}
