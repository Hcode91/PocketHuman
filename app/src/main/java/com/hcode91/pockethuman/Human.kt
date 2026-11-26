package com.hcode91.pockethuman

import org.json.JSONObject

/** Everything the user has chosen. */
class HumanState {
    val macro = linkedMapOf(
        "gender" to 0f, "age" to 0.5f, "muscle" to 0.5f, "weight" to 0.5f,
        "height" to 0.5f, "proportions" to 0.5f, "cupsize" to 0.5f, "firmness" to 0.5f
    )
    val race = linkedMapOf("african" to 1 / 3f, "asian" to 1 / 3f, "caucasian" to 1 / 3f)
    val details = HashMap<String, Float>()
    var skin = 0
    var eyeColour = 0
    var eyebrows: String? = "eyebrows_eyebrow001"
    var eyelashes: String? = "eyelashes_eyelashes01"
    var hair: String? = "hair_ponytail01"
    var hairVariant = 0
    var name = "Character"
    var adult = false
        get() = field && !isUnderage
        set(value) { field = value && !isUnderage }
    val ageValue: Float get() = macro.getValue("age").let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0.5f }
    val isUnderage: Boolean get() = isUnderage(ageValue)

    companion object {
        const val MIN_AGE_YEARS = 1
        const val MAX_AGE_YEARS = 90
        private const val YOUNG_AGE_YEARS = 25
        private const val ADULT_AGE = 17f / 48f

        // Keep 0.5 = 25 and 1 = 90 for existing saves.
        fun yearsForAge(age: Float): Float = if (age < 0.5f)
            MIN_AGE_YEARS + age * 48f else YOUNG_AGE_YEARS + (age - 0.5f) * 130f

        fun ageForYears(years: Int): Float = if (years < YOUNG_AGE_YEARS)
            (years - MIN_AGE_YEARS) / 48f else 0.5f + (years - YOUNG_AGE_YEARS) / 130f

        fun isUnderage(age: Float) = age < ADULT_AGE
    }
    var autoSkin = true
    /** 3D male genital piece (needs adult on): 0 = by gender, 1 = always, 2 = never */
    var genitals = 0
    /** painted female genital detail on the skin (needs adult on): 0 = by gender, 1 = always, 2 = never */
    var genitalDetail = 0
    /** tint anatomy pieces to the body's skin tone */
    var anatomyTint = true
    val clothes = LinkedHashSet<String>()
    /** Expression shown in the app only; exports keep a neutral face plus every expression as a blend shape. */
    val expression = HashMap<String, Float>()

    fun setRace(key: String, value: Float) {
        // keep the three mixing weights summing to 1, sharing the change between the other two
        val v = value.coerceIn(0f, 1f)
        val others = race.keys.filter { it != key }
        val rest = others.sumOf { race[it]!!.toDouble() }.toFloat()
        race[key] = v
        val left = 1f - v
        for (o in others) race[o] = if (rest > 1e-4f) race[o]!! / rest * left else left / others.size
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("format", "pockethuman"); put("version", 1); put("name", name)
        put("macro", JSONObject(macro as Map<*, *>)); put("race", JSONObject(race as Map<*, *>)); put("details", JSONObject(details as Map<*, *>))
        put("skin", skin); put("eyeColour", eyeColour); put("eyebrows", eyebrows ?: ""); put("eyelashes", eyelashes ?: ""); put("hair", hair ?: "")
        put("hairVariant", hairVariant); put("adult", adult); put("autoSkin", autoSkin); put("genitals", genitals); put("genitalDetail", genitalDetail); put("anatomyTint", anatomyTint)
        put("clothes", org.json.JSONArray(clothes.toList()))
    }
    fun load(j: JSONObject) {
        j.optJSONObject("macro")?.let { m -> for (k in macro.keys) if (m.has(k)) macro[k] = m.getDouble(k).toFloat() }
        macro["age"] = ageValue
        j.optJSONObject("race")?.let { m -> for (k in race.keys) if (m.has(k)) race[k] = m.getDouble(k).toFloat() }
        details.clear(); j.optJSONObject("details")?.let { m -> for (k in m.keys()) details[k] = m.getDouble(k).toFloat() }
        skin = j.optInt("skin", 0); eyeColour = j.optInt("eyeColour", 0); hairVariant = j.optInt("hairVariant", 0)
        eyebrows = j.optString("eyebrows").ifEmpty { null }; eyelashes = j.optString("eyelashes").ifEmpty { null }; hair = j.optString("hair").ifEmpty { null }
        name = j.optString("name", "Character")
        adult = j.optBoolean("adult", false); autoSkin = j.optBoolean("autoSkin", true); genitals = j.optInt("genitals", 0); genitalDetail = j.optInt("genitalDetail", 0); anatomyTint = j.optBoolean("anatomyTint", true)
        clothes.clear(); j.optJSONArray("clothes")?.let { a -> for (i in 0 until a.length()) clothes.add(a.getString(i)) }
        expression.clear()
    }
}

/** Turns a HumanState into vertex positions: base + weighted morph targets. */
class HumanModel(val lib: Library) {
    val state = HumanState()

    /** The body's skin texture: the chosen skin, or a copy of it with genital detail painted in when that is on. */
    fun bodyTexture(skin: Skin): String {
        val st = state
        val want = st.adult && !useYouthOutfit && !skin.adult && (st.genitalDetail == 1 || (st.genitalDetail == 0 && st.macro["gender"]!! < 0.5f))
        if (!want) return skin.texture
        return runCatching { detailProvider?.invoke(lib, skin.texture) }.getOrNull() ?: skin.texture
    }
    /** Texture for an anatomy piece: tinted to the skin unless the user turned that off. */
    fun anatomyTexture(tex: String, skin: Skin): String =
        if (!state.anatomyTint) tex else runCatching { tintProvider?.invoke(lib, tex, skin.texture) }.getOrNull() ?: tex

    companion object {
        /** Set by the app: makes/caches the skin copy with detail (needs image decoding, so it lives outside the model). */
        @Volatile var detailProvider: ((Library, String) -> String?)? = null
        /** Set by the app: (library, piece texture, skin texture) -> tinted copy of the piece texture. */
        @Volatile var tintProvider: ((Library, String, String) -> String?)? = null
    }
    /** Shape with the preview expression applied (what the app shows). */
    var verts = lib.base.verts.copyOf()
    /** Same shape with a neutral face (what gets exported). */
    var neutral = verts
    private var builtUnderage = false
    val useYouthOutfit: Boolean get() = builtUnderage || state.isUnderage

    fun canUseSkin(skin: Skin): Boolean =
        if (useYouthOutfit) skin.system && !skin.adult else !skin.adult || state.adult

    fun canUseGroup(group: SliderGroup, underage: Boolean = useYouthOutfit): Boolean =
        (!group.adult || (state.adult && !underage)) &&
            (!underage || (group.name != "breast" && group.sliders.none { lib.isAdultCommunityAsset(it.id) }))

    fun canUseFaceShape(key: String): Boolean =
        !useYouthOutfit || !lib.isAdultCommunityAsset(key.removePrefix("pack:"))

    fun selectedSkin(): Skin {
        val selected = lib.skins.getOrNull(state.skin)
        return selected?.takeIf(::canUseSkin) ?: lib.skins[matchSkin()]
    }

    private fun components(name: String, value: Float): List<Pair<String, Float>> {
        val out = ArrayList<Pair<String, Float>>()
        for (p in lib.defs.macro[name] ?: emptyList()) {
            if (value > p.lowest && value < p.highest) {
                val t = (value - p.lowest) / (p.highest - p.lowest)
                if (p.low.isNotEmpty()) out.add(p.low to 1 - t)
                if (p.high.isNotEmpty()) out.add(p.high to t)
            }
        }
        return out
    }

    /** The list of (target, weight) the macro sliders ask for. */
    fun macroStack(age: Float = state.ageValue): List<Pair<String, Float>> {
        val m = state.macro
        val c = m.keys.associateWith { if (it == "age") ageComponents(age) else components(it, m.getValue(it)) }
        val st = ArrayList<Pair<String, Float>>()
        for ((race, rw) in state.race) for ((a, aw) in c["age"]!!) for ((g, gw) in c["gender"]!!)
            st.add("macrodetails/$race-$g-$a" to rw * gw * aw)
        for ((g, gw) in c["gender"]!!) for ((a, aw) in c["age"]!!) for ((mu, mw) in c["muscle"]!!) for ((we, ww) in c["weight"]!!) {
            val base = gw * aw * mw * ww
            st.add("macrodetails/universal-$g-$a-$mu-$we" to base)
            for ((h, hw) in c["height"]!!) st.add("macrodetails/height/$g-$a-$mu-$we-$h" to base * hw)
            for ((pr, pw) in c["proportions"]!!) st.add("macrodetails/proportions/$g-$a-$mu-$we-$pr" to base * pw)
            if (g == "female" && !HumanState.isUnderage(age) && a != "child") for ((cu, cw) in c["cupsize"]!!) for ((fi, fw) in c["firmness"]!!) {
                val n = "breast/$g-$a-$mu-$we-$cu-$fi"
                // breasts are female-only targets: weighted without the gender factor, like MakeHuman
                if (!n.contains("averagecup-averagefirmness")) st.add(n to aw * mw * ww * cw * fw)
            }
        }
        return st
    }

    private fun ageComponents(age: Float): List<Pair<String, Float>> {
        if (age >= 0.5f) return components("age", age)
        val child = 0.1875f // MPFB2’s child anchor at age 10.
        return if (age <= child) {
            val t = age / child
            listOf("baby" to 1f - t, "child" to t)
        } else {
            val t = (age - child) / (0.5f - child)
            listOf("child" to 1f - t, "young" to t)
        }
    }

    fun rebuild() {
        val age = state.ageValue
        val underage = HumanState.isUnderage(age)
        val v = lib.base.verts.copyOf()
        fun apply(name: String, w: Float) {
            if (w < 0.001f && w > -0.001f) return
            val t = lib.targets.get(name) ?: return
            val (idx, d) = t
            for (i in idx.indices) { val o = idx[i] * 3; v[o] += w * d[i * 3]; v[o + 1] += w * d[i * 3 + 1]; v[o + 2] += w * d[i * 3 + 2] }
        }
        for ((n, w) in macroStack(age)) if (w > 0.01f) apply(n, w)
        for (g in lib.defs.groups) {
            if (!canUseGroup(g, underage)) continue
            for (s in g.sliders) {
                val x = state.details[s.id] ?: continue
                if (x > 0) s.pos.forEach { apply(it, x) } else if (x < 0) s.neg.forEach { apply(it, -x) }
            }
        }
        neutral = v.copyOf()
        for ((key, w) in state.expression) {
            if (w <= 0.001f || (underage && lib.isAdultCommunityAsset(key.removePrefix("pack:")))) continue
            for ((t, tw) in lib.faceShapeTargets(key, state.race)) apply(t, w * tw)
        }
        verts = v
        builtUnderage = underage
        if (state.autoSkin) state.skin = matchSkin()
    }

    /** Closest built-in skin for the current ethnicity mix, gender and age. */
    fun matchSkin(): Int {
        val race = state.race.maxByOrNull { it.value }!!.key
        val gender = if (state.macro["gender"]!! < 0.5f) "female" else "male"
        val y = ageYears(); val age = if (y < 42) "young" else if (y < 62) "middleage" else "old"
        val sk = lib.skins
        fun score(s: Skin) = (if (s.system) 4 else 0) + (if (s.race == race) 4 else 0) + (if (s.gender == gender) 3 else 0) + (if (s.age == age) 2 else 0) - (if (s.adult) 10 else 0)
        return sk.indices.filter { canUseSkin(sk[it]) }.maxByOrNull { score(sk[it]) } ?: 0
    }

    fun proxyIds(): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        out.add("eyes_high-poly" to state.eyeColour)
        state.eyebrows?.let { out.add(it to 0) }
        state.eyelashes?.let { out.add(it to 0) }
        out.add("teeth_teeth_base" to 0); out.add("tongue_tongue01" to 0)
        state.hair?.let { out.add(it to state.hairVariant) }
        val worn = lib.proxies.filter { it.kind == "clothes" && it.id in state.clothes }
        worn.sortedBy { it.zdepth }.forEach { out.add(it.id to 0) }
        // anatomy pieces: adult only, and left off when clothes cover the crotch
        if (state.adult && !useYouthOutfit) {
            val crotch = lib.crotchVerts
            val covered = worn.any { p -> p.delete.any { it in crotch } }
            if (!covered) lib.proxies.filter { it.kind == "anatomy" && it.id in state.clothes }.forEach { out.add(it.id to 0) }
        }
        return out.filter { (id, _) ->
            (!useYouthOutfit || !lib.isAdultCommunityAsset(id)) && lib.proxies.any { it.id == id }
        }
    }

    fun heightMetres(): Float {
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (i in 0 until 13380) { val y = verts[i * 3 + 1]; if (y < lo) lo = y; if (y > hi) hi = y }
        return (hi - lo) * 0.1f
    }
    fun ageYears() = HumanState.yearsForAge(state.ageValue)
}

/** Smooth normals, welded by position so UV seams don't show. */
fun weldedNormals(p: FloatArray, tris: IntArray, n: Int): FloatArray {
    val key = HashMap<Long, Int>(); val group = IntArray(n)
    for (i in 0 until n) {
        val k = (Math.round(p[i * 3] * 2e4).toLong() * 73856093L) xor (Math.round(p[i * 3 + 1] * 2e4).toLong() * 19349663L) xor (Math.round(p[i * 3 + 2] * 2e4).toLong() * 83492791L)
        group[i] = key.getOrPut(k) { key.size }
    }
    val acc = FloatArray(key.size * 3)
    var t = 0
    while (t < tris.size) {
        val a = tris[t]; val b = tris[t + 1]; val c = tris[t + 2]
        val ux = p[b * 3] - p[a * 3]; val uy = p[b * 3 + 1] - p[a * 3 + 1]; val uz = p[b * 3 + 2] - p[a * 3 + 2]
        val vx = p[c * 3] - p[a * 3]; val vy = p[c * 3 + 1] - p[a * 3 + 1]; val vz = p[c * 3 + 2] - p[a * 3 + 2]
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        for (q in intArrayOf(a, b, c)) { val g = group[q] * 3; acc[g] += nx; acc[g + 1] += ny; acc[g + 2] += nz }
        t += 3
    }
    val out = FloatArray(n * 3)
    for (i in 0 until n) {
        val g = group[i] * 3; val l = Math.sqrt((acc[g] * acc[g] + acc[g + 1] * acc[g + 1] + acc[g + 2] * acc[g + 2]).toDouble()).toFloat().coerceAtLeast(1e-12f)
        out[i * 3] = acc[g] / l; out[i * 3 + 1] = acc[g + 1] / l; out[i * 3 + 2] = acc[g + 2] / l
    }
    return out
}
