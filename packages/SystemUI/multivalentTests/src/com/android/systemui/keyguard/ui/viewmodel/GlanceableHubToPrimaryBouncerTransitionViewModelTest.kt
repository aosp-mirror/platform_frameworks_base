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

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_GLANCEABLE_HUB_BLURRED_BACKGROUND
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.ui.transitions.blurConfig
import com.android.systemui.kosmos.collectValues
import com.android.systemui.kosmos.runTest
import com.android.systemui.testKosmos
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class GlanceableHubToPrimaryBouncerTransitionViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val underTest by lazy { kosmos.glanceableHubToPrimaryBouncerTransitionViewModel }

    @Test
    @DisableSceneContainer
    @DisableFlags(FLAG_GLANCEABLE_HUB_BLURRED_BACKGROUND)
    fun blurBecomesMaxValueImmediately() =
        kosmos.runTest {
            val values by collectValues(underTest.windowBlurRadius)

            kosmos.keyguardWindowBlurTestUtil.assertTransitionToBlurRadius(
                transitionProgress = listOf(0.0f, 0.2f, 0.3f, 0.65f, 0.7f, 1.0f),
                startValue = blurConfig.maxBlurRadiusPx,
                endValue = blurConfig.maxBlurRadiusPx,
                actualValuesProvider = { values },
                transitionFactory = { step, transitionState ->
                    TransitionStep(
                        from = KeyguardState.GLANCEABLE_HUB,
                        to = KeyguardState.PRIMARY_BOUNCER,
                        value = step,
                        transitionState = transitionState,
                        ownerName = "GlanceableHubToPrimaryBouncerTransitionViewModelTest",
                    )
                },
                checkInterpolatedValues = false,
            )
        }

    @Test
    @DisableSceneContainer
    @EnableFlags(FLAG_GLANCEABLE_HUB_BLURRED_BACKGROUND)
    fun noBlurTransitionWithBlurredGlanceableHub() =
        kosmos.runTest {
            val values by collectValues(underTest.windowBlurRadius)

            keyguardWindowBlurTestUtil.assertNoBlurRadiusTransition(
                transitionProgress = listOf(0.0f, 0.2f, 0.3f, 0.65f, 0.7f, 1.0f),
                actualValuesProvider = { values },
                transitionFactory = { step, transitionState ->
                    TransitionStep(
                        from = KeyguardState.GLANCEABLE_HUB,
                        to = KeyguardState.PRIMARY_BOUNCER,
                        value = step,
                        transitionState = transitionState,
                        ownerName = "GlanceableHubToPrimaryBouncerTransitionViewModelTest",
                    )
                },
            )
        }
}
