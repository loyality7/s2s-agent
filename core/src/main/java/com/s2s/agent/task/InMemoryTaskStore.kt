package com.s2s.agent.task

import com.s2s.agent.agent.AgentTask
import java.util.concurrent.ConcurrentHashMap

/** Process-lifetime only — for tests and hosts that accept losing task state on process death. */
class InMemoryTaskStore : TaskStore {
    private val tasks = ConcurrentHashMap<String, AgentTask>()

    override fun createTask(task: AgentTask) {
        tasks[task.taskId] = task
    }

    override fun getTask(taskId: String): AgentTask? = tasks[taskId]

    override fun updateTask(task: AgentTask) {
        tasks[task.taskId] = task
    }

    override fun listTasks(sessionId: String?): List<AgentTask> =
        tasks.values.filter { sessionId == null || it.sessionId == sessionId }

    override fun deleteTask(taskId: String) {
        tasks.remove(taskId)
    }
}
