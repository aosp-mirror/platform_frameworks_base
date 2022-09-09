/*
 * Copyright (C) 2022 The Android Open Source Project
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

/**
 * Provides whether an acquired error message should be shown immediately when its received (see
 * [shouldDefer]) or should be shown when the biometric error is received [getDeferredMessage].
 * @property excludedMessages messages that are excluded from counts
 * @property messagesToDefer messages that shouldn't show immediately when received, but may be
 * shown later if the message is the most frequent message processed and meets [THRESHOLD]
 * percentage of all messages (excluding [excludedMessages])
 */
class BiometricMessageDeferral(
    private val excludedMessages: Set<Int>,
    private val messagesToDefer: Set<Int>
) {
    private val msgCounts: MutableMap<Int, Int> = HashMap() // msgId => frequency of msg
    private val msgIdToCharSequence: MutableMap<Int, CharSequence> = HashMap() // msgId => message
    private var totalRelevantMessages = 0
    private var mostFrequentMsgIdToDefer: Int? = null

    /** Reset all saved counts. */
    fun reset() {
        totalRelevantMessages = 0
        msgCounts.clear()
        msgIdToCharSequence.clear()
    }

    /** Whether the given message should be deferred instead of being shown immediately. */
    fun shouldDefer(acquiredMsgId: Int): Boolean {
        return messagesToDefer.contains(acquiredMsgId)
    }

    /**
     * Adds the acquiredMsgId to the counts if it's not in [excludedMessages]. We still count
     * messages that shouldn't be deferred in these counts.
     */
    fun processMessage(acquiredMsgId: Int, helpString: CharSequence) {
        if (excludedMessages.contains(acquiredMsgId)) {
            return
        }

        totalRelevantMessages++
        msgIdToCharSequence[acquiredMsgId] = helpString

        val newAcquiredMsgCount = msgCounts.getOrDefault(acquiredMsgId, 0) + 1
        msgCounts[acquiredMsgId] = newAcquiredMsgCount
        if (
            messagesToDefer.contains(acquiredMsgId) &&
                (mostFrequentMsgIdToDefer == null ||
                    newAcquiredMsgCount > msgCounts.getOrDefault(mostFrequentMsgIdToDefer!!, 0))
        ) {
            mostFrequentMsgIdToDefer = acquiredMsgId
        }
    }

    /**
     * Get the most frequent deferred message that meets the [THRESHOLD] percentage of processed
     * messages excluding [excludedMessages].
     * @return null if no messages have been deferred OR deferred messages didn't meet the
     * [THRESHOLD] percentage of messages to show.
     */
    fun getDeferredMessage(): CharSequence? {
        mostFrequentMsgIdToDefer?.let {
            if (msgCounts.getOrDefault(it, 0) > (THRESHOLD * totalRelevantMessages)) {
                return msgIdToCharSequence[mostFrequentMsgIdToDefer]
            }
        }

        return null
    }
    companion object {
        const val THRESHOLD = .5f
    }
}
