import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.notify"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.notify"
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "0.7.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    }

    signingConfigs {
        create("release") {
            val localProperties = Properties()
            val localPropertiesFile = rootProject.file("local.properties")
            if (localPropertiesFile.exists()) {
                localPropertiesFile.inputStream().use { localProperties.load(it) }
            }

            val keystorePath = localProperties.getProperty("RELEASE_KEYSTORE_PATH")
                ?: System.getenv("RELEASE_KEYSTORE_PATH")
            val keystorePassword = localProperties.getProperty("RELEASE_KEYSTORE_PASSWORD")
                ?: System.getenv("RELEASE_KEYSTORE_PASSWORD")
            val keyAliasStr = localProperties.getProperty("RELEASE_KEY_ALIAS")
                ?: System.getenv("RELEASE_KEY_ALIAS")
            val keyPasswordStr = localProperties.getProperty("RELEASE_KEY_PASSWORD")
                ?: System.getenv("RELEASE_KEY_PASSWORD")

            val resolvedKeystoreFile = if (!keystorePath.isNullOrBlank()) {
                val rootFile = rootProject.file(keystorePath)
                if (rootFile.exists()) rootFile else file(keystorePath)
            } else null

            if (resolvedKeystoreFile != null && resolvedKeystoreFile.exists()) {
                storeFile = resolvedKeystoreFile
                storePassword = keystorePassword
                keyAlias = keyAliasStr
                keyPassword = keyPasswordStr
            } else {
                // Safe fallback to debug signing config when dedicated release keystore is not yet configured
                val debugSigning = getByName("debug")
                storeFile = debugSigning.storeFile
                storePassword = debugSigning.storePassword
                keyAlias = debugSigning.keyAlias
                keyPassword = debugSigning.keyPassword
            }
        }
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    sourceSets {
        getByName("test").assets.srcDirs("$projectDir/schemas")
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:local"))
    implementation(project(":core:playback"))
    implementation(project(":core:downloads"))

    implementation(libs.youtubedl.android.library)
    implementation(libs.youtubedl.android.ffmpeg)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.android.libraries.identity.googleid)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
