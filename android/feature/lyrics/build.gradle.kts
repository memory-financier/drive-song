// 가사 생성 (설계 §5 feature:lyrics, §7.2): LyricsProvider + 서버 구현 + fallback 체인
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.drivesong.feature.lyrics"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:network")) // LyricsInput 이 EnrichedTripDto 를 담는다
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
}
