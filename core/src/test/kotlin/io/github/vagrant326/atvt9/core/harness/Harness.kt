package io.github.vagrant326.atvt9.core.harness

import io.github.vagrant326.atvt9.core.Composer
import io.github.vagrant326.atvt9.core.Dictionary
import io.github.vagrant326.atvt9.core.Keypad
import io.github.vagrant326.atvt9.core.UserDictionary
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * The keyboard, driven from a PC keyboard, with the strip and the state visible.
 *
 * A television is a slow place to find out that a word is missing: sideload, walk to the sofa,
 * type on a remote, and the only thing that comes back is the one word the strip had room for.
 * Here the numeric keypad stands in for the remote and everything the engine knows is on screen
 * — the sequence, every candidate with its score, whether the sequence matches at all, and what
 * the user dictionary has learnt so far. It is the same [Session] the scripted runs use, so
 * anything found by hand can be pinned as a script afterwards.
 *
 *     ./gradlew :core:harness
 *     ./gradlew :core:harness --args="--layout remote --language pl"
 *     ./gradlew :core:harness --args="--keys 2255 0 63736"
 *     ./gradlew :core:harness --args="--targets bench/queries-v1.tsv --record bench/typing.tsv"
 *     ./gradlew :core:harness --args="--text pan-tadeusz.txt --chunk 4"
 *
 * `--keys` runs a script and prints the result, which needs no display and belongs in CI.
 * Without it a window opens. The token alphabet is [Session.tokenOf], and `--layout` is [Pad].
 */
fun main(arguments: Array<String>) {
    val options = arguments.toList().chunked(2).associate { it[0] to it.getOrElse(1) { "" } }

    val dictionaries = LANGUAGES.associateWith { language ->
        val path = options["--dictionary-$language"] ?: "app/src/main/assets/dictionary-$language.bin"
        File(path).takeIf { it.exists() }?.inputStream()?.use { Dictionary.read(it) }
    }
    for ((language, dictionary) in dictionaries) {
        System.err.println(
            if (dictionary == null) {
                "$language: no dictionary — run corpus/build.py"
            } else {
                "$language: ${dictionary.wordCount} words"
            }
        )
    }

    val language = options["--language"]?.takeIf { it in LANGUAGES } ?: "pl"
    val script = options["--keys"]

    if (script != null) {
        val session = Session(dictionaries[language])
        session.run(script)
        println("keys   ${script.filter { !it.isWhitespace() }}")
        println("field  ${session.field}")
        for (query in session.submitted) {
            println("sent   $query")
        }
        println(
            "strip  " + session.candidates.joinToString(" ") { candidate ->
                (if (candidate.exact) "" else "~") + candidate.word + ":" + candidate.score
            }
        )
        return
    }

    val pad = options["--layout"]
        ?.let { name -> Pad.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        ?: Pad.NUMPAD

    // Two sources of phrases to type, and a book is the more useful of them. What is being
    // measured is a thumb against a key grid rather than a vocabulary, so an evening of copying
    // out any Polish text supplies what a query corpus of twenty-six lines never could.
    val text = options["--text"]?.let(::File)?.takeIf { it.exists() }
    val targets = when {
        text != null -> Recorder.fragmentsOf(text.readText(), options["--chunk"]?.toIntOrNull() ?: 4)
        else -> File(options["--targets"] ?: "bench/queries-v1.tsv")
            .takeIf { it.exists() }
            ?.let(Recorder::targetsFrom)
            .orEmpty()
    }
    val source = text?.nameWithoutExtension ?: "queries"
    val recording = File(options["--record"] ?: "bench/typing.tsv")

    SwingUtilities.invokeLater {
        Window(dictionaries, language, pad, targets, source, recording).isVisible = true
    }
}

private val LANGUAGES = listOf("pl", "en")

/**
 * Which digit a key on the numeric keypad stands for.
 *
 * A remote runs `1 2 3` along the top of its number pad; a PC numpad runs `7 8 9`. The same nine
 * keys in the same three-by-three grid, upside down — so a sequence practised here is typed with
 * the wrong fingers on the sofa, and, which matters more, a slip of the thumb lands on a
 * different digit in the two places. [REMOTE] reads the numpad as the remote's grid, so a
 * mistyping here is the mistyping that happens there; that is worth nothing today and is the
 * whole experiment the moment a decoder starts modelling which key was meant.
 *
 * Only the numpad is turned. The number row is a line and has no geometry to preserve, and a
 * script is logical digits from end to end and is never touched by this at all.
 */
enum class Pad {

    /** Each key means the digit printed on it. */
    NUMPAD,

    /** The numpad read as a remote: its top row is `7 8 9` and therefore means `1 2 3`. */
    REMOTE,
    ;

    fun read(digit: Char, fromNumpad: Boolean): Char = when {
        this == NUMPAD || !fromNumpad -> digit
        digit in '1'..'3' -> digit + 6
        digit in '7'..'9' -> digit - 6
        else -> digit // 4 5 6 are the middle row either way up, and 0 is not in the grid
    }

    fun next(): Pad = entries[(ordinal + 1) % entries.size]
}

/**
 * The window.
 *
 * Deliberately one panel of labels rather than a text field: a real editor would bring its own
 * caret, its own selection and its own idea of what a key means, and then the thing on screen
 * would be Swing's answer rather than the keyboard's. Everything here is drawn from [Session]
 * and nothing is drawn from a widget's state.
 */
private class Window(
    private val dictionaries: Map<String, Dictionary?>,
    private var language: String,
    private var pad: Pad,
    private val targets: List<String>,
    private val source: String,
    private val recording: File,
) : JFrame("atv-t9 harness") {

    private var session = Session(dictionaries[language], clock = System::currentTimeMillis)

    /** How much of `session.submitted` has reached the log already. */
    private var logged = 0

    /** Set while typing phrases for the record rather than typing text. See [Recorder]. */
    private var recorder: Recorder? = null

    private val fieldLabel = label(28f)
    private val stripLabel = label(18f)
    private val statusLabel = label(14f)
    private val log = JTextArea().apply {
        isEditable = false
        background = BACKGROUND
        foreground = DIM
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
    }

    init {
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        preferredSize = Dimension(920, 560)

        val root = JPanel(BorderLayout()).apply {
            background = BACKGROUND
            border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
            isFocusable = true
        }

        val top = JPanel().apply {
            background = BACKGROUND
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(fieldLabel)
            add(Box.createVerticalStrut(12))
            add(stripLabel)
            add(Box.createVerticalStrut(8))
            add(statusLabel)
        }

        root.add(top, BorderLayout.NORTH)
        root.add(JScrollPane(log).apply { border = BorderFactory.createEmptyBorder(12, 0, 0, 0) })
        root.add(legend(), BorderLayout.SOUTH)

        root.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) = handle(event)
        })

        contentPane = root
        pack()
        setLocationRelativeTo(null)
        SwingUtilities.invokeLater { root.requestFocusInWindow() }
        render()
    }

    private fun handle(event: KeyEvent) {
        if (event.keyCode == KeyEvent.VK_F5) {
            toggleRecording()
            render()
            return
        }
        recorder?.let {
            record(it, event)
            render()
            return
        }
        when {
            event.keyCode == KeyEvent.VK_F1 -> switchLanguage()
            event.keyCode == KeyEvent.VK_F2 -> reset(forget = true)
            event.keyCode == KeyEvent.VK_F3 -> reset(forget = false)
            event.keyCode == KeyEvent.VK_F4 -> pad = pad.next()
            else -> actionFor(event, pad)?.let(session::press) ?: return
        }
        render()
    }

    /**
     * Starts or stops typing phrases for the record.
     *
     * A separate mode rather than a log of ordinary use, because the two measure different
     * things. Ordinary use here is a person exploring a dictionary; a recording is a person
     * typing a known phrase as fast as they intend to on the sofa, and only the second one can
     * be aligned against what was meant.
     */
    private fun toggleRecording() {
        recorder = when {
            recorder != null -> {
                log.append("-- recording stopped\n")
                null
            }

            targets.isEmpty() -> {
                log.append("-- nothing to type: point --targets at a TSV of phrases\n")
                null
            }

            else -> Recorder(recording, targets, source).also {
                val resumed = if (it.position > 0) ", resuming at ${it.position + 1}" else ""
                log.append("-- $source: ${targets.size} phrases into ${recording.path}$resumed\n")
                log.append("-- type each one at full speed and press Enter; do not fix mistakes\n")
            }
        }
    }

    /**
     * The keys while recording, which are the remote's number keys and nothing else.
     *
     * No candidate walk, no case, no spelling. Every one of those is a decision taken while
     * reading the screen, and a recording made while reading the screen measures a different
     * activity from the one the decoder exists to survive.
     */
    private fun record(recorder: Recorder, event: KeyEvent) {
        val digit = digitOf(event.keyCode)?.let { pad.read(it, isNumpad(event.keyCode)) }
        when {
            digit != null -> recorder.press(digit, System.currentTimeMillis())
            event.keyCode == KeyEvent.VK_BACK_SPACE -> recorder.undo()
            event.keyCode == KeyEvent.VK_ESCAPE -> recorder.restart()
            event.keyCode == KeyEvent.VK_ENTER -> recorder.accept()
            event.keyCode == KeyEvent.VK_F6 -> recorder.skip()
            else -> return
        }
        if (recorder.isFinished) {
            log.append("-- ${targets.size} phrases done, written to ${recording.path}\n")
            this.recorder = null
        }
    }

    private fun switchLanguage() {
        language = LANGUAGES[(LANGUAGES.indexOf(language) + 1) % LANGUAGES.size]
        session.dictionary = dictionaries[language]
    }

    /**
     * A fresh field, and on F2 a fresh user dictionary with it.
     *
     * The distinction is the whole point of the two figures the benchmark prints. Cold is a word
     * the keyboard has never seen; warm is the same word typed a second time. Both are one
     * keypress away here, because a harness where the only reachable state is warm would flatter
     * the method exactly where it is weakest.
     */
    private fun reset(forget: Boolean) {
        val user = if (forget) UserDictionary() else session.user
        if (forget) {
            log.append("-- user dictionary cleared\n")
        }
        session = Session(dictionaries[language], user, clock = System::currentTimeMillis)
        logged = 0
    }

    private fun render() {
        recorder?.let {
            renderRecording(it)
            return
        }

        fieldLabel.text = html(
            span(escape(session.committed), FOREGROUND) +
                span(escape(session.field.removePrefix(session.committed)), ACCENT, underline = true) +
                span("|", DIM)
        )

        stripLabel.text = html(
            when {
                !session.isComposing -> span("(nothing pending)", DIM)
                session.mode == Composer.SPELL -> span("spelling", ACCENT)
                session.candidates.isEmpty() -> span("no match for ${session.sequence}", WARN)
                else -> session.candidates.mapIndexed { index, candidate ->
                    val word = escape(candidate.word) + (if (candidate.exact) "" else "…")
                    if (index == session.selected) {
                        span("[$word]", ACCENT)
                    } else {
                        span(" $word ", FOREGROUND)
                    }
                }.joinToString("")
            }
        )

        statusLabel.text = html(
            span(
                listOf(
                    "lang $language",
                    "pad ${pad.name.lowercase()}",
                    "keys ${session.sequence.ifEmpty { "—" }}",
                    "mode ${session.mode.name.lowercase()}",
                    "case ${session.case.name.lowercase()}",
                    "learnt ${session.user.size}",
                ).joinToString("   "),
                DIM,
            )
        )

        // The log is append-only and the session owns the list, so what has already been printed
        // is remembered here rather than consumed from under it.
        while (logged < session.submitted.size) {
            log.append("> " + session.submitted[logged++] + "\n")
        }
    }

    /**
     * The phrase to type and the digits typed so far, and nothing else on screen.
     *
     * The strip is absent deliberately: what it offers is what a person slows down to read, and
     * the recording is of a person not slowing down.
     */
    private fun renderRecording(recorder: Recorder) {
        fieldLabel.text = html(span(escape(recorder.target), FOREGROUND))
        stripLabel.text = html(span(recorder.pressed.ifEmpty { "—" }, ACCENT))
        statusLabel.text = html(
            span(
                listOf(
                    "recording ${recorder.position + 1} of ${recorder.targets.size}",
                    "pad ${pad.name.lowercase()}",
                    "Enter accepts   Esc restarts   F6 skips   F5 stops",
                ).joinToString("   "),
                DIM,
            )
        )
    }

    private fun label(size: Float) = JLabel().apply {
        foreground = FOREGROUND
        font = Font(Font.SANS_SERIF, Font.PLAIN, size.toInt())
        alignmentX = LEFT_ALIGNMENT
    }

    private fun legend() = JPanel(GridLayout(0, 2, 24, 2)).apply {
        background = BACKGROUND
        border = BorderFactory.createEmptyBorder(12, 0, 0, 0)
        for ((keys, meaning) in LEGEND) {
            add(label(13f).apply { foreground = FOREGROUND; text = keys })
            add(label(13f).apply { foreground = DIM; text = meaning })
        }
    }

    private companion object {

        val BACKGROUND: Color = Color(0x12, 0x14, 0x18)
        val FOREGROUND: Color = Color(0xEC, 0xEF, 0xF4)
        val ACCENT: Color = Color(0x7F, 0xC2, 0xFF)
        val WARN: Color = Color(0xFF, 0x9C, 0x6B)
        val DIM: Color = Color(0x80, 0x88, 0x96)

        /**
         * The remote, on a PC keyboard.
         *
         * The number keys map to themselves, which is the whole reason a numeric keypad is worth
         * using here. The rest are the keys the remote spends on one job each, and the holds are
         * given their own keys rather than a real hold: a hold on a PC keyboard auto-repeats, so
         * holding `0` would cycle the case as fast as the key repeat rate and nobody could aim it.
         */
        val LEGEND = listOf(
            "2 – 9" to "one press per letter",
            "0" to "finish the word, add a space",
            "1" to "cycle . , - ' & : /",
            "← →  /  ↓" to "walk the candidates",
            "↑  /  Backspace" to "delete",
            "Shift + Backspace" to "delete the word",
            "Enter" to "finish the word, or submit the field",
            "Esc" to "abandon the word",
            "+  (or Shift + 0)" to "capitals: abc → Abc → ABC",
            "*  (or Shift + 1)" to "spell it out, letter by letter",
            "/" to "end the multitap letter (the timeout)",
            "F1  /  F2  /  F3" to "language  /  clear what was learnt  /  clear the field",
            "F4" to "numpad as itself, or as a remote (789 becomes 123)",
            "F5" to "record: type the phrases shown, fast, mistakes and all",
        )

        /**
         * Which press an event is, or null for a key that is not on the remote.
         *
         * Numpad and number row both, because the numpad is what makes this feel like the remote
         * and the row is what a laptop has. `VK_ADD` and `VK_MULTIPLY` carry the two holds so the
         * gesture stays one key: `Shift + 0` is also accepted, but on a numpad Shift turns the
         * digit keys into their navigation meanings and the press never arrives as a digit at all.
         */
        fun actionFor(event: KeyEvent, pad: Pad): Action? {
            val digit = digitOf(event.keyCode)?.let { pad.read(it, isNumpad(event.keyCode)) }
            return when {
                digit != null && digit == '0' && event.isShiftDown -> Action.Caps
                digit != null && digit == '1' && event.isShiftDown -> Action.Spell
                digit != null && Keypad.isDigit(digit) -> Action.Digit(digit)
                digit == '0' -> Action.Space
                digit == '1' -> Action.Punctuation

                event.keyCode == KeyEvent.VK_ADD || event.keyCode == KeyEvent.VK_PLUS -> Action.Caps
                event.keyCode == KeyEvent.VK_MULTIPLY -> Action.Spell
                event.keyCode == KeyEvent.VK_DIVIDE -> Action.Settle

                event.keyCode == KeyEvent.VK_RIGHT || event.keyCode == KeyEvent.VK_DOWN -> Action.Next
                event.keyCode == KeyEvent.VK_LEFT -> Action.Previous

                event.keyCode == KeyEvent.VK_BACK_SPACE || event.keyCode == KeyEvent.VK_UP ->
                    if (event.isShiftDown) Action.DeleteWord else Action.Delete

                event.keyCode == KeyEvent.VK_ENTER -> Action.Commit
                event.keyCode == KeyEvent.VK_ESCAPE -> Action.Abandon
                else -> null
            }
        }

        fun digitOf(keyCode: Int): Char? = when (keyCode) {
            in KeyEvent.VK_0..KeyEvent.VK_9 -> '0' + (keyCode - KeyEvent.VK_0)
            in KeyEvent.VK_NUMPAD0..KeyEvent.VK_NUMPAD9 -> '0' + (keyCode - KeyEvent.VK_NUMPAD0)
            else -> null
        }

        fun isNumpad(keyCode: Int): Boolean = keyCode in KeyEvent.VK_NUMPAD0..KeyEvent.VK_NUMPAD9

        fun html(body: String) = "<html><body>$body</body></html>"

        fun span(text: String, colour: Color, underline: Boolean = false): String {
            val decoration = if (underline) ";text-decoration:underline" else ""
            val hex = "#%02x%02x%02x".format(colour.red, colour.green, colour.blue)
            return "<span style='color:$hex$decoration'>$text</span>"
        }

        /** The mark cycle includes `&`, and a label that is HTML would eat it. */
        fun escape(text: String): String = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace(" ", "&nbsp;")
    }
}
