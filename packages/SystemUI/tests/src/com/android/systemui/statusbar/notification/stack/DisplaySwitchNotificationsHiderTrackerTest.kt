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
package com.android.systemui.statusbar.notification.stack

import androidx.test.filters.SmallTest
import com.android.internal.util.LatencyTracker
import com.android.internal.util.LatencyTracker.ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE
import com.android.internal.util.LatencyTracker.ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE_WITH_SHADE_OPEN
import com.android.systemui.SysuiTestCase
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class DisplaySwitchNotificationsHiderTrackerTest : SysuiTestCase() {

    private val testScope = TestScope()
    private val shadeInteractor = mock<ShadeInteractor>()
    private val latencyTracker = mock<LatencyTracker>()

    private val shouldHideFlow = MutableStateFlow(false)
    private val shadeExpandedFlow = MutableStateFlow(false)

    private val tracker = DisplaySwitchNotificationsHiderTracker(shadeInteractor, latencyTracker)

    @Before
    fun setup() {
        whenever(shadeInteractor.isAnyExpanded).thenReturn(shadeExpandedFlow)
    }

    @Test
    fun notificationsBecomeHidden_tracksHideActionStart() = testScope.runTest {
        startTracking()

        shouldHideFlow.value = true
        runCurrent()

        verify(latencyTracker).onActionStart(HIDE_NOTIFICATIONS_ACTION)
    }

    @Test
    fun notificationsBecomeVisibleAfterHidden_tracksHideActionEnd() = testScope.runTest {
        startTracking()

        shouldHideFlow.value = true
        runCurrent()
        clearInvocations(latencyTracker)
        shouldHideFlow.value = false
        runCurrent()

        verify(latencyTracker).onActionEnd(HIDE_NOTIFICATIONS_ACTION)
    }

    @Test
    fun notificationsBecomeHiddenWhenShadeIsClosed_doesNotTrackHideWhenVisibleActionStart() =
            testScope.runTest {
                shouldHideFlow.value = false
                shadeExpandedFlow.value = false
                startTracking()

                shouldHideFlow.value = true
                runCurrent()

                verify(latencyTracker, never())
                        .onActionStart(HIDE_NOTIFICATIONS_WHEN_VISIBLE_ACTION)
            }

    @Test
    fun notificationsBecomeHiddenWhenShadeIsOpen_tracksHideWhenVisibleActionStart() = testScope.runTest {
        shouldHideFlow.value = false
        shadeExpandedFlow.value = false
        startTracking()

        shouldHideFlow.value = true
        shadeExpandedFlow.value = true
        runCurrent()

        verify(latencyTracker).onActionStart(HIDE_NOTIFICATIONS_WHEN_VISIBLE_ACTION)
    }

    @Test
    fun shadeBecomesOpenWhenNotificationsHidden_tracksHideWhenVisibleActionStart() =
            testScope.runTest {
            shouldHideFlow.value = true
            shadeExpandedFlow.value = false
            startTracking()

            shadeExpandedFlow.value = true
            runCurrent()

            verify(latencyTracker).onActionStart(HIDE_NOTIFICATIONS_WHEN_VISIBLE_ACTION)
        }

    @Test
    fun notificationsBecomeVisibleWhenShadeIsOpen_tracksHideWhenVisibleActionEnd() = testScope.runTest {
        shouldHideFlow.value = false
        shadeExpandedFlow.value = false
        startTracking()
        shouldHideFlow.value = true
        shadeExpandedFlow.value = true
        runCurrent()
        clearInvocations(latencyTracker)

        shouldHideFlow.value = false
        runCurrent()

        verify(latencyTracker).onActionEnd(HIDE_NOTIFICATIONS_WHEN_VISIBLE_ACTION)
    }

    @Test
    fun shadeBecomesClosedWhenNotificationsHidden_tracksHideWhenVisibleActionEnd() = testScope.runTest {
        shouldHideFlow.value = false
        shadeExpandedFlow.value = false
        startTracking()
        shouldHideFlow.value = true
        shadeExpandedFlow.value = true
        runCurrent()
        clearInvocations(latencyTracker)

        shadeExpandedFlow.value = false
        runCurrent()

        verify(latencyTracker).onActionEnd(HIDE_NOTIFICATIONS_WHEN_VISIBLE_ACTION)
    }

    private fun TestScope.startTracking() {
        backgroundScope.launch { tracker.trackNotificationHideTime(shouldHideFlow) }
        backgroundScope.launch { tracker.trackNotificationHideTimeWhenVisible(shouldHideFlow) }
        runCurrent()
        clearInvocations(latencyTracker)
    }

    private companion object {
        const val HIDE_NOTIFICATIONS_ACTION = ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE
        const val HIDE_NOTIFICATIONS_WHEN_VISIBLE_ACTION =
                ACTION_NOTIFICATIONS_HIDDEN_FOR_MEASURE_WITH_SHADE_OPEN
    }
}
