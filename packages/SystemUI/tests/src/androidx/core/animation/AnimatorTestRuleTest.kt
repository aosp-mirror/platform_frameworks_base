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
package androidx.core.animation

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.doOnEnd
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
@RunWithLooper(setAsMainLooper = true)
class AnimatorTestRuleTest : SysuiTestCase() {

    @get:Rule val animatorTestRule = AnimatorTestRule2()

    @Test
    fun testA() {
        didTouchA = false
        didTouchB = false
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
        animatorTestRule.advanceTimeBy(100)
        assertThat(didTouchA).isTrue()
        assertThat(didTouchB).isFalse()
    }

    @Test
    fun testB() {
        didTouchA = false
        didTouchB = false
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
        assertThat(didTouchA).isFalse()
        assertThat(didTouchB).isTrue()
    }

    companion object {
        var didTouchA = false
        var didTouchB = false
    }
}
