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

        // Packaging (createDistributable / packageDmg / notarizeDmg) needs a *full*
        // JDK that ships `jpackage`. The Gradle toolchain often resolves to an IDE's
        // JBR, which omits jpackage, so allow pointing packaging at a full JDK ≥ 17
        // via `-PcomposeJdk=<path>` or the COMPOSE_JDK env var. This does not affect
        // compilation or `./gradlew run`.
        val composeJdk = (project.findProperty("composeJdk") as String?)
            ?: providers.environmentVariable("COMPOSE_JDK").orNull
        if (composeJdk != null) javaHome = composeJdk

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "FirstPick"
            // jlink builds a minimal runtime; without these the packaged app crashes
            // (java.net.http for the 17Lands/Scryfall clients, jdk.unsupported for
            // JNA). Regenerate with `./gradlew suggestRuntimeModules`.
            modules("java.instrument", "java.net.http", "jdk.unsupported")
            // Distributable bundle version. The release workflow overrides this
            // from the git tag (-PpackageVersion=…). Defaults to 1.0.0 because
            // macOS requires the bundle's major version to be ≥ 1.
            packageVersion = (project.findProperty("packageVersion") as String?) ?: "1.0.0"

            macOS {
                bundleID = "com.firstpick.app"
                // Code-signing + notarization are driven entirely by environment
                // variables, so no credentials live in the repo. With them unset
                // (local dev, CI test builds) the dmg is simply unsigned — today's
                // behavior. The release workflow supplies them. See docs/distribution.md.
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

// Dev helper: replay a Player.log and print the reconstructed draft.
//   ./gradlew replay -PlogPath="/abs/path/Player-prev.log"
tasks.register<JavaExec>("replay") {
    group = "verification"
    description = "Replay an MTGA Player.log and print the reconstructed draft."
    mainClass.set("com.firstpick.tools.ReplayKt")
    classpath = sourceSets["main"].runtimeClasspath
    if (project.hasProperty("logPath")) args(project.property("logPath").toString())
}

// Dev helper: rank one real pack against 17Lands data end-to-end.
//   ./gradlew rankDemo [-PlogPath="/abs/Player.log"] [-Pformat=PremierDraft]
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

// Eval: backtest the advisor against a 17Lands draft dataset (see docs/eval-harness.md).
//   ./gradlew evalHarness -Pdata=/tmp/mkm_sample.csv -Pset=MKM [-Pformat=PremierDraft] [-Plimit=90000]
tasks.register<JavaExec>("evalHarness") {
    group = "verification"
    description = "Backtest the advisor's picks against a 17Lands draft dataset."
    mainClass.set("com.firstpick.eval.EvalHarnessKt")
    classpath = sourceSets["main"].runtimeClasspath
    maxHeapSize = "2g"
    val a = mutableListOf<String>()
    a.add(project.findProperty("data")?.toString() ?: "")
    a.add(project.findProperty("set")?.toString() ?: "MKM")
    a.add(project.findProperty("format")?.toString() ?: "PremierDraft")
    project.findProperty("limit")?.let { a.add(it.toString()) }
    args(a)
}

// Writes the eval harness runtime classpath to a file so it can be run as plain `java`
// (parallel-safe — no Gradle lock contention when fanning out across sets).
tasks.register("printEvalClasspath") {
    val out = layout.buildDirectory.file("eval-classpath.txt")
    val cp = sourceSets["main"].runtimeClasspath
    doLast { out.get().asFile.writeText(cp.asPath) }
}
