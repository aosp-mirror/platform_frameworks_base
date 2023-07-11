/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.scene.domain.startable

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.shared.model.SceneContainerNames
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class SystemUiDefaultSceneContainerStartableTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope
    private val sceneInteractor = utils.sceneInteractor()
    private val featureFlags = utils.featureFlags
    private val authenticationRepository = utils.authenticationRepository()
    private val authenticationInteractor =
        utils.authenticationInteractor(
            repository = authenticationRepository,
        )

    private val underTest =
        SystemUiDefaultSceneContainerStartable(
            applicationScope = testScope.backgroundScope,
            sceneInteractor = sceneInteractor,
            authenticationInteractor = authenticationInteractor,
            featureFlags = featureFlags,
        )

    @Before
    fun setUp() {
        prepareState()
    }

    @Test
    fun hydrateVisibility_featureEnabled() =
        testScope.runTest {
            val currentSceneKey by
                collectLastValue(
                    sceneInteractor.currentScene(SceneContainerNames.SYSTEM_UI_DEFAULT).map {
                        it.key
                    }
                )
            val isVisible by
                collectLastValue(sceneInteractor.isVisible(SceneContainerNames.SYSTEM_UI_DEFAULT))
            prepareState(
                isFeatureEnabled = true,
                isDeviceUnlocked = true,
                initialSceneKey = SceneKey.Gone,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Gone)
            assertThat(isVisible).isTrue()

            underTest.start()

            assertThat(isVisible).isFalse()

            sceneInteractor.setCurrentScene(
                SceneContainerNames.SYSTEM_UI_DEFAULT,
                SceneModel(SceneKey.Shade)
            )
            assertThat(isVisible).isTrue()
        }

    @Test
    fun hydrateVisibility_featureDisabled() =
        testScope.runTest {
            val currentSceneKey by
                collectLastValue(
                    sceneInteractor.currentScene(SceneContainerNames.SYSTEM_UI_DEFAULT).map {
                        it.key
                    }
                )
            val isVisible by
                collectLastValue(sceneInteractor.isVisible(SceneContainerNames.SYSTEM_UI_DEFAULT))
            prepareState(
                isFeatureEnabled = false,
                isDeviceUnlocked = true,
                initialSceneKey = SceneKey.Lockscreen,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
            assertThat(isVisible).isTrue()

            underTest.start()
            assertThat(isVisible).isTrue()

            sceneInteractor.setCurrentScene(
                SceneContainerNames.SYSTEM_UI_DEFAULT,
                SceneModel(SceneKey.Gone)
            )
            assertThat(isVisible).isTrue()

            sceneInteractor.setCurrentScene(
                SceneContainerNames.SYSTEM_UI_DEFAULT,
                SceneModel(SceneKey.Shade)
            )
            assertThat(isVisible).isTrue()
        }

    @Test
    fun switchToLockscreenWhenDeviceLocks_featureEnabled() =
        testScope.runTest {
            val currentSceneKey by
                collectLastValue(
                    sceneInteractor.currentScene(SceneContainerNames.SYSTEM_UI_DEFAULT).map {
                        it.key
                    }
                )
            prepareState(
                isFeatureEnabled = true,
                isDeviceUnlocked = true,
                initialSceneKey = SceneKey.Gone,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Gone)
            underTest.start()

            authenticationRepository.setUnlocked(false)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
        }

    @Test
    fun switchToLockscreenWhenDeviceLocks_featureDisabled() =
        testScope.runTest {
            val currentSceneKey by
                collectLastValue(
                    sceneInteractor.currentScene(SceneContainerNames.SYSTEM_UI_DEFAULT).map {
                        it.key
                    }
                )
            prepareState(
                isFeatureEnabled = false,
                isDeviceUnlocked = false,
                initialSceneKey = SceneKey.Gone,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Gone)
            underTest.start()

            authenticationRepository.setUnlocked(false)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Gone)
        }

    @Test
    fun switchFromBouncerToGoneWhenDeviceUnlocked_featureEnabled() =
        testScope.runTest {
            val currentSceneKey by
                collectLastValue(
                    sceneInteractor.currentScene(SceneContainerNames.SYSTEM_UI_DEFAULT).map {
                        it.key
                    }
                )
            prepareState(
                isFeatureEnabled = true,
                isDeviceUnlocked = false,
                initialSceneKey = SceneKey.Bouncer,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Bouncer)
            underTest.start()

            authenticationRepository.setUnlocked(true)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Gone)
        }

    @Test
    fun switchFromBouncerToGoneWhenDeviceUnlocked_featureDisabled() =
        testScope.runTest {
            val currentSceneKey by
                collectLastValue(
                    sceneInteractor.currentScene(SceneContainerNames.SYSTEM_UI_DEFAULT).map {
                        it.key
                    }
                )
            prepareState(
                isFeatureEnabled = false,
                isDeviceUnlocked = false,
                initialSceneKey = SceneKey.Bouncer,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Bouncer)
            underTest.start()

            authenticationRepository.setUnlocked(true)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Bouncer)
        }

    @Test
    fun switchFromLockscreenToGoneWhenDeviceUnlocksWithBypassOn_featureOn_bypassOn() =
        testScope.runTest {
            val currentSceneKey by
                collectLastValue(
                    sceneInteractor.currentScene(SceneContainerNames.SYSTEM_UI_DEFAULT).map {
                        it.key
                    }
                )
            prepareState(
                isFeatureEnabled = true,
                isBypassEnabled = true,
                initialSceneKey = SceneKey.Lockscreen,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
            underTest.start()

            authenticationRepository.setUnlocked(true)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Gone)
        }

    @Test
    fun switchFromLockscreenToGoneWhenDeviceUnlocksWithBypassOn_featureOn_bypassOff() =
        testScope.runTest {
            val currentSceneKey by
                collectLastValue(
                    sceneInteractor.currentScene(SceneContainerNames.SYSTEM_UI_DEFAULT).map {
                        it.key
                    }
                )
            prepareState(
                isFeatureEnabled = true,
                isBypassEnabled = false,
                initialSceneKey = SceneKey.Lockscreen,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
            underTest.start()

            authenticationRepository.setUnlocked(true)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
        }

    @Test
    fun switchFromLockscreenToGoneWhenDeviceUnlocksWithBypassOn_featureOff_bypassOn() =
        testScope.runTest {
            val currentSceneKey by
                collectLastValue(
                    sceneInteractor.currentScene(SceneContainerNames.SYSTEM_UI_DEFAULT).map {
                        it.key
                    }
                )
            prepareState(
                isFeatureEnabled = false,
                isBypassEnabled = true,
                initialSceneKey = SceneKey.Lockscreen,
            )
            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
            underTest.start()

            authenticationRepository.setUnlocked(true)

            assertThat(currentSceneKey).isEqualTo(SceneKey.Lockscreen)
        }

    private fun prepareState(
        isFeatureEnabled: Boolean = true,
        isDeviceUnlocked: Boolean = false,
        isBypassEnabled: Boolean = false,
        initialSceneKey: SceneKey? = null,
    ) {
        featureFlags.set(Flags.SCENE_CONTAINER, isFeatureEnabled)
        authenticationRepository.setUnlocked(isDeviceUnlocked)
        authenticationRepository.setBypassEnabled(isBypassEnabled)
        initialSceneKey?.let {
            sceneInteractor.setCurrentScene(SceneContainerNames.SYSTEM_UI_DEFAULT, SceneModel(it))
        }
    }
}
