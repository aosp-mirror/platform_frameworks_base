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
import static android.app.ActivityManager.PROCESS_CAPABILITY_BFSL;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_TOP;
import static android.app.ActivityManager.PROCESS_STATE_HEAVY_WEIGHT;
import static android.app.ActivityManager.PROCESS_STATE_RECEIVER;
import static android.app.ActivityManager.PROCESS_STATE_TOP;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_BIND_SERVICE;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_COMPONENT_DISABLED;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_EXECUTING_SERVICE;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_NONE;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_PROCESS_END;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_REMOVE_TASK;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_SHORT_FGS_TIMEOUT;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_START_SERVICE;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_STOP_SERVICE;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_UID_IDLE;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_UNBIND_SERVICE;
import static android.app.ForegroundServiceTypePolicy.FGS_TYPE_POLICY_CHECK_DEPRECATED;
import static android.app.ForegroundServiceTypePolicy.FGS_TYPE_POLICY_CHECK_DISABLED;
import static android.app.ForegroundServiceTypePolicy.FGS_TYPE_POLICY_CHECK_OK;
import static android.app.ForegroundServiceTypePolicy.FGS_TYPE_POLICY_CHECK_PERMISSION_DENIED_ENFORCED;
import static android.app.ForegroundServiceTypePolicy.FGS_TYPE_POLICY_CHECK_PERMISSION_DENIED_PERMISSIVE;
import static android.app.ForegroundServiceTypePolicy.FGS_TYPE_POLICY_CHECK_UNKNOWN;
import static android.content.Context.BIND_ALLOW_WHITELIST_MANAGEMENT;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE;
import static android.os.PowerExemptionManager.REASON_ACTIVE_DEVICE_ADMIN;
import static android.os.PowerExemptionManager.REASON_ACTIVITY_STARTER;
import static android.os.PowerExemptionManager.REASON_ACTIVITY_VISIBILITY_GRACE_PERIOD;
import static android.os.PowerExemptionManager.REASON_ALARM_MANAGER_ALARM_CLOCK;
import static android.os.PowerExemptionManager.REASON_ALLOWLISTED_PACKAGE;
import static android.os.PowerExemptionManager.REASON_BACKGROUND_ACTIVITY_PERMISSION;
import static android.os.PowerExemptionManager.REASON_BACKGROUND_FGS_PERMISSION;
import static android.os.PowerExemptionManager.REASON_CARRIER_PRIVILEGED_APP;
import static android.os.PowerExemptionManager.REASON_COMPANION_DEVICE_MANAGER;
import static android.os.PowerExemptionManager.REASON_CURRENT_INPUT_METHOD;
import static android.os.PowerExemptionManager.REASON_DENIED;
import static android.os.PowerExemptionManager.REASON_DEVICE_DEMO_MODE;
import static android.os.PowerExemptionManager.REASON_DEVICE_OWNER;
import static android.os.PowerExemptionManager.REASON_DISALLOW_APPS_CONTROL;
import static android.os.PowerExemptionManager.REASON_DPO_PROTECTED_APP;
import static android.os.PowerExemptionManager.REASON_FGS_BINDING;
import static android.os.PowerExemptionManager.REASON_INSTR_BACKGROUND_ACTIVITY_PERMISSION;
import static android.os.PowerExemptionManager.REASON_INSTR_BACKGROUND_FGS_PERMISSION;
import static android.os.PowerExemptionManager.REASON_OPT_OUT_REQUESTED;
import static android.os.PowerExemptionManager.REASON_OP_ACTIVATE_PLATFORM_VPN;
import static android.os.PowerExemptionManager.REASON_OP_ACTIVATE_VPN;
import static android.os.PowerExemptionManager.REASON_PACKAGE_INSTALLER;
import static android.os.PowerExemptionManager.REASON_PROC_STATE_PERSISTENT;
import static android.os.PowerExemptionManager.REASON_PROC_STATE_PERSISTENT_UI;
import static android.os.PowerExemptionManager.REASON_PROC_STATE_TOP;
import static android.os.PowerExemptionManager.REASON_PROFILE_OWNER;
import static android.os.PowerExemptionManager.REASON_ROLE_EMERGENCY;
import static android.os.PowerExemptionManager.REASON_SERVICE_LAUNCH;
import static android.os.PowerExemptionManager.REASON_START_ACTIVITY_FLAG;
import static android.os.PowerExemptionManager.REASON_SYSTEM_ALERT_WINDOW_PERMISSION;
import static android.os.PowerExemptionManager.REASON_SYSTEM_ALLOW_LISTED;
import static android.os.PowerExemptionManager.REASON_SYSTEM_EXEMPT_APP_OP;
import static android.os.PowerExemptionManager.REASON_SYSTEM_MODULE;
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
import static android.content.flags.Flags.enableBindPackageIsolatedProcess;


import static com.android.internal.messages.nano.SystemMessageProto.SystemMessage.NOTE_FOREGROUND_SERVICE_BG_LAUNCH;
import static com.android.internal.util.FrameworkStatsLog.FOREGROUND_SERVICE_STATE_CHANGED__FGS_START_API__FGSSTARTAPI_DELEGATE;
import static com.android.internal.util.FrameworkStatsLog.FOREGROUND_SERVICE_STATE_CHANGED__FGS_START_API__FGSSTARTAPI_NA;
import static com.android.internal.util.FrameworkStatsLog.FOREGROUND_SERVICE_STATE_CHANGED__FGS_START_API__FGSSTARTAPI_NONE;
import static com.android.internal.util.FrameworkStatsLog.FOREGROUND_SERVICE_STATE_CHANGED__FGS_START_API__FGSSTARTAPI_START_FOREGROUND_SERVICE;
import static com.android.internal.util.FrameworkStatsLog.FOREGROUND_SERVICE_STATE_CHANGED__FGS_START_API__FGSSTARTAPI_START_SERVICE;
import static com.android.internal.util.FrameworkStatsLog.FOREGROUND_SERVICE_STATE_CHANGED__STATE__DENIED;
import static com.android.internal.util.FrameworkStatsLog.FOREGROUND_SERVICE_STATE_CHANGED__STATE__ENTER;
import static com.android.internal.util.FrameworkStatsLog.FOREGROUND_SERVICE_STATE_CHANGED__STATE__EXIT;
import static com.android.internal.util.FrameworkStatsLog.FOREGROUND_SERVICE_STATE_CHANGED__STATE__TIMED_OUT;
import static com.android.internal.util.FrameworkStatsLog.SERVICE_REQUEST_EVENT_REPORTED;
import static com.android.internal.util.FrameworkStatsLog.SERVICE_REQUEST_EVENT_REPORTED__PACKAGE_STOPPED_STATE__PACKAGE_STATE_NORMAL;
import static com.android.internal.util.FrameworkStatsLog.SERVICE_REQUEST_EVENT_REPORTED__PACKAGE_STOPPED_STATE__PACKAGE_STATE_STOPPED;
import static com.android.internal.util.FrameworkStatsLog.SERVICE_REQUEST_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_COLD;
import static com.android.internal.util.FrameworkStatsLog.SERVICE_REQUEST_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_HOT;
import static com.android.internal.util.FrameworkStatsLog.SERVICE_REQUEST_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_WARM;
import static com.android.internal.util.FrameworkStatsLog.SERVICE_REQUEST_EVENT_REPORTED__REQUEST_TYPE__BIND;
import static com.android.internal.util.FrameworkStatsLog.SERVICE_REQUEST_EVENT_REPORTED__REQUEST_TYPE__START;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BACKGROUND_CHECK;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_FOREGROUND_SERVICE;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_MU;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PERMISSIONS_REVIEW;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PROCESSES;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_SERVICE;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_SERVICE_EXECUTING;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_MU;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_SERVICE;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_SERVICE_EXECUTING;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UptimeMillisLong;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityManagerInternal.OomAdjReason;
import android.app.ActivityManagerInternal.ServiceNotificationPolicy;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.BackgroundStartPrivileges;
import android.app.ForegroundServiceDelegationOptions;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.ForegroundServiceTypePolicy;
import android.app.ForegroundServiceTypePolicy.ForegroundServicePolicyCheckCode;
import android.app.ForegroundServiceTypePolicy.ForegroundServiceTypePermission;
import android.app.ForegroundServiceTypePolicy.ForegroundServiceTypePolicyInfo;
import android.app.IApplicationThread;
import android.app.IForegroundServiceObserver;
import android.app.IServiceConnection;
import android.app.InvalidForegroundServiceTypeException;
import android.app.MissingForegroundServiceTypeException;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteServiceException.ForegroundServiceDidNotStartInTimeException;
import android.app.RemoteServiceException.ForegroundServiceDidNotStopInTimeException;
import android.app.Service;
import android.app.ServiceStartArgs;
import android.app.StartForegroundCalledOnStoppedServiceException;
import android.app.admin.DevicePolicyEventLogger;
import android.app.compat.CompatChanges;
import android.app.usage.UsageEvents;
import android.appwidget.AppWidgetManagerInternal;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.Overridable;
import android.content.ComponentName;
import android.content.ComponentName.WithComponentName;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.ServiceInfo.ForegroundServiceType;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerExemptionManager;
import android.os.PowerExemptionManager.ReasonCode;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.TransactionTooLargeException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.ondeviceintelligence.OnDeviceSandboxedInferenceService;
import android.service.voice.HotwordDetectionService;
import android.service.voice.VisualQueryDetectionService;
import android.service.wearable.WearableSensingService;
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
import com.android.internal.app.procstats.ServiceState;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.SomeArgs;
import com.android.internal.os.TimeoutRecord;
import com.android.internal.os.TransferPipe;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.AppStateTracker;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.am.ActivityManagerService.ItemMatcher;
import com.android.server.am.LowMemDetector.MemFactor;
import com.android.server.am.ServiceRecord.ShortFgsInfo;
import com.android.server.am.ServiceRecord.TimeLimitedFgsInfo;
import com.android.server.pm.KnownPackages;
import com.android.server.uri.NeededUriGrants;
import com.android.server.utils.AnrTimer;
import com.android.server.wm.ActivityServiceConnectionsHolder;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public final class ActiveServices {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActiveServices" : TAG_AM;
    private static final String TAG_MU = TAG + POSTFIX_MU;
    static final String TAG_SERVICE = TAG + POSTFIX_SERVICE;
    private static final String TAG_SERVICE_EXECUTING = TAG + POSTFIX_SERVICE_EXECUTING;

    private static final boolean DEBUG_DELAYED_SERVICE = DEBUG_SERVICE;
    private static final boolean DEBUG_DELAYED_STARTS = DEBUG_DELAYED_SERVICE;

    private static final boolean DEBUG_SHORT_SERVICE = DEBUG_SERVICE;

    private static final boolean LOG_SERVICE_START_STOP = DEBUG_SERVICE;

    // Foreground service types that always get immediate notification display,
    // expressed in the same bitmask format that ServiceRecord.foregroundServiceType
    // uses.
    static final int FGS_IMMEDIATE_DISPLAY_MASK =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;

    // Keep track of number of foreground services and number of apps that have foreground
    // services in the device. This field is made to be directly accessed without holding AMS lock.
    static final AtomicReference<Pair<Integer, Integer>> sNumForegroundServices =
            new AtomicReference(new Pair<>(0, 0));

    // Foreground service is stopped for unknown reason.
    static final int FGS_STOP_REASON_UNKNOWN = 0;
    // Foreground service is stopped by app calling Service.stopForeground().
    static final int FGS_STOP_REASON_STOP_FOREGROUND = 1;
    // Foreground service is stopped because service is brought down either by app calling
    // stopService() or unbindService(), or service process is killed by the system.
    static final int FGS_STOP_REASON_STOP_SERVICE = 2;
    /**
     * The list of FGS stop reasons.
     */
    @IntDef(flag = true, prefix = { "FGS_STOP_REASON_" }, value = {
            FGS_STOP_REASON_UNKNOWN,
            FGS_STOP_REASON_STOP_FOREGROUND,
            FGS_STOP_REASON_STOP_SERVICE,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface FgsStopReason {}

    /**
     * The policy to be applied to the service bindings; this one means it follows the legacy
     * behavior.
     */
    static final int SERVICE_BIND_OOMADJ_POLICY_LEGACY = 0;

    /**
     * The policy to be applied to the service bindings; this one means we'll skip
     * updating the target process's oom adj score / process state for its {@link Service#onCreate}.
     */
    static final int SERVICE_BIND_OOMADJ_POLICY_SKIP_OOM_UPDATE_ON_CREATE = 1;

    /**
     * The policy to be applied to the service bindings; this one means we'll skip
     * updating the target process's oom adj score / process state for its {@link Service#onBind}.
     */
    static final int SERVICE_BIND_OOMADJ_POLICY_SKIP_OOM_UPDATE_ON_BIND = 1 << 1;

    /**
     * The policy to be applied to the service bindings; this one means we'll skip
     * updating the target process's oom adj score / process state on setting up the service
     * connection between the client and the service host process.
     */
    static final int SERVICE_BIND_OOMADJ_POLICY_SKIP_OOM_UPDATE_ON_CONNECT = 1 << 2;
    /**
     * The policy to be applied to the service bindings; this one means the caller
     * will be frozen upon calling the bindService APIs.
     */
    static final int SERVICE_BIND_OOMADJ_POLICY_FREEZE_CALLER = 1 << 3;

    @IntDef(flag = true, prefix = { "SERVICE_BIND_OOMADJ_POLICY_" }, value = {
            SERVICE_BIND_OOMADJ_POLICY_LEGACY,
            SERVICE_BIND_OOMADJ_POLICY_SKIP_OOM_UPDATE_ON_CREATE,
            SERVICE_BIND_OOMADJ_POLICY_SKIP_OOM_UPDATE_ON_BIND,
            SERVICE_BIND_OOMADJ_POLICY_SKIP_OOM_UPDATE_ON_CONNECT,
            SERVICE_BIND_OOMADJ_POLICY_FREEZE_CALLER,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ServiceBindingOomAdjPolicy {}

    @ServiceBindingOomAdjPolicy
    static final int DEFAULT_SERVICE_NO_BUMP_BIND_POLICY_FLAG =
            SERVICE_BIND_OOMADJ_POLICY_SKIP_OOM_UPDATE_ON_CREATE
            | SERVICE_BIND_OOMADJ_POLICY_SKIP_OOM_UPDATE_ON_BIND
            | SERVICE_BIND_OOMADJ_POLICY_SKIP_OOM_UPDATE_ON_CONNECT;

    @ServiceBindingOomAdjPolicy
    static final int DEFAULT_SERVICE_CACHED_BIND_POLICY_FLAG =
            SERVICE_BIND_OOMADJ_POLICY_SKIP_OOM_UPDATE_ON_CREATE
            | SERVICE_BIND_OOMADJ_POLICY_SKIP_OOM_UPDATE_ON_BIND;

    /**
     * Disables foreground service background starts from BOOT_COMPLETED broadcasts for all types
     * except:
     * <ul>
     *     <li>{@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_LOCATION}</li>
     *     <li>{@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE}</li>
     *     <li>{@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING}</li>
     *     <li>{@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_HEALTH}</li>
     *     <li>{@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED}</li>
     *     <li>{@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_SPECIAL_USE}</li>
     * </ul>
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = VERSION_CODES.VANILLA_ICE_CREAM)
    @Overridable
    public static final long FGS_BOOT_COMPLETED_RESTRICTIONS = 296558535L;

    /**
     * Disables foreground service background starts in System Alert Window for all types
     * unless it already has a System Overlay Window.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = VERSION_CODES.VANILLA_ICE_CREAM)
    @Overridable
    public static final long FGS_SAW_RESTRICTIONS = 319471980L;

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
     * Map of ForegroundServiceDelegation to the delegation ServiceRecord. The delegation
     * ServiceRecord has flag isFgsDelegate set to true.
     */
    final ArrayMap<ForegroundServiceDelegation, ServiceRecord> mFgsDelegations = new ArrayMap<>();

    /**
     * A global counter for generating sequence numbers to uniquely identify bindService requests.
     * It is purely for logging purposes.
     */
    @GuardedBy("mAm")
    private long mBindServiceSeqCounter = 0;

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
     * Foreground service observers: track what apps have FGSes
     */
    final RemoteCallbackList<IForegroundServiceObserver> mFgsObservers =
            new RemoteCallbackList<>();

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

    // Used for logging foreground service API starts and end
    private final ForegroundServiceTypeLoggerModule mFGSLogger;

    /**
     * For keeping ActiveForegroundApps retaining state while the screen is off.
     */
    boolean mScreenOn = true;

    /** Amount of time to allow a last ANR message to exist before freeing the memory. */
    static final int LAST_ANR_LIFETIME_DURATION_MSECS = 2 * 60 * 60 * 1000; // Two hours

    String mLastAnrDump;

    AppWidgetManagerInternal mAppWidgetManagerInternal;

    /**
     * The available ANR timers.
     */
    // ActivityManagerConstants.SERVICE_TIMEOUT/ActivityManagerConstants.SERVICE_BACKGROUND_TIMEOUT
    private final ProcessAnrTimer mActiveServiceAnrTimer;
    // see ServiceRecord$ShortFgsInfo#getAnrTime()
    private final ServiceAnrTimer mShortFGSAnrTimer;
    // ActivityManagerConstants.DEFAULT_SERVICE_START_FOREGROUND_TIMEOUT_MS
    private final ServiceAnrTimer mServiceFGAnrTimer;
    // see ServiceRecord#getEarliestStopTypeAndTime()
    private final ServiceAnrTimer mFGSAnrTimer;

    /**
     * Mapping of uid to {fgs_type, fgs_info} for time limited fgs types such as dataSync and
     * mediaProcessing.
     */
    final SparseArray<SparseArray<TimeLimitedFgsInfo>> mTimeLimitedFgsInfo = new SparseArray<>();

    /**
     * Foreground services of certain types will now have a time limit. If the foreground service
     * of the offending type is not stopped within the allocated time limit, it will receive a
     * callback via {@link Service#onTimeout(int, int)} and it must then be stopped within a few
     * seconds. If an app fails to do so, it will be declared an ANR.
     *
     * @see Service#onTimeout(int, int) onTimeout callback for additional details
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = VERSION_CODES.VANILLA_ICE_CREAM)
    static final long FGS_INTRODUCE_TIME_LIMITS = 317799821L;

    // allowlisted packageName.
    ArraySet<String> mAllowListWhileInUsePermissionInFgs = new ArraySet<>();

    String mCachedDeviceProvisioningPackage;

    // TODO: remove this after feature development is done
    private static final SimpleDateFormat DATE_FORMATTER =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * The BG-launch FGS restriction feature is going to be allowed only for apps targetSdkVersion
     * is higher than R.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = android.os.Build.VERSION_CODES.S)
    @Overridable
    static final long FGS_BG_START_RESTRICTION_CHANGE_ID = 170668199L;

    /**
     * If a service can not become foreground service due to BG-FGS-launch restriction or other
     * reasons, throws an IllegalStateException.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = android.os.Build.VERSION_CODES.S)
    static final long FGS_START_EXCEPTION_CHANGE_ID = 174041399L;

    /**
     * If enabled, the FGS type check against the manifest FSG type will be enabled for
     * instant apps too. Before U, this check was only done for non-instant apps.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = VERSION_CODES.TIRAMISU)
    static final long FGS_TYPE_CHECK_FOR_INSTANT_APPS = 261055255L;

    final Runnable mLastAnrDumpClearer = new Runnable() {
        @Override public void run() {
            synchronized (mAm) {
                mLastAnrDump = null;
            }
        }
    };

    /**
     * Reference to the AppStateTracker service. No lock is needed as we'll assign with the same
     * instance to it always.
     */
    AppStateTracker mAppStateTracker;

    /**
     * Watch for apps being put into background restricted, so we can step their fg
     * services down.
     */
    class BackgroundRestrictedListener implements AppStateTracker.BackgroundRestrictedAppListener {
        @Override
        public void updateBackgroundRestrictedForUidPackage(int uid, String packageName,
                boolean restricted) {
            synchronized (mAm) {
                mAm.mProcessList.updateBackgroundRestrictedForUidPackageLocked(
                        uid, packageName, restricted);
                if (!isForegroundServiceAllowedInBackgroundRestricted(uid, packageName)
                        && !isTempAllowedByAlarmClock(uid)) {
                    stopAllForegroundServicesLocked(uid, packageName);
                }
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
                // If the FGS is started by temp allowlist of alarm-clock
                // (REASON_ALARM_MANAGER_ALARM_CLOCK), allow it to continue and do not stop it,
                // even the app is background-restricted.
                if (r.isForeground
                        && r.mAllowStartForegroundAtEntering != REASON_ALARM_MANAGER_ALARM_CLOCK
                        && !isDeviceProvisioningPackage(r.packageName)) {
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
            setServiceForegroundInnerLocked(r, 0, null, 0, 0,
                    0);
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
        final ArrayList<String> mPendingRemoveForegroundApps = new ArrayList<>();

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
                        final ServiceRecord.StartItem si = r.pendingStarts.get(0);
                        startServiceInnerLocked(this, si.intent, r, false, true, si.callingId,
                                si.mCallingProcessName, si.mCallingProcessState,
                                r.startRequested, si.mCallingPackageName);
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
        this.mFGSLogger = new ForegroundServiceTypeLoggerModule();
        this.mActiveServiceAnrTimer = new ProcessAnrTimer(service,
                ActivityManagerService.SERVICE_TIMEOUT_MSG,
                "SERVICE_TIMEOUT");
        this.mShortFGSAnrTimer = new ServiceAnrTimer(service,
                ActivityManagerService.SERVICE_SHORT_FGS_ANR_TIMEOUT_MSG,
                "SHORT_FGS_TIMEOUT");
        this.mServiceFGAnrTimer = new ServiceAnrTimer(service,
                ActivityManagerService.SERVICE_FOREGROUND_TIMEOUT_MSG,
                "SERVICE_FOREGROUND_TIMEOUT");
        this.mFGSAnrTimer = new ServiceAnrTimer(service,
                ActivityManagerService.SERVICE_FGS_CRASH_TIMEOUT_MSG,
                "FGS_TIMEOUT");
    }

    void systemServicesReady() {
        getAppStateTracker().addBackgroundRestrictedAppListener(new BackgroundRestrictedListener());
        mAppWidgetManagerInternal = LocalServices.getService(AppWidgetManagerInternal.class);
        setAllowListWhileInUsePermissionInFgs();
        initSystemExemptedFgsTypePermission();
        initMediaProjectFgsTypeCustomPermission();
    }

    private AppStateTracker getAppStateTracker() {
        if (mAppStateTracker == null) {
            mAppStateTracker = LocalServices.getService(AppStateTracker.class);
        }
        return mAppStateTracker;
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
        final AppStateTracker appStateTracker = getAppStateTracker();
        if (appStateTracker != null) {
            return appStateTracker.isAppBackgroundRestricted(uid, packageName);
        }
        return false;
    }

    void updateAppRestrictedAnyInBackgroundLocked(final int uid, final String packageName) {
        final boolean restricted = appRestrictedAnyInBackground(uid, packageName);
        final UidRecord uidRec = mAm.mProcessList.getUidRecordLOSP(uid);
        if (uidRec != null) {
            final ProcessRecord app = uidRec.getProcessInPackage(packageName);
            if (app != null) {
                app.mState.setBackgroundRestricted(restricted);
            }
        }
    }

    static String getProcessNameForService(ServiceInfo sInfo, ComponentName name,
            String callingPackage, String instanceName, boolean isSdkSandbox,
            boolean inSharedIsolatedProcess, boolean inPrivateSharedIsolatedProcess) {
        if (isSdkSandbox) {
            // For SDK sandbox, the process name is passed in as the instanceName
            return instanceName;
        }
        if ((sInfo.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) == 0
                || (inPrivateSharedIsolatedProcess && !isDefaultProcessService(sInfo))) {
            // For regular processes, or private package-shared isolated processes, just the name
            // in sInfo
            return sInfo.processName;
        }
        // Isolated processes remain.
        if (inSharedIsolatedProcess) {
            // Shared isolated processes are scoped to the calling package
            return callingPackage + ":ishared:" + instanceName;
        } else {
            return sInfo.processName + ":" + name.getClassName();
        }
    }

    private static boolean isDefaultProcessService(ServiceInfo serviceInfo) {
        return serviceInfo.applicationInfo.processName.equals(serviceInfo.processName);
    }

    private static void traceInstant(@NonNull String message, @NonNull ServiceRecord service) {
        if (!Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
            return;
        }
        final String serviceName = (service.getComponentName() != null)
                ? service.getComponentName().toShortString() : "(?)";
        Trace.instant(Trace.TRACE_TAG_ACTIVITY_MANAGER, message + serviceName);
    }

    ComponentName startServiceLocked(IApplicationThread caller, Intent service, String resolvedType,
            int callingPid, int callingUid, boolean fgRequired, String callingPackage,
            @Nullable String callingFeatureId, final int userId, boolean isSdkSandboxService,
            int sdkSandboxClientAppUid, String sdkSandboxClientAppPackage, String instanceName)
            throws TransactionTooLargeException {
        return startServiceLocked(caller, service, resolvedType, callingPid, callingUid, fgRequired,
                callingPackage, callingFeatureId, userId, BackgroundStartPrivileges.NONE,
                isSdkSandboxService, sdkSandboxClientAppUid, sdkSandboxClientAppPackage,
                instanceName);
    }

    ComponentName startServiceLocked(IApplicationThread caller, Intent service, String resolvedType,
            int callingPid, int callingUid, boolean fgRequired,
            String callingPackage, @Nullable String callingFeatureId, final int userId,
            BackgroundStartPrivileges backgroundStartPrivileges)
            throws TransactionTooLargeException {
        return startServiceLocked(caller, service, resolvedType, callingPid, callingUid, fgRequired,
                callingPackage, callingFeatureId, userId, backgroundStartPrivileges,
                false /* isSdkSandboxService */, INVALID_UID, null, null);
    }

    ComponentName startServiceLocked(IApplicationThread caller, Intent service, String resolvedType,
            int callingPid, int callingUid, boolean fgRequired,
            String callingPackage, @Nullable String callingFeatureId, final int userId,
            BackgroundStartPrivileges backgroundStartPrivileges, boolean isSdkSandboxService,
            int sdkSandboxClientAppUid, String sdkSandboxClientAppPackage, String instanceName)
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

        ServiceLookupResult res = retrieveServiceLocked(service, instanceName, isSdkSandboxService,
                sdkSandboxClientAppUid, sdkSandboxClientAppPackage, resolvedType, callingPackage,
                callingPid, callingUid, userId, true, callerFg, false, false, null, false, false);
        if (res == null) {
            return null;
        }
        if (res.record == null) {
            return new ComponentName("!", res.permission != null
                    ? res.permission : "private to package");
        }

        ServiceRecord r = res.record;

        traceInstant("startService(): ", r);

        // Note, when startService() or startForegroundService() is called on an already
        // running SHORT_SERVICE FGS, the call will succeed (i.e. we won't throw
        // ForegroundServiceStartNotAllowedException), even when the service is already timed
        // out. This is because these APIs will essentially only change the "started" state
        // of the service, and it won't affect "the foreground-ness" of the service, or the type
        // of the FGS.
        // However, this call will still _not_ extend the SHORT_SERVICE timeout either.
        // Also, if the app tries to change the type of the FGS later (using
        // Service.startForeground()), at that point we will consult the BFSL check and the timeout
        // and make the necessary decisions.
        setFgsRestrictionLocked(callingPackage, callingPid, callingUid, service, r, userId,
                backgroundStartPrivileges, false /* isBindService */);

        if (!mAm.mUserController.exists(r.userId)) {
            Slog.w(TAG, "Trying to start service with non-existent user! " + r.userId);
            return null;
        }

        // For the SDK sandbox, we start the service on behalf of the client app.
        final int appUid = isSdkSandboxService ? sdkSandboxClientAppUid : r.appInfo.uid;
        final String appPackageName =
                isSdkSandboxService ? sdkSandboxClientAppPackage : r.packageName;
        int appTargetSdkVersion = r.appInfo.targetSdkVersion;
        if (isSdkSandboxService) {
            try {
                appTargetSdkVersion = AppGlobals.getPackageManager().getApplicationInfo(
                        appPackageName, ActivityManagerService.STOCK_PM_FLAGS,
                        userId).targetSdkVersion;
            } catch (RemoteException ignored) {
            }
        }

        // If we're starting indirectly (e.g. from PendingIntent), figure out whether
        // we're launching into an app in a background state.  This keys off of the same
        // idleness state tracking as e.g. O+ background service start policy.
        final boolean bgLaunch = !mAm.isUidActiveLOSP(appUid);

        // If the app has strict background restrictions, we treat any bg service
        // start analogously to the legacy-app forced-restrictions case, regardless
        // of its target SDK version.
        boolean forcedStandby = false;
        if (bgLaunch
                && appRestrictedAnyInBackground(appUid, appPackageName)
                && !isTempAllowedByAlarmClock(appUid)
                && !isDeviceProvisioningPackage(appPackageName)) {
            if (DEBUG_FOREGROUND_SERVICE) {
                Slog.d(TAG, "Forcing bg-only service start only for " + r.shortInstanceName
                        + " : bgLaunch=" + bgLaunch + " callerFg=" + callerFg);
            }
            forcedStandby = true;
        }

        if (fgRequired) {
            logFgsBackgroundStart(r);
            if (!r.isFgsAllowedStart() && isBgFgsRestrictionEnabled(r, callingUid)) {
                String msg = "startForegroundService() not allowed due to "
                        + "mAllowStartForeground false: service "
                        + r.shortInstanceName;
                Slog.w(TAG, msg);
                showFgsBgRestrictedNotificationLocked(r);
                logFGSStateChangeLocked(r,
                        FOREGROUND_SERVICE_STATE_CHANGED__STATE__DENIED,
                        0, FGS_STOP_REASON_UNKNOWN, FGS_TYPE_POLICY_CHECK_UNKNOWN,
                        FOREGROUND_SERVICE_STATE_CHANGED__FGS_START_API__FGSSTARTAPI_NA,
                        false /* fgsRestrictionRecalculated */
                );
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
                    AppOpsManager.OP_START_FOREGROUND, appUid, appPackageName);
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
        // arbitrary service.
        if (forcedStandby || (!r.startRequested && !fgRequired)) {
            // Before going further -- if this app is not allowed to start services in the
            // background, then at this point we aren't going to let it period.
            final int allowed = mAm.getAppStartModeLOSP(appUid, appPackageName, appTargetSdkVersion,
                    callingPid, false, false, forcedStandby);
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
                UidRecord uidRec = mAm.mProcessList.getUidRecordLOSP(appUid);
                return new ComponentName("?", "app is in background uid " + uidRec);
            }
        }

        // At this point we've applied allowed-to-start policy based on whether this was
        // an ordinary startService() or a startForegroundService().  Now, only require that
        // the app follow through on the startForegroundService() -> startForeground()
        // contract if it actually targets O+.
        if (appTargetSdkVersion < Build.VERSION_CODES.O && fgRequired) {
            if (DEBUG_BACKGROUND_CHECK || DEBUG_FOREGROUND_SERVICE) {
                Slog.i(TAG, "startForegroundService() but host targets "
                        + appTargetSdkVersion + " - not requiring startForeground()");
            }
            fgRequired = false;
        }

        final ProcessRecord callingApp;
        synchronized (mAm.mPidsSelfLocked) {
            callingApp = mAm.mPidsSelfLocked.get(callingPid);
        }
        final String callingProcessName = callingApp != null
                ? callingApp.processName : callingPackage;
        final int callingProcessState =
                callingApp != null && callingApp.getThread() != null && !callingApp.isKilled()
                ? callingApp.mState.getCurProcState() : ActivityManager.PROCESS_STATE_UNKNOWN;
        r.updateProcessStateOnRequest();

        // The package could be frozen (meaning it's doing surgery), defer the actual
        // start until the package is unfrozen.
        if (deferServiceBringupIfFrozenLocked(r, service, callingPackage, callingFeatureId,
                callingUid, callingPid, callingProcessName,
                callingProcessState, fgRequired, callerFg, userId,
                backgroundStartPrivileges, false, null)) {
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

        // If what the client try to start/connect was an alias, then we need to return the
        // alias component name to the client, not the "target" component name, which is
        // what realResult contains.
        final ComponentName realResult =
                startServiceInnerLocked(r, service, callingUid, callingPid,
                        callingProcessName, callingProcessState,
                        fgRequired, callerFg,
                        backgroundStartPrivileges, callingPackage);
        if (res.aliasComponent != null
                && !realResult.getPackageName().startsWith("!")
                && !realResult.getPackageName().startsWith("?")) {
            return res.aliasComponent;
        } else {
            return realResult;
        }
    }

    private boolean shouldAllowBootCompletedStart(ServiceRecord r, int foregroundServiceType) {
        @PowerExemptionManager.ReasonCode final int fgsStartReasonCode = r.getFgsAllowStart();
        if (Flags.fgsBootCompleted()
                && CompatChanges.isChangeEnabled(FGS_BOOT_COMPLETED_RESTRICTIONS, r.appInfo.uid)
                && fgsStartReasonCode == PowerExemptionManager.REASON_BOOT_COMPLETED) {
            // Filter through types
            return ((foregroundServiceType & mAm.mConstants.FGS_BOOT_COMPLETED_ALLOWLIST) != 0);
        }
        // Not BOOT_COMPLETED
        return true;
    }

    private ComponentName startServiceInnerLocked(ServiceRecord r, Intent service,
            int callingUid, int callingPid, String callingProcessName,
            int callingProcessState, boolean fgRequired, boolean callerFg,
            BackgroundStartPrivileges backgroundStartPrivileges, String callingPackage)
            throws TransactionTooLargeException {
        NeededUriGrants neededGrants = mAm.mUgmInternal.checkGrantUriPermissionFromIntent(
                service, callingUid, r.packageName, r.userId);
        if (unscheduleServiceRestartLocked(r, callingUid, false)) {
            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "START SERVICE WHILE RESTART PENDING: " + r);
        }
        final boolean wasStartRequested = r.startRequested;
        r.lastActivity = SystemClock.uptimeMillis();
        r.startRequested = true;
        r.delayedStop = false;
        r.fgRequired = fgRequired;
        r.pendingStarts.add(new ServiceRecord.StartItem(r, false, r.makeNextStartId(),
                service, neededGrants, callingUid, callingProcessName, callingPackage,
                callingProcessState));

        // We want to allow scheduling user-initiated jobs when the app is running a
        // foreground service that was started in the same conditions that allows for scheduling
        // UI jobs. More explicitly, we want to allow scheduling UI jobs when the app is running
        // an FGS that started when the app was in the TOP or a BAL-approved state.
        final boolean isFgs = r.isForeground || r.fgRequired;
        if (isFgs) {
            // As of Android UDC, the conditions required for the while-in-use permissions
            // are the same conditions that we want, so we piggyback on that logic.
            // Use that as a shortcut if possible to avoid having to recheck all the conditions.
            final boolean whileInUseAllowsUiJobScheduling =
                    ActivityManagerService.doesReasonCodeAllowSchedulingUserInitiatedJobs(
                            r.getFgsAllowWiu_forStart(), callingUid);
            r.updateAllowUiJobScheduling(whileInUseAllowsUiJobScheduling
                    || mAm.canScheduleUserInitiatedJobs(callingUid, callingPid, callingPackage));
        } else {
            r.updateAllowUiJobScheduling(false);
        }

        if (fgRequired) {
            // We are now effectively running a foreground service.
            synchronized (mAm.mProcessStats.mLock) {
                final ServiceState stracker = r.getTracker();
                if (stracker != null) {
                    stracker.setForeground(true, mAm.mProcessStats.getMemFactorLocked(),
                            SystemClock.uptimeMillis()); // Use current time, not lastActivity.
                }
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
        if (backgroundStartPrivileges.allowsAny()) {
            r.allowBgActivityStartsOnServiceStart(backgroundStartPrivileges);
        }
        ComponentName cmp = startServiceInnerLocked(smap, service, r, callerFg, addToStarting,
                callingUid, callingProcessName, callingProcessState,
                wasStartRequested, callingPackage);
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
                                    final long identity = mAm.mInjector.clearCallingIdentity();
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
                                                        true /* enqueueOomAdj */,
                                                        SERVICE_BIND_OOMADJ_POLICY_LEGACY);
                                            } catch (RemoteException e) {
                                                /* ignore - local call */
                                            } finally {
                                                /* Will be a no-op if nothing pending */
                                                mAm.updateOomAdjPendingTargetsLocked(
                                                        OOM_ADJ_REASON_START_SERVICE);
                                            }
                                        } else {
                                            unbindServiceLocked(connection);
                                        }
                                    } finally {
                                        mAm.mInjector.restoreCallingIdentity(identity);
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
            int callingUid, int callingPid, String callingProcessName,
            int callingProcessState, boolean fgRequired, boolean callerFg, int userId,
            BackgroundStartPrivileges backgroundStartPrivileges,
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
                                    true /* enqueueOomAdj */,
                                    SERVICE_BIND_OOMADJ_POLICY_LEGACY);
                        } catch (TransactionTooLargeException e) {
                            /* ignore - local call */
                        } finally {
                            /* Will be a no-op if nothing pending */
                            mAm.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_START_SERVICE);
                        }
                    } else { // Starting a service
                        try {
                            startServiceInnerLocked(s, serviceIntent, callingUid, callingPid,
                                    callingProcessName, callingProcessState, fgRequired, callerFg,
                                    backgroundStartPrivileges, callingPackage);
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
            boolean callerFg, boolean addToStarting, int callingUid, String callingProcessName,
            int callingProcessState, boolean wasStartRequested, String callingPackage)
            throws TransactionTooLargeException {
        synchronized (mAm.mProcessStats.mLock) {
            final ServiceState stracker = r.getTracker();
            if (stracker != null) {
                stracker.setStarted(true, mAm.mProcessStats.getMemFactorLocked(),
                        SystemClock.uptimeMillis()); // Use current time, not lastActivity.
            }
        }
        r.callStart = false;

        final int uid = r.appInfo.uid;
        final String packageName = r.name.getPackageName();
        final String serviceName = r.name.getClassName();
        FrameworkStatsLog.write(FrameworkStatsLog.SERVICE_STATE_CHANGED, uid, packageName,
                serviceName, FrameworkStatsLog.SERVICE_STATE_CHANGED__STATE__START);
        mAm.mBatteryStatsService.noteServiceStartRunning(uid, packageName, serviceName);
        final ProcessRecord hostApp = r.app;
        final boolean wasStopped = hostApp == null ? wasStopped(r) : false;
        final boolean firstLaunch =
                hostApp == null ? !mAm.wasPackageEverLaunched(r.packageName, r.userId) : false;

        String error = bringUpServiceLocked(r, service.getFlags(), callerFg,
                false /* whileRestarting */,
                false /* permissionsReviewRequired */,
                false /* packageFrozen */,
                true /* enqueueOomAdj */,
                SERVICE_BIND_OOMADJ_POLICY_LEGACY);
        /* Will be a no-op if nothing pending */
        mAm.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_START_SERVICE);
        if (error != null) {
            return new ComponentName("!!", error);
        }

        final int packageState = wasStopped
                ? SERVICE_REQUEST_EVENT_REPORTED__PACKAGE_STOPPED_STATE__PACKAGE_STATE_STOPPED
                : SERVICE_REQUEST_EVENT_REPORTED__PACKAGE_STOPPED_STATE__PACKAGE_STATE_NORMAL;
        if (DEBUG_PROCESSES) {
            Slog.d(TAG, "Logging startService for " + packageName + ", stopped="
                    + wasStopped + ", firstLaunch=" + firstLaunch + ", intent=" + service
                    + ", r.app=" + r.app);
        }
        FrameworkStatsLog.write(SERVICE_REQUEST_EVENT_REPORTED, uid, callingUid,
                service.getAction(),
                SERVICE_REQUEST_EVENT_REPORTED__REQUEST_TYPE__START, false,
                r.app == null || r.app.getThread() == null
                ? SERVICE_REQUEST_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_COLD
                : (wasStartRequested || !r.getConnections().isEmpty()
                ? SERVICE_REQUEST_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_HOT
                : SERVICE_REQUEST_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_WARM),
                getShortProcessNameForStats(callingUid, callingProcessName),
                getShortServiceNameForStats(r),
                packageState,
                packageName,
                callingPackage,
                callingProcessState,
                r.mProcessStateOnRequest,
                firstLaunch,
                0L /* TODO: stoppedDuration */);

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

    private @Nullable String getShortProcessNameForStats(int uid, String processName) {
        final String[] packages = mAm.mContext.getPackageManager().getPackagesForUid(uid);
        if (packages != null && packages.length == 1) {
            // Not the shared UID case, let's see if the package name equals to the process name.
            if (TextUtils.equals(packages[0], processName)) {
                // same name, just return null here.
                return null;
            } else if (processName != null && processName.startsWith(packages[0])) {
                // return the suffix of the process name
                return processName.substring(packages[0].length());
            }
        }
        // return the full process name.
        return processName;
    }

    private @Nullable String getShortServiceNameForStats(@NonNull ServiceRecord r) {
        final ComponentName cn = r.getComponentName();
        return cn != null ? cn.getShortClassName() : null;
    }

    private void stopServiceLocked(ServiceRecord service, boolean enqueueOomAdj) {
        traceInstant("stopService(): ", service);
        try {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "stopServiceLocked()");
            if (service.delayed) {
                // If service isn't actually running, but is being held in the
                // delayed list, then we need to keep it started but note that it
                // should be stopped once no longer delayed.
                if (DEBUG_DELAYED_STARTS) {
                    Slog.v(TAG_SERVICE, "Delaying stop of pending: " + service);
                }
                service.delayedStop = true;
                return;
            }

            maybeStopShortFgsTimeoutLocked(service);
            maybeStopFgsTimeoutLocked(service);

            final int uid = service.appInfo.uid;
            final String packageName = service.name.getPackageName();
            final String serviceName = service.name.getClassName();
            FrameworkStatsLog.write(FrameworkStatsLog.SERVICE_STATE_CHANGED, uid, packageName,
                    serviceName, FrameworkStatsLog.SERVICE_STATE_CHANGED__STATE__STOP);
            mAm.mBatteryStatsService.noteServiceStopRunning(uid, packageName, serviceName);
            service.startRequested = false;
            if (service.tracker != null) {
                synchronized (mAm.mProcessStats.mLock) {
                    service.tracker.setStarted(false, mAm.mProcessStats.getMemFactorLocked(),
                            SystemClock.uptimeMillis());
                }
            }
            service.callStart = false;

            bringDownServiceIfNeededLocked(service, false, false, enqueueOomAdj,
                    "stopService");
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }

    }

    int stopServiceLocked(IApplicationThread caller, Intent service,
            String resolvedType, int userId, boolean isSdkSandboxService,
            int sdkSandboxClientAppUid, String sdkSandboxClientAppPackage, String instanceName) {
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "stopService: " + service
                + " type=" + resolvedType);

        final ProcessRecord callerApp = mAm.getRecordForAppLOSP(caller);
        if (caller != null && callerApp == null) {
            throw new SecurityException(
                    "Unable to find app for caller " + caller
                    + " (pid=" + mAm.mInjector.getCallingPid()
                    + ") when stopping service " + service);
        }

        // If this service is active, make sure it is stopped.
        ServiceLookupResult r = retrieveServiceLocked(service, instanceName, isSdkSandboxService,
                sdkSandboxClientAppUid, sdkSandboxClientAppPackage, resolvedType, null,
                mAm.mInjector.getCallingPid(), mAm.mInjector.getCallingUid(),
                userId, false, false, false, false, null, false, false);
        if (r != null) {
            if (r.record != null) {
                final long origId = mAm.mInjector.clearCallingIdentity();
                try {
                    stopServiceLocked(r.record, false);
                } finally {
                    mAm.mInjector.restoreCallingIdentity(origId);
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
                    mAm.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_UID_IDLE);
                }
            }
        }
    }

    void killMisbehavingService(ServiceRecord r,
            int appUid, int appPid, String localPackageName, int exceptionTypeId) {
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
            mAm.crashApplicationWithType(appUid, appPid, localPackageName, -1,
                    "Bad notification for startForeground", true /*force*/, exceptionTypeId);
        }
    }

    IBinder peekServiceLocked(Intent service, String resolvedType, String callingPackage) {
        ServiceLookupResult r = retrieveServiceLocked(service, null, resolvedType, callingPackage,
                mAm.mInjector.getCallingPid(), mAm.mInjector.getCallingUid(),
                UserHandle.getCallingUserId(), false, false, false, false, false, false);

        IBinder ret = null;
        if (r != null) {
            // r.record is null if findServiceLocked() failed the caller permission check
            if (r.record == null) {
                throw new SecurityException(
                        "Permission Denial: Accessing service"
                        + " from pid=" + mAm.mInjector.getCallingPid()
                        + ", uid=" + mAm.mInjector.getCallingUid()
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

            maybeStopShortFgsTimeoutLocked(r);
            maybeStopFgsTimeoutLocked(r);

            final int uid = r.appInfo.uid;
            final String packageName = r.name.getPackageName();
            final String serviceName = r.name.getClassName();
            FrameworkStatsLog.write(FrameworkStatsLog.SERVICE_STATE_CHANGED, uid, packageName,
                    serviceName, FrameworkStatsLog.SERVICE_STATE_CHANGED__STATE__STOP);
            mAm.mBatteryStatsService.noteServiceStopRunning(uid, packageName, serviceName);
            r.startRequested = false;
            if (r.tracker != null) {
                synchronized (mAm.mProcessStats.mLock) {
                    r.tracker.setStarted(false, mAm.mProcessStats.getMemFactorLocked(),
                            SystemClock.uptimeMillis());
                }
            }
            r.callStart = false;
            final long origId = mAm.mInjector.clearCallingIdentity();
            bringDownServiceIfNeededLocked(r, false, false, false, "stopServiceToken");
            mAm.mInjector.restoreCallingIdentity(origId);
            return true;
        }
        return false;
    }

    /**
     * Put the named service into the foreground mode
     */
    @GuardedBy("mAm")
    public void setServiceForegroundLocked(ComponentName className, IBinder token,
            int id, Notification notification, int flags, int foregroundServiceType) {
        final int userId = UserHandle.getCallingUserId();
        final int callingUid = mAm.mInjector.getCallingUid();
        final long origId = mAm.mInjector.clearCallingIdentity();
        try {
            ServiceRecord r = findServiceLocked(className, token, userId);
            if (r != null) {
                setServiceForegroundInnerLocked(r, id, notification, flags, foregroundServiceType,
                        callingUid);
            }
        } finally {
            mAm.mInjector.restoreCallingIdentity(origId);
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
        final long origId = mAm.mInjector.clearCallingIdentity();
        int ret = ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE;
        try {
            ServiceRecord r = findServiceLocked(className, token, userId);
            if (r != null) {
                ret = r.foregroundServiceType;
            }
        } finally {
            mAm.mInjector.restoreCallingIdentity(origId);
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

    /**
     * Stop FGSs owned by non-top, BG-restricted apps.
     */
    void updateForegroundApps(ServiceMap smap) {
        // This is called from the handler without the lock held.
        synchronized (mAm) {
            final long now = SystemClock.elapsedRealtime();
            long nextUpdateTime = Long.MAX_VALUE;
            if (smap != null) {
                if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "Updating foreground apps for user "
                        + smap.mUserId);
                smap.mPendingRemoveForegroundApps.clear();
                for (int i = smap.mActiveForegroundApps.size()-1; i >= 0; i--) {
                    ActiveForegroundApp aa = smap.mActiveForegroundApps.valueAt(i);
                    if (aa.mEndTime != 0) {
                        boolean canRemove = foregroundAppShownEnoughLocked(aa, now);
                        if (canRemove) {
                            // This was up for longer than the timeout, so just remove immediately.
                            smap.mPendingRemoveForegroundApps.add(smap.mActiveForegroundApps.keyAt(i));
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
                        if (isForegroundServiceAllowedInBackgroundRestricted(
                                aa.mUid, aa.mPackageName)) {
                            if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "Adding active: pkg="
                                    + aa.mPackageName + ", uid=" + aa.mUid);
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
                for(int i = smap.mPendingRemoveForegroundApps.size() - 1; i >= 0; i--) {
                    smap.mActiveForegroundApps.remove(smap.mPendingRemoveForegroundApps.get(i));
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
            smap.mActiveForegroundAppsChanged = false;
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

    /**
     * Check if the given app is allowed to have FGS running even if it's background restricted.
     *
     * <p>
     * Currently it needs to be in Top/Bound Top/FGS state. An uid could be in the FGS state if:
     * a) Bound by another process in the FGS state;
     * b) There is an active FGS running (ServiceRecord.isForeground is true);
     * c) The startForegroundService() has been called but the startForeground() hasn't - in this
     *    case, it must have passed the background FGS start check so we're safe here.
     * </p>
     */
    private boolean isForegroundServiceAllowedInBackgroundRestricted(ProcessRecord app) {
        final ProcessStateRecord state = app.mState;
        if (isDeviceProvisioningPackage(app.info.packageName)) {
            return true;
        }
        if (!state.isBackgroundRestricted()
                || state.getSetProcState() <= ActivityManager.PROCESS_STATE_BOUND_TOP) {
            return true;
        }
        if (state.getSetProcState() == ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE
                && state.isSetBoundByNonBgRestrictedApp()) {
            return true;
        }
        return false;
    }

    /**
     * Check if the given uid/pkg is allowed to have FGS running even if it's background restricted.
     */
    private boolean isForegroundServiceAllowedInBackgroundRestricted(int uid, String packageName) {
        final UidRecord uidRec = mAm.mProcessList.getUidRecordLOSP(uid);
        ProcessRecord app = null;
        return uidRec != null && ((app = uidRec.getProcessInPackage(packageName)) != null)
                && isForegroundServiceAllowedInBackgroundRestricted(app);
    }

    /*
     * If the FGS start is temp allowlisted by alarm-clock(REASON_ALARM_MANAGER_ALARM_CLOCK), it is
     * allowed even the app is background-restricted.
     */
    private boolean isTempAllowedByAlarmClock(int uid) {
        final ActivityManagerService.FgsTempAllowListItem item =
                mAm.isAllowlistedForFgsStartLOSP(uid);
        if (item != null) {
            return item.mReasonCode == REASON_ALARM_MANAGER_ALARM_CLOCK;
        } else {
            return false;
        }
    }

    void logFgsApiBeginLocked(int uid, int pid, int apiType) {
        synchronized (mFGSLogger) {
            mFGSLogger.logForegroundServiceApiEventBegin(uid, pid, apiType, "");
        }
    }

    void logFgsApiEndLocked(int uid, int pid, int apiType) {
        synchronized (mFGSLogger) {
            mFGSLogger.logForegroundServiceApiEventEnd(uid, pid, apiType);
        }
    }

    void logFgsApiStateChangedLocked(int uid, int pid, int apiType, int state) {
        synchronized (mFGSLogger) {
            mFGSLogger.logForegroundServiceApiStateChanged(uid, pid, apiType, state);
        }
    }

    /**
     * @param id Notification ID.  Zero === exit foreground state for the given service.
     */
    @GuardedBy("mAm")
    private void setServiceForegroundInnerLocked(final ServiceRecord r, int id,
            Notification notification, int flags, int foregroundServiceType,
            int callingUidIfStart) {
        if (id != 0) {
            if (notification == null) {
                throw new IllegalArgumentException("null notification");
            }
            traceInstant("startForeground(): ", r);
            final int foregroundServiceStartType = foregroundServiceType;
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
            }
            final int manifestType = r.serviceInfo.getForegroundServiceType();
            // If passed in foreground service type is FOREGROUND_SERVICE_TYPE_MANIFEST,
            // consider it is the same as manifest foreground service type.
            if (foregroundServiceType == FOREGROUND_SERVICE_TYPE_MANIFEST) {
                foregroundServiceType = manifestType;
            }

            // Check the passed in foreground service type flags is a subset of manifest
            // foreground service type flags.
            final String prop = "debug.skip_fgs_manifest_type_check";
            if (((foregroundServiceType & manifestType) != foregroundServiceType)
                    // When building a test app on Studio, the SDK may not have all the
                    // FGS types yet. This debug flag will allow using FGS types that are
                    // not set in the manifest.
                    && !SystemProperties.getBoolean(prop, false)) {
                final String message = "foregroundServiceType "
                        + String.format("0x%08X", foregroundServiceType)
                        + " is not a subset of foregroundServiceType attribute "
                        + String.format("0x%08X", manifestType)
                        + " in service element of manifest file";
                if (!r.appInfo.isInstantApp()
                        || CompatChanges.isChangeEnabled(FGS_TYPE_CHECK_FOR_INSTANT_APPS,
                        r.appInfo.uid)) {
                    throw new IllegalArgumentException(message);
                } else {
                    Slog.w(TAG, message + "\n"
                            + "This will be an exception once the target SDK level is UDC");
                }
            }
            if ((foregroundServiceType & FOREGROUND_SERVICE_TYPE_SHORT_SERVICE) != 0
                    && foregroundServiceType != FOREGROUND_SERVICE_TYPE_SHORT_SERVICE) {
                Slog.w(TAG_SERVICE, "startForeground(): FOREGROUND_SERVICE_TYPE_SHORT_SERVICE"
                        + " is combined with other types. SHORT_SERVICE will be ignored.");
                // In this case, the service will be handled as a non-short, regular FGS
                // anyway, so we just remove the SHORT_SERVICE type.
                foregroundServiceType &= ~FOREGROUND_SERVICE_TYPE_SHORT_SERVICE;
            }

            boolean alreadyStartedOp = false;
            boolean stopProcStatsOp = false;
            final boolean origFgRequired = r.fgRequired;
            if (r.fgRequired) {
                if (DEBUG_SERVICE || DEBUG_BACKGROUND_CHECK) {
                    Slog.i(TAG, "Service called startForeground() as required: " + r);
                }
                r.fgRequired = false;
                r.fgWaiting = false;
                alreadyStartedOp = stopProcStatsOp = true;
                mServiceFGAnrTimer.cancel(r);
            }

            if (!shouldAllowBootCompletedStart(r, foregroundServiceType)) {
                throw new ForegroundServiceStartNotAllowedException("FGS type "
                        + ServiceInfo.foregroundServiceTypeToLabel(foregroundServiceType)
                        + " not allowed to start from BOOT_COMPLETED!");
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
                        && !isForegroundServiceAllowedInBackgroundRestricted(r.app)
                        && !isTempAllowedByAlarmClock(r.app.uid)) {
                    Slog.w(TAG,
                            "Service.startForeground() not allowed due to bg restriction: service "
                                    + r.shortInstanceName);
                    // Back off of any foreground expectations around this service, since we've
                    // just turned down its fg request.
                    updateServiceForegroundLocked(psr, false);
                    ignoreForeground = true;
                }

                // Whether FGS-BG-start restriction is enabled for this service.
                final boolean isBgFgsRestrictionEnabledForService = isBgFgsRestrictionEnabled(r,
                        callingUidIfStart);

                // Whether to extend the SHORT_SERVICE time out.
                boolean extendShortServiceTimeout = false;

                // Whether setFgsRestrictionLocked() is called in here. Only used for logging.
                boolean fgsRestrictionRecalculated = false;

                final int previousFgsType = r.foregroundServiceType;

                int fgsTypeCheckCode = FGS_TYPE_POLICY_CHECK_UNKNOWN;
                if (!ignoreForeground) {
                    if (foregroundServiceType == FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
                            && !r.startRequested) {
                        // There's a long standing bug that allows a bound service to become
                        // a foreground service *even when it's not started*.
                        // Unfortunately, there are apps relying on this behavior, so we can't just
                        // suddenly disallow it.
                        // However, this would be very problematic if used with a short-FGS, so we
                        // explicitly disallow this combination.
                        throw new StartForegroundCalledOnStoppedServiceException(
                                "startForeground(SHORT_SERVICE) called on a service that's not"
                                + " started.");
                    }

                    // Side note: If a valid short-service (which has to be "started"), happens to
                    // also be bound, then we still _will_ apply a timeout, because it still has
                    // to be stopped.

                    // Calling startForeground on a SHORT_SERVICE will require some additional
                    // checks.
                    // A) SHORT_SERVICE -> another type.
                    //    - This should be allowed only when the app could start another FGS.
                    //    - When succeed, the timeout should stop.
                    // B) SHORT_SERVICE -> SHORT_SERVICE
                    //    - If the app could start an FGS, then this would extend the timeout.
                    //    - Otherwise, it's basically a no-op.
                    //    - If it's already timed out, we also throw.
                    // Also,
                    // C) another type -> SHORT_SERVICE
                    //    - This will always be allowed.
                    //    - Timeout will start.

                    final boolean isOldTypeShortFgs = r.isShortFgs();
                    final boolean isNewTypeShortFgs =
                            foregroundServiceType == FOREGROUND_SERVICE_TYPE_SHORT_SERVICE;
                    final long nowUptime = SystemClock.uptimeMillis();
                    final boolean isOldTypeShortFgsAndTimedOut =
                            r.shouldTriggerShortFgsTimeout(nowUptime);

                    // If true, we skip the BFSL check.
                    boolean bypassBfslCheck = false;

                    if (r.isForeground && (isOldTypeShortFgs || isNewTypeShortFgs)) {
                        if (DEBUG_SHORT_SERVICE) {
                            Slog.i(TAG_SERVICE, String.format(
                                    "FGS type changing from %x%s to %x: %s",
                                    r.foregroundServiceType,
                                    (isOldTypeShortFgsAndTimedOut ? "(timed out short FGS)" : ""),
                                    foregroundServiceStartType,
                                    r.toString()));
                        }
                    }

                    if (r.isForeground && isOldTypeShortFgs) {

                        // If we get here, that means startForeground(SHORT_SERVICE) is called again
                        // on a SHORT_SERVICE FGS.

                        // See if the app could start an FGS or not.
                        r.clearFgsAllowStart();
                        setFgsRestrictionLocked(r.serviceInfo.packageName, r.app.getPid(),
                                r.appInfo.uid, r.intent.getIntent(), r, r.userId,
                                BackgroundStartPrivileges.NONE,
                                false /* isBindService */);
                        fgsRestrictionRecalculated = true;
                        if (!r.isFgsAllowedStart()) {
                            Slog.w(TAG_SERVICE, "FGS type change to/from SHORT_SERVICE: "
                                    + " BFSL DENIED.");
                        } else {
                            if (DEBUG_SHORT_SERVICE) {
                                Slog.w(TAG_SERVICE, "FGS type change to/from SHORT_SERVICE: "
                                        + " BFSL Allowed: "
                                        + PowerExemptionManager.reasonCodeToString(
                                                r.getFgsAllowStart()));
                            }
                        }

                        final boolean fgsStartAllowed =
                                !isBgFgsRestrictionEnabledForService
                                        || r.isFgsAllowedStart();

                        if (fgsStartAllowed) {
                            if (isNewTypeShortFgs) {
                                // Only in this case, we extend the SHORT_SERVICE time out.
                                extendShortServiceTimeout = true;
                            } else {
                                // FGS type is changing from SHORT_SERVICE to another type when
                                // an app is allowed to start FGS, so this will succeed.
                                // The timeout will stop later, in
                                // maybeUpdateShortFgsTrackingLocked().
                            }
                        } else {
                            if (isNewTypeShortFgs) {
                                // startForeground(SHORT_SERVICE) is called on an already running
                                // SHORT_SERVICE FGS, when BFSL is not allowed.
                                // In this case, the call should succeed
                                // (== ForegroundServiceStartNotAllowedException shouldn't be
                                // thrown), but the short service timeout shouldn't extend
                                // (== extendShortServiceTimeout should be false).
                                // We still do everything else -- e.g. we still need to update
                                // the notification.
                                bypassBfslCheck = true;
                            } else {
                                // We catch this case later, in the
                                // "if (r.mAllowStartForeground == REASON_DENIED...)" block below.
                            }
                        }
                    } else if (CompatChanges.isChangeEnabled(
                                    FGS_INTRODUCE_TIME_LIMITS, r.appInfo.uid)
                                && android.app.Flags.introduceNewServiceOntimeoutCallback()
                                && getTimeLimitedFgsType(foregroundServiceType)
                                        != ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE) {
                        // Calling startForeground on a FGS type which has a time limit will only be
                        // allowed if the app is in a state where it can normally start another FGS
                        // and it hasn't hit its time limit in the past 24hrs, or it has been in the
                        // foreground after it hit its time limit, or it is currently in the
                        // TOP (or better) proc state.

                        // See if the app could start an FGS or not.
                        r.clearFgsAllowStart();
                        setFgsRestrictionLocked(r.serviceInfo.packageName, r.app.getPid(),
                                r.appInfo.uid, r.intent.getIntent(), r, r.userId,
                                BackgroundStartPrivileges.NONE, false /* isBindService */);
                        fgsRestrictionRecalculated = true;

                        final boolean fgsStartAllowed = !isBgFgsRestrictionEnabledForService
                                                            || r.isFgsAllowedStart();
                        if (fgsStartAllowed) {
                            SparseArray<TimeLimitedFgsInfo> fgsInfo =
                                    mTimeLimitedFgsInfo.get(r.appInfo.uid);
                            if (fgsInfo == null) {
                                fgsInfo = new SparseArray<>();
                                mTimeLimitedFgsInfo.put(r.appInfo.uid, fgsInfo);
                            }
                            final int timeLimitedFgsType =
                                    getTimeLimitedFgsType(foregroundServiceType);
                            final TimeLimitedFgsInfo fgsTypeInfo = fgsInfo.get(timeLimitedFgsType);
                            if (fgsTypeInfo != null) {
                                final long before24Hr = Math.max(0,
                                            SystemClock.elapsedRealtime() - (24 * 60 * 60 * 1000));
                                final long lastTimeOutAt = fgsTypeInfo.getTimeLimitExceededAt();
                                if (fgsTypeInfo.getFirstFgsStartRealtime() < before24Hr
                                        || r.app.mState.getCurProcState() <= PROCESS_STATE_TOP
                                        || (lastTimeOutAt != Long.MIN_VALUE
                                            && r.app.mState.getLastTopTime() > lastTimeOutAt)) {
                                    // Reset the time limit info for this fgs type if it has been
                                    // more than 24hrs since the first fgs start or if the app is
                                    // currently in the TOP state or was in the TOP state after
                                    // the time limit was exhausted previously.
                                    fgsTypeInfo.reset();
                                } else if (lastTimeOutAt > 0) {
                                    // Time limit was exhausted within the past 24 hours and the app
                                    // has not been in the TOP state since then, throw an exception.
                                    final String exceptionMsg = "Time limit already exhausted for"
                                            + " foreground service type "
                                            + ServiceInfo.foregroundServiceTypeToLabel(
                                                    foregroundServiceType);
                                    // Only throw an exception if the new ANR behavior
                                    // ("do nothing") is not gated or the new crashing logic gate
                                    // is enabled; otherwise, reset the limit temporarily.
                                    if (!android.app.Flags.gateFgsTimeoutAnrBehavior()
                                            || android.app.Flags.enableFgsTimeoutCrashBehavior()) {
                                        throw new ForegroundServiceStartNotAllowedException(
                                                    exceptionMsg);
                                    } else {
                                        Slog.wtf(TAG, exceptionMsg);
                                        fgsTypeInfo.reset();
                                    }
                                }
                            }
                        } else {
                            // This case will be handled in the BFSL check below.
                        }
                    } else if (r.mStartForegroundCount == 0) {
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
                                resetFgsRestrictionLocked(r);
                                setFgsRestrictionLocked(r.serviceInfo.packageName, r.app.getPid(),
                                        r.appInfo.uid, r.intent.getIntent(), r, r.userId,
                                        BackgroundStartPrivileges.NONE,
                                        false /* isBindService */);
                                fgsRestrictionRecalculated = true;
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
                        // We get here if startForeground() is called multiple times
                        // on the same service after it's created, regardless of whether
                        // stopForeground() has been called or not.

                        // The second or later time startForeground() is called after service is
                        // started. Check for app state again.
                        setFgsRestrictionLocked(r.serviceInfo.packageName, r.app.getPid(),
                                r.appInfo.uid, r.intent.getIntent(), r, r.userId,
                                BackgroundStartPrivileges.NONE,
                                false /* isBindService */);
                        fgsRestrictionRecalculated = true;
                    }

                    // When startForeground() is called on a bound service, without having
                    // it started (i.e. no Context.startService() or startForegroundService() was
                    // called.)
                    // called on it, then we probably didn't call setFgsRestrictionLocked()
                    // in startService(). If fgsRestrictionRecalculated is false, then we
                    // didn't call setFgsRestrictionLocked() here either.
                    //
                    // In this situation, we call setFgsRestrictionLocked() with
                    // forBoundFgs = false, so we'd set the FGS allowed reason to the
                    // by-bindings fields, so we can put it in the log, without affecting the
                    // logic.
                    if (!fgsRestrictionRecalculated && !r.startRequested) {
                        setFgsRestrictionLocked(r.serviceInfo.packageName, r.app.getPid(),
                                r.appInfo.uid, r.intent.getIntent(), r, r.userId,
                                BackgroundStartPrivileges.NONE,
                                false /* isBindService */, true /* forBoundFgs */);
                    }

                    // If the foreground service is not started from TOP process, do not allow it to
                    // have while-in-use location/camera/microphone access.
                    if (!r.isFgsAllowedWiu_forCapabilities()) {
                        Slog.w(TAG,
                                "Foreground service started from background can not have "
                                        + "location/camera/microphone access: service "
                                        + r.shortInstanceName);
                    }
                    r.maybeLogFgsLogicChange();
                    if (!bypassBfslCheck) {
                        logFgsBackgroundStart(r);
                        if (!r.isFgsAllowedStart()
                                && isBgFgsRestrictionEnabledForService) {
                            final String msg = "Service.startForeground() not allowed due to "
                                    + "mAllowStartForeground false: service "
                                    + r.shortInstanceName
                                    + (isOldTypeShortFgs ? " (Called on SHORT_SERVICE)" : "");
                            Slog.w(TAG, msg);
                            showFgsBgRestrictedNotificationLocked(r);
                            updateServiceForegroundLocked(psr, true);
                            ignoreForeground = true;
                            logFGSStateChangeLocked(r,
                                    FOREGROUND_SERVICE_STATE_CHANGED__STATE__DENIED,
                                    0, FGS_STOP_REASON_UNKNOWN, FGS_TYPE_POLICY_CHECK_UNKNOWN,
                                    FOREGROUND_SERVICE_STATE_CHANGED__FGS_START_API__FGSSTARTAPI_NA,
                                    false /* fgsRestrictionRecalculated */
                            );
                            if (CompatChanges.isChangeEnabled(FGS_START_EXCEPTION_CHANGE_ID,
                                    r.appInfo.uid)) {
                                throw new ForegroundServiceStartNotAllowedException(msg);
                            }
                        }
                    }

                    if (!ignoreForeground) {
                        Pair<Integer, RuntimeException> fgsTypeResult = null;
                        if (foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE) {
                            fgsTypeResult = validateForegroundServiceType(r,
                                    foregroundServiceType,
                                    ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE,
                                    foregroundServiceStartType);
                        } else {
                            int fgsTypes = foregroundServiceType;
                            // If the service has declared some unknown types which might be coming
                            // from future releases, and if it also comes with the "specialUse",
                            // then it'll be deemed as the "specialUse" and we ignore this
                            // unknown type. Otherwise, it'll be treated as an invalid type.
                            int defaultFgsTypes = (foregroundServiceType
                                    & ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) != 0
                                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE;
                            for (int serviceType = Integer.highestOneBit(fgsTypes);
                                    serviceType != 0;
                                    serviceType = Integer.highestOneBit(fgsTypes)) {
                                fgsTypeResult = validateForegroundServiceType(r,
                                        serviceType, defaultFgsTypes, foregroundServiceStartType);
                                fgsTypes &= ~serviceType;
                                if (fgsTypeResult.first != FGS_TYPE_POLICY_CHECK_OK) {
                                    break;
                                }
                            }
                        }
                        fgsTypeCheckCode = fgsTypeResult.first;
                        if (fgsTypeResult.second != null) {
                            logFGSStateChangeLocked(r,
                                    FOREGROUND_SERVICE_STATE_CHANGED__STATE__DENIED,
                                    0, FGS_STOP_REASON_UNKNOWN, fgsTypeResult.first,
                                    FOREGROUND_SERVICE_STATE_CHANGED__FGS_START_API__FGSSTARTAPI_NA,
                                    false /* fgsRestrictionRecalculated */
                            );
                            throw fgsTypeResult.second;
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

                        // The logging of FOREGROUND_SERVICE_STATE_CHANGED__STATE__ENTER event could
                        // be deferred, make a copy of mAllowStartForeground and
                        // mAllowWhileInUsePermissionInFgs.
                        r.mAllowStartForegroundAtEntering = r.getFgsAllowStart();
                        r.mAllowWhileInUsePermissionInFgsAtEntering =
                                r.isFgsAllowedWiu_forCapabilities();
                        r.mStartForegroundCount++;
                        r.mFgsEnterTime = SystemClock.uptimeMillis();
                        if (!stopProcStatsOp) {
                            synchronized (mAm.mProcessStats.mLock) {
                                final ServiceState stracker = r.getTracker();
                                if (stracker != null) {
                                    stracker.setForeground(true,
                                            mAm.mProcessStats.getMemFactorLocked(),
                                            SystemClock.uptimeMillis());
                                }
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

                        int fgsStartApi = FOREGROUND_SERVICE_STATE_CHANGED__FGS_START_API__FGSSTARTAPI_NONE;
                        if (r.startRequested) {
                            if (origFgRequired) {
                                fgsStartApi =
                                        FOREGROUND_SERVICE_STATE_CHANGED__FGS_START_API__FGSSTARTAPI_START_FOREGROUND_SERVICE;
                            } else {
                                fgsStartApi =
                                        FOREGROUND_SERVICE_STATE_CHANGED__FGS_START_API__FGSSTARTAPI_START_SERVICE;
                            }
                        }

                        logFGSStateChangeLocked(r,
                                FOREGROUND_SERVICE_STATE_CHANGED__STATE__ENTER,
                                0, FGS_STOP_REASON_UNKNOWN, fgsTypeCheckCode,
                                fgsStartApi,
                                fgsRestrictionRecalculated
                        );
                        synchronized (mFGSLogger) {
                            mFGSLogger.logForegroundServiceStart(r.appInfo.uid, 0, r);
                        }
                        updateNumForegroundServicesLocked();
                    }

                    maybeUpdateShortFgsTrackingLocked(r,
                            extendShortServiceTimeout);
                    // Even if the service is already a FGS, we need to update the notification,
                    // so we need to call it again.
                    signalForegroundServiceObserversLocked(r);
                    r.postNotification(true);
                    if (r.app != null) {
                        updateServiceForegroundLocked(psr, true);
                    }
                    getServiceMapLocked(r.userId).ensureNotStartingBackgroundLocked(r);
                    mAm.notifyPackageUse(r.serviceInfo.packageName,
                            PackageManager.NOTIFY_PACKAGE_USE_FOREGROUND_SERVICE);

                    if (CompatChanges.isChangeEnabled(FGS_INTRODUCE_TIME_LIMITS, r.appInfo.uid)
                            && android.app.Flags.introduceNewServiceOntimeoutCallback()) {
                        maybeUpdateFgsTrackingLocked(r, previousFgsType);
                    }
                } else {
                    if (DEBUG_FOREGROUND_SERVICE) {
                        Slog.d(TAG, "Suppressing startForeground() for FAS " + r);
                    }
                }
            } finally {
                if (stopProcStatsOp) {
                    // We got through to this point with it actively being started foreground,
                    // and never decided we wanted to keep it like that, so drop it.
                    synchronized (mAm.mProcessStats.mLock) {
                        final ServiceState stracker = r.getTracker();
                        if (stracker != null) {
                            stracker.setForeground(false, mAm.mProcessStats.getMemFactorLocked(),
                                    SystemClock.uptimeMillis());
                        }
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
                traceInstant("stopForeground(): ", r);
                final ServiceMap smap = getServiceMapLocked(r.userId);
                if (smap != null) {
                    decActiveForegroundAppLocked(smap, r);
                }

                maybeStopShortFgsTimeoutLocked(r);
                maybeStopFgsTimeoutLocked(r);

                // Adjust notification handling before setting isForeground to false, because
                // that state is relevant to the notification policy side.
                // Leave the time-to-display as already set: re-entering foreground mode will
                // only resume the previous quiet timeout, or will display immediately if the
                // deferral period had already passed.
                if ((flags & Service.STOP_FOREGROUND_REMOVE) != 0) {
                    cancelForegroundNotificationLocked(r);
                    r.foregroundId = 0;
                    r.foregroundNoti = null;
                } else if (r.appInfo.targetSdkVersion >= Build.VERSION_CODES.LOLLIPOP) {
                    // if it's been deferred, force to visibility
                    if (!r.mFgsNotificationShown) {
                        r.postNotification(false);
                    }
                    dropFgsNotificationStateLocked(r);
                    if ((flags & Service.STOP_FOREGROUND_DETACH) != 0) {
                        r.foregroundId = 0;
                        r.foregroundNoti = null;
                    }
                }

                r.isForeground = false;
                r.mFgsExitTime = SystemClock.uptimeMillis();
                synchronized (mAm.mProcessStats.mLock) {
                    final ServiceState stracker = r.getTracker();
                    if (stracker != null) {
                        stracker.setForeground(false, mAm.mProcessStats.getMemFactorLocked(),
                                SystemClock.uptimeMillis());
                    }
                }
                mAm.mAppOpsService.finishOperation(
                        AppOpsManager.getToken(mAm.mAppOpsService),
                        AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName, null);
                unregisterAppOpCallbackLocked(r);
                logFGSStateChangeLocked(r,
                        FOREGROUND_SERVICE_STATE_CHANGED__STATE__EXIT,
                        r.mFgsExitTime > r.mFgsEnterTime
                                ? (int) (r.mFgsExitTime - r.mFgsEnterTime) : 0,
                        FGS_STOP_REASON_STOP_FOREGROUND,
                        FGS_TYPE_POLICY_CHECK_UNKNOWN,
                        FOREGROUND_SERVICE_STATE_CHANGED__FGS_START_API__FGSSTARTAPI_NA,
                        false /* fgsRestrictionRecalculated */
                );

                synchronized (mFGSLogger) {
                    mFGSLogger.logForegroundServiceStop(r.appInfo.uid, r);
                }
                // foregroundServiceType is used in logFGSStateChangeLocked(), so we can't clear it
                // earlier.
                r.foregroundServiceType = 0;
                r.mFgsNotificationWasDeferred = false;
                signalForegroundServiceObserversLocked(r);
                resetFgsRestrictionLocked(r);
                mAm.updateForegroundServiceUsageStats(r.name, r.userId, false);
                if (r.app != null) {
                    mAm.updateLruProcessLocked(r.app, false, null);
                    updateServiceForegroundLocked(r.app.mServices, true);
                }
                updateNumForegroundServicesLocked();
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

    /**
     * Validate if the given service can start a foreground service with given type.
     *
     * @return A pair, where the first parameter is the result code and second is the exception
     *         object if it fails to start a foreground service with given type.
     */
    @NonNull
    private Pair<Integer, RuntimeException> validateForegroundServiceType(ServiceRecord r,
            @ForegroundServiceType int type,
            @ForegroundServiceType int defaultToType,
            @ForegroundServiceType int startType) {
        final ForegroundServiceTypePolicy policy = ForegroundServiceTypePolicy.getDefaultPolicy();
        final ForegroundServiceTypePolicyInfo policyInfo =
                policy.getForegroundServiceTypePolicyInfo(type, defaultToType);
        final @ForegroundServicePolicyCheckCode int code = policy.checkForegroundServiceTypePolicy(
                mAm.mContext, r.packageName, r.app.uid, r.app.getPid(),
                r.isFgsAllowedWiu_forStart(), policyInfo);
        RuntimeException exception = null;
        switch (code) {
            case FGS_TYPE_POLICY_CHECK_DEPRECATED: {
                final String msg = "Starting FGS with type "
                        + ServiceInfo.foregroundServiceTypeToLabel(type)
                        + " code=" + code
                        + " callerApp=" + r.app
                        + " targetSDK=" + r.app.info.targetSdkVersion;
                Slog.wtfQuiet(TAG, msg);
                Slog.w(TAG, msg);
            } break;
            case FGS_TYPE_POLICY_CHECK_DISABLED: {
                if (startType == FOREGROUND_SERVICE_TYPE_MANIFEST
                        && type == FOREGROUND_SERVICE_TYPE_NONE) {
                    exception = new MissingForegroundServiceTypeException(
                            "Starting FGS without a type "
                            + " callerApp=" + r.app
                            + " targetSDK=" + r.app.info.targetSdkVersion);
                } else {
                    exception = new InvalidForegroundServiceTypeException(
                            "Starting FGS with type "
                            + ServiceInfo.foregroundServiceTypeToLabel(type)
                            + " callerApp=" + r.app
                            + " targetSDK=" + r.app.info.targetSdkVersion
                            + " has been prohibited");
                }
            } break;
            case FGS_TYPE_POLICY_CHECK_PERMISSION_DENIED_PERMISSIVE: {
                final String msg = "Starting FGS with type "
                        + ServiceInfo.foregroundServiceTypeToLabel(type)
                        + " code=" + code
                        + " callerApp=" + r.app
                        + " targetSDK=" + r.app.info.targetSdkVersion
                        + " requiredPermissions=" + policyInfo.toPermissionString()
                        + (policyInfo.hasForegroundOnlyPermission()
                        ? " and the app must be in the eligible state/exemptions"
                        + " to access the foreground only permission" : "");
                Slog.wtfQuiet(TAG, msg);
                Slog.w(TAG, msg);
            } break;
            case FGS_TYPE_POLICY_CHECK_PERMISSION_DENIED_ENFORCED: {
                exception = new SecurityException("Starting FGS with type "
                        + ServiceInfo.foregroundServiceTypeToLabel(type)
                        + " callerApp=" + r.app
                        + " targetSDK=" + r.app.info.targetSdkVersion
                        + " requires permissions: "
                        + policyInfo.toPermissionString()
                        + (policyInfo.hasForegroundOnlyPermission()
                        ? " and the app must be in the eligible state/exemptions"
                        + " to access the foreground only permission" : ""));
            } break;
            case FGS_TYPE_POLICY_CHECK_OK:
            default:
                break;
        }
        return Pair.create(code, exception);
    }

    private class SystemExemptedFgsTypePermission extends ForegroundServiceTypePermission {
        SystemExemptedFgsTypePermission() {
            super("System exempted");
        }

        @Override
        public int checkPermission(@NonNull Context context, int callerUid, int callerPid,
                @NonNull String packageName, boolean allowWhileInUse) {
            final AppRestrictionController appRestrictionController = mAm.mAppRestrictionController;
            @ReasonCode int reason = appRestrictionController
                    .getPotentialSystemExemptionReason(callerUid);
            if (reason == REASON_DENIED) {
                reason = appRestrictionController
                        .getPotentialSystemExemptionReason(callerUid, packageName);
                if (reason == REASON_DENIED) {
                    reason = appRestrictionController
                            .getPotentialUserAllowedExemptionReason(callerUid, packageName);
                }
            }
            if (reason == REASON_DENIED) {
                if (ArrayUtils.contains(mAm.getPackageManagerInternal().getKnownPackageNames(
                        KnownPackages.PACKAGE_INSTALLER, UserHandle.USER_SYSTEM), packageName)) {
                    reason = REASON_PACKAGE_INSTALLER;
                }
            }

            switch (reason) {
                case REASON_SYSTEM_UID:
                case REASON_SYSTEM_ALLOW_LISTED:
                case REASON_DEVICE_DEMO_MODE:
                case REASON_DISALLOW_APPS_CONTROL:
                case REASON_DEVICE_OWNER:
                case REASON_PROFILE_OWNER:
                case REASON_PROC_STATE_PERSISTENT:
                case REASON_PROC_STATE_PERSISTENT_UI:
                case REASON_SYSTEM_MODULE:
                case REASON_CARRIER_PRIVILEGED_APP:
                case REASON_DPO_PROTECTED_APP:
                case REASON_ACTIVE_DEVICE_ADMIN:
                case REASON_ROLE_EMERGENCY:
                case REASON_ALLOWLISTED_PACKAGE:
                case REASON_PACKAGE_INSTALLER:
                case REASON_SYSTEM_EXEMPT_APP_OP:
                    return PERMISSION_GRANTED;
                default:
                    return PERMISSION_DENIED;
            }
        }
    }

    private void initSystemExemptedFgsTypePermission() {
        final ForegroundServiceTypePolicy policy = ForegroundServiceTypePolicy.getDefaultPolicy();
        final ForegroundServiceTypePolicyInfo policyInfo =
                policy.getForegroundServiceTypePolicyInfo(
                       ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                       ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE);
        if (policyInfo != null) {
            policyInfo.setCustomPermission(new SystemExemptedFgsTypePermission());
        }
    }

    /**
     * A custom permission checker for the "mediaProjection" FGS type:
     * if the app has been granted the permission to start a media projection via
     * the {@link android.media.project.MediaProjectionManager#createScreenCaptureIntent()},
     * it'll get the permission to start a foreground service with type "mediaProjection".
     */
    private class MediaProjectionFgsTypeCustomPermission extends ForegroundServiceTypePermission {
        MediaProjectionFgsTypeCustomPermission() {
            super("Media projection screen capture permission");
        }

        @Override
        public int checkPermission(@NonNull Context context, int callerUid, int callerPid,
                @NonNull String packageName, boolean allowWhileInUse) {
            return mAm.isAllowedMediaProjectionNoOpCheck(callerUid)
                    ? PERMISSION_GRANTED : PERMISSION_DENIED;
        }
    }

    /**
     * Set a custom permission checker for the "mediaProjection" FGS type.
     */
    private void initMediaProjectFgsTypeCustomPermission() {
        final ForegroundServiceTypePolicy policy = ForegroundServiceTypePolicy.getDefaultPolicy();
        final ForegroundServiceTypePolicyInfo policyInfo =
                policy.getForegroundServiceTypePolicyInfo(
                       ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                       ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE);
        if (policyInfo != null) {
            policyInfo.setCustomPermission(new MediaProjectionFgsTypeCustomPermission());
        }
    }

    ServiceNotificationPolicy applyForegroundServiceNotificationLocked(Notification notification,
            final String tag, final int id, final String pkg, final int userId) {
        // By nature of the FGS API, all FGS notifications have a null tag
        if (tag != null) {
            return ServiceNotificationPolicy.NOT_FOREGROUND_SERVICE;
        }

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
        long when = now
                + (r.isShortFgs() ? mAm.mConstants.mFgsNotificationDeferralIntervalForShort
                : mAm.mConstants.mFgsNotificationDeferralInterval);
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
            final long nextEligible = when
                    + (r.isShortFgs() ? mAm.mConstants.mFgsNotificationDeferralExclusionTimeForShort
                    : mAm.mConstants.mFgsNotificationDeferralExclusionTime);
            mFgsDeferralEligible.put(uid, nextEligible);
        }
        r.fgDisplayTime = when;
        r.mFgsNotificationDeferred = true;
        r.mFgsNotificationWasDeferred = true;
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
                            r.postNotification(true);
                            r.mFgsNotificationShown = true;
                        } else {
                            if (DEBUG_FOREGROUND_SERVICE) {
                                Slog.d(TAG_SERVICE, "  - service no longer running/fg, ignoring");
                            }
                        }
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
                // subsystem will not be displaying it yet.
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

        private final AppOpsManager.OnOpNotedInternalListener mOpNotedCallback =
                new AppOpsManager.OnOpNotedInternalListener() {
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
        if (r.foregroundNoti != null) {
            // First check to see if this app has any other active foreground services
            // with the same notification ID.  If so, we shouldn't actually cancel it,
            // because that would wipe away the notification that still needs to be shown
            // due the other service.
            ServiceMap sm = getServiceMapLocked(r.userId);
            if (sm != null) {
                for (int i = sm.mServicesByInstanceName.size() - 1; i >= 0; i--) {
                    ServiceRecord other = sm.mServicesByInstanceName.valueAt(i);
                    if (other != r
                            && other.isForeground
                            && other.foregroundId == r.foregroundId
                            && other.packageName.equals(r.packageName)) {
                        if (DEBUG_FOREGROUND_SERVICE) {
                            Slog.i(TAG_SERVICE, "FGS notification for " + r
                                    + " shared by " + other
                                    + " (isForeground=" + other.isForeground + ")"
                                    + " - NOT cancelling");
                        }
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
        boolean hasTypeNone = false;
        for (int i = psr.numberOfRunningServices() - 1; i >= 0; i--) {
            ServiceRecord sr = psr.getRunningServiceAt(i);
            if (sr.isForeground || sr.fgRequired) {
                anyForeground = true;
                fgServiceTypes |= sr.foregroundServiceType;
                if (sr.foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE) {
                    hasTypeNone = true;
                }
            }
        }
        mAm.updateProcessForegroundLocked(psr.mApp, anyForeground,
                fgServiceTypes, hasTypeNone, oomAdj);
        psr.setHasReportedForegroundServices(anyForeground);
    }

    void unscheduleShortFgsTimeoutLocked(ServiceRecord sr) {
        mShortFGSAnrTimer.cancel(sr);
        mAm.mHandler.removeMessages(ActivityManagerService.SERVICE_SHORT_FGS_PROCSTATE_TIMEOUT_MSG,
                sr);
        mAm.mHandler.removeMessages(ActivityManagerService.SERVICE_SHORT_FGS_TIMEOUT_MSG, sr);
    }

    /**
     * Update a {@link ServiceRecord}'s {@link ShortFgsInfo} as needed, and also start
     * a timeout as needed.
     *
     * If the {@link ServiceRecord} is not a short-FGS, then we'll stop the timeout and clear
     * the {@link ShortFgsInfo}.
     */
    private void maybeUpdateShortFgsTrackingLocked(ServiceRecord sr,
            boolean extendTimeout) {
        if (!sr.isShortFgs()) {
            sr.clearShortFgsInfo(); // Just in case we have it.
            unscheduleShortFgsTimeoutLocked(sr);
            return;
        }

        final boolean isAlreadyShortFgs = sr.hasShortFgsInfo();

        if (extendTimeout || !isAlreadyShortFgs) {
            if (DEBUG_SHORT_SERVICE) {
                if (isAlreadyShortFgs) {
                    Slog.i(TAG_SERVICE, "Extending SHORT_SERVICE time out: " + sr);
                } else {
                    Slog.i(TAG_SERVICE, "Short FGS started: " + sr);
                }
            }
            traceInstant("short FGS start/extend: ", sr);
            sr.setShortFgsInfo(SystemClock.uptimeMillis());

            // We'll restart the timeout.
            unscheduleShortFgsTimeoutLocked(sr);

            final Message msg = mAm.mHandler.obtainMessage(
                    ActivityManagerService.SERVICE_SHORT_FGS_TIMEOUT_MSG, sr);
            mAm.mHandler.sendMessageAtTime(msg, sr.getShortFgsInfo().getTimeoutTime());
        } else {
            if (DEBUG_SHORT_SERVICE) {
                Slog.w(TAG_SERVICE, "NOT extending SHORT_SERVICE time out: " + sr);
            }

            // We only (potentially) update the start command, start count, but not the timeout
            // time.
            // In this case, we keep the existing timeout running.
            sr.getShortFgsInfo().update();
        }
    }

    /**
     * Stop the timeout for a ServiceRecord, if it's of a short-FGS.
     */
    private void maybeStopShortFgsTimeoutLocked(ServiceRecord sr) {
        sr.clearShortFgsInfo(); // Always clear, just in case.
        if (!sr.isShortFgs()) {
            return;
        }
        if (DEBUG_SHORT_SERVICE) {
            Slog.i(TAG_SERVICE, "Stop short FGS timeout: " + sr);
        }
        unscheduleShortFgsTimeoutLocked(sr);
    }

    void onShortFgsTimeout(ServiceRecord sr) {
        synchronized (mAm) {
            final long nowUptime = SystemClock.uptimeMillis();
            if (!sr.shouldTriggerShortFgsTimeout(nowUptime)) {
                if (DEBUG_SHORT_SERVICE) {
                    Slog.d(TAG_SERVICE, "[STALE] Short FGS timed out: " + sr
                            + " " + sr.getShortFgsTimedEventDescription(nowUptime));
                }
                return;
            }
            Slog.e(TAG_SERVICE, "Short FGS timed out: " + sr);
            traceInstant("short FGS timeout: ", sr);

            logFGSStateChangeLocked(sr,
                    FOREGROUND_SERVICE_STATE_CHANGED__STATE__TIMED_OUT,
                    nowUptime > sr.mFgsEnterTime ? (int) (nowUptime - sr.mFgsEnterTime) : 0,
                    FGS_STOP_REASON_UNKNOWN,
                    FGS_TYPE_POLICY_CHECK_UNKNOWN,
                    FOREGROUND_SERVICE_STATE_CHANGED__FGS_START_API__FGSSTARTAPI_NA,
                    false /* fgsRestrictionRecalculated */
            );
            try {
                sr.app.getThread().scheduleTimeoutService(sr, sr.getShortFgsInfo().getStartId());
            } catch (RemoteException e) {
                Slog.w(TAG_SERVICE, "Exception from scheduleTimeoutService: " + e.toString());
            }
            // Schedule the procstate demotion timeout and ANR timeout.
            {
                final Message msg = mAm.mHandler.obtainMessage(
                        ActivityManagerService.SERVICE_SHORT_FGS_PROCSTATE_TIMEOUT_MSG, sr);
                mAm.mHandler.sendMessageAtTime(
                        msg, sr.getShortFgsInfo().getProcStateDemoteTime());
            }

            // ServiceRecord.getAnrTime() is an absolute time with a reference that is not "now".
            // Compute the time from "now" when starting the anr timer.
            mShortFGSAnrTimer.start(sr,
                    sr.getShortFgsInfo().getAnrTime() - SystemClock.uptimeMillis());
        }
    }

    boolean shouldServiceTimeOutLocked(ComponentName className, IBinder token) {
        final int userId = UserHandle.getCallingUserId();
        final long ident = mAm.mInjector.clearCallingIdentity();
        try {
            ServiceRecord sr = findServiceLocked(className, token, userId);
            if (sr == null) {
                return false;
            }
            final long nowUptime = SystemClock.uptimeMillis();
            return sr.shouldTriggerShortFgsTimeout(nowUptime);
        } finally {
            mAm.mInjector.restoreCallingIdentity(ident);
        }
    }

    void onShortFgsProcstateTimeout(ServiceRecord sr) {
        synchronized (mAm) {
            final long nowUptime = SystemClock.uptimeMillis();
            if (!sr.shouldDemoteShortFgsProcState(nowUptime)) {
                if (DEBUG_SHORT_SERVICE) {
                    Slog.d(TAG_SERVICE, "[STALE] Short FGS procstate demotion: " + sr
                            + " " + sr.getShortFgsTimedEventDescription(nowUptime));
                }
                return;
            }

            Slog.e(TAG_SERVICE, "Short FGS procstate demoted: " + sr);
            traceInstant("short FGS demote: ", sr);

            mAm.updateOomAdjLocked(sr.app, OOM_ADJ_REASON_SHORT_FGS_TIMEOUT);
        }
    }

    void onShortFgsAnrTimeout(ServiceRecord sr) {
        final String reason = "A foreground service of FOREGROUND_SERVICE_TYPE_SHORT_SERVICE"
                + " did not stop within a timeout: " + sr.getComponentName();

        final TimeoutRecord tr = TimeoutRecord.forShortFgsTimeout(reason);

        tr.mLatencyTracker.waitingOnAMSLockStarted();
        synchronized (mAm) {
            tr.mLatencyTracker.waitingOnAMSLockEnded();

            final long nowUptime = SystemClock.uptimeMillis();
            if (!sr.shouldTriggerShortFgsAnr(nowUptime)) {
                if (DEBUG_SHORT_SERVICE) {
                    Slog.d(TAG_SERVICE, "[STALE] Short FGS ANR'ed: " + sr
                            + " " + sr.getShortFgsTimedEventDescription(nowUptime));
                }
                mShortFGSAnrTimer.discard(sr);
                return;
            }
            mShortFGSAnrTimer.accept(sr);

            final String message = "Short FGS ANR'ed: " + sr;
            if (DEBUG_SHORT_SERVICE) {
                Slog.wtf(TAG_SERVICE, message);
            } else {
                Slog.e(TAG_SERVICE, message);
            }

            traceInstant("short FGS ANR: ", sr);

            mAm.appNotResponding(sr.app, tr);

            // TODO: Can we close the ANR dialog here, if it's still shown? Currently, the ANR
            // dialog really doesn't remember the "cause" (especially if there have been multiple
            // ANRs), so it's not doable.
        }
    }

    /**
     * @return the fgs type for this service which has the most lenient time limit; if none of the
     * types are time-restricted, return {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_NONE}.
     */
    @ServiceInfo.ForegroundServiceType int getTimeLimitedFgsType(int foregroundServiceType) {
        int fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE;
        long timeout = 0;
        if ((foregroundServiceType & ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                == ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING) {
            fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING;
            timeout = mAm.mConstants.mMediaProcessingFgsTimeoutDuration;
        }
        if ((foregroundServiceType & ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                == ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) {
            // update the timeout and type if this type has a more lenient time limit
            if (timeout == 0 || mAm.mConstants.mDataSyncFgsTimeoutDuration > timeout) {
                fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                timeout = mAm.mConstants.mDataSyncFgsTimeoutDuration;
            }
        }
        // Add logic for time limits introduced in the future for other fgs types above.
        return fgsType;
    }

    /**
     * @return the constant time limit defined for the given foreground service type.
     */
    private long getTimeLimitForFgsType(int foregroundServiceType) {
        return switch (foregroundServiceType) {
            case ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING ->
                    mAm.mConstants.mMediaProcessingFgsTimeoutDuration;
            case ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC ->
                    mAm.mConstants.mDataSyncFgsTimeoutDuration;
            // Add logic for time limits introduced in the future for other fgs types above.
            default -> Long.MAX_VALUE;
        };
    }

    /**
     * @return the next stop time for the given type, based on how long it has already ran for.
     * The total runtime is automatically reset 24hrs after the first fgs start of this type
     * or if the app has recently been in the TOP state when the app calls startForeground().
     */
    private long getNextFgsStopTime(int fgsType, TimeLimitedFgsInfo fgsInfo) {
        final long timeLimit = getTimeLimitForFgsType(fgsType);
        if (timeLimit == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return fgsInfo.getLastFgsStartTime() + Math.max(0, timeLimit - fgsInfo.getTotalRuntime());
    }

    private TimeLimitedFgsInfo getFgsTimeLimitedInfo(int uid, int fgsType) {
        final SparseArray<TimeLimitedFgsInfo> fgsInfo = mTimeLimitedFgsInfo.get(uid);
        if (fgsInfo != null) {
            return fgsInfo.get(fgsType);
        }
        return null;
    }

    private void maybeUpdateFgsTrackingLocked(ServiceRecord sr, int previousFgsType) {
        final int previouslyTimeLimitedType = getTimeLimitedFgsType(previousFgsType);
        if (previouslyTimeLimitedType == ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                && !sr.isFgsTimeLimited()) {
            // FGS was not previously time-limited and new type isn't either.
            return;
        }

        if (previouslyTimeLimitedType != ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE) {
            // FGS is switching types and the previous type was time-limited so update the runtime.
            final TimeLimitedFgsInfo fgsTypeInfo = getFgsTimeLimitedInfo(
                                                    sr.appInfo.uid, previouslyTimeLimitedType);
            if (fgsTypeInfo != null) {
                // Update the total runtime for the previous time-limited fgs type.
                fgsTypeInfo.updateTotalRuntime(SystemClock.uptimeMillis());
                fgsTypeInfo.decNumParallelServices();
            }

            if (!sr.isFgsTimeLimited()) {
                // Reset timers since new type does not have a timeout.
                mFGSAnrTimer.cancel(sr);
                mAm.mHandler.removeMessages(ActivityManagerService.SERVICE_FGS_TIMEOUT_MSG, sr);
                return;
            }
        }

        traceInstant("FGS start: ", sr);
        final long nowUptime = SystemClock.uptimeMillis();

        // Fetch/create/update the fgs info for the time-limited type.
        SparseArray<TimeLimitedFgsInfo> fgsInfo = mTimeLimitedFgsInfo.get(sr.appInfo.uid);
        if (fgsInfo == null) {
            fgsInfo = new SparseArray<>();
            mTimeLimitedFgsInfo.put(sr.appInfo.uid, fgsInfo);
        }
        final int timeLimitedFgsType = getTimeLimitedFgsType(sr.foregroundServiceType);
        TimeLimitedFgsInfo fgsTypeInfo = fgsInfo.get(timeLimitedFgsType);
        if (fgsTypeInfo == null) {
            fgsTypeInfo = sr.createTimeLimitedFgsInfo();
            fgsInfo.put(timeLimitedFgsType, fgsTypeInfo);
        }
        fgsTypeInfo.noteFgsFgsStart(nowUptime);

        // We'll cancel the previous ANR timer and start a fresh one below.
        mFGSAnrTimer.cancel(sr);
        mAm.mHandler.removeMessages(ActivityManagerService.SERVICE_FGS_TIMEOUT_MSG, sr);

        final Message msg = mAm.mHandler.obtainMessage(
                ActivityManagerService.SERVICE_FGS_TIMEOUT_MSG, sr);
        final long timeoutCallbackTime = getNextFgsStopTime(timeLimitedFgsType, fgsTypeInfo);
        if (timeoutCallbackTime == Long.MAX_VALUE) {
            // This should never happen since we only get to this point if the service record's
            // foregroundServiceType attribute contains a type that can be timed-out.
            Slog.wtf(TAG, "Couldn't calculate timeout for time-limited fgs: " + sr);
            return;
        }
        mAm.mHandler.sendMessageAtTime(msg, timeoutCallbackTime);
    }

    private void maybeStopFgsTimeoutLocked(ServiceRecord sr) {
        final int timeLimitedType = getTimeLimitedFgsType(sr.foregroundServiceType);
        if (timeLimitedType == ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE) {
            return; // if the current fgs type is not time-limited, return.
        }

        final TimeLimitedFgsInfo fgsTypeInfo = getFgsTimeLimitedInfo(
                                                sr.appInfo.uid, timeLimitedType);
        if (fgsTypeInfo != null) {
            // Update the total runtime for the previous time-limited fgs type.
            fgsTypeInfo.updateTotalRuntime(SystemClock.uptimeMillis());
            fgsTypeInfo.decNumParallelServices();
        }
        Slog.d(TAG_SERVICE, "Stop FGS timeout: " + sr);
        mFGSAnrTimer.cancel(sr);
        mAm.mHandler.removeMessages(ActivityManagerService.SERVICE_FGS_TIMEOUT_MSG, sr);
    }

    void onUidRemovedLocked(int uid) {
        // Remove all time-limited fgs tracking info stored for this uid.
        mTimeLimitedFgsInfo.delete(uid);
    }

    boolean hasServiceTimedOutLocked(ComponentName className, IBinder token) {
        final int userId = UserHandle.getCallingUserId();
        final long ident = mAm.mInjector.clearCallingIdentity();
        try {
            ServiceRecord sr = findServiceLocked(className, token, userId);
            if (sr == null) {
                return false;
            }
            return getTimeLimitedFgsType(sr.foregroundServiceType)
                    != ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE;
        } finally {
            mAm.mInjector.restoreCallingIdentity(ident);
        }
    }

    void onFgsTimeout(ServiceRecord sr) {
        synchronized (mAm) {
            final int fgsType = getTimeLimitedFgsType(sr.foregroundServiceType);
            if (fgsType == ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE || sr.app == null) {
                mFGSAnrTimer.discard(sr);
                return;
            }

            final long lastTopTime = sr.app.mState.getLastTopTime();
            final long constantTimeLimit = getTimeLimitForFgsType(fgsType);
            final long nowUptime = SystemClock.uptimeMillis();
            if (lastTopTime != Long.MIN_VALUE && constantTimeLimit > (nowUptime - lastTopTime)) {
                // Discard any other messages for this service
                mFGSAnrTimer.discard(sr);
                mAm.mHandler.removeMessages(ActivityManagerService.SERVICE_FGS_TIMEOUT_MSG, sr);
                // The app was in the TOP state after the FGS was started so its time allowance
                // should be counted from that time since this is considered a user interaction
                final Message msg = mAm.mHandler.obtainMessage(
                                        ActivityManagerService.SERVICE_FGS_TIMEOUT_MSG, sr);
                mAm.mHandler.sendMessageAtTime(msg, lastTopTime + constantTimeLimit);
                return;
            }

            Slog.e(TAG_SERVICE, "FGS (" + ServiceInfo.foregroundServiceTypeToLabel(fgsType)
                    + ") timed out: " + sr);
            mFGSAnrTimer.accept(sr);
            traceInstant("FGS timed out: ", sr);

            final TimeLimitedFgsInfo fgsTypeInfo = getFgsTimeLimitedInfo(sr.appInfo.uid, fgsType);
            if (fgsTypeInfo != null) {
                // Update total runtime for the time-limited fgs type and mark it as timed out.
                fgsTypeInfo.updateTotalRuntime(nowUptime);
                fgsTypeInfo.setTimeLimitExceededAt(nowUptime);

                logFGSStateChangeLocked(sr,
                        FOREGROUND_SERVICE_STATE_CHANGED__STATE__TIMED_OUT,
                        nowUptime > fgsTypeInfo.getFirstFgsStartUptime()
                                ? (int) (nowUptime - fgsTypeInfo.getFirstFgsStartUptime()) : 0,
                        FGS_STOP_REASON_UNKNOWN,
                        FGS_TYPE_POLICY_CHECK_UNKNOWN,
                        FOREGROUND_SERVICE_STATE_CHANGED__FGS_START_API__FGSSTARTAPI_NA,
                        false /* fgsRestrictionRecalculated */
                );
            }

            try {
                sr.app.getThread().scheduleTimeoutServiceForType(sr, sr.getLastStartId(), fgsType);
            } catch (RemoteException e) {
                Slog.w(TAG_SERVICE, "Exception from scheduleTimeoutServiceForType: " + e);
            }

            // Crash the service after giving the service some time to clean up.
            mFGSAnrTimer.start(sr, mAm.mConstants.mFgsCrashExtraWaitDuration);
        }
    }

    void onFgsCrashTimeout(ServiceRecord sr) {
        final int fgsType = getTimeLimitedFgsType(sr.foregroundServiceType);
        if (fgsType == ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE) {
            return; // no timed out FGS type was found (either it was stopped or it switched types)
        }

        synchronized (mAm) {
            final TimeLimitedFgsInfo fgsTypeInfo = getFgsTimeLimitedInfo(sr.appInfo.uid, fgsType);
            if (fgsTypeInfo != null) {
                // Runtime is already updated when the service times out - if the app didn't
                // stop the service, decrement the number of parallel running services here.
                fgsTypeInfo.decNumParallelServices();
            }
        }

        final String reason = "A foreground service of type "
                + ServiceInfo.foregroundServiceTypeToLabel(fgsType)
                + " did not stop within its timeout: " + sr.getComponentName();

        if (android.app.Flags.gateFgsTimeoutAnrBehavior()) {
            // Log a WTF instead of throwing an ANR while the new behavior is gated.
            Slog.wtf(TAG, reason);
            return;
        }
        if (android.app.Flags.enableFgsTimeoutCrashBehavior()) {
            // Crash the app
            synchronized (mAm) {
                Slog.e(TAG_SERVICE, "FGS Crashed: " + sr);
                traceInstant("FGS Crash: ", sr);
                if (sr.app != null) {
                    mAm.crashApplicationWithTypeWithExtras(sr.app.uid, sr.app.getPid(),
                            sr.app.info.packageName, sr.app.userId, reason, false /*force*/,
                            ForegroundServiceDidNotStopInTimeException.TYPE_ID,
                            ForegroundServiceDidNotStopInTimeException
                                    .createExtrasForService(sr.getComponentName()));
                }
            }
        } else {
            // ANR the app if the new crash behavior is not enabled
            final TimeoutRecord tr = TimeoutRecord.forFgsTimeout(reason);
            tr.mLatencyTracker.waitingOnAMSLockStarted();
            synchronized (mAm) {
                tr.mLatencyTracker.waitingOnAMSLockEnded();

                Slog.e(TAG_SERVICE, "FGS ANR'ed: " + sr);
                traceInstant("FGS ANR: ", sr);
                if (sr.app != null) {
                    mAm.appNotResponding(sr.app, tr);
                }

                // TODO: Can we close the ANR dialog here, if it's still shown? Currently, the ANR
                // dialog really doesn't remember the "cause" (especially if there have been
                // multiple ANRs), so it's not doable.
            }
        }
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
        maybeStopShortFgsTimeoutLocked(service);
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
            String resolvedType, final IServiceConnection connection, long flags,
            String instanceName, boolean isSdkSandboxService, int sdkSandboxClientAppUid,
            String sdkSandboxClientAppPackage, IApplicationThread sdkSandboxClientApplicationThread,
            String callingPackage, final int userId)
            throws TransactionTooLargeException {
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "bindService: " + service
                + " type=" + resolvedType + " conn=" + connection.asBinder()
                + " flags=0x" + Long.toHexString(flags));
        final int callingPid = mAm.mInjector.getCallingPid();
        final int callingUid = mAm.mInjector.getCallingUid();
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

        if ((flags & BIND_ALLOW_WHITELIST_MANAGEMENT) != 0 && !isCallerSystem) {
            throw new SecurityException(
                    "Non-system caller " + caller + " (pid=" + callingPid
                    + ") set BIND_ALLOW_WHITELIST_MANAGEMENT when binding service " + service);
        }

        if ((flags & Context.BIND_ALLOW_INSTANT) != 0 && !isCallerSystem) {
            throw new SecurityException(
                    "Non-system caller " + caller + " (pid=" + callingPid
                            + ") set BIND_ALLOW_INSTANT when binding service " + service);
        }

        if ((flags & Context.BIND_ALMOST_PERCEPTIBLE) != 0 && !isCallerSystem) {
            throw new SecurityException("Non-system caller (pid=" + callingPid
                    + ") set BIND_ALMOST_PERCEPTIBLE when binding service " + service);
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
        final boolean isBindExternal =
                (flags & Integer.toUnsignedLong(Context.BIND_EXTERNAL_SERVICE)) != 0
                || (flags & Context.BIND_EXTERNAL_SERVICE_LONG) != 0;
        final boolean allowInstant = (flags & Context.BIND_ALLOW_INSTANT) != 0;
        final boolean inSharedIsolatedProcess = (flags & Context.BIND_SHARED_ISOLATED_PROCESS) != 0;
        final boolean inPrivateSharedIsolatedProcess =
                ((flags & Context.BIND_PACKAGE_ISOLATED_PROCESS) != 0)
                        && enableBindPackageIsolatedProcess();
        final boolean matchQuarantined =
                (flags & Context.BIND_MATCH_QUARANTINED_COMPONENTS) != 0;

        ProcessRecord attributedApp = null;
        if (sdkSandboxClientAppUid > 0) {
            attributedApp = mAm.getRecordForAppLOSP(sdkSandboxClientApplicationThread);
        }
        ServiceLookupResult res = retrieveServiceLocked(service, instanceName,
                isSdkSandboxService, sdkSandboxClientAppUid, sdkSandboxClientAppPackage,
                resolvedType, callingPackage, callingPid, callingUid, userId, true, callerFg,
                isBindExternal, allowInstant, null /* fgsDelegateOptions */,
                inSharedIsolatedProcess, inPrivateSharedIsolatedProcess, matchQuarantined);
        if (res == null) {
            return 0;
        }
        if (res.record == null) {
            return -1;
        }
        ServiceRecord s = res.record;
        final AppBindRecord b = s.retrieveAppBindingLocked(service, callerApp, attributedApp);
        final ProcessServiceRecord clientPsr = b.client.mServices;
        if (clientPsr.numberOfConnections() >= mAm.mConstants.mMaxServiceConnectionsPerProcess) {
            Slog.w(TAG, "bindService exceeded max service connection number per process, "
                    + "callerApp:" + callerApp.processName
                    + " intent:" + service);
            return 0;
        }

        final ProcessRecord callingApp;
        synchronized (mAm.mPidsSelfLocked) {
            callingApp = mAm.mPidsSelfLocked.get(callingPid);
        }
        final String callingProcessName = callingApp != null
                ? callingApp.processName : callingPackage;
        final int callingProcessState =
                callingApp != null && callingApp.getThread() != null && !callingApp.isKilled()
                ? callingApp.mState.getCurProcState() : ActivityManager.PROCESS_STATE_UNKNOWN;
        s.updateProcessStateOnRequest();

        // The package could be frozen (meaning it's doing surgery), defer the actual
        // binding until the package is unfrozen.
        boolean packageFrozen = deferServiceBringupIfFrozenLocked(s, service, callingPackage, null,
                callingUid, callingPid, callingProcessName, callingProcessState,
                false, callerFg, userId, BackgroundStartPrivileges.NONE, true, connection);

        // If permissions need a review before any of the app components can run,
        // we schedule binding to the service but do not start its process, then
        // we launch a review activity to which is passed a callback to invoke
        // when done to start the bound service's process to completing the binding.
        boolean permissionsReviewRequired = !packageFrozen
                && !requestStartTargetPermissionsReviewIfNeededLocked(s, callingPackage, null,
                        callingUid, service, callerFg, userId, true, connection);

        final long origId = mAm.mInjector.clearCallingIdentity();

        try {
            if (unscheduleServiceRestartLocked(s, callerApp.info.uid, false)) {
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "BIND SERVICE WHILE RESTART PENDING: "
                        + s);
            }

            if ((flags&Context.BIND_AUTO_CREATE) != 0) {
                s.lastActivity = SystemClock.uptimeMillis();
                if (!s.hasAutoCreateConnections()) {
                    // This is the first binding, let the tracker know.
                    synchronized (mAm.mProcessStats.mLock) {
                        final ServiceState stracker = s.getTracker();
                        if (stracker != null) {
                            stracker.setBound(true, mAm.mProcessStats.getMemFactorLocked(),
                                    SystemClock.uptimeMillis());
                        }
                    }
                }
            }

            if ((flags & Context.BIND_RESTRICT_ASSOCIATIONS) != 0) {
                mAm.requireAllowedAssociationsLocked(s.appInfo.packageName);
            }

            final boolean wasStartRequested = s.startRequested;
            final boolean hadConnections = !s.getConnections().isEmpty();
            mAm.startAssociationLocked(callerApp.uid, callerApp.processName,
                    callerApp.mState.getCurProcState(), s.appInfo.uid, s.appInfo.longVersionCode,
                    s.instanceName, s.processName);
            // Once the apps have become associated, if one of them is caller is ephemeral
            // the target app should now be able to see the calling app
            mAm.grantImplicitAccess(callerApp.userId, service,
                    callerApp.uid, UserHandle.getAppId(s.appInfo.uid));

            ConnectionRecord c = new ConnectionRecord(b, activity,
                    connection, flags, clientLabel, clientIntent,
                    callerApp.uid, callerApp.processName, callingPackage, res.aliasComponent);

            IBinder binder = connection.asBinder();
            s.addConnection(binder, c);
            b.connections.add(c);
            if (activity != null) {
                activity.addConnection(c);
            }
            clientPsr.addConnection(c);
            c.startAssociationIfNeeded();
            // Don't set hasAboveClient if binding to self to prevent modifyRawOomAdj() from
            // dropping the process' adjustment level.
            if (b.client != s.app && c.hasFlag(Context.BIND_ABOVE_CLIENT)) {
                clientPsr.setHasAboveClient(true);
            }
            if (c.hasFlag(BIND_ALLOW_WHITELIST_MANAGEMENT)) {
                s.allowlistManager = true;
            }
            if (c.hasFlag(Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS)) {
                s.setAllowedBgActivityStartsByBinding(true);
            }

            if (c.hasFlag(Context.BIND_NOT_APP_COMPONENT_USAGE)) {
                s.isNotAppComponentUsage = true;
            }

            if (s.app != null && s.app.mState != null
                    && s.app.mState.getCurProcState() <= PROCESS_STATE_TOP
                    && c.hasFlag(Context.BIND_ALMOST_PERCEPTIBLE)) {
                s.lastTopAlmostPerceptibleBindRequestUptimeMs = SystemClock.uptimeMillis();
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

            final boolean isolated = (s.serviceInfo.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0;
            final ProcessRecord hostApp = isolated
                    ? null
                    : mAm.getProcessRecordLocked(s.processName, s.appInfo.uid);
            final int serviceBindingOomAdjPolicy = hostApp != null
                    ? getServiceBindingOomAdjPolicyForAddLocked(b.client, hostApp, c)
                    : SERVICE_BIND_OOMADJ_POLICY_LEGACY;

            final boolean shouldFreezeCaller = !packageFrozen && !permissionsReviewRequired
                    && (serviceBindingOomAdjPolicy & SERVICE_BIND_OOMADJ_POLICY_FREEZE_CALLER) != 0
                    && callerApp.isFreezable();

            if (shouldFreezeCaller) {
                // Freeze the caller immediately, so the following #onBind/#onConnected will be
                // queued up in the app side as they're one way calls. And we'll also hold off
                // the service timeout timer until the process is unfrozen.
                mAm.mOomAdjuster.updateAppFreezeStateLSP(callerApp, OOM_ADJ_REASON_BIND_SERVICE,
                        true);
            }

            final boolean wasStopped = hostApp == null ? wasStopped(s) : false;
            final boolean firstLaunch =
                    hostApp == null ? !mAm.wasPackageEverLaunched(s.packageName, s.userId) : false;

            boolean needOomAdj = false;
            if (c.hasFlag(Context.BIND_AUTO_CREATE)) {
                s.lastActivity = SystemClock.uptimeMillis();
                needOomAdj = (serviceBindingOomAdjPolicy
                        & SERVICE_BIND_OOMADJ_POLICY_SKIP_OOM_UPDATE_ON_CREATE) == 0;
                if (bringUpServiceLocked(s, service.getFlags(), callerFg, false,
                        permissionsReviewRequired, packageFrozen, true, serviceBindingOomAdjPolicy)
                        != null) {
                    mAm.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_BIND_SERVICE);
                    return 0;
                }
            }
            setFgsRestrictionLocked(callingPackage, callingPid, callingUid, service, s, userId,
                    BackgroundStartPrivileges.NONE, true /* isBindService */);

            if (s.app != null) {
                ProcessServiceRecord servicePsr = s.app.mServices;
                if (c.hasFlag(Context.BIND_TREAT_LIKE_ACTIVITY)) {
                    servicePsr.setTreatLikeActivity(true);
                }
                if (s.allowlistManager) {
                    servicePsr.mAllowlistManager = true;
                }
                // This could have made the service more important.
                mAm.updateLruProcessLocked(s.app, (callerApp.hasActivitiesOrRecentTasks()
                            && servicePsr.hasClientActivities())
                        || (callerApp.mState.getCurProcState() <= PROCESS_STATE_TOP
                            && c.hasFlag(Context.BIND_TREAT_LIKE_ACTIVITY)),
                        b.client);
                if (!s.wasOomAdjUpdated() && (serviceBindingOomAdjPolicy
                        & SERVICE_BIND_OOMADJ_POLICY_SKIP_OOM_UPDATE_ON_CONNECT) == 0) {
                    needOomAdj = true;
                    mAm.enqueueOomAdjTargetLocked(s.app);
                }
            }
            if (needOomAdj) {
                mAm.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_BIND_SERVICE);
            }

            final int packageState = wasStopped
                    ? SERVICE_REQUEST_EVENT_REPORTED__PACKAGE_STOPPED_STATE__PACKAGE_STATE_STOPPED
                    : SERVICE_REQUEST_EVENT_REPORTED__PACKAGE_STOPPED_STATE__PACKAGE_STATE_NORMAL;
            if (DEBUG_PROCESSES) {
                Slog.d(TAG, "Logging bindService for " + s.packageName
                        + ", stopped=" + wasStopped + ", firstLaunch=" + firstLaunch);
            }
            FrameworkStatsLog.write(SERVICE_REQUEST_EVENT_REPORTED, s.appInfo.uid, callingUid,
                    ActivityManagerService.getShortAction(service.getAction()),
                    SERVICE_REQUEST_EVENT_REPORTED__REQUEST_TYPE__BIND, false,
                    s.app == null || s.app.getThread() == null
                    ? SERVICE_REQUEST_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_COLD
                    : (wasStartRequested || hadConnections
                    ? SERVICE_REQUEST_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_HOT
                    : SERVICE_REQUEST_EVENT_REPORTED__PROC_START_TYPE__PROCESS_START_TYPE_WARM),
                    getShortProcessNameForStats(callingUid, callerApp.processName),
                    getShortServiceNameForStats(s),
                    packageState,
                    s.packageName,
                    callerApp.info.packageName,
                    callerApp.mState.getCurProcState(),
                    s.mProcessStateOnRequest,
                    firstLaunch,
                    0L /* TODO */);

            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Bind " + s + " with " + b
                    + ": received=" + b.intent.received
                    + " apps=" + b.intent.apps.size()
                    + " doRebind=" + b.intent.doRebind);

            if (s.app != null && b.intent.received) {
                // Service is already running, so we can immediately
                // publish the connection.

                // If what the client try to start/connect was an alias, then we need to
                // pass the alias component name instead to the client.
                final ComponentName clientSideComponentName =
                        res.aliasComponent != null ? res.aliasComponent : s.name;
                try {
                    c.conn.connected(clientSideComponentName, b.intent.binder, false);
                } catch (Exception e) {
                    Slog.w(TAG, "Failure sending service " + s.shortInstanceName
                            + " to connection " + c.conn.asBinder()
                            + " (in " + c.binding.client.processName + ")", e);
                }

                // If this is the first app connected back to this binding,
                // and the service had previously asked to be told when
                // rebound, then do so.
                if (b.intent.apps.size() == 1 && b.intent.doRebind) {
                    requestServiceBindingLocked(s, b.intent, callerFg, true,
                            serviceBindingOomAdjPolicy);
                }
            } else if (!b.intent.requested) {
                requestServiceBindingLocked(s, b.intent, callerFg, false,
                        serviceBindingOomAdjPolicy);
            }

            maybeLogBindCrossProfileService(userId, callingPackage, callerApp.info.uid);

            getServiceMapLocked(s.userId).ensureNotStartingBackgroundLocked(s);

        } finally {
            mAm.mInjector.restoreCallingIdentity(origId);
        }

        notifyBindingServiceEventLocked(callerApp, callingPackage);

        return 1;
    }

    @GuardedBy("mAm")
    private void notifyBindingServiceEventLocked(ProcessRecord callerApp, String callingPackage) {
        final ApplicationInfo ai = callerApp.info;
        final String callerPackage = ai != null ? ai.packageName : callingPackage;
        if (callerPackage != null) {
            mAm.mHandler.obtainMessage(ActivityManagerService.DISPATCH_BINDING_SERVICE_EVENT,
                    callerApp.uid, 0, callerPackage).sendToTarget();
        }
    }

    private void maybeLogBindCrossProfileService(
            int userId, String callingPackage, int callingUid) {
        if (UserHandle.isCore(callingUid)) {
            return;
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (callingUserId == userId
                || !mAm.mUserController.isSameProfileGroup(callingUserId, userId)) {
            return;
        }
        DevicePolicyEventLogger.createEvent(DevicePolicyEnums.BIND_CROSS_PROFILE_SERVICE)
                .setStrings(callingPackage)
                .write();
    }

    void publishServiceLocked(ServiceRecord r, Intent intent, IBinder service) {
        final long origId = mAm.mInjector.clearCallingIdentity();
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
                            // If what the client try to start/connect was an alias, then we need to
                            // pass the alias component name instead to the client.
                            final ComponentName clientSideComponentName =
                                    c.aliasComponent != null ? c.aliasComponent : r.name;
                            try {
                                c.conn.connected(clientSideComponentName, service, false);
                            } catch (Exception e) {
                                Slog.w(TAG, "Failure sending service " + r.shortInstanceName
                                      + " to connection " + c.conn.asBinder()
                                      + " (in " + c.binding.client.processName + ")", e);
                            }
                        }
                    }
                }

                serviceDoneExecutingLocked(r, mDestroyingServices.contains(r), false, false,
                        !Flags.serviceBindingOomAdjPolicy() || r.wasOomAdjUpdated()
                        ? OOM_ADJ_REASON_EXECUTING_SERVICE : OOM_ADJ_REASON_NONE);
            }
        } finally {
            mAm.mInjector.restoreCallingIdentity(origId);
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

        final int callingPid = mAm.mInjector.getCallingPid();
        final long origId = mAm.mInjector.clearCallingIdentity();
        try {
            if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
                String info;
                if (clist.size() > 0) {
                    final ConnectionRecord r = clist.get(0);
                    info = r.binding.service.shortInstanceName + " from " + r.clientProcessName;
                } else {
                    info = Integer.toString(callingPid);
                }
                Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "unbindServiceLocked: " + info);
            }

            boolean needOomAdj = false;
            while (clist.size() > 0) {
                ConnectionRecord r = clist.get(0);
                int serviceBindingOomAdjPolicy = removeConnectionLocked(r, null, null, true);
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
                    if (r.hasFlag(Context.BIND_TREAT_LIKE_ACTIVITY)) {
                        psr.setTreatLikeActivity(true);
                        mAm.updateLruProcessLocked(app, true, null);
                    }
                    // If the bindee is more important than the binder, we may skip the OomAdjuster.
                    if (serviceBindingOomAdjPolicy == SERVICE_BIND_OOMADJ_POLICY_LEGACY) {
                        mAm.enqueueOomAdjTargetLocked(app);
                        needOomAdj = true;
                    }
                }
            }

            if (needOomAdj) {
                mAm.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_UNBIND_SERVICE);
            }

        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
            mAm.mInjector.restoreCallingIdentity(origId);
        }

        return true;
    }

    void unbindFinishedLocked(ServiceRecord r, Intent intent, boolean doRebind) {
        final long origId = mAm.mInjector.clearCallingIdentity();
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
                            requestServiceBindingLocked(r, b, inFg, true,
                                    SERVICE_BIND_OOMADJ_POLICY_LEGACY);
                        } catch (TransactionTooLargeException e) {
                            // Don't pass this back to ActivityThread, it's unrelated.
                        }
                    } else {
                        // Note to tell the service the next time there is
                        // a new client.
                        b.doRebind = true;
                    }
                }

                serviceDoneExecutingLocked(r, inDestroying, false, false,
                        !Flags.serviceBindingOomAdjPolicy() || r.wasOomAdjUpdated()
                        ? OOM_ADJ_REASON_UNBIND_SERVICE : OOM_ADJ_REASON_NONE);
            }
        } finally {
            mAm.mInjector.restoreCallingIdentity(origId);
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

        /**
         * Set only when we looked up to this service via an alias. Otherwise, it's null.
         */
        @Nullable
        final ComponentName aliasComponent;

        ServiceLookupResult(ServiceRecord _record, ComponentName _aliasComponent) {
            record = _record;
            permission = null;
            aliasComponent = _aliasComponent;
        }

        ServiceLookupResult(String _permission) {
            record = null;
            permission = _permission;
            aliasComponent = null;
        }
    }

    private class ServiceRestarter implements Runnable {
        private ServiceRecord mService;

        void setService(ServiceRecord service) {
            mService = service;
        }

        public void run() {
            synchronized (mAm) {
                performServiceRestartLocked(mService);
            }
        }
    }

    private ServiceLookupResult retrieveServiceLocked(Intent service,
            String instanceName, String resolvedType, String callingPackage, int callingPid,
            int callingUid, int userId, boolean createIfNeeded, boolean callingFromFg,
            boolean isBindExternal, boolean allowInstant, boolean inSharedIsolatedProcess,
            boolean inPrivateSharedIsolatedProcess) {
        return retrieveServiceLocked(service, instanceName, false, INVALID_UID, null, resolvedType,
                callingPackage, callingPid, callingUid, userId, createIfNeeded, callingFromFg,
                isBindExternal, allowInstant, null /* fgsDelegateOptions */,
                inSharedIsolatedProcess, inPrivateSharedIsolatedProcess);
    }

    // TODO(b/265746493): Special case for HotwordDetectionService,
    // VisualQueryDetectionService, WearableSensingService and OnDeviceSandboxedInferenceService
    // Need a cleaner way to append this seInfo.
    private String generateAdditionalSeInfoFromService(Intent service) {
        if (service != null && service.getAction() != null
                && (service.getAction().equals(HotwordDetectionService.SERVICE_INTERFACE)
                || service.getAction().equals(VisualQueryDetectionService.SERVICE_INTERFACE)
                || service.getAction().equals(WearableSensingService.SERVICE_INTERFACE)
            || service.getAction().equals(OnDeviceSandboxedInferenceService.SERVICE_INTERFACE))) {
            return ":isolatedComputeApp";
        }
        return "";
    }

    private ServiceLookupResult retrieveServiceLocked(Intent service,
            String instanceName, boolean isSdkSandboxService, int sdkSandboxClientAppUid,
            String sdkSandboxClientAppPackage, String resolvedType,
            String callingPackage, int callingPid, int callingUid, int userId,
            boolean createIfNeeded, boolean callingFromFg, boolean isBindExternal,
            boolean allowInstant, ForegroundServiceDelegationOptions fgsDelegateOptions,
            boolean inSharedIsolatedProcess, boolean inPrivateSharedIsolatedProcess) {
        return retrieveServiceLocked(service, instanceName, isSdkSandboxService,
                sdkSandboxClientAppUid, sdkSandboxClientAppPackage, resolvedType, callingPackage,
                callingPid, callingUid, userId, createIfNeeded, callingFromFg, isBindExternal,
                allowInstant, fgsDelegateOptions, inSharedIsolatedProcess,
                inPrivateSharedIsolatedProcess, false /* matchQuarantined */);
    }

    private ServiceLookupResult retrieveServiceLocked(
            Intent service, String instanceName, boolean isSdkSandboxService,
            int sdkSandboxClientAppUid, String sdkSandboxClientAppPackage, String resolvedType,
            String callingPackage, int callingPid, int callingUid, int userId,
            boolean createIfNeeded, boolean callingFromFg, boolean isBindExternal,
            boolean allowInstant, ForegroundServiceDelegationOptions fgsDelegateOptions,
            boolean inSharedIsolatedProcess, boolean inPrivateSharedIsolatedProcess,
            boolean matchQuarantined) {
        if (isSdkSandboxService && instanceName == null) {
            throw new IllegalArgumentException("No instanceName provided for sdk sandbox process");
        }

        ServiceRecord r = null;
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "retrieveServiceLocked: " + service
                + " type=" + resolvedType + " callingUid=" + callingUid);

        userId = mAm.mUserController.handleIncomingUser(callingPid, callingUid, userId,
                /* allowAll= */false, getAllowMode(service, callingPackage),
                /* name= */ "service", callingPackage);

        ServiceMap smap = getServiceMapLocked(userId);

        // See if the intent refers to an alias. If so, update the intent with the target component
        // name. `resolution` will contain the alias component name, which we need to return
        // to the client.
        final ComponentAliasResolver.Resolution<ComponentName> resolution =
                mAm.mComponentAliasResolver.resolveService(service, resolvedType,
                        /* match flags */ 0, userId, callingUid);

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

        if (r == null && fgsDelegateOptions != null) {
            // Create a ServiceRecord for FGS delegate.
            final ServiceInfo sInfo = new ServiceInfo();
            ApplicationInfo aInfo = null;
            try {
                aInfo = AppGlobals.getPackageManager().getApplicationInfo(
                        fgsDelegateOptions.mClientPackageName,
                        ActivityManagerService.STOCK_PM_FLAGS,
                        userId);
            } catch (RemoteException ex) {
            // pm is in same process, this will never happen.
            }
            if (aInfo == null) {
                throw new SecurityException("startForegroundServiceDelegate failed, "
                        + "could not resolve client package " + callingPackage);
            }
            if (aInfo.uid != fgsDelegateOptions.mClientUid) {
                throw new SecurityException("startForegroundServiceDelegate failed, "
                        + "uid:" + aInfo.uid
                        + " does not match clientUid:" + fgsDelegateOptions.mClientUid);
            }
            sInfo.applicationInfo = aInfo;
            sInfo.packageName = aInfo.packageName;
            sInfo.mForegroundServiceType = fgsDelegateOptions.mForegroundServiceTypes;
            sInfo.processName = aInfo.processName;
            final ComponentName cn = service.getComponent();
            sInfo.name = cn.getClassName();
            if (createIfNeeded) {
                final Intent.FilterComparison filter =
                        new Intent.FilterComparison(service.cloneFilter());
                final ServiceRestarter res = new ServiceRestarter();
                final String processName = getProcessNameForService(sInfo, cn, callingPackage,
                        null /* instanceName */, false /* isSdkSandbox */,
                        false /* inSharedIsolatedProcess */,
                        false /*inPrivateSharedIsolatedProcess*/);
                r = new ServiceRecord(mAm, cn /* name */, cn /* instanceName */,
                        sInfo.applicationInfo.packageName, sInfo.applicationInfo.uid, filter, sInfo,
                        callingFromFg, res, processName,
                        INVALID_UID /* sdkSandboxClientAppUid */,
                        null /* sdkSandboxClientAppPackage */,
                        false /* inSharedIsolatedProcess */);
                r.foregroundId = fgsDelegateOptions.mClientNotificationId;
                r.foregroundNoti = fgsDelegateOptions.mClientNotification;
                res.setService(r);
                smap.mServicesByInstanceName.put(cn, r);
                smap.mServicesByIntent.put(filter, r);
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Retrieve created new service: " + r);
                r.mRecentCallingPackage = callingPackage;
                r.mRecentCallingUid = callingUid;
            }
            r.appInfo.seInfo += generateAdditionalSeInfoFromService(service);
            return new ServiceLookupResult(r, resolution.getAlias());
        }

        if (r == null) {
            try {
                long flags = ActivityManagerService.STOCK_PM_FLAGS
                        | PackageManager.MATCH_DEBUG_TRIAGED_MISSING;
                if (allowInstant) {
                    flags |= PackageManager.MATCH_INSTANT;
                }
                if (matchQuarantined) {
                    flags |= PackageManager.MATCH_QUARANTINED_COMPONENTS;
                }
                // TODO: come back and remove this assumption to triage all services
                ResolveInfo rInfo = mAm.getPackageManagerInternal().resolveService(service,
                        resolvedType, flags, userId, callingUid, callingPid);
                ServiceInfo sInfo = rInfo != null ? rInfo.serviceInfo : null;
                if (sInfo == null) {
                    Slog.w(TAG_SERVICE, "Unable to start service " + service + " U=" + userId +
                          ": not found");
                    return null;
                }
                if (instanceName != null
                        && (sInfo.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) == 0
                        && !isSdkSandboxService) {
                    throw new IllegalArgumentException("Can't use instance name '" + instanceName
                            + "' with non-isolated non-sdk sandbox service '" + sInfo.name + "'");
                }
                if (isSdkSandboxService
                        && (sInfo.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0) {
                    throw new IllegalArgumentException("Service cannot be both sdk sandbox and "
                            + "isolated");
                }

                ComponentName className = new ComponentName(sInfo.applicationInfo.packageName,
                                                            sInfo.name);
                ComponentName name = comp != null ? comp : className;
                if (!mAm.validateAssociationAllowedLocked(callingPackage, callingUid,
                        name.getPackageName(), sInfo.applicationInfo.uid)) {
                    String msg = "association not allowed between packages "
                            + callingPackage + " and " + name.getPackageName();
                    Slog.w(TAG, "Service lookup failed: " + msg);
                    return new ServiceLookupResult(msg);
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
                        if (inPrivateSharedIsolatedProcess) {
                            throw new SecurityException("BIND_PACKAGE_ISOLATED_PROCESS cannot be "
                                    + "applied to an external service.");
                        }
                        if ((sInfo.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) == 0) {
                            throw new SecurityException("BIND_EXTERNAL_SERVICE failed, "
                                    + className + " is not an isolatedProcess");
                        }
                        if (!mAm.getPackageManagerInternal().isSameApp(callingPackage, callingUid,
                                userId)) {
                            throw new SecurityException("BIND_EXTERNAL_SERVICE failed, "
                                    + "calling package not owned by calling UID ");
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
                if (inSharedIsolatedProcess && inPrivateSharedIsolatedProcess) {
                    throw new SecurityException("Either BIND_SHARED_ISOLATED_PROCESS or "
                            + "BIND_PACKAGE_ISOLATED_PROCESS should be set. Not both.");
                }
                if (inSharedIsolatedProcess || inPrivateSharedIsolatedProcess) {
                    if ((sInfo.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) == 0) {
                        throw new SecurityException("BIND_SHARED_ISOLATED_PROCESS failed, "
                                + className + " is not an isolatedProcess");
                    }
                }
                if (inPrivateSharedIsolatedProcess && isDefaultProcessService(sInfo)) {
                    throw new SecurityException("BIND_PACKAGE_ISOLATED_PROCESS cannot be used for "
                            + "services running in the main app process.");
                }
                if (inSharedIsolatedProcess) {
                    if (instanceName == null) {
                        throw new IllegalArgumentException("instanceName must be provided for "
                                + "binding a service into a shared isolated process.");
                    }
                    if ((sInfo.flags & ServiceInfo.FLAG_ALLOW_SHARED_ISOLATED_PROCESS) == 0) {
                        throw new SecurityException("BIND_SHARED_ISOLATED_PROCESS failed, "
                                + className + " has not set the allowSharedIsolatedProcess "
                                + " attribute.");
                    }
                }
                if (userId > 0) {
                    if (mAm.isSystemUserOnly(sInfo.flags)) {
                        Slog.w(TAG_SERVICE, service + " is only available for the SYSTEM user,"
                                + " calling userId is: " + userId);
                        return null;
                    }

                    if (mAm.isSingleton(sInfo.processName, sInfo.applicationInfo,
                            sInfo.name, sInfo.flags)
                            && mAm.isValidSingletonCall(callingUid, sInfo.applicationInfo.uid)) {
                        userId = 0;
                        smap = getServiceMapLocked(0);
                        // Bypass INTERACT_ACROSS_USERS permission check
                        final long token = mAm.mInjector.clearCallingIdentity();
                        try {
                            ResolveInfo rInfoForUserId0 =
                                    mAm.getPackageManagerInternal().resolveService(service,
                                            resolvedType, flags, userId, callingUid, callingPid);
                            if (rInfoForUserId0 == null) {
                                Slog.w(TAG_SERVICE,
                                        "Unable to resolve service " + service + " U=" + userId
                                                + ": not found");
                                return null;
                            }
                            sInfo = rInfoForUserId0.serviceInfo;
                        } finally {
                            mAm.mInjector.restoreCallingIdentity(token);
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
                    String processName = getProcessNameForService(sInfo, name, callingPackage,
                            instanceName, isSdkSandboxService, inSharedIsolatedProcess,
                            inPrivateSharedIsolatedProcess);
                    r = new ServiceRecord(mAm, className, name, definingPackageName,
                            definingUid, filter, sInfo, callingFromFg, res,
                            processName, sdkSandboxClientAppUid,
                            sdkSandboxClientAppPackage,
                            (inSharedIsolatedProcess || inPrivateSharedIsolatedProcess));
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
                return new ServiceLookupResult(msg);
            }
            if (!mAm.mIntentFirewall.checkService(r.name, service, callingUid, callingPid,
                    resolvedType, r.appInfo)) {
                return new ServiceLookupResult("blocked by firewall");
            }
            if (mAm.checkComponentPermission(r.permission,
                    callingPid, callingUid, r.appInfo.uid, r.exported) != PERMISSION_GRANTED) {
                if (!r.exported) {
                    Slog.w(TAG, "Permission Denial: Accessing service " + r.shortInstanceName
                            + " from pid=" + callingPid
                            + ", uid=" + callingUid
                            + " that is not exported from uid " + r.appInfo.uid);
                    return new ServiceLookupResult("not exported from uid "
                            + r.appInfo.uid);
                }
                Slog.w(TAG, "Permission Denial: Accessing service " + r.shortInstanceName
                        + " from pid=" + callingPid
                        + ", uid=" + callingUid
                        + " requires " + r.permission);
                return new ServiceLookupResult(r.permission);
            } else if ((Manifest.permission.BIND_HOTWORD_DETECTION_SERVICE.equals(r.permission)
                    || Manifest.permission.BIND_VISUAL_QUERY_DETECTION_SERVICE.equals(r.permission))
                    && callingUid != Process.SYSTEM_UID) {
                // Hotword detection and visual query detection must run in its own sandbox, and we
                // don't even trust its enclosing application to bind to it - only the system.
                // TODO(b/185746653) remove this special case and generalize
                Slog.w(TAG, "Permission Denial: Accessing service " + r.shortInstanceName
                        + " from pid=" + callingPid
                        + ", uid=" + callingUid
                        + " requiring permission " + r.permission
                        + " can only be bound to from the system.");
                return new ServiceLookupResult("can only be bound to "
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
            r.appInfo.seInfo += generateAdditionalSeInfoFromService(service);
            return new ServiceLookupResult(r, resolution.getAlias());
        }
        return null;
    }

    private int getAllowMode(Intent service, @Nullable String callingPackage) {
        if (callingPackage != null && service.getComponent() != null
                && callingPackage.equals(service.getComponent().getPackageName())) {
            return ActivityManagerInternal.ALLOW_PROFILES_OR_NON_FULL;
        } else {
            return ActivityManagerInternal.ALLOW_NON_FULL_IN_PROFILE;
        }
    }

    /**
     * Bump the given service record into executing state.
     * @param oomAdjReason The caller requests it to perform the oomAdjUpdate not {@link
     *         ActivityManagerInternal#OOM_ADJ_REASON_NONE}.
     */
    private void bumpServiceExecutingLocked(
            ServiceRecord r, boolean fg, String why, @OomAdjReason int oomAdjReason,
            boolean skipTimeoutIfPossible) {
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

        // If the process is frozen or to be frozen, and we want to skip the timeout, skip it.
        final boolean shouldSkipTimeout = skipTimeoutIfPossible && r.app != null
                && (r.app.mOptRecord.isPendingFreeze() || r.app.mOptRecord.isFrozen());

        ProcessServiceRecord psr;
        if (r.executeNesting == 0) {
            r.executeFg = fg;
            synchronized (mAm.mProcessStats.mLock) {
                final ServiceState stracker = r.getTracker();
                if (stracker != null) {
                    stracker.setExecuting(true, mAm.mProcessStats.getMemFactorLocked(),
                            SystemClock.uptimeMillis());
                }
            }
            if (r.app != null) {
                psr = r.app.mServices;
                psr.startExecutingService(r);
                psr.setExecServicesFg(psr.shouldExecServicesFg() || fg);
                if (timeoutNeeded && psr.numberOfExecutingServices() == 1) {
                    if (!shouldSkipTimeout) {
                        scheduleServiceTimeoutLocked(r.app);
                    } else {
                        r.app.mServices.noteScheduleServiceTimeoutPending(true);
                    }
                }
            }
        } else if (r.app != null && fg) {
            psr = r.app.mServices;
            if (!psr.shouldExecServicesFg()) {
                psr.setExecServicesFg(true);
                if (timeoutNeeded) {
                    if (!shouldSkipTimeout) {
                        scheduleServiceTimeoutLocked(r.app);
                    } else {
                        r.app.mServices.noteScheduleServiceTimeoutPending(true);
                    }
                }
            }
        }
        if (r.app != null
                && r.app.mState.getCurProcState() > ActivityManager.PROCESS_STATE_SERVICE) {
            // Enqueue the oom adj target anyway for opportunistic oom adj updates.
            mAm.enqueueOomAdjTargetLocked(r.app);
            r.updateOomAdjSeq();
            if (oomAdjReason != OOM_ADJ_REASON_NONE) {
                // Force an immediate oomAdjUpdate, so the client app could be in the correct
                // process state before doing any service related transactions
                mAm.updateOomAdjPendingTargetsLocked(oomAdjReason);
            }
        }
        r.executeFg |= fg;
        r.executeNesting++;
        r.executingStart = SystemClock.uptimeMillis();
    }

    private final boolean requestServiceBindingLocked(ServiceRecord r, IntentBindRecord i,
            boolean execInFg, boolean rebind,
            @ServiceBindingOomAdjPolicy int serviceBindingOomAdjPolicy)
            throws TransactionTooLargeException {
        if (r.app == null || r.app.getThread() == null) {
            // If service is not currently running, can't yet bind.
            return false;
        }
        if (DEBUG_SERVICE) Slog.d(TAG_SERVICE, "requestBind " + i + ": requested=" + i.requested
                + " rebind=" + rebind);
        final boolean skipOomAdj = (serviceBindingOomAdjPolicy
                & SERVICE_BIND_OOMADJ_POLICY_SKIP_OOM_UPDATE_ON_BIND) != 0;
        if ((!i.requested || rebind) && i.apps.size() > 0) {
            try {
                bumpServiceExecutingLocked(r, execInFg, "bind",
                        skipOomAdj ? OOM_ADJ_REASON_NONE : OOM_ADJ_REASON_BIND_SERVICE,
                        skipOomAdj /* skipTimeoutIfPossible */);
                if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
                    Trace.instant(Trace.TRACE_TAG_ACTIVITY_MANAGER, "requestServiceBinding="
                            + i.intent.getIntent() + ". bindSeq=" + mBindServiceSeqCounter);
                }
                r.app.getThread().scheduleBindService(r, i.intent.getIntent(), rebind,
                        r.app.mState.getReportedProcState(), mBindServiceSeqCounter++);
                if (!rebind) {
                    i.requested = true;
                }
                i.hasBound = true;
                i.doRebind = false;
            } catch (TransactionTooLargeException e) {
                // Keep the executeNesting count accurate.
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Crashed while binding " + r, e);
                final boolean inDestroying = mDestroyingServices.contains(r);
                serviceDoneExecutingLocked(r, inDestroying, inDestroying, false,
                        !Flags.serviceBindingOomAdjPolicy() || r.wasOomAdjUpdated()
                        ? OOM_ADJ_REASON_UNBIND_SERVICE : OOM_ADJ_REASON_NONE);
                throw e;
            } catch (RemoteException e) {
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Crashed while binding " + r);
                // Keep the executeNesting count accurate.
                final boolean inDestroying = mDestroyingServices.contains(r);
                serviceDoneExecutingLocked(r, inDestroying, inDestroying, false,
                        !Flags.serviceBindingOomAdjPolicy() || r.wasOomAdjUpdated()
                        ? OOM_ADJ_REASON_UNBIND_SERVICE : OOM_ADJ_REASON_NONE);
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
        final int oldPosInRestarting = mRestartingServices.indexOf(r);
        boolean inRestarting = oldPosInRestarting != -1;
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
                r.nextRestartTime = r.mEarliestRestartTime = now + r.restartDelay;

                if (inRestarting) {
                    // Take it out of the list temporarily for easier maintenance of the list.
                    mRestartingServices.remove(oldPosInRestarting);
                    inRestarting = false;
                }
                if (mRestartingServices.isEmpty()) {
                    // Apply the extra delay even if it's the only one in the list.
                    final long extraDelay = getExtraRestartTimeInBetweenLocked();
                    r.nextRestartTime = Math.max(now + extraDelay, r.nextRestartTime);
                    r.restartDelay = r.nextRestartTime - now;
                } else {
                    // Make sure that we don't end up restarting a bunch of services
                    // all at the same time.
                    boolean repeat;
                    final long restartTimeBetween = getExtraRestartTimeInBetweenLocked()
                            + mAm.mConstants.SERVICE_MIN_RESTART_TIME_BETWEEN;
                    do {
                        repeat = false;
                        final long nextRestartTime = r.nextRestartTime;
                        // mRestartingServices is sorted by nextRestartTime.
                        for (int i = mRestartingServices.size() - 1; i >= 0; i--) {
                            final ServiceRecord r2 = mRestartingServices.get(i);
                            final long nextRestartTime2 = r2.nextRestartTime;
                            if (nextRestartTime >= (nextRestartTime2 - restartTimeBetween)
                                    && nextRestartTime < (nextRestartTime2 + restartTimeBetween)) {
                                r.nextRestartTime = nextRestartTime2 + restartTimeBetween;
                                r.restartDelay = r.nextRestartTime - now;
                                repeat = true;
                                break;
                            } else if (nextRestartTime >= nextRestartTime2 + restartTimeBetween) {
                                // This spot fulfills our needs, bail out.
                                break;
                            }
                        }
                    } while (repeat);
                }
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
            r.mEarliestRestartTime = 0;
            r.nextRestartTime = now;
            reason = "persistent";
        }

        r.mRestartSchedulingTime = now;
        if (!inRestarting) {
            if (oldPosInRestarting == -1) {
                r.createdFromFg = false;
                synchronized (mAm.mProcessStats.mLock) {
                    r.makeRestarting(mAm.mProcessStats.getMemFactorLocked(),
                            SystemClock.uptimeMillis());
                }
            }
            boolean added = false;
            for (int i = 0, size = mRestartingServices.size(); i < size; i++) {
                final ServiceRecord r2 = mRestartingServices.get(i);
                if (r2.nextRestartTime > r.nextRestartTime) {
                    mRestartingServices.add(i, r);
                    added = true;
                    break;
                }
            }
            if (!added) {
                mRestartingServices.add(r);
            }
        }

        cancelForegroundNotificationLocked(r);

        performScheduleRestartLocked(r, "Scheduling", reason, now);

        return true;
    }

    @GuardedBy("mAm")
    void performScheduleRestartLocked(ServiceRecord r, @NonNull String scheduling,
            @NonNull String reason, @UptimeMillisLong long now) {

        // If the service is waiting to become a foreground service, remove the pending
        // SERVICE_FOREGROUND_TIMEOUT_MSG msg, and set fgWaiting to false, so next time the service
        // is brought up, scheduleServiceForegroundTransitionTimeoutLocked() can be called again and
        // a new SERVICE_FOREGROUND_TIMEOUT_MSG is scheduled in SERVICE_START_FOREGROUND_TIMEOUT
        // again.
        if (r.fgRequired && r.fgWaiting) {
            mServiceFGAnrTimer.cancel(r);
            r.fgWaiting = false;
        }

        mAm.mHandler.removeCallbacks(r.restarter);
        mAm.mHandler.postAtTime(r.restarter, r.nextRestartTime);
        r.nextRestartTime = now + r.restartDelay;
        Slog.w(TAG, scheduling + " restart of crashed service "
                + r.shortInstanceName + " in " + r.restartDelay + "ms for " + reason);
        EventLog.writeEvent(EventLogTags.AM_SCHEDULE_SERVICE_RESTART,
                r.userId, r.shortInstanceName, r.restartDelay);
    }

    /**
     * Reschedule service restarts based on the given memory pressure.
     *
     * @param prevMemFactor The previous memory factor.
     * @param curMemFactor The current memory factor.
     * @param reason The human-readable text about why we're doing rescheduling.
     * @param now The uptimeMillis
     */
    @GuardedBy("mAm")
    void rescheduleServiceRestartOnMemoryPressureIfNeededLocked(@MemFactor int prevMemFactor,
            @MemFactor int curMemFactor, @NonNull String reason, @UptimeMillisLong long now) {
        final boolean enabled = mAm.mConstants.mEnableExtraServiceRestartDelayOnMemPressure;
        if (!enabled) {
            return;
        }
        performRescheduleServiceRestartOnMemoryPressureLocked(
                mAm.mConstants.mExtraServiceRestartDelayOnMemPressure[prevMemFactor],
                mAm.mConstants.mExtraServiceRestartDelayOnMemPressure[curMemFactor], reason, now);
    }

    /**
     * Reschedule service restarts based on if the extra delays are enabled or not.
     *
     * @param prevEnabled The previous state of whether or not it's enabled.
     * @param curEnabled The current state of whether or not it's enabled.
     * @param now The uptimeMillis
     */
    @GuardedBy("mAm")
    void rescheduleServiceRestartOnMemoryPressureIfNeededLocked(boolean prevEnabled,
            boolean curEnabled, @UptimeMillisLong long now) {
        if (prevEnabled == curEnabled) {
            return;
        }
        final @MemFactor int memFactor = mAm.mAppProfiler.getLastMemoryLevelLocked();
        final long delay = mAm.mConstants.mExtraServiceRestartDelayOnMemPressure[memFactor];
        performRescheduleServiceRestartOnMemoryPressureLocked(prevEnabled ? delay : 0,
                curEnabled ? delay : 0, "config", now);
    }

    /**
     * Rescan the list of pending restarts, reschedule them if needed.
     *
     * @param extraRestartTimeBetween The extra interval between restarts.
     * @param minRestartTimeBetween The minimal interval between restarts.
     * @param reason The human-readable text about why we're doing rescheduling.
     * @param now The uptimeMillis
     */
    @GuardedBy("mAm")
    void rescheduleServiceRestartIfPossibleLocked(long extraRestartTimeBetween,
            long minRestartTimeBetween, @NonNull String reason, @UptimeMillisLong long now) {
        final long restartTimeBetween = extraRestartTimeBetween + minRestartTimeBetween;
        final long spanForInsertOne = restartTimeBetween * 2; // Min space to insert a restart.

        long lastRestartTime = now;
        int lastRestartTimePos = -1; // The list index where the "lastRestartTime" comes from.
        for (int i = 0, size = mRestartingServices.size(); i < size; i++) {
            final ServiceRecord r = mRestartingServices.get(i);
            if ((r.serviceInfo.applicationInfo.flags & ApplicationInfo.FLAG_PERSISTENT) != 0
                    || !isServiceRestartBackoffEnabledLocked(r.packageName)) {
                lastRestartTime = r.nextRestartTime;
                lastRestartTimePos = i;
                continue;
            }
            if (lastRestartTime + restartTimeBetween <= r.mEarliestRestartTime) {
                // Bounded by the earliest restart time, honor it; but we also need to
                // check if the interval between the earlist and its prior one is enough or not.
                r.nextRestartTime = Math.max(now, Math.max(r.mEarliestRestartTime, i > 0
                        ? mRestartingServices.get(i - 1).nextRestartTime + restartTimeBetween
                        : 0));
            } else {
                if (lastRestartTime <= now) {
                    // It hasn't moved, this is the first one (besides persistent process),
                    // we don't need to insert the minRestartTimeBetween for it, but need
                    // the extraRestartTimeBetween still.
                    r.nextRestartTime = Math.max(now, Math.max(r.mEarliestRestartTime,
                            r.mRestartSchedulingTime + extraRestartTimeBetween));
                } else {
                    r.nextRestartTime = Math.max(now, lastRestartTime + restartTimeBetween);
                }
                if (i > lastRestartTimePos + 1) {
                    // Move the current service record ahead in the list.
                    mRestartingServices.remove(i);
                    mRestartingServices.add(lastRestartTimePos + 1, r);
                }
            }
            // Find the next available slot to insert one if there is any
            for (int j = lastRestartTimePos + 1; j <= i; j++) {
                final ServiceRecord r2 = mRestartingServices.get(j);
                final long timeInBetween = r2.nextRestartTime - (j == 0 ? lastRestartTime
                        : mRestartingServices.get(j - 1).nextRestartTime);
                if (timeInBetween >= spanForInsertOne) {
                    break;
                }
                lastRestartTime = r2.nextRestartTime;
                lastRestartTimePos = j;
            }
            r.restartDelay = r.nextRestartTime - now;
            performScheduleRestartLocked(r, "Rescheduling", reason, now);
        }
    }

    @GuardedBy("mAm")
    void performRescheduleServiceRestartOnMemoryPressureLocked(long oldExtraDelay,
            long newExtraDelay, @NonNull String reason, @UptimeMillisLong long now) {
        final long delta = newExtraDelay - oldExtraDelay;
        if (delta == 0) {
            return;
        }
        if (delta > 0) {
            final long restartTimeBetween = mAm.mConstants.SERVICE_MIN_RESTART_TIME_BETWEEN
                    + newExtraDelay;
            long lastRestartTime = now;
            // Make the delay in between longer.
            for (int i = 0, size = mRestartingServices.size(); i < size; i++) {
                final ServiceRecord r = mRestartingServices.get(i);
                if ((r.serviceInfo.applicationInfo.flags & ApplicationInfo.FLAG_PERSISTENT) != 0
                        || !isServiceRestartBackoffEnabledLocked(r.packageName)) {
                    lastRestartTime = r.nextRestartTime;
                    continue;
                }
                boolean reschedule = false;
                if (lastRestartTime <= now) {
                    // It hasn't moved, this is the first one (besides persistent process),
                    // we don't need to insert the minRestartTimeBetween for it, but need
                    // the newExtraDelay still.
                    final long oldVal = r.nextRestartTime;
                    r.nextRestartTime = Math.max(now, Math.max(r.mEarliestRestartTime,
                            r.mRestartSchedulingTime + newExtraDelay));
                    reschedule = r.nextRestartTime != oldVal;
                } else if (r.nextRestartTime - lastRestartTime < restartTimeBetween) {
                    r.nextRestartTime = Math.max(lastRestartTime + restartTimeBetween, now);
                    reschedule = true;
                }
                r.restartDelay = r.nextRestartTime - now;
                lastRestartTime = r.nextRestartTime;
                if (reschedule) {
                    performScheduleRestartLocked(r, "Rescheduling", reason, now);
                }
            }
        } else if (delta < 0) {
            // Make the delay in between shorter, we'd do a rescan and reschedule.
            rescheduleServiceRestartIfPossibleLocked(newExtraDelay,
                    mAm.mConstants.SERVICE_MIN_RESTART_TIME_BETWEEN, reason, now);
        }
    }

    @GuardedBy("mAm")
    long getExtraRestartTimeInBetweenLocked() {
        if (!mAm.mConstants.mEnableExtraServiceRestartDelayOnMemPressure) {
            return 0;
        }
        final @MemFactor int memFactor = mAm.mAppProfiler.getLastMemoryLevelLocked();
        return mAm.mConstants.mExtraServiceRestartDelayOnMemPressure[memFactor];
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
                    false, true, SERVICE_BIND_OOMADJ_POLICY_LEGACY);
        } catch (TransactionTooLargeException e) {
            // Ignore, it's been logged and nothing upstack cares.
        } finally {
            /* Will be a no-op if nothing pending */
            mAm.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_START_SERVICE);
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
                synchronized (mAm.mProcessStats.mLock) {
                    r.restartTracker.setRestarting(false, mAm.mProcessStats.getMemFactorLocked(),
                            SystemClock.uptimeMillis());
                }
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
                // mRestartingServices is sorted by nextRestartTime.
                Collections.sort(mRestartingServices,
                        (a, b) -> (int) (a.nextRestartTime - b.nextRestartTime));
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
            boolean enqueueOomAdj, @ServiceBindingOomAdjPolicy int serviceBindingOomAdjPolicy)
            throws TransactionTooLargeException {
        try {
            if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
                Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                        "bringUpServiceLocked: " + r.shortInstanceName);
            }
            return bringUpServiceInnerLocked(r, intentFlags, execInFg, whileRestarting,
                    permissionsReviewRequired, packageFrozen, enqueueOomAdj,
                    serviceBindingOomAdjPolicy);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    private String bringUpServiceInnerLocked(ServiceRecord r, int intentFlags, boolean execInFg,
            boolean whileRestarting, boolean permissionsReviewRequired, boolean packageFrozen,
            boolean enqueueOomAdj, @ServiceBindingOomAdjPolicy int serviceBindingOomAdjPolicy)
            throws TransactionTooLargeException {
        if (r.app != null && r.app.isThreadReady()) {
            r.updateOomAdjSeq();
            sendServiceArgsLocked(r, execInFg, false);
            return null;
        }

        if (!whileRestarting && mRestartingServices.contains(r)) {
            // If waiting for a restart, then do nothing.
            return null;
        }

        final long startTimeNs = SystemClock.elapsedRealtimeNanos();

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

        try {
            mAm.mPackageManagerInt.notifyComponentUsed(
                    r.packageName, r.userId, r.mRecentCallingPackage, r.toString());
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Failed trying to unstop package "
                    + r.packageName + ": " + e);
        }

        final boolean isolated = (r.serviceInfo.flags&ServiceInfo.FLAG_ISOLATED_PROCESS) != 0;
        final String procName = r.processName;
        HostingRecord hostingRecord = new HostingRecord(
                HostingRecord.HOSTING_TYPE_SERVICE, r.instanceName,
                r.definingPackageName, r.definingUid, r.serviceInfo.processName,
                getHostingRecordTriggerType(r));
        ProcessRecord app;

        if (!isolated) {
            app = mAm.getProcessRecordLocked(procName, r.appInfo.uid);
            if (DEBUG_MU) Slog.v(TAG_MU, "bringUpServiceLocked: appInfo.uid=" + r.appInfo.uid
                        + " app=" + app);
            if (app != null) {
                final IApplicationThread thread = app.getThread();
                final int pid = app.getPid();
                final UidRecord uidRecord = app.getUidRecord();
                if (app.isThreadReady()) {
                    try {
                        if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
                            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                                    "realStartServiceLocked: " + r.shortInstanceName);
                        }
                        app.addPackage(r.appInfo.packageName, r.appInfo.longVersionCode,
                                mAm.mProcessStats);
                        realStartServiceLocked(r, app, thread, pid, uidRecord, execInFg,
                                enqueueOomAdj, serviceBindingOomAdjPolicy);
                        return null;
                    } catch (TransactionTooLargeException e) {
                        throw e;
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Exception when starting service " + r.shortInstanceName, e);
                    } finally {
                        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                    }

                    // If a dead object exception was thrown -- fall through to
                    // restart the application.
                }
            }
        } else {
            if (r.inSharedIsolatedProcess) {
                app = mAm.mProcessList.getSharedIsolatedProcess(procName, r.appInfo.uid,
                        r.appInfo.packageName);
                if (app != null) {
                    final IApplicationThread thread = app.getThread();
                    final int pid = app.getPid();
                    final UidRecord uidRecord = app.getUidRecord();
                    r.isolationHostProc = app;
                    if (app.isThreadReady()) {
                        try {
                            if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
                                Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                                        "realStartServiceLocked: " + r.shortInstanceName);
                            }
                            realStartServiceLocked(r, app, thread, pid, uidRecord, execInFg,
                                    enqueueOomAdj, SERVICE_BIND_OOMADJ_POLICY_LEGACY);
                            return null;
                        } catch (TransactionTooLargeException e) {
                            throw e;
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Exception when starting service " + r.shortInstanceName,
                                    e);
                        } finally {
                            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
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
                app = r.isolationHostProc;
                if (WebViewZygote.isMultiprocessEnabled()
                        && r.serviceInfo.packageName.equals(WebViewZygote.getPackageName())) {
                    hostingRecord = HostingRecord.byWebviewZygote(r.instanceName,
                            r.definingPackageName,
                            r.definingUid, r.serviceInfo.processName);
                }
                if ((r.serviceInfo.flags & ServiceInfo.FLAG_USE_APP_ZYGOTE) != 0) {
                    hostingRecord = HostingRecord.byAppZygote(r.instanceName, r.definingPackageName,
                            r.definingUid, r.serviceInfo.processName);
                }
            }
        }

        // Not running -- get it started, and enqueue this service record
        // to be executed when the app comes up.
        if (app == null && !permissionsReviewRequired && !packageFrozen) {
            // TODO (chriswailes): Change the Zygote policy flags based on if the launch-for-service
            //  was initiated from a notification tap or not.
            if (r.isSdkSandbox) {
                final int uid = Process.toSdkSandboxUid(r.sdkSandboxClientAppUid);
                app = mAm.startSdkSandboxProcessLocked(procName, r.appInfo, true, intentFlags,
                        hostingRecord, ZYGOTE_POLICY_FLAG_EMPTY, uid, r.sdkSandboxClientAppPackage);
                r.isolationHostProc = app;
            } else {
                app = mAm.startProcessLocked(procName, r.appInfo, true, intentFlags,
                        hostingRecord, ZYGOTE_POLICY_FLAG_EMPTY, false, isolated);
            }
            if (app == null) {
                String msg = "Unable to launch app "
                        + r.appInfo.packageName + "/"
                        + r.appInfo.uid + " for service "
                        + r.intent.getIntent() + ": process is bad";
                Slog.w(TAG, msg);
                bringDownServiceLocked(r, enqueueOomAdj);
                return msg;
            }
            mAm.mProcessList.getAppStartInfoTracker().handleProcessServiceStart(startTimeNs, app,
                    r);
            if (isolated) {
                r.isolationHostProc = app;
            }
        }

        if (r.fgRequired) {
            if (DEBUG_FOREGROUND_SERVICE) {
                Slog.v(TAG, "Allowlisting " + UserHandle.formatUid(r.appInfo.uid)
                        + " for fg-service launch");
            }
            mAm.tempAllowlistUidLocked(r.appInfo.uid,
                    mAm.mConstants.mServiceStartForegroundTimeoutMs, REASON_SERVICE_LAUNCH,
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

    private String getHostingRecordTriggerType(ServiceRecord r) {
        if (Manifest.permission.BIND_JOB_SERVICE.equals(r.permission)
                && r.mRecentCallingUid == SYSTEM_UID) {
            return HostingRecord.TRIGGER_TYPE_JOB;
        }
        return HostingRecord.TRIGGER_TYPE_UNKNOWN;
    }

    private void requestServiceBindingsLocked(ServiceRecord r, boolean execInFg,
            @ServiceBindingOomAdjPolicy int serviceBindingOomAdjPolicy)
            throws TransactionTooLargeException {
        for (int i=r.bindings.size()-1; i>=0; i--) {
            IntentBindRecord ibr = r.bindings.valueAt(i);
            if (!requestServiceBindingLocked(r, ibr, execInFg, false, serviceBindingOomAdjPolicy)) {
                break;
            }
        }
    }

    @ServiceBindingOomAdjPolicy
    private int getServiceBindingOomAdjPolicyForAddLocked(ProcessRecord clientApp,
            ProcessRecord hostApp, ConnectionRecord cr) {
        @ServiceBindingOomAdjPolicy int policy = SERVICE_BIND_OOMADJ_POLICY_LEGACY;
        if (Flags.serviceBindingOomAdjPolicy() && clientApp != null && hostApp != null) {
            if (clientApp == hostApp) {
                policy = DEFAULT_SERVICE_NO_BUMP_BIND_POLICY_FLAG;
            } else if (clientApp.isCached()) {
                policy = DEFAULT_SERVICE_NO_BUMP_BIND_POLICY_FLAG;
                if (clientApp.isFreezable()) {
                    policy |= SERVICE_BIND_OOMADJ_POLICY_FREEZE_CALLER;
                }
            }
            if ((policy & SERVICE_BIND_OOMADJ_POLICY_SKIP_OOM_UPDATE_ON_CONNECT) == 0) {
                // Binding between two different processes.
                // Check if the caller has a better process state, oom adj score,
                // or if the caller has more capabilities.
                if (!mAm.mOomAdjuster.evaluateServiceConnectionAdd(clientApp, hostApp, cr)) {
                    // Running an oom adjuster won't be give the host app a better score, skip it.
                    policy = DEFAULT_SERVICE_NO_BUMP_BIND_POLICY_FLAG;
                }
            }
        }
        return policy;
    }

    @ServiceBindingOomAdjPolicy
    private int getServiceBindingOomAdjPolicyForRemovalLocked(ProcessRecord clientApp,
            ProcessRecord hostApp, ConnectionRecord cr) {
        @ServiceBindingOomAdjPolicy int policy = SERVICE_BIND_OOMADJ_POLICY_LEGACY;
        if (Flags.serviceBindingOomAdjPolicy() && clientApp != null && hostApp != null
                && cr != null) {
            if (clientApp == hostApp) {
                policy = DEFAULT_SERVICE_NO_BUMP_BIND_POLICY_FLAG;
            } else {
                if (!mAm.mOomAdjuster.evaluateServiceConnectionRemoval(clientApp, hostApp, cr)) {
                    // Running an oom adjuster won't be give the host app a better score, skip it.
                    policy = DEFAULT_SERVICE_NO_BUMP_BIND_POLICY_FLAG;
                }
            }
        }
        return policy;
    }

    /**
     * Note the name of this method should not be confused with the started services concept.
     * The "start" here means bring up the instance in the client, and this method is called
     * from bindService() as well.
     */
    private void realStartServiceLocked(ServiceRecord r, ProcessRecord app,
            IApplicationThread thread, int pid, UidRecord uidRecord, boolean execInFg,
            boolean enqueueOomAdj, @ServiceBindingOomAdjPolicy int serviceBindingOomAdjPolicy)
            throws RemoteException {
        if (thread == null) {
            throw new RemoteException();
        }
        if (DEBUG_MU)
            Slog.v(TAG_MU, "realStartServiceLocked, ServiceRecord.uid = " + r.appInfo.uid
                    + ", ProcessRecord.uid = " + app.uid);
        r.setProcess(app, thread, pid, uidRecord);
        r.restartTime = r.lastActivity = SystemClock.uptimeMillis();
        final boolean skipOomAdj = (serviceBindingOomAdjPolicy
                & SERVICE_BIND_OOMADJ_POLICY_SKIP_OOM_UPDATE_ON_CREATE) != 0;
        final ProcessServiceRecord psr = app.mServices;
        final boolean newService = psr.startService(r);
        bumpServiceExecutingLocked(r, execInFg, "create",
                OOM_ADJ_REASON_NONE /* use "none" to avoid extra oom adj */,
                skipOomAdj /* skipTimeoutIfPossible */);
        mAm.updateLruProcessLocked(app, false, null);
        updateServiceForegroundLocked(psr, /* oomAdj= */ false);
        // Skip the oom adj update if it's a self-binding, the Service#onCreate() will be running
        // at its current adj score.
        if (!skipOomAdj) {
            // Force an immediate oomAdjUpdate, so the host app could be in the correct
            // process state before doing any service related transactions
            mAm.enqueueOomAdjTargetLocked(app);
            r.updateOomAdjSeq();
            mAm.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_START_SERVICE);
        } else {
            // Since we skipped the oom adj update, the Service#onCreate() might be running in
            // the cached state, if the service process drops into the cached state after the call.
            // But there is still a grace period before freezing it, so we should be fine
            // in terms of not getting an ANR.
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
            thread.scheduleCreateService(r, r.serviceInfo,
                    null /* compatInfo (unused but need to keep method signature) */,
                    app.mState.getReportedProcState());
            r.postNotification(false);
            created = true;
        } catch (DeadObjectException e) {
            Slog.w(TAG, "Application dead when creating service " + r);
            mAm.appDiedLocked(app, "Died when creating service");
            throw e;
        } finally {
            if (!created) {
                // Keep the executeNesting count accurate.
                final boolean inDestroying = mDestroyingServices.contains(r);
                serviceDoneExecutingLocked(r, inDestroying, inDestroying, false,
                        !Flags.serviceBindingOomAdjPolicy() || r.wasOomAdjUpdated()
                        ? OOM_ADJ_REASON_STOP_SERVICE : OOM_ADJ_REASON_NONE);

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

        requestServiceBindingsLocked(r, execInFg, serviceBindingOomAdjPolicy);

        updateServiceClientActivitiesLocked(psr, null, true);

        if (newService && created) {
            psr.addBoundClientUidsOfNewService(r);
        }

        // If the service is in the started state, and there are no
        // pending arguments, then fake up one so its onStartCommand() will
        // be called.
        if (r.startRequested && r.callStart && r.pendingStarts.size() == 0) {
            r.pendingStarts.add(new ServiceRecord.StartItem(r, false, r.makeNextStartId(),
                    null, null, 0, null, null, ActivityManager.PROCESS_STATE_UNKNOWN));
        }

        sendServiceArgsLocked(r, execInFg, r.wasOomAdjUpdated());

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
            bumpServiceExecutingLocked(r, execInFg, "start",
                    OOM_ADJ_REASON_NONE /* use "none" to avoid extra oom adj */,
                    false /* skipTimeoutIfPossible */);
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

        if (!oomAdjusted) {
            mAm.enqueueOomAdjTargetLocked(r.app);
            mAm.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_START_SERVICE);
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
                serviceDoneExecutingLocked(r, inDestroying, inDestroying, true,
                        OOM_ADJ_REASON_STOP_SERVICE);
            }
            /* Will be a no-op if nothing pending */
            mAm.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_STOP_SERVICE);
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

    private void bringDownServiceIfNeededLocked(ServiceRecord r, boolean knowConn,
            boolean hasConn, boolean enqueueOomAdj, String debugReason) {
        if (DEBUG_SERVICE) {
            Slog.i(TAG, "Bring down service for " + debugReason + " :" + r.toString());
        }

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

        if (r.isShortFgs()) {
            // FGS can be stopped without the app calling stopService() or stopSelf(),
            // due to force-app-standby, or from Task Manager.
            Slog.w(TAG_SERVICE, "Short FGS brought down without stopping: " + r);
            maybeStopShortFgsTimeoutLocked(r);
        }

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
                final ComponentName clientSideComponentName =
                        cr.aliasComponent != null ? cr.aliasComponent : r.name;
                try {
                    cr.conn.connected(r.name, null, true);
                } catch (Exception e) {
                    Slog.w(TAG, "Failure disconnecting service " + r.shortInstanceName
                          + " to connection " + c.get(i).conn.asBinder()
                          + " (in " + c.get(i).binding.client.processName + ")", e);
                }
            }
        }

        boolean oomAdjusted = Flags.serviceBindingOomAdjPolicy() && r.wasOomAdjUpdated();

        // Tell the service that it has been unbound.
        if (r.app != null && r.app.isThreadReady()) {
            for (int i = r.bindings.size() - 1; i >= 0; i--) {
                IntentBindRecord ibr = r.bindings.valueAt(i);
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Bringing down binding " + ibr
                        + ": hasBound=" + ibr.hasBound);
                if (ibr.hasBound) {
                    try {
                        bumpServiceExecutingLocked(r, false, "bring down unbind",
                                oomAdjusted ? OOM_ADJ_REASON_NONE : OOM_ADJ_REASON_UNBIND_SERVICE,
                                oomAdjusted /* skipTimeoutIfPossible */);
                        oomAdjusted |= r.wasOomAdjUpdated();
                        ibr.hasBound = false;
                        ibr.requested = false;
                        r.app.getThread().scheduleUnbindService(r,
                                ibr.intent.getIntent());
                    } catch (Exception e) {
                        Slog.w(TAG, "Exception when unbinding service "
                                + r.shortInstanceName, e);
                        serviceProcessGoneLocked(r, enqueueOomAdj);
                        break;
                    }
                }
            }
        }

        // Check to see if the service had been started as foreground, but being
        // brought down before actually showing a notification.  That is not allowed.
        if (r.fgRequired) {
            Slog.w(TAG_SERVICE, "Bringing down service while still waiting for start foreground: "
                    + r);
            r.fgRequired = false;
            r.fgWaiting = false;
            synchronized (mAm.mProcessStats.mLock) {
                ServiceState stracker = r.getTracker();
                if (stracker != null) {
                    stracker.setForeground(false, mAm.mProcessStats.getMemFactorLocked(),
                            SystemClock.uptimeMillis());
                }
            }
            mAm.mAppOpsService.finishOperation(AppOpsManager.getToken(mAm.mAppOpsService),
                    AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName, null);
            mServiceFGAnrTimer.cancel(r);
            if (r.app != null) {
                Message msg = mAm.mHandler.obtainMessage(
                        ActivityManagerService.SERVICE_FOREGROUND_CRASH_MSG);
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = r.app;
                args.arg2 = r.toString();
                args.arg3 = r.getComponentName();

                msg.obj = args;
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
        final boolean exitingFg = r.isForeground;
        if (exitingFg) {
            maybeStopShortFgsTimeoutLocked(r);
            decActiveForegroundAppLocked(smap, r);
            synchronized (mAm.mProcessStats.mLock) {
                ServiceState stracker = r.getTracker();
                if (stracker != null) {
                    stracker.setForeground(false, mAm.mProcessStats.getMemFactorLocked(),
                            SystemClock.uptimeMillis());
                }
            }
            mAm.mAppOpsService.finishOperation(
                    AppOpsManager.getToken(mAm.mAppOpsService),
                    AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName, null);
            unregisterAppOpCallbackLocked(r);
            r.mFgsExitTime = SystemClock.uptimeMillis();
            logFGSStateChangeLocked(r,
                    FOREGROUND_SERVICE_STATE_CHANGED__STATE__EXIT,
                    r.mFgsExitTime > r.mFgsEnterTime
                            ? (int) (r.mFgsExitTime - r.mFgsEnterTime) : 0,
                    FGS_STOP_REASON_STOP_SERVICE,
                    FGS_TYPE_POLICY_CHECK_UNKNOWN,
                    FOREGROUND_SERVICE_STATE_CHANGED__FGS_START_API__FGSSTARTAPI_NA,
                    false /* fgsRestrictionRecalculated */
            );
            synchronized (mFGSLogger) {
                mFGSLogger.logForegroundServiceStop(r.appInfo.uid, r);
            }
            mAm.updateForegroundServiceUsageStats(r.name, r.userId, false);
        }

        r.isForeground = false;
        r.mFgsNotificationWasDeferred = false;
        dropFgsNotificationStateLocked(r);
        r.foregroundId = 0;
        r.foregroundNoti = null;
        resetFgsRestrictionLocked(r);
        // Signal FGS observers *after* changing the isForeground state, and
        // only if this was an actual state change.
        if (exitingFg) {
            signalForegroundServiceObserversLocked(r);
        }

        // Clear start entries.
        r.clearDeliveredStartsLocked();
        r.pendingStarts.clear();
        smap.mDelayedStartList.remove(r);

        if (r.app != null) {
            mAm.mBatteryStatsService.noteServiceStopLaunch(r.appInfo.uid, r.name.getPackageName(),
                    r.name.getClassName());
            stopServiceAndUpdateAllowlistManagerLocked(r);
            if (r.app.isThreadReady()) {
                // Bump the process to the top of LRU list
                mAm.updateLruProcessLocked(r.app, false, null);
                updateServiceForegroundLocked(r.app.mServices, false);
                if (r.mIsFgsDelegate) {
                    if (r.mFgsDelegation.mConnection != null) {
                        mAm.mHandler.post(() -> {
                            r.mFgsDelegation.mConnection.onServiceDisconnected(
                                    r.mFgsDelegation.mOptions.getComponentName());
                        });
                    }
                    for (int i = mFgsDelegations.size() - 1; i >= 0; i--) {
                        if (mFgsDelegations.valueAt(i) == r) {
                            mFgsDelegations.removeAt(i);
                            break;
                        }
                    }
                } else {
                    try {
                        bumpServiceExecutingLocked(r, false, "destroy",
                                oomAdjusted ? OOM_ADJ_REASON_NONE : OOM_ADJ_REASON_UNBIND_SERVICE,
                                oomAdjusted /* skipTimeoutIfPossible */);
                        mDestroyingServices.add(r);
                        oomAdjusted |= r.wasOomAdjUpdated();
                        r.destroying = true;
                        r.app.getThread().scheduleStopService(r);
                    } catch (Exception e) {
                        Slog.w(TAG, "Exception when destroying service "
                                + r.shortInstanceName, e);
                        serviceProcessGoneLocked(r, enqueueOomAdj);
                    }
                }
            } else {
                if (DEBUG_SERVICE) Slog.v(
                    TAG_SERVICE, "Removed service that has no process: " + r);
            }
        } else {
            if (DEBUG_SERVICE) Slog.v(
                TAG_SERVICE, "Removed service that is not running: " + r);
        }

        if (!oomAdjusted) {
            mAm.enqueueOomAdjTargetLocked(r.app);
            if (!enqueueOomAdj) {
                mAm.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_STOP_SERVICE);
            }
        }
        if (r.bindings.size() > 0) {
            r.bindings.clear();
        }

        if (r.restarter instanceof ServiceRestarter) {
           ((ServiceRestarter)r.restarter).setService(null);
        }

        synchronized (mAm.mProcessStats.mLock) {
            final int memFactor = mAm.mProcessStats.getMemFactorLocked();
            if (r.tracker != null) {
                final long now = SystemClock.uptimeMillis();
                r.tracker.setStarted(false, memFactor, now);
                r.tracker.setBound(false, memFactor, now);
                if (r.executeNesting == 0) {
                    r.tracker.clearCurrentOwner(r, false);
                    r.tracker = null;
                }
            }
        }

        smap.ensureNotStartingBackgroundLocked(r);
        updateNumForegroundServicesLocked();
    }

    private void dropFgsNotificationStateLocked(ServiceRecord r) {
        if (r.foregroundNoti == null) {
            return;
        }

        // If this is the only FGS using this notification, clear its FGS flag
        boolean shared = false;
        final ServiceMap smap = mServiceMap.get(r.userId);
        if (smap != null) {
            // Is any other FGS using this notification?
            final int numServices = smap.mServicesByInstanceName.size();
            for (int i = 0; i < numServices; i++) {
                final ServiceRecord sr = smap.mServicesByInstanceName.valueAt(i);
                if (sr == r) {
                    continue;
                }
                if (sr.isForeground
                        && r.foregroundId == sr.foregroundId
                        && r.appInfo.packageName.equals(sr.appInfo.packageName)) {
                    shared = true;
                    break;
                }
            }
        } else {
            Slog.wtf(TAG, "FGS " + r + " not found!");
        }

        // No other FGS is sharing this notification, so we're done with it
        if (!shared) {
            r.stripForegroundServiceFlagFromNotification();
        }
    }

    /**
     * @return The ServiceBindingOomAdjPolicy used in this removal.
     */
    @ServiceBindingOomAdjPolicy
    int removeConnectionLocked(ConnectionRecord c, ProcessRecord skipApp,
            ActivityServiceConnectionsHolder skipAct, boolean enqueueOomAdj) {
        IBinder binder = c.conn.asBinder();
        AppBindRecord b = c.binding;
        ServiceRecord s = b.service;
        @ServiceBindingOomAdjPolicy int serviceBindingOomAdjPolicy =
                SERVICE_BIND_OOMADJ_POLICY_LEGACY;
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
            if (c.hasFlag(Context.BIND_ABOVE_CLIENT)) {
                psr.updateHasAboveClientLocked();
            }
            // If this connection requested allowlist management, see if we should
            // now clear that state.
            if (c.hasFlag(BIND_ALLOW_WHITELIST_MANAGEMENT)) {
                s.updateAllowlistManager();
                if (!s.allowlistManager && s.app != null) {
                    updateAllowlistManagerLocked(s.app.mServices);
                }
            }
            // And do the same for bg activity starts ability.
            if (c.hasFlag(Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS)) {
                s.updateIsAllowedBgActivityStartsByBinding();
            }
            // And for almost perceptible exceptions.
            if (c.hasFlag(Context.BIND_ALMOST_PERCEPTIBLE)) {
                psr.updateHasTopStartedAlmostPerceptibleServices();
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
            if (s.app != null && s.app.isThreadReady() && b.intent.apps.size() == 0
                    && b.intent.hasBound) {
                serviceBindingOomAdjPolicy = getServiceBindingOomAdjPolicyForRemovalLocked(b.client,
                        s.app, c);
                final boolean skipOomAdj = (serviceBindingOomAdjPolicy
                        & SERVICE_BIND_OOMADJ_POLICY_SKIP_OOM_UPDATE_ON_CONNECT) != 0;
                try {
                    bumpServiceExecutingLocked(s, false, "unbind",
                            skipOomAdj ? OOM_ADJ_REASON_NONE : OOM_ADJ_REASON_UNBIND_SERVICE,
                            skipOomAdj /* skipTimeoutIfPossible */);
                    if (b.client != s.app && c.notHasFlag(Context.BIND_WAIVE_PRIORITY)
                            && s.app.mState.getSetProcState() <= PROCESS_STATE_HEAVY_WEIGHT) {
                        // If this service's process is not already in the cached list,
                        // then update it in the LRU list here because this may be causing
                        // it to go down there and we want it to start out near the top.
                        mAm.updateLruProcessLocked(s.app, false, null);
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

            if (c.hasFlag(Context.BIND_AUTO_CREATE)) {
                boolean hasAutoCreate = s.hasAutoCreateConnections();
                if (!hasAutoCreate) {
                    if (s.tracker != null) {
                        synchronized (mAm.mProcessStats.mLock) {
                            s.tracker.setBound(false, mAm.mProcessStats.getMemFactorLocked(),
                                    SystemClock.uptimeMillis());
                        }
                    }
                }
                bringDownServiceIfNeededLocked(s, true, hasAutoCreate, enqueueOomAdj,
                        "removeConnection");
            }
        }
        return serviceBindingOomAdjPolicy;
    }

    void serviceDoneExecutingLocked(ServiceRecord r, int type, int startId, int res,
            boolean enqueueOomAdj, Intent intent) {
        boolean inDestroying = mDestroyingServices.contains(r);
        if (r != null) {
            boolean skipOomAdj = false;
            boolean needOomAdj = false;
            if (type == ActivityThread.SERVICE_DONE_EXECUTING_START) {
                // This is a call from a service start...  take care of
                // book-keeping.
                r.callStart = true;

                // Set the result to startCommandResult.
                // START_TASK_REMOVED_COMPLETE is _not_ a result from onStartCommand(), so
                // let's ignore.
                if (res != Service.START_TASK_REMOVED_COMPLETE) {
                    r.startCommandResult = res;
                }
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
                // The service is done, force an oom adj update.
                needOomAdj = true;
            }
            final long origId = mAm.mInjector.clearCallingIdentity();
            serviceDoneExecutingLocked(r, inDestroying, inDestroying, enqueueOomAdj,
                    !Flags.serviceBindingOomAdjPolicy() || r.wasOomAdjUpdated() || needOomAdj
                    ? OOM_ADJ_REASON_EXECUTING_SERVICE : OOM_ADJ_REASON_NONE);
            mAm.mInjector.restoreCallingIdentity(origId);
        } else {
            Slog.w(TAG, "Done executing unknown service from pid "
                    + mAm.mInjector.getCallingPid());
        }
    }

    private void serviceProcessGoneLocked(ServiceRecord r, boolean enqueueOomAdj) {
        if (r.tracker != null) {
            synchronized (mAm.mProcessStats.mLock) {
                final int memFactor = mAm.mProcessStats.getMemFactorLocked();
                final long now = SystemClock.uptimeMillis();
                r.tracker.setExecuting(false, memFactor, now);
                r.tracker.setForeground(false, memFactor, now);
                r.tracker.setBound(false, memFactor, now);
                r.tracker.setStarted(false, memFactor, now);
            }
        }
        serviceDoneExecutingLocked(r, true, true, enqueueOomAdj, OOM_ADJ_REASON_PROCESS_END);
    }

    private void serviceDoneExecutingLocked(ServiceRecord r, boolean inDestroying,
            boolean finishing, boolean enqueueOomAdj, @OomAdjReason int oomAdjReason) {
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
                    if (r.app.mPid != 0) mActiveServiceAnrTimer.cancel(r.app);
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
                if (oomAdjReason != OOM_ADJ_REASON_NONE) {
                    if (enqueueOomAdj) {
                        mAm.enqueueOomAdjTargetLocked(r.app);
                    } else {
                        mAm.updateOomAdjLocked(r.app, oomAdjReason);
                    }
                } else {
                    // Skip oom adj if it wasn't bumped during the bumpServiceExecutingLocked()
                }
                r.updateOomAdjSeq();
            }
            r.executeFg = false;
            if (r.tracker != null) {
                synchronized (mAm.mProcessStats.mLock) {
                    final int memFactor = mAm.mProcessStats.getMemFactorLocked();
                    final long now = SystemClock.uptimeMillis();
                    r.tracker.setExecuting(false, memFactor, now);
                    r.tracker.setForeground(false, memFactor, now);
                    if (finishing) {
                        r.tracker.clearCurrentOwner(r, false);
                        r.tracker = null;
                    }
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

        // Update the app background restriction of the caller
        proc.mState.setBackgroundRestricted(appRestrictedAnyInBackground(
                proc.uid, proc.info.packageName));

        // Collect any services that are waiting for this process to come up.
        if (mPendingServices.size() > 0) {
            ServiceRecord sr = null;
            try {
                for (int i=0; i<mPendingServices.size(); i++) {
                    sr = mPendingServices.get(i);
                    if (proc != sr.isolationHostProc && (proc.uid != sr.appInfo.uid
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
                    try {
                        if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
                            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                                    "realStartServiceLocked: " + sr.shortInstanceName);
                        }
                        realStartServiceLocked(sr, proc, thread, pid, uidRecord, sr.createdFromFg,
                                true, SERVICE_BIND_OOMADJ_POLICY_LEGACY);
                    } finally {
                        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                    }
                    didSomething = true;
                    if (!isServiceNeededLocked(sr, false, false)) {
                        // We were waiting for this service to start, but it is actually no
                        // longer needed.  This could happen because bringDownServiceIfNeeded
                        // won't bring down a service that is pending...  so now the pending
                        // is done, so let's drop it.
                        bringDownServiceLocked(sr, true);
                    }
                    /* Will be a no-op if nothing pending */
                    mAm.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_START_SERVICE);
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
            boolean didImmediateRestart = false;
            for (int i=0; i<mRestartingServices.size(); i++) {
                sr = mRestartingServices.get(i);
                if (proc != sr.isolationHostProc && (proc.uid != sr.appInfo.uid
                        || !processName.equals(sr.processName))) {
                    continue;
                }
                mAm.mHandler.removeCallbacks(sr.restarter);
                mAm.mHandler.post(sr.restarter);
                didImmediateRestart = true;
            }
            if (didImmediateRestart) {
                // Since we kicked off all its pending restarts, there could be some open slots
                // in the pending restarts list, schedule a check on it. We are posting to the same
                // handler, so by the time of the check, those immediate restarts should be done.
                mAm.mHandler.post(() -> {
                    final long now = SystemClock.uptimeMillis();
                    synchronized (mAm) {
                        rescheduleServiceRestartIfPossibleLocked(
                                getExtraRestartTimeInBetweenLocked(),
                                mAm.mConstants.SERVICE_MIN_RESTART_TIME_BETWEEN,
                                "other", now);
                    }
                });
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
                    || sr.isolationHostProc == proc) {
                Slog.w(TAG, "Forcing bringing down service: " + sr);
                sr.isolationHostProc = null;
                mPendingServices.remove(i);
                size = mPendingServices.size();
                i--;
                needOomAdj = true;
                bringDownServiceLocked(sr, true);
            }
        }
        if (needOomAdj) {
            mAm.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_PROCESS_END);
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
                service.isolationHostProc = null;
                if (mTmpCollectionResults == null) {
                    mTmpCollectionResults = new ArrayList<>();
                }
                mTmpCollectionResults.add(service);
            }
        }
        return didSomething;
    }

    boolean bringDownDisabledPackageServicesLocked(String packageName, Set<String> filterByClasses,
            int userId, boolean evenPersistent, boolean fullStop, boolean doit) {
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
                mAm.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_COMPONENT_DISABLED);
            }
            if (fullStop && !mTmpCollectionResults.isEmpty()) {
                // if we're tearing down the app's entire service state, account for possible
                // races around FGS notifications by explicitly tidying up in a separate
                // pass post-shutdown
                final ArrayList<ServiceRecord> allServices =
                        (ArrayList<ServiceRecord>) mTmpCollectionResults.clone();
                mAm.mHandler.postDelayed(() -> {
                    for (int i = 0; i < allServices.size(); i++) {
                        allServices.get(i).cancelNotification();
                    }
                }, 250L);
            }
            mTmpCollectionResults.clear();
        }

        return didSomething;
    }

    @GuardedBy("mAm")
    private void signalForegroundServiceObserversLocked(ServiceRecord r) {
        final int num = mFgsObservers.beginBroadcast();
        for (int i = 0; i < num; i++) {
            try {
                mFgsObservers.getBroadcastItem(i).onForegroundStateChanged(r,
                        r.appInfo.packageName, r.userId, r.isForeground);
            } catch (RemoteException e) {
                // Will be unregistered automatically by RemoteCallbackList's dead-object
                // tracking, so nothing we need to do here.
            }
        }
        mFgsObservers.finishBroadcast();
    }

    @GuardedBy("mAm")
    boolean registerForegroundServiceObserverLocked(final int callingUid,
            IForegroundServiceObserver callback) {
        // We always tell the newly-registered observer about any current FGSes.  The
        // most common case for this is a SysUI crash & relaunch; it needs to
        // reconstruct its tracking of stoppable-FGS-hosting apps.
        try {
            final int mapSize = mServiceMap.size();
            for (int mapIndex = 0; mapIndex < mapSize; mapIndex++) {
                final ServiceMap smap = mServiceMap.valueAt(mapIndex);
                if (smap != null) {
                    final int numServices = smap.mServicesByInstanceName.size();
                    for (int i = 0; i < numServices; i++) {
                        final ServiceRecord sr = smap.mServicesByInstanceName.valueAt(i);
                        if (sr.isForeground && callingUid == sr.appInfo.uid) {
                            callback.onForegroundStateChanged(sr, sr.appInfo.packageName,
                                    sr.userId, true);
                        }
                    }
                }
            }
            // Callback is fine, go ahead and record it
            mFgsObservers.register(callback);
        } catch (RemoteException e) {
            // Whoops, something wrong with the callback.  Don't register it, and
            // report error back to the caller.
            Slog.e(TAG_SERVICE, "Bad FGS observer from uid " + callingUid);
            return false;
        }

        return true;
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
                            sr.getLastStartId(), baseIntent, null, 0, null, null,
                            ActivityManager.PROCESS_STATE_UNKNOWN));
                    if (sr.app != null && sr.app.isThreadReady()) {
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
            mAm.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_REMOVE_TASK);
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
        psr.removeAllSdkSandboxConnections();

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
            sr.isolationHostProc = null;
            sr.executeNesting = 0;
            synchronized (mAm.mProcessStats.mLock) {
                sr.forceClearTracker();
            }
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
                        if (conn.hasFlag(Context.BIND_AUTO_CREATE)
                                && conn.notHasFlag(Context.BIND_ALLOW_OOM_MANAGEMENT
                                |Context.BIND_WAIVE_PRIORITY)) {
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

            // Check: if the service listed for the app is not one
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
                        sr.userId, sr.crashCount, sr.shortInstanceName,
                        sr.app != null ? sr.app.getPid() : -1);
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
                        synchronized (mAm.mProcessStats.mLock) {
                            sr.tracker.setStarted(false, mAm.mProcessStats.getMemFactorLocked(),
                                    SystemClock.uptimeMillis());
                        }
                    }
                }
            }
        }

        mAm.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_STOP_SERVICE);

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
                synchronized (mAm.mProcessStats.mLock) {
                    sr.forceClearTracker();
                }
                mDestroyingServices.remove(i);
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "killServices remove destroying " + sr);
            }
        }

        psr.stopAllExecutingServices();
        psr.noteScheduleServiceTimeoutPending(false);
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

        final long ident = mAm.mInjector.clearCallingIdentity();
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
            mAm.mInjector.restoreCallingIdentity(ident);
        }

        return res;
    }

    public PendingIntent getRunningServiceControlPanelLocked(ComponentName name) {
        int userId = UserHandle.getUserId(mAm.mInjector.getCallingUid());
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
        try {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "serviceTimeout()");
            TimeoutRecord timeoutRecord = null;
            synchronized (mAm) {
                if (proc.isDebugging()) {
                    // The app's being debugged, ignore timeout.
                    mActiveServiceAnrTimer.discard(proc);
                    return;
                }
                final ProcessServiceRecord psr = proc.mServices;
                if (psr.numberOfExecutingServices() == 0 || proc.getThread() == null
                        || proc.isKilled()) {
                    mActiveServiceAnrTimer.discard(proc);
                    return;
                }
                final long now = SystemClock.uptimeMillis();
                final long maxTime =  now
                        - (psr.shouldExecServicesFg()
                        ? mAm.mConstants.SERVICE_TIMEOUT
                        : mAm.mConstants.SERVICE_BACKGROUND_TIMEOUT);
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
                    mActiveServiceAnrTimer.accept(proc);
                    Slog.w(TAG, "Timeout executing service: " + timeout);
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new FastPrintWriter(sw, false, 1024);
                    pw.println(timeout);
                    timeout.dump(pw, "    ");
                    pw.close();
                    mLastAnrDump = sw.toString();
                    mAm.mHandler.removeCallbacks(mLastAnrDumpClearer);
                    mAm.mHandler.postDelayed(mLastAnrDumpClearer,
                            LAST_ANR_LIFETIME_DURATION_MSECS);
                    long waitedMillis = now - timeout.executingStart;
                    timeoutRecord = TimeoutRecord.forServiceExec(timeout.shortInstanceName,
                            waitedMillis);
                } else {
                    mActiveServiceAnrTimer.discard(proc);
                    final long delay = psr.shouldExecServicesFg()
                                       ? (nextTime + mAm.mConstants.SERVICE_TIMEOUT) :
                                       (nextTime + mAm.mConstants.SERVICE_BACKGROUND_TIMEOUT)
                                       - SystemClock.uptimeMillis();
                    mActiveServiceAnrTimer.start(proc, delay);
                }
            }

            if (timeoutRecord != null) {
                mAm.mAnrHelper.appNotResponding(proc, timeoutRecord);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    void serviceForegroundTimeout(ServiceRecord r) {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "serviceForegroundTimeout()");
            ProcessRecord app;
            // Create a TimeoutRecord .
            final String annotation = "Context.startForegroundService() did not then call "
                    + "Service.startForeground(): " + r;
            TimeoutRecord timeoutRecord = TimeoutRecord.forServiceStartWithEndTime(annotation,
                    SystemClock.uptimeMillis());

            timeoutRecord.mLatencyTracker.waitingOnAMSLockStarted();
            synchronized (mAm) {
                timeoutRecord.mLatencyTracker.waitingOnAMSLockEnded();
                if (!r.fgRequired || !r.fgWaiting || r.destroying) {
                    mServiceFGAnrTimer.discard(r);
                    return;
                }

                app = r.app;
                if (app != null && app.isDebugging()) {
                    // The app's being debugged; let it ride
                    mServiceFGAnrTimer.discard(r);
                    return;
                }

                mServiceFGAnrTimer.accept(r);

                if (DEBUG_BACKGROUND_CHECK) {
                    Slog.i(TAG, "Service foreground-required timeout for " + r);
                }
                r.fgWaiting = false;
                stopServiceLocked(r, false);
            }

            if (app != null) {

                Message msg = mAm.mHandler.obtainMessage(
                        ActivityManagerService.SERVICE_FOREGROUND_TIMEOUT_ANR_MSG);
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = app;
                args.arg2 = timeoutRecord;
                msg.obj = args;
                mAm.mHandler.sendMessageDelayed(msg,
                        mAm.mConstants.mServiceStartForegroundAnrDelayMs);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    void serviceForegroundTimeoutANR(ProcessRecord app, TimeoutRecord timeoutRecord) {
        mAm.mAnrHelper.appNotResponding(app, timeoutRecord);
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

    void serviceForegroundCrash(ProcessRecord app, String serviceRecord,
            ComponentName service) {
        mAm.crashApplicationWithTypeWithExtras(
                app.uid, app.getPid(), app.info.packageName, app.userId,
                "Context.startForegroundService() did not then call " + "Service.startForeground(): "
                    + serviceRecord, false /*force*/,
                ForegroundServiceDidNotStartInTimeException.TYPE_ID,
                ForegroundServiceDidNotStartInTimeException.createExtrasForService(service));
    }

    private static class ProcessAnrTimer extends AnrTimer<ProcessRecord> {

        ProcessAnrTimer(ActivityManagerService am, int msg, String label) {
            super(Objects.requireNonNull(am).mHandler, msg, label);
        }

        @Override
        public int getPid(@NonNull ProcessRecord proc) {
            return proc.getPid();
        }

        @Override
        public int getUid(@NonNull ProcessRecord proc) {
            return proc.uid;
        }
    }

    private static class ServiceAnrTimer extends AnrTimer<ServiceRecord> {

        ServiceAnrTimer(ActivityManagerService am, int msg, String label) {
            super(Objects.requireNonNull(am).mHandler, msg, label);
        }

        @Override
        public int getPid(@NonNull ServiceRecord service) {
            return (service.app != null) ? service.app.getPid() : 0;
        }

        @Override
        public int getUid(@NonNull ServiceRecord service) {
            return (service.appInfo != null) ? service.appInfo.uid : 0;
        }
    }

    void scheduleServiceTimeoutLocked(ProcessRecord proc) {
        if (proc.mServices.numberOfExecutingServices() == 0 || proc.getThread() == null) {
            return;
        }
        final long delay = proc.mServices.shouldExecServicesFg()
                ? mAm.mConstants.SERVICE_TIMEOUT : mAm.mConstants.SERVICE_BACKGROUND_TIMEOUT;
        mActiveServiceAnrTimer.start(proc, delay);
        proc.mServices.noteScheduleServiceTimeoutPending(false);
    }

    void scheduleServiceForegroundTransitionTimeoutLocked(ServiceRecord r) {
        if (r.app.mServices.numberOfExecutingServices() == 0 || r.app.getThread() == null) {
            return;
        }
        r.fgWaiting = true;
        mServiceFGAnrTimer.start(r, mAm.mConstants.mServiceStartForegroundTimeoutMs);
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
        try {
            mAm.mOomAdjuster.mCachedAppOptimizer.enableFreezer(false);
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
        } finally {
            mAm.mOomAdjuster.mCachedAppOptimizer.enableFreezer(true);
        }
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

    private void setFgsRestrictionLocked(String callingPackage,
            int callingPid, int callingUid, Intent intent, ServiceRecord r, int userId,
            BackgroundStartPrivileges backgroundStartPrivileges, boolean isBindService) {
        setFgsRestrictionLocked(callingPackage, callingPid, callingUid, intent, r, userId,
                backgroundStartPrivileges, isBindService, /*forBoundFgs*/ false);
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
     * @param inBindService True if it's called from bindService().
     * @param forBoundFgs set to true if it's called from Service.startForeground() for a
     *                    service that's not started but bound.
     */
    private void setFgsRestrictionLocked(String callingPackage,
            int callingPid, int callingUid, Intent intent, ServiceRecord r, int userId,
            BackgroundStartPrivileges backgroundStartPrivileges, boolean inBindService,
            boolean forBoundFgs) {

        @ReasonCode int allowWiu;
        @ReasonCode int allowStart;

        // If called from bindService(), do not update the actual fields, but instead
        // keep it in a separate set of fields.
        if (inBindService) {
            allowWiu = r.mAllowWiu_inBindService;
            allowStart = r.mAllowStart_inBindService;
        } else {
            allowWiu = r.mAllowWiu_noBinding;
            allowStart = r.mAllowStart_noBinding;
        }

        if ((allowWiu == REASON_DENIED) || (allowStart == REASON_DENIED)) {
            @ReasonCode final int allowWhileInUse = shouldAllowFgsWhileInUsePermissionLocked(
                    callingPackage, callingPid, callingUid, r.app, backgroundStartPrivileges);
            // We store them to compare the old and new while-in-use logics to each other.
            // (They're not used for any other purposes.)
            if (allowWiu == REASON_DENIED) {
                allowWiu = allowWhileInUse;
            }
            if (allowStart == REASON_DENIED) {
                allowStart = shouldAllowFgsStartForegroundWithBindingCheckLocked(
                        allowWhileInUse, callingPackage, callingPid, callingUid, intent, r,
                        backgroundStartPrivileges, inBindService);
            }
        }

        if (inBindService) {
            r.mAllowWiu_inBindService = allowWiu;
            r.mAllowStart_inBindService = allowStart;
        } else {
            if (!forBoundFgs) {
                // This is for "normal" situation -- either:
                // - in Context.start[Foreground]Service()
                // - or, in Service.startForeground() on a started service.
                r.mAllowWiu_noBinding = allowWiu;
                r.mAllowStart_noBinding = allowStart;
            } else {
                // Service.startForeground() is called on a service that's not started, but bound.
                // In this case, we set them to "byBindings", not "noBinding", because
                // we don't want to use them when we calculate the "legacy" code.
                //
                // We don't want to set them to "no binding" codes, because on U-QPR1 and below,
                // we didn't call setFgsRestrictionLocked() in the code path which sets
                // forBoundFgs to true, and we wanted to preserve the original behavior in other
                // places to compare the legacy and new logic.
                if (r.mAllowWiu_byBindings == REASON_DENIED) {
                    r.mAllowWiu_byBindings = allowWiu;
                }
                if (r.mAllowStart_byBindings == REASON_DENIED) {
                    r.mAllowStart_byBindings = allowStart;
                }
            }
            // Also do a binding client check, unless called from bindService().
            if (r.mAllowWiu_byBindings == REASON_DENIED) {
                r.mAllowWiu_byBindings =
                        shouldAllowFgsWhileInUsePermissionByBindingsLocked(callingUid);
            }
            if (r.mAllowStart_byBindings == REASON_DENIED) {
                r.mAllowStart_byBindings = r.mAllowWiu_byBindings;
            }
        }
    }

    /**
     * Reset various while-in-use and BFSL related information.
     */
    void resetFgsRestrictionLocked(ServiceRecord r) {
        r.clearFgsAllowWiu();
        r.clearFgsAllowStart();

        r.mInfoAllowStartForeground = null;
        r.mInfoTempFgsAllowListReason = null;
        r.mLoggedInfoAllowStartForeground = false;
        r.updateAllowUiJobScheduling(r.isFgsAllowedWiu_forStart());
    }

    boolean canStartForegroundServiceLocked(int callingPid, int callingUid, String callingPackage) {
        if (!mAm.mConstants.mFlagBackgroundFgsStartRestrictionEnabled) {
            return true;
        }
        final @ReasonCode int allowWhileInUse = shouldAllowFgsWhileInUsePermissionLocked(
                callingPackage, callingPid, callingUid, null /* targetProcess */,
                BackgroundStartPrivileges.NONE);
        @ReasonCode int allowStartFgs = shouldAllowFgsStartForegroundNoBindingCheckLocked(
                allowWhileInUse, callingPid, callingUid, callingPackage, null /* targetService */,
                BackgroundStartPrivileges.NONE);

        if (allowStartFgs == REASON_DENIED) {
            if (canBindingClientStartFgsLocked(callingUid) != null) {
                allowStartFgs = REASON_FGS_BINDING;
            }
        }
        return allowStartFgs != REASON_DENIED;
    }

    /**
     * Should allow while-in-use permissions in FGS or not.
     * A typical BG started FGS is not allowed to have while-in-use permissions.
     *
     * @param callingPackage caller app's package name.
     * @param callingUid     caller app's uid.
     * @param targetProcess  the process of the service to start.
     * @return {@link ReasonCode}
     */
    @ReasonCode int shouldAllowFgsWhileInUsePermissionLocked(String callingPackage,
            int callingPid, int callingUid, @Nullable ProcessRecord targetProcess,
            BackgroundStartPrivileges backgroundStartPrivileges) {
        int ret = REASON_DENIED;

        final int uidState = mAm.getUidStateLocked(callingUid);
        if (ret == REASON_DENIED) {
            // Allow FGS while-in-use if the caller's process state is PROCESS_STATE_PERSISTENT,
            // PROCESS_STATE_PERSISTENT_UI or PROCESS_STATE_TOP.
            if (uidState <= PROCESS_STATE_TOP) {
                ret = getReasonCodeFromProcState(uidState);
            }
        }

        if (ret == REASON_DENIED) {
            // Allow FGS while-in-use if the caller has visible activity.
            // Here we directly check ActivityTaskManagerService, instead of checking
            // PendingStartActivityUids in ActivityManagerService, which gives the same result.
            final boolean isCallingUidVisible = mAm.mAtmInternal.isUidForeground(callingUid);
            if (isCallingUidVisible) {
                ret = REASON_UID_VISIBLE;
            }
        }

        if (ret == REASON_DENIED) {
            // Allow FGS while-in-use if the background activity start flag is on. Because
            // activity start can lead to FGS start in TOP state and obtain while-in-use.
            if (backgroundStartPrivileges.allowsBackgroundActivityStarts()) {
                ret = REASON_START_ACTIVITY_FLAG;
            }
        }

        if (ret == REASON_DENIED) {
            boolean isCallerSystem = false;
            final int callingAppId = UserHandle.getAppId(callingUid);
            // Allow FGS while-in-use for a list of special UIDs.
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
            // Allow FGS while-in-use if the WindowManager allows background activity start.
            // This is mainly to get the 10 seconds grace period if any activity in the caller has
            // either started or finished very recently. The binding flag
            // BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS is also allowed by the check here.
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
            // Allow FGS while-in-use if the caller UID is in ActivityManagerService's
            // mFgsWhileInUseTempAllowList. This is a temp allowlist to allow FGS while-in-use. It
            // is used when MediaSessionService's bluetooth button or play/resume/stop commands are
            // issued. The typical temp allowlist duration is 10 seconds.
            // This temp allowlist mechanism can also be called by other system_server internal
            // components such as Telephone/VOIP if they want to start a FGS and get while-in-use.
            if (mAm.mInternal.isTempAllowlistedForFgsWhileInUse(callingUid)) {
                return REASON_TEMP_ALLOWED_WHILE_IN_USE;
            }
        }

        if (ret == REASON_DENIED) {
            if (targetProcess != null) {
                // Allow FGS while-in-use if the caller of the instrumentation has
                // START_ACTIVITIES_FROM_BACKGROUND permission.
                ActiveInstrumentation instr = targetProcess.getActiveInstrumentation();
                if (instr != null && instr.mHasBackgroundActivityStartsPermission) {
                    ret = REASON_INSTR_BACKGROUND_ACTIVITY_PERMISSION;
                }
            }
        }

        if (ret == REASON_DENIED) {
            // Allow FGS while-in-use if the caller has START_ACTIVITIES_FROM_BACKGROUND
            // permission, because starting an activity can lead to starting FGS from the TOP state
            // and obtain while-in-use.
            if (mAm.checkPermission(START_ACTIVITIES_FROM_BACKGROUND, callingPid, callingUid)
                    == PERMISSION_GRANTED) {
                ret = REASON_BACKGROUND_ACTIVITY_PERMISSION;
            }
        }

        if (ret == REASON_DENIED) {
            // Allow FGS while-in-use if the caller is in the while-in-use allowlist. Right now
            // AttentionService and SystemCaptionsService packageName are in this allowlist.
            if (verifyPackage(callingPackage, callingUid)) {
                final boolean isAllowedPackage =
                        mAllowListWhileInUsePermissionInFgs.contains(callingPackage);
                if (isAllowedPackage) {
                    ret = REASON_ALLOWLISTED_PACKAGE;
                }
            } else {
                EventLog.writeEvent(0x534e4554, "215003903", callingUid,
                        "callingPackage:" + callingPackage + " does not belong to callingUid:"
                                + callingUid);
            }
        }

        if (ret == REASON_DENIED) {
            // Allow FGS while-in-use if the caller is the device owner.
            final boolean isDeviceOwner = mAm.mInternal.isDeviceOwner(callingUid);
            if (isDeviceOwner) {
                ret = REASON_DEVICE_OWNER;
            }
        }
        return ret;
    }

    /**
     * Check all bindings into the calling UID, and see if:
     * - It's bound by a TOP app
     * - or, bound by a persistent process with BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS.
     */
    private @ReasonCode int shouldAllowFgsWhileInUsePermissionByBindingsLocked(int callingUid) {
        final ArraySet<Integer> checkedClientUids = new ArraySet<>();
        final Integer result = mAm.mProcessList.searchEachLruProcessesLOSP(
                false, pr -> {
                    if (pr.uid != callingUid) {
                        return null;
                    }
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
                                final int clientUid = clientPr.uid;

                                // An UID can bind to itself, do not check on itself again.
                                // Also skip already checked clientUid.
                                if (clientUid == callingUid
                                        || checkedClientUids.contains(clientUid)) {
                                    continue;
                                }

                                // Binding found, check the client procstate and the flag.
                                final int clientUidState = mAm.getUidStateLocked(callingUid);
                                final boolean boundByTop = clientUidState == PROCESS_STATE_TOP;
                                final boolean boundByPersistentWithBal =
                                        clientUidState < PROCESS_STATE_TOP
                                        && cr.hasFlag(
                                                Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS);
                                if (boundByTop || boundByPersistentWithBal) {
                                    return getReasonCodeFromProcState(clientUidState);
                                }

                                // Don't check the same UID.
                                checkedClientUids.add(clientUid);
                            }
                        }
                    }
                    return null;
                });
        return result == null ? REASON_DENIED : result;
    }

    /**
     * The uid is not allowed to start FGS, but the uid has a service that is bound
     * by a clientUid, if the clientUid can start FGS, then the clientUid can propagate its
     * BG-FGS-start capability down to the callingUid.
     * @param uid
     * @return The first binding client's packageName that can start FGS. Return null if no client
     *         can start FGS.
     */
    private String canBindingClientStartFgsLocked(int uid) {
        String bindFromPackage = null;
        final ArraySet<Integer> checkedClientUids = new ArraySet<>();
        final Pair<Integer, String> isAllowed = mAm.mProcessList.searchEachLruProcessesLOSP(
                false, pr -> {
                if (pr.uid == uid) {
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
                                // If a binding is from a persistent process, we don't automatically
                                // always allow the bindee to allow FGS BG starts. In this case,
                                // the binder will have to explicitly make sure the bindee's
                                // procstate will be BFGS or above. Otherwise, for example, even if
                                // the system server binds to an app with BIND_NOT_FOREGROUND,
                                // the binder would have to be able to start FGS, which is not what
                                // we want. (e.g. job services shouldn't be allowed BG-FGS.)
                                if (clientPr.isPersistent()) {
                                    continue;
                                }
                                final int clientPid = clientPr.mPid;
                                final int clientUid = clientPr.uid;
                                // An UID can bind to itself, do not check on itself again.
                                // Also skip already checked clientUid.
                                if (clientUid == uid
                                        || checkedClientUids.contains(clientUid)) {
                                    continue;
                                }
                                final String clientPackageName = cr.clientPackageName;
                                final @ReasonCode int allowWhileInUse2 =
                                        shouldAllowFgsWhileInUsePermissionLocked(
                                                clientPackageName,
                                                clientPid, clientUid, null /* targetProcess */,
                                                BackgroundStartPrivileges.NONE);
                                final @ReasonCode int allowStartFgs =
                                        shouldAllowFgsStartForegroundNoBindingCheckLocked(
                                                allowWhileInUse2,
                                                clientPid, clientUid, clientPackageName,
                                                null /* targetService */,
                                                BackgroundStartPrivileges.NONE);
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
            bindFromPackage = isAllowed.second;
        }
        return bindFromPackage;
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
    private @ReasonCode int shouldAllowFgsStartForegroundWithBindingCheckLocked(
            @ReasonCode int allowWhileInUse, String callingPackage, int callingPid,
            int callingUid, Intent intent, ServiceRecord r,
            BackgroundStartPrivileges backgroundStartPrivileges, boolean isBindService) {
        ActivityManagerService.FgsTempAllowListItem tempAllowListReason =
                r.mInfoTempFgsAllowListReason = mAm.isAllowlistedForFgsStartLOSP(callingUid);
        int ret = shouldAllowFgsStartForegroundNoBindingCheckLocked(allowWhileInUse, callingPid,
                callingUid, callingPackage, r, backgroundStartPrivileges);

        // If an app (App 1) is bound by another app (App 2) that could start an FGS, then App 1
        // is also allowed to start an FGS. We check all the binding
        // in canBindingClientStartFgsLocked() to do this check.
        // (Note we won't check more than 1 level of binding.)
        // [bookmark: 61867f60-007c-408c-a2c4-e19e96056135] -- this code is referred to from
        // OomAdjuster.
        String bindFromPackage = null;
        if (ret == REASON_DENIED) {
            bindFromPackage = canBindingClientStartFgsLocked(callingUid);
            if (bindFromPackage != null) {
                ret = REASON_FGS_BINDING;
            }
        }

        final int uidState = mAm.getUidStateLocked(callingUid);
        int callerTargetSdkVersion = -1;
        try {
            callerTargetSdkVersion = mAm.mContext.getPackageManager()
                    .getTargetSdkVersion(callingPackage);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        final boolean uidBfsl = (mAm.getUidProcessCapabilityLocked(callingUid)
                & PROCESS_CAPABILITY_BFSL) != 0;
        final String debugInfo =
                "[callingPackage: " + callingPackage
                        + "; callingUid: " + callingUid
                        + "; uidState: " + ProcessList.makeProcStateString(uidState)
                        + "; uidBFSL: " + (uidBfsl ? "[BFSL]" : "n/a")
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
                        + ": isBindService:" + isBindService
                        + "]";
        if (!debugInfo.equals(r.mInfoAllowStartForeground)) {
            r.mLoggedInfoAllowStartForeground = false;
            r.mInfoAllowStartForeground = debugInfo;
        }
        return ret;
    }

    private @ReasonCode int shouldAllowFgsStartForegroundNoBindingCheckLocked(
            @ReasonCode int allowWhileInUse, int callingPid, int callingUid, String callingPackage,
            @Nullable ServiceRecord targetService,
            BackgroundStartPrivileges backgroundStartPrivileges) {
        int ret = allowWhileInUse;

        if (ret == REASON_DENIED) {
            final int uidState = mAm.getUidStateLocked(callingUid);
            // Is the calling UID at PROCESS_STATE_TOP or above?
            if (uidState <= PROCESS_STATE_TOP) {
                ret = getReasonCodeFromProcState(uidState);
            }
        }

        if (ret == REASON_DENIED) {
            final boolean uidBfsl =
                    (mAm.getUidProcessCapabilityLocked(callingUid) & PROCESS_CAPABILITY_BFSL) != 0;
            final Integer allowedType = mAm.mProcessList.searchEachLruProcessesLOSP(false, app -> {
                if (app.uid == callingUid) {
                    final ProcessStateRecord state = app.mState;
                    final int procstate = state.getCurProcState();
                    if ((procstate <= PROCESS_STATE_BOUND_TOP)
                            || (uidBfsl && (procstate <= PROCESS_STATE_BOUND_FOREGROUND_SERVICE))) {
                        return getReasonCodeFromProcState(procstate);
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
            if (backgroundStartPrivileges.allowsBackgroundFgsStarts()) {
                ret = REASON_START_ACTIVITY_FLAG;
            }
        }

        if (ret == REASON_DENIED) {
            if (mAm.mAtmInternal.hasSystemAlertWindowPermission(
                                    callingUid, callingPid, callingPackage)) {
                // Starting from Android V, it is not enough to only have the SYSTEM_ALERT_WINDOW
                // permission granted - apps must also be showing an overlay window.
                if (Flags.fgsDisableSaw()
                        && CompatChanges.isChangeEnabled(FGS_SAW_RESTRICTIONS, callingUid)) {
                    final UidRecord uidRecord = mAm.mProcessList.getUidRecordLOSP(callingUid);
                    if (uidRecord != null) {
                        for (int i = uidRecord.getNumOfProcs() - 1; i >= 0; i--) {
                            final ProcessRecord pr = uidRecord.getProcessRecordByIndex(i);
                            if (pr != null && pr.mState.hasOverlayUi()) {
                                ret = REASON_SYSTEM_ALERT_WINDOW_PERMISSION;
                                break;
                            }
                        }
                    }
                } else { // pre-V logic
                    ret = REASON_SYSTEM_ALERT_WINDOW_PERMISSION;
                }
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
            if (mAm.mConstants.mFlagSystemExemptPowerRestrictionsEnabled
                    && appOpsManager.checkOpNoThrow(
                    AppOpsManager.OP_SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS, callingUid,
                    callingPackage) == AppOpsManager.MODE_ALLOWED) {
                ret = REASON_SYSTEM_EXEMPT_APP_OP;
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
            final String inputMethod =
                    Settings.Secure.getStringForUser(mAm.mContext.getContentResolver(),
                            Settings.Secure.DEFAULT_INPUT_METHOD,
                            UserHandle.getUserId(callingUid));
            if (inputMethod != null) {
                final ComponentName cn = ComponentName.unflattenFromString(inputMethod);
                if (cn != null && cn.getPackageName().equals(callingPackage)) {
                    ret = REASON_CURRENT_INPUT_METHOD;
                }
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

    private void showFgsBgRestrictedNotificationLocked(ServiceRecord r) {
        if (!mAm.mConstants.mFgsStartRestrictionNotificationEnabled /* default is false */) {
            return;
        }
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

    private boolean isBgFgsRestrictionEnabled(ServiceRecord r, int actualCallingUid) {
        // mFlagFgsStartRestrictionEnabled controls whether to enable the BG FGS restrictions:
        // - If true (default), BG-FGS restrictions are enabled if the service targets >= S.
        // - If false, BG-FGS restrictions are disabled for all apps.
        if (!mAm.mConstants.mFlagFgsStartRestrictionEnabled) {
            return false;
        }

        // If the service target below S, then don't enable the restrictions.
        if (!CompatChanges.isChangeEnabled(FGS_BG_START_RESTRICTION_CHANGE_ID, r.appInfo.uid)) {
            return false;
        }

        // mFgsStartRestrictionCheckCallerTargetSdk controls whether we take the caller's target
        // SDK level into account or not:
        // - If true (default), BG-FGS restrictions only happens if the caller _also_ targets >= S.
        // - If false, BG-FGS restrictions do _not_ use the caller SDK levels.
        if (!mAm.mConstants.mFgsStartRestrictionCheckCallerTargetSdk) {
            return true; // In this case, we only check the service's target SDK level.
        }
        final int callingUid;
        if (Flags.newFgsRestrictionLogic()) {
            // We always consider SYSTEM_UID to target S+, so just enable the restrictions.
            if (actualCallingUid == Process.SYSTEM_UID) {
                return true;
            }
            callingUid = actualCallingUid;
        } else {
            // Legacy logic used mRecentCallingUid.
            callingUid = r.mRecentCallingUid;
        }
        if (!CompatChanges.isChangeEnabled(FGS_BG_START_RESTRICTION_CHANGE_ID, callingUid)) {
            return false; // If the caller targets < S, then we still disable the restrictions.
        }

        // Both the service and the caller target S+, so enable the check.
        return true;
    }

    private void logFgsBackgroundStart(ServiceRecord r) {
        /*
        // Only log if FGS is started from background.
        if (!isFgsBgStart(r.mAllowStartForeground)) {
            return;
        }
        */
        if (!r.mLoggedInfoAllowStartForeground) {
            final String msg = "Background started FGS: "
                    + (r.isFgsAllowedStart() ? "Allowed " : "Disallowed ")
                    + r.mInfoAllowStartForeground
                    + (r.isShortFgs() ? " (Called on SHORT_SERVICE)" : "");
            if (r.isFgsAllowedStart()) {
                if (ActivityManagerUtils.shouldSamplePackageForAtom(r.packageName,
                        mAm.mConstants.mFgsStartAllowedLogSampleRate)) {
                    Slog.wtfQuiet(TAG, msg);
                }
                Slog.i(TAG, msg);
            } else {
                //if (ActivityManagerUtils.shouldSamplePackageForAtom(r.packageName,
                //        mAm.mConstants.mFgsStartDeniedLogSampleRate)) {
                    Slog.wtfQuiet(TAG, msg);
                //}
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
     * @param fgsStopReason why was this FGS stopped.
     * @param fgsTypeCheckCode The FGS type policy check result.
     */
    private void logFGSStateChangeLocked(ServiceRecord r, int state, int durationMs,
            @FgsStopReason int fgsStopReason,
            @ForegroundServicePolicyCheckCode int fgsTypeCheckCode,
            int fgsStartApi, // from ForegroundServiceStateChanged.FgsStartApi
            boolean fgsRestrictionRecalculated
    ) {
        if (!ActivityManagerUtils.shouldSamplePackageForAtom(
                r.packageName, mAm.mConstants.mFgsAtomSampleRate)) {
            return;
        }
        boolean allowWhileInUsePermissionInFgs;
        @PowerExemptionManager.ReasonCode int fgsStartReasonCode;
        if (state == FOREGROUND_SERVICE_STATE_CHANGED__STATE__ENTER
                || state == FOREGROUND_SERVICE_STATE_CHANGED__STATE__EXIT
                || state == FOREGROUND_SERVICE_STATE_CHANGED__STATE__TIMED_OUT) {
            allowWhileInUsePermissionInFgs = r.mAllowWhileInUsePermissionInFgsAtEntering;
            fgsStartReasonCode = r.mAllowStartForegroundAtEntering;
        } else {
            // TODO: Also log "forStart"
            allowWhileInUsePermissionInFgs = r.isFgsAllowedWiu_forCapabilities();
            fgsStartReasonCode = r.getFgsAllowStart();
        }
        final int callerTargetSdkVersion = r.mRecentCallerApplicationInfo != null
                ? r.mRecentCallerApplicationInfo.targetSdkVersion : 0;

        // TODO(short-service): Log the UID capabilities (for BFSL) too, and also the procstate?
        FrameworkStatsLog.write(FrameworkStatsLog.FOREGROUND_SERVICE_STATE_CHANGED,
                r.appInfo.uid,
                r.shortInstanceName,
                state,
                allowWhileInUsePermissionInFgs,
                fgsStartReasonCode,
                r.appInfo.targetSdkVersion,
                r.mRecentCallingUid,
                callerTargetSdkVersion,
                r.mInfoTempFgsAllowListReason != null
                        ? r.mInfoTempFgsAllowListReason.mCallingUid : INVALID_UID,
                r.mFgsNotificationWasDeferred,
                r.mFgsNotificationShown,
                durationMs,
                r.mStartForegroundCount,
                0, // Short instance name -- no longer logging it.
                r.mFgsHasNotificationPermission,
                r.foregroundServiceType,
                fgsTypeCheckCode,
                r.mIsFgsDelegate,
                r.mFgsDelegation != null ? r.mFgsDelegation.mOptions.mClientUid : INVALID_UID,
                r.mFgsDelegation != null ? r.mFgsDelegation.mOptions.mDelegationService
                        : ForegroundServiceDelegationOptions.DELEGATION_SERVICE_DEFAULT,
                0 /* api_sate */,
                null /* api_type */,
                null /* api_timestamp */,
                mAm.getUidStateLocked(r.appInfo.uid),
                mAm.getUidProcessCapabilityLocked(r.appInfo.uid),
                mAm.getUidStateLocked(r.mRecentCallingUid),
                mAm.getUidProcessCapabilityLocked(r.mRecentCallingUid),
                0,
                0,
                r.mAllowWiu_noBinding,
                r.mAllowWiu_inBindService,
                r.mAllowWiu_byBindings,
                r.mAllowStart_noBinding,
                r.mAllowStart_inBindService,
                r.mAllowStart_byBindings,
                fgsStartApi,
                fgsRestrictionRecalculated);

        int event = 0;
        if (state == FOREGROUND_SERVICE_STATE_CHANGED__STATE__ENTER) {
            event = EventLogTags.AM_FOREGROUND_SERVICE_START;
        } else if (state == FOREGROUND_SERVICE_STATE_CHANGED__STATE__EXIT) {
            event = EventLogTags.AM_FOREGROUND_SERVICE_STOP;
        } else if (state == FOREGROUND_SERVICE_STATE_CHANGED__STATE__DENIED) {
            event = EventLogTags.AM_FOREGROUND_SERVICE_DENIED;
        } else if (state == FOREGROUND_SERVICE_STATE_CHANGED__STATE__TIMED_OUT) {
            event = EventLogTags.AM_FOREGROUND_SERVICE_TIMED_OUT;
        } else {
            // Unknown event.
            return;
        }
        EventLog.writeEvent(event,
                r.userId,
                r.shortInstanceName,
                allowWhileInUsePermissionInFgs ? 1 : 0,
                reasonCodeToString(fgsStartReasonCode),
                r.appInfo.targetSdkVersion,
                callerTargetSdkVersion,
                r.mFgsNotificationWasDeferred ? 1 : 0,
                r.mFgsNotificationShown ? 1 : 0,
                durationMs,
                r.mStartForegroundCount,
                fgsStopReasonToString(fgsStopReason),
                r.foregroundServiceType);
    }

    private void updateNumForegroundServicesLocked() {
        sNumForegroundServices.set(mAm.mProcessList.getNumForegroundServices());
    }

    boolean canAllowWhileInUsePermissionInFgsLocked(int callingPid, int callingUid,
            String callingPackage) {
        return shouldAllowFgsWhileInUsePermissionLocked(callingPackage, callingPid, callingUid,
                /* targetProcess */ null,
                BackgroundStartPrivileges.NONE)
                != REASON_DENIED;
    }

    boolean canAllowWhileInUsePermissionInFgsLocked(int callingPid, int callingUid,
            String callingPackage, @Nullable ProcessRecord targetProcess,
            @NonNull BackgroundStartPrivileges backgroundStartPrivileges) {
        return shouldAllowFgsWhileInUsePermissionLocked(callingPackage, callingPid, callingUid,
                targetProcess, backgroundStartPrivileges) != REASON_DENIED;
    }

    /**
     * Checks if a given packageName belongs to a given uid.
     * @param packageName the package of the caller
     * @param uid the uid of the caller
     * @return true or false
     */
    private boolean verifyPackage(String packageName, int uid) {
        if (uid == ROOT_UID || uid == SYSTEM_UID) {
            //System and Root are always allowed
            return true;
        }
        return mAm.getPackageManagerInternal().isSameApp(packageName, uid,
                UserHandle.getUserId(uid));
    }

    private static String fgsStopReasonToString(@FgsStopReason int stopReason) {
        switch (stopReason) {
            case FGS_STOP_REASON_STOP_SERVICE:
                return "STOP_SERVICE";
            case FGS_STOP_REASON_STOP_FOREGROUND:
                return "STOP_FOREGROUND";
            default:
                return "UNKNOWN";
        }
    }

    /**
     * Start a foreground service delegate. The delegate is not an actual service component, it is
     * merely a delegate that promotes the client process into foreground service process state.
     *
     * @param options an ForegroundServiceDelegationOptions object.
     * @param connection callback if the delegate is started successfully.
     * @return true if delegate is started, false otherwise.
     * @throw SecurityException if PackageManaager can not resolve
     *        {@link ForegroundServiceDelegationOptions#mClientPackageName} or the resolved
     *        package's UID is not same as {@link ForegroundServiceDelegationOptions#mClientUid}
     */
    boolean startForegroundServiceDelegateLocked(
            @NonNull ForegroundServiceDelegationOptions options,
            @Nullable ServiceConnection connection) {
        Slog.v(TAG, "startForegroundServiceDelegateLocked " + options.getDescription());
        final ComponentName cn = options.getComponentName();
        for (int i = mFgsDelegations.size() - 1; i >= 0; i--) {
            ForegroundServiceDelegation delegation = mFgsDelegations.keyAt(i);
            if (delegation.mOptions.isSameDelegate(options)) {
                Slog.e(TAG, "startForegroundServiceDelegate " + options.getDescription()
                        + " already exists, multiple connections are not allowed");
                return false;
            }
        }
        final int callingPid = options.mClientPid;
        final int callingUid = options.mClientUid;
        final int userId = UserHandle.getUserId(callingUid);
        final String callingPackage = options.mClientPackageName;

        if (!canStartForegroundServiceLocked(callingPid, callingUid, callingPackage)) {
            Slog.d(TAG, "startForegroundServiceDelegateLocked aborted,"
                    + " app is in the background");
            return false;
        }

        IApplicationThread caller = options.mClientAppThread;
        ProcessRecord callerApp;
        if (caller != null) {
            callerApp = mAm.getRecordForAppLOSP(caller);
        } else {
            synchronized (mAm.mPidsSelfLocked) {
                callerApp = mAm.mPidsSelfLocked.get(callingPid);
                caller = callerApp.getThread();
            }
        }
        if (callerApp == null) {
            throw new SecurityException(
                    "Unable to find app for caller " + caller
                            + " (pid=" + callingPid
                            + ") when startForegroundServiceDelegateLocked " + cn);
        }

        Intent intent = new Intent();
        intent.setComponent(cn);
        ServiceLookupResult res = retrieveServiceLocked(intent, null /*instanceName */,
                false /* isSdkSandboxService */, INVALID_UID /* sdkSandboxClientAppUid */,
                null /* sdkSandboxClientAppPackage */, null /* resolvedType */, callingPackage,
                callingPid, callingUid, userId, true /* createIfNeeded */,
                false /* callingFromFg */, false /* isBindExternal */, false /* allowInstant */ ,
                options, false /* inSharedIsolatedProcess */,
                false /*inPrivateSharedIsolatedProcess*/);
        if (res == null || res.record == null) {
            Slog.d(TAG,
                    "startForegroundServiceDelegateLocked retrieveServiceLocked returns null");
            return false;
        }

        final ServiceRecord r = res.record;
        r.setProcess(callerApp, caller, callingPid, null);
        r.mIsFgsDelegate = true;
        final ForegroundServiceDelegation delegation =
                new ForegroundServiceDelegation(options, connection);
        r.mFgsDelegation = delegation;
        mFgsDelegations.put(delegation, r);
        r.isForeground = true;
        r.mFgsEnterTime = SystemClock.uptimeMillis();
        r.foregroundServiceType = options.mForegroundServiceTypes;
        r.updateOomAdjSeq();
        setFgsRestrictionLocked(callingPackage, callingPid, callingUid, intent, r, userId,
                BackgroundStartPrivileges.NONE,  false /* isBindService */);
        final ProcessServiceRecord psr = callerApp.mServices;
        final boolean newService = psr.startService(r);
        // updateOomAdj.
        updateServiceForegroundLocked(psr, /* oomAdj= */ true);

        synchronized (mAm.mProcessStats.mLock) {
            final ServiceState stracker = r.getTracker();
            if (stracker != null) {
                stracker.setForeground(true,
                        mAm.mProcessStats.getMemFactorLocked(),
                        SystemClock.uptimeMillis());
            }
        }

        mAm.mBatteryStatsService.noteServiceStartRunning(callingUid, callingPackage,
                cn.getClassName());
        mAm.mAppOpsService.startOperation(AppOpsManager.getToken(mAm.mAppOpsService),
                AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName, null,
                true, false, null, false,
                AppOpsManager.ATTRIBUTION_FLAGS_NONE, AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE);
        registerAppOpCallbackLocked(r);
        synchronized (mFGSLogger) {
            mFGSLogger.logForegroundServiceStart(r.appInfo.uid, 0, r);
        }
        logFGSStateChangeLocked(r,
                FrameworkStatsLog.FOREGROUND_SERVICE_STATE_CHANGED__STATE__ENTER,
                0, FGS_STOP_REASON_UNKNOWN, FGS_TYPE_POLICY_CHECK_UNKNOWN,
                FOREGROUND_SERVICE_STATE_CHANGED__FGS_START_API__FGSSTARTAPI_DELEGATE,
                false /* fgsRestrictionRecalculated */
        );
        // Notify the caller.
        if (connection != null) {
            mAm.mHandler.post(() -> {
                connection.onServiceConnected(cn, delegation.mBinder);
            });
        }
        signalForegroundServiceObserversLocked(r);
        if (r.foregroundId != 0 && r.foregroundNoti != null) {
            r.foregroundNoti.flags |= Notification.FLAG_FOREGROUND_SERVICE;
            r.postNotification(true);
        }
        return true;
    }

    /**
     * Stop the foreground service delegate. This removes the process out of foreground service
     * process state.
     *
     * @param options an ForegroundServiceDelegationOptions object.
     */
    void stopForegroundServiceDelegateLocked(@NonNull ForegroundServiceDelegationOptions options) {
        ServiceRecord r = null;
        for (int i = mFgsDelegations.size() - 1; i >= 0; i--) {
            if (mFgsDelegations.keyAt(i).mOptions.isSameDelegate(options)) {
                Slog.d(TAG, "stopForegroundServiceDelegateLocked " + options.getDescription());
                r = mFgsDelegations.valueAt(i);
                break;
            }
        }
        if (r != null) {
            r.updateOomAdjSeq();
            bringDownServiceLocked(r, false);
        } else {
            Slog.e(TAG, "stopForegroundServiceDelegateLocked delegate does not exist "
                    + options.getDescription());
        }
    }

    /**
     * Stop the foreground service delegate by its ServiceConnection.
     * This removes the process out of foreground service process state.
     *
     * @param connection an ServiceConnection object.
     */
    void stopForegroundServiceDelegateLocked(@NonNull ServiceConnection connection) {
        ServiceRecord r = null;
        for (int i = mFgsDelegations.size() - 1; i >= 0; i--) {
            final ForegroundServiceDelegation d = mFgsDelegations.keyAt(i);
            if (d.mConnection == connection) {
                Slog.d(TAG, "stopForegroundServiceDelegateLocked "
                        + d.mOptions.getDescription());
                r = mFgsDelegations.valueAt(i);
                break;
            }
        }
        if (r != null) {
            r.updateOomAdjSeq();
            bringDownServiceLocked(r, false);
        } else {
            Slog.e(TAG, "stopForegroundServiceDelegateLocked delegate does not exist");
        }
    }

    private static void getClientPackages(ServiceRecord sr, ArraySet<String> output) {
        var connections = sr.getConnections();
        for (int conni = connections.size() - 1; conni >= 0; conni--) {
            var connl = connections.valueAt(conni);
            for (int i = 0, size = connl.size(); i < size; i++) {
                var conn = connl.get(i);
                if (conn.binding.client != null) {
                    output.add(conn.binding.client.info.packageName);
                }
            }
        }
    }

    /**
     * Return all client package names of a service.
     */
    ArraySet<String> getClientPackagesLocked(@NonNull String servicePackageName) {
        var results = new ArraySet<String>();
        int[] users = mAm.mUserController.getUsers();
        for (int ui = 0; ui < users.length; ui++) {
            ArrayMap<ComponentName, ServiceRecord> alls = getServicesLocked(users[ui]);
            for (int i = 0, size = alls.size(); i < size; i++) {
                ServiceRecord sr = alls.valueAt(i);
                if (sr.name.getPackageName().equals(servicePackageName)) {
                    getClientPackages(sr, results);
                }
            }
        }
        return results;
    }

    private boolean isDeviceProvisioningPackage(String packageName) {
        if (mCachedDeviceProvisioningPackage == null) {
            mCachedDeviceProvisioningPackage = mAm.mContext.getResources().getString(
                    com.android.internal.R.string.config_deviceProvisioningPackage);
        }
        return mCachedDeviceProvisioningPackage != null
                && mCachedDeviceProvisioningPackage.equals(packageName);
    }

    private boolean wasStopped(ServiceRecord serviceRecord) {
        return (serviceRecord.appInfo.flags & ApplicationInfo.FLAG_STOPPED) != 0;
    }
}
