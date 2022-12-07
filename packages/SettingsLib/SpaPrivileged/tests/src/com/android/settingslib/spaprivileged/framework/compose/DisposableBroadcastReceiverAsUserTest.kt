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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.Mockito.`when` as whenever

@RunWith(AndroidJUnit4::class)
class DisposableBroadcastReceiverAsUserTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var context: Context

    private var registeredBroadcastReceiver: BroadcastReceiver? = null

    @Before
    fun setUp() {
        whenever(context.registerReceiverAsUser(any(), any(), any(), any(), any()))
            .thenAnswer {
                registeredBroadcastReceiver = it.arguments[0] as BroadcastReceiver
                null
            }
    }

    @Test
    fun broadcastReceiver_registered() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                DisposableBroadcastReceiverAsUser(IntentFilter(), USER_HANDLE) {}
            }
        }

        assertThat(registeredBroadcastReceiver).isNotNull()
    }

    @Test
    fun broadcastReceiver_isCalledOnReceive() {
        var onReceiveIsCalled = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                DisposableBroadcastReceiverAsUser(IntentFilter(), USER_HANDLE) {
                    onReceiveIsCalled = true
                }
            }
        }

        registeredBroadcastReceiver!!.onReceive(context, Intent())

        assertThat(onReceiveIsCalled).isTrue()
    }

    @Test
    fun broadcastReceiver_onStartIsCalled() {
        var onStartIsCalled = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                DisposableBroadcastReceiverAsUser(
                    intentFilter = IntentFilter(),
                    userHandle = USER_HANDLE,
                    onStart = { onStartIsCalled = true },
                    onReceive = {},
                )
            }
        }

        assertThat(onStartIsCalled).isTrue()
    }

    private companion object {
        val USER_HANDLE: UserHandle = UserHandle.of(0)
    }
}
