package com.drivesong.feature.song

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.drivesong.core.data.db.SongEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** 사용자 알림 (설계 §7.1: 도착 후 완성 시 즉시 알림, 재시도 후 실패 알림). */
interface SongNotifier {
    fun songReady(song: SongEntity)
    fun songFailed()
}

/** 알림을 누르면 앱을 연다. 알림 권한이 없으면 조용히 건너뛴다 (상태는 홈 화면에도 보인다). */
class NotificationSongNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : SongNotifier {

    override fun songReady(song: SongEntity) =
        post(READY_ID, context.getString(R.string.song_ready_title), song.title)

    override fun songFailed() =
        post(FAILED_ID, context.getString(R.string.song_failed_title), context.getString(R.string.song_failed_text))

    private fun post(id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.song_channel_name))
                .build(),
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        manager.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val CHANNEL_ID = "song_status"
        const val READY_ID = 2001
        const val FAILED_ID = 2002
    }
}
