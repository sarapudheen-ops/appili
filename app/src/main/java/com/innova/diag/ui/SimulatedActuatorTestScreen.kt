package com.innova.diag.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.innova.diag.core.transport.TransportState
import com.innova.diag.domain.active.ActiveTestOutcome
import com.innova.diag.domain.active.ActiveTestState
import com.innova.diag.domain.obd.DiagnosticResult

/**
 * The Simulated Actuator Test screen — the only place in the app that takes control of anything.
 *
 * Three safety properties are visible rather than implied:
 *
 * 1. a banner that says what this is (`ActiveTestPresentation.BANNER`), in the error palette, above
 *    everything else;
 * 2. a **press-and-hold** control with no latching variant — letting go ends the test, by construction,
 *    because the gesture's coroutine is the dead-man switch;
 * 3. a live countdown to the hard cut-off, so the operator can see the test ending before it ends.
 *
 * A physical link cannot reach this screen: [com.innova.diag.domain.active.ActiveTestController]
 * refuses to be constructed over one.
 */
@Composable
fun SimulatedActuatorTestScreen(
    state: ActiveTestUiState,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onDropLink: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { SimulatorBanner() }

        item {
            Text(
                text = ActiveTestPresentation.SUBTITLE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { StatusCard(state) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HoldControl(
                    state = state,
                    onHoldStart = onHoldStart,
                    onHoldEnd = onHoldEnd,
                )
                Text(
                    text = ActiveTestPresentation.HOLD_INSTRUCTION,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val countdown = state.countdown
                if (countdown != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = ActiveTestPresentation.countdownText(
                                (state.test as ActiveTestState.Running).remainingMillis,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = countdown,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                } else {
                    Text(
                        text = ActiveTestPresentation.CEILING_LABEL,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDropLink, enabled = state.connected) {
                    Text("Simulate link drop")
                }
                OutlinedButton(onClick = onReconnect, enabled = !state.connected) {
                    Text("Reconnect")
                }
            }
        }
    }
}

@Composable
private fun SimulatorBanner(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = ActiveTestPresentation.BANNER,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "This screen can only ever drive the in-process mock ECU.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun StatusCard(state: ActiveTestUiState, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = state.command.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = state.statusLine,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.running) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = "Bench link: ${transportLabel(state.transportState)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The press-and-hold control.
 *
 * `detectTapGestures(onPress = …)` with `tryAwaitRelease()` is the whole dead-man implementation on the
 * UI side: [onHoldStart] begins the test, and [onHoldEnd] runs on release, on cancellation, or when the
 * finger slides off — whichever comes first. There is deliberately no click handler, so a stray tap
 * cannot latch anything.
 */
@Composable
private fun HoldControl(
    state: ActiveTestUiState,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background: Color = when {
        state.running -> MaterialTheme.colorScheme.error
        state.canHold -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground: Color = when {
        state.running -> MaterialTheme.colorScheme.onError
        state.canHold -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .pointerInput(state.canHold) {
                detectTapGestures(
                    onPress = {
                        if (state.canHold) {
                            onHoldStart()
                            tryAwaitRelease()
                            onHoldEnd()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (state.running) {
                "HOLDING — release to stop"
            } else {
                "HOLD TO RUN\n${state.command.label}"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = foreground,
        )
    }
}

private fun transportLabel(state: TransportState): String = when (state) {
    TransportState.CONNECTED -> "connected (mock)"
    TransportState.CONNECTING -> "connecting"
    TransportState.DISCONNECTED -> "disconnected"
    is TransportState.ERROR -> "error (${state.throwable::class.java.simpleName})"
}

@Preview(showBackground = true, name = "Simulated actuator test · idle")
@Composable
private fun SimulatedActuatorTestIdlePreview() {
    AppTheme {
        SimulatedActuatorTestScreen(
            state = ActiveTestUiState(transportState = TransportState.CONNECTED),
            onHoldStart = {},
            onHoldEnd = {},
            onDropLink = {},
            onReconnect = {},
        )
    }
}

@Preview(showBackground = true, name = "Simulated actuator test · holding")
@Composable
private fun SimulatedActuatorTestRunningPreview() {
    AppTheme {
        SimulatedActuatorTestScreen(
            state = ActiveTestUiState(
                transportState = TransportState.CONNECTED,
                test = ActiveTestState.Running(
                    command = com.innova.diag.domain.active.ActiveTestCommand.RADIATOR_FAN_RELAY,
                    remainingMillis = 6_400L,
                    durationMillis = 10_000L,
                ),
            ),
            onHoldStart = {},
            onHoldEnd = {},
            onDropLink = {},
            onReconnect = {},
        )
    }
}

@Preview(showBackground = true, name = "Simulated actuator test · link lost")
@Composable
private fun SimulatedActuatorTestAbortedPreview() {
    AppTheme {
        SimulatedActuatorTestScreen(
            state = ActiveTestUiState(
                transportState = TransportState.DISCONNECTED,
                test = ActiveTestState.Finished(
                    command = com.innova.diag.domain.active.ActiveTestCommand.RADIATOR_FAN_RELAY,
                    outcome = ActiveTestOutcome.ABORTED_ON_DISCONNECT,
                    returnControl = DiagnosticResult.NoResponse,
                ),
            ),
            onHoldStart = {},
            onHoldEnd = {},
            onDropLink = {},
            onReconnect = {},
        )
    }
}
