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

package com.android.systemui.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.logging.BroadcastDispatcherLogger
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.lang.IllegalArgumentException
import java.lang.IllegalStateException
import java.util.concurrent.Executor

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
@SmallTest
class ActionReceiverTest : SysuiTestCase() {

    companion object {
        private const val ACTION1 = "TEST_ACTION1"
        private const val ACTION2 = "TEST_ACTION2"
        private const val CATEGORY = "TEST_CATEGORY"
        private val USER = UserHandle.of(0)
        private fun <T : Any> sameNotNull(arg: T): T = Mockito.same(arg) ?: arg

        fun IntentFilter.matchesOther(it: IntentFilter): Boolean {
            val actions = actionsIterator()?.asSequence()?.toSet() ?: emptySet()
            val categories = categoriesIterator()?.asSequence()?.toSet() ?: emptySet()
            return (it.actionsIterator()?.asSequence()?.toSet() ?: emptySet()) == actions &&
                    (it.categoriesIterator()?.asSequence()?.toSet() ?: emptySet()) == categories &&
                    it.countDataAuthorities() == 0 &&
                    it.countDataPaths() == 0 &&
                    it.countDataSchemes() == 0 &&
                    it.countDataTypes() == 0 &&
                    it.countMimeGroups() == 0 &&
                    it.priority == 0
        }
    }

    @Mock
    private lateinit var registerFunction: BroadcastReceiver.(IntentFilter) -> Unit
    @Mock
    private lateinit var unregisterFunction: BroadcastReceiver.() -> Unit
    @Mock
    private lateinit var isPendingRemovalFunction: (BroadcastReceiver, Int) -> Boolean
    @Mock
    private lateinit var receiver1: BroadcastReceiver
    @Mock
    private lateinit var receiver2: BroadcastReceiver
    @Mock
    private lateinit var logger: BroadcastDispatcherLogger
    @Captor
    private lateinit var intentFilterCaptor: ArgumentCaptor<IntentFilter>

    private lateinit var executor: FakeExecutor
    private lateinit var actionReceiver: ActionReceiver
    private val directExecutor = Executor { it.run() }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        executor = FakeExecutor(FakeSystemClock())

        `when`(isPendingRemovalFunction(any(), anyInt())).thenReturn(false)

        actionReceiver = ActionReceiver(
                ACTION1,
                USER.identifier,
                registerFunction,
                unregisterFunction,
                executor,
                logger,
                isPendingRemovalFunction
        )
    }

    @Test
    fun testStartsUnregistered() {
        assertFalse(actionReceiver.registered)
        verify(registerFunction, never()).invoke(sameNotNull(actionReceiver),
                any(IntentFilter::class.java))
    }

    @Test
    fun testRegistersOnFirstAdd() {
        val receiverData = ReceiverData(receiver1, IntentFilter(ACTION1), directExecutor, USER)

        actionReceiver.addReceiverData(receiverData)

        assertTrue(actionReceiver.registered)
        verify(registerFunction).invoke(sameNotNull(actionReceiver), capture(intentFilterCaptor))

        assertTrue(IntentFilter(ACTION1).matchesOther(intentFilterCaptor.value))
    }

    @Test
    fun testRegistersOnlyOnce() {
        val receiverData1 = ReceiverData(receiver1, IntentFilter(ACTION1), directExecutor, USER)
        val receiverData2 = ReceiverData(receiver2, IntentFilter(ACTION1), directExecutor, USER)

        actionReceiver.addReceiverData(receiverData1)
        actionReceiver.addReceiverData(receiverData2)

        verify(registerFunction).invoke(sameNotNull(actionReceiver), any(IntentFilter::class.java))
    }

    @Test
    fun testRemovingLastReceiverUnregisters() {
        val receiverData = ReceiverData(receiver1, IntentFilter(ACTION1), directExecutor, USER)

        actionReceiver.addReceiverData(receiverData)

        actionReceiver.removeReceiver(receiver1)

        assertFalse(actionReceiver.registered)
        verify(unregisterFunction).invoke(sameNotNull(actionReceiver))
    }

    @Test
    fun testRemovingWhileOtherReceiversDoesntUnregister() {
        val receiverData1 = ReceiverData(receiver1, IntentFilter(ACTION1), directExecutor, USER)
        val receiverData2 = ReceiverData(receiver2, IntentFilter(ACTION1), directExecutor, USER)

        actionReceiver.addReceiverData(receiverData1)
        actionReceiver.addReceiverData(receiverData2)

        actionReceiver.removeReceiver(receiver1)

        assertTrue(actionReceiver.registered)
        verify(unregisterFunction, never()).invoke(any(BroadcastReceiver::class.java))
    }

    @Test
    fun testReceiverHasCategories() {
        val filter = IntentFilter(ACTION1)
        filter.addCategory(CATEGORY)

        val receiverData = ReceiverData(receiver1, filter, directExecutor, USER)

        actionReceiver.addReceiverData(receiverData)

        verify(registerFunction).invoke(sameNotNull(actionReceiver), capture(intentFilterCaptor))
        assertTrue(intentFilterCaptor.value.hasCategory(CATEGORY))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testNotRegisteredWithWrongAction_throwsException() {
        val receiverData = ReceiverData(receiver1, IntentFilter(ACTION2), directExecutor, USER)

        actionReceiver.addReceiverData(receiverData)
    }

    @Test
    fun testReceiverGetsBroadcast() {
        val receiverData = ReceiverData(receiver1, IntentFilter(ACTION1), directExecutor, USER)
        actionReceiver.addReceiverData(receiverData)

        val intent = Intent(ACTION1)

        actionReceiver.onReceive(mContext, intent)

        executor.runAllReady()

        verify(receiver1).onReceive(any(Context::class.java), sameNotNull(intent))
    }

    @Test
    fun testReceiverGetsPendingResult() {
        val receiverData = ReceiverData(receiver1, IntentFilter(ACTION1), directExecutor, USER)
        actionReceiver.addReceiverData(receiverData)

        val intent = Intent(ACTION1)
        val pendingResult = mock(BroadcastReceiver.PendingResult::class.java)

        actionReceiver.pendingResult = pendingResult
        actionReceiver.onReceive(mContext, intent)

        executor.runAllReady()
        verify(receiver1).pendingResult = pendingResult
    }

    @Test
    fun testBroadcastIsDispatchedInExecutor() {
        val executor = FakeExecutor(FakeSystemClock())
        val receiverData = ReceiverData(receiver1, IntentFilter(ACTION1), executor, USER)
        actionReceiver.addReceiverData(receiverData)

        val intent = Intent(ACTION1)
        actionReceiver.onReceive(mContext, intent)

        this.executor.runAllReady()

        verify(receiver1, never()).onReceive(mContext, intent)

        executor.runAllReady()
        // Dispatched after executor is processed
        verify(receiver1).onReceive(mContext, intent)
    }

    @Test
    fun testBroadcastReceivedDispatched_logger() {
        val receiverData = ReceiverData(receiver1, IntentFilter(ACTION1), directExecutor, USER)

        actionReceiver.addReceiverData(receiverData)

        val intent = Intent(ACTION1)
        actionReceiver.onReceive(mContext, intent)
        verify(logger).logBroadcastReceived(anyInt(), eq(USER.identifier), eq(intent))

        verify(logger, never()).logBroadcastDispatched(anyInt(), anyString(),
                any(BroadcastReceiver::class.java))

        executor.runAllReady()

        verify(logger).logBroadcastDispatched(anyInt(), eq(ACTION1), sameNotNull(receiver1))
    }

    @Test
    fun testBroadcastNotDispatchingOnPendingRemoval() {
        `when`(isPendingRemovalFunction(receiver1, USER.identifier)).thenReturn(true)

        val receiverData = ReceiverData(receiver1, IntentFilter(ACTION1), directExecutor, USER)

        actionReceiver.addReceiverData(receiverData)

        val intent = Intent(ACTION1)
        actionReceiver.onReceive(mContext, intent)
        executor.runAllReady()
        verify(receiver1, never()).onReceive(any(), eq(intent))
    }

    @Test(expected = IllegalStateException::class)
    fun testBroadcastWithWrongAction_throwsException() {
        actionReceiver.onReceive(mContext, Intent(ACTION2))
    }
}