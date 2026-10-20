package org.ambientflow.tv

import kotlin.math.*

/** Shared reference colors: background, copper, parchment, petrol. */
object FilamentStyle {
    val sagePalette = intArrayOf(0xff838b67.toInt(), 0xffd98947.toInt(), 0xffe1d9b5.toInt(), 0xff285c58.toInt())
}

/** Retained blend state; motion time controls Automatic, elapsed time controls manual fades. */
class FilamentComposition {
    val weights = DoubleArray(4).apply { this[0] = 1.0 }
    private val source = DoubleArray(4)
    private val target = DoubleArray(4)
    private var selection = Int.MIN_VALUE
    private var elapsed = 8.0

    fun update(motionSeconds: Double, elapsedSeconds: Double, name: String = "Automatic") {
        require(motionSeconds.isFinite() && motionSeconds >= 0.0)
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0.0)
        val index = names.indexOf(name) - 1
        val chosen = if (index in 0..3) index else -1
        target.fill(0.0)
        if (chosen < 0) {
            val step = motionSeconds / 180.0
            val current = (step % 4.0).toInt()
            val fraction = ((motionSeconds % 180.0 - 150.0) / 30.0).coerceIn(0.0, 1.0)
            val blend = smooth(fraction)
            target[current] = 1.0 - blend
            target[(current + 1) % 4] = blend
        } else target[chosen] = 1.0
        if (selection == Int.MIN_VALUE) {
            target.copyInto(weights)
            selection = chosen
        } else if (chosen != selection) {
            weights.copyInto(source)
            selection = chosen
            elapsed = 0.0
        } else elapsed = (elapsed + elapsedSeconds).coerceAtMost(8.0)
        if (elapsed < 8.0) {
            val blend = smooth(elapsed / 8.0)
            for (i in weights.indices) weights[i] = source[i] * (1.0-blend) + target[i] * blend
        } else target.copyInto(weights)
    }

    val transitioning: Boolean get() = elapsed < 8.0

    companion object {
        val names = arrayOf("Automatic", "Folded stream", "Interwoven loops", "Drifting knot", "Orbital bloom")
        private fun smooth(t: Double): Double = t*t*t*(t*(t*6.0-15.0)+10.0)
    }
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
    private val foldedStream = arrayOf(
        doubleArrayOf(-.09, .88, .04, .81, .19, .70, .215, .55),
        doubleArrayOf(.215, .55, .235, .39, .11, .24, .215, .17),
        doubleArrayOf(.215, .17, .32, .10, .365, .255, .50, .34),
        doubleArrayOf(.50, .34, .625, .43, .535, .69, .665, .71),
        doubleArrayOf(.665, .71, .795, .73, .90, .635, .855, .49),
        doubleArrayOf(.855, .49, .82, .365, .755, .405, .795, .48),
        doubleArrayOf(.795, .48, .84, .565, .945, .345, 1.09, .29)
    )

    private val interwovenLoops = arrayOf(
        doubleArrayOf(-.09,.78, .15,.88, .43,.72, .40,.43),
        doubleArrayOf(.40,.43, .37,.14, .14,.12, .19,.36),
        doubleArrayOf(.19,.36, .24,.60, .66,.83, .76,.59),
        doubleArrayOf(.76,.59, .86,.35, .67,.11, .52,.28),
        doubleArrayOf(.52,.28, .37,.45, .56,.80, .78,.74),
        doubleArrayOf(.78,.74, 1.00,.68, .91,.20, .75,.25),
        doubleArrayOf(.75,.25, .59,.30, .94,.42, 1.09,.29)
    )
    private val driftingKnot = arrayOf(
        doubleArrayOf(-.09,.72, .15,.66, .64,.91, .74,.64),
        doubleArrayOf(.74,.64, .84,.37, .41,.13, .32,.35),
        doubleArrayOf(.32,.35, .23,.57, .56,.85, .71,.65),
        doubleArrayOf(.71,.65, .86,.45, .62,.16, .47,.30),
        doubleArrayOf(.47,.30, .32,.44, .47,.73, .66,.64),
        doubleArrayOf(.66,.64, .85,.55, .72,.22, .58,.31),
        doubleArrayOf(.58,.31, .44,.40, .94,.29, 1.09,.24)
    )
    private val orbitalBloom = arrayOf(
        doubleArrayOf(-.09,.83, .13,.90, .22,.60, .49,.49),
        doubleArrayOf(.49,.49, .76,.38, .70,.05, .48,.16),
        doubleArrayOf(.48,.16, .26,.27, .28,.76, .51,.78),
        doubleArrayOf(.51,.78, .74,.80, .97,.39, .77,.31),
        doubleArrayOf(.77,.31, .57,.23, .18,.33, .24,.55),
        doubleArrayOf(.24,.55, .30,.77, .77,.77, .80,.55),
        doubleArrayOf(.80,.55, .83,.33, .94,.38, 1.09,.29)
    )
    private val patterns = arrayOf(foldedStream, interwovenLoops, driftingKnot, orbitalBloom)
    private val weights = DoubleArray(4)

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

    /** Existing two-argument calls retain Folded stream. Blend is a continuous geometry morph. */
    fun update(seconds: Double, aspect: Double = 16.0 / 9.0, composition: Int = 0,
               nextComposition: Int = composition, blend: Double = 0.0) {
        require(composition in 0..3 && nextComposition in 0..3 && blend.isFinite() && blend in 0.0..1.0)
        weights.fill(0.0)
        weights[composition] += 1.0 - blend
        weights[nextComposition] += blend
        update(seconds, aspect, weights)
    }

    /** Weighted overload supports interruption of a morph without jumping back to an endpoint. */
    fun update(seconds: Double, aspect: Double, compositionWeights: DoubleArray) {
        require(seconds.isFinite() && aspect.isFinite() && aspect > 0.0)
        require(compositionWeights.size == 4)
        var total = 0.0
        for (weight in compositionWeights) {
            require(weight.isFinite() && weight >= 0.0)
            total += weight
        }
        require(abs(total - 1.0) < .00001)
        // Keep the composition schedule independent of the visible strand motion.
        val motion = seconds * 8.0
        val breath = sin(motion * .019)
        val roll = .12 * sin(motion * .013) + .07 * sin(motion * .0071)
        for (j in 0 until sampleCount) {
            val u = j.toDouble() / (sampleCount - 1)
            val span = min((u * foldedStream.size).toInt(), foldedStream.lastIndex)
            val t = u * foldedStream.size - span
            val s = 1.0 - t
            var px = 0.0
            var py = 0.0
            var nx = 0.0
            var ny = 0.0
            for (pattern in patterns.indices) {
                val weight = compositionWeights[pattern]
                if (weight == 0.0) continue
                val k = patterns[pattern][span]
                px += weight * (s*s*s*k[0] + 3*s*s*t*k[2] + 3*s*t*t*k[4] + t*t*t*k[6])
                py += weight * (s*s*s*k[1] + 3*s*s*t*k[3] + 3*s*t*t*k[5] + t*t*t*k[7])
                val dx = 3*s*s*(k[2]-k[0]) + 6*s*t*(k[4]-k[2]) + 3*t*t*(k[6]-k[4])
                val dy = 3*s*s*(k[3]-k[1]) + 6*s*t*(k[5]-k[3]) + 3*t*t*(k[7]-k[5])
                val length = hypot(dx * aspect, dy).coerceAtLeast(.0001)
                // Blend frames too: normalizing the blended tangent would flip at a collapsed loop.
                nx += weight * -dy / length / aspect
                ny += weight * dx * aspect / length
            }
            val envelope = sin(PI * u)
            centerX[j] = px +
                .006 * envelope * sin(motion * .011 + u * 8) +
                (1.0-compositionWeights[0]) * .024 * envelope * sin(motion * .037 + u * 9)
            centerY[j] = py +
                .011 * envelope * sin(motion * .017 + u * 6) +
                (1.0-compositionWeights[0]) * .032 * envelope * sin(motion * .031 + u * 7)
            normalX[j] = nx
            normalY[j] = ny
            // Narrow waist on entry; an airy crown, open belly, and compact right loop.
            spread[j] = (.047 + .043 * exp(-((u-.30)/.105).pow(2)) +
                .043 * exp(-((u-.56)/.12).pow(2)) - .035 * exp(-((u-.143)/.047).pow(2)) -
                .024 * exp(-((u-.76)/.08).pow(2))) * (1.0 + .045 * breath)
            twist[j] = 8.5 * u + .65 * sin(u * 7.0) + roll
            drift[j] = .003 * sin(u * 17 + motion * .009)
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
