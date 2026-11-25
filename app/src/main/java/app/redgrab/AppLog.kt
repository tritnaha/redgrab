package app.redgrab

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App log kept in a file (last ~400 KB) so it survives restarts and crashes,
 * shown in the Log tab and copyable in one tap.
 */
object AppLog {
    private const val TAG = "RedGrab"
    private const val MAX_BYTES = 400_000L
    private lateinit var file: File
    private lateinit var crashFile: File
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    /** Bumped on every write so the Log tab can refresh. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /** Number of errors logged since the app started (drives the badge on the Log tab). */
    private val _errors = MutableStateFlow(0)
    val errors: StateFlow<Int> = _errors

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "redgrab.log")
        crashFile = File(ctx.filesDir, "last_crash.txt")
        val pi = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        i("App start — RedGrab ${pi?.versionName} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        installCrashHandler()
    }

    fun i(msg: String) = write("I", msg, null)
    fun w(msg: String, t: Throwable? = null) = write("W", msg, t)
    fun e(msg: String, t: Throwable? = null) = write("E", msg, t)

    @Synchronized
    private fun write(level: String, msg: String, t: Throwable?) {
        when (level) {
            "E" -> Log.e(TAG, msg, t)
            "W" -> Log.w(TAG, msg, t)
            else -> Log.i(TAG, msg)
        }
        if (!::file.isInitialized) return
        runCatching {
            val line = buildString {
                append(fmt.format(Date())).append(' ').append(level).append(' ').append(msg)
                if (t != null) append('\n').append(stack(t))
                append('\n')
            }
            file.appendText(line)
            if (file.length() > MAX_BYTES) {
                val keep = file.readText().takeLast((MAX_BYTES / 2).toInt())
                file.writeText("… (older lines trimmed)\n" + keep.substringAfter('\n'))
            }
        }
        if (level == "E") _errors.value++
        _version.value++
    }

    fun read(): String = if (::file.isInitialized && file.exists()) file.readText() else ""

    fun clear() {
        if (::file.isInitialized) file.writeText("")
        _version.value++
    }

    /** Crash text from the previous run, if the app crashed. Cleared once read. */
    fun takeLastCrash(): String? {
        if (!::crashFile.isInitialized || !crashFile.exists()) return null
        val text = crashFile.readText()
        crashFile.delete()
        return text.ifBlank { null }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            runCatching {
                val text = "Crash on thread '${thread.name}' at ${fmt.format(Date())}\n${stack(ex)}"
                crashFile.writeText(text)
                write("E", "CRASH: $text", null)
            }
            previous?.uncaughtException(thread, ex)
        }
    }

    fun stack(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString().trim()
}
