package dev.martin.friction

import android.app.Application
import android.os.Build
import android.util.Log

class FrictionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FLog.init(this)
        FLog.i(
            "App",
            "process start: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        )

        // Write crashes to the log file before the process dies.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            FLog.writeNow("CRASH on thread ${thread.name}:\n${Log.getStackTraceString(e)}\n")
            previous?.uncaughtException(thread, e)
        }
    }
}
