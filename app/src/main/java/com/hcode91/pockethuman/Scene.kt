package com.hcode91.pockethuman

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Scene mode: up to two characters posed together for still images (visual novel CGs, sprites, posters).
 *
 * A pose = a moment of any animation clip (or the rest pose) + per-bone rotations on top, in degrees around
 * the bone's own axes (at rest these line up with the world: X bends forward/back, Y twists, Z tilts sideways).
 * Poses are applied on the CPU when something changes, so the 3D view just draws the result.
 */
class Pose {
    var clip: String? = null
    var time = 0f
    val offsets = HashMap<Int, FloatArray>()          // bone index -> degrees (x, y, z)
    fun toJson() = JSONObject().put("clip", clip ?: JSONObject.NULL).put("time", time.toDouble())
        .put("offsets", JSONObject().apply { for ((k, v) in offsets) put(k.toString(), JSONArray(v.map { it.toDouble() })) })
    fun load(j: JSONObject) {
        clip = if (j.isNull("clip")) null else j.getString("clip"); time = j.optDouble("time", 0.0).toFloat()
        offsets.clear(); j.optJSONObject("offsets")?.let { o -> for (k in o.keys()) { val a = o.getJSONArray(k); offsets[k.toInt()] = FloatArray(3) { a.getDouble(it).toFloat() } } }
    }
}

class SceneChar(var model: HumanModel) {
    var x = 0f; var y = 0f; var z = 0f; var yaw = 0f          // placement, metres and degrees
    val pose = Pose()
    val hidden = HashSet<String>()                             // clothing / hair ids taken off for this scene
    var label = model.state.name
    // caches: the unposed parts (rebuilt when clothes or the face change) and the fitted clip
    internal var raw: List<Part>? = null
    internal var fitted: Pair<String, AnimLibrary.Posed>? = null
    fun invalidate() { raw = null }

    companion object {
        fun fromJson(lib: Library, j: JSONObject): SceneChar {
            val m = HumanModel(lib); m.state.load(j.getJSONObject("state")); m.rebuild()
            return SceneChar(m).apply {
                x = j.optDouble("x").toFloat(); y = j.optDouble("y", 0.0).toFloat(); z = j.optDouble("z").toFloat(); yaw = j.optDouble("yaw").toFloat()
                j.optJSONObject("pose")?.let { pose.load(it) }
                j.optJSONArray("hidden")?.let { a -> for (i in 0 until a.length()) hidden.add(a.getString(i)) }
                label = j.optString("label", model.state.name)
            }
        }
    }

    fun toJson() = JSONObject().put("state", model.state.toJson()).put("x", x.toDouble()).put("y", y.toDouble()).put("z", z.toDouble())
        .put("yaw", yaw.toDouble()).put("pose", pose.toJson()).put("hidden", JSONArray(hidden.toList())).put("label", label)
}

/** Props for a scene: a floor and a simple bed, built as plain coloured boxes. */
object SceneProps {
    fun box(name: String, cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, tex: String): Part {
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val uv = ArrayList<Float>(); val tris = ArrayList<Int>()
        val faces = listOf(
            floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f))
        for (n in faces) {
            // two axes across the face
            val a = if (Math.abs(n[1]) > 0.5f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
            val b = floatArrayOf(n[1] * a[2] - n[2] * a[1], n[2] * a[0] - n[0] * a[2], n[0] * a[1] - n[1] * a[0])
            val base = pos.size / 3
            for ((u, v) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                pos.add(cx + (n[0] + a[0] * u + b[0] * v) * sx / 2); pos.add(cy + (n[1] + a[1] * u + b[1] * v) * sy / 2); pos.add(cz + (n[2] + a[2] * u + b[2] * v) * sz / 2)
                nrm.add(n[0]); nrm.add(n[1]); nrm.add(n[2]); uv.add((u + 1) / 2); uv.add((v + 1) / 2)
            }
            // wind so the face points along n
            val p0 = floatArrayOf(pos[base * 3], pos[base * 3 + 1], pos[base * 3 + 2]); val p1 = floatArrayOf(pos[base * 3 + 3], pos[base * 3 + 4], pos[base * 3 + 5]); val p2 = floatArrayOf(pos[base * 3 + 6], pos[base * 3 + 7], pos[base * 3 + 8])
            val e1 = floatArrayOf(p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]); val e2 = floatArrayOf(p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2])
            val c = floatArrayOf(e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
            if (c[0] * n[0] + c[1] * n[1] + c[2] * n[2] > 0) tris.addAll(listOf(base, base + 1, base + 2, base, base + 2, base + 3))
            else tris.addAll(listOf(base, base + 2, base + 1, base, base + 3, base + 2))
        }
        return Part(name, pos.toFloatArray(), nrm.toFloatArray(), uv.toFloatArray(), tris.toIntArray(), tex, false, null, IntArray(0), FloatArray(0), IntArray(0), FloatArray(0), 0)
    }
}

object ScenePoser {
    /** friendly names for the bones you can adjust, in the order shown */
    val BONES = listOf(
        "Hips" to "mixamorig:Hips", "Lower back" to "mixamorig:Spine", "Middle back" to "mixamorig:Spine1", "Chest" to "mixamorig:Spine2",
        "Neck" to "mixamorig:Neck", "Head" to "mixamorig:Head",
        "Left shoulder" to "mixamorig:LeftShoulder", "Left upper arm" to "mixamorig:LeftArm", "Left forearm" to "mixamorig:LeftForeArm", "Left hand" to "mixamorig:LeftHand",
        "Right shoulder" to "mixamorig:RightShoulder", "Right upper arm" to "mixamorig:RightArm", "Right forearm" to "mixamorig:RightForeArm", "Right hand" to "mixamorig:RightHand",
        "Left thigh" to "mixamorig:LeftUpLeg", "Left shin" to "mixamorig:LeftLeg", "Left foot" to "mixamorig:LeftFoot",
        "Right thigh" to "mixamorig:RightUpLeg", "Right shin" to "mixamorig:RightLeg", "Right foot" to "mixamorig:RightFoot")

    /** What the three sliders mean for a joint, and which way round they go (shown value = stored x sign). */
    class Axes(val labels: List<String>, val signs: FloatArray)
    fun axes(boneName: String): Axes {
        val n = boneName.removePrefix("mixamorig:").removePrefix("Left").removePrefix("Right")
        return when {
            n.startsWith("Shoulder") || n.startsWith("Arm") || n.startsWith("ForeArm") || n.startsWith("Hand") ->
                Axes(listOf("Twist", if (n.startsWith("ForeArm")) "Bend elbow" else "Swing forward / back", "Raise / lower"), floatArrayOf(1f, -1f, 1f))
            n.startsWith("UpLeg") -> Axes(listOf("Lift forward / back", "Twist", "Out to the side"), floatArrayOf(-1f, 1f, 1f))
            n == "Leg" -> Axes(listOf("Bend knee", "Twist", "Tilt"), floatArrayOf(1f, 1f, 1f))
            n.startsWith("Foot") || n.startsWith("ToeBase") -> Axes(listOf("Point toes / lift", "Turn in / out", "Roll"), floatArrayOf(1f, 1f, 1f))
            else -> Axes(listOf("Bend forward / back", "Turn", "Lean to the side"), floatArrayOf(1f, 1f, 1f))
        }
    }

    fun qEuler(d: FloatArray): FloatArray {
        val x = Math.toRadians(d[0].toDouble()) / 2; val y = Math.toRadians(d[1].toDouble()) / 2; val z = Math.toRadians(d[2].toDouble()) / 2
        val qx = floatArrayOf(Math.sin(x).toFloat(), 0f, 0f, Math.cos(x).toFloat()); val qy = floatArrayOf(0f, Math.sin(y).toFloat(), 0f, Math.cos(y).toFloat()); val qz = floatArrayOf(0f, 0f, Math.sin(z).toFloat(), Math.cos(z).toFloat())
        return AnimLibrary.qmul(AnimLibrary.qmul(qy, qx), qz)
    }

    /** Pose result in model units: world rotation and position per bone, plus the joint rest heads. */
    class Posed(val rot: FloatArray, val pos: FloatArray, val heads: List<FloatArray>)

    fun pose(c: SceneChar, al: AnimLibrary?): Posed {
        val m = c.model; val lib = m.lib; val bones = lib.rig.bones; val nb = bones.size
        val heads = PartBuilder.jointHeads(m).toList(); val parents = IntArray(nb) { bones[it].parent }
        var floor = Float.MAX_VALUE; for (i in 0 until 13380) floor = minOf(floor, m.neutral[i * 3 + 1])
        val rot = FloatArray(nb * 4); val pos = FloatArray(nb * 3)
        val clip = c.pose.clip?.let { al?.clip(it) }
        if (clip != null && al != null) {
            val fitted = c.fitted?.takeIf { it.first == clip.name }?.second ?: al.fit(clip, heads, parents, floor).also { c.fitted = clip.name to it }
            val f = (c.pose.time * clip.fps).toInt().coerceIn(0, fitted.frames - 1)
            System.arraycopy(fitted.rot, f * nb * 4, rot, 0, nb * 4); System.arraycopy(fitted.pos, f * nb * 3, pos, 0, nb * 3)
        } else for (r in 0 until nb) { rot[r * 4 + 3] = 1f; for (k in 0..2) pos[r * 3 + k] = heads[r][k] }
        // bone adjustments: local = parent⁻¹ · world, then local · offset, then back to world down the chain
        val order = AnimLibrary.topo(parents)
        val local = Array(nb) { r -> val w = FloatArray(4) { rot[r * 4 + it] }; val p = parents[r]
            if (p < 0) w else AnimLibrary.qmul(AnimLibrary.qinv(FloatArray(4) { rot[p * 4 + it] }), w) }
        // right-side bones take mirrored values, so the same numbers on both sides give a symmetric pose
        for ((b, d) in c.pose.offsets) if (b in 0 until nb) {
            val e = if ("Right" in bones[b].name) floatArrayOf(d[0], -d[1], -d[2]) else d
            local[b] = AnimLibrary.qnorm(AnimLibrary.qmul(local[b], qEuler(e)))
        }
        val outR = FloatArray(nb * 4); val outP = FloatArray(nb * 3)
        for (r in order) {
            val p = parents[r]
            val w = if (p < 0) local[r] else AnimLibrary.qnorm(AnimLibrary.qmul(FloatArray(4) { outR[p * 4 + it] }, local[r]))
            System.arraycopy(w, 0, outR, r * 4, 4)
            if (p < 0) for (k in 0..2) outP[r * 3 + k] = pos[r * 3 + k]
            else { val off = AnimLibrary.qrot(FloatArray(4) { outR[p * 4 + it] }, AnimLibrary.sub(heads[r], heads[p])); for (k in 0..2) outP[r * 3 + k] = outP[p * 3 + k] + off[k] }
        }
        return Posed(outR, outP, heads)
    }

    /**
     * The character's parts posed and placed in the scene (positions baked; no skinning left for the view).
     * Model units are decimetres; the scene places feet on y = 0 and uses x/y/z in metres.
     */
    fun parts(c: SceneChar, al: AnimLibrary?): Pair<List<Part>, Posed> {
        val m = c.model
        val raw = c.raw ?: run {
            // take off the items hidden for this scene by building from a copy of the state
            val saved = m.state.toJson()
            if (c.hidden.isNotEmpty()) {
                m.state.clothes.removeAll(c.hidden)
                if (m.state.hair in c.hidden) m.state.hair = null
                if (m.state.eyebrows in c.hidden) m.state.eyebrows = null
            }
            m.rebuild()
            val built = PartBuilder.build(m, true, withExpression = true).filter { it.name != "body_under_outfit" }
            if (c.hidden.isNotEmpty()) { m.state.load(saved); m.rebuild() }
            built.also { c.raw = it }
        }
        val p = pose(c, al); val nb = p.heads.size
        val mats = FloatArray(nb * 16)
        AnimLibrary.skinMatrices(AnimLibrary.Posed(1, p.rot, p.pos), 0, p.heads, mats)
        var floor = Float.MAX_VALUE; for (i in 0 until 13380) floor = minOf(floor, m.neutral[i * 3 + 1])
        val cy = Math.cos(Math.toRadians(c.yaw.toDouble())).toFloat(); val sy = Math.sin(Math.toRadians(c.yaw.toDouble())).toFloat()
        val out = raw.map { part ->
            val n = part.pos.size / 3; val np = FloatArray(n * 3); val nn = FloatArray(n * 3)
            for (i in 0 until n) {
                var x = 0f; var y = 0f; var z = 0f; var nx = 0f; var ny = 0f; var nz = 0f
                val px = part.pos[i * 3]; val py = part.pos[i * 3 + 1]; val pz = part.pos[i * 3 + 2]
                val qx = part.nrm[i * 3]; val qy = part.nrm[i * 3 + 1]; val qz = part.nrm[i * 3 + 2]
                if (part.joints.isEmpty()) { x = px; y = py; z = pz; nx = qx; ny = qy; nz = qz }
                else for (k in 0..3) {
                    val w = part.weights[i * 4 + k]; if (w == 0f) continue
                    val o = part.joints[i * 4 + k] * 16
                    x += w * (mats[o] * px + mats[o + 4] * py + mats[o + 8] * pz + mats[o + 12])
                    y += w * (mats[o + 1] * px + mats[o + 5] * py + mats[o + 9] * pz + mats[o + 13])
                    z += w * (mats[o + 2] * px + mats[o + 6] * py + mats[o + 10] * pz + mats[o + 14])
                    nx += w * (mats[o] * qx + mats[o + 4] * qy + mats[o + 8] * qz); ny += w * (mats[o + 1] * qx + mats[o + 5] * qy + mats[o + 9] * qz); nz += w * (mats[o + 2] * qx + mats[o + 6] * qy + mats[o + 10] * qz)
                }
                // feet to the floor, turn, then place (model units are decimetres)
                y -= floor
                np[i * 3] = x * cy + z * sy + c.x * 10f; np[i * 3 + 1] = y + c.y * 10f; np[i * 3 + 2] = -x * sy + z * cy + c.z * 10f
                val l = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat().coerceAtLeast(1e-6f)
                nn[i * 3] = (nx * cy + nz * sy) / l; nn[i * 3 + 1] = ny / l; nn[i * 3 + 2] = (-nx * sy + nz * cy) / l
            }
            Part(part.name + "@" + c.label, np, nn, part.uv, part.tris, part.texture, part.alpha, part.normalTex, IntArray(0), FloatArray(0), IntArray(0), FloatArray(0), 0, part.hiddenUnderOutfit)
        }
        // joint positions in the scene, for tap-to-select
        val jp = FloatArray(nb * 3)
        for (r in 0 until nb) {
            val x = p.pos[r * 3]; val y = p.pos[r * 3 + 1] - floor; val z = p.pos[r * 3 + 2]
            jp[r * 3] = x * cy + z * sy + c.x * 10f; jp[r * 3 + 1] = y + c.y * 10f; jp[r * 3 + 2] = -x * sy + z * cy + c.z * 10f
        }
        return out to Posed(p.rot, jp, p.heads)
    }

    /** Finger bones of one hand ("Left" / "Right"), thumb last. */
    fun fingers(lib: Library, side: String): List<Int> = lib.rig.bones.indices.filter { lib.rig.bones[it].name.startsWith("mixamorig:${side}Hand") && lib.rig.bones[it].name != "mixamorig:${side}Hand" }

    /** Curl (0..1) every finger joint of a hand; the thumb bends half as much. */
    fun setCurl(c: SceneChar, side: String, amount: Float) {
        val lib = c.model.lib
        for (b in fingers(lib, side)) {
            val thumb = "Thumb" in lib.rig.bones[b].name
            val d = c.pose.offsets.getOrPut(b) { FloatArray(3) }; d[2] = -amount * (if (thumb) 35f else 80f)
            if (d.all { it == 0f }) c.pose.offsets.remove(b)
        }
    }
    fun curlOf(c: SceneChar, side: String): Float {
        val lib = c.model.lib; val b = fingers(lib, side).firstOrNull { "Index2" in lib.rig.bones[it].name } ?: return 0f
        return Math.abs(c.pose.offsets[b]?.get(2) ?: 0f) / 80f
    }

    /** Copies one side's adjustments onto the other, mirrored. */
    fun mirror(c: SceneChar, from: String) {
        val to = if (from == "Left") "Right" else "Left"; val bones = c.model.lib.rig.bones
        val idx = bones.indices.associateBy { bones[it].name }
        for (b in bones.indices) {
            val n = bones[b].name; if (!n.startsWith("mixamorig:$to")) continue
            val src = idx[n.replaceFirst("mixamorig:$to", "mixamorig:$from")] ?: continue
            val d = c.pose.offsets[src]
            if (d == null) c.pose.offsets.remove(b) else c.pose.offsets[b] = d.copyOf()
        }
    }

    fun saveScene(dir: File, name: String, chars: List<SceneChar>, extra: JSONObject) {
        dir.mkdirs()
        File(dir, name.replace(Regex("[^A-Za-z0-9 _-]"), "_") + ".json").writeText(extra.put("name", name).put("chars", JSONArray(chars.map { it.toJson() })).toString())
    }
}

/** Plain-colour images for the props, written once into the app's own storage. */
object SceneTextures {
    val COLOURS = mapOf("floor_wood" to 0xFF6B4A33.toInt(), "floor_tatami" to 0xFFB9A36B.toInt(), "floor_stone" to 0xFF5D5E63.toInt(),
        "bed_frame" to 0xFF4A3426.toInt(), "bed_sheet" to 0xFFE9E4DA.toInt(), "bed_pillow" to 0xFFF4F1EA.toInt(), "bed_red" to 0xFF7E2430.toInt())
    fun path(name: String) = "imported/scene/$name.png"
}
