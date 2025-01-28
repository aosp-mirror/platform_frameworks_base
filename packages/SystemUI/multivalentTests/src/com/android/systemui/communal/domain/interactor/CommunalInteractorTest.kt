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
 *
 */

package com.android.systemui.communal.domain.interactor

import android.app.admin.DevicePolicyManager
import android.app.admin.devicePolicyManager
import android.content.Intent
import android.content.pm.UserInfo
import android.os.UserHandle
import android.os.UserManager
import android.os.userManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import android.provider.Settings
import android.provider.Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED
import android.widget.RemoteViews
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.Flags.FLAG_COMMUNAL_HUB
import com.android.systemui.Flags.FLAG_COMMUNAL_RESPONSIVE_GRID
import com.android.systemui.Flags.FLAG_COMMUNAL_WIDGET_RESIZING
import com.android.systemui.Flags.FLAG_GLANCEABLE_HUB_V2
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.common.data.repository.batteryRepository
import com.android.systemui.common.data.repository.fake
import com.android.systemui.communal.data.model.CommunalSmartspaceTimer
import com.android.systemui.communal.data.repository.fakeCommunalMediaRepository
import com.android.systemui.communal.data.repository.fakeCommunalPrefsRepository
import com.android.systemui.communal.data.repository.fakeCommunalSceneRepository
import com.android.systemui.communal.data.repository.fakeCommunalSmartspaceRepository
import com.android.systemui.communal.data.repository.fakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.fakeCommunalWidgetRepository
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.domain.model.CommunalTransitionProgressModel
import com.android.systemui.communal.posturing.data.repository.fake
import com.android.systemui.communal.posturing.data.repository.posturingRepository
import com.android.systemui.communal.posturing.shared.model.PosturedState
import com.android.systemui.communal.shared.model.CommunalContentSize
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.shared.model.EditModeState
import com.android.systemui.dock.DockManager
import com.android.systemui.dock.fakeDockManager
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.plugins.activityStarter
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.statusbar.phone.fakeManagedProfileController
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.fakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

/**
 * This class of test cases assume that communal is enabled. For disabled cases, see
 * [CommunalInteractorCommunalDisabledTest].
 */
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class CommunalInteractorTest(flags: FlagsParameterization) : SysuiTestCase() {
    private val mainUser =
        UserInfo(/* id= */ 0, /* name= */ "primary user", /* flags= */ UserInfo.FLAG_MAIN)
    private val secondaryUser = UserInfo(/* id= */ 1, /* name= */ "secondary user", /* flags= */ 0)

    private val kosmos = testKosmos().useUnconfinedTestDispatcher()

    private val Kosmos.underTest by Kosmos.Fixture { communalInteractor }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Before
    fun setUp() {
        whenever(kosmos.userManager.isQuietModeEnabled(any<UserHandle>())).thenReturn(false)
        whenever(kosmos.userManager.isManagedProfile(anyInt())).thenReturn(false)
        kosmos.fakeUserRepository.setUserInfos(listOf(mainUser, secondaryUser))

        kosmos.fakeFeatureFlagsClassic.set(Flags.COMMUNAL_SERVICE_ENABLED, true)
        mSetFlagsRule.enableFlags(FLAG_COMMUNAL_HUB)
    }

    @Test
    fun communalEnabled_true() =
        kosmos.runTest {
            fakeUserRepository.setSelectedUserInfo(mainUser)
            assertThat(underTest.isCommunalEnabled.value).isTrue()
        }

    @Test
    fun isCommunalAvailable_storageUnlockedAndMainUser_true() =
        kosmos.runTest {
            val isAvailable by collectLastValue(underTest.isCommunalAvailable)
            assertThat(isAvailable).isFalse()

            fakeKeyguardRepository.setIsEncryptedOrLockdown(false)
            fakeUserRepository.setSelectedUserInfo(mainUser)
            fakeKeyguardRepository.setKeyguardShowing(true)

            assertThat(isAvailable).isTrue()
        }

    @Test
    fun isCommunalAvailable_storageLockedAndMainUser_false() =
        kosmos.runTest {
            val isAvailable by collectLastValue(underTest.isCommunalAvailable)
            assertThat(isAvailable).isFalse()

            fakeKeyguardRepository.setIsEncryptedOrLockdown(true)
            fakeUserRepository.setSelectedUserInfo(mainUser)
            fakeKeyguardRepository.setKeyguardShowing(true)

            assertThat(isAvailable).isFalse()
        }

    @Test
    fun isCommunalAvailable_storageUnlockedAndSecondaryUser_false() =
        kosmos.runTest {
            val isAvailable by collectLastValue(underTest.isCommunalAvailable)
            assertThat(isAvailable).isFalse()

            fakeKeyguardRepository.setIsEncryptedOrLockdown(false)
            fakeUserRepository.setSelectedUserInfo(secondaryUser)
            fakeKeyguardRepository.setKeyguardShowing(true)

            assertThat(isAvailable).isFalse()
        }

    @Test
    fun isCommunalAvailable_whenKeyguardShowing_true() =
        kosmos.runTest {
            val isAvailable by collectLastValue(underTest.isCommunalAvailable)
            assertThat(isAvailable).isFalse()

            fakeKeyguardRepository.setIsEncryptedOrLockdown(false)
            fakeUserRepository.setSelectedUserInfo(mainUser)
            fakeKeyguardRepository.setKeyguardShowing(true)

            assertThat(isAvailable).isTrue()
        }

    @Test
    fun isCommunalAvailable_communalDisabled_false() =
        kosmos.runTest {
            mSetFlagsRule.disableFlags(FLAG_COMMUNAL_HUB, FLAG_GLANCEABLE_HUB_V2)

            val isAvailable by collectLastValue(underTest.isCommunalAvailable)
            assertThat(isAvailable).isFalse()

            fakeKeyguardRepository.setIsEncryptedOrLockdown(false)
            fakeUserRepository.setSelectedUserInfo(mainUser)
            fakeKeyguardRepository.setKeyguardShowing(true)

            assertThat(isAvailable).isFalse()
        }

    @Test
    fun widget_tutorialCompletedAndWidgetsAvailable_showWidgetContent() =
        kosmos.runTest {
            // Keyguard showing, and tutorial completed.
            fakeKeyguardRepository.setKeyguardShowing(true)
            fakeKeyguardRepository.setKeyguardOccluded(false)
            fakeCommunalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            val userInfos = listOf(MAIN_USER_INFO, USER_INFO_WORK)
            fakeUserRepository.setUserInfos(userInfos)
            fakeUserTracker.set(userInfos = userInfos, selectedUserIndex = 0)

            // Widgets available.
            fakeCommunalWidgetRepository.addWidget(appWidgetId = 1, userId = USER_INFO_WORK.id)
            fakeCommunalWidgetRepository.addWidget(appWidgetId = 2, userId = MAIN_USER_INFO.id)
            fakeCommunalWidgetRepository.addWidget(appWidgetId = 3, userId = MAIN_USER_INFO.id)

            val widgetContent by collectLastValue(underTest.widgetContent)

            assertThat(checkNotNull(widgetContent)).isNotEmpty()
            assertThat(widgetContent!![0].appWidgetId).isEqualTo(1)
            assertThat(widgetContent!![1].appWidgetId).isEqualTo(2)
            assertThat(widgetContent!![2].appWidgetId).isEqualTo(3)
        }

    /** TODO(b/378171351): Handle ongoing content in responsive grid. */
    @Test
    @DisableFlags(FLAG_COMMUNAL_RESPONSIVE_GRID)
    fun smartspaceDynamicSizing_oneCard_fullSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 1,
            expectedSizes = listOf(CommunalContentSize.FixedSize.FULL),
        )

    /** TODO(b/378171351): Handle ongoing content in responsive grid. */
    @Test
    @DisableFlags(FLAG_COMMUNAL_RESPONSIVE_GRID)
    fun smartspace_dynamicSizing_twoCards_halfSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 2,
            expectedSizes =
                listOf(CommunalContentSize.FixedSize.HALF, CommunalContentSize.FixedSize.HALF),
        )

    /** TODO(b/378171351): Handle ongoing content in responsive grid. */
    @Test
    @DisableFlags(FLAG_COMMUNAL_RESPONSIVE_GRID)
    fun smartspace_dynamicSizing_threeCards_thirdSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 3,
            expectedSizes =
                listOf(
                    CommunalContentSize.FixedSize.THIRD,
                    CommunalContentSize.FixedSize.THIRD,
                    CommunalContentSize.FixedSize.THIRD,
                ),
        )

    /** TODO(b/378171351): Handle ongoing content in responsive grid. */
    @Test
    @DisableFlags(FLAG_COMMUNAL_RESPONSIVE_GRID)
    fun smartspace_dynamicSizing_fourCards_threeThirdSizeAndOneFullSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 4,
            expectedSizes =
                listOf(
                    CommunalContentSize.FixedSize.THIRD,
                    CommunalContentSize.FixedSize.THIRD,
                    CommunalContentSize.FixedSize.THIRD,
                    CommunalContentSize.FixedSize.FULL,
                ),
        )

    /** TODO(b/378171351): Handle ongoing content in responsive grid. */
    @Test
    @DisableFlags(FLAG_COMMUNAL_RESPONSIVE_GRID)
    fun smartspace_dynamicSizing_fiveCards_threeThirdAndTwoHalfSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 5,
            expectedSizes =
                listOf(
                    CommunalContentSize.FixedSize.THIRD,
                    CommunalContentSize.FixedSize.THIRD,
                    CommunalContentSize.FixedSize.THIRD,
                    CommunalContentSize.FixedSize.HALF,
                    CommunalContentSize.FixedSize.HALF,
                ),
        )

    /** TODO(b/378171351): Handle ongoing content in responsive grid. */
    @Test
    @DisableFlags(FLAG_COMMUNAL_RESPONSIVE_GRID)
    fun smartspace_dynamicSizing_sixCards_allThirdSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 6,
            expectedSizes =
                listOf(
                    CommunalContentSize.FixedSize.THIRD,
                    CommunalContentSize.FixedSize.THIRD,
                    CommunalContentSize.FixedSize.THIRD,
                    CommunalContentSize.FixedSize.THIRD,
                    CommunalContentSize.FixedSize.THIRD,
                    CommunalContentSize.FixedSize.THIRD,
                ),
        )

    private fun testSmartspaceDynamicSizing(
        totalTargets: Int,
        expectedSizes: List<CommunalContentSize>,
    ) =
        kosmos.runTest {
            // Keyguard showing, and tutorial completed.
            fakeKeyguardRepository.setKeyguardShowing(true)
            fakeKeyguardRepository.setKeyguardOccluded(false)
            fakeCommunalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            val targets = mutableListOf<CommunalSmartspaceTimer>()
            for (index in 0 until totalTargets) {
                targets.add(smartspaceTimer(index.toString()))
            }

            fakeCommunalSmartspaceRepository.setTimers(targets)

            val smartspaceContent by collectLastValue(underTest.ongoingContent(false))
            assertThat(smartspaceContent?.size).isEqualTo(totalTargets)
            for (index in 0 until totalTargets) {
                assertThat(smartspaceContent?.get(index)?.size).isEqualTo(expectedSizes[index])
            }
        }

    @Test
    fun umo_mediaPlaying_showsUmo() =
        kosmos.runTest {
            // Tutorial completed.
            fakeCommunalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            // Media is playing.
            fakeCommunalMediaRepository.mediaActive()

            val umoContent by collectLastValue(underTest.ongoingContent(true))

            assertThat(umoContent?.size).isEqualTo(1)
            assertThat(umoContent?.get(0)).isInstanceOf(CommunalContentModel.Umo::class.java)
            assertThat(umoContent?.get(0)?.key).isEqualTo(CommunalContentModel.KEY.umo())
        }

    @Test
    fun umo_mediaPlaying_mediaHostNotVisible_hidesUmo() =
        kosmos.runTest {
            // Tutorial completed.
            fakeCommunalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            // Media is playing.
            fakeCommunalMediaRepository.mediaActive()

            val umoContent by collectLastValue(underTest.ongoingContent(false))
            assertThat(umoContent?.size).isEqualTo(0)
        }

    /** TODO(b/378171351): Handle ongoing content in responsive grid. */
    @Test
    @DisableFlags(FLAG_COMMUNAL_RESPONSIVE_GRID)
    fun ongoing_shouldOrderAndSizeByTimestamp() =
        kosmos.runTest {
            // Keyguard showing, and tutorial completed.
            fakeKeyguardRepository.setKeyguardShowing(true)
            fakeKeyguardRepository.setKeyguardOccluded(false)
            fakeCommunalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            // Timer1 started
            val timer1 = smartspaceTimer("timer1", timestamp = 1L)
            fakeCommunalSmartspaceRepository.setTimers(listOf(timer1))

            // Umo started
            fakeCommunalMediaRepository.mediaActive(timestamp = 2L)

            // Timer2 started
            val timer2 = smartspaceTimer("timer2", timestamp = 3L)
            fakeCommunalSmartspaceRepository.setTimers(listOf(timer1, timer2))

            // Timer3 started
            val timer3 = smartspaceTimer("timer3", timestamp = 4L)
            fakeCommunalSmartspaceRepository.setTimers(listOf(timer1, timer2, timer3))

            val ongoingContent by collectLastValue(underTest.ongoingContent(true))
            assertThat(ongoingContent?.size).isEqualTo(4)
            assertThat(ongoingContent?.get(0)?.key)
                .isEqualTo(CommunalContentModel.KEY.smartspace("timer3"))
            assertThat(ongoingContent?.get(0)?.size).isEqualTo(CommunalContentSize.FixedSize.HALF)
            assertThat(ongoingContent?.get(1)?.key)
                .isEqualTo(CommunalContentModel.KEY.smartspace("timer2"))
            assertThat(ongoingContent?.get(1)?.size).isEqualTo(CommunalContentSize.FixedSize.HALF)
            assertThat(ongoingContent?.get(2)?.key).isEqualTo(CommunalContentModel.KEY.umo())
            assertThat(ongoingContent?.get(2)?.size).isEqualTo(CommunalContentSize.FixedSize.HALF)
            assertThat(ongoingContent?.get(3)?.key)
                .isEqualTo(CommunalContentModel.KEY.smartspace("timer1"))
            assertThat(ongoingContent?.get(3)?.size).isEqualTo(CommunalContentSize.FixedSize.HALF)
        }

    @Test
    fun ctaTile_showsByDefault() =
        kosmos.runTest {
            fakeCommunalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            val ctaTileContent by collectLastValue(underTest.ctaTileContent)

            assertThat(ctaTileContent?.size).isEqualTo(1)
            assertThat(ctaTileContent?.get(0))
                .isInstanceOf(CommunalContentModel.CtaTileInViewMode::class.java)
            assertThat(ctaTileContent?.get(0)?.key)
                .isEqualTo(CommunalContentModel.KEY.CTA_TILE_IN_VIEW_MODE_KEY)
        }

    @Test
    fun ctaTile_afterDismiss_doesNotShow() =
        kosmos.runTest {
            // Set to main user, so we can dismiss the tile for the main user.
            val user = fakeUserRepository.asMainUser()
            fakeUserTracker.set(userInfos = listOf(user), selectedUserIndex = 0)

            fakeCommunalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)
            fakeCommunalPrefsRepository.setCtaDismissed(user)

            val ctaTileContent by collectLastValue(underTest.ctaTileContent)

            assertThat(ctaTileContent).isEmpty()
        }

    @Test
    fun listensToSceneChange() =
        kosmos.runTest {
            kosmos.setCommunalAvailable(true)

            val desiredScene by collectLastValue(underTest.desiredScene)
            assertThat(desiredScene).isEqualTo(CommunalScenes.Blank)

            val targetScene = CommunalScenes.Communal
            fakeCommunalSceneRepository.changeScene(targetScene)
            assertThat(desiredScene).isEqualTo(targetScene)
        }

    @Test
    fun updatesScene() =
        kosmos.runTest {
            val targetScene = CommunalScenes.Communal
            underTest.changeScene(targetScene, "test")

            val desiredScene by collectLastValue(fakeCommunalSceneRepository.currentScene)
            assertThat(desiredScene).isEqualTo(targetScene)
        }

    @Test
    fun transitionProgress_onTargetScene_fullProgress() =
        kosmos.runTest {
            val targetScene = CommunalScenes.Blank
            val transitionProgressFlow = underTest.transitionProgressToScene(targetScene)
            val transitionProgress by collectLastValue(transitionProgressFlow)

            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(targetScene)
                )
            underTest.setTransitionState(transitionState)

            // We're on the target scene.
            assertThat(transitionProgress)
                .isEqualTo(CommunalTransitionProgressModel.Idle(targetScene))
        }

    @Test
    fun transitionProgress_notOnTargetScene_noProgress() =
        kosmos.runTest {
            val targetScene = CommunalScenes.Blank
            val currentScene = CommunalScenes.Communal
            val transitionProgressFlow = underTest.transitionProgressToScene(targetScene)
            val transitionProgress by collectLastValue(transitionProgressFlow)

            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(currentScene)
                )
            underTest.setTransitionState(transitionState)

            // Transition progress is still idle, but we're not on the target scene.
            assertThat(transitionProgress)
                .isEqualTo(CommunalTransitionProgressModel.Idle(currentScene))
        }

    @Test
    fun transitionProgress_transitioningToTrackedScene() =
        kosmos.runTest {
            val currentScene = CommunalScenes.Communal
            val targetScene = CommunalScenes.Blank
            val transitionProgressFlow = underTest.transitionProgressToScene(targetScene)
            val transitionProgress by collectLastValue(transitionProgressFlow)

            var transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(currentScene)
                )
            underTest.setTransitionState(transitionState)

            // Progress starts at 0.
            assertThat(transitionProgress)
                .isEqualTo(CommunalTransitionProgressModel.Idle(currentScene))

            val progress = MutableStateFlow(0f)
            transitionState =
                MutableStateFlow(
                    ObservableTransitionState.Transition(
                        fromScene = currentScene,
                        toScene = targetScene,
                        currentScene = flowOf(targetScene),
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            underTest.setTransitionState(transitionState)

            // Partially transition.
            progress.value = .4f
            assertThat(transitionProgress)
                .isEqualTo(CommunalTransitionProgressModel.Transition(.4f))

            // Transition is at full progress.
            progress.value = 1f
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgressModel.Transition(1f))

            // Transition finishes.
            transitionState = MutableStateFlow(ObservableTransitionState.Idle(targetScene))
            underTest.setTransitionState(transitionState)
            assertThat(transitionProgress)
                .isEqualTo(CommunalTransitionProgressModel.Idle(targetScene))
        }

    @Test
    fun transitionProgress_transitioningAwayFromTrackedScene() =
        kosmos.runTest {
            val currentScene = CommunalScenes.Blank
            val targetScene = CommunalScenes.Communal
            val transitionProgressFlow = underTest.transitionProgressToScene(currentScene)
            val transitionProgress by collectLastValue(transitionProgressFlow)

            var transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(currentScene)
                )
            underTest.setTransitionState(transitionState)

            // Progress starts at 0.
            assertThat(transitionProgress)
                .isEqualTo(CommunalTransitionProgressModel.Idle(currentScene))

            val progress = MutableStateFlow(0f)
            transitionState =
                MutableStateFlow(
                    ObservableTransitionState.Transition(
                        fromScene = currentScene,
                        toScene = targetScene,
                        currentScene = flowOf(targetScene),
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            underTest.setTransitionState(transitionState)

            // Partially transition.
            progress.value = .4f

            // This is a transition we don't care about the progress of.
            assertThat(transitionProgress)
                .isEqualTo(CommunalTransitionProgressModel.OtherTransition)

            // Transition is at full progress.
            progress.value = 1f
            assertThat(transitionProgress)
                .isEqualTo(CommunalTransitionProgressModel.OtherTransition)

            // Transition finishes.
            transitionState = MutableStateFlow(ObservableTransitionState.Idle(targetScene))
            underTest.setTransitionState(transitionState)
            assertThat(transitionProgress)
                .isEqualTo(CommunalTransitionProgressModel.Idle(targetScene))
        }

    @Test
    fun isCommunalShowing() =
        kosmos.runTest {
            kosmos.setCommunalAvailable(true)

            val isCommunalShowing by collectLastValue(underTest.isCommunalShowing)
            assertThat(isCommunalShowing).isEqualTo(false)

            underTest.changeScene(CommunalScenes.Communal, "test")
            assertThat(isCommunalShowing).isEqualTo(true)
        }

    @Test
    fun isCommunalShowing_whenSceneContainerDisabled() =
        kosmos.runTest {
            kosmos.setCommunalAvailable(true)

            // Verify default is false
            val isCommunalShowing by collectLastValue(underTest.isCommunalShowing)
            assertThat(isCommunalShowing).isFalse()

            // Verify scene changes with the flag doesn't have any impact
            sceneInteractor.changeScene(Scenes.Communal, loggingReason = "")
            assertThat(isCommunalShowing).isFalse()

            // Verify scene changes (without the flag) to communal sets the value to true
            underTest.changeScene(CommunalScenes.Communal, "test")
            assertThat(isCommunalShowing).isTrue()

            // Verify scene changes (without the flag) to blank sets the value back to false
            underTest.changeScene(CommunalScenes.Blank, "test")
            assertThat(isCommunalShowing).isFalse()
        }

    @Test
    @EnableSceneContainer
    fun isCommunalShowing_whenSceneContainerEnabled() =
        kosmos.runTest {
            // Verify default is false
            val isCommunalShowing by collectLastValue(underTest.isCommunalShowing)
            assertThat(isCommunalShowing).isFalse()

            // Verify scene changes (with the flag) to communal sets the value to true
            sceneInteractor.changeScene(Scenes.Communal, loggingReason = "")
            assertThat(isCommunalShowing).isTrue()

            // Verify scene changes (with the flag) to lockscreen sets the value to false
            sceneInteractor.changeScene(Scenes.Lockscreen, loggingReason = "")
            assertThat(isCommunalShowing).isFalse()
        }

    @Test
    @EnableSceneContainer
    fun isCommunalShowing_whenSceneContainerEnabledAndChangeToLegacyScene() =
        kosmos.runTest {
            // Verify default is false
            val isCommunalShowing by collectLastValue(underTest.isCommunalShowing)
            assertThat(isCommunalShowing).isFalse()

            // Verify legacy scene change still makes communal show
            underTest.changeScene(CommunalScenes.Communal, "test")
            assertThat(isCommunalShowing).isTrue()

            // Verify legacy scene change to blank makes communal hidden
            underTest.changeScene(CommunalScenes.Blank, "test")
            assertThat(isCommunalShowing).isFalse()
        }

    @Test
    fun isIdleOnCommunal() =
        kosmos.runTest {
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(CommunalScenes.Blank)
                )
            fakeCommunalSceneRepository.setTransitionState(transitionState)

            // isIdleOnCommunal is false when not on communal.
            val isIdleOnCommunal by collectLastValue(underTest.isIdleOnCommunal)
            assertThat(isIdleOnCommunal).isEqualTo(false)

            // Transition to communal.
            transitionState.value = ObservableTransitionState.Idle(CommunalScenes.Communal)

            // isIdleOnCommunal is now true since we're on communal.
            assertThat(isIdleOnCommunal).isEqualTo(true)

            // Start transition away from communal.
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = CommunalScenes.Communal,
                    toScene = CommunalScenes.Blank,
                    currentScene = flowOf(CommunalScenes.Blank),
                    progress = flowOf(0f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )

            // isIdleOnCommunal turns false as soon as transition away starts.
            assertThat(isIdleOnCommunal).isEqualTo(false)
        }

    @Test
    fun isCommunalVisible() =
        kosmos.runTest {
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(CommunalScenes.Blank)
                )
            fakeCommunalSceneRepository.setTransitionState(transitionState)

            // isCommunalVisible is false when not on communal.
            val isCommunalVisible by collectLastValue(underTest.isCommunalVisible)
            assertThat(isCommunalVisible).isEqualTo(false)

            // Start transition to communal.
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = CommunalScenes.Blank,
                    toScene = CommunalScenes.Communal,
                    currentScene = flowOf(CommunalScenes.Communal),
                    progress = flowOf(0f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )

            // isCommunalVisible is true once transition starts.
            assertThat(isCommunalVisible).isEqualTo(true)

            // Finish transition to communal
            transitionState.value = ObservableTransitionState.Idle(CommunalScenes.Communal)

            // isCommunalVisible is true since we're on communal.
            assertThat(isCommunalVisible).isEqualTo(true)

            // Start transition away from communal.
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = CommunalScenes.Communal,
                    toScene = CommunalScenes.Blank,
                    currentScene = flowOf(CommunalScenes.Blank),
                    progress = flowOf(1.0f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )

            // isCommunalVisible is still true as the false as soon as transition away runs.
            assertThat(isCommunalVisible).isEqualTo(true)
        }

    @Test
    fun testShowWidgetEditorStartsActivity() =
        kosmos.runTest {
            val editModeState by collectLastValue(communalSceneInteractor.editModeState)

            underTest.showWidgetEditor()

            assertThat(editModeState).isEqualTo(EditModeState.STARTING)
            verify(editWidgetsActivityStarter).startActivity()
        }

    @Test
    fun showWidgetEditor_openWidgetPickerOnStart_startsActivity() =
        kosmos.runTest {
            underTest.showWidgetEditor(shouldOpenWidgetPickerOnStart = true)
            verify(editWidgetsActivityStarter).startActivity(shouldOpenWidgetPickerOnStart = true)
        }

    @Test
    fun navigateToCommunalWidgetSettings_startsActivity() =
        kosmos.runTest {
            underTest.navigateToCommunalWidgetSettings()
            val intentCaptor = argumentCaptor<Intent>()
            verify(activityStarter)
                .postStartActivityDismissingKeyguard(capture(intentCaptor), eq(0))
            assertThat(intentCaptor.value.action).isEqualTo(Settings.ACTION_COMMUNAL_SETTING)
        }

    @Test
    fun filterWidgets_whenUserProfileRemoved() =
        kosmos.runTest {
            // Keyguard showing, and tutorial completed.
            fakeKeyguardRepository.setKeyguardShowing(true)
            fakeKeyguardRepository.setKeyguardOccluded(false)
            fakeCommunalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            // Only main user exists.
            val userInfos = listOf(MAIN_USER_INFO)
            fakeUserRepository.setUserInfos(userInfos)
            fakeUserTracker.set(userInfos = userInfos, selectedUserIndex = 0)

            val widgetContent by collectLastValue(underTest.widgetContent)
            // Given three widgets, and one of them is associated with pre-existing work profile.
            fakeCommunalWidgetRepository.addWidget(appWidgetId = 1, userId = USER_INFO_WORK.id)
            fakeCommunalWidgetRepository.addWidget(appWidgetId = 2, userId = MAIN_USER_INFO.id)
            fakeCommunalWidgetRepository.addWidget(appWidgetId = 3, userId = MAIN_USER_INFO.id)

            // One widget is filtered out and the remaining two link to main user id.
            assertThat(checkNotNull(widgetContent).size).isEqualTo(2)
            widgetContent!!.forEachIndexed { _, model ->
                assertThat(model is CommunalContentModel.WidgetContent.Widget).isTrue()
                assertThat(
                        (model as CommunalContentModel.WidgetContent.Widget)
                            .providerInfo
                            .profile
                            ?.identifier
                    )
                    .isEqualTo(MAIN_USER_INFO.id)
            }
        }

    @Test
    fun widgetContent_inQuietMode() =
        kosmos.runTest {
            // Keyguard showing, and tutorial completed.
            fakeKeyguardRepository.setKeyguardShowing(true)
            fakeKeyguardRepository.setKeyguardOccluded(false)
            fakeCommunalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            // Work profile is set up.
            val userInfos = listOf(MAIN_USER_INFO, USER_INFO_WORK)
            fakeUserRepository.setUserInfos(userInfos)
            fakeUserTracker.set(userInfos = userInfos, selectedUserIndex = 0)

            // When work profile is paused.
            whenever(userManager.isQuietModeEnabled(eq(UserHandle.of(USER_INFO_WORK.id))))
                .thenReturn(true)
            whenever(userManager.isManagedProfile(eq(USER_INFO_WORK.id))).thenReturn(true)

            val widgetContent by collectLastValue(underTest.widgetContent)
            fakeCommunalWidgetRepository.addWidget(appWidgetId = 1, userId = USER_INFO_WORK.id)
            fakeCommunalWidgetRepository.addWidget(appWidgetId = 2, userId = MAIN_USER_INFO.id)
            fakeCommunalWidgetRepository.addWidget(appWidgetId = 3, userId = MAIN_USER_INFO.id)

            // The work profile widget is in quiet mode, while other widgets are not.
            assertThat(widgetContent).hasSize(3)
            widgetContent!!.forEach { model ->
                assertThat(model)
                    .isInstanceOf(CommunalContentModel.WidgetContent.Widget::class.java)
            }
            assertThat(
                    (widgetContent!![0] as CommunalContentModel.WidgetContent.Widget).inQuietMode
                )
                .isTrue()
            assertThat(
                    (widgetContent!![1] as CommunalContentModel.WidgetContent.Widget).inQuietMode
                )
                .isFalse()
            assertThat(
                    (widgetContent!![2] as CommunalContentModel.WidgetContent.Widget).inQuietMode
                )
                .isFalse()
        }

    @Test
    fun filterWidgets_whenDisallowedByDevicePolicyForWorkProfile() =
        kosmos.runTest {
            // Keyguard showing, and tutorial completed.
            fakeKeyguardRepository.setKeyguardShowing(true)
            fakeKeyguardRepository.setKeyguardOccluded(false)
            fakeCommunalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            val userInfos = listOf(MAIN_USER_INFO, USER_INFO_WORK)
            fakeUserRepository.setUserInfos(userInfos)
            fakeUserTracker.set(userInfos = userInfos, selectedUserIndex = 0)
            fakeUserRepository.setSelectedUserInfo(MAIN_USER_INFO)

            val widgetContent by collectLastValue(underTest.widgetContent)
            // One available work widget, one pending work widget, and one regular available widget.
            fakeCommunalWidgetRepository.addWidget(appWidgetId = 1, userId = USER_INFO_WORK.id)
            fakeCommunalWidgetRepository.addPendingWidget(
                appWidgetId = 2,
                userId = USER_INFO_WORK.id,
            )
            fakeCommunalWidgetRepository.addWidget(appWidgetId = 3, userId = MAIN_USER_INFO.id)

            setKeyguardFeaturesDisabled(
                USER_INFO_WORK,
                DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL,
            )

            // Widgets under work profile are filtered out. Only the regular widget remains.
            assertThat(widgetContent).hasSize(1)
            assertThat(widgetContent?.get(0)?.appWidgetId).isEqualTo(3)
        }

    @Test
    fun filterWidgets_whenAllowedByDevicePolicyForWorkProfile() =
        kosmos.runTest {
            // Keyguard showing, and tutorial completed.
            fakeKeyguardRepository.setKeyguardShowing(true)
            fakeKeyguardRepository.setKeyguardOccluded(false)
            fakeCommunalTutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            val userInfos = listOf(MAIN_USER_INFO, USER_INFO_WORK)
            fakeUserRepository.setUserInfos(userInfos)
            fakeUserTracker.set(userInfos = userInfos, selectedUserIndex = 0)
            fakeUserRepository.setSelectedUserInfo(MAIN_USER_INFO)

            val widgetContent by collectLastValue(underTest.widgetContent)
            // Given three widgets, and one of them is associated with work profile.
            fakeCommunalWidgetRepository.addWidget(appWidgetId = 1, userId = USER_INFO_WORK.id)
            fakeCommunalWidgetRepository.addPendingWidget(
                appWidgetId = 2,
                userId = USER_INFO_WORK.id,
            )
            fakeCommunalWidgetRepository.addWidget(appWidgetId = 3, userId = MAIN_USER_INFO.id)

            setKeyguardFeaturesDisabled(
                USER_INFO_WORK,
                DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE,
            )

            // Widgets under work profile are available.
            assertThat(widgetContent).hasSize(3)
            assertThat(widgetContent?.get(0)?.appWidgetId).isEqualTo(1)
            assertThat(widgetContent?.get(1)?.appWidgetId).isEqualTo(2)
            assertThat(widgetContent?.get(2)?.appWidgetId).isEqualTo(3)
        }

    @Test
    fun showCommunalFromOccluded_enteredOccludedFromHub() =
        kosmos.runTest {
            kosmos.setCommunalAvailable(true)
            val showCommunalFromOccluded by collectLastValue(underTest.showCommunalFromOccluded)
            assertThat(showCommunalFromOccluded).isFalse()

            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GLANCEABLE_HUB,
                to = KeyguardState.OCCLUDED,
                testScope,
            )

            assertThat(showCommunalFromOccluded).isTrue()
        }

    @Test
    fun showCommunalFromOccluded_enteredOccludedFromLockscreen() =
        kosmos.runTest {
            kosmos.setCommunalAvailable(true)
            val showCommunalFromOccluded by collectLastValue(underTest.showCommunalFromOccluded)
            assertThat(showCommunalFromOccluded).isFalse()

            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.OCCLUDED,
                testScope,
            )

            assertThat(showCommunalFromOccluded).isFalse()
        }

    @Test
    fun showCommunalFromOccluded_communalBecomesUnavailableWhileOccluded() =
        kosmos.runTest {
            kosmos.setCommunalAvailable(true)
            val showCommunalFromOccluded by collectLastValue(underTest.showCommunalFromOccluded)
            assertThat(showCommunalFromOccluded).isFalse()

            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GLANCEABLE_HUB,
                to = KeyguardState.OCCLUDED,
                testScope,
            )
            kosmos.setCommunalAvailable(false)

            assertThat(showCommunalFromOccluded).isFalse()
        }

    @Test
    fun showCommunalFromOccluded_showBouncerWhileOccluded() =
        kosmos.runTest {
            kosmos.setCommunalAvailable(true)
            val showCommunalFromOccluded by collectLastValue(underTest.showCommunalFromOccluded)
            assertThat(showCommunalFromOccluded).isFalse()

            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GLANCEABLE_HUB,
                to = KeyguardState.OCCLUDED,
                testScope,
            )
            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.OCCLUDED,
                to = KeyguardState.PRIMARY_BOUNCER,
                testScope,
            )

            assertThat(showCommunalFromOccluded).isTrue()
        }

    @Test
    fun showCommunalFromOccluded_enteredOccludedFromDreaming() =
        kosmos.runTest {
            kosmos.setCommunalAvailable(true)
            val showCommunalFromOccluded by collectLastValue(underTest.showCommunalFromOccluded)
            assertThat(showCommunalFromOccluded).isFalse()

            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.DREAMING,
                to = KeyguardState.OCCLUDED,
                testScope,
            )

            assertThat(showCommunalFromOccluded).isTrue()
        }

    private fun smartspaceTimer(id: String, timestamp: Long = 0L): CommunalSmartspaceTimer {
        return CommunalSmartspaceTimer(
            smartspaceTargetId = id,
            createdTimestampMillis = timestamp,
            remoteViews = mock(RemoteViews::class.java),
        )
    }

    @Test
    fun dismissDisclaimerSetsDismissedFlag() =
        kosmos.runTest {
            val disclaimerDismissed by collectLastValue(underTest.isDisclaimerDismissed)
            assertThat(disclaimerDismissed).isFalse()
            underTest.setDisclaimerDismissed()
            assertThat(disclaimerDismissed).isTrue()
        }

    @Test
    fun dismissDisclaimerTimeoutResetsDismissedFlag() =
        kosmos.runTest {
            val disclaimerDismissed by collectLastValue(underTest.isDisclaimerDismissed)
            underTest.setDisclaimerDismissed()
            assertThat(disclaimerDismissed).isTrue()
            testScope.advanceTimeBy(CommunalInteractor.DISCLAIMER_RESET_MILLIS)
            assertThat(disclaimerDismissed).isFalse()
        }

    @Test
    fun settingSelectedKey_flowUpdated() {
        kosmos.runTest {
            val key = "test"
            val selectedKey by collectLastValue(underTest.selectedKey)
            underTest.setSelectedKey(key)
            assertThat(selectedKey).isEqualTo(key)
        }
    }

    @Test
    fun unpauseWorkProfileEnablesWorkMode() =
        kosmos.runTest {
            underTest.unpauseWorkProfile()

            assertThat(fakeManagedProfileController.isWorkModeEnabled()).isTrue()
        }

    @Test
    @EnableFlags(FLAG_COMMUNAL_WIDGET_RESIZING)
    @DisableFlags(FLAG_COMMUNAL_RESPONSIVE_GRID)
    fun resizeWidget_withoutUpdatingOrder() =
        kosmos.runTest {
            val userInfos = listOf(MAIN_USER_INFO)
            fakeUserRepository.setUserInfos(userInfos)
            fakeUserTracker.set(userInfos = userInfos, selectedUserIndex = 0)

            // Widgets available.
            fakeCommunalWidgetRepository.addWidget(
                appWidgetId = 1,
                userId = MAIN_USER_INFO.id,
                rank = 0,
                spanY = CommunalContentSize.FixedSize.HALF.span,
            )
            fakeCommunalWidgetRepository.addWidget(
                appWidgetId = 2,
                userId = MAIN_USER_INFO.id,
                rank = 1,
                spanY = CommunalContentSize.FixedSize.HALF.span,
            )
            fakeCommunalWidgetRepository.addWidget(
                appWidgetId = 3,
                userId = MAIN_USER_INFO.id,
                rank = 2,
                spanY = CommunalContentSize.FixedSize.HALF.span,
            )

            val widgetContent by collectLastValue(underTest.widgetContent)

            assertThat(widgetContent?.map { it.appWidgetId to it.size })
                .containsExactly(
                    1 to CommunalContentSize.FixedSize.HALF,
                    2 to CommunalContentSize.FixedSize.HALF,
                    3 to CommunalContentSize.FixedSize.HALF,
                )
                .inOrder()

            underTest.resizeWidget(2, CommunalContentSize.FixedSize.FULL.span, emptyMap())

            // Widget 2 should have been resized to FULL
            assertThat(widgetContent?.map { it.appWidgetId to it.size })
                .containsExactly(
                    1 to CommunalContentSize.FixedSize.HALF,
                    2 to CommunalContentSize.FixedSize.FULL,
                    3 to CommunalContentSize.FixedSize.HALF,
                )
                .inOrder()
        }

    @Test
    @EnableFlags(FLAG_COMMUNAL_WIDGET_RESIZING, FLAG_COMMUNAL_RESPONSIVE_GRID)
    fun resizeWidget_withoutUpdatingOrder_responsive() =
        kosmos.runTest {
            val userInfos = listOf(MAIN_USER_INFO)
            fakeUserRepository.setUserInfos(userInfos)
            fakeUserTracker.set(userInfos = userInfos, selectedUserIndex = 0)

            // Widgets available.
            fakeCommunalWidgetRepository.addWidget(
                appWidgetId = 1,
                userId = MAIN_USER_INFO.id,
                rank = 0,
                spanY = 1,
            )
            fakeCommunalWidgetRepository.addWidget(
                appWidgetId = 2,
                userId = MAIN_USER_INFO.id,
                rank = 1,
                spanY = 1,
            )
            fakeCommunalWidgetRepository.addWidget(
                appWidgetId = 3,
                userId = MAIN_USER_INFO.id,
                rank = 2,
                spanY = 1,
            )

            val widgetContent by collectLastValue(underTest.widgetContent)

            assertThat(widgetContent?.map { it.appWidgetId to it.size })
                .containsExactly(
                    1 to CommunalContentSize.Responsive(1),
                    2 to CommunalContentSize.Responsive(1),
                    3 to CommunalContentSize.Responsive(1),
                )
                .inOrder()

            underTest.resizeWidget(appWidgetId = 2, spanY = 5, widgetIdToRankMap = emptyMap())

            // Widget 2 should have been resized to FULL
            assertThat(widgetContent?.map { it.appWidgetId to it.size })
                .containsExactly(
                    1 to CommunalContentSize.Responsive(1),
                    2 to CommunalContentSize.Responsive(5),
                    3 to CommunalContentSize.Responsive(1),
                )
                .inOrder()
        }

    @Test
    @EnableFlags(FLAG_COMMUNAL_WIDGET_RESIZING)
    @DisableFlags(FLAG_COMMUNAL_RESPONSIVE_GRID)
    fun resizeWidget_andUpdateOrder() =
        kosmos.runTest {
            val userInfos = listOf(MAIN_USER_INFO)
            fakeUserRepository.setUserInfos(userInfos)
            fakeUserTracker.set(userInfos = userInfos, selectedUserIndex = 0)

            // Widgets available.
            fakeCommunalWidgetRepository.addWidget(
                appWidgetId = 1,
                userId = MAIN_USER_INFO.id,
                rank = 0,
                spanY = CommunalContentSize.FixedSize.HALF.span,
            )
            fakeCommunalWidgetRepository.addWidget(
                appWidgetId = 2,
                userId = MAIN_USER_INFO.id,
                rank = 1,
                spanY = CommunalContentSize.FixedSize.HALF.span,
            )
            fakeCommunalWidgetRepository.addWidget(
                appWidgetId = 3,
                userId = MAIN_USER_INFO.id,
                rank = 2,
                spanY = CommunalContentSize.FixedSize.HALF.span,
            )

            val widgetContent by collectLastValue(underTest.widgetContent)

            assertThat(widgetContent?.map { it.appWidgetId to it.size })
                .containsExactly(
                    1 to CommunalContentSize.FixedSize.HALF,
                    2 to CommunalContentSize.FixedSize.HALF,
                    3 to CommunalContentSize.FixedSize.HALF,
                )
                .inOrder()

            underTest.resizeWidget(
                2,
                CommunalContentSize.FixedSize.FULL.span,
                mapOf(2 to 0, 1 to 1),
            )

            // Widget 2 should have been resized to FULL and moved to the front of the list
            assertThat(widgetContent?.map { it.appWidgetId to it.size })
                .containsExactly(
                    2 to CommunalContentSize.FixedSize.FULL,
                    1 to CommunalContentSize.FixedSize.HALF,
                    3 to CommunalContentSize.FixedSize.HALF,
                )
                .inOrder()
        }

    @Test
    @EnableFlags(FLAG_COMMUNAL_WIDGET_RESIZING, FLAG_COMMUNAL_RESPONSIVE_GRID)
    fun resizeWidget_andUpdateOrder_responsive() =
        kosmos.runTest {
            val userInfos = listOf(MAIN_USER_INFO)
            fakeUserRepository.setUserInfos(userInfos)
            fakeUserTracker.set(userInfos = userInfos, selectedUserIndex = 0)

            // Widgets available.
            fakeCommunalWidgetRepository.addWidget(
                appWidgetId = 1,
                userId = MAIN_USER_INFO.id,
                rank = 0,
                spanY = 1,
            )
            fakeCommunalWidgetRepository.addWidget(
                appWidgetId = 2,
                userId = MAIN_USER_INFO.id,
                rank = 1,
                spanY = 1,
            )
            fakeCommunalWidgetRepository.addWidget(
                appWidgetId = 3,
                userId = MAIN_USER_INFO.id,
                rank = 2,
                spanY = 1,
            )

            val widgetContent by collectLastValue(underTest.widgetContent)

            assertThat(widgetContent?.map { it.appWidgetId to it.size })
                .containsExactly(
                    1 to CommunalContentSize.Responsive(1),
                    2 to CommunalContentSize.Responsive(1),
                    3 to CommunalContentSize.Responsive(1),
                )
                .inOrder()

            underTest.resizeWidget(
                appWidgetId = 2,
                spanY = 5,
                widgetIdToRankMap = mapOf(2 to 0, 1 to 1),
            )

            // Widget 2 should have been resized to FULL and moved to the front of the list
            assertThat(widgetContent?.map { it.appWidgetId to it.size })
                .containsExactly(
                    2 to CommunalContentSize.Responsive(5),
                    1 to CommunalContentSize.Responsive(1),
                    3 to CommunalContentSize.Responsive(1),
                )
                .inOrder()
        }

    @Test
    fun showCommunalWhileCharging() =
        kosmos.runTest {
            fakeKeyguardRepository.setIsEncryptedOrLockdown(false)
            fakeUserRepository.setSelectedUserInfo(mainUser)
            fakeKeyguardRepository.setKeyguardShowing(true)
            fakeSettings.putIntForUser(
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP,
                1,
                mainUser.id,
            )

            val shouldShowCommunal by collectLastValue(underTest.shouldShowCommunal)
            batteryRepository.fake.setDevicePluggedIn(false)
            assertThat(shouldShowCommunal).isFalse()

            batteryRepository.fake.setDevicePluggedIn(true)
            assertThat(shouldShowCommunal).isTrue()
        }

    @Test
    fun showCommunalWhilePosturedAndCharging() =
        kosmos.runTest {
            fakeKeyguardRepository.setIsEncryptedOrLockdown(false)
            fakeUserRepository.setSelectedUserInfo(mainUser)
            fakeKeyguardRepository.setKeyguardShowing(true)
            fakeSettings.putIntForUser(
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_POSTURED,
                1,
                mainUser.id,
            )

            val shouldShowCommunal by collectLastValue(underTest.shouldShowCommunal)
            batteryRepository.fake.setDevicePluggedIn(true)
            posturingRepository.fake.setPosturedState(PosturedState.NotPostured)
            assertThat(shouldShowCommunal).isFalse()

            posturingRepository.fake.setPosturedState(PosturedState.Postured(1f))
            assertThat(shouldShowCommunal).isTrue()
        }

    @Test
    fun showCommunalWhileDocked() =
        kosmos.runTest {
            fakeKeyguardRepository.setIsEncryptedOrLockdown(false)
            fakeUserRepository.setSelectedUserInfo(mainUser)
            fakeKeyguardRepository.setKeyguardShowing(true)
            fakeSettings.putIntForUser(Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK, 1, mainUser.id)

            batteryRepository.fake.setDevicePluggedIn(true)
            fakeDockManager.setIsDocked(false)

            val shouldShowCommunal by collectLastValue(underTest.shouldShowCommunal)
            assertThat(shouldShowCommunal).isFalse()

            fakeDockManager.setIsDocked(true)
            fakeDockManager.setDockEvent(DockManager.STATE_DOCKED)
            assertThat(shouldShowCommunal).isTrue()
        }

    private fun setKeyguardFeaturesDisabled(user: UserInfo, disabledFlags: Int) {
        whenever(kosmos.devicePolicyManager.getKeyguardDisabledFeatures(nullable(), eq(user.id)))
            .thenReturn(disabledFlags)
        kosmos.broadcastDispatcher.sendIntentToMatchingReceiversOnly(
            context,
            Intent(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
        )
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf(FLAG_COMMUNAL_RESPONSIVE_GRID)
        }

        private val MAIN_USER_INFO = UserInfo(0, "primary", UserInfo.FLAG_MAIN)
        private val USER_INFO_WORK =
            UserInfo(
                10,
                "work",
                /* iconPath= */ "",
                /* flags= */ 0,
                UserManager.USER_TYPE_PROFILE_MANAGED,
            )
    }
}
