package com.s2s.agent.verify

import org.junit.Assert.assertEquals
import org.junit.Test

class VerifierTest {

    @Test
    fun `deterministic verifier passes when all criteria present`() {
        val outcome = DeterministicVerifier.verify(
            VerificationRequest(
                objective = "send message",
                actionDescription = "sent via provider",
                result = "message accepted, id=msg_123, recipient=alice@example.com",
                successCriteria = listOf("accepted", "msg_123", "alice@example.com"),
            ),
        )
        assertEquals(VerificationVerdict.VERIFIED, outcome.verdict)
    }

    @Test
    fun `deterministic verifier fails when a criterion is missing`() {
        val outcome = DeterministicVerifier.verify(
            VerificationRequest(
                objective = "send message",
                actionDescription = "sent via provider",
                result = "message accepted, id=msg_123",
                successCriteria = listOf("accepted", "msg_123", "recipient=alice@example.com"),
            ),
        )
        assertEquals(VerificationVerdict.NOT_VERIFIED, outcome.verdict)
        assertEquals(true, outcome.reason.contains("recipient"))
    }

    @Test
    fun `deterministic verifier with no criteria always verifies`() {
        val outcome = DeterministicVerifier.verify(
            VerificationRequest("obj", "action", "result", emptyList()),
        )
        assertEquals(VerificationVerdict.VERIFIED, outcome.verdict)
    }
}
