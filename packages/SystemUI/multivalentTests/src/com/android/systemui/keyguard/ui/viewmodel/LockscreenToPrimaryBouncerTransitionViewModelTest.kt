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
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.flags.BrokenWithSceneContainer
import com.android.systemui.flags.Flags
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.ui.transitions.PrimaryBouncerTransition
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.data.repository.sceneContainerRepository
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.testKosmos
import com.google.common.collect.Range
import com.google.common.truth.Truth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
class LockscreenToPrimaryBouncerTransitionViewModelTest(flags: FlagsParameterization) :
    SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            fakeFeatureFlagsClassic.apply { set(Flags.FULL_SCREEN_USER_SWITCHER, false) }
        }
    private val testScope = kosmos.testScope
    private val repository = kosmos.fakeKeyguardTransitionRepository
    private val shadeTestUtil by lazy { kosmos.shadeTestUtil }
    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private lateinit var underTest: LockscreenToPrimaryBouncerTransitionViewModel

    private val transitionState =
        MutableStateFlow<ObservableTransitionState>(
            ObservableTransitionState.Idle(Scenes.Lockscreen)
        )

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
        underTest = kosmos.lockscreenToPrimaryBouncerTransitionViewModel
    }

    @Test
    @BrokenWithSceneContainer(330311871)
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

            kosmos.sceneContainerRepository.setTransitionState(transitionState)
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Lockscreen,
                    toScene = Scenes.Bouncer,
                    emptyFlow(),
                    emptyFlow(),
                    false,
                    emptyFlow(),
                )
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

    @Test
    @BrokenWithSceneContainer(330311871)
    fun blurRadiusIsMaxWhenShadeIsExpanded() =
        testScope.runTest {
            val values by collectValues(underTest.windowBlurRadius)
            kosmos.bouncerWindowBlurTestUtil.shadeExpanded(true)

            kosmos.bouncerWindowBlurTestUtil.assertTransitionToBlurRadius(
                transitionProgress = listOf(0.0f, 0.2f, 0.3f, 0.65f, 0.7f, 1.0f),
                startValue = PrimaryBouncerTransition.MAX_BACKGROUND_BLUR_RADIUS,
                endValue = PrimaryBouncerTransition.MAX_BACKGROUND_BLUR_RADIUS,
                actualValuesProvider = { values },
                transitionFactory = ::step,
                checkInterpolatedValues = false,
            )
        }

    @Test
    @BrokenWithSceneContainer(330311871)
    fun blurRadiusGoesFromMinToMaxWhenShadeIsNotExpanded() =
        testScope.runTest {
            val values by collectValues(underTest.windowBlurRadius)
            kosmos.bouncerWindowBlurTestUtil.shadeExpanded(false)

            kosmos.bouncerWindowBlurTestUtil.assertTransitionToBlurRadius(
                transitionProgress = listOf(0.0f, 0.2f, 0.3f, 0.65f, 0.7f, 1.0f),
                startValue = PrimaryBouncerTransition.MIN_BACKGROUND_BLUR_RADIUS,
                endValue = PrimaryBouncerTransition.MAX_BACKGROUND_BLUR_RADIUS,
                actualValuesProvider = { values },
                transitionFactory = ::step,
            )
        }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING,
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.LOCKSCREEN,
            to =
                if (SceneContainerFlag.isEnabled) KeyguardState.UNDEFINED
                else KeyguardState.PRIMARY_BOUNCER,
            value = value,
            transitionState = state,
            ownerName = "LockscreenToPrimaryBouncerTransitionViewModelTest",
        )
    }

    private fun shadeExpanded(expanded: Boolean) {
        if (expanded) {
            shadeTestUtil.setQsExpansion(1f)
        } else {
            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            shadeTestUtil.setQsExpansion(0f)
            shadeTestUtil.setLockscreenShadeExpansion(0f)
        }
    }
}
