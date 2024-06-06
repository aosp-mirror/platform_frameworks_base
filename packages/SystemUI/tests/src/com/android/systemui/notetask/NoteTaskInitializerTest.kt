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
import android.app.role.RoleManager.ROLE_NOTES
import android.os.UserHandle
import android.os.UserManager
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_N
import android.view.KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.time.FakeSystemClock
import com.android.wm.shell.bubbles.Bubbles
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.MockitoAnnotations.initMocks

/** atest SystemUITests:NoteTaskInitializerTest */
@OptIn(ExperimentalCoroutinesApi::class, InternalNoteTaskApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
internal class NoteTaskInitializerTest : SysuiTestCase() {

    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var bubbles: Bubbles
    @Mock lateinit var controller: NoteTaskController
    @Mock lateinit var roleManager: RoleManager
    @Mock lateinit var userManager: UserManager
    @Mock lateinit var keyguardMonitor: KeyguardUpdateMonitor

    private val executor = FakeExecutor(FakeSystemClock())
    private val userTracker = FakeUserTracker()
    private val handlerCallbacks = mutableListOf<Runnable>()

    @Before
    fun setUp() {
        initMocks(this)
        whenever(keyguardMonitor.isUserUnlocked(userTracker.userId)).thenReturn(true)
    }

    private fun createUnderTest(
        isEnabled: Boolean,
        bubbles: Bubbles?,
    ): NoteTaskInitializer =
        NoteTaskInitializer(
            controller = controller,
            commandQueue = commandQueue,
            optionalBubbles = Optional.ofNullable(bubbles),
            isEnabled = isEnabled,
            roleManager = roleManager,
            userTracker = userTracker,
            keyguardUpdateMonitor = keyguardMonitor,
            backgroundExecutor = executor,
        )

    private fun createKeyEvent(
        action: Int,
        code: Int,
        downTime: Long = 0L,
        eventTime: Long = 0L,
        metaState: Int = 0
    ): KeyEvent = KeyEvent(downTime, eventTime, action, code, 0 /*repeat*/, metaState)

    @Test
    fun initialize_withUserUnlocked() {
        whenever(keyguardMonitor.isUserUnlocked(userTracker.userId)).thenReturn(true)

        createUnderTest(isEnabled = true, bubbles = bubbles).initialize()

        verify(commandQueue).addCallback(any())
        verify(roleManager).addOnRoleHoldersChangedListenerAsUser(any(), any(), any())
        verify(controller).updateNoteTaskForCurrentUserAndManagedProfiles()
        verify(keyguardMonitor).registerCallback(any())
    }

    @Test
    fun initialize_withUserLocked() {
        whenever(keyguardMonitor.isUserUnlocked(userTracker.userId)).thenReturn(false)

        createUnderTest(isEnabled = true, bubbles = bubbles).initialize()

        verify(commandQueue).addCallback(any())
        verify(roleManager).addOnRoleHoldersChangedListenerAsUser(any(), any(), any())
        verify(controller, never()).setNoteTaskShortcutEnabled(any(), any())
        verify(keyguardMonitor).registerCallback(any())
        assertThat(userTracker.callbacks).isNotEmpty()
    }

    @Test
    fun initialize_flagDisabled() {
        val underTest = createUnderTest(isEnabled = false, bubbles = bubbles)

        underTest.initialize()

        verifyZeroInteractions(
            commandQueue,
            bubbles,
            controller,
            roleManager,
            userManager,
            keyguardMonitor,
        )
    }

    @Test
    fun initialize_bubblesNotPresent() {
        val underTest = createUnderTest(isEnabled = true, bubbles = null)

        underTest.initialize()

        verifyZeroInteractions(
            commandQueue,
            bubbles,
            controller,
            roleManager,
            userManager,
            keyguardMonitor,
        )
    }

    @Test
    fun initialize_handleSystemKey() {
        val expectedKeyEvent =
            createKeyEvent(
                ACTION_DOWN,
                KEYCODE_N,
                metaState = KeyEvent.META_META_ON or KeyEvent.META_CTRL_ON
            )
        val underTest = createUnderTest(isEnabled = true, bubbles = bubbles)
        underTest.initialize()
        val callback = withArgCaptor { verify(commandQueue).addCallback(capture()) }

        callback.handleSystemKey(expectedKeyEvent)

        verify(controller).showNoteTask(any())
    }

    @Test
    fun initialize_userUnlocked_shouldUpdateNoteTask() {
        whenever(keyguardMonitor.isUserUnlocked(userTracker.userId)).thenReturn(false)
        val underTest = createUnderTest(isEnabled = true, bubbles = bubbles)
        underTest.initialize()
        val callback = withArgCaptor { verify(keyguardMonitor).registerCallback(capture()) }
        whenever(keyguardMonitor.isUserUnlocked(userTracker.userId)).thenReturn(true)

        callback.onUserUnlocked()

        verify(controller).updateNoteTaskForCurrentUserAndManagedProfiles()
    }

    @Test
    fun initialize_onRoleHoldersChanged_shouldRunOnRoleHoldersChanged() {
        val underTest = createUnderTest(isEnabled = true, bubbles = bubbles)
        underTest.initialize()
        val callback = withArgCaptor {
            verify(roleManager)
                .addOnRoleHoldersChangedListenerAsUser(any(), capture(), eq(UserHandle.ALL))
        }

        callback.onRoleHoldersChanged(ROLE_NOTES, userTracker.userHandle)

        verify(controller).onRoleHoldersChanged(ROLE_NOTES, userTracker.userHandle)
    }

    @Test
    fun initialize_onProfilesChanged_shouldUpdateNoteTask() {
        val underTest = createUnderTest(isEnabled = true, bubbles = bubbles)
        underTest.initialize()

        userTracker.callbacks.first().onProfilesChanged(emptyList())

        verify(controller, times(2)).updateNoteTaskForCurrentUserAndManagedProfiles()
    }

    @Test
    fun initialize_onUserChanged_shouldUpdateNoteTask() {
        val underTest = createUnderTest(isEnabled = true, bubbles = bubbles)
        underTest.initialize()

        userTracker.callbacks.first().onUserChanged(0, mock())

        verify(controller, times(2)).updateNoteTaskForCurrentUserAndManagedProfiles()
    }

    @Test
    fun tailButtonGestureDetection_singlePress_shouldShowNoteTaskOnUp() {
        val underTest = createUnderTest(isEnabled = true, bubbles = bubbles)
        underTest.initialize()
        val callback = withArgCaptor { verify(commandQueue).addCallback(capture()) }

        callback.handleSystemKey(
            createKeyEvent(ACTION_DOWN, KEYCODE_STYLUS_BUTTON_TAIL, downTime = 0, eventTime = 0)
        )
        verify(controller, never()).showNoteTask(any())

        callback.handleSystemKey(
            createKeyEvent(ACTION_UP, KEYCODE_STYLUS_BUTTON_TAIL, downTime = 0, eventTime = 50)
        )

        verify(controller).showNoteTask(any())
    }

    @Test
    fun tailButtonGestureDetection_doublePress_shouldNotShowNoteTaskTwice() {
        val underTest = createUnderTest(isEnabled = true, bubbles = bubbles)
        underTest.initialize()
        val callback = withArgCaptor { verify(commandQueue).addCallback(capture()) }

        callback.handleSystemKey(
            createKeyEvent(ACTION_DOWN, KEYCODE_STYLUS_BUTTON_TAIL, downTime = 0, eventTime = 0)
        )
        callback.handleSystemKey(
            createKeyEvent(ACTION_UP, KEYCODE_STYLUS_BUTTON_TAIL, downTime = 0, eventTime = 50)
        )
        callback.handleSystemKey(
            createKeyEvent(ACTION_DOWN, KEYCODE_STYLUS_BUTTON_TAIL, downTime = 99, eventTime = 99)
        )
        callback.handleSystemKey(
            createKeyEvent(ACTION_UP, KEYCODE_STYLUS_BUTTON_TAIL, downTime = 99, eventTime = 150)
        )

        verify(controller, times(1)).showNoteTask(any())
    }

    @Test
    fun tailButtonGestureDetection_longPress_shouldNotShowNoteTask() {
        val underTest = createUnderTest(isEnabled = true, bubbles = bubbles)
        underTest.initialize()
        val callback = withArgCaptor { verify(commandQueue).addCallback(capture()) }

        callback.handleSystemKey(
            createKeyEvent(ACTION_DOWN, KEYCODE_STYLUS_BUTTON_TAIL, downTime = 0, eventTime = 0)
        )
        callback.handleSystemKey(
            createKeyEvent(ACTION_UP, KEYCODE_STYLUS_BUTTON_TAIL, downTime = 0, eventTime = 1000)
        )

        verify(controller, never()).showNoteTask(any())
    }
}
