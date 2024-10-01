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

package com.android.systemui.biometrics.ui.binder

import android.testing.TestableLooper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.layoutInflater
import android.view.windowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.airbnb.lottie.LottieAnimationView
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.biometrics.updateSfpsIndicatorRequests
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.display.data.repository.displayStateRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.eq
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.firstValue

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class SideFpsOverlayViewBinderTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    @JvmField @Rule var mockitoRule: MockitoRule = MockitoJUnit.rule()
    @Mock private lateinit var layoutInflater: LayoutInflater
    @Mock private lateinit var sideFpsView: View
    @Captor private lateinit var viewCaptor: ArgumentCaptor<View>

    @Before
    fun setup() {
        allowTestableLooperAsMainThread() // repeatWhenAttached requires the main thread
        kosmos.layoutInflater = layoutInflater
        context.addMockSystemService(WindowManager::class.java, kosmos.windowManager)
        `when`(layoutInflater.inflate(R.layout.sidefps_view, null, false)).thenReturn(sideFpsView)
        `when`(sideFpsView.requireViewById<LottieAnimationView>(eq(R.id.sidefps_animation)))
            .thenReturn(mock(LottieAnimationView::class.java))
    }

    @Test
    fun verifyIndicatorNotAdded_whenInRearDisplayMode() {
        kosmos.testScope.runTest {
            setupTestConfiguration(isInRearDisplayMode = true)
            updateSfpsIndicatorRequests(kosmos, mContext, primaryBouncerRequest = true)
            verify(kosmos.windowManager, never()).addView(any(), any())
        }
    }

    @Test
    fun verifyIndicatorShowAndHide_onPrimaryBouncerShowAndHide() {
        kosmos.testScope.runTest {
            setupTestConfiguration(isInRearDisplayMode = false)
            updateSfpsIndicatorRequests(kosmos, mContext, primaryBouncerRequest = true)
            runCurrent()

            verify(kosmos.windowManager).addView(any(), any())

            // Hide primary bouncer
            updateSfpsIndicatorRequests(kosmos, mContext, primaryBouncerRequest = false)
            runCurrent()

            verify(kosmos.windowManager).removeView(any())
        }
    }

    @Test
    fun verifyIndicatorShowAndHide_onAlternateBouncerShowAndHide() {
        kosmos.testScope.runTest {
            setupTestConfiguration(isInRearDisplayMode = false)
            updateSfpsIndicatorRequests(kosmos, mContext, alternateBouncerRequest = true)
            runCurrent()

            verify(kosmos.windowManager).addView(any(), any())

            verify(kosmos.windowManager).addView(viewCaptor.capture(), any())
            verify(viewCaptor.firstValue)
                .announceForAccessibility(
                    mContext.getText(R.string.accessibility_side_fingerprint_indicator_label)
                )

            updateSfpsIndicatorRequests(kosmos, mContext, alternateBouncerRequest = false)
            runCurrent()

            verify(kosmos.windowManager).removeView(any())
        }
    }

    @Test
    fun verifyIndicatorShownAndHidden_onSystemServerAuthenticationStartedAndStopped() {
        kosmos.testScope.runTest {
            setupTestConfiguration(isInRearDisplayMode = false)
            updateSfpsIndicatorRequests(kosmos, mContext, biometricPromptRequest = true)
            runCurrent()

            verify(kosmos.windowManager).addView(any(), any())

            // System server authentication stopped
            updateSfpsIndicatorRequests(kosmos, mContext, biometricPromptRequest = false)
            runCurrent()

            verify(kosmos.windowManager).removeView(any())
        }
    }

    // On progress bar shown - hide indicator
    // On progress bar hidden - show indicator
    // TODO(b/365182034): update + enable when rest to unlock feature is implemented
    @Ignore("b/365182034")
    @Test
    fun verifyIndicatorProgressBarInteraction() {
        kosmos.testScope.runTest {
            // Pre-auth conditions
            setupTestConfiguration(isInRearDisplayMode = false)
            updateSfpsIndicatorRequests(kosmos, mContext, primaryBouncerRequest = true)
            runCurrent()

            val inOrder = inOrder(kosmos.windowManager)
            // Verify indicator shown
            inOrder.verify(kosmos.windowManager).addView(any(), any())

            // Set progress bar visible
            updateSfpsIndicatorRequests(
                kosmos,
                mContext,
                primaryBouncerRequest = true,
            ) // , progressBarShowing = true)
            runCurrent()

            // Verify indicator hidden
            inOrder.verify(kosmos.windowManager).removeView(any())

            // Set progress bar invisible
            updateSfpsIndicatorRequests(
                kosmos,
                mContext,
                primaryBouncerRequest = true,
            ) // , progressBarShowing = false)
            runCurrent()

            // Verify indicator shown
            inOrder.verify(kosmos.windowManager).addView(any(), any())
        }
    }

    private suspend fun TestScope.setupTestConfiguration(isInRearDisplayMode: Boolean) {
        kosmos.fingerprintPropertyRepository.setProperties(
            sensorId = 1,
            strength = SensorStrength.STRONG,
            sensorType = FingerprintSensorType.POWER_BUTTON,
            sensorLocations = emptyMap(),
        )

        kosmos.displayStateRepository.setIsInRearDisplayMode(isInRearDisplayMode)
        kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_0)
        kosmos.displayRepository.emitDisplayChangeEvent(0)
        kosmos.sideFpsOverlayViewBinder.start()
        runCurrent()
    }
}
