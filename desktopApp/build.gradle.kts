import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin { jvmToolchain(17) }

val appVersion = providers.gradleProperty("appVersion").orElse("1.0.0").get()
val nativePackageVersion = appVersion.substringBefore('-').ifBlank { "1.0.0" }

val generateVersionResource by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/version-resources")
    inputs.property("appVersion", appVersion)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.apply { mkdirs() }
        dir.resolve("app-version.txt").writeText(appVersion)
    }
}

sourceSets {
    named("main") {
        resources.srcDir(generateVersionResource)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":transfer"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.3")
    testImplementation("org.jetbrains.compose.ui:ui-test:1.9.1")
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "app.fanboxlibrary.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "FanboxLibrary"
            packageVersion = nativePackageVersion
            description = "Offline library for personally saved FANBOX posts and attachments"
            modules("java.desktop", "java.logging", "java.xml", "jdk.unsupported", "jdk.charsets", "jdk.crypto.ec")
            windows {
                perUserInstall = true
                menu = true
                shortcut = true
                upgradeUuid = "31f5001b-d4de-4b3b-9a07-e4e4ad112d8b"
            }
        }
    }
}
