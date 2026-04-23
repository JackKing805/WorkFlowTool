package io.github.workflowtool.platform

import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path

object DesktopPlatform {
    fun defaultOutputDirectory(): Path {
        val home = System.getProperty("user.home")
        val desktop = Path(home, "Desktop")
        return desktop.resolve("icons_out")
    }

    fun chooseImageFile(): File? {
        val dialog = FileDialog(null as Frame?, "Open image", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name ->
            val lower = name.lowercase()
            lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")
        }
        dialog.isVisible = true
        val file = dialog.file ?: return null
        return File(dialog.directory, file)
    }

    fun chooseDirectory(): File? {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        val dialog = FileDialog(null as Frame?, "Choose output directory", FileDialog.LOAD)
        dialog.isVisible = true
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
        val file = dialog.file ?: return null
        return File(dialog.directory, file)
    }

    fun openDirectory(path: Path) {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(path.toFile())
        }
    }
}

