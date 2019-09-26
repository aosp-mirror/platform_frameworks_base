/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.UserHandle
import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import com.android.systemui.SysuiTestCase
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.anyString
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
@SmallTest
class UserBroadcastDispatcherTest : SysuiTestCase() {

    companion object {
        private const val ACTION_1 = "com.android.systemui.tests.ACTION_1"
        private const val ACTION_2 = "com.android.systemui.tests.ACTION_2"
        private const val CATEGORY_1 = "com.android.systemui.tests.CATEGORY_1"
        private const val CATEGORY_2 = "com.android.systemui.tests.CATEGORY_2"
        private const val USER_ID = 0
        private val USER_HANDLE = UserHandle.of(USER_ID)

        fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
    }

    @Mock
    private lateinit var broadcastReceiver: BroadcastReceiver
    @Mock
    private lateinit var broadcastReceiverOther: BroadcastReceiver
    @Mock
    private lateinit var mockContext: Context
    @Mock
    private lateinit var mockHandler: Handler
    @Mock
    private lateinit var mPendingResult: BroadcastReceiver.PendingResult

    @Captor
    private lateinit var argumentCaptor: ArgumentCaptor<IntentFilter>

    private lateinit var testableLooper: TestableLooper
    private lateinit var universalBroadcastReceiver: UserBroadcastDispatcher
    private lateinit var intentFilter: IntentFilter
    private lateinit var intentFilterOther: IntentFilter
    private lateinit var handler: Handler

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        handler = Handler(testableLooper.looper)

        universalBroadcastReceiver = UserBroadcastDispatcher(
                mockContext, USER_ID, handler, testableLooper.looper)
        universalBroadcastReceiver.pendingResult = mPendingResult
    }

    @Test
    fun testNotRegisteredOnStart() {
        testableLooper.processAllMessages()
        verify(mockContext, never()).registerReceiver(any(), any())
        verify(mockContext, never()).registerReceiver(any(), any(), anyInt())
        verify(mockContext, never()).registerReceiver(any(), any(), anyString(), any())
        verify(mockContext, never()).registerReceiver(any(), any(), anyString(), any(), anyInt())
        verify(mockContext, never()).registerReceiverAsUser(any(), any(), any(), anyString(), any())
    }

    @Test
    fun testSingleReceiverRegistered() {
        intentFilter = IntentFilter(ACTION_1)

        universalBroadcastReceiver.registerReceiver(
                ReceiverData(broadcastReceiver, intentFilter, mockHandler, USER_HANDLE))
        testableLooper.processAllMessages()

        assertTrue(universalBroadcastReceiver.isRegistered())
        verify(mockContext).registerReceiverAsUser(
                any(),
                eq(USER_HANDLE),
                capture(argumentCaptor),
                any(),
                any())
        assertEquals(1, argumentCaptor.value.countActions())
        assertTrue(argumentCaptor.value.hasAction(ACTION_1))
        assertEquals(0, argumentCaptor.value.countCategories())
    }

    @Test
    fun testSingleReceiverUnregistered() {
        intentFilter = IntentFilter(ACTION_1)

        universalBroadcastReceiver.registerReceiver(
                ReceiverData(broadcastReceiver, intentFilter, mockHandler, USER_HANDLE))
        testableLooper.processAllMessages()
        reset(mockContext)

        assertTrue(universalBroadcastReceiver.isRegistered())

        universalBroadcastReceiver.unregisterReceiver(broadcastReceiver)
        testableLooper.processAllMessages()

        verify(mockContext, atLeastOnce()).unregisterReceiver(any())
        verify(mockContext, never()).registerReceiverAsUser(any(), any(), any(), any(), any())
        assertFalse(universalBroadcastReceiver.isRegistered())
    }

    @Test
    fun testFilterHasAllActionsAndCategories_twoReceivers() {
        intentFilter = IntentFilter(ACTION_1)
        intentFilterOther = IntentFilter(ACTION_2).apply {
            addCategory(CATEGORY_1)
            addCategory(CATEGORY_2)
        }

        universalBroadcastReceiver.registerReceiver(
                ReceiverData(broadcastReceiver, intentFilter, mockHandler, USER_HANDLE))
        universalBroadcastReceiver.registerReceiver(
                ReceiverData(broadcastReceiverOther, intentFilterOther, mockHandler, USER_HANDLE))

        testableLooper.processAllMessages()
        assertTrue(universalBroadcastReceiver.isRegistered())

        verify(mockContext, times(2)).registerReceiverAsUser(
                any(),
                eq(USER_HANDLE),
                capture(argumentCaptor),
                any(),
                any())

        val lastFilter = argumentCaptor.value

        assertTrue(lastFilter.hasAction(ACTION_1))
        assertTrue(lastFilter.hasAction(ACTION_2))
        assertTrue(lastFilter.hasCategory(CATEGORY_1))
        assertTrue(lastFilter.hasCategory(CATEGORY_1))
    }

    @Test
    fun testDispatchToCorrectReceiver() {
        intentFilter = IntentFilter(ACTION_1)
        intentFilterOther = IntentFilter(ACTION_2)

        universalBroadcastReceiver.registerReceiver(
                ReceiverData(broadcastReceiver, intentFilter, handler, USER_HANDLE))
        universalBroadcastReceiver.registerReceiver(
                ReceiverData(broadcastReceiverOther, intentFilterOther, handler, USER_HANDLE))

        val intent = Intent(ACTION_2)

        universalBroadcastReceiver.onReceive(mockContext, intent)
        testableLooper.processAllMessages()

        verify(broadcastReceiver, never()).onReceive(any(), any())
        verify(broadcastReceiverOther).onReceive(mockContext, intent)
    }

    @Test
    fun testDispatchToCorrectReceiver_differentFiltersSameReceiver() {
        intentFilter = IntentFilter(ACTION_1)
        intentFilterOther = IntentFilter(ACTION_2)

        universalBroadcastReceiver.registerReceiver(
                ReceiverData(broadcastReceiver, intentFilter, handler, USER_HANDLE))
        universalBroadcastReceiver.registerReceiver(
                ReceiverData(broadcastReceiver, intentFilterOther, handler, USER_HANDLE))

        val intent = Intent(ACTION_2)

        universalBroadcastReceiver.onReceive(mockContext, intent)
        testableLooper.processAllMessages()

        verify(broadcastReceiver).onReceive(mockContext, intent)
    }

    @Test
    fun testDispatchIntentWithoutCategories() {
        intentFilter = IntentFilter(ACTION_1)
        intentFilter.addCategory(CATEGORY_1)
        intentFilterOther = IntentFilter(ACTION_1)
        intentFilterOther.addCategory(CATEGORY_2)

        universalBroadcastReceiver.registerReceiver(
                ReceiverData(broadcastReceiver, intentFilter, handler, USER_HANDLE))
        universalBroadcastReceiver.registerReceiver(
                ReceiverData(broadcastReceiverOther, intentFilterOther, handler, USER_HANDLE))

        val intent = Intent(ACTION_1)

        universalBroadcastReceiver.onReceive(mockContext, intent)
        testableLooper.processAllMessages()

        verify(broadcastReceiver).onReceive(mockContext, intent)
        verify(broadcastReceiverOther).onReceive(mockContext, intent)
    }

    @Test
    fun testPendingResult() {
        intentFilter = IntentFilter(ACTION_1)
        universalBroadcastReceiver.registerReceiver(
                ReceiverData(broadcastReceiver, intentFilter, handler, USER_HANDLE))

        val intent = Intent(ACTION_1)
        universalBroadcastReceiver.onReceive(mockContext, intent)

        testableLooper.processAllMessages()

        verify(broadcastReceiver).onReceive(mockContext, intent)
        verify(broadcastReceiver).pendingResult = mPendingResult
    }
}
