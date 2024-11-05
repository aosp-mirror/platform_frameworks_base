/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_BP
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_KEYGUARD
import android.hardware.biometrics.BiometricRequestConstants.RequestReason
import android.hardware.fingerprint.IUdfpsOverlayControllerCallback
import android.testing.TestableLooper.RunWithLooper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.app.viewcapture.ViewCapture
import com.android.app.viewcapture.ViewCaptureAwareWindowManager
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.biometrics.ui.viewmodel.DefaultUdfpsTouchOverlayViewModel
import com.android.systemui.biometrics.ui.viewmodel.DeviceEntryUdfpsTouchOverlayViewModel
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.testKosmos
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

private const val REQUEST_ID = 2L

// Dimensions for the current display resolution.
private const val DISPLAY_WIDTH = 1080
private const val DISPLAY_HEIGHT = 1920
private const val SENSOR_WIDTH = 30
private const val SENSOR_HEIGHT = 60

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper(setAsMainLooper = true)
class UdfpsControllerOverlayTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    @JvmField @Rule var rule = MockitoJUnit.rule()

    @Mock private lateinit var inflater: LayoutInflater
    @Mock private lateinit var windowManager: WindowManager
    @Mock private lateinit var lazyViewCapture: kotlin.Lazy<ViewCapture>
    @Mock private lateinit var accessibilityManager: AccessibilityManager
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var statusBarKeyguardViewManager: StatusBarKeyguardViewManager
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var dialogManager: SystemUIDialogManager
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var configurationController: ConfigurationController
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock
    private lateinit var unlockedScreenOffAnimationController: UnlockedScreenOffAnimationController
    @Mock private lateinit var udfpsDisplayMode: UdfpsDisplayModeProvider
    @Mock private lateinit var controllerCallback: IUdfpsOverlayControllerCallback
    @Mock private lateinit var udfpsController: UdfpsController
    @Mock private lateinit var mActivityTransitionAnimator: ActivityTransitionAnimator
    @Mock private lateinit var primaryBouncerInteractor: PrimaryBouncerInteractor
    @Mock private lateinit var alternateBouncerInteractor: AlternateBouncerInteractor
    @Mock private lateinit var mSelectedUserInteractor: SelectedUserInteractor
    @Mock
    private lateinit var deviceEntryUdfpsTouchOverlayViewModel:
        DeviceEntryUdfpsTouchOverlayViewModel
    @Mock private lateinit var defaultUdfpsTouchOverlayViewModel: DefaultUdfpsTouchOverlayViewModel
    @Mock
    private lateinit var udfpsKeyguardAccessibilityDelegate: UdfpsKeyguardAccessibilityDelegate
    private lateinit var keyguardTransitionRepository: FakeKeyguardTransitionRepository
    private lateinit var keyguardTransitionInteractor: KeyguardTransitionInteractor
    @Mock private lateinit var shadeInteractor: ShadeInteractor
    @Captor private lateinit var layoutParamsCaptor: ArgumentCaptor<WindowManager.LayoutParams>
    @Mock private lateinit var udfpsOverlayInteractor: UdfpsOverlayInteractor
    private lateinit var powerRepository: FakePowerRepository
    private lateinit var powerInteractor: PowerInteractor
    private lateinit var testScope: TestScope

    private val onTouch = { _: View, _: MotionEvent -> true }
    private var overlayParams: UdfpsOverlayParams = UdfpsOverlayParams()
    private lateinit var controllerOverlay: UdfpsControllerOverlay

    @Before
    fun setup() {
        testScope = kosmos.testScope
        powerRepository = kosmos.fakePowerRepository
        powerInteractor = kosmos.powerInteractor
        keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
        keyguardTransitionInteractor = kosmos.keyguardTransitionInteractor
    }

    private suspend fun withReasonSuspend(
        @RequestReason reason: Int,
        isDebuggable: Boolean = false,
        block: suspend () -> Unit,
    ) {
        withReason(reason, isDebuggable)
        block()
    }

    private fun withReason(
        @RequestReason reason: Int,
        isDebuggable: Boolean = false,
        block: () -> Unit = {},
    ) {
        controllerOverlay =
            UdfpsControllerOverlay(
                context,
                inflater,
                ViewCaptureAwareWindowManager(
                    windowManager,
                    lazyViewCapture,
                    isViewCaptureEnabled = false,
                ),
                accessibilityManager,
                statusBarStateController,
                statusBarKeyguardViewManager,
                keyguardUpdateMonitor,
                dialogManager,
                dumpManager,
                configurationController,
                keyguardStateController,
                unlockedScreenOffAnimationController,
                udfpsDisplayMode,
                REQUEST_ID,
                reason,
                controllerCallback,
                onTouch,
                mActivityTransitionAnimator,
                primaryBouncerInteractor,
                alternateBouncerInteractor,
                isDebuggable,
                udfpsKeyguardAccessibilityDelegate,
                keyguardTransitionInteractor,
                mSelectedUserInteractor,
                { deviceEntryUdfpsTouchOverlayViewModel },
                { defaultUdfpsTouchOverlayViewModel },
                shadeInteractor,
                udfpsOverlayInteractor,
                powerInteractor,
                testScope,
            )
        block()
    }

    @Test
    fun showUdfpsOverlay_whileGoingToSleep() =
        testScope.runTest {
            withReasonSuspend(REASON_AUTH_KEYGUARD) {
                keyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.OFF,
                    to = KeyguardState.GONE,
                    testScope = this,
                )
                powerRepository.updateWakefulness(
                    rawState = WakefulnessState.STARTING_TO_SLEEP,
                    lastWakeReason = WakeSleepReason.POWER_BUTTON,
                    lastSleepReason = WakeSleepReason.OTHER,
                )
                runCurrent()

                // WHEN a request comes to show the view
                controllerOverlay.show(udfpsController, overlayParams)
                runCurrent()

                // THEN the view does not get added immediately
                verify(windowManager, never()).addView(any(), any())

                // we hide to end the job that listens for the finishedGoingToSleep signal
                controllerOverlay.hide()
            }
        }

    @Test
    fun showUdfpsOverlay_whileAsleep() =
        testScope.runTest {
            withReasonSuspend(REASON_AUTH_KEYGUARD) {
                keyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.OFF,
                    to = KeyguardState.GONE,
                    testScope = this,
                )
                powerRepository.updateWakefulness(
                    rawState = WakefulnessState.ASLEEP,
                    lastWakeReason = WakeSleepReason.POWER_BUTTON,
                    lastSleepReason = WakeSleepReason.OTHER,
                )
                runCurrent()

                // WHEN a request comes to show the view
                controllerOverlay.show(udfpsController, overlayParams)
                runCurrent()

                // THEN view isn't added yet
                verify(windowManager, never()).addView(any(), any())

                // we hide to end the job that listens for the finishedGoingToSleep signal
                controllerOverlay.hide()
            }
        }

    @Test
    fun neverRemoveViewThatHasNotBeenAdded() =
        testScope.runTest {
            withReasonSuspend(REASON_AUTH_KEYGUARD) {
                controllerOverlay.show(udfpsController, overlayParams)
                val view = controllerOverlay.getTouchOverlay()
                view?.let {
                    // parent is null, signalling that the view was never added
                    whenever(view.parent).thenReturn(null)
                }
                verify(windowManager, never()).removeView(eq(view))
            }
        }

    @Test
    fun canNotHide() = withReason(REASON_AUTH_BP) { assertThat(controllerOverlay.hide()).isFalse() }

    @Test
    fun cancels() =
        withReason(REASON_AUTH_BP) {
            controllerOverlay.cancel()
            verify(controllerCallback).onUserCanceled()
        }

    @Test
    fun matchesRequestIds() =
        withReason(REASON_AUTH_BP) {
            assertThat(controllerOverlay.matchesRequestId(REQUEST_ID)).isTrue()
            assertThat(controllerOverlay.matchesRequestId(REQUEST_ID + 1)).isFalse()
        }

    @Test
    fun addViewPending_layoutIsNotUpdated() =
        testScope.runTest {
            withReasonSuspend(REASON_AUTH_KEYGUARD) {
                // GIVEN going to sleep
                keyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.OFF,
                    to = KeyguardState.GONE,
                    testScope = this,
                )
                powerRepository.updateWakefulness(
                    rawState = WakefulnessState.STARTING_TO_SLEEP,
                    lastWakeReason = WakeSleepReason.POWER_BUTTON,
                    lastSleepReason = WakeSleepReason.OTHER,
                )
                runCurrent()

                // WHEN a request comes to show the view
                controllerOverlay.show(udfpsController, overlayParams)
                runCurrent()

                // THEN the view does not get added immediately
                verify(windowManager, never()).addView(any(), any())

                // WHEN updateOverlayParams gets called when the view is pending to be added
                controllerOverlay.updateOverlayParams(overlayParams)

                // THEN the view layout is never updated
                verify(windowManager, never()).updateViewLayout(any(), any())

                // CLEANUP we hide to end the job that listens for the finishedGoingToSleep signal
                controllerOverlay.hide()
            }
        }
}
