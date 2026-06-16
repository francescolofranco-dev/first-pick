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
