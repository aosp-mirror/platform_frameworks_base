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

package com.android.systemui.shade

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.latencyTracker
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.common.ui.view.fakeChoreographerUtils
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.testKosmos
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.any

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShadeDisplayChangeLatencyTrackerTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val configurationRepository = kosmos.fakeConfigurationRepository
    private val latencyTracker = kosmos.latencyTracker
    private val testScope = kosmos.testScope
    private val choreographerUtils = kosmos.fakeChoreographerUtils

    private val underTest = kosmos.shadeDisplayChangeLatencyTracker

    @Test
    fun onShadeDisplayChanging_afterMovedToDisplayAndDoFrameCompleted_atomReported() =
        testScope.runTest {
            underTest.onShadeDisplayChanging(1)

            verify(latencyTracker).onActionStart(any())
            verify(latencyTracker, never()).onActionEnd(any())

            sendOnMovedToDisplay(1)
            choreographerUtils.completeDoFrame()

            verify(latencyTracker).onActionEnd(any())
        }

    @Test
    fun onChange_doFrameTimesOut_previousCancelled() =
        testScope.runTest {
            underTest.onShadeDisplayChanging(1)

            verify(latencyTracker).onActionStart(any())
            verify(latencyTracker, never()).onActionEnd(any())

            sendOnMovedToDisplay(1)
            advanceTimeBy(100.seconds)

            verify(latencyTracker, never()).onActionEnd(any())
            verify(latencyTracker).onActionCancel(any())
        }

    @Test
    fun onChange_onMovedToDisplayTimesOut_cancelled() =
        testScope.runTest {
            underTest.onShadeDisplayChanging(1)

            verify(latencyTracker).onActionStart(any())

            choreographerUtils.completeDoFrame()
            advanceTimeBy(100.seconds)

            verify(latencyTracker).onActionCancel(any())
        }

    @Test
    fun onChange_whilePreviousWasInProgress_previousCancelledAndNewStarted() =
        testScope.runTest {
            underTest.onShadeDisplayChanging(1)

            verify(latencyTracker).onActionStart(any())

            underTest.onShadeDisplayChanging(2)

            verify(latencyTracker).onActionCancel(any())
            verify(latencyTracker, times(2)).onActionStart(any())
        }

    @Test
    fun onChange_multiple_multipleReported() =
        testScope.runTest {
            underTest.onShadeDisplayChanging(1)
            verify(latencyTracker).onActionStart(any())

            sendOnMovedToDisplay(1)
            choreographerUtils.completeDoFrame()

            verify(latencyTracker).onActionEnd(any())

            underTest.onShadeDisplayChanging(0)

            sendOnMovedToDisplay(0)
            choreographerUtils.completeDoFrame()

            verify(latencyTracker, times(2)).onActionStart(any())
            verify(latencyTracker, times(2)).onActionEnd(any())
        }

    private fun sendOnMovedToDisplay(displayId: Int) {
        configurationRepository.onMovedToDisplay(displayId)
    }
}
