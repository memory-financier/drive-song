// 차량 데이터 / 주행 제한 래퍼 (설계 §5, §6.4)
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.drivesong.core.vehicle"
    // AAOS Car API (CarUxRestrictionsManager, CarPropertyManager). 컴파일 전용, 런타임은 기기 시스템 라이브러리.
    useLibrary("android.car")
    // 데모 시나리오 JSON(assets)을 JVM 단위 테스트에서도 클래스패스 리소스로 읽는다.
    sourceSets["test"].resources.srcDir("src/main/assets")
}

dependencies {
    api(project(":core:model")) // 공개 인터페이스가 GeoPoint/Gear/RouteInfo 를 노출
    implementation(project(":core:data"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.pleos.vehicle) // 앰비언트 라이트 (설계 §6.5)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
