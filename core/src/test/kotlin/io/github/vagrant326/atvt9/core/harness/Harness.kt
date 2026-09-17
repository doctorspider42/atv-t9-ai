package io.github.vagrant326.atvt9.core.harness

import io.github.vagrant326.atvt9.core.Composer
import io.github.vagrant326.atvt9.core.Dictionary
import io.github.vagrant326.atvt9.core.Keypad
import io.github.vagrant326.atvt9.core.UserDictionary
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.io.File
import java.util.Properties
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * The keyboard, driven from a PC keyboard, with the strip and the state visible.
 *
 * A television is a slow place to find out that a word is missing: sideload, walk to the sofa,
 * type on a remote, and the only thing that comes back is the one word the strip had room for.
 * Here a PC keyboard stands in for the remote and everything the engine knows is on screen — the
 * sequence, every candidate with its score, whether the sequence matches at all, and what the
 * user dictionary has learnt so far.
 *
 * **Everything is set from the window, and what is set is remembered.** The command line still
 * takes the same options, but nothing is only reachable that way: which key does what, which
 * language, which text to copy out, where the record goes. A tool whose main use is sitting and
 * typing into it is a tool nobody should have to configure by relaunching it.
 *
 *     ./gradlew :core:harness
 *     ./gradlew :core:harness --args="--keys 2255 0 63736"
 *     ./gradlew :core:harness --args="--text pan-tadeusz.txt --chunk 4"
 *
 * `--keys` runs a script and prints the result, which needs no display and belongs in CI. Without
 * it a window opens. The token alphabet is [Session.tokenOf].
 */
fun main(arguments: Array<String>) {
    val options = arguments.toList().chunked(2).associate { it[0] to it.getOrElse(1) { "" } }

    val settings = Settings(File(options["--settings"] ?: ".harness.properties"))
    options["--language"]?.takeIf { it in LANGUAGES }?.let { settings.language = it }
    options["--layout"]?.let { name ->
        Pad.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.let { settings.bindings.apply(it) }
    }
    options["--text"]?.let { settings.text = it }
    options["--targets"]?.let { settings.targets = it }
    options["--record"]?.let { settings.record = it }
    options["--chunk"]?.toIntOrNull()?.let { settings.chunk = it }
    options["--dictionary-pl"]?.let { settings.dictionary("pl", it) }
    options["--dictionary-en"]?.let { settings.dictionary("en", it) }

    val dictionaries = LANGUAGES.associateWith { language ->
        File(settings.dictionary(language)).takeIf { it.exists() }?.inputStream()
            ?.use { Dictionary.read(it) }
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

    val script = options["--keys"]
    if (script != null) {
        val session = Session(dictionaries[settings.language])
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

    SwingUtilities.invokeLater {
        Window(dictionaries, settings).apply {
            isVisible = true
            // Launched from Gradle, so it opens behind the terminal the build was started from.
            toFront()
        }
    }
}

private val LANGUAGES = listOf("pl", "en")

/**
 * Which digit a key on the numeric keypad stands for, as a starting point for the bindings.
 *
 * A remote runs `1 2 3` along the top of its number pad; a PC numpad runs `7 8 9`. The same nine
 * keys in the same three-by-three grid, upside down — so a sequence practised here is typed with
 * the wrong fingers on the sofa, and, which matters more, a slip of the thumb lands on a
 * different digit in the two places. [REMOTE] reads the numpad as the remote's grid, so a
 * mistyping here is the mistyping that happens there; that is worth nothing today and is the
 * whole experiment the moment a decoder starts modelling which key was meant.
 *
 * A preset rather than a mode: it writes the digit bindings and then has no further say, so
 * anything rebound by hand afterwards stays rebound.
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
}

/**
 * Everything the window can be set to, written to one file as it is set.
 *
 * Saved on every change rather than on exit. A harness is a thing people close by killing the
 * Gradle daemon, and a setting that survives only a graceful shutdown is a setting that will be
 * entered again tomorrow.
 */
class Settings(private val file: File) {

    private val properties = Properties().apply {
        if (file.exists()) {
            file.inputStream().use(::load)
        }
    }

    val bindings: Bindings = Bindings.load(properties)

    var language: String
        get() = properties.getProperty("language", "pl")
        set(value) = set("language", value)

    var text: String
        get() = properties.getProperty("text", "")
        set(value) = set("text", value)

    var targets: String
        get() = properties.getProperty("targets", "bench/queries-v1.tsv")
        set(value) = set("targets", value)

    var record: String
        get() = properties.getProperty("record", "bench/typing.tsv")
        set(value) = set("record", value)

    var chunk: Int
        get() = properties.getProperty("chunk", "4").toIntOrNull() ?: 4
        set(value) = set("chunk", value.toString())

    fun dictionary(language: String): String =
        properties.getProperty("dictionary-$language", "app/src/main/assets/dictionary-$language.bin")

    fun dictionary(language: String, path: String) = set("dictionary-$language", path)

    fun save() {
        bindings.save(properties)
        file.outputStream().use { properties.store(it, "atv-t9 harness") }
    }

    private fun set(key: String, value: String) {
        properties.setProperty(key, value)
        save()
    }
}

/**
 * The window: what is being typed on the left, everything that can be changed on the right.
 *
 * The typing area is deliberately labels rather than a text field. A real editor brings its own
 * caret, its own selection and its own idea of what a key means, and then what is on screen is
 * Swing's answer rather than the keyboard's. Everything shown is read from [Session] and nothing
 * from a widget.
 *
 * Keys are taken from a dispatcher on the focus manager rather than a listener on a panel,
 * because the controls on the right are real buttons: with a listener the first click would move
 * the focus and typing would stop working. The controls are all non-focusable for the same
 * reason — they answer to the mouse and never to the keyboard, so nothing typed can press one.
 */
private class Window(
    private val dictionaries: Map<String, Dictionary?>,
    private val settings: Settings,
) : JFrame("atv-t9 harness") {

    private var language = settings.language
    private var session = Session(dictionaries[language], clock = System::currentTimeMillis)

    /** How much of `session.submitted` has reached the log already. */
    private var logged = 0

    /** Set while typing phrases for the record rather than typing text. See [Recorder]. */
    private var recorder: Recorder? = null

    /** Set while waiting for the key that a bind is being pointed at. */
    private var capturing: Bind? = null

    private val fieldLabel = label(28f)
    private val stripLabel = label(18f)
    private val statusLabel = label(13f)
    private val log = JTextArea().apply {
        isEditable = false
        background = BACKGROUND
        foreground = DIM
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
    }

    private val bindButtons = Bind.entries.associateWith { bind ->
        button("") { capture(bind) }
    }
    private val recordButton = button("Start recording") { toggleRecording() }
    private val phrasesLabel = label(12f)
    private val recordToLabel = label(12f)
    private val chunkSpinner = JSpinner(SpinnerNumberModel(settings.chunk, 1, 20, 1)).apply {
        isFocusable = false
        addChangeListener {
            settings.chunk = value as Int
            refreshSources()
        }
    }

    init {
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        preferredSize = Dimension(1240, 800)

        val typing = JPanel(BorderLayout()).apply {
            background = BACKGROUND
            border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
            add(
                JPanel().apply {
                    background = BACKGROUND
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(fieldLabel)
                    add(Box.createVerticalStrut(12))
                    add(stripLabel)
                    add(Box.createVerticalStrut(8))
                    add(statusLabel)
                },
                BorderLayout.NORTH,
            )
            add(
                JScrollPane(log).apply {
                    border = BorderFactory.createEmptyBorder(12, 0, 0, 0)
                    // A scroll pane paints its own viewport, and Swing's default for that is
                    // white. Left alone it puts a lit panel in the middle of a dark window.
                    viewport.background = BACKGROUND
                    background = BACKGROUND
                }
            )
        }

        contentPane = JPanel(BorderLayout()).apply {
            background = BACKGROUND
            add(typing)
            add(controls(), BorderLayout.EAST)
        }

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { event ->
            event.id == KeyEvent.KEY_PRESSED && isActive && handle(event)
        }

        pack()
        setLocationRelativeTo(null)
        refreshBinds()
        refreshSources()
        render()
    }

    // ---- keys -------------------------------------------------------------------------------

    /** Returns whether the key was the keyboard's, and therefore must not reach anything else. */
    private fun handle(event: KeyEvent): Boolean {
        val stroke = Stroke.of(event)

        capturing?.let { bind ->
            capturing = null
            if (event.keyCode != KeyEvent.VK_ESCAPE) {
                settings.bindings.rebind(bind, stroke)
                settings.save()
            }
            refreshBinds()
            return true
        }

        val action = settings.bindings.actionFor(stroke) ?: return false
        recorder?.let {
            record(it, action)
            render()
            return true
        }
        session.press(action)
        render()
        return true
    }

    /**
     * Points a bind at whatever is pressed next: click the row's button, then press the key.
     *
     * Capture rather than a list to pick from. What somebody wants to say is "this key, the one
     * under my thumb", and they know it by where it is rather than by what Swing calls it — which
     * for the key this project most wanted on the remote turned out to be keycode 300.
     *
     * Escape cancels, and clicking a different row moves the capture rather than arming two.
     */
    private fun capture(bind: Bind) {
        refreshBinds()
        capturing = bind
        bindButtons.getValue(bind).apply {
            text = "press a key…"
            foreground = Color.BLACK
            background = ACCENT
            isOpaque = true
        }
    }

    /**
     * The keys while recording, which are the remote's number keys and nothing else.
     *
     * No candidate walk, no case, no spelling: every one of those is a decision taken while
     * reading the screen, and a recording made while reading the screen measures a different
     * activity from the one the decoder exists to survive. The three that remain are about the
     * recording rather than about the text — accept it, take back a press, start the phrase again.
     */
    private fun record(recorder: Recorder, action: Action) {
        when (action) {
            is Action.Digit -> recorder.press(action.digit, System.currentTimeMillis())
            Action.Space -> recorder.press('0', System.currentTimeMillis())
            Action.Commit -> recorder.accept()
            Action.Delete -> recorder.undo()
            Action.Abandon -> recorder.restart()
            else -> return
        }
        if (recorder.isFinished) {
            log.append("-- ${recorder.targets.size} phrases done, written to ${settings.record}\n")
            stopRecording()
        }
    }

    // ---- controls ---------------------------------------------------------------------------

    private fun controls(): Component {
        val panel = JPanel().apply {
            background = BACKGROUND
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(16, 8, 16, 16)
        }

        panel.add(heading("Dictionary"))
        panel.add(
            row(
                "language",
                JComboBox(LANGUAGES.toTypedArray()).apply {
                    isFocusable = false
                    selectedItem = language
                    addActionListener {
                        language = selectedItem as String
                        settings.language = language
                        session.dictionary = dictionaries[language]
                        render()
                    }
                },
            )
        )
        panel.add(row("", button("Clear what was learnt") { reset(forget = true) }))
        panel.add(row("", button("Clear the field") { reset(forget = false) }))

        panel.add(heading("Keys"))
        panel.add(
            row(
                "numpad reads as",
                JComboBox(Pad.entries.map { it.name.lowercase() }.toTypedArray()).apply {
                    isFocusable = false
                    addActionListener {
                        settings.bindings.apply(Pad.entries[selectedIndex])
                        settings.save()
                        refreshBinds()
                    }
                },
            )
        )
        for (bind in Bind.entries) {
            panel.add(row(labelOf(bind), bindButtons.getValue(bind)))
        }

        panel.add(heading("Recording"))
        panel.add(phrasesLabel)
        panel.add(row("", button("Copy out a text…") { chooseText() }))
        panel.add(row("", button("Type a query list…") { chooseTargets() }))
        panel.add(row("words per phrase", chunkSpinner))
        panel.add(recordToLabel)
        panel.add(row("", button("Write the record to…") { chooseRecord() }))
        panel.add(row("", recordButton))

        return JScrollPane(panel).apply {
            background = BACKGROUND
            viewport.background = BACKGROUND
            border = BorderFactory.createEmptyBorder()
            preferredSize = Dimension(370, 0)
            verticalScrollBar.unitIncrement = 16
        }
    }

    private fun heading(text: String) = label(12f).apply {
        foreground = ACCENT
        this.text = text.uppercase()
        border = BorderFactory.createEmptyBorder(14, 0, 5, 0)
        alignmentX = LEFT_ALIGNMENT
    }

    private fun row(name: String, control: Component) = JPanel(BorderLayout(8, 0)).apply {
        background = BACKGROUND
        maximumSize = Dimension(Int.MAX_VALUE, 26)
        alignmentX = LEFT_ALIGNMENT
        if (name.isNotEmpty()) {
            add(label(12f).apply { text = name }, BorderLayout.WEST)
        }
        add(control, BorderLayout.EAST)
    }

    private fun button(text: String, onClick: () -> Unit) = JButton(text).apply {
        isFocusable = false
        font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        addActionListener { onClick() }
    }

    /** The letters a number key carries, so the list reads like the remote rather than like code. */
    private fun labelOf(bind: Bind): String = when (bind) {
        Bind.KEY_0 -> "0   space"
        Bind.KEY_1 -> "1   marks"
        Bind.NEXT -> "next candidate"
        Bind.PREVIOUS -> "previous candidate"
        Bind.DELETE -> "delete"
        Bind.DELETE_WORD -> "delete the word"
        Bind.COMMIT -> "OK"
        Bind.CAPS -> "capitals"
        Bind.SPELL -> "spell it out"
        Bind.ABANDON -> "abandon the word"
        Bind.SETTLE -> "multitap timeout"
        else -> "${bind.digit}   ${Keypad.lettersOn(bind.digit!!)}"
    }

    private fun chooseText() = choose("Text to copy out")?.let { file ->
        settings.text = file.path
        refreshSources()
    }

    private fun chooseTargets() = choose("Query list (TSV)")?.let { file ->
        settings.text = ""
        settings.targets = file.path
        refreshSources()
    }

    private fun chooseRecord() = choose("Write the record to", save = true)?.let { file ->
        settings.record = file.path
        refreshSources()
    }

    private fun choose(title: String, save: Boolean = false): File? {
        val chooser = JFileChooser(File(".").absoluteFile)
        chooser.dialogTitle = title
        val answer = if (save) chooser.showSaveDialog(this) else chooser.showOpenDialog(this)
        return chooser.selectedFile.takeIf { answer == JFileChooser.APPROVE_OPTION }
    }

    // ---- recording --------------------------------------------------------------------------

    /**
     * The phrases to type: a text cut into fragments, or a list of queries.
     *
     * A book is the more useful of the two and that is not obvious. What is being measured is a
     * thumb against a key grid rather than a vocabulary, so an evening of copying out any Polish
     * text supplies what a query corpus of twenty-six lines never could.
     */
    private fun phrases(): Pair<String, List<String>> {
        val text = settings.text.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.exists() }
        if (text != null) {
            return text.nameWithoutExtension to Recorder.fragmentsOf(text.readText(), settings.chunk)
        }
        val targets = File(settings.targets).takeIf { it.exists() } ?: return "queries" to emptyList()
        return "queries" to Recorder.targetsFrom(targets)
    }

    private fun toggleRecording() {
        if (recorder != null) {
            log.append("-- recording stopped\n")
            stopRecording()
            return
        }

        val (source, targets) = phrases()
        if (targets.isEmpty()) {
            log.append("-- nothing to type: choose a text or a query list first\n")
            return
        }

        val recorder = Recorder(File(settings.record), targets, source)
        this.recorder = recorder
        recordButton.text = "Stop recording"
        val resumed = if (recorder.position > 0) ", resuming at ${recorder.position + 1}" else ""
        log.append("-- $source: ${targets.size} phrases into ${settings.record}$resumed\n")
        log.append("-- type each one at full speed and press OK; do not fix mistakes\n")
        render()
    }

    private fun stopRecording() {
        recorder = null
        recordButton.text = "Start recording"
        render()
    }

    // ---- rendering --------------------------------------------------------------------------

    /**
     * A fresh field, and on request a fresh user dictionary with it.
     *
     * The distinction is the whole point of the two figures the benchmark prints. Cold is a word
     * the keyboard has never seen; warm is the same word typed a second time. Both are one click
     * away here, because a harness whose only reachable state is warm would flatter the method
     * exactly where it is weakest.
     */
    private fun reset(forget: Boolean) {
        if (forget) {
            log.append("-- user dictionary cleared\n")
        }
        val user = if (forget) UserDictionary() else session.user
        session = Session(dictionaries[language], user, clock = System::currentTimeMillis)
        logged = 0
        render()
    }

    private fun refreshBinds() {
        capturing = null
        for ((bind, button) in bindButtons) {
            val keys = settings.bindings[bind]
            button.text = if (keys.isEmpty()) "—" else keys.joinToString(" / ") { it.text }
            button.isOpaque = false
            button.background = null
            button.foreground = null
        }
    }

    private fun refreshSources() {
        val (source, targets) = phrases()
        phrasesLabel.text = "$source — ${targets.size} phrases"
        recordToLabel.text = "record: ${settings.record}"
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
                "recording ${recorder.position + 1} of ${recorder.targets.size}" +
                    "   OK accepts   delete takes back a press   back starts the phrase again",
                DIM,
            )
        )
    }

    private fun label(size: Float) = JLabel().apply {
        foreground = FOREGROUND
        font = Font(Font.SANS_SERIF, Font.PLAIN, size.toInt())
        alignmentX = LEFT_ALIGNMENT
    }

    private companion object {

        val BACKGROUND: Color = Color(0x12, 0x14, 0x18)
        val FOREGROUND: Color = Color(0xEC, 0xEF, 0xF4)
        val ACCENT: Color = Color(0x7F, 0xC2, 0xFF)
        val WARN: Color = Color(0xFF, 0x9C, 0x6B)
        val DIM: Color = Color(0x80, 0x88, 0x96)

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
