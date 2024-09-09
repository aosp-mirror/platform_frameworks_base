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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.scene.ui.viewmodel

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.DefaultEdgeDetector
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.domain.interactor.falsingInteractor
import com.android.systemui.classifier.fakeFalsingManager
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.fakeOverlaysByKeys
import com.android.systemui.scene.sceneContainerConfig
import com.android.systemui.scene.shared.logger.sceneLogger
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.fakeSceneDataSource
import com.android.systemui.shade.data.repository.fakeShadeRepository
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shade.shared.flag.DualShade
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableSceneContainer
class SceneContainerViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope by lazy { kosmos.testScope }
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val fakeSceneDataSource by lazy { kosmos.fakeSceneDataSource }
    private val fakeShadeRepository by lazy { kosmos.fakeShadeRepository }
    private val sceneContainerConfig by lazy { kosmos.sceneContainerConfig }
    private val falsingManager by lazy { kosmos.fakeFalsingManager }

    private lateinit var underTest: SceneContainerViewModel

    private lateinit var activationJob: Job
    private var motionEventHandler: SceneContainerViewModel.MotionEventHandler? = null

    @Before
    fun setUp() {
        underTest =
            SceneContainerViewModel(
                sceneInteractor = sceneInteractor,
                falsingInteractor = kosmos.falsingInteractor,
                powerInteractor = kosmos.powerInteractor,
                shadeInteractor = kosmos.shadeInteractor,
                splitEdgeDetector = kosmos.splitEdgeDetector,
                logger = kosmos.sceneLogger,
                motionEventHandlerReceiver = { motionEventHandler ->
                    this@SceneContainerViewModelTest.motionEventHandler = motionEventHandler
                },
            )
        activationJob = Job()
        underTest.activateIn(testScope, activationJob)
    }

    @Test
    fun activate_setsMotionEventHandler() =
        testScope.runTest {
            runCurrent()
            assertThat(motionEventHandler).isNotNull()
        }

    @Test
    fun deactivate_clearsMotionEventHandler() =
        testScope.runTest {
            activationJob.cancel()
            runCurrent()

            assertThat(motionEventHandler).isNull()
        }

    @Test
    fun isVisible() =
        testScope.runTest {
            assertThat(underTest.isVisible).isTrue()

            sceneInteractor.setVisible(false, "reason")
            runCurrent()
            assertThat(underTest.isVisible).isFalse()

            sceneInteractor.setVisible(true, "reason")
            runCurrent()
            assertThat(underTest.isVisible).isTrue()
        }

    @Test
    fun sceneTransition() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            fakeSceneDataSource.changeScene(Scenes.Shade)

            assertThat(currentScene).isEqualTo(Scenes.Shade)
        }

    @Test
    fun canChangeScene_whenAllowed_switchingFromGone_returnsTrue() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            fakeSceneDataSource.changeScene(toScene = Scenes.Gone)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Gone)

            sceneContainerConfig.sceneKeys
                .filter { it != currentScene }
                .forEach { toScene ->
                    assertWithMessage("Scene $toScene incorrectly protected when allowed")
                        .that(underTest.canChangeScene(toScene = toScene))
                        .isTrue()
                }
        }

    @Test
    fun canChangeScene_whenAllowed_switchingFromLockscreen_returnsTrue() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            fakeSceneDataSource.changeScene(toScene = Scenes.Lockscreen)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            sceneContainerConfig.sceneKeys
                .filter { it != currentScene }
                .forEach { toScene ->
                    assertWithMessage("Scene $toScene incorrectly protected when allowed")
                        .that(underTest.canChangeScene(toScene = toScene))
                        .isTrue()
                }
        }

    @Test
    fun canChangeScene_whenNotAllowed_fromLockscreen_toFalsingProtectedScenes_returnsFalse() =
        testScope.runTest {
            falsingManager.setIsFalseTouch(true)
            val currentScene by collectLastValue(underTest.currentScene)
            fakeSceneDataSource.changeScene(toScene = Scenes.Lockscreen)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            sceneContainerConfig.sceneKeys
                .filter { it != currentScene }
                .filter {
                    // Moving to the Communal scene is not currently falsing protected.
                    it != Scenes.Communal
                }
                .forEach { toScene ->
                    assertWithMessage("Protected scene $toScene not properly protected")
                        .that(underTest.canChangeScene(toScene = toScene))
                        .isFalse()
                }
        }

    @Test
    fun canChangeScene_whenNotAllowed_fromLockscreen_toFalsingUnprotectedScenes_returnsTrue() =
        testScope.runTest {
            falsingManager.setIsFalseTouch(true)
            val currentScene by collectLastValue(underTest.currentScene)
            fakeSceneDataSource.changeScene(toScene = Scenes.Lockscreen)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            sceneContainerConfig.sceneKeys
                .filter {
                    // Moving to the Communal scene is not currently falsing protected.
                    it == Scenes.Communal
                }
                .forEach { toScene ->
                    assertWithMessage("Unprotected scene $toScene is incorrectly protected")
                        .that(underTest.canChangeScene(toScene = toScene))
                        .isTrue()
                }
        }

    @Test
    fun canChangeScene_whenNotAllowed_fromGone_toAnyOtherScene_returnsTrue() =
        testScope.runTest {
            falsingManager.setIsFalseTouch(true)
            val currentScene by collectLastValue(underTest.currentScene)
            fakeSceneDataSource.changeScene(toScene = Scenes.Gone)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Gone)

            sceneContainerConfig.sceneKeys
                .filter { it != currentScene }
                .forEach { toScene ->
                    assertWithMessage("Protected scene $toScene not properly protected")
                        .that(underTest.canChangeScene(toScene = toScene))
                        .isTrue()
                }
        }

    @Test
    fun userInput() =
        testScope.runTest {
            assertThat(kosmos.fakePowerRepository.userTouchRegistered).isFalse()
            underTest.onMotionEvent(mock())
            assertThat(kosmos.fakePowerRepository.userTouchRegistered).isTrue()
        }

    @Test
    fun remoteUserInteraction_keepsContainerVisible() =
        testScope.runTest {
            sceneInteractor.setVisible(false, "reason")
            runCurrent()
            assertThat(underTest.isVisible).isFalse()
            sceneInteractor.onRemoteUserInputStarted("reason")
            runCurrent()
            assertThat(underTest.isVisible).isTrue()

            underTest.onMotionEvent(
                mock { whenever(actionMasked).thenReturn(MotionEvent.ACTION_UP) }
            )
            runCurrent()

            assertThat(underTest.isVisible).isFalse()
        }

    @Test
    fun getActionableContentKey_noOverlays_returnsCurrentScene() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).isEmpty()

            val actionableContentKey =
                underTest.getActionableContentKey(
                    currentScene = checkNotNull(currentScene),
                    currentOverlays = checkNotNull(currentOverlays),
                    overlayByKey = kosmos.fakeOverlaysByKeys,
                )

            assertThat(actionableContentKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun getActionableContentKey_multipleOverlays_returnsTopOverlay() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            fakeSceneDataSource.showOverlay(Overlays.QuickSettingsShade)
            fakeSceneDataSource.showOverlay(Overlays.NotificationsShade)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays)
                .containsExactly(
                    Overlays.QuickSettingsShade,
                    Overlays.NotificationsShade,
                )

            val actionableContentKey =
                underTest.getActionableContentKey(
                    currentScene = checkNotNull(currentScene),
                    currentOverlays = checkNotNull(currentOverlays),
                    overlayByKey = kosmos.fakeOverlaysByKeys,
                )

            assertThat(actionableContentKey).isEqualTo(Overlays.QuickSettingsShade)
        }

    @Test
    @DisableFlags(DualShade.FLAG_NAME)
    fun edgeDetector_singleShade_usesDefaultEdgeDetector() =
        testScope.runTest {
            val shadeMode by collectLastValue(kosmos.shadeInteractor.shadeMode)
            fakeShadeRepository.setShadeLayoutWide(false)
            assertThat(shadeMode).isEqualTo(ShadeMode.Single)

            assertThat(underTest.edgeDetector).isEqualTo(DefaultEdgeDetector)
        }

    @Test
    @DisableFlags(DualShade.FLAG_NAME)
    fun edgeDetector_splitShade_usesDefaultEdgeDetector() =
        testScope.runTest {
            val shadeMode by collectLastValue(kosmos.shadeInteractor.shadeMode)
            fakeShadeRepository.setShadeLayoutWide(true)
            assertThat(shadeMode).isEqualTo(ShadeMode.Split)

            assertThat(underTest.edgeDetector).isEqualTo(DefaultEdgeDetector)
        }

    @Test
    @EnableFlags(DualShade.FLAG_NAME)
    fun edgeDetector_dualShade_narrowScreen_usesSplitEdgeDetector() =
        testScope.runTest {
            val shadeMode by collectLastValue(kosmos.shadeInteractor.shadeMode)
            fakeShadeRepository.setShadeLayoutWide(false)

            assertThat(shadeMode).isEqualTo(ShadeMode.Dual)
            assertThat(underTest.edgeDetector).isEqualTo(kosmos.splitEdgeDetector)
        }

    @Test
    @EnableFlags(DualShade.FLAG_NAME)
    fun edgeDetector_dualShade_wideScreen_usesSplitEdgeDetector() =
        testScope.runTest {
            val shadeMode by collectLastValue(kosmos.shadeInteractor.shadeMode)
            fakeShadeRepository.setShadeLayoutWide(true)

            assertThat(shadeMode).isEqualTo(ShadeMode.Dual)
            assertThat(underTest.edgeDetector).isEqualTo(kosmos.splitEdgeDetector)
        }
}
