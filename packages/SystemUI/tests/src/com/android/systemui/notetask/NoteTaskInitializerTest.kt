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
package com.android.systemui.notetask

import android.test.suitebuilder.annotation.SmallTest
import android.view.KeyEvent
import androidx.test.runner.AndroidJUnit4
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.util.mockito.whenever
import com.android.wm.shell.bubbles.Bubbles
import java.util.Optional
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/**
 * Tests for [NoteTaskController].
 *
 * Build/Install/Run:
 * - atest SystemUITests:NoteTaskInitializerTest
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
internal class NoteTaskInitializerTest : SysuiTestCase() {

    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var bubbles: Bubbles
    @Mock lateinit var optionalBubbles: Optional<Bubbles>
    @Mock lateinit var noteTaskController: NoteTaskController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(optionalBubbles.isPresent).thenReturn(true)
        whenever(optionalBubbles.orElse(null)).thenReturn(bubbles)
    }

    private fun createNoteTaskInitializer(isEnabled: Boolean = true): NoteTaskInitializer {
        return NoteTaskInitializer(
            optionalBubbles = optionalBubbles,
            noteTaskController = noteTaskController,
            commandQueue = commandQueue,
            isEnabled = isEnabled,
        )
    }

    // region initializer
    @Test
    fun initialize_shouldAddCallbacks() {
        createNoteTaskInitializer().initialize()

        verify(commandQueue).addCallback(any())
    }

    @Test
    fun initialize_flagDisabled_shouldDoNothing() {
        createNoteTaskInitializer(isEnabled = false).initialize()

        verify(commandQueue, never()).addCallback(any())
    }

    @Test
    fun initialize_bubblesNotPresent_shouldDoNothing() {
        whenever(optionalBubbles.isPresent).thenReturn(false)

        createNoteTaskInitializer().initialize()

        verify(commandQueue, never()).addCallback(any())
    }

    @Test
    fun initialize_flagEnabled_shouldEnableShortcut() {
        createNoteTaskInitializer().initialize()

        verify(noteTaskController).setNoteTaskShortcutEnabled(true)
    }

    @Test
    fun initialize_flagDisabled_shouldDisableShortcut() {
        createNoteTaskInitializer(isEnabled = false).initialize()

        verify(noteTaskController).setNoteTaskShortcutEnabled(false)
    }
    // endregion

    // region handleSystemKey
    @Test
    fun handleSystemKey_receiveValidSystemKey_shouldShowNoteTask() {
        createNoteTaskInitializer().callbacks.handleSystemKey(KeyEvent.KEYCODE_VIDEO_APP_1)

        verify(noteTaskController).showNoteTask()
    }

    @Test
    fun handleSystemKey_receiveInvalidSystemKey_shouldDoNothing() {
        createNoteTaskInitializer().callbacks.handleSystemKey(KeyEvent.KEYCODE_UNKNOWN)

        verify(noteTaskController, never()).showNoteTask()
    }
    // endregion
}
