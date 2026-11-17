pluginManagement {
    val kagiroiSource = providers.gradleProperty("kagiroiSource").orNull
        ?: error("Pass -PkagiroiSource=/absolute/path/to/kagiroi")
    includeBuild(kagiroiSource) { name = "kagiroi" }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    includeBuild("../..") { name = "kagiroi-migration" }
}
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "kagiroi-declared-types-contract"
include(":postgres", ":mysql", ":mariadb", ":sqlite", ":sqlserver", ":oracle")
