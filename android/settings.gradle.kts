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
        // Pleos Connect SDK (설계 §6.1). 공개 저장소, 인증 없음. Pleos 패키지만 여기서 찾는다.
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/") {
            content { includeGroupByRegex("ai\\.pleos\\..*") }
        }
    }
}

rootProject.name = "drive-song"

include(
    ":app",
    ":core:model",
    ":core:data",
    ":core:network",
    ":core:vehicle",
    ":feature:trip",
    ":feature:lyrics",
    ":feature:song",
    ":feature:voice",
    ":feature:player",
    ":feature:library",
)
