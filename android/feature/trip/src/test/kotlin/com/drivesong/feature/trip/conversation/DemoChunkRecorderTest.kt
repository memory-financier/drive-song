package com.drivesong.feature.trip.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.drivesong.feature.trip.FakeSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** T19: 데모 모드는 마이크 대신 앱에 넣은 데모 대화를 조각으로 쓴다. */
@RunWith(RobolectricTestRunner::class)
class DemoChunkRecorderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val demoBytes = context.assets.open(DemoChunkRecorder.ASSET).use { it.readBytes() }

    @Test
    fun `demo mode writes the bundled conversation instead of recording the microphone`() = runTest {
        val settings = FakeSettings().apply { demoMode.value = true }
        val recorder = ModeSwitchingChunkRecorder(settings, MediaRecorderChunkRecorder(context), DemoChunkRecorder(context))
        val file = File(context.cacheDir, "0.m4a")

        recorder.start(file)
        assertTrue(recorder.stop())

        assertTrue(demoBytes.size > 300_000) // 86초 AAC 32kbps
        assertArrayEquals(demoBytes, file.readBytes())
        assertEquals(false, recorder.stop()) // 이미 멈춤
    }
}
