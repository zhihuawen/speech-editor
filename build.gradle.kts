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

// ---------------------------------------------------------------- native helpers (macOS)
// Screen Speaker recognizes text with Apple's Vision framework and registers its system-wide shortcut
// with Carbon, each through a tiny Swift program. Gradle compiles them with swiftc (Xcode Command Line
// Tools) and bundles them into the jar as /bin/ocr and /bin/hotkey.
val isMac = System.getProperty("os.name").lowercase().contains("mac")
val helpersDir = layout.buildDirectory.dir("helpers")

fun swiftHelper(name: String) = tasks.register<Exec>("compile" + name.replaceFirstChar { it.uppercase() }) {
    group = "build"
    description = "Compiles the $name helper from src/main/swift/$name.swift with swiftc (macOS only)."
    val source = layout.projectDirectory.file("src/main/swift/$name.swift")
    val binary = helpersDir.map { it.file("bin/$name") }
    inputs.file(source)
    outputs.file(binary)
    onlyIf("the Apple frameworks exist only on macOS") { isMac }
    doFirst { binary.get().asFile.parentFile.mkdirs() }
    commandLine("swiftc", "-O", "-o", binary.get().asFile.absolutePath, source.asFile.absolutePath)
}
val nativeHelpers = listOf(swiftHelper("ocr"), swiftHelper("hotkey"))

sourceSets.main { resources.srcDir(helpersDir) }
tasks.processResources { dependsOn(nativeHelpers) }

tasks.test {
    useJUnitPlatform()
    dependsOn(nativeHelpers)
    if (isMac) {
        systemProperty("ocr.helper", helpersDir.get().file("bin/ocr").asFile.absolutePath)
        systemProperty("hotkey.helper", helpersDir.get().file("bin/hotkey").asFile.absolutePath)
    }
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
