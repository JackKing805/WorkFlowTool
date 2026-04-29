package io.github.workflowtool.platform

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absoluteFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.dialogs.openFileWithDefaultApplication
import io.github.vinceglb.filekit.picturesDir
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists

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
            directory = PlatformFile(existingPickerDirectory(defaultOutputDirectory()).toString()).absoluteFile()
        )?.toJavaFile()
    }

    fun chooseSaveFile(
        suggestedFileName: String,
        initialDirectory: Path? = null
    ): File? = runBlocking {
        val suggested = File(suggestedFileName)
        val suggestedName = suggested.nameWithoutExtension.ifBlank { suggested.name }
        val extension = suggested.extension.ifBlank { null }
        FileKit.openFileSaver(
            suggestedName = suggestedName,
            extension = extension,
            directory = initialDirectory?.let { PlatformFile(existingPickerDirectory(it).toString()).absoluteFile() }
        )?.toJavaFile()
    }

    fun openDirectory(path: Path) {
        FileKit.openFileWithDefaultApplication(PlatformFile(path.toString()).absoluteFile())
    }
}

private fun PlatformFile.toJavaFile(): File = file

private fun existingPickerDirectory(path: Path): Path {
    runCatching { Files.createDirectories(path) }
    if (path.exists()) return path
    val home = System.getProperty("user.home")?.let { Path(it) }
    if (home != null && home.exists()) return home
    return Path(System.getProperty("java.io.tmpdir"))
}
