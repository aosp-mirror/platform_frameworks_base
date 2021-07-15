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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Slog;
import android.view.DisplayAddress;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds a collection of {@link Display}s. A single instance of this class describes
 * how to organize one or more DisplayDevices into LogicalDisplays for a particular device
 * state. For example, there may be one instance of this class to describe display layout when
 * a foldable device is folded, and a second instance for when the device is unfolded.
 */
public class Layout {
    private static final String TAG = "Layout";
    private static int sNextNonDefaultDisplayId = DEFAULT_DISPLAY + 1;

    private final List<Display> mDisplays = new ArrayList<>(2);

    /**
     *  @return The default display ID, or a new unique one to use.
     */
    public static int assignDisplayIdLocked(boolean isDefault) {
        return isDefault ? DEFAULT_DISPLAY : sNextNonDefaultDisplayId++;
    }

    @Override
    public String toString() {
        return mDisplays.toString();
    }

    /**
     * Creates a simple 1:1 LogicalDisplay mapping for the specified DisplayDevice.
     *
     * @param address Address of the device.
     * @param isDefault Indicates if the device is meant to be the default display.
     * @return The new layout.
     */
    public Display createDisplayLocked(
            @NonNull DisplayAddress address, boolean isDefault, boolean isEnabled) {
        if (contains(address)) {
            Slog.w(TAG, "Attempting to add second definition for display-device: " + address);
            return null;
        }

        // See if we're dealing with the "default" display
        if (isDefault && getById(DEFAULT_DISPLAY) != null) {
            Slog.w(TAG, "Ignoring attempt to add a second default display: " + address);
            isDefault = false;
        }

        // Assign a logical display ID and create the new display.
        // Note that the logical display ID is saved into the layout, so when switching between
        // different layouts, a logical display can be destroyed and later recreated with the
        // same logical display ID.
        final int logicalDisplayId = assignDisplayIdLocked(isDefault);
        final Display layout = new Display(address, logicalDisplayId, isEnabled);

        mDisplays.add(layout);
        return layout;
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
        // Address of the display device to map to this display.
        private final DisplayAddress mAddress;

        // Logical Display ID to apply to this display.
        private final int mLogicalDisplayId;

        // Indicates that this display is not usable and should remain off.
        private final boolean mIsEnabled;

        Display(@NonNull DisplayAddress address, int logicalDisplayId, boolean isEnabled) {
            mAddress = address;
            mLogicalDisplayId = logicalDisplayId;
            mIsEnabled = isEnabled;
        }

        @Override
        public String toString() {
            return "{addr: " + mAddress + ", dispId: " + mLogicalDisplayId
                    + "(" + (mIsEnabled ? "ON" : "OFF") + ")}";
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
    }
}
