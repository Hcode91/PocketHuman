package com.hcode91.pockethuman

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A plain textured triangle mesh (no rig), as imported from an AI tool. */
class RawMesh(val pos: FloatArray, val uv: FloatArray, val tris: IntArray, val texture: ByteArray?, val textureExt: String) {
    val nv get() = pos.size / 3
}

object MeshIO {
    fun load(f: File, extraTexture: File? = null): RawMesh = when (f.extension.lowercase()) {
        "glb" -> readGlb(f.readBytes())
        "obj" -> readObj(f, extraTexture)
        else -> error("Use a .glb or .obj file")
    }

    private fun readObj(f: File, tex: File?): RawMesh {
        val (v, vt, faces) = ObjReader.read(f)
        val (rmap, uv, tris) = ObjReader.unweld(v.size / 3, vt, faces)
        val pos = FloatArray(rmap.size * 3) { v[rmap[it / 3] * 3 + it % 3] }
        // texture: the one picked, or the map_Kd from the .mtl next to it
        var t = tex
        if (t == null) {
            val mtl = File(f.parentFile, f.nameWithoutExtension + ".mtl")
            if (mtl.exists()) mtl.readLines().firstOrNull { it.trim().startsWith("map_Kd") }?.let { l -> t = File(f.parentFile, l.trim().split(Regex("\\s+")).last()) }
        }
        val tb = t?.takeIf { it.exists() }?.readBytes()
        return RawMesh(pos, uv, tris, tb, t?.extension?.lowercase() ?: "png")
    }

    fun readGlb(b: ByteArray): RawMesh {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        require(bb.getInt(0) == 0x46546C67) { "Not a .glb file" }
        val jlen = bb.getInt(12); val json = JSONObject(String(b, 20, jlen))
        var binOff = 20 + jlen
        val bin = if (binOff + 8 <= b.size) { val l = bb.getInt(binOff); binOff += 8; ByteBuffer.wrap(b, binOff, l).slice().order(ByteOrder.LITTLE_ENDIAN) } else null
        val ext = json.optJSONArray("extensionsRequired")
        if (ext != null) for (i in 0 until ext.length()) if (ext.getString(i).contains("draco") || ext.getString(i).contains("meshopt"))
            error("This .glb is compressed (${ext.getString(i)}). Export it again without compression.")
        val views = json.optJSONArray("bufferViews"); val accs = json.optJSONArray("accessors")
        fun view(i: Int): ByteBuffer {
            val v = views!!.getJSONObject(i)
            val o = v.optInt("byteOffset"); val l = v.getInt("byteLength")
            val s = bin!!.duplicate().order(ByteOrder.LITTLE_ENDIAN); s.position(o); s.limit(o + l); return s.slice().order(ByteOrder.LITTLE_ENDIAN)
        }
        fun floats(ai: Int): FloatArray {
            val a = accs!!.getJSONObject(ai); val n = a.getInt("count")
            val c = when (a.getString("type")) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> 16 }
            val ct = a.getInt("componentType"); val norm = a.optBoolean("normalized")
            val bv = a.optInt("bufferView", -1); if (bv < 0) return FloatArray(n * c)
            val buf = view(bv); val off = a.optInt("byteOffset")
            val size = when (ct) { 5126 -> 4; 5123, 5122 -> 2; else -> 1 }
            val stride = views!!.getJSONObject(bv).optInt("byteStride", 0).takeIf { it > 0 } ?: (size * c)
            return FloatArray(n * c) { i ->
                val p = off + (i / c) * stride + (i % c) * size
                when (ct) {
                    5126 -> buf.getFloat(p)
                    5123 -> (buf.getShort(p).toInt() and 0xffff).let { if (norm) it / 65535f else it.toFloat() }
                    5122 -> buf.getShort(p).toFloat().let { if (norm) maxOf(it / 32767f, -1f) else it }
                    5121 -> (buf.get(p).toInt() and 0xff).let { if (norm) it / 255f else it.toFloat() }
                    else -> buf.get(p).toFloat().let { if (norm) maxOf(it / 127f, -1f) else it }
                }
            }
        }
        fun ints(ai: Int): IntArray {
            val a = accs!!.getJSONObject(ai); val n = a.getInt("count"); val ct = a.getInt("componentType")
            val buf = view(a.getInt("bufferView")); val off = a.optInt("byteOffset")
            return IntArray(n) { i -> when (ct) { 5125 -> buf.getInt(off + i * 4); 5123 -> buf.getShort(off + i * 2).toInt() and 0xffff; else -> buf.get(off + i).toInt() and 0xff } }
        }
        // walk the scene so node transforms are applied
        val nodes = json.optJSONArray("nodes"); val meshes = json.getJSONArray("meshes")
        val pos = ArrayList<Float>(); val uv = ArrayList<Float>(); val tris = ArrayList<Int>()
        var texIndex = -1
        fun local(n: JSONObject): FloatArray {
            val m = FloatArray(16); android_identity(m)
            n.optJSONArray("matrix")?.let { a -> for (i in 0..15) m[i] = a.getDouble(i).toFloat(); return m }
            val t = n.optJSONArray("translation"); val r = n.optJSONArray("rotation"); val s = n.optJSONArray("scale")
            val q = if (r != null) FloatArray(4) { r.getDouble(it).toFloat() } else floatArrayOf(0f, 0f, 0f, 1f)
            val sc = if (s != null) FloatArray(3) { s.getDouble(it).toFloat() } else floatArrayOf(1f, 1f, 1f)
            val (x, y, z, w) = q.toList()
            val rm = floatArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0f,
                2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0f,
                2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0f, 0f, 0f, 0f, 1f)
            for (c in 0..2) for (rr in 0..2) rm[c * 4 + rr] *= sc[c]
            if (t != null) for (k in 0..2) rm[12 + k] = t.getDouble(k).toFloat()
            return rm
        }
        fun mul(a: FloatArray, b: FloatArray) = FloatArray(16) { i -> val c = i / 4; val r = i % 4; (0..3).sumOf { k -> (a[k * 4 + r] * b[c * 4 + k]).toDouble() }.toFloat() }
        fun addMesh(mi: Int, m: FloatArray) {
            val prims = meshes.getJSONObject(mi).getJSONArray("primitives")
            for (pi in 0 until prims.length()) {
                val p = prims.getJSONObject(pi); if (p.optInt("mode", 4) != 4) continue
                val at = p.getJSONObject("attributes")
                val P = floats(at.getInt("POSITION")); val n = P.size / 3
                val T = if (at.has("TEXCOORD_0")) floats(at.getInt("TEXCOORD_0")) else FloatArray(n * 2)
                val I = if (p.has("indices")) ints(p.getInt("indices")) else IntArray(n) { it }
                if (texIndex < 0) json.optJSONArray("materials")?.optJSONObject(p.optInt("material", -1))?.optJSONObject("pbrMetallicRoughness")
                    ?.optJSONObject("baseColorTexture")?.let { texIndex = it.getInt("index") }
                val base = pos.size / 3
                for (i in 0 until n) {
                    val x = P[i * 3]; val y = P[i * 3 + 1]; val z = P[i * 3 + 2]
                    for (k in 0..2) pos.add(m[k] * x + m[4 + k] * y + m[8 + k] * z + m[12 + k])
                    uv.add(T[i * 2]); uv.add(T[i * 2 + 1])
                }
                I.forEach { tris.add(base + it) }
            }
        }
        fun walk(ni: Int, parent: FloatArray) {
            val n = nodes!!.getJSONObject(ni); val m = mul(parent, local(n))
            if (n.has("mesh")) addMesh(n.getInt("mesh"), m)
            n.optJSONArray("children")?.let { c -> for (i in 0 until c.length()) walk(c.getInt(i), m) }
        }
        val id = FloatArray(16).also { android_identity(it) }
        val scene = json.optJSONArray("scenes")?.optJSONObject(json.optInt("scene", 0))
        if (scene != null && nodes != null) { val r = scene.getJSONArray("nodes"); for (i in 0 until r.length()) walk(r.getInt(i), id) }
        else for (i in 0 until meshes.length()) addMesh(i, id)
        if (pos.isEmpty()) error("No meshes found in this file")
        // texture bytes
        var tex: ByteArray? = null; var extn = "png"
        if (texIndex >= 0) {
            // the image may be named by an extension instead (WebP / KTX2 / DDS textures)
            val tj = json.getJSONArray("textures").getJSONObject(texIndex)
            val src = if (tj.has("source")) tj.getInt("source") else tj.optJSONObject("extensions")?.let { e -> e.keys().asSequence().mapNotNull { k -> e.optJSONObject(k)?.optInt("source", -1)?.takeIf { it >= 0 } }.firstOrNull() } ?: 0
            val img = json.getJSONArray("images").getJSONObject(src)
            val mime = img.optString("mimeType")
            extn = if (mime.contains("jpeg")) "jpg" else if (mime.contains("webp")) "webp" else "png"
            if (img.has("bufferView")) { val v = view(img.getInt("bufferView")); tex = ByteArray(v.remaining()).also { v.get(it) } }
            else img.optString("uri").takeIf { it.startsWith("data:") }?.let { tex = java.util.Base64.getDecoder().decode(it.substringAfter(",")) }
        }
        return RawMesh(pos.toFloatArray(), uv.toFloatArray(), tris.toIntArray(), tex, extn)
    }
    private fun android_identity(m: FloatArray) { java.util.Arrays.fill(m, 0f); m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f }

    /** Vertex clustering that keeps UV islands apart, for very dense AI meshes. */
    fun simplify(m: RawMesh, targetTris: Int): RawMesh {
        if (m.tris.size / 3 <= targetTris) return m
        var lo = 0f; var hi = 0f
        for (k in 0..2) { var a = Float.MAX_VALUE; var b = -Float.MAX_VALUE; for (i in 0 until m.nv) { a = minOf(a, m.pos[i * 3 + k]); b = maxOf(b, m.pos[i * 3 + k]) }; hi = maxOf(hi, b - a) }
        // surfaces facing different ways (the underside of hair over a neck, a sleeve over an arm) are never merged with
        // each other, or thinning spins webs between them: each vertex is binned by which way it faces
        val facing = run {
            val nrm = weldedNormals(m.pos, m.tris, m.nv)
            IntArray(m.nv) { i -> val x = nrm[i * 3]; val y = nrm[i * 3 + 1]; val z = nrm[i * 3 + 2]
                val ax = Math.abs(x); val ay = Math.abs(y); val az = Math.abs(z)
                if (ax >= ay && ax >= az) (if (x > 0) 0 else 1) else if (ay >= az) (if (y > 0) 2 else 3) else (if (z > 0) 4 else 5) }
        }
        var cells = 600
        var res: RawMesh = m
        for (iter in 0 until 14) {
            val cs = hi / cells; val uvc = 1f / cells
            val key = HashMap<Long, Int>(); val sum = ArrayList<Float>(); val cnt = ArrayList<Int>()
            // position cells are shared by every UV island touching them, so both sides of a UV seam end up at the
            // same point (separate cells per island would pull them apart and crack the surface)
            val pkey = HashMap<Long, Int>(); val psum = ArrayList<Float>(); val pcnt = ArrayList<Int>(); val pOf = ArrayList<Int>()
            val map = IntArray(m.nv)
            for (i in 0 until m.nv) {
                val pk = (Math.floor((m.pos[i * 3] / cs).toDouble()).toLong() * 73856093L) xor (Math.floor((m.pos[i * 3 + 1] / cs).toDouble()).toLong() * 19349663L) xor
                        (Math.floor((m.pos[i * 3 + 2] / cs).toDouble()).toLong() * 83492791L) xor (facing[i].toLong() * 1000003L)
                val pc = pkey.getOrPut(pk) { pcnt.add(0); repeat(3) { psum.add(0f) }; pcnt.size - 1 }
                pcnt[pc] = pcnt[pc] + 1; for (q in 0..2) psum[pc * 3 + q] = psum[pc * 3 + q] + m.pos[i * 3 + q]
                val k = (pk * 31) + (Math.floor((m.uv[i * 2] / uvc).toDouble()).toLong() * 2654435761L) + Math.floor((m.uv[i * 2 + 1] / uvc).toDouble()).toLong()
                val c = key.getOrPut(k) { cnt.add(0); repeat(5) { sum.add(0f) }; pOf.add(pc); cnt.size - 1 }
                map[i] = c; cnt[c] = cnt[c] + 1
                sum[c * 5 + 3] = sum[c * 5 + 3] + m.uv[i * 2]; sum[c * 5 + 4] = sum[c * 5 + 4] + m.uv[i * 2 + 1]
            }
            for (c in 0 until cnt.size) { val pc = pOf[c]; for (q in 0..2) sum[c * 5 + q] = psum[pc * 3 + q] / pcnt[pc] * cnt[c] }
            val t = ArrayList<Int>()
            var i = 0
            while (i < m.tris.size) { val a = map[m.tris[i]]; val b = map[m.tris[i + 1]]; val c = map[m.tris[i + 2]]; if (a != b && b != c && a != c) { t.add(a); t.add(b); t.add(c) }; i += 3 }
            val n = cnt.size
            res = RawMesh(FloatArray(n * 3) { sum[(it / 3) * 5 + it % 3] / cnt[it / 3] }, FloatArray(n * 2) { sum[(it / 2) * 5 + 3 + it % 2] / cnt[it / 2] }, t.toIntArray(), m.texture, m.textureExt)
            if (t.size / 3 <= targetTris * 1.3) break
            cells = (cells * 0.8).toInt().coerceAtLeast(12)
        }
        return res
    }
}
