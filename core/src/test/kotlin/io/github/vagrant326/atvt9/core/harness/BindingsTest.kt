package io.github.vagrant326.atvt9.core.harness

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import java.util.Properties

class BindingsTest {

    @Test
    fun `a number key is bound on both the row and the numpad`() {
        val bindings = Bindings.defaults()

        assertEquals(
            listOf(Stroke(KeyEvent.VK_5), Stroke(KeyEvent.VK_NUMPAD5)),
            bindings[Bind.KEY_5],
        )
        assertEquals(Action.Digit('5'), bindings.actionFor(Stroke(KeyEvent.VK_NUMPAD5)))
    }

    @Test
    fun `reading the numpad as a remote turns it over and leaves the row alone`() {
        val bindings = Bindings.defaults().apply { apply(Pad.REMOTE) }

        // The remote's top row is 1 2 3 and sits where the numpad prints 7 8 9.
        assertEquals(Action.Punctuation, bindings.actionFor(Stroke(KeyEvent.VK_NUMPAD7)))
        assertEquals(Action.Digit('9'), bindings.actionFor(Stroke(KeyEvent.VK_NUMPAD3)))
        assertEquals(Action.Digit('5'), bindings.actionFor(Stroke(KeyEvent.VK_NUMPAD5)))
        assertEquals(Action.Digit('3'), bindings.actionFor(Stroke(KeyEvent.VK_3)))
    }

    @Test
    fun `the number keys are what the engine expects and 0 and 1 are not digits`() {
        val bindings = Bindings.defaults()

        assertEquals(Action.Space, bindings.actionFor(Stroke(KeyEvent.VK_0)))
        assertEquals(Action.Punctuation, bindings.actionFor(Stroke(KeyEvent.VK_1)))
        assertEquals(Action.Caps, bindings.actionFor(Stroke(KeyEvent.VK_0, shift = true)))
        assertEquals(Action.Spell, bindings.actionFor(Stroke(KeyEvent.VK_1, shift = true)))
    }

    @Test
    fun `binding a key takes it away from whatever had it`() {
        val bindings = Bindings.defaults()
        bindings.rebind(Bind.COMMIT, Stroke(KeyEvent.VK_NUMPAD5))

        assertEquals(Action.Commit, bindings.actionFor(Stroke(KeyEvent.VK_NUMPAD5)))
        assertEquals(listOf(Stroke(KeyEvent.VK_5)), bindings[Bind.KEY_5])
    }

    @Test
    fun `an unbound key is nobody's`() {
        assertNull(Bindings.defaults().actionFor(Stroke(KeyEvent.VK_F7)))
    }

    @Test
    fun `what was rebound survives being written and read back`() {
        val properties = Properties()
        Bindings.defaults().apply {
            rebind(Bind.SPELL, Stroke(KeyEvent.VK_TAB))
            save(properties)
        }

        val reloaded = Bindings.load(properties)
        assertEquals(Action.Spell, reloaded.actionFor(Stroke(KeyEvent.VK_TAB)))
        // And a bind the file said nothing about keeps the default it always had.
        assertEquals(Action.Commit, reloaded.actionFor(Stroke(KeyEvent.VK_ENTER)))
    }
}
