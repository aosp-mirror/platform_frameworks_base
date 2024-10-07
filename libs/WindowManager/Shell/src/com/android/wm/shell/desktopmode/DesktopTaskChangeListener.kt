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
import com.android.wm.shell.freeform.TaskChangeListener

/** Manages tasks handling specific to Android Desktop Mode. */
class DesktopTaskChangeListener: TaskChangeListener {

  override fun onTaskOpening(taskInfo: RunningTaskInfo) {
    // TODO: b/367268953 - Connect this with DesktopRepository.
  }

  override fun onTaskChanging(taskInfo: RunningTaskInfo) {
    // TODO: b/367268953 - Connect this with DesktopRepository.
  }

  override fun onTaskMovingToFront(taskInfo: RunningTaskInfo) {
    // TODO: b/367268953 - Connect this with DesktopRepository.
  }

  override fun onTaskMovingToBack(taskInfo: RunningTaskInfo) {
    // TODO: b/367268953 - Connect this with DesktopRepository.
  }

  override fun onTaskClosing(taskInfo: RunningTaskInfo) {
    // TODO: b/367268953 - Connect this with DesktopRepository.
  }
}
