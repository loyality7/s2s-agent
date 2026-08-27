package com.s2s.agent.task

import com.s2s.agent.agent.AgentState
import com.s2s.agent.agent.AgentTask
import com.s2s.mobile.pipeline.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TaskStoreTest {

    private fun task(id: String = "t1", sessionId: String = "s1") = AgentTask(
        taskId = id,
        sessionId = sessionId,
        objective = "do something",
        createdAtMs = 1000L,
    )

    @Test
    fun `in-memory store round-trips a task`() {
        val store = InMemoryTaskStore()
        store.createTask(task())
        assertEquals("t1", store.getTask("t1")?.taskId)
    }

    @Test
    fun `in-memory listTasks filters by session`() {
        val store = InMemoryTaskStore()
        store.createTask(task("t1", "sessionA"))
        store.createTask(task("t2", "sessionB"))
        assertEquals(listOf("t1"), store.listTasks("sessionA").map { it.taskId })
    }

    @Test
    fun `in-memory deleteTask removes it`() {
        val store = InMemoryTaskStore()
        store.createTask(task())
        store.deleteTask("t1")
        assertNull(store.getTask("t1"))
    }

    @Test
    fun `file store round-trips a task including pending tool call`() {
        val dir = File.createTempFile("taskstore", "").apply { delete(); mkdirs() }
        val store = FileTaskStore(dir)
        val withPending = task().copy(
            state = AgentState.WAITING_FOR_CONFIRMATION,
            pendingToolCall = ToolCall("calculator", mapOf("a" to "1", "b" to "2")),
            pendingCallId = "call-1",
            stepCount = 2,
            toolCallCount = 1,
            retryCount = 1,
            lastError = null,
        )
        store.createTask(withPending)

        val restored = store.getTask("t1")
        assertEquals(AgentState.WAITING_FOR_CONFIRMATION, restored?.state)
        assertEquals("calculator", restored?.pendingToolCall?.name)
        assertEquals(mapOf("a" to "1", "b" to "2"), restored?.pendingToolCall?.arguments)
        assertEquals("call-1", restored?.pendingCallId)
        assertEquals(2, restored?.stepCount)
    }

    @Test
    fun `file store survives across store instances (process-death simulation)`() {
        val dir = File.createTempFile("taskstore", "").apply { delete(); mkdirs() }
        FileTaskStore(dir).createTask(task())

        val secondInstance = FileTaskStore(dir)
        assertEquals("t1", secondInstance.getTask("t1")?.taskId)
    }

    @Test
    fun `file store handles lastError with special characters`() {
        val dir = File.createTempFile("taskstore", "").apply { delete(); mkdirs() }
        val store = FileTaskStore(dir)
        store.createTask(task().copy(lastError = "failed: \"quoted\" and\nnewline"))

        assertEquals("failed: \"quoted\" and\nnewline", store.getTask("t1")?.lastError)
    }

    @Test
    fun `file store deleteTask removes the file`() {
        val dir = File.createTempFile("taskstore", "").apply { delete(); mkdirs() }
        val store = FileTaskStore(dir)
        store.createTask(task())
        store.deleteTask("t1")

        assertNull(store.getTask("t1"))
        assertTrue(dir.listFiles()?.isEmpty() ?: true)
    }
}
