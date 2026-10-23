base {
    archivesName.set("echosync-mod-common")
}

dependencies {
    api("org.normik.echosync:echosync-api:${project.version}")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.12.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.java-websocket:Java-WebSocket:1.6.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
