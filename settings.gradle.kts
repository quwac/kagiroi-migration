pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

providers.environmentVariable("KAGIROI_DIR").orNull?.let { directory ->
    includeBuild(directory) {
        name = "kagiroi"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kagiroi-migration"

include(
    ":migration-core",
    ":migration-gradle-plugin",
    ":migration-cli",
)
