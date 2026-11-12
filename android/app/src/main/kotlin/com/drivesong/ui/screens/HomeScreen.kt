package com.drivesong.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivesong.R
import com.drivesong.core.data.db.SongEntity
import com.drivesong.core.model.ClipSlot
import com.drivesong.core.network.dto.SongJobStatus
import com.drivesong.feature.song.SongPhase
import com.drivesong.feature.song.SongState
import com.drivesong.feature.trip.TripRecorderService
import com.drivesong.feature.trip.TripState
import com.drivesong.feature.voice.command.VoiceResult
import com.drivesong.feature.voice.command.VoiceStatus
import com.drivesong.ui.LocalUxRestricted
import com.drivesong.ui.PauseIcon
import com.drivesong.ui.RouteCover
import com.drivesong.ui.theme.Night
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 홈 (설계 §10, UI 시안 6 '가족 앨범 × 나이트'): 진행 상태 알약 + 주행 기록, 오늘의 노래(경로 그림),
 * 우리 가족 노래 앨범. 주행 중에는 정차 전용 진입점(탑승자 선택)을 숨긴다. 움직이는 요소 없음 (설계 §2).
 */
@Composable
fun HomeScreen(onPlayRecent: () -> Unit, onChoosePassengers: () -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val restricted = LocalUxRestricted.current
    val tripState by viewModel.tripState.collectAsStateWithLifecycle()
    val songState by viewModel.songState.collectAsStateWithLifecycle()
    val recentSong by viewModel.recentSong.collectAsStateWithLifecycle()
    val albumSongs by viewModel.albumSongs.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val voiceStatus by viewModel.voiceStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.speak() else Toast.makeText(context, R.string.home_voice_mic_denied, Toast.LENGTH_LONG).show()
    }
    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!TripRecorderService.start(context)) {
            Toast.makeText(context, R.string.home_trip_permission_denied, Toast.LENGTH_LONG).show()
        }
    }
    val speak = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.speak()
        } else {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        StatusRow(
            tripState = tripState,
            songState = songState,
            onStartTrip = { requestPermissions.launch(TRIP_PERMISSIONS) },
            onStopTrip = { TripRecorderService.stop(context) },
        )
        TodaySong(
            song = recentSong,
            isPlaying = isPlaying,
            onPlayPause = {
                if (isPlaying) {
                    viewModel.pause()
                } else {
                    viewModel.playRecent()
                    onPlayRecent()
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.home_album_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (!restricted) {
                OutlinedButton(onClick = onChoosePassengers, border = BorderStroke(2.dp, Night.Line), modifier = Modifier.height(80.dp)) {
                    Text(stringResource(R.string.home_choose_passengers), style = MaterialTheme.typography.labelLarge, color = Night.Text)
                }
            }
            // 음성 명령 (설계 §8.2): 주행 중에도 보인다
            OutlinedButton(
                onClick = speak,
                enabled = voiceStatus !is VoiceStatus.Listening && voiceStatus !is VoiceStatus.Recording,
                border = BorderStroke(2.dp, Night.Accent),
                modifier = Modifier.height(80.dp),
            ) {
                Text(stringResource(R.string.home_voice_button), style = MaterialTheme.typography.labelLarge, color = Night.Text)
            }
        }
        Text(
            voiceStatusText(voiceStatus) ?: stringResource(R.string.home_voice_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = Night.SubText,
        )
        if (albumSongs.isEmpty()) {
            Text(stringResource(R.string.home_album_empty), style = MaterialTheme.typography.bodyLarge, color = Night.SubText)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                albumSongs.forEach { song ->
                    AlbumCard(song, Modifier.weight(1f)) {
                        viewModel.playSong(song.id)
                        onPlayRecent()
                    }
                }
                // 3곡보다 적어도 카드 폭은 같게
                repeat(ALBUM_COLUMNS - albumSongs.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 진행 상태 알약 (원형 진행 n/5) + 주행 기록 시작·끄기. */
@Composable
private fun StatusRow(tripState: TripState, songState: SongState, onStartTrip: () -> Unit, onStopTrip: () -> Unit) {
    val tripText = stringResource(
        when (tripState) {
            TripState.Off -> R.string.home_status_off
            TripState.Idle -> R.string.home_status_idle
            is TripState.Recording -> R.string.home_status_recording
        },
    )
    val step = songStep(songState)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            Modifier.weight(1f).height(84.dp).clip(CircleShape).background(Night.Card).border(1.dp, Night.Line, CircleShape)
                .padding(start = 14.dp, end = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProgressRing(step?.let { it.toFloat() / SONG_STEPS } ?: 0f, Modifier.size(56.dp))
            Text(
                listOfNotNull(tripText, songStatus(songState)).joinToString(" · "),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (step != null) {
                Text(stringResource(R.string.home_step, step, SONG_STEPS), style = MaterialTheme.typography.titleMedium, color = Night.Accent)
            }
        }
        if (tripState == TripState.Off) {
            Button(onClick = onStartTrip, modifier = Modifier.height(84.dp)) {
                Text(stringResource(R.string.home_trip_start), style = MaterialTheme.typography.labelLarge)
            }
        } else {
            OutlinedButton(onClick = onStopTrip, border = BorderStroke(2.dp, Night.Line), modifier = Modifier.height(84.dp)) {
                Text(stringResource(R.string.home_trip_stop), style = MaterialTheme.typography.labelLarge, color = Night.Text)
            }
        }
    }
}

/** 정적 원형 진행 (애니메이션 없음). */
@Composable
private fun ProgressRing(fraction: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
        drawArc(Night.Line, startAngle = 0f, sweepAngle = 360f, useCenter = false, style = stroke)
        if (fraction > 0f) drawArc(Night.Accent, startAngle = -90f, sweepAngle = 360f * fraction, useCenter = false, style = stroke)
    }
}

/** 오늘의 노래: 가장 최근 노래와 그 경로 그림. 노래가 없으면 안내. */
@Composable
private fun TodaySong(song: SongEntity?, isPlaying: Boolean, onPlayPause: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(400.dp).clip(RoundedCornerShape(32.dp)).background(Night.Hero)) {
        Column(Modifier.weight(7f).fillMaxHeight().padding(48.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val today = song != null && Instant.ofEpochMilli(song.createdAtMs).atZone(ZoneId.systemDefault()).toLocalDate() ==
                LocalDate.now(ZoneId.systemDefault())
            Text(
                stringResource(if (today || song == null) R.string.home_today_song else R.string.home_recent_song),
                style = MaterialTheme.typography.titleSmall,
                color = Night.SubText,
            )
            Text(
                song?.title ?: stringResource(R.string.home_no_song),
                style = MaterialTheme.typography.displaySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song?.let { songMeta(it) } ?: stringResource(R.string.home_no_song_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = Night.SubText,
            )
            Spacer(Modifier.weight(1f))
            Button(onClick = onPlayPause, enabled = song != null, modifier = Modifier.height(96.dp).width(320.dp)) {
                Icon(if (isPlaying) PauseIcon else Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(if (isPlaying) R.string.player_pause else R.string.player_play), style = MaterialTheme.typography.titleLarge)
            }
        }
        RouteCover(song?.waypoints.orEmpty(), Modifier.weight(5f).fillMaxHeight())
    }
}

@Composable
private fun AlbumCard(song: SongEntity, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(28.dp)).background(Night.Card).border(1.dp, Night.Line, RoundedCornerShape(28.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(formatDay(song.createdAtMs), style = MaterialTheme.typography.bodyMedium, color = Night.SubText)
        Text(song.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(singersText(song), style = MaterialTheme.typography.bodyMedium, color = Night.SubText)
    }
}

/** "11월 2일 · 엄마 · 아빠 · 첫째" */
@Composable
private fun songMeta(song: SongEntity): String = "${formatDay(song.createdAtMs)} · ${singersText(song)}"

@Composable
private fun singersText(song: SongEntity): String =
    song.singers.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: stringResource(R.string.home_ai_singer)

private val DAY_FORMAT = DateTimeFormatter.ofPattern("M월 d일")

private fun formatDay(epochMs: Long): String = DAY_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private const val ALBUM_COLUMNS = 3
private const val SONG_STEPS = 5

/**
 * 노래 만들기 단계 (원형 진행): 1 주행·대화 담기 → 2 가사(대화 분석) → 3 작곡 → 4 가족 목소리 → 5 완성.
 * 대화가 없는 노래는 4단계를 건너뛴다. 기록 전·실패면 null.
 */
internal fun songStep(state: SongState): Int? = when (state.phase) {
    SongPhase.IDLE, SongPhase.FAILED -> null
    SongPhase.RECORDING_TRIP -> 1
    SongPhase.GENERATING_LYRICS, SongPhase.SUBMITTING_JOB -> 2
    SongPhase.WAITING_FOR_SONG -> when (state.serverStatus) {
        SongJobStatus.QUEUED, SongJobStatus.ANALYZING, null -> 2
        SongJobStatus.GENERATING -> 3
        SongJobStatus.CONVERTING, SongJobStatus.MIXING -> 4
        SongJobStatus.READY, SongJobStatus.FAILED -> 5
    }
    SongPhase.READY, SongPhase.PLAYING, SongPhase.ARCHIVED -> 5
}

/** 음성 명령 상태 한 줄 (설계 §8.2). null = 안내 문구를 보여준다. */
@Composable
private fun voiceStatusText(status: VoiceStatus): String? = when (status) {
    VoiceStatus.Idle -> null
    VoiceStatus.Listening -> stringResource(R.string.voice_status_listening)
    is VoiceStatus.Recording -> stringResource(R.string.voice_status_recording, status.nickname, slotName(status.slot))
    is VoiceStatus.Finished -> when (status.result) {
        VoiceResult.PLAYING -> stringResource(R.string.voice_result_playing)
        VoiceResult.PAUSED -> stringResource(R.string.voice_result_paused)
        VoiceResult.NO_SONG -> stringResource(R.string.voice_result_no_song)
        VoiceResult.SAVED -> stringResource(
            R.string.voice_result_saved,
            status.nickname.orEmpty(),
            status.slot?.let { slotName(it) }.orEmpty(),
        )
        VoiceResult.WHO -> stringResource(R.string.voice_result_who)
        VoiceResult.UNKNOWN_MEMBER -> stringResource(R.string.voice_result_unknown_member, status.nickname.orEmpty())
        VoiceResult.MIC_ERROR -> stringResource(R.string.voice_result_mic_error)
        VoiceResult.TOO_SHORT -> stringResource(R.string.voice_result_too_short)
        VoiceResult.SAVE_FAILED -> stringResource(R.string.voice_result_save_failed)
        VoiceResult.NOT_UNDERSTOOD -> stringResource(R.string.voice_result_not_understood)
        VoiceResult.STT_UNAVAILABLE -> stringResource(R.string.voice_result_stt_unavailable)
        VoiceResult.BUSY -> stringResource(R.string.voice_result_busy)
    }
}

@Composable
private fun slotName(slot: ClipSlot): String = stringResource(
    when (slot) {
        ClipSlot.INTRO -> R.string.voice_slot_name_intro
        ClipSlot.CALL -> R.string.voice_slot_name_call
        ClipSlot.OUTRO -> R.string.voice_slot_name_outro
    },
)

/** 노래 생성 상태 한 줄 (설계 §7.1, §10). 정적 텍스트만 (주행 중 움직이는 요소 최소화, 설계 §2). */
@Composable
private fun songStatus(state: SongState): String? = when {
    state.almostReady -> stringResource(R.string.song_status_almost_ready)
    else -> when (state.phase) {
        SongPhase.IDLE -> null
        SongPhase.RECORDING_TRIP -> stringResource(R.string.song_status_recording)
        SongPhase.GENERATING_LYRICS -> stringResource(R.string.song_status_lyrics)
        SongPhase.SUBMITTING_JOB -> stringResource(R.string.song_status_making)
        // 대화 노래 (설계 §7.5): 서버 단계를 그대로 보여 준다
        SongPhase.WAITING_FOR_SONG -> when (state.serverStatus) {
            SongJobStatus.ANALYZING -> stringResource(R.string.song_status_analyzing)
            SongJobStatus.CONVERTING -> stringResource(R.string.song_status_converting)
            else -> stringResource(R.string.song_status_making)
        }
        SongPhase.READY -> stringResource(R.string.song_status_ready, state.song?.title.orEmpty())
        SongPhase.PLAYING -> stringResource(R.string.song_status_playing, state.song?.title.orEmpty())
        SongPhase.ARCHIVED -> stringResource(R.string.song_status_archived)
        SongPhase.FAILED -> stringResource(R.string.song_status_failed)
    }
}

/**
 * 주행 기록 시작 전에 받는 권한. 위치(Foreground Service location 타입에 필수), 알림(API 33+),
 * 차속(CAR_SPEED, AAOS 에서 dangerous 로 알려져 있음 [확인 필요]). 폰에서는 CAR_SPEED 가 거부될 뿐 문제없다.
 * CAR_POWERTRAIN(기어)은 normal 권한으로 알려져 있어 요청하지 않는다 [확인 필요].
 */
private val TRIP_PERMISSIONS = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add("android.car.permission.CAR_SPEED")
    // 도착 조명 연출 (설계 §6.5). Pleos 가 아닌 기기에서는 거부될 뿐이다. 보호 수준 [확인 필요]
    add("pleos.car.permission.CAR_LIGHTS")
    add("pleos.car.permission.CONTROL_CAR_LIGHTS")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()
