plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.br.fred.droptv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.br.fred.droptv"
        minSdk = 24
        //noinspection EditedTargetSdkVersion
        targetSdk = 36
        versionCode = 7
        versionName = "Arpex"
    }

    signingConfigs {
        create("release") {
            // Captura as variáveis de ambiente fornecidas pelo pipeline do Cloud Build
            val keystoreFile = System.getenv("KEYSTORE_FILE") ?: "dummy.jks"
            storeFile = file(keystoreFile)
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Associa a configuração de assinatura criada acima a este tipo de build
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }
}

dependencies {
    // AndroidX
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // Media3 (ExoPlayer e UI)
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
}