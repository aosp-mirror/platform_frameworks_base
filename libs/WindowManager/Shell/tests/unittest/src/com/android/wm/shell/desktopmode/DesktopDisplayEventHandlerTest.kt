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

import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.content.ContentResolver
import android.os.Binder
import android.provider.Settings
import android.provider.Settings.Global.DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS
import android.testing.AndroidTestingRunner
import android.view.Display.DEFAULT_DISPLAY
import android.view.IWindowManager
import android.view.WindowManager.TRANSIT_CHANGE
import android.window.DisplayAreaInfo
import android.window.WindowContainerTransaction
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.never
import com.android.wm.shell.MockToken
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayController.OnDisplaysChangedListener
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.Transitions
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
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

  private lateinit var shellInit: ShellInit
  private lateinit var handler: DesktopDisplayEventHandler

  @Before
  fun setUp() {
    shellInit = spy(ShellInit(testExecutor))
    whenever(transitions.startTransition(anyInt(), any(), isNull())).thenAnswer { Binder() }
    val tda = DisplayAreaInfo(MockToken().token(), DEFAULT_DISPLAY, 0)
    whenever(rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(DEFAULT_DISPLAY)).thenReturn(tda)
    handler =
        DesktopDisplayEventHandler(
            context,
            shellInit,
            transitions,
            displayController,
            rootTaskDisplayAreaOrganizer,
            mockWindowManager,
        )
    shellInit.init()
  }

  private fun testDisplayWindowingModeSwitch(
    defaultWindowingMode: Int,
    extendedDisplayEnabled: Boolean,
    expectTransition: Boolean
  ) {
    val externalDisplayId = 100
    val captor = ArgumentCaptor.forClass(OnDisplaysChangedListener::class.java)
    verify(displayController).addDisplayWindowListener(captor.capture())
    val tda = rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(DEFAULT_DISPLAY)!!
    tda.configuration.windowConfiguration.windowingMode = defaultWindowingMode
    whenever(mockWindowManager.getWindowingMode(anyInt())).thenAnswer { defaultWindowingMode }
    val settingsSession = ExtendedDisplaySettingsSession(
      context.contentResolver, if (extendedDisplayEnabled) 1 else 0)

    settingsSession.use {
      // The external display connected.
      whenever(rootTaskDisplayAreaOrganizer.getDisplayIds())
        .thenReturn(intArrayOf(DEFAULT_DISPLAY, externalDisplayId))
      captor.value.onDisplayAdded(externalDisplayId)
      tda.configuration.windowConfiguration.windowingMode = WINDOWING_MODE_FREEFORM
      // The external display disconnected.
      whenever(rootTaskDisplayAreaOrganizer.getDisplayIds())
        .thenReturn(intArrayOf(DEFAULT_DISPLAY))
      captor.value.onDisplayRemoved(externalDisplayId)

      if (expectTransition) {
        val arg = argumentCaptor<WindowContainerTransaction>()
        verify(transitions, times(2)).startTransition(eq(TRANSIT_CHANGE), arg.capture(), isNull())
        assertThat(arg.firstValue.changes[tda.token.asBinder()]?.windowingMode)
          .isEqualTo(WINDOWING_MODE_FREEFORM)
        assertThat(arg.secondValue.changes[tda.token.asBinder()]?.windowingMode)
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
      expectTransition = false
    )
  }

  @Test
  fun displayWindowingModeSwitchOnDisplayConnected_fullscreenDisplay() {
    testDisplayWindowingModeSwitch(
      defaultWindowingMode = WINDOWING_MODE_FULLSCREEN,
      extendedDisplayEnabled = true,
      expectTransition = true
    )
  }

  @Test
  fun displayWindowingModeSwitchOnDisplayConnected_freeformDisplay() {
    testDisplayWindowingModeSwitch(
      defaultWindowingMode = WINDOWING_MODE_FREEFORM,
      extendedDisplayEnabled = true,
      expectTransition = false
    )
  }

  private class ExtendedDisplaySettingsSession(
    private val contentResolver: ContentResolver,
      private val overrideValue: Int
  ) : AutoCloseable {
    private val settingName = DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS
    private val initialValue = Settings.Global.getInt(contentResolver, settingName, 0)

    init { Settings.Global.putInt(contentResolver, settingName, overrideValue) }

    override fun close() {
      Settings.Global.putInt(contentResolver, settingName, initialValue)
    }
  }
}