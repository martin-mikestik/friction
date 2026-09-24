package dev.martin.friction.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.martin.friction.engine.ParamKind
import dev.martin.friction.engine.ParamSpec
import dev.martin.friction.engine.ParamValue
import kotlin.math.roundToLong

val Muted = Color(0xFF9AA0A6)
val OkGreen = Color(0xFF7BD88F)
val BadRed = Color(0xFFFF7A7A)

/** Screen with a header row (back arrow, title, optional actions) and a scrolling body. */
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) } else Spacer(Modifier.width(12.dp))
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            actions()
        }
        HorizontalDivider()
        val body = Modifier.fillMaxSize().let { if (scroll) it.verticalScroll(rememberScrollState()) else it }
        Column(body.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
}

@Composable
fun Hint(text: String) {
    Text(text, fontSize = 13.sp, color = Muted)
}

/** Tappable card with a title and a summary line. */
@Composable
fun ItemCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 13.sp, color = Muted)
        }
    }
}

fun fmtNum(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.roundToLong().toString() else v.toString()

/** Text field for numbers. Keeps its own text so half-typed values ("1.") aren't overwritten. */
@Composable
fun NumberField(
    label: String,
    value: Double,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    unit: String = "",
    min: Double = 0.0,
    max: Double = 1_000_000.0,
) {
    var text by remember { mutableStateOf(fmtNum(value)) }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    val valid = parsed != null && parsed in min..max
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            val p = t.replace(',', '.').toDoubleOrNull()
            if (p != null && p in min..max) onChange(p)
        },
        label = { Text(if (unit.isBlank()) label else "$label ($unit)") },
        singleLine = true,
        isError = !valid,
        supportingText = { if (!valid) Text("${fmtNum(min)}–${fmtNum(max)}") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
fun TextFieldRow(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Editor for one Task/Interruption Variable: fixed value or random range. */
@Composable
fun ParamEditor(spec: ParamSpec, value: ParamValue, onChange: (ParamValue) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(spec.label, fontWeight = FontWeight.Medium)
        when (spec.kind) {
            ParamKind.CHOICE -> {
                val current = (value as? ParamValue.Fixed)?.value?.roundToLong()?.toInt() ?: 0
                spec.choices.forEachIndexed { i, c ->
                    RadioRow(c, current == i) { onChange(ParamValue.Fixed(i.toDouble())) }
                }
            }
            ParamKind.BOOLEAN -> {
                val on = ((value as? ParamValue.Fixed)?.value ?: 0.0) >= 0.5
                SwitchRow(if (on) "Yes" else "No", on) { onChange(ParamValue.Fixed(if (it) 1.0 else 0.0)) }
            }
            ParamKind.NUMBER, ParamKind.INTEGER -> {
                if (spec.randomizable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.weight(1f)) {
                            RadioRow("Fixed", value is ParamValue.Fixed) {
                                if (value is ParamValue.Range) onChange(ParamValue.Fixed(value.min))
                            }
                        }
                        Row(Modifier.weight(1f)) {
                            RadioRow("Random range", value is ParamValue.Range) {
                                if (value is ParamValue.Fixed) onChange(ParamValue.Range(value.value, value.value))
                            }
                        }
                    }
                }
                when (value) {
                    is ParamValue.Fixed -> NumberField(
                        "Value", value.value, { onChange(ParamValue.Fixed(it)) },
                        Modifier.fillMaxWidth(), spec.unit, spec.min, spec.max,
                    )
                    is ParamValue.Range -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Min", value.min, { onChange(value.copy(min = it)) }, Modifier.weight(1f), spec.unit, spec.min, spec.max)
                        NumberField("Max", value.max, { onChange(value.copy(max = it)) }, Modifier.weight(1f), spec.unit, spec.min, spec.max)
                    }
                }
            }
        }
        if (spec.help.isNotBlank()) Hint(spec.help)
    }
}

/** Simple list picker in a dialog. [options] = (value, label). */
@Composable
fun <T> PickerDialog(title: String, options: List<Pair<T, String>>, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (options.isEmpty()) Hint("Nothing to choose from yet.")
                options.forEach { (v, label) ->
                    Text(
                        label,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(v) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun MessageDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
