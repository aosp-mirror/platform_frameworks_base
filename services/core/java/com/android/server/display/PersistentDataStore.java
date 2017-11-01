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

import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.hardware.display.WifiDisplay;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;
import android.view.Display;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import libcore.io.IoUtils;
import libcore.util.Objects;

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
 *      &lt;display>
 *          &lt;color-mode>0&lt;/color-mode>
 *      &lt;/display>
 *  &lt;/display-states>
 * &lt;/display-manager-state>
 * </code>
 *
 * TODO: refactor this to extract common code shared with the input manager's data store
 */
final class PersistentDataStore {
    static final String TAG = "DisplayManager";

    // Remembered Wifi display devices.
    private ArrayList<WifiDisplay> mRememberedWifiDisplays = new ArrayList<WifiDisplay>();

    // Display state by unique id.
    private final HashMap<String, DisplayState> mDisplayStates =
            new HashMap<String, DisplayState>();

    // The atomic file used to safely read or write the file.
    private final AtomicFile mAtomicFile;

    // True if the data has been loaded.
    private boolean mLoaded;

    // True if there are changes to be saved.
    private boolean mDirty;

    public PersistentDataStore() {
        mAtomicFile = new AtomicFile(new File("/data/system/display-manager-state.xml"));
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
            if (!Objects.equal(display.getDeviceAlias(), alias)) {
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
        clearState();

        final InputStream is;
        try {
            is = mAtomicFile.openRead();
        } catch (FileNotFoundException ex) {
            return;
        }

        XmlPullParser parser;
        try {
            parser = Xml.newPullParser();
            parser.setInput(new BufferedInputStream(is), StandardCharsets.UTF_8.name());
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

    private void save() {
        final FileOutputStream os;
        try {
            os = mAtomicFile.startWrite();
            boolean success = false;
            try {
                XmlSerializer serializer = new FastXmlSerializer();
                serializer.setOutput(new BufferedOutputStream(os), StandardCharsets.UTF_8.name());
                saveToXml(serializer);
                serializer.flush();
                success = true;
            } finally {
                if (success) {
                    mAtomicFile.finishWrite(os);
                } else {
                    mAtomicFile.failWrite(os);
                }
            }
        } catch (IOException ex) {
            Slog.w(TAG, "Failed to save display manager persistent store data.", ex);
        }
    }

    private void loadFromXml(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        XmlUtils.beginDocument(parser, "display-manager-state");
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (parser.getName().equals("remembered-wifi-displays")) {
                loadRememberedWifiDisplaysFromXml(parser);
            }
            if (parser.getName().equals("display-states")) {
                loadDisplaysFromXml(parser);
            }
        }
    }

    private void loadRememberedWifiDisplaysFromXml(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (parser.getName().equals("wifi-display")) {
                String deviceAddress = parser.getAttributeValue(null, "deviceAddress");
                String deviceName = parser.getAttributeValue(null, "deviceName");
                String deviceAlias = parser.getAttributeValue(null, "deviceAlias");
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

    private void loadDisplaysFromXml(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (parser.getName().equals("display")) {
                String uniqueId = parser.getAttributeValue(null, "unique-id");
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

    private void saveToXml(XmlSerializer serializer) throws IOException {
        serializer.startDocument(null, true);
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        serializer.startTag(null, "display-manager-state");
        serializer.startTag(null, "remembered-wifi-displays");
        for (WifiDisplay display : mRememberedWifiDisplays) {
            serializer.startTag(null, "wifi-display");
            serializer.attribute(null, "deviceAddress", display.getDeviceAddress());
            serializer.attribute(null, "deviceName", display.getDeviceName());
            if (display.getDeviceAlias() != null) {
                serializer.attribute(null, "deviceAlias", display.getDeviceAlias());
            }
            serializer.endTag(null, "wifi-display");
        }
        serializer.endTag(null, "remembered-wifi-displays");
        serializer.startTag(null, "display-states");
        for (Map.Entry<String, DisplayState> entry : mDisplayStates.entrySet()) {
            final String uniqueId = entry.getKey();
            final DisplayState state = entry.getValue();
            serializer.startTag(null, "display");
            serializer.attribute(null, "unique-id", uniqueId);
            state.saveToXml(serializer);
            serializer.endTag(null, "display");
        }
        serializer.endTag(null, "display-states");
        serializer.endTag(null, "display-manager-state");
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
    }

    private static final class DisplayState {
        private int mColorMode;

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

        public void loadFromXml(XmlPullParser parser)
                throws IOException, XmlPullParserException {
            final int outerDepth = parser.getDepth();

            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                if (parser.getName().equals("color-mode")) {
                    String value = parser.nextText();
                    mColorMode = Integer.parseInt(value);
                }
            }
        }

        public void saveToXml(XmlSerializer serializer) throws IOException {
            serializer.startTag(null, "color-mode");
            serializer.text(Integer.toString(mColorMode));
            serializer.endTag(null, "color-mode");
        }

        private void dump(final PrintWriter pw, final String prefix) {
            pw.println(prefix + "ColorMode=" + mColorMode);
        }
    }
}
