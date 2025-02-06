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

package com.android.wm.shell.desktopmode

import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.content.ContentResolver
import android.os.Binder
import android.platform.test.annotations.EnableFlags
import android.provider.Settings
import android.provider.Settings.Global.DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS
import android.testing.AndroidTestingRunner
import android.view.Display.DEFAULT_DISPLAY
import android.view.IWindowManager
import android.view.WindowManager.TRANSIT_CHANGE
import android.window.DisplayAreaInfo
import android.window.WindowContainerTransaction
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession
import com.android.dx.mockito.inline.extended.ExtendedMockito.never
import com.android.dx.mockito.inline.extended.StaticMockitoSession
import com.android.window.flags.Flags
import com.android.wm.shell.MockToken
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayController.OnDisplaysChangedListener
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.Transitions
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

/**
 * Test class for [DesktopDisplayEventHandler]
 *
 * Usage: atest WMShellUnitTests:DesktopDisplayEventHandlerTest
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class DesktopDisplayEventHandlerTest : ShellTestCase() {
    @Mock lateinit var testExecutor: ShellExecutor
    @Mock lateinit var transitions: Transitions
    @Mock lateinit var displayController: DisplayController
    @Mock lateinit var rootTaskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer
    @Mock private lateinit var mockWindowManager: IWindowManager
    @Mock private lateinit var mockDesktopUserRepositories: DesktopUserRepositories
    @Mock private lateinit var mockDesktopRepository: DesktopRepository
    @Mock private lateinit var mockDesktopTasksController: DesktopTasksController
    @Mock private lateinit var shellTaskOrganizer: ShellTaskOrganizer

    private lateinit var mockitoSession: StaticMockitoSession
    private lateinit var shellInit: ShellInit
    private lateinit var handler: DesktopDisplayEventHandler

    private val onDisplaysChangedListenerCaptor = argumentCaptor<OnDisplaysChangedListener>()
    private val runningTasks = mutableListOf<RunningTaskInfo>()
    private val externalDisplayId = 100
    private val freeformTask =
        TestRunningTaskInfoBuilder().setWindowingMode(WINDOWING_MODE_FREEFORM).build()
    private val fullscreenTask =
        TestRunningTaskInfoBuilder().setWindowingMode(WINDOWING_MODE_FULLSCREEN).build()
    private val defaultTDA = DisplayAreaInfo(MockToken().token(), DEFAULT_DISPLAY, 0)

    @Before
    fun setUp() {
        mockitoSession =
            mockitoSession()
                .strictness(Strictness.LENIENT)
                .spyStatic(DesktopModeStatus::class.java)
                .startMocking()

        shellInit = spy(ShellInit(testExecutor))
        whenever(mockDesktopUserRepositories.current).thenReturn(mockDesktopRepository)
        whenever(transitions.startTransition(anyInt(), any(), isNull())).thenAnswer { Binder() }
        whenever(rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(DEFAULT_DISPLAY))
            .thenReturn(defaultTDA)
        handler =
            DesktopDisplayEventHandler(
                context,
                shellInit,
                transitions,
                displayController,
                rootTaskDisplayAreaOrganizer,
                mockWindowManager,
                mockDesktopUserRepositories,
                mockDesktopTasksController,
                shellTaskOrganizer,
            )
        whenever(shellTaskOrganizer.getRunningTasks(anyInt())).thenAnswer { runningTasks }
        runningTasks.add(freeformTask)
        runningTasks.add(fullscreenTask)
        shellInit.init()
        verify(displayController)
            .addDisplayWindowListener(onDisplaysChangedListenerCaptor.capture())
    }

    @After
    fun tearDown() {
        mockitoSession.finishMocking()
    }

    private fun testDisplayWindowingModeSwitch(
        defaultWindowingMode: Int,
        extendedDisplayEnabled: Boolean,
        expectTransition: Boolean,
    ) {
        defaultTDA.configuration.windowConfiguration.windowingMode = defaultWindowingMode
        whenever(mockWindowManager.getWindowingMode(anyInt())).thenAnswer { defaultWindowingMode }
        val settingsSession =
            ExtendedDisplaySettingsSession(
                context.contentResolver,
                if (extendedDisplayEnabled) 1 else 0,
            )

        settingsSession.use {
            connectExternalDisplay()
            defaultTDA.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FREEFORM
            disconnectExternalDisplay()

            if (expectTransition) {
                val arg = argumentCaptor<WindowContainerTransaction>()
                verify(transitions, times(2))
                    .startTransition(eq(TRANSIT_CHANGE), arg.capture(), isNull())
                assertThat(arg.firstValue.changes[defaultTDA.token.asBinder()]?.windowingMode)
                    .isEqualTo(WINDOWING_MODE_FREEFORM)
                assertThat(arg.secondValue.changes[defaultTDA.token.asBinder()]?.windowingMode)
                    .isEqualTo(defaultWindowingMode)
            } else {
                verify(transitions, never()).startTransition(eq(TRANSIT_CHANGE), any(), isNull())
            }
        }
    }

    @Test
    fun displayWindowingModeSwitchOnDisplayConnected_extendedDisplayDisabled() {
        testDisplayWindowingModeSwitch(
            defaultWindowingMode = WINDOWING_MODE_FULLSCREEN,
            extendedDisplayEnabled = false,
            expectTransition = false,
        )
    }

    @Test
    fun displayWindowingModeSwitchOnDisplayConnected_fullscreenDisplay() {
        testDisplayWindowingModeSwitch(
            defaultWindowingMode = WINDOWING_MODE_FULLSCREEN,
            extendedDisplayEnabled = true,
            expectTransition = true,
        )
    }

    @Test
    fun displayWindowingModeSwitchOnDisplayConnected_freeformDisplay() {
        testDisplayWindowingModeSwitch(
            defaultWindowingMode = WINDOWING_MODE_FREEFORM,
            extendedDisplayEnabled = true,
            expectTransition = false,
        )
    }

    @Test
    fun testDisplayAdded_supportsDesks_createsDesk() {
        whenever(DesktopModeStatus.canEnterDesktopMode(context)).thenReturn(true)

        onDisplaysChangedListenerCaptor.lastValue.onDisplayAdded(DEFAULT_DISPLAY)

        verify(mockDesktopTasksController).createDesk(DEFAULT_DISPLAY)
    }

    @Test
    fun testDisplayAdded_cannotEnterDesktopMode_doesNotCreateDesk() {
        whenever(DesktopModeStatus.canEnterDesktopMode(context)).thenReturn(false)

        onDisplaysChangedListenerCaptor.lastValue.onDisplayAdded(DEFAULT_DISPLAY)

        verify(mockDesktopTasksController, never()).createDesk(DEFAULT_DISPLAY)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_MULTIPLE_DESKTOPS_BACKEND)
    fun testDeskRemoved_noDesksRemain_createsDesk() {
        whenever(mockDesktopRepository.getNumberOfDesks(DEFAULT_DISPLAY)).thenReturn(0)

        handler.onDeskRemoved(DEFAULT_DISPLAY, deskId = 1)

        verify(mockDesktopTasksController).createDesk(DEFAULT_DISPLAY)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_MULTIPLE_DESKTOPS_BACKEND)
    fun testDeskRemoved_desksRemain_doesNotCreateDesk() {
        whenever(mockDesktopRepository.getNumberOfDesks(DEFAULT_DISPLAY)).thenReturn(1)

        handler.onDeskRemoved(DEFAULT_DISPLAY, deskId = 1)

        verify(mockDesktopTasksController, never()).createDesk(DEFAULT_DISPLAY)
    }

    @Test
    fun displayWindowingModeSwitch_existingTasksOnConnected() {
        defaultTDA.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FULLSCREEN
        whenever(mockWindowManager.getWindowingMode(anyInt())).thenAnswer {
            WINDOWING_MODE_FULLSCREEN
        }

        ExtendedDisplaySettingsSession(context.contentResolver, 1).use {
            connectExternalDisplay()

            val arg = argumentCaptor<WindowContainerTransaction>()
            verify(transitions, times(1))
                .startTransition(eq(TRANSIT_CHANGE), arg.capture(), isNull())
            assertThat(arg.firstValue.changes[freeformTask.token.asBinder()]?.windowingMode)
                .isEqualTo(WINDOWING_MODE_UNDEFINED)
            assertThat(arg.firstValue.changes[fullscreenTask.token.asBinder()]?.windowingMode)
                .isEqualTo(WINDOWING_MODE_FULLSCREEN)
        }
    }

    @Test
    fun displayWindowingModeSwitch_existingTasksOnDisconnected() {
        defaultTDA.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FREEFORM
        whenever(mockWindowManager.getWindowingMode(anyInt())).thenAnswer {
            WINDOWING_MODE_FULLSCREEN
        }

        ExtendedDisplaySettingsSession(context.contentResolver, 1).use {
            disconnectExternalDisplay()

            val arg = argumentCaptor<WindowContainerTransaction>()
            verify(transitions, times(1))
                .startTransition(eq(TRANSIT_CHANGE), arg.capture(), isNull())
            assertThat(arg.firstValue.changes[freeformTask.token.asBinder()]?.windowingMode)
                .isEqualTo(WINDOWING_MODE_FREEFORM)
            assertThat(arg.firstValue.changes[fullscreenTask.token.asBinder()]?.windowingMode)
                .isEqualTo(WINDOWING_MODE_UNDEFINED)
        }
    }

    private fun connectExternalDisplay() {
        whenever(rootTaskDisplayAreaOrganizer.getDisplayIds())
            .thenReturn(intArrayOf(DEFAULT_DISPLAY, externalDisplayId))
        onDisplaysChangedListenerCaptor.lastValue.onDisplayAdded(externalDisplayId)
    }

    private fun disconnectExternalDisplay() {
        whenever(rootTaskDisplayAreaOrganizer.getDisplayIds())
            .thenReturn(intArrayOf(DEFAULT_DISPLAY))
        onDisplaysChangedListenerCaptor.lastValue.onDisplayRemoved(externalDisplayId)
    }

    private class ExtendedDisplaySettingsSession(
        private val contentResolver: ContentResolver,
        private val overrideValue: Int,
    ) : AutoCloseable {
        private val settingName = DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS
        private val initialValue = Settings.Global.getInt(contentResolver, settingName, 0)

        init {
            Settings.Global.putInt(contentResolver, settingName, overrideValue)
        }

        override fun close() {
            Settings.Global.putInt(contentResolver, settingName, initialValue)
        }
    }
}
