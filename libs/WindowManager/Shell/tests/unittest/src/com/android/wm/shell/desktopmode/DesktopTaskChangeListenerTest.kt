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

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.window.flags.Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.desktopmode.DesktopTestHelpers.Companion.createFreeformTask
import com.android.wm.shell.desktopmode.DesktopTestHelpers.Companion.createFullscreenTask
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for {@link DesktopTaskChangeListener}
 *
 * Build/Install/Run: atest WMShellUnitTests:DesktopTaskChangeListenerTest
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class DesktopTaskChangeListenerTest : ShellTestCase() {

  @JvmField @Rule val setFlagsRule = SetFlagsRule()

  private lateinit var desktopTaskChangeListener: DesktopTaskChangeListener

  private val desktopRepository = mock<DesktopRepository>()

  @Before
  fun setUp() {
    desktopTaskChangeListener = DesktopTaskChangeListener(desktopRepository)
  }

  @Test
  fun onTaskOpening_fullscreenTask_notActiveDesktopTask_noop() {
    val task = createFullscreenTask().apply { isVisible = true }
    whenever(desktopRepository.isActiveTask(task.taskId)).thenReturn(false)

    desktopTaskChangeListener.onTaskOpening(task)

    verify(desktopRepository, never()).addOrMoveFreeformTaskToTop(task.displayId, task.taskId)
    verify(desktopRepository, never()).removeFreeformTask(task.displayId, task.taskId)
  }

  @Test
  fun onTaskOpening_freeformTask_activeDesktopTask_removesTaskFromRepo() {
    val task = createFullscreenTask().apply { isVisible = true }
    whenever(desktopRepository.isActiveTask(task.taskId)).thenReturn(true)

    desktopTaskChangeListener.onTaskOpening(task)

    verify(desktopRepository).removeFreeformTask(task.displayId, task.taskId)
  }

  @Test
  fun onTaskOpening_freeformTask_visibleDesktopTask_addsTaskToRepository() {
    val task = createFreeformTask().apply { isVisible = true }
    whenever(desktopRepository.isActiveTask(task.taskId)).thenReturn(false)

    desktopTaskChangeListener.onTaskOpening(task)

    verify(desktopRepository).addOrMoveFreeformTaskToTop(task.displayId, task.taskId)
    verify(desktopRepository).addActiveTask(task.displayId, task.taskId)
    verify(desktopRepository).updateTaskVisibility(task.displayId, task.taskId, visible = true)
  }

  @Test
  fun onTaskOpening_freeformTask_nonVisibleDesktopTask_addsTaskToRepository() {
    val task = createFreeformTask().apply { isVisible = false }
    whenever(desktopRepository.isActiveTask(task.taskId)).thenReturn(true)

    desktopTaskChangeListener.onTaskOpening(task)

    verify(desktopRepository).addOrMoveFreeformTaskToTop(task.displayId, task.taskId)
  }

  @Test
  fun onTaskChanging_freeformTaskOutsideDesktop_removesTaskFromRepo() {
    val task = createFullscreenTask().apply { isVisible = true }
    whenever(desktopRepository.isActiveTask(task.taskId)).thenReturn(true)

    desktopTaskChangeListener.onTaskChanging(task)

    verify(desktopRepository).removeFreeformTask(task.displayId, task.taskId)
  }

  @Test
  fun onTaskChanging_visibleTaskInDesktop_updatesTaskVisibility() {
    val task = createFreeformTask().apply { isVisible = true }
    whenever(desktopRepository.isActiveTask(task.taskId)).thenReturn(true)

    desktopTaskChangeListener.onTaskChanging(task)

    verify(desktopRepository).addActiveTask(task.displayId, task.taskId)
    verify(desktopRepository).updateTaskVisibility(task.displayId, task.taskId, task.isVisible)
  }

  @Test
  fun onTaskChanging_nonVisibleTask_updatesTaskVisibility() {
    val task = createFreeformTask().apply { isVisible = false }
    whenever(desktopRepository.isActiveTask(task.taskId)).thenReturn(true)

    desktopTaskChangeListener.onTaskChanging(task)

    verify(desktopRepository).updateTaskVisibility(task.displayId, task.taskId, task.isVisible)
  }

  @Test
  fun onTaskMovingToFront_freeformTaskOutsideDesktop_removesTaskFromRepo() {
    val task = createFullscreenTask().apply { isVisible = true }
    whenever(desktopRepository.isActiveTask(task.taskId)).thenReturn(true)

    desktopTaskChangeListener.onTaskMovingToFront(task)

    verify(desktopRepository).removeFreeformTask(task.displayId, task.taskId)
  }

  @Test
  @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
  fun onTaskClosing_backNavEnabled_nonClosingTask_minimizesTaskInRepo() {
    val task = createFreeformTask().apply { isVisible = true }
    whenever(desktopRepository.isActiveTask(task.taskId)).thenReturn(true)
    whenever(desktopRepository.isClosingTask(task.taskId)).thenReturn(false)

    desktopTaskChangeListener.onTaskClosing(task)

    verify(desktopRepository).updateTaskVisibility(task.displayId, task.taskId, visible = false)
    verify(desktopRepository).minimizeTask(task.displayId, task.taskId)
  }

  @Test
  @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
  fun onTaskClosing_backNavDisabled_closingTask_removesTaskInRepo() {
    val task = createFreeformTask().apply { isVisible = true }
    whenever(desktopRepository.isActiveTask(task.taskId)).thenReturn(true)
    whenever(desktopRepository.isClosingTask(task.taskId)).thenReturn(true)

    desktopTaskChangeListener.onTaskClosing(task)

    verify(desktopRepository, never()).minimizeTask(task.displayId, task.taskId)
    verify(desktopRepository).removeClosingTask(task.taskId)
    verify(desktopRepository).removeFreeformTask(task.displayId, task.taskId)
  }

  @Test
  @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
  fun onTaskClosing_backNavEnabled_closingTask_removesTaskFromRepo() {
    val task = createFreeformTask().apply { isVisible = true }
    whenever(desktopRepository.isActiveTask(task.taskId)).thenReturn(true)
    whenever(desktopRepository.isClosingTask(task.taskId)).thenReturn(true)

    desktopTaskChangeListener.onTaskClosing(task)

    verify(desktopRepository).removeClosingTask(task.taskId)
    verify(desktopRepository).removeFreeformTask(task.displayId, task.taskId)
  }
}
