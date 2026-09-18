package io.github.vagrant326.atvt9.core.decode

/**
 * A whole query held as presses, and the sentences they might be.
 *
 * The difference from `T9Engine` is the one the decoder exists for: a run of presses is read as
 * however many words it takes, so a space that was never pressed costs nothing to leave out. The
 * engine ends a word at every `0` and reads each one alone, and a run with no `0` in it is
 * therefore one long word that spells nothing.
 *
 * **But a space that *is* pressed still ends what came before it**, the way it does on a phone.
 * Skipping it is forgiven; pressing it is meant. Everything before it is settled, handed to the
 * field and not revised again, and only the presses since are still in the air. Left revisable to
 * the end, a query would rewrite words the user had already watched come out right, which is a
 * worse thing to do to somebody than making them press a key they meant to press anyway.
 *
 * **Decoding does not happen on the press.** At five presses a second a decode per press is work
 * thrown away four times in five, and nobody reads the screen mid-word at that speed — which is
 * exactly what makes a beam this wide affordable on a television. This class says when its
 * reading is [stale]; whoever owns the clock decides when to call [settle].
 *
 * Android-free, like everything in this module, so the harness on a PC and the keyboard on the
 * television run the same composition rather than two versions of it.
 */
class SentenceComposer(private val decoder: Decoder, private val limit: Int = 5) {

    private companion object {
        /** Key `0`: a space in the text, and a line under everything typed before it. */
        const val SPACE = '0'
    }

    private val keys = StringBuilder()
    private var readings: List<Hypothesis> = emptyList()

    /**
     * Text that has just been settled and is waiting to be handed over, once.
     *
     * A buffer rather than a record: the caller takes it, puts it in the field, and from then on
     * it belongs to the field. Keeping a second copy here would mean two places that believe they
     * know what the field says, and they would disagree the first time anything else wrote to it.
     */
    private val finished = StringBuilder()

    /**
     * The presses and the text of the segment a space last settled, so delete can take it back.
     *
     * A word that came out wrong is corrected by retyping it, which on a remote is the whole cost
     * the decoder exists to avoid — and the reading that was wanted is usually the second one on
     * the strip. Holding the last segment's presses means the first delete after a space can put
     * the word back in the air with its alternatives, instead of starting to eat its letters.
     *
     * One segment deep, and deliberately: two would need the field's text to be tracked backwards
     * through everything else that can write to it, and the word just finished is the one being
     * looked at when the mistake is noticed.
     */
    private var settledKeys: String? = null
    private var settledText: String? = null

    /** Whether the presses have moved on since the readings were worked out. */
    var stale: Boolean = false
        private set

    /** Which reading is in hand, and therefore what sending would commit. */
    var selected: Int = 0
        private set

    val pressed: String get() = keys.toString()

    val hypotheses: List<Hypothesis> get() = readings

    val isComposing: Boolean get() = keys.isNotEmpty()

    /** The reading in hand, or empty while there is none — never a guess at one. */
    val text: String get() = readings.getOrNull(selected)?.text.orEmpty()

    /** The words of the reading in hand, for a caller that has to learn or correct them. */
    val words: List<DecodedWord> get() = readings.getOrNull(selected)?.words.orEmpty()

    /**
     * One press. `0` settles what came before it instead of joining it.
     *
     * A `0` over presses that read as nothing is swallowed, and they stay in the air. The
     * alternative is settling something with no reading, which means either losing the presses or
     * putting digits in the field, and both are worse than a space that appears not to have
     * worked while the strip is saying it has nothing.
     */
    fun press(digit: Char) {
        if (digit == SPACE) {
            settleSegment()
            return
        }
        keys.append(digit)
        stale = true
        selected = 0
    }

    /** Text settled since this was last called, for the caller to put in the field. */
    fun takeFinished(): String {
        val text = finished.toString()
        finished.setLength(0)
        return text
    }

    private fun settleSegment() {
        if (keys.isEmpty()) {
            finished.append(' ')
            return
        }
        settle()
        val reading = text
        if (reading.isEmpty()) {
            return
        }
        finished.append(reading).append(' ')
        settledKeys = keys.toString()
        settledText = "$reading "
        keys.setLength(0)
        readings = emptyList()
        selected = 0
        stale = false
    }

    /**
     * Puts the segment a space last settled back in the air, and says what to remove from the
     * field.
     *
     * The caller deletes that many characters and the word returns as a reading with its
     * alternatives, which is what somebody reaching for delete after seeing the wrong word
     * actually wants — they are not trying to lose the letters, they are trying to choose again.
     * Null when there is nothing settled to reopen, and then delete means delete.
     */
    fun reopen(): String? {
        if (keys.isNotEmpty()) {
            return null
        }
        val text = settledText ?: return null
        keys.append(settledKeys.orEmpty())
        settledKeys = null
        settledText = null
        readings = emptyList()
        selected = 0
        stale = true
        return text
    }

    /** Returns false when there was nothing left to take back, so the caller can delete instead. */
    fun delete(): Boolean {
        if (keys.isEmpty()) {
            return false
        }
        keys.setLength(keys.length - 1)
        stale = true
        selected = 0
        return true
    }

    /**
     * Walks the readings, which is where correction lives now.
     *
     * Not a candidate strip per key: there is no word in progress to offer candidates for, since
     * any press may still change any word. The choice is between whole readings and it is made
     * after the typing rather than during it.
     */
    fun next(forward: Boolean = true) {
        if (readings.size > 1) {
            val step = if (forward) 1 else readings.size - 1
            selected = (selected + step) % readings.size
        }
    }

    /**
     * The presses waiting for a reading, or null when the reading is current.
     *
     * With [apply], this is how the decoding happens somewhere other than here. The work is not
     * fast enough to sit on the thread that draws the keyboard — on a television it is hundreds of
     * milliseconds, and a press arriving during it waits — so the caller may take these keys away,
     * read them wherever it likes, and bring the answer back.
     */
    fun pending(): String? = if (stale) keys.toString() else null

    /**
     * Takes a reading worked out elsewhere. Returns whether it was still wanted.
     *
     * A reading is refused when the presses have moved on since it was asked for, because it is
     * then an answer to a question nobody is asking any more: showing it would put a word on
     * screen that the last press has already ruled out. The presses it was for are the whole
     * identity of the answer, which is why they come back with it.
     */
    fun apply(forKeys: String, found: List<Hypothesis>): Boolean {
        if (forKeys != keys.toString()) {
            return false
        }
        readings = found
        selected = 0
        stale = false
        return true
    }

    /** Works out the readings for the presses so far. The caller chooses when this is worth it. */
    fun settle() {
        if (!stale) {
            return
        }
        readings = decoder.decode(keys.toString(), limit)
        selected = 0
        stale = false
    }

    /**
     * Settles what is still in the air and hands it back, along with anything waiting.
     *
     * What comes back is everything that has not reached the field yet, which after a run of
     * spaces is usually just the last word.
     */
    fun commit(): String {
        settle()
        val reading = finished.toString() + text
        clear()
        return reading
    }

    fun clear() {
        settledKeys = null
        settledText = null
        finished.setLength(0)
        keys.setLength(0)
        readings = emptyList()
        selected = 0
        stale = false
    }
}
