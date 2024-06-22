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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.scene.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.domain.interactor.AuthenticationResult
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.domain.startable.sceneContainerStartable
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SceneBackInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val sceneContainerStartable by lazy { kosmos.sceneContainerStartable }
    private val authenticationInteractor by lazy { kosmos.authenticationInteractor }

    private val underTest by lazy { kosmos.sceneBackInteractor }

    @Test
    @EnableSceneContainer
    fun navigateToQs_thenBouncer_thenBack_whileLocked() =
        testScope.runTest {
            sceneContainerStartable.start()

            assertRoute(
                RouteNode(Scenes.Lockscreen, null),
                RouteNode(Scenes.Shade, Scenes.Lockscreen),
                RouteNode(Scenes.QuickSettings, Scenes.Shade),
                RouteNode(Scenes.Bouncer, Scenes.QuickSettings),
                RouteNode(Scenes.QuickSettings, Scenes.Shade),
                RouteNode(Scenes.Shade, Scenes.Lockscreen),
                RouteNode(Scenes.Lockscreen, null),
            )
        }

    @Test
    @EnableSceneContainer
    fun navigateToQs_thenBouncer_thenUnlock() =
        testScope.runTest {
            sceneContainerStartable.start()

            assertRoute(
                RouteNode(Scenes.Lockscreen, null),
                RouteNode(Scenes.Shade, Scenes.Lockscreen),
                RouteNode(Scenes.QuickSettings, Scenes.Shade),
                RouteNode(Scenes.Bouncer, Scenes.QuickSettings, unlockDevice = true),
                RouteNode(Scenes.Gone, null),
            )
        }

    @Test
    @EnableSceneContainer
    fun navigateToQs_skippingShade_thenBouncer_thenBack_whileLocked() =
        testScope.runTest {
            sceneContainerStartable.start()

            assertRoute(
                RouteNode(Scenes.Lockscreen, null),
                RouteNode(Scenes.QuickSettings, Scenes.Lockscreen),
                RouteNode(Scenes.Bouncer, Scenes.QuickSettings),
                RouteNode(Scenes.QuickSettings, Scenes.Lockscreen),
                RouteNode(Scenes.Lockscreen, null),
            )
        }

    @Test
    @EnableSceneContainer
    fun navigateToBouncer_thenBack_whileLocked() =
        testScope.runTest {
            sceneContainerStartable.start()

            assertRoute(
                RouteNode(Scenes.Lockscreen, null),
                RouteNode(Scenes.Bouncer, Scenes.Lockscreen),
                RouteNode(Scenes.Lockscreen, null),
            )
        }

    @Test
    @EnableSceneContainer
    fun navigateToQs_skippingShade_thenBouncer_thenBack_thenShade_whileLocked() =
        testScope.runTest {
            sceneContainerStartable.start()

            assertRoute(
                RouteNode(Scenes.Lockscreen, null),
                RouteNode(Scenes.QuickSettings, Scenes.Lockscreen),
                RouteNode(Scenes.Bouncer, Scenes.QuickSettings),
                RouteNode(Scenes.QuickSettings, Scenes.Lockscreen),
                RouteNode(Scenes.Lockscreen, null),
                RouteNode(Scenes.Shade, Scenes.Lockscreen),
            )
        }

    @Test
    @EnableSceneContainer
    fun navigateToQs_thenBack_whileUnlocked() =
        testScope.runTest {
            sceneContainerStartable.start()
            unlockDevice()

            assertRoute(
                RouteNode(Scenes.Gone, null),
                RouteNode(Scenes.Shade, Scenes.Gone),
                RouteNode(Scenes.QuickSettings, Scenes.Shade),
                RouteNode(Scenes.Shade, Scenes.Gone),
                RouteNode(Scenes.Gone, null),
            )
        }

    @Test
    @EnableSceneContainer
    fun navigateToQs_skippingShade_thenBack_whileUnlocked() =
        testScope.runTest {
            sceneContainerStartable.start()
            unlockDevice()

            assertRoute(
                RouteNode(Scenes.Gone, null),
                RouteNode(Scenes.QuickSettings, Scenes.Gone),
                RouteNode(Scenes.Gone, null),
            )
        }

    @Test
    @EnableSceneContainer
    fun navigateToQs_skippingShade_thenBack_thenShade_whileUnlocked() =
        testScope.runTest {
            sceneContainerStartable.start()
            unlockDevice()

            assertRoute(
                RouteNode(Scenes.Gone, null),
                RouteNode(Scenes.QuickSettings, Scenes.Gone),
                RouteNode(Scenes.Gone, null),
                RouteNode(Scenes.Shade, Scenes.Gone),
            )
        }

    private suspend fun TestScope.assertRoute(vararg route: RouteNode) {
        val currentScene by collectLastValue(sceneInteractor.currentScene)
        val backScene by collectLastValue(underTest.backScene)

        route.forEachIndexed { index, node ->
            sceneInteractor.changeScene(node.changeSceneTo, "")
            assertWithMessage("node at index $index currentScene mismatch")
                .that(currentScene)
                .isEqualTo(node.changeSceneTo)
            assertWithMessage("node at index $index backScene mismatch")
                .that(backScene)
                .isEqualTo(node.expectedBackScene)
            if (node.unlockDevice) {
                unlockDevice()
            }
        }
    }

    private suspend fun TestScope.unlockDevice() {
        val currentScene by collectLastValue(sceneInteractor.currentScene)
        runCurrent()
        assertThat(authenticationInteractor.authenticate(FakeAuthenticationRepository.DEFAULT_PIN))
            .isEqualTo(AuthenticationResult.SUCCEEDED)
        assertThat(currentScene).isEqualTo(Scenes.Gone)
    }

    private data class RouteNode(
        val changeSceneTo: SceneKey,
        val expectedBackScene: SceneKey? = null,
        val unlockDevice: Boolean = false,
    )
}
