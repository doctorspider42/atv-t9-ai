package io.github.vagrant326.atvt9.log

/**
 * Everything the keyboard was told and everything it answered, written down, in the build that is
 * allowed to.
 *
 * Every number this decoder is tuned with came from a person copying phrases into a window on a
 * PC with a numeric keypad. That is a good way to measure a thumb and a bad way to measure a
 * household: the phrases were chosen by the corpus, the typist knew they were being measured, and
 * a desk keypad is not a remote held at arm's length on a sofa. The gaps between presses, the
 * words nobody's dictionary has, the queries that read as nothing at all — none of those are the
 * same on a television, and none of them can be honestly guessed from a laptop.
 *
 * So the dev build records what actually happens. Not the field contents as a thing in itself:
 * what is wanted is the pairing — these presses, at these moments, read as these words, and this
 * is the one the user kept. A correction is the ground truth nobody has to be asked for.
 *
 * **It exists only in the dev channel.** [NONE] is what the released app is given, and the
 * implementation that writes files is in `src/dev` and is not compiled into `prod` at all —
 * nothing that could be switched on by a setting somebody did not mean to touch. The README
 * promises the released keyboard keeps words and a use count and nothing else, and that promise
 * is kept structurally rather than by a flag.
 *
 * Fields that refuse learning refuse recording too — a password, a field that asked not to be
 * learnt from, an address bar. That refusal is not the setting's to override: a dev build is
 * allowed to be curious about how somebody types, not about what their password is.
 */
interface TypingLog {

    /** Whether this build has a recorder at all. False everywhere but the dev channel. */
    val isAvailable: Boolean

    /** Whether it is switched on, which is the user's to decide and theirs to see. */
    var isOn: Boolean

    /** How much has been written. A screen that offers to delete this has to say how much. */
    val bytes: Long

    /** Where it can be read from, for the instructions that say how to fetch it. */
    val where: String

    /**
     * A field opened. [allowed] is false for the fields nothing may be learnt from, and then
     * nothing but this line is recorded until the next field.
     */
    fun opened(field: String, allowed: Boolean)

    /** One press of a key that carries letters. */
    fun pressed(digit: Char)

    /** Anything else a key meant: a delete, a space, a walk of the readings, a held digit. */
    fun acted(action: String)

    /** What the decoder made of the presses so far, best first. */
    fun read(keys: String, offered: List<String>)

    /**
     * What reached the field, and the presses it came from.
     *
     * The pair is the whole point of the file. A reading on its own says what the keyboard
     * believed; the presses on their own can be replayed through any later decoder; together they
     * are a labelled example, and the label came from somebody accepting it rather than from
     * anybody being asked.
     */
    fun put(keys: String, text: String)

    fun closed()

    /** Throws away everything recorded so far, including what has rotated out. */
    fun clear()

    companion object {

        /** The recorder the released keyboard has: none, and nothing to switch on. */
        val NONE: TypingLog = object : TypingLog {
            override val isAvailable = false
            override var isOn: Boolean
                get() = false
                set(_) = Unit
            override val bytes = 0L
            override val where = ""
            override fun opened(field: String, allowed: Boolean) = Unit
            override fun pressed(digit: Char) = Unit
            override fun acted(action: String) = Unit
            override fun read(keys: String, offered: List<String>) = Unit
            override fun put(keys: String, text: String) = Unit
            override fun closed() = Unit
            override fun clear() = Unit
        }
    }
}
