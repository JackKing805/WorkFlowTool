package io.github.workflowtool.application

import io.github.workflowtool.model.CropRegion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AppControllerSupportTest {
    @Test
    fun buildTrainingJsonLineWritesOnlyMaskFirstInstances() {
        val line = buildTrainingJsonLine(
            imagePath = "images/sample.png",
            imageHash = "hash-1",
            regions = listOf(
                CropRegion(
                    id = "1",
                    x = 4,
                    y = 6,
                    width = 2,
                    height = 2,
                    maskWidth = 2,
                    maskHeight = 2,
                    alphaMask = listOf(255, 0, 220, 255)
                )
            )
        )

        val root = parseJsonObject(line)
        assertNotNull(root)
        assertEquals(null, root["regions"])
        val instance = root["instances"]?.asArray()?.values?.firstOrNull()?.asObject()
        assertNotNull(instance)
        assertEquals(null, instance["points"])
        assertEquals(null, instance["holes"])
        assertEquals(2, instance["maskWidth"]?.asInt())
        assertEquals(4, instance["alphaMask"]?.asArray()?.values?.size)
        assertEquals("images/sample.png", root["image"]?.asString())
    }
}
