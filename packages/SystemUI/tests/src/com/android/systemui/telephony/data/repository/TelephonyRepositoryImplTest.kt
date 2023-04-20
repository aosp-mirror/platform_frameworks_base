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

import android.telephony.TelephonyCallback
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.telephony.TelephonyListenerManager
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class TelephonyRepositoryImplTest : SysuiTestCase() {

    @Mock private lateinit var manager: TelephonyListenerManager

    private lateinit var underTest: TelephonyRepositoryImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest =
            TelephonyRepositoryImpl(
                manager = manager,
            )
    }

    @Test
    fun callState() =
        runBlocking(IMMEDIATE) {
            var callState: Int? = null
            val job = underTest.callState.onEach { callState = it }.launchIn(this)
            val listenerCaptor = kotlinArgumentCaptor<TelephonyCallback.CallStateListener>()
            verify(manager).addCallStateListener(listenerCaptor.capture())
            val listener = listenerCaptor.value

            listener.onCallStateChanged(0)
            assertThat(callState).isEqualTo(0)

            listener.onCallStateChanged(1)
            assertThat(callState).isEqualTo(1)

            listener.onCallStateChanged(2)
            assertThat(callState).isEqualTo(2)

            job.cancel()

            verify(manager).removeCallStateListener(listener)
        }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
