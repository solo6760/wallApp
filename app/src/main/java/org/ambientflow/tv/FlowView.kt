package org.ambientflow.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import java.util.Calendar
import kotlin.math.roundToInt

/** Open filaments only: retained geometry/paths, hardware Canvas, no offscreen buffers. */
class FlowView(context: Context) : View(context) {
    private val prefs = Prefs(context)
    private val geometry = FilamentGeometry()
    private val paths = Array(geometry.strandCount) { Path() }
    private val highlights = Array(geometry.strandCount) { Path() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val calendar = Calendar.getInstance()
    private var hour = calendar.get(Calendar.HOUR_OF_DAY)
    private var hourChecked = 0L
    private var running = false
    private var time = 0.0
    private var last = 0L
    private var geometryDirty = true
    private val colors = paletteColors(paletteIndex(prefs.str("palette", "Sage"), hour)).copyOf()
    private val channels = FloatArray(12) { i -> ((colors[i / 3] ushr (16 - (i % 3) * 8)) and 255).toFloat() }
    private var haze: RadialGradient? = null
    private var shade: RadialGradient? = null
    private var bloom: RadialGradient? = null
    private var backgroundDirty = true

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val speed = when (prefs.str("speed", "Slow")) {
                "Still" -> 0.0
                "Gentle" -> 0.6
                "Normal" -> 1.0
                else -> 0.3
            }
            val dt = (now - last).coerceIn(0L, 100L) / 1000.0
            val advance = dt * speed * if (prefs.bool("reduced")) 0.15 else 1.0
            time += advance
            if (advance > 0.0) geometryDirty = true
            last = now
            if (now - hourChecked > 60_000L || hourChecked == 0L) {
                calendar.timeInMillis = System.currentTimeMillis()
                hour = calendar.get(Calendar.HOUR_OF_DAY)
                hourChecked = now
            }
            val target = paletteColors(paletteIndex(prefs.str("palette", "Sage"), hour))
            // Floating channels retain sub-byte progress. Shader rebuilds only on actual color changes.
            for (i in colors.indices) {
                for (j in 0..2) {
                    val k = i * 3 + j
                    val value = ((target[i] ushr (16 - j * 8)) and 255).toFloat()
                    channels[k] += (value - channels[k]) * .018f
                }
                val color = Color.rgb(channels[i*3].roundToInt(), channels[i*3+1].roundToInt(), channels[i*3+2].roundToInt())
                if (color != colors[i]) { colors[i] = color; backgroundDirty = true }
            }
            invalidate()
            postDelayed(this, if (speed == 0.0) 1000L else 33L)
        }
    }

    fun start() { if (running) return; running = true; last = SystemClock.elapsedRealtime(); post(frame) }
    fun stop() { running = false; removeCallbacks(frame) }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        geometryDirty = true
        backgroundDirty = true
    }

    private fun rebuildPaths() {
        geometry.update(time, width.toDouble() / height)
        for (i in geometry.strands.indices) {
            val strand = geometry.strands[i]
            val path = paths[i]
            val light = highlights[i]
            path.rewind()
            light.rewind()
            for (j in 1 until geometry.sampleCount) {
                val x = strand.x[j] * width
                val y = strand.y[j] * height
                if (strand.visible[j]) {
                    if (j == 1 || !strand.visible[j-1]) path.moveTo(strand.x[j-1] * width, strand.y[j-1] * height)
                    val c1x = (strand.x[j-1] + strand.tangentX(j-1)) * width
                    val c1y = (strand.y[j-1] + strand.tangentY(j-1)) * height
                    val c2x = (strand.x[j] - strand.tangentX(j)) * width
                    val c2y = (strand.y[j] - strand.tangentY(j)) * height
                    path.cubicTo(c1x, c1y, c2x, c2y, x, y)
                    if (strand.depth[j] > .42f) {
                        if (j == 1 || !strand.visible[j-1] || strand.depth[j-1] <= .42f)
                            light.moveTo(strand.x[j-1] * width, strand.y[j-1] * height)
                        light.cubicTo(c1x, c1y, c2x, c2y, x, y)
                    }
                }
            }
        }
        geometryDirty = false
    }

    private fun rebuildBackground() {
        val w = width.toFloat()
        val h = height.toFloat()
        haze = RadialGradient(w*.28f, h*.28f, w*.64f, blend(colors[0], colors[2], .14f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        shade = RadialGradient(w*.76f, h*.88f, w*.57f, 0x240e211a, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        bloom = RadialGradient(w*.88f, h*.08f, w*.44f, blend(colors[0], colors[1], .08f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        backgroundDirty = false
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        if (geometryDirty) rebuildPaths()
        if (backgroundDirty) rebuildBackground()
        canvas.drawColor(colors[0])
        backgroundPaint.shader = haze
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        backgroundPaint.shader = shade
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        backgroundPaint.shader = bloom
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        paint.style = Paint.Style.STROKE
        val scale = width / 960f
        // Stable order keeps crossings from popping. The exposed front arcs get a fine second pass.
        for (i in geometry.strands.indices) {
            val strand = geometry.strands[i]
            paint.color = when (strand.colorSlot) { 0 -> colors[1]; 1 -> colors[3]; 2 -> colors[2]; else -> blend(colors[0], 0xff172b26.toInt(), .78f) }
            paint.alpha = (strand.opacity * 190).roundToInt()
            paint.strokeWidth = strand.width * scale
            canvas.drawPath(paths[i], paint)
        }
        for (i in geometry.strands.indices) {
            val strand = geometry.strands[i]
            paint.color = when (strand.colorSlot) { 0 -> colors[1]; 1 -> colors[3]; 2 -> colors[2]; else -> 0xff263c32.toInt() }
            paint.alpha = (strand.opacity * 95).roundToInt()
            paint.strokeWidth = strand.width * scale * .65f
            canvas.drawPath(highlights[i], paint)
        }
        paint.alpha = 255
    }

    companion object {
        val names = arrayOf("Automatic", "Morning", "Afternoon", "Evening", "Night", "Sage")
        // Keep the original four mood families and their indices stable for saved preferences.
        val palettes = arrayOf(
            intArrayOf(0xff273844.toInt(),0xfff1bc9b.toInt(),0xffe8d6ba.toInt(),0xffa8c7d8.toInt()),
            intArrayOf(0xff102d37.toInt(),0xff528e91.toInt(),0xff376d93.toInt(),0xffc1a16b.toInt()),
            intArrayOf(0xff171d38.toInt(),0xff68558c.toInt(),0xffac778d.toInt(),0xffd49b84.toInt()),
            intArrayOf(0xff090f20.toInt(),0xff353153.toInt(),0xff4c426c.toInt(),0xffa88b65.toInt()))
        val sagePalette = FilamentStyle.sagePalette
        fun paletteColors(index: Int): IntArray = if (index == 4) sagePalette else palettes[index]
        fun paletteIndex(name: String, hour: Int): Int = when (name) {
            "Morning" -> 0; "Afternoon" -> 1; "Evening" -> 2; "Night" -> 3; "Sage" -> 4
            else -> when (hour) { in 6..11 -> 0; in 12..16 -> 1; in 17..21 -> 2; else -> 3 }
        }
        fun blend(a: Int, b: Int, t: Float): Int {
            fun channel(shift: Int): Int {
                val x = (a ushr shift) and 255
                return (x + (((b ushr shift) and 255) - x) * t).roundToInt().coerceIn(0, 255)
            }
            return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}
