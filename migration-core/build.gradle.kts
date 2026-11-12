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
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
