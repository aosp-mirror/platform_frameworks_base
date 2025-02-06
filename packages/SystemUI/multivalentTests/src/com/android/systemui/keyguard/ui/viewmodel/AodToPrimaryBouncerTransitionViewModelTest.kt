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
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionState.FINISHED
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.shared.model.TransitionState.STARTED
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.ui.transitions.blurConfig
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class AodToPrimaryBouncerTransitionViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest by lazy { kosmos.aodToPrimaryBouncerTransitionViewModel }

    @Test
    fun aodToPrimaryBouncerChangesBlurToMax() =
        testScope.runTest {
            val values by collectValues(underTest.windowBlurRadius)

            kosmos.keyguardWindowBlurTestUtil.assertTransitionToBlurRadius(
                transitionProgress = listOf(0.0f, 0.0f, 0.3f, 0.4f, 0.5f, 1.0f),
                startValue = kosmos.blurConfig.maxBlurRadiusPx,
                endValue = kosmos.blurConfig.maxBlurRadiusPx,
                transitionFactory = ::step,
                actualValuesProvider = { values },
                checkInterpolatedValues = false,
            )
        }

    @Test
    @EnableFlags(Flags.FLAG_BOUNCER_UI_REVAMP)
    fun aodToPrimaryBouncerHidesLockscreen() =
        testScope.runTest {
            val lockscreenAlpha by collectValues(underTest.lockscreenAlpha)
            val notificationAlpha by collectValues(underTest.notificationAlpha)

            val transitionSteps = listOf(step(0.0f, STARTED), step(0.5f), step(1.0f, FINISHED))
            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(transitionSteps, testScope)
            runCurrent()

            lockscreenAlpha.forEach { assertThat(it).isEqualTo(0.0f) }
            notificationAlpha.forEach { assertThat(it).isEqualTo(0.0f) }
        }

    private fun step(value: Float, transitionState: TransitionState = RUNNING) =
        TransitionStep(
            from = KeyguardState.AOD,
            to = KeyguardState.PRIMARY_BOUNCER,
            value = value,
            transitionState = transitionState,
            ownerName = "AodToPrimaryBouncerTransitionViewModelTest",
        )
}
