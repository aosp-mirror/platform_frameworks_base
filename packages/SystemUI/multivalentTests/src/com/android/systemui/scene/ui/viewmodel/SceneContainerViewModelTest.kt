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

package com.android.systemui.scene.ui.viewmodel

import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.domain.interactor.falsingInteractor
import com.android.systemui.classifier.fakeFalsingManager
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.sceneContainerConfig
import com.android.systemui.scene.sceneKeys
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.fakeSceneDataSource
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableSceneContainer
class SceneContainerViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope by lazy { kosmos.testScope }
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val fakeSceneDataSource by lazy { kosmos.fakeSceneDataSource }
    private val sceneContainerConfig by lazy { kosmos.sceneContainerConfig }
    private val falsingManager by lazy { kosmos.fakeFalsingManager }

    private lateinit var underTest: SceneContainerViewModel

    @Before
    fun setUp() {
        underTest =
            SceneContainerViewModel(
                sceneInteractor = sceneInteractor,
                falsingInteractor = kosmos.falsingInteractor,
                powerInteractor = kosmos.powerInteractor,
            )
    }

    @Test
    fun isVisible() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isVisible)
            assertThat(isVisible).isTrue()

            sceneInteractor.setVisible(false, "reason")
            assertThat(isVisible).isFalse()

            sceneInteractor.setVisible(true, "reason")
            assertThat(isVisible).isTrue()
        }

    @Test
    fun allSceneKeys() {
        assertThat(underTest.allSceneKeys).isEqualTo(kosmos.sceneKeys)
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
            val isVisible by collectLastValue(underTest.isVisible)
            assertThat(isVisible).isFalse()
            sceneInteractor.onRemoteUserInteractionStarted("reason")
            assertThat(isVisible).isTrue()

            underTest.onMotionEvent(
                mock { whenever(actionMasked).thenReturn(MotionEvent.ACTION_UP) }
            )

            assertThat(isVisible).isFalse()
        }
}
