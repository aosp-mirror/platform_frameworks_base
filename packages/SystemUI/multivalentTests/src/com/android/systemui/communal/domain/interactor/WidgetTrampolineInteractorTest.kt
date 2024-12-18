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

package com.android.systemui.communal.domain.interactor

import android.app.ActivityManager.RunningTaskInfo
import android.app.usage.UsageEvents
import android.content.pm.UserInfo
import android.service.dream.dreamManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.usagestats.data.repository.fakeUsageStatsRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.ActivityStarter.OnDismissAction
import com.android.systemui.plugins.activityStarter
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.shared.system.taskStackChangeListeners
import com.android.systemui.testKosmos
import com.android.systemui.util.time.fakeSystemClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class WidgetTrampolineInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val activityStarter = kosmos.activityStarter
    private val usageStatsRepository = kosmos.fakeUsageStatsRepository
    private val taskStackChangeListeners = kosmos.taskStackChangeListeners
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val userTracker = kosmos.fakeUserTracker
    private val systemClock = kosmos.fakeSystemClock

    private val underTest = kosmos.widgetTrampolineInteractor

    @Before
    fun setUp() {
        userTracker.set(listOf(MAIN_USER), 0)
        systemClock.setCurrentTimeMillis(testScope.currentTime)
    }

    @Test
    fun testNewTaskStartsWhileOnHub_triggersUnlock() =
        testScope.runTest {
            transition(from = KeyguardState.LOCKSCREEN, to = KeyguardState.GLANCEABLE_HUB)
            backgroundScope.launch { underTest.waitForActivityStartAndDismissKeyguard() }
            runCurrent()

            verify(activityStarter, never()).dismissKeyguardThenExecute(any(), anyOrNull(), any())
            moveTaskToFront()

            verify(activityStarter).dismissKeyguardThenExecute(any(), anyOrNull(), any())
        }

    @Test
    fun testNewTaskStartsWhileOnHub_stopsDream() =
        testScope.runTest {
            transition(from = KeyguardState.LOCKSCREEN, to = KeyguardState.GLANCEABLE_HUB)
            backgroundScope.launch { underTest.waitForActivityStartAndDismissKeyguard() }
            runCurrent()

            verify(activityStarter, never()).dismissKeyguardThenExecute(any(), anyOrNull(), any())
            moveTaskToFront()

            argumentCaptor<OnDismissAction>().apply {
                verify(activityStarter).dismissKeyguardThenExecute(capture(), anyOrNull(), any())

                firstValue.onDismiss()
                runCurrent()

                // Dream is stopped once keyguard is dismissed.
                verify(kosmos.dreamManager).stopDream()
            }
        }

    @Test
    fun testNewTaskStartsAfterExitingHub_doesNotTriggerUnlock() =
        testScope.runTest {
            transition(from = KeyguardState.LOCKSCREEN, to = KeyguardState.GLANCEABLE_HUB)
            backgroundScope.launch { underTest.waitForActivityStartAndDismissKeyguard() }
            runCurrent()

            transition(from = KeyguardState.GLANCEABLE_HUB, to = KeyguardState.LOCKSCREEN)
            moveTaskToFront()

            verify(activityStarter, never()).dismissKeyguardThenExecute(any(), anyOrNull(), any())
        }

    @Test
    fun testNewTaskStartsAfterTimeout_doesNotTriggerUnlock() =
        testScope.runTest {
            transition(from = KeyguardState.LOCKSCREEN, to = KeyguardState.GLANCEABLE_HUB)
            backgroundScope.launch { underTest.waitForActivityStartAndDismissKeyguard() }
            runCurrent()

            advanceTime(2.seconds)
            moveTaskToFront()

            verify(activityStarter, never()).dismissKeyguardThenExecute(any(), anyOrNull(), any())
        }

    @Test
    fun testActivityResumedWhileOnHub_triggersUnlock() =
        testScope.runTest {
            transition(from = KeyguardState.LOCKSCREEN, to = KeyguardState.GLANCEABLE_HUB)
            backgroundScope.launch { underTest.waitForActivityStartAndDismissKeyguard() }
            runCurrent()

            addActivityEvent(UsageEvents.Event.ACTIVITY_RESUMED)
            advanceTime(1.seconds)

            verify(activityStarter).dismissKeyguardThenExecute(any(), anyOrNull(), any())
        }

    @Test
    fun testActivityResumedAfterExitingHub_doesNotTriggerUnlock() =
        testScope.runTest {
            transition(from = KeyguardState.LOCKSCREEN, to = KeyguardState.GLANCEABLE_HUB)
            backgroundScope.launch { underTest.waitForActivityStartAndDismissKeyguard() }
            runCurrent()

            transition(from = KeyguardState.GLANCEABLE_HUB, to = KeyguardState.LOCKSCREEN)
            addActivityEvent(UsageEvents.Event.ACTIVITY_RESUMED)
            advanceTime(1.seconds)

            verify(activityStarter, never()).dismissKeyguardThenExecute(any(), anyOrNull(), any())
        }

    @Test
    fun testActivityDestroyed_doesNotTriggerUnlock() =
        testScope.runTest {
            transition(from = KeyguardState.LOCKSCREEN, to = KeyguardState.GLANCEABLE_HUB)
            backgroundScope.launch { underTest.waitForActivityStartAndDismissKeyguard() }
            runCurrent()

            addActivityEvent(UsageEvents.Event.ACTIVITY_DESTROYED)
            advanceTime(1.seconds)

            verify(activityStarter, never()).dismissKeyguardThenExecute(any(), anyOrNull(), any())
        }

    @Test
    fun testMultipleActivityEvents_triggersUnlockOnlyOnce() =
        testScope.runTest {
            transition(from = KeyguardState.LOCKSCREEN, to = KeyguardState.GLANCEABLE_HUB)
            backgroundScope.launch { underTest.waitForActivityStartAndDismissKeyguard() }
            runCurrent()

            addActivityEvent(UsageEvents.Event.ACTIVITY_RESUMED)
            advanceTime(10.milliseconds)
            addActivityEvent(UsageEvents.Event.ACTIVITY_RESUMED)
            advanceTime(1.seconds)

            verify(activityStarter, times(1)).dismissKeyguardThenExecute(any(), anyOrNull(), any())
        }

    private fun TestScope.advanceTime(duration: Duration) {
        systemClock.advanceTime(duration.inWholeMilliseconds)
        advanceTimeBy(duration)
    }

    private fun TestScope.addActivityEvent(type: Int) {
        usageStatsRepository.addEvent(
            instanceId = 1,
            user = MAIN_USER.userHandle,
            packageName = "pkg.test",
            timestamp = systemClock.currentTimeMillis(),
            type = type,
        )
        runCurrent()
    }

    private fun TestScope.moveTaskToFront() {
        taskStackChangeListeners.listenerImpl.onTaskMovedToFront(mock<RunningTaskInfo>())
        runCurrent()
    }

    private suspend fun TestScope.transition(from: KeyguardState, to: KeyguardState) {
        keyguardTransitionRepository.sendTransitionSteps(
            listOf(
                TransitionStep(
                    from = from,
                    to = to,
                    value = 0.1f,
                    transitionState = TransitionState.STARTED,
                    ownerName = "test",
                ),
                TransitionStep(
                    from = from,
                    to = to,
                    value = 1f,
                    transitionState = TransitionState.FINISHED,
                    ownerName = "test",
                ),
            ),
            testScope,
        )
        runCurrent()
    }

    private companion object {
        val MAIN_USER: UserInfo = UserInfo(0, "primary", UserInfo.FLAG_MAIN)
    }
}
