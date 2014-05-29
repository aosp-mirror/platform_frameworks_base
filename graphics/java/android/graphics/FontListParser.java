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

/**
 * Parser for font config files.
 *
 * @hide
 */
public class FontListParser {

    public static class Family {
        public Family(List<String> names, List<String> fontFiles, String lang, String variant) {
            this.names = names;
            this.fontFiles = fontFiles;
            this.lang = lang;
            this.variant = variant;
        }

        public List<String> names;
        // todo: need attributes for font files
        public List<String> fontFiles;
        public String lang;
        public String variant;
    }

    /* Parse fallback list (no names) */
    public static List<Family> parse(InputStream in) throws XmlPullParserException, IOException {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            parser.nextTag();
            return readFamilies(parser);
        } finally {
            in.close();
        }
    }

    private static List<Family> readFamilies(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        List<Family> families = new ArrayList<Family>();
        parser.require(XmlPullParser.START_TAG, null, "familyset");
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if (parser.getName().equals("family")) {
                families.add(readFamily(parser));
            } else {
                skip(parser);
            }
        }
        return families;
    }

    private static Family readFamily(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        List<String> names = null;
        List<String> fontFiles = new ArrayList<String>();
        String lang = null;
        String variant = null;
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if (tag.equals("fileset")) {
                while (parser.next() != XmlPullParser.END_TAG) {
                    if (parser.getEventType() != XmlPullParser.START_TAG) continue;
                    if (parser.getName().equals("file")) {
                        if (lang == null) {
                            lang = parser.getAttributeValue(null, "lang");
                        }
                        if (variant == null) {
                            variant = parser.getAttributeValue(null, "variant");
                        }
                        String filename = parser.nextText();
                        String fullFilename = "/system/fonts/" + filename;
                        fontFiles.add(fullFilename);
                    }
                }
            } else if (tag.equals("nameset")) {
                names = new ArrayList<String>();
                while (parser.next() != XmlPullParser.END_TAG) {
                    if (parser.getEventType() != XmlPullParser.START_TAG) continue;
                    if (parser.getName().equals("name")) {
                        String name = parser.nextText();
                        names.add(name);
                    }
                }
            }
        }
        return new Family(names, fontFiles, lang, variant);
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
