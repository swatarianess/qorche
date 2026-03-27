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
}

tasks.register("generateVersionFile") {
    val outputDir = layout.buildDirectory.dir("generated/version")
    outputs.dir(outputDir)
    outputs.upToDateWhen { false }
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
            val args = mutableListOf(
                "--no-fallback",
                "-Ob",
                "--gc=serial",
                "-H:+ReportExceptionStackTraces"
            )
            if (System.getProperty("os.name", "").lowercase().contains("win")) {
                args.add("-H:-CheckToolchain")
            }
            buildArgs.addAll(args)
        }
    }
    metadataRepository {
        enabled.set(true)
    }
}
