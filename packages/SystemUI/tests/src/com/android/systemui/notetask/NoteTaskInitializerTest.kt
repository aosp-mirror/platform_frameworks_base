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

import android.app.role.RoleManager
import android.test.suitebuilder.annotation.SmallTest
import android.view.KeyEvent
import androidx.test.runner.AndroidJUnit4
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.time.FakeSystemClock
import com.android.wm.shell.bubbles.Bubbles
import java.util.Optional
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.MockitoAnnotations

/** atest SystemUITests:NoteTaskInitializerTest */
@SmallTest
@RunWith(AndroidJUnit4::class)
internal class NoteTaskInitializerTest : SysuiTestCase() {

    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var bubbles: Bubbles
    @Mock lateinit var controller: NoteTaskController
    @Mock lateinit var roleManager: RoleManager
    private val clock = FakeSystemClock()
    private val executor = FakeExecutor(clock)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    private fun createNoteTaskInitializer(
        isEnabled: Boolean = true,
        bubbles: Bubbles? = this.bubbles,
    ): NoteTaskInitializer {
        return NoteTaskInitializer(
            controller = controller,
            commandQueue = commandQueue,
            optionalBubbles = Optional.ofNullable(bubbles),
            isEnabled = isEnabled,
            roleManager = roleManager,
            backgroundExecutor = executor,
        )
    }

    // region initializer
    @Test
    fun initialize() {
        createNoteTaskInitializer().initialize()

        verify(controller).setNoteTaskShortcutEnabled(true)
        verify(commandQueue).addCallback(any())
        verify(roleManager).addOnRoleHoldersChangedListenerAsUser(any(), any(), any())
    }

    @Test
    fun initialize_flagDisabled() {
        createNoteTaskInitializer(isEnabled = false).initialize()

        verify(controller, never()).setNoteTaskShortcutEnabled(any())
        verify(commandQueue, never()).addCallback(any())
        verify(roleManager, never()).addOnRoleHoldersChangedListenerAsUser(any(), any(), any())
    }

    @Test
    fun initialize_bubblesNotPresent() {
        createNoteTaskInitializer(bubbles = null).initialize()

        verify(controller, never()).setNoteTaskShortcutEnabled(any())
        verify(commandQueue, never()).addCallback(any())
        verify(roleManager, never()).addOnRoleHoldersChangedListenerAsUser(any(), any(), any())
    }
    // endregion

    // region handleSystemKey
    @Test
    fun handleSystemKey_receiveValidSystemKey_shouldShowNoteTask() {
        createNoteTaskInitializer().callbacks.handleSystemKey(KeyEvent(KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL))

        verify(controller).showNoteTask(entryPoint = NoteTaskEntryPoint.TAIL_BUTTON)
    }

    @Test
    fun handleSystemKey_receiveKeyboardShortcut_shouldShowNoteTask() {
        createNoteTaskInitializer().callbacks.handleSystemKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_N, 0, KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON))

        verify(controller).showNoteTask(entryPoint = NoteTaskEntryPoint.KEYBOARD_SHORTCUT)
    }
    
    @Test
    fun handleSystemKey_receiveInvalidSystemKey_shouldDoNothing() {
        createNoteTaskInitializer().callbacks.handleSystemKey(KeyEvent(KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_UNKNOWN))

        verifyZeroInteractions(controller)
    }
    // endregion
}
