import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
}

group = "com.firstpick"
version = "0.1.0"

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

        val composeJdk = (project.findProperty("composeJdk") as String?)
            ?: providers.environmentVariable("COMPOSE_JDK").orNull
        if (composeJdk != null) javaHome = composeJdk

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "FirstPick"
            modules("java.instrument", "java.net.http", "jdk.unsupported")
            packageVersion = (project.findProperty("packageVersion") as String?) ?: "1.0.0"

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
    // Forward -Dfirstpick.* tuning knobs from the Gradle JVM into the fork.
    System.getProperties().forEach { (k, v) ->
        if (k.toString().startsWith("firstpick.")) systemProperty(k.toString(), v.toString())
    }
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
