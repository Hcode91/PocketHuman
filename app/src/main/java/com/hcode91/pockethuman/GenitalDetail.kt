package com.hcode91.pockethuman

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes an image to ARGB pixels (Android: BitmapFactory; tests: anything). */
fun interface ImageDecoder { fun decode(bytes: ByteArray): Triple<Int, Int, IntArray>? }

/**
 * Painted female genital detail, taken from a CC0 MakeHuman skin as colour ratios against that skin's own
 * smoothed tone, and laid onto any skin: result = (target's smoothed tone) x ratio, blended in by a soft mask.
 */
class GenitalDetail(bytes: ByteArray) {
    private class Patch(val x: Int, val y: Int, val w: Int, val h: Int, val px: ByteArray)
    private val size: Int
    private val patches = ArrayList<Patch>()
    init {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); b.position(4)
        size = b.int; val n = b.int
        repeat(n) { val x = b.int; val y = b.int; val w = b.int; val h = b.int; val px = ByteArray(w * h * 4); b.get(px); patches.add(Patch(x, y, w, h, px)) }
    }

    /** Paints the detail into argb (w x h) in place. */
    fun apply(argb: IntArray, w: Int, h: Int) {
        val sx = w.toFloat() / size; val sy = h.toFloat() / size
        val r = maxOf(2, (70 * sx).toInt())                                   // smoothing radius, same as when the layer was made
        for (p in patches) {
            val x0 = (p.x * sx).toInt(); val y0 = (p.y * sy).toInt(); val x1 = minOf(w, ((p.x + p.w) * sx).toInt()); val y1 = minOf(h, ((p.y + p.h) * sy).toInt())
            // smoothed tone of the target around the patch: three box blurs over the patch plus a margin
            val bx0 = maxOf(0, x0 - 3 * r); val by0 = maxOf(0, y0 - 3 * r); val bx1 = minOf(w, x1 + 3 * r); val by1 = minOf(h, y1 + 3 * r)
            val bw = bx1 - bx0; val bh = by1 - by0
            val ch = Array(3) { c -> FloatArray(bw * bh) { i -> ((argb[(by0 + i / bw) * w + bx0 + i % bw] shr (16 - 8 * c)) and 255).toFloat() } }
            for (c in 0..2) repeat(3) { boxBlur(ch[c], bw, bh, r / 2 + 1) }
            for (y in y0 until y1) for (x in x0 until x1) {
                val u = ((x / sx).toInt() - p.x).coerceIn(0, p.w - 1); val v = ((y / sy).toInt() - p.y).coerceIn(0, p.h - 1)
                val q = (v * p.w + u) * 4; val m = (p.px[q + 3].toInt() and 255) / 255f; if (m <= 0f) continue
                val i = y * w + x; val bi = (y - by0) * bw + (x - bx0); val old = argb[i]; var out = old and 0xff000000.toInt()
                for (c in 0..2) {
                    val ratio = (p.px[q + c].toInt() and 255) / 127.5f
                    val o = ((old shr (16 - 8 * c)) and 255).toFloat()
                    val nv = (ch[c][bi] * ratio).coerceIn(0f, 255f)
                    out = out or (((o + (nv - o) * m).toInt().coerceIn(0, 255)) shl (16 - 8 * c))
                }
                argb[i] = out
            }
        }
    }

    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int) {
        val t = FloatArray(a.size)
        for (y in 0 until h) { var s = 0f; val row = y * w
            for (x in -r..r) s += a[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) { t[row + x] = s / (2 * r + 1); s += a[row + (x + r + 1).coerceAtMost(w - 1)] - a[row + (x - r).coerceAtLeast(0)] } }
        for (x in 0 until w) { var s = 0f
            for (y in -r..r) s += t[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) { a[y * w + x] = s / (2 * r + 1); s += t[(y + r + 1).coerceAtMost(h - 1) * w + x] - t[(y - r).coerceAtLeast(0) * w + x] } }
    }

    companion object {
        /** Makes (or reuses) a copy of a skin texture with the detail painted in. Returns its texture path, or null. */
        fun skinWithDetail(lib: Library, skinTexture: String, filesDir: File, decoder: ImageDecoder, writer: ImageWriter): String? {
            val name = "g_" + skinTexture.replace(Regex("[^A-Za-z0-9]"), "_").takeLast(80)
            val dir = File(filesDir, "imported/genital_skins")
            dir.listFiles()?.firstOrNull { it.nameWithoutExtension == name }?.let { return "imported/genital_skins/${it.name}" }
            val det = runCatching { GenitalDetail(lib.am.bytes("genital_detail.bin")) }.getOrNull() ?: return null
            val (w, h, px) = decoder.decode(lib.am.bytes(skinTexture)) ?: return null
            det.apply(px, w, h)
            return "imported/genital_skins/" + writer.write(px, w, h, dir, name)
        }
    }
}
