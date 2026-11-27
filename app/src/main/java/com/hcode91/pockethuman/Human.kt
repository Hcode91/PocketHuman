package com.hcode91.pockethuman

import org.json.JSONObject

/** Everything the user has chosen. Ages are adult only (25 to 90). */
/** clips exported by default: the basics for a playable character */
val DEFAULT_ANIMS = listOf("Idle_Loop", "Walk_Loop", "Jog_Fwd_Loop", "Sprint_Loop", "Jump_Start", "Jump_Loop", "Jump_Land", "Crouch_Idle_Loop", "Crouch_Fwd_Loop", "Roll", "Interact", "Hit_Chest", "Death01")

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
    var autoSkin = true
    /** 3D male genital piece (needs adult on): 0 = by gender, 1 = always, 2 = never */
    var genitals = 0
    /** painted female genital detail on the skin (needs adult on): 0 = by gender, 1 = always, 2 = never */
    var genitalDetail = 0
    /** tint anatomy pieces to the body's skin tone */
    var anatomyTint = true
    /** an imported model used as the character instead of the PocketHuman body (id in imported/my_models), or null */
    var customModel: String? = null
    /** a head from a 3D model in place of the PocketHuman head (id in imported/my_heads), or null */
    var customHead: String? = null
    /** animation clips to include in the export */
    val anims = ArrayList(DEFAULT_ANIMS)
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
        put("hairVariant", hairVariant); put("adult", adult); put("autoSkin", autoSkin); put("genitals", genitals); put("genitalDetail", genitalDetail); put("anatomyTint", anatomyTint); put("customModel", customModel ?: JSONObject.NULL); put("customHead", customHead ?: JSONObject.NULL); put("anims", org.json.JSONArray(anims))
        put("clothes", org.json.JSONArray(clothes.toList()))
    }
    fun load(j: JSONObject) {
        j.optJSONObject("macro")?.let { m -> for (k in macro.keys) if (m.has(k)) macro[k] = m.getDouble(k).toFloat() }
        macro["age"] = macro["age"]!!.coerceIn(0.5f, 1f)
        j.optJSONObject("race")?.let { m -> for (k in race.keys) if (m.has(k)) race[k] = m.getDouble(k).toFloat() }
        details.clear(); j.optJSONObject("details")?.let { m -> for (k in m.keys()) details[k] = m.getDouble(k).toFloat() }
        skin = j.optInt("skin", 0); eyeColour = j.optInt("eyeColour", 0); hairVariant = j.optInt("hairVariant", 0)
        eyebrows = j.optString("eyebrows").ifEmpty { null }; eyelashes = j.optString("eyelashes").ifEmpty { null }; hair = j.optString("hair").ifEmpty { null }
        name = j.optString("name", "Character")
        adult = j.optBoolean("adult", false); autoSkin = j.optBoolean("autoSkin", true); genitals = j.optInt("genitals", 0); genitalDetail = j.optInt("genitalDetail", 0); anatomyTint = j.optBoolean("anatomyTint", true); customModel = if (j.isNull("customModel")) null else j.optString("customModel").ifEmpty { null }
        customHead = if (!j.has("customHead") || j.isNull("customHead")) null else j.optString("customHead").ifEmpty { null }
        anims.clear(); j.optJSONArray("anims")?.let { a -> for (i in 0 until a.length()) anims.add(a.getString(i)) } ?: anims.addAll(DEFAULT_ANIMS)
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
        val want = st.adult && !skin.adult && (st.genitalDetail == 1 || (st.genitalDetail == 0 && st.macro["gender"]!! < 0.5f))
        if (!want) return skin.texture
        return runCatching { detailProvider?.invoke(lib, skin.texture) }.getOrNull() ?: skin.texture
    }
    /** The imported model when [HumanState.customModel] is set (loaded through [modelLoader], cached). */
    private var loadedId: String? = null; private var loaded: Part? = null
    val customPart: Part? get() {
        val id = state.customModel ?: return null
        if (id != loadedId) { loaded = runCatching { modelLoader?.invoke(id) }.getOrNull(); loadedId = id }
        return loaded
    }

    /** The replacement head when [HumanState.customHead] is set (loaded through [headLoader], cached). */
    private var headId: String? = null; private var headData: HeadData? = null
    val customHead: HeadData? get() {
        val id = state.customHead ?: return null
        if (id != headId) { headData = runCatching { headLoader?.invoke(id) }.getOrNull(); headId = id }
        return headData
    }
    fun forgetHead() { headId = null; headData = null }

    /** Texture for an anatomy piece: tinted to the skin unless the user turned that off. */
    fun anatomyTexture(tex: String, skin: Skin): String =
        if (!state.anatomyTint) tex else runCatching { tintProvider?.invoke(lib, tex, skin.texture) }.getOrNull() ?: tex

    companion object {
        /** Set by the app: makes/caches the skin copy with detail (needs image decoding, so it lives outside the model). */
        @Volatile var detailProvider: ((Library, String) -> String?)? = null
        /** Set by the app: (library, piece texture, skin texture) -> tinted copy of the piece texture. */
        /** Set by the app: loads an imported model by id (see ModelStore). */
        @Volatile var modelLoader: ((String) -> Part?)? = null
        /** Set by the app: loads a replacement head by id (see HeadStore). */
        @Volatile var headLoader: ((String) -> HeadData?)? = null
        @Volatile var tintProvider: ((Library, String, String) -> String?)? = null
    }
    /** Shape with the preview expression applied (what the app shows). */
    var verts = lib.base.verts.copyOf()
    /** Same shape with a neutral face (what gets exported). */
    var neutral = verts

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
    fun macroStack(): List<Pair<String, Float>> {
        val m = state.macro
        val c = m.keys.associateWith { components(it, m[it]!!) }
        val st = ArrayList<Pair<String, Float>>()
        for ((race, rw) in state.race) for ((a, aw) in c["age"]!!) for ((g, gw) in c["gender"]!!)
            st.add("macrodetails/$race-$g-$a" to rw * gw * aw)
        for ((g, gw) in c["gender"]!!) for ((a, aw) in c["age"]!!) for ((mu, mw) in c["muscle"]!!) for ((we, ww) in c["weight"]!!) {
            val base = gw * aw * mw * ww
            st.add("macrodetails/universal-$g-$a-$mu-$we" to base)
            for ((h, hw) in c["height"]!!) st.add("macrodetails/height/$g-$a-$mu-$we-$h" to base * hw)
            for ((pr, pw) in c["proportions"]!!) st.add("macrodetails/proportions/$g-$a-$mu-$we-$pr" to base * pw)
            if (g == "female") for ((cu, cw) in c["cupsize"]!!) for ((fi, fw) in c["firmness"]!!) {
                val n = "breast/$g-$a-$mu-$we-$cu-$fi"
                // breasts are female-only targets: weighted without the gender factor, like MakeHuman
                if (!n.contains("averagecup-averagefirmness")) st.add(n to aw * mw * ww * cw * fw)
            }
        }
        return st
    }

    fun rebuild() {
        val v = lib.base.verts.copyOf()
        fun apply(name: String, w: Float) {
            if (w < 0.001f && w > -0.001f) return
            val t = lib.targets.get(name) ?: return
            val (idx, d) = t
            for (i in idx.indices) { val o = idx[i] * 3; v[o] += w * d[i * 3]; v[o + 1] += w * d[i * 3 + 1]; v[o + 2] += w * d[i * 3 + 2] }
        }
        for ((n, w) in macroStack()) if (w > 0.01f) apply(n, w)
        for (g in lib.defs.groups) {
            if (g.adult && !state.adult) continue
            for (s in g.sliders) {
                val x = state.details[s.id] ?: continue
                if (x > 0) s.pos.forEach { apply(it, x) } else if (x < 0) s.neg.forEach { apply(it, -x) }
            }
        }
        neutral = v.copyOf()
        for ((key, w) in state.expression) if (w > 0.001f) for ((t, tw) in lib.faceShapeTargets(key, state.race)) apply(t, w * tw)
        verts = v
        if (state.autoSkin) state.skin = matchSkin()
    }

    /** Closest built-in skin for the current ethnicity mix, gender and age. */
    fun matchSkin(): Int {
        val race = state.race.maxByOrNull { it.value }!!.key
        val gender = if (state.macro["gender"]!! < 0.5f) "female" else "male"
        val y = ageYears(); val age = if (y < 42) "young" else if (y < 62) "middleage" else "old"
        val sk = lib.skins
        fun score(s: Skin) = (if (s.system) 4 else 0) + (if (s.race == race) 4 else 0) + (if (s.gender == gender) 3 else 0) + (if (s.age == age) 2 else 0) - (if (s.adult) 10 else 0)
        return sk.indices.maxByOrNull { score(sk[it]) } ?: 0
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
        if (state.adult) {
            val crotch = lib.crotchVerts
            val covered = worn.any { p -> p.delete.any { it in crotch } }
            if (!covered) lib.proxies.filter { it.kind == "anatomy" && it.id in state.clothes }.forEach { out.add(it.id to 0) }
        }
        return out.filter { p -> lib.proxies.any { it.id == p.first } }
    }

    fun heightMetres(): Float {
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (i in 0 until 13380) { val y = verts[i * 3 + 1]; if (y < lo) lo = y; if (y > hi) hi = y }
        return (hi - lo) * 0.1f
    }
    fun ageYears() = 25f + (state.macro["age"]!! - 0.5f) * 2f * 65f
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
