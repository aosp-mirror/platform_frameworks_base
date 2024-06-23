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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.logging.BiometricMessageDeferralLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@android.platform.test.annotations.EnabledOnRavenwood
class FaceHelpMessageDeferralTest : SysuiTestCase() {
    val threshold = .75f
    @Mock lateinit var logger: BiometricMessageDeferralLogger
    @Mock lateinit var dumpManager: DumpManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testProcessFrame_logs() {
        val biometricMessageDeferral = createMsgDeferral(setOf(1))
        biometricMessageDeferral.processFrame(1)
        verify(logger).logFrameProcessed(1, 1, "1")
    }

    @Test
    fun testUpdateMessage_logs() {
        val biometricMessageDeferral = createMsgDeferral(setOf(1))
        biometricMessageDeferral.updateMessage(1, "hi")
        verify(logger).logUpdateMessage(1, "hi")
    }

    @Test
    fun testReset_logs() {
        val biometricMessageDeferral = createMsgDeferral(setOf(1))
        biometricMessageDeferral.reset()
        verify(logger).reset()
    }

    @Test
    fun testProcessNoMessages_noDeferredMessage() {
        val biometricMessageDeferral = createMsgDeferral(emptySet())

        assertNull(biometricMessageDeferral.getDeferredMessage())
    }

    @Test
    fun testProcessNonDeferredMessages_noDeferredMessage() {
        val biometricMessageDeferral = createMsgDeferral(setOf(1, 2))

        // WHEN there are no deferred messages processed
        for (i in 0..3) {
            biometricMessageDeferral.processFrame(4)
            biometricMessageDeferral.updateMessage(4, "test")
        }

        // THEN getDeferredMessage is null
        assertNull(biometricMessageDeferral.getDeferredMessage())
    }

    @Test
    fun testProcessMessagesWithDeferredMessage_deferredMessageWasNeverGivenAString() {
        val biometricMessageDeferral = createMsgDeferral(setOf(1, 2))

        biometricMessageDeferral.processFrame(1)

        assertNull(biometricMessageDeferral.getDeferredMessage())
    }

    @Test
    fun testAllProcessedMessagesWereDeferred() {
        val biometricMessageDeferral = createMsgDeferral(setOf(1))

        // WHEN all the processed messages are a deferred message
        for (i in 0..3) {
            biometricMessageDeferral.processFrame(1)
            biometricMessageDeferral.updateMessage(1, "test")
        }

        // THEN deferredMessage will return the string associated with the deferred msgId
        assertEquals("test", biometricMessageDeferral.getDeferredMessage())
    }

    @Test
    fun testReturnsMostFrequentDeferredMessage() {
        val biometricMessageDeferral = createMsgDeferral(setOf(1, 2))

        // WHEN there's 80%of the messages are msgId=1 and 20% is msgId=2
        biometricMessageDeferral.processFrame(1)
        biometricMessageDeferral.processFrame(1)
        biometricMessageDeferral.processFrame(1)
        biometricMessageDeferral.processFrame(1)
        biometricMessageDeferral.updateMessage(1, "msgId-1")

        biometricMessageDeferral.processFrame(2)
        biometricMessageDeferral.updateMessage(2, "msgId-2")

        // THEN the most frequent deferred message is that meets the threshold is returned
        assertEquals("msgId-1", biometricMessageDeferral.getDeferredMessage())
    }

    @Test
    fun testDeferredMessage_mustMeetThreshold() {
        val biometricMessageDeferral = createMsgDeferral(setOf(1))

        // WHEN more nonDeferredMessages are shown than the deferred message
        val totalMessages = 10
        val nonDeferredMessagesCount = (totalMessages * threshold).toInt() + 1
        for (i in 0 until nonDeferredMessagesCount) {
            biometricMessageDeferral.processFrame(4)
            biometricMessageDeferral.updateMessage(4, "non-deferred-msg")
        }
        for (i in nonDeferredMessagesCount until totalMessages) {
            biometricMessageDeferral.processFrame(1)
            biometricMessageDeferral.updateMessage(1, "msgId-1")
        }

        // THEN there's no deferred message because it didn't meet the threshold
        assertNull(biometricMessageDeferral.getDeferredMessage())
    }

    @Test
    fun testResetClearsOutCounts() {
        val biometricMessageDeferral = createMsgDeferral(setOf(1, 2))

        // GIVEN two msgId=1 events processed
        biometricMessageDeferral.processFrame(
            1,
        )
        biometricMessageDeferral.updateMessage(1, "msgId-1")
        biometricMessageDeferral.processFrame(1)
        biometricMessageDeferral.updateMessage(1, "msgId-1")

        // WHEN counts are reset and then a single deferred message is processed (msgId=2)
        biometricMessageDeferral.reset()
        biometricMessageDeferral.processFrame(2)
        biometricMessageDeferral.updateMessage(2, "msgId-2")

        // THEN msgId-2 is the deferred message since the two msgId=1 events were reset
        assertEquals("msgId-2", biometricMessageDeferral.getDeferredMessage())
    }

    @Test
    fun testShouldDefer() {
        // GIVEN should defer msgIds 1 and 2
        val biometricMessageDeferral = createMsgDeferral(setOf(1, 2))

        // THEN shouldDefer returns true for ids 1 & 2
        assertTrue(biometricMessageDeferral.shouldDefer(1))
        assertTrue(biometricMessageDeferral.shouldDefer(2))

        // THEN should defer returns false for ids 3 & 4
        assertFalse(biometricMessageDeferral.shouldDefer(3))
        assertFalse(biometricMessageDeferral.shouldDefer(4))
    }

    @Test
    fun testDeferredMessage_meetThresholdWithIgnoredFrames() {
        val biometricMessageDeferral =
            createMsgDeferral(
                messagesToDefer = setOf(1),
                acquiredInfoToIgnore = setOf(4),
            )

        // WHEN more nonDeferredMessages are shown than the deferred message; HOWEVER the
        // nonDeferredMessages are in acquiredInfoToIgnore
        val totalMessages = 10
        val nonDeferredMessagesCount = (totalMessages * threshold).toInt() + 1
        for (i in 0 until nonDeferredMessagesCount) {
            biometricMessageDeferral.processFrame(4)
            biometricMessageDeferral.updateMessage(4, "non-deferred-msg")
        }
        for (i in nonDeferredMessagesCount until totalMessages) {
            biometricMessageDeferral.processFrame(1)
            biometricMessageDeferral.updateMessage(1, "msgId-1")
        }

        // THEN the deferred message met the threshold excluding the acquiredInfoToIgnore,
        // so the message id deferred
        assertTrue(biometricMessageDeferral.shouldDefer(1))
        assertEquals("msgId-1", biometricMessageDeferral.getDeferredMessage())
    }

    private fun createMsgDeferral(
        messagesToDefer: Set<Int>,
        acquiredInfoToIgnore: Set<Int> = emptySet(),
    ): BiometricMessageDeferral {
        return BiometricMessageDeferral(
            messagesToDefer,
            acquiredInfoToIgnore,
            threshold,
            logger,
            dumpManager,
            "0",
        )
    }
}
