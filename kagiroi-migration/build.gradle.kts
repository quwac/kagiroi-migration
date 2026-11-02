@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

val kagiroiVersion = providers.gradleProperty("kagiroiVersion").get()

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

group = "dev.quwac.kagiroi"

kotlin {
    js {
        nodejs()
    }
    wasmJs {
        nodejs()
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":kagiroi-migration-core"))
            api("dev.quwac.kagiroi:kagiroi-schema:$kagiroiVersion")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("dev.quwac.kagiroi:kagiroi-sql:$kagiroiVersion")
        }
    }
}
