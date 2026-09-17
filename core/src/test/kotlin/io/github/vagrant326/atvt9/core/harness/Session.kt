package io.github.vagrant326.atvt9.core.harness

import io.github.vagrant326.atvt9.core.Candidate
import io.github.vagrant326.atvt9.core.Composer
import io.github.vagrant326.atvt9.core.Dictionary
import io.github.vagrant326.atvt9.core.Keypad
import io.github.vagrant326.atvt9.core.LetterCase
import io.github.vagrant326.atvt9.core.T9Engine
import io.github.vagrant326.atvt9.core.UserDictionary

/** One press of the remote, as the harness delivers it. */
sealed interface Action {

    /** `2`-`9`: a letter under the word rules, or a multitap tap while spelling. */
    data class Digit(val digit: Char) : Action

    /** `0`: finish the word and add a space. */
    data object Space : Action

    /** `1`: cycle `. , - ' & : /`, replacing in place. */
    data object Punctuation : Action

    /** `▶` / `▼`: walk the candidates forward. */
    data object Next : Action

    /** `◀` / `CH▴`: walk them back. */
    data object Previous : Action

    /** `▲`: delete, and the one delete that is never conditional on anything. */
    data object Delete : Action

    /** hold `▲`: delete back to the start of the word. */
    data object DeleteWord : Action

    /** `OK`: finish the word, or submit the field when nothing is pending. */
    data object Commit : Action

    /** hold `0`: `abc` to `Abc` to `ABC`, for the word in flight. */
    data object Caps : Action

    /** hold `1`: spell the word out letter by letter. */
    data object Spell : Action

    /** `BACK`: abandon the word in progress. */
    data object Abandon : Action

    /**
     * The multitap timeout, which is a wait on the remote and has to be a press here.
     *
     * Nothing on the device sends this — [T9Engine.settle] is called from a handler when the
     * timeout expires. A scripted run has no wall clock to expire, so the script says where the
     * letter ended instead, and `aa` stays reachable.
     */
    data object Settle : Action
}

/**
 * The keyboard's state machine without the keyboard: what `T9ImeService` does to an editor,
 * done to a `StringBuilder` instead.
 *
 * This exists so the text the harness prints is the text the IME would have produced. The IME
 * itself cannot be reused — it is an `InputMethodService` and needs Android — so the part of it
 * that is a decision rather than a call into the platform is repeated here, and only that part:
 * digits, the candidate walk, case, the mark cycle, and where a word ends. The digit mode, the
 * symbol layer and the passthrough rules are deliberately absent, because none of them change
 * which word a sequence produces, which is the only question this harness is asked.
 *
 * It is also the seam the decoder goes through. When a beam decoder replaces the per-word
 * lookup it replaces [engine] and nothing else here, and the script that measured the present
 * keyboard measures the new one unchanged.
 */
class Session(
    dictionary: Dictionary?,
    val user: UserDictionary = UserDictionary(),
    private val clock: () -> Long = { 0L },
) {

    private val text = StringBuilder()
    private val engine = T9Engine(dictionary, user)

    private var letterCase = LetterCase.LOWER

    /** Where the mark being cycled sits, so a second press of `1` replaces it rather than adds. */
    private var punctuationAt = -1

    /** Every query that reached `OK` with nothing pending, which is what a search box would see. */
    val submitted = mutableListOf<String>()

    var dictionary: Dictionary?
        get() = engine.dictionary
        set(value) {
            engine.reset()
            engine.dictionary = value
        }

    /** The committed text, without the word still being chosen. */
    val committed: String get() = text.toString()

    /** What the field would show: committed text plus the word in flight. */
    val field: String get() = text.toString() + letterCase.apply(engine.composing)

    val candidates: List<Candidate> get() = engine.candidates

    val selected: Int get() = engine.selected

    val sequence: String get() = engine.sequence

    val isComposing: Boolean get() = engine.isComposing

    val hasMatch: Boolean get() = engine.hasMatch

    val mode: Composer get() = engine.mode

    val case: LetterCase get() = letterCase

    fun press(action: Action) {
        when (action) {
            is Action.Digit -> {
                engine.press(action.digit, clock())
                punctuationAt = -1
            }

            Action.Space -> {
                finishWord()
                text.append(' ')
                punctuationAt = -1
            }

            Action.Punctuation -> cycleMark()

            Action.Next -> engine.next()

            // The engine wraps forward only, because on the remote the strip is walked with two
            // keys and the second is this one. Expressed as a select rather than added to
            // T9Engine: the IME does the same, and a harness that needed engine changes to run
            // would stop being a measurement of the shipped keyboard.
            Action.Previous -> if (engine.candidates.size > 1) {
                val size = engine.candidates.size
                engine.select((engine.selected - 1 + size) % size)
            }

            Action.Delete -> if (!engine.backspace()) {
                deleteCharacter()
            }

            Action.DeleteWord -> if (engine.isComposing) {
                engine.reset()
            } else {
                deleteWord()
            }

            Action.Commit -> if (engine.isComposing) {
                finishWord()
            } else {
                submit()
            }

            Action.Caps -> letterCase = letterCase.next()

            Action.Spell -> engine.spell()

            Action.Abandon -> engine.reset()

            Action.Settle -> engine.settle()
        }
    }

    /** Runs a script of single-character tokens. See [tokenOf] for what they mean. */
    fun run(script: String) {
        for (token in script) {
            tokenOf(token)?.let(::press)
        }
    }

    private fun finishWord() {
        val word = engine.commit(learn = true) ?: return
        text.append(letterCase.apply(word))
        letterCase = letterCase.afterWord()
        punctuationAt = -1
    }

    /**
     * Key `1`, which finishes the word first.
     *
     * A mark arriving mid-word would have to be inserted into a sequence the dictionary has not
     * finished reading, and there is no answer to what the letters either side of it then mean.
     * Ending the word is the only interpretation that leaves the strip describing something.
     */
    private fun cycleMark() {
        if (engine.isComposing) {
            finishWord()
        }
        if (punctuationAt >= 0 && punctuationAt == text.length - 1) {
            val at = MARKS.indexOf(text[punctuationAt])
            text.setCharAt(punctuationAt, MARKS[(at + 1) % MARKS.length])
            return
        }
        text.append(MARKS[0])
        punctuationAt = text.length - 1
    }

    private fun deleteCharacter() {
        if (text.isNotEmpty()) {
            text.setLength(text.length - 1)
        }
        punctuationAt = -1
    }

    private fun deleteWord() {
        while (text.isNotEmpty() && text.last() == ' ') {
            text.setLength(text.length - 1)
        }
        while (text.isNotEmpty() && text.last() != ' ') {
            text.setLength(text.length - 1)
        }
        punctuationAt = -1
    }

    private fun submit() {
        val query = text.toString().trim()
        if (query.isNotEmpty()) {
            submitted.add(query)
        }
        text.setLength(0)
        letterCase = LetterCase.LOWER
        punctuationAt = -1
    }

    companion object {

        /** The same seven the `1` key cycles on the device, in the same order. */
        const val MARKS = ".,-'&:/"

        /**
         * The script alphabet, one character per press.
         *
         * A script is how a run becomes repeatable: `--keys` in the harness, and the noise
         * benchmark the decoder will need, both say what was pressed rather than what was meant.
         */
        fun tokenOf(token: Char): Action? = when {
            Keypad.isDigit(token) -> Action.Digit(token)
            token == '0' -> Action.Space
            token == '1' -> Action.Punctuation
            token == '>' -> Action.Next
            token == '<' -> Action.Previous
            token == '#' -> Action.Commit
            token == '*' -> Action.Delete
            token == '_' -> Action.DeleteWord
            token == '^' -> Action.Caps
            token == '~' -> Action.Spell
            token == '!' -> Action.Abandon
            token == '.' -> Action.Settle
            else -> null // whitespace and anything else: a script stays readable
        }
    }
}
