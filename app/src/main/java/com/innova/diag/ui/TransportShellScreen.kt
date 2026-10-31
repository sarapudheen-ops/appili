package com.innova.diag.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.innova.diag.core.transport.TransportState

/**
 * Shell screen: renders the current [TransportState] and nothing else.
 *
 * Stateless by design — the state arrives from [TransportShellViewModel], which lets the same
 * composable render a fake link today and a real adapter later, and makes the three previews below
 * possible without coroutines.
 */
@Composable
fun TransportShellScreen(
    state: TransportState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Transport shell",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Contract-only phase. The screen is wired to FakeByteTransport: " +
                    "no adapter is opened and no vehicle is contacted.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TransportStateCard(state)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onConnect, enabled = state.canConnect) {
                    Text("Connect")
                }
                OutlinedButton(onClick = onDisconnect, enabled = state.canDisconnect) {
                    Text("Disconnect")
                }
            }

            Text(
                text = state.detail(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransportStateCard(state: TransportState) {
    val colorScheme = MaterialTheme.colorScheme
    val label: String
    val containerColor: Color
    val contentColor: Color

    when (state) {
        TransportState.DISCONNECTED -> {
            label = "DISCONNECTED"
            containerColor = colorScheme.surfaceVariant
            contentColor = colorScheme.onSurfaceVariant
        }
        TransportState.CONNECTING -> {
            label = "CONNECTING"
            containerColor = colorScheme.tertiaryContainer
            contentColor = colorScheme.onTertiaryContainer
        }
        TransportState.CONNECTED -> {
            label = "CONNECTED"
            containerColor = colorScheme.primaryContainer
            contentColor = colorScheme.onPrimaryContainer
        }
        is TransportState.ERROR -> {
            label = "ERROR"
            containerColor = colorScheme.errorContainer
            contentColor = colorScheme.onErrorContainer
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "TRANSPORT STATE",
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
            if (state is TransportState.ERROR) {
                Text(
                    text = state.throwable.message ?: state.throwable::class.java.simpleName,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = contentColor,
                )
            }
        }
    }
}

private val TransportState.canConnect: Boolean
    get() = this == TransportState.DISCONNECTED || this is TransportState.ERROR

private val TransportState.canDisconnect: Boolean
    get() = this == TransportState.CONNECTED || this == TransportState.CONNECTING

private fun TransportState.detail(): String = when (this) {
    TransportState.DISCONNECTED -> "Fake link closed. Bytes buffered at disconnect are discarded."
    TransportState.CONNECTING -> "Opening the fake link; CONNECTING is observable to the UI."
    TransportState.CONNECTED -> "Fake link open. Nothing is read or parsed in this phase."
    is TransportState.ERROR -> "Link failed. Cause: ${throwable::class.java.simpleName}."
}

@Preview(showBackground = true, name = "Disconnected")
@Composable
private fun TransportShellDisconnectedPreview() {
    AppTheme {
        TransportShellScreen(
            state = TransportState.DISCONNECTED,
            onConnect = {},
            onDisconnect = {},
        )
    }
}

@Preview(showBackground = true, name = "Connected")
@Composable
private fun TransportShellConnectedPreview() {
    AppTheme {
        TransportShellScreen(
            state = TransportState.CONNECTED,
            onConnect = {},
            onDisconnect = {},
        )
    }
}

@Preview(showBackground = true, name = "Error")
@Composable
private fun TransportShellErrorPreview() {
    AppTheme {
        TransportShellScreen(
            state = TransportState.ERROR(IllegalStateException("rfcomm socket closed")),
            onConnect = {},
            onDisconnect = {},
        )
    }
}
