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
    val p: LinkedHashMap<String, Float> = shared ?: linkedMapOf(
        "turn" to 0f, "upright" to 0f,                 // fix orientation: turn (x90°), upright (x90° about x)
        "size" to 1f, "width" to 1f, "height" to 1f, "depth" to 1f,
        "up" to 0f, "forward" to 0f, "side" to 0f,
        "armL" to 0f, "armR" to 0f, "legs" to 0f,      // degrees
        "loosen" to 0f,                                // move along the body normal (dm)
        "clip" to 0.05f,                               // keep at least this far outside the body (dm); 0 = off
        "skinCut" to 0.5f,                             // remove skin-coloured parts near the body (0 = off)
        "hideBody" to 1f,                              // hide body skin underneath
        "kind" to 0f                                   // what it is, for auto-fit: see KINDS
    )
    var autoScale = 1f; var autoShift = FloatArray(3)

    /** Brush strokes (grab / shrinkwrap / smooth / puff), replayed in order on any version of the mesh. */
    class Stroke(val type: Int, val c: FloatArray, val r: Float, val d: FloatArray, val strength: Float)
    val strokes = ArrayList<Stroke>()

    fun toJson() = JSONObject().put("id", id).put("name", name).put("params", JSONObject(p as Map<*, *>)).put("autoScale", autoScale.toDouble())
        .put("autoShift", JSONArray(autoShift.toList()))
        .put("strokes", JSONArray(strokes.map { st -> JSONArray(listOf(st.type, st.c[0], st.c[1], st.c[2], st.r, st.d[0], st.d[1], st.d[2], st.strength)) }))
    fun loadParams(j: JSONObject) {
        j.optJSONObject("params")?.let { o -> for (k in p.keys) if (o.has(k)) p[k] = o.getDouble(k).toFloat() }
        autoScale = j.optDouble("autoScale", 1.0).toFloat()
        j.optJSONArray("autoShift")?.let { a -> for (k in 0..2) autoShift[k] = a.getDouble(k).toFloat() }
        name = j.optString("name", name)
        strokes.clear()
        j.optJSONArray("strokes")?.let { a -> for (i in 0 until a.length()) { val x = a.getJSONArray(i); fun f(k: Int) = x.getDouble(k).toFloat()
            strokes.add(Stroke(x.getInt(0), floatArrayOf(f(1), f(2), f(3)), f(4), floatArrayOf(f(5), f(6), f(7)), f(8))) } }
    }

    /**
     * Garments from AI tools come at arbitrary sizes (Meshy scales everything to the same box), so try a range of
     * sizes and heights and keep the one where the garment wraps the body most snugly.
     */
    fun autoFit(body: BodyQuery) {
        val o = oriented(); val bb = bounds(o); val bodyB = bounds(body.v, 13380)
        val rawH = (bb[4] - bb[1]).coerceAtLeast(1e-6f); val bodyH = bodyB[4] - bodyB[1]
        // where on the body this kind of garment sits (fractions of body height from the feet)
        val (lo, hi) = KIND_SPANS[p["kind"]!!.toInt().coerceIn(0, KIND_SPANS.size - 1)]
        val yLo = bodyB[1] + lo * bodyH; val yHi = bodyB[1] + hi * bodyH
        val n = raw.nv; val step = maxOf(1, n / 400)
        val sample = (0 until n step step).toList()
        val cx = (bodyB[0] + bodyB[3]) / 2; val cz = (bodyB[2] + bodyB[5]) / 2
        var best = -1e9f; var bs = 1f; var by = 0f
        // body points the garment ought to cover: torso-ish verts within the garment's span (skip far-out arms/hands)
        val halfW = 0.22f * bodyH
        val cover = ArrayList<Int>(); run { var i = 0; while (i < 13380) { val y = body.v[i * 3 + 1]; val x = body.v[i * 3]
            if (y in yLo..yHi && Math.abs(x - cx) < halfW) cover.add(i); i += 7 } }
        val cs = 0.45f
        val gSample = (0 until n step maxOf(1, n / 2500)).toList()
        fun coverage(sc: Float, yb: Float): Float {
            if (cover.isEmpty()) return 0f
            val cells = HashSet<Long>(gSample.size * 2)
            for (i in gSample) {
                val x = (o[i * 3] - (bb[0] + bb[3]) / 2) * sc + cx; val y = (o[i * 3 + 1] - bb[1]) * sc + yb; val z = (o[i * 3 + 2] - (bb[2] + bb[5]) / 2) * sc + cz
                cells.add(key(Math.floor((x / cs).toDouble()).toInt(), Math.floor((y / cs).toDouble()).toInt(), Math.floor((z / cs).toDouble()).toInt()))
            }
            var hit = 0
            for (v in cover) { val gx = Math.floor((body.v[v * 3] / cs).toDouble()).toInt(); val gy = Math.floor((body.v[v * 3 + 1] / cs).toDouble()).toInt(); val gz = Math.floor((body.v[v * 3 + 2] / cs).toDouble()).toInt()
                var f = false; loop@ for (a in -1..1) for (b in -1..1) for (c in -1..1) if (cells.contains(key(gx + a, gy + b, gz + c))) { f = true; break@loop }
                if (f) hit++ }
            return hit.toFloat() / cover.size
        }
        fun closeness(sc: Float, yb: Float): Float {
            var good = 0; var bad = 0; var far = 0
            for (i in sample) {
                val x = (o[i * 3] - (bb[0] + bb[3]) / 2) * sc + cx; val y = (o[i * 3 + 1] - bb[1]) * sc + yb; val z = (o[i * 3 + 2] - (bb[2] + bb[5]) / 2) * sc + cz
                val h = body.nearest(x, y, z, 1.2f); if (h == null) { far++; continue }
                val nn = body.normalAt(h); val depth = (x - h.x) * nn[0] + (y - h.y) * nn[1] + (z - h.z) * nn[2]
                if (depth < -0.15f) bad++ else if (depth < 0.5f) good++ else far++
            }
            return (good - 2f * bad - 0.5f * far) / sample.size
        }
        fun score(sc: Float, yb: Float): Float = closeness(sc, yb) + 1.5f * coverage(sc, yb)
        val span = yHi - yLo
        for (f in listOf(0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f, 1.1f, 1.2f)) {
            val sc = span * f / rawH; val gh = rawH * sc
            var yb = yLo - 0.1f * span
            while (yb + gh <= yHi + 0.1f * span) { val v = score(sc, yb); if (v > best) { best = v; bs = sc; by = yb }; yb += maxOf(0.2f, span / 25) }
        }
        // refine around the best guess
        for (r in 0 until 2) { val d = if (r == 0) 0.06f else 0.025f; val dy = if (r == 0) 0.25f else 0.1f
            var b2 = best; var s2 = bs; var y2 = by
            for (a in -2..2) for (b in -2..2) { val sc = bs * (1 + a * d); val yb = by + b * dy
                if (yb < yLo - 0.1f * span || yb + rawH * sc > yHi + 0.1f * span) continue
                val v = score(sc, yb); if (v > b2) { b2 = v; s2 = sc; y2 = yb } }
            best = b2; bs = s2; by = y2 }
        autoScale = bs
        // a single garment has no skin of its own: dark marks in its texture shouldn't be cut out as skin
        val k = p["kind"]!!.toInt(); if (k != 0 && k != 4) p["skinCut"] = 0f
        autoShift[0] = cx - bs * (bb[0] + bb[3]) / 2; autoShift[1] = by - bs * bb[1]; autoShift[2] = cz - bs * (bb[2] + bb[5]) / 2
    }

    private fun key(x: Int, y: Int, z: Int): Long = ((x + 100000).toLong() shl 42) or ((y + 100000).toLong() shl 21) or (z + 100000).toLong()

    private var adjCache: Array<IntArray>? = null
    private fun adjacency(): Array<IntArray> = adjCache ?: run {
        val cnt = IntArray(raw.nv); raw.tris.forEach { cnt[it] += 2 }
        val a = Array(raw.nv) { IntArray(cnt[it]) }; val fill = IntArray(raw.nv); var t = 0
        while (t < raw.tris.size) { val v = intArrayOf(raw.tris[t], raw.tris[t + 1], raw.tris[t + 2])
            for (q in 0..2) { val x = v[q]; a[x][fill[x]++] = v[(q + 1) % 3]; a[x][fill[x]++] = v[(q + 2) % 3] }; t += 3 }
        a.also { adjCache = it }
    }
    private fun falloff(d: Float, r: Float): Float { if (d >= r) return 0f; val t = 1 - d / r; return t * t * (3 - 2 * t) }

    /** Apply one stroke to positions (also used live while dragging). */
    fun applyStroke(st: Stroke, pos: FloatArray, body: BodyQuery) {
        val n = pos.size / 3; val r = st.r
        when (st.type) {
            GRAB -> for (i in 0 until n) { val dx = pos[i * 3] - st.c[0]; val dy = pos[i * 3 + 1] - st.c[1]; val dz = pos[i * 3 + 2] - st.c[2]
                val w = falloff(Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat(), r); if (w > 0) for (k in 0..2) pos[i * 3 + k] += w * st.d[k] }
            SHRINK, PUFF -> for (i in 0 until n) { val dx = pos[i * 3] - st.c[0]; val dy = pos[i * 3 + 1] - st.c[1]; val dz = pos[i * 3 + 2] - st.c[2]
                val w = falloff(Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat(), r) * st.strength; if (w <= 0) continue
                val h = body.nearest(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2], 3f) ?: continue; val nn = body.normalAt(h)
                if (st.type == SHRINK) { val gap = maxOf(p["clip"]!!, 0.02f); for (k in 0..2) { val tgt = floatArrayOf(h.x, h.y, h.z)[k] + nn[k] * gap; pos[i * 3 + k] += w * (tgt - pos[i * 3 + k]) } }
                else for (k in 0..2) pos[i * 3 + k] += w * nn[k] * 0.15f }
            SMOOTH -> { val adj = adjacency(); val src = pos.copyOf()
                for (i in 0 until n) { val dx = src[i * 3] - st.c[0]; val dy = src[i * 3 + 1] - st.c[1]; val dz = src[i * 3 + 2] - st.c[2]
                    val w = falloff(Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat(), r) * st.strength; if (w <= 0 || adj[i].isEmpty()) continue
                    for (k in 0..2) { var a = 0f; for (j in adj[i]) a += src[j * 3 + k]; pos[i * 3 + k] += w * (a / adj[i].size - src[i * 3 + k]) } } }
        }
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

    /** Orientation, auto placement and the size/position sliders only. */
    fun placed(body: BodyQuery): FloatArray {
        val o = oriented(); val n = raw.nv
        // centre for width/height/depth scaling = body centre
        val bodyB = bounds(body.v, 13380); val cx = (bodyB[0] + bodyB[3]) / 2; val cy = (bodyB[1] + bodyB[4]) / 2; val cz = (bodyB[2] + bodyB[5]) / 2
        val pos = FloatArray(n * 3)
        val sz = p["size"]!!; val w = p["width"]!!; val h = p["height"]!!; val d = p["depth"]!!
        for (i in 0 until n) {
            val x = o[i * 3] * autoScale + autoShift[0]; val y = o[i * 3 + 1] * autoScale + autoShift[1]; val z = o[i * 3 + 2] * autoScale + autoShift[2]
            // size changes are made around the body's centre so the outfit stays on the body
            pos[i * 3] = cx + (x - cx) * sz * w + p["side"]!!
            pos[i * 3 + 1] = cy + (y - cy) * sz * h + p["up"]!!
            pos[i * 3 + 2] = cz + (z - cz) * sz * d + p["forward"]!!
        }
        return pos
    }

    // ---- fast preview: fit a welded low-poly cage (shape only, no UVs) and carry the result over to every vertex
    private var cage: OutfitDraft? = null; private var cageMap: IntArray? = null; private var cageAdj: Array<IntArray>? = null
    private fun buildCage() {
        val m = raw; var lo = FloatArray(3) { Float.MAX_VALUE }; var hi = FloatArray(3) { -Float.MAX_VALUE }
        for (i in 0 until m.nv) for (k in 0..2) { lo[k] = minOf(lo[k], m.pos[i * 3 + k]); hi[k] = maxOf(hi[k], m.pos[i * 3 + k]) }
        val ext = maxOf(hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]).coerceAtLeast(1e-6f)
        var cells = 90; var map = IntArray(m.nv); var cent = FloatArray(0); var t = IntArray(0)
        for (iter in 0 until 8) {
            val cs = ext / cells; val key = HashMap<Long, Int>(); val sum = ArrayList<Float>(); val cnt = ArrayList<Int>()
            for (i in 0 until m.nv) {
                val k = key(Math.floor(((m.pos[i * 3] - lo[0]) / cs).toDouble()).toInt(), Math.floor(((m.pos[i * 3 + 1] - lo[1]) / cs).toDouble()).toInt(), Math.floor(((m.pos[i * 3 + 2] - lo[2]) / cs).toDouble()).toInt())
                val c = key.getOrPut(k) { cnt.add(0); repeat(3) { sum.add(0f) }; cnt.size - 1 }
                map[i] = c; cnt[c] = cnt[c] + 1; for (q in 0..2) sum[c * 3 + q] = sum[c * 3 + q] + m.pos[i * 3 + q]
            }
            cent = FloatArray(cnt.size * 3) { sum[it] / cnt[it / 3] }
            if (cnt.size <= 12000 || cells <= 30) {
                val tl = ArrayList<Int>(); var q = 0
                while (q < m.tris.size) { val a = map[m.tris[q]]; val b = map[m.tris[q + 1]]; val c = map[m.tris[q + 2]]; if (a != b && b != c && a != c) { tl.add(a); tl.add(b); tl.add(c) }; q += 3 }
                t = tl.toIntArray(); break
            }
            cells = (cells * 0.8f).toInt(); map = IntArray(m.nv)
        }
        val nc = cent.size / 3
        val adj = Array(nc) { HashSet<Int>() }; var q = 0
        while (q < t.size) { val a = t[q]; val b = t[q + 1]; val c = t[q + 2]; adj[a].add(b); adj[a].add(c); adj[b].add(a); adj[b].add(c); adj[c].add(a); adj[c].add(b); q += 3 }
        cage = OutfitDraft(id, name, RawMesh(cent, FloatArray(nc * 2), t, null, "png"), p); cageMap = map; cageAdj = Array(nc) { adj[it].toIntArray() }
    }

    /** Same result as [compute] but quick on big meshes: used for the live preview. */
    fun computePreview(model: HumanModel, body: BodyQuery, colors: ColorSampler?): Fitted {
        if (raw.nv <= 30000) return compute(model, body, colors)
        if (cage == null) buildCage()
        val c = cage!!; val map = cageMap!!; val adj = cageAdj!!
        c.autoScale = autoScale; c.autoShift = autoShift.copyOf(); c.strokes.clear(); c.strokes.addAll(strokes)
        val keepP = HashMap(p); p["skinCut"] = 0f
        val fc = try { c.compute(model, body, null) } finally { p.putAll(keepP) }
        val cp = c.placed(body); val nc = cp.size / 3
        var d = FloatArray(nc * 3) { fc.pos[it] - cp[it] }
        // one smoothing pass so neighbouring clusters move together
        d = FloatArray(nc * 3) { j -> val i = j / 3; val k = j % 3; val a = adj[i]; if (a.isEmpty()) d[j] else { var s = d[j]; for (x in a) s += d[x * 3 + k]; s / (a.size + 1) } }
        val pos = placed(body); val n = raw.nv
        for (i in 0 until n) { val ci = map[i]; for (k in 0..2) pos[i * 3 + k] += d[ci * 3 + k] }
        val hits = Array(n) { fc.hits[map[it]] }
        val cut = p["skinCut"]!!; val skinV = BooleanArray(n)
        if (cut > 0f && colors != null) for (i in 0 until n) { val h = hits[i] ?: continue; if (h.dist > 0.3f + cut * 1.2f) continue; skinV[i] = isSkin(colors.rgb(raw.uv[i * 2], raw.uv[i * 2 + 1]), cut) }
        val keep = ArrayList<Int>(raw.tris.size); var t = 0
        while (t < raw.tris.size) { val a = raw.tris[t]; val b = raw.tris[t + 1]; val cc = raw.tris[t + 2]; if (!(skinV[a] && skinV[b] && skinV[cc])) { keep.add(a); keep.add(b); keep.add(cc) }; t += 3 }
        return Fitted(pos, keep.toIntArray(), fc.hiddenBody, hits)
    }

    class Fitted(val pos: FloatArray, val keepTris: IntArray, val hiddenBody: BooleanArray, val hits: Array<BodyQuery.Hit?>)

    fun compute(model: HumanModel, body: BodyQuery, colors: ColorSampler?): Fitted {
        val n = raw.nv
        val pos = placed(body)
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
        if (strokes.isNotEmpty()) { for (st in strokes) applyStroke(st, pos, body); for (i in 0 until n) hits[i] = body.nearest(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]) }
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
            // a body vertex is covered when garment lies over it: outward along its normal, close sideways
            val r = 0.3f; val cells = HashMap<Long, ArrayList<Int>>()
            for (i in 0 until n) if (used[i]) cells.getOrPut(key(Math.floor((pos[i * 3] / r).toDouble()).toInt(), Math.floor((pos[i * 3 + 1] / r).toDouble()).toInt(), Math.floor((pos[i * 3 + 2] / r).toDouble()).toInt())) { ArrayList() }.add(i)
            val bodyUsed = BooleanArray(model.lib.base.nV); body.tris.forEach { bodyUsed[it] = true }
            for (i in 0 until model.lib.base.nV) { if (!bodyUsed[i]) continue
                val bx = body.v[i * 3]; val by = body.v[i * 3 + 1]; val bz = body.v[i * 3 + 2]
                val nx = body.vn[i * 3]; val ny = body.vn[i * 3 + 1]; val nz = body.vn[i * 3 + 2]
                val gx = Math.floor((bx / r).toDouble()).toInt(); val gy = Math.floor((by / r).toDouble()).toInt(); val gz = Math.floor((bz / r).toDouble()).toInt()
                var f = false
                loop@ for (a2 in -1..1) for (b2 in -1..1) for (c2 in -1..1) { val l = cells[key(gx + a2, gy + b2, gz + c2)] ?: continue
                    for (j in l) { val dx = pos[j * 3] - bx; val dy = pos[j * 3 + 1] - by; val dz = pos[j * 3 + 2] - bz
                        val along = dx * nx + dy * ny + dz * nz; if (along < -0.03f || along > 0.3f) continue
                        val side2 = dx * dx + dy * dy + dz * dz - along * along
                        if (side2 < 0.22f * 0.22f) { f = true; break@loop } } }
                if (f) hidden[i] = true }
            // fill speckles: a vertex whose triangles are mostly hidden around it gets hidden too (twice)
            repeat(2) {
                val hc = IntArray(hidden.size); val tc = IntArray(hidden.size)
                var q = 0
                while (q < body.tris.size) { val a = body.tris[q]; val b = body.tris[q + 1]; val c = body.tris[q + 2]
                    val h = (if (hidden[a]) 1 else 0) + (if (hidden[b]) 1 else 0) + (if (hidden[c]) 1 else 0)
                    for (x in intArrayOf(a, b, c)) { tc[x]++; if (h >= 2) hc[x]++ }; q += 3 }
                for (i in hidden.indices) if (!hidden[i] && tc[i] > 0 && hc[i] * 3 >= tc[i] * 2) hidden[i] = true
            }
            // then pull the edge in by one ring so no gaps show where the cloth ends
            run { val edge = BooleanArray(hidden.size); var q = 0
                while (q < body.tris.size) { val a = body.tris[q]; val b = body.tris[q + 1]; val c = body.tris[q + 2]
                    val h = (if (hidden[a]) 1 else 0) + (if (hidden[b]) 1 else 0) + (if (hidden[c]) 1 else 0)
                    if (h in 1..2) { edge[a] = true; edge[b] = true; edge[c] = true }; q += 3 }
                for (i in hidden.indices) if (edge[i]) hidden[i] = false }
        }
        return Fitted(pos, keep.toIntArray(), hidden, hits)
    }

    private fun isSkin(rgb: Int, sens: Float) = isSkinColour(rgb, sens)

    /** Fitted positions with no clean-up (no skin removal, clipping or loosening): the model exactly as lined up. */
    fun alignedOnly(model: HumanModel, body: BodyQuery): Fitted {
        val keep = HashMap(p); p["skinCut"] = 0f; p["clip"] = 0f; p["loosen"] = 0f; p["hideBody"] = 0f
        try { return compute(model, body, null) } finally { p.putAll(keep) }
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
        // PHP2: like PHP1 but the render map is 32-bit, for outfits with more than 65,535 vertices
        val bb = ByteBuffer.allocate(8 + nU * 6 + nU * 24 + 36 + 4 + nU * 4 + nU * 8 + 4 + tris.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("PHP2".toByteArray()).putInt(nU)
        refs.forEach { bb.putShort(it.toShort()) }; w.forEach { bb.putFloat(it) }; off.forEach { bb.putFloat(it) }
        sa.forEach { bb.putInt(it) }; sref.forEach { bb.putFloat(it) }; sb.forEach { bb.putInt(it) }
        bb.putInt(nU); rmap.forEach { bb.putInt(it) }; uv.forEach { bb.putFloat(it) }
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
        const val GRAB = 0; const val SHRINK = 1; const val SMOOTH = 2; const val PUFF = 3
        val KINDS = listOf("Anything", "Top", "Bottoms", "Dress", "Full outfit", "Shoes", "Hat")
        val KIND_SPANS = listOf(0f to 1.02f, 0.45f to 0.86f, 0f to 0.64f, 0.1f to 0.86f, 0f to 0.95f, 0f to 0.12f, 0.84f to 1.04f)
        /** Skin is clearly warmer than off-white cloth: red well above blue, saturation about 0.2-0.6. */
        fun isSkinColour(rgb: Int, sens: Float): Boolean {
            val r = (rgb shr 16) and 0xff; val g = (rgb shr 8) and 0xff; val b = rgb and 0xff
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            if (mx < 60) return false
            val sat = (mx - mn) / mx.toFloat()
            return r > g && g >= b * 0.85f && r - b > 30 - 10 * sens && sat in (0.2f - 0.06f * sens)..(0.62f + 0.1f * sens)
        }
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
        // 4x4 grey PNG
        val GREY_PNG: ByteArray = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAFUlEQVR4XmPcsmULAwwwwVkMDHg4AGp8AiSGjGjAAAAAAElFTkSuQmCC")
    }
}
