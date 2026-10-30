pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "EdgeVoiceAgent"

// 严格五层单向依赖：app -> ui-presentation / sdk-core -> core-media / core-transport / core-executor / hardware-bridge
include(":app")
include(":ui-presentation")
include(":sdk-core")
include(":core-media")
include(":core-transport")
include(":core-executor")
include(":hardware-bridge")
