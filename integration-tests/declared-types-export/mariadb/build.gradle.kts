plugins {
    kotlin("multiplatform") version "2.4.21"
    id("dev.quwac.kagiroi.migration")
}
kotlin {
    js {
        nodejs()
        useEsModules()
    }
    sourceSets {
        jsMain.dependencies {
            implementation("dev.quwac.kagiroi:kagiroi-mariadb:0.0.0-local")
            implementation("dev.quwac.kagiroi:kagiroi-mysql:0.0.0-local")
        }
    }
}
kagiroi {
    schema.set("example.AppSchema")
    dialect.set("mariadb")
    migrationsDirectory.set(layout.buildDirectory.dir("contract-migrations"))
}
