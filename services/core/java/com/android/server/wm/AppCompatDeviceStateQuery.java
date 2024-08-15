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

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import android.annotation.NonNull;

/**
 * Provides information about the current state of the display in relation of
 * fold/unfold and other positions.
 */
class AppCompatDeviceStateQuery {

    @NonNull
    final ActivityRecord mActivityRecord;

    AppCompatDeviceStateQuery(@NonNull ActivityRecord activityRecord) {
        mActivityRecord = activityRecord;
    }

    /**
     * Check if we are in the given pose and in fullscreen mode.
     *
     * Note that we check the task rather than the parent as with ActivityEmbedding the parent
     * might be a TaskFragment, and its windowing mode is always MULTI_WINDOW, even if the task is
     * actually fullscreen. If display is still in transition e.g. unfolding, don't return true
     * for HALF_FOLDED state or app will flicker.
     */
    boolean isDisplayFullScreenAndInPosture(boolean isTabletop) {
        final Task task = mActivityRecord.getTask();
        final DisplayContent dc = mActivityRecord.mDisplayContent;
        return dc != null && task != null && !dc.inTransition()
                && dc.getDisplayRotation().isDeviceInPosture(
                    DeviceStateController.DeviceState.HALF_FOLDED, isTabletop)
                && task.getWindowingMode() == WINDOWING_MODE_FULLSCREEN;
    }

    /**
     * Note that we check the task rather than the parent as with ActivityEmbedding the parent might
     * be a TaskFragment, and its windowing mode is always MULTI_WINDOW, even if the task is
     * actually fullscreen.
     */
    boolean isDisplayFullScreenAndSeparatingHinge() {
        final Task task = mActivityRecord.getTask();
        return mActivityRecord.mDisplayContent != null && task != null
                && mActivityRecord.mDisplayContent.getDisplayRotation().isDisplaySeparatingHinge()
                && task.getWindowingMode() == WINDOWING_MODE_FULLSCREEN;
    }
}
