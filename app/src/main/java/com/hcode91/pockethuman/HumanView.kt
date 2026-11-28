package com.hcode91.pockethuman

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Orbit-camera 3D view of the character. One finger turns, pinch zooms, two fingers move up/down, double tap = face/body. */
class HumanView(ctx: Context, private var lib: Library) : GLSurfaceView(ctx) {
    private val r = R3()
    var yaw = 0f; var pitch = 5f; var dist = 38f; var targetY = 8.5f
    var targetX = 0f; var targetZ = 0f; var fov = 35f

    /** Lighting: a key light, a fill light, ambient, a rim and the background colour. */
    class Lighting(val name: String, val keyDir: FloatArray, val key: FloatArray, val fillDir: FloatArray, val fill: FloatArray, val amb: FloatArray, val rim: FloatArray, val bg: FloatArray)
    var lighting = PRESETS[0]
        set(v) { field = v; requestRender() }
    private var faceMode = false
    /** Scene mode: single taps go here (picking a joint) and double-tap zoom is off. */
    var onTap: ((Float, Float) -> Unit)? = null

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        setRenderer(r); renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun reload(l: Library) { lib = l }

    /** When set, one-finger drags go to the brush (two fingers still turn/zoom the camera). */
    interface Brush { fun down(x: Float, y: Float): Boolean; fun move(x: Float, y: Float); fun up() }
    var brush: Brush? = null
    private var brushing = false

    /** Same camera as the renderer, for picking: returns proj*view. */
    fun viewProj(): FloatArray {
        val proj = FloatArray(16); val view = FloatArray(16); val out = FloatArray(16)
        Matrix.perspectiveM(proj, 0, fov, width.toFloat() / height.coerceAtLeast(1), 0.5f, 600f)
        val e = eye(); Matrix.setLookAtM(view, 0, e[0], e[1], e[2], targetX, targetY, targetZ, 0f, 1f, 0f)
        Matrix.multiplyMM(out, 0, proj, 0, view, 0); return out
    }
    fun eye(): FloatArray {
        val yr = Math.toRadians(yaw.toDouble()); val pr = Math.toRadians(pitch.toDouble())
        return floatArrayOf(targetX + (dist * Math.sin(yr) * Math.cos(pr)).toFloat(), targetY + (dist * Math.sin(pr)).toFloat(), targetZ + (dist * Math.cos(yr) * Math.cos(pr)).toFloat())
    }

    /** Renders the current view off-screen at w x h (optionally with a see-through background) and hands back the picture. */
    fun renderImage(w: Int, h: Int, transparent: Boolean, done: (android.graphics.Bitmap?) -> Unit) {
        queueEvent { val b = runCatching { r.renderOffscreen(w, h, transparent) }.getOrNull(); post { done(b) } }; requestRender()
    }
    /** Screen point -> world point at a given normalised depth (-1 near .. 1 far). */
    fun unproject(x: Float, y: Float, ndcZ: Float): FloatArray {
        val inv = FloatArray(16); Matrix.invertM(inv, 0, viewProj(), 0)
        val p = floatArrayOf(2f * x / width - 1f, 1f - 2f * y / height, ndcZ, 1f); val o = FloatArray(4)
        Matrix.multiplyMV(o, 0, inv, 0, p, 0); return floatArrayOf(o[0] / o[3], o[1] / o[3], o[2] / o[3])
    }
    fun ndcDepth(w: FloatArray): Float { val o = FloatArray(4); Matrix.multiplyMV(o, 0, viewProj(), 0, floatArrayOf(w[0], w[1], w[2], 1f), 0); return o[2] / o[3] }
    fun show(parts: List<Part>) { queueEvent { r.pending = parts }; requestRender() }
    /** Textures named "live:…" come from here (painting), not from files. */
    @Volatile var liveSource: ((String) -> android.graphics.Bitmap?)? = null
    /** Replaces part of a live texture (the bitmap is recycled afterwards). */
    fun patchTexture(key: String, x: Int, y: Int, bmp: android.graphics.Bitmap) { queueEvent { r.patch(key, x, y, bmp) }; requestRender() }
    /** bone matrices (52 x column-major 4x4) for skinning parts that carry joints; null = draw parts as they are */
    @Volatile private var bones: FloatArray? = null
    fun setBones(m: FloatArray?) { bones = m; requestRender() }

    private val scale = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { dist = (dist / d.scaleFactor).coerceIn(4f, 80f); requestRender(); return true }
    })
    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { val t = onTap ?: return false; t(e.x, e.y); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (onTap != null) return false
            faceMode = !faceMode
            if (faceMode) { dist = 7f; targetY = 15.3f; pitch = 0f } else { dist = 38f; targetY = 8.5f; pitch = 5f }
            requestRender(); return true
        }
    })
    private var lx = 0f; private var ly = 0f; private var lastCount = 0
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = brush
        if (b != null) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN && e.pointerCount == 1) { brushing = b.down(e.x, e.y); if (brushing) return true }
            else if (brushing) {
                when {
                    e.actionMasked == MotionEvent.ACTION_POINTER_DOWN -> { brushing = false; b.up() }
                    e.actionMasked == MotionEvent.ACTION_MOVE -> { b.move(e.x, e.y); return true }
                    e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL -> { brushing = false; b.up(); return true }
                }
            }
        }
        scale.onTouchEvent(e); gestures.onTouchEvent(e)
        val cx = (0 until e.pointerCount).map { e.getX(it) }.average().toFloat(); val cy = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.pointerCount != lastCount) { lx = cx; ly = cy; lastCount = e.pointerCount; return true }
        if (e.actionMasked == MotionEvent.ACTION_MOVE) {
            val dx = cx - lx; val dy = cy - ly
            if (e.pointerCount == 1) { yaw += dx * 0.4f; pitch = (pitch + dy * 0.25f).coerceIn(-60f, 75f) }
            else if (!scale.isInProgress || e.pointerCount == 2) targetY = (targetY + dy * dist * 0.0015f).coerceIn(0f, 18f)
            lx = cx; ly = cy; requestRender()
        }
        if (e.actionMasked == MotionEvent.ACTION_UP) lastCount = 0
        return true
    }

    private class Gpu(val part: Part, val vao: Int, val bufs: IntArray, val nIdx: Int)

    inner class R3 : Renderer {
        @Volatile var pending: List<Part>? = null
        private var prog = 0; private val gpu = ArrayList<Gpu>(); private val tex = HashMap<String, Int>()
        private val proj = FloatArray(16); private val view = FloatArray(16); private val mvp = FloatArray(16)
        private var w = 1; private var h = 1

        override fun onSurfaceCreated(gl: GL10?, c: EGLConfig?) {
            prog = program(VS, FS); glEnable(GL_DEPTH_TEST)
            gpu.clear(); tex.clear()
            if (pending == null && lastParts != null) pending = lastParts
        }
        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { w = width; h = height; glViewport(0, 0, w, h) }
        private var lastParts: List<Part>? = null

        override fun onDrawFrame(gl: GL10?) {
            pending?.let { upload(it); lastParts = it; pending = null }
            drawScene(w, h, false)
        }

        fun renderOffscreen(rw: Int, rh: Int, transparent: Boolean): android.graphics.Bitmap {
            pending?.let { upload(it); lastParts = it; pending = null }
            val fbo = IntArray(1); val col = IntArray(1); val dep = IntArray(1)
            glGenFramebuffers(1, fbo, 0); glBindFramebuffer(GL_FRAMEBUFFER, fbo[0])
            glGenRenderbuffers(1, col, 0); glBindRenderbuffer(GL_RENDERBUFFER, col[0]); glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, rw, rh)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, col[0])
            glGenRenderbuffers(1, dep, 0); glBindRenderbuffer(GL_RENDERBUFFER, dep[0]); glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, rw, rh)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, dep[0])
            glViewport(0, 0, rw, rh)
            drawScene(rw, rh, transparent)
            val buf = ByteBuffer.allocateDirect(rw * rh * 4).order(ByteOrder.nativeOrder())
            glReadPixels(0, 0, rw, rh, GL_RGBA, GL_UNSIGNED_BYTE, buf)
            glBindFramebuffer(GL_FRAMEBUFFER, 0); glViewport(0, 0, w, h)
            glDeleteRenderbuffers(1, col, 0); glDeleteRenderbuffers(1, dep, 0); glDeleteFramebuffers(1, fbo, 0)
            val px = IntArray(rw * rh); buf.position(0)
            for (y in 0 until rh) for (x in 0 until rw) {   // RGBA bottom-up -> ARGB top-down
                val r8 = buf.get().toInt() and 255; val g8 = buf.get().toInt() and 255; val b8 = buf.get().toInt() and 255; val a8 = buf.get().toInt() and 255
                px[(rh - 1 - y) * rw + x] = (a8 shl 24) or (r8 shl 16) or (g8 shl 8) or b8
            }
            return android.graphics.Bitmap.createBitmap(px, rw, rh, android.graphics.Bitmap.Config.ARGB_8888)
        }

        private fun drawScene(w: Int, h: Int, transparent: Boolean) {
            val L = lighting
            glClearColor(L.bg[0], L.bg[1], L.bg[2], if (transparent) 0f else 1f)
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            Matrix.perspectiveM(proj, 0, fov, w.toFloat() / h, 0.5f, 600f)
            val e = eye(); val ex = e[0]; val ey = e[1]; val ez = e[2]
            Matrix.setLookAtM(view, 0, ex, ey, ez, targetX, targetY, targetZ, 0f, 1f, 0f)
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
            glUseProgram(prog)
            glUniformMatrix4fv(glGetUniformLocation(prog, "uMvp"), 1, false, mvp, 0)
            glUniform3f(glGetUniformLocation(prog, "uEye"), ex, ey, ez)
            glUniform3fv(glGetUniformLocation(prog, "uKeyDir"), 1, L.keyDir, 0); glUniform3fv(glGetUniformLocation(prog, "uKey"), 1, L.key, 0)
            glUniform3fv(glGetUniformLocation(prog, "uFillDir"), 1, L.fillDir, 0); glUniform3fv(glGetUniformLocation(prog, "uFill"), 1, L.fill, 0)
            glUniform3fv(glGetUniformLocation(prog, "uAmb"), 1, L.amb, 0); glUniform3fv(glGetUniformLocation(prog, "uRim"), 1, L.rim, 0)
            // opaque first, then cut-out parts (hair, brows, lashes), then markers drawn over everything
            for (pass in 0..2) for (g in gpu) {
                val marker = g.part.name.startsWith("marker")
                if (pass != (if (marker) 2 else if (g.part.alpha) 1 else 0)) continue
                if (marker) glDisable(GL_DEPTH_TEST) else glEnable(GL_DEPTH_TEST)
                if (g.part.alpha || g.part.name.startsWith("my_") || g.part.name == "draft" || g.part.name.startsWith("head")) glDisable(GL_CULL_FACE) else { glEnable(GL_CULL_FACE); glCullFace(GL_BACK) }
                glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texture(g.part.texture))
                glUniform1i(glGetUniformLocation(prog, "uTex"), 0)
                glUniform1f(glGetUniformLocation(prog, "uCut"), if (g.part.alpha) 0.4f else -1f)
                glUniform1f(glGetUniformLocation(prog, "uShine"), if (g.part.name.startsWith("eyes")) 1f else 0.15f)
                val bm = bones; val skin = bm != null && g.part.joints.isNotEmpty()
                glUniform1i(glGetUniformLocation(prog, "uSkin"), if (skin) 1 else 0)
                if (skin) glUniformMatrix4fv(glGetUniformLocation(prog, "uB"), bm!!.size / 16, false, bm, 0)
                glBindVertexArray(g.vao); glDrawElements(GL_TRIANGLES, g.nIdx, GL_UNSIGNED_INT, 0)
            }
            glBindVertexArray(0); glEnable(GL_DEPTH_TEST)
        }

        fun patch(key: String, x: Int, y: Int, bmp: android.graphics.Bitmap) {
            val id = tex[key]
            if (id != null) { glBindTexture(GL_TEXTURE_2D, id); GLUtils.texSubImage2D(GL_TEXTURE_2D, 0, x, y, bmp); glGenerateMipmap(GL_TEXTURE_2D) }
            bmp.recycle()
        }

        private fun texture(path: String): Int = tex.getOrPut(path) {
            val bmp = if (path.startsWith("live:")) (liveSource?.invoke(path) ?: android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888))
                else { val raw = lib.am.bytes(path); BitmapFactory.decodeByteArray(raw, 0, raw.size) }
            val id = IntArray(1); glGenTextures(1, id, 0); glBindTexture(GL_TEXTURE_2D, id[0])
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0); bmp.recycle()
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glGenerateMipmap(GL_TEXTURE_2D); id[0]
        }

        private fun upload(parts: List<Part>) {
            for (g in gpu) { glDeleteBuffers(g.bufs.size, g.bufs, 0); glDeleteVertexArrays(1, intArrayOf(g.vao), 0) }
            gpu.clear()
            for (p in parts) {
                val vao = IntArray(1); glGenVertexArrays(1, vao, 0); glBindVertexArray(vao[0])
                val b = IntArray(6); glGenBuffers(6, b, 0)
                fun attr(i: Int, data: FloatArray, comps: Int) {
                    glBindBuffer(GL_ARRAY_BUFFER, b[i]); glBufferData(GL_ARRAY_BUFFER, data.size * 4, fb(data), GL_STATIC_DRAW)
                    glEnableVertexAttribArray(i); glVertexAttribPointer(i, comps, GL_FLOAT, false, 0, 0)
                }
                attr(0, p.pos, 3); attr(1, p.nrm, 3); attr(2, p.uv, 2)
                if (p.joints.isNotEmpty()) { attr(4, FloatArray(p.joints.size) { p.joints[it].toFloat() }, 4); attr(5, p.weights, 4) }
                else { glDisableVertexAttribArray(4); glDisableVertexAttribArray(5) }
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, b[3]); glBufferData(GL_ELEMENT_ARRAY_BUFFER, p.tris.size * 4, ib(p.tris), GL_STATIC_DRAW)
                gpu.add(Gpu(p, vao[0], b, p.tris.size))
            }
            glBindVertexArray(0)
        }
    }

    companion object {
        private fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
        private fun c(r: Float, g: Float, b: Float, k: Float) = floatArrayOf(r * k, g * k, b * k)
        val PRESETS = listOf(
            Lighting("Studio", v(0.4f, 0.8f, 0.6f), c(1f, 1f, 1f, 0.75f), v(-0.6f, 0.2f, -0.5f), c(1f, 1f, 1f, 0.25f), c(1f, 1f, 1f, 0.28f), c(1f, 1f, 1f, 0.12f), v(0.11f, 0.12f, 0.14f)),
            Lighting("Daylight", v(0.3f, 0.9f, 0.5f), c(1f, 0.97f, 0.9f, 0.85f), v(-0.5f, 0.3f, -0.6f), c(0.6f, 0.7f, 0.9f, 0.3f), v(0.3f, 0.32f, 0.36f), c(1f, 1f, 1f, 0.08f), v(0.55f, 0.68f, 0.82f)),
            Lighting("Warm lantern", v(0.7f, 0.45f, 0.35f), c(1f, 0.72f, 0.45f, 0.95f), v(-0.6f, 0.3f, -0.4f), c(0.3f, 0.35f, 0.6f, 0.3f), v(0.1f, 0.08f, 0.09f), c(1f, 0.6f, 0.35f, 0.35f), v(0.06f, 0.04f, 0.035f)),
            Lighting("Soft bedroom", v(0.2f, 0.7f, 0.8f), c(1f, 0.86f, 0.76f, 0.65f), v(-0.7f, 0.4f, 0.2f), c(0.8f, 0.75f, 0.92f, 0.35f), v(0.24f, 0.19f, 0.2f), c(1f, 0.8f, 0.8f, 0.22f), v(0.18f, 0.13f, 0.14f)),
            Lighting("Moonlight", v(-0.3f, 0.8f, 0.4f), c(0.55f, 0.65f, 1f, 0.7f), v(0.6f, 0.2f, -0.4f), c(1f, 0.6f, 0.4f, 0.12f), v(0.07f, 0.09f, 0.15f), c(0.6f, 0.7f, 1f, 0.4f), v(0.03f, 0.04f, 0.07f)),
            Lighting("Sunset", v(0.85f, 0.3f, -0.2f), c(1f, 0.6f, 0.35f, 1f), v(-0.5f, 0.4f, 0.6f), c(0.35f, 0.45f, 0.75f, 0.4f), v(0.18f, 0.14f, 0.17f), c(1f, 0.5f, 0.3f, 0.3f), v(0.35f, 0.2f, 0.2f)),
        )
        fun fb(a: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(a).also { it.position(0) }
        fun ib(a: IntArray): IntBuffer = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(a).also { it.position(0) }
        fun program(vs: String, fs: String): Int {
            fun sh(t: Int, s: String) = glCreateShader(t).also { glShaderSource(it, s); glCompileShader(it) }
            return glCreateProgram().also { glAttachShader(it, sh(GL_VERTEX_SHADER, vs)); glAttachShader(it, sh(GL_FRAGMENT_SHADER, fs)); glLinkProgram(it) }
        }
        const val VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNrm; layout(location=2) in vec2 aUv;
layout(location=4) in vec4 aJ; layout(location=5) in vec4 aW;
uniform mat4 uMvp; uniform mat4 uB[52]; uniform int uSkin; out vec3 vN; out vec2 vUv; out vec3 vP;
void main(){
  vec3 p=aPos; vec3 n=aNrm;
  if(uSkin==1){ mat4 m=uB[int(aJ.x)]*aW.x+uB[int(aJ.y)]*aW.y+uB[int(aJ.z)]*aW.z+uB[int(aJ.w)]*aW.w; p=(m*vec4(aPos,1.0)).xyz; n=mat3(m)*aNrm; }
  vN=n; vUv=aUv; vP=p; gl_Position=uMvp*vec4(p,1.0); }"""
        const val FS = """#version 300 es
precision mediump float;
in vec3 vN; in vec2 vUv; in vec3 vP; uniform sampler2D uTex; uniform float uCut; uniform float uShine; uniform vec3 uEye; out vec4 o;
uniform vec3 uKeyDir; uniform vec3 uKey; uniform vec3 uFillDir; uniform vec3 uFill; uniform vec3 uAmb; uniform vec3 uRim;
void main(){
  vec4 c=texture(uTex,vUv); if(c.a<uCut) discard;
  vec3 n=normalize(vN); if(!gl_FrontFacing) n=-n; vec3 v=normalize(uEye-vP);
  vec3 l1=normalize(uKeyDir); vec3 l2=normalize(uFillDir);
  vec3 d=uKey*max(dot(n,l1),0.0)+uFill*max(dot(n,l2),0.0)+uAmb;
  float s=pow(max(dot(n,normalize(l1+v)),0.0),32.0)*uShine;
  float rim=pow(1.0-max(dot(n,v),0.0),3.0);
  o=vec4(c.rgb*d+uKey*s+uRim*rim,1.0);
}"""
    }
}
