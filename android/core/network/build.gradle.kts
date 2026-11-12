// Retrofit API (설계 §5, §9)
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// 개발 서버 기본값: 에뮬레이터에서 본 호스트 PC. -PdriveSongApiBaseUrl=... 로 재정의.
// 평문 http:// 재정의는 디버그 빌드만 허용 (app/src/debug/res/xml/network_security_config.xml). 릴리스는 https 로.
val apiBaseUrl = providers.gradleProperty("driveSongApiBaseUrl").getOrElse("http://10.0.2.2:8000/")

android {
    namespace = "com.drivesong.core.network"
    defaultConfig {
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    api(project(":core:model"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    api(libs.retrofit)
    api(libs.okhttp)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.datastore.preferences)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
