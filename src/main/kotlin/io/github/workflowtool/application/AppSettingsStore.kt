package io.github.workflowtool.application

import io.github.workflowtool.model.DetectionConfig
import io.github.workflowtool.model.GridConfig
import io.github.workflowtool.model.ImageFormat
import io.github.workflowtool.model.NamingMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

internal data class AppSettings(
    val detectionConfig: DetectionConfig = DetectionConfig(),
    val gridConfig: GridConfig = GridConfig(),
    val outputDirectory: Path? = null,
    val outputFormat: ImageFormat = ImageFormat.PNG,
    val namingMode: NamingMode = NamingMode.Sequence,
    val customPrefix: String = "icon",
    val keepOriginalSize: Boolean = true,
    val trimTransparent: Boolean = false,
    val removeBackgroundToTransparent: Boolean = false,
    val backgroundRemovalTolerance: Int = 20,
    val padToSquare: Boolean = false,
    val fixedSizeText: String = "",
    val overwriteExisting: Boolean = false,
    val continuousTrainingEnabled: Boolean = false,
    val showGrid: Boolean = true,
    val recentFiles: List<Path> = emptyList()
)

internal object AppSettingsStore {
    private const val MaxRecentFiles = 12
    private val settingsFile: Path get() = AppRuntimeFiles.runtimeRoot.resolve("settings.json")

    fun load(): AppSettings {
        val file = settingsFile
        if (!file.exists()) return AppSettings()
        return runCatching {
            val root = parseJsonObject(Files.readString(file, Charsets.UTF_8)) ?: return@runCatching AppSettings()
            AppSettings(
                detectionConfig = readDetectionConfig(root["detectionConfig"]?.asObject()),
                gridConfig = readGridConfig(root["gridConfig"]?.asObject()),
                outputDirectory = root["outputDirectory"]?.asString()?.takeIf(String::isNotBlank)?.let(Path::of),
                outputFormat = root["outputFormat"]?.asString()?.let { enumValueOrNull<ImageFormat>(it) } ?: ImageFormat.PNG,
                namingMode = root["namingMode"]?.asString()?.let { enumValueOrNull<NamingMode>(it) } ?: NamingMode.Sequence,
                customPrefix = root["customPrefix"]?.asString() ?: "icon",
                keepOriginalSize = root["keepOriginalSize"]?.asBoolean() ?: true,
                trimTransparent = root["trimTransparent"]?.asBoolean() ?: false,
                removeBackgroundToTransparent = root["removeBackgroundToTransparent"]?.asBoolean() ?: false,
                backgroundRemovalTolerance = root["backgroundRemovalTolerance"]?.asInt()?.coerceIn(0, 255) ?: 20,
                padToSquare = root["padToSquare"]?.asBoolean() ?: false,
                fixedSizeText = root["fixedSizeText"]?.asString()?.filter(Char::isDigit).orEmpty(),
                overwriteExisting = root["overwriteExisting"]?.asBoolean() ?: false,
                continuousTrainingEnabled = root["continuousTrainingEnabled"]?.asBoolean() ?: false,
                showGrid = root["showGrid"]?.asBoolean() ?: true,
                recentFiles = root["recentFiles"]?.asArray()?.values
                    ?.mapNotNull { it.asString()?.takeIf(String::isNotBlank)?.let(Path::of) }
                    ?.distinct()
                    ?.take(MaxRecentFiles)
                    .orEmpty()
            )
        }.getOrDefault(AppSettings())
    }

    fun save(settings: AppSettings) {
        runCatching {
            Files.createDirectories(settingsFile.parent)
            Files.writeString(settingsFile, settings.toJson(), Charsets.UTF_8)
        }
    }

    fun sanitizedRecentFiles(paths: List<Path>): List<Path> =
        paths.map { it.toAbsolutePath().normalize() }
            .distinct()
            .filter { Files.isRegularFile(it) }
            .take(MaxRecentFiles)

    private fun readDetectionConfig(obj: JsonValue.JsonObject?): DetectionConfig {
        val defaults = DetectionConfig()
        if (obj == null) return defaults
        return DetectionConfig(
            minWidth = obj["minWidth"]?.asInt() ?: defaults.minWidth,
            minHeight = obj["minHeight"]?.asInt() ?: defaults.minHeight,
            gapThreshold = obj["gapThreshold"]?.asInt() ?: defaults.gapThreshold,
            alphaThreshold = obj["alphaThreshold"]?.asInt() ?: defaults.alphaThreshold,
            backgroundTolerance = obj["backgroundTolerance"]?.asInt() ?: defaults.backgroundTolerance,
            edgeSampleWidth = obj["edgeSampleWidth"]?.asInt() ?: defaults.edgeSampleWidth,
            minPixelArea = obj["minPixelArea"]?.asInt() ?: defaults.minPixelArea,
            colorDistanceThreshold = obj["colorDistanceThreshold"]?.asInt() ?: defaults.colorDistanceThreshold,
            dilateIterations = obj["dilateIterations"]?.asInt() ?: defaults.dilateIterations,
            erodeIterations = obj["erodeIterations"]?.asInt() ?: defaults.erodeIterations,
            enableHoleFill = obj["enableHoleFill"]?.asBoolean() ?: defaults.enableHoleFill,
            bboxPadding = obj["bboxPadding"]?.asInt() ?: defaults.bboxPadding,
            mergeNearbyRegions = obj["mergeNearbyRegions"]?.asBoolean() ?: defaults.mergeNearbyRegions,
            removeSmallRegions = obj["removeSmallRegions"]?.asBoolean() ?: defaults.removeSmallRegions,
            useManualBackground = obj["useManualBackground"]?.asBoolean() ?: defaults.useManualBackground,
            manualBackgroundArgb = obj["manualBackgroundArgb"]?.asInt() ?: defaults.manualBackgroundArgb
        )
    }

    private fun readGridConfig(obj: JsonValue.JsonObject?): GridConfig {
        val defaults = GridConfig()
        if (obj == null) return defaults
        return GridConfig(
            cellWidth = obj["cellWidth"]?.asInt() ?: defaults.cellWidth,
            cellHeight = obj["cellHeight"]?.asInt() ?: defaults.cellHeight,
            columns = obj["columns"]?.asInt() ?: defaults.columns,
            rows = obj["rows"]?.asInt() ?: defaults.rows,
            offsetX = obj["offsetX"]?.asInt() ?: defaults.offsetX,
            offsetY = obj["offsetY"]?.asInt() ?: defaults.offsetY,
            gapX = obj["gapX"]?.asInt() ?: defaults.gapX,
            gapY = obj["gapY"]?.asInt() ?: defaults.gapY,
            snapToContent = obj["snapToContent"]?.asBoolean() ?: defaults.snapToContent,
            searchPadding = obj["searchPadding"]?.asInt() ?: defaults.searchPadding,
            ignoreEmptyCells = obj["ignoreEmptyCells"]?.asBoolean() ?: defaults.ignoreEmptyCells,
            trimCellToContent = obj["trimCellToContent"]?.asBoolean() ?: defaults.trimCellToContent,
            alphaThreshold = obj["alphaThreshold"]?.asInt() ?: defaults.alphaThreshold,
            backgroundTolerance = obj["backgroundTolerance"]?.asInt() ?: defaults.backgroundTolerance
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }

    private fun AppSettings.toJson(): String = buildString {
        append("{\n")
        append("  \"detectionConfig\": ${detectionConfig.toJson()},\n")
        append("  \"gridConfig\": ${gridConfig.toJson()},\n")
        append("  \"outputDirectory\": ${jsonString(outputDirectory?.toString().orEmpty())},\n")
        append("  \"outputFormat\": ${jsonString(outputFormat.name)},\n")
        append("  \"namingMode\": ${jsonString(namingMode.name)},\n")
        append("  \"customPrefix\": ${jsonString(customPrefix)},\n")
        append("  \"keepOriginalSize\": $keepOriginalSize,\n")
        append("  \"trimTransparent\": $trimTransparent,\n")
        append("  \"removeBackgroundToTransparent\": $removeBackgroundToTransparent,\n")
        append("  \"backgroundRemovalTolerance\": $backgroundRemovalTolerance,\n")
        append("  \"padToSquare\": $padToSquare,\n")
        append("  \"fixedSizeText\": ${jsonString(fixedSizeText)},\n")
        append("  \"overwriteExisting\": $overwriteExisting,\n")
        append("  \"continuousTrainingEnabled\": $continuousTrainingEnabled,\n")
        append("  \"showGrid\": $showGrid,\n")
        append("  \"recentFiles\": [${recentFiles.joinToString(", ") { jsonString(it.toString()) }}]\n")
        append("}\n")
    }

    private fun DetectionConfig.toJson(): String = compactJson(
        "minWidth" to minWidth,
        "minHeight" to minHeight,
        "gapThreshold" to gapThreshold,
        "alphaThreshold" to alphaThreshold,
        "backgroundTolerance" to backgroundTolerance,
        "edgeSampleWidth" to edgeSampleWidth,
        "minPixelArea" to minPixelArea,
        "colorDistanceThreshold" to colorDistanceThreshold,
        "dilateIterations" to dilateIterations,
        "erodeIterations" to erodeIterations,
        "enableHoleFill" to enableHoleFill,
        "bboxPadding" to bboxPadding,
        "mergeNearbyRegions" to mergeNearbyRegions,
        "removeSmallRegions" to removeSmallRegions,
        "useManualBackground" to useManualBackground,
        "manualBackgroundArgb" to manualBackgroundArgb
    )

    private fun GridConfig.toJson(): String = compactJson(
        "cellWidth" to cellWidth,
        "cellHeight" to cellHeight,
        "columns" to columns,
        "rows" to rows,
        "offsetX" to offsetX,
        "offsetY" to offsetY,
        "gapX" to gapX,
        "gapY" to gapY,
        "snapToContent" to snapToContent,
        "searchPadding" to searchPadding,
        "ignoreEmptyCells" to ignoreEmptyCells,
        "trimCellToContent" to trimCellToContent,
        "alphaThreshold" to alphaThreshold,
        "backgroundTolerance" to backgroundTolerance
    )

    private fun compactJson(vararg values: Pair<String, Any>): String =
        values.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"$key\":${if (value is String) jsonString(value) else value}"
        }

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            value.forEach { char ->
                append(
                    when (char) {
                        '\\' -> "\\\\"
                        '"' -> "\\\""
                        '\b' -> "\\b"
                        '\u000C' -> "\\f"
                        '\n' -> "\\n"
                        '\r' -> "\\r"
                        '\t' -> "\\t"
                        else -> char
                    }
                )
            }
            append('"')
        }
}
