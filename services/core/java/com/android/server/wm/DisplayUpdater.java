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

package com.android.server.wm;

import android.annotation.NonNull;
import android.os.Message;
import android.view.Surface;
import android.window.DisplayAreaInfo;

/**
 * Interface for a helper class that manages updates of DisplayInfo coming from DisplayManager
 */
interface DisplayUpdater {
    /**
     * Reads the latest display parameters from the display manager and returns them in a callback.
     * If there are pending display updates, it will wait for them to finish first and only then it
     * will call the callback with the latest display parameters.
     *
     * @param callback is called when all pending display updates are finished
     */
    void updateDisplayInfo(@NonNull Runnable callback);

    /**
     * Called when physical display has changed and before DisplayContent has applied new display
     * properties
     */
    default void onDisplayContentDisplayPropertiesPreChanged(int displayId, int initialDisplayWidth,
            int initialDisplayHeight, int newWidth, int newHeight) {
    }

    /**
     * Called after physical display has changed and after DisplayContent applied new display
     * properties
     */
    default void onDisplayContentDisplayPropertiesPostChanged(
            @Surface.Rotation int previousRotation, @Surface.Rotation int newRotation,
            @NonNull DisplayAreaInfo newDisplayAreaInfo) {
    }

    /**
     * Called with {@code true} when physical display is going to switch. And {@code false} when
     * the display is turned on or the device goes to sleep.
     */
    default void onDisplaySwitching(boolean switching) {
    }

    /** Returns {@code true} if the transition will control when to turn on the screen. */
    default boolean waitForTransition(@NonNull Message screenUnBlocker) {
        return false;
    }
}
