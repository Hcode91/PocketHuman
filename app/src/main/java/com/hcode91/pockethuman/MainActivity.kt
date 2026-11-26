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
            runOnUiThread { if (draft == null) view3d.show(parts); updateInfo() }
        }
    }
    private fun updateInfo() {
        info.text = "%s  ·  %.2f m  ·  age %d".format(model.state.name, model.heightMetres(), model.ageYears().toInt())
    }

    // ------------------------------------------------------------------ tabs
    private val tabs = listOf("Body", "Ethnicity", "Details", "Face", "Skin", "Eyes", "Hair", "Clothes", "Packs", "Save")
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
        val s = model.state
        when (t) {
            "Body" -> {
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
                    header("Female: pick a \"with genitals\" skin in the Skin tab. Size sliders are in Details → genitals.")
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
            text = "The .glb has a Mixamo-named skeleton (A-pose), skin weights, eyes, brows, lashes, teeth and hair, ready for the Pose Studio, Blender or Godot.\n\n" +
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
        button("Make it a PocketHuman character") { makeCharacter(d) }
        button("Export as-is, rigged (.glb)") { create(REQ_ASIS, "${safe(d.name)}_rigged.glb", "model/gltf-binary") }
        content.addView(TextView(this).apply { text = "Cancel"; setTextColor(dim); textSize = 15f; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)); setOnClickListener { closeEditor() } })
        content.addView(TextView(this).apply { setTextColor(dim); textSize = 12f
            text = "'Make it a PocketHuman character' adds three things you can change later: a body-shape slider (Details → My shapes), a skin painted from the model (Skin tab) and the model's clothes and hair as an outfit (Clothes tab). " +
                "'Export as-is' keeps the model exactly as it is and just adds the skeleton, so it can be animated, but its body can't be reshaped." })
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
        for (inst in lib.installed.filter { i -> catalog.none { it.getString("id") == i.id } }) {
            content.addView(TextView(this).apply { text = "${inst.title} (imported)  ·  ${inst.counts}  ·  tap to remove"; setTextColor(fg); textSize = 14f; setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { java.io.File(filesDir, "imported/${inst.id}").deleteRecursively(); reloadLibrary { showTab("Packs") } } })
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

    companion object { const val REQ_GLB = 1; const val REQ_SAVE = 2; const val REQ_LOAD = 3; const val REQ_PACK = 4; const val REQ_OUTFIT = 5; const val REQ_OUTFIT_TEX = 6; const val REQ_ASIS = 7 }
}
