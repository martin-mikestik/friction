package dev.martin.friction.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.martin.friction.FLog
import dev.martin.friction.data.ConfigStore
import dev.martin.friction.engine.AppGroup
import dev.martin.friction.engine.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap?)

fun loadLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val result = pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != context.packageName }
        .map { info ->
            val icon = try {
                pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap()
            } catch (e: Exception) {
                null
            }
            AppEntry(info.packageName, pm.getApplicationLabel(info).toString(), icon)
        }
        .sortedBy { it.label.lowercase() }
    FLog.d("Main", "loaded ${result.size} launchable apps")
    return result
}

@Composable
fun GroupsScreen(nav: Nav) {
    val config by ConfigStore.config.collectAsState()
    Page("App groups", onBack = nav::pop, actions = {
        TextButton(onClick = { nav.push(Screen.GroupEdit(null)) }) { Text("New") }
    }) {
        Hint("Apps in a group share one usage budget and one stage sequence. An app can be in only one group.")
        config.groups.forEach { g ->
            val ss = config.stageSequence(g.stageSequenceId)?.name ?: "no stage sequence"
            ItemCard(g.name, "${g.packages.size} app(s) · $ss") { nav.push(Screen.GroupEdit(g.id)) }
        }
    }
}

@Composable
fun GroupEditScreen(nav: Nav, id: String?) {
    val context = LocalContext.current
    val config by ConfigStore.config.collectAsState()
    val existing = remember { ConfigStore.current.group(id) }
    val groupId = remember { existing?.id ?: newId() }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var packages by remember { mutableStateOf(existing?.packages ?: emptySet()) }
    var stageSeqId by remember { mutableStateOf(existing?.stageSequenceId ?: config.stageSequences.firstOrNull()?.id) }
    var query by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val apps by produceState<List<AppEntry>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }
    // package -> name of the *other* group that owns it
    val takenBy = remember(config) {
        config.groups.filter { it.id != groupId }.flatMap { g -> g.packages.map { it to g.name } }.toMap()
    }

    fun save(): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) { message = "Give it a name."; return false }
        if (ConfigStore.current.groups.any { it.name.equals(trimmed, true) && it.id != groupId }) {
            message = "Another group is already called \"$trimmed\"."; return false
        }
        val clash = packages.filter { it in takenBy }
        if (clash.isNotEmpty()) { message = "Already in another group: ${clash.joinToString()}"; return false }
        val g = AppGroup(groupId, trimmed, packages, stageSeqId)
        ConfigStore.update("group '${g.name}' saved (${g.packages.size} apps)") { c -> c.copy(groups = c.groups.upsert(g) { it.id }) }
        return true
    }

    Page(if (existing == null) "New app group" else "Edit app group", onBack = nav::pop, scroll = false) {
        TextFieldRow("Name", name) { name = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Stage sequence: ", modifier = Modifier.padding(end = 8.dp))
            OutlinedButton(onClick = { picking = true }) { Text(config.stageSequence(stageSeqId)?.name ?: "None") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { if (save()) nav.pop() }) { Text("Save") }
            if (existing != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = BadRed) }
            Text("${packages.size} app(s) selected", color = Muted, fontSize = 13.sp)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search apps") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val list = apps
        if (list == null) {
            Hint("Loading apps…")
        } else {
            val shown = list
                .filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                .sortedByDescending { it.pkg in packages }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(shown, key = { it.pkg }) { app ->
                    val owner = takenBy[app.pkg]
                    val checked = app.pkg in packages
                    val toggle: () -> Unit = {
                        if (owner == null) packages = if (checked) packages - app.pkg else packages + app.pkg
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = owner == null, onClick = toggle).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val icon = app.icon
                        if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(36.dp))
                        else Spacer(Modifier.size(36.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label)
                            Text(if (owner != null) "in group \"$owner\"" else app.pkg, fontSize = 11.sp, color = Muted)
                        }
                        Checkbox(checked = checked, onCheckedChange = { toggle() }, enabled = owner == null)
                    }
                }
            }
        }
    }

    if (picking) {
        PickerDialog("Stage sequence", config.stageSequences.map { it.id to it.name }, onPick = {
            stageSeqId = it
            picking = false
        }, onDismiss = { picking = false })
    }
    message?.let { MessageDialog("Can't save", it) { message = null } }
    if (confirmDelete) {
        ConfirmDialog("Delete \"$name\"?", "Its apps will no longer be restricted.", "Delete", onConfirm = {
            ConfigStore.update("group '$name' deleted") { c -> c.copy(groups = c.groups.filter { it.id != groupId }) }
            confirmDelete = false
            nav.pop()
        }, onDismiss = { confirmDelete = false })
    }
}
