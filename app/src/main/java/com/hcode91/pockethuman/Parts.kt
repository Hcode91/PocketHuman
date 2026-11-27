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
    /** withExpression: with forExport, keep skin weights but use the face as it's currently posed (for still renders). */
    fun build(model: HumanModel, forExport: Boolean, extraHidden: BooleanArray? = null, skip: Set<String> = emptySet(), withExpression: Boolean = false): List<Part> {
        // an imported model stands in for the whole character
        if (model.state.customModel != null) model.customPart?.let { return listOf(it) }
        val lib = model.lib; val rig = lib.rig
        val head = model.customHead
        val v0 = if (forExport && !withExpression) model.neutral else model.verts
        // with a replacement head, the app's eyelids (kept for blinking) and eyes move into the new head's sockets
        val v = if (head != null && head.appEyes && head.lids.isNotEmpty()) v0.copyOf().also { e ->
            val di = HashMap<Int, Int>(head.dispIdx.size * 2); for (k in head.dispIdx.indices) di[head.dispIdx[k]] = k
            for (i in head.lids) di[i]?.let { k -> for (c in 0..2) e[i * 3 + c] += head.disp[k * 3 + c] } } else v0
        val out = ArrayList<Part>()
        val bm = lib.base
        val n = bm.renderMap.size
        val pos = FloatArray(n * 3) { v[bm.renderMap[it / 3] * 3 + it % 3] }
        val j = if (forExport) IntArray(n * 4) { rig.joints[bm.renderMap[it / 4] * 4 + it % 4] } else IntArray(0)
        val w = if (forExport) FloatArray(n * 4) { rig.weights[bm.renderMap[it / 4] * 4 + it % 4] } else FloatArray(0)
        val skin = lib.skins[model.state.skin.coerceIn(0, lib.skins.size - 1)]
        // a replacement head hides the PocketHuman head and its pieces (keeping the app's eyes if asked)
        val headSkip = if (head == null) emptySet() else setOfNotNull(model.state.eyebrows, model.state.hair, "teeth_teeth_base", "tongue_tongue01") +
            (if (head.appEyes) emptySet() else setOfNotNull("eyes_high-poly", model.state.eyelashes))
        val ids = model.proxyIds().filter { it.first !in skip && it.first !in headSkip }
        // body vertices covered by the chosen clothes
        val hidden = BooleanArray(bm.nV)
        // the PocketHuman head under a replacement head is left out altogether (not kept as "under the outfit")
        val headGone = BooleanArray(bm.nV); head?.hidden?.forEach { if (it < bm.nV) headGone[it] = true }
        for ((id, _) in ids) lib.proxies.first { it.id == id }.delete.forEach { if (it < bm.nV) hidden[it] = true }
        extraHidden?.let { e -> for (i in e.indices) if (e[i]) hidden[i] = true }
        val vis = ArrayList<Int>(); val under = ArrayList<Int>(); val lidTris = ArrayList<Int>()
        val isLid = if (head != null && head.lidTexture != null && head.lids.isNotEmpty()) BooleanArray(bm.nV).also { l -> head.lids.forEach { if (it < bm.nV) l[it] = true } } else null
        var t = 0
        while (t < bm.tris.size) {
            val a = bm.tris[t]; val b = bm.tris[t + 1]; val c = bm.tris[t + 2]
            if (headGone[bm.renderMap[a]] || headGone[bm.renderMap[b]] || headGone[bm.renderMap[c]]) { t += 3; continue }
            val cov = hidden[bm.renderMap[a]] || hidden[bm.renderMap[b]] || hidden[bm.renderMap[c]]
            if (!cov && isLid != null && isLid[bm.renderMap[a]] && isLid[bm.renderMap[b]] && isLid[bm.renderMap[c]]) { lidTris.add(a); lidTris.add(b); lidTris.add(c) }
            else (if (cov) under else vis).apply { add(a); add(b); add(c) }
            t += 3
        }
        val nrm = weldedNormals(pos, bm.tris, n)
        val refs = bm.renderMap; val rw1 = FloatArray(n) { 1f }
        val bodyTex = model.bodyTexture(skin)
        out.add(Part("body", pos, nrm, bm.uv, vis.toIntArray(), bodyTex, false, skin.normal, j, w, refs, rw1, 1))
        if (lidTris.isNotEmpty()) out.add(Part("eyelids", pos, nrm, bm.uv, lidTris.toIntArray(), head!!.lidTexture!!, false, null, j, w, refs, rw1, 1))
        if (forExport && under.isNotEmpty())
            out.add(Part("body_under_outfit", pos, nrm, bm.uv, under.toIntArray(), bodyTex, false, skin.normal, j, w, refs, rw1, 1, true))
        // MakeHuman's own male genital piece, with the body skin (its own patch of the skin texture)
        val st = model.state; val g = lib.genitals
        // hidden under clothes that cover the crotch
        val covered = g != null && run {
            var cx = 0f; var cy = 0f; var cz = 0f; for (i in g.first) { cx += v[i * 3]; cy += v[i * 3 + 1]; cz += v[i * 3 + 2] }
            val k = g.first.size.toFloat(); cx /= k; cy /= k; cz /= k
            var any = false; for (i in 0 until 13380) { if (!hidden[i]) continue
                val dx = v[i * 3] - cx; val dy = v[i * 3 + 1] - cy; val dz = v[i * 3 + 2] - cz; if (dx * dx + dy * dy + dz * dz < 0.8f * 0.8f) { any = true; break } }
            any }
        if (g != null && !covered && st.adult && (st.genitals == 1 || (st.genitals == 0 && st.macro["gender"]!! >= 0.5f)) && "genitals" !in skip) {
            val (gm, guv, gt) = g; val gn = gm.size
            val gp = FloatArray(gn * 3) { v[gm[it / 3] * 3 + it % 3] }
            val gj = if (forExport) IntArray(gn * 4) { rig.joints[gm[it / 4] * 4 + it % 4] } else IntArray(0)
            val gw = if (forExport) FloatArray(gn * 4) { rig.weights[gm[it / 4] * 4 + it % 4] } else FloatArray(0)
            out.add(Part("genitals", gp, weldedNormals(gp, gt, gn), guv, gt, skin.texture, false, skin.normal, gj, gw, gm, FloatArray(gn) { 1f }, 1))
        }
        if (head != null) out.add(HeadSwap.part(model, head, v0, forExport))
        // the app's eyes, moved into the new head's sockets
        val eyeV = if (head != null && head.appEyes) v0.copyOf().also { e -> for (i in head.dispIdx.indices) for (k in 0..2) e[head.dispIdx[i] * 3 + k] += head.disp[i * 3 + k] } else v
        for ((id, variant) in ids) {
            val pm = lib.proxy(id)
            val vars = pm.info.variants; if (vars.isEmpty()) continue
            val vv = vars[variant.coerceIn(0, vars.size - 1)]
            val fitted = pm.fit(if (head != null && (id == "eyes_high-poly" || id == model.state.eyelashes)) eyeV else v)
            val rn = pm.renderMap.size
            val p = FloatArray(rn * 3) { fitted[pm.renderMap[it / 3] * 3 + it % 3] }
            var pj = IntArray(0); var pw = FloatArray(0)
            if (forExport) {
                val (vj, vw) = blendWeights(rig, pm.refs, pm.rw, pm.n)
                pj = IntArray(rn * 4) { vj[pm.renderMap[it / 4] * 4 + it % 4] }
                pw = FloatArray(rn * 4) { vw[pm.renderMap[it / 4] * 4 + it % 4] }
            }
            val prefs = IntArray(rn * 3) { pm.refs[pm.renderMap[it / 3] * 3 + it % 3] }
            val prw = FloatArray(rn * 3) { pm.rw[pm.renderMap[it / 3] * 3 + it % 3] }
            val ptex = if (pm.info.kind == "anatomy") model.anatomyTexture(vv.texture, skin) else vv.texture
            out.add(Part(id, p, weldedNormals(p, pm.tris, rn), pm.uv, pm.tris, ptex, vv.alpha, vv.normal, pj, pw, prefs, prw, 3))
        }
        return out
    }

    /** Skin weights for vertices that hang off three base vertices each: the blend of theirs, top four joints. */
    fun blendWeights(rig: Rig, refs: IntArray, rws: FloatArray, n: Int): Pair<IntArray, FloatArray> {
        val nb = rig.bones.size; val accum = FloatArray(nb)
        val vj = IntArray(n * 4); val vw = FloatArray(n * 4)
        for (i in 0 until n) {
            java.util.Arrays.fill(accum, 0f)
            for (k in 0..2) { val r = refs[i * 3 + k]; val rw = rws[i * 3 + k]
                for (q in 0..3) accum[rig.joints[r * 4 + q]] += rw * rig.weights[r * 4 + q] }
            val order = (0 until nb).sortedByDescending { accum[it] }.take(4)
            var s = 0f; for (q in order) s += accum[q]
            for (q in 0..3) { vj[i * 4 + q] = order[q]; vw[i * 4 + q] = if (s > 0) accum[order[q]] / s else if (q == 0) 1f else 0f }
        }
        return vj to vw
    }

    /** Joint head positions (base units) for the Mixamo skeleton, from the neutral shape. */
    fun jointHeads(model: HumanModel): Array<FloatArray> = model.lib.rig.bones.map { b ->
        val a = FloatArray(3)
        for (vi in b.head) for (k in 0..2) a[k] += model.neutral[vi * 3 + k]
        for (k in 0..2) a[k] /= b.head.size.toFloat(); a
    }.toTypedArray()
}
