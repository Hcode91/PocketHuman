package com.hcode91.pockethuman

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Downscales pack textures on the phone; keeps PNG only when the image really has transparency. */
object AndroidTextures : TextureSink {
    override fun write(src: File, dstDir: File, baseName: String, maxSize: Int): String? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSize) sample *= 2
        var bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val big = maxOf(bmp.width, bmp.height)
        if (big > maxSize) {
            val s = maxSize.toFloat() / big
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        }
        var alpha = false
        if (bmp.hasAlpha()) {
            val step = maxOf(1, bmp.width / 64)
            loop@ for (y in 0 until bmp.height step step) for (x in 0 until bmp.width step step) if ((bmp.getPixel(x, y) ushr 24) < 250) { alpha = true; break@loop }
        }
        dstDir.mkdirs()
        val name = baseName.replace(Regex("[^A-Za-z0-9_.-]"), "_") + if (alpha) ".png" else ".jpg"
        File(dstDir, name).outputStream().use { bmp.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        return name
    }
}

/** Colour lookups on a decoded image (downsampled to keep memory sensible). */
fun bitmapSampler(bytes: ByteArray, maxSize: Int = 2048): ColorSampler? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSize) s *= 2
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
    val w = bmp.width; val h = bmp.height; val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h); bmp.recycle()
    return ColorSampler { u, v -> px[(v.coerceIn(0f, 0.9999f) * h).toInt() * w + (u.coerceIn(0f, 0.9999f) * w).toInt()] }
}

object AndroidImageWriter : ImageWriter {
    override fun write(argb: IntArray, w: Int, h: Int, dir: File, baseName: String): String {
        dir.mkdirs()
        val bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        val name = "$baseName.jpg"
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle(); return name
    }
}
