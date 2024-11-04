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

package com.android.systemui.communal.view.viewmodel

import android.content.ComponentName
import android.content.pm.UserInfo
import android.platform.test.flag.junit.FlagsParameterization
import android.provider.Settings
import android.widget.RemoteViews
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.Flags.FLAG_COMMUNAL_HUB
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.model.CommunalSmartspaceTimer
import com.android.systemui.communal.data.repository.FakeCommunalMediaRepository
import com.android.systemui.communal.data.repository.FakeCommunalSceneRepository
import com.android.systemui.communal.data.repository.FakeCommunalSmartspaceRepository
import com.android.systemui.communal.data.repository.FakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.FakeCommunalWidgetRepository
import com.android.systemui.communal.data.repository.fakeCommunalMediaRepository
import com.android.systemui.communal.data.repository.fakeCommunalSceneRepository
import com.android.systemui.communal.data.repository.fakeCommunalSmartspaceRepository
import com.android.systemui.communal.data.repository.fakeCommunalTutorialRepository
import com.android.systemui.communal.data.repository.fakeCommunalWidgetRepository
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.domain.interactor.communalSceneInteractor
import com.android.systemui.communal.domain.interactor.communalSettingsInteractor
import com.android.systemui.communal.domain.interactor.communalTutorialInteractor
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.log.CommunalMetricsLogger
import com.android.systemui.communal.shared.model.CommunalContentSize
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel.Companion.POPUP_AUTO_HIDE_TIMEOUT_MS
import com.android.systemui.communal.ui.viewmodel.PopupType
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.flags.Flags.COMMUNAL_SERVICE_ENABLED
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.DozeTransitionModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.scene.data.repository.Idle
import com.android.systemui.scene.data.repository.Transition
import com.android.systemui.scene.data.repository.setTransition
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.shade.ShadeTestUtil
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.data.repository.fakeUserRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class CommunalViewModelTest(flags: FlagsParameterization) : SysuiTestCase() {
    @Mock private lateinit var mediaHost: MediaHost
    @Mock private lateinit var metricsLogger: CommunalMetricsLogger

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var tutorialRepository: FakeCommunalTutorialRepository
    private lateinit var widgetRepository: FakeCommunalWidgetRepository
    private lateinit var smartspaceRepository: FakeCommunalSmartspaceRepository
    private lateinit var mediaRepository: FakeCommunalMediaRepository
    private lateinit var userRepository: FakeUserRepository
    private lateinit var shadeTestUtil: ShadeTestUtil
    private lateinit var keyguardTransitionRepository: FakeKeyguardTransitionRepository
    private lateinit var communalRepository: FakeCommunalSceneRepository
    private lateinit var communalInteractor: CommunalInteractor

    private lateinit var underTest: CommunalViewModel

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        keyguardRepository = kosmos.fakeKeyguardRepository
        keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
        tutorialRepository = kosmos.fakeCommunalTutorialRepository
        widgetRepository = kosmos.fakeCommunalWidgetRepository
        smartspaceRepository = kosmos.fakeCommunalSmartspaceRepository
        mediaRepository = kosmos.fakeCommunalMediaRepository
        userRepository = kosmos.fakeUserRepository
        shadeTestUtil = kosmos.shadeTestUtil
        communalRepository = kosmos.fakeCommunalSceneRepository

        kosmos.fakeFeatureFlagsClassic.set(COMMUNAL_SERVICE_ENABLED, true)
        mSetFlagsRule.enableFlags(FLAG_COMMUNAL_HUB)

        kosmos.fakeUserTracker.set(userInfos = listOf(MAIN_USER_INFO), selectedUserIndex = 0)
        whenever(mediaHost.visible).thenReturn(true)

        kosmos.powerInteractor.setAwakeForTest()

        communalInteractor = spy(kosmos.communalInteractor)

        underTest = createViewModel()
    }

    private fun createViewModel(): CommunalViewModel {
        return CommunalViewModel(
            kosmos.testDispatcher,
            testScope,
            kosmos.testScope.backgroundScope,
            context.resources,
            kosmos.keyguardTransitionInteractor,
            kosmos.keyguardInteractor,
            mock<KeyguardIndicationController>(),
            kosmos.communalSceneInteractor,
            communalInteractor,
            kosmos.communalSettingsInteractor,
            kosmos.communalTutorialInteractor,
            kosmos.shadeInteractor,
            mediaHost,
            logcatLogBuffer("CommunalViewModelTest"),
            metricsLogger,
        )
    }

    @Test
    fun init_initsMediaHost() =
        testScope.runTest {
            // MediaHost is initialized as soon as the class is created.
            verify(mediaHost).init(MediaHierarchyManager.LOCATION_COMMUNAL_HUB)
        }

    @Test
    fun tutorial_tutorialNotCompletedAndKeyguardVisible_showTutorialContent() =
        testScope.runTest {
            // Keyguard showing, storage unlocked, main user, and tutorial not started.
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)
            keyguardRepository.setIsEncryptedOrLockdown(false)
            setIsMainUser(true)
            tutorialRepository.setTutorialSettingState(
                Settings.Secure.HUB_MODE_TUTORIAL_NOT_STARTED
            )

            val communalContent by collectLastValue(underTest.communalContent)

            assertThat(communalContent!!).isNotEmpty()
            communalContent!!.forEach { model ->
                assertThat(model is CommunalContentModel.Tutorial).isTrue()
            }
        }

    @Test
    fun ordering_smartspaceBeforeUmoBeforeWidgetsBeforeCtaTile() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)

            // Widgets available.
            widgetRepository.addWidget(appWidgetId = 0, rank = 30)
            widgetRepository.addWidget(appWidgetId = 1, rank = 20)

            // Smartspace available.
            smartspaceRepository.setTimers(
                listOf(
                    CommunalSmartspaceTimer(
                        smartspaceTargetId = "target",
                        createdTimestampMillis = 0L,
                        remoteViews = Mockito.mock(RemoteViews::class.java),
                    )
                )
            )

            // Media playing.
            mediaRepository.mediaActive()

            val communalContent by collectLastValue(underTest.communalContent)

            // Order is smart space, then UMO, widget content and cta tile.
            assertThat(communalContent?.size).isEqualTo(5)
            assertThat(communalContent?.get(0))
                .isInstanceOf(CommunalContentModel.Smartspace::class.java)
            assertThat(communalContent?.get(1)).isInstanceOf(CommunalContentModel.Umo::class.java)
            assertThat(communalContent?.get(2))
                .isInstanceOf(CommunalContentModel.WidgetContent::class.java)
            assertThat(communalContent?.get(3))
                .isInstanceOf(CommunalContentModel.WidgetContent::class.java)
            assertThat(communalContent?.get(4))
                .isInstanceOf(CommunalContentModel.CtaTileInViewMode::class.java)
        }

    @Test
    fun ongoingContent_umoAndOneTimer_sizedAppropriately() =
        testScope.runTest {
            // Widgets available.
            widgetRepository.addWidget(appWidgetId = 0, rank = 30)
            widgetRepository.addWidget(appWidgetId = 1, rank = 20)

            // Smartspace available.
            smartspaceRepository.setTimers(
                listOf(
                    CommunalSmartspaceTimer(
                        smartspaceTargetId = "target",
                        createdTimestampMillis = 0L,
                        remoteViews = Mockito.mock(RemoteViews::class.java),
                    )
                )
            )

            // Media playing.
            mediaRepository.mediaActive()

            val communalContent by collectLastValue(underTest.communalContent)

            // One timer, UMO, two widgets, and cta.
            assertThat(communalContent?.size).isEqualTo(5)

            val timer = communalContent?.get(0)
            val umo = communalContent?.get(1)

            assertThat(timer).isInstanceOf(CommunalContentModel.Smartspace::class.java)
            assertThat(umo).isInstanceOf(CommunalContentModel.Umo::class.java)

            assertThat(timer?.size).isEqualTo(CommunalContentSize.HALF)
            assertThat(umo?.size).isEqualTo(CommunalContentSize.HALF)
        }

    @Test
    fun ongoingContent_umoAndTwoTimers_sizedAppropriately() =
        testScope.runTest {
            // Widgets available.
            widgetRepository.addWidget(appWidgetId = 0, rank = 30)
            widgetRepository.addWidget(appWidgetId = 1, rank = 20)

            // Smartspace available.
            smartspaceRepository.setTimers(
                listOf(
                    CommunalSmartspaceTimer(
                        smartspaceTargetId = "target",
                        createdTimestampMillis = 0L,
                        remoteViews = Mockito.mock(RemoteViews::class.java),
                    ),
                    CommunalSmartspaceTimer(
                        smartspaceTargetId = "target",
                        createdTimestampMillis = 0L,
                        remoteViews = Mockito.mock(RemoteViews::class.java),
                    ),
                )
            )

            // Media playing.
            mediaRepository.mediaActive()

            val communalContent by collectLastValue(underTest.communalContent)

            // Two timers, UMO, two widgets, and cta.
            assertThat(communalContent?.size).isEqualTo(6)

            val timer1 = communalContent?.get(0)
            val timer2 = communalContent?.get(1)
            val umo = communalContent?.get(2)

            assertThat(timer1).isInstanceOf(CommunalContentModel.Smartspace::class.java)
            assertThat(timer2).isInstanceOf(CommunalContentModel.Smartspace::class.java)
            assertThat(umo).isInstanceOf(CommunalContentModel.Umo::class.java)

            // One full-sized timer and a half-sized timer and half-sized UMO.
            assertThat(timer1?.size).isEqualTo(CommunalContentSize.HALF)
            assertThat(timer2?.size).isEqualTo(CommunalContentSize.HALF)
            assertThat(umo?.size).isEqualTo(CommunalContentSize.FULL)
        }

    @Test
    fun communalContent_mediaHostVisible_umoIncluded() =
        testScope.runTest {
            // Media playing.
            mediaRepository.mediaActive()

            val communalContent by collectLastValue(underTest.communalContent)
            assertThat(communalContent?.size).isEqualTo(2)
            assertThat(communalContent?.get(0)).isInstanceOf(CommunalContentModel.Umo::class.java)
        }

    @Test
    fun communalContent_mediaHostVisible_umoExcluded() =
        testScope.runTest {
            whenever(mediaHost.visible).thenReturn(false)
            mediaHost.updateViewVisibility()
            // Media playing.
            mediaRepository.mediaActive()

            val communalContent by collectLastValue(underTest.communalContent)
            assertThat(communalContent?.size).isEqualTo(1)
            assertThat(communalContent?.get(0))
                .isInstanceOf(CommunalContentModel.CtaTileInViewMode::class.java)
        }

    @Test
    fun communalContent_mediaHostVisible_umoToggle() =
        testScope.runTest {
            mediaHost.updateViewVisibility()
            mediaRepository.mediaActive()

            val communalContent by collectValues(underTest.communalContent)

            whenever(mediaHost.visible).thenReturn(false)
            mediaHost.updateViewVisibility()

            assertThat(communalContent.size).isEqualTo(1)
        }

    @Test
    fun isEmptyState_isTrue_noWidgetButActiveLiveContent() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)

            widgetRepository.setCommunalWidgets(emptyList())
            // UMO playing
            mediaRepository.mediaActive()
            smartspaceRepository.setTimers(emptyList())

            val isEmptyState by collectLastValue(underTest.isEmptyState)
            assertThat(isEmptyState).isTrue()
        }

    @Test
    fun isEmptyState_isFalse_withWidgets() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)

            widgetRepository.addWidget(appWidgetId = 1, rank = 1)
            mediaRepository.mediaInactive()
            smartspaceRepository.setTimers(emptyList())

            val isEmptyState by collectLastValue(underTest.isEmptyState)
            assertThat(isEmptyState).isFalse()
        }

    @Test
    fun dismissCta_hidesCtaTileAndShowsPopup_thenHidesPopupAfterTimeout() =
        testScope.runTest {
            setIsMainUser(true)
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)

            val communalContent by collectLastValue(underTest.communalContent)
            val currentPopup by collectLastValue(underTest.currentPopup)

            assertThat(communalContent?.size).isEqualTo(1)
            assertThat(communalContent?.get(0))
                .isInstanceOf(CommunalContentModel.CtaTileInViewMode::class.java)

            underTest.onDismissCtaTile()

            // hide CTA tile and show the popup
            assertThat(communalContent).isEmpty()
            assertThat(currentPopup).isEqualTo(PopupType.CtaTile)

            // hide popup after time elapsed
            advanceTimeBy(POPUP_AUTO_HIDE_TIMEOUT_MS)
            assertThat(currentPopup).isNull()
        }

    @Test
    fun popup_onDismiss_hidesImmediately() =
        testScope.runTest {
            setIsMainUser(true)
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)

            val currentPopup by collectLastValue(underTest.currentPopup)

            underTest.onDismissCtaTile()
            assertThat(currentPopup).isEqualTo(PopupType.CtaTile)

            // dismiss the popup directly
            underTest.onHidePopup()
            assertThat(currentPopup).isNull()
        }

    @Test
    fun customizeWidgetButton_showsThenHidesAfterTimeout() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)
            val currentPopup by collectLastValue(underTest.currentPopup)

            assertThat(currentPopup).isNull()
            underTest.onLongClick()
            assertThat(currentPopup).isEqualTo(PopupType.CustomizeWidgetButton)
            advanceTimeBy(POPUP_AUTO_HIDE_TIMEOUT_MS)
            assertThat(currentPopup).isNull()
        }

    @Test
    fun customizeWidgetButton_onDismiss_hidesImmediately() =
        testScope.runTest {
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)
            val currentPopup by collectLastValue(underTest.currentPopup)

            underTest.onLongClick()
            assertThat(currentPopup).isEqualTo(PopupType.CustomizeWidgetButton)

            underTest.onHidePopup()
            assertThat(currentPopup).isNull()
        }

    @Test
    fun canChangeScene_shadeNotExpanded() =
        testScope.runTest {
            // On keyguard without any shade expansion.
            kosmos.fakeKeyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            shadeTestUtil.setLockscreenShadeExpansion(0f)
            runCurrent()
            assertThat(underTest.canChangeScene()).isTrue()
        }

    @Test
    fun canChangeScene_shadeExpanded() =
        testScope.runTest {
            // On keyguard with shade fully expanded.
            kosmos.fakeKeyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            shadeTestUtil.setLockscreenShadeExpansion(1f)
            runCurrent()
            assertThat(underTest.canChangeScene()).isFalse()
        }

    @Test
    fun touchesAllowed_shadeNotExpanded() =
        testScope.runTest {
            val touchesAllowed by collectLastValue(underTest.touchesAllowed)

            // On keyguard without any shade expansion.
            kosmos.fakeKeyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            shadeTestUtil.setLockscreenShadeExpansion(0f)
            runCurrent()
            assertThat(touchesAllowed).isTrue()
        }

    @Test
    fun touchesAllowed_shadeExpanded() =
        testScope.runTest {
            val touchesAllowed by collectLastValue(underTest.touchesAllowed)

            // On keyguard with shade fully expanded.
            kosmos.fakeKeyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            shadeTestUtil.setLockscreenShadeExpansion(1f)
            runCurrent()
            assertThat(touchesAllowed).isFalse()
        }

    @Test
    fun isFocusable_isFalse_whenTransitioningAwayFromGlanceableHub() =
        testScope.runTest {
            val isFocusable by collectLastValue(underTest.isFocusable)

            // Shade not expanded.
            shadeTestUtil.setLockscreenShadeExpansion(0f)
            // On communal scene.
            communalRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(CommunalScenes.Communal))
            )
            // Open bouncer.
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.GLANCEABLE_HUB,
                    to = KeyguardState.PRIMARY_BOUNCER,
                    transitionState = TransitionState.STARTED,
                )
            )

            keyguardTransitionRepository.sendTransitionStep(
                from = KeyguardState.GLANCEABLE_HUB,
                to = KeyguardState.PRIMARY_BOUNCER,
                transitionState = TransitionState.RUNNING,
                value = 0.5f,
            )
            assertThat(isFocusable).isEqualTo(false)

            // Transitioned to bouncer.
            keyguardTransitionRepository.sendTransitionStep(
                from = KeyguardState.GLANCEABLE_HUB,
                to = KeyguardState.PRIMARY_BOUNCER,
                transitionState = TransitionState.FINISHED,
                value = 1f,
            )
            assertThat(isFocusable).isEqualTo(false)
        }

    @Test
    fun isFocusable_isFalse_whenNotOnCommunalScene() =
        testScope.runTest {
            val isFocusable by collectLastValue(underTest.isFocusable)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GLANCEABLE_HUB,
                testScope = testScope,
            )
            shadeTestUtil.setLockscreenShadeExpansion(0f)
            // Transitioned away from communal scene.
            communalRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(CommunalScenes.Blank))
            )

            assertThat(isFocusable).isEqualTo(false)
        }

    @Test
    fun isFocusable_isTrue_whenIdleOnCommunal_andShadeNotExpanded() =
        testScope.runTest {
            val isFocusable by collectLastValue(underTest.isFocusable)

            // On communal scene.
            communalRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(CommunalScenes.Communal))
            )
            // Transitioned to Glanceable hub.
            kosmos.setTransition(
                sceneTransition = Idle(Scenes.Communal),
                stateTransition =
                    TransitionStep(
                        from = KeyguardState.LOCKSCREEN,
                        to = KeyguardState.GLANCEABLE_HUB,
                    ),
            )
            // Shade not expanded.
            if (!SceneContainerFlag.isEnabled) shadeTestUtil.setLockscreenShadeExpansion(0f)

            assertThat(isFocusable).isEqualTo(true)
        }

    @Test
    fun isFocusable_isFalse_whenQsIsExpanded() =
        testScope.runTest {
            val isFocusable by collectLastValue(underTest.isFocusable)

            // On communal scene.
            communalRepository.setTransitionState(
                flowOf(ObservableTransitionState.Idle(CommunalScenes.Communal))
            )
            // Transitioned to Glanceable hub.
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GLANCEABLE_HUB,
                testScope = testScope,
            )
            // Qs is expanded.
            shadeTestUtil.setQsExpansion(1f)

            assertThat(isFocusable).isEqualTo(false)
        }

    @Test
    fun isCommunalContentFlowFrozen_whenActivityStartedWhileDreaming() =
        testScope.runTest {
            val isCommunalContentFlowFrozen by
                collectLastValue(underTest.isCommunalContentFlowFrozen)

            // 1. When dreaming not dozing
            keyguardRepository.setDozeTransitionModel(
                DozeTransitionModel(from = DozeStateModel.DOZE, to = DozeStateModel.FINISH)
            )
            advanceTimeBy(600L)

            keyguardRepository.setDreaming(true)
            keyguardRepository.setDreamingWithOverlay(true)
            advanceTimeBy(60L)
            // And keyguard is occluded by dream
            keyguardRepository.setKeyguardOccluded(true)

            // And on hub
            kosmos.setTransition(
                sceneTransition = Idle(Scenes.Communal),
                stateTransition =
                    TransitionStep(
                        from = KeyguardState.DREAMING,
                        to = KeyguardState.GLANCEABLE_HUB
                    ),
            )

            // Then flow is not frozen
            assertThat(isCommunalContentFlowFrozen).isEqualTo(false)

            // 2. When dreaming stopped by the new activity about to show on lock screen
            keyguardRepository.setDreamingWithOverlay(false)
            advanceTimeBy(60L)

            // Then flow is frozen
            assertThat(isCommunalContentFlowFrozen).isEqualTo(true)

            // 3. When transitioned to OCCLUDED and activity shows
            kosmos.setTransition(
                sceneTransition = Idle(Scenes.Lockscreen),
                stateTransition =
                    TransitionStep(
                        from = KeyguardState.GLANCEABLE_HUB,
                        to = KeyguardState.OCCLUDED
                    ),
            )

            // Then flow is not frozen
            assertThat(isCommunalContentFlowFrozen).isEqualTo(false)
        }

    @Test
    fun isCommunalContentFlowFrozen_whenActivityStartedInHandheldMode() =
        testScope.runTest {
            val isCommunalContentFlowFrozen by
                collectLastValue(underTest.isCommunalContentFlowFrozen)

            // 1. When on keyguard and not occluded
            keyguardRepository.setKeyguardShowing(true)
            keyguardRepository.setKeyguardOccluded(false)

            // And transitioned to hub
            kosmos.setTransition(
                sceneTransition = Idle(Scenes.Communal),
                stateTransition =
                    TransitionStep(
                        from = KeyguardState.LOCKSCREEN,
                        to = KeyguardState.GLANCEABLE_HUB,
                    ),
            )

            // Then flow is not frozen
            assertThat(isCommunalContentFlowFrozen).isEqualTo(false)

            // 2. When occluded by a new activity
            keyguardRepository.setKeyguardOccluded(true)
            runCurrent()

            // And transitioning to occluded
            kosmos.setTransition(
                sceneTransition = Transition(from = Scenes.Communal, to = Scenes.Lockscreen),
                stateTransition =
                    TransitionStep(
                        from = KeyguardState.GLANCEABLE_HUB,
                        to = KeyguardState.OCCLUDED,
                        transitionState = TransitionState.STARTED,
                        value = 0f,
                    ),
            )

            // Then flow is frozen
            assertThat(isCommunalContentFlowFrozen).isEqualTo(true)

            // 3. When transition is finished
            kosmos.setTransition(
                sceneTransition = Idle(Scenes.Lockscreen),
                stateTransition =
                    TransitionStep(
                        from = KeyguardState.GLANCEABLE_HUB,
                        to = KeyguardState.OCCLUDED,
                        transitionState = TransitionState.FINISHED,
                        value = 1f,
                    ),
            )

            // Then flow is not frozen
            assertThat(isCommunalContentFlowFrozen).isEqualTo(false)
        }

    @Test
    fun communalContent_emitsFrozenContent_whenFrozen() =
        testScope.runTest {
            val communalContent by collectLastValue(underTest.communalContent)
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)

            // When dreaming
            keyguardRepository.setDozeTransitionModel(
                DozeTransitionModel(from = DozeStateModel.DOZE, to = DozeStateModel.FINISH)
            )
            advanceTimeBy(600L)
            keyguardRepository.setDreaming(true)
            keyguardRepository.setDreamingWithOverlay(true)
            advanceTimeBy(60L)
            keyguardRepository.setKeyguardOccluded(true)

            // And transitioned to hub
            kosmos.setTransition(
                sceneTransition = Idle(Scenes.Communal),
                stateTransition =
                    TransitionStep(
                        from = KeyguardState.DREAMING,
                        to = KeyguardState.GLANCEABLE_HUB
                    ),
            )

            // Widgets available
            widgetRepository.addWidget(appWidgetId = 0, rank = 30)
            widgetRepository.addWidget(appWidgetId = 1, rank = 20)

            // Then hub shows widgets and the CTA tile
            assertThat(communalContent).hasSize(3)

            // When dreaming stopped by another activity which should freeze flow
            keyguardRepository.setDreamingWithOverlay(false)
            advanceTimeBy(60L)

            // New timer available
            smartspaceRepository.setTimers(
                listOf(
                    CommunalSmartspaceTimer(
                        smartspaceTargetId = "target",
                        createdTimestampMillis = 0L,
                        remoteViews = Mockito.mock(RemoteViews::class.java),
                    )
                )
            )
            runCurrent()

            // Still only emits widgets and the CTA tile
            assertThat(communalContent).hasSize(3)
            assertThat(communalContent?.get(0))
                .isInstanceOf(CommunalContentModel.WidgetContent::class.java)
            assertThat(communalContent?.get(1))
                .isInstanceOf(CommunalContentModel.WidgetContent::class.java)
            assertThat(communalContent?.get(2))
                .isInstanceOf(CommunalContentModel.CtaTileInViewMode::class.java)
        }

    @Test
    fun communalContent_emitsLatestContent_whenNotFrozen() =
        testScope.runTest {
            val communalContent by collectLastValue(underTest.communalContent)
            tutorialRepository.setTutorialSettingState(Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED)

            // When dreaming
            keyguardRepository.setDozeTransitionModel(
                DozeTransitionModel(from = DozeStateModel.DOZE, to = DozeStateModel.FINISH)
            )
            advanceTimeBy(600L)
            keyguardRepository.setDreaming(true)
            keyguardRepository.setDreamingWithOverlay(true)
            advanceTimeBy(60L)
            keyguardRepository.setKeyguardOccluded(true)

            // Transitioned to Glanceable hub.
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.DREAMING,
                to = KeyguardState.GLANCEABLE_HUB,
                testScope = testScope,
            )

            // And widgets available
            widgetRepository.addWidget(appWidgetId = 0, rank = 30)
            widgetRepository.addWidget(appWidgetId = 1, rank = 20)

            // Then emits widgets and the CTA tile
            assertThat(communalContent).hasSize(3)

            // When new timer available
            smartspaceRepository.setTimers(
                listOf(
                    CommunalSmartspaceTimer(
                        smartspaceTargetId = "target",
                        createdTimestampMillis = 0L,
                        remoteViews = Mockito.mock(RemoteViews::class.java),
                    )
                )
            )
            runCurrent()

            // Then emits timer, widgets and the CTA tile
            assertThat(communalContent).hasSize(4)
            assertThat(communalContent?.get(0))
                .isInstanceOf(CommunalContentModel.Smartspace::class.java)
            assertThat(communalContent?.get(1))
                .isInstanceOf(CommunalContentModel.WidgetContent::class.java)
            assertThat(communalContent?.get(2))
                .isInstanceOf(CommunalContentModel.WidgetContent::class.java)
            assertThat(communalContent?.get(3))
                .isInstanceOf(CommunalContentModel.CtaTileInViewMode::class.java)
        }

    @Test
    fun communalContent_readTriggersUmoVisibilityUpdate() =
        testScope.runTest {
            verify(mediaHost, never()).updateViewVisibility()

            val communalContent by collectLastValue(underTest.communalContent)

            // updateViewVisibility is called when the flow is collected.
            assertThat(communalContent).isNotNull()
            verify(mediaHost, atLeastOnce()).updateViewVisibility()
        }

    @Test
    fun scrollPosition_persistedOnEditEntry() {
        val index = 2
        val offset = 30
        underTest.onScrollPositionUpdated(index, offset)
        underTest.onOpenWidgetEditor(false)

        verify(communalInteractor).setScrollPosition(eq(index), eq(offset))
    }

    @Test
    fun onTapWidget_logEvent() {
        underTest.onTapWidget(ComponentName("test_pkg", "test_cls"), rank = 10)
        verify(metricsLogger).logTapWidget("test_pkg/test_cls", rank = 10)
    }

    @Test
    fun glanceableTouchAvailable_availableWhenNestedScrollingWithoutConsumption() =
        testScope.runTest {
            val touchAvailable by collectLastValue(underTest.glanceableTouchAvailable)
            assertThat(touchAvailable).isTrue()
            underTest.onHubTouchConsumed()
            assertThat(touchAvailable).isFalse()
            underTest.onNestedScrolling()
            assertThat(touchAvailable).isTrue()
        }

    @Test
    fun selectedKey_changeAffectsAllInstances() =
        testScope.runTest {
            val model1 = createViewModel()
            val selectedKey1 by collectLastValue(model1.selectedKey)
            val model2 = createViewModel()
            val selectedKey2 by collectLastValue(model2.selectedKey)

            val key = "test"
            model1.setSelectedKey(key)

            assertThat(selectedKey1).isEqualTo(key)
            assertThat(selectedKey2).isEqualTo(key)
        }

    private suspend fun setIsMainUser(isMainUser: Boolean) {
        val user = if (isMainUser) MAIN_USER_INFO else SECONDARY_USER_INFO
        with(userRepository) {
            setUserInfos(listOf(user))
            setSelectedUserInfo(user)
        }
        kosmos.fakeUserTracker.set(userInfos = listOf(user), selectedUserIndex = 0)
    }

    private companion object {
        val MAIN_USER_INFO = UserInfo(0, "primary", UserInfo.FLAG_MAIN)
        val SECONDARY_USER_INFO = UserInfo(1, "secondary", 0)

        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf().andSceneContainer()
        }
    }
}
