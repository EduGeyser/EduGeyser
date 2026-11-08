plugins {
    id("geyser.platform-conventions")
    alias(libs.plugins.runpaper)
}

// Kept off the compile classpath: Geyser compiles against an older API, so it keeps working on older servers
val runServerPaperApi: Configuration = configurations.create("runServerPaperApi")

dependencies {
    api(projects.core)
    api(libs.erosion.bukkit.common) {
        isTransitive = false
    }

    implementation(libs.erosion.bukkit.nms) {
        attributes {
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
        }
    }

    implementation(variantOf(libs.adapters.spigot) {
        classifier("all") // otherwise the unshaded jar is used without the shaded NMS implementations
    })
    implementation(variantOf(libs.adapters.paper) {
        classifier("all") // otherwise the unshaded jar is used without the shaded NMS implementations
    })

    implementation(libs.cloud.paper)
    implementation(libs.commodore)

    compileOnly(libs.folia.api)

    compileOnlyApi(libs.viaversion)

    // For 1.16.5/1.17.1
    implementation(libs.gson.record.factory) {
        isTransitive = false
    }

    // Test
    testImplementation(platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
    testImplementation(libs.junit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.folia.api) // The API Geyser compiles against, to load its classes in tests

    // Latest paper-api build of the runServer version, see PaperApiCompatibilityTest
    runServerPaperApi("io.papermc.paper:paper-api:${libs.versions.runpaperversion.get()}.build.+")
}

platformRelocate("it.unimi.dsi.fastutil")
// Relocate net.kyori but exclude the component logger
platformRelocate("net.kyori", "net.kyori.adventure.text.logger.slf4j.ComponentLogger")
platformRelocate("org.objectweb.asm")
platformRelocate("me.lucko.commodore")
platformRelocate("org.incendo")
platformRelocate("io.leangen.geantyref") // provided by cloud and Configurate, should also be relocated
platformRelocate("org.yaml") // Broken as of 1.20
platformRelocate("org.spongepowered")
platformRelocate("marcono1234.gson")
platformRelocate("org.bstats")

provided(libs.viaversion)

tasks.withType<Jar> {
    manifest.attributes["Main-Class"] = "org.geysermc.geyser.platform.spigot.GeyserSpigotMain"
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {

    // Prevents Paper 1.20.5+ from remapping Geyser
    manifest {
        attributes["paperweight-mappings-namespace"] = "mojang"
    }

    archiveBaseName.set("EduGeyser-Spigot")

    dependencies {
        exclude(dependency("com.google.*:.*"))

        // Netty is provided by the server, except for the haproxy module (missing on older Spigot
        // builds) and the HTTP codec. HTTP and Xbox signaling need the codec, the only ways in for
        // Bedrock players; vanilla 1.21.11 and 26.3 list it among their libraries, and the copy
        // here covers a server that leaves it out. The server's own classes load first.
        exclude("io.netty", libs.netty.codec.haproxy, libs.netty.codec.http)

        // Commodore includes Brigadier
        exclude(dependency("com.mojang:.*"))
    }
}

tasks {
    runServer {
        minecraftVersion(libs.versions.runpaperversion.get())
        jvmArgs("-Dcom.mojang.eula.agree=true")
    }

    // Every test task: CI's strict multi-release build also runs the tests as testJava25
    withType<Test>().configureEach {
        useJUnitPlatform()
        inputs.files(runServerPaperApi)
        // Resolved when the test runs, not while configuring
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf("-Dgeyser.runServerPaperApi=" + runServerPaperApi.asPath)
        })
    }
}
