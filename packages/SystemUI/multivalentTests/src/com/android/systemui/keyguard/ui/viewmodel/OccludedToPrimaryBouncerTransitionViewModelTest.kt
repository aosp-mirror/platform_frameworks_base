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
import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_NOTIFICATION_SHADE_BLUR
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.flags.BrokenWithSceneContainer
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.ui.transitions.blurConfig
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class OccludedToPrimaryBouncerTransitionViewModelTest(flags: FlagsParameterization) :
    SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private lateinit var underTest: OccludedToPrimaryBouncerTransitionViewModel

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
        underTest = kosmos.occludedToPrimaryBouncerTransitionViewModel
    }

    @Test
    @BrokenWithSceneContainer(388068805)
    fun notificationsAreBlurredImmediatelyWhenBouncerIsOpenedAndShadeIsExpanded() =
        testScope.runTest {
            val values by collectValues(underTest.notificationBlurRadius)
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

    @Test
    @EnableFlags(FLAG_NOTIFICATION_SHADE_BLUR)
    @BrokenWithSceneContainer(388068805)
    fun blurBecomesMaxValueImmediatelyWhenShadeIsAlreadyExpanded() =
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

    fun step(value: Float, state: TransitionState = TransitionState.RUNNING): TransitionStep {
        return TransitionStep(
            from = KeyguardState.OCCLUDED,
            to = KeyguardState.PRIMARY_BOUNCER,
            value = value,
            transitionState = state,
            ownerName = "OccludedToPrimaryBouncerTransitionViewModelTest",
        )
    }
}
