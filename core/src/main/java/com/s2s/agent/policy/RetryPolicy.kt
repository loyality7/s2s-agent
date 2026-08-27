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
                kind == FailureKind.INVALID_ARGUMENTS && attempt < 1 -> RetryDecision.RETRY
                attempt < maxAttempts -> RetryDecision.RETRY
                else -> RetryDecision.FAIL
            }
        }

        val NEVER_RETRY = RetryPolicy { _, _, _ -> RetryDecision.FAIL }
    }
}
