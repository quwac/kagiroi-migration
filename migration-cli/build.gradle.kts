plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

val kagiroiVersion = providers.gradleProperty("kagiroiVersion").get()

kotlin {
    js {
        nodejs()
        binaries.executable()
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":migration-core"))
            implementation("dev.quwac.kagiroi:kagiroi-sql:$kagiroiVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        jsMain.dependencies {
            // Kagiroi driver artifacts provide Kotlin code. Kotlin/JS needs the same host modules
            // in this executable's npm graph when those artifacts are linked.
            implementation(npm("pg", "8.23.1"))
            implementation(npm("mysql2", "3.23.1"))
            implementation(npm("oracledb", "6.10.0"))
            implementation(npm("@electric-sql/pglite", "0.5.8"))
            implementation(npm("better-sqlite3", "13.0.3"))
            implementation(npm("mssql", "12.7.1"))
            implementation(npm("tedious", "20.3.8"))
            implementation("dev.quwac.kagiroi:kagiroi-d1-driver:$kagiroiVersion")
            implementation("dev.quwac.kagiroi:kagiroi-mysql-node-driver:$kagiroiVersion")
            implementation("dev.quwac.kagiroi:kagiroi-node-oracle-driver:$kagiroiVersion")
            implementation("dev.quwac.kagiroi:kagiroi-pglite-driver:$kagiroiVersion")
            implementation("dev.quwac.kagiroi:kagiroi-postgres-node-driver:$kagiroiVersion")
            implementation("dev.quwac.kagiroi:kagiroi-sqlite-file-driver:$kagiroiVersion")
            implementation("dev.quwac.kagiroi:kagiroi-sqlserver-node-driver:$kagiroiVersion")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
