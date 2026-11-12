package com.drivesong.feature.voice.command

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Pleos SpeechToText SDK, 차량 내 엔진(ON_DEVICE: 네트워크·앱 등록 불필요)으로 한 번 듣는다 (설계 §8.2).
 * Pleos 음성 서비스(CAAS)가 없으면 [SpeechResult.Unavailable].
 * [확인 필요] 에뮬레이터에서 한국어 ON_DEVICE 인식이 되는지, 마이크 권한을 앱이 따로 가져야 하는지.
 */
class PleosSpeechInput @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechInput {

    override suspend fun listen(): SpeechResult = withContext(Dispatchers.Main) {
        if (context.packageManager.queryIntentServices(Intent(SPEECH_ACTION), 0).isEmpty()) {
            return@withContext SpeechResult.Unavailable
        }
        val stt = SpeechToText(context, Mode.ON_DEVICE)
        try {
            val ready = withTimeoutOrNull(INIT_TIMEOUT_MS) { stt.initializeAwait() } ?: false
            if (!ready) return@withContext SpeechResult.Unavailable
            stt.setLocale(Locale.KOREA)
            withTimeoutOrNull(LISTEN_TIMEOUT_MS) { stt.listenOnce() } ?: SpeechResult.NoSpeech
        } catch (e: Exception) {
            Log.w(TAG, "speech to text failed", e)
            SpeechResult.Unavailable
        } finally {
            runCatching { stt.release() }
        }
    }

    private suspend fun SpeechToText.initializeAwait(): Boolean = suspendCancellableCoroutine { cont ->
        initialize(object : OnInitializeListener {
            override fun onSuccess() {
                if (cont.isActive) cont.resume(true)
            }

            override fun onFailure(error: ErrorType) {
                Log.w(TAG, "stt init failed: $error")
                if (cont.isActive) cont.resume(false)
            }
        })
    }

    private suspend fun SpeechToText.listenOnce(): SpeechResult = suspendCancellableCoroutine { cont ->
        var last = ""
        val listener = object : ResultListener {
            override fun onUpdated(text: String, isFinal: Boolean) {
                last = text
                if (isFinal) done(text)
            }

            override fun onEndedRecognition() = done(last)

            override fun onError(error: ErrorType) {
                Log.w(TAG, "stt error: $error")
                if (!cont.isActive) return
                cont.resume(if (error in UNAVAILABLE) SpeechResult.Unavailable else SpeechResult.NoSpeech)
            }

            override fun onUpdatedEpdData(onset: Long, offset: Long) = Unit
            override fun onStartedRecognition() = Unit
            override fun onReady() = Unit
            override fun onUpdatedRms(rms: Double) = Unit

            private fun done(text: String) {
                if (!cont.isActive) return
                cont.resume(if (text.isBlank()) SpeechResult.NoSpeech else SpeechResult.Text(text))
            }
        }
        addListener(listener)
        request()
        cont.invokeOnCancellation {
            runCatching { stop() }
            runCatching { removeListener(listener) }
        }
    }

    private companion object {
        const val TAG = "SpeechInput"
        /** SpeechToText SDK 가 찾는 Pleos 음성 서비스 액션 (SDK AndroidManifest 의 queries). */
        const val SPEECH_ACTION = "ai.pleos.playground.caas.intent.action.SPEECH_START"
        const val INIT_TIMEOUT_MS = 5_000L
        const val LISTEN_TIMEOUT_MS = 8_000L
        val UNAVAILABLE = setOf(
            ErrorType.ON_DEVICE_ENGINE_ERROR,
            ErrorType.UNAVAILABLE_LANGUAGE,
            ErrorType.NOT_SUPPORTED_API,
            ErrorType.PERMISSION_DENIED,
            ErrorType.UNAUTHENTICATED,
        )
    }
}
