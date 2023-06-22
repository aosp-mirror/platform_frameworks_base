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

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.filters.SmallTest
import com.android.internal.util.LatencyTracker
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.classifier.FalsingCollectorFake
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.statusbar.policy.DevicePostureController
import com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_HALF_OPENED
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
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
  private lateinit var mKeyguardMessageAreaControllerFactory: KeyguardMessageAreaController.Factory

  @Mock
  private lateinit var mKeyguardMessageAreaController:
      KeyguardMessageAreaController<BouncerKeyguardMessageArea>

    @Mock private lateinit var mPostureController: DevicePostureController

  private lateinit var mKeyguardPatternViewController: KeyguardPatternViewController
  private lateinit var fakeFeatureFlags: FakeFeatureFlags

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(mKeyguardMessageAreaControllerFactory.create(any()))
            .thenReturn(mKeyguardMessageAreaController)
        fakeFeatureFlags = FakeFeatureFlags()
        fakeFeatureFlags.set(Flags.REVAMPED_BOUNCER_MESSAGES, false)
        mKeyguardPatternView = View.inflate(mContext, R.layout.keyguard_pattern_view, null)
                as KeyguardPatternView


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
                fakeFeatureFlags
            )
        mKeyguardPatternView.onAttachedToWindow()
    }

    @Test
    fun tabletopPostureIsDetectedFromStart() {
        overrideResource(R.dimen.half_opened_bouncer_height_ratio, 0.5f)
        whenever(mPostureController.devicePosture).thenReturn(DEVICE_POSTURE_HALF_OPENED)

        mKeyguardPatternViewController.onViewAttached()

        assertThat(getPatternTopGuideline()).isEqualTo(getExpectedTopGuideline())
    }

    private fun getPatternTopGuideline(): Float {
        val cs = ConstraintSet()
        val container =
            mKeyguardPatternView.findViewById(R.id.pattern_container) as ConstraintLayout
        cs.clone(container)
        return cs.getConstraint(R.id.pattern_top_guideline).layout.guidePercent
    }

    private fun getExpectedTopGuideline(): Float {
        return mContext.resources.getFloat(R.dimen.half_opened_bouncer_height_ratio)
    }

  @Test
  fun withFeatureFlagOn_oldMessage_isHidden() {
    fakeFeatureFlags.set(Flags.REVAMPED_BOUNCER_MESSAGES, true)

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
  fun startAppearAnimation() {
    mKeyguardPatternViewController.startAppearAnimation()
    verify(mKeyguardMessageAreaController)
        .setMessage(context.resources.getString(R.string.keyguard_enter_your_pattern), false)
  }

  @Test
  fun startAppearAnimation_withExistingMessage() {
    `when`(mKeyguardMessageAreaController.message).thenReturn("Unlock to continue.")
    mKeyguardPatternViewController.startAppearAnimation()
    verify(mKeyguardMessageAreaController, never()).setMessage(anyString(), anyBoolean())
  }

  @Test
  fun resume() {
    mKeyguardPatternViewController.onResume(KeyguardSecurityView.VIEW_REVEALED)
    verify(mLockPatternUtils).getLockoutAttemptDeadline(anyInt())
  }
}
