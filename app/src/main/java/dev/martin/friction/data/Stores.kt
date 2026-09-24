package dev.martin.friction.data

import android.content.Context
import dev.martin.friction.FLog
import dev.martin.friction.engine.Config
import dev.martin.friction.engine.Defaults
import dev.martin.friction.engine.GroupState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

private fun writeAtomically(file: File, text: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
        file.writeText(text)
        tmp.delete()
    }
}

/** The user's configuration, kept in memory and saved to config.json on every change. */
object ConfigStore {
    private lateinit var file: File
    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config
    val current: Config get() = _config.value

    fun init(context: Context) {
        file = File(context.filesDir, "config.json")
        _config.value = if (file.exists()) {
            try {
                Json.configFromJson(file.readText())
            } catch (e: Exception) {
                FLog.e("Config", "config.json unreadable, backing it up and using defaults", e)
                file.copyTo(File(context.filesDir, "config.broken.${System.currentTimeMillis()}.json"), overwrite = true)
                Defaults.config()
            }
        } else {
            FLog.i("Config", "first launch: creating example configuration")
            Defaults.config().also { save(it) }
        }
    }

    fun update(reason: String, transform: (Config) -> Config) {
        val next = transform(_config.value)
        _config.value = next
        save(next)
        FLog.i("Config", "changed: $reason")
    }

    /** Replace everything (import). Throws if the JSON is invalid. */
    fun import(text: String) {
        val parsed = Json.configFromJson(text)
        update("imported configuration") { parsed }
    }

    fun export(): String = Json.configToJson(_config.value)

    private fun save(c: Config) {
        try {
            writeAtomically(file, Json.configToJson(c))
        } catch (e: Exception) {
            FLog.e("Config", "saving config failed", e)
        }
    }
}

/** Runtime state of all groups (sessions, blocks, interruptions), saved to state.json. */
object StateStore {
    private lateinit var file: File
    private val states = mutableMapOf<String, GroupState>()

    fun init(context: Context) {
        file = File(context.filesDir, "state.json")
        if (file.exists()) {
            try {
                states.putAll(Json.statesFromJson(file.readText()))
            } catch (e: Exception) {
                FLog.e("State", "state.json unreadable, starting fresh", e)
            }
        }
        FLog.i("State", "loaded state for ${states.size} group(s)")
    }

    @Synchronized
    fun get(groupId: String): GroupState = states[groupId] ?: GroupState()

    @Synchronized
    fun put(groupId: String, state: GroupState, persist: Boolean) {
        states[groupId] = state
        if (persist) save()
    }

    @Synchronized
    fun save() {
        try {
            writeAtomically(file, Json.statesToJson(states))
        } catch (e: Exception) {
            FLog.e("State", "saving state failed", e)
        }
    }
}

/**
 * Structured events (one JSON object per line) for later analysis:
 * attempts, results, walk-aways, blocks, kicks, service on/off, debug actions.
 */
object StatsLog {
    private lateinit var file: File

    fun init(context: Context) {
        file = File(context.filesDir, "stats.jsonl")
    }

    @Synchronized
    fun event(type: String, vararg fields: Pair<String, Any?>) {
        val o = JSONObject().put("t", System.currentTimeMillis()).put("type", type)
        fields.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        FLog.i("Stats", o.toString())
        try {
            file.appendText(o.toString() + "\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun readAll(): String = try {
        if (file.exists()) file.readText() else ""
    } catch (e: Exception) {
        ""
    }
}
