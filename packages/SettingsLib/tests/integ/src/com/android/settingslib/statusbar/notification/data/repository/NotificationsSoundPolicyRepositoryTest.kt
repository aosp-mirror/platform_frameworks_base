/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.settingslib.statusbar.notification.data.repository

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings.Global
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.statusbar.notification.data.model.ZenMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@SmallTest
class NotificationsSoundPolicyRepositoryTest {

    @Mock private lateinit var context: Context
    @Mock private lateinit var notificationManager: NotificationManager
    @Captor private lateinit var receiverCaptor: ArgumentCaptor<BroadcastReceiver>

    private lateinit var underTest: NotificationsSoundPolicyRepository

    private val testScope: TestScope = TestScope()

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        underTest =
            NotificationsSoundPolicyRepositoryImpl(
                context,
                notificationManager,
                testScope.backgroundScope,
                testScope.testScheduler,
            )
    }

    @Test
    fun policyChanges_repositoryEmits() {
        testScope.runTest {
            val values = mutableListOf<NotificationManager.Policy?>()
            `when`(notificationManager.notificationPolicy).thenReturn(testPolicy1)
            underTest.notificationPolicy.onEach { values.add(it) }.launchIn(backgroundScope)
            runCurrent()

            `when`(notificationManager.notificationPolicy).thenReturn(testPolicy2)
            triggerIntent(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED)
            runCurrent()

            assertThat(values)
                .containsExactlyElementsIn(listOf(null, testPolicy1, testPolicy2))
                .inOrder()
        }
    }

    @Test
    fun zenModeChanges_repositoryEmits() {
        testScope.runTest {
            val values = mutableListOf<ZenMode?>()
            `when`(notificationManager.zenMode).thenReturn(Global.ZEN_MODE_OFF)
            underTest.zenMode.onEach { values.add(it) }.launchIn(backgroundScope)
            runCurrent()

            `when`(notificationManager.zenMode).thenReturn(Global.ZEN_MODE_ALARMS)
            triggerIntent(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            runCurrent()

            assertThat(values)
                .containsExactlyElementsIn(
                    listOf(null, ZenMode(Global.ZEN_MODE_OFF), ZenMode(Global.ZEN_MODE_ALARMS))
                )
                .inOrder()
        }
    }

    private fun triggerIntent(action: String) {
        verify(context).registerReceiver(receiverCaptor.capture(), any())
        receiverCaptor.value.onReceive(context, Intent(action))
    }

    private companion object {
        val testPolicy1 =
            NotificationManager.Policy(
                /* priorityCategories = */ 1,
                /* priorityCallSenders =*/ 1,
                /* priorityMessageSenders = */ 1,
            )
        val testPolicy2 =
            NotificationManager.Policy(
                /* priorityCategories = */ 2,
                /* priorityCallSenders =*/ 2,
                /* priorityMessageSenders = */ 2,
            )
    }
}
