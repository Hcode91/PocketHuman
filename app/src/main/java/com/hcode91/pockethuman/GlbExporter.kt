package com.hcode91.pockethuman

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes the character as a single .glb: skinned meshes in metres (y up, facing +z),
 * a Mixamo-named skeleton in the A-pose, textures embedded.
 */
object GlbExporter {
    private const val SCALE = 0.1f

    fun write(model: HumanModel, out: OutputStream) {
        val parts = PartBuilder.build(model, true)
        val heads = PartBuilder.jointHeads(model).map { h -> FloatArray(3) { h[it] * SCALE } }
        val bones = model.lib.rig.bones
        val bin = ByteArrayOutputStream()
        val views = JSONArray(); val accs = JSONArray()
        fun align() { while (bin.size() % 4 != 0) bin.write(0) }
        fun view(bytes: ByteArray, target: Int?): Int {
            align(); val o = JSONObject().put("buffer", 0).put("byteOffset", bin.size()).put("byteLength", bytes.size)
            if (target != null) o.put("target", target)
            bin.write(bytes); views.put(o); return views.length() - 1
        }
        fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        fun accF(a: FloatArray, comps: Int, type: String, target: Int? = 34962, minmax: Boolean = false): Int {
            val bb = le(a.size * 4); a.forEach { bb.putFloat(it) }
            val o = JSONObject().put("bufferView", view(bb.array(), target)).put("componentType", 5126).put("count", a.size / comps).put("type", type)
            if (minmax) {
                val mn = FloatArray(comps) { Float.MAX_VALUE }; val mx = FloatArray(comps) { -Float.MAX_VALUE }
                for (i in a.indices) { val k = i % comps; if (a[i] < mn[k]) mn[k] = a[i]; if (a[i] > mx[k]) mx[k] = a[i] }
                o.put("min", JSONArray(mn.toList())).put("max", JSONArray(mx.toList()))
            }
            accs.put(o); return accs.length() - 1
        }
        fun accU16(a: IntArray, comps: Int, type: String): Int {
            val bb = le(a.size * 2); a.forEach { bb.putShort(it.toShort()) }
            accs.put(JSONObject().put("bufferView", view(bb.array(), 34962)).put("componentType", 5123).put("count", a.size / comps).put("type", type)); return accs.length() - 1
        }
        fun accIdx(a: IntArray): Int {
            val bb = le(a.size * 4); a.forEach { bb.putInt(it) }
            accs.put(JSONObject().put("bufferView", view(bb.array(), 34963)).put("componentType", 5125).put("count", a.size).put("type", "SCALAR")); return accs.length() - 1
        }
        val images = JSONArray(); val textures = JSONArray(); val materials = JSONArray(); val meshes = JSONArray(); val nodes = JSONArray()
        val texIndex = HashMap<String, Int>()
        for (p in parts) {
            val ti = texIndex.getOrPut(p.texture) {
                val data = model.lib.am.bytes(p.texture)
                images.put(JSONObject().put("bufferView", view(data, null)).put("mimeType", if (p.texture.endsWith(".png")) "image/png" else "image/jpeg"))
                textures.put(JSONObject().put("source", images.length() - 1).put("sampler", 0)); textures.length() - 1
            }
            val mat = JSONObject().put("name", p.name)
                .put("pbrMetallicRoughness", JSONObject().put("baseColorTexture", JSONObject().put("index", ti)).put("metallicFactor", 0).put("roughnessFactor", if (p.name.startsWith("eyes")) 0.2 else 0.7))
            if (p.alpha) mat.put("alphaMode", "MASK").put("alphaCutoff", 0.4).put("doubleSided", true)
            materials.put(mat)
            val pos = FloatArray(p.pos.size) { p.pos[it] * SCALE }
            val attrs = JSONObject().put("POSITION", accF(pos, 3, "VEC3", minmax = true)).put("NORMAL", accF(p.nrm, 3, "VEC3"))
                .put("TEXCOORD_0", accF(p.uv, 2, "VEC2")).put("JOINTS_0", accU16(p.joints, 4, "VEC4")).put("WEIGHTS_0", accF(p.weights, 4, "VEC4"))
            meshes.put(JSONObject().put("name", p.name).put("primitives", JSONArray().put(JSONObject().put("attributes", attrs).put("indices", accIdx(p.tris)).put("material", materials.length() - 1))))
            nodes.put(JSONObject().put("name", p.name).put("mesh", meshes.length() - 1).put("skin", 0))
        }
        val j0 = nodes.length()
        for ((i, b) in bones.withIndex()) {
            val h = heads[i]; val ph = if (b.parent >= 0) heads[b.parent] else FloatArray(3)
            nodes.put(JSONObject().put("name", b.name).put("translation", JSONArray(listOf(h[0] - ph[0], h[1] - ph[1], h[2] - ph[2]))))
        }
        for ((i, b) in bones.withIndex()) if (b.parent >= 0) {
            val pn = nodes.getJSONObject(j0 + b.parent)
            (pn.optJSONArray("children") ?: JSONArray().also { pn.put("children", it) }).put(j0 + i)
        }
        val ibm = FloatArray(bones.size * 16)
        for (i in bones.indices) { val o = i * 16; ibm[o] = 1f; ibm[o + 5] = 1f; ibm[o + 10] = 1f; ibm[o + 15] = 1f
            ibm[o + 12] = -heads[i][0]; ibm[o + 13] = -heads[i][1]; ibm[o + 14] = -heads[i][2] }
        val skin = JSONObject().put("joints", JSONArray((j0 until j0 + bones.size).toList())).put("inverseBindMatrices", accF(ibm, 16, "MAT4", null)).put("skeleton", j0)
        val rootChildren = JSONArray((0 until j0).toList()).put(j0)
        nodes.put(JSONObject().put("name", model.state.name.ifBlank { "Character" }).put("children", rootChildren))
        align()
        val st = model.state
        val gltf = JSONObject()
            .put("asset", JSONObject().put("version", "2.0").put("generator", "PocketHuman").put("copyright", "Made with PocketHuman. Base mesh and assets: MakeHuman / MPFB2 community, CC0")
                .put("extras", st.toJson()))
            .put("scene", 0).put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(nodes.length() - 1))))
            .put("nodes", nodes).put("meshes", meshes).put("materials", materials).put("textures", textures).put("images", images)
            .put("samplers", JSONArray().put(JSONObject().put("magFilter", 9729).put("minFilter", 9987)))
            .put("skins", JSONArray().put(skin)).put("accessors", accs).put("bufferViews", views)
            .put("buffers", JSONArray().put(JSONObject().put("byteLength", bin.size())))
        var js = gltf.toString().toByteArray()
        if (js.size % 4 != 0) js += ByteArray(4 - js.size % 4) { ' '.code.toByte() }
        val body = bin.toByteArray()
        val hdr = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(0x46546C67).putInt(2).putInt(12 + 8 + js.size + 8 + body.size).putInt(js.size).putInt(0x4E4F534A)
        out.write(hdr.array()); out.write(js)
        val bh = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).putInt(0x004E4942)
        out.write(bh.array()); out.write(body); out.flush()
    }
}
