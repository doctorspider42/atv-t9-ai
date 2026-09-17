package io.github.vagrant326.atvt9.core.harness

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.io.File

class RecorderTest {

    @TempDir
    lateinit var directory: File

    private fun recorder(vararg targets: String) =
        Recorder(File(directory, "typing.tsv"), targets.toList(), "book", Pad.REMOTE)

    @Test
    fun `an attempt is written with its phrase and its timings`() {
        val recorder = recorder("kot")
        recorder.press('5', atMillis = 1_000)
        recorder.press('6', atMillis = 1_120)
        recorder.press('8', atMillis = 1_190)
        recorder.accept()

        assertEquals(
            listOf(Recorder.HEADER, "book\t0\tremote\tkot\t568\t0,120,70"),
            File(directory, "typing.tsv").readLines(),
        )
    }

    @Test
    fun `a taken back press is recorded as never having happened`() {
        val recorder = recorder("kot")
        recorder.press('5', atMillis = 0)
        recorder.press('9', atMillis = 100)
        recorder.undo()
        recorder.press('6', atMillis = 200)
        recorder.accept()

        assertEquals(
            "book\t0\tremote\tkot\t56\t0,200",
            File(directory, "typing.tsv").readLines()[1],
        )
    }

    @Test
    fun `a sitting picks up where the last one stopped`() {
        val first = recorder("a", "b", "c")
        first.skip()
        first.press('2', atMillis = 0)
        first.accept()

        val second = recorder("a", "b", "c")
        assertEquals(2, second.position)
        assertEquals("c", second.target)
    }

    @Test
    fun `another text in the same file is its own sitting`() {
        val book = recorder("a", "b")
        book.press('2', atMillis = 0)
        book.accept()

        val queries = Recorder(File(directory, "typing.tsv"), listOf("x", "y"), "queries")
        assertEquals(0, queries.position)
    }

    @Test
    fun `a text becomes phrases of a fixed length, with the untypable turned into gaps`() {
        val fragments = Recorder.fragmentsOf(
            "Litwo! Ojczyzno moja! ty jesteś jak zdrowie;\nIle cię trzeba cenić",
            words = 4,
        )

        assertEquals(
            listOf("litwo ojczyzno moja ty", "jesteś jak zdrowie ile", "cię trzeba cenić"),
            fragments,
        )
    }

    @Test
    fun `an attempt with nothing in it writes no row and still advances`() {
        val recorder = recorder("kot", "los")
        recorder.accept()

        assertEquals("los", recorder.target)
        assertFalse(File(directory, "typing.tsv").exists())
    }

    @Test
    fun `an attempt accepted too early comes back, presses and all`() {
        val recorder = recorder("kot", "los")
        recorder.press('5', atMillis = 1_000)
        recorder.press('6', atMillis = 1_150)
        recorder.accept() // the thumb was early: the phrase is not finished

        assertEquals("los", recorder.target)
        assertTrue(recorder.takeBack(atMillis = 9_000))
        assertEquals("kot", recorder.target)
        assertEquals("56", recorder.pressed)

        // Its row is out of the file, so accepting again writes one attempt and not two.
        assertEquals(listOf(Recorder.HEADER), File(directory, "typing.tsv").readLines())

        recorder.press('8', atMillis = 9_400)
        recorder.accept()
        val row = File(directory, "typing.tsv").readLines()[1].split('\t')
        assertEquals("568", row[4])
        // The gap the taking back cost is recorded rather than smoothed away: it happened.
        assertEquals("0,150,400", row[5])
    }

    @Test
    fun `there is nothing before the first phrase`() {
        assertFalse(recorder("kot").takeBack(atMillis = 0))
    }

    @Test
    fun `the phrases run out and the recording is over`() {
        val recorder = recorder("kot")
        assertFalse(recorder.isFinished)
        recorder.skip()
        assertTrue(recorder.isFinished)
    }

    @Test
    fun `phrases come from the first column, past the header and the comments`() {
        val queries = File(directory, "queries.tsv")
        queries.writeText(
            """
            query	language	note
            # a comment, which the corpus has several of
            dzikie ucho	pl
            twoset violin	en	out-of-dictionary channel name

            """.trimIndent() + "\n"
        )

        assertEquals(listOf("dzikie ucho", "twoset violin"), Recorder.targetsFrom(queries))
    }
}
