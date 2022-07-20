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
import android.annotation.NonNull;
import android.graphics.Rect;

import com.android.wm.shell.common.annotations.ExternalThread;
import com.android.wm.shell.common.split.SplitScreenConstants.SplitPosition;

import java.util.concurrent.Executor;

/**
 * Interface to engage split-screen feature.
 * TODO: Figure out which of these are actually needed outside of the Shell
 */
@ExternalThread
public interface SplitScreen {
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
        default void onStagePositionChanged(@StageType int stage, @SplitPosition int position) {}
        default void onTaskStageChanged(int taskId, @StageType int stage, boolean visible) {}
        default void onSplitBoundsChanged(Rect rootBounds, Rect mainBounds, Rect sideBounds) {}
        default void onSplitVisibilityChanged(boolean visible) {}
    }

    /** Registers listener that gets split screen callback. */
    void registerSplitScreenListener(@NonNull SplitScreenListener listener,
            @NonNull Executor executor);

    /** Unregisters listener that gets split screen callback. */
    void unregisterSplitScreenListener(@NonNull SplitScreenListener listener);

    /**
     * Returns a binder that can be passed to an external process to manipulate SplitScreen.
     */
    default ISplitScreen createExternalInterface() {
        return null;
    }

    /** Called when device waking up finished. */
    void onFinishedWakingUp();

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
