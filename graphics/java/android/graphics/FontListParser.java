/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.fonts.FontVariationAxis;
import android.text.FontConfig;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parser for font config files.
 *
 * @hide
 */
public class FontListParser {

    /* Parse fallback list (no names) */
    @UnsupportedAppUsage
    public static FontConfig parse(InputStream in) throws XmlPullParserException, IOException {
        return parse(in, "/system/fonts");
    }

    /**
     * Parse the fonts.xml
     */
    public static FontConfig parse(InputStream in, String fontDir)
            throws XmlPullParserException, IOException {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            parser.nextTag();
            return readFamilies(parser, fontDir);
        } finally {
            in.close();
        }
    }

    private static FontConfig readFamilies(XmlPullParser parser, String fontDir)
            throws XmlPullParserException, IOException {
        List<FontConfig.Family> families = new ArrayList<>();
        List<FontConfig.Alias> aliases = new ArrayList<>();

        parser.require(XmlPullParser.START_TAG, null, "familyset");
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if (tag.equals("family")) {
                families.add(readFamily(parser, fontDir));
            } else if (tag.equals("alias")) {
                aliases.add(readAlias(parser));
            } else {
                skip(parser);
            }
        }
        return new FontConfig(families.toArray(new FontConfig.Family[families.size()]),
                aliases.toArray(new FontConfig.Alias[aliases.size()]));
    }

    /**
     * Reads a family element
     */
    public static FontConfig.Family readFamily(XmlPullParser parser, String fontDir)
            throws XmlPullParserException, IOException {
        final String name = parser.getAttributeValue(null, "name");
        final String lang = parser.getAttributeValue("", "lang");
        final String variant = parser.getAttributeValue(null, "variant");
        final List<FontConfig.Font> fonts = new ArrayList<FontConfig.Font>();
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            final String tag = parser.getName();
            if (tag.equals("font")) {
                fonts.add(readFont(parser, fontDir));
            } else {
                skip(parser);
            }
        }
        int intVariant = FontConfig.Family.VARIANT_DEFAULT;
        if (variant != null) {
            if (variant.equals("compact")) {
                intVariant = FontConfig.Family.VARIANT_COMPACT;
            } else if (variant.equals("elegant")) {
                intVariant = FontConfig.Family.VARIANT_ELEGANT;
            }
        }
        return new FontConfig.Family(name, fonts.toArray(new FontConfig.Font[fonts.size()]), lang,
                intVariant);
    }

    /** Matches leading and trailing XML whitespace. */
    private static final Pattern FILENAME_WHITESPACE_PATTERN =
            Pattern.compile("^[ \\n\\r\\t]+|[ \\n\\r\\t]+$");

    private static FontConfig.Font readFont(XmlPullParser parser, String fontDir)
            throws XmlPullParserException, IOException {
        String indexStr = parser.getAttributeValue(null, "index");
        int index = indexStr == null ? 0 : Integer.parseInt(indexStr);
        List<FontVariationAxis> axes = new ArrayList<FontVariationAxis>();
        String weightStr = parser.getAttributeValue(null, "weight");
        int weight = weightStr == null ? 400 : Integer.parseInt(weightStr);
        boolean isItalic = "italic".equals(parser.getAttributeValue(null, "style"));
        String fallbackFor = parser.getAttributeValue(null, "fallbackFor");
        StringBuilder filename = new StringBuilder();
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() == XmlPullParser.TEXT) {
                filename.append(parser.getText());
            }
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if (tag.equals("axis")) {
                axes.add(readAxis(parser));
            } else {
                skip(parser);
            }
        }
        String sanitizedName = FILENAME_WHITESPACE_PATTERN.matcher(filename).replaceAll("");
        return new FontConfig.Font(fontDir + sanitizedName, index, axes.toArray(
                new FontVariationAxis[axes.size()]), weight, isItalic, fallbackFor);
    }

    private static FontVariationAxis readAxis(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        String tagStr = parser.getAttributeValue(null, "tag");
        String styleValueStr = parser.getAttributeValue(null, "stylevalue");
        skip(parser);  // axis tag is empty, ignore any contents and consume end tag
        return new FontVariationAxis(tagStr, Float.parseFloat(styleValueStr));
    }

    /**
     * Reads alias elements
     */
    public static FontConfig.Alias readAlias(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        String name = parser.getAttributeValue(null, "name");
        String toName = parser.getAttributeValue(null, "to");
        String weightStr = parser.getAttributeValue(null, "weight");
        int weight;
        if (weightStr == null) {
            weight = 400;
        } else {
            weight = Integer.parseInt(weightStr);
        }
        skip(parser);  // alias tag is empty, ignore any contents and consume end tag
        return new FontConfig.Alias(name, toName, weight);
    }

    /**
     * Skip until next element
     */
    public static void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
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
