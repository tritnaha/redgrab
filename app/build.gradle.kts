import java.time.LocalDate

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// With -PsideBySide the build is a separate app ("RedGrab test", app.redgrab.test) that installs
// next to the normal one with its own data. For trying a build signed with another key, which
// Android won't install over an existing app.
val sideBySide = providers.gradleProperty("sideBySide").isPresent

// The key every release is signed with, so each build can update the one before it. When the
// file is there (the release workflow writes it from a repository secret) it is used; without
// it the build falls back to this machine's own debug key, which can't update other builds.
val sharedKey = rootProject.file("signing/release.keystore")

// The commit this build was made from, for the About screen and problem reports.
val gitSha = providers.exec { commandLine("git", "rev-parse", "--short", "HEAD"); isIgnoreExitValue = true }
    .standardOutput.asText.map { it.trim() }.getOrElse("").ifEmpty { "unknown" }

/** Puts the repository's CHANGELOG.md into the app, so "What's new" shows the same text as GitHub. */
abstract class ChangelogAsset : DefaultTask() {
    @get:InputFile abstract val source: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @TaskAction fun copy() {
        val dir = outputDir.get().asFile.apply { mkdirs() }
        source.get().asFile.copyTo(File(dir, "CHANGELOG.md"), overwrite = true)
    }
}
val changelogAsset = tasks.register<ChangelogAsset>("changelogAsset") {
    source.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
}
androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(changelogAsset, ChangelogAsset::outputDir) }
}

android {
    namespace = "app.redgrab"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.redgrab"
        minSdk = 26
        targetSdk = 36
        // The build number has to go up with every release: the updater compares it.
        versionCode = 25
        versionName = "13.12"
        // Only used by the screenshot test (app/src/androidTest), which runs on a phone or emulator.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GITHUB_REPO", "\"tritnaha/redgrab\"")
        // Optional: a Reddit "installed app" client ID built in, so Reddit works without typing one in.
        // Such an ID is not a secret. Set with -PredditClientId=... or the REDDIT_CLIENT_ID environment variable.
        val redditId = ((findProperty("redditClientId") as String?) ?: System.getenv("REDDIT_CLIENT_ID") ?: "").filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        buildConfigField("String", "REDDIT_CLIENT_ID", "\"$redditId\"")
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "BUILD_DATE", "\"${LocalDate.now()}\"")
        if (sideBySide) {
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
        }
        manifestPlaceholders["appLabel"] = if (sideBySide) "RedGrab test" else "@string/app_name"
    }

    signingConfigs {
        if (sharedKey.exists()) create("shared") {
            // A key made by the Android tools. Its passwords come from the environment when set
            // (SIGNING_STORE_PASSWORD, SIGNING_KEY_PASSWORD, SIGNING_KEY_ALIAS); otherwise the tools' standard ones.
            storeFile = sharedKey
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")?.takeIf { it.isNotEmpty() } ?: "android"
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")?.takeIf { it.isNotEmpty() } ?: "androiddebugkey"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("shared") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Unit tests run on the computer: Android's own classes (logging) just do nothing there.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.3")

    // The library database (SQLite through Room). Uses the phone's own SQLite: no native library added.
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1") // FikFap streams are HLS
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.10.1")
    implementation("androidx.media3:media3-transformer:1.10.1")
    implementation("androidx.media3:media3-effect:1.10.1")
    implementation("androidx.media3:media3-common:1.10.1")

    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("io.coil-kt.coil3:coil-gif:3.6.3") // animated GIF / WebP
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517") // the JSON classes Android has built in
    // For the screenshot test only; not part of the app or of the unit tests.
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
