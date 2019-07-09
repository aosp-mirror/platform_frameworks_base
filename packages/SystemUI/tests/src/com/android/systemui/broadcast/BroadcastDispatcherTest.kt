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
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import com.android.systemui.SysuiTestCase
import junit.framework.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
@SmallTest
class BroadcastDispatcherTest : SysuiTestCase() {

    companion object {
        val user0 = UserHandle.of(0)
        val user1 = UserHandle.of(1)

        fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
    }

    @Mock
    private lateinit var mockContext: Context
    @Mock
    private lateinit var mockUBRUser0: UserBroadcastDispatcher
    @Mock
    private lateinit var mockUBRUser1: UserBroadcastDispatcher
    @Mock
    private lateinit var broadcastReceiver: BroadcastReceiver
    @Mock
    private lateinit var broadcastReceiverOther: BroadcastReceiver
    @Mock
    private lateinit var intentFilter: IntentFilter
    @Mock
    private lateinit var intentFilterOther: IntentFilter
    @Mock
    private lateinit var mockHandler: Handler

    @Captor
    private lateinit var argumentCaptor: ArgumentCaptor<ReceiverData>

    private lateinit var testableLooper: TestableLooper
    private lateinit var broadcastDispatcher: BroadcastDispatcher

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)

        broadcastDispatcher = TestBroadcastDispatcher(
                mockContext,
                Handler(testableLooper.looper),
                testableLooper.looper,
                mapOf(0 to mockUBRUser0, 1 to mockUBRUser1))
    }

    @Test
    fun testAddingReceiverToCorrectUBR() {
        broadcastDispatcher.registerReceiver(broadcastReceiver, intentFilter, mockHandler, user0)
        broadcastDispatcher.registerReceiver(
                broadcastReceiverOther, intentFilterOther, mockHandler, user1)

        testableLooper.processAllMessages()

        verify(mockUBRUser0).registerReceiver(capture(argumentCaptor))

        assertSame(broadcastReceiver, argumentCaptor.value.receiver)
        assertSame(intentFilter, argumentCaptor.value.filter)

        verify(mockUBRUser1).registerReceiver(capture(argumentCaptor))
        assertSame(broadcastReceiverOther, argumentCaptor.value.receiver)
        assertSame(intentFilterOther, argumentCaptor.value.filter)
    }

    @Test
    fun testRemovingReceiversRemovesFromAllUBR() {
        broadcastDispatcher.registerReceiver(broadcastReceiver, intentFilter, mockHandler, user0)
        broadcastDispatcher.registerReceiver(broadcastReceiver, intentFilter, mockHandler, user1)

        broadcastDispatcher.unregisterReceiver(broadcastReceiver)

        testableLooper.processAllMessages()

        verify(mockUBRUser0).unregisterReceiver(broadcastReceiver)
        verify(mockUBRUser1).unregisterReceiver(broadcastReceiver)
    }

    @Test
    fun testRemoveReceiverFromUser() {
        broadcastDispatcher.registerReceiver(broadcastReceiver, intentFilter, mockHandler, user0)
        broadcastDispatcher.registerReceiver(broadcastReceiver, intentFilter, mockHandler, user1)

        broadcastDispatcher.unregisterReceiverForUser(broadcastReceiver, user0)

        testableLooper.processAllMessages()

        verify(mockUBRUser0).unregisterReceiver(broadcastReceiver)
        verify(mockUBRUser1, never()).unregisterReceiver(broadcastReceiver)
    }

    private class TestBroadcastDispatcher(
        context: Context,
        mainHandler: Handler,
        bgLooper: Looper,
        var mockUBRMap: Map<Int, UserBroadcastDispatcher>
    ) : BroadcastDispatcher(context, mainHandler, bgLooper) {
        override fun createUBRForUser(userId: Int): UserBroadcastDispatcher {
            return mockUBRMap.getOrDefault(userId, mock(UserBroadcastDispatcher::class.java))
        }
    }
}
