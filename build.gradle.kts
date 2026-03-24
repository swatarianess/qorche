plugins {
    kotlin("jvm") version "2.1.10" apply false
    kotlin("plugin.serialization") version "2.1.10" apply false
    id("org.graalvm.buildtools.native") version "0.10.6" apply false
}

fun gitVersion(): String {
    val process = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val tag = process.inputStream.bufferedReader().readText().trim()
    return if (process.waitFor() == 0 && tag.startsWith("v")) {
        tag.removePrefix("v")
    } else {
        "0.0.0-dev"
    }
}

allprojects {
    group = "io.qorche"
    version = gitVersion()

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    dependencies {
        val implementation by configurations
        val testImplementation by configurations

        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
        implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")

        testImplementation(kotlin("test"))
        testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    }

    tasks.named<Test>("test") {
        useJUnitPlatform {
            excludeTags("large-scale", "benchmark")
        }
        jvmArgs("-Xmx64m")
    }

    tasks.register<Test>("benchmark") {
        useJUnitPlatform {
            includeTags("benchmark")
            excludeTags("large-scale")
        }
        jvmArgs("-Xmx128m")
        group = "verification"
        description = "Run benchmark tests (100-20k files)"
        testClassesDirs = tasks.named<Test>("test").get().testClassesDirs
        classpath = tasks.named<Test>("test").get().classpath
    }

    // Run with: ./gradlew :agent:largeBenchmark
    tasks.register<Test>("largeBenchmark") {
        useJUnitPlatform {
            includeTags("large-scale")
        }
        jvmArgs("-Xmx512m")
        group = "verification"
        description = "Run large-scale benchmarks (50k, 100k files)"
        testClassesDirs = tasks.named<Test>("test").get().testClassesDirs
        classpath = tasks.named<Test>("test").get().classpath
    }
}
