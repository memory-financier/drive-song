plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.drivesong"

    defaultConfig {
        applicationId = "com.drivesong"
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    // 가족이 직접 녹음한 데모 (T19 A안): git 에 넣지 않는 android/demo-private/assets 가 있으면 APK 에 넣는다
    // (demo-family/: 대화·음성 ID·family.json). 공개 빌드(GitHub·제출 APK)는 이 폴더 없이 만든다
    // (docs/demo-family-recording.md). 폴더가 없으면 합성 음성 데모(assets/demo/)를 쓴다.
    sourceSets["main"].assets.srcDir("../demo-private/assets")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":core:vehicle"))
    implementation(project(":feature:trip"))
    implementation(project(":feature:lyrics"))
    implementation(project(":feature:song"))
    implementation(project(":feature:voice"))
    implementation(project(":feature:player"))
    implementation(project(":feature:library"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
