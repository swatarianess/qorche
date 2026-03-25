plugins {
    application
    id("org.graalvm.buildtools.native")
}

application {
    mainClass.set("io.qorche.cli.MainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(project(":core"))
    implementation(project(":agent"))
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
}

tasks.register("generateVersionFile") {
    val outputDir = layout.buildDirectory.dir("generated/version")
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("io/qorche/cli")
        dir.mkdirs()
        dir.resolve("version.txt").writeText(project.version.toString())
    }
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/version"))
}

tasks.named("processResources") {
    dependsOn("generateVersionFile")
}

graalvmNative {
    binaries {
        named("main") {
            mainClass.set("io.qorche.cli.MainKt")
            imageName.set("qorche")
            buildArgs.addAll(
                "--no-fallback",
                "-H:+ReportExceptionStackTraces"
            )
        }
    }
    metadataRepository {
        enabled.set(true)
    }
}
