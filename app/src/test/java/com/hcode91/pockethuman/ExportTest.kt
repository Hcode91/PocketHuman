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
