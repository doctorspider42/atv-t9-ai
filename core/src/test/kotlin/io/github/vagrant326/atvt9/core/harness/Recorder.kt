package io.github.vagrant326.atvt9.core.harness

import java.io.File

/**
 * Records what was actually pressed while typing a phrase fast, against what the phrase was.
 *
 * Every number in the decoder that is about the user rather than about the language has to come
 * from here. A substitution cost, an adjacent-key probability, how often a press double-fires or
 * never arrives, how often the space is simply skipped: these are properties of one remote in one
 * hand at one speed, and inventing them produces a decoder tuned to a noise model rather than to
 * a person. The sequence a phrase *should* produce is derivable from the phrase, so a row of
 * (phrase, presses) is a fully aligned training pair and the confusion matrix falls out of a
 * pile of them.
 *
 * **Nothing is decoded while recording and no candidates are shown.** A strip on screen changes
 * how fast and how carelessly a person types — which is the very thing being measured — so the
 * window shows the phrase and the raw digits and nothing else.
 *
 * One row per accepted attempt, tab separated, appended as it happens so a crash costs the
 * attempt in flight and nothing before it:
 *
 *     target   the phrase that was on screen
 *     keys     what was pressed, in the script alphabet of Session.tokenOf
 *     millis   the gap before each press, comma separated, first one 0
 *
 * The timings are kept because at speed the interesting errors are timing errors. A double-fire
 * and a deliberate double letter are the same two presses and differ only in the gap between
 * them, and no amount of text will separate them afterwards.
 */
class Recorder(private val out: File, val targets: List<String>) {

    private val keys = StringBuilder()

    /**
     * When each press happened, absolute. The file stores gaps, which are what anyone reading it
     * wants, but keeping gaps here would make [undo] wrong: the press after a taken-back one
     * would be timed from a press that is no longer in the record.
     */
    private val times = ArrayList<Long>()

    /** Which phrase is on screen, counted from zero. */
    var position = 0
        private set

    val target: String get() = targets.getOrElse(position) { "" }

    val pressed: String get() = keys.toString()

    val isFinished: Boolean get() = position >= targets.size

    fun press(digit: Char, atMillis: Long) {
        keys.append(digit)
        times.add(atMillis)
    }

    /**
     * Takes back one press, which is recorded as not having happened.
     *
     * Deliberate: a correction the typist made *because they noticed* is not the error the
     * decoder has to survive. The errors worth measuring are the ones that go unnoticed at speed,
     * and those are exactly the ones nobody backspaces.
     */
    fun undo() {
        if (keys.isNotEmpty()) {
            keys.setLength(keys.length - 1)
            times.removeAt(times.lastIndex)
        }
    }

    fun restart() {
        keys.setLength(0)
        times.clear()
    }

    /** Writes the attempt and moves to the next phrase. An empty attempt is skipped instead. */
    fun accept() {
        if (keys.isNotEmpty()) {
            if (!out.exists() || out.length() == 0L) {
                out.parentFile?.mkdirs()
                out.writeText("target\tkeys\tmillis\n")
            }
            val gaps = times.mapIndexed { at, time -> if (at == 0) 0 else time - times[at - 1] }
            out.appendText(target + "\t" + keys + "\t" + gaps.joinToString(",") + "\n")
        }
        skip()
    }

    fun skip() {
        position++
        restart()
    }

    companion object {

        /**
         * Phrases to type, from the first column of a TSV.
         *
         * The query corpus is the obvious source and the default: those are phrases this
         * household actually searched for, so the speed and the carelessness recorded against
         * them are the speed and carelessness of real use rather than of a typing test.
         */
        fun targetsFrom(file: File): List<String> = file.readLines()
            .drop(1)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.substringBefore('\t').trim() }
            .filter { it.isNotEmpty() }
    }
}
