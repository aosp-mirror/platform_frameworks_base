/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard

import android.testing.TestableLooper
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.util.LatencyTracker
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.Flags as AConfigFlags
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.classifier.FalsingCollectorFake
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.haptics.msdl.bouncerHapticPlayer
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.DevicePostureController
import com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_HALF_OPENED
import com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_OPENED
import com.android.systemui.testKosmos
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class KeyguardPatternViewControllerTest : SysuiTestCase() {
    private lateinit var mKeyguardPatternView: KeyguardPatternView

    @Mock private lateinit var mKeyguardUpdateMonitor: KeyguardUpdateMonitor

    @Mock private lateinit var mSecurityMode: KeyguardSecurityModel.SecurityMode

    @Mock private lateinit var mLockPatternUtils: LockPatternUtils

    @Mock private lateinit var mKeyguardSecurityCallback: KeyguardSecurityCallback

    @Mock private lateinit var mLatencyTracker: LatencyTracker
    private var mFalsingCollector: FalsingCollector = FalsingCollectorFake()

    @Mock private lateinit var mEmergencyButtonController: EmergencyButtonController

    @Mock
    private lateinit var mKeyguardMessageAreaControllerFactory:
        KeyguardMessageAreaController.Factory

    @Mock private lateinit var mSelectedUserInteractor: SelectedUserInteractor

    @Mock
    private lateinit var mKeyguardMessageAreaController:
        KeyguardMessageAreaController<BouncerKeyguardMessageArea>

    @Mock private lateinit var mPostureController: DevicePostureController

    private lateinit var mKeyguardPatternViewController: KeyguardPatternViewController
    private lateinit var fakeFeatureFlags: FakeFeatureFlags

    @Captor lateinit var postureCallbackCaptor: ArgumentCaptor<DevicePostureController.Callback>

    private val kosmos = testKosmos()
    private val bouncerHapticHelper = kosmos.bouncerHapticPlayer

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(mKeyguardMessageAreaControllerFactory.create(any()))
            .thenReturn(mKeyguardMessageAreaController)
        fakeFeatureFlags = FakeFeatureFlags()
        fakeFeatureFlags.set(Flags.LOCKSCREEN_ENABLE_LANDSCAPE, false)
        mKeyguardPatternView =
            View.inflate(mContext, R.layout.keyguard_pattern_view, null) as KeyguardPatternView
        mKeyguardPatternView.setIsLockScreenLandscapeEnabled(false)
        mKeyguardPatternViewController =
            KeyguardPatternViewController(
                mKeyguardPatternView,
                mKeyguardUpdateMonitor,
                mSecurityMode,
                mLockPatternUtils,
                mKeyguardSecurityCallback,
                mLatencyTracker,
                mFalsingCollector,
                mEmergencyButtonController,
                mKeyguardMessageAreaControllerFactory,
                mPostureController,
                fakeFeatureFlags,
                mSelectedUserInteractor,
                bouncerHapticHelper,
            )
        mKeyguardPatternView.onAttachedToWindow()
    }

    @Test
    fun onViewAttached_deviceHalfFolded_propagatedToPatternView() {
        overrideResource(R.dimen.half_opened_bouncer_height_ratio, 0.5f)
        whenever(mPostureController.devicePosture).thenReturn(DEVICE_POSTURE_HALF_OPENED)

        mKeyguardPatternViewController.onViewAttached()

        assertThat(getPatternTopGuideline()).isEqualTo(getHalfOpenedBouncerHeightRatio())
    }

    @Test
    fun onDevicePostureChanged_deviceOpened_propagatedToPatternView() {
        overrideResource(R.dimen.half_opened_bouncer_height_ratio, 0.5f)
        whenever(mPostureController.devicePosture).thenReturn(DEVICE_POSTURE_HALF_OPENED)

        mKeyguardPatternViewController.onViewAttached()

        // Verify view begins in posture state DEVICE_POSTURE_HALF_OPENED
        assertThat(getPatternTopGuideline()).isEqualTo(getHalfOpenedBouncerHeightRatio())

        // Simulate posture change to state DEVICE_POSTURE_OPENED with callback
        verify(mPostureController).addCallback(postureCallbackCaptor.capture())
        val postureCallback: DevicePostureController.Callback = postureCallbackCaptor.value
        postureCallback.onPostureChanged(DEVICE_POSTURE_OPENED)

        // Simulate posture change to same state with callback
        assertThat(getPatternTopGuideline()).isNotEqualTo(getHalfOpenedBouncerHeightRatio())

        postureCallback.onPostureChanged(DEVICE_POSTURE_OPENED)

        // Verify view is still in posture state DEVICE_POSTURE_OPENED
        assertThat(getPatternTopGuideline()).isNotEqualTo(getHalfOpenedBouncerHeightRatio())
    }

    private fun getPatternTopGuideline(): Float {
        val cs = ConstraintSet()
        val container =
            mKeyguardPatternView.requireViewById(R.id.pattern_container) as ConstraintLayout
        cs.clone(container)
        return cs.getConstraint(R.id.pattern_top_guideline).layout.guidePercent
    }

    private fun getHalfOpenedBouncerHeightRatio(): Float {
        return mContext.resources.getFloat(R.dimen.half_opened_bouncer_height_ratio)
    }

    @Test
    fun withFeatureFlagOn_oldMessage_isHidden() {
        mSetFlagsRule.enableFlags(AConfigFlags.FLAG_REVAMPED_BOUNCER_MESSAGES)

        mKeyguardPatternViewController.onViewAttached()

        verify<KeyguardMessageAreaController<*>>(mKeyguardMessageAreaController).disable()
    }

    @Test
    fun onPause_resetsText() {
        mKeyguardPatternViewController.init()
        mKeyguardPatternViewController.onPause()
        verify(mKeyguardMessageAreaController).setMessage(R.string.keyguard_enter_your_pattern)
    }

    @Test
    fun testOnViewAttached() {
        reset(mKeyguardMessageAreaController)
        reset(mLockPatternUtils)
        mKeyguardPatternViewController.onViewAttached()
        verify(mKeyguardMessageAreaController)
            .setMessage(context.resources.getString(R.string.keyguard_enter_your_pattern), false)
        verify(mLockPatternUtils).getLockoutAttemptDeadline(anyInt())
    }

    @Test
    fun testOnViewAttached_withExistingMessage() {
        reset(mKeyguardMessageAreaController)
        `when`(mKeyguardMessageAreaController.message).thenReturn("Unlock to continue.")
        mKeyguardPatternViewController.onViewAttached()
        verify(mKeyguardMessageAreaController, never()).setMessage(anyString(), anyBoolean())
    }
}
