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

import android.hardware.biometrics.BiometricOverlayConstants.REASON_AUTH_BP
import android.hardware.biometrics.BiometricOverlayConstants.REASON_AUTH_KEYGUARD
import android.hardware.biometrics.BiometricOverlayConstants.REASON_AUTH_OTHER
import android.hardware.biometrics.BiometricOverlayConstants.REASON_AUTH_SETTINGS
import android.hardware.biometrics.BiometricOverlayConstants.REASON_ENROLL_ENROLLING
import android.hardware.biometrics.BiometricOverlayConstants.REASON_ENROLL_FIND_SENSOR
import android.hardware.biometrics.BiometricOverlayConstants.ShowReason
import android.hardware.biometrics.SensorLocationInternal
import android.hardware.fingerprint.FingerprintManager
import android.hardware.fingerprint.IUdfpsOverlayControllerCallback
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.LockscreenShadeTransitionController
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.time.SystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.Mockito.`when` as whenever

private const val REQUEST_ID = 2L

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
class UdfpsControllerOverlayTest : SysuiTestCase() {

    @JvmField @Rule
    var rule = MockitoJUnit.rule()

    @Mock private lateinit var fingerprintManager: FingerprintManager
    @Mock private lateinit var inflater: LayoutInflater
    @Mock private lateinit var windowManager: WindowManager
    @Mock private lateinit var accessibilityManager: AccessibilityManager
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var panelExpansionStateManager: PanelExpansionStateManager
    @Mock private lateinit var statusBarKeyguardViewManager: StatusBarKeyguardViewManager
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var dialogManager: SystemUIDialogManager
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var transitionController: LockscreenShadeTransitionController
    @Mock private lateinit var configurationController: ConfigurationController
    @Mock private lateinit var systemClock: SystemClock
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock
    private lateinit var unlockedScreenOffAnimationController: UnlockedScreenOffAnimationController
    @Mock private lateinit var hbmProvider: UdfpsHbmProvider
    @Mock private lateinit var controllerCallback: IUdfpsOverlayControllerCallback
    @Mock private lateinit var udfpsController: UdfpsController
    @Mock private lateinit var udfpsView: UdfpsView
    @Mock private lateinit var udfpsEnrollView: UdfpsEnrollView
    @Mock private lateinit var activityLaunchAnimator: ActivityLaunchAnimator

    private val sensorProps = SensorLocationInternal("", 10, 100, 20)
        .asFingerprintSensorProperties()
    private val onTouch = { _: View, _: MotionEvent, _: Boolean -> true }
    private lateinit var controllerOverlay: UdfpsControllerOverlay

    @Before
    fun setup() {
        context.orCreateTestableResources.addOverride(R.integer.config_udfpsEnrollProgressBar, 20)
        whenever(inflater.inflate(R.layout.udfps_view, null, false))
            .thenReturn(udfpsView)
        whenever(inflater.inflate(R.layout.udfps_enroll_view, null))
            .thenReturn(udfpsEnrollView)
        whenever(inflater.inflate(R.layout.udfps_bp_view, null))
            .thenReturn(mock(UdfpsBpView::class.java))
        whenever(inflater.inflate(R.layout.udfps_keyguard_view, null))
            .thenReturn(mock(UdfpsKeyguardView::class.java))
        whenever(inflater.inflate(R.layout.udfps_fpm_other_view, null))
            .thenReturn(mock(UdfpsFpmOtherView::class.java))
        whenever(udfpsEnrollView.context).thenReturn(context)
    }

    private fun withReason(@ShowReason reason: Int, block: () -> Unit) {
        controllerOverlay = UdfpsControllerOverlay(
            context, fingerprintManager, inflater, windowManager, accessibilityManager,
            statusBarStateController, panelExpansionStateManager, statusBarKeyguardViewManager,
            keyguardUpdateMonitor, dialogManager, dumpManager, transitionController,
            configurationController, systemClock, keyguardStateController,
            unlockedScreenOffAnimationController, sensorProps, hbmProvider, REQUEST_ID, reason,
            controllerCallback, onTouch, activityLaunchAnimator)
        block()
    }

    @Test
    fun showUdfpsOverlay_bp() = withReason(REASON_AUTH_BP) { showUdfpsOverlay() }

    @Test
    fun showUdfpsOverlay_keyguard() = withReason(REASON_AUTH_KEYGUARD) { showUdfpsOverlay() }

    @Test
    fun showUdfpsOverlay_settings() = withReason(REASON_AUTH_SETTINGS) { showUdfpsOverlay() }

    @Test
    fun showUdfpsOverlay_locate() = withReason(REASON_ENROLL_FIND_SENSOR) {
        showUdfpsOverlay(isEnrollUseCase = true)
    }

    @Test
    fun showUdfpsOverlay_enroll() = withReason(REASON_ENROLL_ENROLLING) {
        showUdfpsOverlay(isEnrollUseCase = true)
    }

    @Test
    fun showUdfpsOverlay_other() = withReason(REASON_AUTH_OTHER) { showUdfpsOverlay() }

    private fun showUdfpsOverlay(isEnrollUseCase: Boolean = false) {
        val didShow = controllerOverlay.show(udfpsController)

        verify(windowManager).addView(eq(controllerOverlay.overlayView), any())
        verify(udfpsView).setHbmProvider(eq(hbmProvider))
        verify(udfpsView).sensorProperties = eq(sensorProps)
        verify(udfpsView).animationViewController = any()
        verify(udfpsView).addView(any())

        assertThat(didShow).isTrue()
        assertThat(controllerOverlay.isShowing).isTrue()
        assertThat(controllerOverlay.isHiding).isFalse()
        assertThat(controllerOverlay.overlayView).isNotNull()
        if (isEnrollUseCase) {
            verify(udfpsEnrollView).updateSensorLocation(eq(sensorProps))
            assertThat(controllerOverlay.enrollHelper).isNotNull()
        } else {
            assertThat(controllerOverlay.enrollHelper).isNull()
        }
    }

    @Test
    fun hideUdfpsOverlay_bp() = withReason(REASON_AUTH_BP) { hideUdfpsOverlay() }

    @Test
    fun hideUdfpsOverlay_keyguard() = withReason(REASON_AUTH_KEYGUARD) { hideUdfpsOverlay() }

    @Test
    fun hideUdfpsOverlay_settings() = withReason(REASON_AUTH_SETTINGS) { hideUdfpsOverlay() }

    @Test
    fun hideUdfpsOverlay_locate() = withReason(REASON_ENROLL_FIND_SENSOR) { hideUdfpsOverlay() }

    @Test
    fun hideUdfpsOverlay_enroll() = withReason(REASON_ENROLL_ENROLLING) { hideUdfpsOverlay() }

    @Test
    fun hideUdfpsOverlay_other() = withReason(REASON_AUTH_OTHER) { hideUdfpsOverlay() }

    private fun hideUdfpsOverlay() {
        val didShow = controllerOverlay.show(udfpsController)
        val view = controllerOverlay.overlayView
        val didHide = controllerOverlay.hide()

        verify(windowManager).removeView(eq(view))

        assertThat(didShow).isTrue()
        assertThat(didHide).isTrue()
        assertThat(controllerOverlay.overlayView).isNull()
        assertThat(controllerOverlay.animationViewController).isNull()
        assertThat(controllerOverlay.isShowing).isFalse()
        assertThat(controllerOverlay.isHiding).isTrue()
    }

    @Test
    fun canNotHide() = withReason(REASON_AUTH_BP) {
        assertThat(controllerOverlay.hide()).isFalse()
    }

    @Test
    fun canNotReshow() = withReason(REASON_AUTH_BP) {
        assertThat(controllerOverlay.show(udfpsController)).isTrue()
        assertThat(controllerOverlay.show(udfpsController)).isFalse()
    }

    @Test
    fun forwardEnrollProgressEvents() = withReason(REASON_ENROLL_ENROLLING) {
        controllerOverlay.show(udfpsController)

        with(EnrollListener(controllerOverlay)) {
            controllerOverlay.onEnrollmentProgress(/* remaining */20)
            controllerOverlay.onAcquiredGood()
            assertThat(progress).isTrue()
            assertThat(help).isFalse()
            assertThat(acquired).isFalse()
        }
    }

    @Test
    fun forwardEnrollHelpEvents() = withReason(REASON_ENROLL_ENROLLING) {
        controllerOverlay.show(udfpsController)

        with(EnrollListener(controllerOverlay)) {
            controllerOverlay.onEnrollmentHelp()
            assertThat(progress).isFalse()
            assertThat(help).isTrue()
            assertThat(acquired).isFalse()
        }
    }

    @Test
    fun forwardEnrollAcquiredEvents() = withReason(REASON_ENROLL_ENROLLING) {
        controllerOverlay.show(udfpsController)

        with(EnrollListener(controllerOverlay)) {
            controllerOverlay.onEnrollmentProgress(/* remaining */ 1)
            controllerOverlay.onAcquiredGood()
            assertThat(progress).isTrue()
            assertThat(help).isFalse()
            assertThat(acquired).isTrue()
        }
    }

    @Test
    fun cancels() = withReason(REASON_AUTH_BP) {
        controllerOverlay.cancel()
        verify(controllerCallback).onUserCanceled()
    }

    @Test
    fun stopIlluminatingOnHide() = withReason(REASON_AUTH_BP) {
        whenever(udfpsView.isIlluminationRequested).thenReturn(true)

        controllerOverlay.show(udfpsController)
        controllerOverlay.hide()
        verify(udfpsView).stopIllumination()
    }

    @Test
    fun matchesRequestIds() = withReason(REASON_AUTH_BP) {
        assertThat(controllerOverlay.matchesRequestId(REQUEST_ID)).isTrue()
        assertThat(controllerOverlay.matchesRequestId(REQUEST_ID + 1)).isFalse()
    }
}

private class EnrollListener(
    overlay: UdfpsControllerOverlay,
    var progress: Boolean = false,
    var help: Boolean = false,
    var acquired: Boolean = false
) : UdfpsEnrollHelper.Listener {

    init {
        overlay.enrollHelper!!.setListener(this)
    }

    override fun onEnrollmentProgress(remaining: Int, totalSteps: Int) {
        progress = true
    }

    override fun onEnrollmentHelp(remaining: Int, totalSteps: Int) {
        help = true
    }

    override fun onLastStepAcquired() {
        acquired = true
    }
}
