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

import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_NOTIFICATION_SHADE_BLUR
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.flags.BrokenWithSceneContainer
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.keyguard.data.repository.biometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.ui.transitions.blurConfig
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class PrimaryBouncerToLockscreenTransitionViewModelTest(flags: FlagsParameterization) :
    SysuiTestCase() {
    val kosmos = testKosmos()
    val testScope = kosmos.testScope

    val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    val fingerprintPropertyRepository = kosmos.fingerprintPropertyRepository
    val biometricSettingsRepository = kosmos.biometricSettingsRepository

    private lateinit var underTest: PrimaryBouncerToLockscreenTransitionViewModel

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf().andSceneContainer()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Before
    fun setup() {
        underTest = kosmos.primaryBouncerToLockscreenTransitionViewModel
    }

    @Test
    @BrokenWithSceneContainer(392346450)
    fun lockscreenAlphaStartsFromViewStateAccessorAlpha() =
        testScope.runTest {
            val viewState = ViewStateAccessor(alpha = { 0.5f })
            val alpha by collectLastValue(underTest.lockscreenAlpha(viewState))

            keyguardTransitionRepository.sendTransitionStep(step(0f, TransitionState.STARTED))

            keyguardTransitionRepository.sendTransitionStep(step(0f))
            assertThat(alpha).isEqualTo(0.5f)

            keyguardTransitionRepository.sendTransitionStep(step(0.5f))
            assertThat(alpha).isIn(Range.open(0.5f, 1f))

            keyguardTransitionRepository.sendTransitionStep(step(1f))
            assertThat(alpha).isEqualTo(1f)
        }

    @Test
    @BrokenWithSceneContainer(392346450)
    fun deviceEntryParentViewAlpha() =
        testScope.runTest {
            val deviceEntryParentViewAlpha by collectLastValue(underTest.deviceEntryParentViewAlpha)

            // immediately 1f
            keyguardTransitionRepository.sendTransitionStep(step(0f, TransitionState.STARTED))
            assertThat(deviceEntryParentViewAlpha).isEqualTo(1f)

            keyguardTransitionRepository.sendTransitionStep(step(0.4f))
            assertThat(deviceEntryParentViewAlpha).isEqualTo(1f)

            keyguardTransitionRepository.sendTransitionStep(step(.85f))
            assertThat(deviceEntryParentViewAlpha).isEqualTo(1f)

            keyguardTransitionRepository.sendTransitionStep(step(1f))
            assertThat(deviceEntryParentViewAlpha).isEqualTo(1f)
        }

    @Test
    @BrokenWithSceneContainer(392346450)
    fun deviceEntryBackgroundViewAlpha_udfpsEnrolled_show() =
        testScope.runTest {
            fingerprintPropertyRepository.supportsUdfps()
            val bgViewAlpha by collectLastValue(underTest.deviceEntryBackgroundViewAlpha)
            runCurrent()

            // immediately 1f
            keyguardTransitionRepository.sendTransitionStep(step(0f, TransitionState.STARTED))
            assertThat(bgViewAlpha).isEqualTo(1f)

            keyguardTransitionRepository.sendTransitionStep(step(0.1f))
            assertThat(bgViewAlpha).isEqualTo(1f)

            keyguardTransitionRepository.sendTransitionStep(step(.3f))
            assertThat(bgViewAlpha).isEqualTo(1f)

            keyguardTransitionRepository.sendTransitionStep(step(.5f))
            assertThat(bgViewAlpha).isEqualTo(1f)

            keyguardTransitionRepository.sendTransitionStep(step(1f, TransitionState.FINISHED))
            assertThat(bgViewAlpha).isEqualTo(1f)
        }

    @Test
    @BrokenWithSceneContainer(388068805)
    fun blurRadiusGoesFromMaxToMinWhenShadeIsNotExpanded() =
        testScope.runTest {
            val values by collectValues(underTest.windowBlurRadius)
            kosmos.keyguardWindowBlurTestUtil.shadeExpanded(false)

            kosmos.keyguardWindowBlurTestUtil.assertTransitionToBlurRadius(
                transitionProgress = listOf(0.0f, 0.2f, 0.3f, 0.65f, 0.7f, 1.0f),
                startValue = kosmos.blurConfig.maxBlurRadiusPx,
                endValue = kosmos.blurConfig.minBlurRadiusPx,
                actualValuesProvider = { values },
                transitionFactory = ::step,
            )
        }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_SHADE_BLUR)
    @BrokenWithSceneContainer(388068805)
    fun blurRadiusRemainsAtMaxWhenShadeIsExpanded() =
        testScope.runTest {
            val values by collectValues(underTest.windowBlurRadius)
            kosmos.keyguardWindowBlurTestUtil.shadeExpanded(true)

            kosmos.keyguardWindowBlurTestUtil.assertTransitionToBlurRadius(
                transitionProgress = listOf(0.0f, 0.2f, 0.3f, 0.65f, 0.7f, 1.0f),
                startValue = kosmos.blurConfig.maxBlurRadiusPx,
                endValue = kosmos.blurConfig.maxBlurRadiusPx,
                actualValuesProvider = { values },
                transitionFactory = ::step,
                checkInterpolatedValues = false,
            )
        }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING,
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.PRIMARY_BOUNCER,
            to = KeyguardState.LOCKSCREEN,
            value = value,
            transitionState = state,
            ownerName = "PrimaryBouncerToLockscreenTransitionViewModelTest",
        )
    }
}
