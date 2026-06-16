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

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "com.firstpick.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "FirstPick"
            packageVersion = "1.0.0"
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
