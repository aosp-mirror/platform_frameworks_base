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

package com.android.wm.shell.desktopmode;

/**
 * Allows external processes to register a listener in WMShell to get updates about desktop task
 * state.
 */
interface IDesktopTaskListener {

    /** Desktop tasks visibility has changed. Visible if at least 1 task is visible. */
    oneway void onTasksVisibilityChanged(int displayId, int visibleTasksCount);

    /** @deprecated this is no longer supported. */
    oneway void onStashedChanged(int displayId, boolean stashed);

    /**
     * Shows taskbar corner radius when running desktop tasks are updated if
     * [hasTasksRequiringTaskbarRounding] is true.
     */
    oneway void onTaskbarCornerRoundingUpdate(boolean hasTasksRequiringTaskbarRounding);
}