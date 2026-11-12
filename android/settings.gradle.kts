pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "blackbox-adv"

// Native core bridge (Rust cdylib + Kotlin facade). Unchanged by the client work.
include(":bbcore")

// Client layers: contracts, storage, coordination, UI.
include(":core:common")
include(":core:engine-api")
include(":core:engine-impl")
include(":core:database")
include(":core:data")
include(":core:domain")
include(":core:designsystem")

include(":feature:home")
include(":feature:apps")
include(":feature:instances")
include(":feature:settings")
include(":feature:diagnostics")

include(":app")
