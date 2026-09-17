package io.github.vagrant326.atvt9.core.harness

import io.github.vagrant326.atvt9.core.Composer
import io.github.vagrant326.atvt9.core.Dictionary
import io.github.vagrant326.atvt9.core.Keypad
import io.github.vagrant326.atvt9.core.UserDictionary
import java.awt.BorderLayout
import java.awt.CardLayout
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
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import javax.swing.filechooser.FileNameExtensionFilter

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
 * takes the same options, but nothing is only reachable that way. The settings live on a screen
 * of their own rather than beside the text: what is watched while typing is one line and a strip,
 * and nineteen key bindings alongside them are nineteen things in the way.
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
        Pad.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { pad ->
            settings.pad = pad
            settings.bindings.apply(pad)
            settings.save()
        }
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

/** How long a phrase to copy out is. Cycled from a short list rather than typed into a field. */
private val CHUNKS = listOf(2, 3, 4, 5, 6, 8, 10)

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

    fun next(): Pad = entries[(ordinal + 1) % entries.size]
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

    var pad: Pad
        get() = Pad.entries.firstOrNull { it.name == properties.getProperty("pad") } ?: Pad.NUMPAD
        set(value) = set("pad", value.name)

    /**
     * Built on the stored preset rather than on the default one.
     *
     * Otherwise a bind the file happens not to name falls back to a default from the wrong layout,
     * and the screen then shows `numpad` above a row of keys that are plainly the other way up.
     */
    val bindings: Bindings = Bindings.load(properties, pad)

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

    fun dictionary(language: String): String = properties.getProperty(
        "dictionary-$language",
        "app/src/main/assets/dictionary-$language.bin",
    )

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
 * The window: one screen for typing, one for settings, and a strip of buttons above both.
 *
 * The typing screen is deliberately labels rather than a text field. A real editor brings its own
 * caret, its own selection and its own idea of what a key means, and then what is on screen is
 * Swing's answer rather than the keyboard's. Everything shown is read from [Session] and nothing
 * from a widget.
 *
 * Keys are taken from a dispatcher on the focus manager rather than a listener on a panel,
 * because the controls are real buttons: with a listener the first click would move the focus and
 * typing would stop working. The controls are all non-focusable for the same reason — they answer
 * to the mouse and never to the keyboard, so nothing typed can press one.
 *
 * Every control is drawn here rather than by the platform. The system look paints a light face
 * with dark text, which on a dark window is black on black; rather than fight it one widget at a
 * time, buttons are flat panels of known colour and the settings that have two or three values
 * are buttons that cycle through them.
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

    private var showing = TYPING

    private val screens = CardLayout()
    private val body = JPanel(screens).apply { background = BACKGROUND }

    private val fieldLabel = label(30f, FOREGROUND)
    private val stripLabel = label(18f, FOREGROUND)
    private val statusLabel = label(13f, DIM)
    private val log = JTextArea().apply {
        isEditable = false
        background = BACKGROUND
        foreground = DIM
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        border = BorderFactory.createEmptyBorder(6, 0, 6, 0)
    }

    private val bindButtons = Bind.entries.associateWith { bind -> button("") { capture(bind) } }
    private val languageButton = button("") { switchLanguage() }
    private val padButton = button("") { switchPad() }
    private val chunkButton = button("") { switchChunk() }
    private val textButton = button("") { chooseText() }
    private val targetsButton = button("") { chooseTargets() }
    private val recordToButton = button("") { chooseRecord() }
    private val dictionaryButton = button("") { chooseDictionary() }
    private val recordButton = button("Record") { toggleRecording() }
    private val phrasesLabel = label(13f, DIM)

    init {
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        preferredSize = Dimension(1180, 720)

        body.add(typingScreen(), TYPING)
        body.add(settingsScreen(), SETTINGS)

        contentPane = JPanel(BorderLayout()).apply {
            background = BACKGROUND
            border = BorderFactory.createEmptyBorder(14, 18, 14, 18)
            add(toolbar(), BorderLayout.NORTH)
            add(body)
        }

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { event ->
            event.id == KeyEvent.KEY_PRESSED && isActive && handle(event)
        }

        pack()
        setLocationRelativeTo(null)
        refresh()
    }

    // ---- keys -------------------------------------------------------------------------------

    /** Returns whether the key was the keyboard's, and therefore must not reach anything else. */
    private fun handle(event: KeyEvent): Boolean {
        val stroke = Stroke.of(event)

        capturing?.let { bind ->
            if (event.keyCode != KeyEvent.VK_ESCAPE) {
                settings.bindings.rebind(bind, stroke)
                settings.save()
            }
            capturing = null
            refresh()
            return true
        }

        // On the settings screen nothing is being typed into the keyboard, so a key means whatever
        // that screen makes of it and nothing is consumed on the keyboard's behalf.
        if (showing == SETTINGS) {
            if (event.keyCode == KeyEvent.VK_ESCAPE) {
                show(TYPING)
                return true
            }
            return false
        }

        val action = settings.bindings.actionFor(stroke) ?: return false
        recorder?.let {
            record(it, action)
            refresh()
            return true
        }
        session.press(action)
        refresh()
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
        capturing = bind
        refresh()
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
            // Walking back through the candidates means nothing while recording, and walking back
            // through the phrases is the thing that was missing, so the key keeps its direction.
            Action.Previous -> takeBack()
            else -> return
        }
        if (recorder.isFinished) {
            note("${recorder.targets.size} phrases done, written to ${settings.record}")
            stopRecording()
        }
    }

    // ---- screens ----------------------------------------------------------------------------

    private fun show(screen: String) {
        showing = screen
        screens.show(body, screen)
        refresh()
    }

    private fun toolbar() = JPanel(BorderLayout()).apply {
        background = BACKGROUND
        border = BorderFactory.createEmptyBorder(0, 0, 14, 0)
        add(
            line(
                button("Type") { show(TYPING) },
                button("Settings") { show(SETTINGS) },
                button("Open a text…") { chooseText() },
                recordButton,
                button("Take back a phrase") { takeBack() },
            ),
            BorderLayout.WEST,
        )
        add(line(phrasesLabel), BorderLayout.EAST)
    }

    private fun typingScreen() = JPanel(BorderLayout()).apply {
        background = BACKGROUND
        add(
            JPanel().apply {
                background = BACKGROUND
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createEmptyBorder(22, 4, 18, 4)
                add(fieldLabel)
                add(Box.createVerticalStrut(18))
                add(stripLabel)
                add(Box.createVerticalStrut(14))
                add(statusLabel)
            },
            BorderLayout.NORTH,
        )
        add(
            JPanel(BorderLayout()).apply {
                background = BACKGROUND
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, LINE),
                    BorderFactory.createEmptyBorder(8, 4, 0, 4),
                )
                add(label(12f, DIM).apply { text = "SUBMITTED" }, BorderLayout.NORTH)
                add(
                    JScrollPane(log).apply {
                        border = BorderFactory.createEmptyBorder()
                        background = BACKGROUND
                        viewport.background = BACKGROUND
                    }
                )
            }
        )
    }

    /**
     * Everything that can be changed, on a screen of its own.
     *
     * Two columns and one width for every control, because the list is long and a column of
     * ragged buttons is a list nobody scans. The bindings are the bulk of it and they are in the
     * remote's order rather than a tidy one: somebody rebinding has a remote in their hand and is
     * looking for the same thing in a list.
     */
    private fun settingsScreen(): Component {
        val panel = JPanel().apply {
            background = BACKGROUND
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(4, 4, 12, 18)
        }

        panel.add(heading("Dictionary"))
        panel.add(row("language", languageButton))
        panel.add(row("dictionary file", dictionaryButton))
        panel.add(row("start cold again", button("Clear what was learnt") { reset(forget = true) }))
        panel.add(row("", button("Clear the field") { reset(forget = false) }))

        panel.add(heading("Recording"))
        panel.add(row("text to copy out", textButton))
        panel.add(row("or a list of queries", targetsButton))
        panel.add(row("words per phrase", chunkButton))
        panel.add(row("write the record to", recordToButton))

        panel.add(heading("Keys"))
        panel.add(row("numpad reads as", padButton))
        for (bind in Bind.entries) {
            panel.add(row(labelOf(bind), bindButtons.getValue(bind)))
        }
        panel.add(Box.createVerticalStrut(10))
        panel.add(row("", button("Restore the defaults") { restoreBindings() }))

        return JScrollPane(panel).apply {
            border = BorderFactory.createEmptyBorder()
            background = BACKGROUND
            viewport.background = BACKGROUND
            verticalScrollBar.unitIncrement = 16
        }
    }

    // ---- controls ---------------------------------------------------------------------------

    private fun switchLanguage() {
        language = LANGUAGES[(LANGUAGES.indexOf(language) + 1) % LANGUAGES.size]
        settings.language = language
        session.dictionary = dictionaries[language]
        refresh()
    }

    private fun switchPad() {
        settings.pad = settings.pad.next()
        settings.bindings.apply(settings.pad)
        settings.save()
        refresh()
    }

    private fun switchChunk() {
        val at = CHUNKS.indexOf(settings.chunk)
        settings.chunk = CHUNKS[(at + 1) % CHUNKS.size]
        refresh()
    }

    private fun restoreBindings() {
        settings.bindings.restore(settings.pad)
        settings.save()
        refresh()
    }

    private fun chooseText() = choose("Text to copy out", "txt")?.let { file ->
        settings.text = file.path
        note("${file.name}: ${phrases().second.size} phrases to copy out")
        refresh()
    }

    private fun chooseTargets() = choose("List of queries", "tsv")?.let { file ->
        settings.text = ""
        settings.targets = file.path
        refresh()
    }

    private fun chooseRecord() = choose("Write the record to", "tsv", save = true)?.let { file ->
        settings.record = file.path
        refresh()
    }

    private fun chooseDictionary() = choose("Dictionary for $language", "bin")?.let { file ->
        settings.dictionary(language, file.path)
        note("${file.name} takes effect on the next start")
        refresh()
    }

    private fun choose(title: String, extension: String, save: Boolean = false): File? {
        val chooser = JFileChooser(File(".").absoluteFile).apply {
            dialogTitle = title
            fileFilter = FileNameExtensionFilter("$title (*.$extension)", extension)
            isAcceptAllFileFilterUsed = true
        }
        val answer = if (save) chooser.showSaveDialog(this) else chooser.showOpenDialog(this)
        return chooser.selectedFile.takeIf { answer == JFileChooser.APPROVE_OPTION }
    }

    /** The letters a number key carries, so the list reads like the remote and not like code. */
    private fun labelOf(bind: Bind): String = when (bind) {
        Bind.KEY_0 -> "0   space"
        Bind.KEY_1 -> "1   . , - ' & : /"
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
        val targets = File(settings.targets).takeIf { it.exists() }
            ?: return "queries" to emptyList()
        return "queries" to Recorder.targetsFrom(targets)
    }

    private fun toggleRecording() {
        if (recorder != null) {
            note("recording stopped")
            stopRecording()
            return
        }

        val (source, targets) = phrases()
        if (targets.isEmpty()) {
            note("nothing to type: open a text or a list of queries first")
            return
        }

        val started = Recorder(File(settings.record), targets, source, settings.pad)
        recorder = started
        val resumed = if (started.position > 0) ", resuming at ${started.position + 1}" else ""
        note("$source: ${targets.size} phrases into ${settings.record}$resumed")
        note("the numpad is being read as ${settings.pad.name.lowercase()}")
        note("type each one at full speed and press OK; do not fix mistakes")
        show(TYPING)
    }

    /**
     * Back to the phrase before this one, with what was typed into it.
     *
     * The one thing a fast thumb does that nothing else could undo: hitting OK halfway through a
     * phrase wrote the attempt, moved on, and put the phrase out of reach for good.
     */
    private fun takeBack() {
        val recorder = recorder ?: return
        if (recorder.takeBack(System.currentTimeMillis())) {
            note("back to phrase ${recorder.position + 1}, its row taken out of the file")
        } else {
            note("nothing to go back to")
        }
        refresh()
    }

    private fun stopRecording() {
        recorder = null
        refresh()
    }

    private fun note(text: String) = log.append("-- $text\n")

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
            note("user dictionary cleared")
        }
        val user = if (forget) UserDictionary() else session.user
        session = Session(dictionaries[language], user, clock = System::currentTimeMillis)
        logged = 0
        refresh()
    }

    /** Everything on screen, from the state and never from what a widget last had in it. */
    private fun refresh() {
        for ((bind, control) in bindButtons) {
            val keys = settings.bindings[bind]
            control.text = when {
                bind == capturing -> "press a key…"
                keys.isEmpty() -> "—"
                else -> keys.joinToString(" / ") { it.text }
            }
            control.foreground = if (bind == capturing) ACCENT else FOREGROUND
        }

        val (source, targets) = phrases()
        languageButton.text = language
        padButton.text = settings.pad.name.lowercase()
        chunkButton.text = settings.chunk.toString()
        textButton.text = tail(settings.text.ifEmpty { "none — using the list of queries" })
        targetsButton.text = tail(settings.targets)
        recordToButton.text = tail(settings.record)
        dictionaryButton.text = tail(settings.dictionary(language))
        recordButton.text = if (recorder == null) "Record" else "Stop recording"
        phrasesLabel.text = recorder
            ?.let { "recording ${it.position + 1} of ${it.targets.size}  ·  $source" }
            ?: "$source  ·  ${targets.size} phrases"

        recorder?.let { renderRecording(it) } ?: renderTyping()
    }

    private fun renderTyping() {
        fieldLabel.text = html(
            span(escape(session.committed), FOREGROUND) +
                span(
                    escape(session.field.removePrefix(session.committed)),
                    ACCENT,
                    underline = true,
                ) +
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
                ).joinToString("   ·   "),
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
        // The pad is on screen throughout, and it is here rather than only in the settings because
        // the first recording ever made was made through the wrong one and nothing said so.
        statusLabel.text = html(
            span(
                "numpad reads as ${settings.pad.name.lowercase()}   ·   OK accepts   ·   " +
                    "delete takes back a press   ·   back starts the phrase again   ·   " +
                    "previous returns to the phrase before",
                DIM,
            )
        )
    }

    // ---- widgets ----------------------------------------------------------------------------

    private fun label(size: Float, colour: Color) = JLabel().apply {
        foreground = colour
        font = Font(Font.SANS_SERIF, Font.PLAIN, size.toInt())
        alignmentX = LEFT_ALIGNMENT
    }

    private fun heading(text: String) = label(12f, ACCENT).apply {
        this.text = text.uppercase()
        border = BorderFactory.createEmptyBorder(18, 0, 8, 0)
    }

    private fun row(name: String, control: Component) = JPanel(BorderLayout(12, 0)).apply {
        background = BACKGROUND
        maximumSize = Dimension(Int.MAX_VALUE, 28)
        alignmentX = LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(1, 0, 1, 0)
        add(label(13f, FOREGROUND).apply { text = name })
        add(control, BorderLayout.EAST)
    }

    private fun line(vararg parts: Component) = JPanel().apply {
        background = BACKGROUND
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        for (part in parts) {
            add(part)
            add(Box.createHorizontalStrut(8))
        }
    }

    /**
     * A button drawn here rather than by the platform.
     *
     * The system look paints a light face with dark text, which on this window is black on black.
     * Turning the content area off and filling a flat panel instead is the only way to get a
     * control whose colours are known, and known is the whole requirement: the window is dark
     * because the candidate strip has to be read at a glance.
     */
    private fun button(text: String, onClick: () -> Unit) = JButton(text).apply {
        isFocusable = false
        isContentAreaFilled = false
        isOpaque = true
        background = CONTROL
        foreground = FOREGROUND
        font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(LINE),
            BorderFactory.createEmptyBorder(4, 12, 4, 12),
        )
        preferredSize = Dimension(240, 26)
        addActionListener { onClick() }
    }

    /** A path is longer than a button and its tail is the part that identifies it. */
    private fun tail(path: String) = if (path.length <= 36) path else "…" + path.takeLast(35)

    private companion object {

        const val TYPING = "typing"
        const val SETTINGS = "settings"

        val BACKGROUND: Color = Color(0x12, 0x14, 0x18)
        val CONTROL: Color = Color(0x1E, 0x23, 0x2B)
        val LINE: Color = Color(0x2E, 0x35, 0x40)
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
