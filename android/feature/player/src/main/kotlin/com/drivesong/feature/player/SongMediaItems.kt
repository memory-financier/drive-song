package com.drivesong.feature.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.drivesong.core.data.db.SongEntity
import java.io.File

/** 시스템 미디어 UI 탐색 트리의 루트. 바로 아래에 노래가 최근 순으로 있다 (한 단계 트리). */
internal const val LIBRARY_ROOT_ID = "root"

internal fun libraryRoot(title: String): MediaItem = MediaItem.Builder()
    .setMediaId(LIBRARY_ROOT_ID)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build(),
    )
    .build()

/** mediaId = 노래 id (SongEntity.id). 오디오는 내려받아 둔 로컬 m4a. */
fun SongEntity.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri(Uri.fromFile(File(audioPath)))
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build(),
    )
    .build()
