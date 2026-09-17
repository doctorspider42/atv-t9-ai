package io.github.vagrant326.atvt9.core.decode

import java.io.InputStream

/**
 * How likely a word is, given the word before it.
 *
 * Measured, before this existed, on the recorded attempts: the decoder read 58.0% of words
 * correctly and the best of thirty-two readings it returned held 60.5%. The right reading was not
 * sitting second — it was not in the search at all. So this is asked *during* the search, once per
 * word as it closes, where it decides which partial readings survive; a model consulted afterwards
 * would be choosing between readings that no longer contain the answer.
 *
 * What it is for, precisely. `z` and `w` are the same key. So are `bo` and `co`, and so are
 * `opisuje` and `opisuję`, because `e` and `ę` share one. Nothing about how common those words are
 * on their own can separate them — for a given sequence the commoner one wins every time, for
 * ever. Only what came before them can.
 */
interface LanguageModel {

    /**
     * The log-probability of [word] following [previous], or of starting a query when it is null.
     *
     * Log, like everything else in the decoder, because these are added along a reading and a
     * sentence's worth of probabilities multiplied together underflows.
     */
    fun score(previous: String?, word: String): Double

    /** Whether this model has anything at all to say, so a caller can skip the state it costs. */
    val isEmpty: Boolean get() = false

    companion object {

        /** What the decoder uses when there is no model: every word as likely after any other. */
        val NONE: LanguageModel = object : LanguageModel {
            override fun score(previous: String?, word: String): Double = 0.0
            override val isEmpty: Boolean get() = true
        }
    }
}

/**
 * Word pairs and what they cost, as built by `corpus/build.py`.
 *
 * **Hashed rather than stored.** A pair is kept as a 64-bit hash of the two words and a score
 * byte, which is eleven bytes a pair in a sorted array and no strings at all — where storing the
 * words themselves would cost several times that and be read on a television. Two different pairs
 * can collide; at 64 bits over a few hundred thousand pairs that is a handful in the whole table,
 * and the cost of one is that a word occasionally looks likelier after something than it is. That
 * is a wrong ranking in a rare place, against a table several times smaller everywhere.
 *
 * **Backs off rather than refusing.** A pair the table does not hold is not impossible — it is
 * unseen, which for two words that never met in a corpus of subtitles is the ordinary case. It
 * costs [BACKOFF], a fixed penalty against the pairs that were seen, and the word's own frequency
 * still speaks for it. A model that answered "impossible" would delete from the search every
 * sentence nobody has written down before, which is most of the sentences anybody types.
 *
 * Format, big-endian, after the header:
 *
 *     magic     4 bytes  "T9B1"
 *     version   u8       1
 *     span      u16      the log range the score byte was scaled into, times 100
 *     count     u32
 *     pairs     count x (u64 hash, u8 score)   ascending by hash
 */
class Bigrams private constructor(
    private val hashes: LongArray,
    private val scores: ByteArray,
    private val span: Double,
) : LanguageModel {

    override val isEmpty: Boolean get() = hashes.isEmpty()

    /**
     * The stored byte is the log-probability scaled into 254 steps of [span], the commonest pair
     * at the top. Unscaling it here keeps the file small and the arithmetic in nats, which is what
     * everything else in the decoder speaks.
     */
    override fun score(previous: String?, word: String): Double {
        if (previous == null || hashes.isEmpty()) {
            return 0.0
        }
        val found = hashes.binarySearch(hash(previous, word))
        if (found < 0) {
            return BACKOFF
        }
        return ((scores[found].toInt() and 0xFF) - 255.0) * span / 254.0
    }

    companion object {

        const val MAGIC = "T9B1"
        const val VERSION = 1

        /**
         * What an unseen pair costs, in nats.
         *
         * Chosen rather than counted, and it is the one number here that is: it stands for every
         * pair the corpus never saw, which is by construction the thing there is no count for.
         * Six nats puts an unseen pair below the rarest seen one without putting it below the
         * difference between two ordinary words, so context can lose to frequency when it has
         * nothing to say.
         */
        const val BACKOFF = -6.0

        /**
         * One number for two words, which is what the table is keyed by.
         *
         * FNV-1a over the two words with a separator between them, so `ma ma` and `mam a` are
         * different pairs. Not a cryptographic hash and does not need to be: what is being
         * defended against is an accidental collision between two Polish word pairs, and
         * sixty-four bits is far more than that needs.
         */
        fun hash(previous: String, word: String): Long {
            var hash = -3750763034362895579L // FNV-1a 64-bit offset basis
            for (character in previous) {
                hash = (hash xor character.code.toLong()) * PRIME
            }
            hash = (hash xor ' '.code.toLong()) * PRIME
            for (character in word) {
                hash = (hash xor character.code.toLong()) * PRIME
            }
            return hash
        }

        private const val PRIME = 1099511628211L

        fun read(input: InputStream): Bigrams {
            val bytes = input.readBytes()
            // The header alone, which is what a table with nothing in it is.
            require(bytes.size >= 11) { "bigrams are truncated: ${bytes.size} bytes" }
            require(String(bytes, 0, 4, Charsets.US_ASCII) == MAGIC) {
                "not a bigram table: magic was ${String(bytes, 0, 4, Charsets.US_ASCII)}"
            }
            require(bytes[4].toInt() == VERSION) { "bigram version ${bytes[4]} is not $VERSION" }

            var at = 5
            val span = readShort(bytes, at) / 100.0
            at += 2
            val count = readInt(bytes, at)
            at += 4

            val hashes = LongArray(count)
            val scores = ByteArray(count)
            for (pair in 0 until count) {
                hashes[pair] = readLong(bytes, at)
                at += 8
                scores[pair] = bytes[at]
                at += 1
            }
            return Bigrams(hashes, scores, span)
        }

        private fun readShort(bytes: ByteArray, at: Int): Int =
            ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

        private fun readInt(bytes: ByteArray, at: Int): Int =
            ((bytes[at].toInt() and 0xFF) shl 24) or
                ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                ((bytes[at + 2].toInt() and 0xFF) shl 8) or
                (bytes[at + 3].toInt() and 0xFF)

        private fun readLong(bytes: ByteArray, at: Int): Long {
            var value = 0L
            for (step in 0 until 8) {
                value = (value shl 8) or (bytes[at + step].toLong() and 0xFF)
            }
            return value
        }
    }
}
