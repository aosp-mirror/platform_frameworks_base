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

    public static class Config {
        Config() {
            families = new ArrayList<Family>();
            aliases = new ArrayList<Alias>();
        }
        public List<Family> families;
        public List<Alias> aliases;
    }

    public static class Axis {
        Axis(int tag, float styleValue) {
            this.tag = tag;
            this.styleValue = styleValue;
        }
        public final int tag;
        public final float styleValue;
    }

    public static class Font {
        Font(String fontName, int ttcIndex, List<Axis> axes, int weight, boolean isItalic) {
            this.fontName = fontName;
            this.ttcIndex = ttcIndex;
            this.axes = axes;
            this.weight = weight;
            this.isItalic = isItalic;
        }
        public String fontName;
        public int ttcIndex;
        public final List<Axis> axes;
        public int weight;
        public boolean isItalic;
    }

    public static class Alias {
        public String name;
        public String toName;
        public int weight;
    }

    public static class Family {
        public Family(String name, List<Font> fonts, String lang, String variant) {
            this.name = name;
            this.fonts = fonts;
            this.lang = lang;
            this.variant = variant;
        }

        public String name;
        public List<Font> fonts;
        public String lang;
        public String variant;
    }

    /* Parse fallback list (no names) */
    public static Config parse(InputStream in) throws XmlPullParserException, IOException {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            parser.nextTag();
            return readFamilies(parser);
        } finally {
            in.close();
        }
    }

    private static Config readFamilies(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        Config config = new Config();
        parser.require(XmlPullParser.START_TAG, null, "familyset");
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if (tag.equals("family")) {
                config.families.add(readFamily(parser));
            } else if (tag.equals("alias")) {
                config.aliases.add(readAlias(parser));
            } else {
                skip(parser);
            }
        }
        return config;
    }

    private static Family readFamily(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        String name = parser.getAttributeValue(null, "name");
        String lang = parser.getAttributeValue(null, "lang");
        String variant = parser.getAttributeValue(null, "variant");
        List<Font> fonts = new ArrayList<Font>();
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if (tag.equals("font")) {
                fonts.add(readFont(parser));
            } else {
                skip(parser);
            }
        }
        return new Family(name, fonts, lang, variant);
    }

    /** Matches leading and trailing XML whitespace. */
    private static final Pattern FILENAME_WHITESPACE_PATTERN =
            Pattern.compile("^[ \\n\\r\\t]+|[ \\n\\r\\t]+$");

    private static Font readFont(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        String indexStr = parser.getAttributeValue(null, "index");
        int index = indexStr == null ? 0 : Integer.parseInt(indexStr);
        List<Axis> axes = new ArrayList<Axis>();
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
        return new Font(fullFilename, index, axes, weight, isItalic);
    }

    /** The 'tag' attribute value is read as four character values between 0 and 255 inclusive. */
    private static final Pattern TAG_PATTERN = Pattern.compile("[\\x00-\\xFF]{4}");

    /** The 'styleValue' attribute has an optional leading '-', followed by '<digits>',
     *  '<digits>.<digits>', or '.<digits>' where '<digits>' is one or more of [0-9].
     */
    private static final Pattern STYLE_VALUE_PATTERN =
            Pattern.compile("-?(([0-9]+(\\.[0-9]+)?)|(\\.[0-9]+))");

    private static Axis readAxis(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int tag = 0;
        String tagStr = parser.getAttributeValue(null, "tag");
        if (tagStr != null && TAG_PATTERN.matcher(tagStr).matches()) {
            tag = (tagStr.charAt(0) << 24) +
                  (tagStr.charAt(1) << 16) +
                  (tagStr.charAt(2) <<  8) +
                  (tagStr.charAt(3)      );
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
        return new Axis(tag, styleValue);
    }

    private static Alias readAlias(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        Alias alias = new Alias();
        alias.name = parser.getAttributeValue(null, "name");
        alias.toName = parser.getAttributeValue(null, "to");
        String weightStr = parser.getAttributeValue(null, "weight");
        if (weightStr == null) {
            alias.weight = 400;
        } else {
            alias.weight = Integer.parseInt(weightStr);
        }
        skip(parser);  // alias tag is empty, ignore any contents and consume end tag
        return alias;
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
