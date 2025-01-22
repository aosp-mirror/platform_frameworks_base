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

import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.authController
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.keyguard.data.repository.fakeKeyguardClockRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.keyguardOcclusionRepository
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.transition.fakeKeyguardTransitionAnimationCallback
import com.android.systemui.keyguard.shared.transition.keyguardTransitionAnimationCallbackDelegator
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.domain.interactor.enableDualShade
import com.android.systemui.testKosmos
import com.android.systemui.unfold.fakeUnfoldTransitionProgressProvider
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import kotlinx.coroutines.Job
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class LockscreenContentViewModelTest(flags: FlagsParameterization) : SysuiTestCase() {

    private val kosmos: Kosmos = testKosmos()

    private lateinit var underTest: LockscreenContentViewModel
    private val activationJob = Job()

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
        with(kosmos) {
            shadeRepository.setShadeLayoutWide(false)
            underTest =
                lockscreenContentViewModelFactory.create(fakeKeyguardTransitionAnimationCallback)
            underTest.activateIn(testScope, activationJob)
        }
    }

    @Test
    fun isUdfpsVisible_withUdfps_true() =
        kosmos.runTest {
            whenever(authController.isUdfpsSupported).thenReturn(true)
            assertThat(underTest.isUdfpsVisible).isTrue()
        }

    @Test
    fun isUdfpsVisible_withoutUdfps_false() =
        kosmos.runTest {
            whenever(authController.isUdfpsSupported).thenReturn(false)
            assertThat(underTest.isUdfpsVisible).isFalse()
        }

    @Test
    @DisableSceneContainer
    fun clockSize_withLargeClock_true() =
        kosmos.runTest {
            val clockSize by collectLastValue(underTest.clockSize)
            fakeKeyguardClockRepository.setClockSize(ClockSize.LARGE)
            assertThat(clockSize).isEqualTo(ClockSize.LARGE)
        }

    @Test
    @DisableSceneContainer
    fun clockSize_withSmallClock_false() =
        kosmos.runTest {
            val clockSize by collectLastValue(underTest.clockSize)
            fakeKeyguardClockRepository.setClockSize(ClockSize.SMALL)
            assertThat(clockSize).isEqualTo(ClockSize.SMALL)
        }

    @Test
    fun areNotificationsVisible_splitShadeTrue_true() =
        kosmos.runTest {
            val areNotificationsVisible by collectLastValue(underTest.areNotificationsVisible())
            shadeRepository.setShadeLayoutWide(true)
            fakeKeyguardClockRepository.setClockSize(ClockSize.LARGE)

            assertThat(areNotificationsVisible).isTrue()
        }

    @Test
    fun areNotificationsVisible_dualShadeWideOnLockscreen_true() =
        kosmos.runTest {
            val areNotificationsVisible by collectLastValue(underTest.areNotificationsVisible())
            kosmos.enableDualShade()
            shadeRepository.setShadeLayoutWide(true)
            fakeKeyguardClockRepository.setClockSize(ClockSize.LARGE)

            assertThat(areNotificationsVisible).isTrue()
        }

    @Test
    @DisableSceneContainer
    fun areNotificationsVisible_withSmallClock_true() =
        kosmos.runTest {
            val areNotificationsVisible by collectLastValue(underTest.areNotificationsVisible())
            fakeKeyguardClockRepository.setClockSize(ClockSize.SMALL)
            assertThat(areNotificationsVisible).isTrue()
        }

    @Test
    @DisableSceneContainer
    fun areNotificationsVisible_withLargeClock_false() =
        kosmos.runTest {
            val areNotificationsVisible by collectLastValue(underTest.areNotificationsVisible())
            fakeKeyguardClockRepository.setClockSize(ClockSize.LARGE)
            assertThat(areNotificationsVisible).isFalse()
        }

    @Test
    fun isShadeLayoutWide_withConfigTrue_true() =
        kosmos.runTest {
            val isShadeLayoutWide by collectLastValue(underTest.isShadeLayoutWide)
            shadeRepository.setShadeLayoutWide(true)

            assertThat(isShadeLayoutWide).isTrue()
        }

    @Test
    fun isShadeLayoutWide_withConfigFalse_false() =
        kosmos.runTest {
            val isShadeLayoutWide by collectLastValue(underTest.isShadeLayoutWide)
            shadeRepository.setShadeLayoutWide(false)

            assertThat(isShadeLayoutWide).isFalse()
        }

    @Test
    fun unfoldTranslations() =
        kosmos.runTest {
            val maxTranslation = prepareConfiguration()
            val translations by collectLastValue(underTest.unfoldTranslations)

            val unfoldProvider = fakeUnfoldTransitionProgressProvider
            unfoldProvider.onTransitionStarted()
            assertThat(translations?.start).isEqualTo(0f)
            assertThat(translations?.end).isEqualTo(-0f)

            repeat(10) { repetition ->
                val transitionProgress = 0.1f * (repetition + 1)
                unfoldProvider.onTransitionProgress(transitionProgress)
                assertThat(translations?.start).isEqualTo((1 - transitionProgress) * maxTranslation)
                assertThat(translations?.end).isEqualTo(-(1 - transitionProgress) * maxTranslation)
            }

            unfoldProvider.onTransitionFinishing()
            assertThat(translations?.start).isEqualTo(0f)
            assertThat(translations?.end).isEqualTo(-0f)

            unfoldProvider.onTransitionFinished()
            assertThat(translations?.start).isEqualTo(0f)
            assertThat(translations?.end).isEqualTo(-0f)
        }

    @Test
    fun isContentVisible_whenNotOccluded_visible() =
        kosmos.runTest {
            val isContentVisible by collectLastValue(underTest.isContentVisible)

            keyguardOcclusionRepository.setShowWhenLockedActivityInfo(false, null)
            runCurrent()
            assertThat(isContentVisible).isTrue()
        }

    @Test
    fun isContentVisible_whenOccluded_notVisible() =
        kosmos.runTest {
            val isContentVisible by collectLastValue(underTest.isContentVisible)

            keyguardOcclusionRepository.setShowWhenLockedActivityInfo(true, null)
            fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.LOCKSCREEN,
                KeyguardState.OCCLUDED,
            )
            runCurrent()
            assertThat(isContentVisible).isFalse()
        }

    @Test
    fun isContentVisible_whenOccluded_notVisible_evenIfShadeShown() =
        kosmos.runTest {
            val isContentVisible by collectLastValue(underTest.isContentVisible)

            keyguardOcclusionRepository.setShowWhenLockedActivityInfo(true, null)
            fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.LOCKSCREEN,
                KeyguardState.OCCLUDED,
            )
            runCurrent()

            sceneInteractor.snapToScene(Scenes.Shade, "")
            runCurrent()
            assertThat(isContentVisible).isFalse()
        }

    @Test
    fun activate_setsDelegate_onKeyguardTransitionAnimationCallbackDelegator() =
        kosmos.runTest {
            runCurrent()
            assertThat(keyguardTransitionAnimationCallbackDelegator.delegate)
                .isSameInstanceAs(fakeKeyguardTransitionAnimationCallback)
        }

    @Test
    fun deactivate_clearsDelegate_onKeyguardTransitionAnimationCallbackDelegator() =
        kosmos.runTest {
            activationJob.cancel()
            runCurrent()
            assertThat(keyguardTransitionAnimationCallbackDelegator.delegate).isNull()
        }

    @Test
    fun isContentVisible_whenOccluded_notVisibleInOccluded_visibleInAod() =
        kosmos.runTest {
            val isContentVisible by collectLastValue(underTest.isContentVisible)
            keyguardOcclusionRepository.setShowWhenLockedActivityInfo(true, null)
            fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.LOCKSCREEN,
                KeyguardState.OCCLUDED,
            )
            runCurrent()

            sceneInteractor.snapToScene(Scenes.Shade, "")
            runCurrent()
            assertThat(isContentVisible).isFalse()

            fakeKeyguardTransitionRepository.transitionTo(KeyguardState.OCCLUDED, KeyguardState.AOD)
            runCurrent()

            sceneInteractor.snapToScene(Scenes.Lockscreen, "")
            runCurrent()

            assertThat(isContentVisible).isTrue()
        }

    private fun prepareConfiguration(): Int {
        val configuration = context.resources.configuration
        configuration.setLayoutDirection(Locale.US)
        kosmos.fakeConfigurationRepository.onConfigurationChange(configuration)
        val maxTranslation = 10
        kosmos.fakeConfigurationRepository.setDimensionPixelSize(
            R.dimen.notification_side_paddings,
            maxTranslation,
        )
        return maxTranslation
    }
}
