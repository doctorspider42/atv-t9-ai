package io.github.vagrant326.atvt9.ime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.vagrant326.atvt9.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How the test finds its own field, and how the field announces itself. A description rather than
 * a resource id because the field is built in code — there are no layouts in the test APK and one
 * view does not justify the first of them. File-scoped because both halves below need it.
 */
private const val FIELD_LABEL = "the field the smoke test types into"
private val FIELD: BySelector = By.desc(FIELD_LABEL)

/**
 * One test, and it is the only one here on purpose.
 *
 * What a press *means* is decided by `SentenceTyping` and by `core`, and a hundred and eighteen
 * tests on the JVM already watch that — repeating any of it on a device would buy a slower copy
 * of an answer we already have. What no JVM test can be wrong about is the chain itself: that the
 * APK installs, that the service is a keyboard Android will select, that a key pressed on the
 * hardware reaches `onKeyDown` at all, and that what comes back out is a word in the field. Every
 * one of those has exactly one failure mode — nothing happens — and none of them is visible from
 * a unit test.
 *
 * So it types a word and looks at the field. If the letters are there the chain is whole; if the
 * digits are there the keyboard is installed and deaf, which is the failure this exists to catch.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun theKeyboardTurnsPressesIntoAWord() {
        // The flavour decides the applicationId — the dev build carries `.dev` so it can sit
        // beside the released one — so the component is read off the build rather than written
        // out here, and the test runs against whichever flavour was assembled.
        val service = "${BuildConfig.APPLICATION_ID}/io.github.vagrant326.atvt9.ime.T9ImeService"
        shell("ime enable $service")
        shell("ime set $service")

        // The one line nobody guesses, and it cost fifteen minutes here the first time. Android
        // hides every soft keyboard while a hardware keyboard is attached and an emulator always
        // has one, so without this the keyboard is installed, selected and completely deaf — the
        // presses go straight into the field as digits and there is nothing on screen to say why.
        // `:app:tv` sets it for the same reason.
        shell("settings put secure show_ime_with_hard_keyboard 1")

        // Neither this nor the selected keyboard is put back afterwards. `:app:tv` leaves a device
        // in exactly this state, and it is the state somebody looking at the same emulator by hand
        // wants it in; restoring would undo the task rather than tidy up after the test.

        // Started rather than handed back, because `startActivitySync` refuses an activity that
        // resolves to a process other than the one under test — and this one is in the test APK,
        // so it always does. Which is why the field is read through UiAutomator below instead of
        // from the `EditText` itself: there is no reference to it on this side of the fence.
        instrumentation.context.startActivity(
            Intent(instrumentation.context, TypingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        assertTrue(
            "the field never came up, so there was nothing to type into",
            device.wait(Until.hasObject(FIELD), WINDOW_MILLIS),
        )

        // The keyboard is deaf while hidden by design — consuming the d-pad while hidden is what
        // once left a television unnavigable — so there is no point pressing anything until its
        // window is up. The window belongs to the keyboard's package; the field belongs to the
        // test's, so there is nothing else this could match.
        assertTrue(
            "the keyboard never appeared, so nothing below would have been a test of anything",
            device.wait(Until.hasObject(By.pkg(BuildConfig.APPLICATION_ID)), WINDOW_MILLIS),
        )

        // `kot`, or `lot`, or any of the other Polish words on these three keys. Which one the
        // decoder picks is the decoder's business and is measured elsewhere; all that is asked
        // here is that three presses come back as three letters.
        //
        // Then `0`, which finishes the word and adds a space. It is here because what this test
        // wants to look at is text the field actually holds rather than a composing region the
        // keyboard may still take back — and because a word followed by a space is what somebody
        // typing a word does. It also keeps the test off the 300 ms settle: a preview that has
        // not arrived yet is a slow keyboard, and a word that never arrives is a broken one, and
        // only the second is a thing a smoke test should be able to tell you.
        press('5', '6', '8', '0')

        val typed = settled().trim()
        assertTrue(
            "the field holds `$typed`, which is what a field that never reached the keyboard holds",
            typed.matches(WORD),
        )
    }

    /** Digit `d` is Android keycode `KEYCODE_0 + d`, which is how a remote's number row arrives. */
    private fun press(vararg digits: Char) {
        for (digit in digits) {
            device.pressKeyCode(KeyEvent.KEYCODE_0 + (digit - '0'))
        }
    }

    /**
     * What the field holds once it has stopped changing, or once the wait runs out.
     *
     * Generous, because reading a query is hundreds of milliseconds on real hardware and a cold
     * emulator on a CI runner with no GPU is slower than any television — and because the
     * accessibility tree this reads through is a copy that arrives when it arrives. Polling
     * rather than sleeping keeps the usual case quick.
     */
    private fun settled(): String {
        val deadline = SystemClock.uptimeMillis() + DECODE_MILLIS
        var last = ""
        while (SystemClock.uptimeMillis() < deadline) {
            // Found again every time rather than held: the node goes stale whenever the text it
            // describes changes, which here is on every press and again when the reading lands.
            val now = device.findObject(FIELD)?.text.orEmpty()
            if (now.isNotEmpty() && now == last) {
                return now
            }
            last = now
            SystemClock.sleep(POLL_MILLIS)
        }
        return last
    }

    private fun shell(command: String) {
        device.executeShellCommand(command)
    }

    private companion object {

        /** Installing, selecting and showing a keyboard on a cold emulator. */
        const val WINDOW_MILLIS = 20_000L

        const val DECODE_MILLIS = 15_000L
        const val POLL_MILLIS = 250L

        /**
         * Three presses, three letters, and the Polish ones count because folding them onto the
         * key their base letter sits on is the reason this keyboard exists. Deliberately not a
         * particular word: the dictionary is rebuilt from a corpus that is not committed, so a
         * test pinned to one reading would fail on the day the corpus grew and would be reporting
         * the wrong thing when it did.
         */
        val WORD = Regex("[a-ząćęłńóśźż]{3}")
    }
}

/**
 * A field to type into, and nothing else.
 *
 * Here rather than in its own file because it is not a screen — it is the second half of the
 * sentence the test above is written in, and reading one without the other tells you nothing.
 */
class TypingActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val field = EditText(this)
        field.contentDescription = FIELD_LABEL
        setContentView(field)
        field.requestFocus()
        // Asking for the keyboard rather than waiting to be given it. A television image opens
        // with no focus to speak of, and a test that begins by hunting for a field to tap is a
        // test of the test.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }
}
