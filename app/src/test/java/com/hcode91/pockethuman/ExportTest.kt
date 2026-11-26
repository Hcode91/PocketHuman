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
