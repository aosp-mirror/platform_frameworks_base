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

import static android.view.WindowManager.REMOVE_CONTENT_MODE_DESTROY;
import static android.view.WindowManager.REMOVE_CONTENT_MODE_MOVE_TO_PRIMARY;
import static android.view.WindowManager.REMOVE_CONTENT_MODE_UNDEFINED;

import static com.android.server.wm.DisplayContent.FORCE_SCALING_MODE_AUTO;
import static com.android.server.wm.DisplayContent.FORCE_SCALING_MODE_DISABLED;
import static com.android.server.wm.DisplayRotation.FIXED_TO_USER_ROTATION_DEFAULT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.app.WindowConfiguration;
import android.os.Environment;
import android.provider.Settings;
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
import com.android.server.wm.DisplayContent.ForceScalingMode;

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
class DisplayWindowSettings {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "DisplayWindowSettings" : TAG_WM;

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
        private int mForcedWidth;
        private int mForcedHeight;
        private int mForcedDensity;
        private int mForcedScalingMode = FORCE_SCALING_MODE_AUTO;
        private int mRemoveContentMode = REMOVE_CONTENT_MODE_UNDEFINED;
        private boolean mShouldShowWithInsecureKeyguard = false;
        private boolean mShouldShowSystemDecors = false;
        private boolean mShouldShowIme = false;
        private @DisplayRotation.FixedToUserRotation int mFixedToUserRotation =
                FIXED_TO_USER_ROTATION_DEFAULT;

        private Entry(String name) {
            mName = name;
        }

        /** @return {@code true} if all values are default. */
        private boolean isEmpty() {
            return mOverscanLeft == 0 && mOverscanTop == 0 && mOverscanRight == 0
                    && mOverscanBottom == 0
                    && mWindowingMode == WindowConfiguration.WINDOWING_MODE_UNDEFINED
                    && mUserRotationMode == WindowManagerPolicy.USER_ROTATION_FREE
                    && mUserRotation == Surface.ROTATION_0
                    && mForcedWidth == 0 && mForcedHeight == 0 && mForcedDensity == 0
                    && mForcedScalingMode == FORCE_SCALING_MODE_AUTO
                    && mRemoveContentMode == REMOVE_CONTENT_MODE_UNDEFINED
                    && !mShouldShowWithInsecureKeyguard
                    && !mShouldShowSystemDecors
                    && !mShouldShowIme
                    && mFixedToUserRotation == FIXED_TO_USER_ROTATION_DEFAULT;
        }
    }

    DisplayWindowSettings(WindowManagerService service) {
        this(service, new File(Environment.getDataDirectory(), "system"));
    }

    @VisibleForTesting
    DisplayWindowSettings(WindowManagerService service, File folder) {
        mService = service;
        mFile = new AtomicFile(new File(folder, "display_settings.xml"), "wm-displays");
        readSettings();
    }

    private Entry getEntry(DisplayInfo displayInfo) {
        // Try to get the entry with the unique if possible.
        // Else, fall back on the display name.
        Entry entry;
        if (displayInfo.uniqueId == null || (entry = mEntries.get(displayInfo.uniqueId)) == null) {
            entry = mEntries.get(displayInfo.name);
        }
        return entry;
    }

    private Entry getOrCreateEntry(DisplayInfo displayInfo) {
        final Entry entry = getEntry(displayInfo);
        return entry != null ? entry : new Entry(displayInfo.uniqueId);
    }

    void setOverscanLocked(DisplayInfo displayInfo, int left, int top, int right, int bottom) {
        final Entry entry = getOrCreateEntry(displayInfo);
        entry.mOverscanLeft = left;
        entry.mOverscanTop = top;
        entry.mOverscanRight = right;
        entry.mOverscanBottom = bottom;
        writeSettingsIfNeeded(entry, displayInfo);
    }

    void setUserRotation(DisplayContent displayContent, int rotationMode, int rotation) {
        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        final Entry entry = getOrCreateEntry(displayInfo);
        entry.mUserRotationMode = rotationMode;
        entry.mUserRotation = rotation;
        writeSettingsIfNeeded(entry, displayInfo);
    }

    void setForcedSize(DisplayContent displayContent, int width, int height) {
        if (displayContent.isDefaultDisplay) {
            final String sizeString = (width == 0 || height == 0) ? "" : (width + "," + height);
            Settings.Global.putString(mService.mContext.getContentResolver(),
                    Settings.Global.DISPLAY_SIZE_FORCED, sizeString);
            return;
        }

        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        final Entry entry = getOrCreateEntry(displayInfo);
        entry.mForcedWidth = width;
        entry.mForcedHeight = height;
        writeSettingsIfNeeded(entry, displayInfo);
    }

    void setForcedDensity(DisplayContent displayContent, int density, int userId) {
        if (displayContent.isDefaultDisplay) {
            final String densityString = density == 0 ? "" : Integer.toString(density);
            Settings.Secure.putStringForUser(mService.mContext.getContentResolver(),
                    Settings.Secure.DISPLAY_DENSITY_FORCED, densityString, userId);
            return;
        }

        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        final Entry entry = getOrCreateEntry(displayInfo);
        entry.mForcedDensity = density;
        writeSettingsIfNeeded(entry, displayInfo);
    }

    void setForcedScalingMode(DisplayContent displayContent, @ForceScalingMode int mode) {
        if (displayContent.isDefaultDisplay) {
            Settings.Global.putInt(mService.mContext.getContentResolver(),
                    Settings.Global.DISPLAY_SCALING_FORCE, mode);
            return;
        }

        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        final Entry entry = getOrCreateEntry(displayInfo);
        entry.mForcedScalingMode = mode;
        writeSettingsIfNeeded(entry, displayInfo);
    }

    void setFixedToUserRotation(DisplayContent displayContent,
            @DisplayRotation.FixedToUserRotation int fixedToUserRotation) {
        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        final Entry entry = getOrCreateEntry(displayInfo);
        entry.mFixedToUserRotation = fixedToUserRotation;
        writeSettingsIfNeeded(entry, displayInfo);
    }

    private int getWindowingModeLocked(Entry entry, int displayId) {
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
            final boolean forceDesktopMode = mService.mForceDesktopModeOnExternalDisplays
                    && displayId != Display.DEFAULT_DISPLAY;
            windowingMode = mService.mSupportsFreeformWindowManagement
                    && (mService.mIsPc || forceDesktopMode)
                    ? WindowConfiguration.WINDOWING_MODE_FREEFORM
                    : WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
        }
        return windowingMode;
    }

    int getWindowingModeLocked(DisplayContent dc) {
        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final Entry entry = getEntry(displayInfo);
        return getWindowingModeLocked(entry, dc.getDisplayId());
    }

    void setWindowingModeLocked(DisplayContent dc, int mode) {
        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final Entry entry = getOrCreateEntry(displayInfo);
        entry.mWindowingMode = mode;
        dc.setWindowingMode(mode);
        writeSettingsIfNeeded(entry, displayInfo);
    }

    int getRemoveContentModeLocked(DisplayContent dc) {
        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final Entry entry = getEntry(displayInfo);
        if (entry == null || entry.mRemoveContentMode == REMOVE_CONTENT_MODE_UNDEFINED) {
            if (dc.isPrivate()) {
                // For private displays by default content is destroyed on removal.
                return REMOVE_CONTENT_MODE_DESTROY;
            }
            // For other displays by default content is moved to primary on removal.
            return REMOVE_CONTENT_MODE_MOVE_TO_PRIMARY;
        }
        return entry.mRemoveContentMode;
    }

    void setRemoveContentModeLocked(DisplayContent dc, int mode) {
        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final Entry entry = getOrCreateEntry(displayInfo);
        entry.mRemoveContentMode = mode;
        writeSettingsIfNeeded(entry, displayInfo);
    }

    boolean shouldShowWithInsecureKeyguardLocked(DisplayContent dc) {
        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final Entry entry = getEntry(displayInfo);
        if (entry == null) {
            return false;
        }
        return entry.mShouldShowWithInsecureKeyguard;
    }

    void setShouldShowWithInsecureKeyguardLocked(DisplayContent dc, boolean shouldShow) {
        if (!dc.isPrivate() && shouldShow) {
            Slog.e(TAG, "Public display can't be allowed to show content when locked");
            return;
        }

        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final Entry entry = getOrCreateEntry(displayInfo);
        entry.mShouldShowWithInsecureKeyguard = shouldShow;
        writeSettingsIfNeeded(entry, displayInfo);
    }

    boolean shouldShowSystemDecorsLocked(DisplayContent dc) {
        if (dc.getDisplayId() == Display.DEFAULT_DISPLAY) {
            // For default display should show system decors.
            return true;
        }

        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final Entry entry = getEntry(displayInfo);
        if (entry == null) {
            return false;
        }
        return entry.mShouldShowSystemDecors;
    }

    void setShouldShowSystemDecorsLocked(DisplayContent dc, boolean shouldShow) {
        if (dc.getDisplayId() == Display.DEFAULT_DISPLAY && !shouldShow) {
            Slog.e(TAG, "Default display should show system decors");
            return;
        }

        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final Entry entry = getOrCreateEntry(displayInfo);
        entry.mShouldShowSystemDecors = shouldShow;
        writeSettingsIfNeeded(entry, displayInfo);
    }

    boolean shouldShowImeLocked(DisplayContent dc) {
        if (dc.getDisplayId() == Display.DEFAULT_DISPLAY) {
            // For default display should shows IME.
            return true;
        }

        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final Entry entry = getEntry(displayInfo);
        if (entry == null) {
            return false;
        }
        return entry.mShouldShowIme;
    }

    void setShouldShowImeLocked(DisplayContent dc, boolean shouldShow) {
        if (dc.getDisplayId() == Display.DEFAULT_DISPLAY && !shouldShow) {
            Slog.e(TAG, "Default display should show IME");
            return;
        }

        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final Entry entry = getOrCreateEntry(displayInfo);
        entry.mShouldShowIme = shouldShow;
        writeSettingsIfNeeded(entry, displayInfo);
    }

    void applySettingsToDisplayLocked(DisplayContent dc) {
        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final Entry entry = getOrCreateEntry(displayInfo);

        // Setting windowing mode first, because it may override overscan values later.
        dc.setWindowingMode(getWindowingModeLocked(entry, dc.getDisplayId()));

        displayInfo.overscanLeft = entry.mOverscanLeft;
        displayInfo.overscanTop = entry.mOverscanTop;
        displayInfo.overscanRight = entry.mOverscanRight;
        displayInfo.overscanBottom = entry.mOverscanBottom;

        dc.getDisplayRotation().restoreSettings(entry.mUserRotationMode,
                entry.mUserRotation, entry.mFixedToUserRotation);

        if (entry.mForcedDensity != 0) {
            dc.mBaseDisplayDensity = entry.mForcedDensity;
        }
        if (entry.mForcedWidth != 0 && entry.mForcedHeight != 0) {
            dc.updateBaseDisplayMetrics(entry.mForcedWidth, entry.mForcedHeight,
                    dc.mBaseDisplayDensity);
        }
        dc.mDisplayScalingDisabled = entry.mForcedScalingMode == FORCE_SCALING_MODE_DISABLED;
    }

    /**
     * Updates settings for the given display after system features are loaded into window manager
     * service, e.g. if this device is PC and if this device supports freeform.
     *
     * @param dc the given display.
     * @return {@code true} if any settings for this display has changed; {@code false} if nothing
     * changed.
     */
    boolean updateSettingsForDisplay(DisplayContent dc) {
        if (dc.getWindowingMode() != getWindowingModeLocked(dc)) {
            // For the time being the only thing that may change is windowing mode, so just update
            // that.
            dc.setWindowingMode(getWindowingModeLocked(dc));
            return true;
        }
        return false;
    }

    private void readSettings() {
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
            final String str = parser.getAttributeValue(null, name);
            return str != null ? Integer.parseInt(str) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean getBooleanAttribute(XmlPullParser parser, String name) {
        return getBooleanAttribute(parser, name, false /* defaultValue */);
    }

    private boolean getBooleanAttribute(XmlPullParser parser, String name, boolean defaultValue) {
        try {
            final String str = parser.getAttributeValue(null, name);
            return str != null ? Boolean.parseBoolean(str) : defaultValue;
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
            entry.mForcedWidth = getIntAttribute(parser, "forcedWidth");
            entry.mForcedHeight = getIntAttribute(parser, "forcedHeight");
            entry.mForcedDensity = getIntAttribute(parser, "forcedDensity");
            entry.mForcedScalingMode = getIntAttribute(parser, "forcedScalingMode",
                    FORCE_SCALING_MODE_AUTO);
            entry.mRemoveContentMode = getIntAttribute(parser, "removeContentMode",
                    REMOVE_CONTENT_MODE_UNDEFINED);
            entry.mShouldShowWithInsecureKeyguard = getBooleanAttribute(parser,
                    "shouldShowWithInsecureKeyguard");
            entry.mShouldShowSystemDecors = getBooleanAttribute(parser, "shouldShowSystemDecors");
            entry.mShouldShowIme = getBooleanAttribute(parser, "shouldShowIme");
            entry.mFixedToUserRotation = getIntAttribute(parser, "fixedToUserRotation");
            mEntries.put(name, entry);
        }
        XmlUtils.skipCurrentTag(parser);
    }

    private void writeSettingsIfNeeded(Entry changedEntry, DisplayInfo displayInfo) {
        if (changedEntry.isEmpty()) {
            boolean removed = mEntries.remove(displayInfo.uniqueId) != null;
            // Legacy name might have been in used, so we need to clear it.
            removed |= mEntries.remove(displayInfo.name) != null;
            if (!removed) {
                // The entry didn't exist so nothing is changed and no need to update the file.
                return;
            }
        } else {
            mEntries.put(displayInfo.uniqueId, changedEntry);
        }

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
                if (entry.mForcedWidth != 0 && entry.mForcedHeight != 0) {
                    out.attribute(null, "forcedWidth", Integer.toString(entry.mForcedWidth));
                    out.attribute(null, "forcedHeight", Integer.toString(entry.mForcedHeight));
                }
                if (entry.mForcedDensity != 0) {
                    out.attribute(null, "forcedDensity", Integer.toString(entry.mForcedDensity));
                }
                if (entry.mForcedScalingMode != FORCE_SCALING_MODE_AUTO) {
                    out.attribute(null, "forcedScalingMode",
                            Integer.toString(entry.mForcedScalingMode));
                }
                if (entry.mRemoveContentMode != REMOVE_CONTENT_MODE_UNDEFINED) {
                    out.attribute(null, "removeContentMode",
                            Integer.toString(entry.mRemoveContentMode));
                }
                if (entry.mShouldShowWithInsecureKeyguard) {
                    out.attribute(null, "shouldShowWithInsecureKeyguard",
                            Boolean.toString(entry.mShouldShowWithInsecureKeyguard));
                }
                if (entry.mShouldShowSystemDecors) {
                    out.attribute(null, "shouldShowSystemDecors",
                            Boolean.toString(entry.mShouldShowSystemDecors));
                }
                if (entry.mShouldShowIme) {
                    out.attribute(null, "shouldShowIme", Boolean.toString(entry.mShouldShowIme));
                }
                if (entry.mFixedToUserRotation != FIXED_TO_USER_ROTATION_DEFAULT) {
                    out.attribute(null, "fixedToUserRotation",
                            Integer.toString(entry.mFixedToUserRotation));
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
