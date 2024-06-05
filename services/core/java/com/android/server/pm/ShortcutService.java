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

import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED;
import static android.provider.DeviceConfig.NAMESPACE_SYSTEMUI;

import android.Manifest.permission;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.IUidObserver;
import android.app.IUriGrantsManager;
import android.app.UidObserver;
import android.app.UriGrantsManager;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.app.usage.UsageStatsManagerInternal;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.content.LocusId;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
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
import android.content.pm.ShortcutManager;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.ShortcutServiceInternal.ShortcutChangeListener;
import android.content.pm.UserPackage;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Icon;
import android.multiuser.Flags;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.text.format.TimeMigrationUtils;
import android.util.ArraySet;
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

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.util.StatLogger;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.uri.UriGrantsManagerInternal;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    static final boolean DEBUG_REBOOT = true;

    @VisibleForTesting
    static final long DEFAULT_RESET_INTERVAL_SEC = 24 * 60 * 60; // 1 day

    @VisibleForTesting
    static final int DEFAULT_MAX_UPDATES_PER_INTERVAL = 10;

    @VisibleForTesting
    static final int DEFAULT_MAX_SHORTCUTS_PER_ACTIVITY = 15;

    @VisibleForTesting
    static final int DEFAULT_MAX_SHORTCUTS_PER_APP = 100;

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
    static final String DIRECTORY_DUMP = "shortcut_dump";

    @VisibleForTesting
    static final String FILENAME_USER_PACKAGES = "shortcuts.xml";

    @VisibleForTesting
    static final String FILENAME_USER_PACKAGES_RESERVE_COPY =
            FILENAME_USER_PACKAGES + ".reservecopy";

    static final String DIRECTORY_BITMAPS = "bitmaps";

    private static final String TAG_ROOT = "root";
    private static final String TAG_LAST_RESET_TIME = "last_reset_time";

    private static final String ATTR_VALUE = "value";

    private static final String LAUNCHER_INTENT_CATEGORY = Intent.CATEGORY_LAUNCHER;

    private static final String KEY_SHORTCUT = "shortcut";
    private static final String KEY_LOW_RAM = "lowRam";
    private static final String KEY_ICON_SIZE = "iconSize";

    private static final String DUMMY_MAIN_ACTIVITY = "android.__dummy__";

    private static final long CALLBACK_DELAY = 100L;

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
         * Key name for the max shortcuts can be retained in system ram per app. (int)
         */
        String KEY_MAX_SHORTCUTS_PER_APP = "max_shortcuts_per_app";

        /**
         * Key name for icon compression quality, 0-100.
         */
        String KEY_ICON_QUALITY = "icon_quality";

        /**
         * Key name for icon compression format: "PNG", "JPEG" or "WEBP"
         */
        String KEY_ICON_FORMAT = "icon_format";
    }

    private static final int PACKAGE_MATCH_FLAGS =
            PackageManager.MATCH_DIRECT_BOOT_AWARE
                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                    | PackageManager.MATCH_UNINSTALLED_PACKAGES
                    | PackageManager.MATCH_DISABLED_COMPONENTS;

    private static final int SYSTEM_APP_MASK =
            ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;

    final Context mContext;

    private final Object mServiceLock = new Object();
    private final Object mNonPersistentUsersLock = new Object();
    private final Object mWtfLock = new Object();

    private static final List<ResolveInfo> EMPTY_RESOLVE_INFO = new ArrayList<>(0);

    // Temporarily reverted to anonymous inner class form due to: b/32554459
    private static final Predicate<ResolveInfo> ACTIVITY_NOT_EXPORTED =
            new Predicate<ResolveInfo>() {
                public boolean test(ResolveInfo ri) {
                    return !ri.activityInfo.exported;
                }
            };

    private static final Predicate<ResolveInfo> ACTIVITY_NOT_INSTALLED = (ri) ->
            !isInstalled(ri.activityInfo);

    // Temporarily reverted to anonymous inner class form due to: b/32554459
    private static final Predicate<PackageInfo> PACKAGE_NOT_INSTALLED =
            new Predicate<PackageInfo>() {
                public boolean test(PackageInfo pi) {
                    return !isInstalled(pi);
                }
            };

    private final Handler mHandler;

    @GuardedBy("itself")
    private final ArrayList<ShortcutChangeListener> mListeners = new ArrayList<>(1);

    @GuardedBy("itself")
    private final ArrayList<LauncherApps.ShortcutChangeCallback> mShortcutChangeCallbacks =
            new ArrayList<>(1);

    private final AtomicLong mRawLastResetTime = new AtomicLong(0);

    /**
     * User ID -> UserShortcuts
     */
    @GuardedBy("mServiceLock")
    private final SparseArray<ShortcutUser> mUsers = new SparseArray<>();

    /**
     * User ID -> ShortcutNonPersistentUser
     *
     * Note we use a fine-grained lock for {@link #mShortcutNonPersistentUsers} due to b/183618378.
     */
    @GuardedBy("mNonPersistentUsersLock")
    private final SparseArray<ShortcutNonPersistentUser> mShortcutNonPersistentUsers =
            new SparseArray<>();

    /**
     * Max number of dynamic + manifest shortcuts that each activity can have at a time.
     */
    private int mMaxShortcuts;

    /**
     * Max number of shortcuts that can exists in system ram for each application.
     */
    private int mMaxShortcutsPerApp;

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

    int mSaveDelayMillis;

    private final IPackageManager mIPackageManager;
    private final PackageManagerInternal mPackageManagerInternal;
    final UserManagerInternal mUserManagerInternal;
    private final UsageStatsManagerInternal mUsageStatsManagerInternal;
    private final ActivityManagerInternal mActivityManagerInternal;
    private final IUriGrantsManager mUriGrantsManager;
    private final UriGrantsManagerInternal mUriGrantsManagerInternal;
    private final IBinder mUriPermissionOwner;
    private final RoleManager mRoleManager;

    private final ShortcutRequestPinProcessor mShortcutRequestPinProcessor;
    private final ShortcutDumpFiles mShortcutDumpFiles;

    @GuardedBy("mServiceLock")
    final SparseIntArray mUidState = new SparseIntArray();

    @GuardedBy("mServiceLock")
    final SparseLongArray mUidLastForegroundElapsedTime = new SparseLongArray();

    @GuardedBy("mServiceLock")
    private List<Integer> mDirtyUserIds = new ArrayList<>();

    private final AtomicBoolean mBootCompleted = new AtomicBoolean();
    private final AtomicBoolean mShutdown = new AtomicBoolean();

    /**
     * Note we use a fine-grained lock for {@link #mUnlockedUsers} due to b/64303666.
     */
    @GuardedBy("mUnlockedUsers")
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
        int ASYNC_PRELOAD_USER_DELAY = 15;
        int GET_DEFAULT_LAUNCHER = 16;

        int COUNT = GET_DEFAULT_LAUNCHER + 1;
    }

    private final StatLogger mStatLogger = new StatLogger(new String[] {
            "getHomeActivities()",
            "Launcher permission check",
            "getPackageInfo()",
            "getPackageInfo(SIG)",
            "getApplicationInfo",
            "cleanupDanglingBitmaps",
            "getActivity+metadata",
            "getInstalledPackages",
            "checkPackageChanges",
            "getApplicationResources",
            "resourceNameLookup",
            "getLauncherActivity",
            "checkLauncherActivity",
            "isActivityEnabled",
            "packageUpdateCheck",
            "asyncPreloadUserDelay",
            "getDefaultLauncher()"
    });

    private static final int PROCESS_STATE_FOREGROUND_THRESHOLD =
            ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;

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

    @GuardedBy("mWtfLock")
    private int mWtfCount = 0;

    @GuardedBy("mWtfLock")
    private Exception mLastWtfStacktrace;

    @GuardedBy("mServiceLock")
    private final MetricsLogger mMetricsLogger = new MetricsLogger();

    private final boolean mIsAppSearchEnabled;

    private ComponentName mChooserActivity;

    static class InvalidFileFormatException extends Exception {
        public InvalidFileFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public ShortcutService(Context context) {
        this(context, getBgLooper(), /*onyForPackgeManagerApis*/ false);
    }

    private static Looper getBgLooper() {
        final HandlerThread handlerThread = new HandlerThread("shortcut",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        handlerThread.start();
        return handlerThread.getLooper();
    }

    @VisibleForTesting
    ShortcutService(Context context, Looper looper, boolean onlyForPackageManagerApis) {
        mContext = Objects.requireNonNull(context);
        LocalServices.addService(ShortcutServiceInternal.class, new LocalService());
        mHandler = new Handler(looper);
        mIPackageManager = AppGlobals.getPackageManager();
        mPackageManagerInternal = Objects.requireNonNull(
                LocalServices.getService(PackageManagerInternal.class));
        mUserManagerInternal = Objects.requireNonNull(
                LocalServices.getService(UserManagerInternal.class));
        mUsageStatsManagerInternal = Objects.requireNonNull(
                LocalServices.getService(UsageStatsManagerInternal.class));
        mActivityManagerInternal = Objects.requireNonNull(
                LocalServices.getService(ActivityManagerInternal.class));

        mUriGrantsManager = Objects.requireNonNull(UriGrantsManager.getService());
        mUriGrantsManagerInternal = Objects.requireNonNull(
                LocalServices.getService(UriGrantsManagerInternal.class));
        mUriPermissionOwner = mUriGrantsManagerInternal.newUriPermissionOwner(TAG);
        mRoleManager = Objects.requireNonNull(mContext.getSystemService(RoleManager.class));

        mShortcutRequestPinProcessor = new ShortcutRequestPinProcessor(this, mServiceLock);
        mShortcutDumpFiles = new ShortcutDumpFiles(this);
        mIsAppSearchEnabled = DeviceConfig.getBoolean(NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.SHORTCUT_APPSEARCH_INTEGRATION, false)
                && !injectIsLowRamDevice();

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

        final IntentFilter localeFilter = new IntentFilter();
        localeFilter.addAction(Intent.ACTION_LOCALE_CHANGED);
        localeFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiverAsUser(mReceiver, UserHandle.ALL,
                localeFilter, null, mHandler);

        IntentFilter shutdownFilter = new IntentFilter();
        shutdownFilter.addAction(Intent.ACTION_SHUTDOWN);
        shutdownFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiverAsUser(mShutdownReceiver, UserHandle.SYSTEM,
                shutdownFilter, null, mHandler);

        injectRegisterUidObserver(mUidObserver, ActivityManager.UID_OBSERVER_PROCSTATE
                | ActivityManager.UID_OBSERVER_GONE);

        injectRegisterRoleHoldersListener(mOnRoleHoldersChangedListener);
    }

    boolean isAppSearchEnabled() {
        return mIsAppSearchEnabled;
    }

    long getStatStartTime() {
        return mStatLogger.getTime();
    }

    void logDurationStat(int statId, long start) {
        mStatLogger.logDurationStat(statId, start);
    }

    public String injectGetLocaleTagsForUser(@UserIdInt int userId) {
        // TODO This should get the per-user locale.  b/30123329 b/30119489
        return LocaleList.getDefault().toLanguageTags();
    }

    private final OnRoleHoldersChangedListener mOnRoleHoldersChangedListener =
            new OnRoleHoldersChangedListener() {
        @Override
        public void onRoleHoldersChanged(String roleName, UserHandle user) {
            if (RoleManager.ROLE_HOME.equals(roleName)) {
                injectPostToHandler(() -> handleOnDefaultLauncherChanged(user.getIdentifier()));
            }
        }
    };

    void handleOnDefaultLauncherChanged(int userId) {
        if (DEBUG) {
            Slog.v(TAG, "Default launcher changed for user: " + userId);
        }

        // Default launcher is removed or changed, revoke all URI permissions.
        mUriGrantsManagerInternal.revokeUriPermissionFromOwner(mUriPermissionOwner, null, ~0, 0);

        synchronized (mServiceLock) {
            // Clear the launcher cache for this user. It will be set again next time the default
            // launcher is read from RoleManager.
            if (isUserLoadedLocked(userId)) {
                getUserShortcutsLocked(userId).setCachedLauncher(null);
            }
        }
    }

    final private IUidObserver mUidObserver = new UidObserver() {
        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq, int capability) {
            injectPostToHandler(() -> handleOnUidStateChanged(uid, procState));
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            injectPostToHandler(() ->
                    handleOnUidStateChanged(uid, ActivityManager.PROCESS_STATE_NONEXISTENT));
        }
    };

    void handleOnUidStateChanged(int uid, int procState) {
        if (DEBUG_PROCSTATE) {
            Slog.d(TAG, "onUidStateChanged: uid=" + uid + " state=" + procState);
        }
        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "shortcutHandleOnUidStateChanged");
        synchronized (mServiceLock) {
            mUidState.put(uid, procState);

            // We need to keep track of last time an app comes to foreground.
            // See ShortcutPackage.getApiCallCount() for how it's used.
            // It doesn't have to be persisted, but it needs to be the elapsed time.
            if (isProcessStateForeground(procState)) {
                mUidLastForegroundElapsedTime.put(uid, injectElapsedRealtime());
            }
        }
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
    }

    private boolean isProcessStateForeground(int processState) {
        return processState <= PROCESS_STATE_FOREGROUND_THRESHOLD;
    }

    @GuardedBy("mServiceLock")
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

    @GuardedBy("mServiceLock")
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
            if (DEBUG) {
                Binder.LOG_RUNTIME_EXCEPTION = true;
            }
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
        public void onUserStopping(@NonNull TargetUser user) {
            mService.handleStopUser(user.getUserIdentifier());
        }

        @Override
        public void onUserUnlocking(@NonNull TargetUser user) {
            mService.handleUnlockUser(user.getUserIdentifier());
        }
    }

    /** lifecycle event */
    void onBootPhase(int phase) {
        if (DEBUG || DEBUG_REBOOT) {
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
        if (DEBUG || DEBUG_REBOOT) {
            Slog.d(TAG, "handleUnlockUser: user=" + userId);
        }
        synchronized (mUnlockedUsers) {
            mUnlockedUsers.put(userId, true);
        }

        // Preload the user data.
        // Note, we don't use mHandler here but instead just start a new thread.
        // This is because mHandler (which uses com.android.internal.os.BackgroundThread) is very
        // busy at this point and this could take hundreds of milliseconds, which would be too
        // late since the launcher would already have started.
        // So we just create a new thread.  This code runs rarely, so we don't use a thread pool
        // or anything.
        final long start = getStatStartTime();
        injectRunOnNewThread(() -> {
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "shortcutHandleUnlockUser");
            synchronized (mServiceLock) {
                logDurationStat(Stats.ASYNC_PRELOAD_USER_DELAY, start);
                getUserShortcutsLocked(userId);
            }
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        });
    }

    /** lifecycle event */
    void handleStopUser(int userId) {
        if (DEBUG || DEBUG_REBOOT) {
            Slog.d(TAG, "handleStopUser: user=" + userId);
        }
        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "shortcutHandleStopUser");
        synchronized (mServiceLock) {
            unloadUserLocked(userId);

            synchronized (mUnlockedUsers) {
                mUnlockedUsers.put(userId, false);
            }
        }
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
    }

    @GuardedBy("mServiceLock")
    private void unloadUserLocked(int userId) {
        if (DEBUG || DEBUG_REBOOT) {
            Slog.d(TAG, "unloadUserLocked: user=" + userId);
        }
        // Cancel any ongoing background tasks.
        getUserShortcutsLocked(userId).cancelAllInFlightTasks();

        // Save all dirty information.
        saveDirtyInfo();

        // Unload
        mUsers.delete(userId);
    }

    /** Return the base state file name */
    final ResilientAtomicFile getBaseStateFile() {
        File mainFile = new File(injectSystemDataPath(), FILENAME_BASE_STATE);
        File temporaryBackup = new File(injectSystemDataPath(),
                FILENAME_BASE_STATE + ".backup");
        File reserveCopy = new File(injectSystemDataPath(),
                FILENAME_BASE_STATE + ".reservecopy");
        int fileMode = FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IXOTH;
        return new ResilientAtomicFile(mainFile, temporaryBackup, reserveCopy, fileMode,
                "base shortcut", null);
    }

    /**
     * Init the instance. (load the state file, etc)
     */
    private void initialize() {
        synchronized (mServiceLock) {
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
                ConfigConstants.KEY_MAX_SHORTCUTS, DEFAULT_MAX_SHORTCUTS_PER_ACTIVITY));

        mMaxShortcutsPerApp = Math.max(0, (int) parser.getLong(
                ConfigConstants.KEY_MAX_SHORTCUTS_PER_APP, DEFAULT_MAX_SHORTCUTS_PER_APP));

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
    static String parseStringAttribute(TypedXmlPullParser parser, String attribute) {
        return parser.getAttributeValue(null, attribute);
    }

    static boolean parseBooleanAttribute(TypedXmlPullParser parser, String attribute) {
        return parseLongAttribute(parser, attribute) == 1;
    }

    static boolean parseBooleanAttribute(TypedXmlPullParser parser, String attribute, boolean def) {
        return parseLongAttribute(parser, attribute, (def ? 1 : 0)) == 1;
    }

    static int parseIntAttribute(TypedXmlPullParser parser, String attribute) {
        return (int) parseLongAttribute(parser, attribute);
    }

    static int parseIntAttribute(TypedXmlPullParser parser, String attribute, int def) {
        return (int) parseLongAttribute(parser, attribute, def);
    }

    static long parseLongAttribute(TypedXmlPullParser parser, String attribute) {
        return parseLongAttribute(parser, attribute, 0);
    }

    static long parseLongAttribute(TypedXmlPullParser parser, String attribute, long def) {
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
    static ComponentName parseComponentNameAttribute(TypedXmlPullParser parser, String attribute) {
        final String value = parseStringAttribute(parser, attribute);
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        return ComponentName.unflattenFromString(value);
    }

    @Nullable
    static Intent parseIntentAttributeNoDefault(TypedXmlPullParser parser, String attribute) {
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
    static Intent parseIntentAttribute(TypedXmlPullParser parser, String attribute) {
        Intent parsed = parseIntentAttributeNoDefault(parser, attribute);
        if (parsed == null) {
            // Default intent.
            parsed = new Intent(Intent.ACTION_VIEW);
        }
        return parsed;
    }

    static void writeTagValue(TypedXmlSerializer out, String tag, String value) throws IOException {
        if (TextUtils.isEmpty(value)) return;

        out.startTag(null, tag);
        out.attribute(null, ATTR_VALUE, value);
        out.endTag(null, tag);
    }

    static void writeTagValue(TypedXmlSerializer out, String tag, long value) throws IOException {
        writeTagValue(out, tag, Long.toString(value));
    }

    static void writeTagValue(TypedXmlSerializer out, String tag, ComponentName name)
            throws IOException {
        if (name == null) return;
        writeTagValue(out, tag, name.flattenToString());
    }

    static void writeTagExtra(TypedXmlSerializer out, String tag, PersistableBundle bundle)
            throws IOException, XmlPullParserException {
        if (bundle == null) return;

        out.startTag(null, tag);
        bundle.saveToXml(out);
        out.endTag(null, tag);
    }

    static void writeAttr(TypedXmlSerializer out, String name, CharSequence value)
            throws IOException {
        if (TextUtils.isEmpty(value)) return;

        out.attribute(null, name, value.toString());
    }

    static void writeAttr(TypedXmlSerializer out, String name, long value) throws IOException {
        writeAttr(out, name, String.valueOf(value));
    }

    static void writeAttr(TypedXmlSerializer out, String name, boolean value) throws IOException {
        if (value) {
            writeAttr(out, name, "1");
        } else {
            writeAttr(out, name, "0");
        }
    }

    static void writeAttr(TypedXmlSerializer out, String name, ComponentName comp)
            throws IOException {
        if (comp == null) return;
        writeAttr(out, name, comp.flattenToString());
    }

    static void writeAttr(TypedXmlSerializer out, String name, Intent intent) throws IOException {
        if (intent == null) return;

        writeAttr(out, name, intent.toUri(/* flags =*/ 0));
    }

    @VisibleForTesting
    void saveBaseState() {
        try (ResilientAtomicFile file = getBaseStateFile()) {
            if (DEBUG || DEBUG_REBOOT) {
                Slog.d(TAG, "Saving to " + file.getBaseFile());
            }

            FileOutputStream outs = null;
            try {
                synchronized (mServiceLock) {
                    outs = file.startWrite();
                }

                // Write to XML
                TypedXmlSerializer out = Xml.resolveSerializer(outs);
                out.startDocument(null, true);
                out.startTag(null, TAG_ROOT);

                // Body.
                // No locking required. Ok to add lock later if we save more data.
                writeTagValue(out, TAG_LAST_RESET_TIME, mRawLastResetTime.get());

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
    }

    @GuardedBy("mServiceLock")
    private void loadBaseStateLocked() {
        mRawLastResetTime.set(0);

        try (ResilientAtomicFile file = getBaseStateFile()) {
            if (DEBUG || DEBUG_REBOOT) {
                Slog.d(TAG, "Loading from " + file.getBaseFile());
            }
            FileInputStream in = null;
            try {
                in = file.openRead();
                if (in == null) {
                    throw new FileNotFoundException(file.getBaseFile().getAbsolutePath());
                }

                TypedXmlPullParser parser = Xml.resolvePullParser(in);

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
                            mRawLastResetTime.set(parseLongAttribute(parser, ATTR_VALUE));
                            break;
                        default:
                            Slog.e(TAG, "Invalid tag: " + tag);
                            break;
                    }
                }
            } catch (FileNotFoundException e) {
                // Use the default
            } catch (IOException | XmlPullParserException e) {
                // Remove corrupted file and retry.
                file.failRead(in, e);
                loadBaseStateLocked();
                return;
            }
        }
        // Adjust the last reset time.
        getLastResetTimeLocked();
    }

    @VisibleForTesting
    final ResilientAtomicFile getUserFile(@UserIdInt int userId) {
        File mainFile = new File(injectUserDataPath(userId), FILENAME_USER_PACKAGES);
        File temporaryBackup = new File(injectUserDataPath(userId),
                FILENAME_USER_PACKAGES + ".backup");
        File reserveCopy = new File(injectUserDataPath(userId),
                FILENAME_USER_PACKAGES_RESERVE_COPY);
        int fileMode = FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IXOTH;
        return new ResilientAtomicFile(mainFile, temporaryBackup, reserveCopy, fileMode,
                "user shortcut", null);
    }

    private void saveUser(@UserIdInt int userId) {
        try (ResilientAtomicFile file = getUserFile(userId)) {
            FileOutputStream os = null;
            try {
                if (DEBUG || DEBUG_REBOOT) {
                    Slog.d(TAG, "Saving to " + file);
                }

                synchronized (mServiceLock) {
                    os = file.startWrite();
                    saveUserInternalLocked(userId, os, /* forBackup= */ false);
                }

                file.finishWrite(os);

                // Remove all dangling bitmap files.
                cleanupDanglingBitmapDirectoriesLocked(userId);
            } catch (XmlPullParserException | IOException e) {
                Slog.e(TAG, "Failed to write to file " + file, e);
                file.failWrite(os);
            }
        }

        getUserShortcutsLocked(userId).logSharingShortcutStats(mMetricsLogger);
    }

    @GuardedBy("mServiceLock")
    private void saveUserInternalLocked(@UserIdInt int userId, OutputStream os,
            boolean forBackup) throws IOException, XmlPullParserException {

        // Write to XML
        final TypedXmlSerializer out;
        if (forBackup) {
            out = Xml.newFastSerializer();
            out.setOutput(os, StandardCharsets.UTF_8.name());
        } else {
            out = Xml.resolveSerializer(os);
        }
        out.startDocument(null, true);

        getUserShortcutsLocked(userId).saveToXml(out, forBackup);

        out.endDocument();

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
        try (ResilientAtomicFile file = getUserFile(userId)) {
            FileInputStream in = null;
            try {
                if (DEBUG || DEBUG_REBOOT) {
                    Slog.d(TAG, "Loading from " + file);
                }
                in = file.openRead();
                if (in == null) {
                    if (DEBUG || DEBUG_REBOOT) {
                        Slog.d(TAG, "Not found " + file);
                    }
                    return null;
                }
                return loadUserInternal(userId, in, /* forBackup= */ false);
            } catch (Exception e) {
                // Remove corrupted file and retry.
                file.failRead(in, e);
                return loadUserLocked(userId);
            }
        }
    }

    private ShortcutUser loadUserInternal(@UserIdInt int userId, InputStream is,
            boolean fromBackup) throws XmlPullParserException, IOException,
            InvalidFileFormatException {

        ShortcutUser ret = null;
        TypedXmlPullParser parser;
        if (fromBackup) {
            parser = Xml.newFastPullParser();
            parser.setInput(is, StandardCharsets.UTF_8.name());
        } else {
            parser = Xml.resolvePullParser(is);
        }

        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            final int depth = parser.getDepth();

            final String tag = parser.getName();
            if (DEBUG_LOAD || DEBUG_REBOOT) {
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
        if (DEBUG || DEBUG_REBOOT) {
            Slog.d(TAG, "Scheduling to save for " + userId);
        }
        synchronized (mServiceLock) {
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
        if (DEBUG || DEBUG_REBOOT) {
            Slog.d(TAG, "saveDirtyInfo");
        }
        if (mShutdown.get()) {
            return;
        }
        try {
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "shortcutSaveDirtyInfo");
            List<Integer> dirtyUserIds = new ArrayList<>();
            synchronized (mServiceLock) {
                List<Integer> tmp = mDirtyUserIds;
                mDirtyUserIds = dirtyUserIds;
                dirtyUserIds = tmp;
            }
            for (int i = dirtyUserIds.size() - 1; i >= 0; i--) {
                final int userId = dirtyUserIds.get(i);
                if (userId == UserHandle.USER_NULL) { // USER_NULL for base state.
                    saveBaseState();
                } else {
                    saveUser(userId);
                }
            }
        } catch (Exception e) {
            wtf("Exception in saveDirtyInfo", e);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }
    }

    /** Return the last reset time. */
    @GuardedBy("mServiceLock")
    long getLastResetTimeLocked() {
        updateTimesLocked();
        return mRawLastResetTime.get();
    }

    /** Return the next reset time. */
    @GuardedBy("mServiceLock")
    long getNextResetTimeLocked() {
        updateTimesLocked();
        return mRawLastResetTime.get() + mResetInterval;
    }

    static boolean isClockValid(long time) {
        return time >= 1420070400; // Thu, 01 Jan 2015 00:00:00 GMT
    }

    /**
     * Update the last reset time.
     */
    @GuardedBy("mServiceLock")
    private void updateTimesLocked() {

        final long now = injectCurrentTimeMillis();

        final long prevLastResetTime = mRawLastResetTime.get();
        long newLastResetTime = prevLastResetTime;

        if (newLastResetTime == 0) { // first launch.
            // TODO Randomize??
            newLastResetTime = now;
        } else if (now < newLastResetTime) {
            // Clock rewound.
            if (isClockValid(now)) {
                Slog.w(TAG, "Clock rewound");
                // TODO Randomize??
                newLastResetTime = now;
            }
        } else if ((newLastResetTime + mResetInterval) <= now) {
            final long offset = newLastResetTime % mResetInterval;
            newLastResetTime = ((now / mResetInterval) * mResetInterval) + offset;
        }

        mRawLastResetTime.set(newLastResetTime);
        if (prevLastResetTime != newLastResetTime) {
            scheduleSaveBaseState();
        }
    }

    // Requires mServiceLock held, but "Locked" prefix would look weird so we just say "L".
    protected boolean isUserUnlockedL(@UserIdInt int userId) {
        // First, check the local copy.
        synchronized (mUnlockedUsers) {
            if (mUnlockedUsers.get(userId)) {
                return true;
            }
        }

        // If the local copy says the user is locked, check with AM for the actual state, since
        // the user might just have been unlocked.
        // Note we just don't use isUserUnlockingOrUnlocked() here, because it'll return false
        // when the user is STOPPING, which we still want to consider as "unlocked".
        return mUserManagerInternal.isUserUnlockingOrUnlocked(userId);
    }

    // Requires mServiceLock held, but "Locked" prefix would look weird so we just say "L".
    void throwIfUserLockedL(@UserIdInt int userId) {
        if (!isUserUnlockedL(userId)) {
            throw new IllegalStateException("User " + userId + " is locked or not running");
        }
    }

    @GuardedBy("mServiceLock")
    @NonNull
    private boolean isUserLoadedLocked(@UserIdInt int userId) {
        return mUsers.get(userId) != null;
    }

    private int mLastLockedUser = -1;

    /** Return the per-user state. */
    @GuardedBy("mServiceLock")
    @NonNull
    ShortcutUser getUserShortcutsLocked(@UserIdInt int userId) {
        if (!isUserUnlockedL(userId)) {
            // Only do wtf once for each user. (until the user is unlocked)
            if (userId != mLastLockedUser) {
                wtf("User still locked");
                mLastLockedUser = userId;
            }
        } else {
            mLastLockedUser = -1;
        }

        ShortcutUser userPackages = mUsers.get(userId);
        if (userPackages == null) {
            userPackages = loadUserLocked(userId);
            if (userPackages == null) {
                userPackages = new ShortcutUser(this, userId);
            }
            mUsers.put(userId, userPackages);

            // Also when a user's data is first accessed, scan all packages.
            checkPackageChanges(userId);
        }
        return userPackages;
    }

    /** Return the non-persistent per-user state. */
    @GuardedBy("mNonPersistentUsersLock")
    @NonNull
    ShortcutNonPersistentUser getNonPersistentUserLocked(@UserIdInt int userId) {
        ShortcutNonPersistentUser ret = mShortcutNonPersistentUsers.get(userId);
        if (ret == null) {
            ret = new ShortcutNonPersistentUser(userId);
            mShortcutNonPersistentUsers.put(userId, ret);
        }
        return ret;
    }

    @GuardedBy("mServiceLock")
    void forEachLoadedUserLocked(@NonNull Consumer<ShortcutUser> c) {
        for (int i = mUsers.size() - 1; i >= 0; i--) {
            c.accept(mUsers.valueAt(i));
        }
    }

    /**
     * Return the per-user per-package state.  If the caller is a publisher, use
     * {@link #getPackageShortcutsForPublisherLocked} instead.
     */
    @GuardedBy("mServiceLock")
    @NonNull
    ShortcutPackage getPackageShortcutsLocked(
            @NonNull String packageName, @UserIdInt int userId) {
        return getUserShortcutsLocked(userId).getPackageShortcuts(packageName);
    }

    /** Return the per-user per-package state.  Use this when the caller is a publisher. */
    @GuardedBy("mServiceLock")
    @NonNull
    ShortcutPackage getPackageShortcutsForPublisherLocked(
            @NonNull String packageName, @UserIdInt int userId) {
        final ShortcutPackage ret = getUserShortcutsLocked(userId).getPackageShortcuts(packageName);
        ret.getUser().onCalledByPublisher(packageName);
        return ret;
    }

    @GuardedBy("mServiceLock")
    @NonNull
    ShortcutLauncher getLauncherShortcutsLocked(
            @NonNull String packageName, @UserIdInt int ownerUserId,
            @UserIdInt int launcherUserId) {
        return getUserShortcutsLocked(ownerUserId)
                .getLauncherShortcuts(packageName, launcherUserId);
    }

    // === Caller validation ===

    public void cleanupBitmapsForPackage(@UserIdInt int userId, String packageName) {
        final File packagePath = new File(getUserBitmapFilePath(userId), packageName);
        if (!packagePath.isDirectory()) {
            return;
        }
        // ShortcutPackage is already removed at this point, we can safely remove the folder.
        if (!(FileUtils.deleteContents(packagePath) && packagePath.delete())) {
            Slog.w(TAG, "Unable to remove directory " + packagePath);
        }
    }

    /**
     * Remove dangling bitmap files for a user.
     *
     * Note this method must be called with the lock held after calling
     * {@link ShortcutBitmapSaver#waitForAllSavesLocked()} to make sure there's no pending bitmap
     * saves are going on.
     */
    @GuardedBy("mServiceLock")
    private void cleanupDanglingBitmapDirectoriesLocked(@UserIdInt int userId) {
        if (DEBUG) {
            Slog.d(TAG, "cleanupDanglingBitmaps: userId=" + userId);
        }
        final long start = getStatStartTime();

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
                user.getPackageShortcuts(packageName).cleanupDanglingBitmapFiles(child);
            }
        }
        logDurationStat(Stats.CLEANUP_DANGLING_BITMAPS, start);
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

    void saveIconAndFixUpShortcutLocked(ShortcutPackage p, ShortcutInfo shortcut) {
        if (shortcut.hasIconFile() || shortcut.hasIconResource() || shortcut.hasIconUri()) {
            return;
        }

        final long token = injectClearCallingIdentity();
        try {
            // Clear icon info on the shortcut.
            p.removeIcon(shortcut);

            final Icon icon = shortcut.getIcon();
            if (icon == null) {
                return; // has no icon
            }
            int maxIconDimension = mMaxIconDimension;
            Bitmap bitmap;
            try {
                switch (icon.getType()) {
                    case Icon.TYPE_RESOURCE: {
                        injectValidateIconResPackage(shortcut, icon);

                        shortcut.setIconResourceId(icon.getResId());
                        shortcut.addFlags(ShortcutInfo.FLAG_HAS_ICON_RES);
                        return;
                    }
                    case Icon.TYPE_URI:
                        shortcut.setIconUri(icon.getUriString());
                        shortcut.addFlags(ShortcutInfo.FLAG_HAS_ICON_URI);
                        return;
                    case Icon.TYPE_URI_ADAPTIVE_BITMAP:
                        shortcut.setIconUri(icon.getUriString());
                        shortcut.addFlags(ShortcutInfo.FLAG_HAS_ICON_URI
                                | ShortcutInfo.FLAG_ADAPTIVE_BITMAP);
                        return;
                    case Icon.TYPE_BITMAP:
                        bitmap = icon.getBitmap(); // Don't recycle in this case.
                        break;
                    case Icon.TYPE_ADAPTIVE_BITMAP: {
                        bitmap = icon.getBitmap(); // Don't recycle in this case.
                        maxIconDimension *= (1 + 2 * AdaptiveIconDrawable.getExtraInsetFraction());
                        break;
                    }
                    default:
                        // This shouldn't happen because we've already validated the icon, but
                        // just in case.
                        throw ShortcutInfo.getInvalidIconException();
                }
                p.saveBitmap(shortcut, maxIconDimension, mIconPersistFormat, mIconPersistQuality);
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
            final long start = getStatStartTime();
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

    @VisibleForTesting
    ComponentName injectChooserActivity() {
        if (mChooserActivity == null) {
            mChooserActivity = ComponentName.unflattenFromString(
                    mContext.getResources().getString(R.string.config_chooserActivity));
        }
        return mChooserActivity;
    }

    private boolean isCallerChooserActivity() {
        // TODO(b/228975502): Migrate this check to a proper permission or role check
        final int callingUid = injectBinderCallingUid();
        ComponentName systemChooser = injectChooserActivity();
        if (systemChooser == null) {
            return false;
        }
        int uid = injectGetPackageUid(systemChooser.getPackageName(), UserHandle.USER_SYSTEM);
        return UserHandle.getAppId(uid) == UserHandle.getAppId(callingUid);
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

    private void verifyCallerUserId(@UserIdInt int userId) {
        if (isCallerSystem()) {
            return; // no check
        }

        final int callingUid = injectBinderCallingUid();

        // Otherwise, make sure the arguments are valid.
        if (UserHandle.getUserId(callingUid) != userId) {
            throw new SecurityException("Invalid user-ID");
        }
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
        if (injectGetPackageUid(packageName, userId) != callingUid) {
            throw new SecurityException("Calling package name mismatch");
        }
        Preconditions.checkState(!isEphemeralApp(packageName, userId),
                "Ephemeral apps can't use ShortcutManager");
    }

    private void verifyShortcutInfoPackage(String callerPackage, ShortcutInfo si) {
        if (si == null) {
            return;
        }

        if (!Objects.equals(callerPackage, si.getPackage())) {
            android.util.EventLog.writeEvent(0x534e4554, "109824443", -1, "");
            throw new SecurityException("Shortcut package name mismatch");
        }
        final int callingUid = injectBinderCallingUid();
        if (UserHandle.getUserId(callingUid) != si.getUserId()) {
            throw new SecurityException("User-ID in shortcut doesn't match the caller");
        }
    }

    private void verifyShortcutInfoPackages(
            String callerPackage, List<ShortcutInfo> list) {
        final int size = list.size();
        for (int i = 0; i < size; i++) {
            verifyShortcutInfoPackage(callerPackage, list.get(i));
        }
    }

    // Overridden in unit tests to execute r synchronously.
    void injectPostToHandler(Runnable r) {
        mHandler.post(r);
    }

    void injectRunOnNewThread(Runnable r) {
        new Thread(r).start();
    }

    void injectPostToHandlerDebounced(@NonNull final Object token, @NonNull final Runnable r) {
        Objects.requireNonNull(token);
        Objects.requireNonNull(r);
        synchronized (mServiceLock) {
            mHandler.removeCallbacksAndMessages(token);
            mHandler.postDelayed(r, token, CALLBACK_DELAY);
        }
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
     * Return the max number of shortcuts can be retaiend in system ram for each application.
     */
    int getMaxAppShortcuts() {
        return mMaxShortcutsPerApp;
    }

    /**
     * - Sends a notification to LauncherApps
     * - Write to file
     */
    void packageShortcutsChanged(
            @NonNull final ShortcutPackage sp,
            @Nullable final List<ShortcutInfo> changedShortcuts,
            @Nullable final List<ShortcutInfo> removedShortcuts) {
        Objects.requireNonNull(sp);
        final String packageName = sp.getPackageName();
        final int userId = sp.getPackageUserId();
        if (DEBUG) {
            Slog.d(TAG, String.format(
                    "Shortcut changes: package=%s, user=%d", packageName, userId));
        }
        injectPostToHandlerDebounced(sp, notifyListenerRunnable(packageName, userId));
        notifyShortcutChangeCallbacks(packageName, userId, changedShortcuts, removedShortcuts);
        sp.scheduleSave();
    }

    private void notifyListeners(@NonNull final String packageName, @UserIdInt final int userId) {
        if (DEBUG) {
            Slog.d(TAG, String.format(
                    "Shortcut changes: package=%s, user=%d", packageName, userId));
        }
        injectPostToHandler(notifyListenerRunnable(packageName, userId));
    }

    private Runnable notifyListenerRunnable(@NonNull final String packageName,
            @UserIdInt final int userId) {
        return () -> {
            try {
                final ArrayList<ShortcutChangeListener> copy;
                synchronized (mServiceLock) {
                    if (!isUserUnlockedL(userId)) {
                        return;
                    }

                    synchronized (mListeners) {
                        copy = new ArrayList<>(mListeners);
                    }
                }
                // Note onShortcutChanged() needs to be called with the system service permissions.
                for (int i = copy.size() - 1; i >= 0; i--) {
                    copy.get(i).onShortcutChanged(packageName, userId);
                }
            } catch (Exception ignore) {
            }
        };
    }

    private void notifyShortcutChangeCallbacks(@NonNull String packageName, @UserIdInt int userId,
            @Nullable final List<ShortcutInfo> changedShortcuts,
            @Nullable final List<ShortcutInfo> removedShortcuts) {
        final List<ShortcutInfo> changedList = removeNonKeyFields(changedShortcuts);
        final List<ShortcutInfo> removedList = removeNonKeyFields(removedShortcuts);

        final UserHandle user = UserHandle.of(userId);
        injectPostToHandler(() -> {
            try {
                final ArrayList<LauncherApps.ShortcutChangeCallback> copy;
                synchronized (mServiceLock) {
                    if (!isUserUnlockedL(userId)) {
                        return;
                    }
                    synchronized (mShortcutChangeCallbacks) {
                        copy = new ArrayList<>(mShortcutChangeCallbacks);
                    }
                }
                for (int i = copy.size() - 1; i >= 0; i--) {
                    if (!CollectionUtils.isEmpty(changedList)) {
                        copy.get(i).onShortcutsAddedOrUpdated(packageName, changedList, user);
                    }
                    if (!CollectionUtils.isEmpty(removedList)) {
                        copy.get(i).onShortcutsRemoved(packageName, removedList, user);
                    }
                }
            } catch (Exception ignore) {
            }
        });
    }

    private List<ShortcutInfo> removeNonKeyFields(@Nullable List<ShortcutInfo> shortcutInfos) {
        if (CollectionUtils.isEmpty(shortcutInfos)) {
            return shortcutInfos;
        }

        final int size = shortcutInfos.size();
        List<ShortcutInfo> keyFieldOnlyShortcuts = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            final ShortcutInfo si = shortcutInfos.get(i);
            if (si.hasKeyFieldsOnly()) {
                keyFieldOnlyShortcuts.add(si);
            } else {
                keyFieldOnlyShortcuts.add(si.clone(ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO));
            }
        }
        return keyFieldOnlyShortcuts;
    }

    /**
     * Clean up / validate an incoming shortcut.
     * - Make sure all mandatory fields are set.
     * - Make sure the intent's extras are persistable, and them to set
     * {@link ShortcutInfo#mIntentPersistableExtrases}.  Also clear its extras.
     * - Clear flags.
     */
    private void fixUpIncomingShortcutInfo(@NonNull ShortcutInfo shortcut, boolean forUpdate,
            boolean forPinRequest) {
        if (shortcut.isReturnedByServer()) {
            Log.w(TAG,
                    "Re-publishing ShortcutInfo returned by server is not supported."
                    + " Some information such as icon may lost from shortcut.");
        }
        Objects.requireNonNull(shortcut, "Null shortcut detected");
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
            shortcut.enforceMandatoryFields(/* forPinned= */ forPinRequest);
            if (!forPinRequest) {
                Preconditions.checkState(shortcut.getActivity() != null,
                        "Cannot publish shortcut: target activity is not set");
            }
        }
        if (shortcut.getIcon() != null) {
            ShortcutInfo.validateIcon(shortcut.getIcon());
            validateIconURI(shortcut);
        }

        shortcut.replaceFlags(shortcut.getFlags() & ShortcutInfo.FLAG_LONG_LIVED);
    }

    // Validates the calling process has permission to access shortcut icon's image uri
    private void validateIconURI(@NonNull final ShortcutInfo si) {
        final int callingUid = injectBinderCallingUid();
        final Icon icon = si.getIcon();
        if (icon == null) {
            // There's no icon in this shortcut, nothing to validate here.
            return;
        }
        int iconType = icon.getType();
        if (iconType != Icon.TYPE_URI && iconType != Icon.TYPE_URI_ADAPTIVE_BITMAP) {
            // The icon is not URI-based, nothing to validate.
            return;
        }
        final Uri uri = icon.getUri();
        mUriGrantsManagerInternal.checkGrantUriPermission(callingUid, si.getPackage(),
                ContentProvider.getUriWithoutUserId(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                ContentProvider.getUserIdFromUri(uri, UserHandle.getUserId(callingUid)));
    }

    private void fixUpIncomingShortcutInfo(@NonNull ShortcutInfo shortcut, boolean forUpdate) {
        fixUpIncomingShortcutInfo(shortcut, forUpdate, /*forPinRequest=*/ false);
    }

    public void validateShortcutForPinRequest(@NonNull ShortcutInfo shortcut) {
        fixUpIncomingShortcutInfo(shortcut, /* forUpdate= */ false, /*forPinRequest=*/ true);
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

    private List<ShortcutInfo> setReturnedByServer(List<ShortcutInfo> shortcuts) {
        for (int i = shortcuts.size() - 1; i >= 0; i--) {
            shortcuts.get(i).setReturnedByServer();
        }
        return shortcuts;
    }

    // === APIs ===

    @Override
    public boolean setDynamicShortcuts(String packageName, ParceledListSlice shortcutInfoList,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        final boolean unlimited = injectHasUnlimitedShortcutsApiCallsPermission(
                injectBinderCallingPid(), injectBinderCallingUid());
        final List<ShortcutInfo> newShortcuts =
                (List<ShortcutInfo>) shortcutInfoList.getList();
        verifyShortcutInfoPackages(packageName, newShortcuts);
        final int size = newShortcuts.size();

        List<ShortcutInfo> changedShortcuts = null;
        List<ShortcutInfo> removedShortcuts = null;
        final ShortcutPackage ps;

        synchronized (mServiceLock) {
            throwIfUserLockedL(userId);

            ps = getPackageShortcutsForPublisherLocked(packageName, userId);

            ps.ensureImmutableShortcutsNotIncluded(newShortcuts, /*ignoreInvisible=*/ true);
            ps.ensureNoBitmapIconIfShortcutIsLongLived(newShortcuts);

            fillInDefaultActivity(newShortcuts);

            ps.enforceShortcutCountsBeforeOperation(newShortcuts, OPERATION_SET);

            // Throttling.
            if (!ps.tryApiCall(unlimited)) {
                return false;
            }

            // Initialize the implicit ranks for ShortcutPackage.adjustRanks().
            ps.clearAllImplicitRanks();
            assignImplicitRanks(newShortcuts);

            for (int i = 0; i < size; i++) {
                fixUpIncomingShortcutInfo(newShortcuts.get(i), /* forUpdate= */ false);
            }

            ArrayList<ShortcutInfo> cachedOrPinned = new ArrayList<>();
            ps.findAll(cachedOrPinned,
                    (ShortcutInfo si) -> si.isVisibleToPublisher()
                            && si.isDynamic() && (si.isCached() || si.isPinned()),
                    ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO);

            // First, remove all un-pinned and non-cached; dynamic shortcuts
            removedShortcuts = ps.deleteAllDynamicShortcuts();

            // Then, add/update all.  We need to make sure to take over "pinned" flag.
            for (int i = 0; i < size; i++) {
                final ShortcutInfo newShortcut = newShortcuts.get(i);
                ps.addOrReplaceDynamicShortcut(newShortcut);
            }

            // Lastly, adjust the ranks.
            ps.adjustRanks();

            changedShortcuts = prepareChangedShortcuts(
                    cachedOrPinned, newShortcuts, removedShortcuts, ps);
        }

        packageShortcutsChanged(ps, changedShortcuts, removedShortcuts);

        verifyStates();

        return true;
    }

    @Override
    public boolean updateShortcuts(String packageName, ParceledListSlice shortcutInfoList,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        final boolean unlimited = injectHasUnlimitedShortcutsApiCallsPermission(
                injectBinderCallingPid(), injectBinderCallingUid());
        final List<ShortcutInfo> newShortcuts =
                (List<ShortcutInfo>) shortcutInfoList.getList();
        verifyShortcutInfoPackages(packageName, newShortcuts);
        final int size = newShortcuts.size();

        final List<ShortcutInfo> changedShortcuts = new ArrayList<>(1);
        final ShortcutPackage ps;

        synchronized (mServiceLock) {
            throwIfUserLockedL(userId);

            ps = getPackageShortcutsForPublisherLocked(packageName, userId);

            ps.ensureImmutableShortcutsNotIncluded(newShortcuts, /*ignoreInvisible=*/ true);
            ps.ensureNoBitmapIconIfShortcutIsLongLived(newShortcuts);
            ps.ensureAllShortcutsVisibleToLauncher(newShortcuts);

            // For update, don't fill in the default activity.  Having null activity means
            // "don't update the activity" here.

            ps.enforceShortcutCountsBeforeOperation(newShortcuts, OPERATION_UPDATE);

            // Throttling.
            if (!ps.tryApiCall(unlimited)) {
                return false;
            }

            // Initialize the implicit ranks for ShortcutPackage.adjustRanks().
            ps.clearAllImplicitRanks();
            assignImplicitRanks(newShortcuts);

            for (int i = 0; i < size; i++) {
                final ShortcutInfo source = newShortcuts.get(i);
                fixUpIncomingShortcutInfo(source, /* forUpdate= */ true);

                ps.mutateShortcut(source.getId(), null, target -> {
                    // Invisible shortcuts can't be updated.
                    if (target == null || !target.isVisibleToPublisher()) {
                        return;
                    }

                    if (target.isEnabled() != source.isEnabled()) {
                        Slog.w(TAG, "ShortcutInfo.enabled cannot be changed with"
                                + " updateShortcuts()");
                    }

                    if (target.isLongLived() != source.isLongLived()) {
                        Slog.w(TAG,
                                "ShortcutInfo.longLived cannot be changed with"
                                        + " updateShortcuts()");
                    }

                    // When updating the rank, we need to insert between existing ranks,
                    // so set this setRankChanged, and also copy the implicit rank fo
                    // adjustRanks().
                    if (source.hasRank()) {
                        target.setRankChanged();
                        target.setImplicitRank(source.getImplicitRank());
                    }

                    final boolean replacingIcon = (source.getIcon() != null);
                    if (replacingIcon) {
                        ps.removeIcon(target);
                    }

                    // Note copyNonNullFieldsFrom() does the "updatable with?" check too.
                    target.copyNonNullFieldsFrom(source);
                    target.setTimestamp(injectCurrentTimeMillis());

                    if (replacingIcon) {
                        saveIconAndFixUpShortcutLocked(ps, target);
                    }

                    // When we're updating any resource related fields, re-extract the res
                    // names and the values.
                    if (replacingIcon || source.hasStringResources()) {
                        fixUpShortcutResourceNamesAndValues(target);
                    }

                    changedShortcuts.add(target);
                });
            }

            // Lastly, adjust the ranks.
            ps.adjustRanks();
        }
        packageShortcutsChanged(ps, changedShortcuts.isEmpty() ? null : changedShortcuts, null);

        verifyStates();

        return true;
    }

    @Override
    public boolean addDynamicShortcuts(String packageName, ParceledListSlice shortcutInfoList,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        final boolean unlimited = injectHasUnlimitedShortcutsApiCallsPermission(
                injectBinderCallingPid(), injectBinderCallingUid());
        final List<ShortcutInfo> newShortcuts =
                (List<ShortcutInfo>) shortcutInfoList.getList();
        verifyShortcutInfoPackages(packageName, newShortcuts);
        final int size = newShortcuts.size();

        List<ShortcutInfo> changedShortcuts = null;
        final ShortcutPackage ps;

        synchronized (mServiceLock) {
            throwIfUserLockedL(userId);

            ps = getPackageShortcutsForPublisherLocked(packageName, userId);

            ps.ensureImmutableShortcutsNotIncluded(newShortcuts, /*ignoreInvisible=*/ true);
            ps.ensureNoBitmapIconIfShortcutIsLongLived(newShortcuts);

            fillInDefaultActivity(newShortcuts);

            ps.enforceShortcutCountsBeforeOperation(newShortcuts, OPERATION_ADD);

            // Initialize the implicit ranks for ShortcutPackage.adjustRanks().
            ps.clearAllImplicitRanks();
            assignImplicitRanks(newShortcuts);

            // Throttling.
            if (!ps.tryApiCall(unlimited)) {
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
                ps.addOrReplaceDynamicShortcut(newShortcut);

                if (changedShortcuts == null) {
                    changedShortcuts = new ArrayList<>(1);
                }
                changedShortcuts.add(newShortcut);
            }

            // Lastly, adjust the ranks.
            ps.adjustRanks();
        }
        packageShortcutsChanged(ps, changedShortcuts, null);
        verifyStates();
        return true;
    }

    @Override
    public void pushDynamicShortcut(String packageName, ShortcutInfo shortcut,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);
        verifyShortcutInfoPackage(packageName, shortcut);

        List<ShortcutInfo> changedShortcuts = new ArrayList<>();
        List<ShortcutInfo> removedShortcuts = null;
        final ShortcutPackage ps;

        synchronized (mServiceLock) {
            throwIfUserLockedL(userId);

            ps = getPackageShortcutsForPublisherLocked(packageName, userId);

            ps.ensureNotImmutable(shortcut.getId(), /*ignoreInvisible=*/ true);
            fillInDefaultActivity(Arrays.asList(shortcut));

            if (!shortcut.hasRank()) {
                shortcut.setRank(0);
            }
            // Initialize the implicit ranks for ShortcutPackage.adjustRanks().
            ps.clearAllImplicitRanks();
            shortcut.setImplicitRank(0);

            // Validate the shortcut.
            fixUpIncomingShortcutInfo(shortcut, /* forUpdate= */ false);

            // When ranks are changing, we need to insert between ranks, so set the
            // "rank changed" flag.
            shortcut.setRankChanged();

            // Push it.
            boolean deleted = ps.pushDynamicShortcut(shortcut, changedShortcuts);

            if (deleted) {
                if (changedShortcuts.isEmpty()) {
                    return;  // Failed to push.
                }
                removedShortcuts = Collections.singletonList(changedShortcuts.get(0));
                changedShortcuts.clear();
            }
            changedShortcuts.add(shortcut);

            // Lastly, adjust the ranks.
            ps.adjustRanks();
        }

        packageShortcutsChanged(ps, changedShortcuts, removedShortcuts);

        ps.reportShortcutUsed(mUsageStatsManagerInternal, shortcut.getId());

        verifyStates();
    }

    @Override
    public void requestPinShortcut(String packageName, ShortcutInfo shortcut,
            IntentSender resultIntent, int userId, AndroidFuture<String> ret) {
        Objects.requireNonNull(shortcut);
        Preconditions.checkArgument(shortcut.isEnabled(), "Shortcut must be enabled");
        Preconditions.checkArgument(
                !shortcut.isExcludedFromSurfaces(ShortcutInfo.SURFACE_LAUNCHER),
                "Shortcut excluded from launcher cannot be pinned");
        ret.complete(String.valueOf(requestPinItem(
                packageName, userId, shortcut, null, null, resultIntent)));
    }

    @Override
    public void createShortcutResultIntent(String packageName, ShortcutInfo shortcut, int userId,
            AndroidFuture<Intent> ret) throws RemoteException {
        Objects.requireNonNull(shortcut);
        Preconditions.checkArgument(shortcut.isEnabled(), "Shortcut must be enabled");
        verifyCaller(packageName, userId);
        verifyShortcutInfoPackage(packageName, shortcut);
        final Intent intent;
        synchronized (mServiceLock) {
            throwIfUserLockedL(userId);
            // Send request to the launcher, if supported.
            intent = mShortcutRequestPinProcessor.createShortcutResultIntent(shortcut, userId);
        }
        verifyStates();
        ret.complete(intent);
    }

    /**
     * Handles {@link #requestPinShortcut} and {@link ShortcutServiceInternal#requestPinAppWidget}.
     * After validating the caller, it passes the request to {@link #mShortcutRequestPinProcessor}.
     * Either {@param shortcut} or {@param appWidget} should be non-null.
     */
    private boolean requestPinItem(String callingPackage, int userId, ShortcutInfo shortcut,
            AppWidgetProviderInfo appWidget, Bundle extras, IntentSender resultIntent) {
        return requestPinItem(callingPackage, userId, shortcut, appWidget, extras, resultIntent,
                injectBinderCallingPid(), injectBinderCallingUid());
    }

    private boolean requestPinItem(String callingPackage, int userId, ShortcutInfo shortcut,
            AppWidgetProviderInfo appWidget, Bundle extras, IntentSender resultIntent,
            int callingPid, int callingUid) {
        verifyCaller(callingPackage, userId);
        if (shortcut == null || !injectHasAccessShortcutsPermission(
                callingPid, callingUid)) {
            // Verify if caller is the shortcut owner, only if caller doesn't have ACCESS_SHORTCUTS.
            verifyShortcutInfoPackage(callingPackage, shortcut);
        }

        final boolean ret;
        synchronized (mServiceLock) {
            throwIfUserLockedL(userId);

            Preconditions.checkState(isUidForegroundLocked(callingUid),
                    "Calling application must have a foreground activity or a foreground service");

            // If it's a pin shortcut request, and there's already a shortcut with the same ID
            // that's not visible to the caller (i.e. restore-blocked; meaning it's pinned by
            // someone already), then we just replace the existing one with this new one,
            // and then proceed the rest of the process.
            if (shortcut != null) {
                final String shortcutPackage = shortcut.getPackage();
                final ShortcutPackage ps = getPackageShortcutsForPublisherLocked(
                        shortcutPackage, userId);
                final String id = shortcut.getId();
                if (ps.isShortcutExistsAndInvisibleToPublisher(id)) {

                    ps.updateInvisibleShortcutForPinRequestWith(shortcut);

                    packageShortcutsChanged(ps, Collections.singletonList(shortcut), null);
                }
            }

            // Send request to the launcher, if supported.
            ret = mShortcutRequestPinProcessor.requestPinItemLocked(shortcut, appWidget, extras,
                    userId, resultIntent);
        }

        verifyStates();

        return ret;
    }

    @Override
    public void disableShortcuts(String packageName, List shortcutIds,
            CharSequence disabledMessage, int disabledMessageResId, @UserIdInt int userId) {
        verifyCaller(packageName, userId);
        Objects.requireNonNull(shortcutIds, "shortcutIds must be provided");
        List<ShortcutInfo> changedShortcuts = null;
        List<ShortcutInfo> removedShortcuts = null;
        final ShortcutPackage ps;
        synchronized (mServiceLock) {
            throwIfUserLockedL(userId);
            ps = getPackageShortcutsForPublisherLocked(packageName, userId);
            ps.ensureImmutableShortcutsNotIncludedWithIds((List<String>) shortcutIds,
                    /*ignoreInvisible=*/ true);
            final String disabledMessageString =
                    (disabledMessage == null) ? null : disabledMessage.toString();
            for (int i = shortcutIds.size() - 1; i >= 0; i--) {
                final String id = Preconditions.checkStringNotEmpty((String) shortcutIds.get(i));
                if (!ps.isShortcutExistsAndVisibleToPublisher(id)) {
                    continue;
                }
                final ShortcutInfo deleted = ps.disableWithId(id,
                        disabledMessageString, disabledMessageResId,
                        /* overrideImmutable=*/ false, /*ignoreInvisible=*/ true,
                        ShortcutInfo.DISABLED_REASON_BY_APP);
                if (deleted == null) {
                    if (changedShortcuts == null) {
                        changedShortcuts = new ArrayList<>(1);
                    }
                    changedShortcuts.add(ps.findShortcutById(id));
                } else {
                    if (removedShortcuts == null) {
                        removedShortcuts = new ArrayList<>(1);
                    }
                    removedShortcuts.add(deleted);
                }
            }
            // We may have removed dynamic shortcuts which may have left a gap, so adjust the ranks.
            ps.adjustRanks();
        }
        packageShortcutsChanged(ps, changedShortcuts, removedShortcuts);
        verifyStates();
    }

    @Override
    public void enableShortcuts(String packageName, List shortcutIds, @UserIdInt int userId) {
        verifyCaller(packageName, userId);
        Objects.requireNonNull(shortcutIds, "shortcutIds must be provided");
        List<ShortcutInfo> changedShortcuts = null;
        final ShortcutPackage ps;
        synchronized (mServiceLock) {
            throwIfUserLockedL(userId);
            ps = getPackageShortcutsForPublisherLocked(packageName, userId);
            ps.ensureImmutableShortcutsNotIncludedWithIds((List<String>) shortcutIds,
                    /*ignoreInvisible=*/ true);
            for (int i = shortcutIds.size() - 1; i >= 0; i--) {
                final String id = Preconditions.checkStringNotEmpty((String) shortcutIds.get(i));
                if (!ps.isShortcutExistsAndVisibleToPublisher(id)) {
                    continue;
                }
                ps.enableWithId(id);
                if (changedShortcuts == null) {
                    changedShortcuts = new ArrayList<>(1);
                }
                changedShortcuts.add(ps.findShortcutById(id));
            }
        }
        packageShortcutsChanged(ps, changedShortcuts, null);
        verifyStates();
    }


    @Override
    public void removeDynamicShortcuts(String packageName, List<String> shortcutIds,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);
        Objects.requireNonNull(shortcutIds, "shortcutIds must be provided");
        List<ShortcutInfo> changedShortcuts = null;
        List<ShortcutInfo> removedShortcuts = null;
        final ShortcutPackage ps;
        synchronized (mServiceLock) {
            throwIfUserLockedL(userId);
            ps = getPackageShortcutsForPublisherLocked(packageName, userId);
            ps.ensureImmutableShortcutsNotIncludedWithIds((List<String>) shortcutIds,
                    /*ignoreInvisible=*/ true);
            for (int i = shortcutIds.size() - 1; i >= 0; i--) {
                final String id = Preconditions.checkStringNotEmpty(
                        (String) shortcutIds.get(i));
                if (!ps.isShortcutExistsAndVisibleToPublisher(id)) {
                    continue;
                }
                ShortcutInfo removed = ps.deleteDynamicWithId(id, /*ignoreInvisible=*/ true,
                        /*wasPushedOut*/ false);
                if (removed == null) {
                    if (changedShortcuts == null) {
                        changedShortcuts = new ArrayList<>(1);
                    }
                    changedShortcuts.add(ps.findShortcutById(id));
                } else {
                    if (removedShortcuts == null) {
                        removedShortcuts = new ArrayList<>(1);
                    }
                    removedShortcuts.add(removed);
                }
            }
            // We may have removed dynamic shortcuts which may have left a gap, so adjust the ranks.
            ps.adjustRanks();
        }
        packageShortcutsChanged(ps, changedShortcuts, removedShortcuts);
        verifyStates();
    }

    @Override
    public void removeAllDynamicShortcuts(String packageName, @UserIdInt int userId) {
        verifyCaller(packageName, userId);
        List<ShortcutInfo> changedShortcuts = new ArrayList<>();
        List<ShortcutInfo> removedShortcuts = null;
        final ShortcutPackage ps;
        synchronized (mServiceLock) {
            throwIfUserLockedL(userId);
            ps = getPackageShortcutsForPublisherLocked(packageName, userId);
            // Dynamic shortcuts that are either cached or pinned will not get deleted.
            ps.findAll(changedShortcuts,
                    (ShortcutInfo si) -> si.isVisibleToPublisher()
                            && si.isDynamic() && (si.isCached() || si.isPinned()),
                    ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO);
            removedShortcuts = ps.deleteAllDynamicShortcuts();
            changedShortcuts = prepareChangedShortcuts(
                    changedShortcuts, null, removedShortcuts, ps);
        }
        packageShortcutsChanged(ps, changedShortcuts, removedShortcuts);
        verifyStates();
    }

    @Override
    public void removeLongLivedShortcuts(String packageName, List shortcutIds,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);
        Objects.requireNonNull(shortcutIds, "shortcutIds must be provided");
        List<ShortcutInfo> changedShortcuts = null;
        List<ShortcutInfo> removedShortcuts = null;
        final ShortcutPackage ps;
        synchronized (mServiceLock) {
            throwIfUserLockedL(userId);
            ps = getPackageShortcutsForPublisherLocked(packageName, userId);
            ps.ensureImmutableShortcutsNotIncludedWithIds((List<String>) shortcutIds,
                    /*ignoreInvisible=*/ true);
            for (int i = shortcutIds.size() - 1; i >= 0; i--) {
                final String id = Preconditions.checkStringNotEmpty((String) shortcutIds.get(i));
                if (!ps.isShortcutExistsAndVisibleToPublisher(id)) {
                    continue;
                }
                ShortcutInfo removed = ps.deleteLongLivedWithId(id, /*ignoreInvisible=*/ true);
                if (removed != null) {
                    if (removedShortcuts == null) {
                        removedShortcuts = new ArrayList<>(1);
                    }
                    removedShortcuts.add(removed);
                } else {
                    if (changedShortcuts == null) {
                        changedShortcuts = new ArrayList<>(1);
                    }
                    changedShortcuts.add(ps.findShortcutById(id));
                }
            }
            // We may have removed dynamic shortcuts which may have left a gap, so adjust the ranks.
            ps.adjustRanks();
        }
        packageShortcutsChanged(ps, changedShortcuts, removedShortcuts);
        verifyStates();
    }

    @Override
    public ParceledListSlice<ShortcutInfo> getShortcuts(String packageName,
            @ShortcutManager.ShortcutMatchFlags int matchFlags, @UserIdInt int userId) {
        verifyCaller(packageName, userId);
        synchronized (mServiceLock) {
            throwIfUserLockedL(userId);
            final boolean matchDynamic = (matchFlags & ShortcutManager.FLAG_MATCH_DYNAMIC) != 0;
            final boolean matchPinned = (matchFlags & ShortcutManager.FLAG_MATCH_PINNED) != 0;
            final boolean matchManifest = (matchFlags & ShortcutManager.FLAG_MATCH_MANIFEST) != 0;
            final boolean matchCached = (matchFlags & ShortcutManager.FLAG_MATCH_CACHED) != 0;
            final int shortcutFlags = (matchDynamic ? ShortcutInfo.FLAG_DYNAMIC : 0)
                    | (matchPinned ? ShortcutInfo.FLAG_PINNED : 0)
                    | (matchManifest ? ShortcutInfo.FLAG_MANIFEST : 0)
                    | (matchCached ? ShortcutInfo.FLAG_CACHED_ALL : 0);
            return getShortcutsWithQueryLocked(
                    packageName, userId, ShortcutInfo.CLONE_REMOVE_FOR_CREATOR,
                    (ShortcutInfo si) ->
                            si.isVisibleToPublisher()
                                    && (si.getFlags() & shortcutFlags) != 0);
        }
    }

    @Override
    public ParceledListSlice getShareTargets(String packageName,
            IntentFilter filter, @UserIdInt int userId) {
        Preconditions.checkStringNotEmpty(packageName, "packageName");
        Objects.requireNonNull(filter, "intentFilter");
        if (!isCallerChooserActivity()) {
            verifyCaller(packageName, userId);
        }
        enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_APP_PREDICTIONS,
                "getShareTargets");
        final ComponentName chooser = injectChooserActivity();
        final String pkg = chooser != null ? chooser.getPackageName() : mContext.getPackageName();
        synchronized (mServiceLock) {
            throwIfUserLockedL(userId);
            final List<ShortcutManager.ShareShortcutInfo> shortcutInfoList = new ArrayList<>();
            final ShortcutUser user = getUserShortcutsLocked(userId);
            user.forAllPackages(p -> shortcutInfoList.addAll(
                    p.getMatchingShareTargets(filter, pkg)));
            return new ParceledListSlice<>(shortcutInfoList);
        }
    }

    @Override
    public boolean hasShareTargets(String packageName, String packageToCheck,
            @UserIdInt int userId) {
        verifyCaller(packageName, userId);
        enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_APP_PREDICTIONS,
                "hasShareTargets");

        synchronized (mServiceLock) {
            throwIfUserLockedL(userId);

            return getPackageShortcutsLocked(packageToCheck, userId).hasShareTargets();
        }
    }

    public boolean isSharingShortcut(int callingUserId, @NonNull String callingPackage,
            @NonNull String packageName, @NonNull String shortcutId, int userId,
            @NonNull IntentFilter filter) {
        verifyCaller(callingPackage, callingUserId);
        enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_APP_PREDICTIONS,
                "isSharingShortcut");

        synchronized (mServiceLock) {
            throwIfUserLockedL(userId);
            throwIfUserLockedL(callingUserId);

            final List<ShortcutManager.ShareShortcutInfo> matchedTargets =
                    getPackageShortcutsLocked(packageName, userId)
                            .getMatchingShareTargets(filter);
            final int matchedSize = matchedTargets.size();
            for (int i = 0; i < matchedSize; i++) {
                if (matchedTargets.get(i).getShortcutInfo().getId().equals(shortcutId)) {
                    return true;
                }
            }
        }
        return false;
    }

    @GuardedBy("mServiceLock")
    private ParceledListSlice<ShortcutInfo> getShortcutsWithQueryLocked(@NonNull String packageName,
            @UserIdInt int userId, int cloneFlags, @NonNull Predicate<ShortcutInfo> filter) {

        final ArrayList<ShortcutInfo> ret = new ArrayList<>();

        final ShortcutPackage ps = getPackageShortcutsForPublisherLocked(packageName, userId);
        ps.findAll(ret, filter, cloneFlags);
        return new ParceledListSlice<>(setReturnedByServer(ret));
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

        final boolean unlimited = injectHasUnlimitedShortcutsApiCallsPermission(
                injectBinderCallingPid(), injectBinderCallingUid());

        synchronized (mServiceLock) {
            throwIfUserLockedL(userId);

            final ShortcutPackage ps = getPackageShortcutsForPublisherLocked(packageName, userId);
            return mMaxUpdatesPerInterval - ps.getApiCallCount(unlimited);
        }
    }

    @Override
    public long getRateLimitResetTime(String packageName, @UserIdInt int userId) {
        verifyCaller(packageName, userId);

        synchronized (mServiceLock) {
            throwIfUserLockedL(userId);

            return getNextResetTimeLocked();
        }
    }

    @Override
    public int getIconMaxDimensions(String packageName, int userId) {
        verifyCaller(packageName, userId);

        synchronized (mServiceLock) {
            return mMaxIconDimension;
        }
    }

    @Override
    public void reportShortcutUsed(String packageName, String shortcutId, int userId) {
        verifyCaller(packageName, userId);
        Objects.requireNonNull(shortcutId);
        if (DEBUG) {
            Slog.d(TAG, String.format("reportShortcutUsed: Shortcut %s package %s used on user %d",
                    shortcutId, packageName, userId));
        }
        final ShortcutPackage ps;
        synchronized (mServiceLock) {
            throwIfUserLockedL(userId);
            ps = getPackageShortcutsForPublisherLocked(packageName, userId);
            if (ps.findShortcutById(shortcutId) == null) {
                Log.w(TAG, String.format("reportShortcutUsed: package %s doesn't have shortcut %s",
                        packageName, shortcutId));
                return;
            }
        }
        ps.reportShortcutUsed(mUsageStatsManagerInternal, shortcutId);
    }

    @Override
    public boolean isRequestPinItemSupported(int callingUserId, int requestType) {
        verifyCallerUserId(callingUserId);

        final long token = injectClearCallingIdentity();
        try {
            return mShortcutRequestPinProcessor
                    .isRequestPinItemSupported(callingUserId, requestType);
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
        synchronized (mServiceLock) {
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
        mRawLastResetTime.set(injectCurrentTimeMillis());
        scheduleSaveBaseState();
        Slog.i(TAG, "ShortcutManager: throttling counter reset for all users");
    }

    @Override
    public void onApplicationActive(String packageName, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onApplicationActive: package=" + packageName + "  userid=" + userId);
        }
        enforceResetThrottlingPermission();
        synchronized (mServiceLock) {
            if (!isUserUnlockedL(userId)) {
                // This is called by system UI, so no need to throw.  Just ignore.
                return;
            }
            getPackageShortcutsLocked(packageName, userId)
                    .resetRateLimitingForCommandLineNoSaving();
        }
        saveUser(userId);
    }

    // We override this method in unit tests to do a simpler check.
    boolean hasShortcutHostPermission(@NonNull String callingPackage, int userId,
            int callingPid, int callingUid) {
        if (canSeeAnyPinnedShortcut(callingPackage, userId, callingPid, callingUid)) {
            return true;
        }
        final long start = getStatStartTime();
        try {
            return hasShortcutHostPermissionInner(callingPackage, userId);
        } finally {
            logDurationStat(Stats.LAUNCHER_PERMISSION_CHECK, start);
        }
    }

    boolean canSeeAnyPinnedShortcut(@NonNull String callingPackage, int userId,
            int callingPid, int callingUid) {
        if (injectHasAccessShortcutsPermission(callingPid, callingUid)) {
            return true;
        }
        synchronized (mNonPersistentUsersLock) {
            return getNonPersistentUserLocked(userId).hasHostPackage(callingPackage);
        }
    }

    /**
     * Returns true if the caller has the "ACCESS_SHORTCUTS" permission.
     */
    @VisibleForTesting
    boolean injectHasAccessShortcutsPermission(int callingPid, int callingUid) {
        return mContext.checkPermission(android.Manifest.permission.ACCESS_SHORTCUTS,
                callingPid, callingUid) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Returns true if the caller has the "UNLIMITED_SHORTCUTS_API_CALLS" permission.
     */
    @VisibleForTesting
    boolean injectHasUnlimitedShortcutsApiCallsPermission(int callingPid, int callingUid) {
        return mContext.checkPermission(permission.UNLIMITED_SHORTCUTS_API_CALLS,
                callingPid, callingUid) == PackageManager.PERMISSION_GRANTED;
    }

    // This method is extracted so we can directly call this method from unit tests,
    // even when hasShortcutPermission() is overridden.
    @VisibleForTesting
    boolean hasShortcutHostPermissionInner(@NonNull String packageName, int userId) {
        synchronized (mServiceLock) {
            throwIfUserLockedL(userId);

            final String defaultLauncher = getDefaultLauncher(userId);

            if (defaultLauncher != null) {
                if (DEBUG) {
                    Slog.v(TAG, "Detected launcher: " + defaultLauncher + " user: " + userId);
                }
                return defaultLauncher.equals(packageName);
            } else {
                return false;
            }
        }
    }

    @VisibleForTesting
    boolean areShortcutsSupportedOnHomeScreen(@UserIdInt int userId) {
        if (!android.os.Flags.allowPrivateProfile() || !Flags.disablePrivateSpaceItemsOnHome()
                || !android.multiuser.Flags.enablePrivateSpaceFeatures()) {
            return true;
        }
        final long start = getStatStartTime();
        final long token = injectClearCallingIdentity();
        boolean isSupported;
        try {
            synchronized (mServiceLock) {
                isSupported = !mUserManagerInternal.getUserProperties(userId)
                        .areItemsRestrictedOnHomeScreen();
            }
        } finally {
            injectRestoreCallingIdentity(token);
            logDurationStat(Stats.GET_DEFAULT_LAUNCHER, start);
        }
        return isSupported;
    }

    @Nullable
    String getDefaultLauncher(@UserIdInt int userId) {
        final long start = getStatStartTime();
        final long token = injectClearCallingIdentity();
        try {
            synchronized (mServiceLock) {
                throwIfUserLockedL(userId);

                final ShortcutUser user = getUserShortcutsLocked(userId);
                String cachedLauncher = user.getCachedLauncher();
                if (cachedLauncher != null) {
                    return cachedLauncher;
                }

                // Default launcher from role manager.
                final long startGetHomeRoleHoldersAsUser = getStatStartTime();
                final String defaultLauncher = injectGetHomeRoleHolderAsUser(
                        getParentOrSelfUserId(userId));
                logDurationStat(Stats.GET_DEFAULT_HOME, startGetHomeRoleHoldersAsUser);

                if (defaultLauncher != null) {
                    if (DEBUG) {
                        Slog.v(TAG, "Default launcher from RoleManager: " + defaultLauncher
                                + " user: " + userId);
                    }
                    user.setCachedLauncher(defaultLauncher);
                } else {
                    Slog.e(TAG, "Default launcher not found." + " user: " + userId);
                }

                return defaultLauncher;
            }
        } finally {
            injectRestoreCallingIdentity(token);
            logDurationStat(Stats.GET_DEFAULT_LAUNCHER, start);
        }
    }

    public void setShortcutHostPackage(@NonNull String type, @Nullable String packageName,
            int userId) {
        synchronized (mNonPersistentUsersLock) {
            getNonPersistentUserLocked(userId).setShortcutHostPackage(type, packageName);
        }
    }

    // === House keeping ===

    private void cleanUpPackageForAllLoadedUsers(String packageName, @UserIdInt int packageUserId,
            boolean appStillExists) {
        synchronized (mServiceLock) {
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
    @GuardedBy("mServiceLock")
    @VisibleForTesting
    void cleanUpPackageLocked(String packageName, int owningUserId, int packageUserId,
            boolean appStillExists) {
        final boolean wasUserLoaded = isUserLoadedLocked(owningUserId);

        final ShortcutUser user = getUserShortcutsLocked(owningUserId);
        boolean doNotify = false;
        // First, remove the package from the package list (if the package is a publisher).
        final ShortcutPackage sp = (packageUserId == owningUserId)
                ? user.removePackage(packageName) : null;
        if (sp != null) {
            doNotify = true;
        }

        // Also remove from the launcher list (if the package is a launcher).
        user.removeLauncher(packageUserId, packageName);

        // Then remove pinned shortcuts from all launchers.
        user.forAllLaunchers(l -> l.cleanUpPackage(packageName, packageUserId));

        // Now there may be orphan shortcuts because we removed pinned shortcuts at the previous
        // step.  Remove them too.
        user.forAllPackages(p -> p.refreshPinnedFlags());

        if (doNotify) {
            notifyListeners(packageName, owningUserId);
        }

        // If the app still exists (i.e. data cleared), we need to re-publish manifest shortcuts.
        if (appStillExists && (packageUserId == owningUserId)) {
            // This will do the notification and save when needed, so do it after the above
            // notifyListeners.
            user.rescanPackageIfNeeded(packageName, /* forceRescan=*/ true);
        }
        if (!appStillExists && (packageUserId == owningUserId) && sp != null) {
            // If the app is removed altogether, we can get rid of the xml as well
            injectPostToHandler(() -> sp.removeShortcutPackageItem());
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
                @Nullable List<LocusId> locusIds, @Nullable ComponentName componentName,
                int queryFlags, int userId, int callingPid, int callingUid) {
            if (DEBUG_REBOOT) {
                Slog.d(TAG, "Getting shortcuts for launcher= " + callingPackage
                        + "user=" + userId + " pkg=" + packageName);
            }
            final ArrayList<ShortcutInfo> ret = new ArrayList<>();

            int flags = ShortcutInfo.CLONE_REMOVE_FOR_LAUNCHER;
            if ((queryFlags & ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY) != 0) {
                flags = ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO;
            } else if ((queryFlags & ShortcutQuery.FLAG_GET_PERSONS_DATA) != 0) {
                flags &= ~ShortcutInfo.CLONE_REMOVE_PERSON;
            }
            final int cloneFlag = flags;

            if (packageName == null) {
                shortcutIds = null; // LauncherAppsService already threw for it though.
            }

            synchronized (mServiceLock) {
                throwIfUserLockedL(userId);
                throwIfUserLockedL(launcherUserId);

                getLauncherShortcutsLocked(callingPackage, userId, launcherUserId)
                        .attemptToRestoreIfNeededAndSave();

                if (packageName != null) {
                    getShortcutsInnerLocked(launcherUserId,
                            callingPackage, packageName, shortcutIds, locusIds, changedSince,
                            componentName, queryFlags, userId, ret, cloneFlag,
                            callingPid, callingUid);
                } else {
                    final List<String> shortcutIdsF = shortcutIds;
                    final List<LocusId> locusIdsF = locusIds;
                    getUserShortcutsLocked(userId).forAllPackages(p -> {
                        getShortcutsInnerLocked(launcherUserId,
                                callingPackage, p.getPackageName(), shortcutIdsF, locusIdsF,
                                changedSince, componentName, queryFlags, userId, ret, cloneFlag,
                                callingPid, callingUid);
                    });
                }
            }
            return setReturnedByServer(ret);
        }

        @GuardedBy("ShortcutService.this.mServiceLock")
        private void getShortcutsInnerLocked(int launcherUserId, @NonNull String callingPackage,
                @Nullable String packageName, @Nullable List<String> shortcutIds,
                @Nullable List<LocusId> locusIds, long changedSince,
                @Nullable ComponentName componentName, int queryFlags,
                int userId, ArrayList<ShortcutInfo> ret, int cloneFlag,
                int callingPid, int callingUid) {
            final ArraySet<String> ids = shortcutIds == null ? null
                    : new ArraySet<>(shortcutIds);

            final ShortcutUser user = getUserShortcutsLocked(userId);
            final ShortcutPackage p = user.getPackageShortcutsIfExists(packageName);
            if (p == null) {
                if (DEBUG_REBOOT) {
                    Log.d(TAG, "getShortcutsInnerLocked() returned empty results because "
                            + packageName + " isn't loaded");
                }
                return; // No need to instantiate ShortcutPackage.
            }

            final boolean canAccessAllShortcuts =
                    canSeeAnyPinnedShortcut(callingPackage, launcherUserId, callingPid, callingUid);

            final boolean getPinnedByAnyLauncher =
                    canAccessAllShortcuts &&
                    ((queryFlags & ShortcutQuery.FLAG_MATCH_PINNED_BY_ANY_LAUNCHER) != 0);
            queryFlags |= (getPinnedByAnyLauncher ? ShortcutQuery.FLAG_MATCH_PINNED : 0);

            final Predicate<ShortcutInfo> filter = getFilterFromQuery(ids, locusIds, changedSince,
                    componentName, queryFlags, getPinnedByAnyLauncher);
            p.findAll(ret, filter, cloneFlag, callingPackage, launcherUserId,
                        getPinnedByAnyLauncher);
        }

        private Predicate<ShortcutInfo> getFilterFromQuery(@Nullable ArraySet<String> ids,
                @Nullable List<LocusId> locusIds, long changedSince,
                @Nullable ComponentName componentName, int queryFlags,
                boolean getPinnedByAnyLauncher) {
            final ArraySet<LocusId> locIds = locusIds == null ? null
                    : new ArraySet<>(locusIds);

            final boolean matchDynamic = (queryFlags & ShortcutQuery.FLAG_MATCH_DYNAMIC) != 0;
            final boolean matchPinned = (queryFlags & ShortcutQuery.FLAG_MATCH_PINNED) != 0;
            final boolean matchManifest = (queryFlags & ShortcutQuery.FLAG_MATCH_MANIFEST) != 0;
            final boolean matchCached = (queryFlags & ShortcutQuery.FLAG_MATCH_CACHED) != 0;
            return si -> {
                if (si.getLastChangedTimestamp() < changedSince) {
                    return false;
                }
                if (ids != null && !ids.contains(si.getId())) {
                    return false;
                }
                if (locIds != null && !locIds.contains(si.getLocusId())) {
                    return false;
                }
                if (componentName != null) {
                    if (si.getActivity() != null
                            && !si.getActivity().equals(componentName)) {
                        return false;
                    }
                }
                if (matchDynamic && si.isDynamic()) {
                    return true;
                }
                if ((matchPinned || getPinnedByAnyLauncher) && si.isPinned()) {
                    return true;
                }
                if (matchManifest && si.isDeclaredInManifest()) {
                    return true;
                }
                if (matchCached && si.isCached()) {
                    return true;
                }
                return false;
            };
        }

        @Override
        public void getShortcutsAsync(int launcherUserId,
                @NonNull String callingPackage, long changedSince,
                @Nullable String packageName, @Nullable List<String> shortcutIds,
                @Nullable List<LocusId> locusIds, @Nullable ComponentName componentName,
                int queryFlags, int userId, int callingPid, int callingUid,
                @NonNull AndroidFuture<List<ShortcutInfo>> cb) {
            final List<ShortcutInfo> ret = getShortcuts(launcherUserId, callingPackage,
                    changedSince, packageName, shortcutIds, locusIds, componentName, queryFlags,
                    userId, callingPid, callingUid);
            if (shortcutIds == null || packageName == null || ret.size() >= shortcutIds.size()) {
                // skip persistence layer if not querying by id in a specific package or all
                // shortcuts have already been found.
                cb.complete(ret);
                return;
            }
            final ShortcutPackage p;
            synchronized (mServiceLock) {
                p = getUserShortcutsLocked(userId).getPackageShortcutsIfExists(packageName);
            }
            if (p == null) {
                cb.complete(ret);
                return; // Bail-out directly if package doesn't exist.
            }
            // fetch remaining shortcuts from persistence layer
            final ArraySet<String> ids = new ArraySet<>(shortcutIds);
            // remove the ids that are already fetched
            ret.stream().map(ShortcutInfo::getId).collect(Collectors.toList()).forEach(ids::remove);

            int flags = ShortcutInfo.CLONE_REMOVE_FOR_LAUNCHER;
            if ((queryFlags & ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY) != 0) {
                flags = ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO;
            } else if ((queryFlags & ShortcutQuery.FLAG_GET_PERSONS_DATA) != 0) {
                flags &= ~ShortcutInfo.CLONE_REMOVE_PERSON;
            }
            final int cloneFlag = flags;

            p.getShortcutByIdsAsync(ids, shortcuts -> {
                if (shortcuts != null) {
                    shortcuts.stream().map(si -> si.clone(cloneFlag)).forEach(ret::add);
                }
                cb.complete(ret);
            });
        }

        @Override
        public boolean isPinnedByCaller(int launcherUserId, @NonNull String callingPackage,
                @NonNull String packageName, @NonNull String shortcutId, int userId) {
            Preconditions.checkStringNotEmpty(packageName, "packageName");
            Preconditions.checkStringNotEmpty(shortcutId, "shortcutId");

            synchronized (mServiceLock) {
                throwIfUserLockedL(userId);
                throwIfUserLockedL(launcherUserId);

                getLauncherShortcutsLocked(callingPackage, userId, launcherUserId)
                        .attemptToRestoreIfNeededAndSave();

                final ShortcutInfo si = getShortcutInfoLocked(
                        launcherUserId, callingPackage, packageName, shortcutId, userId,
                        /*getPinnedByAnyLauncher=*/ false);
                return si != null && si.isPinned();
            }
        }

        @GuardedBy("ShortcutService.this.mServiceLock")
        private ShortcutInfo getShortcutInfoLocked(
                int launcherUserId, @NonNull String callingPackage,
                @NonNull String packageName, @NonNull String shortcutId, int userId,
                boolean getPinnedByAnyLauncher) {
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
            p.findAll(list, (ShortcutInfo si) -> shortcutId.equals(si.getId()),
                    /* clone flags=*/ 0, callingPackage, launcherUserId, getPinnedByAnyLauncher);
            return list.size() == 0 ? null : list.get(0);
        }

        private void getShortcutInfoAsync(
                int launcherUserId, @NonNull String packageName, @NonNull String shortcutId,
                int userId, @NonNull Consumer<ShortcutInfo> cb) {
            Preconditions.checkStringNotEmpty(packageName, "packageName");
            Preconditions.checkStringNotEmpty(shortcutId, "shortcutId");

            throwIfUserLockedL(userId);
            throwIfUserLockedL(launcherUserId);

            final ShortcutPackage p;
            synchronized (mServiceLock) {
                p = getUserShortcutsLocked(userId).getPackageShortcutsIfExists(packageName);
            }
            if (p == null) {
                cb.accept(null);
                return;
            }
            p.getShortcutByIdsAsync(Collections.singleton(shortcutId), shortcuts ->
                    cb.accept(shortcuts == null || shortcuts.isEmpty() ? null : shortcuts.get(0)));
        }

        @Override
        public void pinShortcuts(int launcherUserId,
                @NonNull String callingPackage, @NonNull String packageName,
                @NonNull List<String> shortcutIds, int userId) {
            // Calling permission must be checked by LauncherAppsImpl.
            Preconditions.checkStringNotEmpty(packageName, "packageName");
            Objects.requireNonNull(shortcutIds, "shortcutIds");

            List<ShortcutInfo> changedShortcuts = null;
            List<ShortcutInfo> removedShortcuts = null;
            final ShortcutPackage sp;
            synchronized (mServiceLock) {
                throwIfUserLockedL(userId);
                throwIfUserLockedL(launcherUserId);

                final ShortcutLauncher launcher =
                        getLauncherShortcutsLocked(callingPackage, userId, launcherUserId);
                launcher.attemptToRestoreIfNeededAndSave();

                sp = getUserShortcutsLocked(userId).getPackageShortcutsIfExists(packageName);
                if (sp != null) {
                    // List the shortcuts that are pinned only, these will get removed.
                    removedShortcuts = new ArrayList<>();
                    sp.findAll(removedShortcuts, (ShortcutInfo si) -> si.isVisibleToPublisher()
                                    && si.isPinned() && !si.isCached() && !si.isDynamic()
                                    && !si.isDeclaredInManifest(),
                            ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO,
                            callingPackage, launcherUserId, false);
                }
                // Get list of shortcuts that will get unpinned.
                ArraySet<String> oldPinnedIds = launcher.getPinnedShortcutIds(packageName, userId);

                launcher.pinShortcuts(userId, packageName, shortcutIds, /*forPinRequest=*/ false);

                if (oldPinnedIds != null && removedShortcuts != null) {
                    for (int i = 0; i < removedShortcuts.size(); i++) {
                        oldPinnedIds.remove(removedShortcuts.get(i).getId());
                    }
                }
                changedShortcuts = prepareChangedShortcuts(
                        oldPinnedIds, new ArraySet<>(shortcutIds), removedShortcuts, sp);
            }

            if (sp != null) {
                packageShortcutsChanged(sp, changedShortcuts, removedShortcuts);
            }

            verifyStates();
        }

        @Override
        public void cacheShortcuts(int launcherUserId,
                @NonNull String callingPackage, @NonNull String packageName,
                @NonNull List<String> shortcutIds, int userId, int cacheFlags) {
            updateCachedShortcutsInternal(launcherUserId, callingPackage, packageName, shortcutIds,
                    userId, cacheFlags, /* doCache= */ true);
        }

        @Override
        public void uncacheShortcuts(int launcherUserId,
                @NonNull String callingPackage, @NonNull String packageName,
                @NonNull List<String> shortcutIds, int userId, int cacheFlags) {
            updateCachedShortcutsInternal(launcherUserId, callingPackage, packageName, shortcutIds,
                    userId, cacheFlags, /* doCache= */ false);
        }

        @Override
        public List<ShortcutManager.ShareShortcutInfo> getShareTargets(
                @NonNull String callingPackage, @NonNull IntentFilter intentFilter, int userId) {
            return ShortcutService.this.getShareTargets(
                    callingPackage, intentFilter, userId).getList();
        }

        @Override
        public boolean isSharingShortcut(int callingUserId, @NonNull String callingPackage,
                @NonNull String packageName, @NonNull String shortcutId, int userId,
                @NonNull IntentFilter filter) {
            Preconditions.checkStringNotEmpty(callingPackage, "callingPackage");
            Preconditions.checkStringNotEmpty(packageName, "packageName");
            Preconditions.checkStringNotEmpty(shortcutId, "shortcutId");

            return ShortcutService.this.isSharingShortcut(callingUserId, callingPackage,
                    packageName, shortcutId, userId, filter);
        }

        private void updateCachedShortcutsInternal(int launcherUserId,
                @NonNull String callingPackage, @NonNull String packageName,
                @NonNull List<String> shortcutIds, int userId, int cacheFlags, boolean doCache) {
            // Calling permission must be checked by LauncherAppsImpl.
            Preconditions.checkStringNotEmpty(packageName, "packageName");
            Objects.requireNonNull(shortcutIds, "shortcutIds");
            Preconditions.checkState(
                    (cacheFlags & ShortcutInfo.FLAG_CACHED_ALL) != 0, "invalid cacheFlags");

            List<ShortcutInfo> changedShortcuts = null;
            List<ShortcutInfo> removedShortcuts = null;
            final ShortcutPackage sp;
            synchronized (mServiceLock) {
                throwIfUserLockedL(userId);
                throwIfUserLockedL(launcherUserId);

                final int idSize = shortcutIds.size();
                sp = getUserShortcutsLocked(userId).getPackageShortcutsIfExists(packageName);
                if (idSize == 0 || sp == null) {
                    return;
                }

                for (int i = 0; i < idSize; i++) {
                    final String id = Preconditions.checkStringNotEmpty(shortcutIds.get(i));
                    final ShortcutInfo si = sp.findShortcutById(id);
                    if (si == null || doCache == si.hasFlags(cacheFlags)) {
                        continue;
                    }

                    if (doCache) {
                        if (si.isLongLived()) {
                            si.addFlags(cacheFlags);
                            if (changedShortcuts == null) {
                                changedShortcuts = new ArrayList<>(1);
                            }
                            changedShortcuts.add(si);
                        } else {
                            Log.w(TAG, "Only long lived shortcuts can get cached. Ignoring id "
                                    + si.getId());
                        }
                    } else {
                        ShortcutInfo removed = null;
                        si.clearFlags(cacheFlags);
                        if (!si.isDynamic() && !si.isCached()) {
                            removed = sp.deleteLongLivedWithId(id, /*ignoreInvisible=*/ true);
                        }
                        if (removed == null) {
                            if (changedShortcuts == null) {
                                changedShortcuts = new ArrayList<>(1);
                            }
                            changedShortcuts.add(si);
                        } else {
                            if (removedShortcuts == null) {
                                removedShortcuts = new ArrayList<>(1);
                            }
                            removedShortcuts.add(removed);
                        }
                    }
                }
            }
            packageShortcutsChanged(sp, changedShortcuts, removedShortcuts);

            verifyStates();
        }

        @Override
        public Intent[] createShortcutIntents(int launcherUserId,
                @NonNull String callingPackage,
                @NonNull String packageName, @NonNull String shortcutId, int userId,
                int callingPid, int callingUid) {
            // Calling permission must be checked by LauncherAppsImpl.
            Preconditions.checkStringNotEmpty(packageName, "packageName can't be empty");
            Preconditions.checkStringNotEmpty(shortcutId, "shortcutId can't be empty");

            synchronized (mServiceLock) {
                throwIfUserLockedL(userId);
                throwIfUserLockedL(launcherUserId);

                getLauncherShortcutsLocked(callingPackage, userId, launcherUserId)
                        .attemptToRestoreIfNeededAndSave();

                final boolean getPinnedByAnyLauncher =
                        canSeeAnyPinnedShortcut(callingPackage, launcherUserId,
                                callingPid, callingUid);

                // Make sure the shortcut is actually visible to the launcher.
                final ShortcutInfo si = getShortcutInfoLocked(
                        launcherUserId, callingPackage, packageName, shortcutId, userId,
                        getPinnedByAnyLauncher);
                // "si == null" should suffice here, but check the flags too just to make sure.
                if (si == null || !si.isEnabled() || !(si.isAlive() || getPinnedByAnyLauncher)) {
                    Log.e(TAG, "Shortcut " + shortcutId + " does not exist or disabled");
                    return null;
                }
                return si.getIntents();
            }
        }

        @Override
        public void createShortcutIntentsAsync(int launcherUserId,
                @NonNull String callingPackage, @NonNull String packageName,
                @NonNull String shortcutId, int userId, int callingPid,
                int callingUid, @NonNull AndroidFuture<Intent[]> cb) {
            // Calling permission must be checked by LauncherAppsImpl.
            Preconditions.checkStringNotEmpty(packageName, "packageName can't be empty");
            Preconditions.checkStringNotEmpty(shortcutId, "shortcutId can't be empty");

            // Check in memory shortcut first
            synchronized (mServiceLock) {
                throwIfUserLockedL(userId);
                throwIfUserLockedL(launcherUserId);

                getLauncherShortcutsLocked(callingPackage, userId, launcherUserId)
                        .attemptToRestoreIfNeededAndSave();

                final boolean getPinnedByAnyLauncher =
                        canSeeAnyPinnedShortcut(callingPackage, launcherUserId,
                                callingPid, callingUid);

                // Make sure the shortcut is actually visible to the launcher.
                final ShortcutInfo si = getShortcutInfoLocked(
                        launcherUserId, callingPackage, packageName, shortcutId, userId,
                        getPinnedByAnyLauncher);
                if (si != null) {
                    if (!si.isEnabled() || !(si.isAlive() || getPinnedByAnyLauncher)) {
                        Log.e(TAG, "Shortcut " + shortcutId + " does not exist or disabled");
                        cb.complete(null);
                        return;
                    }
                    cb.complete(si.getIntents());
                    return;
                }
            }

            // Otherwise check persisted shortcuts
            getShortcutInfoAsync(launcherUserId, packageName, shortcutId, userId, si -> {
                cb.complete(si == null ? null : si.getIntents());
            });
        }

        @Override
        public void addListener(@NonNull ShortcutChangeListener listener) {
            synchronized (mListeners) {
                mListeners.add(Objects.requireNonNull(listener));
            }
        }

        @Override
        public void addShortcutChangeCallback(
                @NonNull LauncherApps.ShortcutChangeCallback callback) {
            synchronized (mShortcutChangeCallbacks) {
                mShortcutChangeCallbacks.add(Objects.requireNonNull(callback));
            }
        }

        @Override
        public int getShortcutIconResId(int launcherUserId, @NonNull String callingPackage,
                @NonNull String packageName, @NonNull String shortcutId, int userId) {
            Objects.requireNonNull(callingPackage, "callingPackage");
            Objects.requireNonNull(packageName, "packageName");
            Objects.requireNonNull(shortcutId, "shortcutId");

            synchronized (mServiceLock) {
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
        @Nullable
        public String getShortcutStartingThemeResName(int launcherUserId,
                @NonNull String callingPackage, @NonNull String packageName,
                @NonNull String shortcutId, int userId) {
            Objects.requireNonNull(callingPackage, "callingPackage");
            Objects.requireNonNull(packageName, "packageName");
            Objects.requireNonNull(shortcutId, "shortcutId");

            synchronized (mServiceLock) {
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
                return shortcutInfo != null ? shortcutInfo.getStartingThemeResName() : null;
            }
        }

        @Override
        public ParcelFileDescriptor getShortcutIconFd(int launcherUserId,
                @NonNull String callingPackage, @NonNull String packageName,
                @NonNull String shortcutId, int userId) {
            Objects.requireNonNull(callingPackage, "callingPackage");
            Objects.requireNonNull(packageName, "packageName");
            Objects.requireNonNull(shortcutId, "shortcutId");

            synchronized (mServiceLock) {
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
                if (shortcutInfo == null) {
                    return null;
                }
                return getShortcutIconParcelFileDescriptor(p, shortcutInfo);
            }
        }

        @Override
        public void getShortcutIconFdAsync(int launcherUserId, @NonNull String callingPackage,
                @NonNull String packageName, @NonNull String shortcutId, int userId,
                @NonNull AndroidFuture<ParcelFileDescriptor> cb) {
            Objects.requireNonNull(callingPackage, "callingPackage");
            Objects.requireNonNull(packageName, "packageName");
            Objects.requireNonNull(shortcutId, "shortcutId");

            // Checks shortcuts in memory first
            final ShortcutPackage p;
            synchronized (mServiceLock) {
                throwIfUserLockedL(userId);
                throwIfUserLockedL(launcherUserId);

                getLauncherShortcutsLocked(callingPackage, userId, launcherUserId)
                        .attemptToRestoreIfNeededAndSave();

                p = getUserShortcutsLocked(userId).getPackageShortcutsIfExists(packageName);
                if (p == null) {
                    cb.complete(null);
                    return;
                }

                final ShortcutInfo shortcutInfo = p.findShortcutById(shortcutId);
                if (shortcutInfo != null) {
                    cb.complete(getShortcutIconParcelFileDescriptor(p, shortcutInfo));
                    return;
                }
            }

            // Otherwise check persisted shortcuts
            getShortcutInfoAsync(launcherUserId, packageName, shortcutId, userId, si ->
                    cb.complete(getShortcutIconParcelFileDescriptor(p, si)));
        }

        @Nullable
        private ParcelFileDescriptor getShortcutIconParcelFileDescriptor(
                @Nullable final ShortcutPackage p, @Nullable final ShortcutInfo shortcutInfo) {
            if (p == null || shortcutInfo == null || !shortcutInfo.hasIconFile()) {
                return null;
            }
            final String path = p.getBitmapPathMayWait(shortcutInfo);
            if (path == null) {
                Slog.w(TAG, "null bitmap detected in getShortcutIconFd()");
                return null;
            }
            try {
                return ParcelFileDescriptor.open(
                        new File(path),
                        ParcelFileDescriptor.MODE_READ_ONLY);
            } catch (FileNotFoundException e) {
                Slog.e(TAG, "Icon file not found: " + path);
                return null;
            }
        }

        @Override
        public String getShortcutIconUri(int launcherUserId, @NonNull String launcherPackage,
                @NonNull String packageName, @NonNull String shortcutId, int userId) {
            Objects.requireNonNull(launcherPackage, "launcherPackage");
            Objects.requireNonNull(packageName, "packageName");
            Objects.requireNonNull(shortcutId, "shortcutId");

            synchronized (mServiceLock) {
                throwIfUserLockedL(userId);
                throwIfUserLockedL(launcherUserId);

                getLauncherShortcutsLocked(launcherPackage, userId, launcherUserId)
                        .attemptToRestoreIfNeededAndSave();

                final ShortcutPackage p = getUserShortcutsLocked(userId)
                        .getPackageShortcutsIfExists(packageName);
                if (p == null) {
                    return null;
                }

                final ShortcutInfo shortcutInfo = p.findShortcutById(shortcutId);
                if (shortcutInfo == null) {
                    return null;
                }
                return getShortcutIconUriInternal(launcherUserId, launcherPackage,
                        packageName, shortcutInfo, userId);
            }
        }

        @Override
        public void getShortcutIconUriAsync(int launcherUserId, @NonNull String launcherPackage,
                @NonNull String packageName, @NonNull String shortcutId, int userId,
                @NonNull AndroidFuture<String> cb) {
            Objects.requireNonNull(launcherPackage, "launcherPackage");
            Objects.requireNonNull(packageName, "packageName");
            Objects.requireNonNull(shortcutId, "shortcutId");

            // Checks shortcuts in memory first
            synchronized (mServiceLock) {
                throwIfUserLockedL(userId);
                throwIfUserLockedL(launcherUserId);

                getLauncherShortcutsLocked(launcherPackage, userId, launcherUserId)
                        .attemptToRestoreIfNeededAndSave();

                final ShortcutPackage p = getUserShortcutsLocked(userId)
                        .getPackageShortcutsIfExists(packageName);
                if (p == null) {
                    cb.complete(null);
                    return;
                }

                final ShortcutInfo shortcutInfo = p.findShortcutById(shortcutId);
                if (shortcutInfo != null) {
                    cb.complete(getShortcutIconUriInternal(launcherUserId, launcherPackage,
                            packageName, shortcutInfo, userId));
                    return;
                }
            }

            // Otherwise check persisted shortcuts
            getShortcutInfoAsync(launcherUserId, packageName, shortcutId, userId, si -> {
                cb.complete(si == null ? null : getShortcutIconUriInternal(launcherUserId,
                        launcherPackage, packageName, si, userId));
            });
        }

        private String getShortcutIconUriInternal(int launcherUserId,
                @NonNull String launcherPackage, @NonNull String packageName,
                @NonNull ShortcutInfo shortcutInfo, int userId) {
            if (!shortcutInfo.hasIconUri()) {
                return null;
            }
            String uri = shortcutInfo.getIconUri();
            if (uri == null) {
                Slog.w(TAG, "null uri detected in getShortcutIconUri()");
                return null;
            }

            final long token = Binder.clearCallingIdentity();
            try {
                int packageUid = mPackageManagerInternal.getPackageUid(packageName,
                        PackageManager.MATCH_DIRECT_BOOT_AUTO, userId);
                // Grant read uri permission to the caller on behalf of the shortcut owner. All
                // granted permissions are revoked when the default launcher changes, or when
                // device is rebooted.
                mUriGrantsManager.grantUriPermissionFromOwner(mUriPermissionOwner, packageUid,
                        launcherPackage, Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        userId, launcherUserId);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to grant uri access to " + launcherPackage + " for " + uri,
                        e);
                uri = null;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return uri;
        }

        @Override
        public boolean hasShortcutHostPermission(int launcherUserId,
                @NonNull String callingPackage, int callingPid, int callingUid) {
            return ShortcutService.this.hasShortcutHostPermission(callingPackage, launcherUserId,
                    callingPid, callingUid);
        }

        public boolean areShortcutsSupportedOnHomeScreen(@UserIdInt int userId) {
            return ShortcutService.this.areShortcutsSupportedOnHomeScreen(userId);
        }

        @Override
        public void setShortcutHostPackage(@NonNull String type, @Nullable String packageName,
                int userId) {
            ShortcutService.this.setShortcutHostPackage(type, packageName, userId);
        }

        @Override
        public boolean requestPinAppWidget(@NonNull String callingPackage,
                @NonNull AppWidgetProviderInfo appWidget, @Nullable Bundle extras,
                @Nullable IntentSender resultIntent, int userId) {
            Objects.requireNonNull(appWidget);
            return requestPinItem(callingPackage, userId, null, appWidget, extras, resultIntent);
        }

        @Override
        public boolean isRequestPinItemSupported(int callingUserId, int requestType) {
            return ShortcutService.this.isRequestPinItemSupported(callingUserId, requestType);
        }

        @Override
        public boolean isForegroundDefaultLauncher(@NonNull String callingPackage, int callingUid) {
            Objects.requireNonNull(callingPackage);

            final int userId = UserHandle.getUserId(callingUid);
            final String defaultLauncher = getDefaultLauncher(userId);
            if (defaultLauncher == null) {
                return false;
            }
            if (!callingPackage.equals(defaultLauncher)) {
                return false;
            }
            synchronized (mServiceLock) {
                if (!isUidForegroundLocked(callingUid)) {
                    return false;
                }
            }
            return true;
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

        synchronized (mServiceLock) {
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
                synchronized (mServiceLock) {
                    if (!isUserUnlockedL(userId)) {
                        if (DEBUG) {
                            Slog.d(TAG, "Ignoring package broadcast " + action
                                    + " for locked/stopped user " + userId);
                        }
                        return;
                    }
                }

                final Uri intentUri = intent.getData();
                final String packageName = (intentUri != null) ? intentUri.getSchemeSpecificPart()
                        : null;
                if (packageName == null) {
                    Slog.w(TAG, "Intent broadcast does not contain package name: " + intent);
                    return;
                }

                final boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                final boolean archival = intent.getBooleanExtra(Intent.EXTRA_ARCHIVAL, false);

                Slog.d(TAG, "received package broadcast intent: " + intent);
                switch (action) {
                    case Intent.ACTION_PACKAGE_ADDED:
                        if (replacing) {
                            Slog.d(TAG, "replacing package: " + packageName + " userId" + userId);
                            handlePackageUpdateFinished(packageName, userId);
                        } else {
                            Slog.d(TAG, "adding package: " + packageName + " userId" + userId);
                            handlePackageAdded(packageName, userId);
                        }
                        break;
                    case Intent.ACTION_PACKAGE_REMOVED:
                        if (!replacing || (replacing && archival)) {
                            if (!replacing) {
                                Slog.d(TAG, "removing package: "
                                        + packageName + " userId" + userId);
                            } else if (archival) {
                                Slog.d(TAG, "archiving package: "
                                        + packageName + " userId" + userId);
                            }
                            handlePackageRemoved(packageName, userId);
                        }
                        break;
                    case Intent.ACTION_PACKAGE_CHANGED:
                        Slog.d(TAG, "changing package: " + packageName + " userId" + userId);
                        handlePackageChanged(packageName, userId);
                        break;
                    case Intent.ACTION_PACKAGE_DATA_CLEARED:
                        Slog.d(TAG, "clearing data for package: "
                                + packageName + " userId" + userId);
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

    private final BroadcastReceiver mShutdownReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG || DEBUG_REBOOT) {
                Slog.d(TAG, "Shutdown broadcast received.");
            }
            // Since it cleans up the shortcut directory and rewrite the ShortcutPackageItems
            // in odrder during saveToXml(), it could lead to shortcuts missing when shutdown.
            // We need it so that it can finish up saving before shutdown.
            synchronized (mServiceLock) {
                if (mHandler.hasCallbacks(mSaveDirtyInfoRunner)) {
                    mHandler.removeCallbacks(mSaveDirtyInfoRunner);
                    forEachLoadedUserLocked(ShortcutUser::cancelAllInFlightTasks);
                    saveDirtyInfo();
                }
                mShutdown.set(true);
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
        if (DEBUG || DEBUG_REBOOT) {
            Slog.d(TAG, "checkPackageChanges() ownerUserId=" + ownerUserId);
        }
        if (injectIsSafeModeEnabled()) {
            Slog.i(TAG, "Safe mode, skipping checkPackageChanges()");
            return;
        }

        final long start = getStatStartTime();
        try {
            final ArrayList<UserPackage> gonePackages = new ArrayList<>();

            synchronized (mServiceLock) {
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
                        gonePackages.add(
                                UserPackage.of(spi.getPackageUserId(), spi.getPackageName()));
                    }
                });
                if (gonePackages.size() > 0) {
                    for (int i = gonePackages.size() - 1; i >= 0; i--) {
                        final UserPackage up = gonePackages.get(i);
                        cleanUpPackageLocked(up.packageName, ownerUserId, up.userId,
                                /* appStillExists = */ false);
                    }
                }

                rescanUpdatedPackagesLocked(ownerUserId, user.getLastAppScanTime());
            }
        } finally {
            logDurationStat(Stats.CHECK_PACKAGE_CHANGES, start);
        }
        verifyStates();
    }

    @GuardedBy("mServiceLock")
    private void rescanUpdatedPackagesLocked(@UserIdInt int userId, long lastScanTime) {
        if (DEBUG_REBOOT) {
            Slog.d(TAG, "rescan updated package user=" + userId + " last scanned=" + lastScanTime);
        }
        final ShortcutUser user = getUserShortcutsLocked(userId);

        // Note after each OTA, we'll need to rescan all system apps, as their lastUpdateTime
        // is not reliable.
        final long now = injectCurrentTimeMillis();
        final boolean afterOta =
                !injectBuildFingerprint().equals(user.getLastAppScanOsFingerprint());

        // Then for each installed app, publish manifest shortcuts when needed.
        forUpdatedPackages(userId, lastScanTime, afterOta, ai -> {
            user.attemptToRestoreIfNeededAndSave(this, ai.packageName, userId);

            user.rescanPackageIfNeeded(ai.packageName, /* forceRescan= */ true);
        });

        // Write the time just before the scan, because there may be apps that have just
        // been updated, and we want to catch them in the next time.
        user.setLastAppScanTime(now);
        user.setLastAppScanOsFingerprint(injectBuildFingerprint());
        scheduleSaveUser(userId);
    }

    private void handlePackageAdded(String packageName, @UserIdInt int userId) {
        if (DEBUG || DEBUG_REBOOT) {
            Slog.d(TAG, String.format("handlePackageAdded: %s user=%d", packageName, userId));
        }
        synchronized (mServiceLock) {
            final ShortcutUser user = getUserShortcutsLocked(userId);
            user.attemptToRestoreIfNeededAndSave(this, packageName, userId);
            user.rescanPackageIfNeeded(packageName, /* forceRescan=*/ true);
        }
        verifyStates();
    }

    private void handlePackageUpdateFinished(String packageName, @UserIdInt int userId) {
        if (DEBUG || DEBUG_REBOOT) {
            Slog.d(TAG, String.format("handlePackageUpdateFinished: %s user=%d",
                    packageName, userId));
        }
        synchronized (mServiceLock) {
            final ShortcutUser user = getUserShortcutsLocked(userId);
            user.attemptToRestoreIfNeededAndSave(this, packageName, userId);

            if (isPackageInstalled(packageName, userId)) {
                user.rescanPackageIfNeeded(packageName, /* forceRescan=*/ true);
            }
        }
        verifyStates();
    }

    private void handlePackageRemoved(String packageName, @UserIdInt int packageUserId) {
        if (DEBUG || DEBUG_REBOOT) {
            Slog.d(TAG, String.format("handlePackageRemoved: %s user=%d", packageName,
                    packageUserId));
        }
        cleanUpPackageForAllLoadedUsers(packageName, packageUserId, /* appStillExists = */ false);

        verifyStates();
    }

    private void handlePackageDataCleared(String packageName, int packageUserId) {
        if (DEBUG || DEBUG_REBOOT) {
            Slog.d(TAG, String.format("handlePackageDataCleared: %s user=%d", packageName,
                    packageUserId));
        }
        cleanUpPackageForAllLoadedUsers(packageName, packageUserId, /* appStillExists = */ true);

        verifyStates();
    }

    private void handlePackageChanged(String packageName, int packageUserId) {
        if (!isPackageInstalled(packageName, packageUserId)) {
            // Probably disabled, which is the same thing as uninstalled.
            handlePackageRemoved(packageName, packageUserId);
            return;
        }
        if (DEBUG || DEBUG_REBOOT) {
            Slog.d(TAG, String.format("handlePackageChanged: %s user=%d", packageName,
                    packageUserId));
        }

        // Activities may be disabled or enabled.  Just rescan the package.
        synchronized (mServiceLock) {
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
        final long start = getStatStartTime();
        final long token = injectClearCallingIdentity();
        try {
            return mIPackageManager.getPackageInfo(packageName, PACKAGE_MATCH_FLAGS
                    | (getSignatures ? PackageManager.GET_SIGNING_CERTIFICATES : 0), userId);
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
        final long start = getStatStartTime();
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
        final long start = getStatStartTime();
        final long token = injectClearCallingIdentity();
        try {
            return mIPackageManager.getActivityInfo(activity,
                    PACKAGE_MATCH_FLAGS | PackageManager.GET_META_DATA, userId);
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
        final long start = getStatStartTime();
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

    private void forUpdatedPackages(@UserIdInt int userId, long lastScanTime, boolean afterOta,
            Consumer<ApplicationInfo> callback) {
        if (DEBUG || DEBUG_REBOOT) {
            Slog.d(TAG, "forUpdatedPackages for user " + userId + ", lastScanTime=" + lastScanTime
                    + " afterOta=" + afterOta);
        }
        final List<PackageInfo> list = getInstalledPackages(userId);
        for (int i = list.size() - 1; i >= 0; i--) {
            final PackageInfo pi = list.get(i);

            // If the package has been updated since the last scan time, then scan it.
            // Also if it's right after an OTA, always re-scan all apps anyway, since the
            // shortcut parser might have changed.
            if (afterOta || (pi.lastUpdateTime >= lastScanTime)) {
                if (DEBUG || DEBUG_REBOOT) {
                    Slog.d(TAG, "Found updated package " + pi.packageName
                            + " updateTime=" + pi.lastUpdateTime);
                }
                callback.accept(pi.applicationInfo);
            }
        }
    }

    private boolean isApplicationFlagSet(@NonNull String packageName, int userId, int flags) {
        final ApplicationInfo ai = injectApplicationInfoWithUninstalled(packageName, userId);
        return (ai != null) && ((ai.flags & flags) == flags);
    }

    // Due to b/38267327, ActivityInfo.enabled may not reflect the current state of the component
    // and we need to check the enabled state via PackageManager.getComponentEnabledSetting.
    private boolean isEnabled(@Nullable ActivityInfo ai, int userId) {
        if (ai == null) {
            return false;
        }

        int enabledFlag;
        final long token = injectClearCallingIdentity();
        try {
            enabledFlag = mIPackageManager.getComponentEnabledSetting(
                    ai.getComponentName(), userId);
        } catch (RemoteException e) {
            // Shouldn't happen.
            Slog.wtf(TAG, "RemoteException", e);
            return false;
        } finally {
            injectRestoreCallingIdentity(token);
        }

        if ((enabledFlag == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && ai.enabled)
                || enabledFlag == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            return true;
        }
        return false;
    }

    private static boolean isSystem(@Nullable ActivityInfo ai) {
        return (ai != null) && isSystem(ai.applicationInfo);
    }

    private static boolean isSystem(@Nullable ApplicationInfo ai) {
        return (ai != null) && (ai.flags & SYSTEM_APP_MASK) != 0;
    }

    private static boolean isInstalled(@Nullable ApplicationInfo ai) {
        return (ai != null) && ai.enabled && (ai.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
    }

    private static boolean isEphemeralApp(@Nullable ApplicationInfo ai) {
        return (ai != null) && ai.isInstantApp();
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

    boolean isEphemeralApp(String packageName, int userId) {
        return isEphemeralApp(getApplicationInfo(packageName, userId));
    }

    @Nullable
    XmlResourceParser injectXmlMetaData(ActivityInfo activityInfo, String key) {
        return activityInfo.loadXmlMetaData(mContext.getPackageManager(), key);
    }

    @Nullable
    Resources injectGetResourcesForApplicationAsUser(String packageName, int userId) {
        final long start = getStatStartTime();
        final long token = injectClearCallingIdentity();
        try {
            return mContext.createContextAsUser(UserHandle.of(userId), /* flags */ 0)
                    .getPackageManager().getResourcesForApplication(packageName);
        } catch (NameNotFoundException e) {
            Slog.e(TAG, "Resources of package " + packageName + " for user " + userId
                    + " not found");
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

        baseIntent.setPackage(Objects.requireNonNull(packageName));
        if (activity != null) {
            baseIntent.setComponent(activity);
        }
        return queryActivities(baseIntent, userId, /* exportedOnly =*/ true);
    }

    @NonNull
    List<ResolveInfo> queryActivities(@NonNull Intent intent, int userId,
            boolean exportedOnly) {
        final List<ResolveInfo> resolved;
        final long token = injectClearCallingIdentity();
        try {
            resolved = mContext.getPackageManager().queryIntentActivitiesAsUser(intent,
                    PACKAGE_MATCH_FLAGS | PackageManager.MATCH_DISABLED_COMPONENTS, userId);
        } finally {
            injectRestoreCallingIdentity(token);
        }
        if (resolved == null || resolved.size() == 0) {
            return EMPTY_RESOLVE_INFO;
        }
        // Make sure the package is installed.
        resolved.removeIf(ACTIVITY_NOT_INSTALLED);
        resolved.removeIf((ri) -> {
            final ActivityInfo ai = ri.activityInfo;
            return !isSystem(ai) && !isEnabled(ai, userId);
        });
        if (exportedOnly) {
            resolved.removeIf(ACTIVITY_NOT_EXPORTED);
        }
        return resolved;
    }

    /**
     * Return the main activity that is exported and, for non-system apps, enabled.  If multiple
     * activities are found, return the first one.
     */
    @Nullable
    ComponentName injectGetDefaultMainActivity(@NonNull String packageName, int userId) {
        final long start = getStatStartTime();
        try {
            final List<ResolveInfo> resolved =
                    queryActivities(getMainActivityIntent(), packageName, null, userId);
            return resolved.size() == 0 ? null : resolved.get(0).activityInfo.getComponentName();
        } finally {
            logDurationStat(Stats.GET_LAUNCHER_ACTIVITY, start);
        }
    }

    /**
     * Return whether an activity is main, exported and, for non-system apps, enabled.
     */
    boolean injectIsMainActivity(@NonNull ComponentName activity, int userId) {
        final long start = getStatStartTime();
        try {
            if (activity == null) {
                wtf("null activity detected");
                return false;
            }
            if (DUMMY_MAIN_ACTIVITY.equals(activity.getClassName())) {
                return true;
            }
            final List<ResolveInfo> resolved = queryActivities(
                    getMainActivityIntent(), activity.getPackageName(), activity, userId);
            return resolved.size() > 0;
        } finally {
            logDurationStat(Stats.CHECK_LAUNCHER_ACTIVITY, start);
        }
    }

    /**
     * Create a placeholder "main activity" component name which is used to create a dynamic shortcut
     * with no main activity temporarily.
     */
    @NonNull
    ComponentName getDummyMainActivity(@NonNull String packageName) {
        return new ComponentName(packageName, DUMMY_MAIN_ACTIVITY);
    }

    boolean isDummyMainActivity(@Nullable ComponentName name) {
        return name != null && DUMMY_MAIN_ACTIVITY.equals(name.getClassName());
    }

    /**
     * Return all the main activities that are exported and, for non-system apps, enabled, from a
     * package.
     */
    @NonNull
    List<ResolveInfo> injectGetMainActivities(@NonNull String packageName, int userId) {
        final long start = getStatStartTime();
        try {
            return queryActivities(getMainActivityIntent(), packageName, null, userId);
        } finally {
            logDurationStat(Stats.CHECK_LAUNCHER_ACTIVITY, start);
        }
    }

    /**
     * Return whether an activity is enabled and exported.
     */
    @VisibleForTesting
    boolean injectIsActivityEnabledAndExported(
            @NonNull ComponentName activity, @UserIdInt int userId) {
        final long start = getStatStartTime();
        try {
            return queryActivities(new Intent(), activity.getPackageName(), activity, userId)
                    .size() > 0;
        } finally {
            logDurationStat(Stats.IS_ACTIVITY_ENABLED, start);
        }
    }

    /**
     * Get the {@link LauncherApps#ACTION_CONFIRM_PIN_SHORTCUT} or
     * {@link LauncherApps#ACTION_CONFIRM_PIN_APPWIDGET} activity in a given package depending on
     * the requestType.
     */
    @Nullable
    ComponentName injectGetPinConfirmationActivity(@NonNull String launcherPackageName,
            int launcherUserId, int requestType) {
        Objects.requireNonNull(launcherPackageName);
        String action = requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT ?
                LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT :
                LauncherApps.ACTION_CONFIRM_PIN_APPWIDGET;

        final Intent confirmIntent = new Intent(action).setPackage(launcherPackageName);
        final List<ResolveInfo> candidates = queryActivities(
                confirmIntent, launcherUserId, /* exportedOnly =*/ false);
        for (ResolveInfo ri : candidates) {
            return ri.activityInfo.getComponentName();
        }
        return null;
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

    /**
     * If {@code userId} is of a managed profile, return the parent user ID.  Otherwise return
     * itself.
     */
    int getParentOrSelfUserId(int userId) {
        return mUserManagerInternal.getProfileParentId(userId);
    }

    void injectSendIntentSender(IntentSender intentSender, Intent extras) {
        if (intentSender == null) {
            return;
        }
        try {
            ActivityOptions options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                            MODE_BACKGROUND_ACTIVITY_START_DENIED);
            intentSender.sendIntent(mContext, /* code= */ 0, extras,
                    /* onFinished=*/ null, /* handler= */ null, null, options.toBundle());
        } catch (SendIntentException e) {
            Slog.w(TAG, "sendIntent failed().", e);
        }
    }

    // === Backup & restore ===

    boolean shouldBackupApp(String packageName, int userId) {
        return isApplicationFlagSet(packageName, userId, ApplicationInfo.FLAG_ALLOW_BACKUP);
    }

    static boolean shouldBackupApp(PackageInfo pi) {
        return (pi.applicationInfo.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0;
    }

    @Override
    public byte[] getBackupPayload(@UserIdInt int userId) {
        enforceSystem();
        if (DEBUG) {
            Slog.d(TAG, "Backing up user " + userId);
        }
        synchronized (mServiceLock) {
            if (!isUserUnlockedL(userId)) {
                wtf("Can't backup: user " + userId + " is locked or not running");
                return null;
            }

            final ShortcutUser user = getUserShortcutsLocked(userId);
            if (user == null) {
                wtf("Can't backup: user not found: id=" + userId);
                return null;
            }

            // Update the signatures for all packages.
            user.forAllPackageItems(spi -> spi.refreshPackageSignatureAndSave());

            // Rescan all apps; this will also update the version codes and "allow-backup".
            user.forAllPackages(pkg -> pkg.rescanPackageIfNeeded(
                    /*isNewApp=*/ false, /*forceRescan=*/ true));

            // Set the version code for the launchers.
            user.forAllLaunchers(launcher -> launcher.ensurePackageInfo());

            // Save to the filesystem.
            scheduleSaveUser(userId);
            saveDirtyInfo();

            // Note, in case of backup, we don't have to wait on bitmap saving, because we don't
            // back up bitmaps anyway.

            // Then create the backup payload.
            final ByteArrayOutputStream os = new ByteArrayOutputStream(32 * 1024);
            try {
                saveUserInternalLocked(userId, os, /* forBackup */ true);
            } catch (XmlPullParserException | IOException e) {
                // Shouldn't happen.
                Slog.w(TAG, "Backup failed.", e);
                return null;
            }
            byte[] payload = os.toByteArray();
            mShortcutDumpFiles.save("backup-1-payload.txt", payload);
            return payload;
        }
    }

    @Override
    public void applyRestore(byte[] payload, @UserIdInt int userId) {
        enforceSystem();
        if (DEBUG || DEBUG_REBOOT) {
            Slog.d(TAG, "Restoring user " + userId);
        }
        synchronized (mServiceLock) {
            if (!isUserUnlockedL(userId)) {
                wtf("Can't restore: user " + userId + " is locked or not running");
                return;
            }
            // Note we print the file timestamps in dumpsys too, but also printing the timestamp
            // in the files anyway.
            mShortcutDumpFiles.save("restore-0-start.txt", pw -> {
                pw.print("Start time: ");
                dumpCurrentTime(pw);
                pw.println();
            });
            mShortcutDumpFiles.save("restore-1-payload.xml", payload);
            // Actually do restore.
            final ShortcutUser restored;
            final ByteArrayInputStream is = new ByteArrayInputStream(payload);
            try {
                restored = loadUserInternal(userId, is, /* fromBackup */ true);
            } catch (XmlPullParserException | IOException | InvalidFileFormatException e) {
                Slog.w(TAG, "Restoration failed.", e);
                return;
            }
            mShortcutDumpFiles.save("restore-2.txt", this::dumpInner);
            getUserShortcutsLocked(userId).mergeRestoredFile(restored);
            mShortcutDumpFiles.save("restore-3.txt", this::dumpInner);
            // Rescan all packages to re-publish manifest shortcuts and do other checks.
            rescanUpdatedPackagesLocked(userId,
                    0 // lastScanTime = 0; rescan all packages.
            );
            mShortcutDumpFiles.save("restore-4.txt", this::dumpInner);
            mShortcutDumpFiles.save("restore-5-finish.txt", pw -> {
                pw.print("Finish time: ");
                dumpCurrentTime(pw);
                pw.println();
            });
        }
        saveUser(userId);
    }

    // === Dump ===

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw)) return;
        dumpNoCheck(fd, pw, args);
    }

    @VisibleForTesting
    void dumpNoCheck(FileDescriptor fd, PrintWriter pw, String[] args) {
        final DumpFilter filter = parseDumpArgs(args);

        if (filter.shouldDumpCheckIn()) {
            // Other flags are not supported for checkin.
            dumpCheckin(pw, filter.shouldCheckInClear());
        } else {
            if (filter.shouldDumpMain()) {
                dumpInner(pw, filter);
                pw.println();
            }
            if (filter.shouldDumpUid()) {
                dumpUid(pw);
                pw.println();
            }
            if (filter.shouldDumpFiles()) {
                dumpDumpFiles(pw);
                pw.println();
            }
        }
    }

    private static DumpFilter parseDumpArgs(String[] args) {
        final DumpFilter filter = new DumpFilter();
        if (args == null) {
            return filter;
        }

        int argIndex = 0;
        while (argIndex < args.length) {
            final String arg = args[argIndex++];

            if ("-c".equals(arg)) {
                filter.setDumpCheckIn(true);
                continue;
            }
            if ("--checkin".equals(arg)) {
                filter.setDumpCheckIn(true);
                filter.setCheckInClear(true);
                continue;
            }
            if ("-a".equals(arg) || "--all".equals(arg)) {
                filter.setDumpUid(true);
                filter.setDumpFiles(true);
                continue;
            }
            if ("-u".equals(arg) || "--uid".equals(arg)) {
                filter.setDumpUid(true);
                continue;
            }
            if ("-f".equals(arg) || "--files".equals(arg)) {
                filter.setDumpFiles(true);
                continue;
            }
            if ("-n".equals(arg) || "--no-main".equals(arg)) {
                filter.setDumpMain(false);
                continue;
            }
            if ("--user".equals(arg)) {
                if (argIndex >= args.length) {
                    throw new IllegalArgumentException("Missing user ID for --user");
                }
                try {
                    filter.addUser(Integer.parseInt(args[argIndex++]));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid user ID", e);
                }
                continue;
            }
            if ("-p".equals(arg) || "--package".equals(arg)) {
                if (argIndex >= args.length) {
                    throw new IllegalArgumentException("Missing package name for --package");
                }
                filter.addPackageRegex(args[argIndex++]);
                filter.setDumpDetails(false);
                continue;
            }
            if (arg.startsWith("-")) {
                throw new IllegalArgumentException("Unknown option " + arg);
            }
            break;
        }
        while (argIndex < args.length) {
            filter.addPackage(args[argIndex++]);
        }
        return filter;
    }

    static class DumpFilter {
        private boolean mDumpCheckIn = false;
        private boolean mCheckInClear = false;

        private boolean mDumpMain = true;
        private boolean mDumpUid = false;
        private boolean mDumpFiles = false;

        private boolean mDumpDetails = true;
        private final List<Pattern> mPackagePatterns = new ArrayList<>();
        private final List<Integer> mUsers = new ArrayList<>();

        void addPackageRegex(String regex) {
            mPackagePatterns.add(Pattern.compile(regex));
        }

        public void addPackage(String packageName) {
            addPackageRegex(Pattern.quote(packageName));
        }

        void addUser(int userId) {
            mUsers.add(userId);
        }

        boolean isPackageMatch(String packageName) {
            if (mPackagePatterns.size() == 0) {
                return true;
            }
            for (int i = 0; i < mPackagePatterns.size(); i++) {
                if (mPackagePatterns.get(i).matcher(packageName).find()) {
                    return true;
                }
            }
            return false;
        }

        boolean isUserMatch(int userId) {
            if (mUsers.size() == 0) {
                return true;
            }
            for (int i = 0; i < mUsers.size(); i++) {
                if (mUsers.get(i) == userId) {
                    return true;
                }
            }
            return false;
        }

        public boolean shouldDumpCheckIn() {
            return mDumpCheckIn;
        }

        public void setDumpCheckIn(boolean dumpCheckIn) {
            mDumpCheckIn = dumpCheckIn;
        }

        public boolean shouldCheckInClear() {
            return mCheckInClear;
        }

        public void setCheckInClear(boolean checkInClear) {
            mCheckInClear = checkInClear;
        }

        public boolean shouldDumpMain() {
            return mDumpMain;
        }

        public void setDumpMain(boolean dumpMain) {
            mDumpMain = dumpMain;
        }

        public boolean shouldDumpUid() {
            return mDumpUid;
        }

        public void setDumpUid(boolean dumpUid) {
            mDumpUid = dumpUid;
        }

        public boolean shouldDumpFiles() {
            return mDumpFiles;
        }

        public void setDumpFiles(boolean dumpFiles) {
            mDumpFiles = dumpFiles;
        }

        public boolean shouldDumpDetails() {
            return mDumpDetails;
        }

        public void setDumpDetails(boolean dumpDetails) {
            mDumpDetails = dumpDetails;
        }
    }

    private void dumpInner(PrintWriter pw) {
        dumpInner(pw, new DumpFilter());
    }

    private void dumpInner(PrintWriter pw, DumpFilter filter) {
        synchronized (mServiceLock) {
            if (filter.shouldDumpDetails()) {
                final long now = injectCurrentTimeMillis();
                pw.print("Now: [");
                pw.print(now);
                pw.print("] ");
                pw.print(formatTime(now));

                pw.print("  Raw last reset: [");
                pw.print(mRawLastResetTime.get());
                pw.print("] ");
                pw.print(formatTime(mRawLastResetTime.get()));

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
                pw.print("    maxShortcutsPerActivity: ");
                pw.println(mMaxShortcuts);
                pw.println();

                mStatLogger.dump(pw, "  ");

                synchronized (mWtfLock) {
                    pw.println();
                    pw.print("  #Failures: ");
                    pw.println(mWtfCount);

                    if (mLastWtfStacktrace != null) {
                        pw.print("  Last failure stack trace: ");
                        pw.println(Log.getStackTraceString(mLastWtfStacktrace));
                    }
                }

                pw.println();
            }

            for (int i = 0; i < mUsers.size(); i++) {
                final ShortcutUser user = mUsers.valueAt(i);
                if (filter.isUserMatch(user.getUserId())) {
                    user.dump(pw, "  ", filter);
                    pw.println();
                }
            }

            for (int i = 0; i < mShortcutNonPersistentUsers.size(); i++) {
                final ShortcutNonPersistentUser user = mShortcutNonPersistentUsers.valueAt(i);
                if (filter.isUserMatch(user.getUserId())) {
                    user.dump(pw, "  ", filter);
                    pw.println();
                }
            }
        }
    }

    private void dumpUid(PrintWriter pw) {
        synchronized (mServiceLock) {
            pw.println("** SHORTCUT MANAGER UID STATES (dumpsys shortcut -n -u)");

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
        return TimeMigrationUtils.formatMillisWithFixedFormat(time);
    }

    private void dumpCurrentTime(PrintWriter pw) {
        pw.print(formatTime(injectCurrentTimeMillis()));
    }

    /**
     * Dumpsys for checkin.
     *
     * @param clear if true, clear the history information.  Some other system services have this
     * behavior but shortcut service doesn't for now.
     */
    private  void dumpCheckin(PrintWriter pw, boolean clear) {
        synchronized (mServiceLock) {
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

    private void dumpDumpFiles(PrintWriter pw) {
        synchronized (mServiceLock) {
            pw.println("** SHORTCUT MANAGER FILES (dumpsys shortcut -n -f)");
            mShortcutDumpFiles.dumpAll(pw);
        }
    }

    // === Shell support ===

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {

        enforceShell();

        final long token = injectClearCallingIdentity();
        try {
            final int status = (new MyShellCommand()).exec(this, in, out, err, args, callback,
                    resultReceiver);
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

        private int mShortcutMatchFlags = ShortcutManager.FLAG_MATCH_CACHED
                | ShortcutManager.FLAG_MATCH_DYNAMIC | ShortcutManager.FLAG_MATCH_MANIFEST
                | ShortcutManager.FLAG_MATCH_PINNED;

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
                    case "--flags":
                        mShortcutMatchFlags = Integer.parseInt(getNextArgRequired());
                        break;
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
                    case "get-default-launcher":
                        handleGetDefaultLauncher();
                        break;
                    case "unload-user":
                        handleUnloadUser();
                        break;
                    case "clear-shortcuts":
                        handleClearShortcuts();
                        break;
                    case "get-shortcuts":
                        handleGetShortcuts();
                        break;
                    case "verify-states": // hidden command to verify various internal states.
                        handleVerifyStates();
                        break;
                    case "has-shortcut-access":
                        handleHasShortcutAccess();
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
            pw.println("[Deprecated] cmd shortcut get-default-launcher [--user USER_ID]");
            pw.println("    Show the default launcher");
            pw.println("    Note: This command is deprecated. Callers should query the default"
                    + " launcher from RoleManager instead.");
            pw.println();
            pw.println("cmd shortcut unload-user [--user USER_ID]");
            pw.println("    Unload a user from the memory");
            pw.println("    (This should not affect any observable behavior)");
            pw.println();
            pw.println("cmd shortcut clear-shortcuts [--user USER_ID] PACKAGE");
            pw.println("    Remove all shortcuts from a package, including pinned shortcuts");
            pw.println();
            pw.println("cmd shortcut get-shortcuts [--user USER_ID] [--flags FLAGS] PACKAGE");
            pw.println("    Show the shortcuts for a package that match the given flags");
            pw.println();
            pw.println("cmd shortcut has-shortcut-access [--user USER_ID] PACKAGE");
            pw.println("    Prints \"true\" if the package can access shortcuts,"
                    + " \"false\" otherwise");
            pw.println();
        }

        private void handleResetThrottling() throws CommandException {
            synchronized (mServiceLock) {
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

            synchronized (mServiceLock) {
                if (!updateConfigurationLocked(config)) {
                    throw new CommandException("override-config failed.  See logcat for details.");
                }
            }
        }

        private void handleResetConfig() {
            Slog.i(TAG, "cmd: handleResetConfig");

            synchronized (mServiceLock) {
                loadConfigurationLocked();
            }
        }

        // This method is used by various cts modules to get the current default launcher. Tests
        // should query this information directly from RoleManager instead. Keeping the old behavior
        // by returning the result from package manager.
        private void handleGetDefaultLauncher() throws CommandException {
            synchronized (mServiceLock) {
                parseOptionsLocked(/* takeUser =*/ true);

                final String defaultLauncher = getDefaultLauncher(mUserId);
                if (defaultLauncher == null) {
                    throw new CommandException(
                            "Failed to get the default launcher for user " + mUserId);
                }

                // Get the class name of the component from PM to keep the old behaviour.
                final List<ResolveInfo> allHomeCandidates = new ArrayList<>();
                mPackageManagerInternal.getHomeActivitiesAsUser(allHomeCandidates,
                        getParentOrSelfUserId(mUserId));
                for (ResolveInfo ri : allHomeCandidates) {
                    final ComponentInfo ci = ri.getComponentInfo();
                    if (ci.packageName.equals(defaultLauncher)) {
                        getOutPrintWriter().println("Launcher: " + ci.getComponentName());
                        break;
                    }
                }
            }
        }

        private void handleUnloadUser() throws CommandException {
            synchronized (mServiceLock) {
                parseOptionsLocked(/* takeUser =*/ true);

                Slog.i(TAG, "cmd: handleUnloadUser: user=" + mUserId);

                ShortcutService.this.handleStopUser(mUserId);
            }
        }

        private void handleClearShortcuts() throws CommandException {
            synchronized (mServiceLock) {
                parseOptionsLocked(/* takeUser =*/ true);
                final String packageName = getNextArgRequired();

                Slog.i(TAG, "cmd: handleClearShortcuts: user" + mUserId + ", " + packageName);

                ShortcutService.this.cleanUpPackageForAllLoadedUsers(packageName, mUserId,
                        /* appStillExists = */ true);
            }
        }

        private void handleGetShortcuts() throws CommandException {
            synchronized (mServiceLock) {
                parseOptionsLocked(/* takeUser =*/ true);
                final String packageName = getNextArgRequired();

                Slog.i(TAG, "cmd: handleGetShortcuts: user=" + mUserId + ", flags="
                        + mShortcutMatchFlags + ", package=" + packageName);

                final ShortcutUser user = ShortcutService.this.getUserShortcutsLocked(mUserId);
                final ShortcutPackage p = user.getPackageShortcutsIfExists(packageName);
                if (p == null) {
                    return;
                }

                p.dumpShortcuts(getOutPrintWriter(), mShortcutMatchFlags);
            }
        }

        private void handleVerifyStates() throws CommandException {
            try {
                verifyStatesForce(); // This will throw when there's an issue.
            } catch (Throwable th) {
                throw new CommandException(th.getMessage() + "\n" + Log.getStackTraceString(th));
            }
        }

        private void handleHasShortcutAccess() throws CommandException {
            synchronized (mServiceLock) {
                parseOptionsLocked(/* takeUser =*/ true);
                final String packageName = getNextArgRequired();

                boolean shortcutAccess = hasShortcutHostPermissionInner(packageName, mUserId);
                getOutPrintWriter().println(Boolean.toString(shortcutAccess));
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

    @VisibleForTesting
    long injectUptimeMillis() {
        return SystemClock.uptimeMillis();
    }

    // Injection point.
    @VisibleForTesting
    int injectBinderCallingUid() {
        return getCallingUid();
    }

    @VisibleForTesting
    int injectBinderCallingPid() {
        return getCallingPid();
    }

    private int getCallingUserId() {
        return UserHandle.getUserId(injectBinderCallingUid());
    }

    // Injection point.
    long injectClearCallingIdentity() {
        return Binder.clearCallingIdentity();
    }

    // Injection point.
    void injectRestoreCallingIdentity(long token) {
        Binder.restoreCallingIdentity(token);
    }

    // Injection point.
    String injectBuildFingerprint() {
        return Build.FINGERPRINT;
    }

    final void wtf(String message) {
        wtf(message, /* exception= */ null);
    }

    // Injection point.
    void wtf(String message, Throwable e) {
        if (e == null) {
            e = new RuntimeException("Stacktrace");
        }
        synchronized (mWtfLock) {
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

    public File getDumpPath() {
        return new File(injectUserDataPath(UserHandle.USER_SYSTEM), DIRECTORY_DUMP);
    }

    @VisibleForTesting
    boolean injectIsLowRamDevice() {
        return ActivityManager.isLowRamDeviceStatic();
    }

    @VisibleForTesting
    void injectRegisterUidObserver(IUidObserver observer, int which) {
        try {
            ActivityManager.getService().registerUidObserver(observer, which,
                    ActivityManager.PROCESS_STATE_UNKNOWN, null);
        } catch (RemoteException shouldntHappen) {
        }
    }

    @VisibleForTesting
    void injectRegisterRoleHoldersListener(OnRoleHoldersChangedListener listener) {
        mRoleManager.addOnRoleHoldersChangedListenerAsUser(mContext.getMainExecutor(), listener,
                UserHandle.ALL);
    }

    @VisibleForTesting
    String injectGetHomeRoleHolderAsUser(int userId) {
        List<String> roleHolders = mRoleManager.getRoleHoldersAsUser(
                RoleManager.ROLE_HOME, UserHandle.of(userId));
        return roleHolders.isEmpty() ? null : roleHolders.get(0);
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
        synchronized (mServiceLock) {
            final ShortcutUser user = mUsers.get(userId);
            if (user == null) return null;

            return user.getAllPackagesForTest().get(packageName);
        }
    }

    @VisibleForTesting
    ShortcutInfo getPackageShortcutForTest(String packageName, String shortcutId, int userId) {
        synchronized (mServiceLock) {
            final ShortcutPackage pkg = getPackageShortcutForTest(packageName, userId);
            if (pkg == null) return null;

            return pkg.findShortcutById(shortcutId);
        }
    }

    @VisibleForTesting
    void updatePackageShortcutForTest(String packageName, String shortcutId, int userId,
            Consumer<ShortcutInfo> cb) {
        synchronized (mServiceLock) {
            final ShortcutPackage pkg = getPackageShortcutForTest(packageName, userId);
            if (pkg == null) return;
            cb.accept(pkg.findShortcutById(shortcutId));
        }
    }

    @VisibleForTesting
    ShortcutLauncher getLauncherShortcutForTest(String packageName, int userId) {
        synchronized (mServiceLock) {
            final ShortcutUser user = mUsers.get(userId);
            if (user == null) return null;

            return user.getAllLaunchersForTest().get(UserPackage.of(userId, packageName));
        }
    }

    @VisibleForTesting
    ShortcutRequestPinProcessor getShortcutRequestPinProcessorForTest() {
        return mShortcutRequestPinProcessor;
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
        synchronized (mServiceLock) {
            forEachLoadedUserLocked(u -> u.forAllPackageItems(ShortcutPackageItem::verifyStates));
        }
    }

    @VisibleForTesting
    void waitForBitmapSavesForTest() {
        synchronized (mServiceLock) {
            forEachLoadedUserLocked(u ->
                    u.forAllPackageItems(ShortcutPackageItem::waitForBitmapSaves));
        }
    }

    /**
     * This helper method does the following 3 tasks:
     *
     * 1- Combines the |changed| and |updated| shortcut lists, while removing duplicates.
     * 2- If a shortcut is deleted and added at once in the same operation, removes it from the
     *    |removed| list.
     * 3- Reloads the final list to get the latest flags.
     */
    private List<ShortcutInfo> prepareChangedShortcuts(ArraySet<String> changedIds,
            ArraySet<String> newIds, List<ShortcutInfo> deletedList, final ShortcutPackage ps) {
        if (ps == null) {
            // This can happen when package restore is not finished yet.
            return null;
        }
        if (CollectionUtils.isEmpty(changedIds) && CollectionUtils.isEmpty(newIds)) {
            return null;
        }

        ArraySet<String> resultIds = new ArraySet<>();
        if (!CollectionUtils.isEmpty(changedIds)) {
            resultIds.addAll(changedIds);
        }
        if (!CollectionUtils.isEmpty(newIds)) {
            resultIds.addAll(newIds);
        }

        if (!CollectionUtils.isEmpty(deletedList)) {
            deletedList.removeIf((ShortcutInfo si) -> resultIds.contains(si.getId()));
        }

        List<ShortcutInfo> result = new ArrayList<>();
        ps.findAll(result, (ShortcutInfo si) -> resultIds.contains(si.getId()),
                ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO);
        return result;
    }

    private List<ShortcutInfo> prepareChangedShortcuts(List<ShortcutInfo> changedList,
            List<ShortcutInfo> newList, List<ShortcutInfo> deletedList, final ShortcutPackage ps) {
        ArraySet<String> changedIds = new ArraySet<>();
        addShortcutIdsToSet(changedIds, changedList);

        ArraySet<String> newIds = new ArraySet<>();
        addShortcutIdsToSet(newIds, newList);

        return prepareChangedShortcuts(changedIds, newIds, deletedList, ps);
    }

    private void addShortcutIdsToSet(ArraySet<String> ids, List<ShortcutInfo> shortcuts) {
        if (CollectionUtils.isEmpty(shortcuts)) {
            return;
        }
        final int size = shortcuts.size();
        for (int i = 0; i < size; i++) {
            ids.add(shortcuts.get(i).getId());
        }
    }
}
