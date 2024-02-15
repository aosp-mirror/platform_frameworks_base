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
import com.android.systemui.CoroutineTestScopeModule
import com.android.systemui.SysUITestComponent
import com.android.systemui.SysUITestModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.collectLastValue
import com.android.systemui.collectValues
import com.android.systemui.communal.dagger.CommunalModule
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.log.assertLogsWtf
import com.android.systemui.runTest
import com.android.systemui.statusbar.data.model.StatusBarMode
import com.android.systemui.statusbar.data.repository.FakeStatusBarModeRepository
import com.android.systemui.statusbar.data.repository.FakeStatusBarModeRepository.Companion.DISPLAY_ID
import com.android.systemui.statusbar.notification.data.model.activeNotificationModel
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor
import com.google.common.truth.Truth.assertThat
import dagger.BindsInstance
import dagger.Component
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test

@SmallTest
class CollapsedStatusBarViewModelImplTest : SysuiTestCase() {

    @SysUISingleton
    @Component(
        modules =
            [
                SysUITestModule::class,
                CommunalModule::class,
            ]
    )
    interface TestComponent : SysUITestComponent<CollapsedStatusBarViewModelImpl> {
        val statusBarModeRepository: FakeStatusBarModeRepository
        val activeNotificationListRepository: ActiveNotificationListRepository
        val keyguardTransitionRepository: FakeKeyguardTransitionRepository

        @Component.Factory
        interface Factory {
            fun create(
                @BindsInstance test: SysuiTestCase,
                testScope: CoroutineTestScopeModule,
            ): TestComponent
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val testComponent: TestComponent =
        DaggerCollapsedStatusBarViewModelImplTest_TestComponent.factory()
            .create(
                test = this,
                testScope = CoroutineTestScopeModule(TestScope(UnconfinedTestDispatcher())),
            )

    @Test
    fun isTransitioningFromLockscreenToOccluded_started_isTrue() =
        testComponent.runTest {
            val job = underTest.isTransitioningFromLockscreenToOccluded.launchIn(testScope)

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.OCCLUDED,
                    value = 0f,
                    TransitionState.STARTED,
                )
            )

            assertThat(underTest.isTransitioningFromLockscreenToOccluded.value).isTrue()

            job.cancel()
        }

    @Test
    fun isTransitioningFromLockscreenToOccluded_running_isTrue() =
        testComponent.runTest {
            val job = underTest.isTransitioningFromLockscreenToOccluded.launchIn(testScope)

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.OCCLUDED,
                    value = 0f,
                    TransitionState.RUNNING,
                )
            )

            assertThat(underTest.isTransitioningFromLockscreenToOccluded.value).isTrue()

            job.cancel()
        }

    @Test
    fun isTransitioningFromLockscreenToOccluded_finished_isFalse() =
        testComponent.runTest {
            val job = underTest.isTransitioningFromLockscreenToOccluded.launchIn(testScope)

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.OCCLUDED,
                testScope.testScheduler,
            )

            assertThat(underTest.isTransitioningFromLockscreenToOccluded.value).isFalse()

            job.cancel()
        }

    @Test
    fun isTransitioningFromLockscreenToOccluded_canceled_isFalse() =
        testComponent.runTest {
            val job = underTest.isTransitioningFromLockscreenToOccluded.launchIn(testScope)

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.OCCLUDED,
                    value = 0f,
                    TransitionState.CANCELED,
                )
            )

            assertThat(underTest.isTransitioningFromLockscreenToOccluded.value).isFalse()

            job.cancel()
        }

    @Test
    fun isTransitioningFromLockscreenToOccluded_irrelevantTransition_isFalse() =
        testComponent.runTest {
            val job = underTest.isTransitioningFromLockscreenToOccluded.launchIn(testScope)

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.AOD,
                    KeyguardState.LOCKSCREEN,
                    value = 0f,
                    TransitionState.RUNNING,
                )
            )

            assertThat(underTest.isTransitioningFromLockscreenToOccluded.value).isFalse()

            job.cancel()
        }

    @Test
    fun isTransitioningFromLockscreenToOccluded_followsRepoUpdates() =
        testComponent.runTest {
            val job = underTest.isTransitioningFromLockscreenToOccluded.launchIn(testScope)

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.LOCKSCREEN,
                    KeyguardState.OCCLUDED,
                    value = 0f,
                    TransitionState.RUNNING,
                )
            )

            assertThat(underTest.isTransitioningFromLockscreenToOccluded.value).isTrue()

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
            assertThat(underTest.isTransitioningFromLockscreenToOccluded.value).isFalse()

            job.cancel()
        }

    @Test
    fun transitionFromLockscreenToDreamStartedEvent_started_emitted() =
        testComponent.runTest {
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
        testComponent.runTest {
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
        testComponent.runTest {
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
        testComponent.runTest {
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
        testComponent.runTest {
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
        testComponent.runTest {
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
        testComponent.runTest {
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
        testComponent.runTest {
            statusBarModeRepository.defaultDisplay.statusBarMode.value = StatusBarMode.TRANSPARENT
            activeNotificationListRepository.activeNotifications.value =
                activeNotificationsStore(emptyList())

            val actual by collectLastValue(underTest.areNotificationsLightsOut(DISPLAY_ID))

            assertThat(actual).isFalse()
        }

    @Test
    @EnableFlags(NotificationsLiveDataStoreRefactor.FLAG_NAME)
    fun areNotificationsLightsOut_defaultStatusBarModeWithNotifications_false() =
        testComponent.runTest {
            statusBarModeRepository.defaultDisplay.statusBarMode.value = StatusBarMode.TRANSPARENT
            activeNotificationListRepository.activeNotifications.value =
                activeNotificationsStore(testNotifications)

            val actual by collectLastValue(underTest.areNotificationsLightsOut(DISPLAY_ID))

            assertThat(actual).isFalse()
        }

    @Test
    @DisableFlags(NotificationsLiveDataStoreRefactor.FLAG_NAME)
    fun areNotificationsLightsOut_requiresFlagEnabled() =
        testComponent.runTest {
            assertLogsWtf {
                val flow = underTest.areNotificationsLightsOut(DISPLAY_ID)
                assertThat(flow).isEqualTo(emptyFlow<Boolean>())
            }
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
