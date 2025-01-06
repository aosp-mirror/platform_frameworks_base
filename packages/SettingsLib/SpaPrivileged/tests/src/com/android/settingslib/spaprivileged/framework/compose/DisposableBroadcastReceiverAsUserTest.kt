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
 */

package com.android.settingslib.spaprivileged.framework.compose

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock

@RunWith(AndroidJUnit4::class)
class DisposableBroadcastReceiverAsUserTest {
    @get:Rule val composeTestRule = createComposeRule()

    private var registeredBroadcastReceiver: BroadcastReceiver? = null

    private val context =
        mock<Context> {
            on {
                registerReceiverAsUser(
                    any(),
                    eq(USER_HANDLE),
                    eq(INTENT_FILTER),
                    isNull(),
                    isNull(),
                    eq(Context.RECEIVER_NOT_EXPORTED),
                )
            } doAnswer
                {
                    registeredBroadcastReceiver = it.arguments[0] as BroadcastReceiver
                    null
                }

            on { unregisterReceiver(any()) } doAnswer
                {
                    if (registeredBroadcastReceiver === it.arguments[0]) {
                        registeredBroadcastReceiver = null
                    }
                }
        }

    @Test
    fun broadcastReceiver_registered() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                DisposableBroadcastReceiverAsUser(INTENT_FILTER, USER_HANDLE) {}
            }
        }

        composeTestRule.waitUntil { registeredBroadcastReceiver != null }
    }

    @Test
    fun broadcastReceiver_isCalledOnReceive() {
        var onReceiveIsCalled = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                DisposableBroadcastReceiverAsUser(INTENT_FILTER, USER_HANDLE) {
                    onReceiveIsCalled = true
                }
            }
        }

        registeredBroadcastReceiver!!.onReceive(context, Intent())

        composeTestRule.waitUntil { onReceiveIsCalled }
    }

    @Test
    fun broadcastReceiver_unregistered() {
        var isBroadcastReceiverRegistered by mutableStateOf(true)
        composeTestRule.setContent {
            if (isBroadcastReceiverRegistered) {
                CompositionLocalProvider(LocalContext provides context) {
                    DisposableBroadcastReceiverAsUser(INTENT_FILTER, USER_HANDLE) {}
                }
            }
        }
        composeTestRule.waitUntil { registeredBroadcastReceiver != null }

        isBroadcastReceiverRegistered = false

        composeTestRule.waitUntil { registeredBroadcastReceiver == null }
    }

    private companion object {
        val USER_HANDLE: UserHandle = UserHandle.of(0)

        val INTENT_FILTER = IntentFilter()
    }
}
