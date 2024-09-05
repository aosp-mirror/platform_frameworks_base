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

import android.hardware.biometrics.BiometricFaceConstants
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.deviceentry.shared.model.HelpFaceAuthenticationStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
@TestableLooper.RunWithLooper
class FaceHelpMessageDebouncerTest : SysuiTestCase() {
    private lateinit var underTest: FaceHelpMessageDebouncer
    private val window = 9L
    private val startWindow = 4L
    private val shownFaceMessageFrequencyBoost = 2

    @Before
    fun setUp() {
        underTest =
            FaceHelpMessageDebouncer(
                window = window,
                startWindow = startWindow,
                shownFaceMessageFrequencyBoost = shownFaceMessageFrequencyBoost,
            )
    }

    @Test
    fun getMessageBeforeStartWindow_null() {
        underTest.addMessage(
            HelpFaceAuthenticationStatus(
                BiometricFaceConstants.FACE_ACQUIRED_TOO_CLOSE,
                "testTooClose",
                0
            )
        )
        assertThat(underTest.getMessageToShow(0)).isNull()
    }

    @Test
    fun getMessageAfterStartWindow() {
        underTest.addMessage(
            HelpFaceAuthenticationStatus(
                BiometricFaceConstants.FACE_ACQUIRED_TOO_CLOSE,
                "tooClose",
                0
            )
        )

        assertThat(underTest.getMessageToShow(startWindow)?.msgId)
            .isEqualTo(BiometricFaceConstants.FACE_ACQUIRED_TOO_CLOSE)
        assertThat(underTest.getMessageToShow(startWindow)?.msg).isEqualTo("tooClose")
    }

    @Test
    fun getMessageAfterMessagesCleared_null() {
        underTest.addMessage(
            HelpFaceAuthenticationStatus(
                BiometricFaceConstants.FACE_ACQUIRED_TOO_CLOSE,
                "tooClose",
                0
            )
        )
        underTest.startNewFaceAuthSession(0)

        assertThat(underTest.getMessageToShow(startWindow)).isNull()
    }

    @Test
    fun messagesBeforeWindowRemoved() {
        underTest.addMessage(
            HelpFaceAuthenticationStatus(
                BiometricFaceConstants.FACE_ACQUIRED_TOO_CLOSE,
                "tooClose",
                0
            )
        )
        underTest.addMessage(
            HelpFaceAuthenticationStatus(
                BiometricFaceConstants.FACE_ACQUIRED_TOO_CLOSE,
                "tooClose",
                0
            )
        )
        underTest.addMessage(
            HelpFaceAuthenticationStatus(
                BiometricFaceConstants.FACE_ACQUIRED_TOO_CLOSE,
                "tooClose",
                window - 1
            )
        )
        val lastMessage =
            HelpFaceAuthenticationStatus(
                BiometricFaceConstants.FACE_ACQUIRED_TOO_BRIGHT,
                "tooBright",
                window
            )
        underTest.addMessage(lastMessage)

        assertThat(underTest.getMessageToShow(window + 1)).isEqualTo(lastMessage)
    }

    @Test
    fun getMessageTieGoesToMostRecent() {
        for (i in 1..window step 2) {
            underTest.addMessage(
                HelpFaceAuthenticationStatus(
                    BiometricFaceConstants.FACE_ACQUIRED_TOO_CLOSE,
                    "tooClose",
                    i
                )
            )
            underTest.addMessage(
                HelpFaceAuthenticationStatus(
                    BiometricFaceConstants.FACE_ACQUIRED_TOO_BRIGHT,
                    "tooBright",
                    i + 1
                )
            )
        }

        assertThat(underTest.getMessageToShow(window)?.msgId)
            .isEqualTo(BiometricFaceConstants.FACE_ACQUIRED_TOO_BRIGHT)
        assertThat(underTest.getMessageToShow(window)?.msg).isEqualTo("tooBright")
    }

    @Test
    fun boostCurrentlyShowingMessage() {
        underTest.addMessage(
            HelpFaceAuthenticationStatus(
                BiometricFaceConstants.FACE_ACQUIRED_TOO_BRIGHT,
                "tooBright",
                0
            )
        )

        val lastMessageShown = underTest.getMessageToShow(startWindow)
        assertThat(lastMessageShown?.msgId)
            .isEqualTo(BiometricFaceConstants.FACE_ACQUIRED_TOO_BRIGHT)

        for (i in 1..<shownFaceMessageFrequencyBoost) {
            underTest.addMessage(
                HelpFaceAuthenticationStatus(
                    BiometricFaceConstants.FACE_ACQUIRED_TOO_CLOSE,
                    "tooClose",
                    startWindow
                )
            )
        }

        // although technically there's a different msgId with a higher frequency count now, the
        // shownFaceMessageFrequencyBoost causes the last message shown to get a "boost"
        // to keep showing
        assertThat(underTest.getMessageToShow(startWindow)).isEqualTo(lastMessageShown)
    }

    @Test
    fun overcomeBoostedCurrentlyShowingMessage() {
        // Comments are assuming shownFaceMessageFrequencyBoost = 2
        // [B], weights: B=1
        underTest.addMessage(
            HelpFaceAuthenticationStatus(
                BiometricFaceConstants.FACE_ACQUIRED_TOO_BRIGHT,
                "tooBright",
                0
            )
        )

        // [B], showing messageB, weights: B=3
        val messageB = underTest.getMessageToShow(startWindow)

        // [B, C, C], showing messageB, weights: B=3, C=2
        for (i in 1..shownFaceMessageFrequencyBoost) {
            underTest.addMessage(
                HelpFaceAuthenticationStatus(
                    BiometricFaceConstants.FACE_ACQUIRED_TOO_CLOSE,
                    "tooClose",
                    startWindow
                )
            )
        }
        // messageB is getting boosted to continue to show
        assertThat(underTest.getMessageToShow(startWindow)).isEqualTo(messageB)

        // receive one more FACE_ACQUIRED_TOO_CLOSE acquired info to pass the boost
        // [C, C, C], showing messageB, weights: B=2, C=3
        underTest.addMessage(
            HelpFaceAuthenticationStatus(
                BiometricFaceConstants.FACE_ACQUIRED_TOO_CLOSE,
                "tooClose",
                startWindow
            )
        )

        // Now FACE_ACQUIRED_TOO_CLOSE has surpassed the boosted messageB frequency
        // [C, C, C], showing messageC, weights: C=5
        assertThat(underTest.getMessageToShow(startWindow)?.msgId)
            .isEqualTo(BiometricFaceConstants.FACE_ACQUIRED_TOO_CLOSE)
    }

    @Test
    fun messageMustMeetThreshold() {
        underTest =
            FaceHelpMessageDebouncer(
                window = window,
                startWindow = 0,
                shownFaceMessageFrequencyBoost = 0,
                threshold = .8f,
            )

        underTest.addMessage(
            HelpFaceAuthenticationStatus(
                BiometricFaceConstants.FACE_ACQUIRED_TOO_CLOSE,
                "tooClose",
                0
            )
        )
        underTest.addMessage(
            HelpFaceAuthenticationStatus(
                BiometricFaceConstants.FACE_ACQUIRED_TOO_CLOSE,
                "tooClose",
                0
            )
        )
        underTest.addMessage(
            HelpFaceAuthenticationStatus(
                BiometricFaceConstants.FACE_ACQUIRED_TOO_BRIGHT,
                "tooBright",
                0
            )
        )

        // although tooClose message is the majority, it doesn't meet the 80% threshold
        assertThat(underTest.getMessageToShow(startWindow)).isNull()

        underTest.addMessage(
            HelpFaceAuthenticationStatus(
                BiometricFaceConstants.FACE_ACQUIRED_TOO_CLOSE,
                "tooClose",
                0
            )
        )
        underTest.addMessage(
            HelpFaceAuthenticationStatus(
                BiometricFaceConstants.FACE_ACQUIRED_TOO_CLOSE,
                "tooClose",
                0
            )
        )

        // message shows once it meets the threshold
        assertThat(underTest.getMessageToShow(startWindow)?.msgId)
            .isEqualTo(BiometricFaceConstants.FACE_ACQUIRED_TOO_CLOSE)
        assertThat(underTest.getMessageToShow(startWindow)?.msg).isEqualTo("tooClose")
    }
}
