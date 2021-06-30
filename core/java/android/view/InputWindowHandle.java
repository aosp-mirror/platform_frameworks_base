/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.view;

import android.annotation.Nullable;
import android.graphics.Region;
import android.gui.TouchOcclusionMode;
import android.os.IBinder;

import java.lang.ref.WeakReference;

/**
 * Functions as a handle for a window that can receive input.
 * Enables the native input dispatcher to refer indirectly to the window manager's window state.
 * @hide
 */
public final class InputWindowHandle {
    // Pointer to the native input window handle.
    // This field is lazily initialized via JNI.
    @SuppressWarnings("unused")
    private long ptr;

    // The input application handle.
    public InputApplicationHandle inputApplicationHandle;

    // The token associates input data with a window and its input channel. The client input
    // channel and the server input channel will both contain this token.
    public IBinder token;

    // The window name.
    public String name;

    // Window layout params attributes.  (WindowManager.LayoutParams)
    public int layoutParamsFlags;
    public int layoutParamsType;

    // Dispatching timeout.
    public long dispatchingTimeoutMillis;

    // Window frame.
    public int frameLeft;
    public int frameTop;
    public int frameRight;
    public int frameBottom;

    public int surfaceInset;

    // Global scaling factor applied to touch events when they are dispatched
    // to the window
    public float scaleFactor;

    // Window touchable region.
    public final Region touchableRegion = new Region();

    // Window is visible.
    public boolean visible;

    // Window can be focused.
    public boolean focusable;

    // Window has wallpaper.  (window is the current wallpaper target)
    public boolean hasWallpaper;

    // Input event dispatching is paused.
    public boolean paused;

    // Window is trusted overlay.
    public boolean trustedOverlay;

    // What effect this window has on touch occlusion if it lets touches pass through
    // By default windows will block touches if they are untrusted and from a different UID due to
    // security concerns
    public int touchOcclusionMode = TouchOcclusionMode.BLOCK_UNTRUSTED;

    // Id of process and user that owns the window.
    public int ownerPid;
    public int ownerUid;

    // Owner package of the window
    public String packageName;

    // Window input features.
    public int inputFeatures;

    // Display this input is on.
    public int displayId;

    /**
     * Crops the touchable region to the bounds of the surface provided.
     *
     * This can be used in cases where the window is not
     * {@link android.view.WindowManager#FLAG_NOT_TOUCH_MODAL} but should be constrained to the
     * bounds of a parent window. That is the window should receive touch events outside its
     * window but be limited to its stack bounds, such as in the case of split screen.
     */
    public WeakReference<SurfaceControl> touchableRegionSurfaceControl = new WeakReference<>(null);

    /**
     * Replace {@link touchableRegion} with the bounds of {@link touchableRegionSurfaceControl}. If
     * the handle is {@code null}, the bounds of the surface associated with this window is used
     * as the touchable region.
     */
    public boolean replaceTouchableRegionWithCrop;

    private native void nativeDispose();

    public InputWindowHandle(InputApplicationHandle inputApplicationHandle, int displayId) {
        this.inputApplicationHandle = inputApplicationHandle;
        this.displayId = displayId;
    }

    @Override
    public String toString() {
        return new StringBuilder(name != null ? name : "")
                .append(", frame=[").append(frameLeft).append(",").append(frameTop).append(",")
                        .append(frameRight).append(",").append(frameBottom).append("]")
                .append(", touchableRegion=").append(touchableRegion)
                .append(", visible=").append(visible)
                .toString();

    }

    @Override
    protected void finalize() throws Throwable {
        try {
            nativeDispose();
        } finally {
            super.finalize();
        }
    }

    /**
     * Set the window touchable region to the bounds of {@link touchableRegionBounds} ignoring any
     * touchable region provided.
     *
     * @param bounds surface to set the touchable region to. Set to {@code null} to set the bounds
     * to the current surface.
     */
    public void replaceTouchableRegionWithCrop(@Nullable SurfaceControl bounds) {
        setTouchableRegionCrop(bounds);
        replaceTouchableRegionWithCrop = true;
    }

    /**
     * Crop the window touchable region to the bounds of the surface provided.
     */
    public void setTouchableRegionCrop(@Nullable SurfaceControl bounds) {
        touchableRegionSurfaceControl = new WeakReference<>(bounds);
    }
}
