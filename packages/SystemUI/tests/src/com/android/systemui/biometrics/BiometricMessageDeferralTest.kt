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

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class BiometricMessageDeferralTest : SysuiTestCase() {

    @Test
    fun testProcessNoMessages_noDeferredMessage() {
        val biometricMessageDeferral = BiometricMessageDeferral(setOf(), setOf())

        assertNull(biometricMessageDeferral.getDeferredMessage())
    }

    @Test
    fun testProcessNonDeferredMessages_noDeferredMessage() {
        val biometricMessageDeferral = BiometricMessageDeferral(setOf(), setOf(1, 2))

        // WHEN there are no deferred messages processed
        for (i in 0..3) {
            biometricMessageDeferral.processMessage(4, "test")
        }

        // THEN getDeferredMessage is null
        assertNull(biometricMessageDeferral.getDeferredMessage())
    }

    @Test
    fun testAllProcessedMessagesWereDeferred() {
        val biometricMessageDeferral = BiometricMessageDeferral(setOf(), setOf(1))

        // WHEN all the processed messages are a deferred message
        for (i in 0..3) {
            biometricMessageDeferral.processMessage(1, "test")
        }

        // THEN deferredMessage will return the string associated with the deferred msgId
        assertEquals("test", biometricMessageDeferral.getDeferredMessage())
    }

    @Test
    fun testReturnsMostFrequentDeferredMessage() {
        val biometricMessageDeferral = BiometricMessageDeferral(setOf(), setOf(1, 2))

        // WHEN there's two msgId=1 processed and one msgId=2 processed
        biometricMessageDeferral.processMessage(1, "msgId-1")
        biometricMessageDeferral.processMessage(1, "msgId-1")
        biometricMessageDeferral.processMessage(1, "msgId-1")
        biometricMessageDeferral.processMessage(2, "msgId-2")

        // THEN the most frequent deferred message is that meets the threshold is returned
        assertEquals("msgId-1", biometricMessageDeferral.getDeferredMessage())
    }

    @Test
    fun testDeferredMessage_mustMeetThreshold() {
        val biometricMessageDeferral = BiometricMessageDeferral(setOf(), setOf(1))

        // WHEN more nonDeferredMessages are shown than the deferred message
        val totalMessages = 10
        val nonDeferredMessagesCount =
            (totalMessages * BiometricMessageDeferral.THRESHOLD).toInt() + 1
        for (i in 0 until nonDeferredMessagesCount) {
            biometricMessageDeferral.processMessage(4, "non-deferred-msg")
        }
        for (i in nonDeferredMessagesCount until totalMessages) {
            biometricMessageDeferral.processMessage(1, "msgId-1")
        }

        // THEN there's no deferred message because it didn't meet the threshold
        assertNull(biometricMessageDeferral.getDeferredMessage())
    }

    @Test
    fun testDeferredMessage_manyExcludedMessages_getDeferredMessage() {
        val biometricMessageDeferral = BiometricMessageDeferral(setOf(3), setOf(1))

        // WHEN more excludedMessages are shown than the deferred message
        val totalMessages = 10
        val excludedMessagesCount = (totalMessages * BiometricMessageDeferral.THRESHOLD).toInt() + 1
        for (i in 0 until excludedMessagesCount) {
            biometricMessageDeferral.processMessage(3, "excluded-msg")
        }
        for (i in excludedMessagesCount until totalMessages) {
            biometricMessageDeferral.processMessage(1, "msgId-1")
        }

        // THEN there IS a deferred message because the deferred msg meets the threshold amongst the
        // non-excluded messages
        assertEquals("msgId-1", biometricMessageDeferral.getDeferredMessage())
    }

    @Test
    fun testResetClearsOutCounts() {
        val biometricMessageDeferral = BiometricMessageDeferral(setOf(), setOf(1, 2))

        // GIVEN two msgId=1 events processed
        biometricMessageDeferral.processMessage(1, "msgId-1")
        biometricMessageDeferral.processMessage(1, "msgId-1")

        // WHEN counts are reset and then a single deferred message is processed (msgId=2)
        biometricMessageDeferral.reset()
        biometricMessageDeferral.processMessage(2, "msgId-2")

        // THEN msgId-2 is the deferred message since the two msgId=1 events were reset
        assertEquals("msgId-2", biometricMessageDeferral.getDeferredMessage())
    }

    @Test
    fun testShouldDefer() {
        // GIVEN should defer msgIds 1 and 2
        val biometricMessageDeferral = BiometricMessageDeferral(setOf(3), setOf(1, 2))

        // THEN shouldDefer returns true for ids 1 & 2
        assertTrue(biometricMessageDeferral.shouldDefer(1))
        assertTrue(biometricMessageDeferral.shouldDefer(2))

        // THEN should defer returns false for ids 3 & 4
        assertFalse(biometricMessageDeferral.shouldDefer(3))
        assertFalse(biometricMessageDeferral.shouldDefer(4))
    }
}
