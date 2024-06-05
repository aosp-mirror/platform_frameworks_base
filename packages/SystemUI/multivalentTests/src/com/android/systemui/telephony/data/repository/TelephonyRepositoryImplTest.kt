/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.telephony.data.repository

import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.telephony.TelephonyListenerManager
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class TelephonyRepositoryImplTest : SysuiTestCase() {

    @Mock private lateinit var manager: TelephonyListenerManager
    @Mock private lateinit var telecomManager: TelecomManager

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var underTest: TelephonyRepositoryImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(telecomManager.isInCall).thenReturn(false)

        underTest =
            TelephonyRepositoryImpl(
                applicationScope = testScope.backgroundScope,
                applicationContext = context,
                backgroundDispatcher = kosmos.testDispatcher,
                manager = manager,
                telecomManager = telecomManager,
            )
    }

    @Test
    fun callState() =
        testScope.runTest {
            val callState by collectLastValue(underTest.callState)
            runCurrent()

            val listenerCaptor = kotlinArgumentCaptor<TelephonyCallback.CallStateListener>()
            verify(manager).addCallStateListener(listenerCaptor.capture())
            val listener = listenerCaptor.value

            listener.onCallStateChanged(0)
            assertThat(callState).isEqualTo(0)

            listener.onCallStateChanged(1)
            assertThat(callState).isEqualTo(1)

            listener.onCallStateChanged(2)
            assertThat(callState).isEqualTo(2)
        }

    @Test
    fun isInCall() =
        testScope.runTest {
            val isInCall by collectLastValue(underTest.isInCall)
            runCurrent()

            val listenerCaptor = kotlinArgumentCaptor<TelephonyCallback.CallStateListener>()
            verify(manager).addCallStateListener(listenerCaptor.capture())
            val listener = listenerCaptor.value
            whenever(telecomManager.isInCall).thenReturn(true)
            listener.onCallStateChanged(TelephonyManager.CALL_STATE_OFFHOOK)

            assertThat(isInCall).isTrue()

            whenever(telecomManager.isInCall).thenReturn(false)
            listener.onCallStateChanged(TelephonyManager.CALL_STATE_IDLE)

            assertThat(isInCall).isFalse()
        }
}
