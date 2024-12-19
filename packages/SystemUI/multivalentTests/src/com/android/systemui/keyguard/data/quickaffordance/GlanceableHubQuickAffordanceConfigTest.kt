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
import com.android.systemui.communal.domain.interactor.setCommunalV2Available
import com.android.systemui.communal.domain.interactor.setCommunalV2Enabled
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.flags.parameterizeSceneContainerFlag
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.scene.data.repository.sceneContainerRepository
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@EnableFlags(Flags.FLAG_GLANCEABLE_HUB_V2)
@RunWith(ParameterizedAndroidJunit4::class)
class GlanceableHubQuickAffordanceConfigTest(flags: FlagsParameterization?) : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val Kosmos.underTest by Kosmos.Fixture { glanceableHubQuickAffordanceConfig }

    init {
        mSetFlagsRule.setFlagsParameterization(flags!!)
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        // Access the class immediately so that flows are instantiated.
        // GlanceableHubQuickAffordanceConfig accesses StateFlow.value directly so we need the flows
        // to start flowing before runCurrent is called in the tests.
        kosmos.underTest
    }

    @Test
    fun lockscreenState_whenGlanceableHubEnabled_returnsVisible() =
        kosmos.runTest {
            kosmos.setCommunalV2Available(true)
            runCurrent()

            val lockScreenState by collectLastValue(underTest.lockScreenState)

            assertThat(lockScreenState)
                .isInstanceOf(KeyguardQuickAffordanceConfig.LockScreenState.Visible::class.java)
        }

    @Test
    fun lockscreenState_whenGlanceableHubDisabled_returnsHidden() =
        kosmos.runTest {
            setCommunalV2Enabled(false)
            val lockScreenState by collectLastValue(underTest.lockScreenState)
            runCurrent()

            assertThat(lockScreenState)
                .isEqualTo(KeyguardQuickAffordanceConfig.LockScreenState.Hidden)
        }

    @Test
    fun lockscreenState_whenGlanceableHubNotAvailable_returnsHidden() =
        kosmos.runTest {
            // Hub is enabled, but not available.
            setCommunalV2Enabled(true)
            fakeKeyguardRepository.setKeyguardShowing(false)
            val lockScreenState by collectLastValue(underTest.lockScreenState)
            runCurrent()

            assertThat(lockScreenState)
                .isEqualTo(KeyguardQuickAffordanceConfig.LockScreenState.Hidden)
        }

    @Test
    fun pickerScreenState_whenGlanceableHubEnabled_returnsDefault() =
        kosmos.runTest {
            setCommunalV2Enabled(true)
            runCurrent()

            assertThat(underTest.getPickerScreenState())
                .isEqualTo(KeyguardQuickAffordanceConfig.PickerScreenState.Default())
        }

    @Test
    fun pickerScreenState_whenGlanceableHubDisabled_returnsDisabled() =
        kosmos.runTest {
            setCommunalV2Enabled(false)
            runCurrent()

            assertThat(
                underTest.getPickerScreenState()
                    is KeyguardQuickAffordanceConfig.PickerScreenState.Disabled
            )
        }

    @Test
    @DisableFlags(Flags.FLAG_SCENE_CONTAINER)
    fun onTriggered_changesSceneToCommunal() =
        kosmos.runTest {
            underTest.onTriggered(expandable = null)
            runCurrent()

            assertThat(kosmos.communalSceneRepository.currentScene.value)
                .isEqualTo(CommunalScenes.Communal)
        }

    @Test
    @EnableFlags(Flags.FLAG_SCENE_CONTAINER)
    fun testTransitionToGlanceableHub_sceneContainer() =
        kosmos.runTest {
            underTest.onTriggered(expandable = null)
            runCurrent()

            assertThat(kosmos.sceneContainerRepository.currentScene.value)
                .isEqualTo(Scenes.Communal)
        }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return parameterizeSceneContainerFlag()
        }
    }
}
