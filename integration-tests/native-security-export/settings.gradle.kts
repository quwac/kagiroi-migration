pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    includeBuild("../..")
}
dependencyResolutionManagement { repositories { mavenCentral() } }
val kagiroiSource = providers.gradleProperty("kagiroiSource").orNull
    ?: error("Pass -PkagiroiSource=/absolute/path/to/kagiroi with native security APIs")
includeBuild(kagiroiSource) { name = "kagiroi" }
rootProject.name = "kagiroi-native-security-contract"
include(":postgres", ":sqlserver", ":oracle")
