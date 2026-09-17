package io.github.vagrant326.atvt9.core.harness

import io.github.vagrant326.atvt9.core.Keypad
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
 *     source   where the phrase came from, so one file can hold several sittings
 *     at        which phrase of that source it was, counted from zero
 *     pad      which way up the numeric keypad was being read
 *     target   the phrase that was on screen
 *     keys     what was pressed, in the script alphabet of Session.tokenOf
 *     millis   the gap before each press, comma separated, first one 0
 *
 * The first two columns exist so a long text can be copied out over several evenings: a new
 * recorder picks up after the highest `at` already written for its source. Nobody is going to
 * type a book in one sitting, and a tool that restarts it from the beginning is a tool that
 * collects the first two hundred lines four times.
 *
 * `pad` is there because the first recording ever made was made through the wrong one: typed
 * with a remote's geometry while the harness read the keypad literally, which turns every `1`
 * into a `7` and is invisible in the file afterwards. A digit here is what the decoder would
 * have seen; the pad is what says where the thumb actually was, and a confusion matrix needs
 * both.
 *
 * The timings are kept because at speed the interesting errors are timing errors. A double-fire
 * and a deliberate double letter are the same two presses and differ only in the gap between
 * them, and no amount of text will separate them afterwards.
 */
class Recorder(
    private val out: File,
    val targets: List<String>,
    private val source: String = "",
    private val pad: Pad = Pad.NUMPAD,
) {

    private val keys = StringBuilder()

    /**
     * When each press happened, absolute. The file stores gaps, which are what anyone reading it
     * wants, but keeping gaps here would make [undo] wrong: the press after a taken-back one
     * would be timed from a press that is no longer in the record.
     */
    private val times = ArrayList<Long>()

    /** Which phrase is on screen, counted from zero, and where a resumed sitting starts. */
    var position = resumeFrom(out, source)
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
                out.writeText(HEADER + "\n")
            }
            val gaps = times.mapIndexed { at, time -> if (at == 0) 0 else time - times[at - 1] }
            out.appendText(
                listOf(source, position, pad.name.lowercase(), target, keys, gaps.joinToString(","))
                    .joinToString("\t") + "\n"
            )
        }
        skip()
    }

    fun skip() {
        position++
        restart()
    }

    companion object {

        const val HEADER = "source\tat\tpad\ttarget\tkeys\tmillis"

        /**
         * Where a sitting picks up: one past the highest phrase already recorded for [source].
         *
         * Highest rather than a count, because a skipped phrase writes no row and a count would
         * then hand back a position that has already been typed. A phrase skipped in the middle
         * of a sitting is lost to the record, which is the right trade: it was skipped because it
         * was not worth recording.
         */
        fun resumeFrom(out: File, source: String): Int {
            if (source.isEmpty() || !out.exists()) {
                return 0
            }
            return out.readLines()
                .drop(1)
                .mapNotNull { row ->
                    val columns = row.split('\t')
                    columns.getOrNull(1)?.toIntOrNull()?.takeIf { columns[0] == source }
                }
                .maxOrNull()
                ?.plus(1)
                ?: 0
        }

        /**
         * A long text cut into phrases of [words] words each, and reduced to what the keypad can
         * reach.
         *
         * Any text will do and that is the point: the errors being measured are a property of a
         * thumb and a key grid, not of a vocabulary, so a book supplies in an evening what a
         * query corpus of twenty-six lines never could. Everything the keypad cannot spell — a
         * comma, a digit, an apostrophe — becomes a space rather than vanishing, because dropping
         * it would join the letters either side into a word that was never written and then ask
         * somebody to type it.
         *
         * Cut by word count rather than by sentence, so every phrase is about as long as the last
         * one. A record made of two-word lines and forty-word lines would measure how tiring a
         * line is as much as how error-prone the keys are.
         */
        fun fragmentsOf(text: String, words: Int): List<String> {
            val spellable = text.lowercase().map { if (Keypad.digitOf(it) != null) it else ' ' }
            return spellable.joinToString("")
                .split(' ')
                .filter { it.isNotEmpty() }
                .chunked(words.coerceAtLeast(1))
                .map { it.joinToString(" ") }
        }

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
