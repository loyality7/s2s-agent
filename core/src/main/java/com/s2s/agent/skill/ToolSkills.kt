package com.s2s.agent.skill

import com.s2s.mobile.pipeline.ToolDefinition

/**
 * Derives one skill per registered tool, so the prompt carries only the tools
 * whose own words match the request instead of the whole catalogue.
 *
 * Why derive instead of authoring skills by hand: the tool catalogue is not
 * known at build time — it grows every time a user installs a plugin — so
 * hand-written skills would either go stale or force the host to know plugin
 * names. A [ToolDefinition] already states a name and a description, which is
 * exactly the two fields [SkillMetadata] needs for relevance matching, so the
 * mapping needs no new authoring surface and no host-side hardcoding.
 *
 * What this costs: relevance is substring matching over the tool's own
 * description (see [SkillRegistry.findRelevant]), so a tool described in words
 * the user never says stays hidden. That is the correct failure direction here
 * — the alternative is what we measured before this existed: every tool in
 * every prompt, in the system message, which is the prompt prefix, so each
 * turn paid a full prefill instead of a cached one (seconds, not milliseconds,
 * on a phone).
 *
 * ponytail: substring matching, not embeddings. Upgrade the matcher in
 * [SkillRegistry.findRelevant] if real tools start getting missed; the seam is
 * already there and this function does not need to change for it.
 */
fun SkillRegistry.registerToolSkills(definitions: List<ToolDefinition>) {
    definitions.forEach { definition ->
        register(
            Skill(
                metadata = SkillMetadata(
                    id = "tool:${definition.name}",
                    name = definition.name,
                    description = definition.description,
                    requiredTools = setOf(definition.name),
                ),
                // The tool's own prompt section is what actually instructs the
                // model on calling it (see ToolCoordinator.promptSectionFor),
                // so a derived skill adds no second copy of those directions.
                instructions = definition.description,
            ),
        )
    }
}
