package com.hcode91.pockethuman

/** One drawable/exportable mesh, in base-mesh units (decimetres, y up). */
class Part(
    val name: String,
    val pos: FloatArray, val nrm: FloatArray, val uv: FloatArray, val tris: IntArray,
    val texture: String, val alpha: Boolean,
    val joints: IntArray, val weights: FloatArray
) { val count get() = pos.size / 3 }

object PartBuilder {
    fun build(model: HumanModel, withSkinning: Boolean): List<Part> {
        val lib = model.lib; val v = model.verts; val rig = lib.rig
        val out = ArrayList<Part>()
        val bm = lib.base
        val n = bm.renderMap.size
        val pos = FloatArray(n * 3) { v[bm.renderMap[it / 3] * 3 + it % 3] }
        val j = if (withSkinning) IntArray(n * 4) { rig.joints[bm.renderMap[it / 4] * 4 + it % 4] } else IntArray(0)
        val w = if (withSkinning) FloatArray(n * 4) { rig.weights[bm.renderMap[it / 4] * 4 + it % 4] } else FloatArray(0)
        val skins = lib.skins
        out.add(Part("body", pos, weldedNormals(pos, bm.tris, n), bm.uv, bm.tris, skins[model.state.skin.coerceIn(0, skins.size - 1)].second, false, j, w))
        for ((id, variant) in model.proxyIds()) {
            val pm = lib.proxy(id)
            val vars = pm.info.variants; if (vars.isEmpty()) continue
            val vv = vars[variant.coerceIn(0, vars.size - 1)]
            val fitted = pm.fit(v)
            val rn = pm.renderMap.size
            val p = FloatArray(rn * 3) { fitted[pm.renderMap[it / 3] * 3 + it % 3] }
            var pj = IntArray(0); var pw = FloatArray(0)
            if (withSkinning) {
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
            out.add(Part(id, p, weldedNormals(p, pm.tris, rn), pm.uv, pm.tris, vv.texture, vv.alpha, pj, pw))
        }
        return out
    }

    /** Joint head positions (base units) for the Mixamo skeleton. */
    fun jointHeads(model: HumanModel): Array<FloatArray> = model.lib.rig.bones.map { b ->
        val a = FloatArray(3)
        for (vi in b.head) for (k in 0..2) a[k] += model.verts[vi * 3 + k]
        for (k in 0..2) a[k] /= b.head.size.toFloat(); a
    }.toTypedArray()
}
