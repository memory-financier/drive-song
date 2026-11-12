// 주행 기록 (설계 §5 feature:trip, §6.2): TripRecorder 엔진 + Foreground Service
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt) // TripRecorderService 의 @AndroidEntryPoint
}

android {
    namespace = "com.drivesong.feature.trip"
    // 데모 음성 assets 를 단위 테스트에서 읽는다 (T19)
    testOptions.unitTests.isIncludeAndroidResources = true
    // 데모 시나리오 JSON 을 단위 테스트에서 클래스패스 리소스로 읽는다 (core:vehicle 과 같은 방식).
    sourceSets["test"].resources.srcDir("../../core/vehicle/src/main/assets")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:vehicle"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)

    // Robolectric(JUnit4) 으로 Room 인메모리 DB 를 쓴다. JUnit Platform 에서는 vintage 엔진으로 실행.
    testImplementation(project(":core:network")) // 대화 조각 업로드용 가짜 DriveSongApi
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.runtime)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}
