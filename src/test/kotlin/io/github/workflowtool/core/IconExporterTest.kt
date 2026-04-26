package io.github.workflowtool.core

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.ExportConfig
import io.github.workflowtool.model.NamingMode
import io.github.workflowtool.model.RegionPoint
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IconExporterTest {
    @Test
    fun exportsVisibleRegionsWithSequenceNames() {
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color.RED
        graphics.fillRect(8, 8, 8, 8)
        graphics.dispose()
        val output = Files.createTempDirectory("icon-export-test")

        val result = IconExporter().export(
            image = image,
            sourceFileName = "icons.png",
            regions = listOf(CropRegion("1", 8, 8, 8, 8)),
            config = ExportConfig(
                outputDirectory = output,
                namingMode = NamingMode.Sequence,
                overwriteExisting = true
            )
        )

        assertEquals(1, result.successCount)
        val exported = output.resolve("001.png")
        assertTrue(Files.exists(exported))
        assertEquals(8, ImageIO.read(exported.toFile()).width)
    }

    @Test
    fun exportsOnlyPixelsInsidePolygonRegion() {
        val image = BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color.RED
        graphics.fillRect(0, 0, 12, 12)
        graphics.dispose()
        val output = Files.createTempDirectory("icon-export-polygon-test")

        val result = IconExporter().export(
            image = image,
            sourceFileName = "icons.png",
            regions = listOf(
                CropRegion(
                    id = "1",
                    x = 2,
                    y = 2,
                    width = 8,
                    height = 8,
                    points = listOf(
                        RegionPoint(2, 2),
                        RegionPoint(10, 2),
                        RegionPoint(2, 10)
                    )
                )
            ),
            config = ExportConfig(
                outputDirectory = output,
                namingMode = NamingMode.Sequence,
                overwriteExisting = true
            )
        )

        assertEquals(1, result.successCount)
        val exported = ImageIO.read(output.resolve("001.png").toFile())
        assertEquals(8, exported.width)
        assertEquals(8, exported.height)
        assertEquals(0, exported.getRGB(7, 7) ushr 24)
        assertEquals(255, exported.getRGB(1, 1) ushr 24)
    }

    @Test
    fun previewCropUsesPolygonShape() {
        val image = BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color.RED
        graphics.fillRect(0, 0, 12, 12)
        graphics.dispose()

        val cropped = IconExporter().cropPreview(
            image,
            CropRegion(
                id = "1",
                x = 2,
                y = 2,
                width = 8,
                height = 8,
                points = listOf(
                    RegionPoint(2, 2),
                    RegionPoint(10, 2),
                    RegionPoint(2, 10)
                )
            )
        )

        assertEquals(8, cropped.width)
        assertEquals(8, cropped.height)
        assertEquals(0, cropped.getRGB(7, 7) ushr 24)
        assertEquals(255, cropped.getRGB(1, 1) ushr 24)
    }

    @Test
    fun removesConfiguredBackgroundColorToTransparency() {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0xF2, 0xF3, 0xF5)
        graphics.fillRect(0, 0, 8, 8)
        graphics.color = Color.RED
        graphics.fillRect(2, 2, 3, 3)
        graphics.dispose()
        val output = Files.createTempDirectory("icon-export-background-test")

        val result = IconExporter().export(
            image = image,
            sourceFileName = "icons.png",
            regions = listOf(CropRegion("1", 0, 0, 8, 8)),
            config = ExportConfig(
                outputDirectory = output,
                namingMode = NamingMode.Sequence,
                overwriteExisting = true,
                removeBackgroundToTransparent = true,
                backgroundArgb = Color(0xF2, 0xF3, 0xF5).rgb,
                backgroundTolerance = 2
            )
        )

        assertEquals(1, result.successCount)
        val exported = ImageIO.read(output.resolve("001.png").toFile())
        assertEquals(0, exported.getRGB(0, 0) ushr 24)
        assertEquals(255, exported.getRGB(3, 3) ushr 24)
    }
}
