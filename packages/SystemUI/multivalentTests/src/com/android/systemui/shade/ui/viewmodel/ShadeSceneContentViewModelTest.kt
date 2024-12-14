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

package com.android.systemui.shade.ui.viewmodel

import android.platform.test.annotations.DisableFlags
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.media.controls.data.repository.mediaFilterRepository
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.qs.ui.adapter.fakeQSSceneAdapter
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.shared.flag.DualShade
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.testKosmos
import com.android.systemui.unfold.fakeUnfoldTransitionProgressProvider
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
@EnableSceneContainer
@DisableFlags(DualShade.FLAG_NAME)
class ShadeSceneContentViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val shadeRepository by lazy { kosmos.shadeRepository }
    private val qsSceneAdapter by lazy { kosmos.fakeQSSceneAdapter }

    private val underTest: ShadeSceneContentViewModel by lazy { kosmos.shadeSceneContentViewModel }

    @Before
    fun setUp() {
        underTest.activateIn(testScope)
    }

    @Test
    fun isEmptySpaceClickable_deviceUnlocked_false() =
        testScope.runTest {
            val isEmptySpaceClickable by collectLastValue(underTest.isEmptySpaceClickable)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            setDeviceEntered(true)
            runCurrent()

            assertThat(isEmptySpaceClickable).isFalse()
        }

    @Test
    fun isEmptySpaceClickable_deviceLockedSecurely_true() =
        testScope.runTest {
            val isEmptySpaceClickable by collectLastValue(underTest.isEmptySpaceClickable)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            runCurrent()

            assertThat(isEmptySpaceClickable).isTrue()
        }

    @Test
    fun onEmptySpaceClicked_deviceLockedSecurely_switchesToLockscreen() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            runCurrent()

            underTest.onEmptySpaceClicked()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun addAndRemoveMedia_mediaVisibilityisUpdated() =
        testScope.runTest {
            val isMediaVisible by collectLastValue(underTest.isMediaVisible)
            val userMedia = MediaData(active = true)

            assertThat(isMediaVisible).isFalse()

            kosmos.mediaFilterRepository.addSelectedUserMediaEntry(userMedia)

            assertThat(isMediaVisible).isTrue()

            kosmos.mediaFilterRepository.removeSelectedUserMediaEntry(userMedia.instanceId)

            assertThat(isMediaVisible).isFalse()
        }

    @Test
    fun shadeMode() =
        testScope.runTest {
            val shadeMode by collectLastValue(underTest.shadeMode)

            shadeRepository.setShadeLayoutWide(true)
            assertThat(shadeMode).isEqualTo(ShadeMode.Split)

            shadeRepository.setShadeLayoutWide(false)
            assertThat(shadeMode).isEqualTo(ShadeMode.Single)

            shadeRepository.setShadeLayoutWide(true)
            assertThat(shadeMode).isEqualTo(ShadeMode.Split)
        }

    @Test
    fun unfoldTransitionProgress() =
        testScope.runTest {
            val maxTranslation = prepareConfiguration()
            val translations by
                collectLastValue(
                    combine(
                        underTest.unfoldTranslationX(isOnStartSide = true),
                        underTest.unfoldTranslationX(isOnStartSide = false),
                    ) { start, end ->
                        Translations(
                            start = start,
                            end = end,
                        )
                    }
                )

            val unfoldProvider = kosmos.fakeUnfoldTransitionProgressProvider
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

    private fun prepareConfiguration(): Int {
        val configuration = context.resources.configuration
        configuration.setLayoutDirection(Locale.US)
        kosmos.fakeConfigurationRepository.onConfigurationChange(configuration)
        val maxTranslation = 10
        kosmos.fakeConfigurationRepository.setDimensionPixelSize(
            R.dimen.notification_side_paddings,
            maxTranslation
        )
        return maxTranslation
    }

    private fun TestScope.setDeviceEntered(isEntered: Boolean) {
        if (isEntered) {
            // Unlock the device marking the device has entered.
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()
        }
        setScene(
            if (isEntered) {
                Scenes.Gone
            } else {
                Scenes.Lockscreen
            }
        )
        assertThat(kosmos.deviceEntryInteractor.isDeviceEntered.value).isEqualTo(isEntered)
    }

    private fun TestScope.setScene(key: SceneKey) {
        sceneInteractor.changeScene(key, "test")
        sceneInteractor.setTransitionState(
            MutableStateFlow<ObservableTransitionState>(ObservableTransitionState.Idle(key))
        )
        runCurrent()
    }

    private data class Translations(
        val start: Float,
        val end: Float,
    )
}
