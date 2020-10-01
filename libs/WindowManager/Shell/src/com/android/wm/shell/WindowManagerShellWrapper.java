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

package com.android.wm.shell;

import static android.view.Display.DEFAULT_DISPLAY;

import android.app.WindowConfiguration;
import android.os.RemoteException;
import android.view.WindowManagerGlobal;

import com.android.wm.shell.pip.PinnedStackListenerForwarder;

/**
 * The singleton wrapper to communicate between WindowManagerService and WMShell features
 * (e.g: PIP, SplitScreen, Bubble, OneHandedMode...etc)
 */
public class WindowManagerShellWrapper {
    private static final String TAG = WindowManagerShellWrapper.class.getSimpleName();

    public static final int WINDOWING_MODE_PINNED = WindowConfiguration.WINDOWING_MODE_PINNED;

    /**
     * Forwarder to which we can add multiple pinned stack listeners. Each listener will receive
     * updates from the window manager service.
     */
    private PinnedStackListenerForwarder mPinnedStackListenerForwarder =
            new PinnedStackListenerForwarder();

    /**
     * Adds a pinned stack listener, which will receive updates from the window manager service
     * along with any other pinned stack listeners that were added via this method.
     */
    public void addPinnedStackListener(PinnedStackListenerForwarder.PinnedStackListener listener)
            throws
            RemoteException {
        mPinnedStackListenerForwarder.addListener(listener);
        WindowManagerGlobal.getWindowManagerService().registerPinnedStackListener(
                DEFAULT_DISPLAY, mPinnedStackListenerForwarder);
    }

    /**
     * Removes a pinned stack listener.
     */
    public void removePinnedStackListener(
            PinnedStackListenerForwarder.PinnedStackListener listener) {
        mPinnedStackListenerForwarder.removeListener(listener);
    }

}
