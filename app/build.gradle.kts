import java.util.Properties

plugins {
    id("com.android.application")
}

// Ключ подписи и пароли берём из окружения (так делает CI) или из signing.properties
// рядом с проектом (так удобнее собирать локально). В git ни то, ни другое не попадает.
val signingPropertiesFile = rootProject.file("signing.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) signingPropertiesFile.inputStream().use { load(it) }
}

fun signingValue(envName: String, propertyName: String): String? =
    System.getenv(envName)?.takeIf(String::isNotBlank)
        ?: signingProperties.getProperty(propertyName)?.takeIf(String::isNotBlank)

val releaseStoreFile = signingValue("VPN_KEYSTORE", "storeFile")
val releaseStorePassword = signingValue("VPN_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("VPN_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("VPN_KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "com.dryakhlitsyn.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dryakhlitsyn.vpn"
        minSdk = 23
        targetSdk = 35
        versionCode = 6
        versionName = "0.4.0"

        // Манифест обновлений: ассет latest.json последнего релиза на GitHub.
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://github.com/mikerikichdimas2000-del/vpn-android/releases/latest/download/latest.json\"",
        )
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            // R8 не трогаем: внутри gomobile-ядро, обфускация там только вредит.
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    // Created locally by scripts/build-android.ps1; the AAR is committed for CI builds.
    val engineAarOverride = providers.gradleProperty("vpnEngineAar")
    if (engineAarOverride.isPresent) {
        implementation(files(engineAarOverride.get()))
    } else {
        implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    }

    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}
