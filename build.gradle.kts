import org.gradle.internal.os.OperatingSystem

plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.compose") version "1.10.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
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
val embedOpenCv = providers.gradleProperty("embedOpenCv").map(String::toBoolean).orElse(false)
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
    val configureArgs = mutableListOf("cmake", "-S", ".", "-B", "build", "-DCMAKE_BUILD_TYPE=Release")
    if (embedOpenCv.get()) {
        configureArgs += "-DCPP_DETECTOR_FETCH_OPENCV=ON"
    }
    commandLine(configureArgs)

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
}

tasks.test {
    useJUnitPlatform()
}

val buildNativeDetector = providers.gradleProperty("buildNativeDetector").map(String::toBoolean).orElse(false)

tasks.matching { it.name in setOf("test", "run", "createDistributable", "packageDistributionForCurrentOS") }.configureEach {
    if (buildNativeDetector.get()) {
        dependsOn(buildCppDetector)
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
