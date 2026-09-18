package io.github.vagrant326.atvt9.core.decode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The composition, against a decoder that answers with the presses it was given.
 *
 * A fake rather than the real decoder, because what is being tested is when the decoding happens
 * and what survives it — not what it says. The one property worth pinning is that a reading is
 * never shown as though it belonged to presses that have moved on since.
 */
class SentenceComposerTest {

    private var decodes = 0

    private val decoder = object : Decoder {
        override fun decode(keys: String, limit: Int): List<Hypothesis> {
            decodes++
            return listOf("read $keys", "also $keys").take(limit).map { text ->
                Hypothesis(
                    listOf(DecodedWord(text, language = "pl", from = 0, until = keys.length)),
                    score = -1.0,
                )
            }
        }
    }

    private fun composer() = SentenceComposer(decoder)

    @Test
    fun `presses pile up and nothing is decoded until the typing stops`() {
        val composer = composer()
        composer.press('5')
        composer.press('6')
        composer.press('8')

        assertEquals(0, decodes)
        assertTrue(composer.stale)
        assertEquals("", composer.text)

        composer.settle()
        assertEquals(1, decodes)
        assertFalse(composer.stale)
        assertEquals("read 568", composer.text)
    }

    @Test
    fun `settling twice over the same presses decodes once`() {
        val composer = composer()
        composer.press('5')
        composer.settle()
        composer.settle()

        assertEquals(1, decodes)
    }

    @Test
    fun `a press after a reading makes it stale, so it is not shown as settled`() {
        val composer = composer()
        composer.press('5')
        composer.settle()
        composer.press('6')

        assertTrue(composer.stale)
    }

    @Test
    fun `a pressed space settles what came before it and nothing after`() {
        val composer = composer()
        composer.press('5')
        composer.press('0')

        // Settled, handed over once, and never the composition's business again.
        assertEquals("read 5 ", composer.takeFinished())
        assertEquals("", composer.takeFinished())
        assertFalse(composer.isComposing)

        composer.press('6')
        composer.settle()
        assertEquals("read 6", composer.text)
    }

    @Test
    fun `a space that was never pressed costs nothing, which is the whole point`() {
        val composer = composer()
        composer.press('5')
        composer.press('6')
        composer.settle()

        // Two words or one is the decoder's business; the composition keeps the run whole.
        assertEquals("read 56", composer.text)
        assertEquals("", composer.takeFinished())
    }

    @Test
    fun `a space over presses that read as nothing leaves them in the air`() {
        val empty = SentenceComposer(object : Decoder {
            override fun decode(keys: String, limit: Int) = emptyList<Hypothesis>()
        })
        empty.press('5')
        empty.press('0')

        assertEquals("", empty.takeFinished())
        assertTrue(empty.isComposing)
    }

    @Test
    fun `the first delete after a space puts the word back, alternatives and all`() {
        val composer = composer()
        composer.press('5')
        composer.press('6')
        composer.press('0')

        assertEquals("read 56 ", composer.takeFinished())
        assertFalse(composer.isComposing)

        // What comes back is what has to leave the field, the trailing space included.
        assertEquals("read 56 ", composer.reopen())
        assertTrue(composer.isComposing)

        composer.settle()
        assertEquals("read 56", composer.text)
        composer.next(forward = true)
        assertEquals("also 56", composer.text)
    }

    @Test
    fun `there is nothing to reopen mid-word, or twice`() {
        val composer = composer()
        composer.press('5')
        assertNull(composer.reopen()) // a word is in the air; delete means delete

        composer.press('0')
        composer.takeFinished()
        assertEquals("read 5 ", composer.reopen())
        assertNull(composer.reopen()) // one segment deep, and it has been spent
    }

    @Test
    fun `delete says when there is nothing left, so the caller can delete the field instead`() {
        val composer = composer()
        assertFalse(composer.delete())

        composer.press('5')
        assertTrue(composer.delete())
        assertFalse(composer.delete())
    }

    @Test
    fun `walking the readings wraps in both directions`() {
        val composer = composer()
        composer.press('5')
        composer.settle()

        composer.next(forward = true)
        assertEquals("also 5", composer.text)
        composer.next(forward = true)
        assertEquals("read 5", composer.text)
        composer.next(forward = false)
        assertEquals("also 5", composer.text)
    }

    @Test
    fun `sending settles first, so presses made since the last reading are not lost`() {
        val composer = composer()
        composer.press('5')
        composer.settle()
        composer.press('6') // never settled: a commit landing here must not send "read 5"

        assertEquals("read 56", composer.commit())
        assertEquals("", composer.takeFinished())
        assertFalse(composer.isComposing)
        assertEquals("", composer.text)
    }
}
