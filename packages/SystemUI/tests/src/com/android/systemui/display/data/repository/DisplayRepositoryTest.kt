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

package com.android.systemui.display.data.repository

import android.hardware.display.DisplayManager
import android.os.Looper
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.Display
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.FlowValue
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.utils.os.FakeHandler
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class DisplayRepositoryTest : SysuiTestCase() {

    private val displayManager = mock<DisplayManager>()
    private val displayListener = kotlinArgumentCaptor<DisplayManager.DisplayListener>()

    private val testHandler = FakeHandler(Looper.getMainLooper())
    private val testScope = TestScope(UnconfinedTestDispatcher())

    private lateinit var displayRepository: DisplayRepositoryImpl

    @Before
    fun setup() {
        setDisplays(emptyList())
        displayRepository =
            DisplayRepositoryImpl(
                displayManager,
                testHandler,
                TestScope(UnconfinedTestDispatcher()),
                UnconfinedTestDispatcher()
            )
        verify(displayManager, never()).registerDisplayListener(any(), any())
    }

    @Test
    fun onFlowCollection_displayListenerRegistered() =
        testScope.runTest {
            val value by latestDisplayFlowValue()

            assertThat(value).isEmpty()

            verify(displayManager).registerDisplayListener(any(), eq(testHandler), anyLong())
        }

    @Test
    fun afterFlowCollection_displayListenerUnregistered() {
        testScope.runTest {
            val value by latestDisplayFlowValue()

            assertThat(value).isEmpty()

            verify(displayManager).registerDisplayListener(any(), eq(testHandler), anyLong())
        }
        verify(displayManager).unregisterDisplayListener(any())
    }

    @Test
    fun afterFlowCollection_multipleSusbcriptions_oneRemoved_displayListenerNotUnregistered() {
        testScope.runTest {
            val firstSubscriber by latestDisplayFlowValue()

            assertThat(firstSubscriber).isEmpty()
            verify(displayManager, times(1))
                .registerDisplayListener(displayListener.capture(), eq(testHandler), anyLong())

            val innerScope = TestScope()
            innerScope.runTest {
                val secondSubscriber by latestDisplayFlowValue()
                assertThat(secondSubscriber).isEmpty()

                // No new registration, just the precedent one.
                verify(displayManager, times(1))
                    .registerDisplayListener(any(), eq(testHandler), anyLong())
            }

            // Let's make sure it has *NOT* been unregistered, as there is still a subscriber.
            setDisplays(1)
            displayListener.value.onDisplayAdded(1)
            assertThat(firstSubscriber?.ids()).containsExactly(1)
        }

        // All subscribers are done, unregister should have been called.
        verify(displayManager).unregisterDisplayListener(any())
    }
    @Test
    fun onDisplayAdded_propagated() =
        testScope.runTest {
            val value by latestDisplayFlowValue()

            setDisplays(1)
            displayListener.value.onDisplayAdded(1)

            assertThat(value?.ids()).containsExactly(1)
        }

    @Test
    fun onDisplayRemoved_propagated() =
        testScope.runTest {
            val value by latestDisplayFlowValue()

            setDisplays(1, 2, 3, 4)
            displayListener.value.onDisplayAdded(1)
            displayListener.value.onDisplayAdded(2)
            displayListener.value.onDisplayAdded(3)
            displayListener.value.onDisplayAdded(4)

            setDisplays(1, 2, 3)
            displayListener.value.onDisplayRemoved(4)

            assertThat(value?.ids()).containsExactly(1, 2, 3)
        }

    @Test
    fun onDisplayChanged_propagated() =
        testScope.runTest {
            val value by latestDisplayFlowValue()

            setDisplays(1, 2, 3, 4)
            displayListener.value.onDisplayAdded(1)
            displayListener.value.onDisplayAdded(2)
            displayListener.value.onDisplayAdded(3)
            displayListener.value.onDisplayAdded(4)

            displayListener.value.onDisplayChanged(4)

            assertThat(value?.ids()).containsExactly(1, 2, 3, 4)
        }

    private fun Iterable<Display>.ids(): List<Int> = map { it.displayId }

    // Wrapper to capture the displayListener.
    private fun TestScope.latestDisplayFlowValue(): FlowValue<Set<Display>?> {
        val flowValue = collectLastValue(displayRepository.displays)
        verify(displayManager)
            .registerDisplayListener(displayListener.capture(), eq(testHandler), anyLong())
        return flowValue
    }

    private fun setDisplays(displays: List<Display>) {
        whenever(displayManager.displays).thenReturn(displays.toTypedArray())
    }

    private fun setDisplays(vararg ids: Int) {
        setDisplays(ids.map { display(it) })
    }

    private fun display(id: Int): Display {
        return mock<Display>().also { mockDisplay ->
            whenever(mockDisplay.displayId).thenReturn(id)
        }
    }
}
