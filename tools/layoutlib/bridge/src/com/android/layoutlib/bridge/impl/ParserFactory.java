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


import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.annotation.NonNull;
import android.annotation.Nullable;

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

    public final static boolean LOG_PARSER = false;

    private final static String ENCODING = "UTF-8"; //$NON-NLS-1$

    // Used to get a new XmlPullParser from the client.
    @Nullable
    private static com.android.ide.common.rendering.api.ParserFactory sParserFactory;

    public static void setParserFactory(
            @Nullable com.android.ide.common.rendering.api.ParserFactory parserFactory) {
        sParserFactory = parserFactory;
    }

    @NonNull
    public static XmlPullParser create(@NonNull File f)
            throws XmlPullParserException, FileNotFoundException {
        return create(f, false);
    }

    public static XmlPullParser create(@NonNull File f, boolean isLayout)
      throws XmlPullParserException, FileNotFoundException {
        InputStream stream = new FileInputStream(f);
        return create(stream, f.getName(), f.length(), isLayout);
    }
    @NonNull
    public static XmlPullParser create(@NonNull InputStream stream, @Nullable String name)
        throws XmlPullParserException {
        return create(stream, name, -1, false);
    }

    @NonNull
    private static XmlPullParser create(@NonNull InputStream stream, @Nullable String name,
            long size, boolean isLayout) throws XmlPullParserException {
        XmlPullParser parser = instantiateParser(name);

        stream = readAndClose(stream, name, size);

        parser.setInput(stream, ENCODING);
        if (isLayout) {
            try {
                return new LayoutParserWrapper(parser).peekTillLayoutStart();
            } catch (IOException e) {
                throw new XmlPullParserException(null, parser, e);
            }
        }
        return parser;
    }

    @NonNull
    public static XmlPullParser instantiateParser(@Nullable String name)
            throws XmlPullParserException {
        if (sParserFactory == null) {
            throw new XmlPullParserException("ParserFactory not initialized.");
        }
        XmlPullParser parser = sParserFactory.createParser(name);
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        return parser;
    }

    @NonNull
    private static InputStream readAndClose(@NonNull InputStream stream, @Nullable String name,
            long size) throws XmlPullParserException {
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
            } catch (IOException ignored) {
            }
        }
    }
}
