/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.biometrics

import android.util.Log
import com.android.systemui.deviceentry.shared.model.HelpFaceAuthenticationStatus

/**
 * Debounces face help messages with parameters:
 * - window: Window of time (in milliseconds) to analyze face acquired messages)
 * - startWindow: Window of time on start required before showing the first help message
 * - shownFaceMessageFrequencyBoost: Frequency boost given to messages that are currently shown to
 *   the user
 * - threshold: minimum percentage of frames a message must appear in order to show it
 */
class FaceHelpMessageDebouncer(
    private val window: Long = DEFAULT_WINDOW_MS,
    private val startWindow: Long = window,
    private val shownFaceMessageFrequencyBoost: Int = 4,
    private val threshold: Float = 0f,
) {
    private val TAG = "FaceHelpMessageDebouncer"
    private var startTime = 0L
    private var helpFaceAuthStatuses: MutableList<HelpFaceAuthenticationStatus> = mutableListOf()
    private var lastMessageIdShown: Int? = null

    /** Remove messages that are outside of the time [window]. */
    private fun removeOldMessages(currTimestamp: Long) {
        var numToRemove = 0
        // This works under the assumption that timestamps are ordered from first to last
        // in chronological order
        for (index in helpFaceAuthStatuses.indices) {
            if ((helpFaceAuthStatuses[index].createdAt + window) >= currTimestamp) {
                break // all timestamps from here and on are within the window
            }
            numToRemove += 1
        }

        // Remove all outside time window
        repeat(numToRemove) { helpFaceAuthStatuses.removeFirst() }

        if (numToRemove > 0) {
            Log.v(TAG, "removedFirst=$numToRemove")
        }
    }

    private fun getMostFrequentHelpMessageSurpassingThreshold(): HelpFaceAuthenticationStatus? {
        // freqMap: msgId => frequency
        val freqMap = helpFaceAuthStatuses.groupingBy { it.msgId }.eachCount().toMutableMap()

        // Give shownFaceMessageFrequencyBoost to lastMessageIdShown
        if (lastMessageIdShown != null) {
            freqMap.computeIfPresent(lastMessageIdShown!!) { _, value ->
                value + shownFaceMessageFrequencyBoost
            }
        }
        // Go through all msgId keys & find the highest frequency msgId
        val msgIdWithHighestFrequency =
            freqMap.entries
                .maxWithOrNull { (msgId1, freq1), (msgId2, freq2) ->
                    // ties are broken by more recent message
                    if (freq1 == freq2) {
                        helpFaceAuthStatuses
                            .findLast { it.msgId == msgId1 }!!
                            .createdAt
                            .compareTo(
                                helpFaceAuthStatuses.findLast { it.msgId == msgId2 }!!.createdAt
                            )
                    } else {
                        freq1.compareTo(freq2)
                    }
                }
                ?.key

        if (msgIdWithHighestFrequency == null) {
            return null
        }

        val freq =
            if (msgIdWithHighestFrequency == lastMessageIdShown) {
                    freqMap[msgIdWithHighestFrequency]!! - shownFaceMessageFrequencyBoost
                } else {
                    freqMap[msgIdWithHighestFrequency]!!
                }
                .toFloat()

        return if ((freq / helpFaceAuthStatuses.size.toFloat()) >= threshold) {
            helpFaceAuthStatuses.findLast { it.msgId == msgIdWithHighestFrequency }
        } else {
            Log.v(TAG, "most frequent helpFaceAuthStatus didn't make the threshold: $threshold")
            null
        }
    }

    fun addMessage(helpFaceAuthStatus: HelpFaceAuthenticationStatus) {
        helpFaceAuthStatuses.add(helpFaceAuthStatus)
        Log.v(TAG, "added message=$helpFaceAuthStatus")
    }

    fun getMessageToShow(atTimestamp: Long): HelpFaceAuthenticationStatus? {
        if (helpFaceAuthStatuses.isEmpty() || (atTimestamp - startTime) < startWindow) {
            // there's not enough time that has passed to determine whether to show anything yet
            Log.v(TAG, "No message; haven't made initial threshold window OR no messages")
            return null
        }
        removeOldMessages(atTimestamp)
        val messageToShow = getMostFrequentHelpMessageSurpassingThreshold()
        if (lastMessageIdShown != messageToShow?.msgId) {
            Log.v(
                TAG,
                "showMessage previousLastMessageId=$lastMessageIdShown" +
                    "\n\tmessageToShow=$messageToShow " +
                    "\n\thelpFaceAuthStatusesSize=${helpFaceAuthStatuses.size}" +
                    "\n\thelpFaceAuthStatuses=$helpFaceAuthStatuses" +
                    "\n\tthreshold=$threshold"
            )
            lastMessageIdShown = messageToShow?.msgId
        }
        return messageToShow
    }

    fun startNewFaceAuthSession(faceAuthStartedTime: Long) {
        Log.d(TAG, "startNewFaceAuthSession at startTime=$startTime")
        startTime = faceAuthStartedTime
        helpFaceAuthStatuses.clear()
        lastMessageIdShown = null
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 200L
    }
}
