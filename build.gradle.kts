plugins {
    kotlin("jvm") apply false
    kotlin("plugin.serialization") apply false
    id("org.graalvm.buildtools.native") version "0.10.6" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    id("org.jetbrains.dokka") version "2.2.0"
}

val detektReportMergeSarif by tasks.registering(io.gitlab.arturbosch.detekt.report.ReportMergeTask::class) {
    output.set(layout.buildDirectory.file("reports/detekt/merged.sarif"))
}

val detektReportMergeXml by tasks.registering(io.gitlab.arturbosch.detekt.report.ReportMergeTask::class) {
    output.set(layout.buildDirectory.file("reports/detekt/merged.xml"))
}

fun gitVersion(): String = try {
    // Use --long to include commit distance: v0.8.3-5-gabcdef → 0.8.3-dev.5
    // On a tagged commit this produces v0.8.3-0-gabcdef → 0.8.3
    val process = ProcessBuilder("git", "describe", "--tags", "--long")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0 && output.startsWith("v")) {
        val parts = output.removePrefix("v").split("-")
        if (parts.size >= 3) {
            val base = parts.dropLast(2).joinToString("-")
            val distance = parts[parts.size - 2].toIntOrNull() ?: 0
            if (distance == 0) base else "$base-dev.$distance"
        } else {
            output.removePrefix("v")
        }
    } else {
        "0.0.0-dev"
    }
} catch (_: Exception) {
    "0.0.0-dev"
}

allprojects {
    group = "io.qorche"
    version = gitVersion()

    repositories {
        mavenCentral()
    }
}

dokka {
    moduleName.set("Qorche")
    dokkaPublications.html {
        outputDirectory.set(layout.buildDirectory.dir("docs/html"))
    }
}

dependencies {
    dokka(project(":core"))
    dokka(project(":agent"))
    dokka(project(":cli"))
    dokka(project(":native"))
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jetbrains.dokka")

    val moduleDoc = file("Module.md")
    if (moduleDoc.exists()) {
        configure<org.jetbrains.dokka.gradle.DokkaExtension> {
            dokkaSourceSets.configureEach {
                includes.from(moduleDoc)
            }
        }
    }

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        parallel = true
        baseline = file("detekt-baseline.xml")
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        reports {
            sarif.required.set(true)
            xml.required.set(true)
            html.required.set(true)
        }
        finalizedBy(detektReportMergeSarif, detektReportMergeXml)
    }

    detektReportMergeSarif.configure {
        input.from(tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().map { it.sarifReportFile })
    }

    detektReportMergeXml.configure {
        input.from(tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().map { it.xmlReportFile })
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
