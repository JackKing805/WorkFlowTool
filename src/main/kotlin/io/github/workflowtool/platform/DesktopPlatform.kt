package io.github.workflowtool.platform

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absoluteFile
import io.github.vinceglb.filekit.picturesDir
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.dialogs.openFileWithDefaultApplication
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.io.path.Path

object DesktopPlatform {
    fun defaultOutputDirectory(): Path {
        val home = System.getProperty("user.home")
        val desktop = Path(home, "Desktop")
        return desktop.resolve("icons_out")
    }

    fun chooseImageFile(): File? = runBlocking {
        FileKit.openFilePicker(
            type = FileKitType.Image,
            directory = FileKit.picturesDir
        )?.toJavaFile()
    }

    fun chooseDirectory(): File? = runBlocking {
        FileKit.openDirectoryPicker(
            directory = PlatformFile(defaultOutputDirectory().toString()).absoluteFile()
        )?.toJavaFile()
    }

    @Suppress("UNUSED_PARAMETER")
    fun chooseSaveFile(
        title: String,
        suggestedFileName: String,
        initialDirectory: Path? = null
    ): File? = runBlocking {
        val suggested = File(suggestedFileName)
        val suggestedName = suggested.nameWithoutExtension.ifBlank { suggested.name }
        val extension = suggested.extension.ifBlank { null }
        FileKit.openFileSaver(
            suggestedName = suggestedName,
            extension = extension,
            directory = initialDirectory?.let { PlatformFile(it.toString()).absoluteFile() }
        )?.toJavaFile()
    }

    fun openDirectory(path: Path) {
        FileKit.openFileWithDefaultApplication(PlatformFile(path.toString()).absoluteFile())
    }
}

private fun PlatformFile.toJavaFile(): File = file
