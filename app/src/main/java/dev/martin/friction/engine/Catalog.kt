package dev.martin.friction.engine

/** A Task Type (T1) or Interruption Type (I1): what it is and which variables it has. */
data class TypeDef(
    val id: String,
    val name: String,
    val description: String,
    val params: List<ParamSpec>,
)

private fun fixed(v: Double) = ParamValue.Fixed(v)

object TaskTypes {
    const val WAIT = "wait"
    const val DICE = "dice"
    const val SHAKE = "shake"
    const val TILT = "tilt"
    const val TYPING = "typing"

    // variable keys
    const val SECONDS = "seconds"
    const val CHANCE = "chance"
    const val INTENSITY = "intensity"
    const val TOLERANCE = "tolerance"
    const val HOLD = "hold"
    const val DEADLINE = "deadline"
    const val OUTSIDE_GRACE = "outsideGrace"
    const val MAX_ANGLE = "maxAngle"
    const val LENGTH = "length"
    const val CHARSET = "charset"
    const val CASE_SENSITIVE = "caseSensitive"

    val CHARSETS = listOf("Letters", "Digits", "Letters + digits", "Look-alikes (l1I|O0o…)")

    val all: List<TypeDef> = listOf(
        TypeDef(
            WAIT, "Wait", "Wait n seconds. Nothing to do but wait.",
            listOf(ParamSpec(SECONDS, "Wait", ParamKind.NUMBER, fixed(30.0), 1.0, 3600.0, "s")),
        ),
        TypeDef(
            DICE, "Fate", "A roll of fate with a hidden chance of success.",
            listOf(ParamSpec(CHANCE, "Chance of success", ParamKind.NUMBER, fixed(50.0), 0.0, 100.0, "%")),
        ),
        TypeDef(
            SHAKE, "Shake", "Shake the phone and keep the intensity inside the target band.",
            listOf(
                ParamSpec(INTENSITY, "Target intensity", ParamKind.NUMBER, fixed(15.0), 2.0, 40.0, "m/s²",
                    help = "Gravity removed. ~8 gentle, ~15 firm, ~25 hard."),
                ParamSpec(TOLERANCE, "Allowed deviation (±)", ParamKind.NUMBER, fixed(6.0), 0.5, 30.0, "m/s²"),
                ParamSpec(HOLD, "Keep it up for", ParamKind.NUMBER, fixed(5.0), 0.5, 120.0, "s"),
                ParamSpec(DEADLINE, "Deadline to reach the band", ParamKind.NUMBER, fixed(10.0), 1.0, 120.0, "s"),
                ParamSpec(OUTSIDE_GRACE, "May stray outside the band for", ParamKind.NUMBER, fixed(0.5), 0.0, 10.0, "s",
                    help = "Longer than this outside the band = fail."),
            ),
        ),
        TypeDef(
            TILT, "Tilt", "Tilt the phone to a random angle and hold it there before the deadline.",
            listOf(
                ParamSpec(MAX_ANGLE, "Max target angle", ParamKind.NUMBER, fixed(40.0), 5.0, 80.0, "°",
                    help = "Target is picked randomly within ± this angle on both axes."),
                ParamSpec(TOLERANCE, "Tolerance", ParamKind.NUMBER, fixed(5.0), 1.0, 30.0, "°"),
                ParamSpec(HOLD, "Hold for", ParamKind.NUMBER, fixed(2.0), 0.2, 30.0, "s"),
                ParamSpec(DEADLINE, "Deadline (reach + hold)", ParamKind.NUMBER, fixed(10.0), 1.0, 120.0, "s"),
            ),
        ),
        TypeDef(
            TYPING, "Type", "Retype a random string exactly.",
            listOf(
                ParamSpec(LENGTH, "Length", ParamKind.INTEGER, fixed(12.0), 1.0, 200.0, "chars"),
                ParamSpec(CHARSET, "Characters", ParamKind.CHOICE, fixed(2.0), 0.0, (CHARSETS.size - 1).toDouble(),
                    randomizable = false, choices = CHARSETS),
                ParamSpec(CASE_SENSITIVE, "Case sensitive", ParamKind.BOOLEAN, fixed(1.0), 0.0, 1.0, randomizable = false),
                ParamSpec(DEADLINE, "Deadline", ParamKind.NUMBER, fixed(45.0), 0.0, 3600.0, "s", help = "0 = no deadline"),
            ),
        ),
    )

    fun byId(id: String): TypeDef? = all.find { it.id == id }
}

object InterruptionTypes {
    const val CLOUDS = "clouds"

    const val DENSITY = "density"
    const val DURATION = "duration"

    val all: List<TypeDef> = listOf(
        TypeDef(
            CLOUDS, "Black clouds", "Dark clouds drift over the app for a while. The duration is never shown.",
            listOf(
                ParamSpec(DENSITY, "Density", ParamKind.INTEGER, fixed(6.0), 1.0, 10.0, "/10"),
                ParamSpec(DURATION, "Duration", ParamKind.NUMBER, ParamValue.Range(7.0, 30.0), 1.0, 600.0, "s",
                    help = "Use a range to make it unpredictable."),
            ),
        ),
    )

    fun byId(id: String): TypeDef? = all.find { it.id == id }
}
