package io.github.vagrant326.atvt9.ime

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import io.github.vagrant326.atvt9.BuildConfig
import io.github.vagrant326.atvt9.core.Candidate
import io.github.vagrant326.atvt9.core.Composer
import io.github.vagrant326.atvt9.core.Keypad
import io.github.vagrant326.atvt9.core.LetterCase
import io.github.vagrant326.atvt9.core.T9Engine
import io.github.vagrant326.atvt9.core.decode.BeamDecoder
import io.github.vagrant326.atvt9.core.decode.SentenceComposer
import io.github.vagrant326.atvt9.core.decode.Source
import io.github.vagrant326.atvt9.log.TypingLog
import io.github.vagrant326.atvt9.log.TypingLogs
import io.github.vagrant326.atvt9.model.DictionaryRepository
import io.github.vagrant326.atvt9.model.Language
import io.github.vagrant326.atvt9.model.UserWords
import io.github.vagrant326.atvt9.settings.Preferences

/**
 * The keyboard.
 *
 * Number keys carry the letters, one press each, and the dictionary decides which letters they
 * were. What is not standard T9 is the second dictionary underneath: everything committed here
 * is remembered, so a series title that the shipped vocabulary has never heard of costs its
 * multitap price exactly once. Without that this method cannot type the thing a TV search box
 * is mostly used for, and the published KSPC of 1.0072 describes a workload nobody has on a
 * television.
 */
class T9ImeService : InputMethodService() {

    private lateinit var preferences: Preferences
    private lateinit var dictionaries: DictionaryRepository
    private lateinit var userWords: UserWords
    private lateinit var strip: CandidateStripView
    private lateinit var engine: T9Engine

    /**
     * The dev build's record of what was typed and what was made of it. [TypingLog.NONE] in the
     * released app, where the class that writes files is not compiled in at all.
     */
    private lateinit var typingLog: TypingLog

    /**
     * Whether anything typed in the current field may be remembered.
     *
     * Decided once per field, from the editor's own declaration, and never from a heuristic over
     * the text. An adaptive keyboard that records indiscriminately is precisely the artefact
     * `docs/00-overview.md` §3.1 argues this project must not build, and the only defensible
     * place to draw the line is where the app hosting the field has already drawn it.
     */
    private var mayLearn = true

    private var punctuationAt = -1

    /**
     * The whole query as presses, when the field is being read as a sentence rather than a word at
     * a time. Null when the setting is off, which is also what every path below tests.
     */
    private var composer: SentenceComposer? = null

    /** What the presses mean while a query is in the air. See [SentenceTyping]. */
    private var typing: SentenceTyping? = null

    /**
     * The field, for the part of this class that has been lifted out of it.
     *
     * Every call goes to the same input connection the rest of this service writes through; the
     * point is not indirection but that [SentenceTyping] can be handed a different one by a test.
     */
    private val editor = object : Editor {
        override fun commit(text: String) {
            currentInputConnection?.commitText(text, 1)
        }

        override fun compose(text: String) {
            currentInputConnection?.setComposingText(text, 1)
        }

        override fun finishComposing() {
            currentInputConnection?.finishComposingText()
        }

        override fun abandonComposing() {
            val connection = currentInputConnection ?: return
            // Emptying the region first is what actually removes the text; finishing alone only
            // stops it being provisional and leaves every letter of it in the field.
            connection.setComposingText("", 1)
            connection.finishComposingText()
        }

        override fun deleteBefore(count: Int) {
            currentInputConnection?.deleteSurroundingText(count, 0)
        }

        override fun deleteWord() {
            this@T9ImeService.deleteWord()
        }
    }

    /**
     * The decoder, and the one thread allowed to touch it.
     *
     * Reading a query is hundreds of milliseconds on a television and it was happening on the
     * thread that draws the keyboard, so every press during a read waited for it — which is most
     * of what "slow" meant. It happens here instead, and the answer is posted back.
     *
     * One thread rather than a pool, because the decoder keeps the search it built for the last
     * query and extends it for the next: two threads in there would each undo the other's work
     * and would do it unsafely. One thumb, one thread.
     */
    private var decoder: BeamDecoder? = null
    private val decoding = Executors.newSingleThreadExecutor()

    /**
     * Decoding waits for the typing to stop.
     *
     * At the speed this keyboard is meant to be typed at, a decode per press is work thrown away
     * four times in five — and nobody reads the strip mid-word at that speed, which is what makes
     * a beam this wide affordable on a television at all. A hundred and fifty milliseconds is
     * below the pause between two words and above the gap between two presses.
     */
    private val clock = Handler(Looper.getMainLooper())

    /**
     * Reads the presses somewhere else and brings the answer back.
     *
     * The keys are taken here, on the main thread, so that what comes back can be checked against
     * what is now in hand: a reading for presses that have since changed is an answer to a
     * question nobody is asking, and putting it on screen would show a word the last press had
     * already ruled out.
     */
    private val settling = Runnable {
        val composer = composer ?: return@Runnable
        val decoder = decoder ?: return@Runnable
        val keys = composer.pending() ?: return@Runnable
        decoding.execute {
            // Timed on the device rather than reasoned about. A decoder fast on a laptop says
            // nothing about a television: the beam allocates heavily, ART collects differently,
            // and the only honest number is the one measured where it will run.
            val started = SystemClock.uptimeMillis()
            val readings = decoder.decode(keys, READINGS)
            val took = SystemClock.uptimeMillis() - started
            clock.post {
                if (composer.apply(keys, readings)) {
                    if (BuildConfig.DEBUG) {
                        Log.i(TAG, "read ${keys.length} keys in ${took}ms off the main thread")
                    }
                    typingLog.read(keys, readings.map { it.text })
                    setComposing()
                    render()
                }
            }
        }
    }

    /**
     * Applied where the word reaches the field, never where it reaches the dictionary. Word-scoped
     * rather than per-character: what is in flight here is a whole word the dictionary has not
     * finished choosing, so there is no single character for a capital to attach to.
     */
    private var letterCase = LetterCase.LOWER

    /** The key whose meaning is waiting on its release. See [Action.DeferToRelease]. */
    private var deferredKey = KeyEvent.KEYCODE_UNKNOWN

    /**
     * Whether `2`-`9` are carrying marks instead of letters, and where in one key's run the mark
     * being cycled has got to.
     *
     * Held here rather than in [T9Engine] because a mark is not a word: it goes straight into the
     * field and is replaced in place while cycled, which is the mechanism key `1` already uses.
     * Keeping it out of the engine is what guarantees a symbol can never reach the dictionary or
     * a candidate list.
     */
    private var symbols = false
    private var symbolKey: Char? = null
    private var symbolAt = 0

    /**
     * Digits instead of letters. Set by the field when it asks for a number, and by the user's
     * key otherwise — a numeric field that offered word candidates would be offering nonsense.
     */
    private var digits = false

    private var showLanguages = false

    override fun onCreate() {
        super.onCreate()
        preferences = Preferences(this)
        dictionaries = DictionaryRepository(this)
        userWords = UserWords.of(this)
        engine = T9Engine(null, userWords.dictionary)
        typingLog = TypingLogs.of(this)
        warm()
    }

    /**
     * Reads a query nobody typed, so that the first one somebody does type is not the slow one.
     *
     * Measured on the television: the first press of a fresh keyboard cost 141-338ms and the
     * fourth cost 18ms. Almost none of that difference is the search — it is the dictionary being
     * read off the flash, the classes being loaded, and ART compiling a path it has never run.
     * All of it is work that can be done before anybody is waiting, and all of it is work that
     * only pays off if it happens on the thread that will do the real decoding, which is why it
     * goes through the same single-thread executor rather than a scratch thread.
     *
     * At service creation rather than when a field opens, because a keyboard is created once and
     * then answers many fields — and because the load used to happen on the main thread while the
     * user was already looking at the box they wanted to type in.
     */
    private fun warm() {
        val languages = preferences.enabledLanguages
        decoding.execute {
            val started = SystemClock.uptimeMillis()
            val sources = languages.mapNotNull { language ->
                dictionaries.dictionaryFor(language)?.let {
                    Source(language.code, it, prior = 1.0, model = dictionaries.modelFor(language))
                }
            }
            if (sources.isEmpty()) {
                return@execute
            }
            // Two presses rather than one: the first level of the search and the level that
            // extends it are different code, and the extension is the one every later press uses.
            BeamDecoder(sources).decode(WARM_KEYS, 1)
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "warmed in ${SystemClock.uptimeMillis() - started}ms")
            }
        }
    }

    /**
     * The same, for the decoder a field is about to be typed into.
     *
     * Warming the classes once at creation left the first press at 279ms against 338 cold, which
     * says most of what it costs is not the classes: it is this decoder's own first level, built
     * from nothing over every source it has. That work is the same whichever key starts it, so it
     * can be done while the user is still looking at the field.
     */
    private fun warm(decoder: BeamDecoder) {
        decoding.execute {
            val started = SystemClock.uptimeMillis()
            decoder.decode(WARM_KEYS, 1)
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "warmed this field's decoder in ${SystemClock.uptimeMillis() - started}ms")
            }
        }
    }

    /**
     * The decoder's thread goes with the keyboard.
     *
     * An executor outlives the service that made it unless it is told not to, and a keyboard is
     * created and destroyed whenever the system feels like reclaiming it — so left alone this
     * would leak a thread per lifetime, in a process that has to stay small enough to be worth
     * keeping alive between fields.
     */
    override fun onDestroy() {
        decoding.shutdownNow()
        clock.removeCallbacks(settling)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        strip = CandidateStripView(this)
        // The grid was a label, because a television cannot be touched. Answering to a finger as
        // well costs nothing there and is what makes the keyboard testable on an emulator, which
        // until now meant building an APK and walking to the sofa for every change.
        strip.onKey = { key -> tap(key, held = false) }
        strip.onHold = { key -> tap(key, held = true) }
        return strip
    }

    /**
     * A cell of the on-screen grid, as the action the same key would produce on the remote.
     *
     * Routed through [handle] rather than acting directly, so a tap and a press are the same
     * event by the time anything decides what it means. Two keys exist only here: a television
     * remote has a delete and an OK of its own, and the grid had two cells going spare.
     */
    private fun tap(key: Char, held: Boolean) {
        val action = when {
            key == CandidateStripView.DELETE_KEY ->
                if (held) Action.WordDelete else Action.Delete

            key == CandidateStripView.COMMIT_KEY -> Action.Commit
            key == '0' -> if (held) Action.ToggleCase else Action.Space
            key == '1' -> if (held) {
                if (engine.isComposing || composer?.isComposing == true) {
                    Action.Spell
                } else {
                    Action.ToggleSymbols
                }
            } else {
                Action.Punctuation
            }

            key in Keypad.FIRST_DIGIT..Keypad.LAST_DIGIT -> Action.Digit(key)
            else -> return
        }
        handle(action)
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "input started, restarting=$restarting")
        }
        engine.reset()
        engine.dictionary = dictionaries.dictionaryFor(preferences.activeLanguage)
        mayLearn = preferences.isLearning && isLearnable(info)
        // The recorder is refused by the field's own declaration rather than by the learning
        // setting: somebody who has turned learning off has said what may be kept, not what may
        // be measured, and a password box refuses both regardless of either.
        typingLog.opened(fieldDescription(info), allowed = isLearnable(info))
        composer = composerFor()
        showLanguages = false
        deferredKey = KeyEvent.KEYCODE_UNKNOWN

        // Like the digit mode below, the case and the mark layer belong to the field and not to
        // the app: neither a lock nor a half-used layer may follow the user into the next box.
        letterCase = LetterCase.LOWER
        leaveSymbols()

        // A field that wants a number gets digits without being asked. Anything else starts in
        // letters even if the mode was left on: the mode belongs to the field, not to the app.
        val classification = info?.inputType?.and(InputType.TYPE_MASK_CLASS)
        digits = classification == InputType.TYPE_CLASS_NUMBER ||
            classification == InputType.TYPE_CLASS_PHONE ||
            classification == InputType.TYPE_CLASS_DATETIME
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        render()
    }

    /**
     * What sort of box this is, for the record, and nothing that identifies it.
     *
     * The input type and the package that asked, because "the presses were slower in the search
     * box than in the scratch field" is a thing worth being able to see, and neither of those is
     * the contents of anything.
     */
    private fun fieldDescription(info: EditorInfo?): String =
        "type=${info?.inputType ?: 0} options=${info?.imeOptions ?: 0} app=${info?.packageName}"

    override fun onFinishInput() {
        typingLog.closed()
        // The field is going away, so a reading asked for on its behalf has nowhere to land.
        clock.removeCallbacks(settling)
        finishWord(commit = false)
        userWords.flush()
        super.onFinishInput()
    }

    /**
     * Never. The default says yes to every landscape screen, and a television is landscape
     * always — so leaving this alone puts the keyboard into extract mode permanently, which
     * covers the whole display with a white text editor and hides the field the user was
     * actually filling in. It reads as the keyboard failing to open rather than as a mode.
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * The keyboard is not always visible when a key arrives, and this is where a previous
     * version of a sibling app left a television unnavigable: consuming d-pad events while
     * hidden means nothing on the device can be reached any more.
     *
     * So while hidden exactly one key is looked at — the trigger the user assigned, unassigned
     * by default — and every other event is handed straight back to the system.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        @Suppress("NAME_SHADOWING")
        val keyCode = KeyBindings.numberKey(keyCode, preferences.isTurnedNumpad)
        if (!isInputViewShown) {
            val trigger = preferences.triggerKeyCode
            if (trigger != KeyBindings.NO_KEY && keyCode == trigger && event.repeatCount == 0) {
                // requestShowSelf is the supported route and arrived in API 28. Below that
                // showWindow is the only way in, and it is what every IME used before 28.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    requestShowSelf(0)
                } else {
                    @Suppress("DEPRECATION")
                    showWindow(true)
                }
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        val action = KeyBindings.of(
            keyCode,
            event.repeatCount,
            preferences.customKeys,
            // A word in progress *or* a query in the air. Left and right mean the candidate walk
            // only while something is being composed, and reading a whole query is composing —
            // asking the word engine alone meant that in sentence mode the arrows fell through to
            // the editor and moved the caret, so the readings could not be walked at all.
            engine.isComposing || composer?.isComposing == true,
            digits,
        ) ?: return super.onKeyDown(keyCode, event)

        return handle(action)
    }

    /**
     * `0` commits nothing until it is released, because it is the only key here that means two
     * things.
     *
     * Android delivers a hold as a second key-down, so a space written on the way down is already
     * in the field by the time the hold announces itself as the case switch — and taking it back
     * is visible in a field the user is looking at. `1` needs no deferral: its hold reaches
     * spelling, which does not write anything, and its tap replaces its own mark in place.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != deferredKey) {
            return super.onKeyUp(keyCode, event)
        }
        deferredKey = KeyEvent.KEYCODE_UNKNOWN
        return handle(Action.Space)
    }

    private fun handle(action: Action): Boolean {
        // Recorded here rather than in each branch below, because here is the one place every
        // press passes through, whichever mode it lands in. What the press *meant* is the thing
        // worth having: `act delete` after four letters is a correction, and a correction is the
        // only ground truth anybody gets without asking the user what they were trying to type.
        when (action) {
            is Action.Digit -> typingLog.pressed(action.digit)
            is Action.Ignore -> Unit
            else -> typingLog.acted(action.toString())
        }

        // The mark layer is spent by one mark, and cycling that mark is a run of presses on the
        // same key. Anything else ends the layer *before* it is handled, so a letter press that
        // follows a mark comes out of the letter run and not the symbol run.
        if (symbols && !continuesMark(action)) {
            leaveSymbols()
        }

        if (composeSentence(action)) {
            setComposing()
            render()
            scheduleSettle()
            return true
        }

        when (action) {
            is Action.Ignore -> Unit

            is Action.ToggleSymbols -> {
                finishWord(commit = true)
                val entering = !symbols
                leaveSymbols()
                symbols = entering
            }

            is Action.Digit -> {
                if (symbols) {
                    cycleMark(action.digit)
                } else if (digits) {
                    // Deterministic: nothing to disambiguate, so it goes straight into the field
                    // rather than through the engine, which would offer words for it.
                    finishWord(commit = true)
                    currentInputConnection?.commitText(action.digit.toString(), 1)
                } else {
                    engine.press(action.digit, System.currentTimeMillis())
                    setComposing()
                }
            }

            is Action.Candidate -> {
                if (action.forward) {
                    engine.next()
                } else {
                    // Stepping back is the forward cycle taken all the way round. The strip
                    // holds eight candidates at most, so the loop is bounded by the strip rather
                    // than by the dictionary and there is no state to keep in step.
                    repeat((engine.candidates.size - 1).coerceAtLeast(0)) { engine.next() }
                }
                setComposing()
            }

            is Action.Space -> {
                finishWord(commit = true)
                currentInputConnection?.commitText(" ", 1)
            }

            is Action.Commit -> {
                val wasComposing = engine.isComposing
                finishWord(commit = true)
                if (!wasComposing) {
                    // Nothing pending, so this press belongs to the field: a search box wants
                    // to search, and swallowing it would strand the user on a filled-in query.
                    return sendDefaultEditorAction(true)
                }
            }

            is Action.Delete -> {
                if (!engine.backspace()) {
                    currentInputConnection?.deleteSurroundingText(1, 0)
                } else {
                    setComposing()
                }
            }

            is Action.Back -> {
                engine.reset()
                currentInputConnection?.finishComposingText()
            }

            is Action.Spell -> {
                engine.spell()
                setComposing()
            }

            // A held number key, straight into the field. Deterministic like the digit mode and
            // handled the same way: the sentence, or the word, ends first, because a digit is not
            // a letter the decoder could still be reading.
            //
            // The short press has to be taken back first. Android announces a hold as a *second*
            // key-down, so by the time it arrives the plain press has already been dealt with -
            // which is why holding `2` produced `a2`. Key `0` is the exception and always was: it
            // is deferred to its release, so there is nothing yet to undo and the release must be
            // stopped from adding a space afterwards.
            is Action.Number -> {
                when {
                    action.digit == '0' -> deferredKey = KeyEvent.KEYCODE_UNKNOWN
                    action.digit == '1' -> {
                        currentInputConnection?.deleteSurroundingText(1, 0)
                        punctuationAt = -1
                    }

                    composer?.delete() == true -> Unit
                    else -> engine.backspace()
                }
                typing?.send()
                finishWord(commit = true)
                currentInputConnection?.commitText(action.digit.toString(), 1)
            }

            is Action.Punctuation -> punctuate()

            is Action.DeferToRelease -> deferredKey = action.keyCode

            /**
             * The word in progress is still the composing region, so the switch applies to it as
             * well as to what follows. Pressing the case key after seeing the wrong case is the
             * order people actually press them in, and making that a delete-and-retype would waste
             * the one advantage a composing region has.
             */
            is Action.ToggleCase -> {
                // The hold has claimed the press, so the release must not also write a space.
                deferredKey = KeyEvent.KEYCODE_UNKNOWN
                letterCase = letterCase.next()
                setComposing()
            }

            is Action.NextLanguage -> nextLanguage()

            is Action.ShowLanguages -> {
                // Cycling blind is fine for two and unusable past that, so a hold names them.
                showLanguages = preferences.enabledLanguages.size > 1
            }

            is Action.ToggleDigits -> {
                finishWord(commit = true)
                digits = !digits
            }

            /**
             * The caret, a word at a time. The word in progress is committed first: leaving it
             * composing while the caret walks away puts the editor's composing region somewhere
             * the user is no longer looking, and what it does next is the editor's business.
             */
            is Action.WordJump -> {
                finishWord(commit = true)
                jumpWord(action.forward)
            }

            is Action.WordDelete -> {
                finishWord(commit = false)
                deleteWord()
            }
        }
        render()
        return true
    }

    /**
     * Moves the caret to the next or previous word boundary.
     *
     * Reads the text around the cursor from the editor rather than tracking a buffer here. The
     * editor owns the text — it may already contain something this keyboard never typed, and a
     * local copy would be wrong the moment it did.
     */
    private fun jumpWord(forward: Boolean) {
        val connection = currentInputConnection ?: return
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0) ?: return
        val text = extracted.text ?: return
        val at = extracted.selectionEnd.coerceIn(0, text.length)

        var target = at
        if (forward) {
            while (target < text.length && text[target].isWhitespace()) target++
            while (target < text.length && !text[target].isWhitespace()) target++
        } else {
            while (target > 0 && text[target - 1].isWhitespace()) target--
            while (target > 0 && !text[target - 1].isWhitespace()) target--
        }
        connection.setSelection(target, target)
    }

    /** Deletes back to the previous word boundary, whitespace included. */
    private fun deleteWord() {
        val connection = currentInputConnection ?: return
        val before = connection.getTextBeforeCursor(MAX_CONTEXT, 0) ?: return
        if (before.isEmpty()) {
            return
        }
        var count = 0
        while (count < before.length && before[before.length - 1 - count].isWhitespace()) count++
        while (count < before.length && !before[before.length - 1 - count].isWhitespace()) count++
        connection.deleteSurroundingText(count, 0)
    }

    /**
     * Cycles the marks on `1`, replacing the previous one in place.
     *
     * A query needs a handful of marks and a T9 keypad has one key spare for them, so cycling is
     * the only arrangement that fits. Replacing in place rather than appending is what makes a
     * wrong choice one more press instead of a delete and a retry.
     */
    /**
     * Whether [action] belongs to the mark currently being cycled.
     *
     * A swallowed key repeat does not end the layer, and neither does the toggle itself — asking
     * afterwards would find the layer already gone and it could be entered but never left. The
     * first press after entering has no key to match, which is why a null [symbolKey] continues.
     */
    private fun continuesMark(action: Action): Boolean = when (action) {
        is Action.Ignore, is Action.ToggleSymbols -> true
        is Action.Digit -> symbolKey == null || action.digit == symbolKey
        else -> false
    }

    private fun leaveSymbols() {
        symbols = false
        symbolKey = null
        symbolAt = 0
    }

    /**
     * Commits one mark, replacing the previous one in place while the same key is being tapped.
     *
     * The same bargain [punctuate] makes on key `1`, and for the same reason: a wrong choice costs
     * one more press rather than a delete and a retry. Nothing here goes through the engine, so a
     * mark cannot be learnt, cannot be offered as a candidate and cannot collide with a word.
     */
    private fun cycleMark(digit: Char) {
        val run = Keypad.symbolsOn(digit)
        if (run.isEmpty()) {
            return
        }
        if (digit == symbolKey) {
            symbolAt = (symbolAt + 1) % run.length
            currentInputConnection?.deleteSurroundingText(1, 0)
        } else {
            finishWord(commit = true)
            symbolKey = digit
            symbolAt = 0
        }
        currentInputConnection?.commitText(run[symbolAt].toString(), 1)
    }

    private fun punctuate() {
        // Worked out before [finishWord], which clears the position — that reset is how every
        // other action breaks the run, and this is the one caller that has to survive it.
        val next = if (punctuationAt < 0) 0 else (punctuationAt + 1) % PUNCTUATION.length
        finishWord(commit = true)
        val connection = currentInputConnection ?: return
        if (next > 0) {
            connection.deleteSurroundingText(1, 0)
        }
        connection.commitText(PUNCTUATION[next].toString(), 1)
        punctuationAt = next
    }

    private fun nextLanguage() {
        val enabled = preferences.enabledLanguages
        // One language has nothing to switch between, and a key that silently does nothing is
        // worse than one that does not exist — so the word in progress is left alone too.
        if (enabled.size < 2) {
            return
        }
        finishWord(commit = true)
        val next = enabled[(enabled.indexOf(preferences.activeLanguage) + 1) % enabled.size]
        preferences.activeLanguage = next
        engine.dictionary = dictionaries.dictionaryFor(next)
    }

    /**
     * The decoder, over both dictionaries at once, or null when the setting is off.
     *
     * Both languages are always in it and the prior leans towards the active one. A switch cannot
     * help with `piątek the series`, which changes language in the middle, and a TV search box is
     * full of exactly that — so the language is decided per word by the evidence rather than by
     * the user pressing something before they start typing.
     */
    private fun composerFor(): SentenceComposer? {
        if (!preferences.isSentence) {
            return null
        }
        val shipped = preferences.enabledLanguages.mapNotNull { language ->
            dictionaries.dictionaryFor(language)?.let {
                Source(
                    language = language.code,
                    dictionary = it,
                    // Fitted on the real queries: the second language at 0.2 to 0.35 reads 44.4%
                    // of them where one language alone reads 38.9%, and the two are a tie there.
                    // 0.35 breaks it, because a query that is English throughout - `again`, `daft
                    // punk` - is the case the tie says nothing about and the harsher prior loses.
                    prior = if (language == preferences.activeLanguage) 0.65 else 0.35,
                    model = dictionaries.modelFor(language),
                )
            }
        }

        // The user's own words, and they are not an afterthought here. Measured on the real
        // queries, more than half the words the decoder gets wrong are words no shipped
        // dictionary holds - `electroboom`, `twoset`, `accantus` - and every one of them is a word
        // this household has already typed once. Until now the decoder could not see them: the
        // keyboard wrote every committed word into the store and never read one back.
        //
        // No language, because a series title has none, and a prior of one because a word this
        // user has actually typed is not less likely than a word from a corpus of subtitles.
        val sources = shipped + Source(
            language = null,
            dictionary = userWords.dictionary,
            prior = 1.0,
        )
        if (sources.isEmpty()) {
            decoder = null
            typing = null
            return null
        }
        val built = BeamDecoder(sources)
        decoder = built
        warm(built)
        val composing = SentenceComposer(built, READINGS)
        typing = SentenceTyping(
            composer = composing,
            editor = editor,
            spelling = { engine.mode == Composer.SPELL },
            learn = { words ->
                if (mayLearn) {
                    words.forEach { userWords.dictionary.learn(it) }
                    userWords.flush()
                }
            },
            committed = { keys, text -> typingLog.put(keys, text) },
        )
        return composing
    }

    /**
     * The presses, while a whole query is in the air. Returns whether the action was spent here.
     *
     * The digit mode and the mark layer are left to the word keyboard below: neither is ambiguous,
     * so neither has anything for a decoder to decide, and a `7` in a phone number must not become
     * a word. Case and spelling are the same — a capital belongs to a word and there is no word
     * yet, so those fall through and end the sentence first.
     */
    /**
     * The presses while a whole query is in the air, which [SentenceTyping] decides.
     *
     * Two of them stay here because they are this class's to undo rather than the composition's:
     * `0` is deferred to its release, so a hold must stop that release from adding a space, and
     * `1` has already put a mark in the field.
     */
    private fun composeSentence(action: Action): Boolean {
        val typing = typing?.takeIf { !symbols && !digits } ?: return false

        if (action is Action.Number) {
            when (action.digit) {
                '0' -> deferredKey = KeyEvent.KEYCODE_UNKNOWN
                '1' -> {
                    currentInputConnection?.deleteSurroundingText(1, 0)
                    punctuationAt = -1
                }
            }
        }

        typing.letterCase = letterCase
        val spent = typing.press(action)
        letterCase = typing.letterCase
        return spent
    }

    /**
     * Asks for a reading once the typing stops, and takes back the one already asked for.
     *
     * Without this the runnable above was written, removed on destruction, and never once posted:
     * nothing ever read the presses in the background, so the strip stayed empty for the whole
     * word and the only decode that happened was the synchronous one `settleSegment` does on the
     * space — on the thread that draws the keyboard, which is exactly what moving it off was for.
     * The television showed the symptom plainly: words appeared a whole word late, in a jump.
     *
     * Re-posting on every press is the point rather than an optimisation. A decode per press is
     * work thrown away four times in five at the speed this is meant to be typed at, and the
     * delay is chosen to sit above the gap between two presses and below the pause between two
     * words.
     */
    private fun scheduleSettle() {
        clock.removeCallbacks(settling)
        if (composer?.stale == true) {
            clock.postDelayed(settling, SETTLE_MILLIS)
        }
    }

    /** Shows the pending word inline, so the field always reads as what committing would leave. */
    private fun setComposing() {
        punctuationAt = -1
        val connection = currentInputConnection ?: return
        composer?.let {
            if (it.isComposing) {
                connection.setComposingText(letterCase.apply(it.text), 1)
            } else {
                connection.finishComposingText()
            }
            return
        }
        if (engine.isComposing) {
            connection.setComposingText(letterCase.apply(engine.composing), 1)
        } else {
            connection.finishComposingText()
        }
    }

    private fun finishWord(commit: Boolean) {
        punctuationAt = -1
        if (!engine.isComposing) {
            return
        }
        val connection = currentInputConnection
        if (commit) {
            // The engine is handed the word in lower case and learns it that way. The capital is
            // applied to what goes into the *field* and never to what goes into the dictionary: a
            // user dictionary holding both `jan` and `Jan` would answer one key sequence twice and
            // carry the duplicate for ever, which is the sort of rot only the user can clear.
            val word = engine.commit(learn = mayLearn)
            if (word != null) {
                connection?.commitText(letterCase.apply(word), 1)
                letterCase = letterCase.afterWord()
            }
            // Cheap enough per word, and the alternative is losing everything learnt in a
            // session when the system reclaims the keyboard process without warning.
            userWords.flush()
        } else {
            engine.reset()
            connection?.finishComposingText()
        }
    }

    private fun render() {
        if (!::strip.isInitialized) {
            return
        }
        val reading = composer?.takeIf { it.isComposing }
        strip.render(
            StripState(
                // The strip shows whole readings rather than words when a whole query is in the
                // air, which is what there is to choose between. Dressed as candidates so the view
                // stays one view: what it draws is a list with one of them picked out, and that is
                // true of both.
                candidates = reading?.hypotheses?.map { Candidate(it.text, 0, exact = true) }
                    ?: engine.candidates,
                selected = reading?.selected ?: engine.selected,
                sequence = reading?.pressed ?: engine.sequence,
                composing = reading?.text ?: engine.composing,
                spelling = engine.mode == Composer.SPELL,
                trained = engine.dictionary != null,
                language = languageLabel(),
                hintMode = preferences.hintMode,
                letterCase = letterCase,
                symbols = symbols,
                digits = digits,
                hasEditor = currentInputConnection != null,
                learning = mayLearn,
                customKeys = preferences.customKeys,
            )
        )
    }

    private fun languageLabel(): String =
        if (preferences.enabledLanguages.size <= 1) "" else preferences.activeLanguage.label

    /**
     * Whether this field's contents may be added to the user dictionary.
     *
     * Three separate refusals, because the apps that set them mean three different things and
     * only one of them is about secrecy. A password must never be stored; a field that asked for
     * no personalised learning has told us not to; a no-suggestions field is usually an
     * identifier, and an identifier in the dictionary is noise the user then has to delete.
     */
    private fun isLearnable(info: EditorInfo?): Boolean {
        if (info == null) {
            return false
        }
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) {
            return false
        }
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val classification = info.inputType and InputType.TYPE_MASK_CLASS
        if (classification == InputType.TYPE_CLASS_TEXT) {
            if (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) {
                return false
            }
            if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_URI
            ) {
                return false
            }
        }
        if (classification == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        ) {
            return false
        }
        return true
    }

    private companion object {

        const val TAG = "T9"

        /**
         * How long the typing has to stop before it is worth reading.
         *
         * Deliberately *below* the gap between two presses, which the typing record puts at a
         * median of 195 ms, so that a press gets its own reading and the word appears while the
         * word is being typed.
         *
         * It used to be 300, chosen to sit above that gap so a run of presses cost one decode at
         * the end of it instead of one per press. That was the right trade when a decode was
         * hundreds of milliseconds on the thread that draws the keyboard: the reading was worth
         * waiting for because asking for it early cost the next press. It is the wrong trade now.
         * A decode is 11-27ms on a background thread, and the 300 was not saving anything the
         * user could feel - it *was* what the user could feel. Five presses in a burst produced
         * exactly one reading, 300ms after the last of them, and until then the strip showed the
         * digits pressed, which is what "it shows me numbers instead of words" meant.
         *
         * Not zero, because a burst faster than a decode would queue one decode per press and
         * every one but the last would be thrown away by [SentenceComposer.apply] anyway.
         */
        const val SETTLE_MILLIS = 60L

        /** The query the keyboard reads to itself before anybody types one. See [warm]. */
        const val WARM_KEYS = "26"

        /** How long a commit may wait for a reading before going ahead without one. */
        const val SETTLE_LIMIT = 2L

        /** Readings kept, which is what the strip can walk. */
        const val READINGS = 5
        /** What a TV query actually contains. Not a general punctuation set, and not meant as one. */
        const val PUNCTUATION = ".,-'&:/"

        /**
         * How much text before the caret a word-delete will look at. A TV query is a line, so
         * this is far more than one word ever needs — the cap exists because the editor is under
         * no obligation to be small and a novel would be copied across the process boundary.
         */
        const val MAX_CONTEXT = 512
    }
}
