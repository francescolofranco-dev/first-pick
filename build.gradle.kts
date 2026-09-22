import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
}

group = "com.firstpick"
version = file("VERSION").readText().trim()
val firstPickPackageVersion = (project.findProperty("packageVersion") as String?) ?: version.toString()

require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(version.toString())) {
    "VERSION must contain MAJOR.MINOR.PATCH."
}
require(firstPickPackageVersion == version.toString()) { "packageVersion must match VERSION." }

tasks.named<ProcessResources>("processResources") {
    from("VERSION") { rename { "app-version.txt" } }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jna)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "com.firstpick.MainKt"
        jvmArgs("-Dfirstpick.version=$firstPickPackageVersion")
        if (System.getProperty("os.name").startsWith("Mac")) {
            jvmArgs("-Xdock:name=FirstPick")
        }

        val composeJdk = (project.findProperty("composeJdk") as String?)
            ?: providers.environmentVariable("COMPOSE_JDK").orNull
        if (composeJdk != null) javaHome = composeJdk

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "FirstPick"
            modules("java.instrument", "java.net.http", "jdk.unsupported")
            packageVersion = firstPickPackageVersion

            macOS {
                bundleID = "com.firstpick.app"
                iconFile.set(project.file("packaging/icon/FirstPick.icns"))
                val signingIdentity = providers.environmentVariable("MACOS_SIGNING_IDENTITY")
                signing {
                    sign.set(signingIdentity.isPresent)
                    identity.set(signingIdentity.orElse(""))
                }
                notarization {
                    appleID.set(providers.environmentVariable("NOTARIZATION_APPLE_ID").orElse(""))
                    password.set(providers.environmentVariable("NOTARIZATION_PASSWORD").orElse(""))
                    teamID.set(providers.environmentVariable("NOTARIZATION_TEAM_ID").orElse(""))
                }
            }
        }
    }
}

tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    environment("FIRSTPICK_DEMO", "1")
    if (project.hasProperty("track")) systemProperty("firstpick.overlayTrack", "true")
}

val buildNativeHelpers = tasks.register<Exec>("buildNativeHelpers") {
    group = "build"
    description = "Build optimized macOS helpers (host architecture, or -PnativeArchitecture=universal)."
    val architectureMode = providers.gradleProperty("nativeArchitecture").orElse("host").get()
    inputs.files(fileTree("native/macos") { include("*.swift", "build.sh") })
    inputs.property("architectureMode", architectureMode)
    inputs.property("signingIdentity", providers.environmentVariable("MACOS_SIGNING_IDENTITY").orElse(""))
    inputs.property("sdkRoot", providers.environmentVariable("SDKROOT").orElse(""))
    inputs.property("developerDir", providers.environmentVariable("DEVELOPER_DIR").orElse(""))
    if (System.getProperty("os.name").startsWith("Mac")) {
        inputs.property("hostArchitecture", providers.exec {
            commandLine("uname", "-m")
        }.standardOutput.asText)
        inputs.property("swiftVersion", providers.exec {
            commandLine("swiftc", "--version")
        }.standardOutput.asText)
        val defaultSdk = providers.exec {
            commandLine("xcrun", "--sdk", "macosx", "--show-sdk-path")
        }.standardOutput.asText.map(String::trim)
        val effectiveSdk = providers.environmentVariable("SDKROOT")
            .filter { it.isNotEmpty() }.orElse(defaultSdk)
        inputs.property("macosSdk", effectiveSdk)
        inputs.file(effectiveSdk.map { File(it, "SDKSettings.json") })
            .withPropertyName("sdkSettings")
    }
    outputs.files(
        "src/main/resources/native/macos/window-locator",
        "src/main/resources/native/macos/window-capture",
        "src/main/resources/native/macos/vision-ocr",
        "src/main/resources/native/macos/deck-reader",
    )
    workingDir(layout.projectDirectory)
    val nativeCommand = mutableListOf("bash", "native/macos/build.sh")
    if (architectureMode == "host") nativeCommand.add("--host")
    commandLine(nativeCommand)
    doFirst {
        check(System.getProperty("os.name").startsWith("Mac")) {
            "Production app builds require macOS and the Xcode command-line tools."
        }
        check(architectureMode in setOf("host", "universal")) {
            "nativeArchitecture must be 'host' or 'universal'."
        }
    }
}

// Order native resources before the shared JVM resource/JAR tasks, without
// requiring Xcode for ordinary development builds or unit tests.
tasks.named("processResources") { mustRunAfter(buildNativeHelpers) }
tasks.matching { it.name == "createDistributable" }.configureEach {
    dependsOn(buildNativeHelpers, "test")
}

val productionApp = layout.buildDirectory.dir("compose/binaries/main/app/FirstPick.app")

val signLocalProduction = tasks.register<Exec>("signLocalProduction") {
    dependsOn("createDistributable")
    val useLocalSigning = providers.environmentVariable("MACOS_SIGNING_IDENTITY")
        .map { it.isBlank() }.orElse(true)
    onlyIf { useLocalSigning.get() }
    commandLine("python3", "scripts/sign-local-app.py", productionApp.get().asFile.absolutePath,
        layout.projectDirectory.dir("src/main/resources/native/macos").asFile.absolutePath)
}

val buildProduction = tasks.register("buildProduction") {
    group = "build"
    description = "Test and build the shipping-equivalent FirstPick.app with its bundled JVM and native helpers."
    dependsOn(buildNativeHelpers, "test", "createDistributable", signLocalProduction)
    val appPath = productionApp.get().asFile.absolutePath
    doLast {
        logger.lifecycle("Production app: $appPath")
        logger.lifecycle("Open it with ./gradlew runProduction, or launch it from Finder.")
    }
}

tasks.register<Exec>("runProduction") {
    group = "application"
    description = "Build and open the production app, with development/preview flags disabled."
    dependsOn(buildProduction)
    commandLine("python3", "scripts/run-production.py", productionApp.get().asFile.absolutePath)
}

tasks.register<JavaExec>("locateArena") {
    group = "verification"
    description = "Print MTG Arena's window bounds via the bundled CoreGraphics helper (macOS)."
    mainClass.set("com.firstpick.tools.LocateArenaKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("replay") {
    group = "verification"
    description = "Replay an MTGA Player.log and print the reconstructed draft."
    mainClass.set("com.firstpick.tools.ReplayKt")
    classpath = sourceSets["main"].runtimeClasspath
    if (project.hasProperty("logPath")) args(project.property("logPath").toString())
}

tasks.register<JavaExec>("rankDemo") {
    group = "verification"
    description = "Reconstruct a pack and print it ranked by 17Lands GIH win rate."
    mainClass.set("com.firstpick.tools.RankDemoKt")
    classpath = sourceSets["main"].runtimeClasspath
    val a = mutableListOf<String>()
    a.add(if (project.hasProperty("logPath")) project.property("logPath").toString() else "")
    a.add(if (project.hasProperty("format")) project.property("format").toString() else "")
    if (project.hasProperty("stop")) a.add(project.property("stop").toString())
    args(a)
}

tasks.register<JavaExec>("evalHarness") {
    group = "verification"
    description = "Backtest the advisor's picks against a 17Lands draft dataset."
    mainClass.set("com.firstpick.eval.EvalHarnessKt")
    classpath = sourceSets["main"].runtimeClasspath
    maxHeapSize = "2g"

    System.getProperties().forEach { (k, v) ->
        if (k.toString().startsWith("firstpick.")) systemProperty(k.toString(), v.toString())
    }

    project.findProperty("net")?.let { systemProperty("firstpick.net", it.toString()) }
    val a = mutableListOf<String>()
    a.add(project.findProperty("data")?.toString() ?: "")
    a.add(project.findProperty("set")?.toString() ?: "MKM")
    a.add(project.findProperty("format")?.toString() ?: "PremierDraft")
    project.findProperty("limit")?.let { a.add(it.toString()) }
    args(a)
}

tasks.register<JavaExec>("overlayDebug") {
    group = "verification"
    description = "Run the overlay capture→detect→recognize pipeline once and report each stage."
    mainClass.set("com.firstpick.tools.OverlayDebugKt")
    classpath = sourceSets["main"].runtimeClasspath
    val a = mutableListOf<String>()
    a.add(project.findProperty("frame")?.toString() ?: "")
    project.findProperty("format")?.let { a.add(it.toString()) }
    args(a)
}

tasks.register<JavaExec>("clickThroughProbe") {
    group = "verification"
    description = "Verify overlay click-through end-to-end on a real window; -Pstay=1 keeps it up for manual clicks."
    mainClass.set("com.firstpick.tools.ClickThroughProbeKt")
    classpath = sourceSets["main"].runtimeClasspath
    if (project.findProperty("stay") != null) args("stay")
}

tasks.register<JavaExec>("coldStartProbe") {
    group = "verification"
    description = "Test whether a new set's card quality can be imputed from functional analogs (cold-start)."
    mainClass.set("com.firstpick.tools.ColdStartProbeKt")
    classpath = sourceSets["main"].runtimeClasspath
    val a = mutableListOf<String>()
    a.add(project.findProperty("set")?.toString() ?: "FIN")
    project.findProperty("format")?.let { a.add(it.toString()) }
    args(a)
}

tasks.register<JavaExec>("auditSets") {
    group = "verification"
    description = "Check bundled synergy profiles against live Standard legality; flag rotated sets to drop."
    mainClass.set("com.firstpick.tools.AuditSetsKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register("printEvalClasspath") {
    val out = layout.buildDirectory.file("eval-classpath.txt")
    val cp = sourceSets["main"].runtimeClasspath
    doLast { out.get().asFile.writeText(cp.asPath) }
}
