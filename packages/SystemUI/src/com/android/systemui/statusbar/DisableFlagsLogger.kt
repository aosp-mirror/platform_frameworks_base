/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.statusbar

import android.app.StatusBarManager.DISABLE_BACK
import android.app.StatusBarManager.DISABLE_CLOCK
import android.app.StatusBarManager.DISABLE_EXPAND
import android.app.StatusBarManager.DISABLE_HOME
import android.app.StatusBarManager.DISABLE_NOTIFICATION_ICONS
import android.app.StatusBarManager.DISABLE_NOTIFICATION_ALERTS
import android.app.StatusBarManager.DISABLE_ONGOING_CALL_CHIP
import android.app.StatusBarManager.DISABLE_RECENT
import android.app.StatusBarManager.DISABLE_SEARCH
import android.app.StatusBarManager.DISABLE_SYSTEM_INFO
import android.app.StatusBarManager.DISABLE2_GLOBAL_ACTIONS
import android.app.StatusBarManager.DISABLE2_NOTIFICATION_SHADE
import android.app.StatusBarManager.DISABLE2_ROTATE_SUGGESTIONS
import android.app.StatusBarManager.DISABLE2_SYSTEM_ICONS
import android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/**
 * A singleton that creates concise but readable strings representing the values of the disable
 * flags for debugging.
 *
 * See [CommandQueue.disable] for information about disable flags.
 *
 * Note that, for both lists passed in, each flag must have a distinct [DisableFlag.flagIsSetSymbol]
 * and distinct [DisableFlag.flagNotSetSymbol] within the list. If this isn't true, the logs could
 * be ambiguous so an [IllegalArgumentException] is thrown.
 */
@SysUISingleton
class DisableFlagsLogger constructor(
    private val disable1FlagsList: List<DisableFlag>,
    private val disable2FlagsList: List<DisableFlag>
) {

    @Inject
    constructor() : this(defaultDisable1FlagsList, defaultDisable2FlagsList)

    init {
        if (flagsListHasDuplicateSymbols(disable1FlagsList)) {
            throw IllegalArgumentException("disable1 flags must have unique symbols")
        }
        if (flagsListHasDuplicateSymbols(disable2FlagsList)) {
            throw IllegalArgumentException("disable2 flags must have unique symbols")
        }
    }

    private fun flagsListHasDuplicateSymbols(list: List<DisableFlag>): Boolean {
        val numDistinctFlagOffStatus = list.map { it.getFlagStatus(0) }.distinct().count()
        val numDistinctFlagOnStatus = list
                .map { it.getFlagStatus(Int.MAX_VALUE) }
                .distinct()
                .count()
        return numDistinctFlagOffStatus < list.count() || numDistinctFlagOnStatus < list.count()
    }

    /**
     * Returns a string representing the, old, new, and new-after-modification disable flag states,
     * as well as the differences between each of the states.
     *
     * Example:
     *   Old: EnaiHbcRso.qINgr | New: EnaihBcRso.qiNGR (hB.iGR) | New after local modification:
     *   EnaihBcRso.qInGR (.n)
     *
     * A capital character signifies the flag is set and a lowercase character signifies that the
     * flag isn't set. The flag states will be logged in the same order as the passed-in lists.
     *
     * The difference between states is written between parentheses, and won't be included if there
     * is no difference. the new-after-modification state also won't be included if there's no
     * difference from the new state.
     *
     * @param old the disable state that had been previously sent.
     * @param new the new disable state that has just been sent.
     * @param newAfterLocalModification the new disable states after a class has locally modified
     *   them. Null if the class does not locally modify.
     */
    fun getDisableFlagsString(
        old: DisableState,
        new: DisableState,
        newAfterLocalModification: DisableState? = null
    ): String {
        val builder = StringBuilder("Received new disable state. ")
        builder.append("Old: ")
        builder.append(getFlagsString(old))
        builder.append(" | New: ")
        if (old != new) {
            builder.append(getFlagsStringWithDiff(old, new))
        } else {
            builder.append(getFlagsString(old))
        }

        if (newAfterLocalModification != null && new != newAfterLocalModification) {
            builder.append(" | New after local modification: ")
            builder.append(getFlagsStringWithDiff(new, newAfterLocalModification))
        }

        return builder.toString()
    }

    /**
     * Returns a string representing [new] state, as well as the difference from [old] to [new]
     * (if there is one).
     */
    private fun getFlagsStringWithDiff(old: DisableState, new: DisableState): String {
        val builder = StringBuilder()
        builder.append(getFlagsString(new))
        builder.append(" ")
        builder.append(getDiffString(old, new))
        return builder.toString()
    }

    /**
     * Returns a string representing the difference between [old] and [new], or an empty string if
     * there is no difference.
     *
     * For example, if old was "abc.DE" and new was "aBC.De", the difference returned would be
     * "(BC.e)".
     */
    private fun getDiffString(old: DisableState, new: DisableState): String {
        if (old == new) {
            return ""
        }

        val builder = StringBuilder("(")
        disable1FlagsList.forEach {
            val newSymbol = it.getFlagStatus(new.disable1)
            if (it.getFlagStatus(old.disable1) != newSymbol) {
                builder.append(newSymbol)
            }
        }
        builder.append(".")
        disable2FlagsList.forEach {
            val newSymbol = it.getFlagStatus(new.disable2)
            if (it.getFlagStatus(old.disable2) != newSymbol) {
                builder.append(newSymbol)
            }
        }
        builder.append(")")
        return builder.toString()
    }

    /** Returns a string representing the disable flag states, e.g. "EnaihBcRso.qiNGR".  */
    private fun getFlagsString(state: DisableState): String {
        val builder = StringBuilder("")
        disable1FlagsList.forEach { builder.append(it.getFlagStatus(state.disable1)) }
        builder.append(".")
        disable2FlagsList.forEach { builder.append(it.getFlagStatus(state.disable2)) }
        return builder.toString()
    }

    /** A POJO representing each disable flag. */
    class DisableFlag(
        private val bitMask: Int,
        private val flagIsSetSymbol: Char,
        private val flagNotSetSymbol: Char
    ) {

        /**
         * Returns a character representing whether or not this flag is set in [state].
         *
         * A capital character signifies the flag is set and a lowercase character signifies that
         * the flag isn't set.
         */
        internal fun getFlagStatus(state: Int): Char =
            if (0 != state and this.bitMask) this.flagIsSetSymbol
            else this.flagNotSetSymbol
    }

    /** POJO to hold [disable1] and [disable2]. */
    data class DisableState(val disable1: Int, val disable2: Int)
}

// LINT.IfChange
private val defaultDisable1FlagsList: List<DisableFlagsLogger.DisableFlag> = listOf(
        DisableFlagsLogger.DisableFlag(DISABLE_EXPAND, 'E', 'e'),
        DisableFlagsLogger.DisableFlag(DISABLE_NOTIFICATION_ICONS, 'N', 'n'),
        DisableFlagsLogger.DisableFlag(DISABLE_NOTIFICATION_ALERTS, 'A', 'a'),
        DisableFlagsLogger.DisableFlag(DISABLE_SYSTEM_INFO, 'I', 'i'),
        DisableFlagsLogger.DisableFlag(DISABLE_HOME, 'H', 'h'),
        DisableFlagsLogger.DisableFlag(DISABLE_BACK, 'B', 'b'),
        DisableFlagsLogger.DisableFlag(DISABLE_CLOCK, 'C', 'c'),
        DisableFlagsLogger.DisableFlag(DISABLE_RECENT, 'R', 'r'),
        DisableFlagsLogger.DisableFlag(DISABLE_SEARCH, 'S', 's'),
        DisableFlagsLogger.DisableFlag(DISABLE_ONGOING_CALL_CHIP, 'O', 'o')
)

private val defaultDisable2FlagsList: List<DisableFlagsLogger.DisableFlag> = listOf(
        DisableFlagsLogger.DisableFlag(DISABLE2_QUICK_SETTINGS, 'Q', 'q'),
        DisableFlagsLogger.DisableFlag(DISABLE2_SYSTEM_ICONS, 'I', 'i'),
        DisableFlagsLogger.DisableFlag(DISABLE2_NOTIFICATION_SHADE, 'N', 'n'),
        DisableFlagsLogger.DisableFlag(DISABLE2_GLOBAL_ACTIONS, 'G', 'g'),
        DisableFlagsLogger.DisableFlag(DISABLE2_ROTATE_SUGGESTIONS, 'R', 'r')
)
// LINT.ThenChange(frameworks/base/core/java/android/app/StatusBarManager.java)