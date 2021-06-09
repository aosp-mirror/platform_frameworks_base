/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static android.Manifest.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND;
import static android.Manifest.permission.REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND;
import static android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND;
import static android.Manifest.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND;
import static android.app.ActivityManager.PROCESS_STATE_HEAVY_WEIGHT;
import static android.app.ActivityManager.PROCESS_STATE_PERSISTENT_UI;
import static android.app.ActivityManager.PROCESS_STATE_RECEIVER;
import static android.app.ActivityManager.PROCESS_STATE_TOP;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST;
import static android.os.PowerExemptionManager.REASON_ACTIVITY_STARTER;
import static android.os.PowerExemptionManager.REASON_ACTIVITY_VISIBILITY_GRACE_PERIOD;
import static android.os.PowerExemptionManager.REASON_ALLOWLISTED_PACKAGE;
import static android.os.PowerExemptionManager.REASON_BACKGROUND_ACTIVITY_PERMISSION;
import static android.os.PowerExemptionManager.REASON_BACKGROUND_FGS_PERMISSION;
import static android.os.PowerExemptionManager.REASON_COMPANION_DEVICE_MANAGER;
import static android.os.PowerExemptionManager.REASON_DENIED;
import static android.os.PowerExemptionManager.REASON_DEVICE_DEMO_MODE;
import static android.os.PowerExemptionManager.REASON_DEVICE_OWNER;
import static android.os.PowerExemptionManager.REASON_FGS_BINDING;
import static android.os.PowerExemptionManager.REASON_INSTR_BACKGROUND_ACTIVITY_PERMISSION;
import static android.os.PowerExemptionManager.REASON_INSTR_BACKGROUND_FGS_PERMISSION;
import static android.os.PowerExemptionManager.REASON_OPT_OUT_REQUESTED;
import static android.os.PowerExemptionManager.REASON_OP_ACTIVATE_PLATFORM_VPN;
import static android.os.PowerExemptionManager.REASON_OP_ACTIVATE_VPN;
import static android.os.PowerExemptionManager.REASON_PROC_STATE_PERSISTENT;
import static android.os.PowerExemptionManager.REASON_PROC_STATE_PERSISTENT_UI;
import static android.os.PowerExemptionManager.REASON_PROC_STATE_TOP;
import static android.os.PowerExemptionManager.REASON_PROFILE_OWNER;
import static android.os.PowerExemptionManager.REASON_SERVICE_LAUNCH;
import static android.os.PowerExemptionManager.REASON_START_ACTIVITY_FLAG;
import static android.os.PowerExemptionManager.REASON_SYSTEM_ALERT_WINDOW_PERMISSION;
import static android.os.PowerExemptionManager.REASON_SYSTEM_ALLOW_LISTED;
import static android.os.PowerExemptionManager.REASON_SYSTEM_UID;
import static android.os.PowerExemptionManager.REASON_TEMP_ALLOWED_WHILE_IN_USE;
import static android.os.PowerExemptionManager.REASON_UID_VISIBLE;
import static android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED;
import static android.os.PowerExemptionManager.getReasonCodeFromProcState;
import static android.os.PowerExemptionManager.reasonCodeToString;
import static android.os.Process.INVALID_UID;
import static android.os.Process.NFC_UID;
import static android.os.Process.ROOT_UID;
import static android.os.Process.SHELL_UID;
import static android.os.Process.SYSTEM_UID;
import static android.os.Process.ZYGOTE_POLICY_FLAG_EMPTY;

import static com.android.internal.messages.nano.SystemMessageProto.SystemMessage.NOTE_FOREGROUND_SERVICE_BG_LAUNCH;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BACKGROUND_CHECK;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_FOREGROUND_SERVICE;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_MU;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PERMISSIONS_REVIEW;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_SERVICE;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_SERVICE_EXECUTING;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_MU;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_SERVICE;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_SERVICE_EXECUTING;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UptimeMillisLong;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityManagerInternal.ServiceNotificationPolicy;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.ForegroundServiceDidNotStartInTimeException;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.IApplicationThread;
import android.app.IServiceConnection;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.ServiceStartArgs;
import android.app.admin.DevicePolicyEventLogger;
import android.app.compat.CompatChanges;
import android.app.usage.UsageEvents;
import android.appwidget.AppWidgetManagerInternal;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.ComponentName;
import android.content.ComponentName.WithComponentName;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerExemptionManager.ReasonCode;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.TransactionTooLargeException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.stats.devicepolicy.DevicePolicyEnums;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Pair;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.webkit.WebViewZygote;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.procstats.ServiceState;
import com.android.internal.messages.nano.SystemMessageProto;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.TransferPipe;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.AppStateTracker;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.am.ActivityManagerService.ItemMatcher;
import com.android.server.uri.NeededUriGrants;
import com.android.server.wm.ActivityServiceConnectionsHolder;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public final class ActiveServices {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActiveServices" : TAG_AM;
    private static final String TAG_MU = TAG + POSTFIX_MU;
    private static final String TAG_SERVICE = TAG + POSTFIX_SERVICE;
    private static final String TAG_SERVICE_EXECUTING = TAG + POSTFIX_SERVICE_EXECUTING;

    private static final boolean DEBUG_DELAYED_SERVICE = DEBUG_SERVICE;
    private static final boolean DEBUG_DELAYED_STARTS = DEBUG_DELAYED_SERVICE;

    private static final boolean LOG_SERVICE_START_STOP = false;

    private static final boolean SHOW_DUNGEON_NOTIFICATION = false;

    // How long we wait for a service to finish executing.
    static final int SERVICE_TIMEOUT = 20 * 1000 * Build.HW_TIMEOUT_MULTIPLIER;

    // How long we wait for a service to finish executing.
    static final int SERVICE_BACKGROUND_TIMEOUT = SERVICE_TIMEOUT * 10;

    // How long the startForegroundService() grace period is to get around to
    // calling startForeground() before we ANR + stop it.
    static final int SERVICE_START_FOREGROUND_TIMEOUT = 10 * 1000 * Build.HW_TIMEOUT_MULTIPLIER;

    // Foreground service types that always get immediate notification display,
    // expressed in the same bitmask format that ServiceRecord.foregroundServiceType
    // uses.
    static final int FGS_IMMEDIATE_DISPLAY_MASK =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;

    final ActivityManagerService mAm;

    // Maximum number of services that we allow to start in the background
    // at the same time.
    final int mMaxStartingBackground;

    /**
     * Master service bookkeeping, keyed by user number.
     */
    final SparseArray<ServiceMap> mServiceMap = new SparseArray<>();

    /**
     * All currently bound service connections.  Keys are the IBinder of
     * the client's IServiceConnection.
     */
    final ArrayMap<IBinder, ArrayList<ConnectionRecord>> mServiceConnections = new ArrayMap<>();

    /**
     * List of services that we have been asked to start,
     * but haven't yet been able to.  It is used to hold start requests
     * while waiting for their corresponding application thread to get
     * going.
     */
    final ArrayList<ServiceRecord> mPendingServices = new ArrayList<>();

    /**
     * List of services that are scheduled to restart following a crash.
     */
    final ArrayList<ServiceRecord> mRestartingServices = new ArrayList<>();

    /**
     * List of services that are in the process of being destroyed.
     */
    final ArrayList<ServiceRecord> mDestroyingServices = new ArrayList<>();

    /**
     * List of services for which display of the FGS notification has been deferred.
     */
    final ArrayList<ServiceRecord> mPendingFgsNotifications = new ArrayList<>();

    /**
     * Whether there is a rate limit that suppresses immediate re-deferral of new FGS
     * notifications from each app.  On by default, disabled only by shell command for
     * test-suite purposes.  To disable the behavior more generally, use the usual
     * DeviceConfig mechanism to set the rate limit interval to zero.
     */
    private boolean mFgsDeferralRateLimited = true;

    /**
     * Uptime at which a given uid becomes eliglible again for FGS notification deferral
     */
    final SparseLongArray mFgsDeferralEligible = new SparseLongArray();

    /**
     * Map of services that are asked to be brought up (start/binding) but not ready to.
     */
    private ArrayMap<ServiceRecord, ArrayList<Runnable>> mPendingBringups = new ArrayMap<>();

    /** Temporary list for holding the results of calls to {@link #collectPackageServicesLocked} */
    private ArrayList<ServiceRecord> mTmpCollectionResults = null;

    /** Mapping from uid to their foreground service AppOpCallbacks (if they have one). */
    @GuardedBy("mAm")
    private final SparseArray<AppOpCallback> mFgsAppOpCallbacks = new SparseArray<>();

    /**
     * The list of packages with the service restart backoff disabled.
     */
    @GuardedBy("mAm")
    private final ArraySet<String> mRestartBackoffDisabledPackages = new ArraySet<>();

    /**
     * For keeping ActiveForegroundApps retaining state while the screen is off.
     */
    boolean mScreenOn = true;

    /** Amount of time to allow a last ANR message to exist before freeing the memory. */
    static final int LAST_ANR_LIFETIME_DURATION_MSECS = 2 * 60 * 60 * 1000; // Two hours

    String mLastAnrDump;

    AppWidgetManagerInternal mAppWidgetManagerInternal;

    // allowlisted packageName.
    ArraySet<String> mAllowListWhileInUsePermissionInFgs = new ArraySet<>();

    // TODO: remove this after feature development is done
    private static final SimpleDateFormat DATE_FORMATTER =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * The BG-launch FGS restriction feature is going to be allowed only for apps targetSdkVersion
     * is higher than R.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = android.os.Build.VERSION_CODES.S)
    static final long FGS_BG_START_RESTRICTION_CHANGE_ID = 170668199L;

    /**
     * If a service can not become foreground service due to BG-FGS-launch restriction or other
     * reasons, throws an IllegalStateException.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = android.os.Build.VERSION_CODES.S)
    static final long FGS_START_EXCEPTION_CHANGE_ID = 174041399L;

    final Runnable mLastAnrDumpClearer = new Runnable() {
        @Override public void run() {
            synchronized (mAm) {
                mLastAnrDump = null;
            }
        }
    };

    /**
     * Watch for apps being put into forced app standby, so we can step their fg
     * services down.
     */
    class ForcedStandbyListener implements AppStateTracker.ForcedAppStandbyListener {
        @Override
        public void updateForceAppStandbyForUidPackage(int uid, String packageName,
                boolean standby) {
            synchronized (mAm) {
                if (standby) {
                    stopAllForegroundServicesLocked(uid, packageName);
                }
                mAm.mProcessList.updateForceAppStandbyForUidPackageLocked(
                        uid, packageName, standby);
            }
        }

        @Override
        public void updateForcedAppStandbyForAllApps() {
            synchronized (mAm) {
                mAm.mProcessList.updateForcedAppStandbyForAllAppsLocked();
            }
        }
    }

    void stopAllForegroundServicesLocked(final int uid, final String packageName) {
        final ServiceMap smap = getServiceMapLocked(UserHandle.getUserId(uid));
        final int N = smap.mServicesByInstanceName.size();
        final ArrayList<ServiceRecord> toStop = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            final ServiceRecord r = smap.mServicesByInstanceName.valueAt(i);
            if (uid == r.serviceInfo.applicationInfo.uid
                    || packageName.equals(r.serviceInfo.packageName)) {
                if (r.isForeground) {
                    toStop.add(r);
                }
            }
        }

        // Now stop them all
        final int numToStop = toStop.size();
        if (numToStop > 0 && DEBUG_FOREGROUND_SERVICE) {
            Slog.i(TAG, "Package " + packageName + "/" + uid
                    + " in FAS with foreground services");
        }
        for (int i = 0; i < numToStop; i++) {
            final ServiceRecord r = toStop.get(i);
            if (DEBUG_FOREGROUND_SERVICE) {
                Slog.i(TAG, "  Stopping fg for service " + r);
            }
            setServiceForegroundInnerLocked(r, 0, null, 0, 0);
        }
    }

    /**
     * Information about an app that is currently running one or more foreground services.
     * (This maps directly to the running apps we show in the notification.)
     */
    static final class ActiveForegroundApp {
        String mPackageName;
        int mUid;
        CharSequence mLabel;
        boolean mShownWhileScreenOn;
        boolean mAppOnTop;
        boolean mShownWhileTop;
        long mStartTime;
        long mStartVisibleTime;
        long mEndTime;
        int mNumActive;

        // Temp output of foregroundAppShownEnoughLocked
        long mHideTime;
    }

    /**
     * Information about services for a single user.
     */
    final class ServiceMap extends Handler {
        final int mUserId;
        final ArrayMap<ComponentName, ServiceRecord> mServicesByInstanceName = new ArrayMap<>();
        final ArrayMap<Intent.FilterComparison, ServiceRecord> mServicesByIntent = new ArrayMap<>();

        final ArrayList<ServiceRecord> mDelayedStartList = new ArrayList<>();
        /* XXX eventually I'd like to have this based on processes instead of services.
         * That is, if we try to start two services in a row both running in the same
         * process, this should be one entry in mStartingBackground for that one process
         * that remains until all services in it are done.
        final ArrayMap<ProcessRecord, DelayingProcess> mStartingBackgroundMap
                = new ArrayMap<ProcessRecord, DelayingProcess>();
        final ArrayList<DelayingProcess> mStartingProcessList
                = new ArrayList<DelayingProcess>();
        */

        final ArrayList<ServiceRecord> mStartingBackground = new ArrayList<>();

        final ArrayMap<String, ActiveForegroundApp> mActiveForegroundApps = new ArrayMap<>();
        boolean mActiveForegroundAppsChanged;

        static final int MSG_BG_START_TIMEOUT = 1;
        static final int MSG_UPDATE_FOREGROUND_APPS = 2;
        static final int MSG_ENSURE_NOT_START_BG = 3;

        ServiceMap(Looper looper, int userId) {
            super(looper);
            mUserId = userId;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_BG_START_TIMEOUT: {
                    synchronized (mAm) {
                        rescheduleDelayedStartsLocked();
                    }
                } break;
                case MSG_UPDATE_FOREGROUND_APPS: {
                    updateForegroundApps(this);
                } break;
                case MSG_ENSURE_NOT_START_BG: {
                    synchronized (mAm) {
                        rescheduleDelayedStartsLocked();
                    }
                } break;
            }
        }

        void ensureNotStartingBackgroundLocked(ServiceRecord r) {
            if (mStartingBackground.remove(r)) {
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE,
                        "No longer background starting: " + r);
                removeMessages(MSG_ENSURE_NOT_START_BG);
                Message msg = obtainMessage(MSG_ENSURE_NOT_START_BG);
                sendMessage(msg);
            }
            if (mDelayedStartList.remove(r)) {
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "No longer delaying start: " + r);
            }
        }

        void rescheduleDelayedStartsLocked() {
            removeMessages(MSG_BG_START_TIMEOUT);
            final long now = SystemClock.uptimeMillis();
            for (int i=0, N=mStartingBackground.size(); i<N; i++) {
                ServiceRecord r = mStartingBackground.get(i);
                if (r.startingBgTimeout <= now) {
                    Slog.i(TAG, "Waited long enough for: " + r);
                    mStartingBackground.remove(i);
                    N--;
                    i--;
                }
            }
            while (mDelayedStartList.size() > 0
                    && mStartingBackground.size() < mMaxStartingBackground) {
                ServiceRecord r = mDelayedStartList.remove(0);
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE,
                        "REM FR DELAY LIST (exec next): " + r);
                if (DEBUG_DELAYED_SERVICE) {
                    if (mDelayedStartList.size() > 0) {
                        Slog.v(TAG_SERVICE, "Remaining delayed list:");
                        for (int i=0; i<mDelayedStartList.size(); i++) {
                            Slog.v(TAG_SERVICE, "  #" + i + ": " + mDelayedStartList.get(i));
                        }
                    }
                }
                r.delayed = false;
                if (r.pendingStarts.size() <= 0) {
                    Slog.wtf(TAG, "**** NO PENDING STARTS! " + r + " startReq=" + r.startRequested
                            + " delayedStop=" + r.delayedStop);
                } else {
                    try {
                        startServiceInnerLocked(this, r.pendingStarts.get(0).intent, r, false,
                                true);
                    } catch (TransactionTooLargeException e) {
                        // Ignore, nobody upstack cares.
                    }
                }
            }
            if (mStartingBackground.size() > 0) {
                ServiceRecord next = mStartingBackground.get(0);
                long when = next.startingBgTimeout > now ? next.startingBgTimeout : now;
                if (DEBUG_DELAYED_SERVICE) Slog.v(TAG_SERVICE, "Top bg start is " + next
                        + ", can delay others up to " + when);
                Message msg = obtainMessage(MSG_BG_START_TIMEOUT);
                sendMessageAtTime(msg, when);
            }
            if (mStartingBackground.size() < mMaxStartingBackground) {
                mAm.backgroundServicesFinishedLocked(mUserId);
            }
        }
    }

    public ActiveServices(ActivityManagerService service) {
        mAm = service;
        int maxBg = 0;
        try {
            maxBg = Integer.parseInt(SystemProperties.get("ro.config.max_starting_bg", "0"));
        } catch(RuntimeException e) {
        }
        mMaxStartingBackground = maxBg > 0
                ? maxBg : ActivityManager.isLowRamDeviceStatic() ? 1 : 8;

        final IBinder b = ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE);
    }

    void systemServicesReady() {
        AppStateTracker ast = LocalServices.getService(AppStateTracker.class);
        ast.addForcedAppStandbyListener(new ForcedStandbyListener());
        mAppWidgetManagerInternal = LocalServices.getService(AppWidgetManagerInternal.class);
        setAllowListWhileInUsePermissionInFgs();
    }

    private void setAllowListWhileInUsePermissionInFgs() {
        final String attentionServicePackageName =
                mAm.mContext.getPackageManager().getAttentionServicePackageName();
        if (!TextUtils.isEmpty(attentionServicePackageName)) {
            mAllowListWhileInUsePermissionInFgs.add(attentionServicePackageName);
        }
        final String systemCaptionsServicePackageName =
                mAm.mContext.getPackageManager().getSystemCaptionsServicePackageName();
        if (!TextUtils.isEmpty(systemCaptionsServicePackageName)) {
            mAllowListWhileInUsePermissionInFgs.add(systemCaptionsServicePackageName);
        }
    }

    ServiceRecord getServiceByNameLocked(ComponentName name, int callingUser) {
        // TODO: Deal with global services
        if (DEBUG_MU)
            Slog.v(TAG_MU, "getServiceByNameLocked(" + name + "), callingUser = " + callingUser);
        return getServiceMapLocked(callingUser).mServicesByInstanceName.get(name);
    }

    boolean hasBackgroundServicesLocked(int callingUser) {
        ServiceMap smap = mServiceMap.get(callingUser);
        return smap != null ? smap.mStartingBackground.size() >= mMaxStartingBackground : false;
    }

    boolean hasForegroundServiceNotificationLocked(String pkg, int userId, String channelId) {
        final ServiceMap smap = mServiceMap.get(userId);
        if (smap != null) {
            for (int i = 0; i < smap.mServicesByInstanceName.size(); i++) {
                final ServiceRecord sr = smap.mServicesByInstanceName.valueAt(i);
                if (sr.appInfo.packageName.equals(pkg) && sr.isForeground) {
                    if (Objects.equals(sr.foregroundNoti.getChannelId(), channelId)) {
                        if (DEBUG_FOREGROUND_SERVICE) {
                            Slog.d(TAG_SERVICE, "Channel u" + userId + "/pkg=" + pkg
                                    + "/channelId=" + channelId
                                    + " has fg service notification");
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }

    void stopForegroundServicesForChannelLocked(String pkg, int userId, String channelId) {
        final ServiceMap smap = mServiceMap.get(userId);
        if (smap != null) {
            for (int i = 0; i < smap.mServicesByInstanceName.size(); i++) {
                final ServiceRecord sr = smap.mServicesByInstanceName.valueAt(i);
                if (sr.appInfo.packageName.equals(pkg) && sr.isForeground) {
                    if (Objects.equals(sr.foregroundNoti.getChannelId(), channelId)) {
                        if (DEBUG_FOREGROUND_SERVICE) {
                            Slog.d(TAG_SERVICE, "Stopping FGS u" + userId + "/pkg=" + pkg
                                    + "/channelId=" + channelId
                                    + " for conversation channel clear");
                        }
                        stopServiceLocked(sr, false);
                    }
                }
            }
        }
    }

    private ServiceMap getServiceMapLocked(int callingUser) {
        ServiceMap smap = mServiceMap.get(callingUser);
        if (smap == null) {
            smap = new ServiceMap(mAm.mHandler.getLooper(), callingUser);
            mServiceMap.put(callingUser, smap);
        }
        return smap;
    }

    ArrayMap<ComponentName, ServiceRecord> getServicesLocked(int callingUser) {
        return getServiceMapLocked(callingUser).mServicesByInstanceName;
    }

    private boolean appRestrictedAnyInBackground(final int uid, final String packageName) {
        final int mode = mAm.getAppOpsManager().checkOpNoThrow(
                AppOpsManager.OP_RUN_ANY_IN_BACKGROUND, uid, packageName);
        return (mode != AppOpsManager.MODE_ALLOWED);
    }

    ComponentName startServiceLocked(IApplicationThread caller, Intent service, String resolvedType,
            int callingPid, int callingUid, boolean fgRequired, String callingPackage,
            @Nullable String callingFeatureId, final int userId)
            throws TransactionTooLargeException {
        return startServiceLocked(caller, service, resolvedType, callingPid, callingUid, fgRequired,
                callingPackage, callingFeatureId, userId, false, null);
    }

    ComponentName startServiceLocked(IApplicationThread caller, Intent service, String resolvedType,
            int callingPid, int callingUid, boolean fgRequired,
            String callingPackage, @Nullable String callingFeatureId, final int userId,
            boolean allowBackgroundActivityStarts, @Nullable IBinder backgroundActivityStartsToken)
            throws TransactionTooLargeException {
        if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "startService: " + service
                + " type=" + resolvedType + " args=" + service.getExtras());

        final boolean callerFg;
        if (caller != null) {
            final ProcessRecord callerApp = mAm.getRecordForAppLOSP(caller);
            if (callerApp == null) {
                throw new SecurityException(
                        "Unable to find app for caller " + caller
                        + " (pid=" + callingPid
                        + ") when starting service " + service);
            }
            callerFg = callerApp.mState.getSetSchedGroup() != ProcessList.SCHED_GROUP_BACKGROUND;
        } else {
            callerFg = true;
        }

        ServiceLookupResult res =
            retrieveServiceLocked(service, null, resolvedType, callingPackage,
                    callingPid, callingUid, userId, true, callerFg, false, false);
        if (res == null) {
            return null;
        }
        if (res.record == null) {
            return new ComponentName("!", res.permission != null
                    ? res.permission : "private to package");
        }

        ServiceRecord r = res.record;
        setFgsRestrictionLocked(callingPackage, callingPid, callingUid, service, r, userId,
                allowBackgroundActivityStarts);

        if (!mAm.mUserController.exists(r.userId)) {
            Slog.w(TAG, "Trying to start service with non-existent user! " + r.userId);
            return null;
        }

        // If we're starting indirectly (e.g. from PendingIntent), figure out whether
        // we're launching into an app in a background state.  This keys off of the same
        // idleness state tracking as e.g. O+ background service start policy.
        final boolean bgLaunch = !mAm.isUidActiveLOSP(r.appInfo.uid);

        // If the app has strict background restrictions, we treat any bg service
        // start analogously to the legacy-app forced-restrictions case, regardless
        // of its target SDK version.
        boolean forcedStandby = false;
        if (bgLaunch && appRestrictedAnyInBackground(r.appInfo.uid, r.packageName)) {
            if (DEBUG_FOREGROUND_SERVICE) {
                Slog.d(TAG, "Forcing bg-only service start only for " + r.shortInstanceName
                        + " : bgLaunch=" + bgLaunch + " callerFg=" + callerFg);
            }
            forcedStandby = true;
        }

        if (fgRequired) {
            logFgsBackgroundStart(r);
            if (r.mAllowStartForeground == REASON_DENIED && isBgFgsRestrictionEnabled(r)) {
                String msg = "startForegroundService() not allowed due to "
                        + "mAllowStartForeground false: service "
                        + r.shortInstanceName;
                Slog.w(TAG, msg);
                showFgsBgRestrictedNotificationLocked(r);
                logFGSStateChangeLocked(r,
                        FrameworkStatsLog.FOREGROUND_SERVICE_STATE_CHANGED__STATE__DENIED,
                        0);
                if (CompatChanges.isChangeEnabled(FGS_START_EXCEPTION_CHANGE_ID, callingUid)) {
                    throw new ForegroundServiceStartNotAllowedException(msg);
                }
                return null;
            }
        }

        // If this is a direct-to-foreground start, make sure it is allowed as per the app op.
        boolean forceSilentAbort = false;
        if (fgRequired) {
            final int mode = mAm.getAppOpsManager().checkOpNoThrow(
                    AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName);
            switch (mode) {
                case AppOpsManager.MODE_ALLOWED:
                case AppOpsManager.MODE_DEFAULT:
                    // All okay.
                    break;
                case AppOpsManager.MODE_IGNORED:
                    // Not allowed, fall back to normal start service, failing siliently
                    // if background check restricts that.
                    Slog.w(TAG, "startForegroundService not allowed due to app op: service "
                            + service + " to " + r.shortInstanceName
                            + " from pid=" + callingPid + " uid=" + callingUid
                            + " pkg=" + callingPackage);
                    fgRequired = false;
                    forceSilentAbort = true;
                    break;
                default:
                    return new ComponentName("!!", "foreground not allowed as per app op");
            }
        }

        // If this isn't a direct-to-foreground start, check our ability to kick off an
        // arbitrary service
        if (forcedStandby || (!r.startRequested && !fgRequired)) {
            // Before going further -- if this app is not allowed to start services in the
            // background, then at this point we aren't going to let it period.
            final int allowed = mAm.getAppStartModeLOSP(r.appInfo.uid, r.packageName,
                    r.appInfo.targetSdkVersion, callingPid, false, false, forcedStandby);
            if (allowed != ActivityManager.APP_START_MODE_NORMAL) {
                Slog.w(TAG, "Background start not allowed: service "
                        + service + " to " + r.shortInstanceName
                        + " from pid=" + callingPid + " uid=" + callingUid
                        + " pkg=" + callingPackage + " startFg?=" + fgRequired);
                if (allowed == ActivityManager.APP_START_MODE_DELAYED || forceSilentAbort) {
                    // In this case we are silently disabling the app, to disrupt as
                    // little as possible existing apps.
                    return null;
                }
                if (forcedStandby) {
                    // This is an O+ app, but we might be here because the user has placed
                    // it under strict background restrictions.  Don't punish the app if it's
                    // trying to do the right thing but we're denying it for that reason.
                    if (fgRequired) {
                        if (DEBUG_BACKGROUND_CHECK) {
                            Slog.v(TAG, "Silently dropping foreground service launch due to FAS");
                        }
                        return null;
                    }
                }
                // This app knows it is in the new model where this operation is not
                // allowed, so tell it what has happened.
                UidRecord uidRec = mAm.mProcessList.getUidRecordLOSP(r.appInfo.uid);
                return new ComponentName("?", "app is in background uid " + uidRec);
            }
        }

        // At this point we've applied allowed-to-start policy based on whether this was
        // an ordinary startService() or a startForegroundService().  Now, only require that
        // the app follow through on the startForegroundService() -> startForeground()
        // contract if it actually targets O+.
        if (r.appInfo.targetSdkVersion < Build.VERSION_CODES.O && fgRequired) {
            if (DEBUG_BACKGROUND_CHECK || DEBUG_FOREGROUND_SERVICE) {
                Slog.i(TAG, "startForegroundService() but host targets "
                        + r.appInfo.targetSdkVersion + " - not requiring startForeground()");
            }
            fgRequired = false;
        }

        // The package could be frozen (meaning it's doing surgery), defer the actual
        // start until the package is unfrozen.
        if (deferServiceBringupIfFrozenLocked(r, service, callingPackage, callingFeatureId,
                callingUid, callingPid, fgRequired, callerFg, userId, allowBackgroundActivityStarts,
                backgroundActivityStartsToken, false, null)) {
            return null;
        }

        // If permissions need a review before any of the app components can run,
        // we do not start the service and launch a review activity if the calling app
        // is in the foreground passing it a pending intent to start the service when
        // review is completed.

        // XXX This is not dealing with fgRequired!
        if (!requestStartTargetPermissionsReviewIfNeededLocked(r, callingPackage, callingFeatureId,
                callingUid, service, callerFg, userId, false, null)) {
            return null;
        }

        return startServiceInnerLocked(r, service, callingUid, callingPid, fgRequired, callerFg,
                allowBackgroundActivityStarts, backgroundActivityStartsToken);
    }

    private ComponentName startServiceInnerLocked(ServiceRecord r, Intent service,
            int callingUid, int callingPid, boolean fgRequired, boolean callerFg,
            boolean allowBackgroundActivityStarts, @Nullable IBinder backgroundActivityStartsToken)
            throws TransactionTooLargeException {
        NeededUriGrants neededGrants = mAm.mUgmInternal.checkGrantUriPermissionFromIntent(
                service, callingUid, r.packageName, r.userId);
        if (unscheduleServiceRestartLocked(r, callingUid, false)) {
            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "START SERVICE WHILE RESTART PENDING: " + r);
        }
        r.lastActivity = SystemClock.uptimeMillis();
        r.startRequested = true;
        r.delayedStop = false;
        r.fgRequired = fgRequired;
        r.pendingStarts.add(new ServiceRecord.StartItem(r, false, r.makeNextStartId(),
                service, neededGrants, callingUid));

        if (fgRequired) {
            // We are now effectively running a foreground service.
            ServiceState stracker = r.getTracker();
            if (stracker != null) {
                stracker.setForeground(true, mAm.mProcessStats.getMemFactorLocked(),
                        r.lastActivity);
            }
            mAm.mAppOpsService.startOperation(AppOpsManager.getToken(mAm.mAppOpsService),
                    AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName, null,
                    true, false, null, false, AppOpsManager.ATTRIBUTION_FLAGS_NONE,
                    AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE);
        }

        final ServiceMap smap = getServiceMapLocked(r.userId);
        boolean addToStarting = false;
        if (!callerFg && !fgRequired && r.app == null
                && mAm.mUserController.hasStartedUserState(r.userId)) {
            ProcessRecord proc = mAm.getProcessRecordLocked(r.processName, r.appInfo.uid);
            if (proc == null || proc.mState.getCurProcState() > PROCESS_STATE_RECEIVER) {
                // If this is not coming from a foreground caller, then we may want
                // to delay the start if there are already other background services
                // that are starting.  This is to avoid process start spam when lots
                // of applications are all handling things like connectivity broadcasts.
                // We only do this for cached processes, because otherwise an application
                // can have assumptions about calling startService() for a service to run
                // in its own process, and for that process to not be killed before the
                // service is started.  This is especially the case for receivers, which
                // may start a service in onReceive() to do some additional work and have
                // initialized some global state as part of that.
                if (DEBUG_DELAYED_SERVICE) Slog.v(TAG_SERVICE, "Potential start delay of "
                        + r + " in " + proc);
                if (r.delayed) {
                    // This service is already scheduled for a delayed start; just leave
                    // it still waiting.
                    if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "Continuing to delay: " + r);
                    return r.name;
                }
                if (smap.mStartingBackground.size() >= mMaxStartingBackground) {
                    // Something else is starting, delay!
                    Slog.i(TAG_SERVICE, "Delaying start of: " + r);
                    smap.mDelayedStartList.add(r);
                    r.delayed = true;
                    return r.name;
                }
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "Not delaying: " + r);
                addToStarting = true;
            } else if (proc.mState.getCurProcState() >= ActivityManager.PROCESS_STATE_SERVICE) {
                // We slightly loosen when we will enqueue this new service as a background
                // starting service we are waiting for, to also include processes that are
                // currently running other services or receivers.
                addToStarting = true;
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE,
                        "Not delaying, but counting as bg: " + r);
            } else if (DEBUG_DELAYED_STARTS) {
                StringBuilder sb = new StringBuilder(128);
                sb.append("Not potential delay (state=").append(proc.mState.getCurProcState())
                        .append(' ').append(proc.mState.getAdjType());
                String reason = proc.mState.makeAdjReason();
                if (reason != null) {
                    sb.append(' ');
                    sb.append(reason);
                }
                sb.append("): ");
                sb.append(r.toString());
                Slog.v(TAG_SERVICE, sb.toString());
            }
        } else if (DEBUG_DELAYED_STARTS) {
            if (callerFg || fgRequired) {
                Slog.v(TAG_SERVICE, "Not potential delay (callerFg=" + callerFg + " uid="
                        + callingUid + " pid=" + callingPid + " fgRequired=" + fgRequired + "): " + r);
            } else if (r.app != null) {
                Slog.v(TAG_SERVICE, "Not potential delay (cur app=" + r.app + "): " + r);
            } else {
                Slog.v(TAG_SERVICE,
                        "Not potential delay (user " + r.userId + " not started): " + r);
            }
        }
        if (allowBackgroundActivityStarts) {
            r.allowBgActivityStartsOnServiceStart(backgroundActivityStartsToken);
        }
        ComponentName cmp = startServiceInnerLocked(smap, service, r, callerFg, addToStarting);
        return cmp;
    }

    private boolean requestStartTargetPermissionsReviewIfNeededLocked(ServiceRecord r,
            String callingPackage, @Nullable String callingFeatureId, int callingUid,
            Intent service, boolean callerFg, final int userId,
            final boolean isBinding, final IServiceConnection connection) {
        if (mAm.getPackageManagerInternal().isPermissionsReviewRequired(
                r.packageName, r.userId)) {

            // Show a permission review UI only for starting/binding from a foreground app
            if (!callerFg) {
                Slog.w(TAG, "u" + r.userId
                        + (isBinding ? " Binding" : " Starting") + " a service in package"
                        + r.packageName + " requires a permissions review");
                return false;
            }

            final Intent intent = new Intent(Intent.ACTION_REVIEW_PERMISSIONS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, r.packageName);

            if (isBinding) {
                RemoteCallback callback = new RemoteCallback(
                        new RemoteCallback.OnResultListener() {
                            @Override
                            public void onResult(Bundle result) {
                                synchronized (mAm) {
                                    final long identity = Binder.clearCallingIdentity();
                                    try {
                                        if (!mPendingServices.contains(r)) {
                                            return;
                                        }
                                        // If there is still a pending record, then the service
                                        // binding request is still valid, so hook them up. We
                                        // proceed only if the caller cleared the review requirement
                                        // otherwise we unbind because the user didn't approve.
                                        if (!mAm.getPackageManagerInternal()
                                                .isPermissionsReviewRequired(r.packageName,
                                                    r.userId)) {
                                            try {
                                                bringUpServiceLocked(r,
                                                        service.getFlags(),
                                                        callerFg,
                                                        false /* whileRestarting */,
                                                        false /* permissionsReviewRequired */,
                                                        false /* packageFrozen */,
                                                        true /* enqueueOomAdj */);
                                            } catch (RemoteException e) {
                                                /* ignore - local call */
                                            } finally {
                                                /* Will be a no-op if nothing pending */
                                                mAm.updateOomAdjPendingTargetsLocked(
                                                        OomAdjuster.OOM_ADJ_REASON_START_SERVICE);
                                            }
                                        } else {
                                            unbindServiceLocked(connection);
                                        }
                                    } finally {
                                        Binder.restoreCallingIdentity(identity);
                                    }
                                }
                            }
                        });
                intent.putExtra(Intent.EXTRA_REMOTE_CALLBACK, callback);
            } else { // Starting a service
                IIntentSender target = mAm.mPendingIntentController.getIntentSender(
                        ActivityManager.INTENT_SENDER_SERVICE, callingPackage, callingFeatureId,
                        callingUid, userId, null, null, 0, new Intent[]{service},
                        new String[]{service.resolveType(mAm.mContext.getContentResolver())},
                        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT
                        | PendingIntent.FLAG_IMMUTABLE, null);
                intent.putExtra(Intent.EXTRA_INTENT, new IntentSender(target));
            }

            if (DEBUG_PERMISSIONS_REVIEW) {
                Slog.i(TAG, "u" + r.userId + " Launching permission review for package "
                        + r.packageName);
            }

            mAm.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mAm.mContext.startActivityAsUser(intent, new UserHandle(userId));
                }
            });

            return false;
        }

        return  true;
    }

    /**
     * Defer the service starting/binding until the package is unfrozen, if it's currently frozen.
     *
     * @return {@code true} if the binding is deferred because it's frozen.
     */
    @GuardedBy("mAm")
    private boolean deferServiceBringupIfFrozenLocked(ServiceRecord s, Intent serviceIntent,
            String callingPackage, @Nullable String callingFeatureId,
            int callingUid, int callingPid, boolean fgRequired, boolean callerFg, int userId,
            boolean allowBackgroundActivityStarts, @Nullable IBinder backgroundActivityStartsToken,
            boolean isBinding, IServiceConnection connection) {
        final PackageManagerInternal pm = mAm.getPackageManagerInternal();
        final boolean frozen = pm.isPackageFrozen(s.packageName, callingUid, s.userId);
        if (!frozen) {
            // Not frozen, it's okay to go
            return false;
        }
        ArrayList<Runnable> curPendingBringups = mPendingBringups.get(s);
        if (curPendingBringups == null) {
            curPendingBringups = new ArrayList<>();
            mPendingBringups.put(s, curPendingBringups);
        }
        curPendingBringups.add(new Runnable() {
            @Override
            public void run() {
                synchronized (mAm) {
                    if (!mPendingBringups.containsKey(s)) {
                        return;
                    }
                    // binding request is still valid, so hook them up.
                    // Before doing so, check if it requires a permission review.
                    if (!requestStartTargetPermissionsReviewIfNeededLocked(s,
                                callingPackage, callingFeatureId, callingUid,
                                serviceIntent, callerFg, userId, isBinding, connection)) {
                        // Let's wait for the user approval.
                        return;
                    }
                    if (isBinding) {
                        try {
                            bringUpServiceLocked(s, serviceIntent.getFlags(), callerFg,
                                    false /* whileRestarting */,
                                    false /* permissionsReviewRequired */,
                                    false /* packageFrozen */,
                                    true /* enqueueOomAdj */);
                        } catch (TransactionTooLargeException e) {
                            /* ignore - local call */
                        } finally {
                            /* Will be a no-op if nothing pending */
                            mAm.updateOomAdjPendingTargetsLocked(
                                    OomAdjuster.OOM_ADJ_REASON_START_SERVICE);
                        }
                    } else { // Starting a service
                        try {
                            startServiceInnerLocked(s, serviceIntent, callingUid, callingPid,
                                    fgRequired, callerFg, allowBackgroundActivityStarts,
                                    backgroundActivityStartsToken);
                        } catch (TransactionTooLargeException e) {
                            /* ignore - local call */
                        }
                    }
                }
            }
        });
        return true;
    }

    @GuardedBy("mAm")
    void schedulePendingServiceStartLocked(String packageName, int userId) {
        int totalPendings = mPendingBringups.size();
        for (int i = totalPendings - 1; i >= 0 && totalPendings > 0;) {
            final ServiceRecord r = mPendingBringups.keyAt(i);
            if (r.userId != userId || !TextUtils.equals(r.packageName, packageName)) {
                i--;
                continue;
            }
            final ArrayList<Runnable> curPendingBringups = mPendingBringups.valueAt(i);
            if (curPendingBringups != null) {
                for (int j = curPendingBringups.size() - 1; j >= 0; j--) {
                    curPendingBringups.get(j).run();
                }
                curPendingBringups.clear();
            }
            // Now, how many remaining ones we have after calling into above runnables
            final int curTotalPendings = mPendingBringups.size();
            // Don't call removeAt() here, as it could have been removed already by above runnables
            mPendingBringups.remove(r);
            if (totalPendings != curTotalPendings) {
                // Okay, within the above Runnable.run(), the mPendingBringups is altered.
                // Restart the loop, it won't call into those finished runnables
                // since we've cleared the curPendingBringups above.
                totalPendings = mPendingBringups.size();
                i = totalPendings - 1;
            } else {
                totalPendings = mPendingBringups.size();
                i--;
            }
        }
    }

    ComponentName startServiceInnerLocked(ServiceMap smap, Intent service, ServiceRecord r,
            boolean callerFg, boolean addToStarting) throws TransactionTooLargeException {
        ServiceState stracker = r.getTracker();
        if (stracker != null) {
            stracker.setStarted(true, mAm.mProcessStats.getMemFactorLocked(), r.lastActivity);
        }
        r.callStart = false;

        final int uid = r.appInfo.uid;
        final String packageName = r.name.getPackageName();
        final String serviceName = r.name.getClassName();
        FrameworkStatsLog.write(FrameworkStatsLog.SERVICE_STATE_CHANGED, uid, packageName,
                serviceName, FrameworkStatsLog.SERVICE_STATE_CHANGED__STATE__START);
        mAm.mBatteryStatsService.noteServiceStartRunning(uid, packageName, serviceName);
        String error = bringUpServiceLocked(r, service.getFlags(), callerFg,
                false /* whileRestarting */,
                false /* permissionsReviewRequired */,
                false /* packageFrozen */,
                true /* enqueueOomAdj */);
        /* Will be a no-op if nothing pending */
        mAm.updateOomAdjPendingTargetsLocked(OomAdjuster.OOM_ADJ_REASON_START_SERVICE);
        if (error != null) {
            return new ComponentName("!!", error);
        }

        if (r.startRequested && addToStarting) {
            boolean first = smap.mStartingBackground.size() == 0;
            smap.mStartingBackground.add(r);
            r.startingBgTimeout = SystemClock.uptimeMillis() + mAm.mConstants.BG_START_TIMEOUT;
            if (DEBUG_DELAYED_SERVICE) {
                RuntimeException here = new RuntimeException("here");
                here.fillInStackTrace();
                Slog.v(TAG_SERVICE, "Starting background (first=" + first + "): " + r, here);
            } else if (DEBUG_DELAYED_STARTS) {
                Slog.v(TAG_SERVICE, "Starting background (first=" + first + "): " + r);
            }
            if (first) {
                smap.rescheduleDelayedStartsLocked();
            }
        } else if (callerFg || r.fgRequired) {
            smap.ensureNotStartingBackgroundLocked(r);
        }

        return r.name;
    }

    private void stopServiceLocked(ServiceRecord service, boolean enqueueOomAdj) {
        if (service.delayed) {
            // If service isn't actually running, but is being held in the
            // delayed list, then we need to keep it started but note that it
            // should be stopped once no longer delayed.
            if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "Delaying stop of pending: " + service);
            service.delayedStop = true;
            return;
        }

        final int uid = service.appInfo.uid;
        final String packageName = service.name.getPackageName();
        final String serviceName = service.name.getClassName();
        FrameworkStatsLog.write(FrameworkStatsLog.SERVICE_STATE_CHANGED, uid, packageName,
                serviceName, FrameworkStatsLog.SERVICE_STATE_CHANGED__STATE__STOP);
        mAm.mBatteryStatsService.noteServiceStopRunning(uid, packageName, serviceName);
        service.startRequested = false;
        if (service.tracker != null) {
            service.tracker.setStarted(false, mAm.mProcessStats.getMemFactorLocked(),
                    SystemClock.uptimeMillis());
        }
        service.callStart = false;

        bringDownServiceIfNeededLocked(service, false, false, enqueueOomAdj);
    }

    int stopServiceLocked(IApplicationThread caller, Intent service,
            String resolvedType, int userId) {
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "stopService: " + service
                + " type=" + resolvedType);

        final ProcessRecord callerApp = mAm.getRecordForAppLOSP(caller);
        if (caller != null && callerApp == null) {
            throw new SecurityException(
                    "Unable to find app for caller " + caller
                    + " (pid=" + Binder.getCallingPid()
                    + ") when stopping service " + service);
        }

        // If this service is active, make sure it is stopped.
        ServiceLookupResult r = retrieveServiceLocked(service, null, resolvedType, null,
                Binder.getCallingPid(), Binder.getCallingUid(), userId, false, false, false, false);
        if (r != null) {
            if (r.record != null) {
                final long origId = Binder.clearCallingIdentity();
                try {
                    stopServiceLocked(r.record, false);
                } finally {
                    Binder.restoreCallingIdentity(origId);
                }
                return 1;
            }
            return -1;
        }

        return 0;
    }

    void stopInBackgroundLocked(int uid) {
        // Stop all services associated with this uid due to it going to the background
        // stopped state.
        ServiceMap services = mServiceMap.get(UserHandle.getUserId(uid));
        ArrayList<ServiceRecord> stopping = null;
        if (services != null) {
            for (int i = services.mServicesByInstanceName.size() - 1; i >= 0; i--) {
                ServiceRecord service = services.mServicesByInstanceName.valueAt(i);
                if (service.appInfo.uid == uid && service.startRequested) {
                    if (mAm.getAppStartModeLOSP(service.appInfo.uid, service.packageName,
                            service.appInfo.targetSdkVersion, -1, false, false, false)
                            != ActivityManager.APP_START_MODE_NORMAL) {
                        if (stopping == null) {
                            stopping = new ArrayList<>();
                        }
                        String compName = service.shortInstanceName;
                        EventLogTags.writeAmStopIdleService(service.appInfo.uid, compName);
                        StringBuilder sb = new StringBuilder(64);
                        sb.append("Stopping service due to app idle: ");
                        UserHandle.formatUid(sb, service.appInfo.uid);
                        sb.append(" ");
                        TimeUtils.formatDuration(service.createRealTime
                                - SystemClock.elapsedRealtime(), sb);
                        sb.append(" ");
                        sb.append(compName);
                        Slog.w(TAG, sb.toString());
                        stopping.add(service);

                        // If the app is under bg restrictions, also make sure that
                        // any notification is dismissed
                        if (appRestrictedAnyInBackground(
                                service.appInfo.uid, service.packageName)) {
                            cancelForegroundNotificationLocked(service);
                        }
                    }
                }
            }
            if (stopping != null) {
                final int size = stopping.size();
                for (int i = size - 1; i >= 0; i--) {
                    ServiceRecord service = stopping.get(i);
                    service.delayed = false;
                    services.ensureNotStartingBackgroundLocked(service);
                    stopServiceLocked(service, true);
                }
                if (size > 0) {
                    mAm.updateOomAdjPendingTargetsLocked(OomAdjuster.OOM_ADJ_REASON_UNBIND_SERVICE);
                }
            }
        }
    }

    void killMisbehavingService(ServiceRecord r,
            int appUid, int appPid, String localPackageName) {
        synchronized (mAm) {
            if (!r.destroying) {
                // This service is still alive, stop it.
                stopServiceLocked(r, false);
            } else {
                // Check if there is another instance of it being started in parallel,
                // if so, stop that too to avoid spamming the system.
                final ServiceMap smap = getServiceMapLocked(r.userId);
                final ServiceRecord found = smap.mServicesByInstanceName.remove(r.instanceName);
                if (found != null) {
                    stopServiceLocked(found, false);
                }
            }
            mAm.crashApplication(appUid, appPid, localPackageName, -1,
                    "Bad notification for startForeground", true /*force*/);
        }
    }

    IBinder peekServiceLocked(Intent service, String resolvedType, String callingPackage) {
        ServiceLookupResult r = retrieveServiceLocked(service, null, resolvedType, callingPackage,
                Binder.getCallingPid(), Binder.getCallingUid(),
                UserHandle.getCallingUserId(), false, false, false, false);

        IBinder ret = null;
        if (r != null) {
            // r.record is null if findServiceLocked() failed the caller permission check
            if (r.record == null) {
                throw new SecurityException(
                        "Permission Denial: Accessing service"
                        + " from pid=" + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " requires " + r.permission);
            }
            IntentBindRecord ib = r.record.bindings.get(r.record.intent);
            if (ib != null) {
                ret = ib.binder;
            }
        }

        return ret;
    }

    boolean stopServiceTokenLocked(ComponentName className, IBinder token,
            int startId) {
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "stopServiceToken: " + className
                + " " + token + " startId=" + startId);
        ServiceRecord r = findServiceLocked(className, token, UserHandle.getCallingUserId());
        if (r != null) {
            if (startId >= 0) {
                // Asked to only stop if done with all work.  Note that
                // to avoid leaks, we will take this as dropping all
                // start items up to and including this one.
                ServiceRecord.StartItem si = r.findDeliveredStart(startId, false, false);
                if (si != null) {
                    while (r.deliveredStarts.size() > 0) {
                        ServiceRecord.StartItem cur = r.deliveredStarts.remove(0);
                        cur.removeUriPermissionsLocked();
                        if (cur == si) {
                            break;
                        }
                    }
                }

                if (r.getLastStartId() != startId) {
                    return false;
                }

                if (r.deliveredStarts.size() > 0) {
                    Slog.w(TAG, "stopServiceToken startId " + startId
                            + " is last, but have " + r.deliveredStarts.size()
                            + " remaining args");
                }
            }

            final int uid = r.appInfo.uid;
            final String packageName = r.name.getPackageName();
            final String serviceName = r.name.getClassName();
            FrameworkStatsLog.write(FrameworkStatsLog.SERVICE_STATE_CHANGED, uid, packageName,
                    serviceName, FrameworkStatsLog.SERVICE_STATE_CHANGED__STATE__STOP);
            mAm.mBatteryStatsService.noteServiceStopRunning(uid, packageName, serviceName);
            r.startRequested = false;
            if (r.tracker != null) {
                r.tracker.setStarted(false, mAm.mProcessStats.getMemFactorLocked(),
                        SystemClock.uptimeMillis());
            }
            r.callStart = false;
            final long origId = Binder.clearCallingIdentity();
            bringDownServiceIfNeededLocked(r, false, false, false);
            Binder.restoreCallingIdentity(origId);
            return true;
        }
        return false;
    }

    public void setServiceForegroundLocked(ComponentName className, IBinder token,
            int id, Notification notification, int flags, int foregroundServiceType) {
        final int userId = UserHandle.getCallingUserId();
        final long origId = Binder.clearCallingIdentity();
        try {
            ServiceRecord r = findServiceLocked(className, token, userId);
            if (r != null) {
                setServiceForegroundInnerLocked(r, id, notification, flags, foregroundServiceType);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Return the current foregroundServiceType of the ServiceRecord.
     * @param className ComponentName of the Service class.
     * @param token IBinder token.
     * @return current foreground service type.
     */
    public int getForegroundServiceTypeLocked(ComponentName className, IBinder token) {
        final int userId = UserHandle.getCallingUserId();
        final long origId = Binder.clearCallingIdentity();
        int ret = ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE;
        try {
            ServiceRecord r = findServiceLocked(className, token, userId);
            if (r != null) {
                ret = r.foregroundServiceType;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
        return ret;
    }

    boolean foregroundAppShownEnoughLocked(ActiveForegroundApp aa, long nowElapsed) {
        if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "Shown enough: pkg=" + aa.mPackageName + ", uid="
                + aa.mUid);
        boolean canRemove = false;
        aa.mHideTime = Long.MAX_VALUE;
        if (aa.mShownWhileTop) {
            // If the app was ever at the top of the screen while the foreground
            // service was running, then we can always just immediately remove it.
            canRemove = true;
            if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "YES - shown while on top");
        } else if (mScreenOn || aa.mShownWhileScreenOn) {
            final long minTime = aa.mStartVisibleTime
                    + (aa.mStartTime != aa.mStartVisibleTime
                            ? mAm.mConstants.FGSERVICE_SCREEN_ON_AFTER_TIME
                            : mAm.mConstants.FGSERVICE_MIN_SHOWN_TIME);
            if (nowElapsed >= minTime) {
                // If shown while the screen is on, and it has been shown for
                // at least the minimum show time, then we can now remove it.
                if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "YES - shown long enough with screen on");
                canRemove = true;
            } else {
                // This is when we will be okay to stop telling the user.
                long reportTime = nowElapsed + mAm.mConstants.FGSERVICE_MIN_REPORT_TIME;
                aa.mHideTime = reportTime > minTime ? reportTime : minTime;
                if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "NO -- wait " + (aa.mHideTime-nowElapsed)
                        + " with screen on");
            }
        } else {
            final long minTime = aa.mEndTime
                    + mAm.mConstants.FGSERVICE_SCREEN_ON_BEFORE_TIME;
            if (nowElapsed >= minTime) {
                // If the foreground service has only run while the screen is
                // off, but it has been gone now for long enough that we won't
                // care to tell the user about it when the screen comes back on,
                // then we can remove it now.
                if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "YES - gone long enough with screen off");
                canRemove = true;
            } else {
                // This is when we won't care about this old fg service.
                aa.mHideTime = minTime;
                if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "NO -- wait " + (aa.mHideTime-nowElapsed)
                        + " with screen off");
            }
        }
        return canRemove;
    }

    void updateForegroundApps(ServiceMap smap) {
        // This is called from the handler without the lock held.
        ArrayList<ActiveForegroundApp> active = null;
        synchronized (mAm) {
            final long now = SystemClock.elapsedRealtime();
            long nextUpdateTime = Long.MAX_VALUE;
            if (smap != null) {
                if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "Updating foreground apps for user "
                        + smap.mUserId);
                for (int i = smap.mActiveForegroundApps.size()-1; i >= 0; i--) {
                    ActiveForegroundApp aa = smap.mActiveForegroundApps.valueAt(i);
                    if (aa.mEndTime != 0) {
                        boolean canRemove = foregroundAppShownEnoughLocked(aa, now);
                        if (canRemove) {
                            // This was up for longer than the timeout, so just remove immediately.
                            smap.mActiveForegroundApps.removeAt(i);
                            smap.mActiveForegroundAppsChanged = true;
                            continue;
                        }
                        if (aa.mHideTime < nextUpdateTime) {
                            nextUpdateTime = aa.mHideTime;
                        }
                    }
                    if (!aa.mAppOnTop) {
                        // Transitioning a fg-service host app out of top: if it's bg restricted,
                        // it loses the fg service state now.
                        if (!appRestrictedAnyInBackground(aa.mUid, aa.mPackageName)) {
                            if (active == null) {
                                active = new ArrayList<>();
                            }
                            if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "Adding active: pkg="
                                    + aa.mPackageName + ", uid=" + aa.mUid);
                            active.add(aa);
                        } else {
                            if (DEBUG_FOREGROUND_SERVICE) {
                                Slog.d(TAG, "bg-restricted app "
                                        + aa.mPackageName + "/" + aa.mUid
                                        + " exiting top; demoting fg services ");
                            }
                            stopAllForegroundServicesLocked(aa.mUid, aa.mPackageName);
                        }
                    }
                }
                smap.removeMessages(ServiceMap.MSG_UPDATE_FOREGROUND_APPS);
                if (nextUpdateTime < Long.MAX_VALUE) {
                    if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "Next update time in: "
                            + (nextUpdateTime-now));
                    Message msg = smap.obtainMessage(ServiceMap.MSG_UPDATE_FOREGROUND_APPS);
                    smap.sendMessageAtTime(msg, nextUpdateTime
                            + SystemClock.uptimeMillis() - SystemClock.elapsedRealtime());
                }
            }
            if (!smap.mActiveForegroundAppsChanged) {
                return;
            }
            smap.mActiveForegroundAppsChanged = false;
        }

        if (!SHOW_DUNGEON_NOTIFICATION) {
            return;
        }

        final NotificationManager nm = (NotificationManager) mAm.mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);
        final Context context = mAm.mContext;

        if (active != null) {
            for (int i = 0; i < active.size(); i++) {
                ActiveForegroundApp aa = active.get(i);
                if (aa.mLabel == null) {
                    PackageManager pm = context.getPackageManager();
                    try {
                        ApplicationInfo ai = pm.getApplicationInfoAsUser(aa.mPackageName,
                                PackageManager.MATCH_KNOWN_PACKAGES, smap.mUserId);
                        aa.mLabel = ai.loadLabel(pm);
                    } catch (PackageManager.NameNotFoundException e) {
                        aa.mLabel = aa.mPackageName;
                    }
                }
            }

            Intent intent;
            String title;
            String msg;
            String[] pkgs;
            final long nowElapsed = SystemClock.elapsedRealtime();
            long oldestStartTime = nowElapsed;
            if (active.size() == 1) {
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.fromParts("package", active.get(0).mPackageName, null));
                title = context.getString(
                        R.string.foreground_service_app_in_background, active.get(0).mLabel);
                msg = context.getString(R.string.foreground_service_tap_for_details);
                pkgs = new String[] { active.get(0).mPackageName };
                oldestStartTime = active.get(0).mStartTime;
            } else {
                intent = new Intent(Settings.ACTION_FOREGROUND_SERVICES_SETTINGS);
                pkgs = new String[active.size()];
                for (int i = 0; i < active.size(); i++) {
                    pkgs[i] = active.get(i).mPackageName;
                    oldestStartTime = Math.min(oldestStartTime, active.get(i).mStartTime);
                }
                intent.putExtra("packages", pkgs);
                title = context.getString(
                        R.string.foreground_service_apps_in_background, active.size());
                msg = active.get(0).mLabel.toString();
                for (int i = 1; i < active.size(); i++) {
                    msg = context.getString(R.string.foreground_service_multiple_separator,
                            msg, active.get(i).mLabel);
                }
            }
            Bundle notificationBundle = new Bundle();
            notificationBundle.putStringArray(Notification.EXTRA_FOREGROUND_APPS, pkgs);
            Notification.Builder n =
                    new Notification.Builder(context,
                            SystemNotificationChannels.FOREGROUND_SERVICE)
                            .addExtras(notificationBundle)
                            .setSmallIcon(R.drawable.stat_sys_vitals)
                            .setOngoing(true)
                            .setShowWhen(oldestStartTime < nowElapsed)
                            .setWhen(System.currentTimeMillis() - (nowElapsed - oldestStartTime))
                            .setColor(context.getColor(
                                    com.android.internal.R.color.system_notification_accent_color))
                            .setContentTitle(title)
                            .setContentText(msg)
                            .setContentIntent(
                                    PendingIntent.getActivityAsUser(context, 0, intent,
                                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE_UNAUDITED,
                                            null, new UserHandle(smap.mUserId)));
            nm.notifyAsUser(null, SystemMessageProto.SystemMessage.NOTE_FOREGROUND_SERVICES,
                    n.build(), new UserHandle(smap.mUserId));
        } else {
            nm.cancelAsUser(null, SystemMessageProto.SystemMessage.NOTE_FOREGROUND_SERVICES,
                    new UserHandle(smap.mUserId));
        }
    }

    private void requestUpdateActiveForegroundAppsLocked(ServiceMap smap, long timeElapsed) {
        Message msg = smap.obtainMessage(ServiceMap.MSG_UPDATE_FOREGROUND_APPS);
        if (timeElapsed != 0) {
            smap.sendMessageAtTime(msg,
                    timeElapsed + SystemClock.uptimeMillis() - SystemClock.elapsedRealtime());
        } else {
            smap.mActiveForegroundAppsChanged = true;
            smap.sendMessage(msg);
        }
    }

    private void decActiveForegroundAppLocked(ServiceMap smap, ServiceRecord r) {
        ActiveForegroundApp active = smap.mActiveForegroundApps.get(r.packageName);
        if (active != null) {
            active.mNumActive--;
            if (active.mNumActive <= 0) {
                active.mEndTime = SystemClock.elapsedRealtime();
                if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "Ended running of service");
                if (foregroundAppShownEnoughLocked(active, active.mEndTime)) {
                    // Have been active for long enough that we will remove it immediately.
                    smap.mActiveForegroundApps.remove(r.packageName);
                    smap.mActiveForegroundAppsChanged = true;
                    requestUpdateActiveForegroundAppsLocked(smap, 0);
                } else if (active.mHideTime < Long.MAX_VALUE){
                    requestUpdateActiveForegroundAppsLocked(smap, active.mHideTime);
                }
            }
        }
    }

    void updateScreenStateLocked(boolean screenOn) {
        if (mScreenOn != screenOn) {
            mScreenOn = screenOn;

            // If screen is turning on, then we now reset the start time of any foreground
            // services that were started while the screen was off.
            if (screenOn) {
                final long nowElapsed = SystemClock.elapsedRealtime();
                if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "Screen turned on");
                for (int i = mServiceMap.size()-1; i >= 0; i--) {
                    ServiceMap smap = mServiceMap.valueAt(i);
                    long nextUpdateTime = Long.MAX_VALUE;
                    boolean changed = false;
                    for (int j = smap.mActiveForegroundApps.size()-1; j >= 0; j--) {
                        ActiveForegroundApp active = smap.mActiveForegroundApps.valueAt(j);
                        if (active.mEndTime == 0) {
                            if (!active.mShownWhileScreenOn) {
                                active.mShownWhileScreenOn = true;
                                active.mStartVisibleTime = nowElapsed;
                            }
                        } else {
                            if (!active.mShownWhileScreenOn
                                    && active.mStartVisibleTime == active.mStartTime) {
                                // If this was never shown while the screen was on, then we will
                                // count the time it started being visible as now, to tell the user
                                // about it now that they have a screen to look at.
                                active.mEndTime = active.mStartVisibleTime = nowElapsed;
                            }
                            if (foregroundAppShownEnoughLocked(active, nowElapsed)) {
                                // Have been active for long enough that we will remove it
                                // immediately.
                                smap.mActiveForegroundApps.remove(active.mPackageName);
                                smap.mActiveForegroundAppsChanged = true;
                                changed = true;
                            } else {
                                if (active.mHideTime < nextUpdateTime) {
                                    nextUpdateTime = active.mHideTime;
                                }
                            }
                        }
                    }
                    if (changed) {
                        // Need to immediately update.
                        requestUpdateActiveForegroundAppsLocked(smap, 0);
                    } else if (nextUpdateTime < Long.MAX_VALUE) {
                        requestUpdateActiveForegroundAppsLocked(smap, nextUpdateTime);
                    }
                }
            }
        }
    }

    void foregroundServiceProcStateChangedLocked(UidRecord uidRec) {
        ServiceMap smap = mServiceMap.get(UserHandle.getUserId(uidRec.getUid()));
        if (smap != null) {
            boolean changed = false;
            for (int j = smap.mActiveForegroundApps.size()-1; j >= 0; j--) {
                ActiveForegroundApp active = smap.mActiveForegroundApps.valueAt(j);
                if (active.mUid == uidRec.getUid()) {
                    if (uidRec.getCurProcState() <= PROCESS_STATE_TOP) {
                        if (!active.mAppOnTop) {
                            active.mAppOnTop = true;
                            changed = true;
                        }
                        active.mShownWhileTop = true;
                    } else if (active.mAppOnTop) {
                        active.mAppOnTop = false;
                        changed = true;
                    }
                }
            }
            if (changed) {
                requestUpdateActiveForegroundAppsLocked(smap, 0);
            }
        }
    }

    private boolean appIsTopLocked(int uid) {
        return mAm.getUidStateLocked(uid) <= PROCESS_STATE_TOP;
    }

    /**
     * @param id Notification ID.  Zero === exit foreground state for the given service.
     */
    private void setServiceForegroundInnerLocked(final ServiceRecord r, int id,
            Notification notification, int flags, int foregroundServiceType) {
        if (id != 0) {
            if (notification == null) {
                throw new IllegalArgumentException("null notification");
            }
            // Instant apps need permission to create foreground services.
            if (r.appInfo.isInstantApp()) {
                final int mode = mAm.getAppOpsManager().checkOpNoThrow(
                        AppOpsManager.OP_INSTANT_APP_START_FOREGROUND,
                        r.appInfo.uid,
                        r.appInfo.packageName);
                switch (mode) {
                    case AppOpsManager.MODE_ALLOWED:
                        break;
                    case AppOpsManager.MODE_IGNORED:
                        Slog.w(TAG, "Instant app " + r.appInfo.packageName
                                + " does not have permission to create foreground services"
                                + ", ignoring.");
                        return;
                    case AppOpsManager.MODE_ERRORED:
                        throw new SecurityException("Instant app " + r.appInfo.packageName
                                + " does not have permission to create foreground services");
                    default:
                        mAm.enforcePermission(
                                android.Manifest.permission.INSTANT_APP_FOREGROUND_SERVICE,
                                r.app.getPid(), r.appInfo.uid, "startForeground");
                }
            } else {
                if (r.appInfo.targetSdkVersion >= Build.VERSION_CODES.P) {
                    mAm.enforcePermission(
                            android.Manifest.permission.FOREGROUND_SERVICE,
                            r.app.getPid(), r.appInfo.uid, "startForeground");
                }

                int manifestType = r.serviceInfo.getForegroundServiceType();
                // If passed in foreground service type is FOREGROUND_SERVICE_TYPE_MANIFEST,
                // consider it is the same as manifest foreground service type.
                if (foregroundServiceType == FOREGROUND_SERVICE_TYPE_MANIFEST) {
                    foregroundServiceType = manifestType;
                }
                // Check the passed in foreground service type flags is a subset of manifest
                // foreground service type flags.
                if ((foregroundServiceType & manifestType) != foregroundServiceType) {
                    throw new IllegalArgumentException("foregroundServiceType "
                        + String.format("0x%08X", foregroundServiceType)
                        + " is not a subset of foregroundServiceType attribute "
                        +  String.format("0x%08X", manifestType)
                        + " in service element of manifest file");
                }
            }

            boolean alreadyStartedOp = false;
            boolean stopProcStatsOp = false;
            if (r.fgRequired) {
                if (DEBUG_SERVICE || DEBUG_BACKGROUND_CHECK) {
                    Slog.i(TAG, "Service called startForeground() as required: " + r);
                }
                r.fgRequired = false;
                r.fgWaiting = false;
                alreadyStartedOp = stopProcStatsOp = true;
                mAm.mHandler.removeMessages(
                        ActivityManagerService.SERVICE_FOREGROUND_TIMEOUT_MSG, r);
            }

            final ProcessServiceRecord psr = r.app.mServices;
            try {
                boolean ignoreForeground = false;
                final int mode = mAm.getAppOpsManager().checkOpNoThrow(
                        AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName);
                switch (mode) {
                    case AppOpsManager.MODE_ALLOWED:
                    case AppOpsManager.MODE_DEFAULT:
                        // All okay.
                        break;
                    case AppOpsManager.MODE_IGNORED:
                        // Whoops, silently ignore this.
                        Slog.w(TAG, "Service.startForeground() not allowed due to app op: service "
                                + r.shortInstanceName);
                        ignoreForeground = true;
                        break;
                    default:
                        throw new SecurityException("Foreground not allowed as per app op");
                }

                // Apps that are TOP or effectively similar may call startForeground() on
                // their services even if they are restricted from doing that while in bg.
                if (!ignoreForeground
                        && !appIsTopLocked(r.appInfo.uid)
                        && appRestrictedAnyInBackground(r.appInfo.uid, r.packageName)) {
                    Slog.w(TAG,
                            "Service.startForeground() not allowed due to bg restriction: service "
                                    + r.shortInstanceName);
                    // Back off of any foreground expectations around this service, since we've
                    // just turned down its fg request.
                    updateServiceForegroundLocked(psr, false);
                    ignoreForeground = true;
                }

                if (!ignoreForeground) {
                    if (r.mStartForegroundCount == 0) {
                        /*
                        If the service was started with startService(), not
                        startForegroundService(), and if startForeground() isn't called within
                        mFgsStartForegroundTimeoutMs, then we check the state of the app
                        (who owns the service, which is the app that called startForeground())
                        again. If the app is in the foreground, or in any other cases where
                        FGS-starts are allowed, then we still allow the FGS to be started.
                        Otherwise, startForeground() would fail.

                        If the service was started with startForegroundService(), then the service
                        must call startForeground() within a timeout anyway, so we don't need this
                        check.
                        */
                        if (!r.fgRequired) {
                            final long delayMs = SystemClock.elapsedRealtime() - r.createRealTime;
                            if (delayMs > mAm.mConstants.mFgsStartForegroundTimeoutMs) {
                                setFgsRestrictionLocked(r.serviceInfo.packageName, r.app.getPid(),
                                        r.appInfo.uid, r.intent.getIntent(), r, r.userId,false);
                                final String temp = "startForegroundDelayMs:" + delayMs;
                                if (r.mInfoAllowStartForeground != null) {
                                    r.mInfoAllowStartForeground += "; " + temp;
                                } else {
                                    r.mInfoAllowStartForeground = temp;
                                }
                                r.mLoggedInfoAllowStartForeground = false;
                            }
                        }
                    } else if (r.mStartForegroundCount >= 1) {
                        // The second or later time startForeground() is called after service is
                        // started. Check for app state again.
                        final long delayMs = SystemClock.elapsedRealtime() -
                                r.mLastSetFgsRestrictionTime;
                        if (delayMs > mAm.mConstants.mFgsStartForegroundTimeoutMs) {
                            setFgsRestrictionLocked(r.serviceInfo.packageName, r.app.getPid(),
                                    r.appInfo.uid, r.intent.getIntent(), r, r.userId,false);
                        }
                    }
                    // If the foreground service is not started from TOP process, do not allow it to
                    // have while-in-use location/camera/microphone access.
                    if (!r.mAllowWhileInUsePermissionInFgs) {
                        Slog.w(TAG,
                                "Foreground service started from background can not have "
                                        + "location/camera/microphone access: service "
                                        + r.shortInstanceName);
                    }
                    logFgsBackgroundStart(r);
                    if (r.mAllowStartForeground == REASON_DENIED && isBgFgsRestrictionEnabled(r)) {
                        final String msg = "Service.startForeground() not allowed due to "
                                + "mAllowStartForeground false: service "
                                + r.shortInstanceName;
                        Slog.w(TAG, msg);
                        showFgsBgRestrictedNotificationLocked(r);
                        updateServiceForegroundLocked(psr, true);
                        ignoreForeground = true;
                        logFGSStateChangeLocked(r,
                                FrameworkStatsLog.FOREGROUND_SERVICE_STATE_CHANGED__STATE__DENIED,
                                0);
                        if (CompatChanges.isChangeEnabled(FGS_START_EXCEPTION_CHANGE_ID,
                                r.appInfo.uid)) {
                            throw new ForegroundServiceStartNotAllowedException(msg);
                        }
                    }
                }

                // Apps under strict background restrictions simply don't get to have foreground
                // services, so now that we've enforced the startForegroundService() contract
                // we only do the machinery of making the service foreground when the app
                // is not restricted.
                if (!ignoreForeground) {
                    if (r.foregroundId != id) {
                        cancelForegroundNotificationLocked(r);
                        r.foregroundId = id;
                    }
                    notification.flags |= Notification.FLAG_FOREGROUND_SERVICE;
                    r.foregroundNoti = notification;
                    r.foregroundServiceType = foregroundServiceType;
                    boolean enterForeground = false;
                    if (!r.isForeground) {
                        final ServiceMap smap = getServiceMapLocked(r.userId);
                        if (smap != null) {
                            ActiveForegroundApp active = smap.mActiveForegroundApps
                                    .get(r.packageName);
                            if (active == null) {
                                active = new ActiveForegroundApp();
                                active.mPackageName = r.packageName;
                                active.mUid = r.appInfo.uid;
                                active.mShownWhileScreenOn = mScreenOn;
                                if (r.app != null) {
                                    final UidRecord uidRec = r.app.getUidRecord();
                                    if (uidRec != null) {
                                        active.mAppOnTop = active.mShownWhileTop =
                                                uidRec.getCurProcState() <= PROCESS_STATE_TOP;
                                    }
                                }
                                active.mStartTime = active.mStartVisibleTime
                                        = SystemClock.elapsedRealtime();
                                smap.mActiveForegroundApps.put(r.packageName, active);
                                requestUpdateActiveForegroundAppsLocked(smap, 0);
                            }
                            active.mNumActive++;
                        }
                        r.isForeground = true;
                        r.mLogEntering = true;
                        enterForeground = true;
                        r.mStartForegroundCount++;
                        r.mFgsEnterTime = SystemClock.uptimeMillis();
                        if (!stopProcStatsOp) {
                            ServiceState stracker = r.getTracker();
                            if (stracker != null) {
                                stracker.setForeground(true,
                                        mAm.mProcessStats.getMemFactorLocked(), r.lastActivity);
                            }
                        } else {
                            stopProcStatsOp = false;
                        }

                        mAm.mAppOpsService.startOperation(
                                AppOpsManager.getToken(mAm.mAppOpsService),
                                AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName,
                                null, true, false, "", false, AppOpsManager.ATTRIBUTION_FLAGS_NONE,
                                AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE);
                        registerAppOpCallbackLocked(r);
                        mAm.updateForegroundServiceUsageStats(r.name, r.userId, true);
                    }
                    // Even if the service is already a FGS, we need to update the notification,
                    // so we need to call it again.
                    r.postNotification();
                    if (r.app != null) {
                        updateServiceForegroundLocked(psr, true);
                    }
                    getServiceMapLocked(r.userId).ensureNotStartingBackgroundLocked(r);
                    mAm.notifyPackageUse(r.serviceInfo.packageName,
                            PackageManager.NOTIFY_PACKAGE_USE_FOREGROUND_SERVICE);
                } else {
                    if (DEBUG_FOREGROUND_SERVICE) {
                        Slog.d(TAG, "Suppressing startForeground() for FAS " + r);
                    }
                }
            } finally {
                if (stopProcStatsOp) {
                    // We got through to this point with it actively being started foreground,
                    // and never decided we wanted to keep it like that, so drop it.
                    ServiceState stracker = r.getTracker();
                    if (stracker != null) {
                        stracker.setForeground(false, mAm.mProcessStats.getMemFactorLocked(),
                                SystemClock.uptimeMillis());
                    }
                }
                if (alreadyStartedOp) {
                    // If we had previously done a start op for direct foreground start,
                    // we have cleared the flag so can now drop it.
                    mAm.mAppOpsService.finishOperation(
                            AppOpsManager.getToken(mAm.mAppOpsService),
                            AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName,
                            null);
                }
            }
        } else {
            if (r.isForeground) {
                final ServiceMap smap = getServiceMapLocked(r.userId);
                if (smap != null) {
                    decActiveForegroundAppLocked(smap, r);
                }
                r.isForeground = false;
                r.mFgsExitTime = SystemClock.uptimeMillis();
                ServiceState stracker = r.getTracker();
                if (stracker != null) {
                    stracker.setForeground(false, mAm.mProcessStats.getMemFactorLocked(),
                            SystemClock.uptimeMillis());
                }
                mAm.mAppOpsService.finishOperation(
                        AppOpsManager.getToken(mAm.mAppOpsService),
                        AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName, null);
                unregisterAppOpCallbackLocked(r);
                logFGSStateChangeLocked(r,
                        FrameworkStatsLog.FOREGROUND_SERVICE_STATE_CHANGED__STATE__EXIT,
                        r.mFgsExitTime > r.mFgsEnterTime
                                ? (int)(r.mFgsExitTime - r.mFgsEnterTime) : 0);
                resetFgsRestrictionLocked(r);
                mAm.updateForegroundServiceUsageStats(r.name, r.userId, false);
                if (r.app != null) {
                    mAm.updateLruProcessLocked(r.app, false, null);
                    updateServiceForegroundLocked(r.app.mServices, true);
                }
            }
            // Leave the time-to-display as already set: re-entering foreground mode will
            // only resume the previous quiet timeout, or will display immediately if the
            // deferral period had already passed.
            if ((flags & Service.STOP_FOREGROUND_REMOVE) != 0) {
                cancelForegroundNotificationLocked(r);
                r.foregroundId = 0;
                r.foregroundNoti = null;
            } else if (r.appInfo.targetSdkVersion >= Build.VERSION_CODES.LOLLIPOP) {
                r.stripForegroundServiceFlagFromNotification();
                if ((flags & Service.STOP_FOREGROUND_DETACH) != 0) {
                    r.foregroundId = 0;
                    r.foregroundNoti = null;
                }
            }
        }
    }

    private boolean withinFgsDeferRateLimit(ServiceRecord sr, final long now) {
        // If we're still within the service's deferral period, then by definition
        // deferral is not rate limited.
        if (now < sr.fgDisplayTime) {
            if (DEBUG_FOREGROUND_SERVICE) {
                Slog.d(TAG_SERVICE, "FGS transition for " + sr
                        + " within deferral period, no rate limit applied");
            }
            return false;
        }

        final int uid = sr.appInfo.uid;
        final long eligible = mFgsDeferralEligible.get(uid, 0L);
        if (DEBUG_FOREGROUND_SERVICE) {
            if (now < eligible) {
                Slog.d(TAG_SERVICE, "FGS transition for uid " + uid
                        + " within rate limit, showing immediately");
            }
        }
        return now < eligible;
    }

    ServiceNotificationPolicy applyForegroundServiceNotificationLocked(Notification notification,
            final int id, final String pkg, final int userId) {
        if (DEBUG_FOREGROUND_SERVICE) {
            Slog.d(TAG_SERVICE, "Evaluating FGS policy for id=" + id
                    + " pkg=" + pkg + " not=" + notification);
        }
        // Is there an FGS using this notification?
        final ServiceMap smap = mServiceMap.get(userId);
        if (smap == null) {
            // No services in this user at all
            return ServiceNotificationPolicy.NOT_FOREGROUND_SERVICE;
        }

        for (int i = 0; i < smap.mServicesByInstanceName.size(); i++) {
            final ServiceRecord sr = smap.mServicesByInstanceName.valueAt(i);
            if (!sr.isForeground
                    || id != sr.foregroundId
                    || !pkg.equals(sr.appInfo.packageName)) {
                // Not this one; keep looking
                continue;
            }

            // Found; it is associated with an FGS.  Make sure that it's flagged:
            // it may have entered the bookkeeping outside of Service-related
            // APIs.  We also make sure to take this latest Notification as
            // the content to be shown (immediately or eventually).
            if (DEBUG_FOREGROUND_SERVICE) {
                Slog.d(TAG_SERVICE, "   FOUND: notification is for " + sr);
            }
            notification.flags |= Notification.FLAG_FOREGROUND_SERVICE;
            sr.foregroundNoti = notification;

            // ...and determine immediate vs deferred display policy for it
            final boolean showNow = shouldShowFgsNotificationLocked(sr);
            if (showNow) {
                if (DEBUG_FOREGROUND_SERVICE) {
                    Slog.d(TAG_SERVICE, "   Showing immediately due to policy");
                }
                sr.mFgsNotificationDeferred = false;
                return ServiceNotificationPolicy.SHOW_IMMEDIATELY;
            }

            // Deferring - kick off the timer if necessary, and tell the caller
            // that it's to be shown only if it's an update to already-
            // visible content (e.g. if it's an FGS adopting a
            // previously-posted Notification).
            if (DEBUG_FOREGROUND_SERVICE) {
                Slog.d(TAG_SERVICE, "   Deferring / update-only");
            }
            startFgsDeferralTimerLocked(sr);
            return ServiceNotificationPolicy.UPDATE_ONLY;
        }

        // None of the services in this user are FGSs
        return ServiceNotificationPolicy.NOT_FOREGROUND_SERVICE;
    }

    // No legacy-app behavior skew intended but there's a runtime E-stop if a need
    // arises, so note that
    @SuppressWarnings("AndroidFrameworkCompatChange")
    private boolean shouldShowFgsNotificationLocked(ServiceRecord r) {
        final long now = SystemClock.uptimeMillis();

        // Is the behavior enabled at all?
        if (!mAm.mConstants.mFlagFgsNotificationDeferralEnabled) {
            return true;
        }

        // Has this service's deferral timer expired?
        if (r.mFgsNotificationDeferred && now >= r.fgDisplayTime) {
            if (DEBUG_FOREGROUND_SERVICE) {
                Slog.d(TAG, "FGS reached end of deferral period: " + r);
            }
            return true;
        }

        // Did the app have another FGS notification deferred recently?
        if (withinFgsDeferRateLimit(r, now)) {
            return true;
        }

        if (mAm.mConstants.mFlagFgsNotificationDeferralApiGated) {
            // Legacy apps' FGS notifications are also deferred unless the relevant
            // DeviceConfig element has been set
            final boolean isLegacyApp = (r.appInfo.targetSdkVersion < Build.VERSION_CODES.S);
            if (isLegacyApp) {
                return true;
            }
        }

        // did we already show it?
        if (r.mFgsNotificationShown) {
            return true;
        }

        // has the app forced deferral?
        if (!r.foregroundNoti.isForegroundDisplayForceDeferred()) {
            // is the notification such that it should show right away?
            if (r.foregroundNoti.shouldShowForegroundImmediately()) {
                if (DEBUG_FOREGROUND_SERVICE) {
                    Slog.d(TAG_SERVICE, "FGS " + r
                            + " notification policy says show immediately");
                }
                return true;
            }

            // or is this an type of FGS that always shows immediately?
            if ((r.foregroundServiceType & FGS_IMMEDIATE_DISPLAY_MASK) != 0) {
                if (DEBUG_FOREGROUND_SERVICE) {
                    Slog.d(TAG_SERVICE, "FGS " + r
                            + " type gets immediate display");
                }
                return true;
            }

            // fall through to return false: no policy dictates immediate display
        } else {
            if (DEBUG_FOREGROUND_SERVICE) {
                Slog.d(TAG_SERVICE, "FGS " + r + " notification is app deferred");
            }
            // fall through to return false
        }

        return false;
    }

    // Target SDK consultation here is strictly for logging purposes, not
    // behavioral variation.
    @SuppressWarnings("AndroidFrameworkCompatChange")
    private void startFgsDeferralTimerLocked(ServiceRecord r) {
        final long now = SystemClock.uptimeMillis();
        final int uid = r.appInfo.uid;

        // schedule the actual notification post
        long when = now + mAm.mConstants.mFgsNotificationDeferralInterval;
        // If there are already deferred FGS notifications for this app,
        // inherit that deferred-show timestamp
        for (int i = 0; i < mPendingFgsNotifications.size(); i++) {
            final ServiceRecord pending = mPendingFgsNotifications.get(i);
            if (pending == r) {
                // Already pending; no need to reschedule
                if (DEBUG_FOREGROUND_SERVICE) {
                    Slog.d(TAG_SERVICE, "FGS " + r
                            + " already pending notification display");
                }
                return;
            }
            if (uid == pending.appInfo.uid) {
                when = Math.min(when, pending.fgDisplayTime);
            }
        }

        if (mFgsDeferralRateLimited) {
            final long nextEligible = when + mAm.mConstants.mFgsNotificationDeferralExclusionTime;
            mFgsDeferralEligible.put(uid, nextEligible);
        }
        r.fgDisplayTime = when;
        r.mFgsNotificationDeferred = true;
        r.mFgsNotificationShown = false;
        mPendingFgsNotifications.add(r);
        if (DEBUG_FOREGROUND_SERVICE) {
            Slog.d(TAG_SERVICE, "FGS " + r
                    + " notification in " + (when - now) + " ms");
        }
        final boolean isLegacyApp = (r.appInfo.targetSdkVersion < Build.VERSION_CODES.S);
        if (isLegacyApp) {
            Slog.i(TAG_SERVICE, "Deferring FGS notification in legacy app "
                    + r.appInfo.packageName + "/" + UserHandle.formatUid(r.appInfo.uid)
                    + " : " + r.foregroundNoti);
        }
        mAm.mHandler.postAtTime(mPostDeferredFGSNotifications, when);
    }

    private final Runnable mPostDeferredFGSNotifications = new Runnable() {
        @Override
        public void run() {
            if (DEBUG_FOREGROUND_SERVICE) {
                Slog.d(TAG_SERVICE, "+++ evaluating deferred FGS notifications +++");
            }
            final long now = SystemClock.uptimeMillis();
            synchronized (mAm) {
                // post all notifications whose time has come
                for (int i = mPendingFgsNotifications.size() - 1; i >= 0; i--) {
                    final ServiceRecord r = mPendingFgsNotifications.get(i);
                    if (r.fgDisplayTime <= now) {
                        if (DEBUG_FOREGROUND_SERVICE) {
                            Slog.d(TAG_SERVICE, "FGS " + r
                                    + " handling deferred notification now");
                        }
                        mPendingFgsNotifications.remove(i);
                        // The service might have been stopped or exited foreground state
                        // in the interval, so we lazy check whether we still need to show
                        // the notification.
                        if (r.isForeground && r.app != null) {
                            r.postNotification();
                            r.mFgsNotificationShown = true;
                        } else {
                            if (DEBUG_FOREGROUND_SERVICE) {
                                Slog.d(TAG_SERVICE, "  - service no longer running/fg, ignoring");
                            }
                        }
                        // Regardless of whether we needed to post the notification or the
                        // service is no longer running, we may not have logged its FGS
                        // transition yet depending on the timing and API sequence that led
                        // to this point - so make sure to do so.
                        maybeLogFGSStateEnteredLocked(r);
                    }
                }
                if (DEBUG_FOREGROUND_SERVICE) {
                    Slog.d(TAG_SERVICE, "Done evaluating deferred FGS notifications; "
                            + mPendingFgsNotifications.size() + " remaining");
                }
            }
        }
    };

    /**
     * Suppress or reenable the rate limit on foreground service notification deferral.
     * Invoked from the activity manager shell command.
     *
     * @param enable false to suppress rate-limit policy; true to reenable it.
     */
    boolean enableFgsNotificationRateLimitLocked(final boolean enable) {
        if (enable != mFgsDeferralRateLimited) {
            mFgsDeferralRateLimited = enable;
            if (!enable) {
                // make sure to reset any active rate limiting
                mFgsDeferralEligible.clear();
            }
        }
        return enable;
    }

    private void removeServiceNotificationDeferralsLocked(String packageName,
            final @UserIdInt int userId) {
        for (int i = mPendingFgsNotifications.size() - 1; i >= 0; i--) {
            final ServiceRecord r = mPendingFgsNotifications.get(i);
            if (userId == r.userId
                    && r.appInfo.packageName.equals(packageName)) {
                mPendingFgsNotifications.remove(i);
                if (DEBUG_FOREGROUND_SERVICE) {
                    Slog.d(TAG_SERVICE, "Removing notification deferral for "
                            + r);
                }
            }
        }
    }

    private void maybeLogFGSStateEnteredLocked(ServiceRecord r) {
        if (r.mLogEntering) {
            logFGSStateChangeLocked(r,
                    FrameworkStatsLog
                            .FOREGROUND_SERVICE_STATE_CHANGED__STATE__ENTER,
                    0);
            r.mLogEntering = false;
        }
    }

    /**
     * Callback from NotificationManagerService whenever it posts a notification
     * associated with a foreground service.  This is the unified handling point
     * for the disjoint code flows that affect an FGS's notifiation content and
     * visibility, starting with both Service.startForeground() and
     * NotificationManager.notify().
     */
    public void onForegroundServiceNotificationUpdateLocked(boolean shown,
            Notification notification, final int id, final String pkg,
            @UserIdInt final int userId) {
        // If this happens to be a Notification for an FGS still in its deferral period,
        // drop the deferral and make sure our content bookkeeping is up to date.
        for (int i = mPendingFgsNotifications.size() - 1; i >= 0; i--) {
            final ServiceRecord sr = mPendingFgsNotifications.get(i);
            if (userId == sr.userId
                    && id == sr.foregroundId
                    && sr.appInfo.packageName.equals(pkg)) {
                // Found it.  If 'shown' is false, it means that the notification
                // subsystem will not be displaying it yet, so all we do is log
                // the "fgs entered" transition noting deferral, then we're done.
                maybeLogFGSStateEnteredLocked(sr);
                if (shown) {
                    if (DEBUG_FOREGROUND_SERVICE) {
                        Slog.d(TAG_SERVICE, "Notification shown; canceling deferral of "
                                + sr);
                    }
                    sr.mFgsNotificationShown = true;
                    sr.mFgsNotificationDeferred = false;
                    mPendingFgsNotifications.remove(i);
                } else {
                    if (DEBUG_FOREGROUND_SERVICE) {
                        Slog.d(TAG_SERVICE, "FGS notification deferred for " + sr);
                    }
                }
            }
        }
        // In all cases, make sure to retain the latest notification content for the FGS
        ServiceMap smap = mServiceMap.get(userId);
        if (smap != null) {
            for (int i = 0; i < smap.mServicesByInstanceName.size(); i++) {
                final ServiceRecord sr = smap.mServicesByInstanceName.valueAt(i);
                if (sr.isForeground
                        && id == sr.foregroundId
                        && sr.appInfo.packageName.equals(pkg)) {
                    if (DEBUG_FOREGROUND_SERVICE) {
                        Slog.d(TAG_SERVICE, "Recording shown notification for "
                                + sr);
                    }
                    sr.foregroundNoti = notification;
                }
            }
        }
    }

    /** Registers an AppOpCallback for monitoring special AppOps for this foreground service. */
    private void registerAppOpCallbackLocked(@NonNull ServiceRecord r) {
        if (r.app == null) {
            return;
        }
        final int uid = r.appInfo.uid;
        AppOpCallback callback = mFgsAppOpCallbacks.get(uid);
        if (callback == null) {
            callback = new AppOpCallback(r.app, mAm.getAppOpsManager());
            mFgsAppOpCallbacks.put(uid, callback);
        }
        callback.registerLocked();
    }

    /** Unregisters a foreground service's AppOpCallback. */
    private void unregisterAppOpCallbackLocked(@NonNull ServiceRecord r) {
        final int uid = r.appInfo.uid;
        final AppOpCallback callback = mFgsAppOpCallbacks.get(uid);
        if (callback != null) {
            callback.unregisterLocked();
            if (callback.isObsoleteLocked()) {
                mFgsAppOpCallbacks.remove(uid);
            }
        }
    }

    /**
     * For monitoring when {@link #LOGGED_AP_OPS} AppOps occur by an app while it is holding
     * at least one foreground service and is not also in the TOP state.
     * Once the uid no longer holds any foreground services, this callback becomes stale
     * (marked by {@link #isObsoleteLocked()}) and must no longer be used.
     *
     * Methods that end in Locked should only be called while the mAm lock is held.
     */
    private static final class AppOpCallback {
        /** AppOps that should be logged if they occur during a foreground service. */
        private static final int[] LOGGED_AP_OPS = new int[] {
                AppOpsManager.OP_COARSE_LOCATION,
                AppOpsManager.OP_FINE_LOCATION,
                AppOpsManager.OP_RECORD_AUDIO,
                AppOpsManager.OP_CAMERA
        };

        private final ProcessRecord mProcessRecord;

        /** Count of acceptances per appop (for LOGGED_AP_OPS) during this fgs session. */
        @GuardedBy("mCounterLock")
        private final SparseIntArray mAcceptedOps = new SparseIntArray();
        /** Count of rejections per appop (for LOGGED_AP_OPS) during this fgs session. */
        @GuardedBy("mCounterLock")
        private final SparseIntArray mRejectedOps = new SparseIntArray();

        /** Lock for the purposes of mAcceptedOps and mRejectedOps. */
        private final Object mCounterLock = new Object();

        /**
         * AppOp Mode (e.g. {@link AppOpsManager#MODE_ALLOWED} per op.
         * This currently cannot change without the process being killed, so they are constants.
         */
        private final SparseIntArray mAppOpModes = new SparseIntArray();

        /**
         * Number of foreground services currently associated with this AppOpCallback (i.e.
         * currently held for this uid).
         */
        @GuardedBy("mAm")
        private int mNumFgs = 0;

        /**
         * Indicates that this Object is stale and must not be used.
         * Specifically, when mNumFgs decreases down to 0, the callbacks will be unregistered and
         * this AppOpCallback is unusable.
         */
        @GuardedBy("mAm")
        private boolean mDestroyed = false;

        private final AppOpsManager mAppOpsManager;

        AppOpCallback(@NonNull ProcessRecord r, @NonNull AppOpsManager appOpsManager) {
            mProcessRecord = r;
            mAppOpsManager = appOpsManager;
            for (int op : LOGGED_AP_OPS) {
                int mode = appOpsManager.unsafeCheckOpRawNoThrow(op, r.uid, r.info.packageName);
                mAppOpModes.put(op, mode);
            }
        }

        private final AppOpsManager.OnOpNotedListener mOpNotedCallback =
                new AppOpsManager.OnOpNotedListener() {
                    @Override
                    public void onOpNoted(int op, int uid, String pkgName,
                            String attributionTag, int flags, int result) {
                        incrementOpCountIfNeeded(op, uid, result);
                    }
        };

        private final AppOpsManager.OnOpStartedListener mOpStartedCallback =
                new AppOpsManager.OnOpStartedListener() {
                    @Override
                    public void onOpStarted(int op, int uid, String pkgName,
                            String attributionTag, int flags,
                            int result) {
                        incrementOpCountIfNeeded(op, uid, result);
                    }
        };

        private void incrementOpCountIfNeeded(int op, int uid, @AppOpsManager.Mode int result) {
            if (uid == mProcessRecord.uid && isNotTop()) {
                incrementOpCount(op, result == AppOpsManager.MODE_ALLOWED);
            }
        }

        private boolean isNotTop() {
            return mProcessRecord.mState.getCurProcState() != PROCESS_STATE_TOP;
        }

        private void incrementOpCount(int op, boolean allowed) {
            synchronized (mCounterLock) {
                final SparseIntArray counter = allowed ? mAcceptedOps : mRejectedOps;
                final int index = counter.indexOfKey(op);
                if (index < 0) {
                    counter.put(op, 1);
                } else {
                    counter.setValueAt(index, counter.valueAt(index) + 1);
                }
            }
        }

        void registerLocked() {
            if (isObsoleteLocked()) {
                Slog.wtf(TAG, "Trying to register on a stale AppOpCallback.");
                return;
            }
            mNumFgs++;
            if (mNumFgs == 1) {
                mAppOpsManager.startWatchingNoted(LOGGED_AP_OPS, mOpNotedCallback);
                mAppOpsManager.startWatchingStarted(LOGGED_AP_OPS, mOpStartedCallback);
            }
        }

        void unregisterLocked() {
            mNumFgs--;
            if (mNumFgs <= 0) {
                mDestroyed = true;
                logFinalValues();
                mAppOpsManager.stopWatchingNoted(mOpNotedCallback);
                mAppOpsManager.stopWatchingStarted(mOpStartedCallback);
            }
        }

        /**
         * Indicates that all foreground services for this uid are now over and the callback is
         * stale and must never be used again.
         */
        boolean isObsoleteLocked() {
            return mDestroyed;
        }

        private void logFinalValues() {
            synchronized (mCounterLock) {
                for (int op : LOGGED_AP_OPS) {
                    final int acceptances = mAcceptedOps.get(op);
                    final int rejections = mRejectedOps.get(op);
                    if (acceptances > 0 ||  rejections > 0) {
                        FrameworkStatsLog.write(
                                FrameworkStatsLog.FOREGROUND_SERVICE_APP_OP_SESSION_ENDED,
                                mProcessRecord.uid, op,
                                modeToEnum(mAppOpModes.get(op)),
                                acceptances, rejections
                        );
                    }
                }
            }
        }

        /** Maps AppOp mode to atoms.proto enum. */
        private static int modeToEnum(int mode) {
            switch (mode) {
                case AppOpsManager.MODE_ALLOWED: return FrameworkStatsLog
                        .FOREGROUND_SERVICE_APP_OP_SESSION_ENDED__APP_OP_MODE__MODE_ALLOWED;
                case AppOpsManager.MODE_IGNORED: return FrameworkStatsLog
                        .FOREGROUND_SERVICE_APP_OP_SESSION_ENDED__APP_OP_MODE__MODE_IGNORED;
                case AppOpsManager.MODE_FOREGROUND: return FrameworkStatsLog
                        .FOREGROUND_SERVICE_APP_OP_SESSION_ENDED__APP_OP_MODE__MODE_FOREGROUND;
                default: return FrameworkStatsLog
                        .FOREGROUND_SERVICE_APP_OP_SESSION_ENDED__APP_OP_MODE__MODE_UNKNOWN;
            }
        }
    }

    private void cancelForegroundNotificationLocked(ServiceRecord r) {
        if (r.foregroundId != 0) {
            // First check to see if this app has any other active foreground services
            // with the same notification ID.  If so, we shouldn't actually cancel it,
            // because that would wipe away the notification that still needs to be shown
            // due the other service.
            ServiceMap sm = getServiceMapLocked(r.userId);
            if (sm != null) {
                for (int i = sm.mServicesByInstanceName.size() - 1; i >= 0; i--) {
                    ServiceRecord other = sm.mServicesByInstanceName.valueAt(i);
                    if (other != r && other.foregroundId == r.foregroundId
                            && other.packageName.equals(r.packageName)) {
                        // Found one!  Abort the cancel.
                        return;
                    }
                }
            }
            r.cancelNotification();
        }
    }

    private void updateServiceForegroundLocked(ProcessServiceRecord psr, boolean oomAdj) {
        boolean anyForeground = false;
        int fgServiceTypes = 0;
        for (int i = psr.numberOfRunningServices() - 1; i >= 0; i--) {
            ServiceRecord sr = psr.getRunningServiceAt(i);
            if (sr.isForeground || sr.fgRequired) {
                anyForeground = true;
                fgServiceTypes |= sr.foregroundServiceType;
            }
        }
        mAm.updateProcessForegroundLocked(psr.mApp, anyForeground, fgServiceTypes, oomAdj);
    }

    private void updateAllowlistManagerLocked(ProcessServiceRecord psr) {
        psr.mAllowlistManager = false;
        for (int i = psr.numberOfRunningServices() - 1; i >= 0; i--) {
            ServiceRecord sr = psr.getRunningServiceAt(i);
            if (sr.allowlistManager) {
                psr.mAllowlistManager = true;
                break;
            }
        }
    }

    private void stopServiceAndUpdateAllowlistManagerLocked(ServiceRecord service) {
        final ProcessServiceRecord psr = service.app.mServices;
        psr.stopService(service);
        psr.updateBoundClientUids();
        if (service.allowlistManager) {
            updateAllowlistManagerLocked(psr);
        }
    }

    void updateServiceConnectionActivitiesLocked(ProcessServiceRecord clientPsr) {
        ArraySet<ProcessRecord> updatedProcesses = null;
        for (int i = 0; i < clientPsr.numberOfConnections(); i++) {
            final ConnectionRecord conn = clientPsr.getConnectionAt(i);
            final ProcessRecord proc = conn.binding.service.app;
            if (proc == null || proc == clientPsr.mApp) {
                continue;
            } else if (updatedProcesses == null) {
                updatedProcesses = new ArraySet<>();
            } else if (updatedProcesses.contains(proc)) {
                continue;
            }
            updatedProcesses.add(proc);
            updateServiceClientActivitiesLocked(proc.mServices, null, false);
        }
    }

    private boolean updateServiceClientActivitiesLocked(ProcessServiceRecord psr,
            ConnectionRecord modCr, boolean updateLru) {
        if (modCr != null && modCr.binding.client != null) {
            if (!modCr.binding.client.hasActivities()) {
                // This connection is from a client without activities, so adding
                // and removing is not interesting.
                return false;
            }
        }

        boolean anyClientActivities = false;
        for (int i = psr.numberOfRunningServices() - 1; i >= 0 && !anyClientActivities; i--) {
            ServiceRecord sr = psr.getRunningServiceAt(i);
            ArrayMap<IBinder, ArrayList<ConnectionRecord>> connections = sr.getConnections();
            for (int conni = connections.size() - 1; conni >= 0 && !anyClientActivities; conni--) {
                ArrayList<ConnectionRecord> clist = connections.valueAt(conni);
                for (int cri=clist.size()-1; cri>=0; cri--) {
                    ConnectionRecord cr = clist.get(cri);
                    if (cr.binding.client == null || cr.binding.client == psr.mApp) {
                        // Binding to ourself is not interesting.
                        continue;
                    }
                    if (cr.binding.client.hasActivities()) {
                        anyClientActivities = true;
                        break;
                    }
                }
            }
        }
        if (anyClientActivities != psr.hasClientActivities()) {
            psr.setHasClientActivities(anyClientActivities);
            if (updateLru) {
                mAm.updateLruProcessLocked(psr.mApp, anyClientActivities, null);
            }
            return true;
        }
        return false;
    }

    int bindServiceLocked(IApplicationThread caller, IBinder token, Intent service,
            String resolvedType, final IServiceConnection connection, int flags,
            String instanceName, String callingPackage, final int userId)
            throws TransactionTooLargeException {
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "bindService: " + service
                + " type=" + resolvedType + " conn=" + connection.asBinder()
                + " flags=0x" + Integer.toHexString(flags));
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        final ProcessRecord callerApp = mAm.getRecordForAppLOSP(caller);
        if (callerApp == null) {
            throw new SecurityException(
                    "Unable to find app for caller " + caller
                    + " (pid=" + callingPid
                    + ") when binding service " + service);
        }

        ActivityServiceConnectionsHolder<ConnectionRecord> activity = null;
        if (token != null) {
            activity = mAm.mAtmInternal.getServiceConnectionsHolder(token);
            if (activity == null) {
                Slog.w(TAG, "Binding with unknown activity: " + token);
                return 0;
            }
        }

        int clientLabel = 0;
        PendingIntent clientIntent = null;
        final boolean isCallerSystem = callerApp.info.uid == Process.SYSTEM_UID;

        if (isCallerSystem) {
            // Hacky kind of thing -- allow system stuff to tell us
            // what they are, so we can report this elsewhere for
            // others to know why certain services are running.
            service.setDefusable(true);
            clientIntent = service.getParcelableExtra(Intent.EXTRA_CLIENT_INTENT);
            if (clientIntent != null) {
                clientLabel = service.getIntExtra(Intent.EXTRA_CLIENT_LABEL, 0);
                if (clientLabel != 0) {
                    // There are no useful extras in the intent, trash them.
                    // System code calling with this stuff just needs to know
                    // this will happen.
                    service = service.cloneFilter();
                }
            }
        }

        if ((flags&Context.BIND_TREAT_LIKE_ACTIVITY) != 0) {
            mAm.enforceCallingPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS,
                    "BIND_TREAT_LIKE_ACTIVITY");
        }

        if ((flags & Context.BIND_SCHEDULE_LIKE_TOP_APP) != 0 && !isCallerSystem) {
            throw new SecurityException("Non-system caller (pid=" + callingPid
                    + ") set BIND_SCHEDULE_LIKE_TOP_APP when binding service " + service);
        }

        if ((flags & Context.BIND_ALLOW_WHITELIST_MANAGEMENT) != 0 && !isCallerSystem) {
            throw new SecurityException(
                    "Non-system caller " + caller + " (pid=" + callingPid
                    + ") set BIND_ALLOW_WHITELIST_MANAGEMENT when binding service " + service);
        }

        if ((flags & Context.BIND_ALLOW_INSTANT) != 0 && !isCallerSystem) {
            throw new SecurityException(
                    "Non-system caller " + caller + " (pid=" + callingPid
                            + ") set BIND_ALLOW_INSTANT when binding service " + service);
        }

        if ((flags & Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS) != 0) {
            mAm.enforceCallingPermission(
                    android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND,
                    "BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS");
        }

        if ((flags & Context.BIND_ALLOW_FOREGROUND_SERVICE_STARTS_FROM_BACKGROUND) != 0) {
            mAm.enforceCallingPermission(
                    android.Manifest.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND,
                    "BIND_ALLOW_FOREGROUND_SERVICE_STARTS_FROM_BACKGROUND");
        }

        final boolean callerFg = callerApp.mState.getSetSchedGroup()
                != ProcessList.SCHED_GROUP_BACKGROUND;
        final boolean isBindExternal = (flags & Context.BIND_EXTERNAL_SERVICE) != 0;
        final boolean allowInstant = (flags & Context.BIND_ALLOW_INSTANT) != 0;

        ServiceLookupResult res =
            retrieveServiceLocked(service, instanceName, resolvedType, callingPackage,
                    callingPid, callingUid, userId, true,
                    callerFg, isBindExternal, allowInstant);
        if (res == null) {
            return 0;
        }
        if (res.record == null) {
            return -1;
        }
        ServiceRecord s = res.record;

        // The package could be frozen (meaning it's doing surgery), defer the actual
        // binding until the package is unfrozen.
        boolean packageFrozen = deferServiceBringupIfFrozenLocked(s, service, callingPackage, null,
                callingUid, callingPid, false, callerFg, userId, false, null, true, connection);

        // If permissions need a review before any of the app components can run,
        // we schedule binding to the service but do not start its process, then
        // we launch a review activity to which is passed a callback to invoke
        // when done to start the bound service's process to completing the binding.
        boolean permissionsReviewRequired = !packageFrozen
                && !requestStartTargetPermissionsReviewIfNeededLocked(s, callingPackage, null,
                        callingUid, service, callerFg, userId, true, connection);

        final long origId = Binder.clearCallingIdentity();

        try {
            if (unscheduleServiceRestartLocked(s, callerApp.info.uid, false)) {
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "BIND SERVICE WHILE RESTART PENDING: "
                        + s);
            }

            if ((flags&Context.BIND_AUTO_CREATE) != 0) {
                s.lastActivity = SystemClock.uptimeMillis();
                if (!s.hasAutoCreateConnections()) {
                    // This is the first binding, let the tracker know.
                    ServiceState stracker = s.getTracker();
                    if (stracker != null) {
                        stracker.setBound(true, mAm.mProcessStats.getMemFactorLocked(),
                                s.lastActivity);
                    }
                }
            }

            if ((flags & Context.BIND_RESTRICT_ASSOCIATIONS) != 0) {
                mAm.requireAllowedAssociationsLocked(s.appInfo.packageName);
            }

            mAm.startAssociationLocked(callerApp.uid, callerApp.processName,
                    callerApp.mState.getCurProcState(), s.appInfo.uid, s.appInfo.longVersionCode,
                    s.instanceName, s.processName);
            // Once the apps have become associated, if one of them is caller is ephemeral
            // the target app should now be able to see the calling app
            mAm.grantImplicitAccess(callerApp.userId, service,
                    callerApp.uid, UserHandle.getAppId(s.appInfo.uid));

            AppBindRecord b = s.retrieveAppBindingLocked(service, callerApp);
            ConnectionRecord c = new ConnectionRecord(b, activity,
                    connection, flags, clientLabel, clientIntent,
                    callerApp.uid, callerApp.processName, callingPackage);

            IBinder binder = connection.asBinder();
            s.addConnection(binder, c);
            b.connections.add(c);
            if (activity != null) {
                activity.addConnection(c);
            }
            final ProcessServiceRecord clientPsr = b.client.mServices;
            clientPsr.addConnection(c);
            c.startAssociationIfNeeded();
            if ((c.flags&Context.BIND_ABOVE_CLIENT) != 0) {
                clientPsr.setHasAboveClient(true);
            }
            if ((c.flags&Context.BIND_ALLOW_WHITELIST_MANAGEMENT) != 0) {
                s.allowlistManager = true;
            }
            if ((flags & Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS) != 0) {
                s.setAllowedBgActivityStartsByBinding(true);
            }

            if ((flags & Context.BIND_NOT_APP_COMPONENT_USAGE) != 0) {
                s.isNotAppComponentUsage = true;
            }

            if (s.app != null) {
                updateServiceClientActivitiesLocked(s.app.mServices, c, true);
            }
            ArrayList<ConnectionRecord> clist = mServiceConnections.get(binder);
            if (clist == null) {
                clist = new ArrayList<>();
                mServiceConnections.put(binder, clist);
            }
            clist.add(c);

            boolean needOomAdj = false;
            if ((flags&Context.BIND_AUTO_CREATE) != 0) {
                s.lastActivity = SystemClock.uptimeMillis();
                needOomAdj = true;
                if (bringUpServiceLocked(s, service.getFlags(), callerFg, false,
                        permissionsReviewRequired, packageFrozen, true) != null) {
                    mAm.updateOomAdjPendingTargetsLocked(OomAdjuster.OOM_ADJ_REASON_BIND_SERVICE);
                    return 0;
                }
            }
            setFgsRestrictionLocked(callingPackage, callingPid, callingUid, service, s, userId,
                    false);

            if (s.app != null) {
                ProcessServiceRecord servicePsr = s.app.mServices;
                if ((flags&Context.BIND_TREAT_LIKE_ACTIVITY) != 0) {
                    servicePsr.setTreatLikeActivity(true);
                }
                if (s.allowlistManager) {
                    servicePsr.mAllowlistManager = true;
                }
                // This could have made the service more important.
                mAm.updateLruProcessLocked(s.app, (callerApp.hasActivitiesOrRecentTasks()
                            && servicePsr.hasClientActivities())
                        || (callerApp.mState.getCurProcState() <= PROCESS_STATE_TOP
                            && (flags & Context.BIND_TREAT_LIKE_ACTIVITY) != 0),
                        b.client);
                needOomAdj = true;
                mAm.enqueueOomAdjTargetLocked(s.app);
            }
            if (needOomAdj) {
                mAm.updateOomAdjPendingTargetsLocked(OomAdjuster.OOM_ADJ_REASON_BIND_SERVICE);
            }

            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Bind " + s + " with " + b
                    + ": received=" + b.intent.received
                    + " apps=" + b.intent.apps.size()
                    + " doRebind=" + b.intent.doRebind);

            if (s.app != null && b.intent.received) {
                // Service is already running, so we can immediately
                // publish the connection.
                try {
                    c.conn.connected(s.name, b.intent.binder, false);
                } catch (Exception e) {
                    Slog.w(TAG, "Failure sending service " + s.shortInstanceName
                            + " to connection " + c.conn.asBinder()
                            + " (in " + c.binding.client.processName + ")", e);
                }

                // If this is the first app connected back to this binding,
                // and the service had previously asked to be told when
                // rebound, then do so.
                if (b.intent.apps.size() == 1 && b.intent.doRebind) {
                    requestServiceBindingLocked(s, b.intent, callerFg, true);
                }
            } else if (!b.intent.requested) {
                requestServiceBindingLocked(s, b.intent, callerFg, false);
            }

            maybeLogBindCrossProfileService(userId, callingPackage, callerApp.info.uid);

            getServiceMapLocked(s.userId).ensureNotStartingBackgroundLocked(s);

        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        return 1;
    }

    private void maybeLogBindCrossProfileService(
            int userId, String callingPackage, int callingUid) {
        if (UserHandle.isCore(callingUid)) {
            return;
        }
        final int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId == userId
                || !mAm.mUserController.isSameProfileGroup(callingUserId, userId)) {
            return;
        }
        DevicePolicyEventLogger.createEvent(DevicePolicyEnums.BIND_CROSS_PROFILE_SERVICE)
                .setStrings(callingPackage)
                .write();
    }

    void publishServiceLocked(ServiceRecord r, Intent intent, IBinder service) {
        final long origId = Binder.clearCallingIdentity();
        try {
            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "PUBLISHING " + r
                    + " " + intent + ": " + service);
            if (r != null) {
                Intent.FilterComparison filter
                        = new Intent.FilterComparison(intent);
                IntentBindRecord b = r.bindings.get(filter);
                if (b != null && !b.received) {
                    b.binder = service;
                    b.requested = true;
                    b.received = true;
                    ArrayMap<IBinder, ArrayList<ConnectionRecord>> connections = r.getConnections();
                    for (int conni = connections.size() - 1; conni >= 0; conni--) {
                        ArrayList<ConnectionRecord> clist = connections.valueAt(conni);
                        for (int i=0; i<clist.size(); i++) {
                            ConnectionRecord c = clist.get(i);
                            if (!filter.equals(c.binding.intent.intent)) {
                                if (DEBUG_SERVICE) Slog.v(
                                        TAG_SERVICE, "Not publishing to: " + c);
                                if (DEBUG_SERVICE) Slog.v(
                                        TAG_SERVICE, "Bound intent: " + c.binding.intent.intent);
                                if (DEBUG_SERVICE) Slog.v(
                                        TAG_SERVICE, "Published intent: " + intent);
                                continue;
                            }
                            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Publishing to: " + c);
                            try {
                                c.conn.connected(r.name, service, false);
                            } catch (Exception e) {
                                Slog.w(TAG, "Failure sending service " + r.shortInstanceName
                                      + " to connection " + c.conn.asBinder()
                                      + " (in " + c.binding.client.processName + ")", e);
                            }
                        }
                    }
                }

                serviceDoneExecutingLocked(r, mDestroyingServices.contains(r), false, false);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void updateServiceGroupLocked(IServiceConnection connection, int group, int importance) {
        final IBinder binder = connection.asBinder();
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "updateServiceGroup: conn=" + binder);
        final ArrayList<ConnectionRecord> clist = mServiceConnections.get(binder);
        if (clist == null) {
            throw new IllegalArgumentException("Could not find connection for "
                    + connection.asBinder());
        }
        for (int i = clist.size() - 1; i >= 0; i--) {
            final ConnectionRecord crec = clist.get(i);
            final ServiceRecord srec = crec.binding.service;
            if (srec != null && (srec.serviceInfo.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0) {
                if (srec.app != null) {
                    final ProcessServiceRecord psr = srec.app.mServices;
                    if (group > 0) {
                        psr.setConnectionService(srec);
                        psr.setConnectionGroup(group);
                        psr.setConnectionImportance(importance);
                    } else {
                        psr.setConnectionService(null);
                        psr.setConnectionGroup(0);
                        psr.setConnectionImportance(0);
                    }
                } else {
                    if (group > 0) {
                        srec.pendingConnectionGroup = group;
                        srec.pendingConnectionImportance = importance;
                    } else {
                        srec.pendingConnectionGroup = 0;
                        srec.pendingConnectionImportance = 0;
                    }
                }
            }
        }
    }

    boolean unbindServiceLocked(IServiceConnection connection) {
        IBinder binder = connection.asBinder();
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "unbindService: conn=" + binder);
        ArrayList<ConnectionRecord> clist = mServiceConnections.get(binder);
        if (clist == null) {
            Slog.w(TAG, "Unbind failed: could not find connection for "
                  + connection.asBinder());
            return false;
        }

        final long origId = Binder.clearCallingIdentity();
        try {
            while (clist.size() > 0) {
                ConnectionRecord r = clist.get(0);
                removeConnectionLocked(r, null, null, true);
                if (clist.size() > 0 && clist.get(0) == r) {
                    // In case it didn't get removed above, do it now.
                    Slog.wtf(TAG, "Connection " + r + " not removed for binder " + binder);
                    clist.remove(0);
                }

                final ProcessRecord app = r.binding.service.app;
                if (app != null) {
                    final ProcessServiceRecord psr = app.mServices;
                    if (psr.mAllowlistManager) {
                        updateAllowlistManagerLocked(psr);
                    }
                    // This could have made the service less important.
                    if ((r.flags&Context.BIND_TREAT_LIKE_ACTIVITY) != 0) {
                        psr.setTreatLikeActivity(true);
                        mAm.updateLruProcessLocked(app, true, null);
                    }
                    mAm.enqueueOomAdjTargetLocked(app);
                }
            }

            mAm.updateOomAdjPendingTargetsLocked(OomAdjuster.OOM_ADJ_REASON_UNBIND_SERVICE);

        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        return true;
    }

    void unbindFinishedLocked(ServiceRecord r, Intent intent, boolean doRebind) {
        final long origId = Binder.clearCallingIdentity();
        try {
            if (r != null) {
                Intent.FilterComparison filter
                        = new Intent.FilterComparison(intent);
                IntentBindRecord b = r.bindings.get(filter);
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "unbindFinished in " + r
                        + " at " + b + ": apps="
                        + (b != null ? b.apps.size() : 0));

                boolean inDestroying = mDestroyingServices.contains(r);
                if (b != null) {
                    if (b.apps.size() > 0 && !inDestroying) {
                        // Applications have already bound since the last
                        // unbind, so just rebind right here.
                        boolean inFg = false;
                        for (int i=b.apps.size()-1; i>=0; i--) {
                            ProcessRecord client = b.apps.valueAt(i).client;
                            if (client != null && client.mState.getSetSchedGroup()
                                    != ProcessList.SCHED_GROUP_BACKGROUND) {
                                inFg = true;
                                break;
                            }
                        }
                        try {
                            requestServiceBindingLocked(r, b, inFg, true);
                        } catch (TransactionTooLargeException e) {
                            // Don't pass this back to ActivityThread, it's unrelated.
                        }
                    } else {
                        // Note to tell the service the next time there is
                        // a new client.
                        b.doRebind = true;
                    }
                }

                serviceDoneExecutingLocked(r, inDestroying, false, false);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private final ServiceRecord findServiceLocked(ComponentName name,
            IBinder token, int userId) {
        ServiceRecord r = getServiceByNameLocked(name, userId);
        return r == token ? r : null;
    }

    private final class ServiceLookupResult {
        final ServiceRecord record;
        final String permission;

        ServiceLookupResult(ServiceRecord _record, String _permission) {
            record = _record;
            permission = _permission;
        }
    }

    private class ServiceRestarter implements Runnable {
        private ServiceRecord mService;

        void setService(ServiceRecord service) {
            mService = service;
        }

        public void run() {
            synchronized(mAm) {
                performServiceRestartLocked(mService);
            }
        }
    }

    private ServiceLookupResult retrieveServiceLocked(Intent service,
            String instanceName, String resolvedType, String callingPackage,
            int callingPid, int callingUid, int userId,
            boolean createIfNeeded, boolean callingFromFg, boolean isBindExternal,
            boolean allowInstant) {
        ServiceRecord r = null;
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "retrieveServiceLocked: " + service
                + " type=" + resolvedType + " callingUid=" + callingUid);

        userId = mAm.mUserController.handleIncomingUser(callingPid, callingUid, userId,
                /* allowAll= */false, getAllowMode(service, callingPackage),
                /* name= */ "service", callingPackage);

        ServiceMap smap = getServiceMapLocked(userId);
        final ComponentName comp;
        if (instanceName == null) {
            comp = service.getComponent();
        } else {
            final ComponentName realComp = service.getComponent();
            if (realComp == null) {
                throw new IllegalArgumentException("Can't use custom instance name '" + instanceName
                        + "' without expicit component in Intent");
            }
            comp = new ComponentName(realComp.getPackageName(),
                    realComp.getClassName() + ":" + instanceName);
        }
        if (comp != null) {
            r = smap.mServicesByInstanceName.get(comp);
            if (DEBUG_SERVICE && r != null) Slog.v(TAG_SERVICE, "Retrieved by component: " + r);
        }
        if (r == null && !isBindExternal && instanceName == null) {
            Intent.FilterComparison filter = new Intent.FilterComparison(service);
            r = smap.mServicesByIntent.get(filter);
            if (DEBUG_SERVICE && r != null) Slog.v(TAG_SERVICE, "Retrieved by intent: " + r);
        }
        if (r != null) {
            // Compared to resolveService below, the ServiceRecord here is retrieved from
            // ServiceMap so the package visibility doesn't apply to it. We need to filter it.
            if (mAm.getPackageManagerInternal().filterAppAccess(r.packageName, callingUid,
                    userId)) {
                Slog.w(TAG_SERVICE, "Unable to start service " + service + " U=" + userId
                        + ": not found");
                return null;
            }
            if ((r.serviceInfo.flags & ServiceInfo.FLAG_EXTERNAL_SERVICE) != 0
                    && !callingPackage.equals(r.packageName)) {
                // If an external service is running within its own package, other packages
                // should not bind to that instance.
                r = null;
                if (DEBUG_SERVICE) {
                    Slog.v(TAG_SERVICE, "Whoops, can't use existing external service");
                }
            }
        }
        if (r == null) {
            try {
                int flags = ActivityManagerService.STOCK_PM_FLAGS
                        | PackageManager.MATCH_DEBUG_TRIAGED_MISSING;
                if (allowInstant) {
                    flags |= PackageManager.MATCH_INSTANT;
                }
                // TODO: come back and remove this assumption to triage all services
                ResolveInfo rInfo = mAm.getPackageManagerInternal().resolveService(service,
                        resolvedType, flags, userId, callingUid);
                ServiceInfo sInfo = rInfo != null ? rInfo.serviceInfo : null;
                if (sInfo == null) {
                    Slog.w(TAG_SERVICE, "Unable to start service " + service + " U=" + userId +
                          ": not found");
                    return null;
                }
                if (instanceName != null
                        && (sInfo.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) == 0) {
                    throw new IllegalArgumentException("Can't use instance name '" + instanceName
                            + "' with non-isolated service '" + sInfo.name + "'");
                }
                ComponentName className = new ComponentName(
                        sInfo.applicationInfo.packageName, sInfo.name);
                ComponentName name = comp != null ? comp : className;
                if (!mAm.validateAssociationAllowedLocked(callingPackage, callingUid,
                        name.getPackageName(), sInfo.applicationInfo.uid)) {
                    String msg = "association not allowed between packages "
                            + callingPackage + " and " + name.getPackageName();
                    Slog.w(TAG, "Service lookup failed: " + msg);
                    return new ServiceLookupResult(null, msg);
                }

                // Store the defining packageName and uid, as they might be changed in
                // the ApplicationInfo for external services (which run with the package name
                // and uid of the caller).
                String definingPackageName = sInfo.applicationInfo.packageName;
                int definingUid = sInfo.applicationInfo.uid;
                if ((sInfo.flags & ServiceInfo.FLAG_EXTERNAL_SERVICE) != 0) {
                    if (isBindExternal) {
                        if (!sInfo.exported) {
                            throw new SecurityException("BIND_EXTERNAL_SERVICE failed, "
                                    + className + " is not exported");
                        }
                        if ((sInfo.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) == 0) {
                            throw new SecurityException("BIND_EXTERNAL_SERVICE failed, "
                                    + className + " is not an isolatedProcess");
                        }
                        // Run the service under the calling package's application.
                        ApplicationInfo aInfo = AppGlobals.getPackageManager().getApplicationInfo(
                                callingPackage, ActivityManagerService.STOCK_PM_FLAGS, userId);
                        if (aInfo == null) {
                            throw new SecurityException("BIND_EXTERNAL_SERVICE failed, " +
                                    "could not resolve client package " + callingPackage);
                        }
                        sInfo = new ServiceInfo(sInfo);
                        sInfo.applicationInfo = new ApplicationInfo(sInfo.applicationInfo);
                        sInfo.applicationInfo.packageName = aInfo.packageName;
                        sInfo.applicationInfo.uid = aInfo.uid;
                        name = new ComponentName(aInfo.packageName, name.getClassName());
                        className = new ComponentName(aInfo.packageName,
                                instanceName == null ? className.getClassName()
                                        : (className.getClassName() + ":" + instanceName));
                        service.setComponent(name);
                    } else {
                        throw new SecurityException("BIND_EXTERNAL_SERVICE required for " +
                                name);
                    }
                } else if (isBindExternal) {
                    throw new SecurityException("BIND_EXTERNAL_SERVICE failed, " + name +
                            " is not an externalService");
                }
                if (userId > 0) {
                    if (mAm.isSingleton(sInfo.processName, sInfo.applicationInfo,
                            sInfo.name, sInfo.flags)
                            && mAm.isValidSingletonCall(callingUid, sInfo.applicationInfo.uid)) {
                        userId = 0;
                        smap = getServiceMapLocked(0);
                        // Bypass INTERACT_ACROSS_USERS permission check
                        final long token = Binder.clearCallingIdentity();
                        try {
                            ResolveInfo rInfoForUserId0 =
                                    mAm.getPackageManagerInternal().resolveService(service,
                                            resolvedType, flags, userId, callingUid);
                            if (rInfoForUserId0 == null) {
                                Slog.w(TAG_SERVICE,
                                        "Unable to resolve service " + service + " U=" + userId
                                                + ": not found");
                                return null;
                            }
                            sInfo = rInfoForUserId0.serviceInfo;
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    }
                    sInfo = new ServiceInfo(sInfo);
                    sInfo.applicationInfo = mAm.getAppInfoForUser(sInfo.applicationInfo, userId);
                }
                r = smap.mServicesByInstanceName.get(name);
                if (DEBUG_SERVICE && r != null) Slog.v(TAG_SERVICE,
                        "Retrieved via pm by intent: " + r);
                if (r == null && createIfNeeded) {
                    final Intent.FilterComparison filter
                            = new Intent.FilterComparison(service.cloneFilter());
                    final ServiceRestarter res = new ServiceRestarter();
                    r = new ServiceRecord(mAm, className, name, definingPackageName,
                            definingUid, filter, sInfo, callingFromFg, res);
                    res.setService(r);
                    smap.mServicesByInstanceName.put(name, r);
                    smap.mServicesByIntent.put(filter, r);

                    // Make sure this component isn't in the pending list.
                    for (int i=mPendingServices.size()-1; i>=0; i--) {
                        final ServiceRecord pr = mPendingServices.get(i);
                        if (pr.serviceInfo.applicationInfo.uid == sInfo.applicationInfo.uid
                                && pr.instanceName.equals(name)) {
                            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Remove pending: " + pr);
                            mPendingServices.remove(i);
                        }
                    }
                    for (int i = mPendingBringups.size() - 1; i >= 0; i--) {
                        final ServiceRecord pr = mPendingBringups.keyAt(i);
                        if (pr.serviceInfo.applicationInfo.uid == sInfo.applicationInfo.uid
                                && pr.instanceName.equals(name)) {
                            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Remove pending bringup: " + pr);
                            mPendingBringups.removeAt(i);
                        }
                    }
                    if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Retrieve created new service: " + r);
                }
            } catch (RemoteException ex) {
                // pm is in same process, this will never happen.
            }
        }
        if (r != null) {
            r.mRecentCallingPackage = callingPackage;
            r.mRecentCallingUid = callingUid;
            try {
                r.mRecentCallerApplicationInfo =
                        mAm.mContext.getPackageManager().getApplicationInfoAsUser(callingPackage,
                                0, UserHandle.getUserId(callingUid));
            } catch (PackageManager.NameNotFoundException e) {
            }
            if (!mAm.validateAssociationAllowedLocked(callingPackage, callingUid, r.packageName,
                    r.appInfo.uid)) {
                String msg = "association not allowed between packages "
                        + callingPackage + " and " + r.packageName;
                Slog.w(TAG, "Service lookup failed: " + msg);
                return new ServiceLookupResult(null, msg);
            }
            if (!mAm.mIntentFirewall.checkService(r.name, service, callingUid, callingPid,
                    resolvedType, r.appInfo)) {
                return new ServiceLookupResult(null, "blocked by firewall");
            }
            if (mAm.checkComponentPermission(r.permission,
                    callingPid, callingUid, r.appInfo.uid, r.exported) != PERMISSION_GRANTED) {
                if (!r.exported) {
                    Slog.w(TAG, "Permission Denial: Accessing service " + r.shortInstanceName
                            + " from pid=" + callingPid
                            + ", uid=" + callingUid
                            + " that is not exported from uid " + r.appInfo.uid);
                    return new ServiceLookupResult(null, "not exported from uid "
                            + r.appInfo.uid);
                }
                Slog.w(TAG, "Permission Denial: Accessing service " + r.shortInstanceName
                        + " from pid=" + callingPid
                        + ", uid=" + callingUid
                        + " requires " + r.permission);
                return new ServiceLookupResult(null, r.permission);
            } else if (Manifest.permission.BIND_HOTWORD_DETECTION_SERVICE.equals(r.permission)
                    && callingUid != Process.SYSTEM_UID) {
                // Hotword detection must run in its own sandbox, and we don't even trust
                // its enclosing application to bind to it - only the system.
                // TODO(b/185746653) remove this special case and generalize
                Slog.w(TAG, "Permission Denial: Accessing service " + r.shortInstanceName
                        + " from pid=" + callingPid
                        + ", uid=" + callingUid
                        + " requiring permission " + r.permission
                        + " can only be bound to from the system.");
                return new ServiceLookupResult(null, "can only be bound to "
                        + "by the system.");
            } else if (r.permission != null && callingPackage != null) {
                final int opCode = AppOpsManager.permissionToOpCode(r.permission);
                if (opCode != AppOpsManager.OP_NONE && mAm.getAppOpsManager().checkOpNoThrow(
                        opCode, callingUid, callingPackage) != AppOpsManager.MODE_ALLOWED) {
                    Slog.w(TAG, "Appop Denial: Accessing service " + r.shortInstanceName
                            + " from pid=" + callingPid
                            + ", uid=" + callingUid
                            + " requires appop " + AppOpsManager.opToName(opCode));
                    return null;
                }
            }
            return new ServiceLookupResult(r, null);
        }
        return null;
    }

    private int getAllowMode(Intent service, @Nullable String callingPackage) {
        if (callingPackage == null || service.getComponent() == null) {
            return ActivityManagerInternal.ALLOW_NON_FULL_IN_PROFILE;
        }
        if (callingPackage.equals(service.getComponent().getPackageName())) {
            return ActivityManagerInternal.ALLOW_ALL_PROFILE_PERMISSIONS_IN_PROFILE;
        } else {
            return ActivityManagerInternal.ALLOW_NON_FULL_IN_PROFILE;
        }
    }

    private final void bumpServiceExecutingLocked(ServiceRecord r, boolean fg, String why) {
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, ">>> EXECUTING "
                + why + " of " + r + " in app " + r.app);
        else if (DEBUG_SERVICE_EXECUTING) Slog.v(TAG_SERVICE_EXECUTING, ">>> EXECUTING "
                + why + " of " + r.shortInstanceName);

        // For b/34123235: Services within the system server won't start until SystemServer
        // does Looper.loop(), so we shouldn't try to start/bind to them too early in the boot
        // process. However, since there's a little point of showing the ANR dialog in that case,
        // let's suppress the timeout until PHASE_THIRD_PARTY_APPS_CAN_START.
        //
        // (Note there are multiple services start at PHASE_THIRD_PARTY_APPS_CAN_START too,
        // which technically could also trigger this timeout if there's a system server
        // that takes a long time to handle PHASE_THIRD_PARTY_APPS_CAN_START, but that shouldn't
        // happen.)
        boolean timeoutNeeded = true;
        if ((mAm.mBootPhase < SystemService.PHASE_THIRD_PARTY_APPS_CAN_START)
                && (r.app != null) && (r.app.getPid() == ActivityManagerService.MY_PID)) {

            Slog.w(TAG, "Too early to start/bind service in system_server: Phase=" + mAm.mBootPhase
                    + " " + r.getComponentName());
            timeoutNeeded = false;
        }

        long now = SystemClock.uptimeMillis();
        ProcessServiceRecord psr;
        if (r.executeNesting == 0) {
            r.executeFg = fg;
            ServiceState stracker = r.getTracker();
            if (stracker != null) {
                stracker.setExecuting(true, mAm.mProcessStats.getMemFactorLocked(), now);
            }
            if (r.app != null) {
                psr = r.app.mServices;
                psr.startExecutingService(r);
                psr.setExecServicesFg(psr.shouldExecServicesFg() || fg);
                if (timeoutNeeded && psr.numberOfExecutingServices() == 1) {
                    scheduleServiceTimeoutLocked(r.app);
                }
            }
        } else if (r.app != null && fg) {
            psr = r.app.mServices;
            if (!psr.shouldExecServicesFg()) {
                psr.setExecServicesFg(true);
                if (timeoutNeeded) {
                    scheduleServiceTimeoutLocked(r.app);
                }
            }
        }
        r.executeFg |= fg;
        r.executeNesting++;
        r.executingStart = now;
    }

    private final boolean requestServiceBindingLocked(ServiceRecord r, IntentBindRecord i,
            boolean execInFg, boolean rebind) throws TransactionTooLargeException {
        if (r.app == null || r.app.getThread() == null) {
            // If service is not currently running, can't yet bind.
            return false;
        }
        if (DEBUG_SERVICE) Slog.d(TAG_SERVICE, "requestBind " + i + ": requested=" + i.requested
                + " rebind=" + rebind);
        if ((!i.requested || rebind) && i.apps.size() > 0) {
            try {
                bumpServiceExecutingLocked(r, execInFg, "bind");
                r.app.mState.forceProcessStateUpTo(ActivityManager.PROCESS_STATE_SERVICE);
                r.app.getThread().scheduleBindService(r, i.intent.getIntent(), rebind,
                        r.app.mState.getReportedProcState());
                if (!rebind) {
                    i.requested = true;
                }
                i.hasBound = true;
                i.doRebind = false;
            } catch (TransactionTooLargeException e) {
                // Keep the executeNesting count accurate.
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Crashed while binding " + r, e);
                final boolean inDestroying = mDestroyingServices.contains(r);
                serviceDoneExecutingLocked(r, inDestroying, inDestroying, false);
                throw e;
            } catch (RemoteException e) {
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Crashed while binding " + r);
                // Keep the executeNesting count accurate.
                final boolean inDestroying = mDestroyingServices.contains(r);
                serviceDoneExecutingLocked(r, inDestroying, inDestroying, false);
                return false;
            }
        }
        return true;
    }

    /** @return {@code true} if the restart is scheduled. */
    private final boolean scheduleServiceRestartLocked(ServiceRecord r, boolean allowCancel) {
        if (mAm.mAtmInternal.isShuttingDown()) {
            Slog.w(TAG, "Not scheduling restart of crashed service " + r.shortInstanceName
                    + " - system is shutting down");
            return false;
        }

        ServiceMap smap = getServiceMapLocked(r.userId);
        if (smap.mServicesByInstanceName.get(r.instanceName) != r) {
            ServiceRecord cur = smap.mServicesByInstanceName.get(r.instanceName);
            Slog.wtf(TAG, "Attempting to schedule restart of " + r
                    + " when found in map: " + cur);
            return false;
        }

        final long now = SystemClock.uptimeMillis();

        final String reason;
        if ((r.serviceInfo.applicationInfo.flags
                &ApplicationInfo.FLAG_PERSISTENT) == 0) {
            long minDuration = mAm.mConstants.SERVICE_RESTART_DURATION;
            long resetTime = mAm.mConstants.SERVICE_RESET_RUN_DURATION;
            boolean canceled = false;

            // Any delivered but not yet finished starts should be put back
            // on the pending list.
            final int N = r.deliveredStarts.size();
            if (N > 0) {
                for (int i=N-1; i>=0; i--) {
                    ServiceRecord.StartItem si = r.deliveredStarts.get(i);
                    si.removeUriPermissionsLocked();
                    if (si.intent == null) {
                        // We'll generate this again if needed.
                    } else if (!allowCancel || (si.deliveryCount < ServiceRecord.MAX_DELIVERY_COUNT
                            && si.doneExecutingCount < ServiceRecord.MAX_DONE_EXECUTING_COUNT)) {
                        r.pendingStarts.add(0, si);
                        long dur = SystemClock.uptimeMillis() - si.deliveredTime;
                        dur *= 2;
                        if (minDuration < dur) minDuration = dur;
                        if (resetTime < dur) resetTime = dur;
                    } else {
                        Slog.w(TAG, "Canceling start item " + si.intent + " in service "
                                + r.shortInstanceName);
                        canceled = true;
                    }
                }
                r.deliveredStarts.clear();
            }

            if (allowCancel) {
                final boolean shouldStop = r.canStopIfKilled(canceled);
                if (shouldStop && !r.hasAutoCreateConnections()) {
                    // Nothing to restart.
                    return false;
                }
                reason = (r.startRequested && !shouldStop) ? "start-requested" : "connection";
            } else {
                reason = "always";
            }

            r.totalRestartCount++;
            if (r.restartDelay == 0) {
                r.restartCount++;
                r.restartDelay = minDuration;
            } else if (r.crashCount > 1) {
                r.restartDelay = mAm.mConstants.BOUND_SERVICE_CRASH_RESTART_DURATION
                        * (r.crashCount - 1);
            } else {
                // If it has been a "reasonably long time" since the service
                // was started, then reset our restart duration back to
                // the beginning, so we don't infinitely increase the duration
                // on a service that just occasionally gets killed (which is
                // a normal case, due to process being killed to reclaim memory).
                if (now > (r.restartTime+resetTime)) {
                    r.restartCount = 1;
                    r.restartDelay = minDuration;
                } else {
                    r.restartDelay *= mAm.mConstants.SERVICE_RESTART_DURATION_FACTOR;
                    if (r.restartDelay < minDuration) {
                        r.restartDelay = minDuration;
                    }
                }
            }

            if (isServiceRestartBackoffEnabledLocked(r.packageName)) {
                r.nextRestartTime = now + r.restartDelay;

                // Make sure that we don't end up restarting a bunch of services
                // all at the same time.
                boolean repeat;
                final long restartTimeBetween = mAm.mConstants.SERVICE_MIN_RESTART_TIME_BETWEEN;
                do {
                    repeat = false;
                    for (int i = mRestartingServices.size() - 1; i >= 0; i--) {
                        final ServiceRecord r2 = mRestartingServices.get(i);
                        if (r2 != r
                                && r.nextRestartTime >= (r2.nextRestartTime - restartTimeBetween)
                                && r.nextRestartTime < (r2.nextRestartTime + restartTimeBetween)) {
                            r.nextRestartTime = r2.nextRestartTime + restartTimeBetween;
                            r.restartDelay = r.nextRestartTime - now;
                            repeat = true;
                            break;
                        }
                    }
                } while (repeat);
            } else {
                // It's been forced to ignore the restart backoff, fix the delay here.
                r.restartDelay = mAm.mConstants.SERVICE_RESTART_DURATION;
                r.nextRestartTime = now + r.restartDelay;
            }

        } else {
            // Persistent processes are immediately restarted, so there is no
            // reason to hold of on restarting their services.
            r.totalRestartCount++;
            r.restartCount = 0;
            r.restartDelay = 0;
            r.nextRestartTime = now;
            reason = "persistent";
        }

        if (!mRestartingServices.contains(r)) {
            r.createdFromFg = false;
            mRestartingServices.add(r);
            r.makeRestarting(mAm.mProcessStats.getMemFactorLocked(), now);
        }

        cancelForegroundNotificationLocked(r);

        performScheduleRestartLocked(r, "Scheduling", reason, SystemClock.uptimeMillis());

        return true;
    }

    @VisibleForTesting
    @GuardedBy("mAm")
    void performScheduleRestartLocked(ServiceRecord r, @NonNull String scheduling,
            @NonNull String reason, @UptimeMillisLong long now) {
        mAm.mHandler.removeCallbacks(r.restarter);
        mAm.mHandler.postAtTime(r.restarter, r.nextRestartTime);
        r.nextRestartTime = now + r.restartDelay;
        Slog.w(TAG, scheduling + " restart of crashed service "
                + r.shortInstanceName + " in " + r.restartDelay + "ms for " + reason);
        EventLog.writeEvent(EventLogTags.AM_SCHEDULE_SERVICE_RESTART,
                r.userId, r.shortInstanceName, r.restartDelay);
    }

    final void performServiceRestartLocked(ServiceRecord r) {
        if (!mRestartingServices.contains(r)) {
            return;
        }
        if (!isServiceNeededLocked(r, false, false)) {
            // Paranoia: is this service actually needed?  In theory a service that is not
            // needed should never remain on the restart list.  In practice...  well, there
            // have been bugs where this happens, and bad things happen because the process
            // ends up just being cached, so quickly killed, then restarted again and again.
            // Let's not let that happen.
            Slog.wtf(TAG, "Restarting service that is not needed: " + r);
            return;
        }
        try {
            bringUpServiceLocked(r, r.intent.getIntent().getFlags(), r.createdFromFg, true, false,
                    false, true);
        } catch (TransactionTooLargeException e) {
            // Ignore, it's been logged and nothing upstack cares.
        } finally {
            /* Will be a no-op if nothing pending */
            mAm.updateOomAdjPendingTargetsLocked(OomAdjuster.OOM_ADJ_REASON_START_SERVICE);
        }
    }

    private final boolean unscheduleServiceRestartLocked(ServiceRecord r, int callingUid,
            boolean force) {
        if (!force && r.restartDelay == 0) {
            return false;
        }
        // Remove from the restarting list; if the service is currently on the
        // restarting list, or the call is coming from another app, then this
        // service has become of much more interest so we reset the restart interval.
        boolean removed = mRestartingServices.remove(r);
        if (removed || callingUid != r.appInfo.uid) {
            r.resetRestartCounter();
        }
        if (removed) {
            clearRestartingIfNeededLocked(r);
        }
        mAm.mHandler.removeCallbacks(r.restarter);
        return true;
    }

    private void clearRestartingIfNeededLocked(ServiceRecord r) {
        if (r.restartTracker != null) {
            // If this is the last restarting record with this tracker, then clear
            // the tracker's restarting state.
            boolean stillTracking = false;
            for (int i=mRestartingServices.size()-1; i>=0; i--) {
                if (mRestartingServices.get(i).restartTracker == r.restartTracker) {
                    stillTracking = true;
                    break;
                }
            }
            if (!stillTracking) {
                r.restartTracker.setRestarting(false, mAm.mProcessStats.getMemFactorLocked(),
                        SystemClock.uptimeMillis());
                r.restartTracker = null;
            }
        }
    }

    /**
     * Toggle service restart backoff policy, used by {@link ActivityManagerShellCommand}.
     */
    @GuardedBy("mAm")
    void setServiceRestartBackoffEnabledLocked(@NonNull String packageName, boolean enable,
            @NonNull String reason) {
        if (!enable) {
            if (mRestartBackoffDisabledPackages.contains(packageName)) {
                // Already disabled, do nothing.
                return;
            }
            mRestartBackoffDisabledPackages.add(packageName);

            final long now = SystemClock.uptimeMillis();
            for (int i = 0, size = mRestartingServices.size(); i < size; i++) {
                final ServiceRecord r = mRestartingServices.get(i);
                if (TextUtils.equals(r.packageName, packageName)) {
                    final long remaining = r.nextRestartTime - now;
                    if (remaining > mAm.mConstants.SERVICE_RESTART_DURATION) {
                        r.restartDelay = mAm.mConstants.SERVICE_RESTART_DURATION;
                        r.nextRestartTime = now + r.restartDelay;
                        performScheduleRestartLocked(r, "Rescheduling", reason, now);
                    }
                }
            }
        } else {
            removeServiceRestartBackoffEnabledLocked(packageName);
            // For the simplicity, we are not going to reschedule its pending restarts
            // when we turn the backoff policy back on.
        }
    }

    @GuardedBy("mAm")
    private void removeServiceRestartBackoffEnabledLocked(@NonNull String packageName) {
        mRestartBackoffDisabledPackages.remove(packageName);
    }

    /**
     * @return {@code false} if the given package has been disable from enforcing the service
     * restart backoff policy, used by {@link ActivityManagerShellCommand}.
     */
    @GuardedBy("mAm")
    boolean isServiceRestartBackoffEnabledLocked(@NonNull String packageName) {
        return !mRestartBackoffDisabledPackages.contains(packageName);
    }

    private String bringUpServiceLocked(ServiceRecord r, int intentFlags, boolean execInFg,
            boolean whileRestarting, boolean permissionsReviewRequired, boolean packageFrozen,
            boolean enqueueOomAdj)
            throws TransactionTooLargeException {
        if (r.app != null && r.app.getThread() != null) {
            sendServiceArgsLocked(r, execInFg, false);
            return null;
        }

        if (!whileRestarting && mRestartingServices.contains(r)) {
            // If waiting for a restart, then do nothing.
            return null;
        }

        if (DEBUG_SERVICE) {
            Slog.v(TAG_SERVICE, "Bringing up " + r + " " + r.intent + " fg=" + r.fgRequired);
        }

        // We are now bringing the service up, so no longer in the
        // restarting state.
        if (mRestartingServices.remove(r)) {
            clearRestartingIfNeededLocked(r);
        }

        // Make sure this service is no longer considered delayed, we are starting it now.
        if (r.delayed) {
            if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "REM FR DELAY LIST (bring up): " + r);
            getServiceMapLocked(r.userId).mDelayedStartList.remove(r);
            r.delayed = false;
        }

        // Make sure that the user who owns this service is started.  If not,
        // we don't want to allow it to run.
        if (!mAm.mUserController.hasStartedUserState(r.userId)) {
            String msg = "Unable to launch app "
                    + r.appInfo.packageName + "/"
                    + r.appInfo.uid + " for service "
                    + r.intent.getIntent() + ": user " + r.userId + " is stopped";
            Slog.w(TAG, msg);
            bringDownServiceLocked(r, enqueueOomAdj);
            return msg;
        }

        // Report usage if binding is from a different package except for explicitly exempted
        // bindings
        if (!r.appInfo.packageName.equals(r.mRecentCallingPackage)
                && !r.isNotAppComponentUsage) {
            mAm.mUsageStatsService.reportEvent(
                    r.packageName, r.userId, UsageEvents.Event.APP_COMPONENT_USED);
        }

        // Service is now being launched, its package can't be stopped.
        try {
            AppGlobals.getPackageManager().setPackageStoppedState(
                    r.packageName, false, r.userId);
        } catch (RemoteException e) {
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Failed trying to unstop package "
                    + r.packageName + ": " + e);
        }

        final boolean isolated = (r.serviceInfo.flags&ServiceInfo.FLAG_ISOLATED_PROCESS) != 0;
        final String procName = r.processName;
        HostingRecord hostingRecord = new HostingRecord("service", r.instanceName);
        ProcessRecord app;

        if (!isolated) {
            app = mAm.getProcessRecordLocked(procName, r.appInfo.uid);
            if (DEBUG_MU) Slog.v(TAG_MU, "bringUpServiceLocked: appInfo.uid=" + r.appInfo.uid
                        + " app=" + app);
            if (app != null) {
                final IApplicationThread thread = app.getThread();
                final int pid = app.getPid();
                final UidRecord uidRecord = app.getUidRecord();
                if (thread != null) {
                    try {
                        app.addPackage(r.appInfo.packageName, r.appInfo.longVersionCode,
                                mAm.mProcessStats);
                        realStartServiceLocked(r, app, thread, pid, uidRecord, execInFg,
                                enqueueOomAdj);
                        return null;
                    } catch (TransactionTooLargeException e) {
                        throw e;
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Exception when starting service " + r.shortInstanceName, e);
                    }

                    // If a dead object exception was thrown -- fall through to
                    // restart the application.
                }
            }
        } else {
            // If this service runs in an isolated process, then each time
            // we call startProcessLocked() we will get a new isolated
            // process, starting another process if we are currently waiting
            // for a previous process to come up.  To deal with this, we store
            // in the service any current isolated process it is running in or
            // waiting to have come up.
            app = r.isolatedProc;
            if (WebViewZygote.isMultiprocessEnabled()
                    && r.serviceInfo.packageName.equals(WebViewZygote.getPackageName())) {
                hostingRecord = HostingRecord.byWebviewZygote(r.instanceName);
            }
            if ((r.serviceInfo.flags & ServiceInfo.FLAG_USE_APP_ZYGOTE) != 0) {
                hostingRecord = HostingRecord.byAppZygote(r.instanceName, r.definingPackageName,
                        r.definingUid);
            }
        }

        // Not running -- get it started, and enqueue this service record
        // to be executed when the app comes up.
        if (app == null && !permissionsReviewRequired && !packageFrozen) {
            // TODO (chriswailes): Change the Zygote policy flags based on if the launch-for-service
            //  was initiated from a notification tap or not.
            if ((app = mAm.startProcessLocked(procName, r.appInfo, true, intentFlags,
                        hostingRecord, ZYGOTE_POLICY_FLAG_EMPTY, false, isolated)) == null) {
                String msg = "Unable to launch app "
                        + r.appInfo.packageName + "/"
                        + r.appInfo.uid + " for service "
                        + r.intent.getIntent() + ": process is bad";
                Slog.w(TAG, msg);
                bringDownServiceLocked(r, enqueueOomAdj);
                return msg;
            }
            if (isolated) {
                r.isolatedProc = app;
            }
        }

        if (r.fgRequired) {
            if (DEBUG_FOREGROUND_SERVICE) {
                Slog.v(TAG, "Allowlisting " + UserHandle.formatUid(r.appInfo.uid)
                        + " for fg-service launch");
            }
            mAm.tempAllowlistUidLocked(r.appInfo.uid,
                    SERVICE_START_FOREGROUND_TIMEOUT, REASON_SERVICE_LAUNCH,
                    "fg-service-launch",
                    TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                    r.mRecentCallingUid);
        }

        if (!mPendingServices.contains(r)) {
            mPendingServices.add(r);
        }

        if (r.delayedStop) {
            // Oh and hey we've already been asked to stop!
            r.delayedStop = false;
            if (r.startRequested) {
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE,
                        "Applying delayed stop (in bring up): " + r);
                stopServiceLocked(r, enqueueOomAdj);
            }
        }

        return null;
    }

    private final void requestServiceBindingsLocked(ServiceRecord r, boolean execInFg)
            throws TransactionTooLargeException {
        for (int i=r.bindings.size()-1; i>=0; i--) {
            IntentBindRecord ibr = r.bindings.valueAt(i);
            if (!requestServiceBindingLocked(r, ibr, execInFg, false)) {
                break;
            }
        }
    }

    /**
     * Note the name of this method should not be confused with the started services concept.
     * The "start" here means bring up the instance in the client, and this method is called
     * from bindService() as well.
     */
    private void realStartServiceLocked(ServiceRecord r, ProcessRecord app,
            IApplicationThread thread, int pid, UidRecord uidRecord, boolean execInFg,
            boolean enqueueOomAdj) throws RemoteException {
        if (thread == null) {
            throw new RemoteException();
        }
        if (DEBUG_MU)
            Slog.v(TAG_MU, "realStartServiceLocked, ServiceRecord.uid = " + r.appInfo.uid
                    + ", ProcessRecord.uid = " + app.uid);
        r.setProcess(app, thread, pid, uidRecord);
        r.restartTime = r.lastActivity = SystemClock.uptimeMillis();

        final ProcessServiceRecord psr = app.mServices;
        final boolean newService = psr.startService(r);
        bumpServiceExecutingLocked(r, execInFg, "create");
        mAm.updateLruProcessLocked(app, false, null);
        updateServiceForegroundLocked(psr, /* oomAdj= */ false);
        if (enqueueOomAdj) {
            mAm.enqueueOomAdjTargetLocked(app);
        } else {
            mAm.updateOomAdjLocked(app, OomAdjuster.OOM_ADJ_REASON_START_SERVICE);
        }

        boolean created = false;
        try {
            if (LOG_SERVICE_START_STOP) {
                String nameTerm;
                int lastPeriod = r.shortInstanceName.lastIndexOf('.');
                nameTerm = lastPeriod >= 0 ? r.shortInstanceName.substring(lastPeriod)
                        : r.shortInstanceName;
                EventLogTags.writeAmCreateService(
                        r.userId, System.identityHashCode(r), nameTerm, r.app.uid, pid);
            }

            final int uid = r.appInfo.uid;
            final String packageName = r.name.getPackageName();
            final String serviceName = r.name.getClassName();
            FrameworkStatsLog.write(FrameworkStatsLog.SERVICE_LAUNCH_REPORTED, uid, packageName,
                    serviceName);
            mAm.mBatteryStatsService.noteServiceStartLaunch(uid, packageName, serviceName);
            mAm.notifyPackageUse(r.serviceInfo.packageName,
                                 PackageManager.NOTIFY_PACKAGE_USE_SERVICE);
            app.mState.forceProcessStateUpTo(ActivityManager.PROCESS_STATE_SERVICE);
            thread.scheduleCreateService(r, r.serviceInfo,
                    mAm.compatibilityInfoForPackage(r.serviceInfo.applicationInfo),
                    app.mState.getReportedProcState());
            r.postNotification();
            created = true;
        } catch (DeadObjectException e) {
            Slog.w(TAG, "Application dead when creating service " + r);
            mAm.appDiedLocked(app, "Died when creating service");
            throw e;
        } finally {
            if (!created) {
                // Keep the executeNesting count accurate.
                final boolean inDestroying = mDestroyingServices.contains(r);
                serviceDoneExecutingLocked(r, inDestroying, inDestroying, false);

                // Cleanup.
                if (newService) {
                    psr.stopService(r);
                    r.setProcess(null, null, 0, null);
                }

                // Retry.
                if (!inDestroying) {
                    scheduleServiceRestartLocked(r, false);
                }
            }
        }

        if (r.allowlistManager) {
            psr.mAllowlistManager = true;
        }

        requestServiceBindingsLocked(r, execInFg);

        updateServiceClientActivitiesLocked(psr, null, true);

        if (newService && created) {
            psr.addBoundClientUidsOfNewService(r);
        }

        // If the service is in the started state, and there are no
        // pending arguments, then fake up one so its onStartCommand() will
        // be called.
        if (r.startRequested && r.callStart && r.pendingStarts.size() == 0) {
            r.pendingStarts.add(new ServiceRecord.StartItem(r, false, r.makeNextStartId(),
                    null, null, 0));
        }

        sendServiceArgsLocked(r, execInFg, true);

        if (r.delayed) {
            if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "REM FR DELAY LIST (new proc): " + r);
            getServiceMapLocked(r.userId).mDelayedStartList.remove(r);
            r.delayed = false;
        }

        if (r.delayedStop) {
            // Oh and hey we've already been asked to stop!
            r.delayedStop = false;
            if (r.startRequested) {
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE,
                        "Applying delayed stop (from start): " + r);
                stopServiceLocked(r, enqueueOomAdj);
            }
        }
    }

    private final void sendServiceArgsLocked(ServiceRecord r, boolean execInFg,
            boolean oomAdjusted) throws TransactionTooLargeException {
        final int N = r.pendingStarts.size();
        if (N == 0) {
            return;
        }

        ArrayList<ServiceStartArgs> args = new ArrayList<>();

        while (r.pendingStarts.size() > 0) {
            ServiceRecord.StartItem si = r.pendingStarts.remove(0);
            if (DEBUG_SERVICE) {
                Slog.v(TAG_SERVICE, "Sending arguments to: "
                        + r + " " + r.intent + " args=" + si.intent);
            }
            if (si.intent == null && N > 1) {
                // If somehow we got a dummy null intent in the middle,
                // then skip it.  DO NOT skip a null intent when it is
                // the only one in the list -- this is to support the
                // onStartCommand(null) case.
                continue;
            }
            si.deliveredTime = SystemClock.uptimeMillis();
            r.deliveredStarts.add(si);
            si.deliveryCount++;
            if (si.neededGrants != null) {
                mAm.mUgmInternal.grantUriPermissionUncheckedFromIntent(si.neededGrants,
                        si.getUriPermissionsLocked());
            }
            mAm.grantImplicitAccess(r.userId, si.intent, si.callingId,
                    UserHandle.getAppId(r.appInfo.uid)
            );
            bumpServiceExecutingLocked(r, execInFg, "start");
            if (!oomAdjusted) {
                oomAdjusted = true;
                mAm.updateOomAdjLocked(r.app, OomAdjuster.OOM_ADJ_REASON_START_SERVICE);
            }
            if (r.fgRequired && !r.fgWaiting) {
                if (!r.isForeground) {
                    if (DEBUG_BACKGROUND_CHECK) {
                        Slog.i(TAG, "Launched service must call startForeground() within timeout: " + r);
                    }
                    scheduleServiceForegroundTransitionTimeoutLocked(r);
                } else {
                    if (DEBUG_BACKGROUND_CHECK) {
                        Slog.i(TAG, "Service already foreground; no new timeout: " + r);
                    }
                    r.fgRequired = false;
                }
            }
            int flags = 0;
            if (si.deliveryCount > 1) {
                flags |= Service.START_FLAG_RETRY;
            }
            if (si.doneExecutingCount > 0) {
                flags |= Service.START_FLAG_REDELIVERY;
            }
            args.add(new ServiceStartArgs(si.taskRemoved, si.id, flags, si.intent));
        }

        ParceledListSlice<ServiceStartArgs> slice = new ParceledListSlice<>(args);
        slice.setInlineCountLimit(4);
        Exception caughtException = null;
        try {
            r.app.getThread().scheduleServiceArgs(r, slice);
        } catch (TransactionTooLargeException e) {
            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Transaction too large for " + args.size()
                    + " args, first: " + args.get(0).args);
            Slog.w(TAG, "Failed delivering service starts", e);
            caughtException = e;
        } catch (RemoteException e) {
            // Remote process gone...  we'll let the normal cleanup take care of this.
            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Crashed while sending args: " + r);
            Slog.w(TAG, "Failed delivering service starts", e);
            caughtException = e;
        } catch (Exception e) {
            Slog.w(TAG, "Unexpected exception", e);
            caughtException = e;
        }

        if (caughtException != null) {
            // Keep nesting count correct
            final boolean inDestroying = mDestroyingServices.contains(r);
            for (int i = 0, size = args.size(); i < size; i++) {
                serviceDoneExecutingLocked(r, inDestroying, inDestroying, true);
            }
            /* Will be a no-op if nothing pending */
            mAm.updateOomAdjPendingTargetsLocked(OomAdjuster.OOM_ADJ_REASON_UNBIND_SERVICE);
            if (caughtException instanceof TransactionTooLargeException) {
                throw (TransactionTooLargeException)caughtException;
            }
        }
    }

    private final boolean isServiceNeededLocked(ServiceRecord r, boolean knowConn,
            boolean hasConn) {
        // Are we still explicitly being asked to run?
        if (r.startRequested) {
            return true;
        }

        // Is someone still bound to us keeping us running?
        if (!knowConn) {
            hasConn = r.hasAutoCreateConnections();
        }
        if (hasConn) {
            return true;
        }

        return false;
    }

    private final void bringDownServiceIfNeededLocked(ServiceRecord r, boolean knowConn,
            boolean hasConn, boolean enqueueOomAdj) {
        //Slog.i(TAG, "Bring down service:");
        //r.dump("  ");

        if (isServiceNeededLocked(r, knowConn, hasConn)) {
            return;
        }

        // Are we in the process of launching?
        if (mPendingServices.contains(r)) {
            return;
        }

        bringDownServiceLocked(r, enqueueOomAdj);
    }

    private void bringDownServiceLocked(ServiceRecord r, boolean enqueueOomAdj) {
        //Slog.i(TAG, "Bring down service:");
        //r.dump("  ");

        // Report to all of the connections that the service is no longer
        // available.
        ArrayMap<IBinder, ArrayList<ConnectionRecord>> connections = r.getConnections();
        for (int conni = connections.size() - 1; conni >= 0; conni--) {
            ArrayList<ConnectionRecord> c = connections.valueAt(conni);
            for (int i=0; i<c.size(); i++) {
                ConnectionRecord cr = c.get(i);
                // There is still a connection to the service that is
                // being brought down.  Mark it as dead.
                cr.serviceDead = true;
                cr.stopAssociation();
                try {
                    cr.conn.connected(r.name, null, true);
                } catch (Exception e) {
                    Slog.w(TAG, "Failure disconnecting service " + r.shortInstanceName
                          + " to connection " + c.get(i).conn.asBinder()
                          + " (in " + c.get(i).binding.client.processName + ")", e);
                }
            }
        }

        boolean needOomAdj = false;
        // Tell the service that it has been unbound.
        if (r.app != null && r.app.getThread() != null) {
            for (int i = r.bindings.size() - 1; i >= 0; i--) {
                IntentBindRecord ibr = r.bindings.valueAt(i);
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Bringing down binding " + ibr
                        + ": hasBound=" + ibr.hasBound);
                if (ibr.hasBound) {
                    try {
                        bumpServiceExecutingLocked(r, false, "bring down unbind");
                        needOomAdj = true;
                        ibr.hasBound = false;
                        ibr.requested = false;
                        r.app.getThread().scheduleUnbindService(r,
                                ibr.intent.getIntent());
                    } catch (Exception e) {
                        Slog.w(TAG, "Exception when unbinding service "
                                + r.shortInstanceName, e);
                        needOomAdj = false;
                        serviceProcessGoneLocked(r, enqueueOomAdj);
                        break;
                    }
                }
            }
        }

        final long now = SystemClock.uptimeMillis();
        // Check to see if the service had been started as foreground, but being
        // brought down before actually showing a notification.  That is not allowed.
        if (r.fgRequired) {
            Slog.w(TAG_SERVICE, "Bringing down service while still waiting for start foreground: "
                    + r);
            r.fgRequired = false;
            r.fgWaiting = false;
            ServiceState stracker = r.getTracker();
            if (stracker != null) {
                stracker.setForeground(false, mAm.mProcessStats.getMemFactorLocked(), now);
            }
            mAm.mAppOpsService.finishOperation(AppOpsManager.getToken(mAm.mAppOpsService),
                    AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName, null);
            mAm.mHandler.removeMessages(
                    ActivityManagerService.SERVICE_FOREGROUND_TIMEOUT_MSG, r);
            if (r.app != null) {
                Message msg = mAm.mHandler.obtainMessage(
                        ActivityManagerService.SERVICE_FOREGROUND_CRASH_MSG);
                msg.obj = r.app;
                msg.getData().putCharSequence(
                    ActivityManagerService.SERVICE_RECORD_KEY, r.toString());
                mAm.mHandler.sendMessage(msg);
            }
        }

        if (DEBUG_SERVICE) {
            RuntimeException here = new RuntimeException();
            here.fillInStackTrace();
            Slog.v(TAG_SERVICE, "Bringing down " + r + " " + r.intent, here);
        }
        r.destroyTime = SystemClock.uptimeMillis();
        if (LOG_SERVICE_START_STOP) {
            EventLogTags.writeAmDestroyService(
                    r.userId, System.identityHashCode(r), (r.app != null) ? r.app.getPid() : -1);
        }

        final ServiceMap smap = getServiceMapLocked(r.userId);
        ServiceRecord found = smap.mServicesByInstanceName.remove(r.instanceName);

        // Note when this method is called by bringUpServiceLocked(), the service is not found
        // in mServicesByInstanceName and found will be null.
        if (found != null && found != r) {
            // This is not actually the service we think is running...  this should not happen,
            // but if it does, fail hard.
            smap.mServicesByInstanceName.put(r.instanceName, found);
            throw new IllegalStateException("Bringing down " + r + " but actually running "
                    + found);
        }
        smap.mServicesByIntent.remove(r.intent);
        r.totalRestartCount = 0;
        unscheduleServiceRestartLocked(r, 0, true);

        // Also make sure it is not on the pending list.
        for (int i=mPendingServices.size()-1; i>=0; i--) {
            if (mPendingServices.get(i) == r) {
                mPendingServices.remove(i);
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Removed pending: " + r);
            }
        }
        if (mPendingBringups.remove(r) != null) {
            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Removed pending bringup: " + r);
        }

        cancelForegroundNotificationLocked(r);
        if (r.isForeground) {
            decActiveForegroundAppLocked(smap, r);
            ServiceState stracker = r.getTracker();
            if (stracker != null) {
                stracker.setForeground(false, mAm.mProcessStats.getMemFactorLocked(), now);
            }
            mAm.mAppOpsService.finishOperation(
                    AppOpsManager.getToken(mAm.mAppOpsService),
                    AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName, null);
            unregisterAppOpCallbackLocked(r);
            r.mFgsExitTime = SystemClock.uptimeMillis();
            logFGSStateChangeLocked(r,
                    FrameworkStatsLog.FOREGROUND_SERVICE_STATE_CHANGED__STATE__EXIT,
                    r.mFgsExitTime > r.mFgsEnterTime
                            ? (int)(r.mFgsExitTime - r.mFgsEnterTime) : 0);
            mAm.updateForegroundServiceUsageStats(r.name, r.userId, false);
        }

        r.isForeground = false;
        r.foregroundId = 0;
        r.foregroundNoti = null;
        resetFgsRestrictionLocked(r);

        // Clear start entries.
        r.clearDeliveredStartsLocked();
        r.pendingStarts.clear();
        smap.mDelayedStartList.remove(r);

        if (r.app != null) {
            mAm.mBatteryStatsService.noteServiceStopLaunch(r.appInfo.uid, r.name.getPackageName(),
                    r.name.getClassName());
            stopServiceAndUpdateAllowlistManagerLocked(r);
            if (r.app.getThread() != null) {
                // Bump the process to the top of LRU list
                mAm.updateLruProcessLocked(r.app, false, null);
                updateServiceForegroundLocked(r.app.mServices, false);
                try {
                    bumpServiceExecutingLocked(r, false, "destroy");
                    mDestroyingServices.add(r);
                    r.destroying = true;
                    needOomAdj = true;
                    r.app.getThread().scheduleStopService(r);
                } catch (Exception e) {
                    Slog.w(TAG, "Exception when destroying service "
                            + r.shortInstanceName, e);
                    serviceProcessGoneLocked(r, enqueueOomAdj);
                }
            } else {
                if (DEBUG_SERVICE) Slog.v(
                    TAG_SERVICE, "Removed service that has no process: " + r);
            }
        } else {
            if (DEBUG_SERVICE) Slog.v(
                TAG_SERVICE, "Removed service that is not running: " + r);
        }

        if (needOomAdj) {
            if (enqueueOomAdj) {
                mAm.enqueueOomAdjTargetLocked(r.app);
            } else {
                mAm.updateOomAdjLocked(r.app, OomAdjuster.OOM_ADJ_REASON_UNBIND_SERVICE);
            }
        }
        if (r.bindings.size() > 0) {
            r.bindings.clear();
        }

        if (r.restarter instanceof ServiceRestarter) {
           ((ServiceRestarter)r.restarter).setService(null);
        }

        int memFactor = mAm.mProcessStats.getMemFactorLocked();
        if (r.tracker != null) {
            r.tracker.setStarted(false, memFactor, now);
            r.tracker.setBound(false, memFactor, now);
            if (r.executeNesting == 0) {
                r.tracker.clearCurrentOwner(r, false);
                r.tracker = null;
            }
        }

        smap.ensureNotStartingBackgroundLocked(r);
    }

    void removeConnectionLocked(ConnectionRecord c, ProcessRecord skipApp,
            ActivityServiceConnectionsHolder skipAct, boolean enqueueOomAdj) {
        IBinder binder = c.conn.asBinder();
        AppBindRecord b = c.binding;
        ServiceRecord s = b.service;
        ArrayList<ConnectionRecord> clist = s.getConnections().get(binder);
        if (clist != null) {
            clist.remove(c);
            if (clist.size() == 0) {
                s.removeConnection(binder);
            }
        }
        b.connections.remove(c);
        c.stopAssociation();
        if (c.activity != null && c.activity != skipAct) {
            c.activity.removeConnection(c);
        }
        if (b.client != skipApp) {
            final ProcessServiceRecord psr = b.client.mServices;
            psr.removeConnection(c);
            if ((c.flags&Context.BIND_ABOVE_CLIENT) != 0) {
                psr.updateHasAboveClientLocked();
            }
            // If this connection requested allowlist management, see if we should
            // now clear that state.
            if ((c.flags&Context.BIND_ALLOW_WHITELIST_MANAGEMENT) != 0) {
                s.updateAllowlistManager();
                if (!s.allowlistManager && s.app != null) {
                    updateAllowlistManagerLocked(s.app.mServices);
                }
            }
            // And do the same for bg activity starts ability.
            if ((c.flags & Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS) != 0) {
                s.updateIsAllowedBgActivityStartsByBinding();
            }
            if (s.app != null) {
                updateServiceClientActivitiesLocked(s.app.mServices, c, true);
            }
        }
        clist = mServiceConnections.get(binder);
        if (clist != null) {
            clist.remove(c);
            if (clist.size() == 0) {
                mServiceConnections.remove(binder);
            }
        }

        mAm.stopAssociationLocked(b.client.uid, b.client.processName, s.appInfo.uid,
                s.appInfo.longVersionCode, s.instanceName, s.processName);

        if (b.connections.size() == 0) {
            b.intent.apps.remove(b.client);
        }

        if (!c.serviceDead) {
            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Disconnecting binding " + b.intent
                    + ": shouldUnbind=" + b.intent.hasBound);
            if (s.app != null && s.app.getThread() != null && b.intent.apps.size() == 0
                    && b.intent.hasBound) {
                try {
                    bumpServiceExecutingLocked(s, false, "unbind");
                    if (b.client != s.app && (c.flags&Context.BIND_WAIVE_PRIORITY) == 0
                            && s.app.mState.getSetProcState() <= PROCESS_STATE_HEAVY_WEIGHT) {
                        // If this service's process is not already in the cached list,
                        // then update it in the LRU list here because this may be causing
                        // it to go down there and we want it to start out near the top.
                        mAm.updateLruProcessLocked(s.app, false, null);
                    }
                    if (enqueueOomAdj) {
                        mAm.enqueueOomAdjTargetLocked(s.app);
                    } else {
                        mAm.updateOomAdjLocked(s.app, OomAdjuster.OOM_ADJ_REASON_UNBIND_SERVICE);
                    }
                    b.intent.hasBound = false;
                    // Assume the client doesn't want to know about a rebind;
                    // we will deal with that later if it asks for one.
                    b.intent.doRebind = false;
                    s.app.getThread().scheduleUnbindService(s, b.intent.intent.getIntent());
                } catch (Exception e) {
                    Slog.w(TAG, "Exception when unbinding service " + s.shortInstanceName, e);
                    serviceProcessGoneLocked(s, enqueueOomAdj);
                }
            }

            // If unbound while waiting to start and there is no connection left in this service,
            // remove the pending service
            if (s.getConnections().isEmpty()) {
                mPendingServices.remove(s);
                mPendingBringups.remove(s);
            }

            if ((c.flags&Context.BIND_AUTO_CREATE) != 0) {
                boolean hasAutoCreate = s.hasAutoCreateConnections();
                if (!hasAutoCreate) {
                    if (s.tracker != null) {
                        s.tracker.setBound(false, mAm.mProcessStats.getMemFactorLocked(),
                                SystemClock.uptimeMillis());
                    }
                }
                bringDownServiceIfNeededLocked(s, true, hasAutoCreate, enqueueOomAdj);
            }
        }
    }

    void serviceDoneExecutingLocked(ServiceRecord r, int type, int startId, int res,
            boolean enqueueOomAdj) {
        boolean inDestroying = mDestroyingServices.contains(r);
        if (r != null) {
            if (type == ActivityThread.SERVICE_DONE_EXECUTING_START) {
                // This is a call from a service start...  take care of
                // book-keeping.
                r.callStart = true;
                switch (res) {
                    case Service.START_STICKY_COMPATIBILITY:
                    case Service.START_STICKY: {
                        // We are done with the associated start arguments.
                        r.findDeliveredStart(startId, false, true);
                        // Don't stop if killed.
                        r.stopIfKilled = false;
                        break;
                    }
                    case Service.START_NOT_STICKY: {
                        // We are done with the associated start arguments.
                        r.findDeliveredStart(startId, false, true);
                        if (r.getLastStartId() == startId) {
                            // There is no more work, and this service
                            // doesn't want to hang around if killed.
                            r.stopIfKilled = true;
                        }
                        break;
                    }
                    case Service.START_REDELIVER_INTENT: {
                        // We'll keep this item until they explicitly
                        // call stop for it, but keep track of the fact
                        // that it was delivered.
                        ServiceRecord.StartItem si = r.findDeliveredStart(startId, false, false);
                        if (si != null) {
                            si.deliveryCount = 0;
                            si.doneExecutingCount++;
                            // Don't stop if killed.
                            r.stopIfKilled = true;
                        }
                        break;
                    }
                    case Service.START_TASK_REMOVED_COMPLETE: {
                        // Special processing for onTaskRemoved().  Don't
                        // impact normal onStartCommand() processing.
                        r.findDeliveredStart(startId, true, true);
                        break;
                    }
                    default:
                        throw new IllegalArgumentException(
                                "Unknown service start result: " + res);
                }
                if (res == Service.START_STICKY_COMPATIBILITY) {
                    r.callStart = false;
                }
            } else if (type == ActivityThread.SERVICE_DONE_EXECUTING_STOP) {
                // This is the final call from destroying the service...  we should
                // actually be getting rid of the service at this point.  Do some
                // validation of its state, and ensure it will be fully removed.
                if (!inDestroying) {
                    // Not sure what else to do with this...  if it is not actually in the
                    // destroying list, we don't need to make sure to remove it from it.
                    // If the app is null, then it was probably removed because the process died,
                    // otherwise wtf
                    if (r.app != null) {
                        Slog.w(TAG, "Service done with onDestroy, but not inDestroying: "
                                + r + ", app=" + r.app);
                    }
                } else if (r.executeNesting != 1) {
                    Slog.w(TAG, "Service done with onDestroy, but executeNesting="
                            + r.executeNesting + ": " + r);
                    // Fake it to keep from ANR due to orphaned entry.
                    r.executeNesting = 1;
                }
            }
            final long origId = Binder.clearCallingIdentity();
            serviceDoneExecutingLocked(r, inDestroying, inDestroying, enqueueOomAdj);
            Binder.restoreCallingIdentity(origId);
        } else {
            Slog.w(TAG, "Done executing unknown service from pid "
                    + Binder.getCallingPid());
        }
    }

    private void serviceProcessGoneLocked(ServiceRecord r, boolean enqueueOomAdj) {
        if (r.tracker != null) {
            int memFactor = mAm.mProcessStats.getMemFactorLocked();
            long now = SystemClock.uptimeMillis();
            r.tracker.setExecuting(false, memFactor, now);
            r.tracker.setForeground(false, memFactor, now);
            r.tracker.setBound(false, memFactor, now);
            r.tracker.setStarted(false, memFactor, now);
        }
        serviceDoneExecutingLocked(r, true, true, enqueueOomAdj);
    }

    private void serviceDoneExecutingLocked(ServiceRecord r, boolean inDestroying,
            boolean finishing, boolean enqueueOomAdj) {
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "<<< DONE EXECUTING " + r
                + ": nesting=" + r.executeNesting
                + ", inDestroying=" + inDestroying + ", app=" + r.app);
        else if (DEBUG_SERVICE_EXECUTING) Slog.v(TAG_SERVICE_EXECUTING,
                "<<< DONE EXECUTING " + r.shortInstanceName);
        r.executeNesting--;
        if (r.executeNesting <= 0) {
            if (r.app != null) {
                final ProcessServiceRecord psr = r.app.mServices;
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE,
                        "Nesting at 0 of " + r.shortInstanceName);
                psr.setExecServicesFg(false);
                psr.stopExecutingService(r);
                if (psr.numberOfExecutingServices() == 0) {
                    if (DEBUG_SERVICE || DEBUG_SERVICE_EXECUTING) Slog.v(TAG_SERVICE_EXECUTING,
                            "No more executingServices of " + r.shortInstanceName);
                    mAm.mHandler.removeMessages(ActivityManagerService.SERVICE_TIMEOUT_MSG, r.app);
                } else if (r.executeFg) {
                    // Need to re-evaluate whether the app still needs to be in the foreground.
                    for (int i = psr.numberOfExecutingServices() - 1; i >= 0; i--) {
                        if (psr.getExecutingServiceAt(i).executeFg) {
                            psr.setExecServicesFg(true);
                            break;
                        }
                    }
                }
                if (inDestroying) {
                    if (DEBUG_SERVICE) Slog.v(TAG_SERVICE,
                            "doneExecuting remove destroying " + r);
                    mDestroyingServices.remove(r);
                    r.bindings.clear();
                }
                if (enqueueOomAdj) {
                    mAm.enqueueOomAdjTargetLocked(r.app);
                } else {
                    mAm.updateOomAdjLocked(r.app, OomAdjuster.OOM_ADJ_REASON_UNBIND_SERVICE);
                }
            }
            r.executeFg = false;
            if (r.tracker != null) {
                final int memFactor = mAm.mProcessStats.getMemFactorLocked();
                final long now = SystemClock.uptimeMillis();
                r.tracker.setExecuting(false, memFactor, now);
                r.tracker.setForeground(false, memFactor, now);
                if (finishing) {
                    r.tracker.clearCurrentOwner(r, false);
                    r.tracker = null;
                }
            }
            if (finishing) {
                if (r.app != null && !r.app.isPersistent()) {
                    stopServiceAndUpdateAllowlistManagerLocked(r);
                }
                r.setProcess(null, null, 0, null);
            }
        }
    }

    boolean attachApplicationLocked(ProcessRecord proc, String processName)
            throws RemoteException {
        boolean didSomething = false;
        // Collect any services that are waiting for this process to come up.
        if (mPendingServices.size() > 0) {
            ServiceRecord sr = null;
            try {
                for (int i=0; i<mPendingServices.size(); i++) {
                    sr = mPendingServices.get(i);
                    if (proc != sr.isolatedProc && (proc.uid != sr.appInfo.uid
                            || !processName.equals(sr.processName))) {
                        continue;
                    }

                    final IApplicationThread thread = proc.getThread();
                    final int pid = proc.getPid();
                    final UidRecord uidRecord = proc.getUidRecord();
                    mPendingServices.remove(i);
                    i--;
                    proc.addPackage(sr.appInfo.packageName, sr.appInfo.longVersionCode,
                            mAm.mProcessStats);
                    realStartServiceLocked(sr, proc, thread, pid, uidRecord, sr.createdFromFg,
                            true);
                    didSomething = true;
                    if (!isServiceNeededLocked(sr, false, false)) {
                        // We were waiting for this service to start, but it is actually no
                        // longer needed.  This could happen because bringDownServiceIfNeeded
                        // won't bring down a service that is pending...  so now the pending
                        // is done, so let's drop it.
                        bringDownServiceLocked(sr, true);
                    }
                    /* Will be a no-op if nothing pending */
                    mAm.updateOomAdjPendingTargetsLocked(OomAdjuster.OOM_ADJ_REASON_START_SERVICE);
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Exception in new application when starting service "
                        + sr.shortInstanceName, e);
                throw e;
            }
        }
        // Also, if there are any services that are waiting to restart and
        // would run in this process, now is a good time to start them.  It would
        // be weird to bring up the process but arbitrarily not let the services
        // run at this point just because their restart time hasn't come up.
        if (mRestartingServices.size() > 0) {
            ServiceRecord sr;
            for (int i=0; i<mRestartingServices.size(); i++) {
                sr = mRestartingServices.get(i);
                if (proc != sr.isolatedProc && (proc.uid != sr.appInfo.uid
                        || !processName.equals(sr.processName))) {
                    continue;
                }
                mAm.mHandler.removeCallbacks(sr.restarter);
                mAm.mHandler.post(sr.restarter);
            }
        }
        return didSomething;
    }

    void processStartTimedOutLocked(ProcessRecord proc) {
        boolean needOomAdj = false;
        for (int i = 0, size = mPendingServices.size(); i < size; i++) {
            ServiceRecord sr = mPendingServices.get(i);
            if ((proc.uid == sr.appInfo.uid
                    && proc.processName.equals(sr.processName))
                    || sr.isolatedProc == proc) {
                Slog.w(TAG, "Forcing bringing down service: " + sr);
                sr.isolatedProc = null;
                mPendingServices.remove(i);
                size = mPendingServices.size();
                i--;
                needOomAdj = true;
                bringDownServiceLocked(sr, true);
            }
        }
        if (needOomAdj) {
            mAm.updateOomAdjPendingTargetsLocked(OomAdjuster.OOM_ADJ_REASON_UNBIND_SERVICE);
        }
    }

    private boolean collectPackageServicesLocked(String packageName, Set<String> filterByClasses,
            boolean evenPersistent, boolean doit, ArrayMap<ComponentName, ServiceRecord> services) {
        boolean didSomething = false;
        for (int i = services.size() - 1; i >= 0; i--) {
            ServiceRecord service = services.valueAt(i);
            final boolean sameComponent = packageName == null
                    || (service.packageName.equals(packageName)
                        && (filterByClasses == null
                            || filterByClasses.contains(service.name.getClassName())));
            if (sameComponent
                    && (service.app == null || evenPersistent || !service.app.isPersistent())) {
                if (!doit) {
                    return true;
                }
                didSomething = true;
                Slog.i(TAG, "  Force stopping service " + service);
                if (service.app != null && !service.app.isPersistent()) {
                    stopServiceAndUpdateAllowlistManagerLocked(service);
                }
                service.setProcess(null, null, 0, null);
                service.isolatedProc = null;
                if (mTmpCollectionResults == null) {
                    mTmpCollectionResults = new ArrayList<>();
                }
                mTmpCollectionResults.add(service);
            }
        }
        return didSomething;
    }

    boolean bringDownDisabledPackageServicesLocked(String packageName, Set<String> filterByClasses,
            int userId, boolean evenPersistent, boolean doit) {
        boolean didSomething = false;

        if (mTmpCollectionResults != null) {
            mTmpCollectionResults.clear();
        }

        if (userId == UserHandle.USER_ALL) {
            for (int i = mServiceMap.size() - 1; i >= 0; i--) {
                didSomething |= collectPackageServicesLocked(packageName, filterByClasses,
                        evenPersistent, doit, mServiceMap.valueAt(i).mServicesByInstanceName);
                if (!doit && didSomething) {
                    return true;
                }
                if (doit && filterByClasses == null) {
                    forceStopPackageLocked(packageName, mServiceMap.valueAt(i).mUserId);
                }
            }
        } else {
            ServiceMap smap = mServiceMap.get(userId);
            if (smap != null) {
                ArrayMap<ComponentName, ServiceRecord> items = smap.mServicesByInstanceName;
                didSomething = collectPackageServicesLocked(packageName, filterByClasses,
                        evenPersistent, doit, items);
            }
            if (doit && filterByClasses == null) {
                forceStopPackageLocked(packageName, userId);
            }
        }

        if (mTmpCollectionResults != null) {
            final int size = mTmpCollectionResults.size();
            for (int i = size - 1; i >= 0; i--) {
                bringDownServiceLocked(mTmpCollectionResults.get(i), true);
            }
            if (size > 0) {
                mAm.updateOomAdjPendingTargetsLocked(OomAdjuster.OOM_ADJ_REASON_UNBIND_SERVICE);
            }
            mTmpCollectionResults.clear();
        }

        return didSomething;
    }

    void forceStopPackageLocked(String packageName, int userId) {
        ServiceMap smap = mServiceMap.get(userId);
        if (smap != null && smap.mActiveForegroundApps.size() > 0) {
            for (int i = smap.mActiveForegroundApps.size()-1; i >= 0; i--) {
                ActiveForegroundApp aa = smap.mActiveForegroundApps.valueAt(i);
                if (aa.mPackageName.equals(packageName)) {
                    smap.mActiveForegroundApps.removeAt(i);
                    smap.mActiveForegroundAppsChanged = true;
                }
            }
            if (smap.mActiveForegroundAppsChanged) {
                requestUpdateActiveForegroundAppsLocked(smap, 0);
            }
        }
        for (int i = mPendingBringups.size() - 1; i >= 0; i--) {
            ServiceRecord r = mPendingBringups.keyAt(i);
            if (TextUtils.equals(r.packageName, packageName) && r.userId == userId) {
                mPendingBringups.removeAt(i);
            }
        }
        removeServiceRestartBackoffEnabledLocked(packageName);
        removeServiceNotificationDeferralsLocked(packageName, userId);
    }

    void cleanUpServices(int userId, ComponentName component, Intent baseIntent) {
        ArrayList<ServiceRecord> services = new ArrayList<>();
        ArrayMap<ComponentName, ServiceRecord> alls = getServicesLocked(userId);
        for (int i = alls.size() - 1; i >= 0; i--) {
            ServiceRecord sr = alls.valueAt(i);
            if (sr.packageName.equals(component.getPackageName())) {
                services.add(sr);
            }
        }

        // Take care of any running services associated with the app.
        boolean needOomAdj = false;
        for (int i = services.size() - 1; i >= 0; i--) {
            ServiceRecord sr = services.get(i);
            if (sr.startRequested) {
                if ((sr.serviceInfo.flags&ServiceInfo.FLAG_STOP_WITH_TASK) != 0) {
                    Slog.i(TAG, "Stopping service " + sr.shortInstanceName + ": remove task");
                    needOomAdj = true;
                    stopServiceLocked(sr, true);
                } else {
                    sr.pendingStarts.add(new ServiceRecord.StartItem(sr, true,
                            sr.getLastStartId(), baseIntent, null, 0));
                    if (sr.app != null && sr.app.getThread() != null) {
                        // We always run in the foreground, since this is called as
                        // part of the "remove task" UI operation.
                        try {
                            sendServiceArgsLocked(sr, true, false);
                        } catch (TransactionTooLargeException e) {
                            // Ignore, keep going.
                        }
                    }
                }
            }
        }
        if (needOomAdj) {
            mAm.updateOomAdjPendingTargetsLocked(OomAdjuster.OOM_ADJ_REASON_UNBIND_SERVICE);
        }
    }

    final void killServicesLocked(ProcessRecord app, boolean allowRestart) {
        final ProcessServiceRecord psr = app.mServices;
        // Report disconnected services.
        if (false) {
            // XXX we are letting the client link to the service for
            // death notifications.
            int numberOfRunningServices = psr.numberOfRunningServices();
            for (int sIndex = 0; sIndex < numberOfRunningServices; sIndex++) {
                ServiceRecord r = psr.getRunningServiceAt(sIndex);
                ArrayMap<IBinder, ArrayList<ConnectionRecord>> connections = r.getConnections();
                for (int conni = connections.size() - 1; conni >= 0; conni--) {
                    ArrayList<ConnectionRecord> cl = connections.valueAt(conni);
                    for (int i = 0; i < cl.size(); i++) {
                        ConnectionRecord c = cl.get(i);
                        if (c.binding.client != app) {
                            try {
                                //c.conn.connected(r.className, null);
                            } catch (Exception e) {
                                // todo: this should be asynchronous!
                                Slog.w(TAG, "Exception thrown disconnected servce "
                                        + r.shortInstanceName
                                        + " from app " + app.processName, e);
                            }
                        }
                    }
                }
            }
        }

        // Clean up any connections this application has to other services.
        for (int i = psr.numberOfConnections() - 1; i >= 0; i--) {
            ConnectionRecord r = psr.getConnectionAt(i);
            removeConnectionLocked(r, app, null, true);
        }
        updateServiceConnectionActivitiesLocked(psr);
        psr.removeAllConnections();

        psr.mAllowlistManager = false;

        // Clear app state from services.
        for (int i = psr.numberOfRunningServices() - 1; i >= 0; i--) {
            ServiceRecord sr = psr.getRunningServiceAt(i);
            mAm.mBatteryStatsService.noteServiceStopLaunch(sr.appInfo.uid, sr.name.getPackageName(),
                    sr.name.getClassName());
            if (sr.app != app && sr.app != null && !sr.app.isPersistent()) {
                sr.app.mServices.stopService(sr);
                sr.app.mServices.updateBoundClientUids();
            }
            sr.setProcess(null, null, 0, null);
            sr.isolatedProc = null;
            sr.executeNesting = 0;
            sr.forceClearTracker();
            if (mDestroyingServices.remove(sr)) {
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "killServices remove destroying " + sr);
            }

            final int numClients = sr.bindings.size();
            for (int bindingi=numClients-1; bindingi>=0; bindingi--) {
                IntentBindRecord b = sr.bindings.valueAt(bindingi);
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Killing binding " + b
                        + ": shouldUnbind=" + b.hasBound);
                b.binder = null;
                b.requested = b.received = b.hasBound = false;
                // If this binding is coming from a cached process and is asking to keep
                // the service created, then we'll kill the cached process as well -- we
                // don't want to be thrashing around restarting processes that are only
                // there to be cached.
                for (int appi=b.apps.size()-1; appi>=0; appi--) {
                    final ProcessRecord proc = b.apps.keyAt(appi);
                    // If the process is already gone, skip it.
                    if (proc.isKilledByAm() || proc.getThread() == null) {
                        continue;
                    }
                    // Only do this for processes that have an auto-create binding;
                    // otherwise the binding can be left, because it won't cause the
                    // service to restart.
                    final AppBindRecord abind = b.apps.valueAt(appi);
                    boolean hasCreate = false;
                    for (int conni = abind.connections.size() - 1; conni >= 0; conni--) {
                        ConnectionRecord conn = abind.connections.valueAt(conni);
                        if ((conn.flags&(Context.BIND_AUTO_CREATE|Context.BIND_ALLOW_OOM_MANAGEMENT
                                |Context.BIND_WAIVE_PRIORITY)) == Context.BIND_AUTO_CREATE) {
                            hasCreate = true;
                            break;
                        }
                    }
                    if (!hasCreate) {
                        continue;
                    }
                    // XXX turned off for now until we have more time to get a better policy.
                    /*
                    if (false && proc != null && !proc.isPersistent() && proc.getThread() != null
                            && proc.getPid() != 0 && proc.getPid() != ActivityManagerService.MY_PID
                            && proc.mState.getSetProcState() >= PROCESS_STATE_LAST_ACTIVITY) {
                        proc.killLocked("bound to service " + sr.shortInstanceName
                                + " in dying proc " + (app != null ? app.processName : "??"),
                                ApplicationExitInfo.REASON_OTHER, true);
                    }
                    */
                }
            }
        }

        ServiceMap smap = getServiceMapLocked(app.userId);

        // Now do remaining service cleanup.
        for (int i = psr.numberOfRunningServices() - 1; i >= 0; i--) {
            ServiceRecord sr = psr.getRunningServiceAt(i);

            // Unless the process is persistent, this process record is going away,
            // so make sure the service is cleaned out of it.
            if (!app.isPersistent()) {
                psr.stopService(sr);
                psr.updateBoundClientUids();
            }

            // Sanity check: if the service listed for the app is not one
            // we actually are maintaining, just let it drop.
            final ServiceRecord curRec = smap.mServicesByInstanceName.get(sr.instanceName);
            if (curRec != sr) {
                if (curRec != null) {
                    Slog.wtf(TAG, "Service " + sr + " in process " + app
                            + " not same as in map: " + curRec);
                }
                continue;
            }

            // Any services running in the application may need to be placed
            // back in the pending list.
            if (allowRestart && sr.crashCount >= mAm.mConstants.BOUND_SERVICE_MAX_CRASH_RETRY
                    && (sr.serviceInfo.applicationInfo.flags
                        &ApplicationInfo.FLAG_PERSISTENT) == 0) {
                Slog.w(TAG, "Service crashed " + sr.crashCount
                        + " times, stopping: " + sr);
                EventLog.writeEvent(EventLogTags.AM_SERVICE_CRASHED_TOO_MUCH,
                        sr.userId, sr.crashCount, sr.shortInstanceName, sr.app.getPid());
                bringDownServiceLocked(sr, true);
            } else if (!allowRestart
                    || !mAm.mUserController.isUserRunning(sr.userId, 0)) {
                bringDownServiceLocked(sr, true);
            } else {
                final boolean scheduled = scheduleServiceRestartLocked(sr, true /* allowCancel */);

                // Should the service remain running?  Note that in the
                // extreme case of so many attempts to deliver a command
                // that it failed we also will stop it here.
                if (!scheduled) {
                    bringDownServiceLocked(sr, true);
                } else if (sr.canStopIfKilled(false /* isStartCanceled */)) {
                    // Update to stopped state because the explicit start is gone. The service is
                    // scheduled to restart for other reason (e.g. connections) so we don't bring
                    // down it.
                    sr.startRequested = false;
                    if (sr.tracker != null) {
                        sr.tracker.setStarted(false, mAm.mProcessStats.getMemFactorLocked(),
                                SystemClock.uptimeMillis());
                    }
                }
            }
        }

        mAm.updateOomAdjPendingTargetsLocked(OomAdjuster.OOM_ADJ_REASON_UNBIND_SERVICE);

        if (!allowRestart) {
            psr.stopAllServices();
            psr.clearBoundClientUids();

            // Make sure there are no more restarting services for this process.
            for (int i=mRestartingServices.size()-1; i>=0; i--) {
                ServiceRecord r = mRestartingServices.get(i);
                if (r.processName.equals(app.processName) &&
                        r.serviceInfo.applicationInfo.uid == app.info.uid) {
                    mRestartingServices.remove(i);
                    clearRestartingIfNeededLocked(r);
                }
            }
            for (int i=mPendingServices.size()-1; i>=0; i--) {
                ServiceRecord r = mPendingServices.get(i);
                if (r.processName.equals(app.processName) &&
                        r.serviceInfo.applicationInfo.uid == app.info.uid) {
                    mPendingServices.remove(i);
                }
            }
            for (int i = mPendingBringups.size() - 1; i >= 0; i--) {
                ServiceRecord r = mPendingBringups.keyAt(i);
                if (r.processName.equals(app.processName)
                        && r.serviceInfo.applicationInfo.uid == app.info.uid) {
                    mPendingBringups.removeAt(i);
                }
            }
        }

        // Make sure we have no more records on the stopping list.
        int i = mDestroyingServices.size();
        while (i > 0) {
            i--;
            ServiceRecord sr = mDestroyingServices.get(i);
            if (sr.app == app) {
                sr.forceClearTracker();
                mDestroyingServices.remove(i);
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "killServices remove destroying " + sr);
            }
        }

        psr.stopAllExecutingServices();
    }

    ActivityManager.RunningServiceInfo makeRunningServiceInfoLocked(ServiceRecord r) {
        ActivityManager.RunningServiceInfo info =
            new ActivityManager.RunningServiceInfo();
        info.service = r.name;
        if (r.app != null) {
            info.pid = r.app.getPid();
        }
        info.uid = r.appInfo.uid;
        info.process = r.processName;
        info.foreground = r.isForeground;
        info.activeSince = r.createRealTime;
        info.started = r.startRequested;
        info.clientCount = r.getConnections().size();
        info.crashCount = r.crashCount;
        info.lastActivityTime = r.lastActivity;
        if (r.isForeground) {
            info.flags |= ActivityManager.RunningServiceInfo.FLAG_FOREGROUND;
        }
        if (r.startRequested) {
            info.flags |= ActivityManager.RunningServiceInfo.FLAG_STARTED;
        }
        if (r.app != null && r.app.getPid() == ActivityManagerService.MY_PID) {
            info.flags |= ActivityManager.RunningServiceInfo.FLAG_SYSTEM_PROCESS;
        }
        if (r.app != null && r.app.isPersistent()) {
            info.flags |= ActivityManager.RunningServiceInfo.FLAG_PERSISTENT_PROCESS;
        }

        ArrayMap<IBinder, ArrayList<ConnectionRecord>> connections = r.getConnections();
        for (int conni = connections.size() - 1; conni >= 0; conni--) {
            ArrayList<ConnectionRecord> connl = connections.valueAt(conni);
            for (int i=0; i<connl.size(); i++) {
                ConnectionRecord conn = connl.get(i);
                if (conn.clientLabel != 0) {
                    info.clientPackage = conn.binding.client.info.packageName;
                    info.clientLabel = conn.clientLabel;
                    return info;
                }
            }
        }
        return info;
    }

    List<ActivityManager.RunningServiceInfo> getRunningServiceInfoLocked(int maxNum, int flags,
        int callingUid, boolean allowed, boolean canInteractAcrossUsers) {
        ArrayList<ActivityManager.RunningServiceInfo> res
                = new ArrayList<ActivityManager.RunningServiceInfo>();

        final long ident = Binder.clearCallingIdentity();
        try {
            if (canInteractAcrossUsers) {
                int[] users = mAm.mUserController.getUsers();
                for (int ui=0; ui<users.length && res.size() < maxNum; ui++) {
                    ArrayMap<ComponentName, ServiceRecord> alls = getServicesLocked(users[ui]);
                    for (int i=0; i<alls.size() && res.size() < maxNum; i++) {
                        ServiceRecord sr = alls.valueAt(i);
                        res.add(makeRunningServiceInfoLocked(sr));
                    }
                }

                for (int i=0; i<mRestartingServices.size() && res.size() < maxNum; i++) {
                    ServiceRecord r = mRestartingServices.get(i);
                    ActivityManager.RunningServiceInfo info =
                            makeRunningServiceInfoLocked(r);
                    info.restarting = r.nextRestartTime;
                    res.add(info);
                }
            } else {
                int userId = UserHandle.getUserId(callingUid);
                ArrayMap<ComponentName, ServiceRecord> alls = getServicesLocked(userId);
                for (int i=0; i<alls.size() && res.size() < maxNum; i++) {
                    ServiceRecord sr = alls.valueAt(i);

                    if (allowed || (sr.app != null && sr.app.uid == callingUid)) {
                        res.add(makeRunningServiceInfoLocked(sr));
                    }
                }

                for (int i=0; i<mRestartingServices.size() && res.size() < maxNum; i++) {
                    ServiceRecord r = mRestartingServices.get(i);
                    if (r.userId == userId
                        && (allowed || (r.app != null && r.app.uid == callingUid))) {
                        ActivityManager.RunningServiceInfo info =
                                makeRunningServiceInfoLocked(r);
                        info.restarting = r.nextRestartTime;
                        res.add(info);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return res;
    }

    public PendingIntent getRunningServiceControlPanelLocked(ComponentName name) {
        int userId = UserHandle.getUserId(Binder.getCallingUid());
        ServiceRecord r = getServiceByNameLocked(name, userId);
        if (r != null) {
            ArrayMap<IBinder, ArrayList<ConnectionRecord>> connections = r.getConnections();
            for (int conni = connections.size() - 1; conni >= 0; conni--) {
                ArrayList<ConnectionRecord> conn = connections.valueAt(conni);
                for (int i=0; i<conn.size(); i++) {
                    if (conn.get(i).clientIntent != null) {
                        return conn.get(i).clientIntent;
                    }
                }
            }
        }
        return null;
    }

    void serviceTimeout(ProcessRecord proc) {
        String anrMessage = null;
        synchronized(mAm) {
            if (proc.isDebugging()) {
                // The app's being debugged, ignore timeout.
                return;
            }
            final ProcessServiceRecord psr = proc.mServices;
            if (psr.numberOfExecutingServices() == 0 || proc.getThread() == null) {
                return;
            }
            final long now = SystemClock.uptimeMillis();
            final long maxTime =  now -
                    (psr.shouldExecServicesFg() ? SERVICE_TIMEOUT : SERVICE_BACKGROUND_TIMEOUT);
            ServiceRecord timeout = null;
            long nextTime = 0;
            for (int i = psr.numberOfExecutingServices() - 1; i >= 0; i--) {
                ServiceRecord sr = psr.getExecutingServiceAt(i);
                if (sr.executingStart < maxTime) {
                    timeout = sr;
                    break;
                }
                if (sr.executingStart > nextTime) {
                    nextTime = sr.executingStart;
                }
            }
            if (timeout != null && mAm.mProcessList.isInLruListLOSP(proc)) {
                Slog.w(TAG, "Timeout executing service: " + timeout);
                StringWriter sw = new StringWriter();
                PrintWriter pw = new FastPrintWriter(sw, false, 1024);
                pw.println(timeout);
                timeout.dump(pw, "    ");
                pw.close();
                mLastAnrDump = sw.toString();
                mAm.mHandler.removeCallbacks(mLastAnrDumpClearer);
                mAm.mHandler.postDelayed(mLastAnrDumpClearer, LAST_ANR_LIFETIME_DURATION_MSECS);
                anrMessage = "executing service " + timeout.shortInstanceName;
            } else {
                Message msg = mAm.mHandler.obtainMessage(
                        ActivityManagerService.SERVICE_TIMEOUT_MSG);
                msg.obj = proc;
                mAm.mHandler.sendMessageAtTime(msg, psr.shouldExecServicesFg()
                        ? (nextTime+SERVICE_TIMEOUT) : (nextTime + SERVICE_BACKGROUND_TIMEOUT));
            }
        }

        if (anrMessage != null) {
            mAm.mAnrHelper.appNotResponding(proc, anrMessage);
        }
    }

    void serviceForegroundTimeout(ServiceRecord r) {
        ProcessRecord app;
        synchronized (mAm) {
            if (!r.fgRequired || r.destroying) {
                return;
            }

            app = r.app;
            if (app != null && app.isDebugging()) {
                // The app's being debugged; let it ride
                return;
            }

            if (DEBUG_BACKGROUND_CHECK) {
                Slog.i(TAG, "Service foreground-required timeout for " + r);
            }
            r.fgWaiting = false;
            stopServiceLocked(r, false);
        }

        if (app != null) {
            mAm.mAnrHelper.appNotResponding(app,
                    "Context.startForegroundService() did not then call Service.startForeground(): "
                        + r);
        }
    }

    public void updateServiceApplicationInfoLocked(ApplicationInfo applicationInfo) {
        final int userId = UserHandle.getUserId(applicationInfo.uid);
        ServiceMap serviceMap = mServiceMap.get(userId);
        if (serviceMap != null) {
            ArrayMap<ComponentName, ServiceRecord> servicesByName
                    = serviceMap.mServicesByInstanceName;
            for (int j = servicesByName.size() - 1; j >= 0; j--) {
                ServiceRecord serviceRecord = servicesByName.valueAt(j);
                if (applicationInfo.packageName.equals(serviceRecord.appInfo.packageName)) {
                    serviceRecord.appInfo = applicationInfo;
                    serviceRecord.serviceInfo.applicationInfo = applicationInfo;
                }
            }
        }
    }

    void serviceForegroundCrash(ProcessRecord app, CharSequence serviceRecord) {
        mAm.crashApplicationWithType(app.uid, app.getPid(), app.info.packageName, app.userId,
                "Context.startForegroundService() did not then call Service.startForeground(): "
                    + serviceRecord, false /*force*/,
                ForegroundServiceDidNotStartInTimeException.TYPE_ID);
    }

    void scheduleServiceTimeoutLocked(ProcessRecord proc) {
        if (proc.mServices.numberOfExecutingServices() == 0 || proc.getThread() == null) {
            return;
        }
        Message msg = mAm.mHandler.obtainMessage(
                ActivityManagerService.SERVICE_TIMEOUT_MSG);
        msg.obj = proc;
        mAm.mHandler.sendMessageDelayed(msg, proc.mServices.shouldExecServicesFg()
                ? SERVICE_TIMEOUT : SERVICE_BACKGROUND_TIMEOUT);
    }

    void scheduleServiceForegroundTransitionTimeoutLocked(ServiceRecord r) {
        if (r.app.mServices.numberOfExecutingServices() == 0 || r.app.getThread() == null) {
            return;
        }
        Message msg = mAm.mHandler.obtainMessage(
                ActivityManagerService.SERVICE_FOREGROUND_TIMEOUT_MSG);
        msg.obj = r;
        r.fgWaiting = true;
        mAm.mHandler.sendMessageDelayed(msg, SERVICE_START_FOREGROUND_TIMEOUT);
    }

    final class ServiceDumper {
        private final FileDescriptor fd;
        private final PrintWriter pw;
        private final String[] args;
        private final boolean dumpAll;
        private final String dumpPackage;
        private final ItemMatcher matcher;
        private final ArrayList<ServiceRecord> services = new ArrayList<>();

        private final long nowReal = SystemClock.elapsedRealtime();

        private boolean needSep = false;
        private boolean printedAnything = false;
        private boolean printed = false;

        /**
         * Note: do not call directly, use {@link #newServiceDumperLocked} instead (this
         * must be called with the lock held).
         */
        ServiceDumper(FileDescriptor fd, PrintWriter pw, String[] args,
                int opti, boolean dumpAll, String dumpPackage) {
            this.fd = fd;
            this.pw = pw;
            this.args = args;
            this.dumpAll = dumpAll;
            this.dumpPackage = dumpPackage;
            matcher = new ItemMatcher();
            matcher.build(args, opti);

            final int[] users = mAm.mUserController.getUsers();
            for (int user : users) {
                ServiceMap smap = getServiceMapLocked(user);
                if (smap.mServicesByInstanceName.size() > 0) {
                    for (int si=0; si<smap.mServicesByInstanceName.size(); si++) {
                        ServiceRecord r = smap.mServicesByInstanceName.valueAt(si);
                        if (!matcher.match(r, r.name)) {
                            continue;
                        }
                        if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
                            continue;
                        }
                        services.add(r);
                    }
                }
            }
        }

        private void dumpHeaderLocked() {
            pw.println("ACTIVITY MANAGER SERVICES (dumpsys activity services)");
            if (mLastAnrDump != null) {
                pw.println("  Last ANR service:");
                pw.print(mLastAnrDump);
                pw.println();
            }
        }

        void dumpLocked() {
            dumpHeaderLocked();

            try {
                int[] users = mAm.mUserController.getUsers();
                for (int user : users) {
                    // Find the first service for this user.
                    int serviceIdx = 0;
                    while (serviceIdx < services.size() && services.get(serviceIdx).userId != user) {
                        serviceIdx++;
                    }
                    printed = false;
                    if (serviceIdx < services.size()) {
                        needSep = false;
                        while (serviceIdx < services.size()) {
                            ServiceRecord r = services.get(serviceIdx);
                            serviceIdx++;
                            if (r.userId != user) {
                                break;
                            }
                            dumpServiceLocalLocked(r);
                        }
                        needSep |= printed;
                    }

                    dumpUserRemainsLocked(user);
                }
            } catch (Exception e) {
                Slog.w(TAG, "Exception in dumpServicesLocked", e);
            }

            dumpRemainsLocked();
        }

        void dumpWithClient() {
            synchronized(mAm) {
                dumpHeaderLocked();
            }

            try {
                int[] users = mAm.mUserController.getUsers();
                for (int user : users) {
                    // Find the first service for this user.
                    int serviceIdx = 0;
                    while (serviceIdx < services.size() && services.get(serviceIdx).userId != user) {
                        serviceIdx++;
                    }
                    printed = false;
                    if (serviceIdx < services.size()) {
                        needSep = false;
                        while (serviceIdx < services.size()) {
                            ServiceRecord r = services.get(serviceIdx);
                            serviceIdx++;
                            if (r.userId != user) {
                                break;
                            }
                            synchronized(mAm) {
                                dumpServiceLocalLocked(r);
                            }
                            dumpServiceClient(r);
                        }
                        needSep |= printed;
                    }

                    synchronized(mAm) {
                        dumpUserRemainsLocked(user);
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "Exception in dumpServicesLocked", e);
            }

            synchronized(mAm) {
                dumpRemainsLocked();
            }
        }

        private void dumpUserHeaderLocked(int user) {
            if (!printed) {
                if (printedAnything) {
                    pw.println();
                }
                pw.println("  User " + user + " active services:");
                printed = true;
            }
            printedAnything = true;
            if (needSep) {
                pw.println();
            }
        }

        private void dumpServiceLocalLocked(ServiceRecord r) {
            dumpUserHeaderLocked(r.userId);
            pw.print("  * ");
            pw.println(r);
            if (dumpAll) {
                r.dump(pw, "    ");
                needSep = true;
            } else {
                pw.print("    app=");
                pw.println(r.app);
                pw.print("    created=");
                TimeUtils.formatDuration(r.createRealTime, nowReal, pw);
                pw.print(" started=");
                pw.print(r.startRequested);
                pw.print(" connections=");
                ArrayMap<IBinder, ArrayList<ConnectionRecord>> connections = r.getConnections();
                pw.println(connections.size());
                if (connections.size() > 0) {
                    pw.println("    Connections:");
                    for (int conni = 0; conni < connections.size(); conni++) {
                        ArrayList<ConnectionRecord> clist = connections.valueAt(conni);
                        for (int i = 0; i < clist.size(); i++) {
                            ConnectionRecord conn = clist.get(i);
                            pw.print("      ");
                            pw.print(conn.binding.intent.intent.getIntent()
                                    .toShortString(false, false, false, false));
                            pw.print(" -> ");
                            ProcessRecord proc = conn.binding.client;
                            pw.println(proc != null ? proc.toShortString() : "null");
                        }
                    }
                }
            }
        }

        private void dumpServiceClient(ServiceRecord r) {
            final ProcessRecord proc = r.app;
            if (proc == null) {
                return;
            }
            final IApplicationThread thread = proc.getThread();
            if (thread == null) {
                return;
            }
            pw.println("    Client:");
            pw.flush();
            try {
                TransferPipe tp = new TransferPipe();
                try {
                    thread.dumpService(tp.getWriteFd(), r, args);
                    tp.setBufferPrefix("      ");
                    // Short timeout, since blocking here can
                    // deadlock with the application.
                    tp.go(fd, 2000);
                } finally {
                    tp.kill();
                }
            } catch (IOException e) {
                pw.println("      Failure while dumping the service: " + e);
            } catch (RemoteException e) {
                pw.println("      Got a RemoteException while dumping the service");
            }
            needSep = true;
        }

        private void dumpUserRemainsLocked(int user) {
            ServiceMap smap = getServiceMapLocked(user);
            printed = false;
            for (int si=0, SN=smap.mDelayedStartList.size(); si<SN; si++) {
                ServiceRecord r = smap.mDelayedStartList.get(si);
                if (!matcher.match(r, r.name)) {
                    continue;
                }
                if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
                    continue;
                }
                if (!printed) {
                    if (printedAnything) {
                        pw.println();
                    }
                    pw.println("  User " + user + " delayed start services:");
                    printed = true;
                }
                printedAnything = true;
                pw.print("  * Delayed start "); pw.println(r);
            }
            printed = false;
            for (int si=0, SN=smap.mStartingBackground.size(); si<SN; si++) {
                ServiceRecord r = smap.mStartingBackground.get(si);
                if (!matcher.match(r, r.name)) {
                    continue;
                }
                if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
                    continue;
                }
                if (!printed) {
                    if (printedAnything) {
                        pw.println();
                    }
                    pw.println("  User " + user + " starting in background:");
                    printed = true;
                }
                printedAnything = true;
                pw.print("  * Starting bg "); pw.println(r);
            }
        }

        private void dumpRemainsLocked() {
            if (mPendingServices.size() > 0) {
                printed = false;
                for (int i=0; i<mPendingServices.size(); i++) {
                    ServiceRecord r = mPendingServices.get(i);
                    if (!matcher.match(r, r.name)) {
                        continue;
                    }
                    if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
                        continue;
                    }
                    printedAnything = true;
                    if (!printed) {
                        if (needSep) pw.println();
                        needSep = true;
                        pw.println("  Pending services:");
                        printed = true;
                    }
                    pw.print("  * Pending "); pw.println(r);
                    r.dump(pw, "    ");
                }
                needSep = true;
            }

            if (mRestartingServices.size() > 0) {
                printed = false;
                for (int i=0; i<mRestartingServices.size(); i++) {
                    ServiceRecord r = mRestartingServices.get(i);
                    if (!matcher.match(r, r.name)) {
                        continue;
                    }
                    if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
                        continue;
                    }
                    printedAnything = true;
                    if (!printed) {
                        if (needSep) pw.println();
                        needSep = true;
                        pw.println("  Restarting services:");
                        printed = true;
                    }
                    pw.print("  * Restarting "); pw.println(r);
                    r.dump(pw, "    ");
                }
                needSep = true;
            }

            if (mDestroyingServices.size() > 0) {
                printed = false;
                for (int i=0; i< mDestroyingServices.size(); i++) {
                    ServiceRecord r = mDestroyingServices.get(i);
                    if (!matcher.match(r, r.name)) {
                        continue;
                    }
                    if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
                        continue;
                    }
                    printedAnything = true;
                    if (!printed) {
                        if (needSep) pw.println();
                        needSep = true;
                        pw.println("  Destroying services:");
                        printed = true;
                    }
                    pw.print("  * Destroy "); pw.println(r);
                    r.dump(pw, "    ");
                }
                needSep = true;
            }

            if (dumpAll) {
                printed = false;
                for (int ic=0; ic<mServiceConnections.size(); ic++) {
                    ArrayList<ConnectionRecord> r = mServiceConnections.valueAt(ic);
                    for (int i=0; i<r.size(); i++) {
                        ConnectionRecord cr = r.get(i);
                        if (!matcher.match(cr.binding.service, cr.binding.service.name)) {
                            continue;
                        }
                        if (dumpPackage != null && (cr.binding.client == null
                                || !dumpPackage.equals(cr.binding.client.info.packageName))) {
                            continue;
                        }
                        printedAnything = true;
                        if (!printed) {
                            if (needSep) pw.println();
                            needSep = true;
                            pw.println("  Connection bindings to services:");
                            printed = true;
                        }
                        pw.print("  * "); pw.println(cr);
                        cr.dump(pw, "    ");
                    }
                }
            }

            if (matcher.all) {
                final long nowElapsed = SystemClock.elapsedRealtime();
                final int[] users = mAm.mUserController.getUsers();
                for (int user : users) {
                    boolean printedUser = false;
                    ServiceMap smap = mServiceMap.get(user);
                    if (smap == null) {
                        continue;
                    }
                    for (int i = smap.mActiveForegroundApps.size() - 1; i >= 0; i--) {
                        ActiveForegroundApp aa = smap.mActiveForegroundApps.valueAt(i);
                        if (dumpPackage != null && !dumpPackage.equals(aa.mPackageName)) {
                            continue;
                        }
                        if (!printedUser) {
                            printedUser = true;
                            printedAnything = true;
                            if (needSep) pw.println();
                            needSep = true;
                            pw.print("Active foreground apps - user ");
                            pw.print(user);
                            pw.println(":");
                        }
                        pw.print("  #");
                        pw.print(i);
                        pw.print(": ");
                        pw.println(aa.mPackageName);
                        if (aa.mLabel != null) {
                            pw.print("    mLabel=");
                            pw.println(aa.mLabel);
                        }
                        pw.print("    mNumActive=");
                        pw.print(aa.mNumActive);
                        pw.print(" mAppOnTop=");
                        pw.print(aa.mAppOnTop);
                        pw.print(" mShownWhileTop=");
                        pw.print(aa.mShownWhileTop);
                        pw.print(" mShownWhileScreenOn=");
                        pw.println(aa.mShownWhileScreenOn);
                        pw.print("    mStartTime=");
                        TimeUtils.formatDuration(aa.mStartTime - nowElapsed, pw);
                        pw.print(" mStartVisibleTime=");
                        TimeUtils.formatDuration(aa.mStartVisibleTime - nowElapsed, pw);
                        pw.println();
                        if (aa.mEndTime != 0) {
                            pw.print("    mEndTime=");
                            TimeUtils.formatDuration(aa.mEndTime - nowElapsed, pw);
                            pw.println();
                        }
                    }
                    if (smap.hasMessagesOrCallbacks()) {
                        if (needSep) {
                            pw.println();
                        }
                        printedAnything = true;
                        needSep = true;
                        pw.print("  Handler - user ");
                        pw.print(user);
                        pw.println(":");
                        smap.dumpMine(new PrintWriterPrinter(pw), "    ");
                    }
                }
            }

            if (!printedAnything) {
                pw.println("  (nothing)");
            }
        }
    }

    ServiceDumper newServiceDumperLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage) {
        return new ServiceDumper(fd, pw, args, opti, dumpAll, dumpPackage);
    }

    protected void dumpDebug(ProtoOutputStream proto, long fieldId) {
        synchronized (mAm) {
            final long outterToken = proto.start(fieldId);
            int[] users = mAm.mUserController.getUsers();
            for (int user : users) {
                ServiceMap smap = mServiceMap.get(user);
                if (smap == null) {
                    continue;
                }
                long token = proto.start(ActiveServicesProto.SERVICES_BY_USERS);
                proto.write(ActiveServicesProto.ServicesByUser.USER_ID, user);
                ArrayMap<ComponentName, ServiceRecord> alls = smap.mServicesByInstanceName;
                for (int i=0; i<alls.size(); i++) {
                    alls.valueAt(i).dumpDebug(proto,
                            ActiveServicesProto.ServicesByUser.SERVICE_RECORDS);
                }
                proto.end(token);
            }
            proto.end(outterToken);
        }
    }

    /**
     * There are three ways to call this:
     *  - no service specified: dump all the services
     *  - a flattened component name that matched an existing service was specified as the
     *    first arg: dump that one service
     *  - the first arg isn't the flattened component name of an existing service:
     *    dump all services whose component contains the first arg as a substring
     */
    protected boolean dumpService(FileDescriptor fd, PrintWriter pw, String name, int[] users,
            String[] args, int opti, boolean dumpAll) {
        final ArrayList<ServiceRecord> services = new ArrayList<>();

        final Predicate<ServiceRecord> filter = DumpUtils.filterRecord(name);

        synchronized (mAm) {
            if (users == null) {
                users = mAm.mUserController.getUsers();
            }

            for (int user : users) {
                ServiceMap smap = mServiceMap.get(user);
                if (smap == null) {
                    continue;
                }
                ArrayMap<ComponentName, ServiceRecord> alls = smap.mServicesByInstanceName;
                for (int i=0; i<alls.size(); i++) {
                    ServiceRecord r1 = alls.valueAt(i);

                    if (filter.test(r1)) {
                        services.add(r1);
                    }
                }
            }
        }

        if (services.size() <= 0) {
            return false;
        }

        // Sort by component name.
        services.sort(Comparator.comparing(WithComponentName::getComponentName));

        boolean needSep = false;
        for (int i=0; i<services.size(); i++) {
            if (needSep) {
                pw.println();
            }
            needSep = true;
            dumpService("", fd, pw, services.get(i), args, dumpAll);
        }
        return true;
    }

    /**
     * Invokes IApplicationThread.dumpService() on the thread of the specified service if
     * there is a thread associated with the service.
     */
    private void dumpService(String prefix, FileDescriptor fd, PrintWriter pw,
            final ServiceRecord r, String[] args, boolean dumpAll) {
        String innerPrefix = prefix + "  ";
        synchronized (mAm) {
            pw.print(prefix); pw.print("SERVICE ");
            pw.print(r.shortInstanceName); pw.print(" ");
            pw.print(Integer.toHexString(System.identityHashCode(r)));
            pw.print(" pid=");
            if (r.app != null) {
                pw.print(r.app.getPid());
                pw.print(" user="); pw.println(r.userId);
            } else pw.println("(not running)");
            if (dumpAll) {
                r.dump(pw, innerPrefix);
            }
        }
        IApplicationThread thread;
        if (r.app != null && (thread = r.app.getThread()) != null) {
            pw.print(prefix); pw.println("  Client:");
            pw.flush();
            try {
                TransferPipe tp = new TransferPipe();
                try {
                    thread.dumpService(tp.getWriteFd(), r, args);
                    tp.setBufferPrefix(prefix + "    ");
                    tp.go(fd);
                } finally {
                    tp.kill();
                }
            } catch (IOException e) {
                pw.println(prefix + "    Failure while dumping the service: " + e);
            } catch (RemoteException e) {
                pw.println(prefix + "    Got a RemoteException while dumping the service");
            }
        }
    }

    /**
     * There are two FGS restrictions:
     * In R, mAllowWhileInUsePermissionInFgs is to allow while-in-use permissions in foreground
     *  service or not. while-in-use permissions in FGS started from background might be restricted.
     * In S, mAllowStartForeground is to allow FGS to startForeground or not. Service started
     * from background may not become a FGS.
     * @param callingPackage caller app's package name.
     * @param callingUid caller app's uid.
     * @param intent intent to start/bind service.
     * @param r the service to start.
     * @return true if allow, false otherwise.
     */
    private void setFgsRestrictionLocked(String callingPackage,
            int callingPid, int callingUid, Intent intent, ServiceRecord r, int userId,
            boolean allowBackgroundActivityStarts) {
        r.mLastSetFgsRestrictionTime = SystemClock.elapsedRealtime();
        // Check DeviceConfig flag.
        if (!mAm.mConstants.mFlagBackgroundFgsStartRestrictionEnabled) {
            r.mAllowWhileInUsePermissionInFgs = true;
        }

        if (!r.mAllowWhileInUsePermissionInFgs
                || (r.mAllowStartForeground == REASON_DENIED)) {
            final @ReasonCode int allowWhileInUse = shouldAllowFgsWhileInUsePermissionLocked(
                    callingPackage, callingPid, callingUid, r, allowBackgroundActivityStarts);
            if (!r.mAllowWhileInUsePermissionInFgs) {
                r.mAllowWhileInUsePermissionInFgs = (allowWhileInUse != REASON_DENIED);
            }
            if (r.mAllowStartForeground == REASON_DENIED) {
                r.mAllowStartForeground = shouldAllowFgsStartForegroundLocked(allowWhileInUse,
                        callingPackage, callingPid, callingUid, intent, r,
                        userId);
            }
        }
    }

    void resetFgsRestrictionLocked(ServiceRecord r) {
        r.mAllowWhileInUsePermissionInFgs = false;
        r.mAllowStartForeground = REASON_DENIED;
        r.mInfoAllowStartForeground = null;
        r.mInfoTempFgsAllowListReason = null;
        r.mLoggedInfoAllowStartForeground = false;
        r.mLastSetFgsRestrictionTime = 0;
    }

    boolean canStartForegroundServiceLocked(int callingPid, int callingUid, String callingPackage) {
        if (!mAm.mConstants.mFlagBackgroundFgsStartRestrictionEnabled) {
            return true;
        }
        final @ReasonCode int allowWhileInUse = shouldAllowFgsWhileInUsePermissionLocked(
                callingPackage, callingPid, callingUid, null /* serviceRecord */,
                false /* allowBackgroundActivityStarts */);
        final @ReasonCode int allowStartFgs = shouldAllowFgsStartForegroundLocked(
                allowWhileInUse, callingPid, callingUid, callingPackage, null /* targetService */);
        return allowStartFgs != REASON_DENIED;
    }

    /**
     * Should allow while-in-use permissions in FGS or not.
     * A typical BG started FGS is not allowed to have while-in-use permissions.
     * @param callingPackage caller app's package name.
     * @param callingUid caller app's uid.
     * @param targetService the service to start.
     * @return {@link ReasonCode}
     */
    private @ReasonCode int shouldAllowFgsWhileInUsePermissionLocked(String callingPackage,
            int callingPid, int callingUid, @Nullable ServiceRecord targetService,
            boolean allowBackgroundActivityStarts) {
        int ret = REASON_DENIED;

        final int uidState = mAm.getUidStateLocked(callingUid);
        if (ret == REASON_DENIED) {
            // Is the calling UID at PROCESS_STATE_TOP or above?
            if (uidState <= PROCESS_STATE_TOP) {
                ret = getReasonCodeFromProcState(uidState);
            }
        }

        if (ret == REASON_DENIED) {
            // Does the calling UID have any visible activity?
            final boolean isCallingUidVisible = mAm.mAtmInternal.isUidForeground(callingUid);
            if (isCallingUidVisible) {
                ret = REASON_UID_VISIBLE;
            }
        }

        if (ret == REASON_DENIED) {
            // Is the allow activity background start flag on?
            if (allowBackgroundActivityStarts) {
                ret = REASON_START_ACTIVITY_FLAG;
            }
        }

        if (ret == REASON_DENIED) {
            boolean isCallerSystem = false;
            final int callingAppId = UserHandle.getAppId(callingUid);
            switch (callingAppId) {
                case ROOT_UID:
                case SYSTEM_UID:
                case NFC_UID:
                case SHELL_UID:
                    isCallerSystem = true;
                    break;
                default:
                    isCallerSystem = false;
                    break;
            }

            if (isCallerSystem) {
                ret = REASON_SYSTEM_UID;
            }
        }

        if (ret == REASON_DENIED) {
            final Integer allowedType = mAm.mProcessList.searchEachLruProcessesLOSP(false, pr -> {
                if (pr.uid == callingUid) {
                    if (pr.getWindowProcessController().areBackgroundFgsStartsAllowed()) {
                        return REASON_ACTIVITY_STARTER;
                    }
                }
                return null;
            });
            if (allowedType != null) {
                ret = allowedType;
            }
        }

        if (ret == REASON_DENIED) {
            if (mAm.mInternal.isTempAllowlistedForFgsWhileInUse(callingUid)) {
                return REASON_TEMP_ALLOWED_WHILE_IN_USE;
            }
        }

        if (ret == REASON_DENIED) {
            if (targetService != null && targetService.app != null) {
                ActiveInstrumentation instr = targetService.app.getActiveInstrumentation();
                if (instr != null && instr.mHasBackgroundActivityStartsPermission) {
                    ret = REASON_INSTR_BACKGROUND_ACTIVITY_PERMISSION;
                }
            }
        }

        if (ret == REASON_DENIED) {
            if (mAm.checkPermission(START_ACTIVITIES_FROM_BACKGROUND, callingPid, callingUid)
                    == PERMISSION_GRANTED) {
                ret = REASON_BACKGROUND_ACTIVITY_PERMISSION;
            }
        }

        if (ret == REASON_DENIED) {
            final boolean isAllowedPackage =
                    mAllowListWhileInUsePermissionInFgs.contains(callingPackage);
            if (isAllowedPackage) {
                ret = REASON_ALLOWLISTED_PACKAGE;
            }
        }

        if (ret == REASON_DENIED) {
            // Is the calling UID a device owner app?
            final boolean isDeviceOwner = mAm.mInternal.isDeviceOwner(callingUid);
            if (isDeviceOwner) {
                ret = REASON_DEVICE_OWNER;
            }
        }
        return ret;
    }

    /**
     * Should allow the FGS to start (AKA startForeground()) or not.
     * The check in this method is in addition to check in
     * {@link #shouldAllowFgsWhileInUsePermissionLocked}
     * @param allowWhileInUse the return code from {@link #shouldAllowFgsWhileInUsePermissionLocked}
     * @param callingPackage caller app's package name.
     * @param callingUid caller app's uid.
     * @param intent intent to start/bind service.
     * @param r the service to start.
     * @return {@link ReasonCode}
     */
    private @ReasonCode int shouldAllowFgsStartForegroundLocked(
            @ReasonCode int allowWhileInUse, String callingPackage, int callingPid,
            int callingUid, Intent intent, ServiceRecord r, int userId) {
        ActivityManagerService.FgsTempAllowListItem tempAllowListReason =
                r.mInfoTempFgsAllowListReason = mAm.isAllowlistedForFgsStartLOSP(callingUid);
        int ret = shouldAllowFgsStartForegroundLocked(allowWhileInUse, callingPid, callingUid,
                callingPackage, r);

        String bindFromPackage = null;
        if (ret == REASON_DENIED) {
            // If the callingUid is not allowed to start FGS, check if the callingUid has any
            // service that is bound by a clientUid, the clientUid can propagate its BG-FGS-start
            // capability down to the callingUid.
            final ArraySet<Integer> checkedClientUids = new ArraySet<>();
            final Pair<Integer, String> isAllowed = mAm.mProcessList.searchEachLruProcessesLOSP(
                    false, pr -> {
                if (pr.uid == callingUid) {
                    final ProcessServiceRecord psr = pr.mServices;
                    final int serviceCount = psr.mServices.size();
                    for (int svc = 0; svc < serviceCount; svc++) {
                        final ArrayMap<IBinder, ArrayList<ConnectionRecord>> conns =
                                psr.mServices.valueAt(svc).getConnections();
                        final int size = conns.size();
                        for (int conni = 0; conni < size; conni++) {
                            final ArrayList<ConnectionRecord> crs = conns.valueAt(conni);
                            for (int con = 0; con < crs.size(); con++) {
                                final ConnectionRecord cr = crs.get(con);
                                final ProcessRecord clientPr = cr.binding.client;
                                // Persistent process does not propagate BG-FGS-start capability
                                // down to service over binding.
                                if (clientPr.mState.getCurProcState()
                                        <= PROCESS_STATE_PERSISTENT_UI) {
                                    continue;
                                }
                                final int clientPid = clientPr.mPid;
                                final int clientUid = clientPr.uid;
                                // An UID can bind to itself, do not check on itself again.
                                // Also skip already checked clientUid.
                                if (clientUid == callingUid
                                        || checkedClientUids.contains(clientUid)) {
                                    continue;
                                }
                                final String clientPackageName = cr.clientPackageName;
                                final @ReasonCode int allowWhileInUse2 =
                                        shouldAllowFgsWhileInUsePermissionLocked(clientPackageName,
                                                clientPid, clientUid, null /* serviceRecord */,
                                                false /* allowBackgroundActivityStarts */);
                                final @ReasonCode int allowStartFgs =
                                        shouldAllowFgsStartForegroundLocked(allowWhileInUse2,
                                                clientPid, clientUid, clientPackageName, null /* targetService */);
                                if (allowStartFgs != REASON_DENIED) {
                                    return new Pair<>(allowStartFgs, clientPackageName);
                                } else {
                                    checkedClientUids.add(clientUid);
                                }

                            }
                        }
                    }
                }
                return null;
            });
            if (isAllowed != null) {
                ret = REASON_FGS_BINDING;
                bindFromPackage = isAllowed.second;
            }
        }

        final int uidState = mAm.getUidStateLocked(callingUid);
        int callerTargetSdkVersion = INVALID_UID;
        try {
            ApplicationInfo ai = mAm.mContext.getPackageManager().getApplicationInfoAsUser(
                    callingPackage, PackageManager.MATCH_KNOWN_PACKAGES, userId);
            callerTargetSdkVersion = ai.targetSdkVersion;
        } catch (PackageManager.NameNotFoundException e) {
        }
        final String debugInfo =
                "[callingPackage: " + callingPackage
                        + "; callingUid: " + callingUid
                        + "; uidState: " + ProcessList.makeProcStateString(uidState)
                        + "; intent: " + intent
                        + "; code:" + reasonCodeToString(ret)
                        + "; tempAllowListReason:<"
                        + (tempAllowListReason == null ? null :
                                (tempAllowListReason.mReason
                                        + ",reasonCode:"
                                        + reasonCodeToString(tempAllowListReason.mReasonCode)
                                        + ",duration:" + tempAllowListReason.mDuration
                                        + ",callingUid:" + tempAllowListReason.mCallingUid))
                        + ">"
                        + "; targetSdkVersion:" + r.appInfo.targetSdkVersion
                        + "; callerTargetSdkVersion:" + callerTargetSdkVersion
                        + "; startForegroundCount:" + r.mStartForegroundCount
                        + "; bindFromPackage:" + bindFromPackage
                        + "]";
        if (!debugInfo.equals(r.mInfoAllowStartForeground)) {
            r.mLoggedInfoAllowStartForeground = false;
            r.mInfoAllowStartForeground = debugInfo;
        }
        return ret;
    }

    private @ReasonCode int shouldAllowFgsStartForegroundLocked(@ReasonCode int allowWhileInUse,
            int callingPid, int callingUid, String callingPackage,
            @Nullable ServiceRecord targetService) {
        int ret = allowWhileInUse;

        if (ret == REASON_DENIED) {
            final int uidState = mAm.getUidStateLocked(callingUid);
            // Is the calling UID at PROCESS_STATE_TOP or above?
            if (uidState <= PROCESS_STATE_TOP) {
                ret = getReasonCodeFromProcState(uidState);
            }
        }

        if (ret == REASON_DENIED) {
            final Integer allowedType = mAm.mProcessList.searchEachLruProcessesLOSP(false, app -> {
                if (app.uid == callingUid) {
                    final ProcessStateRecord state = app.mState;
                    if (state.isAllowedStartFgsState()) {
                        return getReasonCodeFromProcState(state.getAllowStartFgsState());
                    } else {
                        final ActiveInstrumentation instr = app.getActiveInstrumentation();
                        if (instr != null
                                && instr.mHasBackgroundForegroundServiceStartsPermission) {
                            return REASON_INSTR_BACKGROUND_FGS_PERMISSION;
                        }
                        final long lastInvisibleTime = app.mState.getLastInvisibleTime();
                        if (lastInvisibleTime > 0 && lastInvisibleTime < Long.MAX_VALUE) {
                            final long sinceLastInvisible = SystemClock.elapsedRealtime()
                                    - lastInvisibleTime;
                            if (sinceLastInvisible < mAm.mConstants.mFgToBgFgsGraceDuration) {
                                return REASON_ACTIVITY_VISIBILITY_GRACE_PERIOD;
                            }
                        }
                    }
                }
                return null;
            });
            if (allowedType != null) {
                ret = allowedType;
            }
        }

        if (ret == REASON_DENIED) {
            if (mAm.checkPermission(START_FOREGROUND_SERVICES_FROM_BACKGROUND, callingPid,
                    callingUid) == PERMISSION_GRANTED) {
                ret = REASON_BACKGROUND_FGS_PERMISSION;
            }
        }

        if (ret == REASON_DENIED) {
            if (mAm.mAtmInternal.hasSystemAlertWindowPermission(callingUid, callingPid,
                    callingPackage)) {
                ret = REASON_SYSTEM_ALERT_WINDOW_PERMISSION;
            }
        }

        // Check for CDM apps with either REQUEST_COMPANION_RUN_IN_BACKGROUND or
        // REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND.
        // Note: When a CDM app has REQUEST_COMPANION_RUN_IN_BACKGROUND, the app is also put
        // in the user-allowlist. However, in this case, we want to use the reason code
        // REASON_COMPANION_DEVICE_MANAGER, so this check needs to be before the
        // isAllowlistedForFgsStartLOSP check.
        if (ret == REASON_DENIED) {
            final boolean isCompanionApp = mAm.mInternal.isAssociatedCompanionApp(
                    UserHandle.getUserId(callingUid), callingUid);
            if (isCompanionApp) {
                if (isPermissionGranted(
                        REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND,
                        callingPid, callingUid)
                        || isPermissionGranted(REQUEST_COMPANION_RUN_IN_BACKGROUND,
                        callingPid, callingUid)) {
                    ret = REASON_COMPANION_DEVICE_MANAGER;
                }
            }
        }

        if (ret == REASON_DENIED) {
            ActivityManagerService.FgsTempAllowListItem item =
                    mAm.isAllowlistedForFgsStartLOSP(callingUid);
            if (item != null) {
                if (item == ActivityManagerService.FAKE_TEMP_ALLOW_LIST_ITEM) {
                    ret = REASON_SYSTEM_ALLOW_LISTED;
                } else {
                    ret = item.mReasonCode;
                }
            }
        }

        if (ret == REASON_DENIED) {
            if (UserManager.isDeviceInDemoMode(mAm.mContext)) {
                ret = REASON_DEVICE_DEMO_MODE;
            }
        }

        if (ret == REASON_DENIED) {
            // Is the calling UID a profile owner app?
            final boolean isProfileOwner = mAm.mInternal.isProfileOwner(callingUid);
            if (isProfileOwner) {
                ret = REASON_PROFILE_OWNER;
            }
        }

        if (ret == REASON_DENIED) {
            final AppOpsManager appOpsManager = mAm.getAppOpsManager();
            if (appOpsManager.checkOpNoThrow(AppOpsManager.OP_ACTIVATE_VPN, callingUid,
                    callingPackage) == AppOpsManager.MODE_ALLOWED) {
                ret = REASON_OP_ACTIVATE_VPN;
            } else if (appOpsManager.checkOpNoThrow(AppOpsManager.OP_ACTIVATE_PLATFORM_VPN,
                    callingUid, callingPackage) == AppOpsManager.MODE_ALLOWED) {
                ret = REASON_OP_ACTIVATE_PLATFORM_VPN;
            }
        }
        if (ret == REASON_DENIED) {
            if (mAm.mConstants.mFgsAllowOptOut
                    && targetService != null
                    && targetService.appInfo.hasRequestForegroundServiceExemption()) {
                ret = REASON_OPT_OUT_REQUESTED;
            }
        }
        return ret;
    }

    private boolean isPermissionGranted(String permission, int callingPid, int callingUid) {
        return mAm.checkPermission(permission, callingPid, callingUid) == PERMISSION_GRANTED;
    }

    private static boolean isFgsBgStart(@ReasonCode int code) {
        return code != REASON_PROC_STATE_PERSISTENT
                && code != REASON_PROC_STATE_PERSISTENT_UI
                && code != REASON_PROC_STATE_TOP
                && code != REASON_UID_VISIBLE;
    }

    // TODO: remove this notification after feature development is done
    private void showFgsBgRestrictedNotificationLocked(ServiceRecord r) {
        final Context context = mAm.mContext;
        final String title = "Foreground Service BG-Launch Restricted";
        final String content = "App restricted: " + r.mRecentCallingPackage;
        final long now = System.currentTimeMillis();
        final String bigText = DATE_FORMATTER.format(now) + " " + r.mInfoAllowStartForeground;
        final String groupKey = "com.android.fgs-bg-restricted";
        final Notification.Builder n =
                new Notification.Builder(context,
                        SystemNotificationChannels.ALERTS)
                        .setGroup(groupKey)
                        .setSmallIcon(R.drawable.stat_sys_vitals)
                        .setWhen(0)
                        .setColor(context.getColor(
                                com.android.internal.R.color.system_notification_accent_color))
                        .setTicker(title)
                        .setContentTitle(title)
                        .setContentText(content)
                        .setStyle(new Notification.BigTextStyle().bigText(bigText));
        context.getSystemService(NotificationManager.class).notifyAsUser(Long.toString(now),
                NOTE_FOREGROUND_SERVICE_BG_LAUNCH, n.build(), UserHandle.ALL);
    }

    private boolean isBgFgsRestrictionEnabled(ServiceRecord r) {
        return mAm.mConstants.mFlagFgsStartRestrictionEnabled
                // Checking service's targetSdkVersion.
                && CompatChanges.isChangeEnabled(FGS_BG_START_RESTRICTION_CHANGE_ID, r.appInfo.uid)
                && (!mAm.mConstants.mFgsStartRestrictionCheckCallerTargetSdk
                    // Checking callingUid's targetSdkVersion.
                    || CompatChanges.isChangeEnabled(
                            FGS_BG_START_RESTRICTION_CHANGE_ID, r.mRecentCallingUid));
    }

    private void logFgsBackgroundStart(ServiceRecord r) {
        // Only log if FGS is started from background.
        if (!isFgsBgStart(r.mAllowStartForeground)) {
            return;
        }
        if (!r.mLoggedInfoAllowStartForeground) {
            final String msg = "Background started FGS: "
                    + ((r.mAllowStartForeground != REASON_DENIED) ? "Allowed " : "Disallowed ")
                    + r.mInfoAllowStartForeground;
            Slog.wtfQuiet(TAG, msg);
            if (r.mAllowStartForeground != REASON_DENIED) {
                Slog.i(TAG, msg);
            } else {
                Slog.w(TAG, msg);
            }
            r.mLoggedInfoAllowStartForeground = true;
        }
    }

    /**
     * Log the statsd event for FGS.
     * @param r ServiceRecord
     * @param state one of ENTER/EXIT/DENIED event.
     * @param durationMs Only meaningful for EXIT event, the duration from ENTER and EXIT state.
     */
    private void logFGSStateChangeLocked(ServiceRecord r, int state, int durationMs) {
        if (!ActivityManagerUtils.shouldSamplePackageForAtom(
                r.packageName, mAm.mConstants.mFgsAtomSampleRate)) {
            return;
        }
        FrameworkStatsLog.write(FrameworkStatsLog.FOREGROUND_SERVICE_STATE_CHANGED,
                r.appInfo.uid,
                r.shortInstanceName,
                state,
                r.mAllowWhileInUsePermissionInFgs,
                r.mAllowStartForeground,
                r.appInfo.targetSdkVersion,
                r.mRecentCallingUid,
                r.mRecentCallerApplicationInfo != null
                        ? r.mRecentCallerApplicationInfo.targetSdkVersion : 0,
                r.mInfoTempFgsAllowListReason != null
                        ? r.mInfoTempFgsAllowListReason.mCallingUid : INVALID_UID,
                r.mFgsNotificationDeferred,
                r.mFgsNotificationShown,
                durationMs,
                r.mStartForegroundCount,
                ActivityManagerUtils.hashComponentNameForAtom(r.shortInstanceName));
    }

    boolean canAllowWhileInUsePermissionInFgsLocked(int callingPid, int callingUid,
            String callingPackage) {
        return shouldAllowFgsWhileInUsePermissionLocked(callingPackage, callingPid, callingUid,
                /* targetService */ null,
                /* allowBackgroundActivityStarts */ false)
                != REASON_DENIED;
    }
}
