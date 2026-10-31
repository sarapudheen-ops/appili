package com.innova.diag.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Amber for "there is no value here".
 *
 * Deliberately not the theme's error red: a missing PID is not a fault and must not look like one.
 * Grey ([MaterialTheme.colorScheme.onSurfaceVariant]) is the other tone, used for "not supported"
 * and "not read yet" — states that are not even worth a warning.
 */
val WarningAmber: Color = Color(0xFFB25E00)

/**
 * Draws one [ReadingView].
 *
 * Colour is derived from the *claim*, never from the value: `0 km/h` is drawn as a normal value
 * because the vehicle really said zero, while `No Response` is amber and `Not Supported` is grey.
 */
@Composable
fun ReadingText(
    view: ReadingView,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    showDetail: Boolean = true,
) {
    val color = when (view.tone) {
        ReadingTone.VALUE -> MaterialTheme.colorScheme.onSurface
        ReadingTone.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
        ReadingTone.WARNING -> WarningAmber
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = view.text, style = style, color = color)
        val detail = view.detail
        if (showDetail && detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.85f),
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** A labelled reading: title on the left, value on the right. */
@Composable
fun ReadingRow(
    label: String,
    view: ReadingView,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        ReadingText(
            view = view,
            modifier = Modifier.weight(1.2f),
            style = style,
        )
    }
}
