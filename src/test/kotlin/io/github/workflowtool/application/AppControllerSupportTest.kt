package io.github.workflowtool.application

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.RegionPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AppControllerSupportTest {
    @Test
    fun buildTrainingJsonLineWritesInstancesAndPolygonPoints() {
        val line = buildTrainingJsonLine(
            imagePath = "images/sample.png",
            imageHash = "hash-1",
            regions = listOf(
                CropRegion(
                    id = "1",
                    x = 4,
                    y = 6,
                    width = 20,
                    height = 18,
                    points = listOf(
                        RegionPoint(4, 6),
                        RegionPoint(24, 6),
                        RegionPoint(24, 24),
                        RegionPoint(4, 24)
                    )
                )
            )
        )

        val root = parseJsonObject(line)
        assertNotNull(root)
        val instances = root["instances"]?.asArray()?.values.orEmpty()
        assertEquals(1, instances.size)
        val instance = instances.first().asObject()
        assertNotNull(instance)
        assertEquals(4, instance["points"]?.asArray()?.values?.size)
        assertEquals(20, instance["bbox"]?.asObject()?.get("width")?.asInt())
        assertEquals("images/sample.png", root["image"]?.asString())
    }

    @Test
    fun buildTrainingJsonLineFallsBackToRectPointsForBoxOnlyRegion() {
        val line = buildTrainingJsonLine(
            imagePath = "images/sample.png",
            imageHash = "hash-2",
            regions = listOf(CropRegion(id = "1", x = 3, y = 5, width = 9, height = 7))
        )

        val root = parseJsonObject(line)
        assertNotNull(root)
        val instance = root["instances"]?.asArray()?.values?.firstOrNull()?.asObject()
        assertNotNull(instance)
        val points = instance["points"]?.asArray()?.values.orEmpty()
        val bbox = instance["bbox"]?.asObject()
        assertNotNull(bbox)
        assertEquals(4, points.size)
        assertEquals(12, points[1].asObject()?.get("x")?.asInt())
        assertEquals(9, bbox["width"]?.asInt())
        assertEquals(7, bbox["height"]?.asInt())
    }
}
