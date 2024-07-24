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
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This test class validates that two tests' animators are isolated from each other when using the
 * same animator test rule. This is a test to prevent future instances of b/275602127.
 */
@RunWith(AndroidTestingRunner::class)
@SmallTest
@RunWithLooper
class AnimatorTestRuleIsolationTest : SysuiTestCase() {

    @get:Rule val animatorTestRule = AnimatorTestRule(this)

    @Test
    fun testA() {
        // GIVEN global state is reset at the start of the test
        didTouchA = false
        didTouchB = false
        // WHEN starting 2 animations of different durations, and setting didTouchA at the end
        ObjectAnimator.ofFloat(0f, 1f).apply {
            duration = 100
            doOnEnd { didTouchA = true }
            start()
        }
        ObjectAnimator.ofFloat(0f, 1f).apply {
            duration = 150
            doOnEnd { didTouchA = true }
            start()
        }
        // WHEN when you advance time so that only one of the animations has ended
        animatorTestRule.advanceTimeBy(100)
        // VERIFY we did indeed end the current animation
        assertThat(didTouchA).isTrue()
        // VERIFY advancing the animator did NOT cause testB's animator to end
        assertThat(didTouchB).isFalse()
    }

    @Test
    fun testB() {
        // GIVEN global state is reset at the start of the test
        didTouchA = false
        didTouchB = false
        // WHEN starting 2 animations of different durations, and setting didTouchB at the end
        ObjectAnimator.ofFloat(0f, 1f).apply {
            duration = 100
            doOnEnd { didTouchB = true }
            start()
        }
        ObjectAnimator.ofFloat(0f, 1f).apply {
            duration = 150
            doOnEnd { didTouchB = true }
            start()
        }
        animatorTestRule.advanceTimeBy(100)
        // VERIFY advancing the animator did NOT cause testA's animator to end
        assertThat(didTouchA).isFalse()
        // VERIFY we did indeed end the current animation
        assertThat(didTouchB).isTrue()
    }

    companion object {
        var didTouchA = false
        var didTouchB = false
    }
}
