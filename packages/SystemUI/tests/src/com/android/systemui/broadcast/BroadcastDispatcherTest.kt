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
import android.os.Looper
import android.os.PatternMatcher
import android.os.UserHandle
import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.logging.BroadcastDispatcherLogger
import com.android.systemui.dump.DumpManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import junit.framework.Assert.assertSame
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
@SmallTest
class BroadcastDispatcherTest : SysuiTestCase() {

    companion object {
        val user0 = UserHandle.of(0)
        val user1 = UserHandle.of(1)
        const val DEFAULT_FLAG = Context.RECEIVER_EXPORTED
        val DEFAULT_PERMISSION: String? = null

        fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
        const val TEST_ACTION = "TEST_ACTION"
        const val TEST_SCHEME = "TEST_SCHEME"
        const val TEST_PATH = "TEST_PATH"
        const val TEST_TYPE = "test/type"
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
    @Mock
    private lateinit var logger: BroadcastDispatcherLogger
    @Mock
    private lateinit var userTracker: UserTracker
    @Mock
    private lateinit var removalPendingStore: PendingRemovalStore

    private lateinit var executor: Executor

    @Captor
    private lateinit var argumentCaptor: ArgumentCaptor<ReceiverData>

    private lateinit var testableLooper: TestableLooper
    private lateinit var broadcastDispatcher: BroadcastDispatcher

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        executor = FakeExecutor(FakeSystemClock())
        `when`(mockContext.mainExecutor).thenReturn(executor)

        broadcastDispatcher = TestBroadcastDispatcher(
                mockContext,
                testableLooper.looper,
                mock(Executor::class.java),
                mock(DumpManager::class.java),
                logger,
                userTracker,
                removalPendingStore,
                mapOf(0 to mockUBRUser0, 1 to mockUBRUser1))

        // These should be valid filters
        `when`(intentFilter.countActions()).thenReturn(1)
        `when`(intentFilterOther.countActions()).thenReturn(1)
        setUserMock(mockContext, user0)
    }

    @Test
    fun testAddingReceiverToCorrectUBR() {
        broadcastDispatcher.registerReceiverWithHandler(broadcastReceiver, intentFilter,
                mockHandler, user0)
        broadcastDispatcher.registerReceiverWithHandler(
                broadcastReceiverOther, intentFilterOther, mockHandler, user1)

        testableLooper.processAllMessages()

        verify(mockUBRUser0).registerReceiver(capture(argumentCaptor), eq(DEFAULT_FLAG))

        assertSame(broadcastReceiver, argumentCaptor.value.receiver)
        assertSame(intentFilter, argumentCaptor.value.filter)

        verify(mockUBRUser1).registerReceiver(capture(argumentCaptor), eq(DEFAULT_FLAG))
        assertSame(broadcastReceiverOther, argumentCaptor.value.receiver)
        assertSame(intentFilterOther, argumentCaptor.value.filter)
    }

    @Test
    fun testAddingReceiverToCorrectUBR_executor() {
        broadcastDispatcher.registerReceiver(broadcastReceiver, intentFilter, executor, user0)
        broadcastDispatcher.registerReceiver(
                broadcastReceiverOther, intentFilterOther, executor, user1)

        testableLooper.processAllMessages()

        verify(mockUBRUser0).registerReceiver(capture(argumentCaptor), eq(DEFAULT_FLAG))

        assertSame(broadcastReceiver, argumentCaptor.value.receiver)
        assertSame(intentFilter, argumentCaptor.value.filter)

        verify(mockUBRUser1).registerReceiver(capture(argumentCaptor), eq(DEFAULT_FLAG))
        assertSame(broadcastReceiverOther, argumentCaptor.value.receiver)
        assertSame(intentFilterOther, argumentCaptor.value.filter)
    }

    @Test
    fun testAddReceiverDefaultFlag_handler() {
        broadcastDispatcher.registerReceiverWithHandler(
                broadcastReceiver, intentFilter, mockHandler)
        testableLooper.processAllMessages()

        verify(mockUBRUser0).registerReceiver(capture(argumentCaptor), eq(DEFAULT_FLAG))

        assertSame(broadcastReceiver, argumentCaptor.value.receiver)
        assertSame(intentFilter, argumentCaptor.value.filter)
    }

    @Test
    fun testAddReceiverCorrectFlag_handler() {
        val flag = 3

        broadcastDispatcher.registerReceiverWithHandler(
                broadcastReceiver, intentFilter, mockHandler, flags = flag)
        testableLooper.processAllMessages()

        verify(mockUBRUser0).registerReceiver(capture(argumentCaptor), eq(flag))

        assertSame(broadcastReceiver, argumentCaptor.value.receiver)
        assertSame(intentFilter, argumentCaptor.value.filter)
    }

    @Test
    fun testAddReceiverDefaultFlag_executor() {
        broadcastDispatcher.registerReceiver(broadcastReceiver, intentFilter)
        testableLooper.processAllMessages()

        verify(mockUBRUser0).registerReceiver(capture(argumentCaptor), eq(DEFAULT_FLAG))

        assertSame(broadcastReceiver, argumentCaptor.value.receiver)
        assertSame(intentFilter, argumentCaptor.value.filter)
    }

    @Test
    fun testAddReceiverCorrectPermission_executor() {
        val flag = 3
        val permission = "CUSTOM_PERMISSION"

        broadcastDispatcher.registerReceiver(
            broadcastReceiver,
            intentFilter,
            flags = flag,
            permission = permission
        )
        testableLooper.processAllMessages()

        verify(mockUBRUser0).registerReceiver(capture(argumentCaptor), eq(flag))

        assertSame(broadcastReceiver, argumentCaptor.value.receiver)
        assertSame(intentFilter, argumentCaptor.value.filter)
        assertSame(permission, argumentCaptor.value.permission)
    }

    @Test
    fun testAddReceiverDefaultPermission_executor() {
        broadcastDispatcher.registerReceiver(broadcastReceiver, intentFilter)
        testableLooper.processAllMessages()

        verify(mockUBRUser0).registerReceiver(capture(argumentCaptor), eq(DEFAULT_FLAG))

        assertSame(broadcastReceiver, argumentCaptor.value.receiver)
        assertSame(intentFilter, argumentCaptor.value.filter)
        assertSame(DEFAULT_PERMISSION, argumentCaptor.value.permission)
    }

    @Test
    fun testAddReceiverCorrectFlag_executor() {
        val flag = 3

        broadcastDispatcher.registerReceiver(broadcastReceiver, intentFilter, flags = flag)
        testableLooper.processAllMessages()

        verify(mockUBRUser0).registerReceiver(capture(argumentCaptor), eq(flag))

        assertSame(broadcastReceiver, argumentCaptor.value.receiver)
        assertSame(intentFilter, argumentCaptor.value.filter)
    }

    @Test
    fun testRemovingReceiversRemovesFromAllUBR() {
        broadcastDispatcher.registerReceiverWithHandler(broadcastReceiver, intentFilter,
                mockHandler, user0)
        broadcastDispatcher.registerReceiverWithHandler(broadcastReceiver, intentFilter,
                mockHandler, user1)

        broadcastDispatcher.unregisterReceiver(broadcastReceiver)

        testableLooper.processAllMessages()

        verify(mockUBRUser0).unregisterReceiver(broadcastReceiver)
        verify(mockUBRUser1).unregisterReceiver(broadcastReceiver)
    }

    @Test
    fun testRemoveReceiverFromUser() {
        broadcastDispatcher.registerReceiverWithHandler(broadcastReceiver, intentFilter,
                mockHandler, user0)
        broadcastDispatcher.registerReceiverWithHandler(broadcastReceiver, intentFilter,
                mockHandler, user1)

        broadcastDispatcher.unregisterReceiverForUser(broadcastReceiver, user0)

        testableLooper.processAllMessages()

        verify(mockUBRUser0).unregisterReceiver(broadcastReceiver)
        verify(mockUBRUser1, never()).unregisterReceiver(broadcastReceiver)
    }

    @Test
    fun testRegisterCurrentAsActualUser() {
        `when`(userTracker.userId).thenReturn(user1.identifier)

        broadcastDispatcher.registerReceiverWithHandler(broadcastReceiver, intentFilter,
                mockHandler, UserHandle.CURRENT)

        testableLooper.processAllMessages()

        verify(mockUBRUser1).registerReceiver(
                capture(argumentCaptor), eq(Context.RECEIVER_EXPORTED))
        assertSame(broadcastReceiver, argumentCaptor.value.receiver)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testFilterMustContainActions() {
        val testFilter = IntentFilter()
        broadcastDispatcher.registerReceiver(broadcastReceiver, testFilter)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testFilterMustNotContainDataScheme() {
        val testFilter = IntentFilter(TEST_ACTION).apply {
            addDataScheme(TEST_SCHEME)
        }
        broadcastDispatcher.registerReceiver(broadcastReceiver, testFilter)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testFilterMustNotContainDataAuthority() {
        val testFilter = IntentFilter(TEST_ACTION).apply {
            addDataAuthority(mock(IntentFilter.AuthorityEntry::class.java))
        }
        broadcastDispatcher.registerReceiver(broadcastReceiver, testFilter)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testFilterMustNotContainDataPath() {
        val testFilter = IntentFilter(TEST_ACTION).apply {
            addDataPath(TEST_PATH, PatternMatcher.PATTERN_LITERAL)
        }
        broadcastDispatcher.registerReceiver(broadcastReceiver, testFilter)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testFilterMustNotContainDataType() {
        val testFilter = IntentFilter(TEST_ACTION).apply {
            addDataType(TEST_TYPE)
        }
        broadcastDispatcher.registerReceiver(broadcastReceiver, testFilter)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testFilterMustNotSetPriority() {
        val testFilter = IntentFilter(TEST_ACTION).apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        broadcastDispatcher.registerReceiver(broadcastReceiver, testFilter)
    }

    @Test
    fun testTaggedReceiverForRemovalImmediately_allUsers() {
        broadcastDispatcher.unregisterReceiver(broadcastReceiver)

        verify(removalPendingStore).tagForRemoval(broadcastReceiver, UserHandle.USER_ALL)
        verify(removalPendingStore, never()).clearPendingRemoval(eq(broadcastReceiver), anyInt())
    }

    @Test
    fun testTaggedReceiverForRemovalImmediately_singleUser() {
        val user = 0
        broadcastDispatcher.unregisterReceiverForUser(broadcastReceiver, UserHandle.of(user))

        verify(removalPendingStore).tagForRemoval(broadcastReceiver, user)
        verify(removalPendingStore, never()).clearPendingRemoval(eq(broadcastReceiver), anyInt())
    }

    @Test
    fun testUnregisterReceiverClearsPendingRemovalAfterRemoving_allUsers() {
        broadcastDispatcher.registerReceiver(broadcastReceiver, intentFilter, null, user0)
        broadcastDispatcher.registerReceiver(broadcastReceiver, intentFilter, null, user1)

        broadcastDispatcher.unregisterReceiver(broadcastReceiver)

        testableLooper.processAllMessages()

        val inOrderUser0 = inOrder(mockUBRUser0, removalPendingStore)
        inOrderUser0.verify(mockUBRUser0).unregisterReceiver(broadcastReceiver)
        inOrderUser0.verify(removalPendingStore)
            .clearPendingRemoval(broadcastReceiver, UserHandle.USER_ALL)

        val inOrderUser1 = inOrder(mockUBRUser1, removalPendingStore)
        inOrderUser1.verify(mockUBRUser1).unregisterReceiver(broadcastReceiver)
        inOrderUser1.verify(removalPendingStore)
            .clearPendingRemoval(broadcastReceiver, UserHandle.USER_ALL)
    }

    @Test
    fun testUnregisterReceiverclearPendingRemovalAfterRemoving_singleUser() {
        broadcastDispatcher.registerReceiver(broadcastReceiver, intentFilter, null, user1)

        broadcastDispatcher.unregisterReceiverForUser(broadcastReceiver, user1)

        testableLooper.processAllMessages()

        val inOrderUser1 = inOrder(mockUBRUser1, removalPendingStore)
        inOrderUser1.verify(mockUBRUser1).unregisterReceiver(broadcastReceiver)
        inOrderUser1.verify(removalPendingStore)
            .clearPendingRemoval(broadcastReceiver, user1.identifier)
    }

    @Test
    fun testBroadcastFlow() = runBlockingTest {
        val flow = broadcastDispatcher.broadcastFlow(intentFilter, user1) { intent, receiver ->
            intent to receiver
        }

        // Collect the values into collectedValues.
        val collectedValues = mutableListOf<Pair<Intent, BroadcastReceiver>>()
        val job = launch {
            flow.collect { collectedValues.add(it) }
        }

        testableLooper.processAllMessages()
        verify(mockUBRUser1).registerReceiver(capture(argumentCaptor), eq(DEFAULT_FLAG))
        val receiver = argumentCaptor.value.receiver

        // Simulate fake broadcasted intents.
        val fakeIntents = listOf<Intent>(mock(), mock(), mock())
        fakeIntents.forEach { receiver.onReceive(mockContext, it) }

        // The intents should have been collected.
        advanceUntilIdle()

        val expectedValues = fakeIntents.map { it to receiver }
        assertThat(collectedValues).containsExactlyElementsIn(expectedValues)

        // Stop the collection.
        job.cancel()

        testableLooper.processAllMessages()
        verify(mockUBRUser1).unregisterReceiver(receiver)
    }

    private fun setUserMock(mockContext: Context, user: UserHandle) {
        `when`(mockContext.user).thenReturn(user)
        `when`(mockContext.userId).thenReturn(user.identifier)
    }

    private class TestBroadcastDispatcher(
        context: Context,
        bgLooper: Looper,
        executor: Executor,
        dumpManager: DumpManager,
        logger: BroadcastDispatcherLogger,
        userTracker: UserTracker,
        removalPendingStore: PendingRemovalStore,
        var mockUBRMap: Map<Int, UserBroadcastDispatcher>
    ) : BroadcastDispatcher(
        context,
        bgLooper,
        executor,
        dumpManager,
        logger,
        userTracker,
        removalPendingStore
    ) {
        override fun createUBRForUser(userId: Int): UserBroadcastDispatcher {
            return mockUBRMap.getOrDefault(userId, mock(UserBroadcastDispatcher::class.java))
        }
    }
}
