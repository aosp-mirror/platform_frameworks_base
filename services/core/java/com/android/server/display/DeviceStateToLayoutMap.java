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

package com.android.server.display;

import android.content.Context;
import android.hardware.devicestate.DeviceStateManager;
import android.text.TextUtils;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;
import android.view.DisplayAddress;

import com.android.server.display.layout.Layout;

import java.util.Arrays;

/**
 * Mapping from device states into {@link Layout}s. This allows us to map device
 * states into specific layouts for the connected displays; particularly useful for
 * foldable and multi-display devices where the default display and which displays are ON can
 * change depending on the state of the device.
 */
class DeviceStateToLayoutMap {
    private static final String TAG = "DeviceStateToLayoutMap";

    public static final int STATE_DEFAULT = DeviceStateManager.INVALID_DEVICE_STATE;

    private final SparseArray<Layout> mLayoutMap = new SparseArray<>();

    DeviceStateToLayoutMap(Context context) {
        mLayoutMap.append(STATE_DEFAULT, new Layout());
        loadFoldedDisplayConfig(context);
    }

    public void dumpLocked(IndentingPrintWriter ipw) {
        ipw.println("DeviceStateToLayoutMap:");
        ipw.increaseIndent();

        ipw.println("Registered Layouts:");
        for (int i = 0; i < mLayoutMap.size(); i++) {
            ipw.println("state(" + mLayoutMap.keyAt(i) + "): " + mLayoutMap.valueAt(i));
        }
    }

    Layout get(int state) {
        Layout layout = mLayoutMap.get(state);
        if (layout == null) {
            layout = mLayoutMap.get(STATE_DEFAULT);
        }
        return layout;
    }

    private Layout createLayout(int state) {
        if (mLayoutMap.contains(state)) {
            Slog.e(TAG, "Attempted to create a second layout for state " + state);
            return null;
        }

        final Layout layout = new Layout();
        mLayoutMap.append(state, layout);
        return layout;
    }

    /**
     * Loads config.xml-specified folded configurations for foldable devices.
     */
    private void loadFoldedDisplayConfig(Context context) {
        final String[] strDisplayIds = context.getResources().getStringArray(
                com.android.internal.R.array.config_internalFoldedPhysicalDisplayIds);
        if (strDisplayIds.length != 2 || TextUtils.isEmpty(strDisplayIds[0])
                || TextUtils.isEmpty(strDisplayIds[1])) {
            Slog.w(TAG, "Folded display configuration invalid: [" + Arrays.toString(strDisplayIds)
                    + "]");
            return;
        }

        final long[] displayIds;
        try {
            displayIds = new long[] {
                Long.parseLong(strDisplayIds[0]),
                Long.parseLong(strDisplayIds[1])
            };
        } catch (NumberFormatException nfe) {
            Slog.w(TAG, "Folded display config non numerical: " + Arrays.toString(strDisplayIds));
            return;
        }

        final int[] foldedDeviceStates = context.getResources().getIntArray(
                com.android.internal.R.array.config_foldedDeviceStates);
        final int[] unfoldedDeviceStates = context.getResources().getIntArray(
                com.android.internal.R.array.config_unfoldedDeviceStates);
        // Only add folded states if folded state config is not empty
        if (foldedDeviceStates.length == 0 || unfoldedDeviceStates.length == 0) {
            return;
        }

        for (int state : foldedDeviceStates) {
            // Create the folded state layout
            createLayout(state).createDisplayLocked(
                    DisplayAddress.fromPhysicalDisplayId(displayIds[0]), true /*isDefault*/);
        }

        for (int state : unfoldedDeviceStates) {
            // Create the unfolded state layout
            createLayout(state).createDisplayLocked(
                    DisplayAddress.fromPhysicalDisplayId(displayIds[1]), true /*isDefault*/);
        }
    }
}
