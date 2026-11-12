@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.vanniktech.maven.publish")
}

group = "dev.quwac.kagiroi"

publishing {
    repositories {
        maven {
            name = "staging"
            url = rootProject.layout.buildDirectory.dir("staging-repository").get().asFile.toURI()
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("Kagiroi Migration Core")
        description.set("Schema migration generation, verification, and execution APIs for Kagiroi.")
        url.set("https://github.com/quwac/kagiroi-migration")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit")
            }
        }
        developers {
            developer {
                id.set("quwac")
                name.set("Quwac")
                url.set("https://github.com/quwac")
            }
        }
        scm {
            url.set("https://github.com/quwac/kagiroi-migration")
            connection.set("scm:git:https://github.com/quwac/kagiroi-migration.git")
            developerConnection.set("scm:git:ssh://git@github.com/quwac/kagiroi-migration.git")
        }
    }
}

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
