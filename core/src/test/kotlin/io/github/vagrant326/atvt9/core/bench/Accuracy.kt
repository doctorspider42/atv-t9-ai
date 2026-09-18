package io.github.vagrant326.atvt9.core.bench

import io.github.vagrant326.atvt9.core.Dictionary
import io.github.vagrant326.atvt9.core.decode.BeamDecoder
import io.github.vagrant326.atvt9.core.decode.Bigrams
import io.github.vagrant326.atvt9.core.decode.LanguageModel
import io.github.vagrant326.atvt9.core.decode.Source
import io.github.vagrant326.atvt9.core.decode.Weights
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * How often the decoder recovers what was meant, from presses somebody really made.
 *
 * This is the figure the project turns on, and it exists because the harness records real typing:
 * every row of `bench/typing.tsv` is a phrase that was on screen and the presses that were
 * actually aimed at it, wrong ones and all. Nothing here is simulated and no noise is invented.
 *
 * KSPC, which `:core:bench` prints, answers a different question and a less interesting one now.
 * It counts presses on the assumption that they land where they were aimed; the whole premise
 * here is that one press in eighteen does not.
 *
 * Two baselines, because the number means nothing alone. **Exact** is what the shipped keyboard
 * does today: split on the spaces that were pressed, look each group up as typed, and take the
 * best word. **Top 1** and **top 3** are the decoder. The gap between them is what tolerating
 * mistakes is worth.
 */
fun main(arguments: Array<String>) {
    val options = arguments.toList().chunked(2).associate { it[0] to it.getOrElse(1) { "" } }
    val record = File(options["--record"] ?: "bench/typing.tsv")
    if (!record.exists()) {
        System.err.println("no typing recorded at ${record.absolutePath} — run :core:harness")
        return
    }

    val dictionaries = listOf("pl", "en").associateWith { language ->
        File(options["--dictionary-$language"] ?: "app/src/main/assets/dictionary-$language.bin")
            .takeIf { it.exists() }
            ?.inputStream()
            ?.use { Dictionary.read(it) }
    }
    // The pairs are optional and their absence is the interesting comparison: the same benchmark
    // run with and without them is what says whether they were worth building.
    val models = listOf("pl", "en").associateWith { language ->
        val file = File(options["--bigrams-$language"] ?: "app/src/main/assets/bigrams-$language.bin")
        if (file.exists() && "--no-bigrams" !in options) {
            file.inputStream().use { Bigrams.read(it) }
        } else {
            LanguageModel.NONE
        }
    }
    for ((language, model) in models) {
        System.err.println("$language: " + if (model.isEmpty) "no word pairs" else "word pairs loaded")
    }

    val polish = dictionaries["pl"] ?: run {
        System.err.println("no Polish dictionary — run corpus/build.py")
        return
    }

    val rows = record.readLines()
        .drop(1)
        .map { it.split('\t') }
        .filter { it.size >= 6 }
        .map { Attempt(source = it[0], target = it[3], keys = it[4]) }

    println("${rows.size} attempts from ${record.path}\n")

    // Polish alone, then Polish and English together, because the second language is a real cost
    // and the only honest place to see it is on real input. The prior is what the user's own
    // committed words would set; 4:1 is a stand-in until there are enough of them to count.
    val configurations = listOfNotNull(
        "pl" to listOf(Source("pl", polish, prior = 1.0, model = models.getValue("pl"))),
        dictionaries["en"]?.let { english ->
            "pl + en" to listOf(
                Source("pl", polish, prior = 0.8, model = models.getValue("pl")),
                Source("en", english, prior = 0.2, model = models.getValue("en")),
            )
        },
    )

    // The weights are the only part of the decoder that was chosen rather than counted, so they
    // are fitted here against the same real presses everything else is measured on. A grid rather
    // than anything cleverer: there are two of them that matter, the surface is flat enough to see
    // by eye, and a number arrived at by a method nobody can follow is a number nobody will trust.
    if ("--tune" in options) {
        val sources = configurations.first().second
        println("weights, fitted on these attempts")
        println("%-10s %-8s %-8s %10s %10s".format("frequency", "space", "context", "phrases", "words"))
        for (frequency in listOf(0.35, 0.5, 0.7)) {
            for (space in listOf(1.0, 2.0)) {
                // What the word before is worth against what the word itself is worth. The pairs
                // have a range of nine nats and the frequencies fifteen, so at equal weight the
                // context is outvoted by construction rather than by evidence.
                for (context in listOf(0.0, 1.0, 2.0, 4.0, 8.0)) {
                val weights = Weights(
                    frequency = frequency,
                    space = space,
                    languageModel = context,
                )
                val decoder = BeamDecoder(sources, weights = weights, width = 32)
                var phrases = 0
                var right = 0
                var total = 0
                for (attempt in rows) {
                    val reading = decoder.decode(attempt.keys, limit = 1).firstOrNull()?.text
                    if (reading == attempt.target) phrases++
                    val wanted = attempt.target.split(' ')
                    val got = reading?.split(' ').orEmpty()
                    total += wanted.size
                    right += wanted.indices.count { got.getOrNull(it) == wanted[it] }
                }
                println(
                    "%-10.2f %-8.1f %-8.1f %9.1f%% %9.1f%%".format(
                        frequency,
                        space,
                        context,
                        100.0 * phrases / rows.size,
                        100.0 * right / total,
                    )
                )
                }
            }
        }
        return
    }

    // How many readings to look at. Three is what a strip holds; a larger number says whether the
    // right one is in the search at all, which is a different question from whether it is on top.
    val kept = options["--readings"]?.toIntOrNull() ?: 3

    for ((name, sources) in configurations) {
        val decoder = BeamDecoder(sources, width = options["--width"]?.toIntOrNull() ?: 64)
        println("== $name ==")
        for (group in rows.groupBy { it.source }.entries.sortedBy { it.key } + null) {
            val attempts = group?.value ?: rows
            val label = group?.key ?: "ALL"

            var exact = 0
            var first = 0
            var three = 0
            var missed = 0
            var possible = 0
            var rightWords = 0
            var bestWords = 0
            var coveredWords = 0
            var allWords = 0
            val elapsed = measureTimeMillis {
                for (attempt in attempts) {
                    val wantedWords = attempt.target.split(' ').filter { it.isNotEmpty() }
                    if (wantedWords.all { word -> sources.any { it.dictionary.contains(word) } }) {
                        possible++
                    }
                    coveredWords += wantedWords.count { word ->
                        sources.any { it.dictionary.contains(word) }
                    }
                    if (exactReading(attempt.keys, sources.first().dictionary) == attempt.target) {
                        exact++
                    }
                    val readings = decoder.decode(attempt.keys, limit = kept).map { it.text }

                    // A four-word phrase is wrong if one word is, so the phrase figure punishes a
                    // near miss exactly as hard as nonsense. The word figure is what says which of
                    // the two happened, and most of the failures turn out to be one word.
                    val wanted = attempt.target.split(' ')
                    val got = readings.firstOrNull()?.split(' ').orEmpty()
                    allWords += wanted.size
                    rightWords += wanted.indices.count { got.getOrNull(it) == wanted[it] }

                    // What a better ranking could buy without touching the search: the best the
                    // readings already contain. Everything between this and the line above is
                    // reachable by knowing which word follows which, and nothing above it is.
                    bestWords += readings.maxOfOrNull { reading ->
                        val words = reading.split(' ')
                        wanted.indices.count { words.getOrNull(it) == wanted[it] }
                    } ?: 0

                    when {
                        readings.isEmpty() -> missed++
                        readings.first() == attempt.target -> { first++; three++ }
                        attempt.target in readings -> three++
                    }
                }
            }

            println("%-14s %3d attempts".format(label, attempts.size))
            println(
                "   ceiling      phrases %5.1f%%   words %5.1f%%   (what the dictionaries hold)"
                    .format(100.0 * possible / attempts.size, 100.0 * coveredWords / allWords)
            )
            println(
                "   as typed     phrases %5.1f%%".format(100.0 * exact / attempts.size)
            )
            println(
                "   decoded      phrases %5.1f%%   top 3 %5.1f%%   words %5.1f%%   %d ms each"
                    .format(
                        100.0 * first / attempts.size,
                        100.0 * three / attempts.size,
                        100.0 * rightWords / allWords,
                        elapsed / attempts.size,
                    )
            )
            println(
                "   best reading words %5.1f%%   (what ranking alone could still win)"
                    .format(100.0 * bestWords / allWords)
            )
        }

        // A number says the decoder is wrong; only the readings say what it is wrong about. The
        // ones worth printing are those the dictionary could have got: anything else is a
        // vocabulary problem wearing a decoding problem's clothes.
        val examples = options["--examples"]?.toIntOrNull() ?: 0
        if (examples > 0) {
            println()
            rows.filter { isInDictionary(it.target, sources) }
                .mapNotNull { attempt ->
                    val reading = decoder.decode(attempt.keys, limit = 1).firstOrNull()?.text
                    if (reading == attempt.target) null else attempt to reading
                }
                .take(examples)
                .forEach { (attempt, reading) ->
                    println("  meant  ${attempt.target}")
                    println("  keys   ${attempt.keys}")
                    println("  read   " + (reading ?: "(nothing)"))
                    println()
                }
        }
        println()
    }
}

private data class Attempt(val source: String, val target: String, val keys: String)

/**
 * Whether every word of the phrase is in a dictionary at all, which is the ceiling on everything
 * below it.
 *
 * Printed because without it the headline figure cannot be read. A phrase carrying a word no
 * dictionary holds cannot come back however good the search is, and mistaking that for a decoding
 * failure would send the next fortnight into tuning weights that were never the problem.
 */
private fun isInDictionary(phrase: String, sources: List<Source>): Boolean =
    phrase.split(' ').filter { it.isNotEmpty() }.all { word ->
        sources.any { it.dictionary.contains(word) }
    }

/**
 * What the shipped keyboard produces from the same presses: every `0` is a word boundary and
 * every group is looked up exactly as typed.
 *
 * The baseline has to be this rather than something kinder, because this is what is installed.
 */
private fun exactReading(keys: String, dictionary: Dictionary): String? {
    val words = keys.split('0').filter { it.isNotEmpty() }.map { group ->
        dictionary.candidates(group, limit = 1).firstOrNull { it.exact }?.word ?: return null
    }
    return words.joinToString(" ")
}
