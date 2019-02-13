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

import android.graphics.Rect;
import android.hardware.display.DisplayViewport;
import android.os.IBinder;
import android.view.DisplayAddress;
import android.view.Surface;
import android.view.SurfaceControl;

import java.io.PrintWriter;

/**
 * Represents a physical display device such as the built-in display
 * an external monitor, or a WiFi display.
 * <p>
 * Display devices are guarded by the {@link DisplayManagerService.SyncRoot} lock.
 * </p>
 */
abstract class DisplayDevice {
    private final DisplayAdapter mDisplayAdapter;
    private final IBinder mDisplayToken;
    private final String mUniqueId;

    // The display device does not manage these properties itself, they are set by
    // the display manager service.  The display device shouldn't really be looking at these.
    private int mCurrentLayerStack = -1;
    private int mCurrentOrientation = -1;
    private Rect mCurrentLayerStackRect;
    private Rect mCurrentDisplayRect;

    // The display device owns its surface, but it should only set it
    // within a transaction from performTraversalLocked.
    private Surface mCurrentSurface;

    // DEBUG STATE: Last device info which was written to the log, or null if none.
    // Do not use for any other purpose.
    DisplayDeviceInfo mDebugLastLoggedDeviceInfo;

    public DisplayDevice(DisplayAdapter displayAdapter, IBinder displayToken, String uniqueId) {
        mDisplayAdapter = displayAdapter;
        mDisplayToken = displayToken;
        mUniqueId = uniqueId;
    }

    /**
     * Gets the display adapter that owns the display device.
     *
     * @return The display adapter.
     */
    public final DisplayAdapter getAdapterLocked() {
        return mDisplayAdapter;
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
     * @param brightness The new display brightness.
     * @return A runnable containing work to be deferred until after we have
     * exited the critical section, or null if none.
     */
    public Runnable requestDisplayStateLocked(int state, int brightness) {
        return null;
    }

    /**
     * Sets the mode, if supported.
     */
    public void requestDisplayModesLocked(int colorMode, int modeId) {
    }

    public void onOverlayChangedLocked() {
    }

    /**
     * Sets the display layer stack while in a transaction.
     */
    public final void setLayerStackLocked(SurfaceControl.Transaction t, int layerStack) {
        if (mCurrentLayerStack != layerStack) {
            mCurrentLayerStack = layerStack;
            t.setDisplayLayerStack(mDisplayToken, layerStack);
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
                || mCurrentOrientation == Surface.ROTATION_270);
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
        pw.println("mCurrentOrientation=" + mCurrentOrientation);
        pw.println("mCurrentLayerStackRect=" + mCurrentLayerStackRect);
        pw.println("mCurrentDisplayRect=" + mCurrentDisplayRect);
        pw.println("mCurrentSurface=" + mCurrentSurface);
    }
}
