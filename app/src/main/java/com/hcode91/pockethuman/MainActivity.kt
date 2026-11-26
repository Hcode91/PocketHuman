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
            lib = Library(assets.source()); model = HumanModel(lib)
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ model updates
    private fun changed() {
        if (dirty.getAndSet(true)) return
        worker.execute {
            dirty.set(false)
            model.rebuild()
            val parts = PartBuilder.build(model, false)
            runOnUiThread { view3d.show(parts); updateInfo() }
        }
    }
    private fun updateInfo() {
        info.text = "%s  ·  %.2f m  ·  age %d".format(model.state.name, model.heightMetres(), model.ageYears().toInt())
    }

    // ------------------------------------------------------------------ tabs
    private val tabs = listOf("Body", "Ethnicity", "Details", "Skin", "Eyes", "Hair", "Save")
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

    private fun showTab(t: String) {
        content.removeAllViews()
        val s = model.state
        when (t) {
            "Body" -> {
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
                header("Mix of the three base shapes (always adds up to 100%)")
                val bars = HashMap<String, SeekBar>()
                for (k in s.race.keys.toList()) bars[k] = slider(k, s.race[k]!!, 0f, 1f, ::pct) { x ->
                    s.setRace(k, x); for ((o, b) in bars) if (o != k) b.progress = (s.race[o]!! * 1000).toInt(); changed()
                }
            }
            "Details" -> {
                val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (g in lib.defs.groups) chips.addView(chip(g.name, g.name == detailGroup) { detailGroup = g.name; showTab("Details") })
                content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
                val g = lib.defs.groups.firstOrNull { it.name == detailGroup } ?: lib.defs.groups.first()
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
            "Skin" -> lib.skins.forEachIndexed { i, sk -> option(sk.first, s.skin == i) { s.skin = i } }
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
        content.addView(TextView(this).apply {
            setTextColor(dim); textSize = 12f; setPadding(0, dp(16), 0, 0)
            text = "The .glb has a Mixamo-named skeleton (A-pose), skin weights, eyes, brows, lashes, teeth and hair, ready for the Pose Studio, Blender or Godot.\n\n" +
                "Body, morphs, skins, eyes and hair come from the MakeHuman / MPFB2 community and are CC0, so anything you make is yours to use, including commercially."
        })
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
        s.skin = r.nextInt(lib.skins.size)
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

    companion object { const val REQ_GLB = 1; const val REQ_SAVE = 2; const val REQ_LOAD = 3 }
}
