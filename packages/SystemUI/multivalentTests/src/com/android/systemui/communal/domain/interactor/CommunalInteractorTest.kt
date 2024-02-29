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

import android.app.smartspace.SmartspaceTarget
import android.appwidget.AppWidgetProviderInfo
import android.content.pm.UserInfo
import android.os.UserHandle
import android.provider.Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.Flags.FLAG_COMMUNAL_HUB
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.CommunalSettingsRepositoryImpl
import com.android.systemui.communal.data.repository.FakeCommunalMediaRepository
import com.android.systemui.communal.data.repository.FakeCommunalPrefsRepository
import com.android.systemui.communal.data.repository.FakeCommunalRepository
import com.android.systemui.communal.data.repository.FakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.FakeCommunalWidgetRepository
import com.android.systemui.communal.data.repository.fakeCommunalMediaRepository
import com.android.systemui.communal.data.repository.fakeCommunalPrefsRepository
import com.android.systemui.communal.data.repository.fakeCommunalRepository
import com.android.systemui.communal.data.repository.fakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.fakeCommunalWidgetRepository
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.model.CommunalContentSize
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.communal.widgets.EditWidgetsActivityStarter
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.flag.fakeSceneContainerFlags
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.smartspace.data.repository.FakeSmartspaceRepository
import com.android.systemui.smartspace.data.repository.fakeSmartspaceRepository
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.fakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
    private lateinit var communalRepository: FakeCommunalRepository
    private lateinit var mediaRepository: FakeCommunalMediaRepository
    private lateinit var widgetRepository: FakeCommunalWidgetRepository
    private lateinit var smartspaceRepository: FakeSmartspaceRepository
    private lateinit var userRepository: FakeUserRepository
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var communalPrefsRepository: FakeCommunalPrefsRepository
    private lateinit var editWidgetsActivityStarter: EditWidgetsActivityStarter
    private lateinit var sceneInteractor: SceneInteractor
    private lateinit var userTracker: FakeUserTracker

    private lateinit var underTest: CommunalInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        tutorialRepository = kosmos.fakeCommunalTutorialRepository
        communalRepository = kosmos.fakeCommunalRepository
        mediaRepository = kosmos.fakeCommunalMediaRepository
        widgetRepository = kosmos.fakeCommunalWidgetRepository
        smartspaceRepository = kosmos.fakeSmartspaceRepository
        userRepository = kosmos.fakeUserRepository
        keyguardRepository = kosmos.fakeKeyguardRepository
        editWidgetsActivityStarter = kosmos.editWidgetsActivityStarter
        communalPrefsRepository = kosmos.fakeCommunalPrefsRepository
        sceneInteractor = kosmos.sceneInteractor
        userTracker = kosmos.fakeUserTracker

        whenever(mainUser.isMain).thenReturn(true)
        whenever(secondaryUser.isMain).thenReturn(false)
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
    fun isCommunalAvailable_whenDreaming_true() =
        testScope.runTest {
            val isAvailable by collectLastValue(underTest.isCommunalAvailable)
            assertThat(isAvailable).isFalse()

            keyguardRepository.setIsEncryptedOrLockdown(false)
            userRepository.setSelectedUserInfo(mainUser)
            keyguardRepository.setDreaming(true)

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
            userTracker.set(
                userInfos = userInfos,
                selectedUserIndex = 0,
            )
            runCurrent()

            // Widgets available.
            val widget1 = createWidgetForUser(1, USER_INFO_WORK.id)
            val widget2 = createWidgetForUser(2, MAIN_USER_INFO.id)
            val widget3 = createWidgetForUser(3, MAIN_USER_INFO.id)
            val widgets = listOf(widget1, widget2, widget3)
            widgetRepository.setCommunalWidgets(widgets)

            val widgetContent by collectLastValue(underTest.widgetContent)

            assertThat(widgetContent!!).isNotEmpty()
            widgetContent!!.forEachIndexed { index, model ->
                assertThat(model.appWidgetId).isEqualTo(widgets[index].appWidgetId)
            }
        }

    @Test
    fun smartspace_onlyShowTimersWithRemoteViews() =
        testScope.runTest {
            // Keyguard showing, and tutorial completed.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)

            // Not a timer
            val target1 = mock(SmartspaceTarget::class.java)
            whenever(target1.smartspaceTargetId).thenReturn("target1")
            whenever(target1.featureType).thenReturn(SmartspaceTarget.FEATURE_WEATHER)
            whenever(target1.remoteViews).thenReturn(mock(RemoteViews::class.java))
            whenever(target1.creationTimeMillis).thenReturn(0L)

            // Does not have RemoteViews
            val target2 = mock(SmartspaceTarget::class.java)
            whenever(target2.smartspaceTargetId).thenReturn("target2")
            whenever(target2.featureType).thenReturn(SmartspaceTarget.FEATURE_TIMER)
            whenever(target2.remoteViews).thenReturn(null)
            whenever(target2.creationTimeMillis).thenReturn(0L)

            // Timer and has RemoteViews
            val target3 = mock(SmartspaceTarget::class.java)
            whenever(target3.smartspaceTargetId).thenReturn("target3")
            whenever(target3.featureType).thenReturn(SmartspaceTarget.FEATURE_TIMER)
            whenever(target3.remoteViews).thenReturn(mock(RemoteViews::class.java))
            whenever(target3.creationTimeMillis).thenReturn(0L)

            val targets = listOf(target1, target2, target3)
            smartspaceRepository.setCommunalSmartspaceTargets(targets)

            val smartspaceContent by collectLastValue(underTest.ongoingContent)
            assertThat(smartspaceContent?.size).isEqualTo(1)
            assertThat(smartspaceContent?.get(0)?.key)
                .isEqualTo(CommunalContentModel.KEY.smartspace("target3"))
        }

    @Test
    fun smartspaceDynamicSizing_oneCard_fullSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 1,
            expectedSizes =
                listOf(
                    CommunalContentSize.FULL,
                )
        )

    @Test
    fun smartspace_dynamicSizing_twoCards_halfSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 2,
            expectedSizes =
                listOf(
                    CommunalContentSize.HALF,
                    CommunalContentSize.HALF,
                )
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
                )
        )

    @Test
    fun smartspace_dynamicSizing_fourCards_oneFullAndThreeThirdSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 4,
            expectedSizes =
                listOf(
                    CommunalContentSize.FULL,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                )
        )

    @Test
    fun smartspace_dynamicSizing_fiveCards_twoHalfAndThreeThirdSize() =
        testSmartspaceDynamicSizing(
            totalTargets = 5,
            expectedSizes =
                listOf(
                    CommunalContentSize.HALF,
                    CommunalContentSize.HALF,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                    CommunalContentSize.THIRD,
                )
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
                )
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

            val targets = mutableListOf<SmartspaceTarget>()
            for (index in 0 until totalTargets) {
                targets.add(smartspaceTimer(index.toString()))
            }

            smartspaceRepository.setCommunalSmartspaceTargets(targets)

            val smartspaceContent by collectLastValue(underTest.ongoingContent)
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

            val umoContent by collectLastValue(underTest.ongoingContent)

            assertThat(umoContent?.size).isEqualTo(1)
            assertThat(umoContent?.get(0)).isInstanceOf(CommunalContentModel.Umo::class.java)
            assertThat(umoContent?.get(0)?.key).isEqualTo(CommunalContentModel.KEY.umo())
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
            smartspaceRepository.setCommunalSmartspaceTargets(listOf(timer1))

            // Umo started
            mediaRepository.mediaActive(timestamp = 2L)

            // Timer2 started
            val timer2 = smartspaceTimer("timer2", timestamp = 3L)
            smartspaceRepository.setCommunalSmartspaceTargets(listOf(timer1, timer2))

            // Timer3 started
            val timer3 = smartspaceTimer("timer3", timestamp = 4L)
            smartspaceRepository.setCommunalSmartspaceTargets(listOf(timer1, timer2, timer3))

            val ongoingContent by collectLastValue(underTest.ongoingContent)
            assertThat(ongoingContent?.size).isEqualTo(4)
            assertThat(ongoingContent?.get(0)?.key)
                .isEqualTo(CommunalContentModel.KEY.smartspace("timer3"))
            assertThat(ongoingContent?.get(0)?.size).isEqualTo(CommunalContentSize.FULL)
            assertThat(ongoingContent?.get(1)?.key)
                .isEqualTo(CommunalContentModel.KEY.smartspace("timer2"))
            assertThat(ongoingContent?.get(1)?.size).isEqualTo(CommunalContentSize.THIRD)
            assertThat(ongoingContent?.get(2)?.key).isEqualTo(CommunalContentModel.KEY.umo())
            assertThat(ongoingContent?.get(2)?.size).isEqualTo(CommunalContentSize.THIRD)
            assertThat(ongoingContent?.get(3)?.key)
                .isEqualTo(CommunalContentModel.KEY.smartspace("timer1"))
            assertThat(ongoingContent?.get(3)?.size).isEqualTo(CommunalContentSize.THIRD)
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
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)
            communalPrefsRepository.setCtaDismissedForCurrentUser()

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
            communalRepository.setDesiredScene(targetScene)
            desiredScene = collectLastValue(underTest.desiredScene)
            runCurrent()
            assertThat(desiredScene()).isEqualTo(targetScene)
        }

    @Test
    fun updatesScene() =
        testScope.runTest {
            val targetScene = CommunalScenes.Communal

            underTest.onSceneChanged(targetScene)

            val desiredScene = collectLastValue(communalRepository.desiredScene)
            runCurrent()
            assertThat(desiredScene()).isEqualTo(targetScene)
        }

    @Test
    fun desiredScene_communalNotAvailable_returnsBlank() =
        testScope.runTest {
            kosmos.setCommunalAvailable(true)
            runCurrent()

            val desiredScene by collectLastValue(underTest.desiredScene)

            underTest.onSceneChanged(CommunalScenes.Communal)
            assertThat(desiredScene).isEqualTo(CommunalScenes.Communal)

            kosmos.setCommunalAvailable(false)
            runCurrent()

            // Scene returns blank when communal is not available.
            assertThat(desiredScene).isEqualTo(CommunalScenes.Blank)

            kosmos.setCommunalAvailable(true)
            runCurrent()

            // After re-enabling, scene goes back to Communal.
            assertThat(desiredScene).isEqualTo(CommunalScenes.Communal)
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
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgress.Idle(targetScene))
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
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgress.Idle(currentScene))
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
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgress.Idle(currentScene))

            val progress = MutableStateFlow(0f)
            transitionState =
                MutableStateFlow(
                    ObservableTransitionState.Transition(
                        fromScene = currentScene,
                        toScene = targetScene,
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            underTest.setTransitionState(transitionState)

            // Partially transition.
            progress.value = .4f
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgress.Transition(.4f))

            // Transition is at full progress.
            progress.value = 1f
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgress.Transition(1f))

            // Transition finishes.
            transitionState = MutableStateFlow(ObservableTransitionState.Idle(targetScene))
            underTest.setTransitionState(transitionState)
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgress.Idle(targetScene))
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
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgress.Idle(currentScene))

            val progress = MutableStateFlow(0f)
            transitionState =
                MutableStateFlow(
                    ObservableTransitionState.Transition(
                        fromScene = currentScene,
                        toScene = targetScene,
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            underTest.setTransitionState(transitionState)

            // Partially transition.
            progress.value = .4f

            // This is a transition we don't care about the progress of.
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgress.OtherTransition)

            // Transition is at full progress.
            progress.value = 1f
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgress.OtherTransition)

            // Transition finishes.
            transitionState = MutableStateFlow(ObservableTransitionState.Idle(targetScene))
            underTest.setTransitionState(transitionState)
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgress.Idle(targetScene))
        }

    @Test
    fun isCommunalShowing() =
        testScope.runTest {
            kosmos.setCommunalAvailable(true)
            runCurrent()

            var isCommunalShowing = collectLastValue(underTest.isCommunalShowing)
            runCurrent()
            assertThat(isCommunalShowing()).isEqualTo(false)

            underTest.onSceneChanged(CommunalScenes.Communal)

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
            underTest.onSceneChanged(CommunalScenes.Communal)
            runCurrent()
            assertThat(isCommunalShowing).isTrue()

            // Verify scene changes (without the flag) to blank sets the value back to false
            underTest.onSceneChanged(CommunalScenes.Blank)
            runCurrent()
            assertThat(isCommunalShowing).isFalse()
        }

    @Test
    fun isCommunalShowing_whenSceneContainerEnabled() =
        testScope.runTest {
            kosmos.fakeSceneContainerFlags.enabled = true

            // Verify default is false
            val isCommunalShowing by collectLastValue(underTest.isCommunalShowing)
            runCurrent()
            assertThat(isCommunalShowing).isFalse()

            // Verify scene changes without the flag doesn't have any impact
            underTest.onSceneChanged(CommunalScenes.Communal)
            runCurrent()
            assertThat(isCommunalShowing).isFalse()

            // Verify scene changes (with the flag) to communal sets the value to true
            sceneInteractor.changeScene(Scenes.Communal, loggingReason = "")
            runCurrent()
            assertThat(isCommunalShowing).isTrue()

            // Verify scene changes (with the flag) to lockscreen sets the value to false
            sceneInteractor.changeScene(Scenes.Lockscreen, loggingReason = "")
            runCurrent()
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
            underTest.showWidgetEditor()
            verify(editWidgetsActivityStarter).startActivity()
        }

    @Test
    fun showWidgetEditor_withPreselectedKey_startsActivity() =
        testScope.runTest {
            val widgetKey = CommunalContentModel.KEY.widget(123)
            underTest.showWidgetEditor(preselectedKey = widgetKey)
            verify(editWidgetsActivityStarter).startActivity(widgetKey)
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
            userTracker.set(
                userInfos = userInfos,
                selectedUserIndex = 0,
            )
            runCurrent()

            val widgetContent by collectLastValue(underTest.widgetContent)
            // Given three widgets, and one of them is associated with pre-existing work profile.
            val widget1 = createWidgetForUser(1, USER_INFO_WORK.id)
            val widget2 = createWidgetForUser(2, MAIN_USER_INFO.id)
            val widget3 = createWidgetForUser(3, MAIN_USER_INFO.id)
            val widgets = listOf(widget1, widget2, widget3)
            widgetRepository.setCommunalWidgets(widgets)

            // One widget is filtered out and the remaining two link to main user id.
            assertThat(checkNotNull(widgetContent).size).isEqualTo(2)
            widgetContent!!.forEachIndexed { _, model ->
                assertThat(model.providerInfo.profile?.identifier).isEqualTo(MAIN_USER_INFO.id)
            }
        }

    @Test
    fun widgetContent_containsDisabledWidgets_whenCategoryNotAllowed() =
        testScope.runTest {
            // Communal available, and tutorial completed.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)
            userRepository.setSelectedUserInfo(mainUser)

            val userInfos = listOf(MAIN_USER_INFO, USER_INFO_WORK)
            userRepository.setUserInfos(userInfos)
            userTracker.set(
                userInfos = userInfos,
                selectedUserIndex = 0,
            )
            runCurrent()

            // Widgets available.
            val widget1 =
                createWidgetWithCategory(1, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
            val widget2 =
                createWidgetWithCategory(2, AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD)
            val widget3 =
                createWidgetWithCategory(3, AppWidgetProviderInfo.WIDGET_CATEGORY_SEARCHBOX)
            val widgets = listOf(widget1, widget2, widget3)
            widgetRepository.setCommunalWidgets(widgets)

            val widgetContent by collectLastValue(underTest.widgetContent)
            kosmos.fakeSettings.putIntForUser(
                CommunalSettingsRepositoryImpl.GLANCEABLE_HUB_CONTENT_SETTING,
                AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD,
                mainUser.id
            )
            runCurrent()

            // Only the keyguard widget is enabled.
            assertThat(widgetContent).hasSize(3)
            assertThat(widgetContent!!.get(0))
                .isInstanceOf(CommunalContentModel.WidgetContent.DisabledWidget::class.java)
            assertThat(widgetContent!!.get(1))
                .isInstanceOf(CommunalContentModel.WidgetContent.Widget::class.java)
            assertThat(widgetContent!!.get(2))
                .isInstanceOf(CommunalContentModel.WidgetContent.DisabledWidget::class.java)
        }

    @Test
    fun widgetContent_allEnabled_whenCategoryAllowed() =
        testScope.runTest {
            // Communal available, and tutorial completed.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            tutorialRepository.setTutorialSettingState(HUB_MODE_TUTORIAL_COMPLETED)
            userRepository.setSelectedUserInfo(mainUser)

            val userInfos = listOf(MAIN_USER_INFO, USER_INFO_WORK)
            userRepository.setUserInfos(userInfos)
            userTracker.set(
                userInfos = userInfos,
                selectedUserIndex = 0,
            )
            runCurrent()

            // Widgets available.
            val widget1 =
                createWidgetWithCategory(1, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
            val widget2 =
                createWidgetWithCategory(2, AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD)
            val widget3 =
                createWidgetWithCategory(3, AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD)
            val widgets = listOf(widget1, widget2, widget3)
            widgetRepository.setCommunalWidgets(widgets)

            val widgetContent by collectLastValue(underTest.widgetContent)
            kosmos.fakeSettings.putIntForUser(
                CommunalSettingsRepositoryImpl.GLANCEABLE_HUB_CONTENT_SETTING,
                AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD or
                    AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                mainUser.id
            )
            runCurrent()

            // All widgets are enabled.
            assertThat(widgetContent).hasSize(3)
            widgetContent!!.forEach { model ->
                assertThat(model)
                    .isInstanceOf(CommunalContentModel.WidgetContent.Widget::class.java)
            }
        }

    private fun smartspaceTimer(id: String, timestamp: Long = 0L): SmartspaceTarget {
        val timer = mock(SmartspaceTarget::class.java)
        whenever(timer.smartspaceTargetId).thenReturn(id)
        whenever(timer.featureType).thenReturn(SmartspaceTarget.FEATURE_TIMER)
        whenever(timer.remoteViews).thenReturn(mock(RemoteViews::class.java))
        whenever(timer.creationTimeMillis).thenReturn(timestamp)
        return timer
    }

    private fun createWidgetForUser(appWidgetId: Int, userId: Int): CommunalWidgetContentModel =
        mock<CommunalWidgetContentModel> {
            whenever(this.appWidgetId).thenReturn(appWidgetId)
            val providerInfo = mock<AppWidgetProviderInfo>()
            whenever(providerInfo.profile).thenReturn(UserHandle(userId))
            whenever(this.providerInfo).thenReturn(providerInfo)
        }

    private fun createWidgetWithCategory(
        appWidgetId: Int,
        category: Int
    ): CommunalWidgetContentModel =
        mock<CommunalWidgetContentModel> {
            whenever(this.appWidgetId).thenReturn(appWidgetId)
            val providerInfo = mock<AppWidgetProviderInfo>().apply { widgetCategory = category }
            whenever(providerInfo.profile).thenReturn(UserHandle(MAIN_USER_INFO.id))
            whenever(this.providerInfo).thenReturn(providerInfo)
        }

    private companion object {
        val MAIN_USER_INFO = UserInfo(0, "primary", UserInfo.FLAG_MAIN)
        val USER_INFO_WORK = UserInfo(10, "work", UserInfo.FLAG_PROFILE)
    }
}
