package dev.martin.friction

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Aggressive logger: every line goes to Logcat (tag "Friction") AND to a file
 * inside the app, so it can be read and shared from the app itself without adb.
 */
object FLog {
    private const val TAG = "Friction"
    private const val MAX_BYTES = 1_000_000L

    private val writer = Executors.newSingleThreadExecutor()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "friction.log")
    }

    fun d(area: String, msg: String) = write('D', area, msg, null)
    fun i(area: String, msg: String) = write('I', area, msg, null)
    fun w(area: String, msg: String, t: Throwable? = null) = write('W', area, msg, t)
    fun e(area: String, msg: String, t: Throwable? = null) = write('E', area, msg, t)

    private fun write(level: Char, area: String, msg: String, t: Throwable?) {
        val text = "[$area] $msg"
        when (level) {
            'D' -> Log.d(TAG, text, t)
            'I' -> Log.i(TAG, text, t)
            'W' -> Log.w(TAG, text, t)
            else -> Log.e(TAG, text, t)
        }
        val line = buildString {
            append(synchronized(timeFormat) { timeFormat.format(Date()) })
            append(' ').append(level).append(' ').append(text).append('\n')
            if (t != null) append(Log.getStackTraceString(t)).append('\n')
        }
        writer.execute { appendLine(line) }
    }

    /** Synchronous write, used by the crash handler (the process is about to die). */
    fun writeNow(line: String) = appendLine(line)

    @Synchronized
    private fun appendLine(line: String) {
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) {
                // Keep the newest half when the file grows too big.
                val keep = f.readText().takeLast((MAX_BYTES / 2).toInt())
                f.writeText("--- log truncated ---\n$keep")
            }
            f.appendText(line)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun readAll(): String = try {
        file?.takeIf { it.exists() }?.readText() ?: ""
    } catch (e: Exception) {
        "Could not read log: $e"
    }

    @Synchronized
    fun clear() {
        try { file?.writeText("") } catch (_: Exception) {}
    }
}
