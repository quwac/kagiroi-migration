plugins {
    `java-gradle-plugin`
    kotlin("jvm")
}

group = "dev.quwac.kagiroi"

dependencies {
    implementation(gradleApi())
    implementation(project(":kagiroi-migration-core"))
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("kagiroi.test.root", rootProject.projectDir.absolutePath)
}

gradlePlugin {
    plugins {
        create("kagiroiMigration") {
            id = "dev.quwac.kagiroi.migration"
            implementationClass = "dev.quwac.kagiroi.gradle.KagiroiMigrationPlugin"
            displayName = "Kagiroi migration plugin"
            description = "Generates and finalizes Kagiroi SQL migration artifacts."
        }
    }
}
