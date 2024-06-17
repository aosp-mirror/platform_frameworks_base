/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Xml;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A clock that is similar to SystemClock#elapsedRealtime(), except that it is not reset
 * on reboot, but keeps going.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class MonotonicClock {
    private static final String TAG = "MonotonicClock";

    private static final String XML_TAG_MONOTONIC_TIME = "monotonic_time";
    private static final String XML_ATTR_TIMESHIFT = "timeshift";

    private final AtomicFile mFile;
    private final Clock mClock;
    private final long mTimeshift;

    public static final long UNDEFINED = -1;

    public MonotonicClock(File file) {
        this (file, Clock.SYSTEM_CLOCK.elapsedRealtime(), Clock.SYSTEM_CLOCK);
    }

    public MonotonicClock(long monotonicTime, @NonNull Clock clock) {
        this(null, monotonicTime, clock);
    }

    public MonotonicClock(@Nullable File file, long monotonicTime, @NonNull Clock clock) {
        mClock = clock;
        if (file != null) {
            mFile = new AtomicFile(file);
            mTimeshift = read(monotonicTime - mClock.elapsedRealtime());
        } else {
            mFile = null;
            mTimeshift = monotonicTime - mClock.elapsedRealtime();
        }
    }

    /**
     * Returns time in milliseconds, based on SystemClock.elapsedTime, adjusted so that
     * after a device reboot the time keeps increasing.
     */
    public long monotonicTime() {
        return monotonicTime(mClock.elapsedRealtime());
    }

    /**
     * Like {@link #monotonicTime()}, except the elapsed time is supplied as an argument instead
     * of being read from the Clock.
     */
    public long monotonicTime(long elapsedRealtimeMs) {
        return mTimeshift + elapsedRealtimeMs;
    }

    private long read(long defaultTimeshift) {
        if (!mFile.exists()) {
            return defaultTimeshift;
        }

        try {
            return readXml(new ByteArrayInputStream(mFile.readFully()), Xml.newBinaryPullParser());
        } catch (IOException e) {
            Log.e(TAG, "Cannot load monotonic clock from " + mFile.getBaseFile(), e);
            return defaultTimeshift;
        }
    }

    /**
     * Saves the timeshift into a file.  Call this method just before system shutdown, after
     * writing the last battery history event.
     */
    public void write() {
        if (mFile == null) {
            return;
        }

        FileOutputStream out = null;
        try  {
            out = mFile.startWrite();
            writeXml(out, Xml.newBinarySerializer());
            mFile.finishWrite(out);
        } catch (IOException e) {
            Log.e(TAG, "Cannot write monotonic clock to " + mFile.getBaseFile(), e);
            mFile.failWrite(out);
        }
    }

    /**
     * Parses an XML file containing the persistent state of the monotonic clock.
     */
    private long readXml(InputStream inputStream, TypedXmlPullParser parser) throws IOException {
        long savedTimeshift = 0;
        try {
            parser.setInput(inputStream, StandardCharsets.UTF_8.name());
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG
                        && parser.getName().equals(XML_TAG_MONOTONIC_TIME)) {
                    savedTimeshift = parser.getAttributeLong(null, XML_ATTR_TIMESHIFT);
                }
                eventType = parser.next();
            }
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        }
        return savedTimeshift - mClock.elapsedRealtime();
    }

    /**
     * Creates an XML file containing the persistent state of the monotonic clock.
     */
    private void writeXml(OutputStream out, TypedXmlSerializer serializer) throws IOException {
        serializer.setOutput(out, StandardCharsets.UTF_8.name());
        serializer.startDocument(null, true);
        serializer.startTag(null, XML_TAG_MONOTONIC_TIME);
        serializer.attributeLong(null, XML_ATTR_TIMESHIFT, monotonicTime());
        serializer.endTag(null, XML_TAG_MONOTONIC_TIME);
        serializer.endDocument();
    }
}
