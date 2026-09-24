package dev.martin.friction.engine

import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Task Variables (T3) and Interruption Variables.
 *
 * Every variable is stored as a number (choices are an index, booleans are 0/1), which lets
 * every variable be either a fixed value or a random range that is rolled anew each time
 * the task / interruption starts.
 */
enum class ParamKind { NUMBER, INTEGER, CHOICE, BOOLEAN }

data class ParamSpec(
    val key: String,
    val label: String,
    val kind: ParamKind,
    val default: ParamValue,
    val min: Double = 0.0,
    val max: Double = 1_000_000.0,
    val unit: String = "",
    /** Whether the editor offers "random range" for this variable. */
    val randomizable: Boolean = true,
    /** Labels for CHOICE params; the stored value is the index. */
    val choices: List<String> = emptyList(),
    val help: String = "",
)

sealed class ParamValue {
    data class Fixed(val value: Double) : ParamValue()
    data class Range(val min: Double, val max: Double) : ParamValue()
}

/** Resolved variables for one attempt: key -> concrete number. */
typealias Resolved = Map<String, Double>

fun ParamValue.resolve(spec: ParamSpec, rng: Random): Double {
    val raw = when (this) {
        is ParamValue.Fixed -> value
        is ParamValue.Range -> {
            val lo = minOf(min, max)
            val hi = maxOf(min, max)
            when {
                hi == lo -> lo
                spec.kind == ParamKind.NUMBER -> lo + rng.nextDouble() * (hi - lo)
                // integer-like kinds: inclusive integer range
                else -> {
                    val a = lo.roundToLong()
                    val b = hi.roundToLong()
                    (a + rng.nextLong(b - a + 1)).toDouble()
                }
            }
        }
    }
    val clamped = raw.coerceIn(spec.min, spec.max)
    return if (spec.kind == ParamKind.NUMBER) clamped else clamped.roundToLong().toDouble()
}

fun resolveAll(specs: List<ParamSpec>, values: Map<String, ParamValue>, rng: Random): Resolved =
    specs.associate { spec -> spec.key to (values[spec.key] ?: spec.default).resolve(spec, rng) }

fun ParamValue.describe(spec: ParamSpec): String {
    fun fmt(v: Double): String = when (spec.kind) {
        ParamKind.CHOICE -> spec.choices.getOrElse(v.roundToLong().toInt()) { "?" }
        ParamKind.BOOLEAN -> if (v >= 0.5) "yes" else "no"
        ParamKind.INTEGER -> v.roundToLong().toString()
        ParamKind.NUMBER -> if (v == Math.floor(v)) v.roundToLong().toString() else "%.1f".format(v)
    }
    val u = if (spec.unit.isBlank() || spec.kind == ParamKind.CHOICE || spec.kind == ParamKind.BOOLEAN) "" else " ${spec.unit}"
    return when (this) {
        is ParamValue.Fixed -> fmt(value) + u
        is ParamValue.Range -> "${fmt(min)}–${fmt(max)}$u (random)"
    }
}
