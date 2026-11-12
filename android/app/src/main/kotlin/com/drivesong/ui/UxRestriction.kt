package com.drivesong.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.drivesong.R

/** 현재 주행 제한 여부. 제공자가 없으면 안전하게 제한 상태로 본다. */
val LocalUxRestricted = staticCompositionLocalOf { true }

/** 정차 전용 화면: 주행 제한 중이면 [content] 대신 안내 문구만 보여준다 (설계 §10). */
@Composable
fun ParkedOnlyGate(content: @Composable () -> Unit) {
    if (LocalUxRestricted.current) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.parked_only_message),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
    } else {
        content()
    }
}
