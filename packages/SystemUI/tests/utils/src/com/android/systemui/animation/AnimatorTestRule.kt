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

package com.android.systemui.animation

import android.animation.Animator
import java.util.function.Consumer
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A rule that wraps both [androidx.core.animation.AnimatorTestRule] and
 * [android.animation.AnimatorTestRule] such that the clocks of the two animation handlers can be
 * advanced together.
 *
 * @param test the instance of the test used to look up the TestableLooper.  If a TestableLooper is
 * found, the time can only be advanced on that thread; otherwise the time must be advanced on the
 * main thread.
 */
class AnimatorTestRule(test: Any?) : TestRule {
    // Create the androidx rule, which initializes start time to SystemClock.uptimeMillis(),
    // then copy that time to the platform rule so that the two clocks are in sync.
    private val androidxRule = androidx.core.animation.AnimatorTestRule()
    private val platformRule = android.animation.AnimatorTestRule(test, androidxRule.startTime)
    private val advanceAndroidXTimeBy =
        Consumer<Long> { timeDelta -> androidxRule.advanceTimeBy(timeDelta) }

    /** Access the mStartTime field; bypassing the restriction of being on a looper thread. */
    private val androidx.core.animation.AnimatorTestRule.startTime: Long
        get() =
            javaClass.getDeclaredField("mStartTime").let { field ->
                field.isAccessible = true
                field.getLong(this)
            }

    /**
     * Chain is for simplicity not to force a particular order; order should not matter, because
     * each rule affects a different AnimationHandler classes, and no callbacks to code under test
     * should be triggered by these rules
     */
    private val ruleChain = RuleChain.emptyRuleChain().around(androidxRule).around(platformRule)

    override fun apply(base: Statement, description: Description): Statement =
        ruleChain.apply(base, description)

    /**
     * Advances the animation clock by the given amount of delta in milliseconds. This call will
     * produce an animation frame to all the ongoing animations.
     *
     * @param timeDelta the amount of milliseconds to advance
     */
    fun advanceTimeBy(timeDelta: Long) {
        // NOTE: To avoid errors with order, we have to ensure that we advance the time within both
        //  rules before either rule does its frame output. Failing to do this could cause the
        //  animation from one to start later than the other.
        platformRule.advanceTimeBy(timeDelta, advanceAndroidXTimeBy)
    }

    /**
     * This is similar to [advanceTimeBy] but it expects to reach the end of an animation. This call
     * may produce 2 frames for the last animation frame and end animation callback.
     *
     * @param durationMs the duration that is greater than or equal to the animation duration.
     */
    fun advanceAnimationDuration(durationMs: Long) {
        advanceTimeBy(durationMs)
        if (Animator.isPostNotifyEndListenerEnabled()) {
            // If the post-end-callback is enabled, the AnimatorListener#onAnimationEnd will be
            // called on the next frame of last animation frame. So trigger additional doFrame to
            // ensure the end callback method is called (by android.animation.AnimatorTestRule).
            advanceTimeBy(0)
        }
    }

    /**
     * Returns the current time in milliseconds tracked by the AnimationHandlers. Note that this is
     * a different time than the time tracked by {@link SystemClock}.
     */
    val currentTime: Long
        get() = androidxRule.currentTime
}
