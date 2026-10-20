package org.ambientflow.tv

import kotlin.math.*

/** Shared reference colors: background, copper, parchment, petrol. */
object FilamentStyle {
    val sagePalette = intArrayOf(0xff838b67.toInt(), 0xffd98947.toInt(), 0xffe1d9b5.toInt(), 0xff285c58.toInt())
}

/** Deterministic, Android-free geometry. Coordinates are normalized; time is in seconds.
 * All output/storage is retained across updates. The caller owns pixel scaling and drawing.
 */
class FilamentGeometry(val strandCount: Int = 120, val sampleCount: Int = 100) {
    init {
        require(strandCount in 90..150)
        require(sampleCount >= 80)
    }

    class Strand internal constructor(val index: Int, samples: Int) {
        val x = FloatArray(samples)
        val y = FloatArray(samples)
        val depth = FloatArray(samples)
        // Catmull–Rom to cubic control offsets, shared by Canvas and the host preview.
        fun tangentX(sample: Int): Float = (x[min(sample + 1, x.lastIndex)] - x[max(0, sample - 1)]) / 6f
        fun tangentY(sample: Int): Float = (y[min(sample + 1, y.lastIndex)] - y[max(0, sample - 1)]) / 6f
        /** Whether the edge ending at this sample is present. Gaps never blink or migrate. */
        val visible = BooleanArray(samples)
        val colorSlot = when (index % 10) { 0, 1, 2, 3 -> 0; 4, 5, 6 -> 1; 7, 8 -> 2; else -> 3 }
        val width = (0.52 + 1.28 * hash(index + 31)).toFloat()
        val opacity = (0.37 + 0.49 * hash(index + 83)).toFloat()
        internal val angle = index * 2.399963229728653
        internal val radius = sqrt((index + 0.5) / 120.0).coerceAtMost(1.12)
    }

    val strands = Array(strandCount) { Strand(it, sampleCount) }
    private val centerX = DoubleArray(sampleCount)
    private val centerY = DoubleArray(sampleCount)
    private val normalX = DoubleArray(sampleCount)
    private val normalY = DoubleArray(sampleCount)
    private val spread = DoubleArray(sampleCount)
    private val twist = DoubleArray(sampleCount)
    private val drift = DoubleArray(sampleCount)

    // Cubic spans deliberately fold back at the left crown and right-hand eye.
    private val knots = arrayOf(
        doubleArrayOf(-.09, .88, .04, .81, .19, .70, .215, .55),
        doubleArrayOf(.215, .55, .235, .43, .125, .08, .215, .17),
        doubleArrayOf(.215, .17, .305, .26, .365, .255, .50, .34),
        doubleArrayOf(.50, .34, .625, .43, .535, .69, .665, .71),
        doubleArrayOf(.665, .71, .795, .73, .90, .635, .855, .49),
        doubleArrayOf(.855, .49, .82, .365, .755, .405, .795, .48),
        doubleArrayOf(.795, .48, .84, .565, .945, .345, 1.09, .29)
    )

    init {
        for (strand in strands) {
            for (j in 1 until sampleCount) {
                val t = j.toDouble() / (sampleCount - 1)
                // Most filaments remain long, with only a few short missing sections.
                val gap = .15 + .66 * hash(strand.index + 211)
                strand.visible[j] = !(strand.index % 4 != 0 && abs(t - gap) < .012 + .012 * hash(strand.index + 7)) &&
                    !(strand.index % 7 == 0 && t > .44 && t < .49)
            }
        }
        update(0.0)
    }

    fun update(seconds: Double, aspect: Double = 16.0 / 9.0) {
        require(seconds.isFinite() && aspect.isFinite() && aspect > 0.0)
        // Incommensurate, minute-scale motions; no frame randomness or short animation cycle.
        val breath = sin(seconds * .019)
        val roll = .12 * sin(seconds * .013) + .07 * sin(seconds * .0071)
        for (j in 0 until sampleCount) {
            val u = j.toDouble() / (sampleCount - 1)
            val span = min((u * knots.size).toInt(), knots.lastIndex)
            val t = u * knots.size - span
            val s = 1.0 - t
            val k = knots[span]
            val envelope = sin(PI * u)
            centerX[j] = s*s*s*k[0] + 3*s*s*t*k[2] + 3*s*t*t*k[4] + t*t*t*k[6] +
                .006 * envelope * sin(seconds * .011 + u * 8)
            centerY[j] = s*s*s*k[1] + 3*s*s*t*k[3] + 3*s*t*t*k[5] + t*t*t*k[7] +
                .011 * envelope * sin(seconds * .017 + u * 6)
            val dx = 3*s*s*(k[2]-k[0]) + 6*s*t*(k[4]-k[2]) + 3*t*t*(k[6]-k[4])
            val dy = 3*s*s*(k[3]-k[1]) + 6*s*t*(k[5]-k[3]) + 3*t*t*(k[7]-k[5])
            val length = hypot(dx * aspect, dy).coerceAtLeast(.0001)
            normalX[j] = -dy / length / aspect
            normalY[j] = dx * aspect / length
            // Narrow waist on entry; an airy crown, open belly, and compact right loop.
            spread[j] = (.047 + .043 * exp(-((u-.30)/.105).pow(2)) +
                .043 * exp(-((u-.56)/.12).pow(2)) - .035 * exp(-((u-.143)/.047).pow(2)) -
                .024 * exp(-((u-.76)/.08).pow(2))) * (1.0 + .045 * breath)
            twist[j] = 8.5 * u + .65 * sin(u * 7.0) + roll
            drift[j] = .003 * sin(u * 17 + seconds * .009)
        }
        for (strand in strands) {
            val phase = strand.angle
            val radius = strand.radius * sqrt(120.0 / strandCount)
            val individuality = hash(strand.index + 53) - .5
            for (j in 0 until sampleCount) {
                val u = j.toDouble() / (sampleCount - 1)
                val theta = phase + twist[j]
                val cross = cos(theta) * radius
                val z = sin(theta) * radius
                val offset = spread[j] * cross + drift[j] * individuality +
                    .0018 * sin(u * 24 + phase) * sin(PI * u)
                // Elliptical tube: depth also shears longitudinally, giving genuine crossings.
                strand.x[j] = (centerX[j] + normalX[j] * offset + .010 * z * sin(u * PI)).toFloat()
                strand.y[j] = (centerY[j] + normalY[j] * offset + .017 * z * sin(u * PI)).toFloat()
                strand.depth[j] = z.toFloat()
            }
        }
    }

    companion object {
        private fun hash(n: Int): Double {
            val v = sin(n * 127.1 + 311.7) * 43758.5453123
            return v - floor(v)
        }
    }
}
