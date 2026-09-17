package io.github.vagrant326.atvt9.core.decode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The contract, tested against a decoder that decodes nothing.
 *
 * There is no implementation yet and these still earn their place: they are what makes the shape
 * a commitment rather than a comment. The case that matters is the mixed query, because it is the
 * one a single-language decoder would quietly make impossible and the one nobody would notice was
 * missing until the whole beam had been built around one dictionary.
 */
class DecoderTest {

    /** `piątek the series` — the query that decides the whole design. From the query corpus. */
    private val mixed = Hypothesis(
        words = listOf(
            DecodedWord("piątek", language = "pl", from = 0, until = 6, alternatives = listOf("piątek", "piąte")),
            DecodedWord("the", language = "en", from = 7, until = 10),
            DecodedWord("series", language = "en", from = 11, until = 17, alternatives = listOf("series", "serious")),
        ),
        score = -12.5,
    )

    @Test
    fun `a reading can change language partway through`() {
        assertTrue(mixed.isMixed)
        assertEquals("piątek the series", mixed.text)
        assertEquals(listOf("pl", "en", "en"), mixed.words.map { it.language })
    }

    @Test
    fun `a word may belong to no language, which is what a learnt name is`() {
        val learnt = Hypothesis(
            listOf(DecodedWord("accantus", language = null, from = 0, until = 8)),
            score = -4.0,
        )

        assertFalse(learnt.isMixed)
        assertEquals(listOf<String?>(null), learnt.words.map { it.language })
    }

    @Test
    fun `a word points back at the keys it was read from, not at the letters`() {
        // The letters are the answer and the keys are the question, so a correction that replaces
        // a word has to know which presses it is replacing.
        val word = mixed.words[2]
        assertEquals(6, word.until - word.from)
        assertEquals("series", word.text)
    }

    @Test
    fun `the weights that only matter with two languages are there before they are needed`() {
        val weights = Weights()
        assertEquals(1.0, weights.languagePrior)
        assertEquals(-0.8, weights.languageSwitch)
    }

    @Test
    fun `a decoder is handed the whole query and answers with whole readings`() {
        val decoder = object : Decoder {
            override fun decode(keys: String, limit: Int) = listOf(mixed).take(limit)
        }

        // Longer by one key, and the reading is free to differ everywhere: that is the point of
        // passing the whole sequence rather than the word in progress.
        assertEquals(listOf(mixed), decoder.decode("742835084303743779"))
        assertEquals(emptyList<Hypothesis>(), decoder.decode("7428350843037437", limit = 0))
    }
}
