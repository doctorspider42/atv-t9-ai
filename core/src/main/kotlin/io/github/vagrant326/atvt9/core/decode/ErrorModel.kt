package io.github.vagrant326.atvt9.core.decode

import kotlin.math.ln

/**
 * What a press costs when it is not the press that was meant.
 *
 * Every number here was counted rather than chosen. `corpus/errors.py` aligns what somebody typed
 * at full speed against what the phrase should have produced, and the rates below are that
 * alignment over 1,989 presses of Polish copied out at five keys a second. The defaults are
 * written as the rates, not as the costs, so that re-measuring is a matter of replacing numbers
 * that mean something.
 *
 * Costs are log-probabilities relative to a correct press, so a correct press is free and
 * everything else is negative. They are added, never multiplied: a beam that multiplied
 * probabilities across a sentence would underflow inside it.
 *
 * What the measurement said, and what the model does about it:
 *
 *  - **A doubled press is swallowed one time in six**, against one time in seventy for a press
 *    standing on its own — an order of magnitude apart. On a keypad where `mnońó` share a key,
 *    `on`, `no` and `nn` are all two presses of `6`, so this is the commonest error there is and
 *    it gets its own, much cheaper, transition.
 *  - **Slips land next door.** Four in five wrong keys were a physically adjacent key, diagonals
 *    included, so a substitution is priced by where the keys are rather than at one flat rate.
 *  - **Nothing double-fired.** Not once in 1,983 presses, so there is no transition for it. The
 *    model is smaller for having been measured.
 */
data class ErrorModel(
    /** How often a press lands on the key that was meant: 96.5%. */
    val correct: Double = 0.965,

    /** How often it lands on a physically adjacent key, over all such keys: 1.0%. */
    val adjacent: Double = 0.010,

    /** How often it lands somewhere else entirely: 0.2%. */
    val distant: Double = 0.002,

    /** How often a press arrives that was never meant: 2.0%. */
    val extra: Double = 0.020,

    /** How often one of two presses of the same key fails to arrive: 16.4%. */
    val swallowedRepeat: Double = 0.164,

    /** How often a press standing on its own fails to arrive: 1.4%. */
    val missed: Double = 0.014,

    /** How often the space between two words is simply skipped: 3.3%. */
    val skippedSpace: Double = 0.033,
) {

    /** Roughly how many keys sit next to any given one on the grid, for splitting [adjacent]. */
    private val neighbours = 4.0

    val substituteAdjacent: Double = ln(adjacent / neighbours / correct)
    val substituteDistant: Double = ln(distant / neighbours / correct)
    val extraPress: Double = ln(extra / correct)
    val repeatPress: Double = ln(swallowedRepeat / (1.0 - swallowedRepeat))
    val missedPress: Double = ln(missed / correct)
    val noSpace: Double = ln(skippedSpace / (1.0 - skippedSpace))

    fun substitution(wanted: Char, pressed: Char): Double =
        if (Grid.areAdjacent(wanted, pressed)) substituteAdjacent else substituteDistant

    companion object {

        /** The rates as measured on 2026-09-17, which is what the defaults already are. */
        val MEASURED = ErrorModel()
    }
}

/**
 * Where the keys are, which is the remote's grid and not the keypad of whoever is testing.
 *
 * ITU E.161 runs `1 2 3` along the top and puts `0` under `2`, and every remote with a number pad
 * is laid out that way. The harness can read a PC numpad either way up — that is what its `pad`
 * setting is for, and why a typing record carries which one was in force — but by the time a
 * digit reaches the decoder it is a digit, and this is where it sits.
 */
object Grid {

    private val POSITION = mapOf(
        '1' to (0 to 0), '2' to (0 to 1), '3' to (0 to 2),
        '4' to (1 to 0), '5' to (1 to 1), '6' to (1 to 2),
        '7' to (2 to 0), '8' to (2 to 1), '9' to (2 to 2),
        '0' to (3 to 1),
    )

    /** Touching, diagonals included, which is how the slips in the record actually fell. */
    fun areAdjacent(one: Char, other: Char): Boolean {
        val here = POSITION[one] ?: return false
        val there = POSITION[other] ?: return false
        val rows = kotlin.math.abs(here.first - there.first)
        val columns = kotlin.math.abs(here.second - there.second)
        return maxOf(rows, columns) == 1
    }

    fun neighboursOf(key: Char): List<Char> =
        POSITION.keys.filter { it != key && areAdjacent(key, it) }
}
