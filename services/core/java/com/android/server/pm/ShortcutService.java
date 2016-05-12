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
import android.app.ActivityManagerNative;
import android.app.AppGlobals;
import android.app.IUidObserver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.IShortcutService;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.ShortcutQuery;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.ShortcutServiceInternal.ShortcutChangeListener;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SELinux;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.text.format.Time;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.KeyValueListParser;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.TypedValue;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.ShortcutUser.PackageWithUser;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * TODO:
 *
 * - Default launcher check does take a few ms.  Worth caching.
 *
 * - Clear data -> remove all dynamic?  but not the pinned?
 *
 * - Scan and remove orphan bitmaps (just in case).
 *
 * - Detect when already registered instances are passed to APIs again, which might break
 *   internal bitmap handling.
 *
 * - Add more call stats.
 */
public class ShortcutService extends IShortcutService.Stub {
    static final String TAG = "ShortcutService";

    public static final boolean FEATURE_ENABLED = false;

    static final boolean DEBUG = false; // STOPSHIP if true
    static final boolean DEBUG_LOAD = false; // STOPSHIP if true
    static final boolean DEBUG_PROCSTATE = false; // STOPSHIP if true

    @VisibleForTesting
    static final long DEFAULT_RESET_INTERVAL_SEC = 24 * 60 * 60; // 1 day

    @VisibleForTesting
    static final int DEFAULT_MAX_UPDATES_PER_INTERVAL = 10;

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

    @VisibleForTesting
    static final int DEFAULT_SAVE_DELAY_MS = 3000;

    @VisibleForTesting
    static final String FILENAME_BASE_STATE = "shortcut_service.xml";

    @VisibleForTesting
    static final String DIRECTORY_PER_USER = "shortcut_service";

    @VisibleForTesting
    static final String FILENAME_USER_PACKAGES = "shortcuts.xml";

    static final String DIRECTORY_BITMAPS = "bitmaps";

    private static final String TAG_ROOT = "root";
    private static final String TAG_LAST_RESET_TIME = "last_reset_time";
    private static final String TAG_LOCALE_CHANGE_SEQUENCE_NUMBER = "locale_seq_no";

    private static final String ATTR_VALUE = "value";

    @VisibleForTesting
    interface ConfigConstants {
        /**
         * Key name for the save delay, in milliseconds. (int)
         */
        String KEY_SAVE_DELAY_MILLIS = "save_delay_ms";

        /**
         * Key name for the throttling reset interval, in seconds. (long)
         */
        String KEY_RESET_INTERVAL_SEC = "reset_interval_sec";

        /**
         * Key name for the max number of modifying API calls per app for every interval. (int)
         */
        String KEY_MAX_UPDATES_PER_INTERVAL = "max_updates_per_interval";

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
    private final SparseArray<ShortcutUser> mUsers = new SparseArray<>();

    /**
     * Max number of dynamic shortcuts that each application can have at a time.
     */
    private int mMaxDynamicShortcuts;

    /**
     * Max number of updating API calls that each application can make during the interval.
     */
    int mMaxUpdatesPerInterval;

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

    private int mSaveDelayMillis;

    private final IPackageManager mIPackageManager;
    private final PackageManagerInternal mPackageManagerInternal;
    private final UserManager mUserManager;

    @GuardedBy("mLock")
    final SparseIntArray mUidState = new SparseIntArray();

    @GuardedBy("mLock")
    final SparseLongArray mUidLastForegroundElapsedTime = new SparseLongArray();

    @GuardedBy("mLock")
    private List<Integer> mDirtyUserIds = new ArrayList<>();

    /**
     * A counter that increments every time the system locale changes.  We keep track of it to reset
     * throttling counters on the first call from each package after the last locale change.
     *
     * We need this mechanism because we can't do much in the locale change callback, which is
     * {@link ShortcutServiceInternal#onSystemLocaleChangedNoLock()}.
     */
    private final AtomicLong mLocaleChangeSequenceNumber = new AtomicLong();

    private static final int PACKAGE_MATCH_FLAGS =
            PackageManager.MATCH_DIRECT_BOOT_AWARE
            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
            | PackageManager.MATCH_UNINSTALLED_PACKAGES;

    // Stats
    @VisibleForTesting
    interface Stats {
        int GET_DEFAULT_HOME = 0;
        int GET_PACKAGE_INFO = 1;
        int GET_PACKAGE_INFO_WITH_SIG = 2;
        int GET_APPLICATION_INFO = 3;
        int LAUNCHER_PERMISSION_CHECK = 4;

        int COUNT = LAUNCHER_PERMISSION_CHECK + 1;
    }

    final Object mStatLock = new Object();

    @GuardedBy("mStatLock")
    private final int[] mCountStats = new int[Stats.COUNT];

    @GuardedBy("mStatLock")
    private final long[] mDurationStats = new long[Stats.COUNT];

    private static final int PROCESS_STATE_FOREGROUND_THRESHOLD =
            ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;

    public ShortcutService(Context context) {
        this(context, BackgroundThread.get().getLooper());
    }

    @VisibleForTesting
    ShortcutService(Context context, Looper looper) {
        mContext = Preconditions.checkNotNull(context);
        LocalServices.addService(ShortcutServiceInternal.class, new LocalService());
        mHandler = new Handler(looper);
        mIPackageManager = AppGlobals.getPackageManager();
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mUserManager = context.getSystemService(UserManager.class);

        if (!FEATURE_ENABLED) {
            return;
        }
        mPackageMonitor.register(context, looper, UserHandle.ALL, /* externalStorage= */ false);

        injectRegisterUidObserver(mUidObserver, ActivityManager.UID_OBSERVER_PROCSTATE
                | ActivityManager.UID_OBSERVER_GONE);
    }

    void logDurationStat(int statId, long start) {
        synchronized (mStatLock) {
            mCountStats[statId]++;
            mDurationStats[statId] += (System.currentTimeMillis() - start);
        }
    }

    public long getLocaleChangeSequenceNumber() {
        return mLocaleChangeSequenceNumber.get();
    }

    final private IUidObserver mUidObserver = new IUidObserver.Stub() {
        @Override public void onUidStateChanged(int uid, int procState) throws RemoteException {
            handleOnUidStateChanged(uid, procState);
        }

        @Override public void onUidGone(int uid) throws RemoteException {
            handleOnUidStateChanged(uid, ActivityManager.MAX_PROCESS_STATE);
        }

        @Override public void onUidActive(int uid) throws RemoteException {
        }

        @Override public void onUidIdle(int uid) throws RemoteException {
        }
    };

    void handleOnUidStateChanged(int uid, int procState) {
        if (DEBUG_PROCSTATE) {
            Slog.d(TAG, "onUidStateChanged: uid=" + uid + " state=" + procState);
        }
        synchronized (mLock) {
            mUidState.put(uid, procState);

            // We need to keep track of last time an app comes to foreground.
            // See ShortcutPackage.getApiCallCount() for how it's used.
            // It doesn't have to be persisted, but it needs to be the elapsed time.
            if (isProcessStateForeground(procState)) {
                mUidLastForegroundElapsedTime.put(uid, injectElapsedRealtime());
            }
        }
    }

    private boolean isProcessStateForeground(int processState) {
        return processState <= PROCESS_STATE_FOREGROUND_THRESHOLD;
    }

    boolean isUidForegroundLocked(int uid) {
        if (uid == Process.SYSTEM_UID) {
            // IUidObserver doesn't report the state of SYSTEM, but it always has bound services,
            // so it's foreground anyway.
            return true;
        }
        return isProcessStateForeground(mUidState.get(uid, ActivityManager.MAX_PROCESS_STATE));
    }

    long getUidLastForegroundElapsedTimeLocked(int uid) {
        return mUidLastForegroundElapsedTime.get(uid);
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
            mService.handleCleanupUser(userHandle);
        }

        @Override
        public void onUnlockUser(int userId) {
            mService.handleUnlockUser(userId);
        }
    }

    /** lifecycle event */
    void onBootPhase(int phase) {
        // We want to call initialize() to initialize the configurations, so we don't disable this.
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
    void handleUnlockUser(int userId) {
        if (!FEATURE_ENABLED) {
            return;
        }
        synchronized (mLock) {
            // Preload
            getUserShortcutsLocked(userId);

            checkPackageChanges(userId);
        }
    }

    /** lifecycle event */
    void handleCleanupUser(int userId) {
        if (!FEATURE_ENABLED) {
            return;
        }
        synchronized (mLock) {
            unloadUserLocked(userId);
        }
    }

    private void unloadUserLocked(int userId) {
        if (DEBUG) {
            Slog.d(TAG, "unloadUserLocked: user=" + userId);
        }
        // Save all dirty information.
        saveDirtyInfo();

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

        mSaveDelayMillis = Math.max(0, (int) parser.getLong(ConfigConstants.KEY_SAVE_DELAY_MILLIS,
                DEFAULT_SAVE_DELAY_MS));

        mResetInterval = Math.max(1, parser.getLong(
                ConfigConstants.KEY_RESET_INTERVAL_SEC, DEFAULT_RESET_INTERVAL_SEC)
                * 1000L);

        mMaxUpdatesPerInterval = Math.max(0, (int) parser.getLong(
                ConfigConstants.KEY_MAX_UPDATES_PER_INTERVAL, DEFAULT_MAX_UPDATES_PER_INTERVAL));

        mMaxDynamicShortcuts = Math.max(0, (int) parser.getLong(
                ConfigConstants.KEY_MAX_SHORTCUTS, DEFAULT_MAX_SHORTCUTS_PER_APP));

        final int iconDimensionDp = Math.max(1, injectIsLowRamDevice()
                ? (int) parser.getLong(
                    ConfigConstants.KEY_MAX_ICON_DIMENSION_DP_LOWRAM,
                    DEFAULT_MAX_ICON_DIMENSION_LOWRAM_DP)
                : (int) parser.getLong(
                    ConfigConstants.KEY_MAX_ICON_DIMENSION_DP,
                    DEFAULT_MAX_ICON_DIMENSION_DP));

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

    static boolean parseBooleanAttribute(XmlPullParser parser, String attribute) {
        return parseLongAttribute(parser, attribute) == 1;
    }

    static int parseIntAttribute(XmlPullParser parser, String attribute) {
        return (int) parseLongAttribute(parser, attribute);
    }

    static int parseIntAttribute(XmlPullParser parser, String attribute, int def) {
        return (int) parseLongAttribute(parser, attribute, def);
    }

    static long parseLongAttribute(XmlPullParser parser, String attribute) {
        return parseLongAttribute(parser, attribute, 0);
    }

    static long parseLongAttribute(XmlPullParser parser, String attribute, long def) {
        final String value = parseStringAttribute(parser, attribute);
        if (TextUtils.isEmpty(value)) {
            return def;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Error parsing long " + value);
            return def;
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

    static void writeTagValue(XmlSerializer out, String tag, ComponentName name) throws IOException {
        if (name == null) return;
        writeTagValue(out, tag, name.flattenToString());
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

    static void writeAttr(XmlSerializer out, String name, boolean value) throws IOException {
        if (value) {
            writeAttr(out, name, "1");
        }
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
            Slog.d(TAG, "Saving to " + file.getBaseFile());
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
            writeTagValue(out, TAG_LOCALE_CHANGE_SEQUENCE_NUMBER,
                    mLocaleChangeSequenceNumber.get());

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
            Slog.d(TAG, "Loading from " + file.getBaseFile());
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
                    case TAG_LOCALE_CHANGE_SEQUENCE_NUMBER:
                        mLocaleChangeSequenceNumber.set(parseLongAttribute(parser, ATTR_VALUE));
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
            Slog.d(TAG, "Saving to " + path);
        }
        path.mkdirs();
        final AtomicFile file = new AtomicFile(path);
        FileOutputStream os = null;
        try {
            os = file.startWrite();

            saveUserInternalLocked(userId, os, /* forBackup= */ false);

            file.finishWrite(os);
        } catch (XmlPullParserException|IOException e) {
            Slog.e(TAG, "Failed to write to file " + file.getBaseFile(), e);
            file.failWrite(os);
        }
    }

    private void saveUserInternalLocked(@UserIdInt int userId, OutputStream os,
            boolean forBackup) throws IOException, XmlPullParserException {

        final BufferedOutputStream bos = new BufferedOutputStream(os);

        // Write to XML
        XmlSerializer out = new FastXmlSerializer();
        out.setOutput(bos, StandardCharsets.UTF_8.name());
        out.startDocument(null, true);

        getUserShortcutsLocked(userId).saveToXml(this, out, forBackup);

        out.endDocument();

        bos.flush();
        os.flush();
    }

    static IOException throwForInvalidTag(int depth, String tag) throws IOException {
        throw new IOException(String.format("Invalid tag '%s' found at depth %d", tag, depth));
    }

    static void warnForInvalidTag(int depth, String tag) throws IOException {
        Slog.w(TAG, String.format("Invalid tag '%s' found at depth %d", tag, depth));
    }

    @Nullable
    private ShortcutUser loadUserLocked(@UserIdInt int userId) {
        final File path = new File(injectUserDataPath(userId), FILENAME_USER_PACKAGES);
        if (DEBUG) {
            Slog.d(TAG, "Loading from " + path);
        }
        final AtomicFile file = new AtomicFile(path);

        final FileInputStream in;
        try {
            in = file.openRead();
        } catch (FileNotFoundException e) {
            if (DEBUG) {
                Slog.d(TAG, "Not found " + path);
            }
            return null;
        }
        try {
            return loadUserInternal(userId, in, /* forBackup= */ false);
        } catch (IOException|XmlPullParserException e) {
            Slog.e(TAG, "Failed to read file " + file.getBaseFile(), e);
            return null;
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    private ShortcutUser loadUserInternal(@UserIdInt int userId, InputStream is,
            boolean fromBackup) throws XmlPullParserException, IOException {

        final BufferedInputStream bis = new BufferedInputStream(is);

        ShortcutUser ret = null;
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(bis, StandardCharsets.UTF_8.name());

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
            if ((depth == 1) && ShortcutUser.TAG_ROOT.equals(tag)) {
                ret = ShortcutUser.loadFromXml(this, parser, userId, fromBackup);
                continue;
            }
            throwForInvalidTag(depth, tag);
        }
        return ret;
    }

    private void scheduleSaveBaseState() {
        scheduleSaveInner(UserHandle.USER_NULL); // Special case -- use USER_NULL for base state.
    }

    void scheduleSaveUser(@UserIdInt int userId) {
        scheduleSaveInner(userId);
    }

    // In order to re-schedule, we need to reuse the same instance, so keep it in final.
    private final Runnable mSaveDirtyInfoRunner = this::saveDirtyInfo;

    private void scheduleSaveInner(@UserIdInt int userId) {
        if (DEBUG) {
            Slog.d(TAG, "Scheduling to save for " + userId);
        }
        synchronized (mLock) {
            if (!mDirtyUserIds.contains(userId)) {
                mDirtyUserIds.add(userId);
            }
        }
        // If already scheduled, remove that and re-schedule in N seconds.
        mHandler.removeCallbacks(mSaveDirtyInfoRunner);
        mHandler.postDelayed(mSaveDirtyInfoRunner, mSaveDelayMillis);
    }

    @VisibleForTesting
    void saveDirtyInfo() {
        if (DEBUG) {
            Slog.d(TAG, "saveDirtyInfo");
        }
        synchronized (mLock) {
            for (int i = mDirtyUserIds.size() - 1; i >= 0; i--) {
                final int userId = mDirtyUserIds.get(i);
                if (userId == UserHandle.USER_NULL) { // USER_NULL for base state.
                    saveBaseStateLocked();
                } else {
                    saveUserLocked(userId);
                }
            }
            mDirtyUserIds.clear();
        }
    }

    /** Return the last reset time. */
    long getLastResetTimeLocked() {
        updateTimesLocked();
        return mRawLastResetTime;
    }

    /** Return the next reset time. */
    long getNextResetTimeLocked() {
        updateTimesLocked();
        return mRawLastResetTime + mResetInterval;
    }

    static boolean isClockValid(long time) {
        return time >= 1420070400; // Thu, 01 Jan 2015 00:00:00 GMT
    }

    /**
     * Update the last reset time.
     */
    private void updateTimesLocked() {

        final long now = injectCurrentTimeMillis();

        final long prevLastResetTime = mRawLastResetTime;

        if (mRawLastResetTime == 0) { // first launch.
            // TODO Randomize??
            mRawLastResetTime = now;
        } else if (now < mRawLastResetTime) {
            // Clock rewound.
            if (isClockValid(now)) {
                Slog.w(TAG, "Clock rewound");
                // TODO Randomize??
                mRawLastResetTime = now;
            }
        } else {
            if ((mRawLastResetTime + mResetInterval) <= now) {
                final long offset = mRawLastResetTime % mResetInterval;
                mRawLastResetTime = ((now / mResetInterval) * mResetInterval) + offset;
            }
        }
        if (prevLastResetTime != mRawLastResetTime) {
            scheduleSaveBaseState();
        }
    }

    @GuardedBy("mLock")
    @NonNull
    private boolean isUserLoadedLocked(@UserIdInt int userId) {
        return mUsers.get(userId) != null;
    }

    /** Return the per-user state. */
    @GuardedBy("mLock")
    @NonNull
    ShortcutUser getUserShortcutsLocked(@UserIdInt int userId) {
        ShortcutUser userPackages = mUsers.get(userId);
        if (userPackages == null) {
            userPackages = loadUserLocked(userId);
            if (userPackages == null) {
                userPackages = new ShortcutUser(userId);
            }
            mUsers.put(userId, userPackages);
        }
        return userPackages;
    }

    void forEachLoadedUserLocked(@NonNull Consumer<ShortcutUser> c) {
        for (int i = mUsers.size() - 1; i >= 0; i--) {
            c.accept(mUsers.valueAt(i));
        }
    }

    /** Return the per-user per-package state. */
    @GuardedBy("mLock")
    @NonNull
    ShortcutPackage getPackageShortcutsLocked(
            @NonNull String packageName, @UserIdInt int userId) {
        return getUserShortcutsLocked(userId).getPackageShortcuts(this, packageName);
    }

    @GuardedBy("mLock")
    @NonNull
    ShortcutLauncher getLauncherShortcutsLocked(
            @NonNull String packageName, @UserIdInt int ownerUserId,
            @UserIdInt int launcherUserId) {
        return getUserShortcutsLocked(ownerUserId)
                .getLauncherShortcuts(this, packageName, launcherUserId);
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

    public void cleanupBitmapsForPackage(@UserIdInt int userId, String packageName) {
        final File packagePath = new File(getUserBitmapFilePath(userId), packageName);
        if (!packagePath.isDirectory()) {
            return;
        }
        if (!(FileUtils.deleteContents(packagePath) && packagePath.delete())) {
            Slog.w(TAG, "Unable to remove directory " + packagePath);
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

        final long token = injectClearCallingIdentity();
        try {
            // Clear icon info on the shortcut.
            shortcut.setIconResourceId(0);
            shortcut.setBitmapPath(null);

            final Icon icon = shortcut.getIcon();
            if (icon == null) {
                return; // has no icon
            }

            Bitmap bitmap;
            Bitmap bitmapToRecycle = null;
            try {
                switch (icon.getType()) {
                    case Icon.TYPE_RESOURCE: {
                        injectValidateIconResPackage(shortcut, icon);

                        shortcut.setIconResourceId(icon.getResId());
                        shortcut.addFlags(ShortcutInfo.FLAG_HAS_ICON_RES);
                        return;
                    }
                    case Icon.TYPE_BITMAP: {
                        bitmap = icon.getBitmap(); // Don't recycle in this case.
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

                        Bitmap shrunk = shrinkBitmap(bitmap, mMaxIconDimension);
                        try {
                            shrunk.compress(mIconPersistFormat, mIconPersistQuality, out);
                        } finally {
                            if (bitmap != shrunk) {
                                shrunk.recycle();
                            }
                        }

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
                if (bitmapToRecycle != null) {
                    bitmapToRecycle.recycle();
                }
                // Once saved, we won't use the original icon information, so null it out.
                shortcut.clearIcon();
            }
        } finally {
            injectRestoreCallingIdentity(token);
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

    private void enforceSystem() {
        Preconditions.checkState(isCallerSystem(), "Caller must be system");
    }

    private void enforceResetThrottlingPermission() {
        if (isCallerSystem()) {
            return;
        }
        injectEnforceCallingPermission(
                android.Manifest.permission.RESET_SHORTCUT_MANAGER_THROTTLING, null);
    }

    /**
     * Somehow overriding ServiceContext.enforceCallingPermission() in the unit tests would confuse
     * mockito.  So instead we extracted it here and override it in the tests.
     */
    @VisibleForTesting
    void injectEnforceCallingPermission(
            @NonNull String permission, @Nullable String message) {
        mContext.enforceCallingPermission(permission, message);
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
        throw new SecurityException("Calling package name mismatch");
    }

    void postToHandler(Runnable r) {
        mHandler.post(r);
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
    void packageShortcutsChanged(@NonNull String packageName, @UserIdInt int userId) {
        if (DEBUG) {
            Slog.d(TAG, String.format(
                    "Shortcut changes: package=%s, user=%d", packageName, userId));
        }
        notifyListeners(packageName, userId);
        scheduleSaveUser(userId);
    }

    private void notifyListeners(@NonNull String packageName, @UserIdInt int userId) {
        if (!mUserManager.isUserRunning(userId)) {
            return;
        }
        postToHandler(() -> {
            final ArrayList<ShortcutChangeListener> copy;
            synchronized (mLock) {
                copy = new ArrayList<>(mListeners);
            }
            // Note onShortcutChanged() needs to be called with the system service permissions.
            for (int i = copy.size() - 1; i >= 0; i--) {
                copy.get(i).onShortcutChanged(packageName, userId);
            }
        });
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

        shortcut.replaceFlags(0);
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
            final ShortcutPackage ps = getPackageShortcutsLocked(packageName, userId);

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
                ps.addDynamicShortcut(this, newShortcut);
            }
        }
        packageShortcutsChanged(packageName, userId);
        return true;
    }

    @Override
    public boolean updateShortcuts(String packageName, ParceledListSlice shortcutInfoList,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        final List<ShortcutInfo> newShortcuts = (List<ShortcutInfo>) shortcutInfoList.getList();
        final int size = newShortcuts.size();

        synchronized (mLock) {
            final ShortcutPackage ps = getPackageShortcutsLocked(packageName, userId);

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
        packageShortcutsChanged(packageName, userId);

        return true;
    }

    @Override
    public boolean addDynamicShortcuts(String packageName, ParceledListSlice shortcutInfoList,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        final List<ShortcutInfo> newShortcuts = (List<ShortcutInfo>) shortcutInfoList.getList();
        final int size = newShortcuts.size();

        synchronized (mLock) {
            final ShortcutPackage ps = getPackageShortcutsLocked(packageName, userId);

            // Throttling.
            if (!ps.tryApiCall(this)) {
                return false;
            }
            for (int i = 0; i < size; i++) {
                final ShortcutInfo newShortcut = newShortcuts.get(i);

                // Validate the shortcut.
                fixUpIncomingShortcutInfo(newShortcut, /* forUpdate= */ false);

                // Add it.
                ps.addDynamicShortcut(this, newShortcut);
            }
        }
        packageShortcutsChanged(packageName, userId);

        return true;
    }

    @Override
    public void removeDynamicShortcuts(String packageName, List shortcutIds,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);
        Preconditions.checkNotNull(shortcutIds, "shortcutIds must be provided");

        synchronized (mLock) {
            for (int i = shortcutIds.size() - 1; i >= 0; i--) {
                getPackageShortcutsLocked(packageName, userId).deleteDynamicWithId(this,
                        Preconditions.checkStringNotEmpty((String) shortcutIds.get(i)));
            }
        }
        packageShortcutsChanged(packageName, userId);
    }

    @Override
    public void removeAllDynamicShortcuts(String packageName, @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        synchronized (mLock) {
            getPackageShortcutsLocked(packageName, userId).deleteAllDynamicShortcuts(this);
        }
        packageShortcutsChanged(packageName, userId);
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

        getPackageShortcutsLocked(packageName, userId).findAll(this, ret, query, cloneFlags);

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
            return mMaxUpdatesPerInterval
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
        verifyCaller(packageName, userId);

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

        resetThrottlingInner(getCallingUserId());
    }

    void resetThrottlingInner(@UserIdInt int userId) {
        synchronized (mLock) {
            getUserShortcutsLocked(userId).resetThrottling();
        }
        scheduleSaveUser(userId);
        Slog.i(TAG, "ShortcutManager: throttling counter reset for user " + userId);
    }

    void resetAllThrottlingInner() {
        synchronized (mLock) {
            mRawLastResetTime = injectCurrentTimeMillis();
        }
        scheduleSaveBaseState();
        Slog.i(TAG, "ShortcutManager: throttling counter reset for all users");
    }

    void resetPackageThrottling(String packageName, int userId) {
        synchronized (mLock) {
            getPackageShortcutsLocked(packageName, userId)
                    .resetRateLimitingForCommandLineNoSaving();
            saveUserLocked(userId);
        }
    }

    @Override
    public void onApplicationActive(String packageName, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onApplicationActive: package=" + packageName + "  userid=" + userId);
        }
        enforceResetThrottlingPermission();
        resetPackageThrottling(packageName, userId);
    }

    // We override this method in unit tests to do a simpler check.
    boolean hasShortcutHostPermission(@NonNull String callingPackage, int userId) {
        return hasShortcutHostPermissionInner(callingPackage, userId);
    }

    // This method is extracted so we can directly call this method from unit tests,
    // even when hasShortcutPermission() is overridden.
    @VisibleForTesting
    boolean hasShortcutHostPermissionInner(@NonNull String callingPackage, int userId) {
        synchronized (mLock) {
            final long start = System.currentTimeMillis();

            final ShortcutUser user = getUserShortcutsLocked(userId);

            final List<ResolveInfo> allHomeCandidates = new ArrayList<>();

            // Default launcher from package manager.
            final long startGetHomeActivitiesAsUser = System.currentTimeMillis();
            final ComponentName defaultLauncher = injectPackageManagerInternal()
                    .getHomeActivitiesAsUser(allHomeCandidates, userId);
            logDurationStat(Stats.GET_DEFAULT_HOME, startGetHomeActivitiesAsUser);

            ComponentName detected;
            if (defaultLauncher != null) {
                detected = defaultLauncher;
                if (DEBUG) {
                    Slog.v(TAG, "Default launcher from PM: " + detected);
                }
            } else {
                detected = user.getLauncherComponent();

                // TODO: Make sure it's still enabled.
                if (DEBUG) {
                    Slog.v(TAG, "Cached launcher: " + detected);
                }
            }

            if (detected == null) {
                // If we reach here, that means it's the first check since the user was created,
                // and there's already multiple launchers and there's no default set.
                // Find the system one with the highest priority.
                // (We need to check the priority too because of FallbackHome in Settings.)
                // If there's no system launcher yet, then no one can access shortcuts, until
                // the user explicitly
                final int size = allHomeCandidates.size();

                int lastPriority = Integer.MIN_VALUE;
                for (int i = 0; i < size; i++) {
                    final ResolveInfo ri = allHomeCandidates.get(i);
                    if (!ri.activityInfo.applicationInfo.isSystemApp()) {
                        continue;
                    }
                    if (DEBUG) {
                        Slog.d(TAG, String.format("hasShortcutPermissionInner: pkg=%s prio=%d",
                                ri.activityInfo.getComponentName(), ri.priority));
                    }
                    if (ri.priority < lastPriority) {
                        continue;
                    }
                    detected = ri.activityInfo.getComponentName();
                    lastPriority = ri.priority;
                }
            }
            logDurationStat(Stats.LAUNCHER_PERMISSION_CHECK, start);

            if (detected != null) {
                if (DEBUG) {
                    Slog.v(TAG, "Detected launcher: " + detected);
                }
                user.setLauncherComponent(this, detected);
                return detected.getPackageName().equals(callingPackage);
            } else {
                // Default launcher not found.
                return false;
            }
        }
    }

    // === House keeping ===

    private void cleanUpPackageForAllLoadedUsers(String packageName, @UserIdInt int packageUserId) {
        synchronized (mLock) {
            forEachLoadedUserLocked(user ->
                    cleanUpPackageLocked(packageName, user.getUserId(), packageUserId));
        }
    }

    /**
     * Remove all the information associated with a package.  This will really remove all the
     * information, including the restore information (i.e. it'll remove packages even if they're
     * shadow).
     *
     * This is called when an app is uninstalled, or an app gets "clear data"ed.
     */
    @VisibleForTesting
    void cleanUpPackageLocked(String packageName, int owningUserId, int packageUserId) {
        final boolean wasUserLoaded = isUserLoadedLocked(owningUserId);

        final ShortcutUser user = getUserShortcutsLocked(owningUserId);
        boolean doNotify = false;

        // First, remove the package from the package list (if the package is a publisher).
        if (packageUserId == owningUserId) {
            if (user.removePackage(this, packageName) != null) {
                doNotify = true;
            }
        }

        // Also remove from the launcher list (if the package is a launcher).
        user.removeLauncher(packageUserId, packageName);

        // Then remove pinned shortcuts from all launchers.
        user.forAllLaunchers(l -> l.cleanUpPackage(packageName, packageUserId));

        // Now there may be orphan shortcuts because we removed pinned shortcuts at the previous
        // step.  Remove them too.
        user.forAllPackages(p -> p.refreshPinnedFlags(this));

        scheduleSaveUser(owningUserId);

        if (doNotify) {
            notifyListeners(packageName, owningUserId);
        }

        if (!wasUserLoaded) {
            // Note this will execute the scheduled save.
            unloadUserLocked(owningUserId);
        }
    }

    /**
     * Entry point from {@link LauncherApps}.
     */
    private class LocalService extends ShortcutServiceInternal {

        @Override
        public List<ShortcutInfo> getShortcuts(int launcherUserId,
                @NonNull String callingPackage, long changedSince,
                @Nullable String packageName, @Nullable List<String> shortcutIds,
                @Nullable ComponentName componentName,
                int queryFlags, int userId) {
            final ArrayList<ShortcutInfo> ret = new ArrayList<>();
            final int cloneFlag =
                    ((queryFlags & ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY) == 0)
                            ? ShortcutInfo.CLONE_REMOVE_FOR_LAUNCHER
                            : ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO;
            if (packageName == null) {
                shortcutIds = null; // LauncherAppsService already threw for it though.
            }

            synchronized (mLock) {
                getLauncherShortcutsLocked(callingPackage, userId, launcherUserId)
                        .attemptToRestoreIfNeededAndSave(ShortcutService.this);

                if (packageName != null) {
                    getShortcutsInnerLocked(launcherUserId,
                            callingPackage, packageName, shortcutIds, changedSince,
                            componentName, queryFlags, userId, ret, cloneFlag);
                } else {
                    final List<String> shortcutIdsF = shortcutIds;
                    getUserShortcutsLocked(userId).forAllPackages(p -> {
                        getShortcutsInnerLocked(launcherUserId,
                                callingPackage, p.getPackageName(), shortcutIdsF, changedSince,
                                componentName, queryFlags, userId, ret, cloneFlag);
                    });
                }
            }
            return ret;
        }

        private void getShortcutsInnerLocked(int launcherUserId, @NonNull String callingPackage,
                @Nullable String packageName, @Nullable List<String> shortcutIds, long changedSince,
                @Nullable ComponentName componentName, int queryFlags,
                int userId, ArrayList<ShortcutInfo> ret, int cloneFlag) {
            final ArraySet<String> ids = shortcutIds == null ? null
                    : new ArraySet<>(shortcutIds);

            getPackageShortcutsLocked(packageName, userId).findAll(ShortcutService.this, ret,
                    (ShortcutInfo si) -> {
                        if (si.getLastChangedTimestamp() < changedSince) {
                            return false;
                        }
                        if (ids != null && !ids.contains(si.getId())) {
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
                    }, cloneFlag, callingPackage, launcherUserId);
        }

        @Override
        public boolean isPinnedByCaller(int launcherUserId, @NonNull String callingPackage,
                @NonNull String packageName, @NonNull String shortcutId, int userId) {
            Preconditions.checkStringNotEmpty(packageName, "packageName");
            Preconditions.checkStringNotEmpty(shortcutId, "shortcutId");

            synchronized (mLock) {
                getLauncherShortcutsLocked(callingPackage, userId, launcherUserId)
                        .attemptToRestoreIfNeededAndSave(ShortcutService.this);

                final ShortcutInfo si = getShortcutInfoLocked(
                        launcherUserId, callingPackage, packageName, shortcutId, userId);
                return si != null && si.isPinned();
            }
        }

        private ShortcutInfo getShortcutInfoLocked(
                int launcherUserId, @NonNull String callingPackage,
                @NonNull String packageName, @NonNull String shortcutId, int userId) {
            Preconditions.checkStringNotEmpty(packageName, "packageName");
            Preconditions.checkStringNotEmpty(shortcutId, "shortcutId");

            final ArrayList<ShortcutInfo> list = new ArrayList<>(1);
            getPackageShortcutsLocked(packageName, userId).findAll(
                    ShortcutService.this, list,
                    (ShortcutInfo si) -> shortcutId.equals(si.getId()),
                    /* clone flags=*/ 0, callingPackage, launcherUserId);
            return list.size() == 0 ? null : list.get(0);
        }

        @Override
        public void pinShortcuts(int launcherUserId,
                @NonNull String callingPackage, @NonNull String packageName,
                @NonNull List<String> shortcutIds, int userId) {
            // Calling permission must be checked by LauncherAppsImpl.
            Preconditions.checkStringNotEmpty(packageName, "packageName");
            Preconditions.checkNotNull(shortcutIds, "shortcutIds");

            synchronized (mLock) {
                final ShortcutLauncher launcher =
                        getLauncherShortcutsLocked(callingPackage, userId, launcherUserId);
                launcher.attemptToRestoreIfNeededAndSave(ShortcutService.this);

                launcher.pinShortcuts(
                        ShortcutService.this, userId, packageName, shortcutIds);
            }
            packageShortcutsChanged(packageName, userId);
        }

        @Override
        public Intent createShortcutIntent(int launcherUserId,
                @NonNull String callingPackage,
                @NonNull String packageName, @NonNull String shortcutId, int userId) {
            // Calling permission must be checked by LauncherAppsImpl.
            Preconditions.checkStringNotEmpty(packageName, "packageName can't be empty");
            Preconditions.checkStringNotEmpty(shortcutId, "shortcutId can't be empty");

            synchronized (mLock) {
                getLauncherShortcutsLocked(callingPackage, userId, launcherUserId)
                        .attemptToRestoreIfNeededAndSave(ShortcutService.this);

                // Make sure the shortcut is actually visible to the launcher.
                final ShortcutInfo si = getShortcutInfoLocked(
                        launcherUserId, callingPackage, packageName, shortcutId, userId);
                // "si == null" should suffice here, but check the flags too just to make sure.
                if (si == null || !(si.isDynamic() || si.isPinned())) {
                    return null;
                }
                return si.getIntent();
            }
        }

        @Override
        public void addListener(@NonNull ShortcutChangeListener listener) {
            synchronized (mLock) {
                mListeners.add(Preconditions.checkNotNull(listener));
            }
        }

        @Override
        public int getShortcutIconResId(int launcherUserId, @NonNull String callingPackage,
                @NonNull String packageName, @NonNull String shortcutId, int userId) {
            Preconditions.checkNotNull(callingPackage, "callingPackage");
            Preconditions.checkNotNull(packageName, "packageName");
            Preconditions.checkNotNull(shortcutId, "shortcutId");

            synchronized (mLock) {
                getLauncherShortcutsLocked(callingPackage, userId, launcherUserId)
                        .attemptToRestoreIfNeededAndSave(ShortcutService.this);

                final ShortcutInfo shortcutInfo = getPackageShortcutsLocked(
                        packageName, userId).findShortcutById(shortcutId);
                return (shortcutInfo != null && shortcutInfo.hasIconResource())
                        ? shortcutInfo.getIconResourceId() : 0;
            }
        }

        @Override
        public ParcelFileDescriptor getShortcutIconFd(int launcherUserId,
                @NonNull String callingPackage, @NonNull String packageName,
                @NonNull String shortcutId, int userId) {
            Preconditions.checkNotNull(callingPackage, "callingPackage");
            Preconditions.checkNotNull(packageName, "packageName");
            Preconditions.checkNotNull(shortcutId, "shortcutId");

            synchronized (mLock) {
                getLauncherShortcutsLocked(callingPackage, userId, launcherUserId)
                        .attemptToRestoreIfNeededAndSave(ShortcutService.this);

                final ShortcutInfo shortcutInfo = getPackageShortcutsLocked(
                        packageName, userId).findShortcutById(shortcutId);
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

        @Override
        public boolean hasShortcutHostPermission(int launcherUserId,
                @NonNull String callingPackage) {
            return ShortcutService.this.hasShortcutHostPermission(callingPackage, launcherUserId);
        }

        /**
         * Called by AM when the system locale changes *within the AM lock.  ABSOLUTELY do not take
         * any locks in this method.
         */
        @Override
        public void onSystemLocaleChangedNoLock() {
            if (!FEATURE_ENABLED) {
                return;
            }
            // DO NOT HOLD ANY LOCKS HERE.

            // We want to reset throttling for all packages for all users.  But we can't just do so
            // here because:
            // - We can't load/save users that are locked.
            // - Even for loaded users, resetting the counters would require us to hold mLock.
            //
            // So we use a "pull" model instead.  In here, we just increment the "locale change
            // sequence number".  Each ShortcutUser has the "last known locale change sequence".
            //
            // This allows ShortcutUser's to detect the system locale change, so they can reset
            // counters.
            mLocaleChangeSequenceNumber.incrementAndGet();
            postToHandler(() -> scheduleSaveBaseState());
        }
    }

    /**
     * Package event callbacks.
     */
    @VisibleForTesting
    final PackageMonitor mPackageMonitor = new PackageMonitor() {
        @Override
        public void onPackageAdded(String packageName, int uid) {
            handlePackageAdded(packageName, getChangingUserId());
        }

        @Override
        public void onPackageUpdateFinished(String packageName, int uid) {
            handlePackageUpdateFinished(packageName, getChangingUserId());
        }

        @Override
        public void onPackageRemoved(String packageName, int uid) {
            handlePackageRemoved(packageName, getChangingUserId());
        }

        @Override
        public void onPackageDataCleared(String packageName, int uid) {
            handlePackageDataCleared(packageName, getChangingUserId());
        }
    };

    /**
     * Called when a user is unlocked.
     * - Check all known packages still exist, and otherwise perform cleanup.
     * - If a package still exists, check the version code.  If it's been updated, may need to
     *   update timestamps of its shortcuts.
     */
    @VisibleForTesting
    void checkPackageChanges(@UserIdInt int ownerUserId) {
        if (DEBUG) {
            Slog.d(TAG, "checkPackageChanges() ownerUserId=" + ownerUserId);
        }
        final ArrayList<PackageWithUser> gonePackages = new ArrayList<>();

        synchronized (mLock) {
            final ShortcutUser user = getUserShortcutsLocked(ownerUserId);

            user.forAllPackageItems(spi -> {
                if (spi.getPackageInfo().isShadow()) {
                    return; // Don't delete shadow information.
                }
                final int versionCode = getApplicationVersionCode(
                        spi.getPackageName(), spi.getPackageUserId());
                if (versionCode >= 0) {
                    // Package still installed, see if it's updated.
                    getUserShortcutsLocked(ownerUserId).handlePackageUpdated(
                            this, spi.getPackageName(), versionCode);
                } else {
                    gonePackages.add(PackageWithUser.of(spi));
                }
            });
            if (gonePackages.size() > 0) {
                for (int i = gonePackages.size() - 1; i >= 0; i--) {
                    final PackageWithUser pu = gonePackages.get(i);
                    cleanUpPackageLocked(pu.packageName, ownerUserId, pu.userId);
                }
            }
        }
    }

    private void handlePackageAdded(String packageName, @UserIdInt int userId) {
        if (DEBUG) {
            Slog.d(TAG, String.format("handlePackageAdded: %s user=%d", packageName, userId));
        }
        synchronized (mLock) {
            forEachLoadedUserLocked(user ->
                    user.attemptToRestoreIfNeededAndSave(this, packageName, userId));
        }
    }

    private void handlePackageUpdateFinished(String packageName, @UserIdInt int userId) {
        if (DEBUG) {
            Slog.d(TAG, String.format("handlePackageUpdateFinished: %s user=%d",
                    packageName, userId));
        }
        synchronized (mLock) {
            forEachLoadedUserLocked(user ->
                    user.attemptToRestoreIfNeededAndSave(this, packageName, userId));

            final int versionCode = getApplicationVersionCode(packageName, userId);
            if (versionCode < 0) {
                return; // shouldn't happen
            }
            getUserShortcutsLocked(userId).handlePackageUpdated(this, packageName, versionCode);
        }
    }

    private void handlePackageRemoved(String packageName, @UserIdInt int packageUserId) {
        if (DEBUG) {
            Slog.d(TAG, String.format("handlePackageRemoved: %s user=%d", packageName,
                    packageUserId));
        }
        cleanUpPackageForAllLoadedUsers(packageName, packageUserId);
    }

    private void handlePackageDataCleared(String packageName, int packageUserId) {
        if (DEBUG) {
            Slog.d(TAG, String.format("handlePackageDataCleared: %s user=%d", packageName,
                    packageUserId));
        }
        cleanUpPackageForAllLoadedUsers(packageName, packageUserId);
    }

    // === PackageManager interaction ===

    PackageInfo getPackageInfoWithSignatures(String packageName, @UserIdInt int userId) {
        return injectPackageInfo(packageName, userId, true);
    }

    int injectGetPackageUid(@NonNull String packageName, @UserIdInt int userId) {
        final long token = injectClearCallingIdentity();
        try {
            return mIPackageManager.getPackageUid(packageName, PACKAGE_MATCH_FLAGS
                    , userId);
        } catch (RemoteException e) {
            // Shouldn't happen.
            Slog.wtf(TAG, "RemoteException", e);
            return -1;
        } finally {
            injectRestoreCallingIdentity(token);
        }
    }

    @VisibleForTesting
    PackageInfo injectPackageInfo(String packageName, @UserIdInt int userId,
            boolean getSignatures) {
        final long start = System.currentTimeMillis();
        final long token = injectClearCallingIdentity();
        try {
            return mIPackageManager.getPackageInfo(packageName, PACKAGE_MATCH_FLAGS
                    | (getSignatures ? PackageManager.GET_SIGNATURES : 0)
                    , userId);
        } catch (RemoteException e) {
            // Shouldn't happen.
            Slog.wtf(TAG, "RemoteException", e);
            return null;
        } finally {
            injectRestoreCallingIdentity(token);

            logDurationStat(
                    (getSignatures ? Stats.GET_PACKAGE_INFO_WITH_SIG : Stats.GET_PACKAGE_INFO),
                    start);
        }
    }

    @VisibleForTesting
    ApplicationInfo injectApplicationInfo(String packageName, @UserIdInt int userId) {
        final long start = System.currentTimeMillis();
        final long token = injectClearCallingIdentity();
        try {
            return mIPackageManager.getApplicationInfo(packageName, PACKAGE_MATCH_FLAGS, userId);
        } catch (RemoteException e) {
            // Shouldn't happen.
            Slog.wtf(TAG, "RemoteException", e);
            return null;
        } finally {
            injectRestoreCallingIdentity(token);

            logDurationStat(Stats.GET_APPLICATION_INFO, start);
        }
    }

    private boolean isApplicationFlagSet(String packageName, int userId, int flags) {
        final ApplicationInfo ai = injectApplicationInfo(packageName, userId);
        return (ai != null) && ((ai.flags & flags) == flags);
    }

    boolean isPackageInstalled(String packageName, int userId) {
        return isApplicationFlagSet(packageName, userId, ApplicationInfo.FLAG_INSTALLED);
    }

    /**
     * @return the version code of the package, or -1 if the app is not installed.
     */
    int getApplicationVersionCode(String packageName, int userId) {
        final ApplicationInfo ai = injectApplicationInfo(packageName, userId);
        if ((ai == null) || ((ai.flags & ApplicationInfo.FLAG_INSTALLED) == 0)) {
            return -1;
        }
        return ai.versionCode;
    }

    // === Backup & restore ===

    boolean shouldBackupApp(String packageName, int userId) {
        return isApplicationFlagSet(packageName, userId, ApplicationInfo.FLAG_ALLOW_BACKUP);
    }

    boolean shouldBackupApp(PackageInfo pi) {
        return (pi.applicationInfo.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0;
    }

    @Override
    public byte[] getBackupPayload(@UserIdInt int userId) {
        enforceSystem();
        if (DEBUG) {
            Slog.d(TAG, "Backing up user " + userId);
        }
        synchronized (mLock) {
            final ShortcutUser user = getUserShortcutsLocked(userId);
            if (user == null) {
                Slog.w(TAG, "Can't backup: user not found: id=" + userId);
                return null;
            }

            user.forAllPackageItems(spi -> spi.refreshPackageInfoAndSave(this));

            // Then save.
            final ByteArrayOutputStream os = new ByteArrayOutputStream(32 * 1024);
            try {
                saveUserInternalLocked(userId, os, /* forBackup */ true);
            } catch (XmlPullParserException|IOException e) {
                // Shouldn't happen.
                Slog.w(TAG, "Backup failed.", e);
                return null;
            }
            return os.toByteArray();
        }
    }

    @Override
    public void applyRestore(byte[] payload, @UserIdInt int userId) {
        enforceSystem();
        if (DEBUG) {
            Slog.d(TAG, "Restoring user " + userId);
        }
        final ShortcutUser user;
        final ByteArrayInputStream is = new ByteArrayInputStream(payload);
        try {
            user = loadUserInternal(userId, is, /* fromBackup */ true);
        } catch (XmlPullParserException|IOException e) {
            Slog.w(TAG, "Restoration failed.", e);
            return;
        }
        synchronized (mLock) {
            mUsers.put(userId, user);

            // Then purge all the save images.
            final File bitmapPath = getUserBitmapFilePath(userId);
            final boolean success = FileUtils.deleteContents(bitmapPath);
            if (!success) {
                Slog.w(TAG, "Failed to delete " + bitmapPath);
            }

            saveUserLocked(userId);
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
        dumpInner(pw, args);
    }

    @VisibleForTesting
    void dumpInner(PrintWriter pw, String[] args) {
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

            pw.print("  Locale change seq#: ");
            pw.print(mLocaleChangeSequenceNumber.get());
            pw.println();

            pw.print("  Config:");
            pw.print("    Max icon dim: ");
            pw.println(mMaxIconDimension);
            pw.print("    Icon format: ");
            pw.println(mIconPersistFormat);
            pw.print("    Icon quality: ");
            pw.println(mIconPersistQuality);
            pw.print("    saveDelayMillis: ");
            pw.println(mSaveDelayMillis);
            pw.print("    resetInterval: ");
            pw.println(mResetInterval);
            pw.print("    maxUpdatesPerInterval: ");
            pw.println(mMaxUpdatesPerInterval);
            pw.print("    maxDynamicShortcuts: ");
            pw.println(mMaxDynamicShortcuts);
            pw.println();

            pw.println("  Stats:");
            synchronized (mStatLock) {
                final String p = "    ";
                dumpStatLS(pw, p, Stats.GET_DEFAULT_HOME, "getHomeActivities()");
                dumpStatLS(pw, p, Stats.LAUNCHER_PERMISSION_CHECK, "Launcher permission check");

                dumpStatLS(pw, p, Stats.GET_PACKAGE_INFO, "getPackageInfo()");
                dumpStatLS(pw, p, Stats.GET_PACKAGE_INFO_WITH_SIG, "getPackageInfo(SIG)");
                dumpStatLS(pw, p, Stats.GET_APPLICATION_INFO, "getApplicationInfo");
            }

            for (int i = 0; i < mUsers.size(); i++) {
                pw.println();
                mUsers.valueAt(i).dump(this, pw, "  ");
            }

            pw.println();
            pw.println("  UID state:");

            for (int i = 0; i < mUidState.size(); i++) {
                final int uid = mUidState.keyAt(i);
                final int state = mUidState.valueAt(i);
                pw.print("    UID=");
                pw.print(uid);
                pw.print(" state=");
                pw.print(state);
                if (isProcessStateForeground(state)) {
                    pw.print("  [FG]");
                }
                pw.print("  last FG=");
                pw.print(mUidLastForegroundElapsedTime.get(uid));
                pw.println();
            }
        }
    }

    static String formatTime(long time) {
        Time tobj = new Time();
        tobj.set(time);
        return tobj.format("%Y-%m-%d %H:%M:%S");
    }

    private void dumpStatLS(PrintWriter pw, String prefix, int statId, String label) {
        pw.print(prefix);
        final int count = mCountStats[statId];
        final long dur = mDurationStats[statId];
        pw.println(String.format("%s: count=%d, total=%dms, avg=%.1fms",
                label, count, dur,
                (count == 0 ? 0 : ((double) dur) / count)));
    }

    // === Shell support ===

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ResultReceiver resultReceiver) throws RemoteException {

        enforceShell();

        (new MyShellCommand()).exec(this, in, out, err, args, resultReceiver);
    }

    static class CommandException extends Exception {
        public CommandException(String message) {
            super(message);
        }
    }

    /**
     * Handle "adb shell cmd".
     */
    private class MyShellCommand extends ShellCommand {

        private int mUserId = UserHandle.USER_SYSTEM;

        private void parseOptions(boolean takeUser)
                throws CommandException {
            String opt;
            while ((opt = getNextOption()) != null) {
                switch (opt) {
                    case "--user":
                        if (takeUser) {
                            mUserId = UserHandle.parseUserArg(getNextArgRequired());
                            break;
                        }
                        // fallthrough
                    default:
                        throw new CommandException("Unknown option: " + opt);
                }
            }
        }

        @Override
        public int onCommand(String cmd) {
            if (cmd == null) {
                return handleDefaultCommands(cmd);
            }
            final PrintWriter pw = getOutPrintWriter();
            try {
                switch (cmd) {
                    case "reset-package-throttling":
                        handleResetPackageThrottling();
                        break;
                    case "reset-throttling":
                        handleResetThrottling();
                        break;
                    case "reset-all-throttling":
                        handleResetAllThrottling();
                        break;
                    case "override-config":
                        handleOverrideConfig();
                        break;
                    case "reset-config":
                        handleResetConfig();
                        break;
                    case "clear-default-launcher":
                        handleClearDefaultLauncher();
                        break;
                    case "get-default-launcher":
                        handleGetDefaultLauncher();
                        break;
                    case "refresh-default-launcher":
                        handleRefreshDefaultLauncher();
                        break;
                    case "unload-user":
                        handleUnloadUser();
                        break;
                    case "clear-shortcuts":
                        handleClearShortcuts();
                        break;
                    default:
                        return handleDefaultCommands(cmd);
                }
            } catch (CommandException e) {
                pw.println("Error: " + e.getMessage());
                return 1;
            }
            pw.println("Success");
            return 0;
        }

        @Override
        public void onHelp() {
            final PrintWriter pw = getOutPrintWriter();
            pw.println("Usage: cmd shortcut COMMAND [options ...]");
            pw.println();
            pw.println("cmd shortcut reset-package-throttling [--user USER_ID] PACKAGE");
            pw.println("    Reset throttling for a package");
            pw.println();
            pw.println("cmd shortcut reset-throttling [--user USER_ID]");
            pw.println("    Reset throttling for all packages and users");
            pw.println();
            pw.println("cmd shortcut reset-all-throttling");
            pw.println("    Reset the throttling state for all users");
            pw.println();
            pw.println("cmd shortcut override-config CONFIG");
            pw.println("    Override the configuration for testing (will last until reboot)");
            pw.println();
            pw.println("cmd shortcut reset-config");
            pw.println("    Reset the configuration set with \"update-config\"");
            pw.println();
            pw.println("cmd shortcut clear-default-launcher [--user USER_ID]");
            pw.println("    Clear the cached default launcher");
            pw.println();
            pw.println("cmd shortcut get-default-launcher [--user USER_ID]");
            pw.println("    Show the cached default launcher");
            pw.println();
            pw.println("cmd shortcut refresh-default-launcher [--user USER_ID]");
            pw.println("    Refresh the cached default launcher");
            pw.println();
            pw.println("cmd shortcut unload-user [--user USER_ID]");
            pw.println("    Unload a user from the memory");
            pw.println("    (This should not affect any observable behavior)");
            pw.println();
            pw.println("cmd shortcut clear-shortcuts [--user USER_ID] PACKAGE");
            pw.println("    Remove all shortcuts from a package, including pinned shortcuts");
            pw.println();
        }

        private void handleResetThrottling() throws CommandException {
            parseOptions(/* takeUser =*/ true);

            Slog.i(TAG, "cmd: handleResetThrottling");

            resetThrottlingInner(mUserId);
        }

        private void handleResetAllThrottling() {
            Slog.i(TAG, "cmd: handleResetAllThrottling");

            resetAllThrottlingInner();
        }

        private void handleResetPackageThrottling() throws CommandException {
            parseOptions(/* takeUser =*/ true);

            final String packageName = getNextArgRequired();

            Slog.i(TAG, "cmd: handleResetPackageThrottling: " + packageName);

            resetPackageThrottling(packageName, mUserId);
        }

        private void handleOverrideConfig() throws CommandException {
            final String config = getNextArgRequired();

            Slog.i(TAG, "cmd: handleOverrideConfig: " + config);

            synchronized (mLock) {
                if (!updateConfigurationLocked(config)) {
                    throw new CommandException("override-config failed.  See logcat for details.");
                }
            }
        }

        private void handleResetConfig() {
            Slog.i(TAG, "cmd: handleResetConfig");

            synchronized (mLock) {
                loadConfigurationLocked();
            }
        }

        private void clearLauncher() {
            synchronized (mLock) {
                getUserShortcutsLocked(mUserId).setLauncherComponent(
                        ShortcutService.this, null);
            }
        }

        private void showLauncher() {
            synchronized (mLock) {
                // This ensures to set the cached launcher.  Package name doesn't matter.
                hasShortcutHostPermissionInner("-", mUserId);

                getOutPrintWriter().println("Launcher: "
                        + getUserShortcutsLocked(mUserId).getLauncherComponent());
            }
        }

        private void handleClearDefaultLauncher() throws CommandException {
            parseOptions(/* takeUser =*/ true);

            clearLauncher();
        }

        private void handleGetDefaultLauncher() throws CommandException {
            parseOptions(/* takeUser =*/ true);

            showLauncher();
        }

        private void handleRefreshDefaultLauncher() throws CommandException {
            parseOptions(/* takeUser =*/ true);

            clearLauncher();
            showLauncher();
        }

        private void handleUnloadUser() throws CommandException {
            parseOptions(/* takeUser =*/ true);

            Slog.i(TAG, "cmd: handleUnloadUser: " + mUserId);

            ShortcutService.this.handleCleanupUser(mUserId);
        }

        private void handleClearShortcuts() throws CommandException {
            parseOptions(/* takeUser =*/ true);
            final String packageName = getNextArgRequired();

            Slog.i(TAG, "cmd: handleClearShortcuts: " + mUserId + ", " + packageName);

            ShortcutService.this.cleanUpPackageForAllLoadedUsers(packageName, mUserId);
        }
    }

    // === Unit test support ===

    // Injection point.
    @VisibleForTesting
    long injectCurrentTimeMillis() {
        return System.currentTimeMillis();
    }

    @VisibleForTesting
    long injectElapsedRealtime() {
        return SystemClock.elapsedRealtime();
    }

    // Injection point.
    @VisibleForTesting
    int injectBinderCallingUid() {
        return getCallingUid();
    }

    private int getCallingUserId() {
        return UserHandle.getUserId(injectBinderCallingUid());
    }

    // Injection point.
    @VisibleForTesting
    long injectClearCallingIdentity() {
        return Binder.clearCallingIdentity();
    }

    // Injection point.
    @VisibleForTesting
    void injectRestoreCallingIdentity(long token) {
        Binder.restoreCallingIdentity(token);
    }

    final void wtf(String message) {
        wtf( message, /* exception= */ null);
    }

    // Injection point.
    void wtf(String message, Exception e) {
        Slog.wtf(TAG, message, e);
    }

    @VisibleForTesting
    File injectSystemDataPath() {
        return Environment.getDataSystemDirectory();
    }

    @VisibleForTesting
    File injectUserDataPath(@UserIdInt int userId) {
        return new File(Environment.getDataSystemCeDirectory(userId), DIRECTORY_PER_USER);
    }

    @VisibleForTesting
    boolean injectIsLowRamDevice() {
        return ActivityManager.isLowRamDeviceStatic();
    }

    @VisibleForTesting
    void injectRegisterUidObserver(IUidObserver observer, int which) {
        try {
            ActivityManagerNative.getDefault().registerUidObserver(observer, which);
        } catch (RemoteException shouldntHappen) {
        }
    }

    @VisibleForTesting
    PackageManagerInternal injectPackageManagerInternal() {
        return mPackageManagerInternal;
    }

    File getUserBitmapFilePath(@UserIdInt int userId) {
        return new File(injectUserDataPath(userId), DIRECTORY_BITMAPS);
    }

    @VisibleForTesting
    SparseArray<ShortcutUser> getShortcutsForTest() {
        return mUsers;
    }

    @VisibleForTesting
    int getMaxDynamicShortcutsForTest() {
        return mMaxDynamicShortcuts;
    }

    @VisibleForTesting
    int getMaxUpdatesPerIntervalForTest() {
        return mMaxUpdatesPerInterval;
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
            final ShortcutUser user = mUsers.get(userId);
            if (user == null) return null;

            final ShortcutPackage pkg = user.getAllPackagesForTest().get(packageName);
            if (pkg == null) return null;

            return pkg.findShortcutById(shortcutId);
        }
    }
}
