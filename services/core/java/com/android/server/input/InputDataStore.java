/*
 * Copyright 2024 The Android Open Source Project
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

import android.hardware.input.AppLaunchData;
import android.hardware.input.InputGestureData;
import android.os.Environment;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages persistent state recorded by the input manager service as a set of XML files.
 * Caller must acquire lock on the data store before accessing it.
 */
public final class InputDataStore {
    private static final String TAG = "InputDataStore";

    private static final String INPUT_MANAGER_DIRECTORY = "input";

    private static final String TAG_ROOT = "root";

    private static final String TAG_INPUT_GESTURE_LIST = "input_gesture_list";
    private static final String TAG_INPUT_GESTURE = "input_gesture";
    private static final String TAG_KEY_TRIGGER = "key_trigger";
    private static final String TAG_TOUCHPAD_TRIGGER = "touchpad_trigger";
    private static final String TAG_APP_LAUNCH_DATA = "app_launch_data";

    private static final String ATTR_KEY_TRIGGER_KEYCODE = "keycode";
    private static final String ATTR_KEY_TRIGGER_MODIFIER_STATE = "modifiers";
    private static final String ATTR_KEY_GESTURE_TYPE = "key_gesture_type";
    private static final String ATTR_TOUCHPAD_TRIGGER_GESTURE_TYPE = "touchpad_gesture_type";
    private static final String ATTR_APP_LAUNCH_DATA_CATEGORY = "category";
    private static final String ATTR_APP_LAUNCH_DATA_ROLE = "role";
    private static final String ATTR_APP_LAUNCH_DATA_PACKAGE_NAME = "package_name";
    private static final String ATTR_APP_LAUNCH_DATA_CLASS_NAME = "class_name";

    private final FileInjector mInputGestureFileInjector;

    public InputDataStore() {
        this(new FileInjector("input_gestures.xml"));
    }

    public InputDataStore(final FileInjector inputGestureFileInjector) {
        mInputGestureFileInjector = inputGestureFileInjector;
    }

    /**
     * Reads from the local disk storage the list of customized input gestures.
     *
     * @param userId The user id to fetch the gestures for.
     * @return List of {@link InputGestureData} which the user previously customized.
     */
    public List<InputGestureData> loadInputGestures(int userId) {
        List<InputGestureData> inputGestureDataList;
        try {
            final InputStream inputStream = mInputGestureFileInjector.openRead(userId);
            inputGestureDataList = readInputGesturesXml(inputStream, false);
            inputStream.close();
        } catch (IOException exception) {
            // In case we are unable to read from the file on disk or another IO operation error,
            // fail gracefully.
            Slog.e(TAG, "Failed to read from " + mInputGestureFileInjector.getAtomicFileForUserId(
                    userId), exception);
            return List.of();
        } catch (Exception exception) {
            // In the case of any other exception, we want it to bubble up as this would be due
            // to malformed trusted XML data.
            throw new RuntimeException(
                    "Failed to read from " + mInputGestureFileInjector.getAtomicFileForUserId(
                            userId), exception);
        }
        return inputGestureDataList;
    }

    /**
     * Writes to the local disk storage the list of customized input gestures provided as a param.
     *
     * @param userId               The user id to store the {@link InputGestureData} list under.
     * @param inputGestureDataList The list of custom input gestures for the given {@code userId}.
     */
    public void saveInputGestures(int userId, List<InputGestureData> inputGestureDataList) {
        FileOutputStream outputStream = null;
        try {
            outputStream = mInputGestureFileInjector.startWrite(userId);
            writeInputGestureXml(outputStream, false, inputGestureDataList);
            mInputGestureFileInjector.finishWrite(userId, outputStream, true);
        } catch (IOException e) {
            Slog.e(TAG,
                    "Failed to write to file " + mInputGestureFileInjector.getAtomicFileForUserId(
                            userId), e);
            mInputGestureFileInjector.finishWrite(userId, outputStream, false);
        }
    }

    @VisibleForTesting
    List<InputGestureData> readInputGesturesXml(InputStream stream, boolean utf8Encoded)
            throws XmlPullParserException, IOException {
        List<InputGestureData> inputGestureDataList = new ArrayList<>();
        TypedXmlPullParser parser;
        if (utf8Encoded) {
            parser = Xml.newFastPullParser();
            parser.setInput(stream, StandardCharsets.UTF_8.name());
        } else {
            parser = Xml.resolvePullParser(stream);
        }
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            final String tag = parser.getName();
            if (TAG_ROOT.equals(tag)) {
                continue;
            }

            if (TAG_INPUT_GESTURE_LIST.equals(tag)) {
                inputGestureDataList.addAll(readInputGestureListFromXml(parser));
            }
        }
        return inputGestureDataList;
    }

    private InputGestureData readInputGestureFromXml(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException, IllegalArgumentException {
        InputGestureData.Builder builder = new InputGestureData.Builder();
        builder.setKeyGestureType(parser.getAttributeInt(null, ATTR_KEY_GESTURE_TYPE));
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            // If the parser has left the initial scope when it was called, break out.
            if (outerDepth > parser.getDepth()) {
                throw new RuntimeException(
                        "Parser has left the initial scope of the tag that was being parsed on "
                                + "line number: "
                                + parser.getLineNumber());
            }

            // If the parser has reached the closing tag for the Input Gesture, break out.
            if (type == XmlPullParser.END_TAG && parser.getName().equals(TAG_INPUT_GESTURE)) {
                break;
            }

            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            final String tag = parser.getName();
            if (TAG_KEY_TRIGGER.equals(tag)) {
                builder.setTrigger(InputGestureData.createKeyTrigger(
                        parser.getAttributeInt(null, ATTR_KEY_TRIGGER_KEYCODE),
                        parser.getAttributeInt(null, ATTR_KEY_TRIGGER_MODIFIER_STATE)));
            } else if (TAG_TOUCHPAD_TRIGGER.equals(tag)) {
                builder.setTrigger(InputGestureData.createTouchpadTrigger(
                        parser.getAttributeInt(null, ATTR_TOUCHPAD_TRIGGER_GESTURE_TYPE)));
            } else if (TAG_APP_LAUNCH_DATA.equals(tag)) {
                final String roleValue = parser.getAttributeValue(null, ATTR_APP_LAUNCH_DATA_ROLE);
                final String categoryValue = parser.getAttributeValue(null,
                        ATTR_APP_LAUNCH_DATA_CATEGORY);
                final String classNameValue = parser.getAttributeValue(null,
                        ATTR_APP_LAUNCH_DATA_CLASS_NAME);
                final String packageNameValue = parser.getAttributeValue(null,
                        ATTR_APP_LAUNCH_DATA_PACKAGE_NAME);
                final AppLaunchData appLaunchData = AppLaunchData.createLaunchData(categoryValue,
                        roleValue, packageNameValue, classNameValue);
                if (appLaunchData != null) {
                    builder.setAppLaunchData(appLaunchData);
                }
            }
        }
        return builder.build();
    }

    private List<InputGestureData> readInputGestureListFromXml(TypedXmlPullParser parser) throws
            XmlPullParserException, IOException {
        List<InputGestureData> inputGestureDataList = new ArrayList<>();
        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            // If the parser has left the initial scope when it was called, break out.
            if (outerDepth > parser.getDepth()) {
                throw new RuntimeException(
                        "Parser has left the initial scope of the tag that was being parsed on "
                                + "line number: "
                                + parser.getLineNumber());
            }

            // If the parser has reached the closing tag for the Input Gesture List, break out.
            if (type == XmlPullParser.END_TAG && parser.getName().equals(TAG_INPUT_GESTURE_LIST)) {
                break;
            }

            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            final String tag = parser.getName();
            if (TAG_INPUT_GESTURE.equals(tag)) {
                try {
                    inputGestureDataList.add(readInputGestureFromXml(parser));
                } catch (IllegalArgumentException exception) {
                    Slog.w(TAG, "Invalid parameters for input gesture data: ", exception);
                    continue;
                }
            }
        }
        return inputGestureDataList;
    }

    @VisibleForTesting
    void writeInputGestureXml(OutputStream stream, boolean utf8Encoded,
            List<InputGestureData> inputGestureDataList) throws IOException {
        final TypedXmlSerializer serializer;
        if (utf8Encoded) {
            serializer = Xml.newFastSerializer();
            serializer.setOutput(stream, StandardCharsets.UTF_8.name());
        } else {
            serializer = Xml.resolveSerializer(stream);
        }

        serializer.startDocument(null, true);
        serializer.startTag(null, TAG_ROOT);
        writeInputGestureListToXml(serializer, inputGestureDataList);
        serializer.endTag(null, TAG_ROOT);
        serializer.endDocument();
    }

    private void writeInputGestureToXml(TypedXmlSerializer serializer,
            InputGestureData inputGestureData) throws IOException {
        serializer.startTag(null, TAG_INPUT_GESTURE);
        serializer.attributeInt(null, ATTR_KEY_GESTURE_TYPE,
                inputGestureData.getAction().keyGestureType());

        final InputGestureData.Trigger trigger = inputGestureData.getTrigger();
        if (trigger instanceof InputGestureData.KeyTrigger keyTrigger) {
            serializer.startTag(null, TAG_KEY_TRIGGER);
            serializer.attributeInt(null, ATTR_KEY_TRIGGER_KEYCODE, keyTrigger.getKeycode());
            serializer.attributeInt(null, ATTR_KEY_TRIGGER_MODIFIER_STATE,
                    keyTrigger.getModifierState());
            serializer.endTag(null, TAG_KEY_TRIGGER);
        } else if (trigger instanceof InputGestureData.TouchpadTrigger touchpadTrigger) {
            serializer.startTag(null, TAG_TOUCHPAD_TRIGGER);
            serializer.attributeInt(null, ATTR_TOUCHPAD_TRIGGER_GESTURE_TYPE,
                    touchpadTrigger.getTouchpadGestureType());
            serializer.endTag(null, TAG_TOUCHPAD_TRIGGER);
        }

        if (inputGestureData.getAction().appLaunchData() != null) {
            serializer.startTag(null, TAG_APP_LAUNCH_DATA);
            final AppLaunchData appLaunchData = inputGestureData.getAction().appLaunchData();
            if (appLaunchData instanceof AppLaunchData.RoleData roleData) {
                serializer.attribute(null, ATTR_APP_LAUNCH_DATA_ROLE, roleData.getRole());
            } else if (appLaunchData
                    instanceof AppLaunchData.CategoryData categoryData) {
                serializer.attribute(null, ATTR_APP_LAUNCH_DATA_CATEGORY,
                        categoryData.getCategory());
            } else if (appLaunchData instanceof AppLaunchData.ComponentData componentData) {
                serializer.attribute(null, ATTR_APP_LAUNCH_DATA_PACKAGE_NAME,
                        componentData.getPackageName());
                serializer.attribute(null, ATTR_APP_LAUNCH_DATA_CLASS_NAME,
                        componentData.getClassName());
            }
            serializer.endTag(null, TAG_APP_LAUNCH_DATA);
        }

        serializer.endTag(null, TAG_INPUT_GESTURE);
    }

    private void writeInputGestureListToXml(TypedXmlSerializer serializer,
            List<InputGestureData> inputGestureDataList) throws IOException {
        serializer.startTag(null, TAG_INPUT_GESTURE_LIST);
        for (final InputGestureData inputGestureData : inputGestureDataList) {
            writeInputGestureToXml(serializer, inputGestureData);
        }
        serializer.endTag(null, TAG_INPUT_GESTURE_LIST);
    }

    @VisibleForTesting
    static class FileInjector {
        private final SparseArray<AtomicFile> mAtomicFileMap = new SparseArray<>();
        private final String mFileName;

        FileInjector(String fileName) {
            mFileName = fileName;
        }

        InputStream openRead(int userId) throws FileNotFoundException {
            return getAtomicFileForUserId(userId).openRead();
        }

        FileOutputStream startWrite(int userId) throws IOException {
            return getAtomicFileForUserId(userId).startWrite();
        }

        void finishWrite(int userId, FileOutputStream os, boolean success) {
            if (success) {
                getAtomicFileForUserId(userId).finishWrite(os);
            } else {
                getAtomicFileForUserId(userId).failWrite(os);
            }
        }

        AtomicFile getAtomicFileForUserId(int userId) {
            if (!mAtomicFileMap.contains(userId)) {
                mAtomicFileMap.put(userId, new AtomicFile(new File(
                        Environment.buildPath(Environment.getDataSystemDeDirectory(userId),
                                INPUT_MANAGER_DIRECTORY), mFileName)));
            }
            return mAtomicFileMap.get(userId);
        }
    }
}
