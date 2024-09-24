/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.window.data.repository

import android.app.StatusBarManager.WINDOW_NAVIGATION_BAR
import android.app.StatusBarManager.WINDOW_STATE_HIDDEN
import android.app.StatusBarManager.WINDOW_STATE_HIDING
import android.app.StatusBarManager.WINDOW_STATE_SHOWING
import android.app.StatusBarManager.WINDOW_STATUS_BAR
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.commandQueue
import com.android.systemui.statusbar.window.data.model.StatusBarWindowState
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.Mockito.verify
import org.mockito.kotlin.argumentCaptor

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class StatusBarWindowStatePerDisplayRepositoryTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val commandQueue = kosmos.commandQueue
    private val underTest =
        StatusBarWindowStatePerDisplayRepositoryImpl(
            DISPLAY_ID,
            commandQueue,
            testScope.backgroundScope,
        )

    private val callback: CommandQueue.Callbacks
        get() {
            testScope.runCurrent()
            val callbackCaptor = argumentCaptor<CommandQueue.Callbacks>()
            verify(commandQueue).addCallback(callbackCaptor.capture())
            return callbackCaptor.firstValue
        }

    @Test
    fun windowState_notSameDisplayId_notUpdated() =
        testScope.runTest {
            val latest by collectLastValue(underTest.windowState)
            assertThat(latest).isEqualTo(StatusBarWindowState.Hidden)

            callback.setWindowState(DISPLAY_ID + 1, WINDOW_STATUS_BAR, WINDOW_STATE_SHOWING)

            assertThat(latest).isEqualTo(StatusBarWindowState.Hidden)
        }

    @Test
    fun windowState_notStatusBarWindow_notUpdated() =
        testScope.runTest {
            val latest by collectLastValue(underTest.windowState)
            assertThat(latest).isEqualTo(StatusBarWindowState.Hidden)

            callback.setWindowState(DISPLAY_ID, WINDOW_NAVIGATION_BAR, WINDOW_STATE_SHOWING)

            assertThat(latest).isEqualTo(StatusBarWindowState.Hidden)
        }

    @Test
    fun windowState_showing_updated() =
        testScope.runTest {
            val latest by collectLastValue(underTest.windowState)

            callback.setWindowState(DISPLAY_ID, WINDOW_STATUS_BAR, WINDOW_STATE_SHOWING)

            assertThat(latest).isEqualTo(StatusBarWindowState.Showing)
        }

    @Test
    fun windowState_hiding_updated() =
        testScope.runTest {
            val latest by collectLastValue(underTest.windowState)

            callback.setWindowState(DISPLAY_ID, WINDOW_STATUS_BAR, WINDOW_STATE_HIDING)

            assertThat(latest).isEqualTo(StatusBarWindowState.Hiding)
        }

    @Test
    fun windowState_hidden_updated() =
        testScope.runTest {
            val latest by collectLastValue(underTest.windowState)
            callback.setWindowState(DISPLAY_ID, WINDOW_STATUS_BAR, WINDOW_STATE_SHOWING)
            assertThat(latest).isEqualTo(StatusBarWindowState.Showing)

            callback.setWindowState(DISPLAY_ID, WINDOW_STATUS_BAR, WINDOW_STATE_HIDDEN)

            assertThat(latest).isEqualTo(StatusBarWindowState.Hidden)
        }
}

private const val DISPLAY_ID = 10
