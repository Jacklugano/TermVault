package com.jacklugano.termvault.debug

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Sostituto di logcat quando ADB non è disponibile: cattura le eccezioni non
 * gestite, ne salva lo stack trace su file interno e lo ripropone al riavvio
 * (vedi MainActivity). Nessun dato sensibile viene loggato dal resto dell'app,
 * quindi lo stack trace è sicuro da mostrare/condividere.
 */
object CrashReporter {

    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                PrintWriter(sw).use { throwable.printStackTrace(it) }
                val header = buildString {
                    append("Thread: ").append(thread.name).append('\n')
                    append("Model: ").append(android.os.Build.MODEL).append('\n')
                    append("Android: ").append(android.os.Build.VERSION.SDK_INT).append("\n\n")
                }
                file(appContext).writeText(header + sw.toString())
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun consumeReport(context: Context): String? {
        val f = file(context.applicationContext)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        f.delete()
        return text?.takeIf { it.isNotBlank() }
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}
