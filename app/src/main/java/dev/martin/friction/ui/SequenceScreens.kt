package dev.martin.friction.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.martin.friction.FrictionActivity
import dev.martin.friction.data.ConfigStore
import dev.martin.friction.engine.Config
import dev.martin.friction.engine.SessionRules
import dev.martin.friction.engine.Stage
import dev.martin.friction.engine.StageSequence
import dev.martin.friction.engine.TaskSequence
import dev.martin.friction.engine.newId
import dev.martin.friction.formatMinutes

// ------------------------------------------------------------------ task sequences (T4)

fun describeSequence(c: Config, s: TaskSequence): String {
    val names = s.steps.map { c.taskVariant(it)?.name ?: "(missing)" }
    val list = if (names.isEmpty()) "no tasks (free pass)" else names.joinToString(" → ")
    return "$list · fail = ${formatMinutes(s.failBlockMinutes)} block"
}

@Composable
fun TaskSequencesScreen(nav: Nav) {
    val config by ConfigStore.config.collectAsState()
    Page("Task sequences", onBack = nav::pop, actions = {
        TextButton(onClick = { nav.push(Screen.TaskSequenceEdit(null)) }) { Text("New") }
    }) {
        Hint("Ordered task variants. Passing all of them unlocks a session; failing any blocks the group.")
        config.taskSequences.forEach { s ->
            ItemCard(s.name, describeSequence(config, s)) { nav.push(Screen.TaskSequenceEdit(s.id)) }
        }
    }
}

@Composable
fun TaskSequenceEditScreen(nav: Nav, id: String?) {
    val context = LocalContext.current
    val config by ConfigStore.config.collectAsState()
    val existing = remember { ConfigStore.current.taskSequence(id) }
    val seqId = remember { existing?.id ?: newId() }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var steps by remember { mutableStateOf(existing?.steps ?: emptyList()) }
    var block by remember { mutableStateOf(existing?.failBlockMinutes ?: 10.0) }
    var picking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var stepKeys by remember { mutableStateOf(steps.map { newId() }) }

    fun save(): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) { message = "Give it a name."; return false }
        if (ConfigStore.current.taskSequences.any { it.name.equals(trimmed, true) && it.id != seqId }) {
            message = "Another sequence is already called \"$trimmed\"."; return false
        }
        val s = TaskSequence(seqId, trimmed, steps, block)
        ConfigStore.update("task sequence '${s.name}' saved") { c -> c.copy(taskSequences = c.taskSequences.upsert(s) { it.id }) }
        return true
    }

    Page(if (existing == null) "New task sequence" else "Edit task sequence", onBack = nav::pop) {
        TextFieldRow("Name", name) { name = it }
        NumberField("Block after failing", block, { block = it }, Modifier.fillMaxWidth(), "minutes", 0.0, 24 * 60.0)
        SectionTitle("Tasks (in order)")
        if (steps.isEmpty()) Hint("No tasks: the app opens freely.")
        steps.forEachIndexed { i, vid ->
            key(stepKeys.getOrElse(i) { i.toString() }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${config.taskVariant(vid)?.name ?: "(missing)"}", modifier = Modifier.weight(1f))
                    TextButton(onClick = { steps = steps.move(i, i - 1); stepKeys = stepKeys.move(i, i - 1) }, enabled = i > 0) { Text("↑") }
                    TextButton(onClick = { steps = steps.move(i, i + 1); stepKeys = stepKeys.move(i, i + 1) }, enabled = i < steps.lastIndex) { Text("↓") }
                    TextButton(onClick = {
                        steps = steps.filterIndexed { j, _ -> j != i }
                        stepKeys = stepKeys.filterIndexed { j, _ -> j != i }
                    }) { Text("✕") }
                }
            }
        }
        OutlinedButton(onClick = { picking = true }) { Text("Add task") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (save()) nav.pop() }) { Text("Save") }
            OutlinedButton(onClick = {
                if (save()) context.startActivity(FrictionActivity.practiceIntent(context, name.trim(), steps))
            }, enabled = steps.isNotEmpty()) { Text("Save & try") }
        }
        Hint("\"Try\" runs the sequence without consequences.")
        if (existing != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = BadRed) }
    }

    if (picking) {
        PickerDialog("Add task", config.taskVariants.map { it.id to it.name }, onPick = {
            steps = steps + it
            stepKeys = stepKeys + newId()
            picking = false
        }, onDismiss = { picking = false })
    }
    message?.let { MessageDialog("Can't save", it) { message = null } }
    if (confirmDelete) {
        val usages = ConfigStore.current.usagesOf(seqId)
        if (usages.isNotEmpty()) {
            MessageDialog("Still in use", "Remove it from these first:\n\n" + usages.joinToString("\n")) { confirmDelete = false }
        } else {
            ConfirmDialog("Delete \"$name\"?", "This can't be undone.", "Delete", onConfirm = {
                ConfigStore.update("task sequence '$name' deleted") { c -> c.copy(taskSequences = c.taskSequences.filter { it.id != seqId }) }
                confirmDelete = false
                nav.pop()
            }, onDismiss = { confirmDelete = false })
        }
    }
}

// ------------------------------------------------------------------ stage sequences (O1)

fun describeStages(c: Config, ss: StageSequence): String =
    ss.stages.mapIndexed { i, st ->
        val len = if (i == ss.stages.lastIndex) "rest of day" else "${fmtNum(st.durationMinutes)} min"
        val seq = c.taskSequence(st.taskSequenceId)?.name ?: "free"
        "$len: $seq"
    }.joinToString(" | ")

@Composable
fun StageSequencesScreen(nav: Nav) {
    val config by ConfigStore.config.collectAsState()
    Page("Stage sequences", onBack = nav::pop, actions = {
        TextButton(onClick = { nav.push(Screen.StageSequenceEdit(null)) }) { Text("New") }
    }) {
        Hint("How a day unfolds for an app group. Stages advance with the group's usage; the day restarts at the reset time.")
        config.stageSequences.forEach { ss ->
            ItemCard(ss.name, describeStages(config, ss)) { nav.push(Screen.StageSequenceEdit(ss.id)) }
        }
    }
}

@Composable
fun StageSequenceEditScreen(nav: Nav, id: String?) {
    val config by ConfigStore.config.collectAsState()
    val existing = remember { ConfigStore.current.stageSequence(id) }
    val ssId = remember { existing?.id ?: newId() }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var stages by remember {
        mutableStateOf(existing?.stages ?: listOf(Stage(60.0, null, SessionRules(lengthMinutes = null))))
    }
    var keys by remember { mutableStateOf(stages.map { newId() }) }
    var pickingSequenceFor by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun setStage(i: Int, s: Stage) {
        stages = stages.mapIndexed { j, old -> if (j == i) s else old }
    }

    fun save(): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) { message = "Give it a name."; return false }
        if (stages.isEmpty()) { message = "Add at least one stage."; return false }
        if (ConfigStore.current.stageSequences.any { it.name.equals(trimmed, true) && it.id != ssId }) {
            message = "Another stage sequence is already called \"$trimmed\"."; return false
        }
        val ss = StageSequence(ssId, trimmed, stages)
        ConfigStore.update("stage sequence '${ss.name}' saved") { c -> c.copy(stageSequences = c.stageSequences.upsert(ss) { it.id }) }
        return true
    }

    Page(if (existing == null) "New stage sequence" else "Edit stage sequence", onBack = nav::pop) {
        TextFieldRow("Name", name) { name = it }
        stages.forEachIndexed { i, st ->
            key(keys.getOrElse(i) { i.toString() }) {
                StageCard(
                    index = i,
                    isLast = i == stages.lastIndex,
                    stage = st,
                    config = config,
                    onChange = { setStage(i, it) },
                    onPickSequence = { pickingSequenceFor = i },
                    onMove = { to -> stages = stages.move(i, to); keys = keys.move(i, to) },
                    onRemove = {
                        stages = stages.filterIndexed { j, _ -> j != i }
                        keys = keys.filterIndexed { j, _ -> j != i }
                    },
                    count = stages.size,
                )
            }
        }
        OutlinedButton(onClick = {
            stages = stages + Stage(30.0, null, SessionRules(lengthMinutes = 15.0))
            keys = keys + newId()
        }) { Text("Add stage") }
        Button(onClick = { if (save()) nav.pop() }) { Text("Save") }
        if (existing != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = BadRed) }
    }

    pickingSequenceFor?.let { i ->
        val options: List<Pair<String?, String>> =
            listOf<Pair<String?, String>>(null to "None — the app opens freely") + config.taskSequences.map { it.id to it.name }
        PickerDialog("Task sequence for stage ${i + 1}", options, onPick = { sid ->
            stages.getOrNull(i)?.let { setStage(i, it.copy(taskSequenceId = sid)) }
            pickingSequenceFor = null
        }, onDismiss = { pickingSequenceFor = null })
    }
    message?.let { MessageDialog("Can't save", it) { message = null } }
    if (confirmDelete) {
        val usages = ConfigStore.current.usagesOf(ssId)
        if (usages.isNotEmpty()) {
            MessageDialog("Still in use", "Remove it from these first:\n\n" + usages.joinToString("\n")) { confirmDelete = false }
        } else {
            ConfirmDialog("Delete \"$name\"?", "This can't be undone.", "Delete", onConfirm = {
                ConfigStore.update("stage sequence '$name' deleted") { c -> c.copy(stageSequences = c.stageSequences.filter { it.id != ssId }) }
                confirmDelete = false
                nav.pop()
            }, onDismiss = { confirmDelete = false })
        }
    }
}

@Composable
private fun StageCard(
    index: Int,
    isLast: Boolean,
    stage: Stage,
    config: Config,
    onChange: (Stage) -> Unit,
    onPickSequence: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    count: Int,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Stage ${index + 1}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onMove(index - 1) }, enabled = index > 0) { Text("↑") }
                TextButton(onClick = { onMove(index + 1) }, enabled = index < count - 1) { Text("↓") }
                TextButton(onClick = onRemove, enabled = count > 1) { Text("✕") }
            }
            if (isLast) {
                Hint("Last stage: lasts until the daily reset.")
            } else {
                NumberField("Lasts for", stage.durationMinutes, { onChange(stage.copy(durationMinutes = it)) },
                    Modifier.fillMaxWidth(), "minutes of usage", 0.0, 24 * 60.0)
            }

            Text("To open the app:")
            OutlinedButton(onClick = onPickSequence) {
                Text(config.taskSequence(stage.taskSequenceId)?.name ?: "Nothing — opens freely")
            }

            Text("Unlocked session:")
            val unlimited = stage.session.lengthMinutes == null
            SwitchRow("Unlimited (until the stage ends)", unlimited) { on ->
                onChange(stage.copy(session = stage.session.copy(lengthMinutes = if (on) null else 15.0)))
            }
            if (!unlimited) {
                NumberField("Session length", stage.session.lengthMinutes ?: 15.0,
                    { onChange(stage.copy(session = stage.session.copy(lengthMinutes = it))) },
                    Modifier.fillMaxWidth(), "clock minutes", 0.1, 24 * 60.0)
            }

            Text("Interruptions during the session:")
            if (config.interruptionVariants.isEmpty()) Hint("No interruption variants defined yet.")
            config.interruptionVariants.forEach { iv ->
                val checked = iv.id in stage.session.interruptionIds
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { on ->
                        val ids = if (on) stage.session.interruptionIds + iv.id else stage.session.interruptionIds - iv.id
                        onChange(stage.copy(session = stage.session.copy(interruptionIds = ids)))
                    })
                    Text(iv.name)
                }
            }
            if (stage.session.interruptionIds.isNotEmpty()) {
                Hint("Wait a random time between these (in-app seconds) before each interruption:")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Min", stage.session.launcherMinSeconds,
                        { onChange(stage.copy(session = stage.session.copy(launcherMinSeconds = it))) },
                        Modifier.weight(1f), "s", 1.0, 24 * 3600.0)
                    NumberField("Max", stage.session.launcherMaxSeconds,
                        { onChange(stage.copy(session = stage.session.copy(launcherMaxSeconds = it))) },
                        Modifier.weight(1f), "s", 1.0, 24 * 3600.0)
                }
            }
        }
    }
}
