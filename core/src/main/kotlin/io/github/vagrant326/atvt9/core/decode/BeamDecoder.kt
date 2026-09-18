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
 *
 * **It remembers the last query it read.** Asked for the same presses with one more on the end —
 * which is what typing is — it extends the search it already has rather than starting again. Left
 * to start again, the work per press grows with the word: measured on a device, one key took
 * 384 ms and six took 2,889, which is a keyboard nobody can type on. Extending costs the same at
 * every length.
 *
 * Not safe to use from two threads. It is a keyboard: there is one thumb.
 */
class BeamDecoder(
    private val sources: List<Source>,
    private val errors: ErrorModel = ErrorModel.MEASURED,
    private val weights: Weights = Weights(),
    /**
     * How many readings survive each press.
     *
     * Thirty-two, because sixty-four is not better: measured on the recorded attempts, both reach
     * 61.6% of words and the wider one takes twice as long. Sixteen costs a point and a half and
     * halves it again, which is the trade to make if a television turns out to need it.
     */
    private val width: Int = 32,
    private val mistakesPerPress: Double = 1.2,
    private val mistakesAllowed: Double = 7.0,
) : Decoder {

    /**
     * Whether a prefix can still become a word, and what it reads as when it is one.
     *
     * On the decoder rather than on one search, because the answer depends only on the
     * dictionaries: the same prefix asked about in two queries has the same answer, and on a
     * television the second answer should not cost a binary search through a file to find out.
     */
    /**
     * Whether any language here has something to say about word order.
     *
     * When none has, two readings that differ only in a word already finished are worth the same
     * from here on and may be merged — which is the beam's width back. When one has, they are not,
     * and merging them would decide the question before the evidence arrives. That state is what
     * the model costs before it says anything at all.
     */
    private val contextless: Boolean = sources.all { it.model.isEmpty }

    private val live = HashMap<String, Boolean>()
    private val readings = HashMap<String, List<Reading>>()

    /** The search so far, and the presses it was built from. See the note about typing above. */
    private var read: String = ""
    private val levels = ArrayList<HashMap<Key, State>>()

    override fun decode(keys: String, limit: Int): List<Hypothesis> {
        if (keys.isEmpty() || limit <= 0 || sources.isEmpty()) {
            return emptyList()
        }

        // How much of the last search still applies. Usually all of it: a press is appended and
        // nothing before it moves. A delete or a new query shortens this and the rest is redone.
        var shared = 0
        while (shared < read.length && shared < keys.length && read[shared] == keys[shared]) {
            shared++
        }
        if (levels.isEmpty()) {
            levels.add(
                HashMap<Key, State>().also {
                    it[Key("", null, null)] = State("", emptyList(), 0.0, null, 0)
                }
            )
        }
        while (levels.size > shared + 1) {
            levels.removeAt(levels.size - 1)
        }

        for (at in shared until keys.length) {
            val next = HashMap<Key, State>()
            for (state in levels[at].values.sortedByDescending { it.score }.take(width)) {
                expand(keys[at], at, state, next)
            }
            levels.add(next)
        }
        read = keys

        if (live.size > REMEMBERED || readings.size > REMEMBERED) {
            live.clear()
            readings.clear()
        }

        // Every press can be explained as one that should not be there, so given enough licence
        // the search will explain any input as any sentence. A reading that has to assume more
        // mistakes than a thumb makes is not a worse answer than the right one — it is not an
        // answer, and the keyboard needs to hear that so it can offer spelling instead. The bound
        // is on the mistakes alone: a rare word is unlikely, not wrong.
        val allowed = -(mistakesAllowed + mistakesPerPress * keys.length)
        return levels[keys.length].values
            .filter { it.mistakes >= allowed }
            .sortedByDescending { it.score }
            .take(width)
            .mapNotNull { finish(it, keys.length) }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun expand(key: Char, at: Int, state: State, next: MutableMap<Key, State>) {

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

        val previous = state.words.lastOrNull()?.text

        return readings.map { reading ->
            val switch = when {
                state.language == null || reading.language == null -> 0.0
                state.language == reading.language -> 0.0
                else -> weights.languageSwitch
            }
            // What the word before says about this one, from the language this word was read as.
            // This is the whole reason the beam can no longer merge two readings that differ only
            // in a word already finished — see [Key].
            val context = sources[reading.source].model.score(previous, reading.word)
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
                    weights.languagePrior * reading.prior +
                    weights.languageModel * context,
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
    private fun finish(state: State, until: Int): Hypothesis? = when {
        state.words.isEmpty() && state.prefix.isEmpty() -> null
        state.prefix.isEmpty() -> Hypothesis(state.words, state.score)
        else -> close(state, until)
            .maxByOrNull { it.score }
            ?.let { Hypothesis(it.words, it.score) }
    }

    private fun isLive(prefix: String): Boolean = live.getOrPut(prefix) {
        sources.any { it.dictionary.hasPrefix(prefix) }
    }

    private fun readingsOf(prefix: String): List<Reading> = readings.getOrPut(prefix) {
        sources.mapIndexedNotNull { index, source ->
            val exact = source.dictionary.candidates(prefix, ALTERNATIVES)
                .filter(Candidate::exact)
                .takeIf { it.isNotEmpty() }
                ?: return@mapIndexedNotNull null
            Reading(
                source = index,
                word = exact.first().word,
                language = source.language,
                frequency = logProbabilityOf(exact.first().score),
                prior = kotlin.math.ln(source.prior),
                alternatives = exact.map { it.word },
            )
        }
    }

    private fun MutableMap<Key, State>.offer(state: State) {
        val key = Key(
            state.prefix,
            state.language,
            if (contextless) null else state.words.lastOrNull()?.text,
        )
        val existing = this[key]
        if (existing == null || state.score > existing.score) {
            this[key] = state
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

    /**
     * What makes two partial readings the same, and therefore mergeable.
     *
     * [previous] is in here because of the language model and only because of it: without one,
     * two readings of the same presses that differ only in a word already finished are worth the
     * same from here on, and keeping both wastes the beam. With one they are not — the next word
     * costs something different after each — so merging them would decide the question before the
     * evidence arrives. It is the price the model charges before it says anything.
     */
    private data class Key(val prefix: String, val language: String?, val previous: String?)

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
        val source: Int,
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

        /**
         * How many prefixes to remember before starting again.
         *
         * The caches answer from the dictionaries and never go stale, so they could be kept for
         * ever — but a keyboard runs for weeks and a bound nobody can reach is still a bound
         * worth having. Emptied whole rather than evicted one at a time: the cost of refilling is
         * a few binary searches and the cost of tracking what to evict is paid on every lookup.
         */
        const val REMEMBERED = 40_000
    }
}
