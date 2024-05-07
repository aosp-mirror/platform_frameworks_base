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

import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class AodToLockscreenTransitionViewModelTest(flags: FlagsParameterization?) : SysuiTestCase() {
    val kosmos = testKosmos()
    val testScope = kosmos.testScope
    val repository = kosmos.fakeKeyguardTransitionRepository
    val shadeTestUtil by lazy { kosmos.shadeTestUtil }
    val fingerprintPropertyRepository = kosmos.fingerprintPropertyRepository
    lateinit var underTest: AodToLockscreenTransitionViewModel

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf().andSceneContainer()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags!!)
    }

    @Before
    fun setup() {
        underTest = kosmos.aodToLockscreenTransitionViewModel
    }

    @Test
    fun deviceEntryParentViewShows() =
        testScope.runTest {
            val deviceEntryParentViewAlpha by collectValues(underTest.deviceEntryParentViewAlpha)
            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            repository.sendTransitionStep(step(0.1f))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(0.5f))
            repository.sendTransitionStep(step(0.6f))
            repository.sendTransitionStep(step(1f))
            deviceEntryParentViewAlpha.forEach { assertThat(it).isEqualTo(1f) }
        }

    @Test
    fun notificationAlpha_whenShadeIsExpanded_equalsOne() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.notificationAlpha)

            shadeTestUtil.setQsExpansion(0.5f)
            runCurrent()

            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            assertThat(alpha).isEqualTo(1f)
            repository.sendTransitionStep(step(0.5f))
            assertThat(alpha).isEqualTo(1f)
            repository.sendTransitionStep(step(1f))
            assertThat(alpha).isEqualTo(1f)
        }

    @Test
    fun notificationAlpha_whenShadeIsNotExpanded_usesTransitionValue() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.notificationAlpha)

            shadeTestUtil.setQsExpansion(0f)
            runCurrent()

            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            assertThat(alpha).isEqualTo(0f)
            repository.sendTransitionStep(step(0.5f))
            assertThat(alpha).isEqualTo(0.5f)
            repository.sendTransitionStep(step(1f))
            assertThat(alpha).isEqualTo(1f)
        }

    @Test
    fun lockscreenAlphaStartsFromViewStateAccessorAlpha() =
        testScope.runTest {
            val viewState = ViewStateAccessor(alpha = { 0.5f })
            val alpha by collectLastValue(underTest.lockscreenAlpha(viewState))

            repository.sendTransitionStep(step(0f, TransitionState.STARTED))

            repository.sendTransitionStep(step(0f))
            assertThat(alpha).isEqualTo(0.5f)

            repository.sendTransitionStep(step(0.5f))
            assertThat(alpha).isEqualTo(0.75f)

            repository.sendTransitionStep(step(1f))
            assertThat(alpha).isEqualTo(1f)
        }

    @Test
    fun deviceEntryBackgroundView_udfps_alphaFadeIn() =
        testScope.runTest {
            fingerprintPropertyRepository.supportsUdfps()
            val deviceEntryBackgroundViewAlpha by
                collectLastValue(underTest.deviceEntryBackgroundViewAlpha)
            runCurrent()

            // fade in
            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            assertThat(deviceEntryBackgroundViewAlpha).isEqualTo(0f)

            repository.sendTransitionStep(step(0.1f))
            assertThat(deviceEntryBackgroundViewAlpha).isEqualTo(.2f)

            repository.sendTransitionStep(step(0.3f))
            assertThat(deviceEntryBackgroundViewAlpha).isEqualTo(.6f)

            repository.sendTransitionStep(step(0.6f))
            assertThat(deviceEntryBackgroundViewAlpha).isEqualTo(1f)

            repository.sendTransitionStep(step(1f))
            assertThat(deviceEntryBackgroundViewAlpha).isEqualTo(1f)
        }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.AOD,
            to = KeyguardState.LOCKSCREEN,
            value = value,
            transitionState = state,
            ownerName = "AodToLockscreenTransitionViewModelTest"
        )
    }
}
