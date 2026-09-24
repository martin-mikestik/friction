package dev.martin.friction.engine

import java.util.UUID

// ---------------------------------------------------------------------------
// Configuration (what the user defines). Everything refers to everything else by id,
// so renaming/editing a variant updates every sequence that uses it.
// ---------------------------------------------------------------------------

/** (T2) A Task Type with its variables filled in, named by the user. */
data class TaskVariant(
    val id: String,
    val name: String,
    val typeId: String,
    val params: Map<String, ParamValue>,
)

/** (T4) Ordered list of task variants. Empty = free pass. */
data class TaskSequence(
    val id: String,
    val name: String,
    val steps: List<String>,
    /** Real-time block of the whole group after failing this sequence. */
    val failBlockMinutes: Double,
)

/** (I2) An Interruption Type with its variables filled in. */
data class InterruptionVariant(
    val id: String,
    val name: String,
    val typeId: String,
    val params: Map<String, ParamValue>,
)

/**
 * (O3) Session Rules: what passing the stage's task sequence unlocks.
 * Contains the Interruption Set (I3) and the Interruption Launcher (I4) settings.
 */
data class SessionRules(
    /** Clock minutes the session lasts; null = unlimited (until stage change or daily reset). */
    val lengthMinutes: Double?,
    /** (I3) Interruption Set. Empty = no interruptions. */
    val interruptionIds: List<String> = emptyList(),
    /** (I4) Launcher: wait a random time in [min, max] seconds of in-app time, then launch one. */
    val launcherMinSeconds: Double = 60.0,
    val launcherMaxSeconds: Double = 180.0,
)

/** (O2) One stage of the day. */
data class Stage(
    /** Minutes of group usage this stage lasts. Ignored (= infinite) for the last stage. */
    val durationMinutes: Double,
    /** null or an empty sequence = the app opens freely. */
    val taskSequenceId: String?,
    val session: SessionRules,
)

/** (O1) Reusable, named, ordered list of stages. */
data class StageSequence(
    val id: String,
    val name: String,
    val stages: List<Stage>,
)

/** (A2) Named set of apps sharing one stage sequence and one usage budget. */
data class AppGroup(
    val id: String,
    val name: String,
    val packages: Set<String>,
    val stageSequenceId: String?,
)

data class Config(
    val resetHour: Int = 4,
    val resetMinute: Int = 0,
    val taskVariants: List<TaskVariant> = emptyList(),
    val taskSequences: List<TaskSequence> = emptyList(),
    val interruptionVariants: List<InterruptionVariant> = emptyList(),
    val stageSequences: List<StageSequence> = emptyList(),
    val groups: List<AppGroup> = emptyList(),
) {
    fun taskVariant(id: String?) = taskVariants.find { it.id == id }
    fun taskSequence(id: String?) = taskSequences.find { it.id == id }
    fun interruptionVariant(id: String?) = interruptionVariants.find { it.id == id }
    fun stageSequence(id: String?) = stageSequences.find { it.id == id }
    fun group(id: String?) = groups.find { it.id == id }

    /** Each app is in at most one group. */
    fun groupForPackage(pkg: String): AppGroup? = groups.find { pkg in it.packages }

    /** Human-readable list of things that reference [id]; used to refuse deletes. */
    fun usagesOf(id: String): List<String> {
        val out = mutableListOf<String>()
        taskSequences.filter { id in it.steps }.forEach { out += "Task sequence \"${it.name}\"" }
        stageSequences.forEach { ss ->
            ss.stages.forEachIndexed { i, st ->
                if (st.taskSequenceId == id) out += "Stage ${i + 1} of \"${ss.name}\""
                if (id in st.session.interruptionIds) out += "Interruptions of stage ${i + 1} of \"${ss.name}\""
            }
        }
        groups.filter { it.stageSequenceId == id }.forEach { out += "App group \"${it.name}\"" }
        return out
    }
}

fun newId(): String = UUID.randomUUID().toString().substring(0, 8)
