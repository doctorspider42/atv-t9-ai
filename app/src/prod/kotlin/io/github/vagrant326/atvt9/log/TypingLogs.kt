package io.github.vagrant326.atvt9.log

import android.content.Context

/**
 * The released keyboard's recorder, which is no recorder.
 *
 * This file is the whole of the production implementation on purpose. The class that writes
 * typing to disk lives in `src/dev` and is not compiled into this channel, so there is no setting
 * to find, no file to leave behind and no code path to audit. The README's promise about what is
 * kept is then a fact about the APK rather than a claim about a default.
 */
object TypingLogs {

    fun of(context: Context): TypingLog = TypingLog.NONE
}
