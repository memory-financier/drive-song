// 재생 (설계 §5 feature:player, §4.1 Media3): PlaybackService(MediaLibraryService) + 앱 쪽 재생 제어(SongPlayer)
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt) // PlaybackService 의 @AndroidEntryPoint
}

android {
    namespace = "com.drivesong.feature.player"
}

dependencies {
    api(project(":core:data")) // SongPlayer 가 SongEntity 를 받는다
    implementation(project(":feature:song")) // 재생 시작/종료를 SongOrchestrator 에 알린다
    implementation(libs.androidx.core.ktx)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava) // Media3 의 ListenableFuture ↔ 코루틴

    // Robolectric(JUnit4) 으로 실제 ExoPlayer 의 오디오 포커스 처리를 확인한다. JUnit Platform 에서는 vintage 엔진으로 실행.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}
