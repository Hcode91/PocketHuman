package com.hcode91.pockethuman

/**
 * Stencil painting onto the body skin, like Blender's texture paint with a stencil: a picture is laid over the 3D view,
 * and brushing copies the picture, as seen through the screen, onto the skin under the brush.
 *
 * Works on a copy of the skin texture (px); [base] is the untouched skin, for the eraser. Positions are the body as
 * drawn (render vertices), so what you see is where the paint lands.
 */
class SkinPainter(val w: Int, val h: Int, val base: IntArray, val pos: FloatArray, val uv: FloatArray, val tris: IntArray) {
    val px: IntArray = base.copyOf()

    /** The picture: its pixels and the screen -> picture mapping (affine: x' = a x + b y + c, y' = d x + e y + f). */
    class Stencil(val pw: Int, val ph: Int, val pixels: IntArray, val inv: FloatArray)

    // triangles by the grid cell of their centre, to find the ones under a brush quickly
    private val cell = 0.4f
    private val grid = HashMap<Long, ArrayList<Int>>()
    private fun key(x: Float, y: Float, z: Float): Long {
        val i = Math.floor((x / cell).toDouble()).toLong(); val j = Math.floor((y / cell).toDouble()).toLong(); val k = Math.floor((z / cell).toDouble()).toLong()
        return (i * 73856093L) xor (j * 19349663L) xor (k * 83492791L)
    }
    init {
        for (t in 0 until tris.size / 3) {
            val a = tris[t * 3] * 3; val b = tris[t * 3 + 1] * 3; val c = tris[t * 3 + 2] * 3
            grid.getOrPut(key((pos[a] + pos[b] + pos[c]) / 3, (pos[a + 1] + pos[b + 1] + pos[c + 1]) / 3, (pos[a + 2] + pos[b + 2] + pos[c + 2]) / 3)) { ArrayList() }.add(t)
        }
    }

    /** Ray (origin o, direction d) onto the body: nearest hit point or null. */
    fun pick(o: FloatArray, d: FloatArray): FloatArray? {
        var best = Float.MAX_VALUE; var i = 0
        while (i < tris.size) {
            val a = tris[i] * 3; val b = tris[i + 1] * 3; val c = tris[i + 2] * 3; i += 3
            val e1x = pos[b] - pos[a]; val e1y = pos[b + 1] - pos[a + 1]; val e1z = pos[b + 2] - pos[a + 2]
            val e2x = pos[c] - pos[a]; val e2y = pos[c + 1] - pos[a + 1]; val e2z = pos[c + 2] - pos[a + 2]
            val qx = d[1] * e2z - d[2] * e2y; val qy = d[2] * e2x - d[0] * e2z; val qz = d[0] * e2y - d[1] * e2x
            val det = e1x * qx + e1y * qy + e1z * qz; if (Math.abs(det) < 1e-12f) continue
            val inv = 1f / det; val tx = o[0] - pos[a]; val ty = o[1] - pos[a + 1]; val tz = o[2] - pos[a + 2]
            val u = (tx * qx + ty * qy + tz * qz) * inv; if (u < 0 || u > 1) continue
            val rx = ty * e1z - tz * e1y; val ry = tz * e1x - tx * e1z; val rz = tx * e1y - ty * e1x
            val v = (d[0] * rx + d[1] * ry + d[2] * rz) * inv; if (v < 0 || u + v > 1) continue
            val t = (e2x * rx + e2y * ry + e2z * rz) * inv; if (t > 0 && t < best) best = t
        }
        return if (best == Float.MAX_VALUE) null else floatArrayOf(o[0] + d[0] * best, o[1] + d[1] * best, o[2] + d[2] * best)
    }

    // ------------------------------------------------------------------ undo (64x64 tiles saved the first time a stroke touches them)
    private val tile = 64
    private var current: HashMap<Int, IntArray>? = null
    private val undoStack = ArrayList<HashMap<Int, IntArray>>()
    fun strokeBegin() { current = HashMap() }
    fun strokeEnd() { current?.let { if (it.isNotEmpty()) { undoStack.add(it); if (undoStack.size > 25) undoStack.removeAt(0) } }; current = null }
    private fun backup(x0: Int, y0: Int, x1: Int, y1: Int) {
        val cur = current ?: return
        val tw = (w + tile - 1) / tile
        for (ty in y0 / tile..y1 / tile) for (tx in x0 / tile..x1 / tile) {
            val id = ty * tw + tx; if (cur.containsKey(id)) continue
            val sx = tx * tile; val sy = ty * tile; val ex = minOf(w, sx + tile); val ey = minOf(h, sy + tile)
            val save = IntArray((ex - sx) * (ey - sy)); var k = 0
            for (y in sy until ey) for (x in sx until ex) save[k++] = px[y * w + x]
            cur[id] = save
        }
    }
    val canUndo get() = undoStack.isNotEmpty()
    /** Undoes the last stroke; returns the area that changed (x0, y0, x1, y1) or null. */
    fun undo(): IntArray? {
        val last = undoStack.removeLastOrNull() ?: return null
        val tw = (w + tile - 1) / tile; var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
        for ((id, save) in last) {
            val sx = (id % tw) * tile; val sy = (id / tw) * tile; val ex = minOf(w, sx + tile); val ey = minOf(h, sy + tile); var k = 0
            for (y in sy until ey) for (x in sx until ex) px[y * w + x] = save[k++]
            x0 = minOf(x0, sx); y0 = minOf(y0, sy); x1 = maxOf(x1, ex - 1); y1 = maxOf(y1, ey - 1)
        }
        return intArrayOf(x0, y0, x1, y1)
    }

    /**
     * One dab of the brush at screen point (cx, cy), radius rpx pixels, centred on the body at [hit] (world radius wr).
     * vp: the view's proj*view matrix (column-major), vw x vh: the view's size, eye: camera position.
     * erase: back towards the original skin instead of the picture. Returns the changed texture area or null.
     */
    fun dab(hit: FloatArray, wr: Float, cx: Float, cy: Float, rpx: Float, vp: FloatArray, vw: Int, vh: Int, eye: FloatArray,
            stencil: Stencil?, erase: Boolean, strength: Float, softness: Float): IntArray? {
        if (!erase && stencil == null) return null
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        val reach = wr * 1.25f + cell
        val r2 = (wr * 1.25f) * (wr * 1.25f)
        val n = Math.ceil((reach / cell).toDouble()).toInt()
        val seen = HashSet<Int>()
        val hard = (1f - softness).coerceIn(0f, 0.98f)
        for (di in -n..n) for (dj in -n..n) for (dk in -n..n) {
            val list = grid[key(hit[0] + di * cell, hit[1] + dj * cell, hit[2] + dk * cell)] ?: continue
            for (t in list) {
                if (!seen.add(t)) continue
                val a = tris[t * 3]; val b = tris[t * 3 + 1]; val c = tris[t * 3 + 2]
                val ax = pos[a * 3]; val ay = pos[a * 3 + 1]; val az = pos[a * 3 + 2]
                val bx = pos[b * 3]; val by = pos[b * 3 + 1]; val bz = pos[b * 3 + 2]
                val qx = pos[c * 3]; val qy = pos[c * 3 + 1]; val qz = pos[c * 3 + 2]
                val gx = (ax + bx + qx) / 3 - hit[0]; val gy = (ay + by + qy) / 3 - hit[1]; val gz = (az + bz + qz) / 3 - hit[2]
                if (gx * gx + gy * gy + gz * gz > r2 + 0.1f) continue
                // only triangles facing the camera (the back of the head isn't painted through the face)
                val ux = bx - ax; val uy = by - ay; val uz = bz - az; val vx = qx - ax; val vy = qy - ay; val vz = qz - az
                val fx = uy * vz - uz * vy; val fy = uz * vx - ux * vz; val fz = ux * vy - uy * vx
                if (fx * (eye[0] - ax) + fy * (eye[1] - ay) + fz * (eye[2] - az) <= 0f) continue
                // rasterise the triangle in texture space
                val tax = uv[a * 2] * w; val tay = uv[a * 2 + 1] * h; val tbx = uv[b * 2] * w; val tby = uv[b * 2 + 1] * h; val tcx = uv[c * 2] * w; val tcy = uv[c * 2 + 1] * h
                val den = (tby - tcy) * (tax - tcx) + (tcx - tbx) * (tay - tcy); if (Math.abs(den) < 1e-9f) continue
                val rx0 = maxOf(0, Math.floor(minOf(tax, tbx, tcx).toDouble()).toInt() - 1); val rx1 = minOf(w - 1, Math.ceil(maxOf(tax, tbx, tcx).toDouble()).toInt() + 1)
                val ry0 = maxOf(0, Math.floor(minOf(tay, tby, tcy).toDouble()).toInt() - 1); val ry1 = minOf(h - 1, Math.ceil(maxOf(tay, tby, tcy).toDouble()).toInt() + 1)
                val pad = 1.5f / maxOf(1f, Math.sqrt((Math.abs(den) / 2f).toDouble()).toFloat())
                var touched = false
                for (y in ry0..ry1) for (x in rx0..rx1) {
                    val sx0 = x + 0.5f; val sy0 = y + 0.5f
                    var wa = ((tby - tcy) * (sx0 - tcx) + (tcx - tbx) * (sy0 - tcy)) / den
                    var wb = ((tcy - tay) * (sx0 - tcx) + (tax - tcx) * (sy0 - tcy)) / den
                    var wc = 1 - wa - wb
                    if (wa < -pad || wb < -pad || wc < -pad) continue
                    wa = wa.coerceAtLeast(0f); wb = wb.coerceAtLeast(0f); wc = wc.coerceAtLeast(0f); val sw = wa + wb + wc; wa /= sw; wb /= sw; wc /= sw
                    val px3 = wa * ax + wb * bx + wc * qx; val py3 = wa * ay + wb * by + wc * qy; val pz3 = wa * az + wb * bz + wc * qz
                    // where this bit of skin is on screen
                    val cw = vp[3] * px3 + vp[7] * py3 + vp[11] * pz3 + vp[15]; if (cw <= 1e-6f) continue
                    val sxs = ((vp[0] * px3 + vp[4] * py3 + vp[8] * pz3 + vp[12]) / cw + 1f) * 0.5f * vw
                    val sys = (1f - (vp[1] * px3 + vp[5] * py3 + vp[9] * pz3 + vp[13]) / cw) * 0.5f * vh
                    val dd = Math.hypot((sxs - cx).toDouble(), (sys - cy).toDouble()).toFloat() / rpx
                    if (dd >= 1f) continue
                    val fall = if (dd <= hard) 1f else { val q = 1f - (dd - hard) / (1f - hard); q * q * (3 - 2 * q) }
                    val i = y * w + x
                    val target: Int; var a8: Float
                    if (erase) { target = base[i]; a8 = 1f }
                    else {
                        val st = stencil!!; val inv = st.inv
                        val bxp = inv[0] * sxs + inv[1] * sys + inv[2]; val byp = inv[3] * sxs + inv[4] * sys + inv[5]
                        if (bxp < 0f || byp < 0f || bxp >= st.pw || byp >= st.ph) continue
                        target = st.pixels[byp.toInt() * st.pw + bxp.toInt()]
                        a8 = ((target ushr 24) and 255) / 255f
                    }
                    val k = strength * fall * a8; if (k <= 0.002f) continue
                    if (!touched) { touched = true; backup(rx0, ry0, rx1, ry1) }
                    val old = px[i]
                    var o = 0xff000000.toInt()
                    for (s in intArrayOf(16, 8, 0)) o = o or ((((old shr s) and 255) * (1 - k) + ((target shr s) and 255) * k).toInt().coerceIn(0, 255) shl s)
                    px[i] = o
                    if (x < x0) x0 = x; if (y < y0) y0 = y; if (x > x1) x1 = x; if (y > y1) y1 = y
                }
            }
        }
        return if (x1 < 0) null else intArrayOf(x0, y0, x1, y1)
    }
}
