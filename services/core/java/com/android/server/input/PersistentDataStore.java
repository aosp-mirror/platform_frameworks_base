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

import com.android.internal.inputmethod.InputMethodSubtypeHandle;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.annotation.Nullable;
import android.view.Surface;
import android.hardware.input.TouchCalibration;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import libcore.io.IoUtils;
import libcore.util.Objects;

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

    // Input device state by descriptor.
    private final HashMap<String, InputDeviceState> mInputDevices =
            new HashMap<String, InputDeviceState>();
    private final AtomicFile mAtomicFile;

    // True if the data has been loaded.
    private boolean mLoaded;

    // True if there are changes to be saved.
    private boolean mDirty;

    public PersistentDataStore() {
        mAtomicFile = new AtomicFile(new File("/data/system/input-manager-state.xml"));
    }

    public void saveIfNeeded() {
        if (mDirty) {
            save();
            mDirty = false;
        }
    }

    public TouchCalibration getTouchCalibration(String inputDeviceDescriptor, int surfaceRotation) {
        InputDeviceState state = getInputDeviceState(inputDeviceDescriptor, false);
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
        InputDeviceState state = getInputDeviceState(inputDeviceDescriptor, true);

        if (state.setTouchCalibration(surfaceRotation, calibration)) {
            setDirty();
            return true;
        }

        return false;
    }

    public String getCurrentKeyboardLayout(String inputDeviceDescriptor) {
        InputDeviceState state = getInputDeviceState(inputDeviceDescriptor, false);
        return state != null ? state.getCurrentKeyboardLayout() : null;
    }

    public boolean setCurrentKeyboardLayout(String inputDeviceDescriptor,
            String keyboardLayoutDescriptor) {
        InputDeviceState state = getInputDeviceState(inputDeviceDescriptor, true);
        if (state.setCurrentKeyboardLayout(keyboardLayoutDescriptor)) {
            setDirty();
            return true;
        }
        return false;
    }

    public String[] getKeyboardLayouts(String inputDeviceDescriptor) {
        InputDeviceState state = getInputDeviceState(inputDeviceDescriptor, false);
        if (state == null) {
            return (String[])ArrayUtils.emptyArray(String.class);
        }
        return state.getKeyboardLayouts();
    }
    public String getKeyboardLayout(String inputDeviceDescriptor,
            InputMethodSubtypeHandle imeHandle) {
        InputDeviceState state = getInputDeviceState(inputDeviceDescriptor, false);
        if (state == null) {
            return null;
        }
        return state.getKeyboardLayout(imeHandle);
    }

    public boolean setKeyboardLayout(String inputDeviceDescriptor,
            InputMethodSubtypeHandle imeHandle, String keyboardLayoutDescriptor) {
        InputDeviceState state = getInputDeviceState(inputDeviceDescriptor, true);
        if (state.setKeyboardLayout(imeHandle, keyboardLayoutDescriptor)) {
            setDirty();
            return true;
        }
        return false;
    }

    public boolean addKeyboardLayout(String inputDeviceDescriptor, String keyboardLayoutDescriptor) {
        InputDeviceState state = getInputDeviceState(inputDeviceDescriptor, true);
        if (state.addKeyboardLayout(keyboardLayoutDescriptor)) {
            setDirty();
            return true;
        }
        return false;
    }

    public boolean removeKeyboardLayout(String inputDeviceDescriptor,
            String keyboardLayoutDescriptor) {
        InputDeviceState state = getInputDeviceState(inputDeviceDescriptor, true);
        if (state.removeKeyboardLayout(keyboardLayoutDescriptor)) {
            setDirty();
            return true;
        }
        return false;
    }

    public boolean switchKeyboardLayout(String inputDeviceDescriptor,
            InputMethodSubtypeHandle imeHandle) {
        InputDeviceState state = getInputDeviceState(inputDeviceDescriptor, false);
        if (state != null && state.switchKeyboardLayout(imeHandle)) {
            setDirty();
            return true;
        }
        return false;
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

    private InputDeviceState getInputDeviceState(String inputDeviceDescriptor,
            boolean createIfAbsent) {
        loadIfNeeded();
        InputDeviceState state = mInputDevices.get(inputDeviceDescriptor);
        if (state == null && createIfAbsent) {
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
        mInputDevices.clear();
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
            Slog.w(InputManagerService.TAG, "Failed to save input manager persistent store data.", ex);
        }
    }

    private void loadFromXml(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        XmlUtils.beginDocument(parser, "input-manager-state");
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (parser.getName().equals("input-devices")) {
                loadInputDevicesFromXml(parser);
            }
        }
    }

    private void loadInputDevicesFromXml(XmlPullParser parser)
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

    private void saveToXml(XmlSerializer serializer) throws IOException {
        serializer.startDocument(null, true);
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        serializer.startTag(null, "input-manager-state");
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

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "PersistentDataStore");
        pw.println(prefix + "  mLoaded=" + mLoaded);
        pw.println(prefix + "  mDirty=" + mDirty);
        pw.println(prefix + "  InputDeviceStates:");
        int i = 0;
        for (Map.Entry<String, InputDeviceState> entry : mInputDevices.entrySet()) {
            pw.println(prefix + "    " + i++ + ": " + entry.getKey());
            entry.getValue().dump(pw, prefix + "      ");
        }
    }

    private static final class InputDeviceState {
        private static final String[] CALIBRATION_NAME = { "x_scale",
                "x_ymix", "x_offset", "y_xmix", "y_scale", "y_offset" };

        private TouchCalibration[] mTouchCalibration = new TouchCalibration[4];
        @Nullable
        private String mCurrentKeyboardLayout;
        private List<String> mUnassociatedKeyboardLayouts = new ArrayList<>();
        private ArrayMap<InputMethodSubtypeHandle, String> mKeyboardLayouts = new ArrayMap<>();

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
        public String getCurrentKeyboardLayout() {
            return mCurrentKeyboardLayout;
        }

        public boolean setCurrentKeyboardLayout(String keyboardLayout) {
            if (Objects.equal(mCurrentKeyboardLayout, keyboardLayout)) {
                return false;
            }
            addKeyboardLayout(keyboardLayout);
            mCurrentKeyboardLayout = keyboardLayout;
            return true;
        }

        public String[] getKeyboardLayouts() {
            if (mUnassociatedKeyboardLayouts.isEmpty()) {
                return (String[])ArrayUtils.emptyArray(String.class);
            }
            return mUnassociatedKeyboardLayouts.toArray(
                    new String[mUnassociatedKeyboardLayouts.size()]);
        }

        public String getKeyboardLayout(InputMethodSubtypeHandle handle) {
            return mKeyboardLayouts.get(handle);
        }

        public boolean setKeyboardLayout(InputMethodSubtypeHandle imeHandle,
                String keyboardLayout) {
            String existingLayout = mKeyboardLayouts.get(imeHandle);
            if (TextUtils.equals(existingLayout, keyboardLayout)) {
                return false;
            }
            mKeyboardLayouts.put(imeHandle, keyboardLayout);
            return true;
        }

        public boolean addKeyboardLayout(String keyboardLayout) {
            int index = Collections.binarySearch(
                    mUnassociatedKeyboardLayouts, keyboardLayout);
            if (index >= 0) {
                return false;
            }
            mUnassociatedKeyboardLayouts.add(-index - 1, keyboardLayout);
            if (mCurrentKeyboardLayout == null) {
                mCurrentKeyboardLayout = keyboardLayout;
            }
            return true;
        }

        public boolean removeKeyboardLayout(String keyboardLayout) {
            int index = Collections.binarySearch(mUnassociatedKeyboardLayouts, keyboardLayout);
            if (index < 0) {
                return false;
            }
            mUnassociatedKeyboardLayouts.remove(index);
            updateCurrentKeyboardLayoutIfRemoved(keyboardLayout, index);
            return true;
        }

        private void updateCurrentKeyboardLayoutIfRemoved(
                String removedKeyboardLayout, int removedIndex) {
            if (Objects.equal(mCurrentKeyboardLayout, removedKeyboardLayout)) {
                if (!mUnassociatedKeyboardLayouts.isEmpty()) {
                    int index = removedIndex;
                    if (index == mUnassociatedKeyboardLayouts.size()) {
                        index = 0;
                    }
                    mCurrentKeyboardLayout = mUnassociatedKeyboardLayouts.get(index);
                } else {
                    mCurrentKeyboardLayout = null;
                }
            }
        }

        public boolean switchKeyboardLayout(InputMethodSubtypeHandle imeHandle) {
            final String layout = mKeyboardLayouts.get(imeHandle);
            if (!TextUtils.equals(mCurrentKeyboardLayout, layout)) {
                mCurrentKeyboardLayout = layout;
                return true;
            }
            return false;
        }

        public boolean removeUninstalledKeyboardLayouts(Set<String> availableKeyboardLayouts) {
            boolean changed = false;
            for (int i = mUnassociatedKeyboardLayouts.size(); i-- > 0; ) {
                String keyboardLayout = mUnassociatedKeyboardLayouts.get(i);
                if (!availableKeyboardLayouts.contains(keyboardLayout)) {
                    Slog.i(TAG, "Removing uninstalled keyboard layout " + keyboardLayout);
                    mUnassociatedKeyboardLayouts.remove(i);
                    updateCurrentKeyboardLayoutIfRemoved(keyboardLayout, i);
                    changed = true;
                }
            }
            return changed;
        }

        public void loadFromXml(XmlPullParser parser)
                throws IOException, XmlPullParserException {
            final int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                if (parser.getName().equals("keyboard-layout")) {
                    String descriptor = parser.getAttributeValue(null, "descriptor");
                    if (descriptor == null) {
                        throw new XmlPullParserException(
                                "Missing descriptor attribute on keyboard-layout.");
                    }

                    String current = parser.getAttributeValue(null, "current");
                    if (current != null && current.equals("true")) {
                        if (mCurrentKeyboardLayout != null) {
                            throw new XmlPullParserException(
                                    "Found multiple current keyboard layouts.");
                        }
                        mCurrentKeyboardLayout = descriptor;
                    }

                    String inputMethodId = parser.getAttributeValue(null, "input-method-id");
                    String inputMethodSubtypeId =
                        parser.getAttributeValue(null, "input-method-subtype-id");
                    if (inputMethodId == null && inputMethodSubtypeId != null
                            || inputMethodId != null && inputMethodSubtypeId == null) {
                        throw new XmlPullParserException(
                                "Found an incomplete input method description");
                    }

                    if (inputMethodSubtypeId != null) {
                        InputMethodSubtypeHandle handle = new InputMethodSubtypeHandle(
                                inputMethodId, Integer.parseInt(inputMethodSubtypeId));
                        if (mKeyboardLayouts.containsKey(handle)) {
                            throw new XmlPullParserException(
                                    "Found duplicate subtype to keyboard layout mapping: "
                                    + handle);
                        }
                        mKeyboardLayouts.put(handle, descriptor);
                    } else {
                        if (mUnassociatedKeyboardLayouts.contains(descriptor)) {
                            throw new XmlPullParserException(
                                    "Found duplicate unassociated keyboard layout: " + descriptor);
                        }
                        mUnassociatedKeyboardLayouts.add(descriptor);
                    }
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

            // Maintain invariant that layouts are sorted.
            Collections.sort(mUnassociatedKeyboardLayouts);

            // Maintain invariant that there is always a current keyboard layout unless
            // there are none installed.
            if (mCurrentKeyboardLayout == null && !mUnassociatedKeyboardLayouts.isEmpty()) {
                mCurrentKeyboardLayout = mUnassociatedKeyboardLayouts.get(0);
            }
        }

        public void saveToXml(XmlSerializer serializer) throws IOException {
            for (String layout : mUnassociatedKeyboardLayouts) {
                serializer.startTag(null, "keyboard-layout");
                serializer.attribute(null, "descriptor", layout);
                serializer.endTag(null, "keyboard-layout");
            }

            final int N = mKeyboardLayouts.size();
            for (int i = 0; i < N; i++) {
                final InputMethodSubtypeHandle handle = mKeyboardLayouts.keyAt(i);
                final String layout = mKeyboardLayouts.valueAt(i);
                serializer.startTag(null, "keyboard-layout");
                serializer.attribute(null, "descriptor", layout);
                serializer.attribute(null, "input-method-id", handle.getInputMethodId());
                serializer.attribute(null, "input-method-subtype-id",
                        Integer.toString(handle.getSubtypeId()));
                if (layout.equals(mCurrentKeyboardLayout)) {
                    serializer.attribute(null, "current", "true");
                }
                serializer.endTag(null, "keyboard-layout");
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

        private void dump(final PrintWriter pw, final String prefix) {
            pw.println(prefix + "CurrentKeyboardLayout=" + mCurrentKeyboardLayout);
            pw.println(prefix + "UnassociatedKeyboardLayouts=" + mUnassociatedKeyboardLayouts);
            pw.println(prefix + "TouchCalibration=" + Arrays.toString(mTouchCalibration));
            pw.println(prefix + "Subtype to Layout Mappings:");
            final int N = mKeyboardLayouts.size();
            if (N != 0) {
                for (int i = 0; i < N; i++) {
                    pw.println(prefix + "  " + mKeyboardLayouts.keyAt(i) + ": "
                            + mKeyboardLayouts.valueAt(i));
                }
            } else {
                pw.println(prefix + "  <none>");
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
}
