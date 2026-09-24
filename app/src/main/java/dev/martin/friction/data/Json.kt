package dev.martin.friction.data

import dev.martin.friction.engine.ActiveInterruption
import dev.martin.friction.engine.AppGroup
import dev.martin.friction.engine.Config
import dev.martin.friction.engine.GroupState
import dev.martin.friction.engine.InterruptionVariant
import dev.martin.friction.engine.ParamValue
import dev.martin.friction.engine.SessionRules
import dev.martin.friction.engine.SessionState
import dev.martin.friction.engine.Stage
import dev.martin.friction.engine.StageSequence
import dev.martin.friction.engine.TaskSequence
import dev.martin.friction.engine.TaskVariant
import org.json.JSONArray
import org.json.JSONObject

/** Hand-written JSON mapping (org.json ships with Android, so no extra libraries). */
object Json {

    // ---------- helpers ----------

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.dbl(key: String): Double? = if (isNull(key)) null else getDouble(key)

    private inline fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).map { f(getJSONObject(it)) }
    }

    private fun JSONObject.keyList(): List<String> {
        val out = mutableListOf<String>()
        val it = keys()
        while (it.hasNext()) out += it.next()
        return out
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { getString(it) }
    }

    private fun strArray(list: Collection<String>) = JSONArray().apply { list.forEach { put(it) } }

    // ---------- params ----------

    private fun paramsToJson(params: Map<String, ParamValue>) = JSONObject().apply {
        params.forEach { (k, v) ->
            put(k, when (v) {
                is ParamValue.Fixed -> JSONObject().put("fixed", v.value)
                is ParamValue.Range -> JSONObject().put("min", v.min).put("max", v.max)
            })
        }
    }

    private fun paramsFromJson(o: JSONObject?): Map<String, ParamValue> {
        if (o == null) return emptyMap()
        val out = mutableMapOf<String, ParamValue>()
        for (k in o.keyList()) {
            val p = o.getJSONObject(k)
            out[k] = if (p.has("fixed")) ParamValue.Fixed(p.getDouble("fixed"))
            else ParamValue.Range(p.getDouble("min"), p.getDouble("max"))
        }
        return out
    }

    // ---------- config ----------

    fun configToJson(c: Config): String = JSONObject().apply {
        put("version", 1)
        put("resetHour", c.resetHour)
        put("resetMinute", c.resetMinute)
        put("taskVariants", JSONArray().apply {
            c.taskVariants.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("type", it.typeId).put("params", paramsToJson(it.params)))
            }
        })
        put("taskSequences", JSONArray().apply {
            c.taskSequences.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("steps", strArray(it.steps))
                    .put("failBlockMinutes", it.failBlockMinutes))
            }
        })
        put("interruptionVariants", JSONArray().apply {
            c.interruptionVariants.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("type", it.typeId).put("params", paramsToJson(it.params)))
            }
        })
        put("stageSequences", JSONArray().apply {
            c.stageSequences.forEach { ss ->
                put(JSONObject().put("id", ss.id).put("name", ss.name).put("stages", JSONArray().apply {
                    ss.stages.forEach { st ->
                        val session = JSONObject()
                            .put("interruptions", strArray(st.session.interruptionIds))
                            .put("launcherMinSeconds", st.session.launcherMinSeconds)
                            .put("launcherMaxSeconds", st.session.launcherMaxSeconds)
                        st.session.lengthMinutes?.let { session.put("lengthMinutes", it) }
                        val so = JSONObject().put("durationMinutes", st.durationMinutes).put("session", session)
                        st.taskSequenceId?.let { so.put("taskSequence", it) }
                        put(so)
                    }
                }))
            }
        })
        put("groups", JSONArray().apply {
            c.groups.forEach { g ->
                val o = JSONObject().put("id", g.id).put("name", g.name).put("packages", strArray(g.packages.sorted()))
                g.stageSequenceId?.let { o.put("stageSequence", it) }
                put(o)
            }
        })
    }.toString(2)

    fun configFromJson(text: String): Config {
        val o = JSONObject(text)
        return Config(
            resetHour = o.optInt("resetHour", 4),
            resetMinute = o.optInt("resetMinute", 0),
            taskVariants = o.optJSONArray("taskVariants").mapObjects {
                TaskVariant(it.getString("id"), it.getString("name"), it.getString("type"), paramsFromJson(it.optJSONObject("params")))
            },
            taskSequences = o.optJSONArray("taskSequences").mapObjects {
                TaskSequence(it.getString("id"), it.getString("name"), it.optJSONArray("steps").strings(),
                    it.optDouble("failBlockMinutes", 10.0))
            },
            interruptionVariants = o.optJSONArray("interruptionVariants").mapObjects {
                InterruptionVariant(it.getString("id"), it.getString("name"), it.getString("type"), paramsFromJson(it.optJSONObject("params")))
            },
            stageSequences = o.optJSONArray("stageSequences").mapObjects { ss ->
                StageSequence(ss.getString("id"), ss.getString("name"), ss.optJSONArray("stages").mapObjects { st ->
                    val s = st.optJSONObject("session") ?: JSONObject()
                    Stage(
                        durationMinutes = st.optDouble("durationMinutes", 0.0),
                        taskSequenceId = st.str("taskSequence"),
                        session = SessionRules(
                            lengthMinutes = s.dbl("lengthMinutes"),
                            interruptionIds = s.optJSONArray("interruptions").strings(),
                            launcherMinSeconds = s.optDouble("launcherMinSeconds", 60.0),
                            launcherMaxSeconds = s.optDouble("launcherMaxSeconds", 180.0),
                        ),
                    )
                })
            },
            groups = o.optJSONArray("groups").mapObjects {
                AppGroup(it.getString("id"), it.getString("name"), it.optJSONArray("packages").strings().toSet(), it.str("stageSequence"))
            },
        )
    }

    // ---------- runtime state ----------

    fun statesToJson(states: Map<String, GroupState>): String = JSONObject().apply {
        states.forEach { (id, s) ->
            val o = JSONObject()
                .put("dayStartMs", s.dayStartMs)
                .put("blockedUntilMs", s.blockedUntilMs)
                .put("launcherRemainingMs", s.launcherRemainingMs)
                .put("debugExtraUsageMs", s.debugExtraUsageMs)
            s.session?.let {
                o.put("session", JSONObject().put("startedAtMs", it.startedAtMs).put("endsAtMs", it.endsAtMs).put("stageIndex", it.stageIndex))
            }
            s.interruption?.let { a ->
                val params = JSONObject()
                a.params.forEach { (k, v) -> params.put(k, v) }
                o.put("interruption", JSONObject().put("instance", a.instance).put("variantId", a.variantId)
                    .put("typeId", a.typeId).put("params", params).put("remainingMs", a.remainingMs))
            }
            put(id, o)
        }
    }.toString()

    fun statesFromJson(text: String): Map<String, GroupState> {
        val root = JSONObject(text)
        val out = mutableMapOf<String, GroupState>()
        for (id in root.keyList()) {
            val o = root.getJSONObject(id)
            val session = o.optJSONObject("session")?.let {
                SessionState(it.getLong("startedAtMs"), it.getLong("endsAtMs"), it.getInt("stageIndex"))
            }
            val interruption = o.optJSONObject("interruption")?.let { a ->
                val p = a.getJSONObject("params")
                val params = mutableMapOf<String, Double>()
                for (k in p.keyList()) params[k] = p.getDouble(k)
                ActiveInterruption(a.getLong("instance"), a.getString("variantId"), a.getString("typeId"), params, a.getLong("remainingMs"))
            }
            out[id] = GroupState(
                dayStartMs = o.optLong("dayStartMs", 0L),
                session = session,
                blockedUntilMs = o.optLong("blockedUntilMs", 0L),
                launcherRemainingMs = o.optLong("launcherRemainingMs", -1L),
                interruption = interruption,
                debugExtraUsageMs = o.optLong("debugExtraUsageMs", 0L),
            )
        }
        return out
    }
}
