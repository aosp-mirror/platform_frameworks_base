/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.util.proto.ProtoOutputStream;
import android.view.IWindow;

/**
 * Common interface between focusable objects.
 *
 * Both WindowState and EmbeddedWindows can receive input. This consolidates some common properties
 * of both targets.
 */
interface InputTarget {
    /* Get the WindowState associated with the target. */
    WindowState getWindowState();

    /* Display id of the target. */
    int getDisplayId();

    /* Client IWindow for the target. */
    IWindow getIWindow();

    /* Owning pid of the target. */
    int getPid();
    int getUid();

    /**
     * Indicates whether a target should receive focus from server side
     * tap outside focus detection. For example, this is false in the case of
     * EmbeddedWindows in a client view hierarchy, where the client will do internal
     * tap detection and invoke grantEmbeddedWindowFocus itself
     */
    boolean receiveFocusFromTapOutside();

    // Gaining focus
    void handleTapOutsideFocusInsideSelf();
    // Losing focus
    void handleTapOutsideFocusOutsideSelf();

    // Whether this input target can control the IME itself
    boolean shouldControlIme();
    // Whether this input target can be screenshoted by the IME system
    boolean canScreenshotIme();

    ActivityRecord getActivityRecord();

    boolean isInputMethodClientFocus(int uid, int pid);

    DisplayContent getDisplayContent();
    InsetsControlTarget getImeControlTarget();

    void dumpProto(ProtoOutputStream proto, long fieldId,
                   @WindowTraceLogLevel int logLevel);
}

