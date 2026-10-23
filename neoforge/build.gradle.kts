plugins {
    id("net.neoforged.moddev")
}

base {
    archivesName.set("echosync-mod-neoforge")
}

neoForge {
    version = project.property("neoforge_version").toString()

    runs {
        create("client") {
            client()
        }
        create("server") {
            server()
            programArgument("--nogui")
        }
    }

    mods {
        create("echosync") {
            sourceSet(sourceSets.main.get())
            sourceSet(project(":common").sourceSets.main.get())
        }
    }
}

dependencies {
    api(project(":common"))
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "minecraft_version" to project.property("minecraft_version").toString(),
        "neoforge_version" to project.property("neoforge_version").toString()
    )
    inputs.properties(props)
    filesMatching("META-INF/neoforge.mods.toml") {
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
