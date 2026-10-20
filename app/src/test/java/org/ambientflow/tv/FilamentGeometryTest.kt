package org.ambientflow.tv

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class FilamentGeometryTest {
    @Test fun defaultsAreFineOpenFilaments() {
        val g = FilamentGeometry()
        assertEquals(120, g.strandCount)
        assertEquals(100, g.sampleCount)
        for (s in g.strands) {
            assertTrue(s.width in .5f..2f)
            assertTrue(s.opacity in .3f..1f)
            assertTrue(s.colorSlot in 0..3)
            assertFalse(s.visible[0])
            assertTrue(s.visible.count { it } >= 85)
        }
        assertTrue(g.strands.any { s -> (1 until g.sampleCount).any { !s.visible[it] } })
    }

    @Test fun finiteBoundedAndSpatiallyContinuousAcrossTimesAndAspects() {
        for (count in intArrayOf(90, 120, 150)) {
            val g = FilamentGeometry(count)
            for (aspect in doubleArrayOf(1.0, 16.0/9, 21.0/9)) {
                for (time in doubleArrayOf(0.0, 1.0, 60.0, 3600.0, 86400.0, 1e7)) {
                    g.update(time, aspect)
                    for (s in g.strands) for (j in s.x.indices) {
                        assertTrue(s.x[j].isFinite() && s.y[j].isFinite() && s.depth[j].isFinite())
                        assertTrue(s.x[j] in -.2f..1.2f)
                        assertTrue(s.y[j] in .0f..1.05f)
                        if (j > 0) assertTrue("discontinuity: count=$count aspect=$aspect time=$time strand=${s.index} sample=$j distance=${hypot(s.x[j]-s.x[j-1], s.y[j]-s.y[j-1])}", hypot(s.x[j]-s.x[j-1], s.y[j]-s.y[j-1]) < .085f)
                    }
                }
            }
        }
    }

    @Test fun followsReferenceLandmarks() {
        val g = FilamentGeometry()
        val landmarks = arrayOf(
            floatArrayOf(.215f, .55f), floatArrayOf(.215f, .17f), floatArrayOf(.50f, .34f),
            floatArrayOf(.665f, .71f), floatArrayOf(.855f, .49f), floatArrayOf(.795f, .48f)
        )
        for (k in landmarks.indices) {
            val j = ((k+1) * (g.sampleCount-1) / 7.0).roundToInt()
            val x = g.strands.map { it.x[j] }.average().toFloat()
            val y = g.strands.map { it.y[j] }.average().toFloat()
            assertEquals(landmarks[k][0], x, .025f)
            assertEquals(landmarks[k][1], y, .025f)
        }
        assertTrue(g.strands.all { it.x.first() < 0 && it.x.last() > 1 })
    }

    @Test fun updatesReuseStorageAndAreDeterministic() {
        val a = FilamentGeometry()
        val b = FilamentGeometry()
        val strand = a.strands[10]
        val x = strand.x
        val y = strand.y
        val depth = strand.depth
        val visible = strand.visible
        val gaps = visible.copyOf()
        for (t in doubleArrayOf(0.0, 30.0, 900.0)) {
            a.update(t); b.update(t)
            assertSame(strand, a.strands[10])
            assertSame(x, strand.x); assertSame(y, strand.y)
            assertSame(depth, strand.depth); assertSame(visible, strand.visible)
            assertArrayEquals(gaps, visible)
            for (i in a.strands.indices) {
                assertArrayEquals(a.strands[i].x, b.strands[i].x, 0f)
                assertArrayEquals(a.strands[i].y, b.strands[i].y, 0f)
            }
        }
    }

    @Test fun motionIsSlowContinuousAndNotAShortLoop() {
        val g = FilamentGeometry()
        val initial = g.strands.map { it.y.copyOf() }
        g.update(1.0 / 30)
        for (i in g.strands.indices) for (j in 0 until g.sampleCount)
            // Perceptible drift remains subpixel per frame at the 960x540 design size.
                        assertTrue(abs(initial[i][j] - g.strands[i].y[j]) * 540 < .5)
        g.update(60.0)
        assertTrue(g.strands.indices.any { i -> g.strands[i].y.indices.any { j -> abs(initial[i][j] - g.strands[i].y[j]) > .003 } })
        g.update(0.0)
        for (i in g.strands.indices) assertArrayEquals(initial[i], g.strands[i].y, 0f)
    }

    @Test fun everyCompositionAndMorphIsFiniteBoundedAndSpatiallyContinuous() {
        val g = FilamentGeometry()
        for (composition in 0..3) for (aspect in doubleArrayOf(1.0, 16.0/9))
            for (time in doubleArrayOf(0.0, 60.0, 3600.0)) for (step in 0..4) {
                g.update(time, aspect, composition, (composition+1)%4, step/4.0)
                for (s in g.strands) for (j in s.x.indices) {
                    assertTrue(s.x[j].isFinite() && s.y[j].isFinite())
                    assertTrue(s.x[j] in -.2f..1.2f)
                    assertTrue(s.y[j] in 0f..1.05f)
                    if (j > 0) assertTrue("composition=$composition blend=$step aspect=$aspect sample=$j",
                        hypot(s.x[j]-s.x[j-1], s.y[j]-s.y[j-1]) < .085f)
                }
            }
    }

    @Test fun weightedMorphIsContinuousEvenWhenLoopsCollapse() {
        val a = FilamentGeometry()
        val b = FilamentGeometry()
        val g = FilamentGeometry()
        val retainedX = g.strands[0].x
        val retainedY = g.strands[0].y
        val retainedDepth = g.strands[0].depth
        val retainedVisibility = g.strands[0].visible
        val gaps = retainedVisibility.copyOf()
        val weights = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
        for (from in 0..3) for (to in 0..3) {
            a.update(50.0, composition = from)
            b.update(50.0, composition = to)
            for (step in 0..10) {
                val t = step/10.0
                weights.fill(0.0)
                weights[from] += 1-t
                weights[to] += t
                g.update(50.0, 16.0/9, weights)
                assertSame(retainedX, g.strands[0].x)
                assertSame(retainedY, g.strands[0].y)
                assertSame(retainedDepth, g.strands[0].depth)
                assertSame(retainedVisibility, g.strands[0].visible)
                assertArrayEquals(gaps, retainedVisibility)
                for (i in g.strands.indices) for (j in 0 until g.sampleCount) {
                    assertEquals((a.strands[i].x[j]*(1-t) + b.strands[i].x[j]*t).toFloat(), g.strands[i].x[j], .000001f)
                    assertEquals((a.strands[i].y[j]*(1-t) + b.strands[i].y[j]*t).toFloat(), g.strands[i].y[j], .000001f)
                }
            }
        }
    }

    @Test fun automaticCycleAndManualInterruptionsAreSmoothAndRetained() {
        val c = FilamentComposition()
        val weights = c.weights
        for (i in 0..3) {
            c.update(i*180.0, 0.0)
            assertEquals(1.0, weights[i], 0.0)
            c.update(i*180.0+165, 0.0)
            assertEquals(.5, weights[i], .000001)
            assertEquals(.5, weights[(i+1)%4], .000001)
            val before = weights.copyOf()
            c.update(i*180.0+165.001, 0.0)
            for (j in weights.indices) assertTrue(abs(before[j]-weights[j]) < .0001)
        }
        for (boundary in doubleArrayOf(150.0, 180.0, 330.0, 360.0, 510.0, 540.0, 690.0, 720.0)) {
            c.update(boundary-.001, 0.0)
            val before = weights.copyOf()
            c.update(boundary+.001, 0.0)
            for (j in weights.indices) assertTrue(abs(before[j]-weights[j]) < .000001)
        }
        c.update(720.0, 0.0)
        assertEquals(1.0, weights[0], 0.0)
        c.update(720.0, 0.0, "Drifting knot")
        assertEquals(1.0, weights[0], 0.0)
        c.update(720.0, 4.0, "Drifting knot")
        assertEquals(.5, weights[0], .000001)
        assertEquals(.5, weights[2], .000001)
        val interrupted = weights.copyOf()
        c.update(720.0, 0.0, "Orbital bloom")
        assertArrayEquals(interrupted, weights, 0.0)
        c.update(720.0, 8.0, "Orbital bloom")
        assertEquals(1.0, weights[3], 0.0)
        assertFalse(c.transitioning)
        assertSame(weights, c.weights)
        c.update(720.0, 0.0, "unknown")
        assertEquals(1.0, weights[3], 0.0)
        c.update(720.0, 8.0)
        assertEquals(1.0, weights[0], 0.0)
    }

    @Test fun compositionsAreDistinctAndContinueToMove() {
        val g = FilamentGeometry()
        val shapes = Array(4) { i ->
            g.update(0.0, composition = i)
            g.strands[10].x.copyOf()
        }
        for (i in 0..3) for (j in i+1..3)
            assertTrue(shapes[i].indices.any { abs(shapes[i][it]-shapes[j][it]) > .1f })
        for (i in 0..3) {
            g.update(30.0, composition = i)
            val initial = g.strands[10].x.copyOf()
            g.update(30.0+1.0/30, composition = i)
            for (j in initial.indices) assertTrue(abs(initial[j]-g.strands[10].x[j])*960 < .5)
            g.update(90.0, composition = i)
            assertTrue(initial.indices.any { abs(initial[it]-g.strands[10].x[it]) > .003f })
        }
    }

    @Test fun rejectsInvalidInputs() {
        assertThrows(IllegalArgumentException::class.java) { FilamentGeometry(10) }
        assertThrows(IllegalArgumentException::class.java) { FilamentGeometry(sampleCount = 5) }
        val g = FilamentGeometry()
        assertThrows(IllegalArgumentException::class.java) { g.update(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { g.update(0.0, 0.0) }
    }
}
