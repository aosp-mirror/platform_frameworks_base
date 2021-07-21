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

package com.android.systemui.statusbar.policy

import android.app.NotificationManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper

import androidx.test.filters.SmallTest

import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock

import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

private fun <T> anyObject(): T {
    return Mockito.anyObject<T>()
}

@RunWith(AndroidTestingRunner::class)
@RunWithLooper()
@SmallTest
class BatteryStateNotifierTest : SysuiTestCase() {
    @Mock private lateinit var batteryController: BatteryController
    @Mock private lateinit var noMan: NotificationManager

    private val clock = FakeSystemClock()
    private val executor = FakeExecutor(clock)

    private lateinit var notifier: BatteryStateNotifier

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        notifier = BatteryStateNotifier(batteryController, noMan, executor, context)
        notifier.startListening()

        context.ensureTestableResources()
    }

    @Test
    fun testNotifyWhenStateUnknown() {
        notifier.onBatteryUnknownStateChanged(true)
        verify(noMan).notify(anyString(), anyInt(), anyObject())
    }

    @Test
    fun testCancelAfterDelay() {
        notifier.onBatteryUnknownStateChanged(true)
        notifier.onBatteryUnknownStateChanged(false)

        clock.advanceTime(DELAY_MILLIS + 1)
        verify(noMan).cancel(anyInt())
    }
}

// From BatteryStateNotifier.kt
private const val DELAY_MILLIS: Long = 40 * 60 * 60 * 1000
