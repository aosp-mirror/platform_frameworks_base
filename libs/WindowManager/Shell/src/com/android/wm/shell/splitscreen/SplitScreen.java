/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.splitscreen;

import android.annotation.IntDef;
import android.app.ActivityManager;
import android.graphics.Rect;
import android.os.Bundle;

import androidx.annotation.NonNull;

import com.android.wm.shell.common.annotations.ExternalThread;

import java.io.PrintWriter;

/**
 * Interface to engage split-screen feature.
 */
@ExternalThread
public interface SplitScreen {
    /**
     * Specifies that the side-stage is positioned at the top half of the screen if
     * in portrait mode or at the left half of the screen if in landscape mode.
     */
    int SIDE_STAGE_POSITION_TOP_OR_LEFT = 0;

    /**
     * Specifies that the side-stage is positioned at the bottom half of the screen if
     * in portrait mode or at the right half of the screen if in landscape mode.
     */
    int SIDE_STAGE_POSITION_BOTTOM_OR_RIGHT = 1;

    @IntDef(prefix = { "SIDE_STAGE_POSITION_" }, value = {
            SIDE_STAGE_POSITION_TOP_OR_LEFT,
            SIDE_STAGE_POSITION_BOTTOM_OR_RIGHT
    })
    @interface SideStagePosition {}

    /** @return {@code true} if split-screen is currently visible. */
    boolean isSplitScreenVisible();
    /** Moves a task in the side-stage of split-screen. */
    boolean moveToSideStage(int taskId, @SideStagePosition int sideStagePosition);
    /** Moves a task in the side-stage of split-screen. */
    boolean moveToSideStage(ActivityManager.RunningTaskInfo task,
            @SideStagePosition int sideStagePosition);
    /** Removes a task from the side-stage of split-screen. */
    boolean removeFromSideStage(int taskId);
    /** Sets the position of the side-stage. */
    void setSideStagePosition(@SideStagePosition int sideStagePosition);
    /** Hides the side-stage if it is currently visible. */
    void setSideStageVisibility(boolean visible);
    /** Removes the split-screen stages. */
    void exitSplitScreen();
    /** Gets the stage bounds. */
    void getStageBounds(Rect outTopOrLeftBounds, Rect outBottomOrRightBounds);
    /** Updates the launch activity options for the split position we want to launch it in. */
    void updateActivityOptions(Bundle opts, @SideStagePosition int position);
    /** Dumps current status of split-screen. */
    void dump(@NonNull PrintWriter pw, String prefix);
    /** Called when the shell organizer has been registered. */
    void onOrganizerRegistered();
}
