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

package com.android.systemui.communal.domain.interactor

import android.provider.Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED
import android.provider.Settings.Secure.HUB_MODE_TUTORIAL_NOT_STARTED
import android.provider.Settings.Secure.HUB_MODE_TUTORIAL_STARTED
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.FakeCommunalRepository
import com.android.systemui.communal.data.repository.FakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.FakeCommunalWidgetRepository
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.FakeSceneContainerFlags
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalTutorialInteractorTest : SysuiTestCase() {

    @Mock private lateinit var userTracker: UserTracker

    private lateinit var testScope: TestScope
    private lateinit var underTest: CommunalTutorialInteractor
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var keyguardInteractor: KeyguardInteractor
    private lateinit var communalTutorialRepository: FakeCommunalTutorialRepository
    private lateinit var sceneContainerFlags: FakeSceneContainerFlags
    private lateinit var communalInteractor: CommunalInteractor
    private lateinit var communalRepository: FakeCommunalRepository

    private val utils = SceneTestUtils(this)
    private lateinit var sceneInteractor: SceneInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        sceneInteractor = utils.sceneInteractor()
        testScope = utils.testScope
        sceneContainerFlags = utils.sceneContainerFlags.apply { enabled = false }
        communalRepository = FakeCommunalRepository(isCommunalEnabled = true)
        communalInteractor = CommunalInteractor(communalRepository, FakeCommunalWidgetRepository())

        val withDeps = KeyguardInteractorFactory.create()
        keyguardInteractor = withDeps.keyguardInteractor
        keyguardRepository = withDeps.repository
        communalTutorialRepository = FakeCommunalTutorialRepository()

        underTest =
            CommunalTutorialInteractor(
                scope = testScope.backgroundScope,
                communalTutorialRepository = communalTutorialRepository,
                keyguardInteractor = keyguardInteractor,
                communalInteractor = communalInteractor,
                sceneContainerFlags = sceneContainerFlags,
                sceneInteractor = sceneInteractor,
            )

        whenever(userTracker.userHandle).thenReturn(mock())
    }

    @Test
    fun tutorialUnavailable_whenKeyguardNotVisible() =
        testScope.runTest {
            val isTutorialAvailable by collectLastValue(underTest.isTutorialAvailable)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_NOT_STARTED)
            keyguardRepository.setKeyguardShowing(false)
            assertThat(isTutorialAvailable).isFalse()
        }

    @Test
    fun tutorialUnavailable_whenTutorialIsCompleted() =
        testScope.runTest {
            val isTutorialAvailable by collectLastValue(underTest.isTutorialAvailable)
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            communalInteractor.onSceneChanged(CommunalSceneKey.Blank)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)
            assertThat(isTutorialAvailable).isFalse()
        }

    @Test
    fun tutorialAvailable_whenTutorialNotStarted() =
        testScope.runTest {
            val isTutorialAvailable by collectLastValue(underTest.isTutorialAvailable)
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            communalInteractor.onSceneChanged(CommunalSceneKey.Blank)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_NOT_STARTED)
            assertThat(isTutorialAvailable).isTrue()
        }

    @Test
    fun tutorialAvailable_whenTutorialIsStarted() =
        testScope.runTest {
            val isTutorialAvailable by collectLastValue(underTest.isTutorialAvailable)
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            communalInteractor.onSceneChanged(CommunalSceneKey.Communal)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_STARTED)
            assertThat(isTutorialAvailable).isTrue()
        }

    /* Testing tutorial states with transitions when flexiglass off */
    @Test
    fun tutorialState_notStartedAndCommunalSceneShowing_tutorialStarted() =
        testScope.runTest {
            val tutorialSettingState by
                collectLastValue(communalTutorialRepository.tutorialSettingState)
            val currentScene by collectLastValue(communalInteractor.desiredScene)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_NOT_STARTED)

            communalInteractor.onSceneChanged(CommunalSceneKey.Communal)

            assertThat(currentScene).isEqualTo(CommunalSceneKey.Communal)
            assertThat(tutorialSettingState).isEqualTo(HUB_MODE_TUTORIAL_STARTED)
        }

    @Test
    fun tutorialState_startedAndCommunalSceneShowing_stateWillNotUpdate() =
        testScope.runTest {
            val tutorialSettingState by
                collectLastValue(communalTutorialRepository.tutorialSettingState)
            val currentScene by collectLastValue(communalInteractor.desiredScene)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_STARTED)

            communalInteractor.onSceneChanged(CommunalSceneKey.Communal)

            assertThat(currentScene).isEqualTo(CommunalSceneKey.Communal)
            assertThat(tutorialSettingState).isEqualTo(HUB_MODE_TUTORIAL_STARTED)
        }

    @Test
    fun tutorialState_completedAndCommunalSceneShowing_stateWillNotUpdate() =
        testScope.runTest {
            val tutorialSettingState by
                collectLastValue(communalTutorialRepository.tutorialSettingState)
            val currentScene by collectLastValue(communalInteractor.desiredScene)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            communalInteractor.onSceneChanged(CommunalSceneKey.Communal)

            assertThat(currentScene).isEqualTo(CommunalSceneKey.Communal)
            assertThat(tutorialSettingState).isEqualTo(HUB_MODE_TUTORIAL_COMPLETED)
        }

    @Test
    fun tutorialState_notStartedAndCommunalSceneNotShowing_stateWillNotUpdate() =
        testScope.runTest {
            val tutorialSettingState by
                collectLastValue(communalTutorialRepository.tutorialSettingState)
            val currentScene by collectLastValue(communalInteractor.desiredScene)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_NOT_STARTED)

            communalInteractor.onSceneChanged(CommunalSceneKey.Blank)

            assertThat(currentScene).isEqualTo(CommunalSceneKey.Blank)
            assertThat(tutorialSettingState).isEqualTo(HUB_MODE_TUTORIAL_NOT_STARTED)
        }

    @Test
    fun tutorialState_startedAndCommunalSceneNotShowing_tutorialCompleted() =
        testScope.runTest {
            val tutorialSettingState by
                collectLastValue(communalTutorialRepository.tutorialSettingState)
            val currentScene by collectLastValue(communalInteractor.desiredScene)
            communalInteractor.onSceneChanged(CommunalSceneKey.Communal)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_STARTED)

            communalInteractor.onSceneChanged(CommunalSceneKey.Blank)

            assertThat(currentScene).isEqualTo(CommunalSceneKey.Blank)
            assertThat(tutorialSettingState).isEqualTo(HUB_MODE_TUTORIAL_COMPLETED)
        }

    @Test
    fun tutorialState_completedAndCommunalSceneNotShowing_stateWillNotUpdate() =
        testScope.runTest {
            val tutorialSettingState by
                collectLastValue(communalTutorialRepository.tutorialSettingState)
            val currentScene by collectLastValue(communalInteractor.desiredScene)
            communalInteractor.onSceneChanged(CommunalSceneKey.Communal)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            communalInteractor.onSceneChanged(CommunalSceneKey.Blank)

            assertThat(currentScene).isEqualTo(CommunalSceneKey.Blank)
            assertThat(tutorialSettingState).isEqualTo(HUB_MODE_TUTORIAL_COMPLETED)
        }

    /* Testing tutorial states with transitions when flexiglass on */
    @Test
    fun tutorialState_notStartedCommunalSceneShowingAndFlexiglassOn_tutorialStarted() =
        testScope.runTest {
            sceneContainerFlags.enabled = true
            val tutorialSettingState by
                collectLastValue(communalTutorialRepository.tutorialSettingState)
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_NOT_STARTED)

            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Communal), "reason")

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Communal))
            assertThat(tutorialSettingState).isEqualTo(HUB_MODE_TUTORIAL_STARTED)
        }

    @Test
    fun tutorialState_startedCommunalSceneShowingAndFlexiglassOn_stateWillNotUpdate() =
        testScope.runTest {
            sceneContainerFlags.enabled = true
            val tutorialSettingState by
                collectLastValue(communalTutorialRepository.tutorialSettingState)
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_STARTED)

            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Communal), "reason")

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Communal))
            assertThat(tutorialSettingState).isEqualTo(HUB_MODE_TUTORIAL_STARTED)
        }

    @Test
    fun tutorialState_completedCommunalSceneShowingAndFlexiglassOn_stateWillNotUpdate() =
        testScope.runTest {
            sceneContainerFlags.enabled = true
            val tutorialSettingState by
                collectLastValue(communalTutorialRepository.tutorialSettingState)
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Communal), "reason")

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Communal))
            assertThat(tutorialSettingState).isEqualTo(HUB_MODE_TUTORIAL_COMPLETED)
        }

    @Test
    fun tutorialState_notStartedCommunalSceneNotShowingAndFlexiglassOn_stateWillNotUpdate() =
        testScope.runTest {
            sceneContainerFlags.enabled = true
            val tutorialSettingState by
                collectLastValue(communalTutorialRepository.tutorialSettingState)
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_NOT_STARTED)

            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Lockscreen), "reason")

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Lockscreen))
            assertThat(tutorialSettingState).isEqualTo(HUB_MODE_TUTORIAL_NOT_STARTED)
        }

    @Test
    fun tutorialState_startedCommunalSceneNotShowingAndFlexiglassOn_tutorialCompleted() =
        testScope.runTest {
            sceneContainerFlags.enabled = true
            val tutorialSettingState by
                collectLastValue(communalTutorialRepository.tutorialSettingState)
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Communal), "reason")
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_STARTED)

            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Lockscreen), "reason")

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Lockscreen))
            assertThat(tutorialSettingState).isEqualTo(HUB_MODE_TUTORIAL_COMPLETED)
        }

    @Test
    fun tutorialState_completedCommunalSceneNotShowingAndFlexiglassOn_stateWillNotUpdate() =
        testScope.runTest {
            sceneContainerFlags.enabled = true
            val tutorialSettingState by
                collectLastValue(communalTutorialRepository.tutorialSettingState)
            val currentScene by collectLastValue(sceneInteractor.desiredScene)
            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Communal), "reason")
            communalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            sceneInteractor.onSceneChanged(SceneModel(SceneKey.Lockscreen), "reason")

            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Lockscreen))
            assertThat(tutorialSettingState).isEqualTo(HUB_MODE_TUTORIAL_COMPLETED)
        }
}
