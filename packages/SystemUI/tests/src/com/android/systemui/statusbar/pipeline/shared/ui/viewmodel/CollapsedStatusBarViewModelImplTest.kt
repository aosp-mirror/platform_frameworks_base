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

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.assertLogsWtf
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.screenRecordRepository
import com.android.systemui.statusbar.chips.domain.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.NORMAL_PACKAGE
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.setUpPackageManagerForMediaProjection
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModelTest.Companion.assertIsScreenRecordChip
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModelTest.Companion.assertIsShareToAppChip
import com.android.systemui.statusbar.chips.ui.viewmodel.ongoingActivityChipsViewModel
import com.android.systemui.statusbar.data.model.StatusBarMode
import com.android.systemui.statusbar.data.repository.FakeStatusBarModeRepository.Companion.DISPLAY_ID
import com.android.systemui.statusbar.data.repository.fakeStatusBarModeRepository
import com.android.systemui.statusbar.notification.data.model.activeNotificationModel
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.domain.interactor.activeNotificationsInteractor
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor
import com.android.systemui.statusbar.phone.domain.interactor.lightsOutInteractor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class CollapsedStatusBarViewModelImplTest : SysuiTestCase() {
    private val kosmos = Kosmos().also {
        it.testCase = this
        it.testDispatcher = UnconfinedTestDispatcher()
    }

    private val testScope = kosmos.testScope

    private val statusBarModeRepository = kosmos.fakeStatusBarModeRepository
    private val activeNotificationListRepository = kosmos.activeNotificationListRepository
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository

    private val underTest =
        CollapsedStatusBarViewModelImpl(
            kosmos.lightsOutInteractor,
            kosmos.activeNotificationsInteractor,
            kosmos.keyguardTransitionInteractor,
            kosmos.ongoingActivityChipsViewModel,
            kosmos.applicationCoroutineScope,
        )

    @Before
    fun setUp() {
        setUpPackageManagerForMediaProjection(kosmos)
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
    fun ongoingActivityChip_matchesViewModel() =
        testScope.runTest {
            val latest by collectLastValue(underTest.ongoingActivityChip)

            kosmos.screenRecordRepository.screenRecordState.value = ScreenRecordModel.Recording

            assertIsScreenRecordChip(latest)

            kosmos.screenRecordRepository.screenRecordState.value = ScreenRecordModel.DoingNothing

            assertThat(latest).isEqualTo(OngoingActivityChipModel.Hidden)

            kosmos.fakeMediaProjectionRepository.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            assertIsShareToAppChip(latest)
        }

    private fun activeNotificationsStore(notifications: List<ActiveNotificationModel>) =
        ActiveNotificationsStore.Builder()
            .apply { notifications.forEach(::addIndividualNotif) }
            .build()

    private val testNotifications =
        listOf(
            activeNotificationModel(key = "notif1"),
            activeNotificationModel(key = "notif2"),
        )
}
