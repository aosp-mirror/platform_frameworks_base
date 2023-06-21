/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.disableflags.data.repository

import android.app.StatusBarManager.DISABLE2_NONE
import android.app.StatusBarManager.DISABLE_CLOCK
import android.app.StatusBarManager.DISABLE_NONE
import android.app.StatusBarManager.DISABLE_NOTIFICATION_ALERTS
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.disableflags.DisableFlagsLogger
import com.android.systemui.statusbar.disableflags.data.model.DisableFlagsModel
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.verify

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class DisableFlagsRepositoryTest : SysuiTestCase() {

    private lateinit var underTest: DisableFlagsRepository

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val commandQueue: CommandQueue = mock()
    private val logBuffer = LogBufferFactory(DumpManager(), mock()).create("buffer", 10)
    private val disableFlagsLogger = DisableFlagsLogger()

    @Before
    fun setUp() {
        underTest =
            DisableFlagsRepository(
                commandQueue,
                DISPLAY_ID,
                testScope.backgroundScope,
                logBuffer,
                disableFlagsLogger,
            )
    }

    @Test
    fun disableFlags_initialValue_none() {
        assertThat(underTest.disableFlags.value)
            .isEqualTo(DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE))
    }

    @Test
    fun disableFlags_noSubscribers_callbackStillRegistered() =
        testScope.runTest { verify(commandQueue).addCallback(any()) }

    @Test
    fun disableFlags_notifAlertsNotDisabled_notifAlertsEnabledTrue() =
        testScope.runTest {
            getCommandQueueCallback()
                .disable(DISPLAY_ID, DISABLE_NONE, DISABLE2_NONE, /* animate= */ false)

            assertThat(underTest.disableFlags.value.areNotificationAlertsEnabled()).isTrue()
        }

    @Test
    fun disableFlags_notifAlertsDisabled_notifAlertsEnabledFalse() =
        testScope.runTest {
            getCommandQueueCallback()
                .disable(
                    DISPLAY_ID,
                    DISABLE_NOTIFICATION_ALERTS,
                    DISABLE2_NONE,
                    /* animate= */ false,
                )

            assertThat(underTest.disableFlags.value.areNotificationAlertsEnabled()).isFalse()
        }

    @Test
    fun disableFlags_notifAlertsDisabled_differentDisplay_notifAlertsEnabledTrue() =
        testScope.runTest {
            val wrongDisplayId = DISPLAY_ID + 10

            getCommandQueueCallback()
                .disable(
                    wrongDisplayId,
                    DISABLE_NOTIFICATION_ALERTS,
                    DISABLE2_NONE,
                    /* animate= */ false,
                )

            // THEN our repo reports them as still enabled
            assertThat(underTest.disableFlags.value.areNotificationAlertsEnabled()).isTrue()
        }

    @Test
    fun disableFlags_reactsToChanges() =
        testScope.runTest {
            getCommandQueueCallback()
                .disable(
                    DISPLAY_ID,
                    DISABLE_NOTIFICATION_ALERTS,
                    DISABLE2_NONE,
                    /* animate= */ false,
                )
            assertThat(underTest.disableFlags.value.areNotificationAlertsEnabled()).isFalse()

            getCommandQueueCallback()
                .disable(
                    DISPLAY_ID,
                    DISABLE_CLOCK, // Unrelated to notifications
                    DISABLE2_NONE,
                    /* animate= */ false,
                )
            assertThat(underTest.disableFlags.value.areNotificationAlertsEnabled()).isTrue()

            getCommandQueueCallback()
                .disable(
                    DISPLAY_ID,
                    DISABLE_NOTIFICATION_ALERTS,
                    DISABLE2_NONE,
                    /* animate= */ false,
                )
            assertThat(underTest.disableFlags.value.areNotificationAlertsEnabled()).isFalse()
        }

    private fun getCommandQueueCallback(): CommandQueue.Callbacks {
        val callbackCaptor = argumentCaptor<CommandQueue.Callbacks>()
        verify(commandQueue).addCallback(callbackCaptor.capture())
        return callbackCaptor.value
    }

    private companion object {
        const val DISPLAY_ID = 1
    }
}
