package io.github.vagrant326.atvt9.core.decode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.abs

class BigramsTest {

    /**
     * The hashes `corpus/build.py` writes, computed there and pasted here.
     *
     * The table is keyed by a hash and nothing else, so the two implementations agreeing is the
     * whole contract between them: disagree by one character of the recipe and every lookup misses
     * silently, the model says nothing, and everything still builds and runs.
     */
    @Test
    fun `the hash is the one the corpus script computes`() {
        assertEquals(-2251039057464462053L, Bigrams.hash("pink", "floyd"))
        assertEquals(6333728650633612191L, Bigrams.hash("ma", "ma"))
        assertEquals(-1810105805726866284L, Bigrams.hash("z", "jego"))
        assertEquals(-301744899490304790L, Bigrams.hash("cię", "trzeba"))
    }

    @Test
    fun `a pair the table holds beats one it does not`() {
        val model = table("pink" to "floyd" at 255, "pink" to "panther" at 100)

        val known = model.score("pink", "floyd")
        val rarer = model.score("pink", "panther")
        val unseen = model.score("pink", "pudełko")

        assertTrue(known > rarer, "a common pair should beat a rare one")
        assertTrue(rarer > unseen, "a rare pair should still beat one never seen")
        assertEquals(Bigrams.BACKOFF, unseen)
    }

    /**
     * An unseen pair is unlikely, not impossible.
     *
     * A model that answered "impossible" would delete from the search every sentence nobody has
     * written down before, which is most of the sentences anybody types into a television.
     */
    @Test
    fun `nothing is ruled out entirely`() {
        val model = table("pink" to "floyd" at 255)
        assertTrue(model.score("anything", "at all") > Double.NEGATIVE_INFINITY)
    }

    @Test
    fun `with no word before it, a word is judged on its own`() {
        assertEquals(0.0, table("pink" to "floyd" at 255).score(previous = null, word = "floyd"))
    }

    @Test
    fun `an empty table says nothing and admits it`() {
        val model = table()
        assertTrue(model.isEmpty)
        assertEquals(0.0, model.score("pink", "floyd"))
        assertTrue(LanguageModel.NONE.isEmpty)
    }

    @Test
    fun `hashes with the top bit set sort first, as the reader compares them`() {
        // `z jego` hashes negative and `ma ma` positive. Written in the other order the binary
        // search misses half the table and the model quietly says nothing at all.
        val model = table("z" to "jego" at 200, "ma" to "ma" at 120)

        assertTrue(abs(model.score("z", "jego") - Bigrams.BACKOFF) > 0.001)
        assertTrue(abs(model.score("ma", "ma") - Bigrams.BACKOFF) > 0.001)
    }

    private class Pair(val previous: String, val word: String, val score: Int)

    private infix fun kotlin.Pair<String, String>.at(score: Int) = Pair(first, second, score)

    /** The file `build.py` writes, written here so the reader can be tested without the corpus. */
    private fun table(vararg pairs: Pair): Bigrams {
        val sorted = pairs.sortedBy { Bigrams.hash(it.previous, it.word) }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            write(Bigrams.MAGIC.encodeToByteArray())
            writeByte(Bigrams.VERSION)
            writeShort(600) // a span of six nats, times a hundred
            writeInt(sorted.size)
            for (pair in sorted) {
                writeLong(Bigrams.hash(pair.previous, pair.word))
                writeByte(pair.score)
            }
            flush()
        }
        return Bigrams.read(bytes.toByteArray().inputStream())
    }
}
