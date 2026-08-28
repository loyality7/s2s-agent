package com.s2s.agent.skill

/**
 * Staged skill discovery/loading: [listMetadata] is the cheap stage every
 * generation can afford to consider; [load] pulls the full body only once
 * something has decided a skill is relevant (keyword match, explicit
 * selection, whatever the host wants — this class doesn't decide relevance
 * itself, only stores and serves).
 *
 * Not a general plugin system: a skill is data (instructions + tool names),
 * never code, so registering one never runs anything.
 */
class SkillRegistry {
    private val skills = linkedMapOf<String, Skill>()

    fun register(skill: Skill) {
        skills[skill.metadata.id] = skill
    }

    fun unregister(id: String) {
        skills.remove(id)
    }

    /** Stage 1: metadata for every registered skill — cheap enough to consider on every request. */
    fun listMetadata(): List<SkillMetadata> = skills.values.map { it.metadata }

    /** Stage 2: the full skill body, once something decided [id] is relevant. */
    fun load(id: String): Skill? = skills[id]

    /**
     * Simple relevance match over [listMetadata] by substring against name/
     * description — a placeholder selection strategy a host can replace
     * entirely (embeddings, explicit trigger phrase, whatever); this is not
     * meant to be the final word on skill selection, only a usable default.
     */
    fun findRelevant(request: String): SkillMetadata? {
        val lower = request.lowercase()
        return listMetadata().firstOrNull { meta ->
            lower.contains(meta.name.lowercase()) ||
                meta.description.lowercase()
                    // Split on any non-alphanumeric, not just " ": splitting on
                    // spaces alone leaves punctuation attached, so the final
                    // word of a sentence ("result.") could never match a
                    // request that actually said it ("the result"). Safe as an
                    // ASCII class only because the string is already lowercased
                    // above; a non-Latin description falls back to name matching.
                    .split(Regex("[^a-z0-9]+"))
                    .any { it.length > 3 && lower.contains(it) }
        }
    }
}
