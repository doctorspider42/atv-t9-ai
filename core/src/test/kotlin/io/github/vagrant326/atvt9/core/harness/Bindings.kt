package io.github.vagrant326.atvt9.core.harness

import java.awt.event.KeyEvent
import java.util.Properties

/**
 * One key as the harness sees it: a code, and whether shift was held with it.
 *
 * Shift is part of the key rather than a modifier applied to it, because two of the remote's
 * gestures are holds — `0` for capitals, `1` for spelling — and a hold on a PC keyboard
 * auto-repeats. Shift standing in for the hold keeps the gesture one key, and that only works if
 * `0` and `Shift + 0` are two different things a binding can name.
 */
data class Stroke(val keyCode: Int, val shift: Boolean = false) {

    val text: String
        get() = (if (shift) "Shift + " else "") + KeyEvent.getKeyText(keyCode)

    fun encode(): String = "$keyCode/${if (shift) 1 else 0}"

    companion object {

        fun of(event: KeyEvent): Stroke = Stroke(event.keyCode, event.isShiftDown)

        fun decode(text: String): Stroke? {
            val parts = text.split('/')
            val code = parts.getOrNull(0)?.toIntOrNull() ?: return null
            return Stroke(code, parts.getOrNull(1) == "1")
        }
    }
}

/**
 * Everything the remote can do, as something a key can be pointed at.
 *
 * The order is the order they appear in the window, and it is the remote's order rather than a
 * tidy one: the number keys as they sit on the pad, then the keys around them. Somebody
 * rebinding is looking at a remote in their hand and finding the same thing in a list.
 */
enum class Bind(val digit: Char? = null) {

    KEY_1('1'),
    KEY_2('2'),
    KEY_3('3'),
    KEY_4('4'),
    KEY_5('5'),
    KEY_6('6'),
    KEY_7('7'),
    KEY_8('8'),
    KEY_9('9'),
    KEY_0('0'),
    NEXT,
    PREVIOUS,
    DELETE,
    DELETE_WORD,
    COMMIT,
    CAPS,
    SPELL,
    ABANDON,
    SETTLE,
    ;

    val action: Action
        get() = when (this) {
            KEY_0 -> Action.Space
            KEY_1 -> Action.Punctuation
            NEXT -> Action.Next
            PREVIOUS -> Action.Previous
            DELETE -> Action.Delete
            DELETE_WORD -> Action.DeleteWord
            COMMIT -> Action.Commit
            CAPS -> Action.Caps
            SPELL -> Action.Spell
            ABANDON -> Action.Abandon
            SETTLE -> Action.Settle
            else -> Action.Digit(digit!!)
        }
}

/**
 * Which key does what, and it is the user's answer rather than this file's.
 *
 * The defaults are a guess about one PC keyboard, and a guess is all they can be: remotes
 * disagree about which keys exist, keyboards disagree about where they are, and the person doing
 * the typing has a hand and a habit that neither of those knows about. So every one of them is
 * replaceable from the window, and what is replaced is written to disk — a tool whose main use is
 * typing into it is a tool nobody should have to set up twice.
 *
 * A stroke belongs to one bind at a time. Binding a key that is already spoken for takes it away
 * from whatever had it, because the alternative is two things happening at once and no way to see
 * from the list why.
 */
class Bindings private constructor(private val strokes: MutableMap<Bind, MutableList<Stroke>>) {

    operator fun get(bind: Bind): List<Stroke> = strokes[bind].orEmpty()

    fun bindOf(stroke: Stroke): Bind? =
        strokes.entries.firstOrNull { stroke in it.value }?.key

    fun actionFor(stroke: Stroke): Action? = bindOf(stroke)?.action

    /** Points [bind] at [stroke] alone, and takes the stroke off whatever else was holding it. */
    fun rebind(bind: Bind, stroke: Stroke) {
        strokes.values.forEach { it.remove(stroke) }
        strokes[bind] = mutableListOf(stroke)
    }

    /**
     * Turns the number pad over, leaving everything else alone.
     *
     * A remote runs `1 2 3` along the top of its number pad and a PC numpad runs `7 8 9`: the same
     * nine keys in the same grid, upside down. Worth nothing while presses are read literally, and
     * the whole experiment once the decoder starts modelling which key was *meant* — a slip of the
     * thumb has to land on the digit it would land on at home.
     */
    fun apply(pad: Pad) {
        for (bind in Bind.entries) {
            val digit = bind.digit ?: continue
            strokes[bind] = defaultStrokes(digit, pad).toMutableList()
        }
    }

    /** Back to the defaults, every bind of them, for a keyboard nobody can type on any more. */
    fun restore(pad: Pad) {
        strokes.clear()
        strokes.putAll(defaults(pad).strokes)
    }

    /** Writes into [properties] rather than over a file, so one file can hold every setting. */
    fun save(properties: Properties) {
        for ((bind, keys) in strokes) {
            properties.setProperty(bind.name, keys.joinToString(",") { it.encode() })
        }
    }

    companion object {

        fun defaults(pad: Pad = Pad.NUMPAD): Bindings {
            val strokes = LinkedHashMap<Bind, MutableList<Stroke>>()
            for (bind in Bind.entries) {
                strokes[bind] = when (val digit = bind.digit) {
                    null -> fixed(bind).toMutableList()
                    else -> defaultStrokes(digit, pad).toMutableList()
                }
            }
            return Bindings(strokes)
        }

        /**
         * The bindings on disk, falling back to the defaults for anything the file does not name.
         *
         * Missing rather than empty: a bind the file says nothing about keeps its default, so a
         * file written by an older harness does not silently leave the newest gesture unreachable.
         * An explicitly empty value is respected, because unbinding a key is a thing somebody may
         * have meant.
         */
        fun load(properties: Properties, pad: Pad = Pad.NUMPAD): Bindings {
            val bindings = defaults(pad)
            for (bind in Bind.entries) {
                val stored = properties.getProperty(bind.name) ?: continue
                bindings.strokes[bind] = stored.split(',')
                    .mapNotNull(Stroke::decode)
                    .toMutableList()
            }
            return bindings
        }

        /**
         * Both the number row and the numpad, because a laptop has one and a desk has the other.
         *
         * Only the numpad is turned by [Pad]: the number row is a line and has no geometry to
         * preserve, so turning it would trade a real correspondence for a confusing one.
         */
        private fun defaultStrokes(digit: Char, pad: Pad): List<Stroke> {
            val row = KeyEvent.VK_0 + (digit - '0')
            val numpad = KeyEvent.VK_NUMPAD0 + (pad.read(digit, fromNumpad = true) - '0')
            return listOf(Stroke(row), Stroke(numpad))
        }

        private fun fixed(bind: Bind): List<Stroke> = when (bind) {
            Bind.NEXT -> listOf(Stroke(KeyEvent.VK_RIGHT), Stroke(KeyEvent.VK_DOWN))
            Bind.PREVIOUS -> listOf(Stroke(KeyEvent.VK_LEFT))
            // Up deletes, as it does on the remote, where it is the one delete that is never
            // conditional on anything. Backspace as well, because a PC keyboard has one and
            // nobody's hand believes that up is where deleting lives.
            Bind.DELETE -> listOf(Stroke(KeyEvent.VK_UP), Stroke(KeyEvent.VK_BACK_SPACE))
            Bind.DELETE_WORD -> listOf(
                Stroke(KeyEvent.VK_UP, shift = true),
                Stroke(KeyEvent.VK_BACK_SPACE, shift = true),
            )
            Bind.COMMIT -> listOf(Stroke(KeyEvent.VK_ENTER))
            Bind.CAPS -> listOf(Stroke(KeyEvent.VK_ADD), Stroke(KeyEvent.VK_0, shift = true))
            Bind.SPELL -> listOf(Stroke(KeyEvent.VK_MULTIPLY), Stroke(KeyEvent.VK_1, shift = true))
            Bind.ABANDON -> listOf(Stroke(KeyEvent.VK_ESCAPE))
            Bind.SETTLE -> listOf(Stroke(KeyEvent.VK_DIVIDE))
            else -> emptyList()
        }
    }
}
