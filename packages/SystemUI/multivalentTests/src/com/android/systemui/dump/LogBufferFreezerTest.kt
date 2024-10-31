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

package com.android.systemui.dump

import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class LogBufferFreezerTest : SysuiTestCase() {

    lateinit var freezer: LogBufferFreezer
    lateinit var receiver: BroadcastReceiver

    @Mock
    lateinit var dumpManager: DumpManager
    @Mock
    lateinit var broadcastDispatcher: BroadcastDispatcher
    @Captor
    lateinit var receiverCaptor: ArgumentCaptor<BroadcastReceiver>

    val clock = FakeSystemClock()
    val executor = FakeExecutor(clock)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        freezer = LogBufferFreezer(dumpManager, executor, 500)

        freezer.attach(broadcastDispatcher)

        verify(broadcastDispatcher)
                .registerReceiver(
                        capture(receiverCaptor),
                        any(IntentFilter::class.java),
                        eq(executor),
                        any(UserHandle::class.java),
                        anyInt(),
                        nullable())
        receiver = receiverCaptor.value
    }

    @Test
    fun testBuffersAreFrozenInResponseToBroadcast() {
        // WHEN the bugreport intent is fired
        receiver.onReceive(null, null)

        // THEN the buffers are frozen
        verify(dumpManager).freezeBuffers()
    }

    @Test
    fun testBuffersAreUnfrozenAfterTimeout() {
        // GIVEN that we've already frozen the buffers in response to a broadcast
        receiver.onReceive(null, null)
        verify(dumpManager).freezeBuffers()

        // WHEN the timeout expires
        clock.advanceTime(501)

        // THEN the buffers are unfrozen
        verify(dumpManager).unfreezeBuffers()
    }

    @Test
    fun testBuffersAreNotPrematurelyUnfrozen() {
        // GIVEN that we received a broadcast 499ms ago (shortly before the timeout would expire)
        receiver.onReceive(null, null)
        verify(dumpManager).freezeBuffers()
        clock.advanceTime(499)

        // WHEN we receive a second broadcast
        receiver.onReceive(null, null)

        // THEN the buffers are frozen a second time
        verify(dumpManager, times(2)).freezeBuffers()

        // THEN when we advance beyond the first timeout, nothing happens
        clock.advanceTime(101)
        verify(dumpManager, never()).unfreezeBuffers()

        // THEN only when we advance past the reset timeout window are the buffers unfrozen
        clock.advanceTime(401)
        verify(dumpManager).unfreezeBuffers()
    }
}
