/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.os.Environment;
import android.util.Slog;
import android.view.DisplayAddress;

import com.android.server.display.config.DisplayConfiguration;
import com.android.server.display.config.DisplayQuirks;
import com.android.server.display.config.NitsMap;
import com.android.server.display.config.Point;
import com.android.server.display.config.XmlParser;

import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.datatype.DatatypeConfigurationException;

/**
 * Reads and stores display-specific configurations.
 */
public class DisplayDeviceConfig {
    private static final String TAG = "DisplayDeviceConfig";

    public static final float HIGH_BRIGHTNESS_MODE_UNSUPPORTED = Float.NaN;

    public static final String QUIRK_CAN_SET_BRIGHTNESS_VIA_HWC = "canSetBrightnessViaHwc";

    private static final String ETC_DIR = "etc";
    private static final String DISPLAY_CONFIG_DIR = "displayconfig";
    private static final String CONFIG_FILE_FORMAT = "display_%s.xml";
    private static final String PORT_SUFFIX_FORMAT = "port_%d";
    private static final String STABLE_ID_SUFFIX_FORMAT = "id_%d";
    private static final String NO_SUFFIX_FORMAT = "%d";
    private static final long STABLE_FLAG = 1L << 62;

    private float[] mNits;
    private float[] mBrightness;
    private List<String> mQuirks;

    private DisplayDeviceConfig() {
    }

    /**
     * Creates an instance for the specified display.
     * Tries to find a file with identifier in the following priority order:
     * <ol>
     *     <li>physicalDisplayId</li>
     *     <li>physicalDisplayId without a stable flag (old system)</li>
     *     <li>portId</li>
     * </ol>
     * @param physicalDisplayId The display ID for which to load the configuration.
     * @return A configuration instance for the specified display.
     */
    public static DisplayDeviceConfig create(long physicalDisplayId) {
        DisplayDeviceConfig config;

        config = loadConfigFromDirectory(Environment.getProductDirectory(), physicalDisplayId);
        if (config != null) {
            return config;
        }

        config = loadConfigFromDirectory(Environment.getVendorDirectory(), physicalDisplayId);
        if (config != null) {
            return config;
        }

        return null;
    }

    private static DisplayDeviceConfig loadConfigFromDirectory(
            File baseDirectory, long physicalDisplayId) {
        DisplayDeviceConfig config;
        // Create config using filename from physical ID (including "stable" bit).
        config = getConfigFromSuffix(baseDirectory, STABLE_ID_SUFFIX_FORMAT, physicalDisplayId);
        if (config != null) {
            return config;
        }

        // Create config using filename from physical ID (excluding "stable" bit).
        final long withoutStableFlag = physicalDisplayId & ~STABLE_FLAG;
        config = getConfigFromSuffix(baseDirectory, NO_SUFFIX_FORMAT, withoutStableFlag);
        if (config != null) {
            return config;
        }

        // Create config using filename from port ID.
        final DisplayAddress.Physical physicalAddress =
                DisplayAddress.fromPhysicalDisplayId(physicalDisplayId);
        int port = physicalAddress.getPort();
        config = getConfigFromSuffix(baseDirectory, PORT_SUFFIX_FORMAT, port);
        if (config != null) {
            return config;
        }

        // None of these files exist.
        return null;

    }

    /**
     * Return the brightness mapping nits array if one is defined in the configuration file.
     *
     * @return The brightness mapping nits array.
     */
    public float[] getNits() {
        return mNits;
    }

    /**
     * Return the brightness mapping value array if one is defined in the configuration file.
     *
     * @return The brightness mapping value array.
     */
    public float[] getBrightness() {
        return mBrightness;
    }

    /**
     * @param quirkValue The quirk to test.
     * @return {@code true} if the specified quirk is present in this configuration,
     * {@code false} otherwise.
     */
    public boolean hasQuirk(String quirkValue) {
        return mQuirks != null && mQuirks.contains(quirkValue);
    }

    @Override
    public String toString() {
        String str = "DisplayDeviceConfig{"
                + "mBrightness=" + Arrays.toString(mBrightness)
                + ", mNits=" + Arrays.toString(mNits)
                + ", mQuirks=" + mQuirks
                + "}";
        return str;
    }

    private static DisplayDeviceConfig getConfigFromSuffix(File baseDirectory,
            String suffixFormat, long idNumber) {

        final String suffix = String.format(suffixFormat, idNumber);
        final String filename = String.format(CONFIG_FILE_FORMAT, suffix);
        final File filePath = Environment.buildPath(
                baseDirectory, ETC_DIR, DISPLAY_CONFIG_DIR, filename);

        if (filePath.exists()) {
            final DisplayDeviceConfig config = new DisplayDeviceConfig();
            config.initFromFile(filePath);
            return config;
        }
        return null;
    }

    private void initFromFile(File configFile) {
        if (!configFile.exists()) {
            // Display configuration files aren't required to exist.
            return;
        }

        if (!configFile.isFile()) {
            Slog.e(TAG, "Display configuration is not a file: " + configFile + ", skipping");
            return;
        }

        try (InputStream in = new BufferedInputStream(new FileInputStream(configFile))) {
            final DisplayConfiguration config = XmlParser.read(in);
            loadBrightnessMap(config);
            loadQuirks(config);
        } catch (IOException | DatatypeConfigurationException | XmlPullParserException e) {
            Slog.e(TAG, "Encountered an error while reading/parsing display config file: "
                    + configFile, e);
        }
    }

    private void loadBrightnessMap(DisplayConfiguration config) {
        final NitsMap map = config.getScreenBrightnessMap();
        final List<Point> points = map.getPoint();
        final int size = points.size();

        float[] nits = new float[size];
        float[] backlight = new float[size];

        int i = 0;
        for (Point point : points) {
            nits[i] = point.getNits().floatValue();
            backlight[i] = point.getValue().floatValue();
            if (i > 0) {
                if (nits[i] < nits[i - 1]) {
                    Slog.e(TAG, "screenBrightnessMap must be non-decreasing, ignoring rest "
                            + " of configuration. Nits: " + nits[i] + " < " + nits[i - 1]);
                    return;
                }

                if (backlight[i] < backlight[i - 1]) {
                    Slog.e(TAG, "screenBrightnessMap must be non-decreasing, ignoring rest "
                            + " of configuration. Value: " + backlight[i] + " < "
                            + backlight[i - 1]);
                    return;
                }
            }
            ++i;
        }
        mNits = nits;
        mBrightness = backlight;
    }

    private void loadQuirks(DisplayConfiguration config) {
        final DisplayQuirks quirks = config.getQuirks();
        if (quirks != null) {
            mQuirks = new ArrayList<>(quirks.getQuirk());
        }
    }
}
