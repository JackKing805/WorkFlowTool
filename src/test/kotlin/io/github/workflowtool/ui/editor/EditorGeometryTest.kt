package io.github.workflowtool.ui.editor

import androidx.compose.ui.geometry.Offset
import io.github.workflowtool.model.CropRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EditorGeometryTest {
    @Test
    fun selectedRegionIsPreferredWhenRegionsOverlap() {
        val selected = CropRegion("selected", 10, 10, 24, 24, selected = true)
        val unselected = CropRegion("other", 10, 10, 24, 24)

        val hit = findRegionHit(listOf(selected, unselected), Offset(20f, 20f))

        assertNotNull(hit)
        assertEquals("selected", hit.id)
    }

    @Test
    fun selectedHandleIsPreferredWhenRegionsOverlap() {
        val selected = CropRegion("selected", 10, 10, 24, 24, selected = true)
        val unselected = CropRegion("other", 10, 10, 24, 24)

        val hit = findHandleHit(listOf(selected, unselected), Offset(10f, 10f), zoom = 1f)

        assertNotNull(hit)
        assertEquals("selected", hit.first.id)
        assertEquals(0, hit.second)
    }

    @Test
    fun handleHitRadiusRemainsComfortableAfterVisualShrink() {
        val region = CropRegion("a", 20, 20, 24, 24, selected = true)

        val hit = findPointHit(region, Offset(29f, 29f), zoom = 1f)

        assertEquals(0, hit)
    }
}
