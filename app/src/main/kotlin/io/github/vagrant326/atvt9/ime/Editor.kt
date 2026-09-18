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

    /** Settles whatever was being shown inline, **leaving it in the field**. */
    fun finishComposing()

    /**
     * Takes back whatever was being shown inline, leaving nothing of it behind.
     *
     * The distinction from [finishComposing] is the whole reason this method exists, and it cost
     * a bug on the television to learn: `finishComposingText` does not erase the composing text,
     * it *keeps* it and stops calling it provisional. So a hold on `2` — which takes back the
     * press underneath it and then has nothing left to show — settled the `a` into the field and
     * typed `a2`. The fake editor in the tests threw the text away instead, which is why the test
     * for exactly that case passed while the television did not.
     *
     * Everything that abandons a word rather than accepting it comes here: the hold, `BACK`, and
     * a held delete over a word still in the air.
     */
    fun abandonComposing()

    /** Removes [count] characters before the cursor. */
    fun deleteBefore(count: Int)

    /** Deletes back to the start of the word behind the cursor. */
    fun deleteWord()
}
