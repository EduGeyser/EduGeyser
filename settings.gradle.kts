@file:Suppress("UnstableApiUsage")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        gradlePluginPortal()

        maven("https://repo.opencollab.dev/maven-snapshots/")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev/")
        maven("https://maven.neoforged.net/releases")
    }
    includeBuild("build-logic")
}

rootProject.name = "geyser-parent"

// Opt-in local transport testing against the sibling NetworkM checkout: -PlocalNetworkM
// substitutes every NetworkM module the build resolves, so they all come from the same checkout.
// The RakNet module is resolved only because the Protocol library still depends on it.
// Explicit rules turn off Gradle's automatic substitution of each project's own coordinates
// (group:project name), so both included builds list those too: the modded platforms'
// jar-in-jar step requests every resolved component by them.
if (extra.has("localNetworkM")) {
    val networkM = file(providers.gradleProperty("networkMDir").getOrElse("../NetworkM"))
    require(networkM.resolve("settings.gradle.kts").isFile) {
        "Local transport testing requires NetworkM at ${networkM.absolutePath}. " +
            "Set -PnetworkMDir=/path/to/NetworkM to use another checkout."
    }
    includeBuild(networkM) {
        dependencySubstitution {
            for (transport in listOf("common", "nethernet", "raknet")) {
                for (name in listOf("netty-transport-$transport", "transport-$transport")) {
                    substitute(module("io.github.sendablemetatype.netty:$name"))
                        .using(project(":transport-$transport"))
                }
            }
        }
    }
}

// Opt-in local development against the sibling CloudburstProtocol checkout:
// pass -PlocalProtocol to substitute the JitPack-pinned protocol modules
// with the local build. Normal and release builds keep using the pinned hash.
if (extra.has("localProtocol")) {
    val cloudburstProtocol = file("../CloudburstProtocol")
    require(cloudburstProtocol.isDirectory) {
        "-PlocalProtocol was set but ${cloudburstProtocol.absolutePath} does not exist"
    }
    includeBuild(cloudburstProtocol) {
        dependencySubstitution {
            // The second group is the projects' own, which upstream CloudburstMC's Protocol publishes
            // under too: without it the modded platforms nest upstream's snapshot from a repository
            for (name in listOf("common", "bedrock-codec", "bedrock-connection")) {
                for (group in listOf("com.github.EduGeyser.education-edition-support", "org.cloudburstmc.protocol")) {
                    substitute(module("$group:$name")).using(project(":$name"))
                }
            }
        }
    }
}

include(":ap")
include(":api")
include(":bungeecord")
include(":fabric")
include(":gametest")
include(":neoforge")
include(":mod")
include(":spigot")
include(":standalone")
include(":velocity")
include(":viaproxy")
include(":common")
include(":core")

// Specify project dirs
project(":bungeecord").projectDir = file("bootstrap/bungeecord")
project(":fabric").projectDir = file("bootstrap/mod/fabric")
project(":gametest").projectDir = file("bootstrap/mod/gametest")
project(":neoforge").projectDir = file("bootstrap/mod/neoforge")
project(":mod").projectDir = file("bootstrap/mod")
project(":spigot").projectDir = file("bootstrap/spigot")
project(":standalone").projectDir = file("bootstrap/standalone")
project(":velocity").projectDir = file("bootstrap/velocity")
project(":viaproxy").projectDir = file("bootstrap/viaproxy")

// Allow to download JVMs for toolchains
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version ("1.0.0")
}
