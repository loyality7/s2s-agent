package com.s2s.agent.tool

import com.s2s.agent.agent.FakeContextEngine
import com.s2s.agent.agent.FakeTools
import com.s2s.mobile.pipeline.ToolCall
import com.s2s.mobile.pipeline.ToolContext
import com.s2s.mobile.pipeline.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCoordinatorTest {

    @Test
    fun `promptSectionFor with null shows every tool`() {
        val tools = FakeTools().apply {
            register(ToolDefinition("a", "tool a")) { _, _ -> "" }
            register(ToolDefinition("b", "tool b")) { _, _ -> "" }
        }
        val coordinator = ToolCoordinator(tools, FakeContextEngine("sys"))

        val section = coordinator.promptSectionFor(null)
        assertTrue(section!!.contains("a"))
        assertTrue(section.contains("b"))
    }

    @Test
    fun `promptSectionFor narrows to visible tool names only`() {
        val tools = FakeTools().apply {
            register(ToolDefinition("calculator", "adds numbers")) { _, _ -> "" }
            register(ToolDefinition("browser", "browses the web")) { _, _ -> "" }
        }
        val coordinator = ToolCoordinator(tools, FakeContextEngine("sys"))

        val section = coordinator.promptSectionFor(setOf("calculator"))
        assertTrue(section!!.contains("calculator"))
        assertTrue(!section.contains("browser"))
    }

    @Test
    fun `promptSectionFor with empty visible set returns null`() {
        val tools = FakeTools().apply { register(ToolDefinition("a", "tool a")) { _, _ -> "" } }
        val coordinator = ToolCoordinator(tools, FakeContextEngine("sys"))

        assertNull(coordinator.promptSectionFor(emptySet()))
    }

    @Test
    fun `execute records result in context and returns it inline when small`() {
        val tools = FakeTools().apply { register(ToolDefinition("calculator", "adds")) { _, _ -> "42" } }
        val history = FakeContextEngine("sys")
        val coordinator = ToolCoordinator(tools, history)

        val result = coordinator.execute(ToolCall("calculator", emptyMap()), ToolContext("s", "t", "c"))

        assertEquals("42", result.output)
        assertTrue(history.toolResults.any { it.second == "42" })
    }

    @Test
    fun `execute truncates oversized output but still stores the full result in context`() {
        val bigOutput = "x".repeat(5_000)
        val tools = FakeTools().apply { register(ToolDefinition("search", "searches")) { _, _ -> bigOutput } }
        val history = FakeContextEngine("sys")
        val coordinator = ToolCoordinator(tools, history, inlineSizeLimit = 4_000)

        val result = coordinator.execute(ToolCall("search", emptyMap()), ToolContext("s", "t", "c"))

        assertTrue(result.output.length < bigOutput.length)
        assertTrue(result.output.contains("truncated"))
        // Full untruncated output still lands in context — offloading, not data loss.
        assertEquals(bigOutput, history.toolResults.single().second)
    }
}
