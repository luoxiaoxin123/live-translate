import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.livetranslate.app"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.livetranslate.app"
        minSdk = 29
        targetSdk = 35
        // CI can override: -PVERSION_CODE=2 -PVERSION_NAME=0.1.1
        versionCode = (findProperty("VERSION_CODE") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("VERSION_NAME") as String?) ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Signing must be deterministic across CI runs (fresh VMs regenerate a
    // random debug keystore each run, which forces users to uninstall before
    // every update), but the real keystore must not live in the repo. It is
    // provided via Gradle properties — CI decodes the SIGNING_KEYSTORE secret
    // and passes -P flags; local builds read local.properties. Without any,
    // builds fall back to the machine debug keystore: fine for development,
    // but those APKs cannot upgrade over CI-signed ones. See README "Signing".
    val keystoreProperties = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun signingProp(name: String): String? =
        (findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: keystoreProperties.getProperty(name)
    fun requiredSigningProp(name: String): String =
        signingProp(name)
            ?: error("Missing signing property $name. Set KEYSTORE_FILE + $name (+ KEY_ALIAS / KEY_PASSWORD) in local.properties or pass them as -P flags. See README \"Signing\".")

    signingConfigs {
        signingProp("KEYSTORE_FILE")?.let { storePath ->
            create("app") {
                storeFile = rootProject.file(storePath)
                storePassword = requiredSigningProp("KEYSTORE_PASSWORD")
                keyAlias = requiredSigningProp("KEY_ALIAS")
                keyPassword = signingProp("KEY_PASSWORD") ?: storePassword
            }
        }
    }
    if (signingConfigs.findByName("app") == null) {
        logger.warn("live-translate: KEYSTORE_FILE is not configured — APKs will be signed with the machine debug keystore and cannot install over CI-signed builds. See README \"Signing\".")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("app") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.findByName("app") ?: signingConfigs.getByName("debug")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Align with MIUIX 0.9.x (Compose Multiplatform 1.11 / Kotlin 2.4)
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.3")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.savedstate:savedstate-ktx:1.3.0")

    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-squircle:0.9.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
}
