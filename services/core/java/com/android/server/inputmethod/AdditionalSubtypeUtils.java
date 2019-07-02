/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.inputmethod;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.os.Environment;
import android.os.FileUtils;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.util.FastXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class to read/write subtype.xml.
 */
final class AdditionalSubtypeUtils {
    private static final String TAG = "AdditionalSubtypeUtils";

    private static final String SYSTEM_PATH = "system";
    private static final String INPUT_METHOD_PATH = "inputmethod";
    private static final String ADDITIONAL_SUBTYPES_FILE_NAME = "subtypes.xml";
    private static final String NODE_SUBTYPES = "subtypes";
    private static final String NODE_SUBTYPE = "subtype";
    private static final String NODE_IMI = "imi";
    private static final String ATTR_ID = "id";
    private static final String ATTR_LABEL = "label";
    private static final String ATTR_ICON = "icon";
    private static final String ATTR_IME_SUBTYPE_ID = "subtypeId";
    private static final String ATTR_IME_SUBTYPE_LOCALE = "imeSubtypeLocale";
    private static final String ATTR_IME_SUBTYPE_LANGUAGE_TAG = "languageTag";
    private static final String ATTR_IME_SUBTYPE_MODE = "imeSubtypeMode";
    private static final String ATTR_IME_SUBTYPE_EXTRA_VALUE = "imeSubtypeExtraValue";
    private static final String ATTR_IS_AUXILIARY = "isAuxiliary";
    private static final String ATTR_IS_ASCII_CAPABLE = "isAsciiCapable";

    private AdditionalSubtypeUtils() {
    }

    /**
     * Returns a {@link File} that represents the directory at which subtype.xml will be placed.
     *
     * @param userId User ID with with subtype.xml path should be determined.
     * @return {@link File} that represents the directory.
     */
    @NonNull
    private static File getInputMethodDir(@UserIdInt int userId) {
        final File systemDir = userId == UserHandle.USER_SYSTEM
                ? new File(Environment.getDataDirectory(), SYSTEM_PATH)
                : Environment.getUserSystemDirectory(userId);
        return new File(systemDir, INPUT_METHOD_PATH);
    }

    /**
     * Returns an {@link AtomicFile} to read/write additional subtype for the given user id.
     *
     * @param inputMethodDir Directory at which subtype.xml will be placed
     * @return {@link AtomicFile} to be used to read/write additional subtype
     */
    @NonNull
    private static AtomicFile getAdditionalSubtypeFile(File inputMethodDir) {
        final File subtypeFile = new File(inputMethodDir, ADDITIONAL_SUBTYPES_FILE_NAME);
        return new AtomicFile(subtypeFile, "input-subtypes");
    }

    /**
     * Write additional subtypes into "subtype.xml".
     *
     * <p>This method does not confer any data/file locking semantics. Caller must make sure that
     * multiple threads are not calling this method at the same time for the same {@code userId}.
     * </p>
     *
     * @param allSubtypes {@link ArrayMap} from IME ID to additional subtype list. Passing an empty
     *                    map deletes the file.
     * @param methodMap {@link ArrayMap} from IME ID to {@link InputMethodInfo}.
     * @param userId The user ID to be associated with.
     */
    static void save(ArrayMap<String, List<InputMethodSubtype>> allSubtypes,
             ArrayMap<String, InputMethodInfo> methodMap, @UserIdInt int userId) {
        final File inputMethodDir = getInputMethodDir(userId);

        if (allSubtypes.isEmpty()) {
            if (!inputMethodDir.exists()) {
                // Even the parent directory doesn't exist.  There is nothing to clean up.
                return;
            }
            final AtomicFile subtypesFile = getAdditionalSubtypeFile(inputMethodDir);
            if (subtypesFile.exists()) {
                subtypesFile.delete();
            }
            if (FileUtils.listFilesOrEmpty(inputMethodDir).length == 0) {
                if (!inputMethodDir.delete()) {
                    Slog.e(TAG, "Failed to delete the empty parent directory " + inputMethodDir);
                }
            }
            return;
        }

        if (!inputMethodDir.exists() && !inputMethodDir.mkdirs()) {
            Slog.e(TAG, "Failed to create a parent directory " + inputMethodDir);
            return;
        }

        // Safety net for the case that this function is called before methodMap is set.
        final boolean isSetMethodMap = methodMap != null && methodMap.size() > 0;
        FileOutputStream fos = null;
        final AtomicFile subtypesFile = getAdditionalSubtypeFile(inputMethodDir);
        try {
            fos = subtypesFile.startWrite();
            final XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);
            out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            out.startTag(null, NODE_SUBTYPES);
            for (String imiId : allSubtypes.keySet()) {
                if (isSetMethodMap && !methodMap.containsKey(imiId)) {
                    Slog.w(TAG, "IME uninstalled or not valid.: " + imiId);
                    continue;
                }
                out.startTag(null, NODE_IMI);
                out.attribute(null, ATTR_ID, imiId);
                final List<InputMethodSubtype> subtypesList = allSubtypes.get(imiId);
                final int numSubtypes = subtypesList.size();
                for (int i = 0; i < numSubtypes; ++i) {
                    final InputMethodSubtype subtype = subtypesList.get(i);
                    out.startTag(null, NODE_SUBTYPE);
                    if (subtype.hasSubtypeId()) {
                        out.attribute(null, ATTR_IME_SUBTYPE_ID,
                                String.valueOf(subtype.getSubtypeId()));
                    }
                    out.attribute(null, ATTR_ICON, String.valueOf(subtype.getIconResId()));
                    out.attribute(null, ATTR_LABEL, String.valueOf(subtype.getNameResId()));
                    out.attribute(null, ATTR_IME_SUBTYPE_LOCALE, subtype.getLocale());
                    out.attribute(null, ATTR_IME_SUBTYPE_LANGUAGE_TAG,
                            subtype.getLanguageTag());
                    out.attribute(null, ATTR_IME_SUBTYPE_MODE, subtype.getMode());
                    out.attribute(null, ATTR_IME_SUBTYPE_EXTRA_VALUE, subtype.getExtraValue());
                    out.attribute(null, ATTR_IS_AUXILIARY,
                            String.valueOf(subtype.isAuxiliary() ? 1 : 0));
                    out.attribute(null, ATTR_IS_ASCII_CAPABLE,
                            String.valueOf(subtype.isAsciiCapable() ? 1 : 0));
                    out.endTag(null, NODE_SUBTYPE);
                }
                out.endTag(null, NODE_IMI);
            }
            out.endTag(null, NODE_SUBTYPES);
            out.endDocument();
            subtypesFile.finishWrite(fos);
        } catch (java.io.IOException e) {
            Slog.w(TAG, "Error writing subtypes", e);
            if (fos != null) {
                subtypesFile.failWrite(fos);
            }
        }
    }

    /**
     * Read additional subtypes from "subtype.xml".
     *
     * <p>This method does not confer any data/file locking semantics. Caller must make sure that
     * multiple threads are not calling this method at the same time for the same {@code userId}.
     * </p>
     *
     * @param allSubtypes {@link ArrayMap} from IME ID to additional subtype list. This parameter
     *                    will be used to return the result.
     * @param userId The user ID to be associated with.
     */
    static void load(@NonNull ArrayMap<String, List<InputMethodSubtype>> allSubtypes,
            @UserIdInt int userId) {
        allSubtypes.clear();

        final AtomicFile subtypesFile = getAdditionalSubtypeFile(getInputMethodDir(userId));
        if (!subtypesFile.exists()) {
            // Not having the file means there is no additional subtype.
            return;
        }
        try (FileInputStream fis = subtypesFile.openRead()) {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, StandardCharsets.UTF_8.name());
            int type = parser.getEventType();
            // Skip parsing until START_TAG
            while (true) {
                type = parser.next();
                if (type == XmlPullParser.START_TAG || type == XmlPullParser.END_DOCUMENT) {
                    break;
                }
            }
            String firstNodeName = parser.getName();
            if (!NODE_SUBTYPES.equals(firstNodeName)) {
                throw new XmlPullParserException("Xml doesn't start with subtypes");
            }
            final int depth = parser.getDepth();
            String currentImiId = null;
            ArrayList<InputMethodSubtype> tempSubtypesArray = null;
            while (((type = parser.next()) != XmlPullParser.END_TAG
                    || parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }
                final String nodeName = parser.getName();
                if (NODE_IMI.equals(nodeName)) {
                    currentImiId = parser.getAttributeValue(null, ATTR_ID);
                    if (TextUtils.isEmpty(currentImiId)) {
                        Slog.w(TAG, "Invalid imi id found in subtypes.xml");
                        continue;
                    }
                    tempSubtypesArray = new ArrayList<>();
                    allSubtypes.put(currentImiId, tempSubtypesArray);
                } else if (NODE_SUBTYPE.equals(nodeName)) {
                    if (TextUtils.isEmpty(currentImiId) || tempSubtypesArray == null) {
                        Slog.w(TAG, "IME uninstalled or not valid.: " + currentImiId);
                        continue;
                    }
                    final int icon = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_ICON));
                    final int label = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_LABEL));
                    final String imeSubtypeLocale =
                            parser.getAttributeValue(null, ATTR_IME_SUBTYPE_LOCALE);
                    final String languageTag =
                            parser.getAttributeValue(null, ATTR_IME_SUBTYPE_LANGUAGE_TAG);
                    final String imeSubtypeMode =
                            parser.getAttributeValue(null, ATTR_IME_SUBTYPE_MODE);
                    final String imeSubtypeExtraValue =
                            parser.getAttributeValue(null, ATTR_IME_SUBTYPE_EXTRA_VALUE);
                    final boolean isAuxiliary = "1".equals(String.valueOf(
                            parser.getAttributeValue(null, ATTR_IS_AUXILIARY)));
                    final boolean isAsciiCapable = "1".equals(String.valueOf(
                            parser.getAttributeValue(null, ATTR_IS_ASCII_CAPABLE)));
                    final InputMethodSubtype.InputMethodSubtypeBuilder
                            builder = new InputMethodSubtype.InputMethodSubtypeBuilder()
                            .setSubtypeNameResId(label)
                            .setSubtypeIconResId(icon)
                            .setSubtypeLocale(imeSubtypeLocale)
                            .setLanguageTag(languageTag)
                            .setSubtypeMode(imeSubtypeMode)
                            .setSubtypeExtraValue(imeSubtypeExtraValue)
                            .setIsAuxiliary(isAuxiliary)
                            .setIsAsciiCapable(isAsciiCapable);
                    final String subtypeIdString =
                            parser.getAttributeValue(null, ATTR_IME_SUBTYPE_ID);
                    if (subtypeIdString != null) {
                        builder.setSubtypeId(Integer.parseInt(subtypeIdString));
                    }
                    tempSubtypesArray.add(builder.build());
                }
            }
        } catch (XmlPullParserException | IOException | NumberFormatException e) {
            Slog.w(TAG, "Error reading subtypes", e);
        }
    }
}
