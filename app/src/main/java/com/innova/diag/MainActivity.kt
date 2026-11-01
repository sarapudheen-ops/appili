package com.innova.diag

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.innova.diag.platform.bluetooth.BLUETOOTH_RUNTIME_PERMISSIONS
import com.innova.diag.platform.profile.BrandCatalogue
import com.innova.diag.ui.ActiveTestViewModel
import com.innova.diag.ui.AppTheme
import com.innova.diag.ui.DiagnosticsApp
import com.innova.diag.ui.DiagnosticsViewModel
import com.innova.diag.ui.SessionExportPayload

/**
 * Single-activity host.
 *
 * Two Android-only responsibilities live here, at the boundary: runtime permissions and the share
 * sheet. Neither leaks into the view model or the domain.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadBrandCatalogue()
        setContent {
            AppTheme { DiagnosticsRoute(viewModel, activeTestViewModel) }
        }
    }

    /**
     * Loads every brand pack from assets at startup, through the schema.
     *
     * Non-fatal on purpose: a rejected pack is a data defect, and the aggregated problem list (with JSON
     * pointers) belongs in the log, not in a crash. A `null` catalogue means "no brand is supported",
     * which is the fail-closed reading — see [BrandCatalogue].
     */
    private fun loadBrandCatalogue() {
        val registry = BrandCatalogue.load(applicationContext)
        if (registry == null) {
            Log.e(LOG_TAG, "brand packs rejected: ${BrandCatalogue.lastError}")
        } else {
            Log.i(
                LOG_TAG,
                "brand packs loaded: " + registry.listTiers().entries.joinToString { (brand, tier) ->
                    "$brand=$tier"
                },
            )
        }
    }

    private companion object {
        private const val LOG_TAG = "appili"
    }

    private val viewModel: DiagnosticsViewModel by viewModels()

    /**
     * Bench-only. It owns a [com.innova.diag.domain.active.MockEcuSimulator], so nothing it can do
     * touches the Bluetooth transport the other view model drives.
     */
    private val activeTestViewModel: ActiveTestViewModel by viewModels()
}

@Composable
private fun DiagnosticsRoute(
    viewModel: DiagnosticsViewModel,
    activeTestViewModel: ActiveTestViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activeTestState by activeTestViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

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

    // Polling the vehicle with nobody watching is pure cost: stop it when the host leaves the screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.onHostStopped()
    }

    DiagnosticsApp(
        state = state,
        onRequestPermissions = {
            permissionLauncher.launch(BLUETOOTH_RUNTIME_PERMISSIONS.toTypedArray())
        },
        onRefreshDevices = viewModel::refreshPairedDevices,
        onSelectDevice = viewModel::selectDevice,
        onConnect = viewModel::connect,
        onConnectDemo = viewModel::connectDemoAdapter,
        onDisconnect = viewModel::disconnect,
        onScan = viewModel::scan,
        onToggleLiveData = {
            if (state.live.polling) viewModel.stopLiveData() else viewModel.startLiveData()
        },
        onSendRawCommand = viewModel::sendRawCommand,
        onExport = { format ->
            val payload = viewModel.buildExport(format)
            if (payload != null) shareSessionLog(context, payload)
        },
        onSelectScreen = viewModel::navigateTo,
        onDismissMessage = viewModel::dismissMessage,
        activeTest = activeTestState,
        onActiveTestHoldStart = activeTestViewModel::holdStart,
        onActiveTestHoldEnd = activeTestViewModel::holdEnd,
        onActiveTestDropLink = activeTestViewModel::dropLink,
        onActiveTestReconnect = activeTestViewModel::reconnect,
    )
}

/**
 * Hands the log to the platform share sheet as text.
 *
 * No file is written and no storage permission is requested: the payload is already sanitized
 * (`SessionSanitizer`), so sharing it cannot leak a VIN or a Bluetooth address by way of a forgotten
 * cache file.
 */
private fun shareSessionLog(context: Context, payload: SessionExportPayload) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = payload.mimeType
        putExtra(Intent.EXTRA_SUBJECT, payload.fileName)
        putExtra(Intent.EXTRA_TITLE, payload.fileName)
        putExtra(Intent.EXTRA_TEXT, payload.text)
    }
    context.startActivity(Intent.createChooser(intent, "Share ${payload.fileName}"))
}
