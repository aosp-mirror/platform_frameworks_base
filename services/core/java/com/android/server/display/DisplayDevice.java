/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.display;

import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerInternal.DisplayOffloadSession;
import android.hardware.display.DisplayViewport;
import android.os.IBinder;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.server.display.mode.DisplayModeDirector;

import java.io.PrintWriter;

/**
 * Represents a display device such as the built-in display, an external monitor, a WiFi display,
 * or a {@link android.hardware.display.VirtualDisplay}.
 * <p>
 * Display devices are guarded by the {@link DisplayManagerService.SyncRoot} lock.
 * </p>
 */
abstract class DisplayDevice {
    /**
     * Maximum acceptable anisotropy for the output image.
     *
     * Necessary to avoid unnecessary scaling when pixels are almost square, as they are non ideal
     * anyway. For external displays, we expect an anisotropy of about 2% even if the pixels
     * are, in fact, square due to the imprecision of the display's actual size (parsed from edid
     * and rounded to the nearest cm).
     */
    static final float MAX_ANISOTROPY = 1.025f;
    private static final String TAG = "DisplayDevice";
    private static final Display.Mode EMPTY_DISPLAY_MODE = new Display.Mode.Builder().build();

    private final DisplayAdapter mDisplayAdapter;
    private final IBinder mDisplayToken;
    private final String mUniqueId;

    protected DisplayDeviceConfig mDisplayDeviceConfig;
    // The display device does not manage these properties itself, they are set by
    // the display manager service.  The display device shouldn't really be looking at these.
    private int mCurrentLayerStack = -1;
    private int mCurrentFlags = 0;
    private int mCurrentOrientation = -1;
    private Rect mCurrentLayerStackRect;
    private Rect mCurrentDisplayRect;
    private final Context mContext;

    // The display device owns its surface, but it should only set it
    // within a transaction from performTraversalLocked.
    private Surface mCurrentSurface;

    // DEBUG STATE: Last device info which was written to the log, or null if none.
    // Do not use for any other purpose.
    DisplayDeviceInfo mDebugLastLoggedDeviceInfo;

    private final boolean mIsAnisotropyCorrectionEnabled;

    DisplayDevice(DisplayAdapter displayAdapter, IBinder displayToken, String uniqueId,
            Context context) {
        this(displayAdapter, displayToken, uniqueId, context, false);
    }

    DisplayDevice(DisplayAdapter displayAdapter, IBinder displayToken, String uniqueId,
            Context context, boolean isAnisotropyCorrectionEnabled) {
        mDisplayAdapter = displayAdapter;
        mDisplayToken = displayToken;
        mUniqueId = uniqueId;
        mDisplayDeviceConfig = null;
        mContext = context;
        mIsAnisotropyCorrectionEnabled = isAnisotropyCorrectionEnabled;
    }

    /**
     * Gets the display adapter that owns the display device.
     *
     * @return The display adapter.
     */
    public final DisplayAdapter getAdapterLocked() {
        return mDisplayAdapter;
    }

    /*
     * Gets the DisplayDeviceConfig for this DisplayDevice.
     *
     * @return The DisplayDeviceConfig; {@code null} if not overridden.
     */
    public DisplayDeviceConfig getDisplayDeviceConfig() {
        if (mDisplayDeviceConfig == null) {
            mDisplayDeviceConfig = loadDisplayDeviceConfig();
        }
        return mDisplayDeviceConfig;
    }

    /**
     * Gets the Surface Flinger display token for this display.
     *
     * @return The display token, or null if the display is not being managed
     * by Surface Flinger.
     */
    public final IBinder getDisplayTokenLocked() {
        return mDisplayToken;
    }

    /**
     * Gets the id of the display to mirror.
     */
    public int getDisplayIdToMirrorLocked() {
        return Display.DEFAULT_DISPLAY;
    }

    /**
     * Returns the if WindowManager is responsible for mirroring on this display. If {@code false},
     * then SurfaceFlinger performs no layer mirroring on this display.
     * Only used for mirroring started from MediaProjection.
     */
    public boolean isWindowManagerMirroringLocked() {
        return false;
    }

    /**
     * Updates if WindowManager is responsible for mirroring on this display. If {@code false}, then
     * SurfaceFlinger performs no layer mirroring to this display.
     * Only used for mirroring started from MediaProjection.
     */
    public void setWindowManagerMirroringLocked(boolean isMirroring) {
    }

    /**
     * Returns the default size of the surface associated with the display, or null if the surface
     * is not provided for layer mirroring by SurfaceFlinger. For non virtual displays, this will
     * be the actual display device's size, reflecting the current rotation.
     */
    @Nullable
    public Point getDisplaySurfaceDefaultSizeLocked() {
        DisplayDeviceInfo displayDeviceInfo = getDisplayDeviceInfoLocked();
        final boolean isRotated = mCurrentOrientation == ROTATION_90
                || mCurrentOrientation == ROTATION_270;
        var width = displayDeviceInfo.width;
        var height = displayDeviceInfo.height;
        if (mIsAnisotropyCorrectionEnabled && displayDeviceInfo.yDpi > 0
                    && displayDeviceInfo.xDpi > 0) {
            if (displayDeviceInfo.xDpi > displayDeviceInfo.yDpi * MAX_ANISOTROPY) {
                height = (int) (height * displayDeviceInfo.xDpi / displayDeviceInfo.yDpi + 0.5);
            } else if (displayDeviceInfo.xDpi * MAX_ANISOTROPY < displayDeviceInfo.yDpi) {
                width = (int) (width * displayDeviceInfo.yDpi / displayDeviceInfo.xDpi  + 0.5);
            }
        }
        return isRotated ? new Point(height, width) : new Point(width, height);
    }

    /**
     * Gets the name of the display device.
     *
     * @return The display device name.
     */
    public final String getNameLocked() {
        return getDisplayDeviceInfoLocked().name;
    }

    /**
     * Returns the unique id of the display device.
     */
    public final String getUniqueId() {
        return mUniqueId;
    }

    /**
     * Returns whether the unique id of the device is stable across reboots.
     */
    public abstract boolean hasStableUniqueId();

    /**
     * Gets information about the display device.
     *
     * The information returned should not change between calls unless the display
     * adapter sent a {@link DisplayAdapter#DISPLAY_DEVICE_EVENT_CHANGED} event and
     * {@link #applyPendingDisplayDeviceInfoChangesLocked()} has been called to apply
     * the pending changes.
     *
     * @return The display device info, which should be treated as immutable by the caller.
     * The display device should allocate a new display device info object whenever
     * the data changes.
     */
    public abstract DisplayDeviceInfo getDisplayDeviceInfoLocked();

    /**
     * Applies any pending changes to the observable state of the display device
     * if the display adapter sent a {@link DisplayAdapter#DISPLAY_DEVICE_EVENT_CHANGED} event.
     */
    public void applyPendingDisplayDeviceInfoChangesLocked() {
    }

    /**
     * Gives the display device a chance to update its properties while in a transaction.
     */
    public void performTraversalLocked(SurfaceControl.Transaction t) {
    }

    /**
     * Sets the display state, if supported.
     *
     * @param state The new display state.
     * @param brightnessState The new display brightnessState.
     * @param sdrBrightnessState The new display brightnessState for SDR layers.
     * @param displayOffloadSession {@link DisplayOffloadSession} associated with current device.
     * @return A runnable containing work to be deferred until after we have exited the critical
     *     section, or null if none.
     */
    public Runnable requestDisplayStateLocked(
            int state,
            float brightnessState,
            float sdrBrightnessState,
            @Nullable DisplayOffloadSessionImpl displayOffloadSession) {
        return null;
    }

    /**
     * Sets the display mode specs.
     *
     * Not all display devices will automatically switch between modes, so it's important that the
     * default modeId is set correctly.
     */
    public void setDesiredDisplayModeSpecsLocked(
            DisplayModeDirector.DesiredDisplayModeSpecs displayModeSpecs) {}

    /**
     * Sets the user preferred display mode. Removes the user preferred display mode and sets
     * default display mode as the mode chosen by HAL, if 'mode' is null
     * Returns true if the mode set by user is supported by the display.
     */
    public void setUserPreferredDisplayModeLocked(Display.Mode mode) { }

    /**
     * Returns the user preferred display mode.
     */
    public Display.Mode getUserPreferredDisplayModeLocked() {
        return EMPTY_DISPLAY_MODE;
    }

    /**
     * Returns the system preferred display mode.
     */
    public Display.Mode getSystemPreferredDisplayModeLocked() {
        return EMPTY_DISPLAY_MODE;
    }

    /**
     * Returns the display mode that was being used when this display was first found by
     * display manager.
     * @hide
     */
    public Display.Mode getActiveDisplayModeAtStartLocked() {
        return EMPTY_DISPLAY_MODE;
    }

    /**
     * Sets the requested color mode.
     */
    public void setRequestedColorModeLocked(int colorMode) {
    }

    /**
     * Sends the Auto Low Latency Mode (ALLM) signal over HDMI, or requests an internal display to
     * switch to a low-latency mode.
     *
     * @param on Whether to set ALLM on or off.
     */
    public void setAutoLowLatencyModeLocked(boolean on) {
    }

    /**
     * Sends a ContentType=Game signal over HDMI, or requests an internal display to switch to a
     * game mode (generally lower latency).
     *
     * @param on Whether to send a ContentType=Game signal or not
     */
    public void setGameContentTypeLocked(boolean on) {
    }

    public void onOverlayChangedLocked() {
    }

    /**
     * Sets the display layer stack while in a transaction.
     */
    public final void setLayerStackLocked(SurfaceControl.Transaction t, int layerStack,
            int layerStackTag) {
        if (mCurrentLayerStack != layerStack) {
            mCurrentLayerStack = layerStack;
            t.setDisplayLayerStack(mDisplayToken, layerStack);
            Slog.i(TAG, "[" + layerStackTag + "] Layerstack set to " + layerStack + " for "
                    + mUniqueId);
        }
    }

    /**
     * Sets the display flags while in a transaction.
     *
     * Valid display flags:
     *  {@link SurfaceControl#DISPLAY_RECEIVES_INPUT}
     */
    public final void setDisplayFlagsLocked(SurfaceControl.Transaction t, int flags) {
        if (mCurrentFlags != flags) {
            mCurrentFlags = flags;
            t.setDisplayFlags(mDisplayToken, flags);
        }
    }

    /**
     * Sets the display projection while in a transaction.
     *
     * @param orientation defines the display's orientation
     * @param layerStackRect defines which area of the window manager coordinate
     *            space will be used
     * @param displayRect defines where on the display will layerStackRect be
     *            mapped to. displayRect is specified post-orientation, that is
     *            it uses the orientation seen by the end-user
     */
    public final void setProjectionLocked(SurfaceControl.Transaction t, int orientation,
            Rect layerStackRect, Rect displayRect) {
        if (mCurrentOrientation != orientation
                || mCurrentLayerStackRect == null
                || !mCurrentLayerStackRect.equals(layerStackRect)
                || mCurrentDisplayRect == null
                || !mCurrentDisplayRect.equals(displayRect)) {
            mCurrentOrientation = orientation;

            if (mCurrentLayerStackRect == null) {
                mCurrentLayerStackRect = new Rect();
            }
            mCurrentLayerStackRect.set(layerStackRect);

            if (mCurrentDisplayRect == null) {
                mCurrentDisplayRect = new Rect();
            }
            mCurrentDisplayRect.set(displayRect);

            t.setDisplayProjection(mDisplayToken,
                    orientation, layerStackRect, displayRect);
        }
    }

    /**
     * Sets the display surface while in a transaction.
     */
    public final void setSurfaceLocked(SurfaceControl.Transaction t, Surface surface) {
        if (mCurrentSurface != surface) {
            mCurrentSurface = surface;
            t.setDisplaySurface(mDisplayToken, surface);
        }
    }

    /**
     * Populates the specified viewport object with orientation,
     * physical and logical rects based on the display's current projection.
     */
    public final void populateViewportLocked(DisplayViewport viewport) {
        viewport.orientation = mCurrentOrientation;

        if (mCurrentLayerStackRect != null) {
            viewport.logicalFrame.set(mCurrentLayerStackRect);
        } else {
            viewport.logicalFrame.setEmpty();
        }

        if (mCurrentDisplayRect != null) {
            viewport.physicalFrame.set(mCurrentDisplayRect);
        } else {
            viewport.physicalFrame.setEmpty();
        }

        boolean isRotated = (mCurrentOrientation == Surface.ROTATION_90
                || mCurrentOrientation == ROTATION_270);
        DisplayDeviceInfo info = getDisplayDeviceInfoLocked();
        viewport.deviceWidth = isRotated ? info.height : info.width;
        viewport.deviceHeight = isRotated ? info.width : info.height;

        viewport.uniqueId = info.uniqueId;

        if (info.address instanceof DisplayAddress.Physical) {
            viewport.physicalPort = ((DisplayAddress.Physical) info.address).getPort();
        } else {
            viewport.physicalPort = null;
        }
    }

    /**
     * Dumps the local state of the display device.
     * Does not need to dump the display device info because that is already dumped elsewhere.
     */
    public void dumpLocked(PrintWriter pw) {
        pw.println("mAdapter=" + mDisplayAdapter.getName());
        pw.println("mUniqueId=" + mUniqueId);
        pw.println("mDisplayToken=" + mDisplayToken);
        pw.println("mCurrentLayerStack=" + mCurrentLayerStack);
        pw.println("mCurrentFlags=" + mCurrentFlags);
        pw.println("mCurrentOrientation=" + mCurrentOrientation);
        pw.println("mCurrentLayerStackRect=" + mCurrentLayerStackRect);
        pw.println("mCurrentDisplayRect=" + mCurrentDisplayRect);
        pw.println("mCurrentSurface=" + mCurrentSurface);
    }

    private DisplayDeviceConfig loadDisplayDeviceConfig() {
        return DisplayDeviceConfig.create(mContext, /* useConfigXml= */ false,
                mDisplayAdapter.getFeatureFlags());
    }
}
