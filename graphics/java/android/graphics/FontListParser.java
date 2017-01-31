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

import android.text.FontConfig;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.annotation.Nullable;
import com.android.internal.annotations.VisibleForTesting;

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
    public static FontConfig parse(InputStream in) throws XmlPullParserException, IOException {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            parser.nextTag();
            return readFamilies(parser);
        } finally {
            in.close();
        }
    }

    // Note that a well-formed variation contains a four-character tag and a float as styleValue,
    // with spacers in between. The tag is enclosd either by double quotes or single quotes.
    @VisibleForTesting
    public static ArrayList<FontConfig.Axis> parseFontVariationSettings(@Nullable String settings) {
        ArrayList<FontConfig.Axis> axisList = new ArrayList<>();
        if (settings == null) {
            return axisList;
        }
        String[] settingList = settings.split(",");
        settingLoop:
        for (String setting : settingList) {
            int pos = 0;
            while (pos < setting.length()) {
                char c = setting.charAt(pos);
                if (c == '\'' || c == '"') {
                    break;
                } else if (!isSpacer(c)) {
                    continue settingLoop;  // Only spacers are allowed before tag appeared.
                }
                pos++;
            }
            if (pos + 7 > setting.length()) {
                continue;  // 7 is the minimum length of tag-style value pair text.
            }
            if (setting.charAt(pos) != setting.charAt(pos + 5)) {
                continue;  // Tag should be wrapped with double or single quote.
            }
            String tagString = setting.substring(pos + 1, pos + 5);
            if (!TAG_PATTERN.matcher(tagString).matches()) {
                continue;  // Skip incorrect format tag.
            }
            pos += 6;
            while (pos < setting.length()) {
                if (!isSpacer(setting.charAt(pos++))) {
                    break;  // Skip spacers between the tag and the styleValue.
                }
            }
            // Skip invalid styleValue
            float styleValue;
            String valueString = setting.substring(pos - 1);
            if (!STYLE_VALUE_PATTERN.matcher(valueString).matches()) {
                continue;  // Skip incorrect format styleValue.
            }
            try {
                styleValue = Float.parseFloat(valueString);
            } catch (NumberFormatException e) {
                continue;  // ignoreing invalid number format
            }
            int tag = makeTag(tagString.charAt(0), tagString.charAt(1), tagString.charAt(2),
                    tagString.charAt(3));
            axisList.add(new FontConfig.Axis(tag, styleValue));
        }
        return axisList;
    }

    @VisibleForTesting
    public static int makeTag(char c1, char c2, char c3, char c4) {
        return (c1 << 24) | (c2 << 16) | (c3 << 8) | c4;
    }

    private static boolean isSpacer(char c) {
        return c == ' ' || c == '\r' || c == '\t' || c == '\n';
    }

    private static FontConfig readFamilies(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        FontConfig config = new FontConfig();
        parser.require(XmlPullParser.START_TAG, null, "familyset");
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if (tag.equals("family")) {
                config.getFamilies().add(readFamily(parser));
            } else if (tag.equals("alias")) {
                config.getAliases().add(readAlias(parser));
            } else {
                skip(parser);
            }
        }
        return config;
    }

    private static FontConfig.Family readFamily(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        String name = parser.getAttributeValue(null, "name");
        String lang = parser.getAttributeValue(null, "lang");
        String variant = parser.getAttributeValue(null, "variant");
        List<FontConfig.Font> fonts = new ArrayList<FontConfig.Font>();
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if (tag.equals("font")) {
                fonts.add(readFont(parser));
            } else {
                skip(parser);
            }
        }
        return new FontConfig.Family(name, fonts, lang, variant);
    }

    /** Matches leading and trailing XML whitespace. */
    private static final Pattern FILENAME_WHITESPACE_PATTERN =
            Pattern.compile("^[ \\n\\r\\t]+|[ \\n\\r\\t]+$");

    private static FontConfig.Font readFont(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        String indexStr = parser.getAttributeValue(null, "index");
        int index = indexStr == null ? 0 : Integer.parseInt(indexStr);
        List<FontConfig.Axis> axes = new ArrayList<FontConfig.Axis>();
        String weightStr = parser.getAttributeValue(null, "weight");
        int weight = weightStr == null ? 400 : Integer.parseInt(weightStr);
        boolean isItalic = "italic".equals(parser.getAttributeValue(null, "style"));
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
        String fullFilename = "/system/fonts/" +
                FILENAME_WHITESPACE_PATTERN.matcher(filename).replaceAll("");
        return new FontConfig.Font(fullFilename, index, axes, weight, isItalic);
    }

    /** The 'tag' attribute value is read as four character values between U+0020 and U+007E
     *  inclusive.
     */
    private static final Pattern TAG_PATTERN = Pattern.compile("[\\x20-\\x7E]{4}");

    /** The 'styleValue' attribute has an optional leading '-', followed by '<digits>',
     *  '<digits>.<digits>', or '.<digits>' where '<digits>' is one or more of [0-9].
     */
    private static final Pattern STYLE_VALUE_PATTERN =
            Pattern.compile("-?(([0-9]+(\\.[0-9]+)?)|(\\.[0-9]+))");

    private static FontConfig.Axis readAxis(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int tag = 0;
        String tagStr = parser.getAttributeValue(null, "tag");
        if (tagStr != null && TAG_PATTERN.matcher(tagStr).matches()) {
            tag = makeTag(tagStr.charAt(0), tagStr.charAt(1), tagStr.charAt(2), tagStr.charAt(3));
        } else {
            throw new XmlPullParserException("Invalid tag attribute value.", parser, null);
        }

        float styleValue = 0;
        String styleValueStr = parser.getAttributeValue(null, "stylevalue");
        if (styleValueStr != null && STYLE_VALUE_PATTERN.matcher(styleValueStr).matches()) {
            styleValue = Float.parseFloat(styleValueStr);
        } else {
            throw new XmlPullParserException("Invalid styleValue attribute value.", parser, null);
        }

        skip(parser);  // axis tag is empty, ignore any contents and consume end tag
        return new FontConfig.Axis(tag, styleValue);
    }

    private static FontConfig.Alias readAlias(XmlPullParser parser)
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
