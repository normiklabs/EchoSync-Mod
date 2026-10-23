pluginManagement {
    val loomVersion = providers.gradleProperty("loom_version").get()
    val moddevVersion = providers.gradleProperty("moddev_version").get()

    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases/")
        gradlePluginPortal()
        mavenCentral()
    }

    plugins {
        id("net.fabricmc.fabric-loom") version loomVersion
        id("net.neoforged.moddev") version moddevVersion
    }
}

rootProject.name = "echosync-mod"
include("common", "fabric", "neoforge")
