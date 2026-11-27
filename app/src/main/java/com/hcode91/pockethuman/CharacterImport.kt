package com.hcode91.pockethuman

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes an ARGB image to a file (Android: Bitmap.compress). Returns the file name written. */
fun interface ImageWriter { fun write(argb: IntArray, w: Int, h: Int, dir: File, baseName: String): String }

/** Nearest-point queries on any triangle mesh, optionally only over some vertices (e.g. skin). */
class MeshQuery(private val v: FloatArray, private val tris: IntArray, mask: BooleanArray? = null, private val cell: Float = 0.3f) {
    class Hit(val tri: Int, val w0: Float, val w1: Float, val w2: Float, val x: Float, val y: Float, val z: Float, val dist: Float)
    private val grid = HashMap<Long, IntArray>()
    private val vtris: Array<IntArray>
    private val nv = v.size / 3
    init {
        val adj = Array(nv) { ArrayList<Int>(6) }
        for (t in 0 until tris.size / 3) for (q in 0..2) adj[tris[t * 3 + q]].add(t)
        vtris = Array(nv) { adj[it].toIntArray() }
        val tmp = HashMap<Long, ArrayList<Int>>()
        for (i in 0 until nv) if ((mask == null || mask[i]) && adj[i].isNotEmpty()) tmp.getOrPut(key(c(v[i * 3]), c(v[i * 3 + 1]), c(v[i * 3 + 2]))) { ArrayList() }.add(i)
        for ((k, l) in tmp) grid[k] = l.toIntArray()
    }
    private fun c(x: Float) = Math.floor((x / cell).toDouble()).toLong()
    private fun key(a: Long, b: Long, d: Long) = (a * 73856093L) xor (b * 19349663L) xor (d * 83492791L)
    val empty get() = grid.isEmpty()

    fun nearest(px: Float, py: Float, pz: Float, maxR: Float): Hit? {
        val cx = c(px); val cy = c(py); val cz = c(pz)
        var bestV = -1; var bestD = Float.MAX_VALUE; var r = 0L
        while (r * cell <= maxR + cell) {
            for (a in cx - r..cx + r) for (b in cy - r..cy + r) for (d in cz - r..cz + r) {
                if (maxOf(Math.abs(a - cx), Math.abs(b - cy), Math.abs(d - cz)) != r) continue
                grid[key(a, b, d)]?.forEach { i ->
                    val dx = v[i * 3] - px; val dy = v[i * 3 + 1] - py; val dz = v[i * 3 + 2] - pz
                    val dd = dx * dx + dy * dy + dz * dz; if (dd < bestD) { bestD = dd; bestV = i } }
            }
            if (bestV >= 0 && Math.sqrt(bestD.toDouble()) < r * cell) break
            r++
        }
        if (bestV < 0 || Math.sqrt(bestD.toDouble()) > maxR) return null
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
        fun hit(u: Float, w: Float): Hit {
            val x = v[a] + u * abx + w * acx; val y = v[a + 1] + u * aby + w * acy; val z = v[a + 2] + u * abz + w * acz
            val dx = px - x; val dy = py - y; val dz = pz - z
            return Hit(t, 1 - u - w, u, w, x, y, z, Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat())
        }
        if (d1 <= 0 && d2 <= 0) return hit(0f, 0f)
        val bpx = px - v[b]; val bpy = py - v[b + 1]; val bpz = pz - v[b + 2]
        val d3 = abx * bpx + aby * bpy + abz * bpz; val d4 = acx * bpx + acy * bpy + acz * bpz
        if (d3 >= 0 && d4 <= d3) return hit(1f, 0f)
        val vc = d1 * d4 - d3 * d2
        if (vc <= 0 && d1 >= 0 && d3 <= 0 && d1 - d3 != 0f) return hit(d1 / (d1 - d3), 0f)
        val cpx = px - v[cc]; val cpy = py - v[cc + 1]; val cpz = pz - v[cc + 2]
        val d5 = abx * cpx + aby * cpy + abz * cpz; val d6 = acx * cpx + acy * cpy + acz * cpz
        if (d6 >= 0 && d5 <= d6) return hit(0f, 1f)
        val vb = d5 * d2 - d1 * d6
        if (vb <= 0 && d2 >= 0 && d6 <= 0 && d2 - d6 != 0f) return hit(0f, d2 / (d2 - d6))
        val va = d3 * d6 - d5 * d4
        if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) { val w = (d4 - d3) / ((d4 - d3) + (d5 - d6)); if (w.isFinite()) return hit(1 - w, w) }
        val den = 1f / (va + vb + vc)
        // a squashed (zero-area) triangle, common in AI meshes: fall back to its nearest corner
        if (!den.isFinite() || !(vb * den).isFinite() || !(vc * den).isFinite()) {
            val da = apx * apx + apy * apy + apz * apz; val db = bpx * bpx + bpy * bpy + bpz * bpz; val dc = cpx * cpx + cpy * cpy + cpz * cpz
            return if (da <= db && da <= dc) hit(0f, 0f) else if (db <= dc) hit(1f, 0f) else hit(0f, 1f)
        }
        return hit(vb * den, vc * den)
    }
}

/**
 * Turns a lined-up full-character model (e.g. from Meshy) into a PocketHuman character:
 * a custom body shape slider, a skin texture taken from the model, and the model's clothes and hair as an outfit.
 */
object CharacterImport {
    const val BODY_VERTS = 13380

    /** Skin-coloured vertices of the imported model. */
    fun skinMask(raw: RawMesh, colors: ColorSampler?): BooleanArray =
        BooleanArray(raw.nv) { i -> colors != null && OutfitDraft.isSkinColour(colors.rgb(raw.uv[i * 2], raw.uv[i * 2 + 1]), 0.6f) }

    /**
     * Offsets that move the body surface onto the model's bare skin (face, arms, legs…), spread smoothly
     * into the covered areas. Returns base-mesh offsets (decimetres), nV*3.
     */
    fun shapeDelta(model: HumanModel, fitted: FloatArray, raw: RawMesh, skin: BooleanArray, progress: (String) -> Unit): FloatArray {
        val bm = model.lib.base; val v0 = model.verts; val nV = bm.nV
        val bodyTris = IntArray(bm.tris.size) { bm.renderMap[bm.tris[it]] }
        // 1. move and scale the head so the body's face sits on the model's face (AI models often have bigger heads)
        val v = v0.copyOf()
        run {
            val rig = model.lib.rig
            val head = rig.bones.indexOfFirst { it.name == "mixamorig:Head" }
            val hw = FloatArray(nV) { i -> var w = 0f; for (k in 0..3) if (rig.joints[i * 4 + k] == head) w += rig.weights[i * 4 + k]; w }
            val n0 = weldedNormals(v0, bodyTris, nV)
            val face = (0 until BODY_VERTS).filter { hw[it] > 0.9f && n0[it * 3 + 2] > 0.5f }
            if (face.size < 50) return@run
            fun stats(pts: List<FloatArray>): FloatArray {     // centre x,y,z and robust width/height
                val xs = pts.map { it[0] }.sorted(); val ys = pts.map { it[1] }.sorted(); val zs = pts.map { it[2] }.sorted()
                fun q(a: List<Float>, f: Float) = a[(f * (a.size - 1)).toInt()]
                return floatArrayOf((q(xs, 0.05f) + q(xs, 0.95f)) / 2, (q(ys, 0.05f) + q(ys, 0.95f)) / 2, q(zs, 0.9f), q(xs, 0.95f) - q(xs, 0.05f), q(ys, 0.95f) - q(ys, 0.05f))
            }
            val bs = stats(face.map { floatArrayOf(v0[it * 3], v0[it * 3 + 1], v0[it * 3 + 2]) })
            val neckY = bs[1] - bs[4] * 0.9f
            val cand = (0 until raw.nv).filter { skin[it] && fitted[it * 3 + 1] > neckY && fitted[it * 3 + 1] < bs[1] + bs[4] * 1.5f &&
                Math.abs(fitted[it * 3] - bs[0]) < bs[3] * 1.2f && Math.abs(fitted[it * 3 + 2] - bs[2]) < bs[3] * 1.5f }
            if (cand.size < 50) return@run
            val zMed = cand.map { fitted[it * 3 + 2] }.sorted()[cand.size / 2]
            val front = cand.filter { fitted[it * 3 + 2] >= zMed }      // the face, not the neck sides
            val ms = stats(front.map { floatArrayOf(fitted[it * 3], fitted[it * 3 + 1], fitted[it * 3 + 2]) })
            val sc = (((ms[3] / bs[3]) + (ms[4] / bs[4])) / 2).coerceIn(0.8f, 1.35f)
            val t = floatArrayOf(ms[0] - bs[0], ms[1] - bs[1], ms[2] - bs[2])
            progress("Matching the head (scale ${"%.2f".format(sc)})…")
            for (i in 0 until nV) { val w = hw[i]; if (w <= 0f) continue
                for (k in 0..2) { val c = bs[k]; v[i * 3 + k] = v0[i * 3 + k] + w * ((c + sc * (v0[i * 3 + k] - c) + t[k]) - v0[i * 3 + k]) } }
        }
        val nrm = weldedNormals(v, bodyTris, nV)
        val q = MeshQuery(fitted, raw.tris, skin)
        val d = FloatArray(nV * 3); val known = FloatArray(nV)
        if (!q.empty) for (i in 0 until BODY_VERTS) {
            if (i % 2000 == 0) progress("Matching body shape… ${i * 100 / BODY_VERTS}%")
            val h = q.nearest(v[i * 3], v[i * 3 + 1], v[i * 3 + 2], 0.6f) ?: continue
            // move along the body's own normal only, so the surface doesn't slide sideways
            val nx = nrm[i * 3]; val ny = nrm[i * 3 + 1]; val nz = nrm[i * 3 + 2]
            val along = ((h.x - v[i * 3]) * nx + (h.y - v[i * 3 + 1]) * ny + (h.z - v[i * 3 + 2]) * nz).coerceIn(-0.4f, 0.4f)
            val trust = (1f - h.dist / 0.6f).coerceIn(0f, 1f)
            d[i * 3] = nx * along; d[i * 3 + 1] = ny * along; d[i * 3 + 2] = nz * along; known[i] = trust
        }
        // spread into areas the model covers with clothes or hair: smooth, fading to zero far from known skin
        progress("Smoothing…")
        val nbr = Array(nV) { HashSet<Int>() }
        var t = 0
        while (t < bodyTris.size) { val a = bodyTris[t]; val b = bodyTris[t + 1]; val c = bodyTris[t + 2]
            nbr[a].add(b); nbr[a].add(c); nbr[b].add(a); nbr[b].add(c); nbr[c].add(a); nbr[c].add(b); t += 3 }
        val adj = Array(nV) { nbr[it].toIntArray() }
        var cur = d.copyOf()
        repeat(40) {
            val nx = FloatArray(nV * 3)
            for (i in 0 until BODY_VERTS) {
                val a = adj[i]; if (a.isEmpty()) continue
                for (k in 0..2) { var s = 0f; for (j in a) s += cur[j * 3 + k]; val avg = s / a.size
                    // trusted points stay close to their match, others relax towards the neighbours (with a slight pull to zero)
                    nx[i * 3 + k] = known[i] * d[i * 3 + k] + (1 - known[i]) * avg * 0.97f + known[i] * 0f }
            }
            cur = nx
        }
        // a final light smoothing everywhere to remove scan noise
        repeat(3) {
            val nx = cur.copyOf()
            for (i in 0 until BODY_VERTS) { val a = adj[i]; if (a.isEmpty()) continue
                for (k in 0..2) { var s = 0f; for (j in a) s += cur[j * 3 + k]; nx[i * 3 + k] = 0.5f * cur[i * 3 + k] + 0.5f * s / a.size } }
            cur = nx
        }
        for (i in 0 until BODY_VERTS) for (k in 0..2) cur[i * 3 + k] += v[i * 3 + k] - v0[i * 3 + k]
        for (i in BODY_VERTS until nV) for (k in 0..2) cur[i * 3 + k] = 0f
        // helper geometry (eyes, teeth, hair helpers) follows the nearest body vertex so proxies stay attached
        val bodyQ = MeshQuery(v0, bodyTris, BooleanArray(nV) { it < BODY_VERTS })
        for (i in BODY_VERTS until nV) {
            val h = bodyQ.nearest(v0[i * 3], v0[i * 3 + 1], v0[i * 3 + 2], 2f) ?: continue
            for (k in 0..2) cur[i * 3 + k] = h.w0 * cur[bodyTris[h.tri * 3] * 3 + k] + h.w1 * cur[bodyTris[h.tri * 3 + 1] * 3 + k] + h.w2 * cur[bodyTris[h.tri * 3 + 2] * 3 + k]
        }
        return cur
    }

    /** Characters made by "Import a character": one per saved body shape. Pairs of (shape slider id, name). */
    fun characters(filesDir: File): List<Pair<String, String>> {
        val mf = File(filesDir, "imported/my_shapes/manifest.json"); if (!mf.exists()) return emptyList()
        val sl = JSONObject(mf.readText()).getJSONArray("groups").getJSONObject(0).getJSONArray("sliders")
        return (0 until sl.length()).map { sl.getJSONObject(it).let { o -> o.getString("id") to o.getString("label") } }
    }

    /** Removes a character's body shape and painted skin(s). The outfit is removed separately. */
    fun deleteCharacter(filesDir: File, shapeId: String, name: String) {
        val sd = File(filesDir, "imported/my_shapes"); val mf = File(sd, "manifest.json")
        if (mf.exists()) { val m = JSONObject(mf.readText()); val g = m.getJSONArray("groups").getJSONObject(0); val old = g.getJSONArray("sliders"); val nw = JSONArray()
            for (i in 0 until old.length()) if (old.getJSONObject(i).getString("id") != shapeId) nw.put(old.getJSONObject(i))
            g.put("sliders", nw); mf.writeText(m.toString())
            val ij = File(sd, "targets.json"); if (ij.exists()) { val idx = JSONObject(ij.readText()); idx.remove(shapeId); ij.writeText(idx.toString()) } }
        val kd = File(filesDir, "imported/my_skins"); val kf = File(kd, "manifest.json")
        if (kf.exists()) { val m = JSONObject(kf.readText()); val old = m.getJSONArray("skins"); val nw = JSONArray()
            for (i in 0 until old.length()) { val o = old.getJSONObject(i)
                if (o.getString("name") == name) File(filesDir, o.getString("texture")).delete() else nw.put(o) }
            m.put("skins", nw); kf.writeText(m.toString()) }
    }

    /** Saves a body-shape offset field as a slider in the "My shapes" pack. Returns the slider id. */
    fun saveShape(filesDir: File, name: String, delta: FloatArray): String {
        val dir = File(filesDir, "imported/my_shapes").apply { mkdirs() }
        val key = "shape_" + System.currentTimeMillis().toString(36)
        val idx = ArrayList<Int>(); for (i in 0 until delta.size / 3) if (Math.abs(delta[i * 3]) + Math.abs(delta[i * 3 + 1]) + Math.abs(delta[i * 3 + 2]) > 1e-4f) idx.add(i)
        val bb = ByteBuffer.allocate(idx.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        idx.forEach { bb.putShort(it.toShort()) }; idx.forEach { i -> for (k in 0..2) bb.putShort(Half.toHalf(delta[i * 3 + k])) }
        // append to the pack's target blob
        val bin = File(dir, "targets.bin"); val old = if (bin.exists()) bin.readBytes() else ByteArray(0)
        bin.writeBytes(old + bb.array())
        val ij = File(dir, "targets.json"); val index = if (ij.exists()) JSONObject(ij.readText()) else JSONObject()
        val tname = "my_shapes/custom/$key"
        index.put(tname, JSONArray(listOf(old.size, idx.size))); ij.writeText(index.toString())
        val mf = File(dir, "manifest.json")
        val m = if (mf.exists()) JSONObject(mf.readText()) else JSONObject().put("id", "my_shapes").put("title", "My shapes").put("license", "yours")
            .put("proxies", JSONArray()).put("skins", JSONArray()).put("faceShapes", JSONArray()).put("credits", JSONArray())
            .put("groups", JSONArray().put(JSONObject().put("group", "My shapes").put("adult", false).put("sliders", JSONArray())))
        m.getJSONArray("groups").getJSONObject(0).getJSONArray("sliders")
            .put(JSONObject().put("id", tname).put("label", name).put("neg", JSONArray()).put("pos", JSONArray().put(tname)))
        mf.writeText(m.toString())
        return tname
    }

    /**
     * Paints a body skin texture (MakeHuman UV layout) from the model's bare skin; covered areas use the current
     * skin, tinted to match. Saved in the "My skins" pack; returns its texture path.
     */
    fun projectSkin(model: HumanModel, fitted: FloatArray, raw: RawMesh, skin: BooleanArray, modelTex: ColorSampler, baseSkin: ColorSampler,
                    size: Int, filesDir: File, name: String, writer: ImageWriter, progress: (String) -> Unit): String {
        val bm = model.lib.base; val v = model.verts
        val q = MeshQuery(fitted, raw.tris, skin, 0.25f)
        // average colours to tint the fallback skin
        var mr = 0.0; var mg = 0.0; var mb = 0.0; var mn = 0
        for (i in 0 until raw.nv step 7) if (skin[i]) { val c = modelTex.rgb(raw.uv[i * 2], raw.uv[i * 2 + 1]); mr += (c shr 16) and 0xff; mg += (c shr 8) and 0xff; mb += c and 0xff; mn++ }
        var br = 0.0; var bg = 0.0; var bbv = 0.0; var bn = 0
        for (y in 0 until 64) for (x in 0 until 64) { val c = baseSkin.rgb(x / 64f, y / 64f); val l = ((c shr 16) and 0xff) + ((c shr 8) and 0xff) + (c and 0xff); if (l in 120..720) { br += (c shr 16) and 0xff; bg += (c shr 8) and 0xff; bbv += c and 0xff; bn++ } }
        val tr = if (mn > 0 && bn > 0) ((mr / mn) / (br / bn)).toFloat() else 1f
        val tg = if (mn > 0 && bn > 0) ((mg / mn) / (bg / bn)).toFloat() else 1f
        val tb = if (mn > 0 && bn > 0) ((mb / mn) / (bbv / bn)).toFloat() else 1f
        val img = IntArray(size * size)
        for (i in img.indices) { val c = baseSkin.rgb((i % size + 0.5f) / size, (i / size + 0.5f) / size)
            img[i] = (0xff shl 24) or (minOf(255, (((c shr 16) and 0xff) * tr).toInt()) shl 16) or (minOf(255, (((c shr 8) and 0xff) * tg).toInt()) shl 8) or minOf(255, ((c and 0xff) * tb).toInt()) }
        if (!q.empty) {
            val tris = bm.tris; val uv = bm.uv
            for (t in 0 until tris.size / 3) {
                if (t % 3000 == 0) progress("Painting skin… ${t * 100 / (tris.size / 3)}%")
                val ia = tris[t * 3]; val ib = tris[t * 3 + 1]; val ic = tris[t * 3 + 2]
                val ax = uv[ia * 2] * size; val ay = uv[ia * 2 + 1] * size; val bx = uv[ib * 2] * size; val by = uv[ib * 2 + 1] * size; val cx = uv[ic * 2] * size; val cy = uv[ic * 2 + 1] * size
                val den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy); if (Math.abs(den) < 1e-9f) continue
                val pa = bm.renderMap[ia] * 3; val pb = bm.renderMap[ib] * 3; val pc = bm.renderMap[ic] * 3
                for (py in maxOf(0, Math.floor(minOf(ay, by, cy).toDouble()).toInt())..minOf(size - 1, Math.ceil(maxOf(ay, by, cy).toDouble()).toInt()))
                    for (px in maxOf(0, Math.floor(minOf(ax, bx, cx).toDouble()).toInt())..minOf(size - 1, Math.ceil(maxOf(ax, bx, cx).toDouble()).toInt())) {
                        val sx = px + 0.5f; val sy = py + 0.5f
                        val w0 = ((by - cy) * (sx - cx) + (cx - bx) * (sy - cy)) / den; val w1 = ((cy - ay) * (sx - cx) + (ax - cx) * (sy - cy)) / den; val w2 = 1 - w0 - w1
                        if (w0 < -0.02f || w1 < -0.02f || w2 < -0.02f) continue
                        val x = w0 * v[pa] + w1 * v[pb] + w2 * v[pc]; val y = w0 * v[pa + 1] + w1 * v[pb + 1] + w2 * v[pc + 1]; val z = w0 * v[pa + 2] + w1 * v[pb + 2] + w2 * v[pc + 2]
                        val h = q.nearest(x, y, z, 0.3f) ?: continue
                        val a2 = raw.tris[h.tri * 3]; val b2 = raw.tris[h.tri * 3 + 1]; val c2 = raw.tris[h.tri * 3 + 2]
                        val u = h.w0 * raw.uv[a2 * 2] + h.w1 * raw.uv[b2 * 2] + h.w2 * raw.uv[c2 * 2]; val vv = h.w0 * raw.uv[a2 * 2 + 1] + h.w1 * raw.uv[b2 * 2 + 1] + h.w2 * raw.uv[c2 * 2 + 1]
                        val col = modelTex.rgb(u, vv)
                        val k = ((0.3f - h.dist) / 0.15f).coerceIn(0f, 1f)        // fade to the fallback away from the model's skin
                        val o = img[py * size + px]
                        fun mix(sh: Int) = (((col shr sh) and 0xff) * k + ((o shr sh) and 0xff) * (1 - k)).toInt()
                        img[py * size + px] = (0xff shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
                    }
            }
        }
        val dir = File(filesDir, "imported/my_skins").apply { mkdirs() }; File(dir, "skins").mkdirs()
        val key = "skin_" + System.currentTimeMillis().toString(36)
        val file = writer.write(img, size, size, File(dir, "skins"), key)
        val mf = File(dir, "manifest.json")
        val m = if (mf.exists()) JSONObject(mf.readText()) else JSONObject().put("id", "my_skins").put("title", "My skins").put("license", "yours")
            .put("proxies", JSONArray()).put("skins", JSONArray()).put("groups", JSONArray()).put("faceShapes", JSONArray()).put("credits", JSONArray())
        val path = "imported/my_skins/skins/$file"
        m.getJSONArray("skins").put(JSONObject().put("name", name).put("texture", path).put("adult", false).put("system", false).put("gender", "female").put("age", "young"))
        mf.writeText(m.toString())
        return path
    }
}

/** The imported model itself, rigged to the body's skeleton (no body sliders). */
object AsIsExport {
    fun part(model: HumanModel, body: BodyQuery, d: OutfitDraft, f: OutfitDraft.Fitted): Pair<Part, Map<String, ByteArray>> {
        val rig = model.lib.rig; val n = d.raw.nv; val nb = rig.bones.size
        val j = IntArray(n * 4); val w = FloatArray(n * 4); val acc = FloatArray(nb)
        for (i in 0 until n) {
            val h = f.hits[i] ?: body.nearest(f.pos[i * 3], f.pos[i * 3 + 1], f.pos[i * 3 + 2], 40f) ?: continue
            java.util.Arrays.fill(acc, 0f); val ws = floatArrayOf(h.w0, h.w1, h.w2)
            for (q in 0..2) { val bv = body.tris[h.tri * 3 + q]; for (k in 0..3) acc[rig.joints[bv * 4 + k]] += ws[q] * rig.weights[bv * 4 + k] }
            val order = (0 until nb).sortedByDescending { acc[it] }.take(4); var s = 0f; for (q in order) s += acc[q]
            for (q in 0..3) { j[i * 4 + q] = order[q]; w[i * 4 + q] = if (s > 0) acc[order[q]] / s else if (q == 0) 1f else 0f }
        }
        val texPath = "asis/${d.id}.${d.raw.textureExt}"
        val tex = d.raw.texture ?: OutfitDraft.GREY_PNG
        val path = if (d.raw.texture != null) texPath else "asis/grey.png"
        val p = Part("my_" + d.name.replace(' ', '_'), f.pos, weldedNormals(f.pos, d.raw.tris, n), d.raw.uv, d.raw.tris, path, false, null, j, w, IntArray(0), FloatArray(0), 0)
        return p to mapOf(path to tex)
    }
}


/**
 * "Use my model": an imported model used as the character itself, in place of the PocketHuman body.
 * Saved in imported/my_models/<id>/ already lined up with the body's skeleton and skin-weighted to it.
 */
object ModelStore {
    fun dir(filesDir: File, id: String) = File(filesDir, "imported/my_models/$id")

    fun save(filesDir: File, id: String, name: String, part: Part, texture: ByteArray, textureExt: String) {
        val d = dir(filesDir, id).apply { deleteRecursively(); mkdirs() }
        val tex = "texture.$textureExt"; File(d, tex).writeBytes(texture)
        val n = part.pos.size / 3
        val bb = java.nio.ByteBuffer.allocate(8 + n * 12 + n * 12 + n * 8 + n * 16 + n * 16 + 4 + part.tris.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("PHM1".toByteArray()).putInt(n)
        part.pos.forEach { bb.putFloat(it) }; part.nrm.forEach { bb.putFloat(it) }; part.uv.forEach { bb.putFloat(it) }
        part.joints.forEach { bb.putInt(it) }; part.weights.forEach { bb.putFloat(it) }
        bb.putInt(part.tris.size / 3); part.tris.forEach { bb.putInt(it) }
        File(d, "mesh.bin").writeBytes(bb.array())
        File(d, "model.json").writeText(JSONObject().put("name", name).put("texture", tex).toString())
    }

    fun list(filesDir: File): List<Pair<String, String>> =
        File(filesDir, "imported/my_models").listFiles()?.filter { File(it, "model.json").exists() }?.sortedBy { it.lastModified() }
            ?.map { it.name to JSONObject(File(it, "model.json").readText()).optString("name", it.name) } ?: emptyList()

    fun name(filesDir: File, id: String): String? = runCatching { JSONObject(File(dir(filesDir, id), "model.json").readText()).getString("name") }.getOrNull()

    fun load(filesDir: File, id: String): Part? = runCatching {
        val d = dir(filesDir, id); val meta = JSONObject(File(d, "model.json").readText())
        val bb = java.nio.ByteBuffer.wrap(File(d, "mesh.bin").readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN); bb.position(4)
        val n = bb.int
        val pos = FloatArray(n * 3) { bb.float }; val nrm = FloatArray(n * 3) { bb.float }; val uv = FloatArray(n * 2) { bb.float }
        val j = IntArray(n * 4) { bb.int }; val w = FloatArray(n * 4) { bb.float }
        val nt = bb.int; val tris = IntArray(nt * 3) { bb.int }
        Part("my_model", pos, nrm, uv, tris, "imported/my_models/$id/" + meta.getString("texture"), false, null, j, w, IntArray(0), FloatArray(0), 0)
    }.getOrNull()

    fun delete(filesDir: File, id: String) = dir(filesDir, id).deleteRecursively()
}
