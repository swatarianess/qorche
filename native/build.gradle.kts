plugins {
    id("org.graalvm.buildtools.native")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":agent"))
    compileOnly("org.graalvm.sdk:nativeimage:24.2.1")
}

tasks.register("generateVersionFile") {
    val outputDir = layout.buildDirectory.dir("generated/version")
    outputs.dir(outputDir)
    outputs.upToDateWhen { false }
    doLast {
        val dir = outputDir.get().asFile.resolve("io/qorche/ffi")
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
            imageName.set("libqorche")
            sharedLibrary.set(true)
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
