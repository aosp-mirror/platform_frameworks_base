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

package com.android.systemui.statusbar.window

import android.app.StatusBarManager.WindowVisibleState
import android.app.StatusBarManager.WINDOW_NAVIGATION_BAR
import android.app.StatusBarManager.WINDOW_STATE_HIDDEN
import android.app.StatusBarManager.WINDOW_STATE_SHOWING
import android.app.StatusBarManager.WINDOW_STATUS_BAR
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.CommandQueue
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
class StatusBarWindowStateControllerTest : SysuiTestCase() {
    private lateinit var controller: StatusBarWindowStateController
    private lateinit var callback: CommandQueue.Callbacks

    @Mock
    private lateinit var commandQueue: CommandQueue

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        controller = StatusBarWindowStateController(DISPLAY_ID, commandQueue)

        val callbackCaptor = ArgumentCaptor.forClass(CommandQueue.Callbacks::class.java)
        verify(commandQueue).addCallback(callbackCaptor.capture())
        callback = callbackCaptor.value!!
    }

    @Test
    fun setWindowState_notSameDisplayId_listenersNotNotified() {
        val listener = TestListener()
        controller.addListener(listener)

        callback.setWindowState(DISPLAY_ID + 1, WINDOW_STATUS_BAR, WINDOW_STATE_HIDDEN)

        assertThat(listener.state).isNull()
    }

    @Test
    fun setWindowState_notStatusBarWindow_listenersNotNotified() {
        val listener = TestListener()
        controller.addListener(listener)

        callback.setWindowState(DISPLAY_ID, WINDOW_NAVIGATION_BAR, WINDOW_STATE_HIDDEN)

        assertThat(listener.state).isNull()
    }

    @Test
    fun setWindowState_sameState_listenersNotNotified() {
        val listener = TestListener()
        controller.addListener(listener)

        callback.setWindowState(DISPLAY_ID, WINDOW_STATUS_BAR, WINDOW_STATE_SHOWING)

        assertThat(listener.state).isNull()
    }

    @Test
    fun setWindowState_newState_listenersNotified() {
        val listener = TestListener()
        controller.addListener(listener)
        val newState = WINDOW_STATE_HIDDEN

        callback.setWindowState(DISPLAY_ID, WINDOW_STATUS_BAR, newState)

        assertThat(listener.state).isEqualTo(newState)
    }

    private class TestListener : StatusBarWindowStateListener {
        @WindowVisibleState var state: Int? = null
        override fun onStatusBarWindowStateChanged(@WindowVisibleState state: Int) {
            this.state = state
        }
    }
}

private const val DISPLAY_ID = 10
