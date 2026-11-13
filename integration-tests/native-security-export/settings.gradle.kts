pluginManagement {
    val kagiroiSource = providers.gradleProperty("kagiroiSource").orNull
        ?: error("Pass -PkagiroiSource=/absolute/path/to/kagiroi with native security APIs")
    includeBuild(kagiroiSource) { name = "kagiroi" }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    includeBuild("../..") { name = "kagiroi-migration" }
}
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "kagiroi-native-security-contract"
include(":postgres", ":sqlserver", ":oracle")
