package com.overlord08x.sebbypass

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records uncaught exceptions to internal storage so crashes can be surfaced
 * inside the app without requiring ADB logcat on a test phone.
 */
object SebCrashReporter {
    private const val CRASH_FILE = "last_crash.txt"

    fun install(context: Context, processName: String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val appContext = context.applicationContext
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                PrintWriter(sw).use { pw ->
                    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                    pw.println("Time: $ts")
                    pw.println("Process: $processName")
                    pw.println("Thread: ${thread.name}")
                    pw.println()
                    throwable.printStackTrace(pw)
                }
                File(appContext.filesDir, CRASH_FILE).writeText(sw.toString())
            } catch (_: Throwable) {
                // Isolated child processes cannot access app filesDir; ignore safely.
            }

            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            }
        }
    }

    fun consumeCrashLog(context: Context): String? {
        return try {
            val file = File(context.applicationContext.filesDir, CRASH_FILE)
            if (!file.exists()) return null
            val text = file.readText().trim()
            file.delete()
            text.ifEmpty { null }
        } catch (_: Throwable) {
            null
        }
    }
}
