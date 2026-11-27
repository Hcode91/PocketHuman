package com.hcode91.pockethuman

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class MainActivity : Activity() {
    private lateinit var lib: Library
    private lateinit var model: HumanModel
    private lateinit var view3d: HumanView
    private lateinit var content: LinearLayout
    private lateinit var info: TextView
    private lateinit var tabBar: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val dirty = AtomicBoolean(false)
    private var tab = "Body"
    private var detailGroup = "torso"

    private val accent = Color.parseColor("#E0A458")
    private val fg = Color.parseColor("#E8E8EA")
    private val dim = Color.parseColor("#9A9DA6")
    private val panel = Color.parseColor("#23262E")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HumanModel.modelLoader = { id -> ModelStore.load(filesDir, id) }
        HumanModel.headLoader = { id -> HeadStore.load(filesDir, id) }
        HumanModel.tintProvider = { l, tex, skinTex -> synchronized(this) { GenitalDetail.tintedToSkin(l, tex, skinTex, filesDir, AndroidImageDecoder, AndroidImageWriter) } }
        HumanModel.detailProvider = { l, tex -> synchronized(this) { GenitalDetail.skinWithDetail(l, tex, filesDir, AndroidImageDecoder, AndroidImageWriter) } }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val loading = TextView(this).apply { text = "Loading PocketHuman…"; setTextColor(fg); gravity = Gravity.CENTER; textSize = 18f }
        setContentView(loading)
        worker.execute {
            lib = makeLibrary(); model = HumanModel(lib)
            restoreLast()
            model.rebuild()
            val parts = PartBuilder.build(model, false)
            runOnUiThread {
                view3d = HumanView(this, lib)
                root.addView(view3d, LinearLayout.LayoutParams(-1, 0, 0.52f))
                info = TextView(this).apply { setTextColor(dim); textSize = 12f; setPadding(dp(12), dp(4), dp(12), 0) }
                root.addView(info)
                tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(4), dp(6), dp(4)) }
                root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabBar) })
                content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(24)) }
                root.addView(ScrollView(this).apply { setBackgroundColor(panel); addView(content) }, LinearLayout.LayoutParams(-1, 0, 0.48f))
                setContentView(root)
                view3d.show(parts); updateInfo(); buildTabs(); showTab(tab)
            }
        }
    }

    private fun makeLibrary() = Library(AssetSource { p -> if (p.startsWith("imported/")) java.io.File(filesDir, p).readBytes() else assets.open("ph/$p").use { it.readBytes() } }, filesDir)

    /** Reload everything after packs change, keeping the current character. */
    private fun reloadLibrary(done: () -> Unit) = worker.execute {
        val keep = model.state.toJson()
        lib = makeLibrary(); model = HumanModel(lib); model.state.load(keep); model.rebuild()
        val parts = PartBuilder.build(model, false)
        runOnUiThread { view3d.reload(lib); view3d.show(parts); updateInfo(); done() }
    }

    private fun deleteCharacter(shapeId: String, cname: String) {
        val s = model.state; val skinBefore = lib.skins.getOrNull(s.skin)?.texture
        val outfits = lib.proxies.filter { it.id.startsWith("my_") && it.label == "$cname outfit" }.map { it.id }
        worker.execute {
            CharacterImport.deleteCharacter(filesDir, shapeId, cname)
            outfits.forEach { OutfitDraft.delete(filesDir, it.removePrefix("my_")) }
            s.details.remove(shapeId); outfits.forEach { s.clothes.remove(it) }
            if (s.name == cname) s.name = "Character"
            val keep = s.toJson()
            lib = makeLibrary(); model = HumanModel(lib); model.state.load(keep)
            val idx = lib.skins.indexOfFirst { it.texture == skinBefore }
            if (idx >= 0) model.state.skin = idx else { model.state.autoSkin = true; model.state.skin = model.matchSkin() }
            model.rebuild()
            val parts = PartBuilder.build(model, false)
            runOnUiThread { view3d.reload(lib); view3d.show(parts); updateInfo(); saveLast(); showTab("Body"); toast("$cname deleted") }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ model updates
    private fun changed() {
        if (dirty.getAndSet(true)) return
        worker.execute {
            dirty.set(false)
            model.rebuild()
            val parts = PartBuilder.build(model, false)
            runOnUiThread { if (draft == null && headPrep == null && tab != "Scene") view3d.show(parts); updateInfo() }
        }
    }
    private fun updateInfo() {
        info.text = "%s  ·  %.2f m  ·  age %d".format(model.state.name, model.heightMetres(), model.ageYears().toInt())
    }

    // ------------------------------------------------------------------ tabs
    private val tabs = listOf("Body", "Ethnicity", "Details", "Face", "Skin", "Eyes", "Hair", "Clothes", "Animations", "Scene", "Packs", "Save")
    private fun buildTabs() {
        tabBar.removeAllViews()
        for (t in tabs) tabBar.addView(chip(t, t == tab) { tab = t; buildTabs(); showTab(t) })
    }
    private fun chip(label: String, on: Boolean, click: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(if (on) Color.BLACK else fg); setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(if (on) accent else panel) }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(3), 0, dp(3), 0) }
        setOnClickListener { click() }
    }
    private fun header(t: String) = content.addView(TextView(this).apply { text = t; setTextColor(accent); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(12), 0, dp(4)) })

    private fun slider(label: String, value: Float, min: Float, max: Float, fmt: (Float) -> String, onChange: (Float) -> Unit): SeekBar {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(2)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val name = TextView(this).apply { text = label.replaceFirstChar { it.uppercase() }; setTextColor(fg); textSize = 14f }
        val v = TextView(this).apply { text = fmt(value); setTextColor(dim); textSize = 13f; gravity = Gravity.END }
        top.addView(name, LinearLayout.LayoutParams(0, -2, 1f)); top.addView(v)
        val sb = SeekBar(this).apply {
            this.max = 1000; progress = (((value - min) / (max - min)) * 1000).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { if (!user) return; val x = min + (max - min) * p / 1000f; v.text = fmt(x); onChange(x) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { saveLast() }
            })
        }
        row.addView(top); row.addView(sb); content.addView(row)
        return sb
    }
    private fun pct(x: Float) = "${(x * 100).toInt()}%"

    private fun option(label: String, on: Boolean, click: () -> Unit) {
        content.addView(TextView(this).apply {
            text = (if (on) "●  " else "○  ") + label; textSize = 15f; setTextColor(if (on) accent else fg); setPadding(dp(4), dp(10), dp(4), dp(10))
            setOnClickListener { click(); showTab(tab); changed(); saveLast() }
        })
    }

    private fun switch(label: String, on: Boolean, change: (Boolean) -> Unit) = content.addView(Switch(this).apply {
        text = label; isChecked = on; setTextColor(fg); textSize = 14f; setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, c -> change(c); changed(); saveLast(); showTab(tab) }
    })

    private fun toggleOption(label: String, on: Boolean, click: () -> Unit) {
        content.addView(TextView(this).apply {
            text = (if (on) "☑  " else "☐  ") + label; textSize = 15f; setTextColor(if (on) accent else fg); setPadding(dp(4), dp(10), dp(4), dp(10))
            setOnClickListener { click(); showTab(tab); changed(); saveLast() }
        })
    }

    private val presets = linkedMapOf(
        "Neutral" to mapOf(),
        "Smile" to mapOf("mh:mouth-corner-puller" to 0.8f, "mh:eye-left-slit" to 0.25f, "mh:eye-right-slit" to 0.25f),
        "Surprised" to mapOf("mh:eyebrows-left-up" to 1f, "mh:eyebrows-right-up" to 1f, "mh:eye-left-opened-up" to 0.7f, "mh:eye-right-opened-up" to 0.7f, "mh:mouth-open" to 0.6f),
        "Angry" to mapOf("mh:eyebrows-left-down" to 1f, "mh:eyebrows-right-down" to 1f, "mh:nose-compression" to 0.4f, "mh:mouth-compression" to 0.6f),
        "Sad" to mapOf("mh:eyebrows-left-inner-up" to 1f, "mh:eyebrows-right-inner-up" to 1f, "mh:mouth-depression" to 0.8f),
        "Blink" to mapOf("mh:eye-left-closure" to 1f, "mh:eye-right-closure" to 1f)
    )

    private fun showTab(t: String) {
        content.removeAllViews()
        if (t != "Animations" && playing != null) stopClip()
        if (t == "Scene" && view3d.onTap == null) enterScene() else if (t != "Scene" && view3d.onTap != null) leaveScene()
        val s = model.state
        when (t) {
            "Animations" -> animationsTab()
            "Scene" -> sceneTab()
            "Body" -> {
                s.customModel?.let { cm ->
                    header("Using your imported model: ${ModelStore.name(filesDir, cm) ?: "model"}")
                    content.addView(TextView(this).apply { setTextColor(dim); textSize = 13f; setPadding(0, 0, 0, dp(6))
                        text = "It replaces the PocketHuman body, with a skeleton for posing and export (Save tab). Body sliders, skins and clothes don't apply to it." })
                    button("Back to the PocketHuman body") { s.customModel = null; changed(); saveLast(); showTab("Body") }
                }
                val models = ModelStore.list(filesDir)
                if (models.isNotEmpty()) {
                    header("My imported models")
                    for ((mid, mname) in models) {
                        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                        val on = s.customModel == mid
                        row.addView(TextView(this).apply { text = (if (on) "●  " else "○  ") + mname; textSize = 15f; setTextColor(if (on) accent else fg); setPadding(dp(4), dp(10), dp(4), dp(10))
                            setOnClickListener { s.customModel = if (on) null else mid; changed(); saveLast(); showTab("Body") } }, LinearLayout.LayoutParams(0, -2, 1f))
                        row.addView(TextView(this).apply { text = "Delete"; setTextColor(accent); textSize = 14f; setPadding(dp(12), dp(10), dp(12), dp(10))
                            setOnClickListener { AlertDialog.Builder(this@MainActivity).setMessage("Delete $mname?").setPositiveButton("Delete") { _, _ ->
                                if (s.customModel == mid) s.customModel = null; ModelStore.delete(filesDir, mid); changed(); saveLast(); showTab("Body") }.setNegativeButton("Cancel", null).show() } })
                        content.addView(row)
                    }
                    header("Body")
                }
                button("New character (start again from the default body)") { AlertDialog.Builder(this).setMessage("Start a new character? Your current edits will be cleared. Save first in the Save tab if you want to keep them.")
                    .setPositiveButton("Start new") { _, _ -> model.state.load(HumanState().toJson()); model.state.skin = model.matchSkin(); changed(); saveLast(); showTab("Body") }.setNegativeButton("Cancel", null).show() }
                button("Import a character from a 3D model (.glb / .obj)") { charMode = true; startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, REQ_OUTFIT) }
                val chars = CharacterImport.characters(filesDir)
                if (chars.isNotEmpty()) {
                    header("My imported characters")
                    for ((shapeId, cname) in chars) {
                        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                        val on = (s.details[shapeId] ?: 0f) > 0f
                        row.addView(TextView(this).apply { text = (if (on) "●  " else "○  ") + cname; textSize = 15f; setTextColor(if (on) accent else fg); setPadding(dp(4), dp(10), dp(4), dp(10)) }, LinearLayout.LayoutParams(0, -2, 1f))
                        row.addView(TextView(this).apply { text = "Delete"; setTextColor(accent); textSize = 14f; setPadding(dp(12), dp(10), dp(12), dp(10))
                            setOnClickListener { AlertDialog.Builder(this@MainActivity).setMessage("Delete $cname? This removes its body shape, skin and clothes.")
                                .setPositiveButton("Delete") { _, _ -> deleteCharacter(shapeId, cname) }.setNegativeButton("Cancel", null).show() } })
                        content.addView(row)
                    }
                    header("Body")
                }
                switch("Adult anatomy (genitals, genital sliders and skins)", s.adult) { s.adult = it; if (!it && lib.skins[s.skin].adult) s.skin = model.matchSkin(); showTab("Body") }
                if (s.adult) {
                    header("Male genitals (3D)")
                    val gr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    for ((label, v) in listOf("By gender" to 0, "Show" to 1, "Hide" to 2)) gr.addView(chip(label, s.genitals == v) { s.genitals = v; changed(); saveLast(); showTab("Body") })
                    content.addView(gr)
                    header("Anatomy pieces (3D, from Community assets)")
                    val anat = lib.proxies.filter { it.kind == "anatomy" }
                    if (anat.isEmpty()) content.addView(TextView(this).apply { setTextColor(dim); textSize = 13f; text = "None yet. Get some in Packs → Community assets → Anatomy." })
                    for (p in anat) content.addView(TextView(this).apply { val on = p.id in s.clothes
                        text = (if (on) "☑  " else "☐  ") + p.label; textSize = 15f; setTextColor(if (on) accent else fg); setPadding(dp(4), dp(10), dp(4), dp(10))
                        setOnClickListener { if (!s.clothes.remove(p.id)) s.clothes.add(p.id); changed(); saveLast(); showTab("Body") } })
                    if (anat.isNotEmpty()) {
                        switch("Tint anatomy pieces to the skin tone", s.anatomyTint) { s.anatomyTint = it; changed(); saveLast() }
                        content.addView(TextView(this).apply { setTextColor(dim); textSize = 12f; text = "Pieces are hidden when clothes cover the crotch." })
                    }
                    header("Female genital detail (painted onto any skin)")
                    val gd = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    for ((label, v) in listOf("By gender" to 0, "Show" to 1, "Hide" to 2)) gd.addView(chip(label, s.genitalDetail == v) { s.genitalDetail = v; changed(); saveLast(); showTab("Body") })
                    content.addView(gd)
                }
                slider("Gender", s.macro["gender"]!!, 0f, 1f, { if (it < 0.15f) "female" else if (it > 0.85f) "male" else pct(it) }) { s.macro["gender"] = it; changed() }
                slider("Age", s.macro["age"]!!, 0.5f, 1f, { "${(25 + (it - 0.5f) * 130).toInt()} years" }) { s.macro["age"] = it; changed() }
                slider("Muscle", s.macro["muscle"]!!, 0f, 1f, ::pct) { s.macro["muscle"] = it; changed() }
                slider("Weight", s.macro["weight"]!!, 0f, 1f, ::pct) { s.macro["weight"] = it; changed() }
                slider("Height", s.macro["height"]!!, 0f, 1f, ::pct) { s.macro["height"] = it; changed() }
                slider("Proportions", s.macro["proportions"]!!, 0f, 1f, ::pct) { s.macro["proportions"] = it; changed() }
                slider("Breast size", s.macro["cupsize"]!!, 0f, 1f, ::pct) { s.macro["cupsize"] = it; changed() }
                slider("Breast firmness", s.macro["firmness"]!!, 0f, 1f, ::pct) { s.macro["firmness"] = it; changed() }
            }
            "Ethnicity" -> {
                header("Starting points (real faces vary a lot within every group, so adjust freely)")
                val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                var line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                Ethnicities.presets.forEachIndexed { i, pr ->
                    if (i % 3 == 0) { line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3)) }; row.addView(line) }
                    line.addView(chip(pr.name, false) { Ethnicities.apply(pr, model); changed(); saveLast(); showTab("Ethnicity") })
                }
                content.addView(row)
                header("Mix of the three base shapes (always adds up to 100%)")
                val bars = HashMap<String, SeekBar>()
                for (k in s.race.keys.toList()) bars[k] = slider(k, s.race[k]!!, 0f, 1f, ::pct) { x ->
                    s.setRace(k, x); for ((o, b) in bars) if (o != k) b.progress = (s.race[o]!! * 1000).toInt(); changed()
                }
            }
            "Details" -> {
                val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val groups = lib.defs.groups.filter { !it.adult || s.adult }
                for (g in groups) chips.addView(chip(g.name, g.name == detailGroup) { detailGroup = g.name; showTab("Details") })
                content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
                val g = groups.firstOrNull { it.name == detailGroup } ?: groups.first()
                content.addView(TextView(this).apply {
                    text = "Reset ${g.name}"; setTextColor(accent); textSize = 13f; setPadding(0, dp(10), 0, dp(4))
                    setOnClickListener { g.sliders.forEach { s.details.remove(it.id) }; showTab("Details"); changed(); saveLast() }
                })
                for (sl in g.sliders) {
                    val one = sl.neg.isEmpty()
                    slider(sl.label, s.details[sl.id] ?: 0f, if (one) 0f else -1f, 1f, { "%+.0f".format(it * 100) }) { x ->
                        if (Math.abs(x) < 0.01f) s.details.remove(sl.id) else s.details[sl.id] = x; changed()
                    }
                }
            }
            "Skin" -> {
                switch("Match skin to ethnicity, gender and age", s.autoSkin) { s.autoSkin = it }
                lib.skins.forEachIndexed { i, sk -> if (!sk.adult || s.adult) option(sk.name, s.skin == i) { s.skin = i; s.autoSkin = false } }
            }
            "Face" -> {
                if (s.customModel == null) headSection(s)
                header("Preview an expression (exports keep a neutral face, with every shape included as a blend shape)")
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for ((name, set) in presets) row.addView(chip(name, false) { s.expression.clear(); s.expression.putAll(set); changed(); showTab("Face") })
                content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
                header("MakeHuman face units")
                for (u in lib.defs.expressions) slider(u.replace('-', ' '), s.expression["mh:$u"] ?: 0f, 0f, 1f, ::pct) { x -> if (x < 0.01f) s.expression.remove("mh:$u") else s.expression["mh:$u"] = x; changed() }
                if (lib.packFaceShapes.isNotEmpty()) {
                    header("From packs (visemes and more)")
                    for (k in lib.packFaceShapes) slider(k.substringAfterLast('/').replace('_', ' '), s.expression["pack:$k"] ?: 0f, 0f, 1f, ::pct) { x -> if (x < 0.01f) s.expression.remove("pack:$k") else s.expression["pack:$k"] = x; changed() }
                }
                header("ARKit face units")
                for (u in lib.defs.arkit) slider(u, s.expression["arkit:$u"] ?: 0f, 0f, 1f, ::pct) { x -> if (x < 0.01f) s.expression.remove("arkit:$u") else s.expression["arkit:$u"] = x; changed() }
            }
            "Clothes" -> {
                button("Import outfit (.glb / .obj from Meshy or other AI tools)") { charMode = false; startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, REQ_OUTFIT) }
                val mine = lib.proxies.filter { it.id.startsWith("my_") }
                if (mine.isNotEmpty()) {
                    header("My outfits")
                    for (p in mine) {
                        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                        row.addView(TextView(this).apply { text = (if (p.id in s.clothes) "☑  " else "☐  ") + p.label; textSize = 15f; setTextColor(if (p.id in s.clothes) accent else fg); setPadding(dp(4), dp(10), dp(4), dp(10))
                            setOnClickListener { if (!s.clothes.remove(p.id)) s.clothes.add(p.id); showTab(tab); changed(); saveLast() } }, LinearLayout.LayoutParams(0, -2, 1f))
                        row.addView(TextView(this).apply { text = "Edit"; setTextColor(accent); textSize = 14f; setPadding(dp(10), dp(10), dp(10), dp(10))
                            setOnClickListener { worker.execute { val d = OutfitDraft.loadSaved(filesDir, p.id.removePrefix("my_")); runOnUiThread { if (d != null) openEditor(d) else toast("Couldn't open that outfit") } } } })
                        row.addView(TextView(this).apply { text = "Delete"; setTextColor(accent); textSize = 14f; setPadding(dp(10), dp(10), dp(10), dp(10))
                            setOnClickListener { AlertDialog.Builder(this@MainActivity).setMessage("Delete ${p.label}?").setPositiveButton("Delete") { _, _ ->
                                OutfitDraft.delete(filesDir, p.id.removePrefix("my_")); s.clothes.remove(p.id); reloadLibrary { showTab("Clothes") } }.setNegativeButton("Cancel", null).show() } })
                        content.addView(row)
                    }
                    header("Included clothes")
                }
                header("Tap to put on or take off. Body under clothes is hidden, and kept as its own part in the export.")
                for (p in lib.ofKind("clothes").filter { !it.id.startsWith("my_") }) toggleOption(p.label + (lib.credits[p.id]?.takeIf { !it.license.equals("CC0", true) }?.let { "  (${it.license})" } ?: ""), p.id in s.clothes) { if (!s.clothes.remove(p.id)) s.clothes.add(p.id) }
            }
            "Eyes" -> {
                val eyes = lib.proxies.firstOrNull { it.kind == "eyes" }
                eyes?.variants?.forEachIndexed { i, v -> option(v.name, s.eyeColour == i) { s.eyeColour = i } }
                header("Eyelashes")
                option("None", s.eyelashes == null) { s.eyelashes = null }
                for (p in lib.ofKind("eyelashes")) option(p.label, s.eyelashes == p.id) { s.eyelashes = p.id }
            }
            "Hair" -> {
                header("Hair")
                option("None", s.hair == null) { s.hair = null }
                for (p in lib.ofKind("hair")) option(p.label, s.hair == p.id) { s.hair = p.id; s.hairVariant = 0 }
                header("Eyebrows")
                option("None", s.eyebrows == null) { s.eyebrows = null }
                for (p in lib.ofKind("eyebrows")) option(p.label, s.eyebrows == p.id) { s.eyebrows = p.id }
            }
            "Save" -> saveTab()
            "Packs" -> packsTab()
        }
    }

    private fun button(label: String, click: () -> Unit) = content.addView(Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.BLACK); textSize = 15f
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(accent) }
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, dp(6), 0, dp(6)) }
        setOnClickListener { click() }
    })

    private fun saveTab() {
        val s = model.state
        content.addView(TextView(this).apply { text = "Name"; setTextColor(dim); textSize = 12f; setPadding(0, dp(8), 0, 0) })
        content.addView(EditText(this).apply {
            setText(s.name); setTextColor(fg); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setOnFocusChangeListener { _, f -> if (!f) { s.name = text.toString().ifBlank { "Character" }; updateInfo(); saveLast() } }
            setOnEditorActionListener { _, _, _ -> s.name = text.toString().ifBlank { "Character" }; updateInfo(); saveLast(); false }
        })
        button("Export rigged .glb") { create(REQ_GLB, "${safe(s.name)}.glb", "model/gltf-binary") }
        button("Save character (.json)") { create(REQ_SAVE, "${safe(s.name)}.pockethuman.json", "application/json") }
        button("Save to My characters (for scenes)") { saveToMyCharacters() }
        button("Load character") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, REQ_LOAD) }
        button("Randomise") { randomise() }
        button("Reset") { AlertDialog.Builder(this).setMessage("Reset everything?").setPositiveButton("Reset") { _, _ -> model.state.load(HumanState().toJson()); changed(); saveLast(); showTab(tab) }.setNegativeButton("Cancel", null).show() }
        val cr = Credits.forCharacter(model).filter { !it.license.equals("CC0", true) }
        if (cr.isNotEmpty()) {
            header("Credits needed for this character")
            content.addView(TextView(this).apply { setTextColor(fg); textSize = 13f
                text = cr.joinToString("\n") { "• ${it.asset.replace('_', ' ')} by ${it.author.ifEmpty { "unknown" }} (${it.pack}, ${it.license})" } + "\n\nThese are also saved inside the exported .glb." })
        }
        content.addView(TextView(this).apply {
            setTextColor(dim); textSize = 12f; setPadding(0, dp(16), 0, 0)
            text = "The .glb has a Mixamo-named skeleton (A-pose), skin weights, eyes, brows, lashes, teeth, hair and each clothing item as its own object, plus the animations ticked in the Animations tab (${model.state.anims.size} now). Ready for three.js, Godot, Unity or Blender.\n\n" +
                "Body, morphs, skins, eyes and hair come from the MakeHuman / MPFB2 community and are CC0, so anything you make is yours to use, including commercially."
        })
    }
    // ------------------------------------------------------------------ outfit editor
    private var draft: OutfitDraft? = null
    private var draftBody: BodyQuery? = null
    private var draftColors: ColorSampler? = null
    private var draftTex: String? = null
    private var lastFit: OutfitDraft.Fitted? = null
    private val draftDirty = AtomicBoolean(false)
    private var pendingOutfit: java.io.File? = null
    private var charMode = false
    private var pendingKind = 0f
    private fun afterKind(f: java.io.File) {
        if (f.extension.lowercase() == "obj") { pendingOutfit = f
            AlertDialog.Builder(this).setMessage("Pick the texture image that goes with this .obj (or skip for plain grey).")
                .setPositiveButton("Pick texture") { _, _ -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, REQ_OUTFIT_TEX) }
                .setNegativeButton("Skip") { _, _ -> startOutfitImport(f, null) }.show()
        } else startOutfitImport(f, null)
    }

    private fun openEditor(d: OutfitDraft) {
        // fitting always happens on the PocketHuman body, so step out of an imported model while editing
        if (model.state.customModel != null) { model.state.customModel = null; model.rebuild() }
        draft = d; tabBar.visibility = View.GONE
        val wait = AlertDialog.Builder(this).setMessage("Preparing…").setCancelable(false).show()
        worker.execute {
            draftBody = BodyQuery(model)
            draftColors = d.raw.texture?.let { bitmapSampler(it, 2048) }
            val dir = java.io.File(filesDir, "imported/my_outfits/preview").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            draftTex = if (d.raw.texture != null) { java.io.File(dir, "${d.id}.${d.raw.textureExt}").writeBytes(d.raw.texture); "imported/my_outfits/preview/${d.id}.${d.raw.textureExt}" }
                else { java.io.File(dir, "grey.png").writeBytes(OutfitDraft.GREY_PNG); "imported/my_outfits/preview/grey.png" }
            runOnUiThread { wait.dismiss(); showEditor(); refreshDraft() }
        }
    }

    private fun refreshDraft() {
        if (draftDirty.getAndSet(true)) return
        worker.execute {
            draftDirty.set(false)
            val d = draft ?: return@execute; val body = draftBody ?: return@execute
            val f = d.computePreview(model, body, draftColors); val pd = d
            val bodyParts = PartBuilder.build(model, false, f.hiddenBody, setOf("my_${d.id}"))
            runOnUiThread { prevPos = f.pos; prevTris = f.keepTris; prevUv = pd.raw.uv; prevBody = bodyParts; showLive(f.pos) }
        }
    }

    // ---- brushes on the 3D view
    private var prevPos: FloatArray? = null; private var prevTris: IntArray? = null; private var prevUv: FloatArray? = null; private var prevBody: List<Part> = emptyList()
    private var tool = -1; private var brushPx = 70f; private var brushStrength = 0.5f

    private fun showLive(pos: FloatArray) {
        val t = prevTris ?: return; val uv = prevUv ?: return
        view3d.show(prevBody + Part("draft", pos, weldedNormals(pos, t, pos.size / 3), uv, t, draftTex!!, false, null, IntArray(0), FloatArray(0), IntArray(0), FloatArray(0), 1))
    }

    /** Ray from the camera through a screen point onto the preview outfit: hit point or null. */
    private fun pick(pos: FloatArray, x: Float, y: Float): FloatArray? {
        val tris = prevTris ?: return null
        val o = view3d.unproject(x, y, -1f); val far = view3d.unproject(x, y, 1f)
        val d = floatArrayOf(far[0] - o[0], far[1] - o[1], far[2] - o[2])
        var best = Float.MAX_VALUE; var i = 0
        while (i < tris.size) {
            val a = tris[i] * 3; val b = tris[i + 1] * 3; val c = tris[i + 2] * 3; i += 3
            val e1x = pos[b] - pos[a]; val e1y = pos[b + 1] - pos[a + 1]; val e1z = pos[b + 2] - pos[a + 2]
            val e2x = pos[c] - pos[a]; val e2y = pos[c + 1] - pos[a + 1]; val e2z = pos[c + 2] - pos[a + 2]
            val px = d[1] * e2z - d[2] * e2y; val py = d[2] * e2x - d[0] * e2z; val pz = d[0] * e2y - d[1] * e2x
            val det = e1x * px + e1y * py + e1z * pz; if (Math.abs(det) < 1e-12f) continue
            val inv = 1f / det; val tx = o[0] - pos[a]; val ty = o[1] - pos[a + 1]; val tz = o[2] - pos[a + 2]
            val u = (tx * px + ty * py + tz * pz) * inv; if (u < 0 || u > 1) continue
            val qx = ty * e1z - tz * e1y; val qy = tz * e1x - tx * e1z; val qz = tx * e1y - ty * e1x
            val v = (d[0] * qx + d[1] * qy + d[2] * qz) * inv; if (v < 0 || u + v > 1) continue
            val t = (e2x * qx + e2y * qy + e2z * qz) * inv; if (t > 0 && t < best) best = t
        }
        return if (best == Float.MAX_VALUE) null else floatArrayOf(o[0] + d[0] * best, o[1] + d[1] * best, o[2] + d[2] * best)
    }

    private val brushImpl = object : HumanView.Brush {
        var start: FloatArray? = null; var depth = 0f; var radius = 1f; var sx = 0f; var sy = 0f
        var base: FloatArray? = null; var weights: FloatArray? = null; var live: FloatArray? = null
        var delta = FloatArray(3); val stamps = ArrayList<OutfitDraft.Stroke>(); var lx = 0f; var ly = 0f
        override fun down(x: Float, y: Float): Boolean {
            val d = draft ?: return false; val pos = prevPos ?: return false
            if (tool < 0) return false
            val hit = pick(pos, x, y) ?: return false
            start = hit; depth = view3d.ndcDepth(hit); sx = x; sy = y; lx = x; ly = y
            val a = view3d.unproject(x, y, depth); val b = view3d.unproject(x + brushPx, y, depth)
            radius = Math.sqrt(((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1]) + (b[2] - a[2]) * (b[2] - a[2])).toDouble()).toFloat()
            base = pos.copyOf(); live = pos.copyOf(); stamps.clear(); delta = FloatArray(3)
            if (tool == OutfitDraft.GRAB) weights = FloatArray(pos.size / 3) { i ->
                val dx = pos[i * 3] - hit[0]; val dy = pos[i * 3 + 1] - hit[1]; val dz = pos[i * 3 + 2] - hit[2]
                val dd = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat(); if (dd >= radius) 0f else { val t = 1 - dd / radius; t * t * (3 - 2 * t) } }
            else stamp(x, y)
            return true
        }
        private fun stamp(x: Float, y: Float) {
            val d = draft ?: return; val l = live ?: return; val h = pick(l, x, y) ?: return; val body = draftBody ?: return
            val st = OutfitDraft.Stroke(tool, h, radius, FloatArray(3), brushStrength * 0.6f)
            d.applyStroke(st, l, body); stamps.add(st); showLive(l)
        }
        override fun move(x: Float, y: Float) {
            if (tool == OutfitDraft.GRAB) {
                val a = view3d.unproject(sx, sy, depth); val b = view3d.unproject(x, y, depth)
                delta = floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
                val bs = base ?: return; val w = weights ?: return; val l = live ?: return
                for (i in w.indices) { val wi = w[i]; if (wi == 0f) continue; for (k in 0..2) l[i * 3 + k] = bs[i * 3 + k] + wi * delta[k] }
                showLive(l)
            } else if (Math.hypot((x - lx).toDouble(), (y - ly).toDouble()) > brushPx / 3) { lx = x; ly = y; stamp(x, y) }
        }
        override fun up() {
            val d = draft ?: return
            if (tool == OutfitDraft.GRAB) { start?.let { d.strokes.add(OutfitDraft.Stroke(OutfitDraft.GRAB, it, radius, delta, 1f)) } }
            else d.strokes.addAll(stamps)
            refreshDraft()
        }
    }

    private fun closeEditor() { view3d.brush = null; tool = -1; charMode = false; draft = null; draftBody = null; lastFit = null; tabBar.visibility = View.VISIBLE; showTab("Clothes"); changed() }

    private fun showEditor() {
        val d = draft ?: return
        content.removeAllViews()
        content.addView(TextView(this).apply { text = if (charMode) "Line up your character" else "Fit outfit"; setTextColor(accent); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, dp(4)) })
        content.addView(EditText(this).apply { setText(d.name); setTextColor(fg); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setOnFocusChangeListener { _, f -> if (!f) d.name = text.toString().ifBlank { "Outfit" } }; addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { d.name = e.toString().ifBlank { "Outfit" } }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, e: Int) {}; override fun onTextChanged(a: CharSequence?, b: Int, c: Int, e: Int) {} }) })
        fun sl(label: String, key: String, min: Float, max: Float, fmt: (Float) -> String) = slider(label, d.p[key]!!, min, max, fmt) { d.p[key] = it; refreshDraft() }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(chip("Turn 90°", false) { d.p["turn"] = d.p["turn"]!! + 1; worker.execute { d.autoPlace(draftBody!!); runOnUiThread { refreshDraft() } } })
        row.addView(chip("Stand upright", false) { d.p["upright"] = d.p["upright"]!! + 1; worker.execute { d.autoPlace(draftBody!!); runOnUiThread { refreshDraft() } } })
        row.addView(chip("Re-centre", false) { worker.execute { d.autoPlace(draftBody!!); runOnUiThread { refreshDraft() } } })
        if (!charMode) row.addView(chip("Fits: " + OutfitDraft.KINDS[d.p["kind"]!!.toInt()], false) {
            AlertDialog.Builder(this).setItems(OutfitDraft.KINDS.toTypedArray()) { _, k -> d.p["kind"] = k.toFloat(); showEditor() }.show() })
        if (!charMode) row.addView(chip("Auto-fit", false) { val w = AlertDialog.Builder(this).setMessage("Finding the best fit…").setCancelable(false).show()
            worker.execute { d.autoFit(draftBody!!); runOnUiThread { w.dismiss(); d.p["size"] = 1f; d.p["up"] = 0f; showEditor(); refreshDraft() } } })
        content.addView(HorizontalScrollView(this).apply { addView(row) })
        if (!charMode) {
            header("Brushes: pick one, then drag on the outfit (two fingers still turn the view)")
            val tr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, t) in listOf("Turn view" to -1, "Grab" to OutfitDraft.GRAB, "Shrinkwrap" to OutfitDraft.SHRINK, "Smooth" to OutfitDraft.SMOOTH, "Puff" to OutfitDraft.PUFF))
                tr.addView(chip(label, tool == t) { tool = t; view3d.brush = if (t < 0) null else brushImpl; showEditor() })
            tr.addView(chip("Undo", false) { if (d.strokes.isNotEmpty()) { val last = d.strokes.last(); val type = last.type
                // stamps from one drag come in runs: undo the whole run
                if (type == OutfitDraft.GRAB) d.strokes.removeAt(d.strokes.size - 1) else { while (d.strokes.isNotEmpty() && d.strokes.last().type == type && d.strokes.last().r == last.r) d.strokes.removeAt(d.strokes.size - 1) }
                refreshDraft() } })
            tr.addView(chip("Clear brushes", false) { d.strokes.clear(); refreshDraft() })
            content.addView(HorizontalScrollView(this).apply { addView(tr) })
            slider("Brush size", brushPx, 20f, 250f, { "${it.toInt()} px" }) { brushPx = it }
            slider("Brush strength", brushStrength, 0.1f, 1f, ::pct) { brushStrength = it }
        }
        header("Size and position")
        sl("Size", "size", 0.3f, 2.5f, ::pct); sl("Width", "width", 0.7f, 1.3f, ::pct); sl("Height", "height", 0.7f, 1.3f, ::pct); sl("Depth", "depth", 0.7f, 1.3f, ::pct)
        sl("Up / down", "up", -3f, 3f) { "%+.0f cm".format(it * 10) }; sl("Back / forward", "forward", -2f, 2f) { "%+.0f cm".format(it * 10) }; sl("Left / right", "side", -2f, 2f) { "%+.0f cm".format(it * 10) }
        header("Match the pose")
        sl("Left arm", "armL", -45f, 45f) { "%+.0f°".format(it) }; sl("Right arm", "armR", -45f, 45f) { "%+.0f°".format(it) }; sl("Legs apart", "legs", -25f, 25f) { "%+.0f°".format(it) }
        if (charMode) { characterButtons(d); return }
        header("Fit and clipping")
        sl("Loosen / tighten", "loosen", -0.5f, 1f) { "%+.0f mm".format(it * 100) }
        sl("Keep outside the body by", "clip", 0f, 0.3f) { if (it < 0.005f) "off" else "%.0f mm".format(it * 100) }
        sl("Remove the model's own skin", "skinCut", 0f, 1f) { if (it < 0.01f) "off" else pct(it) }
        content.addView(Switch(this).apply { text = "Hide body underneath"; isChecked = d.p["hideBody"]!! > 0.5f; setTextColor(fg); setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, c -> d.p["hideBody"] = if (c) 1f else 0f; refreshDraft() } })
        button("Save outfit") {
            val wait = AlertDialog.Builder(this).setMessage("Saving…").setCancelable(false).show()
            worker.execute {
                val r = runCatching { val f = d.compute(model, draftBody!!, draftColors); d.bake(model, draftBody!!, f, filesDir, AndroidTextures) }
                runOnUiThread { wait.dismiss()
                    r.onSuccess { model.state.clothes.add("my_${d.id}"); reloadLibrary { closeEditor(); saveLast() } }
                     .onFailure { toast("Couldn't save: ${it.message}") } }
            }
        }
        content.addView(TextView(this).apply { text = "Cancel"; setTextColor(dim); textSize = 15f; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)); setOnClickListener { closeEditor() } })
        content.addView(TextView(this).apply { setTextColor(dim); textSize = 12f
            text = "Tips: turn and stand the outfit up first if it's lying down or facing away. Use Size and Up/down to line it up, then the arm and leg angles if the outfit was made in a different pose. " +
                "Raise 'Keep outside the body' to stop skin poking through. The saved outfit follows every body slider, so you can change the body afterwards." })
    }

    private fun characterButtons(d: OutfitDraft) {
        content.addView(TextView(this).apply { setTextColor(dim); textSize = 12f; setPadding(0, dp(8), 0, dp(4))
            text = "Line the model up with the body underneath: turn and stand it up if needed, match the size and height, then the arm and leg angles until the body is hidden inside the model." })
        button("Use this model as my character") { useModel(d) }
        button("Rebuild as a PocketHuman body (sliders work)") { makeCharacter(d) }
        button("Export as-is, rigged (.glb)") { create(REQ_ASIS, "${safe(d.name)}_rigged.glb", "model/gltf-binary") }
        content.addView(TextView(this).apply { text = "Cancel"; setTextColor(dim); textSize = 15f; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)); setOnClickListener { closeEditor() } })
        content.addView(TextView(this).apply { setTextColor(dim); textSize = 12f
            text = "'Use this model' replaces the PocketHuman body with your model exactly as it is, with a skeleton added so it can be posed and exported. Body sliders, skins and clothes don't apply to it; you can switch back any time in the Body tab.\n\n" +
                "'Rebuild as a PocketHuman body' keeps the app's body and reshapes it towards your model: a body-shape slider, a painted skin and the model's clothes as an outfit. It only works well when the model stands in an A-pose or T-pose.\n\n" +
                "Either way the skeleton follows the body underneath, so line the arms and legs up with it. In other poses (an arm up at the head, say) the arms won't bend right when animated: ask Meshy for an A-pose version." })
    }

    private fun useModel(d: OutfitDraft) {
        val dlg = AlertDialog.Builder(this).setMessage("Adding a skeleton to ${d.name}…").setCancelable(false).show()
        worker.execute {
            val r = runCatching {
                val body = BodyQuery(model); val f = d.alignedOnly(model, body)
                val (part, _) = AsIsExport.part(model, body, d, f)
                val id = "m" + System.currentTimeMillis().toString(36)
                ModelStore.save(filesDir, id, d.name, part, d.raw.texture ?: OutfitDraft.GREY_PNG, if (d.raw.texture != null) d.raw.textureExt else "png")
                model.state.customModel = id; model.state.name = d.name; model.rebuild()
            }
            runOnUiThread {
                dlg.dismiss()
                r.onSuccess { closeEditor(); view3d.show(PartBuilder.build(model, false)); updateInfo(); saveLast(); showTab("Body"); toast("${d.name} is now your character") }
                 .onFailure { AlertDialog.Builder(this).setTitle("Couldn't use the model").setMessage(it.message ?: it.toString()).setPositiveButton("OK", null).show() }
            }
        }
    }

    private fun makeCharacter(d: OutfitDraft) {
        val msg = TextView(this).apply { setTextColor(fg); setPadding(dp(20), dp(10), dp(20), dp(10)); text = "Starting…" }
        val dlg = AlertDialog.Builder(this).setTitle(d.name).setView(msg).setCancelable(false).show()
        fun say(t: String) = runOnUiThread { msg.text = t }
        worker.execute {
            val r = runCatching {
                say("Lining up…")
                val body0 = BodyQuery(model)
                val aligned = d.alignedOnly(model, body0)
                val skin = CharacterImport.skinMask(d.raw, draftColors)
                if (skin.count { it } < 50) error("Couldn't find any bare skin on this model to match the body to.")
                // 1. body shape
                val delta = CharacterImport.shapeDelta(model, aligned.pos, d.raw, skin) { say(it) }
                val sliderId = CharacterImport.saveShape(filesDir, d.name, delta)
                val keep = model.state.toJson()
                lib = makeLibrary(); model = HumanModel(lib); model.state.load(keep); model.state.details[sliderId] = 1f; model.rebuild()
                // 2. skin from the model, painted onto the reshaped body
                say("Painting skin…")
                val baseSkin = lib.skins[model.state.skin.coerceIn(0, lib.skins.size - 1)]
                val baseSampler = bitmapSampler(lib.am.bytes(baseSkin.texture), 2048)!!
                val colors = draftColors
                if (colors != null) {
                    val path = CharacterImport.projectSkin(model, aligned.pos, d.raw, skin, colors, baseSampler, 2048, filesDir, d.name, AndroidImageWriter) { say(it) }
                    val k2 = model.state.toJson(); lib = makeLibrary(); model = HumanModel(lib); model.state.load(k2)
                    model.state.skin = lib.skins.indexOfFirst { it.texture == path }.coerceAtLeast(0); model.state.autoSkin = false
                    model.rebuild()
                }
                // 3. clothes and hair as an outfit, fitted to the new body
                say("Fitting the outfit…")
                val outfit = OutfitDraft("c" + System.currentTimeMillis().toString(36), d.name + " outfit", d.raw, LinkedHashMap(d.p)).also { o ->
                    o.autoScale = d.autoScale; o.autoShift = d.autoShift.copyOf(); o.p["skinCut"] = 0.6f; o.p["clip"] = 0.05f; o.p["hideBody"] = 1f }
                val body1 = BodyQuery(model)
                outfit.bake(model, body1, outfit.compute(model, body1, colors), filesDir, AndroidTextures)
                val k3 = model.state.toJson(); lib = makeLibrary(); model = HumanModel(lib); model.state.load(k3)
                model.state.clothes.add("my_${outfit.id}"); model.state.hair = null; model.state.name = d.name
                model.rebuild()
            }
            runOnUiThread {
                dlg.dismiss()
                r.onSuccess { view3d.reload(lib); closeEditor(); saveLast(); toast("${d.name} is ready") }
                 .onFailure { AlertDialog.Builder(this).setTitle("Couldn't make the character").setMessage(it.message ?: it.toString()).setPositiveButton("OK", null).show() }
            }
        }
    }

    private fun startOutfitImport(f: java.io.File, tex: java.io.File?) {
        val wait = AlertDialog.Builder(this).setMessage("Loading model…").setCancelable(false).show()
        worker.execute {
            val r = runCatching {
                var m = MeshIO.load(f, tex)
                if (m.tris.size / 3 > 200000) m = MeshIO.simplify(m, 150000)
                val d = OutfitDraft("o" + System.currentTimeMillis().toString(36), f.nameWithoutExtension.replace("Meshy_AI_", "").replace(Regex("_\\d{8}.*$"), "").replace('_', ' ').take(40), m)
                val body = BodyQuery(model)
                d.p["kind"] = pendingKind
                if (charMode) d.autoPlace(body) else d.autoFit(body); d
            }
            runOnUiThread { wait.dismiss(); r.onSuccess { openEditor(it) }.onFailure { AlertDialog.Builder(this).setTitle("Couldn't load").setMessage(it.message ?: it.toString()).setPositiveButton("OK", null).show() } }
        }
    }

    private fun displayName(uri: Uri): String = contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && i >= 0) c.getString(i) else null } ?: (uri.lastPathSegment ?: "model")

    private val catalog by lazy {
        val a = org.json.JSONArray(String(assets.open("ph/catalog.json").use { it.readBytes() }))
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun packsTab() {
        content.addView(TextView(this).apply { setTextColor(dim); textSize = 12f; setPadding(0, dp(8), 0, dp(4))
            text = "Packs from the MakeHuman community. CC0 = free to use. CC-BY = free to use, but credit the author (the Save tab lists who to credit). Downloads come straight from makehumancommunity.org." })
        button("Community assets: ${community.size} single items (clothes, hair, skins, shapes…)") { communityScreen() }
        button("Import a pack .zip from this phone") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip" }, REQ_PACK) }
        val have = lib.installed.associateBy { it.id }
        for (p in catalog) {
            val id = p.getString("id"); val inst = have[id]
            val status = p.optString("status")
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(10)) }
            box.addView(TextView(this).apply { text = p.getString("title"); setTextColor(fg); textSize = 15f })
            val size = p.optLong("size") / 1_000_000.0
            box.addView(TextView(this).apply { setTextColor(dim); textSize = 12f
                text = "${p.getString("license")}  ·  ${"%.0f".format(maxOf(size, 0.1))} MB" + when {
                    inst != null -> "  ·  installed: ${inst.counts}"
                    status == "builtin" -> "  ·  ${p.optString("note")}"
                    status == "unsupported" -> "  ·  ${p.optString("note")}"
                    else -> "" } })
            if (status.isEmpty()) box.addView(TextView(this).apply {
                text = if (inst != null) "Remove" else "Download"; setTextColor(accent); textSize = 14f; setPadding(0, dp(6), 0, 0)
                setOnClickListener {
                    if (inst != null) { java.io.File(filesDir, "imported/$id").deleteRecursively(); reloadLibrary { showTab("Packs") } }
                    else downloadPack(p)
                } })
            content.addView(box)
        }
        for (inst in lib.installed.filter { i -> catalog.none { it.getString("id") == i.id } && !i.id.startsWith("community_") && !i.id.startsWith("my_") }) {
            content.addView(TextView(this).apply { text = "${inst.title} (imported)  ·  ${inst.counts}  ·  tap to remove"; setTextColor(fg); textSize = 14f; setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { java.io.File(filesDir, "imported/${inst.id}").deleteRecursively(); reloadLibrary { showTab("Packs") } } })
        }
    }

    // ------------------------------------------------------------------ community assets (single items from the old MakeHuman repository)
    private val community: List<org.json.JSONObject> by lazy {
        val a = org.json.JSONArray(String(assets.open("ph/community.json").use { it.readBytes() }))
        (0 until a.length()).map { a.getJSONObject(it) }
    }
    private var commQuery = ""; private var commKind = "all"; private var commInstalled = false; private var commShown = 40
    private val commKinds = listOf("all" to "All", "clothes" to "Clothes", "hair" to "Hair", "brows" to "Brows & lashes", "skin" to "Skins", "target" to "Shapes", "anatomy" to "Anatomy (18+)")

    private fun fetchBytes(url: String): ByteArray {
        var last: Exception? = null
        for (u in listOf(url, url.replace("//files.makehumancommunity.org", "//files2.makehumancommunity.org"))) {
            try {
                val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 60000; c.instanceFollowRedirects = true
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                return c.inputStream.use { it.readBytes() }
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("download failed")
    }

    private fun commMatches(e: org.json.JSONObject): Boolean {
        val s = model.state; val k = e.getString("k")
        if (e.optInt("x") == 1 && !s.adult) return false
        val kindOk = when (commKind) { "all" -> true; "brows" -> k == "eyebrows" || k == "eyelashes"; else -> k == commKind }
        if (!kindOk) return false
        if (commInstalled && !CommunityImporter.installed(filesDir, e.getInt("n"))) return false
        if (commQuery.isNotBlank()) { val q = commQuery.lowercase(); if (!(e.getString("t").lowercase().contains(q) || e.optString("a").lowercase().contains(q) || e.optString("c").lowercase().contains(q))) return false }
        return true
    }

    private fun communityScreen() {
        content.removeAllViews()
        button("‹ Back to packs") { showTab("Packs") }
        content.addView(TextView(this).apply { setTextColor(dim); textSize = 12f; setPadding(0, dp(6), 0, dp(4))
            text = "Single items shared by MakeHuman users, downloaded from makehumancommunity.org. CC0 = free to use. CC-BY = free to use, but credit the author (the Save tab lists who). Quality varies: some items may not fit or may look rough." +
                (if (model.state.adult) "" else "\nTurn on Adult anatomy in the Body tab to see 18+ items.") })
        content.addView(EditText(this).apply {
            setText(commQuery); hint = "Search (name, author, category)"; setTextColor(fg); setHintTextColor(dim); isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { v, _, _ -> commQuery = v.text.toString(); commShown = 40; communityScreen(); true }
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((k, label) in commKinds) { if (k == "anatomy" && !model.state.adult) continue; row.addView(chip(label, commKind == k) { commKind = k; commShown = 40; communityScreen() }) }
        row.addView(chip("Installed", commInstalled) { commInstalled = !commInstalled; commShown = 40; communityScreen() })
        content.addView(HorizontalScrollView(this).apply { addView(row) })
        val hits = community.filter { commMatches(it) }
        header("${hits.size} items")
        for (e in hits.take(commShown)) {
            val n = e.getInt("n"); val inst = CommunityImporter.installed(filesDir, n)
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(9), dp(4), dp(9)); setOnClickListener { communityDetail(e) } }
            box.addView(TextView(this).apply { text = (if (inst) "✓ " else "") + e.getString("t"); setTextColor(if (inst) accent else fg); textSize = 15f })
            box.addView(TextView(this).apply { setTextColor(dim); textSize = 12f
                text = listOf(e.optString("c").ifEmpty { e.getString("k") }, e.optString("a"), e.optString("l"), e.optString("d").take(4)).filter { it.isNotEmpty() }.joinToString("  ·  ") })
            content.addView(box)
        }
        if (hits.size > commShown) button("Show more") { commShown += 60; communityScreen() }
    }

    private fun communityDetail(e: org.json.JSONObject) {
        val n = e.getInt("n"); val inst = CommunityImporter.installed(filesDir, n)
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(4)) }
        val img = android.widget.ImageView(this).apply { adjustViewBounds = true; maxHeight = dp(260) }
        v.addView(img, LinearLayout.LayoutParams(-1, -2))
        v.addView(TextView(this).apply { setTextColor(fg); textSize = 13f; setPadding(0, dp(8), 0, 0)
            text = "By ${e.optString("a").ifEmpty { "unknown" }}  ·  ${e.optString("l")}  ·  ${e.optString("c").ifEmpty { e.getString("k") }}  ·  ${e.optString("d")}\n\n${e.optString("s")}" +
                (if (e.optString("l") == "CC-BY") "\n\nCC-BY: credit ${e.optString("a")} if you use this in your game." else "") })
        val d = AlertDialog.Builder(this).setTitle(e.getString("t")).setView(android.widget.ScrollView(this).apply { addView(v) })
            .setNegativeButton("Close", null)
        if (inst) d.setPositiveButton("Remove") { _, _ -> removeCommunity(n) } else d.setPositiveButton("Download") { _, _ -> installCommunity(e) }
        d.show()
        e.getJSONObject("f").optString("render").ifEmpty { e.getJSONObject("f").optString("thumb") }.takeIf { it.isNotEmpty() }?.let { url ->
            worker.execute { val bmp = runCatching { val b = fetchBytes(url.replace(" ", "%20")); android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }) }.getOrNull()
                runOnUiThread { if (bmp != null) img.setImageBitmap(bmp) } }
        }
    }

    private fun installCommunity(e: org.json.JSONObject) {
        val n = e.getInt("n"); val k = e.getString("k")
        val msg = TextView(this).apply { setTextColor(fg); setPadding(dp(20), dp(10), dp(20), dp(10)); text = "Starting…" }
        val dlg = AlertDialog.Builder(this).setTitle(e.getString("t")).setView(msg).setCancelable(false).show()
        worker.execute {
            val r = runCatching { CommunityImporter(filesDir, AndroidTextures) { fetchBytes(it) }.install(e) { t -> runOnUiThread { msg.text = t } } }
            val keep = model.state.toJson()
            if (r.isSuccess) { lib = makeLibrary(); model = HumanModel(lib); model.state.load(keep) }
            // put the new item on, so it's visible straight away
            val s = model.state; val pid = lib.proxies.firstOrNull { it.id.startsWith(CommunityImporter.packId(n) + "_") }?.id
            var where = ""
            if (r.isSuccess) when (k) {
                "clothes", "anatomy" -> { if (pid != null) s.clothes.add(pid); where = if (k == "anatomy") "Body tab → Anatomy" else "Clothes tab" }
                "hair" -> { s.hair = pid; s.hairVariant = 0; where = "Hair tab" }
                "eyebrows" -> { s.eyebrows = pid; where = "Hair tab" }
                "eyelashes" -> { s.eyelashes = pid; where = "Hair tab" }
                "skin" -> { val i = lib.skins.indexOfFirst { it.texture.startsWith("imported/${CommunityImporter.packId(n)}/") }; if (i >= 0) { s.skin = i; s.autoSkin = false }; where = "Skin tab" }
                "target" -> where = "Details tab"
            }
            model.rebuild(); val parts = PartBuilder.build(model, false)
            runOnUiThread {
                dlg.dismiss(); view3d.reload(lib); view3d.show(parts); updateInfo(); saveLast()
                r.onSuccess { toast("Installed. It's in the $where.") }
                 .onFailure { AlertDialog.Builder(this).setTitle("Couldn't install").setMessage(it.message ?: it.toString()).setPositiveButton("OK", null).show() }
                communityScreen()
            }
        }
    }

    private fun removeCommunity(n: Int) {
        val prefix = CommunityImporter.packId(n) + "_"; val s = model.state
        val skinTex = lib.skins.getOrNull(s.skin)?.texture
        s.clothes.removeAll { it.startsWith(prefix) }
        if (s.hair?.startsWith(prefix) == true) s.hair = null
        if (s.eyebrows?.startsWith(prefix) == true) s.eyebrows = "eyebrows_eyebrow001"
        if (s.eyelashes?.startsWith(prefix) == true) s.eyelashes = "eyelashes_eyelashes01"
        s.details.keys.removeAll { it.startsWith(CommunityImporter.packId(n) + "/") }
        CommunityImporter.remove(filesDir, n)
        worker.execute {
            val keep = s.toJson(); lib = makeLibrary(); model = HumanModel(lib); model.state.load(keep)
            val i = lib.skins.indexOfFirst { it.texture == skinTex }; if (i >= 0) model.state.skin = i else { model.state.autoSkin = true; model.state.skin = model.matchSkin() }
            model.rebuild(); val parts = PartBuilder.build(model, false)
            runOnUiThread { view3d.reload(lib); view3d.show(parts); updateInfo(); saveLast(); communityScreen() }
        }
    }

    // ------------------------------------------------------------------ animations
    private var playing: String? = null
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private var tickRun: Runnable? = null

    private fun playClip(name: String) {
        val al = lib.anims ?: return; val c = al.clip(name) ?: return
        stopTicker(); playing = name
        worker.execute {
            val parts = PartBuilder.build(model, true)
            val heads = PartBuilder.jointHeads(model).toList()
            var floor = Float.MAX_VALUE; for (i in 0 until 13380) floor = minOf(floor, model.neutral[i * 3 + 1])
            val parents = IntArray(lib.rig.bones.size) { lib.rig.bones[it].parent }
            val posed = al.fit(c, heads, parents, floor)
            runOnUiThread {
                if (playing != name) return@runOnUiThread
                view3d.show(parts)
                val mats = FloatArray(heads.size * 16); val start = System.nanoTime()
                val r = object : Runnable { override fun run() {
                    if (playing != name) return
                    val t = (System.nanoTime() - start) / 1e9f
                    // one-shot clips play, hold for half a second, then repeat
                    val span = if (c.loops) c.duration else c.duration + 0.5f
                    val f = ((t % span.coerceAtLeast(0.05f)) * c.fps).toInt().coerceIn(0, posed.frames - 1)
                    AnimLibrary.skinMatrices(posed, f, heads, mats); view3d.setBones(mats.copyOf())
                    ticker.postDelayed(this, 33)
                } }
                tickRun = r; ticker.post(r)
            }
        }
        if (tab == "Animations") showTab("Animations")
    }
    private fun stopTicker() { tickRun?.let { ticker.removeCallbacks(it) }; tickRun = null }
    private fun stopClip() {
        stopTicker(); playing = null; view3d.setBones(null)
        worker.execute { val parts = PartBuilder.build(model, false); runOnUiThread { if (playing == null) view3d.show(parts) } }
    }

    private fun animationsTab() {
        val s = model.state; val al = lib.anims
        if (al == null) { content.addView(TextView(this).apply { setTextColor(fg); text = "No animations found in this build." }); return }
        content.addView(TextView(this).apply { setTextColor(dim); textSize = 12f; setPadding(0, dp(8), 0, dp(4))
            text = "Tap a name to play it on your character. Tick the ones to include when you export the .glb (Save tab). " +
                "Animations: Universal Animation Library 1 and 2 by Quaternius (CC0: free to use, no credit needed)." })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(chip("Stop", playing == null) { stopClip(); showTab("Animations") })
        row.addView(chip("Basics only", false) { s.anims.clear(); s.anims.addAll(DEFAULT_ANIMS); saveLast(); showTab("Animations") })
        row.addView(chip("All", false) { s.anims.clear(); s.anims.addAll(al.clips.map { it.name }); saveLast(); showTab("Animations") })
        row.addView(chip("None", false) { s.anims.clear(); saveLast(); showTab("Animations") })
        content.addView(HorizontalScrollView(this).apply { addView(row) })
        header("${s.anims.count { n -> al.clip(n) != null }} selected for export")
        for ((set, title) in listOf(1 to "Moving, actions and everyday", 2 to "Combat, parkour, farming and more")) {
            header(title)
            for (c in al.clips.filter { it.set == set }.sortedBy { it.label }) {
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val on = c.name in s.anims
                line.addView(TextView(this).apply { text = if (on) "☑" else "☐"; textSize = 20f; setTextColor(if (on) accent else dim); setPadding(dp(4), dp(6), dp(12), dp(6))
                    setOnClickListener { if (!s.anims.remove(c.name)) s.anims.add(c.name); saveLast(); showTab("Animations") } })
                line.addView(TextView(this).apply { text = (if (playing == c.name) "▶  " else "") + c.label + "   " + "%.1fs".format(c.duration); textSize = 15f
                    setTextColor(if (playing == c.name) accent else fg); setPadding(0, dp(8), dp(4), dp(8))
                    setOnClickListener { playClip(c.name) } }, LinearLayout.LayoutParams(0, -2, 1f))
                content.addView(line)
            }
        }
    }

    // ------------------------------------------------------------------ scene (posing characters together for still images)
    private val sceneChars = ArrayList<SceneChar>()
    private var selChar = 0; private var selBone = -1
    private var sceneFloor = "floor_wood"; private var sceneBed = "None"; private var sceneLight = 0
    private var sceneTransparent = false; private var sceneRes = 0
    private val sceneDirty = AtomicBoolean(false)
    private var sceneParts: List<Part> = emptyList(); private var sceneMarker: List<Part> = emptyList()
    private val sceneJoints = HashMap<SceneChar, FloatArray>()
    private val RES = listOf(Triple("1920 × 1080", 1920, 1080), Triple("2560 × 1440", 2560, 1440), Triple("3840 × 2160 (4K)", 3840, 2160), Triple("Portrait 1080 × 1920", 1080, 1920))
    private val FLOORS = linkedMapOf("None" to "", "Wood" to "floor_wood", "Tatami" to "floor_tatami", "Stone" to "floor_stone")
    private val BEDS = listOf("None", "Futon", "Bed")

    private fun sceneTextures() {
        val dir = java.io.File(filesDir, "imported/scene"); dir.mkdirs()
        for ((n, c) in SceneTextures.COLOURS + ("marker" to accent)) {
            val f = java.io.File(dir, "$n.png"); if (f.exists()) continue
            val b = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888); b.eraseColor(c)
            f.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun sceneProps(): List<Part> {
        val out = ArrayList<Part>(); val t = SceneTextures::path
        if (sceneFloor.isNotEmpty()) out += SceneProps.box("floor", 0f, -0.25f, 0f, 80f, 0.5f, 80f, t(sceneFloor))
        when (sceneBed) {
            "Futon" -> { out += SceneProps.box("futon", 0f, 0.6f, 0f, 10f, 1.2f, 20f, t("bed_sheet")); out += SceneProps.box("pillow", 0f, 1.6f, -8.2f, 6f, 0.9f, 3f, t("bed_pillow")) }
            "Bed" -> {
                out += SceneProps.box("bedframe", 0f, 2f, 0f, 15f, 4f, 22f, t("bed_frame")); out += SceneProps.box("headboard", 0f, 6f, -11.2f, 15f, 8f, 0.6f, t("bed_frame"))
                out += SceneProps.box("mattress", 0f, 4.9f, 0.3f, 14f, 1.8f, 20.5f, t("bed_sheet"))
                out += SceneProps.box("pillow1", -3.3f, 6.25f, -8.4f, 5.5f, 0.9f, 3f, t("bed_pillow")); out += SceneProps.box("pillow2", 3.3f, 6.25f, -8.4f, 5.5f, 0.9f, 3f, t("bed_pillow"))
            }
        }
        return out
    }

    private fun sceneChanged() {
        if (sceneDirty.getAndSet(true)) return
        worker.execute {
            sceneDirty.set(false)
            val all = ArrayList<Part>(); val al = lib.anims; val joints = HashMap<SceneChar, FloatArray>()
            for (c in sceneChars.toList()) { val (ps, posed) = ScenePoser.parts(c, al); all += ps; joints[c] = posed.pos }
            all += sceneProps()
            val c = sceneChars.getOrNull(selChar); val jp = c?.let { joints[it] }
            val marker = if (c != null && jp != null && selBone >= 0) listOf(SceneProps.box("marker", jp[selBone * 3], jp[selBone * 3 + 1], jp[selBone * 3 + 2], 0.7f, 0.7f, 0.7f, SceneTextures.path("marker"))) else emptyList()
            runOnUiThread { if (tab != "Scene") return@runOnUiThread; sceneParts = all; sceneMarker = marker; sceneJoints.clear(); sceneJoints.putAll(joints); view3d.show(all + marker) }
        }
    }

    private fun enterScene() {
        if (playing != null) stopClip()
        sceneTextures(); view3d.setBones(null); view3d.lighting = HumanView.PRESETS[sceneLight]
        view3d.onTap = { x, y -> pickJoint(x, y) }
        if (sceneChars.isEmpty()) {
            val keep = model.state.toJson()
            worker.execute {
                val m = HumanModel(lib); m.state.load(keep); m.rebuild()
                runOnUiThread { sceneChars += SceneChar(m).apply { x = -0.4f; yaw = 25f }; selChar = 0; view3d.dist = 42f; view3d.targetY = 8.5f; sceneChanged(); if (tab == "Scene") showTab("Scene") }
            }
        } else sceneChanged()
    }
    private fun leaveScene() {
        view3d.onTap = null; view3d.lighting = HumanView.PRESETS[0]; view3d.targetX = 0f; view3d.targetZ = 0f; view3d.fov = 35f
        changed()
    }

    /** Tap on the 3D view: select the nearest adjustable joint of either character. */
    private fun pickJoint(x: Float, y: Float) {
        val vp = view3d.viewProj(); var best = dp(40).toFloat(); var hit: Pair<Int, Int>? = null
        sceneChars.forEachIndexed { ci, c ->
            val jp = sceneJoints[c] ?: return@forEachIndexed; val bones = c.model.lib.rig.bones
            for ((_, bn) in ScenePoser.BONES) {
                val b = bones.indexOfFirst { it.name == bn }; if (b < 0) continue
                val o = FloatArray(4); android.opengl.Matrix.multiplyMV(o, 0, vp, 0, floatArrayOf(jp[b * 3], jp[b * 3 + 1], jp[b * 3 + 2], 1f), 0)
                if (o[3] <= 0f) continue
                val sx = (o[0] / o[3] + 1f) / 2f * view3d.width; val sy = (1f - o[1] / o[3]) / 2f * view3d.height
                val d = Math.hypot((sx - x).toDouble(), (sy - y).toDouble()).toFloat()
                if (d < best) { best = d; hit = ci to b }
            }
        }
        hit?.let { (ci, b) -> selChar = ci; selBone = b; sceneChanged(); showTab("Scene") } ?: toast("Tap closer to a joint")
    }

    private fun myCharacters(): List<java.io.File> = java.io.File(filesDir, "characters").listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name.lowercase() } ?: emptyList()
    private fun saveToMyCharacters() {
        val d = java.io.File(filesDir, "characters"); d.mkdirs()
        java.io.File(d, safe(model.state.name) + ".json").writeText(model.state.toJson().toString())
        toast("${model.state.name} saved to My characters")
    }
    private fun addSceneChar(state: JSONObject) {
        if (sceneChars.size >= 2) { toast("A scene holds two characters"); return }
        worker.execute {
            val m = HumanModel(lib); m.state.load(state); m.rebuild()
            runOnUiThread { sceneChars += SceneChar(m).apply { x = 0.4f; yaw = -25f }; selChar = sceneChars.size - 1; selBone = -1; sceneChanged(); showTab("Scene") }
        }
    }

    private fun sceneTab() {
        val c = sceneChars.getOrNull(selChar)
        content.addView(TextView(this).apply { setTextColor(dim); textSize = 12f; setPadding(0, dp(8), 0, dp(4))
            text = "Pose up to two characters together and render a still picture (for Ren'Py and other visual novels). Drag to turn the view, pinch to zoom, tap a joint to pick it." })
        if (c == null) { content.addView(TextView(this).apply { setTextColor(fg); text = "Setting up…" }); return }

        header("Characters")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sceneChars.forEachIndexed { i, sc -> row.addView(chip(sc.label, i == selChar) { selChar = i; selBone = -1; sceneChanged(); showTab("Scene") }) }
        if (sceneChars.size < 2) row.addView(chip("+ Add", false) {
            val files = myCharacters()
            val names = arrayOf("A copy of the character you're editing") + files.map { it.name.removeSuffix(".json") } + "From a saved .json file…"
            AlertDialog.Builder(this).setTitle("Add a character").setItems(names) { _, w ->
                when {
                    w == 0 -> addSceneChar(model.state.toJson())
                    w == names.size - 1 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, REQ_SCENE_CHAR)
                    else -> runCatching { addSceneChar(JSONObject(files[w - 1].readText())) }.onFailure { toast("Couldn't read that character") }
                }
            }.show()
        })
        if (sceneChars.size > 1) row.addView(chip("Remove ${c.label}", false) { sceneChars.removeAt(selChar); selChar = 0; selBone = -1; sceneChanged(); showTab("Scene") })
        content.addView(HorizontalScrollView(this).apply { addView(row) })
        button("Save the character you're editing to My characters") { saveToMyCharacters() }

        header("Place ${c.label}")
        slider("Left / right", c.x, -2f, 2f, { "%.2f m".format(it) }) { c.x = it; sceneChanged() }
        slider("Back / front", c.z, -2f, 2f, { "%.2f m".format(it) }) { c.z = it; sceneChanged() }
        slider("Raise", c.y, 0f, 1.5f, { "%.2f m".format(it) }) { c.y = it; sceneChanged() }
        slider("Turn", c.yaw, -180f, 180f, { "${it.toInt()}°" }) { c.yaw = it; sceneChanged() }

        header("Pose")
        val al = lib.anims; val clip = c.pose.clip?.let { al?.clip(it) }
        button("Starting pose: " + (clip?.label ?: "Standing (rest pose)")) {
            if (al == null) return@button
            val list = al.clips.sortedBy { it.label }
            AlertDialog.Builder(this).setTitle("Start from").setItems((listOf("Standing (rest pose)") + list.map { it.label }).toTypedArray()) { _, w ->
                c.pose.clip = if (w == 0) null else list[w - 1].name; c.pose.time = 0f; c.fitted = null; sceneChanged(); showTab("Scene")
            }.show()
        }
        if (clip != null) slider("Moment in the animation", c.pose.time, 0f, clip.duration.coerceAtLeast(0.01f), { "%.2fs".format(it) }) { c.pose.time = it; sceneChanged() }

        val bones = c.model.lib.rig.bones
        val friendly = ScenePoser.BONES.associate { (n, b) -> b to n }
        val selName = if (selBone >= 0) friendly[bones[selBone].name] ?: bones[selBone].name.removePrefix("mixamorig:") else null
        button("Joint: " + (selName ?: "tap one on the model, or choose")) {
            val ids = ScenePoser.BONES.map { (_, b) -> bones.indexOfFirst { it.name == b } }
            AlertDialog.Builder(this).setTitle("Joint").setItems(ScenePoser.BONES.map { it.first }.toTypedArray()) { _, w -> selBone = ids[w]; sceneChanged(); showTab("Scene") }.show()
        }
        if (selBone >= 0) {
            val d = c.pose.offsets[selBone] ?: FloatArray(3)
            val ax = ScenePoser.axes(bones[selBone].name)
            fun set(k: Int, v: Float) { val a = c.pose.offsets.getOrPut(selBone) { FloatArray(3) }; a[k] = v * ax.signs[k]; if (a.all { it == 0f }) c.pose.offsets.remove(selBone); sceneChanged() }
            for (k in 0..2) slider(ax.labels[k], d[k] * ax.signs[k], -180f, 180f, { "${it.toInt()}°" }) { set(k, it) }
            button("Reset this joint") { c.pose.offsets.remove(selBone); sceneChanged(); showTab("Scene") }
        }
        val mrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mrow.addView(chip("Copy left → right", false) { ScenePoser.mirror(c, "Left"); sceneChanged(); showTab("Scene") })
        mrow.addView(chip("Copy right → left", false) { ScenePoser.mirror(c, "Right"); sceneChanged(); showTab("Scene") })
        mrow.addView(chip("Reset whole pose", false) { c.pose.offsets.clear(); sceneChanged(); showTab("Scene") })
        content.addView(HorizontalScrollView(this).apply { addView(mrow) })
        slider("Left hand: curl fingers", ScenePoser.curlOf(c, "Left"), 0f, 1f, ::pct) { ScenePoser.setCurl(c, "Left", it); sceneChanged() }
        slider("Right hand: curl fingers", ScenePoser.curlOf(c, "Right"), 0f, 1f, ::pct) { ScenePoser.setCurl(c, "Right", it); sceneChanged() }

        header("Face")
        val frow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((n, e) in presets) frow.addView(chip(n, c.model.state.expression == e.filterValues { it > 0f }) {
            c.model.state.expression.clear(); c.model.state.expression.putAll(e); c.invalidate(); sceneChanged(); showTab("Scene") })
        content.addView(HorizontalScrollView(this).apply { addView(frow) })

        header("Clothes and hair")
        val st = c.model.state
        val items = st.clothes.map { it to (lib.proxies.firstOrNull { p -> p.id == it }?.label ?: it) } +
            listOfNotNull(st.hair?.let { it to "Hair" }, st.eyebrows?.let { it to "Eyebrows" })
        if (items.isEmpty()) content.addView(TextView(this).apply { setTextColor(dim); text = "Nothing to take off." })
        for ((id, label) in items) {
            val on = id !in c.hidden
            content.addView(TextView(this).apply { text = (if (on) "☑  " else "☐  ") + label; textSize = 15f; setTextColor(if (on) accent else fg); setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { if (!c.hidden.remove(id)) c.hidden.add(id); c.invalidate(); sceneChanged(); showTab("Scene") } })
        }
        if (st.clothes.isNotEmpty()) {
            val crow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            crow.addView(chip("Take all clothes off", false) { c.hidden.addAll(st.clothes); c.invalidate(); sceneChanged(); showTab("Scene") })
            crow.addView(chip("Put everything on", false) { c.hidden.clear(); c.invalidate(); sceneChanged(); showTab("Scene") })
            content.addView(HorizontalScrollView(this).apply { addView(crow) })
        }

        header("Set")
        val srow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((n, f) in FLOORS) srow.addView(chip("Floor: $n", sceneFloor == f) { sceneFloor = f; sceneChanged(); showTab("Scene") })
        content.addView(HorizontalScrollView(this).apply { addView(srow) })
        val brow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (b in BEDS) brow.addView(chip(if (b == "None") "No bed" else b, sceneBed == b) { sceneBed = b; sceneChanged(); showTab("Scene") })
        content.addView(HorizontalScrollView(this).apply { addView(brow) })
        content.addView(TextView(this).apply { setTextColor(dim); textSize = 12f; text = "To lie someone down, pick Hips and bend them 90°, then Raise them onto the bed." })

        header("Lighting")
        val lrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        HumanView.PRESETS.forEachIndexed { i, l -> lrow.addView(chip(l.name, sceneLight == i) { sceneLight = i; view3d.lighting = l; showTab("Scene") }) }
        content.addView(HorizontalScrollView(this).apply { addView(lrow) })

        header("Camera")
        slider("Zoom (lens)", view3d.fov, 15f, 60f, { "${it.toInt()}°" }) { view3d.fov = it; view3d.requestRender() }
        slider("Look height", view3d.targetY, 0f, 20f, { "%.1f m".format(it / 10f) }) { view3d.targetY = it; view3d.requestRender() }
        button("Centre on the characters") {
            view3d.targetX = sceneChars.map { it.x * 10f }.average().toFloat(); view3d.targetZ = sceneChars.map { it.z * 10f }.average().toFloat(); view3d.requestRender()
        }

        header("Render a picture")
        val rrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        RES.forEachIndexed { i, r -> rrow.addView(chip(r.first, sceneRes == i) { sceneRes = i; showTab("Scene") }) }
        content.addView(HorizontalScrollView(this).apply { addView(rrow) })
        switch("See-through background (for character sprites)", sceneTransparent) { sceneTransparent = it }
        button("Render PNG") { create(REQ_RENDER, "${safe(sceneChars.joinToString(" and ") { it.label })} scene.png", "image/png") }

        header("Scenes")
        button("Save this scene") {
            val name = EditText(this).apply { setText(sceneChars.joinToString(" and ") { it.label }) }
            AlertDialog.Builder(this).setTitle("Scene name").setView(name).setPositiveButton("Save") { _, _ ->
                ScenePoser.saveScene(java.io.File(filesDir, "scenes"), name.text.toString().ifBlank { "Scene" }, sceneChars, sceneExtra()); toast("Scene saved"); showTab("Scene")
            }.setNegativeButton("Cancel", null).show()
        }
        for (f in java.io.File(filesDir, "scenes").listFiles { x -> x.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(TextView(this).apply { text = "○  " + f.name.removeSuffix(".json"); textSize = 15f; setTextColor(fg); setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { loadScene(f) } }, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(TextView(this).apply { text = "Delete"; setTextColor(accent); textSize = 14f; setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { f.delete(); showTab("Scene") } })
            content.addView(line)
        }
    }
    // (the Switch inside switch() also calls changed(); harmless: changed() leaves the scene view alone)

    private fun sceneExtra() = JSONObject().put("floor", sceneFloor).put("bed", sceneBed).put("light", sceneLight).put("transparent", sceneTransparent)
        .put("cam", JSONObject().put("yaw", view3d.yaw.toDouble()).put("pitch", view3d.pitch.toDouble()).put("dist", view3d.dist.toDouble())
            .put("tx", view3d.targetX.toDouble()).put("ty", view3d.targetY.toDouble()).put("tz", view3d.targetZ.toDouble()).put("fov", view3d.fov.toDouble()))

    private fun loadScene(f: java.io.File) {
        val j = runCatching { JSONObject(f.readText()) }.getOrNull() ?: run { toast("Couldn't read that scene"); return }
        worker.execute {
            val chars = runCatching { (0 until j.getJSONArray("chars").length()).map { SceneChar.fromJson(lib, j.getJSONArray("chars").getJSONObject(it)) } }.getOrNull()
            runOnUiThread {
                if (chars == null) { toast("Couldn't read that scene"); return@runOnUiThread }
                sceneChars.clear(); sceneChars.addAll(chars); selChar = 0; selBone = -1
                sceneFloor = j.optString("floor", "floor_wood"); sceneBed = j.optString("bed", "None"); sceneLight = j.optInt("light", 0).coerceIn(0, HumanView.PRESETS.size - 1)
                sceneTransparent = j.optBoolean("transparent"); view3d.lighting = HumanView.PRESETS[sceneLight]
                j.optJSONObject("cam")?.let { cm -> view3d.yaw = cm.optDouble("yaw").toFloat(); view3d.pitch = cm.optDouble("pitch").toFloat(); view3d.dist = cm.optDouble("dist").toFloat()
                    view3d.targetX = cm.optDouble("tx").toFloat(); view3d.targetY = cm.optDouble("ty").toFloat(); view3d.targetZ = cm.optDouble("tz").toFloat(); view3d.fov = cm.optDouble("fov", 35.0).toFloat() }
                sceneChanged(); showTab("Scene"); toast("Loaded ${j.optString("name")}")
            }
        }
    }

    private fun renderScene(uri: Uri) {
        val (_, w, h) = RES[sceneRes]
        val dlg = AlertDialog.Builder(this).setMessage("Rendering ${w} × ${h}…").setCancelable(false).show()
        view3d.show(sceneParts)    // no joint marker in the picture
        view3d.renderImage(w, h, sceneTransparent) { bmp ->
            view3d.show(sceneParts + sceneMarker)
            if (bmp == null) { dlg.dismiss(); toast("Render failed"); return@renderImage }
            worker.execute {
                val err = runCatching { contentResolver.openOutputStream(uri)!!.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }.exceptionOrNull()
                bmp.recycle()
                runOnUiThread { dlg.dismiss(); toast(if (err == null) "Picture saved" else "Couldn't save: ${err.message}") }
            }
        }
    }

    // ------------------------------------------------------------------ replacement head from a 3D model
    private var headRaw: RawMesh? = null; private var headPrep: HeadSwap.Prepared? = null
    private var headParams = HeadSwap.Params(); private var headName = "Head"; private var headPreviewTex: String? = null
    private var headBody: List<Part> = emptyList()
    private val headDirty = AtomicBoolean(false)

    private fun headSection(s: HumanState) {
        header("Head")
        s.customHead?.let { id ->
            content.addView(TextView(this).apply { setTextColor(dim); textSize = 13f; setPadding(0, 0, 0, dp(6))
                text = "Using a head from a 3D model: ${model.customHead?.name ?: id}. It moves with the skeleton and these expressions, in the app and in exports." })
            button("Back to the PocketHuman head") { s.customHead = null; changed(); saveLast(); showTab("Face") }
        }
        button("Replace the head with one from a 3D model (.glb / .obj)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, REQ_HEAD)
        }
        for ((hid, hname) in HeadStore.list(filesDir)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val on = s.customHead == hid
            row.addView(TextView(this).apply { text = (if (on) "●  " else "○  ") + hname; textSize = 15f; setTextColor(if (on) accent else fg); setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { s.customHead = if (on) null else hid; model.forgetHead(); changed(); saveLast(); showTab("Face") } }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply { text = "Delete"; setTextColor(accent); textSize = 14f; setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { AlertDialog.Builder(this@MainActivity).setMessage("Delete $hname?").setPositiveButton("Delete") { _, _ ->
                    if (s.customHead == hid) s.customHead = null; HeadStore.delete(filesDir, hid); model.forgetHead(); changed(); saveLast(); showTab("Face") }.setNegativeButton("Cancel", null).show() } })
            content.addView(row)
        }
    }

    private fun startHeadImport(f: java.io.File) {
        val wait = AlertDialog.Builder(this).setMessage("Loading model…").setCancelable(false).show()
        if (model.state.customModel != null) { model.state.customModel = null }
        // fitting needs the neutral face and the PocketHuman head
        model.state.expression.clear(); model.state.customHead = null; model.rebuild()
        worker.execute {
            val r = runCatching {
                // keep the full detail for the head (it's cut out first, then thinned only if still huge)
                var m = MeshIO.load(f); if (m.tris.size / 3 > 1_500_000) m = MeshIO.simplify(m, 1_000_000)
                headRaw = m; headParams = HeadSwap.Params()
                headName = f.nameWithoutExtension.replace("Meshy_AI_", "").replace(Regex("_\\d{8}.*$"), "").replace('_', ' ').take(40).ifBlank { "Head" }
                headPrep = HeadSwap.prepare(m, 0, model)
                val dir = java.io.File(filesDir, "imported/my_heads/preview").apply { deleteRecursively(); mkdirs() }
                val stamp = System.currentTimeMillis().toString(36)     // a new name each time: the 3D view keeps textures by name
                headPreviewTex = if (m.texture != null) { java.io.File(dir, "tex$stamp.${m.textureExt}").writeBytes(m.texture); "imported/my_heads/preview/tex$stamp.${m.textureExt}" }
                    else { java.io.File(dir, "grey.png").writeBytes(OutfitDraft.GREY_PNG); "imported/my_heads/preview/grey.png" }
                val ref = HeadSwap.appRef(model)
                val hide = BooleanArray(lib.base.nV); HeadSwap.hiddenVerts(model, ref).forEach { hide[it] = true }
                val st = model.state
                headBody = PartBuilder.build(model, false, hide, setOfNotNull(st.hair, st.eyebrows, st.eyelashes, "eyes_high-poly", "teeth_teeth_base", "tongue_tongue01"))
            }
            runOnUiThread { wait.dismiss(); r.onSuccess { tabBar.visibility = View.GONE; headEditor(); refreshHead() }
                .onFailure { AlertDialog.Builder(this).setTitle("Couldn't use that model").setMessage(it.message ?: it.toString()).setPositiveButton("OK", null).show(); changed() } }
        }
    }

    private fun refreshHead() {
        if (headDirty.getAndSet(true)) return
        worker.execute {
            headDirty.set(false)
            val prep = headPrep ?: return@execute
            val pos = HeadSwap.place(prep, HeadSwap.appRef(model), headParams)
            val part = Part("head_preview", pos, weldedNormals(pos, prep.tris, prep.n), prep.uv, prep.tris, headPreviewTex!!, false, null, IntArray(0), FloatArray(0), IntArray(0), FloatArray(0), 0)
            runOnUiThread { if (headPrep != null) view3d.show(headBody + part) }
        }
    }

    private fun closeHeadEditor() { headRaw = null; headPrep = null; headBody = emptyList(); tabBar.visibility = View.VISIBLE; showTab("Face"); changed() }

    private fun headEditor() {
        content.removeAllViews()
        val p = headParams
        content.addView(TextView(this).apply { text = "Line up the new head"; setTextColor(accent); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, dp(4)) })
        content.addView(TextView(this).apply { setTextColor(dim); textSize = 12f
            text = "The head has been cut off at the neck and placed on the body. Adjust it until the neck lines up, then tap Make the head. Its skin is matched to the body and the neck seam is blended in." })
        content.addView(EditText(this).apply { setText(headName); setTextColor(fg); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { headName = e.toString().ifBlank { "Head" } }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, e: Int) {}; override fun onTextChanged(a: CharSequence?, b: Int, c: Int, e: Int) {} }) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(chip("Turn 90°", false) {
            p.turn = (p.turn + 1) % 4; val raw = headRaw ?: return@chip
            worker.execute { val pr = runCatching { HeadSwap.prepare(raw, p.turn, model) }.getOrNull(); runOnUiThread { if (pr != null) { headPrep = pr; refreshHead() } else toast("Couldn't find a head facing that way") } }
        })
        row.addView(chip("Reset", false) { val t = p.turn; headParams = HeadSwap.Params().apply { turn = t }; headEditor(); refreshHead() })
        content.addView(HorizontalScrollView(this).apply { addView(row) })
        slider("Size", p.size, 0.6f, 1.6f, ::pct) { p.size = it; refreshHead() }
        slider("Up / down", p.up, -1.5f, 1.5f, { "%.1f cm".format(it * 10) }) { p.up = it; refreshHead() }
        slider("Forward / back", p.forward, -1f, 1f, { "%.1f cm".format(it * 10) }) { p.forward = it; refreshHead() }
        slider("Left / right", p.side, -1f, 1f, { "%.1f cm".format(it * 10) }) { p.side = it; refreshHead() }
        slider("Nod", p.tilt, -30f, 30f, { "${it.toInt()}°" }) { p.tilt = it; refreshHead() }
        header("Skin")
        val mrow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mrow.addView(chip("Head to body", !p.bodyToHead) { p.bodyToHead = false; headEditor() })
        mrow.addView(chip("Body to head", p.bodyToHead) { p.bodyToHead = true; headEditor() })
        content.addView(HorizontalScrollView(this).apply { addView(mrow) })
        content.addView(TextView(this).apply { setTextColor(dim); textSize = 12f
            text = if (p.bodyToHead) "The body is tinted to the head's skin tone (if you change the skin later, the body goes back to that skin)." else "The head's skin is tinted to the body's tone." })
        slider("How closely to match", p.skinMatch, 0f, 1f, ::pct) { p.skinMatch = it }
        header("Eyes")
        content.addView(Switch(this).apply { text = "Put in the app's eyes and eyelids (they can blink and look around)"; setTextColor(fg); isChecked = p.appEyes
            setOnCheckedChangeListener { _, c -> p.appEyes = c; headEditor() } })
        if (p.appEyes) {
            content.addView(TextView(this).apply { setTextColor(dim); textSize = 12f
                text = "They're placed over the painted eyes automatically. If they end up off, nudge them here and make the head again." })
            slider("Eyes up / down", p.eyeUp, -0.6f, 0.6f, { "%.1f cm".format(it * 10) }) { p.eyeUp = it }
            slider("Eyes apart", p.eyeApart, -0.4f, 0.4f, { "%.1f cm".format(it * 10) }) { p.eyeApart = it }
            slider("Eyes in / out", p.eyeDepth, -0.4f, 0.4f, { "%.1f cm".format(it * 10) }) { p.eyeDepth = it }
            slider("Eyelid shade (lighter / darker than the face)", p.lidShade, 0.5f, 1.3f, ::pct) { p.lidShade = it }
        } else content.addView(TextView(this).apply { setTextColor(dim); textSize = 12f; text = "The painted eyes stay. Smiles, brows and lips still move; blinking won't look right." })
        button("Make the head") { makeHead() }
        button("Cancel") { closeHeadEditor() }
    }

    private fun makeHead() {
        val prep = headPrep ?: return
        val msg = TextView(this).apply { text = "Fitting the head…"; setTextColor(fg); setPadding(dp(24), dp(20), dp(24), dp(20)) }
        val dlg = AlertDialog.Builder(this).setView(msg).setCancelable(false).show()
        val id = "h" + System.currentTimeMillis().toString(36)
        val name = headName; val p = headParams
        worker.execute {
            val r = runCatching {
                HeadSwap.build(model, prep, p, id, name, filesDir, AndroidImageDecoder, AndroidImageWriter) { line -> runOnUiThread { msg.text = line } }
            }
            runOnUiThread {
                dlg.dismiss()
                r.onSuccess {
                    model.state.customHead = id; model.forgetHead()
                    java.io.File(filesDir, "imported/my_heads/preview").deleteRecursively()
                    view3d.reload(lib); closeHeadEditor(); saveLast(); toast("$name is on. Try the expressions below.")
                }.onFailure { AlertDialog.Builder(this).setTitle("Couldn't make the head").setMessage(it.message ?: it.toString()).setPositiveButton("OK", null).show() }
            }
        }
    }

    private fun runImport(title: String, open: ((String) -> Unit) -> java.io.InputStream, id: String, license: String) {
        val msg = TextView(this).apply { setTextColor(fg); setPadding(dp(20), dp(10), dp(20), dp(10)); text = "Starting…" }
        val dlg = AlertDialog.Builder(this).setTitle(title).setView(msg).setCancelable(false).show()
        worker.execute {
            val res = runCatching {
                open { t -> runOnUiThread { msg.text = t } }.use { PackImporter(filesDir, AndroidTextures).import(it, id, title, license) { t -> runOnUiThread { msg.text = t } } }
            }
            runOnUiThread {
                dlg.dismiss()
                res.onSuccess { r ->
                    val what = r.counts.entries.joinToString(", ") { "${it.value} ${it.key}" }
                    reloadLibrary { showTab("Packs"); AlertDialog.Builder(this).setTitle("Added").setMessage(what + if (r.skipped.isNotEmpty()) "\n\nSkipped ${r.skipped.size} item(s) that couldn't be used." else "").setPositiveButton("OK", null).show() }
                }.onFailure { AlertDialog.Builder(this).setTitle("Couldn't add pack").setMessage(it.message ?: it.toString()).setPositiveButton("OK", null).show() }
            }
        }
    }

    private fun downloadPack(p: org.json.JSONObject) {
        val total = p.optLong("size")
        runImport(p.getString("title"), { report ->
            var lastErr: Exception? = null
            for (u in listOf(p.getString("url"), p.optString("mirror"))) {
                if (u.isEmpty()) continue
                try {
                    val f = java.io.File(cacheDir, "pack.zip")
                    val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 20000; c.readTimeout = 60000; c.instanceFollowRedirects = true
                    c.inputStream.use { inp -> f.outputStream().use { o ->
                        val buf = ByteArray(1 shl 16); var n: Int; var got = 0L; var last = 0L
                        while (inp.read(buf).also { n = it } >= 0) { o.write(buf, 0, n); got += n
                            if (got - last > 1_000_000) { last = got; report("Downloading… ${got / 1_000_000} / ${maxOf(total / 1_000_000, 1)} MB") } }
                    } }
                    return@runImport object : java.io.FilterInputStream(f.inputStream()) { override fun close() { super.close(); f.delete() } }
                } catch (e: Exception) { lastErr = e }
            }
            throw lastErr ?: IllegalStateException("download failed")
        }, p.getString("id"), p.getString("license"))
    }

    private fun safe(n: String) = n.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Character" }

    private fun randomise() {
        val s = model.state; val r = Random(System.nanoTime())
        for (k in s.macro.keys) s.macro[k] = (0.5f + (r.nextFloat() - 0.5f) * 0.7f).coerceIn(0f, 1f)
        s.macro["age"] = 0.5f + r.nextFloat() * 0.25f
        s.macro["gender"] = if (r.nextBoolean()) r.nextFloat() * 0.1f else 0.9f + r.nextFloat() * 0.1f
        val a = r.nextFloat(); val b = r.nextFloat() * (1 - a)
        s.race["african"] = a; s.race["asian"] = b; s.race["caucasian"] = 1 - a - b
        s.details.clear()
        for (g in lib.defs.groups) if (g.name in setOf("nose", "mouth", "eyes", "chin", "cheek", "forehead", "eyebrows", "ears"))
            for (sl in g.sliders.shuffled(r).take(3)) s.details[sl.id] = (r.nextFloat() - 0.5f) * (if (sl.neg.isEmpty()) 0.6f else 0.8f)
        s.skin = model.matchSkin(); s.autoSkin = true
        lib.proxies.firstOrNull { it.kind == "eyes" }?.let { s.eyeColour = r.nextInt(it.variants.size.coerceAtLeast(1)) }
        val hairs = lib.ofKind("hair"); s.hair = hairs[r.nextInt(hairs.size)].id
        val brows = lib.ofKind("eyebrows"); s.eyebrows = brows[r.nextInt(brows.size)].id
        changed(); saveLast(); showTab(tab)
    }

    // ------------------------------------------------------------------ files
    private fun create(req: Int, name: String, mime: String) =
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = mime; putExtra(Intent.EXTRA_TITLE, name) }, req)

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_OUTFIT_TEX && (res != RESULT_OK || data?.data == null)) { pendingOutfit?.let { startOutfitImport(it, null) }; return }
        val uri: Uri = data?.data ?: return
        if (res != RESULT_OK) return
        when (req) {
            REQ_GLB -> {
                val dlg = ProgressBar(this)
                val d = AlertDialog.Builder(this).setTitle("Exporting…").setView(dlg).setCancelable(false).show()
                worker.execute {
                    val err = runCatching { contentResolver.openOutputStream(uri)!!.use { GlbExporter.write(model, it) } }.exceptionOrNull()
                    runOnUiThread { d.dismiss(); toast(if (err == null) "Exported" else "Export failed: ${err.message}") }
                }
            }
            REQ_RENDER -> renderScene(uri)
            REQ_HEAD -> {
                val f = java.io.File(cacheDir, displayName(uri).replace(Regex("[^A-Za-z0-9._-]"), "_"))
                contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                startHeadImport(f)
            }
            REQ_SCENE_CHAR -> runCatching { JSONObject(contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) }) }
                .onSuccess { addSceneChar(it) }.onFailure { toast("Couldn't read that file") }
            REQ_SAVE -> runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(model.state.toJson().toString(2).toByteArray()) } }
                .onSuccess { toast("Saved") }.onFailure { toast("Save failed: ${it.message}") }
            REQ_ASIS -> {
                val d = draft ?: return
                val dlg = AlertDialog.Builder(this).setMessage("Exporting…").setCancelable(false).show()
                worker.execute {
                    val err = runCatching {
                        val body = BodyQuery(model); val f = d.alignedOnly(model, body)
                        val (part, tex) = AsIsExport.part(model, body, d, f)
                        contentResolver.openOutputStream(uri)!!.use { GlbExporter.write(model, it, listOf(part), tex) }
                    }.exceptionOrNull()
                    runOnUiThread { dlg.dismiss(); toast(if (err == null) "Exported" else "Export failed: ${err.message}") }
                }
            }
            REQ_OUTFIT -> {
                val name = displayName(uri)
                val f = java.io.File(cacheDir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                if (!charMode) { AlertDialog.Builder(this).setTitle("What is it?").setItems(OutfitDraft.KINDS.toTypedArray()) { _, k -> pendingKind = k.toFloat(); afterKind(f) }.show() }
                else afterKind(f)
            }
            REQ_OUTFIT_TEX -> {
                val t = java.io.File(cacheDir, "outfit_tex." + displayName(uri).substringAfterLast('.', "png").lowercase())
                contentResolver.openInputStream(uri)!!.use { i -> t.outputStream().use { i.copyTo(it) } }
                pendingOutfit?.let { startOutfitImport(it, t) }
            }
            REQ_PACK -> {
                val name = (uri.lastPathSegment ?: "pack").substringAfterLast('/').substringAfterLast(':').removeSuffix(".zip")
                val id = name.lowercase().replace(Regex("(_cc0|_ccby|_cc-by)$"), "").replace(Regex("[^a-z0-9_]"), "_")
                val lic = if (Regex("cc-?by", RegexOption.IGNORE_CASE).containsMatchIn(name)) "CC-BY" else "as stated in the pack"
                runImport(name, { contentResolver.openInputStream(uri)!! }, id, lic)
            }
            REQ_LOAD -> runCatching {
                val txt = contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) }
                model.state.load(JSONObject(txt))
            }.onSuccess { changed(); saveLast(); showTab(tab); toast("Loaded") }.onFailure { toast("Couldn't read that file") }
        }
    }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun saveLast() = runCatching { openFileOutput("last.json", MODE_PRIVATE).use { it.write(model.state.toJson().toString().toByteArray()) } }
    private fun restoreLast() = runCatching { openFileInput("last.json").use { model.state.load(JSONObject(String(it.readBytes()))) } }

    override fun onPause() { super.onPause(); if (::view3d.isInitialized) view3d.onPause(); if (::model.isInitialized) saveLast() }
    override fun onResume() { super.onResume(); if (::view3d.isInitialized) view3d.onResume() }

    companion object { const val REQ_GLB = 1; const val REQ_SAVE = 2; const val REQ_LOAD = 3; const val REQ_PACK = 4; const val REQ_OUTFIT = 5; const val REQ_OUTFIT_TEX = 6; const val REQ_ASIS = 7; const val REQ_RENDER = 8; const val REQ_SCENE_CHAR = 9; const val REQ_HEAD = 10 }
}
