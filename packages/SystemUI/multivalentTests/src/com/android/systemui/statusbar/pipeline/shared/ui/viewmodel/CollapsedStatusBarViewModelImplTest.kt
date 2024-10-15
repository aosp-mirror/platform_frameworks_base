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

import android.app.StatusBarManager.DISABLE2_NONE
import android.app.StatusBarManager.DISABLE_CLOCK
import android.app.StatusBarManager.DISABLE_NONE
import android.app.StatusBarManager.DISABLE_NOTIFICATION_ICONS
import android.app.StatusBarManager.DISABLE_SYSTEM_INFO
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.keyguardOcclusionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.assertLogsWtf
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.scene.data.repository.sceneContainerRepository
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.screenRecordRepository
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.NORMAL_PACKAGE
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.setUpPackageManagerForMediaProjection
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModelTest.Companion.assertIsScreenRecordChip
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModelTest.Companion.assertIsShareToAppChip
import com.android.systemui.statusbar.data.model.StatusBarMode
import com.android.systemui.statusbar.data.repository.FakeStatusBarModeRepository.Companion.DISPLAY_ID
import com.android.systemui.statusbar.data.repository.fakeStatusBarModeRepository
import com.android.systemui.statusbar.disableflags.data.model.DisableFlagsModel
import com.android.systemui.statusbar.disableflags.data.repository.fakeDisableFlagsRepository
import com.android.systemui.statusbar.notification.data.model.activeNotificationModel
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CollapsedStatusBarViewModelImplTest : SysuiTestCase() {
    private val kosmos =
        Kosmos().also {
            it.testCase = this
            it.testDispatcher = UnconfinedTestDispatcher()
        }

    private val testScope = kosmos.testScope

    private val statusBarModeRepository = kosmos.fakeStatusBarModeRepository
    private val activeNotificationListRepository = kosmos.activeNotificationListRepository
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val disableFlagsRepository = kosmos.fakeDisableFlagsRepository

    private lateinit var underTest: CollapsedStatusBarViewModel

    @Before
    fun setUp() {
        setUpPackageManagerForMediaProjection(kosmos)
        // Initialize here because some flags are checked when this class is constructed
        underTest = kosmos.collapsedStatusBarViewModel
    }

    @Test
    fun isTransitioningFromLockscreenToOccluded_started_isTrue() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isTransitioningFromLockscreenToOccluded)

            keyguardTransitionRepository.sendTransitionStep(
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
        testScope.runTest {
            val latest by collectLastValue(underTest.isTransitioningFromLockscreenToOccluded)

            keyguardTransitionRepository.sendTransitionStep(
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
        testScope.runTest {
            val latest by collectLastValue(underTest.isTransitioningFromLockscreenToOccluded)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.OCCLUDED,
                testScope.testScheduler,
            )

            assertThat(latest).isFalse()
        }

    @Test
    fun isTransitioningFromLockscreenToOccluded_canceled_isFalse() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isTransitioningFromLockscreenToOccluded)

            keyguardTransitionRepository.sendTransitionStep(
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
        testScope.runTest {
            val latest by collectLastValue(underTest.isTransitioningFromLockscreenToOccluded)

            keyguardTransitionRepository.sendTransitionStep(
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
        testScope.runTest {
            val latest by collectLastValue(underTest.isTransitioningFromLockscreenToOccluded)

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.OCCLUDED,
                    value = 0f,
                    TransitionState.RUNNING,
                )
            )

            assertThat(latest).isTrue()

            // WHEN the repo updates the transition to finished
            keyguardTransitionRepository.sendTransitionStep(
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
        testScope.runTest {
            val emissions by collectValues(underTest.transitionFromLockscreenToDreamStartedEvent)

            keyguardTransitionRepository.sendTransitionStep(
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
        testScope.runTest {
            val emissions by collectValues(underTest.transitionFromLockscreenToDreamStartedEvent)

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.DREAMING,
                    value = 0f,
                    TransitionState.STARTED,
                )
            )

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.DREAMING,
                    value = 0f,
                    TransitionState.STARTED,
                )
            )

            keyguardTransitionRepository.sendTransitionStep(
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
        testScope.runTest {
            val emissions by collectValues(underTest.transitionFromLockscreenToDreamStartedEvent)

            keyguardTransitionRepository.sendTransitionStep(
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
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.DREAMING,
                    value = .1f,
                    TransitionState.RUNNING,
                )
            )

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.DREAMING,
                    value = .2f,
                    TransitionState.RUNNING,
                )
            )

            keyguardTransitionRepository.sendTransitionStep(
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
        testScope.runTest {
            val emissions by collectValues(underTest.transitionFromLockscreenToDreamStartedEvent)

            keyguardTransitionRepository.sendTransitionStep(
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
        testScope.runTest {
            val emissions by collectValues(underTest.transitionFromLockscreenToDreamStartedEvent)

            keyguardTransitionRepository.sendTransitionStep(
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
        testScope.runTest {
            statusBarModeRepository.defaultDisplay.statusBarMode.value =
                StatusBarMode.LIGHTS_OUT_TRANSPARENT
            activeNotificationListRepository.activeNotifications.value =
                activeNotificationsStore(testNotifications)

            val actual by collectLastValue(underTest.areNotificationsLightsOut(DISPLAY_ID))

            assertThat(actual).isTrue()
        }

    @Test
    @EnableFlags(NotificationsLiveDataStoreRefactor.FLAG_NAME)
    fun areNotificationsLightsOut_lowProfileWithoutNotifications_false() =
        testScope.runTest {
            statusBarModeRepository.defaultDisplay.statusBarMode.value =
                StatusBarMode.LIGHTS_OUT_TRANSPARENT
            activeNotificationListRepository.activeNotifications.value =
                activeNotificationsStore(emptyList())

            val actual by collectLastValue(underTest.areNotificationsLightsOut(DISPLAY_ID))

            assertThat(actual).isFalse()
        }

    @Test
    @EnableFlags(NotificationsLiveDataStoreRefactor.FLAG_NAME)
    fun areNotificationsLightsOut_defaultStatusBarModeWithoutNotifications_false() =
        testScope.runTest {
            statusBarModeRepository.defaultDisplay.statusBarMode.value = StatusBarMode.TRANSPARENT
            activeNotificationListRepository.activeNotifications.value =
                activeNotificationsStore(emptyList())

            val actual by collectLastValue(underTest.areNotificationsLightsOut(DISPLAY_ID))

            assertThat(actual).isFalse()
        }

    @Test
    @EnableFlags(NotificationsLiveDataStoreRefactor.FLAG_NAME)
    fun areNotificationsLightsOut_defaultStatusBarModeWithNotifications_false() =
        testScope.runTest {
            statusBarModeRepository.defaultDisplay.statusBarMode.value = StatusBarMode.TRANSPARENT
            activeNotificationListRepository.activeNotifications.value =
                activeNotificationsStore(testNotifications)

            val actual by collectLastValue(underTest.areNotificationsLightsOut(DISPLAY_ID))

            assertThat(actual).isFalse()
        }

    @Test
    @DisableFlags(NotificationsLiveDataStoreRefactor.FLAG_NAME)
    fun areNotificationsLightsOut_requiresFlagEnabled() =
        testScope.runTest {
            assertLogsWtf {
                val flow = underTest.areNotificationsLightsOut(DISPLAY_ID)
                assertThat(flow).isEqualTo(emptyFlow<Boolean>())
            }
        }

    @Test
    fun primaryOngoingActivityChip_matchesViewModel() =
        testScope.runTest {
            val latest by collectLastValue(underTest.primaryOngoingActivityChip)

            kosmos.screenRecordRepository.screenRecordState.value = ScreenRecordModel.Recording

            assertIsScreenRecordChip(latest)

            kosmos.screenRecordRepository.screenRecordState.value = ScreenRecordModel.DoingNothing

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)

            kosmos.fakeMediaProjectionRepository.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            assertIsShareToAppChip(latest)
        }

    @Test
    fun isHomeStatusBarAllowedByScene_sceneLockscreen_notOccluded_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isHomeStatusBarAllowedByScene)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Lockscreen)
            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(false, taskInfo = null)

            assertThat(latest).isFalse()
        }

    @Test
    fun isHomeStatusBarAllowedByScene_sceneLockscreen_occluded_true() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isHomeStatusBarAllowedByScene)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Lockscreen)
            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(true, taskInfo = null)

            assertThat(latest).isTrue()
        }

    @Test
    fun isHomeStatusBarAllowedByScene_sceneBouncer_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isHomeStatusBarAllowedByScene)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Bouncer)

            assertThat(latest).isFalse()
        }

    @Test
    fun isHomeStatusBarAllowedByScene_sceneCommunal_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isHomeStatusBarAllowedByScene)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Communal)

            assertThat(latest).isFalse()
        }

    @Test
    fun isHomeStatusBarAllowedByScene_sceneShade_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isHomeStatusBarAllowedByScene)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Shade)

            assertThat(latest).isFalse()
        }

    @Test
    fun isHomeStatusBarAllowedByScene_sceneGone_true() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isHomeStatusBarAllowedByScene)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Gone)

            assertThat(latest).isTrue()
        }

    @Test
    fun isClockVisible_allowedByDisableFlags_visible() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isClockVisible)
            transitionKeyguardToGone()

            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE)

            assertThat(latest!!.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    fun isClockVisible_notAllowedByDisableFlags_gone() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isClockVisible)
            transitionKeyguardToGone()

            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_CLOCK, DISABLE2_NONE)

            assertThat(latest!!.visibility).isEqualTo(View.GONE)
        }

    @Test
    fun isNotificationIconContainerVisible_allowedByDisableFlags_visible() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isNotificationIconContainerVisible)
            transitionKeyguardToGone()

            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE)

            assertThat(latest!!.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    fun isNotificationIconContainerVisible_notAllowedByDisableFlags_gone() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isNotificationIconContainerVisible)
            transitionKeyguardToGone()

            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_NOTIFICATION_ICONS, DISABLE2_NONE)

            assertThat(latest!!.visibility).isEqualTo(View.GONE)
        }

    @Test
    fun isSystemInfoVisible_allowedByDisableFlags_visible() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isSystemInfoVisible)
            transitionKeyguardToGone()

            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE)

            assertThat(latest!!.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    fun isSystemInfoVisible_notAllowedByDisableFlags_gone() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isSystemInfoVisible)
            transitionKeyguardToGone()

            disableFlagsRepository.disableFlags.value =
                DisableFlagsModel(DISABLE_SYSTEM_INFO, DISABLE2_NONE)

            assertThat(latest!!.visibility).isEqualTo(View.GONE)
        }

    @Test
    @DisableSceneContainer
    fun lockscreenVisible_sceneFlagOff_noStatusBarViewsShown() =
        testScope.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.isSystemInfoVisible)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.GONE,
                to = KeyguardState.LOCKSCREEN,
                testScope = this,
            )

            assertThat(clockVisible!!.visibility).isEqualTo(View.GONE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.GONE)
            assertThat(systemInfoVisible!!.visibility).isEqualTo(View.GONE)
        }

    @Test
    @EnableSceneContainer
    fun lockscreenVisible_sceneFlagOn_noStatusBarViewsShown() =
        testScope.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.isSystemInfoVisible)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Lockscreen)

            assertThat(clockVisible!!.visibility).isEqualTo(View.GONE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.GONE)
            assertThat(systemInfoVisible!!.visibility).isEqualTo(View.GONE)
        }

    @Test
    @DisableSceneContainer
    fun bouncerVisible_sceneFlagOff_noStatusBarViewsShown() =
        testScope.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.isSystemInfoVisible)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.PRIMARY_BOUNCER,
                testScope = this,
            )

            assertThat(clockVisible!!.visibility).isEqualTo(View.GONE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.GONE)
            assertThat(systemInfoVisible!!.visibility).isEqualTo(View.GONE)
        }

    @Test
    @EnableSceneContainer
    fun bouncerVisible_sceneFlagOn_noStatusBarViewsShown() =
        testScope.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.isSystemInfoVisible)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Bouncer)

            assertThat(clockVisible!!.visibility).isEqualTo(View.GONE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.GONE)
            assertThat(systemInfoVisible!!.visibility).isEqualTo(View.GONE)
        }

    @Test
    @DisableSceneContainer
    fun keyguardIsOccluded_sceneFlagOff_statusBarViewsShown() =
        testScope.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.isSystemInfoVisible)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.OCCLUDED,
                testScope = this,
            )

            assertThat(clockVisible!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(systemInfoVisible!!.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    @EnableSceneContainer
    fun keyguardIsOccluded_sceneFlagOn_statusBarViewsShown() =
        testScope.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.isSystemInfoVisible)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Lockscreen)
            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(true, taskInfo = null)

            assertThat(clockVisible!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(systemInfoVisible!!.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    @DisableSceneContainer
    fun keyguardNotShown_sceneFlagOff_statusBarViewsShown() =
        testScope.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.isSystemInfoVisible)

            transitionKeyguardToGone()

            assertThat(clockVisible!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(systemInfoVisible!!.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    @DisableSceneContainer
    fun shadeNotShown_sceneFlagOff_statusBarViewsShown() =
        testScope.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.isSystemInfoVisible)
            transitionKeyguardToGone()

            kosmos.shadeTestUtil.setShadeExpansion(0f)

            assertThat(clockVisible!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(systemInfoVisible!!.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    @EnableSceneContainer
    fun keyguardNotShownAndShadeNotShown_sceneFlagOn_statusBarViewsShown() =
        testScope.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.isSystemInfoVisible)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Gone)

            assertThat(clockVisible!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(systemInfoVisible!!.visibility).isEqualTo(View.VISIBLE)
        }

    @Test
    @DisableSceneContainer
    fun shadeShown_sceneFlagOff_noStatusBarViewsShown() =
        testScope.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.isSystemInfoVisible)
            transitionKeyguardToGone()

            kosmos.shadeTestUtil.setShadeExpansion(1f)

            assertThat(clockVisible!!.visibility).isEqualTo(View.GONE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.GONE)
            assertThat(systemInfoVisible!!.visibility).isEqualTo(View.GONE)
        }

    @Test
    @EnableSceneContainer
    fun shadeShown_sceneFlagOn_noStatusBarViewsShown() =
        testScope.runTest {
            val clockVisible by collectLastValue(underTest.isClockVisible)
            val notifIconsVisible by collectLastValue(underTest.isNotificationIconContainerVisible)
            val systemInfoVisible by collectLastValue(underTest.isSystemInfoVisible)
            transitionKeyguardToGone()

            kosmos.sceneContainerRepository.snapToScene(Scenes.Shade)

            assertThat(clockVisible!!.visibility).isEqualTo(View.GONE)
            assertThat(notifIconsVisible!!.visibility).isEqualTo(View.GONE)
            assertThat(systemInfoVisible!!.visibility).isEqualTo(View.GONE)
        }

    private fun activeNotificationsStore(notifications: List<ActiveNotificationModel>) =
        ActiveNotificationsStore.Builder()
            .apply { notifications.forEach(::addIndividualNotif) }
            .build()

    private val testNotifications =
        listOf(activeNotificationModel(key = "notif1"), activeNotificationModel(key = "notif2"))

    private suspend fun transitionKeyguardToGone() {
        keyguardTransitionRepository.sendTransitionSteps(
            from = KeyguardState.LOCKSCREEN,
            to = KeyguardState.GONE,
            testScope = testScope,
        )
    }
}
