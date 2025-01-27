/*
 * Copyright (C) 2024 The Android Open Source Project
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_BOUNCER_UI_REVAMP
import com.android.systemui.Flags.FLAG_NOTIFICATION_SHADE_BLUR
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.flags.BrokenWithSceneContainer
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.ui.transitions.blurConfig
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class AlternateBouncerToPrimaryBouncerTransitionViewModelTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            fakeFeatureFlagsClassic.apply { set(Flags.FULL_SCREEN_USER_SWITCHER, false) }
        }
    private val testScope = kosmos.testScope
    private val keyguardTransitionRepository by lazy { kosmos.fakeKeyguardTransitionRepository }
    private val underTest by lazy { kosmos.alternateBouncerToPrimaryBouncerTransitionViewModel }

    private val shadeTestUtil by lazy { kosmos.shadeTestUtil }

    @Test
    fun deviceEntryParentViewDisappear() =
        testScope.runTest {
            val values by collectValues(underTest.deviceEntryParentViewAlpha)

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    step(0f, TransitionState.STARTED),
                    step(0f),
                    step(0.1f),
                    step(0.2f),
                    step(0.3f),
                    step(1f),
                ),
                testScope,
            )

            values.forEach { assertThat(it).isEqualTo(0f) }
        }

    @Test
    @EnableFlags(FLAG_BOUNCER_UI_REVAMP)
    @BrokenWithSceneContainer(388068805)
    fun notifications_areFullyVisible_whenShadeIsOpen() =
        testScope.runTest {
            val values by collectValues(underTest.notificationAlpha)
            kosmos.keyguardWindowBlurTestUtil.shadeExpanded(true)

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    step(0f, TransitionState.STARTED),
                    step(0.1f),
                    step(0.2f),
                    step(0.3f),
                    step(1f),
                ),
                testScope,
            )

            values.forEach { assertThat(it).isEqualTo(1f) }
        }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_SHADE_BLUR)
    fun blurRadiusGoesToMaximumWhenShadeIsExpanded() =
        testScope.runTest {
            val values by collectValues(underTest.windowBlurRadius)
            kosmos.keyguardWindowBlurTestUtil.shadeExpanded(true)

            kosmos.keyguardWindowBlurTestUtil.assertTransitionToBlurRadius(
                transitionProgress = listOf(0f, 0f, 0.1f, 0.2f, 0.3f, 1f),
                startValue = kosmos.blurConfig.maxBlurRadiusPx,
                endValue = kosmos.blurConfig.maxBlurRadiusPx,
                checkInterpolatedValues = false,
                transitionFactory = ::step,
                actualValuesProvider = { values },
            )
        }

    @Test
    @EnableFlags(FLAG_BOUNCER_UI_REVAMP)
    @BrokenWithSceneContainer(388068805)
    fun notificationBlur_isNonZero_whenShadeIsExpanded() =
        testScope.runTest {
            val values by collectValues(underTest.notificationBlurRadius)

            kosmos.keyguardWindowBlurTestUtil.shadeExpanded(true)

            kosmos.keyguardWindowBlurTestUtil.assertTransitionToBlurRadius(
                transitionProgress = listOf(0f, 0f, 0.1f, 0.2f, 0.3f, 1f),
                startValue = kosmos.blurConfig.maxBlurRadiusPx,
                endValue = kosmos.blurConfig.maxBlurRadiusPx,
                transitionFactory = ::step,
                actualValuesProvider = { values },
                checkInterpolatedValues = false,
            )
        }

    @Test
    fun blurRadiusGoesFromMinToMaxWhenShadeIsNotExpanded() =
        testScope.runTest {
            val values by collectValues(underTest.windowBlurRadius)
            kosmos.keyguardWindowBlurTestUtil.shadeExpanded(false)

            kosmos.keyguardWindowBlurTestUtil.assertTransitionToBlurRadius(
                transitionProgress = listOf(0f, 0f, 0.1f, 0.2f, 0.3f, 1f),
                startValue = kosmos.blurConfig.minBlurRadiusPx,
                endValue = kosmos.blurConfig.maxBlurRadiusPx,
                transitionFactory = ::step,
                actualValuesProvider = { values },
            )
        }

    private fun step(value: Float, state: TransitionState = RUNNING): TransitionStep {
        return TransitionStep(
            from = KeyguardState.ALTERNATE_BOUNCER,
            to = KeyguardState.PRIMARY_BOUNCER,
            value = value,
            transitionState = state,
            ownerName = "AlternateBouncerToPrimaryBouncerTransitionViewModelTest",
        )
    }
}
