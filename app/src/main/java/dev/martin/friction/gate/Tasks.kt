package dev.martin.friction.gate

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.martin.friction.FLog
import dev.martin.friction.engine.Resolved
import dev.martin.friction.engine.TaskTypes
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** Callback every task calls exactly once: passed?, plus a detail string for the log. */
typealias TaskResult = (Boolean, String) -> Unit

private val Accent = Color(0xFFF2C14E)
private val Good = Color(0xFF7BD88F)
private val Bad = Color(0xFFFF5A5A)

private fun now() = SystemClock.elapsedRealtime()

@Composable
fun TaskHost(typeId: String, params: Resolved, onResult: TaskResult) {
    when (typeId) {
        TaskTypes.WAIT -> WaitTask(params, onResult)
        TaskTypes.DICE -> FateTask(params, onResult)
        TaskTypes.SHAKE -> ShakeTask(params, onResult)
        TaskTypes.TILT -> TiltTask(params, onResult)
        TaskTypes.TYPING -> TypingTask(params, onResult)
        else -> {
            val cb by rememberUpdatedState(onResult)
            LaunchedEffect(Unit) { cb(false, "unknown task type $typeId") }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
}

@Composable
private fun Caption(text: String, color: Color = Color(0xFFCCCCCC)) {
    Text(text, color = color, fontSize = 15.sp, textAlign = TextAlign.Center)
}

// ---------------------------------------------------------------- wait

@Composable
private fun WaitTask(params: Resolved, onResult: TaskResult) {
    val cb by rememberUpdatedState(onResult)
    val totalMs = ((params[TaskTypes.SECONDS] ?: 30.0) * 1000).toLong().coerceAtLeast(1L)
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = now()
        while (true) {
            elapsed = now() - start
            if (elapsed >= totalMs) break
            delay(100)
        }
        cb(true, "waited ${totalMs}ms")
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Title("Wait.")
        CircularProgressIndicator(
            progress = { (elapsed.toFloat() / totalMs).coerceIn(0f, 1f) },
            modifier = Modifier.size(160.dp),
            color = Accent,
            strokeWidth = 8.dp,
        )
        Caption("${((totalMs - elapsed).coerceAtLeast(0) + 999) / 1000} s")
    }
}

// ---------------------------------------------------------------- fate (dice)

@Composable
private fun FateTask(params: Resolved, onResult: TaskResult) {
    val cb by rememberUpdatedState(onResult)
    val chance = params[TaskTypes.CHANCE] ?: 50.0
    val roll = remember { Random.nextDouble() * 100.0 }
    val passed = roll < chance
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(3500)
        revealed = true
        delay(1300)
        cb(passed, "roll=%.1f chance=%.1f".format(roll, chance))
    }
    val transition = rememberInfiniteTransition(label = "fate")
    val pulse by transition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Title(if (!revealed) "Fate is deciding." else if (passed) "Granted." else "Denied.")
        Canvas(Modifier.size(180.dp)) {
            val r = size.minDimension / 2f
            if (!revealed) {
                drawCircle(Color(0xFF6D5F9E).copy(alpha = 0.5f), radius = r * pulse)
                drawCircle(Color(0xFFB9A8F0).copy(alpha = 0.8f), radius = r * pulse * 0.45f)
            } else {
                drawCircle((if (passed) Good else Bad).copy(alpha = 0.8f), radius = r * 0.6f)
            }
        }
    }
}

// ---------------------------------------------------------------- shake

private class ShakeHolder {
    @Volatile var smoothed = 0f
    var lastNs = 0L
    val gravity = FloatArray(3)
}

@Composable
private fun ShakeTask(params: Resolved, onResult: TaskResult) {
    val cb by rememberUpdatedState(onResult)
    val target = (params[TaskTypes.INTENSITY] ?: 15.0).toFloat()
    val tol = (params[TaskTypes.TOLERANCE] ?: 6.0).toFloat()
    val holdMs = ((params[TaskTypes.HOLD] ?: 5.0) * 1000).toLong()
    val deadlineMs = ((params[TaskTypes.DEADLINE] ?: 10.0) * 1000).toLong()
    val graceMs = ((params[TaskTypes.OUTSIDE_GRACE] ?: 0.5) * 1000).toLong()

    val context = LocalContext.current
    val holder = remember { ShakeHolder() }
    var sensorOk by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val linear = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val sensor = linear ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val x: Float
                val y: Float
                val z: Float
                if (linear != null) {
                    x = e.values[0]; y = e.values[1]; z = e.values[2]
                } else {
                    val g = holder.gravity
                    for (i in 0..2) g[i] = 0.9f * g[i] + 0.1f * e.values[i]
                    x = e.values[0] - g[0]; y = e.values[1] - g[1]; z = e.values[2] - g[2]
                }
                val mag = sqrt(x * x + y * y + z * z)
                val dt = if (holder.lastNs == 0L) 0f else (e.timestamp - holder.lastNs) / 1e9f
                holder.lastNs = e.timestamp
                val a = if (dt <= 0f) 1f else dt / (0.3f + dt) // ~0.3 s smoothing
                holder.smoothed += a * (mag - holder.smoothed)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor == null) {
            sensorOk = false
        } else {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose { sm.unregisterListener(listener) }
    }

    var value by remember { mutableFloatStateOf(0f) }
    var holding by remember { mutableStateOf(false) }
    var held by remember { mutableLongStateOf(0L) }
    var timeLeft by remember { mutableLongStateOf(deadlineMs) }

    LaunchedEffect(Unit) {
        if (!sensorOk) {
            cb(false, "no accelerometer")
            return@LaunchedEffect
        }
        val start = now()
        var last = start
        var outside = 0L
        while (true) {
            delay(50)
            val t = now()
            val dt = t - last
            last = t
            val v = holder.smoothed
            value = v
            val inBand = abs(v - target) <= tol
            if (!holding) {
                timeLeft = (deadlineMs - (t - start)).coerceAtLeast(0)
                if (inBand) {
                    holding = true
                } else if (t - start > deadlineMs) {
                    cb(false, "never reached the band (last=%.1f, target=%.1f±%.1f)".format(v, target, tol))
                    return@LaunchedEffect
                }
            } else {
                held += dt
                if (inBand) {
                    outside = 0
                } else {
                    outside += dt
                    if (outside > graceMs) {
                        cb(false, "strayed outside the band for ${outside}ms (value=%.1f, target=%.1f±%.1f)".format(v, target, tol))
                        return@LaunchedEffect
                    }
                }
                if (held >= holdMs) {
                    cb(true, "held ${held}ms")
                    return@LaunchedEffect
                }
            }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Title("Shake.")
        Caption(
            if (!holding) "Get into the yellow band. ${(timeLeft + 999) / 1000} s left."
            else "Keep it there. ${((holdMs - held).coerceAtLeast(0) + 999) / 1000} s to go."
        )
        val scaleMax = maxOf(target + tol * 2f, 10f)
        Canvas(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 24.dp)) {
            val w = size.width
            val h = size.height
            drawRect(Color(0xFF333333), size = Size(w, h))
            val lo = ((target - tol) / scaleMax).coerceIn(0f, 1f) * w
            val hi = ((target + tol) / scaleMax).coerceIn(0f, 1f) * w
            drawRect(Accent.copy(alpha = 0.45f), topLeft = Offset(lo, 0f), size = Size(hi - lo, h))
            val x = (value / scaleMax).coerceIn(0f, 1f) * w
            val inBand = abs(value - target) <= tol
            drawRect(if (inBand) Good else Color.White, topLeft = Offset(x - 4f, 0f), size = Size(8f, h))
        }
        Caption("%.1f m/s²".format(value))
        if (holding) {
            CircularProgressIndicator(
                progress = { (held.toFloat() / holdMs).coerceIn(0f, 1f) },
                modifier = Modifier.size(64.dp),
                color = Good,
            )
        }
    }
}

// ---------------------------------------------------------------- tilt

private class TiltHolder {
    @Volatile var ax = 0f
    @Volatile var ay = 0f
    val lowpass = FloatArray(3)
}

@Composable
private fun TiltTask(params: Resolved, onResult: TaskResult) {
    val cb by rememberUpdatedState(onResult)
    val maxAngle = (params[TaskTypes.MAX_ANGLE] ?: 40.0).toFloat()
    val tol = (params[TaskTypes.TOLERANCE] ?: 5.0).toFloat()
    val holdMs = ((params[TaskTypes.HOLD] ?: 2.0) * 1000).toLong()
    val deadlineMs = ((params[TaskTypes.DEADLINE] ?: 10.0) * 1000).toLong()
    val tx = remember { (Random.nextFloat() * 2f - 1f) * maxAngle }
    val ty = remember { (Random.nextFloat() * 2f - 1f) * maxAngle }

    val context = LocalContext.current
    val holder = remember { TiltHolder() }
    var sensorOk by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gravitySensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val sensor = gravitySensor ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val g = holder.lowpass
                if (gravitySensor != null) {
                    for (i in 0..2) g[i] = e.values[i]
                } else {
                    for (i in 0..2) g[i] = 0.85f * g[i] + 0.15f * e.values[i]
                }
                val norm = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]).coerceAtLeast(0.1f)
                // Like a ball on the screen: it rolls towards the lower edge.
                holder.ax = Math.toDegrees(asin((-g[0] / norm).coerceIn(-1f, 1f).toDouble())).toFloat()
                holder.ay = Math.toDegrees(asin((g[1] / norm).coerceIn(-1f, 1f).toDouble())).toFloat()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor == null) sensorOk = false else sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }

    var ballX by remember { mutableFloatStateOf(0f) }
    var ballY by remember { mutableFloatStateOf(0f) }
    var held by remember { mutableLongStateOf(0L) }
    var timeLeft by remember { mutableLongStateOf(deadlineMs) }

    LaunchedEffect(Unit) {
        if (!sensorOk) {
            cb(false, "no gravity/accelerometer sensor")
            return@LaunchedEffect
        }
        FLog.d("Task", "tilt target x=%.1f y=%.1f tol=%.1f".format(tx, ty, tol))
        val start = now()
        var last = start
        while (true) {
            delay(40)
            val t = now()
            val dt = t - last
            last = t
            ballX = holder.ax
            ballY = holder.ay
            val inside = hypot(ballX - tx, ballY - ty) <= tol
            held = if (inside) held + dt else 0L
            timeLeft = (deadlineMs - (t - start)).coerceAtLeast(0)
            if (held >= holdMs) {
                cb(true, "held target in ${t - start}ms")
                return@LaunchedEffect
            }
            if (t - start > deadlineMs) {
                cb(false, "deadline passed (at x=%.1f y=%.1f, target x=%.1f y=%.1f)".format(ballX, ballY, tx, ty))
                return@LaunchedEffect
            }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Title("Tilt.")
        Caption("Roll the ball into the ring and hold it. ${(timeLeft + 999) / 1000} s left.")
        Canvas(Modifier.size(280.dp)) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            val scale = r / (maxAngle + 12f)
            drawCircle(Color(0xFF2A2A2A), radius = r, center = c)
            drawLine(Color(0xFF444444), Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = 2f)
            drawLine(Color(0xFF444444), Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = 2f)
            val tc = Offset(c.x + tx * scale, c.y + ty * scale)
            val inside = held > 0
            drawCircle(if (inside) Good else Accent, radius = maxOf(tol * scale, 10f), center = tc, style = Stroke(width = 5f))
            val bc = Offset(
                c.x + (ballX * scale).coerceIn(-r, r),
                c.y + (ballY * scale).coerceIn(-r, r),
            )
            drawCircle(Color.White, radius = 14f, center = bc)
        }
        if (held > 0) {
            CircularProgressIndicator(
                progress = { (held.toFloat() / holdMs).coerceIn(0f, 1f) },
                modifier = Modifier.size(48.dp),
                color = Good,
            )
        }
    }
}

// ---------------------------------------------------------------- typing

private fun randomString(length: Int, charset: Int, caseSensitive: Boolean): String {
    val lower = "abcdefghijkmnopqrstuvwxyz"
    val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    val letters = if (caseSensitive) lower + upper else lower
    val digits = "23456789"
    val pool = when (charset) {
        0 -> letters
        1 -> "0123456789"
        2 -> letters + digits
        else -> if (caseSensitive) "lI1iOo0DB8S5Z2" else "l1i0o8b5s2z"
    }
    return (1..length).map { pool[Random.nextInt(pool.length)] }.joinToString("")
}

@Composable
private fun TypingTask(params: Resolved, onResult: TaskResult) {
    val cb by rememberUpdatedState(onResult)
    val length = (params[TaskTypes.LENGTH] ?: 12.0).roundToInt().coerceIn(1, 200)
    val charset = (params[TaskTypes.CHARSET] ?: 2.0).roundToInt()
    val caseSensitive = (params[TaskTypes.CASE_SENSITIVE] ?: 1.0) >= 0.5
    val deadlineMs = ((params[TaskTypes.DEADLINE] ?: 0.0) * 1000).toLong()
    val target = remember { randomString(length, charset, caseSensitive) }
    var input by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }
    var timeLeft by remember { mutableLongStateOf(deadlineMs) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun submit() {
        if (done) return
        done = true
        val ok = if (caseSensitive) input == target else input.equals(target, ignoreCase = true)
        cb(ok, "typed='$input' expected='$target'")
    }

    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
            keyboard?.show()
        } catch (_: Exception) {
        }
        if (deadlineMs <= 0) return@LaunchedEffect
        val start = now()
        while (!done) {
            timeLeft = (deadlineMs - (now() - start)).coerceAtLeast(0)
            if (timeLeft <= 0) {
                done = true
                cb(false, "deadline passed; typed='$input' expected='$target'")
                return@LaunchedEffect
            }
            delay(200)
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Title("Type it exactly.")
        if (deadlineMs > 0) Caption("${(timeLeft + 999) / 1000} s left")
        Text(
            target,
            color = Accent,
            fontSize = 26.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 3.sp,
            textAlign = TextAlign.Center,
        )
        if (!caseSensitive) Caption("(not case sensitive)")
        OutlinedTextField(
            value = input,
            // Only one character at a time: blocks paste and keyboard word suggestions.
            onValueChange = { new -> if (new.length - input.length <= 1) input = new },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 22.sp, fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = { submit() }) { Text("Submit") }
    }
}
