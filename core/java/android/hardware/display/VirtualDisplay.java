/*
 * Copyright (C) 2013 The Android Open Source Project
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
package android.hardware.display;

import android.os.IBinder;
import android.view.Display;

/**
 * Represents a virtual display. The content of a virtual display is rendered to a
 * {@link android.view.Surface} that you must provide to {@link DisplayManager#createVirtualDisplay
 * createVirtualDisplay()}.
 * <p>Because a virtual display renders to a surface provided by the application, it will be
 * released automatically when the process terminates and all remaining windows on it will
 * be forcibly removed. However, you should also explicitly call {@link #release} when you're
 * done with it.
 *
 * @see DisplayManager#createVirtualDisplay
 */
public final class VirtualDisplay {
    private final DisplayManagerGlobal mGlobal;
    private final Display mDisplay;
    private IBinder mToken;

    VirtualDisplay(DisplayManagerGlobal global, Display display, IBinder token) {
        mGlobal = global;
        mDisplay = display;
        mToken = token;
    }

    /**
     * Gets the virtual display.
     */
    public Display getDisplay() {
        return mDisplay;
    }

    /**
     * Releases the virtual display and destroys its underlying surface.
     * <p>
     * All remaining windows on the virtual display will be forcibly removed
     * as part of releasing the virtual display.
     * </p>
     */
    public void release() {
        if (mToken != null) {
            mGlobal.releaseVirtualDisplay(mToken);
            mToken = null;
        }
    }

    @Override
    public String toString() {
        return "VirtualDisplay{display=" + mDisplay + ", token=" + mToken + "}";
    }
}
