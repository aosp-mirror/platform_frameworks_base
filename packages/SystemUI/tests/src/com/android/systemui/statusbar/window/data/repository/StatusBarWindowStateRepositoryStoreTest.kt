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

package com.android.systemui.statusbar.window.data.repository

import android.app.StatusBarManager.WINDOW_STATE_HIDDEN
import android.app.StatusBarManager.WINDOW_STATE_SHOWING
import android.app.StatusBarManager.WINDOW_STATUS_BAR
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.settings.displayTracker
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.commandQueue
import com.android.systemui.statusbar.window.data.model.StatusBarWindowState
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mockito.Mockito.verify
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.reset

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class StatusBarWindowStateRepositoryStoreTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val commandQueue = kosmos.commandQueue
    private val defaultDisplayId = kosmos.displayTracker.defaultDisplayId

    private val underTest = kosmos.statusBarWindowStateRepositoryStore

    @Test
    fun defaultDisplay_repoIsForDefaultDisplay() =
        testScope.runTest {
            val repo = underTest.defaultDisplay
            val latest by collectLastValue(repo.windowState)

            testScope.runCurrent()
            val callbackCaptor = argumentCaptor<CommandQueue.Callbacks>()
            verify(commandQueue).addCallback(callbackCaptor.capture())
            val callback = callbackCaptor.firstValue

            // WHEN a default display callback is sent
            callback.setWindowState(defaultDisplayId, WINDOW_STATUS_BAR, WINDOW_STATE_SHOWING)

            // THEN its value is used
            assertThat(latest).isEqualTo(StatusBarWindowState.Showing)

            // WHEN a non-default display callback is sent
            callback.setWindowState(defaultDisplayId + 1, WINDOW_STATUS_BAR, WINDOW_STATE_HIDDEN)

            // THEN its value is NOT used
            assertThat(latest).isEqualTo(StatusBarWindowState.Showing)
        }

    @Test
    fun forDisplay_repoIsForSpecifiedDisplay() =
        testScope.runTest {
            // The repository store will always create a repository for the default display, which
            // will always add a callback to commandQueue. Ignore that callback here.
            testScope.runCurrent()
            reset(commandQueue)

            val displayId = defaultDisplayId + 15
            val repo = underTest.forDisplay(displayId)
            val latest by collectLastValue(repo.windowState)

            testScope.runCurrent()
            val callbackCaptor = argumentCaptor<CommandQueue.Callbacks>()
            verify(commandQueue).addCallback(callbackCaptor.capture())
            val callback = callbackCaptor.firstValue

            // WHEN a default display callback is sent
            callback.setWindowState(defaultDisplayId, WINDOW_STATUS_BAR, WINDOW_STATE_SHOWING)

            // THEN its value is NOT used
            assertThat(latest).isEqualTo(StatusBarWindowState.Hidden)

            // WHEN a callback for this display is sent
            callback.setWindowState(displayId, WINDOW_STATUS_BAR, WINDOW_STATE_SHOWING)

            // THEN its value is used
            assertThat(latest).isEqualTo(StatusBarWindowState.Showing)
        }

    @Test
    fun forDisplay_reusesRepoForSameDisplayId() =
        testScope.runTest {
            // The repository store will always create a repository for the default display, which
            // will always add a callback to commandQueue. Ignore that callback here.
            testScope.runCurrent()
            reset(commandQueue)

            val displayId = defaultDisplayId + 15
            val firstRepo = underTest.forDisplay(displayId)
            testScope.runCurrent()
            val secondRepo = underTest.forDisplay(displayId)
            testScope.runCurrent()

            assertThat(firstRepo).isEqualTo(secondRepo)
            // Verify that we only added 1 CommandQueue.Callback because we only created 1 repo.
            val callbackCaptor = argumentCaptor<CommandQueue.Callbacks>()
            verify(commandQueue).addCallback(callbackCaptor.capture())
        }
}
