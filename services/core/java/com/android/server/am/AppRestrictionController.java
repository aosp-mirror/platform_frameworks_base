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

import static android.Manifest.permission.MANAGE_ACTIVITY_TASKS;
import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT;
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT_UI;
import static android.app.ActivityManager.RESTRICTION_LEVEL_ADAPTIVE_BUCKET;
import static android.app.ActivityManager.RESTRICTION_LEVEL_BACKGROUND_RESTRICTED;
import static android.app.ActivityManager.RESTRICTION_LEVEL_EXEMPTED;
import static android.app.ActivityManager.RESTRICTION_LEVEL_HIBERNATION;
import static android.app.ActivityManager.RESTRICTION_LEVEL_MAX;
import static android.app.ActivityManager.RESTRICTION_LEVEL_RESTRICTED_BUCKET;
import static android.app.ActivityManager.RESTRICTION_LEVEL_UNKNOWN;
import static android.app.ActivityManager.RESTRICTION_LEVEL_UNRESTRICTED;
import static android.app.ActivityManager.UID_OBSERVER_ACTIVE;
import static android.app.ActivityManager.UID_OBSERVER_GONE;
import static android.app.ActivityManager.UID_OBSERVER_IDLE;
import static android.app.ActivityManager.UID_OBSERVER_PROCSTATE;
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
import static android.os.PowerExemptionManager.REASON_ALLOWLISTED_PACKAGE;
import static android.os.PowerExemptionManager.REASON_CARRIER_PRIVILEGED_APP;
import static android.os.PowerExemptionManager.REASON_COMPANION_DEVICE_MANAGER;
import static android.os.PowerExemptionManager.REASON_DENIED;
import static android.os.PowerExemptionManager.REASON_DEVICE_DEMO_MODE;
import static android.os.PowerExemptionManager.REASON_DEVICE_OWNER;
import static android.os.PowerExemptionManager.REASON_OP_ACTIVATE_PLATFORM_VPN;
import static android.os.PowerExemptionManager.REASON_OP_ACTIVATE_VPN;
import static android.os.PowerExemptionManager.REASON_PROC_STATE_PERSISTENT;
import static android.os.PowerExemptionManager.REASON_PROC_STATE_PERSISTENT_UI;
import static android.os.PowerExemptionManager.REASON_PROFILE_OWNER;
import static android.os.PowerExemptionManager.REASON_ROLE_DIALER;
import static android.os.PowerExemptionManager.REASON_ROLE_EMERGENCY;
import static android.os.PowerExemptionManager.REASON_SYSTEM_ALLOW_LISTED;
import static android.os.PowerExemptionManager.REASON_SYSTEM_MODULE;
import static android.os.PowerExemptionManager.REASON_SYSTEM_UID;
import static android.os.PowerExemptionManager.getExemptionReasonForStatsd;
import static android.os.PowerExemptionManager.reasonCodeToString;
import static android.os.Process.SYSTEM_UID;
import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

import static com.android.internal.notification.SystemNotificationChannels.ABUSIVE_BACKGROUND_APPS;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.AppFGSTracker.foregroundServiceTypeToIndex;
import static com.android.server.am.BaseAppStateTracker.ONE_DAY;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManager.RestrictionLevel;
import android.app.ActivityManagerInternal;
import android.app.ActivityManagerInternal.AppBackgroundRestrictionListener;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.IUidObserver;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.app.usage.AppStandbyInfo;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ServiceInfo;
import android.content.pm.ServiceInfo.ForegroundServiceType;
import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PowerExemptionManager.ExemptionReason;
import android.os.PowerExemptionManager.ReasonCode;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.OnPropertiesChangedListener;
import android.provider.DeviceConfig.Properties;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseArrayMap;
import android.util.TimeUtils;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.function.TriConsumer;
import com.android.server.AppStateTracker;
import com.android.server.LocalServices;
import com.android.server.SystemConfig;
import com.android.server.am.AppBatteryTracker.ImmutableBatteryUsage;
import com.android.server.apphibernation.AppHibernationManagerInternal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.usage.AppStandbyInternal;
import com.android.server.usage.AppStandbyInternal.AppIdleStateChangeListener;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /**
     * Whether or not to show the foreground service manager on tapping notifications.
     */
    private static final boolean ENABLE_SHOW_FOREGROUND_SERVICE_MANAGER = true;

    /**
     * Whether or not to show the action to the foreground service manager when
     * posting the notification for background restriction.
     */
    private static final boolean ENABLE_SHOW_FGS_MANAGER_ACTION_ON_BG_RESTRICTION = false;

    private static final String APP_RESTRICTION_SETTINGS_DIRNAME = "apprestriction";
    private static final String APP_RESTRICTION_SETTINGS_FILENAME = "settings.xml";

    private static final String TAG_SETTINGS = "settings";
    private static final String ATTR_PACKAGE = "package";
    private static final String ATTR_UID = "uid";
    private static final String ATTR_CUR_LEVEL = "curlevel";
    private static final String ATTR_LEVEL_TS = "levelts";
    private static final String ATTR_REASON = "reason";

    private static final String[] ROLES_IN_INTEREST = {
        RoleManager.ROLE_DIALER,
        RoleManager.ROLE_EMERGENCY,
    };

    private final Context mContext;
    private final HandlerThread mBgHandlerThread;
    private final BgHandler mBgHandler;
    private final HandlerExecutor mBgExecutor;

    // No lock is needed, as it's immutable after initialization in constructor.
    private final ArrayList<BaseAppStateTracker> mAppStateTrackers = new ArrayList<>();

    @VisibleForTesting
    @GuardedBy("mSettingsLock")
    final RestrictionSettings mRestrictionSettings = new RestrictionSettings();

    private final CopyOnWriteArraySet<AppBackgroundRestrictionListener> mRestrictionListeners =
            new CopyOnWriteArraySet<>();

    /**
     * A mapping between the UID/Pkg and its pending work which should be triggered on inactive;
     * an active UID/pkg pair should have an entry here, although its pending work could be null.
     */
    @GuardedBy("mSettingsLock")
    private final SparseArrayMap<String, Runnable> mActiveUids = new SparseArrayMap<>();

    // No lock is needed as it's accessed in bg handler thread only.
    private final ArrayList<Runnable> mTmpRunnables = new ArrayList<>();

    /**
     * Power-save allowlisted app-ids (not including except-idle-allowlisted ones).
     */
    private int[] mDeviceIdleAllowlist = new int[0]; // No lock is needed.

    /**
     * Power-save allowlisted app-ids (including except-idle-allowlisted ones).
     */
    private int[] mDeviceIdleExceptIdleAllowlist = new int[0]; // No lock is needed.

    private final Object mLock = new Object();
    private final Object mSettingsLock = new Object();
    private final Injector mInjector;
    private final NotificationHelper mNotificationHelper;

    private final OnRoleHoldersChangedListener mRoleHolderChangedListener =
            this::onRoleHoldersChanged;

    /**
     * The key is the UID, the value is the list of the roles it holds.
     */
    @GuardedBy("mLock")
    private final SparseArray<ArrayList<String>> mUidRolesMapping = new SparseArray<>();

    /**
     * Cache the package name and information about if it's a system module.
     */
    @GuardedBy("mLock")
    private final HashMap<String, Boolean> mSystemModulesCache = new HashMap<>();

    /**
     * The pre-config packages that are exempted from the background restrictions.
     */
    ArraySet<String> mBgRestrictionExemptioFromSysConfig;

    /**
     * Lock specifically for bookkeeping around the carrier-privileged app set.
     * Do not acquire any other locks while holding this one. Methods that
     * require this lock to be held are named with a "CPL" suffix.
     */
    private final Object mCarrierPrivilegedLock = new Object();

    /**
     * List of carrier-privileged apps that should be excluded from standby.
     */
    @GuardedBy("mCarrierPrivilegedLock")
    private List<String> mCarrierPrivilegedApps;

    /**
     * Whether or not we've loaded the restriction settings from the persistent storage.
     */
    private final AtomicBoolean mRestrictionSettingsXmlLoaded = new AtomicBoolean();

    final ActivityManagerService mActivityManagerService;

    static final int TRACKER_TYPE_UNKNOWN = 0;
    static final int TRACKER_TYPE_BATTERY = 1;
    static final int TRACKER_TYPE_BATTERY_EXEMPTION = 2;
    static final int TRACKER_TYPE_FGS = 3;
    static final int TRACKER_TYPE_MEDIA_SESSION = 4;
    static final int TRACKER_TYPE_PERMISSION = 5;
    static final int TRACKER_TYPE_BROADCAST_EVENTS = 6;
    static final int TRACKER_TYPE_BIND_SERVICE_EVENTS = 7;
    @IntDef(prefix = { "TRACKER_TYPE_" }, value = {
            TRACKER_TYPE_UNKNOWN,
            TRACKER_TYPE_BATTERY,
            TRACKER_TYPE_BATTERY_EXEMPTION,
            TRACKER_TYPE_FGS,
            TRACKER_TYPE_MEDIA_SESSION,
            TRACKER_TYPE_PERMISSION,
            TRACKER_TYPE_BROADCAST_EVENTS,
            TRACKER_TYPE_BIND_SERVICE_EVENTS,
    })
    @interface TrackerType {}

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
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
                }
                // fall through.
                case Intent.ACTION_PACKAGE_CHANGED: {
                    final String pkgName = intent.getData().getSchemeSpecificPart();
                    final String[] cmpList = intent.getStringArrayExtra(
                            Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST);
                    // If this is PACKAGE_ADDED (cmpList == null), or if it's a whole-package
                    // enable/disable event (cmpList is just the package name itself), drop
                    // our carrier privileged app & system-app caches and let them refresh
                    if (cmpList == null
                            || (cmpList.length == 1 && pkgName.equals(cmpList[0]))) {
                        clearCarrierPrivilegedApps();
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

    private final BroadcastReceiver mBootReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            switch (intent.getAction()) {
                case Intent.ACTION_LOCKED_BOOT_COMPLETED: {
                    onLockedBootCompleted();
                } break;
            }
        }
    };

    /**
     * The restriction levels that each package is on, the levels here are defined in
     * {@link android.app.ActivityManager.RESTRICTION_LEVEL_*}.
     */
    final class RestrictionSettings {
        @GuardedBy("mSettingsLock")
        final SparseArrayMap<String, PkgSettings> mRestrictionLevels = new SparseArrayMap();

        final class PkgSettings {
            private final String mPackageName;
            private final int mUid;

            private @RestrictionLevel int mCurrentRestrictionLevel;
            private @RestrictionLevel int mLastRestrictionLevel;
            private @CurrentTimeMillisLong long mLevelChangeTime;
            private int mReason;

            private @CurrentTimeMillisLong long[] mLastNotificationShownTime;
            private int[] mNotificationId;

            PkgSettings(String packageName, int uid) {
                mPackageName = packageName;
                mUid = uid;
                mCurrentRestrictionLevel = mLastRestrictionLevel = RESTRICTION_LEVEL_UNKNOWN;
            }

            @GuardedBy("mSettingsLock")
            @RestrictionLevel int update(@RestrictionLevel int level, int reason, int subReason) {
                if (level != mCurrentRestrictionLevel) {
                    mLastRestrictionLevel = mCurrentRestrictionLevel;
                    mCurrentRestrictionLevel = level;
                    mLevelChangeTime = mInjector.currentTimeMillis();
                    mReason = (REASON_MAIN_MASK & reason) | (REASON_SUB_MASK & subReason);
                    mBgHandler.obtainMessage(BgHandler.MSG_APP_RESTRICTION_LEVEL_CHANGED,
                            mUid, level, mPackageName).sendToTarget();
                }
                return mLastRestrictionLevel;
            }

            @Override
            @GuardedBy("mSettingsLock")
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

            void dump(PrintWriter pw, @CurrentTimeMillisLong long now) {
                synchronized (mSettingsLock) {
                    pw.print(toString());
                    if (mLastRestrictionLevel != RESTRICTION_LEVEL_UNKNOWN) {
                        pw.print('/');
                        pw.print(ActivityManager.restrictionLevelToName(mLastRestrictionLevel));
                    }
                    pw.print(" levelChange=");
                    TimeUtils.formatDuration(mLevelChangeTime - now, pw);
                    if (mLastNotificationShownTime != null) {
                        for (int i = 0; i < mLastNotificationShownTime.length; i++) {
                            if (mLastNotificationShownTime[i] > 0) {
                                pw.print(" lastNoti(");
                                pw.print(mNotificationHelper.notificationTypeToString(i));
                                pw.print(")=");
                                TimeUtils.formatDuration(mLastNotificationShownTime[i] - now, pw);
                            }
                        }
                    }
                }
                pw.print(" effectiveExemption=");
                pw.print(reasonCodeToString(getBackgroundRestrictionExemptionReason(mUid)));
            }

            String getPackageName() {
                return mPackageName;
            }

            int getUid() {
                return mUid;
            }

            @GuardedBy("mSettingsLock")
            @RestrictionLevel int getCurrentRestrictionLevel() {
                return mCurrentRestrictionLevel;
            }

            @GuardedBy("mSettingsLock")
            @RestrictionLevel int getLastRestrictionLevel() {
                return mLastRestrictionLevel;
            }

            @GuardedBy("mSettingsLock")
            int getReason() {
                return mReason;
            }

            @GuardedBy("mSettingsLock")
            @CurrentTimeMillisLong long getLastNotificationTime(
                    @NotificationHelper.NotificationType int notificationType) {
                if (mLastNotificationShownTime == null) {
                    return 0;
                }
                return mLastNotificationShownTime[notificationType];
            }

            @GuardedBy("mSettingsLock")
            void setLastNotificationTime(@NotificationHelper.NotificationType int notificationType,
                    @CurrentTimeMillisLong long timestamp) {
                setLastNotificationTime(notificationType, timestamp, true);
            }

            @VisibleForTesting
            @GuardedBy("mSettingsLock")
            void setLastNotificationTime(@NotificationHelper.NotificationType int notificationType,
                    @CurrentTimeMillisLong long timestamp, boolean persist) {
                if (mLastNotificationShownTime == null) {
                    mLastNotificationShownTime =
                            new long[NotificationHelper.NOTIFICATION_TYPE_LAST];
                }
                mLastNotificationShownTime[notificationType] = timestamp;
                if (persist && mRestrictionSettingsXmlLoaded.get()) {
                    schedulePersistToXml(UserHandle.getUserId(mUid));
                }
            }

            @GuardedBy("mSettingsLock")
            int getNotificationId(@NotificationHelper.NotificationType int notificationType) {
                if (mNotificationId == null) {
                    return 0;
                }
                return mNotificationId[notificationType];
            }

            @GuardedBy("mSettingsLock")
            void setNotificationId(@NotificationHelper.NotificationType int notificationType,
                    int notificationId) {
                if (mNotificationId == null) {
                    mNotificationId = new int[NotificationHelper.NOTIFICATION_TYPE_LAST];
                }
                mNotificationId[notificationType] = notificationId;
            }

            @VisibleForTesting
            @GuardedBy("mSettingsLock")
            void setLevelChangeTime(@CurrentTimeMillisLong long timestamp) {
                mLevelChangeTime = timestamp;
            }

            @GuardedBy("mSettingsLock")
            @Override
            public Object clone() {
                final PkgSettings newObj = new PkgSettings(mPackageName, mUid);
                newObj.mCurrentRestrictionLevel = mCurrentRestrictionLevel;
                newObj.mLastRestrictionLevel = mLastRestrictionLevel;
                newObj.mLevelChangeTime = mLevelChangeTime;
                newObj.mReason = mReason;
                if (mLastNotificationShownTime != null) {
                    newObj.mLastNotificationShownTime = Arrays.copyOf(
                            mLastNotificationShownTime, mLastNotificationShownTime.length);
                }
                if (mNotificationId != null) {
                    newObj.mNotificationId = Arrays.copyOf(mNotificationId, mNotificationId.length);
                }
                return newObj;
            }

            @GuardedBy("mSettingsLock")
            @Override
            public boolean equals(Object other) {
                if (other == this) {
                    return true;
                }
                if (other == null || !(other instanceof PkgSettings)) {
                    return false;
                }
                final PkgSettings otherSettings = (PkgSettings) other;
                return otherSettings.mUid == mUid
                        && otherSettings.mCurrentRestrictionLevel == mCurrentRestrictionLevel
                        && otherSettings.mLastRestrictionLevel == mLastRestrictionLevel
                        && otherSettings.mLevelChangeTime == mLevelChangeTime
                        && otherSettings.mReason == mReason
                        && TextUtils.equals(otherSettings.mPackageName, mPackageName)
                        && Arrays.equals(otherSettings.mLastNotificationShownTime,
                                mLastNotificationShownTime)
                        && Arrays.equals(otherSettings.mNotificationId, mNotificationId);
            }
        }

        /**
         * Update the restriction level.
         *
         * @return The previous restriction level.
         */
        @RestrictionLevel int update(String packageName, int uid, @RestrictionLevel int level,
                int reason, int subReason) {
            synchronized (mSettingsLock) {
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
            synchronized (mSettingsLock) {
                final PkgSettings settings = mRestrictionLevels.get(uid, packageName);
                return settings != null ? settings.getReason()
                        : (REASON_MAIN_DEFAULT | REASON_SUB_DEFAULT_UNDEFINED);
            }
        }

        @RestrictionLevel int getRestrictionLevel(int uid) {
            synchronized (mSettingsLock) {
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
            synchronized (mSettingsLock) {
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
            synchronized (mSettingsLock) {
                final PkgSettings settings = mRestrictionLevels.get(uid, packageName);
                return settings == null
                        ? RESTRICTION_LEVEL_UNKNOWN : settings.getLastRestrictionLevel();
            }
        }

        @GuardedBy("mSettingsLock")
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

        @GuardedBy("mSettingsLock")
        void forEachUidLocked(@NonNull Consumer<Integer> consumer) {
            for (int i = mRestrictionLevels.numMaps() - 1; i >= 0; i--) {
                consumer.accept(mRestrictionLevels.keyAt(i));
            }
        }

        @GuardedBy("mSettingsLock")
        PkgSettings getRestrictionSettingsLocked(int uid, String packageName) {
            return mRestrictionLevels.get(uid, packageName);
        }

        void removeUser(@UserIdInt int userId) {
            synchronized (mSettingsLock) {
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
            removePackage(pkgName, uid, true);
        }

        void removePackage(String pkgName, int uid, boolean persist) {
            synchronized (mSettingsLock) {
                final int keyIndex = mRestrictionLevels.indexOfKey(uid);
                mRestrictionLevels.delete(uid, pkgName);
                if (keyIndex >= 0 && mRestrictionLevels.numElementsForKeyAt(keyIndex) == 0) {
                    mRestrictionLevels.deleteAt(keyIndex);
                }
            }
            if (persist && mRestrictionSettingsXmlLoaded.get()) {
                schedulePersistToXml(UserHandle.getUserId(uid));
            }
        }

        void removeUid(int uid) {
            removeUid(uid, true);
        }

        void removeUid(int uid, boolean persist) {
            synchronized (mSettingsLock) {
                mRestrictionLevels.delete(uid);
            }
            if (persist && mRestrictionSettingsXmlLoaded.get()) {
                schedulePersistToXml(UserHandle.getUserId(uid));
            }
        }

        @VisibleForTesting
        void reset() {
            synchronized (mSettingsLock) {
                for (int i = mRestrictionLevels.numMaps() - 1; i >= 0; i--) {
                    mRestrictionLevels.deleteAt(i);
                }
            }
        }

        @VisibleForTesting
        void resetToDefault() {
            synchronized (mSettingsLock) {
                mRestrictionLevels.forEach(settings -> {
                    settings.mCurrentRestrictionLevel = RESTRICTION_LEVEL_UNKNOWN;
                    settings.mLastRestrictionLevel = RESTRICTION_LEVEL_UNKNOWN;
                    settings.mLevelChangeTime = 0L;
                    settings.mReason = REASON_MAIN_DEFAULT | REASON_SUB_DEFAULT_UNDEFINED;
                    if (settings.mLastNotificationShownTime != null) {
                        for (int i = 0; i < settings.mLastNotificationShownTime.length; i++) {
                            settings.mLastNotificationShownTime[i] = 0L;
                        }
                    }
                });
            }
        }

        void dump(PrintWriter pw, String prefix) {
            final ArrayList<PkgSettings> settings = new ArrayList<>();
            synchronized (mSettingsLock) {
                mRestrictionLevels.forEach(setting -> settings.add(setting));
            }
            Collections.sort(settings, Comparator.comparingInt(PkgSettings::getUid));
            final long now = mInjector.currentTimeMillis();
            for (int i = 0, size = settings.size(); i < size; i++) {
                pw.print(prefix);
                pw.print('#');
                pw.print(i);
                pw.print(' ');
                settings.get(i).dump(pw, now);
                pw.println();
            }
        }

        @VisibleForTesting
        void schedulePersistToXml(@UserIdInt int userId) {
            mBgHandler.obtainMessage(BgHandler.MSG_PERSIST_RESTRICTION_SETTINGS, userId, 0)
                    .sendToTarget();
        }

        @VisibleForTesting
        void scheduleLoadFromXml() {
            mBgHandler.sendEmptyMessage(BgHandler.MSG_LOAD_RESTRICTION_SETTINGS);
        }

        @VisibleForTesting
        File getXmlFileNameForUser(@UserIdInt int userId) {
            final File dir = new File(mInjector.getDataSystemDeDirectory(
                    userId), APP_RESTRICTION_SETTINGS_DIRNAME);
            return new File(dir, APP_RESTRICTION_SETTINGS_FILENAME);
        }

        @VisibleForTesting
        void loadFromXml(boolean applyLevel) {
            final int[] allUsers = mInjector.getUserManagerInternal().getUserIds();
            for (int userId : allUsers) {
                loadFromXml(userId, applyLevel);
            }
            mRestrictionSettingsXmlLoaded.set(true);
        }

        void loadFromXml(@UserIdInt int userId, boolean applyLevel) {
            final File file = getXmlFileNameForUser(userId);
            if (!file.exists()) {
                return;
            }
            final long[] ts = new long[NotificationHelper.NOTIFICATION_TYPE_LAST];
            try (InputStream in = new FileInputStream(file)) {
                final TypedXmlPullParser parser = Xml.resolvePullParser(in);
                final long now = SystemClock.elapsedRealtime();
                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    if (type != XmlPullParser.START_TAG) {
                        continue;
                    }
                    final String tagName = parser.getName();
                    if (!TAG_SETTINGS.equals(tagName)) {
                        Slog.w(TAG, "Unexpected tag name: " + tagName);
                        continue;
                    }
                    loadOneFromXml(parser, now, ts, applyLevel);
                }
                if (DEBUG_BG_RESTRICTION_CONTROLLER) {
                    Slog.i(TAG, "Loaded from " + file);
                }
            } catch (IOException | XmlPullParserException e) {
            }
        }

        private void loadOneFromXml(TypedXmlPullParser parser, long now, long[] ts,
                boolean applyLevel) {
            // Reset the buffer.
            for (int i = 0; i < ts.length; i++) {
                ts[i] = 0L;
            }
            // Walk through the attributes.
            int uid = 0;
            String packageName = null;
            int curLevel = RESTRICTION_LEVEL_UNKNOWN;
            int reason = REASON_MAIN_DEFAULT;
            long levelTs = 0L;
            for (int i = 0; i < parser.getAttributeCount(); i++) {
                try {
                    final String attrName = parser.getAttributeName(i);
                    final String attrValue = parser.getAttributeValue(i);
                    switch (attrName) {
                        case ATTR_UID:
                            uid = Integer.parseInt(attrValue);
                            break;
                        case ATTR_PACKAGE:
                            packageName = attrValue;
                            break;
                        case ATTR_CUR_LEVEL:
                            curLevel = Integer.parseInt(attrValue);
                            break;
                        case ATTR_LEVEL_TS:
                            levelTs = Long.parseLong(attrValue);
                            break;
                        case ATTR_REASON:
                            reason = Integer.parseInt(attrValue);
                            break;
                        default:
                            @NotificationHelper.NotificationType int type =
                                    NotificationHelper.notificationTimeAttrToType(attrName);
                            ts[type] = Long.parseLong(attrValue);
                            break;
                    }
                } catch (IllegalArgumentException e) {
                }
            }
            if (uid != 0) {
                if (DEBUG_BG_RESTRICTION_CONTROLLER) {
                    Slog.i(TAG, "Restoring " + packageName + "/" + UserHandle.formatUid(uid)
                            + " level=" + curLevel + " reason=" + Integer.toHexString(reason)
                            + " ts=" + levelTs + " noti=" + Arrays.toString(ts));
                }
                final PkgSettings pkgSettings;
                synchronized (mSettingsLock) {
                    pkgSettings = getRestrictionSettingsLocked(uid, packageName);
                    if (pkgSettings == null) {
                        return;
                    }
                    for (int i = 0; i < ts.length; i++) {
                        if (pkgSettings.getLastNotificationTime(i) == 0 && ts[i] != 0) {
                            pkgSettings.setLastNotificationTime(i, ts[i], false);
                        }
                    }
                    if (pkgSettings.mCurrentRestrictionLevel >= curLevel) {
                        // The current restriction level is the same or more restrictive,
                        // don't restore.
                        return;
                    }
                }
                final int curBucket = mInjector.getAppStandbyInternal().getAppStandbyBucket(
                        packageName, UserHandle.getUserId(uid), now, false);
                if (applyLevel) {
                    applyRestrictionLevel(packageName, uid, curLevel, TRACKER_TYPE_UNKNOWN,
                            curBucket, true, reason & REASON_MAIN_MASK, reason & REASON_SUB_MASK);
                } else {
                    pkgSettings.update(curLevel,
                            reason & REASON_MAIN_MASK, reason & REASON_SUB_MASK);
                }
                synchronized (mSettingsLock) {
                    // Restore the mLevelChangeTime too.
                    pkgSettings.setLevelChangeTime(levelTs);
                }
            }
        }

        @VisibleForTesting
        void persistToXml(@UserIdInt int userId) {
            final File file = getXmlFileNameForUser(userId);
            final File dir = file.getParentFile();
            if (!dir.isDirectory() && !dir.mkdirs()) {
                Slog.w(TAG, "Failed to create folder for " + userId);
                return;
            }
            final AtomicFile atomicFile = new AtomicFile(file);
            FileOutputStream stream = null;
            try {
                stream = atomicFile.startWrite();
                stream.write(toXmlByteArray(userId));
            } catch (Exception e) {
                Slog.e(TAG, "Failed to write file " + file, e);
                if (stream != null) {
                    atomicFile.failWrite(stream);
                }
                return;
            }
            atomicFile.finishWrite(stream);
            if (DEBUG_BG_RESTRICTION_CONTROLLER) {
                Slog.i(TAG, "Successfully written to " + atomicFile);
            }
        }

        private byte[] toXmlByteArray(@UserIdInt int userId) {
            try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                final TypedXmlSerializer serializer = Xml.resolveSerializer(os);

                serializer.startDocument(/* encoding */ null, /* standalone */ true);

                synchronized (mSettingsLock) {
                    for (int i = mRestrictionLevels.numMaps() - 1; i >= 0; i--) {
                        for (int j = mRestrictionLevels.numElementsForKeyAt(i) - 1; j >= 0; j--) {
                            final PkgSettings settings = mRestrictionLevels.valueAt(i, j);
                            final int uid = settings.getUid();
                            if (UserHandle.getUserId(uid) != userId) {
                                continue;
                            }
                            serializer.startTag(null, TAG_SETTINGS);
                            serializer.attributeInt(null, ATTR_UID, uid);
                            serializer.attribute(null, ATTR_PACKAGE, settings.getPackageName());
                            serializer.attributeInt(null, ATTR_CUR_LEVEL,
                                    settings.mCurrentRestrictionLevel);
                            serializer.attributeLong(null, ATTR_LEVEL_TS,
                                    settings.mLevelChangeTime);
                            serializer.attributeInt(null, ATTR_REASON, settings.mReason);
                            for (int k = 0; k < NotificationHelper.NOTIFICATION_TYPE_LAST; k++) {
                                serializer.attributeLong(null,
                                        NotificationHelper.notificationTypeToTimeAttr(k),
                                        settings.getLastNotificationTime(k));
                            }
                            serializer.endTag(null, TAG_SETTINGS);
                        }
                    }
                }

                serializer.endDocument();
                serializer.flush();

                return os.toByteArray();
            } catch (IOException e) {
                return null;
            }
        }

        @VisibleForTesting
        void removeXml() {
            final int[] allUsers = mInjector.getUserManagerInternal().getUserIds();
            for (int userId : allUsers) {
                getXmlFileNameForUser(userId).delete();
            }
        }

        @Override
        public Object clone() {
            final RestrictionSettings newObj = new RestrictionSettings();
            synchronized (mSettingsLock) {
                for (int i = mRestrictionLevels.numMaps() - 1; i >= 0; i--) {
                    for (int j = mRestrictionLevels.numElementsForKeyAt(i) - 1; j >= 0; j--) {
                        final PkgSettings settings = mRestrictionLevels.valueAt(i, j);
                        newObj.mRestrictionLevels.add(mRestrictionLevels.keyAt(i),
                                mRestrictionLevels.keyAt(i, j), (PkgSettings) settings.clone());
                    }
                }
            }
            return newObj;
        }

        @Override
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }
            if (other == null || !(other instanceof RestrictionSettings)) {
                return false;
            }
            final SparseArrayMap<String, PkgSettings> otherSettings = ((RestrictionSettings) other)
                    .mRestrictionLevels;
            synchronized (mSettingsLock) {
                if (otherSettings.numMaps() != mRestrictionLevels.numMaps()) {
                    return false;
                }
                for (int i = mRestrictionLevels.numMaps() - 1; i >= 0; i--) {
                    final int uid = mRestrictionLevels.keyAt(i);
                    if (otherSettings.numElementsForKey(uid)
                            != mRestrictionLevels.numElementsForKeyAt(i)) {
                        return false;
                    }
                    for (int j = mRestrictionLevels.numElementsForKeyAt(i) - 1; j >= 0; j--) {
                        final PkgSettings settings = mRestrictionLevels.valueAt(i, j);
                        if (!settings.equals(otherSettings.get(uid, settings.getPackageName()))) {
                            return false;
                        }
                    }
                }
            }
            return true;
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

        /**
         * The minimal interval in ms before posting a notification again on long running FGS
         * from a certain package.
         */
        static final String KEY_BG_LONG_FGS_NOTIFICATION_MINIMAL_INTERVAL =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "long_fgs_notification_minimal_interval";

        /**
         * The behavior for an app with a FGS and its notification is still showing, when the system
         * detects it's abusive and should be put into bg restricted level. {@code true} - we'll
         * show the prompt to user, {@code false} - we'll not show it.
         */
        static final String KEY_BG_PROMPT_FGS_WITH_NOTIFICATION_TO_BG_RESTRICTED =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "prompt_fgs_with_noti_to_bg_restricted";

        /**
         * The behavior for an app with a FGS and its notification is still showing, when the system
         * detects it's running for a very long time, should we prompt the user.
         * {@code true} - we'll show the prompt to user, {@code false} - we'll not show it.
         */
        static final String KEY_BG_PROMPT_FGS_WITH_NOTIFICATION_ON_LONG_RUNNING =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "prompt_fgs_with_noti_on_long_running";

        /**
         * The list of packages to be exempted from all these background restrictions.
         */
        static final String KEY_BG_RESTRICTION_EXEMPTED_PACKAGES =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "restriction_exempted_packages";

        /**
         * Default value to {@link #mBgAutoRestrictedBucket}.
         */
        static final boolean DEFAULT_BG_AUTO_RESTRICTED_BUCKET_ON_BG_RESTRICTION = false;

        /**
         * Default value to {@link #mBgAbusiveNotificationMinIntervalMs}.
         */
        static final long DEFAULT_BG_ABUSIVE_NOTIFICATION_MINIMAL_INTERVAL_MS = 30 * ONE_DAY;

        /**
         * Default value to {@link #mBgAbusiveNotificationMinIntervalMs}.
         */
        static final long DEFAULT_BG_LONG_FGS_NOTIFICATION_MINIMAL_INTERVAL_MS = 30 * ONE_DAY;

        /**
         * Default value to {@link #mBgPromptFgsWithNotiOnLongRunning}.
         */
        static final boolean DEFAULT_BG_PROMPT_FGS_WITH_NOTIFICATION_ON_LONG_RUNNING = false;

        /**
         * Default value to {@link #mBgPromptFgsWithNotiToBgRestricted}.
         */
        final boolean mDefaultBgPromptFgsWithNotiToBgRestricted;

        volatile boolean mBgAutoRestrictedBucket;

        volatile boolean mRestrictedBucketEnabled;

        volatile long mBgAbusiveNotificationMinIntervalMs;

        volatile long mBgLongFgsNotificationMinIntervalMs;

        /**
         * @see #KEY_BG_RESTRICTION_EXEMPTED_PACKAGES.
         *
         *<p> Mutations on them would result in copy-on-write.</p>
         */
        volatile Set<String> mBgRestrictionExemptedPackages = Collections.emptySet();

        /**
         * @see #KEY_BG_PROMPT_FGS_WITH_NOTIFICATION_TO_BG_RESTRICTED.
         */
        volatile boolean mBgPromptFgsWithNotiToBgRestricted;

        /**
         * @see #KEY_BG_PROMPT_FGS_WITH_NOTIFICATION_ON_LONG_RUNNING.
         */
        volatile boolean mBgPromptFgsWithNotiOnLongRunning;

        ConstantsObserver(Handler handler, Context context) {
            super(handler);
            mDefaultBgPromptFgsWithNotiToBgRestricted = context.getResources().getBoolean(
                    com.android.internal.R.bool.config_bg_prompt_fgs_with_noti_to_bg_restricted);
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
                    case KEY_BG_LONG_FGS_NOTIFICATION_MINIMAL_INTERVAL:
                        updateBgLongFgsNotificationMinimalInterval();
                        break;
                    case KEY_BG_PROMPT_FGS_WITH_NOTIFICATION_TO_BG_RESTRICTED:
                        updateBgPromptFgsWithNotiToBgRestricted();
                        break;
                    case KEY_BG_PROMPT_FGS_WITH_NOTIFICATION_ON_LONG_RUNNING:
                        updateBgPromptFgsWithNotiOnLongRunning();
                        break;
                    case KEY_BG_RESTRICTION_EXEMPTED_PACKAGES:
                        updateBgRestrictionExemptedPackages();
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
            updateBgLongFgsNotificationMinimalInterval();
            updateBgPromptFgsWithNotiToBgRestricted();
            updateBgPromptFgsWithNotiOnLongRunning();
            updateBgRestrictionExemptedPackages();
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
            mBgAbusiveNotificationMinIntervalMs = DeviceConfig.getLong(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_ABUSIVE_NOTIFICATION_MINIMAL_INTERVAL,
                    DEFAULT_BG_ABUSIVE_NOTIFICATION_MINIMAL_INTERVAL_MS);
        }

        private void updateBgLongFgsNotificationMinimalInterval() {
            mBgLongFgsNotificationMinIntervalMs = DeviceConfig.getLong(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_LONG_FGS_NOTIFICATION_MINIMAL_INTERVAL,
                    DEFAULT_BG_LONG_FGS_NOTIFICATION_MINIMAL_INTERVAL_MS);
        }

        private void updateBgPromptFgsWithNotiToBgRestricted() {
            mBgPromptFgsWithNotiToBgRestricted = DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_PROMPT_FGS_WITH_NOTIFICATION_TO_BG_RESTRICTED,
                    mDefaultBgPromptFgsWithNotiToBgRestricted);
        }

        private void updateBgPromptFgsWithNotiOnLongRunning() {
            mBgPromptFgsWithNotiOnLongRunning = DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_PROMPT_FGS_WITH_NOTIFICATION_ON_LONG_RUNNING,
                    DEFAULT_BG_PROMPT_FGS_WITH_NOTIFICATION_ON_LONG_RUNNING);
        }

        private void updateBgRestrictionExemptedPackages() {
            final String settings = DeviceConfig.getString(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_RESTRICTION_EXEMPTED_PACKAGES,
                    null);
            if (settings == null) {
                mBgRestrictionExemptedPackages = Collections.emptySet();
                return;
            }
            final String[] settingsList = settings.split(",");
            final ArraySet<String> packages = new ArraySet<>();
            for (String pkg : settingsList) {
                packages.add(pkg);
            }
            mBgRestrictionExemptedPackages = Collections.unmodifiableSet(packages);
        }

        void dump(PrintWriter pw, String prefix) {
            pw.print(prefix);
            pw.println("BACKGROUND RESTRICTION POLICY SETTINGS:");
            final String indent = "  ";
            prefix = indent + prefix;
            pw.print(prefix);
            pw.print(KEY_BG_AUTO_RESTRICTED_BUCKET_ON_BG_RESTRICTION);
            pw.print('=');
            pw.println(mBgAutoRestrictedBucket);
            pw.print(prefix);
            pw.print(KEY_BG_ABUSIVE_NOTIFICATION_MINIMAL_INTERVAL);
            pw.print('=');
            pw.println(mBgAbusiveNotificationMinIntervalMs);
            pw.print(prefix);
            pw.print(KEY_BG_LONG_FGS_NOTIFICATION_MINIMAL_INTERVAL);
            pw.print('=');
            pw.println(mBgLongFgsNotificationMinIntervalMs);
            pw.print(prefix);
            pw.print(KEY_BG_PROMPT_FGS_WITH_NOTIFICATION_TO_BG_RESTRICTED);
            pw.print('=');
            pw.println(mBgPromptFgsWithNotiToBgRestricted);
            pw.print(prefix);
            pw.print(KEY_BG_RESTRICTION_EXEMPTED_PACKAGES);
            pw.print('=');
            pw.println(mBgRestrictionExemptedPackages.toString());
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
                public void onUidStateChanged(int uid, int procState, long procStateSeq,
                        int capability) {
                    mBgHandler.obtainMessage(BgHandler.MSG_UID_PROC_STATE_CHANGED, uid, procState)
                            .sendToTarget();
                }

                @Override
                public void onUidIdle(int uid, boolean disabled) {
                    mBgHandler.obtainMessage(BgHandler.MSG_UID_IDLE, uid, disabled ? 1 : 0)
                            .sendToTarget();
                }

                @Override
                public void onUidGone(int uid, boolean disabled) {
                    mBgHandler.obtainMessage(BgHandler.MSG_UID_GONE, uid, disabled ? 1 : 0)
                            .sendToTarget();
                }

                @Override
                public void onUidActive(int uid) {
                    mBgHandler.obtainMessage(BgHandler.MSG_UID_ACTIVE, uid, 0).sendToTarget();
                }

                @Override
                public void onUidCachedChanged(int uid, boolean cached) {
                }

                @Override
                public void onUidProcAdjChanged(int uid) {
                }
            };

    /**
     * Register the background restriction listener callback.
     */
    public void addAppBackgroundRestrictionListener(
            @NonNull AppBackgroundRestrictionListener listener) {
        mRestrictionListeners.add(listener);
    }

    AppRestrictionController(final Context context, final ActivityManagerService service) {
        this(new Injector(context), service);
    }

    AppRestrictionController(final Injector injector, final ActivityManagerService service) {
        mInjector = injector;
        mContext = injector.getContext();
        mActivityManagerService = service;
        mBgHandlerThread = new HandlerThread("bgres-controller", THREAD_PRIORITY_BACKGROUND);
        mBgHandlerThread.start();
        mBgHandler = new BgHandler(mBgHandlerThread.getLooper(), injector);
        mBgExecutor = new HandlerExecutor(mBgHandler);
        mConstantsObserver = new ConstantsObserver(mBgHandler, mContext);
        mNotificationHelper = new NotificationHelper(this);
        injector.initAppStateTrackers(this);
    }

    void onSystemReady() {
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                mBgExecutor, mConstantsObserver);
        mConstantsObserver.start();
        initBgRestrictionExemptioFromSysConfig();
        initRestrictionStates();
        initSystemModuleNames();
        initRolesInInterest();
        registerForUidObservers();
        registerForSystemBroadcasts();
        mNotificationHelper.onSystemReady();
        mInjector.getAppStateTracker().addBackgroundRestrictedAppListener(
                mBackgroundRestrictionListener);
        mInjector.getAppStandbyInternal().addListener(mAppIdleStateChangeListener);
        mInjector.getRoleManager().addOnRoleHoldersChangedListenerAsUser(mBgExecutor,
                mRoleHolderChangedListener, UserHandle.ALL);
        mInjector.scheduleInitTrackers(mBgHandler, () -> {
            for (int i = 0, size = mAppStateTrackers.size(); i < size; i++) {
                mAppStateTrackers.get(i).onSystemReady();
            }
        });
    }

    @VisibleForTesting
    void resetRestrictionSettings() {
        synchronized (mSettingsLock) {
            mRestrictionSettings.reset();
        }
        initRestrictionStates();
    }

    @VisibleForTesting
    void tearDown() {
        DeviceConfig.removeOnPropertiesChangedListener(mConstantsObserver);
        unregisterForUidObservers();
        unregisterForSystemBroadcasts();
        mRestrictionSettings.removeXml();
    }

    private void initBgRestrictionExemptioFromSysConfig() {
        mBgRestrictionExemptioFromSysConfig =
                SystemConfig.getInstance().getBgRestrictionExemption();
        if (DEBUG_BG_RESTRICTION_CONTROLLER) {
            final ArraySet<String> exemptedPkgs = mBgRestrictionExemptioFromSysConfig;
            for (int i = exemptedPkgs.size() - 1; i >= 0; i--) {
                Slog.i(TAG, "bg-restriction-exemption: " + exemptedPkgs.valueAt(i));
            }
        }
    }

    private boolean isExemptedFromSysConfig(String packageName) {
        return mBgRestrictionExemptioFromSysConfig != null
                && mBgRestrictionExemptioFromSysConfig.contains(packageName);
    }

    private void initRestrictionStates() {
        final int[] allUsers = mInjector.getUserManagerInternal().getUserIds();
        for (int userId : allUsers) {
            refreshAppRestrictionLevelForUser(userId, REASON_MAIN_FORCED_BY_USER,
                    REASON_SUB_FORCED_USER_FLAG_INTERACTION);
        }
        if (!mInjector.isTest()) {
            // Load the previously saved levels and update the current levels if needed.
            mRestrictionSettings.scheduleLoadFromXml();
            // Also save the current levels right away.
            for (int userId : allUsers) {
                mRestrictionSettings.schedulePersistToXml(userId);
            }
        }
    }

    private void initSystemModuleNames() {
        final PackageManager pm = mInjector.getPackageManager();
        final List<ModuleInfo> moduleInfos = pm.getInstalledModules(0 /* flags */);
        if (moduleInfos == null) {
            return;
        }
        synchronized (mLock) {
            for (ModuleInfo info : moduleInfos) {
                mSystemModulesCache.put(info.getPackageName(), Boolean.TRUE);
            }
        }
    }

    private boolean isSystemModule(String packageName) {
        synchronized (mLock) {
            final Boolean val = mSystemModulesCache.get(packageName);
            if (val != null) {
                return val.booleanValue();
            }
        }

        // Slow path: check if the package is listed among the system modules.
        final PackageManager pm = mInjector.getPackageManager();
        boolean isSystemModule = false;
        try {
            isSystemModule = pm.getModuleInfo(packageName, 0 /* flags */) != null;
        } catch (PackageManager.NameNotFoundException e) {
        }

        if (!isSystemModule) {
            try {
                final PackageInfo pkg = pm.getPackageInfo(packageName, 0 /* flags */);
                // Check if the package is contained in an APEX. There is no public API to properly
                // check whether a given APK package comes from an APEX registered as module.
                // Therefore we conservatively assume that any package scanned from an /apex path is
                // a system package.
                isSystemModule = pkg != null && pkg.applicationInfo.sourceDir.startsWith(
                        Environment.getApexDirectory().getAbsolutePath());
            } catch (PackageManager.NameNotFoundException e) {
            }
        }
        // Update the cache.
        synchronized (mLock) {
            mSystemModulesCache.put(packageName, isSystemModule);
        }
        return isSystemModule;
    }

    private void registerForUidObservers() {
        try {
            mInjector.getIActivityManager().registerUidObserver(mUidObserver,
                    UID_OBSERVER_ACTIVE | UID_OBSERVER_GONE | UID_OBSERVER_IDLE
                    | UID_OBSERVER_PROCSTATE, PROCESS_STATE_FOREGROUND_SERVICE, "android");
        } catch (RemoteException e) {
            // Intra-process call, it won't happen.
        }
    }

    private void unregisterForUidObservers() {
        try {
            mInjector.getIActivityManager().unregisterUidObserver(mUidObserver);
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
            final Pair<Integer, Integer> levelTypePair = calcAppRestrictionLevel(
                    userId, uid, info.mPackageName, info.mStandbyBucket, false, false);
            if (DEBUG_BG_RESTRICTION_CONTROLLER) {
                Slog.i(TAG, "Proposed restriction level of " + info.mPackageName + "/"
                        + UserHandle.formatUid(uid) + ": "
                        + ActivityManager.restrictionLevelToName(levelTypePair.first)
                        + " " + info.mStandbyBucket);
            }
            applyRestrictionLevel(info.mPackageName, uid, levelTypePair.first, levelTypePair.second,
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
            final Pair<Integer, Integer> levelTypePair = calcAppRestrictionLevel(userId, uid, pkg,
                    curBucket, allowRequestBgRestricted, true);
            if (DEBUG_BG_RESTRICTION_CONTROLLER) {
                Slog.i(TAG, "Proposed restriction level of " + pkg + "/"
                        + UserHandle.formatUid(uid) + ": "
                        + ActivityManager.restrictionLevelToName(levelTypePair.first));
            }
            applyRestrictionLevel(pkg, uid, levelTypePair.first, levelTypePair.second,
                    curBucket, true, reason, subReason);
        }
    }

    private Pair<Integer, Integer> calcAppRestrictionLevel(@UserIdInt int userId, int uid,
            String packageName, @UsageStatsManager.StandbyBuckets int standbyBucket,
            boolean allowRequestBgRestricted, boolean calcTrackers) {
        if (mInjector.getAppHibernationInternal().isHibernatingForUser(packageName, userId)) {
            return new Pair<>(RESTRICTION_LEVEL_HIBERNATION, TRACKER_TYPE_UNKNOWN);
        }
        @RestrictionLevel int level;
        @TrackerType int trackerType = TRACKER_TYPE_UNKNOWN;
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
                    return new Pair<>(RESTRICTION_LEVEL_BACKGROUND_RESTRICTED, trackerType);
                }
                level = mConstantsObserver.mRestrictedBucketEnabled
                        && standbyBucket == STANDBY_BUCKET_RESTRICTED
                        ? RESTRICTION_LEVEL_RESTRICTED_BUCKET
                        : RESTRICTION_LEVEL_ADAPTIVE_BUCKET;
                if (calcTrackers) {
                    Pair<Integer, Integer> levelTypePair = calcAppRestrictionLevelFromTackers(
                            uid, packageName, RESTRICTION_LEVEL_MAX);
                    @RestrictionLevel int l = levelTypePair.first;
                    if (l == RESTRICTION_LEVEL_EXEMPTED) {
                        return new Pair<>(RESTRICTION_LEVEL_EXEMPTED, levelTypePair.second);
                    }
                    level = Math.max(l, level);
                    if (l == level) {
                        trackerType = levelTypePair.second;
                    }
                    if (level == RESTRICTION_LEVEL_BACKGROUND_RESTRICTED) {
                        // This level can't be entered without user consent
                        if (allowRequestBgRestricted) {
                            mBgHandler.obtainMessage(BgHandler.MSG_REQUEST_BG_RESTRICTED,
                                    uid, 0, packageName).sendToTarget();
                        }
                        // Lower the level.
                        levelTypePair = calcAppRestrictionLevelFromTackers(uid, packageName,
                                RESTRICTION_LEVEL_BACKGROUND_RESTRICTED);
                        level = levelTypePair.first;
                        trackerType = levelTypePair.second;
                    }
                }
                break;
        }
        return new Pair<>(level, trackerType);
    }

    /**
     * Ask each of the trackers for their proposed restriction levels for the given uid/package,
     * and return the most restrictive level along with the type of tracker which applied this
     * restriction level as a {@code Pair<@RestrictionLevel, @TrackerType>}.
     *
     * <p>Note, it's different from the {@link #getRestrictionLevel} where it returns the least
     * restrictive level. We're returning the most restrictive level here because each tracker
     * monitors certain dimensions of the app, the abusive behaviors could be detected in one or
     * more of these dimensions, but not necessarily all of them. </p>
     */
    private Pair<Integer, Integer> calcAppRestrictionLevelFromTackers(int uid, String packageName,
            @RestrictionLevel int maxLevel) {
        @RestrictionLevel int level = RESTRICTION_LEVEL_UNKNOWN;
        @RestrictionLevel int prevLevel = level;
        @TrackerType int trackerType = TRACKER_TYPE_UNKNOWN;
        final boolean isRestrictedBucketEnabled = mConstantsObserver.mRestrictedBucketEnabled;
        for (int i = mAppStateTrackers.size() - 1; i >= 0; i--) {
            @RestrictionLevel int l = mAppStateTrackers.get(i).getPolicy()
                    .getProposedRestrictionLevel(packageName, uid, maxLevel);
            if (!isRestrictedBucketEnabled && l == RESTRICTION_LEVEL_RESTRICTED_BUCKET) {
                l = RESTRICTION_LEVEL_ADAPTIVE_BUCKET;
            }
            level = Math.max(level, l);
            if (level != prevLevel) {
                trackerType = mAppStateTrackers.get(i).getType();
                prevLevel = level;
            }
        }
        return new Pair<>(level, trackerType);
    }

    private static @RestrictionLevel int standbyBucketToRestrictionLevel(
            @UsageStatsManager.StandbyBuckets int standbyBucket) {
        switch (standbyBucket) {
            case STANDBY_BUCKET_EXEMPTED:
                return RESTRICTION_LEVEL_EXEMPTED;
            case STANDBY_BUCKET_NEVER:
                return RESTRICTION_LEVEL_BACKGROUND_RESTRICTED;
            case STANDBY_BUCKET_ACTIVE:
            case STANDBY_BUCKET_WORKING_SET:
            case STANDBY_BUCKET_FREQUENT:
            case STANDBY_BUCKET_RARE:
                return RESTRICTION_LEVEL_ADAPTIVE_BUCKET;
            case STANDBY_BUCKET_RESTRICTED:
                return RESTRICTION_LEVEL_RESTRICTED_BUCKET;
            default:
                return RESTRICTION_LEVEL_UNKNOWN;
        }
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

    /**
     * @return The total foreground service durations for the given package/uid with given
     * foreground service type, or the total durations regardless the type if the given type is 0.
     */
    long getForegroundServiceTotalDurations(String packageName, int uid, long now,
            @ForegroundServiceType int serviceType) {
        return mInjector.getAppFGSTracker().getTotalDurations(packageName, uid, now,
                foregroundServiceTypeToIndex(serviceType));
    }

    /**
     * @return The total foreground service durations for the given uid with given
     * foreground service type, or the total durations regardless the type if the given type is 0.
     */
    long getForegroundServiceTotalDurations(int uid, long now,
            @ForegroundServiceType int serviceType) {
        return mInjector.getAppFGSTracker().getTotalDurations(uid, now,
                foregroundServiceTypeToIndex(serviceType));
    }

    /**
     * @return The foreground service durations since given timestamp for the given package/uid
     * with given foreground service type, or the total durations regardless the type if the given
     * type is 0.
     */
    long getForegroundServiceTotalDurationsSince(String packageName, int uid, long since, long now,
            @ForegroundServiceType int serviceType) {
        return mInjector.getAppFGSTracker().getTotalDurationsSince(packageName, uid, since, now,
                foregroundServiceTypeToIndex(serviceType));
    }

    /**
     * @return The foreground service durations since given timestamp for the given uid with given
     * foreground service type, or the total durations regardless the type if the given type is 0.
     */
    long getForegroundServiceTotalDurationsSince(int uid, long since, long now,
            @ForegroundServiceType int serviceType) {
        return mInjector.getAppFGSTracker().getTotalDurationsSince(uid, since, now,
                foregroundServiceTypeToIndex(serviceType));
    }

    /**
     * @return The total durations for the given package/uid with active media session.
     */
    long getMediaSessionTotalDurations(String packageName, int uid, long now) {
        return mInjector.getAppMediaSessionTracker().getTotalDurations(packageName, uid, now);
    }

    /**
     * @return The total durations for the given uid with active media session.
     */
    long getMediaSessionTotalDurations(int uid, long now) {
        return mInjector.getAppMediaSessionTracker().getTotalDurations(uid, now);
    }

    /**
     * @return The durations since given timestamp for the given package/uid with
     * active media session.
     */
    long getMediaSessionTotalDurationsSince(String packageName, int uid, long since, long now) {
        return mInjector.getAppMediaSessionTracker().getTotalDurationsSince(packageName, uid, since,
                now);
    }

    /**
     * @return The durations since given timestamp for the given uid with active media session.
     */
    long getMediaSessionTotalDurationsSince(int uid, long since, long now) {
        return mInjector.getAppMediaSessionTracker().getTotalDurationsSince(uid, since, now);
    }

    /**
     * @return The durations over the given window, where the given package/uid has either
     * foreground services with type "mediaPlayback" running, or active media session running.
     */
    long getCompositeMediaPlaybackDurations(String packageName, int uid, long now, long window) {
        final long since = Math.max(0, now - window);
        final long mediaPlaybackDuration = Math.max(
                getMediaSessionTotalDurationsSince(packageName, uid, since, now),
                getForegroundServiceTotalDurationsSince(packageName, uid, since, now,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK));
        return mediaPlaybackDuration;
    }

    /**
     * @return The durations over the given window, where the given uid has either foreground
     * services with type "mediaPlayback" running, or active media session running.
     */
    long getCompositeMediaPlaybackDurations(int uid, long now, long window) {
        final long since = Math.max(0, now - window);
        final long mediaPlaybackDuration = Math.max(
                getMediaSessionTotalDurationsSince(uid, since, now),
                getForegroundServiceTotalDurationsSince(uid, since, now,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK));
        return mediaPlaybackDuration;
    }

    /**
     * @return If the given package/uid has an active foreground service running.
     */
    boolean hasForegroundServices(String packageName, int uid) {
        return mInjector.getAppFGSTracker().hasForegroundServices(packageName, uid);
    }

    /**
     * @return If the given uid has an active foreground service running.
     */
    boolean hasForegroundServices(int uid) {
        return mInjector.getAppFGSTracker().hasForegroundServices(uid);
    }

    /**
     * @return If the given package/uid has a foreground service notification or not.
     */
    boolean hasForegroundServiceNotifications(String packageName, int uid) {
        return mInjector.getAppFGSTracker().hasForegroundServiceNotifications(packageName, uid);
    }

    /**
     * @return If the given uid has a foreground service notification or not.
     */
    boolean hasForegroundServiceNotifications(int uid) {
        return mInjector.getAppFGSTracker().hasForegroundServiceNotifications(uid);
    }

    /**
     * @return The to-be-exempted battery usage of the given UID in the given duration; it could
     *         be considered as "exempted" due to various use cases, i.e. media playback.
     */
    ImmutableBatteryUsage getUidBatteryExemptedUsageSince(int uid, long since, long now,
            int types) {
        return mInjector.getAppBatteryExemptionTracker()
                .getUidBatteryExemptedUsageSince(uid, since, now, types);
    }

    /**
     * @return The total battery usage of the given UID since the system boots.
     */
    @NonNull ImmutableBatteryUsage getUidBatteryUsage(int uid) {
        return mInjector.getUidBatteryUsageProvider().getUidBatteryUsage(uid);
    }

    interface UidBatteryUsageProvider {
        /**
         * @return The total battery usage of the given UID since the system boots.
         */
        @NonNull ImmutableBatteryUsage getUidBatteryUsage(int uid);
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.println("APP BACKGROUND RESTRICTIONS");
        prefix = "  " + prefix;
        pw.print(prefix);
        pw.println("BACKGROUND RESTRICTION LEVEL SETTINGS");
        mRestrictionSettings.dump(pw, "  " + prefix);
        mConstantsObserver.dump(pw, "  " + prefix);
        for (int i = 0, size = mAppStateTrackers.size(); i < size; i++) {
            pw.println();
            mAppStateTrackers.get(i).dump(pw, prefix);
        }
    }

    void dumpAsProto(ProtoOutputStream proto, int uid) {
        for (int i = 0, size = mAppStateTrackers.size(); i < size; i++) {
            mAppStateTrackers.get(i).dumpAsProto(proto, uid);
        }
    }

    private int getRestrictionLevelStatsd(@RestrictionLevel int level) {
        switch (level) {
            case RESTRICTION_LEVEL_UNKNOWN:
                return FrameworkStatsLog
                        .APP_BACKGROUND_RESTRICTIONS_INFO__RESTRICTION_LEVEL__LEVEL_UNKNOWN;
            case RESTRICTION_LEVEL_UNRESTRICTED:
                return FrameworkStatsLog
                        .APP_BACKGROUND_RESTRICTIONS_INFO__RESTRICTION_LEVEL__LEVEL_UNRESTRICTED;
            case RESTRICTION_LEVEL_EXEMPTED:
                return FrameworkStatsLog
                        .APP_BACKGROUND_RESTRICTIONS_INFO__RESTRICTION_LEVEL__LEVEL_EXEMPTED;
            case RESTRICTION_LEVEL_ADAPTIVE_BUCKET:
                return FrameworkStatsLog
                        .APP_BACKGROUND_RESTRICTIONS_INFO__RESTRICTION_LEVEL__LEVEL_ADAPTIVE_BUCKET;
            case RESTRICTION_LEVEL_RESTRICTED_BUCKET:
                return FrameworkStatsLog
                        .APP_BACKGROUND_RESTRICTIONS_INFO__RESTRICTION_LEVEL__LEVEL_RESTRICTED_BUCKET;
            case RESTRICTION_LEVEL_BACKGROUND_RESTRICTED:
                return FrameworkStatsLog
                        .APP_BACKGROUND_RESTRICTIONS_INFO__RESTRICTION_LEVEL__LEVEL_BACKGROUND_RESTRICTED;
            case RESTRICTION_LEVEL_HIBERNATION:
                return FrameworkStatsLog
                        .APP_BACKGROUND_RESTRICTIONS_INFO__RESTRICTION_LEVEL__LEVEL_HIBERNATION;
            default:
                return FrameworkStatsLog
                        .APP_BACKGROUND_RESTRICTIONS_INFO__RESTRICTION_LEVEL__LEVEL_UNKNOWN;
        }
    }

    private int getThresholdStatsd(int reason) {
        switch (reason) {
            case REASON_MAIN_FORCED_BY_SYSTEM:
                return FrameworkStatsLog
                        .APP_BACKGROUND_RESTRICTIONS_INFO__THRESHOLD__THRESHOLD_RESTRICTED;
            case REASON_MAIN_FORCED_BY_USER:
                return FrameworkStatsLog
                        .APP_BACKGROUND_RESTRICTIONS_INFO__THRESHOLD__THRESHOLD_USER;
            default:
                return FrameworkStatsLog
                        .APP_BACKGROUND_RESTRICTIONS_INFO__THRESHOLD__THRESHOLD_UNKNOWN;
        }
    }

    private int getTrackerTypeStatsd(@TrackerType int type) {
        switch (type) {
            case TRACKER_TYPE_BATTERY:
                return FrameworkStatsLog.APP_BACKGROUND_RESTRICTIONS_INFO__TRACKER__BATTERY_TRACKER;
            case TRACKER_TYPE_BATTERY_EXEMPTION:
                return FrameworkStatsLog
                        .APP_BACKGROUND_RESTRICTIONS_INFO__TRACKER__BATTERY_EXEMPTION_TRACKER;
            case TRACKER_TYPE_FGS:
                return FrameworkStatsLog.APP_BACKGROUND_RESTRICTIONS_INFO__TRACKER__FGS_TRACKER;
            case TRACKER_TYPE_MEDIA_SESSION:
                return FrameworkStatsLog
                        .APP_BACKGROUND_RESTRICTIONS_INFO__TRACKER__MEDIA_SESSION_TRACKER;
            case TRACKER_TYPE_PERMISSION:
                return FrameworkStatsLog
                        .APP_BACKGROUND_RESTRICTIONS_INFO__TRACKER__PERMISSION_TRACKER;
            case TRACKER_TYPE_BROADCAST_EVENTS:
                return FrameworkStatsLog
                        .APP_BACKGROUND_RESTRICTIONS_INFO__TRACKER__BROADCAST_EVENTS_TRACKER;
            case TRACKER_TYPE_BIND_SERVICE_EVENTS:
                return FrameworkStatsLog
                        .APP_BACKGROUND_RESTRICTIONS_INFO__TRACKER__BIND_SERVICE_EVENTS_TRACKER;
            default:
                return FrameworkStatsLog.APP_BACKGROUND_RESTRICTIONS_INFO__TRACKER__UNKNOWN_TRACKER;
        }
    }

    private @ExemptionReason int getExemptionReasonStatsd(int uid, @RestrictionLevel int level) {
        if (level != RESTRICTION_LEVEL_EXEMPTED) {
            return FrameworkStatsLog
                    .APP_BACKGROUND_RESTRICTIONS_INFO__EXEMPTION_REASON__REASON_DENIED;
        }

        @ReasonCode final int reasonCode = getBackgroundRestrictionExemptionReason(uid);
        return getExemptionReasonForStatsd(reasonCode);
    }

    private int getOptimizationLevelStatsd(@RestrictionLevel int level) {
        switch (level) {
            case RESTRICTION_LEVEL_UNKNOWN:
                return FrameworkStatsLog.APP_BACKGROUND_RESTRICTIONS_INFO__OPT_LEVEL__UNKNOWN;
            case RESTRICTION_LEVEL_UNRESTRICTED:
                return FrameworkStatsLog.APP_BACKGROUND_RESTRICTIONS_INFO__OPT_LEVEL__NOT_OPTIMIZED;
            case RESTRICTION_LEVEL_ADAPTIVE_BUCKET:
                return FrameworkStatsLog.APP_BACKGROUND_RESTRICTIONS_INFO__OPT_LEVEL__OPTIMIZED;
            case RESTRICTION_LEVEL_BACKGROUND_RESTRICTED:
                return FrameworkStatsLog
                        .APP_BACKGROUND_RESTRICTIONS_INFO__OPT_LEVEL__BACKGROUND_RESTRICTED;
            default:
                return FrameworkStatsLog.APP_BACKGROUND_RESTRICTIONS_INFO__OPT_LEVEL__UNKNOWN;
        }
    }

    @SuppressWarnings("AndroidFrameworkCompatChange")
    private int getTargetSdkStatsd(String packageName) {
        final PackageManager pm = mInjector.getPackageManager();
        if (pm == null) {
            return FrameworkStatsLog.APP_BACKGROUND_RESTRICTIONS_INFO__TARGET_SDK__SDK_UNKNOWN;
        }
        try {
            final PackageInfo pkg = pm.getPackageInfo(packageName, 0 /* flags */);
            if (pkg == null || pkg.applicationInfo == null) {
                return FrameworkStatsLog.APP_BACKGROUND_RESTRICTIONS_INFO__TARGET_SDK__SDK_UNKNOWN;
            }
            final int targetSdk = pkg.applicationInfo.targetSdkVersion;
            if (targetSdk < Build.VERSION_CODES.S) {
                return FrameworkStatsLog.APP_BACKGROUND_RESTRICTIONS_INFO__TARGET_SDK__SDK_PRE_S;
            } else if (targetSdk < Build.VERSION_CODES.TIRAMISU) {
                return FrameworkStatsLog.APP_BACKGROUND_RESTRICTIONS_INFO__TARGET_SDK__SDK_S;
            } else if (targetSdk == Build.VERSION_CODES.TIRAMISU) {
                return FrameworkStatsLog.APP_BACKGROUND_RESTRICTIONS_INFO__TARGET_SDK__SDK_T;
            } else {
                return FrameworkStatsLog.APP_BACKGROUND_RESTRICTIONS_INFO__TARGET_SDK__SDK_UNKNOWN;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return FrameworkStatsLog.APP_BACKGROUND_RESTRICTIONS_INFO__TARGET_SDK__SDK_UNKNOWN;
    }

    private void applyRestrictionLevel(String pkgName, int uid,
            @RestrictionLevel int level, @TrackerType int trackerType,
            int curBucket, boolean allowUpdateBucket, int reason, int subReason) {
        int curLevel;
        int prevReason;
        final AppStandbyInternal appStandbyInternal = mInjector.getAppStandbyInternal();
        synchronized (mSettingsLock) {
            curLevel = getRestrictionLevel(uid, pkgName);
            if (curLevel == level) {
                // Nothing to do.
                return;
            }
            final int levelOfBucket = standbyBucketToRestrictionLevel(curBucket);
            if (levelOfBucket == level) {
                // If the standby bucket yield the same level, use the reason from standby bucket.
                final int bucketReason = appStandbyInternal.getAppStandbyBucketReason(
                        pkgName, UserHandle.getUserId(uid), SystemClock.elapsedRealtime());
                if (bucketReason != 0) {
                    reason = bucketReason & REASON_MAIN_MASK;
                    subReason = bucketReason & REASON_SUB_MASK;
                }
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
        if (level >= RESTRICTION_LEVEL_RESTRICTED_BUCKET
                && curLevel < RESTRICTION_LEVEL_RESTRICTED_BUCKET) {
            if (!mConstantsObserver.mRestrictedBucketEnabled) {
                return;
            }
            // Moving the app standby bucket to restricted in the meanwhile.
            if (DEBUG_BG_RESTRICTION_CONTROLLER
                    && level == RESTRICTION_LEVEL_BACKGROUND_RESTRICTED) {
                Slog.i(TAG, pkgName + "/" + UserHandle.formatUid(uid)
                        + " is bg-restricted, moving to restricted standby bucket");
            }
            if (curBucket != STANDBY_BUCKET_RESTRICTED
                    && (mConstantsObserver.mBgAutoRestrictedBucket
                    || level == RESTRICTION_LEVEL_RESTRICTED_BUCKET)) {
                // restrict the app if it hasn't done so.
                boolean doIt = true;
                synchronized (mSettingsLock) {
                    final int index = mActiveUids.indexOfKey(uid, pkgName);
                    if (index >= 0) {
                        // It's currently active, enqueue it.
                        final int localReason = reason;
                        final int localSubReason = subReason;
                        mActiveUids.add(uid, pkgName, () -> appStandbyInternal.restrictApp(
                                pkgName, UserHandle.getUserId(uid), localReason, localSubReason));
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
            synchronized (mSettingsLock) {
                final int index = mActiveUids.indexOfKey(uid, pkgName);
                if (index >= 0) {
                    mActiveUids.add(uid, pkgName, null);
                }
            }
            appStandbyInternal.maybeUnrestrictApp(pkgName, UserHandle.getUserId(uid),
                    prevReason & REASON_MAIN_MASK, prevReason & REASON_SUB_MASK,
                    reason, subReason);
        }

        FrameworkStatsLog.write(FrameworkStatsLog.APP_BACKGROUND_RESTRICTIONS_INFO, uid,
                getRestrictionLevelStatsd(level),
                getThresholdStatsd(reason),
                getTrackerTypeStatsd(trackerType),
                null, // FgsTrackerInfo
                null, // BatteryTrackerInfo
                null, // BroadcastEventsTrackerInfo
                null, // BindServiceEventsTrackerInfo
                getExemptionReasonStatsd(uid, level),
                getOptimizationLevelStatsd(level),
                getTargetSdkStatsd(pkgName),
                ActivityManager.isLowRamDeviceStatic());
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
                    TRACKER_TYPE_UNKNOWN, curBucket, true, REASON_MAIN_FORCED_BY_USER,
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
            final Pair<Integer, Integer> levelTypePair = calcAppRestrictionLevel(
                    UserHandle.getUserId(uid), uid, pkgName, tentativeBucket, false, true);

            applyRestrictionLevel(pkgName, uid, levelTypePair.first, levelTypePair.second,
                    curBucket, true, REASON_MAIN_USAGE, REASON_SUB_USAGE_USER_INTERACTION);
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
        synchronized (mSettingsLock) {
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
        final Pair<Integer, Integer> levelTypePair = calcAppRestrictionLevel(
                userId, uid, packageName, bucket, false, false);
        applyRestrictionLevel(packageName, uid, levelTypePair.first, levelTypePair.second,
                bucket, false, REASON_MAIN_DEFAULT, REASON_SUB_DEFAULT_UNDEFINED);
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

    void handleUidProcStateChanged(int uid, int procState) {
        for (int i = 0, size = mAppStateTrackers.size(); i < size; i++) {
            mAppStateTrackers.get(i).onUidProcStateChanged(uid, procState);
        }
    }

    void handleUidGone(int uid) {
        for (int i = 0, size = mAppStateTrackers.size(); i < size; i++) {
            mAppStateTrackers.get(i).onUidGone(uid);
        }
    }

    static class NotificationHelper {
        static final String PACKAGE_SCHEME = "package";
        static final String GROUP_KEY = "com.android.app.abusive_bg_apps";

        static final int SUMMARY_NOTIFICATION_ID = SystemMessage.NOTE_ABUSIVE_BG_APPS_BASE;

        static final int NOTIFICATION_TYPE_ABUSIVE_CURRENT_DRAIN = 0;
        static final int NOTIFICATION_TYPE_LONG_RUNNING_FGS = 1;
        static final int NOTIFICATION_TYPE_LAST = 2;

        static final String ATTR_LAST_BATTERY_NOTIFICATION_TIME = "last_batt_noti_ts";
        static final String ATTR_LAST_LONG_FGS_NOTIFICATION_TIME = "last_long_fgs_noti_ts";

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

        static final String[] NOTIFICATION_TIME_ATTRS = {
            ATTR_LAST_BATTERY_NOTIFICATION_TIME,
            ATTR_LAST_LONG_FGS_NOTIFICATION_TIME,
        };

        static @NotificationType int notificationTimeAttrToType(@NonNull String attr) {
            switch (attr) {
                case ATTR_LAST_BATTERY_NOTIFICATION_TIME:
                    return NOTIFICATION_TYPE_ABUSIVE_CURRENT_DRAIN;
                case ATTR_LAST_LONG_FGS_NOTIFICATION_TIME:
                    return NOTIFICATION_TYPE_LONG_RUNNING_FGS;
            }
            throw new IllegalArgumentException();
        }

        static @NonNull String notificationTypeToTimeAttr(@NotificationType int type) {
            return NOTIFICATION_TIME_ATTRS[type];
        }

        static final String ACTION_FGS_MANAGER_TRAMPOLINE =
                "com.android.server.am.ACTION_FGS_MANAGER_TRAMPOLINE";

        static String notificationTypeToString(@NotificationType int notificationType) {
            return NOTIFICATION_TYPE_STRINGS[notificationType];
        }

        private final AppRestrictionController mBgController;
        private final NotificationManager mNotificationManager;
        private final Injector mInjector;
        private final Object mLock;
        private final Object mSettingsLock;
        private final Context mContext;

        private final BroadcastReceiver mActionButtonReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                switch (intent.getAction()) {
                    case ACTION_FGS_MANAGER_TRAMPOLINE:
                        final String packageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
                        final int uid = intent.getIntExtra(Intent.EXTRA_UID, 0);
                        cancelRequestBgRestrictedIfNecessary(packageName, uid);
                        final Intent newIntent = new Intent(ACTION_SHOW_FOREGROUND_SERVICE_MANAGER);
                        newIntent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
                        // Task manager runs in SystemUI, which is SYSTEM user only.
                        mContext.sendBroadcastAsUser(newIntent, UserHandle.SYSTEM);
                        break;
                }
            }
        };

        @GuardedBy("mSettingsLock")
        private int mNotificationIDStepper = SUMMARY_NOTIFICATION_ID + 1;

        NotificationHelper(AppRestrictionController controller) {
            mBgController = controller;
            mInjector = controller.mInjector;
            mNotificationManager = mInjector.getNotificationManager();
            mLock = controller.mLock;
            mSettingsLock = controller.mSettingsLock;
            mContext = mInjector.getContext();
        }

        void onSystemReady() {
            mContext.registerReceiverForAllUsers(mActionButtonReceiver,
                    new IntentFilter(ACTION_FGS_MANAGER_TRAMPOLINE),
                    MANAGE_ACTIVITY_TASKS, mBgController.mBgHandler, Context.RECEIVER_NOT_EXPORTED);
        }

        void postRequestBgRestrictedIfNecessary(String packageName, int uid) {
            final Intent intent = new Intent(Settings.ACTION_VIEW_ADVANCED_POWER_USAGE_DETAIL);
            intent.setData(Uri.fromParts(PACKAGE_SCHEME, packageName, null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            final PendingIntent pendingIntent = PendingIntent.getActivityAsUser(mContext, 0,
                    intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, null,
                    UserHandle.of(UserHandle.getUserId(uid)));
            Notification.Action[] actions = null;
            final boolean hasForegroundServices =
                    mBgController.hasForegroundServices(packageName, uid);
            final boolean hasForegroundServiceNotifications =
                    mBgController.hasForegroundServiceNotifications(packageName, uid);
            if (!mBgController.mConstantsObserver.mBgPromptFgsWithNotiToBgRestricted) {
                // We're not going to prompt the user if the FGS is active and its notification
                // is still showing (not dismissed/silenced/denied).
                if (hasForegroundServices && hasForegroundServiceNotifications) {
                    if (DEBUG_BG_RESTRICTION_CONTROLLER) {
                        Slog.i(TAG, "Not requesting bg-restriction due to FGS with notification");
                    }
                    return;
                }
            }
            if (ENABLE_SHOW_FOREGROUND_SERVICE_MANAGER
                    && ENABLE_SHOW_FGS_MANAGER_ACTION_ON_BG_RESTRICTION
                    && hasForegroundServices) {
                final Intent trampoline = new Intent(ACTION_FGS_MANAGER_TRAMPOLINE);
                trampoline.setPackage("android");
                trampoline.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName);
                trampoline.putExtra(Intent.EXTRA_UID, uid);
                final PendingIntent fgsMgrTrampoline = PendingIntent.getBroadcastAsUser(
                        mContext, 0, trampoline,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                        UserHandle.CURRENT);
                actions = new Notification.Action[] {
                    new Notification.Action.Builder(null,
                            mContext.getString(
                            com.android.internal.R.string.notification_action_check_bg_apps),
                            fgsMgrTrampoline)
                            .build()
                };
            }
            postNotificationIfNecessary(NOTIFICATION_TYPE_ABUSIVE_CURRENT_DRAIN,
                    com.android.internal.R.string.notification_title_abusive_bg_apps,
                    com.android.internal.R.string.notification_content_abusive_bg_apps,
                    pendingIntent, packageName, uid, actions);
        }

        void postLongRunningFgsIfNecessary(String packageName, int uid) {
            PendingIntent pendingIntent;
            if (!mBgController.mConstantsObserver.mBgPromptFgsWithNotiOnLongRunning
                    && mBgController.hasForegroundServiceNotifications(packageName, uid)) {
                if (DEBUG_BG_RESTRICTION_CONTROLLER) {
                    Slog.i(TAG, "Not prompt long-running due to FGS with notification");
                }
                return;
            }
            if (ENABLE_SHOW_FOREGROUND_SERVICE_MANAGER) {
                final Intent intent = new Intent(ACTION_SHOW_FOREGROUND_SERVICE_MANAGER);
                intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
                // Task manager runs in SystemUI, which is SYSTEM user only.
                pendingIntent = PendingIntent.getBroadcastAsUser(mContext, 0,
                        intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                        UserHandle.SYSTEM);
            } else {
                final Intent intent = new Intent(Settings.ACTION_VIEW_ADVANCED_POWER_USAGE_DETAIL);
                intent.setData(Uri.fromParts(PACKAGE_SCHEME, packageName, null));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                pendingIntent = PendingIntent.getActivityAsUser(mContext, 0,
                        intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                        null, UserHandle.of(UserHandle.getUserId(uid)));
            }

            postNotificationIfNecessary(NOTIFICATION_TYPE_LONG_RUNNING_FGS,
                    com.android.internal.R.string.notification_title_long_running_fgs,
                    com.android.internal.R.string.notification_content_long_running_fgs,
                    pendingIntent, packageName, uid, null);
        }

        long getNotificationMinInterval(@NotificationType int notificationType) {
            switch (notificationType) {
                case NOTIFICATION_TYPE_ABUSIVE_CURRENT_DRAIN:
                    return mBgController.mConstantsObserver.mBgAbusiveNotificationMinIntervalMs;
                case NOTIFICATION_TYPE_LONG_RUNNING_FGS:
                    return mBgController.mConstantsObserver.mBgLongFgsNotificationMinIntervalMs;
                default:
                    return 0L;
            }
        }

        int getNotificationIdIfNecessary(@NotificationType int notificationType,
                String packageName, int uid) {
            synchronized (mSettingsLock) {
                final RestrictionSettings.PkgSettings settings = mBgController.mRestrictionSettings
                        .getRestrictionSettingsLocked(uid, packageName);
                if (settings == null) {
                    return 0;
                }

                final long now = mInjector.currentTimeMillis();
                final long lastNotificationShownTime =
                        settings.getLastNotificationTime(notificationType);
                if (lastNotificationShownTime != 0 && (lastNotificationShownTime
                        + getNotificationMinInterval(notificationType) > now)) {
                    if (DEBUG_BG_RESTRICTION_CONTROLLER) {
                        Slog.i(TAG, "Not showing notification as last notification was shown "
                                + TimeUtils.formatDuration(now - lastNotificationShownTime)
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
                            + ", lastShown=" + lastNotificationShownTime);
                }
                return notificationId;
            }
        }

        void postNotificationIfNecessary(@NotificationType int notificationType, int titleRes,
                int messageRes, PendingIntent pendingIntent, String packageName, int uid,
                @Nullable Notification.Action[] actions) {
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
                    ai != null ? ai.loadLabel(pm) : packageName);
            final Icon icon = ai != null ? Icon.createWithResource(packageName, ai.icon) : null;

            postNotification(notificationId, packageName, uid, title, message, icon, pendingIntent,
                    actions);
        }

        void postNotification(int notificationId, String packageName, int uid, String title,
                String message, Icon icon, PendingIntent pendingIntent,
                @Nullable Notification.Action[] actions) {
            final UserHandle targetUser = UserHandle.of(UserHandle.getUserId(uid));
            postSummaryNotification(targetUser);

            final Notification.Builder notificationBuilder = new Notification.Builder(mContext,
                    ABUSIVE_BACKGROUND_APPS)
                    .setAutoCancel(true)
                    .setGroup(GROUP_KEY)
                    .setWhen(mInjector.currentTimeMillis())
                    .setSmallIcon(com.android.internal.R.drawable.stat_sys_warning)
                    .setColor(mContext.getColor(
                            com.android.internal.R.color.system_notification_accent_color))
                    .setContentTitle(title)
                    .setContentText(message)
                    .setContentIntent(pendingIntent);
            if (icon != null) {
                notificationBuilder.setLargeIcon(icon);
            }
            if (actions != null) {
                for (Notification.Action action : actions) {
                    notificationBuilder.addAction(action);
                }
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
            synchronized (mSettingsLock) {
                final RestrictionSettings.PkgSettings settings = mBgController.mRestrictionSettings
                        .getRestrictionSettingsLocked(uid, packageName);
                if (settings != null) {
                    final int notificationId =
                            settings.getNotificationId(NOTIFICATION_TYPE_ABUSIVE_CURRENT_DRAIN);
                    if (notificationId > 0) {
                        mNotificationManager.cancel(notificationId);
                    }
                }
            }
        }

        void cancelLongRunningFGSNotificationIfNecessary(String packageName, int uid) {
            synchronized (mSettingsLock) {
                final RestrictionSettings.PkgSettings settings = mBgController.mRestrictionSettings
                        .getRestrictionSettingsLocked(uid, packageName);
                if (settings != null) {
                    final int notificationId =
                            settings.getNotificationId(NOTIFICATION_TYPE_LONG_RUNNING_FGS);
                    if (notificationId > 0) {
                        mNotificationManager.cancel(notificationId);
                    }
                }
            }
        }
    }

    void handleUidInactive(int uid, boolean disabled) {
        final ArrayList<Runnable> pendingTasks = mTmpRunnables;
        synchronized (mSettingsLock) {
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
        synchronized (mSettingsLock) {
            final AppStandbyInternal appStandbyInternal = mInjector.getAppStandbyInternal();
            final int userId = UserHandle.getUserId(uid);
            mRestrictionSettings.forEachPackageInUidLocked(uid, (pkgName, level, reason) -> {
                if (mConstantsObserver.mBgAutoRestrictedBucket
                        && level == RESTRICTION_LEVEL_BACKGROUND_RESTRICTED) {
                    mActiveUids.add(uid, pkgName, () -> appStandbyInternal.restrictApp(pkgName,
                            userId, reason & REASON_MAIN_MASK, reason & REASON_SUB_MASK));
                } else {
                    mActiveUids.add(uid, pkgName, null);
                }
            });
        }
    }

    boolean isOnDeviceIdleAllowlist(int uid) {
        final int appId = UserHandle.getAppId(uid);

        return Arrays.binarySearch(mDeviceIdleAllowlist, appId) >= 0
                || Arrays.binarySearch(mDeviceIdleExceptIdleAllowlist, appId) >= 0;
    }

    void setDeviceIdleAllowlist(int[] allAppids, int[] exceptIdleAppids) {
        mDeviceIdleAllowlist = allAppids;
        mDeviceIdleExceptIdleAllowlist = exceptIdleAppids;
    }

    /**
     * @return The reason code of whether or not the given UID should be exempted from background
     * restrictions here.
     *
     * <p>
     * Note: Call it with caution as it'll try to acquire locks in other services.
     * </p>
     */
    @ReasonCode
    int getBackgroundRestrictionExemptionReason(int uid) {
        if (UserHandle.isCore(uid)) {
            return REASON_SYSTEM_UID;
        }
        if (isOnDeviceIdleAllowlist(uid)) {
            return REASON_ALLOWLISTED_PACKAGE;
        }
        final ActivityManagerInternal am = mInjector.getActivityManagerInternal();
        if (am.isAssociatedCompanionApp(UserHandle.getUserId(uid), uid)) {
            return REASON_COMPANION_DEVICE_MANAGER;
        }
        if (UserManager.isDeviceInDemoMode(mContext)) {
            return REASON_DEVICE_DEMO_MODE;
        }
        if (am.isDeviceOwner(uid)) {
            return REASON_DEVICE_OWNER;
        }
        if (am.isProfileOwner(uid)) {
            return REASON_PROFILE_OWNER;
        }
        final int uidProcState = am.getUidProcessState(uid);
        if (uidProcState <= PROCESS_STATE_PERSISTENT) {
            return REASON_PROC_STATE_PERSISTENT;
        } else if (uidProcState <= PROCESS_STATE_PERSISTENT_UI) {
            return REASON_PROC_STATE_PERSISTENT_UI;
        }
        final String[] packages = mInjector.getPackageManager().getPackagesForUid(uid);
        if (packages != null) {
            final AppOpsManager appOpsManager = mInjector.getAppOpsManager();
            for (String pkg : packages) {
                if (appOpsManager.checkOpNoThrow(AppOpsManager.OP_ACTIVATE_VPN,
                        uid, pkg) == AppOpsManager.MODE_ALLOWED) {
                    return REASON_OP_ACTIVATE_VPN;
                } else if (appOpsManager.checkOpNoThrow(AppOpsManager.OP_ACTIVATE_PLATFORM_VPN,
                        uid, pkg) == AppOpsManager.MODE_ALLOWED) {
                    return REASON_OP_ACTIVATE_PLATFORM_VPN;
                } else if (isSystemModule(pkg)) {
                    return REASON_SYSTEM_MODULE;
                } else if (isCarrierApp(pkg)) {
                    return REASON_CARRIER_PRIVILEGED_APP;
                } else if (isExemptedFromSysConfig(pkg)) {
                    return REASON_SYSTEM_ALLOW_LISTED;
                } else if (mConstantsObserver.mBgRestrictionExemptedPackages.contains(pkg)) {
                    return REASON_ALLOWLISTED_PACKAGE;
                }
            }
        }
        if (isRoleHeldByUid(RoleManager.ROLE_DIALER, uid)) {
            return REASON_ROLE_DIALER;
        }
        if (isRoleHeldByUid(RoleManager.ROLE_EMERGENCY, uid)) {
            return REASON_ROLE_EMERGENCY;
        }
        return REASON_DENIED;
    }

    private boolean isCarrierApp(String packageName) {
        synchronized (mCarrierPrivilegedLock) {
            if (mCarrierPrivilegedApps == null) {
                fetchCarrierPrivilegedAppsCPL();
            }
            if (mCarrierPrivilegedApps != null) {
                return mCarrierPrivilegedApps.contains(packageName);
            }
            return false;
        }
    }

    private void clearCarrierPrivilegedApps() {
        if (DEBUG_BG_RESTRICTION_CONTROLLER) {
            Slog.i(TAG, "Clearing carrier privileged apps list");
        }
        synchronized (mCarrierPrivilegedLock) {
            mCarrierPrivilegedApps = null; // Need to be refetched.
        }
    }

    @GuardedBy("mCarrierPrivilegedLock")
    private void fetchCarrierPrivilegedAppsCPL() {
        final TelephonyManager telephonyManager = mInjector.getTelephonyManager();
        mCarrierPrivilegedApps =
                telephonyManager.getCarrierPrivilegedPackagesForAllActiveSubscriptions();
        if (DEBUG_BG_RESTRICTION_CONTROLLER) {
            Slog.d(TAG, "apps with carrier privilege " + mCarrierPrivilegedApps);
        }
    }

    private boolean isRoleHeldByUid(@NonNull String roleName, int uid) {
        synchronized (mLock) {
            final ArrayList<String> roles = mUidRolesMapping.get(uid);
            return roles != null && roles.indexOf(roleName) >= 0;
        }
    }

    private void initRolesInInterest() {
        final int[] allUsers = mInjector.getUserManagerInternal().getUserIds();
        for (String role : ROLES_IN_INTEREST) {
            if (mInjector.getRoleManager().isRoleAvailable(role)) {
                for (int userId : allUsers) {
                    final UserHandle user = UserHandle.of(userId);
                    onRoleHoldersChanged(role, user);
                }
            }
        }
    }

    private void onRoleHoldersChanged(@NonNull String roleName, @NonNull UserHandle user) {
        final List<String> rolePkgs = mInjector.getRoleManager().getRoleHoldersAsUser(
                roleName, user);
        final ArraySet<Integer> roleUids = new ArraySet<>();
        final int userId = user.getIdentifier();
        if (rolePkgs != null) {
            final PackageManagerInternal pm = mInjector.getPackageManagerInternal();
            for (String pkg: rolePkgs) {
                roleUids.add(pm.getPackageUid(pkg, STOCK_PM_FLAGS, userId));
            }
        }
        synchronized (mLock) {
            for (int i = mUidRolesMapping.size() - 1; i >= 0; i--) {
                final int uid = mUidRolesMapping.keyAt(i);
                if (UserHandle.getUserId(uid) != userId) {
                    continue;
                }
                final ArrayList<String> roles = mUidRolesMapping.valueAt(i);
                final int index = roles.indexOf(roleName);
                final boolean isRole = roleUids.contains(uid);
                if (index >= 0) {
                    if (!isRole) { // Not holding this role anymore, remove it.
                        roles.remove(index);
                        if (roles.isEmpty()) {
                            mUidRolesMapping.removeAt(i);
                        }
                    }
                } else if (isRole) { // Got this new role, add it.
                    roles.add(roleName);
                    roleUids.remove(uid);
                }
            }
            for (int i = roleUids.size() - 1; i >= 0; i--) { // Take care of the leftovers.
                final ArrayList<String> roles = new ArrayList<>();
                roles.add(roleName);
                mUidRolesMapping.put(roleUids.valueAt(i), roles);
            }
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

    String getPackageName(int pid) {
        return mInjector.getPackageName(pid);
    }

    static class BgHandler extends Handler {
        static final int MSG_BACKGROUND_RESTRICTION_CHANGED = 0;
        static final int MSG_APP_RESTRICTION_LEVEL_CHANGED = 1;
        static final int MSG_APP_STANDBY_BUCKET_CHANGED = 2;
        static final int MSG_USER_INTERACTION_STARTED = 3;
        static final int MSG_REQUEST_BG_RESTRICTED = 4;
        static final int MSG_UID_IDLE = 5;
        static final int MSG_UID_ACTIVE = 6;
        static final int MSG_UID_GONE = 7;
        static final int MSG_UID_PROC_STATE_CHANGED = 8;
        static final int MSG_CANCEL_REQUEST_BG_RESTRICTED = 9;
        static final int MSG_LOAD_RESTRICTION_SETTINGS = 10;
        static final int MSG_PERSIST_RESTRICTION_SETTINGS = 11;

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
                case MSG_UID_IDLE: {
                    c.handleUidInactive(msg.arg1, msg.arg2 == 1);
                } break;
                case MSG_UID_ACTIVE: {
                    c.handleUidActive(msg.arg1);
                } break;
                case MSG_CANCEL_REQUEST_BG_RESTRICTED: {
                    c.handleCancelRequestBgRestricted((String) msg.obj, msg.arg1);
                } break;
                case MSG_UID_PROC_STATE_CHANGED: {
                    c.handleUidProcStateChanged(msg.arg1, msg.arg2);
                } break;
                case MSG_UID_GONE: {
                    // It also means this UID is inactive now.
                    c.handleUidInactive(msg.arg1, msg.arg2 == 1);
                    c.handleUidGone(msg.arg1);
                } break;
                case MSG_LOAD_RESTRICTION_SETTINGS: {
                    c.mRestrictionSettings.loadFromXml(true);
                } break;
                case MSG_PERSIST_RESTRICTION_SETTINGS: {
                    c.mRestrictionSettings.persistToXml(msg.arg1);
                } break;
            }
        }
    }

    static class Injector {
        private final Context mContext;
        private ActivityManagerInternal mActivityManagerInternal;
        private AppRestrictionController mAppRestrictionController;
        private AppOpsManager mAppOpsManager;
        private AppStandbyInternal mAppStandbyInternal;
        private AppStateTracker mAppStateTracker;
        private AppHibernationManagerInternal mAppHibernationInternal;
        private IActivityManager mIActivityManager;
        private UserManagerInternal mUserManagerInternal;
        private PackageManagerInternal mPackageManagerInternal;
        private NotificationManager mNotificationManager;
        private RoleManager mRoleManager;
        private AppBatteryTracker mAppBatteryTracker;
        private AppBatteryExemptionTracker mAppBatteryExemptionTracker;
        private AppFGSTracker mAppFGSTracker;
        private AppMediaSessionTracker mAppMediaSessionTracker;
        private AppPermissionTracker mAppPermissionTracker;
        private TelephonyManager mTelephonyManager;

        Injector(Context context) {
            mContext = context;
        }

        Context getContext() {
            return mContext;
        }

        void initAppStateTrackers(AppRestrictionController controller) {
            mAppRestrictionController = controller;
            mAppBatteryTracker = new AppBatteryTracker(mContext, controller);
            mAppBatteryExemptionTracker = new AppBatteryExemptionTracker(mContext, controller);
            mAppFGSTracker = new AppFGSTracker(mContext, controller);
            mAppMediaSessionTracker = new AppMediaSessionTracker(mContext, controller);
            mAppPermissionTracker = new AppPermissionTracker(mContext, controller);
            controller.mAppStateTrackers.add(mAppBatteryTracker);
            controller.mAppStateTrackers.add(mAppBatteryExemptionTracker);
            controller.mAppStateTrackers.add(mAppFGSTracker);
            controller.mAppStateTrackers.add(mAppMediaSessionTracker);
            controller.mAppStateTrackers.add(mAppPermissionTracker);
            controller.mAppStateTrackers.add(new AppBroadcastEventsTracker(mContext, controller));
            controller.mAppStateTrackers.add(new AppBindServiceEventsTracker(mContext, controller));
        }

        ActivityManagerInternal getActivityManagerInternal() {
            if (mActivityManagerInternal == null) {
                mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
            }
            return mActivityManagerInternal;
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

        RoleManager getRoleManager() {
            if (mRoleManager == null) {
                mRoleManager = getContext().getSystemService(RoleManager.class);
            }
            return mRoleManager;
        }

        TelephonyManager getTelephonyManager() {
            if (mTelephonyManager == null) {
                mTelephonyManager = getContext().getSystemService(TelephonyManager.class);
            }
            return mTelephonyManager;
        }

        AppFGSTracker getAppFGSTracker() {
            return mAppFGSTracker;
        }

        AppMediaSessionTracker getAppMediaSessionTracker() {
            return mAppMediaSessionTracker;
        }

        ActivityManagerService getActivityManagerService() {
            return mAppRestrictionController.mActivityManagerService;
        }

        UidBatteryUsageProvider getUidBatteryUsageProvider() {
            return mAppBatteryTracker;
        }

        AppBatteryExemptionTracker getAppBatteryExemptionTracker() {
            return mAppBatteryExemptionTracker;
        }

        AppPermissionTracker getAppPermissionTracker() {
            return mAppPermissionTracker;
        }

        String getPackageName(int pid) {
            final ActivityManagerService am = getActivityManagerService();
            final ProcessRecord app;
            synchronized (am.mPidsSelfLocked) {
                app = am.mPidsSelfLocked.get(pid);
                if (app != null) {
                    final ApplicationInfo ai = app.info;
                    if (ai != null) {
                        return ai.packageName;
                    }
                }
            }
            return null;
        }

        void scheduleInitTrackers(Handler handler, Runnable initializers) {
            handler.post(initializers);
        }

        File getDataSystemDeDirectory(@UserIdInt int userId) {
            return Environment.getDataSystemDeDirectory(userId);
        }

        @CurrentTimeMillisLong long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        boolean isTest() {
            return false;
        }
    }

    private void registerForSystemBroadcasts() {
        final IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        packageFilter.addDataScheme("package");
        mContext.registerReceiverForAllUsers(mBroadcastReceiver, packageFilter, null, mBgHandler);
        final IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_ADDED);
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        userFilter.addAction(Intent.ACTION_UID_REMOVED);
        mContext.registerReceiverForAllUsers(mBroadcastReceiver, userFilter, null, mBgHandler);
        final IntentFilter bootFilter = new IntentFilter();
        bootFilter.addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED);
        mContext.registerReceiverAsUser(mBootReceiver, UserHandle.SYSTEM,
                bootFilter, null, mBgHandler);
    }

    private void unregisterForSystemBroadcasts() {
        mContext.unregisterReceiver(mBroadcastReceiver);
        mContext.unregisterReceiver(mBootReceiver);
    }

    void forEachTracker(Consumer<BaseAppStateTracker> sink) {
        for (int i = 0, size = mAppStateTrackers.size(); i < size; i++) {
            sink.accept(mAppStateTrackers.get(i));
        }
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

    private void onLockedBootCompleted() {
        for (int i = 0, size = mAppStateTrackers.size(); i < size; i++) {
            mAppStateTrackers.get(i).onLockedBootCompleted();
        }
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
