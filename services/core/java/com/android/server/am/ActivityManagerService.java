/*
 * Copyright (C) 2006-2008 The Android Open Source Project
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

import static android.Manifest.permission.CHANGE_CONFIGURATION;
import static android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST;
import static android.Manifest.permission.FILTER_EVENTS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.REMOVE_TASKS;
import static android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND;
import static android.app.ActivityManager.INSTR_FLAG_DISABLE_HIDDEN_API_CHECKS;
import static android.app.ActivityManager.INSTR_FLAG_MOUNT_EXTERNAL_STORAGE_FULL;
import static android.app.ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.ActivityManagerInternal.ALLOW_FULL_ONLY;
import static android.app.ActivityManagerInternal.ALLOW_NON_FULL;
import static android.app.AppOpsManager.OP_NONE;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.ApplicationInfo.HIDDEN_API_ENFORCEMENT_DEFAULT;
import static android.content.pm.PackageManager.GET_PROVIDERS;
import static android.content.pm.PackageManager.MATCH_ALL;
import static android.content.pm.PackageManager.MATCH_ANY_USER;
import static android.content.pm.PackageManager.MATCH_DEBUG_TRIAGED_MISSING;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.NetworkPolicyManager.isProcStateAllowedWhileIdleOrPowerSaveMode;
import static android.net.NetworkPolicyManager.isProcStateAllowedWhileOnRestrictBackground;
import static android.os.FactoryTest.FACTORY_TEST_OFF;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_CRITICAL;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_HIGH;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_NORMAL;
import static android.os.IServiceManager.DUMP_FLAG_PROTO;
import static android.os.Process.BLUETOOTH_UID;
import static android.os.Process.FIRST_APPLICATION_UID;
import static android.os.Process.NETWORK_STACK_UID;
import static android.os.Process.NFC_UID;
import static android.os.Process.PHONE_UID;
import static android.os.Process.PROC_CHAR;
import static android.os.Process.PROC_OUT_LONG;
import static android.os.Process.PROC_PARENS;
import static android.os.Process.PROC_SPACE_TERM;
import static android.os.Process.ROOT_UID;
import static android.os.Process.SCHED_FIFO;
import static android.os.Process.SCHED_RESET_ON_FORK;
import static android.os.Process.SE_UID;
import static android.os.Process.SHELL_UID;
import static android.os.Process.SIGNAL_USR1;
import static android.os.Process.SYSTEM_UID;
import static android.os.Process.THREAD_PRIORITY_FOREGROUND;
import static android.os.Process.ZYGOTE_PROCESS;
import static android.os.Process.getTotalMemory;
import static android.os.Process.isThreadInProcess;
import static android.os.Process.killProcess;
import static android.os.Process.killProcessQuiet;
import static android.os.Process.myPid;
import static android.os.Process.myUid;
import static android.os.Process.readProcFile;
import static android.os.Process.removeAllProcessGroups;
import static android.os.Process.sendSignal;
import static android.os.Process.setThreadPriority;
import static android.os.Process.setThreadScheduler;
import static android.provider.Settings.Global.ALWAYS_FINISH_ACTIVITIES;
import static android.provider.Settings.Global.DEBUG_APP;
import static android.provider.Settings.Global.NETWORK_ACCESS_TIMEOUT_MS;
import static android.provider.Settings.Global.WAIT_FOR_DEBUGGER;
import static android.text.format.DateUtils.DAY_IN_MILLIS;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_ALL;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_ANR;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BACKGROUND_CHECK;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BACKUP;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BROADCAST;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BROADCAST_BACKGROUND;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BROADCAST_LIGHT;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_MU;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_NETWORK;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_OOM_ADJ;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PERMISSIONS_REVIEW;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_POWER;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PROCESSES;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PROCESS_OBSERVERS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PROVIDER;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PSS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_SERVICE;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_WHITELISTS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_BACKUP;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_BROADCAST;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_CLEANUP;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_LRU;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_MU;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_NETWORK;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_OOM_ADJ;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_POWER;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_PROCESSES;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_PROCESS_OBSERVERS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_PROVIDER;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_PSS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_SERVICE;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_UID_OBSERVERS;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.MemoryStatUtil.hasMemcg;
import static com.android.server.am.MemoryStatUtil.readMemoryStatFromFilesystem;
import static com.android.server.am.MemoryStatUtil.readRssHighWaterMarkFromProcfs;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_CLEANUP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_CONFIGURATION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_CONFIGURATION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_LOCKTASK;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_SWITCH;
import static com.android.server.wm.ActivityTaskManagerService.DUMP_ACTIVITIES_CMD;
import static com.android.server.wm.ActivityTaskManagerService.DUMP_ACTIVITIES_SHORT_CMD;
import static com.android.server.wm.ActivityTaskManagerService.DUMP_CONTAINERS_CMD;
import static com.android.server.wm.ActivityTaskManagerService.DUMP_LASTANR_CMD;
import static com.android.server.wm.ActivityTaskManagerService.DUMP_LASTANR_TRACES_CMD;
import static com.android.server.wm.ActivityTaskManagerService.DUMP_RECENTS_CMD;
import static com.android.server.wm.ActivityTaskManagerService.DUMP_RECENTS_SHORT_CMD;
import static com.android.server.wm.ActivityTaskManagerService.DUMP_STARTER_CMD;
import static com.android.server.wm.ActivityTaskManagerService.KEY_DISPATCHING_TIMEOUT_MS;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_NONE;
import static com.android.server.wm.ActivityTaskManagerService.relaunchReasonToString;

import android.Manifest;
import android.Manifest.permission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManager.StackInfo;
import android.app.ActivityManagerInternal;
import android.app.ActivityManagerProto;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.AppOpsManagerInternal.CheckOpsDelegate;
import android.app.ApplicationErrorReport;
import android.app.ApplicationThreadConstants;
import android.app.BroadcastOptions;
import android.app.ContentProviderHolder;
import android.app.Dialog;
import android.app.IActivityController;
import android.app.IActivityManager;
import android.app.IApplicationThread;
import android.app.IAssistDataReceiver;
import android.app.IInstrumentationWatcher;
import android.app.INotificationManager;
import android.app.IProcessObserver;
import android.app.IServiceConnection;
import android.app.IStopUserCallback;
import android.app.ITaskStackListener;
import android.app.IUiAutomationConnection;
import android.app.IUidObserver;
import android.app.IUserSwitchObserver;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProcessMemoryHighWaterMark;
import android.app.ProcessMemoryState;
import android.app.ProfilerInfo;
import android.app.WaitResult;
import android.app.WindowConfiguration.ActivityType;
import android.app.WindowConfiguration.WindowingMode;
import android.app.backup.IBackupManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageEvents.Event;
import android.app.usage.UsageStatsManagerInternal;
import android.appwidget.AppWidgetManager;
import android.content.AutofillOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.ContentCaptureOptions;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IContentProvider;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ActivityPresentationInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ApplicationInfo.HiddenApiEnforcementPolicy;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageManager;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageManagerInternal.CheckPermissionDelegate;
import android.content.pm.ParceledListSlice;
import android.content.pm.PathPermission;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.SELinuxUtil;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerInternal;
import android.location.LocationManager;
import android.media.audiofx.AudioEffect;
import android.net.Proxy;
import android.net.Uri;
import android.os.AppZygote;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.BinderProxy;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.DropBoxManager;
import android.os.FactoryTest;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.IDeviceIdentifiersPolicyService;
import android.os.IPermissionController;
import android.os.IProcessInfoService;
import android.os.IProgressListener;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.TransactionTooLargeException;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.WorkSource;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.sysprop.VoldProperties;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.SuggestionSpan;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.StatsLog;
import android.util.TimeUtils;
import android.util.TimingsTraceLog;
import android.util.proto.ProtoOutputStream;
import android.util.proto.ProtoUtils;
import android.view.Display;
import android.view.Gravity;
import android.view.IRecentsAnimationRunner;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.autofill.AutofillManagerInternal;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.DumpHeapActivity;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.android.internal.app.ProcessMap;
import com.android.internal.app.SystemUserHomeActivity;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.BinderInternal;
import com.android.internal.os.ByteTransferPipe;
import com.android.internal.os.IResultReceiver;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.os.TransferPipe;
import com.android.internal.os.Zygote;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.MemInfoReader;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.QuadFunction;
import com.android.internal.util.function.TriFunction;
import com.android.server.AlarmManagerInternal;
import com.android.server.AttributeCache;
import com.android.server.DeviceIdleController;
import com.android.server.DisplayThread;
import com.android.server.IntentResolver;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.server.NetworkManagementInternal;
import com.android.server.PackageWatchdog;
import com.android.server.RescueParty;
import com.android.server.ServiceThread;
import com.android.server.SystemConfig;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;
import com.android.server.ThreadPriorityBooster;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerServiceDumpProcessesProto.UidObserverRegistrationProto;
import com.android.server.am.MemoryStatUtil.MemoryStat;
import com.android.server.appop.AppOpsService;
import com.android.server.contentcapture.ContentCaptureManagerInternal;
import com.android.server.firewall.IntentFirewall;
import com.android.server.job.JobSchedulerInternal;
import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;
import com.android.server.uri.GrantUri;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.utils.PriorityDump;
import com.android.server.vr.VrManagerInternal;
import com.android.server.wm.ActivityServiceConnectionsHolder;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.ActivityTaskManagerService;
import com.android.server.wm.WindowManagerService;
import com.android.server.wm.WindowProcessController;

import dalvik.system.VMRuntime;

import libcore.util.EmptyArray;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

public class ActivityManagerService extends IActivityManager.Stub
        implements Watchdog.Monitor, BatteryStatsImpl.BatteryCallback {

    /**
     * Priority we boost main thread and RT of top app to.
     */
    public static final int TOP_APP_PRIORITY_BOOST = -10;

    static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityManagerService" : TAG_AM;
    static final String TAG_BACKUP = TAG + POSTFIX_BACKUP;
    private static final String TAG_BROADCAST = TAG + POSTFIX_BROADCAST;
    private static final String TAG_CLEANUP = TAG + POSTFIX_CLEANUP;
    private static final String TAG_CONFIGURATION = TAG + POSTFIX_CONFIGURATION;
    private static final String TAG_LOCKTASK = TAG + POSTFIX_LOCKTASK;
    static final String TAG_LRU = TAG + POSTFIX_LRU;
    private static final String TAG_MU = TAG + POSTFIX_MU;
    private static final String TAG_NETWORK = TAG + POSTFIX_NETWORK;
    static final String TAG_OOM_ADJ = TAG + POSTFIX_OOM_ADJ;
    private static final String TAG_POWER = TAG + POSTFIX_POWER;
    static final String TAG_PROCESS_OBSERVERS = TAG + POSTFIX_PROCESS_OBSERVERS;
    static final String TAG_PROCESSES = TAG + POSTFIX_PROCESSES;
    private static final String TAG_PROVIDER = TAG + POSTFIX_PROVIDER;
    static final String TAG_PSS = TAG + POSTFIX_PSS;
    private static final String TAG_SERVICE = TAG + POSTFIX_SERVICE;
    private static final String TAG_SWITCH = TAG + POSTFIX_SWITCH;
    static final String TAG_UID_OBSERVERS = TAG + POSTFIX_UID_OBSERVERS;

    // Mock "pretend we're idle now" broadcast action to the job scheduler; declared
    // here so that while the job scheduler can depend on AMS, the other way around
    // need not be the case.
    public static final String ACTION_TRIGGER_IDLE = "com.android.server.ACTION_TRIGGER_IDLE";

    /** Control over CPU and battery monitoring */
    // write battery stats every 30 minutes.
    static final long BATTERY_STATS_TIME = 30 * 60 * 1000;
    static final boolean MONITOR_CPU_USAGE = true;
    // don't sample cpu less than every 5 seconds.
    static final long MONITOR_CPU_MIN_TIME = 5 * 1000;
    // wait possibly forever for next cpu sample.
    static final long MONITOR_CPU_MAX_TIME = 0x0fffffff;
    static final boolean MONITOR_THREAD_CPU_USAGE = false;

    // The flags that are set for all calls we make to the package manager.
    public static final int STOCK_PM_FLAGS = PackageManager.GET_SHARED_LIBRARY_FILES;

    static final String SYSTEM_DEBUGGABLE = "ro.debuggable";

    public static final String ANR_TRACE_DIR = "/data/anr";

    // Maximum number of receivers an app can register.
    private static final int MAX_RECEIVERS_ALLOWED_PER_APP = 1000;

    // How long we wait for a launched process to attach to the activity manager
    // before we decide it's never going to come up for real.
    static final int PROC_START_TIMEOUT = 10*1000;
    // How long we wait for an attached process to publish its content providers
    // before we decide it must be hung.
    static final int CONTENT_PROVIDER_PUBLISH_TIMEOUT = 10*1000;

    // How long we wait to kill an application zygote, after the last process using
    // it has gone away.
    static final int KILL_APP_ZYGOTE_DELAY_MS = 5 * 1000;
    /**
     * How long we wait for an provider to be published. Should be longer than
     * {@link #CONTENT_PROVIDER_PUBLISH_TIMEOUT}.
     */
    static final int CONTENT_PROVIDER_WAIT_TIMEOUT = 20 * 1000;

    // How long we wait for a launched process to attach to the activity manager
    // before we decide it's never going to come up for real, when the process was
    // started with a wrapper for instrumentation (such as Valgrind) because it
    // could take much longer than usual.
    static final int PROC_START_TIMEOUT_WITH_WRAPPER = 1200*1000;

    // How long we allow a receiver to run before giving up on it.
    static final int BROADCAST_FG_TIMEOUT = 10*1000;
    static final int BROADCAST_BG_TIMEOUT = 60*1000;

    public static final int MY_PID = myPid();

    static final String[] EMPTY_STRING_ARRAY = new String[0];

    // How many bytes to write into the dropbox log before truncating
    static final int DROPBOX_MAX_SIZE = 192 * 1024;
    // Assumes logcat entries average around 100 bytes; that's not perfect stack traces count
    // as one line, but close enough for now.
    static final int RESERVED_BYTES_PER_LOGCAT_LINE = 100;

    /** If a UID observer takes more than this long, send a WTF. */
    private static final int SLOW_UID_OBSERVER_THRESHOLD_MS = 20;

    // Necessary ApplicationInfo flags to mark an app as persistent
    static final int PERSISTENT_MASK =
            ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_PERSISTENT;

    // Intent sent when remote bugreport collection has been completed
    private static final String INTENT_REMOTE_BUGREPORT_FINISHED =
            "com.android.internal.intent.action.REMOTE_BUGREPORT_FINISHED";

    // If set, we will push process association information in to procstats.
    static final boolean TRACK_PROCSTATS_ASSOCIATIONS = true;

    /**
     * Default value for {@link Settings.Global#NETWORK_ACCESS_TIMEOUT_MS}.
     */
    private static final long NETWORK_ACCESS_TIMEOUT_DEFAULT_MS = 200; // 0.2 sec

    // The minimum memory growth threshold (in KB) for low RAM devices.
    private static final int MINIMUM_MEMORY_GROWTH_THRESHOLD = 10 * 1000; // 10 MB

    /**
     * The number of binder proxies we need to have before we start warning and
     * dumping debug info.
     */
    private static final int BINDER_PROXY_HIGH_WATERMARK = 6000;

    /**
     * Low watermark that needs to be met before we consider dumping info again,
     * after already hitting the high watermark.
     */
    private static final int BINDER_PROXY_LOW_WATERMARK = 5500;

    /**
     * State indicating that there is no need for any blocking for network.
     */
    @VisibleForTesting
    static final int NETWORK_STATE_NO_CHANGE = 0;

    /**
     * State indicating that the main thread needs to be informed about the network wait.
     */
    @VisibleForTesting
    static final int NETWORK_STATE_BLOCK = 1;

    /**
     * State indicating that any threads waiting for network state to get updated can be unblocked.
     */
    @VisibleForTesting
    static final int NETWORK_STATE_UNBLOCK = 2;

    // Max character limit for a notification title. If the notification title is larger than this
    // the notification will not be legible to the user.
    private static final int MAX_BUGREPORT_TITLE_SIZE = 50;

    private static final int NATIVE_DUMP_TIMEOUT_MS = 2000; // 2 seconds;

    final OomAdjuster mOomAdjuster;

    /** All system services */
    SystemServiceManager mSystemServiceManager;

    private Installer mInstaller;

    final InstrumentationReporter mInstrumentationReporter = new InstrumentationReporter();

    final ArrayList<ActiveInstrumentation> mActiveInstrumentation = new ArrayList<>();

    public final IntentFirewall mIntentFirewall;

    public OomAdjProfiler mOomAdjProfiler = new OomAdjProfiler();

    // Whether we should use SCHED_FIFO for UI and RenderThreads.
    boolean mUseFifoUiScheduling = false;

    // Use an offload queue for long broadcasts, e.g. BOOT_COMPLETED.
    // For simplicity, since we statically declare the size of the array of BroadcastQueues,
    // we still create this new offload queue, but never ever put anything on it.
    boolean mEnableOffloadQueue;

    BroadcastQueue mFgBroadcastQueue;
    BroadcastQueue mBgBroadcastQueue;
    BroadcastQueue mOffloadBroadcastQueue;
    // Convenient for easy iteration over the queues. Foreground is first
    // so that dispatch of foreground broadcasts gets precedence.
    final BroadcastQueue[] mBroadcastQueues = new BroadcastQueue[3];

    BroadcastStats mLastBroadcastStats;
    BroadcastStats mCurBroadcastStats;

    BroadcastQueue broadcastQueueForIntent(Intent intent) {
        if (isOnOffloadQueue(intent.getFlags())) {
            if (DEBUG_BROADCAST_BACKGROUND) {
                Slog.i(TAG_BROADCAST,
                        "Broadcast intent " + intent + " on offload queue");
            }
            return mOffloadBroadcastQueue;
        }

        final boolean isFg = (intent.getFlags() & Intent.FLAG_RECEIVER_FOREGROUND) != 0;
        if (DEBUG_BROADCAST_BACKGROUND) Slog.i(TAG_BROADCAST,
                "Broadcast intent " + intent + " on "
                + (isFg ? "foreground" : "background") + " queue");
        return (isFg) ? mFgBroadcastQueue : mBgBroadcastQueue;
    }

    /**
     * The package name of the DeviceOwner. This package is not permitted to have its data cleared.
     */
    String mDeviceOwnerName;

    final UserController mUserController;
    @VisibleForTesting
    public final PendingIntentController mPendingIntentController;

    final AppErrors mAppErrors;
    final PackageWatchdog mPackageWatchdog;

    /**
     * Indicates the maximum time spent waiting for the network rules to get updated.
     */
    @VisibleForTesting
    long mWaitForNetworkTimeoutMs;

    /** Total # of UID change events dispatched, shown in dumpsys. */
    int mUidChangeDispatchCount;

    /**
     * Helper class which strips out priority and proto arguments then calls the dump function with
     * the appropriate arguments. If priority arguments are omitted, function calls the legacy
     * dump command.
     * If priority arguments are omitted all sections are dumped, otherwise sections are dumped
     * according to their priority.
     */
    private final PriorityDump.PriorityDumper mPriorityDumper = new PriorityDump.PriorityDumper() {
        @Override
        public void dumpCritical(FileDescriptor fd, PrintWriter pw, String[] args,
                boolean asProto) {
            if (asProto) return;
            doDump(fd, pw, new String[]{"activities"}, asProto);
            doDump(fd, pw, new String[]{"service", "all-platform-critical"}, asProto);
        }

        @Override
        public void dumpNormal(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
            doDump(fd, pw, new String[]{"-a", "--normal-priority"}, asProto);
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
            doDump(fd, pw, args, asProto);
        }
    };

    private static ThreadPriorityBooster sThreadPriorityBooster = new ThreadPriorityBooster(
            THREAD_PRIORITY_FOREGROUND, LockGuard.INDEX_ACTIVITY);

    static void boostPriorityForLockedSection() {
        sThreadPriorityBooster.boost();
    }

    static void resetPriorityAfterLockedSection() {
        sThreadPriorityBooster.reset();
    }

    /**
     * Process management.
     */
    final ProcessList mProcessList = new ProcessList();

    /**
     * Tracking long-term execution of processes to look for abuse and other
     * bad app behavior.
     */
    final ProcessStatsService mProcessStats;

    /**
     * Non-persistent appId whitelist for background restrictions
     */
    int[] mBackgroundAppIdWhitelist = new int[] {
            BLUETOOTH_UID
    };

    /**
     * Broadcast actions that will always be deliverable to unlaunched/background apps
     */
    ArraySet<String> mBackgroundLaunchBroadcasts;

    /**
     * When an app has restrictions on the other apps that can have associations with it,
     * it appears here with a set of the allowed apps and also track debuggability of the app.
     */
    ArrayMap<String, PackageAssociationInfo> mAllowedAssociations;

    /**
     * Tracks association information for a particular package along with debuggability.
     * <p> Associations for a package A are allowed to package B if B is part of the
     *     allowed associations for A or if A is debuggable.
     */
    private final class PackageAssociationInfo {
        private final String mSourcePackage;
        private final ArraySet<String> mAllowedPackageAssociations;
        private boolean mIsDebuggable;

        PackageAssociationInfo(String sourcePackage, ArraySet<String> allowedPackages,
                boolean isDebuggable) {
            mSourcePackage = sourcePackage;
            mAllowedPackageAssociations = allowedPackages;
            mIsDebuggable = isDebuggable;
        }

        /**
         * Returns true if {@code mSourcePackage} is allowed association with
         * {@code targetPackage}.
         */
        boolean isPackageAssociationAllowed(String targetPackage) {
            return mIsDebuggable || mAllowedPackageAssociations.contains(targetPackage);
        }

        boolean isDebuggable() {
            return mIsDebuggable;
        }

        void setDebuggable(boolean isDebuggable) {
            mIsDebuggable = isDebuggable;
        }

        ArraySet<String> getAllowedPackageAssociations() {
            return mAllowedPackageAssociations;
        }
    }

    /**
     * All of the processes we currently have running organized by pid.
     * The keys are the pid running the application.
     *
     * <p>NOTE: This object is protected by its own lock, NOT the global activity manager lock!
     */
    final PidMap mPidsSelfLocked = new PidMap();
    final class PidMap {
        private final SparseArray<ProcessRecord> mPidMap = new SparseArray<>();

        /**
         * Puts the process record in the map.
         * <p>NOTE: Callers should avoid acquiring the mPidsSelfLocked lock before calling this
         * method.
         */
        void put(int key, ProcessRecord value) {
            synchronized (this) {
                mPidMap.put(key, value);
            }
            mAtmInternal.onProcessMapped(key, value.getWindowProcessController());
        }

        /**
         * Removes the process record from the map.
         * <p>NOTE: Callers should avoid acquiring the mPidsSelfLocked lock before calling this
         * method.
         */
        void remove(int pid) {
            synchronized (this) {
                mPidMap.remove(pid);
            }
            mAtmInternal.onProcessUnMapped(pid);
        }

        /**
         * Removes the process record from the map if it has a thread.
         * <p>NOTE: Callers should avoid acquiring the mPidsSelfLocked lock before calling this
         * method.
         */
        boolean removeIfNoThread(int pid) {
            boolean removed = false;
            synchronized (this) {
                final ProcessRecord app = get(pid);
                if (app != null && app.thread == null) {
                    mPidMap.remove(pid);
                    removed = true;
                }
            }
            if (removed) {
                mAtmInternal.onProcessUnMapped(pid);
            }
            return removed;
        }

        ProcessRecord get(int pid) {
            return mPidMap.get(pid);
        }

        int size() {
            return mPidMap.size();
        }

        ProcessRecord valueAt(int index) {
            return mPidMap.valueAt(index);
        }

        int keyAt(int index) {
            return mPidMap.keyAt(index);
        }

        int indexOfKey(int key) {
            return mPidMap.indexOfKey(key);
        }
    }

    /**
     * All of the processes that have been forced to be important.  The key
     * is the pid of the caller who requested it (we hold a death
     * link on it).
     */
    abstract class ImportanceToken implements IBinder.DeathRecipient {
        final int pid;
        final IBinder token;
        final String reason;

        ImportanceToken(int _pid, IBinder _token, String _reason) {
            pid = _pid;
            token = _token;
            reason = _reason;
        }

        @Override
        public String toString() {
            return "ImportanceToken { " + Integer.toHexString(System.identityHashCode(this))
                    + " " + reason + " " + pid + " " + token + " }";
        }

        void writeToProto(ProtoOutputStream proto, long fieldId) {
            final long pToken = proto.start(fieldId);
            proto.write(ImportanceTokenProto.PID, pid);
            if (token != null) {
                proto.write(ImportanceTokenProto.TOKEN, token.toString());
            }
            proto.write(ImportanceTokenProto.REASON, reason);
            proto.end(pToken);
        }
    }
    final SparseArray<ImportanceToken> mImportantProcesses = new SparseArray<ImportanceToken>();

    /**
     * List of records for processes that someone had tried to start before the
     * system was ready.  We don't start them at that point, but ensure they
     * are started by the time booting is complete.
     */
    final ArrayList<ProcessRecord> mProcessesOnHold = new ArrayList<ProcessRecord>();

    /**
     * List of persistent applications that are in the process
     * of being started.
     */
    final ArrayList<ProcessRecord> mPersistentStartingProcesses = new ArrayList<ProcessRecord>();

    /**
     * List of processes that should gc as soon as things are idle.
     */
    final ArrayList<ProcessRecord> mProcessesToGc = new ArrayList<ProcessRecord>();

    /**
     * Processes we want to collect PSS data from.
     */
    final ArrayList<ProcessRecord> mPendingPssProcesses = new ArrayList<ProcessRecord>();

    private boolean mBinderTransactionTrackingEnabled = false;

    /**
     * Last time we requested PSS data of all processes.
     */
    long mLastFullPssTime = SystemClock.uptimeMillis();

    /**
     * If set, the next time we collect PSS data we should do a full collection
     * with data from native processes and the kernel.
     */
    boolean mFullPssPending = false;


    /**
     * This is for verifying the UID report flow.
     */
    static final boolean VALIDATE_UID_STATES = true;
    final ActiveUids mValidateUids = new ActiveUids(this, false /* postChangesToAtm */);

    /**
     * Fingerprints (hashCode()) of stack traces that we've
     * already logged DropBox entries for.  Guarded by itself.  If
     * something (rogue user app) forces this over
     * MAX_DUP_SUPPRESSED_STACKS entries, the contents are cleared.
     */
    private final HashSet<Integer> mAlreadyLoggedViolatedStacks = new HashSet<Integer>();
    private static final int MAX_DUP_SUPPRESSED_STACKS = 5000;

    /**
     * Keeps track of all IIntentReceivers that have been registered for broadcasts.
     * Hash keys are the receiver IBinder, hash value is a ReceiverList.
     */
    final HashMap<IBinder, ReceiverList> mRegisteredReceivers = new HashMap<>();

    /**
     * Resolver for broadcast intents to registered receivers.
     * Holds BroadcastFilter (subclass of IntentFilter).
     */
    final IntentResolver<BroadcastFilter, BroadcastFilter> mReceiverResolver
            = new IntentResolver<BroadcastFilter, BroadcastFilter>() {
        @Override
        protected boolean allowFilterResult(
                BroadcastFilter filter, List<BroadcastFilter> dest) {
            IBinder target = filter.receiverList.receiver.asBinder();
            for (int i = dest.size() - 1; i >= 0; i--) {
                if (dest.get(i).receiverList.receiver.asBinder() == target) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected BroadcastFilter newResult(BroadcastFilter filter, int match, int userId) {
            if (userId == UserHandle.USER_ALL || filter.owningUserId == UserHandle.USER_ALL
                    || userId == filter.owningUserId) {
                return super.newResult(filter, match, userId);
            }
            return null;
        }

        @Override
        protected BroadcastFilter[] newArray(int size) {
            return new BroadcastFilter[size];
        }

        @Override
        protected boolean isPackageForFilter(String packageName, BroadcastFilter filter) {
            return packageName.equals(filter.packageName);
        }
    };

    /**
     * State of all active sticky broadcasts per user.  Keys are the action of the
     * sticky Intent, values are an ArrayList of all broadcasted intents with
     * that action (which should usually be one).  The SparseArray is keyed
     * by the user ID the sticky is for, and can include UserHandle.USER_ALL
     * for stickies that are sent to all users.
     */
    final SparseArray<ArrayMap<String, ArrayList<Intent>>> mStickyBroadcasts =
            new SparseArray<ArrayMap<String, ArrayList<Intent>>>();

    final ActiveServices mServices;

    final static class Association {
        final int mSourceUid;
        final String mSourceProcess;
        final int mTargetUid;
        final ComponentName mTargetComponent;
        final String mTargetProcess;

        int mCount;
        long mTime;

        int mNesting;
        long mStartTime;

        // states of the source process when the bind occurred.
        int mLastState = ActivityManager.MAX_PROCESS_STATE + 1;
        long mLastStateUptime;
        long[] mStateTimes = new long[ActivityManager.MAX_PROCESS_STATE
                - ActivityManager.MIN_PROCESS_STATE+1];

        Association(int sourceUid, String sourceProcess, int targetUid,
                ComponentName targetComponent, String targetProcess) {
            mSourceUid = sourceUid;
            mSourceProcess = sourceProcess;
            mTargetUid = targetUid;
            mTargetComponent = targetComponent;
            mTargetProcess = targetProcess;
        }
    }

    /**
     * When service association tracking is enabled, this is all of the associations we
     * have seen.  Mapping is target uid -> target component -> source uid -> source process name
     * -> association data.
     */
    final SparseArray<ArrayMap<ComponentName, SparseArray<ArrayMap<String, Association>>>>
            mAssociations = new SparseArray<>();
    boolean mTrackingAssociations;

    /**
     * Backup/restore process management
     */
    @GuardedBy("this")
    final SparseArray<BackupRecord> mBackupTargets = new SparseArray<>();

    final ProviderMap mProviderMap;

    /**
     * List of content providers who have clients waiting for them.  The
     * application is currently being launched and the provider will be
     * removed from this list once it is published.
     */
    final ArrayList<ContentProviderRecord> mLaunchingProviders = new ArrayList<>();

    boolean mSystemProvidersInstalled;

    CoreSettingsObserver mCoreSettingsObserver;

    DevelopmentSettingsObserver mDevelopmentSettingsObserver;

    private final class DevelopmentSettingsObserver extends ContentObserver {
        private final Uri mUri = Settings.Global
                .getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED);

        private final ComponentName mBugreportStorageProvider = new ComponentName(
                "com.android.shell", "com.android.shell.BugreportStorageProvider");

        public DevelopmentSettingsObserver() {
            super(mHandler);
            mContext.getContentResolver().registerContentObserver(mUri, false, this,
                    UserHandle.USER_ALL);
            // Always kick once to ensure that we match current state
            onChange();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, @UserIdInt int userId) {
            if (mUri.equals(uri)) {
                onChange();
            }
        }

        public void onChange() {
            final boolean enabled = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, Build.IS_ENG ? 1 : 0) != 0;
            mContext.getPackageManager().setComponentEnabledSetting(mBugreportStorageProvider,
                    enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    0);
        }
    }

    /**
     * Thread-local storage used to carry caller permissions over through
     * indirect content-provider access.
     */
    private class Identity {
        public final IBinder token;
        public final int pid;
        public final int uid;

        Identity(IBinder _token, int _pid, int _uid) {
            token = _token;
            pid = _pid;
            uid = _uid;
        }
    }

    private static final ThreadLocal<Identity> sCallerIdentity = new ThreadLocal<Identity>();

    /**
     * All information we have collected about the runtime performance of
     * any user id that can impact battery performance.
     */
    final BatteryStatsService mBatteryStatsService;

    /**
     * Information about component usage
     */
    UsageStatsManagerInternal mUsageStatsService;

    /**
     * Access to DeviceIdleController service.
     */
    DeviceIdleController.LocalService mLocalDeviceIdleController;

    /**
     * Power-save whitelisted app-ids (not including except-idle-whitelisted ones).
     */
    int[] mDeviceIdleWhitelist = new int[0];

    /**
     * Power-save whitelisted app-ids (including except-idle-whitelisted ones).
     */
    int[] mDeviceIdleExceptIdleWhitelist = new int[0];

    /**
     * Set of app ids that are temporarily allowed to escape bg check due to high-pri message
     */
    int[] mDeviceIdleTempWhitelist = new int[0];

    static final class PendingTempWhitelist {
        final int targetUid;
        final long duration;
        final String tag;

        PendingTempWhitelist(int _targetUid, long _duration, String _tag) {
            targetUid = _targetUid;
            duration = _duration;
            tag = _tag;
        }

        void writeToProto(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);
            proto.write(ActivityManagerServiceDumpProcessesProto.PendingTempWhitelist.TARGET_UID, targetUid);
            proto.write(ActivityManagerServiceDumpProcessesProto.PendingTempWhitelist.DURATION_MS, duration);
            proto.write(ActivityManagerServiceDumpProcessesProto.PendingTempWhitelist.TAG, tag);
            proto.end(token);
        }
    }

    final PendingTempWhitelists mPendingTempWhitelist = new PendingTempWhitelists(this);

    /**
     * Information about and control over application operations
     */
    final AppOpsService mAppOpsService;

    /**
     * List of initialization arguments to pass to all processes when binding applications to them.
     * For example, references to the commonly used services.
     */
    ArrayMap<String, IBinder> mAppBindArgs;
    ArrayMap<String, IBinder> mIsolatedAppBindArgs;

    /**
     * Temporary to avoid allocations.  Protected by main lock.
     */
    final StringBuilder mStringBuilder = new StringBuilder(256);

    volatile boolean mProcessesReady = false;
    volatile boolean mSystemReady = false;
    volatile boolean mOnBattery = false;
    final int mFactoryTest;
    volatile boolean mBooting = false;

    @GuardedBy("this") boolean mCallFinishBooting = false;
    @GuardedBy("this") boolean mBootAnimationComplete = false;

    final Context mContext;

    /**
     * This Context is themable and meant for UI display (AlertDialogs, etc.). The theme can
     * change at runtime. Use mContext for non-UI purposes.
     */
    final Context mUiContext;

    /**
     * Last time (in uptime) at which we checked for power usage.
     */
    long mLastPowerCheckUptime;

    /**
     * For some direct access we need to power manager.
     */
    PowerManagerInternal mLocalPowerManager;

    /**
     * State of external calls telling us if the device is awake or asleep.
     */
    int mWakefulness = PowerManagerInternal.WAKEFULNESS_AWAKE;

    /**
     * Allow the current computed overall memory level of the system to go down?
     * This is set to false when we are killing processes for reasons other than
     * memory management, so that the now smaller process list will not be taken as
     * an indication that memory is tighter.
     */
    boolean mAllowLowerMemLevel = false;

    /**
     * The last computed memory level, for holding when we are in a state that
     * processes are going away for other reasons.
     */
    int mLastMemoryLevel = ProcessStats.ADJ_MEM_FACTOR_NORMAL;

    /**
     * The last total number of process we have, to determine if changes actually look
     * like a shrinking number of process due to lower RAM.
     */
    int mLastNumProcesses;

    /**
     * The uptime of the last time we performed idle maintenance.
     */
    long mLastIdleTime = SystemClock.uptimeMillis();

    /**
     * Total time spent with RAM that has been added in the past since the last idle time.
     */
    long mLowRamTimeSinceLastIdle = 0;

    /**
     * If RAM is currently low, when that horrible situation started.
     */
    long mLowRamStartTime = 0;

    /**
     * For reporting to battery stats the current top application.
     */
    private String mCurResumedPackage = null;
    private int mCurResumedUid = -1;

    /**
     * For reporting to battery stats the apps currently running foreground
     * service.  The ProcessMap is package/uid tuples; each of these contain
     * an array of the currently foreground processes.
     */
    final ProcessMap<ArrayList<ProcessRecord>> mForegroundPackages
            = new ProcessMap<ArrayList<ProcessRecord>>();

    /**
     * Set if the systemServer made a call to enterSafeMode.
     */
    boolean mSafeMode;

    /**
     * If true, we are running under a test environment so will sample PSS from processes
     * much more rapidly to try to collect better data when the tests are rapidly
     * running through apps.
     */
    boolean mTestPssMode = false;

    String mDebugApp = null;
    boolean mWaitForDebugger = false;
    boolean mDebugTransient = false;
    String mOrigDebugApp = null;
    boolean mOrigWaitForDebugger = false;
    boolean mAlwaysFinishActivities = false;

    class ProfileData {
        private String mProfileApp = null;
        private ProcessRecord mProfileProc = null;
        private ProfilerInfo mProfilerInfo = null;

        void setProfileApp(String profileApp) {
            mProfileApp = profileApp;
            if (mAtmInternal != null) {
                mAtmInternal.setProfileApp(profileApp);
            }
        }

        String getProfileApp() {
            return mProfileApp;
        }

        void setProfileProc(ProcessRecord profileProc) {
            mProfileProc = profileProc;
            if (mAtmInternal != null) {
                mAtmInternal.setProfileProc(profileProc == null ? null
                        : profileProc.getWindowProcessController());
            }
        }

        ProcessRecord getProfileProc() {
            return mProfileProc;
        }

        void setProfilerInfo(ProfilerInfo profilerInfo) {
            mProfilerInfo = profilerInfo;
            if (mAtmInternal != null) {
                mAtmInternal.setProfilerInfo(profilerInfo);
            }
        }

        ProfilerInfo getProfilerInfo() {
            return mProfilerInfo;
        }
    }
    final ProfileData mProfileData = new ProfileData();

    /**
     * Stores a map of process name -> agent string. When a process is started and mAgentAppMap
     * is not null, this map is checked and the mapped agent installed during bind-time. Note:
     * A non-null agent in mProfileInfo overrides this.
     */
    private @Nullable Map<String, String> mAppAgentMap = null;

    int mProfileType = 0;
    final ProcessMap<Pair<Long, String>> mMemWatchProcesses = new ProcessMap<>();
    String mMemWatchDumpProcName;
    String mMemWatchDumpFile;
    int mMemWatchDumpPid;
    int mMemWatchDumpUid;
    String mTrackAllocationApp = null;
    String mNativeDebuggingApp = null;

    private final Injector mInjector;

    static final class ProcessChangeItem {
        static final int CHANGE_ACTIVITIES = 1<<0;
        static final int CHANGE_FOREGROUND_SERVICES = 1<<1;
        int changes;
        int uid;
        int pid;
        int processState;
        boolean foregroundActivities;
        int foregroundServiceTypes;
    }

    static final class UidObserverRegistration {
        final int uid;
        final String pkg;
        final int which;
        final int cutpoint;

        /**
         * Total # of callback calls that took more than {@link #SLOW_UID_OBSERVER_THRESHOLD_MS}.
         * We show it in dumpsys.
         */
        int mSlowDispatchCount;

        /** Max time it took for each dispatch. */
        int mMaxDispatchTime;

        final SparseIntArray lastProcStates;

        // Please keep the enum lists in sync
        private static int[] ORIG_ENUMS = new int[]{
                ActivityManager.UID_OBSERVER_IDLE,
                ActivityManager.UID_OBSERVER_ACTIVE,
                ActivityManager.UID_OBSERVER_GONE,
                ActivityManager.UID_OBSERVER_PROCSTATE,
        };
        private static int[] PROTO_ENUMS = new int[]{
                ActivityManagerProto.UID_OBSERVER_FLAG_IDLE,
                ActivityManagerProto.UID_OBSERVER_FLAG_ACTIVE,
                ActivityManagerProto.UID_OBSERVER_FLAG_GONE,
                ActivityManagerProto.UID_OBSERVER_FLAG_PROCSTATE,
        };

        UidObserverRegistration(int _uid, String _pkg, int _which, int _cutpoint) {
            uid = _uid;
            pkg = _pkg;
            which = _which;
            cutpoint = _cutpoint;
            if (cutpoint >= ActivityManager.MIN_PROCESS_STATE) {
                lastProcStates = new SparseIntArray();
            } else {
                lastProcStates = null;
            }
        }

        void writeToProto(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);
            proto.write(UidObserverRegistrationProto.UID, uid);
            proto.write(UidObserverRegistrationProto.PACKAGE, pkg);
            ProtoUtils.writeBitWiseFlagsToProtoEnum(proto, UidObserverRegistrationProto.FLAGS,
                    which, ORIG_ENUMS, PROTO_ENUMS);
            proto.write(UidObserverRegistrationProto.CUT_POINT, cutpoint);
            if (lastProcStates != null) {
                final int NI = lastProcStates.size();
                for (int i=0; i<NI; i++) {
                    final long pToken = proto.start(UidObserverRegistrationProto.LAST_PROC_STATES);
                    proto.write(UidObserverRegistrationProto.ProcState.UID, lastProcStates.keyAt(i));
                    proto.write(UidObserverRegistrationProto.ProcState.STATE, lastProcStates.valueAt(i));
                    proto.end(pToken);
                }
            }
            proto.end(token);
        }
    }

    // TODO: Move below 4 members and code to ProcessList
    final RemoteCallbackList<IProcessObserver> mProcessObservers = new RemoteCallbackList<>();
    ProcessChangeItem[] mActiveProcessChanges = new ProcessChangeItem[5];

    final ArrayList<ProcessChangeItem> mPendingProcessChanges = new ArrayList<>();
    final ArrayList<ProcessChangeItem> mAvailProcessChanges = new ArrayList<>();

    final RemoteCallbackList<IUidObserver> mUidObservers = new RemoteCallbackList<>();
    UidRecord.ChangeItem[] mActiveUidChanges = new UidRecord.ChangeItem[5];

    final ArrayList<UidRecord.ChangeItem> mPendingUidChanges = new ArrayList<>();
    final ArrayList<UidRecord.ChangeItem> mAvailUidChanges = new ArrayList<>();

    OomAdjObserver mCurOomAdjObserver;
    int mCurOomAdjUid;

    interface OomAdjObserver {
        void onOomAdjMessage(String msg);
    }

    /**
     * Runtime CPU use collection thread.  This object's lock is used to
     * perform synchronization with the thread (notifying it to run).
     */
    final Thread mProcessCpuThread;

    /**
     * Used to collect per-process CPU use for ANRs, battery stats, etc.
     * Must acquire this object's lock when accessing it.
     * NOTE: this lock will be held while doing long operations (trawling
     * through all processes in /proc), so it should never be acquired by
     * any critical paths such as when holding the main activity manager lock.
     */
    final ProcessCpuTracker mProcessCpuTracker = new ProcessCpuTracker(
            MONITOR_THREAD_CPU_USAGE);
    final AtomicLong mLastCpuTime = new AtomicLong(0);
    final AtomicBoolean mProcessCpuMutexFree = new AtomicBoolean(true);
    final CountDownLatch mProcessCpuInitLatch = new CountDownLatch(1);

    long mLastWriteTime = 0;

    /** Set to true after the system has finished booting. */
    volatile boolean mBooted = false;

    /**
     * Current boot phase.
     */
    int mBootPhase;

    @VisibleForTesting
    public WindowManagerService mWindowManager;
    @VisibleForTesting
    public ActivityTaskManagerService mActivityTaskManager;
    @VisibleForTesting
    public ActivityTaskManagerInternal mAtmInternal;
    @VisibleForTesting
    public UriGrantsManagerInternal mUgmInternal;
    final ActivityThread mSystemThread;

    private final class AppDeathRecipient implements IBinder.DeathRecipient {
        final ProcessRecord mApp;
        final int mPid;
        final IApplicationThread mAppThread;

        AppDeathRecipient(ProcessRecord app, int pid,
                IApplicationThread thread) {
            if (DEBUG_ALL) Slog.v(
                TAG, "New death recipient " + this
                 + " for thread " + thread.asBinder());
            mApp = app;
            mPid = pid;
            mAppThread = thread;
        }

        @Override
        public void binderDied() {
            if (DEBUG_ALL) Slog.v(
                TAG, "Death received in " + this
                + " for thread " + mAppThread.asBinder());
            synchronized(ActivityManagerService.this) {
                appDiedLocked(mApp, mPid, mAppThread, true);
            }
        }
    }

    static final int SHOW_ERROR_UI_MSG = 1;
    static final int SHOW_NOT_RESPONDING_UI_MSG = 2;
    static final int GC_BACKGROUND_PROCESSES_MSG = 5;
    static final int WAIT_FOR_DEBUGGER_UI_MSG = 6;
    static final int SERVICE_TIMEOUT_MSG = 12;
    static final int UPDATE_TIME_ZONE = 13;
    static final int PROC_START_TIMEOUT_MSG = 20;
    static final int KILL_APPLICATION_MSG = 22;
    static final int SHOW_STRICT_MODE_VIOLATION_UI_MSG = 26;
    static final int CHECK_EXCESSIVE_POWER_USE_MSG = 27;
    static final int CLEAR_DNS_CACHE_MSG = 28;
    static final int UPDATE_HTTP_PROXY_MSG = 29;
    static final int DISPATCH_PROCESSES_CHANGED_UI_MSG = 31;
    static final int DISPATCH_PROCESS_DIED_UI_MSG = 32;
    static final int REPORT_MEM_USAGE_MSG = 33;
    static final int UPDATE_TIME_PREFERENCE_MSG = 41;
    static final int NOTIFY_CLEARTEXT_NETWORK_MSG = 49;
    static final int POST_DUMP_HEAP_NOTIFICATION_MSG = 50;
    static final int DELETE_DUMPHEAP_MSG = 51;
    static final int DISPATCH_UIDS_CHANGED_UI_MSG = 53;
    static final int SHUTDOWN_UI_AUTOMATION_CONNECTION_MSG = 56;
    static final int CONTENT_PROVIDER_PUBLISH_TIMEOUT_MSG = 57;
    static final int IDLE_UIDS_MSG = 58;
    static final int HANDLE_TRUST_STORAGE_UPDATE_MSG = 63;
    static final int SERVICE_FOREGROUND_TIMEOUT_MSG = 66;
    static final int PUSH_TEMP_WHITELIST_UI_MSG = 68;
    static final int SERVICE_FOREGROUND_CRASH_MSG = 69;
    static final int DISPATCH_OOM_ADJ_OBSERVER_MSG = 70;
    static final int KILL_APP_ZYGOTE_MSG = 71;

    static final int FIRST_BROADCAST_QUEUE_MSG = 200;

    static final String SERVICE_RECORD_KEY = "servicerecord";

    long mLastMemUsageReportTime = 0;

    /**
     * Flag whether the current user is a "monkey", i.e. whether
     * the UI is driven by a UI automation tool.
     */
    private boolean mUserIsMonkey;

    @VisibleForTesting
    public final ServiceThread mHandlerThread;
    final MainHandler mHandler;
    final Handler mUiHandler;
    final ServiceThread mProcStartHandlerThread;
    final Handler mProcStartHandler;

    final ActivityManagerConstants mConstants;

    // Encapsulates the global setting "hidden_api_blacklist_exemptions"
    final HiddenApiSettings mHiddenApiBlacklist;

    PackageManagerInternal mPackageManagerInt;

    /**
     * Whether to force background check on all apps (for battery saver) or not.
     */
    boolean mForceBackgroundCheck;

    private static String sTheRealBuildSerial = Build.UNKNOWN;

    private ParcelFileDescriptor[] mLifeMonitorFds;

    /**
     * Used to notify activity lifecycle events.
     */
    @Nullable ContentCaptureManagerInternal mContentCaptureService;

    final class UiHandler extends Handler {
        public UiHandler() {
            super(com.android.server.UiThread.get().getLooper(), null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case SHOW_ERROR_UI_MSG: {
                mAppErrors.handleShowAppErrorUi(msg);
                ensureBootCompleted();
            } break;
            case SHOW_NOT_RESPONDING_UI_MSG: {
                mAppErrors.handleShowAnrUi(msg);
                ensureBootCompleted();
            } break;
            case SHOW_STRICT_MODE_VIOLATION_UI_MSG: {
                HashMap<String, Object> data = (HashMap<String, Object>) msg.obj;
                synchronized (ActivityManagerService.this) {
                    ProcessRecord proc = (ProcessRecord) data.get("app");
                    if (proc == null) {
                        Slog.e(TAG, "App not found when showing strict mode dialog.");
                        break;
                    }
                    if (proc.crashDialog != null) {
                        Slog.e(TAG, "App already has strict mode dialog: " + proc);
                        return;
                    }
                    AppErrorResult res = (AppErrorResult) data.get("result");
                    if (mAtmInternal.showStrictModeViolationDialog()) {
                        Dialog d = new StrictModeViolationDialog(mUiContext,
                                ActivityManagerService.this, res, proc);
                        d.show();
                        proc.crashDialog = d;
                    } else {
                        // The device is asleep, so just pretend that the user
                        // saw a crash dialog and hit "force quit".
                        res.set(0);
                    }
                }
                ensureBootCompleted();
            } break;
            case WAIT_FOR_DEBUGGER_UI_MSG: {
                synchronized (ActivityManagerService.this) {
                    ProcessRecord app = (ProcessRecord)msg.obj;
                    if (msg.arg1 != 0) {
                        if (!app.waitedForDebugger) {
                            Dialog d = new AppWaitingForDebuggerDialog(
                                    ActivityManagerService.this,
                                    mUiContext, app);
                            app.waitDialog = d;
                            app.waitedForDebugger = true;
                            d.show();
                        }
                    } else {
                        if (app.waitDialog != null) {
                            app.waitDialog.dismiss();
                            app.waitDialog = null;
                        }
                    }
                }
            } break;
            case DISPATCH_PROCESSES_CHANGED_UI_MSG: {
                dispatchProcessesChanged();
                break;
            }
            case DISPATCH_PROCESS_DIED_UI_MSG: {
                final int pid = msg.arg1;
                final int uid = msg.arg2;
                dispatchProcessDied(pid, uid);
                break;
            }
            case DISPATCH_UIDS_CHANGED_UI_MSG: {
                if (false) { // DO NOT SUBMIT WITH TRUE
                    maybeTriggerWatchdog();
                }
                dispatchUidsChanged();
            } break;
            case DISPATCH_OOM_ADJ_OBSERVER_MSG: {
                dispatchOomAdjObserver((String)msg.obj);
            } break;
            case PUSH_TEMP_WHITELIST_UI_MSG: {
                pushTempWhitelist();
            } break;
            }
        }
    }

    final class MainHandler extends Handler {
        public MainHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case GC_BACKGROUND_PROCESSES_MSG: {
                synchronized (ActivityManagerService.this) {
                    performAppGcsIfAppropriateLocked();
                }
            } break;
            case SERVICE_TIMEOUT_MSG: {
                mServices.serviceTimeout((ProcessRecord)msg.obj);
            } break;
            case SERVICE_FOREGROUND_TIMEOUT_MSG: {
                mServices.serviceForegroundTimeout((ServiceRecord)msg.obj);
            } break;
            case SERVICE_FOREGROUND_CRASH_MSG: {
                mServices.serviceForegroundCrash(
                    (ProcessRecord) msg.obj, msg.getData().getCharSequence(SERVICE_RECORD_KEY));
            } break;
            case UPDATE_TIME_ZONE: {
                synchronized (ActivityManagerService.this) {
                    for (int i = mProcessList.mLruProcesses.size() - 1; i >= 0; i--) {
                        ProcessRecord r = mProcessList.mLruProcesses.get(i);
                        if (r.thread != null) {
                            try {
                                r.thread.updateTimeZone();
                            } catch (RemoteException ex) {
                                Slog.w(TAG, "Failed to update time zone for: " + r.info.processName);
                            }
                        }
                    }
                }
            } break;
            case CLEAR_DNS_CACHE_MSG: {
                synchronized (ActivityManagerService.this) {
                    mProcessList.clearAllDnsCacheLocked();
                }
            } break;
            case UPDATE_HTTP_PROXY_MSG: {
                synchronized (ActivityManagerService.this) {
                    mProcessList.setAllHttpProxyLocked();
                }
            } break;
            case PROC_START_TIMEOUT_MSG: {
                ProcessRecord app = (ProcessRecord)msg.obj;
                synchronized (ActivityManagerService.this) {
                    processStartTimedOutLocked(app);
                }
            } break;
            case CONTENT_PROVIDER_PUBLISH_TIMEOUT_MSG: {
                ProcessRecord app = (ProcessRecord)msg.obj;
                synchronized (ActivityManagerService.this) {
                    processContentProviderPublishTimedOutLocked(app);
                }
            } break;
            case KILL_APPLICATION_MSG: {
                synchronized (ActivityManagerService.this) {
                    final int appId = msg.arg1;
                    final int userId = msg.arg2;
                    Bundle bundle = (Bundle)msg.obj;
                    String pkg = bundle.getString("pkg");
                    String reason = bundle.getString("reason");
                    forceStopPackageLocked(pkg, appId, false, false, true, false,
                            false, userId, reason);
                }
            } break;
                case KILL_APP_ZYGOTE_MSG: {
                    synchronized (ActivityManagerService.this) {
                        final AppZygote appZygote = (AppZygote) msg.obj;
                        mProcessList.killAppZygoteIfNeededLocked(appZygote);
                    }
                } break;
            case CHECK_EXCESSIVE_POWER_USE_MSG: {
                synchronized (ActivityManagerService.this) {
                    checkExcessivePowerUsageLocked();
                    removeMessages(CHECK_EXCESSIVE_POWER_USE_MSG);
                    Message nmsg = obtainMessage(CHECK_EXCESSIVE_POWER_USE_MSG);
                    sendMessageDelayed(nmsg, mConstants.POWER_CHECK_INTERVAL);
                }
            } break;
            case REPORT_MEM_USAGE_MSG: {
                final ArrayList<ProcessMemInfo> memInfos = (ArrayList<ProcessMemInfo>)msg.obj;
                Thread thread = new Thread() {
                    @Override public void run() {
                        reportMemUsage(memInfos);
                    }
                };
                thread.start();
                break;
            }
            case UPDATE_TIME_PREFERENCE_MSG: {
                // The user's time format preference might have changed.
                // For convenience we re-use the Intent extra values.
                synchronized (ActivityManagerService.this) {
                    mProcessList.updateAllTimePrefsLocked(msg.arg1);
                }
                break;
            }
            case NOTIFY_CLEARTEXT_NETWORK_MSG: {
                final int uid = msg.arg1;
                final byte[] firstPacket = (byte[]) msg.obj;

                synchronized (mPidsSelfLocked) {
                    for (int i = 0; i < mPidsSelfLocked.size(); i++) {
                        final ProcessRecord p = mPidsSelfLocked.valueAt(i);
                        if (p.uid == uid && p.thread != null) {
                            try {
                                p.thread.notifyCleartextNetwork(firstPacket);
                            } catch (RemoteException ignored) {
                            }
                        }
                    }
                }
                break;
            }
            case POST_DUMP_HEAP_NOTIFICATION_MSG: {
                final String procName;
                final int uid;
                final long memLimit;
                final String reportPackage;
                synchronized (ActivityManagerService.this) {
                    procName = mMemWatchDumpProcName;
                    uid = mMemWatchDumpUid;
                    Pair<Long, String> val = mMemWatchProcesses.get(procName, uid);
                    if (val == null) {
                        val = mMemWatchProcesses.get(procName, 0);
                    }
                    if (val != null) {
                        memLimit = val.first;
                        reportPackage = val.second;
                    } else {
                        memLimit = 0;
                        reportPackage = null;
                    }
                }
                if (procName == null) {
                    return;
                }

                if (DEBUG_PSS) Slog.d(TAG_PSS,
                        "Showing dump heap notification from " + procName + "/" + uid);

                INotificationManager inm = NotificationManager.getService();
                if (inm == null) {
                    return;
                }

                String text = mContext.getString(R.string.dump_heap_notification, procName);


                Intent deleteIntent = new Intent();
                deleteIntent.setAction(DumpHeapActivity.ACTION_DELETE_DUMPHEAP);
                Intent intent = new Intent();
                intent.setClassName("android", DumpHeapActivity.class.getName());
                intent.putExtra(DumpHeapActivity.KEY_PROCESS, procName);
                intent.putExtra(DumpHeapActivity.KEY_SIZE, memLimit);
                if (reportPackage != null) {
                    intent.putExtra(DumpHeapActivity.KEY_DIRECT_LAUNCH, reportPackage);
                }
                int userId = UserHandle.getUserId(uid);
                Notification notification =
                        new Notification.Builder(mContext, SystemNotificationChannels.DEVELOPER)
                        .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                        .setWhen(0)
                        .setOngoing(true)
                        .setAutoCancel(true)
                        .setTicker(text)
                        .setColor(mContext.getColor(
                                com.android.internal.R.color.system_notification_accent_color))
                        .setContentTitle(text)
                        .setContentText(
                                mContext.getText(R.string.dump_heap_notification_detail))
                        .setContentIntent(PendingIntent.getActivityAsUser(mContext, 0,
                                intent, PendingIntent.FLAG_CANCEL_CURRENT, null,
                                new UserHandle(userId)))
                        .setDeleteIntent(PendingIntent.getBroadcastAsUser(mContext, 0,
                                deleteIntent, 0, UserHandle.SYSTEM))
                        .build();

                try {
                    inm.enqueueNotificationWithTag("android", "android", null,
                            SystemMessage.NOTE_DUMP_HEAP_NOTIFICATION,
                            notification, userId);
                } catch (RuntimeException e) {
                    Slog.w(ActivityManagerService.TAG,
                            "Error showing notification for dump heap", e);
                } catch (RemoteException e) {
                }
            } break;
            case DELETE_DUMPHEAP_MSG: {
                revokeUriPermission(ActivityThread.currentActivityThread().getApplicationThread(),
                        null, DumpHeapActivity.JAVA_URI,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        UserHandle.myUserId());
                synchronized (ActivityManagerService.this) {
                    mMemWatchDumpFile = null;
                    mMemWatchDumpProcName = null;
                    mMemWatchDumpPid = -1;
                    mMemWatchDumpUid = -1;
                }
            } break;
            case SHUTDOWN_UI_AUTOMATION_CONNECTION_MSG: {
                IUiAutomationConnection connection = (IUiAutomationConnection) msg.obj;
                try {
                    connection.shutdown();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Error shutting down UiAutomationConnection");
                }
                // Only a UiAutomation can set this flag and now that
                // it is finished we make sure it is reset to its default.
                mUserIsMonkey = false;
            } break;
            case IDLE_UIDS_MSG: {
                idleUids();
            } break;
            case HANDLE_TRUST_STORAGE_UPDATE_MSG: {
                synchronized (ActivityManagerService.this) {
                    mProcessList.handleAllTrustStorageUpdateLocked();
                }
            } break;
            }
        }
    };

    static final int COLLECT_PSS_BG_MSG = 1;

    final Handler mBgHandler = new Handler(BackgroundThread.getHandler().getLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case COLLECT_PSS_BG_MSG: {
                long start = SystemClock.uptimeMillis();
                MemInfoReader memInfo = null;
                synchronized (ActivityManagerService.this) {
                    if (mFullPssPending) {
                        mFullPssPending = false;
                        memInfo = new MemInfoReader();
                    }
                }
                if (memInfo != null) {
                    updateCpuStatsNow();
                    long nativeTotalPss = 0;
                    final List<ProcessCpuTracker.Stats> stats;
                    synchronized (mProcessCpuTracker) {
                        stats = mProcessCpuTracker.getStats( (st)-> {
                            return st.vsize > 0 && st.uid < FIRST_APPLICATION_UID;
                        });
                    }
                    final int N = stats.size();
                    for (int j = 0; j < N; j++) {
                        synchronized (mPidsSelfLocked) {
                            if (mPidsSelfLocked.indexOfKey(stats.get(j).pid) >= 0) {
                                // This is one of our own processes; skip it.
                                continue;
                            }
                        }
                        nativeTotalPss += Debug.getPss(stats.get(j).pid, null, null);
                    }
                    memInfo.readMemInfo();
                    synchronized (ActivityManagerService.this) {
                        if (DEBUG_PSS) Slog.d(TAG_PSS, "Collected native and kernel memory in "
                                + (SystemClock.uptimeMillis()-start) + "ms");
                        final long cachedKb = memInfo.getCachedSizeKb();
                        final long freeKb = memInfo.getFreeSizeKb();
                        final long zramKb = memInfo.getZramTotalSizeKb();
                        final long kernelKb = memInfo.getKernelUsedSizeKb();
                        EventLogTags.writeAmMeminfo(cachedKb*1024, freeKb*1024, zramKb*1024,
                                kernelKb*1024, nativeTotalPss*1024);
                        mProcessStats.addSysMemUsageLocked(cachedKb, freeKb, zramKb, kernelKb,
                                nativeTotalPss);
                    }
                }

                int num = 0;
                long[] tmp = new long[3];
                do {
                    ProcessRecord proc;
                    int procState;
                    int statType;
                    int pid = -1;
                    long lastPssTime;
                    synchronized (ActivityManagerService.this) {
                        if (mPendingPssProcesses.size() <= 0) {
                            if (mTestPssMode || DEBUG_PSS) Slog.d(TAG_PSS,
                                    "Collected pss of " + num + " processes in "
                                    + (SystemClock.uptimeMillis() - start) + "ms");
                            mPendingPssProcesses.clear();
                            return;
                        }
                        proc = mPendingPssProcesses.remove(0);
                        procState = proc.pssProcState;
                        statType = proc.pssStatType;
                        lastPssTime = proc.lastPssTime;
                        long now = SystemClock.uptimeMillis();
                        if (proc.thread != null && procState == proc.setProcState
                                && (lastPssTime+ProcessList.PSS_SAFE_TIME_FROM_STATE_CHANGE)
                                        < now) {
                            pid = proc.pid;
                        } else {
                            ProcessList.abortNextPssTime(proc.procStateMemTracker);
                            if (DEBUG_PSS) Slog.d(TAG_PSS, "Skipped pss collection of " + pid +
                                    ": still need " +
                                    (lastPssTime+ProcessList.PSS_SAFE_TIME_FROM_STATE_CHANGE-now) +
                                    "ms until safe");
                            proc = null;
                            pid = 0;
                        }
                    }
                    if (proc != null) {
                        long startTime = SystemClock.currentThreadTimeMillis();
                        long pss = Debug.getPss(pid, tmp, null);
                        long endTime = SystemClock.currentThreadTimeMillis();
                        synchronized (ActivityManagerService.this) {
                            if (pss != 0 && proc.thread != null && proc.setProcState == procState
                                    && proc.pid == pid && proc.lastPssTime == lastPssTime) {
                                num++;
                                ProcessList.commitNextPssTime(proc.procStateMemTracker);
                                recordPssSampleLocked(proc, procState, pss, tmp[0], tmp[1], tmp[2],
                                        statType, endTime-startTime, SystemClock.uptimeMillis());
                            } else {
                                ProcessList.abortNextPssTime(proc.procStateMemTracker);
                                if (DEBUG_PSS) Slog.d(TAG_PSS, "Skipped pss collection of " + pid +
                                        ": " + (proc.thread == null ? "NO_THREAD " : "") +
                                        (proc.pid != pid ? "PID_CHANGED " : "") +
                                        " initState=" + procState + " curState=" +
                                        proc.setProcState + " " +
                                        (proc.lastPssTime != lastPssTime ? "TIME_CHANGED" : ""));
                            }
                        }
                    }
                } while (true);
            }
            }
        }
    };

    public void setSystemProcess() {
        try {
            ServiceManager.addService(Context.ACTIVITY_SERVICE, this, /* allowIsolated= */ true,
                    DUMP_FLAG_PRIORITY_CRITICAL | DUMP_FLAG_PRIORITY_NORMAL | DUMP_FLAG_PROTO);
            ServiceManager.addService(ProcessStats.SERVICE_NAME, mProcessStats);
            ServiceManager.addService("meminfo", new MemBinder(this), /* allowIsolated= */ false,
                    DUMP_FLAG_PRIORITY_HIGH);
            ServiceManager.addService("gfxinfo", new GraphicsBinder(this));
            ServiceManager.addService("dbinfo", new DbBinder(this));
            if (MONITOR_CPU_USAGE) {
                ServiceManager.addService("cpuinfo", new CpuBinder(this),
                        /* allowIsolated= */ false, DUMP_FLAG_PRIORITY_CRITICAL);
            }
            ServiceManager.addService("permission", new PermissionController(this));
            ServiceManager.addService("processinfo", new ProcessInfoService(this));

            ApplicationInfo info = mContext.getPackageManager().getApplicationInfo(
                    "android", STOCK_PM_FLAGS | MATCH_SYSTEM_ONLY);
            mSystemThread.installSystemApplicationInfo(info, getClass().getClassLoader());

            synchronized (this) {
                ProcessRecord app = mProcessList.newProcessRecordLocked(info, info.processName,
                        false,
                        0,
                        false);
                app.setPersistent(true);
                app.pid = MY_PID;
                app.getWindowProcessController().setPid(MY_PID);
                app.maxAdj = ProcessList.SYSTEM_ADJ;
                app.makeActive(mSystemThread.getApplicationThread(), mProcessStats);
                mPidsSelfLocked.put(app.pid, app);
                mProcessList.updateLruProcessLocked(app, false, null);
                updateOomAdjLocked();
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(
                    "Unable to find android system package", e);
        }

        // Start watching app ops after we and the package manager are up and running.
        mAppOpsService.startWatchingMode(AppOpsManager.OP_RUN_IN_BACKGROUND, null,
                new IAppOpsCallback.Stub() {
                    @Override public void opChanged(int op, int uid, String packageName) {
                        if (op == AppOpsManager.OP_RUN_IN_BACKGROUND && packageName != null) {
                            if (mAppOpsService.checkOperation(op, uid, packageName)
                                    != AppOpsManager.MODE_ALLOWED) {
                                runInBackgroundDisabled(uid);
                            }
                        }
                    }
                });
    }

    public void setWindowManager(WindowManagerService wm) {
        synchronized (this) {
            mWindowManager = wm;
            mActivityTaskManager.setWindowManager(wm);
        }
    }

    public void setUsageStatsManager(UsageStatsManagerInternal usageStatsManager) {
        mUsageStatsService = usageStatsManager;
        mActivityTaskManager.setUsageStatsManager(usageStatsManager);
    }

    /**
     * Sets the internal content capture manager service.
     *
     * <p>It's called when {@code SystemServer} starts, so we don't really need to acquire the lock.
     */
    public void setContentCaptureManager(
            @Nullable ContentCaptureManagerInternal contentCaptureManager) {
        mContentCaptureService = contentCaptureManager;
    }

    public void startObservingNativeCrashes() {
        final NativeCrashListener ncl = new NativeCrashListener(this);
        ncl.start();
    }

    public IAppOpsService getAppOpsService() {
        return mAppOpsService;
    }

    static class MemBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        private final PriorityDump.PriorityDumper mPriorityDumper =
                new PriorityDump.PriorityDumper() {
            @Override
            public void dumpHigh(FileDescriptor fd, PrintWriter pw, String[] args,
                    boolean asProto) {
                dump(fd, pw, new String[] {"-a"}, asProto);
            }

            @Override
            public void dump(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
                mActivityManagerService.dumpApplicationMemoryUsage(
                        fd, pw, "  ", args, false, null, asProto);
            }
        };

        MemBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(mActivityManagerService.mContext,
                    "meminfo", pw)) return;
            PriorityDump.dump(mPriorityDumper, fd, pw, args);
        }
    }

    static class GraphicsBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        GraphicsBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(mActivityManagerService.mContext,
                    "gfxinfo", pw)) return;
            mActivityManagerService.dumpGraphicsHardwareUsage(fd, pw, args);
        }
    }

    static class DbBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        DbBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(mActivityManagerService.mContext,
                    "dbinfo", pw)) return;
            mActivityManagerService.dumpDbInfo(fd, pw, args);
        }
    }

    static class CpuBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        private final PriorityDump.PriorityDumper mPriorityDumper =
                new PriorityDump.PriorityDumper() {
            @Override
            public void dumpCritical(FileDescriptor fd, PrintWriter pw, String[] args,
                    boolean asProto) {
                if (asProto) return;
                if (!DumpUtils.checkDumpAndUsageStatsPermission(mActivityManagerService.mContext,
                        "cpuinfo", pw)) return;
                synchronized (mActivityManagerService.mProcessCpuTracker) {
                    pw.print(mActivityManagerService.mProcessCpuTracker.printCurrentLoad());
                    pw.print(mActivityManagerService.mProcessCpuTracker.printCurrentState(
                            SystemClock.uptimeMillis()));
                }
            }
        };

        CpuBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            PriorityDump.dump(mPriorityDumper, fd, pw, args);
        }
    }

    public static final class Lifecycle extends SystemService {
        private final ActivityManagerService mService;
        private static ActivityTaskManagerService sAtm;

        public Lifecycle(Context context) {
            super(context);
            mService = new ActivityManagerService(context, sAtm);
        }

        public static ActivityManagerService startService(
                SystemServiceManager ssm, ActivityTaskManagerService atm) {
            sAtm = atm;
            return ssm.startService(ActivityManagerService.Lifecycle.class).getService();
        }

        @Override
        public void onStart() {
            mService.start();
        }

        @Override
        public void onBootPhase(int phase) {
            mService.mBootPhase = phase;
            if (phase == PHASE_SYSTEM_SERVICES_READY) {
                mService.mBatteryStatsService.systemServicesReady();
                mService.mServices.systemServicesReady();
            } else if (phase == PHASE_ACTIVITY_MANAGER_READY) {
                mService.startBroadcastObservers();
            }
        }

        @Override
        public void onCleanupUser(int userId) {
            mService.mBatteryStatsService.onCleanupUser(userId);
        }

        public ActivityManagerService getService() {
            return mService;
        }
    }

    /**
     * Encapsulates global settings related to hidden API enforcement behaviour, including tracking
     * the latest value via a content observer.
     */
    static class HiddenApiSettings extends ContentObserver {

        private final Context mContext;
        private boolean mBlacklistDisabled;
        private String mExemptionsStr;
        private List<String> mExemptions = Collections.emptyList();
        private int mLogSampleRate = -1;
        private int mStatslogSampleRate = -1;
        @HiddenApiEnforcementPolicy private int mPolicy = HIDDEN_API_ENFORCEMENT_DEFAULT;

        public HiddenApiSettings(Handler handler, Context context) {
            super(handler);
            mContext = context;
        }

        public void registerObserver() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.HIDDEN_API_BLACKLIST_EXEMPTIONS),
                    false,
                    this);
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.HIDDEN_API_ACCESS_LOG_SAMPLING_RATE),
                    false,
                    this);
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(
                        Settings.Global.HIDDEN_API_ACCESS_STATSLOG_SAMPLING_RATE),
                    false,
                    this);
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.HIDDEN_API_POLICY),
                    false,
                    this);
            update();
        }

        private void update() {
            String exemptions = Settings.Global.getString(mContext.getContentResolver(),
                    Settings.Global.HIDDEN_API_BLACKLIST_EXEMPTIONS);
            if (!TextUtils.equals(exemptions, mExemptionsStr)) {
                mExemptionsStr = exemptions;
                if ("*".equals(exemptions)) {
                    mBlacklistDisabled = true;
                    mExemptions = Collections.emptyList();
                } else {
                    mBlacklistDisabled = false;
                    mExemptions = TextUtils.isEmpty(exemptions)
                            ? Collections.emptyList()
                            : Arrays.asList(exemptions.split(","));
                }
                if (!ZYGOTE_PROCESS.setApiBlacklistExemptions(mExemptions)) {
                  Slog.e(TAG, "Failed to set API blacklist exemptions!");
                  // leave mExemptionsStr as is, so we don't try to send the same list again.
                  mExemptions = Collections.emptyList();
                }
            }
            int logSampleRate = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.HIDDEN_API_ACCESS_LOG_SAMPLING_RATE, 0x200);
            if (logSampleRate < 0 || logSampleRate > 0x10000) {
                logSampleRate = -1;
            }
            if (logSampleRate != -1 && logSampleRate != mLogSampleRate) {
                mLogSampleRate = logSampleRate;
                ZYGOTE_PROCESS.setHiddenApiAccessLogSampleRate(mLogSampleRate);
            }
            int statslogSampleRate = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.HIDDEN_API_ACCESS_STATSLOG_SAMPLING_RATE, 0);
            if (statslogSampleRate < 0 || statslogSampleRate > 0x10000) {
                statslogSampleRate = -1;
            }
            if (statslogSampleRate != -1 && statslogSampleRate != mStatslogSampleRate) {
                mStatslogSampleRate = statslogSampleRate;
                ZYGOTE_PROCESS.setHiddenApiAccessStatslogSampleRate(mStatslogSampleRate);
            }
            mPolicy = getValidEnforcementPolicy(Settings.Global.HIDDEN_API_POLICY);
        }

        private @HiddenApiEnforcementPolicy int getValidEnforcementPolicy(String settingsKey) {
            int policy = Settings.Global.getInt(mContext.getContentResolver(), settingsKey,
                    ApplicationInfo.HIDDEN_API_ENFORCEMENT_DEFAULT);
            if (ApplicationInfo.isValidHiddenApiEnforcementPolicy(policy)) {
                return policy;
            } else {
                return ApplicationInfo.HIDDEN_API_ENFORCEMENT_DEFAULT;
            }
        }

        boolean isDisabled() {
            return mBlacklistDisabled;
        }

        @HiddenApiEnforcementPolicy int getPolicy() {
            return mPolicy;
        }

        public void onChange(boolean selfChange) {
            update();
        }
    }

    @VisibleForTesting
    public ActivityManagerService(Injector injector) {
        this(injector, null /* handlerThread */);
    }

    /**
     * Provides the basic functionality for activity task related tests when a handler thread is
     * given to initialize the dependency members.
     */
    @VisibleForTesting
    public ActivityManagerService(Injector injector, ServiceThread handlerThread) {
        final boolean hasHandlerThread = handlerThread != null;
        mInjector = injector;
        mContext = mInjector.getContext();
        mUiContext = null;
        mAppErrors = null;
        mPackageWatchdog = null;
        mAppOpsService = mInjector.getAppOpsService(null /* file */, null /* handler */);
        mBatteryStatsService = null;
        mHandler = hasHandlerThread ? new MainHandler(handlerThread.getLooper()) : null;
        mHandlerThread = handlerThread;
        mConstants = hasHandlerThread ? new ActivityManagerConstants(this, mHandler) : null;
        final ActiveUids activeUids = new ActiveUids(this, false /* postChangesToAtm */);
        mProcessList.init(this, activeUids);
        mOomAdjuster = new OomAdjuster(this, mProcessList, activeUids);

        mIntentFirewall = hasHandlerThread
                ? new IntentFirewall(new IntentFirewallInterface(), mHandler) : null;
        mProcessCpuThread = null;
        mProcessStats = null;
        mProviderMap = null;
        // For the usage of {@link ActiveServices#cleanUpServices} that may be invoked from
        // {@link ActivityStackSupervisor#cleanUpRemovedTaskLocked}.
        mServices = hasHandlerThread ? new ActiveServices(this) : null;
        mSystemThread = null;
        mUiHandler = injector.getUiHandler(null /* service */);
        mUserController = hasHandlerThread ? new UserController(this) : null;
        mPendingIntentController = hasHandlerThread
                ? new PendingIntentController(handlerThread.getLooper(), mUserController) : null;
        mProcStartHandlerThread = null;
        mProcStartHandler = null;
        mHiddenApiBlacklist = null;
        mFactoryTest = FACTORY_TEST_OFF;
    }

    // Note: This method is invoked on the main thread but may need to attach various
    // handlers to other threads.  So take care to be explicit about the looper.
    public ActivityManagerService(Context systemContext, ActivityTaskManagerService atm) {
        LockGuard.installLock(this, LockGuard.INDEX_ACTIVITY);
        mInjector = new Injector();
        mContext = systemContext;

        mFactoryTest = FactoryTest.getMode();
        mSystemThread = ActivityThread.currentActivityThread();
        mUiContext = mSystemThread.getSystemUiContext();

        Slog.i(TAG, "Memory class: " + ActivityManager.staticGetMemoryClass());

        mHandlerThread = new ServiceThread(TAG,
                THREAD_PRIORITY_FOREGROUND, false /*allowIo*/);
        mHandlerThread.start();
        mHandler = new MainHandler(mHandlerThread.getLooper());
        mUiHandler = mInjector.getUiHandler(this);

        mProcStartHandlerThread = new ServiceThread(TAG + ":procStart",
                THREAD_PRIORITY_FOREGROUND, false /* allowIo */);
        mProcStartHandlerThread.start();
        mProcStartHandler = new Handler(mProcStartHandlerThread.getLooper());

        mConstants = new ActivityManagerConstants(this, mHandler);
        final ActiveUids activeUids = new ActiveUids(this, true /* postChangesToAtm */);
        mProcessList.init(this, activeUids);
        mOomAdjuster = new OomAdjuster(this, mProcessList, activeUids);

        // Broadcast policy parameters
        final BroadcastConstants foreConstants = new BroadcastConstants(
                Settings.Global.BROADCAST_FG_CONSTANTS);
        foreConstants.TIMEOUT = BROADCAST_FG_TIMEOUT;

        final BroadcastConstants backConstants = new BroadcastConstants(
                Settings.Global.BROADCAST_BG_CONSTANTS);
        backConstants.TIMEOUT = BROADCAST_BG_TIMEOUT;

        final BroadcastConstants offloadConstants = new BroadcastConstants(
                Settings.Global.BROADCAST_OFFLOAD_CONSTANTS);
        offloadConstants.TIMEOUT = BROADCAST_BG_TIMEOUT;
        // by default, no "slow" policy in this queue
        offloadConstants.SLOW_TIME = Integer.MAX_VALUE;

        mEnableOffloadQueue = SystemProperties.getBoolean(
                "persist.device_config.activity_manager_native_boot.offload_queue_enabled", false);

        mFgBroadcastQueue = new BroadcastQueue(this, mHandler,
                "foreground", foreConstants, false);
        mBgBroadcastQueue = new BroadcastQueue(this, mHandler,
                "background", backConstants, true);
        mOffloadBroadcastQueue = new BroadcastQueue(this, mHandler,
                "offload", offloadConstants, true);
        mBroadcastQueues[0] = mFgBroadcastQueue;
        mBroadcastQueues[1] = mBgBroadcastQueue;
        mBroadcastQueues[2] = mOffloadBroadcastQueue;

        mServices = new ActiveServices(this);
        mProviderMap = new ProviderMap(this);
        mPackageWatchdog = PackageWatchdog.getInstance(mUiContext);
        mAppErrors = new AppErrors(mUiContext, this, mPackageWatchdog);

        final File systemDir = SystemServiceManager.ensureSystemDir();

        // TODO: Move creation of battery stats service outside of activity manager service.
        mBatteryStatsService = new BatteryStatsService(systemContext, systemDir,
                BackgroundThread.get().getHandler());
        mBatteryStatsService.getActiveStatistics().readLocked();
        mBatteryStatsService.scheduleWriteToDisk();
        mOnBattery = DEBUG_POWER ? true
                : mBatteryStatsService.getActiveStatistics().getIsOnBattery();
        mBatteryStatsService.getActiveStatistics().setCallback(this);
        mOomAdjProfiler.batteryPowerChanged(mOnBattery);

        mProcessStats = new ProcessStatsService(this, new File(systemDir, "procstats"));

        mAppOpsService = mInjector.getAppOpsService(new File(systemDir, "appops.xml"), mHandler);

        mUgmInternal = LocalServices.getService(UriGrantsManagerInternal.class);

        mUserController = new UserController(this);

        mPendingIntentController = new PendingIntentController(
                mHandlerThread.getLooper(), mUserController);

        if (SystemProperties.getInt("sys.use_fifo_ui", 0) != 0) {
            mUseFifoUiScheduling = true;
        }

        mTrackingAssociations = "1".equals(SystemProperties.get("debug.track-associations"));
        mIntentFirewall = new IntentFirewall(new IntentFirewallInterface(), mHandler);

        mActivityTaskManager = atm;
        mActivityTaskManager.initialize(mIntentFirewall, mPendingIntentController,
                DisplayThread.get().getLooper());
        mAtmInternal = LocalServices.getService(ActivityTaskManagerInternal.class);

        mProcessCpuThread = new Thread("CpuTracker") {
            @Override
            public void run() {
                synchronized (mProcessCpuTracker) {
                    mProcessCpuInitLatch.countDown();
                    mProcessCpuTracker.init();
                }
                while (true) {
                    try {
                        try {
                            synchronized(this) {
                                final long now = SystemClock.uptimeMillis();
                                long nextCpuDelay = (mLastCpuTime.get()+MONITOR_CPU_MAX_TIME)-now;
                                long nextWriteDelay = (mLastWriteTime+BATTERY_STATS_TIME)-now;
                                //Slog.i(TAG, "Cpu delay=" + nextCpuDelay
                                //        + ", write delay=" + nextWriteDelay);
                                if (nextWriteDelay < nextCpuDelay) {
                                    nextCpuDelay = nextWriteDelay;
                                }
                                if (nextCpuDelay > 0) {
                                    mProcessCpuMutexFree.set(true);
                                    this.wait(nextCpuDelay);
                                }
                            }
                        } catch (InterruptedException e) {
                        }
                        updateCpuStatsNow();
                    } catch (Exception e) {
                        Slog.e(TAG, "Unexpected exception collecting process stats", e);
                    }
                }
            }
        };

        mHiddenApiBlacklist = new HiddenApiSettings(mHandler, mContext);

        Watchdog.getInstance().addMonitor(this);
        Watchdog.getInstance().addThread(mHandler);

        // bind background threads to little cores
        // this is expected to fail inside of framework tests because apps can't touch cpusets directly
        // make sure we've already adjusted system_server's internal view of itself first
        updateOomAdjLocked();
        try {
            Process.setThreadGroupAndCpuset(BackgroundThread.get().getThreadId(),
                    Process.THREAD_GROUP_SYSTEM);
            Process.setThreadGroupAndCpuset(
                    mOomAdjuster.mAppCompact.mCompactionThread.getThreadId(),
                    Process.THREAD_GROUP_SYSTEM);
        } catch (Exception e) {
            Slog.w(TAG, "Setting background thread cpuset failed");
        }

    }

    public void setSystemServiceManager(SystemServiceManager mgr) {
        mSystemServiceManager = mgr;
    }

    public void setInstaller(Installer installer) {
        mInstaller = installer;
    }

    private void start() {
        removeAllProcessGroups();
        mProcessCpuThread.start();

        mBatteryStatsService.publish();
        mAppOpsService.publish(mContext);
        Slog.d("AppOps", "AppOpsService published");
        LocalServices.addService(ActivityManagerInternal.class, new LocalService());
        mActivityTaskManager.onActivityManagerInternalAdded();
        mUgmInternal.onActivityManagerInternalAdded();
        mPendingIntentController.onActivityManagerInternalAdded();
        // Wait for the synchronized block started in mProcessCpuThread,
        // so that any other access to mProcessCpuTracker from main thread
        // will be blocked during mProcessCpuTracker initialization.
        try {
            mProcessCpuInitLatch.await();
        } catch (InterruptedException e) {
            Slog.wtf(TAG, "Interrupted wait during start", e);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted wait during start");
        }
    }

    public void initPowerManagement() {
        mActivityTaskManager.onInitPowerManagement();
        mBatteryStatsService.initPowerManagement();
        mLocalPowerManager = LocalServices.getService(PowerManagerInternal.class);
    }

    private ArraySet<String> getBackgroundLaunchBroadcasts() {
        if (mBackgroundLaunchBroadcasts == null) {
            mBackgroundLaunchBroadcasts = SystemConfig.getInstance().getAllowImplicitBroadcasts();
        }
        return mBackgroundLaunchBroadcasts;
    }

    /**
     * Ensures that the given package name has an explicit set of allowed associations.
     * If it does not, give it an empty set.
     */
    void requireAllowedAssociationsLocked(String packageName) {
        ensureAllowedAssociations();
        if (mAllowedAssociations.get(packageName) == null) {
            mAllowedAssociations.put(packageName, new PackageAssociationInfo(packageName,
                    new ArraySet<>(), /* isDebuggable = */ false));
        }
    }

    /**
     * Returns true if the package {@code pkg1} running under user handle {@code uid1} is
     * allowed association with the package {@code pkg2} running under user handle {@code uid2}.
     * <p> If either of the packages are running as  part of the core system, then the
     * association is implicitly allowed.
     */
    boolean validateAssociationAllowedLocked(String pkg1, int uid1, String pkg2, int uid2) {
        ensureAllowedAssociations();
        // Interactions with the system uid are always allowed, since that is the core system
        // that everyone needs to be able to interact with. Also allow reflexive associations
        // within the same uid.
        if (uid1 == uid2 || UserHandle.getAppId(uid1) == SYSTEM_UID
                || UserHandle.getAppId(uid2) == SYSTEM_UID) {
            return true;
        }

        // Check for association on both source and target packages.
        PackageAssociationInfo pai = mAllowedAssociations.get(pkg1);
        if (pai != null && !pai.isPackageAssociationAllowed(pkg2)) {
            return false;
        }
        pai = mAllowedAssociations.get(pkg2);
        if (pai != null && !pai.isPackageAssociationAllowed(pkg1)) {
            return false;
        }
        // If no explicit associations are provided in the manifest, then assume the app is
        // allowed associations with any package.
        return true;
    }

    /** Sets up allowed associations for system prebuilt packages from system config (if needed). */
    private void ensureAllowedAssociations() {
        if (mAllowedAssociations == null) {
            ArrayMap<String, ArraySet<String>> allowedAssociations =
                    SystemConfig.getInstance().getAllowedAssociations();
            mAllowedAssociations = new ArrayMap<>(allowedAssociations.size());
            PackageManagerInternal pm = getPackageManagerInternalLocked();
            for (int i = 0; i < allowedAssociations.size(); i++) {
                final String pkg = allowedAssociations.keyAt(i);
                final ArraySet<String> asc = allowedAssociations.valueAt(i);

                // Query latest debuggable flag from package-manager.
                boolean isDebuggable = false;
                try {
                    ApplicationInfo ai = AppGlobals.getPackageManager()
                            .getApplicationInfo(pkg, MATCH_ALL, 0);
                    if (ai != null) {
                        isDebuggable = (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                    }
                } catch (RemoteException e) {
                    /* ignore */
                }
                mAllowedAssociations.put(pkg, new PackageAssociationInfo(pkg, asc, isDebuggable));
            }
        }
    }

    /** Updates allowed associations for app info (specifically, based on debuggability).  */
    private void updateAssociationForApp(ApplicationInfo appInfo) {
        ensureAllowedAssociations();
        PackageAssociationInfo pai = mAllowedAssociations.get(appInfo.packageName);
        if (pai != null) {
            pai.setDebuggable((appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        }
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == SYSPROPS_TRANSACTION) {
            // We need to tell all apps about the system property change.
            ArrayList<IBinder> procs = new ArrayList<IBinder>();
            synchronized (this) {
                final int NP = mProcessList.mProcessNames.getMap().size();
                for (int ip = 0; ip < NP; ip++) {
                    SparseArray<ProcessRecord> apps =
                            mProcessList.mProcessNames.getMap().valueAt(ip);
                    final int NA = apps.size();
                    for (int ia = 0; ia < NA; ia++) {
                        ProcessRecord app = apps.valueAt(ia);
                        if (app.thread != null) {
                            procs.add(app.thread.asBinder());
                        }
                    }
                }
            }

            int N = procs.size();
            for (int i=0; i<N; i++) {
                Parcel data2 = Parcel.obtain();
                try {
                    procs.get(i).transact(IBinder.SYSPROPS_TRANSACTION, data2, null,
                            Binder.FLAG_ONEWAY);
                } catch (RemoteException e) {
                }
                data2.recycle();
            }
        }
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            // The activity manager only throws certain exceptions intentionally, so let's
            // log all others.
            if (!(e instanceof SecurityException
                    || e instanceof IllegalArgumentException
                    || e instanceof IllegalStateException)) {
                Slog.wtf(TAG, "Activity Manager Crash."
                        + " UID:" + Binder.getCallingUid()
                        + " PID:" + Binder.getCallingPid()
                        + " TRANS:" + code, e);
            }
            throw e;
        }
    }

    void updateCpuStats() {
        final long now = SystemClock.uptimeMillis();
        if (mLastCpuTime.get() >= now - MONITOR_CPU_MIN_TIME) {
            return;
        }
        if (mProcessCpuMutexFree.compareAndSet(true, false)) {
            synchronized (mProcessCpuThread) {
                mProcessCpuThread.notify();
            }
        }
    }

    void updateCpuStatsNow() {
        synchronized (mProcessCpuTracker) {
            mProcessCpuMutexFree.set(false);
            final long now = SystemClock.uptimeMillis();
            boolean haveNewCpuStats = false;

            if (MONITOR_CPU_USAGE &&
                    mLastCpuTime.get() < (now-MONITOR_CPU_MIN_TIME)) {
                mLastCpuTime.set(now);
                mProcessCpuTracker.update();
                if (mProcessCpuTracker.hasGoodLastStats()) {
                    haveNewCpuStats = true;
                    //Slog.i(TAG, mProcessCpu.printCurrentState());
                    //Slog.i(TAG, "Total CPU usage: "
                    //        + mProcessCpu.getTotalCpuPercent() + "%");

                    // Slog the cpu usage if the property is set.
                    if ("true".equals(SystemProperties.get("events.cpu"))) {
                        int user = mProcessCpuTracker.getLastUserTime();
                        int system = mProcessCpuTracker.getLastSystemTime();
                        int iowait = mProcessCpuTracker.getLastIoWaitTime();
                        int irq = mProcessCpuTracker.getLastIrqTime();
                        int softIrq = mProcessCpuTracker.getLastSoftIrqTime();
                        int idle = mProcessCpuTracker.getLastIdleTime();

                        int total = user + system + iowait + irq + softIrq + idle;
                        if (total == 0) total = 1;

                        EventLog.writeEvent(EventLogTags.CPU,
                                ((user+system+iowait+irq+softIrq) * 100) / total,
                                (user * 100) / total,
                                (system * 100) / total,
                                (iowait * 100) / total,
                                (irq * 100) / total,
                                (softIrq * 100) / total);
                    }
                }
            }

            final BatteryStatsImpl bstats = mBatteryStatsService.getActiveStatistics();
            synchronized(bstats) {
                synchronized(mPidsSelfLocked) {
                    if (haveNewCpuStats) {
                        if (bstats.startAddingCpuLocked()) {
                            int totalUTime = 0;
                            int totalSTime = 0;
                            final int N = mProcessCpuTracker.countStats();
                            for (int i=0; i<N; i++) {
                                ProcessCpuTracker.Stats st = mProcessCpuTracker.getStats(i);
                                if (!st.working) {
                                    continue;
                                }
                                ProcessRecord pr = mPidsSelfLocked.get(st.pid);
                                totalUTime += st.rel_utime;
                                totalSTime += st.rel_stime;
                                if (pr != null) {
                                    BatteryStatsImpl.Uid.Proc ps = pr.curProcBatteryStats;
                                    if (ps == null || !ps.isActive()) {
                                        pr.curProcBatteryStats = ps = bstats.getProcessStatsLocked(
                                                pr.info.uid, pr.processName);
                                    }
                                    ps.addCpuTimeLocked(st.rel_utime, st.rel_stime);
                                    pr.curCpuTime += st.rel_utime + st.rel_stime;
                                    if (pr.lastCpuTime == 0) {
                                        pr.lastCpuTime = pr.curCpuTime;
                                    }
                                } else {
                                    BatteryStatsImpl.Uid.Proc ps = st.batteryStats;
                                    if (ps == null || !ps.isActive()) {
                                        st.batteryStats = ps = bstats.getProcessStatsLocked(
                                                bstats.mapUid(st.uid), st.name);
                                    }
                                    ps.addCpuTimeLocked(st.rel_utime, st.rel_stime);
                                }
                            }
                            final int userTime = mProcessCpuTracker.getLastUserTime();
                            final int systemTime = mProcessCpuTracker.getLastSystemTime();
                            final int iowaitTime = mProcessCpuTracker.getLastIoWaitTime();
                            final int irqTime = mProcessCpuTracker.getLastIrqTime();
                            final int softIrqTime = mProcessCpuTracker.getLastSoftIrqTime();
                            final int idleTime = mProcessCpuTracker.getLastIdleTime();
                            bstats.finishAddingCpuLocked(totalUTime, totalSTime, userTime,
                                    systemTime, iowaitTime, irqTime, softIrqTime, idleTime);
                        }
                    }
                }

                if (mLastWriteTime < (now-BATTERY_STATS_TIME)) {
                    mLastWriteTime = now;
                    mBatteryStatsService.scheduleWriteToDisk();
                }
            }
        }
    }

    @Override
    public void batteryNeedsCpuUpdate() {
        updateCpuStatsNow();
    }

    @Override
    public void batteryPowerChanged(boolean onBattery) {
        // When plugging in, update the CPU stats first before changing
        // the plug state.
        updateCpuStatsNow();
        synchronized (this) {
            synchronized(mPidsSelfLocked) {
                mOnBattery = DEBUG_POWER ? true : onBattery;
            }
            mOomAdjProfiler.batteryPowerChanged(onBattery);
        }
    }

    @Override
    public void batteryStatsReset() {
        mOomAdjProfiler.reset();
    }

    @Override
    public void batterySendBroadcast(Intent intent) {
        synchronized (this) {
            broadcastIntentLocked(null, null, intent, null, null, 0, null, null, null,
                    OP_NONE, null, false, false, -1, SYSTEM_UID, Binder.getCallingUid(),
                    Binder.getCallingPid(), UserHandle.USER_ALL);
        }
    }

    /**
     * Initialize the application bind args. These are passed to each
     * process when the bindApplication() IPC is sent to the process. They're
     * lazily setup to make sure the services are running when they're asked for.
     */
    private ArrayMap<String, IBinder> getCommonServicesLocked(boolean isolated) {
        // Isolated processes won't get this optimization, so that we don't
        // violate the rules about which services they have access to.
        if (isolated) {
            if (mIsolatedAppBindArgs == null) {
                mIsolatedAppBindArgs = new ArrayMap<>(1);
                addServiceToMap(mIsolatedAppBindArgs, "package");
            }
            return mIsolatedAppBindArgs;
        }

        if (mAppBindArgs == null) {
            mAppBindArgs = new ArrayMap<>();

            // Add common services.
            // IMPORTANT: Before adding services here, make sure ephemeral apps can access them too.
            // Enable the check in ApplicationThread.bindApplication() to make sure.
            addServiceToMap(mAppBindArgs, "package");
            addServiceToMap(mAppBindArgs, Context.WINDOW_SERVICE);
            addServiceToMap(mAppBindArgs, Context.ALARM_SERVICE);
            addServiceToMap(mAppBindArgs, Context.DISPLAY_SERVICE);
            addServiceToMap(mAppBindArgs, Context.NETWORKMANAGEMENT_SERVICE);
            addServiceToMap(mAppBindArgs, Context.CONNECTIVITY_SERVICE);
            addServiceToMap(mAppBindArgs, Context.ACCESSIBILITY_SERVICE);
            addServiceToMap(mAppBindArgs, Context.INPUT_METHOD_SERVICE);
            addServiceToMap(mAppBindArgs, Context.INPUT_SERVICE);
            addServiceToMap(mAppBindArgs, "graphicsstats");
            addServiceToMap(mAppBindArgs, Context.APP_OPS_SERVICE);
            addServiceToMap(mAppBindArgs, "content");
            addServiceToMap(mAppBindArgs, Context.JOB_SCHEDULER_SERVICE);
            addServiceToMap(mAppBindArgs, Context.NOTIFICATION_SERVICE);
            addServiceToMap(mAppBindArgs, Context.VIBRATOR_SERVICE);
            addServiceToMap(mAppBindArgs, Context.ACCOUNT_SERVICE);
            addServiceToMap(mAppBindArgs, Context.POWER_SERVICE);
            addServiceToMap(mAppBindArgs, Context.USER_SERVICE);
            addServiceToMap(mAppBindArgs, "mount");
        }
        return mAppBindArgs;
    }

    private static void addServiceToMap(ArrayMap<String, IBinder> map, String name) {
        final IBinder service = ServiceManager.getService(name);
        if (service != null) {
            map.put(name, service);
            if (false) {
                Log.i(TAG, "Adding " + name + " to the pre-loaded service cache.");
            }
        }
    }

    @Override
    public void setFocusedStack(int stackId) {
        mActivityTaskManager.setFocusedStack(stackId);
    }

    /** Sets the task stack listener that gets callbacks when a task stack changes. */
    @Override
    public void registerTaskStackListener(ITaskStackListener listener) {
        mActivityTaskManager.registerTaskStackListener(listener);
    }

    /**
     * Unregister a task stack listener so that it stops receiving callbacks.
     */
    @Override
    public void unregisterTaskStackListener(ITaskStackListener listener) {
        mActivityTaskManager.unregisterTaskStackListener(listener);
    }

    final void updateLruProcessLocked(ProcessRecord app, boolean activityChange,
            ProcessRecord client) {
        mProcessList.updateLruProcessLocked(app, activityChange, client);
    }

    final void removeLruProcessLocked(ProcessRecord app) {
        mProcessList.removeLruProcessLocked(app);
    }

    final ProcessRecord getProcessRecordLocked(String processName, int uid, boolean keepIfLarge) {
        return mProcessList.getProcessRecordLocked(processName, uid, keepIfLarge);
    }

    final ProcessMap<ProcessRecord> getProcessNames() {
        return mProcessList.mProcessNames;
    }

    void notifyPackageUse(String packageName, int reason) {
        synchronized(this) {
            getPackageManagerInternalLocked().notifyPackageUse(packageName, reason);
        }
    }

    boolean startIsolatedProcess(String entryPoint, String[] entryPointArgs,
            String processName, String abiOverride, int uid, Runnable crashHandler) {
        synchronized(this) {
            ApplicationInfo info = new ApplicationInfo();
            // In general the ApplicationInfo.uid isn't neccesarily equal to ProcessRecord.uid.
            // For isolated processes, the former contains the parent's uid and the latter the
            // actual uid of the isolated process.
            // In the special case introduced by this method (which is, starting an isolated
            // process directly from the SystemServer without an actual parent app process) the
            // closest thing to a parent's uid is SYSTEM_UID.
            // The only important thing here is to keep AI.uid != PR.uid, in order to trigger
            // the |isolated| logic in the ProcessRecord constructor.
            info.uid = SYSTEM_UID;
            info.processName = processName;
            info.className = entryPoint;
            info.packageName = "android";
            info.seInfoUser = SELinuxUtil.COMPLETE_STR;
            info.targetSdkVersion = Build.VERSION.SDK_INT;
            ProcessRecord proc = mProcessList.startProcessLocked(processName, info /* info */,
                    false /* knownToBeDead */, 0 /* intentFlags */, ""  /* hostingType */,
                    null /* hostingName */, true /* allowWhileBooting */, true /* isolated */,
                    uid, true /* keepIfLarge */, abiOverride, entryPoint, entryPointArgs,
                    crashHandler);
            return proc != null;
        }
    }

    @GuardedBy("this")
    final ProcessRecord startProcessLocked(String processName,
            ApplicationInfo info, boolean knownToBeDead, int intentFlags,
            String hostingType, ComponentName hostingName, boolean allowWhileBooting,
            boolean isolated, boolean keepIfLarge) {
        return mProcessList.startProcessLocked(processName, info, knownToBeDead, intentFlags,
                hostingType,
                hostingName, allowWhileBooting, isolated, 0 /* isolatedUid */, keepIfLarge,
                null /* ABI override */, null /* entryPoint */, null /* entryPointArgs */,
                null /* crashHandler */);
    }

    boolean isAllowedWhileBooting(ApplicationInfo ai) {
        return (ai.flags&ApplicationInfo.FLAG_PERSISTENT) != 0;
    }

    /**
     * Update battery stats on the activity' usage.
     * @param activity
     * @param uid
     * @param userId
     * @param resumed
     */
    void updateBatteryStats(ComponentName activity, int uid, int userId, boolean resumed) {
        if (DEBUG_SWITCH) {
            Slog.d(TAG_SWITCH,
                    "updateBatteryStats: comp=" + activity + "res=" + resumed);
        }
        final BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
        StatsLog.write(StatsLog.ACTIVITY_FOREGROUND_STATE_CHANGED,
                uid, activity.getPackageName(), activity.getShortClassName(),
                resumed ? StatsLog.ACTIVITY_FOREGROUND_STATE_CHANGED__STATE__FOREGROUND :
                        StatsLog.ACTIVITY_FOREGROUND_STATE_CHANGED__STATE__BACKGROUND);
        synchronized (stats) {
            if (resumed) {
                stats.noteActivityResumedLocked(uid);
            } else {
                stats.noteActivityPausedLocked(uid);
            }
        }
    }

    /**
     * Update UsageStas on the activity's usage.
     * @param activity
     * @param userId
     * @param event
     * @param appToken ActivityRecord's appToken.
     * @param taskRoot TaskRecord's root
     */
    public void updateActivityUsageStats(ComponentName activity, int userId, int event,
            IBinder appToken, ComponentName taskRoot) {
        if (DEBUG_SWITCH) {
            Slog.d(TAG_SWITCH, "updateActivityUsageStats: comp="
                    + activity + " hash=" + appToken.hashCode() + " event=" + event);
        }
        synchronized (this) {
            if (mUsageStatsService != null) {
                mUsageStatsService.reportEvent(activity, userId, event, appToken.hashCode(),
                        taskRoot);
            }
        }
        if (mContentCaptureService != null
                && (event == Event.ACTIVITY_PAUSED || event == Event.ACTIVITY_RESUMED)) {
            mContentCaptureService.notifyActivityEvent(userId, activity, event);
        }
    }

    /**
     * Update UsageStats on this package's usage.
     * @param packageName
     * @param userId
     * @param event
     */
    public void updateActivityUsageStats(String packageName, int userId, int event) {
        if (DEBUG_SWITCH) {
            Slog.d(TAG_SWITCH, "updateActivityUsageStats: package="
                    + packageName + " event=" + event);
        }
        synchronized (this) {
            if (mUsageStatsService != null) {
                mUsageStatsService.reportEvent(packageName, userId, event);
            }
        }
    }

    /**
     * Update Usages on this foreground service's usage.
     * @param service
     * @param userId
     * @param started
     */
    void updateForegroundServiceUsageStats(ComponentName service, int userId, boolean started) {
        if (DEBUG_SWITCH) {
            Slog.d(TAG_SWITCH, "updateForegroundServiceUsageStats: comp="
                    + service + " started=" + started);
        }
        synchronized (this) {
            if (mUsageStatsService != null) {
                mUsageStatsService.reportEvent(service, userId,
                        started ? UsageEvents.Event.FOREGROUND_SERVICE_START
                                : UsageEvents.Event.FOREGROUND_SERVICE_STOP, 0, null);
            }
        }
    }

    CompatibilityInfo compatibilityInfoForPackage(ApplicationInfo ai) {
        return mAtmInternal.compatibilityInfoForPackage(ai);
    }

    private void enforceNotIsolatedCaller(String caller) {
        if (UserHandle.isIsolated(Binder.getCallingUid())) {
            throw new SecurityException("Isolated process not allowed to call " + caller);
        }
    }

    @Override
    public void setPackageScreenCompatMode(String packageName, int mode) {
        mActivityTaskManager.setPackageScreenCompatMode(packageName, mode);
    }

    private boolean hasUsageStatsPermission(String callingPackage) {
        final int mode = mAppOpsService.noteOperation(AppOpsManager.OP_GET_USAGE_STATS,
                Binder.getCallingUid(), callingPackage);
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return checkCallingPermission(Manifest.permission.PACKAGE_USAGE_STATS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public int getPackageProcessState(String packageName, String callingPackage) {
        if (!hasUsageStatsPermission(callingPackage)) {
            enforceCallingPermission(android.Manifest.permission.PACKAGE_USAGE_STATS,
                    "getPackageProcessState");
        }

        int procState = PROCESS_STATE_NONEXISTENT;
        synchronized (this) {
            for (int i=mProcessList.mLruProcesses.size()-1; i>=0; i--) {
                final ProcessRecord proc = mProcessList.mLruProcesses.get(i);
                if (procState > proc.setProcState) {
                    if (proc.pkgList.containsKey(packageName) ||
                            (proc.pkgDeps != null && proc.pkgDeps.contains(packageName))) {
                        procState = proc.setProcState;
                    }
                }
            }
        }
        return procState;
    }

    @Override
    public boolean setProcessMemoryTrimLevel(String process, int userId, int level)
            throws RemoteException {
        synchronized (this) {
            final ProcessRecord app = findProcessLocked(process, userId, "setProcessMemoryTrimLevel");
            if (app == null) {
                throw new IllegalArgumentException("Unknown process: " + process);
            }
            if (app.thread == null) {
                throw new IllegalArgumentException("Process has no app thread");
            }
            if (app.trimMemoryLevel >= level) {
                throw new IllegalArgumentException(
                        "Unable to set a higher trim level than current level");
            }
            if (!(level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
                    app.getCurProcState() > ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND)) {
                throw new IllegalArgumentException("Unable to set a background trim level "
                    + "on a foreground process");
            }
            app.thread.scheduleTrimMemory(level);
            app.trimMemoryLevel = level;
            return true;
        }
    }

    private void dispatchProcessesChanged() {
        int N;
        synchronized (this) {
            N = mPendingProcessChanges.size();
            if (mActiveProcessChanges.length < N) {
                mActiveProcessChanges = new ProcessChangeItem[N];
            }
            mPendingProcessChanges.toArray(mActiveProcessChanges);
            mPendingProcessChanges.clear();
            if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG_PROCESS_OBSERVERS,
                    "*** Delivering " + N + " process changes");
        }

        int i = mProcessObservers.beginBroadcast();
        while (i > 0) {
            i--;
            final IProcessObserver observer = mProcessObservers.getBroadcastItem(i);
            if (observer != null) {
                try {
                    for (int j=0; j<N; j++) {
                        ProcessChangeItem item = mActiveProcessChanges[j];
                        if ((item.changes&ProcessChangeItem.CHANGE_ACTIVITIES) != 0) {
                            if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG_PROCESS_OBSERVERS,
                                    "ACTIVITIES CHANGED pid=" + item.pid + " uid="
                                    + item.uid + ": " + item.foregroundActivities);
                            observer.onForegroundActivitiesChanged(item.pid, item.uid,
                                    item.foregroundActivities);
                        }
                        if ((item.changes & ProcessChangeItem.CHANGE_FOREGROUND_SERVICES) != 0) {
                            if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG_PROCESS_OBSERVERS,
                                    "FOREGROUND SERVICES CHANGED pid=" + item.pid + " uid="
                                            + item.uid + ": " + item.foregroundServiceTypes);
                            observer.onForegroundServicesChanged(item.pid, item.uid,
                                    item.foregroundServiceTypes);
                        }
                    }
                } catch (RemoteException e) {
                }
            }
        }
        mProcessObservers.finishBroadcast();

        synchronized (this) {
            for (int j=0; j<N; j++) {
                mAvailProcessChanges.add(mActiveProcessChanges[j]);
            }
        }
    }

    @GuardedBy("this")
    ProcessChangeItem enqueueProcessChangeItemLocked(int uid, int pid) {
        int i = mPendingProcessChanges.size()-1;
        ActivityManagerService.ProcessChangeItem item = null;
        while (i >= 0) {
            item = mPendingProcessChanges.get(i);
            if (item.pid == pid) {
                if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG_PROCESS_OBSERVERS,
                        "Re-using existing item: " + item);
                break;
            }
            i--;
        }

        if (i < 0) {
            // No existing item in pending changes; need a new one.
            final int NA = mAvailProcessChanges.size();
            if (NA > 0) {
                item = mAvailProcessChanges.remove(NA-1);
                if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG_PROCESS_OBSERVERS,
                        "Retrieving available item: " + item);
            } else {
                item = new ActivityManagerService.ProcessChangeItem();
                if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG_PROCESS_OBSERVERS,
                        "Allocating new item: " + item);
            }
            item.changes = 0;
            item.pid = pid;
            item.uid = uid;
            if (mPendingProcessChanges.size() == 0) {
                if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG_PROCESS_OBSERVERS,
                        "*** Enqueueing dispatch processes changed!");
                mUiHandler.obtainMessage(DISPATCH_PROCESSES_CHANGED_UI_MSG)
                        .sendToTarget();
            }
            mPendingProcessChanges.add(item);
        }

        return item;
    }

    private void dispatchProcessDied(int pid, int uid) {
        int i = mProcessObservers.beginBroadcast();
        while (i > 0) {
            i--;
            final IProcessObserver observer = mProcessObservers.getBroadcastItem(i);
            if (observer != null) {
                try {
                    observer.onProcessDied(pid, uid);
                } catch (RemoteException e) {
                }
            }
        }
        mProcessObservers.finishBroadcast();
    }

    @VisibleForTesting
    void dispatchUidsChanged() {
        int N;
        synchronized (this) {
            N = mPendingUidChanges.size();
            if (mActiveUidChanges.length < N) {
                mActiveUidChanges = new UidRecord.ChangeItem[N];
            }
            for (int i=0; i<N; i++) {
                final UidRecord.ChangeItem change = mPendingUidChanges.get(i);
                mActiveUidChanges[i] = change;
                if (change.uidRecord != null) {
                    change.uidRecord.pendingChange = null;
                    change.uidRecord = null;
                }
            }
            mPendingUidChanges.clear();
            if (DEBUG_UID_OBSERVERS) Slog.i(TAG_UID_OBSERVERS,
                    "*** Delivering " + N + " uid changes");
        }

        mUidChangeDispatchCount += N;
        int i = mUidObservers.beginBroadcast();
        while (i > 0) {
            i--;
            dispatchUidsChangedForObserver(mUidObservers.getBroadcastItem(i),
                    (UidObserverRegistration) mUidObservers.getBroadcastCookie(i), N);
        }
        mUidObservers.finishBroadcast();

        if (VALIDATE_UID_STATES && mUidObservers.getRegisteredCallbackCount() > 0) {
            for (int j = 0; j < N; ++j) {
                final UidRecord.ChangeItem item = mActiveUidChanges[j];
                if ((item.change & UidRecord.CHANGE_GONE) != 0) {
                    mValidateUids.remove(item.uid);
                } else {
                    UidRecord validateUid = mValidateUids.get(item.uid);
                    if (validateUid == null) {
                        validateUid = new UidRecord(item.uid);
                        mValidateUids.put(item.uid, validateUid);
                    }
                    if ((item.change & UidRecord.CHANGE_IDLE) != 0) {
                        validateUid.idle = true;
                    } else if ((item.change & UidRecord.CHANGE_ACTIVE) != 0) {
                        validateUid.idle = false;
                    }
                    validateUid.setCurProcState(validateUid.setProcState = item.processState);
                    validateUid.lastDispatchedProcStateSeq = item.procStateSeq;
                }
            }
        }

        synchronized (this) {
            for (int j = 0; j < N; j++) {
                mAvailUidChanges.add(mActiveUidChanges[j]);
            }
        }
    }

    private void dispatchUidsChangedForObserver(IUidObserver observer,
            UidObserverRegistration reg, int changesSize) {
        if (observer == null) {
            return;
        }
        try {
            for (int j = 0; j < changesSize; j++) {
                UidRecord.ChangeItem item = mActiveUidChanges[j];
                final int change = item.change;
                if (change == UidRecord.CHANGE_PROCSTATE &&
                        (reg.which & ActivityManager.UID_OBSERVER_PROCSTATE) == 0) {
                    // No-op common case: no significant change, the observer is not
                    // interested in all proc state changes.
                    continue;
                }
                final long start = SystemClock.uptimeMillis();
                if ((change & UidRecord.CHANGE_IDLE) != 0) {
                    if ((reg.which & ActivityManager.UID_OBSERVER_IDLE) != 0) {
                        if (DEBUG_UID_OBSERVERS) Slog.i(TAG_UID_OBSERVERS,
                                "UID idle uid=" + item.uid);
                        observer.onUidIdle(item.uid, item.ephemeral);
                    }
                } else if ((change & UidRecord.CHANGE_ACTIVE) != 0) {
                    if ((reg.which & ActivityManager.UID_OBSERVER_ACTIVE) != 0) {
                        if (DEBUG_UID_OBSERVERS) Slog.i(TAG_UID_OBSERVERS,
                                "UID active uid=" + item.uid);
                        observer.onUidActive(item.uid);
                    }
                }
                if ((reg.which & ActivityManager.UID_OBSERVER_CACHED) != 0) {
                    if ((change & UidRecord.CHANGE_CACHED) != 0) {
                        if (DEBUG_UID_OBSERVERS) Slog.i(TAG_UID_OBSERVERS,
                                "UID cached uid=" + item.uid);
                        observer.onUidCachedChanged(item.uid, true);
                    } else if ((change & UidRecord.CHANGE_UNCACHED) != 0) {
                        if (DEBUG_UID_OBSERVERS) Slog.i(TAG_UID_OBSERVERS,
                                "UID active uid=" + item.uid);
                        observer.onUidCachedChanged(item.uid, false);
                    }
                }
                if ((change & UidRecord.CHANGE_GONE) != 0) {
                    if ((reg.which & ActivityManager.UID_OBSERVER_GONE) != 0) {
                        if (DEBUG_UID_OBSERVERS) Slog.i(TAG_UID_OBSERVERS,
                                "UID gone uid=" + item.uid);
                        observer.onUidGone(item.uid, item.ephemeral);
                    }
                    if (reg.lastProcStates != null) {
                        reg.lastProcStates.delete(item.uid);
                    }
                } else {
                    if ((reg.which & ActivityManager.UID_OBSERVER_PROCSTATE) != 0) {
                        if (DEBUG_UID_OBSERVERS) Slog.i(TAG_UID_OBSERVERS,
                                "UID CHANGED uid=" + item.uid
                                        + ": " + item.processState);
                        boolean doReport = true;
                        if (reg.cutpoint >= ActivityManager.MIN_PROCESS_STATE) {
                            final int lastState = reg.lastProcStates.get(item.uid,
                                    ActivityManager.PROCESS_STATE_UNKNOWN);
                            if (lastState != ActivityManager.PROCESS_STATE_UNKNOWN) {
                                final boolean lastAboveCut = lastState <= reg.cutpoint;
                                final boolean newAboveCut = item.processState <= reg.cutpoint;
                                doReport = lastAboveCut != newAboveCut;
                            } else {
                                doReport = item.processState != PROCESS_STATE_NONEXISTENT;
                            }
                        }
                        if (doReport) {
                            if (reg.lastProcStates != null) {
                                reg.lastProcStates.put(item.uid, item.processState);
                            }
                            observer.onUidStateChanged(item.uid, item.processState,
                                    item.procStateSeq);
                        }
                    }
                }
                final int duration = (int) (SystemClock.uptimeMillis() - start);
                if (reg.mMaxDispatchTime < duration) {
                    reg.mMaxDispatchTime = duration;
                }
                if (duration >= SLOW_UID_OBSERVER_THRESHOLD_MS) {
                    reg.mSlowDispatchCount++;
                }
            }
        } catch (RemoteException e) {
        }
    }

    void dispatchOomAdjObserver(String msg) {
        OomAdjObserver observer;
        synchronized (this) {
            observer = mCurOomAdjObserver;
        }

        if (observer != null) {
            observer.onOomAdjMessage(msg);
        }
    }

    void setOomAdjObserver(int uid, OomAdjObserver observer) {
        synchronized (this) {
            mCurOomAdjUid = uid;
            mCurOomAdjObserver = observer;
        }
    }

    void clearOomAdjObserver() {
        synchronized (this) {
            mCurOomAdjUid = -1;
            mCurOomAdjObserver = null;
        }
    }

    void reportOomAdjMessageLocked(String tag, String msg) {
        Slog.d(tag, msg);
        if (mCurOomAdjObserver != null) {
            mUiHandler.obtainMessage(DISPATCH_OOM_ADJ_OBSERVER_MSG, msg).sendToTarget();
        }
    }

    void reportUidInfoMessageLocked(String tag, String msg, int uid) {
        Slog.i(TAG, msg);
        if (mCurOomAdjObserver != null && uid == mCurOomAdjUid) {
            mUiHandler.obtainMessage(DISPATCH_OOM_ADJ_OBSERVER_MSG, msg).sendToTarget();
        }

    }

    @Override
    public int startActivity(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
            int startFlags, ProfilerInfo profilerInfo, Bundle bOptions) {
        return mActivityTaskManager.startActivity(caller, callingPackage, intent, resolvedType,
                resultTo, resultWho, requestCode, startFlags, profilerInfo, bOptions);
    }

    @Override
    public final int startActivityAsUser(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
            int startFlags, ProfilerInfo profilerInfo, Bundle bOptions, int userId) {

            return mActivityTaskManager.startActivityAsUser(caller, callingPackage, intent,
                    resolvedType, resultTo, resultWho, requestCode, startFlags, profilerInfo,
                    bOptions, userId);
    }

    WaitResult startActivityAndWait(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
            int startFlags, ProfilerInfo profilerInfo, Bundle bOptions, int userId) {
            return mActivityTaskManager.startActivityAndWait(caller, callingPackage, intent,
                    resolvedType, resultTo, resultWho, requestCode, startFlags, profilerInfo,
                    bOptions, userId);
    }

    @Override
    public final int startActivityFromRecents(int taskId, Bundle bOptions) {
        return mActivityTaskManager.startActivityFromRecents(taskId, bOptions);
    }

    @Override
    public void startRecentsActivity(Intent intent, IAssistDataReceiver assistDataReceiver,
            IRecentsAnimationRunner recentsAnimationRunner) {
        mActivityTaskManager.startRecentsActivity(
                intent, assistDataReceiver, recentsAnimationRunner);
    }

    @Override
    public void cancelRecentsAnimation(boolean restoreHomeStackPosition) {
        mActivityTaskManager.cancelRecentsAnimation(restoreHomeStackPosition);
    }

    /**
     * This is the internal entry point for handling Activity.finish().
     *
     * @param token The Binder token referencing the Activity we want to finish.
     * @param resultCode Result code, if any, from this Activity.
     * @param resultData Result data (Intent), if any, from this Activity.
     * @param finishTask Whether to finish the task associated with this Activity.
     *
     * @return Returns true if the activity successfully finished, or false if it is still running.
     */
    @Override
    public final boolean finishActivity(IBinder token, int resultCode, Intent resultData,
            int finishTask) {
        return mActivityTaskManager.finishActivity(token, resultCode, resultData, finishTask);
    }

    @Override
    public void setRequestedOrientation(IBinder token, int requestedOrientation) {
        mActivityTaskManager.setRequestedOrientation(token, requestedOrientation);
    }

    @Override
    public final void finishHeavyWeightApp() {
        if (checkCallingPermission(android.Manifest.permission.FORCE_STOP_PACKAGES)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: finishHeavyWeightApp() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.FORCE_STOP_PACKAGES;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        mAtmInternal.finishHeavyWeightApp();
    }

    @Override
    public void crashApplication(int uid, int initialPid, String packageName, int userId,
            String message) {
        if (checkCallingPermission(android.Manifest.permission.FORCE_STOP_PACKAGES)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: crashApplication() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.FORCE_STOP_PACKAGES;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        synchronized(this) {
            mAppErrors.scheduleAppCrashLocked(uid, initialPid, packageName, userId, message);
        }
    }

    /**
     * Main function for removing an existing process from the activity manager
     * as a result of that process going away.  Clears out all connections
     * to the process.
     */
    @GuardedBy("this")
    final void handleAppDiedLocked(ProcessRecord app,
            boolean restarting, boolean allowRestart) {
        int pid = app.pid;
        boolean kept = cleanUpApplicationRecordLocked(app, restarting, allowRestart, -1,
                false /*replacingPid*/);
        if (!kept && !restarting) {
            removeLruProcessLocked(app);
            if (pid > 0) {
                ProcessList.remove(pid);
            }
        }

        if (mProfileData.getProfileProc() == app) {
            clearProfilerLocked();
        }

        mAtmInternal.handleAppDied(app.getWindowProcessController(), restarting, () -> {
            Slog.w(TAG, "Crash of app " + app.processName
                    + " running instrumentation " + app.getActiveInstrumentation().mClass);
            Bundle info = new Bundle();
            info.putString("shortMsg", "Process crashed.");
            finishInstrumentationLocked(app, Activity.RESULT_CANCELED, info);
        });
    }

    ProcessRecord getRecordForAppLocked(IApplicationThread thread) {
        if (thread == null) {
            return null;
        }

        ProcessRecord record = mProcessList.getLRURecordForAppLocked(thread);
        if (record != null) return record;

        // Validation: if it isn't in the LRU list, it shouldn't exist, but let's
        // double-check that.
        final IBinder threadBinder = thread.asBinder();
        final ArrayMap<String, SparseArray<ProcessRecord>> pmap =
                mProcessList.mProcessNames.getMap();
        for (int i = pmap.size()-1; i >= 0; i--) {
            final SparseArray<ProcessRecord> procs = pmap.valueAt(i);
            for (int j = procs.size()-1; j >= 0; j--) {
                final ProcessRecord proc = procs.valueAt(j);
                if (proc.thread != null && proc.thread.asBinder() == threadBinder) {
                    Slog.wtf(TAG, "getRecordForApp: exists in name list but not in LRU list: "
                            + proc);
                    return proc;
                }
            }
        }

        return null;
    }

    final void doLowMemReportIfNeededLocked(ProcessRecord dyingProc) {
        // If there are no longer any background processes running,
        // and the app that died was not running instrumentation,
        // then tell everyone we are now low on memory.
        if (!mProcessList.haveBackgroundProcessLocked()) {
            boolean doReport = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
            if (doReport) {
                long now = SystemClock.uptimeMillis();
                if (now < (mLastMemUsageReportTime+5*60*1000)) {
                    doReport = false;
                } else {
                    mLastMemUsageReportTime = now;
                }
            }
            final ArrayList<ProcessMemInfo> memInfos
                    = doReport ? new ArrayList<ProcessMemInfo>(mProcessList.getLruSizeLocked())
                    : null;
            EventLog.writeEvent(EventLogTags.AM_LOW_MEMORY, mProcessList.getLruSizeLocked());
            long now = SystemClock.uptimeMillis();
            for (int i = mProcessList.mLruProcesses.size() - 1; i >= 0; i--) {
                ProcessRecord rec = mProcessList.mLruProcesses.get(i);
                if (rec == dyingProc || rec.thread == null) {
                    continue;
                }
                if (doReport) {
                    memInfos.add(new ProcessMemInfo(rec.processName, rec.pid, rec.setAdj,
                            rec.setProcState, rec.adjType, rec.makeAdjReason()));
                }
                if ((rec.lastLowMemory+mConstants.GC_MIN_INTERVAL) <= now) {
                    // The low memory report is overriding any current
                    // state for a GC request.  Make sure to do
                    // heavy/important/visible/foreground processes first.
                    if (rec.setAdj <= ProcessList.HEAVY_WEIGHT_APP_ADJ) {
                        rec.lastRequestedGc = 0;
                    } else {
                        rec.lastRequestedGc = rec.lastLowMemory;
                    }
                    rec.reportLowMemory = true;
                    rec.lastLowMemory = now;
                    mProcessesToGc.remove(rec);
                    addProcessToGcListLocked(rec);
                }
            }
            if (doReport) {
                Message msg = mHandler.obtainMessage(REPORT_MEM_USAGE_MSG, memInfos);
                mHandler.sendMessage(msg);
            }
            scheduleAppGcsLocked();
        }
    }

    @GuardedBy("this")
    final void appDiedLocked(ProcessRecord app) {
       appDiedLocked(app, app.pid, app.thread, false);
    }

    @GuardedBy("this")
    final void appDiedLocked(ProcessRecord app, int pid, IApplicationThread thread,
            boolean fromBinderDied) {
        // First check if this ProcessRecord is actually active for the pid.
        synchronized (mPidsSelfLocked) {
            ProcessRecord curProc = mPidsSelfLocked.get(pid);
            if (curProc != app) {
                Slog.w(TAG, "Spurious death for " + app + ", curProc for " + pid + ": " + curProc);
                return;
            }
        }

        BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
        synchronized (stats) {
            stats.noteProcessDiedLocked(app.info.uid, pid);
        }

        if (!app.killed) {
            if (!fromBinderDied) {
                killProcessQuiet(pid);
            }
            ProcessList.killProcessGroup(app.uid, pid);
            app.killed = true;
        }

        // Clean up already done if the process has been re-started.
        if (app.pid == pid && app.thread != null &&
                app.thread.asBinder() == thread.asBinder()) {
            boolean doLowMem = app.getActiveInstrumentation() == null;
            boolean doOomAdj = doLowMem;
            if (!app.killedByAm) {
                reportUidInfoMessageLocked(TAG,
                        "Process " + app.processName + " (pid " + pid + ") has died: "
                                + ProcessList.makeOomAdjString(app.setAdj, true) + " "
                                + ProcessList.makeProcStateString(app.setProcState), app.info.uid);
                mAllowLowerMemLevel = true;
            } else {
                // Note that we always want to do oom adj to update our state with the
                // new number of procs.
                mAllowLowerMemLevel = false;
                doLowMem = false;
            }
            EventLog.writeEvent(EventLogTags.AM_PROC_DIED, app.userId, app.pid, app.processName,
                    app.setAdj, app.setProcState);
            if (DEBUG_CLEANUP) Slog.v(TAG_CLEANUP,
                "Dying app: " + app + ", pid: " + pid + ", thread: " + thread.asBinder());
            handleAppDiedLocked(app, false, true);

            if (doOomAdj) {
                updateOomAdjLocked();
            }
            if (doLowMem) {
                doLowMemReportIfNeededLocked(app);
            }
        } else if (app.pid != pid) {
            // A new process has already been started.
            reportUidInfoMessageLocked(TAG,
                    "Process " + app.processName + " (pid " + pid
                            + ") has died and restarted (pid " + app.pid + ").", app.info.uid);
            EventLog.writeEvent(EventLogTags.AM_PROC_DIED, app.userId, app.pid, app.processName);
        } else if (DEBUG_PROCESSES) {
            Slog.d(TAG_PROCESSES, "Received spurious death notification for thread "
                    + thread.asBinder());
        }

        // On the device which doesn't have Cgroup, log LmkStateChanged which is used as a signal
        // for pulling memory stats of other running processes when this process died.
        if (!hasMemcg()) {
            StatsLog.write(StatsLog.APP_DIED, SystemClock.elapsedRealtime());
        }
    }

    /**
     * If a stack trace dump file is configured, dump process stack traces.
     * @param firstPids of dalvik VM processes to dump stack traces for first
     * @param lastPids of dalvik VM processes to dump stack traces for last
     * @param nativePids optional list of native pids to dump stack crawls
     */
    public static File dumpStackTraces(ArrayList<Integer> firstPids,
            ProcessCpuTracker processCpuTracker, SparseArray<Boolean> lastPids,
            ArrayList<Integer> nativePids) {
        ArrayList<Integer> extraPids = null;

        if (DEBUG_ANR) {
            Slog.d(TAG, "dumpStackTraces pids=" + lastPids + " nativepids=" + nativePids);
        }

        // Measure CPU usage as soon as we're called in order to get a realistic sampling
        // of the top users at the time of the request.
        if (processCpuTracker != null) {
            processCpuTracker.init();
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }

            processCpuTracker.update();

            // We'll take the stack crawls of just the top apps using CPU.
            final int N = processCpuTracker.countWorkingStats();
            extraPids = new ArrayList<>();
            for (int i = 0; i < N && extraPids.size() < 5; i++) {
                ProcessCpuTracker.Stats stats = processCpuTracker.getWorkingStats(i);
                if (lastPids.indexOfKey(stats.pid) >= 0) {
                    if (DEBUG_ANR) Slog.d(TAG, "Collecting stacks for extra pid " + stats.pid);

                    extraPids.add(stats.pid);
                } else if (DEBUG_ANR) {
                    Slog.d(TAG, "Skipping next CPU consuming process, not a java proc: "
                            + stats.pid);
                }
            }
        }

        final File tracesDir = new File(ANR_TRACE_DIR);
        // Each set of ANR traces is written to a separate file and dumpstate will process
        // all such files and add them to a captured bug report if they're recent enough.
        maybePruneOldTraces(tracesDir);

        // NOTE: We should consider creating the file in native code atomically once we've
        // gotten rid of the old scheme of dumping and lot of the code that deals with paths
        // can be removed.
        File tracesFile = createAnrDumpFile(tracesDir);
        if (tracesFile == null) {
            return null;
        }
        if (DEBUG_ANR) {
            Slog.d(TAG, "Dumping to " + tracesFile.getAbsolutePath());
        }

        dumpStackTraces(tracesFile.getAbsolutePath(), firstPids, nativePids, extraPids);
        return tracesFile;
    }

    @GuardedBy("ActivityManagerService.class")
    private static SimpleDateFormat sAnrFileDateFormat;

    private static synchronized File createAnrDumpFile(File tracesDir) {
        if (sAnrFileDateFormat == null) {
            sAnrFileDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS");
        }

        final String formattedDate = sAnrFileDateFormat.format(new Date());
        final File anrFile = new File(tracesDir, "anr_" + formattedDate);

        try {
            if (anrFile.createNewFile()) {
                FileUtils.setPermissions(anrFile.getAbsolutePath(), 0600, -1, -1); // -rw-------
                return anrFile;
            } else {
                Slog.w(TAG, "Unable to create ANR dump file: createNewFile failed");
            }
        } catch (IOException ioe) {
            Slog.w(TAG, "Exception creating ANR dump file:", ioe);
        }

        return null;
    }

    /**
     * Prune all trace files that are more than a day old.
     *
     * NOTE: It might make sense to move this functionality to tombstoned eventually, along with a
     * shift away from anr_XX and tombstone_XX to a more descriptive name. We do it here for now
     * since it's the system_server that creates trace files for most ANRs.
     */
    private static void maybePruneOldTraces(File tracesDir) {
        final File[] files = tracesDir.listFiles();
        if (files == null) return;

        final int max = SystemProperties.getInt("tombstoned.max_anr_count", 64);
        final long now = System.currentTimeMillis();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (int i = 0; i < files.length; ++i) {
            if (i > max || (now - files[i].lastModified()) > DAY_IN_MILLIS) {
                if (!files[i].delete()) {
                    Slog.w(TAG, "Unable to prune stale trace file: " + files[i]);
                }
            }
        }
    }

    /**
     * Dump java traces for process {@code pid} to the specified file. If java trace dumping
     * fails, a native backtrace is attempted. Note that the timeout {@code timeoutMs} only applies
     * to the java section of the trace, a further {@code NATIVE_DUMP_TIMEOUT_MS} might be spent
     * attempting to obtain native traces in the case of a failure. Returns the total time spent
     * capturing traces.
     */
    private static long dumpJavaTracesTombstoned(int pid, String fileName, long timeoutMs) {
        final long timeStart = SystemClock.elapsedRealtime();
        if (!Debug.dumpJavaBacktraceToFileTimeout(pid, fileName, (int) (timeoutMs / 1000))) {
            Debug.dumpNativeBacktraceToFileTimeout(pid, fileName,
                    (NATIVE_DUMP_TIMEOUT_MS / 1000));
        }

        return SystemClock.elapsedRealtime() - timeStart;
    }

    public static void dumpStackTraces(String tracesFile, ArrayList<Integer> firstPids,
            ArrayList<Integer> nativePids, ArrayList<Integer> extraPids) {

        // We don't need any sort of inotify based monitoring when we're dumping traces via
        // tombstoned. Data is piped to an "intercept" FD installed in tombstoned so we're in full
        // control of all writes to the file in question.

        // We must complete all stack dumps within 20 seconds.
        long remainingTime = 20 * 1000;

        // First collect all of the stacks of the most important pids.
        if (firstPids != null) {
            int num = firstPids.size();
            for (int i = 0; i < num; i++) {
                if (DEBUG_ANR) Slog.d(TAG, "Collecting stacks for pid " + firstPids.get(i));
                final long timeTaken = dumpJavaTracesTombstoned(firstPids.get(i), tracesFile,
                                                                remainingTime);

                remainingTime -= timeTaken;
                if (remainingTime <= 0) {
                    Slog.e(TAG, "Aborting stack trace dump (current firstPid=" + firstPids.get(i) +
                           "); deadline exceeded.");
                    return;
                }

                if (DEBUG_ANR) {
                    Slog.d(TAG, "Done with pid " + firstPids.get(i) + " in " + timeTaken + "ms");
                }
            }
        }

        // Next collect the stacks of the native pids
        if (nativePids != null) {
            for (int pid : nativePids) {
                if (DEBUG_ANR) Slog.d(TAG, "Collecting stacks for native pid " + pid);
                final long nativeDumpTimeoutMs = Math.min(NATIVE_DUMP_TIMEOUT_MS, remainingTime);

                final long start = SystemClock.elapsedRealtime();
                Debug.dumpNativeBacktraceToFileTimeout(
                        pid, tracesFile, (int) (nativeDumpTimeoutMs / 1000));
                final long timeTaken = SystemClock.elapsedRealtime() - start;

                remainingTime -= timeTaken;
                if (remainingTime <= 0) {
                    Slog.e(TAG, "Aborting stack trace dump (current native pid=" + pid +
                        "); deadline exceeded.");
                    return;
                }

                if (DEBUG_ANR) {
                    Slog.d(TAG, "Done with native pid " + pid + " in " + timeTaken + "ms");
                }
            }
        }

        // Lastly, dump stacks for all extra PIDs from the CPU tracker.
        if (extraPids != null) {
            for (int pid : extraPids) {
                if (DEBUG_ANR) Slog.d(TAG, "Collecting stacks for extra pid " + pid);

                final long timeTaken = dumpJavaTracesTombstoned(pid, tracesFile, remainingTime);

                remainingTime -= timeTaken;
                if (remainingTime <= 0) {
                    Slog.e(TAG, "Aborting stack trace dump (current extra pid=" + pid +
                            "); deadline exceeded.");
                    return;
                }

                if (DEBUG_ANR) {
                    Slog.d(TAG, "Done with extra pid " + pid + " in " + timeTaken + "ms");
                }
            }
        }
    }

    @Override
    public boolean clearApplicationUserData(final String packageName, boolean keepState,
            final IPackageDataObserver observer, int userId) {
        enforceNotIsolatedCaller("clearApplicationUserData");
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        final int resolvedUserId = mUserController.handleIncomingUser(pid, uid, userId, false,
                ALLOW_FULL_ONLY, "clearApplicationUserData", null);

        final ApplicationInfo appInfo;
        final boolean isInstantApp;

        long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            synchronized(this) {
                // Instant packages are not protected
                if (getPackageManagerInternalLocked().isPackageDataProtected(
                        resolvedUserId, packageName)) {
                    throw new SecurityException(
                            "Cannot clear data for a protected package: " + packageName);
                }

                ApplicationInfo applicationInfo = null;
                try {
                    applicationInfo = pm.getApplicationInfo(packageName,
                            MATCH_UNINSTALLED_PACKAGES, resolvedUserId);
                } catch (RemoteException e) {
                    /* ignore */
                }
                appInfo = applicationInfo;

                final boolean clearingOwnUidData = appInfo != null && appInfo.uid == uid;

                if (!clearingOwnUidData && checkComponentPermission(permission.CLEAR_APP_USER_DATA,
                        pid, uid, -1, true) != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("PID " + pid + " does not have permission "
                            + android.Manifest.permission.CLEAR_APP_USER_DATA + " to clear data"
                            + " of package " + packageName);
                }

                final boolean hasInstantMetadata = getPackageManagerInternalLocked()
                        .hasInstantApplicationMetadata(packageName, resolvedUserId);
                final boolean isUninstalledAppWithoutInstantMetadata =
                        (appInfo == null && !hasInstantMetadata);
                isInstantApp = (appInfo != null && appInfo.isInstantApp())
                        || hasInstantMetadata;
                final boolean canAccessInstantApps = checkComponentPermission(
                        permission.ACCESS_INSTANT_APPS, pid, uid, -1, true)
                        == PackageManager.PERMISSION_GRANTED;

                if (isUninstalledAppWithoutInstantMetadata || (isInstantApp
                        && !canAccessInstantApps)) {
                    Slog.w(TAG, "Invalid packageName: " + packageName);
                    if (observer != null) {
                        try {
                            observer.onRemoveCompleted(packageName, false);
                        } catch (RemoteException e) {
                            Slog.i(TAG, "Observer no longer exists.");
                        }
                    }
                    return false;
                }

                if (appInfo != null) {
                    forceStopPackageLocked(packageName, appInfo.uid, "clear data");
                    mAtmInternal.removeRecentTasksByPackageName(packageName, resolvedUserId);
                }
            }

            final IPackageDataObserver localObserver = new IPackageDataObserver.Stub() {
                @Override
                public void onRemoveCompleted(String packageName, boolean succeeded)
                        throws RemoteException {
                    if (appInfo != null) {
                        synchronized (ActivityManagerService.this) {
                            finishForceStopPackageLocked(packageName, appInfo.uid);
                        }
                    }
                    final Intent intent = new Intent(Intent.ACTION_PACKAGE_DATA_CLEARED,
                            Uri.fromParts("package", packageName, null));
                    intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
                    intent.putExtra(Intent.EXTRA_UID, (appInfo != null) ? appInfo.uid : -1);
                    intent.putExtra(Intent.EXTRA_USER_HANDLE, resolvedUserId);
                    if (isInstantApp) {
                        intent.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName);
                        broadcastIntentInPackage("android", SYSTEM_UID, uid, pid, intent, null,
                                null, 0, null, null, permission.ACCESS_INSTANT_APPS, null, false,
                                false, resolvedUserId, false);
                    } else {
                        broadcastIntentInPackage("android", SYSTEM_UID, uid, pid, intent, null,
                                null, 0, null, null, null, null, false, false, resolvedUserId,
                                false);
                    }

                    if (observer != null) {
                        observer.onRemoveCompleted(packageName, succeeded);
                    }
                }
            };

            try {
                // Clear application user data
                pm.clearApplicationUserData(packageName, localObserver, resolvedUserId);

                if (appInfo != null) {
                    // Restore already established notification state and permission grants,
                    // so it told us to keep those intact -- it's about to emplace app data
                    // that is appropriate for those bits of system state.
                    if (!keepState) {
                        // Remove all permissions granted from/to this package
                        mUgmInternal.removeUriPermissionsForPackage(packageName, resolvedUserId,
                                true, false);

                        // Reset notification state
                        INotificationManager inm = NotificationManager.getService();
                        inm.clearData(packageName, appInfo.uid, uid == appInfo.uid);
                    }

                    // Clear its scheduled jobs
                    JobSchedulerInternal js = LocalServices.getService(JobSchedulerInternal.class);
                    js.cancelJobsForUid(appInfo.uid, "clear data");

                    // Clear its pending alarms
                    AlarmManagerInternal ami = LocalServices.getService(AlarmManagerInternal.class);
                    ami.removeAlarmsForUid(appInfo.uid);
                }
            } catch (RemoteException e) {
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
        return true;
    }

    @Override
    public void killBackgroundProcesses(final String packageName, int userId) {
        if (checkCallingPermission(android.Manifest.permission.KILL_BACKGROUND_PROCESSES)
                != PackageManager.PERMISSION_GRANTED &&
                checkCallingPermission(android.Manifest.permission.RESTART_PACKAGES)
                        != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: killBackgroundProcesses() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.KILL_BACKGROUND_PROCESSES;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        userId = mUserController.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, true, ALLOW_FULL_ONLY, "killBackgroundProcesses", null);
        final int[] userIds = mUserController.expandUserId(userId);

        long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            for (int targetUserId : userIds) {
                int appId = -1;
                try {
                    appId = UserHandle.getAppId(
                            pm.getPackageUid(packageName, MATCH_DEBUG_TRIAGED_MISSING,
                                    targetUserId));
                } catch (RemoteException e) {
                }
                if (appId == -1) {
                    Slog.w(TAG, "Invalid packageName: " + packageName);
                    return;
                }
                synchronized (this) {
                    mProcessList.killPackageProcessesLocked(packageName, appId, targetUserId,
                            ProcessList.SERVICE_ADJ, "kill background");
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void killAllBackgroundProcesses() {
        if (checkCallingPermission(android.Manifest.permission.KILL_BACKGROUND_PROCESSES)
                != PackageManager.PERMISSION_GRANTED) {
            final String msg = "Permission Denial: killAllBackgroundProcesses() from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.KILL_BACKGROUND_PROCESSES;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        final long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                // Allow memory level to go down (the flag needs to be set before updating oom adj)
                // because this method is also used to simulate low memory.
                mAllowLowerMemLevel = true;
                mProcessList.killPackageProcessesLocked(null /* packageName */, -1 /* appId */,
                        UserHandle.USER_ALL, ProcessList.CACHED_APP_MIN_ADJ, "kill all background");

                doLowMemReportIfNeededLocked(null);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    /**
     * Kills all background processes, except those matching any of the
     * specified properties.
     *
     * @param minTargetSdk the target SDK version at or above which to preserve
     *                     processes, or {@code -1} to ignore the target SDK
     * @param maxProcState the process state at or below which to preserve
     *                     processes, or {@code -1} to ignore the process state
     */
    void killAllBackgroundProcessesExcept(int minTargetSdk, int maxProcState) {
        if (checkCallingPermission(android.Manifest.permission.KILL_BACKGROUND_PROCESSES)
                != PackageManager.PERMISSION_GRANTED) {
            final String msg = "Permission Denial: killAllBackgroundProcessesExcept() from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.KILL_BACKGROUND_PROCESSES;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        final long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                mProcessList.killAllBackgroundProcessesExceptLocked(minTargetSdk, maxProcState);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void forceStopPackage(final String packageName, int userId) {
        if (checkCallingPermission(android.Manifest.permission.FORCE_STOP_PACKAGES)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: forceStopPackage() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.FORCE_STOP_PACKAGES;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        final int callingPid = Binder.getCallingPid();
        userId = mUserController.handleIncomingUser(callingPid, Binder.getCallingUid(),
                userId, true, ALLOW_FULL_ONLY, "forceStopPackage", null);
        long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            synchronized(this) {
                int[] users = userId == UserHandle.USER_ALL
                        ? mUserController.getUsers() : new int[] { userId };
                for (int user : users) {
                    if (getPackageManagerInternalLocked().isPackageStateProtected(
                            packageName, user)) {
                        Slog.w(TAG, "Ignoring request to force stop protected package "
                                + packageName + " u" + user);
                        return;
                    }

                    int pkgUid = -1;
                    try {
                        pkgUid = pm.getPackageUid(packageName, MATCH_DEBUG_TRIAGED_MISSING,
                                user);
                    } catch (RemoteException e) {
                    }
                    if (pkgUid == -1) {
                        Slog.w(TAG, "Invalid packageName: " + packageName);
                        continue;
                    }
                    try {
                        pm.setPackageStoppedState(packageName, true, user);
                    } catch (RemoteException e) {
                    } catch (IllegalArgumentException e) {
                        Slog.w(TAG, "Failed trying to unstop package "
                                + packageName + ": " + e);
                    }
                    if (mUserController.isUserRunning(user, 0)) {
                        forceStopPackageLocked(packageName, pkgUid, "from pid " + callingPid);
                        finishForceStopPackageLocked(packageName, pkgUid);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void addPackageDependency(String packageName) {
        synchronized (this) {
            int callingPid = Binder.getCallingPid();
            if (callingPid == myPid()) {
                //  Yeah, um, no.
                return;
            }
            ProcessRecord proc;
            synchronized (mPidsSelfLocked) {
                proc = mPidsSelfLocked.get(Binder.getCallingPid());
            }
            if (proc != null) {
                if (proc.pkgDeps == null) {
                    proc.pkgDeps = new ArraySet<String>(1);
                }
                proc.pkgDeps.add(packageName);
            }
        }
    }

    /*
     * The pkg name and app id have to be specified.
     */
    @Override
    public void killApplication(String pkg, int appId, int userId, String reason) {
        if (pkg == null) {
            return;
        }
        // Make sure the uid is valid.
        if (appId < 0) {
            Slog.w(TAG, "Invalid appid specified for pkg : " + pkg);
            return;
        }
        int callerUid = Binder.getCallingUid();
        // Only the system server can kill an application
        if (UserHandle.getAppId(callerUid) == SYSTEM_UID) {
            // Post an aysnc message to kill the application
            Message msg = mHandler.obtainMessage(KILL_APPLICATION_MSG);
            msg.arg1 = appId;
            msg.arg2 = userId;
            Bundle bundle = new Bundle();
            bundle.putString("pkg", pkg);
            bundle.putString("reason", reason);
            msg.obj = bundle;
            mHandler.sendMessage(msg);
        } else {
            throw new SecurityException(callerUid + " cannot kill pkg: " +
                    pkg);
        }
    }

    @Override
    public void closeSystemDialogs(String reason) {
        mAtmInternal.closeSystemDialogs(reason);
    }

    @Override
    public Debug.MemoryInfo[] getProcessMemoryInfo(int[] pids) {
        enforceNotIsolatedCaller("getProcessMemoryInfo");

        final long now = SystemClock.uptimeMillis();
        final long lastNow = now - mConstants.MEMORY_INFO_THROTTLE_TIME;

        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        final boolean allUsers = ActivityManager.checkUidPermission(INTERACT_ACROSS_USERS_FULL,
                callingUid) == PackageManager.PERMISSION_GRANTED;
        // Check REAL_GET_TASKS to see if they are allowed to access other uids
        final boolean allUids = mAtmInternal.isGetTasksAllowed(
                "getProcessMemoryInfo", callingPid, callingUid);

        Debug.MemoryInfo[] infos = new Debug.MemoryInfo[pids.length];
        for (int i=pids.length-1; i>=0; i--) {
            infos[i] = new Debug.MemoryInfo();
            final ProcessRecord proc;
            final int oomAdj;
            synchronized (this) {
                synchronized (mPidsSelfLocked) {
                    proc = mPidsSelfLocked.get(pids[i]);
                    oomAdj = proc != null ? proc.setAdj : 0;
                }
            }
            final int targetUid = (proc != null) ? proc.uid : -1;
            final int targetUserId = (proc != null) ? UserHandle.getUserId(targetUid) : -1;

            if (callingUid != targetUid) {
                if (!allUids) {
                    continue; // Not allowed to see other UIDs.
                }

                if (!allUsers && (targetUserId != callingUserId)) {
                    continue; // Not allowed to see other users.
                }
            }
            if (proc != null && proc.lastMemInfoTime >= lastNow && proc.lastMemInfo != null) {
                // It hasn't been long enough that we want to take another sample; return
                // the last one.
                infos[i].set(proc.lastMemInfo);
                continue;
            }
            final long startTime = SystemClock.currentThreadTimeMillis();
            final Debug.MemoryInfo memInfo = new Debug.MemoryInfo();
            Debug.getMemoryInfo(pids[i], memInfo);
            final long endTime = SystemClock.currentThreadTimeMillis();
            infos[i].set(memInfo);
            if (proc != null) {
                synchronized (this) {
                    proc.lastMemInfo = memInfo;
                    proc.lastMemInfoTime = SystemClock.uptimeMillis();
                    if (proc.thread != null && proc.setAdj == oomAdj) {
                        // Record this for posterity if the process has been stable.
                        proc.baseProcessTracker.addPss(infos[i].getTotalPss(),
                                infos[i].getTotalUss(), infos[i].getTotalRss(), false,
                                ProcessStats.ADD_PSS_EXTERNAL_SLOW, endTime - startTime,
                                proc.pkgList.mPkgList);
                        for (int ipkg = proc.pkgList.size() - 1; ipkg >= 0; ipkg--) {
                            ProcessStats.ProcessStateHolder holder = proc.pkgList.valueAt(ipkg);
                            StatsLog.write(StatsLog.PROCESS_MEMORY_STAT_REPORTED,
                                    proc.info.uid,
                                    holder.state.getName(),
                                    holder.state.getPackage(),
                                    infos[i].getTotalPss(),
                                    infos[i].getTotalUss(),
                                    infos[i].getTotalRss(),
                                    ProcessStats.ADD_PSS_EXTERNAL_SLOW,
                                    endTime-startTime,
                                    holder.appVersion);
                        }
                    }
                }
            }
        }
        return infos;
    }

    @Override
    public long[] getProcessPss(int[] pids) {
        enforceNotIsolatedCaller("getProcessPss");

        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(callingUid);
        final boolean allUsers = ActivityManager.checkUidPermission(INTERACT_ACROSS_USERS_FULL,
                callingUid) == PackageManager.PERMISSION_GRANTED;
        // Check REAL_GET_TASKS to see if they are allowed to access other uids
        final boolean allUids = mAtmInternal.isGetTasksAllowed(
                "getProcessPss", callingPid, callingUid);

        long[] pss = new long[pids.length];
        for (int i=pids.length-1; i>=0; i--) {
            ProcessRecord proc;
            int oomAdj;
            synchronized (this) {
                synchronized (mPidsSelfLocked) {
                    proc = mPidsSelfLocked.get(pids[i]);
                    oomAdj = proc != null ? proc.setAdj : 0;
                }
            }
            if (!allUids || (!allUsers && UserHandle.getUserId(proc.uid) != userId)) {
                // The caller is not allow to get information about this other process...
                // just leave it empty.
                continue;
            }
            long[] tmpUss = new long[3];
            long startTime = SystemClock.currentThreadTimeMillis();
            pss[i] = Debug.getPss(pids[i], tmpUss, null);
            long endTime = SystemClock.currentThreadTimeMillis();
            if (proc != null) {
                synchronized (this) {
                    if (proc.thread != null && proc.setAdj == oomAdj) {
                        // Record this for posterity if the process has been stable.
                        proc.baseProcessTracker.addPss(pss[i], tmpUss[0], tmpUss[2], false,
                                ProcessStats.ADD_PSS_EXTERNAL, endTime-startTime, proc.pkgList.mPkgList);
                        for (int ipkg = proc.pkgList.size() - 1; ipkg >= 0; ipkg--) {
                            ProcessStats.ProcessStateHolder holder = proc.pkgList.valueAt(ipkg);
                            StatsLog.write(StatsLog.PROCESS_MEMORY_STAT_REPORTED,
                                    proc.info.uid,
                                    holder.state.getName(),
                                    holder.state.getPackage(),
                                    pss[i], tmpUss[0], tmpUss[2],
                                    ProcessStats.ADD_PSS_EXTERNAL, endTime-startTime,
                                    holder.appVersion);
                        }
                    }
                }
            }
        }
        return pss;
    }

    @Override
    public void killApplicationProcess(String processName, int uid) {
        if (processName == null) {
            return;
        }

        int callerUid = Binder.getCallingUid();
        // Only the system server can kill an application
        if (callerUid == SYSTEM_UID) {
            synchronized (this) {
                ProcessRecord app = getProcessRecordLocked(processName, uid, true);
                if (app != null && app.thread != null) {
                    try {
                        app.thread.scheduleSuicide();
                    } catch (RemoteException e) {
                        // If the other end already died, then our work here is done.
                    }
                } else {
                    Slog.w(TAG, "Process/uid not found attempting kill of "
                            + processName + " / " + uid);
                }
            }
        } else {
            throw new SecurityException(callerUid + " cannot kill app process: " +
                    processName);
        }
    }

    @GuardedBy("this")
    private void forceStopPackageLocked(final String packageName, int uid, String reason) {
        forceStopPackageLocked(packageName, UserHandle.getAppId(uid), false,
                false, true, false, false, UserHandle.getUserId(uid), reason);
    }

    @GuardedBy("this")
    private void finishForceStopPackageLocked(final String packageName, int uid) {
        Intent intent = new Intent(Intent.ACTION_PACKAGE_RESTARTED,
                Uri.fromParts("package", packageName, null));
        if (!mProcessesReady) {
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                    | Intent.FLAG_RECEIVER_FOREGROUND);
        }
        intent.putExtra(Intent.EXTRA_UID, uid);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, UserHandle.getUserId(uid));
        broadcastIntentLocked(null, null, intent,
                null, null, 0, null, null, null, OP_NONE,
                null, false, false, MY_PID, SYSTEM_UID, Binder.getCallingUid(),
                Binder.getCallingPid(), UserHandle.getUserId(uid));
    }

    private void cleanupDisabledPackageComponentsLocked(
            String packageName, int userId, String[] changedClasses) {

        Set<String> disabledClasses = null;
        boolean packageDisabled = false;
        IPackageManager pm = AppGlobals.getPackageManager();

        if (changedClasses == null) {
            // Nothing changed...
            return;
        }

        // Determine enable/disable state of the package and its components.
        int enabled = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        for (int i = changedClasses.length - 1; i >= 0; i--) {
            final String changedClass = changedClasses[i];

            if (changedClass.equals(packageName)) {
                try {
                    // Entire package setting changed
                    enabled = pm.getApplicationEnabledSetting(packageName,
                            (userId != UserHandle.USER_ALL) ? userId : UserHandle.USER_SYSTEM);
                } catch (Exception e) {
                    // No such package/component; probably racing with uninstall.  In any
                    // event it means we have nothing further to do here.
                    return;
                }
                packageDisabled = enabled != PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        && enabled != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
                if (packageDisabled) {
                    // Entire package is disabled.
                    // No need to continue to check component states.
                    disabledClasses = null;
                    break;
                }
            } else {
                try {
                    enabled = pm.getComponentEnabledSetting(
                            new ComponentName(packageName, changedClass),
                            (userId != UserHandle.USER_ALL) ? userId : UserHandle.USER_SYSTEM);
                } catch (Exception e) {
                    // As above, probably racing with uninstall.
                    return;
                }
                if (enabled != PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        && enabled != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                    if (disabledClasses == null) {
                        disabledClasses = new ArraySet<>(changedClasses.length);
                    }
                    disabledClasses.add(changedClass);
                }
            }
        }

        if (!packageDisabled && disabledClasses == null) {
            // Nothing to do here...
            return;
        }

        mAtmInternal.cleanupDisabledPackageComponents(
                packageName, disabledClasses, userId, mBooted);

        // Clean-up disabled services.
        mServices.bringDownDisabledPackageServicesLocked(
                packageName, disabledClasses, userId, false /* evenPersistent */, true /* doIt */);

        // Clean-up disabled providers.
        ArrayList<ContentProviderRecord> providers = new ArrayList<>();
        mProviderMap.collectPackageProvidersLocked(
                packageName, disabledClasses, true, false, userId, providers);
        for (int i = providers.size() - 1; i >= 0; i--) {
            removeDyingProviderLocked(null, providers.get(i), true);
        }

        // Clean-up disabled broadcast receivers.
        for (int i = mBroadcastQueues.length - 1; i >= 0; i--) {
            mBroadcastQueues[i].cleanupDisabledPackageReceiversLocked(
                    packageName, disabledClasses, userId, true);
        }

    }

    final boolean clearBroadcastQueueForUserLocked(int userId) {
        boolean didSomething = false;
        for (int i = mBroadcastQueues.length - 1; i >= 0; i--) {
            didSomething |= mBroadcastQueues[i].cleanupDisabledPackageReceiversLocked(
                    null, null, userId, true);
        }
        return didSomething;
    }

    @GuardedBy("this")
    final boolean forceStopPackageLocked(String packageName, int appId,
            boolean callerWillRestart, boolean purgeCache, boolean doit,
            boolean evenPersistent, boolean uninstalling, int userId, String reason) {
        int i;

        if (userId == UserHandle.USER_ALL && packageName == null) {
            Slog.w(TAG, "Can't force stop all processes of all users, that is insane!");
        }

        if (appId < 0 && packageName != null) {
            try {
                appId = UserHandle.getAppId(AppGlobals.getPackageManager()
                        .getPackageUid(packageName, MATCH_DEBUG_TRIAGED_MISSING, 0));
            } catch (RemoteException e) {
            }
        }

        if (doit) {
            if (packageName != null) {
                Slog.i(TAG, "Force stopping " + packageName + " appid=" + appId
                        + " user=" + userId + ": " + reason);
            } else {
                Slog.i(TAG, "Force stopping u" + userId + ": " + reason);
            }

            mAppErrors.resetProcessCrashTimeLocked(packageName == null, appId, userId);
        }

        boolean didSomething = mProcessList.killPackageProcessesLocked(packageName, appId, userId,
                ProcessList.INVALID_ADJ, callerWillRestart, true /* allowRestart */, doit,
                evenPersistent, true /* setRemoved */,
                packageName == null ? ("stop user " + userId) : ("stop " + packageName));

        didSomething |=
                mAtmInternal.onForceStopPackage(packageName, doit, evenPersistent, userId);

        if (mServices.bringDownDisabledPackageServicesLocked(
                packageName, null /* filterByClasses */, userId, evenPersistent, doit)) {
            if (!doit) {
                return true;
            }
            didSomething = true;
        }

        if (packageName == null) {
            // Remove all sticky broadcasts from this user.
            mStickyBroadcasts.remove(userId);
        }

        ArrayList<ContentProviderRecord> providers = new ArrayList<>();
        if (mProviderMap.collectPackageProvidersLocked(packageName, null, doit, evenPersistent,
                userId, providers)) {
            if (!doit) {
                return true;
            }
            didSomething = true;
        }
        for (i = providers.size() - 1; i >= 0; i--) {
            removeDyingProviderLocked(null, providers.get(i), true);
        }

        // Remove transient permissions granted from/to this package/user
        mUgmInternal.removeUriPermissionsForPackage(packageName, userId, false, false);

        if (doit) {
            for (i = mBroadcastQueues.length - 1; i >= 0; i--) {
                didSomething |= mBroadcastQueues[i].cleanupDisabledPackageReceiversLocked(
                        packageName, null, userId, doit);
            }
        }

        if (packageName == null || uninstalling) {
            didSomething |= mPendingIntentController.removePendingIntentsForPackage(
                    packageName, userId, appId, doit);
        }

        if (doit) {
            if (purgeCache && packageName != null) {
                AttributeCache ac = AttributeCache.instance();
                if (ac != null) {
                    ac.removePackage(packageName);
                }
            }
            if (mBooted) {
                mAtmInternal.resumeTopActivities(true /* scheduleIdle */);
            }
        }

        return didSomething;
    }

    @GuardedBy("this")
    private final void processContentProviderPublishTimedOutLocked(ProcessRecord app) {
        cleanupAppInLaunchingProvidersLocked(app, true);
        mProcessList.removeProcessLocked(app, false, true, "timeout publishing content providers");
    }

    @GuardedBy("this")
    private final void processStartTimedOutLocked(ProcessRecord app) {
        final int pid = app.pid;
        boolean gone = mPidsSelfLocked.removeIfNoThread(pid);

        if (gone) {
            Slog.w(TAG, "Process " + app + " failed to attach");
            EventLog.writeEvent(EventLogTags.AM_PROCESS_START_TIMEOUT, app.userId,
                    pid, app.uid, app.processName);
            mProcessList.removeProcessNameLocked(app.processName, app.uid);
            mAtmInternal.clearHeavyWeightProcessIfEquals(app.getWindowProcessController());
            mBatteryStatsService.noteProcessFinish(app.processName, app.info.uid);
            // Take care of any launching providers waiting for this process.
            cleanupAppInLaunchingProvidersLocked(app, true);
            // Take care of any services that are waiting for the process.
            mServices.processStartTimedOutLocked(app);
            app.kill("start timeout", true);
            if (app.isolated) {
                mBatteryStatsService.removeIsolatedUid(app.uid, app.info.uid);
            }
            removeLruProcessLocked(app);
            final BackupRecord backupTarget = mBackupTargets.get(app.userId);
            if (backupTarget != null && backupTarget.app.pid == pid) {
                Slog.w(TAG, "Unattached app died before backup, skipping");
                mHandler.post(new Runnable() {
                @Override
                    public void run(){
                        try {
                            IBackupManager bm = IBackupManager.Stub.asInterface(
                                    ServiceManager.getService(Context.BACKUP_SERVICE));
                            bm.agentDisconnectedForUser(app.userId, app.info.packageName);
                        } catch (RemoteException e) {
                            // Can't happen; the backup manager is local
                        }
                    }
                });
            }
            if (isPendingBroadcastProcessLocked(pid)) {
                Slog.w(TAG, "Unattached app died before broadcast acknowledged, skipping");
                skipPendingBroadcastLocked(pid);
            }
        } else {
            Slog.w(TAG, "Spurious process start timeout - pid not known for " + app);
        }
    }

    @GuardedBy("this")
    private final boolean attachApplicationLocked(IApplicationThread thread,
            int pid, int callingUid, long startSeq) {

        // Find the application record that is being attached...  either via
        // the pid if we are running in multiple processes, or just pull the
        // next app record if we are emulating process with anonymous threads.
        ProcessRecord app;
        long startTime = SystemClock.uptimeMillis();
        long bindApplicationTimeMillis;
        if (pid != MY_PID && pid >= 0) {
            synchronized (mPidsSelfLocked) {
                app = mPidsSelfLocked.get(pid);
            }
        } else {
            app = null;
        }

        // It's possible that process called attachApplication before we got a chance to
        // update the internal state.
        if (app == null && startSeq > 0) {
            final ProcessRecord pending = mProcessList.mPendingStarts.get(startSeq);
            if (pending != null && pending.startUid == callingUid
                    && mProcessList.handleProcessStartedLocked(pending, pid, pending
                            .isUsingWrapper(),
                            startSeq, true)) {
                app = pending;
            }
        }

        if (app == null) {
            Slog.w(TAG, "No pending application record for pid " + pid
                    + " (IApplicationThread " + thread + "); dropping process");
            EventLog.writeEvent(EventLogTags.AM_DROP_PROCESS, pid);
            if (pid > 0 && pid != MY_PID) {
                killProcessQuiet(pid);
                //TODO: killProcessGroup(app.info.uid, pid);
            } else {
                try {
                    thread.scheduleExit();
                } catch (Exception e) {
                    // Ignore exceptions.
                }
            }
            return false;
        }

        // If this application record is still attached to a previous
        // process, clean it up now.
        if (app.thread != null) {
            handleAppDiedLocked(app, true, true);
        }

        // Tell the process all about itself.

        if (DEBUG_ALL) Slog.v(
                TAG, "Binding process pid " + pid + " to record " + app);

        final String processName = app.processName;
        try {
            AppDeathRecipient adr = new AppDeathRecipient(
                    app, pid, thread);
            thread.asBinder().linkToDeath(adr, 0);
            app.deathRecipient = adr;
        } catch (RemoteException e) {
            app.resetPackageList(mProcessStats);
            mProcessList.startProcessLocked(app, "link fail", processName);
            return false;
        }

        EventLog.writeEvent(EventLogTags.AM_PROC_BOUND, app.userId, app.pid, app.processName);

        app.curAdj = app.setAdj = app.verifiedAdj = ProcessList.INVALID_ADJ;
        app.setCurrentSchedulingGroup(app.setSchedGroup = ProcessList.SCHED_GROUP_DEFAULT);
        app.forcingToImportant = null;
        updateProcessForegroundLocked(app, false, 0, false);
        app.hasShownUi = false;
        app.setDebugging(false);
        app.cached = false;
        app.killedByAm = false;
        app.killed = false;


        // We carefully use the same state that PackageManager uses for
        // filtering, since we use this flag to decide if we need to install
        // providers when user is unlocked later
        app.unlocked = StorageManager.isUserKeyUnlocked(app.userId);

        mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);

        boolean normalMode = mProcessesReady || isAllowedWhileBooting(app.info);
        List<ProviderInfo> providers = normalMode ? generateApplicationProvidersLocked(app) : null;

        if (providers != null && checkAppInLaunchingProvidersLocked(app)) {
            Message msg = mHandler.obtainMessage(CONTENT_PROVIDER_PUBLISH_TIMEOUT_MSG);
            msg.obj = app;
            mHandler.sendMessageDelayed(msg, CONTENT_PROVIDER_PUBLISH_TIMEOUT);
        }

        checkTime(startTime, "attachApplicationLocked: before bindApplication");

        if (!normalMode) {
            Slog.i(TAG, "Launching preboot mode app: " + app);
        }

        if (DEBUG_ALL) Slog.v(
            TAG, "New app record " + app
            + " thread=" + thread.asBinder() + " pid=" + pid);
        final BackupRecord backupTarget = mBackupTargets.get(app.userId);
        try {
            int testMode = ApplicationThreadConstants.DEBUG_OFF;
            if (mDebugApp != null && mDebugApp.equals(processName)) {
                testMode = mWaitForDebugger
                    ? ApplicationThreadConstants.DEBUG_WAIT
                    : ApplicationThreadConstants.DEBUG_ON;
                app.setDebugging(true);
                if (mDebugTransient) {
                    mDebugApp = mOrigDebugApp;
                    mWaitForDebugger = mOrigWaitForDebugger;
                }
            }

            boolean enableTrackAllocation = false;
            if (mTrackAllocationApp != null && mTrackAllocationApp.equals(processName)) {
                enableTrackAllocation = true;
                mTrackAllocationApp = null;
            }

            // If the app is being launched for restore or full backup, set it up specially
            boolean isRestrictedBackupMode = false;
            if (backupTarget != null && backupTarget.appInfo.packageName.equals(processName)) {
                isRestrictedBackupMode = backupTarget.appInfo.uid >= FIRST_APPLICATION_UID
                        && ((backupTarget.backupMode == BackupRecord.RESTORE)
                                || (backupTarget.backupMode == BackupRecord.RESTORE_FULL)
                                || (backupTarget.backupMode == BackupRecord.BACKUP_FULL));
            }

            final ActiveInstrumentation instr = app.getActiveInstrumentation();

            if (instr != null) {
                notifyPackageUse(instr.mClass.getPackageName(),
                                 PackageManager.NOTIFY_PACKAGE_USE_INSTRUMENTATION);
            }
            if (DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION, "Binding proc "
                    + processName + " with config "
                    + app.getWindowProcessController().getConfiguration());
            ApplicationInfo appInfo = instr != null ? instr.mTargetInfo : app.info;
            app.compat = compatibilityInfoForPackage(appInfo);

            ProfilerInfo profilerInfo = null;
            String preBindAgent = null;
            if (mProfileData.getProfileApp() != null
                    && mProfileData.getProfileApp().equals(processName)) {
                mProfileData.setProfileProc(app);
                if (mProfileData.getProfilerInfo() != null) {
                    // Send a profiler info object to the app if either a file is given, or
                    // an agent should be loaded at bind-time.
                    boolean needsInfo = mProfileData.getProfilerInfo().profileFile != null
                            || mProfileData.getProfilerInfo().attachAgentDuringBind;
                    profilerInfo = needsInfo
                            ? new ProfilerInfo(mProfileData.getProfilerInfo()) : null;
                    if (mProfileData.getProfilerInfo().agent != null) {
                        preBindAgent = mProfileData.getProfilerInfo().agent;
                    }
                }
            } else if (instr != null && instr.mProfileFile != null) {
                profilerInfo = new ProfilerInfo(instr.mProfileFile, null, 0, false, false,
                        null, false);
            }
            if (mAppAgentMap != null && mAppAgentMap.containsKey(processName)) {
                // We need to do a debuggable check here. See setAgentApp for why the check is
                // postponed to here.
                if ((app.info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                    String agent = mAppAgentMap.get(processName);
                    // Do not overwrite already requested agent.
                    if (profilerInfo == null) {
                        profilerInfo = new ProfilerInfo(null, null, 0, false, false,
                                mAppAgentMap.get(processName), true);
                    } else if (profilerInfo.agent == null) {
                        profilerInfo = profilerInfo.setAgent(mAppAgentMap.get(processName), true);
                    }
                }
            }

            if (profilerInfo != null && profilerInfo.profileFd != null) {
                profilerInfo.profileFd = profilerInfo.profileFd.dup();
                if (TextUtils.equals(mProfileData.getProfileApp(), processName)
                        && mProfileData.getProfilerInfo() != null) {
                    clearProfilerLocked();
                }
            }

            // We deprecated Build.SERIAL and it is not accessible to
            // Instant Apps and target APIs higher than O MR1. Since access to the serial
            // is now behind a permission we push down the value.
            final String buildSerial = (!appInfo.isInstantApp()
                    && appInfo.targetSdkVersion < Build.VERSION_CODES.P)
                            ? sTheRealBuildSerial : Build.UNKNOWN;

            // Check if this is a secondary process that should be incorporated into some
            // currently active instrumentation.  (Note we do this AFTER all of the profiling
            // stuff above because profiling can currently happen only in the primary
            // instrumentation process.)
            if (mActiveInstrumentation.size() > 0 && instr == null) {
                for (int i = mActiveInstrumentation.size() - 1;
                        i >= 0 && app.getActiveInstrumentation() == null; i--) {
                    ActiveInstrumentation aInstr = mActiveInstrumentation.get(i);
                    if (!aInstr.mFinished && aInstr.mTargetInfo.uid == app.uid) {
                        if (aInstr.mTargetProcesses.length == 0) {
                            // This is the wildcard mode, where every process brought up for
                            // the target instrumentation should be included.
                            if (aInstr.mTargetInfo.packageName.equals(app.info.packageName)) {
                                app.setActiveInstrumentation(aInstr);
                                aInstr.mRunningProcesses.add(app);
                            }
                        } else {
                            for (String proc : aInstr.mTargetProcesses) {
                                if (proc.equals(app.processName)) {
                                    app.setActiveInstrumentation(aInstr);
                                    aInstr.mRunningProcesses.add(app);
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            // If we were asked to attach an agent on startup, do so now, before we're binding
            // application code.
            if (preBindAgent != null) {
                thread.attachAgent(preBindAgent);
            }


            // Figure out whether the app needs to run in autofill compat mode.
            AutofillOptions autofillOptions = null;
            if (UserHandle.getAppId(app.info.uid) >= Process.FIRST_APPLICATION_UID) {
                final AutofillManagerInternal afm = LocalServices.getService(
                        AutofillManagerInternal.class);
                if (afm != null) {
                    autofillOptions = afm.getAutofillOptions(
                            app.info.packageName, app.info.versionCode, app.userId);
                }
            }
            ContentCaptureOptions contentCaptureOptions = null;
            if (UserHandle.getAppId(app.info.uid) >= Process.FIRST_APPLICATION_UID) {
                final ContentCaptureManagerInternal ccm =
                        LocalServices.getService(ContentCaptureManagerInternal.class);
                if (ccm != null) {
                    contentCaptureOptions = ccm.getOptionsForPackage(app.userId,
                            app.info.packageName);
                }
            }

            checkTime(startTime, "attachApplicationLocked: immediately before bindApplication");
            bindApplicationTimeMillis = SystemClock.elapsedRealtime();
            mAtmInternal.preBindApplication(app.getWindowProcessController());
            final ActiveInstrumentation instr2 = app.getActiveInstrumentation();
            if (app.isolatedEntryPoint != null) {
                // This is an isolated process which should just call an entry point instead of
                // being bound to an application.
                thread.runIsolatedEntryPoint(app.isolatedEntryPoint, app.isolatedEntryPointArgs);
            } else if (instr2 != null) {
                thread.bindApplication(processName, appInfo, providers,
                        instr2.mClass,
                        profilerInfo, instr2.mArguments,
                        instr2.mWatcher,
                        instr2.mUiAutomationConnection, testMode,
                        mBinderTransactionTrackingEnabled, enableTrackAllocation,
                        isRestrictedBackupMode || !normalMode, app.isPersistent(),
                        new Configuration(app.getWindowProcessController().getConfiguration()),
                        app.compat, getCommonServicesLocked(app.isolated),
                        mCoreSettingsObserver.getCoreSettingsLocked(),
                        buildSerial, autofillOptions, contentCaptureOptions);
            } else {
                thread.bindApplication(processName, appInfo, providers, null, profilerInfo,
                        null, null, null, testMode,
                        mBinderTransactionTrackingEnabled, enableTrackAllocation,
                        isRestrictedBackupMode || !normalMode, app.isPersistent(),
                        new Configuration(app.getWindowProcessController().getConfiguration()),
                        app.compat, getCommonServicesLocked(app.isolated),
                        mCoreSettingsObserver.getCoreSettingsLocked(),
                        buildSerial, autofillOptions, contentCaptureOptions);
            }
            if (profilerInfo != null) {
                profilerInfo.closeFd();
                profilerInfo = null;
            }

            // Make app active after binding application or client may be running requests (e.g
            // starting activities) before it is ready.
            app.makeActive(thread, mProcessStats);
            checkTime(startTime, "attachApplicationLocked: immediately after bindApplication");
            mProcessList.updateLruProcessLocked(app, false, null);
            checkTime(startTime, "attachApplicationLocked: after updateLruProcessLocked");
            app.lastRequestedGc = app.lastLowMemory = SystemClock.uptimeMillis();
        } catch (Exception e) {
            // todo: Yikes!  What should we do?  For now we will try to
            // start another process, but that could easily get us in
            // an infinite loop of restarting processes...
            Slog.wtf(TAG, "Exception thrown during bind of " + app, e);

            app.resetPackageList(mProcessStats);
            app.unlinkDeathRecipient();
            mProcessList.startProcessLocked(app, "bind fail", processName);
            return false;
        }

        // Remove this record from the list of starting applications.
        mPersistentStartingProcesses.remove(app);
        if (DEBUG_PROCESSES && mProcessesOnHold.contains(app)) Slog.v(TAG_PROCESSES,
                "Attach application locked removing on hold: " + app);
        mProcessesOnHold.remove(app);

        boolean badApp = false;
        boolean didSomething = false;

        // See if the top visible activity is waiting to run in this process...
        if (normalMode) {
            try {
                didSomething = mAtmInternal.attachApplication(app.getWindowProcessController());
            } catch (Exception e) {
                Slog.wtf(TAG, "Exception thrown launching activities in " + app, e);
                badApp = true;
            }
        }

        // Find any services that should be running in this process...
        if (!badApp) {
            try {
                didSomething |= mServices.attachApplicationLocked(app, processName);
                checkTime(startTime, "attachApplicationLocked: after mServices.attachApplicationLocked");
            } catch (Exception e) {
                Slog.wtf(TAG, "Exception thrown starting services in " + app, e);
                badApp = true;
            }
        }

        // Check if a next-broadcast receiver is in this process...
        if (!badApp && isPendingBroadcastProcessLocked(pid)) {
            try {
                didSomething |= sendPendingBroadcastsLocked(app);
                checkTime(startTime, "attachApplicationLocked: after sendPendingBroadcastsLocked");
            } catch (Exception e) {
                // If the app died trying to launch the receiver we declare it 'bad'
                Slog.wtf(TAG, "Exception thrown dispatching broadcasts in " + app, e);
                badApp = true;
            }
        }

        // Check whether the next backup agent is in this process...
        if (!badApp && backupTarget != null && backupTarget.app == app) {
            if (DEBUG_BACKUP) Slog.v(TAG_BACKUP,
                    "New app is backup target, launching agent for " + app);
            notifyPackageUse(backupTarget.appInfo.packageName,
                             PackageManager.NOTIFY_PACKAGE_USE_BACKUP);
            try {
                thread.scheduleCreateBackupAgent(backupTarget.appInfo,
                        compatibilityInfoForPackage(backupTarget.appInfo),
                        backupTarget.backupMode, backupTarget.userId);
            } catch (Exception e) {
                Slog.wtf(TAG, "Exception thrown creating backup agent in " + app, e);
                badApp = true;
            }
        }

        if (badApp) {
            app.kill("error during init", true);
            handleAppDiedLocked(app, false, true);
            return false;
        }

        if (!didSomething) {
            updateOomAdjLocked();
            checkTime(startTime, "attachApplicationLocked: after updateOomAdjLocked");
        }

        StatsLog.write(
                StatsLog.PROCESS_START_TIME,
                app.info.uid,
                app.pid,
                app.info.packageName,
                StatsLog.PROCESS_START_TIME__TYPE__COLD,
                app.startTime,
                (int) (bindApplicationTimeMillis - app.startTime),
                (int) (SystemClock.elapsedRealtime() - app.startTime),
                app.hostingType,
                (app.hostingNameStr != null ? app.hostingNameStr : ""));
        return true;
    }

    @Override
    public final void attachApplication(IApplicationThread thread, long startSeq) {
        synchronized (this) {
            int callingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();
            final long origId = Binder.clearCallingIdentity();
            attachApplicationLocked(thread, callingPid, callingUid, startSeq);
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void showBootMessage(final CharSequence msg, final boolean always) {
        if (Binder.getCallingUid() != myUid()) {
            throw new SecurityException();
        }
        mWindowManager.showBootMessage(msg, always);
    }

    final void finishBooting() {
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "FinishBooting");

        synchronized (this) {
            if (!mBootAnimationComplete) {
                mCallFinishBooting = true;
                return;
            }
            mCallFinishBooting = false;
        }

        ArraySet<String> completedIsas = new ArraySet<String>();
        for (String abi : Build.SUPPORTED_ABIS) {
            ZYGOTE_PROCESS.establishZygoteConnectionForAbi(abi);
            final String instructionSet = VMRuntime.getInstructionSet(abi);
            if (!completedIsas.contains(instructionSet)) {
                try {
                    mInstaller.markBootComplete(VMRuntime.getInstructionSet(abi));
                } catch (InstallerException e) {
                    if (!VMRuntime.didPruneDalvikCache()) {
                        // This is technically not the right filter, as different zygotes may
                        // have made different pruning decisions. But the log is best effort,
                        // anyways.
                        Slog.w(TAG, "Unable to mark boot complete for abi: " + abi + " (" +
                                e.getMessage() +")");
                    }
                }
                completedIsas.add(instructionSet);
            }
        }

        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_QUERY_PACKAGE_RESTART);
        pkgFilter.addDataScheme("package");
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String[] pkgs = intent.getStringArrayExtra(Intent.EXTRA_PACKAGES);
                if (pkgs != null) {
                    for (String pkg : pkgs) {
                        synchronized (ActivityManagerService.this) {
                            if (forceStopPackageLocked(pkg, -1, false, false, false, false, false,
                                    0, "query restart")) {
                                setResultCode(Activity.RESULT_OK);
                                return;
                            }
                        }
                    }
                }
            }
        }, pkgFilter);

        IntentFilter dumpheapFilter = new IntentFilter();
        dumpheapFilter.addAction(DumpHeapActivity.ACTION_DELETE_DUMPHEAP);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getBooleanExtra(DumpHeapActivity.EXTRA_DELAY_DELETE, false)) {
                    mHandler.sendEmptyMessageDelayed(POST_DUMP_HEAP_NOTIFICATION_MSG, 5*60*1000);
                } else {
                    mHandler.sendEmptyMessage(POST_DUMP_HEAP_NOTIFICATION_MSG);
                }
            }
        }, dumpheapFilter);

        // Let system services know.
        mSystemServiceManager.startBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        synchronized (this) {
            // Ensure that any processes we had put on hold are now started
            // up.
            final int NP = mProcessesOnHold.size();
            if (NP > 0) {
                ArrayList<ProcessRecord> procs =
                    new ArrayList<ProcessRecord>(mProcessesOnHold);
                for (int ip=0; ip<NP; ip++) {
                    if (DEBUG_PROCESSES) Slog.v(TAG_PROCESSES, "Starting process on hold: "
                            + procs.get(ip));
                    mProcessList.startProcessLocked(procs.get(ip), "on-hold", null);
                }
            }
            if (mFactoryTest == FactoryTest.FACTORY_TEST_LOW_LEVEL) {
                return;
            }
            // Start looking for apps that are abusing wake locks.
            Message nmsg = mHandler.obtainMessage(CHECK_EXCESSIVE_POWER_USE_MSG);
            mHandler.sendMessageDelayed(nmsg, mConstants.POWER_CHECK_INTERVAL);
            // Tell anyone interested that we are done booting!
            SystemProperties.set("sys.boot_completed", "1");

            // And trigger dev.bootcomplete if we are not showing encryption progress
            if (!"trigger_restart_min_framework".equals(VoldProperties.decrypt().orElse(""))
                    || "".equals(VoldProperties.encrypt_progress().orElse(""))) {
                SystemProperties.set("dev.bootcomplete", "1");
            }
            mUserController.sendBootCompleted(
                    new IIntentReceiver.Stub() {
                        @Override
                        public void performReceive(Intent intent, int resultCode,
                                String data, Bundle extras, boolean ordered,
                                boolean sticky, int sendingUser) {
                            synchronized (ActivityManagerService.this) {
                                requestPssAllProcsLocked(SystemClock.uptimeMillis(), true, false);
                            }
                        }
                    });
            mUserController.scheduleStartProfiles();
        }

        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    @Override
    public void bootAnimationComplete() {
        final boolean callFinishBooting;
        synchronized (this) {
            callFinishBooting = mCallFinishBooting;
            mBootAnimationComplete = true;
        }
        if (callFinishBooting) {
            finishBooting();
        }
    }

    final void ensureBootCompleted() {
        boolean booting;
        boolean enableScreen;
        synchronized (this) {
            booting = mBooting;
            mBooting = false;
            enableScreen = !mBooted;
            mBooted = true;
        }

        if (booting) {
            finishBooting();
        }

        if (enableScreen) {
            mAtmInternal.enableScreenAfterBoot(mBooted);
        }
    }

    @Override
    public IIntentSender getIntentSender(int type,
            String packageName, IBinder token, String resultWho,
            int requestCode, Intent[] intents, String[] resolvedTypes,
            int flags, Bundle bOptions, int userId) {

        // NOTE: The service lock isn't held in this method because nothing in the method requires
        // the service lock to be held.

        enforceNotIsolatedCaller("getIntentSender");
        // Refuse possible leaked file descriptors
        if (intents != null) {
            if (intents.length < 1) {
                throw new IllegalArgumentException("Intents array length must be >= 1");
            }
            for (int i=0; i<intents.length; i++) {
                Intent intent = intents[i];
                if (intent != null) {
                    if (intent.hasFileDescriptors()) {
                        throw new IllegalArgumentException("File descriptors passed in Intent");
                    }
                    if (type == ActivityManager.INTENT_SENDER_BROADCAST &&
                            (intent.getFlags()&Intent.FLAG_RECEIVER_BOOT_UPGRADE) != 0) {
                        throw new IllegalArgumentException(
                                "Can't use FLAG_RECEIVER_BOOT_UPGRADE here");
                    }
                    intents[i] = new Intent(intent);
                }
            }
            if (resolvedTypes != null && resolvedTypes.length != intents.length) {
                throw new IllegalArgumentException(
                        "Intent array length does not match resolvedTypes length");
            }
        }
        if (bOptions != null) {
            if (bOptions.hasFileDescriptors()) {
                throw new IllegalArgumentException("File descriptors passed in options");
            }
        }

        int callingUid = Binder.getCallingUid();
        int origUserId = userId;
        userId = mUserController.handleIncomingUser(Binder.getCallingPid(), callingUid, userId,
                type == ActivityManager.INTENT_SENDER_BROADCAST,
                ALLOW_NON_FULL, "getIntentSender", null);
        if (origUserId == UserHandle.USER_CURRENT) {
            // We don't want to evaluate this until the pending intent is
            // actually executed.  However, we do want to always do the
            // security checking for it above.
            userId = UserHandle.USER_CURRENT;
        }
        try {
            if (callingUid != 0 && callingUid != SYSTEM_UID) {
                final int uid = AppGlobals.getPackageManager().getPackageUid(packageName,
                        MATCH_DEBUG_TRIAGED_MISSING, UserHandle.getUserId(callingUid));
                if (!UserHandle.isSameApp(callingUid, uid)) {
                    String msg = "Permission Denial: getIntentSender() from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + ", (need uid=" + uid + ")"
                        + " is not allowed to send as package " + packageName;
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                }
            }

            if (type == ActivityManager.INTENT_SENDER_ACTIVITY_RESULT) {
                return mAtmInternal.getIntentSender(type, packageName, callingUid, userId,
                        token, resultWho, requestCode, intents, resolvedTypes, flags, bOptions);
            }
            return mPendingIntentController.getIntentSender(type, packageName, callingUid,
                    userId, token, resultWho, requestCode, intents, resolvedTypes, flags,
                    bOptions);
        } catch (RemoteException e) {
            throw new SecurityException(e);
        }
    }

    @Override
    public int sendIntentSender(IIntentSender target, IBinder whitelistToken, int code,
            Intent intent, String resolvedType,
            IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
        if (target instanceof PendingIntentRecord) {
            return ((PendingIntentRecord)target).sendWithResult(code, intent, resolvedType,
                    whitelistToken, finishedReceiver, requiredPermission, options);
        } else {
            if (intent == null) {
                // Weird case: someone has given us their own custom IIntentSender, and now
                // they have someone else trying to send to it but of course this isn't
                // really a PendingIntent, so there is no base Intent, and the caller isn't
                // supplying an Intent... but we never want to dispatch a null Intent to
                // a receiver, so um...  let's make something up.
                Slog.wtf(TAG, "Can't use null intent with direct IIntentSender call");
                intent = new Intent(Intent.ACTION_MAIN);
            }
            try {
                target.send(code, intent, resolvedType, whitelistToken, null,
                        requiredPermission, options);
            } catch (RemoteException e) {
            }
            // Platform code can rely on getting a result back when the send is done, but if
            // this intent sender is from outside of the system we can't rely on it doing that.
            // So instead we don't give it the result receiver, and instead just directly
            // report the finish immediately.
            if (finishedReceiver != null) {
                try {
                    finishedReceiver.performReceive(intent, 0,
                            null, null, false, false, UserHandle.getCallingUserId());
                } catch (RemoteException e) {
                }
            }
            return 0;
        }
    }

    @Override
    public void cancelIntentSender(IIntentSender sender) {
        mPendingIntentController.cancelIntentSender(sender);
    }

    @Override
    public String getPackageForIntentSender(IIntentSender pendingResult) {
        if (!(pendingResult instanceof PendingIntentRecord)) {
            return null;
        }
        try {
            PendingIntentRecord res = (PendingIntentRecord)pendingResult;
            return res.key.packageName;
        } catch (ClassCastException e) {
        }
        return null;
    }

    @Override
    public void registerIntentSenderCancelListener(IIntentSender sender, IResultReceiver receiver) {
        if (!(sender instanceof PendingIntentRecord)) {
            return;
        }
        boolean isCancelled;
        synchronized(this) {
            PendingIntentRecord pendingIntent = (PendingIntentRecord) sender;
            isCancelled = pendingIntent.canceled;
            if (!isCancelled) {
                pendingIntent.registerCancelListenerLocked(receiver);
            }
        }
        if (isCancelled) {
            try {
                receiver.send(Activity.RESULT_CANCELED, null);
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public void unregisterIntentSenderCancelListener(IIntentSender sender,
            IResultReceiver receiver) {
        if (!(sender instanceof PendingIntentRecord)) {
            return;
        }
        synchronized(this) {
            ((PendingIntentRecord)sender).unregisterCancelListenerLocked(receiver);
        }
    }

    @Override
    public int getUidForIntentSender(IIntentSender sender) {
        if (sender instanceof PendingIntentRecord) {
            try {
                PendingIntentRecord res = (PendingIntentRecord)sender;
                return res.uid;
            } catch (ClassCastException e) {
            }
        }
        return -1;
    }

    @Override
    public boolean isIntentSenderTargetedToPackage(IIntentSender pendingResult) {
        if (!(pendingResult instanceof PendingIntentRecord)) {
            return false;
        }
        try {
            PendingIntentRecord res = (PendingIntentRecord)pendingResult;
            if (res.key.allIntents == null) {
                return false;
            }
            for (int i=0; i<res.key.allIntents.length; i++) {
                Intent intent = res.key.allIntents[i];
                if (intent.getPackage() != null && intent.getComponent() != null) {
                    return false;
                }
            }
            return true;
        } catch (ClassCastException e) {
        }
        return false;
    }

    @Override
    public boolean isIntentSenderAnActivity(IIntentSender pendingResult) {
        if (!(pendingResult instanceof PendingIntentRecord)) {
            return false;
        }
        try {
            PendingIntentRecord res = (PendingIntentRecord)pendingResult;
            if (res.key.type == ActivityManager.INTENT_SENDER_ACTIVITY) {
                return true;
            }
            return false;
        } catch (ClassCastException e) {
        }
        return false;
    }

    @Override
    public boolean isIntentSenderAForegroundService(IIntentSender pendingResult) {
        if (pendingResult instanceof PendingIntentRecord) {
            final PendingIntentRecord res = (PendingIntentRecord) pendingResult;
            return res.key.type == ActivityManager.INTENT_SENDER_FOREGROUND_SERVICE;
        }
        return false;
    }

    @Override
    public boolean isIntentSenderABroadcast(IIntentSender pendingResult) {
        if (pendingResult instanceof PendingIntentRecord) {
            final PendingIntentRecord res = (PendingIntentRecord) pendingResult;
            return res.key.type == ActivityManager.INTENT_SENDER_BROADCAST;
        }
        return false;
    }

    @Override
    public Intent getIntentForIntentSender(IIntentSender pendingResult) {
        enforceCallingPermission(Manifest.permission.GET_INTENT_SENDER_INTENT,
                "getIntentForIntentSender()");
        if (!(pendingResult instanceof PendingIntentRecord)) {
            return null;
        }
        try {
            PendingIntentRecord res = (PendingIntentRecord)pendingResult;
            return res.key.requestIntent != null ? new Intent(res.key.requestIntent) : null;
        } catch (ClassCastException e) {
        }
        return null;
    }

    @Override
    public String getTagForIntentSender(IIntentSender pendingResult, String prefix) {
        if (!(pendingResult instanceof PendingIntentRecord)) {
            return null;
        }
        try {
            PendingIntentRecord res = (PendingIntentRecord)pendingResult;
            synchronized (this) {
                return getTagForIntentSenderLocked(res, prefix);
            }
        } catch (ClassCastException e) {
        }
        return null;
    }

    String getTagForIntentSenderLocked(PendingIntentRecord res, String prefix) {
        final Intent intent = res.key.requestIntent;
        if (intent != null) {
            if (res.lastTag != null && res.lastTagPrefix == prefix && (res.lastTagPrefix == null
                    || res.lastTagPrefix.equals(prefix))) {
                return res.lastTag;
            }
            res.lastTagPrefix = prefix;
            final StringBuilder sb = new StringBuilder(128);
            if (prefix != null) {
                sb.append(prefix);
            }
            if (intent.getAction() != null) {
                sb.append(intent.getAction());
            } else if (intent.getComponent() != null) {
                intent.getComponent().appendShortString(sb);
            } else {
                sb.append("?");
            }
            return res.lastTag = sb.toString();
        }
        return null;
    }

    @Override
    public void setProcessLimit(int max) {
        enforceCallingPermission(android.Manifest.permission.SET_PROCESS_LIMIT,
                "setProcessLimit()");
        synchronized (this) {
            mConstants.setOverrideMaxCachedProcesses(max);
        }
        trimApplications();
    }

    @Override
    public int getProcessLimit() {
        synchronized (this) {
            return mConstants.getOverrideMaxCachedProcesses();
        }
    }

    void importanceTokenDied(ImportanceToken token) {
        synchronized (ActivityManagerService.this) {
            synchronized (mPidsSelfLocked) {
                ImportanceToken cur
                    = mImportantProcesses.get(token.pid);
                if (cur != token) {
                    return;
                }
                mImportantProcesses.remove(token.pid);
                ProcessRecord pr = mPidsSelfLocked.get(token.pid);
                if (pr == null) {
                    return;
                }
                pr.forcingToImportant = null;
                updateProcessForegroundLocked(pr, false, 0, false);
            }
            updateOomAdjLocked();
        }
    }

    @Override
    public void setProcessImportant(IBinder token, int pid, boolean isForeground, String reason) {
        enforceCallingPermission(android.Manifest.permission.SET_PROCESS_LIMIT,
                "setProcessImportant()");
        synchronized(this) {
            boolean changed = false;

            synchronized (mPidsSelfLocked) {
                ProcessRecord pr = mPidsSelfLocked.get(pid);
                if (pr == null && isForeground) {
                    Slog.w(TAG, "setProcessForeground called on unknown pid: " + pid);
                    return;
                }
                ImportanceToken oldToken = mImportantProcesses.get(pid);
                if (oldToken != null) {
                    oldToken.token.unlinkToDeath(oldToken, 0);
                    mImportantProcesses.remove(pid);
                    if (pr != null) {
                        pr.forcingToImportant = null;
                    }
                    changed = true;
                }
                if (isForeground && token != null) {
                    ImportanceToken newToken = new ImportanceToken(pid, token, reason) {
                        @Override
                        public void binderDied() {
                            importanceTokenDied(this);
                        }
                    };
                    try {
                        token.linkToDeath(newToken, 0);
                        mImportantProcesses.put(pid, newToken);
                        pr.forcingToImportant = newToken;
                        changed = true;
                    } catch (RemoteException e) {
                        // If the process died while doing this, we will later
                        // do the cleanup with the process death link.
                    }
                }
            }

            if (changed) {
                updateOomAdjLocked();
            }
        }
    }

    private boolean isAppForeground(int uid) {
        synchronized (this) {
            UidRecord uidRec = mProcessList.mActiveUids.get(uid);
            if (uidRec == null || uidRec.idle) {
                return false;
            }
            return uidRec.getCurProcState() <= ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
        }
    }

    // NOTE: this is an internal method used by the OnShellCommand implementation only and should
    // be guarded by permission checking.
    int getUidState(int uid) {
        synchronized (this) {
            return mProcessList.getUidProcStateLocked(uid);
        }
    }

    // =========================================================
    // PROCESS INFO
    // =========================================================

    static class ProcessInfoService extends IProcessInfoService.Stub {
        final ActivityManagerService mActivityManagerService;
        ProcessInfoService(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        public void getProcessStatesFromPids(/*in*/ int[] pids, /*out*/ int[] states) {
            mActivityManagerService.getProcessStatesAndOomScoresForPIDs(
                    /*in*/ pids, /*out*/ states, null);
        }

        @Override
        public void getProcessStatesAndOomScoresFromPids(
                /*in*/ int[] pids, /*out*/ int[] states, /*out*/ int[] scores) {
            mActivityManagerService.getProcessStatesAndOomScoresForPIDs(
                    /*in*/ pids, /*out*/ states, /*out*/ scores);
        }
    }

    /**
     * For each PID in the given input array, write the current process state
     * for that process into the states array, or -1 to indicate that no
     * process with the given PID exists. If scores array is provided, write
     * the oom score for the process into the scores array, with INVALID_ADJ
     * indicating the PID doesn't exist.
     */
    public void getProcessStatesAndOomScoresForPIDs(
            /*in*/ int[] pids, /*out*/ int[] states, /*out*/ int[] scores) {
        if (scores != null) {
            enforceCallingPermission(android.Manifest.permission.GET_PROCESS_STATE_AND_OOM_SCORE,
                    "getProcessStatesAndOomScoresForPIDs()");
        }

        if (pids == null) {
            throw new NullPointerException("pids");
        } else if (states == null) {
            throw new NullPointerException("states");
        } else if (pids.length != states.length) {
            throw new IllegalArgumentException("pids and states arrays have different lengths!");
        } else if (scores != null && pids.length != scores.length) {
            throw new IllegalArgumentException("pids and scores arrays have different lengths!");
        }

        synchronized (mPidsSelfLocked) {
            for (int i = 0; i < pids.length; i++) {
                ProcessRecord pr = mPidsSelfLocked.get(pids[i]);
                states[i] = (pr == null) ? PROCESS_STATE_NONEXISTENT : pr.getCurProcState();
                if (scores != null) {
                    scores[i] = (pr == null) ? ProcessList.INVALID_ADJ : pr.curAdj;
                }
            }
        }
    }

    // =========================================================
    // PERMISSIONS
    // =========================================================

    static class PermissionController extends IPermissionController.Stub {
        ActivityManagerService mActivityManagerService;
        PermissionController(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        public boolean checkPermission(String permission, int pid, int uid) {
            return mActivityManagerService.checkPermission(permission, pid,
                    uid) == PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public int noteOp(String op, int uid, String packageName) {
            return mActivityManagerService.mAppOpsService
                    .noteOperation(AppOpsManager.strOpToOp(op), uid, packageName);
        }

        @Override
        public String[] getPackagesForUid(int uid) {
            return mActivityManagerService.mContext.getPackageManager()
                    .getPackagesForUid(uid);
        }

        @Override
        public boolean isRuntimePermission(String permission) {
            try {
                PermissionInfo info = mActivityManagerService.mContext.getPackageManager()
                        .getPermissionInfo(permission, 0);
                return (info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                        == PermissionInfo.PROTECTION_DANGEROUS;
            } catch (NameNotFoundException nnfe) {
                Slog.e(TAG, "No such permission: "+ permission, nnfe);
            }
            return false;
        }

        @Override
        public int getPackageUid(String packageName, int flags) {
            try {
                return mActivityManagerService.mContext.getPackageManager()
                        .getPackageUid(packageName, flags);
            } catch (NameNotFoundException nnfe) {
                return -1;
            }
        }
    }

    class IntentFirewallInterface implements IntentFirewall.AMSInterface {
        @Override
        public int checkComponentPermission(String permission, int pid, int uid,
                int owningUid, boolean exported) {
            return ActivityManagerService.this.checkComponentPermission(permission, pid, uid,
                    owningUid, exported);
        }

        @Override
        public Object getAMSLock() {
            return ActivityManagerService.this;
        }
    }

    public static int checkComponentPermission(String permission, int pid, int uid,
            int owningUid, boolean exported) {
        if (pid == MY_PID) {
            return PackageManager.PERMISSION_GRANTED;
        }
        return ActivityManager.checkComponentPermission(permission, uid,
                owningUid, exported);
    }

    /**
     * As the only public entry point for permissions checking, this method
     * can enforce the semantic that requesting a check on a null global
     * permission is automatically denied.  (Internally a null permission
     * string is used when calling {@link #checkComponentPermission} in cases
     * when only uid-based security is needed.)
     *
     * This can be called with or without the global lock held.
     */
    @Override
    public int checkPermission(String permission, int pid, int uid) {
        if (permission == null) {
            return PackageManager.PERMISSION_DENIED;
        }
        return checkComponentPermission(permission, pid, uid, -1, true);
    }

    @Override
    public int checkPermissionWithToken(String permission, int pid, int uid, IBinder callerToken) {
        if (permission == null) {
            return PackageManager.PERMISSION_DENIED;
        }

        // We might be performing an operation on behalf of an indirect binder
        // invocation, e.g. via {@link #openContentUri}.  Check and adjust the
        // client identity accordingly before proceeding.
        Identity tlsIdentity = sCallerIdentity.get();
        if (tlsIdentity != null && tlsIdentity.token == callerToken) {
            Slog.d(TAG, "checkComponentPermission() adjusting {pid,uid} to {"
                    + tlsIdentity.pid + "," + tlsIdentity.uid + "}");
            uid = tlsIdentity.uid;
            pid = tlsIdentity.pid;
        }

        return checkComponentPermission(permission, pid, uid, -1, true);
    }

    /**
     * Binder IPC calls go through the public entry point.
     * This can be called with or without the global lock held.
     */
    int checkCallingPermission(String permission) {
        return checkPermission(permission,
                Binder.getCallingPid(),
                Binder.getCallingUid());
    }

    /**
     * This can be called with or without the global lock held.
     */
    void enforceCallingPermission(String permission, String func) {
        if (checkCallingPermission(permission)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        String msg = "Permission Denial: " + func + " from pid="
                + Binder.getCallingPid()
                + ", uid=" + Binder.getCallingUid()
                + " requires " + permission;
        Slog.w(TAG, msg);
        throw new SecurityException(msg);
    }

    /**
     * This can be called with or without the global lock held.
     */
    void enforcePermission(String permission, int pid, int uid, String func) {
        if (checkPermission(permission, pid, uid) == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        String msg = "Permission Denial: " + func + " from pid=" + pid + ", uid=" + uid
                + " requires " + permission;
        Slog.w(TAG, msg);
        throw new SecurityException(msg);
    }

    public boolean isAppStartModeDisabled(int uid, String packageName) {
        synchronized (this) {
            return getAppStartModeLocked(uid, packageName, 0, -1, false, true, false)
                    == ActivityManager.APP_START_MODE_DISABLED;
        }
    }

    // Unified app-op and target sdk check
    int appRestrictedInBackgroundLocked(int uid, String packageName, int packageTargetSdk) {
        // Apps that target O+ are always subject to background check
        if (packageTargetSdk >= Build.VERSION_CODES.O) {
            if (DEBUG_BACKGROUND_CHECK) {
                Slog.i(TAG, "App " + uid + "/" + packageName + " targets O+, restricted");
            }
            return ActivityManager.APP_START_MODE_DELAYED_RIGID;
        }
        // ...and legacy apps get an AppOp check
        int appop = mAppOpsService.noteOperation(AppOpsManager.OP_RUN_IN_BACKGROUND,
                uid, packageName);
        if (DEBUG_BACKGROUND_CHECK) {
            Slog.i(TAG, "Legacy app " + uid + "/" + packageName + " bg appop " + appop);
        }
        switch (appop) {
            case AppOpsManager.MODE_ALLOWED:
                // If force-background-check is enabled, restrict all apps that aren't whitelisted.
                if (mForceBackgroundCheck &&
                        !UserHandle.isCore(uid) &&
                        !isOnDeviceIdleWhitelistLocked(uid, /*allowExceptIdleToo=*/ true)) {
                    if (DEBUG_BACKGROUND_CHECK) {
                        Slog.i(TAG, "Force background check: " +
                                uid + "/" + packageName + " restricted");
                    }
                    return ActivityManager.APP_START_MODE_DELAYED;
                }
                return ActivityManager.APP_START_MODE_NORMAL;
            case AppOpsManager.MODE_IGNORED:
                return ActivityManager.APP_START_MODE_DELAYED;
            default:
                return ActivityManager.APP_START_MODE_DELAYED_RIGID;
        }
    }

    // Service launch is available to apps with run-in-background exemptions but
    // some other background operations are not.  If we're doing a check
    // of service-launch policy, allow those callers to proceed unrestricted.
    int appServicesRestrictedInBackgroundLocked(int uid, String packageName, int packageTargetSdk) {
        // Persistent app?
        if (mPackageManagerInt.isPackagePersistent(packageName)) {
            if (DEBUG_BACKGROUND_CHECK) {
                Slog.i(TAG, "App " + uid + "/" + packageName
                        + " is persistent; not restricted in background");
            }
            return ActivityManager.APP_START_MODE_NORMAL;
        }

        // Non-persistent but background whitelisted?
        if (uidOnBackgroundWhitelist(uid)) {
            if (DEBUG_BACKGROUND_CHECK) {
                Slog.i(TAG, "App " + uid + "/" + packageName
                        + " on background whitelist; not restricted in background");
            }
            return ActivityManager.APP_START_MODE_NORMAL;
        }

        // Is this app on the battery whitelist?
        if (isOnDeviceIdleWhitelistLocked(uid, /*allowExceptIdleToo=*/ false)) {
            if (DEBUG_BACKGROUND_CHECK) {
                Slog.i(TAG, "App " + uid + "/" + packageName
                        + " on idle whitelist; not restricted in background");
            }
            return ActivityManager.APP_START_MODE_NORMAL;
        }

        // None of the service-policy criteria apply, so we apply the common criteria
        return appRestrictedInBackgroundLocked(uid, packageName, packageTargetSdk);
    }

    int getAppStartModeLocked(int uid, String packageName, int packageTargetSdk,
            int callingPid, boolean alwaysRestrict, boolean disabledOnly, boolean forcedStandby) {
        UidRecord uidRec = mProcessList.getUidRecordLocked(uid);
        if (DEBUG_BACKGROUND_CHECK) Slog.d(TAG, "checkAllowBackground: uid=" + uid + " pkg="
                + packageName + " rec=" + uidRec + " always=" + alwaysRestrict + " idle="
                + (uidRec != null ? uidRec.idle : false));
        if (uidRec == null || alwaysRestrict || forcedStandby || uidRec.idle) {
            boolean ephemeral;
            if (uidRec == null) {
                ephemeral = getPackageManagerInternalLocked().isPackageEphemeral(
                        UserHandle.getUserId(uid), packageName);
            } else {
                ephemeral = uidRec.ephemeral;
            }

            if (ephemeral) {
                // We are hard-core about ephemeral apps not running in the background.
                return ActivityManager.APP_START_MODE_DISABLED;
            } else {
                if (disabledOnly) {
                    // The caller is only interested in whether app starts are completely
                    // disabled for the given package (that is, it is an instant app).  So
                    // we don't need to go further, which is all just seeing if we should
                    // apply a "delayed" mode for a regular app.
                    return ActivityManager.APP_START_MODE_NORMAL;
                }
                final int startMode = (alwaysRestrict)
                        ? appRestrictedInBackgroundLocked(uid, packageName, packageTargetSdk)
                        : appServicesRestrictedInBackgroundLocked(uid, packageName,
                                packageTargetSdk);
                if (DEBUG_BACKGROUND_CHECK) {
                    Slog.d(TAG, "checkAllowBackground: uid=" + uid
                            + " pkg=" + packageName + " startMode=" + startMode
                            + " onwhitelist=" + isOnDeviceIdleWhitelistLocked(uid, false)
                            + " onwhitelist(ei)=" + isOnDeviceIdleWhitelistLocked(uid, true));
                }
                if (startMode == ActivityManager.APP_START_MODE_DELAYED) {
                    // This is an old app that has been forced into a "compatible as possible"
                    // mode of background check.  To increase compatibility, we will allow other
                    // foreground apps to cause its services to start.
                    if (callingPid >= 0) {
                        ProcessRecord proc;
                        synchronized (mPidsSelfLocked) {
                            proc = mPidsSelfLocked.get(callingPid);
                        }
                        if (proc != null &&
                                !ActivityManager.isProcStateBackground(proc.getCurProcState())) {
                            // Whoever is instigating this is in the foreground, so we will allow it
                            // to go through.
                            return ActivityManager.APP_START_MODE_NORMAL;
                        }
                    }
                }
                return startMode;
            }
        }
        return ActivityManager.APP_START_MODE_NORMAL;
    }

    /**
     * @return whether a UID is in the system, user or temp doze whitelist.
     */
    boolean isOnDeviceIdleWhitelistLocked(int uid, boolean allowExceptIdleToo) {
        final int appId = UserHandle.getAppId(uid);

        final int[] whitelist = allowExceptIdleToo
                ? mDeviceIdleExceptIdleWhitelist
                : mDeviceIdleWhitelist;

        return Arrays.binarySearch(whitelist, appId) >= 0
                || Arrays.binarySearch(mDeviceIdleTempWhitelist, appId) >= 0
                || mPendingTempWhitelist.indexOfKey(uid) >= 0;
    }

    /**
     * @return whitelist tag for a uid from mPendingTempWhitelist, null if not currently on
     * the whitelist
     */
    String getPendingTempWhitelistTagForUidLocked(int uid) {
        final PendingTempWhitelist ptw = mPendingTempWhitelist.get(uid);
        return ptw != null ? ptw.tag : null;
    }

    private ProviderInfo getProviderInfoLocked(String authority, int userHandle, int pmFlags) {
        ProviderInfo pi = null;
        ContentProviderRecord cpr = mProviderMap.getProviderByName(authority, userHandle);
        if (cpr != null) {
            pi = cpr.info;
        } else {
            try {
                pi = AppGlobals.getPackageManager().resolveContentProvider(
                        authority, PackageManager.GET_URI_PERMISSION_PATTERNS | pmFlags,
                        userHandle);
            } catch (RemoteException ex) {
            }
        }
        return pi;
    }

    @VisibleForTesting
    public void grantEphemeralAccessLocked(int userId, Intent intent,
            int targetAppId, int ephemeralAppId) {
        getPackageManagerInternalLocked().
                grantEphemeralAccess(userId, intent, targetAppId, ephemeralAppId);
    }

    /**
     * @param uri This uri must NOT contain an embedded userId.
     * @param userId The userId in which the uri is to be resolved.
     */
    @Override
    public int checkUriPermission(Uri uri, int pid, int uid,
            final int modeFlags, int userId, IBinder callerToken) {
        enforceNotIsolatedCaller("checkUriPermission");

        // Another redirected-binder-call permissions check as in
        // {@link checkPermissionWithToken}.
        Identity tlsIdentity = sCallerIdentity.get();
        if (tlsIdentity != null && tlsIdentity.token == callerToken) {
            uid = tlsIdentity.uid;
            pid = tlsIdentity.pid;
        }

        // Our own process gets to do everything.
        if (pid == MY_PID) {
            return PackageManager.PERMISSION_GRANTED;
        }
        return mUgmInternal.checkUriPermission(new GrantUri(userId, uri, false), uid, modeFlags)
                ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
    }

    /**
     * @param uri This uri must NOT contain an embedded userId.
     * @param userId The userId in which the uri is to be resolved.
     */
    @Override
    public void grantUriPermission(IApplicationThread caller, String targetPkg, Uri uri,
            final int modeFlags, int userId) {
        enforceNotIsolatedCaller("grantUriPermission");
        GrantUri grantUri = new GrantUri(userId, uri, false);
        synchronized(this) {
            final ProcessRecord r = getRecordForAppLocked(caller);
            if (r == null) {
                throw new SecurityException("Unable to find app for caller "
                        + caller
                        + " when granting permission to uri " + grantUri);
            }
            if (targetPkg == null) {
                throw new IllegalArgumentException("null target");
            }
            if (grantUri == null) {
                throw new IllegalArgumentException("null uri");
            }

            Preconditions.checkFlagsArgument(modeFlags, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);

            mUgmInternal.grantUriPermission(r.uid, targetPkg, grantUri, modeFlags, null,
                    UserHandle.getUserId(r.uid));
        }
    }

    /**
     * @param uri This uri must NOT contain an embedded userId.
     * @param userId The userId in which the uri is to be resolved.
     */
    @Override
    public void revokeUriPermission(IApplicationThread caller, String targetPackage, Uri uri,
            final int modeFlags, int userId) {
        enforceNotIsolatedCaller("revokeUriPermission");
        synchronized(this) {
            final ProcessRecord r = getRecordForAppLocked(caller);
            if (r == null) {
                throw new SecurityException("Unable to find app for caller "
                        + caller
                        + " when revoking permission to uri " + uri);
            }
            if (uri == null) {
                Slog.w(TAG, "revokeUriPermission: null uri");
                return;
            }

            if (!Intent.isAccessUriMode(modeFlags)) {
                return;
            }

            final String authority = uri.getAuthority();
            final ProviderInfo pi = getProviderInfoLocked(authority, userId,
                    MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE);
            if (pi == null) {
                Slog.w(TAG, "No content provider found for permission revoke: "
                        + uri.toSafeString());
                return;
            }

            mUgmInternal.revokeUriPermission(targetPackage, r.uid, new GrantUri(userId, uri, false),
                    modeFlags);
        }
    }

    @Override
    public void showWaitingForDebugger(IApplicationThread who, boolean waiting) {
        synchronized (this) {
            ProcessRecord app =
                who != null ? getRecordForAppLocked(who) : null;
            if (app == null) return;

            Message msg = Message.obtain();
            msg.what = WAIT_FOR_DEBUGGER_UI_MSG;
            msg.obj = app;
            msg.arg1 = waiting ? 1 : 0;
            mUiHandler.sendMessage(msg);
        }
    }

    @Override
    public void getMemoryInfo(ActivityManager.MemoryInfo outInfo) {
        mProcessList.getMemoryInfo(outInfo);
    }

    // =========================================================
    // TASK MANAGEMENT
    // =========================================================

    @Override
    public List<RunningTaskInfo> getTasks(int maxNum) {
        return mActivityTaskManager.getTasks(maxNum);
    }

    @Override
    public List<RunningTaskInfo> getFilteredTasks(int maxNum, @ActivityType int ignoreActivityType,
            @WindowingMode int ignoreWindowingMode) {
        return mActivityTaskManager.getFilteredTasks(
                maxNum, ignoreActivityType, ignoreWindowingMode);
    }

    @Override
    public void cancelTaskWindowTransition(int taskId) {
        mActivityTaskManager.cancelTaskWindowTransition(taskId);
    }

    @Override
    public void setTaskResizeable(int taskId, int resizeableMode) {
        mActivityTaskManager.setTaskResizeable(taskId, resizeableMode);
    }

    @Override
    public ActivityManager.TaskSnapshot getTaskSnapshot(int taskId, boolean reducedResolution) {
        return mActivityTaskManager.getTaskSnapshot(taskId, reducedResolution);
    }

    @Override
    public void resizeTask(int taskId, Rect bounds, int resizeMode) {
        mActivityTaskManager.resizeTask(taskId, bounds, resizeMode);
    }

    @Override
    public Rect getTaskBounds(int taskId) {
        return mActivityTaskManager.getTaskBounds(taskId);
    }

    @Override
    public void removeStack(int stackId) {
        mActivityTaskManager.removeStack(stackId);
    }

    @Override
    public boolean removeTask(int taskId) {
        return mActivityTaskManager.removeTask(taskId);
    }

    @Override
    public void moveTaskToFront(int taskId, int flags, Bundle bOptions) {
        mActivityTaskManager.moveTaskToFront(taskId, flags, bOptions);
    }

    /**
     * Attempts to move a task backwards in z-order (the order of activities within the task is
     * unchanged).
     *
     * There are several possible results of this call:
     * - if the task is locked, then we will show the lock toast
     * - if there is a task behind the provided task, then that task is made visible and resumed as
     *   this task is moved to the back
     * - otherwise, if there are no other tasks in the stack:
     *     - if this task is in the pinned stack, then we remove the stack completely, which will
     *       have the effect of moving the task to the top or bottom of the fullscreen stack
     *       (depending on whether it is visible)
     *     - otherwise, we simply return home and hide this task
     *
     * @param token A reference to the activity we wish to move
     * @param nonRoot If false then this only works if the activity is the root
     *                of a task; if true it will work for any activity in a task.
     * @return Returns true if the move completed, false if not.
     */
    @Override
    public boolean moveActivityTaskToBack(IBinder token, boolean nonRoot) {
        return mActivityTaskManager.moveActivityTaskToBack(token, nonRoot);
    }

    @Override
    public void moveTaskToStack(int taskId, int stackId, boolean toTop) {
        mActivityTaskManager.moveTaskToStack(taskId, stackId, toTop);
    }

    @Override
    public void resizeStack(int stackId, Rect destBounds, boolean allowResizeInDockedMode,
            boolean preserveWindows, boolean animate, int animationDuration) {
        mActivityTaskManager.resizeStack(stackId, destBounds, allowResizeInDockedMode,
                preserveWindows, animate, animationDuration);
    }

    @Override
    public ParceledListSlice<ActivityManager.RecentTaskInfo> getRecentTasks(int maxNum, int flags,
            int userId) {
        return mActivityTaskManager.getRecentTasks(maxNum, flags, userId);
    }

    /**
     * Moves the top activity in the input stackId to the pinned stack.
     *
     * @param stackId Id of stack to move the top activity to pinned stack.
     * @param bounds Bounds to use for pinned stack.
     *
     * @return True if the top activity of the input stack was successfully moved to the pinned
     *          stack.
     */
    @Override
    public boolean moveTopActivityToPinnedStack(int stackId, Rect bounds) {
        return mActivityTaskManager.moveTopActivityToPinnedStack(stackId, bounds);
    }

    @Override
    public void resizeDockedStack(Rect dockedBounds, Rect tempDockedTaskBounds,
            Rect tempDockedTaskInsetBounds,
            Rect tempOtherTaskBounds, Rect tempOtherTaskInsetBounds) {
        mActivityTaskManager.resizeDockedStack(dockedBounds, tempDockedTaskBounds,
                tempDockedTaskInsetBounds, tempOtherTaskBounds, tempOtherTaskInsetBounds);
    }

    @Override
    public void positionTaskInStack(int taskId, int stackId, int position) {
        mActivityTaskManager.positionTaskInStack(taskId, stackId, position);
    }

    @Override
    public List<StackInfo> getAllStackInfos() {
        return mActivityTaskManager.getAllStackInfos();
    }

    @Override
    public int getTaskForActivity(IBinder token, boolean onlyRoot) {
        return mActivityTaskManager.getTaskForActivity(token, onlyRoot);
    }

    @Override
    public void updateDeviceOwner(String packageName) {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != 0 && callingUid != SYSTEM_UID) {
            throw new SecurityException("updateDeviceOwner called from non-system process");
        }
        synchronized (this) {
            mDeviceOwnerName = packageName;
        }
    }

    @Override
    public void updateLockTaskPackages(int userId, String[] packages) {
        mActivityTaskManager.updateLockTaskPackages(userId, packages);
    }

    @Override
    public boolean isInLockTaskMode() {
        return mActivityTaskManager.isInLockTaskMode();
    }

    @Override
    public int getLockTaskModeState() {
        return mActivityTaskManager.getLockTaskModeState();
    }

    @Override
    public void startSystemLockTaskMode(int taskId) throws RemoteException {
        mActivityTaskManager.startSystemLockTaskMode(taskId);
    }

    // =========================================================
    // CONTENT PROVIDERS
    // =========================================================

    private final List<ProviderInfo> generateApplicationProvidersLocked(ProcessRecord app) {
        List<ProviderInfo> providers = null;
        try {
            providers = AppGlobals.getPackageManager()
                    .queryContentProviders(app.processName, app.uid,
                            STOCK_PM_FLAGS | PackageManager.GET_URI_PERMISSION_PATTERNS
                                    | MATCH_DEBUG_TRIAGED_MISSING, /*metadastaKey=*/ null)
                    .getList();
        } catch (RemoteException ex) {
        }
        if (DEBUG_MU) Slog.v(TAG_MU,
                "generateApplicationProvidersLocked, app.info.uid = " + app.uid);
        int userId = app.userId;
        if (providers != null) {
            int N = providers.size();
            app.pubProviders.ensureCapacity(N + app.pubProviders.size());
            for (int i=0; i<N; i++) {
                // TODO: keep logic in sync with installEncryptionUnawareProviders
                ProviderInfo cpi =
                    (ProviderInfo)providers.get(i);
                boolean singleton = isSingleton(cpi.processName, cpi.applicationInfo,
                        cpi.name, cpi.flags);
                if (singleton && UserHandle.getUserId(app.uid) != UserHandle.USER_SYSTEM) {
                    // This is a singleton provider, but a user besides the
                    // default user is asking to initialize a process it runs
                    // in...  well, no, it doesn't actually run in this process,
                    // it runs in the process of the default user.  Get rid of it.
                    providers.remove(i);
                    N--;
                    i--;
                    continue;
                }

                ComponentName comp = new ComponentName(cpi.packageName, cpi.name);
                ContentProviderRecord cpr = mProviderMap.getProviderByClass(comp, userId);
                if (cpr == null) {
                    cpr = new ContentProviderRecord(this, cpi, app.info, comp, singleton);
                    mProviderMap.putProviderByClass(comp, cpr);
                }
                if (DEBUG_MU) Slog.v(TAG_MU,
                        "generateApplicationProvidersLocked, cpi.uid = " + cpr.uid);
                app.pubProviders.put(cpi.name, cpr);
                if (!cpi.multiprocess || !"android".equals(cpi.packageName)) {
                    // Don't add this if it is a platform component that is marked
                    // to run in multiple processes, because this is actually
                    // part of the framework so doesn't make sense to track as a
                    // separate apk in the process.
                    app.addPackage(cpi.applicationInfo.packageName, cpi.applicationInfo.versionCode,
                            mProcessStats);
                }
                notifyPackageUse(cpi.applicationInfo.packageName,
                                 PackageManager.NOTIFY_PACKAGE_USE_CONTENT_PROVIDER);
            }
        }
        return providers;
    }

    /**
     * Check if the calling UID has a possible chance at accessing the provider
     * at the given authority and user.
     */
    public String checkContentProviderAccess(String authority, int userId) {
        if (userId == UserHandle.USER_ALL) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, TAG);
            userId = UserHandle.getCallingUserId();
        }

        ProviderInfo cpi = null;
        try {
            cpi = AppGlobals.getPackageManager().resolveContentProvider(authority,
                    STOCK_PM_FLAGS | PackageManager.GET_URI_PERMISSION_PATTERNS
                            | PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                    userId);
        } catch (RemoteException ignored) {
        }
        if (cpi == null) {
            return "Failed to find provider " + authority + " for user " + userId
                    + "; expected to find a valid ContentProvider for this authority";
        }

        ProcessRecord r = null;
        synchronized (mPidsSelfLocked) {
            r = mPidsSelfLocked.get(Binder.getCallingPid());
        }
        if (r == null) {
            return "Failed to find PID " + Binder.getCallingPid();
        }

        synchronized (this) {
            return checkContentProviderPermissionLocked(cpi, r, userId, true);
        }
    }

    /**
     * Check if {@link ProcessRecord} has a possible chance at accessing the
     * given {@link ProviderInfo}. Final permission checking is always done
     * in {@link ContentProvider}.
     */
    private final String checkContentProviderPermissionLocked(
            ProviderInfo cpi, ProcessRecord r, int userId, boolean checkUser) {
        final int callingPid = (r != null) ? r.pid : Binder.getCallingPid();
        final int callingUid = (r != null) ? r.uid : Binder.getCallingUid();
        boolean checkedGrants = false;
        if (checkUser) {
            // Looking for cross-user grants before enforcing the typical cross-users permissions
            int tmpTargetUserId = mUserController.unsafeConvertIncomingUser(userId);
            if (tmpTargetUserId != UserHandle.getUserId(callingUid)) {
                if (mUgmInternal.checkAuthorityGrants(
                        callingUid, cpi, tmpTargetUserId, checkUser)) {
                    return null;
                }
                checkedGrants = true;
            }
            userId = mUserController.handleIncomingUser(callingPid, callingUid, userId, false,
                    ALLOW_NON_FULL, "checkContentProviderPermissionLocked " + cpi.authority, null);
            if (userId != tmpTargetUserId) {
                // When we actually went to determine the final targer user ID, this ended
                // up different than our initial check for the authority.  This is because
                // they had asked for USER_CURRENT_OR_SELF and we ended up switching to
                // SELF.  So we need to re-check the grants again.
                checkedGrants = false;
            }
        }
        if (checkComponentPermission(cpi.readPermission, callingPid, callingUid,
                cpi.applicationInfo.uid, cpi.exported)
                == PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        if (checkComponentPermission(cpi.writePermission, callingPid, callingUid,
                cpi.applicationInfo.uid, cpi.exported)
                == PackageManager.PERMISSION_GRANTED) {
            return null;
        }

        PathPermission[] pps = cpi.pathPermissions;
        if (pps != null) {
            int i = pps.length;
            while (i > 0) {
                i--;
                PathPermission pp = pps[i];
                String pprperm = pp.getReadPermission();
                if (pprperm != null && checkComponentPermission(pprperm, callingPid, callingUid,
                        cpi.applicationInfo.uid, cpi.exported)
                        == PackageManager.PERMISSION_GRANTED) {
                    return null;
                }
                String ppwperm = pp.getWritePermission();
                if (ppwperm != null && checkComponentPermission(ppwperm, callingPid, callingUid,
                        cpi.applicationInfo.uid, cpi.exported)
                        == PackageManager.PERMISSION_GRANTED) {
                    return null;
                }
            }
        }
        if (!checkedGrants
                && mUgmInternal.checkAuthorityGrants(callingUid, cpi, userId, checkUser)) {
            return null;
        }

        final String suffix;
        if (!cpi.exported) {
            suffix = " that is not exported from UID " + cpi.applicationInfo.uid;
        } else if (android.Manifest.permission.MANAGE_DOCUMENTS.equals(cpi.readPermission)) {
            suffix = " requires that you obtain access using ACTION_OPEN_DOCUMENT or related APIs";
        } else {
            suffix = " requires " + cpi.readPermission + " or " + cpi.writePermission;
        }
        final String msg = "Permission Denial: opening provider " + cpi.name
                + " from " + (r != null ? r : "(null)") + " (pid=" + callingPid
                + ", uid=" + callingUid + ")" + suffix;
        Slog.w(TAG, msg);
        return msg;
    }

    ContentProviderConnection incProviderCountLocked(ProcessRecord r,
            final ContentProviderRecord cpr, IBinder externalProcessToken, int callingUid,
            String callingPackage, String callingTag, boolean stable) {
        if (r != null) {
            for (int i=0; i<r.conProviders.size(); i++) {
                ContentProviderConnection conn = r.conProviders.get(i);
                if (conn.provider == cpr) {
                    if (DEBUG_PROVIDER) Slog.v(TAG_PROVIDER,
                            "Adding provider requested by "
                            + r.processName + " from process "
                            + cpr.info.processName + ": " + cpr.name.flattenToShortString()
                            + " scnt=" + conn.stableCount + " uscnt=" + conn.unstableCount);
                    if (stable) {
                        conn.stableCount++;
                        conn.numStableIncs++;
                    } else {
                        conn.unstableCount++;
                        conn.numUnstableIncs++;
                    }
                    return conn;
                }
            }
            ContentProviderConnection conn = new ContentProviderConnection(cpr, r, callingPackage);
            conn.startAssociationIfNeeded();
            if (stable) {
                conn.stableCount = 1;
                conn.numStableIncs = 1;
            } else {
                conn.unstableCount = 1;
                conn.numUnstableIncs = 1;
            }
            cpr.connections.add(conn);
            r.conProviders.add(conn);
            startAssociationLocked(r.uid, r.processName, r.getCurProcState(),
                    cpr.uid, cpr.appInfo.longVersionCode, cpr.name, cpr.info.processName);
            return conn;
        }
        cpr.addExternalProcessHandleLocked(externalProcessToken, callingUid, callingTag);
        return null;
    }

    boolean decProviderCountLocked(ContentProviderConnection conn,
            ContentProviderRecord cpr, IBinder externalProcessToken, boolean stable) {
        if (conn != null) {
            cpr = conn.provider;
            if (DEBUG_PROVIDER) Slog.v(TAG_PROVIDER,
                    "Removing provider requested by "
                    + conn.client.processName + " from process "
                    + cpr.info.processName + ": " + cpr.name.flattenToShortString()
                    + " scnt=" + conn.stableCount + " uscnt=" + conn.unstableCount);
            if (stable) {
                conn.stableCount--;
            } else {
                conn.unstableCount--;
            }
            if (conn.stableCount == 0 && conn.unstableCount == 0) {
                conn.stopAssociation();
                cpr.connections.remove(conn);
                conn.client.conProviders.remove(conn);
                if (conn.client.setProcState < PROCESS_STATE_LAST_ACTIVITY) {
                    // The client is more important than last activity -- note the time this
                    // is happening, so we keep the old provider process around a bit as last
                    // activity to avoid thrashing it.
                    if (cpr.proc != null) {
                        cpr.proc.lastProviderTime = SystemClock.uptimeMillis();
                    }
                }
                stopAssociationLocked(conn.client.uid, conn.client.processName, cpr.uid,
                        cpr.appInfo.longVersionCode, cpr.name, cpr.info.processName);
                return true;
            }
            return false;
        }
        cpr.removeExternalProcessHandleLocked(externalProcessToken);
        return false;
    }

    void checkTime(long startTime, String where) {
        long now = SystemClock.uptimeMillis();
        if ((now-startTime) > 50) {
            // If we are taking more than 50ms, log about it.
            Slog.w(TAG, "Slow operation: " + (now-startTime) + "ms so far, now at " + where);
        }
    }

    private static final int[] PROCESS_STATE_STATS_FORMAT = new int[] {
            PROC_SPACE_TERM,
            PROC_SPACE_TERM|PROC_PARENS,
            PROC_SPACE_TERM|PROC_CHAR|PROC_OUT_LONG,        // 3: process state
    };

    private final long[] mProcessStateStatsLongs = new long[1];

    private boolean isProcessAliveLocked(ProcessRecord proc) {
        if (proc.pid <= 0) {
            if (DEBUG_OOM_ADJ) Slog.d(TAG, "Process hasn't started yet: " + proc);
            return false;
        }
        if (proc.procStatFile == null) {
            proc.procStatFile = "/proc/" + proc.pid + "/stat";
        }
        mProcessStateStatsLongs[0] = 0;
        if (!readProcFile(proc.procStatFile, PROCESS_STATE_STATS_FORMAT, null,
                mProcessStateStatsLongs, null)) {
            if (DEBUG_OOM_ADJ) Slog.d(TAG, "UNABLE TO RETRIEVE STATE FOR " + proc.procStatFile);
            return false;
        }
        final long state = mProcessStateStatsLongs[0];
        if (DEBUG_OOM_ADJ) Slog.d(TAG, "RETRIEVED STATE FOR " + proc.procStatFile + ": "
                + (char)state);
        return state != 'Z' && state != 'X' && state != 'x' && state != 'K';
    }

    private String checkContentProviderAssociation(ProcessRecord callingApp, int callingUid,
            ProviderInfo cpi) {
        if (callingApp == null) {
            return validateAssociationAllowedLocked(cpi.packageName, cpi.applicationInfo.uid,
                    null, callingUid) ? null : "<null>";
        }
        for (int i = callingApp.pkgList.size() - 1; i >= 0; i--) {
            if (!validateAssociationAllowedLocked(callingApp.pkgList.keyAt(i), callingApp.uid,
                    cpi.packageName, cpi.applicationInfo.uid)) {
                return cpi.packageName;
            }
        }
        return null;
    }

    private ContentProviderHolder getContentProviderImpl(IApplicationThread caller,
            String name, IBinder token, int callingUid, String callingPackage, String callingTag,
            boolean stable, int userId) {
        ContentProviderRecord cpr;
        ContentProviderConnection conn = null;
        ProviderInfo cpi = null;
        boolean providerRunning = false;

        synchronized(this) {
            long startTime = SystemClock.uptimeMillis();

            ProcessRecord r = null;
            if (caller != null) {
                r = getRecordForAppLocked(caller);
                if (r == null) {
                    throw new SecurityException(
                            "Unable to find app for caller " + caller
                          + " (pid=" + Binder.getCallingPid()
                          + ") when getting content provider " + name);
                }
            }

            boolean checkCrossUser = true;

            checkTime(startTime, "getContentProviderImpl: getProviderByName");

            // First check if this content provider has been published...
            cpr = mProviderMap.getProviderByName(name, userId);
            // If that didn't work, check if it exists for user 0 and then
            // verify that it's a singleton provider before using it.
            if (cpr == null && userId != UserHandle.USER_SYSTEM) {
                cpr = mProviderMap.getProviderByName(name, UserHandle.USER_SYSTEM);
                if (cpr != null) {
                    cpi = cpr.info;
                    if (isSingleton(cpi.processName, cpi.applicationInfo,
                            cpi.name, cpi.flags)
                            && isValidSingletonCall(r.uid, cpi.applicationInfo.uid)) {
                        userId = UserHandle.USER_SYSTEM;
                        checkCrossUser = false;
                    } else {
                        cpr = null;
                        cpi = null;
                    }
                }
            }

            if (cpr != null && cpr.proc != null) {
                providerRunning = !cpr.proc.killed;

                // Note if killedByAm is also set, this means the provider process has just been
                // killed by AM (in ProcessRecord.kill()), but appDiedLocked() hasn't been called
                // yet. So we need to call appDiedLocked() here and let it clean up.
                // (See the commit message on I2c4ba1e87c2d47f2013befff10c49b3dc337a9a7 to see
                // how to test this case.)
                if (cpr.proc.killed && cpr.proc.killedByAm) {
                    checkTime(startTime, "getContentProviderImpl: before appDied (killedByAm)");
                    final long iden = Binder.clearCallingIdentity();
                    try {
                        appDiedLocked(cpr.proc);
                    } finally {
                        Binder.restoreCallingIdentity(iden);
                    }
                    checkTime(startTime, "getContentProviderImpl: after appDied (killedByAm)");
                }
            }

            if (providerRunning) {
                cpi = cpr.info;
                String msg;

                if (r != null && cpr.canRunHere(r)) {
                    if ((msg = checkContentProviderAssociation(r, callingUid, cpi)) != null) {
                        throw new SecurityException("Content provider lookup "
                                + cpr.name.flattenToShortString()
                                + " failed: association not allowed with package " + msg);
                    }
                    checkTime(startTime,
                            "getContentProviderImpl: before checkContentProviderPermission");
                    if ((msg = checkContentProviderPermissionLocked(cpi, r, userId, checkCrossUser))
                            != null) {
                        throw new SecurityException(msg);
                    }
                    checkTime(startTime,
                            "getContentProviderImpl: after checkContentProviderPermission");

                    // This provider has been published or is in the process
                    // of being published...  but it is also allowed to run
                    // in the caller's process, so don't make a connection
                    // and just let the caller instantiate its own instance.
                    ContentProviderHolder holder = cpr.newHolder(null);
                    // don't give caller the provider object, it needs
                    // to make its own.
                    holder.provider = null;
                    return holder;
                }

                // Don't expose providers between normal apps and instant apps
                try {
                    if (AppGlobals.getPackageManager()
                            .resolveContentProvider(name, 0 /*flags*/, userId) == null) {
                        return null;
                    }
                } catch (RemoteException e) {
                }

                if ((msg = checkContentProviderAssociation(r, callingUid, cpi)) != null) {
                    throw new SecurityException("Content provider lookup "
                            + cpr.name.flattenToShortString()
                            + " failed: association not allowed with package " + msg);
                }
                checkTime(startTime,
                        "getContentProviderImpl: before checkContentProviderPermission");
                if ((msg = checkContentProviderPermissionLocked(cpi, r, userId, checkCrossUser))
                        != null) {
                    throw new SecurityException(msg);
                }
                checkTime(startTime,
                        "getContentProviderImpl: after checkContentProviderPermission");

                final long origId = Binder.clearCallingIdentity();

                checkTime(startTime, "getContentProviderImpl: incProviderCountLocked");

                // In this case the provider instance already exists, so we can
                // return it right away.
                conn = incProviderCountLocked(r, cpr, token, callingUid, callingPackage, callingTag,
                        stable);
                if (conn != null && (conn.stableCount+conn.unstableCount) == 1) {
                    if (cpr.proc != null && r.setAdj <= ProcessList.PERCEPTIBLE_LOW_APP_ADJ) {
                        // If this is a perceptible app accessing the provider,
                        // make sure to count it as being accessed and thus
                        // back up on the LRU list.  This is good because
                        // content providers are often expensive to start.
                        checkTime(startTime, "getContentProviderImpl: before updateLruProcess");
                        mProcessList.updateLruProcessLocked(cpr.proc, false, null);
                        checkTime(startTime, "getContentProviderImpl: after updateLruProcess");
                    }
                }

                checkTime(startTime, "getContentProviderImpl: before updateOomAdj");
                final int verifiedAdj = cpr.proc.verifiedAdj;
                boolean success = updateOomAdjLocked(cpr.proc, true);
                // XXX things have changed so updateOomAdjLocked doesn't actually tell us
                // if the process has been successfully adjusted.  So to reduce races with
                // it, we will check whether the process still exists.  Note that this doesn't
                // completely get rid of races with LMK killing the process, but should make
                // them much smaller.
                if (success && verifiedAdj != cpr.proc.setAdj && !isProcessAliveLocked(cpr.proc)) {
                    success = false;
                }
                maybeUpdateProviderUsageStatsLocked(r, cpr.info.packageName, name);
                checkTime(startTime, "getContentProviderImpl: after updateOomAdj");
                if (DEBUG_PROVIDER) Slog.i(TAG_PROVIDER, "Adjust success: " + success);
                // NOTE: there is still a race here where a signal could be
                // pending on the process even though we managed to update its
                // adj level.  Not sure what to do about this, but at least
                // the race is now smaller.
                if (!success) {
                    // Uh oh...  it looks like the provider's process
                    // has been killed on us.  We need to wait for a new
                    // process to be started, and make sure its death
                    // doesn't kill our process.
                    Slog.i(TAG, "Existing provider " + cpr.name.flattenToShortString()
                            + " is crashing; detaching " + r);
                    boolean lastRef = decProviderCountLocked(conn, cpr, token, stable);
                    checkTime(startTime, "getContentProviderImpl: before appDied");
                    appDiedLocked(cpr.proc);
                    checkTime(startTime, "getContentProviderImpl: after appDied");
                    if (!lastRef) {
                        // This wasn't the last ref our process had on
                        // the provider...  we have now been killed, bail.
                        return null;
                    }
                    providerRunning = false;
                    conn = null;
                } else {
                    cpr.proc.verifiedAdj = cpr.proc.setAdj;
                }

                Binder.restoreCallingIdentity(origId);
            }

            if (!providerRunning) {
                try {
                    checkTime(startTime, "getContentProviderImpl: before resolveContentProvider");
                    cpi = AppGlobals.getPackageManager().
                        resolveContentProvider(name,
                            STOCK_PM_FLAGS | PackageManager.GET_URI_PERMISSION_PATTERNS, userId);
                    checkTime(startTime, "getContentProviderImpl: after resolveContentProvider");
                } catch (RemoteException ex) {
                }
                if (cpi == null) {
                    return null;
                }
                // If the provider is a singleton AND
                // (it's a call within the same user || the provider is a
                // privileged app)
                // Then allow connecting to the singleton provider
                boolean singleton = isSingleton(cpi.processName, cpi.applicationInfo,
                        cpi.name, cpi.flags)
                        && isValidSingletonCall(r.uid, cpi.applicationInfo.uid);
                if (singleton) {
                    userId = UserHandle.USER_SYSTEM;
                }
                cpi.applicationInfo = getAppInfoForUser(cpi.applicationInfo, userId);
                checkTime(startTime, "getContentProviderImpl: got app info for user");

                String msg;
                if ((msg = checkContentProviderAssociation(r, callingUid, cpi)) != null) {
                    throw new SecurityException("Content provider lookup " + name
                            + " failed: association not allowed with package " + msg);
                }
                checkTime(startTime, "getContentProviderImpl: before checkContentProviderPermission");
                if ((msg = checkContentProviderPermissionLocked(cpi, r, userId, !singleton))
                        != null) {
                    throw new SecurityException(msg);
                }
                checkTime(startTime, "getContentProviderImpl: after checkContentProviderPermission");

                if (!mProcessesReady
                        && !cpi.processName.equals("system")) {
                    // If this content provider does not run in the system
                    // process, and the system is not yet ready to run other
                    // processes, then fail fast instead of hanging.
                    throw new IllegalArgumentException(
                            "Attempt to launch content provider before system ready");
                }

                // If system providers are not installed yet we aggressively crash to avoid
                // creating multiple instance of these providers and then bad things happen!
                if (!mSystemProvidersInstalled && cpi.applicationInfo.isSystemApp()
                        && "system".equals(cpi.processName)) {
                    throw new IllegalStateException("Cannot access system provider: '"
                            + cpi.authority + "' before system providers are installed!");
                }

                // Make sure that the user who owns this provider is running.  If not,
                // we don't want to allow it to run.
                if (!mUserController.isUserRunning(userId, 0)) {
                    Slog.w(TAG, "Unable to launch app "
                            + cpi.applicationInfo.packageName + "/"
                            + cpi.applicationInfo.uid + " for provider "
                            + name + ": user " + userId + " is stopped");
                    return null;
                }

                ComponentName comp = new ComponentName(cpi.packageName, cpi.name);
                checkTime(startTime, "getContentProviderImpl: before getProviderByClass");
                cpr = mProviderMap.getProviderByClass(comp, userId);
                checkTime(startTime, "getContentProviderImpl: after getProviderByClass");
                final boolean firstClass = cpr == null;
                if (firstClass) {
                    final long ident = Binder.clearCallingIdentity();

                    // If permissions need a review before any of the app components can run,
                    // we return no provider and launch a review activity if the calling app
                    // is in the foreground.
                    if (!requestTargetProviderPermissionsReviewIfNeededLocked(cpi, r, userId)) {
                        return null;
                    }

                    try {
                        checkTime(startTime, "getContentProviderImpl: before getApplicationInfo");
                        ApplicationInfo ai =
                            AppGlobals.getPackageManager().
                                getApplicationInfo(
                                        cpi.applicationInfo.packageName,
                                        STOCK_PM_FLAGS, userId);
                        checkTime(startTime, "getContentProviderImpl: after getApplicationInfo");
                        if (ai == null) {
                            Slog.w(TAG, "No package info for content provider "
                                    + cpi.name);
                            return null;
                        }
                        ai = getAppInfoForUser(ai, userId);
                        cpr = new ContentProviderRecord(this, cpi, ai, comp, singleton);
                    } catch (RemoteException ex) {
                        // pm is in same process, this will never happen.
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                }

                checkTime(startTime, "getContentProviderImpl: now have ContentProviderRecord");

                if (r != null && cpr.canRunHere(r)) {
                    // If this is a multiprocess provider, then just return its
                    // info and allow the caller to instantiate it.  Only do
                    // this if the provider is the same user as the caller's
                    // process, or can run as root (so can be in any process).
                    return cpr.newHolder(null);
                }

                if (DEBUG_PROVIDER) Slog.w(TAG_PROVIDER, "LAUNCHING REMOTE PROVIDER (myuid "
                            + (r != null ? r.uid : null) + " pruid " + cpr.appInfo.uid + "): "
                            + cpr.info.name + " callers=" + Debug.getCallers(6));

                // This is single process, and our app is now connecting to it.
                // See if we are already in the process of launching this
                // provider.
                final int N = mLaunchingProviders.size();
                int i;
                for (i = 0; i < N; i++) {
                    if (mLaunchingProviders.get(i) == cpr) {
                        break;
                    }
                }

                // If the provider is not already being launched, then get it
                // started.
                if (i >= N) {
                    final long origId = Binder.clearCallingIdentity();

                    try {
                        // Content provider is now in use, its package can't be stopped.
                        try {
                            checkTime(startTime, "getContentProviderImpl: before set stopped state");
                            AppGlobals.getPackageManager().setPackageStoppedState(
                                    cpr.appInfo.packageName, false, userId);
                            checkTime(startTime, "getContentProviderImpl: after set stopped state");
                        } catch (RemoteException e) {
                        } catch (IllegalArgumentException e) {
                            Slog.w(TAG, "Failed trying to unstop package "
                                    + cpr.appInfo.packageName + ": " + e);
                        }

                        // Use existing process if already started
                        checkTime(startTime, "getContentProviderImpl: looking for process record");
                        ProcessRecord proc = getProcessRecordLocked(
                                cpi.processName, cpr.appInfo.uid, false);
                        if (proc != null && proc.thread != null && !proc.killed) {
                            if (DEBUG_PROVIDER) Slog.d(TAG_PROVIDER,
                                    "Installing in existing process " + proc);
                            if (!proc.pubProviders.containsKey(cpi.name)) {
                                checkTime(startTime, "getContentProviderImpl: scheduling install");
                                proc.pubProviders.put(cpi.name, cpr);
                                try {
                                    proc.thread.scheduleInstallProvider(cpi);
                                } catch (RemoteException e) {
                                }
                            }
                        } else {
                            checkTime(startTime, "getContentProviderImpl: before start process");
                            proc = startProcessLocked(cpi.processName,
                                    cpr.appInfo, false, 0, "content provider",
                                    new ComponentName(cpi.applicationInfo.packageName,
                                            cpi.name), false, false, false);
                            checkTime(startTime, "getContentProviderImpl: after start process");
                            if (proc == null) {
                                Slog.w(TAG, "Unable to launch app "
                                        + cpi.applicationInfo.packageName + "/"
                                        + cpi.applicationInfo.uid + " for provider "
                                        + name + ": process is bad");
                                return null;
                            }
                        }
                        cpr.launchingApp = proc;
                        mLaunchingProviders.add(cpr);
                    } finally {
                        Binder.restoreCallingIdentity(origId);
                    }
                }

                checkTime(startTime, "getContentProviderImpl: updating data structures");

                // Make sure the provider is published (the same provider class
                // may be published under multiple names).
                if (firstClass) {
                    mProviderMap.putProviderByClass(comp, cpr);
                }

                mProviderMap.putProviderByName(name, cpr);
                conn = incProviderCountLocked(r, cpr, token, callingUid, callingPackage, callingTag,
                        stable);
                if (conn != null) {
                    conn.waiting = true;
                }
            }
            checkTime(startTime, "getContentProviderImpl: done!");

            grantEphemeralAccessLocked(userId, null /*intent*/,
                    UserHandle.getAppId(cpi.applicationInfo.uid),
                    UserHandle.getAppId(Binder.getCallingUid()));
        }

        // Wait for the provider to be published...
        final long timeout = SystemClock.uptimeMillis() + CONTENT_PROVIDER_WAIT_TIMEOUT;
        boolean timedOut = false;
        synchronized (cpr) {
            while (cpr.provider == null) {
                if (cpr.launchingApp == null) {
                    Slog.w(TAG, "Unable to launch app "
                            + cpi.applicationInfo.packageName + "/"
                            + cpi.applicationInfo.uid + " for provider "
                            + name + ": launching app became null");
                    EventLog.writeEvent(EventLogTags.AM_PROVIDER_LOST_PROCESS,
                            UserHandle.getUserId(cpi.applicationInfo.uid),
                            cpi.applicationInfo.packageName,
                            cpi.applicationInfo.uid, name);
                    return null;
                }
                try {
                    final long wait = Math.max(0L, timeout - SystemClock.uptimeMillis());
                    if (DEBUG_MU) Slog.v(TAG_MU,
                            "Waiting to start provider " + cpr
                            + " launchingApp=" + cpr.launchingApp + " for " + wait + " ms");
                    if (conn != null) {
                        conn.waiting = true;
                    }
                    cpr.wait(wait);
                    if (cpr.provider == null) {
                        timedOut = true;
                        break;
                    }
                } catch (InterruptedException ex) {
                } finally {
                    if (conn != null) {
                        conn.waiting = false;
                    }
                }
            }
        }
        if (timedOut) {
            // Note we do it afer releasing the lock.
            String callerName = "unknown";
            synchronized (this) {
                final ProcessRecord record = mProcessList.getLRURecordForAppLocked(caller);
                if (record != null) {
                    callerName = record.processName;
                }
            }

            Slog.wtf(TAG, "Timeout waiting for provider "
                    + cpi.applicationInfo.packageName + "/"
                    + cpi.applicationInfo.uid + " for provider "
                    + name
                    + " providerRunning=" + providerRunning
                    + " caller=" + callerName + "/" + Binder.getCallingUid());
            return null;
        }

        return cpr.newHolder(conn);
    }

    private static final class StartActivityRunnable implements Runnable {
        private final Context mContext;
        private final Intent mIntent;
        private final UserHandle mUserHandle;

        StartActivityRunnable(Context context, Intent intent, UserHandle userHandle) {
            this.mContext = context;
            this.mIntent = intent;
            this.mUserHandle = userHandle;
        }

        @Override
        public void run() {
            mContext.startActivityAsUser(mIntent, mUserHandle);
        }
    }

    private boolean requestTargetProviderPermissionsReviewIfNeededLocked(ProviderInfo cpi,
            ProcessRecord r, final int userId) {
        if (getPackageManagerInternalLocked().isPermissionsReviewRequired(
                cpi.packageName, userId)) {

            final boolean callerForeground = r == null || r.setSchedGroup
                    != ProcessList.SCHED_GROUP_BACKGROUND;

            // Show a permission review UI only for starting from a foreground app
            if (!callerForeground) {
                Slog.w(TAG, "u" + userId + " Instantiating a provider in package"
                        + cpi.packageName + " requires a permissions review");
                return false;
            }

            final Intent intent = new Intent(Intent.ACTION_REVIEW_PERMISSIONS);
            intent.addFlags(FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, cpi.packageName);

            if (DEBUG_PERMISSIONS_REVIEW) {
                Slog.i(TAG, "u" + userId + " Launching permission review "
                        + "for package " + cpi.packageName);
            }

            final UserHandle userHandle = new UserHandle(userId);
            mHandler.post(new StartActivityRunnable(mContext, intent, userHandle));

            return false;
        }

        return true;
    }

    /**
     * Returns the PackageManager. Used by classes hosted by {@link ActivityManagerService}. The
     * PackageManager could be unavailable at construction time and therefore needs to be accessed
     * on demand.
     */
    @VisibleForTesting
    public IPackageManager getPackageManager() {
        return AppGlobals.getPackageManager();
    }

    @VisibleForTesting
    public PackageManagerInternal getPackageManagerInternalLocked() {
        if (mPackageManagerInt == null) {
            mPackageManagerInt = LocalServices.getService(PackageManagerInternal.class);
        }
        return mPackageManagerInt;
    }

    @Override
    public final ContentProviderHolder getContentProvider(
            IApplicationThread caller, String callingPackage, String name, int userId,
            boolean stable) {
        enforceNotIsolatedCaller("getContentProvider");
        if (caller == null) {
            String msg = "null IApplicationThread when getting content provider "
                    + name;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        // The incoming user check is now handled in checkContentProviderPermissionLocked() to deal
        // with cross-user grant.
        final int callingUid = Binder.getCallingUid();
        if (callingPackage != null && mAppOpsService.checkPackage(callingUid, callingPackage)
                != AppOpsManager.MODE_ALLOWED) {
            throw new SecurityException("Given calling package " + callingPackage
                    + " does not match caller's uid " + callingUid);
        }
        return getContentProviderImpl(caller, name, null, callingUid, callingPackage,
                null, stable, userId);
    }

    public ContentProviderHolder getContentProviderExternal(
            String name, int userId, IBinder token, String tag) {
        enforceCallingPermission(android.Manifest.permission.ACCESS_CONTENT_PROVIDERS_EXTERNALLY,
            "Do not have permission in call getContentProviderExternal()");
        userId = mUserController.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, false, ALLOW_FULL_ONLY, "getContentProvider", null);
        return getContentProviderExternalUnchecked(name, token, Binder.getCallingUid(),
                tag != null ? tag : "*external*", userId);
    }

    private ContentProviderHolder getContentProviderExternalUnchecked(String name,
            IBinder token, int callingUid, String callingTag, int userId) {
        return getContentProviderImpl(null, name, token, callingUid, null, callingTag,
                true, userId);
    }

    /**
     * Drop a content provider from a ProcessRecord's bookkeeping
     */
    public void removeContentProvider(IBinder connection, boolean stable) {
        enforceNotIsolatedCaller("removeContentProvider");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                ContentProviderConnection conn;
                try {
                    conn = (ContentProviderConnection)connection;
                } catch (ClassCastException e) {
                    String msg ="removeContentProvider: " + connection
                            + " not a ContentProviderConnection";
                    Slog.w(TAG, msg);
                    throw new IllegalArgumentException(msg);
                }
                if (conn == null) {
                    throw new NullPointerException("connection is null");
                }
                if (decProviderCountLocked(conn, null, null, stable)) {
                    updateOomAdjLocked();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /** @deprecated - Use {@link #removeContentProviderExternalAsUser} which takes a user ID. */
    @Deprecated
    @Override
    public void removeContentProviderExternal(String name, IBinder token) {
        removeContentProviderExternalAsUser(name, token, UserHandle.getCallingUserId());
    }

    @Override
    public void removeContentProviderExternalAsUser(String name, IBinder token, int userId) {
        enforceCallingPermission(android.Manifest.permission.ACCESS_CONTENT_PROVIDERS_EXTERNALLY,
            "Do not have permission in call removeContentProviderExternal()");
        long ident = Binder.clearCallingIdentity();
        try {
            removeContentProviderExternalUnchecked(name, token, userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void removeContentProviderExternalUnchecked(String name, IBinder token, int userId) {
        synchronized (this) {
            ContentProviderRecord cpr = mProviderMap.getProviderByName(name, userId);
            if(cpr == null) {
                //remove from mProvidersByClass
                if(DEBUG_ALL) Slog.v(TAG, name+" content provider not found in providers list");
                return;
            }

            //update content provider record entry info
            ComponentName comp = new ComponentName(cpr.info.packageName, cpr.info.name);
            ContentProviderRecord localCpr = mProviderMap.getProviderByClass(comp, userId);
            if (localCpr.hasExternalProcessHandles()) {
                if (localCpr.removeExternalProcessHandleLocked(token)) {
                    updateOomAdjLocked();
                } else {
                    Slog.e(TAG, "Attmpt to remove content provider " + localCpr
                            + " with no external reference for token: "
                            + token + ".");
                }
            } else {
                Slog.e(TAG, "Attmpt to remove content provider: " + localCpr
                        + " with no external references.");
            }
        }
    }

    public final void publishContentProviders(IApplicationThread caller,
            List<ContentProviderHolder> providers) {
        if (providers == null) {
            return;
        }

        enforceNotIsolatedCaller("publishContentProviders");
        synchronized (this) {
            final ProcessRecord r = getRecordForAppLocked(caller);
            if (DEBUG_MU) Slog.v(TAG_MU, "ProcessRecord uid = " + r.uid);
            if (r == null) {
                throw new SecurityException(
                        "Unable to find app for caller " + caller
                      + " (pid=" + Binder.getCallingPid()
                      + ") when publishing content providers");
            }

            final long origId = Binder.clearCallingIdentity();

            final int N = providers.size();
            for (int i = 0; i < N; i++) {
                ContentProviderHolder src = providers.get(i);
                if (src == null || src.info == null || src.provider == null) {
                    continue;
                }
                ContentProviderRecord dst = r.pubProviders.get(src.info.name);
                if (DEBUG_MU) Slog.v(TAG_MU, "ContentProviderRecord uid = " + dst.uid);
                if (dst != null) {
                    ComponentName comp = new ComponentName(dst.info.packageName, dst.info.name);
                    mProviderMap.putProviderByClass(comp, dst);
                    String names[] = dst.info.authority.split(";");
                    for (int j = 0; j < names.length; j++) {
                        mProviderMap.putProviderByName(names[j], dst);
                    }

                    int launchingCount = mLaunchingProviders.size();
                    int j;
                    boolean wasInLaunchingProviders = false;
                    for (j = 0; j < launchingCount; j++) {
                        if (mLaunchingProviders.get(j) == dst) {
                            mLaunchingProviders.remove(j);
                            wasInLaunchingProviders = true;
                            j--;
                            launchingCount--;
                        }
                    }
                    if (wasInLaunchingProviders) {
                        mHandler.removeMessages(CONTENT_PROVIDER_PUBLISH_TIMEOUT_MSG, r);
                    }
                    synchronized (dst) {
                        dst.provider = src.provider;
                        dst.setProcess(r);
                        dst.notifyAll();
                    }
                    updateOomAdjLocked(r, true);
                    maybeUpdateProviderUsageStatsLocked(r, src.info.packageName,
                            src.info.authority);
                }
            }

            Binder.restoreCallingIdentity(origId);
        }
    }

    public boolean refContentProvider(IBinder connection, int stable, int unstable) {
        ContentProviderConnection conn;
        try {
            conn = (ContentProviderConnection)connection;
        } catch (ClassCastException e) {
            String msg ="refContentProvider: " + connection
                    + " not a ContentProviderConnection";
            Slog.w(TAG, msg);
            throw new IllegalArgumentException(msg);
        }
        if (conn == null) {
            throw new NullPointerException("connection is null");
        }

        synchronized (this) {
            if (stable > 0) {
                conn.numStableIncs += stable;
            }
            stable = conn.stableCount + stable;
            if (stable < 0) {
                throw new IllegalStateException("stableCount < 0: " + stable);
            }

            if (unstable > 0) {
                conn.numUnstableIncs += unstable;
            }
            unstable = conn.unstableCount + unstable;
            if (unstable < 0) {
                throw new IllegalStateException("unstableCount < 0: " + unstable);
            }

            if ((stable+unstable) <= 0) {
                throw new IllegalStateException("ref counts can't go to zero here: stable="
                        + stable + " unstable=" + unstable);
            }
            conn.stableCount = stable;
            conn.unstableCount = unstable;
            return !conn.dead;
        }
    }

    public void unstableProviderDied(IBinder connection) {
        ContentProviderConnection conn;
        try {
            conn = (ContentProviderConnection)connection;
        } catch (ClassCastException e) {
            String msg ="refContentProvider: " + connection
                    + " not a ContentProviderConnection";
            Slog.w(TAG, msg);
            throw new IllegalArgumentException(msg);
        }
        if (conn == null) {
            throw new NullPointerException("connection is null");
        }

        // Safely retrieve the content provider associated with the connection.
        IContentProvider provider;
        synchronized (this) {
            provider = conn.provider.provider;
        }

        if (provider == null) {
            // Um, yeah, we're way ahead of you.
            return;
        }

        // Make sure the caller is being honest with us.
        if (provider.asBinder().pingBinder()) {
            // Er, no, still looks good to us.
            synchronized (this) {
                Slog.w(TAG, "unstableProviderDied: caller " + Binder.getCallingUid()
                        + " says " + conn + " died, but we don't agree");
                return;
            }
        }

        // Well look at that!  It's dead!
        synchronized (this) {
            if (conn.provider.provider != provider) {
                // But something changed...  good enough.
                return;
            }

            ProcessRecord proc = conn.provider.proc;
            if (proc == null || proc.thread == null) {
                // Seems like the process is already cleaned up.
                return;
            }

            // As far as we're concerned, this is just like receiving a
            // death notification...  just a bit prematurely.
            reportUidInfoMessageLocked(TAG,
                    "Process " + proc.processName + " (pid " + proc.pid
                            + ") early provider death",
                    proc.info.uid);
            final long ident = Binder.clearCallingIdentity();
            try {
                appDiedLocked(proc);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void appNotRespondingViaProvider(IBinder connection) {
        enforceCallingPermission(REMOVE_TASKS, "appNotRespondingViaProvider()");

        final ContentProviderConnection conn = (ContentProviderConnection) connection;
        if (conn == null) {
            Slog.w(TAG, "ContentProviderConnection is null");
            return;
        }

        final ProcessRecord host = conn.provider.proc;
        if (host == null) {
            Slog.w(TAG, "Failed to find hosting ProcessRecord");
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                host.appNotResponding(
                        null, null, null, null, false, "ContentProvider not responding");
            }
        });
    }

    public final void installSystemProviders() {
        List<ProviderInfo> providers;
        synchronized (this) {
            ProcessRecord app = mProcessList.mProcessNames.get("system", SYSTEM_UID);
            providers = generateApplicationProvidersLocked(app);
            if (providers != null) {
                for (int i=providers.size()-1; i>=0; i--) {
                    ProviderInfo pi = (ProviderInfo)providers.get(i);
                    if ((pi.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM) == 0) {
                        Slog.w(TAG, "Not installing system proc provider " + pi.name
                                + ": not system .apk");
                        providers.remove(i);
                    }
                }
            }
        }
        if (providers != null) {
            mSystemThread.installSystemProviders(providers);
        }

        synchronized (this) {
            mSystemProvidersInstalled = true;
        }
        mConstants.start(mContext.getContentResolver());
        mCoreSettingsObserver = new CoreSettingsObserver(this);
        mActivityTaskManager.installSystemProviders();
        mDevelopmentSettingsObserver = new DevelopmentSettingsObserver();
        SettingsToPropertiesMapper.start(mContext.getContentResolver());
        mOomAdjuster.initSettings();

        // Now that the settings provider is published we can consider sending
        // in a rescue party.
        RescueParty.onSettingsProviderPublished(mContext);

        //mUsageStatsService.monitorPackages();
    }

    void startPersistentApps(int matchFlags) {
        if (mFactoryTest == FactoryTest.FACTORY_TEST_LOW_LEVEL) return;

        synchronized (this) {
            try {
                final List<ApplicationInfo> apps = AppGlobals.getPackageManager()
                        .getPersistentApplications(STOCK_PM_FLAGS | matchFlags).getList();
                for (ApplicationInfo app : apps) {
                    if (!"android".equals(app.packageName)) {
                        addAppLocked(app, null, false, null /* ABI override */);
                    }
                }
            } catch (RemoteException ex) {
            }
        }
    }

    /**
     * When a user is unlocked, we need to install encryption-unaware providers
     * belonging to any running apps.
     */
    void installEncryptionUnawareProviders(int userId) {
        // We're only interested in providers that are encryption unaware, and
        // we don't care about uninstalled apps, since there's no way they're
        // running at this point.
        final int matchFlags = GET_PROVIDERS | MATCH_DIRECT_BOOT_UNAWARE;

        synchronized (this) {
            final int NP = mProcessList.mProcessNames.getMap().size();
            for (int ip = 0; ip < NP; ip++) {
                final SparseArray<ProcessRecord> apps = mProcessList.mProcessNames.getMap().valueAt
                        (ip);
                final int NA = apps.size();
                for (int ia = 0; ia < NA; ia++) {
                    final ProcessRecord app = apps.valueAt(ia);
                    if (app.userId != userId || app.thread == null || app.unlocked) continue;

                    final int NG = app.pkgList.size();
                    for (int ig = 0; ig < NG; ig++) {
                        try {
                            final String pkgName = app.pkgList.keyAt(ig);
                            final PackageInfo pkgInfo = AppGlobals.getPackageManager()
                                    .getPackageInfo(pkgName, matchFlags, userId);
                            if (pkgInfo != null && !ArrayUtils.isEmpty(pkgInfo.providers)) {
                                for (ProviderInfo pi : pkgInfo.providers) {
                                    // TODO: keep in sync with generateApplicationProvidersLocked
                                    final boolean processMatch = Objects.equals(pi.processName,
                                            app.processName) || pi.multiprocess;
                                    final boolean userMatch = isSingleton(pi.processName,
                                            pi.applicationInfo, pi.name, pi.flags)
                                                    ? (app.userId == UserHandle.USER_SYSTEM) : true;
                                    if (processMatch && userMatch) {
                                        Log.v(TAG, "Installing " + pi);
                                        app.thread.scheduleInstallProvider(pi);
                                    } else {
                                        Log.v(TAG, "Skipping " + pi);
                                    }
                                }
                            }
                        } catch (RemoteException ignored) {
                        }
                    }
                }
            }
        }
    }

    /**
     * Allows apps to retrieve the MIME type of a URI.
     * If an app is in the same user as the ContentProvider, or if it is allowed to interact across
     * users, then it does not need permission to access the ContentProvider.
     * Either, it needs cross-user uri grants.
     *
     * CTS tests for this functionality can be run with "runtest cts-appsecurity".
     *
     * Test cases are at cts/tests/appsecurity-tests/test-apps/UsePermissionDiffCert/
     *     src/com/android/cts/usespermissiondiffcertapp/AccessPermissionWithDiffSigTest.java
     */
    public String getProviderMimeType(Uri uri, int userId) {
        enforceNotIsolatedCaller("getProviderMimeType");
        final String name = uri.getAuthority();
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        long ident = 0;
        boolean clearedIdentity = false;
        userId = mUserController.unsafeConvertIncomingUser(userId);
        if (canClearIdentity(callingPid, callingUid, userId)) {
            clearedIdentity = true;
            ident = Binder.clearCallingIdentity();
        }
        ContentProviderHolder holder = null;
        try {
            holder = getContentProviderExternalUnchecked(name, null, callingUid,
                    "*getmimetype*", userId);
            if (holder != null) {
                return holder.provider.getType(uri);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Content provider dead retrieving " + uri, e);
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Exception while determining type of " + uri, e);
            return null;
        } finally {
            // We need to clear the identity to call removeContentProviderExternalUnchecked
            if (!clearedIdentity) {
                ident = Binder.clearCallingIdentity();
            }
            try {
                if (holder != null) {
                    removeContentProviderExternalUnchecked(name, null, userId);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        return null;
    }

    private boolean canClearIdentity(int callingPid, int callingUid, int userId) {
        if (UserHandle.getUserId(callingUid) == userId) {
            return true;
        }
        if (checkComponentPermission(INTERACT_ACROSS_USERS, callingPid,
                callingUid, -1, true) == PackageManager.PERMISSION_GRANTED
                || checkComponentPermission(INTERACT_ACROSS_USERS_FULL, callingPid,
                callingUid, -1, true) == PackageManager.PERMISSION_GRANTED) {
                return true;
        }
        return false;
    }

    // =========================================================
    // GLOBAL MANAGEMENT
    // =========================================================

    private boolean uidOnBackgroundWhitelist(final int uid) {
        final int appId = UserHandle.getAppId(uid);
        final int[] whitelist = mBackgroundAppIdWhitelist;
        final int N = whitelist.length;
        for (int i = 0; i < N; i++) {
            if (appId == whitelist[i]) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isBackgroundRestricted(String packageName) {
        final int callingUid = Binder.getCallingUid();
        final IPackageManager pm = AppGlobals.getPackageManager();
        try {
            final int packageUid = pm.getPackageUid(packageName, MATCH_DEBUG_TRIAGED_MISSING,
                    UserHandle.getUserId(callingUid));
            if (packageUid != callingUid) {
                throw new IllegalArgumentException("Uid " + callingUid
                        + " cannot query restriction state for package " + packageName);
            }
        } catch (RemoteException exc) {
            // Ignore.
        }
        return isBackgroundRestrictedNoCheck(callingUid, packageName);
    }

    boolean isBackgroundRestrictedNoCheck(final int uid, final String packageName) {
        final int mode = mAppOpsService.checkOperation(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,
                uid, packageName);
        return mode != AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void backgroundWhitelistUid(final int uid) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only the OS may call backgroundWhitelistUid()");
        }

        if (DEBUG_BACKGROUND_CHECK) {
            Slog.i(TAG, "Adding uid " + uid + " to bg uid whitelist");
        }
        synchronized (this) {
            final int N = mBackgroundAppIdWhitelist.length;
            int[] newList = new int[N+1];
            System.arraycopy(mBackgroundAppIdWhitelist, 0, newList, 0, N);
            newList[N] = UserHandle.getAppId(uid);
            mBackgroundAppIdWhitelist = newList;
        }
    }

    @GuardedBy("this")
    final ProcessRecord addAppLocked(ApplicationInfo info, String customProcess, boolean isolated,
            String abiOverride) {
        return addAppLocked(info, customProcess, isolated, false /* disableHiddenApiChecks */,
                false /* mountExtStorageFull */, abiOverride);
    }

    // TODO: Move to ProcessList?
    @GuardedBy("this")
    final ProcessRecord addAppLocked(ApplicationInfo info, String customProcess, boolean isolated,
            boolean disableHiddenApiChecks, boolean mountExtStorageFull, String abiOverride) {
        ProcessRecord app;
        if (!isolated) {
            app = getProcessRecordLocked(customProcess != null ? customProcess : info.processName,
                    info.uid, true);
        } else {
            app = null;
        }

        if (app == null) {
            app = mProcessList.newProcessRecordLocked(info, customProcess, isolated, 0, false);
            mProcessList.updateLruProcessLocked(app, false, null);
            updateOomAdjLocked();
        }

        // This package really, really can not be stopped.
        try {
            AppGlobals.getPackageManager().setPackageStoppedState(
                    info.packageName, false, UserHandle.getUserId(app.uid));
        } catch (RemoteException e) {
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Failed trying to unstop package "
                    + info.packageName + ": " + e);
        }

        if ((info.flags & PERSISTENT_MASK) == PERSISTENT_MASK) {
            app.setPersistent(true);
            app.maxAdj = ProcessList.PERSISTENT_PROC_ADJ;
        }
        if (app.thread == null && mPersistentStartingProcesses.indexOf(app) < 0) {
            mPersistentStartingProcesses.add(app);
            mProcessList.startProcessLocked(app, "added application",
                    customProcess != null ? customProcess : app.processName, disableHiddenApiChecks,
                    mountExtStorageFull, abiOverride);
        }

        return app;
    }

    public void unhandledBack() {
        mActivityTaskManager.unhandledBack();
    }

    public ParcelFileDescriptor openContentUri(String uriString) throws RemoteException {
        enforceNotIsolatedCaller("openContentUri");
        final int userId = UserHandle.getCallingUserId();
        final Uri uri = Uri.parse(uriString);
        String name = uri.getAuthority();
        ContentProviderHolder cph = getContentProviderExternalUnchecked(name, null,
                Binder.getCallingUid(), "*opencontent*", userId);
        ParcelFileDescriptor pfd = null;
        if (cph != null) {
            // We record the binder invoker's uid in thread-local storage before
            // going to the content provider to open the file.  Later, in the code
            // that handles all permissions checks, we look for this uid and use
            // that rather than the Activity Manager's own uid.  The effect is that
            // we do the check against the caller's permissions even though it looks
            // to the content provider like the Activity Manager itself is making
            // the request.
            Binder token = new Binder();
            sCallerIdentity.set(new Identity(
                    token, Binder.getCallingPid(), Binder.getCallingUid()));
            try {
                pfd = cph.provider.openFile(null, uri, "r", null, token);
            } catch (FileNotFoundException e) {
                // do nothing; pfd will be returned null
            } finally {
                // Ensure that whatever happens, we clean up the identity state
                sCallerIdentity.remove();
                // Ensure we're done with the provider.
                removeContentProviderExternalUnchecked(name, null, userId);
            }
        } else {
            Slog.d(TAG, "Failed to get provider for authority '" + name + "'");
        }
        return pfd;
    }

    void reportGlobalUsageEventLocked(int event) {
        mUsageStatsService.reportEvent("android", mUserController.getCurrentUserId(), event);
        int[] profiles = mUserController.getCurrentProfileIds();
        if (profiles != null) {
            for (int i = profiles.length - 1; i >= 0; i--) {
                mUsageStatsService.reportEvent((String)null, profiles[i], event);
            }
        }
    }

    void reportCurWakefulnessUsageEventLocked() {
        reportGlobalUsageEventLocked(mWakefulness == PowerManagerInternal.WAKEFULNESS_AWAKE
                ? UsageEvents.Event.SCREEN_INTERACTIVE
                : UsageEvents.Event.SCREEN_NON_INTERACTIVE);
    }

    void onWakefulnessChanged(int wakefulness) {
        synchronized(this) {
            boolean wasAwake = mWakefulness == PowerManagerInternal.WAKEFULNESS_AWAKE;
            boolean isAwake = wakefulness == PowerManagerInternal.WAKEFULNESS_AWAKE;
            mWakefulness = wakefulness;

            if (wasAwake != isAwake) {
                // Also update state in a special way for running foreground services UI.
                mServices.updateScreenStateLocked(isAwake);
                reportCurWakefulnessUsageEventLocked();
                mActivityTaskManager.onScreenAwakeChanged(isAwake);
                mOomAdjProfiler.onWakefulnessChanged(wakefulness);
            }
            updateOomAdjLocked();
        }
    }

    @Override
    public void notifyCleartextNetwork(int uid, byte[] firstPacket) {
        mHandler.obtainMessage(NOTIFY_CLEARTEXT_NETWORK_MSG, uid, 0, firstPacket).sendToTarget();
    }

    @Override
    public boolean shutdown(int timeout) {
        if (checkCallingPermission(android.Manifest.permission.SHUTDOWN)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.SHUTDOWN);
        }

        final boolean timedout = mAtmInternal.shuttingDown(mBooted, timeout);

        mAppOpsService.shutdown();
        if (mUsageStatsService != null) {
            mUsageStatsService.prepareShutdown();
        }
        mBatteryStatsService.shutdown();
        synchronized (this) {
            mProcessStats.shutdownLocked();
        }

        return timedout;
    }

    @Override
    public void notifyLockedProfile(@UserIdInt int userId) {
        mAtmInternal.notifyLockedProfile(userId, mUserController.getCurrentUserId());
    }

    @Override
    public void startConfirmDeviceCredentialIntent(Intent intent, Bundle options) {
        mAtmInternal.startConfirmDeviceCredentialIntent(intent, options);
    }

    @Override
    public void stopAppSwitches() {
        mActivityTaskManager.stopAppSwitches();
    }

    @Override
    public void resumeAppSwitches() {
        mActivityTaskManager.resumeAppSwitches();
    }

    public void setDebugApp(String packageName, boolean waitForDebugger,
            boolean persistent) {
        enforceCallingPermission(android.Manifest.permission.SET_DEBUG_APP,
                "setDebugApp()");

        long ident = Binder.clearCallingIdentity();
        try {
            // Note that this is not really thread safe if there are multiple
            // callers into it at the same time, but that's not a situation we
            // care about.
            if (persistent) {
                final ContentResolver resolver = mContext.getContentResolver();
                Settings.Global.putString(
                    resolver, Settings.Global.DEBUG_APP,
                    packageName);
                Settings.Global.putInt(
                    resolver, Settings.Global.WAIT_FOR_DEBUGGER,
                    waitForDebugger ? 1 : 0);
            }

            synchronized (this) {
                if (!persistent) {
                    mOrigDebugApp = mDebugApp;
                    mOrigWaitForDebugger = mWaitForDebugger;
                }
                mDebugApp = packageName;
                mWaitForDebugger = waitForDebugger;
                mDebugTransient = !persistent;
                if (packageName != null) {
                    forceStopPackageLocked(packageName, -1, false, false, true, true,
                            false, UserHandle.USER_ALL, "set debug app");
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Set or remove an agent to be run whenever an app with the given process name starts.
     *
     * This method will not check whether the given process name matches a debuggable app. That
     * would require scanning all current packages, and a rescan when new packages are installed
     * or updated.
     *
     * Instead, do the check when an application is started and matched to a stored agent.
     *
     * @param packageName the process name of the app.
     * @param agent the agent string to be used, or null to remove any previously set agent.
     */
    @Override
    public void setAgentApp(@NonNull String packageName, @Nullable String agent) {
        synchronized (this) {
            // note: hijacking SET_ACTIVITY_WATCHER, but should be changed to
            // its own permission.
            if (checkCallingPermission(
                    android.Manifest.permission.SET_ACTIVITY_WATCHER) !=
                        PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException(
                        "Requires permission " + android.Manifest.permission.SET_ACTIVITY_WATCHER);
            }

            if (agent == null) {
                if (mAppAgentMap != null) {
                    mAppAgentMap.remove(packageName);
                    if (mAppAgentMap.isEmpty()) {
                        mAppAgentMap = null;
                    }
                }
            } else {
                if (mAppAgentMap == null) {
                    mAppAgentMap = new HashMap<>();
                }
                if (mAppAgentMap.size() >= 100) {
                    // Limit the size of the map, to avoid OOMEs.
                    Slog.e(TAG, "App agent map has too many entries, cannot add " + packageName
                            + "/" + agent);
                    return;
                }
                mAppAgentMap.put(packageName, agent);
            }
        }
    }

    void setTrackAllocationApp(ApplicationInfo app, String processName) {
        synchronized (this) {
            boolean isDebuggable = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
            if (!isDebuggable) {
                if ((app.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
                    throw new SecurityException("Process not debuggable: " + app.packageName);
                }
            }

            mTrackAllocationApp = processName;
        }
    }

    void setProfileApp(ApplicationInfo app, String processName, ProfilerInfo profilerInfo) {
        synchronized (this) {
            boolean isDebuggable = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
            if (!isDebuggable) {
                if ((app.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
                    throw new SecurityException("Process not debuggable: " + app.packageName);
                }
            }
            mProfileData.setProfileApp(processName);

            if (mProfileData.getProfilerInfo() != null) {
                if (mProfileData.getProfilerInfo().profileFd != null) {
                    try {
                        mProfileData.getProfilerInfo().profileFd.close();
                    } catch (IOException e) {
                    }
                }
            }
            mProfileData.setProfilerInfo(new ProfilerInfo(profilerInfo));
            mProfileType = 0;
        }
    }

    void setNativeDebuggingAppLocked(ApplicationInfo app, String processName) {
        boolean isDebuggable = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
        if (!isDebuggable) {
            if ((app.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
                throw new SecurityException("Process not debuggable: " + app.packageName);
            }
        }
        mNativeDebuggingApp = processName;
    }

    @Override
    public void setAlwaysFinish(boolean enabled) {
        enforceCallingPermission(android.Manifest.permission.SET_ALWAYS_FINISH,
                "setAlwaysFinish()");

        long ident = Binder.clearCallingIdentity();
        try {
            Settings.Global.putInt(
                    mContext.getContentResolver(),
                    Settings.Global.ALWAYS_FINISH_ACTIVITIES, enabled ? 1 : 0);

            synchronized (this) {
                mAlwaysFinishActivities = enabled;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void setActivityController(IActivityController controller, boolean imAMonkey) {
        mActivityTaskManager.setActivityController(controller, imAMonkey);
    }

    @Override
    public void setUserIsMonkey(boolean userIsMonkey) {
        synchronized (this) {
            synchronized (mPidsSelfLocked) {
                final int callingPid = Binder.getCallingPid();
                ProcessRecord proc = mPidsSelfLocked.get(callingPid);
                if (proc == null) {
                    throw new SecurityException("Unknown process: " + callingPid);
                }
                if (proc.getActiveInstrumentation() == null
                        || proc.getActiveInstrumentation().mUiAutomationConnection == null) {
                    throw new SecurityException("Only an instrumentation process "
                            + "with a UiAutomation can call setUserIsMonkey");
                }
            }
            mUserIsMonkey = userIsMonkey;
        }
    }

    @Override
    public boolean isUserAMonkey() {
        synchronized (this) {
            // If there is a controller also implies the user is a monkey.
            return mUserIsMonkey || mActivityTaskManager.isControllerAMonkey();
        }
    }

    /**
     * @deprecated This method is only used by a few internal components and it will soon be
     * replaced by a proper bug report API (which will be restricted to a few, pre-defined apps).
     * No new code should be calling it.
     */
    @Deprecated
    @Override
    public void requestBugReport(int bugreportType) {
        String extraOptions = null;
        switch (bugreportType) {
            case ActivityManager.BUGREPORT_OPTION_FULL:
                extraOptions = "bugreportfull";
                break;
            case ActivityManager.BUGREPORT_OPTION_INTERACTIVE:
                extraOptions = "bugreportplus";
                break;
            case ActivityManager.BUGREPORT_OPTION_REMOTE:
                extraOptions = "bugreportremote";
                break;
            case ActivityManager.BUGREPORT_OPTION_WEAR:
                extraOptions = "bugreportwear";
                break;
            case ActivityManager.BUGREPORT_OPTION_TELEPHONY:
                extraOptions = "bugreporttelephony";
                break;
            case ActivityManager.BUGREPORT_OPTION_WIFI:
                extraOptions = "bugreportwifi";
                break;
            default:
                throw new IllegalArgumentException("Provided bugreport type is not correct, value: "
                        + bugreportType);
        }
        // Always log caller, even if it does not have permission to dump.
        String type = extraOptions == null ? "bugreport" : extraOptions;
        Slog.i(TAG, type + " requested by UID " + Binder.getCallingUid());

        enforceCallingPermission(android.Manifest.permission.DUMP, "requestBugReport");
        if (extraOptions != null) {
            SystemProperties.set("dumpstate.options", extraOptions);
        }
        SystemProperties.set("ctl.start", "bugreport");
    }

    /**
     * @deprecated This method is only used by a few internal components and it will soon be
     * replaced by a proper bug report API (which will be restricted to a few, pre-defined apps).
     * No new code should be calling it.
     */
    @Deprecated
    private void requestBugReportWithDescription(String shareTitle, String shareDescription,
                                                 int bugreportType) {
        if (!TextUtils.isEmpty(shareTitle)) {
            if (shareTitle.length() > MAX_BUGREPORT_TITLE_SIZE) {
                String errorStr = "shareTitle should be less than " +
                        MAX_BUGREPORT_TITLE_SIZE + " characters";
                throw new IllegalArgumentException(errorStr);
            } else {
                if (!TextUtils.isEmpty(shareDescription)) {
                    int length;
                    try {
                        length = shareDescription.getBytes("UTF-8").length;
                    } catch (UnsupportedEncodingException e) {
                        String errorStr = "shareDescription: UnsupportedEncodingException";
                        throw new IllegalArgumentException(errorStr);
                    }
                    if (length > SystemProperties.PROP_VALUE_MAX) {
                        String errorStr = "shareTitle should be less than " +
                                SystemProperties.PROP_VALUE_MAX + " bytes";
                        throw new IllegalArgumentException(errorStr);
                    } else {
                        SystemProperties.set("dumpstate.options.description", shareDescription);
                    }
                }
                SystemProperties.set("dumpstate.options.title", shareTitle);
            }
        }

        Slog.d(TAG, "Bugreport notification title " + shareTitle
                + " description " + shareDescription);
        requestBugReport(bugreportType);
    }

    /**
     * @deprecated This method is only used by a few internal components and it will soon be
     * replaced by a proper bug report API (which will be restricted to a few, pre-defined apps).
     * No new code should be calling it.
     */
    @Deprecated
    @Override
    public void requestTelephonyBugReport(String shareTitle, String shareDescription) {
        requestBugReportWithDescription(shareTitle, shareDescription,
                ActivityManager.BUGREPORT_OPTION_TELEPHONY);
    }

    /**
     * @deprecated This method is only used by a few internal components and it will soon be
     * replaced by a proper bug report API (which will be restricted to a few, pre-defined apps).
     * No new code should be calling it.
     */
    @Deprecated
    @Override
    public void requestWifiBugReport(String shareTitle, String shareDescription) {
        requestBugReportWithDescription(shareTitle, shareDescription,
                ActivityManager.BUGREPORT_OPTION_WIFI);
    }

    public void registerProcessObserver(IProcessObserver observer) {
        enforceCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER,
                "registerProcessObserver()");
        synchronized (this) {
            mProcessObservers.register(observer);
        }
    }

    @Override
    public void unregisterProcessObserver(IProcessObserver observer) {
        synchronized (this) {
            mProcessObservers.unregister(observer);
        }
    }

    @Override
    public int getUidProcessState(int uid, String callingPackage) {
        if (!hasUsageStatsPermission(callingPackage)) {
            enforceCallingPermission(android.Manifest.permission.PACKAGE_USAGE_STATS,
                    "getUidProcessState");
        }

        synchronized (this) {
            return mProcessList.getUidProcStateLocked(uid);
        }
    }

    @Override
    public void registerUidObserver(IUidObserver observer, int which, int cutpoint,
            String callingPackage) {
        if (!hasUsageStatsPermission(callingPackage)) {
            enforceCallingPermission(android.Manifest.permission.PACKAGE_USAGE_STATS,
                    "registerUidObserver");
        }
        synchronized (this) {
            mUidObservers.register(observer, new UidObserverRegistration(Binder.getCallingUid(),
                    callingPackage, which, cutpoint));
        }
    }

    @Override
    public void unregisterUidObserver(IUidObserver observer) {
        synchronized (this) {
            mUidObservers.unregister(observer);
        }
    }

    @Override
    public boolean isUidActive(int uid, String callingPackage) {
        if (!hasUsageStatsPermission(callingPackage)) {
            enforceCallingPermission(android.Manifest.permission.PACKAGE_USAGE_STATS,
                    "isUidActive");
        }
        synchronized (this) {
            return isUidActiveLocked(uid);
        }
    }

    boolean isUidActiveLocked(int uid) {
        final UidRecord uidRecord = mProcessList.getUidRecordLocked(uid);
        return uidRecord != null && !uidRecord.setIdle;
    }

    @Override
    public void setPersistentVrThread(int tid) {
        mActivityTaskManager.setPersistentVrThread(tid);
    }

    /**
     * Schedule the given thread a normal scheduling priority.
     *
     * @param tid the tid of the thread to adjust the scheduling of.
     * @param suppressLogs {@code true} if any error logging should be disabled.
     *
     * @return {@code true} if this succeeded.
     */
    public static boolean scheduleAsRegularPriority(int tid, boolean suppressLogs) {
        try {
            Process.setThreadScheduler(tid, Process.SCHED_OTHER, 0);
            return true;
        } catch (IllegalArgumentException e) {
            if (!suppressLogs) {
                Slog.w(TAG, "Failed to set scheduling policy, thread does not exist:\n" + e);
            }
        } catch (SecurityException e) {
            if (!suppressLogs) {
                Slog.w(TAG, "Failed to set scheduling policy, not allowed:\n" + e);
            }
        }
        return false;
    }

    /**
     * Schedule the given thread an FIFO scheduling priority.
     *
     * @param tid the tid of the thread to adjust the scheduling of.
     * @param suppressLogs {@code true} if any error logging should be disabled.
     *
     * @return {@code true} if this succeeded.
     */
    public static boolean scheduleAsFifoPriority(int tid, boolean suppressLogs) {
        try {
            Process.setThreadScheduler(tid, Process.SCHED_FIFO | Process.SCHED_RESET_ON_FORK, 1);
            return true;
        } catch (IllegalArgumentException e) {
            if (!suppressLogs) {
                Slog.w(TAG, "Failed to set scheduling policy, thread does not exist:\n" + e);
            }
        } catch (SecurityException e) {
            if (!suppressLogs) {
                Slog.w(TAG, "Failed to set scheduling policy, not allowed:\n" + e);
            }
        }
        return false;
    }

    @Override
    public void setRenderThread(int tid) {
        synchronized (this) {
            ProcessRecord proc;
            int pid = Binder.getCallingPid();
            if (pid == Process.myPid()) {
                demoteSystemServerRenderThread(tid);
                return;
            }
            synchronized (mPidsSelfLocked) {
                proc = mPidsSelfLocked.get(pid);
                if (proc != null && proc.renderThreadTid == 0 && tid > 0) {
                    // ensure the tid belongs to the process
                    if (!isThreadInProcess(pid, tid)) {
                        throw new IllegalArgumentException(
                            "Render thread does not belong to process");
                    }
                    proc.renderThreadTid = tid;
                    if (DEBUG_OOM_ADJ) {
                        Slog.d("UI_FIFO", "Set RenderThread tid " + tid + " for pid " + pid);
                    }
                    // promote to FIFO now
                    if (proc.getCurrentSchedulingGroup() == ProcessList.SCHED_GROUP_TOP_APP) {
                        if (DEBUG_OOM_ADJ) Slog.d("UI_FIFO", "Promoting " + tid + "out of band");
                        if (mUseFifoUiScheduling) {
                            setThreadScheduler(proc.renderThreadTid,
                                SCHED_FIFO | SCHED_RESET_ON_FORK, 1);
                        } else {
                            setThreadPriority(proc.renderThreadTid, TOP_APP_PRIORITY_BOOST);
                        }
                    }
                } else {
                    if (DEBUG_OOM_ADJ) {
                        Slog.d("UI_FIFO", "Didn't set thread from setRenderThread? " +
                               "PID: " + pid + ", TID: " + tid + " FIFO: " +
                               mUseFifoUiScheduling);
                    }
                }
            }
        }
    }

    /**
     * We only use RenderThread in system_server to store task snapshots to the disk, which should
     * happen in the background. Thus, demote render thread from system_server to a lower priority.
     *
     * @param tid the tid of the RenderThread
     */
    private void demoteSystemServerRenderThread(int tid) {
        setThreadPriority(tid, Process.THREAD_PRIORITY_BACKGROUND);
    }

    @Override
    public boolean isVrModePackageEnabled(ComponentName packageName) {
        mActivityTaskManager.enforceSystemHasVrFeature();

        final VrManagerInternal vrService = LocalServices.getService(VrManagerInternal.class);

        return vrService.hasVrPackage(packageName, UserHandle.getCallingUserId()) ==
                VrManagerInternal.NO_ERROR;
    }

    public boolean isTopActivityImmersive() {
        return mActivityTaskManager.isTopActivityImmersive();
    }

    @Override
    public boolean isTopOfTask(IBinder token) {
        return mActivityTaskManager.isTopOfTask(token);
    }

    @Override
    public void setHasTopUi(boolean hasTopUi) throws RemoteException {
        if (checkCallingPermission(permission.INTERNAL_SYSTEM_WINDOW) != PERMISSION_GRANTED) {
            String msg = "Permission Denial: setHasTopUi() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + permission.INTERNAL_SYSTEM_WINDOW;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        final int pid = Binder.getCallingPid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                boolean changed = false;
                ProcessRecord pr;
                synchronized (mPidsSelfLocked) {
                    pr = mPidsSelfLocked.get(pid);
                    if (pr == null) {
                        Slog.w(TAG, "setHasTopUi called on unknown pid: " + pid);
                        return;
                    }
                    if (pr.hasTopUi() != hasTopUi) {
                        if (DEBUG_OOM_ADJ) {
                            Slog.d(TAG, "Setting hasTopUi=" + hasTopUi + " for pid=" + pid);
                        }
                        pr.setHasTopUi(hasTopUi);
                        changed = true;
                    }
                }
                if (changed) {
                    updateOomAdjLocked(pr, true);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void setRunningRemoteAnimation(int pid, boolean runningRemoteAnimation) {
        if (pid == Process.myPid()) {
            Slog.wtf(TAG, "system can't run remote animation");
            return;
        }
        synchronized (ActivityManagerService.this) {
            final ProcessRecord pr;
            synchronized (mPidsSelfLocked) {
                pr = mPidsSelfLocked.get(pid);
                if (pr == null) {
                    Slog.w(TAG, "setRunningRemoteAnimation called on unknown pid: " + pid);
                    return;
                }
            }
            if (pr.runningRemoteAnimation == runningRemoteAnimation) {
                return;
            }
            pr.runningRemoteAnimation = runningRemoteAnimation;
            if (DEBUG_OOM_ADJ) {
                Slog.i(TAG, "Setting runningRemoteAnimation=" + pr.runningRemoteAnimation
                        + " for pid=" + pid);
            }
            updateOomAdjLocked(pr, true);
        }
    }

    public final void enterSafeMode() {
        synchronized(this) {
            // It only makes sense to do this before the system is ready
            // and started launching other packages.
            if (!mSystemReady) {
                try {
                    AppGlobals.getPackageManager().enterSafeMode();
                } catch (RemoteException e) {
                }
            }

            mSafeMode = true;
        }
    }

    public final void showSafeModeOverlay() {
        View v = LayoutInflater.from(mContext).inflate(
                com.android.internal.R.layout.safe_mode, null);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.type = WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.format = v.getBackground().getOpacity();
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        ((WindowManager)mContext.getSystemService(
                Context.WINDOW_SERVICE)).addView(v, lp);
    }

    @Override
    public void noteWakeupAlarm(IIntentSender sender, WorkSource workSource, int sourceUid,
            String sourcePkg, String tag) {
        if (workSource != null && workSource.isEmpty()) {
            workSource = null;
        }

        if (sourceUid <= 0 && workSource == null) {
            // Try and derive a UID to attribute things to based on the caller.
            if (sender != null) {
                if (!(sender instanceof PendingIntentRecord)) {
                    return;
                }

                final PendingIntentRecord rec = (PendingIntentRecord) sender;
                final int callerUid = Binder.getCallingUid();
                sourceUid = rec.uid == callerUid ? SYSTEM_UID : rec.uid;
            } else {
                // TODO(narayan): Should we throw an exception in this case ? It means that we
                // haven't been able to derive a UID to attribute things to.
                return;
            }
        }

        if (DEBUG_POWER) {
            Slog.w(TAG, "noteWakupAlarm[ sourcePkg=" + sourcePkg + ", sourceUid=" + sourceUid
                    + ", workSource=" + workSource + ", tag=" + tag + "]");
        }

        mBatteryStatsService.noteWakupAlarm(sourcePkg, sourceUid, workSource, tag);
        if (workSource != null) {
            StatsLog.write(StatsLog.WAKEUP_ALARM_OCCURRED, workSource, tag, sourcePkg);
        } else {
            StatsLog.write_non_chained(StatsLog.WAKEUP_ALARM_OCCURRED, sourceUid, null, tag,
                    sourcePkg);
        }
    }

    @Override
    public void noteAlarmStart(IIntentSender sender, WorkSource workSource, int sourceUid,
            String tag) {
        if (workSource != null && workSource.isEmpty()) {
            workSource = null;
        }

        if (sourceUid <= 0 && workSource == null) {
            // Try and derive a UID to attribute things to based on the caller.
            if (sender != null) {
                if (!(sender instanceof PendingIntentRecord)) {
                    return;
                }

                final PendingIntentRecord rec = (PendingIntentRecord) sender;
                final int callerUid = Binder.getCallingUid();
                sourceUid = rec.uid == callerUid ? SYSTEM_UID : rec.uid;
            } else {
                // TODO(narayan): Should we throw an exception in this case ? It means that we
                // haven't been able to derive a UID to attribute things to.
                return;
            }
        }

        if (DEBUG_POWER) {
            Slog.w(TAG, "noteAlarmStart[sourceUid=" + sourceUid + ", workSource=" + workSource +
                    ", tag=" + tag + "]");
        }

        mBatteryStatsService.noteAlarmStart(tag, workSource, sourceUid);
    }

    @Override
    public void noteAlarmFinish(IIntentSender sender, WorkSource workSource, int sourceUid,
            String tag) {
        if (workSource != null && workSource.isEmpty()) {
            workSource = null;
        }

        if (sourceUid <= 0 && workSource == null) {
            // Try and derive a UID to attribute things to based on the caller.
            if (sender != null) {
                if (!(sender instanceof PendingIntentRecord)) {
                    return;
                }

                final PendingIntentRecord rec = (PendingIntentRecord) sender;
                final int callerUid = Binder.getCallingUid();
                sourceUid = rec.uid == callerUid ? SYSTEM_UID : rec.uid;
            } else {
                // TODO(narayan): Should we throw an exception in this case ? It means that we
                // haven't been able to derive a UID to attribute things to.
                return;
            }
        }

        if (DEBUG_POWER) {
            Slog.w(TAG, "noteAlarmFinish[sourceUid=" + sourceUid + ", workSource=" + workSource +
                    ", tag=" + tag + "]");
        }

        mBatteryStatsService.noteAlarmFinish(tag, workSource, sourceUid);
    }

    public boolean killPids(int[] pids, String pReason, boolean secure) {
        if (Binder.getCallingUid() != SYSTEM_UID) {
            throw new SecurityException("killPids only available to the system");
        }
        String reason = (pReason == null) ? "Unknown" : pReason;
        // XXX Note: don't acquire main activity lock here, because the window
        // manager calls in with its locks held.

        boolean killed = false;
        synchronized (mPidsSelfLocked) {
            int worstType = 0;
            for (int i=0; i<pids.length; i++) {
                ProcessRecord proc = mPidsSelfLocked.get(pids[i]);
                if (proc != null) {
                    int type = proc.setAdj;
                    if (type > worstType) {
                        worstType = type;
                    }
                }
            }

            // If the worst oom_adj is somewhere in the cached proc LRU range,
            // then constrain it so we will kill all cached procs.
            if (worstType < ProcessList.CACHED_APP_MAX_ADJ
                    && worstType > ProcessList.CACHED_APP_MIN_ADJ) {
                worstType = ProcessList.CACHED_APP_MIN_ADJ;
            }

            // If this is not a secure call, don't let it kill processes that
            // are important.
            if (!secure && worstType < ProcessList.SERVICE_ADJ) {
                worstType = ProcessList.SERVICE_ADJ;
            }

            Slog.w(TAG, "Killing processes " + reason + " at adjustment " + worstType);
            for (int i=0; i<pids.length; i++) {
                ProcessRecord proc = mPidsSelfLocked.get(pids[i]);
                if (proc == null) {
                    continue;
                }
                int adj = proc.setAdj;
                if (adj >= worstType && !proc.killedByAm) {
                    proc.kill(reason, true);
                    killed = true;
                }
            }
        }
        return killed;
    }

    @Override
    public void killUid(int appId, int userId, String reason) {
        enforceCallingPermission(Manifest.permission.KILL_UID, "killUid");
        synchronized (this) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mProcessList.killPackageProcessesLocked(null /* packageName */, appId, userId,
                        ProcessList.PERSISTENT_PROC_ADJ, false /* callerWillRestart */,
                        true /* callerWillRestart */, true /* doit */, true /* evenPersistent */,
                        false /* setRemoved */, reason != null ? reason : "kill uid");
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public boolean killProcessesBelowForeground(String reason) {
        if (Binder.getCallingUid() != SYSTEM_UID) {
            throw new SecurityException("killProcessesBelowForeground() only available to system");
        }

        return killProcessesBelowAdj(ProcessList.FOREGROUND_APP_ADJ, reason);
    }

    private boolean killProcessesBelowAdj(int belowAdj, String reason) {
        if (Binder.getCallingUid() != SYSTEM_UID) {
            throw new SecurityException("killProcessesBelowAdj() only available to system");
        }

        boolean killed = false;
        synchronized (mPidsSelfLocked) {
            final int size = mPidsSelfLocked.size();
            for (int i = 0; i < size; i++) {
                final int pid = mPidsSelfLocked.keyAt(i);
                final ProcessRecord proc = mPidsSelfLocked.valueAt(i);
                if (proc == null) continue;

                final int adj = proc.setAdj;
                if (adj > belowAdj && !proc.killedByAm) {
                    proc.kill(reason, true);
                    killed = true;
                }
            }
        }
        return killed;
    }

    @Override
    public void hang(final IBinder who, boolean allowRestart) {
        if (checkCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.SET_ACTIVITY_WATCHER);
        }

        final IBinder.DeathRecipient death = new DeathRecipient() {
            @Override
            public void binderDied() {
                synchronized (this) {
                    notifyAll();
                }
            }
        };

        try {
            who.linkToDeath(death, 0);
        } catch (RemoteException e) {
            Slog.w(TAG, "hang: given caller IBinder is already dead.");
            return;
        }

        synchronized (this) {
            Watchdog.getInstance().setAllowRestart(allowRestart);
            Slog.i(TAG, "Hanging system process at request of pid " + Binder.getCallingPid());
            synchronized (death) {
                while (who.isBinderAlive()) {
                    try {
                        death.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
            Watchdog.getInstance().setAllowRestart(true);
        }
    }

    @Override
    public void restart() {
        if (checkCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.SET_ACTIVITY_WATCHER);
        }

        Log.i(TAG, "Sending shutdown broadcast...");

        BroadcastReceiver br = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                // Now the broadcast is done, finish up the low-level shutdown.
                Log.i(TAG, "Shutting down activity manager...");
                shutdown(10000);
                Log.i(TAG, "Shutdown complete, restarting!");
                killProcess(myPid());
                System.exit(10);
            }
        };

        // First send the high-level shut down broadcast.
        Intent intent = new Intent(Intent.ACTION_SHUTDOWN);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.putExtra(Intent.EXTRA_SHUTDOWN_USERSPACE_ONLY, true);
        /* For now we are not doing a clean shutdown, because things seem to get unhappy.
        mContext.sendOrderedBroadcastAsUser(intent,
                UserHandle.ALL, null, br, mHandler, 0, null, null);
        */
        br.onReceive(mContext, intent);
    }

    private long getLowRamTimeSinceIdle(long now) {
        return mLowRamTimeSinceLastIdle + (mLowRamStartTime > 0 ? (now-mLowRamStartTime) : 0);
    }

    @Override
    public void performIdleMaintenance() {
        if (checkCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.SET_ACTIVITY_WATCHER);
        }

        synchronized (this) {
            final long now = SystemClock.uptimeMillis();
            final long timeSinceLastIdle = now - mLastIdleTime;
            final long lowRamSinceLastIdle = getLowRamTimeSinceIdle(now);
            mLastIdleTime = now;
            mLowRamTimeSinceLastIdle = 0;
            if (mLowRamStartTime != 0) {
                mLowRamStartTime = now;
            }

            StringBuilder sb = new StringBuilder(128);
            sb.append("Idle maintenance over ");
            TimeUtils.formatDuration(timeSinceLastIdle, sb);
            sb.append(" low RAM for ");
            TimeUtils.formatDuration(lowRamSinceLastIdle, sb);
            Slog.i(TAG, sb.toString());

            // If at least 1/3 of our time since the last idle period has been spent
            // with RAM low, then we want to kill processes.
            boolean doKilling = lowRamSinceLastIdle > (timeSinceLastIdle/3);
            // If the processes' memory has increased by more than 1% of the total memory,
            // or 10 MB, whichever is greater, then the processes' are eligible to be killed.
            final long totalMemoryInKb = getTotalMemory() / 1000;
            final long memoryGrowthThreshold =
                    Math.max(totalMemoryInKb / 100, MINIMUM_MEMORY_GROWTH_THRESHOLD);

            for (int i = mProcessList.mLruProcesses.size() - 1 ; i >= 0 ; i--) {
                ProcessRecord proc = mProcessList.mLruProcesses.get(i);
                if (proc.notCachedSinceIdle) {
                    if (proc.setProcState >= ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE
                            && proc.setProcState <= ActivityManager.PROCESS_STATE_SERVICE) {
                        if (doKilling && proc.initialIdlePss != 0
                                && proc.lastPss > ((proc.initialIdlePss * 3) / 2)
                                && proc.lastPss > (proc.initialIdlePss + memoryGrowthThreshold)) {
                            sb = new StringBuilder(128);
                            sb.append("Kill");
                            sb.append(proc.processName);
                            sb.append(" in idle maint: pss=");
                            sb.append(proc.lastPss);
                            sb.append(", swapPss=");
                            sb.append(proc.lastSwapPss);
                            sb.append(", initialPss=");
                            sb.append(proc.initialIdlePss);
                            sb.append(", period=");
                            TimeUtils.formatDuration(timeSinceLastIdle, sb);
                            sb.append(", lowRamPeriod=");
                            TimeUtils.formatDuration(lowRamSinceLastIdle, sb);
                            Slog.wtfQuiet(TAG, sb.toString());
                            proc.kill("idle maint (pss " + proc.lastPss
                                    + " from " + proc.initialIdlePss + ")", true);
                        }
                    }
                } else if (proc.setProcState < ActivityManager.PROCESS_STATE_HOME
                        && proc.setProcState >= ActivityManager.PROCESS_STATE_PERSISTENT) {
                    proc.notCachedSinceIdle = true;
                    proc.initialIdlePss = 0;
                    proc.nextPssTime = ProcessList.computeNextPssTime(proc.setProcState, null,
                            mTestPssMode, mAtmInternal.isSleeping(), now);
                }
            }
        }
    }

    @Override
    public void sendIdleJobTrigger() {
        if (checkCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.SET_ACTIVITY_WATCHER);
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            Intent intent = new Intent(ACTION_TRIGGER_IDLE)
                    .setPackage("android")
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            broadcastIntent(null, intent, null, null, 0, null, null, null,
                    OP_NONE, null, false, false, UserHandle.USER_ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void retrieveSettings() {
        final ContentResolver resolver = mContext.getContentResolver();
        mActivityTaskManager.retrieveSettings(resolver);

        final String debugApp = Settings.Global.getString(resolver, DEBUG_APP);
        final boolean waitForDebugger = Settings.Global.getInt(resolver, WAIT_FOR_DEBUGGER, 0) != 0;
        final boolean alwaysFinishActivities =
                Settings.Global.getInt(resolver, ALWAYS_FINISH_ACTIVITIES, 0) != 0;
        final long waitForNetworkTimeoutMs = Settings.Global.getLong(resolver,
                NETWORK_ACCESS_TIMEOUT_MS, NETWORK_ACCESS_TIMEOUT_DEFAULT_MS);
        mHiddenApiBlacklist.registerObserver();

        synchronized (this) {
            mDebugApp = mOrigDebugApp = debugApp;
            mWaitForDebugger = mOrigWaitForDebugger = waitForDebugger;
            mAlwaysFinishActivities = alwaysFinishActivities;
            // Load resources only after the current configuration has been set.
            final Resources res = mContext.getResources();
            mAppErrors.loadAppsNotReportingCrashesFromConfigLocked(res.getString(
                    com.android.internal.R.string.config_appsNotReportingCrashes));
            mUserController.mUserSwitchUiEnabled = !res.getBoolean(
                    com.android.internal.R.bool.config_customUserSwitchUi);
            mUserController.mMaxRunningUsers = res.getInteger(
                    com.android.internal.R.integer.config_multiuserMaxRunningUsers);

            mWaitForNetworkTimeoutMs = waitForNetworkTimeoutMs;
        }
    }

    public void systemReady(final Runnable goingCallback, TimingsTraceLog traceLog) {
        traceLog.traceBegin("PhaseActivityManagerReady");
        synchronized(this) {
            if (mSystemReady) {
                // If we're done calling all the receivers, run the next "boot phase" passed in
                // by the SystemServer
                if (goingCallback != null) {
                    goingCallback.run();
                }
                return;
            }

            mLocalDeviceIdleController
                    = LocalServices.getService(DeviceIdleController.LocalService.class);
            mActivityTaskManager.onSystemReady();
            // Make sure we have the current profile info, since it is needed for security checks.
            mUserController.onSystemReady();
            mAppOpsService.systemReady();
            mSystemReady = true;
        }

        try {
            sTheRealBuildSerial = IDeviceIdentifiersPolicyService.Stub.asInterface(
                    ServiceManager.getService(Context.DEVICE_IDENTIFIERS_SERVICE))
                    .getSerial();
        } catch (RemoteException e) {}

        ArrayList<ProcessRecord> procsToKill = null;
        synchronized(mPidsSelfLocked) {
            for (int i=mPidsSelfLocked.size()-1; i>=0; i--) {
                ProcessRecord proc = mPidsSelfLocked.valueAt(i);
                if (!isAllowedWhileBooting(proc.info)){
                    if (procsToKill == null) {
                        procsToKill = new ArrayList<ProcessRecord>();
                    }
                    procsToKill.add(proc);
                }
            }
        }

        synchronized(this) {
            if (procsToKill != null) {
                for (int i=procsToKill.size()-1; i>=0; i--) {
                    ProcessRecord proc = procsToKill.get(i);
                    Slog.i(TAG, "Removing system update proc: " + proc);
                    mProcessList.removeProcessLocked(proc, true, false, "system update done");
                }
            }

            // Now that we have cleaned up any update processes, we
            // are ready to start launching real processes and know that
            // we won't trample on them any more.
            mProcessesReady = true;
        }

        Slog.i(TAG, "System now ready");
        EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_AMS_READY, SystemClock.uptimeMillis());

        mAtmInternal.updateTopComponentForFactoryTest();

        retrieveSettings();
        final int currentUserId = mUserController.getCurrentUserId();
        mUgmInternal.onSystemReady();

        final PowerManagerInternal pmi = LocalServices.getService(PowerManagerInternal.class);
        if (pmi != null) {
            pmi.registerLowPowerModeObserver(ServiceType.FORCE_BACKGROUND_CHECK,
                    state -> updateForceBackgroundCheck(state.batterySaverEnabled));
            updateForceBackgroundCheck(
                    pmi.getLowPowerState(ServiceType.FORCE_BACKGROUND_CHECK).batterySaverEnabled);
        } else {
            Slog.wtf(TAG, "PowerManagerInternal not found.");
        }

        if (goingCallback != null) goingCallback.run();
        traceLog.traceBegin("ActivityManagerStartApps");
        mBatteryStatsService.noteEvent(BatteryStats.HistoryItem.EVENT_USER_RUNNING_START,
                Integer.toString(currentUserId), currentUserId);
        mBatteryStatsService.noteEvent(BatteryStats.HistoryItem.EVENT_USER_FOREGROUND_START,
                Integer.toString(currentUserId), currentUserId);
        mSystemServiceManager.startUser(currentUserId);

        synchronized (this) {
            // Only start up encryption-aware persistent apps; once user is
            // unlocked we'll come back around and start unaware apps
            startPersistentApps(PackageManager.MATCH_DIRECT_BOOT_AWARE);

            // Start up initial activity.
            mBooting = true;
            // Enable home activity for system user, so that the system can always boot. We don't
            // do this when the system user is not setup since the setup wizard should be the one
            // to handle home activity in this case.
            if (UserManager.isSplitSystemUser() &&
                    Settings.Secure.getInt(mContext.getContentResolver(),
                         Settings.Secure.USER_SETUP_COMPLETE, 0) != 0) {
                ComponentName cName = new ComponentName(mContext, SystemUserHomeActivity.class);
                try {
                    AppGlobals.getPackageManager().setComponentEnabledSetting(cName,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0,
                            UserHandle.USER_SYSTEM);
                } catch (RemoteException e) {
                    throw e.rethrowAsRuntimeException();
                }
            }
            mAtmInternal.startHomeOnAllDisplays(currentUserId, "systemReady");

            mAtmInternal.showSystemReadyErrorDialogsIfNeeded();

            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            long ident = Binder.clearCallingIdentity();
            try {
                Intent intent = new Intent(Intent.ACTION_USER_STARTED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                        | Intent.FLAG_RECEIVER_FOREGROUND);
                intent.putExtra(Intent.EXTRA_USER_HANDLE, currentUserId);
                broadcastIntentLocked(null, null, intent,
                        null, null, 0, null, null, null, OP_NONE,
                        null, false, false, MY_PID, SYSTEM_UID, callingUid, callingPid,
                        currentUserId);
                intent = new Intent(Intent.ACTION_USER_STARTING);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                intent.putExtra(Intent.EXTRA_USER_HANDLE, currentUserId);
                broadcastIntentLocked(null, null, intent,
                        null, new IIntentReceiver.Stub() {
                            @Override
                            public void performReceive(Intent intent, int resultCode, String data,
                                    Bundle extras, boolean ordered, boolean sticky, int sendingUser)
                                    throws RemoteException {
                            }
                        }, 0, null, null,
                        new String[] {INTERACT_ACROSS_USERS}, OP_NONE,
                        null, true, false, MY_PID, SYSTEM_UID, callingUid, callingPid,
                        UserHandle.USER_ALL);
            } catch (Throwable t) {
                Slog.wtf(TAG, "Failed sending first user broadcasts", t);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            mAtmInternal.resumeTopActivities(false /* scheduleIdle */);
            mUserController.sendUserSwitchBroadcasts(-1, currentUserId);

            BinderInternal.nSetBinderProxyCountWatermarks(BINDER_PROXY_HIGH_WATERMARK,
                    BINDER_PROXY_LOW_WATERMARK);
            BinderInternal.nSetBinderProxyCountEnabled(true);
            BinderInternal.setBinderProxyCountCallback(
                    new BinderInternal.BinderProxyLimitListener() {
                        @Override
                        public void onLimitReached(int uid) {
                            Slog.wtf(TAG, "Uid " + uid + " sent too many Binders to uid "
                                    + Process.myUid());
                            BinderProxy.dumpProxyDebugInfo();
                            if (uid == Process.SYSTEM_UID) {
                                Slog.i(TAG, "Skipping kill (uid is SYSTEM)");
                            } else {
                                killUid(UserHandle.getAppId(uid), UserHandle.getUserId(uid),
                                        "Too many Binders sent to SYSTEM");
                            }
                        }
                    }, mHandler);

            traceLog.traceEnd(); // ActivityManagerStartApps
            traceLog.traceEnd(); // PhaseActivityManagerReady
        }
    }

    private void startBroadcastObservers() {
        for (BroadcastQueue queue : mBroadcastQueues) {
            queue.start(mContext.getContentResolver());
        }
    }

    private void updateForceBackgroundCheck(boolean enabled) {
        synchronized (this) {
            if (mForceBackgroundCheck != enabled) {
                mForceBackgroundCheck = enabled;

                if (DEBUG_BACKGROUND_CHECK) {
                    Slog.i(TAG, "Force background check " + (enabled ? "enabled" : "disabled"));
                }

                if (mForceBackgroundCheck) {
                    // Stop background services for idle UIDs.
                    mProcessList.doStopUidForIdleUidsLocked();
                }
            }
        }
    }

    void killAppAtUsersRequest(ProcessRecord app, Dialog fromDialog) {
        synchronized (this) {
            mAppErrors.killAppAtUserRequestLocked(app, fromDialog);
        }
    }

    void skipCurrentReceiverLocked(ProcessRecord app) {
        for (BroadcastQueue queue : mBroadcastQueues) {
            queue.skipCurrentReceiverLocked(app);
        }
    }

    /**
     * Used by {@link com.android.internal.os.RuntimeInit} to report when an application crashes.
     * The application process will exit immediately after this call returns.
     * @param app object of the crashing app, null for the system server
     * @param crashInfo describing the exception
     */
    public void handleApplicationCrash(IBinder app,
            ApplicationErrorReport.ParcelableCrashInfo crashInfo) {
        ProcessRecord r = findAppProcess(app, "Crash");
        final String processName = app == null ? "system_server"
                : (r == null ? "unknown" : r.processName);

        handleApplicationCrashInner("crash", r, processName, crashInfo);
    }

    /* Native crash reporting uses this inner version because it needs to be somewhat
     * decoupled from the AM-managed cleanup lifecycle
     */
    void handleApplicationCrashInner(String eventType, ProcessRecord r, String processName,
            ApplicationErrorReport.CrashInfo crashInfo) {
        EventLog.writeEvent(EventLogTags.AM_CRASH, Binder.getCallingPid(),
                UserHandle.getUserId(Binder.getCallingUid()), processName,
                r == null ? -1 : r.info.flags,
                crashInfo.exceptionClassName,
                crashInfo.exceptionMessage,
                crashInfo.throwFileName,
                crashInfo.throwLineNumber);

        StatsLog.write(StatsLog.APP_CRASH_OCCURRED,
                Binder.getCallingUid(),
                eventType,
                processName,
                Binder.getCallingPid(),
                (r != null && r.info != null) ? r.info.packageName : "",
                (r != null && r.info != null) ? (r.info.isInstantApp()
                        ? StatsLog.APP_CRASH_OCCURRED__IS_INSTANT_APP__TRUE
                        : StatsLog.APP_CRASH_OCCURRED__IS_INSTANT_APP__FALSE)
                        : StatsLog.APP_CRASH_OCCURRED__IS_INSTANT_APP__UNAVAILABLE,
                r != null ? (r.isInterestingToUserLocked()
                        ? StatsLog.APP_CRASH_OCCURRED__FOREGROUND_STATE__FOREGROUND
                        : StatsLog.APP_CRASH_OCCURRED__FOREGROUND_STATE__BACKGROUND)
                        : StatsLog.APP_CRASH_OCCURRED__FOREGROUND_STATE__UNKNOWN,
                (r != null) ? r.getProcessClassEnum() : 0
        );

        final int relaunchReason = r == null ? RELAUNCH_REASON_NONE
                        : r.getWindowProcessController().computeRelaunchReason();
        final String relaunchReasonString = relaunchReasonToString(relaunchReason);
        if (crashInfo.crashTag == null) {
            crashInfo.crashTag = relaunchReasonString;
        } else {
            crashInfo.crashTag = crashInfo.crashTag + " " + relaunchReasonString;
        }

        addErrorToDropBox(
                eventType, r, processName, null, null, null, null, null, null, crashInfo);

        mAppErrors.crashApplication(r, crashInfo);
    }

    public void handleApplicationStrictModeViolation(
            IBinder app,
            int penaltyMask,
            StrictMode.ViolationInfo info) {
        // We're okay if the ProcessRecord is missing; it probably means that
        // we're reporting a violation from the system process itself.
        final ProcessRecord r = findAppProcess(app, "StrictMode");

        if ((penaltyMask & StrictMode.PENALTY_DROPBOX) != 0) {
            Integer stackFingerprint = info.hashCode();
            boolean logIt = true;
            synchronized (mAlreadyLoggedViolatedStacks) {
                if (mAlreadyLoggedViolatedStacks.contains(stackFingerprint)) {
                    logIt = false;
                    // TODO: sub-sample into EventLog for these, with
                    // the info.durationMillis?  Then we'd get
                    // the relative pain numbers, without logging all
                    // the stack traces repeatedly.  We'd want to do
                    // likewise in the client code, which also does
                    // dup suppression, before the Binder call.
                } else {
                    if (mAlreadyLoggedViolatedStacks.size() >= MAX_DUP_SUPPRESSED_STACKS) {
                        mAlreadyLoggedViolatedStacks.clear();
                    }
                    mAlreadyLoggedViolatedStacks.add(stackFingerprint);
                }
            }
            if (logIt) {
                logStrictModeViolationToDropBox(r, info);
            }
        }

        if ((penaltyMask & StrictMode.PENALTY_DIALOG) != 0) {
            AppErrorResult result = new AppErrorResult();
            synchronized (this) {
                final long origId = Binder.clearCallingIdentity();

                Message msg = Message.obtain();
                msg.what = SHOW_STRICT_MODE_VIOLATION_UI_MSG;
                HashMap<String, Object> data = new HashMap<String, Object>();
                data.put("result", result);
                data.put("app", r);
                data.put("info", info);
                msg.obj = data;
                mUiHandler.sendMessage(msg);

                Binder.restoreCallingIdentity(origId);
            }
            int res = result.get();
            Slog.w(TAG, "handleApplicationStrictModeViolation; res=" + res);
        }
    }

    // Depending on the policy in effect, there could be a bunch of
    // these in quick succession so we try to batch these together to
    // minimize disk writes, number of dropbox entries, and maximize
    // compression, by having more fewer, larger records.
    private void logStrictModeViolationToDropBox(
            ProcessRecord process,
            StrictMode.ViolationInfo info) {
        if (info == null) {
            return;
        }
        final boolean isSystemApp = process == null ||
                (process.info.flags & (ApplicationInfo.FLAG_SYSTEM |
                                       ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        final String processName = process == null ? "unknown" : process.processName;
        final DropBoxManager dbox = (DropBoxManager)
                mContext.getSystemService(Context.DROPBOX_SERVICE);

        // Exit early if the dropbox isn't configured to accept this report type.
        final String dropboxTag = processClass(process) + "_strictmode";
        if (dbox == null || !dbox.isTagEnabled(dropboxTag)) return;

        final StringBuilder sb = new StringBuilder(1024);
        synchronized (sb) {
            appendDropBoxProcessHeaders(process, processName, sb);
            sb.append("Build: ").append(Build.FINGERPRINT).append("\n");
            sb.append("System-App: ").append(isSystemApp).append("\n");
            sb.append("Uptime-Millis: ").append(info.violationUptimeMillis).append("\n");
            if (info.violationNumThisLoop != 0) {
                sb.append("Loop-Violation-Number: ").append(info.violationNumThisLoop).append("\n");
            }
            if (info.numAnimationsRunning != 0) {
                sb.append("Animations-Running: ").append(info.numAnimationsRunning).append("\n");
            }
            if (info.broadcastIntentAction != null) {
                sb.append("Broadcast-Intent-Action: ").append(info.broadcastIntentAction).append("\n");
            }
            if (info.durationMillis != -1) {
                sb.append("Duration-Millis: ").append(info.durationMillis).append("\n");
            }
            if (info.numInstances != -1) {
                sb.append("Instance-Count: ").append(info.numInstances).append("\n");
            }
            if (info.tags != null) {
                for (String tag : info.tags) {
                    sb.append("Span-Tag: ").append(tag).append("\n");
                }
            }
            sb.append("\n");
            sb.append(info.getStackTrace());
            sb.append("\n");
            if (info.getViolationDetails() != null) {
                sb.append(info.getViolationDetails());
                sb.append("\n");
            }
        }

        final String res = sb.toString();
        IoThread.getHandler().post(() -> {
            dbox.addText(dropboxTag, res);
        });
    }

    /**
     * Used by {@link Log} via {@link com.android.internal.os.RuntimeInit} to report serious errors.
     * @param app object of the crashing app, null for the system server
     * @param tag reported by the caller
     * @param system whether this wtf is coming from the system
     * @param crashInfo describing the context of the error
     * @return true if the process should exit immediately (WTF is fatal)
     */
    public boolean handleApplicationWtf(final IBinder app, final String tag, boolean system,
            final ApplicationErrorReport.ParcelableCrashInfo crashInfo) {
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();

        if (system) {
            // If this is coming from the system, we could very well have low-level
            // system locks held, so we want to do this all asynchronously.  And we
            // never want this to become fatal, so there is that too.
            mHandler.post(new Runnable() {
                @Override public void run() {
                    handleApplicationWtfInner(callingUid, callingPid, app, tag, crashInfo);
                }
            });
            return false;
        }

        final ProcessRecord r = handleApplicationWtfInner(callingUid, callingPid, app, tag,
                crashInfo);

        final boolean isFatal = Build.IS_ENG || Settings.Global
                .getInt(mContext.getContentResolver(), Settings.Global.WTF_IS_FATAL, 0) != 0;
        final boolean isSystem = (r == null) || r.isPersistent();

        if (isFatal && !isSystem) {
            mAppErrors.crashApplication(r, crashInfo);
            return true;
        } else {
            return false;
        }
    }

    ProcessRecord handleApplicationWtfInner(int callingUid, int callingPid, IBinder app, String tag,
            final ApplicationErrorReport.CrashInfo crashInfo) {
        final ProcessRecord r = findAppProcess(app, "WTF");
        final String processName = app == null ? "system_server"
                : (r == null ? "unknown" : r.processName);

        EventLog.writeEvent(EventLogTags.AM_WTF, UserHandle.getUserId(callingUid), callingPid,
                processName, r == null ? -1 : r.info.flags, tag, crashInfo.exceptionMessage);

        StatsLog.write(StatsLog.WTF_OCCURRED, callingUid, tag, processName,
                callingPid, (r != null) ? r.getProcessClassEnum() : 0);

        addErrorToDropBox("wtf", r, processName, null, null, null, tag, null, null, crashInfo);

        return r;
    }

    /**
     * @param app object of some object (as stored in {@link com.android.internal.os.RuntimeInit})
     * @return the corresponding {@link ProcessRecord} object, or null if none could be found
     */
    private ProcessRecord findAppProcess(IBinder app, String reason) {
        if (app == null) {
            return null;
        }

        synchronized (this) {
            return mProcessList.findAppProcessLocked(app, reason);
        }
    }

    /**
     * Utility function for addErrorToDropBox and handleStrictModeViolation's logging
     * to append various headers to the dropbox log text.
     */
    private void appendDropBoxProcessHeaders(ProcessRecord process, String processName,
            StringBuilder sb) {
        // Watchdog thread ends up invoking this function (with
        // a null ProcessRecord) to add the stack file to dropbox.
        // Do not acquire a lock on this (am) in such cases, as it
        // could cause a potential deadlock, if and when watchdog
        // is invoked due to unavailability of lock on am and it
        // would prevent watchdog from killing system_server.
        if (process == null) {
            sb.append("Process: ").append(processName).append("\n");
            return;
        }
        // Note: ProcessRecord 'process' is guarded by the service
        // instance.  (notably process.pkgList, which could otherwise change
        // concurrently during execution of this method)
        synchronized (this) {
            sb.append("Process: ").append(processName).append("\n");
            sb.append("PID: ").append(process.pid).append("\n");
            sb.append("UID: ").append(process.uid).append("\n");
            int flags = process.info.flags;
            IPackageManager pm = AppGlobals.getPackageManager();
            sb.append("Flags: 0x").append(Integer.toHexString(flags)).append("\n");
            for (int ip=0; ip<process.pkgList.size(); ip++) {
                String pkg = process.pkgList.keyAt(ip);
                sb.append("Package: ").append(pkg);
                try {
                    PackageInfo pi = pm.getPackageInfo(pkg, 0, UserHandle.getCallingUserId());
                    if (pi != null) {
                        sb.append(" v").append(pi.getLongVersionCode());
                        if (pi.versionName != null) {
                            sb.append(" (").append(pi.versionName).append(")");
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error getting package info: " + pkg, e);
                }
                sb.append("\n");
            }
            if (process.info.isInstantApp()) {
                sb.append("Instant-App: true\n");
            }
        }
    }

    private static String processClass(ProcessRecord process) {
        if (process == null || process.pid == MY_PID) {
            return "system_server";
        } else if ((process.info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            return "system_app";
        } else {
            return "data_app";
        }
    }

    private volatile long mWtfClusterStart;
    private volatile int mWtfClusterCount;

    /**
     * Write a description of an error (crash, WTF, ANR) to the drop box.
     * @param eventType to include in the drop box tag ("crash", "wtf", etc.)
     * @param process which caused the error, null means the system server
     * @param activityShortComponentName which triggered the error, null if unknown
     * @param parentShortComponentName activity related to the error, null if unknown
     * @param parentProcess parent process
     * @param subject line related to the error, null if absent
     * @param report in long form describing the error, null if absent
     * @param dataFile text file to include in the report, null if none
     * @param crashInfo giving an application stack trace, null if absent
     */
    public void addErrorToDropBox(String eventType,
            ProcessRecord process, String processName, String activityShortComponentName,
            String parentShortComponentName, ProcessRecord parentProcess,
            String subject, final String report, final File dataFile,
            final ApplicationErrorReport.CrashInfo crashInfo) {
        // NOTE -- this must never acquire the ActivityManagerService lock,
        // otherwise the watchdog may be prevented from resetting the system.

        // Bail early if not published yet
        if (ServiceManager.getService(Context.DROPBOX_SERVICE) == null) return;
        final DropBoxManager dbox = mContext.getSystemService(DropBoxManager.class);

        // Exit early if the dropbox isn't configured to accept this report type.
        final String dropboxTag = processClass(process) + "_" + eventType;
        if (dbox == null || !dbox.isTagEnabled(dropboxTag)) return;

        // Rate-limit how often we're willing to do the heavy lifting below to
        // collect and record logs; currently 5 logs per 10 second period.
        final long now = SystemClock.elapsedRealtime();
        if (now - mWtfClusterStart > 10 * DateUtils.SECOND_IN_MILLIS) {
            mWtfClusterStart = now;
            mWtfClusterCount = 1;
        } else {
            if (mWtfClusterCount++ >= 5) return;
        }

        final StringBuilder sb = new StringBuilder(1024);
        appendDropBoxProcessHeaders(process, processName, sb);
        if (process != null) {
            sb.append("Foreground: ")
                    .append(process.isInterestingToUserLocked() ? "Yes" : "No")
                    .append("\n");
        }
        if (activityShortComponentName != null) {
            sb.append("Activity: ").append(activityShortComponentName).append("\n");
        }
        if (parentShortComponentName != null) {
            if (parentProcess != null && parentProcess.pid != process.pid) {
                sb.append("Parent-Process: ").append(parentProcess.processName).append("\n");
            }
            if (!parentShortComponentName.equals(activityShortComponentName)) {
                sb.append("Parent-Activity: ").append(parentShortComponentName).append("\n");
            }
        }
        if (subject != null) {
            sb.append("Subject: ").append(subject).append("\n");
        }
        sb.append("Build: ").append(Build.FINGERPRINT).append("\n");
        if (Debug.isDebuggerConnected()) {
            sb.append("Debugger: Connected\n");
        }
        if (crashInfo != null && crashInfo.crashTag != null && !crashInfo.crashTag.isEmpty()) {
            sb.append("Crash-Tag: ").append(crashInfo.crashTag).append("\n");
        }
        sb.append("\n");

        // Do the rest in a worker thread to avoid blocking the caller on I/O
        // (After this point, we shouldn't access AMS internal data structures.)
        Thread worker = new Thread("Error dump: " + dropboxTag) {
            @Override
            public void run() {
                if (report != null) {
                    sb.append(report);
                }

                String setting = Settings.Global.ERROR_LOGCAT_PREFIX + dropboxTag;
                int lines = Settings.Global.getInt(mContext.getContentResolver(), setting, 0);
                int maxDataFileSize = DROPBOX_MAX_SIZE - sb.length()
                        - lines * RESERVED_BYTES_PER_LOGCAT_LINE;

                if (dataFile != null && maxDataFileSize > 0) {
                    try {
                        sb.append(FileUtils.readTextFile(dataFile, maxDataFileSize,
                                    "\n\n[[TRUNCATED]]"));
                    } catch (IOException e) {
                        Slog.e(TAG, "Error reading " + dataFile, e);
                    }
                }
                if (crashInfo != null && crashInfo.stackTrace != null) {
                    sb.append(crashInfo.stackTrace);
                }

                if (lines > 0) {
                    sb.append("\n");

                    // Merge several logcat streams, and take the last N lines
                    InputStreamReader input = null;
                    try {
                        java.lang.Process logcat = new ProcessBuilder(
                                "/system/bin/timeout", "-k", "15s", "10s",
                                "/system/bin/logcat", "-v", "threadtime", "-b", "events", "-b", "system",
                                "-b", "main", "-b", "crash", "-t", String.valueOf(lines))
                                        .redirectErrorStream(true).start();

                        try { logcat.getOutputStream().close(); } catch (IOException e) {}
                        try { logcat.getErrorStream().close(); } catch (IOException e) {}
                        input = new InputStreamReader(logcat.getInputStream());

                        int num;
                        char[] buf = new char[8192];
                        while ((num = input.read(buf)) > 0) sb.append(buf, 0, num);
                    } catch (IOException e) {
                        Slog.e(TAG, "Error running logcat", e);
                    } finally {
                        if (input != null) try { input.close(); } catch (IOException e) {}
                    }
                }

                dbox.addText(dropboxTag, sb.toString());
            }
        };

        if (process == null) {
            // If process is null, we are being called from some internal code
            // and may be about to die -- run this synchronously.
            final int oldMask = StrictMode.allowThreadDiskWritesMask();
            try {
                worker.run();
            } finally {
                StrictMode.setThreadPolicyMask(oldMask);
            }
        } else {
            worker.start();
        }
    }

    @Override
    public List<ActivityManager.ProcessErrorStateInfo> getProcessesInErrorState() {
        enforceNotIsolatedCaller("getProcessesInErrorState");
        // assume our apps are happy - lazy create the list
        List<ActivityManager.ProcessErrorStateInfo> errList = null;

        final boolean allUsers = ActivityManager.checkUidPermission(INTERACT_ACROSS_USERS_FULL,
                Binder.getCallingUid()) == PackageManager.PERMISSION_GRANTED;
        int userId = UserHandle.getUserId(Binder.getCallingUid());

        synchronized (this) {

            // iterate across all processes
            for (int i=mProcessList.mLruProcesses.size()-1; i>=0; i--) {
                ProcessRecord app = mProcessList.mLruProcesses.get(i);
                if (!allUsers && app.userId != userId) {
                    continue;
                }
                final boolean crashing = app.isCrashing();
                final boolean notResponding = app.isNotResponding();
                if ((app.thread != null) && (crashing || notResponding)) {
                    // This one's in trouble, so we'll generate a report for it
                    // crashes are higher priority (in case there's a crash *and* an anr)
                    ActivityManager.ProcessErrorStateInfo report = null;
                    if (crashing) {
                        report = app.crashingReport;
                    } else if (notResponding) {
                        report = app.notRespondingReport;
                    }

                    if (report != null) {
                        if (errList == null) {
                            errList = new ArrayList<>(1);
                        }
                        errList.add(report);
                    } else {
                        Slog.w(TAG, "Missing app error report, app = " + app.processName +
                                " crashing = " + crashing +
                                " notResponding = " + notResponding);
                    }
                }
            }
        }

        return errList;
    }

    @Override
    public List<ActivityManager.RunningAppProcessInfo> getRunningAppProcesses() {
        enforceNotIsolatedCaller("getRunningAppProcesses");

        final int callingUid = Binder.getCallingUid();
        final int clientTargetSdk = mPackageManagerInt.getUidTargetSdkVersion(callingUid);

        final boolean allUsers = ActivityManager.checkUidPermission(INTERACT_ACROSS_USERS_FULL,
                callingUid) == PackageManager.PERMISSION_GRANTED;
        final int userId = UserHandle.getUserId(callingUid);
        final boolean allUids = mAtmInternal.isGetTasksAllowed(
                "getRunningAppProcesses", Binder.getCallingPid(), callingUid);

        synchronized (this) {
            // Iterate across all processes
            return mProcessList.getRunningAppProcessesLocked(allUsers, userId, allUids,
                    callingUid, clientTargetSdk);
        }
    }

    @Override
    public List<ApplicationInfo> getRunningExternalApplications() {
        enforceNotIsolatedCaller("getRunningExternalApplications");
        List<ActivityManager.RunningAppProcessInfo> runningApps = getRunningAppProcesses();
        List<ApplicationInfo> retList = new ArrayList<ApplicationInfo>();
        if (runningApps != null && runningApps.size() > 0) {
            Set<String> extList = new HashSet<String>();
            for (ActivityManager.RunningAppProcessInfo app : runningApps) {
                if (app.pkgList != null) {
                    for (String pkg : app.pkgList) {
                        extList.add(pkg);
                    }
                }
            }
            IPackageManager pm = AppGlobals.getPackageManager();
            for (String pkg : extList) {
                try {
                    ApplicationInfo info = pm.getApplicationInfo(pkg, 0, UserHandle.getCallingUserId());
                    if ((info.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0) {
                        retList.add(info);
                    }
                } catch (RemoteException e) {
                }
            }
        }
        return retList;
    }

    @Override
    public void getMyMemoryState(ActivityManager.RunningAppProcessInfo outState) {
        if (outState == null) {
            throw new IllegalArgumentException("outState is null");
        }
        enforceNotIsolatedCaller("getMyMemoryState");

        final int callingUid = Binder.getCallingUid();
        final int clientTargetSdk = mPackageManagerInt.getUidTargetSdkVersion(callingUid);

        synchronized (this) {
            ProcessRecord proc;
            synchronized (mPidsSelfLocked) {
                proc = mPidsSelfLocked.get(Binder.getCallingPid());
            }
            if (proc != null) {
                mProcessList.fillInProcMemInfoLocked(proc, outState, clientTargetSdk);
            }
        }
    }

    @Override
    public int getMemoryTrimLevel() {
        enforceNotIsolatedCaller("getMyMemoryState");
        synchronized (this) {
            return mLastMemoryLevel;
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) {
        (new ActivityManagerShellCommand(this, false)).exec(
                this, in, out, err, args, callback, resultReceiver);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        PriorityDump.dump(mPriorityDumper, fd, pw, args);
    }

    private void dumpEverything(FileDescriptor fd, PrintWriter pw, String[] args, int opti,
            boolean dumpAll, String dumpPackage, boolean dumpClient, boolean dumpNormalPriority,
            int dumpAppId) {

        ActiveServices.ServiceDumper sdumper;

        synchronized(this) {
            mConstants.dump(pw);
            mOomAdjuster.dumpAppCompactorSettings(pw);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");

            }
            dumpAllowedAssociationsLocked(fd, pw, args, opti, dumpAll, dumpPackage);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");

            }
            mPendingIntentController.dumpPendingIntents(pw, dumpAll, dumpPackage);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            dumpBroadcastsLocked(fd, pw, args, opti, dumpAll, dumpPackage);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            if (dumpAll || dumpPackage != null) {
                dumpBroadcastStatsLocked(fd, pw, args, opti, dumpAll, dumpPackage);
                pw.println();
                if (dumpAll) {
                    pw.println("-------------------------------------------------------------------------------");
                }
            }
            dumpProvidersLocked(fd, pw, args, opti, dumpAll, dumpPackage);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            dumpPermissionsLocked(fd, pw, args, opti, dumpAll, dumpPackage);
            pw.println();
            sdumper = mServices.newServiceDumperLocked(fd, pw, args, opti, dumpAll, dumpPackage);
            if (!dumpClient) {
                if (dumpAll) {
                    pw.println("-------------------------------------------------------------------------------");
                }
                sdumper.dumpLocked();
            }
        }
        // We drop the lock here because we can't call dumpWithClient() with the lock held;
        // if the caller wants a consistent state for the !dumpClient case, it can call this
        // method with the lock held.
        if (dumpClient) {
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            sdumper.dumpWithClient();
        }
        if (dumpPackage == null) {
            // Intentionally dropping the lock for this, because dumpBinderProxies() will make many
            // outgoing binder calls to retrieve interface descriptors; while that is system code,
            // there is nothing preventing an app from overriding this implementation by talking to
            // the binder driver directly, and hang up system_server in the process. So, dump
            // without locks held, and even then only when there is an unreasonably large number of
            // proxies in the first place.
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            dumpBinderProxies(pw, BINDER_PROXY_HIGH_WATERMARK /* minToDump */);
        }
        synchronized(this) {
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            mAtmInternal.dump(
                    DUMP_RECENTS_CMD, fd, pw, args, opti, dumpAll, dumpClient, dumpPackage);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            mAtmInternal.dump(
                    DUMP_LASTANR_CMD, fd, pw, args, opti, dumpAll, dumpClient, dumpPackage);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            mAtmInternal.dump(
                    DUMP_STARTER_CMD, fd, pw, args, opti, dumpAll, dumpClient, dumpPackage);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            mAtmInternal.dump(
                    DUMP_CONTAINERS_CMD, fd, pw, args, opti, dumpAll, dumpClient, dumpPackage);
            // Activities section is dumped as part of the Critical priority dump. Exclude the
            // section if priority is Normal.
            if (!dumpNormalPriority) {
                pw.println();
                if (dumpAll) {
                    pw.println("-------------------------------------------------------------------------------");
                }
                mAtmInternal.dump(
                        DUMP_ACTIVITIES_CMD, fd, pw, args, opti, dumpAll, dumpClient, dumpPackage);
            }
            if (mAssociations.size() > 0) {
                pw.println();
                if (dumpAll) {
                    pw.println("-------------------------------------------------------------------------------");
                }
                dumpAssociationsLocked(fd, pw, args, opti, dumpAll, dumpClient, dumpPackage);
            }
            if (dumpPackage == null) {
                pw.println();
                if (dumpAll) {
                    pw.println("-------------------------------------------------------------------------------");
                }
                mOomAdjProfiler.dump(pw);
                pw.println();
                if (dumpAll) {
                    pw.println("-------------------------------------------------------------------------------");
                }
                dumpLmkLocked(pw);
            }
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            dumpLruLocked(pw, dumpPackage);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            dumpProcessesLocked(fd, pw, args, opti, dumpAll, dumpPackage, dumpAppId);
        }
    }

    /**
     * Wrapper function to print out debug data filtered by specified arguments.
    */
    private void doDump(FileDescriptor fd, PrintWriter pw, String[] args, boolean useProto) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw)) return;

        boolean dumpAll = false;
        boolean dumpClient = false;
        boolean dumpCheckin = false;
        boolean dumpCheckinFormat = false;
        boolean dumpNormalPriority = false;
        boolean dumpVisibleStacksOnly = false;
        boolean dumpFocusedStackOnly = false;
        String dumpPackage = null;

        int opti = 0;
        while (opti < args.length) {
            String opt = args[opti];
            if (opt == null || opt.length() <= 0 || opt.charAt(0) != '-') {
                break;
            }
            opti++;
            if ("-a".equals(opt)) {
                dumpAll = true;
            } else if ("-c".equals(opt)) {
                dumpClient = true;
            } else if ("-v".equals(opt)) {
                dumpVisibleStacksOnly = true;
            } else if ("-f".equals(opt)) {
                dumpFocusedStackOnly = true;
            } else if ("-p".equals(opt)) {
                if (opti < args.length) {
                    dumpPackage = args[opti];
                    opti++;
                } else {
                    pw.println("Error: -p option requires package argument");
                    return;
                }
                dumpClient = true;
            } else if ("--checkin".equals(opt)) {
                dumpCheckin = dumpCheckinFormat = true;
            } else if ("-C".equals(opt)) {
                dumpCheckinFormat = true;
            } else if ("--normal-priority".equals(opt)) {
                dumpNormalPriority = true;
            } else if ("-h".equals(opt)) {
                ActivityManagerShellCommand.dumpHelp(pw, true);
                return;
            } else {
                pw.println("Unknown argument: " + opt + "; use -h for help");
            }
        }

        long origId = Binder.clearCallingIdentity();

        if (useProto) {
            final ProtoOutputStream proto = new ProtoOutputStream(fd);
            String cmd = opti < args.length ? args[opti] : "";
            opti++;

            if ("activities".equals(cmd) || "a".equals(cmd)) {
                // output proto is ActivityManagerServiceDumpActivitiesProto
                mAtmInternal.writeActivitiesToProto(proto);
            } else if ("broadcasts".equals(cmd) || "b".equals(cmd)) {
                // output proto is ActivityManagerServiceDumpBroadcastsProto
                synchronized (this) {
                    writeBroadcastsToProtoLocked(proto);
                }
            } else if ("provider".equals(cmd)) {
                String[] newArgs;
                String name;
                if (opti >= args.length) {
                    name = null;
                    newArgs = EMPTY_STRING_ARRAY;
                } else {
                    name = args[opti];
                    opti++;
                    newArgs = new String[args.length - opti];
                    if (args.length > 2) System.arraycopy(args, opti, newArgs, 0,
                            args.length - opti);
                }
                if (!dumpProviderProto(fd, pw, name, newArgs)) {
                    pw.println("No providers match: " + name);
                    pw.println("Use -h for help.");
                }
            } else if ("service".equals(cmd)) {
                // output proto is ActivityManagerServiceDumpServicesProto
                mServices.writeToProto(proto, ActivityManagerServiceDumpServicesProto.ACTIVE_SERVICES);
            } else if ("processes".equals(cmd) || "p".equals(cmd)) {
                if (opti < args.length) {
                    dumpPackage = args[opti];
                    opti++;
                }
                // output proto is ProcessProto
                synchronized (this) {
                    writeProcessesToProtoLocked(proto, dumpPackage);
                }
            } else {
                // default option, dump everything, output is ActivityManagerServiceProto
                synchronized (this) {
                    long activityToken = proto.start(ActivityManagerServiceProto.ACTIVITIES);
                    mAtmInternal.writeActivitiesToProto(proto);
                    proto.end(activityToken);

                    long broadcastToken = proto.start(ActivityManagerServiceProto.BROADCASTS);
                    writeBroadcastsToProtoLocked(proto);
                    proto.end(broadcastToken);

                    long serviceToken = proto.start(ActivityManagerServiceProto.SERVICES);
                    mServices.writeToProto(proto,
                            ActivityManagerServiceDumpServicesProto.ACTIVE_SERVICES);
                    proto.end(serviceToken);

                    long processToken = proto.start(ActivityManagerServiceProto.PROCESSES);
                    writeProcessesToProtoLocked(proto, dumpPackage);
                    proto.end(processToken);
                }
            }
            proto.flush();
            Binder.restoreCallingIdentity(origId);
            return;
        }

        int dumpAppId = getAppId(dumpPackage);
        boolean more = false;
        // Is the caller requesting to dump a particular piece of data?
        if (opti < args.length) {
            String cmd = args[opti];
            opti++;
            if (DUMP_ACTIVITIES_CMD.equals(cmd) || DUMP_ACTIVITIES_SHORT_CMD.equals(cmd)
                    || DUMP_LASTANR_CMD.equals(cmd) || DUMP_LASTANR_TRACES_CMD.equals(cmd)
                    || DUMP_STARTER_CMD.equals(cmd) || DUMP_CONTAINERS_CMD.equals(cmd)
                    || DUMP_RECENTS_CMD.equals(cmd) || DUMP_RECENTS_SHORT_CMD.equals(cmd)) {
                mAtmInternal.dump(
                        cmd, fd, pw, args, opti, true /* dumpAll */, dumpClient, dumpPackage);
            } else if ("binder-proxies".equals(cmd)) {
                if (opti >= args.length) {
                    dumpBinderProxies(pw, 0 /* minToDump */);
                } else {
                    String uid = args[opti];
                    opti++;
                    // Ensure Binder Proxy Count is as up to date as possible
                    System.gc();
                    System.runFinalization();
                    System.gc();
                    pw.println(BinderInternal.nGetBinderProxyCount(Integer.parseInt(uid)));
                }
            } else if ("allowed-associations".equals(cmd)) {
                if (opti < args.length) {
                    dumpPackage = args[opti];
                    opti++;
                }
                synchronized (this) {
                    dumpAllowedAssociationsLocked(fd, pw, args, opti, true, dumpPackage);
                }
            } else if ("broadcasts".equals(cmd) || "b".equals(cmd)) {
                if (opti < args.length) {
                    dumpPackage = args[opti];
                    opti++;
                }
                synchronized (this) {
                    dumpBroadcastsLocked(fd, pw, args, opti, true, dumpPackage);
                }
            } else if ("broadcast-stats".equals(cmd)) {
                if (opti < args.length) {
                    dumpPackage = args[opti];
                    opti++;
                }
                synchronized (this) {
                    if (dumpCheckinFormat) {
                        dumpBroadcastStatsCheckinLocked(fd, pw, args, opti, dumpCheckin,
                                dumpPackage);
                    } else {
                        dumpBroadcastStatsLocked(fd, pw, args, opti, true, dumpPackage);
                    }
                }
            } else if ("intents".equals(cmd) || "i".equals(cmd)) {
                if (opti < args.length) {
                    dumpPackage = args[opti];
                    opti++;
                }
                synchronized (this) {
                    mPendingIntentController.dumpPendingIntents(pw, true, dumpPackage);
                }
            } else if ("processes".equals(cmd) || "p".equals(cmd)) {
                if (opti < args.length) {
                    dumpPackage = args[opti];
                    opti++;
                }
                synchronized (this) {
                    dumpProcessesLocked(fd, pw, args, opti, true, dumpPackage, dumpAppId);
                }
            } else if ("oom".equals(cmd) || "o".equals(cmd)) {
                synchronized (this) {
                    dumpOomLocked(fd, pw, args, opti, true);
                }
            } else if ("lmk".equals(cmd)) {
                synchronized (this) {
                    dumpLmkLocked(pw);
                }
            } else if ("lru".equals(cmd)) {
                synchronized (this) {
                    dumpLruLocked(pw, null);
                }
            } else if ("permissions".equals(cmd) || "perm".equals(cmd)) {
                synchronized (this) {
                    dumpPermissionsLocked(fd, pw, args, opti, true, null);
                }
            } else if ("provider".equals(cmd)) {
                String[] newArgs;
                String name;
                if (opti >= args.length) {
                    name = null;
                    newArgs = EMPTY_STRING_ARRAY;
                } else {
                    name = args[opti];
                    opti++;
                    newArgs = new String[args.length - opti];
                    if (args.length > 2) System.arraycopy(args, opti, newArgs, 0, args.length - opti);
                }
                if (!dumpProvider(fd, pw, name, newArgs, 0, dumpAll)) {
                    pw.println("No providers match: " + name);
                    pw.println("Use -h for help.");
                }
            } else if ("providers".equals(cmd) || "prov".equals(cmd)) {
                synchronized (this) {
                    dumpProvidersLocked(fd, pw, args, opti, true, null);
                }
            } else if ("service".equals(cmd)) {
                String[] newArgs;
                String name;
                if (opti >= args.length) {
                    name = null;
                    newArgs = EMPTY_STRING_ARRAY;
                } else {
                    name = args[opti];
                    opti++;
                    newArgs = new String[args.length - opti];
                    if (args.length > 2) System.arraycopy(args, opti, newArgs, 0,
                            args.length - opti);
                }
                if (!mServices.dumpService(fd, pw, name, newArgs, 0, dumpAll)) {
                    pw.println("No services match: " + name);
                    pw.println("Use -h for help.");
                }
            } else if ("package".equals(cmd)) {
                String[] newArgs;
                if (opti >= args.length) {
                    pw.println("package: no package name specified");
                    pw.println("Use -h for help.");
                } else {
                    dumpPackage = args[opti];
                    opti++;
                    newArgs = new String[args.length - opti];
                    if (args.length > 2) System.arraycopy(args, opti, newArgs, 0,
                            args.length - opti);
                    args = newArgs;
                    opti = 0;
                    more = true;
                }
            } else if ("associations".equals(cmd) || "as".equals(cmd)) {
                synchronized (this) {
                    dumpAssociationsLocked(fd, pw, args, opti, true, dumpClient, dumpPackage);
                }
            } else if ("settings".equals(cmd)) {
                synchronized (this) {
                    mConstants.dump(pw);
                    mOomAdjuster.dumpAppCompactorSettings(pw);
                }
            } else if ("services".equals(cmd) || "s".equals(cmd)) {
                if (dumpClient) {
                    ActiveServices.ServiceDumper dumper;
                    synchronized (this) {
                        dumper = mServices.newServiceDumperLocked(fd, pw, args, opti, true,
                                dumpPackage);
                    }
                    dumper.dumpWithClient();
                } else {
                    synchronized (this) {
                        mServices.newServiceDumperLocked(fd, pw, args, opti, true,
                                dumpPackage).dumpLocked();
                    }
                }
            } else if ("locks".equals(cmd)) {
                LockGuard.dump(fd, pw, args);
            } else {
                // Dumping a single activity?
                if (!mAtmInternal.dumpActivity(fd, pw, cmd, args, opti, dumpAll,
                        dumpVisibleStacksOnly, dumpFocusedStackOnly)) {
                    ActivityManagerShellCommand shell = new ActivityManagerShellCommand(this, true);
                    int res = shell.exec(this, null, fd, null, args, null,
                            new ResultReceiver(null));
                    if (res < 0) {
                        pw.println("Bad activity command, or no activities match: " + cmd);
                        pw.println("Use -h for help.");
                    }
                }
            }
            if (!more) {
                Binder.restoreCallingIdentity(origId);
                return;
            }
        }

        // No piece of data specified, dump everything.
        if (dumpCheckinFormat) {
            dumpBroadcastStatsCheckinLocked(fd, pw, args, opti, dumpCheckin, dumpPackage);
        } else {
            if (dumpClient) {
                // dumpEverything() will take the lock when needed, and momentarily drop
                // it for dumping client state.
                dumpEverything(fd, pw, args, opti, dumpAll, dumpPackage, dumpClient,
                        dumpNormalPriority, dumpAppId);
            } else {
                // Take the lock here, so we get a consistent state for the entire dump;
                // dumpEverything() will take the lock as well, but that is fine.
                synchronized(this) {
                    dumpEverything(fd, pw, args, opti, dumpAll, dumpPackage, dumpClient,
                            dumpNormalPriority, dumpAppId);
                }
            }
        }
        Binder.restoreCallingIdentity(origId);
    }

    void dumpAssociationsLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, boolean dumpClient, String dumpPackage) {
        pw.println("ACTIVITY MANAGER ASSOCIATIONS (dumpsys activity associations)");

        int dumpUid = 0;
        if (dumpPackage != null) {
            IPackageManager pm = AppGlobals.getPackageManager();
            try {
                dumpUid = pm.getPackageUid(dumpPackage, MATCH_ANY_USER, 0);
            } catch (RemoteException e) {
            }
        }

        boolean printedAnything = false;

        final long now = SystemClock.uptimeMillis();

        for (int i1=0, N1=mAssociations.size(); i1<N1; i1++) {
            ArrayMap<ComponentName, SparseArray<ArrayMap<String, Association>>> targetComponents
                    = mAssociations.valueAt(i1);
            for (int i2=0, N2=targetComponents.size(); i2<N2; i2++) {
                SparseArray<ArrayMap<String, Association>> sourceUids
                        = targetComponents.valueAt(i2);
                for (int i3=0, N3=sourceUids.size(); i3<N3; i3++) {
                    ArrayMap<String, Association> sourceProcesses = sourceUids.valueAt(i3);
                    for (int i4=0, N4=sourceProcesses.size(); i4<N4; i4++) {
                        Association ass = sourceProcesses.valueAt(i4);
                        if (dumpPackage != null) {
                            if (!ass.mTargetComponent.getPackageName().equals(dumpPackage)
                                    && UserHandle.getAppId(ass.mSourceUid) != dumpUid) {
                                continue;
                            }
                        }
                        printedAnything = true;
                        pw.print("  ");
                        pw.print(ass.mTargetProcess);
                        pw.print("/");
                        UserHandle.formatUid(pw, ass.mTargetUid);
                        pw.print(" <- ");
                        pw.print(ass.mSourceProcess);
                        pw.print("/");
                        UserHandle.formatUid(pw, ass.mSourceUid);
                        pw.println();
                        pw.print("    via ");
                        pw.print(ass.mTargetComponent.flattenToShortString());
                        pw.println();
                        pw.print("    ");
                        long dur = ass.mTime;
                        if (ass.mNesting > 0) {
                            dur += now - ass.mStartTime;
                        }
                        TimeUtils.formatDuration(dur, pw);
                        pw.print(" (");
                        pw.print(ass.mCount);
                        pw.print(" times)");
                        pw.print("  ");
                        for (int i=0; i<ass.mStateTimes.length; i++) {
                            long amt = ass.mStateTimes[i];
                            if ((ass.mLastState-ActivityManager.MIN_PROCESS_STATE) == i) {
                                amt += now - ass.mLastStateUptime;
                            }
                            if (amt != 0) {
                                pw.print(" ");
                                pw.print(ProcessList.makeProcStateString(
                                            i + ActivityManager.MIN_PROCESS_STATE));
                                pw.print("=");
                                TimeUtils.formatDuration(amt, pw);
                                if ((ass.mLastState-ActivityManager.MIN_PROCESS_STATE) == i) {
                                    pw.print("*");
                                }
                            }
                        }
                        pw.println();
                        if (ass.mNesting > 0) {
                            pw.print("    Currently active: ");
                            TimeUtils.formatDuration(now - ass.mStartTime, pw);
                            pw.println();
                        }
                    }
                }
            }

        }

        if (!printedAnything) {
            pw.println("  (nothing)");
        }
    }

    private int getAppId(String dumpPackage) {
        if (dumpPackage != null) {
            try {
                ApplicationInfo info = mContext.getPackageManager().getApplicationInfo(
                        dumpPackage, 0);
                return UserHandle.getAppId(info.uid);
            } catch (NameNotFoundException e) {
                e.printStackTrace();
            }
        }
        return -1;
    }

    boolean dumpUids(PrintWriter pw, String dumpPackage, int dumpAppId, ActiveUids uids,
                String header, boolean needSep) {
        boolean printed = false;
        for (int i=0; i<uids.size(); i++) {
            UidRecord uidRec = uids.valueAt(i);
            if (dumpPackage != null && UserHandle.getAppId(uidRec.uid) != dumpAppId) {
                continue;
            }
            if (!printed) {
                printed = true;
                if (needSep) {
                    pw.println();
                }
                pw.print("  ");
                pw.println(header);
                needSep = true;
            }
            pw.print("    UID "); UserHandle.formatUid(pw, uidRec.uid);
            pw.print(": "); pw.println(uidRec);
        }
        return printed;
    }

    void dumpBinderProxyInterfaceCounts(PrintWriter pw, String header) {
        final BinderProxy.InterfaceCount[] proxyCounts = BinderProxy.getSortedInterfaceCounts(50);

        pw.println(header);
        for (int i = 0; i < proxyCounts.length; i++) {
            pw.println("    #" + (i + 1) + ": " + proxyCounts[i]);
        }
    }

    boolean dumpBinderProxiesCounts(PrintWriter pw, String header) {
        SparseIntArray counts = BinderInternal.nGetBinderProxyPerUidCounts();
        if(counts != null) {
            pw.println(header);
            for (int i = 0; i < counts.size(); i++) {
                final int uid = counts.keyAt(i);
                final int binderCount = counts.valueAt(i);
                pw.print("    UID ");
                pw.print(uid);
                pw.print(", binder count = ");
                pw.print(binderCount);
                pw.print(", package(s)= ");
                final String[] pkgNames = mContext.getPackageManager().getPackagesForUid(uid);
                if (pkgNames != null) {
                    for (int j = 0; j < pkgNames.length; j++) {
                        pw.print(pkgNames[j]);
                        pw.print("; ");
                    }
                } else {
                    pw.print("NO PACKAGE NAME FOUND");
                }
                pw.println();
            }
            return true;
        }
        return false;
    }

    void dumpBinderProxies(PrintWriter pw, int minCountToDumpInterfaces) {
        pw.println("ACTIVITY MANAGER BINDER PROXY STATE (dumpsys activity binder-proxies)");
        final int proxyCount = BinderProxy.getProxyCount();
        if (proxyCount >= minCountToDumpInterfaces) {
            dumpBinderProxyInterfaceCounts(pw,
                    "Top proxy interface names held by SYSTEM");
        } else {
            pw.print("Not dumping proxy interface counts because size ("
                    + Integer.toString(proxyCount) + ") looks reasonable");
            pw.println();
        }
        dumpBinderProxiesCounts(pw,
                "  Counts of Binder Proxies held by SYSTEM");
    }

    void dumpLruEntryLocked(PrintWriter pw, int index, ProcessRecord proc) {
        pw.print("    #");
        pw.print(index);
        pw.print(": ");
        pw.print(ProcessList.makeOomAdjString(proc.setAdj, false));
        pw.print(" ");
        pw.print(ProcessList.makeProcStateString(proc.getCurProcState()));
        pw.print(" ");
        pw.print(proc.toShortString());
        pw.print(" ");
        if (proc.hasActivitiesOrRecentTasks() || proc.hasClientActivities()
                || proc.treatLikeActivity) {
            pw.print(" activity=");
            boolean printed = false;
            if (proc.hasActivities()) {
                pw.print("activities");
                printed = true;
            }
            if (proc.hasRecentTasks()) {
                if (printed) {
                    pw.print("|");
                }
                pw.print("recents");
                printed = true;
            }
            if (proc.hasClientActivities()) {
                if (printed) {
                    pw.print("|");
                }
                pw.print("client");
                printed = true;
            }
            if (proc.treatLikeActivity) {
                if (printed) {
                    pw.print("|");
                }
                pw.print("treated");
            }
        }
        pw.println();
    }

    // TODO: Move to ProcessList?
    void dumpLruLocked(PrintWriter pw, String dumpPackage) {
        pw.println("ACTIVITY MANAGER LRU PROCESSES (dumpsys activity lru)");
        final int N = mProcessList.mLruProcesses.size();
        int i;
        boolean first = true;
        for (i = N - 1; i >= mProcessList.mLruProcessActivityStart; i--) {
            final ProcessRecord r = mProcessList.mLruProcesses.get(i);
            if (dumpPackage != null && !r.pkgList.containsKey(dumpPackage)) {
                continue;
            }
            if (first) {
                pw.println("  Activities:");
                first = false;
            }
            dumpLruEntryLocked(pw, i, r);
        }
        first = true;
        for (; i >= mProcessList.mLruProcessServiceStart; i--) {
            final ProcessRecord r = mProcessList.mLruProcesses.get(i);
            if (dumpPackage != null && !r.pkgList.containsKey(dumpPackage)) {
                continue;
            }
            if (first) {
                pw.println("  Services:");
                first = false;
            }
            dumpLruEntryLocked(pw, i, r);
        }
        first = true;
        for (; i >= 0; i--) {
            final ProcessRecord r = mProcessList.mLruProcesses.get(i);
            if (dumpPackage != null && !r.pkgList.containsKey(dumpPackage)) {
                continue;
            }
            if (first) {
                pw.println("  Other:");
                first = false;
            }
            dumpLruEntryLocked(pw, i, r);
        }
    }

    // TODO: Move to ProcessList?
    @GuardedBy("this")
    void dumpProcessesLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage, int dumpAppId) {
        boolean needSep = false;
        int numPers = 0;

        pw.println("ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)");

        if (dumpAll) {
            final int NP = mProcessList.mProcessNames.getMap().size();
            for (int ip=0; ip<NP; ip++) {
                SparseArray<ProcessRecord> procs = mProcessList.mProcessNames.getMap().valueAt(ip);
                final int NA = procs.size();
                for (int ia=0; ia<NA; ia++) {
                    ProcessRecord r = procs.valueAt(ia);
                    if (dumpPackage != null && !r.pkgList.containsKey(dumpPackage)) {
                        continue;
                    }
                    if (!needSep) {
                        pw.println("  All known processes:");
                        needSep = true;
                    }
                    pw.print(r.isPersistent() ? "  *PERS*" : "  *APP*");
                        pw.print(" UID "); pw.print(procs.keyAt(ia));
                        pw.print(" "); pw.println(r);
                    r.dump(pw, "    ");
                    if (r.isPersistent()) {
                        numPers++;
                    }
                }
            }
        }

        if (mProcessList.mIsolatedProcesses.size() > 0) {
            boolean printed = false;
            for (int i=0; i<mProcessList.mIsolatedProcesses.size(); i++) {
                ProcessRecord r = mProcessList.mIsolatedProcesses.valueAt(i);
                if (dumpPackage != null && !r.pkgList.containsKey(dumpPackage)) {
                    continue;
                }
                if (!printed) {
                    if (needSep) {
                        pw.println();
                    }
                    pw.println("  Isolated process list (sorted by uid):");
                    printed = true;
                    needSep = true;
                }
                pw.print("    Isolated #"); pw.print(i); pw.print(": ");
                pw.println(r);
            }
        }

        if (mActiveInstrumentation.size() > 0) {
            boolean printed = false;
            for (int i=0; i<mActiveInstrumentation.size(); i++) {
                ActiveInstrumentation ai = mActiveInstrumentation.get(i);
                if (dumpPackage != null && !ai.mClass.getPackageName().equals(dumpPackage)
                        && !ai.mTargetInfo.packageName.equals(dumpPackage)) {
                    continue;
                }
                if (!printed) {
                    if (needSep) {
                        pw.println();
                    }
                    pw.println("  Active instrumentation:");
                    printed = true;
                    needSep = true;
                }
                pw.print("    Instrumentation #"); pw.print(i); pw.print(": ");
                pw.println(ai);
                ai.dump(pw, "      ");
            }
        }

        if (mProcessList.mActiveUids.size() > 0) {
            if (dumpUids(pw, dumpPackage, dumpAppId, mProcessList.mActiveUids,
                    "UID states:", needSep)) {
                needSep = true;
            }
        }

        if (dumpAll) {
            if (mValidateUids.size() > 0) {
                if (dumpUids(pw, dumpPackage, dumpAppId, mValidateUids, "UID validation:",
                        needSep)) {
                    needSep = true;
                }
            }
        }

        if (mProcessList.getLruSizeLocked() > 0) {
            if (needSep) {
                pw.println();
            }
            mProcessList.dumpLruListHeaderLocked(pw);
            dumpProcessOomList(pw, this, mProcessList.mLruProcesses, "    ", "Proc", "PERS", false,
                    dumpPackage);
            needSep = true;
        }

        if (dumpAll || dumpPackage != null) {
            synchronized (mPidsSelfLocked) {
                boolean printed = false;
                for (int i=0; i<mPidsSelfLocked.size(); i++) {
                    ProcessRecord r = mPidsSelfLocked.valueAt(i);
                    if (dumpPackage != null && !r.pkgList.containsKey(dumpPackage)) {
                        continue;
                    }
                    if (!printed) {
                        if (needSep) pw.println();
                        needSep = true;
                        pw.println("  PID mappings:");
                        printed = true;
                    }
                    pw.print("    PID #"); pw.print(mPidsSelfLocked.keyAt(i));
                        pw.print(": "); pw.println(mPidsSelfLocked.valueAt(i));
                }
            }
        }

        if (mImportantProcesses.size() > 0) {
            synchronized (mPidsSelfLocked) {
                boolean printed = false;
                for (int i = 0; i< mImportantProcesses.size(); i++) {
                    ProcessRecord r = mPidsSelfLocked.get(
                            mImportantProcesses.valueAt(i).pid);
                    if (dumpPackage != null && (r == null
                            || !r.pkgList.containsKey(dumpPackage))) {
                        continue;
                    }
                    if (!printed) {
                        if (needSep) pw.println();
                        needSep = true;
                        pw.println("  Foreground Processes:");
                        printed = true;
                    }
                    pw.print("    PID #"); pw.print(mImportantProcesses.keyAt(i));
                            pw.print(": "); pw.println(mImportantProcesses.valueAt(i));
                }
            }
        }

        if (mPersistentStartingProcesses.size() > 0) {
            if (needSep) pw.println();
            needSep = true;
            pw.println("  Persisent processes that are starting:");
            dumpProcessList(pw, this, mPersistentStartingProcesses, "    ",
                    "Starting Norm", "Restarting PERS", dumpPackage);
        }

        if (mProcessList.mRemovedProcesses.size() > 0) {
            if (needSep) pw.println();
            needSep = true;
            pw.println("  Processes that are being removed:");
            dumpProcessList(pw, this, mProcessList.mRemovedProcesses, "    ",
                    "Removed Norm", "Removed PERS", dumpPackage);
        }

        if (mProcessesOnHold.size() > 0) {
            if (needSep) pw.println();
            needSep = true;
            pw.println("  Processes that are on old until the system is ready:");
            dumpProcessList(pw, this, mProcessesOnHold, "    ",
                    "OnHold Norm", "OnHold PERS", dumpPackage);
        }

        needSep = dumpProcessesToGc(pw, needSep, dumpPackage);

        needSep = mAppErrors.dumpLocked(fd, pw, needSep, dumpPackage);

        if (dumpPackage == null) {
            pw.println();
            needSep = false;
            mUserController.dump(pw, dumpAll);
        }

        needSep = mAtmInternal.dumpForProcesses(fd, pw, dumpAll, dumpPackage, dumpAppId, needSep,
                mTestPssMode, mWakefulness);

        if (dumpAll && mProcessList.mPendingStarts.size() > 0) {
            if (needSep) pw.println();
            needSep = true;
            pw.println("  mPendingStarts: ");
            for (int i = 0, len = mProcessList.mPendingStarts.size(); i < len; ++i ) {
                pw.println("    " + mProcessList.mPendingStarts.keyAt(i) + ": "
                        + mProcessList.mPendingStarts.valueAt(i));
            }
        }
        if (dumpAll) {
            final int NI = mUidObservers.getRegisteredCallbackCount();
            boolean printed = false;
            for (int i=0; i<NI; i++) {
                final UidObserverRegistration reg = (UidObserverRegistration)
                        mUidObservers.getRegisteredCallbackCookie(i);
                if (dumpPackage == null || dumpPackage.equals(reg.pkg)) {
                    if (!printed) {
                        pw.println("  mUidObservers:");
                        printed = true;
                    }
                    pw.print("    "); UserHandle.formatUid(pw, reg.uid);
                    pw.print(" "); pw.print(reg.pkg);
                    final IUidObserver observer = mUidObservers.getRegisteredCallbackItem(i);
                    pw.print(" "); pw.print(observer.getClass().getTypeName()); pw.print(":");
                    if ((reg.which&ActivityManager.UID_OBSERVER_IDLE) != 0) {
                        pw.print(" IDLE");
                    }
                    if ((reg.which&ActivityManager.UID_OBSERVER_ACTIVE) != 0) {
                        pw.print(" ACT" );
                    }
                    if ((reg.which&ActivityManager.UID_OBSERVER_GONE) != 0) {
                        pw.print(" GONE");
                    }
                    if ((reg.which&ActivityManager.UID_OBSERVER_PROCSTATE) != 0) {
                        pw.print(" STATE");
                        pw.print(" (cut="); pw.print(reg.cutpoint);
                        pw.print(")");
                    }
                    pw.println();
                    if (reg.lastProcStates != null) {
                        final int NJ = reg.lastProcStates.size();
                        for (int j=0; j<NJ; j++) {
                            pw.print("      Last ");
                            UserHandle.formatUid(pw, reg.lastProcStates.keyAt(j));
                            pw.print(": "); pw.println(reg.lastProcStates.valueAt(j));
                        }
                    }
                }
            }
            pw.println("  mDeviceIdleWhitelist=" + Arrays.toString(mDeviceIdleWhitelist));
            pw.println("  mDeviceIdleExceptIdleWhitelist="
                    + Arrays.toString(mDeviceIdleExceptIdleWhitelist));
            pw.println("  mDeviceIdleTempWhitelist=" + Arrays.toString(mDeviceIdleTempWhitelist));
            if (mPendingTempWhitelist.size() > 0) {
                pw.println("  mPendingTempWhitelist:");
                for (int i = 0; i < mPendingTempWhitelist.size(); i++) {
                    PendingTempWhitelist ptw = mPendingTempWhitelist.valueAt(i);
                    pw.print("    ");
                    UserHandle.formatUid(pw, ptw.targetUid);
                    pw.print(": ");
                    TimeUtils.formatDuration(ptw.duration, pw);
                    pw.print(" ");
                    pw.println(ptw.tag);
                }
            }
        }
        if (mDebugApp != null || mOrigDebugApp != null || mDebugTransient
                || mOrigWaitForDebugger) {
            if (dumpPackage == null || dumpPackage.equals(mDebugApp)
                    || dumpPackage.equals(mOrigDebugApp)) {
                if (needSep) {
                    pw.println();
                    needSep = false;
                }
                pw.println("  mDebugApp=" + mDebugApp + "/orig=" + mOrigDebugApp
                        + " mDebugTransient=" + mDebugTransient
                        + " mOrigWaitForDebugger=" + mOrigWaitForDebugger);
            }
        }
        if (mMemWatchProcesses.getMap().size() > 0) {
            pw.println("  Mem watch processes:");
            final ArrayMap<String, SparseArray<Pair<Long, String>>> procs
                    = mMemWatchProcesses.getMap();
            for (int i=0; i<procs.size(); i++) {
                final String proc = procs.keyAt(i);
                final SparseArray<Pair<Long, String>> uids = procs.valueAt(i);
                for (int j=0; j<uids.size(); j++) {
                    if (needSep) {
                        pw.println();
                        needSep = false;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append("    ").append(proc).append('/');
                    UserHandle.formatUid(sb, uids.keyAt(j));
                    Pair<Long, String> val = uids.valueAt(j);
                    sb.append(": "); DebugUtils.sizeValueToString(val.first, sb);
                    if (val.second != null) {
                        sb.append(", report to ").append(val.second);
                    }
                    pw.println(sb.toString());
                }
            }
            pw.print("  mMemWatchDumpProcName="); pw.println(mMemWatchDumpProcName);
            pw.print("  mMemWatchDumpFile="); pw.println(mMemWatchDumpFile);
            pw.print("  mMemWatchDumpPid="); pw.print(mMemWatchDumpPid);
                    pw.print(" mMemWatchDumpUid="); pw.println(mMemWatchDumpUid);
        }
        if (mTrackAllocationApp != null) {
            if (dumpPackage == null || dumpPackage.equals(mTrackAllocationApp)) {
                if (needSep) {
                    pw.println();
                    needSep = false;
                }
                pw.println("  mTrackAllocationApp=" + mTrackAllocationApp);
            }
        }
        if (mProfileData.getProfileApp() != null || mProfileData.getProfileProc() != null
                || (mProfileData.getProfilerInfo() != null &&
                (mProfileData.getProfilerInfo().profileFile != null
                        || mProfileData.getProfilerInfo().profileFd != null))) {
            if (dumpPackage == null || dumpPackage.equals(mProfileData.getProfileApp())) {
                if (needSep) {
                    pw.println();
                    needSep = false;
                }
                pw.println("  mProfileApp=" + mProfileData.getProfileApp()
                        + " mProfileProc=" + mProfileData.getProfileProc());
                if (mProfileData.getProfilerInfo() != null) {
                    pw.println("  mProfileFile=" + mProfileData.getProfilerInfo().profileFile
                            + " mProfileFd=" + mProfileData.getProfilerInfo().profileFd);
                    pw.println("  mSamplingInterval="
                            + mProfileData.getProfilerInfo().samplingInterval +
                            " mAutoStopProfiler="
                            + mProfileData.getProfilerInfo().autoStopProfiler +
                            " mStreamingOutput=" + mProfileData.getProfilerInfo().streamingOutput);
                    pw.println("  mProfileType=" + mProfileType);
                }
            }
        }
        if (mNativeDebuggingApp != null) {
            if (dumpPackage == null || dumpPackage.equals(mNativeDebuggingApp)) {
                if (needSep) {
                    pw.println();
                    needSep = false;
                }
                pw.println("  mNativeDebuggingApp=" + mNativeDebuggingApp);
            }
        }
        if (dumpPackage == null) {
            if (mAlwaysFinishActivities) {
                pw.println("  mAlwaysFinishActivities=" + mAlwaysFinishActivities);
            }
            if (dumpAll) {
                pw.println("  Total persistent processes: " + numPers);
                pw.println("  mProcessesReady=" + mProcessesReady
                        + " mSystemReady=" + mSystemReady
                        + " mBooted=" + mBooted
                        + " mFactoryTest=" + mFactoryTest);
                pw.println("  mBooting=" + mBooting
                        + " mCallFinishBooting=" + mCallFinishBooting
                        + " mBootAnimationComplete=" + mBootAnimationComplete);
                pw.print("  mLastPowerCheckUptime=");
                        TimeUtils.formatDuration(mLastPowerCheckUptime, pw);
                        pw.println("");
                mOomAdjuster.dumpSequenceNumbersLocked(pw);
                mOomAdjuster.dumpProcCountsLocked(pw);
                pw.println("  mAllowLowerMemLevel=" + mAllowLowerMemLevel
                        + " mLastMemoryLevel=" + mLastMemoryLevel
                        + " mLastNumProcesses=" + mLastNumProcesses);
                long now = SystemClock.uptimeMillis();
                pw.print("  mLastIdleTime=");
                        TimeUtils.formatDuration(now, mLastIdleTime, pw);
                        pw.print(" mLowRamSinceLastIdle=");
                        TimeUtils.formatDuration(getLowRamTimeSinceIdle(now), pw);
                        pw.println();
                pw.println();
                pw.print("  mUidChangeDispatchCount=");
                pw.print(mUidChangeDispatchCount);
                pw.println();

                pw.println("  Slow UID dispatches:");
                final int N = mUidObservers.beginBroadcast();
                for (int i = 0; i < N; i++) {
                    UidObserverRegistration r =
                            (UidObserverRegistration) mUidObservers.getBroadcastCookie(i);
                    pw.print("    ");
                    pw.print(mUidObservers.getBroadcastItem(i).getClass().getTypeName());
                    pw.print(": ");
                    pw.print(r.mSlowDispatchCount);
                    pw.print(" / Max ");
                    pw.print(r.mMaxDispatchTime);
                    pw.println("ms");
                }
                mUidObservers.finishBroadcast();

                pw.println();
                pw.println("  ServiceManager statistics:");
                ServiceManager.sStatLogger.dump(pw, "    ");
                pw.println();
            }
        }
        pw.println("  mForceBackgroundCheck=" + mForceBackgroundCheck);
    }

    @GuardedBy("this")
    void writeProcessesToProtoLocked(ProtoOutputStream proto, String dumpPackage) {
        int numPers = 0;

        final int NP = mProcessList.mProcessNames.getMap().size();
        for (int ip=0; ip<NP; ip++) {
            SparseArray<ProcessRecord> procs = mProcessList.mProcessNames.getMap().valueAt(ip);
            final int NA = procs.size();
            for (int ia = 0; ia<NA; ia++) {
                ProcessRecord r = procs.valueAt(ia);
                if (dumpPackage != null && !r.pkgList.containsKey(dumpPackage)) {
                    continue;
                }
                r.writeToProto(proto, ActivityManagerServiceDumpProcessesProto.PROCS,
                        mProcessList.mLruProcesses.indexOf(r)
                );
                if (r.isPersistent()) {
                    numPers++;
                }
            }
        }

        for (int i=0; i<mProcessList.mIsolatedProcesses.size(); i++) {
            ProcessRecord r = mProcessList.mIsolatedProcesses.valueAt(i);
            if (dumpPackage != null && !r.pkgList.containsKey(dumpPackage)) {
                continue;
            }
            r.writeToProto(proto, ActivityManagerServiceDumpProcessesProto.ISOLATED_PROCS,
                    mProcessList.mLruProcesses.indexOf(r)
            );
        }

        for (int i=0; i<mActiveInstrumentation.size(); i++) {
            ActiveInstrumentation ai = mActiveInstrumentation.get(i);
            if (dumpPackage != null && !ai.mClass.getPackageName().equals(dumpPackage)
                    && !ai.mTargetInfo.packageName.equals(dumpPackage)) {
                continue;
            }
            ai.writeToProto(proto,
                    ActivityManagerServiceDumpProcessesProto.ACTIVE_INSTRUMENTATIONS);
        }

        int whichAppId = getAppId(dumpPackage);
        for (int i = 0; i < mProcessList.mActiveUids.size(); i++) {
            UidRecord uidRec = mProcessList.mActiveUids.valueAt(i);
            if (dumpPackage != null && UserHandle.getAppId(uidRec.uid) != whichAppId) {
                continue;
            }
            uidRec.writeToProto(proto, ActivityManagerServiceDumpProcessesProto.ACTIVE_UIDS);
        }

        for (int i = 0; i < mValidateUids.size(); i++) {
            UidRecord uidRec = mValidateUids.valueAt(i);
            if (dumpPackage != null && UserHandle.getAppId(uidRec.uid) != whichAppId) {
                continue;
            }
            uidRec.writeToProto(proto, ActivityManagerServiceDumpProcessesProto.VALIDATE_UIDS);
        }

        if (mProcessList.getLruSizeLocked() > 0) {
            long lruToken = proto.start(ActivityManagerServiceDumpProcessesProto.LRU_PROCS);
            int total = mProcessList.getLruSizeLocked();
            proto.write(ActivityManagerServiceDumpProcessesProto.LruProcesses.SIZE, total);
            proto.write(ActivityManagerServiceDumpProcessesProto.LruProcesses.NON_ACT_AT,
                    total - mProcessList.mLruProcessActivityStart);
            proto.write(ActivityManagerServiceDumpProcessesProto.LruProcesses.NON_SVC_AT,
                    total - mProcessList.mLruProcessServiceStart);
            writeProcessOomListToProto(proto,
                    ActivityManagerServiceDumpProcessesProto.LruProcesses.LIST, this,
                    mProcessList.mLruProcesses,false, dumpPackage);
            proto.end(lruToken);
        }

        if (dumpPackage != null) {
            synchronized (mPidsSelfLocked) {
                for (int i=0; i<mPidsSelfLocked.size(); i++) {
                    ProcessRecord r = mPidsSelfLocked.valueAt(i);
                    if (!r.pkgList.containsKey(dumpPackage)) {
                        continue;
                    }
                    r.writeToProto(proto,
                            ActivityManagerServiceDumpProcessesProto.PIDS_SELF_LOCKED);
                }
            }
        }

        if (mImportantProcesses.size() > 0) {
            synchronized (mPidsSelfLocked) {
                for (int i=0; i<mImportantProcesses.size(); i++) {
                    ImportanceToken it = mImportantProcesses.valueAt(i);
                    ProcessRecord r = mPidsSelfLocked.get(it.pid);
                    if (dumpPackage != null && (r == null
                            || !r.pkgList.containsKey(dumpPackage))) {
                        continue;
                    }
                    it.writeToProto(proto,
                            ActivityManagerServiceDumpProcessesProto.IMPORTANT_PROCS);
                }
            }
        }

        for (int i=0; i<mPersistentStartingProcesses.size(); i++) {
            ProcessRecord r = mPersistentStartingProcesses.get(i);
            if (dumpPackage != null && !dumpPackage.equals(r.info.packageName)) {
                continue;
            }
            r.writeToProto(proto,
                    ActivityManagerServiceDumpProcessesProto.PERSISTENT_STARTING_PROCS);
        }

        for (int i = 0; i < mProcessList.mRemovedProcesses.size(); i++) {
            ProcessRecord r = mProcessList.mRemovedProcesses.get(i);
            if (dumpPackage != null && !dumpPackage.equals(r.info.packageName)) {
                continue;
            }
            r.writeToProto(proto, ActivityManagerServiceDumpProcessesProto.REMOVED_PROCS);
        }

        for (int i=0; i<mProcessesOnHold.size(); i++) {
            ProcessRecord r = mProcessesOnHold.get(i);
            if (dumpPackage != null && !dumpPackage.equals(r.info.packageName)) {
                continue;
            }
            r.writeToProto(proto, ActivityManagerServiceDumpProcessesProto.ON_HOLD_PROCS);
        }

        writeProcessesToGcToProto(proto, ActivityManagerServiceDumpProcessesProto.GC_PROCS,
                dumpPackage);
        mAppErrors.writeToProto(proto, ActivityManagerServiceDumpProcessesProto.APP_ERRORS,
                dumpPackage);
        mAtmInternal.writeProcessesToProto(proto, dumpPackage, mWakefulness, mTestPssMode);

        if (dumpPackage == null) {
            mUserController.writeToProto(proto,
            ActivityManagerServiceDumpProcessesProto.USER_CONTROLLER);
        }

        final int NI = mUidObservers.getRegisteredCallbackCount();
        for (int i=0; i<NI; i++) {
            final UidObserverRegistration reg = (UidObserverRegistration)
                    mUidObservers.getRegisteredCallbackCookie(i);
            if (dumpPackage == null || dumpPackage.equals(reg.pkg)) {
                reg.writeToProto(proto, ActivityManagerServiceDumpProcessesProto.UID_OBSERVERS);
            }
        }

        for (int v : mDeviceIdleWhitelist) {
            proto.write(ActivityManagerServiceDumpProcessesProto.DEVICE_IDLE_WHITELIST, v);
        }

        for (int v : mDeviceIdleTempWhitelist) {
            proto.write(ActivityManagerServiceDumpProcessesProto.DEVICE_IDLE_TEMP_WHITELIST, v);
        }

        if (mPendingTempWhitelist.size() > 0) {
            for (int i=0; i < mPendingTempWhitelist.size(); i++) {
                mPendingTempWhitelist.valueAt(i).writeToProto(proto,
                        ActivityManagerServiceDumpProcessesProto.PENDING_TEMP_WHITELIST);
            }
        }

        if (mDebugApp != null || mOrigDebugApp != null || mDebugTransient
                || mOrigWaitForDebugger) {
            if (dumpPackage == null || dumpPackage.equals(mDebugApp)
                    || dumpPackage.equals(mOrigDebugApp)) {
                final long debugAppToken = proto.start(ActivityManagerServiceDumpProcessesProto.DEBUG);
                proto.write(ActivityManagerServiceDumpProcessesProto.DebugApp.DEBUG_APP, mDebugApp);
                proto.write(ActivityManagerServiceDumpProcessesProto.DebugApp.ORIG_DEBUG_APP, mOrigDebugApp);
                proto.write(ActivityManagerServiceDumpProcessesProto.DebugApp.DEBUG_TRANSIENT, mDebugTransient);
                proto.write(ActivityManagerServiceDumpProcessesProto.DebugApp.ORIG_WAIT_FOR_DEBUGGER, mOrigWaitForDebugger);
                proto.end(debugAppToken);
            }
        }

        if (mMemWatchProcesses.getMap().size() > 0) {
            final long token = proto.start(ActivityManagerServiceDumpProcessesProto.MEM_WATCH_PROCESSES);
            ArrayMap<String, SparseArray<Pair<Long, String>>> procs = mMemWatchProcesses.getMap();
            for (int i=0; i<procs.size(); i++) {
                final String proc = procs.keyAt(i);
                final SparseArray<Pair<Long, String>> uids = procs.valueAt(i);
                final long ptoken = proto.start(ActivityManagerServiceDumpProcessesProto.MemWatchProcess.PROCS);
                proto.write(ActivityManagerServiceDumpProcessesProto.MemWatchProcess.Process.NAME, proc);
                for (int j=0; j<uids.size(); j++) {
                    final long utoken = proto.start(ActivityManagerServiceDumpProcessesProto.MemWatchProcess.Process.MEM_STATS);
                    Pair<Long, String> val = uids.valueAt(j);
                    proto.write(ActivityManagerServiceDumpProcessesProto.MemWatchProcess.Process.MemStats.UID, uids.keyAt(j));
                    proto.write(ActivityManagerServiceDumpProcessesProto.MemWatchProcess.Process.MemStats.SIZE,
                            DebugUtils.sizeValueToString(val.first, new StringBuilder()));
                    proto.write(ActivityManagerServiceDumpProcessesProto.MemWatchProcess.Process.MemStats.REPORT_TO, val.second);
                    proto.end(utoken);
                }
                proto.end(ptoken);
            }

            final long dtoken = proto.start(ActivityManagerServiceDumpProcessesProto.MemWatchProcess.DUMP);
            proto.write(ActivityManagerServiceDumpProcessesProto.MemWatchProcess.Dump.PROC_NAME, mMemWatchDumpProcName);
            proto.write(ActivityManagerServiceDumpProcessesProto.MemWatchProcess.Dump.FILE, mMemWatchDumpFile);
            proto.write(ActivityManagerServiceDumpProcessesProto.MemWatchProcess.Dump.PID, mMemWatchDumpPid);
            proto.write(ActivityManagerServiceDumpProcessesProto.MemWatchProcess.Dump.UID, mMemWatchDumpUid);
            proto.end(dtoken);

            proto.end(token);
        }

        if (mTrackAllocationApp != null) {
            if (dumpPackage == null || dumpPackage.equals(mTrackAllocationApp)) {
                proto.write(ActivityManagerServiceDumpProcessesProto.TRACK_ALLOCATION_APP,
                        mTrackAllocationApp);
            }
        }

        if (mProfileData.getProfileApp() != null || mProfileData.getProfileProc() != null
                || (mProfileData.getProfilerInfo() != null &&
                (mProfileData.getProfilerInfo().profileFile != null
                        || mProfileData.getProfilerInfo().profileFd != null))) {
            if (dumpPackage == null || dumpPackage.equals(mProfileData.getProfileApp())) {
                final long token = proto.start(ActivityManagerServiceDumpProcessesProto.PROFILE);
                proto.write(ActivityManagerServiceDumpProcessesProto.Profile.APP_NAME,
                        mProfileData.getProfileApp());
                mProfileData.getProfileProc().writeToProto(proto,
                        ActivityManagerServiceDumpProcessesProto.Profile.PROC);
                if (mProfileData.getProfilerInfo() != null) {
                    mProfileData.getProfilerInfo().writeToProto(proto,
                            ActivityManagerServiceDumpProcessesProto.Profile.INFO);
                    proto.write(ActivityManagerServiceDumpProcessesProto.Profile.TYPE,
                            mProfileType);
                }
                proto.end(token);
            }
        }

        if (dumpPackage == null || dumpPackage.equals(mNativeDebuggingApp)) {
            proto.write(ActivityManagerServiceDumpProcessesProto.NATIVE_DEBUGGING_APP, mNativeDebuggingApp);
        }

        if (dumpPackage == null) {
            proto.write(ActivityManagerServiceDumpProcessesProto.ALWAYS_FINISH_ACTIVITIES, mAlwaysFinishActivities);
            proto.write(ActivityManagerServiceDumpProcessesProto.TOTAL_PERSISTENT_PROCS, numPers);
            proto.write(ActivityManagerServiceDumpProcessesProto.PROCESSES_READY, mProcessesReady);
            proto.write(ActivityManagerServiceDumpProcessesProto.SYSTEM_READY, mSystemReady);
            proto.write(ActivityManagerServiceDumpProcessesProto.BOOTED, mBooted);
            proto.write(ActivityManagerServiceDumpProcessesProto.FACTORY_TEST, mFactoryTest);
            proto.write(ActivityManagerServiceDumpProcessesProto.BOOTING, mBooting);
            proto.write(ActivityManagerServiceDumpProcessesProto.CALL_FINISH_BOOTING, mCallFinishBooting);
            proto.write(ActivityManagerServiceDumpProcessesProto.BOOT_ANIMATION_COMPLETE, mBootAnimationComplete);
            proto.write(ActivityManagerServiceDumpProcessesProto.LAST_POWER_CHECK_UPTIME_MS, mLastPowerCheckUptime);
            mOomAdjuster.dumpProcessListVariablesLocked(proto);
            proto.write(ActivityManagerServiceDumpProcessesProto.ALLOW_LOWER_MEM_LEVEL, mAllowLowerMemLevel);
            proto.write(ActivityManagerServiceDumpProcessesProto.LAST_MEMORY_LEVEL, mLastMemoryLevel);
            proto.write(ActivityManagerServiceDumpProcessesProto.LAST_NUM_PROCESSES, mLastNumProcesses);
            long now = SystemClock.uptimeMillis();
            ProtoUtils.toDuration(proto, ActivityManagerServiceDumpProcessesProto.LAST_IDLE_TIME, mLastIdleTime, now);
            proto.write(ActivityManagerServiceDumpProcessesProto.LOW_RAM_SINCE_LAST_IDLE_MS, getLowRamTimeSinceIdle(now));
        }
    }

    void writeProcessesToGcToProto(ProtoOutputStream proto, long fieldId, String dumpPackage) {
        if (mProcessesToGc.size() > 0) {
            long now = SystemClock.uptimeMillis();
            for (int i=0; i<mProcessesToGc.size(); i++) {
                ProcessRecord r = mProcessesToGc.get(i);
                if (dumpPackage != null && !dumpPackage.equals(r.info.packageName)) {
                    continue;
                }
                final long token = proto.start(fieldId);
                r.writeToProto(proto, ProcessToGcProto.PROC);
                proto.write(ProcessToGcProto.REPORT_LOW_MEMORY, r.reportLowMemory);
                proto.write(ProcessToGcProto.NOW_UPTIME_MS, now);
                proto.write(ProcessToGcProto.LAST_GCED_MS, r.lastRequestedGc);
                proto.write(ProcessToGcProto.LAST_LOW_MEMORY_MS, r.lastLowMemory);
                proto.end(token);
            }
        }
    }

    boolean dumpProcessesToGc(PrintWriter pw, boolean needSep, String dumpPackage) {
        if (mProcessesToGc.size() > 0) {
            boolean printed = false;
            long now = SystemClock.uptimeMillis();
            for (int i=0; i<mProcessesToGc.size(); i++) {
                ProcessRecord proc = mProcessesToGc.get(i);
                if (dumpPackage != null && !dumpPackage.equals(proc.info.packageName)) {
                    continue;
                }
                if (!printed) {
                    if (needSep) pw.println();
                    needSep = true;
                    pw.println("  Processes that are waiting to GC:");
                    printed = true;
                }
                pw.print("    Process "); pw.println(proc);
                pw.print("      lowMem="); pw.print(proc.reportLowMemory);
                        pw.print(", last gced=");
                        pw.print(now-proc.lastRequestedGc);
                        pw.print(" ms ago, last lowMem=");
                        pw.print(now-proc.lastLowMemory);
                        pw.println(" ms ago");

            }
        }
        return needSep;
    }

    void printOomLevel(PrintWriter pw, String name, int adj) {
        pw.print("    ");
        if (adj >= 0) {
            pw.print(' ');
            if (adj < 10) pw.print(' ');
        } else {
            if (adj > -10) pw.print(' ');
        }
        pw.print(adj);
        pw.print(": ");
        pw.print(name);
        pw.print(" (");
        pw.print(stringifySize(mProcessList.getMemLevel(adj), 1024));
        pw.println(")");
    }

    boolean dumpOomLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll) {
        boolean needSep = false;

        if (mProcessList.getLruSizeLocked() > 0) {
            if (needSep) pw.println();
            needSep = true;
            pw.println("  OOM levels:");
            printOomLevel(pw, "SYSTEM_ADJ", ProcessList.SYSTEM_ADJ);
            printOomLevel(pw, "PERSISTENT_PROC_ADJ", ProcessList.PERSISTENT_PROC_ADJ);
            printOomLevel(pw, "PERSISTENT_SERVICE_ADJ", ProcessList.PERSISTENT_SERVICE_ADJ);
            printOomLevel(pw, "FOREGROUND_APP_ADJ", ProcessList.FOREGROUND_APP_ADJ);
            printOomLevel(pw, "VISIBLE_APP_ADJ", ProcessList.VISIBLE_APP_ADJ);
            printOomLevel(pw, "PERCEPTIBLE_APP_ADJ", ProcessList.PERCEPTIBLE_APP_ADJ);
            printOomLevel(pw, "PERCEPTIBLE_LOW_APP_ADJ", ProcessList.PERCEPTIBLE_LOW_APP_ADJ);
            printOomLevel(pw, "BACKUP_APP_ADJ", ProcessList.BACKUP_APP_ADJ);
            printOomLevel(pw, "HEAVY_WEIGHT_APP_ADJ", ProcessList.HEAVY_WEIGHT_APP_ADJ);
            printOomLevel(pw, "SERVICE_ADJ", ProcessList.SERVICE_ADJ);
            printOomLevel(pw, "HOME_APP_ADJ", ProcessList.HOME_APP_ADJ);
            printOomLevel(pw, "PREVIOUS_APP_ADJ", ProcessList.PREVIOUS_APP_ADJ);
            printOomLevel(pw, "SERVICE_B_ADJ", ProcessList.SERVICE_B_ADJ);
            printOomLevel(pw, "CACHED_APP_MIN_ADJ", ProcessList.CACHED_APP_MIN_ADJ);
            printOomLevel(pw, "CACHED_APP_MAX_ADJ", ProcessList.CACHED_APP_MAX_ADJ);

            if (needSep) pw.println();
            pw.print("  Process OOM control ("); pw.print(mProcessList.getLruSizeLocked());
                    pw.print(" total, non-act at ");
                    pw.print(mProcessList.getLruSizeLocked()
                            - mProcessList.mLruProcessActivityStart);
                    pw.print(", non-svc at ");
                    pw.print(mProcessList.getLruSizeLocked()
                            - mProcessList.mLruProcessServiceStart);
                    pw.println("):");
            dumpProcessOomList(pw, this, mProcessList.mLruProcesses, "    ", "Proc", "PERS", true,
                    null);
            needSep = true;
        }

        dumpProcessesToGc(pw, needSep, null);

        pw.println();
        mAtmInternal.dumpForOom(pw);

        return true;
    }

    private boolean reportLmkKillAtOrBelow(PrintWriter pw, int oom_adj) {
        Integer cnt = ProcessList.getLmkdKillCount(0, oom_adj);
        if (cnt != null) {
            pw.println("    kills at or below oom_adj " + oom_adj + ": " + cnt);
            return true;
        }
        return false;
    }

    boolean dumpLmkLocked(PrintWriter pw) {
        pw.println("ACTIVITY MANAGER LMK KILLS (dumpsys activity lmk)");
        Integer cnt = ProcessList.getLmkdKillCount(ProcessList.UNKNOWN_ADJ,
                ProcessList.UNKNOWN_ADJ);
        if (cnt == null) {
            return false;
        }
        pw.println("  Total number of kills: " + cnt);

        return reportLmkKillAtOrBelow(pw, ProcessList.CACHED_APP_MAX_ADJ) &&
                reportLmkKillAtOrBelow(pw, ProcessList.CACHED_APP_MIN_ADJ) &&
                reportLmkKillAtOrBelow(pw, ProcessList.SERVICE_B_ADJ) &&
                reportLmkKillAtOrBelow(pw, ProcessList.PREVIOUS_APP_ADJ) &&
                reportLmkKillAtOrBelow(pw, ProcessList.HOME_APP_ADJ) &&
                reportLmkKillAtOrBelow(pw, ProcessList.SERVICE_ADJ) &&
                reportLmkKillAtOrBelow(pw, ProcessList.HEAVY_WEIGHT_APP_ADJ) &&
                reportLmkKillAtOrBelow(pw, ProcessList.BACKUP_APP_ADJ) &&
                reportLmkKillAtOrBelow(pw, ProcessList.PERCEPTIBLE_LOW_APP_ADJ) &&
                reportLmkKillAtOrBelow(pw, ProcessList.PERCEPTIBLE_APP_ADJ) &&
                reportLmkKillAtOrBelow(pw, ProcessList.VISIBLE_APP_ADJ) &&
                reportLmkKillAtOrBelow(pw, ProcessList.FOREGROUND_APP_ADJ);
    }

    /**
     * There are three ways to call this:
     *  - no provider specified: dump all the providers
     *  - a flattened component name that matched an existing provider was specified as the
     *    first arg: dump that one provider
     *  - the first arg isn't the flattened component name of an existing provider:
     *    dump all providers whose component contains the first arg as a substring
     */
    protected boolean dumpProvider(FileDescriptor fd, PrintWriter pw, String name, String[] args,
            int opti, boolean dumpAll) {
        return mProviderMap.dumpProvider(fd, pw, name, args, opti, dumpAll);
    }

    /**
     * Similar to the dumpProvider, but only dumps the first matching provider.
     * The provider is responsible for dumping as proto.
     */
    protected boolean dumpProviderProto(FileDescriptor fd, PrintWriter pw, String name,
            String[] args) {
        return mProviderMap.dumpProviderProto(fd, pw, name, args);
    }

    public static class ItemMatcher {
        ArrayList<ComponentName> components;
        ArrayList<String> strings;
        ArrayList<Integer> objects;
        boolean all;

        public ItemMatcher() {
            all = true;
        }

        public void build(String name) {
            ComponentName componentName = ComponentName.unflattenFromString(name);
            if (componentName != null) {
                if (components == null) {
                    components = new ArrayList<ComponentName>();
                }
                components.add(componentName);
                all = false;
            } else {
                int objectId = 0;
                // Not a '/' separated full component name; maybe an object ID?
                try {
                    objectId = Integer.parseInt(name, 16);
                    if (objects == null) {
                        objects = new ArrayList<Integer>();
                    }
                    objects.add(objectId);
                    all = false;
                } catch (RuntimeException e) {
                    // Not an integer; just do string match.
                    if (strings == null) {
                        strings = new ArrayList<String>();
                    }
                    strings.add(name);
                    all = false;
                }
            }
        }

        public int build(String[] args, int opti) {
            for (; opti<args.length; opti++) {
                String name = args[opti];
                if ("--".equals(name)) {
                    return opti+1;
                }
                build(name);
            }
            return opti;
        }

        public boolean match(Object object, ComponentName comp) {
            if (all) {
                return true;
            }
            if (components != null) {
                for (int i=0; i<components.size(); i++) {
                    if (components.get(i).equals(comp)) {
                        return true;
                    }
                }
            }
            if (objects != null) {
                for (int i=0; i<objects.size(); i++) {
                    if (System.identityHashCode(object) == objects.get(i)) {
                        return true;
                    }
                }
            }
            if (strings != null) {
                String flat = comp.flattenToString();
                for (int i=0; i<strings.size(); i++) {
                    if (flat.contains(strings.get(i))) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    void writeBroadcastsToProtoLocked(ProtoOutputStream proto) {
        if (mRegisteredReceivers.size() > 0) {
            Iterator it = mRegisteredReceivers.values().iterator();
            while (it.hasNext()) {
                ReceiverList r = (ReceiverList)it.next();
                r.writeToProto(proto, ActivityManagerServiceDumpBroadcastsProto.RECEIVER_LIST);
            }
        }
        mReceiverResolver.writeToProto(proto, ActivityManagerServiceDumpBroadcastsProto.RECEIVER_RESOLVER);
        for (BroadcastQueue q : mBroadcastQueues) {
            q.writeToProto(proto, ActivityManagerServiceDumpBroadcastsProto.BROADCAST_QUEUE);
        }
        for (int user=0; user<mStickyBroadcasts.size(); user++) {
            long token = proto.start(ActivityManagerServiceDumpBroadcastsProto.STICKY_BROADCASTS);
            proto.write(StickyBroadcastProto.USER, mStickyBroadcasts.keyAt(user));
            for (Map.Entry<String, ArrayList<Intent>> ent
                    : mStickyBroadcasts.valueAt(user).entrySet()) {
                long actionToken = proto.start(StickyBroadcastProto.ACTIONS);
                proto.write(StickyBroadcastProto.StickyAction.NAME, ent.getKey());
                for (Intent intent : ent.getValue()) {
                    intent.writeToProto(proto, StickyBroadcastProto.StickyAction.INTENTS,
                            false, true, true, false);
                }
                proto.end(actionToken);
            }
            proto.end(token);
        }

        long handlerToken = proto.start(ActivityManagerServiceDumpBroadcastsProto.HANDLER);
        proto.write(ActivityManagerServiceDumpBroadcastsProto.MainHandler.HANDLER, mHandler.toString());
        mHandler.getLooper().writeToProto(proto,
            ActivityManagerServiceDumpBroadcastsProto.MainHandler.LOOPER);
        proto.end(handlerToken);
    }

    void dumpAllowedAssociationsLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage) {
        boolean needSep = false;

        pw.println("ACTIVITY MANAGER ALLOWED ASSOCIATION STATE (dumpsys activity allowed-associations)");
        boolean printed = false;
        if (mAllowedAssociations != null) {
            for (int i = 0; i < mAllowedAssociations.size(); i++) {
                final String pkg = mAllowedAssociations.keyAt(i);
                final ArraySet<String> asc =
                        mAllowedAssociations.valueAt(i).getAllowedPackageAssociations();
                boolean printedHeader = false;
                for (int j = 0; j < asc.size(); j++) {
                    if (dumpPackage == null || pkg.equals(dumpPackage)
                            || asc.valueAt(j).equals(dumpPackage)) {
                        if (!printed) {
                            pw.println("  Allowed associations (by restricted package):");
                            printed = true;
                            needSep = true;
                        }
                        if (!printedHeader) {
                            pw.print("  * ");
                            pw.print(pkg);
                            pw.println(":");
                            printedHeader = true;
                        }
                        pw.print("      Allow: ");
                        pw.println(asc.valueAt(j));
                    }
                }
                if (mAllowedAssociations.valueAt(i).isDebuggable()) {
                    pw.println("      (debuggable)");
                }
            }
        }
        if (!printed) {
            pw.println("  (No association restrictions)");
        }
    }

    void dumpBroadcastsLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage) {
        boolean needSep = false;
        boolean onlyHistory = false;
        boolean printedAnything = false;

        if ("history".equals(dumpPackage)) {
            if (opti < args.length && "-s".equals(args[opti])) {
                dumpAll = false;
            }
            onlyHistory = true;
            dumpPackage = null;
        }

        pw.println("ACTIVITY MANAGER BROADCAST STATE (dumpsys activity broadcasts)");
        if (!onlyHistory && dumpAll) {
            if (mRegisteredReceivers.size() > 0) {
                boolean printed = false;
                Iterator it = mRegisteredReceivers.values().iterator();
                while (it.hasNext()) {
                    ReceiverList r = (ReceiverList)it.next();
                    if (dumpPackage != null && (r.app == null ||
                            !dumpPackage.equals(r.app.info.packageName))) {
                        continue;
                    }
                    if (!printed) {
                        pw.println("  Registered Receivers:");
                        needSep = true;
                        printed = true;
                        printedAnything = true;
                    }
                    pw.print("  * "); pw.println(r);
                    r.dump(pw, "    ");
                }
            }

            if (mReceiverResolver.dump(pw, needSep ?
                    "\n  Receiver Resolver Table:" : "  Receiver Resolver Table:",
                    "    ", dumpPackage, false, false)) {
                needSep = true;
                printedAnything = true;
            }
        }

        for (BroadcastQueue q : mBroadcastQueues) {
            needSep = q.dumpLocked(fd, pw, args, opti, dumpAll, dumpPackage, needSep);
            printedAnything |= needSep;
        }

        needSep = true;

        if (!onlyHistory && mStickyBroadcasts != null && dumpPackage == null) {
            for (int user=0; user<mStickyBroadcasts.size(); user++) {
                if (needSep) {
                    pw.println();
                }
                needSep = true;
                printedAnything = true;
                pw.print("  Sticky broadcasts for user ");
                        pw.print(mStickyBroadcasts.keyAt(user)); pw.println(":");
                StringBuilder sb = new StringBuilder(128);
                for (Map.Entry<String, ArrayList<Intent>> ent
                        : mStickyBroadcasts.valueAt(user).entrySet()) {
                    pw.print("  * Sticky action "); pw.print(ent.getKey());
                    if (dumpAll) {
                        pw.println(":");
                        ArrayList<Intent> intents = ent.getValue();
                        final int N = intents.size();
                        for (int i=0; i<N; i++) {
                            sb.setLength(0);
                            sb.append("    Intent: ");
                            intents.get(i).toShortString(sb, false, true, false, false);
                            pw.println(sb.toString());
                            Bundle bundle = intents.get(i).getExtras();
                            if (bundle != null) {
                                pw.print("      ");
                                pw.println(bundle.toString());
                            }
                        }
                    } else {
                        pw.println("");
                    }
                }
            }
        }

        if (!onlyHistory && dumpAll) {
            pw.println();
            for (BroadcastQueue queue : mBroadcastQueues) {
                pw.println("  mBroadcastsScheduled [" + queue.mQueueName + "]="
                        + queue.mBroadcastsScheduled);
            }
            pw.println("  mHandler:");
            mHandler.dump(new PrintWriterPrinter(pw), "    ");
            needSep = true;
            printedAnything = true;
        }

        if (!printedAnything) {
            pw.println("  (nothing)");
        }
    }

    void dumpBroadcastStatsLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage) {
        if (mCurBroadcastStats == null) {
            return;
        }

        pw.println("ACTIVITY MANAGER BROADCAST STATS STATE (dumpsys activity broadcast-stats)");
        final long now = SystemClock.elapsedRealtime();
        if (mLastBroadcastStats != null) {
            pw.print("  Last stats (from ");
            TimeUtils.formatDuration(mLastBroadcastStats.mStartRealtime, now, pw);
            pw.print(" to ");
            TimeUtils.formatDuration(mLastBroadcastStats.mEndRealtime, now, pw);
            pw.print(", ");
            TimeUtils.formatDuration(mLastBroadcastStats.mEndUptime
                    - mLastBroadcastStats.mStartUptime, pw);
            pw.println(" uptime):");
            if (!mLastBroadcastStats.dumpStats(pw, "    ", dumpPackage)) {
                pw.println("    (nothing)");
            }
            pw.println();
        }
        pw.print("  Current stats (from ");
        TimeUtils.formatDuration(mCurBroadcastStats.mStartRealtime, now, pw);
        pw.print(" to now, ");
        TimeUtils.formatDuration(SystemClock.uptimeMillis()
                - mCurBroadcastStats.mStartUptime, pw);
        pw.println(" uptime):");
        if (!mCurBroadcastStats.dumpStats(pw, "    ", dumpPackage)) {
            pw.println("    (nothing)");
        }
    }

    void dumpBroadcastStatsCheckinLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean fullCheckin, String dumpPackage) {
        if (mCurBroadcastStats == null) {
            return;
        }

        if (mLastBroadcastStats != null) {
            mLastBroadcastStats.dumpCheckinStats(pw, dumpPackage);
            if (fullCheckin) {
                mLastBroadcastStats = null;
                return;
            }
        }
        mCurBroadcastStats.dumpCheckinStats(pw, dumpPackage);
        if (fullCheckin) {
            mCurBroadcastStats = null;
        }
    }

    void dumpProvidersLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage) {
        boolean needSep;
        boolean printedAnything = false;

        ItemMatcher matcher = new ItemMatcher();
        matcher.build(args, opti);

        pw.println("ACTIVITY MANAGER CONTENT PROVIDERS (dumpsys activity providers)");

        needSep = mProviderMap.dumpProvidersLocked(pw, dumpAll, dumpPackage);
        printedAnything |= needSep;

        if (mLaunchingProviders.size() > 0) {
            boolean printed = false;
            for (int i=mLaunchingProviders.size()-1; i>=0; i--) {
                ContentProviderRecord r = mLaunchingProviders.get(i);
                if (dumpPackage != null && !dumpPackage.equals(r.name.getPackageName())) {
                    continue;
                }
                if (!printed) {
                    if (needSep) pw.println();
                    needSep = true;
                    pw.println("  Launching content providers:");
                    printed = true;
                    printedAnything = true;
                }
                pw.print("  Launching #"); pw.print(i); pw.print(": ");
                        pw.println(r);
            }
        }

        if (!printedAnything) {
            pw.println("  (nothing)");
        }
    }

    @GuardedBy("this")
    void dumpPermissionsLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage) {

        pw.println("ACTIVITY MANAGER URI PERMISSIONS (dumpsys activity permissions)");

        mUgmInternal.dump(pw, dumpAll, dumpPackage);
    }

    private static final int dumpProcessList(PrintWriter pw,
            ActivityManagerService service, List list,
            String prefix, String normalLabel, String persistentLabel,
            String dumpPackage) {
        int numPers = 0;
        final int N = list.size()-1;
        for (int i=N; i>=0; i--) {
            ProcessRecord r = (ProcessRecord)list.get(i);
            if (dumpPackage != null && !dumpPackage.equals(r.info.packageName)) {
                continue;
            }
            pw.println(String.format("%s%s #%2d: %s",
                    prefix, (r.isPersistent() ? persistentLabel : normalLabel),
                    i, r.toString()));
            if (r.isPersistent()) {
                numPers++;
            }
        }
        return numPers;
    }

    private static final ArrayList<Pair<ProcessRecord, Integer>>
        sortProcessOomList(List<ProcessRecord> origList, String dumpPackage) {
        ArrayList<Pair<ProcessRecord, Integer>> list
                = new ArrayList<Pair<ProcessRecord, Integer>>(origList.size());
        for (int i=0; i<origList.size(); i++) {
            ProcessRecord r = origList.get(i);
            if (dumpPackage != null && !r.pkgList.containsKey(dumpPackage)) {
                continue;
            }
            list.add(new Pair<ProcessRecord, Integer>(origList.get(i), i));
        }

        Comparator<Pair<ProcessRecord, Integer>> comparator
                = new Comparator<Pair<ProcessRecord, Integer>>() {
            @Override
            public int compare(Pair<ProcessRecord, Integer> object1,
                    Pair<ProcessRecord, Integer> object2) {
                if (object1.first.setAdj != object2.first.setAdj) {
                    return object1.first.setAdj > object2.first.setAdj ? -1 : 1;
                }
                if (object1.first.setProcState != object2.first.setProcState) {
                    return object1.first.setProcState > object2.first.setProcState ? -1 : 1;
                }
                if (object1.second.intValue() != object2.second.intValue()) {
                    return object1.second.intValue() > object2.second.intValue() ? -1 : 1;
                }
                return 0;
            }
        };

        Collections.sort(list, comparator);
        return list;
    }

    private static final boolean writeProcessOomListToProto(ProtoOutputStream proto, long fieldId,
            ActivityManagerService service, List<ProcessRecord> origList,
            boolean inclDetails, String dumpPackage) {
        ArrayList<Pair<ProcessRecord, Integer>> list = sortProcessOomList(origList, dumpPackage);
        if (list.isEmpty()) return false;

        final long curUptime = SystemClock.uptimeMillis();

        for (int i = list.size() - 1; i >= 0; i--) {
            ProcessRecord r = list.get(i).first;
            long token = proto.start(fieldId);
            String oomAdj = ProcessList.makeOomAdjString(r.setAdj, true);
            proto.write(ProcessOomProto.PERSISTENT, r.isPersistent());
            proto.write(ProcessOomProto.NUM, (origList.size()-1)-list.get(i).second);
            proto.write(ProcessOomProto.OOM_ADJ, oomAdj);
            int schedGroup = ProcessOomProto.SCHED_GROUP_UNKNOWN;
            switch (r.setSchedGroup) {
                case ProcessList.SCHED_GROUP_BACKGROUND:
                    schedGroup = ProcessOomProto.SCHED_GROUP_BACKGROUND;
                    break;
                case ProcessList.SCHED_GROUP_DEFAULT:
                    schedGroup = ProcessOomProto.SCHED_GROUP_DEFAULT;
                    break;
                case ProcessList.SCHED_GROUP_TOP_APP:
                    schedGroup = ProcessOomProto.SCHED_GROUP_TOP_APP;
                    break;
                case ProcessList.SCHED_GROUP_TOP_APP_BOUND:
                    schedGroup = ProcessOomProto.SCHED_GROUP_TOP_APP_BOUND;
                    break;
            }
            if (schedGroup != ProcessOomProto.SCHED_GROUP_UNKNOWN) {
                proto.write(ProcessOomProto.SCHED_GROUP, schedGroup);
            }
            if (r.hasForegroundActivities()) {
                proto.write(ProcessOomProto.ACTIVITIES, true);
            } else if (r.hasForegroundServices()) {
                proto.write(ProcessOomProto.SERVICES, true);
            }
            proto.write(ProcessOomProto.STATE,
                    ProcessList.makeProcStateProtoEnum(r.getCurProcState()));
            proto.write(ProcessOomProto.TRIM_MEMORY_LEVEL, r.trimMemoryLevel);
            r.writeToProto(proto, ProcessOomProto.PROC);
            proto.write(ProcessOomProto.ADJ_TYPE, r.adjType);
            if (r.adjSource != null || r.adjTarget != null) {
                if (r.adjTarget instanceof  ComponentName) {
                    ComponentName cn = (ComponentName) r.adjTarget;
                    cn.writeToProto(proto, ProcessOomProto.ADJ_TARGET_COMPONENT_NAME);
                } else if (r.adjTarget != null) {
                    proto.write(ProcessOomProto.ADJ_TARGET_OBJECT, r.adjTarget.toString());
                }
                if (r.adjSource instanceof ProcessRecord) {
                    ProcessRecord p = (ProcessRecord) r.adjSource;
                    p.writeToProto(proto, ProcessOomProto.ADJ_SOURCE_PROC);
                } else if (r.adjSource != null) {
                    proto.write(ProcessOomProto.ADJ_SOURCE_OBJECT, r.adjSource.toString());
                }
            }
            if (inclDetails) {
                long detailToken = proto.start(ProcessOomProto.DETAIL);
                proto.write(ProcessOomProto.Detail.MAX_ADJ, r.maxAdj);
                proto.write(ProcessOomProto.Detail.CUR_RAW_ADJ, r.getCurRawAdj());
                proto.write(ProcessOomProto.Detail.SET_RAW_ADJ, r.setRawAdj);
                proto.write(ProcessOomProto.Detail.CUR_ADJ, r.curAdj);
                proto.write(ProcessOomProto.Detail.SET_ADJ, r.setAdj);
                proto.write(ProcessOomProto.Detail.CURRENT_STATE,
                        ProcessList.makeProcStateProtoEnum(r.getCurProcState()));
                proto.write(ProcessOomProto.Detail.SET_STATE,
                        ProcessList.makeProcStateProtoEnum(r.setProcState));
                proto.write(ProcessOomProto.Detail.LAST_PSS, DebugUtils.sizeValueToString(
                        r.lastPss*1024, new StringBuilder()));
                proto.write(ProcessOomProto.Detail.LAST_SWAP_PSS, DebugUtils.sizeValueToString(
                        r.lastSwapPss*1024, new StringBuilder()));
                proto.write(ProcessOomProto.Detail.LAST_CACHED_PSS, DebugUtils.sizeValueToString(
                        r.lastCachedPss*1024, new StringBuilder()));
                proto.write(ProcessOomProto.Detail.CACHED, r.cached);
                proto.write(ProcessOomProto.Detail.EMPTY, r.empty);
                proto.write(ProcessOomProto.Detail.HAS_ABOVE_CLIENT, r.hasAboveClient);

                if (r.setProcState >= ActivityManager.PROCESS_STATE_SERVICE) {
                    if (r.lastCpuTime != 0) {
                        long uptimeSince = curUptime - service.mLastPowerCheckUptime;
                        long timeUsed = r.curCpuTime - r.lastCpuTime;
                        long cpuTimeToken = proto.start(ProcessOomProto.Detail.SERVICE_RUN_TIME);
                        proto.write(ProcessOomProto.Detail.CpuRunTime.OVER_MS, uptimeSince);
                        proto.write(ProcessOomProto.Detail.CpuRunTime.USED_MS, timeUsed);
                        proto.write(ProcessOomProto.Detail.CpuRunTime.ULTILIZATION,
                                (100.0*timeUsed)/uptimeSince);
                        proto.end(cpuTimeToken);
                    }
                }
                proto.end(detailToken);
            }
            proto.end(token);
        }

        return true;
    }

    private static final boolean dumpProcessOomList(PrintWriter pw,
            ActivityManagerService service, List<ProcessRecord> origList,
            String prefix, String normalLabel, String persistentLabel,
            boolean inclDetails, String dumpPackage) {

        ArrayList<Pair<ProcessRecord, Integer>> list = sortProcessOomList(origList, dumpPackage);
        if (list.isEmpty()) return false;

        final long curUptime = SystemClock.uptimeMillis();
        final long uptimeSince = curUptime - service.mLastPowerCheckUptime;

        for (int i=list.size()-1; i>=0; i--) {
            ProcessRecord r = list.get(i).first;
            String oomAdj = ProcessList.makeOomAdjString(r.setAdj, false);
            char schedGroup;
            switch (r.setSchedGroup) {
                case ProcessList.SCHED_GROUP_BACKGROUND:
                    schedGroup = 'B';
                    break;
                case ProcessList.SCHED_GROUP_DEFAULT:
                    schedGroup = 'F';
                    break;
                case ProcessList.SCHED_GROUP_TOP_APP:
                    schedGroup = 'T';
                    break;
                case ProcessList.SCHED_GROUP_RESTRICTED:
                    schedGroup = 'R';
                    break;
                default:
                    schedGroup = '?';
                    break;
            }
            char foreground;
            if (r.hasForegroundActivities()) {
                foreground = 'A';
            } else if (r.hasForegroundServices()) {
                foreground = 'S';
            } else {
                foreground = ' ';
            }
            String procState = ProcessList.makeProcStateString(r.getCurProcState());
            pw.print(prefix);
            pw.print(r.isPersistent() ? persistentLabel : normalLabel);
            pw.print(" #");
            int num = (origList.size()-1)-list.get(i).second;
            if (num < 10) pw.print(' ');
            pw.print(num);
            pw.print(": ");
            pw.print(oomAdj);
            pw.print(' ');
            pw.print(schedGroup);
            pw.print('/');
            pw.print(foreground);
            pw.print('/');
            pw.print(procState);
            pw.print(" trm:");
            if (r.trimMemoryLevel < 10) pw.print(' ');
            pw.print(r.trimMemoryLevel);
            pw.print(' ');
            pw.print(r.toShortString());
            pw.print(" (");
            pw.print(r.adjType);
            pw.println(')');
            if (r.adjSource != null || r.adjTarget != null) {
                pw.print(prefix);
                pw.print("    ");
                if (r.adjTarget instanceof ComponentName) {
                    pw.print(((ComponentName)r.adjTarget).flattenToShortString());
                } else if (r.adjTarget != null) {
                    pw.print(r.adjTarget.toString());
                } else {
                    pw.print("{null}");
                }
                pw.print("<=");
                if (r.adjSource instanceof ProcessRecord) {
                    pw.print("Proc{");
                    pw.print(((ProcessRecord)r.adjSource).toShortString());
                    pw.println("}");
                } else if (r.adjSource != null) {
                    pw.println(r.adjSource.toString());
                } else {
                    pw.println("{null}");
                }
            }
            if (inclDetails) {
                pw.print(prefix);
                pw.print("    ");
                pw.print("oom: max="); pw.print(r.maxAdj);
                pw.print(" curRaw="); pw.print(r.getCurRawAdj());
                pw.print(" setRaw="); pw.print(r.setRawAdj);
                pw.print(" cur="); pw.print(r.curAdj);
                pw.print(" set="); pw.println(r.setAdj);
                pw.print(prefix);
                pw.print("    ");
                pw.print("state: cur="); pw.print(
                        ProcessList.makeProcStateString(r.getCurProcState()));
                pw.print(" set="); pw.print(ProcessList.makeProcStateString(r.setProcState));
                pw.print(" lastPss="); DebugUtils.printSizeValue(pw, r.lastPss*1024);
                pw.print(" lastSwapPss="); DebugUtils.printSizeValue(pw, r.lastSwapPss*1024);
                pw.print(" lastCachedPss="); DebugUtils.printSizeValue(pw, r.lastCachedPss*1024);
                pw.println();
                pw.print(prefix);
                pw.print("    ");
                pw.print("cached="); pw.print(r.cached);
                pw.print(" empty="); pw.print(r.empty);
                pw.print(" hasAboveClient="); pw.println(r.hasAboveClient);

                if (r.setProcState >= ActivityManager.PROCESS_STATE_SERVICE) {
                    if (r.lastCpuTime != 0) {
                        long timeUsed = r.curCpuTime - r.lastCpuTime;
                        pw.print(prefix);
                        pw.print("    ");
                        pw.print("run cpu over ");
                        TimeUtils.formatDuration(uptimeSince, pw);
                        pw.print(" used ");
                        TimeUtils.formatDuration(timeUsed, pw);
                        pw.print(" (");
                        pw.print((timeUsed*100)/uptimeSince);
                        pw.println("%)");
                    }
                }
            }
        }
        return true;
    }

    ArrayList<ProcessRecord> collectProcesses(PrintWriter pw, int start, boolean allPkgs,
            String[] args) {
        synchronized (this) {
            return mProcessList.collectProcessesLocked(start, allPkgs, args);
        }
    }

    final void dumpGraphicsHardwareUsage(FileDescriptor fd,
            PrintWriter pw, String[] args) {
        ArrayList<ProcessRecord> procs = collectProcesses(pw, 0, false, args);
        if (procs == null) {
            pw.println("No process found for: " + args[0]);
            return;
        }

        long uptime = SystemClock.uptimeMillis();
        long realtime = SystemClock.elapsedRealtime();
        pw.println("Applications Graphics Acceleration Info:");
        pw.println("Uptime: " + uptime + " Realtime: " + realtime);

        for (int i = procs.size() - 1 ; i >= 0 ; i--) {
            ProcessRecord r = procs.get(i);
            if (r.thread != null) {
                pw.println("\n** Graphics info for pid " + r.pid + " [" + r.processName + "] **");
                pw.flush();
                try {
                    TransferPipe tp = new TransferPipe();
                    try {
                        r.thread.dumpGfxInfo(tp.getWriteFd(), args);
                        tp.go(fd);
                    } finally {
                        tp.kill();
                    }
                } catch (IOException e) {
                    pw.println("Failure while dumping the app: " + r);
                    pw.flush();
                } catch (RemoteException e) {
                    pw.println("Got a RemoteException while dumping the app " + r);
                    pw.flush();
                }
            }
        }
    }

    final void dumpDbInfo(FileDescriptor fd, PrintWriter pw, String[] args) {
        ArrayList<ProcessRecord> procs = collectProcesses(pw, 0, false, args);
        if (procs == null) {
            pw.println("No process found for: " + args[0]);
            return;
        }

        pw.println("Applications Database Info:");

        for (int i = procs.size() - 1 ; i >= 0 ; i--) {
            ProcessRecord r = procs.get(i);
            if (r.thread != null) {
                pw.println("\n** Database info for pid " + r.pid + " [" + r.processName + "] **");
                pw.flush();
                try {
                    TransferPipe tp = new TransferPipe();
                    try {
                        r.thread.dumpDbInfo(tp.getWriteFd(), args);
                        tp.go(fd);
                    } finally {
                        tp.kill();
                    }
                } catch (IOException e) {
                    pw.println("Failure while dumping the app: " + r);
                    pw.flush();
                } catch (RemoteException e) {
                    pw.println("Got a RemoteException while dumping the app " + r);
                    pw.flush();
                }
            }
        }
    }

    final static class MemItem {
        final boolean isProc;
        final String label;
        final String shortLabel;
        final long pss;
        final long swapPss;
        final int id;
        final boolean hasActivities;
        ArrayList<MemItem> subitems;

        public MemItem(String _label, String _shortLabel, long _pss, long _swapPss, int _id,
                boolean _hasActivities) {
            isProc = true;
            label = _label;
            shortLabel = _shortLabel;
            pss = _pss;
            swapPss = _swapPss;
            id = _id;
            hasActivities = _hasActivities;
        }

        public MemItem(String _label, String _shortLabel, long _pss, long _swapPss, int _id) {
            isProc = false;
            label = _label;
            shortLabel = _shortLabel;
            pss = _pss;
            swapPss = _swapPss;
            id = _id;
            hasActivities = false;
        }
    }

    private static void sortMemItems(List<MemItem> items) {
        Collections.sort(items, new Comparator<MemItem>() {
            @Override
            public int compare(MemItem lhs, MemItem rhs) {
                if (lhs.pss < rhs.pss) {
                    return 1;
                } else if (lhs.pss > rhs.pss) {
                    return -1;
                }
                return 0;
            }
        });
    }

    static final void dumpMemItems(PrintWriter pw, String prefix, String tag,
            ArrayList<MemItem> items, boolean sort, boolean isCompact, boolean dumpSwapPss) {
        if (sort && !isCompact) {
            sortMemItems(items);
        }

        for (int i=0; i<items.size(); i++) {
            MemItem mi = items.get(i);
            if (!isCompact) {
                if (dumpSwapPss) {
                    pw.printf("%s%s: %-60s (%s in swap)\n", prefix, stringifyKBSize(mi.pss),
                            mi.label, stringifyKBSize(mi.swapPss));
                } else {
                    pw.printf("%s%s: %s\n", prefix, stringifyKBSize(mi.pss), mi.label);
                }
            } else if (mi.isProc) {
                pw.print("proc,"); pw.print(tag); pw.print(","); pw.print(mi.shortLabel);
                pw.print(","); pw.print(mi.id); pw.print(","); pw.print(mi.pss); pw.print(",");
                pw.print(dumpSwapPss ? mi.swapPss : "N/A");
                pw.println(mi.hasActivities ? ",a" : ",e");
            } else {
                pw.print(tag); pw.print(","); pw.print(mi.shortLabel); pw.print(",");
                pw.print(mi.pss); pw.print(","); pw.println(dumpSwapPss ? mi.swapPss : "N/A");
            }
            if (mi.subitems != null) {
                dumpMemItems(pw, prefix + "    ", mi.shortLabel, mi.subitems,
                        true, isCompact, dumpSwapPss);
            }
        }
    }

    static final void dumpMemItems(ProtoOutputStream proto, long fieldId, String tag,
            ArrayList<MemItem> items, boolean sort, boolean dumpSwapPss) {
        if (sort) {
            sortMemItems(items);
        }

        for (int i=0; i<items.size(); i++) {
            MemItem mi = items.get(i);
            final long token = proto.start(fieldId);

            proto.write(MemInfoDumpProto.MemItem.TAG, tag);
            proto.write(MemInfoDumpProto.MemItem.LABEL, mi.shortLabel);
            proto.write(MemInfoDumpProto.MemItem.IS_PROC, mi.isProc);
            proto.write(MemInfoDumpProto.MemItem.ID, mi.id);
            proto.write(MemInfoDumpProto.MemItem.HAS_ACTIVITIES, mi.hasActivities);
            proto.write(MemInfoDumpProto.MemItem.PSS_KB, mi.pss);
            if (dumpSwapPss) {
                proto.write(MemInfoDumpProto.MemItem.SWAP_PSS_KB, mi.swapPss);
            }
            if (mi.subitems != null) {
                dumpMemItems(proto, MemInfoDumpProto.MemItem.SUB_ITEMS, mi.shortLabel, mi.subitems,
                        true, dumpSwapPss);
            }
            proto.end(token);
        }
    }

    // These are in KB.
    static final long[] DUMP_MEM_BUCKETS = new long[] {
        5*1024, 7*1024, 10*1024, 15*1024, 20*1024, 30*1024, 40*1024, 80*1024,
        120*1024, 160*1024, 200*1024,
        250*1024, 300*1024, 350*1024, 400*1024, 500*1024, 600*1024, 800*1024,
        1*1024*1024, 2*1024*1024, 5*1024*1024, 10*1024*1024, 20*1024*1024
    };

    static final void appendMemBucket(StringBuilder out, long memKB, String label,
            boolean stackLike) {
        int start = label.lastIndexOf('.');
        if (start >= 0) start++;
        else start = 0;
        int end = label.length();
        for (int i=0; i<DUMP_MEM_BUCKETS.length; i++) {
            if (DUMP_MEM_BUCKETS[i] >= memKB) {
                long bucket = DUMP_MEM_BUCKETS[i]/1024;
                out.append(bucket);
                out.append(stackLike ? "MB." : "MB ");
                out.append(label, start, end);
                return;
            }
        }
        out.append(memKB/1024);
        out.append(stackLike ? "MB." : "MB ");
        out.append(label, start, end);
    }

    static final int[] DUMP_MEM_OOM_ADJ = new int[] {
            ProcessList.NATIVE_ADJ,
            ProcessList.SYSTEM_ADJ, ProcessList.PERSISTENT_PROC_ADJ,
            ProcessList.PERSISTENT_SERVICE_ADJ, ProcessList.FOREGROUND_APP_ADJ,
            ProcessList.VISIBLE_APP_ADJ,
            ProcessList.PERCEPTIBLE_APP_ADJ, ProcessList.PERCEPTIBLE_LOW_APP_ADJ,
            ProcessList.BACKUP_APP_ADJ, ProcessList.HEAVY_WEIGHT_APP_ADJ,
            ProcessList.SERVICE_ADJ, ProcessList.HOME_APP_ADJ,
            ProcessList.PREVIOUS_APP_ADJ, ProcessList.SERVICE_B_ADJ, ProcessList.CACHED_APP_MIN_ADJ
    };
    static final String[] DUMP_MEM_OOM_LABEL = new String[] {
            "Native",
            "System", "Persistent", "Persistent Service", "Foreground",
            "Visible", "Perceptible", "Perceptible Low",
            "Heavy Weight", "Backup",
            "A Services", "Home",
            "Previous", "B Services", "Cached"
    };
    static final String[] DUMP_MEM_OOM_COMPACT_LABEL = new String[] {
            "native",
            "sys", "pers", "persvc", "fore",
            "vis", "percept", "perceptl",
            "heavy", "backup",
            "servicea", "home",
            "prev", "serviceb", "cached"
    };

    private final void dumpApplicationMemoryUsageHeader(PrintWriter pw, long uptime,
            long realtime, boolean isCheckinRequest, boolean isCompact) {
        if (isCompact) {
            pw.print("version,"); pw.println(MEMINFO_COMPACT_VERSION);
        }
        if (isCheckinRequest || isCompact) {
            // short checkin version
            pw.print("time,"); pw.print(uptime); pw.print(","); pw.println(realtime);
        } else {
            pw.println("Applications Memory Usage (in Kilobytes):");
            pw.println("Uptime: " + uptime + " Realtime: " + realtime);
        }
    }

    private static final int KSM_SHARED = 0;
    private static final int KSM_SHARING = 1;
    private static final int KSM_UNSHARED = 2;
    private static final int KSM_VOLATILE = 3;

    private final long[] getKsmInfo() {
        long[] longOut = new long[4];
        final int[] SINGLE_LONG_FORMAT = new int[] {
            PROC_SPACE_TERM| PROC_OUT_LONG
        };
        long[] longTmp = new long[1];
        readProcFile("/sys/kernel/mm/ksm/pages_shared",
                SINGLE_LONG_FORMAT, null, longTmp, null);
        longOut[KSM_SHARED] = longTmp[0] * ProcessList.PAGE_SIZE / 1024;
        longTmp[0] = 0;
        readProcFile("/sys/kernel/mm/ksm/pages_sharing",
                SINGLE_LONG_FORMAT, null, longTmp, null);
        longOut[KSM_SHARING] = longTmp[0] * ProcessList.PAGE_SIZE / 1024;
        longTmp[0] = 0;
        readProcFile("/sys/kernel/mm/ksm/pages_unshared",
                SINGLE_LONG_FORMAT, null, longTmp, null);
        longOut[KSM_UNSHARED] = longTmp[0] * ProcessList.PAGE_SIZE / 1024;
        longTmp[0] = 0;
        readProcFile("/sys/kernel/mm/ksm/pages_volatile",
                SINGLE_LONG_FORMAT, null, longTmp, null);
        longOut[KSM_VOLATILE] = longTmp[0] * ProcessList.PAGE_SIZE / 1024;
        return longOut;
    }

    private static String stringifySize(long size, int order) {
        Locale locale = Locale.US;
        switch (order) {
            case 1:
                return String.format(locale, "%,13d", size);
            case 1024:
                return String.format(locale, "%,9dK", size / 1024);
            case 1024 * 1024:
                return String.format(locale, "%,5dM", size / 1024 / 1024);
            case 1024 * 1024 * 1024:
                return String.format(locale, "%,1dG", size / 1024 / 1024 / 1024);
            default:
                throw new IllegalArgumentException("Invalid size order");
        }
    }

    private static String stringifyKBSize(long size) {
        return stringifySize(size * 1024, 1024);
    }

    // Update this version number if you change the 'compact' format.
    private static final int MEMINFO_COMPACT_VERSION = 1;

    private static class MemoryUsageDumpOptions {
        boolean dumpDetails;
        boolean dumpFullDetails;
        boolean dumpDalvik;
        boolean dumpSummaryOnly;
        boolean dumpUnreachable;
        boolean oomOnly;
        boolean isCompact;
        boolean localOnly;
        boolean packages;
        boolean isCheckinRequest;
        boolean dumpSwapPss;
        boolean dumpProto;
    }

    final void dumpApplicationMemoryUsage(FileDescriptor fd, PrintWriter pw, String prefix,
            String[] args, boolean brief, PrintWriter categoryPw, boolean asProto) {
        MemoryUsageDumpOptions opts = new MemoryUsageDumpOptions();
        opts.dumpDetails = false;
        opts.dumpFullDetails = false;
        opts.dumpDalvik = false;
        opts.dumpSummaryOnly = false;
        opts.dumpUnreachable = false;
        opts.oomOnly = false;
        opts.isCompact = false;
        opts.localOnly = false;
        opts.packages = false;
        opts.isCheckinRequest = false;
        opts.dumpSwapPss = false;
        opts.dumpProto = asProto;

        int opti = 0;
        while (opti < args.length) {
            String opt = args[opti];
            if (opt == null || opt.length() <= 0 || opt.charAt(0) != '-') {
                break;
            }
            opti++;
            if ("-a".equals(opt)) {
                opts.dumpDetails = true;
                opts.dumpFullDetails = true;
                opts.dumpDalvik = true;
                opts.dumpSwapPss = true;
            } else if ("-d".equals(opt)) {
                opts.dumpDalvik = true;
            } else if ("-c".equals(opt)) {
                opts.isCompact = true;
            } else if ("-s".equals(opt)) {
                opts.dumpDetails = true;
                opts.dumpSummaryOnly = true;
            } else if ("-S".equals(opt)) {
                opts.dumpSwapPss = true;
            } else if ("--unreachable".equals(opt)) {
                opts.dumpUnreachable = true;
            } else if ("--oom".equals(opt)) {
                opts.oomOnly = true;
            } else if ("--local".equals(opt)) {
                opts.localOnly = true;
            } else if ("--package".equals(opt)) {
                opts.packages = true;
            } else if ("--checkin".equals(opt)) {
                opts.isCheckinRequest = true;
            } else if ("--proto".equals(opt)) {
                opts.dumpProto = true;

            } else if ("-h".equals(opt)) {
                pw.println("meminfo dump options: [-a] [-d] [-c] [-s] [--oom] [process]");
                pw.println("  -a: include all available information for each process.");
                pw.println("  -d: include dalvik details.");
                pw.println("  -c: dump in a compact machine-parseable representation.");
                pw.println("  -s: dump only summary of application memory usage.");
                pw.println("  -S: dump also SwapPss.");
                pw.println("  --oom: only show processes organized by oom adj.");
                pw.println("  --local: only collect details locally, don't call process.");
                pw.println("  --package: interpret process arg as package, dumping all");
                pw.println("             processes that have loaded that package.");
                pw.println("  --checkin: dump data for a checkin");
                pw.println("  --proto: dump data to proto");
                pw.println("If [process] is specified it can be the name or ");
                pw.println("pid of a specific process to dump.");
                return;
            } else {
                pw.println("Unknown argument: " + opt + "; use -h for help");
            }
        }

        String[] innerArgs = new String[args.length-opti];
        System.arraycopy(args, opti, innerArgs, 0, args.length-opti);

        ArrayList<ProcessRecord> procs = collectProcesses(pw, opti, opts.packages, args);
        if (opts.dumpProto) {
            dumpApplicationMemoryUsage(fd, opts, innerArgs, brief, procs);
        } else {
            dumpApplicationMemoryUsage(fd, pw, prefix, opts, innerArgs, brief, procs, categoryPw);
        }
    }

    private final void dumpApplicationMemoryUsage(FileDescriptor fd, PrintWriter pw, String prefix,
            MemoryUsageDumpOptions opts, String[] innerArgs, boolean brief,
            ArrayList<ProcessRecord> procs, PrintWriter categoryPw) {
        long uptime = SystemClock.uptimeMillis();
        long realtime = SystemClock.elapsedRealtime();
        final long[] tmpLong = new long[1];

        if (procs == null) {
            // No Java processes.  Maybe they want to print a native process.
            String proc = "N/A";
            if (innerArgs.length > 0) {
                proc = innerArgs[0];
                if (proc.charAt(0) != '-') {
                    ArrayList<ProcessCpuTracker.Stats> nativeProcs
                            = new ArrayList<ProcessCpuTracker.Stats>();
                    updateCpuStatsNow();
                    int findPid = -1;
                    try {
                        findPid = Integer.parseInt(innerArgs[0]);
                    } catch (NumberFormatException e) {
                    }
                    synchronized (mProcessCpuTracker) {
                        final int N = mProcessCpuTracker.countStats();
                        for (int i=0; i<N; i++) {
                            ProcessCpuTracker.Stats st = mProcessCpuTracker.getStats(i);
                            if (st.pid == findPid || (st.baseName != null
                                    && st.baseName.equals(innerArgs[0]))) {
                                nativeProcs.add(st);
                            }
                        }
                    }
                    if (nativeProcs.size() > 0) {
                        dumpApplicationMemoryUsageHeader(pw, uptime, realtime,
                                opts.isCheckinRequest, opts.isCompact);
                        Debug.MemoryInfo mi = null;
                        for (int i = nativeProcs.size() - 1 ; i >= 0 ; i--) {
                            final ProcessCpuTracker.Stats r = nativeProcs.get(i);
                            final int pid = r.pid;
                            if (!opts.isCheckinRequest && opts.dumpDetails) {
                                pw.println("\n** MEMINFO in pid " + pid + " [" + r.baseName + "] **");
                            }
                            if (mi == null) {
                                mi = new Debug.MemoryInfo();
                            }
                            if (opts.dumpDetails || (!brief && !opts.oomOnly)) {
                                Debug.getMemoryInfo(pid, mi);
                            } else {
                                mi.dalvikPss = (int)Debug.getPss(pid, tmpLong, null);
                                mi.dalvikPrivateDirty = (int)tmpLong[0];
                            }
                            ActivityThread.dumpMemInfoTable(pw, mi, opts.isCheckinRequest,
                                    opts.dumpFullDetails, opts.dumpDalvik, opts.dumpSummaryOnly,
                                    pid, r.baseName, 0, 0, 0, 0, 0, 0);
                            if (opts.isCheckinRequest) {
                                pw.println();
                            }
                        }
                        return;
                    }
                }
            }
            pw.println("No process found for: " + proc);
            return;
        }

        if (!brief && !opts.oomOnly && (procs.size() == 1 || opts.isCheckinRequest || opts.packages)) {
            opts.dumpDetails = true;
        }

        dumpApplicationMemoryUsageHeader(pw, uptime, realtime, opts.isCheckinRequest, opts.isCompact);

        ArrayList<MemItem> procMems = new ArrayList<MemItem>();
        final SparseArray<MemItem> procMemsMap = new SparseArray<MemItem>();
        long nativePss = 0;
        long nativeSwapPss = 0;
        long dalvikPss = 0;
        long dalvikSwapPss = 0;
        long[] dalvikSubitemPss = opts.dumpDalvik ? new long[Debug.MemoryInfo.NUM_DVK_STATS] :
                EmptyArray.LONG;
        long[] dalvikSubitemSwapPss = opts.dumpDalvik ? new long[Debug.MemoryInfo.NUM_DVK_STATS] :
                EmptyArray.LONG;
        long otherPss = 0;
        long otherSwapPss = 0;
        long[] miscPss = new long[Debug.MemoryInfo.NUM_OTHER_STATS];
        long[] miscSwapPss = new long[Debug.MemoryInfo.NUM_OTHER_STATS];

        long oomPss[] = new long[DUMP_MEM_OOM_LABEL.length];
        long oomSwapPss[] = new long[DUMP_MEM_OOM_LABEL.length];
        ArrayList<MemItem>[] oomProcs = (ArrayList<MemItem>[])
                new ArrayList[DUMP_MEM_OOM_LABEL.length];

        long totalPss = 0;
        long totalSwapPss = 0;
        long cachedPss = 0;
        long cachedSwapPss = 0;
        boolean hasSwapPss = false;

        Debug.MemoryInfo mi = null;
        for (int i = procs.size() - 1 ; i >= 0 ; i--) {
            final ProcessRecord r = procs.get(i);
            final IApplicationThread thread;
            final int pid;
            final int oomAdj;
            final boolean hasActivities;
            synchronized (this) {
                thread = r.thread;
                pid = r.pid;
                oomAdj = r.getSetAdjWithServices();
                hasActivities = r.hasActivities();
            }
            if (thread != null) {
                if (!opts.isCheckinRequest && opts.dumpDetails) {
                    pw.println("\n** MEMINFO in pid " + pid + " [" + r.processName + "] **");
                }
                if (mi == null) {
                    mi = new Debug.MemoryInfo();
                }
                final int reportType;
                final long startTime;
                final long endTime;
                if (opts.dumpDetails || (!brief && !opts.oomOnly)) {
                    reportType = ProcessStats.ADD_PSS_EXTERNAL_SLOW;
                    startTime = SystemClock.currentThreadTimeMillis();
                    Debug.getMemoryInfo(pid, mi);
                    endTime = SystemClock.currentThreadTimeMillis();
                    hasSwapPss = mi.hasSwappedOutPss;
                } else {
                    reportType = ProcessStats.ADD_PSS_EXTERNAL;
                    startTime = SystemClock.currentThreadTimeMillis();
                    mi.dalvikPss = (int)Debug.getPss(pid, tmpLong, null);
                    endTime = SystemClock.currentThreadTimeMillis();
                    mi.dalvikPrivateDirty = (int)tmpLong[0];
                }
                if (opts.dumpDetails) {
                    if (opts.localOnly) {
                        ActivityThread.dumpMemInfoTable(pw, mi, opts.isCheckinRequest, opts.dumpFullDetails,
                                opts.dumpDalvik, opts.dumpSummaryOnly, pid, r.processName, 0, 0, 0, 0, 0, 0);
                        if (opts.isCheckinRequest) {
                            pw.println();
                        }
                    } else {
                        pw.flush();
                        try {
                            TransferPipe tp = new TransferPipe();
                            try {
                                thread.dumpMemInfo(tp.getWriteFd(),
                                        mi, opts.isCheckinRequest, opts.dumpFullDetails,
                                        opts.dumpDalvik, opts.dumpSummaryOnly, opts.dumpUnreachable, innerArgs);
                                tp.go(fd, opts.dumpUnreachable ? 30000 : 5000);
                            } finally {
                                tp.kill();
                            }
                        } catch (IOException e) {
                            if (!opts.isCheckinRequest) {
                                pw.println("Got IoException! " + e);
                                pw.flush();
                            }
                        } catch (RemoteException e) {
                            if (!opts.isCheckinRequest) {
                                pw.println("Got RemoteException! " + e);
                                pw.flush();
                            }
                        }
                    }
                }

                final long myTotalPss = mi.getTotalPss();
                final long myTotalUss = mi.getTotalUss();
                final long myTotalRss = mi.getTotalRss();
                final long myTotalSwapPss = mi.getTotalSwappedOutPss();

                synchronized (this) {
                    if (r.thread != null && oomAdj == r.getSetAdjWithServices()) {
                        // Record this for posterity if the process has been stable.
                        r.baseProcessTracker.addPss(myTotalPss, myTotalUss, myTotalRss, true,
                                reportType, endTime-startTime, r.pkgList.mPkgList);
                        for (int ipkg = r.pkgList.size() - 1; ipkg >= 0; ipkg--) {
                            ProcessStats.ProcessStateHolder holder = r.pkgList.valueAt(ipkg);
                            StatsLog.write(StatsLog.PROCESS_MEMORY_STAT_REPORTED,
                                    r.info.uid,
                                    holder.state.getName(),
                                    holder.state.getPackage(),
                                    myTotalPss, myTotalUss, myTotalRss, reportType,
                                    endTime-startTime,
                                    holder.appVersion);
                        }
                    }
                }

                if (!opts.isCheckinRequest && mi != null) {
                    totalPss += myTotalPss;
                    totalSwapPss += myTotalSwapPss;
                    MemItem pssItem = new MemItem(r.processName + " (pid " + pid +
                            (hasActivities ? " / activities)" : ")"), r.processName, myTotalPss,
                            myTotalSwapPss, pid, hasActivities);
                    procMems.add(pssItem);
                    procMemsMap.put(pid, pssItem);

                    nativePss += mi.nativePss;
                    nativeSwapPss += mi.nativeSwappedOutPss;
                    dalvikPss += mi.dalvikPss;
                    dalvikSwapPss += mi.dalvikSwappedOutPss;
                    for (int j=0; j<dalvikSubitemPss.length; j++) {
                        dalvikSubitemPss[j] += mi.getOtherPss(Debug.MemoryInfo.NUM_OTHER_STATS + j);
                        dalvikSubitemSwapPss[j] +=
                                mi.getOtherSwappedOutPss(Debug.MemoryInfo.NUM_OTHER_STATS + j);
                    }
                    otherPss += mi.otherPss;
                    otherSwapPss += mi.otherSwappedOutPss;
                    for (int j=0; j<Debug.MemoryInfo.NUM_OTHER_STATS; j++) {
                        long mem = mi.getOtherPss(j);
                        miscPss[j] += mem;
                        otherPss -= mem;
                        mem = mi.getOtherSwappedOutPss(j);
                        miscSwapPss[j] += mem;
                        otherSwapPss -= mem;
                    }

                    if (oomAdj >= ProcessList.CACHED_APP_MIN_ADJ) {
                        cachedPss += myTotalPss;
                        cachedSwapPss += myTotalSwapPss;
                    }

                    for (int oomIndex=0; oomIndex<oomPss.length; oomIndex++) {
                        if (oomIndex == (oomPss.length - 1)
                                || (oomAdj >= DUMP_MEM_OOM_ADJ[oomIndex]
                                        && oomAdj < DUMP_MEM_OOM_ADJ[oomIndex + 1])) {
                            oomPss[oomIndex] += myTotalPss;
                            oomSwapPss[oomIndex] += myTotalSwapPss;
                            if (oomProcs[oomIndex] == null) {
                                oomProcs[oomIndex] = new ArrayList<MemItem>();
                            }
                            oomProcs[oomIndex].add(pssItem);
                            break;
                        }
                    }
                }
            }
        }

        long nativeProcTotalPss = 0;

        if (!opts.isCheckinRequest && procs.size() > 1 && !opts.packages) {
            // If we are showing aggregations, also look for native processes to
            // include so that our aggregations are more accurate.
            updateCpuStatsNow();
            mi = null;
            synchronized (mProcessCpuTracker) {
                final int N = mProcessCpuTracker.countStats();
                for (int i=0; i<N; i++) {
                    ProcessCpuTracker.Stats st = mProcessCpuTracker.getStats(i);
                    if (st.vsize > 0 && procMemsMap.indexOfKey(st.pid) < 0) {
                        if (mi == null) {
                            mi = new Debug.MemoryInfo();
                        }
                        if (!brief && !opts.oomOnly) {
                            Debug.getMemoryInfo(st.pid, mi);
                        } else {
                            mi.nativePss = (int)Debug.getPss(st.pid, tmpLong, null);
                            mi.nativePrivateDirty = (int)tmpLong[0];
                        }

                        final long myTotalPss = mi.getTotalPss();
                        final long myTotalSwapPss = mi.getTotalSwappedOutPss();
                        totalPss += myTotalPss;
                        totalSwapPss += myTotalSwapPss;
                        nativeProcTotalPss += myTotalPss;

                        MemItem pssItem = new MemItem(st.name + " (pid " + st.pid + ")",
                                st.name, myTotalPss, mi.getSummaryTotalSwapPss(), st.pid, false);
                        procMems.add(pssItem);

                        nativePss += mi.nativePss;
                        nativeSwapPss += mi.nativeSwappedOutPss;
                        dalvikPss += mi.dalvikPss;
                        dalvikSwapPss += mi.dalvikSwappedOutPss;
                        for (int j=0; j<dalvikSubitemPss.length; j++) {
                            dalvikSubitemPss[j] += mi.getOtherPss(Debug.MemoryInfo.NUM_OTHER_STATS + j);
                            dalvikSubitemSwapPss[j] +=
                                    mi.getOtherSwappedOutPss(Debug.MemoryInfo.NUM_OTHER_STATS + j);
                        }
                        otherPss += mi.otherPss;
                        otherSwapPss += mi.otherSwappedOutPss;
                        for (int j=0; j<Debug.MemoryInfo.NUM_OTHER_STATS; j++) {
                            long mem = mi.getOtherPss(j);
                            miscPss[j] += mem;
                            otherPss -= mem;
                            mem = mi.getOtherSwappedOutPss(j);
                            miscSwapPss[j] += mem;
                            otherSwapPss -= mem;
                        }
                        oomPss[0] += myTotalPss;
                        oomSwapPss[0] += myTotalSwapPss;
                        if (oomProcs[0] == null) {
                            oomProcs[0] = new ArrayList<MemItem>();
                        }
                        oomProcs[0].add(pssItem);
                    }
                }
            }

            ArrayList<MemItem> catMems = new ArrayList<MemItem>();

            catMems.add(new MemItem("Native", "Native", nativePss, nativeSwapPss, -1));
            final int dalvikId = -2;
            catMems.add(new MemItem("Dalvik", "Dalvik", dalvikPss, dalvikSwapPss, dalvikId));
            catMems.add(new MemItem("Unknown", "Unknown", otherPss, otherSwapPss, -3));
            for (int j=0; j<Debug.MemoryInfo.NUM_OTHER_STATS; j++) {
                String label = Debug.MemoryInfo.getOtherLabel(j);
                catMems.add(new MemItem(label, label, miscPss[j], miscSwapPss[j], j));
            }
            if (dalvikSubitemPss.length > 0) {
                // Add dalvik subitems.
                for (MemItem memItem : catMems) {
                    int memItemStart = 0, memItemEnd = 0;
                    if (memItem.id == dalvikId) {
                        memItemStart = Debug.MemoryInfo.OTHER_DVK_STAT_DALVIK_START;
                        memItemEnd = Debug.MemoryInfo.OTHER_DVK_STAT_DALVIK_END;
                    } else if (memItem.id == Debug.MemoryInfo.OTHER_DALVIK_OTHER) {
                        memItemStart = Debug.MemoryInfo.OTHER_DVK_STAT_DALVIK_OTHER_START;
                        memItemEnd = Debug.MemoryInfo.OTHER_DVK_STAT_DALVIK_OTHER_END;
                    } else if (memItem.id == Debug.MemoryInfo.OTHER_DEX) {
                        memItemStart = Debug.MemoryInfo.OTHER_DVK_STAT_DEX_START;
                        memItemEnd = Debug.MemoryInfo.OTHER_DVK_STAT_DEX_END;
                    } else if (memItem.id == Debug.MemoryInfo.OTHER_ART) {
                        memItemStart = Debug.MemoryInfo.OTHER_DVK_STAT_ART_START;
                        memItemEnd = Debug.MemoryInfo.OTHER_DVK_STAT_ART_END;
                    } else {
                        continue;  // No subitems, continue.
                    }
                    memItem.subitems = new ArrayList<MemItem>();
                    for (int j=memItemStart; j<=memItemEnd; j++) {
                        final String name = Debug.MemoryInfo.getOtherLabel(
                                Debug.MemoryInfo.NUM_OTHER_STATS + j);
                        memItem.subitems.add(new MemItem(name, name, dalvikSubitemPss[j],
                                dalvikSubitemSwapPss[j], j));
                    }
                }
            }

            ArrayList<MemItem> oomMems = new ArrayList<MemItem>();
            for (int j=0; j<oomPss.length; j++) {
                if (oomPss[j] != 0) {
                    String label = opts.isCompact ? DUMP_MEM_OOM_COMPACT_LABEL[j]
                            : DUMP_MEM_OOM_LABEL[j];
                    MemItem item = new MemItem(label, label, oomPss[j], oomSwapPss[j],
                            DUMP_MEM_OOM_ADJ[j]);
                    item.subitems = oomProcs[j];
                    oomMems.add(item);
                }
            }

            opts.dumpSwapPss = opts.dumpSwapPss && hasSwapPss && totalSwapPss != 0;
            if (!brief && !opts.oomOnly && !opts.isCompact) {
                pw.println();
                pw.println("Total PSS by process:");
                dumpMemItems(pw, "  ", "proc", procMems, true, opts.isCompact, opts.dumpSwapPss);
                pw.println();
            }
            if (!opts.isCompact) {
                pw.println("Total PSS by OOM adjustment:");
            }
            dumpMemItems(pw, "  ", "oom", oomMems, false, opts.isCompact, opts.dumpSwapPss);
            if (!brief && !opts.oomOnly) {
                PrintWriter out = categoryPw != null ? categoryPw : pw;
                if (!opts.isCompact) {
                    out.println();
                    out.println("Total PSS by category:");
                }
                dumpMemItems(out, "  ", "cat", catMems, true, opts.isCompact, opts.dumpSwapPss);
            }
            if (!opts.isCompact) {
                pw.println();
            }
            MemInfoReader memInfo = new MemInfoReader();
            memInfo.readMemInfo();
            if (nativeProcTotalPss > 0) {
                synchronized (this) {
                    final long cachedKb = memInfo.getCachedSizeKb();
                    final long freeKb = memInfo.getFreeSizeKb();
                    final long zramKb = memInfo.getZramTotalSizeKb();
                    final long kernelKb = memInfo.getKernelUsedSizeKb();
                    EventLogTags.writeAmMeminfo(cachedKb*1024, freeKb*1024, zramKb*1024,
                            kernelKb*1024, nativeProcTotalPss*1024);
                    mProcessStats.addSysMemUsageLocked(cachedKb, freeKb, zramKb, kernelKb,
                            nativeProcTotalPss);
                }
            }
            if (!brief) {
                if (!opts.isCompact) {
                    pw.print("Total RAM: "); pw.print(stringifyKBSize(memInfo.getTotalSizeKb()));
                    pw.print(" (status ");
                    switch (mLastMemoryLevel) {
                        case ProcessStats.ADJ_MEM_FACTOR_NORMAL:
                            pw.println("normal)");
                            break;
                        case ProcessStats.ADJ_MEM_FACTOR_MODERATE:
                            pw.println("moderate)");
                            break;
                        case ProcessStats.ADJ_MEM_FACTOR_LOW:
                            pw.println("low)");
                            break;
                        case ProcessStats.ADJ_MEM_FACTOR_CRITICAL:
                            pw.println("critical)");
                            break;
                        default:
                            pw.print(mLastMemoryLevel);
                            pw.println(")");
                            break;
                    }
                    pw.print(" Free RAM: ");
                    pw.print(stringifyKBSize(cachedPss + memInfo.getCachedSizeKb()
                            + memInfo.getFreeSizeKb()));
                    pw.print(" (");
                    pw.print(stringifyKBSize(cachedPss));
                    pw.print(" cached pss + ");
                    pw.print(stringifyKBSize(memInfo.getCachedSizeKb()));
                    pw.print(" cached kernel + ");
                    pw.print(stringifyKBSize(memInfo.getFreeSizeKb()));
                    pw.println(" free)");
                } else {
                    pw.print("ram,"); pw.print(memInfo.getTotalSizeKb()); pw.print(",");
                    pw.print(cachedPss + memInfo.getCachedSizeKb()
                            + memInfo.getFreeSizeKb()); pw.print(",");
                    pw.println(totalPss - cachedPss);
                }
            }
            long lostRAM = memInfo.getTotalSizeKb() - (totalPss - totalSwapPss)
                    - memInfo.getFreeSizeKb() - memInfo.getCachedSizeKb()
                    - memInfo.getKernelUsedSizeKb() - memInfo.getZramTotalSizeKb();
            if (!opts.isCompact) {
                pw.print(" Used RAM: "); pw.print(stringifyKBSize(totalPss - cachedPss
                        + memInfo.getKernelUsedSizeKb())); pw.print(" (");
                pw.print(stringifyKBSize(totalPss - cachedPss)); pw.print(" used pss + ");
                pw.print(stringifyKBSize(memInfo.getKernelUsedSizeKb())); pw.print(" kernel)\n");
                pw.print(" Lost RAM: "); pw.println(stringifyKBSize(lostRAM));
            } else {
                pw.print("lostram,"); pw.println(lostRAM);
            }
            if (!brief) {
                if (memInfo.getZramTotalSizeKb() != 0) {
                    if (!opts.isCompact) {
                        pw.print("     ZRAM: ");
                        pw.print(stringifyKBSize(memInfo.getZramTotalSizeKb()));
                                pw.print(" physical used for ");
                                pw.print(stringifyKBSize(memInfo.getSwapTotalSizeKb()
                                        - memInfo.getSwapFreeSizeKb()));
                                pw.print(" in swap (");
                                pw.print(stringifyKBSize(memInfo.getSwapTotalSizeKb()));
                                pw.println(" total swap)");
                    } else {
                        pw.print("zram,"); pw.print(memInfo.getZramTotalSizeKb()); pw.print(",");
                                pw.print(memInfo.getSwapTotalSizeKb()); pw.print(",");
                                pw.println(memInfo.getSwapFreeSizeKb());
                    }
                }
                final long[] ksm = getKsmInfo();
                if (!opts.isCompact) {
                    if (ksm[KSM_SHARING] != 0 || ksm[KSM_SHARED] != 0 || ksm[KSM_UNSHARED] != 0
                            || ksm[KSM_VOLATILE] != 0) {
                        pw.print("      KSM: "); pw.print(stringifyKBSize(ksm[KSM_SHARING]));
                                pw.print(" saved from shared ");
                                pw.print(stringifyKBSize(ksm[KSM_SHARED]));
                        pw.print("           "); pw.print(stringifyKBSize(ksm[KSM_UNSHARED]));
                                pw.print(" unshared; ");
                                pw.print(stringifyKBSize(
                                             ksm[KSM_VOLATILE])); pw.println(" volatile");
                    }
                    pw.print("   Tuning: ");
                    pw.print(ActivityManager.staticGetMemoryClass());
                    pw.print(" (large ");
                    pw.print(ActivityManager.staticGetLargeMemoryClass());
                    pw.print("), oom ");
                    pw.print(stringifySize(
                                mProcessList.getMemLevel(ProcessList.CACHED_APP_MAX_ADJ), 1024));
                    pw.print(", restore limit ");
                    pw.print(stringifyKBSize(mProcessList.getCachedRestoreThresholdKb()));
                    if (ActivityManager.isLowRamDeviceStatic()) {
                        pw.print(" (low-ram)");
                    }
                    if (ActivityManager.isHighEndGfx()) {
                        pw.print(" (high-end-gfx)");
                    }
                    pw.println();
                } else {
                    pw.print("ksm,"); pw.print(ksm[KSM_SHARING]); pw.print(",");
                    pw.print(ksm[KSM_SHARED]); pw.print(","); pw.print(ksm[KSM_UNSHARED]);
                    pw.print(","); pw.println(ksm[KSM_VOLATILE]);
                    pw.print("tuning,");
                    pw.print(ActivityManager.staticGetMemoryClass());
                    pw.print(',');
                    pw.print(ActivityManager.staticGetLargeMemoryClass());
                    pw.print(',');
                    pw.print(mProcessList.getMemLevel(ProcessList.CACHED_APP_MAX_ADJ)/1024);
                    if (ActivityManager.isLowRamDeviceStatic()) {
                        pw.print(",low-ram");
                    }
                    if (ActivityManager.isHighEndGfx()) {
                        pw.print(",high-end-gfx");
                    }
                    pw.println();
                }
            }
        }
    }

    private final void dumpApplicationMemoryUsage(FileDescriptor fd,
            MemoryUsageDumpOptions opts, String[] innerArgs, boolean brief,
            ArrayList<ProcessRecord> procs) {
        final long uptimeMs = SystemClock.uptimeMillis();
        final long realtimeMs = SystemClock.elapsedRealtime();
        final long[] tmpLong = new long[1];

        if (procs == null) {
            // No Java processes.  Maybe they want to print a native process.
            String proc = "N/A";
            if (innerArgs.length > 0) {
                proc = innerArgs[0];
                if (proc.charAt(0) != '-') {
                    ArrayList<ProcessCpuTracker.Stats> nativeProcs
                            = new ArrayList<ProcessCpuTracker.Stats>();
                    updateCpuStatsNow();
                    int findPid = -1;
                    try {
                        findPid = Integer.parseInt(innerArgs[0]);
                    } catch (NumberFormatException e) {
                    }
                    synchronized (mProcessCpuTracker) {
                        final int N = mProcessCpuTracker.countStats();
                        for (int i=0; i<N; i++) {
                            ProcessCpuTracker.Stats st = mProcessCpuTracker.getStats(i);
                            if (st.pid == findPid || (st.baseName != null
                                    && st.baseName.equals(innerArgs[0]))) {
                                nativeProcs.add(st);
                            }
                        }
                    }
                    if (nativeProcs.size() > 0) {
                        ProtoOutputStream proto = new ProtoOutputStream(fd);

                        proto.write(MemInfoDumpProto.UPTIME_DURATION_MS, uptimeMs);
                        proto.write(MemInfoDumpProto.ELAPSED_REALTIME_MS, realtimeMs);
                        Debug.MemoryInfo mi = null;
                        for (int i = nativeProcs.size() - 1 ; i >= 0 ; i--) {
                            final ProcessCpuTracker.Stats r = nativeProcs.get(i);
                            final int pid = r.pid;
                            final long nToken = proto.start(MemInfoDumpProto.NATIVE_PROCESSES);

                            proto.write(MemInfoDumpProto.ProcessMemory.PID, pid);
                            proto.write(MemInfoDumpProto.ProcessMemory.PROCESS_NAME, r.baseName);

                            if (mi == null) {
                                mi = new Debug.MemoryInfo();
                            }
                            if (opts.dumpDetails || (!brief && !opts.oomOnly)) {
                                Debug.getMemoryInfo(pid, mi);
                            } else {
                                mi.dalvikPss = (int)Debug.getPss(pid, tmpLong, null);
                                mi.dalvikPrivateDirty = (int)tmpLong[0];
                            }
                            ActivityThread.dumpMemInfoTable(proto, mi, opts.dumpDalvik,
                                    opts.dumpSummaryOnly, 0, 0, 0, 0, 0, 0);

                            proto.end(nToken);
                        }

                        proto.flush();
                        return;
                    }
                }
            }
            Log.d(TAG, "No process found for: " + innerArgs[0]);
            return;
        }

        if (!brief && !opts.oomOnly && (procs.size() == 1 || opts.isCheckinRequest || opts.packages)) {
            opts.dumpDetails = true;
        }

        ProtoOutputStream proto = new ProtoOutputStream(fd);

        proto.write(MemInfoDumpProto.UPTIME_DURATION_MS, uptimeMs);
        proto.write(MemInfoDumpProto.ELAPSED_REALTIME_MS, realtimeMs);

        ArrayList<MemItem> procMems = new ArrayList<MemItem>();
        final SparseArray<MemItem> procMemsMap = new SparseArray<MemItem>();
        long nativePss = 0;
        long nativeSwapPss = 0;
        long dalvikPss = 0;
        long dalvikSwapPss = 0;
        long[] dalvikSubitemPss = opts.dumpDalvik ? new long[Debug.MemoryInfo.NUM_DVK_STATS] :
                EmptyArray.LONG;
        long[] dalvikSubitemSwapPss = opts.dumpDalvik ? new long[Debug.MemoryInfo.NUM_DVK_STATS] :
                EmptyArray.LONG;
        long otherPss = 0;
        long otherSwapPss = 0;
        long[] miscPss = new long[Debug.MemoryInfo.NUM_OTHER_STATS];
        long[] miscSwapPss = new long[Debug.MemoryInfo.NUM_OTHER_STATS];

        long oomPss[] = new long[DUMP_MEM_OOM_LABEL.length];
        long oomSwapPss[] = new long[DUMP_MEM_OOM_LABEL.length];
        ArrayList<MemItem>[] oomProcs = (ArrayList<MemItem>[])
                new ArrayList[DUMP_MEM_OOM_LABEL.length];

        long totalPss = 0;
        long totalSwapPss = 0;
        long cachedPss = 0;
        long cachedSwapPss = 0;
        boolean hasSwapPss = false;

        Debug.MemoryInfo mi = null;
        for (int i = procs.size() - 1 ; i >= 0 ; i--) {
            final ProcessRecord r = procs.get(i);
            final IApplicationThread thread;
            final int pid;
            final int oomAdj;
            final boolean hasActivities;
            synchronized (this) {
                thread = r.thread;
                pid = r.pid;
                oomAdj = r.getSetAdjWithServices();
                hasActivities = r.hasActivities();
            }
            if (thread == null) {
                continue;
            }
            if (mi == null) {
                mi = new Debug.MemoryInfo();
            }
            final int reportType;
            final long startTime;
            final long endTime;
            if (opts.dumpDetails || (!brief && !opts.oomOnly)) {
                reportType = ProcessStats.ADD_PSS_EXTERNAL_SLOW;
                startTime = SystemClock.currentThreadTimeMillis();
                Debug.getMemoryInfo(pid, mi);
                endTime = SystemClock.currentThreadTimeMillis();
                hasSwapPss = mi.hasSwappedOutPss;
            } else {
                reportType = ProcessStats.ADD_PSS_EXTERNAL;
                startTime = SystemClock.currentThreadTimeMillis();
                mi.dalvikPss = (int) Debug.getPss(pid, tmpLong, null);
                endTime = SystemClock.currentThreadTimeMillis();
                mi.dalvikPrivateDirty = (int) tmpLong[0];
            }
            if (opts.dumpDetails) {
                if (opts.localOnly) {
                    final long aToken = proto.start(MemInfoDumpProto.APP_PROCESSES);
                    final long mToken = proto.start(MemInfoDumpProto.AppData.PROCESS_MEMORY);
                    proto.write(MemInfoDumpProto.ProcessMemory.PID, pid);
                    proto.write(MemInfoDumpProto.ProcessMemory.PROCESS_NAME, r.processName);
                    ActivityThread.dumpMemInfoTable(proto, mi, opts.dumpDalvik,
                            opts.dumpSummaryOnly, 0, 0, 0, 0, 0, 0);
                    proto.end(mToken);
                    proto.end(aToken);
                } else {
                    try {
                        ByteTransferPipe tp = new ByteTransferPipe();
                        try {
                            thread.dumpMemInfoProto(tp.getWriteFd(),
                                mi, opts.dumpFullDetails, opts.dumpDalvik, opts.dumpSummaryOnly,
                                opts.dumpUnreachable, innerArgs);
                            proto.write(MemInfoDumpProto.APP_PROCESSES, tp.get());
                        } finally {
                            tp.kill();
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "Got IOException!", e);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Got RemoteException!", e);
                    }
                }
            }

            final long myTotalPss = mi.getTotalPss();
            final long myTotalUss = mi.getTotalUss();
            final long myTotalRss = mi.getTotalRss();
            final long myTotalSwapPss = mi.getTotalSwappedOutPss();

            synchronized (this) {
                if (r.thread != null && oomAdj == r.getSetAdjWithServices()) {
                    // Record this for posterity if the process has been stable.
                    r.baseProcessTracker.addPss(myTotalPss, myTotalUss, myTotalRss, true,
                            reportType, endTime-startTime, r.pkgList.mPkgList);
                    for (int ipkg = r.pkgList.size() - 1; ipkg >= 0; ipkg--) {
                        ProcessStats.ProcessStateHolder holder = r.pkgList.valueAt(ipkg);
                        StatsLog.write(StatsLog.PROCESS_MEMORY_STAT_REPORTED,
                                r.info.uid,
                                holder.state.getName(),
                                holder.state.getPackage(),
                                myTotalPss, myTotalUss, myTotalRss, reportType, endTime-startTime,
                                holder.appVersion);
                    }
                }
            }

            if (!opts.isCheckinRequest && mi != null) {
                totalPss += myTotalPss;
                totalSwapPss += myTotalSwapPss;
                MemItem pssItem = new MemItem(r.processName + " (pid " + pid +
                        (hasActivities ? " / activities)" : ")"), r.processName, myTotalPss,
                        myTotalSwapPss, pid, hasActivities);
                procMems.add(pssItem);
                procMemsMap.put(pid, pssItem);

                nativePss += mi.nativePss;
                nativeSwapPss += mi.nativeSwappedOutPss;
                dalvikPss += mi.dalvikPss;
                dalvikSwapPss += mi.dalvikSwappedOutPss;
                for (int j=0; j<dalvikSubitemPss.length; j++) {
                    dalvikSubitemPss[j] += mi.getOtherPss(Debug.MemoryInfo.NUM_OTHER_STATS + j);
                    dalvikSubitemSwapPss[j] +=
                            mi.getOtherSwappedOutPss(Debug.MemoryInfo.NUM_OTHER_STATS + j);
                }
                otherPss += mi.otherPss;
                otherSwapPss += mi.otherSwappedOutPss;
                for (int j=0; j<Debug.MemoryInfo.NUM_OTHER_STATS; j++) {
                    long mem = mi.getOtherPss(j);
                    miscPss[j] += mem;
                    otherPss -= mem;
                    mem = mi.getOtherSwappedOutPss(j);
                    miscSwapPss[j] += mem;
                    otherSwapPss -= mem;
                }

                if (oomAdj >= ProcessList.CACHED_APP_MIN_ADJ) {
                    cachedPss += myTotalPss;
                    cachedSwapPss += myTotalSwapPss;
                }

                for (int oomIndex=0; oomIndex<oomPss.length; oomIndex++) {
                    if (oomIndex == (oomPss.length - 1)
                            || (oomAdj >= DUMP_MEM_OOM_ADJ[oomIndex]
                                    && oomAdj < DUMP_MEM_OOM_ADJ[oomIndex + 1])) {
                        oomPss[oomIndex] += myTotalPss;
                        oomSwapPss[oomIndex] += myTotalSwapPss;
                        if (oomProcs[oomIndex] == null) {
                            oomProcs[oomIndex] = new ArrayList<MemItem>();
                        }
                        oomProcs[oomIndex].add(pssItem);
                        break;
                    }
                }
            }
        }

        long nativeProcTotalPss = 0;

        if (procs.size() > 1 && !opts.packages) {
            // If we are showing aggregations, also look for native processes to
            // include so that our aggregations are more accurate.
            updateCpuStatsNow();
            mi = null;
            synchronized (mProcessCpuTracker) {
                final int N = mProcessCpuTracker.countStats();
                for (int i=0; i<N; i++) {
                    ProcessCpuTracker.Stats st = mProcessCpuTracker.getStats(i);
                    if (st.vsize > 0 && procMemsMap.indexOfKey(st.pid) < 0) {
                        if (mi == null) {
                            mi = new Debug.MemoryInfo();
                        }
                        if (!brief && !opts.oomOnly) {
                            Debug.getMemoryInfo(st.pid, mi);
                        } else {
                            mi.nativePss = (int)Debug.getPss(st.pid, tmpLong, null);
                            mi.nativePrivateDirty = (int)tmpLong[0];
                        }

                        final long myTotalPss = mi.getTotalPss();
                        final long myTotalSwapPss = mi.getTotalSwappedOutPss();
                        totalPss += myTotalPss;
                        nativeProcTotalPss += myTotalPss;

                        MemItem pssItem = new MemItem(st.name + " (pid " + st.pid + ")",
                                st.name, myTotalPss, mi.getSummaryTotalSwapPss(), st.pid, false);
                        procMems.add(pssItem);

                        nativePss += mi.nativePss;
                        nativeSwapPss += mi.nativeSwappedOutPss;
                        dalvikPss += mi.dalvikPss;
                        dalvikSwapPss += mi.dalvikSwappedOutPss;
                        for (int j=0; j<dalvikSubitemPss.length; j++) {
                            dalvikSubitemPss[j] += mi.getOtherPss(Debug.MemoryInfo.NUM_OTHER_STATS + j);
                            dalvikSubitemSwapPss[j] +=
                                    mi.getOtherSwappedOutPss(Debug.MemoryInfo.NUM_OTHER_STATS + j);
                        }
                        otherPss += mi.otherPss;
                        otherSwapPss += mi.otherSwappedOutPss;
                        for (int j=0; j<Debug.MemoryInfo.NUM_OTHER_STATS; j++) {
                            long mem = mi.getOtherPss(j);
                            miscPss[j] += mem;
                            otherPss -= mem;
                            mem = mi.getOtherSwappedOutPss(j);
                            miscSwapPss[j] += mem;
                            otherSwapPss -= mem;
                        }
                        oomPss[0] += myTotalPss;
                        oomSwapPss[0] += myTotalSwapPss;
                        if (oomProcs[0] == null) {
                            oomProcs[0] = new ArrayList<MemItem>();
                        }
                        oomProcs[0].add(pssItem);
                    }
                }
            }

            ArrayList<MemItem> catMems = new ArrayList<MemItem>();

            catMems.add(new MemItem("Native", "Native", nativePss, nativeSwapPss, -1));
            final int dalvikId = -2;
            catMems.add(new MemItem("Dalvik", "Dalvik", dalvikPss, dalvikSwapPss, dalvikId));
            catMems.add(new MemItem("Unknown", "Unknown", otherPss, otherSwapPss, -3));
            for (int j=0; j<Debug.MemoryInfo.NUM_OTHER_STATS; j++) {
                String label = Debug.MemoryInfo.getOtherLabel(j);
                catMems.add(new MemItem(label, label, miscPss[j], miscSwapPss[j], j));
            }
            if (dalvikSubitemPss.length > 0) {
                // Add dalvik subitems.
                for (MemItem memItem : catMems) {
                    int memItemStart = 0, memItemEnd = 0;
                    if (memItem.id == dalvikId) {
                        memItemStart = Debug.MemoryInfo.OTHER_DVK_STAT_DALVIK_START;
                        memItemEnd = Debug.MemoryInfo.OTHER_DVK_STAT_DALVIK_END;
                    } else if (memItem.id == Debug.MemoryInfo.OTHER_DALVIK_OTHER) {
                        memItemStart = Debug.MemoryInfo.OTHER_DVK_STAT_DALVIK_OTHER_START;
                        memItemEnd = Debug.MemoryInfo.OTHER_DVK_STAT_DALVIK_OTHER_END;
                    } else if (memItem.id == Debug.MemoryInfo.OTHER_DEX) {
                        memItemStart = Debug.MemoryInfo.OTHER_DVK_STAT_DEX_START;
                        memItemEnd = Debug.MemoryInfo.OTHER_DVK_STAT_DEX_END;
                    } else if (memItem.id == Debug.MemoryInfo.OTHER_ART) {
                        memItemStart = Debug.MemoryInfo.OTHER_DVK_STAT_ART_START;
                        memItemEnd = Debug.MemoryInfo.OTHER_DVK_STAT_ART_END;
                    } else {
                        continue;  // No subitems, continue.
                    }
                    memItem.subitems = new ArrayList<MemItem>();
                    for (int j=memItemStart; j<=memItemEnd; j++) {
                        final String name = Debug.MemoryInfo.getOtherLabel(
                                Debug.MemoryInfo.NUM_OTHER_STATS + j);
                        memItem.subitems.add(new MemItem(name, name, dalvikSubitemPss[j],
                                dalvikSubitemSwapPss[j], j));
                    }
                }
            }

            ArrayList<MemItem> oomMems = new ArrayList<MemItem>();
            for (int j=0; j<oomPss.length; j++) {
                if (oomPss[j] != 0) {
                    String label = opts.isCompact ? DUMP_MEM_OOM_COMPACT_LABEL[j]
                            : DUMP_MEM_OOM_LABEL[j];
                    MemItem item = new MemItem(label, label, oomPss[j], oomSwapPss[j],
                            DUMP_MEM_OOM_ADJ[j]);
                    item.subitems = oomProcs[j];
                    oomMems.add(item);
                }
            }

            opts.dumpSwapPss = opts.dumpSwapPss && hasSwapPss && totalSwapPss != 0;
            if (!opts.oomOnly) {
                dumpMemItems(proto, MemInfoDumpProto.TOTAL_PSS_BY_PROCESS, "proc",
                        procMems, true, opts.dumpSwapPss);
            }
            dumpMemItems(proto, MemInfoDumpProto.TOTAL_PSS_BY_OOM_ADJUSTMENT, "oom",
                    oomMems, false, opts.dumpSwapPss);
            if (!brief && !opts.oomOnly) {
                dumpMemItems(proto, MemInfoDumpProto.TOTAL_PSS_BY_CATEGORY, "cat",
                        catMems, true, opts.dumpSwapPss);
            }
            MemInfoReader memInfo = new MemInfoReader();
            memInfo.readMemInfo();
            if (nativeProcTotalPss > 0) {
                synchronized (this) {
                    final long cachedKb = memInfo.getCachedSizeKb();
                    final long freeKb = memInfo.getFreeSizeKb();
                    final long zramKb = memInfo.getZramTotalSizeKb();
                    final long kernelKb = memInfo.getKernelUsedSizeKb();
                    EventLogTags.writeAmMeminfo(cachedKb*1024, freeKb*1024, zramKb*1024,
                            kernelKb*1024, nativeProcTotalPss*1024);
                    mProcessStats.addSysMemUsageLocked(cachedKb, freeKb, zramKb, kernelKb,
                            nativeProcTotalPss);
                }
            }
            if (!brief) {
                proto.write(MemInfoDumpProto.TOTAL_RAM_KB, memInfo.getTotalSizeKb());
                proto.write(MemInfoDumpProto.STATUS, mLastMemoryLevel);
                proto.write(MemInfoDumpProto.CACHED_PSS_KB, cachedPss);
                proto.write(MemInfoDumpProto.CACHED_KERNEL_KB, memInfo.getCachedSizeKb());
                proto.write(MemInfoDumpProto.FREE_KB, memInfo.getFreeSizeKb());
            }
            long lostRAM = memInfo.getTotalSizeKb() - (totalPss - totalSwapPss)
                    - memInfo.getFreeSizeKb() - memInfo.getCachedSizeKb()
                    - memInfo.getKernelUsedSizeKb() - memInfo.getZramTotalSizeKb();
            proto.write(MemInfoDumpProto.USED_PSS_KB, totalPss - cachedPss);
            proto.write(MemInfoDumpProto.USED_KERNEL_KB, memInfo.getKernelUsedSizeKb());
            proto.write(MemInfoDumpProto.LOST_RAM_KB, lostRAM);
            if (!brief) {
                if (memInfo.getZramTotalSizeKb() != 0) {
                    proto.write(MemInfoDumpProto.TOTAL_ZRAM_KB, memInfo.getZramTotalSizeKb());
                    proto.write(MemInfoDumpProto.ZRAM_PHYSICAL_USED_IN_SWAP_KB,
                            memInfo.getSwapTotalSizeKb() - memInfo.getSwapFreeSizeKb());
                    proto.write(MemInfoDumpProto.TOTAL_ZRAM_SWAP_KB, memInfo.getSwapTotalSizeKb());
                }
                final long[] ksm = getKsmInfo();
                proto.write(MemInfoDumpProto.KSM_SHARING_KB, ksm[KSM_SHARING]);
                proto.write(MemInfoDumpProto.KSM_SHARED_KB, ksm[KSM_SHARED]);
                proto.write(MemInfoDumpProto.KSM_UNSHARED_KB, ksm[KSM_UNSHARED]);
                proto.write(MemInfoDumpProto.KSM_VOLATILE_KB, ksm[KSM_VOLATILE]);

                proto.write(MemInfoDumpProto.TUNING_MB, ActivityManager.staticGetMemoryClass());
                proto.write(MemInfoDumpProto.TUNING_LARGE_MB, ActivityManager.staticGetLargeMemoryClass());
                proto.write(MemInfoDumpProto.OOM_KB,
                        mProcessList.getMemLevel(ProcessList.CACHED_APP_MAX_ADJ) / 1024);
                proto.write(MemInfoDumpProto.RESTORE_LIMIT_KB,
                        mProcessList.getCachedRestoreThresholdKb());

                proto.write(MemInfoDumpProto.IS_LOW_RAM_DEVICE, ActivityManager.isLowRamDeviceStatic());
                proto.write(MemInfoDumpProto.IS_HIGH_END_GFX, ActivityManager.isHighEndGfx());
            }
        }

        proto.flush();
    }

    private void appendBasicMemEntry(StringBuilder sb, int oomAdj, int procState, long pss,
            long memtrack, String name) {
        sb.append("  ");
        sb.append(ProcessList.makeOomAdjString(oomAdj, false));
        sb.append(' ');
        sb.append(ProcessList.makeProcStateString(procState));
        sb.append(' ');
        ProcessList.appendRamKb(sb, pss);
        sb.append(": ");
        sb.append(name);
        if (memtrack > 0) {
            sb.append(" (");
            sb.append(stringifyKBSize(memtrack));
            sb.append(" memtrack)");
        }
    }

    private void appendMemInfo(StringBuilder sb, ProcessMemInfo mi) {
        appendBasicMemEntry(sb, mi.oomAdj, mi.procState, mi.pss, mi.memtrack, mi.name);
        sb.append(" (pid ");
        sb.append(mi.pid);
        sb.append(") ");
        sb.append(mi.adjType);
        sb.append('\n');
        if (mi.adjReason != null) {
            sb.append("                      ");
            sb.append(mi.adjReason);
            sb.append('\n');
        }
    }

    void reportMemUsage(ArrayList<ProcessMemInfo> memInfos) {
        final SparseArray<ProcessMemInfo> infoMap = new SparseArray<>(memInfos.size());
        for (int i=0, N=memInfos.size(); i<N; i++) {
            ProcessMemInfo mi = memInfos.get(i);
            infoMap.put(mi.pid, mi);
        }
        updateCpuStatsNow();
        long[] memtrackTmp = new long[1];
        long[] swaptrackTmp = new long[2];
        final List<ProcessCpuTracker.Stats> stats;
        // Get a list of Stats that have vsize > 0
        synchronized (mProcessCpuTracker) {
            stats = mProcessCpuTracker.getStats((st) -> {
                return st.vsize > 0;
            });
        }
        final int statsCount = stats.size();
        for (int i = 0; i < statsCount; i++) {
            ProcessCpuTracker.Stats st = stats.get(i);
            long pss = Debug.getPss(st.pid, swaptrackTmp, memtrackTmp);
            if (pss > 0) {
                if (infoMap.indexOfKey(st.pid) < 0) {
                    ProcessMemInfo mi = new ProcessMemInfo(st.name, st.pid,
                            ProcessList.NATIVE_ADJ, -1, "native", null);
                    mi.pss = pss;
                    mi.swapPss = swaptrackTmp[1];
                    mi.memtrack = memtrackTmp[0];
                    memInfos.add(mi);
                }
            }
        }

        long totalPss = 0;
        long totalSwapPss = 0;
        long totalMemtrack = 0;
        for (int i=0, N=memInfos.size(); i<N; i++) {
            ProcessMemInfo mi = memInfos.get(i);
            if (mi.pss == 0) {
                mi.pss = Debug.getPss(mi.pid, swaptrackTmp, memtrackTmp);
                mi.swapPss = swaptrackTmp[1];
                mi.memtrack = memtrackTmp[0];
            }
            totalPss += mi.pss;
            totalSwapPss += mi.swapPss;
            totalMemtrack += mi.memtrack;
        }
        Collections.sort(memInfos, new Comparator<ProcessMemInfo>() {
            @Override public int compare(ProcessMemInfo lhs, ProcessMemInfo rhs) {
                if (lhs.oomAdj != rhs.oomAdj) {
                    return lhs.oomAdj < rhs.oomAdj ? -1 : 1;
                }
                if (lhs.pss != rhs.pss) {
                    return lhs.pss < rhs.pss ? 1 : -1;
                }
                return 0;
            }
        });

        StringBuilder tag = new StringBuilder(128);
        StringBuilder stack = new StringBuilder(128);
        tag.append("Low on memory -- ");
        appendMemBucket(tag, totalPss, "total", false);
        appendMemBucket(stack, totalPss, "total", true);

        StringBuilder fullNativeBuilder = new StringBuilder(1024);
        StringBuilder shortNativeBuilder = new StringBuilder(1024);
        StringBuilder fullJavaBuilder = new StringBuilder(1024);

        boolean firstLine = true;
        int lastOomAdj = Integer.MIN_VALUE;
        long extraNativeRam = 0;
        long extraNativeMemtrack = 0;
        long cachedPss = 0;
        for (int i=0, N=memInfos.size(); i<N; i++) {
            ProcessMemInfo mi = memInfos.get(i);

            if (mi.oomAdj >= ProcessList.CACHED_APP_MIN_ADJ) {
                cachedPss += mi.pss;
            }

            if (mi.oomAdj != ProcessList.NATIVE_ADJ
                    && (mi.oomAdj < ProcessList.SERVICE_ADJ
                            || mi.oomAdj == ProcessList.HOME_APP_ADJ
                            || mi.oomAdj == ProcessList.PREVIOUS_APP_ADJ)) {
                if (lastOomAdj != mi.oomAdj) {
                    lastOomAdj = mi.oomAdj;
                    if (mi.oomAdj <= ProcessList.FOREGROUND_APP_ADJ) {
                        tag.append(" / ");
                    }
                    if (mi.oomAdj >= ProcessList.FOREGROUND_APP_ADJ) {
                        if (firstLine) {
                            stack.append(":");
                            firstLine = false;
                        }
                        stack.append("\n\t at ");
                    } else {
                        stack.append("$");
                    }
                } else {
                    tag.append(" ");
                    stack.append("$");
                }
                if (mi.oomAdj <= ProcessList.FOREGROUND_APP_ADJ) {
                    appendMemBucket(tag, mi.pss, mi.name, false);
                }
                appendMemBucket(stack, mi.pss, mi.name, true);
                if (mi.oomAdj >= ProcessList.FOREGROUND_APP_ADJ
                        && ((i+1) >= N || memInfos.get(i+1).oomAdj != lastOomAdj)) {
                    stack.append("(");
                    for (int k=0; k<DUMP_MEM_OOM_ADJ.length; k++) {
                        if (DUMP_MEM_OOM_ADJ[k] == mi.oomAdj) {
                            stack.append(DUMP_MEM_OOM_LABEL[k]);
                            stack.append(":");
                            stack.append(DUMP_MEM_OOM_ADJ[k]);
                        }
                    }
                    stack.append(")");
                }
            }

            appendMemInfo(fullNativeBuilder, mi);
            if (mi.oomAdj == ProcessList.NATIVE_ADJ) {
                // The short form only has native processes that are >= 512K.
                if (mi.pss >= 512) {
                    appendMemInfo(shortNativeBuilder, mi);
                } else {
                    extraNativeRam += mi.pss;
                    extraNativeMemtrack += mi.memtrack;
                }
            } else {
                // Short form has all other details, but if we have collected RAM
                // from smaller native processes let's dump a summary of that.
                if (extraNativeRam > 0) {
                    appendBasicMemEntry(shortNativeBuilder, ProcessList.NATIVE_ADJ,
                            -1, extraNativeRam, extraNativeMemtrack, "(Other native)");
                    shortNativeBuilder.append('\n');
                    extraNativeRam = 0;
                }
                appendMemInfo(fullJavaBuilder, mi);
            }
        }

        fullJavaBuilder.append("           ");
        ProcessList.appendRamKb(fullJavaBuilder, totalPss);
        fullJavaBuilder.append(": TOTAL");
        if (totalMemtrack > 0) {
            fullJavaBuilder.append(" (");
            fullJavaBuilder.append(stringifyKBSize(totalMemtrack));
            fullJavaBuilder.append(" memtrack)");
        } else {
        }
        fullJavaBuilder.append("\n");

        MemInfoReader memInfo = new MemInfoReader();
        memInfo.readMemInfo();
        final long[] infos = memInfo.getRawInfo();

        StringBuilder memInfoBuilder = new StringBuilder(1024);
        Debug.getMemInfo(infos);
        memInfoBuilder.append("  MemInfo: ");
        memInfoBuilder.append(stringifyKBSize(infos[Debug.MEMINFO_SLAB])).append(" slab, ");
        memInfoBuilder.append(stringifyKBSize(infos[Debug.MEMINFO_SHMEM])).append(" shmem, ");
        memInfoBuilder.append(stringifyKBSize(
                                  infos[Debug.MEMINFO_VM_ALLOC_USED])).append(" vm alloc, ");
        memInfoBuilder.append(stringifyKBSize(
                                  infos[Debug.MEMINFO_PAGE_TABLES])).append(" page tables ");
        memInfoBuilder.append(stringifyKBSize(
                                  infos[Debug.MEMINFO_KERNEL_STACK])).append(" kernel stack\n");
        memInfoBuilder.append("           ");
        memInfoBuilder.append(stringifyKBSize(infos[Debug.MEMINFO_BUFFERS])).append(" buffers, ");
        memInfoBuilder.append(stringifyKBSize(infos[Debug.MEMINFO_CACHED])).append(" cached, ");
        memInfoBuilder.append(stringifyKBSize(infos[Debug.MEMINFO_MAPPED])).append(" mapped, ");
        memInfoBuilder.append(stringifyKBSize(infos[Debug.MEMINFO_FREE])).append(" free\n");
        if (infos[Debug.MEMINFO_ZRAM_TOTAL] != 0) {
            memInfoBuilder.append("  ZRAM: ");
            memInfoBuilder.append(stringifyKBSize(infos[Debug.MEMINFO_ZRAM_TOTAL]));
            memInfoBuilder.append(" RAM, ");
            memInfoBuilder.append(stringifyKBSize(infos[Debug.MEMINFO_SWAP_TOTAL]));
            memInfoBuilder.append(" swap total, ");
            memInfoBuilder.append(stringifyKBSize(infos[Debug.MEMINFO_SWAP_FREE]));
            memInfoBuilder.append(" swap free\n");
        }
        final long[] ksm = getKsmInfo();
        if (ksm[KSM_SHARING] != 0 || ksm[KSM_SHARED] != 0 || ksm[KSM_UNSHARED] != 0
                || ksm[KSM_VOLATILE] != 0) {
            memInfoBuilder.append("  KSM: ");
            memInfoBuilder.append(stringifyKBSize(ksm[KSM_SHARING]));
            memInfoBuilder.append(" saved from shared ");
            memInfoBuilder.append(stringifyKBSize(ksm[KSM_SHARED]));
            memInfoBuilder.append("\n       ");
            memInfoBuilder.append(stringifyKBSize(ksm[KSM_UNSHARED]));
            memInfoBuilder.append(" unshared; ");
            memInfoBuilder.append(stringifyKBSize(ksm[KSM_VOLATILE]));
            memInfoBuilder.append(" volatile\n");
        }
        memInfoBuilder.append("  Free RAM: ");
        memInfoBuilder.append(stringifyKBSize(cachedPss + memInfo.getCachedSizeKb()
                + memInfo.getFreeSizeKb()));
        memInfoBuilder.append("\n");
        memInfoBuilder.append("  Used RAM: ");
        memInfoBuilder.append(stringifyKBSize(
                                  totalPss - cachedPss + memInfo.getKernelUsedSizeKb()));
        memInfoBuilder.append("\n");
        memInfoBuilder.append("  Lost RAM: ");
        memInfoBuilder.append(stringifyKBSize(memInfo.getTotalSizeKb()
                - (totalPss - totalSwapPss) - memInfo.getFreeSizeKb() - memInfo.getCachedSizeKb()
                - memInfo.getKernelUsedSizeKb() - memInfo.getZramTotalSizeKb()));
        memInfoBuilder.append("\n");
        Slog.i(TAG, "Low on memory:");
        Slog.i(TAG, shortNativeBuilder.toString());
        Slog.i(TAG, fullJavaBuilder.toString());
        Slog.i(TAG, memInfoBuilder.toString());

        StringBuilder dropBuilder = new StringBuilder(1024);
        /*
        StringWriter oomSw = new StringWriter();
        PrintWriter oomPw = new FastPrintWriter(oomSw, false, 256);
        StringWriter catSw = new StringWriter();
        PrintWriter catPw = new FastPrintWriter(catSw, false, 256);
        String[] emptyArgs = new String[] { };
        dumpApplicationMemoryUsage(null, oomPw, "  ", emptyArgs, true, catPw);
        oomPw.flush();
        String oomString = oomSw.toString();
        */
        dropBuilder.append("Low on memory:");
        dropBuilder.append(stack);
        dropBuilder.append('\n');
        dropBuilder.append(fullNativeBuilder);
        dropBuilder.append(fullJavaBuilder);
        dropBuilder.append('\n');
        dropBuilder.append(memInfoBuilder);
        dropBuilder.append('\n');
        /*
        dropBuilder.append(oomString);
        dropBuilder.append('\n');
        */
        StringWriter catSw = new StringWriter();
        synchronized (ActivityManagerService.this) {
            PrintWriter catPw = new FastPrintWriter(catSw, false, 256);
            String[] emptyArgs = new String[] { };
            catPw.println();
            dumpProcessesLocked(null, catPw, emptyArgs, 0, false, null, -1);
            catPw.println();
            mServices.newServiceDumperLocked(null, catPw, emptyArgs, 0,
                    false, null).dumpLocked();
            catPw.println();
            mAtmInternal.dump(DUMP_ACTIVITIES_CMD, null, catPw, emptyArgs, 0, false, false, null);
            catPw.flush();
        }
        dropBuilder.append(catSw.toString());
        StatsLog.write(StatsLog.LOW_MEM_REPORTED);
        addErrorToDropBox("lowmem", null, "system_server", null,
                null, null, tag.toString(), dropBuilder.toString(), null, null);
        //Slog.i(TAG, "Sent to dropbox:");
        //Slog.i(TAG, dropBuilder.toString());
        synchronized (ActivityManagerService.this) {
            long now = SystemClock.uptimeMillis();
            if (mLastMemUsageReportTime < now) {
                mLastMemUsageReportTime = now;
            }
        }
    }

    /**
     * Searches array of arguments for the specified string
     * @param args array of argument strings
     * @param value value to search for
     * @return true if the value is contained in the array
     */
    private static boolean scanArgs(String[] args, String value) {
        if (args != null) {
            for (String arg : args) {
                if (value.equals(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    private final boolean removeDyingProviderLocked(ProcessRecord proc,
            ContentProviderRecord cpr, boolean always) {
        final boolean inLaunching = mLaunchingProviders.contains(cpr);

        if (!inLaunching || always) {
            synchronized (cpr) {
                cpr.launchingApp = null;
                cpr.notifyAll();
            }
            mProviderMap.removeProviderByClass(cpr.name, UserHandle.getUserId(cpr.uid));
            String names[] = cpr.info.authority.split(";");
            for (int j = 0; j < names.length; j++) {
                mProviderMap.removeProviderByName(names[j], UserHandle.getUserId(cpr.uid));
            }
        }

        for (int i = cpr.connections.size() - 1; i >= 0; i--) {
            ContentProviderConnection conn = cpr.connections.get(i);
            if (conn.waiting) {
                // If this connection is waiting for the provider, then we don't
                // need to mess with its process unless we are always removing
                // or for some reason the provider is not currently launching.
                if (inLaunching && !always) {
                    continue;
                }
            }
            ProcessRecord capp = conn.client;
            conn.dead = true;
            if (conn.stableCount > 0) {
                if (!capp.isPersistent() && capp.thread != null
                        && capp.pid != 0
                        && capp.pid != MY_PID) {
                    capp.kill("depends on provider "
                            + cpr.name.flattenToShortString()
                            + " in dying proc " + (proc != null ? proc.processName : "??")
                            + " (adj " + (proc != null ? proc.setAdj : "??") + ")", true);
                }
            } else if (capp.thread != null && conn.provider.provider != null) {
                try {
                    capp.thread.unstableProviderDied(conn.provider.provider.asBinder());
                } catch (RemoteException e) {
                }
                // In the protocol here, we don't expect the client to correctly
                // clean up this connection, we'll just remove it.
                cpr.connections.remove(i);
                if (conn.client.conProviders.remove(conn)) {
                    stopAssociationLocked(capp.uid, capp.processName, cpr.uid,
                            cpr.appInfo.longVersionCode, cpr.name, cpr.info.processName);
                }
            }
        }

        if (inLaunching && always) {
            mLaunchingProviders.remove(cpr);
        }
        return inLaunching;
    }

    /**
     * Main code for cleaning up a process when it has gone away.  This is
     * called both as a result of the process dying, or directly when stopping
     * a process when running in single process mode.
     *
     * @return Returns true if the given process has been restarted, so the
     * app that was passed in must remain on the process lists.
     */
    @GuardedBy("this")
    final boolean cleanUpApplicationRecordLocked(ProcessRecord app,
            boolean restarting, boolean allowRestart, int index, boolean replacingPid) {
        if (index >= 0) {
            removeLruProcessLocked(app);
            ProcessList.remove(app.pid);
        }

        mProcessesToGc.remove(app);
        mPendingPssProcesses.remove(app);
        ProcessList.abortNextPssTime(app.procStateMemTracker);

        // Dismiss any open dialogs.
        if (app.crashDialog != null && !app.forceCrashReport) {
            app.crashDialog.dismiss();
            app.crashDialog = null;
        }
        if (app.anrDialog != null) {
            app.anrDialog.dismiss();
            app.anrDialog = null;
        }
        if (app.waitDialog != null) {
            app.waitDialog.dismiss();
            app.waitDialog = null;
        }

        app.setCrashing(false);
        app.setNotResponding(false);

        app.resetPackageList(mProcessStats);
        app.unlinkDeathRecipient();
        app.makeInactive(mProcessStats);
        app.waitingToKill = null;
        app.forcingToImportant = null;
        updateProcessForegroundLocked(app, false, 0, false);
        app.setHasForegroundActivities(false);
        app.hasShownUi = false;
        app.treatLikeActivity = false;
        app.hasAboveClient = false;
        app.setHasClientActivities(false);

        mServices.killServicesLocked(app, allowRestart);

        boolean restart = false;

        // Remove published content providers.
        for (int i = app.pubProviders.size() - 1; i >= 0; i--) {
            ContentProviderRecord cpr = app.pubProviders.valueAt(i);
            final boolean always = app.bad || !allowRestart;
            boolean inLaunching = removeDyingProviderLocked(app, cpr, always);
            if ((inLaunching || always) && cpr.hasConnectionOrHandle()) {
                // We left the provider in the launching list, need to
                // restart it.
                restart = true;
            }

            cpr.provider = null;
            cpr.setProcess(null);
        }
        app.pubProviders.clear();

        // Take care of any launching providers waiting for this process.
        if (cleanupAppInLaunchingProvidersLocked(app, false)) {
            restart = true;
        }

        // Unregister from connected content providers.
        if (!app.conProviders.isEmpty()) {
            for (int i = app.conProviders.size() - 1; i >= 0; i--) {
                ContentProviderConnection conn = app.conProviders.get(i);
                conn.provider.connections.remove(conn);
                stopAssociationLocked(app.uid, app.processName, conn.provider.uid,
                        conn.provider.appInfo.longVersionCode, conn.provider.name,
                        conn.provider.info.processName);
            }
            app.conProviders.clear();
        }

        // At this point there may be remaining entries in mLaunchingProviders
        // where we were the only one waiting, so they are no longer of use.
        // Look for these and clean up if found.
        // XXX Commented out for now.  Trying to figure out a way to reproduce
        // the actual situation to identify what is actually going on.
        if (false) {
            for (int i = mLaunchingProviders.size() - 1; i >= 0; i--) {
                ContentProviderRecord cpr = mLaunchingProviders.get(i);
                if (cpr.connections.size() <= 0 && !cpr.hasExternalProcessHandles()) {
                    synchronized (cpr) {
                        cpr.launchingApp = null;
                        cpr.notifyAll();
                    }
                }
            }
        }

        skipCurrentReceiverLocked(app);

        // Unregister any receivers.
        for (int i = app.receivers.size() - 1; i >= 0; i--) {
            removeReceiverLocked(app.receivers.valueAt(i));
        }
        app.receivers.clear();

        // If the app is undergoing backup, tell the backup manager about it
        final BackupRecord backupTarget = mBackupTargets.get(app.userId);
        if (backupTarget != null && app.pid == backupTarget.app.pid) {
            if (DEBUG_BACKUP || DEBUG_CLEANUP) Slog.d(TAG_CLEANUP, "App "
                    + backupTarget.appInfo + " died during backup");
            mHandler.post(new Runnable() {
                @Override
                public void run(){
                    try {
                        IBackupManager bm = IBackupManager.Stub.asInterface(
                                ServiceManager.getService(Context.BACKUP_SERVICE));
                        bm.agentDisconnectedForUser(app.userId, app.info.packageName);
                    } catch (RemoteException e) {
                        // can't happen; backup manager is local
                    }
                }
            });
        }

        for (int i = mPendingProcessChanges.size() - 1; i >= 0; i--) {
            ProcessChangeItem item = mPendingProcessChanges.get(i);
            if (app.pid > 0 && item.pid == app.pid) {
                mPendingProcessChanges.remove(i);
                mAvailProcessChanges.add(item);
            }
        }
        mUiHandler.obtainMessage(DISPATCH_PROCESS_DIED_UI_MSG, app.pid, app.info.uid,
                null).sendToTarget();

        // If the caller is restarting this app, then leave it in its
        // current lists and let the caller take care of it.
        if (restarting) {
            return false;
        }

        if (!app.isPersistent() || app.isolated) {
            if (DEBUG_PROCESSES || DEBUG_CLEANUP) Slog.v(TAG_CLEANUP,
                    "Removing non-persistent process during cleanup: " + app);
            if (!replacingPid) {
                mProcessList.removeProcessNameLocked(app.processName, app.uid, app);
            }
            mAtmInternal.clearHeavyWeightProcessIfEquals(app.getWindowProcessController());
        } else if (!app.removed) {
            // This app is persistent, so we need to keep its record around.
            // If it is not already on the pending app list, add it there
            // and start a new process for it.
            if (mPersistentStartingProcesses.indexOf(app) < 0) {
                mPersistentStartingProcesses.add(app);
                restart = true;
            }
        }
        if ((DEBUG_PROCESSES || DEBUG_CLEANUP) && mProcessesOnHold.contains(app)) Slog.v(
                TAG_CLEANUP, "Clean-up removing on hold: " + app);
        mProcessesOnHold.remove(app);

        mAtmInternal.onCleanUpApplicationRecord(app.getWindowProcessController());

        if (restart && !app.isolated) {
            // We have components that still need to be running in the
            // process, so re-launch it.
            if (index < 0) {
                ProcessList.remove(app.pid);
            }
            mProcessList.addProcessNameLocked(app);
            app.pendingStart = false;
            mProcessList.startProcessLocked(app, "restart", app.processName);
            return true;
        } else if (app.pid > 0 && app.pid != MY_PID) {
            // Goodbye!
            mPidsSelfLocked.remove(app.pid);
            mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);
            mBatteryStatsService.noteProcessFinish(app.processName, app.info.uid);
            if (app.isolated) {
                mBatteryStatsService.removeIsolatedUid(app.uid, app.info.uid);
            }
            app.setPid(0);
        }
        return false;
    }

    boolean checkAppInLaunchingProvidersLocked(ProcessRecord app) {
        for (int i = mLaunchingProviders.size() - 1; i >= 0; i--) {
            ContentProviderRecord cpr = mLaunchingProviders.get(i);
            if (cpr.launchingApp == app) {
                return true;
            }
        }
        return false;
    }

    boolean cleanupAppInLaunchingProvidersLocked(ProcessRecord app, boolean alwaysBad) {
        // Look through the content providers we are waiting to have launched,
        // and if any run in this process then either schedule a restart of
        // the process or kill the client waiting for it if this process has
        // gone bad.
        boolean restart = false;
        for (int i = mLaunchingProviders.size() - 1; i >= 0; i--) {
            ContentProviderRecord cpr = mLaunchingProviders.get(i);
            if (cpr.launchingApp == app) {
                if (!alwaysBad && !app.bad && cpr.hasConnectionOrHandle()) {
                    restart = true;
                } else {
                    removeDyingProviderLocked(app, cpr, true);
                }
            }
        }
        return restart;
    }

    // =========================================================
    // SERVICES
    // =========================================================

    @Override
    public List<ActivityManager.RunningServiceInfo> getServices(int maxNum, int flags) {
        enforceNotIsolatedCaller("getServices");

        final int callingUid = Binder.getCallingUid();
        final boolean canInteractAcrossUsers = (ActivityManager.checkUidPermission(
            INTERACT_ACROSS_USERS_FULL, callingUid) == PERMISSION_GRANTED);
        final boolean allowed = mAtmInternal.isGetTasksAllowed("getServices",
                Binder.getCallingPid(), callingUid);
        synchronized (this) {
            return mServices.getRunningServiceInfoLocked(maxNum, flags, callingUid,
                allowed, canInteractAcrossUsers);
        }
    }

    @Override
    public PendingIntent getRunningServiceControlPanel(ComponentName name) {
        enforceNotIsolatedCaller("getRunningServiceControlPanel");
        synchronized (this) {
            return mServices.getRunningServiceControlPanelLocked(name);
        }
    }

    @Override
    public ComponentName startService(IApplicationThread caller, Intent service,
            String resolvedType, boolean requireForeground, String callingPackage, int userId)
            throws TransactionTooLargeException {
        enforceNotIsolatedCaller("startService");
        // Refuse possible leaked file descriptors
        if (service != null && service.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        if (callingPackage == null) {
            throw new IllegalArgumentException("callingPackage cannot be null");
        }

        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE,
                "*** startService: " + service + " type=" + resolvedType + " fg=" + requireForeground);
        synchronized(this) {
            final int callingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();
            final long origId = Binder.clearCallingIdentity();
            ComponentName res;
            try {
                res = mServices.startServiceLocked(caller, service,
                        resolvedType, callingPid, callingUid,
                        requireForeground, callingPackage, userId);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
            return res;
        }
    }

    @Override
    public int stopService(IApplicationThread caller, Intent service,
            String resolvedType, int userId) {
        enforceNotIsolatedCaller("stopService");
        // Refuse possible leaked file descriptors
        if (service != null && service.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            return mServices.stopServiceLocked(caller, service, resolvedType, userId);
        }
    }

    @Override
    public IBinder peekService(Intent service, String resolvedType, String callingPackage) {
        enforceNotIsolatedCaller("peekService");
        // Refuse possible leaked file descriptors
        if (service != null && service.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        if (callingPackage == null) {
            throw new IllegalArgumentException("callingPackage cannot be null");
        }

        synchronized(this) {
            return mServices.peekServiceLocked(service, resolvedType, callingPackage);
        }
    }

    @Override
    public boolean stopServiceToken(ComponentName className, IBinder token,
            int startId) {
        synchronized(this) {
            return mServices.stopServiceTokenLocked(className, token, startId);
        }
    }

    @Override
    public void setServiceForeground(ComponentName className, IBinder token,
            int id, Notification notification, int flags, int foregroundServiceType) {
        synchronized(this) {
            mServices.setServiceForegroundLocked(className, token, id, notification, flags,
                    foregroundServiceType);
        }
    }

    @Override
    public int getForegroundServiceType(ComponentName className, IBinder token) {
        synchronized (this) {
            return mServices.getForegroundServiceTypeLocked(className, token);
        }
    }

    @Override
    public int handleIncomingUser(int callingPid, int callingUid, int userId, boolean allowAll,
            boolean requireFull, String name, String callerPackage) {
        return mUserController.handleIncomingUser(callingPid, callingUid, userId, allowAll,
                requireFull ? ALLOW_FULL_ONLY : ALLOW_NON_FULL, name, callerPackage);
    }

    boolean isSingleton(String componentProcessName, ApplicationInfo aInfo,
            String className, int flags) {
        boolean result = false;
        // For apps that don't have pre-defined UIDs, check for permission
        if (UserHandle.getAppId(aInfo.uid) >= FIRST_APPLICATION_UID) {
            if ((flags & ServiceInfo.FLAG_SINGLE_USER) != 0) {
                if (ActivityManager.checkUidPermission(
                        INTERACT_ACROSS_USERS,
                        aInfo.uid) != PackageManager.PERMISSION_GRANTED) {
                    ComponentName comp = new ComponentName(aInfo.packageName, className);
                    String msg = "Permission Denial: Component " + comp.flattenToShortString()
                            + " requests FLAG_SINGLE_USER, but app does not hold "
                            + INTERACT_ACROSS_USERS;
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                }
                // Permission passed
                result = true;
            }
        } else if ("system".equals(componentProcessName)) {
            result = true;
        } else if ((flags & ServiceInfo.FLAG_SINGLE_USER) != 0) {
            // Phone app and persistent apps are allowed to export singleuser providers.
            result = UserHandle.isSameApp(aInfo.uid, PHONE_UID)
                    || (aInfo.flags & ApplicationInfo.FLAG_PERSISTENT) != 0;
        }
        if (DEBUG_MU) Slog.v(TAG_MU,
                "isSingleton(" + componentProcessName + ", " + aInfo + ", " + className + ", 0x"
                + Integer.toHexString(flags) + ") = " + result);
        return result;
    }

    /**
     * Checks to see if the caller is in the same app as the singleton
     * component, or the component is in a special app. It allows special apps
     * to export singleton components but prevents exporting singleton
     * components for regular apps.
     */
    boolean isValidSingletonCall(int callingUid, int componentUid) {
        int componentAppId = UserHandle.getAppId(componentUid);
        return UserHandle.isSameApp(callingUid, componentUid)
                || componentAppId == SYSTEM_UID
                || componentAppId == PHONE_UID
                || ActivityManager.checkUidPermission(INTERACT_ACROSS_USERS_FULL, componentUid)
                        == PackageManager.PERMISSION_GRANTED;
    }

    public int bindService(IApplicationThread caller, IBinder token, Intent service,
            String resolvedType, IServiceConnection connection, int flags,
            String callingPackage, int userId) throws TransactionTooLargeException {
        return bindIsolatedService(caller, token, service, resolvedType, connection, flags,
                null, callingPackage, userId);
    }

    public int bindIsolatedService(IApplicationThread caller, IBinder token, Intent service,
            String resolvedType, IServiceConnection connection, int flags, String instanceName,
            String callingPackage, int userId) throws TransactionTooLargeException {
        enforceNotIsolatedCaller("bindService");

        // Refuse possible leaked file descriptors
        if (service != null && service.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        if (callingPackage == null) {
            throw new IllegalArgumentException("callingPackage cannot be null");
        }

        synchronized(this) {
            return mServices.bindServiceLocked(caller, token, service,
                    resolvedType, connection, flags, instanceName, callingPackage, userId);
        }
    }

    public void updateServiceGroup(IServiceConnection connection, int group, int importance) {
        synchronized (this) {
            mServices.updateServiceGroupLocked(connection, group, importance);
        }
    }

    public boolean unbindService(IServiceConnection connection) {
        synchronized (this) {
            return mServices.unbindServiceLocked(connection);
        }
    }

    public void publishService(IBinder token, Intent intent, IBinder service) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            if (!(token instanceof ServiceRecord)) {
                throw new IllegalArgumentException("Invalid service token");
            }
            mServices.publishServiceLocked((ServiceRecord)token, intent, service);
        }
    }

    public void unbindFinished(IBinder token, Intent intent, boolean doRebind) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            mServices.unbindFinishedLocked((ServiceRecord)token, intent, doRebind);
        }
    }

    public void serviceDoneExecuting(IBinder token, int type, int startId, int res) {
        synchronized(this) {
            if (!(token instanceof ServiceRecord)) {
                Slog.e(TAG, "serviceDoneExecuting: Invalid service token=" + token);
                throw new IllegalArgumentException("Invalid service token");
            }
            mServices.serviceDoneExecutingLocked((ServiceRecord)token, type, startId, res);
        }
    }

    // =========================================================
    // BACKUP AND RESTORE
    // =========================================================

    // Cause the target app to be launched if necessary and its backup agent
    // instantiated.  The backup agent will invoke backupAgentCreated() on the
    // activity manager to announce its creation.
    public boolean bindBackupAgent(String packageName, int backupMode, int targetUserId) {
        if (DEBUG_BACKUP) {
            Slog.v(TAG, "bindBackupAgent: app=" + packageName + " mode=" + backupMode
                    + " targetUserId=" + targetUserId + " callingUid = " + Binder.getCallingUid()
                    + " uid = " + Process.myUid());
        }
        enforceCallingPermission("android.permission.CONFIRM_FULL_BACKUP", "bindBackupAgent");

        // The instantiatedUserId is the user of the process the backup agent is started in. This is
        // different from the targetUserId which is the user whose data is to be backed up or
        // restored. This distinction is important for system-process packages that live in the
        // system user's process but backup/restore data for non-system users.
        // TODO (b/123688746): Handle all system-process packages with singleton check.
        final int instantiatedUserId =
                PLATFORM_PACKAGE_NAME.equals(packageName) ? UserHandle.USER_SYSTEM : targetUserId;

        IPackageManager pm = AppGlobals.getPackageManager();
        ApplicationInfo app = null;
        try {
            app = pm.getApplicationInfo(packageName, STOCK_PM_FLAGS, instantiatedUserId);
        } catch (RemoteException e) {
            // can't happen; package manager is process-local
        }
        if (app == null) {
            Slog.w(TAG, "Unable to bind backup agent for " + packageName);
            return false;
        }

        int oldBackupUid;
        int newBackupUid;

        synchronized(this) {
            // !!! TODO: currently no check here that we're already bound
            // Backup agent is now in use, its package can't be stopped.
            try {
                AppGlobals.getPackageManager().setPackageStoppedState(
                        app.packageName, false, UserHandle.getUserId(app.uid));
            } catch (RemoteException e) {
            } catch (IllegalArgumentException e) {
                Slog.w(TAG, "Failed trying to unstop package "
                        + app.packageName + ": " + e);
            }

            BackupRecord r = new BackupRecord(app, backupMode, targetUserId);
            ComponentName hostingName =
                    (backupMode == ApplicationThreadConstants.BACKUP_MODE_INCREMENTAL)
                            ? new ComponentName(app.packageName, app.backupAgentName)
                            : new ComponentName("android", "FullBackupAgent");
            // startProcessLocked() returns existing proc's record if it's already running
            ProcessRecord proc = startProcessLocked(app.processName, app,
                    false, 0, "backup", hostingName, false, false, false);
            if (proc == null) {
                Slog.e(TAG, "Unable to start backup agent process " + r);
                return false;
            }

            // If the app is a regular app (uid >= 10000) and not the system server or phone
            // process, etc, then mark it as being in full backup so that certain calls to the
            // process can be blocked. This is not reset to false anywhere because we kill the
            // process after the full backup is done and the ProcessRecord will vaporize anyway.
            if (UserHandle.isApp(app.uid) &&
                    backupMode == ApplicationThreadConstants.BACKUP_MODE_FULL) {
                proc.inFullBackup = true;
            }
            r.app = proc;
            final BackupRecord backupTarget = mBackupTargets.get(targetUserId);
            oldBackupUid = backupTarget != null ? backupTarget.appInfo.uid : -1;
            newBackupUid = proc.inFullBackup ? r.appInfo.uid : -1;
            mBackupTargets.put(targetUserId, r);

            // Try not to kill the process during backup
            updateOomAdjLocked(proc, true);

            // If the process is already attached, schedule the creation of the backup agent now.
            // If it is not yet live, this will be done when it attaches to the framework.
            if (proc.thread != null) {
                if (DEBUG_BACKUP) Slog.v(TAG_BACKUP, "Agent proc already running: " + proc);
                try {
                    proc.thread.scheduleCreateBackupAgent(app,
                            compatibilityInfoForPackage(app), backupMode, targetUserId);
                } catch (RemoteException e) {
                    // Will time out on the backup manager side
                }
            } else {
                if (DEBUG_BACKUP) Slog.v(TAG_BACKUP, "Agent proc not running, waiting for attach");
            }
            // Invariants: at this point, the target app process exists and the application
            // is either already running or in the process of coming up.  mBackupTarget and
            // mBackupAppName describe the app, so that when it binds back to the AM we
            // know that it's scheduled for a backup-agent operation.
        }

        JobSchedulerInternal js = LocalServices.getService(JobSchedulerInternal.class);
        if (oldBackupUid != -1) {
            js.removeBackingUpUid(oldBackupUid);
        }
        if (newBackupUid != -1) {
            js.addBackingUpUid(newBackupUid);
        }

        return true;
    }

    private void clearPendingBackup(int userId) {
        if (DEBUG_BACKUP) {
            Slog.v(TAG_BACKUP, "clearPendingBackup: userId = " + userId + " callingUid = "
                    + Binder.getCallingUid() + " uid = " + Process.myUid());
        }

        synchronized (this) {
            mBackupTargets.delete(userId);
        }

        JobSchedulerInternal js = LocalServices.getService(JobSchedulerInternal.class);
        js.clearAllBackingUpUids();
    }

    // A backup agent has just come up
    @Override
    public void backupAgentCreated(String agentPackageName, IBinder agent, int userId) {
        // Resolve the target user id and enforce permissions.
        userId = mUserController.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, /* allowAll */ false, ALLOW_FULL_ONLY, "backupAgentCreated", null);
        if (DEBUG_BACKUP) {
            Slog.v(TAG_BACKUP, "backupAgentCreated: " + agentPackageName + " = " + agent
                    + " callingUserId = " + UserHandle.getCallingUserId() + " userId = " + userId
                    + " callingUid = " + Binder.getCallingUid() + " uid = " + Process.myUid());
        }

        synchronized(this) {
            final BackupRecord backupTarget = mBackupTargets.get(userId);
            String backupAppName = backupTarget == null ? null : backupTarget.appInfo.packageName;
            if (!agentPackageName.equals(backupAppName)) {
                Slog.e(TAG, "Backup agent created for " + agentPackageName + " but not requested!");
                return;
            }
        }

        long oldIdent = Binder.clearCallingIdentity();
        try {
            IBackupManager bm = IBackupManager.Stub.asInterface(
                    ServiceManager.getService(Context.BACKUP_SERVICE));
            bm.agentConnectedForUser(userId, agentPackageName, agent);
        } catch (RemoteException e) {
            // can't happen; the backup manager service is local
        } catch (Exception e) {
            Slog.w(TAG, "Exception trying to deliver BackupAgent binding: ");
            e.printStackTrace();
        } finally {
            Binder.restoreCallingIdentity(oldIdent);
        }
    }

    // done with this agent
    public void unbindBackupAgent(ApplicationInfo appInfo) {
        if (DEBUG_BACKUP) {
            Slog.v(TAG_BACKUP, "unbindBackupAgent: " + appInfo + " appInfo.uid = "
                    + appInfo.uid + " callingUid = " + Binder.getCallingUid() + " uid = "
                    + Process.myUid());
        }

        enforceCallingPermission("android.permission.CONFIRM_FULL_BACKUP", "unbindBackupAgent");
        if (appInfo == null) {
            Slog.w(TAG, "unbind backup agent for null app");
            return;
        }

        int oldBackupUid;

        final int userId = UserHandle.getUserId(appInfo.uid);
        synchronized(this) {
            final BackupRecord backupTarget = mBackupTargets.get(userId);
            String backupAppName = backupTarget == null ? null : backupTarget.appInfo.packageName;
            try {
                if (backupAppName == null) {
                    Slog.w(TAG, "Unbinding backup agent with no active backup");
                    return;
                }

                if (!backupAppName.equals(appInfo.packageName)) {
                    Slog.e(TAG, "Unbind of " + appInfo + " but is not the current backup target");
                    return;
                }

                // Not backing this app up any more; reset its OOM adjustment
                final ProcessRecord proc = backupTarget.app;
                updateOomAdjLocked(proc, true);
                proc.inFullBackup = false;

                oldBackupUid = backupTarget != null ? backupTarget.appInfo.uid : -1;

                // If the app crashed during backup, 'thread' will be null here
                if (proc.thread != null) {
                    try {
                        proc.thread.scheduleDestroyBackupAgent(appInfo,
                                compatibilityInfoForPackage(appInfo), userId);
                    } catch (Exception e) {
                        Slog.e(TAG, "Exception when unbinding backup agent:");
                        e.printStackTrace();
                    }
                }
            } finally {
                mBackupTargets.delete(userId);
            }
        }

        if (oldBackupUid != -1) {
            JobSchedulerInternal js = LocalServices.getService(JobSchedulerInternal.class);
            js.removeBackingUpUid(oldBackupUid);
        }
    }

    // =========================================================
    // BROADCASTS
    // =========================================================

    private boolean isInstantApp(ProcessRecord record, @Nullable String callerPackage, int uid) {
        if (UserHandle.getAppId(uid) < FIRST_APPLICATION_UID) {
            return false;
        }
        // Easy case -- we have the app's ProcessRecord.
        if (record != null) {
            return record.info.isInstantApp();
        }
        // Otherwise check with PackageManager.
        IPackageManager pm = AppGlobals.getPackageManager();
        try {
            if (callerPackage == null) {
                final String[] packageNames = pm.getPackagesForUid(uid);
                if (packageNames == null || packageNames.length == 0) {
                    throw new IllegalArgumentException("Unable to determine caller package name");
                }
                // Instant Apps can't use shared uids, so its safe to only check the first package.
                callerPackage = packageNames[0];
            }
            mAppOpsService.checkPackage(uid, callerPackage);
            return pm.isInstantApp(callerPackage, UserHandle.getUserId(uid));
        } catch (RemoteException e) {
            Slog.e(TAG, "Error looking up if " + callerPackage + " is an instant app.", e);
            return true;
        }
    }

    boolean isPendingBroadcastProcessLocked(int pid) {
        return mFgBroadcastQueue.isPendingBroadcastProcessLocked(pid)
                || mBgBroadcastQueue.isPendingBroadcastProcessLocked(pid)
                || mOffloadBroadcastQueue.isPendingBroadcastProcessLocked(pid);
    }

    void skipPendingBroadcastLocked(int pid) {
            Slog.w(TAG, "Unattached app died before broadcast acknowledged, skipping");
            for (BroadcastQueue queue : mBroadcastQueues) {
                queue.skipPendingBroadcastLocked(pid);
            }
    }

    // The app just attached; send any pending broadcasts that it should receive
    boolean sendPendingBroadcastsLocked(ProcessRecord app) {
        boolean didSomething = false;
        for (BroadcastQueue queue : mBroadcastQueues) {
            didSomething |= queue.sendPendingBroadcastsLocked(app);
        }
        return didSomething;
    }

    public Intent registerReceiver(IApplicationThread caller, String callerPackage,
            IIntentReceiver receiver, IntentFilter filter, String permission, int userId,
            int flags) {
        enforceNotIsolatedCaller("registerReceiver");
        ArrayList<Intent> stickyIntents = null;
        ProcessRecord callerApp = null;
        final boolean visibleToInstantApps
                = (flags & Context.RECEIVER_VISIBLE_TO_INSTANT_APPS) != 0;
        int callingUid;
        int callingPid;
        boolean instantApp;
        synchronized(this) {
            if (caller != null) {
                callerApp = getRecordForAppLocked(caller);
                if (callerApp == null) {
                    throw new SecurityException(
                            "Unable to find app for caller " + caller
                            + " (pid=" + Binder.getCallingPid()
                            + ") when registering receiver " + receiver);
                }
                if (callerApp.info.uid != SYSTEM_UID &&
                        !callerApp.pkgList.containsKey(callerPackage) &&
                        !"android".equals(callerPackage)) {
                    throw new SecurityException("Given caller package " + callerPackage
                            + " is not running in process " + callerApp);
                }
                callingUid = callerApp.info.uid;
                callingPid = callerApp.pid;
            } else {
                callerPackage = null;
                callingUid = Binder.getCallingUid();
                callingPid = Binder.getCallingPid();
            }

            instantApp = isInstantApp(callerApp, callerPackage, callingUid);
            userId = mUserController.handleIncomingUser(callingPid, callingUid, userId, true,
                    ALLOW_FULL_ONLY, "registerReceiver", callerPackage);

            Iterator<String> actions = filter.actionsIterator();
            if (actions == null) {
                ArrayList<String> noAction = new ArrayList<String>(1);
                noAction.add(null);
                actions = noAction.iterator();
            }

            // Collect stickies of users
            int[] userIds = { UserHandle.USER_ALL, UserHandle.getUserId(callingUid) };
            while (actions.hasNext()) {
                String action = actions.next();
                for (int id : userIds) {
                    ArrayMap<String, ArrayList<Intent>> stickies = mStickyBroadcasts.get(id);
                    if (stickies != null) {
                        ArrayList<Intent> intents = stickies.get(action);
                        if (intents != null) {
                            if (stickyIntents == null) {
                                stickyIntents = new ArrayList<Intent>();
                            }
                            stickyIntents.addAll(intents);
                        }
                    }
                }
            }
        }

        ArrayList<Intent> allSticky = null;
        if (stickyIntents != null) {
            final ContentResolver resolver = mContext.getContentResolver();
            // Look for any matching sticky broadcasts...
            for (int i = 0, N = stickyIntents.size(); i < N; i++) {
                Intent intent = stickyIntents.get(i);
                // Don't provided intents that aren't available to instant apps.
                if (instantApp &&
                        (intent.getFlags() & Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS) == 0) {
                    continue;
                }
                // If intent has scheme "content", it will need to acccess
                // provider that needs to lock mProviderMap in ActivityThread
                // and also it may need to wait application response, so we
                // cannot lock ActivityManagerService here.
                if (filter.match(resolver, intent, true, TAG) >= 0) {
                    if (allSticky == null) {
                        allSticky = new ArrayList<Intent>();
                    }
                    allSticky.add(intent);
                }
            }
        }

        // The first sticky in the list is returned directly back to the client.
        Intent sticky = allSticky != null ? allSticky.get(0) : null;
        if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Register receiver " + filter + ": " + sticky);
        if (receiver == null) {
            return sticky;
        }

        synchronized (this) {
            if (callerApp != null && (callerApp.thread == null
                    || callerApp.thread.asBinder() != caller.asBinder())) {
                // Original caller already died
                return null;
            }
            ReceiverList rl = mRegisteredReceivers.get(receiver.asBinder());
            if (rl == null) {
                rl = new ReceiverList(this, callerApp, callingPid, callingUid,
                        userId, receiver);
                if (rl.app != null) {
                    final int totalReceiversForApp = rl.app.receivers.size();
                    if (totalReceiversForApp >= MAX_RECEIVERS_ALLOWED_PER_APP) {
                        throw new IllegalStateException("Too many receivers, total of "
                                + totalReceiversForApp + ", registered for pid: "
                                + rl.pid + ", callerPackage: " + callerPackage);
                    }
                    rl.app.receivers.add(rl);
                } else {
                    try {
                        receiver.asBinder().linkToDeath(rl, 0);
                    } catch (RemoteException e) {
                        return sticky;
                    }
                    rl.linkedToDeath = true;
                }
                mRegisteredReceivers.put(receiver.asBinder(), rl);
            } else if (rl.uid != callingUid) {
                throw new IllegalArgumentException(
                        "Receiver requested to register for uid " + callingUid
                        + " was previously registered for uid " + rl.uid
                        + " callerPackage is " + callerPackage);
            } else if (rl.pid != callingPid) {
                throw new IllegalArgumentException(
                        "Receiver requested to register for pid " + callingPid
                        + " was previously registered for pid " + rl.pid
                        + " callerPackage is " + callerPackage);
            } else if (rl.userId != userId) {
                throw new IllegalArgumentException(
                        "Receiver requested to register for user " + userId
                        + " was previously registered for user " + rl.userId
                        + " callerPackage is " + callerPackage);
            }
            BroadcastFilter bf = new BroadcastFilter(filter, rl, callerPackage,
                    permission, callingUid, userId, instantApp, visibleToInstantApps);
            if (rl.containsFilter(filter)) {
                Slog.w(TAG, "Receiver with filter " + filter
                        + " already registered for pid " + rl.pid
                        + ", callerPackage is " + callerPackage);
            } else {
                rl.add(bf);
                if (!bf.debugCheck()) {
                    Slog.w(TAG, "==> For Dynamic broadcast");
                }
                mReceiverResolver.addFilter(bf);
            }

            // Enqueue broadcasts for all existing stickies that match
            // this filter.
            if (allSticky != null) {
                ArrayList receivers = new ArrayList();
                receivers.add(bf);

                final int stickyCount = allSticky.size();
                for (int i = 0; i < stickyCount; i++) {
                    Intent intent = allSticky.get(i);
                    BroadcastQueue queue = broadcastQueueForIntent(intent);
                    BroadcastRecord r = new BroadcastRecord(queue, intent, null,
                            null, -1, -1, false, null, null, OP_NONE, null, receivers,
                            null, 0, null, null, false, true, true, -1, false,
                            false /* only PRE_BOOT_COMPLETED should be exempt, no stickies */);
                    queue.enqueueParallelBroadcastLocked(r);
                    queue.scheduleBroadcastsLocked();
                }
            }

            return sticky;
        }
    }

    public void unregisterReceiver(IIntentReceiver receiver) {
        if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Unregister receiver: " + receiver);

        final long origId = Binder.clearCallingIdentity();
        try {
            boolean doTrim = false;

            synchronized(this) {
                ReceiverList rl = mRegisteredReceivers.get(receiver.asBinder());
                if (rl != null) {
                    final BroadcastRecord r = rl.curBroadcast;
                    if (r != null && r == r.queue.getMatchingOrderedReceiver(r)) {
                        final boolean doNext = r.queue.finishReceiverLocked(
                                r, r.resultCode, r.resultData, r.resultExtras,
                                r.resultAbort, false);
                        if (doNext) {
                            doTrim = true;
                            r.queue.processNextBroadcast(false);
                        }
                    }

                    if (rl.app != null) {
                        rl.app.receivers.remove(rl);
                    }
                    removeReceiverLocked(rl);
                    if (rl.linkedToDeath) {
                        rl.linkedToDeath = false;
                        rl.receiver.asBinder().unlinkToDeath(rl, 0);
                    }
                }
            }

            // If we actually concluded any broadcasts, we might now be able
            // to trim the recipients' apps from our working set
            if (doTrim) {
                trimApplications();
                return;
            }

        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void removeReceiverLocked(ReceiverList rl) {
        mRegisteredReceivers.remove(rl.receiver.asBinder());
        for (int i = rl.size() - 1; i >= 0; i--) {
            mReceiverResolver.removeFilter(rl.get(i));
        }
    }

    private final void sendPackageBroadcastLocked(int cmd, String[] packages, int userId) {
        mProcessList.sendPackageBroadcastLocked(cmd, packages, userId);
    }

    private List<ResolveInfo> collectReceiverComponents(Intent intent, String resolvedType,
            int callingUid, int[] users) {
        // TODO: come back and remove this assumption to triage all broadcasts
        int pmFlags = STOCK_PM_FLAGS | MATCH_DEBUG_TRIAGED_MISSING;

        List<ResolveInfo> receivers = null;
        try {
            HashSet<ComponentName> singleUserReceivers = null;
            boolean scannedFirstReceivers = false;
            for (int user : users) {
                // Skip users that have Shell restrictions, with exception of always permitted
                // Shell broadcasts
                if (callingUid == SHELL_UID
                        && mUserController.hasUserRestriction(
                                UserManager.DISALLOW_DEBUGGING_FEATURES, user)
                        && !isPermittedShellBroadcast(intent)) {
                    continue;
                }
                List<ResolveInfo> newReceivers = AppGlobals.getPackageManager()
                        .queryIntentReceivers(intent, resolvedType, pmFlags, user).getList();
                if (user != UserHandle.USER_SYSTEM && newReceivers != null) {
                    // If this is not the system user, we need to check for
                    // any receivers that should be filtered out.
                    for (int i=0; i<newReceivers.size(); i++) {
                        ResolveInfo ri = newReceivers.get(i);
                        if ((ri.activityInfo.flags&ActivityInfo.FLAG_SYSTEM_USER_ONLY) != 0) {
                            newReceivers.remove(i);
                            i--;
                        }
                    }
                }
                if (newReceivers != null && newReceivers.size() == 0) {
                    newReceivers = null;
                }
                if (receivers == null) {
                    receivers = newReceivers;
                } else if (newReceivers != null) {
                    // We need to concatenate the additional receivers
                    // found with what we have do far.  This would be easy,
                    // but we also need to de-dup any receivers that are
                    // singleUser.
                    if (!scannedFirstReceivers) {
                        // Collect any single user receivers we had already retrieved.
                        scannedFirstReceivers = true;
                        for (int i=0; i<receivers.size(); i++) {
                            ResolveInfo ri = receivers.get(i);
                            if ((ri.activityInfo.flags&ActivityInfo.FLAG_SINGLE_USER) != 0) {
                                ComponentName cn = new ComponentName(
                                        ri.activityInfo.packageName, ri.activityInfo.name);
                                if (singleUserReceivers == null) {
                                    singleUserReceivers = new HashSet<ComponentName>();
                                }
                                singleUserReceivers.add(cn);
                            }
                        }
                    }
                    // Add the new results to the existing results, tracking
                    // and de-dupping single user receivers.
                    for (int i=0; i<newReceivers.size(); i++) {
                        ResolveInfo ri = newReceivers.get(i);
                        if ((ri.activityInfo.flags&ActivityInfo.FLAG_SINGLE_USER) != 0) {
                            ComponentName cn = new ComponentName(
                                    ri.activityInfo.packageName, ri.activityInfo.name);
                            if (singleUserReceivers == null) {
                                singleUserReceivers = new HashSet<ComponentName>();
                            }
                            if (!singleUserReceivers.contains(cn)) {
                                singleUserReceivers.add(cn);
                                receivers.add(ri);
                            }
                        } else {
                            receivers.add(ri);
                        }
                    }
                }
            }
        } catch (RemoteException ex) {
            // pm is in same process, this will never happen.
        }
        return receivers;
    }

    private boolean isPermittedShellBroadcast(Intent intent) {
        // remote bugreport should always be allowed to be taken
        return INTENT_REMOTE_BUGREPORT_FINISHED.equals(intent.getAction());
    }

    private void checkBroadcastFromSystem(Intent intent, ProcessRecord callerApp,
            String callerPackage, int callingUid, boolean isProtectedBroadcast, List receivers) {
        if ((intent.getFlags() & Intent.FLAG_RECEIVER_FROM_SHELL) != 0) {
            // Don't yell about broadcasts sent via shell
            return;
        }

        final String action = intent.getAction();
        if (isProtectedBroadcast
                || Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                || Intent.ACTION_DISMISS_KEYBOARD_SHORTCUTS.equals(action)
                || Intent.ACTION_MEDIA_BUTTON.equals(action)
                || Intent.ACTION_MEDIA_SCANNER_SCAN_FILE.equals(action)
                || Intent.ACTION_SHOW_KEYBOARD_SHORTCUTS.equals(action)
                || Intent.ACTION_MASTER_CLEAR.equals(action)
                || Intent.ACTION_FACTORY_RESET.equals(action)
                || AppWidgetManager.ACTION_APPWIDGET_CONFIGURE.equals(action)
                || AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)
                || LocationManager.HIGH_POWER_REQUEST_CHANGE_ACTION.equals(action)
                || TelephonyIntents.ACTION_REQUEST_OMADM_CONFIGURATION_UPDATE.equals(action)
                || SuggestionSpan.ACTION_SUGGESTION_PICKED.equals(action)
                || AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(action)
                || AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(action)) {
            // Broadcast is either protected, or it's a public action that
            // we've relaxed, so it's fine for system internals to send.
            return;
        }

        // This broadcast may be a problem...  but there are often system components that
        // want to send an internal broadcast to themselves, which is annoying to have to
        // explicitly list each action as a protected broadcast, so we will check for that
        // one safe case and allow it: an explicit broadcast, only being received by something
        // that has protected itself.
        if (intent.getPackage() != null || intent.getComponent() != null) {
            if (receivers == null || receivers.size() == 0) {
                // Intent is explicit and there's no receivers.
                // This happens, e.g. , when a system component sends a broadcast to
                // its own runtime receiver, and there's no manifest receivers for it,
                // because this method is called twice for each broadcast,
                // for runtime receivers and manifest receivers and the later check would find
                // no receivers.
                return;
            }
            boolean allProtected = true;
            for (int i = receivers.size()-1; i >= 0; i--) {
                Object target = receivers.get(i);
                if (target instanceof ResolveInfo) {
                    ResolveInfo ri = (ResolveInfo)target;
                    if (ri.activityInfo.exported && ri.activityInfo.permission == null) {
                        allProtected = false;
                        break;
                    }
                } else {
                    BroadcastFilter bf = (BroadcastFilter)target;
                    if (bf.requiredPermission == null) {
                        allProtected = false;
                        break;
                    }
                }
            }
            if (allProtected) {
                // All safe!
                return;
            }
        }

        // The vast majority of broadcasts sent from system internals
        // should be protected to avoid security holes, so yell loudly
        // to ensure we examine these cases.
        if (callerApp != null) {
            Log.wtf(TAG, "Sending non-protected broadcast " + action
                            + " from system " + callerApp.toShortString() + " pkg " + callerPackage,
                    new Throwable());
        } else {
            Log.wtf(TAG, "Sending non-protected broadcast " + action
                            + " from system uid " + UserHandle.formatUid(callingUid)
                            + " pkg " + callerPackage,
                    new Throwable());
        }
    }

    @GuardedBy("this")
    final int broadcastIntentLocked(ProcessRecord callerApp,
            String callerPackage, Intent intent, String resolvedType,
            IIntentReceiver resultTo, int resultCode, String resultData,
            Bundle resultExtras, String[] requiredPermissions, int appOp, Bundle bOptions,
            boolean ordered, boolean sticky, int callingPid, int callingUid, int realCallingUid,
            int realCallingPid, int userId) {
        return broadcastIntentLocked(callerApp, callerPackage, intent, resolvedType, resultTo,
            resultCode, resultData, resultExtras, requiredPermissions, appOp, bOptions, ordered,
            sticky, callingPid, callingUid, realCallingUid, realCallingPid, userId,
            false /* allowBackgroundActivityStarts */);
    }

    @GuardedBy("this")
    final int broadcastIntentLocked(ProcessRecord callerApp,
            String callerPackage, Intent intent, String resolvedType,
            IIntentReceiver resultTo, int resultCode, String resultData,
            Bundle resultExtras, String[] requiredPermissions, int appOp, Bundle bOptions,
            boolean ordered, boolean sticky, int callingPid, int callingUid, int realCallingUid,
            int realCallingPid, int userId, boolean allowBackgroundActivityStarts) {
        intent = new Intent(intent);

        final boolean callerInstantApp = isInstantApp(callerApp, callerPackage, callingUid);
        // Instant Apps cannot use FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS
        if (callerInstantApp) {
            intent.setFlags(intent.getFlags() & ~Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
        }

        // By default broadcasts do not go to stopped apps.
        intent.addFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);

        // If we have not finished booting, don't allow this to launch new processes.
        if (!mProcessesReady && (intent.getFlags()&Intent.FLAG_RECEIVER_BOOT_UPGRADE) == 0) {
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        }

        if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG_BROADCAST,
                (sticky ? "Broadcast sticky: ": "Broadcast: ") + intent
                + " ordered=" + ordered + " userid=" + userId);
        if ((resultTo != null) && !ordered) {
            Slog.w(TAG, "Broadcast " + intent + " not ordered but result callback requested!");
        }

        userId = mUserController.handleIncomingUser(callingPid, callingUid, userId, true,
                ALLOW_NON_FULL, "broadcast", callerPackage);

        // Make sure that the user who is receiving this broadcast or its parent is running.
        // If not, we will just skip it. Make an exception for shutdown broadcasts, upgrade steps.
        if (userId != UserHandle.USER_ALL && !mUserController.isUserOrItsParentRunning(userId)) {
            if ((callingUid != SYSTEM_UID
                    || (intent.getFlags() & Intent.FLAG_RECEIVER_BOOT_UPGRADE) == 0)
                    && !Intent.ACTION_SHUTDOWN.equals(intent.getAction())) {
                Slog.w(TAG, "Skipping broadcast of " + intent
                        + ": user " + userId + " and its parent (if any) are stopped");
                return ActivityManager.BROADCAST_FAILED_USER_STOPPED;
            }
        }

        final String action = intent.getAction();
        BroadcastOptions brOptions = null;
        if (bOptions != null) {
            brOptions = new BroadcastOptions(bOptions);
            if (brOptions.getTemporaryAppWhitelistDuration() > 0) {
                // See if the caller is allowed to do this.  Note we are checking against
                // the actual real caller (not whoever provided the operation as say a
                // PendingIntent), because that who is actually supplied the arguments.
                if (checkComponentPermission(
                        android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST,
                        realCallingPid, realCallingUid, -1, true)
                        != PackageManager.PERMISSION_GRANTED) {
                    String msg = "Permission Denial: " + intent.getAction()
                            + " broadcast from " + callerPackage + " (pid=" + callingPid
                            + ", uid=" + callingUid + ")"
                            + " requires "
                            + android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST;
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                }
            }
            if (brOptions.isDontSendToRestrictedApps()
                    && !isUidActiveLocked(callingUid)
                    && isBackgroundRestrictedNoCheck(callingUid, callerPackage)) {
                Slog.i(TAG, "Not sending broadcast " + action + " - app " + callerPackage
                        + " has background restrictions");
                return ActivityManager.START_CANCELED;
            }
            if (brOptions.allowsBackgroundActivityStarts()) {
                // See if the caller is allowed to do this.  Note we are checking against
                // the actual real caller (not whoever provided the operation as say a
                // PendingIntent), because that who is actually supplied the arguments.
                if (checkComponentPermission(
                        android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND,
                        realCallingPid, realCallingUid, -1, true)
                        != PackageManager.PERMISSION_GRANTED) {
                    String msg = "Permission Denial: " + intent.getAction()
                            + " broadcast from " + callerPackage + " (pid=" + callingPid
                            + ", uid=" + callingUid + ")"
                            + " requires "
                            + android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND;
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                } else {
                    allowBackgroundActivityStarts = true;
                }
            }
        }

        // Verify that protected broadcasts are only being sent by system code,
        // and that system code is only sending protected broadcasts.
        final boolean isProtectedBroadcast;
        try {
            isProtectedBroadcast = AppGlobals.getPackageManager().isProtectedBroadcast(action);
        } catch (RemoteException e) {
            Slog.w(TAG, "Remote exception", e);
            return ActivityManager.BROADCAST_SUCCESS;
        }

        final boolean isCallerSystem;
        switch (UserHandle.getAppId(callingUid)) {
            case ROOT_UID:
            case SYSTEM_UID:
            case PHONE_UID:
            case BLUETOOTH_UID:
            case NFC_UID:
            case SE_UID:
            case NETWORK_STACK_UID:
                isCallerSystem = true;
                break;
            default:
                isCallerSystem = (callerApp != null) && callerApp.isPersistent();
                break;
        }

        // First line security check before anything else: stop non-system apps from
        // sending protected broadcasts.
        if (!isCallerSystem) {
            if (isProtectedBroadcast) {
                String msg = "Permission Denial: not allowed to send broadcast "
                        + action + " from pid="
                        + callingPid + ", uid=" + callingUid;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);

            } else if (AppWidgetManager.ACTION_APPWIDGET_CONFIGURE.equals(action)
                    || AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {
                // Special case for compatibility: we don't want apps to send this,
                // but historically it has not been protected and apps may be using it
                // to poke their own app widget.  So, instead of making it protected,
                // just limit it to the caller.
                if (callerPackage == null) {
                    String msg = "Permission Denial: not allowed to send broadcast "
                            + action + " from unknown caller.";
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                } else if (intent.getComponent() != null) {
                    // They are good enough to send to an explicit component...  verify
                    // it is being sent to the calling app.
                    if (!intent.getComponent().getPackageName().equals(
                            callerPackage)) {
                        String msg = "Permission Denial: not allowed to send broadcast "
                                + action + " to "
                                + intent.getComponent().getPackageName() + " from "
                                + callerPackage;
                        Slog.w(TAG, msg);
                        throw new SecurityException(msg);
                    }
                } else {
                    // Limit broadcast to their own package.
                    intent.setPackage(callerPackage);
                }
            }
        }

        boolean timeoutExempt = false;

        if (action != null) {
            if (getBackgroundLaunchBroadcasts().contains(action)) {
                if (DEBUG_BACKGROUND_CHECK) {
                    Slog.i(TAG, "Broadcast action " + action + " forcing include-background");
                }
                intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            }

            switch (action) {
                case Intent.ACTION_UID_REMOVED:
                case Intent.ACTION_PACKAGE_REMOVED:
                case Intent.ACTION_PACKAGE_CHANGED:
                case Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE:
                case Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE:
                case Intent.ACTION_PACKAGES_SUSPENDED:
                case Intent.ACTION_PACKAGES_UNSUSPENDED:
                    // Handle special intents: if this broadcast is from the package
                    // manager about a package being removed, we need to remove all of
                    // its activities from the history stack.
                    if (checkComponentPermission(
                            android.Manifest.permission.BROADCAST_PACKAGE_REMOVED,
                            callingPid, callingUid, -1, true)
                            != PackageManager.PERMISSION_GRANTED) {
                        String msg = "Permission Denial: " + intent.getAction()
                                + " broadcast from " + callerPackage + " (pid=" + callingPid
                                + ", uid=" + callingUid + ")"
                                + " requires "
                                + android.Manifest.permission.BROADCAST_PACKAGE_REMOVED;
                        Slog.w(TAG, msg);
                        throw new SecurityException(msg);
                    }
                    switch (action) {
                        case Intent.ACTION_UID_REMOVED:
                            final int uid = getUidFromIntent(intent);
                            if (uid >= 0) {
                                mBatteryStatsService.removeUid(uid);
                                mAppOpsService.uidRemoved(uid);
                            }
                            break;
                        case Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE:
                            // If resources are unavailable just force stop all those packages
                            // and flush the attribute cache as well.
                            String list[] =
                                    intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                            if (list != null && list.length > 0) {
                                for (int i = 0; i < list.length; i++) {
                                    forceStopPackageLocked(list[i], -1, false, true, true,
                                            false, false, userId, "storage unmount");
                                }
                                mAtmInternal.cleanupRecentTasksForUser(UserHandle.USER_ALL);
                                sendPackageBroadcastLocked(
                                        ApplicationThreadConstants.EXTERNAL_STORAGE_UNAVAILABLE,
                                        list, userId);
                            }
                            break;
                        case Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE:
                            mAtmInternal.cleanupRecentTasksForUser(UserHandle.USER_ALL);
                            break;
                        case Intent.ACTION_PACKAGE_REMOVED:
                        case Intent.ACTION_PACKAGE_CHANGED:
                            Uri data = intent.getData();
                            String ssp;
                            if (data != null && (ssp=data.getSchemeSpecificPart()) != null) {
                                boolean removed = Intent.ACTION_PACKAGE_REMOVED.equals(action);
                                final boolean replacing =
                                        intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                                final boolean killProcess =
                                        !intent.getBooleanExtra(Intent.EXTRA_DONT_KILL_APP, false);
                                final boolean fullUninstall = removed && !replacing;
                                if (removed) {
                                    if (killProcess) {
                                        forceStopPackageLocked(ssp, UserHandle.getAppId(
                                                intent.getIntExtra(Intent.EXTRA_UID, -1)),
                                                false, true, true, false, fullUninstall, userId,
                                                removed ? "pkg removed" : "pkg changed");
                                    }
                                    final int cmd = killProcess
                                            ? ApplicationThreadConstants.PACKAGE_REMOVED
                                            : ApplicationThreadConstants.PACKAGE_REMOVED_DONT_KILL;
                                    sendPackageBroadcastLocked(cmd,
                                            new String[] {ssp}, userId);
                                    if (fullUninstall) {
                                        mAppOpsService.packageRemoved(
                                                intent.getIntExtra(Intent.EXTRA_UID, -1), ssp);

                                        // Remove all permissions granted from/to this package
                                        mUgmInternal.removeUriPermissionsForPackage(ssp, userId,
                                                true, false);

                                        mAtmInternal.removeRecentTasksByPackageName(ssp, userId);

                                        mServices.forceStopPackageLocked(ssp, userId);
                                        mAtmInternal.onPackageUninstalled(ssp);
                                        mBatteryStatsService.notePackageUninstalled(ssp);
                                    }
                                } else {
                                    if (killProcess) {
                                        final int extraUid = intent.getIntExtra(Intent.EXTRA_UID,
                                                -1);
                                        mProcessList.killPackageProcessesLocked(ssp,
                                                UserHandle.getAppId(extraUid),
                                                userId, ProcessList.INVALID_ADJ, "change " + ssp);
                                    }
                                    cleanupDisabledPackageComponentsLocked(ssp, userId,
                                            intent.getStringArrayExtra(
                                                    Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST));
                                }
                            }
                            break;
                        case Intent.ACTION_PACKAGES_SUSPENDED:
                        case Intent.ACTION_PACKAGES_UNSUSPENDED:
                            final boolean suspended = Intent.ACTION_PACKAGES_SUSPENDED.equals(
                                    intent.getAction());
                            final String[] packageNames = intent.getStringArrayExtra(
                                    Intent.EXTRA_CHANGED_PACKAGE_LIST);
                            final int userHandle = intent.getIntExtra(
                                    Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);

                            mAtmInternal.onPackagesSuspendedChanged(packageNames, suspended,
                                    userHandle);
                            break;
                    }
                    break;
                case Intent.ACTION_PACKAGE_REPLACED:
                {
                    final Uri data = intent.getData();
                    final String ssp;
                    if (data != null && (ssp = data.getSchemeSpecificPart()) != null) {
                        ApplicationInfo aInfo = null;
                        try {
                            aInfo = AppGlobals.getPackageManager()
                                    .getApplicationInfo(ssp, STOCK_PM_FLAGS, userId);
                        } catch (RemoteException ignore) {}
                        if (aInfo == null) {
                            Slog.w(TAG, "Dropping ACTION_PACKAGE_REPLACED for non-existent pkg:"
                                    + " ssp=" + ssp + " data=" + data);
                            return ActivityManager.BROADCAST_SUCCESS;
                        }
                        updateAssociationForApp(aInfo);
                        mAtmInternal.onPackageReplaced(aInfo);
                        mServices.updateServiceApplicationInfoLocked(aInfo);
                        sendPackageBroadcastLocked(ApplicationThreadConstants.PACKAGE_REPLACED,
                                new String[] {ssp}, userId);
                    }
                    break;
                }
                case Intent.ACTION_PACKAGE_ADDED:
                {
                    // Special case for adding a package: by default turn on compatibility mode.
                    Uri data = intent.getData();
                    String ssp;
                    if (data != null && (ssp = data.getSchemeSpecificPart()) != null) {
                        final boolean replacing =
                                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                        mAtmInternal.onPackageAdded(ssp, replacing);

                        try {
                            ApplicationInfo ai = AppGlobals.getPackageManager().
                                    getApplicationInfo(ssp, STOCK_PM_FLAGS, 0);
                            mBatteryStatsService.notePackageInstalled(ssp,
                                    ai != null ? ai.versionCode : 0);
                        } catch (RemoteException e) {
                        }
                    }
                    break;
                }
                case Intent.ACTION_PACKAGE_DATA_CLEARED:
                {
                    Uri data = intent.getData();
                    String ssp;
                    if (data != null && (ssp = data.getSchemeSpecificPart()) != null) {
                        mAtmInternal.onPackageDataCleared(ssp);
                    }
                    break;
                }
                case Intent.ACTION_TIMEZONE_CHANGED:
                    // If this is the time zone changed action, queue up a message that will reset
                    // the timezone of all currently running processes. This message will get
                    // queued up before the broadcast happens.
                    mHandler.sendEmptyMessage(UPDATE_TIME_ZONE);
                    break;
                case Intent.ACTION_TIME_CHANGED:
                    // EXTRA_TIME_PREF_24_HOUR_FORMAT is optional so we must distinguish between
                    // the tri-state value it may contain and "unknown".
                    // For convenience we re-use the Intent extra values.
                    final int NO_EXTRA_VALUE_FOUND = -1;
                    final int timeFormatPreferenceMsgValue = intent.getIntExtra(
                            Intent.EXTRA_TIME_PREF_24_HOUR_FORMAT,
                            NO_EXTRA_VALUE_FOUND /* defaultValue */);
                    // Only send a message if the time preference is available.
                    if (timeFormatPreferenceMsgValue != NO_EXTRA_VALUE_FOUND) {
                        Message updateTimePreferenceMsg =
                                mHandler.obtainMessage(UPDATE_TIME_PREFERENCE_MSG,
                                        timeFormatPreferenceMsgValue, 0);
                        mHandler.sendMessage(updateTimePreferenceMsg);
                    }
                    BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
                    synchronized (stats) {
                        stats.noteCurrentTimeChangedLocked();
                    }
                    break;
                case Intent.ACTION_CLEAR_DNS_CACHE:
                    mHandler.sendEmptyMessage(CLEAR_DNS_CACHE_MSG);
                    break;
                case Proxy.PROXY_CHANGE_ACTION:
                    mHandler.sendMessage(mHandler.obtainMessage(UPDATE_HTTP_PROXY_MSG));
                    break;
                case android.hardware.Camera.ACTION_NEW_PICTURE:
                case android.hardware.Camera.ACTION_NEW_VIDEO:
                    // In N we just turned these off; in O we are turing them back on partly,
                    // only for registered receivers.  This will still address the main problem
                    // (a spam of apps waking up when a picture is taken putting significant
                    // memory pressure on the system at a bad point), while still allowing apps
                    // that are already actively running to know about this happening.
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                    break;
                case android.security.KeyChain.ACTION_TRUST_STORE_CHANGED:
                    mHandler.sendEmptyMessage(HANDLE_TRUST_STORAGE_UPDATE_MSG);
                    break;
                case "com.android.launcher.action.INSTALL_SHORTCUT":
                    // As of O, we no longer support this broadcasts, even for pre-O apps.
                    // Apps should now be using ShortcutManager.pinRequestShortcut().
                    Log.w(TAG, "Broadcast " + action
                            + " no longer supported. It will not be delivered.");
                    return ActivityManager.BROADCAST_SUCCESS;
                case Intent.ACTION_PRE_BOOT_COMPLETED:
                    timeoutExempt = true;
                    break;
            }

            if (Intent.ACTION_PACKAGE_ADDED.equals(action) ||
                    Intent.ACTION_PACKAGE_REMOVED.equals(action) ||
                    Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                final int uid = getUidFromIntent(intent);
                if (uid != -1) {
                    final UidRecord uidRec = mProcessList.getUidRecordLocked(uid);
                    if (uidRec != null) {
                        uidRec.updateHasInternetPermission();
                    }
                }
            }
        }

        // Add to the sticky list if requested.
        if (sticky) {
            if (checkPermission(android.Manifest.permission.BROADCAST_STICKY,
                    callingPid, callingUid)
                    != PackageManager.PERMISSION_GRANTED) {
                String msg = "Permission Denial: broadcastIntent() requesting a sticky broadcast from pid="
                        + callingPid + ", uid=" + callingUid
                        + " requires " + android.Manifest.permission.BROADCAST_STICKY;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }
            if (requiredPermissions != null && requiredPermissions.length > 0) {
                Slog.w(TAG, "Can't broadcast sticky intent " + intent
                        + " and enforce permissions " + Arrays.toString(requiredPermissions));
                return ActivityManager.BROADCAST_STICKY_CANT_HAVE_PERMISSION;
            }
            if (intent.getComponent() != null) {
                throw new SecurityException(
                        "Sticky broadcasts can't target a specific component");
            }
            // We use userId directly here, since the "all" target is maintained
            // as a separate set of sticky broadcasts.
            if (userId != UserHandle.USER_ALL) {
                // But first, if this is not a broadcast to all users, then
                // make sure it doesn't conflict with an existing broadcast to
                // all users.
                ArrayMap<String, ArrayList<Intent>> stickies = mStickyBroadcasts.get(
                        UserHandle.USER_ALL);
                if (stickies != null) {
                    ArrayList<Intent> list = stickies.get(intent.getAction());
                    if (list != null) {
                        int N = list.size();
                        int i;
                        for (i=0; i<N; i++) {
                            if (intent.filterEquals(list.get(i))) {
                                throw new IllegalArgumentException(
                                        "Sticky broadcast " + intent + " for user "
                                        + userId + " conflicts with existing global broadcast");
                            }
                        }
                    }
                }
            }
            ArrayMap<String, ArrayList<Intent>> stickies = mStickyBroadcasts.get(userId);
            if (stickies == null) {
                stickies = new ArrayMap<>();
                mStickyBroadcasts.put(userId, stickies);
            }
            ArrayList<Intent> list = stickies.get(intent.getAction());
            if (list == null) {
                list = new ArrayList<>();
                stickies.put(intent.getAction(), list);
            }
            final int stickiesCount = list.size();
            int i;
            for (i = 0; i < stickiesCount; i++) {
                if (intent.filterEquals(list.get(i))) {
                    // This sticky already exists, replace it.
                    list.set(i, new Intent(intent));
                    break;
                }
            }
            if (i >= stickiesCount) {
                list.add(new Intent(intent));
            }
        }

        int[] users;
        if (userId == UserHandle.USER_ALL) {
            // Caller wants broadcast to go to all started users.
            users = mUserController.getStartedUserArray();
        } else {
            // Caller wants broadcast to go to one specific user.
            users = new int[] {userId};
        }

        // Figure out who all will receive this broadcast.
        List receivers = null;
        List<BroadcastFilter> registeredReceivers = null;
        // Need to resolve the intent to interested receivers...
        if ((intent.getFlags()&Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                 == 0) {
            receivers = collectReceiverComponents(intent, resolvedType, callingUid, users);
        }
        if (intent.getComponent() == null) {
            if (userId == UserHandle.USER_ALL && callingUid == SHELL_UID) {
                // Query one target user at a time, excluding shell-restricted users
                for (int i = 0; i < users.length; i++) {
                    if (mUserController.hasUserRestriction(
                            UserManager.DISALLOW_DEBUGGING_FEATURES, users[i])) {
                        continue;
                    }
                    List<BroadcastFilter> registeredReceiversForUser =
                            mReceiverResolver.queryIntent(intent,
                                    resolvedType, false /*defaultOnly*/, users[i]);
                    if (registeredReceivers == null) {
                        registeredReceivers = registeredReceiversForUser;
                    } else if (registeredReceiversForUser != null) {
                        registeredReceivers.addAll(registeredReceiversForUser);
                    }
                }
            } else {
                registeredReceivers = mReceiverResolver.queryIntent(intent,
                        resolvedType, false /*defaultOnly*/, userId);
            }
        }

        final boolean replacePending =
                (intent.getFlags()&Intent.FLAG_RECEIVER_REPLACE_PENDING) != 0;

        if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Enqueueing broadcast: " + intent.getAction()
                + " replacePending=" + replacePending);

        int NR = registeredReceivers != null ? registeredReceivers.size() : 0;
        if (!ordered && NR > 0) {
            // If we are not serializing this broadcast, then send the
            // registered receivers separately so they don't wait for the
            // components to be launched.
            if (isCallerSystem) {
                checkBroadcastFromSystem(intent, callerApp, callerPackage, callingUid,
                        isProtectedBroadcast, registeredReceivers);
            }
            final BroadcastQueue queue = broadcastQueueForIntent(intent);
            BroadcastRecord r = new BroadcastRecord(queue, intent, callerApp,
                    callerPackage, callingPid, callingUid, callerInstantApp, resolvedType,
                    requiredPermissions, appOp, brOptions, registeredReceivers, resultTo,
                    resultCode, resultData, resultExtras, ordered, sticky, false, userId,
                    allowBackgroundActivityStarts, timeoutExempt);
            if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Enqueueing parallel broadcast " + r);
            final boolean replaced = replacePending
                    && (queue.replaceParallelBroadcastLocked(r) != null);
            // Note: We assume resultTo is null for non-ordered broadcasts.
            if (!replaced) {
                queue.enqueueParallelBroadcastLocked(r);
                queue.scheduleBroadcastsLocked();
            }
            registeredReceivers = null;
            NR = 0;
        }

        // Merge into one list.
        int ir = 0;
        if (receivers != null) {
            // A special case for PACKAGE_ADDED: do not allow the package
            // being added to see this broadcast.  This prevents them from
            // using this as a back door to get run as soon as they are
            // installed.  Maybe in the future we want to have a special install
            // broadcast or such for apps, but we'd like to deliberately make
            // this decision.
            String skipPackages[] = null;
            if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())
                    || Intent.ACTION_PACKAGE_RESTARTED.equals(intent.getAction())
                    || Intent.ACTION_PACKAGE_DATA_CLEARED.equals(intent.getAction())) {
                Uri data = intent.getData();
                if (data != null) {
                    String pkgName = data.getSchemeSpecificPart();
                    if (pkgName != null) {
                        skipPackages = new String[] { pkgName };
                    }
                }
            } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(intent.getAction())) {
                skipPackages = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            }
            if (skipPackages != null && (skipPackages.length > 0)) {
                for (String skipPackage : skipPackages) {
                    if (skipPackage != null) {
                        int NT = receivers.size();
                        for (int it=0; it<NT; it++) {
                            ResolveInfo curt = (ResolveInfo)receivers.get(it);
                            if (curt.activityInfo.packageName.equals(skipPackage)) {
                                receivers.remove(it);
                                it--;
                                NT--;
                            }
                        }
                    }
                }
            }

            int NT = receivers != null ? receivers.size() : 0;
            int it = 0;
            ResolveInfo curt = null;
            BroadcastFilter curr = null;
            while (it < NT && ir < NR) {
                if (curt == null) {
                    curt = (ResolveInfo)receivers.get(it);
                }
                if (curr == null) {
                    curr = registeredReceivers.get(ir);
                }
                if (curr.getPriority() >= curt.priority) {
                    // Insert this broadcast record into the final list.
                    receivers.add(it, curr);
                    ir++;
                    curr = null;
                    it++;
                    NT++;
                } else {
                    // Skip to the next ResolveInfo in the final list.
                    it++;
                    curt = null;
                }
            }
        }
        while (ir < NR) {
            if (receivers == null) {
                receivers = new ArrayList();
            }
            receivers.add(registeredReceivers.get(ir));
            ir++;
        }

        if (isCallerSystem) {
            checkBroadcastFromSystem(intent, callerApp, callerPackage, callingUid,
                    isProtectedBroadcast, receivers);
        }

        if ((receivers != null && receivers.size() > 0)
                || resultTo != null) {
            BroadcastQueue queue = broadcastQueueForIntent(intent);
            BroadcastRecord r = new BroadcastRecord(queue, intent, callerApp,
                    callerPackage, callingPid, callingUid, callerInstantApp, resolvedType,
                    requiredPermissions, appOp, brOptions, receivers, resultTo, resultCode,
                    resultData, resultExtras, ordered, sticky, false, userId,
                    allowBackgroundActivityStarts, timeoutExempt);

            if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Enqueueing ordered broadcast " + r);

            final BroadcastRecord oldRecord =
                    replacePending ? queue.replaceOrderedBroadcastLocked(r) : null;
            if (oldRecord != null) {
                // Replaced, fire the result-to receiver.
                if (oldRecord.resultTo != null) {
                    final BroadcastQueue oldQueue = broadcastQueueForIntent(oldRecord.intent);
                    try {
                        oldQueue.performReceiveLocked(oldRecord.callerApp, oldRecord.resultTo,
                                oldRecord.intent,
                                Activity.RESULT_CANCELED, null, null,
                                false, false, oldRecord.userId);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failure ["
                                + queue.mQueueName + "] sending broadcast result of "
                                + intent, e);

                    }
                }
            } else {
                queue.enqueueOrderedBroadcastLocked(r);
                queue.scheduleBroadcastsLocked();
            }
        } else {
            // There was nobody interested in the broadcast, but we still want to record
            // that it happened.
            if (intent.getComponent() == null && intent.getPackage() == null
                    && (intent.getFlags()&Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
                // This was an implicit broadcast... let's record it for posterity.
                addBroadcastStatLocked(intent.getAction(), callerPackage, 0, 0, 0);
            }
        }

        return ActivityManager.BROADCAST_SUCCESS;
    }

    /**
     * @return uid from the extra field {@link Intent#EXTRA_UID} if present, Otherwise -1
     */
    private int getUidFromIntent(Intent intent) {
        if (intent == null) {
            return -1;
        }
        final Bundle intentExtras = intent.getExtras();
        return intent.hasExtra(Intent.EXTRA_UID)
                ? intentExtras.getInt(Intent.EXTRA_UID) : -1;
    }

    final void rotateBroadcastStatsIfNeededLocked() {
        final long now = SystemClock.elapsedRealtime();
        if (mCurBroadcastStats == null ||
                (mCurBroadcastStats.mStartRealtime +(24*60*60*1000) < now)) {
            mLastBroadcastStats = mCurBroadcastStats;
            if (mLastBroadcastStats != null) {
                mLastBroadcastStats.mEndRealtime = SystemClock.elapsedRealtime();
                mLastBroadcastStats.mEndUptime = SystemClock.uptimeMillis();
            }
            mCurBroadcastStats = new BroadcastStats();
        }
    }

    final void addBroadcastStatLocked(String action, String srcPackage, int receiveCount,
            int skipCount, long dispatchTime) {
        rotateBroadcastStatsIfNeededLocked();
        mCurBroadcastStats.addBroadcast(action, srcPackage, receiveCount, skipCount, dispatchTime);
    }

    final void addBackgroundCheckViolationLocked(String action, String targetPackage) {
        rotateBroadcastStatsIfNeededLocked();
        mCurBroadcastStats.addBackgroundCheckViolation(action, targetPackage);
    }

    final Intent verifyBroadcastLocked(Intent intent) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        int flags = intent.getFlags();

        if (!mProcessesReady) {
            // if the caller really truly claims to know what they're doing, go
            // ahead and allow the broadcast without launching any receivers
            if ((flags&Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT) != 0) {
                // This will be turned into a FLAG_RECEIVER_REGISTERED_ONLY later on if needed.
            } else if ((flags&Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
                Slog.e(TAG, "Attempt to launch receivers of broadcast intent " + intent
                        + " before boot completion");
                throw new IllegalStateException("Cannot broadcast before boot completed");
            }
        }

        if ((flags&Intent.FLAG_RECEIVER_BOOT_UPGRADE) != 0) {
            throw new IllegalArgumentException(
                    "Can't use FLAG_RECEIVER_BOOT_UPGRADE here");
        }

        if ((flags & Intent.FLAG_RECEIVER_FROM_SHELL) != 0) {
            switch (Binder.getCallingUid()) {
                case ROOT_UID:
                case SHELL_UID:
                    break;
                default:
                    Slog.w(TAG, "Removing FLAG_RECEIVER_FROM_SHELL because caller is UID "
                            + Binder.getCallingUid());
                    intent.removeFlags(Intent.FLAG_RECEIVER_FROM_SHELL);
                    break;
            }
        }

        return intent;
    }

    public final int broadcastIntent(IApplicationThread caller,
            Intent intent, String resolvedType, IIntentReceiver resultTo,
            int resultCode, String resultData, Bundle resultExtras,
            String[] requiredPermissions, int appOp, Bundle bOptions,
            boolean serialized, boolean sticky, int userId) {
        enforceNotIsolatedCaller("broadcastIntent");
        synchronized(this) {
            intent = verifyBroadcastLocked(intent);

            final ProcessRecord callerApp = getRecordForAppLocked(caller);
            final int callingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();
            final long origId = Binder.clearCallingIdentity();
            int res = broadcastIntentLocked(callerApp,
                    callerApp != null ? callerApp.info.packageName : null,
                    intent, resolvedType, resultTo, resultCode, resultData, resultExtras,
                    requiredPermissions, appOp, bOptions, serialized, sticky,
                    callingPid, callingUid, callingUid, callingPid, userId);
            Binder.restoreCallingIdentity(origId);
            return res;
        }
    }


    int broadcastIntentInPackage(String packageName, int uid, int realCallingUid,
            int realCallingPid, Intent intent, String resolvedType, IIntentReceiver resultTo,
            int resultCode, String resultData, Bundle resultExtras,
            String requiredPermission, Bundle bOptions, boolean serialized, boolean sticky,
            int userId, boolean allowBackgroundActivityStarts) {
        synchronized(this) {
            intent = verifyBroadcastLocked(intent);

            final long origId = Binder.clearCallingIdentity();
            String[] requiredPermissions = requiredPermission == null ? null
                    : new String[] {requiredPermission};
            int res = broadcastIntentLocked(null, packageName, intent, resolvedType,
                    resultTo, resultCode, resultData, resultExtras,
                    requiredPermissions, OP_NONE, bOptions, serialized,
                    sticky, -1, uid, realCallingUid, realCallingPid, userId,
                    allowBackgroundActivityStarts);
            Binder.restoreCallingIdentity(origId);
            return res;
        }
    }

    public final void unbroadcastIntent(IApplicationThread caller, Intent intent, int userId) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        userId = mUserController.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, true, ALLOW_NON_FULL, "removeStickyBroadcast", null);

        synchronized(this) {
            if (checkCallingPermission(android.Manifest.permission.BROADCAST_STICKY)
                    != PackageManager.PERMISSION_GRANTED) {
                String msg = "Permission Denial: unbroadcastIntent() from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " requires " + android.Manifest.permission.BROADCAST_STICKY;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }
            ArrayMap<String, ArrayList<Intent>> stickies = mStickyBroadcasts.get(userId);
            if (stickies != null) {
                ArrayList<Intent> list = stickies.get(intent.getAction());
                if (list != null) {
                    int N = list.size();
                    int i;
                    for (i=0; i<N; i++) {
                        if (intent.filterEquals(list.get(i))) {
                            list.remove(i);
                            break;
                        }
                    }
                    if (list.size() <= 0) {
                        stickies.remove(intent.getAction());
                    }
                }
                if (stickies.size() <= 0) {
                    mStickyBroadcasts.remove(userId);
                }
            }
        }
    }

    void backgroundServicesFinishedLocked(int userId) {
        for (BroadcastQueue queue : mBroadcastQueues) {
            queue.backgroundServicesFinishedLocked(userId);
        }
    }

    public void finishReceiver(IBinder who, int resultCode, String resultData,
            Bundle resultExtras, boolean resultAbort, int flags) {
        if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Finish receiver: " + who);

        // Refuse possible leaked file descriptors
        if (resultExtras != null && resultExtras.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Bundle");
        }

        final long origId = Binder.clearCallingIdentity();
        try {
            boolean doNext = false;
            BroadcastRecord r;
            BroadcastQueue queue;

            synchronized(this) {
                if (isOnOffloadQueue(flags)) {
                    queue = mOffloadBroadcastQueue;
                } else {
                    queue = (flags & Intent.FLAG_RECEIVER_FOREGROUND) != 0
                            ? mFgBroadcastQueue : mBgBroadcastQueue;
                }

                r = queue.getMatchingOrderedReceiver(who);
                if (r != null) {
                    doNext = r.queue.finishReceiverLocked(r, resultCode,
                        resultData, resultExtras, resultAbort, true);
                }
                if (doNext) {
                    r.queue.processNextBroadcastLocked(/*fromMsg=*/ false, /*skipOomAdj=*/ true);
                }
                // updateOomAdjLocked() will be done here
                trimApplicationsLocked();
            }

        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    // =========================================================
    // INSTRUMENTATION
    // =========================================================

    public boolean startInstrumentation(ComponentName className,
            String profileFile, int flags, Bundle arguments,
            IInstrumentationWatcher watcher, IUiAutomationConnection uiAutomationConnection,
            int userId, String abiOverride) {
        enforceNotIsolatedCaller("startInstrumentation");
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        userId = mUserController.handleIncomingUser(callingPid, callingUid,
                userId, false, ALLOW_FULL_ONLY, "startInstrumentation", null);
        // Refuse possible leaked file descriptors
        if (arguments != null && arguments.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Bundle");
        }

        synchronized(this) {
            InstrumentationInfo ii = null;
            ApplicationInfo ai = null;
            try {
                ii = mContext.getPackageManager().getInstrumentationInfo(
                    className, STOCK_PM_FLAGS);
                ai = AppGlobals.getPackageManager().getApplicationInfo(
                        ii.targetPackage, STOCK_PM_FLAGS, userId);
            } catch (PackageManager.NameNotFoundException e) {
            } catch (RemoteException e) {
            }
            if (ii == null) {
                reportStartInstrumentationFailureLocked(watcher, className,
                        "Unable to find instrumentation info for: " + className);
                return false;
            }
            if (ai == null) {
                reportStartInstrumentationFailureLocked(watcher, className,
                        "Unable to find instrumentation target package: " + ii.targetPackage);
                return false;
            }
            if (!ai.hasCode()) {
                reportStartInstrumentationFailureLocked(watcher, className,
                        "Instrumentation target has no code: " + ii.targetPackage);
                return false;
            }

            int match = mContext.getPackageManager().checkSignatures(
                    ii.targetPackage, ii.packageName);
            if (match < 0 && match != PackageManager.SIGNATURE_FIRST_NOT_SIGNED) {
                String msg = "Permission Denial: starting instrumentation "
                        + className + " from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingPid()
                        + " not allowed because package " + ii.packageName
                        + " does not have a signature matching the target "
                        + ii.targetPackage;
                reportStartInstrumentationFailureLocked(watcher, className, msg);
                throw new SecurityException(msg);
            }

            ActiveInstrumentation activeInstr = new ActiveInstrumentation(this);
            activeInstr.mClass = className;
            String defProcess = ai.processName;;
            if (ii.targetProcesses == null) {
                activeInstr.mTargetProcesses = new String[]{ai.processName};
            } else if (ii.targetProcesses.equals("*")) {
                activeInstr.mTargetProcesses = new String[0];
            } else {
                activeInstr.mTargetProcesses = ii.targetProcesses.split(",");
                defProcess = activeInstr.mTargetProcesses[0];
            }
            activeInstr.mTargetInfo = ai;
            activeInstr.mProfileFile = profileFile;
            activeInstr.mArguments = arguments;
            activeInstr.mWatcher = watcher;
            activeInstr.mUiAutomationConnection = uiAutomationConnection;
            activeInstr.mResultClass = className;
            activeInstr.mHasBackgroundActivityStartsPermission = checkPermission(
                    START_ACTIVITIES_FROM_BACKGROUND, callingPid, callingUid)
                            == PackageManager.PERMISSION_GRANTED;

            boolean disableHiddenApiChecks = ai.usesNonSdkApi()
                    || (flags & INSTR_FLAG_DISABLE_HIDDEN_API_CHECKS) != 0;
            if (disableHiddenApiChecks) {
                enforceCallingPermission(android.Manifest.permission.DISABLE_HIDDEN_API_CHECKS,
                        "disable hidden API checks");
            }
            final boolean mountExtStorageFull = isCallerShell()
                    && (flags & INSTR_FLAG_MOUNT_EXTERNAL_STORAGE_FULL) != 0;

            final long origId = Binder.clearCallingIdentity();
            // Instrumentation can kill and relaunch even persistent processes
            forceStopPackageLocked(ii.targetPackage, -1, true, false, true, true, false, userId,
                    "start instr");
            // Inform usage stats to make the target package active
            if (mUsageStatsService != null) {
                mUsageStatsService.reportEvent(ii.targetPackage, userId,
                        UsageEvents.Event.SYSTEM_INTERACTION);
            }

            ProcessRecord app = addAppLocked(ai, defProcess, false, disableHiddenApiChecks,
                    mountExtStorageFull, abiOverride);
            app.setActiveInstrumentation(activeInstr);
            activeInstr.mFinished = false;
            activeInstr.mRunningProcesses.add(app);
            if (!mActiveInstrumentation.contains(activeInstr)) {
                mActiveInstrumentation.add(activeInstr);
            }
            Binder.restoreCallingIdentity(origId);
        }

        return true;
    }

    private boolean isCallerShell() {
        final int callingUid = Binder.getCallingUid();
        return callingUid == SHELL_UID || callingUid == ROOT_UID;
    }

    /**
     * Report errors that occur while attempting to start Instrumentation.  Always writes the
     * error to the logs, but if somebody is watching, send the report there too.  This enables
     * the "am" command to report errors with more information.
     *
     * @param watcher The IInstrumentationWatcher.  Null if there isn't one.
     * @param cn The component name of the instrumentation.
     * @param report The error report.
     */
    private void reportStartInstrumentationFailureLocked(IInstrumentationWatcher watcher,
            ComponentName cn, String report) {
        Slog.w(TAG, report);
        if (watcher != null) {
            Bundle results = new Bundle();
            results.putString(Instrumentation.REPORT_KEY_IDENTIFIER, "ActivityManagerService");
            results.putString("Error", report);
            mInstrumentationReporter.reportStatus(watcher, cn, -1, results);
        }
    }

    void addInstrumentationResultsLocked(ProcessRecord app, Bundle results) {
        final ActiveInstrumentation instr = app.getActiveInstrumentation();
        if (instr == null) {
            Slog.w(TAG, "finishInstrumentation called on non-instrumented: " + app);
            return;
        }

        if (!instr.mFinished && results != null) {
            if (instr.mCurResults == null) {
                instr.mCurResults = new Bundle(results);
            } else {
                instr.mCurResults.putAll(results);
            }
        }
    }

    public void addInstrumentationResults(IApplicationThread target, Bundle results) {
        int userId = UserHandle.getCallingUserId();
        // Refuse possible leaked file descriptors
        if (results != null && results.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            ProcessRecord app = getRecordForAppLocked(target);
            if (app == null) {
                Slog.w(TAG, "addInstrumentationResults: no app for " + target);
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            addInstrumentationResultsLocked(app, results);
            Binder.restoreCallingIdentity(origId);
        }
    }

    @GuardedBy("this")
    void finishInstrumentationLocked(ProcessRecord app, int resultCode, Bundle results) {
        final ActiveInstrumentation instr = app.getActiveInstrumentation();
        if (instr == null) {
            Slog.w(TAG, "finishInstrumentation called on non-instrumented: " + app);
            return;
        }

        if (!instr.mFinished) {
            if (instr.mWatcher != null) {
                Bundle finalResults = instr.mCurResults;
                if (finalResults != null) {
                    if (instr.mCurResults != null && results != null) {
                        finalResults.putAll(results);
                    }
                } else {
                    finalResults = results;
                }
                mInstrumentationReporter.reportFinished(instr.mWatcher,
                        instr.mClass, resultCode, finalResults);
            }

            // Can't call out of the system process with a lock held, so post a message.
            if (instr.mUiAutomationConnection != null) {
                mAppOpsService.setAppOpsServiceDelegate(null);
                getPackageManagerInternalLocked().setCheckPermissionDelegate(null);
                mHandler.obtainMessage(SHUTDOWN_UI_AUTOMATION_CONNECTION_MSG,
                        instr.mUiAutomationConnection).sendToTarget();
            }
            instr.mFinished = true;
        }

        instr.removeProcess(app);
        app.setActiveInstrumentation(null);

        forceStopPackageLocked(app.info.packageName, -1, false, false, true, true, false, app.userId,
                "finished inst");
    }

    public void finishInstrumentation(IApplicationThread target,
            int resultCode, Bundle results) {
        int userId = UserHandle.getCallingUserId();
        // Refuse possible leaked file descriptors
        if (results != null && results.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            ProcessRecord app = getRecordForAppLocked(target);
            if (app == null) {
                Slog.w(TAG, "finishInstrumentation: no app for " + target);
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            finishInstrumentationLocked(app, resultCode, results);
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public StackInfo getFocusedStackInfo() throws RemoteException {
        return mActivityTaskManager.getFocusedStackInfo();
    }

    @Override
    public Configuration getConfiguration() {
        return mActivityTaskManager.getConfiguration();
    }

    @Override
    public void suppressResizeConfigChanges(boolean suppress) throws RemoteException {
        mActivityTaskManager.suppressResizeConfigChanges(suppress);
    }

    @Override
    public void updatePersistentConfiguration(Configuration values) {
        enforceCallingPermission(CHANGE_CONFIGURATION, "updatePersistentConfiguration()");
        enforceWriteSettingsPermission("updatePersistentConfiguration()");
        if (values == null) {
            throw new NullPointerException("Configuration must not be null");
        }

        int userId = UserHandle.getCallingUserId();

        mActivityTaskManager.updatePersistentConfiguration(values, userId);
    }

    private void enforceWriteSettingsPermission(String func) {
        int uid = Binder.getCallingUid();
        if (uid == ROOT_UID) {
            return;
        }

        if (Settings.checkAndNoteWriteSettingsOperation(mContext, uid,
                Settings.getPackageNameForUid(mContext, uid), false)) {
            return;
        }

        String msg = "Permission Denial: " + func + " from pid="
                + Binder.getCallingPid()
                + ", uid=" + uid
                + " requires " + android.Manifest.permission.WRITE_SETTINGS;
        Slog.w(TAG, msg);
        throw new SecurityException(msg);
    }

    @Override
    public boolean updateConfiguration(Configuration values) {
        return mActivityTaskManager.updateConfiguration(values);
    }

    @Override
    public int getLaunchedFromUid(IBinder activityToken) {
        return mActivityTaskManager.getLaunchedFromUid(activityToken);
    }

    public String getLaunchedFromPackage(IBinder activityToken) {
        return mActivityTaskManager.getLaunchedFromPackage(activityToken);
    }

    // =========================================================
    // LIFETIME MANAGEMENT
    // =========================================================

    // Returns whether the app is receiving broadcast.
    // If receiving, fetch all broadcast queues which the app is
    // the current [or imminent] receiver on.
    boolean isReceivingBroadcastLocked(ProcessRecord app,
            ArraySet<BroadcastQueue> receivingQueues) {
        final int N = app.curReceivers.size();
        if (N > 0) {
            for (int i = 0; i < N; i++) {
                receivingQueues.add(app.curReceivers.valueAt(i).queue);
            }
            return true;
        }

        // It's not the current receiver, but it might be starting up to become one
        for (BroadcastQueue queue : mBroadcastQueues) {
            final BroadcastRecord r = queue.mPendingBroadcast;
            if (r != null && r.curApp == app) {
                // found it; report which queue it's in
                receivingQueues.add(queue);
            }
        }

        return !receivingQueues.isEmpty();
    }

    Association startAssociationLocked(int sourceUid, String sourceProcess, int sourceState,
            int targetUid, long targetVersionCode, ComponentName targetComponent,
            String targetProcess) {
        if (!mTrackingAssociations) {
            return null;
        }
        ArrayMap<ComponentName, SparseArray<ArrayMap<String, Association>>> components
                = mAssociations.get(targetUid);
        if (components == null) {
            components = new ArrayMap<>();
            mAssociations.put(targetUid, components);
        }
        SparseArray<ArrayMap<String, Association>> sourceUids = components.get(targetComponent);
        if (sourceUids == null) {
            sourceUids = new SparseArray<>();
            components.put(targetComponent, sourceUids);
        }
        ArrayMap<String, Association> sourceProcesses = sourceUids.get(sourceUid);
        if (sourceProcesses == null) {
            sourceProcesses = new ArrayMap<>();
            sourceUids.put(sourceUid, sourceProcesses);
        }
        Association ass = sourceProcesses.get(sourceProcess);
        if (ass == null) {
            ass = new Association(sourceUid, sourceProcess, targetUid, targetComponent,
                    targetProcess);
            sourceProcesses.put(sourceProcess, ass);
        }
        ass.mCount++;
        ass.mNesting++;
        if (ass.mNesting == 1) {
            ass.mStartTime = ass.mLastStateUptime = SystemClock.uptimeMillis();
            ass.mLastState = sourceState;
        }
        return ass;
    }

    void stopAssociationLocked(int sourceUid, String sourceProcess, int targetUid,
            long targetVersionCode, ComponentName targetComponent, String targetProcess) {
        if (!mTrackingAssociations) {
            return;
        }
        ArrayMap<ComponentName, SparseArray<ArrayMap<String, Association>>> components
                = mAssociations.get(targetUid);
        if (components == null) {
            return;
        }
        SparseArray<ArrayMap<String, Association>> sourceUids = components.get(targetComponent);
        if (sourceUids == null) {
            return;
        }
        ArrayMap<String, Association> sourceProcesses = sourceUids.get(sourceUid);
        if (sourceProcesses == null) {
            return;
        }
        Association ass = sourceProcesses.get(sourceProcess);
        if (ass == null || ass.mNesting <= 0) {
            return;
        }
        ass.mNesting--;
        if (ass.mNesting == 0) {
            long uptime = SystemClock.uptimeMillis();
            ass.mTime += uptime - ass.mStartTime;
            ass.mStateTimes[ass.mLastState-ActivityManager.MIN_PROCESS_STATE]
                    += uptime - ass.mLastStateUptime;
            ass.mLastState = ActivityManager.MAX_PROCESS_STATE + 2;
        }
    }

    void noteUidProcessState(final int uid, final int state) {
        mBatteryStatsService.noteUidProcessState(uid, state);
        mAppOpsService.updateUidProcState(uid, state);
        if (mTrackingAssociations) {
            for (int i1=0, N1=mAssociations.size(); i1<N1; i1++) {
                ArrayMap<ComponentName, SparseArray<ArrayMap<String, Association>>> targetComponents
                        = mAssociations.valueAt(i1);
                for (int i2=0, N2=targetComponents.size(); i2<N2; i2++) {
                    SparseArray<ArrayMap<String, Association>> sourceUids
                            = targetComponents.valueAt(i2);
                    ArrayMap<String, Association> sourceProcesses = sourceUids.get(uid);
                    if (sourceProcesses != null) {
                        for (int i4=0, N4=sourceProcesses.size(); i4<N4; i4++) {
                            Association ass = sourceProcesses.valueAt(i4);
                            if (ass.mNesting >= 1) {
                                // currently associated
                                long uptime = SystemClock.uptimeMillis();
                                ass.mStateTimes[ass.mLastState-ActivityManager.MIN_PROCESS_STATE]
                                        += uptime - ass.mLastStateUptime;
                                ass.mLastState = state;
                                ass.mLastStateUptime = uptime;
                            }
                        }
                    }
                }
            }
        }
    }

    private static final class RecordPssRunnable implements Runnable {
        private final ActivityManagerService mService;
        private final ProcessRecord mProc;
        private final File mHeapdumpFile;

        RecordPssRunnable(ActivityManagerService service, ProcessRecord proc, File heapdumpFile) {
            this.mService = service;
            this.mProc = proc;
            this.mHeapdumpFile = heapdumpFile;
        }

        @Override
        public void run() {
            mService.revokeUriPermission(ActivityThread.currentActivityThread()
                            .getApplicationThread(),
                    null, DumpHeapActivity.JAVA_URI,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    UserHandle.myUserId());
            ParcelFileDescriptor fd = null;
            try {
                mHeapdumpFile.delete();
                fd = ParcelFileDescriptor.open(mHeapdumpFile,
                        ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE
                        | ParcelFileDescriptor.MODE_WRITE_ONLY
                        | ParcelFileDescriptor.MODE_APPEND);
                IApplicationThread thread = mProc.thread;
                if (thread != null) {
                    try {
                        if (DEBUG_PSS) {
                            Slog.d(TAG_PSS, "Requesting dump heap from "
                                    + mProc + " to " + mHeapdumpFile);
                        }
                        thread.dumpHeap(/* managed= */ true,
                                /* mallocInfo= */ false, /* runGc= */ false,
                                mHeapdumpFile.toString(), fd,
                                /* finishCallback= */ null);
                    } catch (RemoteException e) {
                    }
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } finally {
                if (fd != null) {
                    try {
                        fd.close();
                    } catch (IOException e) {
                    }
                }
            }
        }
    }

    /**
     * Record new PSS sample for a process.
     */
    void recordPssSampleLocked(ProcessRecord proc, int procState, long pss, long uss, long swapPss,
            long rss, int statType, long pssDuration, long now) {
        EventLogTags.writeAmPss(proc.pid, proc.uid, proc.processName, pss * 1024, uss * 1024,
                swapPss * 1024, rss * 1024, statType, procState, pssDuration);
        proc.lastPssTime = now;
        proc.baseProcessTracker.addPss(
                pss, uss, rss, true, statType, pssDuration, proc.pkgList.mPkgList);
        for (int ipkg = proc.pkgList.mPkgList.size() - 1; ipkg >= 0; ipkg--) {
            ProcessStats.ProcessStateHolder holder = proc.pkgList.valueAt(ipkg);
            StatsLog.write(StatsLog.PROCESS_MEMORY_STAT_REPORTED,
                    proc.info.uid,
                    holder.state.getName(),
                    holder.state.getPackage(),
                    pss, uss, rss, statType, pssDuration,
                    holder.appVersion);
        }
        if (DEBUG_PSS) Slog.d(TAG_PSS,
                "pss of " + proc.toShortString() + ": " + pss + " lastPss=" + proc.lastPss
                + " state=" + ProcessList.makeProcStateString(procState));
        if (proc.initialIdlePss == 0) {
            proc.initialIdlePss = pss;
        }
        proc.lastPss = pss;
        proc.lastSwapPss = swapPss;
        if (procState >= ActivityManager.PROCESS_STATE_HOME) {
            proc.lastCachedPss = pss;
            proc.lastCachedSwapPss = swapPss;
        }

        final SparseArray<Pair<Long, String>> watchUids
                = mMemWatchProcesses.getMap().get(proc.processName);
        Long check = null;
        if (watchUids != null) {
            Pair<Long, String> val = watchUids.get(proc.uid);
            if (val == null) {
                val = watchUids.get(0);
            }
            if (val != null) {
                check = val.first;
            }
        }
        if (check != null) {
            if ((pss * 1024) >= check && proc.thread != null && mMemWatchDumpProcName == null) {
                boolean isDebuggable = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
                if (!isDebuggable) {
                    if ((proc.info.flags&ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                        isDebuggable = true;
                    }
                }
                if (isDebuggable) {
                    Slog.w(TAG, "Process " + proc + " exceeded pss limit " + check + "; reporting");
                    final ProcessRecord myProc = proc;
                    final File heapdumpFile = DumpHeapProvider.getJavaFile();
                    mMemWatchDumpProcName = proc.processName;
                    mMemWatchDumpFile = heapdumpFile.toString();
                    mMemWatchDumpPid = proc.pid;
                    mMemWatchDumpUid = proc.uid;
                    BackgroundThread.getHandler().post(
                            new RecordPssRunnable(this, myProc, DumpHeapProvider.getJavaFile()));
                } else {
                    Slog.w(TAG, "Process " + proc + " exceeded pss limit " + check
                            + ", but debugging not enabled");
                }
            }
        }
    }

    /**
     * Schedule PSS collection of a process.
     */
    boolean requestPssLocked(ProcessRecord proc, int procState) {
        if (mPendingPssProcesses.contains(proc)) {
            return false;
        }
        if (mPendingPssProcesses.size() == 0) {
            mBgHandler.sendEmptyMessage(COLLECT_PSS_BG_MSG);
        }
        if (DEBUG_PSS) Slog.d(TAG_PSS, "Requesting pss of: " + proc);
        proc.pssProcState = procState;
        proc.pssStatType = ProcessStats.ADD_PSS_INTERNAL_SINGLE;
        mPendingPssProcesses.add(proc);
        return true;
    }

    /**
     * Schedule PSS collection of all processes.
     */
    void requestPssAllProcsLocked(long now, boolean always, boolean memLowered) {
        if (!always) {
            if (now < (mLastFullPssTime +
                    (memLowered ? mConstants.FULL_PSS_LOWERED_INTERVAL
                            : mConstants.FULL_PSS_MIN_INTERVAL))) {
                return;
            }
        }
        if (DEBUG_PSS) Slog.d(TAG_PSS, "Requesting pss of all procs!  memLowered=" + memLowered);
        mLastFullPssTime = now;
        mFullPssPending = true;
        for (int i = mPendingPssProcesses.size() - 1; i >= 0; i--) {
            ProcessList.abortNextPssTime(mPendingPssProcesses.get(i).procStateMemTracker);;
        }
        mPendingPssProcesses.ensureCapacity(mProcessList.getLruSizeLocked());
        mPendingPssProcesses.clear();
        for (int i = mProcessList.getLruSizeLocked() - 1; i >= 0; i--) {
            ProcessRecord app = mProcessList.mLruProcesses.get(i);
            if (app.thread == null || app.getCurProcState() == PROCESS_STATE_NONEXISTENT) {
                continue;
            }
            if (memLowered || (always && now >
                            app.lastStateTime+ProcessList.PSS_SAFE_TIME_FROM_STATE_CHANGE)
                    || now > (app.lastStateTime+ProcessList.PSS_ALL_INTERVAL)) {
                app.pssProcState = app.setProcState;
                app.pssStatType = always ? ProcessStats.ADD_PSS_INTERNAL_ALL_POLL
                        : ProcessStats.ADD_PSS_INTERNAL_ALL_MEM;
                app.nextPssTime = ProcessList.computeNextPssTime(app.getCurProcState(),
                        app.procStateMemTracker, mTestPssMode, mAtmInternal.isSleeping(), now);
                mPendingPssProcesses.add(app);
            }
        }
        if (!mBgHandler.hasMessages(COLLECT_PSS_BG_MSG)) {
            mBgHandler.sendEmptyMessage(COLLECT_PSS_BG_MSG);
        }
    }

    public void setTestPssMode(boolean enabled) {
        synchronized (this) {
            mTestPssMode = enabled;
            if (enabled) {
                // Whenever we enable the mode, we want to take a snapshot all of current
                // process mem use.
                requestPssAllProcsLocked(SystemClock.uptimeMillis(), true, true);
            }
        }
    }

    /**
     * Ask a given process to GC right now.
     */
    final void performAppGcLocked(ProcessRecord app) {
        try {
            app.lastRequestedGc = SystemClock.uptimeMillis();
            if (app.thread != null) {
                if (app.reportLowMemory) {
                    app.reportLowMemory = false;
                    app.thread.scheduleLowMemory();
                } else {
                    app.thread.processInBackground();
                }
            }
        } catch (Exception e) {
            // whatever.
        }
    }

    /**
     * Returns true if things are idle enough to perform GCs.
     */
    private final boolean canGcNowLocked() {
        for (BroadcastQueue q : mBroadcastQueues) {
            if (!q.mParallelBroadcasts.isEmpty() || !q.mDispatcher.isEmpty()) {
                return false;
            }
        }
        return mAtmInternal.canGcNow();
    }

    /**
     * Perform GCs on all processes that are waiting for it, but only
     * if things are idle.
     */
    final void performAppGcsLocked() {
        final int N = mProcessesToGc.size();
        if (N <= 0) {
            return;
        }
        if (canGcNowLocked()) {
            while (mProcessesToGc.size() > 0) {
                ProcessRecord proc = mProcessesToGc.remove(0);
                if (proc.getCurRawAdj() > ProcessList.PERCEPTIBLE_APP_ADJ || proc.reportLowMemory) {
                    if ((proc.lastRequestedGc+mConstants.GC_MIN_INTERVAL)
                            <= SystemClock.uptimeMillis()) {
                        // To avoid spamming the system, we will GC processes one
                        // at a time, waiting a few seconds between each.
                        performAppGcLocked(proc);
                        scheduleAppGcsLocked();
                        return;
                    } else {
                        // It hasn't been long enough since we last GCed this
                        // process...  put it in the list to wait for its time.
                        addProcessToGcListLocked(proc);
                        break;
                    }
                }
            }

            scheduleAppGcsLocked();
        }
    }

    /**
     * If all looks good, perform GCs on all processes waiting for them.
     */
    final void performAppGcsIfAppropriateLocked() {
        if (canGcNowLocked()) {
            performAppGcsLocked();
            return;
        }
        // Still not idle, wait some more.
        scheduleAppGcsLocked();
    }

    /**
     * Schedule the execution of all pending app GCs.
     */
    final void scheduleAppGcsLocked() {
        mHandler.removeMessages(GC_BACKGROUND_PROCESSES_MSG);

        if (mProcessesToGc.size() > 0) {
            // Schedule a GC for the time to the next process.
            ProcessRecord proc = mProcessesToGc.get(0);
            Message msg = mHandler.obtainMessage(GC_BACKGROUND_PROCESSES_MSG);

            long when = proc.lastRequestedGc + mConstants.GC_MIN_INTERVAL;
            long now = SystemClock.uptimeMillis();
            if (when < (now+mConstants.GC_TIMEOUT)) {
                when = now + mConstants.GC_TIMEOUT;
            }
            mHandler.sendMessageAtTime(msg, when);
        }
    }

    /**
     * Add a process to the array of processes waiting to be GCed.  Keeps the
     * list in sorted order by the last GC time.  The process can't already be
     * on the list.
     */
    final void addProcessToGcListLocked(ProcessRecord proc) {
        boolean added = false;
        for (int i=mProcessesToGc.size()-1; i>=0; i--) {
            if (mProcessesToGc.get(i).lastRequestedGc <
                    proc.lastRequestedGc) {
                added = true;
                mProcessesToGc.add(i+1, proc);
                break;
            }
        }
        if (!added) {
            mProcessesToGc.add(0, proc);
        }
    }

    /**
     * Set up to ask a process to GC itself.  This will either do it
     * immediately, or put it on the list of processes to gc the next
     * time things are idle.
     */
    final void scheduleAppGcLocked(ProcessRecord app) {
        long now = SystemClock.uptimeMillis();
        if ((app.lastRequestedGc+mConstants.GC_MIN_INTERVAL) > now) {
            return;
        }
        if (!mProcessesToGc.contains(app)) {
            addProcessToGcListLocked(app);
            scheduleAppGcsLocked();
        }
    }

    final void checkExcessivePowerUsageLocked() {
        updateCpuStatsNow();

        BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
        boolean doCpuKills = true;
        if (mLastPowerCheckUptime == 0) {
            doCpuKills = false;
        }
        final long curUptime = SystemClock.uptimeMillis();
        final long uptimeSince = curUptime - mLastPowerCheckUptime;
        mLastPowerCheckUptime = curUptime;
        int i = mProcessList.mLruProcesses.size();
        while (i > 0) {
            i--;
            ProcessRecord app = mProcessList.mLruProcesses.get(i);
            if (app.setProcState >= ActivityManager.PROCESS_STATE_HOME) {
                if (app.lastCpuTime <= 0) {
                    continue;
                }
                long cputimeUsed = app.curCpuTime - app.lastCpuTime;
                if (DEBUG_POWER) {
                    StringBuilder sb = new StringBuilder(128);
                    sb.append("CPU for ");
                    app.toShortString(sb);
                    sb.append(": over ");
                    TimeUtils.formatDuration(uptimeSince, sb);
                    sb.append(" used ");
                    TimeUtils.formatDuration(cputimeUsed, sb);
                    sb.append(" (");
                    sb.append((cputimeUsed*100)/uptimeSince);
                    sb.append("%)");
                    Slog.i(TAG_POWER, sb.toString());
                }
                // If the process has used too much CPU over the last duration, the
                // user probably doesn't want this, so kill!
                if (doCpuKills && uptimeSince > 0) {
                    // What is the limit for this process?
                    int cpuLimit;
                    long checkDur = curUptime - app.getWhenUnimportant();
                    if (checkDur <= mConstants.POWER_CHECK_INTERVAL) {
                        cpuLimit = mConstants.POWER_CHECK_MAX_CPU_1;
                    } else if (checkDur <= (mConstants.POWER_CHECK_INTERVAL*2)
                            || app.setProcState <= ActivityManager.PROCESS_STATE_HOME) {
                        cpuLimit = mConstants.POWER_CHECK_MAX_CPU_2;
                    } else if (checkDur <= (mConstants.POWER_CHECK_INTERVAL*3)) {
                        cpuLimit = mConstants.POWER_CHECK_MAX_CPU_3;
                    } else {
                        cpuLimit = mConstants.POWER_CHECK_MAX_CPU_4;
                    }
                    if (((cputimeUsed*100)/uptimeSince) >= cpuLimit) {
                        synchronized (stats) {
                            stats.reportExcessiveCpuLocked(app.info.uid, app.processName,
                                    uptimeSince, cputimeUsed);
                        }
                        app.kill("excessive cpu " + cputimeUsed + " during " + uptimeSince
                                + " dur=" + checkDur + " limit=" + cpuLimit, true);
                        app.baseProcessTracker.reportExcessiveCpu(app.pkgList.mPkgList);
                        for (int ipkg = app.pkgList.size() - 1; ipkg >= 0; ipkg--) {
                            ProcessStats.ProcessStateHolder holder = app.pkgList.valueAt(ipkg);
                            StatsLog.write(StatsLog.EXCESSIVE_CPU_USAGE_REPORTED,
                                    app.info.uid,
                                    holder.state.getName(),
                                    holder.state.getPackage(),
                                    holder.appVersion);
                        }
                    }
                }
                app.lastCpuTime = app.curCpuTime;
            }
        }
    }

    private boolean isEphemeralLocked(int uid) {
        String packages[] = mContext.getPackageManager().getPackagesForUid(uid);
        if (packages == null || packages.length != 1) { // Ephemeral apps cannot share uid
            return false;
        }
        return getPackageManagerInternalLocked().isPackageEphemeral(UserHandle.getUserId(uid),
                packages[0]);
    }

    @VisibleForTesting
    final void enqueueUidChangeLocked(UidRecord uidRec, int uid, int change) {
        final UidRecord.ChangeItem pendingChange;
        if (uidRec == null || uidRec.pendingChange == null) {
            if (mPendingUidChanges.size() == 0) {
                if (DEBUG_UID_OBSERVERS) Slog.i(TAG_UID_OBSERVERS,
                        "*** Enqueueing dispatch uid changed!");
                mUiHandler.obtainMessage(DISPATCH_UIDS_CHANGED_UI_MSG).sendToTarget();
            }
            final int NA = mAvailUidChanges.size();
            if (NA > 0) {
                pendingChange = mAvailUidChanges.remove(NA-1);
                if (DEBUG_UID_OBSERVERS) Slog.i(TAG_UID_OBSERVERS,
                        "Retrieving available item: " + pendingChange);
            } else {
                pendingChange = new UidRecord.ChangeItem();
                if (DEBUG_UID_OBSERVERS) Slog.i(TAG_UID_OBSERVERS,
                        "Allocating new item: " + pendingChange);
            }
            if (uidRec != null) {
                uidRec.pendingChange = pendingChange;
                if ((change & UidRecord.CHANGE_GONE) != 0 && !uidRec.idle) {
                    // If this uid is going away, and we haven't yet reported it is gone,
                    // then do so now.
                    change |= UidRecord.CHANGE_IDLE;
                }
            } else if (uid < 0) {
                throw new IllegalArgumentException("No UidRecord or uid");
            }
            pendingChange.uidRecord = uidRec;
            pendingChange.uid = uidRec != null ? uidRec.uid : uid;
            mPendingUidChanges.add(pendingChange);
        } else {
            pendingChange = uidRec.pendingChange;
            // If there is no change in idle or active state, then keep whatever was pending.
            if ((change & (UidRecord.CHANGE_IDLE | UidRecord.CHANGE_ACTIVE)) == 0) {
                change |= (pendingChange.change & (UidRecord.CHANGE_IDLE
                        | UidRecord.CHANGE_ACTIVE));
            }
            // If there is no change in cached or uncached state, then keep whatever was pending.
            if ((change & (UidRecord.CHANGE_CACHED | UidRecord.CHANGE_UNCACHED)) == 0) {
                change |= (pendingChange.change & (UidRecord.CHANGE_CACHED
                        | UidRecord.CHANGE_UNCACHED));
            }
            // If this is a report of the UID being gone, then we shouldn't keep any previous
            // report of it being active or cached.  (That is, a gone uid is never active,
            // and never cached.)
            if ((change & UidRecord.CHANGE_GONE) != 0) {
                change &= ~(UidRecord.CHANGE_ACTIVE | UidRecord.CHANGE_CACHED);
                if (!uidRec.idle) {
                    // If this uid is going away, and we haven't yet reported it is gone,
                    // then do so now.
                    change |= UidRecord.CHANGE_IDLE;
                }
            }
        }
        pendingChange.change = change;
        pendingChange.processState = uidRec != null ? uidRec.setProcState : PROCESS_STATE_NONEXISTENT;
        pendingChange.ephemeral = uidRec != null ? uidRec.ephemeral : isEphemeralLocked(uid);
        pendingChange.procStateSeq = uidRec != null ? uidRec.curProcStateSeq : 0;
        if (uidRec != null) {
            uidRec.lastReportedChange = change;
            uidRec.updateLastDispatchedProcStateSeq(change);
        }

        // Directly update the power manager, since we sit on top of it and it is critical
        // it be kept in sync (so wake locks will be held as soon as appropriate).
        if (mLocalPowerManager != null) {
            // TO DO: dispatch cached/uncached changes here, so we don't need to report
            // all proc state changes.
            if ((change & UidRecord.CHANGE_ACTIVE) != 0) {
                mLocalPowerManager.uidActive(pendingChange.uid);
            }
            if ((change & UidRecord.CHANGE_IDLE) != 0) {
                mLocalPowerManager.uidIdle(pendingChange.uid);
            }
            if ((change & UidRecord.CHANGE_GONE) != 0) {
                mLocalPowerManager.uidGone(pendingChange.uid);
            } else {
                mLocalPowerManager.updateUidProcState(pendingChange.uid,
                        pendingChange.processState);
            }
        }
    }

    private void maybeUpdateProviderUsageStatsLocked(ProcessRecord app, String providerPkgName,
            String authority) {
        if (app == null) return;
        if (app.getCurProcState() <= ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND) {
            UserState userState = mUserController.getStartedUserState(app.userId);
            if (userState == null) return;
            final long now = SystemClock.elapsedRealtime();
            Long lastReported = userState.mProviderLastReportedFg.get(authority);
            if (lastReported == null || lastReported < now - 60 * 1000L) {
                if (mSystemReady) {
                    // Cannot touch the user stats if not system ready
                    mUsageStatsService.reportContentProviderUsage(
                            authority, providerPkgName, app.userId);
                }
                userState.mProviderLastReportedFg.put(authority, now);
            }
        }
    }

    final void setProcessTrackerStateLocked(ProcessRecord proc, int memFactor, long now) {
        if (proc.thread != null && proc.baseProcessTracker != null) {
            proc.baseProcessTracker.setState(
                    proc.getReportedProcState(), memFactor, now, proc.pkgList.mPkgList);
        }
    }

    @GuardedBy("this")
    final void updateProcessForegroundLocked(ProcessRecord proc, boolean isForeground,
            int fgServiceTypes, boolean oomAdj) {
        proc.setHasForegroundServices(isForeground, fgServiceTypes);

        final boolean hasFgServiceLocationType =
                (fgServiceTypes & ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) != 0;
        if (isForeground != proc.hasForegroundServices()
                || proc.hasLocationForegroundServices() != hasFgServiceLocationType) {
            ArrayList<ProcessRecord> curProcs = mForegroundPackages.get(proc.info.packageName,
                    proc.info.uid);
            if (isForeground) {
                if (curProcs == null) {
                    curProcs = new ArrayList<ProcessRecord>();
                    mForegroundPackages.put(proc.info.packageName, proc.info.uid, curProcs);
                }
                if (!curProcs.contains(proc)) {
                    curProcs.add(proc);
                    mBatteryStatsService.noteEvent(BatteryStats.HistoryItem.EVENT_FOREGROUND_START,
                            proc.info.packageName, proc.info.uid);
                }
            } else {
                if (curProcs != null) {
                    if (curProcs.remove(proc)) {
                        mBatteryStatsService.noteEvent(
                                BatteryStats.HistoryItem.EVENT_FOREGROUND_FINISH,
                                proc.info.packageName, proc.info.uid);
                        if (curProcs.size() <= 0) {
                            mForegroundPackages.remove(proc.info.packageName, proc.info.uid);
                        }
                    }
                }
            }
            if (oomAdj) {
                updateOomAdjLocked();
            }
        }

        if (proc.getForegroundServiceTypes() != fgServiceTypes) {
            proc.setReportedForegroundServiceTypes(fgServiceTypes);
            ProcessChangeItem item = enqueueProcessChangeItemLocked(proc.info.uid, proc.pid);
            item.changes = ProcessChangeItem.CHANGE_FOREGROUND_SERVICES;
            item.foregroundServiceTypes = fgServiceTypes;
        }
    }

    // TODO(b/111541062): This method is only used for updating OOM adjustments. We need to update
    // the logic there and in mBatteryStatsService to make them aware of multiple resumed activities
    ProcessRecord getTopAppLocked() {
        final WindowProcessController wpc = mAtmInternal != null ? mAtmInternal.getTopApp() : null;
        final ProcessRecord r = wpc != null ? (ProcessRecord) wpc.mOwner : null;
        String pkg;
        int uid;
        if (r != null) {
            pkg = r.processName;
            uid = r.info.uid;
        } else {
            pkg = null;
            uid = -1;
        }
        // Has the UID or resumed package name changed?
        if (uid != mCurResumedUid || (pkg != mCurResumedPackage
                && (pkg == null || !pkg.equals(mCurResumedPackage)))) {

            final long identity = Binder.clearCallingIdentity();
            try {
                if (mCurResumedPackage != null) {
                    mBatteryStatsService.noteEvent(BatteryStats.HistoryItem.EVENT_TOP_FINISH,
                            mCurResumedPackage, mCurResumedUid);
                }
                mCurResumedPackage = pkg;
                mCurResumedUid = uid;
                if (mCurResumedPackage != null) {
                    mBatteryStatsService.noteEvent(BatteryStats.HistoryItem.EVENT_TOP_START,
                            mCurResumedPackage, mCurResumedUid);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

        }
        return r;
    }

    /**
     * Update OomAdj for a specific process.
     * @param app The process to update
     * @param oomAdjAll If it's ok to call updateOomAdjLocked() for all running apps
     *                  if necessary, or skip.
     * @return whether updateOomAdjLocked(app) was successful.
     */
    @GuardedBy("this")
    final boolean updateOomAdjLocked(ProcessRecord app, boolean oomAdjAll) {
        return mOomAdjuster.updateOomAdjLocked(app, oomAdjAll);
    }

    static final class ProcStatsRunnable implements Runnable {
        private final ActivityManagerService mService;
        private final ProcessStatsService mProcessStats;

        ProcStatsRunnable(ActivityManagerService service, ProcessStatsService mProcessStats) {
            this.mService = service;
            this.mProcessStats = mProcessStats;
        }

        @Override public void run() {
            synchronized (mService) {
                mProcessStats.writeStateAsyncLocked();
            }
        }
    }

    @GuardedBy("this")
    final boolean updateLowMemStateLocked(int numCached, int numEmpty, int numTrimming) {
        final int N = mProcessList.getLruSizeLocked();
        final long now = SystemClock.uptimeMillis();
        // Now determine the memory trimming level of background processes.
        // Unfortunately we need to start at the back of the list to do this
        // properly.  We only do this if the number of background apps we
        // are managing to keep around is less than half the maximum we desire;
        // if we are keeping a good number around, we'll let them use whatever
        // memory they want.
        final int numCachedAndEmpty = numCached + numEmpty;
        int memFactor;
        if (numCached <= mConstants.CUR_TRIM_CACHED_PROCESSES
                && numEmpty <= mConstants.CUR_TRIM_EMPTY_PROCESSES) {
            if (numCachedAndEmpty <= ProcessList.TRIM_CRITICAL_THRESHOLD) {
                memFactor = ProcessStats.ADJ_MEM_FACTOR_CRITICAL;
            } else if (numCachedAndEmpty <= ProcessList.TRIM_LOW_THRESHOLD) {
                memFactor = ProcessStats.ADJ_MEM_FACTOR_LOW;
            } else {
                memFactor = ProcessStats.ADJ_MEM_FACTOR_MODERATE;
            }
        } else {
            memFactor = ProcessStats.ADJ_MEM_FACTOR_NORMAL;
        }
        // We always allow the memory level to go up (better).  We only allow it to go
        // down if we are in a state where that is allowed, *and* the total number of processes
        // has gone down since last time.
        if (DEBUG_OOM_ADJ) Slog.d(TAG_OOM_ADJ, "oom: memFactor=" + memFactor
                + " last=" + mLastMemoryLevel + " allowLow=" + mAllowLowerMemLevel
                + " numProcs=" + mProcessList.getLruSizeLocked() + " last=" + mLastNumProcesses);
        if (memFactor > mLastMemoryLevel) {
            if (!mAllowLowerMemLevel || mProcessList.getLruSizeLocked() >= mLastNumProcesses) {
                memFactor = mLastMemoryLevel;
                if (DEBUG_OOM_ADJ) Slog.d(TAG_OOM_ADJ, "Keeping last mem factor!");
            }
        }
        if (memFactor != mLastMemoryLevel) {
            EventLogTags.writeAmMemFactor(memFactor, mLastMemoryLevel);
            StatsLog.write(StatsLog.MEMORY_FACTOR_STATE_CHANGED, memFactor);
        }
        mLastMemoryLevel = memFactor;
        mLastNumProcesses = mProcessList.getLruSizeLocked();
        boolean allChanged = mProcessStats.setMemFactorLocked(
                memFactor, mAtmInternal != null ? !mAtmInternal.isSleeping() : true, now);
        final int trackerMemFactor = mProcessStats.getMemFactorLocked();
        if (memFactor != ProcessStats.ADJ_MEM_FACTOR_NORMAL) {
            if (mLowRamStartTime == 0) {
                mLowRamStartTime = now;
            }
            int step = 0;
            int fgTrimLevel;
            switch (memFactor) {
                case ProcessStats.ADJ_MEM_FACTOR_CRITICAL:
                    fgTrimLevel = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
                    break;
                case ProcessStats.ADJ_MEM_FACTOR_LOW:
                    fgTrimLevel = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW;
                    break;
                default:
                    fgTrimLevel = ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE;
                    break;
            }
            int factor = numTrimming/3;
            int minFactor = 2;
            if (mAtmInternal.getHomeProcess() != null) minFactor++;
            if (mAtmInternal.getPreviousProcess() != null) minFactor++;
            if (factor < minFactor) factor = minFactor;
            int curLevel = ComponentCallbacks2.TRIM_MEMORY_COMPLETE;
            for (int i=N-1; i>=0; i--) {
                ProcessRecord app = mProcessList.mLruProcesses.get(i);
                if (allChanged || app.procStateChanged) {
                    setProcessTrackerStateLocked(app, trackerMemFactor, now);
                    app.procStateChanged = false;
                }
                if (app.getCurProcState() >= ActivityManager.PROCESS_STATE_HOME
                        && !app.killedByAm) {
                    if (app.trimMemoryLevel < curLevel && app.thread != null) {
                        try {
                            if (DEBUG_SWITCH || DEBUG_OOM_ADJ) Slog.v(TAG_OOM_ADJ,
                                    "Trimming memory of " + app.processName + " to " + curLevel);
                            app.thread.scheduleTrimMemory(curLevel);
                        } catch (RemoteException e) {
                        }
                    }
                    app.trimMemoryLevel = curLevel;
                    step++;
                    if (step >= factor) {
                        step = 0;
                        switch (curLevel) {
                            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                                curLevel = ComponentCallbacks2.TRIM_MEMORY_MODERATE;
                                break;
                            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                                curLevel = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND;
                                break;
                        }
                    }
                } else if (app.getCurProcState() == ActivityManager.PROCESS_STATE_HEAVY_WEIGHT
                        && !app.killedByAm) {
                    if (app.trimMemoryLevel < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                            && app.thread != null) {
                        try {
                            if (DEBUG_SWITCH || DEBUG_OOM_ADJ) Slog.v(TAG_OOM_ADJ,
                                    "Trimming memory of heavy-weight " + app.processName
                                    + " to " + ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
                            app.thread.scheduleTrimMemory(
                                    ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
                        } catch (RemoteException e) {
                        }
                    }
                    app.trimMemoryLevel = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND;
                } else {
                    if ((app.getCurProcState() >= ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
                            || app.systemNoUi) && app.hasPendingUiClean()) {
                        // If this application is now in the background and it
                        // had done UI, then give it the special trim level to
                        // have it free UI resources.
                        final int level = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN;
                        if (app.trimMemoryLevel < level && app.thread != null) {
                            try {
                                if (DEBUG_SWITCH || DEBUG_OOM_ADJ) Slog.v(TAG_OOM_ADJ,
                                        "Trimming memory of bg-ui " + app.processName
                                        + " to " + level);
                                app.thread.scheduleTrimMemory(level);
                            } catch (RemoteException e) {
                            }
                        }
                        app.setPendingUiClean(false);
                    }
                    if (app.trimMemoryLevel < fgTrimLevel && app.thread != null) {
                        try {
                            if (DEBUG_SWITCH || DEBUG_OOM_ADJ) Slog.v(TAG_OOM_ADJ,
                                    "Trimming memory of fg " + app.processName
                                    + " to " + fgTrimLevel);
                            app.thread.scheduleTrimMemory(fgTrimLevel);
                        } catch (RemoteException e) {
                        }
                    }
                    app.trimMemoryLevel = fgTrimLevel;
                }
            }
        } else {
            if (mLowRamStartTime != 0) {
                mLowRamTimeSinceLastIdle += now - mLowRamStartTime;
                mLowRamStartTime = 0;
            }
            for (int i=N-1; i>=0; i--) {
                ProcessRecord app = mProcessList.mLruProcesses.get(i);
                if (allChanged || app.procStateChanged) {
                    setProcessTrackerStateLocked(app, trackerMemFactor, now);
                    app.procStateChanged = false;
                }
                if ((app.getCurProcState() >= ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
                        || app.systemNoUi) && app.hasPendingUiClean()) {
                    if (app.trimMemoryLevel < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                            && app.thread != null) {
                        try {
                            if (DEBUG_SWITCH || DEBUG_OOM_ADJ) Slog.v(TAG_OOM_ADJ,
                                    "Trimming memory of ui hidden " + app.processName
                                    + " to " + ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
                            app.thread.scheduleTrimMemory(
                                    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
                        } catch (RemoteException e) {
                        }
                    }
                    app.setPendingUiClean(false);
                }
                app.trimMemoryLevel = 0;
            }
        }
        return allChanged;
    }

    @GuardedBy("this")
    final void updateOomAdjLocked() {
        mOomAdjuster.updateOomAdjLocked();
    }

    @Override
    public void makePackageIdle(String packageName, int userId) {
        if (checkCallingPermission(android.Manifest.permission.FORCE_STOP_PACKAGES)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: makePackageIdle() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.FORCE_STOP_PACKAGES;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        final int callingPid = Binder.getCallingPid();
        userId = mUserController.handleIncomingUser(callingPid, Binder.getCallingUid(),
                userId, true, ALLOW_FULL_ONLY, "makePackageIdle", null);
        long callingId = Binder.clearCallingIdentity();
        synchronized(this) {
            try {
                IPackageManager pm = AppGlobals.getPackageManager();
                int pkgUid = -1;
                try {
                    pkgUid = pm.getPackageUid(packageName, MATCH_UNINSTALLED_PACKAGES
                            | MATCH_DEBUG_TRIAGED_MISSING, UserHandle.USER_SYSTEM);
                } catch (RemoteException e) {
                }
                if (pkgUid == -1) {
                    throw new IllegalArgumentException("Unknown package name " + packageName);
                }

                if (mLocalPowerManager != null) {
                    mLocalPowerManager.startUidChanges();
                }
                final int appId = UserHandle.getAppId(pkgUid);
                final int N = mProcessList.mActiveUids.size();
                for (int i = N - 1; i >= 0; i--) {
                    final UidRecord uidRec = mProcessList.mActiveUids.valueAt(i);
                    final long bgTime = uidRec.lastBackgroundTime;
                    if (bgTime > 0 && !uidRec.idle) {
                        if (UserHandle.getAppId(uidRec.uid) == appId) {
                            if (userId == UserHandle.USER_ALL ||
                                    userId == UserHandle.getUserId(uidRec.uid)) {
                                EventLogTags.writeAmUidIdle(uidRec.uid);
                                uidRec.idle = true;
                                uidRec.setIdle = true;
                                Slog.w(TAG, "Idling uid " + UserHandle.formatUid(uidRec.uid)
                                        + " from package " + packageName + " user " + userId);
                                doStopUidLocked(uidRec.uid, uidRec);
                            }
                        }
                    }
                }
            } finally {
                if (mLocalPowerManager != null) {
                    mLocalPowerManager.finishUidChanges();
                }
                Binder.restoreCallingIdentity(callingId);
            }
        }
    }

    /** Make the currently active UIDs idle after a certain grace period. */
    final void idleUids() {
        synchronized (this) {
            mOomAdjuster.idleUidsLocked();
        }
    }

    /**
     * Checks if any uid is coming from background to foreground or vice versa and if so, increments
     * the {@link UidRecord#curProcStateSeq} corresponding to that uid using global seq counter
     * {@link ProcessList#mProcStateSeqCounter} and notifies the app if it needs to block.
     */
    @VisibleForTesting
    @GuardedBy("this")
    void incrementProcStateSeqAndNotifyAppsLocked() {
        if (mWaitForNetworkTimeoutMs <= 0) {
            return;
        }
        // Used for identifying which uids need to block for network.
        ArrayList<Integer> blockingUids = null;
        for (int i = mProcessList.mActiveUids.size() - 1; i >= 0; --i) {
            final UidRecord uidRec = mProcessList.mActiveUids.valueAt(i);
            // If the network is not restricted for uid, then nothing to do here.
            if (!mInjector.isNetworkRestrictedForUid(uidRec.uid)) {
                continue;
            }
            if (!UserHandle.isApp(uidRec.uid) || !uidRec.hasInternetPermission) {
                continue;
            }
            // If process state is not changed, then there's nothing to do.
            if (uidRec.setProcState == uidRec.getCurProcState()) {
                continue;
            }
            final int blockState = getBlockStateForUid(uidRec);
            // No need to inform the app when the blockState is NETWORK_STATE_NO_CHANGE as
            // there's nothing the app needs to do in this scenario.
            if (blockState == NETWORK_STATE_NO_CHANGE) {
                continue;
            }
            synchronized (uidRec.networkStateLock) {
                uidRec.curProcStateSeq = ++mProcessList.mProcStateSeqCounter; // TODO: use method
                if (blockState == NETWORK_STATE_BLOCK) {
                    if (blockingUids == null) {
                        blockingUids = new ArrayList<>();
                    }
                    blockingUids.add(uidRec.uid);
                } else {
                    if (DEBUG_NETWORK) {
                        Slog.d(TAG_NETWORK, "uid going to background, notifying all blocking"
                                + " threads for uid: " + uidRec);
                    }
                    if (uidRec.waitingForNetwork) {
                        uidRec.networkStateLock.notifyAll();
                    }
                }
            }
        }

        // There are no uids that need to block, so nothing more to do.
        if (blockingUids == null) {
            return;
        }

        for (int i = mProcessList.mLruProcesses.size() - 1; i >= 0; --i) {
            final ProcessRecord app = mProcessList.mLruProcesses.get(i);
            if (!blockingUids.contains(app.uid)) {
                continue;
            }
            if (!app.killedByAm && app.thread != null) {
                final UidRecord uidRec = mProcessList.getUidRecordLocked(app.uid);
                try {
                    if (DEBUG_NETWORK) {
                        Slog.d(TAG_NETWORK, "Informing app thread that it needs to block: "
                                + uidRec);
                    }
                    if (uidRec != null) {
                        app.thread.setNetworkBlockSeq(uidRec.curProcStateSeq);
                    }
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    /**
     * Checks if the uid is coming from background to foreground or vice versa and returns
     * appropriate block state based on this.
     *
     * @return blockState based on whether the uid is coming from background to foreground or
     *         vice versa. If bg->fg or fg->bg, then {@link #NETWORK_STATE_BLOCK} or
     *         {@link #NETWORK_STATE_UNBLOCK} respectively, otherwise
     *         {@link #NETWORK_STATE_NO_CHANGE}.
     */
    @VisibleForTesting
    int getBlockStateForUid(UidRecord uidRec) {
        // Denotes whether uid's process state is currently allowed network access.
        final boolean isAllowed =
                isProcStateAllowedWhileIdleOrPowerSaveMode(uidRec.getCurProcState())
                || isProcStateAllowedWhileOnRestrictBackground(uidRec.getCurProcState());
        // Denotes whether uid's process state was previously allowed network access.
        final boolean wasAllowed = isProcStateAllowedWhileIdleOrPowerSaveMode(uidRec.setProcState)
                || isProcStateAllowedWhileOnRestrictBackground(uidRec.setProcState);

        // When the uid is coming to foreground, AMS should inform the app thread that it should
        // block for the network rules to get updated before launching an activity.
        if (!wasAllowed && isAllowed) {
            return NETWORK_STATE_BLOCK;
        }
        // When the uid is going to background, AMS should inform the app thread that if an
        // activity launch is blocked for the network rules to get updated, it should be unblocked.
        if (wasAllowed && !isAllowed) {
            return NETWORK_STATE_UNBLOCK;
        }
        return NETWORK_STATE_NO_CHANGE;
    }

    final void runInBackgroundDisabled(int uid) {
        synchronized (this) {
            UidRecord uidRec = mProcessList.getUidRecordLocked(uid);
            if (uidRec != null) {
                // This uid is actually running...  should it be considered background now?
                if (uidRec.idle) {
                    doStopUidLocked(uidRec.uid, uidRec);
                }
            } else {
                // This uid isn't actually running...  still send a report about it being "stopped".
                doStopUidLocked(uid, null);
            }
        }
    }

    @GuardedBy("this")
    final void doStopUidLocked(int uid, final UidRecord uidRec) {
        mServices.stopInBackgroundLocked(uid);
        enqueueUidChangeLocked(uidRec, uid, UidRecord.CHANGE_IDLE);
    }

    /**
     * Whitelists {@code targetUid} to temporarily bypass Power Save mode.
     */
    @GuardedBy("this")
    void tempWhitelistForPendingIntentLocked(int callerPid, int callerUid, int targetUid,
            long duration, String tag) {
        if (DEBUG_WHITELISTS) {
            Slog.d(TAG, "tempWhitelistForPendingIntentLocked(" + callerPid + ", " + callerUid + ", "
                    + targetUid + ", " + duration + ")");
        }

        synchronized (mPidsSelfLocked) {
            final ProcessRecord pr = mPidsSelfLocked.get(callerPid);
            if (pr == null) {
                Slog.w(TAG, "tempWhitelistForPendingIntentLocked() no ProcessRecord for pid "
                        + callerPid);
                return;
            }
            if (!pr.whitelistManager) {
                if (checkPermission(CHANGE_DEVICE_IDLE_TEMP_WHITELIST, callerPid, callerUid)
                        != PackageManager.PERMISSION_GRANTED) {
                    if (DEBUG_WHITELISTS) {
                        Slog.d(TAG, "tempWhitelistForPendingIntentLocked() for target " + targetUid
                                + ": pid " + callerPid + " is not allowed");
                    }
                    return;
                }
            }
        }

        tempWhitelistUidLocked(targetUid, duration, tag);
    }

    /**
     * Whitelists {@code targetUid} to temporarily bypass Power Save mode.
     */
    @GuardedBy("this")
    void tempWhitelistUidLocked(int targetUid, long duration, String tag) {
        mPendingTempWhitelist.put(targetUid, new PendingTempWhitelist(targetUid, duration, tag));
        setUidTempWhitelistStateLocked(targetUid, true);
        mUiHandler.obtainMessage(PUSH_TEMP_WHITELIST_UI_MSG).sendToTarget();
    }

    void pushTempWhitelist() {
        final int N;
        final PendingTempWhitelist[] list;

        // First copy out the pending changes...  we need to leave them in the map for now,
        // in case someone needs to check what is coming up while we don't have the lock held.
        synchronized(this) {
            N = mPendingTempWhitelist.size();
            list = new PendingTempWhitelist[N];
            for (int i = 0; i < N; i++) {
                list[i] = mPendingTempWhitelist.valueAt(i);
            }
        }

        // Now safely dispatch changes to device idle controller.
        for (int i = 0; i < N; i++) {
            PendingTempWhitelist ptw = list[i];
            mLocalDeviceIdleController.addPowerSaveTempWhitelistAppDirect(ptw.targetUid,
                    ptw.duration, true, ptw.tag);
        }

        // And now we can safely remove them from the map.
        synchronized(this) {
            for (int i = 0; i < N; i++) {
                PendingTempWhitelist ptw = list[i];
                int index = mPendingTempWhitelist.indexOfKey(ptw.targetUid);
                if (index >= 0 && mPendingTempWhitelist.valueAt(index) == ptw) {
                    mPendingTempWhitelist.removeAt(index);
                }
            }
        }
    }

    @GuardedBy("this")
    final void setAppIdTempWhitelistStateLocked(int appId, boolean onWhitelist) {
        mOomAdjuster.setAppIdTempWhitelistStateLocked(appId, onWhitelist);
    }

    @GuardedBy("this")
    final void setUidTempWhitelistStateLocked(int uid, boolean onWhitelist) {
        mOomAdjuster.setUidTempWhitelistStateLocked(uid, onWhitelist);
    }

    final void trimApplications() {
        synchronized (this) {
            trimApplicationsLocked();
        }
    }

    @GuardedBy("this")
    final void trimApplicationsLocked() {
        // First remove any unused application processes whose package
        // has been removed.
        for (int i = mProcessList.mRemovedProcesses.size() - 1; i >= 0; i--) {
            final ProcessRecord app = mProcessList.mRemovedProcesses.get(i);
            if (!app.hasActivitiesOrRecentTasks()
                    && app.curReceivers.isEmpty() && app.services.size() == 0) {
                Slog.i(
                    TAG, "Exiting empty application process "
                    + app.toShortString() + " ("
                    + (app.thread != null ? app.thread.asBinder() : null)
                    + ")\n");
                if (app.pid > 0 && app.pid != MY_PID) {
                    app.kill("empty", false);
                } else if (app.thread != null) {
                    try {
                        app.thread.scheduleExit();
                    } catch (Exception e) {
                        // Ignore exceptions.
                    }
                }
                cleanUpApplicationRecordLocked(app, false, true, -1, false /*replacingPid*/);
                mProcessList.mRemovedProcesses.remove(i);

                if (app.isPersistent()) {
                    addAppLocked(app.info, null, false, null /* ABI override */);
                }
            }
        }

        // Now update the oom adj for all processes. Don't skip this, since other callers
        // might be depending on it.
        updateOomAdjLocked();
    }

    /** This method sends the specified signal to each of the persistent apps */
    public void signalPersistentProcesses(int sig) throws RemoteException {
        if (sig != SIGNAL_USR1) {
            throw new SecurityException("Only SIGNAL_USR1 is allowed");
        }

        synchronized (this) {
            if (checkCallingPermission(android.Manifest.permission.SIGNAL_PERSISTENT_PROCESSES)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires permission "
                        + android.Manifest.permission.SIGNAL_PERSISTENT_PROCESSES);
            }

            for (int i = mProcessList.mLruProcesses.size() - 1 ; i >= 0 ; i--) {
                ProcessRecord r = mProcessList.mLruProcesses.get(i);
                if (r.thread != null && r.isPersistent()) {
                    sendSignal(r.pid, sig);
                }
            }
        }
    }

    private void stopProfilerLocked(ProcessRecord proc, int profileType) {
        if (proc == null || proc == mProfileData.getProfileProc()) {
            proc = mProfileData.getProfileProc();
            profileType = mProfileType;
            clearProfilerLocked();
        }
        if (proc == null) {
            return;
        }
        try {
            proc.thread.profilerControl(false, null, profileType);
        } catch (RemoteException e) {
            throw new IllegalStateException("Process disappeared");
        }
    }

    void clearProfilerLocked() {
        if (mProfileData.getProfilerInfo() != null
                && mProfileData.getProfilerInfo().profileFd != null) {
            try {
                mProfileData.getProfilerInfo().profileFd.close();
            } catch (IOException e) {
            }
        }
        mProfileData.setProfileApp(null);
        mProfileData.setProfileProc(null);
        mProfileData.setProfilerInfo(null);
    }

    public boolean profileControl(String process, int userId, boolean start,
            ProfilerInfo profilerInfo, int profileType) throws RemoteException {

        try {
            synchronized (this) {
                // note: hijacking SET_ACTIVITY_WATCHER, but should be changed to
                // its own permission.
                if (checkCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER)
                        != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Requires permission "
                            + android.Manifest.permission.SET_ACTIVITY_WATCHER);
                }

                if (start && (profilerInfo == null || profilerInfo.profileFd == null)) {
                    throw new IllegalArgumentException("null profile info or fd");
                }

                ProcessRecord proc = null;
                if (process != null) {
                    proc = findProcessLocked(process, userId, "profileControl");
                }

                if (start && (proc == null || proc.thread == null)) {
                    throw new IllegalArgumentException("Unknown process: " + process);
                }

                if (start) {
                    stopProfilerLocked(null, 0);
                    setProfileApp(proc.info, proc.processName, profilerInfo);
                    mProfileData.setProfileProc(proc);
                    mProfileType = profileType;
                    ParcelFileDescriptor fd = profilerInfo.profileFd;
                    try {
                        fd = fd.dup();
                    } catch (IOException e) {
                        fd = null;
                    }
                    profilerInfo.profileFd = fd;
                    proc.thread.profilerControl(start, profilerInfo, profileType);
                    fd = null;
                    try {
                        mProfileData.getProfilerInfo().profileFd.close();
                    } catch (IOException e) {
                    }
                    mProfileData.getProfilerInfo().profileFd = null;

                    if (proc.pid == MY_PID) {
                        // When profiling the system server itself, avoid closing the file
                        // descriptor, as profilerControl will not create a copy.
                        // Note: it is also not correct to just set profileFd to null, as the
                        //       whole ProfilerInfo instance is passed down!
                        profilerInfo = null;
                    }
                } else {
                    stopProfilerLocked(proc, profileType);
                    if (profilerInfo != null && profilerInfo.profileFd != null) {
                        try {
                            profilerInfo.profileFd.close();
                        } catch (IOException e) {
                        }
                    }
                }

                return true;
            }
        } catch (RemoteException e) {
            throw new IllegalStateException("Process disappeared");
        } finally {
            if (profilerInfo != null && profilerInfo.profileFd != null) {
                try {
                    profilerInfo.profileFd.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private ProcessRecord findProcessLocked(String process, int userId, String callName) {
        userId = mUserController.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, true, ALLOW_FULL_ONLY, callName, null);
        ProcessRecord proc = null;
        try {
            int pid = Integer.parseInt(process);
            synchronized (mPidsSelfLocked) {
                proc = mPidsSelfLocked.get(pid);
            }
        } catch (NumberFormatException e) {
        }

        if (proc == null) {
            ArrayMap<String, SparseArray<ProcessRecord>> all
                    = mProcessList.mProcessNames.getMap();
            SparseArray<ProcessRecord> procs = all.get(process);
            if (procs != null && procs.size() > 0) {
                proc = procs.valueAt(0);
                if (userId != UserHandle.USER_ALL && proc.userId != userId) {
                    for (int i=1; i<procs.size(); i++) {
                        ProcessRecord thisProc = procs.valueAt(i);
                        if (thisProc.userId == userId) {
                            proc = thisProc;
                            break;
                        }
                    }
                }
            }
        }

        return proc;
    }

    @Override
    public boolean dumpHeap(String process, int userId, boolean managed, boolean mallocInfo,
            boolean runGc, String path, ParcelFileDescriptor fd, RemoteCallback finishCallback) {

        try {
            synchronized (this) {
                // note: hijacking SET_ACTIVITY_WATCHER, but should be changed to
                // its own permission (same as profileControl).
                if (checkCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER)
                        != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Requires permission "
                            + android.Manifest.permission.SET_ACTIVITY_WATCHER);
                }

                if (fd == null) {
                    throw new IllegalArgumentException("null fd");
                }

                ProcessRecord proc = findProcessLocked(process, userId, "dumpHeap");
                if (proc == null || proc.thread == null) {
                    throw new IllegalArgumentException("Unknown process: " + process);
                }

                boolean isDebuggable = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
                if (!isDebuggable) {
                    if ((proc.info.flags&ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
                        throw new SecurityException("Process not debuggable: " + proc);
                    }
                }

                proc.thread.dumpHeap(managed, mallocInfo, runGc, path, fd, finishCallback);
                fd = null;
                return true;
            }
        } catch (RemoteException e) {
            throw new IllegalStateException("Process disappeared");
        } finally {
            if (fd != null) {
                try {
                    fd.close();
                } catch (IOException e) {
                }
            }
        }
    }

    @Override
    public void setDumpHeapDebugLimit(String processName, int uid, long maxMemSize,
            String reportPackage) {
        if (processName != null) {
            enforceCallingPermission(android.Manifest.permission.SET_DEBUG_APP,
                    "setDumpHeapDebugLimit()");
        } else {
            synchronized (mPidsSelfLocked) {
                ProcessRecord proc = mPidsSelfLocked.get(Binder.getCallingPid());
                if (proc == null) {
                    throw new SecurityException("No process found for calling pid "
                            + Binder.getCallingPid());
                }
                if (!Build.IS_DEBUGGABLE
                        && (proc.info.flags&ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
                    throw new SecurityException("Not running a debuggable build");
                }
                processName = proc.processName;
                uid = proc.uid;
                if (reportPackage != null && !proc.pkgList.containsKey(reportPackage)) {
                    throw new SecurityException("Package " + reportPackage + " is not running in "
                            + proc);
                }
            }
        }
        synchronized (this) {
            if (maxMemSize > 0) {
                mMemWatchProcesses.put(processName, uid, new Pair(maxMemSize, reportPackage));
            } else {
                if (uid != 0) {
                    mMemWatchProcesses.remove(processName, uid);
                } else {
                    mMemWatchProcesses.getMap().remove(processName);
                }
            }
        }
    }

    @Override
    public void dumpHeapFinished(String path) {
        synchronized (this) {
            if (Binder.getCallingPid() != mMemWatchDumpPid) {
                Slog.w(TAG, "dumpHeapFinished: Calling pid " + Binder.getCallingPid()
                        + " does not match last pid " + mMemWatchDumpPid);
                return;
            }
            if (mMemWatchDumpFile == null || !mMemWatchDumpFile.equals(path)) {
                Slog.w(TAG, "dumpHeapFinished: Calling path " + path
                        + " does not match last path " + mMemWatchDumpFile);
                return;
            }
            if (DEBUG_PSS) Slog.d(TAG_PSS, "Dump heap finished for " + path);
            mHandler.sendEmptyMessage(POST_DUMP_HEAP_NOTIFICATION_MSG);

            // Forced gc to clean up the remnant hprof fd.
            Runtime.getRuntime().gc();
        }
    }

    /** In this method we try to acquire our lock to make sure that we have not deadlocked */
    public void monitor() {
        synchronized (this) { }
    }

    void onCoreSettingsChange(Bundle settings) {
        synchronized (this) {
            mProcessList.updateCoreSettingsLocked(settings);
        }
    }

    // Multi-user methods

    /**
     * Start user, if its not already running, but don't bring it to foreground.
     */
    @Override
    public boolean startUserInBackground(final int userId) {
        return startUserInBackgroundWithListener(userId, null);
    }

    @Override
    public boolean startUserInBackgroundWithListener(final int userId,
                @Nullable IProgressListener unlockListener) {
        return mUserController.startUser(userId, /* foreground */ false, unlockListener);
    }

    @Override
    public boolean startUserInForegroundWithListener(final int userId,
            @Nullable IProgressListener unlockListener) {
        // Permission check done inside UserController.
        return mUserController.startUser(userId, /* foreground */ true, unlockListener);
    }

    @Override
    public boolean unlockUser(int userId, byte[] token, byte[] secret, IProgressListener listener) {
        return mUserController.unlockUser(userId, token, secret, listener);
    }

    @Override
    public boolean switchUser(final int targetUserId) {
        return mUserController.switchUser(targetUserId);
    }

    @Override
    public int stopUser(final int userId, boolean force, final IStopUserCallback callback) {
        return mUserController.stopUser(userId, force, callback);
    }

    @Override
    public UserInfo getCurrentUser() {
        return mUserController.getCurrentUser();
    }

    String getStartedUserState(int userId) {
        final UserState userState = mUserController.getStartedUserState(userId);
        return UserState.stateToString(userState.state);
    }

    @Override
    public boolean isUserRunning(int userId, int flags) {
        if (!mUserController.isSameProfileGroup(userId, UserHandle.getCallingUserId())
                && checkCallingPermission(INTERACT_ACROSS_USERS)
                    != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: isUserRunning() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + INTERACT_ACROSS_USERS;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        return mUserController.isUserRunning(userId, flags);
    }

    @Override
    public int[] getRunningUserIds() {
        if (checkCallingPermission(INTERACT_ACROSS_USERS)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: isUserRunning() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + INTERACT_ACROSS_USERS;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        return mUserController.getStartedUserArray();
    }

    @Override
    public void registerUserSwitchObserver(IUserSwitchObserver observer, String name) {
        mUserController.registerUserSwitchObserver(observer, name);
    }

    @Override
    public void unregisterUserSwitchObserver(IUserSwitchObserver observer) {
        mUserController.unregisterUserSwitchObserver(observer);
    }

    ApplicationInfo getAppInfoForUser(ApplicationInfo info, int userId) {
        if (info == null) return null;
        ApplicationInfo newInfo = new ApplicationInfo(info);
        newInfo.initForUser(userId);
        return newInfo;
    }

    public boolean isUserStopped(int userId) {
        return mUserController.getStartedUserState(userId) == null;
    }

    ActivityInfo getActivityInfoForUser(ActivityInfo aInfo, int userId) {
        if (aInfo == null
                || (userId < 1 && aInfo.applicationInfo.uid < UserHandle.PER_USER_RANGE)) {
            return aInfo;
        }

        ActivityInfo info = new ActivityInfo(aInfo);
        info.applicationInfo = getAppInfoForUser(info.applicationInfo, userId);
        return info;
    }

    private boolean processSanityChecksLocked(ProcessRecord process) {
        if (process == null || process.thread == null) {
            return false;
        }

        boolean isDebuggable = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
        if (!isDebuggable) {
            if ((process.info.flags&ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
                return false;
            }
        }

        return true;
    }

    public boolean startBinderTracking() throws RemoteException {
        synchronized (this) {
            mBinderTransactionTrackingEnabled = true;
            // TODO: hijacking SET_ACTIVITY_WATCHER, but should be changed to its own
            // permission (same as profileControl).
            if (checkCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires permission "
                        + android.Manifest.permission.SET_ACTIVITY_WATCHER);
            }

            for (int i = 0; i < mProcessList.mLruProcesses.size(); i++) {
                ProcessRecord process = mProcessList.mLruProcesses.get(i);
                if (!processSanityChecksLocked(process)) {
                    continue;
                }
                try {
                    process.thread.startBinderTracking();
                } catch (RemoteException e) {
                    Log.v(TAG, "Process disappared");
                }
            }
            return true;
        }
    }

    public boolean stopBinderTrackingAndDump(ParcelFileDescriptor fd) throws RemoteException {
        try {
            synchronized (this) {
                mBinderTransactionTrackingEnabled = false;
                // TODO: hijacking SET_ACTIVITY_WATCHER, but should be changed to its own
                // permission (same as profileControl).
                if (checkCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER)
                        != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Requires permission "
                            + android.Manifest.permission.SET_ACTIVITY_WATCHER);
                }

                if (fd == null) {
                    throw new IllegalArgumentException("null fd");
                }

                PrintWriter pw = new FastPrintWriter(new FileOutputStream(fd.getFileDescriptor()));
                pw.println("Binder transaction traces for all processes.\n");
                for (ProcessRecord process : mProcessList.mLruProcesses) {
                    if (!processSanityChecksLocked(process)) {
                        continue;
                    }

                    pw.println("Traces for process: " + process.processName);
                    pw.flush();
                    try {
                        TransferPipe tp = new TransferPipe();
                        try {
                            process.thread.stopBinderTrackingAndDump(tp.getWriteFd());
                            tp.go(fd.getFileDescriptor());
                        } finally {
                            tp.kill();
                        }
                    } catch (IOException e) {
                        pw.println("Failure while dumping IPC traces from " + process +
                                ".  Exception: " + e);
                        pw.flush();
                    } catch (RemoteException e) {
                        pw.println("Got a RemoteException while dumping IPC traces from " +
                                process + ".  Exception: " + e);
                        pw.flush();
                    }
                }
                fd = null;
                return true;
            }
        } finally {
            if (fd != null) {
                try {
                    fd.close();
                } catch (IOException e) {
                }
            }
        }
    }

    @VisibleForTesting
    public final class LocalService extends ActivityManagerInternal {
        @Override
        public String checkContentProviderAccess(String authority, int userId) {
            return ActivityManagerService.this.checkContentProviderAccess(authority, userId);
        }

        @Override
        public void onWakefulnessChanged(int wakefulness) {
            ActivityManagerService.this.onWakefulnessChanged(wakefulness);
        }

        @Override
        public boolean startIsolatedProcess(String entryPoint, String[] entryPointArgs,
                String processName, String abiOverride, int uid, Runnable crashHandler) {
            return ActivityManagerService.this.startIsolatedProcess(entryPoint, entryPointArgs,
                    processName, abiOverride, uid, crashHandler);
        }

        @Override
        public void killForegroundAppsForUser(int userHandle) {
            synchronized (ActivityManagerService.this) {
                final ArrayList<ProcessRecord> procs = new ArrayList<>();
                final int NP = mProcessList.mProcessNames.getMap().size();
                for (int ip = 0; ip < NP; ip++) {
                    final SparseArray<ProcessRecord> apps =
                            mProcessList.mProcessNames.getMap().valueAt(ip);
                    final int NA = apps.size();
                    for (int ia = 0; ia < NA; ia++) {
                        final ProcessRecord app = apps.valueAt(ia);
                        if (app.isPersistent()) {
                            // We don't kill persistent processes.
                            continue;
                        }
                        if (app.removed
                                || (app.userId == userHandle && app.hasForegroundActivities())) {
                            procs.add(app);
                        }
                    }
                }

                final int N = procs.size();
                for (int i = 0; i < N; i++) {
                    mProcessList.removeProcessLocked(procs.get(i), false, true, "kill all fg");
                }
            }
        }

        @Override
        public void setPendingIntentWhitelistDuration(IIntentSender target, IBinder whitelistToken,
                long duration) {
            if (!(target instanceof PendingIntentRecord)) {
                Slog.w(TAG, "markAsSentFromNotification(): not a PendingIntentRecord: " + target);
                return;
            }
            synchronized (ActivityManagerService.this) {
                ((PendingIntentRecord) target).setWhitelistDurationLocked(whitelistToken, duration);
            }
        }

        @Override
        public void setPendingIntentAllowBgActivityStarts(IIntentSender target,
                IBinder whitelistToken, int flags) {
            if (!(target instanceof PendingIntentRecord)) {
                Slog.w(TAG, "setPendingIntentAllowBgActivityStarts():"
                        + " not a PendingIntentRecord: " + target);
                return;
            }
            synchronized (ActivityManagerService.this) {
                ((PendingIntentRecord) target).setAllowBgActivityStarts(whitelistToken, flags);
            }
        }

        @Override
        public void setDeviceIdleWhitelist(int[] allAppids, int[] exceptIdleAppids) {
            synchronized (ActivityManagerService.this) {
                mDeviceIdleWhitelist = allAppids;
                mDeviceIdleExceptIdleWhitelist = exceptIdleAppids;
            }
        }

        @Override
        public void updateDeviceIdleTempWhitelist(int[] appids, int changingAppId, boolean adding) {
            synchronized (ActivityManagerService.this) {
                mDeviceIdleTempWhitelist = appids;
                setAppIdTempWhitelistStateLocked(changingAppId, adding);
            }
        }

        @Override
        public int getUidProcessState(int uid) {
            return getUidState(uid);
        }

        @Override
        public boolean isSystemReady() {
            // no need to synchronize(this) just to read & return the value
            return mSystemReady;
        }

        /**
         * Sets if the given pid has an overlay UI or not.
         *
         * @param pid The pid we are setting overlay UI for.
         * @param hasOverlayUi True if the process has overlay UI.
         * @see android.view.WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
         */
        @Override
        public void setHasOverlayUi(int pid, boolean hasOverlayUi) {
            synchronized (ActivityManagerService.this) {
                final ProcessRecord pr;
                synchronized (mPidsSelfLocked) {
                    pr = mPidsSelfLocked.get(pid);
                    if (pr == null) {
                        Slog.w(TAG, "setHasOverlayUi called on unknown pid: " + pid);
                        return;
                    }
                }
                if (pr.hasOverlayUi() == hasOverlayUi) {
                    return;
                }
                pr.setHasOverlayUi(hasOverlayUi);
                //Slog.i(TAG, "Setting hasOverlayUi=" + pr.hasOverlayUi + " for pid=" + pid);
                updateOomAdjLocked(pr, true);
            }
        }

        /**
         * Called after the network policy rules are updated by
         * {@link com.android.server.net.NetworkPolicyManagerService} for a specific {@param uid}
         * and {@param procStateSeq}.
         */
        @Override
        public void notifyNetworkPolicyRulesUpdated(int uid, long procStateSeq) {
            if (DEBUG_NETWORK) {
                Slog.d(TAG_NETWORK, "Got update from NPMS for uid: "
                        + uid + " seq: " + procStateSeq);
            }
            UidRecord record;
            synchronized (ActivityManagerService.this) {
                record = mProcessList.getUidRecordLocked(uid);
                if (record == null) {
                    if (DEBUG_NETWORK) {
                        Slog.d(TAG_NETWORK, "No active uidRecord for uid: " + uid
                                + " procStateSeq: " + procStateSeq);
                    }
                    return;
                }
            }
            synchronized (record.networkStateLock) {
                if (record.lastNetworkUpdatedProcStateSeq >= procStateSeq) {
                    if (DEBUG_NETWORK) {
                        Slog.d(TAG_NETWORK, "procStateSeq: " + procStateSeq + " has already"
                                + " been handled for uid: " + uid);
                    }
                    return;
                }
                record.lastNetworkUpdatedProcStateSeq = procStateSeq;
                if (record.curProcStateSeq > procStateSeq) {
                    if (DEBUG_NETWORK) {
                        Slog.d(TAG_NETWORK, "No need to handle older seq no., Uid: " + uid
                                + ", curProcstateSeq: " + record.curProcStateSeq
                                + ", procStateSeq: " + procStateSeq);
                    }
                    return;
                }
                if (record.waitingForNetwork) {
                    if (DEBUG_NETWORK) {
                        Slog.d(TAG_NETWORK, "Notifying all blocking threads for uid: " + uid
                                + ", procStateSeq: " + procStateSeq);
                    }
                    record.networkStateLock.notifyAll();
                }
            }
        }

        @Override
        public boolean isRuntimeRestarted() {
            return mSystemServiceManager.isRuntimeRestarted();
        }

        @Override
        public boolean canStartMoreUsers() {
            return mUserController.canStartMoreUsers();
        }

        @Override
        public void setSwitchingFromSystemUserMessage(String switchingFromSystemUserMessage) {
            mUserController.setSwitchingFromSystemUserMessage(switchingFromSystemUserMessage);
        }

        @Override
        public void setSwitchingToSystemUserMessage(String switchingToSystemUserMessage) {
            mUserController.setSwitchingToSystemUserMessage(switchingToSystemUserMessage);
        }

        @Override
        public int getMaxRunningUsers() {
            return mUserController.mMaxRunningUsers;
        }

        @Override
        public boolean isUidActive(int uid) {
            synchronized (ActivityManagerService.this) {
                return isUidActiveLocked(uid);
            }
        }

        @Override
        public void setRunningRemoteAnimation(int pid, boolean runningRemoteAnimation) {
            ActivityManagerService.this.setRunningRemoteAnimation(pid, runningRemoteAnimation);
        }

        @Override
        public List<ProcessMemoryState> getMemoryStateForProcesses() {
            List<ProcessMemoryState> processMemoryStates = new ArrayList<>();
            synchronized (mPidsSelfLocked) {
                for (int i = 0, size = mPidsSelfLocked.size(); i < size; i++) {
                    final ProcessRecord r = mPidsSelfLocked.valueAt(i);
                    final int pid = r.pid;
                    final int uid = r.uid;
                    final MemoryStat memoryStat = readMemoryStatFromFilesystem(uid, pid);
                    if (memoryStat == null) {
                        continue;
                    }
                    ProcessMemoryState processMemoryState =
                            new ProcessMemoryState(uid,
                                    r.processName,
                                    r.curAdj,
                                    memoryStat.pgfault,
                                    memoryStat.pgmajfault,
                                    memoryStat.rssInBytes,
                                    memoryStat.cacheInBytes,
                                    memoryStat.swapInBytes,
                                    memoryStat.startTimeNanos);
                    processMemoryStates.add(processMemoryState);
                }
            }
            return processMemoryStates;
        }

        @Override
        public List<ProcessMemoryHighWaterMark> getMemoryHighWaterMarkForProcesses() {
            List<ProcessMemoryHighWaterMark> results = new ArrayList<>();
            synchronized (mPidsSelfLocked) {
                for (int i = 0, size = mPidsSelfLocked.size(); i < size; i++) {
                    final ProcessRecord r = mPidsSelfLocked.valueAt(i);
                    final long rssHighWaterMarkInBytes = readRssHighWaterMarkFromProcfs(r.pid);
                    results.add(new ProcessMemoryHighWaterMark(r.uid, r.processName,
                            rssHighWaterMarkInBytes));
                }
            }
            return results;
        }

        @Override
        public int handleIncomingUser(int callingPid, int callingUid, int userId,
                boolean allowAll, int allowMode, String name, String callerPackage) {
            return mUserController.handleIncomingUser(callingPid, callingUid, userId, allowAll,
                    allowMode, name, callerPackage);
        }

        @Override
        public void enforceCallingPermission(String permission, String func) {
            ActivityManagerService.this.enforceCallingPermission(permission, func);
        }

        @Override
        public int getCurrentUserId() {
            return mUserController.getCurrentUserIdLU();
        }

        @Override
        public boolean isUserRunning(int userId, int flags) {
            // Holding am lock isn't required to call into user controller.
            return mUserController.isUserRunning(userId, flags);
        }

        @Override
        public void trimApplications() {
            ActivityManagerService.this.trimApplications();
        }

        public void killProcessesForRemovedTask(ArrayList<Object> procsToKill) {
            synchronized (ActivityManagerService.this) {
                for (int i = 0; i < procsToKill.size(); i++) {
                    final WindowProcessController wpc =
                            (WindowProcessController) procsToKill.get(i);
                    final ProcessRecord pr = (ProcessRecord) wpc.mOwner;
                    if (pr.setSchedGroup == ProcessList.SCHED_GROUP_BACKGROUND
                            && pr.curReceivers.isEmpty()) {
                        pr.kill("remove task", true);
                    } else {
                        // We delay killing processes that are not in the background or running a
                        // receiver.
                        pr.waitingToKill = "remove task";
                    }
                }
            }
        }

        @Override
        public void killProcess(String processName, int uid, String reason) {
            synchronized (ActivityManagerService.this) {
                final ProcessRecord proc = getProcessRecordLocked(processName, uid,
                        true /* keepIfLarge */);
                mProcessList.removeProcessLocked(proc, false /* callerWillRestart */,
                        true /* allowRestart */, reason);
            }
        }

        @Override
        public boolean hasRunningActivity(int uid, @Nullable String packageName) {
            if (packageName == null) return false;

            synchronized (ActivityManagerService.this) {
                for (int i = 0; i < mProcessList.mLruProcesses.size(); i++) {
                    final ProcessRecord pr = mProcessList.mLruProcesses.get(i);
                    if (pr.uid != uid) {
                        continue;
                    }
                    if (pr.getWindowProcessController().hasRunningActivity(packageName)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public void updateOomAdj() {
            synchronized (ActivityManagerService.this) {
                ActivityManagerService.this.updateOomAdjLocked();
            }
        }

        @Override
        public void updateCpuStats() {
            synchronized (ActivityManagerService.this) {
                ActivityManagerService.this.updateCpuStats();
            }
        }

        @Override
        public void updateBatteryStats(ComponentName activity, int uid, int userId,
                boolean resumed) {
            synchronized (ActivityManagerService.this) {
                ActivityManagerService.this.updateBatteryStats(activity, uid, userId, resumed);
            }
        }

        @Override
        public void updateActivityUsageStats(ComponentName activity, int userId, int event,
                IBinder appToken, ComponentName taskRoot) {
            synchronized (ActivityManagerService.this) {
                ActivityManagerService.this.updateActivityUsageStats(activity, userId, event,
                        appToken, taskRoot);
            }
        }

        @Override
        public void updateForegroundTimeIfOnBattery(
                String packageName, int uid, long cpuTimeDiff) {
            synchronized (ActivityManagerService.this) {
                if (!mBatteryStatsService.isOnBattery()) {
                    return;
                }
                final BatteryStatsImpl bsi = mBatteryStatsService.getActiveStatistics();
                synchronized (bsi) {
                    final BatteryStatsImpl.Uid.Proc ps =
                            bsi.getProcessStatsLocked(uid, packageName);
                    if (ps != null) {
                        ps.addForegroundTimeLocked(cpuTimeDiff);
                    }
                }
            }
        }

        @Override
        public void sendForegroundProfileChanged(int userId) {
            mUserController.sendForegroundProfileChanged(userId);
        }

        @Override
        public boolean shouldConfirmCredentials(int userId) {
            return mUserController.shouldConfirmCredentials(userId);
        }

        @Override
        public int[] getCurrentProfileIds() {
            return mUserController.getCurrentProfileIds();
        }

        @Override
        public UserInfo getCurrentUser() {
            return mUserController.getCurrentUser();
        }

        @Override
        public void ensureNotSpecialUser(int userId) {
            mUserController.ensureNotSpecialUser(userId);
        }

        @Override
        public boolean isCurrentProfile(int userId) {
            return mUserController.isCurrentProfile(userId);
        }

        @Override
        public boolean hasStartedUserState(int userId) {
            return mUserController.hasStartedUserState(userId);
        }

        @Override
        public void finishUserSwitch(Object uss) {
            mUserController.finishUserSwitch((UserState) uss);
        }

        @Override
        public void scheduleAppGcs() {
            synchronized (ActivityManagerService.this) {
                ActivityManagerService.this.scheduleAppGcsLocked();
            }
        }

        @Override
        public int getTaskIdForActivity(IBinder token, boolean onlyRoot) {
            synchronized (ActivityManagerService.this) {
                return ActivityManagerService.this.getTaskForActivity(token, onlyRoot);
            }
        }

        @Override
        public ActivityPresentationInfo getActivityPresentationInfo(IBinder token) {
            int displayId = Display.INVALID_DISPLAY;
            try {
                displayId = mActivityTaskManager.getActivityDisplayId(token);
            } catch (RemoteException e) {
            }

            return new ActivityPresentationInfo(mActivityTaskManager.getTaskForActivity(token,
                    /*onlyRoot=*/ false), displayId,
                    mActivityTaskManager.getActivityClassForToken(token));
        }

        @Override
        public void setBooting(boolean booting) {
            mBooting = booting;
        }

        @Override
        public boolean isBooting() {
            return mBooting;
        }

        @Override
        public void setBooted(boolean booted) {
            mBooted = booted;
        }

        @Override
        public boolean isBooted() {
            return mBooted;
        }

        @Override
        public void finishBooting() {
            ActivityManagerService.this.finishBooting();
        }

        @Override
        public void tempWhitelistForPendingIntent(int callerPid, int callerUid, int targetUid,
                long duration, String tag) {
            synchronized (ActivityManagerService.this) {
                ActivityManagerService.this.tempWhitelistForPendingIntentLocked(
                        callerPid, callerUid, targetUid, duration, tag);
            }
        }

        @Override
        public int broadcastIntentInPackage(String packageName, int uid, int realCallingUid,
                int realCallingPid, Intent intent, String resolvedType, IIntentReceiver resultTo,
                int resultCode, String resultData, Bundle resultExtras, String requiredPermission,
                Bundle bOptions, boolean serialized, boolean sticky, int userId,
                boolean allowBackgroundActivityStarts) {
            synchronized (ActivityManagerService.this) {
                return ActivityManagerService.this.broadcastIntentInPackage(packageName, uid,
                        realCallingUid, realCallingPid, intent, resolvedType, resultTo, resultCode,
                        resultData, resultExtras, requiredPermission, bOptions, serialized, sticky,
                        userId, allowBackgroundActivityStarts);
            }
        }

        @Override
        public ComponentName startServiceInPackage(int uid, Intent service, String resolvedType,
                boolean fgRequired, String callingPackage, int userId,
                boolean allowBackgroundActivityStarts) throws TransactionTooLargeException {
            synchronized(ActivityManagerService.this) {
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE,
                        "startServiceInPackage: " + service + " type=" + resolvedType);
                final long origId = Binder.clearCallingIdentity();
                ComponentName res;
                try {
                    res = mServices.startServiceLocked(null, service,
                            resolvedType, -1, uid, fgRequired, callingPackage, userId,
                            allowBackgroundActivityStarts);
                } finally {
                    Binder.restoreCallingIdentity(origId);
                }
                return res;
            }
        }

        @Override
        public void disconnectActivityFromServices(Object connectionHolder) {
            synchronized(ActivityManagerService.this) {
                final ActivityServiceConnectionsHolder c =
                        (ActivityServiceConnectionsHolder) connectionHolder;
                c.forEachConnection(cr -> mServices.removeConnectionLocked(
                        (ConnectionRecord) cr, null, c));
            }
        }

        public void cleanUpServices(int userId, ComponentName component, Intent baseIntent) {
            synchronized(ActivityManagerService.this) {
                mServices.cleanUpServices(userId, component, baseIntent);
            }
        }

        public ActivityInfo getActivityInfoForUser(ActivityInfo aInfo, int userId) {
            // Locked intentionally not held as it isn't needed for this case.
            return ActivityManagerService.this.getActivityInfoForUser(aInfo, userId);
        }

        public void ensureBootCompleted() {
            // Locked intentionally not held as it isn't needed for this case.
            ActivityManagerService.this.ensureBootCompleted();
        }

        public void updateOomLevelsForDisplay(int displayId) {
            synchronized(ActivityManagerService.this) {
                if (mWindowManager != null) {
                    mProcessList.applyDisplaySize(mWindowManager);
                }
            }
        }

        public boolean isActivityStartsLoggingEnabled() {
            return mConstants.mFlagActivityStartsLoggingEnabled;
        }

        public boolean isPackageNameWhitelistedForBgActivityStarts(String packageName) {
            return mConstants.mPackageNamesWhitelistedForBgActivityStarts.contains(packageName);
        }

        public boolean isBackgroundActivityStartsEnabled() {
            return mConstants.mFlagBackgroundActivityStartsEnabled;
        }

        public void reportCurKeyguardUsageEvent(boolean keyguardShowing) {
            synchronized(ActivityManagerService.this) {
                ActivityManagerService.this.reportGlobalUsageEventLocked(keyguardShowing
                        ? UsageEvents.Event.KEYGUARD_SHOWN
                        : UsageEvents.Event.KEYGUARD_HIDDEN);
            }
        }

        @Override
        public long inputDispatchingTimedOut(int pid, boolean aboveSystem, String reason) {
            synchronized (ActivityManagerService.this) {
                return ActivityManagerService.this.inputDispatchingTimedOut(
                        pid, aboveSystem, reason);
            }
        }

        @Override
        public boolean inputDispatchingTimedOut(Object proc, String activityShortComponentName,
                ApplicationInfo aInfo, String parentShortComponentName, Object parentProc,
                boolean aboveSystem, String reason) {
            return ActivityManagerService.this.inputDispatchingTimedOut((ProcessRecord) proc,
                    activityShortComponentName, aInfo, parentShortComponentName,
                    (WindowProcessController) parentProc, aboveSystem, reason);

        }

        @Override
        public void broadcastGlobalConfigurationChanged(int changes, boolean initLocale) {
            synchronized (ActivityManagerService.this) {
                Intent intent = new Intent(Intent.ACTION_CONFIGURATION_CHANGED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                        | Intent.FLAG_RECEIVER_REPLACE_PENDING
                        | Intent.FLAG_RECEIVER_FOREGROUND
                        | Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
                broadcastIntentLocked(null, null, intent, null, null, 0, null, null, null,
                        OP_NONE, null, false, false, MY_PID, SYSTEM_UID, Binder.getCallingUid(),
                        Binder.getCallingPid(), UserHandle.USER_ALL);
                if ((changes & ActivityInfo.CONFIG_LOCALE) != 0) {
                    intent = new Intent(Intent.ACTION_LOCALE_CHANGED);
                    intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND
                            | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND
                            | Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
                    if (initLocale || !mProcessesReady) {
                        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                    }
                    broadcastIntentLocked(null, null, intent, null, null, 0, null, null, null,
                            OP_NONE, null, false, false, MY_PID, SYSTEM_UID, Binder.getCallingUid(),
                            Binder.getCallingPid(), UserHandle.USER_ALL);
                }

                // Send a broadcast to PackageInstallers if the configuration change is interesting
                // for the purposes of installing additional splits.
                if (!initLocale && isSplitConfigurationChange(changes)) {
                    intent = new Intent(Intent.ACTION_SPLIT_CONFIGURATION_CHANGED);
                    intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                            | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);

                    // Typically only app stores will have this permission.
                    String[] permissions =
                            new String[] { android.Manifest.permission.INSTALL_PACKAGES };
                    broadcastIntentLocked(null, null, intent, null, null, 0, null, null,
                            permissions, OP_NONE, null, false, false, MY_PID, SYSTEM_UID,
                            Binder.getCallingUid(), Binder.getCallingPid(), UserHandle.USER_ALL);
                }
            }
        }

        /**
         * Returns true if this configuration change is interesting enough to send an
         * {@link Intent#ACTION_SPLIT_CONFIGURATION_CHANGED} broadcast.
         */
        private boolean isSplitConfigurationChange(int configDiff) {
            return (configDiff & (ActivityInfo.CONFIG_LOCALE | ActivityInfo.CONFIG_DENSITY)) != 0;
        }

        @Override
        public void broadcastCloseSystemDialogs(String reason) {
            synchronized (ActivityManagerService.this) {
                final Intent intent = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                        | Intent.FLAG_RECEIVER_FOREGROUND);
                if (reason != null) {
                    intent.putExtra("reason", reason);
                }

                broadcastIntentLocked(null, null, intent, null, null, 0, null, null, null,
                        OP_NONE, null, false, false, -1, SYSTEM_UID, Binder.getCallingUid(),
                        Binder.getCallingPid(), UserHandle.USER_ALL);
            }
        }

        @Override
        public void killAllBackgroundProcessesExcept(int minTargetSdk, int maxProcState) {
            synchronized (ActivityManagerService.this) {
                ActivityManagerService.this.killAllBackgroundProcessesExcept(
                        minTargetSdk, maxProcState);
            }
        }

        @Override
        public void startProcess(String processName, ApplicationInfo info,
                boolean knownToBeDead, String hostingType, ComponentName hostingName) {
            try {
                if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
                    Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "startProcess:"
                            + processName);
                }
                synchronized (ActivityManagerService.this) {
                    startProcessLocked(processName, info, knownToBeDead, 0 /* intentFlags */,
                            hostingType, hostingName, false /* allowWhileBooting */,
                            false /* isolated */, true /* keepIfLarge */);
                }
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
            }
        }

        @Override
        public void setDebugFlagsForStartingActivity(ActivityInfo aInfo, int startFlags,
                ProfilerInfo profilerInfo, Object wmLock) {
            synchronized (ActivityManagerService.this) {
                /**
                 * This function is called from the window manager context and needs to be executed
                 * synchronously.  To avoid deadlock, we pass a message to AMS to execute the
                 * function and notify the passed in lock when it has been completed.
                 */
                synchronized (wmLock) {
                    if ((startFlags & ActivityManager.START_FLAG_DEBUG) != 0) {
                        setDebugApp(aInfo.processName, true, false);
                    }

                    if ((startFlags & ActivityManager.START_FLAG_NATIVE_DEBUGGING) != 0) {
                        setNativeDebuggingAppLocked(aInfo.applicationInfo, aInfo.processName);
                    }

                    if ((startFlags & ActivityManager.START_FLAG_TRACK_ALLOCATION) != 0) {
                        setTrackAllocationApp(aInfo.applicationInfo, aInfo.processName);
                    }

                    if (profilerInfo != null) {
                        setProfileApp(aInfo.applicationInfo, aInfo.processName, profilerInfo);
                    }
                    wmLock.notify();
                }
            }
        }

        @Override
        public int getStorageMountMode(int pid, int uid) {
            if (uid == SHELL_UID || uid == ROOT_UID) {
                return Zygote.MOUNT_EXTERNAL_FULL;
            }
            synchronized (mPidsSelfLocked) {
                final ProcessRecord pr = mPidsSelfLocked.get(pid);
                return pr == null ? Zygote.MOUNT_EXTERNAL_NONE : pr.mountMode;
            }
        }

        @Override
        public boolean isAppForeground(int uid) {
            return ActivityManagerService.this.isAppForeground(uid);
        }

        @Override
        public void clearPendingBackup(int userId) {
            ActivityManagerService.this.clearPendingBackup(userId);
        }

        /**
         * When power button is very long pressed, call this interface to do some pre-shutdown work
         * like persisting database etc.
         */
        @Override
        public void prepareForPossibleShutdown() {
            ActivityManagerService.this.prepareForPossibleShutdown();
        }

        @Override
        public boolean hasRunningForegroundService(int uid, int foregroundServicetype) {
            synchronized (ActivityManagerService.this) {
                for (int i = 0; i < mProcessList.mLruProcesses.size(); i++) {
                    final ProcessRecord pr = mProcessList.mLruProcesses.get(i);
                    if (pr.uid != uid) {
                        continue;
                    }

                    if ((pr.getForegroundServiceTypes() & foregroundServicetype) != 0) {
                        return true;
                    }
                }
            }

            return false;
        }

        @Override
        public void registerProcessObserver(IProcessObserver processObserver) {
            ActivityManagerService.this.registerProcessObserver(processObserver);
        }

        @Override
        public void unregisterProcessObserver(IProcessObserver processObserver) {
            ActivityManagerService.this.unregisterProcessObserver(processObserver);
        }
    }

    long inputDispatchingTimedOut(int pid, final boolean aboveSystem, String reason) {
        if (checkCallingPermission(FILTER_EVENTS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission " + FILTER_EVENTS);
        }
        ProcessRecord proc;
        long timeout;
        synchronized (this) {
            synchronized (mPidsSelfLocked) {
                proc = mPidsSelfLocked.get(pid);
            }
            timeout = proc != null ? proc.getInputDispatchingTimeout() : KEY_DISPATCHING_TIMEOUT_MS;
        }

        if (inputDispatchingTimedOut(proc, null, null, null, null, aboveSystem, reason)) {
            return -1;
        }

        return timeout;
    }

    /**
     * Handle input dispatching timeouts.
     * @return whether input dispatching should be aborted or not.
     */
    boolean inputDispatchingTimedOut(ProcessRecord proc, String activityShortComponentName,
            ApplicationInfo aInfo, String parentShortComponentName,
            WindowProcessController parentProcess, boolean aboveSystem, String reason) {
        if (checkCallingPermission(FILTER_EVENTS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission " + FILTER_EVENTS);
        }

        final String annotation;
        if (reason == null) {
            annotation = "Input dispatching timed out";
        } else {
            annotation = "Input dispatching timed out (" + reason + ")";
        }

        if (proc != null) {
            synchronized (this) {
                if (proc.isDebugging()) {
                    return false;
                }

                if (proc.getActiveInstrumentation() != null) {
                    Bundle info = new Bundle();
                    info.putString("shortMsg", "keyDispatchingTimedOut");
                    info.putString("longMsg", annotation);
                    finishInstrumentationLocked(proc, Activity.RESULT_CANCELED, info);
                    return true;
                }
            }
            proc.appNotResponding(activityShortComponentName, aInfo,
                    parentShortComponentName, parentProcess, aboveSystem, annotation);
        }

        return true;
    }

    /**
     * Called by app main thread to wait for the network policy rules to get updated.
     *
     * @param procStateSeq The sequence number indicating the process state change that the main
     *                     thread is interested in.
     */
    @Override
    public void waitForNetworkStateUpdate(long procStateSeq) {
        final int callingUid = Binder.getCallingUid();
        if (DEBUG_NETWORK) {
            Slog.d(TAG_NETWORK, "Called from " + callingUid + " to wait for seq: " + procStateSeq);
        }
        UidRecord record;
        synchronized (this) {
            record = mProcessList.getUidRecordLocked(callingUid);
            if (record == null) {
                return;
            }
        }
        synchronized (record.networkStateLock) {
            if (record.lastDispatchedProcStateSeq < procStateSeq) {
                if (DEBUG_NETWORK) {
                    Slog.d(TAG_NETWORK, "Uid state change for seq no. " + procStateSeq + " is not "
                            + "dispatched to NPMS yet, so don't wait. Uid: " + callingUid
                            + " lastProcStateSeqDispatchedToObservers: "
                            + record.lastDispatchedProcStateSeq);
                }
                return;
            }
            if (record.curProcStateSeq > procStateSeq) {
                if (DEBUG_NETWORK) {
                    Slog.d(TAG_NETWORK, "Ignore the wait requests for older seq numbers. Uid: "
                            + callingUid + ", curProcStateSeq: " + record.curProcStateSeq
                            + ", procStateSeq: " + procStateSeq);
                }
                return;
            }
            if (record.lastNetworkUpdatedProcStateSeq >= procStateSeq) {
                if (DEBUG_NETWORK) {
                    Slog.d(TAG_NETWORK, "Network rules have been already updated for seq no. "
                            + procStateSeq + ", so no need to wait. Uid: "
                            + callingUid + ", lastProcStateSeqWithUpdatedNetworkState: "
                            + record.lastNetworkUpdatedProcStateSeq);
                }
                return;
            }
            try {
                if (DEBUG_NETWORK) {
                    Slog.d(TAG_NETWORK, "Starting to wait for the network rules update."
                        + " Uid: " + callingUid + " procStateSeq: " + procStateSeq);
                }
                final long startTime = SystemClock.uptimeMillis();
                record.waitingForNetwork = true;
                record.networkStateLock.wait(mWaitForNetworkTimeoutMs);
                record.waitingForNetwork = false;
                final long totalTime = SystemClock.uptimeMillis() - startTime;
                if (totalTime >= mWaitForNetworkTimeoutMs || DEBUG_NETWORK) {
                    Slog.w(TAG_NETWORK, "Total time waited for network rules to get updated: "
                            + totalTime + ". Uid: " + callingUid + " procStateSeq: "
                            + procStateSeq + " UidRec: " + record
                            + " validateUidRec: " + mValidateUids.get(callingUid));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void waitForBroadcastIdle(PrintWriter pw) {
        enforceCallingPermission(permission.DUMP, "waitForBroadcastIdle()");
        while (true) {
            boolean idle = true;
            synchronized (this) {
                for (BroadcastQueue queue : mBroadcastQueues) {
                    if (!queue.isIdle()) {
                        final String msg = "Waiting for queue " + queue + " to become idle...";
                        pw.println(msg);
                        pw.println(queue.describeState());
                        pw.flush();
                        Slog.v(TAG, msg);
                        queue.cancelDeferrals();
                        idle = false;
                    }
                }
            }

            if (idle) {
                final String msg = "All broadcast queues are idle!";
                pw.println(msg);
                pw.flush();
                Slog.v(TAG, msg);
                return;
            } else {
                SystemClock.sleep(1000);
            }
        }
    }

    /**
     * Kill processes for the user with id userId and that depend on the package named packageName
     */
    @Override
    public void killPackageDependents(String packageName, int userId) {
        enforceCallingPermission(android.Manifest.permission.KILL_UID, "killPackageDependents()");
        if (packageName == null) {
            throw new NullPointerException(
                    "Cannot kill the dependents of a package without its name.");
        }

        long callingId = Binder.clearCallingIdentity();
        IPackageManager pm = AppGlobals.getPackageManager();
        int pkgUid = -1;
        try {
            pkgUid = pm.getPackageUid(packageName, MATCH_DEBUG_TRIAGED_MISSING, userId);
        } catch (RemoteException e) {
        }
        if (userId != UserHandle.USER_ALL && pkgUid == -1) {
            throw new IllegalArgumentException(
                    "Cannot kill dependents of non-existing package " + packageName);
        }
        try {
            synchronized(this) {
                mProcessList.killPackageProcessesLocked(packageName, UserHandle.getAppId(pkgUid),
                        userId, ProcessList.FOREGROUND_APP_ADJ, "dep: " + packageName);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public int restartUserInBackground(final int userId) {
        return mUserController.restartUser(userId, /* foreground */ false);
    }

    @Override
    public void scheduleApplicationInfoChanged(List<String> packageNames, int userId) {
        enforceCallingPermission(android.Manifest.permission.CHANGE_CONFIGURATION,
                "scheduleApplicationInfoChanged()");

        synchronized (this) {
            final long origId = Binder.clearCallingIdentity();
            try {
                updateApplicationInfoLocked(packageNames, userId);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    void updateApplicationInfoLocked(@NonNull List<String> packagesToUpdate, int userId) {
        final boolean updateFrameworkRes = packagesToUpdate.contains("android");

        mProcessList.updateApplicationInfoLocked(packagesToUpdate, userId, updateFrameworkRes);

        if (updateFrameworkRes) {
            // Update system server components that need to know about changed overlays. Because the
            // overlay is applied in ActivityThread, we need to serialize through its thread too.
            final Executor executor = ActivityThread.currentActivityThread().getExecutor();
            final DisplayManagerInternal display =
                    LocalServices.getService(DisplayManagerInternal.class);
            if (display != null) {
                executor.execute(display::onOverlayChanged);
            }
            if (mWindowManager != null) {
                executor.execute(mWindowManager::onOverlayChanged);
            }
        }
    }

    /**
     * Attach an agent to the specified process (proces name or PID)
     */
    public void attachAgent(String process, String path) {
        try {
            synchronized (this) {
                ProcessRecord proc = findProcessLocked(process, UserHandle.USER_SYSTEM,
                        "attachAgent");
                if (proc == null || proc.thread == null) {
                    throw new IllegalArgumentException("Unknown process: " + process);
                }

                boolean isDebuggable = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
                if (!isDebuggable) {
                    if ((proc.info.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
                        throw new SecurityException("Process not debuggable: " + proc);
                    }
                }

                proc.thread.attachAgent(path);
            }
        } catch (RemoteException e) {
            throw new IllegalStateException("Process disappeared");
        }
    }

    /**
     * When power button is very long pressed, call this interface to do some pre-shutdown work
     * like persisting database etc.
     */
    public void prepareForPossibleShutdown() {
        synchronized (this) {
            if (mUsageStatsService != null) {
                mUsageStatsService.prepareForPossibleShutdown();
            }
        }
    }

    @VisibleForTesting
    public static class Injector {
        private NetworkManagementInternal mNmi;

        public Context getContext() {
            return null;
        }

        public AppOpsService getAppOpsService(File file, Handler handler) {
            return new AppOpsService(file, handler);
        }

        public Handler getUiHandler(ActivityManagerService service) {
            return service.new UiHandler();
        }

        public boolean isNetworkRestrictedForUid(int uid) {
            if (ensureHasNetworkManagementInternal()) {
                return mNmi.isNetworkRestrictedForUid(uid);
            }
            return false;
        }

        private boolean ensureHasNetworkManagementInternal() {
            if (mNmi == null) {
                mNmi = LocalServices.getService(NetworkManagementInternal.class);
            }
            return mNmi != null;
        }
    }

    @Override
    public void startDelegateShellPermissionIdentity(int delegateUid,
            @Nullable String[] permissions) {
        if (UserHandle.getCallingAppId() != Process.SHELL_UID
                && UserHandle.getCallingAppId() != Process.ROOT_UID) {
            throw new SecurityException("Only the shell can delegate its permissions");
        }

        // We allow delegation only to one instrumentation started from the shell
        synchronized (ActivityManagerService.this) {
            // If there is a delegate it should be the same instance for app ops and permissions.
            if (mAppOpsService.getAppOpsServiceDelegate()
                    != getPackageManagerInternalLocked().getCheckPermissionDelegate()) {
                throw new IllegalStateException("Bad shell delegate state");
            }

            // If the delegate is already set up for the target UID, nothing to do.
            if (mAppOpsService.getAppOpsServiceDelegate() != null) {
                if (!(mAppOpsService.getAppOpsServiceDelegate() instanceof ShellDelegate)) {
                    throw new IllegalStateException("Bad shell delegate state");
                }
                final ShellDelegate delegate = (ShellDelegate) mAppOpsService
                        .getAppOpsServiceDelegate();
                if (delegate.getDelegateUid() != delegateUid) {
                    throw new SecurityException("Shell can delegate permissions only "
                            + "to one instrumentation at a time");
                }
                delegate.setPermissions(permissions);
                return;
            }

            final int instrCount = mActiveInstrumentation.size();
            for (int i = 0; i < instrCount; i++) {
                final ActiveInstrumentation instr = mActiveInstrumentation.get(i);
                if (instr.mTargetInfo.uid != delegateUid) {
                    continue;
                }
                // If instrumentation started from the shell the connection is not null
                if (instr.mUiAutomationConnection == null) {
                    throw new SecurityException("Shell can delegate its permissions" +
                            " only to an instrumentation started from the shell");
                }

                // Hook them up...
                final ShellDelegate shellDelegate = new ShellDelegate(
                        instr.mTargetInfo.packageName, delegateUid, permissions);
                mAppOpsService.setAppOpsServiceDelegate(shellDelegate);
                getPackageManagerInternalLocked().setCheckPermissionDelegate(shellDelegate);
                return;
            }
        }
    }

    @Override
    public void stopDelegateShellPermissionIdentity() {
        if (UserHandle.getCallingAppId() != Process.SHELL_UID
                && UserHandle.getCallingAppId() != Process.ROOT_UID) {
            throw new SecurityException("Only the shell can delegate its permissions");
        }
        synchronized (ActivityManagerService.this) {
            mAppOpsService.setAppOpsServiceDelegate(null);
            getPackageManagerInternalLocked().setCheckPermissionDelegate(null);
        }
    }

    private class ShellDelegate implements CheckOpsDelegate, CheckPermissionDelegate {
        private final String mTargetPackageName;
        private final int mTargetUid;
        private @Nullable String[] mPermissions;

        ShellDelegate(String targetPacakgeName, int targetUid, @Nullable String[] permissions) {
            mTargetPackageName = targetPacakgeName;
            mTargetUid = targetUid;
            mPermissions = permissions;
        }

        int getDelegateUid() {
            return mTargetUid;
        }

        void setPermissions(@Nullable String[] permissions) {
            mPermissions = permissions;
        }

        @Override
        public int checkOperation(int code, int uid, String packageName, boolean raw,
                QuadFunction<Integer, Integer, String, Boolean, Integer> superImpl) {
            if (uid == mTargetUid && isTargetOp(code)) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    return superImpl.apply(code, Process.SHELL_UID,
                            "com.android.shell", raw);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return superImpl.apply(code, uid, packageName, raw);
        }

        @Override
        public int checkAudioOperation(int code, int usage, int uid, String packageName,
                QuadFunction<Integer, Integer, Integer, String, Integer> superImpl) {
            if (uid == mTargetUid && isTargetOp(code)) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    return superImpl.apply(code, usage, Process.SHELL_UID,
                            "com.android.shell");
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return superImpl.apply(code, usage, uid, packageName);
        }

        @Override
        public int noteOperation(int code, int uid, String packageName,
                TriFunction<Integer, Integer, String, Integer> superImpl) {
            if (uid == mTargetUid && isTargetOp(code)) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    return mAppOpsService.noteProxyOperation(code, Process.SHELL_UID,
                            "com.android.shell", uid, packageName);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return superImpl.apply(code, uid, packageName);
        }

        @Override
        public int checkPermission(String permName, String pkgName, int userId,
                TriFunction<String, String, Integer, Integer> superImpl) {
            if (mTargetPackageName.equals(pkgName) && isTargetPermission(permName)) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    return superImpl.apply(permName, "com.android.shell", userId);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return superImpl.apply(permName, pkgName, userId);
        }

        @Override
        public int checkUidPermission(String permName, int uid,
                BiFunction<String, Integer, Integer> superImpl) {
            if (uid == mTargetUid  && isTargetPermission(permName)) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    return superImpl.apply(permName, Process.SHELL_UID);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
            return superImpl.apply(permName, uid);
        }

        private boolean isTargetOp(int code) {
            // null permissions means all ops are targeted
            if (mPermissions == null) {
                return true;
            }
            // no permission for the op means the op is targeted
            final String permission = AppOpsManager.opToPermission(code);
            if (permission == null) {
                return true;
            }
            return isTargetPermission(permission);
        }

        private boolean isTargetPermission(@NonNull String permission) {
            // null permissions means all permissions are targeted
            return (mPermissions == null || ArrayUtils.contains(mPermissions, permission));
        }
    }

    /**
     * If debug.trigger.watchdog is set to 1, sleep 10 minutes with the AM lock held, which would
     * cause a watchdog kill.
     */
    void maybeTriggerWatchdog() {
        final String key = "debug.trigger.watchdog";
        if (Watchdog.DEBUG && SystemProperties.getInt(key, 0) == 1) {
            Slog.w(TAG, "!!! TRIGGERING WATCHDOG !!!");

            // Clear the property; otherwise the system would hang again after a watchdog restart.
            SystemProperties.set(key, "");
            synchronized (ActivityManagerService.this) {
                try {
                    // Arbitrary long sleep for watchdog to catch.
                    Thread.sleep(60 * 60 * 1000);
                } catch (InterruptedException e) {
                }
            }
        }
    }

    private boolean isOnOffloadQueue(int flags) {
        return (mEnableOffloadQueue && ((flags & Intent.FLAG_RECEIVER_OFFLOAD) != 0));
    }

    @Override
    public ParcelFileDescriptor getLifeMonitor() {
        if (!isCallerShell()) {
            throw new SecurityException("Only shell can call it");
        }
        synchronized (this) {
            try {
                if (mLifeMonitorFds == null) {
                    mLifeMonitorFds = ParcelFileDescriptor.createPipe();
                }
                // The returned FD will be closed, but we want to keep our reader open,
                // so return a dup instead.
                return mLifeMonitorFds[0].dup();
            } catch (IOException e) {
                Slog.w(TAG, "Unable to create pipe", e);
                return null;
            }
        }
    }
}
