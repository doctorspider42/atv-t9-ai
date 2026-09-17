package io.github.vagrant326.atvt9.core.decode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `the space is a press like any other, not a commit`() {
        val composer = composer()
        composer.press('5')
        composer.press('0')
        composer.press('6')
        composer.settle()

        assertEquals("read 506", composer.text)
        assertTrue(composer.isComposing)
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
        assertFalse(composer.isComposing)
        assertEquals("", composer.text)
    }
}
