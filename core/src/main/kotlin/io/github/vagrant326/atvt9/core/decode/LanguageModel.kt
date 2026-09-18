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
 * **What it stores is how much the first word changes the second, not how likely the pair is.**
 * The difference is the difference between a model that helps and one that destroys the decoder,
 * and it was learned by doing the other thing: storing the pair's own probability means every pair
 * the table does not hold needs a fixed cost, and that cost lands once per word — so a reading of
 * four words pays it four times and a reading of one pays it once, and the decoder stops reading
 * `dzikie ucho` and starts preferring `dzikiego`. Measured, word accuracy went from 61.8% to 40.6%
 * at a weight of one and to 11% at two.
 *
 * So the number is pointwise mutual information: how much likelier this word is after that one
 * than it is anywhere. A pair the table does not hold scores zero — the model has no information,
 * which is not the same as evidence against — and the word's own frequency, which the decoder
 * counts separately, is not counted twice.
 *
 * Format, big-endian, after the header:
 *
 *     magic     4 bytes  "T9B1"
 *     version   u8       2
 *     floor     i16      the lowest stored value, in nats times 100
 *     span      u16      the range the score byte covers, in nats times 100
 *     count     u32
 *     pairs     count x (u64 hash, u8 score)   ascending by hash
 */
class Bigrams private constructor(
    private val hashes: LongArray,
    private val scores: ByteArray,
    private val floor: Double,
    private val span: Double,
) : LanguageModel {

    override val isEmpty: Boolean get() = hashes.isEmpty()

    /**
     * The stored byte is the pair's mutual information scaled into 254 steps from [floor] up.
     * Unscaling here keeps the file small and the arithmetic in nats, which is what the rest of
     * the decoder speaks.
     */
    override fun score(previous: String?, word: String): Double {
        if (previous == null || hashes.isEmpty()) {
            return 0.0
        }
        val found = hashes.binarySearch(hash(previous, word))
        if (found < 0) {
            return NOTHING_KNOWN
        }
        return floor + ((scores[found].toInt() and 0xFF) - 1) * span / 254.0
    }

    companion object {

        const val MAGIC = "T9B1"
        const val VERSION = 2

        /**
         * What a pair the table does not hold is worth: nothing, in the arithmetical sense.
         *
         * Not a penalty. Two words that never met in a corpus of subtitles are the ordinary case
         * rather than an impossibility, and any fixed cost here is paid once per word — which
         * makes the model a tax on reading a query as several words instead of one, whatever else
         * it was meant to do.
         */
        const val NOTHING_KNOWN = 0.0

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
            require(bytes.size >= 13) { "bigrams are truncated: ${bytes.size} bytes" }
            require(String(bytes, 0, 4, Charsets.US_ASCII) == MAGIC) {
                "not a bigram table: magic was ${String(bytes, 0, 4, Charsets.US_ASCII)}"
            }
            require(bytes[4].toInt() == VERSION) { "bigram version ${bytes[4]} is not $VERSION" }

            var at = 5
            val floor = readShort(bytes, at).toShort() / 100.0
            at += 2
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
            return Bigrams(hashes, scores, floor, span)
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
