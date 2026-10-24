package com.bitperfect.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.bitperfect.relay.R
import kotlin.math.roundToInt

/**
 * Visualizer settings (user, 2026-10-24): color preset, the user's own colors (hue, saturation,
 * brightness per slot), VU needle response time, and the view's sync offset.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun VizSettingsDialog(
    presetNames: List<String>,
    presetIdx: Int,
    custom: List<Color>,
    tuning: VizTuning,
    onPreset: (Int) -> Unit,
    onCustom: (List<Color>) -> Unit,
    onTuning: (VizTuning) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val slotNames = context.resources.getStringArray(R.array.viz_color_slots)
    var colors by remember { mutableStateOf(custom) }
    var slot by remember { mutableStateOf(1) }
    val hsv = remember(slot, colors) { FloatArray(3).also { android.graphics.Color.colorToHSV(colors[slot].toArgb(), it) } }
    fun setHsv(h: Float = hsv[0], s: Float = hsv[1], v: Float = hsv[2]) {
        val c = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
        colors = colors.toMutableList().also { it[slot] = c }
        onCustom(colors)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.ui_viz_settings)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(context.getString(R.string.ui_viz_preset), style = MaterialTheme.typography.titleSmall)
                presetNames.forEachIndexed { i, name ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = i == presetIdx, role = Role.RadioButton) { onPreset(i) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = i == presetIdx, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(name)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(context.getString(R.string.ui_viz_custom_edit), style = MaterialTheme.typography.titleSmall)
                Text(context.getString(R.string.ui_viz_custom_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    slotNames.forEachIndexed { i, name ->
                        FilterChip(
                            selected = i == slot,
                            onClick = { slot = i },
                            label = { Text(name) },
                            leadingIcon = {
                                Box(
                                    Modifier
                                        .size(14.dp)
                                        .background(colors[i], CircleShape)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                                )
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                LabeledSlider(
                    label = context.getString(R.string.ui_viz_hue, hsv[0].roundToInt()),
                    value = hsv[0], range = 0f..360f,
                ) { setHsv(h = it) }
                LabeledSlider(
                    label = context.getString(R.string.ui_viz_saturation, (hsv[1] * 100).roundToInt()),
                    value = hsv[1], range = 0f..1f,
                ) { setHsv(s = it) }
                LabeledSlider(
                    label = context.getString(R.string.ui_viz_brightness, (hsv[2] * 100).roundToInt()),
                    value = hsv[2], range = 0f..1f,
                ) { setHsv(v = it) }
                Spacer(Modifier.height(12.dp))
                LabeledSlider(
                    label = context.getString(R.string.ui_viz_needle, tuning.needleSec),
                    value = tuning.needleSec, range = 0.2f..1.5f, steps = 12,
                ) { onTuning(tuning.copy(needleSec = (it * 10).roundToInt() / 10f)) }
                Text(context.getString(R.string.ui_viz_needle_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                LabeledSlider(
                    label = context.getString(R.string.ui_viz_sync, tuning.syncOffsetMs),
                    value = tuning.syncOffsetMs.toFloat(), range = -500f..800f, steps = 25,
                ) { onTuning(tuning.copy(syncOffsetMs = (it / 50).roundToInt() * 50)) }
                Text(context.getString(R.string.ui_viz_sync_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(context.getString(R.string.ui_close))
            }
        },
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    contentDescription = label
                    stateDescription = label
                },
        )
    }
}
