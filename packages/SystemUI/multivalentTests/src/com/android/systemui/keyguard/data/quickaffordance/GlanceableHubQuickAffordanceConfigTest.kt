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
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.communalSceneRepository
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.domain.interactor.communalSettingsInteractor
import com.android.systemui.communal.domain.interactor.setCommunalV2Enabled
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.data.repository.sceneContainerRepository
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@EnableFlags(Flags.FLAG_GLANCEABLE_HUB_SHORTCUT_BUTTON, Flags.FLAG_GLANCEABLE_HUB_V2)
@RunWith(ParameterizedAndroidJunit4::class)
class GlanceableHubQuickAffordanceConfigTest(flags: FlagsParameterization?) : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var underTest: GlanceableHubQuickAffordanceConfig

    init {
        mSetFlagsRule.setFlagsParameterization(flags!!)
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest =
            GlanceableHubQuickAffordanceConfig(
                context = context,
                communalInteractor = kosmos.communalInteractor,
                communalSceneRepository = kosmos.communalSceneRepository,
                communalSettingsInteractor = kosmos.communalSettingsInteractor,
                sceneInteractor = kosmos.sceneInteractor,
            )
    }

    @Test
    fun lockscreenState_whenGlanceableHubEnabled_returnsVisible() =
        testScope.runTest {
            kosmos.setCommunalV2Enabled(true)
            runCurrent()

            val lockScreenState by collectLastValue(underTest.lockScreenState)

            assertThat(lockScreenState)
                .isInstanceOf(KeyguardQuickAffordanceConfig.LockScreenState.Visible::class.java)
        }

    @Test
    fun lockscreenState_whenGlanceableHubDisabled_returnsHidden() =
        testScope.runTest {
            kosmos.setCommunalV2Enabled(false)
            val lockScreenState by collectLastValue(underTest.lockScreenState)
            runCurrent()

            assertThat(lockScreenState)
                .isEqualTo(KeyguardQuickAffordanceConfig.LockScreenState.Hidden)
        }

    @Test
    fun pickerScreenState_whenGlanceableHubEnabled_returnsDefault() =
        testScope.runTest {
            kosmos.setCommunalV2Enabled(true)
            runCurrent()

            assertThat(underTest.getPickerScreenState())
                .isEqualTo(KeyguardQuickAffordanceConfig.PickerScreenState.Default())
        }

    @Test
    fun pickerScreenState_whenGlanceableHubDisabled_returnsDisabled() =
        testScope.runTest {
            kosmos.setCommunalV2Enabled(false)
            runCurrent()

            assertThat(
                underTest.getPickerScreenState()
                    is KeyguardQuickAffordanceConfig.PickerScreenState.Disabled
            )
        }

    @Test
    @DisableFlags(Flags.FLAG_SCENE_CONTAINER)
    fun onTriggered_changesSceneToCommunal() =
        testScope.runTest {
            underTest.onTriggered(expandable = null)
            runCurrent()

            assertThat(kosmos.communalSceneRepository.currentScene.value)
                .isEqualTo(CommunalScenes.Communal)
        }

    @Test
    @EnableFlags(Flags.FLAG_SCENE_CONTAINER)
    fun testTransitionToGlanceableHub_sceneContainer() =
        testScope.runTest {
            underTest.onTriggered(expandable = null)
            runCurrent()

            assertThat(kosmos.sceneContainerRepository.currentScene.value)
                .isEqualTo(Scenes.Communal)
        }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf(
                    Flags.FLAG_GLANCEABLE_HUB_SHORTCUT_BUTTON,
                    Flags.FLAG_GLANCEABLE_HUB_V2,
                )
                .andSceneContainer()
        }
    }
}
