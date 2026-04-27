import org.gradle.internal.os.OperatingSystem
import java.io.File

plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.compose") version "1.10.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
}

fun findExecutable(name: String): File? {
    val path = System.getenv("PATH") ?: return null
    val extensions = if (OperatingSystem.current().isWindows) {
        val pathExt = System.getenv("PATHEXT")
            ?.split(File.pathSeparatorChar)
            ?.filter { it.isNotBlank() }
            ?: listOf(".EXE", ".BAT", ".CMD")
        pathExt + ""
    } else {
        listOf("")
    }
    return path
        .split(File.pathSeparatorChar)
        .asSequence()
        .filter { it.isNotBlank() }
        .map(::File)
        .flatMap { dir -> extensions.asSequence().map { ext -> File(dir, "$name$ext") } }
        .firstOrNull { it.isFile && it.canExecute() }
}

group = "io.github.workflowtool"
version = "1.0.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("io.github.vinceglb:filekit-core:0.13.0")
    implementation("io.github.vinceglb:filekit-dialogs:0.13.0")
    implementation("net.java.dev.jna:jna:5.14.0")
    testImplementation(kotlin("test"))
}

val cppDetectorDir = layout.projectDirectory.dir("native/cpp_detector")
val cppBuildDir = cppDetectorDir.dir("build")
val cppReleaseDir = cppBuildDir.dir("release")
val hasCmake = findExecutable("cmake") != null
val hasXcrun = findExecutable("xcrun") != null
val cppLibraryName = when {
    OperatingSystem.current().isMacOsX -> "libcpp_detector.dylib"
    OperatingSystem.current().isWindows -> "cpp_detector.dll"
    else -> "libcpp_detector.so"
}
val cppLibraryFile = cppReleaseDir.file(cppLibraryName)

val configureCppDetector by tasks.registering(Exec::class) {
    group = "build"
    description = "Configures the C++ native detector used by the desktop app."
    workingDir = cppDetectorDir.asFile
    commandLine("cmake", "-S", ".", "-B", "build", "-DCMAKE_BUILD_TYPE=Release")

    inputs.file(cppDetectorDir.file("CMakeLists.txt"))
    inputs.files(
        fileTree(cppDetectorDir.dir("src")) {
            include("**/*.cpp", "**/*.h", "**/*.hpp")
        }
    )
    outputs.dir(cppBuildDir)
}

val buildCppDetector by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the C++ native detector used by the desktop app."
    dependsOn(configureCppDetector)
    workingDir = cppDetectorDir.asFile
    commandLine("cmake", "--build", "build", "--config", "Release")

    inputs.files(
        fileTree(cppDetectorDir.dir("src")) {
            include("**/*.cpp", "**/*.h", "**/*.hpp")
        }
    )
    outputs.file(cppLibraryFile)
    doLast {
        val nestedLibrary = cppReleaseDir.file("Release/$cppLibraryName").asFile
        if (nestedLibrary.isFile && nestedLibrary != cppLibraryFile.asFile) {
            cppReleaseDir.asFile.mkdirs()
            nestedLibrary.copyTo(cppLibraryFile.asFile, overwrite = true)
        }
    }
}

val buildCppDetectorBuiltinMac by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the C++ native detector directly with Apple clang on macOS."
    workingDir = cppDetectorDir.asFile
    doFirst {
        cppReleaseDir.asFile.mkdirs()
    }
    commandLine(
        "xcrun",
        "clang++",
        "-std=c++17",
        "-O2",
        "-dynamiclib",
        "-fvisibility=hidden",
        "src/cpp_detector.cpp",
        "src/detector_backend_builtin.cpp",
        "-o",
        cppLibraryFile.asFile.absolutePath
    )

    inputs.files(
        fileTree(cppDetectorDir.dir("src")) {
            include("cpp_detector.cpp", "detector_backend_builtin.cpp", "detector_backend.h")
        }
    )
    outputs.file(cppLibraryFile)
}

val buildCppDetectorForCurrentHost by tasks.registering {
    group = "build"
    description = "Builds the C++ native detector for the current host toolchain."

    when {
        OperatingSystem.current().isMacOsX && !hasCmake && hasXcrun -> dependsOn(buildCppDetectorBuiltinMac)
        hasCmake -> dependsOn(buildCppDetector)
        else -> doFirst {
            throw GradleException(
                "C++ detector build requires cmake, or on macOS can fall back to xcrun clang++. " +
                    "Current PATH did not provide a supported toolchain."
            )
        }
    }
}

val buildNativeDetector = providers.gradleProperty("buildNativeDetector").map(String::toBoolean).orElse(false)
val shouldBuildNativeForLaunch = providers.provider {
    gradle.startParameter.taskNames.any { taskName ->
        val name = taskName.substringAfterLast(':')
        name in setOf("run", "createDistributable", "packageDistributionForCurrentOS", "packageExe", "packageMsi")
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    if (buildNativeDetector.get() || shouldBuildNativeForLaunch.get()) {
        dependsOn(buildCppDetectorForCurrentHost)
    }

    from("python_detector") {
        into("python_detector")
        include("**/*")
        exclude("**/__pycache__/**")
    }
    from("third_party") {
        into("third_party")
        include("**/*")
    }
    from("scripts") {
        into("scripts")
        include("**/*")
    }
    from(cppReleaseDir) {
        into("native")
        include(cppLibraryName)
    }
}

tasks.matching { it.name in setOf("test", "run", "createDistributable", "packageDistributionForCurrentOS") }.configureEach {
    if (buildNativeDetector.get() || name == "run" || name in setOf("createDistributable", "packageDistributionForCurrentOS")) {
        dependsOn(buildCppDetectorForCurrentHost)
    }
}

compose.desktop {
    application {
        mainClass = "io.github.workflowtool.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm
            )
            packageName = "Icon Auto Crop Tool"
            packageVersion = "1.0.0"
            description = "Cross-platform desktop tool for detecting, editing, and exporting icon crops."
            vendor = "WorkFlowTool"
        }
    }
}
