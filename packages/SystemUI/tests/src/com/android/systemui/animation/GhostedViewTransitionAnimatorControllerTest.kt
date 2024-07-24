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

package com.android.systemui.animation

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.view.LaunchableFrameLayout
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class GhostedViewTransitionAnimatorControllerTest : SysuiTestCase() {
    @Test
    fun animatingOrphanViewDoesNotCrash() {
        val state = TransitionAnimator.State(top = 0, bottom = 0, left = 0, right = 0)

        val controller = GhostedViewTransitionAnimatorController(LaunchableFrameLayout(mContext))
        controller.onIntentStarted(willAnimate = true)
        controller.onTransitionAnimationStart(isExpandingFullyAbove = true)
        controller.onTransitionAnimationProgress(state, progress = 0f, linearProgress = 0f)
        controller.onTransitionAnimationEnd(isExpandingFullyAbove = true)
    }

    @Test
    fun creatingControllerFromNormalViewThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            GhostedViewTransitionAnimatorController(FrameLayout(mContext))
        }
    }
}
