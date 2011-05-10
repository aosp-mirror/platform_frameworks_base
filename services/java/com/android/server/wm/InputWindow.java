/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.graphics.Region;
import android.view.InputChannel;

/**
 * Describes input-related window properties for use by the input dispatcher.
 * @hide
 */
public final class InputWindow {
    // The window handle.
    public InputWindowHandle inputWindowHandle;

    // The input channel associated with the window.
    public InputChannel inputChannel;

    // The window name.
    public String name;

    // Window layout params attributes.  (WindowManager.LayoutParams)
    public int layoutParamsFlags;
    public int layoutParamsType;

    // Dispatching timeout.
    public long dispatchingTimeoutNanos;

    // Window frame.
    public int frameLeft;
    public int frameTop;
    public int frameRight;
    public int frameBottom;

    // Global scaling factor applied to touch events when they are dispatched
    // to the window
    public float scaleFactor;

    // Window touchable region.
    public final Region touchableRegion = new Region();

    // Window is visible.
    public boolean visible;

    // Window can receive keys.
    public boolean canReceiveKeys;

    // Window has focus.
    public boolean hasFocus;

    // Window has wallpaper.  (window is the current wallpaper target)
    public boolean hasWallpaper;

    // Input event dispatching is paused.
    public boolean paused;

    // Window layer.
    public int layer;

    // Id of process and user that owns the window.
    public int ownerPid;
    public int ownerUid;

    public void recycle() {
        inputWindowHandle = null;
        inputChannel = null;
    }
}
