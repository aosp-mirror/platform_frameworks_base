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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.ui.transitions.PrimaryBouncerTransition
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class PrimaryBouncerToGlanceableHubTransitionViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest by lazy { kosmos.primaryBouncerToGlanceableHubTransitionViewModel }

    @Test
    @DisableSceneContainer
    fun blurBecomesMinValueImmediately() =
        testScope.runTest {
            val values by collectValues(underTest.windowBlurRadius)

            kosmos.bouncerWindowBlurTestUtil.assertTransitionToBlurRadius(
                transitionProgress = listOf(0.0f, 0.2f, 0.3f, 0.65f, 0.7f, 1.0f),
                startValue = PrimaryBouncerTransition.MIN_BACKGROUND_BLUR_RADIUS,
                endValue = PrimaryBouncerTransition.MIN_BACKGROUND_BLUR_RADIUS,
                actualValuesProvider = { values },
                transitionFactory = { step, transitionState ->
                    TransitionStep(
                        from = KeyguardState.PRIMARY_BOUNCER,
                        to = KeyguardState.GLANCEABLE_HUB,
                        value = step,
                        transitionState = transitionState,
                        ownerName = "PrimaryBouncerToGlanceableHubTransitionViewModelTest",
                    )
                },
                checkInterpolatedValues = false,
            )
        }
}
