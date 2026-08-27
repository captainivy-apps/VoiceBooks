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
        // epublib-core is not published to Maven Central
        maven {
            url = uri("https://raw.githubusercontent.com/psiegman/mvn-repo/master/releases/")
        }
    }
}
rootProject.name = "Voicebook"
include(":app")
