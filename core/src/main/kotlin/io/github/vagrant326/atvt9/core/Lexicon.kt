package io.github.vagrant326.atvt9.core

/**
 * Somewhere words can be looked up by the keys that spell them.
 *
 * Exists because the decoder was reading the shipped dictionary and nothing else, while the
 * keyboard went on writing every committed word into the user's own — learnt, saved, and never
 * read back. On the workload this project is for that is close to fatal: measured on the real
 * queries, more than half the wrong words are words no shipped dictionary holds, and they are the
 * ones the user has already typed once.
 *
 * Two implementations and they are nothing alike. [Dictionary] is a minimised, front-coded file
 * that cannot absorb a word without being rebuilt; [UserDictionary] is a few thousand words in a
 * hash map that changes on every committed word. Keeping them behind one interface is what lets
 * the decoder treat "what this language knows" and "what this household knows" as the same kind of
 * question, which is the only way the second can reach the beam at all.
 */
interface Lexicon {

    /** Whether any word starts with [digits], which is how a search knows a branch is dead. */
    fun hasPrefix(digits: String): Boolean

    /** Words for [digits], best first: what the keys spell, then what they complete to. */
    fun candidates(digits: String, limit: Int = Dictionary.DEFAULT_LIMIT): List<Candidate>

    /** Whether the word is already held, which is what stops one being learnt twice. */
    fun contains(word: String): Boolean
}
