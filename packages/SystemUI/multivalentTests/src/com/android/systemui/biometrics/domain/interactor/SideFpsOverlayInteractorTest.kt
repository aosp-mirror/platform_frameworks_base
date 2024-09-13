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

package com.android.systemui.biometrics.domain.interactor

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.biometricStatusRepository
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.biometrics.shared.model.AuthenticationReason
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.biometrics.updateSfpsIndicatorRequests
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.display.data.repository.displayStateRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class SideFpsOverlayInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val underTest = kosmos.sideFpsOverlayInteractor

    @Test
    fun verifyIsShowingFalse_whenInRearDisplayMode() {
        kosmos.testScope.runTest {
            val isShowing by collectLastValue(underTest.isShowing)
            setupTestConfiguration(isInRearDisplayMode = true)

            updateSfpsIndicatorRequests(kosmos, mContext, primaryBouncerRequest = true)
            runCurrent()

            assertThat(isShowing).isFalse()
        }
    }

    @Test
    fun verifyIsShowingUpdates_onPrimaryBouncerShowAndHide() {
        kosmos.testScope.runTest {
            val isShowing by collectLastValue(underTest.isShowing)
            setupTestConfiguration(isInRearDisplayMode = false)

            // Show primary bouncer
            updateSfpsIndicatorRequests(kosmos, mContext, primaryBouncerRequest = true)
            runCurrent()

            assertThat(isShowing).isTrue()

            // Hide primary bouncer
            updateSfpsIndicatorRequests(kosmos, mContext, primaryBouncerRequest = false)
            runCurrent()

            assertThat(isShowing).isFalse()
        }
    }

    @Test
    fun verifyIsShowingUpdates_onAlternateBouncerShowAndHide() {
        kosmos.testScope.runTest {
            val isShowing by collectLastValue(underTest.isShowing)
            setupTestConfiguration(isInRearDisplayMode = false)

            updateSfpsIndicatorRequests(kosmos, mContext, alternateBouncerRequest = true)
            runCurrent()

            assertThat(isShowing).isTrue()

            // Hide alternate bouncer
            updateSfpsIndicatorRequests(kosmos, mContext, alternateBouncerRequest = false)
            runCurrent()

            assertThat(isShowing).isFalse()
        }
    }

    @Test
    fun verifyIsShowingUpdates_onSystemServerAuthenticationStartedAndStopped() {
        kosmos.testScope.runTest {
            val isShowing by collectLastValue(underTest.isShowing)
            setupTestConfiguration(isInRearDisplayMode = false)

            updateSfpsIndicatorRequests(kosmos, mContext, biometricPromptRequest = true)
            runCurrent()

            assertThat(isShowing).isTrue()

            // System server authentication stopped
            updateSfpsIndicatorRequests(kosmos, mContext, biometricPromptRequest = false)
            runCurrent()

            assertThat(isShowing).isFalse()
        }
    }

    // On progress bar shown - hide indicator
    // On progress bar hidden - show indicator
    // TODO(b/365182034): update + enable when rest to unlock feature is implemented
    @Ignore("b/365182034")
    @Test
    fun verifyIsShowingUpdates_onProgressBarInteraction() {
        kosmos.testScope.runTest {
            val isShowing by collectLastValue(underTest.isShowing)
            setupTestConfiguration(isInRearDisplayMode = false)

            updateSfpsIndicatorRequests(kosmos, mContext, primaryBouncerRequest = true)
            runCurrent()

            assertThat(isShowing).isTrue()

            //            updateSfpsIndicatorRequests(
            //                kosmos, mContext, primaryBouncerRequest = true, progressBarShowing =
            // true
            //            )
            runCurrent()

            assertThat(isShowing).isFalse()

            // Set progress bar invisible
            //            updateSfpsIndicatorRequests(
            //                kosmos, mContext, primaryBouncerRequest = true, progressBarShowing =
            // false
            //            )
            runCurrent()

            // Verify indicator shown
            assertThat(isShowing).isTrue()
        }
    }

    private suspend fun TestScope.setupTestConfiguration(isInRearDisplayMode: Boolean) {
        kosmos.fingerprintPropertyRepository.setProperties(
            sensorId = 1,
            strength = SensorStrength.STRONG,
            sensorType = FingerprintSensorType.POWER_BUTTON,
            sensorLocations = emptyMap()
        )

        kosmos.displayStateRepository.setIsInRearDisplayMode(isInRearDisplayMode)
        kosmos.displayRepository.emitDisplayChangeEvent(0)
        runCurrent()

        kosmos.biometricStatusRepository.setFingerprintAuthenticationReason(
            AuthenticationReason.NotRunning
        )
        // TODO(b/365182034): set progress bar visibility once rest to unlock feature is implemented
    }
}
