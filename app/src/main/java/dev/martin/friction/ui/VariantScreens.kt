package dev.martin.friction.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.martin.friction.FrictionAccessibilityService
import dev.martin.friction.FrictionActivity
import dev.martin.friction.data.ConfigStore
import dev.martin.friction.engine.ActiveInterruption
import dev.martin.friction.engine.InterruptionTypes
import dev.martin.friction.engine.InterruptionVariant
import dev.martin.friction.engine.ParamValue
import dev.martin.friction.engine.TaskTypes
import dev.martin.friction.engine.TaskVariant
import dev.martin.friction.engine.TypeDef
import dev.martin.friction.engine.describe
import dev.martin.friction.engine.newId
import dev.martin.friction.engine.resolveAll
import kotlin.random.Random

fun summarize(type: TypeDef?, params: Map<String, ParamValue>): String {
    if (type == null) return "unknown type"
    return type.name + " · " + type.params.joinToString(", ") { spec ->
        "${spec.label.lowercase()}: ${(params[spec.key] ?: spec.default).describe(spec)}"
    }
}

// ------------------------------------------------------------------ task variants (T2)

@Composable
fun TaskVariantsScreen(nav: Nav) {
    val config by ConfigStore.config.collectAsState()
    var picking by remember { mutableStateOf(false) }
    Page("Task variants", onBack = nav::pop, actions = { TextButton(onClick = { picking = true }) { Text("New") } }) {
        Hint("A task variant is a task type with its variables filled in. Sequences are built from these.")
        config.taskVariants.forEach { v ->
            ItemCard(v.name, summarize(TaskTypes.byId(v.typeId), v.params)) { nav.push(Screen.TaskVariantEdit(v.id, v.typeId)) }
        }
    }
    if (picking) {
        PickerDialog(
            "Task type",
            TaskTypes.all.map { it.id to "${it.name} — ${it.description}" },
            onPick = { picking = false; nav.push(Screen.TaskVariantEdit(null, it)) },
            onDismiss = { picking = false },
        )
    }
}

@Composable
fun TaskVariantEditScreen(nav: Nav, id: String?, typeId: String) {
    val context = LocalContext.current
    val existing = remember { ConfigStore.current.taskVariant(id) }
    val type = TaskTypes.byId(existing?.typeId ?: typeId)
    var name by remember { mutableStateOf(existing?.name ?: (type?.name ?: "Task")) }
    var params by remember { mutableStateOf(existing?.params ?: type?.params?.associate { it.key to it.default }.orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val variantId = remember { existing?.id ?: newId() }

    fun save(): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) { message = "Give it a name."; return false }
        if (ConfigStore.current.taskVariants.any { it.name.equals(trimmed, true) && it.id != variantId }) {
            message = "Another task variant is already called \"$trimmed\"."; return false
        }
        val v = TaskVariant(variantId, trimmed, type?.id ?: typeId, params)
        ConfigStore.update("task variant '${v.name}' saved") { c -> c.copy(taskVariants = c.taskVariants.upsert(v) { it.id }) }
        return true
    }

    Page(if (existing == null) "New ${type?.name ?: ""} task" else "Edit task", onBack = nav::pop) {
        Hint(type?.description ?: "Unknown task type")
        TextFieldRow("Name", name) { name = it }
        type?.params?.forEach { spec ->
            ParamEditor(spec, params[spec.key] ?: spec.default) { params = params + (spec.key to it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (save()) nav.pop() }) { Text("Save") }
            OutlinedButton(onClick = {
                if (save()) context.startActivity(FrictionActivity.practiceIntent(context, name.trim(), listOf(variantId)))
            }) { Text("Save & try") }
        }
        if (existing != null) {
            TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = BadRed) }
        }
    }

    message?.let { MessageDialog("Can't save", it) { message = null } }
    if (confirmDelete) {
        val usages = ConfigStore.current.usagesOf(variantId)
        if (usages.isNotEmpty()) {
            MessageDialog("Still in use", "Remove it from these first:\n\n" + usages.joinToString("\n")) { confirmDelete = false }
        } else {
            ConfirmDialog("Delete \"$name\"?", "This can't be undone.", "Delete", onConfirm = {
                ConfigStore.update("task variant '$name' deleted") { c -> c.copy(taskVariants = c.taskVariants.filter { it.id != variantId }) }
                confirmDelete = false
                nav.pop()
            }, onDismiss = { confirmDelete = false })
        }
    }
}

// ------------------------------------------------------------------ interruption variants (I2)

@Composable
fun InterruptionVariantsScreen(nav: Nav) {
    val config by ConfigStore.config.collectAsState()
    var picking by remember { mutableStateOf(false) }
    Page("Interruption variants", onBack = nav::pop, actions = { TextButton(onClick = { picking = true }) { Text("New") } }) {
        Hint("Interruptions disturb an unlocked session. Stages pick which ones can appear.")
        config.interruptionVariants.forEach { v ->
            ItemCard(v.name, summarize(InterruptionTypes.byId(v.typeId), v.params)) {
                nav.push(Screen.InterruptionVariantEdit(v.id, v.typeId))
            }
        }
    }
    if (picking) {
        PickerDialog(
            "Interruption type",
            InterruptionTypes.all.map { it.id to "${it.name} — ${it.description}" },
            onPick = { picking = false; nav.push(Screen.InterruptionVariantEdit(null, it)) },
            onDismiss = { picking = false },
        )
    }
}

@Composable
fun InterruptionVariantEditScreen(nav: Nav, id: String?, typeId: String) {
    val context = LocalContext.current
    val existing = remember { ConfigStore.current.interruptionVariant(id) }
    val type = InterruptionTypes.byId(existing?.typeId ?: typeId)
    var name by remember { mutableStateOf(existing?.name ?: (type?.name ?: "Interruption")) }
    var params by remember { mutableStateOf(existing?.params ?: type?.params?.associate { it.key to it.default }.orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val variantId = remember { existing?.id ?: newId() }

    fun save(): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) { message = "Give it a name."; return false }
        if (ConfigStore.current.interruptionVariants.any { it.name.equals(trimmed, true) && it.id != variantId }) {
            message = "Another interruption is already called \"$trimmed\"."; return false
        }
        val v = InterruptionVariant(variantId, trimmed, type?.id ?: typeId, params)
        ConfigStore.update("interruption '${v.name}' saved") { c ->
            c.copy(interruptionVariants = c.interruptionVariants.upsert(v) { it.id })
        }
        return true
    }

    Page(if (existing == null) "New interruption" else "Edit interruption", onBack = nav::pop) {
        Hint(type?.description ?: "Unknown interruption type")
        TextFieldRow("Name", name) { name = it }
        type?.params?.forEach { spec ->
            ParamEditor(spec, params[spec.key] ?: spec.default) { params = params + (spec.key to it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (save()) nav.pop() }) { Text("Save") }
            OutlinedButton(onClick = {
                if (save() && type != null) {
                    val service = FrictionAccessibilityService.instance
                    if (service == null) {
                        Toast.makeText(context, "Turn on the accessibility service first", Toast.LENGTH_LONG).show()
                    } else {
                        val resolved = resolveAll(type.params, params, Random.Default)
                        val ms = ((resolved[InterruptionTypes.DURATION] ?: 10.0) * 1000).toLong()
                        service.preview(ActiveInterruption(Random.nextLong(), variantId, type.id, resolved, ms))
                    }
                }
            }) { Text("Save & preview") }
        }
        if (existing != null) {
            TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = BadRed) }
        }
    }

    message?.let { MessageDialog("Can't save", it) { message = null } }
    if (confirmDelete) {
        val usages = ConfigStore.current.usagesOf(variantId)
        if (usages.isNotEmpty()) {
            MessageDialog("Still in use", "Remove it from these first:\n\n" + usages.joinToString("\n")) { confirmDelete = false }
        } else {
            ConfirmDialog("Delete \"$name\"?", "This can't be undone.", "Delete", onConfirm = {
                ConfigStore.update("interruption '$name' deleted") { c ->
                    c.copy(interruptionVariants = c.interruptionVariants.filter { it.id != variantId })
                }
                confirmDelete = false
                nav.pop()
            }, onDismiss = { confirmDelete = false })
        }
    }
}
