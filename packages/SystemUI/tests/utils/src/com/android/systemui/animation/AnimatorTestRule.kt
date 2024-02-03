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

import java.util.function.Consumer
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A rule that wraps both [androidx.core.animation.AnimatorTestRule] and
 * [android.animation.AnimatorTestRule] such that the clocks of the two animation handlers can be
 * advanced together.
 */
class AnimatorTestRule : TestRule {
    private val androidxRule = androidx.core.animation.AnimatorTestRule()
    private val platformRule = android.animation.AnimatorTestRule()
    private val advanceAndroidXTimeBy =
        Consumer<Long> { timeDelta -> androidxRule.advanceTimeBy(timeDelta) }

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
}
