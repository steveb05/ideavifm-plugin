import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

fun gitShortHash(): String = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf(String::isNotEmpty) ?: "dev"

val baseVersion = "1.0.0"

group = "me.steveb05"

// The hash lands only on distributed builds. A version that moves with every commit
// would rebuild and retest the whole plugin even when no source changed.
version = providers.gradleProperty("stampGitHash")
    .map { "$baseVersion+${gitShortHash()}" }
    .getOrElse(baseVersion)

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.2")
        testFramework(TestFrameworkType.Platform)
        testBundledPlugin("org.jetbrains.kotlin")
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    buildSearchableOptions = providers.gradleProperty("searchableOptions")
        .map(String::toBoolean)
        .orElse(false)

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }
}

kotlin {
    jvmToolchain(21)
}
