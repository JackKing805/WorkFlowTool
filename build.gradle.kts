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
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
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
