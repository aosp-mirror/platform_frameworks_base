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

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.UnsupportedOperationException

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class NotifLiveDataStoreImplTest : SysuiTestCase() {

    private val executor = FakeExecutor(FakeSystemClock())
    private val liveDataStoreImpl = NotifLiveDataStoreImpl(executor)

    @Before
    fun setup() {
        allowTestableLooperAsMainThread()
    }

    @Test
    fun testAllObserversSeeConsistentValues() {
        val entry1 = NotificationEntryBuilder().setId(1).build()
        val entry2 = NotificationEntryBuilder().setId(2).build()
        val observer: (Any) -> Unit = {
            assertThat(liveDataStoreImpl.hasActiveNotifs.value).isEqualTo(true)
            assertThat(liveDataStoreImpl.activeNotifCount.value).isEqualTo(2)
            assertThat(liveDataStoreImpl.activeNotifList.value).isEqualTo(listOf(entry1, entry2))
        }
        liveDataStoreImpl.hasActiveNotifs.addSyncObserver(observer)
        liveDataStoreImpl.hasActiveNotifs.addAsyncObserver(observer)
        liveDataStoreImpl.activeNotifCount.addSyncObserver(observer)
        liveDataStoreImpl.activeNotifCount.addAsyncObserver(observer)
        liveDataStoreImpl.activeNotifList.addSyncObserver(observer)
        liveDataStoreImpl.activeNotifList.addAsyncObserver(observer)
        liveDataStoreImpl.setActiveNotifList(listOf(entry1, entry2))
        executor.runAllReady()
    }

    @Test
    fun testOriginalListIsCopied() {
        val entry1 = NotificationEntryBuilder().setId(1).build()
        val entry2 = NotificationEntryBuilder().setId(2).build()
        val mutableInputList = mutableListOf(entry1, entry2)
        val observer: (Any) -> Unit = {
            mutableInputList.clear()
            assertThat(liveDataStoreImpl.hasActiveNotifs.value).isEqualTo(true)
            assertThat(liveDataStoreImpl.activeNotifCount.value).isEqualTo(2)
            assertThat(liveDataStoreImpl.activeNotifList.value).isEqualTo(listOf(entry1, entry2))
        }
        liveDataStoreImpl.hasActiveNotifs.addSyncObserver(observer)
        liveDataStoreImpl.hasActiveNotifs.addAsyncObserver(observer)
        liveDataStoreImpl.activeNotifCount.addSyncObserver(observer)
        liveDataStoreImpl.activeNotifCount.addAsyncObserver(observer)
        liveDataStoreImpl.activeNotifList.addSyncObserver(observer)
        liveDataStoreImpl.activeNotifList.addAsyncObserver(observer)
        liveDataStoreImpl.setActiveNotifList(mutableInputList)
        executor.runAllReady()
    }

    @Test
    fun testProvidedListIsUnmodifiable() {
        val entry1 = NotificationEntryBuilder().setId(1).build()
        val entry2 = NotificationEntryBuilder().setId(2).build()
        val observer: (List<NotificationEntry>) -> Unit = { providedValue ->
            val provided = providedValue as MutableList<NotificationEntry>
            Assert.assertThrows(UnsupportedOperationException::class.java) {
                provided.clear()
            }
            val current = liveDataStoreImpl.activeNotifList.value as MutableList<NotificationEntry>
            Assert.assertThrows(UnsupportedOperationException::class.java) {
                current.clear()
            }
            assertThat(liveDataStoreImpl.activeNotifList.value).isEqualTo(listOf(entry1, entry2))
        }
        liveDataStoreImpl.activeNotifList.addSyncObserver(observer)
        liveDataStoreImpl.activeNotifList.addAsyncObserver(observer)
        liveDataStoreImpl.setActiveNotifList(mutableListOf(entry1, entry2))
        executor.runAllReady()
    }
}