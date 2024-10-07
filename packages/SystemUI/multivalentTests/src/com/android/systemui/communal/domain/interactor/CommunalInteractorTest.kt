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
import android.provider.Settings
import android.provider.Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.Flags.FLAG_COMMUNAL_HUB
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.communal.data.model.CommunalSmartspaceTimer
import com.android.systemui.communal.data.repository.FakeCommunalMediaRepository
import com.android.systemui.communal.data.repository.FakeCommunalPrefsRepository
import com.android.systemui.communal.data.repository.FakeCommunalSceneRepository
import com.android.systemui.communal.data.repository.FakeCommunalSmartspaceRepository
import com.android.systemui.communal.data.repository.FakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.FakeCommunalWidgetRepository
import com.android.systemui.communal.data.repository.fakeCommunalMediaRepository
import com.android.systemui.communal.data.repository.fakeCommunalPrefsRepository
import com.android.systemui.communal.data.repository.fakeCommunalSceneRepository
import com.android.systemui.communal.data.repository.fakeCommunalSmartspaceRepository
import com.android.systemui.communal.data.repository.fakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.fakeCommunalWidgetRepository
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.domain.model.CommunalTransitionProgressModel
import com.android.systemui.communal.shared.model.CommunalContentSize
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.shared.model.EditModeState
import com.android.systemui.communal.widgets.EditWidgetsActivityStarter
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.activityStarter
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.statusbar.phone.fakeManagedProfileController
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.android.systemui.utils.leaks.FakeManagedProfileController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/**
 * This class of test cases assume that communal is enabled. For disabled cases, see
 * [CommunalInteractorCommunalDisabledTest].
 */
@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CommunalInteractorTest : SysuiTestCase() {
    @Mock private lateinit var mainUser: UserInfo
    @Mock private lateinit var secondaryUser: UserInfo

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var tutorialRepository: FakeCommunalTutorialRepository
    private lateinit var communalRepository: FakeCommunalSceneRepository
    private lateinit var mediaRepository: FakeCommunalMediaRepository
    private lateinit var widgetRepository: FakeCommunalWidgetRepository
    private lateinit var smartspaceRepository: FakeCommunalSmartspaceRepository
    private lateinit var userRepository: FakeUserRepository
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var communalPrefsRepository: FakeCommunalPrefsRepository
    private lateinit var editWidgetsActivityStarter: EditWidgetsActivityStarter
    private lateinit var sceneInteractor: SceneInteractor
    private lateinit var communalSceneInteractor: CommunalSceneInteractor
    private lateinit var userTracker: FakeUserTracker
    private lateinit var activityStarter: ActivityStarter
    private lateinit var userManager: UserManager
    private lateinit var managedProfileController: FakeManagedProfileController

    private lateinit var underTest: CommunalInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        tutorialRepository = kosmos.fakeCommunalTutorialRepository
        communalRepository = kosmos.fakeCommunalSceneRepository
        mediaRepository = kosmos.fakeCommunalMediaRepository
        widgetRepository = kosmos.fakeCommunalWidgetRepository
        smartspaceRepository = kosmos.fakeCommunalSmartspaceRepository
        userRepository = kosmos.fakeUserRepository
        keyguardRepository = kosmos.fakeKeyguardRepository
        editWidgetsActivityStarter = kosmos.editWidgetsActivityStarter
        communalPrefsRepository = kosmos.fakeCommunalPrefsRepository
        sceneInteractor = kosmos.sceneInteractor
        communalSceneInteractor = kosmos.communalSceneInteractor
        userTracker = kosmos.fakeUserTracker
        activityStarter = kosmos.activityStarter
        userManager = kosmos.userManager
        managedProfileController = kosmos.fakeManagedProfileController

        whenever(mainUser.isMain).thenReturn(true)
        whenever(secondaryUser.isMain).thenReturn(false)
        whenever(userManager.isQuietModeEnabled(any<UserHandle>())).thenReturn(false)
        whenever(userManager.isManagedProfile(anyInt())).thenReturn(false)
        userRepository.setUserInfos(listOf(mainUser, secondaryUser))

        kosmos.fakeFeatureFlagsClassic.set(Flags.COMMUNAL_SERVICE_ENABLED, true)
        mSetFlagsRule.enableFlags(FLAG_COMMUNAL_HUB)

        underTest = kosmos.communalInteractor
    }

    @Test
    fun communalEnabled_true() =
        testScope.runTest {
            userRepository.setSelectedUserInfo(mainUser)
            runCurrent()
            assertThat(underTest.isCommunalEnabled.value).isTrue()
        }

    @Test
    fun isCommunalAvailable_storageUnlockedAndMainUser_true() =
        testScope.runTest {
            val isAvailable by collectLastValue(underTest.isCommunalAvailable)
            assertThat(isAvailable).isFalse()

            keyguardRepository.setIsEncryptedOrLockdown(false)
            userRepository.setSelectedUserInfo(mainUser)
            keyguardRepository.setKeyguardShowing(true)

            assertThat(isAvailable).isTrue()
        }

    @Test
    fun isCommunalAvailable_storageLockedAndMainUser_false() =
        testScope.runTest {
            val isAvailable by collectLastValue(underTest.isCommunalAvailable)
            assertThat(isAvailable).isFalse()

            keyguardRepository.setIsEncryptedOrLockdown(true)
            userRepository.setSelectedUserInfo(mainUser)
            keyguardRepository.setKeyguardShowing(true)

            assertThat(isAvailable).isFalse()
        }

    @Test
    fun isCommunalAvailable_storageUnlockedAndSecondaryUser_false() =
        testScope.runTest {
            val isAvailable by collectLastValue(underTest.isCommunalAvailable)
            assertThat(isAvailable).isFalse()

            keyguardRepository.setIsEncryptedOrLockdown(false)
            userRepository.setSelectedUserInfo(secondaryUser)
            keyguardRepository.setKeyguardShowing(true)

            assertThat(isAvailable).isFalse()
        }

    @Test
    fun isCommunalAvailable_whenKeyguardShowing_true() =
        testScope.runTest {
            val isAvailable by collectLastValue(underTest.isCommunalAvailable)
            assertThat(isAvailable).isFalse()

            keyguardRepository.setIsEncryptedOrLockdown(false)
            userRepository.setSelectedUserInfo(mainUser)
            keyguardRepository.setKeyguardShowing(true)

            assertThat(isAvailable).isTrue()
        }

    @Test
    fun isCommunalAvailable_communalDisabled_false() =
        testScope.runTest {
            mSetFlagsRule.disableFlags(FLAG_COMMUNAL_HUB)

            val isAvailable by collectLastValue(underTest.isCommunalAvailable)
            assertThat(isAvailable).isFalse()

            keyguardRepository.setIsEncryptedOrLockdown(false)
            userRepository.setSelectedUserInfo(mainUser)
            keyguardRepository.setKeyguardShowing(true)

            assertThat(isAvailable).isFalse()
        }

    @Test
    fun widget_tutorialCompletedAndWidgetsAvailable_showWidgetContent() =
        testScope.runTest {
            // Keyguard showing, and tutorial completed.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            val userInfos = listOf(MAIN_USER_INFO, USER_INFO_WORK)
            userRepository.setUserInfos(userInfos)
            userTracker.set(userInfos = userInfos, selectedUserIndex = 0)
            runCurrent()

            // Widgets available.
            widgetRepository.addWidget(appWidgetId = 1, userId = USER_INFO_WORK.id)
            widgetRepository.addWidget(appWidgetId = 2, userId = MAIN_USER_INFO.id)
            widgetRepository.addWidget(appWidgetId = 3, userId = MAIN_USER_INFO.id)

            val widgetContent by collectLastValue(underTest.widgetContent)

            assertThat(checkNotNull(widgetContent)).isNotEmpty()
            assertThat(widgetContent!![0].appWidgetId).isEqualTo(1)
            assertThat(widgetContent!![1].appWidgetId).isEqualTo(2)
            assertThat(widgetContent!![2].appWidgetId).isEqualTo(3)
        }

    @Test
    fun smartspaceDynamicSizing_oneCard_fullSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 1,
            expectedSizes = listOf(CommunalContentSize.FULL),
        )

    @Test
    fun smartspace_dynamicSizing_twoCards_halfSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 2,
            expectedSizes = listOf(CommunalContentSize.HALF, CommunalContentSize.HALF),
        )

    @Test
    fun smartspace_dynamicSizing_threeCards_thirdSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 3,
            expectedSizes =
                listOf(
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                ),
        )

    @Test
    fun smartspace_dynamicSizing_fourCards_threeThirdSizeAndOneFullSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 4,
            expectedSizes =
                listOf(
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.FULL,
                ),
        )

    @Test
    fun smartspace_dynamicSizing_fiveCards_threeThirdAndTwoHalfSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 5,
            expectedSizes =
                listOf(
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.HALF,
                    CommunalContentSize.HALF,
                ),
        )

    @Test
    fun smartspace_dynamicSizing_sixCards_allThirdSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 6,
            expectedSizes =
                listOf(
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                ),
        )

    private fun testSmartspaceDynamicSizing(
        totalTargets: Int,
        expectedSizes: List<CommunalContentSize>,
    ) =
        testScope.runTest {
            // Keyguard showing, and tutorial completed.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            val targets = mutableListOf<CommunalSmartspaceTimer>()
            for (index in 0 until totalTargets) {
                targets.add(smartspaceTimer(index.toString()))
            }

            smartspaceRepository.setTimers(targets)

            val smartspaceContent by collectLastValue(underTest.ongoingContent(false))
            assertThat(smartspaceContent?.size).isEqualTo(totalTargets)
            for (index in 0 until totalTargets) {
                assertThat(smartspaceContent?.get(index)?.size).isEqualTo(expectedSizes[index])
            }
        }

    @Test
    fun umo_mediaPlaying_showsUmo() =
        testScope.runTest {
            // Tutorial completed.
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            // Media is playing.
            mediaRepository.mediaActive()

            val umoContent by collectLastValue(underTest.ongoingContent(true))

            assertThat(umoContent?.size).isEqualTo(1)
            assertThat(umoContent?.get(0)).isInstanceOf(CommunalContentModel.Umo::class.java)
            assertThat(umoContent?.get(0)?.key).isEqualTo(CommunalContentModel.KEY.umo())
        }

    @Test
    fun umo_mediaPlaying_mediaHostNotVisible_hidesUmo() =
        testScope.runTest {
            // Tutorial completed.
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            // Media is playing.
            mediaRepository.mediaActive()

            val umoContent by collectLastValue(underTest.ongoingContent(false))
            assertThat(umoContent?.size).isEqualTo(0)
        }

    @Test
    fun ongoing_shouldOrderAndSizeByTimestamp() =
        testScope.runTest {
            // Keyguard showing, and tutorial completed.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            // Timer1 started
            val timer1 = smartspaceTimer("timer1", timestamp = 1L)
            smartspaceRepository.setTimers(listOf(timer1))

            // Umo started
            mediaRepository.mediaActive(timestamp = 2L)

            // Timer2 started
            val timer2 = smartspaceTimer("timer2", timestamp = 3L)
            smartspaceRepository.setTimers(listOf(timer1, timer2))

            // Timer3 started
            val timer3 = smartspaceTimer("timer3", timestamp = 4L)
            smartspaceRepository.setTimers(listOf(timer1, timer2, timer3))

            val ongoingContent by collectLastValue(underTest.ongoingContent(true))
            assertThat(ongoingContent?.size).isEqualTo(4)
            assertThat(ongoingContent?.get(0)?.key)
                .isEqualTo(CommunalContentModel.KEY.smartspace("timer3"))
            assertThat(ongoingContent?.get(0)?.size).isEqualTo(CommunalContentSize.HALF)
            assertThat(ongoingContent?.get(1)?.key)
                .isEqualTo(CommunalContentModel.KEY.smartspace("timer2"))
            assertThat(ongoingContent?.get(1)?.size).isEqualTo(CommunalContentSize.HALF)
            assertThat(ongoingContent?.get(2)?.key).isEqualTo(CommunalContentModel.KEY.umo())
            assertThat(ongoingContent?.get(2)?.size).isEqualTo(CommunalContentSize.HALF)
            assertThat(ongoingContent?.get(3)?.key)
                .isEqualTo(CommunalContentModel.KEY.smartspace("timer1"))
            assertThat(ongoingContent?.get(3)?.size).isEqualTo(CommunalContentSize.HALF)
        }

    @Test
    fun ctaTile_showsByDefault() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            val ctaTileContent by collectLastValue(underTest.ctaTileContent)

            assertThat(ctaTileContent?.size).isEqualTo(1)
            assertThat(ctaTileContent?.get(0))
                .isInstanceOf(CommunalContentModel.CtaTileInViewMode::class.java)
            assertThat(ctaTileContent?.get(0)?.key)
                .isEqualTo(CommunalContentModel.KEY.CTA_TILE_IN_VIEW_MODE_KEY)
        }

    @Test
    fun ctaTile_afterDismiss_doesNotShow() =
        testScope.runTest {
            // Set to main user, so we can dismiss the tile for the main user.
            val user = userRepository.asMainUser()
            userTracker.set(userInfos = listOf(user), selectedUserIndex = 0)
            runCurrent()

            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)
            communalPrefsRepository.setCtaDismissed(user)

            val ctaTileContent by collectLastValue(underTest.ctaTileContent)

            assertThat(ctaTileContent).isEmpty()
        }

    @Test
    fun listensToSceneChange() =
        testScope.runTest {
            kosmos.setCommunalAvailable(true)
            runCurrent()

            var desiredScene = collectLastValue(underTest.desiredScene)
            runCurrent()
            assertThat(desiredScene()).isEqualTo(CommunalScenes.Blank)

            val targetScene = CommunalScenes.Communal
            communalRepository.changeScene(targetScene)
            desiredScene = collectLastValue(underTest.desiredScene)
            runCurrent()
            assertThat(desiredScene()).isEqualTo(targetScene)
        }

    @Test
    fun updatesScene() =
        testScope.runTest {
            val targetScene = CommunalScenes.Communal

            underTest.changeScene(targetScene, "test")

            val desiredScene = collectLastValue(communalRepository.currentScene)
            runCurrent()
            assertThat(desiredScene()).isEqualTo(targetScene)
        }

    @Test
    fun transitionProgress_onTargetScene_fullProgress() =
        testScope.runTest {
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
        testScope.runTest {
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
        testScope.runTest {
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
        testScope.runTest {
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
        testScope.runTest {
            kosmos.setCommunalAvailable(true)
            runCurrent()

            var isCommunalShowing = collectLastValue(underTest.isCommunalShowing)
            runCurrent()
            assertThat(isCommunalShowing()).isEqualTo(false)

            underTest.changeScene(CommunalScenes.Communal, "test")

            isCommunalShowing = collectLastValue(underTest.isCommunalShowing)
            runCurrent()
            assertThat(isCommunalShowing()).isEqualTo(true)
        }

    @Test
    fun isCommunalShowing_whenSceneContainerDisabled() =
        testScope.runTest {
            kosmos.setCommunalAvailable(true)
            runCurrent()

            // Verify default is false
            val isCommunalShowing by collectLastValue(underTest.isCommunalShowing)
            runCurrent()
            assertThat(isCommunalShowing).isFalse()

            // Verify scene changes with the flag doesn't have any impact
            sceneInteractor.changeScene(Scenes.Communal, loggingReason = "")
            runCurrent()
            assertThat(isCommunalShowing).isFalse()

            // Verify scene changes (without the flag) to communal sets the value to true
            underTest.changeScene(CommunalScenes.Communal, "test")
            runCurrent()
            assertThat(isCommunalShowing).isTrue()

            // Verify scene changes (without the flag) to blank sets the value back to false
            underTest.changeScene(CommunalScenes.Blank, "test")
            runCurrent()
            assertThat(isCommunalShowing).isFalse()
        }

    @Test
    @EnableSceneContainer
    fun isCommunalShowing_whenSceneContainerEnabled() =
        testScope.runTest {
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
        testScope.runTest {
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
        testScope.runTest {
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(CommunalScenes.Blank)
                )
            communalRepository.setTransitionState(transitionState)

            // isIdleOnCommunal is false when not on communal.
            val isIdleOnCommunal by collectLastValue(underTest.isIdleOnCommunal)
            runCurrent()
            assertThat(isIdleOnCommunal).isEqualTo(false)

            // Transition to communal.
            transitionState.value = ObservableTransitionState.Idle(CommunalScenes.Communal)
            runCurrent()

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
            runCurrent()

            // isIdleOnCommunal turns false as soon as transition away starts.
            assertThat(isIdleOnCommunal).isEqualTo(false)
        }

    @Test
    fun isCommunalVisible() =
        testScope.runTest {
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(CommunalScenes.Blank)
                )
            communalRepository.setTransitionState(transitionState)

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
        testScope.runTest {
            val editModeState by collectLastValue(communalSceneInteractor.editModeState)

            underTest.showWidgetEditor()

            assertThat(editModeState).isEqualTo(EditModeState.STARTING)
            verify(editWidgetsActivityStarter).startActivity()
        }

    @Test
    fun showWidgetEditor_openWidgetPickerOnStart_startsActivity() =
        testScope.runTest {
            underTest.showWidgetEditor(shouldOpenWidgetPickerOnStart = true)
            verify(editWidgetsActivityStarter).startActivity(shouldOpenWidgetPickerOnStart = true)
        }

    @Test
    fun navigateToCommunalWidgetSettings_startsActivity() =
        testScope.runTest {
            underTest.navigateToCommunalWidgetSettings()
            val intentCaptor = argumentCaptor<Intent>()
            verify(activityStarter)
                .postStartActivityDismissingKeyguard(capture(intentCaptor), eq(0))
            assertThat(intentCaptor.value.action).isEqualTo(Settings.ACTION_COMMUNAL_SETTING)
        }

    @Test
    fun filterWidgets_whenUserProfileRemoved() =
        testScope.runTest {
            // Keyguard showing, and tutorial completed.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            // Only main user exists.
            val userInfos = listOf(MAIN_USER_INFO)
            userRepository.setUserInfos(userInfos)
            userTracker.set(userInfos = userInfos, selectedUserIndex = 0)
            runCurrent()

            val widgetContent by collectLastValue(underTest.widgetContent)
            // Given three widgets, and one of them is associated with pre-existing work profile.
            widgetRepository.addWidget(appWidgetId = 1, userId = USER_INFO_WORK.id)
            widgetRepository.addWidget(appWidgetId = 2, userId = MAIN_USER_INFO.id)
            widgetRepository.addWidget(appWidgetId = 3, userId = MAIN_USER_INFO.id)

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
        testScope.runTest {
            // Keyguard showing, and tutorial completed.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            // Work profile is set up.
            val userInfos = listOf(MAIN_USER_INFO, USER_INFO_WORK)
            userRepository.setUserInfos(userInfos)
            userTracker.set(userInfos = userInfos, selectedUserIndex = 0)
            runCurrent()

            // When work profile is paused.
            whenever(userManager.isQuietModeEnabled(eq(UserHandle.of(USER_INFO_WORK.id))))
                .thenReturn(true)
            whenever(userManager.isManagedProfile(eq(USER_INFO_WORK.id))).thenReturn(true)

            val widgetContent by collectLastValue(underTest.widgetContent)
            widgetRepository.addWidget(appWidgetId = 1, userId = USER_INFO_WORK.id)
            widgetRepository.addWidget(appWidgetId = 2, userId = MAIN_USER_INFO.id)
            widgetRepository.addWidget(appWidgetId = 3, userId = MAIN_USER_INFO.id)

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
        testScope.runTest {
            // Keyguard showing, and tutorial completed.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            val userInfos = listOf(MAIN_USER_INFO, USER_INFO_WORK)
            userRepository.setUserInfos(userInfos)
            userTracker.set(userInfos = userInfos, selectedUserIndex = 0)
            userRepository.setSelectedUserInfo(MAIN_USER_INFO)
            runCurrent()

            val widgetContent by collectLastValue(underTest.widgetContent)
            // One available work widget, one pending work widget, and one regular available widget.
            widgetRepository.addWidget(appWidgetId = 1, userId = USER_INFO_WORK.id)
            widgetRepository.addPendingWidget(appWidgetId = 2, userId = USER_INFO_WORK.id)
            widgetRepository.addWidget(appWidgetId = 3, userId = MAIN_USER_INFO.id)

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
        testScope.runTest {
            // Keyguard showing, and tutorial completed.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            val userInfos = listOf(MAIN_USER_INFO, USER_INFO_WORK)
            userRepository.setUserInfos(userInfos)
            userTracker.set(userInfos = userInfos, selectedUserIndex = 0)
            userRepository.setSelectedUserInfo(MAIN_USER_INFO)
            runCurrent()

            val widgetContent by collectLastValue(underTest.widgetContent)
            // Given three widgets, and one of them is associated with work profile.
            widgetRepository.addWidget(appWidgetId = 1, userId = USER_INFO_WORK.id)
            widgetRepository.addPendingWidget(appWidgetId = 2, userId = USER_INFO_WORK.id)
            widgetRepository.addWidget(appWidgetId = 3, userId = MAIN_USER_INFO.id)

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
        testScope.runTest {
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
        testScope.runTest {
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
        testScope.runTest {
            kosmos.setCommunalAvailable(true)
            val showCommunalFromOccluded by collectLastValue(underTest.showCommunalFromOccluded)
            assertThat(showCommunalFromOccluded).isFalse()

            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GLANCEABLE_HUB,
                to = KeyguardState.OCCLUDED,
                testScope,
            )
            runCurrent()
            kosmos.setCommunalAvailable(false)

            assertThat(showCommunalFromOccluded).isFalse()
        }

    @Test
    fun showCommunalFromOccluded_showBouncerWhileOccluded() =
        testScope.runTest {
            kosmos.setCommunalAvailable(true)
            val showCommunalFromOccluded by collectLastValue(underTest.showCommunalFromOccluded)
            assertThat(showCommunalFromOccluded).isFalse()

            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GLANCEABLE_HUB,
                to = KeyguardState.OCCLUDED,
                testScope,
            )
            runCurrent()
            kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.OCCLUDED,
                to = KeyguardState.PRIMARY_BOUNCER,
                testScope,
            )

            assertThat(showCommunalFromOccluded).isTrue()
        }

    @Test
    fun showCommunalFromOccluded_enteredOccludedFromDreaming() =
        testScope.runTest {
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
        testScope.runTest {
            val disclaimerDismissed by collectLastValue(underTest.isDisclaimerDismissed)
            assertThat(disclaimerDismissed).isFalse()
            underTest.setDisclaimerDismissed()
            assertThat(disclaimerDismissed).isTrue()
        }

    @Test
    fun dismissDisclaimerTimeoutResetsDismissedFlag() =
        testScope.runTest {
            val disclaimerDismissed by collectLastValue(underTest.isDisclaimerDismissed)
            underTest.setDisclaimerDismissed()
            assertThat(disclaimerDismissed).isTrue()
            advanceTimeBy(CommunalInteractor.DISCLAIMER_RESET_MILLIS)
            assertThat(disclaimerDismissed).isFalse()
        }

    @Test
    fun settingSelectedKey_flowUpdated() {
        testScope.runTest {
            val key = "test"
            val selectedKey by collectLastValue(underTest.selectedKey)
            underTest.setSelectedKey(key)
            assertThat(selectedKey).isEqualTo(key)
        }
    }

    @Test
    fun unpauseWorkProfileEnablesWorkMode() =
        testScope.runTest {
            underTest.unpauseWorkProfile()

            assertThat(managedProfileController.isWorkModeEnabled()).isTrue()
        }

    private fun setKeyguardFeaturesDisabled(user: UserInfo, disabledFlags: Int) {
        whenever(kosmos.devicePolicyManager.getKeyguardDisabledFeatures(nullable(), eq(user.id)))
            .thenReturn(disabledFlags)
        kosmos.broadcastDispatcher.sendIntentToMatchingReceiversOnly(
            context,
            Intent(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
        )
    }

    private companion object {
        val MAIN_USER_INFO = UserInfo(0, "primary", UserInfo.FLAG_MAIN)
        val USER_INFO_WORK =
            UserInfo(
                10,
                "work",
                /* iconPath= */ "",
                /* flags= */ 0,
                UserManager.USER_TYPE_PROFILE_MANAGED,
            )
    }
}
