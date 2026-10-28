plugins {
    java
    application
}

group = "dev.zhihua"
version = "0.1.0"

java {
    // Compile for Java 17 bytecode with whatever JDK runs Gradle (17+).
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---------------------------------------------------------------- native OCR helper (macOS)
// Screen Speaker recognizes text with Apple's Vision framework through a tiny Swift program.
// Gradle compiles it with swiftc (Xcode Command Line Tools) and bundles it into the jar as /bin/ocr.
val isMac = System.getProperty("os.name").lowercase().contains("mac")
val ocrHelperDir = layout.buildDirectory.dir("helpers")
val ocrHelperBinary = ocrHelperDir.map { it.file("bin/ocr") }

val compileOcr by tasks.registering(Exec::class) {
    group = "build"
    description = "Compiles the Vision OCR helper with swiftc (macOS only)."
    val source = layout.projectDirectory.file("src/main/swift/ocr.swift")
    inputs.file(source)
    outputs.file(ocrHelperBinary)
    onlyIf("the Vision framework exists only on macOS") { isMac }
    doFirst { ocrHelperBinary.get().asFile.parentFile.mkdirs() }
    commandLine("swiftc", "-O", "-o", ocrHelperBinary.get().asFile.absolutePath, source.asFile.absolutePath)
}

sourceSets.main { resources.srcDir(ocrHelperDir) }
tasks.processResources { dependsOn(compileOcr) }

tasks.test {
    useJUnitPlatform()
    dependsOn(compileOcr)
    if (isMac) systemProperty("ocr.helper", ocrHelperBinary.get().asFile.absolutePath)
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.withType<JavaCompile> {
    options.release.set(17)
    options.encoding = "UTF-8"
}

application {
    mainClass.set("SpeechEditor")
    applicationDefaultJvmArgs = listOf("-Dapple.laf.useScreenMenuBar=true")
}

tasks.jar {
    archiveFileName.set("speech-editor.jar")
    manifest { attributes("Main-Class" to "SpeechEditor") }
}

// ---------------------------------------------------------------- Screen Speaker (second app)
val screenSpeakerJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Assembles screen-speaker.jar (same classes, Screen Speaker as the entry point)."
    archiveFileName.set("screen-speaker.jar")
    from(sourceSets.main.get().output)
    manifest { attributes("Main-Class" to "ScreenSpeaker") }
}
tasks.assemble { dependsOn(screenSpeakerJar) }

tasks.register<JavaExec>("runScreen") {
    group = "application"
    description = "Runs Screen Speaker from sources."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ScreenSpeaker")
    jvmArgs("-Dapple.laf.useScreenMenuBar=true")
}

// ---------------------------------------------------------------- macOS .app bundles
// ./gradlew appImage       -> build/app/Speech Editor.app
// ./gradlew appImageScreen -> build/app/Screen Speaker.app
fun registerAppImage(taskName: String, appName: String, jarTask: TaskProvider<Jar>, mainClass: String) =
    tasks.register<Exec>(taskName) {
        group = "distribution"
        description = "Builds build/app/$appName.app with jpackage (bundles its own runtime)."
        dependsOn(jarTask)
        val input = layout.buildDirectory.dir("jpackage-input/$taskName")
        val dest = layout.buildDirectory.dir("app")
        doFirst {
            delete(input, dest.get().dir("$appName.app"))
            copy { from(jarTask); into(input) }
        }
        val javaHome = javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath
        executable = javaHome.file("bin/jpackage").asFile.absolutePath
        args(
            "--type", "app-image", "--name", appName,
            "--dest", dest.get().asFile.absolutePath,
            "--input", input.get().asFile.absolutePath,
            "--main-jar", jarTask.get().archiveFileName.get(), "--main-class", mainClass,
            "--java-options", "-Dapple.laf.useScreenMenuBar=true",
        )
    }

registerAppImage("appImage", "Speech Editor", tasks.jar, "SpeechEditor")
registerAppImage("appImageScreen", "Screen Speaker", screenSpeakerJar, "ScreenSpeaker")
