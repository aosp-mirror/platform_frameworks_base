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

import android.hardware.devicestate.DeviceStateManager;
import android.os.Environment;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;
import android.view.DisplayAddress;

import com.android.server.display.config.layout.Layouts;
import com.android.server.display.config.layout.XmlParser;
import com.android.server.display.layout.Layout;

import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.xml.datatype.DatatypeConfigurationException;

/**
 * Mapping from device states into {@link Layout}s. This allows us to map device
 * states into specific layouts for the connected displays; particularly useful for
 * foldable and multi-display devices where the default display and which displays are ON can
 * change depending on the state of the device.
 */
class DeviceStateToLayoutMap {
    private static final String TAG = "DeviceStateToLayoutMap";

    public static final int STATE_DEFAULT = DeviceStateManager.INVALID_DEVICE_STATE;

    private static final String CONFIG_FILE_PATH =
            "etc/displayconfig/display_layout_configuration.xml";

    private final SparseArray<Layout> mLayoutMap = new SparseArray<>();

    DeviceStateToLayoutMap() {
        loadLayoutsFromConfig();
        createLayout(STATE_DEFAULT);
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

    int size() {
        return mLayoutMap.size();
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
     * Reads display-layout-configuration files to get the layouts to use for this device.
     */
    private void loadLayoutsFromConfig() {
        final File configFile = Environment.buildPath(
                Environment.getVendorDirectory(), CONFIG_FILE_PATH);

        if (!configFile.exists()) {
            return;
        }

        Slog.i(TAG, "Loading display layouts from " + configFile);
        try (InputStream in = new BufferedInputStream(new FileInputStream(configFile))) {
            final Layouts layouts = XmlParser.read(in);
            if (layouts == null) {
                Slog.i(TAG, "Display layout config not found: " + configFile);
                return;
            }
            for (com.android.server.display.config.layout.Layout l : layouts.getLayout()) {
                final int state = l.getState().intValue();
                final Layout layout = createLayout(state);
                for (com.android.server.display.config.layout.Display d: l.getDisplay()) {
                    layout.createDisplayLocked(
                            DisplayAddress.fromPhysicalDisplayId(d.getAddress().longValue()),
                            d.isDefaultDisplay(),
                            d.isEnabled());
                }
            }
        } catch (IOException | DatatypeConfigurationException | XmlPullParserException e) {
            Slog.e(TAG, "Encountered an error while reading/parsing display layout config file: "
                    + configFile, e);
        }
    }
}
