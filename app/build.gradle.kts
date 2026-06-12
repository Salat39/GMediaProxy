plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

val appVersionCode = 1037
val appVersionName = "1.2.1"

android {
    namespace = "com.geely.online.service"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.geely.online.service"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("Float", "UI_SCALE", "1.4f")
        }
        debug {
            buildConfigField("Float", "UI_SCALE", "1.5f")
        }
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Automatic signing and assembly
    // 1) Copy and rename the file "_secure.signing.gradle" to "secure.signing.gradle"
    // 2) You can copy it to any location and specify the path to it in the "gradle.properties" file
    // 3) Specify the necessary values to sign all builds of the application
    // 4) Run the command in the terminal "./gradlew prepareRelease"
    // 5) Wait and pick up all builds from "app/build/outputs/apk/" and "app/build/outputs/bundle/"
    // https://www.timroes.de/handling-signing-configs-with-gradle
    if (project.hasProperty("secure.signing") && project.file(project.property("secure.signing") as String).exists()) {
        apply(project.property("secure.signing"))
    }
}

base {
    archivesName.set("$appVersionName[$appVersionCode]GMediaProxy")
}

dependencies {
    implementation(project(":com_geely"))

    // For release build – compileOnly (used at compile time, not included in the final APK)
    // add("releaseCompileOnly", project(":com_geely"))

    // For debug build – implementation (fully included)
    // add("debugImplementation", project(":com_geely"))

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// -----------------------------------------------------
// Create release BP and assemble release
// -----------------------------------------------------

// Step 1: Create release profiles
tasks.register("prepareRelease") {
    // dependsOn("generateReleaseBaselineProfile")
    finalizedBy("assembleReleaseBuild")
}

// Step 2: Assemble release
tasks.register("assembleReleaseBuild").get()
    .dependsOn("assembleRelease")
