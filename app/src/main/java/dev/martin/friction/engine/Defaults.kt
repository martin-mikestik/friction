package dev.martin.friction.engine

/** Example configuration created on first launch. Everything here is editable. */
object Defaults {
    fun config(): Config {
        fun f(v: Double) = ParamValue.Fixed(v)

        val wait30 = TaskVariant("v-wait30", "Wait 30 s", TaskTypes.WAIT, mapOf(TaskTypes.SECONDS to f(30.0)))
        val wait60 = TaskVariant("v-wait60", "Wait 60 s", TaskTypes.WAIT, mapOf(TaskTypes.SECONDS to f(60.0)))
        val coin = TaskVariant("v-coin", "Coin flip", TaskTypes.DICE, mapOf(TaskTypes.CHANCE to f(50.0)))
        val grim = TaskVariant("v-grim", "Grim fate", TaskTypes.DICE, mapOf(TaskTypes.CHANCE to f(30.0)))
        val type12 = TaskVariant(
            "v-type12", "Type 12", TaskTypes.TYPING,
            mapOf(TaskTypes.LENGTH to f(12.0), TaskTypes.CHARSET to f(2.0), TaskTypes.CASE_SENSITIVE to f(1.0), TaskTypes.DEADLINE to f(45.0)),
        )
        val typeNasty = TaskVariant(
            "v-typenasty", "Type look-alikes", TaskTypes.TYPING,
            mapOf(TaskTypes.LENGTH to ParamValue.Range(14.0, 20.0), TaskTypes.CHARSET to f(3.0),
                TaskTypes.CASE_SENSITIVE to f(1.0), TaskTypes.DEADLINE to f(40.0)),
        )
        val shake = TaskVariant(
            "v-shake", "Shake firmly", TaskTypes.SHAKE,
            mapOf(TaskTypes.INTENSITY to f(15.0), TaskTypes.TOLERANCE to f(6.0), TaskTypes.HOLD to f(5.0),
                TaskTypes.DEADLINE to f(10.0), TaskTypes.OUTSIDE_GRACE to f(0.5)),
        )
        val tilt = TaskVariant(
            "v-tilt", "Tilt", TaskTypes.TILT,
            mapOf(TaskTypes.MAX_ANGLE to f(40.0), TaskTypes.TOLERANCE to f(5.0), TaskTypes.HOLD to f(2.0), TaskTypes.DEADLINE to f(10.0)),
        )

        val burnout = TaskSequence("s-burnout", "burnout", listOf(wait30.id, type12.id, coin.id), failBlockMinutes = 10.0)
        val hell = TaskSequence("s-hell", "hell", listOf(wait60.id, shake.id, tilt.id, typeNasty.id, grim.id), failBlockMinutes = 30.0)

        val clouds = InterruptionVariant(
            "i-clouds", "Black clouds", InterruptionTypes.CLOUDS,
            mapOf(InterruptionTypes.DENSITY to f(6.0), InterruptionTypes.DURATION to ParamValue.Range(7.0, 30.0)),
        )

        val glitch = InterruptionVariant(
            "i-glitch", "Glitch", InterruptionTypes.GLITCH,
            mapOf(InterruptionTypes.INTENSITY to f(7.0), InterruptionTypes.DURATION to ParamValue.Range(5.0, 20.0)),
        )

        val daily = StageSequence(
            "st-default", "Default day",
            listOf(
                Stage(60.0, null, SessionRules(lengthMinutes = null)),
                Stage(30.0, burnout.id, SessionRules(lengthMinutes = 15.0)),
                Stage(0.0, hell.id, SessionRules(lengthMinutes = 10.0, interruptionIds = listOf(clouds.id, glitch.id),
                    launcherMinSeconds = 60.0, launcherMaxSeconds = 180.0)),
            ),
        )

        return Config(
            resetHour = 4,
            resetMinute = 0,
            taskVariants = listOf(wait30, wait60, coin, grim, type12, typeNasty, shake, tilt),
            taskSequences = listOf(burnout, hell),
            interruptionVariants = listOf(clouds, glitch),
            stageSequences = listOf(daily),
            groups = listOf(AppGroup("g-social", "Social", emptySet(), daily.id)),
        )
    }
}
