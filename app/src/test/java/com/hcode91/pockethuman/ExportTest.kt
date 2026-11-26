package com.hcode91.pockethuman

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
