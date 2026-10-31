package com.innova.diag

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.innova.diag.ui.AppTheme
import com.innova.diag.ui.TransportShellScreen
import com.innova.diag.ui.TransportShellViewModel

/**
 * Single-activity host for the transport shell.
 *
 * The only thing this phase proves on-device: the transport contract, its fake, and the state flow
 * compose correctly. No adapter, no permissions, no diagnostic traffic.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: TransportShellViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                TransportShellScreen(
                    state = state,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                )
            }
        }
    }
}
