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
package android.content.res;

import com.android.internal.R;
import android.text.FontConfig;
import android.util.AttributeSet;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for xml type font resources.
 * @hide
 */
public class FontResourcesParser {
    private static final int NORMAL_WEIGHT = 400;
    private static final int ITALIC = 1;

    public static FontConfig parse(XmlPullParser parser, Resources resources)
            throws XmlPullParserException, IOException {
        int type;
        while ((type=parser.next()) != XmlPullParser.START_TAG
                && type != XmlPullParser.END_DOCUMENT) {
            // Empty loop.
        }

        if (type != XmlPullParser.START_TAG) {
            throw new XmlPullParserException("No start tag found");
        }
        return readFamilies(parser, resources);
    }

    private static FontConfig readFamilies(XmlPullParser parser, Resources resources)
            throws XmlPullParserException, IOException {
        FontConfig config = new FontConfig();
        parser.require(XmlPullParser.START_TAG, null, "font-family");
        String tag = parser.getName();
        if (tag.equals("font-family")) {
            config.getFamilies().add(readFamily(parser, resources));
        } else {
            skip(parser);
        }
        return config;
    }

    private static FontConfig.Family readFamily(XmlPullParser parser, Resources resources)
            throws XmlPullParserException, IOException {
        List<FontConfig.Font> fonts = new ArrayList<>();
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if (tag.equals("font")) {
                fonts.add(readFont(parser, resources));
            } else {
                skip(parser);
            }
        }
        return new FontConfig.Family(null, fonts, null, null);
    }

    private static FontConfig.Font readFont(XmlPullParser parser, Resources resources)
            throws XmlPullParserException, IOException {
        AttributeSet attrs = Xml.asAttributeSet(parser);
        TypedArray array = resources.obtainAttributes(attrs, R.styleable.FontFamilyFont);
        int weight = array.getInt(R.styleable.FontFamilyFont_fontWeight, NORMAL_WEIGHT);
        boolean isItalic = ITALIC == array.getInt(R.styleable.FontFamilyFont_fontStyle, 0);
        String filename = array.getString(R.styleable.FontFamilyFont_font);
        array.recycle();
        while (parser.next() != XmlPullParser.END_TAG) {
            skip(parser);
        }
        return new FontConfig.Font(filename, 0, null, weight, isItalic);
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
