plugins {
    kotlin("multiplatform")
}

val kagiroiVersion = providers.gradleProperty("kagiroiVersion").get()

kotlin {
    js {
        nodejs()
        useEsModules()
        binaries.executable()
    }
    sourceSets {
        jsMain.dependencies {
            implementation(project(":migration-core"))
            implementation("dev.quwac.kagiroi:kagiroi-postgres-hyperdrive-driver:$kagiroiVersion")
            implementation("dev.quwac.kagiroi:kagiroi-mysql-hyperdrive-driver:$kagiroiVersion")
            implementation(npm("pg", "8.23.1"))
            implementation(npm("mysql2", "3.23.1"))
        }
    }
}
