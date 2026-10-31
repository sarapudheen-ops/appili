package com.innova.diag.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.innova.diag.core.transport.TransportState

/**
 * Renders [TransportState] with its own visual language.
 *
 * `ERROR` deliberately looks different from `DISCONNECTED`: "the link dropped" and "the link was
 * never opened" mean different things to a technician, and `RESULT_UNKNOWN` (a transaction outcome,
 * not a connection state) never appears here at all — see `docs/SAFETY_POLICY.md` §4.3.
 */
@Composable
fun TransportStateCard(
    state: TransportState,
    modifier: Modifier = Modifier,
) {
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
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "LINK STATE",
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
