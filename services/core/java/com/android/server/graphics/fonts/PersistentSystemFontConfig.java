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

package com.android.server.graphics.fonts;

import android.annotation.NonNull;
import android.graphics.fonts.FontUpdateRequest;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/* package */ class PersistentSystemFontConfig {
    private static final String TAG = "PersistentSystemFontConfig";

    private static final String TAG_ROOT = "fontConfig";
    private static final String TAG_LAST_MODIFIED_DATE = "lastModifiedDate";
    private static final String TAG_UPDATED_FONT_DIR = "updatedFontDir";
    private static final String TAG_FAMILY = "family";
    private static final String ATTR_VALUE = "value";

    /* package */ static class Config {
        public long lastModifiedMillis;
        public final Set<String> updatedFontDirs = new ArraySet<>();
        public final List<FontUpdateRequest.Family> fontFamilies = new ArrayList<>();
    }

    /**
     * Read config XML and write to out argument.
     */
    public static void loadFromXml(@NonNull InputStream is, @NonNull Config out)
            throws XmlPullParserException, IOException {
        TypedXmlPullParser parser = Xml.resolvePullParser(is);

        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            final int depth = parser.getDepth();
            final String tag = parser.getName();
            if (depth == 1) {
                if (!TAG_ROOT.equals(tag)) {
                    Slog.e(TAG, "Invalid root tag: " + tag);
                    return;
                }
            } else if (depth == 2) {
                switch (tag) {
                    case TAG_LAST_MODIFIED_DATE:
                        out.lastModifiedMillis = parseLongAttribute(parser, ATTR_VALUE, 0);
                        break;
                    case TAG_UPDATED_FONT_DIR:
                        out.updatedFontDirs.add(getAttribute(parser, ATTR_VALUE));
                        break;
                    case TAG_FAMILY:
                        // updatableFontMap is not ready here. We get the base file names by passing
                        // empty fontDir, and resolve font paths later.
                        out.fontFamilies.add(FontUpdateRequest.Family.readFromXml(parser));
                        break;
                    default:
                        Slog.w(TAG, "Skipping unknown tag: " + tag);
                }
            }
        }

    }

    /**
     * Write config to OutputStream as XML file.
     */
    public static void writeToXml(@NonNull OutputStream os, @NonNull Config config)
            throws IOException {
        TypedXmlSerializer out = Xml.resolveSerializer(os);
        out.startDocument(null /* encoding */, true /* standalone */);

        out.startTag(null, TAG_ROOT);
        out.startTag(null, TAG_LAST_MODIFIED_DATE);
        out.attribute(null, ATTR_VALUE, Long.toString(config.lastModifiedMillis));
        out.endTag(null, TAG_LAST_MODIFIED_DATE);
        for (String dir : config.updatedFontDirs) {
            out.startTag(null, TAG_UPDATED_FONT_DIR);
            out.attribute(null, ATTR_VALUE, dir);
            out.endTag(null, TAG_UPDATED_FONT_DIR);
        }
        List<FontUpdateRequest.Family> fontFamilies = config.fontFamilies;
        for (int i = 0; i < fontFamilies.size(); i++) {
            FontUpdateRequest.Family fontFamily = fontFamilies.get(i);
            out.startTag(null, TAG_FAMILY);
            FontUpdateRequest.Family.writeFamilyToXml(out, fontFamily);
            out.endTag(null, TAG_FAMILY);
        }
        out.endTag(null, TAG_ROOT);

        out.endDocument();
    }

    private static long parseLongAttribute(TypedXmlPullParser parser, String attr, long defValue) {
        final String value = parser.getAttributeValue(null /* namespace */, attr);
        if (TextUtils.isEmpty(value)) {
            return defValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    @NonNull
    private static String getAttribute(TypedXmlPullParser parser, String attr) {
        final String value = parser.getAttributeValue(null /* namespace */, attr);
        return value == null ? "" : value;
    }
}
