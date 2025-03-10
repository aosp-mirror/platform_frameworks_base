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

package com.android.systemui.scene.ui.viewmodel

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.ObservableTransitionState.Transition.ShowOrHideOverlay
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.haptics.msdl.fakeMSDLPlayer
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.sceneContainerHapticsViewModelFactory
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.google.android.msdl.data.model.MSDLToken
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableSceneContainer
class SceneContainerHapticsViewModelTest() : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val msdlPlayer = kosmos.fakeMSDLPlayer
    private val view = mock<View>()

    private lateinit var underTest: SceneContainerHapticsViewModel

    @Before
    fun setup() {
        underTest = kosmos.sceneContainerHapticsViewModelFactory.create(view)
        underTest.activateIn(testScope)
    }

    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    @DisableFlags(Flags.FLAG_DUAL_SHADE)
    @Test
    fun onValidSceneTransition_withMSDL_playsMSDLShadePullHaptics() =
        testScope.runTest {
            // GIVEN a valid scene transition to play haptics
            val validTransition = createTransitionState(from = Scenes.Gone, to = Scenes.Shade)

            // WHEN the transition occurs
            sceneInteractor.setTransitionState(MutableStateFlow(validTransition))
            runCurrent()

            // THEN the expected token plays without interaction properties
            assertThat(msdlPlayer.latestTokenPlayed).isEqualTo(MSDLToken.SWIPE_THRESHOLD_INDICATOR)
            assertThat(msdlPlayer.latestPropertiesPlayed).isNull()
        }

    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    @DisableFlags(Flags.FLAG_DUAL_SHADE)
    @Test
    fun onInValidSceneTransition_withMSDL_doesNotPlayMSDLShadePullHaptics() =
        testScope.runTest {
            // GIVEN an invalid scene transition to play haptics
            val invalidTransition = createTransitionState(from = Scenes.Shade, to = Scenes.Gone)

            // WHEN the transition occurs
            sceneInteractor.setTransitionState(MutableStateFlow(invalidTransition))
            runCurrent()

            // THEN the no token plays with no interaction properties
            assertThat(msdlPlayer.latestTokenPlayed).isNull()
            assertThat(msdlPlayer.latestPropertiesPlayed).isNull()
        }

    @DisableFlags(Flags.FLAG_DUAL_SHADE, Flags.FLAG_MSDL_FEEDBACK)
    @Test
    fun onValidSceneTransition_withoutMSDL_playsHapticConstantForShadePullHaptics() =
        testScope.runTest {
            // GIVEN a valid scene transition to play haptics
            val validTransition = createTransitionState(from = Scenes.Gone, to = Scenes.Shade)

            // WHEN the transition occurs
            sceneInteractor.setTransitionState(MutableStateFlow(validTransition))
            runCurrent()

            // THEN the expected haptic feedback constant plays
            verify(view).performHapticFeedback(eq(HapticFeedbackConstants.GESTURE_START))
        }

    @DisableFlags(Flags.FLAG_DUAL_SHADE, Flags.FLAG_MSDL_FEEDBACK)
    @Test
    fun onInValidSceneTransition_withoutMSDL_doesNotPlayHapticConstantForShadePullHaptics() =
        testScope.runTest {
            // GIVEN an invalid scene transition to play haptics
            val invalidTransition = createTransitionState(from = Scenes.Shade, to = Scenes.Gone)

            // WHEN the transition occurs
            sceneInteractor.setTransitionState(MutableStateFlow(invalidTransition))
            runCurrent()

            // THEN the view does not play a haptic feedback constant
            verifyNoMoreInteractions(view)
        }

    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK, Flags.FLAG_DUAL_SHADE)
    @Test
    fun onValidOverlayTransition_withMSDL_playsMSDLShadePullHaptics() =
        testScope.runTest {
            // GIVEN a valid scene transition to play haptics
            val validTransition =
                createTransitionState(from = Scenes.Gone, to = Overlays.NotificationsShade)

            // WHEN the transition occurs
            sceneInteractor.setTransitionState(MutableStateFlow(validTransition))
            runCurrent()

            // THEN the expected token plays without interaction properties
            assertThat(msdlPlayer.latestTokenPlayed).isEqualTo(MSDLToken.SWIPE_THRESHOLD_INDICATOR)
            assertThat(msdlPlayer.latestPropertiesPlayed).isNull()
        }

    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK, Flags.FLAG_DUAL_SHADE)
    @Test
    fun onInValidOverlayTransition_withMSDL_doesNotPlayMSDLShadePullHaptics() =
        testScope.runTest {
            // GIVEN an invalid scene transition to play haptics
            val invalidTransition =
                createTransitionState(from = Scenes.Bouncer, to = Overlays.NotificationsShade)

            // WHEN the transition occurs
            sceneInteractor.setTransitionState(MutableStateFlow(invalidTransition))
            runCurrent()

            // THEN the no token plays with no interaction properties
            assertThat(msdlPlayer.latestTokenPlayed).isNull()
            assertThat(msdlPlayer.latestPropertiesPlayed).isNull()
        }

    @EnableFlags(Flags.FLAG_DUAL_SHADE)
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    @Test
    fun onValidOverlayTransition_withoutMSDL_playsHapticConstantForShadePullHaptics() =
        testScope.runTest {
            // GIVEN a valid scene transition to play haptics
            val validTransition =
                createTransitionState(from = Scenes.Gone, to = Overlays.NotificationsShade)

            // WHEN the transition occurs
            sceneInteractor.setTransitionState(MutableStateFlow(validTransition))
            runCurrent()

            // THEN the expected haptic feedback constant plays
            verify(view).performHapticFeedback(eq(HapticFeedbackConstants.GESTURE_START))
        }

    @EnableFlags(Flags.FLAG_DUAL_SHADE)
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    @Test
    fun onInValidOverlayTransition_withoutMSDL_doesNotPlayHapticConstantForShadePullHaptics() =
        testScope.runTest {
            // GIVEN an invalid scene transition to play haptics
            val invalidTransition =
                createTransitionState(from = Scenes.Bouncer, to = Overlays.NotificationsShade)

            // WHEN the transition occurs
            sceneInteractor.setTransitionState(MutableStateFlow(invalidTransition))
            runCurrent()

            // THEN the view does not play a haptic feedback constant
            verifyNoMoreInteractions(view)
        }

    private fun createTransitionState(from: SceneKey, to: ContentKey) =
        when (to) {
            is SceneKey ->
                ObservableTransitionState.Transition(
                    fromScene = from,
                    toScene = to,
                    currentScene = flowOf(from),
                    progress = MutableStateFlow(0.2f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(true),
                )
            is OverlayKey ->
                ShowOrHideOverlay(
                    overlay = to,
                    fromContent = from,
                    toContent = to,
                    currentScene = from,
                    currentOverlays = sceneInteractor.currentOverlays,
                    progress = MutableStateFlow(0.2f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(true),
                    previewProgress = flowOf(0f),
                    isInPreviewStage = flowOf(false),
                )
        }
}
