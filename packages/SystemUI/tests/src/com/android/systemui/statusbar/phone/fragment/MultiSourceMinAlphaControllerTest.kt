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

package com.android.systemui.statusbar.phone.fragment

import android.platform.test.annotations.DisableFlags
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_STATUS_BAR_SIMPLE_FRAGMENT
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.AnimatorTestRule
import junit.framework.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_SOURCE_1 = 1
private const val TEST_SOURCE_2 = 2
private const val TEST_ANIMATION_DURATION = 100L
private const val INITIAL_ALPHA = 1f

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
@DisableFlags(FLAG_STATUS_BAR_SIMPLE_FRAGMENT)
class MultiSourceMinAlphaControllerTest : SysuiTestCase() {

    private val view = View(context)
    private val multiSourceMinAlphaController =
        MultiSourceMinAlphaController(view, initialAlpha = INITIAL_ALPHA)

    @get:Rule val animatorTestRule = AnimatorTestRule(this)

    @Before
    fun setup() {
        multiSourceMinAlphaController.reset()
    }

    @Test
    fun testSetAlpha() {
        multiSourceMinAlphaController.setAlpha(alpha = 0.5f, sourceId = TEST_SOURCE_1)
        assertEquals(0.5f, view.alpha)
    }

    @Test
    fun testAnimateToAlpha() {
        multiSourceMinAlphaController.animateToAlpha(
            alpha = 0.5f,
            sourceId = TEST_SOURCE_1,
            duration = TEST_ANIMATION_DURATION,
        )
        animatorTestRule.advanceTimeBy(TEST_ANIMATION_DURATION)
        assertEquals(0.5f, view.alpha)
    }

    @Test
    fun testReset() {
        multiSourceMinAlphaController.animateToAlpha(
            alpha = 0.5f,
            sourceId = TEST_SOURCE_1,
            duration = TEST_ANIMATION_DURATION,
        )
        multiSourceMinAlphaController.setAlpha(alpha = 0.7f, sourceId = TEST_SOURCE_2)
        multiSourceMinAlphaController.reset()
        // advance time to ensure that animators are cancelled when the controller is reset
        animatorTestRule.advanceTimeBy(TEST_ANIMATION_DURATION)
        assertEquals(INITIAL_ALPHA, view.alpha)
    }

    @Test
    fun testMinOfTwoSourcesIsApplied() {
        multiSourceMinAlphaController.setAlpha(alpha = 0f, sourceId = TEST_SOURCE_1)
        multiSourceMinAlphaController.setAlpha(alpha = 0.5f, sourceId = TEST_SOURCE_2)
        assertEquals(0f, view.alpha)
        multiSourceMinAlphaController.setAlpha(alpha = 1f, sourceId = TEST_SOURCE_1)
        assertEquals(0.5f, view.alpha)
    }

    @Test
    fun testSetAlphaForSameSourceCancelsAnimator() {
        multiSourceMinAlphaController.animateToAlpha(
            alpha = 0f,
            sourceId = TEST_SOURCE_1,
            duration = TEST_ANIMATION_DURATION,
        )
        animatorTestRule.advanceTimeBy(TEST_ANIMATION_DURATION / 2)
        multiSourceMinAlphaController.setAlpha(alpha = 1f, sourceId = TEST_SOURCE_1)
        animatorTestRule.advanceTimeBy(TEST_ANIMATION_DURATION / 2)
        // verify that animation was cancelled and the setAlpha call overrides the alpha value of
        // the animation
        assertEquals(1f, view.alpha)
    }
}
