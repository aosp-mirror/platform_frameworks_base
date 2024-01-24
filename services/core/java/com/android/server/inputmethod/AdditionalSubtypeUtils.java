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
import android.icu.util.ULocale;
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

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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
    private static final String ATTR_NAME_OVERRIDE = "nameOverride";
    private static final String ATTR_NAME_PK_LANGUAGE_TAG = "pkLanguageTag";
    private static final String ATTR_NAME_PK_LAYOUT_TYPE = "pkLayoutType";
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
     * @param userId User ID with subtype.xml path should be determined.
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
     * @param methodMap   {@link ArrayMap} from IME ID to {@link InputMethodInfo}.
     * @param userId      The user ID to be associated with.
     */
    static void save(ArrayMap<String, List<InputMethodSubtype>> allSubtypes,
            InputMethodMap methodMap, @UserIdInt int userId) {
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
        saveToFile(allSubtypes, methodMap, getAdditionalSubtypeFile(inputMethodDir));
    }

    @VisibleForTesting
    static void saveToFile(ArrayMap<String, List<InputMethodSubtype>> allSubtypes,
            InputMethodMap methodMap, AtomicFile subtypesFile) {
        // Safety net for the case that this function is called before methodMap is set.
        final boolean isSetMethodMap = methodMap != null && methodMap.size() > 0;
        FileOutputStream fos = null;
        try {
            fos = subtypesFile.startWrite();
            final TypedXmlSerializer out = Xml.resolveSerializer(fos);
            out.startDocument(null, true);
            out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            out.startTag(null, NODE_SUBTYPES);
            for (String imiId : allSubtypes.keySet()) {
                if (isSetMethodMap && !methodMap.containsKey(imiId)) {
                    Slog.w(TAG, "IME uninstalled or not valid.: " + imiId);
                    continue;
                }
                final List<InputMethodSubtype> subtypesList = allSubtypes.get(imiId);
                if (subtypesList == null) {
                    Slog.e(TAG, "Null subtype list for IME " + imiId);
                    continue;
                }
                out.startTag(null, NODE_IMI);
                out.attribute(null, ATTR_ID, imiId);
                for (final InputMethodSubtype subtype : subtypesList) {
                    out.startTag(null, NODE_SUBTYPE);
                    if (subtype.hasSubtypeId()) {
                        out.attributeInt(null, ATTR_IME_SUBTYPE_ID, subtype.getSubtypeId());
                    }
                    out.attributeInt(null, ATTR_ICON, subtype.getIconResId());
                    out.attributeInt(null, ATTR_LABEL, subtype.getNameResId());
                    out.attribute(null, ATTR_NAME_OVERRIDE, subtype.getNameOverride().toString());
                    ULocale pkLanguageTag = subtype.getPhysicalKeyboardHintLanguageTag();
                    if (pkLanguageTag != null) {
                        out.attribute(null, ATTR_NAME_PK_LANGUAGE_TAG,
                                pkLanguageTag.toLanguageTag());
                    }
                    out.attribute(null, ATTR_NAME_PK_LAYOUT_TYPE,
                            subtype.getPhysicalKeyboardHintLayoutType());

                    out.attribute(null, ATTR_IME_SUBTYPE_LOCALE, subtype.getLocale());
                    out.attribute(null, ATTR_IME_SUBTYPE_LANGUAGE_TAG,
                            subtype.getLanguageTag());
                    out.attribute(null, ATTR_IME_SUBTYPE_MODE, subtype.getMode());
                    out.attribute(null, ATTR_IME_SUBTYPE_EXTRA_VALUE, subtype.getExtraValue());
                    out.attributeInt(null, ATTR_IS_AUXILIARY, subtype.isAuxiliary() ? 1 : 0);
                    out.attributeInt(null, ATTR_IS_ASCII_CAPABLE, subtype.isAsciiCapable() ? 1 : 0);
                    out.endTag(null, NODE_SUBTYPE);
                }
                out.endTag(null, NODE_IMI);
            }
            out.endTag(null, NODE_SUBTYPES);
            out.endDocument();
            subtypesFile.finishWrite(fos);
        } catch (IOException e) {
            Slog.w(TAG, "Error writing subtypes", e);
            if (fos != null) {
                subtypesFile.failWrite(fos);
            }
        } finally {
            IoUtils.closeQuietly(fos);
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
     * @param userId      The user ID to be associated with.
     */
    static void load(@NonNull ArrayMap<String, List<InputMethodSubtype>> allSubtypes,
            @UserIdInt int userId) {
        allSubtypes.clear();

        final AtomicFile subtypesFile = getAdditionalSubtypeFile(getInputMethodDir(userId));
        // Not having the file means there is no additional subtype.
        if (subtypesFile.exists()) {
            loadFromFile(allSubtypes, subtypesFile);
        }
    }

    @VisibleForTesting
    static void loadFromFile(@NonNull ArrayMap<String, List<InputMethodSubtype>> allSubtypes,
            AtomicFile subtypesFile) {
        try (FileInputStream fis = subtypesFile.openRead()) {
            final TypedXmlPullParser parser = Xml.resolvePullParser(fis);
            int type = parser.next();
            // Skip parsing until START_TAG
            while (type != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT) {
                type = parser.next();
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
                    final int icon = parser.getAttributeInt(null, ATTR_ICON);
                    final int label = parser.getAttributeInt(null, ATTR_LABEL);
                    final String untranslatableName = parser.getAttributeValue(null,
                            ATTR_NAME_OVERRIDE);
                    final String pkLanguageTag = parser.getAttributeValue(null,
                            ATTR_NAME_PK_LANGUAGE_TAG);
                    final String pkLayoutType = parser.getAttributeValue(null,
                            ATTR_NAME_PK_LAYOUT_TYPE);
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
                            .setPhysicalKeyboardHint(
                                    pkLanguageTag == null ? null : new ULocale(pkLanguageTag),
                                    pkLayoutType == null ? "" : pkLayoutType)
                            .setSubtypeIconResId(icon)
                            .setSubtypeLocale(imeSubtypeLocale)
                            .setLanguageTag(languageTag)
                            .setSubtypeMode(imeSubtypeMode)
                            .setSubtypeExtraValue(imeSubtypeExtraValue)
                            .setIsAuxiliary(isAuxiliary)
                            .setIsAsciiCapable(isAsciiCapable);
                    final int subtypeId = parser.getAttributeInt(null, ATTR_IME_SUBTYPE_ID,
                            InputMethodSubtype.SUBTYPE_ID_NONE);
                    if (subtypeId != InputMethodSubtype.SUBTYPE_ID_NONE) {
                        builder.setSubtypeId(subtypeId);
                    }
                    if (untranslatableName != null) {
                        builder.setSubtypeNameOverride(untranslatableName);
                    }
                    tempSubtypesArray.add(builder.build());
                }
            }
        } catch (XmlPullParserException | IOException | NumberFormatException e) {
            Slog.w(TAG, "Error reading subtypes", e);
        }
    }
}
