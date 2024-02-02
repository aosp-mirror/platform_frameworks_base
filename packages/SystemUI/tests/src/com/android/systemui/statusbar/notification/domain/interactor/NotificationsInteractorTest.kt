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

package com.android.systemui.statusbar.notification.domain.interactor

import android.app.StatusBarManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.disableflags.DisableFlagsLogger
import com.android.systemui.statusbar.disableflags.data.repository.DisableFlagsRepository
import com.android.systemui.statusbar.disableflags.data.repository.DisableFlagsRepositoryImpl
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.verify

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsInteractorTest : SysuiTestCase() {

    private lateinit var underTest: NotificationsInteractor

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val commandQueue: CommandQueue = mock()
    private val logBuffer = LogBufferFactory(DumpManager(), mock()).create("buffer", 10)
    private val disableFlagsLogger = DisableFlagsLogger()
    private lateinit var disableFlagsRepository: DisableFlagsRepository

    @Before
    fun setUp() {
        disableFlagsRepository =
            DisableFlagsRepositoryImpl(
                commandQueue,
                DISPLAY_ID,
                testScope.backgroundScope,
                mock(),
                logBuffer,
                disableFlagsLogger,
            )
        underTest = NotificationsInteractor(disableFlagsRepository)
    }

    @Test
    fun disableFlags_notifAlertsNotDisabled_notifAlertsEnabledTrue() {
        val callback = getCommandQueueCallback()

        callback.disable(
            DISPLAY_ID,
            StatusBarManager.DISABLE_NONE,
            StatusBarManager.DISABLE2_NONE,
            /* animate= */ false
        )

        assertThat(underTest.areNotificationAlertsEnabled()).isTrue()
    }

    @Test
    fun disableFlags_notifAlertsDisabled_notifAlertsEnabledFalse() {
        val callback = getCommandQueueCallback()

        callback.disable(
            DISPLAY_ID,
            StatusBarManager.DISABLE_NOTIFICATION_ALERTS,
            StatusBarManager.DISABLE2_NONE,
            /* animate= */ false
        )

        assertThat(underTest.areNotificationAlertsEnabled()).isFalse()
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
