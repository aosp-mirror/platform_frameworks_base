/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.os.UserHandle.USER_SYSTEM;
import static android.view.Display.TYPE_VIRTUAL;
import static android.view.WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
import static android.view.WindowManager.DISPLAY_IME_POLICY_LOCAL;
import static android.view.WindowManager.REMOVE_CONTENT_MODE_UNDEFINED;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.WindowConfiguration;
import android.os.Environment;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;
import android.view.DisplayAddress;
import android.view.DisplayInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.wm.DisplayWindowSettings.SettingsProvider;
import com.android.window.flags.Flags;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of {@link SettingsProvider} that reads the base settings provided in a display
 * settings file stored in /vendor/etc and then overlays those values with the settings provided in
 * /data/system.
 *
 * @see DisplayWindowSettings
 */
class DisplayWindowSettingsProvider implements SettingsProvider {
    private static final String TAG = TAG_WITH_CLASS_NAME
            ? "DisplayWindowSettingsProvider" : TAG_WM;

    private static final String DATA_DISPLAY_SETTINGS_FILE_PATH = "system/display_settings.xml";
    private static final String VENDOR_DISPLAY_SETTINGS_FILE_PATH = "etc/display_settings.xml";
    private static final String WM_DISPLAY_COMMIT_TAG = "wm-displays";

    private static final int IDENTIFIER_UNIQUE_ID = 0;
    private static final int IDENTIFIER_PORT = 1;
    @IntDef(prefix = { "IDENTIFIER_" }, value = {
            IDENTIFIER_UNIQUE_ID,
            IDENTIFIER_PORT,
    })
    @interface DisplayIdentifierType {}

    /** Interface that allows reading the display window settings. */
    interface ReadableSettingsStorage {
        InputStream openRead() throws IOException;
    }

    /** Interface that allows reading and writing the display window settings. */
    interface WritableSettingsStorage extends ReadableSettingsStorage {
        OutputStream startWrite() throws IOException;
        void finishWrite(OutputStream os, boolean success);
    }

    @NonNull
    private ReadableSettings mBaseSettings;
    @NonNull
    private WritableSettings mOverrideSettings;

    DisplayWindowSettingsProvider() {
        this(new AtomicFileStorage(getVendorSettingsFile()),
                new AtomicFileStorage(getOverrideSettingsFileForUser(USER_SYSTEM)));
    }

    @VisibleForTesting
    DisplayWindowSettingsProvider(@NonNull ReadableSettingsStorage baseSettingsStorage,
            @NonNull WritableSettingsStorage overrideSettingsStorage) {
        mBaseSettings = new ReadableSettings(baseSettingsStorage);
        mOverrideSettings = new WritableSettings(overrideSettingsStorage);
    }

    /**
     * Overrides the path for the file that should be used to read base settings. If {@code null} is
     * passed the default base settings file path will be used.
     *
     * @see #VENDOR_DISPLAY_SETTINGS_FILE_PATH
     */
    void setBaseSettingsFilePath(@Nullable String path) {
        AtomicFile settingsFile;
        File file = path != null ? new File(path) : null;
        if (file != null && file.exists()) {
            settingsFile = new AtomicFile(file, WM_DISPLAY_COMMIT_TAG);
        } else {
            Slog.w(TAG, "display settings " + path + " does not exist, using vendor defaults");
            settingsFile = getVendorSettingsFile();
        }
        setBaseSettingsStorage(new AtomicFileStorage(settingsFile));
    }

    /**
     * Overrides the storage that should be used to read base settings.
     *
     * @see #setBaseSettingsFilePath(String)
     */
    @VisibleForTesting
    void setBaseSettingsStorage(@NonNull ReadableSettingsStorage baseSettingsStorage) {
        mBaseSettings = new ReadableSettings(baseSettingsStorage);
    }

    /**
     * Overrides the storage that should be used to save override settings for a user.
     *
     * @see #DATA_DISPLAY_SETTINGS_FILE_PATH
     */
    void setOverrideSettingsForUser(@UserIdInt int userId) {
        if (!Flags.perUserDisplayWindowSettings()) {
            return;
        }
        final AtomicFile settingsFile = getOverrideSettingsFileForUser(userId);
        setOverrideSettingsStorage(new AtomicFileStorage(settingsFile));
    }

    /**
     * Removes display override settings that are no longer associated with active displays.
     * This is necessary because displays can be dynamically added or removed during
     * the system's lifecycle (e.g., user switch, system server restart).
     *
     * @param root The root window container used to obtain the currently active displays.
     */
    void removeStaleDisplaySettings(@NonNull RootWindowContainer root) {
        if (!Flags.perUserDisplayWindowSettings()) {
            return;
        }
        final Set<String> displayIdentifiers = new ArraySet<>();
        root.forAllDisplays(dc -> {
            final String identifier = mOverrideSettings.getIdentifier(dc.getDisplayInfo());
            displayIdentifiers.add(identifier);
        });
        mOverrideSettings.removeStaleDisplaySettings(displayIdentifiers);
    }

    /**
     * Overrides the storage that should be used to save override settings.
     *
     * @see #setOverrideSettingsForUser(int)
     */
    @VisibleForTesting
    void setOverrideSettingsStorage(@NonNull WritableSettingsStorage overrideSettingsStorage) {
        mOverrideSettings = new WritableSettings(overrideSettingsStorage);
    }

    @Override
    @NonNull
    public SettingsEntry getSettings(@NonNull DisplayInfo info) {
        SettingsEntry baseSettings = mBaseSettings.getSettingsEntry(info);
        SettingsEntry overrideSettings = mOverrideSettings.getOrCreateSettingsEntry(info);
        if (baseSettings == null) {
            return new SettingsEntry(overrideSettings);
        } else {
            SettingsEntry mergedSettings = new SettingsEntry(baseSettings);
            mergedSettings.updateFrom(overrideSettings);
            return mergedSettings;
        }
    }

    @Override
    @NonNull
    public SettingsEntry getOverrideSettings(@NonNull DisplayInfo info) {
        return new SettingsEntry(mOverrideSettings.getOrCreateSettingsEntry(info));
    }

    @Override
    public void updateOverrideSettings(@NonNull DisplayInfo info,
            @NonNull SettingsEntry overrides) {
        mOverrideSettings.updateSettingsEntry(info, overrides);
    }

    @Override
    public void onDisplayRemoved(@NonNull DisplayInfo info) {
        mOverrideSettings.onDisplayRemoved(info);
    }

    @Override
    public void clearDisplaySettings(@NonNull DisplayInfo info) {
        mOverrideSettings.clearDisplaySettings(info);
    }

    @VisibleForTesting
    int getOverrideSettingsSize() {
        return mOverrideSettings.mSettings.size();
    }

    /**
     * Class that allows reading {@link SettingsEntry entries} from a
     * {@link ReadableSettingsStorage}.
     */
    private static class ReadableSettings {
        /**
         * The preferred type of a display identifier to use when storing and retrieving entries
         * from the settings entries.
         *
         * @see #getIdentifier(DisplayInfo)
         */
        @DisplayIdentifierType
        protected int mIdentifierType;
        @NonNull
        protected final ArrayMap<String, SettingsEntry> mSettings = new ArrayMap<>();

        ReadableSettings(@NonNull ReadableSettingsStorage settingsStorage) {
            loadSettings(settingsStorage);
        }

        @Nullable
        final SettingsEntry getSettingsEntry(@NonNull DisplayInfo info) {
            final String identifier = getIdentifier(info);
            SettingsEntry settings;
            // Try to get corresponding settings using preferred identifier for the current config.
            if ((settings = mSettings.get(identifier)) != null) {
                return settings;
            }
            // Else, fall back to the display name.
            if ((settings = mSettings.get(info.name)) != null) {
                // Found an entry stored with old identifier.
                mSettings.remove(info.name);
                mSettings.put(identifier, settings);
                return settings;
            }
            return null;
        }

        /** Gets the identifier of choice for the current config. */
        @NonNull
        protected final String getIdentifier(@NonNull DisplayInfo displayInfo) {
            if (mIdentifierType == IDENTIFIER_PORT && displayInfo.address != null) {
                // Config suggests using port as identifier for physical displays.
                if (displayInfo.address instanceof DisplayAddress.Physical) {
                    return "port:" + ((DisplayAddress.Physical) displayInfo.address).getPort();
                }
            }
            return displayInfo.uniqueId;
        }

        private void loadSettings(@NonNull ReadableSettingsStorage settingsStorage) {
            FileData fileData = readSettings(settingsStorage);
            if (fileData != null) {
                mIdentifierType = fileData.mIdentifierType;
                mSettings.putAll(fileData.mSettings);
            }
        }
    }

    /**
     * Class that allows reading {@link SettingsEntry entries} from, and writing entries to, a
     * {@link WritableSettingsStorage}.
     */
    private static final class WritableSettings extends ReadableSettings {
        @NonNull
        private final WritableSettingsStorage mSettingsStorage;
        @NonNull
        private final ArraySet<String> mVirtualDisplayIdentifiers = new ArraySet<>();

        WritableSettings(@NonNull WritableSettingsStorage settingsStorage) {
            super(settingsStorage);
            mSettingsStorage = settingsStorage;
        }

        @NonNull
        SettingsEntry getOrCreateSettingsEntry(@NonNull DisplayInfo info) {
            final String identifier = getIdentifier(info);
            SettingsEntry settings;
            // Try to get corresponding settings using preferred identifier for the current config.
            if ((settings = mSettings.get(identifier)) != null) {
                return settings;
            }
            // Else, fall back to the display name.
            if ((settings = mSettings.get(info.name)) != null) {
                // Found an entry stored with old identifier.
                mSettings.remove(info.name);
                mSettings.put(identifier, settings);
                writeSettings();
                return settings;
            }

            settings = new SettingsEntry();
            mSettings.put(identifier, settings);
            if (info.type == TYPE_VIRTUAL) {
                // Keep track of virtual display. We don't want to write virtual display settings to
                // file.
                mVirtualDisplayIdentifiers.add(identifier);
            }
            return settings;
        }

        void updateSettingsEntry(@NonNull DisplayInfo info, @NonNull SettingsEntry settings) {
            final SettingsEntry overrideSettings = getOrCreateSettingsEntry(info);
            final boolean changed = overrideSettings.setTo(settings);
            if (changed && info.type != TYPE_VIRTUAL) {
                writeSettings();
            }
        }

        void onDisplayRemoved(@NonNull DisplayInfo info) {
            final String identifier = getIdentifier(info);
            if (!mSettings.containsKey(identifier)) {
                return;
            }
            if (mVirtualDisplayIdentifiers.remove(identifier)
                    || mSettings.get(identifier).isEmpty()) {
                // Don't keep track of virtual display or empty settings to avoid growing the cached
                // map.
                mSettings.remove(identifier);
            }
        }

        void clearDisplaySettings(@NonNull DisplayInfo info) {
            final String identifier = getIdentifier(info);
            mSettings.remove(identifier);
            mVirtualDisplayIdentifiers.remove(identifier);
        }

        void removeStaleDisplaySettings(@NonNull Set<String> currentDisplayIdentifiers) {
            if (mSettings.retainAll(currentDisplayIdentifiers)) {
                writeSettings();
            }
        }

        private void writeSettings() {
            final FileData fileData = new FileData();
            fileData.mIdentifierType = mIdentifierType;
            final int size = mSettings.size();
            for (int i = 0; i < size; i++) {
                final String identifier = mSettings.keyAt(i);
                if (mVirtualDisplayIdentifiers.contains(identifier)) {
                    // Do not write virtual display settings to file.
                    continue;
                }
                fileData.mSettings.put(identifier, mSettings.get(identifier));
            }
            DisplayWindowSettingsProvider.writeSettings(mSettingsStorage, fileData);
        }
    }

    @NonNull
    private static AtomicFile getVendorSettingsFile() {
        // First look under product path for treblized builds.
        File vendorFile = new File(Environment.getProductDirectory(),
                VENDOR_DISPLAY_SETTINGS_FILE_PATH);
        if (!vendorFile.exists()) {
            // Try and look in vendor path.
            vendorFile = new File(Environment.getVendorDirectory(),
                VENDOR_DISPLAY_SETTINGS_FILE_PATH);
        }
        return new AtomicFile(vendorFile, WM_DISPLAY_COMMIT_TAG);
    }

    @NonNull
    private static AtomicFile getOverrideSettingsFileForUser(@UserIdInt int userId) {
        final File directory;
        if (userId == USER_SYSTEM || !Flags.perUserDisplayWindowSettings()) {
            directory = Environment.getDataDirectory();
        } else {
            directory = Environment.getDataSystemCeDirectory(userId);
        }
        final File overrideSettingsFile = new File(directory, DATA_DISPLAY_SETTINGS_FILE_PATH);
        return new AtomicFile(overrideSettingsFile, WM_DISPLAY_COMMIT_TAG);
    }

    @Nullable
    private static FileData readSettings(@NonNull ReadableSettingsStorage storage) {
        InputStream stream;
        try {
            stream = storage.openRead();
        } catch (IOException e) {
            Slog.i(TAG, "No existing display settings, starting empty");
            return null;
        }
        FileData fileData = new FileData();
        boolean success = false;
        try {
            TypedXmlPullParser parser = Xml.resolvePullParser(stream);
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
                    readDisplay(parser, fileData);
                } else if (tagName.equals("config")) {
                    readConfig(parser, fileData);
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
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
        if (!success) {
            fileData.mSettings.clear();
        }
        return fileData;
    }

    private static int getIntAttribute(@NonNull TypedXmlPullParser parser, @NonNull String name,
            int defaultValue) {
        return parser.getAttributeInt(null, name, defaultValue);
    }

    @Nullable
    private static Integer getIntegerAttribute(@NonNull TypedXmlPullParser parser,
            @NonNull String name, @Nullable Integer defaultValue) {
        try {
            return parser.getAttributeInt(null, name);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    @Nullable
    private static Boolean getBooleanAttribute(@NonNull TypedXmlPullParser parser,
            @NonNull String name, @Nullable Boolean defaultValue) {
        try {
            return parser.getAttributeBoolean(null, name);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static void readDisplay(@NonNull TypedXmlPullParser parser, @NonNull FileData fileData)
            throws NumberFormatException, XmlPullParserException, IOException {
        String name = parser.getAttributeValue(null, "name");
        if (name != null) {
            SettingsEntry settingsEntry = new SettingsEntry();
            settingsEntry.mWindowingMode = getIntAttribute(parser, "windowingMode",
                    WindowConfiguration.WINDOWING_MODE_UNDEFINED /* defaultValue */);
            settingsEntry.mUserRotationMode = getIntegerAttribute(parser, "userRotationMode",
                    null /* defaultValue */);
            settingsEntry.mUserRotation = getIntegerAttribute(parser, "userRotation",
                    null /* defaultValue */);
            settingsEntry.mForcedWidth = getIntAttribute(parser, "forcedWidth",
                    0 /* defaultValue */);
            settingsEntry.mForcedHeight = getIntAttribute(parser, "forcedHeight",
                    0 /* defaultValue */);
            settingsEntry.mForcedDensity = getIntAttribute(parser, "forcedDensity",
                    0 /* defaultValue */);
            settingsEntry.mForcedScalingMode = getIntegerAttribute(parser, "forcedScalingMode",
                    null /* defaultValue */);
            settingsEntry.mRemoveContentMode = getIntAttribute(parser, "removeContentMode",
                    REMOVE_CONTENT_MODE_UNDEFINED /* defaultValue */);
            settingsEntry.mShouldShowWithInsecureKeyguard = getBooleanAttribute(parser,
                    "shouldShowWithInsecureKeyguard", null /* defaultValue */);
            settingsEntry.mShouldShowSystemDecors = getBooleanAttribute(parser,
                    "shouldShowSystemDecors", null /* defaultValue */);
            final Boolean shouldShowIme = getBooleanAttribute(parser, "shouldShowIme",
                    null /* defaultValue */);
            if (shouldShowIme != null) {
                settingsEntry.mImePolicy = shouldShowIme ? DISPLAY_IME_POLICY_LOCAL
                        : DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
            } else {
                settingsEntry.mImePolicy = getIntegerAttribute(parser, "imePolicy",
                        null /* defaultValue */);
            }
            settingsEntry.mFixedToUserRotation = getIntegerAttribute(parser, "fixedToUserRotation",
                    null /* defaultValue */);
            settingsEntry.mIgnoreOrientationRequest = getBooleanAttribute(parser,
                    "ignoreOrientationRequest", null /* defaultValue */);
            settingsEntry.mIgnoreDisplayCutout = getBooleanAttribute(parser,
                    "ignoreDisplayCutout", null /* defaultValue */);
            settingsEntry.mDontMoveToTop = getBooleanAttribute(parser,
                    "dontMoveToTop", null /* defaultValue */);

            fileData.mSettings.put(name, settingsEntry);
        }
        XmlUtils.skipCurrentTag(parser);
    }

    private static void readConfig(@NonNull TypedXmlPullParser parser, @NonNull FileData fileData)
            throws NumberFormatException,
            XmlPullParserException, IOException {
        fileData.mIdentifierType = getIntAttribute(parser, "identifier",
                IDENTIFIER_UNIQUE_ID);
        XmlUtils.skipCurrentTag(parser);
    }

    private static void writeSettings(@NonNull WritableSettingsStorage storage,
            @NonNull FileData data) {
        OutputStream stream;
        try {
            stream = storage.startWrite();
        } catch (IOException e) {
            Slog.w(TAG, "Failed to write display settings: " + e);
            return;
        }

        boolean success = false;
        try {
            TypedXmlSerializer out = Xml.resolveSerializer(stream);
            out.startDocument(null, true);

            out.startTag(null, "display-settings");

            out.startTag(null, "config");
            out.attributeInt(null, "identifier", data.mIdentifierType);
            out.endTag(null, "config");

            for (Map.Entry<String, SettingsEntry> entry
                    : data.mSettings.entrySet()) {
                String displayIdentifier = entry.getKey();
                SettingsEntry settingsEntry = entry.getValue();
                if (settingsEntry.isEmpty()) {
                    continue;
                }

                out.startTag(null, "display");
                out.attribute(null, "name", displayIdentifier);
                if (settingsEntry.mWindowingMode != WindowConfiguration.WINDOWING_MODE_UNDEFINED) {
                    out.attributeInt(null, "windowingMode", settingsEntry.mWindowingMode);
                }
                if (settingsEntry.mUserRotationMode != null) {
                    out.attributeInt(null, "userRotationMode",
                            settingsEntry.mUserRotationMode);
                }
                if (settingsEntry.mUserRotation != null) {
                    out.attributeInt(null, "userRotation",
                            settingsEntry.mUserRotation);
                }
                if (settingsEntry.mForcedWidth != 0 && settingsEntry.mForcedHeight != 0) {
                    out.attributeInt(null, "forcedWidth", settingsEntry.mForcedWidth);
                    out.attributeInt(null, "forcedHeight", settingsEntry.mForcedHeight);
                }
                if (settingsEntry.mForcedDensity != 0) {
                    out.attributeInt(null, "forcedDensity", settingsEntry.mForcedDensity);
                }
                if (settingsEntry.mForcedScalingMode != null) {
                    out.attributeInt(null, "forcedScalingMode",
                            settingsEntry.mForcedScalingMode);
                }
                if (settingsEntry.mRemoveContentMode != REMOVE_CONTENT_MODE_UNDEFINED) {
                    out.attributeInt(null, "removeContentMode", settingsEntry.mRemoveContentMode);
                }
                if (settingsEntry.mShouldShowWithInsecureKeyguard != null) {
                    out.attributeBoolean(null, "shouldShowWithInsecureKeyguard",
                            settingsEntry.mShouldShowWithInsecureKeyguard);
                }
                if (settingsEntry.mShouldShowSystemDecors != null) {
                    out.attributeBoolean(null, "shouldShowSystemDecors",
                            settingsEntry.mShouldShowSystemDecors);
                }
                if (settingsEntry.mImePolicy != null) {
                    out.attributeInt(null, "imePolicy", settingsEntry.mImePolicy);
                }
                if (settingsEntry.mFixedToUserRotation != null) {
                    out.attributeInt(null, "fixedToUserRotation",
                            settingsEntry.mFixedToUserRotation);
                }
                if (settingsEntry.mIgnoreOrientationRequest != null) {
                    out.attributeBoolean(null, "ignoreOrientationRequest",
                            settingsEntry.mIgnoreOrientationRequest);
                }
                if (settingsEntry.mIgnoreDisplayCutout != null) {
                    out.attributeBoolean(null, "ignoreDisplayCutout",
                            settingsEntry.mIgnoreDisplayCutout);
                }
                if (settingsEntry.mDontMoveToTop != null) {
                    out.attributeBoolean(null, "dontMoveToTop",
                            settingsEntry.mDontMoveToTop);
                }
                out.endTag(null, "display");
            }

            out.endTag(null, "display-settings");
            out.endDocument();
            success = true;
        } catch (IOException e) {
            Slog.w(TAG, "Failed to write display window settings.", e);
        } finally {
            storage.finishWrite(stream, success);
        }
    }

    private static final class FileData {
        int mIdentifierType;
        @NonNull
        final Map<String, SettingsEntry> mSettings = new ArrayMap<>();

        @Override
        public String toString() {
            return "FileData{"
                    + "mIdentifierType=" + mIdentifierType
                    + ", mSettings=" + mSettings
                    + '}';
        }
    }

    private static final class AtomicFileStorage implements WritableSettingsStorage {
        @NonNull
        private final AtomicFile mAtomicFile;

        AtomicFileStorage(@NonNull AtomicFile atomicFile) {
            mAtomicFile = atomicFile;
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
