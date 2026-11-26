package com.hcode91.pockethuman

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Little-endian reader over a whole asset file. */
class Bin(bytes: ByteArray) {
    val b: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun int() = b.int
    fun skip(n: Int) { b.position(b.position() + n) }
    fun floats(n: Int) = FloatArray(n).also { b.asFloatBuffer().get(it); skip(n * 4) }
    fun ints(n: Int) = IntArray(n).also { b.asIntBuffer().get(it); skip(n * 4) }
    fun ushorts(n: Int) = IntArray(n) { (b.short.toInt() and 0xffff) }
    fun ubytes(n: Int) = IntArray(n) { (b.get().toInt() and 0xff) }
}

/** Reads files from the asset pack (assets/ph on Android, a folder in tests). */
fun interface AssetSource { fun bytes(path: String): ByteArray }
fun AssetSource.json(path: String): String = String(bytes(path))
fun AssetManager.source() = AssetSource { p -> open("ph/$p").use { it.readBytes() } }

/** Base mesh: all vertices (body + helpers), plus the renderable body (unwelded for UV seams). */
class BaseMesh(am: AssetSource) {
    val nV: Int
    val verts: FloatArray
    val renderMap: IntArray
    val uv: FloatArray
    val tris: IntArray
    init {
        val r = Bin(am.bytes("base.bin")); r.skip(4)
        nV = r.int(); verts = r.floats(nV * 3)
        val nR = r.int(); renderMap = r.ushorts(nR); uv = r.floats(nR * 2)
        val nT = r.int(); tris = r.ints(nT * 3)
    }
}

/** Morph targets, decoded lazily from targets.bin (index + half-float offsets). */
class Targets(am: AssetSource) {
    private val blob = ByteBuffer.wrap(am.bytes("targets.bin")).order(ByteOrder.LITTLE_ENDIAN)
    private val index = HashMap<String, IntArray>()
    private val cache = HashMap<String, Pair<IntArray, FloatArray>>()
    init {
        val j = JSONObject(am.json("targets.json"))
        for (k in j.keys()) { val a = j.getJSONArray(k); index[k] = intArrayOf(a.getInt(0), a.getInt(1)) }
    }
    fun has(name: String) = index.containsKey(name)
    fun get(name: String): Pair<IntArray, FloatArray>? {
        cache[name]?.let { return it }
        val (off, n) = index[name] ?: return null
        val idx = IntArray(n) { blob.getShort(off + it * 2).toInt() and 0xffff }
        val d = FloatArray(n * 3) { half(blob.getShort(off + n * 2 + it * 2)) }
        return Pair(idx, d).also { cache[name] = it }
    }
    private fun half(h: Short): Float {
        val s = h.toInt() and 0xffff
        val sign = if (s and 0x8000 != 0) -1f else 1f
        val e = (s shr 10) and 0x1f; val m = s and 0x3ff
        return when (e) {
            0 -> sign * m * 5.9604645e-8f
            31 -> if (m == 0) sign * Float.POSITIVE_INFINITY else Float.NaN
            else -> sign * (1f + m / 1024f) * Math.pow(2.0, (e - 15).toDouble()).toFloat()
        }
    }
}

class Slider(val id: String, val label: String, val neg: List<String>, val pos: List<String>)
class SliderGroup(val name: String, val sliders: List<Slider>, val adult: Boolean)
class MacroPart(val lowest: Float, val highest: Float, val low: String, val high: String)

class SliderDefs(am: AssetSource) {
    val groups = ArrayList<SliderGroup>()
    val macro = HashMap<String, List<MacroPart>>()
    val expressions = ArrayList<String>()
    val arkit = ArrayList<String>()
    init {
        val j = JSONObject(am.json("sliders.json"))
        val g = j.getJSONArray("groups")
        for (i in 0 until g.length()) {
            val o = g.getJSONObject(i); val s = o.getJSONArray("sliders")
            groups.add(SliderGroup(o.getString("group"), (0 until s.length()).map {
                val x = s.getJSONObject(it)
                Slider(x.getString("id"), x.getString("label"), strs(x.getJSONArray("neg")), strs(x.getJSONArray("pos")))
            }, o.optBoolean("adult", false)))
        }
        j.optJSONArray("expressions")?.let { e -> for (i in 0 until e.length()) expressions.add(e.getString(i)) }
        j.optJSONArray("arkit")?.let { e -> for (i in 0 until e.length()) arkit.add(e.getString(i)) }
        val m = j.getJSONObject("macro").getJSONObject("macrotargets")
        for (k in m.keys()) {
            val parts = m.getJSONObject(k).getJSONArray("parts")
            macro[k] = (0 until parts.length()).map {
                val p = parts.getJSONObject(it)
                MacroPart(p.getDouble("lowest").toFloat(), p.getDouble("highest").toFloat(), p.getString("low"), p.getString("high"))
            }
        }
    }
    private fun strs(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
}

class Bone(val name: String, val parent: Int, val head: IntArray, val tail: IntArray)
class Rig(am: AssetSource, nV: Int) {
    val bones: List<Bone>
    val joints: IntArray   // nV*4
    val weights: FloatArray // nV*4
    init {
        val a = JSONObject(am.json("rig.json")).getJSONArray("bones")
        bones = (0 until a.length()).map {
            val o = a.getJSONObject(it)
            fun v(k: String): IntArray { val x = o.getJSONObject(k).getJSONArray("verts"); return IntArray(x.length()) { i -> x.getInt(i) } }
            Bone(o.getString("name"), o.getInt("parent"), v("head"), v("tail"))
        }
        val r = Bin(am.bytes("rig_weights.bin"))
        joints = r.ubytes(nV * 4); weights = r.floats(nV * 4)
    }
}

class ProxyVariant(val name: String, val texture: String, val alpha: Boolean, val normal: String?)
class ProxyInfo(val id: String, val kind: String, val label: String, val file: String, val delete: IntArray, val variants: List<ProxyVariant>, val zdepth: Int)
class Skin(val name: String, val texture: String, val normal: String?, val adult: Boolean, val system: Boolean, val race: String?, val gender: String, val age: String)

/** A proxy mesh (eyes, hair...) and how each of its vertices hangs off the base mesh. */
class ProxyMesh(am: AssetSource, val info: ProxyInfo) {
    val n: Int; val refs: IntArray; val rw: FloatArray; val ro: FloatArray
    val scaleA = IntArray(3); val scaleB = IntArray(3); val scaleRef = FloatArray(3)
    val renderMap: IntArray; val uv: FloatArray; val tris: IntArray
    init {
        val r = Bin(am.bytes(info.file)); r.skip(4)
        n = r.int(); refs = r.ushorts(n * 3); rw = r.floats(n * 3); ro = r.floats(n * 3)
        for (k in 0..2) scaleA[k] = r.int(); for (k in 0..2) scaleRef[k] = r.b.float; for (k in 0..2) scaleB[k] = r.int()
        val nr = r.int(); renderMap = r.ushorts(nr); uv = r.floats(nr * 2)
        val nt = r.int(); tris = r.ints(nt * 3)
    }
    /** Fit to the current (morphed) base vertices. */
    fun fit(base: FloatArray): FloatArray {
        val s = FloatArray(3) { k -> if (scaleRef[k] > 0f) Math.abs(base[scaleA[k] * 3 + k] - base[scaleB[k] * 3 + k]) / scaleRef[k] else 1f }
        val out = FloatArray(n * 3)
        for (i in 0 until n) for (k in 0..2) {
            var v = 0f
            for (j in 0..2) v += rw[i * 3 + j] * base[refs[i * 3 + j] * 3 + k]
            out[i * 3 + k] = v + ro[i * 3 + k] * s[k]
        }
        return out
    }
}

class Library(val am: AssetSource) {
    val base = BaseMesh(am)
    val targets = Targets(am)
    val defs = SliderDefs(am)
    val rig = Rig(am, base.nV)
    val proxies: List<ProxyInfo>
    val skins: List<Skin>
    private val meshCache = HashMap<String, ProxyMesh>()
    init {
        val p = JSONArray(am.json("proxies.json"))
        proxies = (0 until p.length()).map {
            val o = p.getJSONObject(it); val v = o.getJSONArray("variants"); val d = o.getJSONArray("delete")
            ProxyInfo(o.getString("id"), o.getString("kind"), o.getString("label"), o.getString("file"), IntArray(d.length()) { i -> d.getInt(i) },
                (0 until v.length()).map { i -> val x = v.getJSONObject(i); ProxyVariant(x.getString("name"), x.getString("texture"), x.getBoolean("alpha"), x.optString("normal").ifEmpty { null }) },
                o.optInt("zdepth", 50))
        }
        val s = JSONArray(am.json("skins.json"))
        skins = (0 until s.length()).map { val o = s.getJSONObject(it)
            Skin(o.getString("name"), o.getString("texture"), o.optString("normal").ifEmpty { null }, o.optBoolean("adult"), o.optBoolean("system"),
                o.optString("race").ifEmpty { null }, o.optString("gender", "female"), o.optString("age", "young")) }
    }
    fun proxy(id: String) = meshCache.getOrPut(id) { ProxyMesh(am, proxies.first { it.id == id }) }
    fun ofKind(kind: String) = proxies.filter { it.kind == kind }

    /** All face shapes: MakeHuman units ("mh:mouth-open") and ARKit face units ("arkit:jawOpen"). */
    fun faceShapes(): List<String> = defs.expressions.map { "mh:$it" } + defs.arkit.map { "arkit:$it" }

    /** (target, weight) pairs making up one face shape; MakeHuman units blend the three ethnic variants. */
    fun faceShapeTargets(key: String, race: Map<String, Float>): List<Pair<String, Float>> =
        if (key.startsWith("arkit:")) listOf("faceunits/" + key.removePrefix("arkit:") to 1f)
        else race.map { (r, w) -> "expression/units/$r/${key.removePrefix("mh:")}" to w }

    /** A face shape as a dense offset field over the base vertices. */
    fun faceShapeDelta(key: String, race: Map<String, Float>): FloatArray {
        val d = FloatArray(base.nV * 3)
        for ((t, w) in faceShapeTargets(key, race)) {
            if (w < 1e-4f) continue
            val (idx, off) = targets.get(t) ?: continue
            for (i in idx.indices) for (k in 0..2) d[idx[i] * 3 + k] += w * off[i * 3 + k]
        }
        return d
    }
}
