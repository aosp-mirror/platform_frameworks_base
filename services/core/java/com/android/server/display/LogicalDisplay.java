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

import static com.android.server.display.DisplayDeviceInfo.TOUCH_NONE;
import static com.android.server.wm.utils.DisplayInfoOverrides.WM_OVERRIDE_FIELDS;
import static com.android.server.wm.utils.DisplayInfoOverrides.copyDisplayInfoFields;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerInternal;
import android.util.ArraySet;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayEventReceiver;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.server.display.layout.Layout;
import com.android.server.display.mode.DisplayModeDirector;
import com.android.server.wm.utils.InsetUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Objects;

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
    private static final String TAG = "LogicalDisplay";
    // The layer stack we use when the display has been blanked to prevent any
    // of its content from appearing.
    private static final int BLANK_LAYER_STACK = -1;

    private static final DisplayInfo EMPTY_DISPLAY_INFO = new DisplayInfo();

    private final DisplayInfo mBaseDisplayInfo = new DisplayInfo();
    private final int mDisplayId;
    private final int mLayerStack;

    // Indicates which display leads this logical display, in terms of brightness or other
    // properties.
    // {@link Layout.NO_LEAD_DISPLAY} means that this display is not lead by any others, and could
    // be a leader itself.
    private int mLeadDisplayId = Layout.NO_LEAD_DISPLAY;

    private int mDisplayGroupId = Display.INVALID_DISPLAY_GROUP;

    /**
     * Override information set by the window manager. Will be reported instead of {@link #mInfo}
     * if not null.
     * @see #setDisplayInfoOverrideFromWindowManagerLocked(DisplayInfo)
     * @see #getDisplayInfoLocked()
     */
    private DisplayInfo mOverrideDisplayInfo;
    /**
     * Current display info. Initialized with {@link #mBaseDisplayInfo}. Set to {@code null} if
     * needs to be updated.
     * @see #getDisplayInfoLocked()
     */
    private final DisplayInfoProxy mInfo = new DisplayInfoProxy(null);

    // The display device that this logical display is based on and which
    // determines the base metrics that it uses.
    private DisplayDevice mPrimaryDisplayDevice;
    private DisplayDeviceInfo mPrimaryDisplayDeviceInfo;

    // True if the logical display has unique content.
    private boolean mHasContent;

    private int mRequestedColorMode;
    private boolean mRequestedMinimalPostProcessing;

    private int[] mUserDisabledHdrTypes = {};

    private DisplayModeDirector.DesiredDisplayModeSpecs mDesiredDisplayModeSpecs =
            new DisplayModeDirector.DesiredDisplayModeSpecs();

    // The display offsets to apply to the display projection.
    private int mDisplayOffsetX;
    private int mDisplayOffsetY;

    /**
     * The position of the display projection sent to SurfaceFlinger
     */
    private final Point mDisplayPosition = new Point();

    /**
     * {@code true} if display scaling is disabled, or {@code false} if the default scaling mode
     * is used.
     * @see #isDisplayScalingDisabled()
     * @see #setDisplayScalingDisabledLocked(boolean)
     */
    private boolean mDisplayScalingDisabled;

    // Temporary rectangle used when needed.
    private final Rect mTempLayerStackRect = new Rect();
    private final Rect mTempDisplayRect = new Rect();

    /** A session token that controls the offloading operations of this logical display. */
    private DisplayOffloadSessionImpl mDisplayOffloadSession;

    /**
     * Name of a display group to which the display is assigned.
     */
    private String mDisplayGroupName;

    /**
     * The UID mappings for refresh rate override
     */
    private DisplayEventReceiver.FrameRateOverride[] mFrameRateOverrides;

    /**
     * Holds a set of UIDs that their frame rate override changed and needs to be notified
     */
    private ArraySet<Integer> mPendingFrameRateOverrideUids;

    /**
     * Temporary frame rate override list, used when needed.
     */
    private final SparseArray<Float> mTempFrameRateOverride;

    // Indicates the display is enabled (allowed to be ON).
    private boolean mIsEnabled;

    // Indicates the display is part of a transition from one device-state ({@link
    // DeviceStateManager}) to another. Being a "part" of a transition means that either
    // the {@link mIsEnabled} is changing, or the underlying mPrimaryDisplayDevice is changing.
    private boolean mIsInTransition;

    // Indicates the position of the display, POSITION_UNKNOWN could mean it hasn't been specified,
    // or this is a virtual display etc.
    private int mDevicePosition = Layout.Display.POSITION_UNKNOWN;

    // Indicates that something other than the primary display device info has changed and needs to
    // be handled in the next update.
    private boolean mDirty = false;

    /**
     * The ID of the thermal brightness throttling data that should be used. This can change e.g.
     * in concurrent displays mode in which a stricter brightness throttling policy might need to
     * be used.
     */
    private String mThermalBrightnessThrottlingDataId;

    /**
     * Refresh rate range limitation based on the current device layout
     */
    @Nullable
    private SurfaceControl.RefreshRateRange mLayoutLimitedRefreshRate;

    /**
     * The ID of the power throttling data that should be used.
     */
    private String mPowerThrottlingDataId;

    /**
     * RefreshRateRange limitation for @Temperature.ThrottlingStatus
     */
    @NonNull
    private SparseArray<SurfaceControl.RefreshRateRange> mThermalRefreshRateThrottling =
            new SparseArray<>();

    /**
     * If the aspect ratio of the resolution of the display does not match the physical aspect
     * ratio of the display, then without this feature enabled, picture would appear stretched to
     * the user. This is because applications assume that they are rendered on square pixels
     * (meaning density of pixels in x and y directions are equal). This would result into circles
     * appearing as ellipses to the user.
     * To compensate for non-square (anisotropic) pixels, if this feature is enabled:
     * 1. LogicalDisplay will add more pixels for the applications to render on, as if the pixels
     * were square and occupied the full display.
     * 2. SurfaceFlinger will squeeze this taller/wider surface into the available number of
     * physical pixels in the current display resolution.
     * 3. If a setting on the display itself is set to "fill the entire display panel" then the
     * display will stretch the pixels to fill the display fully.
     */
    private final boolean mIsAnisotropyCorrectionEnabled;

    LogicalDisplay(int displayId, int layerStack, DisplayDevice primaryDisplayDevice) {
        this(displayId, layerStack, primaryDisplayDevice, false);
    }

    LogicalDisplay(int displayId, int layerStack, DisplayDevice primaryDisplayDevice,
            boolean isAnisotropyCorrectionEnabled) {
        mDisplayId = displayId;
        mLayerStack = layerStack;
        mPrimaryDisplayDevice = primaryDisplayDevice;
        mPendingFrameRateOverrideUids = new ArraySet<>();
        mTempFrameRateOverride = new SparseArray<>();
        mIsEnabled = true;
        mIsInTransition = false;
        mThermalBrightnessThrottlingDataId = DisplayDeviceConfig.DEFAULT_ID;
        mPowerThrottlingDataId = DisplayDeviceConfig.DEFAULT_ID;
        mBaseDisplayInfo.thermalBrightnessThrottlingDataId = mThermalBrightnessThrottlingDataId;
        mIsAnisotropyCorrectionEnabled = isAnisotropyCorrectionEnabled;
    }

    public void setDevicePositionLocked(int position) {
        if (mDevicePosition != position) {
            mDevicePosition = position;
            mDirty = true;
        }
    }
    public int getDevicePositionLocked() {
        return mDevicePosition;
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
        if (mInfo.get() == null) {
            DisplayInfo info = new DisplayInfo();
            copyDisplayInfoFields(info, mBaseDisplayInfo, mOverrideDisplayInfo,
                    WM_OVERRIDE_FIELDS);
            mInfo.set(info);
        }
        return mInfo.get();
    }

    /**
     * Returns the frame rate overrides list
     */
    public DisplayEventReceiver.FrameRateOverride[] getFrameRateOverrides() {
        return mFrameRateOverrides;
    }

    /**
     * Returns the list of uids that needs to be updated about their frame rate override
     */
    public ArraySet<Integer> getPendingFrameRateOverrideUids() {
        return mPendingFrameRateOverrideUids;
    }

    /**
     * Clears the list of uids that needs to be updated about their frame rate override
     */
    public void clearPendingFrameRateOverrideUids() {
        mPendingFrameRateOverrideUids = new ArraySet<>();
    }

    /**
     * @see DisplayManagerInternal#getNonOverrideDisplayInfo(int, DisplayInfo)
     */
    void getNonOverrideDisplayInfoLocked(DisplayInfo outInfo) {
        outInfo.copyFrom(mBaseDisplayInfo);
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
                mInfo.set(null);
                return true;
            } else if (!mOverrideDisplayInfo.equals(info)) {
                mOverrideDisplayInfo.copyFrom(info);
                mInfo.set(null);
                return true;
            }
        } else if (mOverrideDisplayInfo != null) {
            mOverrideDisplayInfo = null;
            mInfo.set(null);
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

    boolean isDirtyLocked() {
        return mDirty;
    }

    /**
     * Updates the {@link DisplayGroup} to which the logical display belongs.
     *
     * @param groupId Identifier for the {@link DisplayGroup}.
     */
    public void updateDisplayGroupIdLocked(int groupId) {
        if (groupId != mDisplayGroupId) {
            mDisplayGroupId = groupId;
            mDirty = true;
        }
    }

    /**
     * Updates layoutLimitedRefreshRate
     *
     * @param layoutLimitedRefreshRate refresh rate limited by layout or null.
     */
    public void updateLayoutLimitedRefreshRateLocked(
            @Nullable SurfaceControl.RefreshRateRange layoutLimitedRefreshRate) {
        if (!Objects.equals(layoutLimitedRefreshRate, mLayoutLimitedRefreshRate)) {
            mLayoutLimitedRefreshRate = layoutLimitedRefreshRate;
            mDirty = true;
        }
    }
    /**
     * Updates thermalRefreshRateThrottling
     *
     * @param refreshRanges new thermalRefreshRateThrottling ranges limited by layout or default
     */
    public void updateThermalRefreshRateThrottling(
            @Nullable SparseArray<SurfaceControl.RefreshRateRange> refreshRanges) {
        if (refreshRanges == null) {
            refreshRanges = new SparseArray<>();
        }
        if (!mThermalRefreshRateThrottling.contentEquals(refreshRanges)) {
            mThermalRefreshRateThrottling = refreshRanges;
            mDirty = true;
        }
    }

    /**
     * Updates the state of the logical display based on the available display devices.
     * The logical display might become invalid if it is attached to a display device
     * that no longer exists.
     *
     * @param deviceRepo Repository of active {@link DisplayDevice}s.
     */
    public void updateLocked(DisplayDeviceRepository deviceRepo) {
        // Nothing to update if already invalid.
        if (mPrimaryDisplayDevice == null) {
            return;
        }

        // Check whether logical display has become invalid.
        if (!deviceRepo.containsLocked(mPrimaryDisplayDevice)) {
            setPrimaryDisplayDeviceLocked(null);
            return;
        }

        // Bootstrap the logical display using its associated primary physical display.
        // We might use more elaborate configurations later.  It's possible that the
        // configuration of several physical displays might be used to determine the
        // logical display that they are sharing.  (eg. Adjust size for pixel-perfect
        // mirroring over HDMI.)
        DisplayDeviceInfo deviceInfo = mPrimaryDisplayDevice.getDisplayDeviceInfoLocked();
        if (!Objects.equals(mPrimaryDisplayDeviceInfo, deviceInfo) || mDirty) {
            mBaseDisplayInfo.layerStack = mLayerStack;
            mBaseDisplayInfo.flags = 0;
            // Displays default to moving content to the primary display when removed
            mBaseDisplayInfo.removeMode = Display.REMOVE_MODE_MOVE_CONTENT_TO_PRIMARY;
            if ((deviceInfo.flags & DisplayDeviceInfo.FLAG_SUPPORTS_PROTECTED_BUFFERS) != 0) {
                mBaseDisplayInfo.flags |= Display.FLAG_SUPPORTS_PROTECTED_BUFFERS;
            }
            if ((deviceInfo.flags & DisplayDeviceInfo.FLAG_SECURE) != 0) {
                mBaseDisplayInfo.flags |= Display.FLAG_SECURE;
            }
            if ((deviceInfo.flags & DisplayDeviceInfo.FLAG_PRIVATE) != 0) {
                mBaseDisplayInfo.flags |= Display.FLAG_PRIVATE;
                // For private displays by default content is destroyed on removal.
                mBaseDisplayInfo.removeMode = Display.REMOVE_MODE_DESTROY_CONTENT;
            }
            if ((deviceInfo.flags & DisplayDeviceInfo.FLAG_DESTROY_CONTENT_ON_REMOVAL) != 0) {
                mBaseDisplayInfo.removeMode = Display.REMOVE_MODE_DESTROY_CONTENT;
            }
            if ((deviceInfo.flags & DisplayDeviceInfo.FLAG_PRESENTATION) != 0) {
                mBaseDisplayInfo.flags |= Display.FLAG_PRESENTATION;
            }
            if ((deviceInfo.flags & DisplayDeviceInfo.FLAG_ROUND) != 0) {
                mBaseDisplayInfo.flags |= Display.FLAG_ROUND;
            }
            if ((deviceInfo.flags & DisplayDeviceInfo.FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD) != 0) {
                mBaseDisplayInfo.flags |= Display.FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD;
            }
            if ((deviceInfo.flags & DisplayDeviceInfo.FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS) != 0) {
                mBaseDisplayInfo.flags |= Display.FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS;
            }
            if ((deviceInfo.flags & DisplayDeviceInfo.FLAG_TRUSTED) != 0) {
                mBaseDisplayInfo.flags |= Display.FLAG_TRUSTED;
            }
            if ((deviceInfo.flags & DisplayDeviceInfo.FLAG_OWN_DISPLAY_GROUP) != 0) {
                mBaseDisplayInfo.flags |= Display.FLAG_OWN_DISPLAY_GROUP;
            }
            if ((deviceInfo.flags & DisplayDeviceInfo.FLAG_ALWAYS_UNLOCKED) != 0) {
                mBaseDisplayInfo.flags |= Display.FLAG_ALWAYS_UNLOCKED;
            }
            if ((deviceInfo.flags & DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT) != 0) {
                mBaseDisplayInfo.flags |= Display.FLAG_ROTATES_WITH_CONTENT;
            }
            if ((deviceInfo.flags & DisplayDeviceInfo.FLAG_TOUCH_FEEDBACK_DISABLED) != 0) {
                mBaseDisplayInfo.flags |= Display.FLAG_TOUCH_FEEDBACK_DISABLED;
            }
            if ((deviceInfo.flags & DisplayDeviceInfo.FLAG_OWN_FOCUS) != 0) {
                mBaseDisplayInfo.flags |= Display.FLAG_OWN_FOCUS;
            }
            if ((deviceInfo.flags & DisplayDeviceInfo.FLAG_STEAL_TOP_FOCUS_DISABLED) != 0) {
                mBaseDisplayInfo.flags |= Display.FLAG_STEAL_TOP_FOCUS_DISABLED;
            }
            Rect maskingInsets = getMaskingInsets(deviceInfo);
            int maskedWidth = deviceInfo.width - maskingInsets.left - maskingInsets.right;
            int maskedHeight = deviceInfo.height - maskingInsets.top - maskingInsets.bottom;

            if (mIsAnisotropyCorrectionEnabled && deviceInfo.xDpi > 0 && deviceInfo.yDpi > 0) {
                if (deviceInfo.xDpi > deviceInfo.yDpi * DisplayDevice.MAX_ANISOTROPY) {
                    maskedHeight = (int) (maskedHeight * deviceInfo.xDpi / deviceInfo.yDpi + 0.5);
                } else if (deviceInfo.xDpi * DisplayDevice.MAX_ANISOTROPY < deviceInfo.yDpi) {
                    maskedWidth = (int) (maskedWidth * deviceInfo.yDpi / deviceInfo.xDpi + 0.5);
                }
            }

            mBaseDisplayInfo.type = deviceInfo.type;
            mBaseDisplayInfo.address = deviceInfo.address;
            mBaseDisplayInfo.deviceProductInfo = deviceInfo.deviceProductInfo;
            mBaseDisplayInfo.name = deviceInfo.name;
            mBaseDisplayInfo.uniqueId = deviceInfo.uniqueId;
            mBaseDisplayInfo.appWidth = maskedWidth;
            mBaseDisplayInfo.appHeight = maskedHeight;
            mBaseDisplayInfo.logicalWidth = maskedWidth;
            mBaseDisplayInfo.logicalHeight = maskedHeight;
            mBaseDisplayInfo.rotation = Surface.ROTATION_0;
            mBaseDisplayInfo.modeId = deviceInfo.modeId;
            mBaseDisplayInfo.renderFrameRate = deviceInfo.renderFrameRate;
            mBaseDisplayInfo.defaultModeId = deviceInfo.defaultModeId;
            mBaseDisplayInfo.userPreferredModeId = deviceInfo.userPreferredModeId;
            mBaseDisplayInfo.supportedModes = Arrays.copyOf(
                    deviceInfo.supportedModes, deviceInfo.supportedModes.length);
            mBaseDisplayInfo.colorMode = deviceInfo.colorMode;
            mBaseDisplayInfo.supportedColorModes = Arrays.copyOf(
                    deviceInfo.supportedColorModes,
                    deviceInfo.supportedColorModes.length);
            mBaseDisplayInfo.hdrCapabilities = deviceInfo.hdrCapabilities;
            mBaseDisplayInfo.userDisabledHdrTypes = mUserDisabledHdrTypes;
            mBaseDisplayInfo.minimalPostProcessingSupported =
                    deviceInfo.allmSupported || deviceInfo.gameContentTypeSupported;
            mBaseDisplayInfo.logicalDensityDpi = deviceInfo.densityDpi;
            mBaseDisplayInfo.physicalXDpi = deviceInfo.xDpi;
            mBaseDisplayInfo.physicalYDpi = deviceInfo.yDpi;
            mBaseDisplayInfo.appVsyncOffsetNanos = deviceInfo.appVsyncOffsetNanos;
            mBaseDisplayInfo.presentationDeadlineNanos = deviceInfo.presentationDeadlineNanos;
            mBaseDisplayInfo.state = deviceInfo.state;
            mBaseDisplayInfo.committedState = deviceInfo.committedState;
            mBaseDisplayInfo.smallestNominalAppWidth = maskedWidth;
            mBaseDisplayInfo.smallestNominalAppHeight = maskedHeight;
            mBaseDisplayInfo.largestNominalAppWidth = maskedWidth;
            mBaseDisplayInfo.largestNominalAppHeight = maskedHeight;
            mBaseDisplayInfo.ownerUid = deviceInfo.ownerUid;
            mBaseDisplayInfo.ownerPackageName = deviceInfo.ownerPackageName;
            boolean maskCutout =
                    (deviceInfo.flags & DisplayDeviceInfo.FLAG_MASK_DISPLAY_CUTOUT) != 0;
            mBaseDisplayInfo.displayCutout = maskCutout ? null : deviceInfo.displayCutout;
            mBaseDisplayInfo.displayId = mDisplayId;
            mBaseDisplayInfo.displayGroupId = mDisplayGroupId;
            updateFrameRateOverrides(deviceInfo);
            mBaseDisplayInfo.brightnessMinimum = deviceInfo.brightnessMinimum;
            mBaseDisplayInfo.brightnessMaximum = deviceInfo.brightnessMaximum;
            mBaseDisplayInfo.brightnessDefault = deviceInfo.brightnessDefault;
            mBaseDisplayInfo.hdrSdrRatio = deviceInfo.hdrSdrRatio;
            mBaseDisplayInfo.roundedCorners = deviceInfo.roundedCorners;
            mBaseDisplayInfo.installOrientation = deviceInfo.installOrientation;
            mBaseDisplayInfo.displayShape = deviceInfo.displayShape;

            if (mDevicePosition == Layout.Display.POSITION_REAR) {
                // A rear display is meant to host a specific experience that is essentially
                // a presentation to another user or users other than the main user since they
                // can't actually see that display. Given that, it's a suitable display for
                // presentations but the content should be destroyed rather than moved to a non-rear
                // display when the rear display is removed.
                mBaseDisplayInfo.flags |= Display.FLAG_REAR;
                mBaseDisplayInfo.flags |= Display.FLAG_PRESENTATION;
                mBaseDisplayInfo.removeMode = Display.REMOVE_MODE_DESTROY_CONTENT;
            }

            mBaseDisplayInfo.layoutLimitedRefreshRate = mLayoutLimitedRefreshRate;
            mBaseDisplayInfo.thermalRefreshRateThrottling = mThermalRefreshRateThrottling;
            mBaseDisplayInfo.thermalBrightnessThrottlingDataId = mThermalBrightnessThrottlingDataId;

            mPrimaryDisplayDeviceInfo = deviceInfo;
            mInfo.set(null);
            mDirty = false;
        }
    }

    private void updateFrameRateOverrides(DisplayDeviceInfo deviceInfo) {
        mTempFrameRateOverride.clear();
        if (mFrameRateOverrides != null) {
            for (DisplayEventReceiver.FrameRateOverride frameRateOverride
                    : mFrameRateOverrides) {
                mTempFrameRateOverride.put(frameRateOverride.uid,
                        frameRateOverride.frameRateHz);
            }
        }
        mFrameRateOverrides = deviceInfo.frameRateOverrides;
        if (mFrameRateOverrides != null) {
            for (DisplayEventReceiver.FrameRateOverride frameRateOverride
                    : mFrameRateOverrides) {
                float refreshRate = mTempFrameRateOverride.get(frameRateOverride.uid, 0f);
                if (refreshRate == 0 || frameRateOverride.frameRateHz != refreshRate) {
                    mTempFrameRateOverride.put(frameRateOverride.uid,
                            frameRateOverride.frameRateHz);
                } else {
                    mTempFrameRateOverride.delete(frameRateOverride.uid);
                }
            }
        }
        for (int i = 0; i < mTempFrameRateOverride.size(); i++) {
            mPendingFrameRateOverrideUids.add(mTempFrameRateOverride.keyAt(i));
        }
    }

    /**
     * Return the insets currently applied to the display.
     *
     * Note that the base DisplayInfo already takes these insets into account, so if you want to
     * find out the <b>true</b> size of the display, you need to add them back to the logical
     * dimensions.
     */
    public Rect getInsets() {
        return getMaskingInsets(mPrimaryDisplayDeviceInfo);
    }

    /**
     * Returns insets in ROTATION_0 for areas that are masked.
     */
    private static Rect getMaskingInsets(DisplayDeviceInfo deviceInfo) {
        boolean maskCutout = (deviceInfo.flags & DisplayDeviceInfo.FLAG_MASK_DISPLAY_CUTOUT) != 0;
        if (maskCutout && deviceInfo.displayCutout != null) {
            // getSafeInsets is fixed at creation time and cannot change
            return deviceInfo.displayCutout.getSafeInsets();
        } else {
            return new Rect();
        }
    }

    /**
     * Returns the position of the display's projection.
     *
     * @return The x, y coordinates of the display. The return object must be treated as immutable.
     */
    Point getDisplayPosition() {
        // Allocate a new object to avoid a data race.
        return new Point(mDisplayPosition);
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
    public void configureDisplayLocked(SurfaceControl.Transaction t,
            DisplayDevice device,
            boolean isBlanked) {
        // Set the layer stack.
        device.setLayerStackLocked(t, isBlanked ? BLANK_LAYER_STACK : mLayerStack, mDisplayId);
        // Also inform whether the device is the same one sent to inputflinger for its layerstack.
        // Prevent displays that are disabled from receiving input.
        // TODO(b/188914255): Remove once input can dispatch against device vs layerstack.
        device.setDisplayFlagsLocked(t,
                (isEnabledLocked() && device.getDisplayDeviceInfoLocked().touch != TOUCH_NONE)
                        ? SurfaceControl.DISPLAY_RECEIVES_INPUT
                        : 0);

        // Set the color mode and allowed display mode.
        if (device == mPrimaryDisplayDevice) {
            device.setDesiredDisplayModeSpecsLocked(mDesiredDisplayModeSpecs);
            device.setRequestedColorModeLocked(mRequestedColorMode);
        } else {
            // Reset to default for non primary displays
            device.setDesiredDisplayModeSpecsLocked(
                    new DisplayModeDirector.DesiredDisplayModeSpecs());
            device.setRequestedColorModeLocked(0);
        }

        device.setAutoLowLatencyModeLocked(mRequestedMinimalPostProcessing);
        device.setGameContentTypeLocked(mRequestedMinimalPostProcessing);

        // Only grab the display info now as it may have been changed based on the requests above.
        final DisplayInfo displayInfo = getDisplayInfoLocked();
        final DisplayDeviceInfo displayDeviceInfo = device.getDisplayDeviceInfoLocked();

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

        Rect maskingInsets = getMaskingInsets(displayDeviceInfo);
        InsetUtils.rotateInsets(maskingInsets, orientation);
        // Don't consider the masked area as available when calculating the scaling below.
        physWidth -= maskingInsets.left + maskingInsets.right;
        physHeight -= maskingInsets.top + maskingInsets.bottom;

        var displayLogicalWidth = displayInfo.logicalWidth;
        var displayLogicalHeight = displayInfo.logicalHeight;

        if (mIsAnisotropyCorrectionEnabled && displayDeviceInfo.xDpi > 0
                    && displayDeviceInfo.yDpi > 0) {
            if (displayDeviceInfo.xDpi > displayDeviceInfo.yDpi * DisplayDevice.MAX_ANISOTROPY) {
                var scalingFactor = displayDeviceInfo.yDpi / displayDeviceInfo.xDpi;
                if (rotated) {
                    displayLogicalWidth = (int) ((float) displayLogicalWidth * scalingFactor + 0.5);
                } else {
                    displayLogicalHeight = (int) ((float) displayLogicalHeight * scalingFactor
                                                          + 0.5);
                }
            } else if (displayDeviceInfo.xDpi * DisplayDevice.MAX_ANISOTROPY
                               < displayDeviceInfo.yDpi) {
                var scalingFactor = displayDeviceInfo.xDpi / displayDeviceInfo.yDpi;
                if (rotated) {
                    displayLogicalHeight = (int) ((float) displayLogicalHeight * scalingFactor
                                                          + 0.5);
                } else {
                    displayLogicalWidth = (int) ((float) displayLogicalWidth * scalingFactor + 0.5);
                }
            }
        }

        // Determine whether the width or height is more constrained to be scaled.
        //    physWidth / displayInfo.logicalWidth    => letter box
        // or physHeight / displayInfo.logicalHeight  => pillar box
        //
        // We avoid a division (and possible floating point imprecision) here by
        // multiplying the fractions by the product of their denominators before
        // comparing them.
        int displayRectWidth, displayRectHeight;
        if ((displayInfo.flags & Display.FLAG_SCALING_DISABLED) != 0 || mDisplayScalingDisabled) {
            displayRectWidth = displayLogicalWidth;
            displayRectHeight = displayLogicalHeight;
        } else if (physWidth * displayLogicalHeight
                < physHeight * displayLogicalWidth) {
            // Letter box.
            displayRectWidth = physWidth;
            displayRectHeight = displayLogicalHeight * physWidth / displayLogicalWidth;
        } else {
            // Pillar box.
            displayRectWidth = displayLogicalWidth * physHeight / displayLogicalHeight;
            displayRectHeight = physHeight;
        }
        int displayRectTop = (physHeight - displayRectHeight) / 2;
        int displayRectLeft = (physWidth - displayRectWidth) / 2;
        mTempDisplayRect.set(displayRectLeft, displayRectTop,
                displayRectLeft + displayRectWidth, displayRectTop + displayRectHeight);

        // Now add back the offset for the masked area.
        mTempDisplayRect.offset(maskingInsets.left, maskingInsets.top);

        if (orientation == Surface.ROTATION_0) {
            mTempDisplayRect.offset(mDisplayOffsetX, mDisplayOffsetY);
        } else if (orientation == Surface.ROTATION_90) {
            mTempDisplayRect.offset(mDisplayOffsetY, -mDisplayOffsetX);
        } else if (orientation == Surface.ROTATION_180) {
            mTempDisplayRect.offset(-mDisplayOffsetX, -mDisplayOffsetY);
        } else {  // Surface.ROTATION_270
            mTempDisplayRect.offset(-mDisplayOffsetY, mDisplayOffsetX);
        }

        mDisplayPosition.set(mTempDisplayRect.left, mTempDisplayRect.top);
        device.setProjectionLocked(t, orientation, mTempLayerStackRect, mTempDisplayRect);
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
     * Sets the display configs the system can use.
     */
    public void setDesiredDisplayModeSpecsLocked(
            DisplayModeDirector.DesiredDisplayModeSpecs specs) {
        mDesiredDisplayModeSpecs = specs;
    }

    /**
     * Returns the display configs the system can choose.
     */
    public DisplayModeDirector.DesiredDisplayModeSpecs getDesiredDisplayModeSpecsLocked() {
        return mDesiredDisplayModeSpecs;
    }

    /**
     * Requests the given color mode.
     */
    public void setRequestedColorModeLocked(int colorMode) {
        mRequestedColorMode = colorMode;
    }

    /**
     * Returns the last requested minimal post processing setting.
     */
    public boolean getRequestedMinimalPostProcessingLocked() {
        return mRequestedMinimalPostProcessing;
    }

    /**
     * Instructs the connected display to do minimal post processing. This is implemented either
     * via HDMI 2.1 ALLM or HDMI 1.4 ContentType=Game.
     *
     * @param on Whether to set minimal post processing on/off on the connected display.
     */
    public void setRequestedMinimalPostProcessingLocked(boolean on) {
        mRequestedMinimalPostProcessing = on;
    }

    /** Returns the pending requested color mode. */
    public int getRequestedColorModeLocked() {
        return mRequestedColorMode;
    }

    /**
     * Gets the burn-in offset in X.
     */
    public int getDisplayOffsetXLocked() {
        return mDisplayOffsetX;
    }

    /**
     * Gets the burn-in offset in Y.
     */
    public int getDisplayOffsetYLocked() {
        return mDisplayOffsetY;
    }

    /**
     * Sets the burn-in offsets.
     */
    public void setDisplayOffsetsLocked(int x, int y) {
        mDisplayOffsetX = x;
        mDisplayOffsetY = y;
    }

    /**
     * @return {@code true} if display scaling is disabled, or {@code false} if the default scaling
     * mode is used.
     */
    public boolean isDisplayScalingDisabled() {
        return mDisplayScalingDisabled;
    }

    /**
     * Disables scaling for a display.
     *
     * @param disableScaling {@code true} to disable scaling,
     * {@code false} to use the default scaling behavior of the logical display.
     */
    public void setDisplayScalingDisabledLocked(boolean disableScaling) {
        mDisplayScalingDisabled = disableScaling;
    }

    public void setUserDisabledHdrTypes(@NonNull int[] userDisabledHdrTypes) {
        if (mUserDisabledHdrTypes != userDisabledHdrTypes) {
            mUserDisabledHdrTypes = userDisabledHdrTypes;
            mBaseDisplayInfo.userDisabledHdrTypes = userDisabledHdrTypes;
            mInfo.set(null);
        }
    }

    /**
     * Swap the underlying {@link DisplayDevice} with the specified LogicalDisplay.
     *
     * @param targetDisplay The display with which to swap display-devices.
     * @return {@code true} if the displays were swapped, {@code false} otherwise.
     */
    public void swapDisplaysLocked(@NonNull LogicalDisplay targetDisplay) {
        final DisplayDevice oldTargetDevice =
                targetDisplay.setPrimaryDisplayDeviceLocked(mPrimaryDisplayDevice);
        setPrimaryDisplayDeviceLocked(oldTargetDevice);
    }

    /**
     * Sets the primary display device to the specified device.
     *
     * @param device The new device to set.
     * @return The previously set display device.
     */
    public DisplayDevice setPrimaryDisplayDeviceLocked(@Nullable DisplayDevice device) {
        final DisplayDevice old = mPrimaryDisplayDevice;
        mPrimaryDisplayDevice = device;

        // Reset all our display info data
        mPrimaryDisplayDeviceInfo = null;
        mBaseDisplayInfo.copyFrom(EMPTY_DISPLAY_INFO);
        mInfo.set(null);

        return old;
    }

    /**
     * @return {@code true} if the LogicalDisplay is enabled or {@code false}
     * if disabled indicating that the display should be hidden from the rest of the apps and
     * framework.
     */
    public boolean isEnabledLocked() {
        return mIsEnabled;
    }

    /**
     * Sets the display as enabled.
     *
     * @param enabled True if enabled, false otherwise.
     */
    public void setEnabledLocked(boolean enabled) {
        if (enabled != mIsEnabled) {
            mDirty = true;
            mIsEnabled = enabled;
        }
    }

    /**
     * @return {@code true} if the LogicalDisplay is in a transition phase. This is used to indicate
     * that we are getting ready to swap the underlying display-device and the display should be
     * rendered appropriately to reduce jank.
     */
    public boolean isInTransitionLocked() {
        return mIsInTransition;
    }

    /**
     * Sets the transition phase.
     * @param isInTransition True if it display is in transition.
     */
    public void setIsInTransitionLocked(boolean isInTransition) {
        mIsInTransition = isInTransition;
    }

    /**
     * @param brightnessThrottlingDataId The ID of the brightness throttling data that this
     *                                  display should use.
     */
    public void setThermalBrightnessThrottlingDataIdLocked(String brightnessThrottlingDataId) {
        if (!Objects.equals(brightnessThrottlingDataId, mThermalBrightnessThrottlingDataId)) {
            mThermalBrightnessThrottlingDataId = brightnessThrottlingDataId;
            mDirty = true;
        }
    }

    /**
     * @param powerThrottlingDataId The ID of the brightness throttling data that this
     *                                  display should use.
     */
    public void setPowerThrottlingDataIdLocked(String powerThrottlingDataId) {
        if (!Objects.equals(powerThrottlingDataId, mPowerThrottlingDataId)) {
            mPowerThrottlingDataId = powerThrottlingDataId;
            mDirty = true;
        }
    }

    /**
     * Returns powerThrottlingDataId which is the ID of the brightness
     * throttling data that this display should use.
     */
    public String getPowerThrottlingDataIdLocked() {
        return mPowerThrottlingDataId;
    }

    /**
     * Sets the display of which this display is a follower, regarding brightness or other
     * properties. If set to {@link Layout#NO_LEAD_DISPLAY}, this display does not follow any
     * others, and has the potential to be a lead display to others.
     *
     * A display cannot be a leader or follower of itself, and there cannot be cycles.
     * A display cannot be both a leader and a follower, ie, there must not be any chains.
     *
     * @param displayId logical display id
     */
    public void setLeadDisplayLocked(int displayId) {
        if (mDisplayId != mLeadDisplayId && mDisplayId != displayId) {
            mLeadDisplayId = displayId;
        }
    }

    public int getLeadDisplayIdLocked() {
        return mLeadDisplayId;
    }

    /**
     * Sets the name of display group to which the display is assigned.
     */
    public void setDisplayGroupNameLocked(String displayGroupName) {
        mDisplayGroupName = displayGroupName;
    }

    /**
     * Gets the name of display group to which the display is assigned.
     */
    public String getDisplayGroupNameLocked() {
        return mDisplayGroupName;
    }

    public void setDisplayOffloadSessionLocked(DisplayOffloadSessionImpl session) {
        mDisplayOffloadSession = session;
    }

    public DisplayOffloadSessionImpl getDisplayOffloadSessionLocked() {
        return mDisplayOffloadSession;
    }

    public void dumpLocked(PrintWriter pw) {
        pw.println("mDisplayId=" + mDisplayId);
        pw.println("mIsEnabled=" + mIsEnabled);
        pw.println("mIsInTransition=" + mIsInTransition);
        pw.println("mLayerStack=" + mLayerStack);
        pw.println("mPosition=" + mDevicePosition);
        pw.println("mHasContent=" + mHasContent);
        pw.println("mDesiredDisplayModeSpecs={" + mDesiredDisplayModeSpecs + "}");
        pw.println("mRequestedColorMode=" + mRequestedColorMode);
        pw.println("mDisplayOffset=(" + mDisplayOffsetX + ", " + mDisplayOffsetY + ")");
        pw.println("mDisplayScalingDisabled=" + mDisplayScalingDisabled);
        pw.println("mPrimaryDisplayDevice=" + (mPrimaryDisplayDevice != null ?
                mPrimaryDisplayDevice.getNameLocked() : "null"));
        pw.println("mBaseDisplayInfo=" + mBaseDisplayInfo);
        pw.println("mOverrideDisplayInfo=" + mOverrideDisplayInfo);
        pw.println("mRequestedMinimalPostProcessing=" + mRequestedMinimalPostProcessing);
        pw.println("mFrameRateOverrides=" + Arrays.toString(mFrameRateOverrides));
        pw.println("mPendingFrameRateOverrideUids=" + mPendingFrameRateOverrideUids);
        pw.println("mDisplayGroupName=" + mDisplayGroupName);
        pw.println("mThermalBrightnessThrottlingDataId=" + mThermalBrightnessThrottlingDataId);
        pw.println("mLeadDisplayId=" + mLeadDisplayId);
        pw.println("mLayoutLimitedRefreshRate=" + mLayoutLimitedRefreshRate);
        pw.println("mThermalRefreshRateThrottling=" + mThermalRefreshRateThrottling);
        pw.println("mPowerThrottlingDataId=" + mPowerThrottlingDataId);
    }

    @Override
    public String toString() {
        StringWriter sw = new StringWriter();
        dumpLocked(new PrintWriter(sw));
        return sw.toString();
    }
}
