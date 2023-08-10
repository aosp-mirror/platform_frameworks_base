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

import static com.android.window.flags.Flags.surfaceTrustedOverlay;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Region;
import android.gui.TouchOcclusionMode;
import android.os.IBinder;
import android.os.InputConfig;
import android.util.Size;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;

/**
 * Functions as a handle for a window that can receive input, and allows for the behavior of the
 * input window to be configured.
 * @hide
 */
public final class InputWindowHandle {
    /**
     * An internal annotation for all the {@link android.os.InputConfig} flags that can be
     * specified to {@link #inputConfig} to control the behavior of an input window. Only the
     * flags listed here are valid for use in Java.
     *
     * The default flag value is 0, which is what we expect for a normal application window. Adding
     * a flag indicates that the window's behavior deviates from that of a normal application
     * window.
     *
     * The flags are defined as an AIDL enum to keep it in sync with native code.
     * {@link android.os.InputConfig} flags that are not listed here should not be used in Java, and
     * are only meant to be used in native code.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {
            InputConfig.DEFAULT,
            InputConfig.NO_INPUT_CHANNEL,
            InputConfig.NOT_FOCUSABLE,
            InputConfig.NOT_TOUCHABLE,
            InputConfig.PREVENT_SPLITTING,
            InputConfig.DUPLICATE_TOUCH_TO_WALLPAPER,
            InputConfig.IS_WALLPAPER,
            InputConfig.PAUSE_DISPATCHING,
            InputConfig.WATCH_OUTSIDE_TOUCH,
            InputConfig.SLIPPERY,
            InputConfig.DISABLE_USER_ACTIVITY,
            InputConfig.SPY,
            InputConfig.INTERCEPTS_STYLUS,
            InputConfig.CLONE,
    })
    public @interface InputConfigFlags {}

    // Pointer to the native input window handle.
    // This field is lazily initialized via JNI.
    @SuppressWarnings("unused")
    private long ptr;

    // The input application handle.
    public InputApplicationHandle inputApplicationHandle;

    // The token associates input data with a window and its input channel. The client input
    // channel and the server input channel will both contain this token.
    public IBinder token;

    /**
     * The {@link IWindow} handle if InputWindowHandle is associated with a window, null otherwise.
     */
    @Nullable
    private IBinder windowToken;
    /**
     * Used to cache IWindow from the windowToken so we don't need to convert every time getWindow
     * is called.
     */
    private IWindow window;

    // The window name.
    public String name;

    // Window layout params attributes. (WindowManager.LayoutParams)
    // These values do not affect any input configurations. Use {@link #inputConfig} instead.
    public int layoutParamsFlags;
    public int layoutParamsType;

    // Dispatching timeout.
    public long dispatchingTimeoutMillis;

    // Window frame.
    public final Rect frame = new Rect();

    // The real size of the content, excluding any crop. If no buffer is rendered, this is 0,0
    public Size contentSize = new Size(0, 0);

    public int surfaceInset;

    // Global scaling factor applied to touch events when they are dispatched
    // to the window
    public float scaleFactor;

    // Window touchable region.
    public final Region touchableRegion = new Region();

    // Flags that specify the behavior of this input window. See {@link #InputConfigFlags}.
    @InputConfigFlags
    public int inputConfig;

    // What effect this window has on touch occlusion if it lets touches pass through
    // By default windows will block touches if they are untrusted and from a different UID due to
    // security concerns
    public int touchOcclusionMode = TouchOcclusionMode.BLOCK_UNTRUSTED;

    // Id of process and user that owns the window.
    public int ownerPid;
    public int ownerUid;

    // Owner package of the window
    public String packageName;

    // Display this input window is on.
    public int displayId;

    /**
     * Crops the {@link #touchableRegion} to the bounds of the surface provided.
     *
     * This can be used in cases where the window should be constrained to the bounds of a parent
     * window. That is, the window should receive touch events outside its window frame, but be
     * limited to its stack bounds, such as in the case of split screen.
     */
    public WeakReference<SurfaceControl> touchableRegionSurfaceControl = new WeakReference<>(null);

    /**
     * Replace {@link #touchableRegion} with the bounds of {@link #touchableRegionSurfaceControl}.
     * If the handle is {@code null}, the bounds of the surface associated with this window is used
     * as the touchable region.
     */
    public boolean replaceTouchableRegionWithCrop;

    /**
     * The transform that should be applied to the Window to get it from screen coordinates to
     * window coordinates
     */
    public Matrix transform;

    /**
     * The alpha value returned from SurfaceFlinger. This will be ignored if passed as input data.
     */
    public float alpha;

    /**
     * The input token for the window to which focus should be transferred when this input window
     * can be successfully focused. If null, this input window will not transfer its focus to
     * any other window.
     */
    @Nullable
    public IBinder focusTransferTarget;

    private native void nativeDispose();

    public InputWindowHandle(InputApplicationHandle inputApplicationHandle, int displayId) {
        this.inputApplicationHandle = inputApplicationHandle;
        this.displayId = displayId;
    }

    public InputWindowHandle(InputWindowHandle other) {
        // Do not copy ptr to prevent this copy from sharing the same native object.
        ptr = 0;
        inputApplicationHandle = new InputApplicationHandle(other.inputApplicationHandle);
        token = other.token;
        windowToken = other.windowToken;
        window = other.window;
        name = other.name;
        layoutParamsFlags = other.layoutParamsFlags;
        layoutParamsType = other.layoutParamsType;
        dispatchingTimeoutMillis = other.dispatchingTimeoutMillis;
        frame.set(other.frame);
        surfaceInset = other.surfaceInset;
        scaleFactor = other.scaleFactor;
        touchableRegion.set(other.touchableRegion);
        inputConfig = other.inputConfig;
        touchOcclusionMode = other.touchOcclusionMode;
        ownerPid = other.ownerPid;
        ownerUid = other.ownerUid;
        packageName = other.packageName;
        displayId = other.displayId;
        touchableRegionSurfaceControl = other.touchableRegionSurfaceControl;
        replaceTouchableRegionWithCrop = other.replaceTouchableRegionWithCrop;
        if (other.transform != null) {
            transform = new Matrix();
            transform.set(other.transform);
        }
        focusTransferTarget = other.focusTransferTarget;
        contentSize = new Size(other.contentSize.getWidth(), other.contentSize.getHeight());
        alpha = other.alpha;
    }

    @Override
    public String toString() {
        return new StringBuilder(name != null ? name : "")
                .append(", frame=[").append(frame).append("]")
                .append(", touchableRegion=").append(touchableRegion)
                .append(", scaleFactor=").append(scaleFactor)
                .append(", transform=").append(transform)
                .append(", windowToken=").append(windowToken)
                .append(", displayId=").append(displayId)
                .append(", isClone=").append((inputConfig & InputConfig.CLONE) != 0)
                .append(", contentSize=").append(contentSize)
                .append(", alpha=").append(alpha)
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
     * Set the window's touchable region to the bounds of {@link #touchableRegionSurfaceControl}
     * and ignore the value of {@link #touchableRegion}.
     *
     * @param bounds surface to set the touchable region to. Set to {@code null} to set the
     *               touchable region as the current surface bounds.
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

    public void setWindowToken(IWindow iwindow) {
        windowToken = iwindow.asBinder();
        window = iwindow;
    }

    public @Nullable IBinder getWindowToken() {
        return windowToken;
    }

    public IWindow getWindow() {
        if (window != null) {
            return window;
        }
        window = IWindow.Stub.asInterface(windowToken);
        return window;
    }

    /**
     * Set the provided inputConfig flag values.
     * @param inputConfig the flag values to change
     * @param value the provided flag values are set when true, and cleared when false
     */
    public void setInputConfig(@InputConfigFlags int inputConfig, boolean value) {
        if (value) {
            this.inputConfig |= inputConfig;
            return;
        }
        this.inputConfig &= ~inputConfig;
    }

    public void setTrustedOverlay(SurfaceControl.Transaction t, SurfaceControl sc,
            boolean isTrusted) {
        if (surfaceTrustedOverlay()) {
            t.setTrustedOverlay(sc, isTrusted);
        } else if (isTrusted) {
            inputConfig |= InputConfig.TRUSTED_OVERLAY;
        }
    }
}
