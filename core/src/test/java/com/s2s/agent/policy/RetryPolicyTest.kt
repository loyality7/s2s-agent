package com.s2s.agent.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryPolicyTest {
    private val policy = RetryPolicy.boundedRetry(maxAttempts = 3)

    @Test
    fun `a rate limit is not retried`() {
        assertEquals(RetryDecision.FAIL, policy.decide(0, FailureKind.UNAVAILABLE, null))
    }

    @Test
    fun `an ordinary application error is still retried within budget`() {
        assertEquals(RetryDecision.RETRY, policy.decide(0, FailureKind.APPLICATION_ERROR, null))
        assertEquals(RetryDecision.FAIL, policy.decide(3, FailureKind.APPLICATION_ERROR, null))
    }

    @Test
    fun `cancellation and permission failures keep their decisions`() {
        assertEquals(RetryDecision.FAIL, policy.decide(0, FailureKind.CANCELLED, null))
        assertEquals(RetryDecision.ASK_USER, policy.decide(0, FailureKind.PERMISSION_DENIED, null))
    }
}
