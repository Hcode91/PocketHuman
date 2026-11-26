package com.hcode91.pockethuman

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Animation clips (Quaternius' Universal Animation Library, CC0), stored as world-space rotation changes per bone
 * (see tools/build_animations.py) and fitted onto the current body: world(t) = delta(t) * align(our bone -> source bone).
 */
class AnimLibrary(bytes: ByteArray, boneNames: List<String>) {
    class Clip(val name: String, val set: Int, val fps: Float, val frames: Int, val dirs: FloatArray, val hips: FloatArray, val rot: ShortArray) {
        val loops get() = name.endsWith("_Loop")
        val duration get() = (frames - 1) / fps
        /** a friendlier label: "Jog_Fwd_Loop" -> "Jog fwd (loop)" */
        val label: String get() {
            var n = name; val loop = n.endsWith("_Loop"); if (loop) n = n.removeSuffix("_Loop")
            val rm = n.endsWith("_RM"); if (rm) n = n.removeSuffix("_RM")
            n = n.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
            return n + (if (loop) " (loop)" else "") + (if (rm) " (moves)" else "")
        }
    }
    val nBones: Int; val clips = ArrayList<Clip>()
    /** our rig bone index for each stored bone */
    val map: IntArray

    init {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); b.position(4)
        nBones = b.int; val n = b.int
        repeat(n) {
            val nl = b.get().toInt() and 255; val nb = ByteArray(nl); b.get(nb)
            val set = b.get().toInt(); val fps = b.float; val nf = b.int
            val dirs = FloatArray(nBones * 3) { b.float }
            val hips = FloatArray(nf * 3); val rot = ShortArray(nf * nBones * 4)
            for (f in 0 until nf) { for (k in 0..2) hips[f * 3 + k] = b.float; for (q in 0 until nBones * 4) rot[f * nBones * 4 + q] = b.short }
            clips.add(Clip(String(nb), set, fps, nf, dirs, hips, rot))
        }
        val stored = listOf("Hips", "Spine", "Spine1", "Spine2", "Neck", "Head", "LeftShoulder", "LeftArm", "LeftUpLeg", "LeftLeg", "LeftFoot",
            "LeftForeArm", "LeftHand", "LeftHandIndex1", "LeftHandIndex2", "LeftHandIndex3", "LeftHandMiddle1", "LeftHandMiddle2",
            "LeftHandMiddle3", "LeftHandPinky1", "LeftHandPinky2", "LeftHandPinky3", "LeftHandRing1", "LeftHandRing2", "LeftHandRing3",
            "LeftHandThumb1", "LeftHandThumb2", "LeftHandThumb3", "LeftToeBase", "RightShoulder", "RightArm", "RightUpLeg", "RightLeg",
            "RightFoot", "RightForeArm", "RightHand", "RightHandIndex1", "RightHandIndex2", "RightHandIndex3", "RightHandMiddle1",
            "RightHandMiddle2", "RightHandMiddle3", "RightHandPinky1", "RightHandPinky2", "RightHandPinky3", "RightHandRing1",
            "RightHandRing2", "RightHandRing3", "RightHandThumb1", "RightHandThumb2", "RightHandThumb3", "RightToeBase")
        map = IntArray(nBones) { boneNames.indexOf("mixamorig:" + stored[it]) }
    }

    fun clip(name: String) = clips.firstOrNull { it.name == name }

    /**
     * Fits a clip onto a skeleton. heads: rest joint positions (any units), parents: rig parents, floorY: lowest body point.
     * Returns, per frame and rig bone, world rotation (x,y,z,w) and world position.
     */
    class Posed(val frames: Int, val rot: FloatArray, val pos: FloatArray)

    fun fit(c: Clip, heads: List<FloatArray>, parents: IntArray, floorY: Float): Posed {
        val nb = heads.size
        // our bind direction for each rig bone, chosen the same way as for the source
        val byName = HashMap<Int, Int>(); for (k in 0 until nBones) if (map[k] >= 0) byName[map[k]] = k
        val stored2rig = map
        // child choice from the stored bone order (mirrors CHILD in build_animations.py)
        val childName = mapOf("Hips" to "Spine", "Spine" to "Spine1", "Spine1" to "Spine2", "Spine2" to "Neck", "Neck" to "Head") +
            listOf("Left", "Right").flatMap { s -> listOf("${s}Shoulder" to "${s}Arm", "${s}Arm" to "${s}ForeArm", "${s}ForeArm" to "${s}Hand", "${s}Hand" to "${s}HandMiddle1",
                "${s}UpLeg" to "${s}Leg", "${s}Leg" to "${s}Foot", "${s}Foot" to "${s}ToeBase") +
                listOf("Index", "Middle", "Pinky", "Ring", "Thumb").flatMap { f -> listOf("${s}Hand${f}1" to "${s}Hand${f}2", "${s}Hand${f}2" to "${s}Hand${f}3") } }
        val align = Array(nb) { floatArrayOf(0f, 0f, 0f, 1f) }
        val names = rigNames ?: emptyList()
        for (k in 0 until nBones) {
            val r = stored2rig[k]; if (r < 0) continue
            val nm = names.getOrNull(r)?.removePrefix("mixamorig:") ?: continue
            val cName = childName[nm]
            val ci = cName?.let { cn -> names.indexOf("mixamorig:$cn") } ?: -1
            val d = if (ci >= 0) sub(heads[ci], heads[r]) else if (parents[r] >= 0) sub(heads[r], heads[parents[r]]) else floatArrayOf(0f, 1f, 0f)
            align[r] = between(norm(d), floatArrayOf(c.dirs[k * 3], c.dirs[k * 3 + 1], c.dirs[k * 3 + 2]))
        }
        val rot = FloatArray(c.frames * nb * 4); val pos = FloatArray(c.frames * nb * 3)
        val hipsR = stored2rig[0]; val hipH = heads[hipsR][1] - floorY
        val order = topo(parents)
        for (f in 0 until c.frames) {
            val ro = f * nb * 4; val po = f * nb * 3
            // world rotations: stored deltas times alignment; bones without data follow their parent
            for (r in 0 until nb) { rot[ro + r * 4 + 3] = 1f }
            for (k in 0 until nBones) {
                val r = stored2rig[k]; if (r < 0) continue
                val o = (f * nBones + k) * 4
                val d = floatArrayOf(c.rot[o] / 32767f, c.rot[o + 1] / 32767f, c.rot[o + 2] / 32767f, c.rot[o + 3] / 32767f)
                val w = qnorm(qmul(d, align[r])); System.arraycopy(w, 0, rot, ro + r * 4, 4)
            }
            for (r in order) {
                val p = parents[r]
                if (byName[r] == null && p >= 0) System.arraycopy(rot, ro + p * 4, rot, ro + r * 4, 4)
                if (p < 0) { for (k in 0..2) pos[po + r * 3 + k] = heads[r][k] + c.hips[f * 3 + k] * hipH }
                else {
                    val pr = floatArrayOf(rot[ro + p * 4], rot[ro + p * 4 + 1], rot[ro + p * 4 + 2], rot[ro + p * 4 + 3])
                    // rest rotations are identity, so a bone's offset from its parent is just the head difference
                    val off = qrot(pr, sub(heads[r], heads[p]))
                    for (k in 0..2) pos[po + r * 3 + k] = pos[po + p * 3 + k] + off[k]
                }
            }
        }
        return Posed(c.frames, rot, pos)
    }

    /** set by the owner: rig bone names in order */
    var rigNames: List<String>? = boneNames

    companion object {
        fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
        fun norm(a: FloatArray): FloatArray { val l = Math.sqrt((a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).toDouble()).toFloat().coerceAtLeast(1e-9f); return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }
        fun qmul(a: FloatArray, b: FloatArray) = floatArrayOf(
            a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1], a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
            a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3], a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2])
        fun qinv(q: FloatArray) = floatArrayOf(-q[0], -q[1], -q[2], q[3])
        fun qnorm(q: FloatArray): FloatArray { val l = Math.sqrt((q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]).toDouble()).toFloat().coerceAtLeast(1e-9f); return floatArrayOf(q[0] / l, q[1] / l, q[2] / l, q[3] / l) }
        fun qrot(q: FloatArray, v: FloatArray): FloatArray { val r = qmul(qmul(q, floatArrayOf(v[0], v[1], v[2], 0f)), qinv(q)); return floatArrayOf(r[0], r[1], r[2]) }
        /** shortest rotation taking unit vector a onto unit vector b */
        fun between(a: FloatArray, b: FloatArray): FloatArray {
            val d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
            if (d < -0.999999f) { val ax = if (Math.abs(a[0]) < 0.9f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
                val c = norm(floatArrayOf(a[1] * ax[2] - a[2] * ax[1], a[2] * ax[0] - a[0] * ax[2], a[0] * ax[1] - a[1] * ax[0])); return floatArrayOf(c[0], c[1], c[2], 0f) }
            val c = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
            return qnorm(floatArrayOf(c[0], c[1], c[2], 1f + d))
        }
        fun topo(parents: IntArray): IntArray {
            val out = ArrayList<Int>(); val done = BooleanArray(parents.size)
            fun visit(i: Int) { if (done[i]) return; if (parents[i] >= 0) visit(parents[i]); done[i] = true; out.add(i) }
            for (i in parents.indices) visit(i); return out.toIntArray()
        }
        /** bone matrices (column-major 4x4) for skinning: M = T(pos) * R(rot) * T(-head) */
        fun skinMatrices(p: Posed, frame: Int, heads: List<FloatArray>, out: FloatArray) {
            val nb = heads.size
            for (r in 0 until nb) {
                val q = FloatArray(4) { p.rot[(frame * nb + r) * 4 + it] }
                val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
                val m = floatArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0f,
                    2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0f,
                    2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0f, 0f, 0f, 0f, 1f)
                val h = heads[r]; val pp = FloatArray(3) { p.pos[(frame * nb + r) * 3 + it] }
                // translation = pos - R*head
                for (k in 0..2) m[12 + k] = pp[k] - (m[k] * h[0] + m[4 + k] * h[1] + m[8 + k] * h[2])
                System.arraycopy(m, 0, out, r * 16, 16)
            }
        }
    }
}
