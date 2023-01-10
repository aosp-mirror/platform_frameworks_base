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
import android.os.Handler
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import androidx.test.rule.ActivityTestRule
import androidx.test.runner.intercepting.SingleActivityFactory
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class BrightnessDialogTest : SysuiTestCase() {

    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var brightnessSliderControllerFactory: BrightnessSliderController.Factory
    @Mock private lateinit var mainExecutor: Executor
    @Mock private lateinit var backgroundHandler: Handler
    @Mock private lateinit var brightnessSliderController: BrightnessSliderController

    @Rule
    @JvmField
    var activityRule =
        ActivityTestRule(
            object : SingleActivityFactory<TestDialog>(TestDialog::class.java) {
                override fun create(intent: Intent?): TestDialog {
                    return TestDialog(
                        userTracker,
                        brightnessSliderControllerFactory,
                        mainExecutor,
                        backgroundHandler
                    )
                }
            },
            false,
            false
        )

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        `when`(brightnessSliderControllerFactory.create(any(), any()))
            .thenReturn(brightnessSliderController)
        `when`(brightnessSliderController.rootView).thenReturn(View(context))

        activityRule.launchActivity(null)
    }

    @After
    fun tearDown() {
        activityRule.finishActivity()
    }

    @Test
    fun testGestureExclusion() {
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

    class TestDialog(
        userTracker: UserTracker,
        brightnessSliderControllerFactory: BrightnessSliderController.Factory,
        mainExecutor: Executor,
        backgroundHandler: Handler
    ) :
        BrightnessDialog(
            userTracker,
            brightnessSliderControllerFactory,
            mainExecutor,
            backgroundHandler
        )
}
