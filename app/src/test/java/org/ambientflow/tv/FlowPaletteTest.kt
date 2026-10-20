package org.ambientflow.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class FlowPaletteTest {
    @Test fun automaticDayBoundaries() {
        for(hour in 0..23) assertEquals(when(hour) { in 6..11->0; in 12..16->1; in 17..21->2; else->3 },FlowView.paletteIndex("Automatic",hour))
    }
    @Test fun manualSelectionIgnoresTime() {
        for(hour in 0..23) for((name,index) in listOf("Morning" to 0,"Afternoon" to 1,"Evening" to 2,"Night" to 3)) assertEquals(index,FlowView.paletteIndex(name,hour))
    }
    @Test fun sageIsSeparateFromTheFourLegacyFamilies() {
        assertEquals(4, FlowView.paletteIndex("Sage", 0))
        assertEquals(4, FlowView.paletteIndex("Sage", 12))
        assertEquals("Sage", FlowView.names.last())
        assertEquals(4, FlowView.sagePalette.size)
        assertEquals(FilamentStyle.sagePalette[0], FlowView.paletteColors(4)[0])
    }
    @Test fun blendPreservesOpaqueRgbContract() {
        assertEquals(0xff000000.toInt(), FlowView.blend(0xff000000.toInt(), 0xffffffff.toInt(), 0f))
        assertEquals(0xffffffff.toInt(), FlowView.blend(0xff000000.toInt(), 0xffffffff.toInt(), 1f))
        assertEquals(0xff808080.toInt(), FlowView.blend(0xff000000.toInt(), 0xffffffff.toInt(), .5f))
    }
    @Test fun exactlyTwoVisualModesAndFourPaletteFamilies() {
        assertEquals(4,FlowView.palettes.size)
        FlowView.palettes.forEach { assertEquals(4,it.size) }
    }
}
