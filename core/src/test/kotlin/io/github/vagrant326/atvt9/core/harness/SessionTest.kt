package io.github.vagrant326.atvt9.core.harness

import io.github.vagrant326.atvt9.core.DictionaryWriter
import io.github.vagrant326.atvt9.core.UserDictionary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The harness is a measuring instrument, so it gets the same tests as the thing it measures.
 *
 * Every case here is written as a script, because a script is what the harness will be asked for
 * once the decoder lands: a run that found something by hand on the window becomes a line in a
 * test by copying the keys that produced it.
 */
class SessionTest {

    // los, kos and kop all spell 5-6-7, which is the collision the whole method turns on.
    private val dictionary = DictionaryWriter.of(
        "los" to 900_000,
        "kos" to 4_000,
        "kop" to 300,
        "kot" to 5_000,
        "źle" to 900,
    )

    private fun session(user: UserDictionary = UserDictionary()) = Session(dictionary, user)

    @Test
    fun `a word and a space reach the field`() {
        val session = session()
        session.run("567 0")
        assertEquals("los ", session.committed)
    }

    @Test
    fun `NEXT chooses before the word is committed`() {
        val session = session()
        session.run("567 > 0")
        assertEquals("kos ", session.committed)
    }

    @Test
    fun `walking back wraps to the end of the strip`() {
        val session = session()
        session.run("567 <")
        assertEquals(session.candidates.last().word, session.field)
    }

    @Test
    fun `the case applies to the word and then lapses`() {
        val session = session()
        session.run("^ 567 0 567 0")
        assertEquals("Los los ", session.committed)
    }

    @Test
    fun `a locked case shouts every word`() {
        val session = session()
        session.run("^^ 567 0 567 0")
        assertEquals("LOS LOS ", session.committed)
    }

    @Test
    fun `the mark cycle replaces in place`() {
        val session = session()
        session.run("567 1 1")
        assertEquals("los,", session.committed)
    }

    @Test
    fun `spelling reaches a word the dictionary has not got`() {
        val session = session()
        // 9 is w-x-y-z-ź-ż and 4 is g-h-i: four taps of 9, a settle, then one of 4.
        session.run("~ 9999 . 4 #")
        assertEquals("zg", session.committed)
    }

    @Test
    fun `delete empties the word first and the field second`() {
        val session = session()
        session.run("567 0 567 * * * *")
        assertEquals("los", session.committed)
    }

    @Test
    fun `OK with nothing pending submits the field`() {
        val session = session()
        session.run("567 0 567 #  #")
        assertEquals(listOf("los los"), session.submitted)
        assertEquals("", session.committed)
    }

    @Test
    fun `the numpad can be read as a remote, which is upside down to it`() {
        // The middle row and the space key are the same either way up, and the number row is a
        // line rather than a grid, so nothing there is turned at all.
        assertEquals("789456123", "123456789".map { Pad.REMOTE.read(it, fromNumpad = true) }.joinToString(""))
        assertEquals("123456789", "123456789".map { Pad.REMOTE.read(it, fromNumpad = false) }.joinToString(""))
        assertEquals("123456789", "123456789".map { Pad.NUMPAD.read(it, fromNumpad = true) }.joinToString(""))
        assertEquals('0', Pad.REMOTE.read('0', fromNumpad = true))
    }

    @Test
    fun `a spelled word is learnt, which is what the warm figure measures`() {
        val session = session()
        session.run("~ 9999 . 4 #")
        assertTrue(session.user.contains("zg"))

        // And the second time it costs one press per letter, which is the whole claim.
        session.run("94")
        assertEquals("zg", session.field.removePrefix(session.committed))
    }
}
