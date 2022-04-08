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

import android.annotation.Nullable;
import android.graphics.Point;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.WifiDisplay;
import android.os.Handler;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseLongArray;
import android.util.TimeUtils;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.XmlUtils;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Manages persistent state recorded by the display manager service as an XML file.
 * Caller must acquire lock on the data store before accessing it.
 *
 * File format:
 * <code>
 * &lt;display-manager-state>
 *   &lt;remembered-wifi-displays>
 *     &lt;wifi-display deviceAddress="00:00:00:00:00:00" deviceName="XXXX" deviceAlias="YYYY" />
 *   &lt;remembered-wifi-displays>
 *   &lt;display-states>
 *      &lt;display unique-id="XXXXXXX">
 *          &lt;color-mode>0&lt;/color-mode>
 *          &lt;brightness-value>0&lt;/brightness-value>
 *          &lt;brightness-configurations>
 *              &lt;brightness-configuration user-serial="0" package-name="com.example"
 *              timestamp="1234">
 *                  &lt;brightness-curve description="some text">
 *                      &lt;brightness-point lux="0" nits="13.25"/>
 *                      &lt;brightness-point lux="20" nits="35.94"/>
 *                  &lt;/brightness-curve>
 *              &lt;/brightness-configuration>
 *          &lt;/brightness-configurations>
 *      &lt;/display>
 *  &lt;/display-states>
 *  &lt;stable-device-values>
 *      &lt;stable-display-height>1920&lt;/stable-display-height>
 *      &lt;stable-display-width>1080&lt;/stable-display-width>
 *  &lt;/stable-device-values>
 *  &lt;brightness-configurations>
 *      &lt;brightness-configuration user-serial="0" package-name="com.example" timestamp="1234">
 *          &lt;brightness-curve description="some text">
 *              &lt;brightness-point lux="0" nits="13.25"/>
 *              &lt;brightness-point lux="20" nits="35.94"/>
 *          &lt;/brightness-curve>
 *      &lt;/brightness-configuration>
 *  &lt;/brightness-configurations>
 * &lt;/display-manager-state>
 * </code>
 *
 * TODO: refactor this to extract common code shared with the input manager's data store
 */
final class PersistentDataStore {
    static final String TAG = "DisplayManager.PersistentDataStore";

    private static final String TAG_DISPLAY_MANAGER_STATE = "display-manager-state";

    private static final String TAG_REMEMBERED_WIFI_DISPLAYS = "remembered-wifi-displays";
    private static final String TAG_WIFI_DISPLAY = "wifi-display";
    private static final String ATTR_DEVICE_ADDRESS = "deviceAddress";
    private static final String ATTR_DEVICE_NAME = "deviceName";
    private static final String ATTR_DEVICE_ALIAS = "deviceAlias";

    private static final String TAG_DISPLAY_STATES = "display-states";
    private static final String TAG_DISPLAY = "display";
    private static final String TAG_COLOR_MODE = "color-mode";
    private static final String TAG_BRIGHTNESS_VALUE = "brightness-value";
    private static final String ATTR_UNIQUE_ID = "unique-id";

    private static final String TAG_STABLE_DEVICE_VALUES = "stable-device-values";
    private static final String TAG_STABLE_DISPLAY_HEIGHT = "stable-display-height";
    private static final String TAG_STABLE_DISPLAY_WIDTH = "stable-display-width";

    private static final String TAG_BRIGHTNESS_CONFIGURATIONS = "brightness-configurations";
    private static final String TAG_BRIGHTNESS_CONFIGURATION = "brightness-configuration";
    private static final String ATTR_USER_SERIAL = "user-serial";
    private static final String ATTR_PACKAGE_NAME = "package-name";
    private static final String ATTR_TIME_STAMP = "timestamp";

    // Remembered Wifi display devices.
    private ArrayList<WifiDisplay> mRememberedWifiDisplays = new ArrayList<WifiDisplay>();

    // Display state by unique id.
    private final HashMap<String, DisplayState> mDisplayStates =
            new HashMap<String, DisplayState>();

    // Display values which should be stable across the device's lifetime.
    private final StableDeviceValues mStableDeviceValues = new StableDeviceValues();

    // Brightness configuration by user
    private BrightnessConfigurations mGlobalBrightnessConfigurations =
            new BrightnessConfigurations();

    // True if the data has been loaded.
    private boolean mLoaded;

    // True if there are changes to be saved.
    private boolean mDirty;

    // The interface for methods which should be replaced by the test harness.
    private Injector mInjector;

    private final Handler mHandler;
    private final Object mFileAccessLock = new Object();

    public PersistentDataStore() {
        this(new Injector());
    }

    @VisibleForTesting
    PersistentDataStore(Injector injector) {
        this(injector, BackgroundThread.getHandler());
    }

    @VisibleForTesting
    PersistentDataStore(Injector injector, Handler handler) {
        mInjector = injector;
        mHandler = handler;
    }

    public void saveIfNeeded() {
        if (mDirty) {
            save();
            mDirty = false;
        }
    }

    public WifiDisplay getRememberedWifiDisplay(String deviceAddress) {
        loadIfNeeded();
        int index = findRememberedWifiDisplay(deviceAddress);
        if (index >= 0) {
            return mRememberedWifiDisplays.get(index);
        }
        return null;
    }

    public WifiDisplay[] getRememberedWifiDisplays() {
        loadIfNeeded();
        return mRememberedWifiDisplays.toArray(new WifiDisplay[mRememberedWifiDisplays.size()]);
    }

    public WifiDisplay applyWifiDisplayAlias(WifiDisplay display) {
        if (display != null) {
            loadIfNeeded();

            String alias = null;
            int index = findRememberedWifiDisplay(display.getDeviceAddress());
            if (index >= 0) {
                alias = mRememberedWifiDisplays.get(index).getDeviceAlias();
            }
            if (!Objects.equals(display.getDeviceAlias(), alias)) {
                return new WifiDisplay(display.getDeviceAddress(), display.getDeviceName(),
                        alias, display.isAvailable(), display.canConnect(), display.isRemembered());
            }
        }
        return display;
    }

    public WifiDisplay[] applyWifiDisplayAliases(WifiDisplay[] displays) {
        WifiDisplay[] results = displays;
        if (results != null) {
            int count = displays.length;
            for (int i = 0; i < count; i++) {
                WifiDisplay result = applyWifiDisplayAlias(displays[i]);
                if (result != displays[i]) {
                    if (results == displays) {
                        results = new WifiDisplay[count];
                        System.arraycopy(displays, 0, results, 0, count);
                    }
                    results[i] = result;
                }
            }
        }
        return results;
    }

    public boolean rememberWifiDisplay(WifiDisplay display) {
        loadIfNeeded();

        int index = findRememberedWifiDisplay(display.getDeviceAddress());
        if (index >= 0) {
            WifiDisplay other = mRememberedWifiDisplays.get(index);
            if (other.equals(display)) {
                return false; // already remembered without change
            }
            mRememberedWifiDisplays.set(index, display);
        } else {
            mRememberedWifiDisplays.add(display);
        }
        setDirty();
        return true;
    }

    public boolean forgetWifiDisplay(String deviceAddress) {
        loadIfNeeded();
        int index = findRememberedWifiDisplay(deviceAddress);
        if (index >= 0) {
            mRememberedWifiDisplays.remove(index);
            setDirty();
            return true;
        }
        return false;
    }

    private int findRememberedWifiDisplay(String deviceAddress) {
        int count = mRememberedWifiDisplays.size();
        for (int i = 0; i < count; i++) {
            if (mRememberedWifiDisplays.get(i).getDeviceAddress().equals(deviceAddress)) {
                return i;
            }
        }
        return -1;
    }

    public int getColorMode(DisplayDevice device) {
        if (!device.hasStableUniqueId()) {
            return Display.COLOR_MODE_INVALID;
        }
        DisplayState state = getDisplayState(device.getUniqueId(), false);
        if (state == null) {
            return Display.COLOR_MODE_INVALID;
        }
        return state.getColorMode();
    }

    public boolean setColorMode(DisplayDevice device, int colorMode) {
        if (!device.hasStableUniqueId()) {
            return false;
        }
        DisplayState state = getDisplayState(device.getUniqueId(), true);
        if (state.setColorMode(colorMode)) {
            setDirty();
            return true;
        }
        return false;
    }

    public float getBrightness(DisplayDevice device) {
        if (device == null || !device.hasStableUniqueId()) {
            return Float.NaN;
        }
        final DisplayState state = getDisplayState(device.getUniqueId(), false);
        if (state == null) {
            return Float.NaN;
        }
        return state.getBrightness();
    }

    public boolean setBrightness(DisplayDevice displayDevice, float brightness) {
        final String displayDeviceUniqueId = displayDevice.getUniqueId();
        if (!displayDevice.hasStableUniqueId() || displayDeviceUniqueId == null) {
            return false;
        }
        final DisplayState state = getDisplayState(displayDeviceUniqueId, true);
        if (state.setBrightness(brightness)) {
            setDirty();
            return true;
        }
        return false;
    }

    public boolean setUserPreferredRefreshRate(DisplayDevice displayDevice, float refreshRate) {
        final String displayDeviceUniqueId = displayDevice.getUniqueId();
        if (!displayDevice.hasStableUniqueId() || displayDeviceUniqueId == null) {
            return false;
        }
        DisplayState state = getDisplayState(displayDevice.getUniqueId(), true);
        if (state.setRefreshRate(refreshRate)) {
            setDirty();
            return true;
        }
        return false;
    }

    public float getUserPreferredRefreshRate(DisplayDevice device) {
        if (device == null || !device.hasStableUniqueId()) {
            return Float.NaN;
        }
        final DisplayState state = getDisplayState(device.getUniqueId(), false);
        if (state == null) {
            return Float.NaN;
        }
        return state.getRefreshRate();
    }

    public boolean setUserPreferredResolution(DisplayDevice displayDevice, int width, int height) {
        final String displayDeviceUniqueId = displayDevice.getUniqueId();
        if (!displayDevice.hasStableUniqueId() || displayDeviceUniqueId == null) {
            return false;
        }
        DisplayState state = getDisplayState(displayDevice.getUniqueId(), true);
        if (state.setResolution(width, height)) {
            setDirty();
            return true;
        }
        return false;
    }

    public Point getUserPreferredResolution(DisplayDevice displayDevice) {
        if (displayDevice == null || !displayDevice.hasStableUniqueId()) {
            return null;
        }
        final DisplayState state = getDisplayState(displayDevice.getUniqueId(), false);
        if (state == null) {
            return null;
        }
        return state.getResolution();
    }

    public Point getStableDisplaySize() {
        loadIfNeeded();
        return mStableDeviceValues.getDisplaySize();
    }

    public void setStableDisplaySize(Point size) {
        loadIfNeeded();
        if (mStableDeviceValues.setDisplaySize(size)) {
            setDirty();
        }
    }

    // Used for testing & reset
    public void setBrightnessConfigurationForUser(BrightnessConfiguration c, int userSerial,
            @Nullable String packageName) {
        loadIfNeeded();
        if (mGlobalBrightnessConfigurations.setBrightnessConfigurationForUser(c, userSerial,
                packageName)) {

            setDirty();
        }
    }

    public boolean setBrightnessConfigurationForDisplayLocked(BrightnessConfiguration configuration,
            DisplayDevice device, int userSerial, String packageName) {
        if (device == null || !device.hasStableUniqueId()) {
            return false;
        }
        DisplayState state = getDisplayState(device.getUniqueId(), /*createIfAbsent*/ true);
        if (state.setBrightnessConfiguration(configuration, userSerial, packageName)) {
            setDirty();
            return true;
        }
        return false;
    }


    public BrightnessConfiguration getBrightnessConfigurationForDisplayLocked(
            String uniqueDisplayId, int userSerial) {
        loadIfNeeded();
        DisplayState state = mDisplayStates.get(uniqueDisplayId);
        if (state != null) {
            return state.getBrightnessConfiguration(userSerial);
        }
        return null;
    }

    public BrightnessConfiguration getBrightnessConfiguration(int userSerial) {
        loadIfNeeded();
        return mGlobalBrightnessConfigurations.getBrightnessConfiguration(userSerial);
    }

    private DisplayState getDisplayState(String uniqueId, boolean createIfAbsent) {
        loadIfNeeded();
        DisplayState state = mDisplayStates.get(uniqueId);
        if (state == null && createIfAbsent) {
            state = new DisplayState();
            mDisplayStates.put(uniqueId, state);
            setDirty();
        }
        return state;
    }

    public void loadIfNeeded() {
        if (!mLoaded) {
            load();
            mLoaded = true;
        }
    }

    private void setDirty() {
        mDirty = true;
    }

    private void clearState() {
        mRememberedWifiDisplays.clear();
    }

    private void load() {
        synchronized (mFileAccessLock) {
            clearState();

            final InputStream is;
            try {
                is = mInjector.openRead();
            } catch (FileNotFoundException ex) {
                return;
            }

            TypedXmlPullParser parser;
            try {
                parser = Xml.resolvePullParser(is);
                loadFromXml(parser);
            } catch (IOException ex) {
                Slog.w(TAG, "Failed to load display manager persistent store data.", ex);
                clearState();
            } catch (XmlPullParserException ex) {
                Slog.w(TAG, "Failed to load display manager persistent store data.", ex);
                clearState();
            } finally {
                IoUtils.closeQuietly(is);
            }
        }
    }

    private void save() {
        final ByteArrayOutputStream os;
        try {
            os = new ByteArrayOutputStream();

            TypedXmlSerializer serializer = Xml.resolveSerializer(os);
            saveToXml(serializer);

            mHandler.removeCallbacksAndMessages(/* token */ null);
            mHandler.post(() -> {
                synchronized (mFileAccessLock) {
                    OutputStream fileOutput = null;
                    try {
                        fileOutput = mInjector.startWrite();
                        os.writeTo(fileOutput);
                        fileOutput.flush();
                    } catch (IOException ex) {
                        Slog.w(TAG, "Failed to save display manager persistent store data.", ex);
                    } finally {
                        if (fileOutput != null) {
                            mInjector.finishWrite(fileOutput, true);
                        }
                    }
                }
            });

            serializer.flush();
        } catch (IOException ex) {
            Slog.w(TAG, "Failed to process the XML serializer.", ex);
        }
    }

    private void loadFromXml(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        XmlUtils.beginDocument(parser, TAG_DISPLAY_MANAGER_STATE);
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (parser.getName().equals(TAG_REMEMBERED_WIFI_DISPLAYS)) {
                loadRememberedWifiDisplaysFromXml(parser);
            }
            if (parser.getName().equals(TAG_DISPLAY_STATES)) {
                loadDisplaysFromXml(parser);
            }
            if (parser.getName().equals(TAG_STABLE_DEVICE_VALUES)) {
                mStableDeviceValues.loadFromXml(parser);
            }
            if (parser.getName().equals(TAG_BRIGHTNESS_CONFIGURATIONS)) {
                mGlobalBrightnessConfigurations.loadFromXml(parser);
            }
        }
    }

    private void loadRememberedWifiDisplaysFromXml(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (parser.getName().equals(TAG_WIFI_DISPLAY)) {
                String deviceAddress = parser.getAttributeValue(null, ATTR_DEVICE_ADDRESS);
                String deviceName = parser.getAttributeValue(null, ATTR_DEVICE_NAME);
                String deviceAlias = parser.getAttributeValue(null, ATTR_DEVICE_ALIAS);
                if (deviceAddress == null || deviceName == null) {
                    throw new XmlPullParserException(
                            "Missing deviceAddress or deviceName attribute on wifi-display.");
                }
                if (findRememberedWifiDisplay(deviceAddress) >= 0) {
                    throw new XmlPullParserException(
                            "Found duplicate wifi display device address.");
                }

                mRememberedWifiDisplays.add(
                        new WifiDisplay(deviceAddress, deviceName, deviceAlias,
                                false, false, false));
            }
        }
    }

    private void loadDisplaysFromXml(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (parser.getName().equals(TAG_DISPLAY)) {
                String uniqueId = parser.getAttributeValue(null, ATTR_UNIQUE_ID);
                if (uniqueId == null) {
                    throw new XmlPullParserException(
                            "Missing unique-id attribute on display.");
                }
                if (mDisplayStates.containsKey(uniqueId)) {
                    throw new XmlPullParserException("Found duplicate display.");
                }

                DisplayState state = new DisplayState();
                state.loadFromXml(parser);
                mDisplayStates.put(uniqueId, state);
            }
        }
    }

    private void saveToXml(TypedXmlSerializer serializer) throws IOException {
        serializer.startDocument(null, true);
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        serializer.startTag(null, TAG_DISPLAY_MANAGER_STATE);
        serializer.startTag(null, TAG_REMEMBERED_WIFI_DISPLAYS);
        for (WifiDisplay display : mRememberedWifiDisplays) {
            serializer.startTag(null, TAG_WIFI_DISPLAY);
            serializer.attribute(null, ATTR_DEVICE_ADDRESS, display.getDeviceAddress());
            serializer.attribute(null, ATTR_DEVICE_NAME, display.getDeviceName());
            if (display.getDeviceAlias() != null) {
                serializer.attribute(null, ATTR_DEVICE_ALIAS, display.getDeviceAlias());
            }
            serializer.endTag(null, TAG_WIFI_DISPLAY);
        }
        serializer.endTag(null, TAG_REMEMBERED_WIFI_DISPLAYS);
        serializer.startTag(null, TAG_DISPLAY_STATES);
        for (Map.Entry<String, DisplayState> entry : mDisplayStates.entrySet()) {
            final String uniqueId = entry.getKey();
            final DisplayState state = entry.getValue();
            serializer.startTag(null, TAG_DISPLAY);
            serializer.attribute(null, ATTR_UNIQUE_ID, uniqueId);
            state.saveToXml(serializer);
            serializer.endTag(null, TAG_DISPLAY);
        }
        serializer.endTag(null, TAG_DISPLAY_STATES);
        serializer.startTag(null, TAG_STABLE_DEVICE_VALUES);
        mStableDeviceValues.saveToXml(serializer);
        serializer.endTag(null, TAG_STABLE_DEVICE_VALUES);
        serializer.startTag(null, TAG_BRIGHTNESS_CONFIGURATIONS);
        mGlobalBrightnessConfigurations.saveToXml(serializer);
        serializer.endTag(null, TAG_BRIGHTNESS_CONFIGURATIONS);
        serializer.endTag(null, TAG_DISPLAY_MANAGER_STATE);
        serializer.endDocument();
    }

    public void dump(PrintWriter pw) {
        pw.println("PersistentDataStore");
        pw.println("  mLoaded=" + mLoaded);
        pw.println("  mDirty=" + mDirty);
        pw.println("  RememberedWifiDisplays:");
        int i = 0;
        for (WifiDisplay display : mRememberedWifiDisplays) {
            pw.println("    " + i++ + ": " + display);
        }
        pw.println("  DisplayStates:");
        i = 0;
        for (Map.Entry<String, DisplayState> entry : mDisplayStates.entrySet()) {
            pw.println("    " + i++ + ": " + entry.getKey());
            entry.getValue().dump(pw, "      ");
        }
        pw.println("  StableDeviceValues:");
        mStableDeviceValues.dump(pw, "      ");
        pw.println("  GlobalBrightnessConfigurations:");
        mGlobalBrightnessConfigurations.dump(pw, "      ");
    }

    private static final class DisplayState {
        private int mColorMode;
        private float mBrightness;
        private int mWidth;
        private int mHeight;
        private float mRefreshRate;

        // Brightness configuration by user
        private BrightnessConfigurations mDisplayBrightnessConfigurations =
                new BrightnessConfigurations();

        public boolean setColorMode(int colorMode) {
            if (colorMode == mColorMode) {
                return false;
            }
            mColorMode = colorMode;
            return true;
        }

        public int getColorMode() {
            return mColorMode;
        }

        public boolean setBrightness(float brightness) {
            if (brightness == mBrightness) {
                return false;
            }
            mBrightness = brightness;
            return true;
        }

        public float getBrightness() {
            return mBrightness;
        }

        public boolean setBrightnessConfiguration(BrightnessConfiguration configuration,
                int userSerial, String packageName) {
            mDisplayBrightnessConfigurations.setBrightnessConfigurationForUser(
                    configuration, userSerial, packageName);
            return true;
        }

        public BrightnessConfiguration getBrightnessConfiguration(int userSerial) {
            return mDisplayBrightnessConfigurations.mConfigurations.get(userSerial);
        }

        public boolean setResolution(int width, int height) {
            if (width == mWidth && height == mHeight) {
                return false;
            }
            mWidth = width;
            mHeight = height;
            return true;
        }

        public Point getResolution() {
            return new Point(mWidth, mHeight);
        }

        public boolean setRefreshRate(float refreshRate) {
            if (refreshRate == mRefreshRate) {
                return false;
            }
            mRefreshRate = refreshRate;
            return true;
        }

        public float getRefreshRate() {
            return mRefreshRate;
        }

        public void loadFromXml(TypedXmlPullParser parser)
                throws IOException, XmlPullParserException {
            final int outerDepth = parser.getDepth();

            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                switch (parser.getName()) {
                    case TAG_COLOR_MODE:
                        String value = parser.nextText();
                        mColorMode = Integer.parseInt(value);
                        break;
                    case TAG_BRIGHTNESS_VALUE:
                        String brightness = parser.nextText();
                        mBrightness = Float.parseFloat(brightness);
                        break;
                    case TAG_BRIGHTNESS_CONFIGURATIONS:
                        mDisplayBrightnessConfigurations.loadFromXml(parser);
                        break;
                }
            }
        }

        public void saveToXml(TypedXmlSerializer serializer) throws IOException {
            serializer.startTag(null, TAG_COLOR_MODE);
            serializer.text(Integer.toString(mColorMode));
            serializer.endTag(null, TAG_COLOR_MODE);

            serializer.startTag(null, TAG_BRIGHTNESS_VALUE);
            serializer.text(Float.toString(mBrightness));
            serializer.endTag(null, TAG_BRIGHTNESS_VALUE);

            serializer.startTag(null, TAG_BRIGHTNESS_CONFIGURATIONS);
            mDisplayBrightnessConfigurations.saveToXml(serializer);
            serializer.endTag(null, TAG_BRIGHTNESS_CONFIGURATIONS);
        }

        public void dump(final PrintWriter pw, final String prefix) {
            pw.println(prefix + "ColorMode=" + mColorMode);
            pw.println(prefix + "BrightnessValue=" + mBrightness);
            pw.println(prefix + "DisplayBrightnessConfigurations: ");
            mDisplayBrightnessConfigurations.dump(pw, prefix);
        }
    }

    private static final class StableDeviceValues {
        private int mWidth;
        private int mHeight;

        private Point getDisplaySize() {
            return new Point(mWidth, mHeight);
        }

        public boolean setDisplaySize(Point r) {
            if (mWidth != r.x || mHeight != r.y) {
                mWidth = r.x;
                mHeight = r.y;
                return true;
            }
            return false;
        }

        public void loadFromXml(TypedXmlPullParser parser)
                throws IOException, XmlPullParserException {
            final int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                switch (parser.getName()) {
                    case TAG_STABLE_DISPLAY_WIDTH:
                        mWidth = loadIntValue(parser);
                        break;
                    case TAG_STABLE_DISPLAY_HEIGHT:
                        mHeight = loadIntValue(parser);
                        break;
                }
            }
        }

        private static int loadIntValue(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
            try {
                String value = parser.nextText();
                return Integer.parseInt(value);
            } catch (NumberFormatException nfe) {
                return 0;
            }
        }

        public void saveToXml(TypedXmlSerializer serializer) throws IOException {
            if (mWidth > 0 && mHeight > 0) {
                serializer.startTag(null, TAG_STABLE_DISPLAY_WIDTH);
                serializer.text(Integer.toString(mWidth));
                serializer.endTag(null, TAG_STABLE_DISPLAY_WIDTH);
                serializer.startTag(null, TAG_STABLE_DISPLAY_HEIGHT);
                serializer.text(Integer.toString(mHeight));
                serializer.endTag(null, TAG_STABLE_DISPLAY_HEIGHT);
            }
        }

        public void dump(final PrintWriter pw, final String prefix) {
            pw.println(prefix + "StableDisplayWidth=" + mWidth);
            pw.println(prefix + "StableDisplayHeight=" + mHeight);
        }
    }

    private static final class BrightnessConfigurations {
        // Maps from a user ID to the users' given brightness configuration
        private final SparseArray<BrightnessConfiguration> mConfigurations;
        // Timestamp of time the configuration was set.
        private final SparseLongArray mTimeStamps;
        // Package that set the configuration.
        private final SparseArray<String> mPackageNames;

        public BrightnessConfigurations() {
            mConfigurations = new SparseArray<>();
            mTimeStamps = new SparseLongArray();
            mPackageNames = new SparseArray<>();
        }

        private boolean setBrightnessConfigurationForUser(BrightnessConfiguration c,
                int userSerial, String packageName) {
            BrightnessConfiguration currentConfig = mConfigurations.get(userSerial);
            if (currentConfig != c && (currentConfig == null || !currentConfig.equals(c))) {
                if (c != null) {
                    if (packageName == null) {
                        mPackageNames.remove(userSerial);
                    } else {
                        mPackageNames.put(userSerial, packageName);
                    }
                    mTimeStamps.put(userSerial, System.currentTimeMillis());
                    mConfigurations.put(userSerial, c);
                } else {
                    mPackageNames.remove(userSerial);
                    mTimeStamps.delete(userSerial);
                    mConfigurations.remove(userSerial);
                }
                return true;
            }
            return false;
        }

        public BrightnessConfiguration getBrightnessConfiguration(int userSerial) {
            return mConfigurations.get(userSerial);
        }

        public void loadFromXml(TypedXmlPullParser parser)
                throws IOException, XmlPullParserException {
            final int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                if (TAG_BRIGHTNESS_CONFIGURATION.equals(parser.getName())) {
                    int userSerial;
                    try {
                        userSerial = parser.getAttributeInt(null, ATTR_USER_SERIAL);
                    } catch (NumberFormatException nfe) {
                        userSerial = -1;
                        Slog.e(TAG, "Failed to read in brightness configuration", nfe);
                    }

                    String packageName = parser.getAttributeValue(null, ATTR_PACKAGE_NAME);
                    long timeStamp = parser.getAttributeLong(null, ATTR_TIME_STAMP, -1);

                    try {
                        BrightnessConfiguration config =
                                BrightnessConfiguration.loadFromXml(parser);
                        if (userSerial >= 0 && config != null) {
                            mConfigurations.put(userSerial, config);
                            if (timeStamp != -1) {
                                mTimeStamps.put(userSerial, timeStamp);
                            }
                            if (packageName != null) {
                                mPackageNames.put(userSerial, packageName);
                            }
                        }
                    } catch (IllegalArgumentException iae) {
                        Slog.e(TAG, "Failed to load brightness configuration!", iae);
                    }
                }
            }
        }

        public void saveToXml(TypedXmlSerializer serializer) throws IOException {
            for (int i = 0; i < mConfigurations.size(); i++) {
                final int userSerial = mConfigurations.keyAt(i);
                final BrightnessConfiguration config = mConfigurations.valueAt(i);

                serializer.startTag(null, TAG_BRIGHTNESS_CONFIGURATION);
                serializer.attributeInt(null, ATTR_USER_SERIAL, userSerial);
                String packageName = mPackageNames.get(userSerial);
                if (packageName != null) {
                    serializer.attribute(null, ATTR_PACKAGE_NAME, packageName);
                }
                long timestamp = mTimeStamps.get(userSerial, -1);
                if (timestamp != -1) {
                    serializer.attributeLong(null, ATTR_TIME_STAMP, timestamp);
                }
                config.saveToXml(serializer);
                serializer.endTag(null, TAG_BRIGHTNESS_CONFIGURATION);
            }
        }

        public void dump(final PrintWriter pw, final String prefix) {
            for (int i = 0; i < mConfigurations.size(); i++) {
                final int userSerial = mConfigurations.keyAt(i);
                long time = mTimeStamps.get(userSerial, -1);
                String packageName = mPackageNames.get(userSerial);
                pw.println(prefix + "User " + userSerial + ":");
                if (time != -1) {
                    pw.println(prefix + "  set at: " + TimeUtils.formatForLogging(time));
                }
                if (packageName != null) {
                    pw.println(prefix + "  set by: " + packageName);
                }
                pw.println(prefix + "  " + mConfigurations.valueAt(i));
            }
        }
    }

    @VisibleForTesting
    static class Injector {
        private final AtomicFile mAtomicFile;

        public Injector() {
            mAtomicFile = new AtomicFile(new File("/data/system/display-manager-state.xml"),
                    "display-state");
        }

        public InputStream openRead() throws FileNotFoundException {
            return mAtomicFile.openRead();
        }

        public OutputStream startWrite() throws IOException {
            return mAtomicFile.startWrite();
        }

        public void finishWrite(OutputStream os, boolean success) {
            if (!(os instanceof FileOutputStream)) {
                throw new IllegalArgumentException("Unexpected OutputStream as argument: " + os);
            }
            FileOutputStream fos = (FileOutputStream) os;
            if (success) {
                mAtomicFile.finishWrite(fos);
            } else {
                mAtomicFile.failWrite(fos);
            }
        }
    }
}
