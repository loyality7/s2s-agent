package com.s2s.agent.policy

/** Normalized reason a step failed, so policy can differentiate rather than treat every failure alike. */
enum class FailureKind { NETWORK, TIMEOUT, INVALID_ARGUMENTS, PERMISSION_DENIED, UNAVAILABLE, APPLICATION_ERROR, CANCELLED }

/** What to do after a tool or generation failure. Bounded — never retries forever. */
enum class RetryDecision { RETRY, FALLBACK, ASK_USER, FAIL }

/**
 * Failure-handling policy, kept as a small pluggable abstraction rather than
 * hardcoded throughout the execution loop. Maps (attempt count, failure kind)
 * to a decision — a deterministic table, not a "recovery agent".
 *
 * ponytail: fixed max-retries counter, no backoff curve — add one if a real
 * tool backend needs it.
 */
fun interface RetryPolicy {
    fun decide(attempt: Int, kind: FailureKind, error: Throwable?): RetryDecision

    companion object {
        fun boundedRetry(maxAttempts: Int) = RetryPolicy { attempt, kind, _ ->
            when {
                kind == FailureKind.CANCELLED -> RetryDecision.FAIL
                kind == FailureKind.PERMISSION_DENIED -> RetryDecision.ASK_USER
                // A rate limit or an overloaded server is the one failure where
                // retrying immediately makes things worse: a real device saw
                // HTTP 429 three times in 900ms, each retry fast enough to earn
                // the next 429, and the user got no answer. There is no backoff
                // curve here (see the class note), so the honest decision is to
                // stop and say so rather than spend the step budget.
                kind == FailureKind.UNAVAILABLE -> RetryDecision.FAIL
                kind == FailureKind.INVALID_ARGUMENTS && attempt < 1 -> RetryDecision.RETRY
                attempt < maxAttempts -> RetryDecision.RETRY
                else -> RetryDecision.FAIL
            }
        }

        val NEVER_RETRY = RetryPolicy { _, _, _ -> RetryDecision.FAIL }
    }
}
