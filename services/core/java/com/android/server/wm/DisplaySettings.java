/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.wm;

import android.graphics.Rect;
import android.os.Environment;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * Current persistent settings about a display
 */
public class DisplaySettings {
    private static final String TAG = WindowManagerService.TAG;

    private final AtomicFile mFile;
    private final HashMap<String, Entry> mEntries = new HashMap<String, Entry>();

    public static class Entry {
        public final String name;
        public int overscanLeft;
        public int overscanTop;
        public int overscanRight;
        public int overscanBottom;

        public Entry(String _name) {
            name = _name;
        }
    }

    public DisplaySettings() {
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        mFile = new AtomicFile(new File(systemDir, "display_settings.xml"));
    }

    public void getOverscanLocked(String name, String uniqueId, Rect outRect) {
        // Try to get the entry with the unique if possible.
        // Else, fall back on the display name.
        Entry entry;
        if (uniqueId == null || (entry = mEntries.get(uniqueId)) == null) {
            entry = mEntries.get(name);
        }
        if (entry != null) {
            outRect.left = entry.overscanLeft;
            outRect.top = entry.overscanTop;
            outRect.right = entry.overscanRight;
            outRect.bottom = entry.overscanBottom;
        } else {
            outRect.set(0, 0, 0, 0);
        }
    }

    public void setOverscanLocked(String name, int left, int top, int right, int bottom) {
        if (left == 0 && top == 0 && right == 0 && bottom == 0) {
            // Right now all we are storing is overscan; if there is no overscan,
            // we have no need for the entry.
            mEntries.remove(name);
            return;
        }
        Entry entry = mEntries.get(name);
        if (entry == null) {
            entry = new Entry(name);
            mEntries.put(name, entry);
        }
        entry.overscanLeft = left;
        entry.overscanTop = top;
        entry.overscanRight = right;
        entry.overscanBottom = bottom;
    }

    public void readSettingsLocked() {
        FileInputStream stream;
        try {
            stream = mFile.openRead();
        } catch (FileNotFoundException e) {
            Slog.i(TAG, "No existing display settings " + mFile.getBaseFile()
                    + "; starting empty");
            return;
        }
        boolean success = false;
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, StandardCharsets.UTF_8.name());
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                // Do nothing.
            }

            if (type != XmlPullParser.START_TAG) {
                throw new IllegalStateException("no start tag found");
            }

            int outerDepth = parser.getDepth();
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }

                String tagName = parser.getName();
                if (tagName.equals("display")) {
                    readDisplay(parser);
                } else {
                    Slog.w(TAG, "Unknown element under <display-settings>: "
                            + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }
            success = true;
        } catch (IllegalStateException e) {
            Slog.w(TAG, "Failed parsing " + e);
        } catch (NullPointerException e) {
            Slog.w(TAG, "Failed parsing " + e);
        } catch (NumberFormatException e) {
            Slog.w(TAG, "Failed parsing " + e);
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "Failed parsing " + e);
        } catch (IOException e) {
            Slog.w(TAG, "Failed parsing " + e);
        } catch (IndexOutOfBoundsException e) {
            Slog.w(TAG, "Failed parsing " + e);
        } finally {
            if (!success) {
                mEntries.clear();
            }
            try {
                stream.close();
            } catch (IOException e) {
            }
        }
    }

    private int getIntAttribute(XmlPullParser parser, String name) {
        try {
            String str = parser.getAttributeValue(null, name);
            return str != null ? Integer.parseInt(str) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void readDisplay(XmlPullParser parser) throws NumberFormatException,
            XmlPullParserException, IOException {
        String name = parser.getAttributeValue(null, "name");
        if (name != null) {
            Entry entry = new Entry(name);
            entry.overscanLeft = getIntAttribute(parser, "overscanLeft");
            entry.overscanTop = getIntAttribute(parser, "overscanTop");
            entry.overscanRight = getIntAttribute(parser, "overscanRight");
            entry.overscanBottom = getIntAttribute(parser, "overscanBottom");
            mEntries.put(name, entry);
        }
        XmlUtils.skipCurrentTag(parser);
    }

    public void writeSettingsLocked() {
        FileOutputStream stream;
        try {
            stream = mFile.startWrite();
        } catch (IOException e) {
            Slog.w(TAG, "Failed to write display settings: " + e);
            return;
        }

        try {
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(stream, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);
            out.startTag(null, "display-settings");

            for (Entry entry : mEntries.values()) {
                out.startTag(null, "display");
                out.attribute(null, "name", entry.name);
                if (entry.overscanLeft != 0) {
                    out.attribute(null, "overscanLeft", Integer.toString(entry.overscanLeft));
                }
                if (entry.overscanTop != 0) {
                    out.attribute(null, "overscanTop", Integer.toString(entry.overscanTop));
                }
                if (entry.overscanRight != 0) {
                    out.attribute(null, "overscanRight", Integer.toString(entry.overscanRight));
                }
                if (entry.overscanBottom != 0) {
                    out.attribute(null, "overscanBottom", Integer.toString(entry.overscanBottom));
                }
                out.endTag(null, "display");
            }

            out.endTag(null, "display-settings");
            out.endDocument();
            mFile.finishWrite(stream);
        } catch (IOException e) {
            Slog.w(TAG, "Failed to write display settings, restoring backup.", e);
            mFile.failWrite(stream);
        }
    }
}
