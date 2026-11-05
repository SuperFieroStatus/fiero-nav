package dev.mike.fieronav

import dev.mike.fieronav.ui.MultiTap
import org.junit.Assert.assertEquals
import org.junit.Test

class MultiTapTest {

    /** Fake timer: [pause] runs the pending commit, like waiting out the 0.9 s letter timeout. */
    private class Keypad {
        private var scheduled: Runnable? = null
        var shown = ""
        val tap = MultiTap(
            schedule = { r, _ -> scheduled = r },
            cancel = { r -> if (scheduled === r) scheduled = null },
        ) { text, pending -> shown = text + (pending ?: "") }

        fun keys(vararg digits: Int) = digits.forEach { tap.press(it) }
        fun pause() { scheduled?.run(); scheduled = null }
        fun hold(d: Int) { tap.press(d); tap.longPress(d) } // key-down, then the long-press repeat
    }

    @Test
    fun abcCapitalisesTheFirstLetterOnly() {
        val k = Keypad()
        k.keys(6); k.pause()               // M
        k.keys(2); k.pause()               // a
        k.keys(4, 4, 4); k.pause()         // i
        k.keys(6, 6); k.pause()            // n
        assertEquals("Main", k.tap.text)
        assertEquals("Abc", k.tap.mode.label)
    }

    @Test
    fun differentKeyCommitsWithoutWaiting() {
        val k = Keypad()
        k.keys(4, 4, 3, 3, 2, 2)           // h, e, b
        assertEquals("Heb", k.tap.text)
    }

    @Test
    fun poundCyclesModes() {
        val k = Keypad()
        assertEquals("Abc", k.tap.mode.label)
        k.tap.nextMode(); assertEquals("abc", k.tap.mode.label)
        k.tap.nextMode(); assertEquals("ABC", k.tap.mode.label)
        k.tap.nextMode(); assertEquals("123", k.tap.mode.label)
        k.tap.nextMode(); assertEquals("Abc", k.tap.mode.label)
    }

    @Test
    fun lowerAndUpperModes() {
        val k = Keypad()
        k.tap.nextMode()                   // abc
        k.keys(4, 4, 3, 3, 2, 2); k.pause()
        assertEquals("heb", k.tap.text)
        k.tap.nextMode()                   // ABC
        k.keys(0, 4, 4, 3, 3, 2, 2); k.pause()
        assertEquals("heb HEB", k.tap.text)
    }

    @Test
    fun digitModeTypesDigitsImmediately() {
        val k = Keypad()
        repeat(3) { k.tap.nextMode() }     // 123
        k.keys(1, 2, 3)
        assertEquals("123", k.tap.text)
        k.tap.nextMode()                   // back to Abc
        k.keys(0)                          // space (letter-mode 0)
        k.pause()
        k.keys(6); k.pause()
        k.keys(2); k.pause()
        assertEquals("123 ma", k.tap.text) // not sentence start: lower case
    }

    @Test
    fun holdingAKeyTypesItsDigitInLetterModes() {
        val k = Keypad()
        k.hold(1); k.hold(2); k.hold(3)    // "123" without leaving Abc
        k.keys(0); k.pause()
        k.keys(6); k.pause()
        assertEquals("123 m", k.tap.text)
        assertEquals("Abc", k.tap.mode.label)
    }

    @Test
    fun holdingCommitsTheLetterBeforeIt() {
        val k = Keypad()
        k.keys(2, 2)                       // "B" still cycling
        k.hold(5)                          // a different key held: B commits, then 5
        assertEquals("B5", k.tap.text)
    }

    @Test
    fun holdingInDigitModeDoesNotDouble() {
        val k = Keypad()
        repeat(3) { k.tap.nextMode() }
        k.hold(7)
        assertEquals("7", k.tap.text)
    }

    @Test
    fun capitalAfterFullStop() {
        val k = Keypad()
        k.keys(2); k.pause()               // A
        k.keys(1); k.pause()               // .
        k.keys(0); k.pause()               // space
        k.keys(2, 2); k.pause()            // B (sentence start again)
        assertEquals("A. B", k.tap.text)
    }

    @Test
    fun backspaceAndModeChangeCommitTheCyclingLetter() {
        val k = Keypad()
        k.keys(2, 2)                       // B cycling
        k.tap.nextMode()                   // commits B, then abc
        k.keys(2)
        assertEquals("Ba", k.tap.text)
        k.tap.backspace()
        assertEquals("B", k.tap.text)
    }
}
