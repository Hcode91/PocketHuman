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
