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

package android.graphics.fonts;

import static android.text.FontConfig.Alias;
import static android.text.FontConfig.NamedFamilyList;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.FontListParser;
import android.text.FontConfig;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parser for font customization
 *
 * @hide
 */
public class FontCustomizationParser {
    private static final String TAG = "FontCustomizationParser";

    /**
     * Represents a customization XML
     */
    public static class Result {
        private final Map<String, NamedFamilyList> mAdditionalNamedFamilies;

        private final List<Alias> mAdditionalAliases;

        private final List<FontConfig.Customization.LocaleFallback> mLocaleFamilyCustomizations;

        public Result() {
            mAdditionalNamedFamilies = Collections.emptyMap();
            mLocaleFamilyCustomizations = Collections.emptyList();
            mAdditionalAliases = Collections.emptyList();
        }

        public Result(Map<String, NamedFamilyList> additionalNamedFamilies,
                List<FontConfig.Customization.LocaleFallback> localeFamilyCustomizations,
                List<Alias> additionalAliases) {
            mAdditionalNamedFamilies = additionalNamedFamilies;
            mLocaleFamilyCustomizations = localeFamilyCustomizations;
            mAdditionalAliases = additionalAliases;
        }

        public Map<String, NamedFamilyList> getAdditionalNamedFamilies() {
            return mAdditionalNamedFamilies;
        }

        public List<Alias> getAdditionalAliases() {
            return mAdditionalAliases;
        }

        public List<FontConfig.Customization.LocaleFallback> getLocaleFamilyCustomizations() {
            return mLocaleFamilyCustomizations;
        }
    }

    /**
     * Parses the customization XML
     *
     * Caller must close the input stream
     */
    public static Result parse(
            @NonNull InputStream in,
            @NonNull String fontDir,
            @Nullable Map<String, File> updatableFontMap
    ) throws XmlPullParserException, IOException {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(in, null);
        parser.nextTag();
        return readFamilies(parser, fontDir, updatableFontMap);
    }

    private static Result validateAndTransformToResult(
            List<NamedFamilyList> families,
            List<FontConfig.Customization.LocaleFallback> outLocaleFamilies,
            List<Alias> aliases) {
        HashMap<String, NamedFamilyList> namedFamily = new HashMap<>();
        for (int i = 0; i < families.size(); ++i) {
            final NamedFamilyList family = families.get(i);
            final String name = family.getName();
            if (name != null) {
                if (namedFamily.put(name, family) != null) {
                    throw new IllegalArgumentException(
                            "new-named-family requires unique name attribute");
                }
            } else {
                throw new IllegalArgumentException(
                        "new-named-family requires name attribute or new-default-fallback-family"
                                + "requires fallackTarget attribute");
            }
        }
        return new Result(namedFamily, outLocaleFamilies, aliases);
    }

    private static Result readFamilies(
            @NonNull XmlPullParser parser,
            @NonNull String fontDir,
            @Nullable Map<String, File> updatableFontMap
    ) throws XmlPullParserException, IOException {
        List<NamedFamilyList> families = new ArrayList<>();
        List<Alias> aliases = new ArrayList<>();
        List<FontConfig.Customization.LocaleFallback> outLocaleFamilies = new ArrayList<>();
        parser.require(XmlPullParser.START_TAG, null, "fonts-modification");
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if (tag.equals("family")) {
                readFamily(parser, fontDir, families, outLocaleFamilies, updatableFontMap);
            } else if (tag.equals("family-list")) {
                readFamilyList(parser, fontDir, families, updatableFontMap);
            } else if (tag.equals("alias")) {
                aliases.add(FontListParser.readAlias(parser));
            } else {
                FontListParser.skip(parser);
            }
        }
        return validateAndTransformToResult(families, outLocaleFamilies, aliases);
    }

    private static void readFamily(
            @NonNull XmlPullParser parser,
            @NonNull String fontDir,
            @NonNull List<NamedFamilyList> out,
            @NonNull List<FontConfig.Customization.LocaleFallback> outCustomization,
            @Nullable Map<String, File> updatableFontMap)
            throws XmlPullParserException, IOException {
        final String customizationType = parser.getAttributeValue(null, "customizationType");
        if (customizationType == null) {
            throw new IllegalArgumentException("customizationType must be specified");
        }
        if (customizationType.equals("new-named-family")) {
            NamedFamilyList fontFamily = FontListParser.readNamedFamily(
                    parser, fontDir, updatableFontMap, false);
            if (fontFamily != null) {
                out.add(fontFamily);
            }
        } else if (customizationType.equals("new-locale-family")) {
            final String lang = parser.getAttributeValue(null, "lang");
            final String op = parser.getAttributeValue(null, "operation");
            final int intOp;
            if (op.equals("append")) {
                intOp = FontConfig.Customization.LocaleFallback.OPERATION_APPEND;
            } else if (op.equals("prepend")) {
                intOp = FontConfig.Customization.LocaleFallback.OPERATION_PREPEND;
            } else if (op.equals("replace")) {
                intOp = FontConfig.Customization.LocaleFallback.OPERATION_REPLACE;
            } else {
                throw new IllegalArgumentException("Unknown operation=" + op);
            }

            final FontConfig.FontFamily family = FontListParser.readFamily(
                    parser, fontDir, updatableFontMap, false);

            // For ignoring the customization, consume the new-locale-family element but don't
            // register any customizations.
            outCustomization.add(new FontConfig.Customization.LocaleFallback(
                    Locale.forLanguageTag(lang), intOp, family));
        } else {
            throw new IllegalArgumentException("Unknown customizationType=" + customizationType);
        }
    }

    private static void readFamilyList(
            @NonNull XmlPullParser parser,
            @NonNull String fontDir,
            @NonNull List<NamedFamilyList> out,
            @Nullable Map<String, File> updatableFontMap)
            throws XmlPullParserException, IOException {
        final String customizationType = parser.getAttributeValue(null, "customizationType");
        if (customizationType == null) {
            throw new IllegalArgumentException("customizationType must be specified");
        }
        if (customizationType.equals("new-named-family")) {
            NamedFamilyList fontFamily = FontListParser.readNamedFamilyList(
                    parser, fontDir, updatableFontMap, false);
            if (fontFamily != null) {
                out.add(fontFamily);
            }
        } else {
            throw new IllegalArgumentException("Unknown customizationType=" + customizationType);
        }
    }
}
