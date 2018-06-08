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

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DISPLAY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.content.res.Configuration;
import android.os.Binder;
import android.util.Slog;
import android.view.Display;

/**
 * Controller for the display container. This is created by activity manager to link activity
 * displays to the display content they use in window manager.
 */
public class DisplayWindowController
        extends WindowContainerController<DisplayContent, WindowContainerListener> {

    private final int mDisplayId;

    public DisplayWindowController(Display display, WindowContainerListener listener) {
        super(listener, WindowManagerService.getInstance());
        mDisplayId = display.getDisplayId();

        synchronized (mWindowMap) {
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                mRoot.createDisplayContent(display, this /* controller */);
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }

            if (mContainer == null) {
                throw new IllegalArgumentException("Trying to add display=" + display
                        + " dc=" + mRoot.getDisplayContent(mDisplayId));
            }
        }
    }

    @Override
    public void removeContainer() {
        synchronized (mWindowMap) {
            if(mContainer == null) {
                if (DEBUG_DISPLAY) Slog.i(TAG_WM, "removeDisplay: could not find displayId="
                        + mDisplayId);
                return;
            }
            mContainer.removeIfPossible();
            super.removeContainer();
        }
    }

    @Override
    public void onOverrideConfigurationChanged(Configuration overrideConfiguration) {
        // TODO: The container receives override configuration changes through other means. enabling
        // callbacks through the controller causes layout issues. Investigate consolidating
        // override configuration propagation to just here.
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

    /**
     * Starts deferring the ability to update the IME target. This is needed when a call will
     * attempt to update the IME target before all information about the Windows have been updated.
     */
    public void deferUpdateImeTarget() {
        synchronized (mWindowMap) {
            final DisplayContent dc = mRoot.getDisplayContent(mDisplayId);
            if (dc != null) {
                dc.deferUpdateImeTarget();
            }
        }
    }

    /**
     * Resumes updating the IME target after deferring. See {@link #deferUpdateImeTarget()}
     */
    public void continueUpdateImeTarget() {
        synchronized (mWindowMap) {
            final DisplayContent dc = mRoot.getDisplayContent(mDisplayId);
            if (dc != null) {
                dc.continueUpdateImeTarget();
            }
        }
    }

    @Override
    public String toString() {
        return "{DisplayWindowController displayId=" + mDisplayId + "}";
    }
}
