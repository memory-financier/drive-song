// 가족 구성원·목소리 클립 녹음/관리, 이번 주행 탑승자 (설계 §5 feature:voice, §8, §10)
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.drivesong.feature.voice"
    // 데모 음성 assets 를 단위 테스트에서 읽는다 (T19)
    testOptions.unitTests.isIncludeAndroidResources = true
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:vehicle")) // UxRestrictionsMonitor
    implementation(project(":feature:player")) // 음성 명령 "노래 틀어줘" (설계 §8.2)
    implementation(libs.pleos.speechtotext) // "말로 하기" (설계 §8.2)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)

    // Robolectric(JUnit4) 으로 Room 인메모리 DB 를 쓴다. JUnit Platform 에서는 vintage 엔진으로 실행.
    testImplementation(project(":core:network")) // 가짜 DriveSongApi
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.runtime)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}
