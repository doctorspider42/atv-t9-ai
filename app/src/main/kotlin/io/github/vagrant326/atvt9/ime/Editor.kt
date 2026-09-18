package io.github.vagrant326.atvt9.ime

/**
 * The field, as everything above it needs to see it.
 *
 * Exists so the decisions can be tested without Android. Every bug found on the television so far
 * has lived in `T9ImeService` - a held `2` typing `a2`, the arrows moving the caret instead of
 * walking the readings, spelling announcing itself and then doing nothing - and that is the one
 * class in this project with no tests, because it is an `InputMethodService` and needs a device to
 * exist at all. A hundred and eight tests sat in `core`, none of them anywhere near where the bugs
 * were.
 *
 * None of those were Android bugs. Each was a press that two mechanisms both believed was theirs,
 * which is ordinary logic and testable the moment the field is something other than a system
 * object. So it is this: five calls, no state, and a fake of it in the tests that records what was
 * written.
 */
interface Editor {

    /** Puts text in at the cursor, as committed text the keyboard will not revise. */
    fun commit(text: String)

    /** Shows text inline as still being chosen, replacing whatever was shown before. */
    fun compose(text: String)

    /** Settles whatever was being shown inline, leaving it in the field. */
    fun finishComposing()

    /** Removes [count] characters before the cursor. */
    fun deleteBefore(count: Int)

    /** Deletes back to the start of the word behind the cursor. */
    fun deleteWord()
}
