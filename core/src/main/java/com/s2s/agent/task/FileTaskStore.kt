package com.s2s.agent.task

import com.s2s.agent.agent.AgentState
import com.s2s.agent.agent.AgentTask
import com.s2s.mobile.pipeline.ToolCall
import java.io.File

/**
 * One JSON file per task under [directory]. Survives process death — the
 * whole point of [TaskStore]: a task in [AgentState.WAITING_FOR_CONFIRMATION]
 * must still be there after the app restarts.
 *
 * ponytail: file-per-task + directory scan, no index/DB. Fine at the task
 * counts a personal voice agent produces; move to SQLite (s2s-context already
 * proves the pattern) if [listTasks] ever needs to scale past a few hundred.
 *
 * Hand-rolled JSON, not `org.json` — android.jar's copy is a stub that throws
 * on the plain JVM, same reason `ToolRegistry` in `s2s-tools` avoids it.
 */
class FileTaskStore(private val directory: File) : TaskStore {
    init {
        directory.mkdirs()
    }

    private fun fileFor(taskId: String) = File(directory, "$taskId.json")

    override fun createTask(task: AgentTask) = writeTask(task)

    override fun getTask(taskId: String): AgentTask? {
        val file = fileFor(taskId)
        if (!file.exists()) return null
        return runCatching { parseTask(file.readText()) }.getOrNull()
    }

    override fun updateTask(task: AgentTask) = writeTask(task)

    override fun listTasks(sessionId: String?): List<AgentTask> =
        directory.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { parseTask(it.readText()) }.getOrNull() }
            ?.filter { sessionId == null || it.sessionId == sessionId }
            ?: emptyList()

    override fun deleteTask(taskId: String) {
        fileFor(taskId).delete()
    }

    private fun writeTask(task: AgentTask) {
        fileFor(task.taskId).writeText(toJson(task))
    }

    private fun toJson(task: AgentTask): String = buildString {
        append("{")
        append("\"taskId\":").append(quote(task.taskId)).append(",")
        append("\"sessionId\":").append(quote(task.sessionId)).append(",")
        append("\"objective\":").append(quote(task.objective)).append(",")
        append("\"state\":").append(quote(task.state.name)).append(",")
        append("\"stepCount\":").append(task.stepCount).append(",")
        append("\"toolCallCount\":").append(task.toolCallCount).append(",")
        append("\"createdAtMs\":").append(task.createdAtMs).append(",")
        append("\"updatedAtMs\":").append(task.updatedAtMs).append(",")
        append("\"retryCount\":").append(task.retryCount).append(",")
        append("\"lastError\":").append(task.lastError?.let { quote(it) } ?: "null").append(",")
        append("\"pendingCallId\":").append(task.pendingCallId?.let { quote(it) } ?: "null").append(",")
        append("\"pendingToolName\":").append(task.pendingToolCall?.name?.let { quote(it) } ?: "null").append(",")
        append("\"pendingToolArgs\":").append(mapToJson(task.pendingToolCall?.arguments ?: emptyMap())).append(",")
        append("\"visibleToolNames\":").append(task.visibleToolNames?.let { setToJson(it) } ?: "null")
        append("}")
    }

    private fun mapToJson(map: Map<String, String>): String =
        map.entries.joinToString(",", "{", "}") { (k, v) -> "${quote(k)}:${quote(v)}" }

    private fun setToJson(set: Set<String>): String =
        set.joinToString(",", "[", "]") { quote(it) }

    private fun quote(s: String): String = "\"" + s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n") + "\""

    /** Reads the flat top-level fields this store itself writes — not a general JSON parser. */
    internal fun parseTask(json: String): AgentTask {
        val fields = readFlatFields(json)
        val pendingArgsStart = json.indexOf("\"pendingToolArgs\":")
        val pendingArgs = if (pendingArgsStart >= 0) {
            readFlatFields(json.substring(json.indexOf('{', pendingArgsStart)))
        } else {
            emptyMap()
        }

        val toolName = fields["pendingToolName"]
        val pendingCall = if (toolName != null) ToolCall(toolName, pendingArgs) else null

        val visibleStart = json.indexOf("\"visibleToolNames\":")
        val visibleToolNames = if (visibleStart >= 0) {
            val bracket = json.indexOf('[', visibleStart)
            val nullAt = json.indexOf("null", visibleStart)
            val isNull = nullAt in visibleStart until (if (bracket >= 0) bracket else Int.MAX_VALUE)
            if (isNull || bracket < 0) {
                null
            } else {
                val close = json.indexOf(']', bracket)
                readStringArray(json.substring(bracket, close + 1))
            }
        } else {
            null
        }

        return AgentTask(
            taskId = fields.getValue("taskId"),
            sessionId = fields.getValue("sessionId"),
            objective = fields.getValue("objective"),
            state = AgentState.valueOf(fields.getValue("state")),
            stepCount = fields["stepCount"]?.toIntOrNull() ?: 0,
            toolCallCount = fields["toolCallCount"]?.toIntOrNull() ?: 0,
            createdAtMs = fields.getValue("createdAtMs").toLong(),
            updatedAtMs = fields["updatedAtMs"]?.toLongOrNull() ?: 0L,
            retryCount = fields["retryCount"]?.toIntOrNull() ?: 0,
            lastError = fields["lastError"],
            pendingToolCall = pendingCall,
            pendingCallId = fields["pendingCallId"],
            visibleToolNames = visibleToolNames,
        )
    }

    /** Reads a bracketed, comma-separated list of quoted strings — `["a","b"]` / `[]`. */
    private fun readStringArray(json: String): Set<String> {
        val out = linkedSetOf<String>()
        var i = 0
        while (i < json.length) {
            if (json[i] == '"') {
                val end = endOfString(json, i) ?: break
                out += unescape(json.substring(i + 1, end))
                i = end + 1
            } else {
                i++
            }
        }
        return out
    }

    /** Reads top-level `"key": "value"|number|null` pairs of one JSON object, stopping at the first nested object. */
    private fun readFlatFields(json: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var i = 0
        var depth = 0
        while (i < json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return out }
                '"' -> {
                    if (depth != 1) { i = skipString(json, i); continue }
                    val keyEnd = endOfString(json, i) ?: return out
                    val key = json.substring(i + 1, keyEnd)
                    var j = keyEnd + 1
                    while (j < json.length && (json[j] == ':' || json[j].isWhitespace())) j++
                    if (j >= json.length) return out
                    when (json[j]) {
                        '"' -> {
                            val valueEnd = endOfString(json, j) ?: return out
                            out[key] = unescape(json.substring(j + 1, valueEnd))
                            i = valueEnd + 1
                        }
                        '{' -> { i = j; continue }
                        else -> {
                            var k = j
                            while (k < json.length && json[k] != ',' && json[k] != '}') k++
                            val raw = json.substring(j, k).trim()
                            if (raw != "null") out[key] = raw
                            i = k
                        }
                    }
                    continue
                }
            }
            i++
        }
        return out
    }

    private fun endOfString(s: String, quote: Int): Int? {
        var i = quote + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return null
    }

    private fun skipString(s: String, quote: Int): Int = (endOfString(s, quote) ?: (s.length - 1)) + 1

    // Kept identical to s2s-tools' ToolRegistry.unescape — same minimal JSON
    // parser, independently written because s2s-agent and s2s-tools do not
    // depend on each other by design (see this file's class doc: org.json is
    // stubbed under plain-JVM unit tests, so both repos hand-roll the same
    // narrow parser rather than share a module across a boundary that
    // otherwise has no reason to exist). A ponytail audit found this copy had
    // drifted — missing the \t case the other copy already handled.
    private fun unescape(s: String): String = s
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}
