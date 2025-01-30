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

package com.android.systemui.statusbar.pipeline.shared.ui.viewmodel

import android.app.StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP
import android.app.StatusBarManager.DISABLE2_NONE
import android.app.StatusBarManager.DISABLE_CLOCK
import android.app.StatusBarManager.DISABLE_NONE
import android.app.StatusBarManager.DISABLE_NOTIFICATION_ICONS
import android.app.StatusBarManager.DISABLE_SYSTEM_INFO
import android.content.testableContext
import android.graphics.Rect
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.Display.DEFAULT_DISPLAY
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.display.data.repository.fake
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.keyguardOcclusionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.collectValues
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.log.assertLogsWtf
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.scene.data.repository.sceneContainerRepository
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.screenRecordRepository
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.NORMAL_PACKAGE
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.setUpPackageManagerForMediaProjection
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.MediaProjectionStopDialogModel
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.chips.sharetoapp.ui.viewmodel.shareToAppChipViewModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModelTest.Companion.assertIsScreenRecordChip
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModelTest.Companion.assertIsShareToAppChip
import com.android.systemui.statusbar.data.model.StatusBarMode
import com.android.systemui.statusbar.data.repository.fakeStatusBarModeRepository
import com.android.systemui.statusbar.disableflags.data.repository.fakeDisableFlagsRepository
import com.android.systemui.statusbar.disableflags.shared.model.DisableFlagsModel
import com.android.systemui.statusbar.events.data.repository.systemStatusEventAnimationRepository
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.AnimatingIn
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.AnimatingOut
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.Idle
import com.android.systemui.statusbar.headsup.shared.StatusBarNoHunBehavior
import com.android.systemui.statusbar.notification.data.model.activeNotificationModel
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore
import com.android.systemui.statusbar.notification.data.repository.UnconfinedFakeHeadsUpRowRepository
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.headsup.PinnedStatus
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor
import com.android.systemui.statusbar.notification.stack.data.repository.headsUpNotificationRepository
import com.android.systemui.statusbar.phone.SysuiDarkIconDispatcher
import com.android.systemui.statusbar.phone.data.repository.fakeDarkIconRepository
import com.android.systemui.statusbar.pipeline.shared.domain.interactor.setHomeStatusBarIconBlockList
import com.android.systemui.statusbar.pipeline.shared.domain.interactor.setHomeStatusBarInteractorShowOperatorName
import com.android.systemui.statusbar.pipeline.shared.ui.model.VisibilityModel
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class HomeStatusBarViewModelImplTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val Kosmos.underTest by Kosmos.Fixture { kosmos.homeStatusBarViewModel }

    @Before
    fun setUp() {
        setUpPackageManagerForMediaProjection(kosmos)
    }

    @Before
    fun addDisplays() = runBlocking { kosmos.displayRepository.fake.addDisplay(DEFAULT_DISPLAY) }

    @Test
    @EnableFlags(com.android.media.projection.flags.Flags.FLAG_SHOW_STOP_DIALOG_POST_CALL_END)
    fun mediaProjectionStopDialogDueToCallEndedState_initiallyHidden() =
        kosmos.runTest {
            shareToAppChipViewModel.start()
            val latest by collectLastValue(underTest.mediaProjectionStopDialogDueToCallEndedState)

            // Verify that the stop dialog is initially hidden
            assertThat(latest).isInstanceOf(MediaProjectionStopDialogModel.Hidden::class.java)
        }

    @Test
    @EnableFlags(com.android.media.projection.flags.Flags.FLAG_SHOW_STOP_DIALOG_POST_CALL_END)
    fun mediaProjectionStopDialogDueToCallEndedState_flagEnabled_mediaIsProjecting_projectionStartedDuringCallAndActivePostCallEventEmitted_isShown() =
        kosmos.runTest {
            shareToAppChipViewModel.start()

            val latest by
                collectLastValue(
                    homeStatusBarViewModel.mediaProjectionStopDialogDueToCallEndedState
                )

            fakeMediaProjectionRepository.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            fakeMediaProjectionRepository.emitProjectionStartedDuringCallAndActivePostCallEvent()

            assertThat(latest).isInstanceOf(MediaProjectionStopDialogModel.Shown::class.java)
        }

    @Test
    @DisableFlags(com.android.media.projection.flags.Flags.FLAG_SHOW_STOP_DIALOG_POST_CALL_END)
    fun mediaProjectionStopDialogDueToCallEndedState_flagDisabled_mediaIsProjecting_projectionStartedDuringCallAndActivePostCallEventEmitted_isHidden() =
        kosmos.runTest {
            shareToAppChipViewModel.start()

            val latest by
                collectLastValue(
                    homeStatusBarViewModel.mediaProjectionStopDialogDueToCallEndedState
                )

            fakeMediaProjectionRepository.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            fakeMediaProjectionRepository.emitProjectionStartedDuringCallAndActivePostCallEvent()

            assertThat(latest).isInstanceOf(MediaProjectionStopDialogModel.Hidden::class.java)
        }

    @Test
    fun isTransitioningFromLockscreenToOccluded_started_isTrue() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isTransitioningFromLockscreenToOccluded)

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.OCCLUDED,
                    value = 0f,
                    TransitionState.STARTED,
                )
            )

            assertThat(latest).isTrue()
        }

    @Test
    fun isTransitioningFromLockscreenToOccluded_running_isTrue() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isTransitioningFromLockscreenToOccluded)

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.OCCLUDED,
                    value = 0f,
                    TransitionState.RUNNING,
                )
            )

            assertThat(latest).isTrue()
        }

    @Test
    fun isTransitioningFromLockscreenToOccluded_finished_isFalse() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isTransitioningFromLockscreenToOccluded)

            fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.OCCLUDED,
                testScope.testScheduler,
            )

            assertThat(latest).isFalse()
        }

    @Test
    fun isTransitioningFromLockscreenToOccluded_canceled_isFalse() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isTransitioningFromLockscreenToOccluded)

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.OCCLUDED,
                    value = 0f,
                    TransitionState.CANCELED,
                )
            )

            assertThat(latest).isFalse()
        }

    @Test
    fun isTransitioningFromLockscreenToOccluded_irrelevantTransition_isFalse() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isTransitioningFromLockscreenToOccluded)

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.AOD,
                    KeyguardState.LOCKSCREEN,
                    value = 0f,
                    TransitionState.RUNNING,
                )
            )

            assertThat(latest).isFalse()
        }

    @Test
    fun isTransitioningFromLockscreenToOccluded_followsRepoUpdates() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isTransitioningFromLockscreenToOccluded)

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.OCCLUDED,
                    value = 0f,
                    TransitionState.RUNNING,
                )
            )

            assertThat(latest).isTrue()

            // WHEN the repo updates the transition to finished
            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.OCCLUDED,
                    value = 0f,
                    TransitionState.FINISHED,
                )
            )

            // THEN our manager also updates
            assertThat(latest).isFalse()
        }

    @Test
    fun transitionFromLockscreenToDreamStartedEvent_started_emitted() =
        kosmos.runTest {
            val emissions by collectValues(underTest.transitionFromLockscreenToDreamStartedEvent)

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.DREAMING,
                    value = 0f,
                    TransitionState.STARTED,
                )
            )

            assertThat(emissions.size).isEqualTo(1)
        }

    @Test
    fun transitionFromLockscreenToDreamStartedEvent_startedMultiple_emittedMultiple() =
        kosmos.runTest {
            val emissions by collectValues(underTest.transitionFromLockscreenToDreamStartedEvent)

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.DREAMING,
                    value = 0f,
                    TransitionState.STARTED,
                )
            )

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.DREAMING,
                    value = 0f,
                    TransitionState.STARTED,
                )
            )

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.DREAMING,
                    value = 0f,
                    TransitionState.STARTED,
                )
            )

            assertThat(emissions.size).isEqualTo(3)
        }

    @Test
    fun transitionFromLockscreenToDreamStartedEvent_startedThenRunning_emittedOnlyOne() =
        kosmos.runTest {
            val emissions by collectValues(underTest.transitionFromLockscreenToDreamStartedEvent)

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.DREAMING,
                    value = 0f,
                    TransitionState.STARTED,
                )
            )
            assertThat(emissions.size).isEqualTo(1)

            // WHEN the transition progresses through its animation by going through the RUNNING
            // step with increasing fractions
            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.DREAMING,
                    value = .1f,
                    TransitionState.RUNNING,
                )
            )

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.DREAMING,
                    value = .2f,
                    TransitionState.RUNNING,
                )
            )

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.DREAMING,
                    value = .3f,
                    TransitionState.RUNNING,
                )
            )

            // THEN the flow does not emit since the flow should only emit when the transition
            // starts
            assertThat(emissions.size).isEqualTo(1)
        }

    @Test
    fun transitionFromLockscreenToDreamStartedEvent_irrelevantTransition_notEmitted() =
        kosmos.runTest {
            val emissions by collectValues(underTest.transitionFromLockscreenToDreamStartedEvent)

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.OCCLUDED,
                    value = 0f,
                    TransitionState.STARTED,
                )
            )

            assertThat(emissions).isEmpty()
        }

    @Test
    fun transitionFromLockscreenToDreamStartedEvent_irrelevantTransitionState_notEmitted() =
        kosmos.runTest {
            val emissions by collectValues(underTest.transitionFromLockscreenToDreamStartedEvent)

            fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.DREAMING,
                    value = 1.0f,
                    TransitionState.FINISHED,
                ),
                // We're intentionally not sending STARTED to validate that FINISHED steps are
                // ignored.
                validateStep = false,
            )

            assertThat(emissions).isEmpty()
        }

    @Test
    @EnableFlags(NotificationsLiveDataStoreRefactor.FLAG_NAME)
    fun areNotificationsLightsOut_lowProfileWithNotifications_true() =
        kosmos.runTest {
            fakeStatusBarModeRepository.defaultDisplay.statusBarMode.value =
                StatusBarMode.LIGHTS_OUT_TRANSPARENT
            activeNotificationListRepository.activeNotifications.value =
                activeNotificationsStore(testNotifications)

            val actual by collectLastValue(underTest.areNotificationsLightsOut)

            assertThat(actual).isTrue()
        }

    @Test
    @EnableFlags(NotificationsLiveDataStoreRefactor.FLAG_NAME)
    fun areNotificationsLightsOut_lowProfileWithoutNotifications_false() =
        kosmos.runTest {
            fakeStatusBarModeRepository.defaultDisplay.statusBarMode.value =
                StatusBarMode.LIGHTS_OUT_TRANSPARENT
            activeNotificationListRepository.activeNotifications.value =
                activeNotificationsStore(emptyList())

            val actual by collectLastValue(underTest.areNotificationsLightsOut)

            assertThat(actual).isFalse()
        }

    @Test
    @EnableFlags(NotificationsLiveDataStoreRefactor.FLAG_NAME)
    fun areNotificationsLightsOut_defaultStatusBarModeWithoutNotifications_false() =
        kosmos.runTest {
            fakeStatusBarModeRepository.defaultDisplay.statusBarMode.value =
                StatusBarMode.TRANSPARENT
            activeNotificationListRepository.activeNotifications.value =
                activeNotificationsStore(emptyList())

            val actual by collectLastValue(underTest.areNotificationsLightsOut)

            assertThat(actual).isFalse()
        }

    @Test
    @EnableFlags(NotificationsLiveDataStoreRefactor.FLAG_NAME)
    fun areNotificationsLightsOut_defaultStatusBarModeWithNotifications_false() =
        kosmos.runTest {
            fakeStatusBarModeRepository.defaultDisplay.statusBarMode.value =
                StatusBarMode.TRANSPARENT
            activeNotificationListRepository.activeNotifications.value =
                activeNotificationsStore(testNotifications)

            val actual by collectLastValue(underTest.areNotificationsLightsOut)

            assertThat(actual).isFalse()
        }

    @Test
    @DisableFlags(NotificationsLiveDataStoreRefactor.FLAG_NAME)
    fun areNotificationsLightsOut_requiresFlagEnabled() =
        kosmos.runTest {
            assertLogsWtf {
                val latest by collectLastValue(underTest.areNotificationsLightsOut)
                // Nothing is emitted
                assertThat(latest).isNull()
            }
        }

    @Test
    fun primaryOngoingActivityChip_matchesViewModel() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.primaryOngoingActivityChip)

            kosmos.screenRecordRepository.screenRecordState.value = ScreenRecordModel.Recording

            assertIsScreenRecordChip(latest)

            kosmos.screenRecordRepository.screenRecordState.value = ScreenRecordModel.DoingNothing

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Inactive::class.java)

            kosmos.fakeMediaProjectionRepository.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            assertIsShareToAppChip(latest)
        }

    @Test
    fun isHomeStatusBarAllowedByScene_sceneLockscreen_notOccluded_false() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isHomeStatusBarAllowedByScene)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Lockscreen)
            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(false, taskInfo = null)

            assertThat(latest).isFalse()
        }

    @Test
    fun isHomeStatusBarAllowedByScene_sceneLockscreen_occluded_true() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isHomeStatusBarAllowedByScene)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Lockscreen)
            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(true, taskInfo = null)

            assertThat(latest).isTrue()
        }

    @Test
    fun isHomeStatusBarAllowedByScene_sceneBouncer_false() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isHomeStatusBarAllowedByScene)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Bouncer)

            assertThat(latest).isFalse()
        }

    @Test
    fun isHomeStatusBarAllowedByScene_sceneCommunal_false() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isHomeStatusBarAllowedByScene)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Communal)

            assertThat(latest).isFalse()
        }

    @Test
    fun isHomeStatusBarAllowedByScene_sceneShade_false() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isHomeStatusBarAllowedByScene)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Shade)

            assertThat(latest).isFalse()
        }

    @Test
    fun isHomeStatusBarAllowedByScene_sceneGone_true() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isHomeStatusBarAllowedByScene)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Gone)

            assertThat(latest).isTrue()
        }

    @Test
    fun isHomeStatusBarAllowedByScene_sceneGoneWithNotificationsShadeOverlay_false() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isHomeStatusBarAllowedByScene)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Gone)
            kosmos.sceneContainerRepository.showOverlay(Overlays.NotificationsShade)
            runCurrent()

            assertThat(latest).isFalse()
        }

    @Test
    fun isHomeStatusBarAllowedByScene_sceneGoneWithQuickSettingsShadeOverlay_false() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isHomeStatusBarAllowedByScene)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Gone)
            kosmos.sceneContainerRepository.showOverlay(Overlays.QuickSettingsShade)
            runCurrent()

            assertThat(latest).isFalse()
        }

    @Test
    fun shouldShowOperatorNameView_allowedByInteractor_allowedByDisableFlags_visible() =
        kosmos.runTest {
            kosmos.setHomeStatusBarInteractorShowOperatorName(true)

            val latest by collectLastValue(underTest.shouldShowOperatorNameView)
            transitionKeyguardToGone()

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE)

            assertThat(latest).isTrue()
        }

    @Test
    fun shouldShowOperatorNameView_disAllowedByInteractor_allowedByDisableFlags_notVisible() =
        kosmos.runTest {
            kosmos.setHomeStatusBarInteractorShowOperatorName(false)

            transitionKeyguardToGone()

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE)

            val latest by collectLastValue(underTest.shouldShowOperatorNameView)

            assertThat(latest).isFalse()
        }

    @Test
    fun shouldShowOperatorNameView_allowedByInteractor_disallowedByDisableFlags_notVisible() =
        kosmos.runTest {
            kosmos.setHomeStatusBarInteractorShowOperatorName(true)

            val latest by collectLastValue(underTest.shouldShowOperatorNameView)
            transitionKeyguardToGone()

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_SYSTEM_INFO, DISABLE2_NONE)

            assertThat(latest).isFalse()
        }

    @Test
    @DisableFlags(StatusBarNoHunBehavior.FLAG_NAME)
    fun shouldShowOperatorNameView_allowedByInteractor_hunPinned_noHunBehaviorFlagOff_false() =
        kosmos.runTest {
            kosmos.setHomeStatusBarInteractorShowOperatorName(true)

            transitionKeyguardToGone()

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE)

            // there is an active HUN
            headsUpNotificationRepository.setNotifications(
                UnconfinedFakeHeadsUpRowRepository(
                    key = "key",
                    pinnedStatus = MutableStateFlow(PinnedStatus.PinnedBySystem),
                )
            )

            val latest by collectLastValue(underTest.shouldShowOperatorNameView)

            assertThat(latest).isFalse()
        }

    @Test
    @EnableFlags(StatusBarNoHunBehavior.FLAG_NAME)
    fun shouldShowOperatorNameView_allowedByInteractor_hunPinned_noHunBehaviorFlagOn_true() =
        kosmos.runTest {
            kosmos.setHomeStatusBarInteractorShowOperatorName(true)

            transitionKeyguardToGone()

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE)

            // WHEN there is an active HUN
            headsUpNotificationRepository.setNotifications(
                UnconfinedFakeHeadsUpRowRepository(
                    key = "key",
                    pinnedStatus = MutableStateFlow(PinnedStatus.PinnedBySystem),
                )
            )

            val latest by collectLastValue(underTest.shouldShowOperatorNameView)

            // THEN we still show the operator name view if NoHunBehavior flag is enabled
            assertThat(latest).isTrue()
        }

    @Test
    fun isClockVisible_allowedByDisableFlags_visible() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isClockVisible)
            transitionKeyguardToGone()

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE)

            assertThat(latest!!.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    fun isClockVisible_notAllowedByDisableFlags_invisible() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isClockVisible)
            transitionKeyguardToGone()

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_CLOCK, DISABLE2_NONE)

            assertThat(latest!!.visibility).isEqualTo(View.INVISIBLE)
        }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun isClockVisible_allowedByDisableFlags_hunPinnedByUser_visible() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isClockVisible)
            transitionKeyguardToGone()

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE)
            // there is an active HUN
            headsUpNotificationRepository.setNotifications(
                UnconfinedFakeHeadsUpRowRepository(
                    key = "key",
                    pinnedStatus = MutableStateFlow(PinnedStatus.PinnedByUser),
                )
            )

            assertThat(latest!!.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    @DisableFlags(StatusBarNoHunBehavior.FLAG_NAME)
    fun isClockVisible_allowedByDisableFlags_hunPinnedBySystem_noHunBehaviorFlagOff_notVisible() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isClockVisible)
            transitionKeyguardToGone()

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE)
            // there is an active HUN
            headsUpNotificationRepository.setNotifications(
                UnconfinedFakeHeadsUpRowRepository(
                    key = "key",
                    pinnedStatus = MutableStateFlow(PinnedStatus.PinnedBySystem),
                )
            )

            assertThat(latest!!.visibility).isEqualTo(View.INVISIBLE)
        }

    @Test
    @EnableFlags(StatusBarNoHunBehavior.FLAG_NAME)
    fun isClockVisible_allowedByDisableFlags_hunPinnedBySystem_noHunBehaviorFlagOn_visible() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isClockVisible)
            transitionKeyguardToGone()

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE)
            // WHEN there is an active HUN
            headsUpNotificationRepository.setNotifications(
                UnconfinedFakeHeadsUpRowRepository(
                    key = "key",
                    pinnedStatus = MutableStateFlow(PinnedStatus.PinnedBySystem),
                )
            )

            // THEN we still show the clock view if NoHunBehavior flag is enabled
            assertThat(latest!!.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    @DisableFlags(StatusBarNoHunBehavior.FLAG_NAME)
    fun isClockVisible_allowedByDisableFlags_hunBecomesInactive_visibleAgain() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isClockVisible)
            transitionKeyguardToGone()

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE)
            // there is an active HUN pinned by the system
            headsUpNotificationRepository.setNotifications(
                UnconfinedFakeHeadsUpRowRepository(
                    key = "key",
                    pinnedStatus = MutableStateFlow(PinnedStatus.PinnedBySystem),
                )
            )
            assertThat(latest!!.visibility).isEqualTo(View.INVISIBLE)

            // hun goes away
            headsUpNotificationRepository.setNotifications(listOf())

            assertThat(latest!!.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    @DisableFlags(StatusBarNoHunBehavior.FLAG_NAME)
    fun isClockVisible_disableFlagsProhibitClock_hunBecomesInactive_neverVisible() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isClockVisible)
            transitionKeyguardToGone()

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_CLOCK, DISABLE2_NONE)
            // there is an active HUN
            headsUpNotificationRepository.setNotifications(
                UnconfinedFakeHeadsUpRowRepository(
                    key = "key",
                    pinnedStatus = MutableStateFlow(PinnedStatus.PinnedBySystem),
                )
            )

            assertThat(latest!!.visibility).isEqualTo(View.INVISIBLE)

            // hun goes away
            headsUpNotificationRepository.setNotifications(listOf())

            assertThat(latest!!.visibility).isEqualTo(View.INVISIBLE)
        }

    @Test
    fun isNotificationIconContainerVisible_allowedByDisableFlags_visible() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isNotificationIconContainerVisible)
            transitionKeyguardToGone()

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE)

            assertThat(latest!!.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    fun isNotificationIconContainerVisible_notAllowedByDisableFlags_gone() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isNotificationIconContainerVisible)
            transitionKeyguardToGone()

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_NOTIFICATION_ICONS, DISABLE2_NONE)

            assertThat(latest!!.visibility).isEqualTo(View.GONE)
        }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun isNotificationIconContainerVisible_anyChipShowing_PromotedNotifsOn() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isNotificationIconContainerVisible)
            transitionKeyguardToGone()

            kosmos.screenRecordRepository.screenRecordState.value = ScreenRecordModel.Recording

            assertThat(latest!!.visibility).isEqualTo(View.GONE)

            kosmos.screenRecordRepository.screenRecordState.value = ScreenRecordModel.DoingNothing

            assertThat(latest!!.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    @DisableFlags(StatusBarNotifChips.FLAG_NAME)
    fun isNotificationIconContainerVisible_anyChipShowing_PromotedNotifsOff() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.isNotificationIconContainerVisible)
            transitionKeyguardToGone()

            kosmos.screenRecordRepository.screenRecordState.value = ScreenRecordModel.Recording

            assertThat(latest!!.visibility).isEqualTo(View.GONE)

            kosmos.screenRecordRepository.screenRecordState.value = ScreenRecordModel.DoingNothing

            assertThat(latest!!.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    fun isSystemInfoVisible_allowedByDisableFlags_visible() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.systemInfoCombinedVis)
            transitionKeyguardToGone()

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE)

            assertThat(latest!!.baseVisibility.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    fun isSystemInfoVisible_notAllowedByDisableFlags_gone() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.systemInfoCombinedVis)
            transitionKeyguardToGone()

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_SYSTEM_INFO, DISABLE2_NONE)

            assertThat(latest!!.baseVisibility.visibility).isEqualTo(View.GONE)
        }

    @Test
    fun systemInfoCombineVis_animationsPassThrough() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.systemInfoCombinedVis)
            transitionKeyguardToGone()

            assertThat(latest!!.baseVisibility)
                .isEqualTo(VisibilityModel(visibility = View.VISIBLE, shouldAnimateChange = false))
            assertThat(latest!!.animationState).isEqualTo(Idle)

            // WHEN the animation state changes, but the visibility state doesn't change
            systemStatusEventAnimationRepository.animationState.value = AnimatingIn

            // THEN the visibility is the same
            assertThat(latest!!.baseVisibility)
                .isEqualTo(VisibilityModel(visibility = View.VISIBLE, shouldAnimateChange = false))
            // THEN the animation state updates
            assertThat(latest!!.animationState).isEqualTo(AnimatingIn)

            systemStatusEventAnimationRepository.animationState.value = AnimatingOut
            assertThat(latest!!.baseVisibility)
                .isEqualTo(VisibilityModel(visibility = View.VISIBLE, shouldAnimateChange = false))
            assertThat(latest!!.animationState).isEqualTo(AnimatingOut)
        }

    @Test
    @DisableSceneContainer
    fun lockscreenVisible_sceneFlagOff_noStatusBarViewsShown() =
        kosmos.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.systemInfoCombinedVis)

            fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.LOCKSCREEN,
                testScope = testScope,
            )

            assertThat(clockVisible!!.visibility).isEqualTo(View.INVISIBLE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.GONE)
            assertThat(systemInfoVisible!!.baseVisibility.visibility).isEqualTo(View.GONE)
        }

    @Test
    @EnableSceneContainer
    fun lockscreenVisible_sceneFlagOn_noStatusBarViewsShown() =
        kosmos.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.systemInfoCombinedVis)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Lockscreen)

            assertThat(clockVisible!!.visibility).isEqualTo(View.INVISIBLE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.GONE)
            assertThat(systemInfoVisible!!.baseVisibility.visibility).isEqualTo(View.GONE)
        }

    @Test
    @DisableSceneContainer
    fun bouncerVisible_sceneFlagOff_noStatusBarViewsShown() =
        kosmos.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.systemInfoCombinedVis)

            fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.PRIMARY_BOUNCER,
                testScope = testScope,
            )

            assertThat(clockVisible!!.visibility).isEqualTo(View.INVISIBLE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.GONE)
            assertThat(systemInfoVisible!!.baseVisibility.visibility).isEqualTo(View.GONE)
        }

    @Test
    @EnableSceneContainer
    fun bouncerVisible_sceneFlagOn_noStatusBarViewsShown() =
        kosmos.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.systemInfoCombinedVis)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Bouncer)

            assertThat(clockVisible!!.visibility).isEqualTo(View.INVISIBLE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.GONE)
            assertThat(systemInfoVisible!!.baseVisibility.visibility).isEqualTo(View.GONE)
        }

    @Test
    @DisableSceneContainer
    fun keyguardIsOccluded_sceneFlagOff_statusBarViewsShown() =
        kosmos.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.systemInfoCombinedVis)

            fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.OCCLUDED,
                testScope = testScope,
            )

            assertThat(clockVisible!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(systemInfoVisible!!.baseVisibility.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    @EnableSceneContainer
    fun keyguardIsOccluded_sceneFlagOn_statusBarViewsShown() =
        kosmos.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.systemInfoCombinedVis)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Lockscreen)
            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(true, taskInfo = null)

            assertThat(clockVisible!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(systemInfoVisible!!.baseVisibility.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    @DisableSceneContainer
    fun keyguardNotShown_sceneFlagOff_statusBarViewsShown() =
        kosmos.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.systemInfoCombinedVis)

            transitionKeyguardToGone()

            assertThat(clockVisible!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(systemInfoVisible!!.baseVisibility.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    @DisableSceneContainer
    fun shadeNotShown_sceneFlagOff_statusBarViewsShown() =
        kosmos.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.systemInfoCombinedVis)
            transitionKeyguardToGone()

            kosmos.shadeTestUtil.setShadeExpansion(0f)

            assertThat(clockVisible!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(systemInfoVisible!!.baseVisibility.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    @EnableSceneContainer
    fun keyguardNotShownAndShadeNotShown_sceneFlagOn_statusBarViewsShown() =
        kosmos.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.systemInfoCombinedVis)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Gone)

            assertThat(clockVisible!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(systemInfoVisible!!.baseVisibility.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    @DisableSceneContainer
    fun shadeShown_sceneFlagOff_noStatusBarViewsShown() =
        kosmos.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.systemInfoCombinedVis)
            transitionKeyguardToGone()

            kosmos.shadeTestUtil.setShadeExpansion(1f)

            assertThat(clockVisible!!.visibility).isEqualTo(View.INVISIBLE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.GONE)
            assertThat(systemInfoVisible!!.baseVisibility.visibility).isEqualTo(View.GONE)
        }

    @Test
    @EnableSceneContainer
    fun shadeShown_sceneFlagOn_noStatusBarViewsShown() =
        kosmos.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.systemInfoCombinedVis)
            transitionKeyguardToGone()

            kosmos.sceneContainerRepository.snapToScene(Scenes.Shade)

            assertThat(clockVisible!!.visibility).isEqualTo(View.INVISIBLE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.GONE)
            assertThat(systemInfoVisible!!.baseVisibility.visibility).isEqualTo(View.GONE)
        }

    @Test
    @DisableSceneContainer
    fun secureCameraActive_sceneFlagOff_noStatusBarViewsShown() =
        kosmos.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.systemInfoCombinedVis)

            // Secure camera is an occluding activity
            fakeKeyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.OCCLUDED,
                testScope = testScope,
            )
            kosmos.keyguardInteractor.onCameraLaunchDetected(CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP)

            assertThat(clockVisible!!.visibility).isEqualTo(View.INVISIBLE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.GONE)
            assertThat(systemInfoVisible!!.baseVisibility.visibility).isEqualTo(View.GONE)
        }

    @Test
    @EnableSceneContainer
    fun secureCameraActive_sceneFlagOn_noStatusBarViewsShown() =
        kosmos.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.systemInfoCombinedVis)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Lockscreen)
            // Secure camera is an occluding activity
            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(true, taskInfo = null)
            kosmos.keyguardInteractor.onCameraLaunchDetected(CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP)

            assertThat(clockVisible!!.visibility).isEqualTo(View.INVISIBLE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.GONE)
            assertThat(systemInfoVisible!!.baseVisibility.visibility).isEqualTo(View.GONE)
        }

    @Test
    fun areaTint_viewIsInDarkBounds_getsDarkTint() =
        kosmos.runTest {
            val displayId = testableContext.displayId
            fakeDarkIconRepository.darkState(displayId).value =
                SysuiDarkIconDispatcher.DarkChange(listOf(Rect(0, 0, 5, 5)), 0f, 0xAABBCC)

            val areaTint by collectLastValue(underTest.areaTint)

            val tint = areaTint?.tint(Rect(1, 1, 3, 3))

            assertThat(tint).isEqualTo(0xAABBCC)
        }

    @Test
    fun areaTint_viewIsNotInDarkBounds_getsDefaultTint() =
        kosmos.runTest {
            val displayId = testableContext.displayId
            fakeDarkIconRepository.darkState(displayId).value =
                SysuiDarkIconDispatcher.DarkChange(listOf(Rect(0, 0, 5, 5)), 0f, 0xAABBCC)

            val areaTint by collectLastValue(underTest.areaTint)

            val tint = areaTint?.tint(Rect(6, 6, 7, 7))

            assertThat(tint).isEqualTo(DarkIconDispatcher.DEFAULT_ICON_TINT)
        }

    @Test
    fun areaTint_viewIsInDarkBounds_darkBoundsChange_viewUpdates() =
        kosmos.runTest {
            val displayId = testableContext.displayId
            fakeDarkIconRepository.darkState(displayId).value =
                SysuiDarkIconDispatcher.DarkChange(listOf(Rect(0, 0, 5, 5)), 0f, 0xAABBCC)

            val areaTint by collectLastValue(underTest.areaTint)

            var tint = areaTint?.tint(Rect(1, 1, 3, 3))

            assertThat(tint).isEqualTo(0xAABBCC)

            // Dark region moves 5px to the right
            fakeDarkIconRepository.darkState(displayId).value =
                SysuiDarkIconDispatcher.DarkChange(listOf(Rect(5, 0, 10, 5)), 0f, 0xAABBCC)

            tint = areaTint?.tint(Rect(1, 1, 3, 3))

            assertThat(tint).isEqualTo(DarkIconDispatcher.DEFAULT_ICON_TINT)
        }

    @Test
    fun iconBlockList_followsInteractor() =
        kosmos.runTest {
            setHomeStatusBarIconBlockList(listOf("icon1", "icon2"))

            val latest by collectLastValue(underTest.iconBlockList)

            assertThat(latest).containsExactly("icon1", "icon2")
        }

    private fun activeNotificationsStore(notifications: List<ActiveNotificationModel>) =
        ActiveNotificationsStore.Builder()
            .apply { notifications.forEach(::addIndividualNotif) }
            .build()

    private val testNotifications by lazy {
        listOf(activeNotificationModel(key = "notif1"), activeNotificationModel(key = "notif2"))
    }

    private suspend fun Kosmos.transitionKeyguardToGone() {
        fakeKeyguardTransitionRepository.sendTransitionSteps(
            from = KeyguardState.LOCKSCREEN,
            to = KeyguardState.GONE,
            testScope = testScope,
        )
    }
}
