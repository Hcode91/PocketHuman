package com.hcode91.pockethuman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExportTest {
    private val dir = File("src/main/assets/ph")
    private val lib by lazy { Library(AssetSource { File(dir, it).readBytes() }) }

    @Test fun exportsDefaultAndCustom() {
        val m = HumanModel(lib)
        m.rebuild()
        val h = m.heightMetres(); println("default height $h")
        assertTrue(h in 1.4f..2.0f)
        File(System.getProperty("java.io.tmpdir"), "ph_default.glb").outputStream().use { GlbExporter.write(m, it) }
        m.state.macro["gender"] = 1f; m.state.macro["muscle"] = 0.9f; m.state.macro["age"] = 0.8f
        m.state.setRace("asian", 0.8f); m.state.details["nose/nose-scale-horiz-decr-incr"] = 0.8f
        m.state.hair = "hair_short02"; m.state.clothes.add("clothes_male_casualsuit01"); m.state.clothes.add("clothes_shoes02"); m.state.expression["mh:mouth-corner-puller"] = 0.8f
        m.rebuild(); println("custom height ${m.heightMetres()} age ${m.ageYears()}")
        File(System.getProperty("java.io.tmpdir"), "ph_custom.glb").outputStream().use { GlbExporter.write(m, it) }
    }
}

class ImportTest {
    @Test fun importsPacks() {
        val tmp = java.nio.file.Files.createTempDirectory("ph").toFile()
        val copy = TextureSink { src, dir, base, _ -> dir.mkdirs(); val n = base + "." + src.extension.lowercase(); src.copyTo(File(dir, n), true); n }
        val packs: List<File> = (File("/home/claude/ph_test").listFiles() ?: arrayOf<File>()).filter { it.name.endsWith(".zip") }.sortedBy { it.name }
        if (packs.isEmpty()) { println("no local packs to test"); return }
        for (z in packs) {
            val id = z.nameWithoutExtension.replace(Regex("(_cc0|_ccby|_cc-by)$"), "")
            val r = runCatching { PackImporter(tmp, copy).import(z.inputStream(), id, id, if (z.name.contains("cc")) "CC-BY" else "CC0") }
            println("pack $id -> " + r.fold({ "${it.counts} skipped ${it.skipped.size}" }, { "FAILED ${it.message}" }))
        }
        val lib = Library(AssetSource { p -> if (p.startsWith("imported/")) File(tmp, p).readBytes() else File("src/main/assets/ph", p).readBytes() }, tmp)
        println("installed: " + lib.installed.joinToString { "${it.title}: ${it.counts}" })
        val m = HumanModel(lib)
        lib.proxies.filter { it.id.startsWith("glasses02") || it.id.startsWith("underwear04") || it.id.startsWith("bodyparts05") }.take(3).forEach { m.state.clothes.add(it.id) }
        lib.defs.groups.firstOrNull { it.name.startsWith("nose01") }?.sliders?.take(2)?.forEach { m.state.details[it.id] = 0.8f }
        Ethnicities.apply(Ethnicities.presets.first { it.name == "South Asian" }, m)
        m.state.macro["gender"] = 1f
        m.rebuild()
        println("credits: " + Credits.forCharacter(m).joinToString { "${it.asset}/${it.author}/${it.license}" })
        File(System.getProperty("java.io.tmpdir"), "ph_packs.glb").outputStream().use { GlbExporter.write(m, it) }
    }
}

class OutfitTest {
    @Test fun importsMeshyOutfit() {
        val src = File("/home/claude/meshy_in/baked.glb"); if (!src.exists()) { println("no test model"); return }
        val tmp = java.nio.file.Files.createTempDirectory("pho").toFile()
        val copy = TextureSink { s, dir, base, _ -> dir.mkdirs(); val n = base + "." + s.extension.lowercase(); s.copyTo(File(dir, n), true); n }
        val srcOf = AssetSource { p -> if (p.startsWith("imported/")) File(tmp, p).readBytes() else File("src/main/assets/ph", p).readBytes() }
        var lib = Library(srcOf, tmp)
        val m = HumanModel(lib); m.rebuild()
        var raw = MeshIO.load(src); println("loaded ${raw.nv} verts ${raw.tris.size / 3} tris tex ${raw.texture?.size}")
        raw = MeshIO.simplify(raw, 150000); println("simplified to ${raw.tris.size / 3} tris")
        val rgb = File("/tmp/tex1024.rgb").readBytes()
        val colors = ColorSampler { u, v -> val x = (u.coerceIn(0f, 0.9999f) * 1024).toInt(); val y = (v.coerceIn(0f, 0.9999f) * 1024).toInt(); val o = (y * 1024 + x) * 3
            ((rgb[o].toInt() and 0xff) shl 16) or ((rgb[o + 1].toInt() and 0xff) shl 8) or (rgb[o + 2].toInt() and 0xff) }
        val d = OutfitDraft("test1", "Akari uniform", raw)
        val body = BodyQuery(m)
        d.autoPlace(body); println("auto scale ${d.autoScale} shift ${d.autoShift.toList()}")
        d.p["skinCut"] = 0.6f
        val tp = System.currentTimeMillis(); val pf = d.computePreview(m, body, colors); println("preview compute ms ${System.currentTimeMillis() - tp} (${pf.keepTris.size / 3} tris)")
        val t0 = System.currentTimeMillis(); val f = d.compute(m, body, colors); println("compute ms ${System.currentTimeMillis() - t0}, kept tris ${f.keepTris.size / 3}, hidden body ${f.hiddenBody.count { it }}")
        var inside = 0; var checked = 0; var minD = 0f
        for (i in 0 until f.pos.size / 3) { val h = body.nearest(f.pos[i * 3], f.pos[i * 3 + 1], f.pos[i * 3 + 2]) ?: continue; if (h.dist > 1.5f) continue
            val nn = body.normalAt(h); val dd = (f.pos[i * 3] - h.x) * nn[0] + (f.pos[i * 3 + 1] - h.y) * nn[1] + (f.pos[i * 3 + 2] - h.z) * nn[2]; checked++; if (dd < 0) { inside++; minD = minOf(minD, dd) } }
        println("after fit: inside $inside of $checked, deepest $minD")
        d.bake(m, body, f, tmp, copy)
        lib = Library(srcOf, tmp)
        val m2 = HumanModel(lib); m2.state.load(m.state.toJson()); m2.state.hair = null; m2.state.clothes.add("my_test1"); m2.rebuild()
        run { val pm = lib.proxy("my_test1"); val fit = pm.fit(m2.verts)
            val used = f.keepTris.distinct()
            var mx = 0f; for ((j, i) in used.withIndex()) for (k in 0..2) mx = maxOf(mx, Math.abs(fit[j * 3 + k] - f.pos[i * 3 + k]))
            println("bake round-trip max error (dm): $mx, verts ${used.size} vs ${pm.n}") }
        File(System.getProperty("java.io.tmpdir"), "ph_outfit.glb").outputStream().use { GlbExporter.write(m2, it) }
        m2.state.macro["weight"] = 0.9f; m2.state.macro["height"] = 0.9f; m2.rebuild()
        File(System.getProperty("java.io.tmpdir"), "ph_outfit_heavy.glb").outputStream().use { GlbExporter.write(m2, it) }
        val back = OutfitDraft.loadSaved(tmp, "test1"); println("reloaded for editing: ${back?.name} armL=${back?.p?.get("armL")}")
    }
}

class CharacterTest {
    fun png(argb: IntArray, w: Int, h: Int): ByteArray {
        val raw = java.io.ByteArrayOutputStream()
        for (y in 0 until h) { raw.write(0); for (x in 0 until w) { val c = argb[y * w + x]; raw.write((c shr 16) and 0xff); raw.write((c shr 8) and 0xff); raw.write(c and 0xff) } }
        val z = java.io.ByteArrayOutputStream(); java.util.zip.DeflaterOutputStream(z).use { it.write(raw.toByteArray()) }
        val out = java.io.ByteArrayOutputStream()
        fun chunk(t: String, d: ByteArray) { val crc = java.util.zip.CRC32(); crc.update(t.toByteArray()); crc.update(d)
            out.write(java.nio.ByteBuffer.allocate(4).putInt(d.size).array()); out.write(t.toByteArray()); out.write(d); out.write(java.nio.ByteBuffer.allocate(4).putInt(crc.value.toInt()).array()) }
        out.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        chunk("IHDR", java.nio.ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(2).put(0).put(0).put(0).array())
        chunk("IDAT", z.toByteArray()); chunk("IEND", ByteArray(0)); return out.toByteArray()
    }
    @Test fun makesCharacterFromModel() {
        val src = File("/home/claude/meshy_in/baked.glb"); if (!src.exists()) { println("no test model"); return }
        val tmp = java.nio.file.Files.createTempDirectory("phc").toFile()
        val copy = TextureSink { s, dir, base, _ -> dir.mkdirs(); val n = base + "." + s.extension.lowercase(); s.copyTo(File(dir, n), true); n }
        val writer = ImageWriter { a, w, h, dir, base -> dir.mkdirs(); File(dir, "$base.png").writeBytes(png(a, w, h)); "$base.png" }
        val srcOf = AssetSource { p -> if (p.startsWith("imported/")) File(tmp, p).readBytes() else File("src/main/assets/ph", p).readBytes() }
        var lib = Library(srcOf, tmp); var m = HumanModel(lib); m.state.hair = null; m.rebuild()
        val raw = MeshIO.simplify(MeshIO.load(src), 60000)
        val rgb = File("/tmp/tex1024.rgb").readBytes()
        val colors = ColorSampler { u, v -> val x = (u.coerceIn(0f, 0.9999f) * 1024).toInt(); val y = (v.coerceIn(0f, 0.9999f) * 1024).toInt(); val o = (y * 1024 + x) * 3
            ((rgb[o].toInt() and 0xff) shl 16) or ((rgb[o + 1].toInt() and 0xff) shl 8) or (rgb[o + 2].toInt() and 0xff) }
        val baseSkin = ColorSampler { _, _ -> 0xC89078 }
        val d = OutfitDraft("t", "Akari", raw); d.autoPlace(BodyQuery(m))
        val t0 = System.currentTimeMillis()
        val aligned = d.alignedOnly(m, BodyQuery(m))
        val skin = CharacterImport.skinMask(raw, colors); println("skin verts ${skin.count { it }} of ${raw.nv}")
        val delta = CharacterImport.shapeDelta(m, aligned.pos, raw, skin) {}
        var mx = 0f; for (x in delta) mx = maxOf(mx, Math.abs(x)); println("shape: max offset ${mx * 10} cm, ms ${System.currentTimeMillis() - t0}")
        val sid = CharacterImport.saveShape(tmp, "Akari", delta)
        var keep = m.state.toJson(); lib = Library(srcOf, tmp); m = HumanModel(lib); m.state.load(keep); m.state.details[sid] = 1f; m.rebuild()
        val t1 = System.currentTimeMillis()
        val path = CharacterImport.projectSkin(m, aligned.pos, raw, skin, colors, baseSkin, 1024, tmp, "Akari", writer) {}
        println("skin painted in ${System.currentTimeMillis() - t1} ms -> $path")
        keep = m.state.toJson(); lib = Library(srcOf, tmp); m = HumanModel(lib); m.state.load(keep)
        m.state.skin = lib.skins.indexOfFirst { it.texture == path }; m.state.autoSkin = false; m.rebuild()
        val o = OutfitDraft("c1", "Akari outfit", raw, LinkedHashMap(d.p)).also { it.autoScale = d.autoScale; it.autoShift = d.autoShift.copyOf(); it.p["skinCut"] = 0.6f }
        val b1 = BodyQuery(m); o.bake(m, b1, o.compute(m, b1, colors), tmp, copy)
        keep = m.state.toJson(); lib = Library(srcOf, tmp); m = HumanModel(lib); m.state.load(keep); m.state.clothes.add("my_c1"); m.rebuild()
        File(System.getProperty("java.io.tmpdir"), "ph_character.glb").outputStream().use { GlbExporter.write(m, it) }
        m.state.clothes.clear(); m.rebuild()
        File(System.getProperty("java.io.tmpdir"), "ph_character_bare.glb").outputStream().use { GlbExporter.write(m, it) }
        val b2 = BodyQuery(m); val f2 = d.alignedOnly(m, b2); val (part, tex) = AsIsExport.part(m, b2, d, f2)
        File(System.getProperty("java.io.tmpdir"), "ph_asis.glb").outputStream().use { GlbExporter.write(m, it, listOf(part), tex) }
        println("character done")
    }
}

class TankTopTest {
    @Test fun fitsTankTop() {
        val src = File("/mnt/user-data/uploads/Meshy_AI_Black_Racerback_Tank_Top_20261026_texture.obj"); if (!src.exists()) { println("no tank top"); return }
        val tmp = java.nio.file.Files.createTempDirectory("pht").toFile()
        val copy = TextureSink { s, dir, base, _ -> dir.mkdirs(); val n = base + "." + s.extension.lowercase(); s.copyTo(File(dir, n), true); n }
        val srcOf = AssetSource { p -> if (p.startsWith("imported/")) File(tmp, p).readBytes() else File("src/main/assets/ph", p).readBytes() }
        var lib = Library(srcOf, tmp); var m = HumanModel(lib); m.state.hair = null; m.rebuild()
        val t0 = System.currentTimeMillis()
        var raw = MeshIO.load(src, File("/mnt/user-data/uploads/d015f3f7-image.webp"))
        println("tank top loaded ${raw.nv} verts ${raw.tris.size / 3} tris in ${System.currentTimeMillis() - t0} ms")
        val t1 = System.currentTimeMillis(); raw = MeshIO.simplify(raw, 150000); println("simplified to ${raw.tris.size / 3} in ${System.currentTimeMillis() - t1} ms")
        val d = OutfitDraft("tank", "Tank top", raw); d.p["kind"] = 1f; val body = BodyQuery(m)
        val t2 = System.currentTimeMillis(); d.autoFit(body); println("auto-fit ${System.currentTimeMillis() - t2} ms scale ${d.autoScale} shift ${d.autoShift.toList()}")
        // a test brush stroke: shrinkwrap at the front
        d.strokes.add(OutfitDraft.Stroke(OutfitDraft.SHRINK, floatArrayOf(0f, 3f, 1.2f), 1.2f, FloatArray(3), 0.5f))
        val tq = System.currentTimeMillis(); val pv = d.computePreview(m, body, null); val tq2 = System.currentTimeMillis()
        val f = d.compute(m, body, null)
        run { var e = 0.0; var mx = 0f; for (i in f.pos.indices) { val x = Math.abs(f.pos[i] - pv.pos[i]); e += x; mx = maxOf(mx, x) }
            println("preview ${tq2 - tq} ms, full ${System.currentTimeMillis() - tq2} ms; preview vs full mean ${"%.4f".format(e / f.pos.size)} dm, max $mx dm") }
        fun inside(pos: FloatArray, q: BodyQuery): Int { var c = 0; for (i in 0 until pos.size / 3) { val h = q.nearest(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]) ?: continue; if (h.dist > 1.5f) continue
            val nn = q.normalAt(h); if ((pos[i * 3] - h.x) * nn[0] + (pos[i * 3 + 1] - h.y) * nn[1] + (pos[i * 3 + 2] - h.z) * nn[2] < 0) c++ }; return c }
        println("tank inside after compute: ${inside(f.pos, body)} of ${f.pos.size / 3}")
        d.bake(m, body, f, tmp, copy)
        lib = Library(srcOf, tmp); val m2 = HumanModel(lib); m2.state.load(m.state.toJson()); m2.state.clothes.add("my_tank"); m2.rebuild()
        val fp = m2.lib.proxy("my_tank").fit(m2.verts)
        println("tank inside after bake: ${inside(fp, BodyQuery(m2))} of ${fp.size / 3}")
        File(System.getProperty("java.io.tmpdir"), "ph_tank.glb").outputStream().use { GlbExporter.write(m2, it) }
        File(System.getProperty("java.io.tmpdir"), "ph_tank_only.glb").outputStream().use { GlbExporter.write(m2, it, PartBuilder.build(m2, true).filter { it.name.startsWith("my_") }) }
    }
}

class SimplifyTest {
    private fun area(m: RawMesh): Double { var a = 0.0; var t = 0; val p = m.pos
        while (t < m.tris.size) { val i = m.tris[t] * 3; val j = m.tris[t + 1] * 3; val k = m.tris[t + 2] * 3
            val ux = p[j] - p[i]; val uy = p[j + 1] - p[i + 1]; val uz = p[j + 2] - p[i + 2]; val vx = p[k] - p[i]; val vy = p[k + 1] - p[i + 1]; val vz = p[k + 2] - p[i + 2]
            val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx; a += 0.5 * Math.sqrt((cx * cx + cy * cy + cz * cz).toDouble()); t += 3 }; return a }
    @Test fun damage() {
        for (f in listOf("/mnt/user-data/uploads/Meshy_AI_Black_Racerback_Tank_Top_20261026_texture.obj", "/home/claude/meshy_in/baked.glb")) {
            val src = File(f); if (!src.exists()) continue
            val raw = MeshIO.load(src, null); val a0 = area(raw)
            for (t in listOf(150000, 60000, 15000)) { val s = MeshIO.simplify(raw, t); println("${src.name} ${raw.tris.size / 3} -> ${s.tris.size / 3}: area kept ${"%.2f".format(area(s) / a0)}") }
        }
    }
}

class DeleteCharacterTest {
    @Test fun deletes() {
        val tmp = java.nio.file.Files.createTempDirectory("phd").toFile()
        val srcOf = AssetSource { p -> if (p.startsWith("imported/")) File(tmp, p).readBytes() else File("src/main/assets/ph", p).readBytes() }
        var m = HumanModel(Library(srcOf, tmp)); m.rebuild()
        val d = FloatArray(m.lib.base.nV * 3); d[1] = 0.1f
        val a = CharacterImport.saveShape(tmp, "Ann", d); val b = CharacterImport.saveShape(tmp, "Bea", d)
        println("chars ${CharacterImport.characters(tmp)}")
        CharacterImport.deleteCharacter(tmp, a, "Ann")
        val left = CharacterImport.characters(tmp); println("after delete $left")
        org.junit.Assert.assertEquals(listOf(b to "Bea"), left)
        m = HumanModel(Library(srcOf, tmp)); m.state.details[b] = 1f; m.rebuild()   // remaining shape still loads
    }
}

class GenitalsTest {
    @Test fun shapes() {
        val tmp = java.nio.file.Files.createTempDirectory("phg").toFile()
        val srcOf = AssetSource { p -> File("src/main/assets/ph", p).readBytes() }
        val lib = Library(srcOf, tmp)
        for (g in listOf(0f, 1f)) {
            val m = HumanModel(lib); m.state.macro["gender"] = g; m.state.adult = true; m.rebuild(); val a = m.verts.copyOf()
            for (id in listOf("genitals/penis-length-decr-incr", "genitals/penis-testicles-decr-incr")) {
                m.state.details[id] = 1f; m.rebuild(); var mx = 0f; for (i in a.indices) mx = maxOf(mx, Math.abs(m.verts[i] - a[i])); println("gender $g $id max move ${mx} dm"); m.state.details.remove(id) }
            m.state.hair = null; m.rebuild()
            File(System.getProperty("java.io.tmpdir"), "ph_gen$g.glb").outputStream().use { GlbExporter.write(m, it) }
        }
        println("has penis target: " + lib.targets.has("genitals/penis-length-incr") + " " + lib.targets.has("genitals/penis-length-decr-incr"))
    }
}

class GenitalsClothesTest {
    @Test fun hiddenUnderTrousers() {
        val tmp = java.nio.file.Files.createTempDirectory("phgc").toFile()
        val lib = Library(AssetSource { p -> File("src/main/assets/ph", p).readBytes() }, tmp)
        val m = HumanModel(lib); m.state.macro["gender"] = 1f; m.state.adult = true; m.rebuild()
        org.junit.Assert.assertTrue(PartBuilder.build(m, false).any { it.name == "genitals" })
        val pants = lib.proxies.first { it.id == "clothes_male_casualsuit01" }
        m.state.clothes.add(pants.id); m.rebuild()
        println("with ${pants.id}: genitals shown = ${PartBuilder.build(m, false).any { it.name == "genitals" }}")
        org.junit.Assert.assertFalse(PartBuilder.build(m, false).any { it.name == "genitals" })
        m.state.adult = false; m.state.clothes.clear(); m.rebuild()
        org.junit.Assert.assertFalse(PartBuilder.build(m, false).any { it.name == "genitals" })
    }
}


class GenitalDetailTest {
    @Test fun paintsOntoDarkSkin() {
        val rawF = File("/tmp/afr.rgb"); if (!rawF.exists()) return
        val tmp = java.nio.file.Files.createTempDirectory("phgd").toFile()
        val srcOf = AssetSource { p -> if (p.startsWith("imported/")) File(tmp, p).readBytes() else File("src/main/assets/ph", p).readBytes() }
        val lib = Library(srcOf, tmp)
        val dec = ImageDecoder { _ -> val b = rawF.readBytes(); Triple(2048, 2048, IntArray(2048 * 2048) { i -> (0xff shl 24) or ((b[i * 3].toInt() and 255) shl 16) or ((b[i * 3 + 1].toInt() and 255) shl 8) or (b[i * 3 + 2].toInt() and 255) }) }
        val ct = CharacterTest()
        val writer = ImageWriter { a, w, h, dir, base -> dir.mkdirs(); File(dir, "$base.png").writeBytes(ct.png(a, w, h)); "$base.png" }
        HumanModel.detailProvider = { l, tex -> GenitalDetail.skinWithDetail(l, tex, tmp, dec, writer) }
        val m = HumanModel(lib); m.state.macro["gender"] = 0f; m.state.adult = true; m.state.hair = null
        m.state.skin = lib.skins.indexOfFirst { it.texture.contains("young_african_female") }; m.state.autoSkin = false; m.rebuild()
        val t0 = System.currentTimeMillis(); val tex = m.bodyTexture(lib.skins[m.state.skin]); println("detail skin $tex in ${System.currentTimeMillis() - t0} ms")
        org.junit.Assert.assertTrue(tex.startsWith("imported/genital_skins/"))
        File(tmp, tex).copyTo(File("/tmp/ph_gd_skin.png"), true)
        File(System.getProperty("java.io.tmpdir"), "ph_gd.glb").outputStream().use { GlbExporter.write(m, it) }
        m.state.adult = false; org.junit.Assert.assertEquals(lib.skins[m.state.skin].texture, m.bodyTexture(lib.skins[m.state.skin]))
        HumanModel.detailProvider = null
    }
}

class CommunityTest {
    @Test fun installsAssets() {
        val tmp = java.nio.file.Files.createTempDirectory("phcm").toFile()
        val copy = TextureSink { s, dir, base, _ -> dir.mkdirs(); val n = base + "." + s.extension.lowercase(); s.copyTo(File(dir, n), true); n }
        val fetch: (String) -> ByteArray = { u -> try { java.net.URL(u).openStream().use { it.readBytes() } } catch (e: Exception) { throw RuntimeException("download failed: $u ($e)") } }
        val all = org.json.JSONArray(File("src/main/assets/ph/community.json").readText())
        val byN = (0 until all.length()).associate { all.getJSONObject(it).let { e -> e.getInt("n") to e } }
        val imp = CommunityImporter(tmp, copy, fetch)
        for (n in listOf(3167, 3223, 2892, 3673, 3605, 3728, 2621)) {
            val e = byN[n] ?: continue
            val r = runCatching { imp.install(e) { } }
            println("community ${e.getString("k")} '${e.getString("t")}' (${e.getString("l")}): " + r.fold({ "ok ${it.counts} skipped ${it.skipped}" }, { "FAILED ${it.message}" }))
        }
        val srcOf = AssetSource { p -> if (p.startsWith("imported/")) File(tmp, p).readBytes() else File("src/main/assets/ph", p).readBytes() }
        val lib = Library(srcOf, tmp)
        println("anatomy proxies: " + lib.proxies.filter { it.kind == "anatomy" }.map { it.id })
        // tests have no image decoder on the classpath: let python/PIL turn the image into raw ARGB
        val dec = ImageDecoder { b -> val inF = File.createTempFile("img", ".bin").apply { writeBytes(b) }; val outF = File.createTempFile("img", ".raw")
            ProcessBuilder("python3", "-c", "import sys,struct;from PIL import Image;im=Image.open(sys.argv[1]).convert('RGBA');w,h=im.size;open(sys.argv[2],'wb').write(struct.pack('<II',w,h)+im.tobytes())", inF.path, outF.path).inheritIO().start().waitFor()
            val r = outF.readBytes(); if (r.size < 8) null else { val bb = java.nio.ByteBuffer.wrap(r).order(java.nio.ByteOrder.LITTLE_ENDIAN); val w = bb.int; val h = bb.int
                Triple(w, h, IntArray(w * h) { i -> val o = 8 + i * 4; ((r[o + 3].toInt() and 255) shl 24) or ((r[o].toInt() and 255) shl 16) or ((r[o + 1].toInt() and 255) shl 8) or (r[o + 2].toInt() and 255) }) } }
        val ct = CharacterTest()
        val writer = ImageWriter { a, w, h, dir, base -> dir.mkdirs(); File(dir, "$base.png").writeBytes(ct.png(a, w, h)); "$base.png" }
        HumanModel.tintProvider = { l, t, sk -> GenitalDetail.tintedToSkin(l, t, sk, tmp, dec, writer) }
        val m = HumanModel(lib); m.state.macro["gender"] = 0f; m.state.adult = true; m.state.hair = lib.proxies.firstOrNull { it.id.startsWith("community_2892") }?.id
        m.state.clothes.add("community_3167_anatomy_realisticlabiamedium"); m.rebuild()
        println("parts: " + PartBuilder.build(m, false).map { it.name })
        File(System.getProperty("java.io.tmpdir"), "ph_comm_f.glb").outputStream().use { GlbExporter.write(m, it) }
        val mm = HumanModel(lib); mm.state.macro["gender"] = 1f; mm.state.adult = true; mm.state.genitals = 2; mm.state.clothes.add("community_3223_anatomy_man_genital"); mm.rebuild()
        println("male parts: " + PartBuilder.build(mm, false).map { it.name })
        File(System.getProperty("java.io.tmpdir"), "ph_comm_m.glb").outputStream().use { GlbExporter.write(mm, it) }
        mm.state.clothes.add("clothes_male_casualsuit01"); mm.rebuild()
        println("male dressed parts: " + PartBuilder.build(mm, false).map { it.name })
        HumanModel.tintProvider = null
    }
}

class UrbanTest {
    @Test fun converts() {
        val src = File("/tmp/urban.glb"); if (!src.exists()) return
        val tmp = java.nio.file.Files.createTempDirectory("phu").toFile()
        val ct = CharacterTest()
        val copy = TextureSink { s, dir, base, _ -> dir.mkdirs(); val n = base + "." + s.extension.lowercase(); s.copyTo(File(dir, n), true); n }
        val writer = ImageWriter { a, w, h, dir, base -> dir.mkdirs(); File(dir, "$base.png").writeBytes(ct.png(a, w, h)); "$base.png" }
        val srcOf = AssetSource { p -> if (p.startsWith("imported/")) File(tmp, p).readBytes() else File("src/main/assets/ph", p).readBytes() }
        var lib = Library(srcOf, tmp); var m = HumanModel(lib); m.state.hair = null; m.rebuild()
        var raw = MeshIO.load(src); if (raw.tris.size / 3 > 200000) raw = MeshIO.simplify(raw, 150000)
        println("urban: ${raw.nv} verts ${raw.tris.size / 3} tris")
        val rgb = File("/tmp/urban_tex.rgb").readBytes(); val S = 2048
        val colors = ColorSampler { u, v -> val x = (u.coerceIn(0f, 0.9999f) * S).toInt(); val y = (v.coerceIn(0f, 0.9999f) * S).toInt(); val o = (y * S + x) * 3
            ((rgb[o].toInt() and 0xff) shl 16) or ((rgb[o + 1].toInt() and 0xff) shl 8) or (rgb[o + 2].toInt() and 0xff) }
        val d = OutfitDraft("u", "Urban", raw); d.autoPlace(BodyQuery(m))
        // what the editor shows before converting
        run { val f = d.computePreview(m, BodyQuery(m), colors)
            val parts = PartBuilder.build(m, true, f.hiddenBody, setOf("my_u")).toMutableList()
            File(System.getProperty("java.io.tmpdir"), "urban_editor.glb").outputStream().use { GlbExporter.write(m, it, parts) } }
        val aligned = d.alignedOnly(m, BodyQuery(m))
        // "Use this model": the model replaces the body
        run { val (part, _) = AsIsExport.part(m, BodyQuery(m), d, aligned)
            ModelStore.save(tmp, "mu", "Urban", part, raw.texture ?: OutfitDraft.GREY_PNG, raw.textureExt)
            HumanModel.modelLoader = { id -> ModelStore.load(tmp, id) }
            val mm = HumanModel(lib); mm.state.customModel = "mu"; mm.rebuild()
            val parts = PartBuilder.build(mm, true); println("use-model parts: " + parts.map { it.name })
            org.junit.Assert.assertEquals(listOf("my_model"), parts.map { it.name })
            File(System.getProperty("java.io.tmpdir"), "urban_model.glb").outputStream().use { GlbExporter.write(mm, it) }
            mm.state.customModel = null; org.junit.Assert.assertTrue(PartBuilder.build(mm, false).any { it.name == "body" })
            HumanModel.modelLoader = null }
        val skin = CharacterImport.skinMask(raw, colors); println("skin verts ${skin.count { it }} of ${raw.nv}")
        val delta = CharacterImport.shapeDelta(m, aligned.pos, raw, skin) {}
        var mx = 0f; for (x in delta) mx = maxOf(mx, Math.abs(x)); println("shape: max offset ${mx * 10} cm")
        val sid = CharacterImport.saveShape(tmp, "Urban", delta)
        var keep = m.state.toJson(); lib = Library(srcOf, tmp); m = HumanModel(lib); m.state.load(keep); m.state.details[sid] = 1f; m.rebuild()
        val base = lib.skins[m.state.skin]
        val baseRgb = ColorSampler { _, _ -> 0xC89078 }
        val path = CharacterImport.projectSkin(m, aligned.pos, raw, skin, colors, baseRgb, 2048, tmp, "Urban", writer) {}
        keep = m.state.toJson(); lib = Library(srcOf, tmp); m = HumanModel(lib); m.state.load(keep)
        m.state.skin = lib.skins.indexOfFirst { it.texture == path }; m.state.autoSkin = false; m.rebuild()
        val o = OutfitDraft("c1", "Urban outfit", raw, LinkedHashMap(d.p)).also { it.autoScale = d.autoScale; it.autoShift = d.autoShift.copyOf(); it.p["skinCut"] = 0.6f }
        val b1 = BodyQuery(m); val fo = o.compute(m, b1, colors); o.bake(m, b1, fo, tmp, copy)
        keep = m.state.toJson(); lib = Library(srcOf, tmp); m = HumanModel(lib); m.state.load(keep); m.state.clothes.add("my_c1"); m.rebuild()
        File(System.getProperty("java.io.tmpdir"), "urban_char.glb").outputStream().use { GlbExporter.write(m, it) }
        println("urban done")
    }
}

class AnimTest {
    @Test fun exportsClips() {
        val tmp = java.nio.file.Files.createTempDirectory("pha").toFile()
        val lib = Library(AssetSource { p -> File("src/main/assets/ph", p).readBytes() }, tmp)
        println("clips: ${lib.anims!!.clips.size}; first: ${lib.anims!!.clips.take(5).map { it.label }}")
        val m = HumanModel(lib); m.state.macro["gender"] = 0f; m.state.clothes.add("clothes_female_casualsuit01"); m.rebuild()
        m.state.anims.clear(); m.state.anims.addAll(listOf("Idle_Loop", "Walk_Loop", "Jog_Fwd_Loop", "Sword_Attack", "Sword_Regular_Combo", "A_missing"))
        val t0 = System.currentTimeMillis()
        File(System.getProperty("java.io.tmpdir"), "ph_anim.glb").outputStream().use { GlbExporter.write(m, it) }
        println("anim export ${System.currentTimeMillis() - t0} ms, ${File(System.getProperty("java.io.tmpdir"), "ph_anim.glb").length() / 1024} KB")
    }
}

class GameCharTest {
    @Test fun exportsStarter() {
        val tmp = java.nio.file.Files.createTempDirectory("phg").toFile()
        val lib = Library(AssetSource { p -> File("src/main/assets/ph", p).readBytes() }, tmp)
        val m = HumanModel(lib); m.state.macro["gender"] = 0f; m.state.macro["height"] = 0.45f
        m.state.setRace("asian", 0.85f); m.state.hair = "hair_long01"; m.state.name = "Akari"
        m.state.clothes.add("clothes_female_casualsuit01"); m.state.skin = m.matchSkin(); m.rebuild()
        File("/tmp/akari_starter.glb").outputStream().use { GlbExporter.write(m, it) }
        println("starter ${File("/tmp/akari_starter.glb").length() / 1024} KB")
    }
}

class GameCastTest {
    @Test fun exportsCast() {
        val tmp = java.nio.file.Files.createTempDirectory("phc2").toFile()
        val lib = Library(AssetSource { p -> File("src/main/assets/ph", p).readBytes() }, tmp)
        fun make(name: String, setup: HumanModel.() -> Unit, anims: List<String>, out: String) {
            val m = HumanModel(lib); m.state.name = name; m.setup(); m.state.skin = m.matchSkin(); m.rebuild()
            File(out).outputStream().use { GlbExporter.write(m, it, anims = anims, faceShapes = false) }
            println("$name ${File(out).length() / 1024} KB")
        }
        make("Ren", { state.macro["gender"] = 1f; state.macro["muscle"] = 0.62f; state.macro["height"] = 0.55f; state.setRace("asian", 0.9f)
            state.hair = "hair_short02"; state.clothes.add("clothes_male_worksuit01"); state.clothes.add("clothes_shoes02") },
            DEFAULT_ANIMS + listOf("LayToIdle", "Idle_Talking_Loop"), "/tmp/ren.glb")
        make("Genzo", { state.macro["gender"] = 1f; state.macro["age"] = 0.88f; state.macro["weight"] = 0.45f; state.setRace("asian", 0.9f)
            state.hair = "hair_short04"; state.clothes.add("clothes_male_casualsuit03"); state.clothes.add("clothes_shoes01") },
            listOf("Idle_Loop", "Idle_Talking_Loop", "Idle_No_Loop", "Yes", "Walk_Loop"), "/tmp/genzo.glb")
    }
}

class GodotCastTest {
    @Test fun exportsCast() {
        val tmp = java.nio.file.Files.createTempDirectory("phgc").toFile()
        val copy = TextureSink { s, dir, base, _ -> dir.mkdirs(); val n = base + "." + s.extension.lowercase(); s.copyTo(File(dir, n), true); n }
        val fetch: (String) -> ByteArray = { u -> java.net.URL(u).openStream().use { it.readBytes() } }
        val all = org.json.JSONArray(File("src/main/assets/ph/community.json").readText())
        val byN = (0 until all.length()).associate { all.getJSONObject(it).let { e -> e.getInt("n") to e } }
        for (n in listOf(63, 896, 3238, 2617)) println("install $n: " + runCatching { CommunityImporter(tmp, copy, fetch).install(byN[n]!!) {} }.fold({ "ok ${it.counts}" }, { "FAIL ${it.message}" }))
        val srcOf = AssetSource { p -> if (p.startsWith("imported/")) File(tmp, p).readBytes() else File("src/main/assets/ph", p).readBytes() }
        val lib = Library(srcOf, tmp)
        val gi = lib.proxies.first { it.id.startsWith("community_63_") }.id; val ninja = lib.proxies.first { it.id.startsWith("community_896_") }.id
        val robe = lib.proxies.first { it.id.startsWith("community_3238_") }.id; val tunic = lib.proxies.first { it.id.startsWith("community_2617_") }.id
        println("gi=$gi ninja=$ninja robe=$robe tunic=$tunic")
        val out = File("/tmp/godot_cast").apply { mkdirs() }
        fun make(file: String, name: String, anims: List<String>, setup: HumanModel.() -> Unit) {
            val m = HumanModel(lib); m.state.name = name; m.setup(); m.state.skin = m.matchSkin(); m.rebuild()
            File(out, file).outputStream().use { GlbExporter.write(m, it, anims = anims, faceShapes = false) }
            println("$name ${File(out, file).length() / 1024} KB")
        }
        val base = DEFAULT_ANIMS
        make("ren.glb", "Ren", base + listOf("LayToIdle", "Idle_Talking_Loop", "Fixing_Kneeling", "Hit_Chest", "Hit_Head", "Hit_Knockback", "Swim_Idle_Loop", "Sword_Idle", "Sitting_Idle_Loop")) {
            state.macro["gender"] = 1f; state.macro["muscle"] = 0.62f; state.macro["height"] = 0.55f; state.setRace("asian", 0.9f)
            state.hair = "hair_short02"; state.clothes.add(tunic); state.clothes.add("clothes_shoes02") }
        make("genzo.glb", "Genzo", listOf("Idle_Loop", "Idle_Talking_Loop", "Idle_No_Loop", "Yes", "Walk_Loop")) {
            state.macro["gender"] = 1f; state.macro["age"] = 0.88f; state.macro["weight"] = 0.45f; state.setRace("asian", 0.9f)
            state.hair = "hair_short04"; state.clothes.add("clothes_male_casualsuit03"); state.clothes.add("clothes_shoes01") }
        make("lord.glb", "Lord Kaze", listOf("Idle_Loop", "Idle_Talking_Loop", "Death01", "Hit_Chest", "Idle_FoldArms_Loop")) {
            state.macro["gender"] = 1f; state.macro["age"] = 0.8f; state.macro["height"] = 0.6f; state.setRace("asian", 0.95f)
            state.hair = "hair_short03"; state.clothes.add(robe); state.clothes.add("clothes_shoes05") }
        make("takeshi.glb", "Takeshi", listOf("Idle_Loop", "Idle_Talking_Loop", "Sword_Idle", "Sword_Attack", "Walk_Loop", "Interact", "Idle_No_Loop", "Idle_FoldArms_Loop", "Yes", "Fixing_Kneeling")) {
            state.macro["gender"] = 1f; state.macro["muscle"] = 0.6f; state.macro["height"] = 0.62f; state.setRace("asian", 0.85f)
            state.hair = "hair_short01"; state.clothes.add(gi); state.clothes.add("clothes_shoes02") }
        make("guard.glb", "Guard", listOf("Idle_Loop", "Walk_Loop", "Sword_Idle", "Idle_Shield_Loop", "Punch_Cross", "Walk_Carry_Loop", "Interact", "Sword_Attack")) {
            state.macro["gender"] = 1f; state.macro["muscle"] = 0.7f; state.macro["weight"] = 0.6f; state.setRace("asian", 0.9f)
            state.hair = null; state.clothes.add(ninja) }
    }
}

class SceneTest {
    @Test fun posesTwoCharacters() {
        val tmp = java.nio.file.Files.createTempDirectory("phsc").toFile()
        val src = AssetSource { p -> if (p.startsWith("imported/")) File(tmp, p).readBytes() else File("src/main/assets/ph", p).readBytes() }
        val lib = Library(src, tmp); val al = lib.anims!!
        val a = HumanModel(lib).apply { state.macro["gender"] = 1f; state.hair = "hair_short02"; state.clothes.add("clothes_shoes02"); state.skin = matchSkin(); rebuild() }
        val b = HumanModel(lib).apply { state.macro["gender"] = 0f; state.expression["mh:mouth-corner-puller"] = 0.8f; state.skin = matchSkin(); rebuild() }
        val ca = SceneChar(a).apply { x = -0.45f; yaw = 30f; label = "A" }
        val cb = SceneChar(b).apply { x = 0.45f; yaw = -30f; label = "B"; pose.clip = "Sitting_Idle_Loop"; pose.time = 0.5f }
        val bones = lib.rig.bones; fun bi(n: String) = bones.indexOfFirst { it.name == "mixamorig:$n" }
        // A: right arm raised forward, head tilted, left hand made into a fist; B sits on a raised block
        ca.pose.offsets[bi("RightArm")] = floatArrayOf(-70f, 0f, 0f); ca.pose.offsets[bi("Head")] = floatArrayOf(10f, 0f, 15f)
        ScenePoser.setCurl(ca, "Left", 1f); ScenePoser.mirror(ca, "Left")
        assertTrue(ScenePoser.curlOf(ca, "Right") > 0.9f)
        // raise both arms (same numbers both sides), bend the left elbow forward, lift the right thigh forward, bend its knee
        ca.pose.offsets[bi("RightArm")] = floatArrayOf(0f, 0f, 40f); ca.pose.offsets[bi("LeftArm")] = floatArrayOf(0f, 0f, 40f)
        ca.pose.offsets[bi("LeftForeArm")] = floatArrayOf(0f, -70f, 0f)
        ca.pose.offsets[bi("RightUpLeg")] = floatArrayOf(-50f, 0f, 0f); ca.pose.offsets[bi("RightLeg")] = floatArrayOf(60f, 0f, 0f)
        ca.yaw = 0f; ca.x = 0f
        cb.y = 0.45f
        val out = File("/tmp/scene_test").apply { mkdirs() }
        val dump = org.json.JSONArray()
        val parts = ScenePoser.parts(ca, al).first + ScenePoser.parts(cb, al).first + SceneProps.box("bench", 4.5f, 2.2f, 0f, 6f, 4.4f, 6f, "x")
        for ((i, p) in parts.withIndex()) {
            assertTrue(p.joints.isEmpty())
            val f = File(out, "p$i.bin"); java.io.DataOutputStream(f.outputStream().buffered()).use { o ->
                o.writeInt(p.pos.size / 3); o.writeInt(p.tris.size); p.pos.forEach { o.writeFloat(it) }; p.uv.forEach { o.writeFloat(it) }; p.tris.forEach { o.writeInt(it) } }
            val tex = File(out, "t$i.png"); runCatching { tex.writeBytes(src.bytes(p.texture)) }
            dump.put(org.json.JSONObject().put("bin", f.path).put("tex", if (tex.exists()) tex.path else "").put("alpha", p.alpha).put("name", p.name))
        }
        File(out, "parts.json").writeText(dump.toString())
        // feet on the floor for the standing character
        val body = ScenePoser.parts(ca, al).first.first { it.name.startsWith("body") }
        var miny = Float.MAX_VALUE; for (i in 0 until body.count) miny = minOf(miny, body.pos[i * 3 + 1])
        println("A lowest point (dm): $miny"); assertTrue(Math.abs(miny) < 0.6f)
        // saving and loading a scene keeps the pose
        ScenePoser.saveScene(File(tmp, "scenes"), "Test", listOf(ca, cb), org.json.JSONObject())
        val j = org.json.JSONObject(File(tmp, "scenes/Test.json").readText())
        val back = SceneChar.fromJson(lib, j.getJSONArray("chars").getJSONObject(1))
        assertEquals("Sitting_Idle_Loop", back.pose.clip); assertEquals(0.45f, back.y, 1e-4f)
    }
}

class SceneCastTest {
    @Test fun exportsSayoAndRen() {
        val tmp = java.nio.file.Files.createTempDirectory("phsc2").toFile()
        val copy = TextureSink { s, dir, base, _ -> dir.mkdirs(); val n = base + "." + s.extension.lowercase(); s.copyTo(File(dir, n), true); n }
        val fetch: (String) -> ByteArray = { u -> java.net.URL(u).openStream().use { it.readBytes() } }
        val all = org.json.JSONArray(File("src/main/assets/ph/community.json").readText())
        val tunicEntry = (0 until all.length()).map { all.getJSONObject(it) }.first { it.getInt("n") == 2617 }
        println("tunic: " + runCatching { CommunityImporter(tmp, copy, fetch).install(tunicEntry) {} }.fold({ "ok" }, { "FAIL ${it.message}" }))
        val src = AssetSource { p -> if (p.startsWith("imported/")) File(tmp, p).readBytes() else File("src/main/assets/ph", p).readBytes() }
        val lib = Library(src, tmp)
        val out = File("/tmp/scene_cast").apply { mkdirs() }
        val sayo = HumanModel(lib).apply { state.name = "Sayo"
            state.macro["gender"] = 0f; state.macro["age"] = 0.56f; state.macro["muscle"] = 0.58f; state.macro["weight"] = 0.45f; state.macro["height"] = 0.55f
            state.setRace("caucasian", 0.8f); state.setRace("asian", 0.15f)
            state.hair = "hair_ponytail01"; state.eyebrows = "eyebrows_eyebrow002"; state.eyeColour = 2
            state.clothes.add("clothes_female_casualsuit01"); state.clothes.add("clothes_shoes03")
            state.skin = lib.skins.indexOfFirst { it.name.contains("young eurasian female") }.takeIf { it >= 0 } ?: matchSkin(); state.autoSkin = false; rebuild() }
        val ren = HumanModel(lib).apply { state.name = "Ren"
            state.macro["gender"] = 1f; state.macro["age"] = 0.58f; state.macro["muscle"] = 0.62f; state.macro["height"] = 0.55f; state.setRace("asian", 0.9f)
            state.hair = "hair_short02"; state.clothes.add("clothes_male_casualsuit03"); state.clothes.add("clothes_shoes02")
            state.skin = matchSkin(); rebuild() }
        for (m in listOf(sayo, ren)) {
            File(out, m.state.name.lowercase() + ".glb").outputStream().use { GlbExporter.write(m, it, anims = listOf("Idle_Loop")) }
            File(out, m.state.name.lowercase() + ".pockethuman.json").writeText(m.state.toJson().toString(2))
            println("${m.state.name}: age ${m.ageYears()} ${File(out, m.state.name.lowercase() + ".glb").length() / 1024} KB skin ${lib.skins[m.state.skin].name}")
        }
    }
}

class StencilPaintTest {
    private fun lookAtProj(eye: FloatArray, at: FloatArray, fovDeg: Float, aspect: Float): FloatArray {
        // column-major proj * view (same as android.opengl.Matrix.perspectiveM + setLookAtM)
        val f = 1f / Math.tan(Math.toRadians(fovDeg / 2.0)).toFloat(); val n = 0.5f; val fa = 600f
        val P = floatArrayOf(f / aspect, 0f, 0f, 0f, 0f, f, 0f, 0f, 0f, 0f, (fa + n) / (n - fa), -1f, 0f, 0f, 2 * fa * n / (n - fa), 0f)
        var zx = eye[0] - at[0]; var zy = eye[1] - at[1]; var zz = eye[2] - at[2]; val zl = Math.sqrt((zx * zx + zy * zy + zz * zz).toDouble()).toFloat(); zx /= zl; zy /= zl; zz /= zl
        var xx = 1f * zz - 0f * zy; var xy = 0f * zx - 0f * zz; var xz = 0f * zy - 1f * zx
        val xl = Math.sqrt((xx * xx + xy * xy + xz * xz).toDouble()).toFloat(); xx /= xl; xy /= xl; xz /= xl
        val yx = zy * xz - zz * xy; val yy = zz * xx - zx * xz; val yz = zx * xy - zy * xx
        val V = floatArrayOf(xx, yx, zx, 0f, xy, yy, zy, 0f, xz, yz, zz, 0f,
            -(xx * eye[0] + xy * eye[1] + xz * eye[2]), -(yx * eye[0] + yy * eye[1] + yz * eye[2]), -(zx * eye[0] + zy * eye[1] + zz * eye[2]), 1f)
        val o = FloatArray(16); for (c in 0..3) for (r in 0..3) { var sum = 0f; for (k in 0..3) sum += P[k * 4 + r] * V[c * 4 + k]; o[c * 4 + r] = sum }
        return o
    }
    @Test fun paintsThroughStencil() {
        if (!File("/tmp/wrap_test").exists()) return
        val tmp = java.nio.file.Files.createTempDirectory("phs").toFile()
        val ct = CharacterTest()
        val srcOf = AssetSource { p -> if (p.startsWith("imported/")) File(tmp, p).readBytes() else File("src/main/assets/ph", p).readBytes() }
        val lib = Library(srcOf, tmp)
        val m = HumanModel(lib).apply { state.macro["gender"] = 0f; rebuild() }
        val body = PartBuilder.build(m, false).first { it.name == "body" }
        val texBytes = lib.am.bytes(body.texture)
        val dec = File.createTempFile("skin", ".png").apply { writeBytes(texBytes) }; val raw = File.createTempFile("skin", ".raw")
        ProcessBuilder("python3", "-c", "import sys,struct;from PIL import Image;im=Image.open(sys.argv[1]).convert('RGBA');w,h=im.size;open(sys.argv[2],'wb').write(struct.pack('<II',w,h)+im.tobytes())", dec.path, raw.path).inheritIO().start().waitFor()
        val rb = raw.readBytes(); val bb = java.nio.ByteBuffer.wrap(rb).order(java.nio.ByteOrder.LITTLE_ENDIAN); val w = bb.int; val h = bb.int
        val base = IntArray(w * h) { i -> val o = 8 + i * 4; ((rb[o + 3].toInt() and 255) shl 24) or ((rb[o].toInt() and 255) shl 16) or ((rb[o + 1].toInt() and 255) shl 8) or (rb[o + 2].toInt() and 255) }
        val p = SkinPainter(w, h, base, body.pos, body.uv, body.tris)
        // the stencil: 200x200, red left half, blue right half, a white stripe across the middle
        val pw = 200; val sp = IntArray(pw * pw) { i -> val x = i % pw; val y = i / pw; if (Math.abs(y - 100) < 8) 0xffffffff.toInt() else if (x < 100) 0xffdd2222.toInt() else 0xff2244dd.toInt() }
        // camera in front of the face; the picture covers the middle of an 800x800 view at 2x
        val top = (0 until body.pos.size / 3).maxOf { body.pos[it * 3 + 1] }; val fy = top - 1.3f
        val eye = floatArrayOf(0f, fy, 7f); val vp = lookAtProj(eye, floatArrayOf(0f, fy, 0f), 35f, 1f)
        val st = SkinPainter.Stencil(pw, pw, sp, floatArrayOf(0.5f, 0f, -100f, 0f, 0.5f, -100f))
        // unproject screen points: invert by ray marching along the camera axis is overkill; use the camera geometry directly
        val tanH = Math.tan(Math.toRadians(17.5)).toFloat()
        fun ray(sx: Float, sy: Float): Pair<FloatArray, FloatArray> { val nx = sx / 400f - 1f; val ny = 1f - sy / 400f; return eye to floatArrayOf(nx * tanH, ny * tanH, -1f) }
        p.strokeBegin()
        var painted = 0
        for (sy in 250..550 step 25) for (sx in 250..550 step 25) {
            val (o, d) = ray(sx.toFloat(), sy.toFloat()); val hit = p.pick(o, d) ?: continue
            val dist = Math.sqrt(((hit[0] - eye[0]) * (hit[0] - eye[0]) + (hit[1] - eye[1]) * (hit[1] - eye[1]) + (hit[2] - eye[2]) * (hit[2] - eye[2])).toDouble()).toFloat()
            val wr = 40f / 400f * tanH * dist
            if (p.dab(hit, wr, sx.toFloat(), sy.toFloat(), 40f, vp, 800, 800, eye, st, false, 1f, 0.3f) != null) painted++
        }
        p.strokeEnd()
        run { val (o, d) = ray(400f, 400f); val hit = p.pick(o, d); println("centre hit ${hit?.joinToString()}")
            val vy = body.pos.filterIndexed { i, _ -> i % 3 == 1 }; println("body y ${vy.minOrNull()}..${vy.maxOrNull()} z ${body.pos.filterIndexed { i, _ -> i % 3 == 2 }.maxOrNull()}") }
        println("stencil: $painted dabs painted")
        assertTrue(painted > 50)
        val dir = File(tmp, "imported/painted/t1").apply { mkdirs() }
        File(dir, "skin.png").writeBytes(ct.png(p.px, w, h))
        m.state.paintSkin = "imported/painted/t1/skin.png"; m.state.paintFrom = body.texture
        File("/tmp/wrap").mkdirs()
        File("/tmp/wrap/stencil.glb").outputStream().use { GlbExporter.write(m, it, only = PartBuilder.build(m, true, withExpression = true), anims = emptyList(), faceShapes = false) }
        // undo puts it back
        p.undo(); var diff = 0; for (i in base.indices) if (p.px[i] != base[i]) diff++
        println("after undo: $diff pixels differ"); assertTrue(diff == 0)
    }
}

class HeadSwapTest {
    @Test fun wrapsHead() {
        val src = File(System.getProperty("headsrc") ?: "/tmp/headsrc.glb"); if (!src.exists() || !File("/tmp/wrap_test").exists()) return
        val tmp = java.nio.file.Files.createTempDirectory("phw").toFile()
        val ct = CharacterTest()
        val dec = ImageDecoder { b -> val inF = File.createTempFile("img", ".bin").apply { writeBytes(b) }; val outF = File.createTempFile("img", ".raw")
            ProcessBuilder("python3", "-c", "import sys,struct;from PIL import Image;im=Image.open(sys.argv[1]).convert('RGBA');w,h=im.size;open(sys.argv[2],'wb').write(struct.pack('<II',w,h)+im.tobytes())", inF.path, outF.path).inheritIO().start().waitFor()
            val r = outF.readBytes(); inF.delete(); outF.delete(); if (r.size < 8) null else { val bb = java.nio.ByteBuffer.wrap(r).order(java.nio.ByteOrder.LITTLE_ENDIAN); val w = bb.int; val h = bb.int
                Triple(w, h, IntArray(w * h) { i -> val o = 8 + i * 4; ((r[o + 3].toInt() and 255) shl 24) or ((r[o].toInt() and 255) shl 16) or ((r[o + 1].toInt() and 255) shl 8) or (r[o + 2].toInt() and 255) }) } }
        val writer = ImageWriter { a, w, h, dir, base -> dir.mkdirs(); File(dir, "$base.png").writeBytes(ct.png(a, w, h)); "$base.png" }
        val srcOf = AssetSource { p -> if (p.startsWith("imported/")) File(tmp, p).readBytes() else File("src/main/assets/ph", p).readBytes() }
        val lib = Library(srcOf, tmp)
        val m = HumanModel(lib).apply { state.macro["gender"] = 0f; state.macro["age"] = 0.56f; state.clothes.add("clothes_female_casualsuit01")
            if (File("/tmp/wrap_light").exists()) state.setRace("caucasian", 1f); rebuild() }
        var raw = MeshIO.load(src); if (raw.tris.size / 3 > 1_500_000) raw = MeshIO.simplify(raw, 1_000_000)
        HeadSwap.debug = { println(it) }
        val t0 = System.currentTimeMillis()
        val prep = HeadSwap.prepare(raw, 0, m)
        val p = HeadSwap.Params().apply { wrap = true; bodyToHead = File("/tmp/wrap_b2h").exists() }
        run {   // the new head as placed on the body, for comparing shapes
            File(tmp, "imported/prev").mkdirs(); File(tmp, "imported/prev/tex.${raw.textureExt}").writeBytes(raw.texture!!)
            val pos = HeadSwap.place(prep, HeadSwap.appRef(m), p)
            val part = Part("head_src", pos, weldedNormals(pos, prep.tris, prep.n), prep.uv, prep.tris, "imported/prev/tex.${raw.textureExt}", false, null, IntArray(0), FloatArray(0), IntArray(0), FloatArray(0), 0)
            File("/tmp/wrap").mkdirs()
            File("/tmp/wrap/app.glb").outputStream().use { GlbExporter.write(m, it, only = PartBuilder.build(m, true, withExpression = true), anims = emptyList(), faceShapes = false) }
            File("/tmp/wrap/source_placed.obj").bufferedWriter().use { o ->
                for (i in 0 until prep.n) o.write("v ${pos[i * 3] * 0.1f} ${pos[i * 3 + 1] * 0.1f} ${pos[i * 3 + 2] * 0.1f}\n")
                for (t in 0 until prep.tris.size / 3) o.write("f ${prep.tris[t * 3] + 1} ${prep.tris[t * 3 + 1] + 1} ${prep.tris[t * 3 + 2] + 1}\n") }
            part.name
        }
        val d = HeadSwap.build(m, prep, p, "w1", "Wrapped", tmp, dec, writer) { println(it) }
        println("wrap built: moved ${d.dispIdx.size}, ${System.currentTimeMillis() - t0} ms")
        HumanModel.headLoader = { id -> HeadStore.load(tmp, id) }
        try {
            m.state.customHead = "w1"; m.forgetHead(); m.rebuild()
            val parts = PartBuilder.build(m, false).map { it.name }; println("parts: $parts")
            assertTrue("head" !in parts); assertTrue("eyes_high-poly" in parts)
            val out = File("/tmp/wrap").apply { mkdirs() }
            for ((nm, ex) in listOf("neutral" to emptyMap(), "smile" to mapOf("mh:mouth-corner-puller" to 0.9f, "mh:eye-left-slit" to 0.3f, "mh:eye-right-slit" to 0.3f),
                                     "blink" to mapOf("mh:eye-left-closure" to 1f, "mh:eye-right-closure" to 1f),
                                     "open" to mapOf("mh:mouth-open" to 0.7f, "mh:eyebrows-left-up" to 1f, "mh:eyebrows-right-up" to 1f))) {
                m.state.expression.clear(); m.state.expression.putAll(ex); m.rebuild()
                File(out, "$nm.glb").outputStream().use { GlbExporter.write(m, it, only = PartBuilder.build(m, true, withExpression = true), anims = emptyList(), faceShapes = false) }
            }
        } finally { HumanModel.headLoader = null }
    }

    @Test fun replacesHead() {
        val src = File(System.getProperty("headsrc") ?: "/tmp/headsrc.glb"); if (!src.exists()) return
        val tmp = java.nio.file.Files.createTempDirectory("phh").toFile()
        val ct = CharacterTest()
        val dec = ImageDecoder { b -> val inF = File.createTempFile("img", ".bin").apply { writeBytes(b) }; val outF = File.createTempFile("img", ".raw")
            ProcessBuilder("python3", "-c", "import sys,struct;from PIL import Image;im=Image.open(sys.argv[1]).convert('RGBA');w,h=im.size;open(sys.argv[2],'wb').write(struct.pack('<II',w,h)+im.tobytes())", inF.path, outF.path).inheritIO().start().waitFor()
            val r = outF.readBytes(); if (r.size < 8) null else { val bb = java.nio.ByteBuffer.wrap(r).order(java.nio.ByteOrder.LITTLE_ENDIAN); val w = bb.int; val h = bb.int
                Triple(w, h, IntArray(w * h) { i -> val o = 8 + i * 4; ((r[o + 3].toInt() and 255) shl 24) or ((r[o].toInt() and 255) shl 16) or ((r[o + 1].toInt() and 255) shl 8) or (r[o + 2].toInt() and 255) }) } }
        val writer = ImageWriter { a, w, h, dir, base -> dir.mkdirs(); File(dir, "$base.png").writeBytes(ct.png(a, w, h)); "$base.png" }
        val srcOf = AssetSource { p -> if (p.startsWith("imported/")) File(tmp, p).readBytes() else File("src/main/assets/ph", p).readBytes() }
        val lib = Library(srcOf, tmp)
        val m = HumanModel(lib).apply { state.macro["gender"] = 0f; state.macro["age"] = 0.56f; state.clothes.add("clothes_female_casualsuit01"); rebuild() }
        var raw = MeshIO.load(src); if (raw.tris.size / 3 > 1_500_000) raw = MeshIO.simplify(raw, 1_000_000)
        println("source: ${raw.nv} verts ${raw.tris.size / 3} tris")
        val t0 = System.currentTimeMillis()
        HeadSwap.debug = { println(it) }
        val prep = HeadSwap.prepare(raw, 0, m)
        println("head: ${prep.n} verts ${prep.tris.size / 3} tris full=${prep.full} prep ${System.currentTimeMillis() - t0} ms")
        val p = HeadSwap.Params()
        run {
            File(tmp, "imported/prev").mkdirs(); File(tmp, "imported/prev/tex.${raw.textureExt}").writeBytes(raw.texture!!)
            val pos = HeadSwap.place(prep, HeadSwap.appRef(m), p)
            val part = Part("head_preview", pos, weldedNormals(pos, prep.tris, prep.n), prep.uv, prep.tris, "imported/prev/tex.${raw.textureExt}", false, null, IntArray(0), FloatArray(0), IntArray(0), FloatArray(0), 0)
            File("/tmp/headswap").mkdirs()
            java.io.DataOutputStream(File("/tmp/headswap/preview.bin").outputStream().buffered()).use { o ->
                o.writeInt(prep.n); o.writeInt(prep.tris.size); pos.forEach { o.writeFloat(it) }; prep.uv.forEach { o.writeFloat(it) }; prep.tris.forEach { o.writeInt(it) } }
            File("/tmp/headswap/preview_tex.${raw.textureExt}").writeBytes(raw.texture!!)
        }
        if (System.getProperty("previewOnly") != null || File("/tmp/preview_only").exists()) return
        val d = HeadSwap.build(m, prep, p, "h1", "Test head", tmp, dec, writer) { println(it) }
        println("built: ${d.n} verts, hidden ${d.hidden.size}, wrapped ${d.dispIdx.size}, total ${System.currentTimeMillis() - t0} ms")
        HumanModel.headLoader = { id -> HeadStore.load(tmp, id) }
        try {
            m.state.customHead = "h1"
            val parts = PartBuilder.build(m, false).map { it.name }; println("parts: $parts")
            assertTrue("head" in parts); assertTrue("hair_ponytail01" !in parts)
            val out = File("/tmp/headswap").apply { mkdirs() }
            run {
                val hd = HeadStore.load(tmp, "h1")!!
                val n0 = HeadSwap.part(m, hd, m.neutral, false).pos
                m.state.expression["mh:eye-left-closure"] = 1f; m.rebuild()
                val n1 = HeadSwap.part(m, hd, m.verts, false).pos
                var mx = 0f; var cnt = 0; for (i in 0 until hd.n) { var d = 0f; for (k in 0..2) d += (n1[i*3+k]-n0[i*3+k]).let { it*it }; d = Math.sqrt(d.toDouble()).toFloat(); mx = maxOf(mx, d); if (d > 0.02f) cnt++ }
                var am = 0f; for (i in 0 until 13380) { var d = 0f; for (k in 0..2) d += (m.verts[i*3+k]-m.neutral[i*3+k]).let { it*it }; am = maxOf(am, Math.sqrt(d.toDouble()).toFloat()) }
                println("blink: head max move $mx, moved verts $cnt; app max move $am")
                m.state.expression.clear(); m.rebuild()
            }
            File(out, "neutral.glb").outputStream().use { GlbExporter.write(m, it, anims = emptyList()) }
            for ((nm, ex) in listOf("smile" to mapOf("mh:mouth-corner-puller" to 0.9f, "mh:eye-left-slit" to 0.3f, "mh:eye-right-slit" to 0.3f),
                                     "blink" to mapOf("mh:eye-left-closure" to 1f, "mh:eye-right-closure" to 1f),
                                     "open" to mapOf("mh:mouth-open" to 0.7f, "mh:eyebrows-left-up" to 1f, "mh:eyebrows-right-up" to 1f))) {
                m.state.expression.clear(); m.state.expression.putAll(ex); m.rebuild()
                File(out, "$nm.glb").outputStream().use { GlbExporter.write(m, it, only = PartBuilder.build(m, true, withExpression = true), anims = emptyList(), faceShapes = false) }
            }
            // without the app's eyes
            m.state.expression.clear(); m.rebuild()
            val p2 = HeadSwap.Params().apply { appEyes = false; bodyToHead = true }
            HeadSwap.build(m, prep, p2, "h2", "Test head 2", tmp, dec, writer)
            m.state.customHead = "h2"
            File(out, "painted.glb").outputStream().use { GlbExporter.write(m, it, anims = emptyList(), faceShapes = false) }
        } finally { HumanModel.headLoader = null }
    }
}
