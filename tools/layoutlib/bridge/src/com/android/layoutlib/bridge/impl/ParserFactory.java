/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.layoutlib.bridge.impl;


import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

/**
 * A factory for {@link XmlPullParser}.
 *
 */
public class ParserFactory {

    private final static String ENCODING = "UTF-8"; //$NON-NLS-1$

    public final static boolean LOG_PARSER = false;

    public static XmlPullParser create(File f)
            throws XmlPullParserException, FileNotFoundException {
        KXmlParser parser = instantiateParser(f.getName());
        parser.setInput(new FileInputStream(f), ENCODING);
        return parser;
    }

    public static XmlPullParser create(InputStream stream, String name)
            throws XmlPullParserException {
        KXmlParser parser = instantiateParser(name);
        parser.setInput(stream, ENCODING);
        return parser;
    }

    private static KXmlParser instantiateParser(String name) throws XmlPullParserException {
        KXmlParser parser;
        if (name != null) {
            parser = new CustomParser(name);
        } else {
            parser = new KXmlParser();
        }
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        return parser;
    }

    private static class CustomParser extends KXmlParser {
        private final String mName;

        CustomParser(String name) {
            super();
            mName = name;
        }

        @Override
        public String toString() {
            return mName;
        }
    }
}
