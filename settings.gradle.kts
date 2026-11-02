pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kagiroi-migration"

include(
    ":kagiroi-migration-core",
    ":kagiroi-migration",
    ":kagiroi-gradle-plugin",
)
