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

import android.annotation.NonNull;
import android.graphics.FontListParser;
import android.text.FontConfig;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Parser for font customization
 *
 * @hide
 */
public class FontCustomizationParser {
    /**
     * Represents a customization XML
     */
    public static class Result {
        ArrayList<FontConfig.Family> mAdditionalNamedFamilies = new ArrayList<>();
        ArrayList<FontConfig.Alias> mAdditionalAliases = new ArrayList<>();
    }

    /**
     * Parses the customization XML
     *
     * Caller must close the input stream
     */
    public static Result parse(@NonNull InputStream in, @NonNull String fontDir)
            throws XmlPullParserException, IOException {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(in, null);
        parser.nextTag();
        return readFamilies(parser, fontDir);
    }

    private static void validate(Result result) {
        HashSet<String> familyNames = new HashSet<>();
        for (int i = 0; i < result.mAdditionalNamedFamilies.size(); ++i) {
            final FontConfig.Family family = result.mAdditionalNamedFamilies.get(i);
            final String name = family.getName();
            if (name == null) {
                throw new IllegalArgumentException("new-named-family requires name attribute");
            }
            if (!familyNames.add(name)) {
                throw new IllegalArgumentException(
                        "new-named-family requires unique name attribute");
            }
        }
    }

    private static Result readFamilies(XmlPullParser parser, String fontDir)
            throws XmlPullParserException, IOException {
        Result out = new Result();
        parser.require(XmlPullParser.START_TAG, null, "fonts-modification");
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if (tag.equals("family")) {
                readFamily(parser, fontDir, out);
            } else if (tag.equals("alias")) {
                out.mAdditionalAliases.add(FontListParser.readAlias(parser));
            } else {
                FontListParser.skip(parser);
            }
        }
        validate(out);
        return out;
    }

    private static void readFamily(XmlPullParser parser, String fontDir, Result out)
            throws XmlPullParserException, IOException {
        final String customizationType = parser.getAttributeValue(null, "customizationType");
        if (customizationType == null) {
            throw new IllegalArgumentException("customizationType must be specified");
        }
        if (customizationType.equals("new-named-family")) {
            out.mAdditionalNamedFamilies.add(FontListParser.readFamily(parser, fontDir));
        } else {
            throw new IllegalArgumentException("Unknown customizationType=" + customizationType);
        }
    }
}
