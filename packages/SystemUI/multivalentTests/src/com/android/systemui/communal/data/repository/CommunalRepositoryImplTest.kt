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

package com.android.systemui.communal.data.repository

import android.content.pm.UserInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_COMMUNAL_HUB
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.communal.shared.model.ObservableCommunalTransitionState
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.data.repository.sceneContainerRepository
import com.android.systemui.scene.shared.flag.fakeSceneContainerFlags
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalRepositoryImplTest : SysuiTestCase() {
    private lateinit var underTest: CommunalRepositoryImpl

    private lateinit var secureSettings: FakeSettings
    private lateinit var userRepository: FakeUserRepository

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val sceneContainerRepository = kosmos.sceneContainerRepository

    @Before
    fun setUp() {
        secureSettings = FakeSettings()
        userRepository = kosmos.fakeUserRepository

        val listOfUserInfo = listOf(MAIN_USER_INFO)
        userRepository.setUserInfos(listOfUserInfo)

        kosmos.fakeFeatureFlagsClassic.apply { set(Flags.COMMUNAL_SERVICE_ENABLED, true) }
        mSetFlagsRule.enableFlags(FLAG_COMMUNAL_HUB)

        underTest = createRepositoryImpl(false)
    }

    private fun createRepositoryImpl(sceneContainerEnabled: Boolean): CommunalRepositoryImpl {
        return CommunalRepositoryImpl(
            testScope.backgroundScope,
            testScope.backgroundScope,
            kosmos.testDispatcher,
            kosmos.fakeFeatureFlagsClassic,
            kosmos.fakeSceneContainerFlags.apply { enabled = sceneContainerEnabled },
            sceneContainerRepository,
            kosmos.fakeUserRepository,
            secureSettings,
        )
    }

    @Test
    fun isCommunalShowing_sceneContainerDisabled_onCommunalScene_true() =
        testScope.runTest {
            underTest.setDesiredScene(CommunalSceneKey.Communal)

            val isCommunalHubShowing by collectLastValue(underTest.isCommunalHubShowing)
            assertThat(isCommunalHubShowing).isTrue()
        }

    @Test
    fun isCommunalShowing_sceneContainerDisabled_onBlankScene_false() =
        testScope.runTest {
            underTest.setDesiredScene(CommunalSceneKey.Blank)

            val isCommunalHubShowing by collectLastValue(underTest.isCommunalHubShowing)
            assertThat(isCommunalHubShowing).isFalse()
        }

    @Test
    fun isCommunalShowing_sceneContainerEnabled_onCommunalScene_true() =
        testScope.runTest {
            underTest = createRepositoryImpl(true)

            sceneContainerRepository.setDesiredScene(SceneModel(key = SceneKey.Communal))

            val isCommunalHubShowing by collectLastValue(underTest.isCommunalHubShowing)
            assertThat(isCommunalHubShowing).isTrue()
        }

    @Test
    fun isCommunalShowing_sceneContainerEnabled_onLockscreenScene_false() =
        testScope.runTest {
            underTest = createRepositoryImpl(true)

            sceneContainerRepository.setDesiredScene(SceneModel(key = SceneKey.Lockscreen))

            val isCommunalHubShowing by collectLastValue(underTest.isCommunalHubShowing)
            assertThat(isCommunalHubShowing).isFalse()
        }

    @Test
    fun transitionState_idleByDefault() =
        testScope.runTest {
            val transitionState by collectLastValue(underTest.transitionState)
            assertThat(transitionState)
                .isEqualTo(ObservableCommunalTransitionState.Idle(CommunalSceneKey.DEFAULT))
        }

    @Test
    fun transitionState_setTransitionState_returnsNewValue() =
        testScope.runTest {
            val expectedSceneKey = CommunalSceneKey.Communal
            underTest.setTransitionState(
                flowOf(ObservableCommunalTransitionState.Idle(expectedSceneKey))
            )

            val transitionState by collectLastValue(underTest.transitionState)
            assertThat(transitionState)
                .isEqualTo(ObservableCommunalTransitionState.Idle(expectedSceneKey))
        }

    @Test
    fun transitionState_setNullTransitionState_returnsDefaultValue() =
        testScope.runTest {
            // Set a value for the transition state flow.
            underTest.setTransitionState(
                flowOf(ObservableCommunalTransitionState.Idle(CommunalSceneKey.Communal))
            )

            // Set the transition state flow back to null.
            underTest.setTransitionState(null)

            // Flow returns default scene key.
            val transitionState by collectLastValue(underTest.transitionState)
            assertThat(transitionState)
                .isEqualTo(ObservableCommunalTransitionState.Idle(CommunalSceneKey.DEFAULT))
        }

    @Test
    fun communalEnabledState_false_whenGlanceableHubSettingFalse() =
        testScope.runTest {
            userRepository.setSelectedUserInfo(MAIN_USER_INFO)
            secureSettings.putIntForUser(GLANCEABLE_HUB_ENABLED, 0, MAIN_USER_INFO.id)

            val communalEnabled by collectLastValue(underTest.communalEnabledState)
            assertThat(communalEnabled).isFalse()
        }

    @Test
    fun communalEnabledState_true_whenGlanceableHubSettingTrue() =
        testScope.runTest {
            userRepository.setSelectedUserInfo(MAIN_USER_INFO)
            secureSettings.putIntForUser(GLANCEABLE_HUB_ENABLED, 1, MAIN_USER_INFO.id)

            val communalEnabled by collectLastValue(underTest.communalEnabledState)
            assertThat(communalEnabled).isTrue()
        }

    companion object {
        private const val GLANCEABLE_HUB_ENABLED = "glanceable_hub_enabled"
        private val MAIN_USER_INFO = UserInfo(0, "primary", UserInfo.FLAG_MAIN)
    }
}
