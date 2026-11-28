package com.hcode91.pockethuman

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * The stencil picture, drawn see-through over the 3D view. While [moving] it takes the touches: one finger moves it,
 * two fingers pinch to size it and twist to turn it. Otherwise touches pass through to the 3D view underneath.
 */
class StencilOverlay(ctx: Context) : View(ctx) {
    var picture: Bitmap? = null
        set(v) { field = v; if (v != null) fit(); invalidate() }
    val picMatrix = Matrix()
    var opacity = 0.5f
        set(v) { field = v; invalidate() }
    var moving = false
    /** brush outline (screen x, y, radius), or radius 0 for none */
    var cursorX = 0f; var cursorY = 0f; var cursorR = 0f
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.WHITE }
    private val ring2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = 0x88000000.toInt() }

    /** Picture centred, filling about 70% of the view. */
    fun fit() {
        val b = picture ?: return
        if (width == 0 || height == 0) { post { fit() }; return }
        val s = 0.7f * minOf(width / b.width.toFloat(), height / b.height.toFloat())
        picMatrix.reset(); picMatrix.postScale(s, s); picMatrix.postTranslate((width - b.width * s) / 2, (height - b.height * s) / 2)
        invalidate()
    }

    fun flip() {
        val b = picture ?: return
        val m = Matrix(); m.setScale(-1f, 1f, b.width / 2f, 0f); picMatrix.preConcat(m); invalidate()
    }

    /** Screen -> picture pixel mapping as 6 numbers (a, b, c, d, e, f). */
    fun inverse(): FloatArray? {
        val inv = Matrix(); if (!picMatrix.invert(inv)) return null
        val v = FloatArray(9); inv.getValues(v)
        return floatArrayOf(v[0], v[1], v[2], v[3], v[4], v[5])
    }

    override fun onDraw(c: Canvas) {
        picture?.let { b -> bmpPaint.alpha = (opacity * 255).toInt(); c.drawBitmap(b, picMatrix, bmpPaint) }
        if (cursorR > 0f) { c.drawCircle(cursorX, cursorY, cursorR, ring2); c.drawCircle(cursorX, cursorY, cursorR, ring) }
    }

    private var lx = 0f; private var ly = 0f; private var ld = 0f; private var la = 0f; private var count = 0
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!moving || picture == null) return false
        val n = e.pointerCount
        val cx = (0 until n).map { e.getX(it) }.average().toFloat(); val cy = (0 until n).map { e.getY(it) }.average().toFloat()
        val d = if (n >= 2) Math.hypot((e.getX(1) - e.getX(0)).toDouble(), (e.getY(1) - e.getY(0)).toDouble()).toFloat() else 0f
        val a = if (n >= 2) Math.toDegrees(Math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat() else 0f
        if (e.actionMasked == MotionEvent.ACTION_DOWN || n != count || e.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            lx = cx; ly = cy; ld = d; la = a; count = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) -1 else n; return true
        }
        if (e.actionMasked == MotionEvent.ACTION_MOVE) {
            picMatrix.postTranslate(cx - lx, cy - ly)
            if (n >= 2 && ld > 10f && d > 10f) { picMatrix.postScale(d / ld, d / ld, cx, cy); picMatrix.postRotate(a - la, cx, cy) }
            lx = cx; ly = cy; ld = d; la = a; invalidate()
        }
        if (e.actionMasked == MotionEvent.ACTION_UP) count = 0
        return true
    }
}
