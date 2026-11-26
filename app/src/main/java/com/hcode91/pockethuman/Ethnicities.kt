package com.hcode91.pockethuman

/**
 * One-tap starting points. MakeHuman has three base shapes (African, Asian, Caucasian); these presets blend them,
 * make a few gentle face adjustments and pick a matching skin. They are averages, not definitions.
 */
object Ethnicities {
    class Preset(val name: String, val african: Float, val asian: Float, val caucasian: Float, val details: Map<String, Float> = emptyMap(), val skinHint: String? = null)

    val presets = listOf(
        Preset("East Asian", 0f, 0.9f, 0.1f),
        Preset("Southeast Asian", 0.15f, 0.7f, 0.15f, mapOf("nose/nose-flaring-decr-incr" to 0.2f)),
        Preset("South Asian", 0.15f, 0.35f, 0.5f, mapOf("nose/nose-scale-vert-decr-incr" to 0.1f), skinHint = "indian"),
        Preset("Middle Eastern", 0.1f, 0.15f, 0.75f, mapOf("nose/nose-hump-decr-incr" to 0.3f, "nose/nose-scale-vert-decr-incr" to 0.2f)),
        Preset("North African", 0.3f, 0.1f, 0.6f, mapOf("nose/nose-hump-decr-incr" to 0.15f)),
        Preset("West African", 0.95f, 0f, 0.05f),
        Preset("East African", 0.75f, 0.05f, 0.2f, mapOf("nose/nose-scale-horiz-decr-incr" to -0.2f)),
        Preset("Latin American", 0.15f, 0.35f, 0.5f),
        Preset("Indigenous American", 0.05f, 0.65f, 0.3f, mapOf("cheek/cheek-bones-decr-incr" to 0.3f)),
        Preset("Pacific Islander", 0.3f, 0.5f, 0.2f, mapOf("nose/nose-flaring-decr-incr" to 0.2f)),
        Preset("Northern European", 0f, 0f, 1f),
        Preset("Southern European", 0.05f, 0.05f, 0.9f),
        Preset("Mixed", 1 / 3f, 1 / 3f, 1 / 3f)
    )
    private val touched = presets.flatMap { it.details.keys }.toSet()

    fun apply(p: Preset, model: HumanModel) {
        val s = model.state
        s.race["african"] = p.african; s.race["asian"] = p.asian; s.race["caucasian"] = p.caucasian
        touched.forEach { s.details.remove(it) }
        s.details.putAll(p.details)
        val female = s.macro["gender"]!! < 0.5f
        val hinted = p.skinHint?.let { h -> model.lib.skins.indexOfFirst { it.texture.contains(h) && (it.gender == "female") == female && !it.adult } }
        if (hinted != null && hinted >= 0) { s.skin = hinted; s.autoSkin = false } else s.autoSkin = true
    }
}

object Credits {
    /** Credits for every pack asset this character uses (proxies, skin, imported shape sliders). */
    fun forCharacter(model: HumanModel): List<Credit> {
        val lib = model.lib; val out = LinkedHashMap<String, Credit>()
        for ((id, _) in model.proxyIds()) lib.credits[id]?.let { out[it.asset] = it }
        lib.credits[model.selectedSkin().texture]?.let { out[it.asset] = it }
        for (g in lib.defs.groups.filter { model.canUseGroup(it) }) for (sl in g.sliders) if ((model.state.details[sl.id] ?: 0f) != 0f)
            for (t in sl.pos + sl.neg) lib.credits[t.substringAfterLast('/')]?.let { out[it.asset] = it }
        return out.values.toList()
    }
}
