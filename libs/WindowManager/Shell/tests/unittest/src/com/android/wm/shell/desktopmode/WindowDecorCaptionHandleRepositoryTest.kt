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
import android.graphics.Rect
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class WindowDecorCaptionHandleRepositoryTest {
  private lateinit var captionHandleRepository: WindowDecorCaptionHandleRepository

  @Before
  fun setUp() {
    captionHandleRepository = WindowDecorCaptionHandleRepository()
  }

  @Test
  fun initialState_noAction_returnsNoCaption() {
    // Check the initial value of `captionStateFlow`.
    assertThat(captionHandleRepository.captionStateFlow.value).isEqualTo(CaptionState.NoCaption)
  }

  @Test
  fun notifyCaptionChange_toAppHandleVisible_updatesStateWithCorrectData() {
    val taskInfo = createTaskInfo(WINDOWING_MODE_FULLSCREEN, GMAIL_PACKAGE_NAME)
    val appHandleCaptionState =
        CaptionState.AppHandle(
            taskInfo, false, Rect(/* left= */ 0, /* top= */ 1, /* right= */ 2, /* bottom= */ 3))

    captionHandleRepository.notifyCaptionChanged(appHandleCaptionState)

    assertThat(captionHandleRepository.captionStateFlow.value).isEqualTo(appHandleCaptionState)
  }

  @Test
  fun notifyCaptionChange_toAppChipVisible_updatesStateWithCorrectData() {
    val taskInfo = createTaskInfo(WINDOWING_MODE_FREEFORM, GMAIL_PACKAGE_NAME)
    val appHeaderCaptionState =
        CaptionState.AppHeader(
            taskInfo, true, Rect(/* left= */ 0, /* top= */ 1, /* right= */ 2, /* bottom= */ 3))

    captionHandleRepository.notifyCaptionChanged(appHeaderCaptionState)

    assertThat(captionHandleRepository.captionStateFlow.value).isEqualTo(appHeaderCaptionState)
  }

  @Test
  fun notifyCaptionChange_toNoCaption_updatesState() {
    captionHandleRepository.notifyCaptionChanged(CaptionState.NoCaption)

    assertThat(captionHandleRepository.captionStateFlow.value).isEqualTo(CaptionState.NoCaption)
  }

  private fun createTaskInfo(
      deviceWindowingMode: Int = WINDOWING_MODE_UNDEFINED,
      runningTaskPackageName: String = LAUNCHER_PACKAGE_NAME
  ): RunningTaskInfo =
      RunningTaskInfo().apply {
        configuration.windowConfiguration.apply { windowingMode = deviceWindowingMode }
        topActivityInfo?.apply { packageName = runningTaskPackageName }
      }

  private companion object {
    const val GMAIL_PACKAGE_NAME = "com.google.android.gm"
    const val LAUNCHER_PACKAGE_NAME = "com.google.android.apps.nexuslauncher"
  }
}
