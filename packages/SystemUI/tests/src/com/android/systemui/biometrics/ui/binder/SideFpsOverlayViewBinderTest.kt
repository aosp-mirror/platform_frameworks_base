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

import android.animation.Animator
import android.graphics.Rect
import android.hardware.biometrics.SensorLocationInternal
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManagerGlobal
import android.testing.TestableLooper
import android.view.Display
import android.view.DisplayInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewPropertyAnimator
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import android.view.layoutInflater
import android.view.windowManager
import androidx.test.filters.SmallTest
import com.airbnb.lottie.LottieAnimationView
import com.android.keyguard.keyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.FingerprintInteractiveToAuthProvider
import com.android.systemui.biometrics.data.repository.biometricStatusRepository
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.biometrics.shared.model.AuthenticationReason
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.bouncer.data.repository.keyguardBouncerRepository
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.display.data.repository.displayStateRepository
import com.android.systemui.keyguard.ui.viewmodel.sideFpsProgressBarViewModel
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class SideFpsOverlayViewBinderTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    @JvmField @Rule var mockitoRule: MockitoRule = MockitoJUnit.rule()
    @Mock private lateinit var displayManager: DisplayManager
    @Mock
    private lateinit var fingerprintInteractiveToAuthProvider: FingerprintInteractiveToAuthProvider
    @Mock private lateinit var layoutInflater: LayoutInflater
    @Mock private lateinit var sideFpsView: View

    private val contextDisplayInfo = DisplayInfo()

    private var displayWidth: Int = 0
    private var displayHeight: Int = 0
    private var boundsWidth: Int = 0
    private var boundsHeight: Int = 0

    private lateinit var deviceConfig: DeviceConfig
    private lateinit var sensorLocation: SensorLocationInternal

    enum class DeviceConfig {
        X_ALIGNED,
        Y_ALIGNED,
    }

    @Before
    fun setup() {
        allowTestableLooperAsMainThread() // repeatWhenAttached requires the main thread

        mContext = spy(mContext)

        val resources = mContext.resources
        whenever(mContext.display)
            .thenReturn(
                Display(mock(DisplayManagerGlobal::class.java), 1, contextDisplayInfo, resources)
            )

        kosmos.layoutInflater = layoutInflater

        whenever(fingerprintInteractiveToAuthProvider.enabledForCurrentUser)
            .thenReturn(MutableStateFlow(false))

        context.addMockSystemService(DisplayManager::class.java, displayManager)
        context.addMockSystemService(WindowManager::class.java, kosmos.windowManager)

        `when`(layoutInflater.inflate(R.layout.sidefps_view, null, false)).thenReturn(sideFpsView)
        `when`(sideFpsView.requireViewById<LottieAnimationView>(eq(R.id.sidefps_animation)))
            .thenReturn(mock(LottieAnimationView::class.java))
        with(mock(ViewPropertyAnimator::class.java)) {
            `when`(sideFpsView.animate()).thenReturn(this)
            `when`(alpha(Mockito.anyFloat())).thenReturn(this)
            `when`(setStartDelay(Mockito.anyLong())).thenReturn(this)
            `when`(setDuration(Mockito.anyLong())).thenReturn(this)
            `when`(setListener(any())).thenAnswer {
                (it.arguments[0] as Animator.AnimatorListener).onAnimationEnd(
                    mock(Animator::class.java)
                )
                this
            }
        }
    }

    @Test
    fun verifyIndicatorNotAdded_whenInRearDisplayMode() {
        kosmos.testScope.runTest {
            setupTestConfiguration(
                DeviceConfig.X_ALIGNED,
                rotation = DisplayRotation.ROTATION_0,
                isInRearDisplayMode = true
            )
            kosmos.biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.NotRunning
            )
            kosmos.sideFpsProgressBarViewModel.setVisible(false)
            updatePrimaryBouncer(
                isShowing = true,
                isAnimatingAway = false,
                fpsDetectionRunning = true,
                isUnlockingWithFpAllowed = true
            )
            runCurrent()

            verify(kosmos.windowManager, never()).addView(any(), any())
        }
    }

    @Test
    fun verifyIndicatorShowAndHide_onPrimaryBouncerShowAndHide() {
        kosmos.testScope.runTest {
            setupTestConfiguration(
                DeviceConfig.X_ALIGNED,
                rotation = DisplayRotation.ROTATION_0,
                isInRearDisplayMode = false
            )
            kosmos.biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.NotRunning
            )
            kosmos.sideFpsProgressBarViewModel.setVisible(false)
            // Show primary bouncer
            updatePrimaryBouncer(
                isShowing = true,
                isAnimatingAway = false,
                fpsDetectionRunning = true,
                isUnlockingWithFpAllowed = true
            )
            runCurrent()

            verify(kosmos.windowManager).addView(any(), any())

            // Hide primary bouncer
            updatePrimaryBouncer(
                isShowing = false,
                isAnimatingAway = false,
                fpsDetectionRunning = true,
                isUnlockingWithFpAllowed = true
            )
            runCurrent()

            verify(kosmos.windowManager).removeView(any())
        }
    }

    @Test
    fun verifyIndicatorShowAndHide_onAlternateBouncerShowAndHide() {
        kosmos.testScope.runTest {
            setupTestConfiguration(
                DeviceConfig.X_ALIGNED,
                rotation = DisplayRotation.ROTATION_0,
                isInRearDisplayMode = false
            )
            kosmos.biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.NotRunning
            )
            kosmos.sideFpsProgressBarViewModel.setVisible(false)
            // Show alternate bouncer
            kosmos.keyguardBouncerRepository.setAlternateVisible(true)
            runCurrent()

            verify(kosmos.windowManager).addView(any(), any())

            // Hide alternate bouncer
            kosmos.keyguardBouncerRepository.setAlternateVisible(false)
            runCurrent()

            verify(kosmos.windowManager).removeView(any())
        }
    }

    @Test
    fun verifyIndicatorShownAndHidden_onSystemServerAuthenticationStartedAndStopped() {
        kosmos.testScope.runTest {
            setupTestConfiguration(
                DeviceConfig.X_ALIGNED,
                rotation = DisplayRotation.ROTATION_0,
                isInRearDisplayMode = false
            )
            kosmos.sideFpsProgressBarViewModel.setVisible(false)
            updatePrimaryBouncer(
                isShowing = false,
                isAnimatingAway = false,
                fpsDetectionRunning = true,
                isUnlockingWithFpAllowed = true
            )
            // System server authentication started
            kosmos.biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.BiometricPromptAuthentication
            )
            runCurrent()

            verify(kosmos.windowManager).addView(any(), any())

            // System server authentication stopped
            kosmos.biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.NotRunning
            )
            runCurrent()

            verify(kosmos.windowManager).removeView(any())
        }
    }

    // On progress bar shown - hide indicator
    // On progress bar hidden - show indicator
    @Test
    fun verifyIndicatorProgressBarInteraction() {
        kosmos.testScope.runTest {
            // Pre-auth conditions
            setupTestConfiguration(
                DeviceConfig.X_ALIGNED,
                rotation = DisplayRotation.ROTATION_0,
                isInRearDisplayMode = false
            )
            kosmos.biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.NotRunning
            )
            kosmos.sideFpsProgressBarViewModel.setVisible(false)

            // Show primary bouncer
            updatePrimaryBouncer(
                isShowing = true,
                isAnimatingAway = false,
                fpsDetectionRunning = true,
                isUnlockingWithFpAllowed = true
            )
            runCurrent()

            val inOrder = inOrder(kosmos.windowManager)

            // Verify indicator shown
            inOrder.verify(kosmos.windowManager).addView(any(), any())

            // Set progress bar visible
            kosmos.sideFpsProgressBarViewModel.setVisible(true)

            runCurrent()

            // Verify indicator hidden
            inOrder.verify(kosmos.windowManager).removeView(any())

            // Set progress bar invisible
            kosmos.sideFpsProgressBarViewModel.setVisible(false)

            runCurrent()

            // Verify indicator shown
            inOrder.verify(kosmos.windowManager).addView(any(), any())
        }
    }

    private fun updatePrimaryBouncer(
        isShowing: Boolean,
        isAnimatingAway: Boolean,
        fpsDetectionRunning: Boolean,
        isUnlockingWithFpAllowed: Boolean,
    ) {
        kosmos.keyguardBouncerRepository.setPrimaryShow(isShowing)
        kosmos.keyguardBouncerRepository.setPrimaryStartingToHide(false)
        val primaryStartDisappearAnimation = if (isAnimatingAway) Runnable {} else null
        kosmos.keyguardBouncerRepository.setPrimaryStartDisappearAnimation(
            primaryStartDisappearAnimation
        )

        whenever(kosmos.keyguardUpdateMonitor.isFingerprintDetectionRunning)
            .thenReturn(fpsDetectionRunning)
        whenever(kosmos.keyguardUpdateMonitor.isUnlockingWithFingerprintAllowed)
            .thenReturn(isUnlockingWithFpAllowed)
        mContext.orCreateTestableResources.addOverride(
            R.bool.config_show_sidefps_hint_on_bouncer,
            true
        )
    }

    private suspend fun TestScope.setupTestConfiguration(
        deviceConfig: DeviceConfig,
        rotation: DisplayRotation = DisplayRotation.ROTATION_0,
        isInRearDisplayMode: Boolean,
    ) {
        this@SideFpsOverlayViewBinderTest.deviceConfig = deviceConfig

        when (deviceConfig) {
            DeviceConfig.X_ALIGNED -> {
                displayWidth = 3000
                displayHeight = 1500
                boundsWidth = 200
                boundsHeight = 100
                sensorLocation = SensorLocationInternal("", 2500, 0, boundsWidth / 2)
            }
            DeviceConfig.Y_ALIGNED -> {
                displayWidth = 2500
                displayHeight = 2000
                boundsWidth = 100
                boundsHeight = 200
                sensorLocation = SensorLocationInternal("", displayWidth, 300, boundsHeight / 2)
            }
        }

        whenever(kosmos.windowManager.maximumWindowMetrics)
            .thenReturn(
                WindowMetrics(
                    Rect(0, 0, displayWidth, displayHeight),
                    mock(WindowInsets::class.java),
                )
            )

        contextDisplayInfo.uniqueId = DISPLAY_ID

        kosmos.fingerprintPropertyRepository.setProperties(
            sensorId = 1,
            strength = SensorStrength.STRONG,
            sensorType = FingerprintSensorType.POWER_BUTTON,
            sensorLocations = mapOf(DISPLAY_ID to sensorLocation)
        )

        kosmos.displayStateRepository.setIsInRearDisplayMode(isInRearDisplayMode)
        kosmos.displayStateRepository.setCurrentRotation(rotation)
        kosmos.displayRepository.emitDisplayChangeEvent(0)
        kosmos.sideFpsOverlayViewBinder.start()
        runCurrent()
    }

    companion object {
        private const val DISPLAY_ID = "displayId"
    }
}
