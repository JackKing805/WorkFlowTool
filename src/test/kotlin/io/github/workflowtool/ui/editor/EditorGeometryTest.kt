package io.github.workflowtool.ui.editor

import androidx.compose.ui.geometry.Offset
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.MaskEditMode
import io.github.workflowtool.model.maskAlphaAt
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
    fun movingRegionUpdatesBoundsOnly() {
        val region = CropRegion(id = "frame", x = 0, y = 0, width = 20, height = 20)

        val moved = moveRegion(region, 5, 4, 40, 40)

        assertEquals(5, moved.x)
        assertEquals(4, moved.y)
        assertEquals(20, moved.width)
        assertEquals(20, moved.height)
    }

    @Test
    fun maskBrushCanAddAndSubtractSelectionArea() {
        val region = CropRegion(
            id = "mask",
            x = 0,
            y = 0,
            width = 10,
            height = 10,
            maskWidth = 10,
            maskHeight = 10,
            alphaMask = MutableList(100) { 0 }.also { it[5 * 10 + 5] = 255 },
            selected = true
        )

        val added = editSelectionMask(region, Offset(6f, 5f), radius = 2, mode = MaskEditMode.Add, imageWidth = 20, imageHeight = 20)
        assertEquals("mask", findRegionHit(listOf(added), Offset(6f, 5f))?.id)

        val subtracted = editSelectionMask(added, Offset(6f, 5f), radius = 3, mode = MaskEditMode.Subtract, imageWidth = 20, imageHeight = 20)
        assertEquals(0, subtracted.maskAlphaAt(6, 5))
    }
}
