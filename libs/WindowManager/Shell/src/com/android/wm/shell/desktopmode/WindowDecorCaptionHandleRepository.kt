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
import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Repository to observe caption state. */
class WindowDecorCaptionHandleRepository {
  private val _captionStateFlow = MutableStateFlow<CaptionState>(CaptionState.NoCaption)
  /** Observer for app handle state changes. */
  val captionStateFlow: StateFlow<CaptionState> = _captionStateFlow

  /** Notifies [captionStateFlow] if there is a change to caption state. */
  fun notifyCaptionChanged(captionState: CaptionState) {
    _captionStateFlow.value = captionState
  }
}

/**
 * Represents the current status of the caption.
 *
 * It can be one of three options:
 * * [AppHandle]: Indicating that there is at least one visible app handle on the screen.
 * * [AppHeader]: Indicating that there is at least one visible app chip on the screen.
 * * [NoCaption]: Signifying that no caption handle is currently visible on the device.
 */
sealed class CaptionState {
  data class AppHandle(
      val runningTaskInfo: RunningTaskInfo,
      val isHandleMenuExpanded: Boolean,
      val globalAppHandleBounds: Rect
  ) : CaptionState()

  data class AppHeader(
      val runningTaskInfo: RunningTaskInfo,
      val isHeaderMenuExpanded: Boolean,
      val globalAppChipBounds: Rect
  ) : CaptionState()

  data object NoCaption : CaptionState()
}
