package io.github.vagrant326.atvt9.log

import android.content.Context

/**
 * One recorder for the process, because the keyboard and the settings screen are both in it.
 *
 * They are the same process — only the updater is split off — and both hold a view of the same
 * file: the keyboard appends to it and the settings screen reports its size and offers to delete
 * it. Two instances would each have their own buffered writer over one file, which is how a log
 * ends up with half of one line inside another.
 */
object TypingLogs {

    private var log: TypingLog? = null

    @Synchronized
    fun of(context: Context): TypingLog =
        log ?: FileTypingLog(context.applicationContext).also { log = it }
}
