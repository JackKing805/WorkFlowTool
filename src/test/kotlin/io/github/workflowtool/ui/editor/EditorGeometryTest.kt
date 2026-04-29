package io.github.workflowtool.ui.editor

import androidx.compose.ui.geometry.Offset
import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.MaskEditMode
import io.github.workflowtool.model.ToolMode
import io.github.workflowtool.model.hasMask
import io.github.workflowtool.model.maskAlphaAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.math.abs

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

    @Test
    fun edgeHitDetectsRegionAndModifierModeSelectsMaskEditing() {
        val region = CropRegion("selected", 10, 10, 60, 60, selected = true)

        val edgeHit = findRegionHitTarget(listOf(region), Offset(10f, 18f), zoom = 1f)
        val interiorHit = findRegionHitTarget(listOf(region), Offset(40f, 40f), zoom = 1f)

        assertNotNull(edgeHit)
        assertNotNull(interiorHit)
        assertTrue(edgeHit.nearEdge)
        assertFalse(interiorHit.nearEdge)
        assertEquals(MaskEditMode.Add, maskEditModeForModifiers(altPressed = false, shiftPressed = true))
        assertEquals(MaskEditMode.Subtract, maskEditModeForModifiers(altPressed = true, shiftPressed = false))
    }

    @Test
    fun modifierMaskEditModePrefersAltOverShift() {
        assertEquals(MaskEditMode.Replace, maskEditModeForModifiers(altPressed = false, shiftPressed = false))
        assertEquals(MaskEditMode.Add, maskEditModeForModifiers(altPressed = false, shiftPressed = true))
        assertEquals(MaskEditMode.Subtract, maskEditModeForModifiers(altPressed = true, shiftPressed = false))
        assertEquals(MaskEditMode.Subtract, maskEditModeForModifiers(altPressed = true, shiftPressed = true))
    }

    @Test
    fun imageSelectionBoundsNormalizeAndClampDragCorners() {
        val bounds = imageSelectionBounds(
            start = Offset(25.4f, -4.2f),
            end = Offset(3.1f, 42.8f),
            imageWidth = 20,
            imageHeight = 30
        )

        assertEquals(3, bounds.left)
        assertEquals(0, bounds.top)
        assertEquals(20, bounds.right)
        assertEquals(30, bounds.bottom)
        assertEquals(17, bounds.width)
        assertEquals(30, bounds.height)
    }

    @Test
    fun refinementRequiresActivatedSelectedRegion() {
        assertFalse(canRefineHitRegion(ToolMode.Select, regionAlreadySelected = false))
        assertTrue(canRefineHitRegion(ToolMode.Select, regionAlreadySelected = true))
        assertFalse(canRefineHitRegion(ToolMode.Move, regionAlreadySelected = true))
    }

    @Test
    fun brushCreatesMaskForPlainRectangleBeforeAddingOrSubtracting() {
        val region = CropRegion("rect", 4, 4, 8, 8, selected = true)

        val added = editSelectionMask(region, Offset(14f, 8f), radius = 2, mode = MaskEditMode.Add, imageWidth = 24, imageHeight = 24)

        assertEquals(true, added.hasMask())
        assertEquals(255, added.maskAlphaAt(5, 5))
        assertEquals(255, added.maskAlphaAt(14, 8))

        val subtracted = editSelectionMask(region, Offset(5f, 5f), radius = 3, mode = MaskEditMode.Subtract, imageWidth = 24, imageHeight = 24)

        assertEquals(true, subtracted.hasMask())
        assertEquals(0, subtracted.maskAlphaAt(5, 5))
        assertEquals(255, subtracted.maskAlphaAt(10, 10))
    }

    @Test
    fun regionIntersectsSelectionBoundsOnOverlapAndEdges() {
        val region = CropRegion("r", 10, 10, 20, 20)

        assertTrue(regionIntersectsBounds(region, 0, 0, 12, 12))
        assertTrue(regionIntersectsBounds(region, 30, 30, 40, 40))
        assertFalse(regionIntersectsBounds(region, 31, 31, 40, 40))
        assertFalse(regionIntersectsBounds(region.copy(visible = false), 0, 0, 40, 40))
    }

    @Test
    fun maskBoundarySegmentsStayPixelAlignedForStairStepMask() {
        val mask = listOf(
            true, false, false,
            true, true, false,
            true, true, true
        )
        val segments = mutableListOf<MaskBoundarySegment>()

        forEachMaskBoundarySegment(
            width = 3,
            height = 3,
            isFilled = { x, y -> mask[y * 3 + x] }
        ) { startX, startY, endX, endY ->
            segments += MaskBoundarySegment(startX, startY, endX, endY)
        }

        assertTrue(segments.isNotEmpty())
        assertTrue(segments.all { it.startX == it.endX || it.startY == it.endY })
        assertTrue(MaskBoundarySegment(0, 0, 1, 0) in segments)
        assertTrue(MaskBoundarySegment(3, 2, 3, 3) in segments)
    }

    @Test
    fun viewportTransformRoundTripsThroughActualRenderedCoordinates() {
        val transform = EditorViewportTransform(
            viewportOffset = Offset(12.42f, -8.61f),
            zoom = 1.337f,
            imageWidth = 257,
            imageHeight = 193
        )
        val imagePoint = Offset(88.5f, 41.25f)

        val screenPoint = transform.imageToScreen(imagePoint)
        val roundTripped = transform.screenToImage(screenPoint)

        assertWithin(0.001f, imagePoint.x, roundTripped.x)
        assertWithin(0.001f, imagePoint.y, roundTripped.y)
        assertEquals(344, transform.renderWidth)
        assertEquals(258, transform.renderHeight)
    }

    @Test
    fun viewportTransformConvertsScreenDeltaWithRenderedZoom() {
        val transform = EditorViewportTransform(
            viewportOffset = Offset.Zero,
            zoom = 1.333f,
            imageWidth = 100,
            imageHeight = 80
        )

        val delta = transform.screenDeltaToImageDelta(Offset(133f, 107f))

        assertWithin(0.001f, 100f, delta.x)
        assertWithin(0.001f, 80f, delta.y)
    }

    private fun assertWithin(tolerance: Float, expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) <= tolerance, "Expected $actual to be within $tolerance of $expected")
    }
}
