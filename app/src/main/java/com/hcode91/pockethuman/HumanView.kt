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
class HumanView(ctx: Context, private val lib: Library) : GLSurfaceView(ctx) {
    private val r = R3()
    var yaw = 0f; var pitch = 5f; var dist = 38f; var targetY = 8.5f
    private var faceMode = false

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        setRenderer(r); renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun show(parts: List<Part>) { queueEvent { r.pending = parts }; requestRender() }

    private val scale = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { dist = (dist / d.scaleFactor).coerceIn(4f, 80f); requestRender(); return true }
    })
    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            faceMode = !faceMode
            if (faceMode) { dist = 7f; targetY = 15.3f; pitch = 0f } else { dist = 38f; targetY = 8.5f; pitch = 5f }
            requestRender(); return true
        }
    })
    private var lx = 0f; private var ly = 0f; private var lastCount = 0
    override fun onTouchEvent(e: MotionEvent): Boolean {
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
            prog = program(VS, FS); glEnable(GL_DEPTH_TEST); glClearColor(0.11f, 0.12f, 0.14f, 1f)
            gpu.clear(); tex.clear()
            if (pending == null && lastParts != null) pending = lastParts
        }
        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { w = width; h = height; glViewport(0, 0, w, h) }
        private var lastParts: List<Part>? = null

        override fun onDrawFrame(gl: GL10?) {
            pending?.let { upload(it); lastParts = it; pending = null }
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            Matrix.perspectiveM(proj, 0, 35f, w.toFloat() / h, 0.5f, 400f)
            val yr = Math.toRadians(yaw.toDouble()); val pr = Math.toRadians(pitch.toDouble())
            val ex = (dist * Math.sin(yr) * Math.cos(pr)).toFloat(); val ez = (dist * Math.cos(yr) * Math.cos(pr)).toFloat(); val ey = targetY + (dist * Math.sin(pr)).toFloat()
            Matrix.setLookAtM(view, 0, ex, ey, ez, 0f, targetY, 0f, 0f, 1f, 0f)
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
            glUseProgram(prog)
            glUniformMatrix4fv(glGetUniformLocation(prog, "uMvp"), 1, false, mvp, 0)
            glUniform3f(glGetUniformLocation(prog, "uEye"), ex, ey, ez)
            // opaque first, then cut-out parts (hair, brows, lashes)
            for (pass in 0..1) for (g in gpu) {
                if ((pass == 1) != g.part.alpha) continue
                if (g.part.alpha) glDisable(GL_CULL_FACE) else { glEnable(GL_CULL_FACE); glCullFace(GL_BACK) }
                glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texture(g.part.texture))
                glUniform1i(glGetUniformLocation(prog, "uTex"), 0)
                glUniform1f(glGetUniformLocation(prog, "uCut"), if (g.part.alpha) 0.4f else -1f)
                glUniform1f(glGetUniformLocation(prog, "uShine"), if (g.part.name.startsWith("eyes")) 1f else 0.15f)
                glBindVertexArray(g.vao); glDrawElements(GL_TRIANGLES, g.nIdx, GL_UNSIGNED_INT, 0)
            }
            glBindVertexArray(0)
        }

        private fun texture(path: String): Int = tex.getOrPut(path) {
            val raw = lib.am.bytes(path); val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
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
                val b = IntArray(4); glGenBuffers(4, b, 0)
                fun attr(i: Int, data: FloatArray, comps: Int) {
                    glBindBuffer(GL_ARRAY_BUFFER, b[i]); glBufferData(GL_ARRAY_BUFFER, data.size * 4, fb(data), GL_STATIC_DRAW)
                    glEnableVertexAttribArray(i); glVertexAttribPointer(i, comps, GL_FLOAT, false, 0, 0)
                }
                attr(0, p.pos, 3); attr(1, p.nrm, 3); attr(2, p.uv, 2)
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, b[3]); glBufferData(GL_ELEMENT_ARRAY_BUFFER, p.tris.size * 4, ib(p.tris), GL_STATIC_DRAW)
                gpu.add(Gpu(p, vao[0], b, p.tris.size))
            }
            glBindVertexArray(0)
        }
    }

    companion object {
        fun fb(a: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(a).also { it.position(0) }
        fun ib(a: IntArray): IntBuffer = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(a).also { it.position(0) }
        fun program(vs: String, fs: String): Int {
            fun sh(t: Int, s: String) = glCreateShader(t).also { glShaderSource(it, s); glCompileShader(it) }
            return glCreateProgram().also { glAttachShader(it, sh(GL_VERTEX_SHADER, vs)); glAttachShader(it, sh(GL_FRAGMENT_SHADER, fs)); glLinkProgram(it) }
        }
        const val VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNrm; layout(location=2) in vec2 aUv;
uniform mat4 uMvp; out vec3 vN; out vec2 vUv; out vec3 vP;
void main(){ vN=aNrm; vUv=aUv; vP=aPos; gl_Position=uMvp*vec4(aPos,1.0); }"""
        const val FS = """#version 300 es
precision mediump float;
in vec3 vN; in vec2 vUv; in vec3 vP; uniform sampler2D uTex; uniform float uCut; uniform float uShine; uniform vec3 uEye; out vec4 o;
void main(){
  vec4 c=texture(uTex,vUv); if(c.a<uCut) discard;
  vec3 n=normalize(vN); if(!gl_FrontFacing) n=-n; vec3 v=normalize(uEye-vP);
  vec3 l1=normalize(vec3(0.4,0.8,0.6)); vec3 l2=normalize(vec3(-0.6,0.2,-0.5));
  float d=max(dot(n,l1),0.0)*0.75+max(dot(n,l2),0.0)*0.25+0.28;
  float s=pow(max(dot(n,normalize(l1+v)),0.0),32.0)*uShine;
  float rim=pow(1.0-max(dot(n,v),0.0),3.0)*0.12;
  o=vec4(c.rgb*d+vec3(s+rim),1.0);
}"""
    }
}
