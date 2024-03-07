package com.android.systemui.bouncer.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.ui.viewmodel.EntryToken.ClearAll
import com.android.systemui.bouncer.ui.viewmodel.EntryToken.Digit
import com.android.systemui.bouncer.ui.viewmodel.PinInputSubject.Companion.assertThat
import com.android.systemui.bouncer.ui.viewmodel.PinInputViewModel.Companion.empty
import com.google.common.truth.Fact
import com.google.common.truth.FailureMetadata
import com.google.common.truth.Subject
import com.google.common.truth.Subject.Factory
import com.google.common.truth.Truth.assertAbout
import com.google.common.truth.Truth.assertThat
import java.lang.Character.isDigit
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This test uses a mnemonic code to create and verify PinInput instances: strings of digits [0-9]
 * for [Digit] tokens, as well as a `C` for the [ClearAll] token.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class PinInputViewModelTest : SysuiTestCase() {

    @Test
    fun create_emptyList_throws() {
        assertThrows(IllegalArgumentException::class.java) { PinInputViewModel(emptyList()) }
    }

    @Test
    fun create_inputWithoutLeadingClearAll_throws() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                PinInputViewModel(listOf(Digit(0)))
            }
        assertThat(exception).hasMessageThat().contains("does not begin with a ClearAll token")
    }

    @Test
    fun create_inputNotInAscendingOrder_throws() {
        val sentinel = ClearAll()
        val first = Digit(0)
        val second = Digit(1)
        // [first] is created before [second] is created, thus their sequence numbers are ordered.
        check(first.sequenceNumber < second.sequenceNumber)

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                // Passing the [Digit] tokens in reverse order throws.
                PinInputViewModel(listOf(sentinel, second, first))
            }
        assertThat(exception).hasMessageThat().contains("EntryTokens are not sorted")
    }

    @Test
    fun append_digitToEmptyInput() {
        val result = empty().append(0)
        assertThat(result).matches("C0")
    }

    @Test
    fun append_digitToExistingPin() {
        val subject = pinInput("C1")
        assertThat(subject.append(2)).matches("C12")
    }

    @Test
    fun append_withTwoCompletePinsEntered_dropsFirst() {
        val subject = pinInput("C12C34C")
        assertThat(subject.append(5)).matches("C34C5")
    }

    @Test
    fun deleteLast_removesLastDigit() {
        val subject = pinInput("C12")
        assertThat(subject.deleteLast()).matches("C1")
    }

    @Test
    fun deleteLast_onEmptyInput_returnsSameInstance() {
        val subject = empty()
        assertThat(subject.deleteLast()).isSameInstanceAs(subject)
    }

    @Test
    fun deleteLast_onInputEndingInClearAll_returnsSameInstance() {
        val subject = pinInput("C12C")
        assertThat(subject.deleteLast()).isSameInstanceAs(subject)
    }

    @Test
    fun clearAll_appendsClearAllEntryToExistingInput() {
        val subject = pinInput("C12")
        assertThat(subject.clearAll()).matches("C12C")
    }

    @Test
    fun clearAll_onInputEndingInClearAll_returnsSameInstance() {
        val subject = pinInput("C12C")
        assertThat(subject.clearAll()).isSameInstanceAs(subject)
    }

    @Test
    fun clearAll_retainsUpToTwoPinEntries() {
        val subject = pinInput("C12C34")
        assertThat(subject.clearAll()).matches("C12C34C")
    }

    @Test
    fun isEmpty_onEmptyInput_returnsTrue() {
        val subject = empty()
        assertThat(subject.isEmpty()).isTrue()
    }

    @Test
    fun isEmpty_whenLastEntryIsDigit_returnsFalse() {
        val subject = pinInput("C1234")
        assertThat(subject.isEmpty()).isFalse()
    }

    @Test
    fun isEmpty_whenLastEntryIsClearAll_returnsTrue() {
        val subject = pinInput("C1234C")
        assertThat(subject.isEmpty()).isTrue()
    }

    @Test
    fun getPin_onEmptyInput_returnsEmptyList() {
        val subject = empty()
        assertThat(subject.getPin()).isEmpty()
    }

    @Test
    fun getPin_whenLastEntryIsDigit_returnsPin() {
        val subject = pinInput("C1234")
        assertThat(subject.getPin()).containsExactly(1, 2, 3, 4)
    }

    @Test
    fun getPin_withMultiplePins_returnsLastPin() {
        val subject = pinInput("C1234C5678")
        assertThat(subject.getPin()).containsExactly(5, 6, 7, 8)
    }

    @Test
    fun getPin_whenLastEntryIsClearAll_returnsEmptyList() {
        val subject = pinInput("C1234C")
        assertThat(subject.getPin()).isEmpty()
    }

    @Test
    fun mostRecentClearAllMarker_onEmptyInput_returnsSentinel() {
        val subject = empty()
        val sentinel = subject.input[0] as ClearAll

        assertThat(subject.mostRecentClearAll()).isSameInstanceAs(sentinel)
    }

    @Test
    fun mostRecentClearAllMarker_whenLastEntryIsDigit_returnsSentinel() {
        val subject = pinInput("C1234")
        val sentinel = subject.input[0] as ClearAll

        assertThat(subject.mostRecentClearAll()).isSameInstanceAs(sentinel)
    }

    @Test
    fun mostRecentClearAllMarker_withMultiplePins_returnsLastMarker() {
        val subject = pinInput("C1234C5678")
        val lastMarker = subject.input[5] as ClearAll

        assertThat(subject.mostRecentClearAll()).isSameInstanceAs(lastMarker)
    }

    @Test
    fun mostRecentClearAllMarker_whenLastEntryIsClearAll_returnsLastEntry() {
        val subject = pinInput("C1234C")
        val lastEntry = subject.input[5] as ClearAll

        assertThat(subject.mostRecentClearAll()).isSameInstanceAs(lastEntry)
    }

    @Test
    fun getDigits_invalidClearAllMarker_onEmptyInput_returnsEmptyList() {
        val subject = empty()
        assertThat(subject.getDigits(ClearAll())).isEmpty()
    }

    @Test
    fun getDigits_invalidClearAllMarker_whenLastEntryIsDigit_returnsEmptyList() {
        val subject = pinInput("C1234")
        assertThat(subject.getDigits(ClearAll())).isEmpty()
    }

    @Test
    fun getDigits_clearAllMarkerPointsToFirstPin_returnsFirstPinDigits() {
        val subject = pinInput("C1234C5678")
        val marker = subject.input[0] as ClearAll

        assertThat(subject.getDigits(marker).map { it.input }).containsExactly(1, 2, 3, 4)
    }

    @Test
    fun getDigits_clearAllMarkerPointsToLastPin_returnsLastPinDigits() {
        val subject = pinInput("C1234C5678")
        val marker = subject.input[5] as ClearAll

        assertThat(subject.getDigits(marker).map { it.input }).containsExactly(5, 6, 7, 8)
    }

    @Test
    fun entryToken_equality() {
        val clearAll = ClearAll()
        val zero = Digit(0)
        val one = Digit(1)

        // Guava's EqualsTester is not available in this codebase.
        assertThat(zero.equals(zero.copy())).isTrue()

        assertThat(zero.equals(one)).isFalse()
        assertThat(zero.equals(clearAll)).isFalse()

        assertThat(clearAll.equals(clearAll.copy())).isTrue()
        assertThat(clearAll.equals(zero)).isFalse()

        // Not equal when the sequence number does not match
        assertThat(zero.equals(Digit(0))).isFalse()
        assertThat(clearAll.equals(ClearAll())).isFalse()
    }

    private fun pinInput(mnemonics: String): PinInputViewModel {
        return PinInputViewModel(
            mnemonics.map {
                when {
                    it == 'C' -> ClearAll()
                    isDigit(it) -> Digit(it.digitToInt())
                    else -> throw AssertionError()
                }
            }
        )
    }
}

private class PinInputSubject
private constructor(metadata: FailureMetadata, private val actual: PinInputViewModel) :
    Subject(metadata, actual) {

    fun matches(mnemonics: String) {
        val actualMnemonics =
            actual.input
                .map { entry ->
                    when (entry) {
                        is Digit -> entry.input.digitToChar()
                        is ClearAll -> 'C'
                        else -> throw IllegalArgumentException()
                    }
                }
                .joinToString(separator = "")

        if (mnemonics != actualMnemonics) {
            failWithActual(
                Fact.simpleFact(
                    "expected pin input to be '$mnemonics' but is '$actualMnemonics' instead"
                )
            )
        }
    }

    companion object {
        fun assertThat(input: PinInputViewModel): PinInputSubject =
            assertAbout(Factory(::PinInputSubject)).that(input)
    }
}
