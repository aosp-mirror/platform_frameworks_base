/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection

import android.testing.TestableLooper.RunWithLooper
import androidx.lifecycle.Observer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.eq
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class NotifLiveDataImplTest : SysuiTestCase() {

    private val executor = FakeExecutor(FakeSystemClock())
    private val liveDataImpl: NotifLiveDataImpl<Int> = NotifLiveDataImpl("tst", 9, executor)
    private val syncObserver: Observer<Int> = mock()
    private val asyncObserver: Observer<Int> = mock()

    @Before
    fun setup() {
        allowTestableLooperAsMainThread()
        liveDataImpl.addSyncObserver(syncObserver)
        liveDataImpl.addAsyncObserver(asyncObserver)
    }

    @Test
    fun testGetInitialValue() {
        assertThat(liveDataImpl.value).isEqualTo(9)
    }

    @Test
    fun testGetModifiedValue() {
        liveDataImpl.value = 13
        assertThat(liveDataImpl.value).isEqualTo(13)
    }

    @Test
    fun testGetsModifiedValueFromWithinSyncObserver() {
        liveDataImpl.addSyncObserver { intVal ->
            assertThat(intVal).isEqualTo(13)
            assertThat(liveDataImpl.value).isEqualTo(13)
        }
        liveDataImpl.value = 13
    }

    @Test
    fun testDoesNotAlertsRemovedObservers() {
        liveDataImpl.removeObserver(syncObserver)
        liveDataImpl.removeObserver(asyncObserver)

        liveDataImpl.value = 13

        // There should be no runnables on the executor
        assertThat(executor.runAllReady()).isEqualTo(0)

        // And observers should not be called
        verifyNoMoreInteractions(syncObserver, asyncObserver)
    }

    @Test
    fun testDoesNotAsyncObserversRemovedSinceChange() {
        liveDataImpl.value = 13
        liveDataImpl.removeObserver(asyncObserver)

        // There should be a runnable that will get executed...
        assertThat(executor.runAllReady()).isEqualTo(1)

        // ...but async observers should not be called
        verifyNoMoreInteractions(asyncObserver)
    }

    @Test
    fun testAlertsObservers() {
        liveDataImpl.value = 13

        // Verify that the synchronous observer is called immediately
        verify(syncObserver).onChanged(eq(13))
        verifyNoMoreInteractions(syncObserver, asyncObserver)

        // Verify that the asynchronous observer is called when the executor runs
        assertThat(executor.runAllReady()).isEqualTo(1)
        verify(asyncObserver).onChanged(eq(13))
        verifyNoMoreInteractions(syncObserver, asyncObserver)
    }

    @Test
    fun testAlertsObserversFromDispatcher() {
        // GIVEN that we use setValueAndProvideDispatcher()
        val dispatcher = liveDataImpl.setValueAndProvideDispatcher(13)

        // VERIFY that nothing is done before the dispatcher is called
        assertThat(executor.numPending()).isEqualTo(0)
        verifyNoMoreInteractions(syncObserver, asyncObserver)

        // WHEN the dispatcher is invoked...
        dispatcher.invoke()

        // Verify that the synchronous observer is called immediately
        verify(syncObserver).onChanged(eq(13))
        verifyNoMoreInteractions(syncObserver, asyncObserver)

        // Verify that the asynchronous observer is called when the executor runs
        assertThat(executor.runAllReady()).isEqualTo(1)
        verify(asyncObserver).onChanged(eq(13))
        verifyNoMoreInteractions(syncObserver, asyncObserver)
    }

    @Test
    fun testSkipsAllObserversIfValueDidNotChange() {
        liveDataImpl.value = 9
        // Does not add a runnable
        assertThat(executor.runAllReady()).isEqualTo(0)
        // Setting the current value does not call synchronous observers
        verifyNoMoreInteractions(syncObserver, asyncObserver)
    }

    @Test
    fun testSkipsAsyncObserversWhenValueTogglesBack() {
        liveDataImpl.value = 13
        liveDataImpl.value = 11
        liveDataImpl.value = 9

        // Synchronous observers will receive every change event immediately
        inOrder(syncObserver).apply {
            verify(syncObserver).onChanged(eq(13))
            verify(syncObserver).onChanged(eq(11))
            verify(syncObserver).onChanged(eq(9))
        }
        verifyNoMoreInteractions(syncObserver, asyncObserver)

        // Running the first runnable on the queue will just emit the most recent value
        assertThat(executor.runNextReady()).isTrue()
        verify(asyncObserver).onChanged(eq(9))
        verifyNoMoreInteractions(syncObserver, asyncObserver)

        // Running the next 2 runnable will have no effect
        assertThat(executor.runAllReady()).isEqualTo(2)
        verifyNoMoreInteractions(syncObserver, asyncObserver)
    }
}
