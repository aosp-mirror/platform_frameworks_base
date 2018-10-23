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

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.app.WindowConfiguration;
import android.graphics.Rect;
import android.os.Environment;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import com.android.server.policy.WindowManagerPolicy;

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
class DisplaySettings {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "DisplaySettings" : TAG_WM;

    private final WindowManagerService mService;
    private final AtomicFile mFile;
    private final HashMap<String, Entry> mEntries = new HashMap<String, Entry>();

    private static class Entry {
        private final String mName;
        private int mOverscanLeft;
        private int mOverscanTop;
        private int mOverscanRight;
        private int mOverscanBottom;
        private int mWindowingMode = WindowConfiguration.WINDOWING_MODE_UNDEFINED;
        private int mUserRotationMode = WindowManagerPolicy.USER_ROTATION_FREE;
        private int mUserRotation = Surface.ROTATION_0;

        private Entry(String _name) {
            mName = _name;
        }

        private boolean isEmpty() {
            return mOverscanLeft == 0 && mOverscanTop == 0 && mOverscanRight == 0
                    && mOverscanBottom == 0
                    && mWindowingMode == WindowConfiguration.WINDOWING_MODE_UNDEFINED
                    && mUserRotationMode == WindowManagerPolicy.USER_ROTATION_FREE
                    && mUserRotation == Surface.ROTATION_0;
        }
    }

    DisplaySettings(WindowManagerService service) {
        this(service, new File(Environment.getDataDirectory(), "system"));
    }

    @VisibleForTesting
    DisplaySettings(WindowManagerService service, File folder) {
        mService = service;
        mFile = new AtomicFile(new File(folder, "display_settings.xml"), "wm-displays");
    }

    private Entry getEntry(String name, String uniqueId) {
        // Try to get the entry with the unique if possible.
        // Else, fall back on the display name.
        Entry entry;
        if (uniqueId == null || (entry = mEntries.get(uniqueId)) == null) {
            entry = mEntries.get(name);
        }
        return entry;
    }

    private Entry getOrCreateEntry(String uniqueId, String name) {
        Entry entry = getEntry(uniqueId, name);
        if (entry == null) {
            entry = new Entry(uniqueId);
            mEntries.put(uniqueId, entry);
        }
        return entry;
    }

    private void removeEntryIfEmpty(String uniqueId, String name) {
        final Entry entry = getEntry(uniqueId, name);
        if (entry.isEmpty()) {
            mEntries.remove(uniqueId);
            mEntries.remove(name);
        }
    }

    private void getOverscanLocked(String name, String uniqueId, Rect outRect) {
        final Entry entry = getEntry(name, uniqueId);
        if (entry != null) {
            outRect.left = entry.mOverscanLeft;
            outRect.top = entry.mOverscanTop;
            outRect.right = entry.mOverscanRight;
            outRect.bottom = entry.mOverscanBottom;
        } else {
            outRect.set(0, 0, 0, 0);
        }
    }

    void setOverscanLocked(String uniqueId, String name, int left, int top, int right,
            int bottom) {
        Entry entry = mEntries.get(uniqueId);
        if (left == 0 && top == 0 && right == 0 && bottom == 0 && entry == null) {
            // All default value, no action needed.
            return;
        }
        entry = getOrCreateEntry(uniqueId, name);
        entry.mOverscanLeft = left;
        entry.mOverscanTop = top;
        entry.mOverscanRight = right;
        entry.mOverscanBottom = bottom;
        removeEntryIfEmpty(uniqueId, name);
    }

    private int getWindowingModeLocked(String name, String uniqueId, int displayId) {
        final Entry entry = getEntry(name, uniqueId);
        int windowingMode = entry != null ? entry.mWindowingMode
                : WindowConfiguration.WINDOWING_MODE_UNDEFINED;
        // This display used to be in freeform, but we don't support freeform anymore, so fall
        // back to fullscreen.
        if (windowingMode == WindowConfiguration.WINDOWING_MODE_FREEFORM
                && !mService.mSupportsFreeformWindowManagement) {
            return WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
        }
        // No record is present so use default windowing mode policy.
        if (windowingMode == WindowConfiguration.WINDOWING_MODE_UNDEFINED) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                windowingMode = (mService.mIsPc && mService.mSupportsFreeformWindowManagement)
                        ? WindowConfiguration.WINDOWING_MODE_FREEFORM
                        : WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
            } else {
                windowingMode = mService.mSupportsFreeformWindowManagement
                        ? WindowConfiguration.WINDOWING_MODE_FREEFORM
                        : WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
            }
        }
        return windowingMode;
    }

    void setUserRotation(DisplayContent dc, int rotationMode, int rotation) {
        final DisplayInfo displayInfo = dc.getDisplayInfo();

        final String uniqueId = displayInfo.uniqueId;
        final String name = displayInfo.name;
        Entry entry = getEntry(displayInfo.name, uniqueId);
        if (rotationMode == WindowManagerPolicy.USER_ROTATION_FREE
                && rotation == Surface.ROTATION_0 && entry == null) {
            // All default values. No action needed.
            return;
        }

        entry = getOrCreateEntry(uniqueId, name);
        entry.mUserRotationMode = rotationMode;
        entry.mUserRotation = rotation;
        removeEntryIfEmpty(uniqueId, name);
    }

    private void restoreUserRotation(DisplayContent dc) {
        final DisplayInfo info = dc.getDisplayInfo();

        final Entry entry = getEntry(info.name, info.uniqueId);
        final int userRotationMode = entry != null ? entry.mUserRotationMode
                : WindowManagerPolicy.USER_ROTATION_FREE;
        final int userRotation = entry != null ? entry.mUserRotation
                : Surface.ROTATION_0;

        dc.getDisplayRotation().restoreUserRotation(userRotationMode, userRotation);
    }

    void applySettingsToDisplayLocked(DisplayContent dc) {
        final DisplayInfo displayInfo = dc.getDisplayInfo();

        // Setting windowing mode first, because it may override overscan values later.
        dc.setWindowingMode(getWindowingModeLocked(displayInfo.name, displayInfo.uniqueId,
                dc.getDisplayId()));

        final Rect rect = new Rect();
        getOverscanLocked(displayInfo.name, displayInfo.uniqueId, rect);
        displayInfo.overscanLeft = rect.left;
        displayInfo.overscanTop = rect.top;
        displayInfo.overscanRight = rect.right;
        displayInfo.overscanBottom = rect.bottom;

        restoreUserRotation(dc);
    }

    void readSettingsLocked() {
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
        return getIntAttribute(parser, name, 0 /* defaultValue */);
    }

    private int getIntAttribute(XmlPullParser parser, String name, int defaultValue) {
        try {
            String str = parser.getAttributeValue(null, name);
            return str != null ? Integer.parseInt(str) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void readDisplay(XmlPullParser parser) throws NumberFormatException,
            XmlPullParserException, IOException {
        String name = parser.getAttributeValue(null, "name");
        if (name != null) {
            Entry entry = new Entry(name);
            entry.mOverscanLeft = getIntAttribute(parser, "overscanLeft");
            entry.mOverscanTop = getIntAttribute(parser, "overscanTop");
            entry.mOverscanRight = getIntAttribute(parser, "overscanRight");
            entry.mOverscanBottom = getIntAttribute(parser, "overscanBottom");
            entry.mWindowingMode = getIntAttribute(parser, "windowingMode",
                    WindowConfiguration.WINDOWING_MODE_UNDEFINED);
            entry.mUserRotationMode = getIntAttribute(parser, "userRotationMode",
                    WindowManagerPolicy.USER_ROTATION_FREE);
            entry.mUserRotation = getIntAttribute(parser, "userRotation",
                    Surface.ROTATION_0);
            mEntries.put(name, entry);
        }
        XmlUtils.skipCurrentTag(parser);
    }

    void writeSettingsLocked() {
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
                out.attribute(null, "name", entry.mName);
                if (entry.mOverscanLeft != 0) {
                    out.attribute(null, "overscanLeft", Integer.toString(entry.mOverscanLeft));
                }
                if (entry.mOverscanTop != 0) {
                    out.attribute(null, "overscanTop", Integer.toString(entry.mOverscanTop));
                }
                if (entry.mOverscanRight != 0) {
                    out.attribute(null, "overscanRight", Integer.toString(entry.mOverscanRight));
                }
                if (entry.mOverscanBottom != 0) {
                    out.attribute(null, "overscanBottom", Integer.toString(entry.mOverscanBottom));
                }
                if (entry.mWindowingMode != WindowConfiguration.WINDOWING_MODE_UNDEFINED) {
                    out.attribute(null, "windowingMode", Integer.toString(entry.mWindowingMode));
                }
                if (entry.mUserRotationMode != WindowManagerPolicy.USER_ROTATION_FREE) {
                    out.attribute(null, "userRotationMode",
                            Integer.toString(entry.mUserRotationMode));
                }
                if (entry.mUserRotation != Surface.ROTATION_0) {
                    out.attribute(null, "userRotation", Integer.toString(entry.mUserRotation));
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
