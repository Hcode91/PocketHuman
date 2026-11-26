package com.hcode91.pockethuman

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Downscales/re-encodes a texture file. Returns the written file name (with extension) relative to dstDir. */
fun interface TextureSink { fun write(src: File, dstDir: File, baseName: String, maxSize: Int): String? }

/**
 * Imports a MakeHuman asset pack (.zip) into the app's own formats under filesDir/imported/<packId>/.
 * Handles clothes (and accessories, beards, glasses, hats…), hair, eyebrows, eyelashes, skins and shape targets.
 */
class PackImporter(private val filesDir: File, private val textures: TextureSink) {
    class Result(val packId: String, val counts: Map<String, Int>, val skipped: List<String>)

    private fun adultOk(name: String) = !Regex("(^|[-_/])(baby|child|kid)([-_.]|$)").containsMatchIn(name.lowercase())

    fun import(zip: InputStream, packId: String, title: String, packLicense: String, progress: (String) -> Unit = {}): Result {
        val tmp = File(filesDir, "import_tmp/$packId").apply { deleteRecursively(); mkdirs() }
        val out = File(filesDir, "imported/$packId").apply { deleteRecursively(); mkdirs() }
        try {
            progress("Unpacking…")
            ZipInputStream(zip.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val f = File(tmp, e.name).canonicalFile
                    if (!f.path.startsWith(tmp.canonicalPath)) continue   // no path tricks
                    if (e.isDirectory) { f.mkdirs(); continue }
                    if (f.name.endsWith(".thumb") || f.name.endsWith(".blend")) continue
                    f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) }
                }
            }
            return convert(tmp, out, packId, title, packLicense, progress)
        } finally { tmp.deleteRecursively() }
    }

    private fun convert(root: File, out: File, packId: String, title: String, packLicense: String, progress: (String) -> Unit): Result {
        // per-asset metadata (author, licence) from packs/*.json
        val meta = HashMap<String, JSONObject>()
        root.resolve("packs").listFiles()?.filter { it.name.endsWith(".json") }?.forEach { f ->
            runCatching { val j = JSONObject(f.readText()); for (k in j.keys()) j.optJSONObject(k)?.let { meta[k] = it } }
        }
        fun credit(name: String): JSONObject {
            val m = meta[name]
            return JSONObject().put("asset", name).put("pack", title)
                .put("author", m?.optString("author").orEmpty()).put("license", m?.optString("license")?.ifEmpty { null } ?: packLicense)
        }
        val proxies = JSONArray(); val skins = JSONArray(); val groups = JSONArray(); val faceShapes = JSONArray(); val credits = JSONArray()
        val skipped = ArrayList<String>(); val counts = HashMap<String, Int>()
        File(out, "proxies").mkdirs(); File(out, "skins").mkdirs()

        // ---- meshes: clothes/, hair/, eyebrows/, eyelashes/
        for (kindDir in listOf("clothes", "hair", "eyebrows", "eyelashes")) {
            val dirs = root.resolve(kindDir).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: continue
            for (d in dirs) {
                val name = d.name
                if (!adultOk(name)) { skipped.add(name); continue }
                val mhclo = d.listFiles()?.firstOrNull { it.name.endsWith(".mhclo") } ?: continue
                progress("Converting $name")
                runCatching {
                    val id = "${packId}_${kindDir}_$name"
                    val info = Mhclo.read(mhclo)
                    val obj = File(d, info.obj ?: return@runCatching).takeIf { it.exists() } ?: return@runCatching
                    val (pv, pvt, pf) = ObjReader.read(obj)
                    if (info.refs.size / 3 != pv.size / 3) error("vertex count mismatch")
                    val (rmap, ruv, tris) = ObjReader.unweld(pv.size / 3, pvt, pf)
                    File(out, "proxies/$id.bin").writeBytes(proxyBin(info, pv.size / 3, rmap, ruv, tris))
                    val variants = JSONArray()
                    val mats = d.listFiles()?.filter { it.name.endsWith(".mhmat") }?.sortedBy { if (it.name == info.material) 0 else 1 } ?: emptyList()
                    for (mp in mats) {
                        val m = Mhclo.mhmat(mp)
                        val diff = m["diffuseTexture"]?.let { File(d, it.substringAfterLast('/')) }?.takeIf { it.exists() } ?: continue
                        val vname = mp.nameWithoutExtension
                        val tex = textures.write(diff, File(out, "proxies"), "${id}_$vname", if (kindDir == "clothes" || kindDir == "hair") 1024 else 512) ?: continue
                        val v = JSONObject().put("name", vname.replace('_', ' ')).put("texture", "imported/$packId/proxies/$tex").put("alpha", tex.endsWith(".png"))
                        m["normalmapTexture"]?.let { File(d, it.substringAfterLast('/')) }?.takeIf { it.exists() }?.let { nf ->
                            textures.write(nf, File(out, "proxies"), "${id}_${vname}_normal", 1024)?.let { v.put("normal", "imported/$packId/proxies/$it") }
                        }
                        variants.put(v)
                    }
                    if (variants.length() == 0) error("no texture")
                    val kind = if (kindDir == "clothes" && (meta[name]?.optString("category")?.lowercase() == "hair")) "hair" else kindDir
                    val label = (meta[name]?.optString("name")?.ifEmpty { null } ?: name).replace('_', ' ')
                    proxies.put(JSONObject().put("id", id).put("kind", kind).put("label", label).put("file", "imported/$packId/proxies/$id.bin")
                        .put("delete", JSONArray(info.delete.toList())).put("variants", variants).put("zdepth", info.zdepth).put("credit", credit(name)))
                    credits.put(credit(name)); counts[kind] = (counts[kind] ?: 0) + 1
                }.onFailure { skipped.add("$name (${it.message})") }
            }
        }
        // ---- skins/
        root.resolve("skins").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { d ->
            val name = d.name
            if (!adultOk(name)) { skipped.add(name); return@forEach }
            val mp = d.listFiles()?.firstOrNull { it.name.endsWith(".mhmat") } ?: return@forEach
            val m = Mhclo.mhmat(mp)
            val diff = m["diffuseTexture"]?.let { File(d, it.substringAfterLast('/')) }?.takeIf { it.exists() } ?: return@forEach
            progress("Converting skin $name")
            val tex = textures.write(diff, File(out, "skins"), name, 2048) ?: return@forEach
            val o = JSONObject().put("name", name.replace('_', ' ')).put("texture", "imported/$packId/skins/$tex")
                .put("adult", name.contains("genital")).put("system", false).put("gender", if (Regex("(^|_)male").containsMatchIn(name)) "male" else "female")
                .put("age", if (name.contains("old")) "old" else if (name.contains("middle")) "middleage" else "young").put("credit", credit(name))
            m["normalmapTexture"]?.let { File(d, it.substringAfterLast('/')) }?.takeIf { it.exists() }?.let { nf ->
                textures.write(nf, File(out, "skins"), name + "_normal", 1024)?.let { o.put("normal", "imported/$packId/skins/$it") } }
            for ((r, keys) in listOf("african" to listOf("african", "black"), "asian" to listOf("asian", "indian", "eurasian"), "caucasian" to listOf("caucasian", "light", "white")))
                if (keys.any { name.contains(it) }) { o.put("race", r); break }
            skins.put(o); credits.put(credit(name)); counts["skins"] = (counts["skins"] ?: 0) + 1
        }
        // ---- targets/<group>/*.target(.gz): detail sliders, or face shapes for visemes / face units
        val blob = java.io.ByteArrayOutputStream(); val index = JSONObject()
        root.resolve("targets").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { gdir ->
            val files = gdir.walkTopDown().filter { it.isFile && (it.name.endsWith(".target") || it.name.endsWith(".target.gz")) }.sortedBy { it.name }.toList()
            val names = ArrayList<String>()
            for (f in files) {
                val base = f.name.removeSuffix(".gz").removeSuffix(".target")
                if (!adultOk(base)) { skipped.add(base); continue }
                val key = "$packId/${gdir.name}/$base"
                val (idx, d) = readTarget(f)
                index.put(key, JSONArray(listOf(blob.size(), idx.size)))
                val bb = ByteBuffer.allocate(idx.size * 2 + d.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                idx.forEach { bb.putShort(it.toShort()) }; d.forEach { bb.putShort(Half.toHalf(it)) }
                blob.write(bb.array()); names.add(base); credits.put(credit(base))
            }
            if (names.isEmpty()) return@forEach
            val isFace = gdir.name.contains("viseme") || gdir.name.contains("faceunit") || gdir.name.contains("expression")
            if (isFace) { names.forEach { faceShapes.put("$packId/${gdir.name}/$it") }; counts["face shapes"] = (counts["face shapes"] ?: 0) + names.size }
            else {
                // pair "...-decr" / "...-incr" (and down/up, in/out) into one slider; anything else is a 0..1 slider
                val sliders = JSONArray(); val used = HashSet<String>()
                val pairs = listOf("decr" to "incr", "down" to "up", "in" to "out", "less" to "more", "small" to "big")
                for (n in names) {
                    if (n in used) continue
                    var done = false
                    for ((a, b) in pairs) {
                        val m = Regex("^(.*)[-_]($a|$b)$").find(n) ?: continue
                        val stem = m.groupValues[1]
                        val neg = names.firstOrNull { it == "$stem-$a" || it == "${stem}_$a" }; val pos = names.firstOrNull { it == "$stem-$b" || it == "${stem}_$b" }
                        if (neg != null && pos != null) {
                            used.add(neg); used.add(pos)
                            sliders.put(JSONObject().put("id", "$packId/${gdir.name}/$stem").put("label", stem.replace('-', ' ').replace('_', ' '))
                                .put("neg", JSONArray().put("$packId/${gdir.name}/$neg")).put("pos", JSONArray().put("$packId/${gdir.name}/$pos")))
                            done = true; break
                        }
                    }
                    if (!done) { used.add(n); sliders.put(JSONObject().put("id", "$packId/${gdir.name}/$n").put("label", n.replace('-', ' ').replace('_', ' '))
                        .put("neg", JSONArray()).put("pos", JSONArray().put("$packId/${gdir.name}/$n"))) }
                }
                groups.put(JSONObject().put("group", "$title: ${gdir.name}").put("sliders", sliders).put("adult", false))
                counts["shape sliders"] = (counts["shape sliders"] ?: 0) + sliders.length()
            }
        }
        if (blob.size() > 0) { File(out, "targets.bin").writeBytes(blob.toByteArray()); File(out, "targets.json").writeText(index.toString()) }
        val manifest = JSONObject().put("id", packId).put("title", title).put("license", packLicense)
            .put("proxies", proxies).put("skins", skins).put("groups", groups).put("faceShapes", faceShapes).put("credits", credits)
        File(out, "manifest.json").writeText(manifest.toString())
        if (counts.isEmpty()) { out.deleteRecursively(); error("Nothing in this pack that PocketHuman can use yet") }
        return Result(packId, counts, skipped)
    }

    private fun proxyBin(info: Mhclo, n: Int, rmap: IntArray, ruv: FloatArray, tris: IntArray): ByteArray {
        val bb = ByteBuffer.allocate(8 + n * 6 + n * 12 + n * 12 + 36 + 4 + rmap.size * 2 + ruv.size * 4 + 4 + tris.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("PHP1".toByteArray()).putInt(n)
        info.refs.forEach { bb.putShort(it.toShort()) }; info.w.forEach { bb.putFloat(it) }; info.off.forEach { bb.putFloat(it) }
        for (k in 0..2) bb.putInt(info.scaleA[k]); for (k in 0..2) bb.putFloat(info.scaleRef[k]); for (k in 0..2) bb.putInt(info.scaleB[k])
        bb.putInt(rmap.size); rmap.forEach { bb.putShort(it.toShort()) }; ruv.forEach { bb.putFloat(it) }
        bb.putInt(tris.size / 3); tris.forEach { bb.putInt(it) }
        return bb.array()
    }

    private fun readTarget(f: File): Pair<IntArray, FloatArray> {
        val text = (if (f.name.endsWith(".gz")) GZIPInputStream(f.inputStream()) else f.inputStream()).bufferedReader().readText()
        val idx = ArrayList<Int>(); val d = ArrayList<Float>()
        for (line in text.lineSequence()) {
            if (line.startsWith("#")) continue
            val p = line.trim().split(Regex("\\s+")); if (p.size != 4) continue
            val i = p[0].toIntOrNull() ?: continue
            val x = p[1].toFloat(); val y = p[2].toFloat(); val z = p[3].toFloat()
            if (Math.abs(x) + Math.abs(y) + Math.abs(z) < 1e-5f || i >= 65535) continue
            idx.add(i); d.add(x); d.add(y); d.add(z)
        }
        return idx.toIntArray() to d.toFloatArray()
    }
}

object Half {
    fun toHalf(f: Float): Short {
        val b = java.lang.Float.floatToIntBits(f)
        val sign = (b ushr 16) and 0x8000
        var e = ((b ushr 23) and 0xff) - 127 + 15
        var m = b and 0x7fffff
        if (e <= 0) { if (e < -10) return sign.toShort(); m = (m or 0x800000) shr (1 - e); return (sign or ((m + 0x1000) shr 13)).toShort() }
        if (e >= 31) return (sign or 0x7c00).toShort()
        val h = sign or (e shl 10) or ((m + 0x1000) shr 13)
        return h.toShort()
    }
}

/** .mhclo proxy fitting file (and .mhmat material reader). */
class Mhclo {
    var obj: String? = null; var material: String? = null; var zdepth = 50
    val scaleA = IntArray(3); val scaleB = IntArray(3); val scaleRef = FloatArray(3)
    var refs = IntArray(0); var w = FloatArray(0); var off = FloatArray(0); var delete = IntArray(0)
    companion object {
        fun read(f: File): Mhclo {
            val m = Mhclo(); val r = ArrayList<Int>(); val ww = ArrayList<Float>(); val oo = ArrayList<Float>(); val del = ArrayList<Int>()
            var status = ""
            for (raw in f.readLines()) {
                val t = raw.trim().split(Regex("\\s+")); if (t.isEmpty() || t[0].isEmpty() || t[0].startsWith("#")) continue
                if (status == "v") {
                    if (t[0].all { it.isDigit() }) {
                        if (t.size == 1) { val v = t[0].toInt(); r.add(v); r.add(v); r.add(v); ww.add(1f); ww.add(0f); ww.add(0f); oo.add(0f); oo.add(0f); oo.add(0f) }
                        else { for (k in 0..2) r.add(t[k].toInt()); for (k in 3..5) ww.add(t[k].toFloat()); for (k in 6..8) oo.add(t[k].toFloat()) }
                        continue
                    }
                    if (r.isNotEmpty()) status = ""
                }
                if (status == "d") {
                    if (t[0].all { it.isDigit() } || t[0] == "-") {
                        var seq = false; var prev = 0
                        for (x in t) { if (x == "-") { seq = true; continue }; val v = x.toInt(); if (seq) { for (q in prev + 1..v) del.add(q); seq = false } else del.add(v); prev = v }
                        continue
                    }
                    status = ""
                }
                when (t[0]) {
                    "obj_file" -> m.obj = t.getOrNull(1)
                    "material" -> m.material = t.getOrNull(1)
                    "z_depth" -> m.zdepth = t.getOrNull(1)?.toIntOrNull() ?: 50
                    "x_scale", "y_scale", "z_scale" -> { val k = "xyz".indexOf(t[0][0]); m.scaleA[k] = t[1].toInt(); m.scaleB[k] = t[2].toInt(); m.scaleRef[k] = t[3].toFloat() }
                    "verts" -> status = "v"
                    "delete_verts" -> status = "d"
                }
            }
            m.refs = r.toIntArray(); m.w = ww.toFloatArray(); m.off = oo.toFloatArray(); m.delete = del.distinct().sorted().toIntArray()
            return m
        }
        fun mhmat(f: File): Map<String, String> = f.readLines().mapNotNull { l ->
            val p = l.trim().split(Regex("\\s+"), limit = 2); if (p.size == 2 && !p[0].startsWith("#")) p[0] to p[1] else null }.toMap()
    }
}

object ObjReader {
    /** positions, texcoords, faces as list of (v, vt) corner arrays */
    fun read(f: File): Triple<FloatArray, FloatArray, List<IntArray>> {
        var v = FloatArray(1 shl 16); var nv = 0; var vt = FloatArray(1 shl 16); var nt = 0
        val faces = ArrayList<IntArray>()
        fun pf(s: String, a: Int, b: Int) = java.lang.Float.parseFloat(s.substring(a, b))
        f.bufferedReader(Charsets.ISO_8859_1, 1 shl 16).forEachLine { l ->
            if (l.length < 3) return@forEachLine
            if (l[0] == 'v' && (l[1] == ' ' || l[1] == 't')) {
                val isT = l[1] == 't'; var i = if (isT) 3 else 2; var k = 0; val want = if (isT) 2 else 3
                if (isT) { if (nt + 2 > vt.size) vt = vt.copyOf(vt.size * 2) } else { if (nv + 3 > v.size) v = v.copyOf(v.size * 2) }
                while (k < want && i < l.length) {
                    while (i < l.length && l[i] == ' ') i++
                    var j = i; while (j < l.length && l[j] != ' ') j++
                    if (j > i) { val x = pf(l, i, j); if (isT) vt[nt++] = x else v[nv++] = x; k++ }
                    i = j
                }
            } else if (l[0] == 'f' && l[1] == ' ') {
                val c = l.substring(2).trim().split(' ').filter { it.isNotEmpty() }
                val a = IntArray(c.size * 2)
                c.forEachIndexed { i, s -> val sl = s.indexOf('/'); if (sl < 0) { a[i * 2] = s.toInt() - 1; a[i * 2 + 1] = -1 } else {
                    a[i * 2] = s.substring(0, sl).toInt() - 1; val s2 = s.indexOf('/', sl + 1); val t = if (s2 < 0) s.substring(sl + 1) else s.substring(sl + 1, s2)
                    a[i * 2 + 1] = if (t.isEmpty()) -1 else t.toInt() - 1 } }
                faces.add(a)
            }
        }
        return Triple(v.copyOf(nv), vt.copyOf(nt), faces)
    }
    /** render vertices (unique v/vt pairs) -> (map to obj vertex, uv with top-left origin, triangles) */
    fun unweld(nv: Int, vt: FloatArray, faces: List<IntArray>): Triple<IntArray, FloatArray, IntArray> {
        val key = HashMap<Long, Int>(); val rmap = ArrayList<Int>(); val uv = ArrayList<Float>(); val tris = ArrayList<Int>()
        for (f in faces) {
            val ids = IntArray(f.size / 2) { i ->
                val vi = f[i * 2]; val ti = f[i * 2 + 1]
                key.getOrPut(vi.toLong() * 1_000_003L + ti) {
                    rmap.add(vi)
                    if (ti >= 0 && ti * 2 + 1 < vt.size) { uv.add(vt[ti * 2]); uv.add(1f - vt[ti * 2 + 1]) } else { uv.add(0f); uv.add(0f) }
                    rmap.size - 1
                }
            }
            for (j in 1 until ids.size - 1) { tris.add(ids[0]); tris.add(ids[j]); tris.add(ids[j + 1]) }
        }
        return Triple(rmap.toIntArray(), uv.toFloatArray(), tris.toIntArray())
    }
}
