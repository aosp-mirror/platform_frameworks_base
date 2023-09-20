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

package com.android.systemui.statusbar.data.repository

import android.view.WindowInsets
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.Mockito.verify

@SmallTest
class StatusBarModeRepositoryImplTest : SysuiTestCase() {
    private val commandQueue = mock<CommandQueue>()

    private val underTest =
        StatusBarModeRepositoryImpl(
                DISPLAY_ID,
                commandQueue,
            )
            .apply { this.start() }

    private val commandQueueCallback: CommandQueue.Callbacks
        get() {
            val callbackCaptor = argumentCaptor<CommandQueue.Callbacks>()
            verify(commandQueue).addCallback(callbackCaptor.capture())
            return callbackCaptor.value
        }

    @Test
    fun isTransientShown_commandQueueShow_wrongDisplayId_notUpdated() {
        commandQueueCallback.showTransient(
            DISPLAY_ID + 1,
            WindowInsets.Type.statusBars(),
            /* isGestureOnSystemBar= */ false,
        )

        assertThat(underTest.isTransientShown.value).isFalse()
    }

    @Test
    fun isTransientShown_commandQueueShow_notStatusBarType_notUpdated() {
        commandQueueCallback.showTransient(
            DISPLAY_ID,
            WindowInsets.Type.navigationBars(),
            /* isGestureOnSystemBar= */ false,
        )

        assertThat(underTest.isTransientShown.value).isFalse()
    }

    @Test
    fun isTransientShown_commandQueueShow_true() {
        commandQueueCallback.showTransient(
            DISPLAY_ID,
            WindowInsets.Type.statusBars(),
            /* isGestureOnSystemBar= */ false,
        )

        assertThat(underTest.isTransientShown.value).isTrue()
    }

    @Test
    fun isTransientShown_commandQueueShow_statusBarAndOtherTypes_true() {
        commandQueueCallback.showTransient(
            DISPLAY_ID,
            WindowInsets.Type.statusBars().or(WindowInsets.Type.navigationBars()),
            /* isGestureOnSystemBar= */ false,
        )

        assertThat(underTest.isTransientShown.value).isTrue()
    }

    @Test
    fun isTransientShown_commandQueueAbort_wrongDisplayId_notUpdated() {
        // Start as true
        commandQueueCallback.showTransient(
            DISPLAY_ID,
            WindowInsets.Type.statusBars(),
            /* isGestureOnSystemBar= */ false,
        )
        assertThat(underTest.isTransientShown.value).isTrue()

        // GIVEN the wrong display ID
        commandQueueCallback.abortTransient(DISPLAY_ID + 1, WindowInsets.Type.statusBars())

        // THEN the old value remains
        assertThat(underTest.isTransientShown.value).isTrue()
    }

    @Test
    fun isTransientShown_commandQueueAbort_notStatusBarType_notUpdated() {
        // Start as true
        commandQueueCallback.showTransient(
            DISPLAY_ID,
            WindowInsets.Type.statusBars(),
            /* isGestureOnSystemBar= */ false,
        )
        assertThat(underTest.isTransientShown.value).isTrue()

        // GIVEN the wrong type
        commandQueueCallback.abortTransient(DISPLAY_ID, WindowInsets.Type.navigationBars())

        // THEN the old value remains
        assertThat(underTest.isTransientShown.value).isTrue()
    }

    @Test
    fun isTransientShown_commandQueueAbort_false() {
        // Start as true
        commandQueueCallback.showTransient(
            DISPLAY_ID,
            WindowInsets.Type.statusBars(),
            /* isGestureOnSystemBar= */ false,
        )
        assertThat(underTest.isTransientShown.value).isTrue()

        commandQueueCallback.abortTransient(DISPLAY_ID, WindowInsets.Type.statusBars())

        assertThat(underTest.isTransientShown.value).isFalse()
    }

    @Test
    fun isTransientShown_commandQueueAbort_statusBarAndOtherTypes_false() {
        // Start as true
        commandQueueCallback.showTransient(
            DISPLAY_ID,
            WindowInsets.Type.statusBars(),
            /* isGestureOnSystemBar= */ false,
        )
        assertThat(underTest.isTransientShown.value).isTrue()

        commandQueueCallback.abortTransient(
            DISPLAY_ID,
            WindowInsets.Type.statusBars().or(WindowInsets.Type.captionBar()),
        )

        assertThat(underTest.isTransientShown.value).isFalse()
    }

    @Test
    fun isTransientShown_showTransient_true() {
        underTest.showTransient()

        assertThat(underTest.isTransientShown.value).isTrue()
    }

    @Test
    fun isTransientShown_clearTransient_false() {
        // Start as true
        commandQueueCallback.showTransient(
            DISPLAY_ID,
            WindowInsets.Type.statusBars(),
            /* isGestureOnSystemBar= */ false,
        )
        assertThat(underTest.isTransientShown.value).isTrue()

        underTest.clearTransient()

        assertThat(underTest.isTransientShown.value).isFalse()
    }

    private companion object {
        const val DISPLAY_ID = 5
    }
}
