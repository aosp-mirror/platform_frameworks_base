/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.quickaffordance

import android.content.Intent
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.domain.quickaffordance.KeyguardQuickAffordanceConfig.OnClickedResult
import com.android.systemui.qrcodescanner.controller.QRCodeScannerController
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class QrCodeScannerKeyguardQuickAffordanceConfigTest : SysuiTestCase() {

    @Mock private lateinit var controller: QRCodeScannerController

    private lateinit var underTest: QrCodeScannerKeyguardQuickAffordanceConfig

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(controller.intent).thenReturn(INTENT_1)

        underTest = QrCodeScannerKeyguardQuickAffordanceConfig(controller)
    }

    @Test
    fun `affordance - sets up registration and delivers initial model`() = runBlockingTest {
        whenever(controller.isEnabledForLockScreenButton).thenReturn(true)
        var latest: KeyguardQuickAffordanceConfig.State? = null

        val job = underTest.state.onEach { latest = it }.launchIn(this)

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
    fun `affordance - scanner activity changed - delivers model with updated intent`() =
        runBlockingTest {
            whenever(controller.isEnabledForLockScreenButton).thenReturn(true)
            var latest: KeyguardQuickAffordanceConfig.State? = null
            val job = underTest.state.onEach { latest = it }.launchIn(this)
            val callbackCaptor = argumentCaptor<QRCodeScannerController.Callback>()
            verify(controller).addCallback(callbackCaptor.capture())

            whenever(controller.intent).thenReturn(INTENT_2)
            callbackCaptor.value.onQRCodeScannerActivityChanged()

            assertVisibleState(latest)

            job.cancel()
            verify(controller).removeCallback(callbackCaptor.value)
        }

    @Test
    fun `affordance - scanner preference changed - delivers visible model`() = runBlockingTest {
        var latest: KeyguardQuickAffordanceConfig.State? = null
        val job = underTest.state.onEach { latest = it }.launchIn(this)
        val callbackCaptor = argumentCaptor<QRCodeScannerController.Callback>()
        verify(controller).addCallback(callbackCaptor.capture())

        whenever(controller.isEnabledForLockScreenButton).thenReturn(true)
        callbackCaptor.value.onQRCodeScannerPreferenceChanged()

        assertVisibleState(latest)

        job.cancel()
        verify(controller).removeCallback(callbackCaptor.value)
    }

    @Test
    fun `affordance - scanner preference changed - delivers none`() = runBlockingTest {
        var latest: KeyguardQuickAffordanceConfig.State? = null
        val job = underTest.state.onEach { latest = it }.launchIn(this)
        val callbackCaptor = argumentCaptor<QRCodeScannerController.Callback>()
        verify(controller).addCallback(callbackCaptor.capture())

        whenever(controller.isEnabledForLockScreenButton).thenReturn(false)
        callbackCaptor.value.onQRCodeScannerPreferenceChanged()

        assertThat(latest).isEqualTo(KeyguardQuickAffordanceConfig.State.Hidden)

        job.cancel()
        verify(controller).removeCallback(callbackCaptor.value)
    }

    @Test
    fun onQuickAffordanceClicked() {
        assertThat(underTest.onQuickAffordanceClicked(mock()))
            .isEqualTo(
                OnClickedResult.StartActivity(
                    intent = INTENT_1,
                    canShowWhileLocked = true,
                )
            )
    }

    private fun assertVisibleState(latest: KeyguardQuickAffordanceConfig.State?) {
        assertThat(latest).isInstanceOf(KeyguardQuickAffordanceConfig.State.Visible::class.java)
        val visibleState = latest as KeyguardQuickAffordanceConfig.State.Visible
        assertThat(visibleState.icon).isNotNull()
        assertThat(visibleState.contentDescriptionResourceId).isNotNull()
    }

    companion object {
        private val INTENT_1 = Intent("intent1")
        private val INTENT_2 = Intent("intent2")
    }
}
