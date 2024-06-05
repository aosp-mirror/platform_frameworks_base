/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.spaprivileged.framework.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.testutils.firstWithTimeoutOrNull
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub

@RunWith(AndroidJUnit4::class)
class BroadcastReceiverAsUserFlowTest {

    private var registeredBroadcastReceiver: BroadcastReceiver? = null

    private val context = mock<Context> {
        on {
            registerReceiverAsUser(
                any(),
                eq(USER_HANDLE),
                eq(INTENT_FILTER),
                isNull(),
                isNull(),
                eq(Context.RECEIVER_NOT_EXPORTED),
            )
        } doAnswer {
            registeredBroadcastReceiver = it.arguments[0] as BroadcastReceiver
            null
        }
    }

    @Test
    fun broadcastReceiverAsUserFlow_registered() = runBlocking {
        val flow = context.broadcastReceiverAsUserFlow(INTENT_FILTER, USER_HANDLE)

        flow.firstWithTimeoutOrNull()

        assertThat(registeredBroadcastReceiver).isNotNull()
    }

    @Test
    fun broadcastReceiverAsUserFlow_isCalledOnReceive() = runBlocking {
        var onReceiveIsCalled = false
        launch {
            context.broadcastReceiverAsUserFlow(INTENT_FILTER, USER_HANDLE).first {
                onReceiveIsCalled = true
                true
            }
        }

        delay(100)
        registeredBroadcastReceiver!!.onReceive(context, Intent())
        delay(100)

        assertThat(onReceiveIsCalled).isTrue()
    }

    @Test
    fun broadcastReceiverAsUserFlow_unregisterReceiverThrowException_noCrash() = runBlocking {
        context.stub {
            on { unregisterReceiver(any()) } doThrow IllegalArgumentException()
        }
        val flow = context.broadcastReceiverAsUserFlow(INTENT_FILTER, USER_HANDLE)

        flow.firstWithTimeoutOrNull()

        assertThat(registeredBroadcastReceiver).isNotNull()
    }

    private companion object {
        val USER_HANDLE: UserHandle = UserHandle.of(0)

        val INTENT_FILTER = IntentFilter()
    }
}
