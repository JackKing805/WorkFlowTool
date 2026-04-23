package io.github.workflowtool.core

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.GridConfig

class GridSplitter {
    fun split(imageWidth: Int, imageHeight: Int, config: GridConfig): List<CropRegion> {
        val regions = mutableListOf<CropRegion>()
        var id = 1
        for (row in 0 until config.rows.coerceAtLeast(0)) {
            for (column in 0 until config.columns.coerceAtLeast(0)) {
                val x = config.offsetX + column * (config.cellWidth + config.gapX)
                val y = config.offsetY + row * (config.cellHeight + config.gapY)
                if (x < imageWidth && y < imageHeight) {
                    val width = config.cellWidth.coerceAtMost(imageWidth - x)
                    val height = config.cellHeight.coerceAtMost(imageHeight - y)
                    if (width > 0 && height > 0) {
                        regions += CropRegion(id = id.toString(), x = x, y = y, width = width, height = height)
                        id++
                    }
                }
            }
        }
        return regions
    }
}

