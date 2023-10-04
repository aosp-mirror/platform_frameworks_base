/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.bouncer.ui.viewmodel

import androidx.annotation.VisibleForTesting
import com.android.systemui.bouncer.ui.viewmodel.EntryToken.ClearAll
import com.android.systemui.bouncer.ui.viewmodel.EntryToken.Digit

/**
 * Immutable pin input state.
 *
 * The input is a hybrid of state ([Digit]) and event ([ClearAll]) tokens. The [ClearAll] token can
 * be interpreted as a watermark, indicating that the current input up to that point is deleted
 * (after a auth failure or when long-pressing the delete button). Therefore, [Digit]s following a
 * [ClearAll] make up the next pin input entry. Up to two complete pin inputs are memoized.
 *
 * This is required when auto-confirm rejects the input, and the last digit will be animated-in at
 * the end of the input, concurrently with the staggered clear-all animation starting to play at the
 * beginning of the input.
 *
 * The input is guaranteed to always contain a initial [ClearAll] token as a sentinel, thus clients
 * can always assume there is a 'ClearAll' watermark available.
 */
data class PinInputViewModel(
    @get:VisibleForTesting(otherwise = VisibleForTesting.PRIVATE) val input: List<EntryToken>
) {
    init {
        require(input.firstOrNull() is ClearAll) { "input does not begin with a ClearAll token" }
        require(input.zipWithNext().all { it.first < it.second }) {
            "EntryTokens are not sorted by their sequenceNumber"
        }
    }
    /**
     * [PinInputViewModel] with [previousInput] and appended [newToken].
     *
     * [previousInput] is trimmed so that the new [PinBouncerViewModel] contains at most two pin
     * inputs.
     */
    private constructor(
        previousInput: List<EntryToken>,
        newToken: EntryToken
    ) : this(
        buildList {
            addAll(
                previousInput.subList(previousInput.indexOfLastClearAllToKeep(), previousInput.size)
            )
            add(newToken)
        }
    )

    fun append(digit: Int): PinInputViewModel {
        return PinInputViewModel(input, Digit(digit))
    }

    /**
     * Delete last digit.
     *
     * This removes the last digit from the input. Returns `this` if the last token is [ClearAll].
     */
    fun deleteLast(): PinInputViewModel {
        if (isEmpty()) return this
        return PinInputViewModel(input.take(input.size - 1))
    }

    /**
     * Appends a [ClearAll] watermark, completing the current pin.
     *
     * Returns `this` if the last token is [ClearAll].
     */
    fun clearAll(): PinInputViewModel {
        if (isEmpty()) return this
        return PinInputViewModel(input, ClearAll())
    }

    /** Whether the current pin is empty. */
    fun isEmpty(): Boolean {
        return input.last() is ClearAll
    }

    /** The current pin, or an empty list if [isEmpty]. */
    fun getPin(): List<Int> {
        return getDigits(mostRecentClearAll()).map { it.input }
    }

    /**
     * The digits following the specified [ClearAll] marker, up to the next marker or the end of the
     * input.
     *
     * Returns an empty list if the [ClearAll] is not in the input.
     */
    fun getDigits(clearAllMarker: ClearAll): List<Digit> {
        val startIndex = input.indexOf(clearAllMarker) + 1
        if (startIndex == 0 || startIndex == input.size) return emptyList()

        return input.subList(startIndex, input.size).takeWhile { it is Digit }.map { it as Digit }
    }

    /** The most recent [ClearAll] marker. */
    fun mostRecentClearAll(): ClearAll {
        return input.last { it is ClearAll } as ClearAll
    }

    companion object {
        fun empty() = PinInputViewModel(listOf(ClearAll()))
    }
}

/**
 * Pin bouncer entry token with a [sequenceNumber] to indicate input event ordering.
 *
 * Since the model only allows appending/removing [Digit]s from the end, the [sequenceNumber] is
 * strictly increasing in input order of the pin, but not guaranteed to be monotonic or start at a
 * specific number.
 */
sealed interface EntryToken : Comparable<EntryToken> {
    val sequenceNumber: Int

    /** The pin bouncer [input] as digits 0-9. */
    data class Digit(val input: Int, override val sequenceNumber: Int = nextSequenceNumber++) :
        EntryToken {
        init {
            check(input in 0..9)
        }
    }

    /**
     * Marker to indicate the input is completely cleared, and subsequent [EntryToken]s mark a new
     * pin entry.
     */
    data class ClearAll(override val sequenceNumber: Int = nextSequenceNumber++) : EntryToken

    override fun compareTo(other: EntryToken): Int =
        compareValuesBy(this, other, EntryToken::sequenceNumber)

    companion object {
        private var nextSequenceNumber = 1
    }
}

/**
 * Index of the last [ClearAll] token to keep for a new [PinInputViewModel], so that after appending
 * another [EntryToken], there are at most two pin inputs in the [PinInputViewModel].
 */
private fun List<EntryToken>.indexOfLastClearAllToKeep(): Int {
    require(isNotEmpty() && first() is ClearAll)

    var seenClearAll = 0
    for (i in size - 1 downTo 0) {
        if (get(i) is ClearAll) {
            seenClearAll++
            if (seenClearAll == 2) {
                return i
            }
        }
    }

    // The first element is guaranteed to be a ClearAll marker.
    return 0
}
