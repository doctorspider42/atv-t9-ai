package io.github.vagrant326.atvt9.ime

import io.github.vagrant326.atvt9.core.decode.Decoder
import io.github.vagrant326.atvt9.core.decode.DecodedWord
import io.github.vagrant326.atvt9.core.decode.Hypothesis
import io.github.vagrant326.atvt9.core.decode.SentenceComposer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The presses, and what they leave in the field.
 *
 * Every case here is a bug that reached the television first. That is the point of the file: the
 * decisions were inside an `InputMethodService`, so the only way to find out what a held `2` did
 * was to build an APK, install it, and hold `2` — and by then the answer was `a2`.
 */
class SentenceTypingTest {

    /**
     * A field that remembers what was written to it, which is all the service's editor does that
     * matters here.
     */
    private class Field : Editor {
        val committed = StringBuilder()
        var composing: String = ""

        override fun commit(text: String) {
            committed.append(text)
            composing = ""
        }

        override fun compose(text: String) {
            composing = text
        }

        override fun finishComposing() {
            composing = ""
        }

        override fun deleteBefore(count: Int) {
            committed.setLength((committed.length - count).coerceAtLeast(0))
        }

        override fun deleteWord() {
            while (committed.isNotEmpty() && committed.last() == ' ') {
                committed.setLength(committed.length - 1)
            }
            while (committed.isNotEmpty() && committed.last() != ' ') {
                committed.setLength(committed.length - 1)
            }
        }

        /** What somebody looking at the television would see. */
        override fun toString(): String = committed.toString() + composing
    }

    /** Reads `568` as `kot` and offers `kos` behind it, so a walk of the readings is visible. */
    private val decoder = object : Decoder {
        override fun decode(keys: String, limit: Int): List<Hypothesis> {
            if (keys.isEmpty()) {
                return emptyList()
            }
            return listOf("word $keys", "other $keys").take(limit).mapIndexed { at, text ->
                Hypothesis(
                    listOf(DecodedWord(text, language = "pl", from = 0, until = keys.length)),
                    score = -at.toDouble(),
                )
            }
        }
    }

    private val field = Field()
    private val learnt = mutableListOf<String>()
    private var spelling = false
    private val composer = SentenceComposer(decoder)
    private val typing = SentenceTyping(
        composer = composer,
        editor = field,
        spelling = { spelling },
        learn = { learnt += it },
    )

    private fun type(vararg digits: Char) {
        for (digit in digits) {
            typing.press(if (digit == '0') Action.Space else Action.Digit(digit))
        }
        composer.settle()
        typing.showComposing()
    }

    @Test
    fun `holding a number key types the number, not a letter and the number`() {
        // The press underneath the hold has already been dealt with — Android announces a hold as
        // a second key-down — so without taking it back this wrote `word 2` and then `2`.
        typing.press(Action.Digit('2'))
        typing.press(Action.Number('2'))

        assertEquals("2", field.toString())
        assertFalse(typing.isComposing)
    }

    @Test
    fun `a number after a word ends the word first`() {
        type('5', '6', '8')
        typing.press(Action.Digit('2'))
        typing.press(Action.Number('2'))

        assertEquals("word 5682", field.toString())
    }

    @Test
    fun `while spelling, the presses belong to the word engine`() {
        // The strip said `spelling` while every digit went on being read as a word, so the mode
        // announced itself and did nothing at all.
        spelling = true

        assertFalse(typing.press(Action.Digit('2')))
        assertFalse(typing.press(Action.Commit))
        assertEquals("", field.toString())
    }

    @Test
    fun `the arrows walk the readings`() {
        type('5', '6', '8')
        assertEquals("word 568", field.toString())

        typing.press(Action.Candidate(forward = true))
        typing.showComposing()
        assertEquals("other 568", field.toString())

        typing.press(Action.Candidate(forward = false))
        typing.showComposing()
        assertEquals("word 568", field.toString())
    }

    @Test
    fun `a space settles the word and hands it over once`() {
        type('5', '6', '8', '0')

        assertEquals("word 568 ", field.toString())
        assertFalse(typing.isComposing)
        assertEquals(listOf("word", "568"), learnt)
    }

    @Test
    fun `the first delete after a space reopens the word rather than eating it`() {
        type('5', '6', '8', '0')
        assertEquals("word 568 ", field.toString())

        typing.press(Action.Delete)
        composer.settle()
        typing.showComposing()

        // The word is back in the air, the field holds nothing of it, and the other reading is one
        // press away — which is the whole reason somebody reaches for delete here.
        assertEquals("word 568", field.toString())
        assertTrue(typing.isComposing)

        typing.press(Action.Candidate(forward = true))
        typing.showComposing()
        assertEquals("other 568", field.toString())
    }

    @Test
    fun `a second delete goes back to eating what is in the field`() {
        typing.press(Action.Digit('5'))
        typing.press(Action.Space)
        typing.press(Action.Delete) // reopens
        typing.press(Action.Delete) // takes back the press
        composer.settle()
        typing.showComposing()

        assertEquals("", field.toString())
        assertFalse(typing.isComposing)

        field.commit("abc")
        typing.press(Action.Delete)
        assertEquals("ab", field.toString())
    }

    @Test
    fun `OK with nothing pending is the field's press, not the keyboard's`() {
        assertFalse(typing.press(Action.Commit))

        type('5', '6', '8')
        assertTrue(typing.press(Action.Commit))
        assertEquals("word 568", field.toString())
    }

    @Test
    fun `holding delete drops what is in the air, then words behind the cursor`() {
        type('5', '6', '8')
        typing.press(Action.WordDelete)
        typing.showComposing()
        assertEquals("", field.toString())

        field.commit("one two ")
        typing.press(Action.WordDelete)
        assertEquals("one ", field.toString())
    }

    @Test
    fun `anything the sentence has no meaning for ends it and is handed back`() {
        type('5', '6', '8')

        // Spelling, the mark layer and the digit mode all arrive this way: the query reaches the
        // field first, and the press goes on to the word keyboard.
        assertFalse(typing.press(Action.Spell))
        assertEquals("word 568", field.toString())
    }
}
