// Sorgenti vendorizzati da termux/termux-app v0.118.3 (Apache-2.0),
// modulo terminal-emulator, con patch "stream mode" in TerminalSession
// per alimentare l'emulatore da uno stream SSH invece che da un pty locale.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.terminal"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
}
