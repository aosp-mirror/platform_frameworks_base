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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.testKosmos
import com.google.common.collect.Range
import com.google.common.truth.Truth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class LockscreenToPrimaryBouncerTransitionViewModelTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            fakeFeatureFlagsClassic.apply { set(Flags.FULL_SCREEN_USER_SWITCHER, false) }
        }
    private val testScope = kosmos.testScope
    private val repository = kosmos.fakeKeyguardTransitionRepository
    private val shadeRepository = kosmos.shadeRepository
    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private val underTest = kosmos.lockscreenToPrimaryBouncerTransitionViewModel

    @Test
    fun deviceEntryParentViewAlpha_shadeExpanded() =
        testScope.runTest {
            val actual by collectLastValue(underTest.deviceEntryParentViewAlpha)
            shadeExpanded(true)
            runCurrent()

            // immediately 0f
            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            runCurrent()
            Truth.assertThat(actual).isEqualTo(0f)

            repository.sendTransitionStep(step(.2f))
            runCurrent()
            Truth.assertThat(actual).isEqualTo(0f)

            repository.sendTransitionStep(step(0.8f))
            runCurrent()
            Truth.assertThat(actual).isEqualTo(0f)

            repository.sendTransitionStep(step(1f, TransitionState.FINISHED))
            runCurrent()
            Truth.assertThat(actual).isEqualTo(0f)
        }

    @Test
    fun deviceEntryParentViewAlpha_shadeNotExpanded() =
        testScope.runTest {
            val actual by collectLastValue(underTest.deviceEntryParentViewAlpha)
            shadeExpanded(false)
            runCurrent()

            // fade out
            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            runCurrent()
            Truth.assertThat(actual).isEqualTo(1f)

            repository.sendTransitionStep(step(.1f))
            runCurrent()
            Truth.assertThat(actual).isIn(Range.open(.1f, .9f))

            // alpha is 1f before the full transition starts ending
            repository.sendTransitionStep(step(0.8f))
            runCurrent()
            Truth.assertThat(actual).isEqualTo(0f)

            repository.sendTransitionStep(step(1f, TransitionState.FINISHED))
            runCurrent()
            Truth.assertThat(actual).isEqualTo(0f)
        }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING,
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.LOCKSCREEN,
            to = KeyguardState.PRIMARY_BOUNCER,
            value = value,
            transitionState = state,
            ownerName = "LockscreenToPrimaryBouncerTransitionViewModelTest"
        )
    }

    private fun shadeExpanded(expanded: Boolean) {
        if (expanded) {
            shadeRepository.setQsExpansion(1f)
        } else {
            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            shadeRepository.setQsExpansion(0f)
            shadeRepository.setLockscreenShadeExpansion(0f)
        }
    }
}
