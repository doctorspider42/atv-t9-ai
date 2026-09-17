package io.github.vagrant326.atvt9.core.decode

import io.github.vagrant326.atvt9.core.Dictionary

/**
 * The contract the decoder is built to, written before there was a decoder.
 *
 * Every decision here was free to make then and expensive to retrofit, because each of them is a
 * shape rather than a value — and [BeamDecoder] was written against all six without argument,
 * which is the only evidence that writing them down first was worth anything:
 *
 *  1. **A whole query goes in, not a word.** A decoder that cannot revise the second word when
 *     the eleventh key arrives cannot fix the thing this project exists to fix. Committing a word
 *     and dropping its keys — which is what `T9Engine` does today — makes that revision impossible
 *     no matter how good the model behind it is.
 *  2. **Languages are a list, not a setting.** One language plus a switch cannot type
 *     `piątek the series`, and `bench/queries-v1.tsv` is full of exactly that.
 *  3. **The language belongs to the word.** Not to the query and not to a mode, because the
 *     switch happens inside the phrase.
 *  4. **A word may have no language at all.** Everything learnt from the user is a proper noun
 *     far more often than not, and a proper noun is not Polish or English. [DecodedWord.language]
 *     is null for those, and the language prior does not apply to them.
 *  5. **Every hypothesis carries alternatives per word.** At speed nobody reads the strip, so the
 *     correction happens afterwards, on the word that came out wrong — which needs the beam to
 *     have kept what else that word could have been, position by position.
 *  6. **The weights are named and all present from the first version.** A weight added after the
 *     others are tuned means tuning all of them again, so the two that only matter with more than
 *     one language — [Weights.languagePrior] and [Weights.languageSwitch] — exist from the start
 *     even while they are held fixed.
 *
 * Measured, so the cost of point 2 is on the record rather than assumed: of 128,303 Polish key
 * sequences, 19,205 are also spelled by an English word — 15%. Weighted by how often the Polish
 * words are used, a prior of four to one on Polish loses first place on about 3.7% of them. That
 * is what a second language costs, and it buys English titles that are otherwise unreachable
 * without a language switch nobody presses mid-title.
 */
interface Decoder {

    /**
     * Reads [keys] — the whole query, digits `0`-`9` as pressed — and returns the best readings.
     *
     * Incremental by construction on the calling side: the caller passes the sequence again, one
     * key longer, and the implementation is free to keep whatever state makes that cheap. The
     * signature stays total because a decoder that can only be fed forwards cannot be tested
     * against a corpus of finished queries, and that corpus is how this will be measured.
     */
    fun decode(keys: String, limit: Int = 3): List<Hypothesis>
}

/**
 * One language the decoder may read a word as.
 *
 * [prior] is the share of words expected to be in this language, and it is a number about this
 * household rather than about the languages: it is estimated from what the user actually commits,
 * which is why it is data here and not a constant in the scoring function.
 */
data class Source(
    val language: String,
    val dictionary: Dictionary,
    val prior: Double,
    /**
     * What this language says about which word follows which.
     *
     * Per language rather than one for the query, because a pair is a fact about a language and a
     * query may change language halfway: `piątek the series` should have its Polish pair scored
     * against Polish and its English one against English, and a single table would have to have
     * been built from a corpus that is neither.
     */
    val model: LanguageModel = LanguageModel.NONE,
)

/**
 * What the score is made of, so each part can be tuned without touching the others.
 *
 * Log-domain throughout: these multiply probabilities, and a beam that added them in the linear
 * domain would underflow inside a sentence.
 */
data class Weights(
    /**
     * How much to trust the error model over the keys — what a wrong, missing or doubled press
     * costs. The prices themselves live in the error model, where they were measured; this says
     * how loudly they speak against the language.
     */
    val input: Double = 1.0,

    /** How likely the words are in that order. Carried but unused until the n-grams exist. */
    val languageModel: Double = 1.0,

    /**
     * How common the word is on its own, which is all a unigram decoder has.
     *
     * Below one because the byte the dictionary stores is a crude proxy for a probability and the
     * distribution it implies is too peaked against an error model measured to two decimal places.
     * Fitted at 0.5 on the recorded attempts; at 1.0 the whole thing loses ten points of word
     * accuracy, and what it loses them to is preferring one long rare word over two ordinary ones.
     */
    val frequency: Double = 0.5,

    /**
     * How dearly to hold a word boundary where no `0` was pressed.
     *
     * Separate from [input] although it is measured the same way, because it is the one error the
     * user makes on purpose: skipping the space is the point of the exercise, and how forgiving
     * to be about it is a decision rather than an observation.
     *
     * Fitted at 2, meaning twice as unwilling to split as the measurement alone would be — but
     * the measurement it is fitted against is of somebody copying out text who pressed the space
     * 96.7% of the time. For the workload this is all for, where the space is skipped on purpose,
     * this number is fitted on the wrong evidence and will have to be fitted again on the right.
     */
    val space: Double = 2.0,

    /** How much a word's language being the expected one is worth. */
    val languagePrior: Double = 1.0,

    /**
     * What it costs for the language to change between one word and the next, in nats.
     *
     * A cost rather than a multiplier, unlike the rest: there is no model behind it to scale.
     * `piątek the series` has to be able to pay it twice and still win.
     */
    val languageSwitch: Double = -0.8,
)

/**
 * One word of a reading, and everything needed to correct it afterwards.
 *
 * [from] and [until] index the *key sequence*, not the text, because that is what a correction
 * has to be expressed against: the letters are the decoder's answer and the keys are the
 * question, and only the question is fixed.
 */
data class DecodedWord(
    val text: String,
    /** The language this word was read as, or null for one that has none — see point 4. */
    val language: String?,
    val from: Int,
    val until: Int,
    /** What else these keys could have been, best first, for the correction UI. */
    val alternatives: List<String> = emptyList(),
)

/** One whole reading of the keys, and what it scored. */
data class Hypothesis(val words: List<DecodedWord>, val score: Double) {

    val text: String get() = words.joinToString(" ") { it.text }

    /** Whether the reading changes language partway, which is the case that must keep working. */
    val isMixed: Boolean
        get() = words.mapNotNull { it.language }.distinct().size > 1
}
