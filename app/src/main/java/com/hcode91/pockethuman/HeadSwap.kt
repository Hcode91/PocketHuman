package com.hcode91.pockethuman

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Replace the PocketHuman head with a head from a 3D model (e.g. Meshy).
 *
 *  1. Find the neck on the model (narrowest slice under the head), cut the head off and keep only the head pieces.
 *  2. Line it up on the PocketHuman neck (size, position, tilt; the user can adjust).
 *  3. Shrink-wrap the PocketHuman head onto the new head, so every point of the new head knows which point of the
 *     PocketHuman face it sits on. Through that link it moves with the skeleton and with every face shape
 *     (expressions, blinks, the iPhone-style set), in the app and in exports.
 *  4. Blend the neck seam into the body, shape and colour, and match the head's skin tone to the body.
 *  5. Optionally put the app's own eyeballs and lashes in, so the eyes can blink and look around.
 */
class HeadData(
    val id: String, val name: String,
    val refs: IntArray, val w: FloatArray, val off: FloatArray,     // 3 base vertices + weights + offset per vertex
    val uv: FloatArray, val tris: IntArray, val texture: String,
    val hidden: IntArray,                                          // base vertices of the PocketHuman head to hide
    val dispIdx: IntArray, val disp: FloatArray,                   // shrink-wrap of the PocketHuman head (moves the app eyes into the new head)
    val appEyes: Boolean,
    val lids: IntArray = IntArray(0),                              // app eyelid vertices kept (moved into the new head) so the eyes can blink
    val lidTexture: String? = null                                 // the body skin tinted to the new face, for those eyelids
) {
    val n get() = uv.size / 2
    /** welded neighbours of each vertex (for smoothing the normals of noisy AI surfaces) */
    val nbrs: Array<IntArray> by lazy {
        val sets = Array(n) { HashSet<Int>(8) }
        for (t in 0 until tris.size / 3) { val a = tris[t * 3]; val b = tris[t * 3 + 1]; val c = tris[t * 3 + 2]
            sets[a].add(b); sets[a].add(c); sets[b].add(a); sets[b].add(c); sets[c].add(a); sets[c].add(b) }
        Array(n) { sets[it].toIntArray() }
    }
}

object HeadSwap {
    /** most triangles kept for a head */
    const val MAX_TRIS = 300_000
    @Volatile var debug: ((String) -> Unit)? = null
    class Params {
        var turn = 0                       // x90° about the vertical
        var size = 1f; var up = 0f; var forward = 0f; var side = 0f; var tilt = 0f
        var skinMatch = 1f                 // 0..1, how far to pull the head's skin tone to the body's
        var appEyes = true
        var eyeUp = 0f; var eyeApart = 0f; var eyeDepth = 0f   // fine-tuning of where the app's eyes sit (dm)
        var lidShade = 0.82f                                  // brightness of the app's eyelids against the new face
        fun toJson() = JSONObject().put("turn", turn).put("size", size.toDouble()).put("up", up.toDouble()).put("forward", forward.toDouble())
            .put("side", side.toDouble()).put("tilt", tilt.toDouble()).put("skinMatch", skinMatch.toDouble()).put("appEyes", appEyes)
            .put("eyeUp", eyeUp.toDouble()).put("eyeApart", eyeApart.toDouble()).put("eyeDepth", eyeDepth.toDouble()).put("lidShade", lidShade.toDouble())
        fun load(j: JSONObject) { turn = j.optInt("turn"); size = j.optDouble("size", 1.0).toFloat(); up = j.optDouble("up").toFloat(); forward = j.optDouble("forward").toFloat()
            side = j.optDouble("side").toFloat(); tilt = j.optDouble("tilt").toFloat(); skinMatch = j.optDouble("skinMatch", 1.0).toFloat(); appEyes = j.optBoolean("appEyes", true)
            eyeUp = j.optDouble("eyeUp").toFloat(); eyeApart = j.optDouble("eyeApart").toFloat(); eyeDepth = j.optDouble("eyeDepth").toFloat(); lidShade = j.optDouble("lidShade", 0.82).toFloat() }
    }

    /** The cut-off head, in decimetres, with the middle of the neck cut at the origin. */
    class Prepared(val pos: FloatArray, val uv: FloatArray, val tris: IntArray, val texture: ByteArray?, val textureExt: String, val full: Boolean, val chin: Float) {
        val n get() = pos.size / 3
    }

    /** Where the PocketHuman neck and head are (neutral face): cut height, neck centre x, neck front z, crown, chin, nose. */
    class AppRef(val cutY: Float, val ax: Float, val front: Float, val top: Float, val chin: Float, val nose: Float, val lo: Float)

    /**
     * Side profile of a figure: for each slice from the top, how far forward the middle strip of it reaches.
     * The nose is the most forward point near the top; the chin is where the profile falls back to the neck.
     */
    private class Profile(val noseY: Float, val chinY: Float)
    private fun profile(p: FloatArray, n: Int, hi: Float, h: Float, cx: Float, strip: Float, full: Boolean): Profile {
        val slices = 240; val sh = h / slices
        val front = FloatArray(slices) { -Float.MAX_VALUE }; val cnt = IntArray(slices)
        for (i in 0 until n) { if (Math.abs(p[i * 3] - cx) > strip) continue
            val s = ((hi - p[i * 3 + 1]) / sh).toInt(); if (s < 0 || s >= slices) continue
            cnt[s]++; if (p[i * 3 + 2] > front[s]) front[s] = p[i * 3 + 2] }
        // fill thin slices from their neighbours
        for (s in 0 until slices) if (cnt[s] < 3) front[s] = Float.NaN
        var last = Float.NaN; for (s in 0 until slices) { if (front[s].isNaN()) front[s] = last else last = front[s] }
        // 5-tap median: single odd slices (a gap in the mesh, a stray strand) don't count
        val med = FloatArray(slices) { s -> val w = (s - 2..s + 2).map { front[it.coerceIn(0, slices - 1)] }.filter { !it.isNaN() }.sorted(); if (w.isEmpty()) Float.NaN else w[w.size / 2] }
        System.arraycopy(med, 0, front, 0, slices)
        val (a0, a1, span) = if (full) Triple(0.03f, 0.16f, 0.08f) else Triple(0.08f, 0.7f, 0.35f)
        var nose = (a0 * slices).toInt()
        for (s in (a0 * slices).toInt()..(a1 * slices).toInt()) if (!front[s].isNaN() && (front[nose].isNaN() || front[s] > front[nose] + 1e-4f)) nose = s
        // the chin: the sharpest step back after the nose (jaw line to neck)
        val end = minOf(slices - 4, nose + (span * slices).toInt())
        var chin = end; var best = -Float.MAX_VALUE
        for (s in nose + 3..end) { val a = front[s - 3]; val c = front[s + 3]; if (a.isNaN() || c.isNaN()) continue
            if (a - c > best) { best = a - c; chin = s } }
        return Profile(hi - (nose + 0.5f) * sh, hi - chin * sh)
    }

    fun appRef(model: HumanModel): AppRef {
        val v = model.neutral; val bones = model.lib.rig.bones; val heads = PartBuilder.jointHeads(model)
        val neck = heads[bones.indexOfFirst { it.name == "mixamorig:Neck" }]; val head = heads[bones.indexOfFirst { it.name == "mixamorig:Head" }]
        val cutY = neck[1] + 0.45f * (head[1] - neck[1])
        var top = -Float.MAX_VALUE; var lo = Float.MAX_VALUE; var front = -Float.MAX_VALUE
        for (i in 0 until 13380) {
            val x = v[i * 3]; val y = v[i * 3 + 1]; val z = v[i * 3 + 2]
            if (y > top) top = y; if (y < lo) lo = y
            if (Math.abs(x - head[0]) < 0.25f && Math.abs(y - cutY) < 0.12f && z > front) front = z
        }
        val pr = profile(v, 13380, top, top - lo, head[0], 0.25f, true)
        return AppRef(cutY, head[0], front, top, pr.chinY, pr.noseY, lo)
    }

    /** Finds and cuts out the head, sized to the PocketHuman head by its chin (hair doesn't count). */
    fun prepare(raw: RawMesh, turn: Int, model: HumanModel): Prepared {
        val n = raw.nv; val o = raw.pos.copyOf()
        val t = ((turn % 4) + 4) % 4
        for (i in 0 until n) { var x = o[i * 3]; var z = o[i * 3 + 2]; repeat(t) { val nx = z; val nz = -x; x = nx; z = nz }; o[i * 3] = x; o[i * 3 + 2] = z }
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE; var xl = Float.MAX_VALUE; var xh = -Float.MAX_VALUE
        for (i in 0 until n) { lo = minOf(lo, o[i * 3 + 1]); hi = maxOf(hi, o[i * 3 + 1]) }
        val unit = if (hi - lo < 5f) 10f else 1f
        for (i in o.indices) o[i] *= unit; lo *= unit; hi *= unit
        val h = hi - lo
        val ref = appRef(model)
        val bodyH = ref.top - ref.lo
        val full = h > bodyH * 0.6f
        // middle of the figure, side to side: median x of the top part
        val xs = ArrayList<Float>(); for (i in 0 until n) if (o[i * 3 + 1] > hi - 0.2f * h) xs.add(o[i * 3]); xs.sort()
        var cx = if (xs.isEmpty()) 0f else xs[xs.size / 2]
        val est = if (full) bodyH / h else 1f
        var pr = profile(o, n, hi, h, cx, 0.25f / est, full)
        // a ponytail or loose hair to one side pulls that guess over; the face itself is symmetric, so re-centre on
        // the front-most part of the face (cheeks, nose, mouth) between the chin and the nose
        run {
            var fz = -Float.MAX_VALUE
            for (i in 0 until n) { val y = o[i * 3 + 1]; if (y > pr.chinY && y < pr.noseY && Math.abs(o[i * 3] - cx) < 0.6f / est) fz = maxOf(fz, o[i * 3 + 2]) }
            var mnx = Float.MAX_VALUE; var mxx = -Float.MAX_VALUE
            for (i in 0 until n) { val y = o[i * 3 + 1]
                if (y > pr.chinY && y < pr.noseY && Math.abs(o[i * 3] - cx) < 0.8f / est && o[i * 3 + 2] > fz - 0.35f / est) { mnx = minOf(mnx, o[i * 3]); mxx = maxOf(mxx, o[i * 3]) } }
            if (mnx < mxx) { cx = (mnx + mxx) / 2; pr = profile(o, n, hi, h, cx, 0.25f / est, full) }
        }
        // size: whole figures by feet-to-chin, heads and busts by nose-to-chin
        val scale = if (full) (ref.chin - ref.lo) / (pr.chinY - lo).coerceAtLeast(0.1f)
            else (ref.nose - ref.chin) / (pr.noseY - pr.chinY).coerceAtLeast(0.01f)
        // the cut: as far under the chin as on the PocketHuman body
        val neckY = pr.chinY - (ref.chin - ref.cutY) / scale
        debug?.invoke("app: top ${ref.top} nose ${ref.nose} chin ${ref.chin} cut ${ref.cutY} lo ${ref.lo} | model: hi $hi lo $lo nose ${pr.noseY} chin ${pr.chinY} neck $neckY scale $scale full $full")
        var front = -Float.MAX_VALUE
        for (i in 0 until n) if (Math.abs(o[i * 3] - cx) < 0.25f / scale && Math.abs(o[i * 3 + 1] - neckY) < 0.12f / scale) front = maxOf(front, o[i * 3 + 2])
        if (front == -Float.MAX_VALUE) front = 0f
        val overlap = 0.4f / scale            // keep a little neck below the cut for the seam (in model units)
        val cutAt = neckY - overlap
        val chinY = pr.chinY

        // weld by position to find connected pieces; keep the pieces that reach up into the head
        val key = HashMap<Long, Int>(); val grp = IntArray(n)
        val q = h / 4000f
        for (i in 0 until n) {
            val k = (Math.round(o[i * 3] / q).toLong() * 73856093L) xor (Math.round(o[i * 3 + 1] / q).toLong() * 19349663L) xor (Math.round(o[i * 3 + 2] / q).toLong() * 83492791L)
            grp[i] = key.getOrPut(k) { key.size }
        }
        val parent = IntArray(key.size) { it }
        fun find(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        val keepTri = BooleanArray(raw.tris.size / 3)
        for (tI in 0 until raw.tris.size / 3) {
            val a = raw.tris[tI * 3]; val b = raw.tris[tI * 3 + 1]; val c = raw.tris[tI * 3 + 2]
            val gy = (o[a * 3 + 1] + o[b * 3 + 1] + o[c * 3 + 1]) / 3; val gz = (o[a * 3 + 2] + o[b * 3 + 2] + o[c * 3 + 2]) / 3
            val gx = (o[a * 3] + o[b * 3] + o[c * 3]) / 3
            if (minOf(o[a * 3 + 1], o[b * 3 + 1], o[c * 3 + 1]) < cutAt) continue
            // shoulder tops and straps that reach above the cut, out to the sides
            if (gy < chinY && gy < neckY + 0.35f / scale && Math.abs(gx - cx) > 0.95f / scale) continue
            // below the chin, nothing that hangs in front of the neck (collars, ties, a raised hand)
            if (gy < chinY && gz > front + 0.15f / scale) continue
            keepTri[tI] = true
            val ra = find(grp[a]); val rb = find(grp[b]); val rc = find(grp[c]); parent[rb] = ra; parent[find(rc)] = ra
        }
        val topOf = HashMap<Int, Float>()
        for (tI in keepTri.indices) if (keepTri[tI]) for (k in 0..2) { val vI = raw.tris[tI * 3 + k]; val r = find(grp[vI]); topOf[r] = maxOf(topOf[r] ?: -Float.MAX_VALUE, o[vI * 3 + 1]) }
        val reach = chinY + 0.5f * (pr.noseY - chinY)
        val used = IntArray(n) { -1 }; var nu = 0; val tl = ArrayList<Int>()
        for (tI in keepTri.indices) {
            if (!keepTri[tI]) continue
            val a = raw.tris[tI * 3]; if ((topOf[find(grp[a])] ?: 0f) < reach) continue
            for (k in 0..2) { val vI = raw.tris[tI * 3 + k]; if (used[vI] < 0) used[vI] = nu++; tl.add(used[vI]) }
        }
        val pos = FloatArray(nu * 3); val uv = FloatArray(nu * 2)
        for (i in 0 until n) if (used[i] >= 0) {
            val j = used[i]
            pos[j * 3] = (o[i * 3] - cx) * scale; pos[j * 3 + 1] = (o[i * 3 + 1] - neckY) * scale; pos[j * 3 + 2] = (o[i * 3 + 2] - front) * scale
            uv[j * 2] = raw.uv[i * 2]; uv[j * 2 + 1] = raw.uv[i * 2 + 1]
        }
        require(tl.isNotEmpty()) { "Couldn't find a head in that model" }
        // very detailed heads are thinned out to keep the phone quick (the cut-off head, not the whole model)
        if (tl.size / 3 > MAX_TRIS) {
            val sm = MeshIO.simplify(RawMesh(pos, uv, tl.toIntArray(), null, "png"), MAX_TRIS)
            return Prepared(sm.pos, sm.uv, sm.tris, raw.texture, raw.textureExt, full, (chinY - neckY) * scale)
        }
        return Prepared(pos, uv, tl.toIntArray(), raw.texture, raw.textureExt, full, (chinY - neckY) * scale)
    }

    /**
     * AI meshes often mix clockwise and anticlockwise triangles, which lights patches of the face from the inside.
     * Make every connected piece wind consistently (spreading from triangle to neighbouring triangle), then turn each
     * piece so its triangles face outwards.
     */
    private fun orient(pos: FloatArray, tl: ArrayList<Int>) {
        val nv = pos.size / 3; val nt = tl.size / 3
        val wk = HashMap<Long, Int>(); val wid = IntArray(nv)
        for (i in 0 until nv) { val k = (Math.round(pos[i * 3] * 2e3).toLong() * 73856093L) xor (Math.round(pos[i * 3 + 1] * 2e3).toLong() * 19349663L) xor (Math.round(pos[i * 3 + 2] * 2e3).toLong() * 83492791L); wid[i] = wk.getOrPut(k) { wk.size } }
        fun ekey(a: Int, b: Int) = if (a < b) a.toLong() * 4_000_000L + b else b.toLong() * 4_000_000L + a
        val edges = HashMap<Long, IntArray>()     // up to two triangles per edge
        for (t in 0 until nt) for (q in 0..2) {
            val k = ekey(wid[tl[t * 3 + q]], wid[tl[t * 3 + (q + 1) % 3]])
            val e = edges[k]; if (e == null) edges[k] = intArrayOf(t, -1) else if (e[1] < 0 && e[0] != t) e[1] = t
        }
        fun dirOf(t: Int, a: Int, b: Int): Int { for (q in 0..2) { val x = wid[tl[t * 3 + q]]; val y = wid[tl[t * 3 + (q + 1) % 3]]; if (x == a && y == b) return 1; if (x == b && y == a) return -1 }; return 0 }
        fun flip(t: Int) { val b = tl[t * 3 + 1]; tl[t * 3 + 1] = tl[t * 3 + 2]; tl[t * 3 + 2] = b }
        val comp = IntArray(nt) { -1 }; var nc = 0
        val queue = ArrayDeque<Int>()
        for (s0 in 0 until nt) {
            if (comp[s0] >= 0) continue
            comp[s0] = nc; queue.add(s0)
            while (queue.isNotEmpty()) {
                val t = queue.removeFirst()
                for (q in 0..2) {
                    val a = wid[tl[t * 3 + q]]; val b = wid[tl[t * 3 + (q + 1) % 3]]
                    val e = edges[ekey(a, b)] ?: continue
                    val o = if (e[0] == t) e[1] else e[0]; if (o < 0 || comp[o] >= 0) continue
                    // a consistent neighbour runs the shared edge the other way
                    if (dirOf(o, a, b) == 1) flip(o)
                    comp[o] = nc; queue.add(o)
                }
            }
            nc++
        }
        // each piece faces outwards from the head's middle
        var cx = 0.0; var cy = 0.0; var cz = 0.0; for (i in 0 until nv) { cx += pos[i * 3]; cy += pos[i * 3 + 1]; cz += pos[i * 3 + 2] }
        cx /= nv; cy /= nv; cz /= nv
        val score = DoubleArray(nc)
        for (t in 0 until nt) {
            val a = tl[t * 3]; val b = tl[t * 3 + 1]; val c = tl[t * 3 + 2]
            val ux = pos[b * 3] - pos[a * 3]; val uy = pos[b * 3 + 1] - pos[a * 3 + 1]; val uz = pos[b * 3 + 2] - pos[a * 3 + 2]
            val vx = pos[c * 3] - pos[a * 3]; val vy = pos[c * 3 + 1] - pos[a * 3 + 1]; val vz = pos[c * 3 + 2] - pos[a * 3 + 2]
            score[comp[t]] += (uy * vz - uz * vy) * (pos[a * 3] - cx) + (uz * vx - ux * vz) * (pos[a * 3 + 1] - cy) + (ux * vy - uy * vx) * (pos[a * 3 + 2] - cz)
        }
        for (t in 0 until nt) if (score[comp[t]] < 0) flip(t)
        debug?.invoke("orient: $nc pieces")
    }

    /** The prepared head moved onto the PocketHuman neck with the user's adjustments. */
    fun place(prep: Prepared, ref: AppRef, p: Params): FloatArray {
        val out = FloatArray(prep.pos.size)
        val a = Math.toRadians(p.tilt.toDouble()); val ca = Math.cos(a).toFloat(); val sa = Math.sin(a).toFloat()
        for (i in 0 until prep.n) {
            val x = prep.pos[i * 3] * p.size; val y = prep.pos[i * 3 + 1] * p.size; val z = prep.pos[i * 3 + 2] * p.size
            // tilt: nod forward / back around the neck cut
            val y2 = y * ca - z * sa; val z2 = y * sa + z * ca
            out[i * 3] = x + ref.ax + p.side; out[i * 3 + 1] = y2 + ref.cutY + p.up; out[i * 3 + 2] = z2 + ref.front + p.forward
        }
        return out
    }

    /** PocketHuman head vertices to hide under the new head. */
    fun hiddenVerts(model: HumanModel, ref: AppRef): IntArray {
        val v = model.neutral; val out = ArrayList<Int>()
        for (i in 0 until 13380) {
            val dx = v[i * 3] - ref.ax; val dz = v[i * 3 + 2] - (ref.front - 0.5f)
            if (v[i * 3 + 1] > ref.cutY - 0.12f && dx * dx + dz * dz < 1.6f * 1.6f) out.add(i)
        }
        return out.toIntArray()
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /**
     * Builds and saves the head. progress gets short status lines.
     */
    fun build(model: HumanModel, prep: Prepared, p: Params, id: String, name: String, filesDir: File,
              decoder: ImageDecoder, writer: ImageWriter, progress: (String) -> Unit = {}): HeadData {
        val lib = model.lib; val bm = lib.base; val nV = bm.nV
        val V = model.neutral; val ref = appRef(model)
        val mp = place(prep, ref, p); val nm = prep.n
        val body = BodyQuery(model)          // the caller makes sure the face is neutral
        val bt = body.tris; val vn = body.vn

        // --- the PocketHuman head region and its neighbours
        progress("Fitting the head…")
        val inH = BooleanArray(nV)
        for (i in 0 until 13380) { val dx = V[i * 3] - ref.ax; val dz = V[i * 3 + 2] - (ref.front - 0.5f)
            if (V[i * 3 + 1] > ref.cutY - 0.8f && dx * dx + dz * dz < 2.4f * 2.4f) inH[i] = true }
        val nb = Array(nV) { HashSet<Int>(8) }
        val headTris = ArrayList<Int>()
        for (t in 0 until bt.size / 3) {
            val a = bt[t * 3]; val b = bt[t * 3 + 1]; val c = bt[t * 3 + 2]
            if (!(inH[a] && inH[b] && inH[c])) continue
            headTris.add(a); headTris.add(b); headTris.add(c)
            nb[a].add(b); nb[a].add(c); nb[b].add(a); nb[b].add(c); nb[c].add(a); nb[c].add(b)
        }
        val hv = (0 until nV).filter { inH[it] }.toIntArray()
        debug?.invoke("head region ${hv.size} verts, ${headTris.size / 3} tris, front ${ref.front} ax ${ref.ax}")

        // --- shrink-wrap the PocketHuman head onto the new one
        val mn = weldedNormals(mp, prep.tris, nm).also { nrm ->
            var cx = 0.0; var cy = 0.0; var cz = 0.0; for (i in 0 until nm) { cx += mp[i * 3]; cy += mp[i * 3 + 1]; cz += mp[i * 3 + 2] }
            cx /= nm; cy /= nm; cz /= nm
            var s = 0.0; for (i in 0 until nm) s += nrm[i * 3] * (mp[i * 3] - cx) + nrm[i * 3 + 1] * (mp[i * 3 + 1] - cy) + nrm[i * 3 + 2] * (mp[i * 3 + 2] - cz)
            if (s < 0) for (k in nrm.indices) nrm[k] = -nrm[k]
        }
        val mq = MeshQuery(mp, prep.tris, null, 0.25f)
        val disp = FloatArray(nV * 3)
        // line up the features first (chin and eyes), so the shrink-wrap links eyes to eyes and mouth to mouth
        val eyeProxy = lib.proxy("eyes_high-poly"); val eyesAt = eyeProxy.fit(V)
        val eyeSpheres = spheres(eyesAt, eyeProxy.n, ref.ax)
        val img = prep.texture?.let { runCatching { decoder.decode(it) }.getOrNull() }
        run {
            val chinM = ref.cutY + p.up + prep.chin * p.size
            // trust the painted-eye guess only when both eyes agree (level and either side of the middle)
            val found = (if (img != null) findEyes(mp, mn, prep.uv, nm, img, ref, chinM) else null)?.takeIf { f ->
                Math.abs(f[1] - f[4]) < 0.15f && Math.abs(f[2] - f[5]) < 0.3f }
            // spacing only when the two marks sit either side of the middle
            val spacingOk = found != null && Math.abs((found[0] + found[3]) / 2 - ref.ax) < 0.12f && found[0] - found[3] > 0.35f
            val eA = eyeSpheres.map { it[1] }.average().toFloat(); val cA = ref.chin
            val eM = (found?.let { (it[1] + it[4]) / 2 } ?: eA) + p.eyeUp
            val k = ((eM - chinM) / (eA - cA)).coerceIn(0.6f, 1.8f)
            val sepA = if (eyeSpheres.size == 2) Math.abs(eyeSpheres[0][0] - eyeSpheres[1][0]) else 1f
            val sx = ((if (spacingOk && found != null && eyeSpheres.size == 2) (Math.abs(found[0] - found[3]) / sepA).coerceIn(0.85f, 1.25f) else 1f) + p.eyeApart / sepA).coerceIn(0.5f, 2f)
            val dz = (if (found != null && eyeSpheres.size == 2) ((found[2] + found[5]) / 2 - (eyeSpheres.map { it[2] + it[3] }.average().toFloat())) else 0f) + p.eyeDepth
            debug?.invoke("eyes found ${found?.joinToString { "%.2f".format(it) }} app eye $eA chin $cA -> model eye $eM chin $chinM k $k sx $sx dz $dz")
            for (i in hv) {
                val y = V[i * 3 + 1]; if (y <= ref.cutY) continue
                val ny = if (y < cA) ref.cutY + (y - ref.cutY) * (chinM - ref.cutY) / (cA - ref.cutY) else chinM + (y - cA) * k
                val f = smooth(ref.cutY, cA, y)
                disp[i * 3 + 1] = ny - y
                disp[i * 3] = (V[i * 3] - ref.ax) * (sx - 1) * f
                disp[i * 3 + 2] = dz * f
            }
        }
        val pinned = BooleanArray(nV) { inH[it] && V[it * 3 + 1] < ref.cutY - 0.2f }
        val tmp = FloatArray(nV * 3)
        for (it in 0 until 16) {
            for (i in hv) {
                if (pinned[i]) continue
                val px = V[i * 3] + disp[i * 3]; val py = V[i * 3 + 1] + disp[i * 3 + 1]; val pz = V[i * 3 + 2] + disp[i * 3 + 2]
                val h = mq.nearest(px, py, pz, 1.2f) ?: continue
                val ta = prep.tris[h.tri * 3]; val tb = prep.tris[h.tri * 3 + 1]; val tc = prep.tris[h.tri * 3 + 2]
                val nx = h.w0 * mn[ta * 3] + h.w1 * mn[tb * 3] + h.w2 * mn[tc * 3]; val ny = h.w0 * mn[ta * 3 + 1] + h.w1 * mn[tb * 3 + 1] + h.w2 * mn[tc * 3 + 1]; val nz = h.w0 * mn[ta * 3 + 2] + h.w1 * mn[tb * 3 + 2] + h.w2 * mn[tc * 3 + 2]
                if (nx * vn[i * 3] + ny * vn[i * 3 + 1] + nz * vn[i * 3 + 2] < 0.1f) continue     // facing the other way: let smoothing carry it
                val k = 0.5f * smooth(ref.cutY - 0.2f, ref.cutY + 0.3f, V[i * 3 + 1])
                disp[i * 3] += k * (h.x - V[i * 3] - disp[i * 3]); disp[i * 3 + 1] += k * (h.y - V[i * 3 + 1] - disp[i * 3 + 1]); disp[i * 3 + 2] += k * (h.z - V[i * 3 + 2] - disp[i * 3 + 2])
            }
            repeat(2) {
                for (i in hv) {
                    if (pinned[i] || nb[i].isEmpty()) { for (k in 0..2) tmp[i * 3 + k] = disp[i * 3 + k]; continue }
                    for (k in 0..2) { var s = 0f; for (j in nb[i]) s += disp[j * 3 + k]; tmp[i * 3 + k] = 0.5f * disp[i * 3 + k] + 0.5f * s / nb[i].size }
                }
                for (i in hv) for (k in 0..2) disp[i * 3 + k] = tmp[i * 3 + k]
            }
        }
        // the eyeballs (helper geometry, not part of the skin) move with the eyelids around them
        val helperMoved = ArrayList<Int>(); val eyeShift = ArrayList<FloatArray>()
        for (e in eyeSpheres) {
            var ax = 0f; var ay = 0f; var az = 0f; var c = 0
            for (i in hv) { val dx = V[i * 3] - e[0]; val dy = V[i * 3 + 1] - e[1]; val dz = V[i * 3 + 2] - e[2]
                if (dx * dx + dy * dy + dz * dz < (1.7f * e[3]) * (1.7f * e[3])) { ax += disp[i * 3]; ay += disp[i * 3 + 1]; az += disp[i * 3 + 2]; c++ } }
            if (c == 0) { eyeShift.add(FloatArray(3)); continue }; ax /= c; ay /= c; az /= c; eyeShift.add(floatArrayOf(ax, ay, az))
            for (j in 13380 until nV) { val dx = V[j * 3] - e[0]; val dy = V[j * 3 + 1] - e[1]; val dz = V[j * 3 + 2] - e[2]
                if (dx * dx + dy * dy + dz * dz < (1.4f * e[3]) * (1.4f * e[3])) { disp[j * 3] = ax; disp[j * 3 + 1] = ay; disp[j * 3 + 2] = az; helperMoved.add(j) } }
        }
        val W = V.copyOf(); for (i in hv) for (k in 0..2) W[i * 3 + k] += disp[i * 3 + k]
        for (j in helperMoved) for (k in 0..2) W[j * 3 + k] += disp[j * 3 + k]

        // --- link every new-head vertex to the wrapped PocketHuman surface
        progress("Linking it to the face…")
        val ht = headTris.toIntArray()
        val wq = MeshQuery(W, ht, null, 0.25f)
        val refs = IntArray(nm * 3); val rw = FloatArray(nm * 3); val off = FloatArray(nm * 3)
        val seamT = FloatArray(nm); val gap = FloatArray(nm)
        // copies of a vertex (split at UV seams) must move as one, or the surface cracks open when the face moves
        val weldFirst = HashMap<Long, Int>()
        for (i in 0 until nm) {
            val x = mp[i * 3]; val y = mp[i * 3 + 1]; val z = mp[i * 3 + 2]
            val wk = (Math.round(x * 2e3).toLong() * 73856093L) xor (Math.round(y * 2e3).toLong() * 19349663L) xor (Math.round(z * 2e3).toLong() * 83492791L)
            val first = weldFirst.getOrPut(wk) { i }
            if (first != i) { for (q in 0..2) { refs[i * 3 + q] = refs[first * 3 + q]; rw[i * 3 + q] = rw[first * 3 + q]; off[i * 3 + q] = off[first * 3 + q] }; seamT[i] = seamT[first]; gap[i] = gap[first]; continue }
            val h = wq.nearest(x, y, z, 6f) ?: wq.nearest(x, y, z, 30f)!!
            gap[i] = h.dist
            val ws = floatArrayOf(h.w0, h.w1, h.w2)
            var ax = 0f; var ay = 0f; var az = 0f; var nx = 0f; var ny = 0f; var nz = 0f
            for (q in 0..2) { val r = ht[h.tri * 3 + q]; refs[i * 3 + q] = r; rw[i * 3 + q] = ws[q]
                ax += ws[q] * V[r * 3]; ay += ws[q] * V[r * 3 + 1]; az += ws[q] * V[r * 3 + 2]
                nx += ws[q] * vn[r * 3]; ny += ws[q] * vn[r * 3 + 1]; nz += ws[q] * vn[r * 3 + 2] }
            // the bottom of the neck melts into the body: sits exactly on it (a hair outside, so it covers it)
            // ...but only skin that sits on the neck: hair hanging past it keeps its shape
            val t = maxOf(smooth(ref.cutY - 0.4f, ref.cutY + 0.15f, y), smooth(0.12f, 0.3f, h.dist)); seamT[i] = t
            val push = 0.035f
            off[i * 3] = (x - ax) * t + nx * push * (1 - t); off[i * 3 + 1] = (y - ay) * t + ny * push * (1 - t); off[i * 3 + 2] = (z - az) * t + nz * push * (1 - t)
        }

        // --- make every triangle face outwards. AI meshes mix both windings, which shows up as missing patches
        // (back faces get culled) and dark ones (lit from inside). The wrapped PocketHuman head under each point
        // says which way is out.
        var tris = prep.tris.copyOf()
        var flipped = 0
        for (t in 0 until tris.size / 3) {
            val a = tris[t * 3]; val b = tris[t * 3 + 1]; val c = tris[t * 3 + 2]
            val ux = mp[b * 3] - mp[a * 3]; val uy = mp[b * 3 + 1] - mp[a * 3 + 1]; val uz = mp[b * 3 + 2] - mp[a * 3 + 2]
            val vx = mp[c * 3] - mp[a * 3]; val vy = mp[c * 3 + 1] - mp[a * 3 + 1]; val vz = mp[c * 3 + 2] - mp[a * 3 + 2]
            val fx = uy * vz - uz * vy; val fy = uz * vx - ux * vz; val fz = ux * vy - uy * vx
            var rx = 0f; var ry = 0f; var rz = 0f
            for (v in intArrayOf(a, b, c)) for (q in 0..2) { val r = refs[v * 3 + q]; val w = rw[v * 3 + q]; rx += w * vn[r * 3]; ry += w * vn[r * 3 + 1]; rz += w * vn[r * 3 + 2] }
            // only skin lying on the face is checked: hair and the insides of things legitimately face other ways
            if (gap[a] < 0.08f && gap[b] < 0.08f && gap[c] < 0.08f && fx * rx + fy * ry + fz * rz < 0) { tris[t * 3 + 1] = c; tris[t * 3 + 2] = b; flipped++ }
        }
        debug?.invoke("flipped $flipped of ${tris.size / 3} triangles to face outwards")

        // --- the app's eyes: take out the painted eyes, keep the eyelids
        if (p.appEyes) {
            val eyes = eyeProxy; val fitted = eyes.fit(W)
            val left = ArrayList<Int>(); val right = ArrayList<Int>()
            for (i in 0 until eyes.n) (if (fitted[i * 3] > ref.ax) left else right).add(i)
            val keep = ArrayList<Int>()
            val spheres = listOf(left, right).filter { it.isNotEmpty() }.map { side ->
                var cx = 0f; var cy = 0f; var cz = 0f; for (i in side) { cx += fitted[i * 3]; cy += fitted[i * 3 + 1]; cz += fitted[i * 3 + 2] }
                cx /= side.size; cy /= side.size; cz /= side.size
                var r = 0f; for (i in side) { val dx = fitted[i * 3] - cx; val dy = fitted[i * 3 + 1] - cy; val dz = fitted[i * 3 + 2] - cz; r = maxOf(r, Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()) }
                floatArrayOf(cx, cy, cz, r)
            }
            // the painted eyes come out, and the app's own eyelids go in their place (wrapped onto the new face), so
            // blinks and eye movements work like on any PocketHuman character
            for (t in 0 until tris.size / 3) {
                var gx = 0f; var gy = 0f; var gz = 0f
                for (q in 0..2) { val v = tris[t * 3 + q]; gx += mp[v * 3] / 3; gy += mp[v * 3 + 1] / 3; gz += mp[v * 3 + 2] / 3 }
                val inEye = spheres.any { s -> val dx = gx - s[0]; val dy = gy - s[1]; val dz = gz - s[2]
                    dz > -0.3f * s[3] && (dx * dx) / (1.15f * 1.15f * s[3] * s[3]) + (dy * dy) / (0.8f * 0.8f * s[3] * s[3]) < 1f }
                if (!inEye) for (q in 0..2) keep.add(tris[t * 3 + q])
            }
            tris = keep.toIntArray()
        }

        // --- the app's eyelids
        val lidList = ArrayList<Int>()
        // eyelids keep their own shape and move with their eyeball (wrapping them would squash them)
        if (p.appEyes) for ((ei, e) in eyeSpheres.withIndex()) for (i in hv) {
            val dx = V[i * 3] - e[0]; val dy = V[i * 3 + 1] - e[1]; val dz = V[i * 3 + 2] - e[2]
            if (dz > -0.4f * e[3] && (dx * dx) / (1.3f * 1.3f * e[3] * e[3]) + (dy * dy) / (1.0f * 1.0f * e[3] * e[3]) < 1f) {
                lidList.add(i); val sh = eyeShift.getOrNull(ei) ?: FloatArray(3); for (k in 0..2) disp[i * 3 + k] = sh[k] }
        }
        val lidSet = lidList.toHashSet()
        // --- texture: skin tone matched to the body, neck seam faded into the body's colour
        progress("Matching the skin…")
        val dir = File(filesDir, "imported/my_heads/$id").apply { deleteRecursively(); mkdirs() }
        var lidTex: String? = null
        val texName = run {
            if (img == null) { File(dir, "texture.png").writeBytes(OutfitDraft.GREY_PNG); "texture.png" }
            else {
                val (tw, th, px) = img
                val skinTex = model.bodyTexture(lib.skins[model.state.skin.coerceIn(0, lib.skins.size - 1)])
                val sk = runCatching { decoder.decode(lib.am.bytes(skinTex)) }.getOrNull()
                // a body UV for each base vertex
                val buv = FloatArray(nV * 2) { -1f }
                for (r in bm.renderMap.indices) { val b = bm.renderMap[r]; if (buv[b * 2] < 0) { buv[b * 2] = bm.uv[r * 2]; buv[b * 2 + 1] = bm.uv[r * 2 + 1] } }
                fun sample(img: Triple<Int, Int, IntArray>, u: Float, v: Float): Int { val (w, h, a) = img
                    return a[(v.coerceIn(0f, 0.9999f) * h).toInt() * w + (u.coerceIn(0f, 0.9999f) * w).toInt()] }
                // body colour at each head vertex (through its link); mean skin tones of both just above the cut
                val bodyCol = IntArray(nm)
                val mHead = DoubleArray(3); val mBody = DoubleArray(3); var cnt = 0
                for (i in 0 until nm) {
                    var u = 0f; var v = 0f; for (q in 0..2) { val r = refs[i * 3 + q]; u += rw[i * 3 + q] * buv[r * 2]; v += rw[i * 3 + q] * buv[r * 2 + 1] }
                    bodyCol[i] = if (sk != null) sample(sk, u, v) else 0xffc09070.toInt()
                    val y = mp[i * 3 + 1]
                    if (y > ref.cutY && y < ref.cutY + 0.5f) {
                        val hc = sample(img, prep.uv[i * 2], prep.uv[i * 2 + 1])
                        if (OutfitDraft.isSkinColour(hc, 0.6f)) { for (k in 0..2) { mHead[k] += ((hc shr (16 - 8 * k)) and 255).toDouble(); mBody[k] += ((bodyCol[i] shr (16 - 8 * k)) and 255).toDouble() }; cnt++ }
                    }
                }
                debug?.invoke("skin match: tex $skinTex head ${mHead.map { it / maxOf(cnt, 1) }} body ${mBody.map { it / maxOf(cnt, 1) }} n $cnt")
                val gain = FloatArray(3) { k -> if (cnt < 10) 1f else (1f + ((mBody[k] / mHead[k].coerceAtLeast(1.0)).toFloat() - 1f) * p.skinMatch).coerceIn(0.5f, 2f) }
                // tone shift on skin-coloured pixels only (eyes, lips, hair keep theirs mostly)
                if (cnt >= 10 && p.skinMatch > 0f) for (i in px.indices) {
                    val c = px[i]; if (!OutfitDraft.isSkinColour(c, 0.8f)) continue
                    var o2 = c and 0xff000000.toInt()
                    for (k in 0..2) o2 = o2 or ((((c shr (16 - 8 * k)) and 255) * gain[k]).toInt().coerceIn(0, 255) shl (16 - 8 * k))
                    px[i] = o2
                }
                // fade to the body's own colour down the seam: paint each seam triangle in UV space
                for (t in 0 until tris.size / 3) {
                    val a = tris[t * 3]; val b = tris[t * 3 + 1]; val c = tris[t * 3 + 2]
                    if (minOf(seamT[a], seamT[b], seamT[c]) >= 0.999f) continue
                    paintTri(px, tw, th, prep.uv, a, b, c) { wa, wb, wc, old ->
                        val tt = wa * seamT[a] + wb * seamT[b] + wc * seamT[c]
                        var o2 = old and 0xff000000.toInt()
                        for (k in 0..2) { val sh = 16 - 8 * k
                            val bc = wa * ((bodyCol[a] shr sh) and 255) + wb * ((bodyCol[b] shr sh) and 255) + wc * ((bodyCol[c] shr sh) and 255)
                            o2 = o2 or (((old shr sh and 255) * tt + bc * (1 - tt)).toInt().coerceIn(0, 255) shl sh) }
                        o2
                    }
                }
                // eyelids: a copy of the body skin tinted to the new face's colour around the eyes
                if (lidList.isNotEmpty() && sk != null) {
                    val fm = DoubleArray(3); var fc = 0; val bmn = DoubleArray(3); var bc = 0
                    val ring = ArrayList<Int>()
                    for (i in 0 until nm) for ((ei, e) in eyeSpheres.withIndex()) {
                        val sh = eyeShift.getOrNull(ei) ?: FloatArray(3)
                        val dx = mp[i * 3] - e[0] - sh[0]; val dy = mp[i * 3 + 1] - e[1] - sh[1]
                        val d2 = dx * dx + dy * dy
                        // the skin under the eye (no fringe there), as painted
                        if (dy < -0.4f * e[3] && d2 > (1.35f * e[3]) * (1.35f * e[3]) && d2 < (2.4f * e[3]) * (2.4f * e[3]) && mn[i * 3 + 2] > 0.3f)
                            ring.add(sample(Triple(tw, th, px), prep.uv[i * 2], prep.uv[i * 2 + 1]))
                    }
                    // typical skin there, shading and make-up included: warm-coloured samples (no grey hair strands or black
                    // lashes), the middle half of them by brightness
                    val warm = ring.filter { c -> val r = (c shr 16) and 255; val b = c and 255; r > b + 8 && r + ((c shr 8) and 255) + b > 90 }
                        .sortedBy { ((it shr 16) and 255) + ((it shr 8) and 255) + (it and 255) }
                    for (c in warm.subList(warm.size / 4, warm.size * 3 / 4)) { for (k in 0..2) fm[k] += ((c shr (16 - 8 * k)) and 255).toDouble(); fc++ }
                    // the body skin over the whole eyelid area (not just at its vertices: the texture between them is often lighter)
                    run {
                        val (sw, shh, sp) = sk; val copy = sp.copyOf(); val lidSet2 = lidList.toHashSet()
                        var t = 0
                        while (t < bm.tris.size) {
                            val a = bm.tris[t]; val b = bm.tris[t + 1]; val c = bm.tris[t + 2]; t += 3
                            if (bm.renderMap[a] !in lidSet2 || bm.renderMap[b] !in lidSet2 || bm.renderMap[c] !in lidSet2) continue
                            paintTri(copy, sw, shh, bm.uv, a, b, c) { _, _, _, old -> for (k in 0..2) bmn[k] += ((old shr (16 - 8 * k)) and 255).toDouble(); bc++; old }
                        }
                    }
                    if (fc > 5 && bc > 5) {
                        val g = FloatArray(3) { k -> ((fm[k] / fc) / (bmn[k] / bc).coerceAtLeast(1.0) * p.lidShade).toFloat().coerceIn(0.3f, 2.5f) }
                        debug?.invoke("lid tint: face ${fm.map { it / fc }} body ${bmn.map { it / bc }} n $fc $bc")
                        val (sw, shh, sp) = sk; val tinted = IntArray(sp.size) { i -> val c = sp[i]; var o2 = c and 0xff000000.toInt()
                            for (k in 0..2) o2 = o2 or ((((c shr (16 - 8 * k)) and 255) * g[k]).toInt().coerceIn(0, 255) shl (16 - 8 * k)); o2 }
                        lidTex = "imported/my_heads/$id/" + writer.write(tinted, sw, shh, dir, "lids")
                    }
                }
                writer.write(px, tw, th, dir, "texture")
            }
        }

        // --- save
        val hidden = hiddenVerts(model, ref).filter { it !in lidSet }.toIntArray()
        // push the kept eyelids a hair outwards so they sit on top of the new face where they overlap
        for (i in lidList) for (k in 0..2) disp[i * 3 + k] += vn[i * 3 + k] * 0.012f
        val dIdx = (hv.toList() + helperMoved).distinct().filter { i -> Math.abs(disp[i * 3]) + Math.abs(disp[i * 3 + 1]) + Math.abs(disp[i * 3 + 2]) > 1e-5f }.toIntArray()
        val dVal = FloatArray(dIdx.size * 3) { disp[dIdx[it / 3] * 3 + it % 3] }
        debug?.invoke("lids ${lidList.size}, moved ${dIdx.size}, |disp| max ${hv.maxOfOrNull { i -> Math.abs(disp[i * 3]) + Math.abs(disp[i * 3 + 1]) + Math.abs(disp[i * 3 + 2]) }}, eyes ${eyeSpheres.size}, appEyes ${p.appEyes}")
        // drop vertices no triangle uses any more
        val used = IntArray(nm) { -1 }; var nu = 0; for (x in tris) if (used[x] < 0) used[x] = nu++
        val idx = IntArray(nu); for (i in 0 until nm) if (used[i] >= 0) idx[used[i]] = i
        val data = HeadData(id, name,
            IntArray(nu * 3) { refs[idx[it / 3] * 3 + it % 3] }, FloatArray(nu * 3) { rw[idx[it / 3] * 3 + it % 3] }, FloatArray(nu * 3) { off[idx[it / 3] * 3 + it % 3] },
            FloatArray(nu * 2) { prep.uv[idx[it / 2] * 2 + it % 2] }, IntArray(tris.size) { used[tris[it]] },
            "imported/my_heads/$id/$texName", hidden, dIdx, dVal, p.appEyes, lidList.toIntArray(), lidTex)
        HeadStore.save(filesDir, data, p)
        return data
    }

    /** Eye centres (x, y, z each) of the two eyeballs of a fitted eye proxy, with radius: [x, y, z, r] per eye. */
    private fun spheres(at: FloatArray, n: Int, ax: Float): List<FloatArray> {
        val l = ArrayList<Int>(); val r = ArrayList<Int>()
        for (i in 0 until n) (if (at[i * 3] > ax) l else r).add(i)
        return listOf(l, r).filter { it.isNotEmpty() }.map { side ->
            var cx = 0f; var cy = 0f; var cz = 0f; for (i in side) { cx += at[i * 3]; cy += at[i * 3 + 1]; cz += at[i * 3 + 2] }
            cx /= side.size; cy /= side.size; cz /= side.size
            var rr = 0f; for (i in side) { val dx = at[i * 3] - cx; val dy = at[i * 3 + 1] - cy; val dz = at[i * 3 + 2] - cz; rr = maxOf(rr, Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()) }
            floatArrayOf(cx, cy, cz, rr) }
    }

    /**
     * The painted eyes on the new head: the whites of the eyes are near-white and unsaturated, on the front of the face
     * above the chin. Returns [lx, ly, lz, rx, ry, rz] or null.
     */
    private fun findEyes(mp: FloatArray, mn: FloatArray, uv: FloatArray, n: Int, img: Triple<Int, Int, IntArray>, ref: AppRef, chinY: Float): FloatArray? {
        val (w, h, px) = img
        // painted eyes (iris, lashes) are the dark, non-skin marks on the front of the face between the chin and the brows;
        // the brows and a fringe sit higher, so take the lower part of each side's marks
        val ys = Array(2) { ArrayList<Float>() }; val xs = Array(2) { ArrayList<Float>() }; val zs = Array(2) { ArrayList<Float>() }
        var faceFront = -Float.MAX_VALUE
        for (i in 0 until n) { val y = mp[i * 3 + 1]; if (y > chinY + 0.3f && y < chinY + 1.5f && Math.abs(mp[i * 3] - ref.ax) < 0.3f) faceFront = maxOf(faceFront, mp[i * 3 + 2]) }
        for (i in 0 until n) {
            val y = mp[i * 3 + 1]; val x = mp[i * 3] - ref.ax
            if (mp[i * 3 + 2] < faceFront - 0.5f) continue
            if (y < chinY + 0.45f || y > chinY + 1.2f || Math.abs(x) > 0.65f || Math.abs(x) < 0.12f || mn[i * 3 + 2] < 0.45f) continue
            val c = px[(uv[i * 2 + 1].coerceIn(0f, 0.9999f) * h).toInt() * w + (uv[i * 2].coerceIn(0f, 0.9999f) * w).toInt()]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            if (OutfitDraft.isSkinColour(c, 0.7f) || r + g + b > 330) continue
            val sI = if (x > 0) 0 else 1; ys[sI].add(y); xs[sI].add(mp[i * 3]); zs[sI].add(mp[i * 3 + 2])
        }
        if (ys.any { it.size < 6 }) return null
        return FloatArray(6) { k -> val sI = k / 3
            when (k % 3) { 0 -> xs[sI].sorted()[xs[sI].size / 2]; 1 -> ys[sI].sorted()[(ys[sI].size * 0.15f).toInt()]; else -> zs[sI].sorted()[(zs[sI].size * 0.8f).toInt()] } }
    }

    /** Rasterises a UV triangle, calling f(barycentric weights, old colour) -> new colour for each texel. */
    private inline fun paintTri(px: IntArray, w: Int, h: Int, uv: FloatArray, a: Int, b: Int, c: Int, f: (Float, Float, Float, Int) -> Int) {
        val ax = uv[a * 2] * w; val ay = uv[a * 2 + 1] * h; val bx = uv[b * 2] * w; val by = uv[b * 2 + 1] * h; val cx = uv[c * 2] * w; val cy = uv[c * 2 + 1] * h
        val den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy); if (Math.abs(den) < 1e-9f) return
        val x0 = maxOf(0, Math.floor(minOf(ax, bx, cx).toDouble()).toInt() - 1); val x1 = minOf(w - 1, Math.ceil(maxOf(ax, bx, cx).toDouble()).toInt() + 1)
        val y0 = maxOf(0, Math.floor(minOf(ay, by, cy).toDouble()).toInt() - 1); val y1 = minOf(h - 1, Math.ceil(maxOf(ay, by, cy).toDouble()).toInt() + 1)
        val pad = 1.5f / maxOf(1f, Math.abs(den) / 2f).let { Math.sqrt(it.toDouble()).toFloat() }   // a little bleed past the edge
        for (y in y0..y1) for (x in x0..x1) {
            val px0 = x + 0.5f; val py0 = y + 0.5f
            val wa = ((by - cy) * (px0 - cx) + (cx - bx) * (py0 - cy)) / den; val wb = ((cy - ay) * (px0 - cx) + (ax - cx) * (py0 - cy)) / den; val wc = 1 - wa - wb
            if (wa < -pad || wb < -pad || wc < -pad) continue
            val s = (wa.coerceAtLeast(0f) + wb.coerceAtLeast(0f) + wc.coerceAtLeast(0f)).coerceAtLeast(1e-6f)
            px[y * w + x] = f(wa.coerceAtLeast(0f) / s, wb.coerceAtLeast(0f) / s, wc.coerceAtLeast(0f) / s, px[y * w + x])
        }
    }

    /** The head as a drawable part on the current shape v (base-vertex positions, with or without an expression). */
    fun part(model: HumanModel, d: HeadData, v: FloatArray, forExport: Boolean): Part {
        val n = d.n; val pos = FloatArray(n * 3)
        for (i in 0 until n) for (k in 0..2) { var s = d.off[i * 3 + k]; for (q in 0..2) s += d.w[i * 3 + q] * v[d.refs[i * 3 + q] * 3 + k]; pos[i * 3 + k] = s }
        var j = IntArray(0); var wt = FloatArray(0)
        if (forExport) { val r = PartBuilder.blendWeights(model.lib.rig, d.refs, d.w, n); j = r.first; wt = r.second }
        // AI-made surfaces are bumpy at the triangle level, which shades them darker and blotchy: smooth the normals
        var nrm = weldedNormals(pos, d.tris, n); val nb = d.nbrs; val tmp = FloatArray(n * 3)
        repeat(3) {
            for (i in 0 until n) { var x = nrm[i * 3]; var y = nrm[i * 3 + 1]; var z = nrm[i * 3 + 2]
                for (j in nb[i]) { x += nrm[j * 3]; y += nrm[j * 3 + 1]; z += nrm[j * 3 + 2] }
                val l = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat().coerceAtLeast(1e-9f); tmp[i * 3] = x / l; tmp[i * 3 + 1] = y / l; tmp[i * 3 + 2] = z / l }
            nrm = tmp.copyOf()
        }
        return Part("head", pos, nrm, d.uv, d.tris, d.texture, false, null, j, wt, d.refs, d.w, 3)
    }
}

/** Saved heads: imported/my_heads/<id>/ head.bin + head.json + texture. */
object HeadStore {
    fun dir(filesDir: File, id: String) = File(filesDir, "imported/my_heads/$id")

    fun save(filesDir: File, d: HeadData, p: HeadSwap.Params) {
        val dir = dir(filesDir, d.id).apply { mkdirs() }; val n = d.n
        val bb = ByteBuffer.allocate(4 + 4 + n * 36 + n * 8 + 4 + d.tris.size * 4 + 4 + d.hidden.size * 4 + 4 + d.dispIdx.size * 16 + 4 + d.lids.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("PHH1".toByteArray()).putInt(n)
        d.refs.forEach { bb.putInt(it) }; d.w.forEach { bb.putFloat(it) }; d.off.forEach { bb.putFloat(it) }; d.uv.forEach { bb.putFloat(it) }
        bb.putInt(d.tris.size); d.tris.forEach { bb.putInt(it) }
        bb.putInt(d.hidden.size); d.hidden.forEach { bb.putInt(it) }
        bb.putInt(d.dispIdx.size); d.dispIdx.forEach { bb.putInt(it) }; d.disp.forEach { bb.putFloat(it) }
        bb.putInt(d.lids.size); d.lids.forEach { bb.putInt(it) }
        File(dir, "head.bin").writeBytes(bb.array())
        File(dir, "head.json").writeText(JSONObject().put("name", d.name).put("texture", d.texture).put("lidTexture", d.lidTexture ?: JSONObject.NULL).put("appEyes", d.appEyes).put("params", p.toJson()).toString())
    }

    fun load(filesDir: File, id: String): HeadData? = runCatching {
        val dir = dir(filesDir, id); val meta = JSONObject(File(dir, "head.json").readText())
        val bb = ByteBuffer.wrap(File(dir, "head.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN); bb.position(4)
        val n = bb.int
        val refs = IntArray(n * 3) { bb.int }; val w = FloatArray(n * 3) { bb.float }; val off = FloatArray(n * 3) { bb.float }; val uv = FloatArray(n * 2) { bb.float }
        val tris = IntArray(bb.int) { bb.int }; val hidden = IntArray(bb.int) { bb.int }
        val nd = bb.int; val di = IntArray(nd) { bb.int }; val dv = FloatArray(nd * 3) { bb.float }
        val lids = if (bb.remaining() >= 4) IntArray(bb.int) { bb.int } else IntArray(0)
        HeadData(id, meta.optString("name", id), refs, w, off, uv, tris, meta.getString("texture"), hidden, di, dv, meta.optBoolean("appEyes", false), lids, if (meta.isNull("lidTexture")) null else meta.optString("lidTexture").ifEmpty { null })
    }.getOrNull()

    fun list(filesDir: File): List<Pair<String, String>> =
        File(filesDir, "imported/my_heads").listFiles()?.filter { File(it, "head.json").exists() }?.sortedBy { it.lastModified() }
            ?.map { it.name to JSONObject(File(it, "head.json").readText()).optString("name", it.name) } ?: emptyList()

    fun delete(filesDir: File, id: String) = dir(filesDir, id).deleteRecursively()
}
