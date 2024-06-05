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
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig.OnTriggeredResult
import com.android.systemui.qrcodescanner.controller.QRCodeScannerController
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@android.platform.test.annotations.EnabledOnRavenwood
class QrCodeScannerKeyguardQuickAffordanceConfigTest : SysuiTestCase() {

    @Mock private lateinit var controller: QRCodeScannerController

    private lateinit var underTest: QrCodeScannerKeyguardQuickAffordanceConfig

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(controller.intent).thenReturn(INTENT_1)

        underTest = QrCodeScannerKeyguardQuickAffordanceConfig(context, controller)
    }

    @Test
    fun affordance_setsUpRegistrationAndDeliversInitialModel() = runBlockingTest {
        whenever(controller.isEnabledForLockScreenButton).thenReturn(true)
        var latest: KeyguardQuickAffordanceConfig.LockScreenState? = null

        val job = underTest.lockScreenState.onEach { latest = it }.launchIn(this)

        val callbackCaptor = argumentCaptor<QRCodeScannerController.Callback>()
        verify(controller).addCallback(callbackCaptor.capture())
        verify(controller)
            .registerQRCodeScannerChangeObservers(
                QRCodeScannerController.DEFAULT_QR_CODE_SCANNER_CHANGE,
                QRCodeScannerController.QR_CODE_SCANNER_PREFERENCE_CHANGE
            )
        assertVisibleState(latest)

        job.cancel()
        verify(controller).removeCallback(callbackCaptor.value)
    }

    @Test
    fun affordance_scannerActivityChanged_deliversModelWithUpdatedIntent() = runBlockingTest {
        whenever(controller.isEnabledForLockScreenButton).thenReturn(true)
        var latest: KeyguardQuickAffordanceConfig.LockScreenState? = null
        val job = underTest.lockScreenState.onEach { latest = it }.launchIn(this)
        val callbackCaptor = argumentCaptor<QRCodeScannerController.Callback>()
        verify(controller).addCallback(callbackCaptor.capture())

        whenever(controller.intent).thenReturn(INTENT_2)
        callbackCaptor.value.onQRCodeScannerActivityChanged()

        assertVisibleState(latest)

        job.cancel()
        verify(controller).removeCallback(callbackCaptor.value)
    }

    @Test
    fun affordance_scannerPreferenceChanged_deliversVisibleModel() = runBlockingTest {
        var latest: KeyguardQuickAffordanceConfig.LockScreenState? = null
        val job = underTest.lockScreenState.onEach { latest = it }.launchIn(this)
        val callbackCaptor = argumentCaptor<QRCodeScannerController.Callback>()
        verify(controller).addCallback(callbackCaptor.capture())

        whenever(controller.isEnabledForLockScreenButton).thenReturn(true)
        callbackCaptor.value.onQRCodeScannerPreferenceChanged()

        assertVisibleState(latest)

        job.cancel()
        verify(controller).removeCallback(callbackCaptor.value)
    }

    @Test
    fun affordance_scannerPreferenceChanged_deliversNone() = runBlockingTest {
        var latest: KeyguardQuickAffordanceConfig.LockScreenState? = null
        val job = underTest.lockScreenState.onEach { latest = it }.launchIn(this)
        val callbackCaptor = argumentCaptor<QRCodeScannerController.Callback>()
        verify(controller).addCallback(callbackCaptor.capture())

        whenever(controller.isEnabledForLockScreenButton).thenReturn(false)
        callbackCaptor.value.onQRCodeScannerPreferenceChanged()

        assertThat(latest).isEqualTo(KeyguardQuickAffordanceConfig.LockScreenState.Hidden)

        job.cancel()
        verify(controller).removeCallback(callbackCaptor.value)
    }

    @Test
    fun onQuickAffordanceTriggered() {
        assertThat(underTest.onTriggered(mock()))
            .isEqualTo(
                OnTriggeredResult.StartActivity(
                    intent = INTENT_1,
                    canShowWhileLocked = true,
                )
            )
    }

    @Test
    fun getPickerScreenState_enabledIfConfiguredOnDevice_isEnabledForPickerState() = runTest {
        whenever(controller.isAllowedOnLockScreen).thenReturn(true)
        whenever(controller.isAbleToLaunchScannerActivity).thenReturn(true)

        assertThat(underTest.getPickerScreenState())
            .isEqualTo(KeyguardQuickAffordanceConfig.PickerScreenState.Default())
    }

    @Test
    fun getPickerScreenState_disabledIfConfiguredOnDevice_isDisabledForPickerState() = runTest {
        whenever(controller.isAllowedOnLockScreen).thenReturn(true)
        whenever(controller.isAbleToLaunchScannerActivity).thenReturn(false)

        assertThat(underTest.getPickerScreenState())
            .isEqualTo(KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice)
    }

    private fun assertVisibleState(latest: KeyguardQuickAffordanceConfig.LockScreenState?) {
        assertThat(latest)
            .isInstanceOf(KeyguardQuickAffordanceConfig.LockScreenState.Visible::class.java)
        val visibleState = latest as KeyguardQuickAffordanceConfig.LockScreenState.Visible
        assertThat(visibleState.icon).isNotNull()
        assertThat(visibleState.icon.contentDescription).isNotNull()
    }

    companion object {
        private val INTENT_1 = Intent("intent1")
        private val INTENT_2 = Intent("intent2")
    }
}
