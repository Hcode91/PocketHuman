package com.hcode91.pockethuman

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AgeTest {
    private val lib = Library(AssetSource { File("src/main/assets/ph", it).readBytes() })

    @Test fun agesRoundTripAndExistingSavesKeepTheirAges() {
        val state = HumanState()
        for (years in 1..90) {
            state.macro["age"] = HumanState.ageForYears(years)
            val loaded = HumanState().apply { load(state.toJson()) }
            assertEquals(years.toFloat(), HumanState.yearsForAge(loaded.ageValue), 0.0001f)
            assertEquals(years < 18, loaded.isUnderage)
        }
        state.load(JSONObject("""{"version":1,"macro":{"age":0.75}}"""))
        assertEquals(57.5f, HumanState.yearsForAge(state.ageValue), 0f)
    }

    @Test fun babyAndChildUseRealMorphTargets() {
        val model = HumanModel(lib)
        val heights = (listOf(1, 10, 17, 25)).map { years ->
            model.state.macro["age"] = HumanState.ageForYears(years)
            model.rebuild()
            model.heightMetres()
        }
        assertTrue(heights.zipWithNext().all { (younger, older) -> older - younger > 0.1f })
        for ((years, anchor) in listOf(1 to "baby", 10 to "child")) {
            model.state.macro["age"] = HumanState.ageForYears(years)
            val stack = model.macroStack().filter { (name, weight) -> "-$anchor" in name && weight > 0.01f }
            assertTrue(stack.isNotEmpty())
            assertTrue(stack.any { (name, _) -> name.startsWith("macrodetails/universal-") && lib.targets.has(name) })
            assertTrue(stack.any { (name, _) -> name.startsWith("macrodetails/caucasian-") && lib.targets.has(name) })
        }
    }

    @Test fun previewAndExportKeepTheYouthOutfitWithAnAdultSave() {
        val model = HumanModel(lib)
        val state = model.state
        state.macro["gender"] = 1f
        state.macro["age"] = HumanState.ageForYears(18)
        state.adult = true
        state.genitals = 1
        state.autoSkin = false
        state.skin = lib.skins.indexOfFirst { it.adult }.also { assertTrue(it >= 0) }
        model.rebuild()
        assertTrue(PartBuilder.build(model, false).any { it.name == "genitals" })

        val younger = state.toJson()
        younger.getJSONObject("macro").put("age", 0.0)
        state.load(younger)
        state.adult = true
        model.rebuild()
        assertFalse(state.adult)
        assertFalse(model.selectedSkin().adult)
        assertTrue(model.selectedSkin().system)
        for (export in listOf(false, true)) {
            val parts = PartBuilder.build(model, export)
            assertFalse(parts.any { it.name in setOf("genitals", "body_under_outfit") })
            val outfit = parts.single { it.name == "youth_base_outfit" }
            val body = parts.single { it.name == "body" }
            assertTrue(outfit.tris.isNotEmpty())
            assertFalse(outfit.alpha)
            assertEquals(lib.base.tris.size, outfit.tris.size + body.tris.size)
        }

        val glb = ByteArrayOutputStream().also { GlbExporter.write(model, it) }.toByteArray()
        val buffer = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, buffer.int)
        buffer.position(12)
        val jsonLength = buffer.int
        buffer.int
        val root = JSONObject(String(glb, buffer.position(), jsonLength))
        val meshes = root.getJSONArray("meshes")
        val names = (0 until meshes.length()).map { meshes.getJSONObject(it).getString("name") }
        assertTrue("youth_base_outfit" in names)
        assertFalse("genitals" in names)
        assertTrue(root.has("skins"))
    }
}
