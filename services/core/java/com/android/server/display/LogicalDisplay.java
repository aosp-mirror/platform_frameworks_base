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
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

import libcore.util.Objects;

/**
 * Describes how a logical display is configured.
 * <p>
 * At this time, we only support logical displays that are coupled to a particular
 * primary display device from which the logical display derives its basic properties
 * such as its size, density and refresh rate.
 * </p><p>
 * A logical display may be mirrored onto multiple display devices in addition to its
 * primary display device.  Note that the contents of a logical display may not
 * always be visible, even on its primary display device, such as in the case where
 * the primary display device is currently mirroring content from a different
 * logical display.
 * </p><p>
 * This object is designed to encapsulate as much of the policy of logical
 * displays as possible.  The idea is to make it easy to implement new kinds of
 * logical displays mostly by making local changes to this class.
 * </p><p>
 * Note: The display manager architecture does not actually require logical displays
 * to be associated with any individual display device.  Logical displays and
 * display devices are orthogonal concepts.  Some mapping will exist between
 * logical displays and display devices but it can be many-to-many and
 * and some might have no relation at all.
 * </p><p>
 * Logical displays are guarded by the {@link DisplayManagerService.SyncRoot} lock.
 * </p>
 */
final class LogicalDisplay {
    private final DisplayInfo mBaseDisplayInfo = new DisplayInfo();

    // The layer stack we use when the display has been blanked to prevent any
    // of its content from appearing.
    private static final int BLANK_LAYER_STACK = -1;

    private final int mDisplayId;
    private final int mLayerStack;
    private DisplayInfo mOverrideDisplayInfo; // set by the window manager
    private DisplayInfo mInfo;

    // The display device that this logical display is based on and which
    // determines the base metrics that it uses.
    private DisplayDevice mPrimaryDisplayDevice;
    private DisplayDeviceInfo mPrimaryDisplayDeviceInfo;

    // True if the logical display has unique content.
    private boolean mHasContent;

    // The pending requested refresh rate. 0 if no request is pending.
    private float mRequestedRefreshRate;

    // Temporary rectangle used when needed.
    private final Rect mTempLayerStackRect = new Rect();
    private final Rect mTempDisplayRect = new Rect();

    public LogicalDisplay(int displayId, int layerStack, DisplayDevice primaryDisplayDevice) {
        mDisplayId = displayId;
        mLayerStack = layerStack;
        mPrimaryDisplayDevice = primaryDisplayDevice;
    }

    /**
     * Gets the logical display id of this logical display.
     *
     * @return The logical display id.
     */
    public int getDisplayIdLocked() {
        return mDisplayId;
    }

    /**
     * Gets the primary display device associated with this logical display.
     *
     * @return The primary display device.
     */
    public DisplayDevice getPrimaryDisplayDeviceLocked() {
        return mPrimaryDisplayDevice;
    }

    /**
     * Gets information about the logical display.
     *
     * @return The device info, which should be treated as immutable by the caller.
     * The logical display should allocate a new display info object whenever
     * the data changes.
     */
    public DisplayInfo getDisplayInfoLocked() {
        if (mInfo == null) {
            mInfo = new DisplayInfo();
            if (mOverrideDisplayInfo != null) {
                mInfo.copyFrom(mOverrideDisplayInfo);
                mInfo.layerStack = mBaseDisplayInfo.layerStack;
                mInfo.name = mBaseDisplayInfo.name;
                mInfo.uniqueId = mBaseDisplayInfo.uniqueId;
                mInfo.state = mBaseDisplayInfo.state;
            } else {
                mInfo.copyFrom(mBaseDisplayInfo);
            }
        }
        return mInfo;
    }

    /**
     * Sets overridden logical display information from the window manager.
     * This method can be used to adjust application insets, rotation, and other
     * properties that the window manager takes care of.
     *
     * @param info The logical display information, may be null.
     */
    public boolean setDisplayInfoOverrideFromWindowManagerLocked(DisplayInfo info) {
        if (info != null) {
            if (mOverrideDisplayInfo == null) {
                mOverrideDisplayInfo = new DisplayInfo(info);
                mInfo = null;
                return true;
            }
            if (!mOverrideDisplayInfo.equals(info)) {
                mOverrideDisplayInfo.copyFrom(info);
                mInfo = null;
                return true;
            }
        } else if (mOverrideDisplayInfo != null) {
            mOverrideDisplayInfo = null;
            mInfo = null;
            return true;
        }
        return false;
    }

    /**
     * Returns true if the logical display is in a valid state.
     * This method should be checked after calling {@link #updateLocked} to handle the
     * case where a logical display should be removed because all of its associated
     * display devices are gone or if it is otherwise no longer needed.
     *
     * @return True if the logical display is still valid.
     */
    public boolean isValidLocked() {
        return mPrimaryDisplayDevice != null;
    }

    /**
     * Updates the state of the logical display based on the available display devices.
     * The logical display might become invalid if it is attached to a display device
     * that no longer exists.
     *
     * @param devices The list of all connected display devices.
     */
    public void updateLocked(List<DisplayDevice> devices) {
        // Nothing to update if already invalid.
        if (mPrimaryDisplayDevice == null) {
            return;
        }

        // Check whether logical display has become invalid.
        if (!devices.contains(mPrimaryDisplayDevice)) {
            mPrimaryDisplayDevice = null;
            return;
        }

        // Bootstrap the logical display using its associated primary physical display.
        // We might use more elaborate configurations later.  It's possible that the
        // configuration of several physical displays might be used to determine the
        // logical display that they are sharing.  (eg. Adjust size for pixel-perfect
        // mirroring over HDMI.)
        DisplayDeviceInfo deviceInfo = mPrimaryDisplayDevice.getDisplayDeviceInfoLocked();
        if (!Objects.equal(mPrimaryDisplayDeviceInfo, deviceInfo)) {
            mBaseDisplayInfo.layerStack = mLayerStack;
            mBaseDisplayInfo.flags = 0;
            if ((deviceInfo.flags & DisplayDeviceInfo.FLAG_SUPPORTS_PROTECTED_BUFFERS) != 0) {
                mBaseDisplayInfo.flags |= Display.FLAG_SUPPORTS_PROTECTED_BUFFERS;
            }
            if ((deviceInfo.flags & DisplayDeviceInfo.FLAG_SECURE) != 0) {
                mBaseDisplayInfo.flags |= Display.FLAG_SECURE;
            }
            if ((deviceInfo.flags & DisplayDeviceInfo.FLAG_PRIVATE) != 0) {
                mBaseDisplayInfo.flags |= Display.FLAG_PRIVATE;
            }
            if ((deviceInfo.flags & DisplayDeviceInfo.FLAG_PRESENTATION) != 0) {
                mBaseDisplayInfo.flags |= Display.FLAG_PRESENTATION;
            }
            mBaseDisplayInfo.type = deviceInfo.type;
            mBaseDisplayInfo.address = deviceInfo.address;
            mBaseDisplayInfo.name = deviceInfo.name;
            mBaseDisplayInfo.uniqueId = deviceInfo.uniqueId;
            mBaseDisplayInfo.appWidth = deviceInfo.width;
            mBaseDisplayInfo.appHeight = deviceInfo.height;
            mBaseDisplayInfo.logicalWidth = deviceInfo.width;
            mBaseDisplayInfo.logicalHeight = deviceInfo.height;
            mBaseDisplayInfo.rotation = Surface.ROTATION_0;
            mBaseDisplayInfo.refreshRate = deviceInfo.refreshRate;
            mBaseDisplayInfo.supportedRefreshRates = Arrays.copyOf(
                    deviceInfo.supportedRefreshRates, deviceInfo.supportedRefreshRates.length);
            mBaseDisplayInfo.logicalDensityDpi = deviceInfo.densityDpi;
            mBaseDisplayInfo.physicalXDpi = deviceInfo.xDpi;
            mBaseDisplayInfo.physicalYDpi = deviceInfo.yDpi;
            mBaseDisplayInfo.appVsyncOffsetNanos = deviceInfo.appVsyncOffsetNanos;
            mBaseDisplayInfo.presentationDeadlineNanos = deviceInfo.presentationDeadlineNanos;
            mBaseDisplayInfo.state = deviceInfo.state;
            mBaseDisplayInfo.smallestNominalAppWidth = deviceInfo.width;
            mBaseDisplayInfo.smallestNominalAppHeight = deviceInfo.height;
            mBaseDisplayInfo.largestNominalAppWidth = deviceInfo.width;
            mBaseDisplayInfo.largestNominalAppHeight = deviceInfo.height;
            mBaseDisplayInfo.ownerUid = deviceInfo.ownerUid;
            mBaseDisplayInfo.ownerPackageName = deviceInfo.ownerPackageName;

            mPrimaryDisplayDeviceInfo = deviceInfo;
            mInfo = null;
        }
    }

    /**
     * Applies the layer stack and transformation to the given display device
     * so that it shows the contents of this logical display.
     *
     * We know that the given display device is only ever showing the contents of
     * a single logical display, so this method is expected to blow away all of its
     * transformation properties to make it happen regardless of what the
     * display device was previously showing.
     *
     * The caller must have an open Surface transaction.
     *
     * The display device may not be the primary display device, in the case
     * where the display is being mirrored.
     *
     * @param device The display device to modify.
     * @param isBlanked True if the device is being blanked.
     */
    public void configureDisplayInTransactionLocked(DisplayDevice device,
            boolean isBlanked) {
        final DisplayInfo displayInfo = getDisplayInfoLocked();
        final DisplayDeviceInfo displayDeviceInfo = device.getDisplayDeviceInfoLocked();

        // Set the layer stack.
        device.setLayerStackInTransactionLocked(isBlanked ? BLANK_LAYER_STACK : mLayerStack);

        // Set the refresh rate
        device.requestRefreshRateLocked(mRequestedRefreshRate);

        // Set the viewport.
        // This is the area of the logical display that we intend to show on the
        // display device.  For now, it is always the full size of the logical display.
        mTempLayerStackRect.set(0, 0, displayInfo.logicalWidth, displayInfo.logicalHeight);

        // Set the orientation.
        // The orientation specifies how the physical coordinate system of the display
        // is rotated when the contents of the logical display are rendered.
        int orientation = Surface.ROTATION_0;
        if ((displayDeviceInfo.flags & DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT) != 0) {
            orientation = displayInfo.rotation;
        }

        // Apply the physical rotation of the display device itself.
        orientation = (orientation + displayDeviceInfo.rotation) % 4;

        // Set the frame.
        // The frame specifies the rotated physical coordinates into which the viewport
        // is mapped.  We need to take care to preserve the aspect ratio of the viewport.
        // Currently we maximize the area to fill the display, but we could try to be
        // more clever and match resolutions.
        boolean rotated = (orientation == Surface.ROTATION_90
                || orientation == Surface.ROTATION_270);
        int physWidth = rotated ? displayDeviceInfo.height : displayDeviceInfo.width;
        int physHeight = rotated ? displayDeviceInfo.width : displayDeviceInfo.height;

        // Determine whether the width or height is more constrained to be scaled.
        //    physWidth / displayInfo.logicalWidth    => letter box
        // or physHeight / displayInfo.logicalHeight  => pillar box
        //
        // We avoid a division (and possible floating point imprecision) here by
        // multiplying the fractions by the product of their denominators before
        // comparing them.
        int displayRectWidth, displayRectHeight;
        if (physWidth * displayInfo.logicalHeight
                < physHeight * displayInfo.logicalWidth) {
            // Letter box.
            displayRectWidth = physWidth;
            displayRectHeight = displayInfo.logicalHeight * physWidth / displayInfo.logicalWidth;
        } else {
            // Pillar box.
            displayRectWidth = displayInfo.logicalWidth * physHeight / displayInfo.logicalHeight;
            displayRectHeight = physHeight;
        }
        int displayRectTop = (physHeight - displayRectHeight) / 2;
        int displayRectLeft = (physWidth - displayRectWidth) / 2;
        mTempDisplayRect.set(displayRectLeft, displayRectTop,
                displayRectLeft + displayRectWidth, displayRectTop + displayRectHeight);

        device.setProjectionInTransactionLocked(orientation, mTempLayerStackRect, mTempDisplayRect);
    }

    /**
     * Returns true if the logical display has unique content.
     * <p>
     * If the display has unique content then we will try to ensure that it is
     * visible on at least its primary display device.  Otherwise we will ignore the
     * logical display and perhaps show mirrored content on the primary display device.
     * </p>
     *
     * @return True if the display has unique content.
     */
    public boolean hasContentLocked() {
        return mHasContent;
    }

    /**
     * Sets whether the logical display has unique content.
     *
     * @param hasContent True if the display has unique content.
     */
    public void setHasContentLocked(boolean hasContent) {
        mHasContent = hasContent;
    }

    /**
     * Requests the given refresh rate.
     * @param requestedRefreshRate The desired refresh rate.
     */
    public void setRequestedRefreshRateLocked(float requestedRefreshRate) {
        mRequestedRefreshRate = requestedRefreshRate;
    }

    /**
     * Gets the pending requested refresh rate.
     *
     * @return The pending refresh rate requested
     */
    public float getRequestedRefreshRateLocked() {
        return mRequestedRefreshRate;
    }

    public void dumpLocked(PrintWriter pw) {
        pw.println("mDisplayId=" + mDisplayId);
        pw.println("mLayerStack=" + mLayerStack);
        pw.println("mHasContent=" + mHasContent);
        pw.println("mPrimaryDisplayDevice=" + (mPrimaryDisplayDevice != null ?
                mPrimaryDisplayDevice.getNameLocked() : "null"));
        pw.println("mBaseDisplayInfo=" + mBaseDisplayInfo);
        pw.println("mOverrideDisplayInfo=" + mOverrideDisplayInfo);
    }
}
