/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.recordissue

import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.UserContextProvider
import com.android.traceur.PresetTraceConfigs
import java.util.concurrent.CountDownLatch
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class TraceurConnectionTest : SysuiTestCase() {

    @Mock private lateinit var userContextProvider: UserContextProvider

    private lateinit var underTest: TraceurConnection

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(userContextProvider.userContext).thenReturn(mContext)
        underTest = TraceurConnection.Provider(userContextProvider, Looper.getMainLooper()).create()
    }

    @Test
    fun onBoundRunnables_areRun_whenServiceIsBound() {
        val latch = CountDownLatch(1)
        underTest.onBound.add { latch.countDown() }

        underTest.onServiceConnected(
            InstrumentationRegistry.getInstrumentation().componentName,
            mock(IBinder::class.java),
        )

        latch.await()
    }

    @Test
    fun startTracing_sendsMsg_toStartTracing() {
        underTest.binder = mock(Messenger::class.java)

        underTest.startTracing(PresetTraceConfigs.getThermalConfig())

        verify(underTest.binder)!!.send(any())
    }
}
