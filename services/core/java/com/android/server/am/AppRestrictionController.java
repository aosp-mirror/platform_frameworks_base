/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.am;

import static android.app.ActivityManager.RESTRICTION_LEVEL_ADAPTIVE_BUCKET;
import static android.app.ActivityManager.RESTRICTION_LEVEL_BACKGROUND_RESTRICTED;
import static android.app.ActivityManager.RESTRICTION_LEVEL_EXEMPTED;
import static android.app.ActivityManager.RESTRICTION_LEVEL_HIBERNATION;
import static android.app.ActivityManager.RESTRICTION_LEVEL_RESTRICTED_BUCKET;
import static android.app.ActivityManager.RESTRICTION_LEVEL_UNKNOWN;
import static android.app.ActivityManager.UID_OBSERVER_ACTIVE;
import static android.app.ActivityManager.UID_OBSERVER_GONE;
import static android.app.ActivityManager.UID_OBSERVER_IDLE;
import static android.app.usage.UsageStatsManager.REASON_MAIN_DEFAULT;
import static android.app.usage.UsageStatsManager.REASON_MAIN_FORCED_BY_SYSTEM;
import static android.app.usage.UsageStatsManager.REASON_MAIN_FORCED_BY_USER;
import static android.app.usage.UsageStatsManager.REASON_MAIN_MASK;
import static android.app.usage.UsageStatsManager.REASON_MAIN_USAGE;
import static android.app.usage.UsageStatsManager.REASON_SUB_DEFAULT_UNDEFINED;
import static android.app.usage.UsageStatsManager.REASON_SUB_FORCED_SYSTEM_FLAG_UNDEFINED;
import static android.app.usage.UsageStatsManager.REASON_SUB_FORCED_USER_FLAG_INTERACTION;
import static android.app.usage.UsageStatsManager.REASON_SUB_MASK;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_SYSTEM_UPDATE;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_USER_INTERACTION;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_EXEMPTED;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_NEVER;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET;
import static android.app.usage.UsageStatsManager.reasonToString;
import static android.content.Intent.ACTION_SHOW_FOREGROUND_SERVICE_MANAGER;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;
import static android.os.Process.SYSTEM_UID;

import static com.android.internal.notification.SystemNotificationChannels.ABUSIVE_BACKGROUND_APPS;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManager.RestrictionLevel;
import android.app.ActivityManagerInternal.AppBackgroundRestrictionListener;
import android.app.ActivityThread;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.IUidObserver;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.AppStandbyInfo;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.OnPropertiesChangedListener;
import android.provider.DeviceConfig.Properties;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.util.Slog;
import android.util.SparseArrayMap;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.function.TriConsumer;
import com.android.server.AppStateTracker;
import com.android.server.LocalServices;
import com.android.server.apphibernation.AppHibernationManagerInternal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.usage.AppStandbyInternal;
import com.android.server.usage.AppStandbyInternal.AppIdleStateChangeListener;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * This class tracks various state of the apps and mutates their restriction levels accordingly.
 */
public final class AppRestrictionController {
    static final String TAG = TAG_WITH_CLASS_NAME ? "AppRestrictionController" : TAG_AM;
    static final boolean DEBUG_BG_RESTRICTION_CONTROLLER = false;

    /**
     * The prefix for the sub-namespace of our device configs under
     * the {@link android.provider.DeviceConfig#NAMESPACE_ACTIVITY_MANAGER}.
     */
    static final String DEVICE_CONFIG_SUBNAMESPACE_PREFIX = "bg_";

    static final int STOCK_PM_FLAGS = MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE
            | MATCH_DISABLED_UNTIL_USED_COMPONENTS;

    private final Context mContext;
    private final HandlerThread mBgHandlerThread;
    private final BgHandler mBgHandler;

    // No lock is needed, as it's immutable after initialization in constructor.
    private final ArrayList<BaseAppStateTracker> mAppStateTrackers = new ArrayList<>();

    @GuardedBy("mLock")
    private final RestrictionSettings mRestrictionSettings = new RestrictionSettings();

    private final CopyOnWriteArraySet<AppBackgroundRestrictionListener> mRestrictionListeners =
            new CopyOnWriteArraySet<>();

    /**
     * A mapping between the UID/Pkg and its pending work which should be triggered on inactive;
     * an active UID/pkg pair should have an entry here, although its pending work could be null.
     */
    @GuardedBy("mLock")
    private final SparseArrayMap<String, Runnable> mActiveUids = new SparseArrayMap<>();

    // No lock is needed as it's accessed in bg handler thread only.
    private final ArrayList<Runnable> mTmpRunnables = new ArrayList<>();

    private final Object mLock = new Object();
    private final Injector mInjector;
    private final NotificationHelper mNotificationHelper;

    /**
     * The restriction levels that each package is on, the levels here are defined in
     * {@link android.app.ActivityManager.RESTRICTION_LEVEL_*}.
     */
    final class RestrictionSettings {
        @GuardedBy("mLock")
        final SparseArrayMap<String, PkgSettings> mRestrictionLevels = new SparseArrayMap();

        final class PkgSettings {
            private final String mPackageName;
            private final int mUid;

            private @RestrictionLevel int mCurrentRestrictionLevel;
            private @RestrictionLevel int mLastRestrictionLevel;
            private @ElapsedRealtimeLong long mLevelChangeTimeElapsed;
            private int mReason;

            private @ElapsedRealtimeLong long[] mLastNotificationShownTimeElapsed;
            private int[] mNotificationId;

            PkgSettings(String packageName, int uid) {
                mPackageName = packageName;
                mUid = uid;
                mCurrentRestrictionLevel = mLastRestrictionLevel = RESTRICTION_LEVEL_UNKNOWN;
            }

            @RestrictionLevel int update(@RestrictionLevel int level, int reason, int subReason) {
                if (level != mCurrentRestrictionLevel) {
                    mLastRestrictionLevel = mCurrentRestrictionLevel;
                    mCurrentRestrictionLevel = level;
                    mLevelChangeTimeElapsed = SystemClock.elapsedRealtime();
                    mReason = (REASON_MAIN_MASK & reason) | (REASON_SUB_MASK & subReason);
                    mBgHandler.obtainMessage(BgHandler.MSG_APP_RESTRICTION_LEVEL_CHANGED,
                            mUid, level, mPackageName).sendToTarget();
                }
                return mLastRestrictionLevel;
            }

            @Override
            public String toString() {
                final StringBuilder sb = new StringBuilder(128);
                sb.append("RestrictionLevel{");
                sb.append(Integer.toHexString(System.identityHashCode(this)));
                sb.append(':');
                sb.append(mPackageName);
                sb.append('/');
                sb.append(UserHandle.formatUid(mUid));
                sb.append('}');
                sb.append(' ');
                sb.append(ActivityManager.restrictionLevelToName(mCurrentRestrictionLevel));
                sb.append('(');
                sb.append(reasonToString(mReason));
                sb.append(')');
                return sb.toString();
            }

            void dump(PrintWriter pw, @ElapsedRealtimeLong long nowElapsed) {
                pw.print(toString());
                if (mLastRestrictionLevel != RESTRICTION_LEVEL_UNKNOWN) {
                    pw.print('/');
                    pw.print(ActivityManager.restrictionLevelToName(mLastRestrictionLevel));
                }
                pw.print(" levelChange=");
                TimeUtils.formatDuration(mLevelChangeTimeElapsed - nowElapsed, pw);
                if (mLastNotificationShownTimeElapsed != null) {
                    for (int i = 0; i < mLastNotificationShownTimeElapsed.length; i++) {
                        if (mLastNotificationShownTimeElapsed[i] > 0) {
                            pw.print(" lastNoti(");
                            pw.print(mNotificationHelper.notificationTypeToString(i));
                            pw.print(")=");
                            TimeUtils.formatDuration(
                                    mLastNotificationShownTimeElapsed[i] - nowElapsed, pw);
                        }
                    }
                }
            }

            String getPackageName() {
                return mPackageName;
            }

            int getUid() {
                return mUid;
            }

            @RestrictionLevel int getCurrentRestrictionLevel() {
                return mCurrentRestrictionLevel;
            }

            @RestrictionLevel int getLastRestrictionLevel() {
                return mLastRestrictionLevel;
            }

            int getReason() {
                return mReason;
            }

            @ElapsedRealtimeLong long getLastNotificationTime(
                    @NotificationHelper.NotificationType int notificationType) {
                if (mLastNotificationShownTimeElapsed == null) {
                    return 0;
                }
                return mLastNotificationShownTimeElapsed[notificationType];
            }

            void setLastNotificationTime(@NotificationHelper.NotificationType int notificationType,
                    @ElapsedRealtimeLong long timestamp) {
                if (mLastNotificationShownTimeElapsed == null) {
                    mLastNotificationShownTimeElapsed =
                            new long[NotificationHelper.NOTIFICATION_TYPE_LAST];
                }
                mLastNotificationShownTimeElapsed[notificationType] = timestamp;
            }

            int getNotificationId(@NotificationHelper.NotificationType int notificationType) {
                if (mNotificationId == null) {
                    return 0;
                }
                return mNotificationId[notificationType];
            }

            void setNotificationId(@NotificationHelper.NotificationType int notificationType,
                    int notificationId) {
                if (mNotificationId == null) {
                    mNotificationId = new int[NotificationHelper.NOTIFICATION_TYPE_LAST];
                }
                mNotificationId[notificationType] = notificationId;
            }
        }

        /**
         * Update the restriction level.
         *
         * @return The previous restriction level.
         */
        @RestrictionLevel int update(String packageName, int uid, @RestrictionLevel int level,
                int reason, int subReason) {
            synchronized (mLock) {
                PkgSettings settings = getRestrictionSettingsLocked(uid, packageName);
                if (settings == null) {
                    settings = new PkgSettings(packageName, uid);
                    mRestrictionLevels.add(uid, packageName, settings);
                }
                return settings.update(level, reason, subReason);
            }
        }

        /**
         * @return The reason of why it's in this level.
         */
        int getReason(String packageName, int uid) {
            synchronized (mLock) {
                final PkgSettings settings = mRestrictionLevels.get(uid, packageName);
                return settings != null ? settings.getReason()
                        : (REASON_MAIN_DEFAULT | REASON_SUB_DEFAULT_UNDEFINED);
            }
        }

        @RestrictionLevel int getRestrictionLevel(int uid) {
            synchronized (mLock) {
                final int uidKeyIndex = mRestrictionLevels.indexOfKey(uid);
                if (uidKeyIndex < 0) {
                    return RESTRICTION_LEVEL_UNKNOWN;
                }
                final int numPackages = mRestrictionLevels.numElementsForKeyAt(uidKeyIndex);
                if (numPackages == 0) {
                    return RESTRICTION_LEVEL_UNKNOWN;
                }
                @RestrictionLevel int level = RESTRICTION_LEVEL_UNKNOWN;
                for (int i = 0; i < numPackages; i++) {
                    final PkgSettings setting = mRestrictionLevels.valueAt(uidKeyIndex, i);
                    if (setting != null) {
                        final @RestrictionLevel int l = setting.getCurrentRestrictionLevel();
                        level = (level == RESTRICTION_LEVEL_UNKNOWN) ? l : Math.min(level, l);
                    }
                }
                return level;
            }
        }

        @RestrictionLevel int getRestrictionLevel(int uid, String packageName) {
            synchronized (mLock) {
                final PkgSettings settings = getRestrictionSettingsLocked(uid, packageName);
                return settings == null
                        ? getRestrictionLevel(uid) : settings.getCurrentRestrictionLevel();
            }
        }

        @RestrictionLevel int getRestrictionLevel(String packageName, @UserIdInt int userId) {
            final PackageManagerInternal pm = mInjector.getPackageManagerInternal();
            final int uid = pm.getPackageUid(packageName, STOCK_PM_FLAGS, userId);
            return getRestrictionLevel(uid, packageName);
        }

        private @RestrictionLevel int getLastRestrictionLevel(int uid, String packageName) {
            synchronized (mLock) {
                final PkgSettings settings = mRestrictionLevels.get(uid, packageName);
                return settings.getLastRestrictionLevel();
            }
        }

        @GuardedBy("mLock")
        void forEachPackageInUidLocked(int uid,
                @NonNull TriConsumer<String, Integer, Integer> consumer) {
            final int uidKeyIndex = mRestrictionLevels.indexOfKey(uid);
            if (uidKeyIndex < 0) {
                return;
            }
            final int numPackages = mRestrictionLevels.numElementsForKeyAt(uidKeyIndex);
            for (int i = 0; i < numPackages; i++) {
                final PkgSettings settings = mRestrictionLevels.valueAt(uidKeyIndex, i);
                consumer.accept(mRestrictionLevels.keyAt(uidKeyIndex, i),
                        settings.getCurrentRestrictionLevel(), settings.getReason());
            }
        }

        @GuardedBy("mLock")
        void forEachUidLocked(@NonNull Consumer<Integer> consumer) {
            for (int i = mRestrictionLevels.numMaps() - 1; i >= 0; i--) {
                consumer.accept(mRestrictionLevels.keyAt(i));
            }
        }

        @GuardedBy("mLock")
        PkgSettings getRestrictionSettingsLocked(int uid, String packageName) {
            return mRestrictionLevels.get(uid, packageName);
        }

        void removeUser(@UserIdInt int userId) {
            synchronized (mLock) {
                for (int i = mRestrictionLevels.numMaps() - 1; i >= 0; i--) {
                    final int uid = mRestrictionLevels.keyAt(i);
                    if (UserHandle.getUserId(uid) != userId) {
                        continue;
                    }
                    mRestrictionLevels.deleteAt(i);
                }
            }
        }

        void removePackage(String pkgName, int uid) {
            synchronized (mLock) {
                mRestrictionLevels.delete(uid, pkgName);
            }
        }

        void removeUid(int uid) {
            synchronized (mLock) {
                mRestrictionLevels.delete(uid);
            }
        }

        @VisibleForTesting
        void reset() {
            synchronized (mLock) {
                mRestrictionLevels.clear();
            }
        }

        @GuardedBy("mLock")
        void dumpLocked(PrintWriter pw, String prefix) {
            final ArrayList<PkgSettings> settings = new ArrayList<>();
            mRestrictionLevels.forEach(setting -> settings.add(setting));
            Collections.sort(settings, Comparator.comparingInt(PkgSettings::getUid));
            final long nowElapsed = SystemClock.elapsedRealtime();
            for (int i = 0, size = settings.size(); i < size; i++) {
                pw.print(prefix);
                pw.print('#');
                pw.print(i);
                pw.print(' ');
                settings.get(i).dump(pw, nowElapsed);
                pw.println();
            }
        }
    }

    final class ConstantsObserver extends ContentObserver implements
            OnPropertiesChangedListener {
        /**
         * Whether or not to set the app to restricted standby bucket automatically
         * when it's background-restricted.
         */
        static final String KEY_BG_AUTO_RESTRICTED_BUCKET_ON_BG_RESTRICTION =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "auto_restricted_bucket_on_bg_restricted";

        /**
         * The minimal interval in ms before posting a notification again on abusive behaviors
         * of a certain package.
         */
        static final String KEY_BG_ABUSIVE_NOTIFICATION_MINIMAL_INTERVAL =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "abusive_notification_minimal_interval";

        static final boolean DEFAULT_BG_AUTO_RESTRICTED_BUCKET_ON_BG_RESTRICTION = true;
        static final long DEFAULT_BG_ABUSIVE_NOTIFICATION_MINIMAL_INTERVAL_MS = 24 * 60 * 60 * 1000;

        volatile boolean mBgAutoRestrictedBucket;

        volatile boolean mRestrictedBucketEnabled;

        volatile long mBgNotificationMinIntervalMs;

        ConstantsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onPropertiesChanged(Properties properties) {
            for (String name : properties.getKeyset()) {
                if (name == null || !name.startsWith(DEVICE_CONFIG_SUBNAMESPACE_PREFIX)) {
                    return;
                }
                switch (name) {
                    case KEY_BG_AUTO_RESTRICTED_BUCKET_ON_BG_RESTRICTION:
                        updateBgAutoRestrictedBucketChanged();
                        break;
                    case KEY_BG_ABUSIVE_NOTIFICATION_MINIMAL_INTERVAL:
                        updateBgAbusiveNotificationMinimalInterval();
                        break;
                }
                AppRestrictionController.this.onPropertiesChanged(name);
            }
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }

        public void start() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(Global.getUriFor(Global.ENABLE_RESTRICTED_BUCKET),
                    false, this);
            updateSettings();
            updateDeviceConfig();
        }

        void updateSettings() {
            mRestrictedBucketEnabled = isRestrictedBucketEnabled();
        }

        private boolean isRestrictedBucketEnabled() {
            return Global.getInt(mContext.getContentResolver(),
                    Global.ENABLE_RESTRICTED_BUCKET,
                    Global.DEFAULT_ENABLE_RESTRICTED_BUCKET) == 1;
        }

        void updateDeviceConfig() {
            updateBgAutoRestrictedBucketChanged();
            updateBgAbusiveNotificationMinimalInterval();
        }

        private void updateBgAutoRestrictedBucketChanged() {
            boolean oldValue = mBgAutoRestrictedBucket;
            mBgAutoRestrictedBucket = DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_AUTO_RESTRICTED_BUCKET_ON_BG_RESTRICTION,
                    DEFAULT_BG_AUTO_RESTRICTED_BUCKET_ON_BG_RESTRICTION);
            if (oldValue != mBgAutoRestrictedBucket) {
                dispatchAutoRestrictedBucketFeatureFlagChanged(mBgAutoRestrictedBucket);
            }
        }

        private void updateBgAbusiveNotificationMinimalInterval() {
            mBgNotificationMinIntervalMs = DeviceConfig.getLong(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_ABUSIVE_NOTIFICATION_MINIMAL_INTERVAL,
                    DEFAULT_BG_ABUSIVE_NOTIFICATION_MINIMAL_INTERVAL_MS);
        }
    }

    private final ConstantsObserver mConstantsObserver;

    private final AppStateTracker.BackgroundRestrictedAppListener mBackgroundRestrictionListener =
            new AppStateTracker.BackgroundRestrictedAppListener() {
                @Override
                public void updateBackgroundRestrictedForUidPackage(int uid, String packageName,
                        boolean restricted) {
                    mBgHandler.obtainMessage(BgHandler.MSG_BACKGROUND_RESTRICTION_CHANGED,
                            uid, restricted ? 1 : 0, packageName).sendToTarget();
                }
            };

    private final AppIdleStateChangeListener mAppIdleStateChangeListener =
            new AppIdleStateChangeListener() {
                @Override
                public void onAppIdleStateChanged(String packageName, @UserIdInt int userId,
                        boolean idle, int bucket, int reason) {
                    mBgHandler.obtainMessage(BgHandler.MSG_APP_STANDBY_BUCKET_CHANGED,
                            userId, bucket, packageName).sendToTarget();
                }

                @Override
                public void onUserInteractionStarted(String packageName, @UserIdInt int userId) {
                    mBgHandler.obtainMessage(BgHandler.MSG_USER_INTERACTION_STARTED,
                            userId, 0, packageName).sendToTarget();
                }
            };

    private final IUidObserver mUidObserver =
            new IUidObserver.Stub() {
                @Override
                public void onUidGone(int uid, boolean disabled) {
                    mBgHandler.obtainMessage(BgHandler.MSG_UID_INACTIVE, uid, disabled ? 1 : 0)
                            .sendToTarget();
                }

                @Override
                public void onUidIdle(int uid, boolean disabled) {
                    mBgHandler.obtainMessage(BgHandler.MSG_UID_INACTIVE, uid, disabled ? 1 : 0)
                            .sendToTarget();
                }

                @Override
                public void onUidActive(int uid) {
                    mBgHandler.obtainMessage(BgHandler.MSG_UID_ACTIVE, uid, 0).sendToTarget();
                }

                @Override
                public void onUidStateChanged(int uid, int procState, long procStateSeq,
                        int capability) {
                }

                @Override
                public void onUidCachedChanged(int uid, boolean cached) {
                }
            };

    /**
     * Register the background restriction listener callback.
     */
    public void addAppBackgroundRestrictionListener(
            @NonNull AppBackgroundRestrictionListener listener) {
        mRestrictionListeners.add(listener);
    }

    AppRestrictionController(final Context context) {
        this(new Injector(context));
    }

    AppRestrictionController(final Injector injector) {
        mInjector = injector;
        mContext = injector.getContext();
        mBgHandlerThread = new HandlerThread("bgres-controller");
        mBgHandlerThread.start();
        mBgHandler = new BgHandler(mBgHandlerThread.getLooper(), injector);
        mConstantsObserver = new ConstantsObserver(mBgHandler);
        mNotificationHelper = new NotificationHelper(this);
        injector.initAppStateTrackers(this);
    }

    void onSystemReady() {
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                ActivityThread.currentApplication().getMainExecutor(), mConstantsObserver);
        mConstantsObserver.start();
        initRestrictionStates();
        registerForUidObservers();
        registerForSystemBroadcasts();
        mInjector.getAppStateTracker().addBackgroundRestrictedAppListener(
                mBackgroundRestrictionListener);
        mInjector.getAppStandbyInternal().addListener(mAppIdleStateChangeListener);
        for (int i = 0, size = mAppStateTrackers.size(); i < size; i++) {
            mAppStateTrackers.get(i).onSystemReady();
        }
    }

    @VisibleForTesting
    void resetRestrictionSettings() {
        mRestrictionSettings.reset();
        initRestrictionStates();
    }

    private void initRestrictionStates() {
        final int[] allUsers = mInjector.getUserManagerInternal().getUserIds();
        for (int userId : allUsers) {
            refreshAppRestrictionLevelForUser(userId, REASON_MAIN_FORCED_BY_USER,
                    REASON_SUB_FORCED_USER_FLAG_INTERACTION);
        }
    }

    private void registerForUidObservers() {
        try {
            mInjector.getIActivityManager().registerUidObserver(mUidObserver,
                    UID_OBSERVER_ACTIVE | UID_OBSERVER_GONE | UID_OBSERVER_IDLE,
                    ActivityManager.PROCESS_STATE_UNKNOWN, "android");
        } catch (RemoteException e) {
            // Intra-process call, it won't happen.
        }
    }

    /**
     * Called when initializing a user.
     */
    private void refreshAppRestrictionLevelForUser(@UserIdInt int userId, int reason,
            int subReason) {
        final List<AppStandbyInfo> appStandbyInfos = mInjector.getAppStandbyInternal()
                .getAppStandbyBuckets(userId);
        if (ArrayUtils.isEmpty(appStandbyInfos)) {
            return;
        }

        if (DEBUG_BG_RESTRICTION_CONTROLLER) {
            Slog.i(TAG, "Refreshing restriction levels of user " + userId);
        }
        final PackageManagerInternal pm = mInjector.getPackageManagerInternal();
        for (AppStandbyInfo info: appStandbyInfos) {
            final int uid = pm.getPackageUid(info.mPackageName, STOCK_PM_FLAGS, userId);
            if (uid < 0) {
                // Shouldn't happen.
                Slog.e(TAG, "Unable to find " + info.mPackageName + "/u" + userId);
                continue;
            }
            final @RestrictionLevel int level = calcAppRestrictionLevel(
                    userId, uid, info.mPackageName, info.mStandbyBucket, false, false);
            applyRestrictionLevel(info.mPackageName, uid, level,
                    info.mStandbyBucket, true, reason, subReason);
        }
    }

    void refreshAppRestrictionLevelForUid(int uid, int reason, int subReason,
            boolean allowRequestBgRestricted) {
        final String[] packages = mInjector.getPackageManager().getPackagesForUid(uid);
        if (ArrayUtils.isEmpty(packages)) {
            return;
        }
        final AppStandbyInternal appStandbyInternal = mInjector.getAppStandbyInternal();
        final int userId = UserHandle.getUserId(uid);
        final long now = SystemClock.elapsedRealtime();
        for (String pkg: packages) {
            final int curBucket = appStandbyInternal.getAppStandbyBucket(pkg, userId, now, false);
            final @RestrictionLevel int level = calcAppRestrictionLevel(userId, uid, pkg,
                    curBucket, allowRequestBgRestricted, true);
            if (DEBUG_BG_RESTRICTION_CONTROLLER) {
                Slog.i(TAG, "Proposed restriction level of " + pkg + "/"
                        + UserHandle.formatUid(uid) + ": "
                        + ActivityManager.restrictionLevelToName(level));
            }
            applyRestrictionLevel(pkg, uid, level, curBucket, true, reason, subReason);
        }
    }

    private @RestrictionLevel int calcAppRestrictionLevel(@UserIdInt int userId, int uid,
            String packageName, @UsageStatsManager.StandbyBuckets int standbyBucket,
            boolean allowRequestBgRestricted, boolean calcTrackers) {
        if (mInjector.getAppHibernationInternal().isHibernatingForUser(packageName, userId)) {
            return RESTRICTION_LEVEL_HIBERNATION;
        }
        @RestrictionLevel int level;
        switch (standbyBucket) {
            case STANDBY_BUCKET_EXEMPTED:
                level = RESTRICTION_LEVEL_EXEMPTED;
                break;
            case STANDBY_BUCKET_NEVER:
                level = RESTRICTION_LEVEL_BACKGROUND_RESTRICTED;
                break;
            case STANDBY_BUCKET_ACTIVE:
            case STANDBY_BUCKET_WORKING_SET:
            case STANDBY_BUCKET_FREQUENT:
            case STANDBY_BUCKET_RARE:
            case STANDBY_BUCKET_RESTRICTED:
            default:
                if (mInjector.getAppStateTracker()
                        .isAppBackgroundRestricted(uid, packageName)) {
                    return RESTRICTION_LEVEL_BACKGROUND_RESTRICTED;
                }
                level = mConstantsObserver.mRestrictedBucketEnabled
                        && standbyBucket == STANDBY_BUCKET_RESTRICTED
                        ? RESTRICTION_LEVEL_RESTRICTED_BUCKET
                        : RESTRICTION_LEVEL_ADAPTIVE_BUCKET;
                if (calcTrackers) {
                    @RestrictionLevel int l = calcAppRestrictionLevelFromTackers(uid, packageName);
                    if (l == RESTRICTION_LEVEL_EXEMPTED) {
                        return RESTRICTION_LEVEL_EXEMPTED;
                    }
                    level = Math.max(l, level);
                    if (level == RESTRICTION_LEVEL_BACKGROUND_RESTRICTED) {
                        // This level can't be entered without user consent
                        if (allowRequestBgRestricted) {
                            mBgHandler.obtainMessage(BgHandler.MSG_REQUEST_BG_RESTRICTED,
                                    uid, 0, packageName).sendToTarget();
                        }
                        // Lower the level.
                        level = RESTRICTION_LEVEL_RESTRICTED_BUCKET;
                    }
                }
                break;
        }
        return level;
    }

    /**
     * Ask each of the trackers for their proposed restriction levels for the given uid/package,
     * and return the most restrictive level.
     *
     * <p>Note, it's different from the {@link #getRestrictionLevel} where it returns the least
     * restrictive level. We're returning the most restrictive level here because each tracker
     * monitors certain dimensions of the app, the abusive behaviors could be detected in one or
     * more of these dimensions, but not necessarily all of them. </p>
     */
    private @RestrictionLevel int calcAppRestrictionLevelFromTackers(int uid, String packageName) {
        @RestrictionLevel int level = RESTRICTION_LEVEL_UNKNOWN;
        final boolean isRestrictedBucketEnabled = mConstantsObserver.mRestrictedBucketEnabled;
        for (int i = mAppStateTrackers.size() - 1; i >= 0; i--) {
            @RestrictionLevel int l = mAppStateTrackers.get(i).getPolicy()
                    .getProposedRestrictionLevel(packageName, uid);
            if (!isRestrictedBucketEnabled && l == RESTRICTION_LEVEL_RESTRICTED_BUCKET) {
                l = RESTRICTION_LEVEL_ADAPTIVE_BUCKET;
            }
            level = Math.max(level, l);
        }
        return level;
    }

    /**
     * Get the restriction level of the given UID, if it hosts multiple packages,
     * return least restricted one (or if any of them is exempted).
     */
    @RestrictionLevel int getRestrictionLevel(int uid) {
        return mRestrictionSettings.getRestrictionLevel(uid);
    }

    /**
     * Get the restriction level of the given UID and package.
     */
    @RestrictionLevel int getRestrictionLevel(int uid, String packageName) {
        return mRestrictionSettings.getRestrictionLevel(uid, packageName);
    }

    /**
     * Get the restriction level of the given package in given user id.
     */
    @RestrictionLevel int getRestrictionLevel(String packageName, @UserIdInt int userId) {
        return mRestrictionSettings.getRestrictionLevel(packageName, userId);
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.println("BACKGROUND RESTRICTION LEVEL SETTINGS");
        synchronized (mLock) {
            mRestrictionSettings.dumpLocked(pw, prefix + "  ");
        }
    }

    private void applyRestrictionLevel(String pkgName, int uid, @RestrictionLevel int level,
            int curBucket, boolean allowUpdateBucket, int reason, int subReason) {
        int curLevel;
        int prevReason;
        synchronized (mLock) {
            curLevel = getRestrictionLevel(uid, pkgName);
            if (curLevel == level) {
                // Nothing to do.
                return;
            }
            if (DEBUG_BG_RESTRICTION_CONTROLLER) {
                Slog.i(TAG, "Updating the restriction level of " + pkgName + "/"
                        + UserHandle.formatUid(uid) + " from "
                        + ActivityManager.restrictionLevelToName(curLevel) + " to "
                        + ActivityManager.restrictionLevelToName(level)
                        + " reason=" + reason + ", subReason=" + subReason);
            }

            prevReason = mRestrictionSettings.getReason(pkgName, uid);
            mRestrictionSettings.update(pkgName, uid, level, reason, subReason);
        }

        if (!allowUpdateBucket || curBucket == STANDBY_BUCKET_EXEMPTED) {
            return;
        }
        final AppStandbyInternal appStandbyInternal = mInjector.getAppStandbyInternal();
        if (level >= RESTRICTION_LEVEL_RESTRICTED_BUCKET
                && curLevel < RESTRICTION_LEVEL_RESTRICTED_BUCKET) {
            if (!mConstantsObserver.mRestrictedBucketEnabled
                    || !mConstantsObserver.mBgAutoRestrictedBucket) {
                return;
            }
            // Moving the app standby bucket to restricted in the meanwhile.
            if (DEBUG_BG_RESTRICTION_CONTROLLER
                    && level == RESTRICTION_LEVEL_BACKGROUND_RESTRICTED) {
                Slog.i(TAG, pkgName + "/" + UserHandle.formatUid(uid)
                        + " is bg-restricted, moving to restricted standby bucket");
            }
            if (curBucket != STANDBY_BUCKET_RESTRICTED) {
                // restrict the app if it hasn't done so.
                boolean doIt = true;
                synchronized (mLock) {
                    final int index = mActiveUids.indexOfKey(uid, pkgName);
                    if (index >= 0) {
                        // It's currently active, enqueue it.
                        mActiveUids.add(uid, pkgName, () -> appStandbyInternal.restrictApp(
                                pkgName, UserHandle.getUserId(uid), reason, subReason));
                        doIt = false;
                    }
                }
                if (doIt) {
                    appStandbyInternal.restrictApp(pkgName, UserHandle.getUserId(uid),
                            reason, subReason);
                }
            }
        } else if (curLevel >= RESTRICTION_LEVEL_RESTRICTED_BUCKET
                && level < RESTRICTION_LEVEL_RESTRICTED_BUCKET) {
            // Moved out of the background-restricted state.
            if (curBucket != STANDBY_BUCKET_RARE) {
                synchronized (mLock) {
                    final int index = mActiveUids.indexOfKey(uid, pkgName);
                    if (index >= 0) {
                        mActiveUids.add(uid, pkgName, null);
                    }
                }
                appStandbyInternal.maybeUnrestrictApp(pkgName, UserHandle.getUserId(uid),
                        prevReason & REASON_MAIN_MASK, prevReason & REASON_SUB_MASK,
                        reason, subReason);
            }
        }
    }

    private void handleBackgroundRestrictionChanged(int uid, String pkgName, boolean restricted) {
        // Firstly, notify the trackers.
        for (int i = 0, size = mAppStateTrackers.size(); i < size; i++) {
            mAppStateTrackers.get(i)
                    .onBackgroundRestrictionChanged(uid, pkgName, restricted);
        }

        final AppStandbyInternal appStandbyInternal = mInjector.getAppStandbyInternal();
        final int userId = UserHandle.getUserId(uid);
        final long now = SystemClock.elapsedRealtime();
        final int curBucket = appStandbyInternal.getAppStandbyBucket(pkgName, userId, now, false);
        if (restricted) {
            // The app could fall into the background restricted with user consent only,
            // so set the reason to it.
            applyRestrictionLevel(pkgName, uid, RESTRICTION_LEVEL_BACKGROUND_RESTRICTED,
                    curBucket, true, REASON_MAIN_FORCED_BY_USER,
                    REASON_SUB_FORCED_USER_FLAG_INTERACTION);
            mBgHandler.obtainMessage(BgHandler.MSG_CANCEL_REQUEST_BG_RESTRICTED, uid, 0, pkgName)
                    .sendToTarget();
        } else {
            // Moved out of the background-restricted state, we'd need to check if it should
            // stay in the restricted standby bucket.
            final @RestrictionLevel int lastLevel =
                    mRestrictionSettings.getLastRestrictionLevel(uid, pkgName);
            final int tentativeBucket = curBucket == STANDBY_BUCKET_EXEMPTED
                    ? STANDBY_BUCKET_EXEMPTED
                    : (lastLevel == RESTRICTION_LEVEL_RESTRICTED_BUCKET
                            ? STANDBY_BUCKET_RESTRICTED : STANDBY_BUCKET_RARE);
            final @RestrictionLevel int level = calcAppRestrictionLevel(
                    UserHandle.getUserId(uid), uid, pkgName, tentativeBucket, false, true);

            applyRestrictionLevel(pkgName, uid, level, curBucket, true,
                    REASON_MAIN_USAGE, REASON_SUB_USAGE_USER_INTERACTION);
        }
    }

    private void dispatchAppRestrictionLevelChanges(int uid, String pkgName,
            @RestrictionLevel int newLevel) {
        mRestrictionListeners.forEach(
                l -> l.onRestrictionLevelChanged(uid, pkgName, newLevel));
    }

    private void dispatchAutoRestrictedBucketFeatureFlagChanged(boolean newValue) {
        final AppStandbyInternal appStandbyInternal = mInjector.getAppStandbyInternal();
        final ArrayList<Runnable> pendingTasks = new ArrayList<>();
        synchronized (mLock) {
            mRestrictionSettings.forEachUidLocked(uid -> {
                mRestrictionSettings.forEachPackageInUidLocked(uid, (pkgName, level, reason) -> {
                    if (level == RESTRICTION_LEVEL_BACKGROUND_RESTRICTED) {
                        pendingTasks.add(newValue
                                ? () -> appStandbyInternal.restrictApp(pkgName,
                                UserHandle.getUserId(uid), reason & REASON_MAIN_MASK,
                                reason & REASON_SUB_MASK)
                                : () -> appStandbyInternal.maybeUnrestrictApp(pkgName,
                                UserHandle.getUserId(uid), reason & REASON_MAIN_MASK,
                                reason & REASON_SUB_MASK, REASON_MAIN_USAGE,
                                REASON_SUB_USAGE_SYSTEM_UPDATE));
                    }
                });
            });
        }
        for (int i = 0; i < pendingTasks.size(); i++) {
            pendingTasks.get(i).run();
        }
        mRestrictionListeners.forEach(
                l -> l.onAutoRestrictedBucketFeatureFlagChanged(newValue));
    }

    private void handleAppStandbyBucketChanged(int bucket, String packageName,
            @UserIdInt int userId) {
        final int uid = mInjector.getPackageManagerInternal().getPackageUid(
                packageName, STOCK_PM_FLAGS, userId);
        final @RestrictionLevel int level = calcAppRestrictionLevel(
                userId, uid, packageName, bucket, false, false);
        applyRestrictionLevel(packageName, uid, level, bucket, false,
                REASON_MAIN_DEFAULT, REASON_SUB_DEFAULT_UNDEFINED);
    }

    void handleRequestBgRestricted(String packageName, int uid) {
        if (DEBUG_BG_RESTRICTION_CONTROLLER) {
            Slog.i(TAG, "Requesting background restricted " + packageName + " "
                    + UserHandle.formatUid(uid));
        }
        mNotificationHelper.postRequestBgRestrictedIfNecessary(packageName, uid);
    }

    void handleCancelRequestBgRestricted(String packageName, int uid) {
        if (DEBUG_BG_RESTRICTION_CONTROLLER) {
            Slog.i(TAG, "Cancelling requesting background restricted " + packageName + " "
                    + UserHandle.formatUid(uid));
        }
        mNotificationHelper.cancelRequestBgRestrictedIfNecessary(packageName, uid);
    }

    static class NotificationHelper {
        static final String PACKAGE_SCHEME = "package";
        static final String GROUP_KEY = "com.android.app.abusive_bg_apps";

        static final int SUMMARY_NOTIFICATION_ID = SystemMessage.NOTE_ABUSIVE_BG_APPS_BASE;

        static final int NOTIFICATION_TYPE_ABUSIVE_CURRENT_DRAIN = 0;
        static final int NOTIFICATION_TYPE_LONG_RUNNING_FGS = 1;
        static final int NOTIFICATION_TYPE_LAST = 2;

        @IntDef(prefix = { "NOTIFICATION_TYPE_"}, value = {
            NOTIFICATION_TYPE_ABUSIVE_CURRENT_DRAIN,
            NOTIFICATION_TYPE_LONG_RUNNING_FGS,
        })
        @Retention(RetentionPolicy.SOURCE)
        static @interface NotificationType{}

        static final String[] NOTIFICATION_TYPE_STRINGS = {
            "Abusive current drain",
            "Long-running FGS",
        };

        static String notificationTypeToString(@NotificationType int notificationType) {
            return NOTIFICATION_TYPE_STRINGS[notificationType];
        }

        private final AppRestrictionController mBgController;
        private final NotificationManager mNotificationManager;
        private final Injector mInjector;
        private final Object mLock;
        private final Context mContext;

        @GuardedBy("mLock")
        private int mNotificationIDStepper = SUMMARY_NOTIFICATION_ID + 1;

        NotificationHelper(AppRestrictionController controller) {
            mBgController = controller;
            mInjector = controller.mInjector;
            mNotificationManager = mInjector.getNotificationManager();
            mLock = controller.mLock;
            mContext = mInjector.getContext();
        }

        void postRequestBgRestrictedIfNecessary(String packageName, int uid) {
            final Intent intent = new Intent(Settings.ACTION_VIEW_ADVANCED_POWER_USAGE_DETAIL);
            intent.setData(Uri.fromParts(PACKAGE_SCHEME, packageName, null));

            final PendingIntent pendingIntent = PendingIntent.getActivityAsUser(mContext, 0,
                    intent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE, null,
                    UserHandle.of(UserHandle.getUserId(uid)));
            postNotificationIfNecessary(NOTIFICATION_TYPE_ABUSIVE_CURRENT_DRAIN,
                    com.android.internal.R.string.notification_title_abusive_bg_apps,
                    com.android.internal.R.string.notification_content_abusive_bg_apps,
                    pendingIntent, packageName, uid);
        }

        void postLongRunningFgsIfNecessary(String packageName, int uid) {
            final Intent intent = new Intent(ACTION_SHOW_FOREGROUND_SERVICE_MANAGER);
            intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);

            final PendingIntent pendingIntent = PendingIntent.getBroadcastAsUser(mContext, 0,
                    intent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                    UserHandle.of(UserHandle.getUserId(uid)));
            postNotificationIfNecessary(NOTIFICATION_TYPE_LONG_RUNNING_FGS,
                    com.android.internal.R.string.notification_title_abusive_bg_apps,
                    com.android.internal.R.string.notification_content_long_running_fgs,
                    pendingIntent, packageName, uid);
        }

        int getNotificationIdIfNecessary(@NotificationType int notificationType,
                String packageName, int uid) {
            synchronized (mLock) {
                final RestrictionSettings.PkgSettings settings = mBgController.mRestrictionSettings
                        .getRestrictionSettingsLocked(uid, packageName);

                final long now = SystemClock.elapsedRealtime();
                final long lastNotificationShownTimeElapsed =
                        settings.getLastNotificationTime(notificationType);
                if (lastNotificationShownTimeElapsed != 0 && (lastNotificationShownTimeElapsed
                        + mBgController.mConstantsObserver.mBgNotificationMinIntervalMs > now)) {
                    if (DEBUG_BG_RESTRICTION_CONTROLLER) {
                        Slog.i(TAG, "Not showing notification as last notification was shown "
                                + TimeUtils.formatDuration(now - lastNotificationShownTimeElapsed)
                                + " ago");
                    }
                    return 0;
                }
                settings.setLastNotificationTime(notificationType, now);
                int notificationId = settings.getNotificationId(notificationType);
                if (notificationId <= 0) {
                    notificationId = mNotificationIDStepper++;
                    settings.setNotificationId(notificationType, notificationId);
                }
                if (DEBUG_BG_RESTRICTION_CONTROLLER) {
                    Slog.i(TAG, "Showing notification for " + packageName
                            + "/" + UserHandle.formatUid(uid)
                            + ", id=" + notificationId
                            + ", now=" + now
                            + ", lastShown=" + lastNotificationShownTimeElapsed);
                }
                return notificationId;
            }
        }

        void postNotificationIfNecessary(@NotificationType int notificationType, int titleRes,
                int messageRes, PendingIntent pendingIntent, String packageName, int uid) {
            int notificationId = getNotificationIdIfNecessary(notificationType, packageName, uid);
            if (notificationId <= 0) {
                return;
            }

            final PackageManagerInternal pmi = mInjector.getPackageManagerInternal();
            final PackageManager pm = mInjector.getPackageManager();
            final ApplicationInfo ai = pmi.getApplicationInfo(packageName, STOCK_PM_FLAGS,
                    SYSTEM_UID, UserHandle.getUserId(uid));
            final String title = mContext.getString(titleRes);
            final String message = mContext.getString(messageRes,
                    ai != null ? pm.getText(packageName, ai.labelRes, ai) : packageName);
            final Icon icon = ai != null ? Icon.createWithResource(packageName, ai.icon) : null;

            postNotification(notificationId, packageName, uid, title, message, icon, pendingIntent);
        }

        void postNotification(int notificationId, String packageName, int uid, String title,
                String message, Icon icon, PendingIntent pendingIntent) {
            final UserHandle targetUser = UserHandle.of(UserHandle.getUserId(uid));
            postSummaryNotification(targetUser);

            final Notification.Builder notificationBuilder = new Notification.Builder(mContext,
                    ABUSIVE_BACKGROUND_APPS)
                    .setAutoCancel(true)
                    .setGroup(GROUP_KEY)
                    .setWhen(System.currentTimeMillis())
                    .setSmallIcon(com.android.internal.R.drawable.stat_sys_warning)
                    .setColor(mContext.getColor(
                            com.android.internal.R.color.system_notification_accent_color))
                    .setContentTitle(title)
                    .setContentText(message)
                    .setContentIntent(pendingIntent);
            if (icon != null) {
                notificationBuilder.setLargeIcon(icon);
            }

            final Notification notification = notificationBuilder.build();
            // Remember the package name for testing.
            notification.extras.putString(Intent.EXTRA_PACKAGE_NAME, packageName);

            mNotificationManager.notifyAsUser(null, notificationId, notification, targetUser);
        }

        private void postSummaryNotification(@NonNull UserHandle targetUser) {
            final Notification summary = new Notification.Builder(mContext,
                    ABUSIVE_BACKGROUND_APPS)
                    .setGroup(GROUP_KEY)
                    .setGroupSummary(true)
                    .setStyle(new Notification.BigTextStyle())
                    .setSmallIcon(com.android.internal.R.drawable.stat_sys_warning)
                    .setColor(mContext.getColor(
                            com.android.internal.R.color.system_notification_accent_color))
                    .build();
            mNotificationManager.notifyAsUser(null, SUMMARY_NOTIFICATION_ID, summary, targetUser);
        }

        void cancelRequestBgRestrictedIfNecessary(String packageName, int uid) {
            synchronized (mLock) {
                final RestrictionSettings.PkgSettings settings = mBgController.mRestrictionSettings
                        .getRestrictionSettingsLocked(uid, packageName);
                final int notificationId =
                        settings.getNotificationId(NOTIFICATION_TYPE_ABUSIVE_CURRENT_DRAIN);
                if (notificationId > 0) {
                    mNotificationManager.cancel(notificationId);
                }
            }
        }

        void cancelLongRunningFGSNotificationIfNecessary(String packageName, int uid) {
            synchronized (mLock) {
                final RestrictionSettings.PkgSettings settings = mBgController.mRestrictionSettings
                        .getRestrictionSettingsLocked(uid, packageName);
                final int notificationId =
                        settings.getNotificationId(NOTIFICATION_TYPE_LONG_RUNNING_FGS);
                if (notificationId > 0) {
                    mNotificationManager.cancel(notificationId);
                }
            }
        }
    }

    void handleUidInactive(int uid, boolean disabled) {
        final ArrayList<Runnable> pendingTasks = mTmpRunnables;
        synchronized (mLock) {
            final int index = mActiveUids.indexOfKey(uid);
            if (index < 0) {
                return;
            }
            final int numPackages = mActiveUids.numElementsForKeyAt(index);
            for (int i = 0; i < numPackages; i++) {
                final Runnable pendingTask = mActiveUids.valueAt(index, i);
                if (pendingTask != null) {
                    pendingTasks.add(pendingTask);
                }
            }
            mActiveUids.deleteAt(index);
        }
        for (int i = 0, size = pendingTasks.size(); i < size; i++) {
            pendingTasks.get(i).run();
        }
        pendingTasks.clear();
    }

    void handleUidActive(int uid) {
        synchronized (mLock) {
            final AppStandbyInternal appStandbyInternal = mInjector.getAppStandbyInternal();
            final int userId = UserHandle.getUserId(uid);
            mRestrictionSettings.forEachPackageInUidLocked(uid, (pkgName, level, reason) -> {
                if (level == RESTRICTION_LEVEL_BACKGROUND_RESTRICTED) {
                    mActiveUids.add(uid, pkgName, () -> appStandbyInternal.restrictApp(pkgName,
                            userId, reason & REASON_MAIN_MASK, reason & REASON_SUB_MASK));
                } else {
                    mActiveUids.add(uid, pkgName, null);
                }
            });
        }
    }

    /**
     * @return The background handler of this controller.
     */
    Handler getBackgroundHandler() {
        return mBgHandler;
    }

    /**
     * @return The background handler thread of this controller.
     */
    @VisibleForTesting
    HandlerThread getBackgroundHandlerThread() {
        return mBgHandlerThread;
    }

    /**
     * @return The global lock of this controller.
     */
    Object getLock() {
        return mLock;
    }

    @VisibleForTesting
    void addAppStateTracker(@NonNull BaseAppStateTracker tracker) {
        mAppStateTrackers.add(tracker);
    }

    /**
     * @return The tracker instance of the given class.
     */
    <T extends BaseAppStateTracker> T getAppStateTracker(Class<T> trackerClass) {
        for (BaseAppStateTracker tracker : mAppStateTrackers) {
            if (trackerClass.isAssignableFrom(tracker.getClass())) {
                return (T) tracker;
            }
        }
        return null;
    }

    void postLongRunningFgsIfNecessary(String packageName, int uid) {
        mNotificationHelper.postLongRunningFgsIfNecessary(packageName, uid);
    }

    void cancelLongRunningFGSNotificationIfNecessary(String packageName, int uid) {
        mNotificationHelper.cancelLongRunningFGSNotificationIfNecessary(packageName, uid);
    }

    static class BgHandler extends Handler {
        static final int MSG_BACKGROUND_RESTRICTION_CHANGED = 0;
        static final int MSG_APP_RESTRICTION_LEVEL_CHANGED = 1;
        static final int MSG_APP_STANDBY_BUCKET_CHANGED = 2;
        static final int MSG_USER_INTERACTION_STARTED = 3;
        static final int MSG_REQUEST_BG_RESTRICTED = 4;
        static final int MSG_UID_INACTIVE = 5;
        static final int MSG_UID_ACTIVE = 6;
        static final int MSG_CANCEL_REQUEST_BG_RESTRICTED = 7;

        private final Injector mInjector;

        BgHandler(Looper looper, Injector injector) {
            super(looper);
            mInjector = injector;
        }

        @Override
        public void handleMessage(Message msg) {
            final AppRestrictionController c = mInjector
                    .getAppRestrictionController();
            switch (msg.what) {
                case MSG_BACKGROUND_RESTRICTION_CHANGED: {
                    c.handleBackgroundRestrictionChanged(msg.arg1, (String) msg.obj, msg.arg2 == 1);
                } break;
                case MSG_APP_RESTRICTION_LEVEL_CHANGED: {
                    c.dispatchAppRestrictionLevelChanges(msg.arg1, (String) msg.obj, msg.arg2);
                } break;
                case MSG_APP_STANDBY_BUCKET_CHANGED: {
                    c.handleAppStandbyBucketChanged(msg.arg2, (String) msg.obj, msg.arg1);
                } break;
                case MSG_USER_INTERACTION_STARTED: {
                    c.onUserInteractionStarted((String) msg.obj, msg.arg1);
                } break;
                case MSG_REQUEST_BG_RESTRICTED: {
                    c.handleRequestBgRestricted((String) msg.obj, msg.arg1);
                } break;
                case MSG_UID_INACTIVE : {
                    c.handleUidInactive(msg.arg1, msg.arg2 == 1);
                } break;
                case MSG_UID_ACTIVE: {
                    c.handleUidActive(msg.arg1);
                } break;
                case MSG_CANCEL_REQUEST_BG_RESTRICTED: {
                    c.handleCancelRequestBgRestricted((String) msg.obj, msg.arg1);
                } break;
            }
        }
    }

    static class Injector {
        private final Context mContext;
        private AppRestrictionController mAppRestrictionController;
        private AppOpsManager mAppOpsManager;
        private AppStandbyInternal mAppStandbyInternal;
        private AppStateTracker mAppStateTracker;
        private AppHibernationManagerInternal mAppHibernationInternal;
        private IActivityManager mIActivityManager;
        private UserManagerInternal mUserManagerInternal;
        private PackageManagerInternal mPackageManagerInternal;
        private NotificationManager mNotificationManager;

        Injector(Context context) {
            mContext = context;
        }

        Context getContext() {
            return mContext;
        }

        void initAppStateTrackers(AppRestrictionController controller) {
            mAppRestrictionController = controller;
            controller.mAppStateTrackers.add(new AppBatteryTracker(mContext, controller));
            controller.mAppStateTrackers.add(new AppFGSTracker(mContext, controller));
        }

        AppRestrictionController getAppRestrictionController() {
            return mAppRestrictionController;
        }

        AppOpsManager getAppOpsManager() {
            if (mAppOpsManager == null) {
                mAppOpsManager = getContext().getSystemService(AppOpsManager.class);
            }
            return mAppOpsManager;
        }

        AppStandbyInternal getAppStandbyInternal() {
            if (mAppStandbyInternal == null) {
                mAppStandbyInternal = LocalServices.getService(AppStandbyInternal.class);
            }
            return mAppStandbyInternal;
        }

        AppHibernationManagerInternal getAppHibernationInternal() {
            if (mAppHibernationInternal == null) {
                mAppHibernationInternal = LocalServices.getService(
                        AppHibernationManagerInternal.class);
            }
            return mAppHibernationInternal;
        }

        AppStateTracker getAppStateTracker() {
            if (mAppStateTracker == null) {
                mAppStateTracker = LocalServices.getService(AppStateTracker.class);
            }
            return mAppStateTracker;
        }

        IActivityManager getIActivityManager() {
            return ActivityManager.getService();
        }

        UserManagerInternal getUserManagerInternal() {
            if (mUserManagerInternal == null) {
                mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
            }
            return mUserManagerInternal;
        }

        PackageManagerInternal getPackageManagerInternal() {
            if (mPackageManagerInternal == null) {
                mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
            }
            return mPackageManagerInternal;
        }

        PackageManager getPackageManager() {
            return getContext().getPackageManager();
        }

        NotificationManager getNotificationManager() {
            if (mNotificationManager == null) {
                mNotificationManager = getContext().getSystemService(NotificationManager.class);
            }
            return mNotificationManager;
        }
    }

    private void registerForSystemBroadcasts() {
        final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                switch (intent.getAction()) {
                    case Intent.ACTION_PACKAGE_ADDED: {
                        if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                            final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                            if (uid >= 0) {
                                onUidAdded(uid);
                            }
                        }
                    } break;
                    case Intent.ACTION_PACKAGE_FULLY_REMOVED: {
                        final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                        final Uri data = intent.getData();
                        String ssp;
                        if (uid >= 0 && data != null
                                && (ssp = data.getSchemeSpecificPart()) != null) {
                            onPackageRemoved(ssp, uid);
                        }
                    } break;
                    case Intent.ACTION_UID_REMOVED: {
                        if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                            final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                            if (uid >= 0) {
                                onUidRemoved(uid);
                            }
                        }
                    } break;
                    case Intent.ACTION_USER_ADDED: {
                        final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                        if (userId >= 0) {
                            onUserAdded(userId);
                        }
                    } break;
                    case Intent.ACTION_USER_STARTED: {
                        final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                        if (userId >= 0) {
                            onUserStarted(userId);
                        }
                    } break;
                    case Intent.ACTION_USER_STOPPED: {
                        final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                        if (userId >= 0) {
                            onUserStopped(userId);
                        }
                    } break;
                    case Intent.ACTION_USER_REMOVED: {
                        final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                        if (userId >= 0) {
                            onUserRemoved(userId);
                        }
                    } break;
                }
            }
        };
        final IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        packageFilter.addDataScheme("package");
        mContext.registerReceiverForAllUsers(broadcastReceiver, packageFilter, null, mBgHandler);
        final IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_ADDED);
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        userFilter.addAction(Intent.ACTION_UID_REMOVED);
        mContext.registerReceiverForAllUsers(broadcastReceiver, userFilter, null, mBgHandler);
    }

    private void onUserAdded(@UserIdInt int userId) {
        for (int i = 0, size = mAppStateTrackers.size(); i < size; i++) {
            mAppStateTrackers.get(i).onUserAdded(userId);
        }
    }

    private void onUserStarted(@UserIdInt int userId) {
        refreshAppRestrictionLevelForUser(userId, REASON_MAIN_FORCED_BY_USER,
                REASON_SUB_FORCED_USER_FLAG_INTERACTION);
        for (int i = 0, size = mAppStateTrackers.size(); i < size; i++) {
            mAppStateTrackers.get(i).onUserStarted(userId);
        }
    }

    private void onUserStopped(@UserIdInt int userId) {
        for (int i = 0, size = mAppStateTrackers.size(); i < size; i++) {
            mAppStateTrackers.get(i).onUserStopped(userId);
        }
    }

    private void onUserRemoved(@UserIdInt int userId) {
        for (int i = 0, size = mAppStateTrackers.size(); i < size; i++) {
            mAppStateTrackers.get(i).onUserRemoved(userId);
        }
        mRestrictionSettings.removeUser(userId);
    }

    private void onUidAdded(int uid) {
        refreshAppRestrictionLevelForUid(uid, REASON_MAIN_FORCED_BY_SYSTEM,
                REASON_SUB_FORCED_SYSTEM_FLAG_UNDEFINED, false);
        for (int i = 0, size = mAppStateTrackers.size(); i < size; i++) {
            mAppStateTrackers.get(i).onUidAdded(uid);
        }
    }

    private void onPackageRemoved(String pkgName, int uid) {
        mRestrictionSettings.removePackage(pkgName, uid);
    }

    private void onUidRemoved(int uid) {
        for (int i = 0, size = mAppStateTrackers.size(); i < size; i++) {
            mAppStateTrackers.get(i).onUidRemoved(uid);
        }
        mRestrictionSettings.removeUid(uid);
    }

    boolean isBgAutoRestrictedBucketFeatureFlagEnabled() {
        return mConstantsObserver.mBgAutoRestrictedBucket;
    }

    private void onPropertiesChanged(String name) {
        for (int i = 0, size = mAppStateTrackers.size(); i < size; i++) {
            mAppStateTrackers.get(i).onPropertiesChanged(name);
        }
    }

    private void onUserInteractionStarted(String packageName, @UserIdInt int userId) {
        final int uid = mInjector.getPackageManagerInternal()
                .getPackageUid(packageName, STOCK_PM_FLAGS, userId);
        for (int i = 0, size = mAppStateTrackers.size(); i < size; i++) {
            mAppStateTrackers.get(i).onUserInteractionStarted(packageName, uid);
        }
    }
}
