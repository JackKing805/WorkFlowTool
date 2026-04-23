package io.github.workflowtool.core

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.ExportConfig
import io.github.workflowtool.model.NamingMode
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
}

