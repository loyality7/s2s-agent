package com.s2s.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRegistryTest {

    private fun weeklyUpdateSkill() = Skill(
        metadata = SkillMetadata(
            id = "weekly-update",
            name = "weekly update",
            description = "Creates weekly project updates.",
            requiredTools = setOf("calendar", "docs"),
        ),
        instructions = "Gather this week's commits, summarize, format as bullet points.",
    )

    @Test
    fun `metadata discovery returns only metadata not full body`() {
        val registry = SkillRegistry().apply { register(weeklyUpdateSkill()) }

        val metadata = registry.listMetadata()
        assertEquals(1, metadata.size)
        assertEquals("weekly-update", metadata.first().id)
    }

    @Test
    fun `skill loading returns the full body only on request`() {
        val registry = SkillRegistry().apply { register(weeklyUpdateSkill()) }

        val loaded = registry.load("weekly-update")
        assertTrue(loaded!!.instructions.contains("bullet points"))
    }

    @Test
    fun `unregistered skill id returns null`() {
        val registry = SkillRegistry()
        assertNull(registry.load("nonexistent"))
    }

    @Test
    fun `relevant skill selection matches request against metadata`() {
        val registry = SkillRegistry().apply { register(weeklyUpdateSkill()) }

        val match = registry.findRelevant("can you do the weekly update for me")
        assertEquals("weekly-update", match?.id)
    }

    @Test
    fun `irrelevant request finds no skill`() {
        val registry = SkillRegistry().apply { register(weeklyUpdateSkill()) }

        assertNull(registry.findRelevant("what's the capital of France"))
    }

    @Test
    fun `skill-specific tool exposure carries required tools in metadata`() {
        val registry = SkillRegistry().apply { register(weeklyUpdateSkill()) }

        val meta = registry.listMetadata().first()
        assertEquals(setOf("calendar", "docs"), meta.requiredTools)
    }
}
