package com.hcode91.pockethuman

/** One drawable/exportable mesh, in base-mesh units (decimetres, y up). */
class Part(
    val name: String,
    val pos: FloatArray, val nrm: FloatArray, val uv: FloatArray, val tris: IntArray,
    val texture: String, val alpha: Boolean, val normalTex: String?,
    val joints: IntArray, val weights: FloatArray,
    /** how each render vertex maps onto base-mesh vertices, for carrying blend shapes over: (refs, weights) of 1 or 3 base verts */
    val baseRefs: IntArray, val baseRefW: FloatArray, val refsPer: Int,
    /** shown only when the outfit is taken off (body under clothes) */
    val hiddenUnderOutfit: Boolean = false
) { val count get() = pos.size / 3 }

object PartBuilder {
    /** forExport: neutral face, skin weights, and the body under the clothes kept as its own part. */
    fun build(model: HumanModel, forExport: Boolean): List<Part> {
        val lib = model.lib; val v = if (forExport) model.neutral else model.verts; val rig = lib.rig
        val out = ArrayList<Part>()
        val bm = lib.base
        val n = bm.renderMap.size
        val pos = FloatArray(n * 3) { v[bm.renderMap[it / 3] * 3 + it % 3] }
        val j = if (forExport) IntArray(n * 4) { rig.joints[bm.renderMap[it / 4] * 4 + it % 4] } else IntArray(0)
        val w = if (forExport) FloatArray(n * 4) { rig.weights[bm.renderMap[it / 4] * 4 + it % 4] } else FloatArray(0)
        val skin = lib.skins[model.state.skin.coerceIn(0, lib.skins.size - 1)]
        val ids = model.proxyIds()
        // body vertices covered by the chosen clothes
        val hidden = BooleanArray(bm.nV)
        for ((id, _) in ids) lib.proxies.first { it.id == id }.delete.forEach { if (it < bm.nV) hidden[it] = true }
        val vis = ArrayList<Int>(); val under = ArrayList<Int>()
        var t = 0
        while (t < bm.tris.size) {
            val a = bm.tris[t]; val b = bm.tris[t + 1]; val c = bm.tris[t + 2]
            val cov = hidden[bm.renderMap[a]] || hidden[bm.renderMap[b]] || hidden[bm.renderMap[c]]
            (if (cov) under else vis).apply { add(a); add(b); add(c) }
            t += 3
        }
        val nrm = weldedNormals(pos, bm.tris, n)
        val refs = bm.renderMap; val rw1 = FloatArray(n) { 1f }
        out.add(Part("body", pos, nrm, bm.uv, vis.toIntArray(), skin.texture, false, skin.normal, j, w, refs, rw1, 1))
        if (forExport && under.isNotEmpty())
            out.add(Part("body_under_outfit", pos, nrm, bm.uv, under.toIntArray(), skin.texture, false, skin.normal, j, w, refs, rw1, 1, true))
        for ((id, variant) in ids) {
            val pm = lib.proxy(id)
            val vars = pm.info.variants; if (vars.isEmpty()) continue
            val vv = vars[variant.coerceIn(0, vars.size - 1)]
            val fitted = pm.fit(v)
            val rn = pm.renderMap.size
            val p = FloatArray(rn * 3) { fitted[pm.renderMap[it / 3] * 3 + it % 3] }
            var pj = IntArray(0); var pw = FloatArray(0)
            if (forExport) {
                // each proxy vertex takes the blended skin weights of the three base vertices it hangs off
                val nb = rig.bones.size
                val accum = FloatArray(nb)
                val vj = IntArray(pm.n * 4); val vw = FloatArray(pm.n * 4)
                for (i in 0 until pm.n) {
                    java.util.Arrays.fill(accum, 0f)
                    for (k in 0..2) { val r = pm.refs[i * 3 + k]; val rw = pm.rw[i * 3 + k]
                        for (q in 0..3) accum[rig.joints[r * 4 + q]] += rw * rig.weights[r * 4 + q] }
                    val order = (0 until nb).sortedByDescending { accum[it] }.take(4)
                    var s = 0f; for (q in order) s += accum[q]
                    for (q in 0..3) { vj[i * 4 + q] = order[q]; vw[i * 4 + q] = if (s > 0) accum[order[q]] / s else if (q == 0) 1f else 0f }
                }
                pj = IntArray(rn * 4) { vj[pm.renderMap[it / 4] * 4 + it % 4] }
                pw = FloatArray(rn * 4) { vw[pm.renderMap[it / 4] * 4 + it % 4] }
            }
            val prefs = IntArray(rn * 3) { pm.refs[pm.renderMap[it / 3] * 3 + it % 3] }
            val prw = FloatArray(rn * 3) { pm.rw[pm.renderMap[it / 3] * 3 + it % 3] }
            out.add(Part(id, p, weldedNormals(p, pm.tris, rn), pm.uv, pm.tris, vv.texture, vv.alpha, vv.normal, pj, pw, prefs, prw, 3))
        }
        return out
    }

    /** Joint head positions (base units) for the Mixamo skeleton, from the neutral shape. */
    fun jointHeads(model: HumanModel): Array<FloatArray> = model.lib.rig.bones.map { b ->
        val a = FloatArray(3)
        for (vi in b.head) for (k in 0..2) a[k] += model.neutral[vi * 3 + k]
        for (k in 0..2) a[k] /= b.head.size.toFloat(); a
    }.toTypedArray()
}
