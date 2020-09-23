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
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.WindowConfiguration;
import android.os.Environment;
import android.os.FileUtils;
import android.provider.Settings;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.DisplayInfo;
import android.view.IWindowManager;
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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * Current persistent settings about a display
 */
class DisplayWindowSettings {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "DisplayWindowSettings" : TAG_WM;

    private static final String SYSTEM_DIRECTORY = "system";
    private static final String DISPLAY_SETTINGS_FILE_NAME = "display_settings.xml";
    private static final String VENDOR_DISPLAY_SETTINGS_PATH = "etc/" + DISPLAY_SETTINGS_FILE_NAME;
    private static final String WM_DISPLAY_COMMIT_TAG = "wm-displays";

    private static final int IDENTIFIER_UNIQUE_ID = 0;
    private static final int IDENTIFIER_PORT = 1;
    @IntDef(prefix = { "IDENTIFIER_" }, value = {
            IDENTIFIER_UNIQUE_ID,
            IDENTIFIER_PORT,
    })
    @interface DisplayIdentifierType {}

    private final WindowManagerService mService;
    private final HashMap<String, Entry> mEntries = new HashMap<>();
    private final SettingPersister mStorage;

    /**
     * The preferred type of a display identifier to use when storing and retrieving entries.
     * {@link #getIdentifier(DisplayInfo)} must be used to get current preferred identifier for each
     * display. It will fall back to using {@link #IDENTIFIER_UNIQUE_ID} if the currently selected
     * one is not applicable to a particular display.
     */
    @DisplayIdentifierType
    private int mIdentifier = IDENTIFIER_UNIQUE_ID;

    /** Interface for persisting the display window settings. */
    interface SettingPersister {
        InputStream openRead() throws IOException;
        OutputStream startWrite() throws IOException;
        void finishWrite(OutputStream os, boolean success);
    }

    private static class Entry {
        private final String mName;
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
        private int mFixedToUserRotation = IWindowManager.FIXED_TO_USER_ROTATION_DEFAULT;

        private Entry(String name) {
            mName = name;
        }

        private Entry(String name, Entry copyFrom) {
            this(name);
            mWindowingMode = copyFrom.mWindowingMode;
            mUserRotationMode = copyFrom.mUserRotationMode;
            mUserRotation = copyFrom.mUserRotation;
            mForcedWidth = copyFrom.mForcedWidth;
            mForcedHeight = copyFrom.mForcedHeight;
            mForcedDensity = copyFrom.mForcedDensity;
            mForcedScalingMode = copyFrom.mForcedScalingMode;
            mRemoveContentMode = copyFrom.mRemoveContentMode;
            mShouldShowWithInsecureKeyguard = copyFrom.mShouldShowWithInsecureKeyguard;
            mShouldShowSystemDecors = copyFrom.mShouldShowSystemDecors;
            mShouldShowIme = copyFrom.mShouldShowIme;
            mFixedToUserRotation = copyFrom.mFixedToUserRotation;
        }

        /** @return {@code true} if all values are default. */
        private boolean isEmpty() {
            return mWindowingMode == WindowConfiguration.WINDOWING_MODE_UNDEFINED
                    && mUserRotationMode == WindowManagerPolicy.USER_ROTATION_FREE
                    && mUserRotation == Surface.ROTATION_0
                    && mForcedWidth == 0 && mForcedHeight == 0 && mForcedDensity == 0
                    && mForcedScalingMode == FORCE_SCALING_MODE_AUTO
                    && mRemoveContentMode == REMOVE_CONTENT_MODE_UNDEFINED
                    && !mShouldShowWithInsecureKeyguard
                    && !mShouldShowSystemDecors
                    && !mShouldShowIme
                    && mFixedToUserRotation == IWindowManager.FIXED_TO_USER_ROTATION_DEFAULT;
        }
    }

    DisplayWindowSettings(WindowManagerService service) {
        this(service, new AtomicFileStorage());
    }

    @VisibleForTesting
    DisplayWindowSettings(WindowManagerService service, SettingPersister storageImpl) {
        mService = service;
        mStorage = storageImpl;
        readSettings();
    }

    private @Nullable Entry getEntry(DisplayInfo displayInfo) {
        final String identifier = getIdentifier(displayInfo);
        Entry entry;
        // Try to get corresponding entry using preferred identifier for the current config.
        if ((entry = mEntries.get(identifier)) != null) {
            return entry;
        }
        // Else, fall back to the display name.
        if ((entry = mEntries.get(displayInfo.name)) != null) {
            // Found an entry stored with old identifier - upgrade to the new type now.
            return updateIdentifierForEntry(entry, displayInfo);
        }
        return null;
    }

    private Entry getOrCreateEntry(DisplayInfo displayInfo) {
        final Entry entry = getEntry(displayInfo);
        return entry != null ? entry : new Entry(getIdentifier(displayInfo));
    }

    /**
     * Upgrades the identifier of a legacy entry. Does it by copying the data from the old record
     * and clearing the old key in memory. The entry will be written to storage next time when a
     * setting changes.
     */
    private Entry updateIdentifierForEntry(Entry entry, DisplayInfo displayInfo) {
        final Entry newEntry = new Entry(getIdentifier(displayInfo), entry);
        removeEntry(displayInfo);
        mEntries.put(newEntry.mName, newEntry);
        return newEntry;
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

    void setFixedToUserRotation(DisplayContent displayContent, int fixedToUserRotation) {
        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        final Entry entry = getOrCreateEntry(displayInfo);
        entry.mFixedToUserRotation = fixedToUserRotation;
        writeSettingsIfNeeded(entry, displayInfo);
    }

    private int getWindowingModeLocked(Entry entry, DisplayContent dc) {
        int windowingMode = entry != null ? entry.mWindowingMode
                : WindowConfiguration.WINDOWING_MODE_UNDEFINED;
        // This display used to be in freeform, but we don't support freeform anymore, so fall
        // back to fullscreen.
        if (windowingMode == WindowConfiguration.WINDOWING_MODE_FREEFORM
                && !mService.mAtmService.mSupportsFreeformWindowManagement) {
            return WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
        }
        // No record is present so use default windowing mode policy.
        if (windowingMode == WindowConfiguration.WINDOWING_MODE_UNDEFINED) {
            windowingMode = mService.mAtmService.mSupportsFreeformWindowManagement
                    && (mService.mIsPc || dc.forceDesktopMode())
                    ? WindowConfiguration.WINDOWING_MODE_FREEFORM
                    : WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
        }
        return windowingMode;
    }

    int getWindowingModeLocked(DisplayContent dc) {
        final DisplayInfo displayInfo = dc.getDisplayInfo();
        final Entry entry = getEntry(displayInfo);
        return getWindowingModeLocked(entry, dc);
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
        dc.setWindowingMode(getWindowingModeLocked(entry, dc));

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
        InputStream stream;
        try {
            stream = mStorage.openRead();
        } catch (IOException e) {
            Slog.i(TAG, "No existing display settings, starting empty");
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
                } else if (tagName.equals("config")) {
                    readConfig(parser);
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

    private void readConfig(XmlPullParser parser) throws NumberFormatException,
            XmlPullParserException, IOException {
        mIdentifier = getIntAttribute(parser, "identifier");
        XmlUtils.skipCurrentTag(parser);
    }

    private void writeSettingsIfNeeded(Entry changedEntry, DisplayInfo displayInfo) {
        if (changedEntry.isEmpty() && !removeEntry(displayInfo)) {
            // The entry didn't exist so nothing is changed and no need to update the file.
            return;
        }

        mEntries.put(getIdentifier(displayInfo), changedEntry);
        writeSettings();
    }

    private void writeSettings() {
        OutputStream stream;
        try {
            stream = mStorage.startWrite();
        } catch (IOException e) {
            Slog.w(TAG, "Failed to write display settings: " + e);
            return;
        }

        try {
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(stream, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);

            out.startTag(null, "display-settings");

            out.startTag(null, "config");
            out.attribute(null, "identifier", Integer.toString(mIdentifier));
            out.endTag(null, "config");

            for (Entry entry : mEntries.values()) {
                out.startTag(null, "display");
                out.attribute(null, "name", entry.mName);
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
                if (entry.mFixedToUserRotation != IWindowManager.FIXED_TO_USER_ROTATION_DEFAULT) {
                    out.attribute(null, "fixedToUserRotation",
                            Integer.toString(entry.mFixedToUserRotation));
                }
                out.endTag(null, "display");
            }

            out.endTag(null, "display-settings");
            out.endDocument();
            mStorage.finishWrite(stream, true /* success */);
        } catch (IOException e) {
            Slog.w(TAG, "Failed to write display window settings.", e);
            mStorage.finishWrite(stream, false /* success */);
        }
    }

    /**
     * Removes an entry from {@link #mEntries} cache. Looks up by new and previously used
     * identifiers.
     */
    private boolean removeEntry(DisplayInfo displayInfo) {
        // Remove entry based on primary identifier.
        boolean removed = mEntries.remove(getIdentifier(displayInfo)) != null;
        // Ensure that legacy entries are cleared as well.
        removed |= mEntries.remove(displayInfo.uniqueId) != null;
        removed |= mEntries.remove(displayInfo.name) != null;
        return removed;
    }

    /** Gets the identifier of choice for the current config. */
    private String getIdentifier(DisplayInfo displayInfo) {
        if (mIdentifier == IDENTIFIER_PORT && displayInfo.address != null) {
            // Config suggests using port as identifier for physical displays.
            if (displayInfo.address instanceof DisplayAddress.Physical) {
                byte port = ((DisplayAddress.Physical) displayInfo.address).getPort();
                return "port:" + Byte.toUnsignedInt(port);
            }
        }
        return displayInfo.uniqueId;
    }

    private static class AtomicFileStorage implements SettingPersister {
        private final AtomicFile mAtomicFile;

        AtomicFileStorage() {
            final File folder = new File(Environment.getDataDirectory(), SYSTEM_DIRECTORY);
            final File settingsFile = new File(folder, DISPLAY_SETTINGS_FILE_NAME);
            // If display_settings.xml doesn't exist, try to copy the vendor's one instead
            // in order to provide the vendor specific initialization.
            if (!settingsFile.exists()) {
                copyVendorSettings(settingsFile);
            }
            mAtomicFile = new AtomicFile(settingsFile, WM_DISPLAY_COMMIT_TAG);
        }

        private static void copyVendorSettings(File target) {
            final File vendorFile = new File(Environment.getVendorDirectory(),
                    VENDOR_DISPLAY_SETTINGS_PATH);
            if (vendorFile.canRead()) {
                try {
                    FileUtils.copy(vendorFile, target);
                } catch (IOException e) {
                    Slog.e(TAG, "Failed to copy vendor display_settings.xml");
                }
            }
        }

        @Override
        public InputStream openRead() throws FileNotFoundException {
            return mAtomicFile.openRead();
        }

        @Override
        public OutputStream startWrite() throws IOException {
            return mAtomicFile.startWrite();
        }

        @Override
        public void finishWrite(OutputStream os, boolean success) {
            if (!(os instanceof FileOutputStream)) {
                throw new IllegalArgumentException("Unexpected OutputStream as argument: " + os);
            }
            FileOutputStream fos = (FileOutputStream) os;
            if (success) {
                mAtomicFile.finishWrite(fos);
            } else {
                mAtomicFile.failWrite(fos);
            }
        }
    }
}
