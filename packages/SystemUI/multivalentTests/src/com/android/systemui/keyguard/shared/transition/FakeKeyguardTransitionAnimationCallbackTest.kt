/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.keyguard.shared.transition

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class FakeKeyguardTransitionAnimationCallbackTest : SysuiTestCase() {

    private val kosmos = testKosmos().useUnconfinedTestDispatcher()

    val underTest = FakeKeyguardTransitionAnimationCallback()

    @Test
    fun onAnimationStarted() =
        kosmos.runTest {
            assertThat(underTest.activeAnimations).isEmpty()

            underTest.onAnimationStarted(KeyguardState.LOCKSCREEN, KeyguardState.AOD)
            assertThat(underTest.activeAnimations).hasSize(1)

            underTest.onAnimationStarted(KeyguardState.AOD, KeyguardState.ALTERNATE_BOUNCER)
            assertThat(underTest.activeAnimations).hasSize(2)
        }

    @Test
    fun onAnimationEnded() =
        kosmos.runTest {
            underTest.onAnimationStarted(KeyguardState.LOCKSCREEN, KeyguardState.AOD)
            underTest.onAnimationStarted(KeyguardState.AOD, KeyguardState.ALTERNATE_BOUNCER)
            assertThat(underTest.activeAnimations).hasSize(2)

            underTest.onAnimationEnded(KeyguardState.AOD, KeyguardState.ALTERNATE_BOUNCER)
            assertThat(underTest.activeAnimations).hasSize(1)

            underTest.onAnimationEnded(KeyguardState.LOCKSCREEN, KeyguardState.AOD)
            assertThat(underTest.activeAnimations).isEmpty()
        }

    @Test
    fun onAnimationCanceled() =
        kosmos.runTest {
            underTest.onAnimationStarted(KeyguardState.LOCKSCREEN, KeyguardState.AOD)
            underTest.onAnimationStarted(KeyguardState.AOD, KeyguardState.ALTERNATE_BOUNCER)
            assertThat(underTest.activeAnimations).hasSize(2)

            underTest.onAnimationCanceled(KeyguardState.AOD, KeyguardState.ALTERNATE_BOUNCER)
            assertThat(underTest.activeAnimations).hasSize(1)

            underTest.onAnimationCanceled(KeyguardState.LOCKSCREEN, KeyguardState.AOD)
            assertThat(underTest.activeAnimations).isEmpty()
        }

    @Test(expected = IllegalStateException::class)
    fun onAnimationEnded_throwsWhenNoSuchAnimation() =
        kosmos.runTest {
            underTest.onAnimationStarted(KeyguardState.LOCKSCREEN, KeyguardState.AOD)
            underTest.onAnimationStarted(KeyguardState.AOD, KeyguardState.ALTERNATE_BOUNCER)
            assertThat(underTest.activeAnimations).hasSize(2)

            underTest.onAnimationEnded(KeyguardState.AOD, KeyguardState.LOCKSCREEN)
        }

    @Test(expected = IllegalStateException::class)
    fun onAnimationCanceled_throwsWhenNoSuchAnimation() =
        kosmos.runTest {
            underTest.onAnimationStarted(KeyguardState.LOCKSCREEN, KeyguardState.AOD)
            underTest.onAnimationStarted(KeyguardState.AOD, KeyguardState.ALTERNATE_BOUNCER)
            assertThat(underTest.activeAnimations).hasSize(2)

            underTest.onAnimationCanceled(KeyguardState.AOD, KeyguardState.LOCKSCREEN)
        }
}
