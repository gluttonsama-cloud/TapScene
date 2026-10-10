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
rootProject.name = "TapScene"
include(":app")
// Synthetic input receiver is an opt-in CI fixture, never an app dependency.
if (providers.gradleProperty("tapsceneRuntimeSmoke").orNull == "true") {
    include(":runtime-target")
}
