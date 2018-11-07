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

import android.view.Display;
import android.view.Surface;

/**
 * Represents a virtual display. The content of a virtual display is rendered to a
 * {@link android.view.Surface} that you must provide to {@link DisplayManager#createVirtualDisplay
 * createVirtualDisplay()}.
 * <p>
 * Because a virtual display renders to a surface provided by the application, it will be
 * released automatically when the process terminates and all remaining windows on it will
 * be forcibly removed.  However, you should also explicitly call {@link #release} when
 * you're done with it.
 * </p>
 *
 * @see DisplayManager#createVirtualDisplay
 */
public final class VirtualDisplay {
    private final DisplayManagerGlobal mGlobal;
    private final Display mDisplay;
    private IVirtualDisplayCallback mToken;
    private Surface mSurface;

    VirtualDisplay(DisplayManagerGlobal global, Display display,
            IVirtualDisplayCallback token, Surface surface) {
        mGlobal = global;
        mDisplay = display;
        mToken = token;
        mSurface = surface;
    }

    /**
     * Gets the virtual display.
     */
    public Display getDisplay() {
        return mDisplay;
    }

    /**
     * Gets the surface that backs the virtual display.
     */
    public Surface getSurface() {
        return mSurface;
    }

    /**
     * Sets the surface that backs the virtual display.
     * <p>
     * Detaching the surface that backs a virtual display has a similar effect to
     * turning off the screen.
     * </p><p>
     * It is still the caller's responsibility to destroy the surface after it has
     * been detached.
     * </p>
     *
     * @param surface The surface to set, or null to detach the surface from the virtual display.
     */
    public void setSurface(Surface surface) {
        if (mSurface != surface) {
            mGlobal.setVirtualDisplaySurface(mToken, surface);
            mSurface = surface;
        }
    }

    /**
     * Asks the virtual display to resize.
     *<p>
     * This is really just a convenience to allow applications using
     * virtual displays to adapt to changing conditions without having
     * to tear down and recreate the display.
     * </p>
     */
    public void resize(int width, int height, int densityDpi) {
        mGlobal.resizeVirtualDisplay(mToken, width, height, densityDpi);
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

    /**
     * Sets the on/off state for a virtual display.
     *
     * @param isOn Whether the display should be on or off.
     * @hide
     */
    public void setDisplayState(boolean isOn) {
        if (mToken != null) {
            mGlobal.setVirtualDisplayState(mToken, isOn);
        }
    }

    @Override
    public String toString() {
        return "VirtualDisplay{display=" + mDisplay + ", token=" + mToken
                + ", surface=" + mSurface + "}";
    }

    /**
     * Interface for receiving information about a {@link VirtualDisplay}'s state changes.
     */
    public static abstract class Callback {
        /**
         * Called when the virtual display video projection has been
         * paused by the system or when the surface has been detached
         * by the application by calling setSurface(null).
         * The surface will not receive any more buffers while paused.
         */
         public void onPaused() { }

        /**
         * Called when the virtual display video projection has been
         * resumed after having been paused.
         */
         public void onResumed() { }

        /**
         * Called when the virtual display video projection has been
         * stopped by the system.  It will no longer receive frames
         * and it will never be resumed.  It is still the responsibility
         * of the application to release() the virtual display.
         */
        public void onStopped() { }
    }
}
