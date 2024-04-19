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
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.keyguardClockRepository
import com.android.systemui.keyguard.data.repository.keyguardRepository
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.data.repository.mediaFilterRepository
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.shared.model.ShadeMode
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
    @DisableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOff_basedOnRepository() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.keyguardInteractor.setClockShouldBeCentered(true)
            assertThat(value).isEqualTo(true)

            kosmos.keyguardInteractor.setClockShouldBeCentered(false)
            assertThat(value).isEqualTo(false)
        }

    @Test
    @EnableSceneContainer
    fun clockSize_forceSmallClock_SMALL() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockSize)
            kosmos.fakeKeyguardClockRepository.setShouldForceSmallClock(true)
            kosmos.fakeFeatureFlagsClassic.set(Flags.LOCKSCREEN_ENABLE_LANDSCAPE, true)
            transitionTo(KeyguardState.AOD, KeyguardState.LOCKSCREEN)
            assertThat(value).isEqualTo(ClockSize.SMALL)
        }

    @Test
    @EnableSceneContainer
    fun clockSize_SceneContainerFlagOn_shadeModeSingle_hasNotifs_SMALL() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockSize)
            kosmos.shadeRepository.setShadeMode(ShadeMode.Single)
            kosmos.activeNotificationListRepository.setActiveNotifs(1)
            assertThat(value).isEqualTo(ClockSize.SMALL)
        }

    @Test
    @EnableSceneContainer
    fun clockSize_SceneContainerFlagOn_shadeModeSingle_hasMedia_SMALL() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockSize)
            kosmos.shadeRepository.setShadeMode(ShadeMode.Single)
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
            kosmos.shadeRepository.setShadeMode(ShadeMode.Split)
            kosmos.mediaFilterRepository.addSelectedUserMediaEntry(userMedia)
            kosmos.keyguardRepository.setIsDozing(false)
            assertThat(value).isEqualTo(ClockSize.SMALL)
        }

    @Test
    @EnableSceneContainer
    fun clockSize_SceneContainerFlagOn_shadeModeSplit_noMedia_LARGE() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockSize)
            kosmos.shadeRepository.setShadeMode(ShadeMode.Split)
            kosmos.keyguardRepository.setIsDozing(false)
            assertThat(value).isEqualTo(ClockSize.LARGE)
        }

    @Test
    @EnableSceneContainer
    fun clockSize_SceneContainerFlagOn_shadeModeSplit_isDozing_LARGE() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockSize)
            val userMedia = MediaData().copy(active = true)
            kosmos.shadeRepository.setShadeMode(ShadeMode.Split)
            kosmos.mediaFilterRepository.addSelectedUserMediaEntry(userMedia)
            kosmos.keyguardRepository.setIsDozing(true)
            assertThat(value).isEqualTo(ClockSize.LARGE)
        }

    @Test
    @EnableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOn_notSplitMode_true() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.shadeRepository.setShadeMode(ShadeMode.Single)
            assertThat(value).isEqualTo(true)
        }

    @Test
    @EnableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOn_splitMode_noActiveNotifications_true() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.shadeRepository.setShadeMode(ShadeMode.Split)
            kosmos.activeNotificationListRepository.setActiveNotifs(0)
            assertThat(value).isEqualTo(true)
        }

    @Test
    @EnableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOn_splitMode_isActiveDreamLockscreenHosted_true() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.shadeRepository.setShadeMode(ShadeMode.Split)
            kosmos.activeNotificationListRepository.setActiveNotifs(1)
            kosmos.keyguardRepository.setIsActiveDreamLockscreenHosted(true)
            assertThat(value).isEqualTo(true)
        }

    @Test
    @EnableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOn_splitMode_hasPulsingNotifications_false() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.shadeRepository.setShadeMode(ShadeMode.Split)
            kosmos.activeNotificationListRepository.setActiveNotifs(1)
            kosmos.headsUpNotificationRepository.isHeadsUpAnimatingAway.value = true
            kosmos.keyguardRepository.setIsDozing(true)
            assertThat(value).isEqualTo(false)
        }

    @Test
    @EnableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOn_splitMode_onAod_true() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.shadeRepository.setShadeMode(ShadeMode.Split)
            kosmos.activeNotificationListRepository.setActiveNotifs(1)
            transitionTo(KeyguardState.LOCKSCREEN, KeyguardState.AOD)
            assertThat(value).isEqualTo(true)
        }

    @Test
    @EnableSceneContainer
    fun clockShouldBeCentered_sceneContainerFlagOn_splitMode_offAod_false() =
        testScope.runTest {
            val value by collectLastValue(underTest.clockShouldBeCentered)
            kosmos.shadeRepository.setShadeMode(ShadeMode.Split)
            kosmos.activeNotificationListRepository.setActiveNotifs(1)
            transitionTo(KeyguardState.AOD, KeyguardState.LOCKSCREEN)
            assertThat(value).isEqualTo(false)
        }

    private suspend fun transitionTo(from: KeyguardState, to: KeyguardState) {
        with(kosmos.fakeKeyguardTransitionRepository) {
            sendTransitionStep(TransitionStep(from, to, 0f, TransitionState.STARTED))
            sendTransitionStep(TransitionStep(from, to, 0.5f, TransitionState.RUNNING))
            sendTransitionStep(TransitionStep(from, to, 1f, TransitionState.FINISHED))
        }
    }
}
