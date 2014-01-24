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

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
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
        InputStream stream = new FileInputStream(f);
        return create(stream, f.getName(), f.length());
    }

    public static XmlPullParser create(InputStream stream, String name)
        throws XmlPullParserException {
        return create(stream, name, -1);
    }

    private static XmlPullParser create(InputStream stream, String name, long size)
            throws XmlPullParserException {
        KXmlParser parser = instantiateParser(name);

        stream = readAndClose(stream, name, size);

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

    private static InputStream readAndClose(InputStream stream, String name, long size)
            throws XmlPullParserException {
        // just a sanity check. It's doubtful we'll have such big files!
        if (size > Integer.MAX_VALUE) {
            throw new XmlPullParserException("File " + name + " is too big to be parsed");
        }
        int intSize = (int) size;

        // create a buffered reader to facilitate reading.
        BufferedInputStream bufferedStream = new BufferedInputStream(stream);
        try {
            int avail;
            if (intSize != -1) {
                avail = intSize;
            } else {
                // get the size to read.
                avail = bufferedStream.available();
            }

            // create the initial buffer and read it.
            byte[] buffer = new byte[avail];
            int read = stream.read(buffer);

            // this is the easy case.
            if (read == intSize) {
                return new ByteArrayInputStream(buffer);
            }

            // check if there is more to read (read() does not necessarily read all that
            // available() returned!)
            while ((avail = bufferedStream.available()) > 0) {
                if (read + avail > buffer.length) {
                    // just allocate what is needed. We're mostly reading small files
                    // so it shouldn't be too problematic.
                    byte[] moreBuffer = new byte[read + avail];
                    System.arraycopy(buffer, 0, moreBuffer, 0, read);
                    buffer = moreBuffer;
                }

                read += stream.read(buffer, read, avail);
            }

            // return a new stream encapsulating this buffer.
            return new ByteArrayInputStream(buffer);

        } catch (IOException e) {
            throw new XmlPullParserException("Failed to read " + name, null, e);
        } finally {
            try {
                bufferedStream.close();
            } catch (IOException e) {
            }
        }
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
