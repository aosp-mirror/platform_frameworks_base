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
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import android.testing.TestableLooper
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.logging.BroadcastDispatcherLogger
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertNotNull
import junit.framework.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.nullable
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
@SmallTest
class UserBroadcastDispatcherTest : SysuiTestCase() {

    companion object {
        private const val ACTION_1 = "com.android.systemui.tests.ACTION_1"
        private const val ACTION_2 = "com.android.systemui.tests.ACTION_2"
        private const val USER_ID = 0
        private const val FLAG = 3
        private val USER_HANDLE = UserHandle.of(USER_ID)

        fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
        fun <T> any(): T = Mockito.any()
        fun <T> eq(v: T) = Mockito.eq(v) ?: v
    }

    @Mock
    private lateinit var broadcastReceiver: BroadcastReceiver
    @Mock
    private lateinit var broadcastReceiverOther: BroadcastReceiver
    @Mock
    private lateinit var mockContext: Context
    @Mock
    private lateinit var logger: BroadcastDispatcherLogger
    @Mock
    private lateinit var removalPendingStore: PendingRemovalStore

    private lateinit var testableLooper: TestableLooper
    private lateinit var userBroadcastDispatcher: UserBroadcastDispatcher
    private lateinit var intentFilter: IntentFilter
    private lateinit var intentFilterOther: IntentFilter
    private lateinit var handler: Handler
    private lateinit var fakeExecutor: FakeExecutor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        handler = Handler(testableLooper.looper)
        fakeExecutor = FakeExecutor(FakeSystemClock())

        userBroadcastDispatcher = object : UserBroadcastDispatcher(
                mockContext,
                USER_ID,
                testableLooper.looper,
                mock(Executor::class.java),
                logger,
                removalPendingStore
        ) {
            override fun createActionReceiver(
                action: String,
                permission: String?,
                flags: Int
            ): ActionReceiver {
                return mock(ActionReceiver::class.java)
            }
        }
    }

    @Test
    fun testSingleReceiverRegistered() {
        intentFilter = IntentFilter(ACTION_1)
        val receiverData = ReceiverData(broadcastReceiver, intentFilter, fakeExecutor, USER_HANDLE)

        userBroadcastDispatcher.registerReceiver(receiverData, FLAG)
        testableLooper.processAllMessages()

        val actionReceiver = userBroadcastDispatcher.getActionReceiver(ACTION_1, FLAG)
        assertNotNull(actionReceiver)
        verify(actionReceiver)?.addReceiverData(receiverData)
    }

    @Test
    fun testDifferentActionReceiversForDifferentFlags() {
        intentFilter = IntentFilter(ACTION_1)
        val receiverData = ReceiverData(broadcastReceiver, intentFilter, fakeExecutor, USER_HANDLE)

        val flag1 = 0
        val flag2 = 1

        userBroadcastDispatcher.registerReceiver(receiverData, flag1)
        userBroadcastDispatcher.registerReceiver(receiverData, flag2)
        testableLooper.processAllMessages()

        assertNotSame(
                userBroadcastDispatcher.getActionReceiver(ACTION_1, flag1),
                userBroadcastDispatcher.getActionReceiver(ACTION_1, flag2)
        )
    }

    @Test
    fun testDifferentActionReceiversForDifferentPermissions() {
        intentFilter = IntentFilter(ACTION_1)
        val receiverData1 =
            ReceiverData(broadcastReceiver, intentFilter, fakeExecutor, USER_HANDLE, "PERMISSION1")
        val receiverData2 =
            ReceiverData(broadcastReceiver, intentFilter, fakeExecutor, USER_HANDLE, "PERMISSION2")

        userBroadcastDispatcher.registerReceiver(receiverData1, 0)
        userBroadcastDispatcher.registerReceiver(receiverData2, 0)
        testableLooper.processAllMessages()

        assertNotSame(
            userBroadcastDispatcher.getActionReceiver(ACTION_1, 0, "PERMISSION1"),
            userBroadcastDispatcher.getActionReceiver(ACTION_1, 0, "PERMISSION2")
        )
    }

    @Test
    fun testSingleReceiverRegistered_logging() {
        intentFilter = IntentFilter(ACTION_1)

        userBroadcastDispatcher.registerReceiver(
                ReceiverData(broadcastReceiver, intentFilter, fakeExecutor, USER_HANDLE), FLAG)
        testableLooper.processAllMessages()

        verify(logger).logReceiverRegistered(USER_HANDLE.identifier, broadcastReceiver, FLAG)
    }

    @Test
    fun testSingleReceiverUnregistered() {
        intentFilter = IntentFilter(ACTION_1)

        userBroadcastDispatcher.registerReceiver(
                ReceiverData(broadcastReceiver, intentFilter, fakeExecutor, USER_HANDLE), FLAG)
        testableLooper.processAllMessages()

        userBroadcastDispatcher.unregisterReceiver(broadcastReceiver)
        testableLooper.processAllMessages()

        val actionReceiver = userBroadcastDispatcher.getActionReceiver(ACTION_1, FLAG)
        assertNotNull(actionReceiver)
        verify(actionReceiver)?.removeReceiver(broadcastReceiver)
    }

    @Test
    fun testSingleReceiverUnregistered_logger() {
        intentFilter = IntentFilter(ACTION_1)

        userBroadcastDispatcher.registerReceiver(
                ReceiverData(broadcastReceiver, intentFilter, fakeExecutor, USER_HANDLE), FLAG)
        testableLooper.processAllMessages()

        userBroadcastDispatcher.unregisterReceiver(broadcastReceiver)
        testableLooper.processAllMessages()

        verify(logger).logReceiverUnregistered(USER_HANDLE.identifier, broadcastReceiver)
    }

    @Test
    fun testRemoveReceiverReferences() {
        intentFilter = IntentFilter(ACTION_1)
        userBroadcastDispatcher.registerReceiver(
                ReceiverData(broadcastReceiver, intentFilter, fakeExecutor, USER_HANDLE), FLAG)

        intentFilterOther = IntentFilter(ACTION_1)
        intentFilterOther.addAction(ACTION_2)
        userBroadcastDispatcher.registerReceiver(
                ReceiverData(
                        broadcastReceiverOther,
                        intentFilterOther,
                        fakeExecutor,
                        USER_HANDLE
                ), FLAG
        )

        userBroadcastDispatcher.unregisterReceiver(broadcastReceiver)
        testableLooper.processAllMessages()
        fakeExecutor.runAllReady()

        assertFalse(userBroadcastDispatcher.isReceiverReferenceHeld(broadcastReceiver))
    }

    @Test
    fun testCreateActionReceiver_registerWithFlag() {
        val uBR = UserBroadcastDispatcher(
                mockContext,
                USER_ID,
                testableLooper.looper,
                fakeExecutor,
                logger,
                removalPendingStore
        )
        uBR.registerReceiver(
                ReceiverData(
                        broadcastReceiver,
                        IntentFilter(ACTION_1),
                        fakeExecutor,
                        USER_HANDLE
                ),
                FLAG
        )

        testableLooper.processAllMessages()
        fakeExecutor.runAllReady()

        verify(mockContext).registerReceiverAsUser(
                any(), any(), any(), nullable(String::class.java), any(), eq(FLAG))
    }

    @Test
    fun testCreateActionReceiver_registerWithPermission() {
        val permission = "CUSTOM_PERMISSION"
        val uBR = UserBroadcastDispatcher(
            mockContext,
            USER_ID,
            testableLooper.looper,
            fakeExecutor,
            logger,
            removalPendingStore
        )
        uBR.registerReceiver(
            ReceiverData(
                broadcastReceiver,
                IntentFilter(ACTION_1),
                fakeExecutor,
                USER_HANDLE,
                permission
            ),
            FLAG
        )

        testableLooper.processAllMessages()
        fakeExecutor.runAllReady()

        verify(mockContext).registerReceiverAsUser(
            any(), any(), any(), eq(permission), any(), eq(FLAG))
    }

    private fun UserBroadcastDispatcher.getActionReceiver(
        action: String,
        flags: Int,
        permission: String? = null
    ): ActionReceiver? {
        return actionsToActionsReceivers.get(
            UserBroadcastDispatcher.ReceiverProperties(
                action,
                flags,
                permission
            )
        )
    }
}
