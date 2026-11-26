package com.hcode91.pockethuman

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads a texture's colour at a UV (Android: Bitmap). */
fun interface ColorSampler { fun rgb(u: Float, v: Float): Int }

/** Closest-point queries against the current body surface (base-mesh units). */
class BodyQuery(model: HumanModel) {
    val v = model.verts
    val tris: IntArray               // base-vertex triangles of the visible body
    val vn: FloatArray
    init {
        val bm = model.lib.base
        tris = IntArray(bm.tris.size) { bm.renderMap[bm.tris[it]] }
        vn = weldedNormals(v, tris, bm.nV)
        // make sure normals point out of the body (depends on the mesh's winding)
        var cx = 0.0; var cy = 0.0; var cz = 0.0; for (i in 0 until 13380) { cx += v[i * 3]; cy += v[i * 3 + 1]; cz += v[i * 3 + 2] }
        cx /= 13380; cy /= 13380; cz /= 13380
        var sgn = 0.0; for (i in 0 until 13380) sgn += vn[i * 3] * (v[i * 3] - cx) + vn[i * 3 + 1] * (v[i * 3 + 1] - cy) + vn[i * 3 + 2] * (v[i * 3 + 2] - cz)
        if (sgn < 0) for (i in vn.indices) vn[i] = -vn[i]
    }

    class Hit(val tri: Int, val w0: Float, val w1: Float, val w2: Float, val x: Float, val y: Float, val z: Float, val dist: Float)

    // vertex grid + vertex->triangle lists: nearest vertex first, then the triangles around it
    private val vcell = 0.35f
    private val vgrid = HashMap<Long, IntArray>()
    private val vtris: Array<IntArray>
    init {
        val nb = 13380
        val tmp = HashMap<Long, ArrayList<Int>>()
        val used = BooleanArray(model.lib.base.nV); tris.forEach { used[it] = true }
        for (i in 0 until model.lib.base.nV) if (used[i]) tmp.getOrPut(vkey(v[i * 3], v[i * 3 + 1], v[i * 3 + 2])) { ArrayList() }.add(i)
        for ((k, l) in tmp) vgrid[k] = l.toIntArray()
        val adj = Array(model.lib.base.nV) { ArrayList<Int>(6) }
        for (t in 0 until tris.size / 3) for (q in 0..2) adj[tris[t * 3 + q]].add(t)
        vtris = Array(adj.size) { adj[it].toIntArray() }
    }
    private fun vc(x: Float) = Math.floor((x / vcell).toDouble()).toLong()
    private fun vkey(x: Float, y: Float, z: Float) = (vc(x) * 73856093L) xor (vc(y) * 19349663L) xor (vc(z) * 83492791L)

    /** Nearest point on the body: nearest body vertex, then the closest point on its surrounding triangles. */
    fun nearest(px: Float, py: Float, pz: Float, maxR: Float = 6f): Hit? {
        val cx = vc(px); val cy = vc(py); val cz = vc(pz)
        var bestV = -1; var bestD = Float.MAX_VALUE; var r = 0L
        while (r * vcell <= maxR + vcell) {
            for (a in cx - r..cx + r) for (b in cy - r..cy + r) for (d in cz - r..cz + r) {
                if (maxOf(Math.abs(a - cx), Math.abs(b - cy), Math.abs(d - cz)) != r) continue
                vgrid[(a * 73856093L) xor (b * 19349663L) xor (d * 83492791L)]?.forEach { i ->
                    val dx = v[i * 3] - px; val dy = v[i * 3 + 1] - py; val dz = v[i * 3 + 2] - pz
                    val dd = dx * dx + dy * dy + dz * dz; if (dd < bestD) { bestD = dd; bestV = i } }
            }
            if (bestV >= 0 && Math.sqrt(bestD.toDouble()) < r * vcell) break
            r++
        }
        if (bestV < 0) return null
        var best: Hit? = null
        for (t in vtris[bestV]) { val h = closest(t, px, py, pz); if (best == null || h.dist < best.dist) best = h }
        return best
    }
    private fun closest(t: Int, px: Float, py: Float, pz: Float): Hit {
        val a = tris[t * 3] * 3; val b = tris[t * 3 + 1] * 3; val cc = tris[t * 3 + 2] * 3
        val abx = v[b] - v[a]; val aby = v[b + 1] - v[a + 1]; val abz = v[b + 2] - v[a + 2]
        val acx = v[cc] - v[a]; val acy = v[cc + 1] - v[a + 1]; val acz = v[cc + 2] - v[a + 2]
        val apx = px - v[a]; val apy = py - v[a + 1]; val apz = pz - v[a + 2]
        val d1 = abx * apx + aby * apy + abz * apz; val d2 = acx * apx + acy * apy + acz * apz
        fun hit(u: Float, w: Float): Hit {   // barycentric (1-u-w, u, w)
            val x = v[a] + u * abx + w * acx; val y = v[a + 1] + u * aby + w * acy; val z = v[a + 2] + u * abz + w * acz
            val dx = px - x; val dy = py - y; val dz = pz - z
            return Hit(t, 1 - u - w, u, w, x, y, z, Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat())
        }
        if (d1 <= 0 && d2 <= 0) return hit(0f, 0f)
        val bpx = px - v[b]; val bpy = py - v[b + 1]; val bpz = pz - v[b + 2]
        val d3 = abx * bpx + aby * bpy + abz * bpz; val d4 = acx * bpx + acy * bpy + acz * bpz
        if (d3 >= 0 && d4 <= d3) return hit(1f, 0f)
        val vc = d1 * d4 - d3 * d2
        if (vc <= 0 && d1 >= 0 && d3 <= 0) return hit(d1 / (d1 - d3), 0f)
        val cpx = px - v[cc]; val cpy = py - v[cc + 1]; val cpz = pz - v[cc + 2]
        val d5 = abx * cpx + aby * cpy + abz * cpz; val d6 = acx * cpx + acy * cpy + acz * cpz
        if (d6 >= 0 && d5 <= d6) return hit(0f, 1f)
        val vb = d5 * d2 - d1 * d6
        if (vb <= 0 && d2 >= 0 && d6 <= 0) return hit(0f, d2 / (d2 - d6))
        val va = d3 * d6 - d5 * d4
        if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) { val w = (d4 - d3) / ((d4 - d3) + (d5 - d6)); return hit(1 - w, w) }
        val den = 1f / (va + vb + vc); return hit(vb * den, vc * den)
    }
    fun normalAt(h: Hit): FloatArray {
        val n = FloatArray(3)
        for (k in 0..2) { val ws = floatArrayOf(h.w0, h.w1, h.w2); for (q in 0..2) n[k] += ws[q] * vn[tris[h.tri * 3 + q] * 3 + k] }
        val l = Math.sqrt((n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).toDouble()).toFloat().coerceAtLeast(1e-6f)
        return floatArrayOf(n[0] / l, n[1] / l, n[2] / l)
    }
}

/**
 * An imported outfit being fitted to the body. All settings are simple numbers so they can be saved and re-edited.
 * Units: base-mesh units (decimetres), y up, facing +z.
 */
class OutfitDraft(val id: String, var name: String, val raw: RawMesh, shared: LinkedHashMap<String, Float>? = null) {
    private var lite: OutfitDraft? = null
    /** Same outfit and settings on a lighter mesh, for live preview while editing. */
    fun previewDraft(): OutfitDraft {
        if (raw.tris.size / 3 <= 20000) return this
        val l = lite ?: OutfitDraft(id, name, MeshIO.simplify(raw, 15000), p).also { lite = it }
        l.autoScale = autoScale; l.autoShift = autoShift.copyOf(); return l
    }
    val p: LinkedHashMap<String, Float> = shared ?: linkedMapOf(
        "turn" to 0f, "upright" to 0f,                 // fix orientation: turn (x90°), upright (x90° about x)
        "size" to 1f, "width" to 1f, "height" to 1f, "depth" to 1f,
        "up" to 0f, "forward" to 0f, "side" to 0f,
        "armL" to 0f, "armR" to 0f, "legs" to 0f,      // degrees
        "loosen" to 0f,                                // move along the body normal (dm)
        "clip" to 0.05f,                               // keep at least this far outside the body (dm); 0 = off
        "skinCut" to 0.5f,                             // remove skin-coloured parts near the body (0 = off)
        "hideBody" to 1f                               // hide body skin underneath
    )
    var autoScale = 1f; var autoShift = FloatArray(3)

    fun toJson() = JSONObject().put("id", id).put("name", name).put("params", JSONObject(p as Map<*, *>)).put("autoScale", autoScale.toDouble())
        .put("autoShift", JSONArray(autoShift.toList()))
    fun loadParams(j: JSONObject) {
        j.optJSONObject("params")?.let { o -> for (k in p.keys) if (o.has(k)) p[k] = o.getDouble(k).toFloat() }
        autoScale = j.optDouble("autoScale", 1.0).toFloat()
        j.optJSONArray("autoShift")?.let { a -> for (k in 0..2) autoShift[k] = a.getDouble(k).toFloat() }
        name = j.optString("name", name)
    }

    private fun oriented(): FloatArray {
        val out = raw.pos.copyOf()
        val up = ((p["upright"]!!.toInt() % 4) + 4) % 4; val turn = ((p["turn"]!!.toInt() % 4) + 4) % 4
        for (i in 0 until raw.nv) {
            var x = out[i * 3]; var y = out[i * 3 + 1]; var z = out[i * 3 + 2]
            repeat(up) { val ny = -z; val nz = y; y = ny; z = nz }          // +90° about x (z-up files)
            repeat(turn) { val nx = z; val nz = -x; x = nx; z = nz }        // +90° about y
            out[i * 3] = x; out[i * 3 + 1] = y; out[i * 3 + 2] = z
        }
        return out
    }

    /** First guess: metres -> decimetres, full figures matched to the body's height, centred on the body. */
    fun autoPlace(body: BodyQuery) {
        val o = oriented()
        val bb = bounds(o); val bodyB = bounds(body.v, 13380)
        var s = if (bb[4] - bb[1] < 5f) 10f else 1f
        val h = (bb[4] - bb[1]) * s; val bodyH = bodyB[4] - bodyB[1]
        val full = h > bodyH * 0.75f
        if (full) s *= bodyH / h
        autoScale = s
        autoShift[0] = (bodyB[0] + bodyB[3]) / 2 - s * (bb[0] + bb[3]) / 2
        autoShift[2] = (bodyB[2] + bodyB[5]) / 2 - s * (bb[2] + bb[5]) / 2
        autoShift[1] = if (full) bodyB[1] - s * bb[1] else (bodyB[1] + bodyB[4]) / 2 - s * (bb[1] + bb[4]) / 2
    }
    private fun bounds(a: FloatArray, n: Int = a.size / 3): FloatArray {
        val r = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until n) for (k in 0..2) { r[k] = minOf(r[k], a[i * 3 + k]); r[3 + k] = maxOf(r[3 + k], a[i * 3 + k]) }
        return r
    }

    class Fitted(val pos: FloatArray, val keepTris: IntArray, val hiddenBody: BooleanArray, val hits: Array<BodyQuery.Hit?>)

    fun compute(model: HumanModel, body: BodyQuery, colors: ColorSampler?): Fitted {
        val o = oriented(); val n = raw.nv
        // centre for width/height/depth scaling = body centre
        val bodyB = bounds(body.v, 13380); val cx = (bodyB[0] + bodyB[3]) / 2; val cy = (bodyB[1] + bodyB[4]) / 2; val cz = (bodyB[2] + bodyB[5]) / 2
        val pos = FloatArray(n * 3)
        val sz = p["size"]!!
        for (i in 0 until n) {
            val x = o[i * 3] * autoScale + autoShift[0]; val y = o[i * 3 + 1] * autoScale + autoShift[1]; val z = o[i * 3 + 2] * autoScale + autoShift[2]
            // size changes are made around the body's centre so the outfit stays on the body
            pos[i * 3] = cx + (x - cx) * sz * p["width"]!! + p["side"]!!
            pos[i * 3 + 1] = cy + (y - cy) * sz * p["height"]!! + p["up"]!!
            pos[i * 3 + 2] = cz + (z - cz) * sz * p["depth"]!! + p["forward"]!!
        }
        // pose fixes: turn arms/legs of the outfit about the body's shoulder/hip joints, weighted by the nearest body skin weights
        val rig = model.lib.rig; val heads = PartBuilder.jointHeads(model)
        fun boneIdx(n: String) = rig.bones.indexOfFirst { it.name == "mixamorig:$n" }
        val chains = mapOf(
            "armL" to Triple(boneIdx("LeftArm"), rig.bones.indices.filter { rig.bones[it].name.contains("Left") && (rig.bones[it].name.contains("Arm") || rig.bones[it].name.contains("Hand")) }.toSet(), 1f),
            "armR" to Triple(boneIdx("RightArm"), rig.bones.indices.filter { rig.bones[it].name.contains("Right") && (rig.bones[it].name.contains("Arm") || rig.bones[it].name.contains("Hand")) }.toSet(), -1f))
        val legsL = boneIdx("LeftUpLeg") to rig.bones.indices.filter { rig.bones[it].name.contains("Left") && (rig.bones[it].name.contains("Leg") || rig.bones[it].name.contains("Foot") || rig.bones[it].name.contains("Toe")) }.toSet()
        val legsR = boneIdx("RightUpLeg") to rig.bones.indices.filter { rig.bones[it].name.contains("Right") && (rig.bones[it].name.contains("Leg") || rig.bones[it].name.contains("Foot") || rig.bones[it].name.contains("Toe")) }.toSet()
        val hits = arrayOfNulls<BodyQuery.Hit>(n)
        for (i in 0 until n) hits[i] = body.nearest(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2])
        fun chainWeight(i: Int, set: Set<Int>): Float {
            val h = hits[i] ?: return 0f; var w = 0f; val ws = floatArrayOf(h.w0, h.w1, h.w2)
            for (q in 0..2) { val bv = body.tris[h.tri * 3 + q]; for (k in 0..3) if (rig.joints[bv * 4 + k] in set) w += ws[q] * rig.weights[bv * 4 + k] }
            return w
        }
        fun rotateZ(i: Int, pivot: FloatArray, deg: Float) {
            if (deg == 0f) return
            val a = Math.toRadians(deg.toDouble()); val c = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
            val x = pos[i * 3] - pivot[0]; val y = pos[i * 3 + 1] - pivot[1]
            pos[i * 3] = pivot[0] + c * x - sn * y; pos[i * 3 + 1] = pivot[1] + sn * x + c * y
        }
        val moved = BooleanArray(n)
        for ((k, ch) in chains) { val deg = p[k]!!; if (deg == 0f || ch.first < 0) continue
            for (i in 0 until n) { val w = chainWeight(i, ch.second); if (w > 0.01f) { rotateZ(i, heads[ch.first], deg * w * ch.third); moved[i] = true } } }
        val lg = p["legs"]!!
        if (lg != 0f) for ((b, set) in listOf(legsL, legsR)) { if (b < 0) continue; val sgn = if (set === legsL.second) 1f else -1f
            for (i in 0 until n) { val w = chainWeight(i, set); if (w > 0.01f) { rotateZ(i, heads[b], lg * w * sgn); moved[i] = true } } }
        for (i in 0 until n) if (moved[i]) hits[i] = body.nearest(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2])
        // loosen / tighten, then clipping fix along the body normal (repeated, since a push can land near another part of the body)
        val loosen = p["loosen"]!!; val clip = p["clip"]!!
        for (i in 0 until n) {
            val h = hits[i] ?: continue
            if (h.dist > 1.5f || loosen == 0f) continue          // far-away parts (sword, long hair…) are left alone
            val nn = body.normalAt(h); val push = loosen * (1f - h.dist / 1.5f).coerceAtLeast(0f)
            pos[i * 3] += nn[0] * push; pos[i * 3 + 1] += nn[1] * push; pos[i * 3 + 2] += nn[2] * push
        }
        if (clip > 0f) for (pass in 0 until 3) {
            var fixed = 0
            for (i in 0 until n) {
                val h = (if (pass == 0 && loosen == 0f) hits[i] else body.nearest(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2])) ?: continue
                if (h.dist > 1.5f) continue
                val nn = body.normalAt(h)
                val depth = (pos[i * 3] - h.x) * nn[0] + (pos[i * 3 + 1] - h.y) * nn[1] + (pos[i * 3 + 2] - h.z) * nn[2]
                if (depth < clip) { val push = clip - depth; pos[i * 3] += nn[0] * push; pos[i * 3 + 1] += nn[1] * push; pos[i * 3 + 2] += nn[2] * push; fixed++ }
            }
            if (fixed == 0) break
        }
        // remove the model's own skin (skin-coloured triangles lying on the body)
        val keep = ArrayList<Int>(raw.tris.size)
        val cut = p["skinCut"]!!
        val skinV = BooleanArray(n)
        if (cut > 0f && colors != null) for (i in 0 until n) {
            val h = hits[i] ?: continue
            if (h.dist > 0.3f + cut * 1.2f) continue
            skinV[i] = isSkin(colors.rgb(raw.uv[i * 2], raw.uv[i * 2 + 1]), cut)
        }
        var t = 0
        while (t < raw.tris.size) { val a = raw.tris[t]; val b = raw.tris[t + 1]; val c = raw.tris[t + 2]
            if (!(skinV[a] && skinV[b] && skinV[c])) { keep.add(a); keep.add(b); keep.add(c) }; t += 3 }
        // hide body skin that the outfit covers
        val hidden = BooleanArray(model.lib.base.nV)
        if (p["hideBody"]!! > 0.5f) {
            val used = BooleanArray(n); keep.forEach { used[it] = true }
            val cov = IntArray(model.lib.base.nV)
            for (i in 0 until n) { if (!used[i]) continue; val h = hits[i] ?: continue; if (h.dist > 0.6f) continue
                if (h.w0 > 0.2f) cov[body.tris[h.tri * 3]]++; if (h.w1 > 0.2f) cov[body.tris[h.tri * 3 + 1]]++; if (h.w2 > 0.2f) cov[body.tris[h.tri * 3 + 2]]++ }
            for (i in cov.indices) if (cov[i] > 0) hidden[i] = true
            // shrink the hidden area by one ring so no gaps show at the edges
            val edge = BooleanArray(hidden.size)
            var q = 0
            while (q < body.tris.size) { val a = body.tris[q]; val b = body.tris[q + 1]; val c = body.tris[q + 2]
                val h = (if (hidden[a]) 1 else 0) + (if (hidden[b]) 1 else 0) + (if (hidden[c]) 1 else 0)
                if (h in 1..2) { edge[a] = true; edge[b] = true; edge[c] = true }; q += 3 }
            for (i in hidden.indices) if (edge[i]) hidden[i] = false
        }
        return Fitted(pos, keep.toIntArray(), hidden, hits)
    }

    private fun isSkin(rgb: Int, sens: Float): Boolean {
        val r = (rgb shr 16) and 0xff; val g = (rgb shr 8) and 0xff; val b = rgb and 0xff
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        if (mx < 60) return false
        val sat = (mx - mn) / mx.toFloat()
        // warm hue (red > green > blue), moderate saturation: covers light to dark skin
        // skin is clearly warmer than off-white cloth: red well above blue, saturation 0.2-0.6
        return r > g && g >= b * 0.85f && r - b > 30 - 10 * sens && sat in (0.2f - 0.06f * sens)..(0.62f + 0.1f * sens)
    }

    /** Turn the fitted outfit into a regular clothing item (bound to the base mesh) and save it. */
    fun bake(model: HumanModel, body: BodyQuery, f: Fitted, filesDir: File, textures: TextureSink) {
        val dir = File(filesDir, "imported/my_outfits").apply { mkdirs() }
        File(dir, "proxies").mkdirs()
        // keep only used vertices
        val used = IntArray(raw.nv) { -1 }; var nU = 0
        for (x in f.keepTris) if (used[x] < 0) used[x] = nU++
        val idx = IntArray(nU); for (i in 0 until raw.nv) if (used[i] >= 0) idx[used[i]] = i
        val refs = IntArray(nU * 3); val w = FloatArray(nU * 3); val off = FloatArray(nU * 3)
        for (j in 0 until nU) {
            val i = idx[j]
            val h = f.hits[i] ?: body.nearest(f.pos[i * 3], f.pos[i * 3 + 1], f.pos[i * 3 + 2], 40f)!!
            val ws = floatArrayOf(h.w0, h.w1, h.w2)
            for (q in 0..2) { refs[j * 3 + q] = body.tris[h.tri * 3 + q]; w[j * 3 + q] = ws[q] }
            // position relative to the anchor point, recomputed from the triangle (exact)
            for (k in 0..2) { var a = 0f; for (q in 0..2) a += ws[q] * body.v[refs[j * 3 + q] * 3 + k]; off[j * 3 + k] = f.pos[i * 3 + k] - a }
        }
        val tris = IntArray(f.keepTris.size) { used[f.keepTris[it]] }
        val uv = FloatArray(nU * 2) { raw.uv[idx[it / 2] * 2 + it % 2] }
        val rmap = IntArray(nU) { it }
        // scale references: the same body measurements MakeHuman clothes use, so the outfit grows with the body
        val sa = intArrayOf(5399, 791, 962); val sb = intArrayOf(11998, 881, 5320)
        val sref = FloatArray(3) { k -> Math.abs(body.v[sa[k] * 3 + k] - body.v[sb[k] * 3 + k]) }
        val bb = ByteBuffer.allocate(8 + nU * 6 + nU * 24 + 36 + 4 + nU * 2 + nU * 8 + 4 + tris.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("PHP1".toByteArray()).putInt(nU)
        refs.forEach { bb.putShort(it.toShort()) }; w.forEach { bb.putFloat(it) }; off.forEach { bb.putFloat(it) }
        sa.forEach { bb.putInt(it) }; sref.forEach { bb.putFloat(it) }; sb.forEach { bb.putInt(it) }
        bb.putInt(nU); rmap.forEach { bb.putShort(it.toShort()) }; uv.forEach { bb.putFloat(it) }
        bb.putInt(tris.size / 3); tris.forEach { bb.putInt(it) }
        File(dir, "proxies/$id.bin").writeBytes(bb.array())
        // texture
        val texName: String = raw.texture?.let { t ->
            val tmp = File(dir, "tmp_$id.${raw.textureExt}").apply { writeBytes(t) }
            textures.write(tmp, File(dir, "proxies"), id, 2048).also { tmp.delete() }
        } ?: run {
            // no texture: plain grey
            val tmp = File(dir, "proxies/${id}.png"); if (!tmp.exists()) tmp.writeBytes(GREY_PNG); "${id}.png"
        }
        val hidden = f.hiddenBody.indices.filter { f.hiddenBody[it] }
        val entry = JSONObject().put("id", "my_$id").put("kind", "clothes").put("label", name).put("file", "imported/my_outfits/proxies/$id.bin")
            .put("delete", JSONArray(hidden)).put("zdepth", 60)
            .put("variants", JSONArray().put(JSONObject().put("name", "default").put("texture", "imported/my_outfits/proxies/$texName").put("alpha", texName.endsWith(".png") && raw.texture != null)))
            .put("credit", JSONObject().put("asset", name).put("pack", "My outfits").put("author", "you").put("license", "CC0"))
        // manifest (replace any older version of this outfit)
        val mf = File(dir, "manifest.json")
        val m = if (mf.exists()) JSONObject(mf.readText()) else JSONObject().put("id", "my_outfits").put("title", "My outfits").put("license", "yours")
            .put("proxies", JSONArray()).put("skins", JSONArray()).put("groups", JSONArray()).put("faceShapes", JSONArray()).put("credits", JSONArray())
        val old = m.getJSONArray("proxies"); val nw = JSONArray()
        for (i in 0 until old.length()) if (old.getJSONObject(i).getString("id") != "my_$id") nw.put(old.getJSONObject(i))
        nw.put(entry); m.put("proxies", nw); mf.writeText(m.toString())
        // keep the source and settings so it can be edited again
        val src = File(filesDir, "outfits/$id").apply { mkdirs() }
        File(src, "settings.json").writeText(toJson().toString())
        saveRaw(File(src, "mesh.bin"))
    }

    fun saveRaw(f: File) {
        val t = raw.texture ?: ByteArray(0)
        val bb = ByteBuffer.allocate(16 + raw.pos.size * 4 + raw.uv.size * 4 + raw.tris.size * 4 + t.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(raw.nv).putInt(raw.tris.size).putInt(t.size).putInt(if (raw.textureExt == "jpg") 1 else 0)
        raw.pos.forEach { bb.putFloat(it) }; raw.uv.forEach { bb.putFloat(it) }; raw.tris.forEach { bb.putInt(it) }; bb.put(t)
        f.writeBytes(bb.array())
    }

    companion object {
        fun loadSaved(filesDir: File, id: String): OutfitDraft? {
            val d = File(filesDir, "outfits/$id"); val mf = File(d, "mesh.bin"); if (!mf.exists()) return null
            val bb = ByteBuffer.wrap(mf.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val nv = bb.int; val nt = bb.int; val ts = bb.int; val ext = if (bb.int == 1) "jpg" else "png"
            val pos = FloatArray(nv * 3) { bb.float }; val uv = FloatArray(nv * 2) { bb.float }; val tris = IntArray(nt) { bb.int }
            val tex = if (ts > 0) ByteArray(ts).also { bb.get(it) } else null
            val j = JSONObject(File(d, "settings.json").readText())
            return OutfitDraft(id, j.optString("name", id), RawMesh(pos, uv, tris, tex, ext)).also { it.loadParams(j) }
        }
        fun delete(filesDir: File, id: String) {
            File(filesDir, "outfits/$id").deleteRecursively()
            val dir = File(filesDir, "imported/my_outfits"); val mf = File(dir, "manifest.json"); if (!mf.exists()) return
            val m = JSONObject(mf.readText()); val old = m.getJSONArray("proxies"); val nw = JSONArray()
            for (i in 0 until old.length()) if (old.getJSONObject(i).getString("id") != "my_$id") nw.put(old.getJSONObject(i))
            m.put("proxies", nw); mf.writeText(m.toString())
            File(dir, "proxies").listFiles()?.filter { it.name.startsWith(id) }?.forEach { it.delete() }
        }
        // 1x1 grey PNG
        val GREY_PNG: ByteArray = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGO4fPkyAAWOAsUoXmAAAAAASUVORK5CYII=")
    }
}
