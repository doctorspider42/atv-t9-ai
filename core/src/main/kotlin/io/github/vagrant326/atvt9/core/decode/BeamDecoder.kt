package io.github.vagrant326.atvt9.core.decode

import io.github.vagrant326.atvt9.core.Candidate
import io.github.vagrant326.atvt9.core.Keypad

/**
 * Reads a whole query of presses as a sentence, allowing for the presses being wrong.
 *
 * The keys are not treated as a sequence to look up but as evidence about a sequence that was
 * meant. Every step through the input may consume the press as typed, as a neighbouring key, as a
 * press that should not be there, or as one that hid a press which never arrived; and a word may
 * end wherever a word could end, whether or not a space was pressed. What comes out is not the
 * words the keys spell — often nothing spells them — but the sentence that best explains them.
 *
 * The transitions and their prices come from [ErrorModel], which is counted rather than invented,
 * and the measurement decided which transitions exist at all: there is one for a swallowed repeat
 * because a doubled press goes missing one time in six, and there is none for a double-fire
 * because in 1,983 recorded presses it never happened once.
 *
 * **What this version does not do yet.** The language model is unigram — words are scored by how
 * common they are and never by what preceded them — so [Weights.languageModel] is carried and not
 * yet used. Adding it changes the cost of a closed word and nothing about the search, which is
 * why the search is worth having first: it is the part that can be measured without it.
 *
 * Cost is a log-probability throughout. A correct press is free, everything else is negative, and
 * they add rather than multiply, because a sentence's worth of probabilities multiplied together
 * underflows.
 */
class BeamDecoder(
    private val sources: List<Source>,
    private val errors: ErrorModel = ErrorModel.MEASURED,
    private val weights: Weights = Weights(),
    private val width: Int = 64,
    private val mistakesPerPress: Double = 1.2,
    private val mistakesAllowed: Double = 7.0,
) : Decoder {

    override fun decode(keys: String, limit: Int): List<Hypothesis> {
        if (keys.isEmpty() || limit <= 0 || sources.isEmpty()) {
            return emptyList()
        }

        val search = Search(keys)
        return search.run().sortedByDescending { it.score }.take(limit)
    }

    /**
     * One decoding, and the caches that only make sense within it.
     *
     * A class per call rather than per decoder because the caches are keyed by digit prefix and a
     * prefix means nothing across two different inputs — but within one input the same prefix is
     * asked about hundreds of times, once per beam state that reached it by a different route.
     */
    private inner class Search(private val keys: String) {

        private val live = HashMap<String, Boolean>()
        private val words = HashMap<String, List<Reading>>()

        fun run(): List<Hypothesis> {
            // One bucket per position in the input. Every transition consumes exactly one press,
            // including the ones that account for presses that never happened, so the search
            // walks forward and never revisits a bucket it has left.
            val levels = Array(keys.length + 1) { HashMap<Key, State>() }
            levels[0][Key("", null)] = State("", emptyList(), 0.0, null, 0)

            for (at in 0 until keys.length) {
                for (state in levels[at].values.sortedByDescending { it.score }.take(width)) {
                    expand(at, state, levels[at + 1])
                }
            }

            // Every press can be explained as one that should not be there, so given enough
            // licence the search will explain any input as any sentence. A reading that has to
            // assume more mistakes than a thumb makes is not a worse answer than the right one —
            // it is not an answer, and the keyboard needs to hear that so it can offer spelling
            // instead. The bound is on the mistakes alone: a rare word is unlikely, not wrong.
            // A rate plus a flat allowance, because a rate alone cannot be set. One slip in a
            // three-press word is a third of it wrong, and a budget tight enough to refuse
            // nonsense on three presses refuses `kot` typed with one finger astray. The allowance
            // is about one adjacent slip, so a short word may have one and a long one may have
            // several, which is how they actually arrive.
            val allowed = -(mistakesAllowed + mistakesPerPress * keys.length)
            return levels[keys.length].values
                .filter { it.mistakes >= allowed }
                .sortedByDescending { it.score }
                .take(width)
                .mapNotNull { finish(it) }
        }

        private fun expand(at: Int, state: State, next: MutableMap<Key, State>) {
            val key = keys[at]

            // A press that should not be there. Cheap to say and expensive to leave out: it is
            // one press in fifty and without it a single stray press invalidates a whole query.
            val extra = weights.input * errors.extraPress
            next.offer(
                state.copy(score = state.score + extra, mistakes = state.mistakes + extra)
            )

            if (key == SPACE) {
                // The space was pressed, so a word ends here — but only if what is in hand is a
                // word. A `0` after nothing is a space in the text and costs nothing either.
                if (state.prefix.isEmpty()) {
                    next.offer(state)
                } else {
                    for (closed in close(state, at)) {
                        next.offer(closed)
                    }
                }
                return
            }

            for (letter in LETTERS) {
                val cost = when {
                    letter == key -> 0.0
                    Grid.areAdjacent(letter, key) -> errors.substituteAdjacent
                    else -> continue // a distant slip is one press in five hundred: not worth a branch
                }

                // As typed, or as typed with a press restored in front of it. The restored press
                // is far cheaper when it repeats what is already there, because that is what the
                // record says actually goes missing.
                extend(state, letter, at, weights.input * cost, next)
                for (missing in LETTERS) {
                    val restored = if (missing == letter) errors.repeatPress else errors.missedPress
                    extend(state, missing, at, weights.input * (cost + restored), next, then = letter)
                }

                // A word can also end where no space was pressed, which is the whole reason this
                // is a sentence decoder and not a word one.
                if (state.prefix.isNotEmpty()) {
                    for (closed in close(state, at, spacePressed = false)) {
                        extend(closed, letter, at, weights.input * cost, next)
                    }
                }
            }
        }

        /** Adds one or two letters to the word in hand, if any word still starts that way. */
        private fun extend(
            state: State,
            letter: Char,
            at: Int,
            cost: Double,
            next: MutableMap<Key, State>,
            then: Char? = null,
        ) {
            val prefix = state.prefix + letter + (then ?: "")
            if (prefix.length > LONGEST || !isLive(prefix)) {
                return
            }
            next.offer(
                state.copy(
                    prefix = prefix,
                    score = state.score + cost,
                    mistakes = state.mistakes + cost,
                    from = if (state.prefix.isEmpty()) at else state.from,
                )
            )
        }

        /**
         * Ends the word in hand, once per language that spells it.
         *
         * One state per language rather than the best of them, because which language a word was
         * read as changes what the next word costs — that is what [Weights.languageSwitch] is —
         * and collapsing them here would decide the question before the evidence arrives.
         */
        private fun close(state: State, at: Int, spacePressed: Boolean = true): List<State> {
            val readings = readingsOf(state.prefix)
            if (readings.isEmpty()) {
                return emptyList()
            }
            val boundary = if (spacePressed) 0.0 else weights.space * errors.noSpace

            return readings.map { reading ->
                val switch = when {
                    state.language == null || reading.language == null -> 0.0
                    state.language == reading.language -> 0.0
                    else -> weights.languageSwitch
                }
                State(
                    prefix = "",
                    mistakes = state.mistakes,
                    words = state.words + DecodedWord(
                        text = reading.word,
                        language = reading.language,
                        from = state.from,
                        until = at,
                        alternatives = reading.alternatives,
                    ),
                    score = state.score + boundary + switch +
                        weights.frequency * reading.frequency +
                        weights.languagePrior * reading.prior,
                    language = reading.language,
                    from = at + 1,
                )
            }
        }

        /**
         * A state at the end of the input is only an answer if it is not mid-word, and if it says
         * anything at all.
         *
         * The empty reading has to be refused explicitly. Every press can be explained as a press
         * that should not be there, so there is always a path that throws the whole input away,
         * and its cost — a fixed price per press — beats a real reading as soon as the real one
         * needs a rare word. It was outscoring `kot` on three presses.
         */
        private fun finish(state: State): Hypothesis? = when {
            state.words.isEmpty() && state.prefix.isEmpty() -> null
            state.prefix.isEmpty() -> Hypothesis(state.words, state.score)
            else -> close(state, keys.length)
                .maxByOrNull { it.score }
                ?.let { Hypothesis(it.words, it.score) }
        }

        private fun isLive(prefix: String): Boolean = live.getOrPut(prefix) {
            sources.any { it.dictionary.hasPrefix(prefix) }
        }

        private fun readingsOf(prefix: String): List<Reading> = words.getOrPut(prefix) {
            sources.mapNotNull { source ->
                val exact = source.dictionary.candidates(prefix, ALTERNATIVES)
                    .filter(Candidate::exact)
                    .takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                Reading(
                    word = exact.first().word,
                    language = source.language,
                    frequency = logProbabilityOf(exact.first().score),
                    prior = kotlin.math.ln(source.prior),
                    alternatives = exact.map { it.word },
                )
            }
        }

        private fun MutableMap<Key, State>.offer(state: State) {
            val key = Key(state.prefix, state.language)
            val existing = this[key]
            if (existing == null || state.score > existing.score) {
                this[key] = state
            }
        }
    }

    /**
     * What the shipped dictionary's byte of score is worth as a log-probability.
     *
     * `corpus/build.py` writes `1 + 254 * log1p(count) / log1p(commonest)`, so the byte is already
     * logarithmic and undoing it needs only the span it was scaled into. That span is the log of
     * the commonest word's count, which for these corpora is around fifteen — it is not in the
     * file, and putting it there is a format change worth making when the numbers it feeds are
     * being tuned rather than now.
     */
    private fun logProbabilityOf(score: Int): Double = (score - 255) * (LOG_SPAN / 254.0)

    private data class Key(val prefix: String, val language: String?)

    /**
     * [mistakes] is the part of [score] that came from the presses being wrong, kept apart so the
     * search can refuse a reading for needing too many of them. A rare word drags the score down
     * too, and refusing one for that would be refusing the proper nouns this keyboard is for.
     */
    private data class State(
        val prefix: String,
        val words: List<DecodedWord>,
        val score: Double,
        val language: String?,
        val from: Int,
        val mistakes: Double = 0.0,
    )

    private data class Reading(
        val word: String,
        val language: String?,
        val frequency: Double,
        val prior: Double,
        val alternatives: List<String>,
    )

    private companion object {

        val LETTERS = (Keypad.FIRST_DIGIT..Keypad.LAST_DIGIT).toList()

        /** Key `0`, which on this keyboard finishes the word and writes a space. */
        const val SPACE = '0'

        /** Long enough for `nieprawdopodobne`, short enough that a runaway prefix cannot grow. */
        const val LONGEST = 24

        const val ALTERNATIVES = 4
        const val LOG_SPAN = 15.0
    }
}
