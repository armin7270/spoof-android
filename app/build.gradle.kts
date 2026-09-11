import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing resolution order:
//   1. keystore.properties in the repo root (local machine, never committed)
//   2. ANDROID_* environment variables (CI secrets)
//   3. neither -> assembleRelease produces an unsigned APK, which is still
//      enough to verify that R8 runs clean.
// keystore.properties looks like:
//   storeFile=keystore/snispoof-release.keystore
//   storePassword=...
//   keyAlias=snispoof
//   keyPassword=...
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasKeystoreProps = keystoreProps.getProperty("storeFile") != null
val hasCiSigning = System.getenv("ANDROID_STORE_FILE") != null

android {
    namespace = "com.armin7270.snispoof"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.armin7270.snispoof"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        create("release") {
            if (hasKeystoreProps || hasCiSigning) {
                // Resolve relative paths against the repo root, so both the
                // properties file and CI secrets behave the same way.
                storeFile = rootProject.file(
                    keystoreProps.getProperty("storeFile")
                        ?: System.getenv("ANDROID_STORE_FILE")
                )
                storePassword = keystoreProps.getProperty("storePassword")
                    ?: System.getenv("ANDROID_STORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("keyAlias")
                    ?: System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("keyPassword")
                    ?: System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Shrink + obfuscate: without R8 the APK ships ~21 MB and every class
            // name is readable, which makes the app trivially fingerprintable by
            // its bytes as well as its traffic.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasKeystoreProps || hasCiSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    // The root helper is NOT packaged: it is a standalone binary that a rooted
    // device installs to /data/local/tmp. Build and install it with
    // tools/build-helper.cmd (Windows) or tools/build-helper.sh (POSIX) — see README.
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    testImplementation("junit:junit:4.13.2")
}
