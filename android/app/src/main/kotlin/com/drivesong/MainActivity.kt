package com.drivesong

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivesong.ui.ConversationBanner
import com.drivesong.ui.DriveSongApp
import com.drivesong.ui.theme.DriveSongTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val restricted by viewModel.isRestricted.collectAsStateWithLifecycle()
            val simulation by viewModel.restrictionSimulation.collectAsStateWithLifecycle()
            val conversationOn by viewModel.conversationOn.collectAsStateWithLifecycle()
            val conversationRecording by viewModel.conversationRecording.collectAsStateWithLifecycle()
            DriveSongTheme {
                DriveSongApp(
                    restricted = restricted,
                    restrictionSimulation = simulation,
                    onRestrictionSimulationChange = viewModel::setRestrictionSimulation,
                    conversation = ConversationBanner(conversationOn, conversationRecording),
                    onTurnOffConversation = viewModel::turnOffConversation,
                )
            }
        }
    }
}
