import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Generates composeApp's baseline profile on a connected device (Android 13+ needs no root):
// `./gradlew :composeApp:generateBaselineProfile`. It installs a `.benchmark` build beside the real app.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "io.waves.baselineprofile"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = 28
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    targetProjectPath = ":composeApp"
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}
