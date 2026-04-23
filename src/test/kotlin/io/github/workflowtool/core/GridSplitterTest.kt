package io.github.workflowtool.core

import io.github.workflowtool.model.GridConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class GridSplitterTest {
    @Test
    fun createsGridRegionsWithinImageBounds() {
        val regions = GridSplitter().split(
            imageWidth = 205,
            imageHeight = 100,
            config = GridConfig(cellWidth = 96, cellHeight = 96, columns = 3, rows = 1, gapX = 4)
        )

        assertEquals(3, regions.size)
        assertEquals(5, regions.last().width)
        assertEquals(96, regions.first().height)
    }
}

