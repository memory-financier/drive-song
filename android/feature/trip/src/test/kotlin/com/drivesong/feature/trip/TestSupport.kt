package com.drivesong.feature.trip

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.drivesong.core.data.db.DriveSongDatabase
import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.model.ClipPlanMode
import com.drivesong.core.model.SongStyle
import com.drivesong.core.model.GeoPoint
import com.drivesong.core.model.Gear
import com.drivesong.core.model.RouteInfo
import com.drivesong.core.vehicle.LocationDataSource
import com.drivesong.core.vehicle.NavigationDataSource
import com.drivesong.core.vehicle.VehicleDataSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

/** Room 인메모리 DB. 쿼리를 호출한 스레드에서 바로 실행해 코루틴 가상 시간과 함께 쓸 수 있게 한다. */
fun inMemoryDatabase(): DriveSongDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DriveSongDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
        .build()

/** 코루틴 가상 시간을 따르는 시계: [start] + 경과 가상 시간. */
@OptIn(ExperimentalCoroutinesApi::class) // TestCoroutineScheduler.currentTime
class VirtualClock(
    private val scheduler: TestCoroutineScheduler,
    private val start: Instant,
    private val zone: ZoneId = SEOUL,
) : Clock() {
    override fun instant(): Instant = start.plusMillis(scheduler.currentTime)
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = VirtualClock(scheduler, start, zone)
}

class FakeVehicle : VehicleDataSource {
    val gearState = MutableStateFlow(Gear.PARK)
    val speed = MutableStateFlow(0f)
    override val speedKmh: Flow<Float> = speed
    override val gear: Flow<Gear> = gearState
}

class FakeNavigation : NavigationDataSource {
    val route = MutableStateFlow<RouteInfo?>(null)
    override val activeRoute: Flow<RouteInfo?> = route
}

/** 요청 인자를 기록하고, [position] 이 바뀔 때마다 내보낸다 (간격·거리 규칙은 core:vehicle 의 몫). */
class FakeLocation : LocationDataSource {
    val requests = mutableListOf<Pair<Long, Float>>()
    val position = MutableStateFlow(GeoPoint(37.5665, 126.9780))
    override fun locations(intervalMs: Long, minDistanceM: Float): Flow<GeoPoint> {
        requests += intervalMs to minDistanceM
        return position
    }
}

class FakeSettings(passengers: Set<String> = emptySet()) : SettingsRepository {
    override val simulateDrivingRestriction = MutableStateFlow(false)
    override suspend fun setSimulateDrivingRestriction(enabled: Boolean) = Unit
    override val demoMode = MutableStateFlow(false)
    override suspend fun setDemoMode(enabled: Boolean) = Unit
    override val destinationPresetId = MutableStateFlow<String?>(null)
    override suspend fun setDestinationPresetId(id: String?) {
        destinationPresetId.value = id
    }
    override val selectedPassengerIds = MutableStateFlow(passengers)
    override suspend fun setSelectedPassengerIds(ids: Set<String>) {
        selectedPassengerIds.value = ids
    }
    override val clipPlanMode = MutableStateFlow(ClipPlanMode.RANDOM)
    override suspend fun setClipPlanMode(mode: ClipPlanMode) = Unit
    override val songStyle = MutableStateFlow(SongStyle.CITY_POP)
    override suspend fun setSongStyle(style: SongStyle) = Unit
    override val autoPlay = MutableStateFlow(true)
    override suspend fun setAutoPlay(enabled: Boolean) = Unit
    override val ambientLightShow = MutableStateFlow(true)
    override suspend fun setAmbientLightShow(enabled: Boolean) = Unit
}
