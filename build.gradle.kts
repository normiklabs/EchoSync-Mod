import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.plugins.signing.SigningExtension

plugins {
    id("net.fabricmc.fabric-loom") apply false
    id("net.neoforged.moddev") apply false
}

val javaVersionProp = property("java_version").toString().toInt()
val groupProp = property("group").toString()
val versionProp = property("version").toString()

subprojects {
    pluginManager.apply("java-library")
    pluginManager.apply("maven-publish")
    pluginManager.apply("signing")

    group = groupProp
    version = versionProp
    description = rootProject.property("pom_description").toString()

    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases/")
    }

    configurations.all {
        resolutionStrategy {
            force(
                "org.ow2.asm:asm:9.11.1",
                "org.ow2.asm:asm-analysis:9.11.1",
                "org.ow2.asm:asm-commons:9.11.1",
                "org.ow2.asm:asm-tree:9.11.1",
                "org.ow2.asm:asm-util:9.11.1",
                "at.yawk.lz4:lz4-java:1.12.1",
                "io.netty:netty-buffer:4.2.19.Final",
                "io.netty:netty-codec:4.2.19.Final",
                "io.netty:netty-codec-base:4.2.19.Final",
                "io.netty:netty-codec-compression:4.2.19.Final",
                "io.netty:netty-codec-http:4.2.19.Final",
                "io.netty:netty-common:4.2.19.Final",
                "io.netty:netty-handler:4.2.19.Final",
                "io.netty:netty-resolver:4.2.19.Final",
                "io.netty:netty-transport:4.2.19.Final",
                "io.netty:netty-transport-classes-epoll:4.2.19.Final",
                "io.netty:netty-transport-classes-kqueue:4.2.19.Final",
                "io.netty:netty-transport-native-epoll:4.2.19.Final",
                "io.netty:netty-transport-native-kqueue:4.2.19.Final",
                "io.netty:netty-transport-native-unix-common:4.2.19.Final",
                "org.apache.logging.log4j:log4j-api:2.26.1",
                "org.apache.logging.log4j:log4j-core:2.26.1",
                "org.apache.logging.log4j:log4j-slf4j2-impl:2.26.1",
                "org.jline:jline-reader:3.30.17",
                "org.jline:jline-terminal:3.30.17"
            )
        }
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(javaVersionProp))
        }
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(javaVersionProp)
        options.encoding = "UTF-8"
    }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }

    tasks.withType<Jar>().configureEach {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(rootProject.file("LICENSE")) {
            into("META-INF")
        }
        from(rootProject.file("AUTHORS")) {
            into("META-INF")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    afterEvaluate {
        val archiveBase = project.extensions.getByType<BasePluginExtension>().archivesName.get()
        val publishingExt = project.extensions.getByType<PublishingExtension>()

        publishingExt.apply {
            publications {
                create<MavenPublication>("mavenJava") {
                    from(components["java"])
                    groupId = project.group.toString()
                    artifactId = archiveBase
                    version = project.version.toString()

                    pom {
                        name.set(archiveBase)
                        description.set(project.description)
                        url.set(rootProject.property("pom_url").toString())
                        licenses {
                            license {
                                name.set("The Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                distribution.set("repo")
                            }
                        }
                        developers {
                            developer {
                                id.set(rootProject.property("pom_developer_id").toString())
                                name.set(rootProject.property("pom_developer_name").toString())
                                email.set(rootProject.property("pom_developer_email").toString())
                                organization.set(rootProject.property("pom_organization_name").toString())
                                organizationUrl.set(rootProject.property("pom_organization_url").toString())
                            }
                        }
                        scm {
                            connection.set(rootProject.property("pom_scm_connection").toString())
                            developerConnection.set(rootProject.property("pom_scm_dev_connection").toString())
                            url.set(rootProject.property("pom_scm_url").toString())
                        }
                    }
                }
            }
            repositories {
                maven {
                    name = "MavenCentral"
                    val releasesUrl = uri(
                        System.getenv("MAVEN_CENTRAL_RELEASE_URL")
                            ?: (findProperty("mavenCentralReleaseUrl") as String?)
                            ?: "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
                    )
                    val snapshotsUrl = uri(
                        System.getenv("MAVEN_CENTRAL_SNAPSHOT_URL")
                            ?: (findProperty("mavenCentralSnapshotUrl") as String?)
                            ?: "https://central.sonatype.com/repository/maven-snapshots/"
                    )
                    url = if (project.version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
                    credentials {
                        username = System.getenv("MAVEN_CENTRAL_USERNAME")
                            ?: (findProperty("mavenCentralUsername") as String?)
                        password = System.getenv("MAVEN_CENTRAL_PASSWORD")
                            ?: (findProperty("mavenCentralPassword") as String?)
                    }
                }
            }
        }

        project.extensions.configure<SigningExtension> {
            val signingKey = System.getenv("SIGNING_KEY")
                ?: (findProperty("signingInMemoryKey") as String?)
            val signingPassword = System.getenv("SIGNING_PASSWORD")
                ?: (findProperty("signingInMemoryKeyPassword") as String?)
                ?: ""
            isRequired = !signingKey.isNullOrBlank()
            if (!signingKey.isNullOrBlank()) {
                useInMemoryPgpKeys(signingKey, signingPassword)
                sign(publishingExt.publications["mavenJava"])
            }
        }
    }
}
