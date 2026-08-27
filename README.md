# s2s-agent

Agent Harness for [speech-to-speech-mobile](https://github.com/loyality7/speech-to-speech-mobile).
Model = Model + Harness: this repo is the harness — state, context selection,
tools, feedback, continuity, execution control, recovery, verification,
permissions, skills, durable tasks, observability — around whatever
`LanguageModel` a host supplies.

## Packages

- `agent` — `AgentRuntime` (the execution loop), `AgentTask`/`AgentState`
  (durable snapshot), `AgentDecision`, `AgentEvent`
- `task` — `TaskStore` interface, `InMemoryTaskStore`, `FileTaskStore`
  (disk-backed, survives process death)
- `tool` — `ToolCoordinator` (dispatch, progressive tool exposure,
  oversized-result offloading)
- `skill` — `SkillRegistry`, `Skill`/`SkillMetadata` (staged metadata→body
  loading)
- `verify` — `Verifier`, `DeterministicVerifier`, `ModelVerifier`
- `policy` — `RetryPolicy`, `ConfirmationPolicy`, `ExecutionBudget`
- `middleware` — `AgentMiddleware` (before/after model, before/after tool)
- `trace` — `AgentTracer`, `TraceRecord`

Depends only on the published contracts (`LanguageModel`, `ContextEngine`,
`Tools`) from `speech-to-speech-mobile:1.0.4` — no dependency on any specific
`s2s-llm` backend, `s2s-context` implementation, or `s2s-tools` registry.

```kotlin
val runtime = AgentRuntime(
    engine = s2sEngine,
    languageModel = languageModel,
    context = contextEngine,
    tools = tools,
    taskStore = FileTaskStore(File(context.filesDir, "agent-tasks")),
)
val task = runtime.run("what's the weather, and should I bring an umbrella?")

// A tool the confirmation policy flagged:
if (task.state == AgentState.WAITING_FOR_CONFIRMATION) {
    // ask the user, out loud, then:
    runtime.resumeTask(task.taskId)   // or runtime.rejectConfirmation(task.taskId)
}
```

## Confirmation pause/resume

`AgentTask` is a persisted value snapshot, not a live object — `run()`
returns as soon as a tool needs confirmation, after checkpointing the pending
`ToolCall` into `taskStore`. `resumeTask(taskId)` re-reads that snapshot (a
fresh `AgentRuntime` instance works too — the pending call travels entirely
through the store, not through any runtime-held state) and continues the
loop from there. The process can die while a task sits in
`WAITING_FOR_CONFIRMATION`; nothing is lost.

## Skill-gated tool exposure

`AgentRuntime` accepts an optional `skills: SkillRegistry?`. When set,
`run(request)` calls `skills.findRelevant(request)` once at task start and
stores the match's `requiredTools` on the `AgentTask` itself
(`visibleToolNames`) — so the narrowed tool set survives a
confirmation-pause/resume round trip, including through `FileTaskStore`,
without re-matching. No skill registry configured (the default) exposes
every registered tool, same as before.

## Known limitations

- `ToolDefinition` carries no risk/capability metadata, so `ConfirmationPolicy`
  is keyed by tool name/pattern in the host's own policy rather than anything
  the `Tools` contract exposes. A genuine gap, not fixed by touching core —
  extending `ToolDefinition` would need a `speech-to-speech-mobile` change
  that isn't required for the harness to work.
- No true parallel tool-call execution. The current tool-call shape
  (`ToolRegistry.parse()`) produces one call per generation, so the loop is
  sequential — correct for what the contract actually returns today.
- `SkillRegistry.findRelevant` is a placeholder substring matcher, explicitly
  documented as replaceable — not a claim that it's a good relevance
  strategy.
- Background/long-running tasks (§19 of the harness spec — "keep watching
  the price") are supported by `TaskStore` surviving process death, but there
  is no scheduler/trigger mechanism yet; a host must re-poll or re-invoke a
  task itself. Deliberately out of scope — the harness separates execution
  state from Android process/lifecycle scheduling, which a future host layer
  (WorkManager, foreground service, etc.) owns.
- Interruption policy (cancel vs. let finish in background vs. ignore) is not
  implemented as a distinct decision table; `AgentRuntime.cancel()` is a
  blunt stop. The nuance belongs to whatever surfaces a `UserInterrupted`
  signal from the speech layer, which doesn't exist yet.
- No sandboxing of tool/skill capabilities — every registered `Tools`
  implementation is trusted equally today. Deliberately deferred: Android's
  own app sandbox plus least-privilege host wiring covers V1; a
  plugin-isolation boundary is a future architecture phase once installable
  plugins/MCP exist, not a `SandboxManager` bolted onto this harness now.

Not published to JitPack yet.
