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

package com.android.systemui.util

import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.UserHandle
import android.testing.TestableLooper
import androidx.lifecycle.Observer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class RingerModeLiveDataTest : SysuiTestCase() {

    companion object {
        private fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
        private fun <T> any(): T = Mockito.any()
        private fun <T> eq(value: T): T = Mockito.eq(value) ?: value
        private val INTENT = "INTENT"
    }

    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock private lateinit var valueSupplier: () -> Int
    @Mock private lateinit var observer: Observer<Int>
    @Captor private lateinit var broadcastReceiverCaptor: ArgumentCaptor<BroadcastReceiver>
    @Captor private lateinit var intentFilterCaptor: ArgumentCaptor<IntentFilter>

    // Run everything immediately
    private val executor = Executor { it.run() }
    private lateinit var liveData: RingerModeLiveData

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        liveData = RingerModeLiveData(broadcastDispatcher, executor, INTENT, valueSupplier)
    }

    @After
    fun tearDown() {
        liveData.removeObserver(observer)
    }

    @Test
    fun testInit_broadcastNotRegistered() {
        verifyNoMoreInteractions(broadcastDispatcher)
    }

    @Test
    fun testOnActive_broadcastRegistered() {
        liveData.observeForever(observer)
        verify(broadcastDispatcher)
            .registerReceiver(any(), any(), eq(executor), eq(UserHandle.ALL), anyInt(), any())
    }

    @Test
    fun testOnActive_intentFilterHasIntent() {
        liveData.observeForever(observer)
        verify(broadcastDispatcher)
            .registerReceiver(any(), capture(intentFilterCaptor), any(), any(), anyInt(), any())
        assertTrue(intentFilterCaptor.value.hasAction(INTENT))
    }

    @Test
    fun testOnActive_valueObtained() {
        liveData.observeForever(observer)
        verify(valueSupplier).invoke()
    }

    @Test
    fun testOnInactive_broadcastUnregistered() {
        liveData.observeForever(observer)
        liveData.removeObserver(observer)
        verify(broadcastDispatcher).unregisterReceiver(any())
    }
}
