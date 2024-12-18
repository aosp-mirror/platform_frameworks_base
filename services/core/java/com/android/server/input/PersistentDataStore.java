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

package com.android.server.input;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.input.TouchCalibration;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.Xml;
import android.view.Surface;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Manages persistent state recorded by the input manager service as an XML file.
 * Caller must acquire lock on the data store before accessing it.
 *
 * File format:
 * <code>
 * &lt;input-mananger-state>
 *   &lt;input-devices>
 *     &lt;input-device descriptor="xxxxx" keyboard-layout="yyyyy" />
 *   &gt;input-devices>
 * &gt;/input-manager-state>
 * </code>
 */
final class PersistentDataStore {
    static final String TAG = "InputManager";

    private static final int INVALID_VALUE = -1;

    // Input device state by descriptor.
    private final HashMap<String, InputDeviceState> mInputDevices =
            new HashMap<String, InputDeviceState>();

    // The interface for methods which should be replaced by the test harness.
    private final Injector mInjector;

    // True if the data has been loaded.
    private boolean mLoaded;

    // True if there are changes to be saved.
    private boolean mDirty;

    // Storing key remapping
    private final Map<Integer, Integer> mKeyRemapping = new HashMap<>();

    public PersistentDataStore() {
        this(new Injector());
    }

    @VisibleForTesting
    PersistentDataStore(Injector injector) {
        mInjector = injector;
    }

    public void saveIfNeeded() {
        if (mDirty) {
            save();
            mDirty = false;
        }
    }

    public boolean hasInputDeviceEntry(String inputDeviceDescriptor) {
        return getInputDeviceState(inputDeviceDescriptor) != null;
    }

    public TouchCalibration getTouchCalibration(String inputDeviceDescriptor, int surfaceRotation) {
        InputDeviceState state = getInputDeviceState(inputDeviceDescriptor);
        if (state == null) {
            return TouchCalibration.IDENTITY;
        }

        TouchCalibration cal = state.getTouchCalibration(surfaceRotation);
        if (cal == null) {
            return TouchCalibration.IDENTITY;
        }
        return cal;
    }

    public boolean setTouchCalibration(String inputDeviceDescriptor, int surfaceRotation, TouchCalibration calibration) {
        InputDeviceState state = getOrCreateInputDeviceState(inputDeviceDescriptor);

        if (state.setTouchCalibration(surfaceRotation, calibration)) {
            setDirty();
            return true;
        }

        return false;
    }

    @Nullable
    public String getKeyboardLayout(String inputDeviceDescriptor, String key) {
        InputDeviceState state = getInputDeviceState(inputDeviceDescriptor);
        return state != null ? state.getKeyboardLayout(key) : null;
    }

    public boolean setKeyboardLayout(String inputDeviceDescriptor, String key,
            String keyboardLayoutDescriptor) {
        InputDeviceState state = getOrCreateInputDeviceState(inputDeviceDescriptor);
        if (state.setKeyboardLayout(key, keyboardLayoutDescriptor)) {
            setDirty();
            return true;
        }
        return false;
    }

    public boolean setSelectedKeyboardLayouts(String inputDeviceDescriptor,
            @NonNull Set<String> selectedLayouts) {
        InputDeviceState state = getOrCreateInputDeviceState(inputDeviceDescriptor);
        if (state.setSelectedKeyboardLayouts(selectedLayouts)) {
            setDirty();
            return true;
        }
        return false;
    }

    public boolean setKeyboardBacklightBrightness(String inputDeviceDescriptor, int lightId,
            int brightness) {
        InputDeviceState state = getOrCreateInputDeviceState(inputDeviceDescriptor);
        if (state.setKeyboardBacklightBrightness(lightId, brightness)) {
            setDirty();
            return true;
        }
        return false;
    }

    public OptionalInt getKeyboardBacklightBrightness(String inputDeviceDescriptor, int lightId) {
        InputDeviceState state = getInputDeviceState(inputDeviceDescriptor);
        if (state == null) {
            return OptionalInt.empty();
        }
        return state.getKeyboardBacklightBrightness(lightId);
    }

    public boolean remapKey(int fromKey, int toKey) {
        loadIfNeeded();
        if (mKeyRemapping.getOrDefault(fromKey, INVALID_VALUE) == toKey) {
            return false;
        }
        mKeyRemapping.put(fromKey, toKey);
        setDirty();
        return true;
    }

    public boolean clearMappedKey(int key) {
        loadIfNeeded();
        if (mKeyRemapping.containsKey(key)) {
            mKeyRemapping.remove(key);
            setDirty();
        }
        return true;
    }

    public Map<Integer, Integer> getKeyRemapping() {
        loadIfNeeded();
        return new HashMap<>(mKeyRemapping);
    }

    public boolean removeUninstalledKeyboardLayouts(Set<String> availableKeyboardLayouts) {
        boolean changed = false;
        for (InputDeviceState state : mInputDevices.values()) {
            if (state.removeUninstalledKeyboardLayouts(availableKeyboardLayouts)) {
                changed = true;
            }
        }
        if (changed) {
            setDirty();
            return true;
        }
        return false;
    }

    private InputDeviceState getInputDeviceState(String inputDeviceDescriptor) {
        loadIfNeeded();
        return mInputDevices.get(inputDeviceDescriptor);
    }

    private InputDeviceState getOrCreateInputDeviceState(String inputDeviceDescriptor) {
        loadIfNeeded();
        InputDeviceState state = mInputDevices.get(inputDeviceDescriptor);
        if (state == null) {
            state = new InputDeviceState();
            mInputDevices.put(inputDeviceDescriptor, state);
            setDirty();
        }
        return state;
    }

    private void loadIfNeeded() {
        if (!mLoaded) {
            load();
            mLoaded = true;
        }
    }

    private void setDirty() {
        mDirty = true;
    }

    private void clearState() {
        mKeyRemapping.clear();
        mInputDevices.clear();
    }

    private void load() {
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
            Slog.w(InputManagerService.TAG, "Failed to load input manager persistent store data.", ex);
            clearState();
        } catch (XmlPullParserException ex) {
            Slog.w(InputManagerService.TAG, "Failed to load input manager persistent store data.", ex);
            clearState();
        } finally {
            IoUtils.closeQuietly(is);
        }
    }

    private void save() {
        final FileOutputStream os;
        try {
            os = mInjector.startWrite();
            boolean success = false;
            try {
                TypedXmlSerializer serializer = Xml.resolveSerializer(os);
                saveToXml(serializer);
                serializer.flush();
                success = true;
            } finally {
                mInjector.finishWrite(os, success);
            }
        } catch (IOException ex) {
            Slog.w(InputManagerService.TAG, "Failed to save input manager persistent store data.", ex);
        }
    }

    private void loadFromXml(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        XmlUtils.beginDocument(parser, "input-manager-state");
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (parser.getName().equals("key-remapping")) {
                loadKeyRemappingFromXml(parser);
            } else if (parser.getName().equals("input-devices")) {
                loadInputDevicesFromXml(parser);
            }
        }
    }

    private void loadInputDevicesFromXml(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (parser.getName().equals("input-device")) {
                String descriptor = parser.getAttributeValue(null, "descriptor");
                if (descriptor == null) {
                    throw new XmlPullParserException(
                            "Missing descriptor attribute on input-device.");
                }
                if (mInputDevices.containsKey(descriptor)) {
                    throw new XmlPullParserException("Found duplicate input device.");
                }

                InputDeviceState state = new InputDeviceState();
                state.loadFromXml(parser);
                mInputDevices.put(descriptor, state);
            }
        }
    }

    private void loadKeyRemappingFromXml(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (parser.getName().equals("remap")) {
                int fromKey = parser.getAttributeInt(null, "from-key");
                int toKey = parser.getAttributeInt(null, "to-key");
                mKeyRemapping.put(fromKey, toKey);
            }
        }
    }

    private void saveToXml(TypedXmlSerializer serializer) throws IOException {
        serializer.startDocument(null, true);
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        serializer.startTag(null, "input-manager-state");
        serializer.startTag(null, "key-remapping");
        for (int fromKey : mKeyRemapping.keySet()) {
            int toKey = mKeyRemapping.get(fromKey);
            serializer.startTag(null, "remap");
            serializer.attributeInt(null, "from-key", fromKey);
            serializer.attributeInt(null, "to-key", toKey);
            serializer.endTag(null, "remap");
        }
        serializer.endTag(null, "key-remapping");
        serializer.startTag(null, "input-devices");
        for (Map.Entry<String, InputDeviceState> entry : mInputDevices.entrySet()) {
            final String descriptor = entry.getKey();
            final InputDeviceState state = entry.getValue();
            serializer.startTag(null, "input-device");
            serializer.attribute(null, "descriptor", descriptor);
            state.saveToXml(serializer);
            serializer.endTag(null, "input-device");
        }
        serializer.endTag(null, "input-devices");
        serializer.endTag(null, "input-manager-state");
        serializer.endDocument();
    }

    private static final class InputDeviceState {
        private static final String[] CALIBRATION_NAME = { "x_scale",
                "x_ymix", "x_offset", "y_xmix", "y_scale", "y_offset" };

        private final TouchCalibration[] mTouchCalibration = new TouchCalibration[4];
        private final SparseIntArray mKeyboardBacklightBrightnessMap = new SparseIntArray();

        private final Map<String, String> mKeyboardLayoutMap = new ArrayMap<>();

        private Set<String> mSelectedKeyboardLayouts;

        public TouchCalibration getTouchCalibration(int surfaceRotation) {
            try {
                return mTouchCalibration[surfaceRotation];
            } catch (ArrayIndexOutOfBoundsException ex) {
                Slog.w(InputManagerService.TAG, "Cannot get touch calibration.", ex);
                return null;
            }
        }

        public boolean setTouchCalibration(int surfaceRotation, TouchCalibration calibration) {
            try {
                if (!calibration.equals(mTouchCalibration[surfaceRotation])) {
                    mTouchCalibration[surfaceRotation] = calibration;
                    return true;
                }
                return false;
            } catch (ArrayIndexOutOfBoundsException ex) {
                Slog.w(InputManagerService.TAG, "Cannot set touch calibration.", ex);
                return false;
            }
        }

        @Nullable
        public String getKeyboardLayout(String key) {
            return mKeyboardLayoutMap.get(key);
        }

        public boolean setKeyboardLayout(String key, String keyboardLayout) {
            return !Objects.equals(mKeyboardLayoutMap.put(key, keyboardLayout), keyboardLayout);
        }

        public boolean setSelectedKeyboardLayouts(@NonNull Set<String> selectedLayouts) {
            if (Objects.equals(mSelectedKeyboardLayouts, selectedLayouts)) {
                return false;
            }
            mSelectedKeyboardLayouts = new HashSet<>(selectedLayouts);
            return true;
        }

        public boolean setKeyboardBacklightBrightness(int lightId, int brightness) {
            if (mKeyboardBacklightBrightnessMap.get(lightId, INVALID_VALUE) == brightness) {
                return false;
            }
            mKeyboardBacklightBrightnessMap.put(lightId, brightness);
            return true;
        }

        public OptionalInt getKeyboardBacklightBrightness(int lightId) {
            int brightness = mKeyboardBacklightBrightnessMap.get(lightId, INVALID_VALUE);
            return brightness == INVALID_VALUE ? OptionalInt.empty() : OptionalInt.of(brightness);
        }

        public boolean removeUninstalledKeyboardLayouts(Set<String> availableKeyboardLayouts) {
            boolean changed = false;
            List<String> removedEntries = new ArrayList<>();
            for (String key : mKeyboardLayoutMap.keySet()) {
                if (!availableKeyboardLayouts.contains(mKeyboardLayoutMap.get(key))) {
                    removedEntries.add(key);
                }
            }
            if (!removedEntries.isEmpty()) {
                for (String key : removedEntries) {
                    mKeyboardLayoutMap.remove(key);
                }
                changed = true;
            }
            return changed;
        }

        public void loadFromXml(TypedXmlPullParser parser)
                throws IOException, XmlPullParserException {
            final int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                if (parser.getName().equals("keyed-keyboard-layout")) {
                    String key = parser.getAttributeValue(null, "key");
                    if (key == null) {
                        throw new XmlPullParserException(
                                "Missing key attribute on keyed-keyboard-layout.");
                    }
                    String layout = parser.getAttributeValue(null, "layout");
                    if (layout == null) {
                        throw new XmlPullParserException(
                                "Missing layout attribute on keyed-keyboard-layout.");
                    }
                    mKeyboardLayoutMap.put(key, layout);
                } else if (parser.getName().equals("selected-keyboard-layout")) {
                    String layout = parser.getAttributeValue(null, "layout");
                    if (layout == null) {
                        throw new XmlPullParserException(
                                "Missing layout attribute on selected-keyboard-layout.");
                    }
                    if (mSelectedKeyboardLayouts == null) {
                        mSelectedKeyboardLayouts = new HashSet<>();
                    }
                    mSelectedKeyboardLayouts.add(layout);
                } else if (parser.getName().equals("light-info")) {
                    int lightId = parser.getAttributeInt(null, "light-id");
                    int lightBrightness = parser.getAttributeInt(null, "light-brightness");
                    mKeyboardBacklightBrightnessMap.put(lightId, lightBrightness);
                } else if (parser.getName().equals("calibration")) {
                    String format = parser.getAttributeValue(null, "format");
                    String rotation = parser.getAttributeValue(null, "rotation");
                    int r = -1;

                    if (format == null) {
                        throw new XmlPullParserException(
                                "Missing format attribute on calibration.");
                    }
                    if (!format.equals("affine")) {
                        throw new XmlPullParserException(
                                "Unsupported format for calibration.");
                    }
                    if (rotation != null) {
                        try {
                            r = stringToSurfaceRotation(rotation);
                        } catch (IllegalArgumentException e) {
                            throw new XmlPullParserException(
                                    "Unsupported rotation for calibration.");
                        }
                    }

                    float[] matrix = TouchCalibration.IDENTITY.getAffineTransform();
                    int depth = parser.getDepth();
                    while (XmlUtils.nextElementWithin(parser, depth)) {
                        String tag = parser.getName().toLowerCase();
                        String value = parser.nextText();

                        for (int i = 0; i < matrix.length && i < CALIBRATION_NAME.length; i++) {
                            if (tag.equals(CALIBRATION_NAME[i])) {
                                matrix[i] = Float.parseFloat(value);
                                break;
                            }
                        }
                    }

                    if (r == -1) {
                        // Assume calibration applies to all rotations
                        for (r = 0; r < mTouchCalibration.length; r++) {
                            mTouchCalibration[r] = new TouchCalibration(matrix[0],
                                matrix[1], matrix[2], matrix[3], matrix[4], matrix[5]);
                        }
                    } else {
                        mTouchCalibration[r] = new TouchCalibration(matrix[0],
                            matrix[1], matrix[2], matrix[3], matrix[4], matrix[5]);
                    }
                }
            }
        }

        public void saveToXml(TypedXmlSerializer serializer) throws IOException {
            for (String key : mKeyboardLayoutMap.keySet()) {
                serializer.startTag(null, "keyed-keyboard-layout");
                serializer.attribute(null, "key", key);
                serializer.attribute(null, "layout", mKeyboardLayoutMap.get(key));
                serializer.endTag(null, "keyed-keyboard-layout");
            }

            if (mSelectedKeyboardLayouts != null) {
                for (String layout : mSelectedKeyboardLayouts) {
                    serializer.startTag(null, "selected-keyboard-layout");
                    serializer.attribute(null, "layout", layout);
                    serializer.endTag(null, "selected-keyboard-layout");
                }
            }

            for (int i = 0; i < mKeyboardBacklightBrightnessMap.size(); i++) {
                serializer.startTag(null, "light-info");
                serializer.attributeInt(null, "light-id", mKeyboardBacklightBrightnessMap.keyAt(i));
                serializer.attributeInt(null, "light-brightness",
                        mKeyboardBacklightBrightnessMap.valueAt(i));
                serializer.endTag(null, "light-info");
            }

            for (int i = 0; i < mTouchCalibration.length; i++) {
                if (mTouchCalibration[i] != null) {
                    String rotation = surfaceRotationToString(i);
                    float[] transform = mTouchCalibration[i].getAffineTransform();

                    serializer.startTag(null, "calibration");
                    serializer.attribute(null, "format", "affine");
                    serializer.attribute(null, "rotation", rotation);
                    for (int j = 0; j < transform.length && j < CALIBRATION_NAME.length; j++) {
                        serializer.startTag(null, CALIBRATION_NAME[j]);
                        serializer.text(Float.toString(transform[j]));
                        serializer.endTag(null, CALIBRATION_NAME[j]);
                    }
                    serializer.endTag(null, "calibration");
                }
            }
        }

        private static String surfaceRotationToString(int surfaceRotation) {
            switch (surfaceRotation) {
                case Surface.ROTATION_0:   return "0";
                case Surface.ROTATION_90:  return "90";
                case Surface.ROTATION_180: return "180";
                case Surface.ROTATION_270: return "270";
            }
            throw new IllegalArgumentException("Unsupported surface rotation value" + surfaceRotation);
        }

        private static int stringToSurfaceRotation(String s) {
            if ("0".equals(s)) {
                return Surface.ROTATION_0;
            }
            if ("90".equals(s)) {
                return Surface.ROTATION_90;
            }
            if ("180".equals(s)) {
                return Surface.ROTATION_180;
            }
            if ("270".equals(s)) {
                return Surface.ROTATION_270;
            }
            throw new IllegalArgumentException("Unsupported surface rotation string '" + s + "'");
        }
    }

    @VisibleForTesting
    static class Injector {
        private final AtomicFile mAtomicFile;

        Injector() {
            mAtomicFile = new AtomicFile(new File("/data/system/input-manager-state.xml"),
                    "input-state");
        }

        InputStream openRead() throws FileNotFoundException {
            return mAtomicFile.openRead();
        }

        FileOutputStream startWrite() throws IOException {
            return mAtomicFile.startWrite();
        }

        void finishWrite(FileOutputStream fos, boolean success) {
            if (success) {
                mAtomicFile.finishWrite(fos);
            } else {
                mAtomicFile.failWrite(fos);
            }
        }
    }
}
