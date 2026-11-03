@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

group = "dev.quwac.kagiroi"

val kagiroiVersion = providers.gradleProperty("kagiroiVersion").get()

kotlin {
    jvm()
    js {
        nodejs()
    }
    wasmJs {
        nodejs()
    }
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
        }
        jsMain.dependencies {
            api("dev.quwac.kagiroi:kagiroi-sql:$kagiroiVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jsTest.dependencies {
            implementation("dev.quwac.kagiroi:kagiroi-postgres-hyperdrive-driver:$kagiroiVersion")
            implementation("dev.quwac.kagiroi:kagiroi-mysql-hyperdrive-driver:$kagiroiVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            implementation(npm("pg", "8.23.1"))
            implementation(npm("mysql2", "3.23.1"))
        }
    }
}
