package com.drivesong.feature.voice

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 이번 주행 탑승자 (설계 §10). 기본값은 지난번 선택. 정차 전용 화면 (출발 전 선택). */
@Composable
fun PassengersScreen(onManageMembers: () -> Unit, viewModel: PassengersViewModel = hiltViewModel()) {
    val members by viewModel.members.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val canCapture by viewModel.canCapture.collectAsStateWithLifecycle()
    val captureOn by viewModel.captureOn.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 대화 담기는 마이크를 쓴다: 켤 때 권한을 묻는다
    val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.setCapture(true) else Toast.makeText(context, R.string.voice_error_permission, Toast.LENGTH_LONG).show()
    }
    val turnCapture = { on: Boolean ->
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (on && !micGranted) requestMic.launch(Manifest.permission.RECORD_AUDIO) else viewModel.setCapture(on)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.passengers_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.passengers_description), style = MaterialTheme.typography.bodyLarge)
        if (members.isEmpty()) {
            Text(stringResource(R.string.passengers_empty), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onManageMembers, modifier = Modifier.height(72.dp)) {
                Text(stringResource(R.string.passengers_manage_members))
            }
        }
        members.forEach { member ->
            ListItem(
                headlineContent = { Text(member.nickname, style = MaterialTheme.typography.titleLarge) },
                leadingContent = { Checkbox(checked = member.id in selected, onCheckedChange = null) },
                modifier = Modifier.height(88.dp).clickable { viewModel.toggle(member.id) },
            )
        }
        if (members.isNotEmpty()) ConversationCaptureCard(canCapture, captureOn, turnCapture)
    }
}

/** '대화 담기' (설계 §8.3): 이번 주행만. 탑승자 모두가 대화 녹음에 동의해야 켤 수 있다. */
@Composable
private fun ConversationCaptureCard(canCapture: Boolean, on: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().height(72.dp)
                    .toggleable(on, enabled = canCapture || on, role = Role.Switch, onValueChange = onChange),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.passengers_conversation_title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = null, enabled = canCapture || on)
            }
            Text(stringResource(R.string.passengers_conversation_body), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (canCapture) R.string.passengers_conversation_notice else R.string.passengers_conversation_need_consent),
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}
