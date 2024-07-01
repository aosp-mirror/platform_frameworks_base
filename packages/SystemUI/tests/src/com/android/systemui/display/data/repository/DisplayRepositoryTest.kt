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
import android.testing.TestableLooper
import android.view.Display
import android.view.Display.TYPE_EXTERNAL
import android.view.Display.TYPE_INTERNAL
import androidx.test.ext.junit.runners.AndroidJUnit4
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

@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class DisplayRepositoryTest : SysuiTestCase() {

    private val displayManager = mock<DisplayManager>()
    private val displayListener = kotlinArgumentCaptor<DisplayManager.DisplayListener>()
    private val connectedDisplayListener = kotlinArgumentCaptor<DisplayManager.DisplayListener>()

    private val testHandler = FakeHandler(Looper.getMainLooper())
    private val testScope = TestScope(UnconfinedTestDispatcher())

    private lateinit var displayRepository: DisplayRepositoryImpl

    @Before
    fun setup() {
        setDisplays(emptyList())
        setAllDisplaysIncludingDisabled()
        displayRepository =
            DisplayRepositoryImpl(
                displayManager,
                testHandler,
                TestScope(UnconfinedTestDispatcher()),
                UnconfinedTestDispatcher()
            )
        verify(displayManager, never()).registerDisplayListener(any(), any())
        verify(displayManager, never()).getDisplays(any())
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
            sendOnDisplayAdded(1)
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
            sendOnDisplayAdded(1)

            assertThat(value?.ids()).containsExactly(1)
        }

    @Test
    fun onDisplayRemoved_propagated() =
        testScope.runTest {
            val value by latestDisplayFlowValue()

            setDisplays(1, 2, 3, 4)
            sendOnDisplayAdded(1)
            sendOnDisplayAdded(2)
            sendOnDisplayAdded(3)
            sendOnDisplayAdded(4)

            setDisplays(1, 2, 3)
            sendOnDisplayRemoved(4)

            assertThat(value?.ids()).containsExactly(1, 2, 3)
        }

    @Test
    fun onDisplayChanged_propagated() =
        testScope.runTest {
            val value by latestDisplayFlowValue()

            setDisplays(1, 2, 3, 4)
            sendOnDisplayAdded(1)
            sendOnDisplayAdded(2)
            sendOnDisplayAdded(3)
            sendOnDisplayAdded(4)

            displayListener.value.onDisplayChanged(4)

            assertThat(value?.ids()).containsExactly(1, 2, 3, 4)
        }

    @Test
    fun onDisplayConnected_pendingDisplayReceived() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1)

            assertThat(pendingDisplay!!.id).isEqualTo(1)
        }

    @Test
    fun onDisplayDisconnected_pendingDisplayNull() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()
            sendOnDisplayConnected(1)

            assertThat(pendingDisplay).isNotNull()

            sendOnDisplayDisconnected(1)

            assertThat(pendingDisplay).isNull()
        }

    @Test
    fun onDisplayDisconnected_unknownDisplay_doesNotSendNull() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()
            sendOnDisplayConnected(1)

            assertThat(pendingDisplay).isNotNull()

            sendOnDisplayDisconnected(2)

            assertThat(pendingDisplay).isNotNull()
        }

    @Test
    fun onDisplayConnected_multipleTimes_sendsOnlyTheMaximum() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1)
            sendOnDisplayConnected(2)

            assertThat(pendingDisplay!!.id).isEqualTo(2)
        }

    @Test
    fun onPendingDisplay_enable_displayEnabled() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1)
            pendingDisplay!!.enable()

            verify(displayManager).enableConnectedDisplay(eq(1))
        }

    @Test
    fun onPendingDisplay_enableBySysui_disabledBySomeoneElse_pendingDisplayStillIgnored() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1)
            pendingDisplay!!.enable()
            // to mock the display being really enabled:
            sendOnDisplayAdded(1)

            // Simulate the display being disabled by someone else. Now, sysui will have it in the
            // "pending displays" list again, but it should be ignored.
            sendOnDisplayRemoved(1)

            assertThat(pendingDisplay).isNull()
        }

    @Test
    fun onPendingDisplay_ignoredBySysui_enabledDisabledBySomeoneElse_pendingDisplayStillIgnored() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1)
            pendingDisplay!!.ignore()

            // to mock the display being enabled and disabled by someone else:
            sendOnDisplayAdded(1)
            sendOnDisplayRemoved(1)

            // Sysui already decided to ignore it, so the pending display should be null.
            assertThat(pendingDisplay).isNull()
        }

    @Test
    fun onPendingDisplay_disable_displayDisabled() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1)
            pendingDisplay!!.disable()

            verify(displayManager).disableConnectedDisplay(eq(1))
        }

    @Test
    fun onPendingDisplay_ignore_pendingDisplayNull() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()
            sendOnDisplayConnected(1)

            pendingDisplay!!.ignore()

            assertThat(pendingDisplay).isNull()
            verify(displayManager, never()).disableConnectedDisplay(eq(1))
            verify(displayManager, never()).enableConnectedDisplay(eq(1))
        }

    @Test
    fun onPendingDisplay_enabled_pendingDisplayNull() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1)
            assertThat(pendingDisplay).isNotNull()

            setDisplays(1)
            sendOnDisplayAdded(1)

            assertThat(pendingDisplay).isNull()
        }

    @Test
    fun onPendingDisplay_multipleConnected_oneEnabled_pendingDisplayNotNull() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1)
            sendOnDisplayConnected(2)

            assertThat(pendingDisplay).isNotNull()

            setDisplays(1)
            sendOnDisplayAdded(1)

            assertThat(pendingDisplay).isNotNull()
            assertThat(pendingDisplay!!.id).isEqualTo(2)

            setDisplays(1, 2)
            sendOnDisplayAdded(2)

            assertThat(pendingDisplay).isNull()
        }

    @Test
    fun pendingDisplay_connectedDisconnectedAndReconnected_expectedPendingDisplayState() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            // Plug the cable
            sendOnDisplayConnected(1)

            // Enable it
            assertThat(pendingDisplay).isNotNull()
            pendingDisplay!!.enable()

            // Enabled
            verify(displayManager).enableConnectedDisplay(1)
            setDisplays(1)
            sendOnDisplayAdded(1)

            // No more pending displays
            assertThat(pendingDisplay).isNull()

            // Let's disconnect the cable
            setDisplays()
            sendOnDisplayRemoved(1)
            sendOnDisplayDisconnected(1)

            assertThat(pendingDisplay).isNull()

            // Let's reconnect it
            sendOnDisplayConnected(1)

            assertThat(pendingDisplay).isNotNull()
        }

    @Test
    fun initialState_onePendingDisplayOnBoot_notNull() =
        testScope.runTest {
            // 1 is not enabled, but just connected. It should be seen as pending
            setAllDisplaysIncludingDisabled(0, 1)
            setDisplays(0) // 0 is enabled.
            verify(displayManager, never()).getDisplays(any())

            val pendingDisplay by collectLastValue(displayRepository.pendingDisplay)

            verify(displayManager).getDisplays(any())

            assertThat(pendingDisplay).isNotNull()
            assertThat(pendingDisplay!!.id).isEqualTo(1)
        }

    @Test
    fun onPendingDisplay_internalDisplay_ignored() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1, Display.TYPE_INTERNAL)

            assertThat(pendingDisplay).isNull()
        }

    @Test
    fun pendingDisplay_afterConfigChanged_doesNotChange() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1, TYPE_EXTERNAL)
            val initialPendingDisplay: DisplayRepository.PendingDisplay? = pendingDisplay
            assertThat(pendingDisplay).isNotNull()
            sendOnDisplayChanged(1)

            assertThat(initialPendingDisplay).isEqualTo(pendingDisplay)
        }

    @Test
    fun pendingDisplay_afterNewHigherDisplayConnected_changes() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1, TYPE_EXTERNAL)
            val initialPendingDisplay: DisplayRepository.PendingDisplay? = pendingDisplay
            assertThat(pendingDisplay).isNotNull()
            sendOnDisplayConnected(2, TYPE_EXTERNAL)

            assertThat(initialPendingDisplay).isNotEqualTo(pendingDisplay)
        }

    @Test
    fun onPendingDisplay_OneInternalAndOneExternalDisplay_internalIgnored() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1, TYPE_EXTERNAL)
            sendOnDisplayConnected(2, Display.TYPE_INTERNAL)

            assertThat(pendingDisplay!!.id).isEqualTo(1)
        }

    @Test
    fun onDisplayAdded_emitsDisplayAdditionEvent() =
        testScope.runTest {
            val display by lastDisplayAdditionEvent()

            sendOnDisplayAdded(1, TYPE_EXTERNAL)

            assertThat(display!!.displayId).isEqualTo(1)
            assertThat(display!!.type).isEqualTo(TYPE_EXTERNAL)
        }

    @Test
    fun defaultDisplayOff_changes() =
        testScope.runTest {
            val defaultDisplayOff by latestDefaultDisplayOffFlowValue()
            setDisplays(
                listOf(
                    display(
                        type = TYPE_INTERNAL,
                        id = Display.DEFAULT_DISPLAY,
                        state = Display.STATE_OFF
                    )
                )
            )
            displayListener.value.onDisplayChanged(Display.DEFAULT_DISPLAY)
            assertThat(defaultDisplayOff).isTrue()

            setDisplays(
                listOf(
                    display(
                        type = TYPE_INTERNAL,
                        id = Display.DEFAULT_DISPLAY,
                        state = Display.STATE_ON
                    )
                )
            )
            displayListener.value.onDisplayChanged(Display.DEFAULT_DISPLAY)
            assertThat(defaultDisplayOff).isFalse()
        }

    @Test
    fun displayFlow_startsWithDefaultDisplayBeforeAnyEvent() =
        testScope.runTest {
            setDisplays(Display.DEFAULT_DISPLAY)

            val value by latestDisplayFlowValue()

            assertThat(value?.ids()).containsExactly(Display.DEFAULT_DISPLAY)
        }

    private fun Iterable<Display>.ids(): List<Int> = map { it.displayId }

    // Wrapper to capture the displayListener.
    private fun TestScope.latestDisplayFlowValue(): FlowValue<Set<Display>?> {
        val flowValue = collectLastValue(displayRepository.displays)
        captureAddedRemovedListener()
        return flowValue
    }

    // Wrapper to capture the displayListener.
    private fun TestScope.latestDefaultDisplayOffFlowValue(): FlowValue<Boolean?> {
        val flowValue = collectLastValue(displayRepository.defaultDisplayOff)
        captureAddedRemovedListener()
        return flowValue
    }

    private fun TestScope.lastPendingDisplay(): FlowValue<DisplayRepository.PendingDisplay?> {
        val flowValue = collectLastValue(displayRepository.pendingDisplay)
        captureAddedRemovedListener()
        verify(displayManager)
            .registerDisplayListener(
                connectedDisplayListener.capture(),
                eq(testHandler),
                eq(DisplayManager.EVENT_FLAG_DISPLAY_CONNECTION_CHANGED)
            )
        return flowValue
    }

    private fun TestScope.lastDisplayAdditionEvent(): FlowValue<Display?> {
        val flowValue = collectLastValue(displayRepository.displayAdditionEvent)
        captureAddedRemovedListener()
        return flowValue
    }

    private fun captureAddedRemovedListener() {
        verify(displayManager)
            .registerDisplayListener(
                displayListener.capture(),
                eq(testHandler),
                eq(
                    DisplayManager.EVENT_FLAG_DISPLAY_ADDED or
                        DisplayManager.EVENT_FLAG_DISPLAY_CHANGED or
                        DisplayManager.EVENT_FLAG_DISPLAY_REMOVED
                )
            )
    }

    private fun sendOnDisplayAdded(id: Int, displayType: Int) {
        val mockDisplay = display(id = id, type = displayType)
        whenever(displayManager.getDisplay(eq(id))).thenReturn(mockDisplay)
        displayListener.value.onDisplayAdded(id)
    }

    private fun sendOnDisplayAdded(id: Int) {
        displayListener.value.onDisplayAdded(id)
    }

    private fun sendOnDisplayRemoved(id: Int) {
        displayListener.value.onDisplayRemoved(id)
    }

    private fun sendOnDisplayDisconnected(id: Int) {
        connectedDisplayListener.value.onDisplayDisconnected(id)
        whenever(displayManager.getDisplay(eq(id))).thenReturn(null)
    }

    private fun sendOnDisplayConnected(id: Int, displayType: Int = TYPE_EXTERNAL) {
        val mockDisplay = display(id = id, type = displayType)
        whenever(displayManager.getDisplay(eq(id))).thenReturn(mockDisplay)
        connectedDisplayListener.value.onDisplayConnected(id)
    }

    private fun sendOnDisplayChanged(id: Int) {
        connectedDisplayListener.value.onDisplayChanged(id)
    }

    private fun setDisplays(displays: List<Display>) {
        whenever(displayManager.displays).thenReturn(displays.toTypedArray())
        displays.forEach { display ->
            whenever(displayManager.getDisplay(eq(display.displayId))).thenReturn(display)
        }
    }

    private fun setAllDisplaysIncludingDisabled(vararg ids: Int) {
        val displays = ids.map { display(type = TYPE_EXTERNAL, id = it) }.toTypedArray()
        whenever(
                displayManager.getDisplays(
                    eq(DisplayManager.DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED)
                )
            )
            .thenReturn(displays)
        displays.forEach { display ->
            whenever(displayManager.getDisplay(eq(display.displayId))).thenReturn(display)
        }
    }

    private fun setDisplays(vararg ids: Int) {
        setDisplays(ids.map { display(type = TYPE_EXTERNAL, id = it) })
    }
}
