package io.github.vagrant326.atvt9.core.decode

import io.github.vagrant326.atvt9.core.DictionaryWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The decoder against a dictionary small enough to reason about.
 *
 * Each case is one of the things the typing record said actually happens, so a change that trades
 * one of them away fails here rather than four hundred phrases later in the benchmark.
 */
class BeamDecoderTest {

    private val polish = DictionaryWriter.of(
        "kot" to 5_000,
        "kos" to 400,
        "los" to 900_000,
        "to" to 800_000,
        "on" to 700_000,
        "anna" to 500_000,
        "ma" to 600_000,
        "mama" to 30_000,
    )

    private val english = DictionaryWriter.of(
        "the" to 900_000,
        "cat" to 20_000,
    )

    private fun decoder(vararg sources: Source) = BeamDecoder(
        sources.toList().ifEmpty { listOf(Source("pl", polish, prior = 1.0)) }
    )

    private fun read(keys: String, vararg sources: Source): String? =
        decoder(*sources).decode(keys).firstOrNull()?.text

    @Test
    fun `the keys as pressed read as the words they spell`() {
        assertEquals("kot", read("568"))
        assertEquals("los to", read("5670868"))
    }

    @Test
    fun `a word can end where no space was pressed`() {
        // 568 868 with nothing between them. This is the whole point of a sentence decoder:
        // the space is the key most often skipped and skipping it must cost, not break.
        assertEquals("kot to", read("568868"))
    }

    @Test
    fun `a press that landed next door is read as the key it was aimed at`() {
        // kot is 5-6-8 and 5 sits next to 6 on the grid, so 5-5-8 is a thumb that fell short.
        assertEquals("kot", read("558"))
    }

    @Test
    fun `one of two presses of the same key may never have arrived`() {
        // anna is 2-6-6-2 and the doubled 6 is swallowed one time in six, which is why this is
        // priced far below an ordinary missing press.
        assertEquals("anna", read("262"))
    }

    @Test
    fun `a press that should not be there is ignored`() {
        assertEquals("kot", read("5688"))
    }

    @Test
    fun `a reading says which language each word was read as`() {
        val sources = arrayOf(
            Source("pl", polish, prior = 0.8),
            Source("en", english, prior = 0.2),
        )
        val reading = decoder(*sources).decode("8430228").firstOrNull()

        // the cat: 843 and 228, with no space between them.
        assertEquals("the cat", reading?.text)
        assertEquals(listOf("en", "en"), reading?.words?.map { it.language })
        assertTrue(reading?.isMixed == false)
    }

    @Test
    fun `a word points back at the presses it was read from`() {
        val reading = decoder().decode("568868")!!.first()

        assertEquals(listOf(0, 3), reading.words.map { it.from })
        assertEquals(listOf(3, 6), reading.words.map { it.until })
    }

    @Test
    fun `nothing readable comes back as nothing, rather than as a guess`() {
        // 999 spells no word here, and inventing one would be worse than saying so: the keyboard
        // has a spelling mode for exactly this and cannot reach it if the decoder always answers.
        assertEquals(emptyList<Hypothesis>(), decoder().decode("999"))
    }
}
