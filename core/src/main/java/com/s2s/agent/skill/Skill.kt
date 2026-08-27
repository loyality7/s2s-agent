package com.s2s.agent.skill

/**
 * Metadata-only view of a [Skill] — the ~100-token stage a model/router sees
 * to decide relevance, before the full body loads. Not Android-specific and
 * not tied to any storage format; a host can source these from bundled
 * resources, a directory tree, or a network fetch.
 */
data class SkillMetadata(
    val id: String,
    val name: String,
    val description: String,
    /** Tool names this skill needs — the harness exposes only these, not the full tool catalog, once the skill is active. */
    val requiredTools: Set<String> = emptySet(),
)

/**
 * Full skill body, loaded only once [SkillMetadata] indicates relevance
 * (stage 2 of 3). Procedure/instructions text is a prompt fragment, not an
 * executable format — deterministic steps belong in [scriptRefs] instead of
 * being described in prose for the model to improvise.
 *
 * ponytail: no script execution engine — [scriptRefs] is a set of opaque
 * identifiers a host resolves itself (file path, tool name, etc.). Add real
 * script invocation when a skill actually needs one.
 */
data class Skill(
    val metadata: SkillMetadata,
    val instructions: String,
    val scriptRefs: List<String> = emptyList(),
)
