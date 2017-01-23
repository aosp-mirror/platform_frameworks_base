/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.graphics;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parser for xml type font resources.
 * @hide
 */
public class FontResourcesParser {
    private static final String ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";

    /* Parse fallback list (no names) */
    public static FontListParser.Config parse(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int type;
        //noinspection StatementWithEmptyBody
        while ((type=parser.next()) != XmlPullParser.START_TAG
                && type != XmlPullParser.END_DOCUMENT) {
            // Empty loop.
        }

        if (type != XmlPullParser.START_TAG) {
            throw new XmlPullParserException("No start tag found");
        }
        return readFamilies(parser);
    }

    private static FontListParser.Config readFamilies(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        FontListParser.Config config = new FontListParser.Config();
        parser.require(XmlPullParser.START_TAG, null, "font-family");
        String tag = parser.getName();
        if (tag.equals("font-family")) {
            config.families.add(readFamily(parser));
        } else {
            skip(parser);
        }
        return config;
    }

    private static FontListParser.Family readFamily(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        String name = parser.getAttributeValue(null, "name");
        String lang = parser.getAttributeValue(null, "lang");
        String variant = parser.getAttributeValue(null, "variant");
        List<FontListParser.Font> fonts = new ArrayList<>();
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if (tag.equals("font")) {
                fonts.add(readFont(parser));
            } else {
                skip(parser);
            }
        }
        return new FontListParser.Family(name, fonts, lang, variant);
    }

    /** Matches leading and trailing XML whitespace. */
    private static final Pattern FILENAME_WHITESPACE_PATTERN =
            Pattern.compile("^[ \\n\\r\\t]+|[ \\n\\r\\t]+$");

    private static FontListParser.Font readFont(XmlPullParser parser)
            throws XmlPullParserException, IOException {

        List<FontListParser.Axis> axes = new ArrayList<>();

        String weightStr = parser.getAttributeValue(ANDROID_NAMESPACE, "fontWeight");
        int weight = weightStr == null ? 400 : Integer.parseInt(weightStr);

        boolean isItalic = "italic".equals(
                parser.getAttributeValue(ANDROID_NAMESPACE, "fontStyle"));

        String filename = parser.getAttributeValue(ANDROID_NAMESPACE, "font");
        String fullFilename = FILENAME_WHITESPACE_PATTERN.matcher(filename).replaceAll("");
        return new FontListParser.Font(fullFilename, 0, axes, weight, isItalic);
    }

    private static void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            switch (parser.next()) {
                case XmlPullParser.START_TAG:
                    depth++;
                    break;
                case XmlPullParser.END_TAG:
                    depth--;
                    break;
            }
        }
    }
}
