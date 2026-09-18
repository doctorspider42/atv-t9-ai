package io.github.vagrant326.atvt9.ime

import io.github.vagrant326.atvt9.core.LetterCase
import io.github.vagrant326.atvt9.core.decode.SentenceComposer

/**
 * What the presses do while a whole query is being read, with no Android in sight.
 *
 * Lifted out of `T9ImeService` because every bug the television has shown lived in exactly these
 * decisions, and none of them needed a device to reproduce - a held `2` that typed `a2`, spelling
 * that announced itself and swallowed nothing, a delete that ate a word somebody was trying to
 * correct. They were logic, and logic can be tested; it simply had nowhere to be tested from while
 * it was inside an `InputMethodService`.
 *
 * The service keeps everything that is genuinely Android: which key arrived, whether the field
 * wants digits, the strip, the clock. This keeps what a press means.
 */
class SentenceTyping(
    private val composer: SentenceComposer,
    private val editor: Editor,
    /** Whether the word engine has the presses instead, which spelling means. */
    private val spelling: () -> Boolean = { false },
    /** Committed words, for the store that makes the second typing of a title cheap. */
    private val learn: (List<String>) -> Unit = {},
) {

    /** Applied where words reach the field and never where they reach a dictionary. */
    var letterCase: LetterCase = LetterCase.LOWER

    val isComposing: Boolean get() = composer.isComposing

    /**
     * Returns whether the press was spent here. False hands it back to the word keyboard, which
     * is how spelling, the mark layer and the digit mode keep working unchanged.
     */
    fun press(action: Action): Boolean {
        if (spelling()) {
            return false
        }

        when (action) {
            is Action.Digit -> composer.press(action.digit)

            // Skipping the space is forgiven; pressing it is meant. It settles everything before
            // it, which then belongs to the field and is not revised again.
            is Action.Space -> composer.press('0')

            is Action.Delete -> if (!composer.delete()) {
                // Nothing in the air, so the first delete reopens the word a space just settled
                // rather than starting to eat it. Somebody reaching for delete after seeing the
                // wrong word is not trying to lose the letters — they are trying to choose again,
                // and the reading they wanted is usually already on the strip.
                val settled = composer.reopen()
                if (settled == null) {
                    editor.deleteBefore(1)
                    return true
                }
                editor.deleteBefore(settled.length)
            }

            is Action.Candidate -> {
                composer.next(forward = action.forward)
                return true
            }

            is Action.Back -> {
                composer.clear()
                editor.finishComposing()
                return true
            }

            is Action.Commit -> {
                if (!composer.isComposing) {
                    return false // nothing pending: the press belongs to the field, as before
                }
                send()
                return true
            }

            // Held delete drops the presses still in the air, or, when there are none, the word
            // behind the cursor. Without this it fell through and *committed* the query, which is
            // the opposite of what anybody holding delete is asking for.
            is Action.WordDelete -> {
                if (composer.isComposing) {
                    composer.clear()
                    editor.finishComposing()
                } else {
                    editor.deleteWord()
                }
                return true
            }

            /**
             * A held number key types that number, and the press underneath it has to come back
             * first.
             *
             * Android announces a hold as a second key-down, so the plain press has already been
             * dealt with by the time the hold arrives — and a plain `2` is a letter. Without the
             * undo, holding `2` types `a2`. Keys `0` and `1` are the service's to undo: one is
             * deferred to its release and the other has already put a mark in the field.
             */
            is Action.Number -> {
                if (action.digit in '2'..'9') {
                    composer.delete()
                }
                send()
                editor.commit(action.digit.toString())
                return true
            }

            // Anything else ends the sentence and lets the word keyboard have the press: spelling
            // a word the dictionaries lack is exactly the escape this needs to leave open.
            else -> {
                send()
                return false
            }
        }

        drain()
        return true
    }

    /** Moves anything the composition has settled into the field, once. */
    fun drain() {
        val settled = composer.takeFinished()
        if (settled.isEmpty()) {
            return
        }
        editor.commit(letterCase.apply(settled))
        letterCase = letterCase.afterWord()
        learn(settled.trim().split(' ').filter { it.isNotEmpty() })
    }

    /** Puts the reading of every press so far into the field and starts again. */
    fun send() {
        val words = composer.words.map { it.text }
        val reading = composer.commit()
        if (reading.isEmpty()) {
            editor.finishComposing()
            return
        }
        editor.commit(letterCase.apply(reading))
        letterCase = letterCase.afterWord()
        learn(words)
    }

    /** What the field should show inline: the reading in hand, or nothing. */
    fun showComposing() {
        if (composer.isComposing) {
            editor.compose(letterCase.apply(composer.text))
        } else {
            editor.finishComposing()
        }
    }
}
