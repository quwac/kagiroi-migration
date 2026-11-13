import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.npm.NpmExtension
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.npm.WasmNpmExtension

plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    id("com.gradle.plugin-publish") version "2.2.1" apply false
    id("dev.detekt") version "2.0.0-alpha.6"
}

if (providers.environmentVariable("KAGIROI_DIR").isPresent) {
    plugins.withType<NodeJsRootPlugin> {
        NpmExtension[rootProject].lockFileDirectory.set(layout.projectDirectory.dir("kotlin-js-store/kagiroi-source"))
    }
    plugins.withType<WasmNodeJsRootPlugin> {
        WasmNpmExtension[rootProject].lockFileDirectory.set(
            layout.projectDirectory.dir("kotlin-js-store/kagiroi-source/wasm"),
        )
    }
}

allprojects {
    group = "dev.quwac.kagiroi"
    version = providers.gradleProperty("version").getOrElse("0.1.0-SNAPSHOT")
}

dependencies {
    detektPlugins("dev.detekt:detekt-rules-ktlint-wrapper:2.0.0-alpha.6")
}

detekt {
    toolVersion = "2.0.0-alpha.6"
    source.setFrom(
        files("build.gradle.kts", "settings.gradle.kts"),
        layout.projectDirectory.dir("integration-tests/native-security-export"),
        subprojects.map { files(it.layout.projectDirectory.dir("src"), it.buildFile) },
    )
    config.setFrom(layout.projectDirectory.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    basePath.set(rootDir)
}

tasks.named<dev.detekt.gradle.Detekt>("detekt") {
    include("**/*.kt", "**/*.kts")
    exclude("integration-tests/native-security-export/**/build/**")
    exclude("integration-tests/native-security-export/**/.gradle/**")
}
