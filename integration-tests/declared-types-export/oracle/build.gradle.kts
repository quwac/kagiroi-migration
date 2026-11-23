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
            implementation("dev.quwac.kagiroi:kagiroi-oracle:0.0.0-local")
        }
    }
}
kagiroi {
    schema.set("example.AppSchema")
    dialect.set("oracle")
    migrationsDirectory.set(layout.buildDirectory.dir("contract-migrations"))
}
