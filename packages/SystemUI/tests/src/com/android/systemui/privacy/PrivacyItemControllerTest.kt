/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.privacy

import android.app.ActivityManager
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.privacy.logging.PrivacyLogger
import com.android.systemui.util.DeviceConfigProxy
import com.android.systemui.util.DeviceConfigProxyFake
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
@RunWithLooper
class PrivacyItemControllerTest : SysuiTestCase() {

    companion object {
        val CURRENT_USER_ID = ActivityManager.getCurrentUser()
        val TEST_UID = CURRENT_USER_ID * UserHandle.PER_USER_RANGE
        const val TEST_PACKAGE_NAME = "test"

        fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
        fun <T> any(): T = Mockito.any<T>()
    }

    @Mock
    private lateinit var callback: PrivacyItemController.Callback
    @Mock
    private lateinit var privacyConfig: PrivacyConfig
    @Mock
    private lateinit var privacyItemMonitor: PrivacyItemMonitor
    @Mock
    private lateinit var privacyItemMonitor2: PrivacyItemMonitor
    @Mock
    private lateinit var dumpManager: DumpManager
    @Mock
    private lateinit var logger: PrivacyLogger
    @Captor
    private lateinit var argCaptor: ArgumentCaptor<List<PrivacyItem>>
    @Captor
    private lateinit var argCaptorCallback: ArgumentCaptor<PrivacyItemMonitor.Callback>
    @Captor
    private lateinit var argCaptorConfigCallback: ArgumentCaptor<PrivacyConfig.Callback>

    private lateinit var privacyItemController: PrivacyItemController
    private lateinit var executor: FakeExecutor
    private lateinit var fakeClock: FakeSystemClock
    private lateinit var deviceConfigProxy: DeviceConfigProxy

    fun createPrivacyItemController(): PrivacyItemController {
        return PrivacyItemController(
                executor,
                executor,
                privacyConfig,
                setOf(privacyItemMonitor, privacyItemMonitor2),
                logger,
                fakeClock,
                dumpManager)
    }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        fakeClock = FakeSystemClock()
        executor = FakeExecutor(fakeClock)
        deviceConfigProxy = DeviceConfigProxyFake()
        privacyItemController = createPrivacyItemController()
    }

    @Test
    fun testStartListeningByAddingCallback() {
        privacyItemController.addCallback(callback)
        executor.runAllReady()
        verify(privacyItemMonitor).startListening(any())
        verify(privacyItemMonitor2).startListening(any())
        verify(callback).onPrivacyItemsChanged(anyList())
    }

    @Test
    fun testStopListeningByRemovingLastCallback() {
        privacyItemController.addCallback(callback)
        executor.runAllReady()
        verify(privacyItemMonitor, never()).stopListening()
        privacyItemController.removeCallback(callback)
        executor.runAllReady()
        verify(privacyItemMonitor).stopListening()
        verify(privacyItemMonitor2).stopListening()
        verify(callback).onPrivacyItemsChanged(emptyList())
    }

    @Test
    fun testPrivacyItemsAggregated() {
        val item1 = PrivacyItem(PrivacyType.TYPE_CAMERA,
                PrivacyApplication(TEST_PACKAGE_NAME, TEST_UID), 0)
        val item2 = PrivacyItem(PrivacyType.TYPE_MICROPHONE,
                PrivacyApplication(TEST_PACKAGE_NAME, TEST_UID), 1)
        doReturn(listOf(item1))
                .`when`(privacyItemMonitor).getActivePrivacyItems()
        doReturn(listOf(item2))
                .`when`(privacyItemMonitor2).getActivePrivacyItems()

        privacyItemController.addCallback(callback)
        executor.runAllReady()
        verify(callback).onPrivacyItemsChanged(capture(argCaptor))
        assertEquals(2, argCaptor.value.size)
        assertTrue(argCaptor.value.contains(item1))
        assertTrue(argCaptor.value.contains(item2))
    }

    @Test
    fun testDistinctItems() {
        doReturn(listOf(
                PrivacyItem(PrivacyType.TYPE_CAMERA,
                        PrivacyApplication(TEST_PACKAGE_NAME, TEST_UID), 0),
                PrivacyItem(PrivacyType.TYPE_CAMERA,
                        PrivacyApplication(TEST_PACKAGE_NAME, TEST_UID), 0)))
                .`when`(privacyItemMonitor).getActivePrivacyItems()
        doReturn(listOf(
                PrivacyItem(PrivacyType.TYPE_CAMERA,
                        PrivacyApplication(TEST_PACKAGE_NAME, TEST_UID), 0)))
                .`when`(privacyItemMonitor2).getActivePrivacyItems()

        privacyItemController.addCallback(callback)
        executor.runAllReady()
        verify(callback).onPrivacyItemsChanged(capture(argCaptor))
        assertEquals(1, argCaptor.value.size)
    }

    @Test
    fun testSimilarItemsDifferentTimeStamp() {
        doReturn(listOf(
                PrivacyItem(PrivacyType.TYPE_CAMERA,
                        PrivacyApplication(TEST_PACKAGE_NAME, TEST_UID), 0),
                PrivacyItem(PrivacyType.TYPE_CAMERA,
                        PrivacyApplication(TEST_PACKAGE_NAME, TEST_UID), 1)))
                .`when`(privacyItemMonitor).getActivePrivacyItems()

        privacyItemController.addCallback(callback)
        executor.runAllReady()
        verify(callback).onPrivacyItemsChanged(capture(argCaptor))
        assertEquals(2, argCaptor.value.size)
    }

    @Test
    fun testAddMultipleCallbacks() {
        val otherCallback = mock(PrivacyItemController.Callback::class.java)
        privacyItemController.addCallback(callback)
        executor.runAllReady()
        verify(callback).onPrivacyItemsChanged(anyList())

        privacyItemController.addCallback(otherCallback)
        executor.runAllReady()
        verify(otherCallback).onPrivacyItemsChanged(anyList())
        // Adding a callback should not unnecessarily call previous ones
        verifyNoMoreInteractions(callback)
    }

    @Test
    fun testMultipleCallbacksAreUpdated() {
        doReturn(emptyList<PrivacyItem>()).`when`(privacyItemMonitor).getActivePrivacyItems()

        val otherCallback = mock(PrivacyItemController.Callback::class.java)
        privacyItemController.addCallback(callback)
        privacyItemController.addCallback(otherCallback)
        executor.runAllReady()
        reset(callback)
        reset(otherCallback)

        verify(privacyItemMonitor).startListening(capture(argCaptorCallback))
        argCaptorCallback.value.onPrivacyItemsChanged()
        executor.runAllReady()
        verify(callback).onPrivacyItemsChanged(anyList())
        verify(otherCallback).onPrivacyItemsChanged(anyList())
    }

    @Test
    fun testRemoveCallback() {
        doReturn(emptyList<PrivacyItem>()).`when`(privacyItemMonitor).getActivePrivacyItems()
        val otherCallback = mock(PrivacyItemController.Callback::class.java)
        privacyItemController.addCallback(callback)
        privacyItemController.addCallback(otherCallback)
        executor.runAllReady()
        executor.runAllReady()
        reset(callback)
        reset(otherCallback)

        verify(privacyItemMonitor).startListening(capture(argCaptorCallback))
        privacyItemController.removeCallback(callback)
        argCaptorCallback.value.onPrivacyItemsChanged()
        executor.runAllReady()
        verify(callback, never()).onPrivacyItemsChanged(anyList())
        verify(otherCallback).onPrivacyItemsChanged(anyList())
    }

    @Test
    fun testListShouldBeCopy() {
        val list = listOf(PrivacyItem(PrivacyType.TYPE_CAMERA,
                PrivacyApplication(TEST_PACKAGE_NAME, TEST_UID), 0))
        privacyItemController.privacyList = list
        val privacyList = privacyItemController.privacyList
        assertEquals(list, privacyList)
        assertTrue(list !== privacyList)
    }

    @Test
    fun testLogListUpdated() {
        val privacyItem = PrivacyItem(
                PrivacyType.TYPE_LOCATION,
                PrivacyApplication(TEST_PACKAGE_NAME, TEST_UID),
                0
        )

        doReturn(listOf(privacyItem)).`when`(privacyItemMonitor).getActivePrivacyItems()

        privacyItemController.addCallback(callback)
        executor.runAllReady()

        verify(privacyItemMonitor).startListening(capture(argCaptorCallback))
        argCaptorCallback.value.onPrivacyItemsChanged()
        executor.runAllReady()

        val captor = argumentCaptor<List<PrivacyItem>>()
        verify(logger, atLeastOnce()).logRetrievedPrivacyItemsList(capture(captor))
        // Let's look at the last log
        val values = captor.allValues
        assertTrue(values[values.size - 1].contains(privacyItem))
    }

    @Test
    fun testPassageOfTimeDoesNotRemoveIndicators() {
        doReturn(listOf(
                PrivacyItem(PrivacyType.TYPE_CAMERA,
                        PrivacyApplication(TEST_PACKAGE_NAME, TEST_UID), 0)
        )).`when`(privacyItemMonitor).getActivePrivacyItems()

        privacyItemController.addCallback(callback)

        fakeClock.advanceTime(PrivacyItemController.TIME_TO_HOLD_INDICATORS * 10)
        executor.runAllReady()

        verify(callback, never()).onPrivacyItemsChanged(emptyList())
        assertTrue(privacyItemController.privacyList.isNotEmpty())
    }

    @Test
    fun testNotHeldAfterTimeIsOff() {
        // Start with some element at time 0
        doReturn(listOf(
                PrivacyItem(PrivacyType.TYPE_CAMERA,
                        PrivacyApplication(TEST_PACKAGE_NAME, TEST_UID), 0)
        )).`when`(privacyItemMonitor).getActivePrivacyItems()
        privacyItemController.addCallback(callback)
        executor.runAllReady()

        // Then remove it at time HOLD + 1
        doReturn(emptyList<PrivacyItem>()).`when`(privacyItemMonitor).getActivePrivacyItems()
        fakeClock.advanceTime(PrivacyItemController.TIME_TO_HOLD_INDICATORS + 1)

        verify(privacyItemMonitor).startListening(capture(argCaptorCallback))
        argCaptorCallback.value.onPrivacyItemsChanged()
        executor.runAllReady()

        // See it's not there
        verify(callback).onPrivacyItemsChanged(emptyList())
        assertTrue(privacyItemController.privacyList.isEmpty())
    }

    @Test
    fun testElementNotRemovedBeforeHoldTime() {
        // Start with some element at current time
        doReturn(listOf(
                PrivacyItem(PrivacyType.TYPE_CAMERA,
                        PrivacyApplication(TEST_PACKAGE_NAME, TEST_UID),
                        fakeClock.elapsedRealtime())
        )).`when`(privacyItemMonitor).getActivePrivacyItems()
        privacyItemController.addCallback(callback)
        executor.runAllReady()

        // Then remove it at time HOLD - 1
        doReturn(emptyList<PrivacyItem>()).`when`(privacyItemMonitor).getActivePrivacyItems()
        fakeClock.advanceTime(PrivacyItemController.TIME_TO_HOLD_INDICATORS - 1)

        verify(privacyItemMonitor).startListening(capture(argCaptorCallback))
        argCaptorCallback.value.onPrivacyItemsChanged()
        executor.runAllReady()

        // See it's still there
        verify(callback, never()).onPrivacyItemsChanged(emptyList())
        assertTrue(privacyItemController.privacyList.isNotEmpty())
    }

    @Test
    fun testElementAutoRemovedAfterHoldTime() {
        // Start with some element at time 0
        doReturn(listOf(
                PrivacyItem(PrivacyType.TYPE_CAMERA,
                        PrivacyApplication(TEST_PACKAGE_NAME, TEST_UID), 0)
        )).`when`(privacyItemMonitor).getActivePrivacyItems()
        privacyItemController.addCallback(callback)
        executor.runAllReady()

        // Then remove it at time HOLD - 1
        doReturn(emptyList<PrivacyItem>()).`when`(privacyItemMonitor).getActivePrivacyItems()
        fakeClock.advanceTime(PrivacyItemController.TIME_TO_HOLD_INDICATORS - 1)

        verify(privacyItemMonitor).startListening(capture(argCaptorCallback))
        argCaptorCallback.value.onPrivacyItemsChanged()
        executor.runAllReady()

        fakeClock.advanceTime(2L)
        executor.runAllReady()

        // See it was auto-removed
        verify(callback).onPrivacyItemsChanged(emptyList())
        assertTrue(privacyItemController.privacyList.isEmpty())
    }

    @Test
    fun testFlagsAll_listeningToAll() {
        verify(privacyConfig).addCallback(capture(argCaptorConfigCallback))
        privacyItemController.addCallback(callback)
        `when`(privacyConfig.micCameraAvailable).thenReturn(true)
        `when`(privacyConfig.locationAvailable).thenReturn(true)
        `when`(privacyConfig.mediaProjectionAvailable).thenReturn(true)
        argCaptorConfigCallback.value.onFlagMicCameraChanged(true)
        argCaptorConfigCallback.value.onFlagLocationChanged(true)
        argCaptorConfigCallback.value.onFlagMediaProjectionChanged(true)
        executor.runAllReady()

        assertTrue(privacyItemController.allIndicatorsAvailable)
    }

    @Test
    fun testFlags_onFlagMicCameraChanged() {
        verify(privacyConfig).addCallback(capture(argCaptorConfigCallback))
        privacyItemController.addCallback(callback)
        `when`(privacyConfig.micCameraAvailable).thenReturn(true)
        argCaptorConfigCallback.value.onFlagMicCameraChanged(true)
        executor.runAllReady()

        assertTrue(privacyItemController.micCameraAvailable)
        verify(callback).onFlagMicCameraChanged(true)
    }

    @Test
    fun testFlags_onFlagLocationChanged() {
        verify(privacyConfig).addCallback(capture(argCaptorConfigCallback))
        privacyItemController.addCallback(callback)
        `when`(privacyConfig.locationAvailable).thenReturn(true)
        argCaptorConfigCallback.value.onFlagLocationChanged(true)
        executor.runAllReady()

        assertTrue(privacyItemController.locationAvailable)
        verify(callback).onFlagLocationChanged(true)
    }

    @Test
    fun testFlags_onFlagMediaProjectionChanged() {
        verify(privacyConfig).addCallback(capture(argCaptorConfigCallback))
        privacyItemController.addCallback(callback)
        `when`(privacyConfig.mediaProjectionAvailable).thenReturn(true)
        argCaptorConfigCallback.value.onFlagMediaProjectionChanged(true)
        executor.runAllReady()

        verify(callback).onFlagMediaProjectionChanged(true)
    }

    @Test
    fun testPausedElementsAreRemoved() {
        doReturn(listOf(
                PrivacyItem(PrivacyType.TYPE_MICROPHONE,
                        PrivacyApplication(TEST_PACKAGE_NAME, TEST_UID), 0, true)))
                .`when`(privacyItemMonitor).getActivePrivacyItems()

        privacyItemController.addCallback(callback)
        executor.runAllReady()

        assertTrue(privacyItemController.privacyList.isEmpty())
    }
}