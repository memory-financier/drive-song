package com.drivesong.feature.voice

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivesong.core.data.db.ClipEntity
import com.drivesong.core.data.db.UploadState
import com.drivesong.core.data.voice.VoiceRepository
import com.drivesong.core.model.ClipPlanMode
import com.drivesong.core.model.ClipSlot

/** 호칭 프리셋 (설계 §2: 키보드 입력 없음, 프리셋 선택). */
private val NicknamePresets = listOf("엄마", "아빠", "첫째", "둘째", "셋째", "할머니", "할아버지", "이모", "삼촌")

private val ButtonHeight = 72.dp // Pleos 화면 터치 크기 (UI 시안 6)

/** 가족 구성원·녹음 (설계 §8, §8.1, §10). 정차 전용 화면. */
@Composable
fun MembersScreen(viewModel: MembersViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var adding by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.dismissMessage()
        }
    }

    // RECORD_AUDIO 런타임 권한: 처음 녹음할 때 요청하고, 허용되면 바로 녹음을 시작한다.
    var pendingRecord by remember { mutableStateOf<Pair<String, ClipSlot>?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val target = pendingRecord
        pendingRecord = null
        if (granted && target != null) {
            viewModel.startRecording(target.first, target.second)
        } else if (!granted) {
            Toast.makeText(context, R.string.voice_error_permission, Toast.LENGTH_LONG).show()
        }
    }
    val record = { memberId: String, slot: ClipSlot ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startRecording(memberId, slot)
        } else {
            pendingRecord = memberId to slot
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (adding) {
            AddMemberForm(
                usedNicknames = state.members.map { it.member.nickname }.toSet(),
                busy = state.busy,
                onCancel = { adding = false },
                onRegister = { nickname, isChild, voiceConsent, guardianConsent, conversation, conversion ->
                    viewModel.addMember(nickname, isChild, voiceConsent, guardianConsent, conversation, conversion) { adding = false }
                },
            )
        } else {
            MemberList(state, record, viewModel, onAdd = { adding = true })
        }
    }
}

@Composable
private fun MemberList(
    state: MembersUiState,
    onRecord: (String, ClipSlot) -> Unit,
    viewModel: MembersViewModel,
    onAdd: () -> Unit,
) {
    Text(stringResource(R.string.voice_title), style = MaterialTheme.typography.headlineMedium)
    ClipPlanModeRow(state.clipPlanMode, viewModel::setClipPlanMode)
    if (state.members.isEmpty()) {
        Text(stringResource(R.string.voice_no_members), style = MaterialTheme.typography.bodyLarge)
    }
    state.members.forEach { member ->
        MemberCard(
            member = member,
            state = state,
            onRecord = onRecord,
            viewModel = viewModel,
        )
    }
    Button(onClick = onAdd, enabled = !state.busy, modifier = Modifier.height(ButtonHeight)) {
        Text(stringResource(R.string.voice_add_member))
    }
}

@Composable
private fun ClipPlanModeRow(mode: ClipPlanMode, onSelect: (ClipPlanMode) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.voice_clip_mode_title), style = MaterialTheme.typography.titleMedium)
        FilterChip(
            selected = mode == ClipPlanMode.RANDOM,
            onClick = { onSelect(ClipPlanMode.RANDOM) },
            label = { Text(stringResource(R.string.voice_clip_mode_random)) },
        )
        FilterChip(
            selected = mode == ClipPlanMode.IN_ORDER,
            onClick = { onSelect(ClipPlanMode.IN_ORDER) },
            label = { Text(stringResource(R.string.voice_clip_mode_in_order)) },
        )
    }
}

@Composable
private fun MemberCard(
    member: MemberClips,
    state: MembersUiState,
    onRecord: (String, ClipSlot) -> Unit,
    viewModel: MembersViewModel,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(member.member.nickname, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (member.member.isChild) {
                    Text(stringResource(R.string.voice_child_badge), style = MaterialTheme.typography.labelLarge)
                }
                TextButton(onClick = { confirmDelete = true }, enabled = !state.busy) {
                    Text(stringResource(R.string.voice_delete))
                }
            }
            // 선택 동의 (설계 §8.3): 바로 서버에 반영. 목소리 변환을 끄면 보관한 참고 목소리가 지워진다
            val m = member.member
            ConsentSwitch(R.string.voice_consent_conversation_short, m.conversationConsent, !state.busy) {
                viewModel.setConsents(m.id, it, m.voiceConversionConsent)
            }
            ConsentSwitch(R.string.voice_consent_conversion_short, m.voiceConversionConsent, !state.busy) {
                viewModel.setConsents(m.id, m.conversationConsent, it)
            }
            ClipSlot.entries.forEach { slot ->
                SlotSection(member, slot, state, onRecord, viewModel)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.voice_delete_member_title, member.member.nickname)) },
            text = { Text(stringResource(R.string.voice_delete_member_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteMember(member.member.id)
                }) { Text(stringResource(R.string.voice_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.voice_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotSection(
    member: MemberClips,
    slot: ClipSlot,
    state: MembersUiState,
    onRecord: (String, ClipSlot) -> Unit,
    viewModel: MembersViewModel,
) {
    val clips = member.clips(slot)
    val recording = state.recording
    val here = when (recording) {
        is RecordingState.Recording -> recording.memberId == member.member.id && recording.slot == slot
        is RecordingState.Draft -> recording.memberId == member.member.id && recording.slot == slot
        RecordingState.Idle -> false
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${stringResource(slot.label)}  " +
                stringResource(R.string.voice_slot_count, clips.size, VoiceRepository.MAX_CLIPS_PER_SLOT),
            style = MaterialTheme.typography.titleSmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            clips.forEachIndexed { index, clip -> ClipItem(index + 1, clip, clip.localId in state.uploading, viewModel) }
            when {
                here && recording is RecordingState.Recording -> {
                    Text(stringResource(R.string.voice_recording), modifier = Modifier.align(Alignment.CenterVertically))
                    Button(onClick = viewModel::stopRecording, modifier = Modifier.height(ButtonHeight)) {
                        Text(stringResource(R.string.voice_stop))
                    }
                }
                here && recording is RecordingState.Draft -> {
                    OutlinedButton(onClick = viewModel::previewDraft, modifier = Modifier.height(ButtonHeight)) {
                        Text(stringResource(R.string.voice_preview))
                    }
                    Button(onClick = viewModel::saveDraft, modifier = Modifier.height(ButtonHeight)) {
                        Text(stringResource(R.string.voice_save))
                    }
                    OutlinedButton(onClick = viewModel::discardDraft, modifier = Modifier.height(ButtonHeight)) {
                        Text(stringResource(R.string.voice_discard))
                    }
                }
                else -> Button(
                    onClick = { onRecord(member.member.id, slot) },
                    enabled = state.canRecord(member.member.id, slot),
                    modifier = Modifier.height(ButtonHeight),
                ) { Text(stringResource(R.string.voice_record)) }
            }
        }
    }
}

@Composable
private fun ClipItem(number: Int, clip: ClipEntity, uploading: Boolean, viewModel: MembersViewModel) {
    Card {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewModel.playClip(clip) }, modifier = Modifier.height(ButtonHeight)) {
                Text(stringResource(R.string.voice_clip_play, number))
            }
            when {
                uploading -> Text(stringResource(R.string.voice_upload_in_progress))
                clip.uploadState != UploadState.UPLOADED -> {
                    Text(stringResource(R.string.voice_upload_failed))
                    TextButton(onClick = { viewModel.retryUpload(clip) }, modifier = Modifier.height(ButtonHeight)) {
                        Text(stringResource(R.string.voice_retry))
                    }
                }
            }
            TextButton(onClick = { viewModel.deleteClip(clip) }, enabled = !uploading, modifier = Modifier.height(ButtonHeight)) {
                Text(stringResource(R.string.voice_delete))
            }
        }
    }
}

/**
 * 가족 추가 + 목소리 수집 동의 (설계 §8.1). 아이는 보호자 동의가 있어야 등록할 수 있다.
 * v0.4 (§8.3): 대화 녹음·노래 목소리 변환은 선택 동의 (나중에 구성원 카드에서 바꿀 수 있다).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddMemberForm(
    usedNicknames: Set<String>,
    busy: Boolean,
    onCancel: () -> Unit,
    onRegister: (
        nickname: String,
        isChild: Boolean,
        voiceConsent: Boolean,
        guardianConsent: Boolean,
        conversationConsent: Boolean,
        voiceConversionConsent: Boolean,
    ) -> Unit,
) {
    var nickname by rememberSaveable { mutableStateOf<String?>(null) }
    var isChild by rememberSaveable { mutableStateOf(false) }
    var voiceConsent by rememberSaveable { mutableStateOf(false) }
    var guardianConsent by rememberSaveable { mutableStateOf(false) }
    var conversationConsent by rememberSaveable { mutableStateOf(false) }
    var voiceConversionConsent by rememberSaveable { mutableStateOf(false) }

    Text(stringResource(R.string.voice_add_title), style = MaterialTheme.typography.headlineMedium)
    Text(stringResource(R.string.voice_nickname_title), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NicknamePresets.forEach { preset ->
            FilterChip(
                selected = nickname == preset,
                onClick = { nickname = preset },
                enabled = preset !in usedNicknames,
                label = { Text(preset, style = MaterialTheme.typography.titleMedium) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.voice_child_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = isChild, onCheckedChange = { isChild = it })
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.voice_consent_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.voice_consent_body), style = MaterialTheme.typography.bodyLarge)
            ConsentCheck(R.string.voice_consent_check, voiceConsent) { voiceConsent = it }
        }
    }
    OptionalConsent(R.string.voice_conversation_consent_title, R.string.voice_conversation_consent_body,
        R.string.voice_conversation_consent_check, conversationConsent) { conversationConsent = it }
    OptionalConsent(R.string.voice_conversion_consent_title, R.string.voice_conversion_consent_body,
        R.string.voice_conversion_consent_check, voiceConversionConsent) { voiceConversionConsent = it }
    if (isChild) ConsentCheck(R.string.voice_guardian_check, guardianConsent) { guardianConsent = it }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.height(ButtonHeight)) {
            Text(stringResource(R.string.voice_cancel))
        }
        val selected = nickname
        Button(
            onClick = {
                if (selected != null) {
                    onRegister(selected, isChild, voiceConsent, isChild && guardianConsent, conversationConsent, voiceConversionConsent)
                }
            },
            enabled = !busy && selected != null && selected !in usedNicknames && voiceConsent && (!isChild || guardianConsent),
            modifier = Modifier.height(ButtonHeight),
        ) { Text(stringResource(R.string.voice_register)) }
    }
}

/** 글자를 눌러도 체크된다 (줄 전체가 터치 영역, 높이 56dp). */
@Composable
private fun ConsentCheck(@StringRes text: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(ButtonHeight).toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun OptionalConsent(@StringRes title: Int, @StringRes body: Int, @StringRes check: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
            ConsentCheck(check, checked, onChange)
        }
    }
}

@Composable
private fun ConsentSwitch(@StringRes label: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(ButtonHeight)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private val ClipSlot.label: Int
    get() = when (this) {
        ClipSlot.INTRO -> R.string.voice_slot_intro
        ClipSlot.CALL -> R.string.voice_slot_call
        ClipSlot.OUTRO -> R.string.voice_slot_outro
    }
