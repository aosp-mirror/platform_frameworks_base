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

package com.android.systemui.keyguard.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardClockRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.keyguardClockRepository
import com.android.systemui.keyguard.data.repository.keyguardRepository
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.DozeTransitionModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.data.repository.mediaFilterRepository
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.data.repository.setActiveNotifs
import com.android.systemui.statusbar.notification.stack.data.repository.headsUpNotificationRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardClockInteractorTest : SysuiTestCase() {
    private lateinit var kosmos: Kosmos
    private lateinit var underTest: KeyguardClockInteractor
    private lateinit var testScope: TestScope

    @Before
    fun setup() {
        kosmos = testKosmos()
        testScope = kosmos.testScope
        underTest = kosmos.keyguardClockInteractor
    }

    @Test
    @DisableSceneContainer
    fun clockSize_sceneContainerFlagOff_basedOnRepository() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockSize)
            kosmos.keyguardClockRepository.setClockSize(ClockSize.LARGE)
            assertThat(value).isEqualTo(ClockSize.LARGE)

            kosmos.keyguardClockRepository.setClockSize(ClockSize.SMALL)
            assertThat(value).isEqualTo(ClockSize.SMALL)
        }

    @Test
    @EnableSceneContainer
    fun clockSize_forceSmallClock_SMALL() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockSize)
            kosmos.fakeKeyguardClockRepository.setShouldForceSmallClock(true)
            kosmos.fakeFeatureFlagsClassic.set(Flags.LOCKSCREEN_ENABLE_LANDSCAPE, true)
            kosmos.fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.AOD,
                KeyguardState.LOCKSCREEN,
            )
            assertThat(value).isEqualTo(ClockSize.SMALL)
        }

    @Test
    @EnableSceneContainer
    fun clockSize_SceneContainerFlagOn_shadeModeSingle_hasNotifs_SMALL() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockSize)
            kosmos.shadeRepository.setShadeLayoutWide(false)
            kosmos.activeNotificationListRepository.setActiveNotifs(1)
            assertThat(value).isEqualTo(ClockSize.SMALL)
        }

    @Test
    @EnableSceneContainer
    fun clockSize_SceneContainerFlagOn_shadeModeSingle_hasMedia_SMALL() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockSize)
            kosmos.shadeRepository.setShadeLayoutWide(false)
            val userMedia = MediaData().copy(active = true)
            kosmos.mediaFilterRepository.addSelectedUserMediaEntry(userMedia)
            assertThat(value).isEqualTo(ClockSize.SMALL)
        }

    @Test
    @EnableSceneContainer
    fun clockSize_SceneContainerFlagOn_shadeModeSplit_isMediaVisible_SMALL() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockSize)
            val userMedia = MediaData().copy(active = true)
            kosmos.shadeRepository.setShadeLayoutWide(true)
            kosmos.mediaFilterRepository.addSelectedUserMediaEntry(userMedia)
            kosmos.keyguardRepository.setIsDozing(false)
            assertThat(value).isEqualTo(ClockSize.SMALL)
        }

    @Test
    @EnableSceneContainer
    fun clockSize_SceneContainerFlagOn_shadeModeSplit_noMedia_LARGE() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockSize)
            kosmos.shadeRepository.setShadeLayoutWide(true)
            kosmos.keyguardRepository.setIsDozing(false)
            assertThat(value).isEqualTo(ClockSize.LARGE)
        }

    @Test
    @EnableSceneContainer
    fun clockSize_SceneContainerFlagOn_shadeModeSplit_isDozing_LARGE() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockSize)
            val userMedia = MediaData().copy(active = true)
            kosmos.shadeRepository.setShadeLayoutWide(true)
            kosmos.mediaFilterRepository.addSelectedUserMediaEntry(userMedia)
            kosmos.keyguardRepository.setIsDozing(true)
            assertThat(value).isEqualTo(ClockSize.LARGE)
        }

    @Test
    @EnableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOn_notSplitMode_true() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.shadeRepository.setShadeLayoutWide(false)
            assertThat(value).isTrue()
        }

    @Test
    @EnableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOn_splitMode_noActiveNotifications_true() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.shadeRepository.setShadeLayoutWide(true)
            kosmos.activeNotificationListRepository.setActiveNotifs(0)
            assertThat(value).isTrue()
        }

    @Test
    @EnableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOn_splitMode_hasPulsingNotifications_false() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.shadeRepository.setShadeLayoutWide(true)
            kosmos.activeNotificationListRepository.setActiveNotifs(1)
            kosmos.headsUpNotificationRepository.isHeadsUpAnimatingAway.value = true
            kosmos.keyguardRepository.setIsDozing(true)
            assertThat(value).isFalse()
        }

    @Test
    @EnableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOn_splitMode_onAod_true() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.shadeRepository.setShadeLayoutWide(true)
            kosmos.activeNotificationListRepository.setActiveNotifs(1)
            kosmos.fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.LOCKSCREEN,
                KeyguardState.AOD,
            )
            assertThat(value).isTrue()
        }

    @Test
    @EnableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOn_splitMode_offAod_false() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.shadeRepository.setShadeLayoutWide(true)
            kosmos.activeNotificationListRepository.setActiveNotifs(1)
            kosmos.fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.AOD,
                KeyguardState.LOCKSCREEN,
            )
            assertThat(value).isFalse()
        }

    @Test
    @DisableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOff_notSplitMode_true() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.shadeRepository.setShadeLayoutWide(false)
            assertThat(value).isTrue()
        }

    @Test
    @DisableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOff_splitMode_lockscreen_withNotifs_false() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.shadeRepository.setShadeLayoutWide(true)
            kosmos.activeNotificationListRepository.setActiveNotifs(1)
            kosmos.fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.AOD,
                KeyguardState.LOCKSCREEN,
            )
            assertThat(value).isFalse()
        }

    @Test
    @DisableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOff_splitMode_lockscreen_withoutNotifs_true() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.shadeRepository.setShadeLayoutWide(true)
            kosmos.activeNotificationListRepository.setActiveNotifs(0)
            kosmos.fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.AOD,
                KeyguardState.LOCKSCREEN,
            )
            assertThat(value).isTrue()
        }

    @Test
    @DisableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOff_splitMode_LsToAod_withNotifs_true() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.shadeRepository.setShadeLayoutWide(true)
            kosmos.activeNotificationListRepository.setActiveNotifs(1)
            kosmos.fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.OFF,
                KeyguardState.LOCKSCREEN,
            )
            assertThat(value).isFalse()
            kosmos.fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.LOCKSCREEN,
                KeyguardState.AOD,
            )
            assertThat(value).isTrue()
        }

    @Test
    @DisableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOff_splitMode_AodToLs_withNotifs_false() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.shadeRepository.setShadeLayoutWide(true)
            kosmos.activeNotificationListRepository.setActiveNotifs(1)
            kosmos.fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.LOCKSCREEN,
                KeyguardState.AOD,
            )
            assertThat(value).isTrue()
            kosmos.fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.AOD,
                KeyguardState.LOCKSCREEN,
            )
            assertThat(value).isFalse()
        }

    @Test
    @DisableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOff_splitMode_Aod_withPulsingNotifs_false() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.shadeRepository.setShadeLayoutWide(true)
            kosmos.fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.LOCKSCREEN,
                KeyguardState.AOD,
            )
            assertThat(value).isTrue()
            kosmos.fakeKeyguardRepository.setDozeTransitionModel(
                DozeTransitionModel(
                    from = DozeStateModel.DOZE_AOD,
                    to = DozeStateModel.DOZE_PULSING,
                )
            )
            assertThat(value).isFalse()
        }

    @Test
    @DisableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOff_splitMode_LStoGone_withoutNotifs_true() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.shadeRepository.setShadeLayoutWide(true)
            kosmos.activeNotificationListRepository.setActiveNotifs(0)
            kosmos.fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.OFF,
                KeyguardState.LOCKSCREEN,
            )
            assertThat(value).isTrue()
            kosmos.fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.LOCKSCREEN,
                KeyguardState.GONE,
            )
            kosmos.activeNotificationListRepository.setActiveNotifs(1)
            assertThat(value).isTrue()
        }

    @Test
    @DisableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOff_splitMode_AodOn_GoneToAOD() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.AOD,
                KeyguardState.LOCKSCREEN,
            )
            kosmos.shadeRepository.setShadeLayoutWide(true)
            kosmos.activeNotificationListRepository.setActiveNotifs(0)
            assertThat(value).isTrue()

            kosmos.fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.LOCKSCREEN,
                KeyguardState.GONE,
            )
            kosmos.activeNotificationListRepository.setActiveNotifs(1)
            assertThat(value).isTrue()

            kosmos.fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.GONE,
                KeyguardState.AOD,
            )
            assertThat(value).isTrue()
        }

    @Test
    @DisableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOff_splitMode_AodOff_GoneToDoze() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.shadeRepository.setShadeLayoutWide(true)
            kosmos.fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.DOZING,
                KeyguardState.LOCKSCREEN,
            )
            kosmos.activeNotificationListRepository.setActiveNotifs(0)
            assertThat(value).isTrue()

            kosmos.fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.LOCKSCREEN,
                KeyguardState.GONE,
            )
            kosmos.activeNotificationListRepository.setActiveNotifs(1)
            assertThat(value).isTrue()

            kosmos.fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.GONE,
                KeyguardState.DOZING,
            )
            kosmos.activeNotificationListRepository.setActiveNotifs(1)
            assertThat(value).isTrue()

            kosmos.fakeKeyguardTransitionRepository.transitionTo(
                KeyguardState.DOZING,
                KeyguardState.LOCKSCREEN,
            )
            kosmos.activeNotificationListRepository.setActiveNotifs(0)
            assertThat(value).isTrue()
        }
}
