package com.drivesong.ui.screens

import com.drivesong.core.data.erase.AllDataDeleter
import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.vehicle.demo.DemoDriveController
import com.drivesong.feature.voice.demo.DemoFamilyLoader
import com.drivesong.feature.song.SongOrchestrator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val deleter = mockk<AllDataDeleter>()
    private val player = FakeSongPlayer()

    /** 삭제를 오케스트레이터를 멈춘 채로 실행한다 (만들던 노래 취소 → 삭제 → 상태 되돌림). */
    private val orchestrator = mockk<SongOrchestrator> {
        coEvery { clearForDataDeletion(any()) } coAnswers { firstArg<suspend () -> Unit>().invoke() }
    }
    private lateinit var vm: SettingsViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = SettingsViewModel(mockk<SettingsRepository>(relaxed = true), mockk<DemoDriveController>(relaxed = true), deleter, player, orchestrator,
            mockk<DemoFamilyLoader>(relaxed = true))
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `delete all stops playback first, deletes once even if tapped twice, then reports done`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { deleter.deleteAll() } coAnswers { gate.await() }

        vm.deleteAllData()
        vm.deleteAllData()
        assertEquals(DeleteStatus.DELETING, vm.deleteStatus.value)
        assertEquals(listOf("stop"), player.calls)

        gate.complete(Unit)
        assertEquals(DeleteStatus.DONE, vm.deleteStatus.value)
        coVerify(exactly = 1) { deleter.deleteAll() }
        coVerify(exactly = 1) { orchestrator.clearForDataDeletion(any()) }
    }

    @Test
    fun `a server failure is reported so the user can retry`() = runTest(dispatcher) {
        coEvery { deleter.deleteAll() } throws IOException("offline")

        vm.deleteAllData()

        assertEquals(DeleteStatus.FAILED, vm.deleteStatus.value)
    }
}
