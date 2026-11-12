package com.drivesong.feature.trip

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.drivesong.core.data.conversation.ConversationRepository
import com.drivesong.feature.trip.conversation.ConversationRecorder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 주행 기록 Foreground Service (location 타입, 설계 §3 TripRecorder). 실제 기록은 [TripRecorder] 가 한다.
 * 앱 화면에서 [start] / [stop] 한다. 정확한 위치 권한(FINE)은 시작 전에 앱이 받아 두어야 한다.
 *
 * 대화 담기 (설계 §8.3): [ConversationRecorder] 도 여기서 돈다. 대화 담기가 켜져 있으면 microphone 타입을 더한다
 * (Android 14 는 백그라운드에서 마이크 타입을 새로 켤 수 없다: 대화 담기는 화면에서 켜므로 그때 바뀐다).
 */
@AndroidEntryPoint
class TripRecorderService : Service() {

    @Inject lateinit var recorder: TripRecorder

    @Inject lateinit var conversationRecorder: ConversationRecorder

    @Inject lateinit var conversation: ConversationRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasLocationPermission(this)) {
            // API 34+ 에서 권한 없이 location 타입으로 startForeground 하면 SecurityException
            Log.w(TAG, "location permission not granted; not recording")
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground(conversation.armed.value)
        if (job == null) {
            job = scope.launch {
                launch { conversationRecorder.run() }
                launch { conversation.armed.drop(1).collect { withContext(Dispatchers.Main) { goForeground(it) } } }
                recorder.run()
            }
        }
        // 프로세스가 죽으면 다시 켜지 않는다 (백그라운드에서 위치 FGS 재시작은 제한됨). 진행 중이던 주행은 미종료로 남는다.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel() // TripRecorder.run 이 진행 중 주행을 STOPPED 로 끝내 저장한다
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 대화 담기를 켰고 마이크 권한이 있으면 microphone 타입을 더한다. 거부되면 위치만으로 (녹음은 시작하지 못한다). */
    private fun goForeground(conversationOn: Boolean) {
        val mic = conversationOn && hasMicPermission(this)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(mic), foregroundTypes(mic))
        } catch (e: RuntimeException) { // 백그라운드에서 마이크 타입 (ForegroundServiceStartNotAllowedException·SecurityException)
            if (!mic) throw e
            Log.w(TAG, "microphone foreground type not allowed now", e)
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(false), foregroundTypes(false))
        }
    }

    private fun notification(conversationOn: Boolean) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle(getString(R.string.trip_notification_title))
        .setContentText(getString(if (conversationOn) R.string.trip_notification_text_conversation else R.string.trip_notification_text))
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
            },
        )
        .build()

    private fun createChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.trip_channel_name))
                .build(),
        )
    }

    companion object {
        private const val TAG = "TripRecorderService"
        private const val CHANNEL_ID = "trip_recording"
        private const val NOTIFICATION_ID = 1001

        /** 정확한 위치 권한이 없으면 시작하지 않고 false. */
        fun start(context: Context): Boolean {
            if (!hasLocationPermission(context)) return false
            ContextCompat.startForegroundService(context, Intent(context, TripRecorderService::class.java))
            return true
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TripRecorderService::class.java))
        }

        /**
         * 위치 소스(GPS_PROVIDER)는 FINE 이 있어야 위치를 준다. 대략적 위치(COARSE)만으로 시작하면 위치 없는 주행만
         * 쌓여 매번 노래 실패 알림이 뜨므로 FINE 만 인정한다 (Android 12+ 권한 창에서 '대략적 위치'를 고른 경우).
         */
        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun hasMicPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun foregroundTypes(microphone: Boolean): Int =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or (if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
    }
}
