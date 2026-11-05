package dev.mike.fieronav.ui

import android.os.Handler
import android.os.Looper

/**
 * Classic phone-keypad text entry, with the usual feature-phone conventions:
 *
 * - Letter modes: pressing a digit repeatedly cycles its letters; a pause of [COMMIT_MS] (or another
 *   key) commits the letter. 0 = space, 1 = punctuation.
 * - `#` cycles the mode: **Abc** (capital at the start / after ". ") → **abc** → **ABC** → **123**.
 * - **123** mode types digits straight away.
 * - Holding a digit key types that digit in any mode ([longPress]).
 *
 * [schedule]/[cancel] default to the main looper; tests pass their own.
 */
class MultiTap(
    private val schedule: (Runnable, Long) -> Unit = { r, ms -> mainHandler.postDelayed(r, ms) },
    private val cancel: (Runnable) -> Unit = { r -> mainHandler.removeCallbacks(r) },
    private val onChange: (text: String, pending: Char?) -> Unit,
) {
    enum class Mode(val label: String) { SENTENCE("Abc"), LOWER("abc"), UPPER("ABC"), DIGITS("123") }

    var mode: Mode = Mode.SENTENCE
        private set

    private val committed = StringBuilder()
    private var pendingKey = -1
    private var pendingIndex = 0

    val text: String get() = committed.toString() + (pendingChar()?.toString() ?: "")

    private val commit = Runnable { commitPending() }

    fun press(digit: Int) {
        if (mode == Mode.DIGITS) {
            commitPending()
            committed.append('0' + digit)
            notifyChange()
            return
        }
        val letters = KEYS[digit]
        if (digit == pendingKey) {
            pendingIndex = (pendingIndex + 1) % letters.length
        } else {
            commitPending()
            pendingKey = digit
            pendingIndex = 0
        }
        cancel(commit)
        schedule(commit, COMMIT_MS)
        notifyChange()
    }

    /**
     * The key was held (called once, after [press] already ran for the same key-down): the letter that
     * press started becomes the digit itself. In 123 mode the press already typed the digit.
     */
    fun longPress(digit: Int) {
        if (mode == Mode.DIGITS) return
        cancel(commit)
        if (pendingKey == digit) pendingKey = -1 else commitPending()
        committed.append('0' + digit)
        notifyChange()
    }

    /** `#`: next input mode (commits a letter still cycling). */
    fun nextMode() {
        commitPending()
        mode = Mode.entries[(mode.ordinal + 1) % Mode.entries.size]
        notifyChange()
    }

    /** Deletes the pending letter or the last committed one. Returns false if already empty. */
    fun backspace(): Boolean {
        cancel(commit)
        if (pendingKey >= 0) {
            pendingKey = -1
        } else if (committed.isNotEmpty()) {
            committed.deleteCharAt(committed.length - 1)
        } else {
            return false
        }
        notifyChange()
        return true
    }

    fun clear() {
        cancel(commit)
        pendingKey = -1
        committed.setLength(0)
        notifyChange()
    }

    private fun commitPending() {
        cancel(commit)
        pendingChar()?.let { committed.append(it) }
        pendingKey = -1
        notifyChange()
    }

    private fun pendingChar(): Char? {
        if (pendingKey < 0) return null
        val c = KEYS[pendingKey][pendingIndex]
        return when (mode) {
            Mode.UPPER -> c.uppercaseChar()
            Mode.SENTENCE -> if (atSentenceStart()) c.uppercaseChar() else c
            else -> c
        }
    }

    /** Start of the text, or after ". " — where Abc mode capitalises. */
    private fun atSentenceStart(): Boolean {
        val t = committed.trimEnd()
        return t.isEmpty() || (t.last() == '.' && committed.endsWith(" "))
    }

    private fun notifyChange() = onChange(committed.toString(), pendingChar())

    companion object {
        const val COMMIT_MS = 900L
        val KEYS = arrayOf(" 0", ".'-&1", "abc2", "def3", "ghi4", "jkl5", "mno6", "pqrs7", "tuv8", "wxyz9")
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    }
}
