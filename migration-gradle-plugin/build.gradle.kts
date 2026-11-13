plugins {
    `java-gradle-plugin`
    kotlin("jvm")
    id("com.gradle.plugin-publish")
}

group = "dev.quwac.kagiroi"

dependencies {
    implementation(gradleApi())
    implementation(project(":migration-core"))
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("kagiroi.test.root", rootProject.projectDir.absolutePath)
    systemProperty("kagiroi.test.version", providers.gradleProperty("kagiroiVersion").get())
    val kagiroiDirectory = providers.environmentVariable("KAGIROI_DIR")
    inputs.property("kagiroiSourceEnabled", kagiroiDirectory.isPresent)
    kagiroiDirectory.orNull?.let { directory ->
        val sourceFiles = fileTree(directory) {
            exclude(
                ".git",
                ".git/**",
                ".idea/**",
                "**/build/**",
                "**/.gradle/**",
                "**/.kotlin/**",
                "**/node_modules/**",
            )
        }
        inputs.files(sourceFiles)
            .withPropertyName("kagiroiSource")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

gradlePlugin {
    website.set("https://github.com/quwac/kagiroi-migration")
    vcsUrl.set("https://github.com/quwac/kagiroi-migration.git")
    plugins {
        create("kagiroiMigration") {
            id = "dev.quwac.kagiroi.migration"
            implementationClass = "dev.quwac.kagiroi.gradle.KagiroiMigrationPlugin"
            displayName = "Kagiroi migration plugin"
            description = "Generates and verifies executable Kagiroi SQL migration artifacts."
            tags.set(listOf("kotlin", "sql", "database", "migration", "kagiroi"))
        }
    }
}

publishing {
    repositories {
        maven {
            name = "staging"
            url = rootProject.layout.buildDirectory.dir("staging-repository").get().asFile.toURI()
        }
    }
}
