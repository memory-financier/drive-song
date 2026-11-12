// 노래 생성 오케스트레이션 (설계 §5 feature:song, §7.1): 트리거·상태 머신·잡 폴링·다운로드·알림
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.drivesong.feature.song"
    // 데모 시나리오 JSON 을 단위 테스트에서 클래스패스 리소스로 읽는다 (core:vehicle 과 같은 방식).
    sourceSets["test"].resources.srcDir("../../core/vehicle/src/main/assets")
}

dependencies {
    api(project(":core:data")) // SongState 가 SongEntity 를 노출
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":feature:trip"))
    implementation(project(":feature:lyrics"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)

    // Robolectric(JUnit4) 으로 Room 인메모리 DB 를 쓴다. JUnit Platform 에서는 vintage 엔진으로 실행.
    testImplementation(project(":core:vehicle")) // 실제 ScenarioPlayer·Fake 소스로 데모 주행 전체를 돌린다
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.runtime)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}
