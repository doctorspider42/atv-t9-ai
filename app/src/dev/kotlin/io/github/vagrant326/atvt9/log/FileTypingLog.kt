package io.github.vagrant326.atvt9.log

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.vagrant326.atvt9.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.Writer
import java.util.concurrent.Executors

/**
 * The recorder itself: one tab separated line per event, appended as it happens.
 *
 * **Lines, not records.** A row per committed word would be tidier and would already have decided
 * what the interesting thing is. What is interesting here is not yet known — that is the reason
 * for recording at all — so what goes down is the sequence: every press with the moment it
 * arrived, every reading the decoder offered, every walk of those readings, every delete, and
 * what finally reached the field. A row per word can be derived from that afterwards; the
 * sequence cannot be derived from the rows.
 *
 *     epoch   millis, absolute, so two devices' files can be merged and a gap is a subtraction
 *     event   one of: open refused key act read put close
 *     rest    whatever that event carries, tabs and newlines stripped out of it
 *
 * Absolute times rather than gaps, because a gap is a difference between two lines and a file
 * that stores differences cannot survive a line going missing. This one is written from a
 * background thread and flushed on every commit, so a power cut — which happened during the
 * session this was written in — costs the presses since the last word and nothing before it.
 *
 * On the external files directory rather than in private storage, so that fetching it is
 * `adb pull` and not a lesson about `run-as`. It is a dev build on a television in one house;
 * making the data awkward to get at would only mean it never gets looked at.
 */
class FileTypingLog(context: Context) : TypingLog {

    private val directory = context.getExternalFilesDir(null) ?: context.filesDir
    private val file = File(directory, NAME)
    private val previous = File(directory, ROTATED)

    /**
     * Its own preferences file rather than the keyboard's.
     *
     * The keyboard's [io.github.vagrant326.atvt9.settings.Preferences] is compiled into both
     * channels, and a key that only one channel can act on has no business sitting there next to
     * the ones that matter — somebody reading it would have to know which build they were holding
     * to know what it did.
     */
    private val store = context.getSharedPreferences("typing-log", Context.MODE_PRIVATE)

    /** The writing happens here, so a press never waits for the flash. */
    private val writing = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "typing-log").apply { isDaemon = true }
    }

    /** Set while a field that refuses learning is open. Nothing is recorded through it. */
    @Volatile
    private var muted = false

    override val isAvailable = true

    override var isOn: Boolean
        get() = store.getBoolean(KEY_ON, true)
        set(value) = store.edit().putBoolean(KEY_ON, value).apply()

    override val bytes: Long get() = file.length() + previous.length()

    override val where: String get() = file.absolutePath

    /**
     * Held until the field turns out to be one somebody typed in.
     *
     * Every row of a settings screen that takes focus is a field as far as Android is concerned,
     * so walking down a menu with the d-pad produced a matched pair of open and close per row and
     * nothing in between. Writing the line when the first press arrives keeps the file about
     * typing, and loses nothing: a field nobody typed in has nothing to say.
     */
    @Volatile
    private var waiting: String? = null

    override fun opened(field: String, allowed: Boolean) {
        muted = !allowed
        waiting = null
        if (!isOn) {
            return
        }
        if (allowed) {
            waiting = field
            return
        }
        // A refusal is written at once and is the only thing written for that field. Knowing that
        // a password box was opened and skipped is worth having; knowing what was typed into it
        // is not something this file is allowed to want. Refusals are never the menu-row noise
        // above, because only a real text field can declare itself a password or an address.
        write("refused", field)
    }

    override fun pressed(digit: Char) = write("key", digit.toString())

    override fun acted(action: String) = write("act", action)

    override fun read(keys: String, offered: List<String>) =
        write("read", keys, offered.joinToString("|"))

    override fun put(keys: String, text: String) {
        write("put", keys, text)
        // A committed word is the natural place to make the file durable: it is the end of
        // something, and it is rare enough that flushing on it costs nothing.
        writing.execute { runCatching { flush() } }
    }

    override fun closed() {
        // A field nobody typed in leaves no trace at all, not even the pair of lines saying it
        // was there and then was not.
        if (waiting != null) {
            waiting = null
            return
        }
        write("close")
        writing.execute { runCatching { flush() } }
    }

    override fun clear() {
        writing.execute {
            runCatching {
                file.delete()
                previous.delete()
            }.onFailure { Log.w(TAG, "could not delete the typing log", it) }
        }
    }

    private fun write(event: String, vararg parts: String) {
        if (!isOn || muted) {
            return
        }
        // The field this event belongs to, written now that it has turned out to be one.
        waiting?.let {
            waiting = null
            write("open", it)
        }
        val line = buildString {
            append(System.currentTimeMillis())
            append('\t')
            append(event)
            for (part in parts) {
                append('\t')
                append(clean(part))
            }
            append('\n')
        }
        writing.execute {
            runCatching { append(line) }
                .onFailure { Log.w(TAG, "could not write the typing log", it) }
        }
    }

    /** Whatever was typed, made safe for a line-and-tab format. Never shortened. */
    private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ')

    // Everything below runs on the writing thread and nowhere else.

    private var out: Writer? = null

    private fun append(line: String) {
        val writer = out ?: open()
        writer.write(line)
        if (file.length() > LIMIT) {
            rotate()
        }
    }

    /**
     * Opened for appending, which `File.bufferedWriter` does not do — it truncates, and a record
     * that starts again every time the keyboard is created is not a record.
     */
    private fun open(): Writer {
        val fresh = !file.exists() || file.length() == 0L
        val writer = FileOutputStream(file, true).writer(Charsets.UTF_8).buffered()
        out = writer
        if (fresh) {
            // Which device and which build, once per file, so a merged pile of these still says
            // where each line came from.
            writer.write(
                "#\tdevice\t${Build.MODEL}\tandroid\t${Build.VERSION.SDK_INT}" +
                    "\tbuild\t${BuildConfig.VERSION_NAME}\n"
            )
        }
        return writer
    }

    private fun flush() {
        out?.flush()
    }

    /**
     * One file back, and then the oldest is lost.
     *
     * A recorder that fills a television's storage is a recorder that gets uninstalled. Four
     * megabytes is somewhere around a hundred thousand events, which is far more typing than
     * anybody will do between two `adb pull`s.
     */
    private fun rotate() {
        runCatching {
            out?.flush()
            out?.close()
            out = null
            previous.delete()
            file.renameTo(previous)
        }.onFailure { Log.w(TAG, "could not rotate the typing log", it) }
    }

    private companion object {
        const val TAG = "T9"
        const val NAME = "typing.tsv"
        const val ROTATED = "typing-1.tsv"
        const val LIMIT = 4L * 1024 * 1024
        const val KEY_ON = "on"
    }
}
