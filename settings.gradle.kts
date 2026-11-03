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
    ":migration-core",
    ":migration-gradle-plugin",
    ":migration-cli",
    ":integration-tests:hyperdrive",
)
