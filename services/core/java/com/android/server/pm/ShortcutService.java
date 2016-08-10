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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityManagerNative;
import android.app.AppGlobals;
import android.app.IUidObserver;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.IShortcutService;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.ShortcutQuery;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.ShortcutServiceInternal.ShortcutChangeListener;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.text.format.Time;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.KeyValueListParser;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.TypedValue;
import android.util.Xml;
import android.view.IWindowManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.ShortcutUser.PackageWithUser;

import libcore.io.IoUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
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
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * TODO:
 * - getIconMaxWidth()/getIconMaxHeight() should use xdpi and ydpi.
 *   -> But TypedValue.applyDimension() doesn't differentiate x and y..?
 *
 * - Detect when already registered instances are passed to APIs again, which might break
 * internal bitmap handling.
 */
public class ShortcutService extends IShortcutService.Stub {
    static final String TAG = "ShortcutService";

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

    private static final String ATTR_VALUE = "value";

    private static final String LAUNCHER_INTENT_CATEGORY = Intent.CATEGORY_LAUNCHER;

    private static final String KEY_SHORTCUT = "shortcut";
    private static final String KEY_LOW_RAM = "lowRam";
    private static final String KEY_ICON_SIZE = "iconSize";

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
         * Key name for the max dynamic shortcuts per activity. (int)
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

    private static List<ResolveInfo> EMPTY_RESOLVE_INFO = new ArrayList<>(0);

    private static Predicate<ResolveInfo> ACTIVITY_NOT_EXPORTED =
            ri -> !ri.activityInfo.exported;

    private static Predicate<PackageInfo> PACKAGE_NOT_INSTALLED = pi -> !isInstalled(pi);

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
     * Max number of dynamic + manifest shortcuts that each application can have at a time.
     */
    private int mMaxShortcuts;

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
    private final UsageStatsManagerInternal mUsageStatsManagerInternal;
    private final ActivityManagerInternal mActivityManagerInternal;

    @GuardedBy("mLock")
    final SparseIntArray mUidState = new SparseIntArray();

    @GuardedBy("mLock")
    final SparseLongArray mUidLastForegroundElapsedTime = new SparseLongArray();

    @GuardedBy("mLock")
    private List<Integer> mDirtyUserIds = new ArrayList<>();

    private final AtomicBoolean mBootCompleted = new AtomicBoolean();

    private static final int PACKAGE_MATCH_FLAGS =
            PackageManager.MATCH_DIRECT_BOOT_AWARE
                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                    | PackageManager.MATCH_UNINSTALLED_PACKAGES;

    @GuardedBy("mLock")
    final SparseBooleanArray mUnlockedUsers = new SparseBooleanArray();

    // Stats
    @VisibleForTesting
    interface Stats {
        int GET_DEFAULT_HOME = 0;
        int GET_PACKAGE_INFO = 1;
        int GET_PACKAGE_INFO_WITH_SIG = 2;
        int GET_APPLICATION_INFO = 3;
        int LAUNCHER_PERMISSION_CHECK = 4;
        int CLEANUP_DANGLING_BITMAPS = 5;
        int GET_ACTIVITY_WITH_METADATA = 6;
        int GET_INSTALLED_PACKAGES = 7;
        int CHECK_PACKAGE_CHANGES = 8;
        int GET_APPLICATION_RESOURCES = 9;
        int RESOURCE_NAME_LOOKUP = 10;
        int GET_LAUNCHER_ACTIVITY = 11;
        int CHECK_LAUNCHER_ACTIVITY = 12;
        int IS_ACTIVITY_ENABLED = 13;
        int PACKAGE_UPDATE_CHECK = 14;

        int COUNT = PACKAGE_UPDATE_CHECK + 1;
    }

    final Object mStatLock = new Object();

    @GuardedBy("mStatLock")
    private final int[] mCountStats = new int[Stats.COUNT];

    @GuardedBy("mStatLock")
    private final long[] mDurationStats = new long[Stats.COUNT];

    private static final int PROCESS_STATE_FOREGROUND_THRESHOLD =
            ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;

    static final int OPERATION_SET = 0;
    static final int OPERATION_ADD = 1;
    static final int OPERATION_UPDATE = 2;

    /** @hide */
    @IntDef(value = {
            OPERATION_SET,
            OPERATION_ADD,
            OPERATION_UPDATE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ShortcutOperation {
    }

    @GuardedBy("mLock")
    private int mWtfCount = 0;

    @GuardedBy("mLock")
    private Exception mLastWtfStacktrace;

    public ShortcutService(Context context) {
        this(context, BackgroundThread.get().getLooper(), /*onyForPackgeManagerApis*/ false);
    }

    @VisibleForTesting
    ShortcutService(Context context, Looper looper, boolean onlyForPackageManagerApis) {
        mContext = Preconditions.checkNotNull(context);
        LocalServices.addService(ShortcutServiceInternal.class, new LocalService());
        mHandler = new Handler(looper);
        mIPackageManager = AppGlobals.getPackageManager();
        mPackageManagerInternal = Preconditions.checkNotNull(
                LocalServices.getService(PackageManagerInternal.class));
        mUserManager = Preconditions.checkNotNull(context.getSystemService(UserManager.class));
        mUsageStatsManagerInternal = Preconditions.checkNotNull(
                LocalServices.getService(UsageStatsManagerInternal.class));
        mActivityManagerInternal = Preconditions.checkNotNull(
                LocalServices.getService(ActivityManagerInternal.class));

        if (onlyForPackageManagerApis) {
            return; // Don't do anything further.  For unit tests only.
        }

        // Register receivers.

        // We need to set a priority, so let's just not use PackageMonitor for now.
        // TODO Refactor PackageMonitor to support priorities.
        final IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_DATA_CLEARED);
        packageFilter.addDataScheme("package");
        packageFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiverAsUser(mPackageMonitor, UserHandle.ALL,
                packageFilter, null, mHandler);

        final IntentFilter preferedActivityFilter = new IntentFilter();
        preferedActivityFilter.addAction(Intent.ACTION_PREFERRED_ACTIVITY_CHANGED);
        preferedActivityFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiverAsUser(mPackageMonitor, UserHandle.ALL,
                preferedActivityFilter, null, mHandler);

        final IntentFilter localeFilter = new IntentFilter();
        localeFilter.addAction(Intent.ACTION_LOCALE_CHANGED);
        localeFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiverAsUser(mReceiver, UserHandle.ALL,
                localeFilter, null, mHandler);

        injectRegisterUidObserver(mUidObserver, ActivityManager.UID_OBSERVER_PROCSTATE
                | ActivityManager.UID_OBSERVER_GONE);
    }

    void logDurationStat(int statId, long start) {
        synchronized (mStatLock) {
            mCountStats[statId]++;
            mDurationStats[statId] += (injectElapsedRealtime() - start);
        }
    }

    public String injectGetLocaleTagsForUser(@UserIdInt int userId) {
        // TODO This should get the per-user locale.  b/30123329 b/30119489
        return LocaleList.getDefault().toLanguageTags();
    }

    final private IUidObserver mUidObserver = new IUidObserver.Stub() {
        @Override
        public void onUidStateChanged(int uid, int procState) throws RemoteException {
            handleOnUidStateChanged(uid, procState);
        }

        @Override
        public void onUidGone(int uid) throws RemoteException {
            handleOnUidStateChanged(uid, ActivityManager.MAX_PROCESS_STATE);
        }

        @Override
        public void onUidActive(int uid) throws RemoteException {
        }

        @Override
        public void onUidIdle(int uid) throws RemoteException {
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
        return (processState != ActivityManager.PROCESS_STATE_NONEXISTENT)
                && (processState <= PROCESS_STATE_FOREGROUND_THRESHOLD);
    }

    boolean isUidForegroundLocked(int uid) {
        if (uid == Process.SYSTEM_UID) {
            // IUidObserver doesn't report the state of SYSTEM, but it always has bound services,
            // so it's foreground anyway.
            return true;
        }
        // First, check with the local cache.
        if (isProcessStateForeground(mUidState.get(uid, ActivityManager.MAX_PROCESS_STATE))) {
            return true;
        }
        // If the cache says background, reach out to AM.  Since it'll internally need to hold
        // the AM lock, we use it as a last resort.
        return isProcessStateForeground(mActivityManagerInternal.getUidProcessState(uid));
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
        if (DEBUG) {
            Slog.d(TAG, "onBootPhase: " + phase);
        }
        switch (phase) {
            case SystemService.PHASE_LOCK_SETTINGS_READY:
                initialize();
                break;
            case SystemService.PHASE_BOOT_COMPLETED:
                mBootCompleted.set(true);
                break;
        }
    }

    /** lifecycle event */
    void handleUnlockUser(int userId) {
        if (DEBUG) {
            Slog.d(TAG, "handleUnlockUser: user=" + userId);
        }
        synchronized (mLock) {
            mUnlockedUsers.put(userId, true);

            // Preload the user's shortcuts.
            // Also see if the locale has changed.
            // Note as of nyc, the locale is per-user, so the locale shouldn't change
            // when the user is locked.  However due to b/30119489 it still happens.
            getUserShortcutsLocked(userId).detectLocaleChange();

            checkPackageChanges(userId);
        }
    }

    /** lifecycle event */
    void handleCleanupUser(int userId) {
        if (DEBUG) {
            Slog.d(TAG, "handleCleanupUser: user=" + userId);
        }
        synchronized (mLock) {
            unloadUserLocked(userId);

            mUnlockedUsers.put(userId, false);
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

        mMaxShortcuts = Math.max(0, (int) parser.getLong(
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
    static Intent parseIntentAttributeNoDefault(XmlPullParser parser, String attribute) {
        final String value = parseStringAttribute(parser, attribute);
        Intent parsed = null;
        if (!TextUtils.isEmpty(value)) {
            try {
                parsed = Intent.parseUri(value, /* flags =*/ 0);
            } catch (URISyntaxException e) {
                Slog.e(TAG, "Error parsing intent", e);
            }
        }
        return parsed;
    }

    @Nullable
    static Intent parseIntentAttribute(XmlPullParser parser, String attribute) {
        Intent parsed = parseIntentAttributeNoDefault(parser, attribute);
        if (parsed == null) {
            // Default intent.
            parsed = new Intent(Intent.ACTION_VIEW);
        }
        return parsed;
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

    static void writeAttr(XmlSerializer out, String name, CharSequence value) throws IOException {
        if (TextUtils.isEmpty(value)) return;

        out.attribute(null, name, value.toString());
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
                    default:
                        Slog.e(TAG, "Invalid tag: " + tag);
                        break;
                }
            }
        } catch (FileNotFoundException e) {
            // Use the default
        } catch (IOException | XmlPullParserException e) {
            Slog.e(TAG, "Failed to read file " + file.getBaseFile(), e);

            mRawLastResetTime = 0;
        }
        // Adjust the last reset time.
        getLastResetTimeLocked();
    }

    @VisibleForTesting
    final File getUserFile(@UserIdInt int userId) {
        return new File(injectUserDataPath(userId), FILENAME_USER_PACKAGES);
    }

    private void saveUserLocked(@UserIdInt int userId) {
        final File path = getUserFile(userId);
        if (DEBUG) {
            Slog.d(TAG, "Saving to " + path);
        }
        path.getParentFile().mkdirs();
        final AtomicFile file = new AtomicFile(path);
        FileOutputStream os = null;
        try {
            os = file.startWrite();

            saveUserInternalLocked(userId, os, /* forBackup= */ false);

            file.finishWrite(os);

            // Remove all dangling bitmap files.
            cleanupDanglingBitmapDirectoriesLocked(userId);
        } catch (XmlPullParserException | IOException e) {
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

        getUserShortcutsLocked(userId).saveToXml(out, forBackup);

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
        final File path = getUserFile(userId);
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
            final ShortcutUser ret = loadUserInternal(userId, in, /* forBackup= */ false);
            return ret;
        } catch (IOException | XmlPullParserException e) {
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
        try {
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
        } catch (Exception e) {
            wtf("Exception in saveDirtyInfo", e);
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

    // Requires mLock held, but "Locked" prefix would look weired so we just say "L".
    protected boolean isUserUnlockedL(@UserIdInt int userId) {
        // First, check the local copy.
        if (mUnlockedUsers.get(userId)) {
            return true;
        }
        // If the local copy says the user is locked, check with AM for the actual state, since
        // the user might just have been unlocked.
        // Note we just don't use isUserUnlockingOrUnlocked() here, because it'll return false
        // when the user is STOPPING, which we still want to consider as "unlocked".
        final long token = injectClearCallingIdentity();
        try {
            return mUserManager.isUserUnlockingOrUnlocked(userId);
        } finally {
            injectRestoreCallingIdentity(token);
        }
    }

    // Requires mLock held, but "Locked" prefix would look weired so we jsut say "L".
    void throwIfUserLockedL(@UserIdInt int userId) {
        if (!isUserUnlockedL(userId)) {
            throw new IllegalStateException("User " + userId + " is locked or not running");
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
        if (!isUserUnlockedL(userId)) {
            wtf("User still locked");
        }

        ShortcutUser userPackages = mUsers.get(userId);
        if (userPackages == null) {
            userPackages = loadUserLocked(userId);
            if (userPackages == null) {
                userPackages = new ShortcutUser(this, userId);
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
        return getUserShortcutsLocked(userId).getPackageShortcuts(packageName);
    }

    @GuardedBy("mLock")
    @NonNull
    ShortcutLauncher getLauncherShortcutsLocked(
            @NonNull String packageName, @UserIdInt int ownerUserId,
            @UserIdInt int launcherUserId) {
        return getUserShortcutsLocked(ownerUserId)
                .getLauncherShortcuts(packageName, launcherUserId);
    }

    // === Caller validation ===

    void removeIcon(@UserIdInt int userId, ShortcutInfo shortcut) {
        // Do not remove the actual bitmap file yet, because if the device crashes before saving
        // he XML we'd lose the icon.  We just remove all dangling files after saving the XML.
        shortcut.setIconResourceId(0);
        shortcut.setIconResName(null);
        shortcut.clearFlags(ShortcutInfo.FLAG_HAS_ICON_FILE | ShortcutInfo.FLAG_HAS_ICON_RES);
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

    private void cleanupDanglingBitmapDirectoriesLocked(@UserIdInt int userId) {
        if (DEBUG) {
            Slog.d(TAG, "cleanupDanglingBitmaps: userId=" + userId);
        }
        final long start = injectElapsedRealtime();

        final ShortcutUser user = getUserShortcutsLocked(userId);

        final File bitmapDir = getUserBitmapFilePath(userId);
        final File[] children = bitmapDir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            final String packageName = child.getName();
            if (DEBUG) {
                Slog.d(TAG, "cleanupDanglingBitmaps: Found directory=" + packageName);
            }
            if (!user.hasPackage(packageName)) {
                if (DEBUG) {
                    Slog.d(TAG, "Removing dangling bitmap directory: " + packageName);
                }
                cleanupBitmapsForPackage(userId, packageName);
            } else {
                cleanupDanglingBitmapFilesLocked(userId, user, packageName, child);
            }
        }
        logDurationStat(Stats.CLEANUP_DANGLING_BITMAPS, start);
    }

    private void cleanupDanglingBitmapFilesLocked(@UserIdInt int userId, @NonNull ShortcutUser user,
            @NonNull String packageName, @NonNull File path) {
        final ArraySet<String> usedFiles =
                user.getPackageShortcuts(packageName).getUsedBitmapFiles();

        for (File child : path.listFiles()) {
            if (!child.isFile()) {
                continue;
            }
            final String name = child.getName();
            if (!usedFiles.contains(name)) {
                if (DEBUG) {
                    Slog.d(TAG, "Removing dangling bitmap file: " + child.getAbsolutePath());
                }
                child.delete();
            }
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
                shortcut.getPackage());
        if (!packagePath.isDirectory()) {
            packagePath.mkdirs();
            if (!packagePath.isDirectory()) {
                throw new IOException("Unable to create directory " + packagePath);
            }
            SELinux.restorecon(packagePath);
        }

        final String baseName = String.valueOf(injectCurrentTimeMillis());
        for (int suffix = 0; ; suffix++) {
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
            removeIcon(userId, shortcut);

            final Icon icon = shortcut.getIcon();
            if (icon == null) {
                return; // has no icon
            }

            Bitmap bitmap;
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
                } catch (IOException | RuntimeException e) {
                    // STOPSHIP Change wtf to e
                    Slog.wtf(ShortcutService.TAG, "Unable to write bitmap to file", e);
                    if (path != null && path.exists()) {
                        path.delete();
                    }
                }
            } finally {
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
        if (!shortcut.getPackage().equals(icon.getResPackage())) {
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

    /**
     * For a shortcut, update all resource names from resource IDs, and also update all
     * resource-based strings.
     */
    void fixUpShortcutResourceNamesAndValues(ShortcutInfo si) {
        final Resources publisherRes = injectGetResourcesForApplicationAsUser(
                si.getPackage(), si.getUserId());
        if (publisherRes != null) {
            final long start = injectElapsedRealtime();
            try {
                si.lookupAndFillInResourceNames(publisherRes);
            } finally {
                logDurationStat(Stats.RESOURCE_NAME_LOOKUP, start);
            }
            si.resolveResourceStrings(publisherRes);
        }
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
        if (!(isCallerSystem() || isCallerShell())) {
            throw new SecurityException("Caller must be system or shell");
        }
    }

    private void enforceShell() {
        if (!isCallerShell()) {
            throw new SecurityException("Caller must be shell");
        }
    }

    private void enforceSystem() {
        if (!isCallerSystem()) {
            throw new SecurityException("Caller must be system");
        }
    }

    private void enforceResetThrottlingPermission() {
        if (isCallerSystem()) {
            return;
        }
        enforceCallingOrSelfPermission(
                android.Manifest.permission.RESET_SHORTCUT_MANAGER_THROTTLING, null);
    }

    private void enforceCallingOrSelfPermission(
            @NonNull String permission, @Nullable String message) {
        if (isCallerSystem()) {
            return;
        }
        injectEnforceCallingPermission(permission, message);
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

    // Overridden in unit tests to execute r synchronously.
    void injectPostToHandler(Runnable r) {
        mHandler.post(r);
    }

    /**
     * @throws IllegalArgumentException if {@code numShortcuts} is bigger than
     *                                  {@link #getMaxActivityShortcuts()}.
     */
    void enforceMaxActivityShortcuts(int numShortcuts) {
        if (numShortcuts > mMaxShortcuts) {
            throw new IllegalArgumentException("Max number of dynamic shortcuts exceeded");
        }
    }

    /**
     * Return the max number of dynamic + manifest shortcuts for each launcher icon.
     */
    int getMaxActivityShortcuts() {
        return mMaxShortcuts;
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
        injectPostToHandler(() -> {
            try {
                final ArrayList<ShortcutChangeListener> copy;
                synchronized (mLock) {
                    if (!isUserUnlockedL(userId)) {
                        return;
                    }

                    copy = new ArrayList<>(mListeners);
                }
                // Note onShortcutChanged() needs to be called with the system service permissions.
                for (int i = copy.size() - 1; i >= 0; i--) {
                    copy.get(i).onShortcutChanged(packageName, userId);
                }
            } catch (Exception ignore) {
            }
        });
    }

    /**
     * Clean up / validate an incoming shortcut.
     * - Make sure all mandatory fields are set.
     * - Make sure the intent's extras are persistable, and them to set
     * {@link ShortcutInfo#mIntentPersistableExtrases}.  Also clear its extras.
     * - Clear flags.
     *
     * TODO Detailed unit tests
     */
    private void fixUpIncomingShortcutInfo(@NonNull ShortcutInfo shortcut, boolean forUpdate) {
        Preconditions.checkNotNull(shortcut, "Null shortcut detected");
        if (shortcut.getActivity() != null) {
            Preconditions.checkState(
                    shortcut.getPackage().equals(shortcut.getActivity().getPackageName()),
                    "Cannot publish shortcut: activity " + shortcut.getActivity() + " does not"
                    + " belong to package " + shortcut.getPackage());
            Preconditions.checkState(
                    injectIsMainActivity(shortcut.getActivity(), shortcut.getUserId()),
                    "Cannot publish shortcut: activity " + shortcut.getActivity() + " is not"
                            + " main activity");
        }

        if (!forUpdate) {
            shortcut.enforceMandatoryFields();
            Preconditions.checkArgument(
                    injectIsMainActivity(shortcut.getActivity(), shortcut.getUserId()),
                    "Cannot publish shortcut: " + shortcut.getActivity() + " is not main activity");
        }
        if (shortcut.getIcon() != null) {
            ShortcutInfo.validateIcon(shortcut.getIcon());
        }

        shortcut.replaceFlags(0);
    }

    /**
     * When a shortcut has no target activity, set the default one from the package.
     */
    private void fillInDefaultActivity(List<ShortcutInfo> shortcuts) {

        ComponentName defaultActivity = null;
        for (int i = shortcuts.size() - 1; i >= 0; i--) {
            final ShortcutInfo si = shortcuts.get(i);
            if (si.getActivity() == null) {
                if (defaultActivity == null) {
                    defaultActivity = injectGetDefaultMainActivity(
                            si.getPackage(), si.getUserId());
                    Preconditions.checkState(defaultActivity != null,
                            "Launcher activity not found for package " + si.getPackage());
                }
                si.setActivity(defaultActivity);
            }
        }
    }

    private void assignImplicitRanks(List<ShortcutInfo> shortcuts) {
        for (int i = shortcuts.size() - 1; i >= 0; i--) {
            shortcuts.get(i).setImplicitRank(i);
        }
    }

    // === APIs ===

    @Override
    public boolean setDynamicShortcuts(String packageName, ParceledListSlice shortcutInfoList,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        final List<ShortcutInfo> newShortcuts = (List<ShortcutInfo>) shortcutInfoList.getList();
        final int size = newShortcuts.size();

        synchronized (mLock) {
            throwIfUserLockedL(userId);

            final ShortcutPackage ps = getPackageShortcutsLocked(packageName, userId);
            ps.getUser().onCalledByPublisher(packageName);

            ps.ensureImmutableShortcutsNotIncluded(newShortcuts);

            fillInDefaultActivity(newShortcuts);

            ps.enforceShortcutCountsBeforeOperation(newShortcuts, OPERATION_SET);

            // Throttling.
            if (!ps.tryApiCall()) {
                return false;
            }

            // Initialize the implicit ranks for ShortcutPackage.adjustRanks().
            ps.clearAllImplicitRanks();
            assignImplicitRanks(newShortcuts);

            for (int i = 0; i < size; i++) {
                fixUpIncomingShortcutInfo(newShortcuts.get(i), /* forUpdate= */ false);
            }

            // First, remove all un-pinned; dynamic shortcuts
            ps.deleteAllDynamicShortcuts();

            // Then, add/update all.  We need to make sure to take over "pinned" flag.
            for (int i = 0; i < size; i++) {
                final ShortcutInfo newShortcut = newShortcuts.get(i);
                ps.addOrUpdateDynamicShortcut(newShortcut);
            }

            // Lastly, adjust the ranks.
            ps.adjustRanks();
        }
        packageShortcutsChanged(packageName, userId);

        verifyStates();

        return true;
    }

    @Override
    public boolean updateShortcuts(String packageName, ParceledListSlice shortcutInfoList,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        final List<ShortcutInfo> newShortcuts = (List<ShortcutInfo>) shortcutInfoList.getList();
        final int size = newShortcuts.size();

        synchronized (mLock) {
            throwIfUserLockedL(userId);

            final ShortcutPackage ps = getPackageShortcutsLocked(packageName, userId);
            ps.getUser().onCalledByPublisher(packageName);

            ps.ensureImmutableShortcutsNotIncluded(newShortcuts);

            // For update, don't fill in the default activity.  Having null activity means
            // "don't update the activity" here.

            ps.enforceShortcutCountsBeforeOperation(newShortcuts, OPERATION_UPDATE);

            // Throttling.
            if (!ps.tryApiCall()) {
                return false;
            }

            // Initialize the implicit ranks for ShortcutPackage.adjustRanks().
            ps.clearAllImplicitRanks();
            assignImplicitRanks(newShortcuts);

            for (int i = 0; i < size; i++) {
                final ShortcutInfo source = newShortcuts.get(i);
                fixUpIncomingShortcutInfo(source, /* forUpdate= */ true);

                final ShortcutInfo target = ps.findShortcutById(source.getId());
                if (target == null) {
                    continue;
                }

                if (target.isEnabled() != source.isEnabled()) {
                    Slog.w(TAG,
                            "ShortcutInfo.enabled cannot be changed with updateShortcuts()");
                }

                // When updating the rank, we need to insert between existing ranks, so set
                // this setRankChanged, and also copy the implicit rank fo adjustRanks().
                if (source.hasRank()) {
                    target.setRankChanged();
                    target.setImplicitRank(source.getImplicitRank());
                }

                final boolean replacingIcon = (source.getIcon() != null);
                if (replacingIcon) {
                    removeIcon(userId, target);
                }

                // Note copyNonNullFieldsFrom() does the "updatable with?" check too.
                target.copyNonNullFieldsFrom(source);
                target.setTimestamp(injectCurrentTimeMillis());

                if (replacingIcon) {
                    saveIconAndFixUpShortcut(userId, target);
                }

                // When we're updating any resource related fields, re-extract the res names and
                // the values.
                if (replacingIcon || source.hasStringResources()) {
                    fixUpShortcutResourceNamesAndValues(target);
                }
            }

            // Lastly, adjust the ranks.
            ps.adjustRanks();
        }
        packageShortcutsChanged(packageName, userId);

        verifyStates();

        return true;
    }

    @Override
    public boolean addDynamicShortcuts(String packageName, ParceledListSlice shortcutInfoList,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        final List<ShortcutInfo> newShortcuts = (List<ShortcutInfo>) shortcutInfoList.getList();
        final int size = newShortcuts.size();

        synchronized (mLock) {
            throwIfUserLockedL(userId);

            final ShortcutPackage ps = getPackageShortcutsLocked(packageName, userId);
            ps.getUser().onCalledByPublisher(packageName);

            ps.ensureImmutableShortcutsNotIncluded(newShortcuts);

            fillInDefaultActivity(newShortcuts);

            ps.enforceShortcutCountsBeforeOperation(newShortcuts, OPERATION_ADD);

            // Initialize the implicit ranks for ShortcutPackage.adjustRanks().
            ps.clearAllImplicitRanks();
            assignImplicitRanks(newShortcuts);

            // Throttling.
            if (!ps.tryApiCall()) {
                return false;
            }
            for (int i = 0; i < size; i++) {
                final ShortcutInfo newShortcut = newShortcuts.get(i);

                // Validate the shortcut.
                fixUpIncomingShortcutInfo(newShortcut, /* forUpdate= */ false);

                // When ranks are changing, we need to insert between ranks, so set the
                // "rank changed" flag.
                newShortcut.setRankChanged();

                // Add it.
                ps.addOrUpdateDynamicShortcut(newShortcut);
            }

            // Lastly, adjust the ranks.
            ps.adjustRanks();
        }
        packageShortcutsChanged(packageName, userId);

        verifyStates();

        return true;
    }

    @Override
    public void disableShortcuts(String packageName, List shortcutIds,
            CharSequence disabledMessage, int disabledMessageResId, @UserIdInt int userId) {
        verifyCaller(packageName, userId);
        Preconditions.checkNotNull(shortcutIds, "shortcutIds must be provided");

        synchronized (mLock) {
            throwIfUserLockedL(userId);

            final ShortcutPackage ps = getPackageShortcutsLocked(packageName, userId);
            ps.getUser().onCalledByPublisher(packageName);

            ps.ensureImmutableShortcutsNotIncludedWithIds((List<String>) shortcutIds);

            final String disabledMessageString =
                    (disabledMessage == null) ? null : disabledMessage.toString();

            for (int i = shortcutIds.size() - 1; i >= 0; i--) {
                ps.disableWithId(Preconditions.checkStringNotEmpty((String) shortcutIds.get(i)),
                        disabledMessageString, disabledMessageResId,
                        /* overrideImmutable=*/ false);
            }

            // We may have removed dynamic shortcuts which may have left a gap, so adjust the ranks.
            ps.adjustRanks();
        }
        packageShortcutsChanged(packageName, userId);

        verifyStates();
    }

    @Override
    public void enableShortcuts(String packageName, List shortcutIds, @UserIdInt int userId) {
        verifyCaller(packageName, userId);
        Preconditions.checkNotNull(shortcutIds, "shortcutIds must be provided");

        synchronized (mLock) {
            throwIfUserLockedL(userId);

            final ShortcutPackage ps = getPackageShortcutsLocked(packageName, userId);
            ps.getUser().onCalledByPublisher(packageName);

            ps.ensureImmutableShortcutsNotIncludedWithIds((List<String>) shortcutIds);

            for (int i = shortcutIds.size() - 1; i >= 0; i--) {
                ps.enableWithId((String) shortcutIds.get(i));
            }
        }
        packageShortcutsChanged(packageName, userId);

        verifyStates();
    }

    @Override
    public void removeDynamicShortcuts(String packageName, List shortcutIds,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);
        Preconditions.checkNotNull(shortcutIds, "shortcutIds must be provided");

        synchronized (mLock) {
            throwIfUserLockedL(userId);

            final ShortcutPackage ps = getPackageShortcutsLocked(packageName, userId);
            ps.getUser().onCalledByPublisher(packageName);

            ps.ensureImmutableShortcutsNotIncludedWithIds((List<String>) shortcutIds);

            for (int i = shortcutIds.size() - 1; i >= 0; i--) {
                ps.deleteDynamicWithId(
                        Preconditions.checkStringNotEmpty((String) shortcutIds.get(i)));
            }

            // We may have removed dynamic shortcuts which may have left a gap, so adjust the ranks.
            ps.adjustRanks();
        }
        packageShortcutsChanged(packageName, userId);

        verifyStates();
    }

    @Override
    public void removeAllDynamicShortcuts(String packageName, @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        synchronized (mLock) {
            throwIfUserLockedL(userId);

            final ShortcutPackage ps = getPackageShortcutsLocked(packageName, userId);
            ps.getUser().onCalledByPublisher(packageName);
            ps.deleteAllDynamicShortcuts();
        }
        packageShortcutsChanged(packageName, userId);

        verifyStates();
    }

    @Override
    public ParceledListSlice<ShortcutInfo> getDynamicShortcuts(String packageName,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        synchronized (mLock) {
            throwIfUserLockedL(userId);

            return getShortcutsWithQueryLocked(
                    packageName, userId, ShortcutInfo.CLONE_REMOVE_FOR_CREATOR,
                    ShortcutInfo::isDynamic);
        }
    }

    @Override
    public ParceledListSlice<ShortcutInfo> getManifestShortcuts(String packageName,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        synchronized (mLock) {
            throwIfUserLockedL(userId);

            return getShortcutsWithQueryLocked(
                    packageName, userId, ShortcutInfo.CLONE_REMOVE_FOR_CREATOR,
                    ShortcutInfo::isManifestShortcut);
        }
    }

    @Override
    public ParceledListSlice<ShortcutInfo> getPinnedShortcuts(String packageName,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        synchronized (mLock) {
            throwIfUserLockedL(userId);

            return getShortcutsWithQueryLocked(
                    packageName, userId, ShortcutInfo.CLONE_REMOVE_FOR_CREATOR,
                    ShortcutInfo::isPinned);
        }
    }

    private ParceledListSlice<ShortcutInfo> getShortcutsWithQueryLocked(@NonNull String packageName,
            @UserIdInt int userId, int cloneFlags, @NonNull Predicate<ShortcutInfo> query) {

        final ArrayList<ShortcutInfo> ret = new ArrayList<>();

        final ShortcutPackage ps = getPackageShortcutsLocked(packageName, userId);
        ps.getUser().onCalledByPublisher(packageName);
        ps.findAll(ret, query, cloneFlags);

        return new ParceledListSlice<>(ret);
    }

    @Override
    public int getMaxShortcutCountPerActivity(String packageName, @UserIdInt int userId)
            throws RemoteException {
        verifyCaller(packageName, userId);

        return mMaxShortcuts;
    }

    @Override
    public int getRemainingCallCount(String packageName, @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        synchronized (mLock) {
            throwIfUserLockedL(userId);

            final ShortcutPackage ps = getPackageShortcutsLocked(packageName, userId);
            ps.getUser().onCalledByPublisher(packageName);
            return mMaxUpdatesPerInterval - ps.getApiCallCount();
        }
    }

    @Override
    public long getRateLimitResetTime(String packageName, @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        synchronized (mLock) {
            throwIfUserLockedL(userId);

            return getNextResetTimeLocked();
        }
    }

    @Override
    public int getIconMaxDimensions(String packageName, int userId) {
        verifyCaller(packageName, userId);

        synchronized (mLock) {
            return mMaxIconDimension;
        }
    }

    @Override
    public void reportShortcutUsed(String packageName, String shortcutId, int userId) {
        verifyCaller(packageName, userId);

        Preconditions.checkNotNull(shortcutId);

        if (DEBUG) {
            Slog.d(TAG, String.format("reportShortcutUsed: Shortcut %s package %s used on user %d",
                    shortcutId, packageName, userId));
        }

        synchronized (mLock) {
            throwIfUserLockedL(userId);

            final ShortcutPackage ps = getPackageShortcutsLocked(packageName, userId);
            ps.getUser().onCalledByPublisher(packageName);

            if (ps.findShortcutById(shortcutId) == null) {
                Log.w(TAG, String.format("reportShortcutUsed: package %s doesn't have shortcut %s",
                        packageName, shortcutId));
                return;
            }
        }

        final long token = injectClearCallingIdentity();
        try {
            mUsageStatsManagerInternal.reportShortcutUsage(packageName, shortcutId, userId);
        } finally {
            injectRestoreCallingIdentity(token);
        }
    }

    /**
     * Reset all throttling, for developer options and command line.  Only system/shell can call
     * it.
     */
    @Override
    public void resetThrottling() {
        enforceSystemOrShell();

        resetThrottlingInner(getCallingUserId());
    }

    void resetThrottlingInner(@UserIdInt int userId) {
        synchronized (mLock) {
            if (!isUserUnlockedL(userId)) {
                Log.w(TAG, "User " + userId + " is locked or not running");
                return;
            }

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

    @Override
    public void onApplicationActive(String packageName, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onApplicationActive: package=" + packageName + "  userid=" + userId);
        }
        enforceResetThrottlingPermission();

        synchronized (mLock) {
            if (!isUserUnlockedL(userId)) {
                // This is called by system UI, so no need to throw.  Just ignore.
                return;
            }

            getPackageShortcutsLocked(packageName, userId)
                    .resetRateLimitingForCommandLineNoSaving();
            saveUserLocked(userId);
        }
    }

    // We override this method in unit tests to do a simpler check.
    boolean hasShortcutHostPermission(@NonNull String callingPackage, int userId) {
        final long start = injectElapsedRealtime();
        try {
            return hasShortcutHostPermissionInner(callingPackage, userId);
        } finally {
            logDurationStat(Stats.LAUNCHER_PERMISSION_CHECK, start);
        }
    }

    // This method is extracted so we can directly call this method from unit tests,
    // even when hasShortcutPermission() is overridden.
    @VisibleForTesting
    boolean hasShortcutHostPermissionInner(@NonNull String callingPackage, int userId) {
        synchronized (mLock) {
            throwIfUserLockedL(userId);

            final ShortcutUser user = getUserShortcutsLocked(userId);

            // Always trust the in-memory cache.
            final ComponentName cached = user.getCachedLauncher();
            if (cached != null) {
                if (cached.getPackageName().equals(callingPackage)) {
                    return true;
                }
            }
            // If the cached one doesn't match, then go ahead

            final List<ResolveInfo> allHomeCandidates = new ArrayList<>();

            // Default launcher from package manager.
            final long startGetHomeActivitiesAsUser = injectElapsedRealtime();
            final ComponentName defaultLauncher = mPackageManagerInternal
                    .getHomeActivitiesAsUser(allHomeCandidates, userId);
            logDurationStat(Stats.GET_DEFAULT_HOME, startGetHomeActivitiesAsUser);

            ComponentName detected;
            if (defaultLauncher != null) {
                detected = defaultLauncher;
                if (DEBUG) {
                    Slog.v(TAG, "Default launcher from PM: " + detected);
                }
            } else {
                detected = user.getLastKnownLauncher();

                if (detected != null) {
                    if (injectIsActivityEnabledAndExported(detected, userId)) {
                        if (DEBUG) {
                            Slog.v(TAG, "Cached launcher: " + detected);
                        }
                    } else {
                        Slog.w(TAG, "Cached launcher " + detected + " no longer exists");
                        detected = null;
                        user.clearLauncher();
                    }
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

            // Update the cache.
            user.setLauncher(detected);
            if (detected != null) {
                if (DEBUG) {
                    Slog.v(TAG, "Detected launcher: " + detected);
                }
                return detected.getPackageName().equals(callingPackage);
            } else {
                // Default launcher not found.
                return false;
            }
        }
    }

    // === House keeping ===

    private void cleanUpPackageForAllLoadedUsers(String packageName, @UserIdInt int packageUserId,
            boolean appStillExists) {
        synchronized (mLock) {
            forEachLoadedUserLocked(user ->
                    cleanUpPackageLocked(packageName, user.getUserId(), packageUserId,
                            appStillExists));
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
    void cleanUpPackageLocked(String packageName, int owningUserId, int packageUserId,
            boolean appStillExists) {
        final boolean wasUserLoaded = isUserLoadedLocked(owningUserId);

        final ShortcutUser user = getUserShortcutsLocked(owningUserId);
        boolean doNotify = false;

        // First, remove the package from the package list (if the package is a publisher).
        if (packageUserId == owningUserId) {
            if (user.removePackage(packageName) != null) {
                doNotify = true;
            }
        }

        // Also remove from the launcher list (if the package is a launcher).
        user.removeLauncher(packageUserId, packageName);

        // Then remove pinned shortcuts from all launchers.
        user.forAllLaunchers(l -> l.cleanUpPackage(packageName, packageUserId));

        // Now there may be orphan shortcuts because we removed pinned shortcuts at the previous
        // step.  Remove them too.
        user.forAllPackages(p -> p.refreshPinnedFlags());

        scheduleSaveUser(owningUserId);

        if (doNotify) {
            notifyListeners(packageName, owningUserId);
        }

        // If the app still exists (i.e. data cleared), we need to re-publish manifest shortcuts.
        if (appStillExists && (packageUserId == owningUserId)) {
            // This will do the notification and save when needed, so do it after the above
            // notifyListeners.
            user.rescanPackageIfNeeded(packageName, /* forceRescan=*/ true);
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

            final boolean cloneKeyFieldOnly =
                    ((queryFlags & ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY) != 0);
            final int cloneFlag = cloneKeyFieldOnly ? ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO
                    : ShortcutInfo.CLONE_REMOVE_FOR_LAUNCHER;
            if (packageName == null) {
                shortcutIds = null; // LauncherAppsService already threw for it though.
            }

            synchronized (mLock) {
                throwIfUserLockedL(userId);
                throwIfUserLockedL(launcherUserId);

                getLauncherShortcutsLocked(callingPackage, userId, launcherUserId)
                        .attemptToRestoreIfNeededAndSave();

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

            final ShortcutPackage p = getUserShortcutsLocked(userId)
                    .getPackageShortcutsIfExists(packageName);
            if (p == null) {
                return; // No need to instantiate ShortcutPackage.
            }

            p.findAll(ret,
                    (ShortcutInfo si) -> {
                        if (si.getLastChangedTimestamp() < changedSince) {
                            return false;
                        }
                        if (ids != null && !ids.contains(si.getId())) {
                            return false;
                        }
                        if (componentName != null) {
                            if (si.getActivity() != null
                                    && !si.getActivity().equals(componentName)) {
                                return false;
                            }
                        }
                        if (((queryFlags & ShortcutQuery.FLAG_GET_DYNAMIC) != 0)
                                && si.isDynamic()) {
                            return true;
                        }
                        if (((queryFlags & ShortcutQuery.FLAG_GET_PINNED) != 0)
                                && si.isPinned()) {
                            return true;
                        }
                        if (((queryFlags & ShortcutQuery.FLAG_GET_MANIFEST) != 0)
                                && si.isManifestShortcut()) {
                            return true;
                        }
                        return false;
                    }, cloneFlag, callingPackage, launcherUserId);
        }

        @Override
        public boolean isPinnedByCaller(int launcherUserId, @NonNull String callingPackage,
                @NonNull String packageName, @NonNull String shortcutId, int userId) {
            Preconditions.checkStringNotEmpty(packageName, "packageName");
            Preconditions.checkStringNotEmpty(shortcutId, "shortcutId");

            synchronized (mLock) {
                throwIfUserLockedL(userId);
                throwIfUserLockedL(launcherUserId);

                getLauncherShortcutsLocked(callingPackage, userId, launcherUserId)
                        .attemptToRestoreIfNeededAndSave();

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

            throwIfUserLockedL(userId);
            throwIfUserLockedL(launcherUserId);

            final ShortcutPackage p = getUserShortcutsLocked(userId)
                    .getPackageShortcutsIfExists(packageName);
            if (p == null) {
                return null;
            }

            final ArrayList<ShortcutInfo> list = new ArrayList<>(1);
            p.findAll(list,
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
                throwIfUserLockedL(userId);
                throwIfUserLockedL(launcherUserId);

                final ShortcutLauncher launcher =
                        getLauncherShortcutsLocked(callingPackage, userId, launcherUserId);
                launcher.attemptToRestoreIfNeededAndSave();

                launcher.pinShortcuts(userId, packageName, shortcutIds);
            }
            packageShortcutsChanged(packageName, userId);

            verifyStates();
        }

        @Override
        public Intent[] createShortcutIntents(int launcherUserId,
                @NonNull String callingPackage,
                @NonNull String packageName, @NonNull String shortcutId, int userId) {
            // Calling permission must be checked by LauncherAppsImpl.
            Preconditions.checkStringNotEmpty(packageName, "packageName can't be empty");
            Preconditions.checkStringNotEmpty(shortcutId, "shortcutId can't be empty");

            synchronized (mLock) {
                throwIfUserLockedL(userId);
                throwIfUserLockedL(launcherUserId);

                getLauncherShortcutsLocked(callingPackage, userId, launcherUserId)
                        .attemptToRestoreIfNeededAndSave();

                // Make sure the shortcut is actually visible to the launcher.
                final ShortcutInfo si = getShortcutInfoLocked(
                        launcherUserId, callingPackage, packageName, shortcutId, userId);
                // "si == null" should suffice here, but check the flags too just to make sure.
                if (si == null || !si.isEnabled() || !si.isAlive()) {
                    Log.e(TAG, "Shortcut " + shortcutId + " does not exist or disabled");
                    return null;
                }
                return si.getIntents();
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
                throwIfUserLockedL(userId);
                throwIfUserLockedL(launcherUserId);

                getLauncherShortcutsLocked(callingPackage, userId, launcherUserId)
                        .attemptToRestoreIfNeededAndSave();

                final ShortcutPackage p = getUserShortcutsLocked(userId)
                        .getPackageShortcutsIfExists(packageName);
                if (p == null) {
                    return 0;
                }

                final ShortcutInfo shortcutInfo = p.findShortcutById(shortcutId);
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
                throwIfUserLockedL(userId);
                throwIfUserLockedL(launcherUserId);

                getLauncherShortcutsLocked(callingPackage, userId, launcherUserId)
                        .attemptToRestoreIfNeededAndSave();

                final ShortcutPackage p = getUserShortcutsLocked(userId)
                        .getPackageShortcutsIfExists(packageName);
                if (p == null) {
                    return null;
                }

                final ShortcutInfo shortcutInfo = p.findShortcutById(shortcutId);
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
    }

    final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mBootCompleted.get()) {
                return; // Boot not completed, ignore the broadcast.
            }
            try {
                if (Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) {
                    handleLocaleChanged();
                }
            } catch (Exception e) {
                wtf("Exception in mReceiver.onReceive", e);
            }
        }
    };

    void handleLocaleChanged() {
        if (DEBUG) {
            Slog.d(TAG, "handleLocaleChanged");
        }
        scheduleSaveBaseState();

        synchronized (mLock) {
            final long token = injectClearCallingIdentity();
            try {
                forEachLoadedUserLocked(user -> user.detectLocaleChange());
            } finally {
                injectRestoreCallingIdentity(token);
            }
        }
    }

    /**
     * Package event callbacks.
     */
    @VisibleForTesting
    final BroadcastReceiver mPackageMonitor = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int userId  = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            if (userId == UserHandle.USER_NULL) {
                Slog.w(TAG, "Intent broadcast does not contain user handle: " + intent);
                return;
            }

            final String action = intent.getAction();

            // This is normally called on Handler, so clearCallingIdentity() isn't needed,
            // but we still check it in unit tests.
            final long token = injectClearCallingIdentity();
            try {
                synchronized (mLock) {
                    if (!isUserUnlockedL(userId)) {
                        if (DEBUG) {
                            Slog.d(TAG, "Ignoring package broadcast " + action
                                    + " for locked/stopped user " + userId);
                        }
                        return;
                    }

                    // Whenever we get one of those package broadcasts, or get
                    // ACTION_PREFERRED_ACTIVITY_CHANGED, we purge the default launcher cache.
                    final ShortcutUser user = getUserShortcutsLocked(userId);
                    user.clearLauncher();
                }
                if (Intent.ACTION_PREFERRED_ACTIVITY_CHANGED.equals(action)) {
                    // Nothing farther to do.
                    return;
                }

                final Uri intentUri = intent.getData();
                final String packageName = (intentUri != null) ? intentUri.getSchemeSpecificPart()
                        : null;
                if (packageName == null) {
                    Slog.w(TAG, "Intent broadcast does not contain package name: " + intent);
                    return;
                }

                final boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);

                switch (action) {
                    case Intent.ACTION_PACKAGE_ADDED:
                        if (replacing) {
                            handlePackageUpdateFinished(packageName, userId);
                        } else {
                            handlePackageAdded(packageName, userId);
                        }
                        break;
                    case Intent.ACTION_PACKAGE_REMOVED:
                        if (!replacing) {
                            handlePackageRemoved(packageName, userId);
                        }
                        break;
                    case Intent.ACTION_PACKAGE_CHANGED:
                        handlePackageChanged(packageName, userId);

                        break;
                    case Intent.ACTION_PACKAGE_DATA_CLEARED:
                        handlePackageDataCleared(packageName, userId);
                        break;
                }
            } catch (Exception e) {
                wtf("Exception in mPackageMonitor.onReceive", e);
            } finally {
                injectRestoreCallingIdentity(token);
            }
        }
    };

    /**
     * Called when a user is unlocked.
     * - Check all known packages still exist, and otherwise perform cleanup.
     * - If a package still exists, check the version code.  If it's been updated, may need to
     * update timestamps of its shortcuts.
     */
    @VisibleForTesting
    void checkPackageChanges(@UserIdInt int ownerUserId) {
        if (DEBUG) {
            Slog.d(TAG, "checkPackageChanges() ownerUserId=" + ownerUserId);
        }
        if (injectIsSafeModeEnabled()) {
            Slog.i(TAG, "Safe mode, skipping checkPackageChanges()");
            return;
        }

        final long start = injectElapsedRealtime();
        try {
            final ArrayList<PackageWithUser> gonePackages = new ArrayList<>();

            synchronized (mLock) {
                final ShortcutUser user = getUserShortcutsLocked(ownerUserId);

                // Find packages that have been uninstalled.
                user.forAllPackageItems(spi -> {
                    if (spi.getPackageInfo().isShadow()) {
                        return; // Don't delete shadow information.
                    }
                    if (!isPackageInstalled(spi.getPackageName(), spi.getPackageUserId())) {
                        if (DEBUG) {
                            Slog.d(TAG, "Uninstalled: " + spi.getPackageName()
                                    + " user " + spi.getPackageUserId());
                        }
                        gonePackages.add(PackageWithUser.of(spi));
                    }
                });
                if (gonePackages.size() > 0) {
                    for (int i = gonePackages.size() - 1; i >= 0; i--) {
                        final PackageWithUser pu = gonePackages.get(i);
                        cleanUpPackageLocked(pu.packageName, ownerUserId, pu.userId,
                                /* appStillExists = */ false);
                    }
                }

                rescanUpdatedPackagesLocked(ownerUserId, user.getLastAppScanTime(),
                        /* forceRescan=*/ false);
            }
        } finally {
            logDurationStat(Stats.CHECK_PACKAGE_CHANGES, start);
        }
        verifyStates();
    }

    private void rescanUpdatedPackagesLocked(@UserIdInt int userId, long lastScanTime,
            boolean forceRescan) {
        final ShortcutUser user = getUserShortcutsLocked(userId);

        final long now = injectCurrentTimeMillis();

        // Then for each installed app, publish manifest shortcuts when needed.
        forUpdatedPackages(userId, lastScanTime, ai -> {
            user.attemptToRestoreIfNeededAndSave(this, ai.packageName, userId);
            user.rescanPackageIfNeeded(ai.packageName, forceRescan);
        });

        // Write the time just before the scan, because there may be apps that have just
        // been updated, and we want to catch them in the next time.
        user.setLastAppScanTime(now);
        scheduleSaveUser(userId);
    }

    private void handlePackageAdded(String packageName, @UserIdInt int userId) {
        if (DEBUG) {
            Slog.d(TAG, String.format("handlePackageAdded: %s user=%d", packageName, userId));
        }
        synchronized (mLock) {
            final ShortcutUser user = getUserShortcutsLocked(userId);
            user.attemptToRestoreIfNeededAndSave(this, packageName, userId);
            user.rescanPackageIfNeeded(packageName, /* forceRescan=*/ true);
        }
        verifyStates();
    }

    private void handlePackageUpdateFinished(String packageName, @UserIdInt int userId) {
        if (DEBUG) {
            Slog.d(TAG, String.format("handlePackageUpdateFinished: %s user=%d",
                    packageName, userId));
        }
        synchronized (mLock) {
            final ShortcutUser user = getUserShortcutsLocked(userId);
            user.attemptToRestoreIfNeededAndSave(this, packageName, userId);

            if (isPackageInstalled(packageName, userId)) {
                user.rescanPackageIfNeeded(packageName, /* forceRescan=*/ true);
            }
        }
        verifyStates();
    }

    private void handlePackageRemoved(String packageName, @UserIdInt int packageUserId) {
        if (DEBUG) {
            Slog.d(TAG, String.format("handlePackageRemoved: %s user=%d", packageName,
                    packageUserId));
        }
        cleanUpPackageForAllLoadedUsers(packageName, packageUserId, /* appStillExists = */ false);

        verifyStates();
    }

    private void handlePackageDataCleared(String packageName, int packageUserId) {
        if (DEBUG) {
            Slog.d(TAG, String.format("handlePackageDataCleared: %s user=%d", packageName,
                    packageUserId));
        }
        cleanUpPackageForAllLoadedUsers(packageName, packageUserId, /* appStillExists = */ true);

        verifyStates();
    }

    private void handlePackageChanged(String packageName, int packageUserId) {
        if (DEBUG) {
            Slog.d(TAG, String.format("handlePackageChanged: %s user=%d", packageName,
                    packageUserId));
        }

        // Activities may be disabled or enabled.  Just rescan the package.
        synchronized (mLock) {
            final ShortcutUser user = getUserShortcutsLocked(packageUserId);

            user.rescanPackageIfNeeded(packageName, /* forceRescan=*/ true);
        }

        verifyStates();
    }

    // === PackageManager interaction ===

    /**
     * Returns {@link PackageInfo} unless it's uninstalled or disabled.
     */
    @Nullable
    final PackageInfo getPackageInfoWithSignatures(String packageName, @UserIdInt int userId) {
        return getPackageInfo(packageName, userId, true);
    }

    /**
     * Returns {@link PackageInfo} unless it's uninstalled or disabled.
     */
    @Nullable
    final PackageInfo getPackageInfo(String packageName, @UserIdInt int userId) {
        return getPackageInfo(packageName, userId, false);
    }

    int injectGetPackageUid(@NonNull String packageName, @UserIdInt int userId) {
        final long token = injectClearCallingIdentity();
        try {
            return mIPackageManager.getPackageUid(packageName, PACKAGE_MATCH_FLAGS, userId);
        } catch (RemoteException e) {
            // Shouldn't happen.
            Slog.wtf(TAG, "RemoteException", e);
            return -1;
        } finally {
            injectRestoreCallingIdentity(token);
        }
    }

    /**
     * Returns {@link PackageInfo} unless it's uninstalled or disabled.
     */
    @Nullable
    @VisibleForTesting
    final PackageInfo getPackageInfo(String packageName, @UserIdInt int userId,
            boolean getSignatures) {
        return isInstalledOrNull(injectPackageInfoWithUninstalled(
                packageName, userId, getSignatures));
    }

    /**
     * Do not use directly; this returns uninstalled packages too.
     */
    @Nullable
    @VisibleForTesting
    PackageInfo injectPackageInfoWithUninstalled(String packageName, @UserIdInt int userId,
            boolean getSignatures) {
        final long start = injectElapsedRealtime();
        final long token = injectClearCallingIdentity();
        try {
            return mIPackageManager.getPackageInfo(
                    packageName, PACKAGE_MATCH_FLAGS
                            | (getSignatures ? PackageManager.GET_SIGNATURES : 0), userId);
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

    /**
     * Returns {@link ApplicationInfo} unless it's uninstalled or disabled.
     */
    @Nullable
    @VisibleForTesting
    final ApplicationInfo getApplicationInfo(String packageName, @UserIdInt int userId) {
        return isInstalledOrNull(injectApplicationInfoWithUninstalled(packageName, userId));
    }

    /**
     * Do not use directly; this returns uninstalled packages too.
     */
    @Nullable
    @VisibleForTesting
    ApplicationInfo injectApplicationInfoWithUninstalled(
            String packageName, @UserIdInt int userId) {
        final long start = injectElapsedRealtime();
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

    /**
     * Returns {@link ActivityInfo} with its metadata unless it's uninstalled or disabled.
     */
    @Nullable
    final ActivityInfo getActivityInfoWithMetadata(ComponentName activity, @UserIdInt int userId) {
        return isInstalledOrNull(injectGetActivityInfoWithMetadataWithUninstalled(
                activity, userId));
    }

    /**
     * Do not use directly; this returns uninstalled packages too.
     */
    @Nullable
    @VisibleForTesting
    ActivityInfo injectGetActivityInfoWithMetadataWithUninstalled(
            ComponentName activity, @UserIdInt int userId) {
        final long start = injectElapsedRealtime();
        final long token = injectClearCallingIdentity();
        try {
            return mIPackageManager.getActivityInfo(activity,
                    (PACKAGE_MATCH_FLAGS | PackageManager.GET_META_DATA), userId);
        } catch (RemoteException e) {
            // Shouldn't happen.
            Slog.wtf(TAG, "RemoteException", e);
            return null;
        } finally {
            injectRestoreCallingIdentity(token);

            logDurationStat(Stats.GET_ACTIVITY_WITH_METADATA, start);
        }
    }

    /**
     * Return all installed and enabled packages.
     */
    @NonNull
    @VisibleForTesting
    final List<PackageInfo> getInstalledPackages(@UserIdInt int userId) {
        final long start = injectElapsedRealtime();
        final long token = injectClearCallingIdentity();
        try {
            final List<PackageInfo> all = injectGetPackagesWithUninstalled(userId);

            all.removeIf(PACKAGE_NOT_INSTALLED);

            return all;
        } catch (RemoteException e) {
            // Shouldn't happen.
            Slog.wtf(TAG, "RemoteException", e);
            return null;
        } finally {
            injectRestoreCallingIdentity(token);

            logDurationStat(Stats.GET_INSTALLED_PACKAGES, start);
        }
    }

    /**
     * Do not use directly; this returns uninstalled packages too.
     */
    @NonNull
    @VisibleForTesting
    List<PackageInfo> injectGetPackagesWithUninstalled(@UserIdInt int userId)
            throws RemoteException {
        final ParceledListSlice<PackageInfo> parceledList =
                mIPackageManager.getInstalledPackages(PACKAGE_MATCH_FLAGS, userId);
        if (parceledList == null) {
            return Collections.emptyList();
        }
        return parceledList.getList();
    }

    private void forUpdatedPackages(@UserIdInt int userId, long lastScanTime,
            Consumer<ApplicationInfo> callback) {
        if (DEBUG) {
            Slog.d(TAG, "forUpdatedPackages for user " + userId + ", lastScanTime=" + lastScanTime);
        }
        final List<PackageInfo> list = getInstalledPackages(userId);
        for (int i = list.size() - 1; i >= 0; i--) {
            final PackageInfo pi = list.get(i);

            // If the package has been updated since the last scan time, then scan it.
            // Also if it's a system app with no update, lastUpdateTime is not reliable, so
            // just scan it.
            if (pi.lastUpdateTime >= lastScanTime || isPureSystemApp(pi.applicationInfo)) {
                if (DEBUG) {
                    Slog.d(TAG, "Found updated package " + pi.packageName);
                }
                callback.accept(pi.applicationInfo);
            }
        }
    }

    /**
     * @return true if it's a system app with no updates.
     */
    private boolean isPureSystemApp(ApplicationInfo ai) {
        return ai.isSystemApp() && !ai.isUpdatedSystemApp();
    }

    private boolean isApplicationFlagSet(@NonNull String packageName, int userId, int flags) {
        final ApplicationInfo ai = injectApplicationInfoWithUninstalled(packageName, userId);
        return (ai != null) && ((ai.flags & flags) == flags);
    }

    private static boolean isInstalled(@Nullable ApplicationInfo ai) {
        return (ai != null) && (ai.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
    }

    private static boolean isInstalled(@Nullable PackageInfo pi) {
        return (pi != null) && isInstalled(pi.applicationInfo);
    }

    private static boolean isInstalled(@Nullable ActivityInfo ai) {
        return (ai != null) && isInstalled(ai.applicationInfo);
    }

    private static ApplicationInfo isInstalledOrNull(ApplicationInfo ai) {
        return isInstalled(ai) ? ai : null;
    }

    private static PackageInfo isInstalledOrNull(PackageInfo pi) {
        return isInstalled(pi) ? pi : null;
    }

    private static ActivityInfo isInstalledOrNull(ActivityInfo ai) {
        return isInstalled(ai) ? ai : null;
    }

    boolean isPackageInstalled(String packageName, int userId) {
        return getApplicationInfo(packageName, userId) != null;
    }

    @Nullable
    XmlResourceParser injectXmlMetaData(ActivityInfo activityInfo, String key) {
        return activityInfo.loadXmlMetaData(mContext.getPackageManager(), key);
    }

    @Nullable
    Resources injectGetResourcesForApplicationAsUser(String packageName, int userId) {
        final long start = injectElapsedRealtime();
        final long token = injectClearCallingIdentity();
        try {
            return mContext.getPackageManager().getResourcesForApplicationAsUser(
                    packageName, userId);
        } catch (NameNotFoundException e) {
            Slog.e(TAG, "Resources for package " + packageName + " not found");
            return null;
        } finally {
            injectRestoreCallingIdentity(token);

            logDurationStat(Stats.GET_APPLICATION_RESOURCES, start);
        }
    }

    private Intent getMainActivityIntent() {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(LAUNCHER_INTENT_CATEGORY);
        return intent;
    }

    /**
     * Same as queryIntentActivitiesAsUser, except it makes sure the package is installed,
     * and only returns exported activities.
     */
    @NonNull
    @VisibleForTesting
    List<ResolveInfo> queryActivities(@NonNull Intent baseIntent,
            @NonNull String packageName, @Nullable ComponentName activity, int userId) {

        baseIntent.setPackage(Preconditions.checkNotNull(packageName));
        if (activity != null) {
            baseIntent.setComponent(activity);
        }

        final List<ResolveInfo> resolved =
                mContext.getPackageManager().queryIntentActivitiesAsUser(
                        baseIntent, PACKAGE_MATCH_FLAGS, userId);
        if (resolved == null || resolved.size() == 0) {
            return EMPTY_RESOLVE_INFO;
        }
        // Make sure the package is installed.
        if (!isInstalled(resolved.get(0).activityInfo)) {
            return EMPTY_RESOLVE_INFO;
        }
        resolved.removeIf(ACTIVITY_NOT_EXPORTED);
        return resolved;
    }

    /**
     * Return the main activity that is enabled and exported.  If multiple activities are found,
     * return the first one.
     */
    @Nullable
    ComponentName injectGetDefaultMainActivity(@NonNull String packageName, int userId) {
        final long start = injectElapsedRealtime();
        final long token = injectClearCallingIdentity();
        try {
            final List<ResolveInfo> resolved =
                    queryActivities(getMainActivityIntent(), packageName, null, userId);
            return resolved.size() == 0 ? null : resolved.get(0).activityInfo.getComponentName();
        } finally {
            injectRestoreCallingIdentity(token);

            logDurationStat(Stats.GET_LAUNCHER_ACTIVITY, start);
        }
    }

    /**
     * Return whether an activity is enabled, exported and main.
     */
    boolean injectIsMainActivity(@NonNull ComponentName activity, int userId) {
        final long start = injectElapsedRealtime();
        final long token = injectClearCallingIdentity();
        try {
            final List<ResolveInfo> resolved =
                    queryActivities(getMainActivityIntent(), activity.getPackageName(),
                            activity, userId);
            return resolved.size() > 0;
        } finally {
            injectRestoreCallingIdentity(token);

            logDurationStat(Stats.CHECK_LAUNCHER_ACTIVITY, start);
        }
    }

    /**
     * Return all the enabled, exported and main activities from a package.
     */
    @NonNull
    List<ResolveInfo> injectGetMainActivities(@NonNull String packageName, int userId) {
        final long start = injectElapsedRealtime();
        final long token = injectClearCallingIdentity();
        try {
            return queryActivities(getMainActivityIntent(), packageName, null, userId);
        } finally {
            injectRestoreCallingIdentity(token);

            logDurationStat(Stats.CHECK_LAUNCHER_ACTIVITY, start);
        }
    }

    /**
     * Return whether an activity is enabled and exported.
     */
    @VisibleForTesting
    boolean injectIsActivityEnabledAndExported(
            @NonNull ComponentName activity, @UserIdInt int userId) {
        final long start = injectElapsedRealtime();
        final long token = injectClearCallingIdentity();
        try {
            return queryActivities(new Intent(), activity.getPackageName(), activity, userId)
                    .size() > 0;
        } finally {
            injectRestoreCallingIdentity(token);

            logDurationStat(Stats.IS_ACTIVITY_ENABLED, start);
        }
    }

    boolean injectIsSafeModeEnabled() {
        final long token = injectClearCallingIdentity();
        try {
            return IWindowManager.Stub
                    .asInterface(ServiceManager.getService(Context.WINDOW_SERVICE))
                    .isSafeModeEnabled();
        } catch (RemoteException e) {
            return false; // Shouldn't happen though.
        } finally {
            injectRestoreCallingIdentity(token);
        }
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
            if (!isUserUnlockedL(userId)) {
                wtf("Can't backup: user " + userId + " is locked or not running");
                return null;
            }

            final ShortcutUser user = getUserShortcutsLocked(userId);
            if (user == null) {
                wtf("Can't backup: user not found: id=" + userId);
                return null;
            }

            user.forAllPackageItems(spi -> spi.refreshPackageInfoAndSave());

            // Then save.
            final ByteArrayOutputStream os = new ByteArrayOutputStream(32 * 1024);
            try {
                saveUserInternalLocked(userId, os, /* forBackup */ true);
            } catch (XmlPullParserException | IOException e) {
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
        synchronized (mLock) {
            if (!isUserUnlockedL(userId)) {
                wtf("Can't restore: user " + userId + " is locked or not running");
                return;
            }
            final ShortcutUser user;
            final ByteArrayInputStream is = new ByteArrayInputStream(payload);
            try {
                user = loadUserInternal(userId, is, /* fromBackup */ true);
            } catch (XmlPullParserException | IOException e) {
                Slog.w(TAG, "Restoration failed.", e);
                return;
            }
            mUsers.put(userId, user);

            // Rescan all packages to re-publish manifest shortcuts and do other checks.
            rescanUpdatedPackagesLocked(userId,
                    0, // lastScanTime = 0; rescan all packages.
                    /* forceRescan= */ true);

            saveUserLocked(userId);
        }
    }

    // === Dump ===

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        enforceCallingOrSelfPermission(android.Manifest.permission.DUMP,
                "can't dump by this caller");
        boolean checkin = false;
        boolean clear = false;
        if (args != null) {
            for (String arg : args) {
                if ("-c".equals(arg)) {
                    checkin = true;
                } else if ("--checkin".equals(arg)) {
                    checkin = true;
                    clear = true;
                }
            }
        }

        if (checkin) {
            dumpCheckin(pw, clear);
        } else {
            dumpInner(pw);
        }
    }

    private void dumpInner(PrintWriter pw) {
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
            pw.print("    maxShortcutsPerActivity: ");
            pw.println(mMaxShortcuts);
            pw.println();

            pw.println("  Stats:");
            synchronized (mStatLock) {
                final String p = "    ";
                dumpStatLS(pw, p, Stats.GET_DEFAULT_HOME, "getHomeActivities()");
                dumpStatLS(pw, p, Stats.LAUNCHER_PERMISSION_CHECK, "Launcher permission check");

                dumpStatLS(pw, p, Stats.GET_PACKAGE_INFO, "getPackageInfo()");
                dumpStatLS(pw, p, Stats.GET_PACKAGE_INFO_WITH_SIG, "getPackageInfo(SIG)");
                dumpStatLS(pw, p, Stats.GET_APPLICATION_INFO, "getApplicationInfo");
                dumpStatLS(pw, p, Stats.CLEANUP_DANGLING_BITMAPS, "cleanupDanglingBitmaps");
                dumpStatLS(pw, p, Stats.GET_ACTIVITY_WITH_METADATA, "getActivity+metadata");
                dumpStatLS(pw, p, Stats.GET_INSTALLED_PACKAGES, "getInstalledPackages");
                dumpStatLS(pw, p, Stats.CHECK_PACKAGE_CHANGES, "checkPackageChanges");
                dumpStatLS(pw, p, Stats.GET_APPLICATION_RESOURCES, "getApplicationResources");
                dumpStatLS(pw, p, Stats.RESOURCE_NAME_LOOKUP, "resourceNameLookup");
                dumpStatLS(pw, p, Stats.GET_LAUNCHER_ACTIVITY, "getLauncherActivity");
                dumpStatLS(pw, p, Stats.CHECK_LAUNCHER_ACTIVITY, "checkLauncherActivity");
                dumpStatLS(pw, p, Stats.IS_ACTIVITY_ENABLED, "isActivityEnabled");
                dumpStatLS(pw, p, Stats.PACKAGE_UPDATE_CHECK, "packageUpdateCheck");
            }

            pw.println();
            pw.print("  #Failures: ");
            pw.println(mWtfCount);

            if (mLastWtfStacktrace != null) {
                pw.print("  Last failure stack trace: ");
                pw.println(Log.getStackTraceString(mLastWtfStacktrace));
            }

            for (int i = 0; i < mUsers.size(); i++) {
                pw.println();
                mUsers.valueAt(i).dump(pw, "  ");
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

    /**
     * Dumpsys for checkin.
     *
     * @param clear if true, clear the history information.  Some other system services have this
     * behavior but shortcut service doesn't for now.
     */
    private  void dumpCheckin(PrintWriter pw, boolean clear) {
        synchronized (mLock) {
            try {
                final JSONArray users = new JSONArray();

                for (int i = 0; i < mUsers.size(); i++) {
                    users.put(mUsers.valueAt(i).dumpCheckin(clear));
                }

                final JSONObject result = new JSONObject();

                result.put(KEY_SHORTCUT, users);
                result.put(KEY_LOW_RAM, injectIsLowRamDevice());
                result.put(KEY_ICON_SIZE, mMaxIconDimension);

                pw.println(result.toString(1));
            } catch (JSONException e) {
                Slog.e(TAG, "Unable to write in json", e);
            }
        }
    }

    // === Shell support ===

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ResultReceiver resultReceiver) throws RemoteException {

        enforceShell();

        final long token = injectClearCallingIdentity();
        try {
            final int status = (new MyShellCommand()).exec(this, in, out, err, args, resultReceiver);
            resultReceiver.send(status, null);
        } finally {
            injectRestoreCallingIdentity(token);
        }
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

        private void parseOptionsLocked(boolean takeUser)
                throws CommandException {
            String opt;
            while ((opt = getNextOption()) != null) {
                switch (opt) {
                    case "--user":
                        if (takeUser) {
                            mUserId = UserHandle.parseUserArg(getNextArgRequired());
                            if (!isUserUnlockedL(mUserId)) {
                                throw new CommandException(
                                        "User " + mUserId + " is not running or locked");
                            }
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
                    case "unload-user":
                        handleUnloadUser();
                        break;
                    case "clear-shortcuts":
                        handleClearShortcuts();
                        break;
                    case "verify-states": // hidden command to verify various internal states.
                        handleVerifyStates();
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
            pw.println("    Show the default launcher");
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
            synchronized (mLock) {
                parseOptionsLocked(/* takeUser =*/ true);

                Slog.i(TAG, "cmd: handleResetThrottling: user=" + mUserId);

                resetThrottlingInner(mUserId);
            }
        }

        private void handleResetAllThrottling() {
            Slog.i(TAG, "cmd: handleResetAllThrottling");

            resetAllThrottlingInner();
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
                getUserShortcutsLocked(mUserId).forceClearLauncher();
            }
        }

        private void showLauncher() {
            synchronized (mLock) {
                // This ensures to set the cached launcher.  Package name doesn't matter.
                hasShortcutHostPermissionInner("-", mUserId);

                getOutPrintWriter().println("Launcher: "
                        + getUserShortcutsLocked(mUserId).getLastKnownLauncher());
            }
        }

        private void handleClearDefaultLauncher() throws CommandException {
            synchronized (mLock) {
                parseOptionsLocked(/* takeUser =*/ true);

                clearLauncher();
            }
        }

        private void handleGetDefaultLauncher() throws CommandException {
            synchronized (mLock) {
                parseOptionsLocked(/* takeUser =*/ true);

                clearLauncher();
                showLauncher();
            }
        }

        private void handleUnloadUser() throws CommandException {
            synchronized (mLock) {
                parseOptionsLocked(/* takeUser =*/ true);

                Slog.i(TAG, "cmd: handleUnloadUser: user=" + mUserId);

                ShortcutService.this.handleCleanupUser(mUserId);
            }
        }

        private void handleClearShortcuts() throws CommandException {
            synchronized (mLock) {
                parseOptionsLocked(/* takeUser =*/ true);
                final String packageName = getNextArgRequired();

                Slog.i(TAG, "cmd: handleClearShortcuts: user" + mUserId + ", " + packageName);

                ShortcutService.this.cleanUpPackageForAllLoadedUsers(packageName, mUserId,
                        /* appStillExists = */ true);
            }
        }

        private void handleVerifyStates() throws CommandException {
            try {
                verifyStatesForce(); // This will throw when there's an issue.
            } catch (Throwable th) {
                throw new CommandException(th.getMessage() + "\n" + Log.getStackTraceString(th));
            }
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
        wtf(message, /* exception= */ null);
    }

    // Injection point.
    void wtf(String message, Throwable e) {
        if (e == null) {
            e = new RuntimeException("Stacktrace");
        }
        synchronized (mLock) {
            mWtfCount++;
            mLastWtfStacktrace = new Exception("Last failure was logged here:");
        }
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

    File getUserBitmapFilePath(@UserIdInt int userId) {
        return new File(injectUserDataPath(userId), DIRECTORY_BITMAPS);
    }

    @VisibleForTesting
    SparseArray<ShortcutUser> getShortcutsForTest() {
        return mUsers;
    }

    @VisibleForTesting
    int getMaxShortcutsForTest() {
        return mMaxShortcuts;
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
    ShortcutPackage getPackageShortcutForTest(String packageName, int userId) {
        synchronized (mLock) {
            final ShortcutUser user = mUsers.get(userId);
            if (user == null) return null;

            return user.getAllPackagesForTest().get(packageName);
        }
    }

    @VisibleForTesting
    ShortcutInfo getPackageShortcutForTest(String packageName, String shortcutId, int userId) {
        synchronized (mLock) {
            final ShortcutPackage pkg = getPackageShortcutForTest(packageName, userId);
            if (pkg == null) return null;

            return pkg.findShortcutById(shortcutId);
        }
    }

    /**
     * Control whether {@link #verifyStates} should be performed.  We always perform it during unit
     * tests.
     */
    @VisibleForTesting
    boolean injectShouldPerformVerification() {
        return DEBUG;
    }

    /**
     * Check various internal states and throws if there's any inconsistency.
     * This is normally only enabled during unit tests.
     */
    final void verifyStates() {
        if (injectShouldPerformVerification()) {
            verifyStatesInner();
        }
    }

    private final void verifyStatesForce() {
        verifyStatesInner();
    }

    private void verifyStatesInner() {
        synchronized (mLock) {
            forEachLoadedUserLocked(u -> u.forAllPackageItems(ShortcutPackageItem::verifyStates));
        }
    }
}
