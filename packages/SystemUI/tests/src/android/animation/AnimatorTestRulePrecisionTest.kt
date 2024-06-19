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
package android.animation

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.core.animation.doOnEnd
import androidx.test.filters.SmallTest
import com.android.app.animation.Interpolators
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
@RunWithLooper
class AnimatorTestRulePrecisionTest : SysuiTestCase() {

    @get:Rule val animatorTestRule = AnimatorTestRule(this)

    var value1: Float = -1f
    var value2: Float = -1f

    private inline fun animateThis(
        propertyName: String,
        duration: Long,
        startDelay: Long = 0,
        crossinline onEndAction: (animator: Animator) -> Unit,
    ) {
        ObjectAnimator.ofFloat(this, propertyName, 0f, 1f).also {
            it.interpolator = Interpolators.LINEAR
            it.duration = duration
            it.startDelay = startDelay
            it.doOnEnd(onEndAction)
            it.start()
        }
    }

    @Test
    fun testSingleAnimator() {
        var ended = false
        animateThis("value1", duration = 100) { ended = true }

        assertThat(value1).isEqualTo(0f)
        assertThat(ended).isFalse()
        assertThat(AnimationHandler.getAnimationCount()).isEqualTo(1)

        animatorTestRule.advanceTimeBy(50)
        assertThat(value1).isEqualTo(0.5f)
        assertThat(ended).isFalse()
        assertThat(AnimationHandler.getAnimationCount()).isEqualTo(1)

        animatorTestRule.advanceTimeBy(49)
        assertThat(value1).isEqualTo(0.99f)
        assertThat(ended).isFalse()
        assertThat(AnimationHandler.getAnimationCount()).isEqualTo(1)

        animatorTestRule.advanceTimeBy(1)
        assertThat(value1).isEqualTo(1f)
        assertThat(ended).isTrue()
        assertThat(AnimationHandler.getAnimationCount()).isEqualTo(0)
    }

    @Test
    fun testDelayedAnimator() {
        var ended = false
        animateThis("value1", duration = 100, startDelay = 50) { ended = true }

        assertThat(value1).isEqualTo(-1f)
        assertThat(ended).isFalse()
        assertThat(AnimationHandler.getAnimationCount()).isEqualTo(1)

        animatorTestRule.advanceTimeBy(49)
        assertThat(value1).isEqualTo(-1f)
        assertThat(ended).isFalse()
        assertThat(AnimationHandler.getAnimationCount()).isEqualTo(1)

        animatorTestRule.advanceTimeBy(1)
        assertThat(value1).isEqualTo(0f)
        assertThat(ended).isFalse()
        assertThat(AnimationHandler.getAnimationCount()).isEqualTo(1)

        animatorTestRule.advanceTimeBy(99)
        assertThat(value1).isEqualTo(0.99f)
        assertThat(ended).isFalse()
        assertThat(AnimationHandler.getAnimationCount()).isEqualTo(1)

        animatorTestRule.advanceTimeBy(1)
        assertThat(value1).isEqualTo(1f)
        assertThat(ended).isTrue()
        assertThat(AnimationHandler.getAnimationCount()).isEqualTo(0)
    }

    @Test
    fun testTwoAnimators() {
        var ended1 = false
        var ended2 = false
        animateThis("value1", duration = 100) { ended1 = true }
        animateThis("value2", duration = 200) { ended2 = true }
        assertThat(value1).isEqualTo(0f)
        assertThat(value2).isEqualTo(0f)
        assertThat(ended1).isFalse()
        assertThat(ended2).isFalse()
        assertThat(AnimationHandler.getAnimationCount()).isEqualTo(2)

        animatorTestRule.advanceTimeBy(99)
        assertThat(value1).isEqualTo(0.99f)
        assertThat(value2).isEqualTo(0.495f)
        assertThat(ended1).isFalse()
        assertThat(ended2).isFalse()
        assertThat(AnimationHandler.getAnimationCount()).isEqualTo(2)

        animatorTestRule.advanceTimeBy(1)
        assertThat(value1).isEqualTo(1f)
        assertThat(value2).isEqualTo(0.5f)
        assertThat(ended1).isTrue()
        assertThat(ended2).isFalse()
        assertThat(AnimationHandler.getAnimationCount()).isEqualTo(1)

        animatorTestRule.advanceTimeBy(99)
        assertThat(value1).isEqualTo(1f)
        assertThat(value2).isEqualTo(0.995f)
        assertThat(ended1).isTrue()
        assertThat(ended2).isFalse()
        assertThat(AnimationHandler.getAnimationCount()).isEqualTo(1)

        animatorTestRule.advanceTimeBy(1)
        assertThat(value1).isEqualTo(1f)
        assertThat(value2).isEqualTo(1f)
        assertThat(ended1).isTrue()
        assertThat(ended2).isTrue()
        assertThat(AnimationHandler.getAnimationCount()).isEqualTo(0)
    }

    @Test
    fun testChainedAnimators() {
        var ended1 = false
        var ended2 = false
        animateThis("value1", duration = 100) {
            ended1 = true
            animateThis("value2", duration = 100) { ended2 = true }
        }

        assertThat(value1).isEqualTo(0f)
        assertThat(value2).isEqualTo(-1f)
        assertThat(ended1).isFalse()
        assertThat(ended2).isFalse()
        assertThat(AnimationHandler.getAnimationCount()).isEqualTo(1)

        animatorTestRule.advanceTimeBy(99)
        assertThat(value1).isEqualTo(0.99f)
        assertThat(value2).isEqualTo(-1f)
        assertThat(ended1).isFalse()
        assertThat(ended2).isFalse()
        assertThat(AnimationHandler.getAnimationCount()).isEqualTo(1)

        animatorTestRule.advanceTimeBy(1)
        assertThat(value1).isEqualTo(1f)
        assertThat(value2).isEqualTo(0f)
        assertThat(ended1).isTrue()
        assertThat(ended2).isFalse()
        assertThat(AnimationHandler.getAnimationCount()).isEqualTo(1)

        animatorTestRule.advanceTimeBy(99)
        assertThat(value1).isEqualTo(1f)
        assertThat(value2).isEqualTo(0.99f)
        assertThat(ended1).isTrue()
        assertThat(ended2).isFalse()
        assertThat(AnimationHandler.getAnimationCount()).isEqualTo(1)

        animatorTestRule.advanceTimeBy(1)
        assertThat(value1).isEqualTo(1f)
        assertThat(value2).isEqualTo(1f)
        assertThat(ended1).isTrue()
        assertThat(ended2).isTrue()
        assertThat(AnimationHandler.getAnimationCount()).isEqualTo(0)
    }
}
