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

package com.android.server.display.layout;

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.display.layout.Layout.Display.POSITION_UNKNOWN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Slog;
import android.view.DisplayAddress;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Holds a collection of {@link Display}s. A single instance of this class describes
 * how to organize one or more DisplayDevices into LogicalDisplays for a particular device
 * state. For example, there may be one instance of this class to describe display layout when
 * a foldable device is folded, and a second instance for when the device is unfolded.
 */
public class Layout {
    public static final String DEFAULT_DISPLAY_GROUP_NAME = "";

    private static final String TAG = "Layout";

    // Lead display Id is set to this if this is not a follower display, and therefore
    // has no lead.
    public static final int NO_LEAD_DISPLAY = -1;

    private final List<Display> mDisplays = new ArrayList<>(2);

    @Override
    public String toString() {
        return mDisplays.toString();
    }

    @Override
    public boolean equals(Object obj) {

        if (!(obj instanceof  Layout)) {
            return false;
        }

        Layout otherLayout = (Layout) obj;
        return this.mDisplays.equals(otherLayout.mDisplays);
    }

    @Override
    public int hashCode() {
        return mDisplays.hashCode();
    }

    /**
     * Creates the default 1:1 LogicalDisplay mapping for the specified DisplayDevice.
     *
     * @param address Address of the device.
     * @param idProducer Produces the logical display id.
     */
    public void createDefaultDisplayLocked(@NonNull DisplayAddress address,
            DisplayIdProducer idProducer) {
        createDisplayLocked(address, /* isDefault= */ true, /* isEnabled= */ true,
                DEFAULT_DISPLAY_GROUP_NAME, idProducer, POSITION_UNKNOWN,
                NO_LEAD_DISPLAY, /* brightnessThrottlingMapId= */ null,
                /* refreshRateZoneId= */ null, /* refreshRateThermalThrottlingMapId= */ null);
    }

    /**
     * Creates a simple 1:1 LogicalDisplay mapping for the specified DisplayDevice.
     *
     * @param address Address of the device.
     * @param isDefault Indicates if the device is meant to be the default display.
     * @param isEnabled Indicates if this display is usable and can be switched on
     * @param displayGroupName Name of the display group to which the display is assigned.
     * @param idProducer Produces the logical display id.
     * @param position Indicates the position this display is facing in this layout.
     * @param leadDisplayId Display that this one follows (-1 if none).
     * @param brightnessThrottlingMapId Name of which brightness throttling policy should be used.
     * @param refreshRateZoneId Layout limited refresh rate zone name.
     * @param refreshRateThermalThrottlingMapId Name of which refresh rate throttling
     *                                          policy should be used.

     * @exception IllegalArgumentException When a default display owns a display group other than
     *            DEFAULT_DISPLAY_GROUP.
     */
    public void createDisplayLocked(
            @NonNull DisplayAddress address, boolean isDefault, boolean isEnabled,
            String displayGroupName, DisplayIdProducer idProducer, int position, int leadDisplayId,
            String brightnessThrottlingMapId, @Nullable String refreshRateZoneId,
            @Nullable String refreshRateThermalThrottlingMapId) {
        if (contains(address)) {
            Slog.w(TAG, "Attempting to add second definition for display-device: " + address);
            return;
        }

        // See if we're dealing with the "default" display
        if (isDefault && getById(DEFAULT_DISPLAY) != null) {
            Slog.w(TAG, "Ignoring attempt to add a second default display: " + address);
            return;
        }

        // Assign a logical display ID and create the new display.
        // Note that the logical display ID is saved into the layout, so when switching between
        // different layouts, a logical display can be destroyed and later recreated with the
        // same logical display ID.
        if (displayGroupName == null) {
            displayGroupName = DEFAULT_DISPLAY_GROUP_NAME;
        }
        if (isDefault && !displayGroupName.equals(DEFAULT_DISPLAY_GROUP_NAME)) {
            throw new IllegalArgumentException("Default display should own DEFAULT_DISPLAY_GROUP");
        }
        final int logicalDisplayId = idProducer.getId(isDefault);
        leadDisplayId = isDefault ? NO_LEAD_DISPLAY : leadDisplayId;

        final Display display = new Display(address, logicalDisplayId, isEnabled, displayGroupName,
                brightnessThrottlingMapId, position, leadDisplayId, refreshRateZoneId,
                refreshRateThermalThrottlingMapId);

        mDisplays.add(display);
    }

    /**
     * @param id The ID of the display to remove.
     */
    public void removeDisplayLocked(int id) {
        Display display = getById(id);
        if (display != null) {
            mDisplays.remove(display);
        }
    }

    /**
     * @param address The address to check.
     *
     * @return True if the specified address is used in this layout.
     */
    public boolean contains(@NonNull DisplayAddress address) {
        final int size = mDisplays.size();
        for (int i = 0; i < size; i++) {
            if (address.equals(mDisplays.get(i).getAddress())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param id The display ID to check.
     *
     * @return The display corresponding to the specified display ID.
     */
    @Nullable
    public Display getById(int id) {
        for (int i = 0; i < mDisplays.size(); i++) {
            Display display = mDisplays.get(i);
            if (id == display.getLogicalDisplayId()) {
                return display;
            }
        }
        return null;
    }

    /**
     * @param address The display address to check.
     *
     * @return The display corresponding to the specified address.
     */
    @Nullable
    public Display getByAddress(@NonNull DisplayAddress address) {
        for (int i = 0; i < mDisplays.size(); i++) {
            Display display = mDisplays.get(i);
            if (address.equals(display.getAddress())) {
                return display;
            }
        }
        return null;
    }

    /**
     * @param index The index of the display to return.
     *
     * @return the display at the specified index.
     */
    public Display getAt(int index) {
        return mDisplays.get(index);
    }

    /**
     * @return The number of displays defined for this layout.
     */
    public int size() {
        return mDisplays.size();
    }

    /**
     * Describes how a {@link LogicalDisplay} is built from {@link DisplayDevice}s.
     */
    public static class Display {
        public static final int POSITION_UNKNOWN = -1;
        public static final int POSITION_FRONT = 0;
        public static final int POSITION_REAR = 1;

        // Address of the display device to map to this display.
        private final DisplayAddress mAddress;

        // Logical Display ID to apply to this display.
        private final int mLogicalDisplayId;

        // Indicates if this display is usable and can be switched on
        private final boolean mIsEnabled;

        // Name of display group to which the display is assigned
        private final String mDisplayGroupName;

        // The direction the display faces
        // {@link DeviceStateToLayoutMap.POSITION_FRONT} or
        // {@link DeviceStateToLayoutMap.POSITION_REAR}.
        // {@link DeviceStateToLayoutMap.POSITION_UNKNOWN} is unspecified.
        private final int mPosition;

        // The ID of the thermal brightness throttling map that should be used. This can change
        // e.g. in concurrent displays mode in which a stricter brightness throttling policy might
        // need to be used.
        @Nullable
        private final String mThermalBrightnessThrottlingMapId;

        // The ID of the lead display that this display will follow in a layout. -1 means no lead.
        private final int mLeadDisplayId;

        // Refresh rate zone id for specific layout
        @Nullable
        private final String mRefreshRateZoneId;

        @Nullable
        private final String mThermalRefreshRateThrottlingMapId;

        private Display(@NonNull DisplayAddress address, int logicalDisplayId, boolean isEnabled,
                @NonNull String displayGroupName, String brightnessThrottlingMapId, int position,
                int leadDisplayId, @Nullable String refreshRateZoneId,
                @Nullable String refreshRateThermalThrottlingMapId) {
            mAddress = address;
            mLogicalDisplayId = logicalDisplayId;
            mIsEnabled = isEnabled;
            mDisplayGroupName = displayGroupName;
            mPosition = position;
            mThermalBrightnessThrottlingMapId = brightnessThrottlingMapId;
            mRefreshRateZoneId = refreshRateZoneId;
            mThermalRefreshRateThrottlingMapId = refreshRateThermalThrottlingMapId;
            mLeadDisplayId = leadDisplayId;
        }

        @Override
        public String toString() {
            return "{"
                    + "dispId: " + mLogicalDisplayId
                    + "(" + (mIsEnabled ? "ON" : "OFF") + ")"
                    + ", displayGroupName: " + mDisplayGroupName
                    + ", addr: " + mAddress
                    +  ((mPosition == POSITION_UNKNOWN) ? "" : ", position: " + mPosition)
                    + ", mThermalBrightnessThrottlingMapId: " + mThermalBrightnessThrottlingMapId
                    + ", mRefreshRateZoneId: " + mRefreshRateZoneId
                    + ", mLeadDisplayId: " + mLeadDisplayId
                    + ", mThermalRefreshRateThrottlingMapId: " + mThermalRefreshRateThrottlingMapId
                    + "}";
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Display)) {
                return false;
            }

            Display otherDisplay = (Display) obj;

            return otherDisplay.mIsEnabled == this.mIsEnabled
                    && otherDisplay.mPosition == this.mPosition
                    && otherDisplay.mLogicalDisplayId == this.mLogicalDisplayId
                    && this.mDisplayGroupName.equals(otherDisplay.mDisplayGroupName)
                    && this.mAddress.equals(otherDisplay.mAddress)
                    && Objects.equals(mThermalBrightnessThrottlingMapId,
                    otherDisplay.mThermalBrightnessThrottlingMapId)
                    && Objects.equals(otherDisplay.mRefreshRateZoneId, this.mRefreshRateZoneId)
                    && this.mLeadDisplayId == otherDisplay.mLeadDisplayId
                    && Objects.equals(mThermalRefreshRateThrottlingMapId,
                    otherDisplay.mThermalRefreshRateThrottlingMapId);
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 31 * result + Boolean.hashCode(mIsEnabled);
            result = 31 * result + mPosition;
            result = 31 * result + mLogicalDisplayId;
            result = 31 * result + mDisplayGroupName.hashCode();
            result = 31 * result + mAddress.hashCode();
            result = 31 * result + mThermalBrightnessThrottlingMapId.hashCode();
            result = 31 * result + Objects.hashCode(mRefreshRateZoneId);
            result = 31 * result + mLeadDisplayId;
            result = 31 * result + Objects.hashCode(mThermalRefreshRateThrottlingMapId);
            return result;
        }

        public DisplayAddress getAddress() {
            return mAddress;
        }

        public int getLogicalDisplayId() {
            return mLogicalDisplayId;
        }

        public boolean isEnabled() {
            return mIsEnabled;
        }

        public String getDisplayGroupName() {
            return mDisplayGroupName;
        }

        @Nullable
        public String getRefreshRateZoneId() {
            return mRefreshRateZoneId;
        }

        /**
         * Gets the id of the thermal brightness throttling map that should be used.
         * @return The ID of the thermal brightness throttling map that this display should use,
         *         null if unspecified, will fall back to default.
         */
        public String getThermalBrightnessThrottlingMapId() {
            return mThermalBrightnessThrottlingMapId;
        }

        /**
         * @return the position that this display is facing.
         */
        public int getPosition() {
            return mPosition;
        }

        /**
         * @return logical displayId of the display that this one follows.
         */
        public int getLeadDisplayId() {
            return mLeadDisplayId;
        }

        public String getRefreshRateThermalThrottlingMapId() {
            return mThermalRefreshRateThrottlingMapId;
        }
    }
}
