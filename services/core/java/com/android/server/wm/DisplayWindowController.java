/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.content.res.Configuration;
import android.util.Slog;

/**
 * Controller for the display container. This is created by activity manager to link activity
 * displays to the display content they use in window manager.
 */
public class DisplayWindowController
        extends WindowContainerController<DisplayContent, WindowContainerListener> {

    private final int mDisplayId;

    public DisplayWindowController(int displayId, WindowContainerListener listener) {
        super(listener, WindowManagerService.getInstance());
        mDisplayId = displayId;

        synchronized (mWindowMap) {
            // TODO: Convert to setContainer() from DisplayContent once everything is hooked up.
            // Currently we are not setup to register for config changes.
            mContainer = mRoot.getDisplayContentOrCreate(displayId);
            if (mContainer == null) {
                throw new IllegalArgumentException("Trying to add displayId=" + displayId);
            }
        }
    }

    @Override
    public void removeContainer() {
        // TODO: Pipe through from ActivityDisplay to remove the display
        throw new UnsupportedOperationException("To be implemented");
    }

    @Override
    public void onOverrideConfigurationChanged(Configuration overrideConfiguration) {
        // TODO: Pipe through from ActivityDisplay to update the configuration for the display
        throw new UnsupportedOperationException("To be implemented");
    }

    /**
     * Positions the task stack at the given position in the task stack container.
     */
    public void positionChildAt(StackWindowController child, int position) {
        synchronized (mWindowMap) {
            if (DEBUG_STACK) Slog.i(TAG_WM, "positionTaskStackAt: positioning stack=" + child
                    + " at " + position);
            if (mContainer == null) {
                if (DEBUG_STACK) Slog.i(TAG_WM,
                        "positionTaskStackAt: could not find display=" + mContainer);
                return;
            }
            if (child.mContainer == null) {
                if (DEBUG_STACK) Slog.i(TAG_WM,
                        "positionTaskStackAt: could not find stack=" + this);
                return;
            }
            mContainer.positionStackAt(position, child.mContainer);
        }
    }

    @Override
    public String toString() {
        return "{DisplayWindowController displayId=" + mDisplayId + "}";
    }
}
