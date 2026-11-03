plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Numéro de build qui augmente tout seul : numéro du run GitHub Actions en CI,
// nombre de commits en local. Android exige un versionCode croissant pour mettre à jour.
val gitCommitCount: Int = try {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
} catch (e: Exception) {
    1
}
val buildNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: gitCommitCount

android {
    namespace = "com.filmcam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.argenticam"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.$buildNumber"
    }

    // Clé de signature fixe : sans elle, chaque build GitHub signe avec une clé de debug différente
    // et Android refuse la mise à jour ("conflit avec le paquet"). La clé n'est PAS dans le dépôt :
    // elle est fournie par les variables d'environnement (secrets GitHub en CI). Sans elles, on
    // garde la clé de debug locale.
    val keystorePath = System.getenv("ARGENTICAM_KEYSTORE")
    val keystorePassword = System.getenv("ARGENTICAM_KEYSTORE_PASSWORD")
    val stableSigning = keystorePath != null && keystorePassword != null && file(keystorePath).exists()
    signingConfigs {
        if (stableSigning) {
            create("argenticam") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = "argenticam"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        debug {
            if (stableSigning) signingConfig = signingConfigs.getByName("argenticam")
        }
        release {
            isMinifyEnabled = false
            if (stableSigning) signingConfig = signingConfigs.getByName("argenticam")
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
        buildConfig = true
    }
}

dependencies {
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
}
