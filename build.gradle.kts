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
    systemProperty("java.awt.headless", "true") // tests draw images and play sound, never open windows
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.withType<JavaCompile> {
    options.release.set(17)
    options.encoding = "UTF-8"
}

// ---------------------------------------------------------------- Screen Speaker
// ./gradlew run   -> Screen Speaker from sources
// ./gradlew build -> build/libs/screen-speaker.jar
application {
    mainClass.set("ScreenSpeaker")
    applicationDefaultJvmArgs = listOf("-Dapple.laf.useScreenMenuBar=true")
}

tasks.jar {
    archiveFileName.set("screen-speaker.jar")
    manifest { attributes("Main-Class" to "ScreenSpeaker") }
}

// ---------------------------------------------------------------- app icon (macOS)
// AppIcon.java draws the icon; iconutil packs the sizes into the .icns that jpackage puts on the bundle.
val appName = "Screen Speaker"
val iconSet = layout.buildDirectory.dir("icon/$appName.iconset")
val iconFile = layout.buildDirectory.file("icon/$appName.icns")

val renderIconSet by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Draws the app icon at every size macOS wants into build/icon/$appName.iconset."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("AppIcon")
    jvmArgs("-Djava.awt.headless=true")
    args(iconSet.get().asFile.absolutePath)
    outputs.dir(iconSet)
}

val appIcon by tasks.registering(Exec::class) {
    group = "build"
    description = "Packs the iconset into build/icon/$appName.icns with iconutil (macOS only)."
    dependsOn(renderIconSet)
    inputs.dir(iconSet)
    outputs.file(iconFile)
    onlyIf("iconutil exists only on macOS") { isMac }
    commandLine("iconutil", "-c", "icns", iconSet.get().asFile.absolutePath, "-o", iconFile.get().asFile.absolutePath)
}

// ---------------------------------------------------------------- macOS .app bundle
// ./gradlew appImage -> build/app/Screen Speaker.app (bundles its own Java runtime)
tasks.register<Exec>("appImage") {
    group = "distribution"
    description = "Builds build/app/$appName.app with jpackage (bundles its own runtime)."
    dependsOn(tasks.jar, appIcon)
    val input = layout.buildDirectory.dir("jpackage-input")
    val dest = layout.buildDirectory.dir("app")
    doFirst {
        delete(input, dest.get().dir("$appName.app"))
        copy { from(tasks.jar); into(input) }
    }
    val javaHome = javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath
    executable = javaHome.file("bin/jpackage").asFile.absolutePath
    args(
        "--type", "app-image", "--name", appName,
        "--dest", dest.get().asFile.absolutePath,
        "--input", input.get().asFile.absolutePath,
        "--main-jar", tasks.jar.get().archiveFileName.get(), "--main-class", "ScreenSpeaker",
        "--icon", iconFile.get().asFile.absolutePath,
        "--java-options", "-Dapple.laf.useScreenMenuBar=true",
    )
}
