package com.innova.diag

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.innova.diag.platform.bluetooth.BLUETOOTH_RUNTIME_PERMISSIONS
import com.innova.diag.ui.AppTheme
import com.innova.diag.ui.BluetoothShellScreen
import com.innova.diag.ui.BluetoothShellViewModel

/**
 * Single-activity host for the adapter screen.
 *
 * Runtime permissions are requested here, at the Android boundary, and the result is pushed into
 * the injected permission provider. The transport itself never touches the permission API.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: BluetoothShellViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme { BluetoothShellRoute(viewModel) }
        }
    }
}

@Composable
private fun BluetoothShellRoute(viewModel: BluetoothShellViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.onPermissionsChanged() }

    // Ask once on first composition; afterwards the user drives it from the screen.
    var requestedOnLaunch by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.permissions.connectGranted) {
        if (!state.permissions.connectGranted && !requestedOnLaunch) {
            requestedOnLaunch = true
            permissionLauncher.launch(BLUETOOTH_RUNTIME_PERMISSIONS.toTypedArray())
        }
    }

    // Returning from system settings can change the answer without any callback of ours.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onPermissionsChanged()
    }

    BluetoothShellScreen(
        state = state,
        onRequestPermissions = {
            permissionLauncher.launch(BLUETOOTH_RUNTIME_PERMISSIONS.toTypedArray())
        },
        onRefreshDevices = viewModel::refreshPairedDevices,
        onSelectDevice = viewModel::selectDevice,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
    )
}
