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

import android.os.HandlerThread
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.view.LaunchableFrameLayout
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class GhostedViewTransitionAnimatorControllerTest : SysuiTestCase() {
    companion object {
        private const val LAUNCH_CUJ = 0
        private const val RETURN_CUJ = 1
    }

    private val interactionJankMonitor = FakeInteractionJankMonitor()

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

    @Test
    fun cujsAreLoggedCorrectly() {
        val parent = FrameLayout(mContext)

        val launchView = LaunchableFrameLayout(mContext)
        parent.addView((launchView))
        val launchController =
            GhostedViewTransitionAnimatorController(
                    launchView,
                launchCujType = LAUNCH_CUJ,
                returnCujType = RETURN_CUJ,
                interactionJankMonitor = interactionJankMonitor
            )
        launchController.onTransitionAnimationStart(isExpandingFullyAbove = true)
        assertThat(interactionJankMonitor.ongoing).containsExactly(LAUNCH_CUJ)
        launchController.onTransitionAnimationEnd(isExpandingFullyAbove = true)
        assertThat(interactionJankMonitor.ongoing).isEmpty()
        assertThat(interactionJankMonitor.finished).containsExactly(LAUNCH_CUJ)

        val returnView = LaunchableFrameLayout(mContext)
        parent.addView((returnView))
        val returnController =
            object : GhostedViewTransitionAnimatorController(
                returnView,
                launchCujType = LAUNCH_CUJ,
                returnCujType = RETURN_CUJ,
                interactionJankMonitor = interactionJankMonitor
            ) {
                override val isLaunching = false
            }
        returnController.onTransitionAnimationStart(isExpandingFullyAbove = true)
        assertThat(interactionJankMonitor.ongoing).containsExactly(RETURN_CUJ)
        returnController.onTransitionAnimationEnd(isExpandingFullyAbove = true)
        assertThat(interactionJankMonitor.ongoing).isEmpty()
        assertThat(interactionJankMonitor.finished).containsExactly(LAUNCH_CUJ, RETURN_CUJ)
    }

    /**
     * A fake implementation of [InteractionJankMonitor] which stores ongoing and finished CUJs and
     * allows inspection.
     */
    private class FakeInteractionJankMonitor : InteractionJankMonitor(
        HandlerThread("testThread")
    ) {
        val ongoing: MutableSet<Int> = mutableSetOf()
        val finished: MutableSet<Int> = mutableSetOf()

        override fun begin(v: View?, cujType: Int): Boolean {
            ongoing.add(cujType)
            return true
        }

        override fun end(cujType: Int): Boolean {
            ongoing.remove(cujType)
            finished.add(cujType)
            return true
        }
    }
}
