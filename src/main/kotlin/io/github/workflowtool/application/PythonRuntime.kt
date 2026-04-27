package io.github.workflowtool.application

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.runCatching

internal object PythonRuntime {
    private val resolvedCommand: List<String>? by lazy {
        candidateCommands().firstOrNull(::isCommandAvailable)
    }

    val isAvailable: Boolean
        get() = resolvedCommand != null

    fun status(scriptAvailable: Boolean, script: Path): String {
        return when {
            !scriptAvailable -> "python detector missing ($script)"
            resolvedCommand != null -> "python: ${resolvedCommand!!.joinToString(" ")}; script: $script"
            else -> "python interpreter missing (tried: ${attemptedCommands()}); script: $script"
        }
    }

    fun buildCommand(args: List<String>): List<String>? {
        return resolvedCommand?.plus(args)
    }

    fun configureProcess(builder: ProcessBuilder): ProcessBuilder {
        val env = builder.environment()
        env["PYTHONNOUSERSITE"] = "1"
        env["PIP_NO_INDEX"] = "1"
        env["WORKFLOWTOOL_OFFLINE"] = "1"
        env["WORKFLOWTOOL_DISABLE_NETWORK"] = "1"
        return builder
    }

    private fun candidateCommands(): List<List<String>> {
        val configured = configuredCommand()
        val bundled = bundledCommands()
        val defaults = if (allowSystemPython()) {
            if (isWindows()) {
                listOf(listOf("py", "-3"), listOf("python"))
            } else {
                listOf(listOf("python3"), listOf("python"))
            }
        } else {
            emptyList()
        }
        return (listOfNotNull(configured) + bundled + defaults)
            .distinctBy { it.joinToString("\u0000") }
    }

    private fun configuredCommand(): List<String>? {
        val raw = sequenceOf("WORKFLOWTOOL_PYTHON", "PYTHON")
            .firstNotNullOfOrNull {
                System.getenv(it)?.trim()?.takeIf(String::isNotEmpty)
            } ?: return null
        return raw.split(Regex("\\s+")).filter(String::isNotEmpty)
    }

    private fun bundledCommands(): List<List<String>> {
        return bundledExecutableCandidates().map { listOf(it.absolutePathString()) }
    }

    internal fun bundledExecutableCandidates(): List<Path> {
        AppRuntimeFiles.prepareBundledDependencies()
        return buildList {
            bundledPythonRoots().forEach { root ->
                pythonExecutableCandidates(root).forEach { candidate ->
                    if (candidate.exists()) add(candidate)
                }
            }
        }.distinct()
    }

    private fun bundledPythonRoots(): List<Path> {
        val projectRoot = Path(System.getProperty("user.dir"))
        val osDir = when {
            isWindows() -> "windows"
            isMac() -> "macos"
            else -> "linux"
        }
        val generic = if (isWindows()) "win" else if (isMac()) "mac" else "linux"
        return listOf(
            AppRuntimeFiles.runtimeRoot.resolve("python-runtime"),
            AppRuntimeFiles.runtimeRoot.resolve("third_party").resolve("python"),
            AppRuntimeFiles.runtimeRoot.resolve("third_party").resolve("python").resolve(osDir),
            AppRuntimeFiles.runtimeRoot.resolve("third_party").resolve("python").resolve(generic),
            projectRoot.resolve("third_party").resolve("python"),
            projectRoot.resolve("third_party").resolve("python").resolve(osDir),
            projectRoot.resolve("third_party").resolve("python").resolve(generic),
            projectRoot.resolve(".runtime").resolve("python")
        ).distinct()
    }

    private fun pythonExecutableCandidates(root: Path): List<Path> {
        return if (isWindows()) {
            listOf(
                root.resolve("python.exe"),
                root.resolve("bin").resolve("python.exe"),
                root.resolve("Scripts").resolve("python.exe")
            )
        } else {
            listOf(
                root.resolve("bin").resolve("python3"),
                root.resolve("bin").resolve("python")
            )
        }
    }

    private fun attemptedCommands(): String {
        return candidateCommands().joinToString(", ") { it.joinToString(" ") }
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

    private fun isMac(): Boolean {
        return System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    }

    private fun allowSystemPython(): Boolean {
        val configured = System.getenv("WORKFLOWTOOL_ALLOW_SYSTEM_PYTHON")
            ?.trim()
            ?.lowercase()
        if (configured != null) return configured == "1" || configured == "true" || configured == "yes"
        val disabled = System.getenv("WORKFLOWTOOL_DISABLE_SYSTEM_PYTHON")
            ?.trim()
            ?.lowercase()
            ?.let { it == "1" || it == "true" || it == "yes" }
            ?: false
        return !disabled
    }

    internal fun systemFallbackEnabled(): Boolean = allowSystemPython()
}
