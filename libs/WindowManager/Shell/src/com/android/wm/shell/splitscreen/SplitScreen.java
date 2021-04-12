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

import com.android.wm.shell.common.annotations.ExternalThread;

/**
 * Interface to engage split-screen feature.
 * TODO: Figure out which of these are actually needed outside of the Shell
 */
@ExternalThread
public interface SplitScreen {
    /**
     * Stage position isn't specified normally meaning to use what ever it is currently set to.
     */
    int STAGE_POSITION_UNDEFINED = -1;
    /**
     * Specifies that a stage is positioned at the top half of the screen if
     * in portrait mode or at the left half of the screen if in landscape mode.
     */
    int STAGE_POSITION_TOP_OR_LEFT = 0;

    /**
     * Specifies that a stage is positioned at the bottom half of the screen if
     * in portrait mode or at the right half of the screen if in landscape mode.
     */
    int STAGE_POSITION_BOTTOM_OR_RIGHT = 1;

    @IntDef(prefix = { "STAGE_POSITION_" }, value = {
            STAGE_POSITION_UNDEFINED,
            STAGE_POSITION_TOP_OR_LEFT,
            STAGE_POSITION_BOTTOM_OR_RIGHT
    })
    @interface StagePosition {}

    /**
     * Stage type isn't specified normally meaning to use what ever the default is.
     * E.g. exit split-screen and launch the app in fullscreen.
     */
    int STAGE_TYPE_UNDEFINED = -1;
    /**
     * The main stage type.
     * @see MainStage
     */
    int STAGE_TYPE_MAIN = 0;

    /**
     * The side stage type.
     * @see SideStage
     */
    int STAGE_TYPE_SIDE = 1;

    @IntDef(prefix = { "STAGE_TYPE_" }, value = {
            STAGE_TYPE_UNDEFINED,
            STAGE_TYPE_MAIN,
            STAGE_TYPE_SIDE
    })
    @interface StageType {}

    /** Callback interface for listening to changes in a split-screen stage. */
    interface SplitScreenListener {
        void onStagePositionChanged(@StageType int stage, @StagePosition int position);
        void onTaskStageChanged(int taskId, @StageType int stage, boolean visible);
    }

    /**
     * Returns a binder that can be passed to an external process to manipulate SplitScreen.
     */
    default ISplitScreen createExternalInterface() {
        return null;
    }

    /** Get a string representation of a stage type */
    static String stageTypeToString(@StageType int stage) {
        switch (stage) {
            case STAGE_TYPE_UNDEFINED: return "UNDEFINED";
            case STAGE_TYPE_MAIN: return "MAIN";
            case STAGE_TYPE_SIDE: return "SIDE";
            default: return "UNKNOWN(" + stage + ")";
        }
    }
}
