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
 */

package com.android.systemui.settings.brightness

import android.content.Intent
import android.graphics.Rect
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManagerPolicyConstants.EXTRA_FROM_BRIGHTNESS_KEY
import androidx.test.filters.FlakyTest
import androidx.test.filters.SmallTest
import androidx.test.rule.ActivityTestRule
import com.android.systemui.SysuiTestCase
import com.android.systemui.activity.SingleActivityFactory
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.eq
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class BrightnessDialogTest : SysuiTestCase() {

    @Mock private lateinit var brightnessSliderControllerFactory: BrightnessSliderController.Factory
    @Mock private lateinit var brightnessSliderController: BrightnessSliderController
    @Mock private lateinit var brightnessControllerFactory: BrightnessController.Factory
    @Mock private lateinit var brightnessController: BrightnessController
    @Mock private lateinit var accessibilityMgr: AccessibilityManagerWrapper
    @Mock private lateinit var shadeInteractor: ShadeInteractor

    private val clock = FakeSystemClock()
    private val mainExecutor = FakeExecutor(clock)

    @Rule
    @JvmField
    var activityRule =
        ActivityTestRule(
            /* activityFactory= */ SingleActivityFactory {
                TestDialog(
                    brightnessSliderControllerFactory,
                    brightnessControllerFactory,
                    mainExecutor,
                    accessibilityMgr,
                    shadeInteractor
                )
            },
            /* initialTouchMode= */ false,
            /* launchActivity= */ false,
        )

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        `when`(brightnessSliderControllerFactory.create(any(), any()))
            .thenReturn(brightnessSliderController)
        `when`(brightnessSliderController.rootView).thenReturn(View(context))
        `when`(brightnessControllerFactory.create(any())).thenReturn(brightnessController)
        whenever(shadeInteractor.isQsExpanded).thenReturn(MutableStateFlow(false))
    }

    @After
    fun tearDown() {
        activityRule.finishActivity()
    }

    @Test
    fun testGestureExclusion() {
        activityRule.launchActivity(Intent(Intent.ACTION_SHOW_BRIGHTNESS_DIALOG))
        val frame = activityRule.activity.requireViewById<View>(R.id.brightness_mirror_container)

        val lp = frame.layoutParams as ViewGroup.MarginLayoutParams
        val horizontalMargin =
            activityRule.activity.resources.getDimensionPixelSize(
                R.dimen.notification_side_paddings
            )
        assertThat(lp.leftMargin).isEqualTo(horizontalMargin)
        assertThat(lp.rightMargin).isEqualTo(horizontalMargin)

        assertThat(frame.systemGestureExclusionRects.size).isEqualTo(1)
        val exclusion = frame.systemGestureExclusionRects[0]
        assertThat(exclusion)
            .isEqualTo(Rect(-horizontalMargin, 0, frame.width + horizontalMargin, frame.height))
    }

    @Test
    fun testTimeout() {
        `when`(
                accessibilityMgr.getRecommendedTimeoutMillis(
                    eq(BrightnessDialog.DIALOG_TIMEOUT_MILLIS),
                    anyInt()
                )
            )
            .thenReturn(BrightnessDialog.DIALOG_TIMEOUT_MILLIS)
        val intent = Intent(Intent.ACTION_SHOW_BRIGHTNESS_DIALOG)
        intent.putExtra(EXTRA_FROM_BRIGHTNESS_KEY, true)
        activityRule.launchActivity(intent)

        assertThat(activityRule.activity.isFinishing()).isFalse()

        clock.advanceTime(BrightnessDialog.DIALOG_TIMEOUT_MILLIS.toLong())
        assertThat(activityRule.activity.isFinishing()).isTrue()
    }

    @Test
    fun testRestartTimeout() {
        `when`(
                accessibilityMgr.getRecommendedTimeoutMillis(
                    eq(BrightnessDialog.DIALOG_TIMEOUT_MILLIS),
                    anyInt()
                )
            )
            .thenReturn(BrightnessDialog.DIALOG_TIMEOUT_MILLIS)
        val intent = Intent(Intent.ACTION_SHOW_BRIGHTNESS_DIALOG)
        intent.putExtra(EXTRA_FROM_BRIGHTNESS_KEY, true)
        activityRule.launchActivity(intent)

        assertThat(activityRule.activity.isFinishing()).isFalse()

        clock.advanceTime(BrightnessDialog.DIALOG_TIMEOUT_MILLIS.toLong() / 2)
        // Restart the timeout
        activityRule.activity.onResume()

        clock.advanceTime(BrightnessDialog.DIALOG_TIMEOUT_MILLIS.toLong() / 2)
        // The dialog should not have disappeared yet
        assertThat(activityRule.activity.isFinishing()).isFalse()

        clock.advanceTime(BrightnessDialog.DIALOG_TIMEOUT_MILLIS.toLong() / 2)
        assertThat(activityRule.activity.isFinishing()).isTrue()
    }

    @Test
    fun testNoTimeoutIfNotStartedByBrightnessKey() {
        `when`(
                accessibilityMgr.getRecommendedTimeoutMillis(
                    eq(BrightnessDialog.DIALOG_TIMEOUT_MILLIS),
                    anyInt()
                )
            )
            .thenReturn(BrightnessDialog.DIALOG_TIMEOUT_MILLIS)
        activityRule.launchActivity(Intent(Intent.ACTION_SHOW_BRIGHTNESS_DIALOG))

        assertThat(activityRule.activity.isFinishing()).isFalse()

        clock.advanceTime(BrightnessDialog.DIALOG_TIMEOUT_MILLIS.toLong())
        assertThat(activityRule.activity.isFinishing()).isFalse()
    }

    @OptIn(FlowPreview::class)
    @FlakyTest(bugId = 326186573)
    @Test
    fun testFinishOnQSExpanded() = runTest {
        val isQSExpanded = MutableStateFlow(false)
        `when`(shadeInteractor.isQsExpanded).thenReturn(isQSExpanded)
        activityRule.launchActivity(Intent(Intent.ACTION_SHOW_BRIGHTNESS_DIALOG))

        assertThat(activityRule.activity.isFinishing()).isFalse()

        isQSExpanded.value = true
        // Observe the activity's state until is it finishing or the timeout is reached, whatever
        // comes first. This fixes the flakiness seen when using advanceUntilIdle().
        activityRule.activity.finishing.timeout(100.milliseconds).takeWhile { !it }.collect {}
        assertThat(activityRule.activity.isFinishing()).isTrue()
    }

    class TestDialog(
        brightnessSliderControllerFactory: BrightnessSliderController.Factory,
        brightnessControllerFactory: BrightnessController.Factory,
        mainExecutor: DelayableExecutor,
        accessibilityMgr: AccessibilityManagerWrapper,
        shadeInteractor: ShadeInteractor
    ) :
        BrightnessDialog(
            brightnessSliderControllerFactory,
            brightnessControllerFactory,
            mainExecutor,
            accessibilityMgr,
            shadeInteractor
        ) {
        var finishing = MutableStateFlow(false)

        override fun isFinishing(): Boolean {
            return finishing.value
        }

        override fun requestFinish() {
            finishing.value = true
        }
    }
}
