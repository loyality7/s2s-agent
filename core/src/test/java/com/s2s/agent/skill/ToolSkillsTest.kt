package com.s2s.agent.skill

import com.s2s.mobile.pipeline.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolSkillsTest {
    private val catalogue = listOf(
        ToolDefinition("calculator", "Evaluates an arithmetic expression and returns the result."),
        ToolDefinition("torch", "Turns the phone flashlight on or off."),
    )

    private fun registry() = SkillRegistry().apply { registerToolSkills(catalogue) }

    @Test
    fun `a request narrows to the one matching tool`() {
        assertEquals(setOf("calculator"), registry().findRelevant("what is the result of 12 times 9")?.requiredTools)
        assertEquals(setOf("torch"), registry().findRelevant("turn on the flashlight")?.requiredTools)
    }

    @Test
    fun `an unrelated request matches no tool`() {
        assertNull(registry().findRelevant("hi, how are you"))
    }

    @Test
    fun `one skill is derived per tool`() {
        assertEquals(listOf("tool:calculator", "tool:torch"), registry().listMetadata().map { it.id })
    }
}
