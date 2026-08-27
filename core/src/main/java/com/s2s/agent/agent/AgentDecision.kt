package com.s2s.agent.agent

import com.s2s.mobile.pipeline.ToolCall
import com.s2s.agent.policy.FailureKind

/**
 * What the harness decided a completed [com.s2s.mobile.pipeline.LanguageModel.generate]
 * call's raw output means, after running it through [com.s2s.mobile.pipeline.Tools.parse].
 * Never leaks a provider-specific response type — every [com.s2s.mobile.pipeline.LanguageModel]
 * backend produces plain text; this is the harness's own interpretation of it.
 */
sealed interface AgentDecision {
    data class FinalResponse(val text: String) : AgentDecision
    data class ToolInvocation(val call: ToolCall) : AgentDecision
    data class Failure(val message: String, val kind: FailureKind, val cause: Throwable? = null) : AgentDecision
}
