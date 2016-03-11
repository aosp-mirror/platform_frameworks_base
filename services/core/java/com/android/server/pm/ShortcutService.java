/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IShortcutService;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.ShortcutQuery;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ParceledListSlice;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.ShortcutServiceInternal.ShortcutChangeListener;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SELinux;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.text.format.Time;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.KeyValueListParser;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedValue;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * TODO:
 *
 * - Detect when already registered instances are passed to APIs again, which might break
 *   internal bitmap handling.
 *
 * - Listen to PACKAGE_*, remove orphan info, update timestamp for icon res
 *   -> Need to scan all packages when a user starts too.
 *   -> Clear data -> remove all dynamic?  but not the pinned?
 *
 * - Pinned per each launcher package (multiple launchers)
 *
 * - Make save async (should we?)
 *
 * - Scan and remove orphan bitmaps (just in case).
 *
 * - Backup & restore
 */
public class ShortcutService extends IShortcutService.Stub {
    static final String TAG = "ShortcutService";

    static final boolean DEBUG = false; // STOPSHIP if true
    static final boolean DEBUG_LOAD = false; // STOPSHIP if true

    @VisibleForTesting
    static final long DEFAULT_RESET_INTERVAL_SEC = 24 * 60 * 60; // 1 day

    @VisibleForTesting
    static final int DEFAULT_MAX_DAILY_UPDATES = 10;

    @VisibleForTesting
    static final int DEFAULT_MAX_SHORTCUTS_PER_APP = 5;

    @VisibleForTesting
    static final int DEFAULT_MAX_ICON_DIMENSION_DP = 96;

    @VisibleForTesting
    static final int DEFAULT_MAX_ICON_DIMENSION_LOWRAM_DP = 48;

    @VisibleForTesting
    static final String DEFAULT_ICON_PERSIST_FORMAT = CompressFormat.PNG.name();

    @VisibleForTesting
    static final int DEFAULT_ICON_PERSIST_QUALITY = 100;

    private static final int SAVE_DELAY_MS = 5000; // in milliseconds.

    @VisibleForTesting
    static final String FILENAME_BASE_STATE = "shortcut_service.xml";

    @VisibleForTesting
    static final String DIRECTORY_PER_USER = "shortcut_service";

    @VisibleForTesting
    static final String FILENAME_USER_PACKAGES = "shortcuts.xml";

    static final String DIRECTORY_BITMAPS = "bitmaps";

    static final String TAG_ROOT = "root";
    static final String TAG_USER = "user";
    static final String TAG_PACKAGE = "package";
    static final String TAG_LAST_RESET_TIME = "last_reset_time";
    static final String TAG_INTENT_EXTRAS = "intent-extras";
    static final String TAG_EXTRAS = "extras";
    static final String TAG_SHORTCUT = "shortcut";

    static final String ATTR_VALUE = "value";
    static final String ATTR_NAME = "name";
    static final String ATTR_DYNAMIC_COUNT = "dynamic-count";
    static final String ATTR_CALL_COUNT = "call-count";
    static final String ATTR_LAST_RESET = "last-reset";
    static final String ATTR_ID = "id";
    static final String ATTR_ACTIVITY = "activity";
    static final String ATTR_TITLE = "title";
    static final String ATTR_INTENT = "intent";
    static final String ATTR_WEIGHT = "weight";
    static final String ATTR_TIMESTAMP = "timestamp";
    static final String ATTR_FLAGS = "flags";
    static final String ATTR_ICON_RES = "icon-res";
    static final String ATTR_BITMAP_PATH = "bitmap-path";

    @VisibleForTesting
    interface ConfigConstants {
        /**
         * Key name for the throttling reset interval, in seconds. (long)
         */
        String KEY_RESET_INTERVAL_SEC = "reset_interval_sec";

        /**
         * Key name for the max number of modifying API calls per app for every interval. (int)
         */
        String KEY_MAX_DAILY_UPDATES = "max_daily_updates";

        /**
         * Key name for the max icon dimensions in DP, for non-low-memory devices.
         */
        String KEY_MAX_ICON_DIMENSION_DP = "max_icon_dimension_dp";

        /**
         * Key name for the max icon dimensions in DP, for low-memory devices.
         */
        String KEY_MAX_ICON_DIMENSION_DP_LOWRAM = "max_icon_dimension_dp_lowram";

        /**
         * Key name for the max dynamic shortcuts per app. (int)
         */
        String KEY_MAX_SHORTCUTS = "max_shortcuts";

        /**
         * Key name for icon compression quality, 0-100.
         */
        String KEY_ICON_QUALITY = "icon_quality";

        /**
         * Key name for icon compression format: "PNG", "JPEG" or "WEBP"
         */
        String KEY_ICON_FORMAT = "icon_format";
    }

    final Context mContext;

    private final Object mLock = new Object();

    private final Handler mHandler;

    @GuardedBy("mLock")
    private final ArrayList<ShortcutChangeListener> mListeners = new ArrayList<>(1);

    @GuardedBy("mLock")
    private long mRawLastResetTime;

    /**
     * User ID -> UserShortcuts
     */
    @GuardedBy("mLock")
    private final SparseArray<UserShortcuts> mUsers = new SparseArray<>();

    /**
     * Max number of dynamic shortcuts that each application can have at a time.
     */
    private int mMaxDynamicShortcuts;

    /**
     * Max number of updating API calls that each application can make a day.
     */
    int mMaxDailyUpdates;

    /**
     * Actual throttling-reset interval.  By default it's a day.
     */
    private long mResetInterval;

    /**
     * Icon max width/height in pixels.
     */
    private int mMaxIconDimension;

    private CompressFormat mIconPersistFormat;
    private int mIconPersistQuality;

    public ShortcutService(Context context) {
        mContext = Preconditions.checkNotNull(context);
        LocalServices.addService(ShortcutServiceInternal.class, new LocalService());
        mHandler = new Handler(BackgroundThread.get().getLooper());
    }

    /**
     * System service lifecycle.
     */
    public static final class Lifecycle extends SystemService {
        final ShortcutService mService;

        public Lifecycle(Context context) {
            super(context);
            mService = new ShortcutService(context);
        }

        @Override
        public void onStart() {
            publishBinderService(Context.SHORTCUT_SERVICE, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            mService.onBootPhase(phase);
        }

        @Override
        public void onCleanupUser(int userHandle) {
            synchronized (mService.mLock) {
                mService.onCleanupUserInner(userHandle);
            }
        }

        @Override
        public void onUnlockUser(int userId) {
            synchronized (mService.mLock) {
                mService.onStartUserLocked(userId);
            }
        }
    }

    /** lifecycle event */
    void onBootPhase(int phase) {
        if (DEBUG) {
            Slog.d(TAG, "onBootPhase: " + phase);
        }
        switch (phase) {
            case SystemService.PHASE_LOCK_SETTINGS_READY:
                initialize();
                break;
        }
    }

    /** lifecycle event */
    void onStartUserLocked(int userId) {
        // Preload
        getUserShortcutsLocked(userId);
    }

    /** lifecycle event */
    void onCleanupUserInner(int userId) {
        // Unload
        mUsers.delete(userId);
    }

    /** Return the base state file name */
    private AtomicFile getBaseStateFile() {
        final File path = new File(injectSystemDataPath(), FILENAME_BASE_STATE);
        path.mkdirs();
        return new AtomicFile(path);
    }

    /**
     * Init the instance. (load the state file, etc)
     */
    private void initialize() {
        synchronized (mLock) {
            loadConfigurationLocked();
            loadBaseStateLocked();
        }
    }

    /**
     * Load the configuration from Settings.
     */
    private void loadConfigurationLocked() {
        updateConfigurationLocked(injectShortcutManagerConstants());
    }

    /**
     * Load the configuration from Settings.
     */
    @VisibleForTesting
    boolean updateConfigurationLocked(String config) {
        boolean result = true;

        final KeyValueListParser parser = new KeyValueListParser(',');
        try {
            parser.setString(config);
        } catch (IllegalArgumentException e) {
            // Failed to parse the settings string, log this and move on
            // with defaults.
            Slog.e(TAG, "Bad shortcut manager settings", e);
            result = false;
        }

        mResetInterval = parser.getLong(
                ConfigConstants.KEY_RESET_INTERVAL_SEC, DEFAULT_RESET_INTERVAL_SEC)
                * 1000L;

        mMaxDailyUpdates = (int) parser.getLong(
                ConfigConstants.KEY_MAX_DAILY_UPDATES, DEFAULT_MAX_DAILY_UPDATES);

        mMaxDynamicShortcuts = (int) parser.getLong(
                ConfigConstants.KEY_MAX_SHORTCUTS, DEFAULT_MAX_SHORTCUTS_PER_APP);

        final int iconDimensionDp = injectIsLowRamDevice()
                ? (int) parser.getLong(
                    ConfigConstants.KEY_MAX_ICON_DIMENSION_DP_LOWRAM,
                    DEFAULT_MAX_ICON_DIMENSION_LOWRAM_DP)
                : (int) parser.getLong(
                    ConfigConstants.KEY_MAX_ICON_DIMENSION_DP,
                    DEFAULT_MAX_ICON_DIMENSION_DP);

        mMaxIconDimension = injectDipToPixel(iconDimensionDp);

        mIconPersistFormat = CompressFormat.valueOf(
                parser.getString(ConfigConstants.KEY_ICON_FORMAT, DEFAULT_ICON_PERSIST_FORMAT));

        mIconPersistQuality = (int) parser.getLong(
                ConfigConstants.KEY_ICON_QUALITY,
                DEFAULT_ICON_PERSIST_QUALITY);

        return result;
    }

    @VisibleForTesting
    String injectShortcutManagerConstants() {
        return android.provider.Settings.Global.getString(
                mContext.getContentResolver(),
                android.provider.Settings.Global.SHORTCUT_MANAGER_CONSTANTS);
    }

    @VisibleForTesting
    int injectDipToPixel(int dip) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip,
                mContext.getResources().getDisplayMetrics());
    }

    // === Persisting ===

    @Nullable
    static String parseStringAttribute(XmlPullParser parser, String attribute) {
        return parser.getAttributeValue(null, attribute);
    }

    static int parseIntAttribute(XmlPullParser parser, String attribute) {
        return (int) parseLongAttribute(parser, attribute);
    }

    static long parseLongAttribute(XmlPullParser parser, String attribute) {
        final String value = parseStringAttribute(parser, attribute);
        if (TextUtils.isEmpty(value)) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Error parsing long " + value);
            return 0;
        }
    }

    @Nullable
    static ComponentName parseComponentNameAttribute(XmlPullParser parser, String attribute) {
        final String value = parseStringAttribute(parser, attribute);
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        return ComponentName.unflattenFromString(value);
    }

    @Nullable
    static Intent parseIntentAttribute(XmlPullParser parser, String attribute) {
        final String value = parseStringAttribute(parser, attribute);
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        try {
            return Intent.parseUri(value, /* flags =*/ 0);
        } catch (URISyntaxException e) {
            Slog.e(TAG, "Error parsing intent", e);
            return null;
        }
    }

    static void writeTagValue(XmlSerializer out, String tag, String value) throws IOException {
        if (TextUtils.isEmpty(value)) return;

        out.startTag(null, tag);
        out.attribute(null, ATTR_VALUE, value);
        out.endTag(null, tag);
    }

    static void writeTagValue(XmlSerializer out, String tag, long value) throws IOException {
        writeTagValue(out, tag, Long.toString(value));
    }

    static void writeTagExtra(XmlSerializer out, String tag, PersistableBundle bundle)
            throws IOException, XmlPullParserException {
        if (bundle == null) return;

        out.startTag(null, tag);
        bundle.saveToXml(out);
        out.endTag(null, tag);
    }

    static void writeAttr(XmlSerializer out, String name, String value) throws IOException {
        if (TextUtils.isEmpty(value)) return;

        out.attribute(null, name, value);
    }

    static void writeAttr(XmlSerializer out, String name, long value) throws IOException {
        writeAttr(out, name, String.valueOf(value));
    }

    static void writeAttr(XmlSerializer out, String name, ComponentName comp) throws IOException {
        if (comp == null) return;
        writeAttr(out, name, comp.flattenToString());
    }

    static void writeAttr(XmlSerializer out, String name, Intent intent) throws IOException {
        if (intent == null) return;

        writeAttr(out, name, intent.toUri(/* flags =*/ 0));
    }

    @VisibleForTesting
    void saveBaseStateLocked() {
        final AtomicFile file = getBaseStateFile();
        if (DEBUG) {
            Slog.i(TAG, "Saving to " + file.getBaseFile());
        }

        FileOutputStream outs = null;
        try {
            outs = file.startWrite();

            // Write to XML
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(outs, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);
            out.startTag(null, TAG_ROOT);

            // Body.
            writeTagValue(out, TAG_LAST_RESET_TIME, mRawLastResetTime);

            // Epilogue.
            out.endTag(null, TAG_ROOT);
            out.endDocument();

            // Close.
            file.finishWrite(outs);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write to file " + file.getBaseFile(), e);
            file.failWrite(outs);
        }
    }

    private void loadBaseStateLocked() {
        mRawLastResetTime = 0;

        final AtomicFile file = getBaseStateFile();
        if (DEBUG) {
            Slog.i(TAG, "Loading from " + file.getBaseFile());
        }
        try (FileInputStream in = file.openRead()) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, StandardCharsets.UTF_8.name());

            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }
                final int depth = parser.getDepth();
                // Check the root tag
                final String tag = parser.getName();
                if (depth == 1) {
                    if (!TAG_ROOT.equals(tag)) {
                        Slog.e(TAG, "Invalid root tag: " + tag);
                        return;
                    }
                    continue;
                }
                // Assume depth == 2
                switch (tag) {
                    case TAG_LAST_RESET_TIME:
                        mRawLastResetTime = parseLongAttribute(parser, ATTR_VALUE);
                        break;
                    default:
                        Slog.e(TAG, "Invalid tag: " + tag);
                        break;
                }
            }
        } catch (FileNotFoundException e) {
            // Use the default
        } catch (IOException|XmlPullParserException e) {
            Slog.e(TAG, "Failed to read file " + file.getBaseFile(), e);

            mRawLastResetTime = 0;
        }
        // Adjust the last reset time.
        getLastResetTimeLocked();
    }

    private void saveUserLocked(@UserIdInt int userId) {
        final File path = new File(injectUserDataPath(userId), FILENAME_USER_PACKAGES);
        if (DEBUG) {
            Slog.i(TAG, "Saving to " + path);
        }
        path.mkdirs();
        final AtomicFile file = new AtomicFile(path);
        FileOutputStream outs = null;
        try {
            outs = file.startWrite();

            // Write to XML
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(outs, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);

            getUserShortcutsLocked(userId).saveToXml(out);

            out.endDocument();

            // Close.
            file.finishWrite(outs);
        } catch (IOException|XmlPullParserException e) {
            Slog.e(TAG, "Failed to write to file " + file.getBaseFile(), e);
            file.failWrite(outs);
        }
    }

    static IOException throwForInvalidTag(int depth, String tag) throws IOException {
        throw new IOException(String.format("Invalid tag '%s' found at depth %d", tag, depth));
    }

    @Nullable
    private UserShortcuts loadUserLocked(@UserIdInt int userId) {
        final File path = new File(injectUserDataPath(userId), FILENAME_USER_PACKAGES);
        if (DEBUG) {
            Slog.i(TAG, "Loading from " + path);
        }
        final AtomicFile file = new AtomicFile(path);

        final FileInputStream in;
        try {
            in = file.openRead();
        } catch (FileNotFoundException e) {
            if (DEBUG) {
                Slog.i(TAG, "Not found " + path);
            }
            return null;
        }
        UserShortcuts ret = null;
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, StandardCharsets.UTF_8.name());

            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }
                final int depth = parser.getDepth();

                final String tag = parser.getName();
                if (DEBUG_LOAD) {
                    Slog.d(TAG, String.format("depth=%d type=%d name=%s",
                            depth, type, tag));
                }
                if ((depth == 1) && TAG_USER.equals(tag)) {
                    ret = UserShortcuts.loadFromXml(parser, userId);
                    continue;
                }
                throwForInvalidTag(depth, tag);
            }
            return ret;
        } catch (IOException|XmlPullParserException e) {
            Slog.e(TAG, "Failed to read file " + file.getBaseFile(), e);
            return null;
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    // TODO Actually make it async.
    private void scheduleSaveBaseState() {
        synchronized (mLock) {
            saveBaseStateLocked();
        }
    }

    // TODO Actually make it async.
    private void scheduleSaveUser(@UserIdInt int userId) {
        synchronized (mLock) {
            saveUserLocked(userId);
        }
    }

    /** Return the last reset time. */
    long getLastResetTimeLocked() {
        updateTimes();
        return mRawLastResetTime;
    }

    /** Return the next reset time. */
    long getNextResetTimeLocked() {
        updateTimes();
        return mRawLastResetTime + mResetInterval;
    }

    /**
     * Update the last reset time.
     */
    private void updateTimes() {

        final long now = injectCurrentTimeMillis();

        final long prevLastResetTime = mRawLastResetTime;

        if (mRawLastResetTime == 0) { // first launch.
            // TODO Randomize??
            mRawLastResetTime = now;
        } else if (now < mRawLastResetTime) {
            // Clock rewound.
            // TODO Randomize??
            mRawLastResetTime = now;
        } else {
            // TODO Do it properly.
            while ((mRawLastResetTime + mResetInterval) <= now) {
                mRawLastResetTime += mResetInterval;
            }
        }
        if (prevLastResetTime != mRawLastResetTime) {
            scheduleSaveBaseState();
        }
    }

    /** Return the per-user state. */
    @GuardedBy("mLock")
    @NonNull
    private UserShortcuts getUserShortcutsLocked(@UserIdInt int userId) {
        UserShortcuts userPackages = mUsers.get(userId);
        if (userPackages == null) {
            userPackages = loadUserLocked(userId);
            if (userPackages == null) {
                userPackages = new UserShortcuts(userId);
            }
            mUsers.put(userId, userPackages);
        }
        return userPackages;
    }

    /** Return the per-user per-package state. */
    @GuardedBy("mLock")
    @NonNull
    private PackageShortcuts getPackageShortcutsLocked(
            @NonNull String packageName, @UserIdInt int userId) {
        final UserShortcuts userPackages = getUserShortcutsLocked(userId);
        PackageShortcuts shortcuts = userPackages.getPackages().get(packageName);
        if (shortcuts == null) {
            shortcuts = new PackageShortcuts(userId, packageName);
            userPackages.getPackages().put(packageName, shortcuts);
        }
        return shortcuts;
    }

    // === Caller validation ===

    void removeIcon(@UserIdInt int userId, ShortcutInfo shortcut) {
        if (shortcut.getBitmapPath() != null) {
            if (DEBUG) {
                Slog.d(TAG, "Removing " + shortcut.getBitmapPath());
            }
            new File(shortcut.getBitmapPath()).delete();

            shortcut.setBitmapPath(null);
            shortcut.setIconResourceId(0);
            shortcut.clearFlags(ShortcutInfo.FLAG_HAS_ICON_FILE | ShortcutInfo.FLAG_HAS_ICON_RES);
        }
    }

    @VisibleForTesting
    static class FileOutputStreamWithPath extends FileOutputStream {
        private final File mFile;

        public FileOutputStreamWithPath(File file) throws FileNotFoundException {
            super(file);
            mFile = file;
        }

        public File getFile() {
            return mFile;
        }
    }

    /**
     * Build the cached bitmap filename for a shortcut icon.
     *
     * The filename will be based on the ID, except certain characters will be escaped.
     */
    @VisibleForTesting
    FileOutputStreamWithPath openIconFileForWrite(@UserIdInt int userId, ShortcutInfo shortcut)
            throws IOException {
        final File packagePath = new File(getUserBitmapFilePath(userId),
                shortcut.getPackageName());
        if (!packagePath.isDirectory()) {
            packagePath.mkdirs();
            if (!packagePath.isDirectory()) {
                throw new IOException("Unable to create directory " + packagePath);
            }
            SELinux.restorecon(packagePath);
        }

        final String baseName = String.valueOf(injectCurrentTimeMillis());
        for (int suffix = 0;; suffix++) {
            final String filename = (suffix == 0 ? baseName : baseName + "_" + suffix) + ".png";
            final File file = new File(packagePath, filename);
            if (!file.exists()) {
                if (DEBUG) {
                    Slog.d(TAG, "Saving icon to " + file.getAbsolutePath());
                }
                return new FileOutputStreamWithPath(file);
            }
        }
    }

    void saveIconAndFixUpShortcut(@UserIdInt int userId, ShortcutInfo shortcut) {
        if (shortcut.hasIconFile() || shortcut.hasIconResource()) {
            return;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            // Clear icon info on the shortcut.
            shortcut.setIconResourceId(0);
            shortcut.setBitmapPath(null);

            final Icon icon = shortcut.getIcon();
            if (icon == null) {
                return; // has no icon
            }

            Bitmap bitmap = null;
            try {
                switch (icon.getType()) {
                    case Icon.TYPE_RESOURCE: {
                        injectValidateIconResPackage(shortcut, icon);

                        shortcut.setIconResourceId(icon.getResId());
                        shortcut.addFlags(ShortcutInfo.FLAG_HAS_ICON_RES);
                        return;
                    }
                    case Icon.TYPE_BITMAP: {
                        bitmap = icon.getBitmap();
                        break;
                    }
                    case Icon.TYPE_URI: {
                        final Uri uri = ContentProvider.maybeAddUserId(icon.getUri(), userId);

                        try (InputStream is = mContext.getContentResolver().openInputStream(uri)) {

                            bitmap = BitmapFactory.decodeStream(is);

                        } catch (IOException e) {
                            Slog.e(TAG, "Unable to load icon from " + uri);
                            return;
                        }
                        break;
                    }
                    default:
                        // This shouldn't happen because we've already validated the icon, but
                        // just in case.
                        throw ShortcutInfo.getInvalidIconException();
                }
                if (bitmap == null) {
                    Slog.e(TAG, "Null bitmap detected");
                    return;
                }
                // Shrink and write to the file.
                File path = null;
                try {
                    final FileOutputStreamWithPath out = openIconFileForWrite(userId, shortcut);
                    try {
                        path = out.getFile();

                        shrinkBitmap(bitmap, mMaxIconDimension)
                                .compress(mIconPersistFormat, mIconPersistQuality, out);

                        shortcut.setBitmapPath(out.getFile().getAbsolutePath());
                        shortcut.addFlags(ShortcutInfo.FLAG_HAS_ICON_FILE);
                    } finally {
                        IoUtils.closeQuietly(out);
                    }
                } catch (IOException|RuntimeException e) {
                    // STOPSHIP Change wtf to e
                    Slog.wtf(ShortcutService.TAG, "Unable to write bitmap to file", e);
                    if (path != null && path.exists()) {
                        path.delete();
                    }
                }
            } finally {
                if (bitmap != null) {
                    bitmap.recycle();
                }
                // Once saved, we won't use the original icon information, so null it out.
                shortcut.clearIcon();
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Unfortunately we can't do this check in unit tests because we fake creator package names,
    // so override in unit tests.
    // TODO CTS this case.
    void injectValidateIconResPackage(ShortcutInfo shortcut, Icon icon) {
        if (!shortcut.getPackageName().equals(icon.getResPackage())) {
            throw new IllegalArgumentException(
                    "Icon resource must reside in shortcut owner package");
        }
    }

    @VisibleForTesting
    static Bitmap shrinkBitmap(Bitmap in, int maxSize) {
        // Original width/height.
        final int ow = in.getWidth();
        final int oh = in.getHeight();
        if ((ow <= maxSize) && (oh <= maxSize)) {
            if (DEBUG) {
                Slog.d(TAG, String.format("Icon size %dx%d, no need to shrink", ow, oh));
            }
            return in;
        }
        final int longerDimension = Math.max(ow, oh);

        // New width and height.
        final int nw = ow * maxSize / longerDimension;
        final int nh = oh * maxSize / longerDimension;
        if (DEBUG) {
            Slog.d(TAG, String.format("Icon size %dx%d, shrinking to %dx%d",
                    ow, oh, nw, nh));
        }

        final Bitmap scaledBitmap = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(scaledBitmap);

        final RectF dst = new RectF(0, 0, nw, nh);

        c.drawBitmap(in, /*src=*/ null, dst, /* paint =*/ null);

        in.recycle();

        return scaledBitmap;
    }

    // === Caller validation ===

    private boolean isCallerSystem() {
        final int callingUid = injectBinderCallingUid();
         return UserHandle.isSameApp(callingUid, Process.SYSTEM_UID);
    }

    private boolean isCallerShell() {
        final int callingUid = injectBinderCallingUid();
        return callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID;
    }

    private void enforceSystemOrShell() {
        Preconditions.checkState(isCallerSystem() || isCallerShell(),
                "Caller must be system or shell");
    }

    private void enforceShell() {
        Preconditions.checkState(isCallerShell(), "Caller must be shell");
    }

    private void verifyCaller(@NonNull String packageName, @UserIdInt int userId) {
        Preconditions.checkStringNotEmpty(packageName, "packageName");

        if (isCallerSystem()) {
            return; // no check
        }

        final int callingUid = injectBinderCallingUid();

        // Otherwise, make sure the arguments are valid.
        if (UserHandle.getUserId(callingUid) != userId) {
            throw new SecurityException("Invalid user-ID");
        }
        if (injectGetPackageUid(packageName, userId) == injectBinderCallingUid()) {
            return; // Caller is valid.
        }
        throw new SecurityException("Caller UID= doesn't own " + packageName);
    }

    // Test overrides it.
    int injectGetPackageUid(@NonNull String packageName, @UserIdInt int userId) {
        try {

            // TODO Is MATCH_UNINSTALLED_PACKAGES correct to get SD card app info?

            return mContext.getPackageManager().getPackageUidAsUser(packageName,
                    PackageManager.MATCH_ENCRYPTION_AWARE_AND_UNAWARE
                            | PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
        } catch (NameNotFoundException e) {
            return -1;
        }
    }

    /**
     * Throw if {@code numShortcuts} is bigger than {@link #mMaxDynamicShortcuts}.
     */
    void enforceMaxDynamicShortcuts(int numShortcuts) {
        if (numShortcuts > mMaxDynamicShortcuts) {
            throw new IllegalArgumentException("Max number of dynamic shortcuts exceeded");
        }
    }

    /**
     * - Sends a notification to LauncherApps
     * - Write to file
     */
    private void userPackageChanged(@NonNull String packageName, @UserIdInt int userId) {
        notifyListeners(packageName, userId);
        scheduleSaveUser(userId);
    }

    private void notifyListeners(@NonNull String packageName, @UserIdInt int userId) {
        final ArrayList<ShortcutChangeListener> copy;
        final List<ShortcutInfo> shortcuts = new ArrayList<>();
        synchronized (mLock) {
            copy = new ArrayList<>(mListeners);

            getPackageShortcutsLocked(packageName, userId)
                    .findAll(shortcuts, /* query =*/ null, ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO);
        }
        for (int i = copy.size() - 1; i >= 0; i--) {
            copy.get(i).onShortcutChanged(packageName, shortcuts, userId);
        }
    }

    /**
     * Clean up / validate an incoming shortcut.
     * - Make sure all mandatory fields are set.
     * - Make sure the intent's extras are persistable, and them to set
     *  {@link ShortcutInfo#mIntentPersistableExtras}.  Also clear its extras.
     * - Clear flags.
     *
     * TODO Detailed unit tests
     */
    private void fixUpIncomingShortcutInfo(@NonNull ShortcutInfo shortcut, boolean forUpdate) {
        Preconditions.checkNotNull(shortcut, "Null shortcut detected");
        if (shortcut.getActivityComponent() != null) {
            Preconditions.checkState(
                    shortcut.getPackageName().equals(
                            shortcut.getActivityComponent().getPackageName()),
                    "Activity package name mismatch");
        }

        if (!forUpdate) {
            shortcut.enforceMandatoryFields();
        }
        if (shortcut.getIcon() != null) {
            ShortcutInfo.validateIcon(shortcut.getIcon());
        }

        validateForXml(shortcut.getId());
        validateForXml(shortcut.getTitle());
        validatePersistableBundleForXml(shortcut.getIntentPersistableExtras());
        validatePersistableBundleForXml(shortcut.getExtras());

        shortcut.setFlags(0);
    }

    // KXmlSerializer is strict and doesn't allow certain characters, so we disallow those
    // characters.

    private static void validatePersistableBundleForXml(PersistableBundle b) {
        if (b == null || b.size() == 0) {
            return;
        }
        for (String key : b.keySet()) {
            validateForXml(key);
            final Object value = b.get(key);
            if (value == null) {
                continue;
            } else if (value instanceof String) {
                validateForXml((String) value);
            } else if (value instanceof String[]) {
                for (String v : (String[]) value) {
                    validateForXml(v);
                }
            } else if (value instanceof PersistableBundle) {
                validatePersistableBundleForXml((PersistableBundle) value);
            }
        }
    }

    private static void validateForXml(String s) {
        if (TextUtils.isEmpty(s)) {
            return;
        }
        for (int i = s.length() - 1; i >= 0; i--) {
            if (!isAllowedInXml(s.charAt(i))) {
                throw new IllegalArgumentException("Unsupported character detected in: " + s);
            }
        }
    }

    private static boolean isAllowedInXml(char c) {
        return (c >= 0x20 && c <= 0xd7ff) || (c >= 0xe000 && c <= 0xfffd);
    }

    // === APIs ===

    @Override
    public boolean setDynamicShortcuts(String packageName, ParceledListSlice shortcutInfoList,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        final List<ShortcutInfo> newShortcuts = (List<ShortcutInfo>) shortcutInfoList.getList();
        final int size = newShortcuts.size();

        synchronized (mLock) {
            final PackageShortcuts ps = getPackageShortcutsLocked(packageName, userId);

            // Throttling.
            if (!ps.tryApiCall(this)) {
                return false;
            }
            enforceMaxDynamicShortcuts(size);

            // Validate the shortcuts.
            for (int i = 0; i < size; i++) {
                fixUpIncomingShortcutInfo(newShortcuts.get(i), /* forUpdate= */ false);
            }

            // First, remove all un-pinned; dynamic shortcuts
            ps.deleteAllDynamicShortcuts(this);

            // Then, add/update all.  We need to make sure to take over "pinned" flag.
            for (int i = 0; i < size; i++) {
                final ShortcutInfo newShortcut = newShortcuts.get(i);
                newShortcut.addFlags(ShortcutInfo.FLAG_DYNAMIC);
                ps.updateShortcutWithCapping(this, newShortcut);
            }
        }
        userPackageChanged(packageName, userId);
        return true;
    }

    @Override
    public boolean updateShortcuts(String packageName, ParceledListSlice shortcutInfoList,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        final List<ShortcutInfo> newShortcuts = (List<ShortcutInfo>) shortcutInfoList.getList();
        final int size = newShortcuts.size();

        synchronized (mLock) {
            final PackageShortcuts ps = getPackageShortcutsLocked(packageName, userId);

            // Throttling.
            if (!ps.tryApiCall(this)) {
                return false;
            }

            for (int i = 0; i < size; i++) {
                final ShortcutInfo source = newShortcuts.get(i);
                fixUpIncomingShortcutInfo(source, /* forUpdate= */ true);

                final ShortcutInfo target = ps.findShortcutById(source.getId());
                if (target != null) {
                    final boolean replacingIcon = (source.getIcon() != null);
                    if (replacingIcon) {
                        removeIcon(userId, target);
                    }

                    target.copyNonNullFieldsFrom(source);

                    if (replacingIcon) {
                        saveIconAndFixUpShortcut(userId, target);
                    }
                }
            }
        }
        userPackageChanged(packageName, userId);

        return true;
    }

    @Override
    public boolean addDynamicShortcut(String packageName, ShortcutInfo newShortcut,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        synchronized (mLock) {
            final PackageShortcuts ps = getPackageShortcutsLocked(packageName, userId);

            // Throttling.
            if (!ps.tryApiCall(this)) {
                return false;
            }

            // Validate the shortcut.
            fixUpIncomingShortcutInfo(newShortcut, /* forUpdate= */ false);

            // Add it.
            newShortcut.addFlags(ShortcutInfo.FLAG_DYNAMIC);
            ps.updateShortcutWithCapping(this, newShortcut);
        }
        userPackageChanged(packageName, userId);

        return true;
    }

    @Override
    public void deleteDynamicShortcut(String packageName, String shortcutId,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);
        Preconditions.checkStringNotEmpty(shortcutId, "shortcutId must be provided");

        synchronized (mLock) {
            getPackageShortcutsLocked(packageName, userId).deleteDynamicWithId(this, shortcutId);
        }
        userPackageChanged(packageName, userId);
    }

    @Override
    public void deleteAllDynamicShortcuts(String packageName, @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        synchronized (mLock) {
            getPackageShortcutsLocked(packageName, userId).deleteAllDynamicShortcuts(this);
        }
        userPackageChanged(packageName, userId);
    }

    @Override
    public ParceledListSlice<ShortcutInfo> getDynamicShortcuts(String packageName,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);
        synchronized (mLock) {
            return getShortcutsWithQueryLocked(
                    packageName, userId, ShortcutInfo.CLONE_REMOVE_FOR_CREATOR,
                    ShortcutInfo::isDynamic);
        }
    }

    @Override
    public ParceledListSlice<ShortcutInfo> getPinnedShortcuts(String packageName,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);
        synchronized (mLock) {
            return getShortcutsWithQueryLocked(
                    packageName, userId, ShortcutInfo.CLONE_REMOVE_FOR_CREATOR,
                    ShortcutInfo::isPinned);
        }
    }

    private ParceledListSlice<ShortcutInfo> getShortcutsWithQueryLocked(@NonNull String packageName,
            @UserIdInt int userId, int cloneFlags, @NonNull Predicate<ShortcutInfo> query) {

        final ArrayList<ShortcutInfo> ret = new ArrayList<>();

        getPackageShortcutsLocked(packageName, userId).findAll(ret, query, cloneFlags);

        return new ParceledListSlice<>(ret);
    }

    @Override
    public int getMaxDynamicShortcutCount(String packageName, @UserIdInt int userId)
            throws RemoteException {
        verifyCaller(packageName, userId);

        return mMaxDynamicShortcuts;
    }

    @Override
    public int getRemainingCallCount(String packageName, @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        synchronized (mLock) {
            return mMaxDailyUpdates
                    - getPackageShortcutsLocked(packageName, userId).getApiCallCount(this);
        }
    }

    @Override
    public long getRateLimitResetTime(String packageName, @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        synchronized (mLock) {
            return getNextResetTimeLocked();
        }
    }

    @Override
    public int getIconMaxDimensions(String packageName, int userId) throws RemoteException {
        synchronized (mLock) {
            return mMaxIconDimension;
        }
    }

    /**
     * Reset all throttling, for developer options and command line.  Only system/shell can call it.
     */
    @Override
    public void resetThrottling() {
        enforceSystemOrShell();

        resetThrottlingInner();
    }

    @VisibleForTesting
    void resetThrottlingInner() {
        synchronized (mLock) {
            mRawLastResetTime = injectCurrentTimeMillis();
        }
        scheduleSaveBaseState();
        Slog.i(TAG, "ShortcutManager: throttling counter reset");
    }

    /**
     * Entry point from {@link LauncherApps}.
     */
    private class LocalService extends ShortcutServiceInternal {
        @Override
        public List<ShortcutInfo> getShortcuts(
                @NonNull String callingPackage, long changedSince,
                @Nullable String packageName, @Nullable ComponentName componentName,
                int queryFlags, int userId) {
            final ArrayList<ShortcutInfo> ret = new ArrayList<>();
            final int cloneFlag =
                    ((queryFlags & ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY) == 0)
                            ? ShortcutInfo.CLONE_REMOVE_FOR_LAUNCHER
                            : ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO;

            synchronized (mLock) {
                if (packageName != null) {
                    getShortcutsInnerLocked(packageName, changedSince, componentName, queryFlags,
                            userId, ret, cloneFlag);
                } else {
                    final ArrayMap<String, PackageShortcuts> packages =
                            getUserShortcutsLocked(userId).getPackages();
                    for (int i = packages.size() - 1; i >= 0; i--) {
                        getShortcutsInnerLocked(
                                packages.keyAt(i),
                                changedSince, componentName, queryFlags, userId, ret, cloneFlag);
                    }
                }
            }
            return ret;
        }

        private void getShortcutsInnerLocked(@Nullable String packageName,long changedSince,
                @Nullable ComponentName componentName, int queryFlags,
                int userId, ArrayList<ShortcutInfo> ret, int cloneFlag) {
            getPackageShortcutsLocked(packageName, userId).findAll(ret,
                    (ShortcutInfo si) -> {
                        if (si.getLastChangedTimestamp() < changedSince) {
                            return false;
                        }
                        if (componentName != null
                                && !componentName.equals(si.getActivityComponent())) {
                            return false;
                        }
                        final boolean matchDynamic =
                                ((queryFlags & ShortcutQuery.FLAG_GET_DYNAMIC) != 0)
                                && si.isDynamic();
                        final boolean matchPinned =
                                ((queryFlags & ShortcutQuery.FLAG_GET_PINNED) != 0)
                                        && si.isPinned();
                        return matchDynamic || matchPinned;
                    }, cloneFlag);
        }

        @Override
        public List<ShortcutInfo> getShortcutInfo(
                @NonNull String callingPackage,
                @NonNull String packageName, @Nullable List<String> ids, int userId) {
            // Calling permission must be checked by LauncherAppsImpl.
            Preconditions.checkStringNotEmpty(packageName, "packageName");

            final ArrayList<ShortcutInfo> ret = new ArrayList<>(ids.size());
            final ArraySet<String> idSet = new ArraySet<>(ids);
            synchronized (mLock) {
                getPackageShortcutsLocked(packageName, userId).findAll(ret,
                        (ShortcutInfo si) -> idSet.contains(si.getId()),
                        ShortcutInfo.CLONE_REMOVE_FOR_LAUNCHER);
            }
            return ret;
        }

        @Override
        public void pinShortcuts(@NonNull String callingPackage, @NonNull String packageName,
                @NonNull List<String> shortcutIds, int userId) {
            // Calling permission must be checked by LauncherAppsImpl.
            Preconditions.checkStringNotEmpty(packageName, "packageName");
            Preconditions.checkNotNull(shortcutIds, "shortcutIds");

            synchronized (mLock) {
                getPackageShortcutsLocked(packageName, userId).replacePinned(
                        ShortcutService.this, callingPackage, shortcutIds);
            }
            userPackageChanged(packageName, userId);
        }

        @Override
        public Intent createShortcutIntent(@NonNull String callingPackage,
                @NonNull String packageName, @NonNull String shortcutId, int userId) {
            // Calling permission must be checked by LauncherAppsImpl.
            Preconditions.checkStringNotEmpty(packageName, "packageName can't be empty");
            Preconditions.checkStringNotEmpty(shortcutId, "shortcutId can't be empty");

            synchronized (mLock) {
                final ShortcutInfo fullShortcut =
                        getPackageShortcutsLocked(packageName, userId)
                        .findShortcutById(shortcutId);
                return fullShortcut == null ? null : fullShortcut.getIntent();
            }
        }

        @Override
        public void addListener(@NonNull ShortcutChangeListener listener) {
            synchronized (mLock) {
                mListeners.add(Preconditions.checkNotNull(listener));
            }
        }

        @Override
        public int getShortcutIconResId(@NonNull String callingPackage,
                @NonNull ShortcutInfo shortcut, int userId) {
            Preconditions.checkNotNull(shortcut, "shortcut");

            synchronized (mLock) {
                final ShortcutInfo shortcutInfo = getPackageShortcutsLocked(
                        shortcut.getPackageName(), userId).findShortcutById(shortcut.getId());
                return (shortcutInfo != null && shortcutInfo.hasIconResource())
                        ? shortcutInfo.getIconResourceId() : 0;
            }
        }

        @Override
        public ParcelFileDescriptor getShortcutIconFd(@NonNull String callingPackage,
                @NonNull ShortcutInfo shortcutIn, int userId) {
            Preconditions.checkNotNull(shortcutIn, "shortcut");

            synchronized (mLock) {
                final ShortcutInfo shortcutInfo = getPackageShortcutsLocked(
                        shortcutIn.getPackageName(), userId).findShortcutById(shortcutIn.getId());
                if (shortcutInfo == null || !shortcutInfo.hasIconFile()) {
                    return null;
                }
                try {
                    if (shortcutInfo.getBitmapPath() == null) {
                        Slog.w(TAG, "null bitmap detected in getShortcutIconFd()");
                        return null;
                    }
                    return ParcelFileDescriptor.open(
                            new File(shortcutInfo.getBitmapPath()),
                            ParcelFileDescriptor.MODE_READ_ONLY);
                } catch (FileNotFoundException e) {
                    Slog.e(TAG, "Icon file not found: " + shortcutInfo.getBitmapPath());
                    return null;
                }
            }
        }
    }

    // === Dump ===

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump UserManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " without permission "
                    + android.Manifest.permission.DUMP);
            return;
        }
        dumpInner(pw);
    }

    @VisibleForTesting
    void dumpInner(PrintWriter pw) {
        synchronized (mLock) {
            final long now = injectCurrentTimeMillis();
            pw.print("Now: [");
            pw.print(now);
            pw.print("] ");
            pw.print(formatTime(now));

            pw.print("  Raw last reset: [");
            pw.print(mRawLastResetTime);
            pw.print("] ");
            pw.print(formatTime(mRawLastResetTime));

            final long last = getLastResetTimeLocked();
            pw.print("  Last reset: [");
            pw.print(last);
            pw.print("] ");
            pw.print(formatTime(last));

            final long next = getNextResetTimeLocked();
            pw.print("  Next reset: [");
            pw.print(next);
            pw.print("] ");
            pw.print(formatTime(next));
            pw.println();

            pw.print("  Max icon dim: ");
            pw.print(mMaxIconDimension);
            pw.print("  Icon format: ");
            pw.print(mIconPersistFormat);
            pw.print("  Icon quality: ");
            pw.print(mIconPersistQuality);
            pw.println();


            for (int i = 0; i < mUsers.size(); i++) {
                pw.println();
                mUsers.valueAt(i).dump(this, pw, "  ");
            }
        }
    }

    static String formatTime(long time) {
        Time tobj = new Time();
        tobj.set(time);
        return tobj.format("%Y-%m-%d %H:%M:%S");
    }

    // === Shell support ===

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ResultReceiver resultReceiver) throws RemoteException {

        enforceShell();

        (new MyShellCommand()).exec(this, in, out, err, args, resultReceiver);
    }

    /**
     * Handle "adb shell cmd".
     */
    private class MyShellCommand extends ShellCommand {
        @Override
        public int onCommand(String cmd) {
            if (cmd == null) {
                return handleDefaultCommands(cmd);
            }
            final PrintWriter pw = getOutPrintWriter();
            int ret = 1;
            switch (cmd) {
                case "reset-package-throttling":
                    ret = handleResetPackageThrottling();
                    break;
                case "reset-throttling":
                    ret = handleResetThrottling();
                    break;
                case "override-config":
                    ret = handleOverrideConfig();
                    break;
                case "reset-config":
                    ret = handleResetConfig();
                    break;
                default:
                    return handleDefaultCommands(cmd);
            }
            if (ret == 0) {
                pw.println("Success");
            }
            return ret;
        }

        @Override
        public void onHelp() {
            final PrintWriter pw = getOutPrintWriter();
            pw.println("Usage: cmd shortcut COMMAND [options ...]");
            pw.println();
            pw.println("cmd shortcut reset-package-throttling [--user USER_ID] PACKAGE");
            pw.println("    Reset throttling for a package");
            pw.println();
            pw.println("cmd shortcut reset-throttling");
            pw.println("    Reset throttling for all packages and users");
            pw.println();
            pw.println("cmd shortcut override-config CONFIG");
            pw.println("    Override the configuration for testing (will last until reboot)");
            pw.println();
            pw.println("cmd shortcut reset-config");
            pw.println("    Reset the configuration set with \"update-config\"");
            pw.println();
        }

        private int handleResetThrottling() {
            resetThrottling();
            return 0;
        }

        private int handleResetPackageThrottling() {
            final PrintWriter pw = getOutPrintWriter();

            int userId = UserHandle.USER_SYSTEM;
            String opt;
            while ((opt = getNextOption()) != null) {
                switch (opt) {
                    case "--user":
                        userId = UserHandle.parseUserArg(getNextArgRequired());
                        break;
                    default:
                        pw.println("Error: Unknown option: " + opt);
                        return 1;
                }
            }
            final String packageName = getNextArgRequired();

            synchronized (mLock) {
                getPackageShortcutsLocked(packageName, userId).resetRateLimitingForCommandLine();
                saveUserLocked(userId);
            }

            return 0;
        }

        private int handleOverrideConfig() {
            final PrintWriter pw = getOutPrintWriter();
            final String config = getNextArgRequired();

            synchronized (mLock) {
                if (!updateConfigurationLocked(config)) {
                    pw.println("override-config failed.  See logcat for details.");
                    return 1;
                }
            }
            return 0;
        }

        private int handleResetConfig() {
            synchronized (mLock) {
                loadConfigurationLocked();
            }
            return 0;
        }
    }

    // === Unit test support ===

    // Injection point.
    long injectCurrentTimeMillis() {
        return System.currentTimeMillis();
    }

    // Injection point.
    int injectBinderCallingUid() {
        return getCallingUid();
    }

    File injectSystemDataPath() {
        return Environment.getDataSystemDirectory();
    }

    File injectUserDataPath(@UserIdInt int userId) {
        return new File(Environment.getDataSystemCeDirectory(userId), DIRECTORY_PER_USER);
    }

    @VisibleForTesting
    boolean injectIsLowRamDevice() {
        return ActivityManager.isLowRamDeviceStatic();
    }

    File getUserBitmapFilePath(@UserIdInt int userId) {
        return new File(injectUserDataPath(userId), DIRECTORY_BITMAPS);
    }

    @VisibleForTesting
    SparseArray<UserShortcuts> getShortcutsForTest() {
        return mUsers;
    }

    @VisibleForTesting
    int getMaxDynamicShortcutsForTest() {
        return mMaxDynamicShortcuts;
    }

    @VisibleForTesting
    int getMaxDailyUpdatesForTest() {
        return mMaxDailyUpdates;
    }

    @VisibleForTesting
    long getResetIntervalForTest() {
        return mResetInterval;
    }

    @VisibleForTesting
    int getMaxIconDimensionForTest() {
        return mMaxIconDimension;
    }

    @VisibleForTesting
    CompressFormat getIconPersistFormatForTest() {
        return mIconPersistFormat;
    }

    @VisibleForTesting
    int getIconPersistQualityForTest() {
        return mIconPersistQuality;
    }

    @VisibleForTesting
    ShortcutInfo getPackageShortcutForTest(String packageName, String shortcutId, int userId) {
        synchronized (mLock) {
            return getPackageShortcutsLocked(packageName, userId).findShortcutById(shortcutId);
        }
    }
}

class UserShortcuts {
    private static final String TAG = ShortcutService.TAG;

    @UserIdInt
    final int mUserId;

    private final ArrayMap<String, PackageShortcuts> mPackages = new ArrayMap<>();

    public UserShortcuts(int userId) {
        mUserId = userId;
    }

    public ArrayMap<String, PackageShortcuts> getPackages() {
        return mPackages;
    }

    public void saveToXml(XmlSerializer out) throws IOException, XmlPullParserException {
        out.startTag(null, ShortcutService.TAG_USER);

        for (int i = 0; i < mPackages.size(); i++) {
            final String packageName = mPackages.keyAt(i);
            final PackageShortcuts packageShortcuts = mPackages.valueAt(i);

            packageShortcuts.saveToXml(out);
        }

        out.endTag(null, ShortcutService.TAG_USER);
    }

    public static UserShortcuts loadFromXml(XmlPullParser parser, int userId)
            throws IOException, XmlPullParserException {
        final UserShortcuts ret = new UserShortcuts(userId);

        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            final int depth = parser.getDepth();
            final String tag = parser.getName();
            switch (tag) {
                case ShortcutService.TAG_PACKAGE:
                    final PackageShortcuts shortcuts = PackageShortcuts.loadFromXml(parser, userId);

                    // Don't use addShortcut(), we don't need to save the icon.
                    ret.getPackages().put(shortcuts.mPackageName, shortcuts);
                    continue;
            }
            throw ShortcutService.throwForInvalidTag(depth, tag);
        }
        return ret;
    }

    public void dump(@NonNull ShortcutService s, @NonNull PrintWriter pw, @NonNull String prefix) {
        pw.print("  ");
        pw.print("User: ");
        pw.print(mUserId);
        pw.println();

        for (int i = 0; i < mPackages.size(); i++) {
            mPackages.valueAt(i).dump(s, pw, prefix + "  ");
        }
    }
}

/**
 * All the information relevant to shortcuts from a single package (per-user).
 */
class PackageShortcuts {
    private static final String TAG = ShortcutService.TAG;

    @UserIdInt
    final int mUserId;

    @NonNull
    final String mPackageName;

    /**
     * All the shortcuts from the package, keyed on IDs.
     */
    final private ArrayMap<String, ShortcutInfo> mShortcuts = new ArrayMap<>();

    /**
     * # of dynamic shortcuts.
     */
    private int mDynamicShortcutCount = 0;

    /**
     * # of times the package has called rate-limited APIs.
     */
    private int mApiCallCount;

    /**
     * When {@link #mApiCallCount} was reset last time.
     */
    private long mLastResetTime;

    PackageShortcuts(int userId, String packageName) {
        mUserId = userId;
        mPackageName = packageName;
    }

    @GuardedBy("mLock")
    @Nullable
    public ShortcutInfo findShortcutById(String id) {
        return mShortcuts.get(id);
    }

    private ShortcutInfo deleteShortcut(@NonNull ShortcutService s,
            @NonNull String id) {
        final ShortcutInfo shortcut = mShortcuts.remove(id);
        if (shortcut != null) {
            s.removeIcon(mUserId, shortcut);
            shortcut.clearFlags(ShortcutInfo.FLAG_DYNAMIC | ShortcutInfo.FLAG_PINNED);
        }
        return shortcut;
    }

    void addShortcut(@NonNull ShortcutService s, @NonNull ShortcutInfo newShortcut) {
        deleteShortcut(s, newShortcut.getId());
        s.saveIconAndFixUpShortcut(mUserId, newShortcut);
        mShortcuts.put(newShortcut.getId(), newShortcut);
    }

    /**
     * Add a shortcut, or update one with the same ID, with taking over existing flags.
     *
     * It checks the max number of dynamic shortcuts.
     */
    @GuardedBy("mLock")
    public void updateShortcutWithCapping(@NonNull ShortcutService s,
            @NonNull ShortcutInfo newShortcut) {
        final ShortcutInfo oldShortcut = mShortcuts.get(newShortcut.getId());

        int oldFlags = 0;
        int newDynamicCount = mDynamicShortcutCount;

        if (oldShortcut != null) {
            oldFlags = oldShortcut.getFlags();
            if (oldShortcut.isDynamic()) {
                newDynamicCount--;
            }
        }
        if (newShortcut.isDynamic()) {
            newDynamicCount++;
        }
        // Make sure there's still room.
        s.enforceMaxDynamicShortcuts(newDynamicCount);

        // Okay, make it dynamic and add.
        newShortcut.addFlags(oldFlags);

        addShortcut(s, newShortcut);
        mDynamicShortcutCount = newDynamicCount;
    }

    /**
     * Remove all shortcuts that aren't pinned nor dynamic.
     */
    private void removeOrphans(@NonNull ShortcutService s) {
        ArrayList<String> removeList = null; // Lazily initialize.

        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            final ShortcutInfo si = mShortcuts.valueAt(i);

            if (si.isPinned() || si.isDynamic()) continue;

            if (removeList == null) {
                removeList = new ArrayList<>();
            }
            removeList.add(si.getId());
        }
        if (removeList != null) {
            for (int i = removeList.size() - 1 ; i >= 0; i--) {
                deleteShortcut(s, removeList.get(i));
            }
        }
    }

    @GuardedBy("mLock")
    public void deleteAllDynamicShortcuts(@NonNull ShortcutService s) {
        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            mShortcuts.valueAt(i).clearFlags(ShortcutInfo.FLAG_DYNAMIC);
        }
        removeOrphans(s);
        mDynamicShortcutCount = 0;
    }

    @GuardedBy("mLock")
    public void deleteDynamicWithId(@NonNull ShortcutService s, @NonNull String shortcutId) {
        final ShortcutInfo oldShortcut = mShortcuts.get(shortcutId);

        if (oldShortcut == null) {
            return;
        }
        if (oldShortcut.isDynamic()) {
            mDynamicShortcutCount--;
        }
        if (oldShortcut.isPinned()) {
            oldShortcut.clearFlags(ShortcutInfo.FLAG_DYNAMIC);
        } else {
            deleteShortcut(s, shortcutId);
        }
    }

    @GuardedBy("mLock")
    public void replacePinned(@NonNull ShortcutService s, String launcherPackage,
            List<String> shortcutIds) {

        // TODO Should be per launcherPackage.

        // First, un-pin all shortcuts
        for (int i = mShortcuts.size() - 1; i >= 0; i--) {
            mShortcuts.valueAt(i).clearFlags(ShortcutInfo.FLAG_PINNED);
        }

        // Then pin ALL
        for (int i = shortcutIds.size() - 1; i >= 0; i--) {
            final ShortcutInfo shortcut = mShortcuts.get(shortcutIds.get(i));
            if (shortcut != null) {
                shortcut.addFlags(ShortcutInfo.FLAG_PINNED);
            }
        }

        removeOrphans(s);
    }

    /**
     * Number of calls that the caller has made, since the last reset.
     */
    @GuardedBy("mLock")
    public int getApiCallCount(@NonNull ShortcutService s) {
        final long last = s.getLastResetTimeLocked();

        final long now = s.injectCurrentTimeMillis();
        if (mLastResetTime > now) {
            // Clock rewound. // TODO Test it
            mLastResetTime = now;
        }

        // If not reset yet, then reset.
        if (mLastResetTime < last) {
            mApiCallCount = 0;
            mLastResetTime = last;
        }
        return mApiCallCount;
    }

    /**
     * If the caller app hasn't been throttled yet, increment {@link #mApiCallCount}
     * and return true.  Otherwise just return false.
     */
    @GuardedBy("mLock")
    public boolean tryApiCall(@NonNull ShortcutService s) {
        if (getApiCallCount(s) >= s.mMaxDailyUpdates) {
            return false;
        }
        mApiCallCount++;
        return true;
    }

    @GuardedBy("mLock")
    public void resetRateLimitingForCommandLine() {
        mApiCallCount = 0;
        mLastResetTime = 0;
    }

    /**
     * Find all shortcuts that match {@code query}.
     */
    @GuardedBy("mLock")
    public void findAll(@NonNull List<ShortcutInfo> result,
            @Nullable Predicate<ShortcutInfo> query, int cloneFlag) {
        for (int i = 0; i < mShortcuts.size(); i++) {
            final ShortcutInfo si = mShortcuts.valueAt(i);
            if (query == null || query.test(si)) {
                result.add(si.clone(cloneFlag));
            }
        }
    }

    public void dump(@NonNull ShortcutService s, @NonNull PrintWriter pw, @NonNull String prefix) {
        pw.print(prefix);
        pw.print("Package: ");
        pw.print(mPackageName);
        pw.println();

        pw.print(prefix);
        pw.print("  ");
        pw.print("Calls: ");
        pw.print(getApiCallCount(s));
        pw.println();

        // This should be after getApiCallCount(), which may update it.
        pw.print(prefix);
        pw.print("  ");
        pw.print("Last reset: [");
        pw.print(mLastResetTime);
        pw.print("] ");
        pw.print(s.formatTime(mLastResetTime));
        pw.println();

        pw.println("      Shortcuts:");
        long totalBitmapSize = 0;
        final ArrayMap<String, ShortcutInfo> shortcuts = mShortcuts;
        final int size = shortcuts.size();
        for (int i = 0; i < size; i++) {
            final ShortcutInfo si = shortcuts.valueAt(i);
            pw.print("        ");
            pw.println(si.toInsecureString());
            if (si.getBitmapPath() != null) {
                final long len = new File(si.getBitmapPath()).length();
                pw.print("          ");
                pw.print("bitmap size=");
                pw.println(len);

                totalBitmapSize += len;
            }
        }
        pw.print(prefix);
        pw.print("  ");
        pw.print("Total bitmap size: ");
        pw.print(totalBitmapSize);
        pw.print(" (");
        pw.print(Formatter.formatFileSize(s.mContext, totalBitmapSize));
        pw.println(")");
    }

    public void saveToXml(@NonNull XmlSerializer out) throws IOException, XmlPullParserException {
        out.startTag(null, ShortcutService.TAG_PACKAGE);

        ShortcutService.writeAttr(out, ShortcutService.ATTR_NAME, mPackageName);
        ShortcutService.writeAttr(out, ShortcutService.ATTR_DYNAMIC_COUNT, mDynamicShortcutCount);
        ShortcutService.writeAttr(out, ShortcutService.ATTR_CALL_COUNT, mApiCallCount);
        ShortcutService.writeAttr(out, ShortcutService.ATTR_LAST_RESET, mLastResetTime);

        final int size = mShortcuts.size();
        for (int j = 0; j < size; j++) {
            saveShortcut(out, mShortcuts.valueAt(j));
        }

        out.endTag(null, ShortcutService.TAG_PACKAGE);
    }

    private static void saveShortcut(XmlSerializer out, ShortcutInfo si)
            throws IOException, XmlPullParserException {
        out.startTag(null, ShortcutService.TAG_SHORTCUT);
        ShortcutService.writeAttr(out, ShortcutService.ATTR_ID, si.getId());
        // writeAttr(out, "package", si.getPackageName()); // not needed
        ShortcutService.writeAttr(out, ShortcutService.ATTR_ACTIVITY, si.getActivityComponent());
        // writeAttr(out, "icon", si.getIcon());  // We don't save it.
        ShortcutService.writeAttr(out, ShortcutService.ATTR_TITLE, si.getTitle());
        ShortcutService.writeAttr(out, ShortcutService.ATTR_INTENT, si.getIntentNoExtras());
        ShortcutService.writeAttr(out, ShortcutService.ATTR_WEIGHT, si.getWeight());
        ShortcutService.writeAttr(out, ShortcutService.ATTR_TIMESTAMP,
                si.getLastChangedTimestamp());
        ShortcutService.writeAttr(out, ShortcutService.ATTR_FLAGS, si.getFlags());
        ShortcutService.writeAttr(out, ShortcutService.ATTR_ICON_RES, si.getIconResourceId());
        ShortcutService.writeAttr(out, ShortcutService.ATTR_BITMAP_PATH, si.getBitmapPath());

        ShortcutService.writeTagExtra(out, ShortcutService.TAG_INTENT_EXTRAS,
                si.getIntentPersistableExtras());
        ShortcutService.writeTagExtra(out, ShortcutService.TAG_EXTRAS, si.getExtras());

        out.endTag(null, ShortcutService.TAG_SHORTCUT);
    }

    public static PackageShortcuts loadFromXml(XmlPullParser parser, int userId)
            throws IOException, XmlPullParserException {

        final String packageName = ShortcutService.parseStringAttribute(parser,
                ShortcutService.ATTR_NAME);

        final PackageShortcuts ret = new PackageShortcuts(userId, packageName);

        ret.mDynamicShortcutCount =
                ShortcutService.parseIntAttribute(parser, ShortcutService.ATTR_DYNAMIC_COUNT);
        ret.mApiCallCount =
                ShortcutService.parseIntAttribute(parser, ShortcutService.ATTR_CALL_COUNT);
        ret.mLastResetTime =
                ShortcutService.parseLongAttribute(parser, ShortcutService.ATTR_LAST_RESET);

        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            final int depth = parser.getDepth();
            final String tag = parser.getName();
            switch (tag) {
                case ShortcutService.TAG_SHORTCUT:
                    final ShortcutInfo si = parseShortcut(parser, packageName);

                    // Don't use addShortcut(), we don't need to save the icon.
                    ret.mShortcuts.put(si.getId(), si);
                    continue;
            }
            throw ShortcutService.throwForInvalidTag(depth, tag);
        }
        return ret;
    }

    private static ShortcutInfo parseShortcut(XmlPullParser parser, String packageName)
            throws IOException, XmlPullParserException {
        String id;
        ComponentName activityComponent;
        // Icon icon;
        String title;
        Intent intent;
        PersistableBundle intentPersistableExtras = null;
        int weight;
        PersistableBundle extras = null;
        long lastChangedTimestamp;
        int flags;
        int iconRes;
        String bitmapPath;

        id = ShortcutService.parseStringAttribute(parser, ShortcutService.ATTR_ID);
        activityComponent = ShortcutService.parseComponentNameAttribute(parser,
                ShortcutService.ATTR_ACTIVITY);
        title = ShortcutService.parseStringAttribute(parser, ShortcutService.ATTR_TITLE);
        intent = ShortcutService.parseIntentAttribute(parser, ShortcutService.ATTR_INTENT);
        weight = (int) ShortcutService.parseLongAttribute(parser, ShortcutService.ATTR_WEIGHT);
        lastChangedTimestamp = (int) ShortcutService.parseLongAttribute(parser,
                ShortcutService.ATTR_TIMESTAMP);
        flags = (int) ShortcutService.parseLongAttribute(parser, ShortcutService.ATTR_FLAGS);
        iconRes = (int) ShortcutService.parseLongAttribute(parser, ShortcutService.ATTR_ICON_RES);
        bitmapPath = ShortcutService.parseStringAttribute(parser, ShortcutService.ATTR_BITMAP_PATH);

        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            final int depth = parser.getDepth();
            final String tag = parser.getName();
            if (ShortcutService.DEBUG_LOAD) {
                Slog.d(TAG, String.format("  depth=%d type=%d name=%s",
                        depth, type, tag));
            }
            switch (tag) {
                case ShortcutService.TAG_INTENT_EXTRAS:
                    intentPersistableExtras = PersistableBundle.restoreFromXml(parser);
                    continue;
                case ShortcutService.TAG_EXTRAS:
                    extras = PersistableBundle.restoreFromXml(parser);
                    continue;
            }
            throw ShortcutService.throwForInvalidTag(depth, tag);
        }
        return new ShortcutInfo(
                id, packageName, activityComponent, /* icon =*/ null, title, intent,
                intentPersistableExtras, weight, extras, lastChangedTimestamp, flags,
                iconRes, bitmapPath);
    }
}
