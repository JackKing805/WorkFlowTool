package io.github.workflowtool.application

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.runCatching

internal object PythonRuntime {
    val venvDir: Path by lazy {
        configuredPath("WORKFLOWTOOL_PYTHON_VENV") ?: AppRuntimeFiles.runtimeRoot.resolve("python-venv")
    }

    val venvPython: Path
        get() = if (isWindows()) {
            venvDir.resolve("Scripts").resolve("python.exe")
        } else {
            venvDir.resolve("bin").resolve("python")
        }

    private val systemCommand: List<String>? by lazy {
        candidateSystemCommands().firstOrNull(::isCommandAvailable)
    }

    val isSystemPythonAvailable: Boolean
        get() = systemCommand != null

    val isVenvAvailable: Boolean
        get() = venvPython.exists() && isCommandAvailable(listOf(venvPython.absolutePathString()))

    val isAvailable: Boolean
        get() = isVenvAvailable || isSystemPythonAvailable

    fun status(scriptAvailable: Boolean, script: Path): String {
        return when {
            !scriptAvailable -> "python detector missing ($script)"
            isVenvAvailable -> "python venv: ${venvPython.absolutePathString()}; script: $script"
            systemCommand != null -> "system python: ${systemCommand!!.joinToString(" ")}; venv pending: $venvDir; script: $script"
            else -> "system python missing (tried: ${attemptedCommands()}); script: $script"
        }
    }

    fun buildCommand(args: List<String>): List<String>? {
        return if (isVenvAvailable) {
            listOf(venvPython.absolutePathString()) + args
        } else {
            null
        }
    }

    fun buildSystemCommand(args: List<String>): List<String>? {
        return systemCommand?.plus(args)
    }

    fun configureProcess(builder: ProcessBuilder): ProcessBuilder {
        val env = builder.environment()
        env["PYTHONNOUSERSITE"] = "1"
        env.remove("PIP_NO_INDEX")
        env.remove("WORKFLOWTOOL_OFFLINE")
        env.remove("WORKFLOWTOOL_DISABLE_NETWORK")
        return builder
    }

    private fun candidateSystemCommands(): List<List<String>> {
        val configured = configuredCommand()
        val defaults = if (isWindows()) {
            listOf(listOf("py", "-3"), listOf("python"))
        } else {
            listOf(listOf("python3"), listOf("python"))
        }
        return (listOfNotNull(configured) + defaults)
            .distinctBy { it.joinToString("\u0000") }
    }

    private fun configuredCommand(): List<String>? {
        val raw = sequenceOf("WORKFLOWTOOL_PYTHON", "PYTHON")
            .firstNotNullOfOrNull {
                System.getenv(it)?.trim()?.takeIf(String::isNotEmpty)
            } ?: return null
        return raw.split(Regex("\\s+")).filter(String::isNotEmpty)
    }

    private fun configuredPath(name: String): Path? {
        return System.getenv(name)?.trim()?.takeIf(String::isNotEmpty)?.let(::Path)
    }

    private fun attemptedCommands(): String {
        return candidateSystemCommands().joinToString(", ") { it.joinToString(" ") }
    }

    private fun isCommandAvailable(command: List<String>): Boolean {
        return runCatching {
            val process = configureProcess(
                ProcessBuilder(command + "--version")
                    .redirectErrorStream(true)
            ).start()
            process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            process.waitFor() == 0
        }.getOrDefault(false)
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    }
}
