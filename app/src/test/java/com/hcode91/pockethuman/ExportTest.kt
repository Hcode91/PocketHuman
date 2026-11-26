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
        raw = MeshIO.simplify(raw, 60000); println("simplified to ${raw.tris.size / 3} tris")
        val rgb = File("/tmp/tex1024.rgb").readBytes()
        val colors = ColorSampler { u, v -> val x = (u.coerceIn(0f, 0.9999f) * 1024).toInt(); val y = (v.coerceIn(0f, 0.9999f) * 1024).toInt(); val o = (y * 1024 + x) * 3
            ((rgb[o].toInt() and 0xff) shl 16) or ((rgb[o + 1].toInt() and 0xff) shl 8) or (rgb[o + 2].toInt() and 0xff) }
        val d = OutfitDraft("test1", "Akari uniform", raw)
        val body = BodyQuery(m)
        d.autoPlace(body); println("auto scale ${d.autoScale} shift ${d.autoShift.toList()}")
        d.p["skinCut"] = 0.6f
        val tp = System.currentTimeMillis(); val pf = d.previewDraft().compute(m, body, colors); println("preview compute ms ${System.currentTimeMillis() - tp} (${pf.keepTris.size / 3} tris)")
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
