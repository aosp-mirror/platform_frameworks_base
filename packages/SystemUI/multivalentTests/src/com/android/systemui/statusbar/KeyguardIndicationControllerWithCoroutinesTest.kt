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

package com.android.systemui.statusbar

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_BIOMETRIC_MESSAGE
import com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP
import com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_TRUST
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.times
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class KeyguardIndicationControllerWithCoroutinesTest : KeyguardIndicationControllerBaseTest() {
    @Test
    fun onTrustAgentErrorMessageDelayed_fingerprintEngaged() {
        createController()
        mController.setVisible(true)

        // GIVEN fingerprint is engaged
        whenever(mDeviceEntryFingerprintAuthInteractor.isEngaged).thenReturn(MutableStateFlow(true))

        // WHEN a trust agent error message arrives
        mKeyguardUpdateMonitorCallback.onTrustAgentErrorMessage("testMessage")
        mExecutor.runAllReady()

        // THEN no message shows immediately since fingerprint is engaged
        verifyNoMessage(INDICATION_TYPE_TRUST)
        verifyNoMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE)
        verifyNoMessage(INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP)

        // WHEN fingerprint is no longer engaged
        whenever(mDeviceEntryFingerprintAuthInteractor.isEngaged)
            .thenReturn(MutableStateFlow(false))
        mController.mIsFingerprintEngagedCallback.accept(false)
        mExecutor.runAllReady()

        // THEN the message will show
        verifyIndicationShown(INDICATION_TYPE_BIOMETRIC_MESSAGE, "testMessage")
    }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
