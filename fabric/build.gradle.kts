plugins {
    id("net.fabricmc.fabric-loom")
}

base {
    archivesName.set("echosync-mod-fabric")
}

loom {
    runs {
        named("client") {
            client()
            configName = "Fabric Client"
            ideConfigGenerated(true)
            runDir("run")
        }
        named("server") {
            server()
            configName = "Fabric Server"
            ideConfigGenerated(true)
            runDir("run")
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${project.property("fabric_loader_version")}")
    api(project(":common"))
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "minecraft_version" to project.property("minecraft_version").toString(),
        "fabric_loader_version" to project.property("fabric_loader_version").toString(),
        "java_version" to project.property("java_version").toString()
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

tasks.named<Jar>("jar") {
    from(project(":common").sourceSets.main.get().output)
    from({
        project(":common").configurations.getByName("runtimeClasspath")
            .filter { it.name.startsWith("echosync-api") }
            .map { zipTree(it) }
    })
}
