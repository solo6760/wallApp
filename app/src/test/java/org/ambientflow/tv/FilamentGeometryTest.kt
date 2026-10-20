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
                        if (j > 0) assertTrue("discontinuity at $j", hypot(s.x[j]-s.x[j-1], s.y[j]-s.y[j-1]) < .085f)
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
            assertTrue(abs(initial[i][j] - g.strands[i].y[j]) * 540 < .05)
        g.update(60.0)
        assertTrue(g.strands.indices.any { i -> g.strands[i].y.indices.any { j -> abs(initial[i][j] - g.strands[i].y[j]) > .003 } })
        g.update(0.0)
        for (i in g.strands.indices) assertArrayEquals(initial[i], g.strands[i].y, 0f)
    }

    @Test fun rejectsInvalidInputs() {
        assertThrows(IllegalArgumentException::class.java) { FilamentGeometry(10) }
        assertThrows(IllegalArgumentException::class.java) { FilamentGeometry(sampleCount = 5) }
        val g = FilamentGeometry()
        assertThrows(IllegalArgumentException::class.java) { g.update(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { g.update(0.0, 0.0) }
    }
}
