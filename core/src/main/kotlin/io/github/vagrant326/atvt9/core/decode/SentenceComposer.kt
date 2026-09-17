package io.github.vagrant326.atvt9.core.decode

/**
 * A whole query held as presses, and the sentences they might be.
 *
 * The difference from `T9Engine` is the one the decoder exists for: nothing is committed while
 * typing. The engine ends a word at every `0` and throws its keys away, so by the time the
 * eleventh press reveals that the second word was wrong there is nothing left to revise. Here the
 * presses stay until the query is sent, and every new one may change any word before it.
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

    private val keys = StringBuilder()
    private var readings: List<Hypothesis> = emptyList()

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

    fun press(digit: Char) {
        keys.append(digit)
        stale = true
        selected = 0
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

    /** Works out the readings for the presses so far. The caller chooses when this is worth it. */
    fun settle() {
        if (!stale) {
            return
        }
        readings = decoder.decode(keys.toString(), limit)
        selected = 0
        stale = false
    }

    /** Settles, hands back what should reach the field, and starts again. Empty if nothing reads. */
    fun commit(): String {
        settle()
        val reading = text
        clear()
        return reading
    }

    fun clear() {
        keys.setLength(0)
        readings = emptyList()
        selected = 0
        stale = false
    }
}
