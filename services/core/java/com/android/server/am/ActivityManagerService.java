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
import static android.Manifest.permission.MANAGE_ACTIVITY_TASKS;
import static android.Manifest.permission.MANAGE_USERS;
import static android.Manifest.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND;
import static android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND;
import static android.Manifest.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND;
import static android.app.ActivityManager.INSTR_FLAG_ALWAYS_CHECK_SIGNATURE;
import static android.app.ActivityManager.INSTR_FLAG_DISABLE_HIDDEN_API_CHECKS;
import static android.app.ActivityManager.INSTR_FLAG_DISABLE_ISOLATED_STORAGE;
import static android.app.ActivityManager.INSTR_FLAG_DISABLE_TEST_API_CHECKS;
import static android.app.ActivityManager.INSTR_FLAG_NO_RESTART;
import static android.app.ActivityManager.INTENT_SENDER_ACTIVITY;
import static android.app.ActivityManager.PROCESS_CAPABILITY_ALL;
import static android.app.ActivityManager.PROCESS_STATE_BOUND_TOP;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.ActivityManager.PROCESS_STATE_TOP;
import static android.app.ActivityManager.RESTRICTION_LEVEL_FORCE_STOPPED;
import static android.app.ActivityManager.RESTRICTION_REASON_DEFAULT;
import static android.app.ActivityManager.RESTRICTION_REASON_USAGE;
import static android.app.ActivityManager.RESTRICTION_SOURCE_SYSTEM;
import static android.app.ActivityManager.RESTRICTION_SOURCE_USER;
import static android.app.ActivityManager.StopUserOnSwitch;
import static android.app.ActivityManager.UidFrozenStateChangedCallback.UID_FROZEN_STATE_FROZEN;
import static android.app.ActivityManager.UidFrozenStateChangedCallback.UID_FROZEN_STATE_UNFROZEN;
import static android.app.ActivityManagerInternal.ALLOW_FULL_ONLY;
import static android.app.ActivityManagerInternal.ALLOW_NON_FULL;
import static android.app.ActivityManagerInternal.MEDIA_PROJECTION_TOKEN_EVENT_CREATED;
import static android.app.ActivityManagerInternal.MEDIA_PROJECTION_TOKEN_EVENT_DESTROYED;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_BACKUP;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_FINISH_RECEIVER;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_PROCESS_BEGIN;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_PROCESS_END;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_SHELL;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_SYSTEM_INIT;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_UI_VISIBILITY;
import static android.app.AppOpsManager.OP_NONE;
import static android.app.ProcessMemoryState.HOSTING_COMPONENT_TYPE_BACKUP;
import static android.app.ProcessMemoryState.HOSTING_COMPONENT_TYPE_INSTRUMENTATION;
import static android.app.ProcessMemoryState.HOSTING_COMPONENT_TYPE_PERSISTENT;
import static android.app.ProcessMemoryState.HOSTING_COMPONENT_TYPE_SYSTEM;
import static android.content.pm.ApplicationInfo.HIDDEN_API_ENFORCEMENT_DEFAULT;
import static android.content.pm.PackageManager.GET_SHARED_LIBRARY_FILES;
import static android.content.pm.PackageManager.MATCH_ALL;
import static android.content.pm.PackageManager.MATCH_ANY_USER;
import static android.content.pm.PackageManager.MATCH_DEBUG_TRIAGED_MISSING;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.content.pm.PackageManager.SIGNATURE_NO_MATCH;
import static android.net.ConnectivityManager.BLOCKED_REASON_NONE;
import static android.os.FactoryTest.FACTORY_TEST_OFF;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_CRITICAL;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_HIGH;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_NORMAL;
import static android.os.IServiceManager.DUMP_FLAG_PROTO;
import static android.os.InputConstants.DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
import static android.os.PowerExemptionManager.REASON_ACTIVITY_VISIBILITY_GRACE_PERIOD;
import static android.os.PowerExemptionManager.REASON_BACKGROUND_ACTIVITY_PERMISSION;
import static android.os.PowerExemptionManager.REASON_BOOT_COMPLETED;
import static android.os.PowerExemptionManager.REASON_COMPANION_DEVICE_MANAGER;
import static android.os.PowerExemptionManager.REASON_INSTR_BACKGROUND_ACTIVITY_PERMISSION;
import static android.os.PowerExemptionManager.REASON_LOCKED_BOOT_COMPLETED;
import static android.os.PowerExemptionManager.REASON_PROC_STATE_BTOP;
import static android.os.PowerExemptionManager.REASON_PROC_STATE_PERSISTENT;
import static android.os.PowerExemptionManager.REASON_PROC_STATE_PERSISTENT_UI;
import static android.os.PowerExemptionManager.REASON_PROC_STATE_TOP;
import static android.os.PowerExemptionManager.REASON_START_ACTIVITY_FLAG;
import static android.os.PowerExemptionManager.REASON_SYSTEM_ALERT_WINDOW_PERMISSION;
import static android.os.PowerExemptionManager.REASON_SYSTEM_ALLOW_LISTED;
import static android.os.PowerExemptionManager.REASON_SYSTEM_UID;
import static android.os.PowerExemptionManager.REASON_UID_VISIBLE;
import static android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED;
import static android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_NONE;
import static android.os.PowerExemptionManager.getReasonCodeFromProcState;
import static android.os.Process.BLUETOOTH_UID;
import static android.os.Process.FIRST_APPLICATION_UID;
import static android.os.Process.INVALID_UID;
import static android.os.Process.NETWORK_STACK_UID;
import static android.os.Process.NFC_UID;
import static android.os.Process.PHONE_UID;
import static android.os.Process.PROC_OUT_LONG;
import static android.os.Process.PROC_SPACE_TERM;
import static android.os.Process.ROOT_UID;
import static android.os.Process.SCHED_FIFO;
import static android.os.Process.SCHED_RESET_ON_FORK;
import static android.os.Process.SE_UID;
import static android.os.Process.SHELL_UID;
import static android.os.Process.SIGNAL_USR1;
import static android.os.Process.SYSTEM_UID;
import static android.os.Process.THREAD_PRIORITY_FOREGROUND;
import static android.os.Process.THREAD_PRIORITY_TOP_APP_BOOST;
import static android.os.Process.ZYGOTE_POLICY_FLAG_BATCH_LAUNCH;
import static android.os.Process.ZYGOTE_POLICY_FLAG_EMPTY;
import static android.os.Process.ZYGOTE_POLICY_FLAG_LATENCY_SENSITIVE;
import static android.os.Process.ZYGOTE_POLICY_FLAG_SYSTEM_PROCESS;
import static android.os.Process.ZYGOTE_PROCESS;
import static android.os.Process.getTotalMemory;
import static android.os.Process.isThreadInProcess;
import static android.os.Process.killProcess;
import static android.os.Process.killProcessGroup;
import static android.os.Process.killProcessQuiet;
import static android.os.Process.myPid;
import static android.os.Process.myUid;
import static android.os.Process.readProcFile;
import static android.os.Process.sendSignal;
import static android.os.Process.setThreadPriority;
import static android.os.Process.setThreadScheduler;
import static android.provider.Settings.Global.ALWAYS_FINISH_ACTIVITIES;
import static android.provider.Settings.Global.DEBUG_APP;
import static android.provider.Settings.Global.WAIT_FOR_DEBUGGER;
import static android.util.FeatureFlagUtils.SETTINGS_ENABLE_MONITOR_PHANTOM_PROCS;
import static android.view.Display.INVALID_DISPLAY;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_CONFIGURATION;
import static com.android.internal.util.FrameworkStatsLog.UNSAFE_INTENT_EVENT_REPORTED__EVENT_TYPE__INTERNAL_NON_EXPORTED_COMPONENT_MATCH;
import static com.android.internal.util.FrameworkStatsLog.UNSAFE_INTENT_EVENT_REPORTED__EVENT_TYPE__NEW_MUTABLE_IMPLICIT_PENDING_INTENT_RETRIEVED;
import static com.android.sdksandbox.flags.Flags.sdkSandboxInstrumentationInfo;
import static com.android.server.am.ActiveServices.FGS_SAW_RESTRICTIONS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_ALL;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_ALLOWLISTS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BACKGROUND_CHECK;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BACKUP;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BROADCAST;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_BROADCAST_LIGHT;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_MU;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_NETWORK;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_OOM_ADJ;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_POWER;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PROCESSES;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_SERVICE;
import static com.android.server.am.ActivityManagerDebugConfig.LOG_WRITER_INFO;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_BACKUP;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_BROADCAST;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_CLEANUP;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_LRU;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_MU;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_NETWORK;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_OOM_ADJ;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_POWER;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_PROCESSES;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_SERVICE;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_UID_OBSERVERS;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.MemoryStatUtil.hasMemcg;
import static com.android.server.am.ProcessList.ProcStartHandler;
import static com.android.server.flags.Flags.disableSystemCompaction;
import static com.android.server.net.NetworkPolicyManagerInternal.updateBlockedReasonsWithProcState;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.pm.UserManagerInternal.USER_START_MODE_BACKGROUND;
import static com.android.server.pm.UserManagerInternal.USER_START_MODE_FOREGROUND;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_CLEANUP;
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
import static com.android.server.wm.ActivityTaskManagerService.DUMP_TOP_RESUMED_ACTIVITY;
import static com.android.server.wm.ActivityTaskManagerService.DUMP_VISIBLE_ACTIVITIES;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_NONE;
import static com.android.server.wm.ActivityTaskManagerService.relaunchReasonToString;
import static com.android.systemui.shared.Flags.enableHomeDelay;

import android.Manifest;
import android.Manifest.permission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.PermissionMethod;
import android.annotation.PermissionName;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityClient;
import android.app.ActivityManager;
import android.app.ActivityManager.ForegroundServiceApiType;
import android.app.ActivityManager.PendingIntentInfo;
import android.app.ActivityManager.ProcessCapability;
import android.app.ActivityManager.RestrictionLevel;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManager.UidFrozenStateChangedCallback.UidFrozenState;
import android.app.ActivityManagerInternal;
import android.app.ActivityManagerInternal.BindServiceEventListener;
import android.app.ActivityManagerInternal.BroadcastEventListener;
import android.app.ActivityManagerInternal.ForegroundServiceStateListener;
import android.app.ActivityManagerInternal.MediaProjectionTokenEvent;
import android.app.ActivityManagerInternal.OomAdjReason;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.ActivityThread;
import android.app.AnrController;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.AppOpsManager.AttributionFlags;
import android.app.AppOpsManagerInternal.CheckOpsDelegate;
import android.app.ApplicationErrorReport;
import android.app.ApplicationExitInfo;
import android.app.ApplicationStartInfo;
import android.app.ApplicationThreadConstants;
import android.app.BackgroundStartPrivileges;
import android.app.BroadcastOptions;
import android.app.ContentProviderHolder;
import android.app.ForegroundServiceDelegationOptions;
import android.app.IActivityController;
import android.app.IActivityManager;
import android.app.IApplicationStartInfoCompleteListener;
import android.app.IApplicationThread;
import android.app.IForegroundServiceObserver;
import android.app.IInstrumentationWatcher;
import android.app.INotificationManager;
import android.app.IProcessObserver;
import android.app.IServiceConnection;
import android.app.IStopUserCallback;
import android.app.ITaskStackListener;
import android.app.IUiAutomationConnection;
import android.app.IUidFrozenStateChangedCallback;
import android.app.IUidObserver;
import android.app.IUnsafeIntentStrictModeCallback;
import android.app.IUserSwitchObserver;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.PendingIntentStats;
import android.app.ProcessMemoryState;
import android.app.ProfilerInfo;
import android.app.ServiceStartNotAllowedException;
import android.app.WaitResult;
import android.app.assist.ActivityId;
import android.app.backup.BackupAnnotations.BackupDestination;
import android.app.backup.IBackupManager;
import android.app.compat.CompatChanges;
import android.app.job.JobParameters;
import android.app.usage.UsageEvents;
import android.app.usage.UsageEvents.Event;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageStatsManagerInternal;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetManagerInternal;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.Overridable;
import android.content.AttributionSource;
import android.content.AutofillOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.ContentCaptureOptions;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Context.BindServiceFlags;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.LocusId;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ActivityPresentationInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ApplicationInfo.HiddenApiEnforcementPolicy;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageManager;
import android.content.pm.IncrementalStatesInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionInfo;
import android.content.pm.ProcessInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ProviderInfoList;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.TestUtilityService;
import android.content.pm.UserInfo;
import android.content.pm.VersionedPackage;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.media.audiofx.AudioEffect;
import android.net.ConnectivityManager;
import android.net.Proxy;
import android.net.Uri;
import android.os.AppZygote;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.BinderProxy;
import android.os.BugreportParams;
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
import android.os.InputConstants;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.PowerExemptionManager;
import android.os.PowerExemptionManager.ReasonCode;
import android.os.PowerExemptionManager.TempAllowListType;
import android.os.PowerManager;
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.SharedMemory;
import android.os.ShellCallback;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.TransactionTooLargeException;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.WorkSource;
import android.os.incremental.IIncrementalService;
import android.os.incremental.IncrementalManager;
import android.os.incremental.IncrementalMetrics;
import android.os.storage.IStorageManager;
import android.os.storage.StorageManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.server.ServerProtoEnums;
import android.sysprop.InitProperties;
import android.system.Os;
import android.system.OsConstants;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.text.style.SuggestionSpan;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.FeatureFlagUtils;
import android.util.IndentingPrintWriter;
import android.util.IntArray;
import android.util.Log;
import android.util.MathUtils;
import android.util.Pair;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.StatsEvent;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.util.proto.ProtoUtils;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.autofill.AutofillManagerInternal;

import com.android.internal.annotations.CompositeRWLock;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IAppOpsActiveCallback;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.android.internal.app.ProcessMap;
import com.android.internal.app.SystemUserHomeActivity;
import com.android.internal.app.procstats.ProcessState;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.content.InstallLocationUtils;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.BinderCallHeavyHitterWatcher.BinderCallHeavyHitterListener;
import com.android.internal.os.BinderCallHeavyHitterWatcher.HeavyHitterContainer;
import com.android.internal.os.BinderInternal;
import com.android.internal.os.BinderInternal.BinderProxyCountEventListener;
import com.android.internal.os.BinderTransactionNameResolver;
import com.android.internal.os.ByteTransferPipe;
import com.android.internal.os.IResultReceiver;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.os.SomeArgs;
import com.android.internal.os.TimeoutRecord;
import com.android.internal.os.TransferPipe;
import com.android.internal.os.Zygote;
import com.android.internal.pm.pkg.parsing.ParsingPackageUtils;
import com.android.internal.policy.AttributeCache;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.MemInfoReader;
import com.android.internal.util.Preconditions;
import com.android.server.AlarmManagerInternal;
import com.android.server.BootReceiver;
import com.android.server.DeviceIdleInternal;
import com.android.server.DisplayThread;
import com.android.server.IntentResolver;
import com.android.server.IoThread;
import com.android.server.LocalManagerRegistry;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.server.PackageWatchdog;
import com.android.server.ServiceThread;
import com.android.server.SystemConfig;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;
import com.android.server.ThreadPriorityBooster;
import com.android.server.UserspaceRebootLogger;
import com.android.server.Watchdog;
import com.android.server.am.ComponentAliasResolver.Resolution;
import com.android.server.am.LowMemDetector.MemFactor;
import com.android.server.appop.AppOpsService;
import com.android.server.compat.PlatformCompat;
import com.android.server.contentcapture.ContentCaptureManagerInternal;
import com.android.server.criticalevents.CriticalEventLog;
import com.android.server.firewall.IntentFirewall;
import com.android.server.graphics.fonts.FontManagerInternal;
import com.android.server.job.JobSchedulerInternal;
import com.android.server.net.NetworkManagementInternal;
import com.android.server.os.NativeTombstoneManager;
import com.android.server.pm.Computer;
import com.android.server.pm.Installer;
import com.android.server.pm.PackageManagerServiceUtils;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.SELinuxUtil;
import com.android.server.pm.snapshot.PackageDataSnapshot;
import com.android.server.power.stats.BatteryStatsImpl;
import com.android.server.sdksandbox.SdkSandboxManagerLocal;
import com.android.server.stats.pull.StatsPullAtomService;
import com.android.server.stats.pull.StatsPullAtomServiceInternal;
import com.android.server.uri.GrantUri;
import com.android.server.uri.NeededUriGrants;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.utils.AnrTimer;
import com.android.server.utils.PriorityDump;
import com.android.server.utils.Slogf;
import com.android.server.utils.TimingsTraceAndSlog;
import com.android.server.vr.VrManagerInternal;
import com.android.server.wm.ActivityMetricsLaunchObserver;
import com.android.server.wm.ActivityServiceConnectionsHolder;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.ActivityTaskManagerService;
import com.android.server.wm.WindowManagerInternal;
import com.android.server.wm.WindowManagerService;
import com.android.server.wm.WindowProcessController;

import dalvik.annotation.optimization.NeverCompile;
import dalvik.system.VMRuntime;

import libcore.util.EmptyArray;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;

public class ActivityManagerService extends IActivityManager.Stub
        implements Watchdog.Monitor, BatteryStatsImpl.BatteryCallback, ActivityManagerGlobalLock {

    private static final String SYSTEM_PROPERTY_DEVICE_PROVISIONED =
            "persist.sys.device_provisioned";

    static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityManagerService" : TAG_AM;
    static final String TAG_BACKUP = TAG + POSTFIX_BACKUP;
    private static final String TAG_BROADCAST = TAG + POSTFIX_BROADCAST;
    private static final String TAG_CLEANUP = TAG + POSTFIX_CLEANUP;
    private static final String TAG_CONFIGURATION = TAG + POSTFIX_CONFIGURATION;
    private static final String TAG_LOCKTASK = TAG + POSTFIX_LOCKTASK;
    static final String TAG_LRU = TAG + POSTFIX_LRU;
    static final String TAG_MU = TAG + POSTFIX_MU;
    static final String TAG_NETWORK = TAG + POSTFIX_NETWORK;
    static final String TAG_OOM_ADJ = TAG + POSTFIX_OOM_ADJ;
    private static final String TAG_POWER = TAG + POSTFIX_POWER;
    static final String TAG_PROCESSES = TAG + POSTFIX_PROCESSES;
    private static final String TAG_SERVICE = TAG + POSTFIX_SERVICE;
    private static final String TAG_SWITCH = TAG + POSTFIX_SWITCH;
    static final String TAG_UID_OBSERVERS = TAG + POSTFIX_UID_OBSERVERS;

    // Mock "pretend we're idle now" broadcast action to the job scheduler; declared
    // here so that while the job scheduler can depend on AMS, the other way around
    // need not be the case.
    public static final String ACTION_TRIGGER_IDLE = "com.android.server.ACTION_TRIGGER_IDLE";

    private static final String INTENT_BUGREPORT_REQUESTED =
            "com.android.internal.intent.action.BUGREPORT_REQUESTED";
    private static final String SHELL_APP_PACKAGE = "com.android.shell";

    // The flags that are set for all calls we make to the package manager.
    public static final int STOCK_PM_FLAGS = PackageManager.GET_SHARED_LIBRARY_FILES;

    static final String SYSTEM_USER_HOME_NEEDED = "ro.system_user_home_needed";

    // Maximum number of receivers an app can register.
    private static final int MAX_RECEIVERS_ALLOWED_PER_APP = 1000;

    // How long we wait for a launched process to attach to the activity manager
    // before we decide it's never going to come up for real.
    static final int PROC_START_TIMEOUT = 10 * 1000 * Build.HW_TIMEOUT_MULTIPLIER;

    // How long we wait for a launched process to complete its app startup before we ANR.
    static final int BIND_APPLICATION_TIMEOUT = 15 * 1000 * Build.HW_TIMEOUT_MULTIPLIER;

    // How long we wait to kill an application zygote, after the last process using
    // it has gone away.
    static final int KILL_APP_ZYGOTE_DELAY_MS = 5 * 1000;

    // How long we wait for a launched process to attach to the activity manager
    // before we decide it's never going to come up for real, when the process was
    // started with a wrapper for instrumentation (such as Valgrind) because it
    // could take much longer than usual.
    static final int PROC_START_TIMEOUT_WITH_WRAPPER = 1200*1000;

    // How long we allow a receiver to run before giving up on it.
    static final int BROADCAST_FG_TIMEOUT = 10 * 1000 * Build.HW_TIMEOUT_MULTIPLIER;
    static final int BROADCAST_BG_TIMEOUT = 60 * 1000 * Build.HW_TIMEOUT_MULTIPLIER;

    public static final int MY_PID = myPid();

    static final String[] EMPTY_STRING_ARRAY = new String[0];

    // How many bytes to write into the dropbox log before truncating
    static final int DROPBOX_DEFAULT_MAX_SIZE = 192 * 1024;
    // Assumes logcat entries average around 100 bytes; that's not perfect stack traces count
    // as one line, but close enough for now.
    static final int RESERVED_BYTES_PER_LOGCAT_LINE = 100;

    // How many seconds should the system wait before terminating the spawned logcat process.
    static final int LOGCAT_TIMEOUT_SEC = 10;

    // Necessary ApplicationInfo flags to mark an app as persistent
    static final int PERSISTENT_MASK =
            ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_PERSISTENT;

    // Intent sent when remote bugreport collection has been completed
    private static final String INTENT_REMOTE_BUGREPORT_FINISHED =
            "com.android.internal.intent.action.REMOTE_BUGREPORT_FINISHED";

    public static final String DATA_FILE_PATH_HEADER = "Data File: ";
    public static final String DATA_FILE_PATH_FOOTER = "End Data File\n";

    // If set, we will push process association information in to procstats.
    static final boolean TRACK_PROCSTATS_ASSOCIATIONS = true;

    // The minimum memory growth threshold (in KB) for low RAM devices.
    private static final int MINIMUM_MEMORY_GROWTH_THRESHOLD = 10 * 1000; // 10 MB

    /**
     * The number of binder proxies we need to have before we start dumping debug info
     * and kill the offenders.
     */
    private static final int BINDER_PROXY_HIGH_WATERMARK = 6000;

    /**
     * Low watermark that needs to be met before we consider dumping info again,
     * after already hitting the high watermark.
     */
    private static final int BINDER_PROXY_LOW_WATERMARK = 5500;

    /**
     * The number of binder proxies we need to have before we start warning.
     */
    private static final int BINDER_PROXY_WARNING_WATERMARK = 5750;

    // Max character limit for a notification title. If the notification title is larger than this
    // the notification will not be legible to the user.
    private static final int MAX_BUGREPORT_TITLE_SIZE = 100;
    private static final int MAX_BUGREPORT_DESCRIPTION_SIZE = 150;

    private static final DateTimeFormatter DROPBOX_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSZ");

    OomAdjuster mOomAdjuster;

    static final String EXTRA_TITLE = "android.intent.extra.TITLE";
    static final String EXTRA_DESCRIPTION = "android.intent.extra.DESCRIPTION";
    static final String EXTRA_BUGREPORT_TYPE = "android.intent.extra.BUGREPORT_TYPE";
    static final String EXTRA_BUGREPORT_NONCE = "android.intent.extra.BUGREPORT_NONCE";
    static final String EXTRA_EXTRA_ATTACHMENT_URI =
            "android.intent.extra.EXTRA_ATTACHMENT_URI";
    /**
     * It is now required for apps to explicitly set either
     * {@link android.content.Context#RECEIVER_EXPORTED} or
     * {@link android.content.Context#RECEIVER_NOT_EXPORTED} when registering a receiver for an
     * unprotected broadcast in code.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private static final long DYNAMIC_RECEIVER_EXPLICIT_EXPORT_REQUIRED = 161145287L;

    /**
     * Apps targeting Android U and above will need to export components in order to invoke them
     * through implicit intents.
     *
     * If a component is not exported and invoked, it will be removed from the list of receivers.
     * This applies specifically to activities and broadcasts.
     */
    @ChangeId
    @Overridable
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public static final long IMPLICIT_INTENTS_ONLY_MATCH_EXPORTED_COMPONENTS = 229362273;

    /**
     * The maximum number of bytes that {@link #setProcessStateSummary} accepts.
     *
     * @see {@link android.app.ActivityManager#setProcessStateSummary(byte[])}
     */
    static final int MAX_STATE_DATA_SIZE = 128;

    /** All system services */
    SystemServiceManager mSystemServiceManager;

    private Installer mInstaller;

    final InstrumentationReporter mInstrumentationReporter = new InstrumentationReporter();

    @CompositeRWLock({"this", "mProcLock"})
    final ArrayList<ActiveInstrumentation> mActiveInstrumentation = new ArrayList<>();

    public final IntentFirewall mIntentFirewall;

    /**
     * The global lock for AMS, it's de-facto the ActivityManagerService object as of now.
     */
    final ActivityManagerGlobalLock mGlobalLock = ActivityManagerService.this;

    /**
     * Whether or not to enable the {@link #mProcLock}. If {@code false}, the {@link #mProcLock}
     * will be equivalent to the {@link #mGlobalLock}.
     */
    private static final boolean ENABLE_PROC_LOCK = true;

    /**
     * The lock for process management.
     *
     * <p>
     * This lock is widely used in conjunction with the {@link #mGlobalLock} at present,
     * where it'll require any of the locks to read from a data class, and both of the locks
     * to write into that data class.
     *
     * For the naming convention of function suffixes:
     * <ul>
     *    <li>-LOSP:    Locked with any Of global am Service or Process lock</li>
     *    <li>-LSP:     Locked with both of global am Service and Process lock</li>
     *    <li>-Locked:  Locked with global am service lock alone</li>
     *    <li>-LPr:     Locked with Process lock alone</li>
     * </ul>
     * For the simplicity, the getters/setters of the fields in data classes usually don't end with
     * the above suffixes even if they're guarded by the locks here.
     * </p>
     *
     * <p>
     * In terms of locking order, it should be right below to the {@link #mGlobalLock},
     * and above everything else which used to be underneath the {@link #mGlobalLock}.
     * As of today, the core components(services/providers/broadcasts) are still guarded by
     * the {@link #mGlobalLock} alone, so be cautious, avoid from acquiring the {@link #mGlobalLock}
     * while holding this lock.
     * </p>
     *
     */
    final ActivityManagerGlobalLock mProcLock = ENABLE_PROC_LOCK
            ? new ActivityManagerProcLock() : mGlobalLock;

    // Whether we should use SCHED_FIFO for UI and RenderThreads.
    final boolean mUseFifoUiScheduling;

    /** Whether some specified important processes are allowed to use FIFO priority. */
    boolean mAllowSpecifiedFifoScheduling = true;

    @GuardedBy("this")
    private final SparseArray<IUnsafeIntentStrictModeCallback>
            mStrictModeCallbacks = new SparseArray<>();

    // Convenient for easy iteration over the queues. Foreground is first
    // so that dispatch of foreground broadcasts gets precedence.
    private BroadcastQueue mBroadcastQueue;

    @GuardedBy("this")
    BroadcastStats mLastBroadcastStats;

    @GuardedBy("this")
    BroadcastStats mCurBroadcastStats;

    TraceErrorLogger mTraceErrorLogger;

    private volatile int mDeviceOwnerUid = INVALID_UID;

    /**
     * Map userId to its companion app uids.
     */
    private final Map<Integer, Set<Integer>> mCompanionAppUidsMap = new ArrayMap<>();

    /**
     * The profile owner UIDs.
     */
    private ArraySet<Integer> mProfileOwnerUids = null;

    final UserController mUserController;
    @VisibleForTesting
    public final PendingIntentController mPendingIntentController;

    final AppErrors mAppErrors;
    final PackageWatchdog mPackageWatchdog;

    @GuardedBy("mDeliveryGroupPolicyIgnoredActions")
    private final ArraySet<String> mDeliveryGroupPolicyIgnoredActions = new ArraySet();

    private AccessCheckDelegateHelper mAccessCheckDelegateHelper;

    /**
     * Uids of apps with current active camera sessions.  Access synchronized on
     * the IntArray instance itself, and no other locks must be acquired while that
     * one is held.
     */
    @GuardedBy("mActiveCameraUids")
    final IntArray mActiveCameraUids = new IntArray(4);

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

    private static ThreadPriorityBooster sProcThreadPriorityBooster = new ThreadPriorityBooster(
            THREAD_PRIORITY_FOREGROUND, LockGuard.INDEX_PROC);

    static void boostPriorityForProcLockedSection() {
        if (ENABLE_PROC_LOCK) {
            sProcThreadPriorityBooster.boost();
        } else {
            sThreadPriorityBooster.boost();
        }
    }

    static void resetPriorityAfterProcLockedSection() {
        if (ENABLE_PROC_LOCK) {
            sProcThreadPriorityBooster.reset();
        } else {
            sThreadPriorityBooster.reset();
        }
    }

    /**
     * Process management.
     */
    final ProcessList mProcessList;

    /**
     * The list of phantom processes.
     * @see PhantomProcessRecord
     */
    final PhantomProcessList mPhantomProcessList;

    /**
     * Tracking long-term execution of processes to look for abuse and other
     * bad app behavior.
     */
    final ProcessStatsService mProcessStats;

    /**
     * Non-persistent appId allowlist for background restrictions
     */
    @CompositeRWLock({"this", "mProcLock"})
    private int[] mBackgroundAppIdAllowlist = new int[] {
            BLUETOOTH_UID
    };

    /**
     * Broadcast actions that will always be deliverable to unlaunched/background apps
     */
    @GuardedBy("this")
    private ArraySet<String> mBackgroundLaunchBroadcasts;

    /**
     * When an app has restrictions on the other apps that can have associations with it,
     * it appears here with a set of the allowed apps and also track debuggability of the app.
     */
    @GuardedBy("this")
    private ArrayMap<String, PackageAssociationInfo> mAllowedAssociations;

    @GuardedBy("this")
    final ComponentAliasResolver mComponentAliasResolver;

    private static final long HOME_LAUNCH_TIMEOUT_MS = 15000;
    private final AtomicBoolean mHasHomeDelay = new AtomicBoolean(false);

    /**
     * Tracks all users with computed color resources by ThemeOverlaycvontroller
     */
    @GuardedBy("this")
    private final Set<Integer> mThemeOverlayReadyUsers = new HashSet<>();

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
     * These are the currently running processes for which we have a ProcessInfo.
     * Note: needs to be static since the permission checking call chain is static.  This
     * all probably should be refactored into a separate permission checking object.
     */
    @GuardedBy("sActiveProcessInfoSelfLocked")
    static final SparseArray<ProcessInfo> sActiveProcessInfoSelfLocked = new SparseArray<>();

    /**
     * All of the processes we currently have running organized by pid.
     * The keys are the pid running the application.
     *
     * <p>NOTE: This object is protected by its own lock, NOT the global activity manager lock!
     */
    final PidMap mPidsSelfLocked = new PidMap();
    static final class PidMap {
        private final SparseArray<ProcessRecord> mPidMap = new SparseArray<>();

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

        void doAddInternal(int pid, ProcessRecord app) {
            mPidMap.put(pid, app);
        }

        boolean doRemoveInternal(int pid, ProcessRecord app) {
            final ProcessRecord existingApp = mPidMap.get(pid);
            if (existingApp != null && existingApp.getStartSeq() == app.getStartSeq()) {
                mPidMap.remove(pid);
                return true;
            }
            return false;
        }
    }

    private final PendingStartActivityUids mPendingStartActivityUids;

    /**
     * Puts the process record in the map.
     * <p>NOTE: Callers should avoid acquiring the mPidsSelfLocked lock before calling this
     * method.
     */
    @GuardedBy("this")
    void addPidLocked(ProcessRecord app) {
        final int pid = app.getPid();
        synchronized (mPidsSelfLocked) {
            mPidsSelfLocked.doAddInternal(pid, app);
        }
        synchronized (sActiveProcessInfoSelfLocked) {
            if (app.processInfo != null) {
                sActiveProcessInfoSelfLocked.put(pid, app.processInfo);
            } else {
                sActiveProcessInfoSelfLocked.remove(pid);
            }
        }
        mAtmInternal.onProcessMapped(pid, app.getWindowProcessController());
    }

    /**
     * Removes the process record from the map.
     * <p>NOTE: Callers should avoid acquiring the mPidsSelfLocked lock before calling this
     * method.
     */
    @GuardedBy("this")
    boolean removePidLocked(int pid, ProcessRecord app) {
        final boolean removed;
        synchronized (mPidsSelfLocked) {
            removed = mPidsSelfLocked.doRemoveInternal(pid, app);
        }
        if (removed) {
            synchronized (sActiveProcessInfoSelfLocked) {
                sActiveProcessInfoSelfLocked.remove(pid);
            }
            mAtmInternal.onProcessUnMapped(pid);
        }
        return removed;
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

        void dumpDebug(ProtoOutputStream proto, long fieldId) {
            final long pToken = proto.start(fieldId);
            proto.write(ImportanceTokenProto.PID, pid);
            if (token != null) {
                proto.write(ImportanceTokenProto.TOKEN, token.toString());
            }
            proto.write(ImportanceTokenProto.REASON, reason);
            proto.end(pToken);
        }
    }
    @GuardedBy("this")
    final SparseArray<ImportanceToken> mImportantProcesses = new SparseArray<ImportanceToken>();

    /** The processes that are allowed to use SCHED_FIFO prorioty. */
    @GuardedBy("mProcLock")
    final ArrayList<ProcessRecord> mSpecifiedFifoProcesses = new ArrayList<>();

    /**
     * List of records for processes that someone had tried to start before the
     * system was ready.  We don't start them at that point, but ensure they
     * are started by the time booting is complete.
     */
    @GuardedBy("this")
    final ArrayList<ProcessRecord> mProcessesOnHold = new ArrayList<ProcessRecord>();

    /**
     * List of persistent applications that are in the process
     * of being started.
     */
    @GuardedBy("this")
    final ArrayList<ProcessRecord> mPersistentStartingProcesses = new ArrayList<ProcessRecord>();

    private final ActivityMetricsLaunchObserver mActivityLaunchObserver =
            new ActivityMetricsLaunchObserver() {

        @Override
        public void onIntentStarted(@NonNull Intent intent, long timestampNanos) {
            synchronized (this) {
                mProcessList.getAppStartInfoTracker().onIntentStarted(intent, timestampNanos);
            }
        }

        @Override
        public void onIntentFailed(long id) {
            mProcessList.getAppStartInfoTracker().onIntentFailed(id);
        }

        @Override
        public void onActivityLaunched(long id, ComponentName name, int temperature, int userId) {
            mAppProfiler.onActivityLaunched();
            synchronized (ActivityManagerService.this) {
                ProcessRecord record = null;
                try {
                    record = getProcessRecordLocked(name.getPackageName(), mContext
                            .getPackageManager().getPackageUidAsUser(name.getPackageName(), 0,
                            userId));
                } catch (NameNotFoundException nnfe) {
                    // Ignore, record will be lost.
                }
                mProcessList.getAppStartInfoTracker().onActivityLaunched(id, name, temperature,
                        record);
            }
        }

        @Override
        public void onActivityLaunchCancelled(long id) {
            mProcessList.getAppStartInfoTracker().onActivityLaunchCancelled(id);
        }

        @Override
        public void onActivityLaunchFinished(long id, ComponentName name, long timestampNanos,
                int launchMode) {
            mProcessList.getAppStartInfoTracker().onActivityLaunchFinished(id, name,
                    timestampNanos, launchMode);
        }

        @Override
        public void onReportFullyDrawn(long id, long timestampNanos) {
            mProcessList.getAppStartInfoTracker().onReportFullyDrawn(id, timestampNanos);
        }
    };

    private volatile boolean mBinderTransactionTrackingEnabled = false;

    /**
     * Fingerprints (hashCode()) of stack traces that we've
     * already logged DropBox entries for.  Guarded by itself.  If
     * something (rogue user app) forces this over
     * MAX_DUP_SUPPRESSED_STACKS entries, the contents are cleared.
     */
    @GuardedBy("mAlreadyLoggedViolatedStacks")
    private final HashSet<Integer> mAlreadyLoggedViolatedStacks = new HashSet<Integer>();
    private static final int MAX_DUP_SUPPRESSED_STACKS = 5000;

    /**
     * Keeps track of all IIntentReceivers that have been registered for broadcasts.
     * Hash keys are the receiver IBinder, hash value is a ReceiverList.
     */
    @GuardedBy("this")
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
        protected BroadcastFilter newResult(@NonNull Computer computer, BroadcastFilter filter,
                int match, int userId, long customFlags) {
            if (userId == UserHandle.USER_ALL || filter.owningUserId == UserHandle.USER_ALL
                    || userId == filter.owningUserId) {
                return super.newResult(computer, filter, match, userId, customFlags);
            }
            return null;
        }

        @Override
        protected IntentFilter getIntentFilter(@NonNull BroadcastFilter input) {
            return input;
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
    @GuardedBy("mStickyBroadcasts")
    final SparseArray<ArrayMap<String, ArrayList<StickyBroadcast>>> mStickyBroadcasts =
            new SparseArray<>();

    @VisibleForTesting
    static final class StickyBroadcast {
        public Intent intent;
        public boolean deferUntilActive;
        public int originalCallingUid;
        /** The snapshot process state of the app who sent this broadcast */
        public int originalCallingAppProcessState;
        public String resolvedDataType;

        public static StickyBroadcast create(Intent intent, boolean deferUntilActive,
                int originalCallingUid, int originalCallingAppProcessState,
                String resolvedDataType) {
            final StickyBroadcast b = new StickyBroadcast();
            b.intent = intent;
            b.deferUntilActive = deferUntilActive;
            b.originalCallingUid = originalCallingUid;
            b.originalCallingAppProcessState = originalCallingAppProcessState;
            b.resolvedDataType = resolvedDataType;
            return b;
        }

        @Override
        public String toString() {
            return "{intent=" + intent + ", defer=" + deferUntilActive + ", originalCallingUid="
                    + originalCallingUid + ", originalCallingAppProcessState="
                    + originalCallingAppProcessState + ", type=" + resolvedDataType + "}";
        }
    }

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
    @GuardedBy("this")
    final SparseArray<ArrayMap<ComponentName, SparseArray<ArrayMap<String, Association>>>>
            mAssociations = new SparseArray<>();
    boolean mTrackingAssociations;

    /**
     * Backup/restore process management
     */
    @GuardedBy("this")
    final SparseArray<BackupRecord> mBackupTargets = new SparseArray<>();

    final ContentProviderHelper mCpHelper;

    CoreSettingsObserver mCoreSettingsObserver;

    /**
     * All information we have collected about the runtime performance of
     * any user id that can impact battery performance.
     */
    final BatteryStatsService mBatteryStatsService;

    StatsPullAtomServiceInternal mStatsPullAtomServiceInternal;

    /**
     * Information about component usage
     */
    volatile UsageStatsManagerInternal mUsageStatsService;

    /**
     * Access to DeviceIdleController service.
     */
    DeviceIdleInternal mLocalDeviceIdleController;

    /**
     * Power-save allowlisted app-ids (not including except-idle-allowlisted ones).
     */
    @CompositeRWLock({"this", "mProcLock"})
    int[] mDeviceIdleAllowlist = new int[0];

    /**
     * Power-save allowlisted app-ids (including except-idle-allowlisted ones).
     */
    @CompositeRWLock({"this", "mProcLock"})
    int[] mDeviceIdleExceptIdleAllowlist = new int[0];

    /**
     * Set of app ids that are temporarily allowed to escape bg check due to high-pri message
     */
    @CompositeRWLock({"this", "mProcLock"})
    int[] mDeviceIdleTempAllowlist = new int[0];

    static final class PendingTempAllowlist {
        final int targetUid;
        final long duration;
        final String tag;
        final int type;
        final @ReasonCode int reasonCode;
        final int callingUid;

        PendingTempAllowlist(int targetUid, long duration, @ReasonCode int reasonCode, String tag,
                int type, int callingUid) {
            this.targetUid = targetUid;
            this.duration = duration;
            this.tag = tag;
            this.type = type;
            this.reasonCode = reasonCode;
            this.callingUid = callingUid;
        }

        void dumpDebug(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);
            proto.write(ActivityManagerServiceDumpProcessesProto.PendingTempWhitelist.TARGET_UID,
                    targetUid);
            proto.write(ActivityManagerServiceDumpProcessesProto.PendingTempWhitelist.DURATION_MS,
                    duration);
            proto.write(ActivityManagerServiceDumpProcessesProto.PendingTempWhitelist.TAG, tag);
            proto.write(ActivityManagerServiceDumpProcessesProto.PendingTempWhitelist.TYPE, type);
            proto.write(ActivityManagerServiceDumpProcessesProto.PendingTempWhitelist.REASON_CODE,
                    reasonCode);
            proto.write(ActivityManagerServiceDumpProcessesProto.PendingTempWhitelist.CALLING_UID,
                    callingUid);
            proto.end(token);
        }
    }

    @CompositeRWLock({"this", "mProcLock"})
    final PendingTempAllowlists mPendingTempAllowlist = new PendingTempAllowlists(this);

    public static final class FgsTempAllowListItem {
        final long mDuration;
        final @PowerExemptionManager.ReasonCode int mReasonCode;
        final String mReason;
        final int mCallingUid;

        FgsTempAllowListItem(long duration, @PowerExemptionManager.ReasonCode int reasonCode,
                String reason, int callingUid) {
            mDuration = duration;
            mReasonCode = reasonCode;
            mReason = reason;
            mCallingUid = callingUid;
        }

        void dump(PrintWriter pw) {
            pw.print(" duration=" + mDuration +
                    " callingUid=" + UserHandle.formatUid(mCallingUid) +
                    " reasonCode=" + PowerExemptionManager.reasonCodeToString(mReasonCode) +
                    " reason=" + mReason);
        }
    }

    /**
     * The temp-allowlist that is allowed to start FGS from background.
     */
    @CompositeRWLock({"this", "mProcLock"})
    final FgsTempAllowList<FgsTempAllowListItem> mFgsStartTempAllowList =
            new FgsTempAllowList();

    static final FgsTempAllowListItem FAKE_TEMP_ALLOW_LIST_ITEM = new FgsTempAllowListItem(
            Long.MAX_VALUE, REASON_SYSTEM_ALLOW_LISTED, "", INVALID_UID);

    /*
     * List of uids that are allowed to have while-in-use permission when FGS is started from
     * background.
     */
    private final FgsTempAllowList<String> mFgsWhileInUseTempAllowList =
            new FgsTempAllowList();

    /**
     * Information about and control over application operations
     */
    final AppOpsService mAppOpsService;
    private AppOpsManager mAppOpsManager;

    /**
     * List of initialization arguments to pass to all processes when binding applications to them.
     * For example, references to the commonly used services.
     */
    ArrayMap<String, IBinder> mAppBindArgs;
    ArrayMap<String, IBinder> mIsolatedAppBindArgs;

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
    @GuardedBy("mProcLock")
    long mLastPowerCheckUptime;

    /**
     * For some direct access we need to power manager.
     */
    PowerManagerInternal mLocalPowerManager;

    /**
     * State of external calls telling us if the device is awake or asleep.
     */
    AtomicInteger mWakefulness = new AtomicInteger(PowerManagerInternal.WAKEFULNESS_AWAKE);

    /**
     * The uptime of the last time we performed idle maintenance.
     */
    @GuardedBy("mProcLock")
    long mLastIdleTime = SystemClock.uptimeMillis();

    /**
     * For reporting to battery stats the current top application.
     *
     * <p>It has its own lock to avoid from the need of double locking if using the global
     * ActivityManagerService lock and proc lock to guard it.</p>
     */
    @GuardedBy("mCurResumedAppLock")
    private String mCurResumedPackage = null;

    @GuardedBy("mCurResumedAppLock")
    private int mCurResumedUid = -1;

    /**
     * Dedicated lock for {@link #mCurResumedPackage} and {@link #mCurResumedUid}.
     */
    private final Object mCurResumedAppLock = new Object();

    /**
     * For reporting to battery stats the apps currently running foreground
     * service.  The ProcessMap is package/uid tuples; each of these contain
     * an array of the currently foreground processes.
     */
    @GuardedBy("this")
    final ProcessMap<ArrayList<ProcessRecord>> mForegroundPackages
            = new ProcessMap<ArrayList<ProcessRecord>>();

    /**
     * The list of foreground service state change listeners.
     */
    @GuardedBy("this")
    final ArrayList<ForegroundServiceStateListener> mForegroundServiceStateListeners =
            new ArrayList<>();

    /**
     * The list of broadcast event listeners.
     */
    final CopyOnWriteArrayList<BroadcastEventListener> mBroadcastEventListeners =
            new CopyOnWriteArrayList<>();

    /**
     * The list of bind service event listeners.
     */
    final CopyOnWriteArrayList<BindServiceEventListener> mBindServiceEventListeners =
            new CopyOnWriteArrayList<>();

    /**
     * Set if the systemServer made a call to enterSafeMode.
     */
    @GuardedBy("this")
    boolean mSafeMode;

    @GuardedBy("this")
    private String mDebugApp = null;

    @GuardedBy("this")
    private boolean mWaitForDebugger = false;

    @GuardedBy("this")
    private boolean mSuspendUponWait = false;

    @GuardedBy("this")
    private boolean mDebugTransient = false;

    @GuardedBy("this")
    private String mOrigDebugApp = null;

    @GuardedBy("this")
    private boolean mOrigWaitForDebugger = false;

    @GuardedBy("this")
    boolean mAlwaysFinishActivities = false;

    @GuardedBy("mProcLock")
    private String mTrackAllocationApp = null;

    @GuardedBy("this")
    String mNativeDebuggingApp = null;

    final Injector mInjector;

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

    @GuardedBy("mOomAdjObserverLock")
    OomAdjObserver mCurOomAdjObserver;

    @GuardedBy("mOomAdjObserverLock")
    int mCurOomAdjUid;

    /**
     * Dedicated lock for {@link #mCurOomAdjObserver} and {@link #mCurOomAdjUid}.
     */
    final Object mOomAdjObserverLock = new Object();

    interface OomAdjObserver {
        void onOomAdjMessage(String msg);
    }

    final AnrHelper mAnrHelper = new AnrHelper(this);

    /** Set to true after the system has finished booting. */
    volatile boolean mBooted = false;

    /**
     * Current boot phase.
     */
    int mBootPhase;

    /**
     * The time stamp that all apps have received BOOT_COMPLETED.
     */
    volatile long mBootCompletedTimestamp;

    @GuardedBy("this")
    boolean mDeterministicUidIdle = false;

    @VisibleForTesting
    public WindowManagerService mWindowManager;
    WindowManagerInternal mWmInternal;
    @VisibleForTesting
    public ActivityTaskManagerService mActivityTaskManager;
    @VisibleForTesting
    public ActivityTaskManagerInternal mAtmInternal;
    UriGrantsManagerInternal mUgmInternal;
    @VisibleForTesting
    public final ActivityManagerInternal mInternal;
    final ActivityThread mSystemThread;

    final UidObserverController mUidObserverController;
    private volatile IUidObserver mNetworkPolicyUidObserver;

    @GuardedBy("mUidNetworkBlockedReasons")
    private final SparseIntArray mUidNetworkBlockedReasons = new SparseIntArray();

    final AppRestrictionController mAppRestrictionController;

    /**
     * The collection of the MediaProjection tokens per UID, for the apps that are allowed to
     * start FGS with the type "mediaProjection"; this permission is granted via the request over
     * the call to {@link android.media.project.MediaProjectionManager#createScreenCaptureIntent()}.
     *
     * <p>Note, the "token" here is actually an instance of
     * {@link android.media.projection.IMediaProjection}.</p>
     */
    @GuardedBy("mMediaProjectionTokenMap")
    private final SparseArray<ArraySet<IBinder>> mMediaProjectionTokenMap = new SparseArray();

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
                appDiedLocked(mApp, mPid, mAppThread, true, null);
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
    static final int ABORT_DUMPHEAP_MSG = 51;
    static final int SHUTDOWN_UI_AUTOMATION_CONNECTION_MSG = 56;
    static final int CONTENT_PROVIDER_PUBLISH_TIMEOUT_MSG = 57;
    static final int IDLE_UIDS_MSG = 58;
    static final int HANDLE_TRUST_STORAGE_UPDATE_MSG = 63;
    static final int SERVICE_FOREGROUND_TIMEOUT_MSG = 66;
    static final int SERVICE_FOREGROUND_TIMEOUT_ANR_MSG = 67;
    static final int PUSH_TEMP_ALLOWLIST_UI_MSG = 68;
    static final int SERVICE_FOREGROUND_CRASH_MSG = 69;
    static final int DISPATCH_OOM_ADJ_OBSERVER_MSG = 70;
    static final int KILL_APP_ZYGOTE_MSG = 71;
    static final int BINDER_HEAVYHITTER_AUTOSAMPLER_TIMEOUT_MSG = 72;
    static final int WAIT_FOR_CONTENT_PROVIDER_TIMEOUT_MSG = 73;
    static final int DISPATCH_SENDING_BROADCAST_EVENT = 74;
    static final int DISPATCH_BINDING_SERVICE_EVENT = 75;
    static final int SERVICE_SHORT_FGS_TIMEOUT_MSG = 76;
    static final int SERVICE_SHORT_FGS_PROCSTATE_TIMEOUT_MSG = 77;
    static final int SERVICE_SHORT_FGS_ANR_TIMEOUT_MSG = 78;
    static final int UPDATE_CACHED_APP_HIGH_WATERMARK = 79;
    static final int ADD_UID_TO_OBSERVER_MSG = 80;
    static final int REMOVE_UID_FROM_OBSERVER_MSG = 81;
    static final int BIND_APPLICATION_TIMEOUT_SOFT_MSG = 82;
    static final int BIND_APPLICATION_TIMEOUT_HARD_MSG = 83;
    static final int SERVICE_FGS_TIMEOUT_MSG = 84;
    static final int SERVICE_FGS_CRASH_TIMEOUT_MSG = 85;

    static final int FIRST_BROADCAST_QUEUE_MSG = 200;

    /**
     * Flag whether the current user is a "monkey", i.e. whether
     * the UI is driven by a UI automation tool.
     */
    private volatile boolean mUserIsMonkey;

    @VisibleForTesting
    public final ServiceThread mHandlerThread;
    final MainHandler mHandler;
    final Handler mUiHandler;
    final ServiceThread mProcStartHandlerThread;
    final ProcStartHandler mProcStartHandler;

    ActivityManagerConstants mConstants;

    // Encapsulates the global setting "hidden_api_blacklist_exemptions"
    final HiddenApiSettings mHiddenApiBlacklist;

    private final PlatformCompat mPlatformCompat;

    PackageManagerInternal mPackageManagerInt;
    PermissionManagerServiceInternal mPermissionManagerInt;
    private TestUtilityService mTestUtilityService;

    // Packages which have received a (LOCKED_)BOOT_COMPLETED broadcast since
    // the private space profile has been started
    @GuardedBy("this")
    private final ArraySet<String> mPrivateSpaceBootCompletedPackages = new ArraySet<String>();

    /**
     * Whether to force background check on all apps (for battery saver) or not.
     */
    @CompositeRWLock({"this", "mProcLock"})
    private boolean mForceBackgroundCheck;

    private static String sTheRealBuildSerial = Build.UNKNOWN;

    @GuardedBy("mProcLock")
    private ParcelFileDescriptor[] mLifeMonitorFds;

    static final HostingRecord sNullHostingRecord =
            new HostingRecord(HostingRecord.HOSTING_TYPE_EMPTY);
    /**
     * Used to notify activity lifecycle events.
     */
    @Nullable volatile ContentCaptureManagerInternal mContentCaptureService;

    /*
     * The default duration for the binder heavy hitter auto sampler
     */
    private static final long BINDER_HEAVY_HITTER_AUTO_SAMPLER_DURATION_MS = 300000L;

    /**
     * The default throttling duration for the binder heavy hitter auto sampler
     */
    private static final long BINDER_HEAVY_HITTER_AUTO_SAMPLER_THROTTLE_MS = 3600000L;

    /**
     * The last time when the binder heavy hitter auto sampler started.
     */
    @GuardedBy("mProcLock")
    private long mLastBinderHeavyHitterAutoSamplerStart = 0L;

    AppProfiler mAppProfiler;

    private static final int INDEX_NATIVE_PSS = 0;
    private static final int INDEX_NATIVE_SWAP_PSS = 1;
    private static final int INDEX_NATIVE_RSS = 2;
    private static final int INDEX_NATIVE_PRIVATE_DIRTY = 3;
    private static final int INDEX_DALVIK_PSS = 4;
    private static final int INDEX_DALVIK_SWAP_PSS = 5;
    private static final int INDEX_DALVIK_RSS = 6;
    private static final int INDEX_DALVIK_PRIVATE_DIRTY = 7;
    private static final int INDEX_OTHER_PSS = 8;
    private static final int INDEX_OTHER_SWAP_PSS = 9;
    private static final int INDEX_OTHER_RSS = 10;
    private static final int INDEX_OTHER_PRIVATE_DIRTY = 11;
    private static final int INDEX_TOTAL_PSS = 12;
    private static final int INDEX_TOTAL_SWAP_PSS = 13;
    private static final int INDEX_TOTAL_RSS = 14;
    private static final int INDEX_TOTAL_PRIVATE_DIRTY = 15;
    private static final int INDEX_TOTAL_NATIVE_PSS = 16;
    private static final int INDEX_TOTAL_MEMTRACK_GRAPHICS = 17;
    private static final int INDEX_TOTAL_MEMTRACK_GL = 18;
    private static final int INDEX_LAST = 19;

    /**
     * Used to notify activity lifecycle events.
     */
    @Nullable
    volatile ActivityManagerInternal.VoiceInteractionManagerProvider
            mVoiceInteractionManagerProvider;

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
                    synchronized (mProcLock) {
                        ProcessRecord proc = (ProcessRecord) data.get("app");
                        if (proc == null) {
                            Slog.e(TAG, "App not found when showing strict mode dialog.");
                            break;
                        }
                        if (proc.mErrorState.getDialogController().hasViolationDialogs()) {
                            Slog.e(TAG, "App already has strict mode dialog: " + proc);
                            return;
                        }
                        AppErrorResult res = (AppErrorResult) data.get("result");
                        if (mAtmInternal.showStrictModeViolationDialog()) {
                            proc.mErrorState.getDialogController().showViolationDialogs(res);
                        } else {
                            // The device is asleep, so just pretend that the user
                            // saw a crash dialog and hit "force quit".
                            res.set(0);
                        }
                    }
                    ensureBootCompleted();
                } break;
                case WAIT_FOR_DEBUGGER_UI_MSG: {
                    synchronized (mProcLock) {
                        ProcessRecord app = (ProcessRecord) msg.obj;
                        if (msg.arg1 != 0) {
                            if (!app.hasWaitedForDebugger()) {
                                app.mErrorState.getDialogController().showDebugWaitingDialogs();
                                app.setWaitedForDebugger(true);
                            }
                        } else {
                            app.mErrorState.getDialogController().clearWaitingDialog();
                        }
                    }
                } break;
                case DISPATCH_PROCESSES_CHANGED_UI_MSG: {
                    mProcessList.dispatchProcessesChanged();
                    break;
                }
                case DISPATCH_PROCESS_DIED_UI_MSG: {
                    if (false) { // DO NOT SUBMIT WITH TRUE
                        maybeTriggerWatchdog();
                    }
                    final int pid = msg.arg1;
                    final int uid = msg.arg2;
                    mProcessList.dispatchProcessDied(pid, uid);
                    break;
                }
                case DISPATCH_OOM_ADJ_OBSERVER_MSG: {
                    dispatchOomAdjObserver((String) msg.obj);
                } break;
                case PUSH_TEMP_ALLOWLIST_UI_MSG: {
                    pushTempAllowlist();
                } break;
                case ADD_UID_TO_OBSERVER_MSG: {
                    mUidObserverController.addUidToObserverImpl((IBinder) msg.obj, msg.arg1);
                } break;
                case REMOVE_UID_FROM_OBSERVER_MSG: {
                    mUidObserverController.removeUidFromObserverImpl((IBinder) msg.obj, msg.arg1);
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
                    mAppProfiler.performAppGcsIfAppropriateLocked();
                }
            } break;
            case SERVICE_TIMEOUT_MSG: {
                mServices.serviceTimeout((ProcessRecord) msg.obj);
            } break;
            case SERVICE_FOREGROUND_TIMEOUT_MSG: {
                mServices.serviceForegroundTimeout((ServiceRecord) msg.obj);
            } break;
            case SERVICE_FOREGROUND_TIMEOUT_ANR_MSG: {
                SomeArgs args = (SomeArgs) msg.obj;
                mServices.serviceForegroundTimeoutANR((ProcessRecord) args.arg1,
                        (TimeoutRecord) args.arg2);
                args.recycle();
            } break;
            case SERVICE_FOREGROUND_CRASH_MSG: {
                SomeArgs args = (SomeArgs) msg.obj;
                mServices.serviceForegroundCrash(
                        (ProcessRecord) args.arg1,
                        (String) args.arg2,
                        (ComponentName) args.arg3);
                args.recycle();
            } break;
            case UPDATE_TIME_ZONE: {
                synchronized (mProcLock) {
                    mProcessList.forEachLruProcessesLOSP(false, app -> {
                        final IApplicationThread thread = app.getThread();
                        if (thread != null) {
                            try {
                                thread.updateTimeZone();
                            } catch (RemoteException ex) {
                                Slog.w(TAG, "Failed to update time zone for: "
                                        + app.info.processName);
                            }
                            }
                        });
                    }
            } break;
            case CLEAR_DNS_CACHE_MSG: {
                synchronized (mProcLock) {
                    mProcessList.clearAllDnsCacheLOSP();
                }
            } break;
            case UPDATE_HTTP_PROXY_MSG: {
                mProcessList.setAllHttpProxy();
            } break;
            case PROC_START_TIMEOUT_MSG: {
                ProcessRecord app = (ProcessRecord) msg.obj;
                synchronized (ActivityManagerService.this) {
                    handleProcessStartOrKillTimeoutLocked(app, /* isKillTimeout */ false);
                }
            } break;
            case CONTENT_PROVIDER_PUBLISH_TIMEOUT_MSG: {
                ProcessRecord app = (ProcessRecord) msg.obj;
                synchronized (ActivityManagerService.this) {
                    mCpHelper.processContentProviderPublishTimedOutLocked(app);
                }
            } break;
            case KILL_APPLICATION_MSG: {
                synchronized (ActivityManagerService.this) {
                    final int appId = msg.arg1;
                    final int userId = msg.arg2;
                    SomeArgs args = (SomeArgs) msg.obj;
                    String pkg = (String) args.arg1;
                    String reason = (String) args.arg2;
                    int exitInfoReason = (int) args.arg3;
                    args.recycle();
                    forceStopPackageLocked(pkg, appId, false, false, true, false,
                            false, false, userId, reason, exitInfoReason);
                }
            } break;

                case KILL_APP_ZYGOTE_MSG: {
                    synchronized (ActivityManagerService.this) {
                        final AppZygote appZygote = (AppZygote) msg.obj;
                        mProcessList.killAppZygoteIfNeededLocked(appZygote, false /* force */);
                    }
                } break;
            case CHECK_EXCESSIVE_POWER_USE_MSG: {
                checkExcessivePowerUsage();
                removeMessages(CHECK_EXCESSIVE_POWER_USE_MSG);
                Message nmsg = obtainMessage(CHECK_EXCESSIVE_POWER_USE_MSG);
                sendMessageDelayed(nmsg, mConstants.POWER_CHECK_INTERVAL);
            } break;
            case REPORT_MEM_USAGE_MSG: {
                final ArrayList<ProcessMemInfo> memInfos = (ArrayList<ProcessMemInfo>) msg.obj;
                Thread thread = new Thread() {
                    @Override public void run() {
                        mAppProfiler.reportMemUsage(memInfos);
                    }
                };
                thread.start();
                break;
            }
            case UPDATE_TIME_PREFERENCE_MSG: {
                // The user's time format preference might have changed.
                // For convenience we re-use the Intent extra values.
                synchronized (mProcLock) {
                    mProcessList.updateAllTimePrefsLOSP(msg.arg1);
                }
                break;
            }
            case NOTIFY_CLEARTEXT_NETWORK_MSG: {
                final int uid = msg.arg1;
                final byte[] firstPacket = (byte[]) msg.obj;

                synchronized (mProcLock) {
                    synchronized (mPidsSelfLocked) {
                        for (int i = 0; i < mPidsSelfLocked.size(); i++) {
                            final ProcessRecord p = mPidsSelfLocked.valueAt(i);
                            final IApplicationThread thread = p.getThread();
                            if (p.uid == uid && thread != null) {
                                try {
                                    thread.notifyCleartextNetwork(firstPacket);
                                } catch (RemoteException ignored) {
                                }
                            }
                        }
                    }
                }
            } break;
            case POST_DUMP_HEAP_NOTIFICATION_MSG: {
                mAppProfiler.handlePostDumpHeapNotification();
            } break;
            case ABORT_DUMPHEAP_MSG: {
                mAppProfiler.handleAbortDumpHeap((String) msg.obj);
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
                synchronized (mProcLock) {
                    mProcessList.handleAllTrustStorageUpdateLOSP();
                }
            } break;
                case BINDER_HEAVYHITTER_AUTOSAMPLER_TIMEOUT_MSG: {
                    handleBinderHeavyHitterAutoSamplerTimeOut();
                } break;
                case WAIT_FOR_CONTENT_PROVIDER_TIMEOUT_MSG: {
                    synchronized (ActivityManagerService.this) {
                        ((ContentProviderRecord) msg.obj).onProviderPublishStatusLocked(false);
                    }
                } break;
                case DISPATCH_SENDING_BROADCAST_EVENT: {
                    mBroadcastEventListeners.forEach(l ->
                            l.onSendingBroadcast((String) msg.obj, msg.arg1));
                } break;
                case DISPATCH_BINDING_SERVICE_EVENT: {
                    mBindServiceEventListeners.forEach(l ->
                            l.onBindingService((String) msg.obj, msg.arg1));
                } break;
                case SERVICE_SHORT_FGS_TIMEOUT_MSG: {
                    mServices.onShortFgsTimeout((ServiceRecord) msg.obj);
                } break;
                case SERVICE_SHORT_FGS_PROCSTATE_TIMEOUT_MSG: {
                    mServices.onShortFgsProcstateTimeout((ServiceRecord) msg.obj);
                } break;
                case SERVICE_SHORT_FGS_ANR_TIMEOUT_MSG: {
                    mServices.onShortFgsAnrTimeout((ServiceRecord) msg.obj);
                } break;
                case UPDATE_CACHED_APP_HIGH_WATERMARK: {
                    mAppProfiler.mCachedAppsWatermarkData.updateCachedAppsSnapshot((long) msg.obj);
                } break;
                case BIND_APPLICATION_TIMEOUT_SOFT_MSG: {
                    handleBindApplicationTimeoutSoft((ProcessRecord) msg.obj, msg.arg1);
                } break;
                case BIND_APPLICATION_TIMEOUT_HARD_MSG: {
                    handleBindApplicationTimeoutHard((ProcessRecord) msg.obj);
                } break;
                case SERVICE_FGS_TIMEOUT_MSG: {
                    mServices.onFgsTimeout((ServiceRecord) msg.obj);
                } break;
                case SERVICE_FGS_CRASH_TIMEOUT_MSG: {
                    mServices.onFgsCrashTimeout((ServiceRecord) msg.obj);
                } break;
            }
        }
    }


    public void setSystemProcess() {
        try {
            ServiceManager.addService(Context.ACTIVITY_SERVICE, this, /* allowIsolated= */ true,
                    DUMP_FLAG_PRIORITY_CRITICAL | DUMP_FLAG_PRIORITY_NORMAL | DUMP_FLAG_PROTO);
            ServiceManager.addService(ProcessStats.SERVICE_NAME, mProcessStats);
            ServiceManager.addService("meminfo", new MemBinder(this), /* allowIsolated= */ false,
                    DUMP_FLAG_PRIORITY_HIGH);
            ServiceManager.addService("gfxinfo", new GraphicsBinder(this));
            ServiceManager.addService("dbinfo", new DbBinder(this));
            mAppProfiler.setCpuInfoService();
            ServiceManager.addService("permission", new PermissionController(this));
            ServiceManager.addService("processinfo", new ProcessInfoService(this));
            ServiceManager.addService("cacheinfo", new CacheBinder(this));

            ApplicationInfo info = mContext.getPackageManager().getApplicationInfo(
                    "android", STOCK_PM_FLAGS | MATCH_SYSTEM_ONLY);
            mSystemThread.installSystemApplicationInfo(info, getClass().getClassLoader());

            synchronized (this) {
                ProcessRecord app = mProcessList.newProcessRecordLocked(info, info.processName,
                        false,
                        0,
                        false,
                        0,
                        null,
                        new HostingRecord(HostingRecord.HOSTING_TYPE_SYSTEM));
                app.setPersistent(true);
                app.setPid(MY_PID);
                app.mState.setMaxAdj(ProcessList.SYSTEM_ADJ);
                app.makeActive(mSystemThread.getApplicationThread(), mProcessStats);
                app.mProfile.addHostingComponentType(HOSTING_COMPONENT_TYPE_SYSTEM);
                addPidLocked(app);
                updateLruProcessLocked(app, false, null);
                updateOomAdjLocked(OOM_ADJ_REASON_SYSTEM_INIT);
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(
                    "Unable to find android system package", e);
        }

        // Start watching app ops after we and the package manager are up and running.
        mAppOpsService.startWatchingMode(AppOpsManager.OP_RUN_IN_BACKGROUND, null,
                new IAppOpsCallback.Stub() {
                    @Override public void opChanged(int op, int uid, String packageName,
                            String persistentDeviceId) {
                        if (op == AppOpsManager.OP_RUN_IN_BACKGROUND && packageName != null) {
                            if (getAppOpsManager().checkOpNoThrow(op, uid, packageName)
                                    != AppOpsManager.MODE_ALLOWED) {
                                runInBackgroundDisabled(uid);
                            }
                        }
                    }
                });

        final int[] cameraOp = {AppOpsManager.OP_CAMERA};
        mAppOpsService.startWatchingActive(cameraOp, new IAppOpsActiveCallback.Stub() {
            @Override
            public void opActiveChanged(int op, int uid, String packageName, String attributionTag,
                    int virtualDeviceId, boolean active, @AttributionFlags int attributionFlags,
                    int attributionChainId) {
                cameraActiveChanged(uid, active);
            }
        });
    }

    public void setWindowManager(WindowManagerService wm) {
        synchronized (this) {
            mWindowManager = wm;
            mWmInternal = LocalServices.getService(WindowManagerInternal.class);
            mActivityTaskManager.setWindowManager(wm);
        }
    }

    /**
     * @param usageStatsManager shouldn't be null
     */
    public void setUsageStatsManager(@NonNull UsageStatsManagerInternal usageStatsManager) {
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

    /**
     * Sets a policy for handling app ops.
     *
     * @param appOpsPolicy The policy.
     */
    public void setAppOpsPolicy(@Nullable CheckOpsDelegate appOpsPolicy) {
        mAppOpsService.setAppOpsPolicy(appOpsPolicy);
    }

    public IAppOpsService getAppOpsService() {
        return mAppOpsService;
    }

    /**
     * Sets the internal voice interaction manager service.
     */
    private void setVoiceInteractionManagerProvider(
            @Nullable ActivityManagerInternal.VoiceInteractionManagerProvider provider) {
        mVoiceInteractionManagerProvider = provider;
    }

    /**
     * Represents volatile states associated with a Dropbox entry.
     * <p>
     * These states, such as the process frozen state, can change quickly over time and thus
     * should be captured as soon as possible to ensure accurate state. If a state is undefined,
     * it means that the state was not read early and a fallback value can be used.
     * </p>
     */
    static class VolatileDropboxEntryStates {
        private final Boolean mIsProcessFrozen;
        private final ZonedDateTime mTimestamp;

        private VolatileDropboxEntryStates(Boolean frozenState, ZonedDateTime timestamp) {
            this.mIsProcessFrozen = frozenState;
            this.mTimestamp = timestamp;
        }

        public static VolatileDropboxEntryStates withProcessFrozenStateAndTimestamp(
                boolean frozenState, ZonedDateTime timestamp) {
            return new VolatileDropboxEntryStates(frozenState, timestamp);
        }

        public Boolean isProcessFrozen() {
            return mIsProcessFrozen;
        }

        public ZonedDateTime getTimestamp() {
            return mTimestamp;
        }
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
            try {
                mActivityManagerService.mOomAdjuster.mCachedAppOptimizer.enableFreezer(false);

                if (!DumpUtils.checkDumpAndUsageStatsPermission(mActivityManagerService.mContext,
                        "meminfo", pw)) return;
                PriorityDump.dump(mPriorityDumper, fd, pw, args);
            } finally {
                mActivityManagerService.mOomAdjuster.mCachedAppOptimizer.enableFreezer(true);
            }
        }
    }

    static class GraphicsBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        GraphicsBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            try {
                mActivityManagerService.mOomAdjuster.mCachedAppOptimizer.enableFreezer(false);

                if (!DumpUtils.checkDumpAndUsageStatsPermission(mActivityManagerService.mContext,
                        "gfxinfo", pw)) return;
                mActivityManagerService.dumpGraphicsHardwareUsage(fd, pw, args);
            } finally {
                mActivityManagerService.mOomAdjuster.mCachedAppOptimizer.enableFreezer(true);
            }
        }
    }

    static class DbBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        DbBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            try {
                mActivityManagerService.mOomAdjuster.mCachedAppOptimizer.enableFreezer(false);

                if (!DumpUtils.checkDumpAndUsageStatsPermission(mActivityManagerService.mContext,
                        "dbinfo", pw)) return;
                mActivityManagerService.dumpDbInfo(fd, pw, args);
            } finally {
                mActivityManagerService.mOomAdjuster.mCachedAppOptimizer.enableFreezer(true);
            }
        }
    }

    static class CacheBinder extends Binder {
        ActivityManagerService mActivityManagerService;

        CacheBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            try {
                mActivityManagerService.mOomAdjuster.mCachedAppOptimizer.enableFreezer(false);

                if (!DumpUtils.checkDumpAndUsageStatsPermission(mActivityManagerService.mContext,
                        "cacheinfo", pw)) {
                    return;
                }

                mActivityManagerService.dumpBinderCacheContents(fd, pw, args);
            } finally {
                mActivityManagerService.mOomAdjuster.mCachedAppOptimizer.enableFreezer(true);
            }
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
            } else if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
                mService.mPackageWatchdog.onPackagesReady();
                mService.scheduleHomeTimeout();
            }
        }

        @Override
        public void onUserStopped(@NonNull TargetUser user) {
            mService.mBatteryStatsService.onCleanupUser(user.getUserIdentifier());

            if (android.os.Flags.allowPrivateProfile()
                    && android.multiuser.Flags.enablePrivateSpaceFeatures()) {
                final UserManagerInternal umInternal =
                        LocalServices.getService(UserManagerInternal.class);
                UserInfo userInfo = umInternal.getUserInfo(user.getUserIdentifier());

                if (userInfo != null && userInfo.isPrivateProfile()) {
                    synchronized (mService) {
                        mService.mPrivateSpaceBootCompletedPackages.clear();
                    }
                }
            }
        }

        public ActivityManagerService getService() {
            return mService;
        }
    }

    private void maybeLogUserspaceRebootEvent() {
        if (!UserspaceRebootLogger.shouldLogUserspaceRebootEvent()) {
            return;
        }
        final int userId = mUserController.getCurrentUserId();
        if (userId != UserHandle.USER_SYSTEM) {
            // Only log for user0.
            return;
        }
        // TODO(b/148767783): should we check all profiles under user0?
        UserspaceRebootLogger.logEventAsync(StorageManager.isCeStorageUnlocked(userId),
                BackgroundThread.getExecutor());
    }

    /**
     * Encapsulates global settings related to hidden API enforcement behaviour, including tracking
     * the latest value via a content observer.
     */
    static class HiddenApiSettings extends ContentObserver
            implements DeviceConfig.OnPropertiesChangedListener {

        private final Context mContext;
        private boolean mBlacklistDisabled;
        private String mExemptionsStr;
        private List<String> mExemptions = Collections.emptyList();
        private int mLogSampleRate = -1;
        private int mStatslogSampleRate = -1;
        @HiddenApiEnforcementPolicy private int mPolicy = HIDDEN_API_ENFORCEMENT_DEFAULT;

        /**
         * Sampling rate for hidden API access event logs with libmetricslogger, as an integer in
         * the range 0 to 0x10000 inclusive.
         *
         * @hide
         */
        public static final String HIDDEN_API_ACCESS_LOG_SAMPLING_RATE =
                "hidden_api_access_log_sampling_rate";

        /**
         * Sampling rate for hidden API access event logging with statslog, as an integer in the
         * range 0 to 0x10000 inclusive.
         *
         * @hide
         */
        public static final String HIDDEN_API_ACCESS_STATSLOG_SAMPLING_RATE =
                "hidden_api_access_statslog_sampling_rate";

        public void onPropertiesChanged(DeviceConfig.Properties properties) {
            int logSampleRate = properties.getInt(HIDDEN_API_ACCESS_LOG_SAMPLING_RATE, 0x0);
            if (logSampleRate < 0 || logSampleRate > 0x10000) {
                logSampleRate = -1;
            }
            if (logSampleRate != -1 && logSampleRate != mLogSampleRate) {
                mLogSampleRate = logSampleRate;
                ZYGOTE_PROCESS.setHiddenApiAccessLogSampleRate(mLogSampleRate);
            }

            int statslogSampleRate =
                    properties.getInt(HIDDEN_API_ACCESS_STATSLOG_SAMPLING_RATE, 0);
            if (statslogSampleRate < 0 || statslogSampleRate > 0x10000) {
                statslogSampleRate = -1;
            }
            if (statslogSampleRate != -1 && statslogSampleRate != mStatslogSampleRate) {
                mStatslogSampleRate = statslogSampleRate;
                ZYGOTE_PROCESS.setHiddenApiAccessStatslogSampleRate(mStatslogSampleRate);
            }
        }

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
                    Settings.Global.getUriFor(Settings.Global.HIDDEN_API_POLICY),
                    false,
                    this);
            DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_APP_COMPAT,
                    mContext.getMainExecutor(), this);
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
                if (!ZYGOTE_PROCESS.setApiDenylistExemptions(mExemptions)) {
                  Slog.e(TAG, "Failed to set API blacklist exemptions!");
                  // leave mExemptionsStr as is, so we don't try to send the same list again.
                  mExemptions = Collections.emptyList();
                }
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

    AppOpsManager getAppOpsManager() {
        if (mAppOpsManager == null) {
            mAppOpsManager = mContext.getSystemService(AppOpsManager.class);
        }
        return mAppOpsManager;
    }

    /** Provides the basic functionality for unit tests. */
    @VisibleForTesting
    ActivityManagerService(Injector injector, @NonNull ServiceThread handlerThread) {
        this(injector, handlerThread, null);
    }

    /** Provides the basic functionality for unit tests. */
    @VisibleForTesting
    ActivityManagerService(Injector injector, @NonNull ServiceThread handlerThread,
            @Nullable UserController userController) {
        mInjector = injector;
        mContext = mInjector.getContext();
        mUiContext = null;
        mAppErrors = injector.getAppErrors();
        mPackageWatchdog = null;
        mAppOpsService = mInjector.getAppOpsService(null /* recentAccessesFile */,
            null /* storageFile */, null /* handler */);
        mBatteryStatsService = mInjector.getBatteryStatsService();
        mHandler = new MainHandler(handlerThread.getLooper());
        mHandlerThread = handlerThread;
        mConstants = new ActivityManagerConstants(mContext, this, mHandler);
        final ActiveUids activeUids = new ActiveUids(this, false /* postChangesToAtm */);
        mPlatformCompat = null;
        mProcessList = injector.getProcessList(this);
        mProcessList.init(this, activeUids, mPlatformCompat);
        mAppProfiler = new AppProfiler(this, BackgroundThread.getHandler().getLooper(), null);
        mPhantomProcessList = new PhantomProcessList(this);
        mOomAdjuster = mConstants.ENABLE_NEW_OOMADJ
                ? new OomAdjusterModernImpl(this, mProcessList, activeUids, handlerThread)
                : new OomAdjuster(this, mProcessList, activeUids, handlerThread);

        mIntentFirewall = injector.getIntentFirewall();
        mProcessStats = new ProcessStatsService(this, mContext.getCacheDir());
        mCpHelper = new ContentProviderHelper(this, false);
        mServices = mInjector.getActiveServices(this);
        mSystemThread = null;
        mUiHandler = injector.getUiHandler(null /* service */);
        mUidObserverController = new UidObserverController(mUiHandler);
        mUserController = userController == null ? new UserController(this) : userController;
        mInjector.mUserController = mUserController;
        mPendingIntentController =
                new PendingIntentController(handlerThread.getLooper(), mUserController, mConstants);
        mAppRestrictionController = new AppRestrictionController(mContext, this);
        mProcStartHandlerThread = null;
        mProcStartHandler = null;
        mHiddenApiBlacklist = null;
        mFactoryTest = FACTORY_TEST_OFF;
        mUgmInternal = LocalServices.getService(UriGrantsManagerInternal.class);
        mInternal = new LocalService();
        mPendingStartActivityUids = new PendingStartActivityUids();
        mUseFifoUiScheduling = false;
        mBroadcastQueue = injector.getBroadcastQueue(this);
        mComponentAliasResolver = new ComponentAliasResolver(this);
    }

    // Note: This method is invoked on the main thread but may need to attach various
    // handlers to other threads.  So take care to be explicit about the looper.
    public ActivityManagerService(Context systemContext, ActivityTaskManagerService atm) {
        LockGuard.installLock(this, LockGuard.INDEX_ACTIVITY);
        mInjector = new Injector(systemContext);
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
        mProcStartHandler = new ProcStartHandler(this, mProcStartHandlerThread.getLooper());

        mConstants = new ActivityManagerConstants(mContext, this, mHandler);
        final ActiveUids activeUids = new ActiveUids(this, true /* postChangesToAtm */);
        mPlatformCompat = (PlatformCompat) ServiceManager.getService(
                Context.PLATFORM_COMPAT_SERVICE);
        mProcessList = mInjector.getProcessList(this);
        mProcessList.init(this, activeUids, mPlatformCompat);
        mAppProfiler = new AppProfiler(this, BackgroundThread.getHandler().getLooper(),
                new LowMemDetector(this));
        mPhantomProcessList = new PhantomProcessList(this);
        mOomAdjuster = mConstants.ENABLE_NEW_OOMADJ
                ? new OomAdjusterModernImpl(this, mProcessList, activeUids)
                : new OomAdjuster(this, mProcessList, activeUids);

        mBroadcastQueue = mInjector.getBroadcastQueue(this);

        mServices = new ActiveServices(this);
        mCpHelper = new ContentProviderHelper(this, true);
        mPackageWatchdog = PackageWatchdog.getInstance(mUiContext);
        mAppErrors = new AppErrors(mUiContext, this, mPackageWatchdog);
        mUidObserverController = new UidObserverController(mUiHandler);

        final File systemDir = SystemServiceManager.ensureSystemDir();

        // TODO: Move creation of battery stats service outside of activity manager service.
        mBatteryStatsService = BatteryStatsService.create(systemContext, systemDir,
                BackgroundThread.getHandler(), this);
        mOnBattery = DEBUG_POWER ? true
                : mBatteryStatsService.getActiveStatistics().getIsOnBattery();

        mProcessStats = new ProcessStatsService(this, new File(systemDir, "procstats"));

        mAppOpsService = mInjector.getAppOpsService(new File(systemDir, "appops_accesses.xml"),
                new File(systemDir, "appops.xml"), mHandler);

        mUgmInternal = LocalServices.getService(UriGrantsManagerInternal.class);

        mUserController = new UserController(this);
        mInjector.mUserController = mUserController;

        mPendingIntentController = new PendingIntentController(
                mHandlerThread.getLooper(), mUserController, mConstants);

        mAppRestrictionController = new AppRestrictionController(mContext, this);

        mUseFifoUiScheduling = SystemProperties.getInt("sys.use_fifo_ui", 0) != 0;

        mTrackingAssociations = "1".equals(SystemProperties.get("debug.track-associations"));
        mIntentFirewall = new IntentFirewall(new IntentFirewallInterface(), mHandler);

        mActivityTaskManager = atm;
        mActivityTaskManager.initialize(mIntentFirewall, mPendingIntentController,
                DisplayThread.get().getLooper());
        mAtmInternal = LocalServices.getService(ActivityTaskManagerInternal.class);

        mHiddenApiBlacklist = new HiddenApiSettings(mHandler, mContext);

        Watchdog.getInstance().addMonitor(this);
        Watchdog.getInstance().addThread(mHandler);

        // bind background threads to little cores
        // this is expected to fail inside of framework tests because apps can't touch cpusets directly
        // make sure we've already adjusted system_server's internal view of itself first
        updateOomAdjLocked(OOM_ADJ_REASON_SYSTEM_INIT);
        try {
            Process.setThreadGroupAndCpuset(BackgroundThread.get().getThreadId(),
                    Process.THREAD_GROUP_SYSTEM);
            Process.setThreadGroupAndCpuset(
                    mOomAdjuster.mCachedAppOptimizer.mCachedAppOptimizerThread.getThreadId(),
                    Process.THREAD_GROUP_SYSTEM);
        } catch (Exception e) {
            Slog.w(TAG, "Setting background thread cpuset failed");
        }

        mInternal = new LocalService();
        mPendingStartActivityUids = new PendingStartActivityUids();
        mTraceErrorLogger = new TraceErrorLogger();
        mComponentAliasResolver = new ComponentAliasResolver(this);
    }

    void setBroadcastQueueForTest(BroadcastQueue broadcastQueue) {
        mBroadcastQueue = broadcastQueue;
    }

    BroadcastQueue getBroadcastQueue() {
        return mBroadcastQueue;
    }

    public void setSystemServiceManager(SystemServiceManager mgr) {
        mSystemServiceManager = mgr;
    }

    public void setInstaller(Installer installer) {
        mInstaller = installer;
    }

    private void start() {
        mBatteryStatsService.publish();
        mAppOpsService.publish();
        mProcessStats.publish();
        Slog.d("AppOps", "AppOpsService published");
        LocalServices.addService(ActivityManagerInternal.class, mInternal);
        LocalManagerRegistry.addManager(ActivityManagerLocal.class,
                (ActivityManagerLocal) mInternal);
        mActivityTaskManager.onActivityManagerInternalAdded();
        mPendingIntentController.onActivityManagerInternalAdded();
        mAppProfiler.onActivityManagerInternalAdded();
        CriticalEventLog.init();
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

    private String getWearRemoteIntentAction() {
        return mContext.getResources().getString(
                    com.android.internal.R.string.config_wearRemoteIntentAction);
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
            PackageManagerInternal pm = getPackageManagerInternal();
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
            synchronized (mProcLock) {
                final ArrayMap<String, SparseArray<ProcessRecord>> pmap =
                        mProcessList.getProcessNamesLOSP().getMap();
                final int numOfNames = pmap.size();
                for (int ip = 0; ip < numOfNames; ip++) {
                    SparseArray<ProcessRecord> apps = pmap.valueAt(ip);
                    final int numOfApps = apps.size();
                    for (int ia = 0; ia < numOfApps; ia++) {
                        ProcessRecord app = apps.valueAt(ia);
                        final IApplicationThread thread = app.getThread();
                        if (thread != null) {
                            procs.add(thread.asBinder());
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
        mAppProfiler.updateCpuStats();
    }

    void updateCpuStatsNow() {
        mAppProfiler.updateCpuStatsNow();
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
        synchronized (mProcLock) {
            mOnBattery = DEBUG_POWER ? true : onBattery;
        }
    }

    @Override
    public void batteryStatsReset() {
        // Empty for now.
    }

    @Override
    public void batterySendBroadcast(Intent intent) {
        synchronized (this) {
            broadcastIntentLocked(null, null, null, intent, null, null, 0, null, null, null, null,
                    null, OP_NONE, null, false, false, -1, SYSTEM_UID, Binder.getCallingUid(),
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
                addServiceToMap(mIsolatedAppBindArgs, "permissionmgr");
            }
            return mIsolatedAppBindArgs;
        }

        if (mAppBindArgs == null) {
            mAppBindArgs = new ArrayMap<>();

            // Add common services.
            // IMPORTANT: Before adding services here, make sure ephemeral apps can access them too.
            // Enable the check in ApplicationThread.bindApplication() to make sure.
            addServiceToMap(mAppBindArgs, "package");
            addServiceToMap(mAppBindArgs, "permissionmgr");
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
            addServiceToMap(mAppBindArgs, Context.PLATFORM_COMPAT_SERVICE);
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
    public void setFocusedRootTask(int taskId) {
        mActivityTaskManager.setFocusedRootTask(taskId);
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

    @GuardedBy("this")
    final void updateLruProcessLocked(ProcessRecord app, boolean activityChange,
            ProcessRecord client) {
        mProcessList.updateLruProcessLocked(app, activityChange, client);
    }

    @GuardedBy("this")
    final void removeLruProcessLocked(ProcessRecord app) {
        mProcessList.removeLruProcessLocked(app);
    }

    @GuardedBy("this")
    final ProcessRecord getProcessRecordLocked(String processName, int uid) {
        return mProcessList.getProcessRecordLocked(processName, uid);
    }

    @GuardedBy(anyOf = {"this", "mProcLock"})
    final ProcessMap<ProcessRecord> getProcessNamesLOSP() {
        return mProcessList.getProcessNamesLOSP();
    }

    void notifyPackageUse(String packageName, int reason) {
        getPackageManagerInternal().notifyPackageUse(packageName, reason);
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
                    false /* knownToBeDead */, 0 /* intentFlags */,
                    sNullHostingRecord  /* hostingRecord */, ZYGOTE_POLICY_FLAG_EMPTY,
                    true /* allowWhileBooting */, true /* isolated */,
                    uid, false /* isSdkSandbox */, 0 /* sdkSandboxUid */,
                    null /* sdkSandboxClientAppPackage */,
                    abiOverride, entryPoint, entryPointArgs, crashHandler);
            return proc != null;
        }
    }

    @GuardedBy("this")
    final ProcessRecord startSdkSandboxProcessLocked(String processName,
            ApplicationInfo info, boolean knownToBeDead, int intentFlags,
            HostingRecord hostingRecord, int zygotePolicyFlags, int sdkSandboxUid,
            String sdkSandboxClientAppPackage) {
        return mProcessList.startProcessLocked(processName, info, knownToBeDead, intentFlags,
                hostingRecord, zygotePolicyFlags, false /* allowWhileBooting */,
                false /* isolated */, 0 /* isolatedUid */,
                true /* isSdkSandbox */, sdkSandboxUid, sdkSandboxClientAppPackage,
                null /* ABI override */, null /* entryPoint */,
                null /* entryPointArgs */, null /* crashHandler */);
    }

    @GuardedBy("this")
    final ProcessRecord startProcessLocked(String processName,
            ApplicationInfo info, boolean knownToBeDead, int intentFlags,
            HostingRecord hostingRecord, int zygotePolicyFlags, boolean allowWhileBooting,
            boolean isolated) {
        return mProcessList.startProcessLocked(processName, info, knownToBeDead, intentFlags,
                hostingRecord, zygotePolicyFlags, allowWhileBooting, isolated, 0 /* isolatedUid */,
                false /* isSdkSandbox */, 0 /* sdkSandboxClientAppUid */,
                null /* sdkSandboxClientAppPackage */,
                null /* ABI override */, null /* entryPoint */,
                null /* entryPointArgs */, null /* crashHandler */);
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
        mBatteryStatsService.updateBatteryStatsOnActivityUsage(activity.getPackageName(),
                activity.getShortClassName(), uid, userId, resumed);
    }

    /**
     * Update UsageStas on the activity's usage.
     * @param activity
     * @param userId
     * @param event
     * @param appToken ActivityRecord's appToken.
     * @param taskRoot Task's root
     */
    public void updateActivityUsageStats(ComponentName activity, int userId, int event,
            IBinder appToken, ComponentName taskRoot, ActivityId activityId) {
        if (DEBUG_SWITCH) {
            Slog.d(TAG_SWITCH, "updateActivityUsageStats: comp="
                    + activity + " hash=" + appToken.hashCode() + " event=" + event);
        }
        if (mUsageStatsService != null) {
            mUsageStatsService.reportEvent(activity, userId, event, appToken.hashCode(), taskRoot);
            if (event == Event.ACTIVITY_RESUMED) {
                // Report component usage as an activity is an app component
                mUsageStatsService.reportEvent(
                        activity.getPackageName(), userId, Event.APP_COMPONENT_USED);
            }
        }
        ContentCaptureManagerInternal contentCaptureService = mContentCaptureService;
        if (contentCaptureService != null && (event == Event.ACTIVITY_PAUSED
                || event == Event.ACTIVITY_RESUMED || event == Event.ACTIVITY_STOPPED
                || event == Event.ACTIVITY_DESTROYED)) {
            contentCaptureService.notifyActivityEvent(userId, activity, event, activityId);
        }
        // Currently we have move most of logic to the client side. When the activity lifecycle
        // event changed, the client side will notify the VoiceInteractionManagerService. But
        // when the application process died, the VoiceInteractionManagerService will miss the
        // activity lifecycle event changed, so we still need ACTIVITY_DESTROYED event here to
        // know if the activity has been destroyed.
        if (mVoiceInteractionManagerProvider != null && event == Event.ACTIVITY_DESTROYED) {
            mVoiceInteractionManagerProvider.notifyActivityDestroyed(appToken);
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
        if (mUsageStatsService != null) {
            mUsageStatsService.reportEvent(packageName, userId, event);
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
        if (mUsageStatsService != null) {
            mUsageStatsService.reportEvent(service, userId,
                    started ? UsageEvents.Event.FOREGROUND_SERVICE_START
                            : UsageEvents.Event.FOREGROUND_SERVICE_STOP, 0, null);
        }
    }

    CompatibilityInfo compatibilityInfoForPackage(ApplicationInfo ai) {
        return mAtmInternal.compatibilityInfoForPackage(ai);
    }

    /**
     * Enforces that the uid that calls a method is not an
     * {@link UserHandle#isIsolated(int) isolated} uid.
     *
     * @param caller the name of the method being called.
     * @throws SecurityException if the calling uid is an isolated uid.
     */
    /* package */ void enforceNotIsolatedCaller(String caller) {
        if (UserHandle.isIsolated(Binder.getCallingUid())) {
            throw new SecurityException("Isolated process not allowed to call " + caller);
        }
    }

    /**
     * Enforces that the uid that calls a method is not an
     * {@link UserHandle#isIsolated(int) isolated} uid or an
     * {@link Process#isSdkSandboxUid(int) SDK sandbox} uid.
     *
     * @param caller the name of the method being called.
     * @throws SecurityException if the calling uid is an isolated uid or SDK sandbox uid.
     */
    void enforceNotIsolatedOrSdkSandboxCaller(String caller) {
        enforceNotIsolatedCaller(caller);

        if (Process.isSdkSandboxUid(Binder.getCallingUid())) {
            throw new SecurityException("SDK sandbox process not allowed to call " + caller);
        }
    }

    /**
     * If the caller is an {@link Process#isSdkSandboxUid(int) SDK sandbox uid}, enforces that the
     * SDK sandbox has permission to start or bind to a given service.
     *
     * @param intent the intent used to start or bind to the service.
     * @throws IllegalStateException if {@link SdkSandboxManagerLocal} cannot be resolved.
     * @throws SecurityException if the SDK sandbox is not allowed to bind to this service.
     */
    private void enforceAllowedToStartOrBindServiceIfSdkSandbox(Intent intent) {
        if (Process.isSdkSandboxUid(Binder.getCallingUid())) {
            SdkSandboxManagerLocal sdkSandboxManagerLocal =
                    LocalManagerRegistry.getManager(SdkSandboxManagerLocal.class);
            if (sdkSandboxManagerLocal != null) {
                sdkSandboxManagerLocal.enforceAllowedToStartOrBindService(intent);
            } else {
                throw new IllegalStateException("SdkSandboxManagerLocal not found when checking"
                        + " whether SDK sandbox uid may start or bind to a service.");
            }
        }
    }

    /**
     * Enforces that the uid of the caller matches the uid of the package.
     *
     * @param packageName the name of the package to match uid against.
     * @param callingUid the uid of the caller.
     * @throws SecurityException if the calling uid doesn't match uid of the package.
     */
    private void enforceCallingPackage(String packageName, int callingUid) {
        final int userId = UserHandle.getUserId(callingUid);
        final int packageUid = getPackageManagerInternal().getPackageUid(packageName,
                /*flags=*/ 0, userId);
        if (packageUid != callingUid) {
            throw new SecurityException(packageName + " does not belong to uid " + callingUid);
        }
    }

    @Override
    public void setPackageScreenCompatMode(String packageName, int mode) {
        mActivityTaskManager.setPackageScreenCompatMode(packageName, mode);
    }

    private boolean hasUsageStatsPermission(String callingPackage, int callingUid, int callingPid) {
        final int mode = mAppOpsService.noteOperation(AppOpsManager.OP_GET_USAGE_STATS,
                callingUid, callingPackage, null, false, "", false).getOpMode();
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return checkPermission(Manifest.permission.PACKAGE_USAGE_STATS, callingPid, callingUid)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private boolean hasUsageStatsPermission(String callingPackage) {
        return hasUsageStatsPermission(callingPackage,
                Binder.getCallingUid(), Binder.getCallingPid());
    }

    private void enforceUsageStatsPermission(String callingPackage,
            int callingUid, int callingPid, String operation) {
        if (!hasUsageStatsPermission(callingPackage, callingUid, callingPid)) {
            final String errorMsg = "Permission denial for <" + operation + "> from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " which requires PACKAGE_USAGE_STATS permission";
            throw new SecurityException(errorMsg);
        }
    }

    @Override
    public int getPackageProcessState(String packageName, String callingPackage) {
        if (!hasUsageStatsPermission(callingPackage)) {
            enforceCallingPermission(android.Manifest.permission.PACKAGE_USAGE_STATS,
                    "getPackageProcessState");
        }

        final int[] procState = {PROCESS_STATE_NONEXISTENT};
        synchronized (mProcLock) {
            mProcessList.forEachLruProcessesLOSP(false, proc -> {
                if (procState[0] > proc.mState.getSetProcState()) {
                    if (proc.getPkgList().containsKey(packageName) || (proc.getPkgDeps() != null
                                && proc.getPkgDeps().contains(packageName))) {
                        procState[0] = proc.mState.getSetProcState();
                    }
                }
            });
        }
        return procState[0];
    }

    @Override
    public boolean setProcessMemoryTrimLevel(String process, int userId, int level)
            throws RemoteException {
        if (!isCallerShell()) {
            throw new SecurityException("Only shell can call it");
        }
        synchronized (this) {
            final ProcessRecord app = findProcessLOSP(process, userId, "setProcessMemoryTrimLevel");
            if (app == null) {
                throw new IllegalArgumentException("Unknown process: " + process);
            }
            final IApplicationThread thread = app.getThread();
            if (thread == null) {
                throw new IllegalArgumentException("Process has no app thread");
            }
            if (app.mProfile.getTrimMemoryLevel() >= level) {
                throw new IllegalArgumentException(
                        "Unable to set a higher trim level than current level");
            }
            if (!(level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
                    app.mState.getCurProcState() > PROCESS_STATE_IMPORTANT_FOREGROUND)) {
                throw new IllegalArgumentException("Unable to set a background trim level "
                    + "on a foreground process");
            }
            thread.scheduleTrimMemory(level);
            synchronized (mProcLock) {
                app.mProfile.setTrimMemoryLevel(level);
            }
            return true;
        }
    }

    void dispatchOomAdjObserver(String msg) {
        OomAdjObserver observer;
        synchronized (mOomAdjObserverLock) {
            observer = mCurOomAdjObserver;
        }

        if (observer != null) {
            observer.onOomAdjMessage(msg);
        }
    }

    void setOomAdjObserver(int uid, OomAdjObserver observer) {
        synchronized (mOomAdjObserverLock) {
            mCurOomAdjUid = uid;
            mCurOomAdjObserver = observer;
        }
    }

    void clearOomAdjObserver() {
        synchronized (mOomAdjObserverLock) {
            mCurOomAdjUid = -1;
            mCurOomAdjObserver = null;
        }
    }

    void reportUidInfoMessageLocked(String tag, String msg, int uid) {
        Slog.i(TAG, msg);
        synchronized (mOomAdjObserverLock) {
            if (mCurOomAdjObserver != null && uid == mCurOomAdjUid) {
                mUiHandler.obtainMessage(DISPATCH_OOM_ADJ_OBSERVER_MSG, msg).sendToTarget();
            }
        }
    }

    /**
     * @deprecated use {@link #startActivityWithFeature} instead
     */
    @Deprecated
    @Override
    public int startActivity(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
            int startFlags, ProfilerInfo profilerInfo, Bundle bOptions) {
        return mActivityTaskManager.startActivity(caller, callingPackage, null, intent,
                resolvedType, resultTo, resultWho, requestCode, startFlags, profilerInfo, bOptions);
    }

    @Override
    public int startActivityWithFeature(IApplicationThread caller, String callingPackage,
            String callingFeatureId, Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int startFlags, ProfilerInfo profilerInfo,
            Bundle bOptions) {
        return mActivityTaskManager.startActivity(caller, callingPackage, callingFeatureId, intent,
                resolvedType, resultTo, resultWho, requestCode, startFlags, profilerInfo, bOptions);
    }

    /**
     * @deprecated use {@link #startActivityAsUserWithFeature} instead
     */
    @Deprecated
    @Override
    public final int startActivityAsUser(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode,
            int startFlags, ProfilerInfo profilerInfo, Bundle bOptions, int userId) {
        return startActivityAsUserWithFeature(caller, callingPackage, null, intent, resolvedType,
                resultTo, resultWho, requestCode, startFlags, profilerInfo, bOptions, userId);
    }

    @Override
    public final int startActivityAsUserWithFeature(IApplicationThread caller,
            String callingPackage, String callingFeatureId, Intent intent, String resolvedType,
            IBinder resultTo, String resultWho, int requestCode, int startFlags,
            ProfilerInfo profilerInfo, Bundle bOptions, int userId) {
        return mActivityTaskManager.startActivityAsUser(caller, callingPackage,
                    callingFeatureId, intent, resolvedType, resultTo, resultWho, requestCode,
                    startFlags, profilerInfo, bOptions, userId);
    }

    WaitResult startActivityAndWait(IApplicationThread caller, String callingPackage,
            @Nullable String callingFeatureId, Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int startFlags, ProfilerInfo profilerInfo,
            Bundle bOptions, int userId) {
            return mActivityTaskManager.startActivityAndWait(caller, callingPackage,
                    callingFeatureId, intent, resolvedType, resultTo, resultWho, requestCode,
                    startFlags, profilerInfo, bOptions, userId);
    }

    @Override
    public final int startActivityFromRecents(int taskId, Bundle bOptions) {
        return mActivityTaskManager.startActivityFromRecents(taskId, bOptions);
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
        return ActivityClient.getInstance().finishActivity(token, resultCode, resultData,
                finishTask);
    }

    @Override
    public void setRequestedOrientation(IBinder token, int requestedOrientation) {
        ActivityClient.getInstance().setRequestedOrientation(token, requestedOrientation);
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
    public void crashApplicationWithType(int uid, int initialPid, String packageName, int userId,
            String message, boolean force, int exceptionTypeId) {
        crashApplicationWithTypeWithExtras(uid, initialPid, packageName, userId, message,
                force, exceptionTypeId, null);
    }

    @Override
    public void crashApplicationWithTypeWithExtras(int uid, int initialPid, String packageName,
            int userId, String message, boolean force, int exceptionTypeId,
            @Nullable Bundle extras) {
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
            mAppErrors.scheduleAppCrashLocked(uid, initialPid, packageName, userId,
                    message, force, exceptionTypeId, extras);
        }
    }

    /**
     * Main function for removing an existing process from the activity manager
     * as a result of that process going away.  Clears out all connections
     * to the process.
     */
    @GuardedBy("this")
    final void handleAppDiedLocked(ProcessRecord app, int pid,
            boolean restarting, boolean allowRestart, boolean fromBinderDied) {
        boolean kept = cleanUpApplicationRecordLocked(app, pid, restarting, allowRestart, -1,
                false /*replacingPid*/, fromBinderDied);
        if (!kept && !restarting) {
            removeLruProcessLocked(app);
            if (pid > 0) {
                ProcessList.remove(pid);
            }
        }

        mAppProfiler.onAppDiedLocked(app);

        mAtmInternal.handleAppDied(app.getWindowProcessController(), restarting, () -> {
            Slog.w(TAG, "Crash of app " + app.processName
                    + " running instrumentation " + app.getActiveInstrumentation().mClass);
            Bundle info = new Bundle();
            info.putString("shortMsg", "Process crashed.");
            finishInstrumentationLocked(app, Activity.RESULT_CANCELED, info);
        });
    }

    @GuardedBy(anyOf = {"this", "mProcLock"})
    ProcessRecord getRecordForAppLOSP(IApplicationThread thread) {
        if (thread == null) {
            return null;
        }
        return getRecordForAppLOSP(thread.asBinder());
    }

    @GuardedBy(anyOf = {"this", "mProcLock"})
    ProcessRecord getRecordForAppLOSP(IBinder threadBinder) {
        if (threadBinder == null) {
            return null;
        }

        ProcessRecord record = mProcessList.getLRURecordForAppLOSP(threadBinder);
        if (record != null) return record;

        // Validation: if it isn't in the LRU list, it shouldn't exist, but let's double-check that.
        final ArrayMap<String, SparseArray<ProcessRecord>> pmap =
                mProcessList.getProcessNamesLOSP().getMap();
        for (int i = pmap.size()-1; i >= 0; i--) {
            final SparseArray<ProcessRecord> procs = pmap.valueAt(i);
            for (int j = procs.size()-1; j >= 0; j--) {
                final ProcessRecord proc = procs.valueAt(j);
                final IApplicationThread procThread = proc.getThread();
                if (procThread != null && procThread.asBinder() == threadBinder) {
                    if (!proc.isPendingFinishAttach()) {
                        Slog.wtf(TAG, "getRecordForApp: exists in name list but not in LRU list: "
                                + proc);
                    }
                    return proc;
                }
            }
        }

        return null;
    }

    @GuardedBy("this")
    final void appDiedLocked(ProcessRecord app, String reason) {
        appDiedLocked(app, app.getPid(), app.getThread(), false, reason);
    }

    @GuardedBy("this")
    final void appDiedLocked(ProcessRecord app, int pid, IApplicationThread thread,
            boolean fromBinderDied, String reason) {
        // First check if this ProcessRecord is actually active for the pid.
        final ProcessRecord curProc;
        synchronized (mPidsSelfLocked) {
            curProc = mPidsSelfLocked.get(pid);
        }
        if (curProc != app) {
            if (!fromBinderDied || !mProcessList.handleDyingAppDeathLocked(app, pid)) {
                Slog.w(TAG, "Spurious death for " + app + ", curProc for " + pid + ": " + curProc);
            }
            return;
        }

        mBatteryStatsService.noteProcessDied(app.info.uid, pid);

        if (!app.isKilled()) {
            if (!fromBinderDied) {
                killProcessQuiet(pid);
                mProcessList.noteAppKill(app, ApplicationExitInfo.REASON_OTHER,
                        ApplicationExitInfo.SUBREASON_UNKNOWN, reason);
            }
            app.killProcessGroupIfNecessaryLocked(true);
            synchronized (mProcLock) {
                app.setKilled(true);
            }
        }

        // Clean up already done if the process has been re-started.
        IApplicationThread appThread;
        final int setAdj = app.mState.getSetAdj();
        final int setProcState = app.mState.getSetProcState();
        if (app.getPid() == pid && (appThread = app.getThread()) != null
                && appThread.asBinder() == thread.asBinder()) {
            boolean doLowMem = app.getActiveInstrumentation() == null;
            boolean doOomAdj = doLowMem;
            if (!app.isKilledByAm()) {
                reportUidInfoMessageLocked(TAG,
                        "Process " + app.processName + " (pid " + pid + ") has died: "
                        + ProcessList.makeOomAdjString(setAdj, true) + " "
                        + ProcessList.makeProcStateString(setProcState), app.info.uid);
                mAppProfiler.setAllowLowerMemLevelLocked(true);
            } else {
                // Note that we always want to do oom adj to update our state with the
                // new number of procs.
                mAppProfiler.setAllowLowerMemLevelLocked(false);
                doLowMem = false;
            }
            if (doOomAdj) {
                if (Flags.migrateFullOomadjUpdates()) {
                    app.forEachConnectionHost((host) -> enqueueOomAdjTargetLocked(host));
                }
            }

            EventLogTags.writeAmProcDied(app.userId, pid, app.processName, setAdj, setProcState);
            if (DEBUG_CLEANUP) Slog.v(TAG_CLEANUP,
                "Dying app: " + app + ", pid: " + pid + ", thread: " + thread.asBinder());
            handleAppDiedLocked(app, pid, false, true, fromBinderDied);

            if (doOomAdj) {
                if (Flags.migrateFullOomadjUpdates()) {
                    updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_PROCESS_END);
                } else {
                    updateOomAdjLocked(OOM_ADJ_REASON_PROCESS_END);
                }
            }
            if (doLowMem) {
                mAppProfiler.doLowMemReportIfNeededLocked(app);
            }
        } else if (app.getPid() != pid) {
            // A new process has already been started.
            reportUidInfoMessageLocked(TAG,
                    "Process " + app.processName + " (pid " + pid
                            + ") has died and restarted (pid " + app.getPid() + ").", app.info.uid);

            EventLogTags.writeAmProcDied(app.userId, app.getPid(), app.processName,
                    setAdj, setProcState);
        } else if (DEBUG_PROCESSES) {
            Slog.d(TAG_PROCESSES, "Received spurious death notification for thread "
                    + thread.asBinder());
        }

        // On the device which doesn't have Cgroup, log LmkStateChanged which is used as a signal
        // for pulling memory stats of other running processes when this process died.
        if (!hasMemcg()) {
            FrameworkStatsLog.write(FrameworkStatsLog.APP_DIED, SystemClock.elapsedRealtime());
        }
    }

    @Override
    public boolean clearApplicationUserData(final String packageName, boolean keepState,
            final IPackageDataObserver observer, int userId) {
        return clearApplicationUserData(packageName, keepState, /*isRestore=*/ false, observer,
                userId);
    }

    private boolean clearApplicationUserData(final String packageName, boolean keepState,
            boolean isRestore, final IPackageDataObserver observer, int userId) {
        enforceNotIsolatedCaller("clearApplicationUserData");
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        EventLog.writeEvent(EventLogTags.AM_CLEAR_APP_DATA_CALLER, pid, uid, packageName);
        final int resolvedUserId = mUserController.handleIncomingUser(pid, uid, userId, false,
                ALLOW_FULL_ONLY, "clearApplicationUserData", null);

        final ApplicationInfo appInfo;
        final boolean isInstantApp;

        final long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            boolean permitted = true;
            // Instant packages are not protected
            if (getPackageManagerInternal().isPackageDataProtected(
                    resolvedUserId, packageName)) {
                if (ActivityManager.checkUidPermission(android.Manifest.permission.MANAGE_USERS,
                        uid) == PERMISSION_GRANTED) {
                    // The caller has the MANAGE_USERS permission, tell them what's going on.
                    throw new SecurityException(
                            "Cannot clear data for a protected package: " + packageName);
                } else {
                    permitted = false; // fall through and throw the SecurityException below.
                }
            }

            ApplicationInfo applicationInfo = null;
            if (permitted) {
                try {
                    applicationInfo = pm.getApplicationInfo(packageName,
                            MATCH_UNINSTALLED_PACKAGES, resolvedUserId);
                } catch (RemoteException e) {
                    /* ignore */
                }
                permitted = (applicationInfo != null && applicationInfo.uid == uid) // own uid data
                        || (checkComponentPermission(permission.CLEAR_APP_USER_DATA,
                                pid, uid, -1, true) == PackageManager.PERMISSION_GRANTED);
            }

            if (!permitted) {
                throw new SecurityException("PID " + pid + " does not have permission "
                        + android.Manifest.permission.CLEAR_APP_USER_DATA + " to clear data"
                        + " of package " + packageName);
            }
            appInfo = applicationInfo;

            final boolean hasInstantMetadata = getPackageManagerInternal()
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

            synchronized (this) {
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

                    if (succeeded) {

                        mPackageManagerInt.sendPackageDataClearedBroadcast(packageName,
                                ((appInfo != null) ? appInfo.uid : INVALID_UID), resolvedUserId,
                                isRestore, isInstantApp);
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
                    // Clearing data is a user-initiated action.
                    js.cancelJobsForUid(appInfo.uid, /* includeProxiedJobs */ true,
                            JobParameters.STOP_REASON_USER,
                            JobParameters.INTERNAL_STOP_REASON_DATA_CLEARED, "clear data");

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
        final boolean hasKillAllPermission = checkCallingPermission(
                android.Manifest.permission.KILL_ALL_BACKGROUND_PROCESSES) == PERMISSION_GRANTED;
        final int callingUid = Binder.getCallingUid();
        final int callingAppId = UserHandle.getAppId(callingUid);

        userId = mUserController.handleIncomingUser(Binder.getCallingPid(), callingUid,
                userId, true, ALLOW_FULL_ONLY, "killBackgroundProcesses", null);
        final int[] userIds = mUserController.expandUserId(userId);

        final long callingId = Binder.clearCallingIdentity();
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
                if (appId == -1 || (!hasKillAllPermission && appId != callingAppId)) {
                    Slog.w(TAG, "Invalid packageName: " + packageName);
                    return;
                }
                synchronized (this) {
                    synchronized (mProcLock) {
                        mProcessList.killPackageProcessesLSP(packageName, appId, targetUserId,
                                ProcessList.SERVICE_ADJ, ApplicationExitInfo.REASON_USER_REQUESTED,
                                ApplicationExitInfo.SUBREASON_KILL_BACKGROUND, "kill background");
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void killAllBackgroundProcesses() {
        if (checkCallingPermission(android.Manifest.permission.KILL_ALL_BACKGROUND_PROCESSES)
                != PackageManager.PERMISSION_GRANTED) {
            final String msg = "Permission Denial: killAllBackgroundProcesses() from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.KILL_ALL_BACKGROUND_PROCESSES;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        final long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                // Allow memory level to go down (the flag needs to be set before updating oom adj)
                // because this method is also used to simulate low memory.
                mAppProfiler.setAllowLowerMemLevelLocked(true);
                synchronized (mProcLock) {
                    mProcessList.killPackageProcessesLSP(null /* packageName */, -1 /* appId */,
                            UserHandle.USER_ALL, ProcessList.CACHED_APP_MIN_ADJ,
                            ApplicationExitInfo.REASON_USER_REQUESTED,
                            ApplicationExitInfo.SUBREASON_KILL_BACKGROUND,
                            "kill all background");
                }

                mAppProfiler.doLowMemReportIfNeededLocked(null);
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
        if (checkCallingPermission(android.Manifest.permission.KILL_ALL_BACKGROUND_PROCESSES)
                != PackageManager.PERMISSION_GRANTED) {
            final String msg = "Permission Denial: killAllBackgroundProcessesExcept() from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.KILL_ALL_BACKGROUND_PROCESSES;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        final long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                synchronized (mProcLock) {
                    mProcessList.killAllBackgroundProcessesExceptLSP(minTargetSdk, maxProcState);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void stopAppForUser(final String packageName, int userId) {
        if (checkCallingPermission(MANAGE_ACTIVITY_TASKS)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: stopAppForUser() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + MANAGE_ACTIVITY_TASKS;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        final int callingPid = Binder.getCallingPid();
        userId = mUserController.handleIncomingUser(callingPid, Binder.getCallingUid(),
                userId, true, ALLOW_FULL_ONLY, "stopAppForUser", null);
        final long callingId = Binder.clearCallingIdentity();
        try {
            stopAppForUserInternal(packageName, userId);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public boolean registerForegroundServiceObserver(IForegroundServiceObserver callback) {
        final int callingUid = Binder.getCallingUid();
        final int permActivityTasks = checkCallingPermission(MANAGE_ACTIVITY_TASKS);
        final int permAcrossUsersFull = checkCallingPermission(INTERACT_ACROSS_USERS_FULL);
        if (permActivityTasks != PackageManager.PERMISSION_GRANTED
                || permAcrossUsersFull != PERMISSION_GRANTED) {
            String msg = "Permission Denial: registerForegroundServiceObserver() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + callingUid
                    + " requires " + MANAGE_ACTIVITY_TASKS
                    + " and " + INTERACT_ACROSS_USERS_FULL;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        synchronized (this) {
            return mServices.registerForegroundServiceObserverLocked(callingUid, callback);
        }
    }

    @Override
    public void forceStopPackage(final String packageName, int userId) {
        forceStopPackage(packageName, userId, /*flags=*/ 0, null);
    }

    @Override
    public void forceStopPackageEvenWhenStopping(final String packageName, int userId) {
        forceStopPackage(packageName, userId, ActivityManager.FLAG_OR_STOPPED, null);
    }

    private void forceStopPackage(final String packageName, int userId, int userRunningFlags,
            String reason) {
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
        final long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            synchronized (this) {
                int[] users = userId == UserHandle.USER_ALL
                        ? mUserController.getUsers() : new int[] { userId };
                for (int user : users) {
                    if (getPackageManagerInternal().isPackageStateProtected(
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
                    if (mUserController.isUserRunning(user, userRunningFlags)) {
                        forceStopPackageLocked(packageName, UserHandle.getAppId(pkgUid),
                                false /* callerWillRestart */, false /* purgeCache */,
                                true /* doIt */, false /* evenPersistent */,
                                false /* uninstalling */, true /* packageStateStopped */, user,
                                reason == null ? ("from pid " + callingPid) : reason);
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
        int callingPid = Binder.getCallingPid();
        if (callingPid == myPid()) {
            //  Yeah, um, no.
            return;
        }
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (getPackageManagerInternal().filterAppAccess(packageName, callingUid, callingUserId)) {
            Slog.w(TAG, "Failed trying to add dependency on non-existing package: " + packageName);
            return;
        }
        ProcessRecord proc;
        synchronized (mPidsSelfLocked) {
            proc = mPidsSelfLocked.get(Binder.getCallingPid());
        }
        if (proc != null) {
            ArraySet<String> pkgDeps = proc.getPkgDeps();
            synchronized (this) {
                synchronized (mProcLock) {
                    if (pkgDeps == null) {
                        proc.setPkgDeps(pkgDeps = new ArraySet<String>(1));
                    }
                    pkgDeps.add(packageName);
                }
            }
        }
    }

    /*
     * The pkg name and app id have to be specified.
     */
    @Override
    public void killApplication(String pkg, int appId, int userId, String reason,
            int exitInfoReason) {
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
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = pkg;
            args.arg2 = reason;
            args.arg3 = exitInfoReason;
            msg.obj = args;
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

        // Check if the caller is actually instrumented and from shell, if it's true, we may lift
        // the throttle of PSS info sampling.
        boolean isCallerInstrumentedFromShell = false;
        synchronized (mProcLock) {
            synchronized (mPidsSelfLocked) {
                ProcessRecord caller = mPidsSelfLocked.get(callingPid);
                if (caller != null) {
                    final ActiveInstrumentation instr = caller.getActiveInstrumentation();
                    isCallerInstrumentedFromShell = instr != null
                            && (instr.mSourceUid == SHELL_UID || instr.mSourceUid == ROOT_UID);
                }
            }
        }

        final Debug.MemoryInfo[] infos = new Debug.MemoryInfo[pids.length];
        for (int i=pids.length-1; i>=0; i--) {
            final Debug.MemoryInfo mi = infos[i] = new Debug.MemoryInfo();
            final ProcessRecord proc;
            final int oomAdj;
            final ProcessProfileRecord profile;
            synchronized (mAppProfiler.mProfilerLock) {
                synchronized (mPidsSelfLocked) {
                    proc = mPidsSelfLocked.get(pids[i]);
                    if (proc != null) {
                        profile = proc.mProfile;
                        oomAdj = profile.getSetAdj();
                    } else {
                        profile = null;
                        oomAdj = 0;
                    }
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
            if (proc != null) {
                synchronized (mAppProfiler.mProfilerLock) {
                    if (profile.getLastMemInfoTime() >= lastNow && profile.getLastMemInfo() != null
                            && !isCallerInstrumentedFromShell) {
                        // It hasn't been long enough that we want to take another sample; return
                        // the last one.
                        mi.set(profile.getLastMemInfo());
                        continue;
                    }
                }
            }
            final long startTime = SystemClock.currentThreadTimeMillis();
            final Debug.MemoryInfo memInfo = new Debug.MemoryInfo();
            Debug.getMemoryInfo(pids[i], memInfo);
            final long duration = SystemClock.currentThreadTimeMillis() - startTime;
            mi.set(memInfo);
            if (proc != null) {
                synchronized (mAppProfiler.mProfilerLock) {
                    profile.setLastMemInfo(memInfo);
                    profile.setLastMemInfoTime(SystemClock.uptimeMillis());
                    if (profile.getThread() != null && profile.getSetAdj() == oomAdj) {
                        // Record this for posterity if the process has been stable.
                        profile.addPss(mi.getTotalPss(),
                                mi.getTotalUss(), mi.getTotalRss(), false,
                                ProcessStats.ADD_PSS_EXTERNAL_SLOW, duration);
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

        final long[] pss = new long[pids.length];
        for (int i=pids.length-1; i>=0; i--) {
            ProcessRecord proc;
            int oomAdj;
            synchronized (mProcLock) {
                synchronized (mPidsSelfLocked) {
                    proc = mPidsSelfLocked.get(pids[i]);
                    oomAdj = proc != null ? proc.mState.getSetAdj() : 0;
                }
            }
            if (!allUids || (!allUsers && UserHandle.getUserId(proc.uid) != userId)) {
                // The caller is not allow to get information about this other process...
                // just leave it empty.
                continue;
            }
            final long[] tmpUss = new long[3];
            final long startTime = SystemClock.currentThreadTimeMillis();
            final long pi = pss[i] = Debug.getPss(pids[i], tmpUss, null);
            final long duration = SystemClock.currentThreadTimeMillis() - startTime;
            if (proc != null) {
                final ProcessProfileRecord profile = proc.mProfile;
                synchronized (mAppProfiler.mProfilerLock) {
                    if (profile.getThread() != null && profile.getSetAdj() == oomAdj) {
                        // Record this for posterity if the process has been stable.
                        profile.addPss(pi, tmpUss[0], tmpUss[2], false,
                                ProcessStats.ADD_PSS_EXTERNAL, duration);
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
                ProcessRecord app = getProcessRecordLocked(processName, uid);
                IApplicationThread thread;
                if (app != null && (thread = app.getThread()) != null) {
                    try {
                        thread.scheduleSuicide();
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
                false, true, false, false, false, UserHandle.getUserId(uid), reason);
    }

    @GuardedBy("this")
    private void finishForceStopPackageLocked(final String packageName, int uid) {
        int flags = 0;
        if (!mProcessesReady) {
            flags = Intent.FLAG_RECEIVER_REGISTERED_ONLY
                    | Intent.FLAG_RECEIVER_FOREGROUND;
        }
        mPackageManagerInt.sendPackageRestartedBroadcast(packageName, uid, flags);
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
                packageName, disabledClasses, userId, false /* evenPersistent */,
                false /* fullStop */, true /* doIt */);

        // Clean-up disabled providers.
        ArrayList<ContentProviderRecord> providers = new ArrayList<>();
        mCpHelper.getProviderMap().collectPackageProvidersLocked(
                packageName, disabledClasses, true, false, userId, providers);
        for (int i = providers.size() - 1; i >= 0; i--) {
            mCpHelper.removeDyingProviderLocked(null, providers.get(i), true);
        }

        // Clean-up disabled broadcast receivers.
        mBroadcastQueue.cleanupDisabledPackageReceiversLocked(
                packageName, disabledClasses, userId);

    }

    final boolean clearBroadcastQueueForUserLocked(int userId) {
        boolean didSomething = mBroadcastQueue.cleanupDisabledPackageReceiversLocked(
                null, null, userId);
        return didSomething;
    }

    @GuardedBy("this")
    final void forceStopAppZygoteLocked(String packageName, int appId, int userId) {
        if (packageName == null) {
            return;
        }
        if (appId < 0) {
            appId = UserHandle.getAppId(getPackageManagerInternal().getPackageUid(packageName,
                    MATCH_DEBUG_TRIAGED_MISSING | MATCH_ANY_USER, UserHandle.USER_SYSTEM));
        }

        mProcessList.killAppZygotesLocked(packageName, appId, userId, true /* force */);
    }

    void stopAppForUserInternal(final String packageName, @UserIdInt final int userId) {
        final int uid = getPackageManagerInternal().getPackageUid(packageName,
                MATCH_DEBUG_TRIAGED_MISSING | MATCH_ANY_USER, userId);
        if (uid < 0) {
            Slog.w(TAG, "Asked to stop " + packageName + "/u" + userId
                    + " but does not exist in that user");
            return;
        }

        // Policy: certain classes of app are not subject to user-invoked stop
        if (getPackageManagerInternal().isPackageStateProtected(packageName, userId)) {
            Slog.w(TAG, "Asked to stop " + packageName + "/u" + userId
                    + " but it is protected");
            return;
        }

        Slog.i(TAG, "Stopping app for user: " + packageName + "/" + userId);

        // A specific subset of the work done in forceStopPackageLocked(), because we are
        // intentionally not rendering the app nonfunctional; we're just halting its current
        // execution.
        final int appId = UserHandle.getAppId(uid);
        synchronized (this) {
            synchronized (mProcLock) {
                mAtmInternal.onForceStopPackage(packageName, true, false, userId);

                mProcessList.killPackageProcessesLSP(packageName, appId, userId,
                        ProcessList.INVALID_ADJ, true, false, true,
                        false, true /* setRemoved */, false,
                        ApplicationExitInfo.REASON_USER_REQUESTED,
                        ApplicationExitInfo.SUBREASON_STOP_APP,
                        "fully stop " + packageName + "/" + userId + " by user request");
            }

            mServices.bringDownDisabledPackageServicesLocked(
                    packageName, null, userId, false, true, true);
            mServices.onUidRemovedLocked(uid);

            if (mBooted) {
                mAtmInternal.resumeTopActivities(true);
            }
        }
    }

    @GuardedBy("this")
    final boolean forceStopPackageLocked(String packageName, int appId,
            boolean callerWillRestart, boolean purgeCache, boolean doit,
            boolean evenPersistent, boolean uninstalling, boolean packageStateStopped,
            int userId, String reasonString) {
        int reason = packageName == null ? ApplicationExitInfo.REASON_USER_STOPPED
                : ApplicationExitInfo.REASON_USER_REQUESTED;
        return forceStopPackageLocked(packageName, appId, callerWillRestart, purgeCache, doit,
                evenPersistent, uninstalling, packageStateStopped, userId, reasonString, reason);

    }

    @GuardedBy("this")
    final boolean forceStopPackageLocked(String packageName, int appId,
            boolean callerWillRestart, boolean purgeCache, boolean doit,
            boolean evenPersistent, boolean uninstalling, boolean packageStateStopped,
            int userId, String reasonString, int reason) {
        int i;

        if (userId == UserHandle.USER_ALL && packageName == null) {
            Slog.w(TAG, "Can't force stop all processes of all users, that is insane!");
        }

        final int uid = getPackageManagerInternal().getPackageUid(packageName,
                            MATCH_DEBUG_TRIAGED_MISSING | MATCH_ANY_USER, UserHandle.USER_SYSTEM);
        if (appId < 0 && packageName != null) {
            appId = UserHandle.getAppId(uid);
        }

        boolean didSomething;
        if (doit) {
            if (packageName != null) {
                Slog.i(TAG, "Force stopping " + packageName + " appid=" + appId
                        + " user=" + userId + ": " + reasonString);
            } else {
                Slog.i(TAG, "Force stopping u" + userId + ": " + reasonString);
            }

            mAppErrors.resetProcessCrashTime(packageName == null, appId, userId);
        }

        synchronized (mProcLock) {
            // Notify first that the package is stopped, so its process won't be restarted
            // unexpectedly if there is an activity of the package without attached process
            // becomes visible when killing its other processes with visible activities.
            didSomething = mAtmInternal.onForceStopPackage(
                    packageName, doit, evenPersistent, userId);
            int subReason;
            if (reason == ApplicationExitInfo.REASON_USER_REQUESTED) {
                subReason = ApplicationExitInfo.SUBREASON_FORCE_STOP;
            } else {
                subReason = ApplicationExitInfo.SUBREASON_UNKNOWN;
            }

            didSomething |= mProcessList.killPackageProcessesLSP(packageName, appId, userId,
                    ProcessList.INVALID_ADJ, callerWillRestart, false /* allowRestart */, doit,
                    evenPersistent, true /* setRemoved */, uninstalling,
                    reason,
                    subReason,
                    (packageName == null ? ("stop user " + userId) : ("stop " + packageName))
                    + " due to " + reasonString);
        }

        if (mServices.bringDownDisabledPackageServicesLocked(
                packageName, null /* filterByClasses */, userId, evenPersistent, true, doit)) {
            if (!doit) {
                return true;
            }
            didSomething = true;
        }
        mServices.onUidRemovedLocked(uid);

        if (packageName == null) {
            // Remove all sticky broadcasts from this user.
            synchronized (mStickyBroadcasts) {
                mStickyBroadcasts.remove(userId);
            }
        }

        ArrayList<ContentProviderRecord> providers = new ArrayList<>();
        if (mCpHelper.getProviderMap().collectPackageProvidersLocked(packageName, null, doit,
                evenPersistent, userId, providers)) {
            if (!doit) {
                return true;
            }
            didSomething = true;
        }
        for (i = providers.size() - 1; i >= 0; i--) {
            mCpHelper.removeDyingProviderLocked(null, providers.get(i), true);
        }

        // Remove transient permissions granted from/to this package/user
        mUgmInternal.removeUriPermissionsForPackage(packageName, userId, false, false);

        if (doit) {
            didSomething |= mBroadcastQueue.cleanupDisabledPackageReceiversLocked(
                        packageName, null, userId);
        }

        boolean clearPendingIntentsForStoppedApp = false;
        try {
            clearPendingIntentsForStoppedApp = (packageStateStopped
                    && android.content.pm.Flags.stayStopped());
        } catch (IllegalStateException e) {
            // It's unlikely for a package to be force-stopped early in the boot cycle. So, if we
            // check for 'packageStateStopped' which should evaluate to 'false', then this should
            // ensure we are not accessing the flag early in the boot cycle. As an additional
            // safety measure, catch the exception and ignore to avoid causing a device restart.
            clearPendingIntentsForStoppedApp = false;
        }
        if (packageName == null || uninstalling || clearPendingIntentsForStoppedApp) {
            final int cancelReason;
            if (packageName == null) {
                cancelReason = PendingIntentRecord.CANCEL_REASON_USER_STOPPED;
            } else if (uninstalling) {
                cancelReason = PendingIntentRecord.CANCEL_REASON_OWNER_UNINSTALLED;
            } else {
                cancelReason = PendingIntentRecord.CANCEL_REASON_OWNER_FORCE_STOPPED;
            }
            didSomething |= mPendingIntentController.removePendingIntentsForPackage(
                    packageName, userId, appId, doit, cancelReason);
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
    void handleProcessStartOrKillTimeoutLocked(ProcessRecord app, boolean isKillTimeout) {
        final int pid = app.getPid();
        boolean gone = isKillTimeout || removePidLocked(pid, app);

        if (gone) {
            if (isKillTimeout) {
                // It's still alive... maybe blocked at uninterruptible sleep ?
                final ProcessRecord successor = app.mSuccessor;
                if (successor == null) {
                    // There might be a race, nothing to do here.
                    return;
                }
                Slog.wtf(TAG, app.toString() + " " + app.getDyingPid()
                        + " refused to die while trying to launch " + successor
                        + ", cancelling the process start");

                // It doesn't make sense to proceed with launching the new instance while the old
                // instance is still alive, abort the launch.
                app.mSuccessorStartRunnable = null;
                app.mSuccessor = null;
                successor.mPredecessor = null;

                // We're going to cleanup the successor process record, which wasn't started at all.
                app = successor;
            } else {
                final String msg = "Process " + app + " failed to attach";
                Slog.w(TAG, msg);
                EventLogTags.writeAmProcessStartTimeout(app.userId, pid, app.uid, app.processName);
                if (app.getActiveInstrumentation() != null) {
                    final Bundle info = new Bundle();
                    info.putString("shortMsg", "failed to attach");
                    info.putString("longMsg", msg);
                    finishInstrumentationLocked(app, Activity.RESULT_CANCELED, info);
                }
            }
            synchronized (mProcLock) {
                mProcessList.removeProcessNameLocked(app.processName, app.uid);
                mAtmInternal.clearHeavyWeightProcessIfEquals(app.getWindowProcessController());
                // Take care of any launching providers waiting for this process.
                mCpHelper.cleanupAppInLaunchingProvidersLocked(app, true);
                // Take care of any services that are waiting for the process.
                mServices.processStartTimedOutLocked(app);
                // Take care of any broadcasts waiting for the process.
                mBroadcastQueue.onApplicationTimeoutLocked(app);
                if (!isKillTimeout) {
                    mBatteryStatsService.noteProcessFinish(app.processName, app.info.uid);
                    app.killLocked("start timeout",
                            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, true);
                    removeLruProcessLocked(app);
                }
                if (app.isolated) {
                    mBatteryStatsService.removeIsolatedUid(app.uid, app.info.uid);
                    mProcessList.mAppExitInfoTracker.mIsolatedUidRecords.removeIsolatedUid(
                            app.uid, app.info.uid);
                    getPackageManagerInternal().removeIsolatedUid(app.uid);
                }
            }
            final BackupRecord backupTarget = mBackupTargets.get(app.userId);
            if (!isKillTimeout && backupTarget != null && backupTarget.app.getPid() == pid) {
                Slog.w(TAG, "Unattached app died before backup, skipping");
                final int userId = app.userId;
                final String packageName = app.info.packageName;
                mHandler.post(new Runnable() {
                @Override
                    public void run() {
                        try {
                            IBackupManager bm = IBackupManager.Stub.asInterface(
                                    ServiceManager.getService(Context.BACKUP_SERVICE));
                            bm.agentDisconnectedForUser(userId, packageName);
                        } catch (RemoteException e) {
                            // Can't happen; the backup manager is local
                        }
                    }
                });
            }
        } else {
            Slog.w(TAG, "Spurious process start timeout - pid not known for " + app);
        }
    }

    @GuardedBy("this")
    private void attachApplicationLocked(@NonNull IApplicationThread thread,
            int pid, int callingUid, long startSeq) {
        // Find the application record that is being attached...  either via
        // the pid if we are running in multiple processes, or just pull the
        // next app record if we are emulating process with anonymous threads.
        ProcessRecord app;
        long startTime = SystemClock.uptimeMillis();
        long bindApplicationTimeMillis;
        long bindApplicationTimeNanos;
        if (pid != MY_PID && pid >= 0) {
            synchronized (mPidsSelfLocked) {
                app = mPidsSelfLocked.get(pid);
            }
            if (app != null && (app.getStartUid() != callingUid || app.getStartSeq() != startSeq)) {
                String processName = null;
                final ProcessRecord pending = mProcessList.mPendingStarts.get(startSeq);
                if (pending != null) {
                    processName = pending.processName;
                }
                final String msg = "attachApplicationLocked process:" + processName
                        + " startSeq:" + startSeq
                        + " pid:" + pid
                        + " belongs to another existing app:" + app.processName
                        + " startSeq:" + app.getStartSeq();
                Slog.wtf(TAG, msg);
                // SafetyNet logging for b/131105245.
                EventLog.writeEvent(0x534e4554, "131105245", app.getStartUid(), msg);
                // If there is already an app occupying that pid that hasn't been cleaned up
                cleanUpApplicationRecordLocked(app, pid, false, false, -1,
                        true /*replacingPid*/, false /* fromBinderDied */);
                removePidLocked(pid, app);
                app = null;
            }
        } else {
            app = null;
        }

        // It's possible that process called attachApplication before we got a chance to
        // update the internal state.
        if (app == null && startSeq > 0) {
            final ProcessRecord pending = mProcessList.mPendingStarts.get(startSeq);
            if (pending != null && pending.getStartUid() == callingUid
                    && pending.getStartSeq() == startSeq
                    && mProcessList.handleProcessStartedLocked(pending, pid,
                        pending.isUsingWrapper(), startSeq, true)) {
                app = pending;
            }
        }

        if (app == null) {
            Slog.w(TAG, "No pending application record for pid " + pid
                    + " (IApplicationThread " + thread + "); dropping process");
            EventLogTags.writeAmDropProcess(pid);
            if (pid > 0 && pid != MY_PID) {
                killProcessQuiet(pid);
                //TODO: killProcessGroup(app.info.uid, pid);
                // We can't log the app kill info for this process since we don't
                // know who it is, so just skip the logging.
            } else {
                try {
                    thread.scheduleExit();
                } catch (Exception e) {
                    // Ignore exceptions.
                }
            }
            return;
        }

        // If this application record is still attached to a previous
        // process, clean it up now.
        if (app.getThread() != null) {
            handleAppDiedLocked(app, pid, true, true, false /* fromBinderDied */);
        }

        // Tell the process all about itself.

        if (DEBUG_ALL) Slog.v(
                TAG, "Binding process pid " + pid + " to record " + app);

        final String processName = app.processName;
        try {
            AppDeathRecipient adr = new AppDeathRecipient(
                    app, pid, thread);
            thread.asBinder().linkToDeath(adr, 0);
            app.setDeathRecipient(adr);
        } catch (RemoteException e) {
            app.resetPackageList(mProcessStats);
            mProcessList.startProcessLocked(app,
                    new HostingRecord(HostingRecord.HOSTING_TYPE_LINK_FAIL, processName),
                    ZYGOTE_POLICY_FLAG_EMPTY);
            return;
        }

        EventLogTags.writeAmProcBound(app.userId, pid, app.processName);

        synchronized (mProcLock) {
            mOomAdjuster.setAttachingProcessStatesLSP(app);
            clearProcessForegroundLocked(app);
            app.setDebugging(false);
            app.setKilledByAm(false);
            app.setKilled(false);
            // We carefully use the same state that PackageManager uses for
            // filtering, since we use this flag to decide if we need to install
            // providers when user is unlocked later
            app.setUnlocked(StorageManager.isCeStorageUnlocked(app.userId));
        }

        boolean normalMode = mProcessesReady || isAllowedWhileBooting(app.info);
        List<ProviderInfo> providers = normalMode
                                            ? mCpHelper.generateApplicationProvidersLocked(app)
                                            : null;

        if (providers != null && mCpHelper.checkAppInLaunchingProvidersLocked(app)) {
            Message msg = mHandler.obtainMessage(CONTENT_PROVIDER_PUBLISH_TIMEOUT_MSG);
            msg.obj = app;
            mHandler.sendMessageDelayed(msg,
                    ContentResolver.CONTENT_PROVIDER_PUBLISH_TIMEOUT_MILLIS);
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
                if (mWaitForDebugger) {
                    if (mSuspendUponWait) {
                        testMode = ApplicationThreadConstants.DEBUG_SUSPEND;
                    } else {
                        testMode = ApplicationThreadConstants.DEBUG_WAIT;
                    }
                } else {
                    testMode = ApplicationThreadConstants.DEBUG_ON;
                }
                app.setDebugging(true);
                if (mDebugTransient) {
                    mDebugApp = mOrigDebugApp;
                    mWaitForDebugger = mOrigWaitForDebugger;
                }
            }

            boolean enableTrackAllocation = false;
            synchronized (mProcLock) {
                if (mTrackAllocationApp != null && mTrackAllocationApp.equals(processName)) {
                    enableTrackAllocation = true;
                    mTrackAllocationApp = null;
                }
            }

            // If the app is being launched for restore or full backup, set it up specially
            boolean isRestrictedBackupMode = false;
            if (backupTarget != null && backupTarget.appInfo.packageName.equals(processName)) {
                isRestrictedBackupMode = backupTarget.appInfo.uid >= FIRST_APPLICATION_UID
                        && ((backupTarget.backupMode == BackupRecord.RESTORE_FULL)
                                || (backupTarget.backupMode == BackupRecord.BACKUP_FULL));
            }

            final ActiveInstrumentation instr = app.getActiveInstrumentation();

            if (instr != null) {
                notifyPackageUse(instr.mClass.getPackageName(),
                                 PackageManager.NOTIFY_PACKAGE_USE_INSTRUMENTATION);
            }
            ProtoLog.v(WM_DEBUG_CONFIGURATION, "Binding proc %s with config %s",
                    processName, app.getWindowProcessController().getConfiguration());
            ApplicationInfo appInfo = instr != null ? instr.mTargetInfo : app.info;
            app.setCompat(compatibilityInfoForPackage(appInfo));

            ProfilerInfo profilerInfo = mAppProfiler.setupProfilerInfoLocked(thread, app, instr);

            // We deprecated Build.SERIAL and it is not accessible to
            // Instant Apps and target APIs higher than O MR1. Since access to the serial
            // is now behind a permission we push down the value.
            final String buildSerial = (!appInfo.isInstantApp()
                    && appInfo.targetSdkVersion < Build.VERSION_CODES.P)
                            ? sTheRealBuildSerial : Build.UNKNOWN;

            // Figure out whether the app needs to run in autofill compat mode.
            AutofillOptions autofillOptions = null;
            if (UserHandle.getAppId(app.info.uid) >= Process.FIRST_APPLICATION_UID) {
                final AutofillManagerInternal afm = LocalServices.getService(
                        AutofillManagerInternal.class);
                if (afm != null) {
                    autofillOptions = afm.getAutofillOptions(
                            app.info.packageName, app.info.longVersionCode, app.userId);
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
            SharedMemory serializedSystemFontMap = null;
            final FontManagerInternal fm = LocalServices.getService(FontManagerInternal.class);
            if (fm != null) {
                serializedSystemFontMap = fm.getSerializedSystemFontMap();
            }

            checkTime(startTime, "attachApplicationLocked: immediately before bindApplication");
            bindApplicationTimeMillis = SystemClock.uptimeMillis();
            bindApplicationTimeNanos = SystemClock.uptimeNanos();
            mAtmInternal.preBindApplication(app.getWindowProcessController());
            final ActiveInstrumentation instr2 = app.getActiveInstrumentation();
            if (mPlatformCompat != null) {
                mPlatformCompat.resetReporting(app.info);
            }
            final ProviderInfoList providerList = ProviderInfoList.fromList(providers);
            app.mProfile.mLastCpuDelayTime.set(app.getCpuDelayTime());
            if (app.getIsolatedEntryPoint() != null) {
                // This is an isolated process which should just call an entry point instead of
                // being bound to an application.
                thread.runIsolatedEntryPoint(
                        app.getIsolatedEntryPoint(), app.getIsolatedEntryPointArgs());
            } else {
                boolean isSdkInSandbox = false;
                ComponentName instrumentationName = null;
                Bundle instrumentationArgs = null;
                IInstrumentationWatcher instrumentationWatcher = null;
                IUiAutomationConnection instrumentationUiConnection = null;
                if (instr2 != null) {
                    isSdkInSandbox = instr2.mIsSdkInSandbox;
                    instrumentationName = instr2.mClass;
                    instrumentationArgs = instr2.mArguments;
                    instrumentationWatcher = instr2.mWatcher;
                    instrumentationUiConnection = instr2.mUiAutomationConnection;
                }
                thread.bindApplication(
                        processName,
                        appInfo,
                        app.sdkSandboxClientAppVolumeUuid,
                        app.sdkSandboxClientAppPackage,
                        isSdkInSandbox,
                        providerList,
                        instrumentationName,
                        profilerInfo,
                        instrumentationArgs,
                        instrumentationWatcher,
                        instrumentationUiConnection,
                        testMode,
                        mBinderTransactionTrackingEnabled,
                        enableTrackAllocation,
                        isRestrictedBackupMode || !normalMode,
                        app.isPersistent(),
                        new Configuration(app.getWindowProcessController().getConfiguration()),
                        app.getCompat(),
                        getCommonServicesLocked(app.isolated),
                        mCoreSettingsObserver.getCoreSettingsLocked(),
                        buildSerial,
                        autofillOptions,
                        contentCaptureOptions,
                        app.getDisabledCompatChanges(),
                        app.getLoggableCompatChanges(),
                        serializedSystemFontMap,
                        app.getStartElapsedTime(),
                        app.getStartUptime());
            }

            Message msg = mHandler.obtainMessage(BIND_APPLICATION_TIMEOUT_SOFT_MSG);
            msg.obj = app;
            msg.arg1 = BIND_APPLICATION_TIMEOUT;
            mHandler.sendMessageDelayed(msg, msg.arg1 /* BIND_APPLICATION_TIMEOUT */);
            mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);

            if (profilerInfo != null) {
                profilerInfo.closeFd();
                profilerInfo = null;
            }

            app.setBindApplicationTime(bindApplicationTimeMillis);
            mProcessList.getAppStartInfoTracker()
                    .addTimestampToStart(app, bindApplicationTimeNanos,
                            ApplicationStartInfo.START_TIMESTAMP_BIND_APPLICATION);

            // Make app active after binding application or client may be running requests (e.g
            // starting activities) before it is ready.
            synchronized (mProcLock) {
                app.makeActive(thread, mProcessStats);
                checkTime(startTime, "attachApplicationLocked: immediately after bindApplication");
            }
            app.setPendingFinishAttach(true);

            updateLruProcessLocked(app, false, null);
            checkTime(startTime, "attachApplicationLocked: after updateLruProcessLocked");

            updateOomAdjLocked(app, OOM_ADJ_REASON_PROCESS_BEGIN);
            checkTime(startTime, "attachApplicationLocked: after updateOomAdjLocked");

            final long now = SystemClock.uptimeMillis();
            synchronized (mAppProfiler.mProfilerLock) {
                app.mProfile.setLastRequestedGc(now);
                app.mProfile.setLastLowMemory(now);
            }

            // Remove this record from the list of starting applications.
            mPersistentStartingProcesses.remove(app);
            if (DEBUG_PROCESSES && mProcessesOnHold.contains(app)) {
                Slog.v(TAG_PROCESSES, "Attach application locked removing on hold: " + app);
            }
            mProcessesOnHold.remove(app);

            if (!mConstants.mEnableWaitForFinishAttachApplication) {
                finishAttachApplicationInner(startSeq, callingUid, pid);
            }
            maybeSendBootCompletedLocked(app);
        } catch (Exception e) {
            // We need kill the process group here. (b/148588589)
            Slog.wtf(TAG, "Exception thrown during bind of " + app, e);
            app.resetPackageList(mProcessStats);
            app.unlinkDeathRecipient();
            app.killLocked("error during bind", ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                    true);
            handleAppDiedLocked(app, pid, false, true, false /* fromBinderDied */);
            return;
        }
    }

    @Override
    public final void attachApplication(IApplicationThread thread, long startSeq) {
        if (thread == null) {
            throw new SecurityException("Invalid application interface");
        }
        synchronized (this) {
            int callingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();
            final long origId = Binder.clearCallingIdentity();
            attachApplicationLocked(thread, callingPid, callingUid, startSeq);
            Binder.restoreCallingIdentity(origId);
        }
    }

    private void finishAttachApplicationInner(long startSeq, int uid, int pid) {
        final long startTime = SystemClock.uptimeMillis();
        // Find the application record that is being attached...  either via
        // the pid if we are running in multiple processes, or just pull the
        // next app record if we are emulating process with anonymous threads.
        final ProcessRecord app;
        synchronized (mPidsSelfLocked) {
            app = mPidsSelfLocked.get(pid);
        }

        if (app != null && app.getStartUid() == uid && app.getStartSeq() == startSeq) {
            mHandler.removeMessages(BIND_APPLICATION_TIMEOUT_SOFT_MSG, app);
            mHandler.removeMessages(BIND_APPLICATION_TIMEOUT_HARD_MSG, app);
        } else {
            Slog.wtf(TAG, "Mismatched or missing ProcessRecord: " + app + ". Pid: " + pid
                    + ". Uid: " + uid);
            if (pid > 0) {
                killProcess(pid);
                killProcessGroup(uid, pid);
            }
            mProcessList.noteAppKill(pid, uid,
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                    ApplicationExitInfo.SUBREASON_UNKNOWN,
                    "wrong startSeq");
            synchronized (this) {
                app.killLocked("unexpected process record",
                        ApplicationExitInfo.REASON_OTHER, true);
            }
            return;
        }

        synchronized (this) {
            // Mark the finish attach application phase as completed
            app.setPendingFinishAttach(false);

            final boolean normalMode = mProcessesReady || isAllowedWhileBooting(app.info);
            final String processName = app.processName;
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
                    checkTime(startTime, "finishAttachApplicationInner: "
                            + "after mServices.attachApplicationLocked");
                } catch (Exception e) {
                    Slog.wtf(TAG, "Exception thrown starting services in " + app, e);
                    badApp = true;
                }
            }

            // Check if a next-broadcast receiver is in this process...
            if (!badApp) {
                try {
                    didSomething |= mBroadcastQueue.onApplicationAttachedLocked(app);
                    checkTime(startTime, "finishAttachApplicationInner: "
                            + "after dispatching broadcasts");
                } catch (BroadcastDeliveryFailedException e) {
                    // If the app died trying to launch the receiver we declare it 'bad'
                    Slog.wtf(TAG, "Exception thrown dispatching broadcasts in " + app, e);
                    badApp = true;
                }
            }

            // Check whether the next backup agent is in this process...
            final BackupRecord backupTarget = mBackupTargets.get(app.userId);
            if (!badApp && backupTarget != null && backupTarget.app == app) {
                if (DEBUG_BACKUP) {
                    Slog.v(TAG_BACKUP,
                            "New app is backup target, launching agent for " + app);
                }

                notifyPackageUse(backupTarget.appInfo.packageName,
                        PackageManager.NOTIFY_PACKAGE_USE_BACKUP);
                try {
                    app.getThread().scheduleCreateBackupAgent(backupTarget.appInfo,
                            backupTarget.backupMode, backupTarget.userId,
                            backupTarget.backupDestination);
                } catch (Exception e) {
                    Slog.wtf(TAG, "Exception thrown creating backup agent in " + app, e);
                    badApp = true;
                }
            }

            if (badApp) {
                app.killLocked("error during init",
                        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, true);
                handleAppDiedLocked(app, pid, false, true, false /* fromBinderDied */);
                return;
            }

            if (!didSomething) {
                updateOomAdjLocked(app, OOM_ADJ_REASON_PROCESS_BEGIN);
                checkTime(startTime, "finishAttachApplicationInner: after updateOomAdjLocked");
            }

            final HostingRecord hostingRecord = app.getHostingRecord();
            final String shortAction = getShortAction(hostingRecord.getAction());
            FrameworkStatsLog.write(
                    FrameworkStatsLog.PROCESS_START_TIME,
                    app.info.uid,
                    pid,
                    app.info.packageName,
                    FrameworkStatsLog.PROCESS_START_TIME__TYPE__COLD,
                    app.getStartElapsedTime(),
                    (int) (app.getBindApplicationTime() - app.getStartUptime()),
                    (int) (SystemClock.uptimeMillis() - app.getStartUptime()),
                    hostingRecord.getType(),
                    hostingRecord.getName(),
                    shortAction,
                    HostingRecord.getHostingTypeIdStatsd(hostingRecord.getType()),
                    HostingRecord.getTriggerTypeForStatsd(hostingRecord.getTriggerType()));
        }
    }

    @Override
    public final void finishAttachApplication(long startSeq, long timestampApplicationOnCreateNs) {
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();

        if (!mConstants.mEnableWaitForFinishAttachApplication) {
            Slog.i(TAG, "Flag disabled. Ignoring finishAttachApplication from uid: "
                    + uid + ". pid: " + pid);
            return;
        }

        if (pid == MY_PID && uid == SYSTEM_UID) {
            return;
        }

        final long origId = Binder.clearCallingIdentity();
        try {
            finishAttachApplicationInner(startSeq, uid, pid);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        if (android.app.Flags.appStartInfoTimestamps() && timestampApplicationOnCreateNs > 0) {
            addStartInfoTimestampInternal(ApplicationStartInfo.START_TIMESTAMP_APPLICATION_ONCREATE,
                    timestampApplicationOnCreateNs, UserHandle.getUserId(uid), uid);
        }
    }

    private void handleBindApplicationTimeoutSoft(ProcessRecord app, int softTimeoutMillis) {
        // Similar logic as the broadcast delivery timeout:
        // instead of immediately triggering an ANR, extend the timeout by
        // the amount of time the process was runnable-but-waiting; we're
        // only willing to do this once before triggering an hard ANR.
        final long cpuDelayTime = app.getCpuDelayTime() - app.mProfile.mLastCpuDelayTime.get();
        final long hardTimeoutMillis = MathUtils.constrain(cpuDelayTime, 0, softTimeoutMillis);

        if (hardTimeoutMillis == 0) {
            handleBindApplicationTimeoutHard(app);
            return;
        }

        Slog.i(TAG, "Extending process start timeout by " + hardTimeoutMillis + "ms for " + app);
        Trace.instant(Trace.TRACE_TAG_ACTIVITY_MANAGER, "bindApplicationTimeSoft "
                + app.processName + "(" + app.getPid() + ")");
        final Message msg = mHandler.obtainMessage(BIND_APPLICATION_TIMEOUT_HARD_MSG, app);
        mHandler.sendMessageDelayed(msg, hardTimeoutMillis);
    }

    private void handleBindApplicationTimeoutHard(ProcessRecord app) {
        final String anrMessage;
        synchronized (app) {
            anrMessage = "Process " + app + " failed to complete startup";
        }

        mAnrHelper.appNotResponding(app, TimeoutRecord.forAppStart(anrMessage));
    }

    /**
     * @return The last part of the string of an intent's action.
     */
    static @Nullable String getShortAction(@Nullable String action) {
        String shortAction = action;
        if (action != null) {
            int index = action.lastIndexOf('.');
            if (index != -1 && index != action.length() - 1) {
                shortAction = action.substring(index + 1);
            }
        }
        return shortAction;
    }

    void checkTime(long startTime, String where) {
        long now = SystemClock.uptimeMillis();
        if ((now - startTime) > 50) {
            // If we are taking more than 50ms, log about it.
            Slog.w(TAG, "Slow operation: " + (now - startTime) + "ms so far, now at " + where);
        }
    }

    /**
     * Send LOCKED_BOOT_COMPLETED and BOOT_COMPLETED to the package explicitly when unstopped,
     * or when the package first starts in private space
     */
    private void maybeSendBootCompletedLocked(ProcessRecord app) {
        boolean sendBroadcast = false;
        if (android.os.Flags.allowPrivateProfile()
                && android.multiuser.Flags.enablePrivateSpaceFeatures()) {
            final UserManagerInternal umInternal =
                    LocalServices.getService(UserManagerInternal.class);
            UserInfo userInfo = umInternal.getUserInfo(app.userId);

            if (userInfo != null && userInfo.isPrivateProfile()) {
                // Packages in private space get deferred boot completed whenever they start the
                // first time since profile start
                if (!mPrivateSpaceBootCompletedPackages.contains(app.info.packageName)) {
                    mPrivateSpaceBootCompletedPackages.add(app.info.packageName);
                    sendBroadcast = true;
                } // else, stopped packages in private space may still hit the logic below
            }
        }

        final boolean wasForceStopped = app.wasForceStopped()
                || app.getWindowProcessController().wasForceStopped();
        if (android.app.Flags.appRestrictionsApi() && wasForceStopped) {
            noteAppRestrictionEnabled(app.info.packageName, app.uid,
                    RESTRICTION_LEVEL_FORCE_STOPPED, false,
                    RESTRICTION_REASON_USAGE, "unknown", RESTRICTION_SOURCE_USER, 0L);
        }

        if (!sendBroadcast) {
            if (!android.content.pm.Flags.stayStopped()) return;
            // Nothing to do if it wasn't previously stopped
            if (!wasForceStopped) {
                return;
            }
        }

        // Send LOCKED_BOOT_COMPLETED, if necessary
        if (app.getApplicationInfo().isEncryptionAware()) {
            sendBootBroadcastToAppLocked(app, new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED),
                    REASON_LOCKED_BOOT_COMPLETED);
        }
        // Send BOOT_COMPLETED if the user is unlocked
        if (StorageManager.isCeStorageUnlocked(app.userId)) {
            sendBootBroadcastToAppLocked(app, new Intent(Intent.ACTION_BOOT_COMPLETED),
                    REASON_BOOT_COMPLETED);
        }
        // The stopped state is reset in ProcessRecord when the pid changes, to deal with
        // any re-use of the ProcessRecord.
    }

    /** Send a boot_completed broadcast to app */
    private void sendBootBroadcastToAppLocked(ProcessRecord app, Intent intent,
            @PowerExemptionManager.ReasonCode int reason) {
        intent.setPackage(app.info.packageName);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, app.userId);
        intent.addFlags(Intent.FLAG_RECEIVER_NO_ABORT
                | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND
                | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);

        broadcastIntentLocked(null, null, null, intent, null, null, 0, null, null,
                new String[]{android.Manifest.permission.RECEIVE_BOOT_COMPLETED},
                null, null, AppOpsManager.OP_NONE,
                null, true,
                false, MY_PID, SYSTEM_UID,
                SYSTEM_UID, MY_PID, app.userId);
    }

    @Override
    public void showBootMessage(final CharSequence msg, final boolean always) {
        if (Binder.getCallingUid() != myUid()) {
            throw new SecurityException();
        }
        mWindowManager.showBootMessage(msg, always);
    }

    final void finishBooting() {
        TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG + "Timing",
                Trace.TRACE_TAG_ACTIVITY_MANAGER);
        t.traceBegin("FinishBooting");

        synchronized (this) {
            if (!mBootAnimationComplete) {
                mCallFinishBooting = true;
                return;
            }
            mCallFinishBooting = false;
        }

        // Let the ART runtime in zygote and system_server know that the boot completed.
        ZYGOTE_PROCESS.bootCompleted();
        VMRuntime.bootCompleted();

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
                                    false, 0, "query restart")) {
                                setResultCode(Activity.RESULT_OK);
                                return;
                            }
                        }
                    }
                }
            }
        }, pkgFilter);

        // Inform checkpointing systems of success
        try {
            // This line is needed to CTS test for the correct exception handling
            // See b/138952436#comment36 for context
            Slog.i(TAG, "About to commit checkpoint");
            IStorageManager storageManager = InstallLocationUtils.getStorageManager();
            storageManager.commitChanges();
        } catch (Exception e) {
            PowerManager pm = (PowerManager)
                     mInjector.getContext().getSystemService(Context.POWER_SERVICE);
            pm.reboot("Checkpoint commit failed");
        }

        // Let system services know.
        mSystemServiceManager.startBootPhase(t, SystemService.PHASE_BOOT_COMPLETED);

        synchronized (this) {
            // Ensure that any processes we had put on hold are now started
            // up.
            final int NP = mProcessesOnHold.size();
            if (NP > 0) {
                ArrayList<ProcessRecord> procs = new ArrayList<ProcessRecord>(mProcessesOnHold);
                for (int ip = 0; ip < NP; ip++) {
                    if (DEBUG_PROCESSES) {
                        Slog.v(TAG_PROCESSES, "Starting process on hold: " + procs.get(ip));
                    }
                    mProcessList.startProcessLocked(procs.get(ip),
                            new HostingRecord(HostingRecord.HOSTING_TYPE_ON_HOLD),
                            ZYGOTE_POLICY_FLAG_BATCH_LAUNCH);
                }
            }
            if (mFactoryTest == FactoryTest.FACTORY_TEST_LOW_LEVEL) {
                return;
            }
            // Start looking for apps that are abusing wake locks.
            Message nmsg = mHandler.obtainMessage(CHECK_EXCESSIVE_POWER_USE_MSG);
            mHandler.sendMessageDelayed(nmsg, mConstants.POWER_CHECK_INTERVAL);
            // Check if we are performing userspace reboot before setting sys.boot_completed to
            // avoid race with init reseting sys.init.userspace_reboot.in_progress once sys
            // .boot_completed is 1.
            if (InitProperties.userspace_reboot_in_progress().orElse(false)) {
                UserspaceRebootLogger.noteUserspaceRebootSuccess();
            }
            // Tell anyone interested that we are done booting!
            SystemProperties.set("sys.boot_completed", "1");
            SystemProperties.set("dev.bootcomplete", "1");

            // Start PSI monitoring in LMKD if it was skipped earlier.
            ProcessList.startPsiMonitoringAfterBoot();

            mUserController.onBootComplete(
                    new IIntentReceiver.Stub() {
                        @Override
                        public void performReceive(Intent intent, int resultCode,
                                String data, Bundle extras, boolean ordered,
                                boolean sticky, int sendingUser) {
                            mBootCompletedTimestamp = SystemClock.uptimeMillis();
                            // Defer the full Pss collection as the system is really busy now.
                            mHandler.postDelayed(() -> {
                                synchronized (mProcLock) {
                                    mAppProfiler.requestPssAllProcsLPr(
                                            SystemClock.uptimeMillis(), true, false);
                                }
                            }, mConstants.FULL_PSS_MIN_INTERVAL);
                        }
                    });
            maybeLogUserspaceRebootEvent();
            mUserController.scheduleStartProfiles();
        }
        // UART is on if init's console service is running, send a warning notification.
        showConsoleNotificationIfActive();
        showMteOverrideNotificationIfActive();

        t.traceEnd();
    }

    private void showConsoleNotificationIfActive() {
        if (!SystemProperties.get("init.svc.console").equals("running")) {
            return;
        }
        String title = mContext
                .getString(com.android.internal.R.string.console_running_notification_title);
        String message = mContext
                .getString(com.android.internal.R.string.console_running_notification_message);
        Notification notification =
                new Notification.Builder(mContext, SystemNotificationChannels.DEVELOPER)
                        .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                        .setWhen(0)
                        .setOngoing(true)
                        .setTicker(title)
                        .setDefaults(0)  // please be quiet
                        .setColor(mContext.getColor(
                                com.android.internal.R.color
                                        .system_notification_accent_color))
                        .setContentTitle(title)
                        .setContentText(message)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .build();

        NotificationManager notificationManager =
                mContext.getSystemService(NotificationManager.class);
        notificationManager.notifyAsUser(
                null, SystemMessage.NOTE_SERIAL_CONSOLE_ENABLED, notification, UserHandle.ALL);

    }

    private void showMteOverrideNotificationIfActive() {
        String bootctl = SystemProperties.get("arm64.memtag.bootctl");
        // If MTE is on, there is one in three cases:
        // * a fullmte build: ro.arm64.memtag.bootctl_supported is not set
        // * memtag: arm64.memtag.bootctl contains "memtag"
        // * memtag-once
        // In the condition below we detect memtag-once by exclusion.
        if (Arrays.asList(bootctl.split(",")).contains("memtag")
            || !SystemProperties.getBoolean("ro.arm64.memtag.bootctl_supported", false)
            || !com.android.internal.os.Zygote.nativeSupportsMemoryTagging()) {
            return;
        }
        String title = mContext
                .getString(com.android.internal.R.string.mte_override_notification_title);
        String message = mContext
                .getString(com.android.internal.R.string.mte_override_notification_message);
        Notification notification =
                new Notification.Builder(mContext, SystemNotificationChannels.DEVELOPER)
                        .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                        .setOngoing(true)
                        .setTicker(title)
                        .setDefaults(0)  // please be quiet
                        .setColor(mContext.getColor(
                                com.android.internal.R.color
                                        .system_notification_accent_color))
                        .setContentTitle(title)
                        .setContentText(message)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .build();

        NotificationManager notificationManager =
                mContext.getSystemService(NotificationManager.class);
        notificationManager.notifyAsUser(
                null, SystemMessage.NOTE_MTE_OVERRIDE_ENABLED, notification, UserHandle.ALL);
    }

    @Override
    public void bootAnimationComplete() {
        if (DEBUG_ALL) Slog.d(TAG, "bootAnimationComplete: Callers=" + Debug.getCallers(4));

        final boolean callFinishBooting;
        synchronized (this) {
            callFinishBooting = mCallFinishBooting;
            mBootAnimationComplete = true;
        }
        if (callFinishBooting) {
            finishBooting();
        }
    }

    /**
     * Starts Home if there is no completion signal from ThemeOverlayController
     */
    private void scheduleHomeTimeout() {
        if (!isHomeLaunchDelayable()) {
            Slog.d(TAG, "ThemeHomeDelay: Home launch is not delayable, skipping timeout creation");
            return;
        }

        if (!mHasHomeDelay.compareAndSet(false, true)) return;

        mHandler.postDelayed(() -> {
            int userId = mUserController.getCurrentUserId();
            if (!isThemeOverlayReady(userId)) {
                Slog.d(TAG,
                        "ThemeHomeDelay: ThemeOverlayController not responding, launching "
                                + "Home after " + HOME_LAUNCH_TIMEOUT_MS + "ms"
                                + " with user " + userId);
                setThemeOverlayReady(userId);
            }
        }, HOME_LAUNCH_TIMEOUT_MS);

    }

    /**
     * Used by ThemeOverlayController to notify when color
     * palette is ready.
     *
     * @param userId The ID of the user where ThemeOverlayController is ready.
     * @hide
     */
    @Override
    public void setThemeOverlayReady(@UserIdInt int userId) {
        if (!isHomeLaunchDelayable()) {
            Slog.d(TAG, "ThemeHomeDelay: Home launch is not delayable, "
                    + "ignoring setThemeOverlayReady() call");
            return;
        }

        enforceCallingPermission(Manifest.permission.SET_THEME_OVERLAY_CONTROLLER_READY,
                "setThemeOverlayReady");
        Slog.d(TAG, "ThemeHomeDelay: userId " + userId
                + " notified ThemeOverlayController completeness");
        boolean updateUser;
        synchronized (mThemeOverlayReadyUsers) {
            updateUser = mThemeOverlayReadyUsers.add(userId);
            Slog.d(TAG, "ThemeHomeDelay: updateUser " + userId + " isUpdatable: " + updateUser);
        }

        if (updateUser) {
            Slog.d(TAG, "ThemeHomeDelay: updating user " + userId);
            mAtmInternal.startHomeOnAllDisplays(userId, "setThemeOverlayReady");
        }
    }

    /**
     * Returns current state of ThemeOverlayController color
     * palette readiness.
     *
     * @hide
     */
    public boolean isThemeOverlayReady(int userId) {
        synchronized (mThemeOverlayReadyUsers) {
            return mThemeOverlayReadyUsers.contains(userId);
        }
    }

    /** Checks whether the home launch delay feature is enabled. */
    private boolean isHomeLaunchDelayable() {
        // This feature is disabled on Auto since it seems to add an unacceptably long boot delay
        // without even solving the underlying issue (it merely hits the timeout).
        return enableHomeDelay() &&
                !mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
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

    /**
     * @deprecated Use {@link #getIntentSenderWithFeature} instead
     */
    @Deprecated
    @Override
    public IIntentSender getIntentSender(int type,
            String packageName, IBinder token, String resultWho,
            int requestCode, Intent[] intents, String[] resolvedTypes,
            int flags, Bundle bOptions, int userId) {
        return getIntentSenderWithFeature(type, packageName, null, token, resultWho, requestCode,
                intents, resolvedTypes, flags, bOptions, userId);
    }

    @Override
    public IIntentSender getIntentSenderWithFeature(int type, String packageName, String featureId,
            IBinder token, String resultWho, int requestCode, Intent[] intents,
            String[] resolvedTypes, int flags, Bundle bOptions, int userId) {
        enforceNotIsolatedCaller("getIntentSender");

        return getIntentSenderWithFeatureAsApp(type, packageName, featureId, token, resultWho,
                requestCode, intents, resolvedTypes, flags, bOptions, userId,
                Binder.getCallingUid());
    }

    /**
     * System-internal callers can invoke this with owningUid being the app's own identity
     * rather than the public API's behavior of always assigning ownership to the actual
     * caller identity.  This will create an IntentSender as though the package/userid/uid app
     * were the caller, so that the ultimate PendingIntent is triggered with only the app's
     * capabilities and not the system's.  Used in cases like notification groups where
     * the OS must synthesize a PendingIntent on an app's behalf.
     */
    public IIntentSender getIntentSenderWithFeatureAsApp(int type, String packageName,
            String featureId, IBinder token, String resultWho, int requestCode, Intent[] intents,
            String[] resolvedTypes, int flags, Bundle bOptions, int userId, int owningUid) {
        // NOTE: The service lock isn't held in this method because nothing in the method requires
        // the service lock to be held.

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
                    boolean isActivityResultType =
                            type == ActivityManager.INTENT_SENDER_ACTIVITY_RESULT;
                    if (PendingIntent.isNewMutableDisallowedImplicitPendingIntent(flags, intent,
                            isActivityResultType)) {
                        boolean isChangeEnabled = CompatChanges.isChangeEnabled(
                                        PendingIntent.BLOCK_MUTABLE_IMPLICIT_PENDING_INTENT,
                                        packageName, UserHandle.of(userId));
                        String resolvedType = resolvedTypes == null
                                || i >= resolvedTypes.length ? null : resolvedTypes[i];
                        ActivityManagerUtils.logUnsafeIntentEvent(
                                UNSAFE_INTENT_EVENT_REPORTED__EVENT_TYPE__NEW_MUTABLE_IMPLICIT_PENDING_INTENT_RETRIEVED,
                                owningUid, intent, resolvedType, isChangeEnabled);
                        if (isChangeEnabled) {
                            String msg = packageName + ": Targeting U+ (version "
                                    + Build.VERSION_CODES.UPSIDE_DOWN_CAKE + " and above) disallows"
                                    + " creating or retrieving a PendingIntent with FLAG_MUTABLE,"
                                    + " an implicit Intent within and without FLAG_NO_CREATE and"
                                    + " FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT for"
                                    + " security reasons. To retrieve an already existing"
                                    + " PendingIntent, use FLAG_NO_CREATE, however, to create a"
                                    + " new PendingIntent with an implicit Intent use"
                                    + " FLAG_IMMUTABLE.";
                            throw new IllegalArgumentException(msg);
                        }
                    }
                    intents[i] = new Intent(intent);
                    intents[i].removeExtendedFlags(Intent.EXTENDED_FLAG_FILTER_MISMATCH);
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

        int origUserId = userId;
        userId = mUserController.handleIncomingUser(Binder.getCallingPid(), owningUid, userId,
                type == ActivityManager.INTENT_SENDER_BROADCAST,
                ALLOW_NON_FULL, "getIntentSender", null);
        if (origUserId == UserHandle.USER_CURRENT) {
            // We don't want to evaluate this until the pending intent is
            // actually executed.  However, we do want to always do the
            // security checking for it above.
            userId = UserHandle.USER_CURRENT;
        }

        if (owningUid != 0 && owningUid != SYSTEM_UID) {
            if (!getPackageManagerInternal().isSameApp(
                    packageName,
                    MATCH_DEBUG_TRIAGED_MISSING,
                    owningUid,
                    UserHandle.getUserId(owningUid))) {
                String msg = "Permission Denial: getIntentSender() from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + owningUid
                        + " is not allowed to send as package " + packageName;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }
        }

        if (type == ActivityManager.INTENT_SENDER_ACTIVITY_RESULT) {
            return mAtmInternal.getIntentSender(type, packageName, featureId, owningUid,
                    userId, token, resultWho, requestCode, intents, resolvedTypes, flags,
                    bOptions);
        }
        return mPendingIntentController.getIntentSender(type, packageName, featureId,
                owningUid, userId, token, resultWho, requestCode, intents, resolvedTypes,
                flags, bOptions);
    }

    @Override
    public int sendIntentSender(IApplicationThread caller, IIntentSender target,
            IBinder allowlistToken, int code, Intent intent, String resolvedType,
            IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
        if (target instanceof PendingIntentRecord) {
            final PendingIntentRecord originalRecord = (PendingIntentRecord) target;

            // In multi-display scenarios, there can be background users who execute the
            // PendingIntent. In these scenarios, we don't want to use the foreground user as the
            // current user.
            final PendingIntentRecord.Key originalKey = originalRecord.key;
            final UserManagerInternal umInternal =
                    LocalServices.getService(UserManagerInternal.class);
            final int callingUserId = UserHandle.getCallingUserId();
            if (UserManager.isVisibleBackgroundUsersEnabled()
                    && originalKey.userId == UserHandle.USER_CURRENT
                    && callingUserId != UserHandle.USER_SYSTEM
                    && umInternal.isUserVisible(callingUserId)) {
                EventLogTags.writeAmIntentSenderRedirectUser(callingUserId);
                final PendingIntentRecord.Key key = new PendingIntentRecord.Key(originalKey.type,
                        originalKey.packageName, originalKey.featureId, originalKey.activity,
                        originalKey.who, originalKey.requestCode, originalKey.allIntents,
                        originalKey.allResolvedTypes, originalKey.flags, originalKey.options,
                        callingUserId);

                final PendingIntentRecord newRecord = new PendingIntentRecord(
                        originalRecord.controller, key, originalRecord.uid);

                return newRecord.sendWithResult(caller, code, intent, resolvedType, allowlistToken,
                        finishedReceiver, requiredPermission, options);
            }

            return originalRecord.sendWithResult(caller, code, intent, resolvedType, allowlistToken,
                    finishedReceiver, requiredPermission, options);
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
                if (allowlistToken != null) {
                    final int callingUid = Binder.getCallingUid();
                    final String packageName;
                    final long token = Binder.clearCallingIdentity();
                    try {
                        packageName = AppGlobals.getPackageManager().getNameForUid(callingUid);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                    Slog.wtf(TAG, "Send a non-null allowlistToken to a non-PI target."
                            + " Calling package: " + packageName + "; intent: " + intent
                            + "; options: " + options);
                }
                target.send(code, intent, resolvedType, null, null,
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
    public boolean registerIntentSenderCancelListenerEx(
            IIntentSender sender, IResultReceiver receiver) {
        return mPendingIntentController.registerIntentSenderCancelListener(sender, receiver);
    }

    @Override
    public void unregisterIntentSenderCancelListener(IIntentSender sender,
            IResultReceiver receiver) {
        mPendingIntentController.unregisterIntentSenderCancelListener(sender, receiver);
    }

    @Override
    public PendingIntentInfo getInfoForIntentSender(IIntentSender sender) {
        if (sender instanceof PendingIntentRecord) {
            final PendingIntentRecord res = (PendingIntentRecord) sender;
            final String packageName = res.key.packageName;
            final int uid = res.uid;
            final boolean shouldFilter = getPackageManagerInternal().filterAppAccess(
                    packageName, Binder.getCallingUid(), UserHandle.getUserId(uid));
            return new PendingIntentInfo(
                    shouldFilter ? null : packageName,
                    shouldFilter ? INVALID_UID : uid,
                    (res.key.flags & PendingIntent.FLAG_IMMUTABLE) != 0,
                    res.key.type);
        } else {
            return new PendingIntentInfo(null, INVALID_UID, false,
                    ActivityManager.INTENT_SENDER_UNKNOWN);
        }
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
    public ParceledListSlice<ResolveInfo> queryIntentComponentsForIntentSender(
            IIntentSender pendingResult, int matchFlags) {
        enforceCallingPermission(Manifest.permission.GET_INTENT_SENDER_INTENT,
                "queryIntentComponentsForIntentSender()");
        Objects.requireNonNull(pendingResult);
        final PendingIntentRecord res;
        try {
            res = (PendingIntentRecord) pendingResult;
        } catch (ClassCastException e) {
            return null;
        }
        final Intent intent = res.key.requestIntent;
        if (intent == null) {
            return null;
        }
        final int userId = res.key.userId;
        final int uid = res.uid;
        final String resolvedType = res.key.requestResolvedType;
        switch (res.key.type) {
            case ActivityManager.INTENT_SENDER_ACTIVITY:
                return new ParceledListSlice<>(mPackageManagerInt.queryIntentActivities(
                        intent, resolvedType, matchFlags, uid, userId));
            case ActivityManager.INTENT_SENDER_SERVICE:
            case ActivityManager.INTENT_SENDER_FOREGROUND_SERVICE:
                return new ParceledListSlice<>(mPackageManagerInt.queryIntentServices(
                        intent, matchFlags, uid, userId));
            case ActivityManager.INTENT_SENDER_BROADCAST:
                return new ParceledListSlice<>(mPackageManagerInt.queryIntentReceivers(
                        intent, resolvedType, matchFlags, uid, userId, false));
            default: // ActivityManager.INTENT_SENDER_ACTIVITY_RESULT
                throw new IllegalStateException("Unsupported intent sender type: " + res.key.type);
        }
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
            trimApplicationsLocked(true, OOM_ADJ_REASON_PROCESS_END);
        }
    }

    @Override
    public int getProcessLimit() {
        synchronized (this) {
            return mConstants.getOverrideMaxCachedProcesses();
        }
    }

    void importanceTokenDied(ImportanceToken token) {
        synchronized (ActivityManagerService.this) {
            ProcessRecord pr = null;
            synchronized (mPidsSelfLocked) {
                ImportanceToken cur
                    = mImportantProcesses.get(token.pid);
                if (cur != token) {
                    return;
                }
                mImportantProcesses.remove(token.pid);
                pr = mPidsSelfLocked.get(token.pid);
                if (pr == null) {
                    return;
                }
                pr.mState.setForcingToImportant(null);
                clearProcessForegroundLocked(pr);
            }
            updateOomAdjLocked(pr, OOM_ADJ_REASON_UI_VISIBILITY);
        }
    }

    @Override
    public void setProcessImportant(IBinder token, int pid, boolean isForeground, String reason) {
        enforceCallingPermission(android.Manifest.permission.SET_PROCESS_LIMIT,
                "setProcessImportant()");
        synchronized(this) {
            boolean changed = false;

            ProcessRecord pr = null;
            synchronized (mPidsSelfLocked) {
                pr = mPidsSelfLocked.get(pid);
                if (pr == null && isForeground) {
                    Slog.w(TAG, "setProcessForeground called on unknown pid: " + pid);
                    return;
                }
                ImportanceToken oldToken = mImportantProcesses.get(pid);
                if (oldToken != null) {
                    oldToken.token.unlinkToDeath(oldToken, 0);
                    mImportantProcesses.remove(pid);
                    if (pr != null) {
                        pr.mState.setForcingToImportant(null);
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
                        pr.mState.setForcingToImportant(newToken);
                        changed = true;
                    } catch (RemoteException e) {
                        // If the process died while doing this, we will later
                        // do the cleanup with the process death link.
                    }
                }
            }

            if (changed) {
                updateOomAdjLocked(pr, OOM_ADJ_REASON_UI_VISIBILITY);
            }
        }
    }

    private boolean isAppForeground(int uid) {
        synchronized (mProcLock) {
            UidRecord uidRec = mProcessList.mActiveUids.get(uid);
            if (uidRec == null || uidRec.isIdle()) {
                return false;
            }
            return uidRec.getCurProcState() <= PROCESS_STATE_IMPORTANT_FOREGROUND;
        }
    }

    private boolean isAppBad(final String processName, final int uid) {
        return mAppErrors.isBadProcess(processName, uid);
    }

    // NOTE: this is an internal method used by the OnShellCommand implementation only and should
    // be guarded by permission checking.
    int getUidState(int uid) {
        synchronized (mProcLock) {
            return mProcessList.getUidProcStateLOSP(uid);
        }
    }

    @GuardedBy("this")
    int getUidStateLocked(int uid) {
        return mProcessList.getUidProcStateLOSP(uid);
    }

    @GuardedBy("this")
    int getUidProcessCapabilityLocked(int uid) {
        return mProcessList.getUidProcessCapabilityLOSP(uid);
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

        synchronized (mProcLock) {
            synchronized (mPidsSelfLocked) {
                int newestTimeIndex = -1;
                long newestTime = Long.MIN_VALUE;
                for (int i = 0; i < pids.length; i++) {
                    ProcessRecord pr = mPidsSelfLocked.get(pids[i]);
                    if (pr != null) {
                        final long pendingTopTime =
                                mPendingStartActivityUids.getPendingTopPidTime(pr.uid, pids[i]);
                        if (pendingTopTime != PendingStartActivityUids.INVALID_TIME) {
                            // The uid in mPendingStartActivityUids gets the TOP process state.
                            states[i] = PROCESS_STATE_TOP;
                            if (scores != null) {
                                // The uid in mPendingStartActivityUids gets a better score.
                                scores[i] = ProcessList.FOREGROUND_APP_ADJ - 1;
                            }
                            if (pendingTopTime > newestTime) {
                                newestTimeIndex = i;
                                newestTime = pendingTopTime;
                            }
                        } else {
                            states[i] = pr.mState.getCurProcState();
                            if (scores != null) {
                                scores[i] = pr.mState.getCurAdj();
                            }
                        }
                    } else {
                        states[i] = PROCESS_STATE_NONEXISTENT;
                        if (scores != null) {
                            scores[i] = ProcessList.INVALID_ADJ;
                        }
                    }
                }
                // The uid with the newest timestamp in mPendingStartActivityUids gets the best
                // score.
                if (newestTimeIndex != -1) {
                    if (scores != null) {
                        scores[newestTimeIndex] = ProcessList.FOREGROUND_APP_ADJ - 2;
                    }
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
            // TODO moltmann: Allow to specify featureId
            return mActivityManagerService.mAppOpsService
                    .noteOperation(AppOpsManager.strOpToOp(op), uid, packageName, null,
                            false, "", false).getOpMode();
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

    /**
     * Allows if {@code pid} is {@link #MY_PID}, then denies if the {@code pid} has been denied
     * provided non-{@code null} {@code permission} before. Otherwise calls into
     * {@link ActivityManager#checkComponentPermission(String, int, int, int, boolean)}.
     */
    @PackageManager.PermissionResult
    @PermissionMethod
    public static int checkComponentPermission(@PermissionName String permission, int pid, int uid,
            int owningUid, boolean exported) {
        return checkComponentPermission(permission, pid, uid, Context.DEVICE_ID_DEFAULT,
                owningUid, exported);
    }

    /**
     * Allows if {@code pid} is {@link #MY_PID}, then denies if the {@code pid} has been denied
     * provided non-{@code null} {@code permission} before. Otherwise calls into
     * {@link ActivityManager#checkComponentPermission(String, int, int, int, boolean)}.
     */
    @PackageManager.PermissionResult
    @PermissionMethod
    public static int checkComponentPermission(@PermissionName String permission, int pid, int uid,
            int deviceId, int owningUid, boolean exported) {
        if (pid == MY_PID) {
            return PackageManager.PERMISSION_GRANTED;
        }
        // If there is an explicit permission being checked, and this is coming from a process
        // that has been denied access to that permission, then just deny.  Ultimately this may
        // not be quite right -- it means that even if the caller would have access for another
        // reason (such as being the owner of the component it is trying to access), it would still
        // fail.  This also means the system and root uids would be able to deny themselves
        // access to permissions, which...  well okay. ¯\_(ツ)_/¯
        if (permission != null) {
            synchronized (sActiveProcessInfoSelfLocked) {
                ProcessInfo procInfo = sActiveProcessInfoSelfLocked.get(pid);
                if (procInfo != null && procInfo.deniedPermissions != null
                        && procInfo.deniedPermissions.contains(permission)) {
                    return PackageManager.PERMISSION_DENIED;
                }
            }
        }
        return ActivityManager.checkComponentPermission(permission, uid, deviceId,
                owningUid, exported);
    }

    private void enforceDebuggable(ProcessRecord proc) {
        if (!Build.IS_DEBUGGABLE && !proc.isDebuggable()) {
            throw new SecurityException("Process not debuggable: " + proc.info.packageName);
        }
    }

    private void enforceDebuggable(ApplicationInfo info) {
        if (!Build.IS_DEBUGGABLE && (info.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            throw new SecurityException("Process not debuggable: " + info.packageName);
        }
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
    @PackageManager.PermissionResult
    @PermissionMethod
    public int checkPermission(@PermissionName String permission, int pid, int uid) {
        return checkPermissionForDevice(permission, pid, uid, Context.DEVICE_ID_DEFAULT);
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
    @PackageManager.PermissionResult
    @PermissionMethod
    public int checkPermissionForDevice(@PermissionName String permission, int pid, int uid,
            int deviceId) {
        if (permission == null) {
            return PackageManager.PERMISSION_DENIED;
        }
        return checkComponentPermission(permission, pid, uid, deviceId, -1, true);
    }

    /**
     * Binder IPC calls go through the public entry point.
     * This can be called with or without the global lock held.
     */
    @PackageManager.PermissionResult
    @PermissionMethod
    int checkCallingPermission(@PermissionName String permission) {
        return checkPermission(permission,
                Binder.getCallingPid(),
                Binder.getCallingUid());
    }

    /**
     * This can be called with or without the global lock held.
     */
    @PermissionMethod
    void enforceCallingPermission(@PermissionName String permission, String func) {
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
    @PermissionMethod(anyOf = true)
    private void enforceCallingHasAtLeastOnePermission(String func, String... permissions) {
        for (String permission : permissions) {
            if (checkCallingPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        String msg = "Permission Denial: " + func + " from pid="
                + Binder.getCallingPid()
                + ", uid=" + Binder.getCallingUid()
                + " requires one of " + Arrays.toString(permissions);
        Slog.w(TAG, msg);
        throw new SecurityException(msg);
    }

    /**
     * This can be called with or without the global lock held.
     */
    @PermissionMethod
    void enforcePermission(@PermissionName String permission, int pid, int uid, String func) {
        if (checkPermission(permission, pid, uid) == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        String msg = "Permission Denial: " + func + " from pid=" + pid + ", uid=" + uid
                + " requires " + permission;
        Slog.w(TAG, msg);
        throw new SecurityException(msg);
    }

    public boolean isAppStartModeDisabled(int uid, String packageName) {
        synchronized (mProcLock) {
            return getAppStartModeLOSP(uid, packageName, 0, -1, false, true, false)
                    == ActivityManager.APP_START_MODE_DISABLED;
        }
    }

    private boolean isInRestrictedBucket(int userId, String packageName, long nowElapsed) {
        return UsageStatsManager.STANDBY_BUCKET_RESTRICTED
                <= mUsageStatsService.getAppStandbyBucket(packageName, userId, nowElapsed);
    }

    // Unified app-op and target sdk check
    @GuardedBy(anyOf = {"this", "mProcLock"})
    int appRestrictedInBackgroundLOSP(int uid, String packageName, int packageTargetSdk) {
        // Apps that target O+ are always subject to background check
        if (packageTargetSdk >= Build.VERSION_CODES.O) {
            if (DEBUG_BACKGROUND_CHECK) {
                Slog.i(TAG, "App " + uid + "/" + packageName + " targets O+, restricted");
            }
            return ActivityManager.APP_START_MODE_DELAYED_RIGID;
        }
        // It's a legacy app. If it's in the RESTRICTED bucket, always restrict on battery.
        if (mOnBattery // Short-circuit in common case.
                && mConstants.FORCE_BACKGROUND_CHECK_ON_RESTRICTED_APPS
                && isInRestrictedBucket(
                        UserHandle.getUserId(uid), packageName, SystemClock.elapsedRealtime())) {
            if (DEBUG_BACKGROUND_CHECK) {
                Slog.i(TAG, "Legacy app " + uid + "/" + packageName + " in RESTRICTED bucket");
            }
            return ActivityManager.APP_START_MODE_DELAYED;
        }
        // Not in the RESTRICTED bucket so policy is based on AppOp check.
        int appop = getAppOpsManager().noteOpNoThrow(AppOpsManager.OP_RUN_IN_BACKGROUND,
                uid, packageName, null, "");
        if (DEBUG_BACKGROUND_CHECK) {
            Slog.i(TAG, "Legacy app " + uid + "/" + packageName + " bg appop " + appop);
        }
        switch (appop) {
            case AppOpsManager.MODE_ALLOWED:
                // If force-background-check is enabled, restrict all apps that aren't allowlisted.
                if (mForceBackgroundCheck &&
                        !UserHandle.isCore(uid) &&
                        !isOnDeviceIdleAllowlistLOSP(uid, /*allowExceptIdleToo=*/ true)) {
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
    @GuardedBy(anyOf = {"this", "mProcLock"})
    int appServicesRestrictedInBackgroundLOSP(int uid, String packageName, int packageTargetSdk) {
        // Persistent app?
        if (mPackageManagerInt.isPackagePersistent(packageName)) {
            if (DEBUG_BACKGROUND_CHECK) {
                Slog.i(TAG, "App " + uid + "/" + packageName
                        + " is persistent; not restricted in background");
            }
            return ActivityManager.APP_START_MODE_NORMAL;
        }

        // Non-persistent but background whitelisted?
        if (uidOnBackgroundAllowlistLOSP(uid)) {
            if (DEBUG_BACKGROUND_CHECK) {
                Slog.i(TAG, "App " + uid + "/" + packageName
                        + " on background allowlist; not restricted in background");
            }
            return ActivityManager.APP_START_MODE_NORMAL;
        }

        // Is this app on the battery whitelist?
        if (isOnDeviceIdleAllowlistLOSP(uid, /*allowExceptIdleToo=*/ false)) {
            if (DEBUG_BACKGROUND_CHECK) {
                Slog.i(TAG, "App " + uid + "/" + packageName
                        + " on idle allowlist; not restricted in background");
            }
            return ActivityManager.APP_START_MODE_NORMAL;
        }

        // None of the service-policy criteria apply, so we apply the common criteria
        return appRestrictedInBackgroundLOSP(uid, packageName, packageTargetSdk);
    }

    @GuardedBy(anyOf = {"this", "mProcLock"})
    int getAppStartModeLOSP(int uid, String packageName, int packageTargetSdk,
            int callingPid, boolean alwaysRestrict, boolean disabledOnly, boolean forcedStandby) {
        if (mInternal.isPendingTopUid(uid)) {
            return ActivityManager.APP_START_MODE_NORMAL;
        }
        UidRecord uidRec = mProcessList.getUidRecordLOSP(uid);
        if (DEBUG_BACKGROUND_CHECK) Slog.d(TAG, "checkAllowBackground: uid=" + uid + " pkg="
                + packageName + " rec=" + uidRec + " always=" + alwaysRestrict + " idle="
                + (uidRec != null ? uidRec.isIdle() : false));
        if (uidRec == null || alwaysRestrict || forcedStandby || uidRec.isIdle()) {
            boolean ephemeral;
            if (uidRec == null) {
                ephemeral = getPackageManagerInternal().isPackageEphemeral(
                        UserHandle.getUserId(uid), packageName);
            } else {
                ephemeral = uidRec.isEphemeral();
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
                        ? appRestrictedInBackgroundLOSP(uid, packageName, packageTargetSdk)
                        : appServicesRestrictedInBackgroundLOSP(uid, packageName,
                                packageTargetSdk);
                if (DEBUG_BACKGROUND_CHECK) {
                    Slog.d(TAG, "checkAllowBackground: uid=" + uid
                            + " pkg=" + packageName + " startMode=" + startMode
                            + " onallowlist=" + isOnDeviceIdleAllowlistLOSP(uid, false)
                            + " onallowlist(ei)=" + isOnDeviceIdleAllowlistLOSP(uid, true));
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
                        if (proc != null && !ActivityManager.isProcStateBackground(
                                proc.mState.getCurProcState())) {
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
     * @return whether a UID is in the system, user or temp doze allowlist.
     */
    @GuardedBy(anyOf = {"this", "mProcLock"})
    boolean isOnDeviceIdleAllowlistLOSP(int uid, boolean allowExceptIdleToo) {
        final int appId = UserHandle.getAppId(uid);

        final int[] allowlist = allowExceptIdleToo
                ? mDeviceIdleExceptIdleAllowlist
                : mDeviceIdleAllowlist;

        return Arrays.binarySearch(allowlist, appId) >= 0
                || Arrays.binarySearch(mDeviceIdleTempAllowlist, appId) >= 0
                || mPendingTempAllowlist.get(uid) != null;
    }

    /**
     * Is the uid allowlisted to start FGS?
     * @param uid
     * @return a TempAllowListEntry if the uid is allowed.
     *         null if the uid is not allowed.
     */
    @Nullable
    @GuardedBy(anyOf = {"this", "mProcLock"})
    FgsTempAllowListItem isAllowlistedForFgsStartLOSP(int uid) {
        if (Arrays.binarySearch(mDeviceIdleExceptIdleAllowlist, UserHandle.getAppId(uid)) >= 0) {
            return FAKE_TEMP_ALLOW_LIST_ITEM;
        }
        final Pair<Long, FgsTempAllowListItem> entry = mFgsStartTempAllowList.get(uid);
        return entry == null ? null : entry.second;
    }

    private static class GetBackgroundStartPrivilegesFunctor implements Consumer<ProcessRecord> {
        private BackgroundStartPrivileges mBackgroundStartPrivileges =
                BackgroundStartPrivileges.NONE;
        private int mUid;

        void prepare(int uid) {
            mUid = uid;
            mBackgroundStartPrivileges = BackgroundStartPrivileges.NONE;
        }

        @NonNull
        BackgroundStartPrivileges getResult() {
            return mBackgroundStartPrivileges;
        }

        public void accept(ProcessRecord pr) {
            if (pr.uid == mUid) {
                mBackgroundStartPrivileges =
                        mBackgroundStartPrivileges.merge(pr.getBackgroundStartPrivileges());
            }
        }
    }

    private final GetBackgroundStartPrivilegesFunctor mGetBackgroundStartPrivilegesFunctor =
            new GetBackgroundStartPrivilegesFunctor();

    /**
     * Returns the current complete {@link BackgroundStartPrivileges} of the UID.
     */
    @NonNull
    private BackgroundStartPrivileges getBackgroundStartPrivileges(int uid) {
        synchronized (mProcLock) {
            final UidRecord uidRecord = mProcessList.getUidRecordLOSP(uid);
            if (uidRecord == null) {
                return BackgroundStartPrivileges.NONE;
            }
            mGetBackgroundStartPrivilegesFunctor.prepare(uid);
            uidRecord.forEachProcess(mGetBackgroundStartPrivilegesFunctor);
            return mGetBackgroundStartPrivilegesFunctor.getResult();
        }
    }

    /**
     * Returns true if the reasonCode is included in the base set of reasons an app may be
     * allowed to schedule a
     * {@link android.app.job.JobInfo.Builder#setUserInitiated(boolean) user-initiated job}.
     * This is a shortcut and <b>DOES NOT</b> include all reasons.
     * Use {@link #canScheduleUserInitiatedJobs(int, int, String)} to cover all cases.
     */
    static boolean doesReasonCodeAllowSchedulingUserInitiatedJobs(int reasonCode, int uid) {
        switch (reasonCode) {
            case REASON_PROC_STATE_PERSISTENT:
            case REASON_PROC_STATE_PERSISTENT_UI:
            case REASON_PROC_STATE_TOP:
            case REASON_PROC_STATE_BTOP:
            case REASON_UID_VISIBLE:
            case REASON_SYSTEM_UID:
            case REASON_START_ACTIVITY_FLAG:
            case REASON_ACTIVITY_VISIBILITY_GRACE_PERIOD:
            case REASON_COMPANION_DEVICE_MANAGER:
            case REASON_BACKGROUND_ACTIVITY_PERMISSION:
            case REASON_INSTR_BACKGROUND_ACTIVITY_PERMISSION:
                return true;
            case REASON_SYSTEM_ALERT_WINDOW_PERMISSION:
                if (!Flags.fgsDisableSaw()
                        || !CompatChanges.isChangeEnabled(FGS_SAW_RESTRICTIONS, uid)) {
                    return true;
                } else {
                    // With the new SAW restrictions starting Android V, only allow the app to
                    // schedule a user-initiated job if it's currently showing an overlay window
                    // in additional to holding the permission - this additional logic will be
                    // checked in #canScheduleUserInitiatedJobs(int, int, String) below since this
                    // method is simply a shortcut for checking based on the reason codes.
                }
        }
        return false;
    }

    /**
     * Returns true if the ProcessRecord has some conditions that allow the app to schedule a
     * {@link android.app.job.JobInfo.Builder#setUserInitiated(boolean) user-initiated job}.
     * This is a shortcut and <b>DOES NOT</b> include all reasons.
     * Use {@link #canScheduleUserInitiatedJobs(int, int, String)} to cover all cases.
     */
    @GuardedBy(anyOf = {"this", "mProcLock"})
    private boolean isProcessInStateToScheduleUserInitiatedJobsLocked(
            @Nullable ProcessRecord pr, long nowElapsed, int uid) {
        if (pr == null) {
            return false;
        }

        final BackgroundStartPrivileges backgroundStartPrivileges =
                pr.getBackgroundStartPrivileges();
        // Is the allow activity background start flag on?
        if (backgroundStartPrivileges.allowsBackgroundActivityStarts()) {
            // REASON_START_ACTIVITY_FLAG;
            return true;
        }

        final ProcessStateRecord state = pr.mState;
        final int procstate = state.getCurProcState();
        if (procstate <= PROCESS_STATE_BOUND_TOP) {
            if (doesReasonCodeAllowSchedulingUserInitiatedJobs(
                    getReasonCodeFromProcState(procstate), uid)) {
                return true;
            }
        }

        final long lastInvisibleTime = state.getLastInvisibleTime();
        if (lastInvisibleTime > 0 && lastInvisibleTime < Long.MAX_VALUE) {
            final long timeSinceVisibleMs = nowElapsed - lastInvisibleTime;
            if (timeSinceVisibleMs < mConstants.mVisibleToInvisibleUijScheduleGraceDurationMs) {
                // REASON_ACTIVITY_VISIBILITY_GRACE_PERIOD
                return true;
            }
        }

        final ProcessServiceRecord psr = pr.mServices;
        if (psr != null && psr.hasForegroundServices()) {
            for (int s = psr.numberOfRunningServices() - 1; s >= 0; --s) {
                final ServiceRecord sr = psr.getRunningServiceAt(s);
                if (sr.isForeground && sr.mAllowUiJobScheduling) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Returns whether the app in question is in a state where we allow scheduling a
     * {@link android.app.job.JobInfo.Builder#setUserInitiated(boolean) user-initiated job}.
     */
    // TODO(262260570): log allow reason to an atom
    boolean canScheduleUserInitiatedJobs(int uid, int pid, String pkgName) {
        synchronized (this) {
            final ProcessRecord processRecord;
            synchronized (mPidsSelfLocked) {
                processRecord = mPidsSelfLocked.get(pid);
            }

            final long nowElapsed = SystemClock.elapsedRealtime();
            final BackgroundStartPrivileges backgroundStartPrivileges;
            if (processRecord != null) {
                if (isProcessInStateToScheduleUserInitiatedJobsLocked(
                        processRecord, nowElapsed, uid)) {
                    return true;
                }
                backgroundStartPrivileges = processRecord.getBackgroundStartPrivileges();
            } else {
                backgroundStartPrivileges = getBackgroundStartPrivileges(uid);
            }
            // Is the allow activity background start flag on?
            if (backgroundStartPrivileges.allowsBackgroundActivityStarts()) {
                // REASON_START_ACTIVITY_FLAG;
                return true;
            }

            // We allow scheduling a user-initiated job when the app is in the TOP or a
            // Background Activity Launch approved state. These are cases that indicate the user
            // has interacted with the app and therefore it is reasonable to believe the app may
            // attempt to schedule a user-initiated job in response to the user interaction.
            // As of Android UDC, the conditions required to grant a while-in-use permission
            // covers the majority of those cases, and so we piggyback on that logic as the base.
            // Missing cases are added after.
            if (mServices.canAllowWhileInUsePermissionInFgsLocked(
                    pid, uid, pkgName, processRecord, backgroundStartPrivileges)) {
                return true;
            }

            final UidRecord uidRecord = mProcessList.getUidRecordLOSP(uid);
            final boolean hasSawPermission = mAtmInternal.hasSystemAlertWindowPermission(uid, pid,
                                                            pkgName);
            final boolean strictSawCheckEnabled = Flags.fgsDisableSaw()
                            && CompatChanges.isChangeEnabled(FGS_SAW_RESTRICTIONS, uid);
            if (uidRecord != null) {
                for (int i = uidRecord.getNumOfProcs() - 1; i >= 0; --i) {
                    ProcessRecord pr = uidRecord.getProcessRecordByIndex(i);
                    if (isProcessInStateToScheduleUserInitiatedJobsLocked(pr, nowElapsed, uid)) {
                        return true;
                    } else if (hasSawPermission && strictSawCheckEnabled) {
                        // isProcessInStateToScheduleUserInitiatedJobsLocked() doesn't do a strict
                        // check for the SAW permission which is enabled from V onwards, so perform
                        // that here (pre-V versions will be checked in the conditional below)
                        // Starting Android V, only allow the app to schedule a user-initiated job
                        // if it's granted the permission and currently showing an overlay window
                        if (pr != null && pr.mState.hasOverlayUi()) {
                            return true;
                        }
                    }
                }
            }

            if (hasSawPermission && !strictSawCheckEnabled) {
                // REASON_SYSTEM_ALERT_WINDOW_PERMISSION (pre-V)
                return true;
            }

            final int userId = UserHandle.getUserId(uid);
            final boolean isCompanionApp = mInternal.isAssociatedCompanionApp(userId, uid);
            if (isCompanionApp) {
                if (checkPermission(REQUEST_COMPANION_RUN_IN_BACKGROUND, pid, uid)
                        == PERMISSION_GRANTED) {
                    // REASON_COMPANION_DEVICE_MANAGER;
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * @return allowlist tag for a uid from mPendingTempAllowlist, null if not currently on
     * the allowlist
     */
    @GuardedBy(anyOf = {"this", "mProcLock"})
    String getPendingTempAllowlistTagForUidLOSP(int uid) {
        final PendingTempAllowlist ptw = mPendingTempAllowlist.get(uid);
        return ptw != null ? ptw.tag : null;
    }

    @VisibleForTesting
    public void grantImplicitAccess(int userId, Intent intent, int visibleUid, int recipientAppId) {
        getPackageManagerInternal()
                .grantImplicitAccess(userId, intent, recipientAppId, visibleUid, true /*direct*/);
    }

    /**
     * @param uri This uri must NOT contain an embedded userId.
     * @param userId The userId in which the uri is to be resolved.
     */
    @Override
    public int checkUriPermission(Uri uri, int pid, int uid,
            final int modeFlags, int userId, IBinder callerToken) {
        return checkUriPermission(uri, pid, uid, modeFlags, userId,
                /* isFullAccessForContentUri */ false, "checkUriPermission");
    }

    /**
     * @param uri This uri must NOT contain an embedded userId.
     * @param userId The userId in which the uri is to be resolved.
     */
    @Override
    public int checkContentUriPermissionFull(Uri uri, int pid, int uid,
            final int modeFlags, int userId) {
        return checkUriPermission(uri, pid, uid, modeFlags, userId,
                /* isFullAccessForContentUri */ true, "checkContentUriPermissionFull");
    }

    private int checkUriPermission(Uri uri, int pid, int uid,
            final int modeFlags, int userId, boolean isFullAccessForContentUri, String methodName) {
        enforceNotIsolatedCaller(methodName);

        // Our own process gets to do everything.
        if (pid == MY_PID) {
            return PackageManager.PERMISSION_GRANTED;
        }
        if (uid != ROOT_UID) { // bypass the root
            if (mPackageManagerInt.filterAppAccess(uid, Binder.getCallingUid())) {
                return PackageManager.PERMISSION_DENIED;
            }
        }
        boolean granted = mUgmInternal.checkUriPermission(new GrantUri(userId, uri, modeFlags), uid,
                modeFlags, isFullAccessForContentUri);

        return granted ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
    }

    @Override
    public int[] checkUriPermissions(@NonNull List<Uri> uris, int pid, int uid,
            final int modeFlags, int userId, IBinder callerToken) {
        final int size = uris.size();
        int[] res = new int[size];
        // Default value DENIED.
        Arrays.fill(res, PackageManager.PERMISSION_DENIED);

        for (int i = 0; i < size; i++) {
            final Uri uri = uris.get(i);
            final int userIdFromUri = ContentProvider.getUserIdFromUri(uri, userId);
            res[i] = checkUriPermission(ContentProvider.getUriWithoutUserId(uri), pid, uid,
                    modeFlags, userIdFromUri, callerToken);
        }
        return res;
    }

    /**
     * @param uri This uri must NOT contain an embedded userId.
     * @param userId The userId in which the uri is to be resolved.
     */
    @Override
    public void grantUriPermission(IApplicationThread caller, String targetPkg, Uri uri,
            final int modeFlags, int userId) {
        enforceNotIsolatedCaller("grantUriPermission");
        GrantUri grantUri = new GrantUri(userId, uri, modeFlags);
        synchronized (this) {
            final ProcessRecord r = getRecordForAppLOSP(caller);
            if (r == null) {
                throw new SecurityException("Unable to find app for caller "
                        + caller
                        + " when granting permission to uri " + grantUri);
            }
            if (targetPkg == null) {
                throw new IllegalArgumentException("null target");
            }
            final int callingUserId = UserHandle.getUserId(r.uid);
            if (mPackageManagerInt.filterAppAccess(targetPkg, r.uid, callingUserId)) {
                return;
            }

            Preconditions.checkFlagsArgument(modeFlags, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);

            final Intent intent = new Intent();
            intent.setData(ContentProvider.maybeAddUserId(uri, userId));
            intent.setFlags(modeFlags);

            final NeededUriGrants needed = mUgmInternal.checkGrantUriPermissionFromIntent(intent,
                    r.uid, targetPkg, callingUserId);
            mUgmInternal.grantUriPermissionUncheckedFromIntent(needed, null);
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
        synchronized (this) {
            final ProcessRecord r = getRecordForAppLOSP(caller);
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
            final ProviderInfo pi = mCpHelper.getProviderInfoLocked(authority, userId,
                    MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE);
            if (pi == null) {
                Slog.w(TAG, "No content provider found for permission revoke: "
                        + uri.toSafeString());
                return;
            }

            mUgmInternal.revokeUriPermission(targetPackage, r.uid,
                    new GrantUri(userId, uri, modeFlags), modeFlags);
        }
    }

    @Override
    public void showWaitingForDebugger(IApplicationThread who, boolean waiting) {
        synchronized (mProcLock) {
            final ProcessRecord app = who != null ? getRecordForAppLOSP(who) : null;
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
    public void cancelTaskWindowTransition(int taskId) {
        mActivityTaskManager.cancelTaskWindowTransition(taskId);
    }

    @Override
    public void setTaskResizeable(int taskId, int resizeableMode) {
        mActivityTaskManager.setTaskResizeable(taskId, resizeableMode);
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
    public boolean removeTask(int taskId) {
        return mActivityTaskManager.removeTask(taskId);
    }

    @Override
    public void moveTaskToFront(IApplicationThread appThread, String callingPackage, int taskId,
            int flags, Bundle bOptions) {
        mActivityTaskManager.moveTaskToFront(appThread, callingPackage, taskId, flags, bOptions);
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
        return ActivityClient.getInstance().moveActivityTaskToBack(token, nonRoot);
    }

    @Override
    public void moveTaskToRootTask(int taskId, int rootTaskId, boolean toTop) {
        mActivityTaskManager.moveTaskToRootTask(taskId, rootTaskId, toTop);
    }

    @Override
    public ParceledListSlice<ActivityManager.RecentTaskInfo> getRecentTasks(int maxNum, int flags,
            int userId) {
        return mActivityTaskManager.getRecentTasks(maxNum, flags, userId);
    }

    @Override
    public List<RootTaskInfo> getAllRootTaskInfos() {
        return mActivityTaskManager.getAllRootTaskInfos();
    }

    @Override
    public int getTaskForActivity(IBinder token, boolean onlyRoot) {
        return ActivityClient.getInstance().getTaskForActivity(token, onlyRoot);
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
    public PackageManagerInternal getPackageManagerInternal() {
        // Intentionally hold no locks: in case of race conditions, the mPackageManagerInt will
        // be set to the same value anyway.
        if (mPackageManagerInt == null) {
            mPackageManagerInt = LocalServices.getService(PackageManagerInternal.class);
        }
        return mPackageManagerInt;
    }

    private PermissionManagerServiceInternal getPermissionManagerInternal() {
        // Intentionally hold no locks: in case of race conditions, the mPermissionManagerInt will
        // be set to the same value anyway.
        if (mPermissionManagerInt == null) {
            mPermissionManagerInt =
                    LocalServices.getService(PermissionManagerServiceInternal.class);
        }
        return mPermissionManagerInt;
    }

    private AccessCheckDelegateHelper getAccessCheckDelegateHelper() {
        // Intentionally hold no locks: in case of race conditions, the mPermissionManagerInt will
        // be set to the same value anyway.
        if (mAccessCheckDelegateHelper == null) {
            mAccessCheckDelegateHelper = new AccessCheckDelegateHelper(mProcLock,
                    mActiveInstrumentation, mAppOpsService, getPermissionManagerInternal());
        }
        return mAccessCheckDelegateHelper;
    }

    /** Returns whether the given package was ever launched since install */
    boolean wasPackageEverLaunched(String packageName, @UserIdInt int userId) {
        boolean wasLaunched = false;
        try {
            wasLaunched = getPackageManagerInternal().wasPackageEverLaunched(packageName, userId);
        } catch (Exception e) {
            // If the package state record doesn't exist yet, assume it was never launched
        }
        return wasLaunched;
    }

    private TestUtilityService getTestUtilityServiceLocked() {
        if (mTestUtilityService == null) {
            mTestUtilityService =
                    LocalServices.getService(TestUtilityService.class);
        }
        return mTestUtilityService;
    }

    @Override
    public void appNotResponding(final String reason) {
        appNotResponding(reason, /*isContinuousAnr*/ false);
    }

    public void appNotResponding(final String reason, boolean isContinuousAnr) {
        TimeoutRecord timeoutRecord = TimeoutRecord.forApp("App requested: " + reason);
        final int callingPid = Binder.getCallingPid();

        timeoutRecord.mLatencyTracker.waitingOnPidLockStarted();
        synchronized (mPidsSelfLocked) {
            timeoutRecord.mLatencyTracker.waitingOnPidLockEnded();
            final ProcessRecord app = mPidsSelfLocked.get(callingPid);
            if (app == null) {
                throw new SecurityException("Unknown process: " + callingPid);
            }

            mAnrHelper.appNotResponding(app, null, app.info, null, null, false,
                    timeoutRecord, isContinuousAnr);
        }
    }

    void appNotResponding(@NonNull ProcessRecord anrProcess, @NonNull TimeoutRecord timeoutRecord) {
        mAnrHelper.appNotResponding(anrProcess, timeoutRecord);
    }

    private void appNotResponding(@NonNull String processName, int uid,
            @NonNull TimeoutRecord timeoutRecord) {
        Objects.requireNonNull(processName);
        Objects.requireNonNull(timeoutRecord);

        synchronized (this) {
            final ProcessRecord app = getProcessRecordLocked(processName, uid);
            if (app == null) {
                Slog.e(TAG, "Unknown process: " + processName);
                return;
            }
            mAnrHelper.appNotResponding(app, timeoutRecord);
        }
    }

    void startPersistentApps(int matchFlags) {
        if (mFactoryTest == FactoryTest.FACTORY_TEST_LOW_LEVEL) return;

        synchronized (this) {
            try {
                final List<ApplicationInfo> apps = AppGlobals.getPackageManager()
                        .getPersistentApplications(STOCK_PM_FLAGS | matchFlags).getList();
                for (ApplicationInfo app : apps) {
                    if (!"android".equals(app.packageName)) {
                        final ProcessRecord proc = addAppLocked(
                                app, null, false, null /* ABI override */,
                                ZYGOTE_POLICY_FLAG_BATCH_LAUNCH);
                        if (proc != null) {
                            proc.mProfile.addHostingComponentType(
                                    HOSTING_COMPONENT_TYPE_PERSISTENT);
                        }
                    }
                }
            } catch (RemoteException ex) {
            }
        }
    }

    // =========================================================
    // CONTENT PROVIDERS
    // =========================================================

    public ContentProviderHelper getContentProviderHelper() {
        return mCpHelper;
    }

    @Override
    public final ContentProviderHolder getContentProvider(
            IApplicationThread caller, String callingPackage, String name, int userId,
            boolean stable) {
        traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "getContentProvider: ", name);
        try {
            return mCpHelper.getContentProvider(caller, callingPackage, name, userId, stable);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    @Override
    public ContentProviderHolder getContentProviderExternal(
            String name, int userId, IBinder token, String tag) {
        traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "getContentProviderExternal: ", name);
        try {
            return mCpHelper.getContentProviderExternal(name, userId, token, tag);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    /**
     * Drop a content provider from a ProcessRecord's bookkeeping
     */
    @Override
    public void removeContentProvider(IBinder connection, boolean stable) {
        mCpHelper.removeContentProvider(connection, stable);
    }

    /** @deprecated - Use {@link #removeContentProviderExternalAsUser} which takes a user ID. */
    @Deprecated
    @Override
    public void removeContentProviderExternal(String name, IBinder token) {
        traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "removeContentProviderExternal: ", name);
        try {
            removeContentProviderExternalAsUser(name, token, UserHandle.getCallingUserId());
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    @Override
    public void removeContentProviderExternalAsUser(String name, IBinder token, int userId) {
        traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "removeContentProviderExternalAsUser: ", name);
        try {
            mCpHelper.removeContentProviderExternalAsUser(name, token, userId);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    @Override
    public final void publishContentProviders(IApplicationThread caller,
            List<ContentProviderHolder> providers) {
        if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
            final int maxLength = 256;
            final StringBuilder sb = new StringBuilder(maxLength);
            sb.append("publishContentProviders: ");
            if (providers != null) {
                boolean first = true;
                for (int i = 0, size = providers.size(); i < size; i++) {
                    final ContentProviderHolder holder = providers.get(i);
                    if (holder != null && holder.info != null && holder.info.authority != null) {
                        final int len = holder.info.authority.length();
                        if (sb.length() + len > maxLength) {
                            sb.append("[[TRUNCATED]]");
                            break;
                        }
                        if (!first) {
                            sb.append(';');
                        } else {
                            first = false;
                        }
                        sb.append(holder.info.authority);
                    }
                }
            }
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, sb.toString());
        }
        try {
            mCpHelper.publishContentProviders(caller, providers);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    @Override
    public boolean refContentProvider(IBinder connection, int stable, int unstable) {
        return mCpHelper.refContentProvider(connection, stable, unstable);
    }

    @Override
    public void unstableProviderDied(IBinder connection) {
        mCpHelper.unstableProviderDied(connection);
    }

    @Override
    public void appNotRespondingViaProvider(IBinder connection) {
        mCpHelper.appNotRespondingViaProvider(connection);
    }

    /**
     * Filters calls to getType based on permission. If the caller has required permission,
     * then it returns the contentProvider#getType.
     * Else, it returns the contentProvider#getTypeAnonymous, which does not
     * reveal any internal information which should be protected by any permission.
     */
    @Override
    public void getMimeTypeFilterAsync(Uri uri, int userId, RemoteCallback resultCallback) {
        mCpHelper.getMimeTypeFilterAsync(uri, userId, resultCallback);
    }

    // =========================================================
    // GLOBAL MANAGEMENT
    // =========================================================

    @GuardedBy(anyOf = {"this", "mProcLock"})
    private boolean uidOnBackgroundAllowlistLOSP(final int uid) {
        final int appId = UserHandle.getAppId(uid);
        final int[] allowlist = mBackgroundAppIdAllowlist;
        for (int i = 0, len = allowlist.length; i < len; i++) {
            if (appId == allowlist[i]) {
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

    @VisibleForTesting
    public boolean isBackgroundRestrictedNoCheck(final int uid, final String packageName) {
        final int mode = getAppOpsManager().checkOpNoThrow(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,
                uid, packageName);
        return mode != AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void backgroundAllowlistUid(final int uid) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only the OS may call backgroundAllowlistUid()");
        }

        if (DEBUG_BACKGROUND_CHECK) {
            Slog.i(TAG, "Adding uid " + uid + " to bg uid allowlist");
        }
        synchronized (this) {
            synchronized (mProcLock) {
                final int num = mBackgroundAppIdAllowlist.length;
                int[] newList = new int[num + 1];
                System.arraycopy(mBackgroundAppIdAllowlist, 0, newList, 0, num);
                newList[num] = UserHandle.getAppId(uid);
                mBackgroundAppIdAllowlist = newList;
            }
        }
    }

    @GuardedBy("this")
    final ProcessRecord addAppLocked(ApplicationInfo info, String customProcess, boolean isolated,
            String abiOverride, int zygotePolicyFlags) {
        return addAppLocked(info, customProcess, isolated, false /* disableHiddenApiChecks */,
                abiOverride, zygotePolicyFlags);
    }

    @GuardedBy("this")
    final ProcessRecord addAppLocked(ApplicationInfo info, String customProcess, boolean isolated,
            boolean disableHiddenApiChecks, String abiOverride, int zygotePolicyFlags) {
        return addAppLocked(info, customProcess, isolated, disableHiddenApiChecks,
                false /* disableTestApiChecks */, abiOverride, zygotePolicyFlags);
    }

    // TODO: Move to ProcessList?
    @GuardedBy("this")
    final ProcessRecord addAppLocked(ApplicationInfo info, String customProcess, boolean isolated,
            boolean disableHiddenApiChecks, boolean disableTestApiChecks,
            String abiOverride, int zygotePolicyFlags) {
        return addAppLocked(
                info,
                customProcess,
                isolated,
                /* isSdkSandbox= */ false,
                /* sdkSandboxUid= */ 0,
                /* sdkSandboxClientAppPackage= */ null,
                disableHiddenApiChecks,
                disableTestApiChecks,
                abiOverride,
                zygotePolicyFlags);
    }

    final ProcessRecord addAppLocked(
            ApplicationInfo info,
            String customProcess,
            boolean isolated,
            boolean isSdkSandbox,
            int sdkSandboxUid,
            @Nullable String sdkSandboxClientAppPackage,
            boolean disableHiddenApiChecks,
            boolean disableTestApiChecks,
            String abiOverride,
            int zygotePolicyFlags) {
        ProcessRecord app;
        if (!isolated) {
            app = getProcessRecordLocked(customProcess != null ? customProcess : info.processName,
                    info.uid);
        } else {
            app = null;
        }

        if (app == null) {
            app = mProcessList.newProcessRecordLocked(
                    info,
                    customProcess,
                    isolated,
                    /* isolatedUid= */0,
                    isSdkSandbox,
                    sdkSandboxUid,
                    sdkSandboxClientAppPackage,
                    new HostingRecord(HostingRecord.HOSTING_TYPE_ADDED_APPLICATION,
                            customProcess != null ? customProcess : info.processName));
            updateLruProcessLocked(app, false, null);
            updateOomAdjLocked(app, OOM_ADJ_REASON_PROCESS_BEGIN);
        }

        // Report usage as process is persistent and being started.
        mUsageStatsService.reportEvent(info.packageName, UserHandle.getUserId(app.uid),
                Event.APP_COMPONENT_USED);

        // This package really, really can not be stopped.
        // TODO: how set package stopped state should work for sdk sandboxes?
        if (!isSdkSandbox) {
            try {
                mPackageManagerInt.setPackageStoppedState(
                        info.packageName, false, UserHandle.getUserId(app.uid));
            } catch (IllegalArgumentException e) {
                Slog.w(TAG, "Failed trying to unstop package "
                        + info.packageName + ": " + e);
            }
        }

        if ((info.flags & PERSISTENT_MASK) == PERSISTENT_MASK) {
            app.setPersistent(true);
            app.mState.setMaxAdj(ProcessList.PERSISTENT_PROC_ADJ);
        }
        if (app.getThread() == null && mPersistentStartingProcesses.indexOf(app) < 0) {
            mPersistentStartingProcesses.add(app);
            mProcessList.startProcessLocked(app, new HostingRecord(
                    HostingRecord.HOSTING_TYPE_ADDED_APPLICATION,
                    customProcess != null ? customProcess : app.processName),
                    zygotePolicyFlags, disableHiddenApiChecks, disableTestApiChecks,
                    abiOverride);
        }

        return app;
    }

    public void unhandledBack() {
        mActivityTaskManager.unhandledBack();
    }

    // TODO: Replace this method with one that returns a bound IContentProvider.
    public ParcelFileDescriptor openContentUri(String uriString) throws RemoteException {
        enforceNotIsolatedCaller("openContentUri");
        final int userId = UserHandle.getCallingUserId();
        final Uri uri = Uri.parse(uriString);
        String name = uri.getAuthority();
        ContentProviderHolder cph = mCpHelper.getContentProviderExternalUnchecked(name, null,
                Binder.getCallingUid(), "*opencontent*", userId);
        ParcelFileDescriptor pfd = null;
        if (cph != null) {
            try {
                // This method is exposed to the VNDK and to avoid changing its
                // signature we just use the first package in the UID. For shared
                // UIDs we may blame the wrong app but that is Okay as they are
                // in the same security/privacy sandbox.
                final int uid = Binder.getCallingUid();
                // Here we handle some of the special UIDs (mediaserver, systemserver, etc)
                final String packageName = AppOpsManager.resolvePackageName(uid,
                        /*packageName*/ null);
                final AndroidPackage androidPackage;
                if (packageName != null) {
                    androidPackage = mPackageManagerInt.getPackage(packageName);
                } else {
                    androidPackage = mPackageManagerInt.getPackage(uid);
                }
                if (androidPackage == null) {
                    Log.e(TAG, "Cannot find package for uid: " + uid);
                    return null;
                }

                final ApplicationInfo appInfo = mPackageManagerInt.getApplicationInfo(
                        androidPackage.getPackageName(), /*flags*/0, Process.SYSTEM_UID,
                        UserHandle.USER_SYSTEM);
                if (!appInfo.isVendor() && !appInfo.isSystemApp() && !appInfo.isSystemExt()
                        && !appInfo.isProduct()) {
                    Log.e(TAG, "openContentUri may only be used by vendor/system/product.");
                    return null;
                }

                final AttributionSource attributionSource = new AttributionSource(
                        Binder.getCallingUid(), androidPackage.getPackageName(), null);
                pfd = cph.provider.openFile(attributionSource, uri, "r", null);
            } catch (FileNotFoundException e) {
                // do nothing; pfd will be returned null
            } finally {
                // Ensure we're done with the provider.
                mCpHelper.removeContentProviderExternalUnchecked(name, null, userId);
            }
        } else {
            Slog.d(TAG, "Failed to get provider for authority '" + name + "'");
        }
        return pfd;
    }

    void reportGlobalUsageEvent(int event) {
        final int currentUserId = mUserController.getCurrentUserId();
        mUsageStatsService.reportEvent(Event.DEVICE_EVENT_PACKAGE_NAME, currentUserId, event);
        int[] profiles = mUserController.getCurrentProfileIds();
        if (profiles != null) {
            for (int i = profiles.length - 1; i >= 0; i--) {
                if (profiles[i] == currentUserId) {
                    continue;
                }
                mUsageStatsService.reportEvent(Event.DEVICE_EVENT_PACKAGE_NAME, profiles[i], event);
            }
        }
    }

    void reportCurWakefulnessUsageEvent() {
        reportGlobalUsageEvent(mWakefulness.get() == PowerManagerInternal.WAKEFULNESS_AWAKE
                ? UsageEvents.Event.SCREEN_INTERACTIVE
                : UsageEvents.Event.SCREEN_NON_INTERACTIVE);
    }

    void onWakefulnessChanged(int wakefulness) {
        synchronized (this) {
            boolean wasAwake = mWakefulness.getAndSet(wakefulness)
                    == PowerManagerInternal.WAKEFULNESS_AWAKE;
            boolean isAwake = wakefulness == PowerManagerInternal.WAKEFULNESS_AWAKE;

            if (wasAwake != isAwake) {
                // Also update state in a special way for running foreground services UI.
                mServices.updateScreenStateLocked(isAwake);
                reportCurWakefulnessUsageEvent();
                mActivityTaskManager.onScreenAwakeChanged(isAwake);
                mOomAdjuster.onWakefulnessChanged(wakefulness);

                updateOomAdjLocked(OOM_ADJ_REASON_UI_VISIBILITY);
            }
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
        mProcessStats.shutdown();

        return timedout;
    }

    @Override
    public void notifyLockedProfile(@UserIdInt int userId) {
        mAtmInternal.notifyLockedProfile(userId);
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
        setDebugApp(packageName, waitForDebugger, persistent, false);
    }

    private void setDebugApp(String packageName, boolean waitForDebugger,
            boolean persistent, boolean suspendUponWait) {
        enforceCallingPermission(android.Manifest.permission.SET_DEBUG_APP,
                "setDebugApp()");

        final long ident = Binder.clearCallingIdentity();
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
                mSuspendUponWait = suspendUponWait;
                mDebugTransient = !persistent;
                if (packageName != null) {
                    forceStopPackageLocked(packageName, -1, false, false, true, true,
                            false, false, UserHandle.USER_ALL, "set debug app");
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
        // note: hijacking SET_ACTIVITY_WATCHER, but should be changed to
        // its own permission.
        if (checkCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires permission " + android.Manifest.permission.SET_ACTIVITY_WATCHER);
        }

        synchronized (mAppProfiler.mProfilerLock) {
            mAppProfiler.setAgentAppLPf(packageName, agent);
        }
    }

    void setTrackAllocationApp(ApplicationInfo app, String processName) {
        enforceDebuggable(app);

        synchronized (mProcLock) {
            mTrackAllocationApp = processName;
        }
    }

    void setProfileApp(ApplicationInfo app, String processName, ProfilerInfo profilerInfo,
            ApplicationInfo sdkSandboxClientApp) {
        synchronized (mAppProfiler.mProfilerLock) {
            if (!Build.IS_DEBUGGABLE) {
                boolean isAppDebuggable = (app.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                boolean isAppProfileable = app.isProfileableByShell();

                if (sdkSandboxClientApp != null) {
                    isAppDebuggable |=
                            (sdkSandboxClientApp.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                    isAppProfileable |= sdkSandboxClientApp.isProfileableByShell();
                }
                if (!isAppDebuggable && !isAppProfileable) {
                    throw new SecurityException("Process not debuggable, "
                            + "and not profileable by shell: " + app.packageName);
                }
            }
            mAppProfiler.setProfileAppLPf(processName, profilerInfo);
        }
    }

    void setNativeDebuggingAppLocked(ApplicationInfo app, String processName) {
        enforceDebuggable(app);
        mNativeDebuggingApp = processName;
    }

    @Override
    public void setAlwaysFinish(boolean enabled) {
        enforceCallingPermission(android.Manifest.permission.SET_ALWAYS_FINISH,
                "setAlwaysFinish()");

        final long ident = Binder.clearCallingIdentity();
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
        if (controller != null) {
            Binder.allowBlocking(controller.asBinder());
        }
        mActivityTaskManager.setActivityController(controller, imAMonkey);
    }

    @Override
    public void setUserIsMonkey(boolean userIsMonkey) {
        synchronized (mProcLock) {
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
        synchronized (mProcLock) {
            // If there is a controller also implies the user is a monkey.
            return mUserIsMonkey || mActivityTaskManager.isControllerAMonkey();
        }
    }

    @Override
    public void requestSystemServerHeapDump() {
        if (!Build.IS_DEBUGGABLE) {
            Slog.wtf(TAG, "requestSystemServerHeapDump called on a user build");
            return;
        }
        if (Binder.getCallingUid() != SYSTEM_UID) {
            // This also intentionally excludes secondary profiles from calling this.
            throw new SecurityException(
                    "Only the system process is allowed to request a system heap dump");
        }
        ProcessRecord pr;
        synchronized (mPidsSelfLocked) {
            pr = mPidsSelfLocked.get(myPid());
        }
        if (pr == null) {
            Slog.w(TAG, "system process not in mPidsSelfLocked: " + myPid());
            return;
        }
        synchronized (mAppProfiler.mProfilerLock) {
            mAppProfiler.startHeapDumpLPf(pr.mProfile, true);
        }
    }

    /**
     * Takes a bugreport using bug report API ({@code BugreportManager}) with no pre-set
     * title and description
     */
    @Override
    public void requestBugReport(@BugreportParams.BugreportMode int bugreportType) {
        requestBugReportWithDescription(null, null, bugreportType, 0L);
    }

    /**
     * Takes a bugreport using bug report API ({@code BugreportManager}) which gets
     * triggered by sending a broadcast to Shell.
     */
    @Override
    public void requestBugReportWithDescription(@Nullable String shareTitle,
            @Nullable String shareDescription, int bugreportType) {
        requestBugReportWithDescription(shareTitle, shareDescription, bugreportType, /*nonce*/ 0L);
    }

    /**
     * Takes a bugreport using bug report API ({@code BugreportManager}) which gets
     * triggered by sending a broadcast to Shell.
     */
    public void requestBugReportWithDescription(@Nullable String shareTitle,
            @Nullable String shareDescription, int bugreportType, long nonce) {
        requestBugReportWithDescription(shareTitle, shareDescription, bugreportType, nonce, null);
    }

    /**
     * Takes a bugreport using bug report API ({@code BugreportManager}) which gets
     * triggered by sending a broadcast to Shell. Optionally adds an extra attachment.
     */
    public void requestBugReportWithDescription(@Nullable String shareTitle,
            @Nullable String shareDescription, int bugreportType, long nonce,
            @Nullable Uri extraAttachment) {
        String type = null;
        switch (bugreportType) {
            case BugreportParams.BUGREPORT_MODE_FULL:
                type = "bugreportfull";
                break;
            case BugreportParams.BUGREPORT_MODE_INTERACTIVE:
                type = "bugreportplus";
                break;
            case BugreportParams.BUGREPORT_MODE_REMOTE:
                type = "bugreportremote";
                break;
            case BugreportParams.BUGREPORT_MODE_WEAR:
                type = "bugreportwear";
                break;
            case BugreportParams.BUGREPORT_MODE_TELEPHONY:
                type = "bugreporttelephony";
                break;
            case BugreportParams.BUGREPORT_MODE_WIFI:
                type = "bugreportwifi";
                break;
            case BugreportParams.BUGREPORT_MODE_ONBOARDING:
                type = "bugreportonboarding";
                break;
            default:
                throw new IllegalArgumentException(
                    "Provided bugreport type is not correct, value: "
                        + bugreportType);
        }
        // Always log caller, even if it does not have permission to dump.
        Slog.i(TAG, type + " requested by UID " + Binder.getCallingUid());
        enforceCallingPermission(android.Manifest.permission.DUMP, "requestBugReport");

        if (!TextUtils.isEmpty(shareTitle)) {
            if (shareTitle.length() > MAX_BUGREPORT_TITLE_SIZE) {
                String errorStr = "shareTitle should be less than "
                        + MAX_BUGREPORT_TITLE_SIZE + " characters";
                throw new IllegalArgumentException(errorStr);
            }
            if (!TextUtils.isEmpty(shareDescription)) {
                if (shareDescription.length() > MAX_BUGREPORT_DESCRIPTION_SIZE) {
                    String errorStr = "shareDescription should be less than "
                            + MAX_BUGREPORT_DESCRIPTION_SIZE + " characters";
                    throw new IllegalArgumentException(errorStr);
                }
            }
            Slog.d(TAG, "Bugreport notification title " + shareTitle
                    + " description " + shareDescription);
        }
        // Create intent to trigger Bugreport API via Shell
        Intent triggerShellBugreport = new Intent();
        triggerShellBugreport.setAction(INTENT_BUGREPORT_REQUESTED);
        triggerShellBugreport.setPackage(SHELL_APP_PACKAGE);
        triggerShellBugreport.putExtra(EXTRA_BUGREPORT_TYPE, bugreportType);
        triggerShellBugreport.putExtra(EXTRA_BUGREPORT_NONCE, nonce);
        if (extraAttachment != null) {
            triggerShellBugreport.putExtra(EXTRA_EXTRA_ATTACHMENT_URI, extraAttachment);
            triggerShellBugreport.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        triggerShellBugreport.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        triggerShellBugreport.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        if (shareTitle != null) {
            triggerShellBugreport.putExtra(EXTRA_TITLE, shareTitle);
        }
        if (shareDescription != null) {
            triggerShellBugreport.putExtra(EXTRA_DESCRIPTION, shareDescription);
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            // Send broadcast to shell to trigger bugreport using Bugreport API
            // Always start the shell process on the current user to ensure that
            // the foreground user can see all bugreport notifications.
            // In case of BUGREPORT_MODE_REMOTE send the broadcast to SYSTEM user as the device
            // owner apps are running on the SYSTEM user.
            if (bugreportType == BugreportParams.BUGREPORT_MODE_REMOTE) {
                mContext.sendBroadcastAsUser(triggerShellBugreport, UserHandle.SYSTEM);
            } else {
                mContext.sendBroadcastAsUser(triggerShellBugreport,
                        getCurrentUser().getUserHandle());
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Takes a telephony bugreport with title and description
     */
    @Override
    public void requestTelephonyBugReport(String shareTitle, String shareDescription) {
        requestBugReportWithDescription(shareTitle, shareDescription,
                BugreportParams.BUGREPORT_MODE_TELEPHONY);
    }

    /**
     * Takes a minimal bugreport of Wifi-related state with pre-set title and description
     */
    @Override
    public void requestWifiBugReport(String shareTitle, String shareDescription) {
        requestBugReportWithDescription(shareTitle, shareDescription,
                BugreportParams.BUGREPORT_MODE_WIFI);
    }

    /**
     * Takes an interactive bugreport with a progress notification
     */
    @Override
    public void requestInteractiveBugReport() {
        requestBugReportWithDescription(null, null, BugreportParams.BUGREPORT_MODE_INTERACTIVE);
    }

    /**
     * Takes an interactive bugreport with a progress notification. Also attaches given file uri.
     */
    @Override
    public void requestBugReportWithExtraAttachment(@NonNull Uri extraAttachment) {
        requestBugReportWithDescription(null, null, BugreportParams.BUGREPORT_MODE_INTERACTIVE, 0L,
                extraAttachment);
    }

    /**
     * Takes an interactive bugreport with a progress notification. Also, shows the given title and
     * description on the final share notification
     */
    @Override
    public void requestInteractiveBugReportWithDescription(String shareTitle,
            String shareDescription) {
        requestBugReportWithDescription(shareTitle, shareDescription,
                BugreportParams.BUGREPORT_MODE_INTERACTIVE);
    }

    /**
     * Takes a bugreport with minimal user interference
     */
    @Override
    public void requestFullBugReport() {
        requestBugReportWithDescription(null, null,  BugreportParams.BUGREPORT_MODE_FULL);
    }

    /**
     * Takes a bugreport remotely
     */
    @Override
    public void requestRemoteBugReport(long nonce) {
        requestBugReportWithDescription(null, null, BugreportParams.BUGREPORT_MODE_REMOTE, nonce);
    }

    /**
     * Launches a bugreport-whitelisted app to handle a bugreport.
     *
     * <p>Allows a bug report handler app to take bugreports on the user's behalf. The handler can
     * be predefined in the config, meant to be launched with the primary user. The user can
     * override this with a different (or same) handler app on possibly a different user. This is
     * useful for capturing bug reports from work profile, for instance.
     *
     * @return true if there is a bugreport-whitelisted app to handle a bugreport, or false
     * otherwise.
     */
    @Override
    public boolean launchBugReportHandlerApp() {

        Context currentUserContext = mContext.createContextAsUser(getCurrentUser().getUserHandle(),
                /* flags= */ 0);
        if (!BugReportHandlerUtil.isBugReportHandlerEnabled(currentUserContext)) {
            return false;
        }

        // Always log caller, even if it does not have permission to dump.
        Slog.i(TAG, "launchBugReportHandlerApp requested by UID " + Binder.getCallingUid());
        enforceCallingPermission(android.Manifest.permission.DUMP,
                "launchBugReportHandlerApp");

        return BugReportHandlerUtil.launchBugReportHandlerApp(currentUserContext);
    }

    /**
     * Get packages of bugreport-whitelisted apps to handle a bug report.
     *
     * @return packages of bugreport-whitelisted apps to handle a bug report.
     */
    @Override
    public List<String> getBugreportWhitelistedPackages() {
        enforceCallingPermission(android.Manifest.permission.MANAGE_DEBUGGING,
                "getBugreportWhitelistedPackages");
        return new ArrayList<>(SystemConfig.getInstance().getBugreportWhitelistedPackages());
    }

    public void registerProcessObserver(IProcessObserver observer) {
        enforceCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER,
                "registerProcessObserver()");
        mProcessList.registerProcessObserver(observer);
    }

    @Override
    public void unregisterProcessObserver(IProcessObserver observer) {
        mProcessList.unregisterProcessObserver(observer);
    }

    @Override
    public int getUidProcessState(int uid, String callingPackage) {
        if (!hasUsageStatsPermission(callingPackage)) {
            enforceCallingPermission(android.Manifest.permission.PACKAGE_USAGE_STATS,
                    "getUidProcessState");
        }
        // In case the caller is requesting processState of an app in a different user,
        // then verify the caller has INTERACT_ACROSS_USERS_FULL permission
        mUserController.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                UserHandle.getUserId(uid), false /* allowAll */, ALLOW_FULL_ONLY,
                "getUidProcessState", callingPackage); // Ignore return value

        synchronized (mProcLock) {
            return getUidProcessStateInnerLOSP(uid);
        }
    }

    @Override
    public int getBindingUidProcessState(int targetUid, String callingPackage) {
        if (!hasUsageStatsPermission(callingPackage)) {
            enforceCallingPermission(android.Manifest.permission.GET_BINDING_UID_IMPORTANCE,
                    "getBindingUidProcessState");
        }
        // We don't need to do a cross-user check here (unlike getUidProcessState),
        // because we only allow to see UIDs that are actively communicating with the caller.

        final int callingUid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                final boolean allowed = (callingUid == targetUid)
                        || hasServiceBindingOrProviderUseLocked(callingUid, targetUid);
                if (!allowed) {
                    return PROCESS_STATE_NONEXISTENT;
                }
                return getUidProcessStateInnerLOSP(targetUid);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @GuardedBy(anyOf = {"this", "mProcLock"})
    private int getUidProcessStateInnerLOSP(int uid) {
        if (mPendingStartActivityUids.isPendingTopUid(uid)) {
            return PROCESS_STATE_TOP;
        }
        return mProcessList.getUidProcStateLOSP(uid);
    }

    /**
     * Ensure that {@code clientUid} has a bound service client to {@code callingUid}
     */
    @GuardedBy("this")
    private boolean hasServiceBindingOrProviderUseLocked(int callingUid, int clientUid) {
        // See if there's a service binding
        final Boolean hasBinding = mProcessList.searchEachLruProcessesLOSP(
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

                                    if (clientPr.uid == clientUid) {
                                        return Boolean.TRUE;
                                    }
                                }
                            }
                        }
                    }
                    return null;
                });
        if (Boolean.TRUE.equals(hasBinding)) {
            return true;
        }

        final Boolean hasProviderClient = mProcessList.searchEachLruProcessesLOSP(
                false, pr -> {
                    if (pr.uid == callingUid) {
                        final ProcessProviderRecord ppr = pr.mProviders;
                        for (int provi = ppr.numberOfProviders() - 1; provi >= 0; provi--) {
                            ContentProviderRecord cpr = ppr.getProviderAt(provi);

                            for (int i = cpr.connections.size() - 1; i >= 0; i--) {
                                ContentProviderConnection conn = cpr.connections.get(i);
                                ProcessRecord client = conn.client;
                                if (client.uid == clientUid) {
                                    return Boolean.TRUE;
                                }
                            }
                        }
                    }
                    return null;
                });

        return Boolean.TRUE.equals(hasProviderClient);
    }

    @Override
    public @ProcessCapability int getUidProcessCapabilities(int uid, String callingPackage) {
        if (!hasUsageStatsPermission(callingPackage)) {
            enforceCallingPermission(android.Manifest.permission.PACKAGE_USAGE_STATS,
                    "getUidProcessState");
        }
        // In case the caller is requesting processCapabilities of an app in a different user,
        // then verify the caller has INTERACT_ACROSS_USERS_FULL permission
        mUserController.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                UserHandle.getUserId(uid), false /* allowAll */, ALLOW_FULL_ONLY,
                "getUidProcessCapabilities", callingPackage); // Ignore return value

        synchronized (mProcLock) {
            return mProcessList.getUidProcessCapabilityLOSP(uid);
        }
    }

    @Override
    public void registerUidObserver(IUidObserver observer, int which, int cutpoint,
            String callingPackage) {
        registerUidObserverForUids(observer, which, cutpoint, callingPackage, null /* uids */);
    }

    /**
     * Registers a UidObserver with a uid filter.
     *
     * @param observer The UidObserver implementation to register.
     * @param which    A bitmask of events to observe. See ActivityManager.UID_OBSERVER_*.
     * @param cutpoint The cutpoint for onUidStateChanged events. When the state crosses this
     *                 threshold in either direction, onUidStateChanged will be called.
     * @param callingPackage The name of the calling package.
     * @param uids     A list of uids to watch. If all uids are to be watched, use
     *                 registerUidObserver instead.
     * @throws RemoteException
     * @return Returns A binder token identifying the UidObserver registration.
     */
    @Override
    public IBinder registerUidObserverForUids(IUidObserver observer, int which, int cutpoint,
            String callingPackage, int[] uids) {
        if (!hasUsageStatsPermission(callingPackage)) {
            enforceCallingPermission(android.Manifest.permission.PACKAGE_USAGE_STATS,
                    "registerUidObserver");
        }
        return mUidObserverController.register(observer, which, cutpoint, callingPackage,
                Binder.getCallingUid(), uids);
    }

    @Override
    public void unregisterUidObserver(IUidObserver observer) {
        mUidObserverController.unregister(observer);
    }

    /**
     * Adds a uid to the list of uids that a UidObserver will receive updates about.
     *
     * @param observerToken  The binder token identifying the UidObserver registration.
     * @param callingPackage The name of the calling package.
     * @param uid            The uid to watch.
     * @throws RemoteException
     */
    @Override
    public void addUidToObserver(IBinder observerToken, String callingPackage, int uid) {
        if (!hasUsageStatsPermission(callingPackage)) {
            enforceCallingPermission(android.Manifest.permission.PACKAGE_USAGE_STATS,
                    "registerUidObserver");
        }
        mUidObserverController.addUidToObserver(observerToken, uid);
    }

    /**
     * Removes a uid from the list of uids that a UidObserver will receive updates about.
     *
     * @param observerToken  The binder token identifying the UidObserver registration.
     * @param callingPackage The name of the calling package.
     * @param uid            The uid to stop watching.
     * @throws RemoteException
     */
    @Override
    public void removeUidFromObserver(IBinder observerToken, String callingPackage, int uid) {
        if (!hasUsageStatsPermission(callingPackage)) {
            enforceCallingPermission(android.Manifest.permission.PACKAGE_USAGE_STATS,
                    "registerUidObserver");
        }
        mUidObserverController.removeUidFromObserver(observerToken, uid);
    }

    @Override
    public boolean isUidActive(int uid, String callingPackage) {
        if (!hasUsageStatsPermission(callingPackage)) {
            enforceCallingPermission(android.Manifest.permission.PACKAGE_USAGE_STATS,
                    "isUidActive");
        }
        synchronized (mProcLock) {
            if (isUidActiveLOSP(uid)) {
                return true;
            }
        }
        return mInternal.isPendingTopUid(uid);
    }

    @GuardedBy(anyOf = {"this", "mProcLock"})
    boolean isUidActiveLOSP(int uid) {
        final UidRecord uidRecord = mProcessList.getUidRecordLOSP(uid);
        return uidRecord != null && !uidRecord.isSetIdle();
    }

    @Override
    public long getUidLastIdleElapsedTime(int uid, String callingPackage) {
        if (!hasUsageStatsPermission(callingPackage)) {
            enforceCallingPermission(android.Manifest.permission.PACKAGE_USAGE_STATS,
                    "getUidLastIdleElapsedTime");
        }
        synchronized (mProcLock) {
            final UidRecord uidRecord = mProcessList.getUidRecordLOSP(uid);
            return uidRecord != null ? uidRecord.getRealLastIdleTime() : 0;
        }
    }

    @GuardedBy("mUidFrozenStateChangedCallbackList")
    private final RemoteCallbackList<IUidFrozenStateChangedCallback>
            mUidFrozenStateChangedCallbackList = new RemoteCallbackList<>();

    /**
     * Register a {@link IUidFrozenStateChangedCallback} to receive Uid frozen state events.
     *
     * @param callback remote callback object to be registered
     */
    @RequiresPermission(Manifest.permission.PACKAGE_USAGE_STATS)
    @Override
    public void registerUidFrozenStateChangedCallback(
            @NonNull IUidFrozenStateChangedCallback callback) {
        Preconditions.checkNotNull(callback, "callback cannot be null");
        enforceCallingPermission(android.Manifest.permission.PACKAGE_USAGE_STATS,
                "registerUidFrozenStateChangedCallback()");
        synchronized (mUidFrozenStateChangedCallbackList) {
            final boolean registered = mUidFrozenStateChangedCallbackList.register(callback);
            if (!registered) {
                Slog.w(TAG, "Failed to register with RemoteCallbackList!");
            }
        }
    }

    /**
     * Unregister a {@link IUidFrozenStateChangedCallback}.
     *
     * @param callback remote callback object to be unregistered
     */
    @RequiresPermission(Manifest.permission.PACKAGE_USAGE_STATS)
    @Override
    public void unregisterUidFrozenStateChangedCallback(
            @NonNull IUidFrozenStateChangedCallback callback) {
        Preconditions.checkNotNull(callback, "callback cannot be null");
        enforceCallingPermission(android.Manifest.permission.PACKAGE_USAGE_STATS,
                "unregisterUidFrozenStateChangedCallback()");
        synchronized (mUidFrozenStateChangedCallbackList) {
            mUidFrozenStateChangedCallbackList.unregister(callback);
        }
    }

    /**
     * Query the frozen state of a list of UIDs.
     *
     * @param uids the array of UIDs which the client would like to know the frozen state of.
     * @return An array containing the frozen state for each requested UID, by index. Will be set
     *               to {@link UidFrozenStateChangedCallback#UID_FROZEN_STATE_FROZEN}
     *               if the UID is frozen. If the UID is not frozen or not found,
     *               {@link UidFrozenStateChangedCallback#UID_FROZEN_STATE_UNFROZEN}
     *               will be set.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.PACKAGE_USAGE_STATS)
    @Override
    public @NonNull int[] getUidFrozenState(@NonNull int[] uids) {
        Preconditions.checkNotNull(uids, "uid array cannot be null");
        enforceCallingPermission(android.Manifest.permission.PACKAGE_USAGE_STATS,
                "getUidFrozenState()");

        final int[] frozenStates = new int[uids.length];
        synchronized (mProcLock) {
            for (int i = 0; i < uids.length; i++) {
                final UidRecord uidRec = mProcessList.mActiveUids.get(uids[i]);
                if (uidRec != null && uidRec.areAllProcessesFrozen()) {
                    frozenStates[i] = UID_FROZEN_STATE_FROZEN;
                } else {
                    frozenStates[i] = UID_FROZEN_STATE_UNFROZEN;
                }
            }
        }
        return frozenStates;
    }

    /**
     * Notify the system that a UID has been frozen or unfrozen.
     *
     * @param uids The Uid(s) in question
     * @param frozenStates Frozen state for each UID index
     *
     * @hide
     */
    public void reportUidFrozenStateChanged(@NonNull int[] uids,
            @UidFrozenState int[] frozenStates) {
        synchronized (mUidFrozenStateChangedCallbackList) {
            final int n = mUidFrozenStateChangedCallbackList.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mUidFrozenStateChangedCallbackList.getBroadcastItem(i).onUidFrozenStateChanged(
                            uids, frozenStates);
                } catch (RemoteException e) {
                    /*
                    * The process at the other end has died or otherwise gone away.
                    * According to spec, RemoteCallbacklist will take care of unregistering any
                    * object associated with that process - we are safe to ignore the exception
                    * here.
                    */
                }
            }
            mUidFrozenStateChangedCallbackList.finishBroadcast();
        }
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

    /**
     * Switches the priority between SCHED_FIFO and SCHED_OTHER for the main thread and render
     * thread of the given process.
     */
    @GuardedBy("mProcLock")
    static void setFifoPriority(@NonNull ProcessRecord app, boolean enable) {
        final int pid = app.getPid();
        final int renderThreadTid = app.getRenderThreadTid();
        if (enable) {
            scheduleAsFifoPriority(pid, true /* suppressLogs */);
            if (renderThreadTid != 0) {
                scheduleAsFifoPriority(renderThreadTid, true /* suppressLogs */);
            }
        } else {
            scheduleAsRegularPriority(pid, true /* suppressLogs */);
            if (renderThreadTid != 0) {
                scheduleAsRegularPriority(renderThreadTid, true /* suppressLogs */);
            }
        }
    }

    @Override
    public void setRenderThread(int tid) {
        synchronized (mProcLock) {
            ProcessRecord proc;
            int pid = Binder.getCallingPid();
            if (pid == Process.myPid()) {
                demoteSystemServerRenderThread(tid);
                return;
            }
            synchronized (mPidsSelfLocked) {
                proc = mPidsSelfLocked.get(pid);
            }
            if (proc != null && proc.getRenderThreadTid() == 0 && tid > 0) {
                // ensure the tid belongs to the process
                if (!isThreadInProcess(pid, tid)) {
                    throw new IllegalArgumentException(
                            "Render thread does not belong to process");
                }
                proc.setRenderThreadTid(tid);
                if (DEBUG_OOM_ADJ) {
                    Slog.d("UI_FIFO", "Set RenderThread tid " + tid + " for pid " + pid);
                }
                // promote to FIFO now
                if (proc.mState.getCurrentSchedulingGroup() == ProcessList.SCHED_GROUP_TOP_APP) {
                    if (DEBUG_OOM_ADJ) Slog.d("UI_FIFO", "Promoting " + tid + "out of band");
                    if (proc.useFifoUiScheduling()) {
                        setThreadScheduler(proc.getRenderThreadTid(),
                                SCHED_FIFO | SCHED_RESET_ON_FORK, 1);
                    } else {
                        setThreadPriority(proc.getRenderThreadTid(), THREAD_PRIORITY_TOP_APP_BOOST);
                    }
                }
            } else {
                if (DEBUG_OOM_ADJ) {
                    Slog.d("UI_FIFO", "Didn't set thread from setRenderThread? "
                            + "PID: " + pid + ", TID: " + tid + " FIFO: " + mUseFifoUiScheduling);
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
        return ActivityClient.getInstance().isTopOfTask(token);
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
                    if (pr.mState.hasTopUi() != hasTopUi) {
                        if (DEBUG_OOM_ADJ) {
                            Slog.d(TAG, "Setting hasTopUi=" + hasTopUi + " for pid=" + pid);
                        }
                        pr.mState.setHasTopUi(hasTopUi);
                        changed = true;
                    }
                }
                if (changed) {
                    updateOomAdjLocked(pr, OOM_ADJ_REASON_UI_VISIBILITY);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
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
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
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

        int standbyBucket = 0;

        mBatteryStatsService.noteWakupAlarm(sourcePkg, sourceUid, workSource, tag);
        if (workSource != null) {
            String workSourcePackage = workSource.getPackageName(0);
            int workSourceUid = workSource.getAttributionUid();
            if (workSourcePackage == null) {
                workSourcePackage = sourcePkg;
                workSourceUid = sourceUid;
            }

            if (mUsageStatsService != null) {
                standbyBucket = mUsageStatsService.getAppStandbyBucket(workSourcePackage,
                        UserHandle.getUserId(workSourceUid), SystemClock.elapsedRealtime());
            }

            FrameworkStatsLog.write(FrameworkStatsLog.WAKEUP_ALARM_OCCURRED, workSource, tag,
                    sourcePkg, standbyBucket);
            if (DEBUG_POWER) {
                Slog.w(TAG, "noteWakeupAlarm[ sourcePkg=" + sourcePkg + ", sourceUid=" + sourceUid
                        + ", workSource=" + workSource + ", tag=" + tag + ", standbyBucket="
                        + standbyBucket + " wsName=" + workSourcePackage + ")]");
            }
        } else {
            if (mUsageStatsService != null) {
                standbyBucket = mUsageStatsService.getAppStandbyBucket(sourcePkg,
                        UserHandle.getUserId(sourceUid), SystemClock.elapsedRealtime());
            }
            FrameworkStatsLog.write_non_chained(FrameworkStatsLog.WAKEUP_ALARM_OCCURRED, sourceUid,
                    null, tag, sourcePkg, standbyBucket);
            if (DEBUG_POWER) {
                Slog.w(TAG, "noteWakeupAlarm[ sourcePkg=" + sourcePkg + ", sourceUid=" + sourceUid
                        + ", workSource=" + workSource + ", tag=" + tag + ", standbyBucket="
                        + standbyBucket + "]");
            }
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
        final String reason = (pReason == null) ? "Unknown" : pReason;
        // XXX Note: don't acquire main activity lock here, because the window
        // manager calls in with its locks held.

        boolean killed = false;
        final ArrayList<ProcessRecord> killCandidates = new ArrayList<>();
        synchronized (mPidsSelfLocked) {
            int worstType = 0;
            for (int i = 0; i < pids.length; i++) {
                ProcessRecord proc = mPidsSelfLocked.get(pids[i]);
                if (proc != null) {
                    int type = proc.mState.getSetAdj();
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
            for (int i = 0; i < pids.length; i++) {
                ProcessRecord proc = mPidsSelfLocked.get(pids[i]);
                if (proc == null) {
                    continue;
                }
                int adj = proc.mState.getSetAdj();
                if (adj >= worstType && !proc.isKilledByAm()) {
                    killCandidates.add(proc);
                    killed = true;
                }
            }
        }
        if (!killCandidates.isEmpty()) {
            mHandler.post(() -> {
                synchronized (ActivityManagerService.this) {
                    for (int i = 0, size = killCandidates.size(); i < size; i++) {
                        killCandidates.get(i).killLocked(reason,
                                ApplicationExitInfo.REASON_OTHER,
                                ApplicationExitInfo.SUBREASON_KILL_PID, true);
                    }
                }
            });
        }
        return killed;
    }

    private void killUid(int appId, int userId, int reason, int subReason,
            String reasonAsString) {
        enforceCallingPermission(Manifest.permission.KILL_UID, "killUid");
        synchronized (this) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mProcLock) {
                    mProcessList.killPackageProcessesLSP(null /* packageName */, appId, userId,
                            ProcessList.PERSISTENT_PROC_ADJ, false /* callerWillRestart */,
                            true /* callerWillRestart */, true /* doit */,
                            true /* evenPersistent */, false /* setRemoved */,
                            false /* uninstalling */,
                            reason,
                            subReason,
                            reasonAsString != null ? reasonAsString : "kill uid");
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public void killUid(int appId, int userId, String reason) {
        killUid(appId, userId, ApplicationExitInfo.REASON_OTHER,
                ApplicationExitInfo.SUBREASON_KILL_UID, reason);
    }

    @Override
    public void killUidForPermissionChange(int appId, int userId, String reason) {
        enforceCallingPermission(Manifest.permission.KILL_UID, "killUid");
        synchronized (this) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mProcLock) {
                    mProcessList.killPackageProcessesLSP(null /* packageName */, appId, userId,
                            ProcessList.PERSISTENT_PROC_ADJ, false /* callerWillRestart */,
                            true /* callerWillRestart */, true /* doit */,
                            true /* evenPersistent */, false /* setRemoved */,
                            false /* uninstalling */,
                            ApplicationExitInfo.REASON_PERMISSION_CHANGE,
                            ApplicationExitInfo.SUBREASON_UNKNOWN,
                            reason != null ? reason : "kill uid");
                }
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
        synchronized (this) {
            synchronized (mProcLock) {
                synchronized (mPidsSelfLocked) {
                    final int size = mPidsSelfLocked.size();
                    for (int i = 0; i < size; i++) {
                        final int pid = mPidsSelfLocked.keyAt(i);
                        final ProcessRecord proc = mPidsSelfLocked.valueAt(i);
                        if (proc == null) continue;

                        final int adj = proc.mState.getSetAdj();
                        if (adj > belowAdj && !proc.isKilledByAm()) {
                            proc.killLocked(reason, ApplicationExitInfo.REASON_PERMISSION_CHANGE,
                                    true);
                            killed = true;
                        }
                    }
                }
            }
        }
        return killed;
    }

    /**
     * Similar to {@link #killPids} but killing will be delayed until the device is idle
     * and the given process is imperceptible.
     */
    @Override
    public void killProcessesWhenImperceptible(int[] pids, String reason) {
        if (checkCallingPermission(android.Manifest.permission.FORCE_STOP_PACKAGES)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.FORCE_STOP_PACKAGES);
        }
        int callerUid = Binder.getCallingUid();
        final long iden = Binder.clearCallingIdentity();
        try {
            mProcessList.killProcessesWhenImperceptible(pids, reason, callerUid);
        } finally {
            Binder.restoreCallingIdentity(iden);
        }
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

    @Override
    public void performIdleMaintenance() {
        if (checkCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.SET_ACTIVITY_WATCHER);
        }

        synchronized (mProcLock) {
            final long now = SystemClock.uptimeMillis();
            final long timeSinceLastIdle = now - mLastIdleTime;

            if (!disableSystemCompaction()) {
                // Compact all non-zygote processes to freshen up the page cache.
                mOomAdjuster.mCachedAppOptimizer.compactAllSystem();
            }

            final long lowRamSinceLastIdle = mAppProfiler.getLowRamTimeSinceIdleLPr(now);
            mLastIdleTime = now;
            mAppProfiler.updateLowRamTimestampLPr(now);

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

            // This threshold should be applicable to both PSS and RSS because the value is absolute
            // and represents an increase in process memory relative to its own previous state.
            //
            // TODO(b/296454553): Tune this value during the flag rollout process if more processes
            // seem to be getting killed than before.
            final long memoryGrowthThreshold =
                    Math.max(totalMemoryInKb / 100, MINIMUM_MEMORY_GROWTH_THRESHOLD);
            mProcessList.forEachLruProcessesLOSP(false, proc -> {
                if (proc.getThread() == null) {
                    return;
                }
                final ProcessProfileRecord pr = proc.mProfile;
                final ProcessStateRecord state = proc.mState;
                final int setProcState = state.getSetProcState();
                if (state.isNotCachedSinceIdle()) {
                    if (setProcState >= ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE
                            && setProcState <= ActivityManager.PROCESS_STATE_SERVICE) {
                        final long initialIdlePssOrRss, lastPssOrRss, lastSwapPss;
                        synchronized (mAppProfiler.mProfilerLock) {
                            initialIdlePssOrRss = pr.getInitialIdlePssOrRss();
                            lastPssOrRss = mAppProfiler.isProfilingPss()
                                    ? pr.getLastPss() : pr.getLastRss();
                            lastSwapPss = pr.getLastSwapPss();
                        }
                        if (doKilling && initialIdlePssOrRss != 0
                                && lastPssOrRss > (initialIdlePssOrRss * 3 / 2)
                                && lastPssOrRss > (initialIdlePssOrRss + memoryGrowthThreshold)) {
                            final StringBuilder sb2 = new StringBuilder(128);
                            sb2.append("Kill");
                            sb2.append(proc.processName);
                            if (mAppProfiler.isProfilingPss()) {
                                sb2.append(" in idle maint: pss=");
                            } else {
                                sb2.append(" in idle maint: rss=");
                            }
                            sb2.append(lastPssOrRss);

                            if (mAppProfiler.isProfilingPss()) {
                                sb2.append(", swapPss=");
                                sb2.append(lastSwapPss);
                                sb2.append(", initialPss=");
                            } else {
                                sb2.append(", initialRss=");
                            }
                            sb2.append(initialIdlePssOrRss);
                            sb2.append(", period=");
                            TimeUtils.formatDuration(timeSinceLastIdle, sb2);
                            sb2.append(", lowRamPeriod=");
                            TimeUtils.formatDuration(lowRamSinceLastIdle, sb2);
                            Slog.wtfQuiet(TAG, sb2.toString());
                            mHandler.post(() -> {
                                synchronized (ActivityManagerService.this) {
                                    proc.killLocked(mAppProfiler.isProfilingPss()
                                            ? "idle maint (pss " : "idle maint (rss " + lastPssOrRss
                                            + " from " + initialIdlePssOrRss + ")",
                                            ApplicationExitInfo.REASON_OTHER,
                                            ApplicationExitInfo.SUBREASON_MEMORY_PRESSURE,
                                            true);
                                }
                            });
                        }
                    }
                } else if (setProcState < ActivityManager.PROCESS_STATE_HOME
                        && setProcState >= ActivityManager.PROCESS_STATE_PERSISTENT) {
                    state.setNotCachedSinceIdle(true);
                    synchronized (mAppProfiler.mProfilerLock) {
                        pr.setInitialIdlePssOrRss(0);
                        mAppProfiler.updateNextPssTimeLPf(
                                state.getSetProcState(), proc.mProfile, now, true);
                    }
                }
            });
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
        mHiddenApiBlacklist.registerObserver();
        mPlatformCompat.registerContentObserver();

        mAppProfiler.retrieveSettings();

        final Resources res;
        synchronized (this) {
            mDebugApp = mOrigDebugApp = debugApp;
            mWaitForDebugger = mOrigWaitForDebugger = waitForDebugger;
            mAlwaysFinishActivities = alwaysFinishActivities;
            // Load resources only after the current configuration has been set.
            res = mContext.getResources();
            final boolean userSwitchUiEnabled = !res.getBoolean(
                    com.android.internal.R.bool.config_customUserSwitchUi);
            final int maxRunningUsers = res.getInteger(
                    com.android.internal.R.integer.config_multiuserMaxRunningUsers);
            final boolean delayUserDataLocking = res.getBoolean(
                    com.android.internal.R.bool.config_multiuserDelayUserDataLocking);
            final int backgroundUserScheduledStopTimeSecs = res.getInteger(
                    com.android.internal.R.integer.config_backgroundUserScheduledStopTimeSecs);
            mUserController.setInitialConfig(userSwitchUiEnabled, maxRunningUsers,
                    delayUserDataLocking, backgroundUserScheduledStopTimeSecs);
        }
        mAppErrors.loadAppsNotReportingCrashesFromConfig(res.getString(
                com.android.internal.R.string.config_appsNotReportingCrashes));
    }

    /**
     * Ready. Set. Go!
     */
    public void systemReady(final Runnable goingCallback, @NonNull TimingsTraceAndSlog t) {
        t.traceBegin("PhaseActivityManagerReady");
        mSystemServiceManager.preSystemReady();
        synchronized(this) {
            if (mSystemReady) {
                // If we're done calling all the receivers, run the next "boot phase" passed in
                // by the SystemServer
                if (goingCallback != null) {
                    goingCallback.run();
                }
                t.traceEnd(); // PhaseActivityManagerReady
                return;
            }

            t.traceBegin("controllersReady");
            mLocalDeviceIdleController =
                    LocalServices.getService(DeviceIdleInternal.class);
            mActivityTaskManager.onSystemReady();
            // Make sure we have the current profile info, since it is needed for security checks.
            mUserController.onSystemReady();
            mAppOpsService.systemReady();
            mProcessList.onSystemReady();
            mAppRestrictionController.onSystemReady();
            mSystemReady = true;
            t.traceEnd();
        }

        try {
            sTheRealBuildSerial = IDeviceIdentifiersPolicyService.Stub.asInterface(
                    ServiceManager.getService(Context.DEVICE_IDENTIFIERS_SERVICE))
                    .getSerial();
        } catch (RemoteException e) {}

        t.traceBegin("killProcesses");
        ArrayList<ProcessRecord> procsToKill = null;
        synchronized(mPidsSelfLocked) {
            for (int i=mPidsSelfLocked.size()-1; i>=0; i--) {
                ProcessRecord proc = mPidsSelfLocked.valueAt(i);
                if (!isAllowedWhileBooting(proc.info)) {
                    if (procsToKill == null) {
                        procsToKill = new ArrayList<ProcessRecord>();
                    }
                    procsToKill.add(proc);
                }
            }
        }

        synchronized(this) {
            if (procsToKill != null) {
                for (int i = procsToKill.size() - 1; i >= 0; i--) {
                    ProcessRecord proc = procsToKill.get(i);
                    Slog.i(TAG, "Removing system update proc: " + proc);
                    mProcessList.removeProcessLocked(proc, true, false,
                            ApplicationExitInfo.REASON_OTHER,
                            ApplicationExitInfo.SUBREASON_SYSTEM_UPDATE_DONE,
                            "system update done");
                }
            }

            // Now that we have cleaned up any update processes, we
            // are ready to start launching real processes and know that
            // we won't trample on them any more.
            mProcessesReady = true;
        }
        t.traceEnd(); // KillProcesses

        Slog.i(TAG, "System now ready");

        EventLogTags.writeBootProgressAmsReady(SystemClock.uptimeMillis());

        t.traceBegin("updateTopComponentForFactoryTest");
        mAtmInternal.updateTopComponentForFactoryTest();
        t.traceEnd();

        t.traceBegin("registerActivityLaunchObserver");
        mAtmInternal.getLaunchObserverRegistry().registerLaunchObserver(mActivityLaunchObserver);
        t.traceEnd();

        t.traceBegin("watchDeviceProvisioning");
        watchDeviceProvisioning(mContext);
        t.traceEnd();

        t.traceBegin("retrieveSettings");
        retrieveSettings();
        t.traceEnd();

        t.traceBegin("Ugm.onSystemReady");
        mUgmInternal.onSystemReady();
        t.traceEnd();

        t.traceBegin("updateForceBackgroundCheck");
        final PowerManagerInternal pmi = LocalServices.getService(PowerManagerInternal.class);
        if (pmi != null) {
            pmi.registerLowPowerModeObserver(ServiceType.FORCE_BACKGROUND_CHECK,
                    state -> updateForceBackgroundCheck(state.batterySaverEnabled));
            updateForceBackgroundCheck(
                    pmi.getLowPowerState(ServiceType.FORCE_BACKGROUND_CHECK).batterySaverEnabled);
        } else {
            Slog.wtf(TAG, "PowerManagerInternal not found.");
        }
        t.traceEnd();

        if (goingCallback != null) goingCallback.run();

        t.traceBegin("getCurrentUser"); // should be fast, but these methods acquire locks
        // Check the current user here as a user can be started inside goingCallback.run() from
        // other system services.
        final int currentUserId = mUserController.getCurrentUserId();
        Slog.i(TAG, "Current user:" + currentUserId);
        if (currentUserId != UserHandle.USER_SYSTEM && !mUserController.isSystemUserStarted()) {
            // User other than system user has started. Make sure that system user is already
            // started before switching user.
            throw new RuntimeException("System user not started while current user is:"
                    + currentUserId);
        }
        t.traceEnd();

        t.traceBegin("ActivityManagerStartApps");
        mBatteryStatsService.onSystemReady();
        mBatteryStatsService.noteEvent(BatteryStats.HistoryItem.EVENT_USER_RUNNING_START,
                Integer.toString(currentUserId), currentUserId);
        mBatteryStatsService.noteEvent(BatteryStats.HistoryItem.EVENT_USER_FOREGROUND_START,
                Integer.toString(currentUserId), currentUserId);

        // On Automotive / Headless System User Mode, at this point the system user has already been
        // started and unlocked, and some of the tasks we do here have already been done. So skip
        // those in that case. The duplicate system user start is guarded in SystemServiceManager.
        // TODO(b/266158156): this workaround shouldn't be necessary once we move the headless-user
        // start logic to UserManager-land.
        mUserController.onSystemUserStarting();

        synchronized (this) {
            // Only start up encryption-aware persistent apps; once user is
            // unlocked we'll come back around and start unaware apps
            t.traceBegin("startPersistentApps");
            startPersistentApps(PackageManager.MATCH_DIRECT_BOOT_AWARE);
            t.traceEnd();

            // Start up initial activity.
            mBooting = true;
            // Enable home activity for system user, so that the system can always boot. We don't
            // do this when the system user is not setup since the setup wizard should be the one
            // to handle home activity in this case.
            if (SystemProperties.getBoolean(SYSTEM_USER_HOME_NEEDED, false)) {
                t.traceBegin("enableHomeActivity");
                ComponentName cName = new ComponentName(mContext, SystemUserHomeActivity.class);
                try {
                    AppGlobals.getPackageManager().setComponentEnabledSetting(cName,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0,
                            UserHandle.USER_SYSTEM, "am");
                } catch (RemoteException e) {
                    throw e.rethrowAsRuntimeException();
                }
                t.traceEnd();
            }

            boolean isBootingSystemUser = currentUserId == UserHandle.USER_SYSTEM;

            // Some systems - like automotive - will explicitly unlock system user then switch
            // to a secondary user.
            // TODO(b/266158156): this workaround shouldn't be necessary once we move
            // the headless-user start logic to UserManager-land.
            if (isBootingSystemUser && !UserManager.isHeadlessSystemUserMode()) {
                t.traceBegin("startHomeOnAllDisplays");
                mAtmInternal.startHomeOnAllDisplays(currentUserId, "systemReady");
                t.traceEnd();
            }

            mHandler.post(mAtmInternal::showSystemReadyErrorDialogsIfNeeded);

            if (isBootingSystemUser) {
                // Need to send the broadcasts for the system user here because
                // UserController#startUserInternal will not send them for the system user starting,
                // It checks if the user state already exists, which is always the case for the
                // system user.
                t.traceBegin("sendUserStartBroadcast");
                final int callingUid = Binder.getCallingUid();
                final int callingPid = Binder.getCallingPid();
                final long ident = Binder.clearCallingIdentity();
                try {
                    mUserController.sendUserStartedBroadcast(
                            currentUserId, callingUid, callingPid);
                    mUserController.sendUserStartingBroadcast(
                            currentUserId, callingUid, callingPid);
                } catch (Throwable e) {
                    Slog.wtf(TAG, "Failed sending first user broadcasts", e);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
                t.traceEnd();
            } else {
                Slog.i(TAG, "Not sending multi-user broadcasts for non-system user "
                        + currentUserId);
            }

            t.traceBegin("resumeTopActivities");
            mAtmInternal.resumeTopActivities(false /* scheduleIdle */);
            t.traceEnd();

            if (isBootingSystemUser) {
                t.traceBegin("sendUserSwitchBroadcasts");
                mUserController.sendUserSwitchBroadcasts(-1, currentUserId);
                t.traceEnd();
            }

            t.traceBegin("setBinderProxies");
            BinderInternal.nSetBinderProxyCountWatermarks(BINDER_PROXY_HIGH_WATERMARK,
                    BINDER_PROXY_LOW_WATERMARK, BINDER_PROXY_WARNING_WATERMARK);
            BinderInternal.nSetBinderProxyCountEnabled(true);
            BinderInternal.setBinderProxyCountCallback(new MyBinderProxyCountEventListener(),
                    mHandler);
            t.traceEnd(); // setBinderProxies

            t.traceEnd(); // ActivityManagerStartApps

            // Load the component aliases.
            t.traceBegin("componentAlias");
            mComponentAliasResolver.onSystemReady(mConstants.mEnableComponentAlias,
                    mConstants.mComponentAliasOverrides);
            t.traceEnd(); // componentAlias

            t.traceEnd(); // PhaseActivityManagerReady
        }
    }

    private class MyBinderProxyCountEventListener implements BinderProxyCountEventListener {
        @Override
        public void onLimitReached(int uid) {
            // Spawn a new thread for the dump as it'll take long time.
            new Thread(() -> handleLimitReached(uid), "BinderProxy Dump: " + uid).start();
        }

        private void handleLimitReached(int uid) {
            Slog.wtf(TAG, "Uid " + uid + " sent too many Binders to uid "
                    + Process.myUid());
            BinderProxy.dumpProxyDebugInfo();
            CriticalEventLog.getInstance().logExcessiveBinderCalls(uid);
            if (uid == Process.SYSTEM_UID) {
                Slog.i(TAG, "Skipping kill (uid is SYSTEM)");
            } else {
                killUid(UserHandle.getAppId(uid), UserHandle.getUserId(uid),
                        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                        ApplicationExitInfo.SUBREASON_EXCESSIVE_BINDER_OBJECTS,
                        "Too many Binders sent to SYSTEM");
                // We need to run a GC here, because killing the processes involved
                // actually isn't guaranteed to free up the proxies; in fact, if the
                // GC doesn't run for a long time, we may even exceed the global
                // proxy limit for a process (20000), resulting in system_server itself
                // being killed.
                // Note that the GC here might not actually clean up all the proxies,
                // because the binder reference decrements will come in asynchronously;
                // but if new processes belonging to the UID keep adding proxies, we
                // will get another callback here, and run the GC again - this time
                // cleaning up the old proxies.
                VMRuntime.getRuntime().requestConcurrentGC();
            }
        }

        @Override
        public void onWarningThresholdReached(int uid) {
            if (Flags.logExcessiveBinderProxies()) {
                Slog.w(TAG, "Uid " + uid + " sent too many ("
                        + BINDER_PROXY_WARNING_WATERMARK + ") Binders to uid " + Process.myUid());
                FrameworkStatsLog.write(
                        FrameworkStatsLog.EXCESSIVE_BINDER_PROXY_COUNT_REPORTED,
                        uid);
            }
        }
    }

    private void watchDeviceProvisioning(Context context) {
        // setting system property based on whether device is provisioned

        if (isDeviceProvisioned(context)) {
            SystemProperties.set(SYSTEM_PROPERTY_DEVICE_PROVISIONED, "1");
        } else {
            // watch for device provisioning change
            context.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED), false,
                    new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            if (isDeviceProvisioned(context)) {
                                SystemProperties.set(SYSTEM_PROPERTY_DEVICE_PROVISIONED, "1");
                                context.getContentResolver().unregisterContentObserver(this);
                            }
                        }
                    });
        }
    }

    private boolean isDeviceProvisioned(Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) != 0;
    }

    private void startBroadcastObservers() {
        mBroadcastQueue.start(mContext.getContentResolver());
    }

    private void updateForceBackgroundCheck(boolean enabled) {
        synchronized (this) {
            synchronized (mProcLock) {
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
    }

    void killAppAtUsersRequest(ProcessRecord app) {
        synchronized (this) {
            mAppErrors.killAppAtUserRequestLocked(app);
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
        float loadingProgress = 1;
        IncrementalMetrics incrementalMetrics = null;
        // Obtain Incremental information if available
        if (r != null && r.info != null && r.info.packageName != null) {
            IncrementalStatesInfo incrementalStatesInfo =
                    mPackageManagerInt.getIncrementalStatesInfo(r.info.packageName, SYSTEM_UID,
                            r.userId);
            if (incrementalStatesInfo != null) {
                loadingProgress = incrementalStatesInfo.getProgress();
            }
            final String codePath = r.info.getCodePath();
            if (codePath != null && !codePath.isEmpty()
                    && IncrementalManager.isIncrementalPath(codePath)) {
                // Report in the main log about the incremental package
                Slog.e(TAG, "App crashed on incremental package " + r.info.packageName
                        + " which is " + ((int) (loadingProgress * 100)) + "% loaded.");
                final IBinder incrementalService = ServiceManager.getService(
                        Context.INCREMENTAL_SERVICE);
                if (incrementalService != null) {
                    final IncrementalManager incrementalManager = new IncrementalManager(
                            IIncrementalService.Stub.asInterface(incrementalService));
                    incrementalMetrics = incrementalManager.getMetrics(codePath);
                }
            }
        }

        boolean recoverable = eventType.equals("native_recoverable_crash");

        EventLogTags.writeAmCrash(Binder.getCallingPid(),
                UserHandle.getUserId(Binder.getCallingUid()), processName,
                r == null ? -1 : r.info.flags,
                crashInfo.exceptionClassName,
                crashInfo.exceptionMessage,
                crashInfo.throwFileName,
                crashInfo.throwLineNumber,
                recoverable ? 1 : 0);

        int processClassEnum = processName.equals("system_server") ? ServerProtoEnums.SYSTEM_SERVER
                : (r != null) ? r.getProcessClassEnum()
                        : ServerProtoEnums.ERROR_SOURCE_UNKNOWN;
        int uid = (r != null) ? r.uid : -1;
        int pid = (r != null) ? r.getPid() : -1;
        FrameworkStatsLog.write(FrameworkStatsLog.APP_CRASH_OCCURRED,
                uid,
                eventType,
                processName,
                pid,
                (r != null && r.info != null) ? r.info.packageName : "",
                (r != null && r.info != null) ? (r.info.isInstantApp()
                        ? FrameworkStatsLog.APP_CRASH_OCCURRED__IS_INSTANT_APP__TRUE
                        : FrameworkStatsLog.APP_CRASH_OCCURRED__IS_INSTANT_APP__FALSE)
                        : FrameworkStatsLog.APP_CRASH_OCCURRED__IS_INSTANT_APP__UNAVAILABLE,
                r != null ? (r.isInterestingToUserLocked()
                        ? FrameworkStatsLog.APP_CRASH_OCCURRED__FOREGROUND_STATE__FOREGROUND
                        : FrameworkStatsLog.APP_CRASH_OCCURRED__FOREGROUND_STATE__BACKGROUND)
                        : FrameworkStatsLog.APP_CRASH_OCCURRED__FOREGROUND_STATE__UNKNOWN,
                processClassEnum,
                incrementalMetrics != null /* isIncremental */, loadingProgress,
                incrementalMetrics != null ? incrementalMetrics.getMillisSinceOldestPendingRead()
                        : -1,
                incrementalMetrics != null ? incrementalMetrics.getStorageHealthStatusCode()
                        : -1,
                incrementalMetrics != null ? incrementalMetrics.getDataLoaderStatusCode()
                        : -1,
                incrementalMetrics != null && incrementalMetrics.getReadLogsEnabled(),
                incrementalMetrics != null ? incrementalMetrics.getMillisSinceLastDataLoaderBind()
                        : -1,
                incrementalMetrics != null ? incrementalMetrics.getDataLoaderBindDelayMillis()
                        : -1,
                incrementalMetrics != null ? incrementalMetrics.getTotalDelayedReads()
                        : -1,
                incrementalMetrics != null ? incrementalMetrics.getTotalFailedReads()
                        : -1,
                incrementalMetrics != null ? incrementalMetrics.getLastReadErrorUid()
                        : -1,
                incrementalMetrics != null ? incrementalMetrics.getMillisSinceLastReadError()
                        : -1,
                incrementalMetrics != null ? incrementalMetrics.getLastReadErrorNumber()
                        : 0,
                incrementalMetrics != null ? incrementalMetrics.getTotalDelayedReadsDurationMillis()
                        : -1
        );

        if (eventType.equals("native_crash")) {
            CriticalEventLog.getInstance().logNativeCrash(processClassEnum, processName, uid, pid);
        } else if (eventType.equals("crash")) {
            CriticalEventLog.getInstance().logJavaCrash(crashInfo.exceptionClassName,
                    processClassEnum, processName, uid, pid);
        }

        final int relaunchReason = r == null ? RELAUNCH_REASON_NONE
                        : r.getWindowProcessController().computeRelaunchReason();
        final String relaunchReasonString = relaunchReasonToString(relaunchReason);
        if (crashInfo.crashTag == null) {
            crashInfo.crashTag = relaunchReasonString;
        } else {
            crashInfo.crashTag = crashInfo.crashTag + " " + relaunchReasonString;
        }

        addErrorToDropBox(
                eventType, r, processName, null, null, null, null, null, null, crashInfo,
                new Float(loadingProgress), incrementalMetrics, null, null);

        // For GWP-ASan recoverable crashes, don't make the app crash (the whole point of
        // 'recoverable' is that the app doesn't crash). Normally, for nonrecoreable native crashes,
        // debuggerd will terminate the process, but there's a backup where ActivityManager will
        // also kill it. Avoid that.
        if (recoverable) {
            mAppErrors.sendRecoverableCrashToAppExitInfo(r, crashInfo);
        } else {
            mAppErrors.crashApplication(r, crashInfo);
        }
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
            final long origId = Binder.clearCallingIdentity();
            try {
                Message msg = Message.obtain();
                msg.what = SHOW_STRICT_MODE_VIOLATION_UI_MSG;
                HashMap<String, Object> data = new HashMap<String, Object>();
                data.put("result", result);
                data.put("app", r);
                data.put("info", info);
                msg.obj = data;
                mUiHandler.sendMessage(msg);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
            int res = result.get();
            Slog.w(TAG, "handleApplicationStrictModeViolation; res=" + res);
        }
    }

    /**
     * Register a callback to raise strict mode violations.
     * @param callback The binder used to communicate the violations.
     */
    @Override
    public void registerStrictModeCallback(IBinder callback) {
        int callingPid = Binder.getCallingPid();
        mStrictModeCallbacks.put(callingPid,
                IUnsafeIntentStrictModeCallback.Stub.asInterface(callback));
        try {
            callback.linkToDeath(new DeathRecipient() {
                @Override
                public void binderDied() {
                    mStrictModeCallbacks.remove(callingPid);
                }
            }, 0);
        } catch (RemoteException e) {
            mStrictModeCallbacks.remove(callingPid);
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
        final String processName = process != null && process.getPid() == MY_PID
                ? "system_server"
                : (process == null ? "unknown" : process.processName);
        final DropBoxManager dbox = (DropBoxManager)
                mContext.getSystemService(Context.DROPBOX_SERVICE);

        // Exit early if the dropbox isn't configured to accept this report type.
        final String dropboxTag = processClass(process) + "_strictmode";
        if (dbox == null || !dbox.isTagEnabled(dropboxTag)) return;

        final StringBuilder sb = new StringBuilder(1024);
        synchronized (sb) {
            appendDropBoxProcessHeaders(process, processName, null, sb);
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
                sb.append("Broadcast-Intent-Action: ")
                        .append(info.broadcastIntentAction)
                        .append("\n");
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
    @Override
    public boolean handleApplicationWtf(@Nullable final IBinder app, @Nullable final String tag,
            boolean system, @NonNull final ApplicationErrorReport.ParcelableCrashInfo crashInfo,
            int immediateCallerPid) {
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();

        // Internal callers in RuntimeInit should always generate a crashInfo.
        Preconditions.checkNotNull(crashInfo);

        // If this is coming from the system, we could very well have low-level
        // system locks held, so we want to do this all asynchronously.  And we
        // never want this to become fatal, so there is that too.
        //
        // Note: "callingPid == Process.myPid())" wouldn't be reliable because even if the caller
        // is within the system server, if it calls Log.wtf() without clearning the calling
        // identity, callingPid would still be of a remote caller. So we explicltly pass the
        // process PID from the caller.
        if (system || (immediateCallerPid == Process.myPid())) {
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

    ProcessRecord handleApplicationWtfInner(int callingUid, int callingPid, @Nullable IBinder app,
            @Nullable String tag, @Nullable final ApplicationErrorReport.CrashInfo crashInfo) {
        final ProcessRecord r = findAppProcess(app, "WTF");
        final String processName = app == null ? "system_server"
                : (r == null ? "unknown" : r.processName);

        EventLogTags.writeAmWtf(UserHandle.getUserId(callingUid), callingPid,
                processName, r == null ? -1 : r.info.flags, tag,
                crashInfo == null ? "unknown" : crashInfo.exceptionMessage);

        FrameworkStatsLog.write(FrameworkStatsLog.WTF_OCCURRED, callingUid, tag, processName,
                callingPid, (r != null) ? r.getProcessClassEnum() : 0);

        addErrorToDropBox("wtf", r, processName, null, null, null, tag, null, null, crashInfo,
                null, null, null, null);

        return r;
    }

    /**
     * Schedule to handle any pending system_server WTFs.
     */
    public void schedulePendingSystemServerWtfs(
            final LinkedList<Pair<String, ApplicationErrorReport.CrashInfo>> list) {
        mHandler.post(() -> handlePendingSystemServerWtfs(list));
    }

    /**
     * Handle any pending system_server WTFs, add into the dropbox
     */
    private void handlePendingSystemServerWtfs(
            final LinkedList<Pair<String, ApplicationErrorReport.CrashInfo>> list) {
        ProcessRecord proc;
        synchronized (mPidsSelfLocked) {
            proc = mPidsSelfLocked.get(MY_PID);
        }
        for (Pair<String, ApplicationErrorReport.CrashInfo> p = list.poll();
                p != null; p = list.poll()) {
            addErrorToDropBox("wtf", proc, "system_server", null, null, null, p.first, null, null,
                    p.second, null, null, null, null);
        }
    }

    /**
     * @param app object of some object (as stored in {@link com.android.internal.os.RuntimeInit})
     * @return the corresponding {@link ProcessRecord} object, or null if none could be found
     */
    private ProcessRecord findAppProcess(IBinder app, String reason) {
        if (app == null) {
            return null;
        }

        synchronized (mProcLock) {
            return mProcessList.findAppProcessLOSP(app, reason);
        }
    }

    /**
     * Utility function for addErrorToDropBox and handleStrictModeViolation's logging
     * to append various headers to the dropbox log text.
     */
    void appendDropBoxProcessHeaders(ProcessRecord process, String processName,
            final VolatileDropboxEntryStates volatileStates, final StringBuilder sb) {
        sb.append("SystemUptimeMs: ").append(SystemClock.uptimeMillis()).append("\n");

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
        synchronized (mProcLock) {
            sb.append("Process: ").append(processName).append("\n");
            sb.append("PID: ").append(process.getPid()).append("\n");
            sb.append("UID: ").append(process.uid).append("\n");
            if (process.mOptRecord != null) {
                // Use 'isProcessFrozen' from 'volatileStates' if it'snon-null (present),
                // otherwise use 'isFrozen' from 'mOptRecord'.
                sb.append("Frozen: ").append(
                    (volatileStates != null && volatileStates.isProcessFrozen() != null)
                    ? volatileStates.isProcessFrozen() : process.mOptRecord.isFrozen()
                ).append("\n");
            }
            if (volatileStates != null && volatileStates.getTimestamp() != null) {
                String formattedTime = DROPBOX_TIME_FORMATTER.format(
                    volatileStates.getTimestamp());
                sb.append("Timestamp: ").append(formattedTime).append("\n");
            }
            int flags = process.info.flags;
            final IPackageManager pm = AppGlobals.getPackageManager();
            sb.append("Flags: 0x").append(Integer.toHexString(flags)).append("\n");
            final int callingUserId = UserHandle.getCallingUserId();
            process.getPkgList().forEachPackage(pkg -> {
                sb.append("Package: ").append(pkg);
                try {
                    final PackageInfo pi = pm.getPackageInfo(pkg, 0, callingUserId);
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
            });
            if (process.info.isInstantApp()) {
                sb.append("Instant-App: true\n");
            }

            if (process.isSdkSandbox) {
                final String clientPackage = process.sdkSandboxClientAppPackage;
                try {
                    final PackageInfo pi = pm.getPackageInfo(clientPackage,
                            PackageManager.GET_SHARED_LIBRARY_FILES, callingUserId);
                    if (pi != null) {
                        appendSdkSandboxClientPackageHeader(sb, pi);
                        appendSdkSandboxLibraryHeaders(sb, pi);
                    } else {
                        Slog.e(TAG,
                                "PackageInfo is null for SDK sandbox client: " + clientPackage);
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG,
                            "Error getting package info for SDK sandbox client: " + clientPackage,
                            e);
                }
                sb.append("SdkSandbox: true\n");
            }
        }
    }

    private void appendSdkSandboxClientPackageHeader(StringBuilder sb,
            PackageInfo clientPackageInfo) {
        sb.append("SdkSandbox-Client-Package: ").append(clientPackageInfo.packageName);
        sb.append(" v").append(clientPackageInfo.getLongVersionCode());
        if (clientPackageInfo.versionName != null) {
            sb.append(" (").append(clientPackageInfo.versionName).append(")");
        }
        sb.append("\n");
    }

    private void appendSdkSandboxLibraryHeaders(StringBuilder sb,
            PackageInfo clientPackageInfo) {
        final ApplicationInfo info = clientPackageInfo.applicationInfo;
        final List<SharedLibraryInfo> sharedLibraries = info.getSharedLibraryInfos();
        for (int j = 0, size = sharedLibraries.size(); j < size; j++) {
            final SharedLibraryInfo sharedLibrary = sharedLibraries.get(j);
            if (!sharedLibrary.isSdk()) {
                continue;
            }

            sb.append("SdkSandbox-Library: ").append(sharedLibrary.getPackageName());
            final VersionedPackage versionedPackage = sharedLibrary.getDeclaringPackage();
            sb.append(" v").append(versionedPackage.getLongVersionCode());
            sb.append("\n");
        }
    }

    private static String processClass(ProcessRecord process) {
        if (process == null || process.getPid() == MY_PID) {
            return "system_server";
        } else if (process.info.isSystemApp() || process.info.isSystemExt()) {
            return "system_app";
        } else {
            return "data_app";
        }
    }

    private final DropboxRateLimiter mDropboxRateLimiter = new DropboxRateLimiter();

    /** Initializes the Dropbox Rate Limiter parameters from flags. */
    public void initDropboxRateLimiter() {
        mDropboxRateLimiter.init();
    }

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
     * @param loadingProgress the loading progress of an installed package, range in [0, 1].
     * @param incrementalMetrics metrics for apps installed on Incremental.
     * @param errorId a unique id to append to the dropbox headers.
     */
    @SuppressWarnings("DoNotCall") // Ignore warning for synchronous to call to worker.run()
    public void addErrorToDropBox(String eventType,
            ProcessRecord process, String processName, String activityShortComponentName,
            String parentShortComponentName, ProcessRecord parentProcess,
            String subject, final String report, final File dataFile,
            final ApplicationErrorReport.CrashInfo crashInfo,
            @Nullable Float loadingProgress, @Nullable IncrementalMetrics incrementalMetrics,
            @Nullable UUID errorId, @Nullable VolatileDropboxEntryStates volatileStates) {
        // NOTE -- this must never acquire the ActivityManagerService lock,
        // otherwise the watchdog may be prevented from resetting the system.

        // Bail early if not published yet
        final DropBoxManager dbox;
        try {
            dbox = mContext.getSystemService(DropBoxManager.class);
        } catch (Exception e) {
            return;
        }

        // Exit early if the dropbox isn't configured to accept this report type.
        final String dropboxTag = processClass(process) + "_" + eventType;
        if (dbox == null || !dbox.isTagEnabled(dropboxTag)) return;

        // Check if we should rate limit and abort early if needed.
        final DropboxRateLimiter.RateLimitResult rateLimitResult =
                mDropboxRateLimiter.shouldRateLimit(eventType, processName);
        if (rateLimitResult.shouldRateLimit()) return;

        final StringBuilder sb = new StringBuilder(1024);
        appendDropBoxProcessHeaders(process, processName, volatileStates, sb);
        if (process != null) {
            sb.append("Foreground: ")
                    .append(process.isInterestingToUserLocked() ? "Yes" : "No")
                    .append("\n");
            if (process.getStartUptime() > 0) {
                long runtimeMillis = SystemClock.uptimeMillis() - process.getStartUptime();
                sb.append("Process-Runtime: ").append(runtimeMillis).append("\n");
            }
        }
        if (eventType.equals("crash")) {
            String formattedTime = DROPBOX_TIME_FORMATTER.format(
                    Instant.now().atZone(ZoneId.systemDefault()));
            sb.append("Timestamp: ").append(formattedTime).append("\n");
        }
        if (activityShortComponentName != null) {
            sb.append("Activity: ").append(activityShortComponentName).append("\n");
        }
        if (parentShortComponentName != null) {
            if (parentProcess != null && parentProcess.getPid() != process.getPid()) {
                sb.append("Parent-Process: ").append(parentProcess.processName).append("\n");
            }
            if (!parentShortComponentName.equals(activityShortComponentName)) {
                sb.append("Parent-Activity: ").append(parentShortComponentName).append("\n");
            }
        }
        if (subject != null) {
            sb.append("Subject: ").append(subject).append("\n");
        }
        if (errorId != null) {
            sb.append("ErrorId: ").append(errorId.toString()).append("\n");
        }
        sb.append("Build: ").append(Build.FINGERPRINT).append("\n");

        // If device is not using 4KB pages, add the PageSize
        long pageSize = Os.sysconf(OsConstants._SC_PAGESIZE);
        if (pageSize != 4096) {
            sb.append("PageSize: ").append(pageSize).append("\n");
        }

        if (Debug.isDebuggerConnected()) {
            sb.append("Debugger: Connected\n");
        }
        if (crashInfo != null && crashInfo.exceptionHandlerClassName != null
                && !crashInfo.exceptionHandlerClassName.isEmpty()) {
            sb.append("Crash-Handler: ").append(crashInfo.exceptionHandlerClassName).append("\n");
        }
        if (crashInfo != null && crashInfo.crashTag != null && !crashInfo.crashTag.isEmpty()) {
            sb.append("Crash-Tag: ").append(crashInfo.crashTag).append("\n");
        }
        if (loadingProgress != null) {
            sb.append("Loading-Progress: ").append(loadingProgress.floatValue()).append("\n");
        }
        if (incrementalMetrics != null) {
            sb.append("Incremental: Yes").append("\n");
            final long millisSinceOldestPendingRead =
                    incrementalMetrics.getMillisSinceOldestPendingRead();
            if (millisSinceOldestPendingRead > 0) {
                sb.append("Millis-Since-Oldest-Pending-Read: ").append(
                        millisSinceOldestPendingRead).append("\n");
            }
        }
        sb.append(rateLimitResult.createHeader());
        sb.append("\n");

        // Do the rest in a worker thread to avoid blocking the caller on I/O
        // (After this point, we shouldn't access AMS internal data structures.)
        //
        // If process is null, we are being called from some internal code
        // and may be about to die -- run this synchronously.
        final boolean runSynchronously = process == null;
        Thread worker = new Thread("Error dump: " + dropboxTag) {
            @Override
            public void run() {
                if (report != null) {
                    sb.append(report);
                }

                String logcatSetting = Settings.Global.ERROR_LOGCAT_PREFIX + dropboxTag;
                String kerLogSetting = Settings.Global.ERROR_KERNEL_LOG_PREFIX + dropboxTag;
                String maxBytesSetting = Settings.Global.MAX_ERROR_BYTES_PREFIX + dropboxTag;
                int logcatLines = Build.IS_USER
                        ? 0
                        : Settings.Global.getInt(mContext.getContentResolver(), logcatSetting, 0);
                int kernelLogLines = Build.IS_USER
                        ? 0
                        : Settings.Global.getInt(mContext.getContentResolver(), kerLogSetting, 0);
                int dropboxMaxSize = Settings.Global.getInt(
                        mContext.getContentResolver(), maxBytesSetting, DROPBOX_DEFAULT_MAX_SIZE);

                if (dataFile != null) {
                    // Attach the stack traces file to the report so collectors can load them
                    // by file if they have access.
                    sb.append(DATA_FILE_PATH_HEADER)
                            .append(dataFile.getAbsolutePath()).append('\n');

                    int maxDataFileSize = dropboxMaxSize
                            - sb.length()
                            - logcatLines * RESERVED_BYTES_PER_LOGCAT_LINE
                            - kernelLogLines * RESERVED_BYTES_PER_LOGCAT_LINE
                            - DATA_FILE_PATH_FOOTER.length();

                    if (maxDataFileSize > 0) {
                        // Inline dataFile contents if there is room.
                        try {
                            sb.append(FileUtils.readTextFile(dataFile, maxDataFileSize,
                                    "\n\n[[TRUNCATED]]\n"));
                        } catch (IOException e) {
                            Slog.e(TAG, "Error reading " + dataFile, e);
                        }
                    }
                    // Always append the footer, even there wasn't enough space to inline the
                    // dataFile contents.
                    sb.append(DATA_FILE_PATH_FOOTER);
                }

                if (crashInfo != null && crashInfo.stackTrace != null) {
                    sb.append(crashInfo.stackTrace);
                }
                boolean shouldAddLogs = logcatLines > 0 || kernelLogLines > 0;
                if (!runSynchronously && shouldAddLogs) {
                    sb.append("\n");
                    if (logcatLines > 0) {
                        fetchLogcatBuffers(sb, logcatLines, LOGCAT_TIMEOUT_SEC,
                                List.of("events", "system", "main", "crash"));
                    }
                    if (kernelLogLines > 0) {
                        fetchLogcatBuffers(sb, kernelLogLines, LOGCAT_TIMEOUT_SEC / 2,
                                List.of("kernel"));
                    }
                }

                dbox.addText(dropboxTag, sb.toString());
            }
        };

        if (runSynchronously) {
            final int oldMask = StrictMode.allowThreadDiskWritesMask();
            try {
                worker.run();
            } finally {
                StrictMode.setThreadPolicyMask(oldMask);
            }
        } else {
            worker.start();
            if (process != null && process.mPid == MY_PID && "crash".equals(eventType)) {
                // We're actually crashing, let's wait for up to 2 seconds before killing ourselves,
                // so the data could be persisted into the dropbox.
                try {
                    worker.join(2000);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    @Override
    public List<ActivityManager.ProcessErrorStateInfo> getProcessesInErrorState() {
        enforceNotIsolatedCaller("getProcessesInErrorState");
        // assume our apps are happy - lazy create the list
        final List<ActivityManager.ProcessErrorStateInfo>[] errList = new List[1];

        final int callingUid = Binder.getCallingUid();
        final boolean allUsers = ActivityManager.checkUidPermission(INTERACT_ACROSS_USERS_FULL,
                callingUid) == PackageManager.PERMISSION_GRANTED;
        int userId = UserHandle.getUserId(callingUid);

        final boolean hasDumpPermission = ActivityManager.checkUidPermission(
                android.Manifest.permission.DUMP, callingUid) == PackageManager.PERMISSION_GRANTED;

        synchronized (mProcLock) {
            // iterate across all processes
            mProcessList.forEachLruProcessesLOSP(false, app -> {
                if (!allUsers && app.userId != userId) {
                    return;
                }
                if (!hasDumpPermission && app.info.uid != callingUid) {
                    return;
                }
                final ProcessErrorStateRecord errState = app.mErrorState;
                final boolean crashing = errState.isCrashing();
                final boolean notResponding = errState.isNotResponding();
                if ((app.getThread() != null) && (crashing || notResponding)) {
                    // This one's in trouble, so we'll generate a report for it
                    // crashes are higher priority (in case there's a crash *and* an anr)
                    ActivityManager.ProcessErrorStateInfo report = null;
                    if (crashing) {
                        report = errState.getCrashingReport();
                    } else if (notResponding) {
                        report = errState.getNotRespondingReport();
                    }

                    if (report != null) {
                        if (errList[0] == null) {
                            errList[0] = new ArrayList<>(1);
                        }
                        errList[0].add(report);
                    } else {
                        Slog.w(TAG, "Missing app error report, app = " + app.processName +
                                " crashing = " + crashing +
                                " notResponding = " + notResponding);
                    }
                }
            });
        }

        return errList[0];
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

        synchronized (mProcLock) {
            // Iterate across all processes
            return mProcessList.getRunningAppProcessesLOSP(allUsers, userId, allUids,
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
    public ParceledListSlice<ApplicationStartInfo> getHistoricalProcessStartReasons(
            String packageName, int maxNum, int userId) {
        enforceNotIsolatedCaller("getHistoricalProcessStartReasons");
        // For the simplification, we don't support USER_ALL nor USER_CURRENT here.
        if (userId == UserHandle.USER_ALL || userId == UserHandle.USER_CURRENT) {
            throw new IllegalArgumentException("Unsupported userId");
        }

        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        mUserController.handleIncomingUser(callingPid, callingUid, userId, true, ALLOW_NON_FULL,
                "getHistoricalProcessStartReasons", null);

        final ArrayList<ApplicationStartInfo> results = new ArrayList<ApplicationStartInfo>();
        if (!TextUtils.isEmpty(packageName)) {
            final int uid = enforceDumpPermissionForPackage(packageName, userId, callingUid,
                        "getHistoricalProcessStartReasons");
            if (uid != INVALID_UID) {
                mProcessList.getAppStartInfoTracker().getStartInfo(
                        packageName, uid, callingPid, maxNum, results);
            }
        } else {
            // If no package name is given, use the caller's uid as the filter uid.
            mProcessList.getAppStartInfoTracker().getStartInfo(
                    packageName, callingUid, callingPid, maxNum, results);
        }
        return new ParceledListSlice<ApplicationStartInfo>(results);
    }


    @Override
    public void addApplicationStartInfoCompleteListener(
            IApplicationStartInfoCompleteListener listener, int userId) {
        enforceNotIsolatedCaller("setApplicationStartInfoCompleteListener");

        // For the simplification, we don't support USER_ALL nor USER_CURRENT here.
        if (userId == UserHandle.USER_ALL || userId == UserHandle.USER_CURRENT) {
            throw new IllegalArgumentException("Unsupported userId");
        }

        final int callingUid = Binder.getCallingUid();
        mUserController.handleIncomingUser(Binder.getCallingPid(), callingUid, userId, true,
                ALLOW_NON_FULL, "addApplicationStartInfoCompleteListener", null);

        mProcessList.getAppStartInfoTracker().addStartInfoCompleteListener(listener,
                UserHandle.getUid(userId, UserHandle.getAppId(callingUid)));
    }


    @Override
    public void removeApplicationStartInfoCompleteListener(
            IApplicationStartInfoCompleteListener listener, int userId) {
        enforceNotIsolatedCaller("clearApplicationStartInfoCompleteListener");

        // For the simplification, we don't support USER_ALL nor USER_CURRENT here.
        if (userId == UserHandle.USER_ALL || userId == UserHandle.USER_CURRENT) {
            throw new IllegalArgumentException("Unsupported userId");
        }

        final int callingUid = Binder.getCallingUid();
        mUserController.handleIncomingUser(Binder.getCallingPid(), callingUid, userId, true,
                ALLOW_NON_FULL, "removeApplicationStartInfoCompleteListener", null);

        mProcessList.getAppStartInfoTracker().removeStartInfoCompleteListener(listener,
                UserHandle.getUid(userId, UserHandle.getAppId(callingUid)), true);
    }

    @Override
    public void addStartInfoTimestamp(int key, long timestampNs, int userId) {
        enforceNotIsolatedCaller("addStartInfoTimestamp");

        // For the simplification, we don't support USER_ALL nor USER_CURRENT here.
        if (userId == UserHandle.USER_ALL || userId == UserHandle.USER_CURRENT) {
            throw new IllegalArgumentException("Unsupported userId");
        }

        final int callingUid = Binder.getCallingUid();
        mUserController.handleIncomingUser(Binder.getCallingPid(), callingUid, userId, true,
                ALLOW_NON_FULL, "addStartInfoTimestamp", null);

        addStartInfoTimestampInternal(key, timestampNs, userId, callingUid);
    }

    private void addStartInfoTimestampInternal(int key, long timestampNs, int userId, int uid) {
        mProcessList.getAppStartInfoTracker().addTimestampToStart(
                Settings.getPackageNameForUid(mContext, uid),
                UserHandle.getUid(userId, UserHandle.getAppId(uid)),
                timestampNs,
                key);
    }

    @Override
    public ParceledListSlice<ApplicationExitInfo> getHistoricalProcessExitReasons(
            String packageName, int pid, int maxNum, int userId) {
        enforceNotIsolatedCaller("getHistoricalProcessExitReasons");

        // For the simplification, we don't support USER_ALL nor USER_CURRENT here.
        if (userId == UserHandle.USER_ALL || userId == UserHandle.USER_CURRENT) {
            throw new IllegalArgumentException("Unsupported userId");
        }

        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getCallingUserId();
        mUserController.handleIncomingUser(callingPid, callingUid, userId, true, ALLOW_NON_FULL,
                "getHistoricalProcessExitReasons", null);

        NativeTombstoneManager tombstoneService = LocalServices.getService(
                NativeTombstoneManager.class);

        final ArrayList<ApplicationExitInfo> results = new ArrayList<ApplicationExitInfo>();
        if (!TextUtils.isEmpty(packageName)) {
            final int uid = enforceDumpPermissionForPackage(packageName, userId, callingUid,
                      "getHistoricalProcessExitReasons");
            if (uid != INVALID_UID) {
                mProcessList.mAppExitInfoTracker.getExitInfo(
                        packageName, uid, pid, maxNum, results);
                tombstoneService.collectTombstones(results, uid, pid, maxNum);
            }
        } else {
            // If no package name is given, use the caller's uid as the filter uid.
            mProcessList.mAppExitInfoTracker.getExitInfo(
                    packageName, callingUid, pid, maxNum, results);
            tombstoneService.collectTombstones(results, callingUid, pid, maxNum);
        }

        return new ParceledListSlice<ApplicationExitInfo>(results);
    }

    @Override
    public void setProcessStateSummary(@Nullable byte[] state) {
        if (state != null && state.length > MAX_STATE_DATA_SIZE) {
            throw new IllegalArgumentException("Data size is too large");
        }
        mProcessList.mAppExitInfoTracker.setProcessStateSummary(Binder.getCallingUid(),
                Binder.getCallingPid(), state);
    }

    /**
     * Retrieves logs from specified logcat buffers and appends them to a StringBuilder
     * in the supplied order. The method executes a logcat command to fetch specific
     * log entries from the supplied buffers.
     *
     * @param sb the StringBuilder to append the logcat output to.
     * @param lines the number of lines to retrieve.
     * @param timeout the maximum allowed time in seconds for logcat to run before being terminated.
     * @param buffers the list of log buffers from which to retrieve logs.
     */
    private static void fetchLogcatBuffers(StringBuilder sb, int lines,
            int timeout, List<String> buffers) {

        if (buffers.size() == 0 || lines <= 0 || timeout <= 0) {
            return;
        }

        List<String> command = new ArrayList<>(10 + (2 * buffers.size()));
        // Time out after 10s of inactivity, but kill logcat with SEGV
        // so we can investigate why it didn't finish.
        command.add("/system/bin/timeout");
        command.add("-i");
        command.add("-s");
        command.add("SEGV");
        command.add(timeout + "s");

        // Merge several logcat streams, and take the last N lines.
        command.add("/system/bin/logcat");
        command.add("-v");
        // This adds a timestamp and thread info to each log line.
        command.add("threadtime");
        for (String buffer : buffers) {
            command.add("-b");
            command.add(buffer);
        }
        // Limit the output to the last N lines.
        command.add("-t");
        command.add(String.valueOf(lines));

        try {
            java.lang.Process proc =
                    new ProcessBuilder(command).redirectErrorStream(true).start();

            // Close the output stream immediately as we do not send input to the process.
            try {
                proc.getOutputStream().close();
            } catch (IOException e) {
            }

            try (InputStreamReader reader = new InputStreamReader(proc.getInputStream())) {
                char[] buffer = new char[8192];
                int numRead;
                while ((numRead = reader.read(buffer, 0, buffer.length)) > 0) {
                    sb.append(buffer, 0, numRead);
                }
            }
        } catch (IOException e) {
            Slog.e(TAG, "Error running logcat", e);
        }
    }

    /**
     * Check if the calling process has the permission to dump given package,
     * throw SecurityException if it doesn't have the permission.
     *
     * @return The real UID of process that can be dumped, or {@link android.os.Process#INVALID_UID}
     *         if the package is not found.
     */
    int enforceDumpPermissionForPackage(String packageName, int userId, int callingUid,
            String function) {
        // Allow SDK sandbox process to dump for its own process (under SDK sandbox package)
        try {
            if (Process.isSdkSandboxUid(callingUid)
                    && getPackageManager().getSdkSandboxPackageName().equals(packageName)) {
                return callingUid;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Could not get SDK sandbox package name");
        }
        final long identity = Binder.clearCallingIdentity();
        int uid = INVALID_UID;
        try {
            uid = mPackageManagerInt.getPackageUid(packageName,
                    MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        // If the uid is Process.INVALID_UID, the below 'if' check will be always true
        if (UserHandle.getAppId(uid) != UserHandle.getAppId(callingUid)) {
            // Requires the DUMP permission if the target package doesn't belong
            // to the caller or it doesn't exist.
            enforceCallingPermission(android.Manifest.permission.DUMP, function);
        }
        return uid;
    }

    @Override
    public void getMyMemoryState(ActivityManager.RunningAppProcessInfo outState) {
        if (outState == null) {
            throw new IllegalArgumentException("outState is null");
        }
        enforceNotIsolatedCaller("getMyMemoryState");

        final int callingUid = Binder.getCallingUid();
        final int clientTargetSdk = mPackageManagerInt.getUidTargetSdkVersion(callingUid);

        synchronized (mProcLock) {
            ProcessRecord proc;
            synchronized (mPidsSelfLocked) {
                proc = mPidsSelfLocked.get(Binder.getCallingPid());
            }
            if (proc != null) {
                mProcessList.fillInProcMemInfoLOSP(proc, outState, clientTargetSdk);
            }
        }
    }

    @Override
    public @MemFactor int getMemoryTrimLevel() {
        enforceNotIsolatedCaller("getMyMemoryState");
        synchronized (this) {
            return mAppProfiler.getLastMemoryLevelLocked();
        }
    }

    void setMemFactorOverride(@MemFactor int level) {
        synchronized (this) {
            if (level == mAppProfiler.getLastMemoryLevelLocked()) {
                return;
            }

            mAppProfiler.setMemFactorOverrideLocked(level);
            // Kick off an oom adj update since we forced a mem factor update.
            updateOomAdjLocked(OOM_ADJ_REASON_SHELL);
        }
    }

    /**
     * Toggle service restart backoff policy, used by {@link ActivityManagerShellCommand}.
     */
    void setServiceRestartBackoffEnabled(@NonNull String packageName, boolean enable,
            @NonNull String reason) {
        synchronized (this) {
            mServices.setServiceRestartBackoffEnabledLocked(packageName, enable, reason);
        }
    }

    /**
     * @return {@code false} if the given package has been disable from enforcing the service
     * restart backoff policy, used by {@link ActivityManagerShellCommand}.
     */
    boolean isServiceRestartBackoffEnabled(@NonNull String packageName) {
        synchronized (this) {
            return mServices.isServiceRestartBackoffEnabledLocked(packageName);
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != ROOT_UID && callingUid != Process.SHELL_UID) {
            resultReceiver.send(-1, null);
            throw new SecurityException("Shell commands are only callable by root or shell");
        }
        (new ActivityManagerShellCommand(this, false)).exec(
                this, in, out, err, args, callback, resultReceiver);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        PriorityDump.dump(mPriorityDumper, fd, pw, args);
    }

    private static final String TICK =
            "---------------------------------------"
            + "----------------------------------------";

    private void dumpEverything(FileDescriptor fd, PrintWriter pw, String[] args, int opti,
            boolean dumpAll, String dumpPackage, int displayIdFilter, boolean dumpClient,
            boolean dumpNormalPriority, int dumpAppId, boolean dumpProxies) {

        ActiveServices.ServiceDumper sdumper;

        synchronized(this) {
            mConstants.dump(pw);
            synchronized (mProcLock) {
                mOomAdjuster.dumpCachedAppOptimizerSettings(pw);
            }
            mOomAdjuster.dumpCacheOomRankerSettings(pw);
            pw.println();
            if (dumpAll) {
                pw.println(
                        "-------------------------------------------------------------------------------");
            }
            dumpAllowedAssociationsLocked(fd, pw, args, opti, dumpAll, dumpPackage);
            pw.println();
            if (dumpAll) {
                pw.println(
                        "-------------------------------------------------------------------------------");
            }
            mPendingIntentController.dumpPendingIntents(pw, dumpAll, dumpPackage);
            pw.println();
            if (dumpAll) {
                pw.println(
                        "-------------------------------------------------------------------------------");
            }
            dumpBroadcastsLocked(fd, pw, args, opti, dumpAll, dumpPackage);
            pw.println();
            if (dumpAll) {
                pw.println(
                        "-------------------------------------------------------------------------------");
            }
            if (dumpAll || dumpPackage != null) {
                dumpBroadcastStatsLocked(fd, pw, args, opti, dumpAll, dumpPackage);
                pw.println();
                if (dumpAll) {
                    pw.println(
                            "-------------------------------------------------------------------------------");
                }
            }
            mCpHelper.dumpProvidersLocked(fd, pw, args, opti, dumpAll, dumpPackage);
            pw.println();
            if (dumpAll) {
                pw.println(
                        "-------------------------------------------------------------------------------");
            }
            dumpPermissions(fd, pw, args, opti, dumpAll, dumpPackage);
            pw.println();
            sdumper = mServices.newServiceDumperLocked(fd, pw, args, opti, dumpAll, dumpPackage);
            if (!dumpClient) {
                if (dumpAll) {
                    pw.println(
                            "-------------------------------------------------------------------------------");
                }
                sdumper.dumpLocked();
            }
        }

        // No need to hold the lock.
        pw.println(TICK);
        AnrTimer.dump(pw, false);

        // We drop the lock here because we can't call dumpWithClient() with the lock held;
        // if the caller wants a consistent state for the !dumpClient case, it can call this
        // method with the lock held.
        if (dumpClient) {
            if (dumpAll) {
                pw.println(
                        "-------------------------------------------------------------------------------");
            }
            sdumper.dumpWithClient();
        }
        if (dumpPackage == null && dumpProxies) {
            // Intentionally dropping the lock for this, because dumpBinderProxies() will make many
            // outgoing binder calls to retrieve interface descriptors; while that is system code,
            // there is nothing preventing an app from overriding this implementation by talking to
            // the binder driver directly, and hang up system_server in the process. So, dump
            // without locks held, and even then only when there is an unreasonably large number of
            // proxies in the first place.
            pw.println();
            if (dumpAll) {
                pw.println(
                        "-------------------------------------------------------------------------------");
            }
            dumpBinderProxies(pw, BINDER_PROXY_HIGH_WATERMARK /* minToDump */);
        }
        synchronized(this) {
            pw.println();
            if (dumpAll) {
                pw.println(
                        "-------------------------------------------------------------------------------");
            }
            mAtmInternal.dump(DUMP_RECENTS_CMD, fd, pw, args, opti, dumpAll, dumpClient,
                    dumpPackage, displayIdFilter);
            pw.println();
            if (dumpAll) {
                pw.println(
                        "-------------------------------------------------------------------------------");
            }
            mAtmInternal.dump(DUMP_LASTANR_CMD, fd, pw, args, opti, dumpAll, dumpClient,
                    dumpPackage, displayIdFilter);
            pw.println();
            if (dumpAll) {
                pw.println(
                        "-------------------------------------------------------------------------------");
            }
            mAtmInternal.dump(DUMP_STARTER_CMD, fd, pw, args, opti, dumpAll, dumpClient,
                    dumpPackage, displayIdFilter);
            if (dumpPackage == null) {
                pw.println();
                if (dumpAll) {
                    pw.println(
                            "-------------------------------------------------------------------------------");
                }
                mAtmInternal.dump(DUMP_CONTAINERS_CMD, fd, pw, args, opti, dumpAll, dumpClient,
                        dumpPackage, displayIdFilter);
            }
            // Activities section is dumped as part of the Critical priority dump. Exclude the
            // section if priority is Normal.
            if (!dumpNormalPriority) {
                pw.println();
                if (dumpAll) {
                    pw.println(
                            "-------------------------------------------------------------------------------");
                }
                mAtmInternal.dump(DUMP_ACTIVITIES_CMD, fd, pw, args, opti, dumpAll, dumpClient,
                        dumpPackage, displayIdFilter);
            }
            if (mAssociations.size() > 0) {
                pw.println();
                if (dumpAll) {
                    pw.println(
                            "-------------------------------------------------------------------------------");
                }
                dumpAssociationsLocked(fd, pw, args, opti, dumpAll, dumpClient, dumpPackage);
            }
            pw.println();
            if (dumpAll) {
                pw.println(
                        "-------------------------------------------------------------------------------");
                mProcessList.getAppStartInfoTracker().dumpHistoryProcessStartInfo(pw, dumpPackage);
                pw.println(
                        "-------------------------------------------------------------------------------");
                mProcessList.mAppExitInfoTracker.dumpHistoryProcessExitInfo(pw, dumpPackage);
            }
            if (dumpPackage == null) {
                pw.println();
                if (dumpAll) {
                    pw.println(
                            "-------------------------------------------------------------------------------");
                }
                dumpLmkLocked(pw);
            }
            pw.println();
            if (dumpAll) {
                pw.println(
                        "-------------------------------------------------------------------------------");
            }
            synchronized (mProcLock) {
                mProcessList.dumpProcessesLSP(fd, pw, args, opti, dumpAll, dumpPackage, dumpAppId);
            }
            pw.println();
            if (dumpAll) {
                pw.println(
                        "-------------------------------------------------------------------------------");
            }
            dumpUsers(pw);

            pw.println();
            if (dumpAll) {
                pw.println(
                        "-------------------------------------------------------------------------------");
            }
            mComponentAliasResolver.dump(pw);
        }
    }

    /**
     * Dump the app restriction controller, it's required not to hold the global lock here.
     */
    private void dumpAppRestrictionController(PrintWriter pw) {
        pw.println(
                "-------------------------------------------------------------------------------");
        mAppRestrictionController.dump(pw, "");
    }

    void dumpAppRestrictionController(ProtoOutputStream proto, int uid) {
        mAppRestrictionController.dumpAsProto(proto, uid);
    }

    /**
     * Wrapper function to print out debug data filtered by specified arguments.
    */
    @NeverCompile // Avoid size overhead of debugging code.
    private void doDump(FileDescriptor fd, PrintWriter pw, String[] args, boolean useProto) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw)) return;

        boolean dumpAll = false;
        boolean dumpClient = false;
        boolean dumpCheckin = false;
        boolean dumpCheckinFormat = false;
        boolean dumpNormalPriority = false;
        boolean dumpVisibleStacksOnly = false;
        boolean dumpFocusedStackOnly = false;
        int dumpDisplayId = INVALID_DISPLAY;
        String dumpPackage = null;
        int dumpUserId = UserHandle.USER_ALL;

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
            } else if ("--user".equals(opt)) {
                if (opti < args.length) {
                    dumpUserId = UserHandle.parseUserArg(args[opti]);
                    if (dumpUserId == UserHandle.USER_CURRENT) {
                        dumpUserId = mUserController.getCurrentUserId();
                    }
                    opti++;
                } else {
                    pw.println("Error: --user option requires user id argument");
                    return;
                }
            } else if ("-d".equals(opt)) {
                if (opti < args.length) {
                    dumpDisplayId = Integer.parseInt(args[opti]);
                    if (dumpDisplayId == INVALID_DISPLAY) {
                        pw.println("Error: -d cannot be used with INVALID_DISPLAY");
                        return;
                    }
                    opti++;
                } else {
                    pw.println("Error: -d option requires display argument");
                    return;
                }
                dumpClient = true;
            } else if ("-h".equals(opt)) {
                ActivityManagerShellCommand.dumpHelp(pw, true);
                return;
            } else {
                pw.println("Unknown argument: " + opt + "; use -h for help");
                return;
            }
        }

        final long origId = Binder.clearCallingIdentity();

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
                if (!mCpHelper.dumpProviderProto(fd, pw, name, newArgs)) {
                    pw.println("No providers match: " + name);
                    pw.println("Use -h for help.");
                }
            } else if ("service".equals(cmd)) {
                // output proto is ActivityManagerServiceDumpServicesProto
                mServices.dumpDebug(proto, ActivityManagerServiceDumpServicesProto.ACTIVE_SERVICES);
            } else if ("processes".equals(cmd) || "p".equals(cmd)) {
                if (opti < args.length) {
                    dumpPackage = args[opti];
                    opti++;
                }
                // output proto is ProcessProto
                synchronized (this) {
                    synchronized (mProcLock) {
                        mProcessList.writeProcessesToProtoLSP(proto, dumpPackage);
                    }
                }
            } else if ("app-restrictions".equals(cmd)) {
                int uid = Process.INVALID_UID;
                boolean error = false;
                for (int i = 0; i < args.length; i++) {
                    if ("--uid".equals(args[i])) {
                        if (i + 1 < args.length) {
                            try {
                                uid = Integer.parseInt(args[i + 1]);
                            } catch (NumberFormatException e) {
                                error = true;
                            }
                        } else {
                            error = true;
                        }
                        break;
                    }
                }
                if (error) {
                    pw.println("Invalid --uid argument");
                    pw.println("Use -h for help.");
                } else {
                    dumpAppRestrictionController(proto, uid);
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
                    mServices.dumpDebug(proto,
                            ActivityManagerServiceDumpServicesProto.ACTIVE_SERVICES);
                    proto.end(serviceToken);

                    long processToken = proto.start(ActivityManagerServiceProto.PROCESSES);
                    synchronized (mProcLock) {
                        mProcessList.writeProcessesToProtoLSP(proto, dumpPackage);
                    }
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
                    || DUMP_RECENTS_CMD.equals(cmd) || DUMP_RECENTS_SHORT_CMD.equals(cmd)
                    || DUMP_TOP_RESUMED_ACTIVITY.equals(cmd)
                    || DUMP_VISIBLE_ACTIVITIES.equals(cmd)) {
                mAtmInternal.dump(cmd, fd, pw, args, opti, /* dumpAll= */ true , dumpClient,
                        dumpPackage, dumpDisplayId);
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
                    dumpBroadcastsLocked(fd, pw, args, opti, /* dumpAll= */ true, dumpPackage);
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
                mPendingIntentController.dumpPendingIntents(pw, true, dumpPackage);
            } else if ("processes".equals(cmd) || "p".equals(cmd)) {
                if (opti < args.length) {
                    dumpPackage = args[opti];
                    opti++;
                }
                synchronized (this) {
                    synchronized (mProcLock) {
                        mProcessList.dumpProcessesLSP(
                                fd, pw, args, opti, true, dumpPackage, dumpAppId);
                    }
                }
            } else if ("oom".equals(cmd) || "o".equals(cmd)) {
                synchronized (this) {
                    mProcessList.dumpOomLocked(fd, pw, false, args, opti, true, dumpPackage, true);
                }
            } else if ("lmk".equals(cmd)) {
                synchronized (this) {
                    dumpLmkLocked(pw);
                }
            } else if ("lru".equals(cmd)) {
                synchronized (this) {
                    mProcessList.dumpLruLocked(pw, dumpPackage, null);
                }
            } else if ("permissions".equals(cmd) || "perm".equals(cmd)) {
                dumpPermissions(fd, pw, args, opti, true, dumpPackage);
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
                if (!mCpHelper.dumpProvider(fd, pw, name, newArgs, 0, dumpAll)) {
                    pw.println("No providers match: " + name);
                    pw.println("Use -h for help.");
                }
            } else if ("providers".equals(cmd) || "prov".equals(cmd)) {
                synchronized (this) {
                    mCpHelper.dumpProvidersLocked(fd, pw, args, opti, true, dumpPackage);
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
                int[] users = dumpUserId == UserHandle.USER_ALL ? null : new int[] { dumpUserId };
                if (!mServices.dumpService(fd, pw, name, users, newArgs, 0, dumpAll)) {
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
                }
                synchronized (mProcLock) {
                    mOomAdjuster.dumpCachedAppOptimizerSettings(pw);
                    mOomAdjuster.dumpCacheOomRankerSettings(pw);
                }
            } else if ("timers".equals(cmd)) {
                AnrTimer.dump(pw, true);
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
            } else if ("users".equals(cmd)) {
                dumpUsers(pw);
            } else if ("start-info".equals(cmd)) {
                if (opti < args.length) {
                    dumpPackage = args[opti];
                    opti++;
                }
                mProcessList.getAppStartInfoTracker().dumpHistoryProcessStartInfo(pw, dumpPackage);
            } else if ("exit-info".equals(cmd)) {
                if (opti < args.length) {
                    dumpPackage = args[opti];
                    opti++;
                }
                mProcessList.mAppExitInfoTracker.dumpHistoryProcessExitInfo(pw, dumpPackage);
            } else if ("component-alias".equals(cmd)) {
                mComponentAliasResolver.dump(pw);
            } else {
                // Dumping a single activity?
                if (!mAtmInternal.dumpActivity(fd, pw, cmd, args, opti, dumpAll,
                        dumpVisibleStacksOnly, dumpFocusedStackOnly, dumpDisplayId, dumpUserId)) {
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
                dumpEverything(fd, pw, args, opti, dumpAll, dumpPackage, dumpDisplayId,
                        dumpClient, dumpNormalPriority, dumpAppId, /* dumpProxies= */ true);
            } else {
                // Take the lock here, so we get a consistent state for the entire dump;
                // dumpEverything() will take the lock as well, which is fine for everything
                // except dumping proxies, which can take a long time; exclude them.
                synchronized(this) {
                    dumpEverything(fd, pw, args, opti, dumpAll, dumpPackage, dumpDisplayId,
                            dumpClient, dumpNormalPriority, dumpAppId, /* dumpProxies= */ false);
                }
            }
            if (dumpAll) {
                dumpAppRestrictionController(pw);
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

    int getAppId(String dumpPackage) {
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

    @GuardedBy("this")
    boolean dumpActiveInstruments(PrintWriter pw, String dumpPackage, boolean needSep) {
        final int size = mActiveInstrumentation.size();
        if (size > 0) {
            boolean printed = false;
            for (int i = 0; i < size; i++) {
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
        return needSep;
    }

    @NeverCompile // Avoid size overhead of debugging code.
    @GuardedBy({"this", "mProcLock"})
    void dumpOtherProcessesInfoLSP(FileDescriptor fd, PrintWriter pw,
            boolean dumpAll, String dumpPackage, int dumpAppId, int numPers, boolean needSep) {
        if (dumpAll || dumpPackage != null) {
            final SparseArray<ProcessRecord> pidToProcess = new SparseArray<>();
            synchronized (mPidsSelfLocked) {
                boolean printed = false;
                for (int i = 0, size = mPidsSelfLocked.size(); i < size; i++) {
                    ProcessRecord r = mPidsSelfLocked.valueAt(i);
                    pidToProcess.put(r.getPid(), r);
                    if (dumpPackage != null && !r.getPkgList().containsKey(dumpPackage)) {
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

            synchronized (sActiveProcessInfoSelfLocked) {
                boolean printed = false;
                for (int i = 0, size = sActiveProcessInfoSelfLocked.size(); i < size; i++) {
                    ProcessInfo info = sActiveProcessInfoSelfLocked.valueAt(i);
                    ProcessRecord r = pidToProcess.get(sActiveProcessInfoSelfLocked.keyAt(i));
                    if (r != null && dumpPackage != null
                            && !r.getPkgList().containsKey(dumpPackage)) {
                        continue;
                    }
                    if (!printed) {
                        if (needSep) pw.println();
                        needSep = true;
                        pw.println("  Active process infos:");
                        printed = true;
                    }
                    pw.print("    Pinfo PID #"); pw.print(sActiveProcessInfoSelfLocked.keyAt(i));
                    pw.println(":");
                    pw.print("      name="); pw.println(info.name);
                    if (info.deniedPermissions != null) {
                        for (int j = 0; j < info.deniedPermissions.size(); j++) {
                            pw.print("      deny: ");
                            pw.println(info.deniedPermissions.valueAt(j));
                        }
                    }
                }
            }
        }

        if (dumpAll) {
            mPhantomProcessList.dump(pw, "  ");
        }

        if (mImportantProcesses.size() > 0) {
            synchronized (mPidsSelfLocked) {
                boolean printed = false;
                for (int i = 0, size = mImportantProcesses.size(); i < size; i++) {
                    ProcessRecord r = mPidsSelfLocked.get(mImportantProcesses.valueAt(i).pid);
                    if (dumpPackage != null && (r == null
                            || !r.getPkgList().containsKey(dumpPackage))) {
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

        needSep = mAppErrors.dumpLPr(fd, pw, needSep, dumpPackage);

        needSep = mAtmInternal.dumpForProcesses(fd, pw, dumpAll, dumpPackage, dumpAppId, needSep,
                mAppProfiler.getTestPssMode(), mWakefulness.get());

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
            mUidObserverController.dump(pw, dumpPackage);

            pw.println("  mDeviceIdleAllowlist=" + Arrays.toString(mDeviceIdleAllowlist));
            pw.println("  mDeviceIdleExceptIdleAllowlist="
                    + Arrays.toString(mDeviceIdleExceptIdleAllowlist));
            pw.println("  mDeviceIdleTempAllowlist=" + Arrays.toString(mDeviceIdleTempAllowlist));
            if (mPendingTempAllowlist.size() > 0) {
                pw.println("  mPendingTempAllowlist:");
                for (int i = 0, size = mPendingTempAllowlist.size(); i < size; i++) {
                    PendingTempAllowlist ptw = mPendingTempAllowlist.valueAt(i);
                    pw.print("    ");
                    UserHandle.formatUid(pw, ptw.targetUid);
                    pw.print(": ");
                    TimeUtils.formatDuration(ptw.duration, pw);
                    pw.print(" ");
                    pw.println(ptw.tag);
                    pw.print(" ");
                    pw.print(ptw.type);
                    pw.print(" ");
                    pw.print(ptw.reasonCode);
                    pw.print(" ");
                    pw.print(ptw.callingUid);
                }
            }
            pw.println("  mFgsStartTempAllowList:");
            final long currentTimeNow = System.currentTimeMillis();
            final long elapsedRealtimeNow = SystemClock.elapsedRealtime();
            mFgsStartTempAllowList.forEach((uid, entry) -> {
                pw.print("    " + UserHandle.formatUid(uid) + ": ");
                entry.second.dump(pw);
                pw.print(" expiration=");
                // Convert entry.mExpirationTime, which is an elapsed time since boot,
                // to a time since epoch (i.e. System.currentTimeMillis()-based time.)
                final long expirationInCurrentTime =
                        currentTimeNow - elapsedRealtimeNow + entry.first;
                TimeUtils.dumpTimeWithDelta(pw, expirationInCurrentTime, currentTimeNow);
                pw.println();
            });

            if (!mProcessList.mAppsInBackgroundRestricted.isEmpty()) {
                pw.println("  Processes that are in background restricted:");
                for (int i = 0, size = mProcessList.mAppsInBackgroundRestricted.size();
                        i < size; i++) {
                    pw.println(String.format("%s #%2d: %s", "    ", i,
                            mProcessList.mAppsInBackgroundRestricted.valueAt(i).toString()));
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
        synchronized (mAppProfiler.mProfilerLock) {
            needSep = mAppProfiler.dumpMemWatchProcessesLPf(pw, needSep);
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
        needSep = mAppProfiler.dumpProfileDataLocked(pw, dumpPackage, needSep);
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
            if (mAllowSpecifiedFifoScheduling) {
                pw.println("  mAllowSpecifiedFifoScheduling=true");
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
                mAppProfiler.dumpMemoryLevelsLocked(pw);
                long now = SystemClock.uptimeMillis();
                pw.print("  mLastIdleTime=");
                        TimeUtils.formatDuration(now, mLastIdleTime, pw);
                        pw.print(" mLowRamSinceLastIdle=");
                        TimeUtils.formatDuration(
                                mAppProfiler.getLowRamTimeSinceIdleLPr(now), pw);
                        pw.println();

                pw.println();
                pw.println("  ServiceManager statistics:");
                ServiceManager.sStatLogger.dump(pw, "    ");
                pw.println();
            }
        }
        pw.println("  mForceBackgroundCheck=" + mForceBackgroundCheck);
    }

    private void dumpUsers(PrintWriter pw) {
        pw.println("ACTIVITY MANAGER USERS (dumpsys activity users)");
        mUserController.dump(pw);
    }

    @GuardedBy({"this", "mProcLock"})
    void writeOtherProcessesInfoToProtoLSP(ProtoOutputStream proto, String dumpPackage,
            int dumpAppId, int numPers) {
        for (int i = 0, size = mActiveInstrumentation.size(); i < size; i++) {
            ActiveInstrumentation ai = mActiveInstrumentation.get(i);
            if (dumpPackage != null && !ai.mClass.getPackageName().equals(dumpPackage)
                    && !ai.mTargetInfo.packageName.equals(dumpPackage)) {
                continue;
            }
            ai.dumpDebug(proto,
                    ActivityManagerServiceDumpProcessesProto.ACTIVE_INSTRUMENTATIONS);
        }

        mUidObserverController.dumpValidateUidsProto(proto, dumpPackage, dumpAppId,
                ActivityManagerServiceDumpProcessesProto.VALIDATE_UIDS);

        if (dumpPackage != null) {
            synchronized (mPidsSelfLocked) {
                for (int i = 0, size = mPidsSelfLocked.size(); i < size; i++) {
                    ProcessRecord r = mPidsSelfLocked.valueAt(i);
                    if (!r.getPkgList().containsKey(dumpPackage)) {
                        continue;
                    }
                    r.dumpDebug(proto,
                            ActivityManagerServiceDumpProcessesProto.PIDS_SELF_LOCKED);
                }
            }
        }

        if (mImportantProcesses.size() > 0) {
            synchronized (mPidsSelfLocked) {
                for (int i = 0, size = mImportantProcesses.size(); i < size; i++) {
                    ImportanceToken it = mImportantProcesses.valueAt(i);
                    ProcessRecord r = mPidsSelfLocked.get(it.pid);
                    if (dumpPackage != null && (r == null
                            || !r.getPkgList().containsKey(dumpPackage))) {
                        continue;
                    }
                    it.dumpDebug(proto,
                            ActivityManagerServiceDumpProcessesProto.IMPORTANT_PROCS);
                }
            }
        }

        for (int i = 0, size = mPersistentStartingProcesses.size(); i < size; i++) {
            ProcessRecord r = mPersistentStartingProcesses.get(i);
            if (dumpPackage != null && !dumpPackage.equals(r.info.packageName)) {
                continue;
            }
            r.dumpDebug(proto,
                    ActivityManagerServiceDumpProcessesProto.PERSISTENT_STARTING_PROCS);
        }

        for (int i = 0, size = mProcessList.mRemovedProcesses.size(); i < size; i++) {
            ProcessRecord r = mProcessList.mRemovedProcesses.get(i);
            if (dumpPackage != null && !dumpPackage.equals(r.info.packageName)) {
                continue;
            }
            r.dumpDebug(proto, ActivityManagerServiceDumpProcessesProto.REMOVED_PROCS);
        }

        for (int i = 0, size = mProcessesOnHold.size(); i < size; i++) {
            ProcessRecord r = mProcessesOnHold.get(i);
            if (dumpPackage != null && !dumpPackage.equals(r.info.packageName)) {
                continue;
            }
            r.dumpDebug(proto, ActivityManagerServiceDumpProcessesProto.ON_HOLD_PROCS);
        }

        synchronized (mAppProfiler.mProfilerLock) {
            mAppProfiler.writeProcessesToGcToProto(proto,
                    ActivityManagerServiceDumpProcessesProto.GC_PROCS,
                    dumpPackage);
        }
        mAppErrors.dumpDebugLPr(proto, ActivityManagerServiceDumpProcessesProto.APP_ERRORS,
                dumpPackage);
        mAtmInternal.writeProcessesToProto(proto, dumpPackage, mWakefulness.get(),
                mAppProfiler.getTestPssMode());

        if (dumpPackage == null) {
            mUserController.dumpDebug(proto,
            ActivityManagerServiceDumpProcessesProto.USER_CONTROLLER);
        }

        mUidObserverController.dumpDebug(proto, dumpPackage);

        for (int v : mDeviceIdleAllowlist) {
            proto.write(ActivityManagerServiceDumpProcessesProto.DEVICE_IDLE_WHITELIST, v);
        }

        for (int v : mDeviceIdleTempAllowlist) {
            proto.write(ActivityManagerServiceDumpProcessesProto.DEVICE_IDLE_TEMP_WHITELIST, v);
        }

        if (mPendingTempAllowlist.size() > 0) {
            for (int i = 0, size = mPendingTempAllowlist.size(); i < size; i++) {
                mPendingTempAllowlist.valueAt(i).dumpDebug(proto,
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

        synchronized (mAppProfiler.mProfilerLock) {
            mAppProfiler.writeMemWatchProcessToProtoLPf(proto);
        }

        if (mTrackAllocationApp != null) {
            if (dumpPackage == null || dumpPackage.equals(mTrackAllocationApp)) {
                proto.write(ActivityManagerServiceDumpProcessesProto.TRACK_ALLOCATION_APP,
                        mTrackAllocationApp);
            }
        }

        mAppProfiler.writeProfileDataToProtoLocked(proto, dumpPackage);

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
            mAppProfiler.writeMemoryLevelsToProtoLocked(proto);
            long now = SystemClock.uptimeMillis();
            ProtoUtils.toDuration(proto, ActivityManagerServiceDumpProcessesProto.LAST_IDLE_TIME, mLastIdleTime, now);
            proto.write(ActivityManagerServiceDumpProcessesProto.LOW_RAM_SINCE_LAST_IDLE_MS,
                    mAppProfiler.getLowRamTimeSinceIdleLPr(now));
        }
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
                r.dumpDebug(proto, ActivityManagerServiceDumpBroadcastsProto.RECEIVER_LIST);
            }
        }
        mReceiverResolver.dumpDebug(proto, ActivityManagerServiceDumpBroadcastsProto.RECEIVER_RESOLVER);
        mBroadcastQueue.dumpDebug(proto, ActivityManagerServiceDumpBroadcastsProto.BROADCAST_QUEUE);
        synchronized (mStickyBroadcasts) {
            for (int user = 0; user < mStickyBroadcasts.size(); user++) {
                long token = proto.start(
                        ActivityManagerServiceDumpBroadcastsProto.STICKY_BROADCASTS);
                proto.write(StickyBroadcastProto.USER, mStickyBroadcasts.keyAt(user));
                for (Map.Entry<String, ArrayList<StickyBroadcast>> ent
                        : mStickyBroadcasts.valueAt(user).entrySet()) {
                    long actionToken = proto.start(StickyBroadcastProto.ACTIONS);
                    proto.write(StickyBroadcastProto.StickyAction.NAME, ent.getKey());
                    for (StickyBroadcast broadcast : ent.getValue()) {
                        broadcast.intent.dumpDebug(proto, StickyBroadcastProto.StickyAction.INTENTS,
                                false, true, true, false);
                    }
                    proto.end(actionToken);
                }
                proto.end(token);
            }
        }

        long handlerToken = proto.start(ActivityManagerServiceDumpBroadcastsProto.HANDLER);
        proto.write(ActivityManagerServiceDumpBroadcastsProto.MainHandler.HANDLER, mHandler.toString());
        mHandler.getLooper().dumpDebug(proto,
            ActivityManagerServiceDumpBroadcastsProto.MainHandler.LOOPER);
        proto.end(handlerToken);
    }

    void dumpAllowedAssociationsLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage) {
        pw.println(
                "ACTIVITY MANAGER ALLOWED ASSOCIATION STATE (dumpsys activity"
                    + " allowed-associations)");
        boolean printed = false;
        if (mAllowedAssociations != null) {
            for (int i = 0; i < mAllowedAssociations.size(); i++) {
                final String pkg = mAllowedAssociations.keyAt(i);
                final ArraySet<String> asc =
                        mAllowedAssociations.valueAt(i).getAllowedPackageAssociations();
                if (!printed) {
                    pw.println("  Allowed associations (by restricted package):");
                    printed = true;
                }
                pw.print("  * ");
                pw.print(pkg);
                pw.println(":");
                for (int j = 0; j < asc.size(); j++) {
                    if (dumpPackage == null || pkg.equals(dumpPackage)
                            || asc.valueAt(j).equals(dumpPackage)) {
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

    @NeverCompile
    void dumpBroadcastsLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage) {
        boolean dumpConstants = true;
        boolean dumpHistory = true;
        boolean needSep = false;
        boolean onlyHistory = false;
        boolean printedAnything = false;
        boolean onlyReceivers = false;
        int filteredUid = Process.INVALID_UID;

        if ("history".equals(dumpPackage)) {
            if (opti < args.length && "-s".equals(args[opti])) {
                dumpAll = false;
            }
            onlyHistory = true;
            dumpPackage = null;
        }
        if ("receivers".equals(dumpPackage)) {
            onlyReceivers = true;
            dumpPackage = null;
            if (opti + 2 <= args.length) {
                for (int i = opti; i < args.length; i++) {
                    String arg = args[i];
                    switch (arg) {
                        case "--uid":
                            filteredUid = getIntArg(pw, args, ++i, Process.INVALID_UID);
                            if (filteredUid == Process.INVALID_UID) {
                                return;
                            }
                            break;
                        default:
                            pw.printf("Invalid argument at index %d: %s\n", i, arg);
                            return;
                    }
                }
            }
        }
        if (DEBUG_BROADCAST) {
            Slogf.d(TAG_BROADCAST, "dumpBroadcastsLocked(): dumpPackage=%s, onlyHistory=%b, "
                    + "onlyReceivers=%b, filteredUid=%d", dumpPackage, onlyHistory, onlyReceivers,
                    filteredUid);
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
                    if (filteredUid != Process.INVALID_UID && filteredUid != r.app.uid) {
                        if (DEBUG_BROADCAST) {
                            Slogf.v(TAG_BROADCAST, "dumpBroadcastsLocked(): skipping receiver whose"
                                    + " uid (%d) is not %d: %s", r.app.uid, filteredUid, r.app);
                        }
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
            } else {
                if (onlyReceivers) {
                    pw.println("  (no registered receivers)");
                }
            }

            if (!onlyReceivers) {
                if (mReceiverResolver.dump(pw, needSep
                        ? "\n  Receiver Resolver Table:" : "  Receiver Resolver Table:",
                        "    ", dumpPackage, false, false)) {
                    needSep = true;
                    printedAnything = true;
                }
            }
        }

        if (!onlyReceivers) {
            needSep = mBroadcastQueue.dumpLocked(fd, pw, args, opti,
                    dumpConstants, dumpHistory, dumpAll, dumpPackage, needSep);
            printedAnything |= needSep;
        }

        needSep = true;

        synchronized (mStickyBroadcasts) {
            if (!onlyHistory && !onlyReceivers && mStickyBroadcasts != null
                    && dumpPackage == null) {
                for (int user = 0; user < mStickyBroadcasts.size(); user++) {
                    if (needSep) {
                        pw.println();
                    }
                    needSep = true;
                    printedAnything = true;
                    pw.print("  Sticky broadcasts for user ");
                    pw.print(mStickyBroadcasts.keyAt(user));
                    pw.println(":");
                    StringBuilder sb = new StringBuilder(128);
                    for (Map.Entry<String, ArrayList<StickyBroadcast>> ent
                            : mStickyBroadcasts.valueAt(user).entrySet()) {
                        pw.print("  * Sticky action ");
                        pw.print(ent.getKey());
                        if (dumpAll) {
                            pw.println(":");
                            ArrayList<StickyBroadcast> broadcasts = ent.getValue();
                            final int N = broadcasts.size();
                            for (int i = 0; i < N; i++) {
                                final Intent intent = broadcasts.get(i).intent;
                                final boolean deferUntilActive = broadcasts.get(i).deferUntilActive;
                                sb.setLength(0);
                                sb.append("    Intent: ");
                                intent.toShortString(sb, false, true, false, false);
                                pw.print(sb);
                                if (deferUntilActive) {
                                    pw.print(" [D]");
                                }
                                pw.println();
                                pw.print("      originalCallingUid: ");
                                pw.println(broadcasts.get(i).originalCallingUid);
                                pw.println();
                                Bundle bundle = intent.getExtras();
                                if (bundle != null) {
                                    pw.print("      extras: ");
                                    pw.println(bundle);
                                }
                            }
                        } else {
                            pw.println("");
                        }
                    }
                }
            }
        }

        if (!onlyHistory && !onlyReceivers && dumpAll) {
            pw.println();
            pw.println("  Queue " + mBroadcastQueue.toString() + ": "
                    + mBroadcastQueue.describeStateLocked());
            pw.println("  mHandler:");
            mHandler.dump(new PrintWriterPrinter(pw), "    ");
            needSep = true;
            printedAnything = true;
        }

        if (!printedAnything) {
            pw.println("  (nothing)");
        }
    }

    @NeverCompile
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

    @NeverCompile
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

    void dumpPermissions(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage) {

        pw.println("ACTIVITY MANAGER URI PERMISSIONS (dumpsys activity permissions)");

        mUgmInternal.dump(pw, dumpAll, dumpPackage);
    }

    private static int dumpProcessList(PrintWriter pw,
            ActivityManagerService service, List list,
            String prefix, String normalLabel, String persistentLabel,
            String dumpPackage) {
        int numPers = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            ProcessRecord r = (ProcessRecord) list.get(i);
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

    ArrayList<ProcessRecord> collectProcesses(PrintWriter pw, int start, boolean allPkgs,
            String[] args) {
        synchronized (mProcLock) {
            return mProcessList.collectProcessesLOSP(start, allPkgs, args);
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
            final int pid = r.getPid();
            final IApplicationThread thread = r.getThread();
            if (thread != null) {
                pw.println("\n** Graphics info for pid " + pid + " [" + r.processName + "] **");
                pw.flush();
                try {
                    TransferPipe tp = new TransferPipe();
                    try {
                        thread.dumpGfxInfo(tp.getWriteFd(), args);
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

    final void dumpBinderCacheContents(FileDescriptor fd, PrintWriter pw, String[] args) {
        ArrayList<ProcessRecord> procs = collectProcesses(pw, 0, false, args);
        if (procs == null) {
            pw.println("No process found for: " + args[0]);
            return;
        }

        pw.println("Per-process Binder Cache Contents");

        for (int i = procs.size() - 1; i >= 0; i--) {
            ProcessRecord r = procs.get(i);
            final int pid = r.getPid();
            final IApplicationThread thread = r.getThread();
            if (thread != null) {
                pw.println("\n\n** Cache info for pid " + pid + " [" + r.processName + "] **");
                pw.flush();
                try {
                    if (pid == Process.myPid()) {
                        // Directly dump to target fd for local dump to avoid hang.
                        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromFd(fd.getInt$())) {
                            thread.dumpCacheInfo(pfd, args);
                        }
                        continue;
                    }
                    TransferPipe tp = new TransferPipe();
                    try {
                        thread.dumpCacheInfo(tp.getWriteFd(), args);
                        tp.go(fd);
                    } finally {
                        tp.kill();
                    }
                } catch (IOException e) {
                    pw.println("Failure while dumping the app " + r);
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
            final int pid = r.getPid();
            final IApplicationThread thread = r.getThread();
            if (thread != null) {
                pw.println("\n** Database info for pid " + pid + " [" + r.processName + "] **");
                pw.flush();
                try {
                    TransferPipe tp = new TransferPipe();
                    try {
                        thread.dumpDbInfo(tp.getWriteFd(), args);
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
        final long mRss;
        final long mPrivateDirty;
        final int id; // pid
        final int userId;
        final boolean hasActivities;
        ArrayList<MemItem> subitems;

        MemItem(String label, String shortLabel, long pss, long swapPss, long rss,
                long privateDirty, int id, @UserIdInt int userId,
                boolean hasActivities) {
            this.isProc = true;
            this.label = label;
            this.shortLabel = shortLabel;
            this.pss = pss;
            this.swapPss = swapPss;
            this.mRss = rss;
            this.mPrivateDirty = privateDirty;
            this.id = id;
            this.userId = userId;
            this.hasActivities = hasActivities;
        }

        MemItem(String label, String shortLabel, long pss, long swapPss, long rss,
                long privateDirty, int id) {
            this.isProc = false;
            this.label = label;
            this.shortLabel = shortLabel;
            this.pss = pss;
            this.swapPss = swapPss;
            this.mRss = rss;
            this.mPrivateDirty = privateDirty;
            this.id = id;
            this.userId = UserHandle.USER_SYSTEM;
            this.hasActivities = false;
        }
    }

    private static void sortMemItems(List<MemItem> items, final boolean pss) {
        Collections.sort(items, new Comparator<MemItem>() {
            @Override
            public int compare(MemItem lhs, MemItem rhs) {
                long lss = pss ? lhs.pss : lhs.mRss;
                long rss = pss ? rhs.pss : rhs.mRss;
                if (lss < rss) {
                    return 1;
                } else if (lss > rss) {
                    return -1;
                }
                return 0;
            }
        });
    }

    static final void dumpMemItems(PrintWriter pw, String prefix, String tag,
            ArrayList<MemItem> items, boolean sort, boolean isCompact, boolean dumpPss,
            boolean dumpSwapPss, boolean dumpPrivateDirty) {
        if (sort && !isCompact) {
            sortMemItems(items, dumpPss);
        }

        for (int i=0; i<items.size(); i++) {
            MemItem mi = items.get(i);
            if (!isCompact) {
                String printFormat = "%s%s: %s%s\n";
                if ((dumpPss && dumpSwapPss) || dumpPrivateDirty) {
                    StringBuilder format = new StringBuilder();
                    format.append("%s%s: %-60s%s");
                    if (dumpSwapPss) {
                        format.append(String.format("(%s in swap%s", stringifyKBSize(mi.swapPss),
                                dumpPrivateDirty ? ", " : ")"));
                    }
                    if (dumpPrivateDirty) {
                        format.append(String.format("%s%s private dirty)", dumpSwapPss ? "" : "(",
                                stringifyKBSize(mi.mPrivateDirty)));
                    }
                    printFormat = format.append("\n").toString();
                }
                pw.printf(printFormat, prefix, stringifyKBSize(dumpPss ? mi.pss : mi.mRss),
                            mi.label,
                            mi.userId != UserHandle.USER_SYSTEM ? " (user " + mi.userId + ")" : "");
            } else if (mi.isProc) {
                pw.print("proc,"); pw.print(tag); pw.print(","); pw.print(mi.shortLabel);
                pw.print(","); pw.print(mi.id); pw.print(",");
                pw.print(dumpPss ? mi.pss : mi.mRss); pw.print(",");
                pw.print(dumpSwapPss ? mi.swapPss : "N/A");
                pw.println(mi.hasActivities ? ",a" : ",e");
            } else {
                pw.print(tag); pw.print(","); pw.print(mi.shortLabel); pw.print(",");
                pw.print(dumpPss ? mi.pss : mi.mRss); pw.print(",");
                pw.println(dumpSwapPss ? mi.swapPss : "N/A");
            }
            if (mi.subitems != null) {
                dumpMemItems(pw, prefix + "    ", mi.shortLabel, mi.subitems,
                        true, isCompact, dumpPss, dumpSwapPss, dumpPrivateDirty);
            }
        }
    }

    static final void dumpMemItems(ProtoOutputStream proto, long fieldId, String tag,
            ArrayList<MemItem> items, boolean sort, boolean dumpPss, boolean dumpSwapPss) {
        if (sort) {
            sortMemItems(items, dumpPss);
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
            proto.write(MemInfoDumpProto.MemItem.RSS_KB, mi.mRss);
            if (dumpSwapPss) {
                proto.write(MemInfoDumpProto.MemItem.SWAP_PSS_KB, mi.swapPss);
            }
            if (mi.subitems != null) {
                dumpMemItems(proto, MemInfoDumpProto.MemItem.SUB_ITEMS, mi.shortLabel, mi.subitems,
                        true, dumpPss, dumpSwapPss);
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
            ProcessList.PERCEPTIBLE_APP_ADJ,
            ProcessList.PERCEPTIBLE_MEDIUM_APP_ADJ, ProcessList.PERCEPTIBLE_LOW_APP_ADJ,
            ProcessList.BACKUP_APP_ADJ, ProcessList.HEAVY_WEIGHT_APP_ADJ,
            ProcessList.SERVICE_ADJ, ProcessList.HOME_APP_ADJ,
            ProcessList.PREVIOUS_APP_ADJ, ProcessList.SERVICE_B_ADJ, ProcessList.CACHED_APP_MIN_ADJ
    };
    static final String[] DUMP_MEM_OOM_LABEL = new String[] {
            "Native",
            "System", "Persistent", "Persistent Service", "Foreground",
            "Visible", "Perceptible", "Perceptible Medium", "Perceptible Low",
            "Backup", "Heavy Weight",
            "A Services", "Home",
            "Previous", "B Services", "Cached"
    };
    static final String[] DUMP_MEM_OOM_COMPACT_LABEL = new String[] {
            "native",
            "sys", "pers", "persvc", "fore",
            "vis", "percept", "perceptm", "perceptl",
            "backup", "heavy",
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

    static final int KSM_SHARED = 0;
    static final int KSM_SHARING = 1;
    static final int KSM_UNSHARED = 2;
    static final int KSM_VOLATILE = 3;

    static final long[] getKsmInfo() {
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

    static String stringifySize(long size, int order) {
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

    static String stringifyKBSize(long size) {
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
        boolean mDumpPrivateDirty;
        boolean mDumpAllocatorStats;
    }

    @NeverCompile // Avoid size overhead of debugging code.
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
        opts.mDumpPrivateDirty = false;
        opts.mDumpAllocatorStats = false;

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
            } else if ("-p".equals(opt)) {
                opts.mDumpPrivateDirty = true;
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
            } else if ("--logstats".equals(opt)) {
                opts.mDumpAllocatorStats = true;
            } else if ("-h".equals(opt)) {
                pw.println("meminfo dump options: [-a] [-d] [-c] [-s] [--oom] [process]");
                pw.println("  -a: include all available information for each process.");
                pw.println("  -d: include dalvik details.");
                pw.println("  -c: dump in a compact machine-parseable representation.");
                pw.println("  -s: dump only summary of application memory usage.");
                pw.println("  -S: dump also SwapPss.");
                pw.println("  -p: dump also private dirty memory usage.");
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

    @NeverCompile // Avoid size overhead of debugging code.
    private final void dumpApplicationMemoryUsage(FileDescriptor fd, PrintWriter pw, String prefix,
            MemoryUsageDumpOptions opts, final String[] innerArgs, boolean brief,
            ArrayList<ProcessRecord> procs, PrintWriter categoryPw) {
        long uptime = SystemClock.uptimeMillis();
        long realtime = SystemClock.elapsedRealtime();
        final long[] tmpLong = new long[3];

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
                    final int fFindPid = findPid;
                    mAppProfiler.forAllCpuStats((st) -> {
                        if (st.pid == fFindPid || (st.baseName != null
                                && st.baseName.equals(innerArgs[0]))) {
                            nativeProcs.add(st);
                        }
                    });
                    if (nativeProcs.size() > 0) {
                        dumpApplicationMemoryUsageHeader(pw, uptime, realtime,
                                opts.isCheckinRequest, opts.isCompact);
                        Debug.MemoryInfo mi = null;
                        for (int i = nativeProcs.size() - 1 ; i >= 0 ; i--) {
                            final ProcessCpuTracker.Stats r = nativeProcs.get(i);
                            final int pid = r.pid;
                            if (mi == null) {
                                mi = new Debug.MemoryInfo();
                            }
                            if (opts.dumpDetails || (!brief && !opts.oomOnly)) {
                                if (!Debug.getMemoryInfo(pid, mi)) {
                                    continue;
                                }
                            } else {
                                long pss = Debug.getPss(pid, tmpLong, null);
                                if (pss == 0) {
                                    continue;
                                }
                                mi.nativePss = (int) pss;
                                mi.nativePrivateDirty = (int) tmpLong[0];
                                mi.nativeRss = (int) tmpLong[2];
                            }
                            if (!opts.isCheckinRequest && opts.dumpDetails) {
                                pw.println("\n** MEMINFO in pid " + pid + " ["
                                        + r.baseName + "] **");
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
        final int numProcs = procs.size();
        final boolean collectNative = !opts.isCheckinRequest && numProcs > 1 && !opts.packages;
        if (collectNative) {
            // If we are showing aggregations, also look for native processes to
            // include so that our aggregations are more accurate.
            updateCpuStatsNow();
        }

        dumpApplicationMemoryUsageHeader(pw, uptime, realtime, opts.isCheckinRequest, opts.isCompact);

        ArrayList<MemItem> procMems = new ArrayList<MemItem>();
        final SparseArray<MemItem> procMemsMap = new SparseArray<MemItem>();
        final long[] ss = new long[INDEX_LAST];
        long[] dalvikSubitemPss = opts.dumpDalvik ? new long[Debug.MemoryInfo.NUM_DVK_STATS] :
                EmptyArray.LONG;
        long[] dalvikSubitemSwapPss = opts.dumpDalvik ? new long[Debug.MemoryInfo.NUM_DVK_STATS] :
                EmptyArray.LONG;
        long[] dalvikSubitemRss = opts.dumpDalvik ? new long[Debug.MemoryInfo.NUM_DVK_STATS] :
                EmptyArray.LONG;
        long[] dalvikSubitemPrivateDirty = opts.dumpDalvik
                ? new long[Debug.MemoryInfo.NUM_DVK_STATS] : EmptyArray.LONG;
        long[] miscPss = new long[Debug.MemoryInfo.NUM_OTHER_STATS];
        long[] miscSwapPss = new long[Debug.MemoryInfo.NUM_OTHER_STATS];
        long[] miscRss = new long[Debug.MemoryInfo.NUM_OTHER_STATS];
        long[] miscPrivateDirty = new long[Debug.MemoryInfo.NUM_OTHER_STATS];
        long[] memtrackTmp = new long[4];

        long oomPss[] = new long[DUMP_MEM_OOM_LABEL.length];
        long oomSwapPss[] = new long[DUMP_MEM_OOM_LABEL.length];
        long[] oomRss = new long[DUMP_MEM_OOM_LABEL.length];
        long[] oomPrivateDirty = new long[DUMP_MEM_OOM_LABEL.length];
        ArrayList<MemItem>[] oomProcs = (ArrayList<MemItem>[])
                new ArrayList[DUMP_MEM_OOM_LABEL.length];

        long totalSwapPss = 0;
        long totalRss = 0;
        long cachedPss = 0;
        long cachedSwapPss = 0;
        boolean hasSwapPss = false;

        Debug.MemoryInfo mi = null;
        for (int i = numProcs - 1; i >= 0; i--) {
            final ProcessRecord r = procs.get(i);
            final IApplicationThread thread;
            final int pid;
            final int oomAdj;
            final boolean hasActivities;
            synchronized (mProcLock) {
                thread = r.getThread();
                pid = r.getPid();
                oomAdj = r.mState.getSetAdjWithServices();
                hasActivities = r.hasActivities();
            }
            if (thread != null) {
                if (mi == null) {
                    mi = new Debug.MemoryInfo();
                }
                final int reportType;
                final long startTime;
                final long endTime;
                long memtrackGraphics = 0;
                long memtrackGl = 0;
                if (opts.dumpDetails || (!brief && !opts.oomOnly)) {
                    reportType = ProcessStats.ADD_PSS_EXTERNAL_SLOW;
                    startTime = SystemClock.currentThreadTimeMillis();
                    if (!Debug.getMemoryInfo(pid, mi)) {
                        continue;
                    }
                    endTime = SystemClock.currentThreadTimeMillis();
                    hasSwapPss = mi.hasSwappedOutPss;
                    memtrackGraphics = mi.getOtherPrivate(Debug.MemoryInfo.OTHER_GRAPHICS);
                    memtrackGl = mi.getOtherPrivate(Debug.MemoryInfo.OTHER_GL);
                } else {
                    reportType = ProcessStats.ADD_PSS_EXTERNAL;
                    startTime = SystemClock.currentThreadTimeMillis();
                    long pss = Debug.getPss(pid, tmpLong, memtrackTmp);
                    if (pss == 0) {
                        continue;
                    }
                    mi.dalvikPss = (int) pss;
                    endTime = SystemClock.currentThreadTimeMillis();
                    mi.dalvikPrivateDirty = (int) tmpLong[0];
                    mi.dalvikRss = (int) tmpLong[2];
                    memtrackGraphics = memtrackTmp[1];
                    memtrackGl = memtrackTmp[2];
                }
                if (!opts.isCheckinRequest && opts.dumpDetails) {
                    pw.println("\n** MEMINFO in pid " + pid + " [" + r.processName + "] **");
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
                                        opts.dumpDalvik, opts.dumpSummaryOnly, opts.dumpUnreachable,
                                        opts.mDumpAllocatorStats, innerArgs);
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
                final long myTotalPrivateDirty = mi.getTotalPrivateDirty();

                synchronized (mProcLock) {
                    if (r.getThread() != null && oomAdj == r.mState.getSetAdjWithServices()) {
                        // Record this for posterity if the process has been stable.
                        r.mProfile.addPss(myTotalPss, myTotalUss, myTotalRss, true,
                                reportType, endTime - startTime);
                    }
                }

                if (!opts.isCheckinRequest && mi != null) {
                    ss[INDEX_TOTAL_PSS] += myTotalPss;
                    ss[INDEX_TOTAL_SWAP_PSS] += myTotalSwapPss;
                    ss[INDEX_TOTAL_RSS] += myTotalRss;
                    ss[INDEX_TOTAL_PRIVATE_DIRTY] += myTotalPrivateDirty;
                    ss[INDEX_TOTAL_MEMTRACK_GRAPHICS] += memtrackGraphics;
                    ss[INDEX_TOTAL_MEMTRACK_GL] += memtrackGl;
                    MemItem pssItem = new MemItem(r.processName + " (pid " + pid +
                            (hasActivities ? " / activities)" : ")"), r.processName, myTotalPss,
                            myTotalSwapPss, myTotalRss, myTotalPrivateDirty,
                            pid, r.userId, hasActivities);
                    procMems.add(pssItem);
                    procMemsMap.put(pid, pssItem);

                    ss[INDEX_NATIVE_PSS] += mi.nativePss;
                    ss[INDEX_NATIVE_SWAP_PSS] += mi.nativeSwappedOutPss;
                    ss[INDEX_NATIVE_RSS] += mi.nativeRss;
                    ss[INDEX_NATIVE_PRIVATE_DIRTY] += mi.nativePrivateDirty;
                    ss[INDEX_DALVIK_PSS] += mi.dalvikPss;
                    ss[INDEX_DALVIK_SWAP_PSS] += mi.dalvikSwappedOutPss;
                    ss[INDEX_DALVIK_RSS] += mi.dalvikRss;
                    ss[INDEX_DALVIK_PRIVATE_DIRTY] += mi.dalvikPrivateDirty;
                    for (int j=0; j<dalvikSubitemPss.length; j++) {
                        dalvikSubitemPss[j] += mi.getOtherPss(Debug.MemoryInfo.NUM_OTHER_STATS + j);
                        dalvikSubitemSwapPss[j] +=
                                mi.getOtherSwappedOutPss(Debug.MemoryInfo.NUM_OTHER_STATS + j);
                        dalvikSubitemPrivateDirty[j] +=
                                mi.getOtherPrivateDirty(Debug.MemoryInfo.NUM_OTHER_STATS + j);
                        dalvikSubitemRss[j] += mi.getOtherRss(Debug.MemoryInfo.NUM_OTHER_STATS + j);
                    }
                    ss[INDEX_OTHER_PSS] += mi.otherPss;
                    ss[INDEX_OTHER_RSS] += mi.otherRss;
                    ss[INDEX_OTHER_SWAP_PSS] += mi.otherSwappedOutPss;
                    ss[INDEX_OTHER_PRIVATE_DIRTY] += mi.otherPrivateDirty;
                    for (int j=0; j<Debug.MemoryInfo.NUM_OTHER_STATS; j++) {
                        long mem = mi.getOtherPss(j);
                        miscPss[j] += mem;
                        ss[INDEX_OTHER_PSS] -= mem;
                        mem = mi.getOtherSwappedOutPss(j);
                        miscSwapPss[j] += mem;
                        ss[INDEX_OTHER_SWAP_PSS] -= mem;
                        mem = mi.getOtherPrivateDirty(j);
                        miscPrivateDirty[j] += mem;
                        ss[INDEX_OTHER_PRIVATE_DIRTY] -= mem;
                        mem = mi.getOtherRss(j);
                        miscRss[j] += mem;
                        ss[INDEX_OTHER_RSS] -= mem;
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
                            oomPrivateDirty[oomIndex] += myTotalPrivateDirty;
                            if (oomProcs[oomIndex] == null) {
                                oomProcs[oomIndex] = new ArrayList<MemItem>();
                            }
                            oomProcs[oomIndex].add(pssItem);
                            oomRss[oomIndex] += myTotalRss;
                            break;
                        }
                    }
                }
            }
        }

        long nativeProcTotalPss = 0;

        if (collectNative) {
            mi = null;
            final Debug.MemoryInfo[] memInfos = new Debug.MemoryInfo[1];
            mAppProfiler.forAllCpuStats((st) -> {
                if (st.vsize > 0 && procMemsMap.indexOfKey(st.pid) < 0) {
                    long memtrackGraphics = 0;
                    long memtrackGl = 0;
                    if (memInfos[0] == null) {
                        memInfos[0] = new Debug.MemoryInfo();
                    }
                    final Debug.MemoryInfo info = memInfos[0];
                    if (!brief && !opts.oomOnly) {
                        if (!Debug.getMemoryInfo(st.pid, info)) {
                            return;
                        }
                        memtrackGraphics = info.getOtherPrivate(Debug.MemoryInfo.OTHER_GRAPHICS);
                        memtrackGl = info.getOtherPrivate(Debug.MemoryInfo.OTHER_GL);
                    } else {
                        long pss = Debug.getPss(st.pid, tmpLong, memtrackTmp);
                        if (pss == 0) {
                            return;
                        }
                        info.nativePss = (int) pss;
                        info.nativePrivateDirty = (int) tmpLong[0];
                        info.nativeRss = (int) tmpLong[2];
                        memtrackGraphics = memtrackTmp[1];
                        memtrackGl = memtrackTmp[2];
                    }

                    final long myTotalPss = info.getTotalPss();
                    final long myTotalSwapPss = info.getTotalSwappedOutPss();
                    final long myTotalRss = info.getTotalRss();
                    final long myTotalPrivateDirty = info.getTotalPrivateDirty();
                    ss[INDEX_TOTAL_PSS] += myTotalPss;
                    ss[INDEX_TOTAL_SWAP_PSS] += myTotalSwapPss;
                    ss[INDEX_TOTAL_RSS] += myTotalRss;
                    ss[INDEX_TOTAL_NATIVE_PSS] += myTotalPss;
                    ss[INDEX_TOTAL_MEMTRACK_GRAPHICS] += memtrackGraphics;
                    ss[INDEX_TOTAL_MEMTRACK_GL] += memtrackGl;

                    MemItem pssItem = new MemItem(st.name + " (pid " + st.pid + ")",
                            st.name, myTotalPss, info.getSummaryTotalSwapPss(), myTotalRss,
                            myTotalPrivateDirty, st.pid, UserHandle.getUserId(st.uid), false);
                    procMems.add(pssItem);

                    ss[INDEX_NATIVE_PSS] += info.nativePss;
                    ss[INDEX_NATIVE_SWAP_PSS] += info.nativeSwappedOutPss;
                    ss[INDEX_NATIVE_RSS] += info.nativeRss;
                    ss[INDEX_NATIVE_PRIVATE_DIRTY] += info.nativePrivateDirty;
                    ss[INDEX_DALVIK_PSS] += info.dalvikPss;
                    ss[INDEX_DALVIK_SWAP_PSS] += info.dalvikSwappedOutPss;
                    ss[INDEX_DALVIK_RSS] += info.dalvikRss;
                    ss[INDEX_DALVIK_PRIVATE_DIRTY] += info.dalvikPrivateDirty;
                    for (int j = 0; j < dalvikSubitemPss.length; j++) {
                        dalvikSubitemPss[j] += info.getOtherPss(
                                Debug.MemoryInfo.NUM_OTHER_STATS + j);
                        dalvikSubitemSwapPss[j] +=
                                info.getOtherSwappedOutPss(Debug.MemoryInfo.NUM_OTHER_STATS + j);
                        dalvikSubitemRss[j] += info.getOtherRss(Debug.MemoryInfo.NUM_OTHER_STATS
                                + j);
                        dalvikSubitemPrivateDirty[j] +=
                                info.getOtherPrivateDirty(Debug.MemoryInfo.NUM_OTHER_STATS + j);
                    }
                    ss[INDEX_OTHER_PSS] += info.otherPss;
                    ss[INDEX_OTHER_SWAP_PSS] += info.otherSwappedOutPss;
                    ss[INDEX_OTHER_RSS] += info.otherRss;
                    ss[INDEX_OTHER_PRIVATE_DIRTY] += info.otherPrivateDirty;
                    for (int j = 0; j < Debug.MemoryInfo.NUM_OTHER_STATS; j++) {
                        long mem = info.getOtherPss(j);
                        miscPss[j] += mem;
                        ss[INDEX_OTHER_PSS] -= mem;
                        mem = info.getOtherSwappedOutPss(j);
                        miscSwapPss[j] += mem;
                        ss[INDEX_OTHER_SWAP_PSS] -= mem;
                        mem = info.getOtherRss(j);
                        miscRss[j] += mem;
                        ss[INDEX_OTHER_RSS] -= mem;
                        mem = info.getOtherPrivateDirty(j);
                        miscPrivateDirty[j] += mem;
                        ss[INDEX_OTHER_PRIVATE_DIRTY] -= mem;
                    }
                    oomPss[0] += myTotalPss;
                    oomSwapPss[0] += myTotalSwapPss;
                    if (oomProcs[0] == null) {
                        oomProcs[0] = new ArrayList<MemItem>();
                    }
                    oomProcs[0].add(pssItem);
                    oomRss[0] += myTotalRss;
                    oomPrivateDirty[0] += myTotalPrivateDirty;
                }
            });

            ArrayList<MemItem> catMems = new ArrayList<MemItem>();

            catMems.add(new MemItem("Native", "Native",
                    ss[INDEX_NATIVE_PSS], ss[INDEX_NATIVE_SWAP_PSS],
                    ss[INDEX_NATIVE_RSS], ss[INDEX_NATIVE_PRIVATE_DIRTY], -1));
            final int dalvikId = -2;
            catMems.add(new MemItem("Dalvik", "Dalvik", ss[INDEX_DALVIK_PSS],
                    ss[INDEX_DALVIK_SWAP_PSS], ss[INDEX_DALVIK_RSS],
                    ss[INDEX_DALVIK_PRIVATE_DIRTY], dalvikId));
            catMems.add(new MemItem("Unknown", "Unknown", ss[INDEX_OTHER_PSS],
                    ss[INDEX_OTHER_SWAP_PSS], ss[INDEX_OTHER_RSS],
                    ss[INDEX_OTHER_PRIVATE_DIRTY], -3));
            for (int j=0; j<Debug.MemoryInfo.NUM_OTHER_STATS; j++) {
                String label = Debug.MemoryInfo.getOtherLabel(j);
                catMems.add(new MemItem(label, label, miscPss[j], miscSwapPss[j], miscRss[j],
                        miscPrivateDirty[j], j));
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
                                dalvikSubitemSwapPss[j], dalvikSubitemRss[j],
                                dalvikSubitemPrivateDirty[j], j));
                    }
                }
            }

            ArrayList<MemItem> oomMems = new ArrayList<MemItem>();
            for (int j=0; j<oomPss.length; j++) {
                if (oomPss[j] != 0) {
                    String label = opts.isCompact ? DUMP_MEM_OOM_COMPACT_LABEL[j]
                            : DUMP_MEM_OOM_LABEL[j];
                    MemItem item = new MemItem(label, label, oomPss[j], oomSwapPss[j], oomRss[j],
                            oomPrivateDirty[j], DUMP_MEM_OOM_ADJ[j]);
                    item.subitems = oomProcs[j];
                    oomMems.add(item);
                }
            }
            if (!opts.isCompact) {
                pw.println();
            }
            if (!brief && !opts.oomOnly && !opts.isCompact) {
                pw.println();
                pw.println("Total RSS by process:");
                dumpMemItems(pw, "  ", "proc", procMems, true, opts.isCompact, false, false, false);
                pw.println();
            }
            if (!opts.isCompact) {
                pw.println("Total RSS by OOM adjustment:");
            }
            dumpMemItems(pw, "  ", "oom", oomMems, false, opts.isCompact, false, false, false);
            if (!brief && !opts.oomOnly) {
                PrintWriter out = categoryPw != null ? categoryPw : pw;
                if (!opts.isCompact) {
                    out.println();
                    out.println("Total RSS by category:");
                }
                dumpMemItems(out, "  ", "cat", catMems, true, opts.isCompact, false, false, false);
            }
            opts.dumpSwapPss = opts.dumpSwapPss && hasSwapPss && ss[INDEX_TOTAL_SWAP_PSS] != 0;
            if (!brief && !opts.oomOnly && !opts.isCompact) {
                pw.println();
                pw.println("Total PSS by process:");
                dumpMemItems(pw, "  ", "proc", procMems, true, opts.isCompact, true,
                        opts.dumpSwapPss, opts.mDumpPrivateDirty);
                pw.println();
            }
            if (!opts.isCompact) {
                pw.println("Total PSS by OOM adjustment:");
            }
            dumpMemItems(pw, "  ", "oom", oomMems, false, opts.isCompact, true, opts.dumpSwapPss,
                    opts.mDumpPrivateDirty);
            if (!brief && !opts.oomOnly) {
                PrintWriter out = categoryPw != null ? categoryPw : pw;
                if (!opts.isCompact) {
                    out.println();
                    out.println("Total PSS by category:");
                }
                dumpMemItems(out, "  ", "cat", catMems, true, opts.isCompact, true,
                        opts.dumpSwapPss, opts.mDumpPrivateDirty);
            }
            if (!opts.isCompact) {
                pw.println();
            }
            MemInfoReader memInfo = new MemInfoReader();
            memInfo.readMemInfo();
            if (ss[INDEX_TOTAL_NATIVE_PSS] > 0) {
                synchronized (mProcessStats.mLock) {
                    final long cachedKb = memInfo.getCachedSizeKb();
                    final long freeKb = memInfo.getFreeSizeKb();
                    final long zramKb = memInfo.getZramTotalSizeKb();
                    final long kernelKb = memInfo.getKernelUsedSizeKb();
                    EventLogTags.writeAmMeminfo(cachedKb * 1024, freeKb * 1024, zramKb * 1024,
                            kernelKb * 1024, ss[INDEX_TOTAL_NATIVE_PSS] * 1024);
                    mProcessStats.addSysMemUsageLocked(cachedKb, freeKb, zramKb, kernelKb,
                            ss[INDEX_TOTAL_NATIVE_PSS]);
                }
            }
            if (!brief) {
                if (!opts.isCompact) {
                    pw.print("Total RAM: "); pw.print(stringifyKBSize(memInfo.getTotalSizeKb()));
                    pw.print(" (status ");
                    mAppProfiler.dumpLastMemoryLevelLocked(pw);
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
                    pw.println(ss[INDEX_TOTAL_PSS] - cachedPss);
                }
            }
            long kernelUsed = memInfo.getKernelUsedSizeKb();
            final long ionHeap = Debug.getIonHeapsSizeKb();
            final long ionPool = Debug.getIonPoolsSizeKb();
            final long dmabufMapped = Debug.getDmabufMappedSizeKb();
            if (ionHeap >= 0 && ionPool >= 0) {
                final long ionUnmapped = ionHeap - dmabufMapped;
                pw.print("      ION: ");
                        pw.print(stringifyKBSize(ionHeap + ionPool));
                        pw.print(" (");
                        pw.print(stringifyKBSize(dmabufMapped));
                        pw.print(" mapped + ");
                        pw.print(stringifyKBSize(ionUnmapped));
                        pw.print(" unmapped + ");
                        pw.print(stringifyKBSize(ionPool));
                        pw.println(" pools)");
                kernelUsed += ionUnmapped;
                // Note: mapped ION memory is not accounted in PSS due to VM_PFNMAP flag being
                // set on ION VMAs, however it might be included by the memtrack HAL.
                // Replace memtrack HAL reported Graphics category with mapped dmabufs
                ss[INDEX_TOTAL_PSS] -= ss[INDEX_TOTAL_MEMTRACK_GRAPHICS];
                ss[INDEX_TOTAL_PSS] += dmabufMapped;
            } else {
                final long totalExportedDmabuf = Debug.getDmabufTotalExportedKb();
                if (totalExportedDmabuf >= 0) {
                    final long dmabufUnmapped = totalExportedDmabuf - dmabufMapped;
                    pw.print("DMA-BUF: ");
                    pw.print(stringifyKBSize(totalExportedDmabuf));
                    pw.print(" (");
                    pw.print(stringifyKBSize(dmabufMapped));
                    pw.print(" mapped + ");
                    pw.print(stringifyKBSize(dmabufUnmapped));
                    pw.println(" unmapped)");
                    // Account unmapped dmabufs as part of kernel memory allocations
                    kernelUsed += dmabufUnmapped;
                    // Replace memtrack HAL reported Graphics category with mapped dmabufs
                    ss[INDEX_TOTAL_PSS] -= ss[INDEX_TOTAL_MEMTRACK_GRAPHICS];
                    ss[INDEX_TOTAL_PSS] += dmabufMapped;
                }

                // totalDmabufHeapExported is included in totalExportedDmabuf above and hence do not
                // need to be added to kernelUsed.
                final long totalDmabufHeapExported = Debug.getDmabufHeapTotalExportedKb();
                if (totalDmabufHeapExported >= 0) {
                    pw.print("DMA-BUF Heaps: ");
                    pw.println(stringifyKBSize(totalDmabufHeapExported));
                }

                final long totalDmabufHeapPool = Debug.getDmabufHeapPoolsSizeKb();
                if (totalDmabufHeapPool >= 0) {
                    pw.print("DMA-BUF Heaps pool: ");
                    pw.println(stringifyKBSize(totalDmabufHeapPool));
                }
            }
            final long gpuUsage = Debug.getGpuTotalUsageKb();
            if (gpuUsage >= 0) {
                final long gpuPrivateUsage = Debug.getGpuPrivateMemoryKb();
                if (gpuPrivateUsage >= 0) {
                    final long gpuDmaBufUsage = gpuUsage - gpuPrivateUsage;
                    pw.print("      GPU: ");
                    pw.print(stringifyKBSize(gpuUsage));
                    pw.print(" (");
                    pw.print(stringifyKBSize(gpuDmaBufUsage));
                    pw.print(" dmabuf + ");
                    pw.print(stringifyKBSize(gpuPrivateUsage));
                    pw.println(" private)");
                    // Replace memtrack HAL reported GL category with private GPU allocations and
                    // account it as part of kernel memory allocations
                    ss[INDEX_TOTAL_PSS] -= ss[INDEX_TOTAL_MEMTRACK_GL];
                    kernelUsed += gpuPrivateUsage;
                } else {
                    pw.print("      GPU: "); pw.println(stringifyKBSize(gpuUsage));
                }
            }

             // Note: ION/DMA-BUF heap pools are reclaimable and hence, they are included as part of
             // memInfo.getCachedSizeKb().
            final long lostRAM = memInfo.getTotalSizeKb()
                    - (ss[INDEX_TOTAL_PSS] - ss[INDEX_TOTAL_SWAP_PSS])
                    - memInfo.getFreeSizeKb() - memInfo.getCachedSizeKb()
                    - kernelUsed - memInfo.getZramTotalSizeKb();
            if (!opts.isCompact) {
                pw.print(" Used RAM: "); pw.print(stringifyKBSize(ss[INDEX_TOTAL_PSS] - cachedPss
                        + kernelUsed)); pw.print(" (");
                pw.print(stringifyKBSize(ss[INDEX_TOTAL_PSS] - cachedPss));
                pw.print(" used pss + ");
                pw.print(stringifyKBSize(kernelUsed)); pw.print(" kernel)\n");
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

    @NeverCompile // Avoid size overhead of debugging code.
    private final void dumpApplicationMemoryUsage(FileDescriptor fd,
            MemoryUsageDumpOptions opts, final String[] innerArgs, boolean brief,
            ArrayList<ProcessRecord> procs) {
        final long uptimeMs = SystemClock.uptimeMillis();
        final long realtimeMs = SystemClock.elapsedRealtime();
        final long[] tmpLong = new long[3];

        if (procs == null) {
            // No Java processes.  Maybe they want to print a native process.
            String proc = "N/A";
            if (innerArgs.length > 0) {
                proc = innerArgs[0];
                if (proc.charAt(0) != '-') {
                    final ArrayList<ProcessCpuTracker.Stats> nativeProcs
                            = new ArrayList<ProcessCpuTracker.Stats>();
                    updateCpuStatsNow();
                    int findPid = -1;
                    try {
                        findPid = Integer.parseInt(innerArgs[0]);
                    } catch (NumberFormatException e) {
                    }
                    final int fFindPid = findPid;
                    mAppProfiler.forAllCpuStats((st) -> {
                        if (st.pid == fFindPid || (st.baseName != null
                                && st.baseName.equals(innerArgs[0]))) {
                            nativeProcs.add(st);
                        }
                    });
                    if (nativeProcs.size() > 0) {
                        ProtoOutputStream proto = new ProtoOutputStream(fd);

                        proto.write(MemInfoDumpProto.UPTIME_DURATION_MS, uptimeMs);
                        proto.write(MemInfoDumpProto.ELAPSED_REALTIME_MS, realtimeMs);
                        Debug.MemoryInfo mi = null;
                        for (int i = nativeProcs.size() - 1 ; i >= 0 ; i--) {
                            final ProcessCpuTracker.Stats r = nativeProcs.get(i);
                            final int pid = r.pid;

                            if (mi == null) {
                                mi = new Debug.MemoryInfo();
                            }
                            if (opts.dumpDetails || (!brief && !opts.oomOnly)) {
                                if (!Debug.getMemoryInfo(pid, mi)) {
                                    continue;
                                }
                            } else {
                                long pss = Debug.getPss(pid, tmpLong, null);
                                if (pss == 0) {
                                    continue;
                                }
                                mi.nativePss = (int) pss;
                                mi.nativePrivateDirty = (int) tmpLong[0];
                                mi.nativeRss = (int) tmpLong[2];
                            }

                            final long nToken = proto.start(MemInfoDumpProto.NATIVE_PROCESSES);

                            proto.write(MemInfoDumpProto.ProcessMemory.PID, pid);
                            proto.write(MemInfoDumpProto.ProcessMemory.PROCESS_NAME, r.baseName);

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
        final int numProcs = procs.size();
        final boolean collectNative = numProcs > 1 && !opts.packages;
        if (collectNative) {
            // If we are showing aggregations, also look for native processes to
            // include so that our aggregations are more accurate.
            updateCpuStatsNow();
        }

        ProtoOutputStream proto = new ProtoOutputStream(fd);

        proto.write(MemInfoDumpProto.UPTIME_DURATION_MS, uptimeMs);
        proto.write(MemInfoDumpProto.ELAPSED_REALTIME_MS, realtimeMs);

        final ArrayList<MemItem> procMems = new ArrayList<MemItem>();
        final SparseArray<MemItem> procMemsMap = new SparseArray<MemItem>();
        final long[] ss = new long[INDEX_LAST];
        long[] dalvikSubitemPss = opts.dumpDalvik ? new long[Debug.MemoryInfo.NUM_DVK_STATS] :
                EmptyArray.LONG;
        long[] dalvikSubitemSwapPss = opts.dumpDalvik ? new long[Debug.MemoryInfo.NUM_DVK_STATS] :
                EmptyArray.LONG;
        long[] dalvikSubitemRss = opts.dumpDalvik ? new long[Debug.MemoryInfo.NUM_DVK_STATS] :
                EmptyArray.LONG;
        long[] miscPss = new long[Debug.MemoryInfo.NUM_OTHER_STATS];
        long[] miscSwapPss = new long[Debug.MemoryInfo.NUM_OTHER_STATS];
        long[] miscRss = new long[Debug.MemoryInfo.NUM_OTHER_STATS];

        final long[] oomPss = new long[DUMP_MEM_OOM_LABEL.length];
        final long[] oomSwapPss = new long[DUMP_MEM_OOM_LABEL.length];
        final long[] oomRss = new long[DUMP_MEM_OOM_LABEL.length];
        final ArrayList<MemItem>[] oomProcs = (ArrayList<MemItem>[])
                new ArrayList[DUMP_MEM_OOM_LABEL.length];

        long cachedPss = 0;
        long cachedSwapPss = 0;
        boolean hasSwapPss = false;

        Debug.MemoryInfo mi = null;
        for (int i = numProcs - 1; i >= 0; i--) {
            final ProcessRecord r = procs.get(i);
            final IApplicationThread thread;
            final int pid;
            final int oomAdj;
            final boolean hasActivities;
            synchronized (mProcLock) {
                thread = r.getThread();
                pid = r.getPid();
                oomAdj = r.mState.getSetAdjWithServices();
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
                if (!Debug.getMemoryInfo(pid, mi)) {
                    continue;
                }
                endTime = SystemClock.currentThreadTimeMillis();
                hasSwapPss = mi.hasSwappedOutPss;
            } else {
                reportType = ProcessStats.ADD_PSS_EXTERNAL;
                startTime = SystemClock.currentThreadTimeMillis();
                long pss = Debug.getPss(pid, tmpLong, null);
                if (pss == 0) {
                    continue;
                }
                mi.dalvikPss = (int) pss;
                endTime = SystemClock.currentThreadTimeMillis();
                mi.dalvikPrivateDirty = (int) tmpLong[0];
                mi.dalvikRss = (int) tmpLong[2];
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

            synchronized (mProcLock) {
                if (r.getThread() != null && oomAdj == r.mState.getSetAdjWithServices()) {
                    // Record this for posterity if the process has been stable.
                    r.mProfile.addPss(myTotalPss, myTotalUss, myTotalRss, true,
                                reportType, endTime - startTime);
                }
            }

            if (!opts.isCheckinRequest && mi != null) {
                ss[INDEX_TOTAL_PSS] += myTotalPss;
                ss[INDEX_TOTAL_SWAP_PSS] += myTotalSwapPss;
                ss[INDEX_TOTAL_RSS] += myTotalRss;
                MemItem pssItem = new MemItem(r.processName + " (pid " + pid +
                        (hasActivities ? " / activities)" : ")"), r.processName, myTotalPss,
                        myTotalSwapPss, myTotalRss, 0, pid, r.userId, hasActivities);
                procMems.add(pssItem);
                procMemsMap.put(pid, pssItem);

                ss[INDEX_NATIVE_PSS] += mi.nativePss;
                ss[INDEX_NATIVE_SWAP_PSS] += mi.nativeSwappedOutPss;
                ss[INDEX_NATIVE_RSS] += mi.nativeRss;
                ss[INDEX_DALVIK_PSS] += mi.dalvikPss;
                ss[INDEX_DALVIK_SWAP_PSS] += mi.dalvikSwappedOutPss;
                ss[INDEX_DALVIK_RSS] += mi.dalvikRss;
                for (int j=0; j<dalvikSubitemPss.length; j++) {
                    dalvikSubitemPss[j] += mi.getOtherPss(Debug.MemoryInfo.NUM_OTHER_STATS + j);
                    dalvikSubitemSwapPss[j] +=
                            mi.getOtherSwappedOutPss(Debug.MemoryInfo.NUM_OTHER_STATS + j);
                    dalvikSubitemRss[j] += mi.getOtherRss(Debug.MemoryInfo.NUM_OTHER_STATS + j);
                }
                ss[INDEX_OTHER_PSS] += mi.otherPss;
                ss[INDEX_OTHER_RSS] += mi.otherRss;
                ss[INDEX_OTHER_SWAP_PSS] += mi.otherSwappedOutPss;
                for (int j=0; j<Debug.MemoryInfo.NUM_OTHER_STATS; j++) {
                    long mem = mi.getOtherPss(j);
                    miscPss[j] += mem;
                    ss[INDEX_OTHER_PSS] -= mem;
                    mem = mi.getOtherSwappedOutPss(j);
                    miscSwapPss[j] += mem;
                    ss[INDEX_OTHER_SWAP_PSS] -= mem;
                    mem = mi.getOtherRss(j);
                    miscRss[j] += mem;
                    ss[INDEX_OTHER_RSS] -= mem;
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
                        oomRss[oomIndex] += myTotalRss;
                        break;
                    }
                }
            }
        }

        long nativeProcTotalPss = 0;

        if (collectNative) {
            mi = null;
            final Debug.MemoryInfo[] memInfos = new Debug.MemoryInfo[1];
            mAppProfiler.forAllCpuStats((st) -> {
                if (st.vsize > 0 && procMemsMap.indexOfKey(st.pid) < 0) {
                    if (memInfos[0] == null) {
                        memInfos[0] = new Debug.MemoryInfo();
                    }
                    final Debug.MemoryInfo info = memInfos[0];
                    if (!brief && !opts.oomOnly) {
                        if (!Debug.getMemoryInfo(st.pid, info)) {
                            return;
                        }
                    } else {
                        long pss = Debug.getPss(st.pid, tmpLong, null);
                        if (pss == 0) {
                            return;
                        }
                        info.nativePss = (int) pss;
                        info.nativePrivateDirty = (int) tmpLong[0];
                        info.nativeRss = (int) tmpLong[2];
                    }

                    final long myTotalPss = info.getTotalPss();
                    final long myTotalSwapPss = info.getTotalSwappedOutPss();
                    final long myTotalRss = info.getTotalRss();
                    ss[INDEX_TOTAL_PSS] += myTotalPss;
                    ss[INDEX_TOTAL_SWAP_PSS] += myTotalSwapPss;
                    ss[INDEX_TOTAL_RSS] += myTotalRss;
                    ss[INDEX_TOTAL_NATIVE_PSS] += myTotalPss;

                    MemItem pssItem = new MemItem(st.name + " (pid " + st.pid + ")",
                            st.name, myTotalPss, info.getSummaryTotalSwapPss(), myTotalRss, 0,
                            st.pid, UserHandle.getUserId(st.uid), false);
                    procMems.add(pssItem);

                    ss[INDEX_NATIVE_PSS] += info.nativePss;
                    ss[INDEX_NATIVE_SWAP_PSS] += info.nativeSwappedOutPss;
                    ss[INDEX_NATIVE_RSS] += info.nativeRss;
                    ss[INDEX_DALVIK_PSS] += info.dalvikPss;
                    ss[INDEX_DALVIK_SWAP_PSS] += info.dalvikSwappedOutPss;
                    ss[INDEX_DALVIK_RSS] += info.dalvikRss;
                    for (int j = 0; j < dalvikSubitemPss.length; j++) {
                        dalvikSubitemPss[j] += info.getOtherPss(
                                Debug.MemoryInfo.NUM_OTHER_STATS + j);
                        dalvikSubitemSwapPss[j] +=
                                info.getOtherSwappedOutPss(Debug.MemoryInfo.NUM_OTHER_STATS + j);
                        dalvikSubitemRss[j] += info.getOtherRss(Debug.MemoryInfo.NUM_OTHER_STATS
                                + j);
                    }
                    ss[INDEX_OTHER_PSS] += info.otherPss;
                    ss[INDEX_OTHER_SWAP_PSS] += info.otherSwappedOutPss;
                    ss[INDEX_OTHER_RSS] += info.otherRss;
                    for (int j = 0; j < Debug.MemoryInfo.NUM_OTHER_STATS; j++) {
                        long mem = info.getOtherPss(j);
                        miscPss[j] += mem;
                        ss[INDEX_OTHER_PSS] -= mem;
                        mem = info.getOtherSwappedOutPss(j);
                        miscSwapPss[j] += mem;
                        ss[INDEX_OTHER_SWAP_PSS] -= mem;
                        mem = info.getOtherRss(j);
                        miscRss[j] += mem;
                        ss[INDEX_OTHER_RSS] -= mem;
                    }
                    oomPss[0] += myTotalPss;
                    oomSwapPss[0] += myTotalSwapPss;
                    if (oomProcs[0] == null) {
                        oomProcs[0] = new ArrayList<MemItem>();
                    }
                    oomProcs[0].add(pssItem);
                    oomRss[0] += myTotalRss;
                }
            });

            ArrayList<MemItem> catMems = new ArrayList<MemItem>();

            catMems.add(new MemItem("Native", "Native", ss[INDEX_NATIVE_PSS],
                    ss[INDEX_NATIVE_SWAP_PSS], ss[INDEX_NATIVE_RSS], 0, -1));
            final int dalvikId = -2;
            catMems.add(new MemItem("Dalvik", "Dalvik", ss[INDEX_DALVIK_PSS],
                    ss[INDEX_DALVIK_SWAP_PSS], ss[INDEX_DALVIK_RSS], 0, dalvikId));
            catMems.add(new MemItem("Unknown", "Unknown", ss[INDEX_OTHER_PSS],
                    ss[INDEX_OTHER_SWAP_PSS], ss[INDEX_OTHER_RSS], 0, -3));
            for (int j=0; j<Debug.MemoryInfo.NUM_OTHER_STATS; j++) {
                String label = Debug.MemoryInfo.getOtherLabel(j);
                catMems.add(new MemItem(label, label, miscPss[j], miscSwapPss[j],
                        miscRss[j], 0, j));
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
                                dalvikSubitemSwapPss[j], dalvikSubitemRss[j], 0, j));
                    }
                }
            }

            ArrayList<MemItem> oomMems = new ArrayList<MemItem>();
            for (int j=0; j<oomPss.length; j++) {
                if (oomPss[j] != 0) {
                    String label = opts.isCompact ? DUMP_MEM_OOM_COMPACT_LABEL[j]
                            : DUMP_MEM_OOM_LABEL[j];
                    MemItem item = new MemItem(label, label, oomPss[j], oomSwapPss[j], oomRss[j], 0,
                            DUMP_MEM_OOM_ADJ[j]);
                    item.subitems = oomProcs[j];
                    oomMems.add(item);
                }
            }

            if (!opts.oomOnly) {
                dumpMemItems(proto, MemInfoDumpProto.TOTAL_RSS_BY_PROCESS, "proc",
                        procMems, true, false, false);
            }
            dumpMemItems(proto, MemInfoDumpProto.TOTAL_RSS_BY_OOM_ADJUSTMENT, "oom",
                    oomMems, false, false, false);
            if (!brief && !opts.oomOnly) {
                dumpMemItems(proto, MemInfoDumpProto.TOTAL_RSS_BY_CATEGORY, "cat",
                        catMems, true, false, false);
            }

            opts.dumpSwapPss = opts.dumpSwapPss && hasSwapPss && ss[INDEX_TOTAL_SWAP_PSS] != 0;
            if (!opts.oomOnly) {
                dumpMemItems(proto, MemInfoDumpProto.TOTAL_PSS_BY_PROCESS, "proc",
                        procMems, true, true, opts.dumpSwapPss);
            }
            dumpMemItems(proto, MemInfoDumpProto.TOTAL_PSS_BY_OOM_ADJUSTMENT, "oom",
                    oomMems, false, true, opts.dumpSwapPss);
            if (!brief && !opts.oomOnly) {
                dumpMemItems(proto, MemInfoDumpProto.TOTAL_PSS_BY_CATEGORY, "cat",
                        catMems, true, true, opts.dumpSwapPss);
            }
            MemInfoReader memInfo = new MemInfoReader();
            memInfo.readMemInfo();
            if (ss[INDEX_TOTAL_NATIVE_PSS] > 0) {
                synchronized (mProcessStats.mLock) {
                    final long cachedKb = memInfo.getCachedSizeKb();
                    final long freeKb = memInfo.getFreeSizeKb();
                    final long zramKb = memInfo.getZramTotalSizeKb();
                    final long kernelKb = memInfo.getKernelUsedSizeKb();
                    EventLogTags.writeAmMeminfo(cachedKb * 1024, freeKb * 1024, zramKb * 1024,
                            kernelKb * 1024, ss[INDEX_TOTAL_NATIVE_PSS] * 1024);
                    mProcessStats.addSysMemUsageLocked(cachedKb, freeKb, zramKb, kernelKb,
                            ss[INDEX_TOTAL_NATIVE_PSS]);
                }
            }
            if (!brief) {
                proto.write(MemInfoDumpProto.TOTAL_RAM_KB, memInfo.getTotalSizeKb());
                proto.write(MemInfoDumpProto.STATUS, mAppProfiler.getLastMemoryLevelLocked());
                proto.write(MemInfoDumpProto.CACHED_PSS_KB, cachedPss);
                proto.write(MemInfoDumpProto.CACHED_KERNEL_KB, memInfo.getCachedSizeKb());
                proto.write(MemInfoDumpProto.FREE_KB, memInfo.getFreeSizeKb());
            }
            long lostRAM = memInfo.getTotalSizeKb()
                    - (ss[INDEX_TOTAL_PSS] - ss[INDEX_TOTAL_SWAP_PSS])
                    - memInfo.getFreeSizeKb() - memInfo.getCachedSizeKb()
                    - memInfo.getKernelUsedSizeKb() - memInfo.getZramTotalSizeKb();
            proto.write(MemInfoDumpProto.USED_PSS_KB, ss[INDEX_TOTAL_PSS] - cachedPss);
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

    static void appendBasicMemEntry(StringBuilder sb, int oomAdj, int procState, long pss,
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

    static void appendMemInfo(StringBuilder sb, ProcessMemInfo mi) {
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

    /**
     * Filters out non-exported components in a given list of broadcast filters
     * @param intent the original intent
     * @param callingUid the calling UID
     * @param query the list of broadcast filters
     * @param platformCompat the instance of platform compat
     */
    private void filterNonExportedComponents(Intent intent, int callingUid, int callingPid,
            List query, PlatformCompat platformCompat, String callerPackage, String resolvedType) {
        if (query == null
                || intent.getPackage() != null
                || intent.getComponent() != null
                || ActivityManager.canAccessUnexportedComponents(callingUid)) {
            return;
        }
        IUnsafeIntentStrictModeCallback callback = mStrictModeCallbacks.get(callingPid);
        for (int i = query.size() - 1; i >= 0; i--) {
            String componentInfo;
            ResolveInfo resolveInfo;
            BroadcastFilter broadcastFilter;
            if (query.get(i) instanceof ResolveInfo) {
                resolveInfo = (ResolveInfo) query.get(i);
                if (resolveInfo.getComponentInfo().exported) {
                    continue;
                }
                componentInfo = resolveInfo.getComponentInfo()
                        .getComponentName().flattenToShortString();
            } else if (query.get(i) instanceof BroadcastFilter) {
                broadcastFilter = (BroadcastFilter) query.get(i);
                if (broadcastFilter.exported) {
                    continue;
                }
                componentInfo = broadcastFilter.packageName;
            } else {
                continue;
            }
            if (callback != null) {
                mHandler.post(() -> {
                    try {
                        callback.onImplicitIntentMatchedInternalComponent(intent.cloneFilter());
                    } catch (RemoteException e) {
                        mStrictModeCallbacks.remove(callingPid);
                    }
                });
            }
            boolean hasToBeExportedToMatch = platformCompat.isChangeEnabledByUid(
                    ActivityManagerService.IMPLICIT_INTENTS_ONLY_MATCH_EXPORTED_COMPONENTS,
                    callingUid);
            ActivityManagerUtils.logUnsafeIntentEvent(
                    UNSAFE_INTENT_EVENT_REPORTED__EVENT_TYPE__INTERNAL_NON_EXPORTED_COMPONENT_MATCH,
                    callingUid, intent, resolvedType, hasToBeExportedToMatch);
            if (!hasToBeExportedToMatch) {
                return;
            }
            query.remove(i);
        }
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
    final boolean cleanUpApplicationRecordLocked(ProcessRecord app, int pid,
            boolean restarting, boolean allowRestart, int index, boolean replacingPid,
            boolean fromBinderDied) {
        boolean restart;
        synchronized (mProcLock) {
            if (index >= 0) {
                removeLruProcessLocked(app);
                ProcessList.remove(pid);
            }

            // We don't want to unlinkDeathRecipient immediately, if it's not called from binder
            // and it's not isolated, as we'd need the signal to bookkeeping the dying process list.
            restart = app.onCleanupApplicationRecordLSP(mProcessStats, allowRestart,
                    fromBinderDied || app.isolated /* unlinkDeath */);

            // Cancel pending frozen task and clean up frozen record if there is any.
            mOomAdjuster.mCachedAppOptimizer.onCleanupApplicationRecordLocked(app);
        }
        mAppProfiler.onCleanupApplicationRecordLocked(app);
        mBroadcastQueue.onApplicationCleanupLocked(app);
        clearProcessForegroundLocked(app);
        mServices.killServicesLocked(app, allowRestart);
        mPhantomProcessList.onAppDied(pid);

        // If the app is undergoing backup, tell the backup manager about it
        final BackupRecord backupTarget = mBackupTargets.get(app.userId);
        if (backupTarget != null && pid == backupTarget.app.getPid()) {
            if (DEBUG_BACKUP || DEBUG_CLEANUP) Slog.d(TAG_CLEANUP, "App "
                    + backupTarget.appInfo + " died during backup");
            mHandler.post(new Runnable() {
                @Override
                public void run() {
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

        mProcessList.scheduleDispatchProcessDiedLocked(pid, app.info.uid);

        // If this is a preceding instance of another process instance
        allowRestart = mProcessList.handlePrecedingAppDiedLocked(app);

        // If somehow this process was still waiting for the death of its predecessor,
        // (probably it's "killed" before starting for real), reset the bookkeeping.
        final ProcessRecord predecessor = app.mPredecessor;
        if (predecessor != null) {
            predecessor.mSuccessor = null;
            predecessor.mSuccessorStartRunnable = null;
            app.mPredecessor = null;
        }

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
        } else if (!app.isRemoved()) {
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
        mProcessList.noteProcessDiedLocked(app);

        if (restart && allowRestart && !app.isolated) {
            // We have components that still need to be running in the
            // process, so re-launch it.
            if (index < 0) {
                ProcessList.remove(pid);
            }

            // Remove provider publish timeout because we will start a new timeout when the
            // restarted process is attaching (if the process contains launching providers).
            mHandler.removeMessages(CONTENT_PROVIDER_PUBLISH_TIMEOUT_MSG, app);

            mProcessList.addProcessNameLocked(app);
            app.setPendingStart(false);
            mProcessList.startProcessLocked(app, new HostingRecord(
                    HostingRecord.HOSTING_TYPE_RESTART, app.processName),
                    ZYGOTE_POLICY_FLAG_EMPTY);
            return true;
        } else if (pid > 0 && pid != MY_PID) {
            // Goodbye!
            removePidLocked(pid, app);
            mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);
            mHandler.removeMessages(BIND_APPLICATION_TIMEOUT_SOFT_MSG, app);
            mHandler.removeMessages(BIND_APPLICATION_TIMEOUT_HARD_MSG, app);
            mBatteryStatsService.noteProcessFinish(app.processName, app.info.uid);
            if (app.isolated) {
                mBatteryStatsService.removeIsolatedUid(app.uid, app.info.uid);
            }
            app.setPid(0);
        }
        return false;
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
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (name == null || getPackageManagerInternal()
                .filterAppAccess(name.getPackageName(), callingUid, callingUserId)) {
            return null;
        }
        synchronized (this) {
            return mServices.getRunningServiceControlPanelLocked(name);
        }
    }

    @Override
    public void logFgsApiBegin(@ForegroundServiceApiType int apiType,
            int uid, int pid) {
        enforceCallingPermission(android.Manifest.permission.LOG_FOREGROUND_RESOURCE_USE,
                "logFgsApiBegin");
        synchronized (this) {
            mServices.logFgsApiBeginLocked(apiType, uid, pid);
        }
    }

    @Override
    public void logFgsApiEnd(@ForegroundServiceApiType int apiType,
            int uid, int pid) {
        enforceCallingPermission(android.Manifest.permission.LOG_FOREGROUND_RESOURCE_USE,
                "logFgsApiEnd");
        synchronized (this) {
            mServices.logFgsApiEndLocked(apiType, uid, pid);
        }
    }

    @Override
    public void logFgsApiStateChanged(@ForegroundServiceApiType int apiType,
            int state, int uid, int pid) {
        enforceCallingPermission(android.Manifest.permission.LOG_FOREGROUND_RESOURCE_USE,
                "logFgsApiEvent");
        synchronized (this) {
            mServices.logFgsApiStateChangedLocked(apiType, uid, pid, state);
        }
    }

    @Override
    public ComponentName startService(IApplicationThread caller, Intent service,
            String resolvedType, boolean requireForeground, String callingPackage,
            String callingFeatureId, int userId)
            throws TransactionTooLargeException {
        return startService(caller, service, resolvedType, requireForeground, callingPackage,
                callingFeatureId, userId, false /* isSdkSandboxService */, INVALID_UID, null, null);
    }

    private ComponentName startService(IApplicationThread caller, Intent service,
            String resolvedType, boolean requireForeground, String callingPackage,
            String callingFeatureId, int userId, boolean isSdkSandboxService,
            int sdkSandboxClientAppUid, String sdkSandboxClientAppPackage, String instanceName)
            throws TransactionTooLargeException {
        enforceNotIsolatedCaller("startService");
        enforceAllowedToStartOrBindServiceIfSdkSandbox(service);
        if (service != null) {
            // Refuse possible leaked file descriptors
            if (service.hasFileDescriptors()) {
                throw new IllegalArgumentException("File descriptors passed in Intent");
            }
            // Remove existing mismatch flag so it can be properly updated later
            service.removeExtendedFlags(Intent.EXTENDED_FLAG_FILTER_MISMATCH);
        }

        if (callingPackage == null) {
            throw new IllegalArgumentException("callingPackage cannot be null");
        }

        if (isSdkSandboxService && instanceName == null) {
            throw new IllegalArgumentException("No instance name provided for SDK sandbox process");
        }
        validateServiceInstanceName(instanceName);

        if (DEBUG_SERVICE)
            Slog.v(
                    TAG_SERVICE,
                    "*** startService: "
                            + service
                            + " type="
                            + resolvedType
                            + " fg="
                            + requireForeground);
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        ComponentName res;
        try {
            if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
                Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "startService: "
                        + "intent=" + service + ", caller=" + callingPackage
                        + ", fgRequired=" + requireForeground);
            }
            synchronized (this) {
                res = mServices.startServiceLocked(caller, service,
                        resolvedType, callingPid, callingUid,
                        requireForeground, callingPackage, callingFeatureId, userId,
                        isSdkSandboxService, sdkSandboxClientAppUid, sdkSandboxClientAppPackage,
                        instanceName);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
            Binder.restoreCallingIdentity(origId);
        }
        return res;
    }

    private void validateServiceInstanceName(String instanceName) {
        // Ensure that instanceName, which is caller provided, does not contain
        // unusual characters.
        if (instanceName != null) {
            if (!instanceName.matches("[a-zA-Z0-9_.]+")) {
                throw new IllegalArgumentException("Illegal instanceName");
            }
        }
    }

    @Override
    public int stopService(IApplicationThread caller, Intent service,
            String resolvedType, int userId) {
        return stopService(caller, service, resolvedType, userId, false /* isSdkSandboxService */,
                INVALID_UID, null, null);
    }

    private int stopService(IApplicationThread caller, Intent service, String resolvedType,
            int userId, boolean isSdkSandboxService,
            int sdkSandboxClientAppUid, String sdkSandboxClientAppPackage, String instanceName) {
        enforceNotIsolatedCaller("stopService");
        // Refuse possible leaked file descriptors
        if (service != null && service.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        try {
            if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
                Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "stopService: " + service);
            }
            synchronized (this) {
                return mServices.stopServiceLocked(caller, service, resolvedType, userId,
                        isSdkSandboxService, sdkSandboxClientAppUid, sdkSandboxClientAppPackage,
                        instanceName);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
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
        try {
            if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
                Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "stopServiceToken: "
                        + (className != null ? className.toShortString()
                        : ("from " + Binder.getCallingPid())));
            }
            synchronized (this) {
                return mServices.stopServiceTokenLocked(className, token, startId);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
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
    public boolean shouldServiceTimeOut(ComponentName className, IBinder token) {
        synchronized (this) {
            return mServices.shouldServiceTimeOutLocked(className, token);
        }
    }

    @Override
    public boolean hasServiceTimeLimitExceeded(ComponentName className, IBinder token) {
        synchronized (this) {
            return mServices.hasServiceTimedOutLocked(className, token);
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

    boolean isSystemUserOnly(int flags) {
        return android.multiuser.Flags.enableSystemUserOnlyForServicesAndProviders()
                && (flags & ServiceInfo.FLAG_SYSTEM_USER_ONLY) != 0;
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
            String resolvedType, IServiceConnection connection, long flags,
            String callingPackage, int userId) throws TransactionTooLargeException {
        return bindServiceInstance(caller, token, service, resolvedType, connection, flags,
                null, callingPackage, userId);
    }

    /**
     * Binds to a service with a given instanceName, creating it if it does not already exist.
     * If the instanceName field is not supplied, binding to the service occurs as usual.
     */
    public int bindServiceInstance(IApplicationThread caller, IBinder token, Intent service,
            String resolvedType, IServiceConnection connection, long flags, String instanceName,
            String callingPackage, int userId) throws TransactionTooLargeException {
        return bindServiceInstance(caller, token, service, resolvedType, connection, flags,
                instanceName, false, INVALID_UID, null, null, callingPackage, userId);
    }

    private int bindServiceInstance(IApplicationThread caller, IBinder token, Intent service,
            String resolvedType, IServiceConnection connection, long flags, String instanceName,
            boolean isSdkSandboxService, int sdkSandboxClientAppUid,
            String sdkSandboxClientAppPackage,
            IApplicationThread sdkSandboxClientApplicationThread,
            String callingPackage, int userId)
            throws TransactionTooLargeException {
        enforceNotIsolatedCaller("bindService");
        enforceAllowedToStartOrBindServiceIfSdkSandbox(service);

        if (service != null) {
            // Refuse possible leaked file descriptors
            if (service.hasFileDescriptors()) {
                throw new IllegalArgumentException("File descriptors passed in Intent");
            }
            // Remove existing mismatch flag so it can be properly updated later
            service.removeExtendedFlags(Intent.EXTENDED_FLAG_FILTER_MISMATCH);
        }

        if (callingPackage == null) {
            throw new IllegalArgumentException("callingPackage cannot be null");
        }

        if (isSdkSandboxService && instanceName == null) {
            throw new IllegalArgumentException("No instance name provided for isolated process");
        }

        validateServiceInstanceName(instanceName);

        try {
            if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
                final ComponentName cn = service.getComponent();
                Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "bindService:"
                        + (cn != null ? cn.toShortString() : service.getAction()));
            }
            synchronized (this) {
                return mServices.bindServiceLocked(caller, token, service, resolvedType, connection,
                        flags, instanceName, isSdkSandboxService, sdkSandboxClientAppUid,
                        sdkSandboxClientAppPackage, sdkSandboxClientApplicationThread,
                        callingPackage, userId);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    public void updateServiceGroup(IServiceConnection connection, int group, int importance) {
        synchronized (this) {
            mServices.updateServiceGroupLocked(connection, group, importance);
        }
    }

    public boolean unbindService(IServiceConnection connection) {
        try {
            if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
                Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "unbindService");
            }
            synchronized (this) {
                return mServices.unbindServiceLocked(connection);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
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

    @Override
    public void serviceDoneExecuting(IBinder token, int type, int startId, int res, Intent intent) {
        synchronized(this) {
            if (!(token instanceof ServiceRecord)) {
                Slog.e(TAG, "serviceDoneExecuting: Invalid service token=" + token);
                throw new IllegalArgumentException("Invalid service token");
            }
            mServices.serviceDoneExecutingLocked((ServiceRecord) token, type, startId, res, false,
                    intent);
        }
    }

    // =========================================================
    // BACKUP AND RESTORE
    // =========================================================

    // Cause the target app to be launched if necessary and its backup agent
    // instantiated.  The backup agent will invoke backupAgentCreated() on the
    // activity manager to announce its creation.
    public boolean bindBackupAgent(String packageName, int backupMode, int targetUserId,
            @BackupDestination int backupDestination) {
        long startTimeNs = SystemClock.uptimeNanos();
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
        boolean useSystemUser = PLATFORM_PACKAGE_NAME.equals(packageName)
                || getPackageManagerInternal().getSystemUiServiceComponent().getPackageName()
                        .equals(packageName);
        final int instantiatedUserId = useSystemUser ? UserHandle.USER_SYSTEM : targetUserId;

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
        if (app.backupAgentName != null) {
            final ComponentName backupAgentName = new ComponentName(
                    app.packageName, app.backupAgentName);
            int enableState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            try {
                enableState = pm.getComponentEnabledSetting(backupAgentName, instantiatedUserId);
            } catch (RemoteException e) {
                // can't happen; package manager is process-local
            }
            switch (enableState) {
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                    Slog.w(TAG, "Unable to bind backup agent for " + backupAgentName
                            + ", the backup agent component is disabled.");
                    return false;

                case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT:
                case PackageManager.COMPONENT_ENABLED_STATE_ENABLED:
                default:
                    // Since there's no way to declare a backup agent disabled in the manifest,
                    // assume the case COMPONENT_ENABLED_STATE_DEFAULT to be enabled.
                    break;
            }
        }

        int oldBackupUid;
        int newBackupUid;

        synchronized(this) {
            if (android.app.Flags.appRestrictionsApi()) {
                try {
                    final boolean wasStopped = mPackageManagerInt.isPackageStopped(app.packageName,
                            UserHandle.getUserId(app.uid));
                    if (wasStopped) {
                        noteAppRestrictionEnabled(app.packageName, app.uid,
                                RESTRICTION_LEVEL_FORCE_STOPPED, false,
                                RESTRICTION_REASON_DEFAULT, "restore",
                                RESTRICTION_SOURCE_SYSTEM, 0L);
                    }
                } catch (NameNotFoundException e) {
                    Slog.w(TAG, "No such package", e);
                }
            }

            // !!! TODO: currently no check here that we're already bound
            // Backup agent is now in use, its package can't be stopped.
            try {
                mPackageManagerInt.setPackageStoppedState(
                        app.packageName, false, UserHandle.getUserId(app.uid));
            } catch (IllegalArgumentException e) {
                Slog.w(TAG, "Failed trying to unstop package "
                        + app.packageName + ": " + e);
            }

            BackupRecord r = new BackupRecord(app, backupMode, targetUserId, backupDestination);
            ComponentName hostingName =
                    (backupMode == ApplicationThreadConstants.BACKUP_MODE_INCREMENTAL
                            || backupMode == ApplicationThreadConstants.BACKUP_MODE_RESTORE)
                            ? new ComponentName(app.packageName, app.backupAgentName)
                            : new ComponentName("android", "FullBackupAgent");

            ProcessRecord proc = getProcessRecordLocked(app.processName, app.uid);
            boolean isProcessStarted = proc != null;
            if (!isProcessStarted) {
                proc = startProcessLocked(app.processName, app,
                  false, 0,
                  new HostingRecord(HostingRecord.HOSTING_TYPE_BACKUP, hostingName),
                  ZYGOTE_POLICY_FLAG_SYSTEM_PROCESS, false, false);
            }
            if (proc == null) {
                Slog.e(TAG, "Unable to start backup agent process " + r);
                return false;
            }
            mProcessList.getAppStartInfoTracker().handleProcessBackupStart(startTimeNs, proc, r,
                    !isProcessStarted);

            // If the app is a regular app (uid >= 10000) and not the system server or phone
            // process, etc, then mark it as being in full backup so that certain calls to the
            // process can be blocked. This is not reset to false anywhere because we kill the
            // process after the full backup is done and the ProcessRecord will vaporize anyway.
            if (UserHandle.isApp(app.uid) &&
                    backupMode == ApplicationThreadConstants.BACKUP_MODE_FULL) {
                proc.setInFullBackup(true);
            }
            r.app = proc;
            final BackupRecord backupTarget = mBackupTargets.get(targetUserId);
            oldBackupUid = backupTarget != null ? backupTarget.appInfo.uid : -1;
            newBackupUid = proc.isInFullBackup() ? r.appInfo.uid : -1;
            mBackupTargets.put(targetUserId, r);

            proc.mProfile.addHostingComponentType(HOSTING_COMPONENT_TYPE_BACKUP);

            // Try not to kill the process during backup
            updateOomAdjLocked(proc, OOM_ADJ_REASON_BACKUP);

            // If the process is already attached, schedule the creation of the backup agent now.
            // If it is not yet live, this will be done when it attaches to the framework.
            final IApplicationThread thread = proc.getThread();
            if (thread != null) {
                if (DEBUG_BACKUP) Slog.v(TAG_BACKUP, "Agent proc already running: " + proc);
                try {
                    thread.scheduleCreateBackupAgent(app, backupMode, targetUserId,
                            backupDestination);
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
            final int indexOfKey = mBackupTargets.indexOfKey(userId);
            if (indexOfKey >= 0) {
                final BackupRecord backupTarget = mBackupTargets.valueAt(indexOfKey);
                if (backupTarget != null && backupTarget.app != null) {
                    backupTarget.app.mProfile.clearHostingComponentType(
                            HOSTING_COMPONENT_TYPE_BACKUP);
                }
                mBackupTargets.removeAt(indexOfKey);
            }
        }

        JobSchedulerInternal js = LocalServices.getService(JobSchedulerInternal.class);
        js.clearAllBackingUpUids();
    }

    // A backup agent has just come up
    @Override
    public void backupAgentCreated(String agentPackageName, IBinder agent, int userId) {
        final int callingUid = Binder.getCallingUid();
        enforceCallingPackage(agentPackageName, callingUid);

        // Resolve the target user id and enforce permissions.
        userId = mUserController.handleIncomingUser(Binder.getCallingPid(), callingUid,
                userId, /* allowAll */ false, ALLOW_FULL_ONLY, "backupAgentCreated", null);
        if (DEBUG_BACKUP) {
            Slog.v(TAG_BACKUP, "backupAgentCreated: " + agentPackageName + " = " + agent
                    + " callingUserId = " + UserHandle.getCallingUserId() + " userId = " + userId
                    + " callingUid = " + callingUid + " uid = " + Process.myUid());
        }

        synchronized(this) {
            final BackupRecord backupTarget = mBackupTargets.get(userId);
            String backupAppName = backupTarget == null ? null : backupTarget.appInfo.packageName;
            if (!agentPackageName.equals(backupAppName)) {
                Slog.e(TAG, "Backup agent created for " + agentPackageName + " but not requested!");
                return;
            }
        }

        final long oldIdent = Binder.clearCallingIdentity();
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
                updateOomAdjLocked(proc, OOM_ADJ_REASON_BACKUP);
                proc.setInFullBackup(false);
                proc.mProfile.clearHostingComponentType(HOSTING_COMPONENT_TYPE_BACKUP);

                oldBackupUid = backupTarget != null ? backupTarget.appInfo.uid : -1;

                // If the app crashed during backup, 'thread' will be null here
                final IApplicationThread thread = proc.getThread();
                if (thread != null) {
                    try {
                        thread.scheduleDestroyBackupAgent(appInfo, userId);
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

    /**
     * @deprecated Use {@link #registerReceiverWithFeature}
     */
    @Deprecated
    public Intent registerReceiver(IApplicationThread caller, String callerPackage,
            IIntentReceiver receiver, IntentFilter filter, String permission, int userId,
            int flags) {
        return registerReceiverWithFeature(caller, callerPackage, null, null,
                receiver, filter, permission, userId, flags);
    }

    public Intent registerReceiverWithFeature(IApplicationThread caller, String callerPackage,
            String callerFeatureId, String receiverId, IIntentReceiver receiver,
            IntentFilter filter, String permission, int userId, int flags) {
        traceRegistrationBegin(receiverId, receiver, filter, userId);
        try {
            return registerReceiverWithFeatureTraced(caller, callerPackage, callerFeatureId,
                    receiverId, receiver, filter, permission, userId, flags);
        } finally {
            traceRegistrationEnd();
        }
    }

    private static void traceRegistrationBegin(String receiverId, IIntentReceiver receiver,
            IntentFilter filter, int userId) {
        if (!Flags.traceReceiverRegistration()) {
            return;
        }
        if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
            final StringBuilder sb = new StringBuilder("registerReceiver: ");
            sb.append(Binder.getCallingUid()); sb.append('/');
            sb.append(receiverId == null ? "null" : receiverId); sb.append('/');
            final int actionsCount = filter.countActions();
            if (actionsCount > 0) {
                for (int i = 0; i < actionsCount; ++i) {
                    sb.append(filter.getAction(i));
                    if (i != actionsCount - 1) sb.append(',');
                }
            } else {
                sb.append("null");
            }
            sb.append('/');
            sb.append('u'); sb.append(userId); sb.append('/');
            sb.append(receiver == null ? "null" : receiver.asBinder());
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, sb.toString());
        }
    }

    private static void traceRegistrationEnd() {
        if (!Flags.traceReceiverRegistration()) {
            return;
        }
        if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    private Intent registerReceiverWithFeatureTraced(IApplicationThread caller,
            String callerPackage, String callerFeatureId, String receiverId,
            IIntentReceiver receiver, IntentFilter filter, String permission,
            int userId, int flags) {
        enforceNotIsolatedCaller("registerReceiver");
        ArrayList<StickyBroadcast> stickyBroadcasts = null;
        ProcessRecord callerApp = null;
        final boolean visibleToInstantApps
                = (flags & Context.RECEIVER_VISIBLE_TO_INSTANT_APPS) != 0;

        int callingUid;
        int callingPid;
        boolean instantApp;
        synchronized (mProcLock) {
            callerApp = getRecordForAppLOSP(caller);
            if (callerApp == null) {
                Slog.w(TAG, "registerReceiverWithFeature: no app for " + caller);
                return null;
            }
            if (callerApp.info.uid != SYSTEM_UID
                    && !callerApp.getPkgList().containsKey(callerPackage)
                    && !"android".equals(callerPackage)) {
                throw new SecurityException("Given caller package " + callerPackage
                        + " is not running in process " + callerApp);
            }
            callingUid = callerApp.info.uid;
            callingPid = callerApp.getPid();

            instantApp = isInstantApp(callerApp, callerPackage, callingUid);
        }
        userId = mUserController.handleIncomingUser(callingPid, callingUid, userId, true,
                ALLOW_FULL_ONLY, "registerReceiver", callerPackage);

        // Warn if system internals are registering for important broadcasts
        // without also using a priority to ensure they process the event
        // before normal apps hear about it
        if (UserHandle.isCore(callingUid)) {
            final int priority = filter.getPriority();
            final boolean systemPriority = (priority >= IntentFilter.SYSTEM_HIGH_PRIORITY)
                    || (priority <= IntentFilter.SYSTEM_LOW_PRIORITY);
            if (!systemPriority) {
                final int N = filter.countActions();
                for (int i = 0; i < N; i++) {
                    // TODO: expand to additional important broadcasts over time
                    final String action = filter.getAction(i);
                    if (action.startsWith("android.intent.action.USER_")
                            || action.startsWith("android.intent.action.PACKAGE_")
                            || action.startsWith("android.intent.action.UID_")
                            || action.startsWith("android.intent.action.EXTERNAL_")
                            || action.startsWith("android.bluetooth.")
                            || action.equals(Intent.ACTION_SHUTDOWN)) {
                        if (DEBUG_BROADCAST) {
                            Slog.wtf(TAG,
                                    "System internals registering for " + filter.toLongString()
                                            + " with app priority; this will race with apps!",
                                    new Throwable());
                        }

                        // When undefined, assume that system internals need
                        // to hear about the event first; they can use
                        // SYSTEM_LOW_PRIORITY if they need to hear last
                        if (priority == 0) {
                            filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
                        }
                        break;
                    }
                }
            }
        }

        Iterator<String> actions = filter.actionsIterator();
        if (actions == null) {
            ArrayList<String> noAction = new ArrayList<String>(1);
            noAction.add(null);
            actions = noAction.iterator();
        }
        boolean onlyProtectedBroadcasts = true;

        // Collect stickies of users and check if broadcast is only registered for protected
        // broadcasts
        int[] userIds = { UserHandle.USER_ALL, UserHandle.getUserId(callingUid) };
        synchronized (mStickyBroadcasts) {
            while (actions.hasNext()) {
                String action = actions.next();
                for (int id : userIds) {
                    ArrayMap<String, ArrayList<StickyBroadcast>> stickies =
                            mStickyBroadcasts.get(id);
                    if (stickies != null) {
                        ArrayList<StickyBroadcast> broadcasts = stickies.get(action);
                        if (broadcasts != null) {
                            if (stickyBroadcasts == null) {
                                stickyBroadcasts = new ArrayList<>();
                            }
                            stickyBroadcasts.addAll(broadcasts);
                        }
                    }
                }
                if (onlyProtectedBroadcasts) {
                    try {
                        onlyProtectedBroadcasts &=
                                AppGlobals.getPackageManager().isProtectedBroadcast(action);
                    } catch (RemoteException e) {
                        onlyProtectedBroadcasts = false;
                        Slog.w(TAG, "Remote exception", e);
                    }
                }
            }
        }

        if (Process.isSdkSandboxUid(Binder.getCallingUid())) {
            SdkSandboxManagerLocal sdkSandboxManagerLocal =
                    LocalManagerRegistry.getManager(SdkSandboxManagerLocal.class);
            if (sdkSandboxManagerLocal == null) {
                throw new IllegalStateException("SdkSandboxManagerLocal not found when checking"
                        + " whether SDK sandbox uid can register to broadcast receivers.");
            }
            if (!sdkSandboxManagerLocal.canRegisterBroadcastReceiver(
                    /*IntentFilter=*/ filter, flags, onlyProtectedBroadcasts)) {
                throw new SecurityException("SDK sandbox not allowed to register receiver"
                        + " with the given IntentFilter: " + filter.toLongString());
            }
        }

        // If the change is enabled, but neither exported or not exported is set, we need to log
        // an error so the consumer can know to explicitly set the value for their flag.
        // If the caller is registering for a sticky broadcast with a null receiver, we won't
        // require a flag
        final boolean explicitExportStateDefined =
                (flags & (Context.RECEIVER_EXPORTED | Context.RECEIVER_NOT_EXPORTED)) != 0;
        if (((flags & Context.RECEIVER_EXPORTED) != 0) && (
                (flags & Context.RECEIVER_NOT_EXPORTED) != 0)) {
            throw new IllegalArgumentException(
                    "Receiver can't specify both RECEIVER_EXPORTED and RECEIVER_NOT_EXPORTED"
                            + "flag");
        }

        // Don't enforce the flag check if we're EITHER registering for only protected
        // broadcasts, or the receiver is null (a sticky broadcast). Sticky broadcasts should
        // not be used generally, so we will be marking them as exported by default
        boolean requireExplicitFlagForDynamicReceivers = CompatChanges.isChangeEnabled(
                DYNAMIC_RECEIVER_EXPLICIT_EXPORT_REQUIRED, callingUid);

        // A receiver that is visible to instant apps must also be exported.
        final boolean unexportedReceiverVisibleToInstantApps =
                ((flags & Context.RECEIVER_VISIBLE_TO_INSTANT_APPS) != 0) && (
                        (flags & Context.RECEIVER_NOT_EXPORTED) != 0);
        if (unexportedReceiverVisibleToInstantApps && requireExplicitFlagForDynamicReceivers) {
            throw new IllegalArgumentException(
                    "Receiver can't specify both RECEIVER_VISIBLE_TO_INSTANT_APPS and "
                            + "RECEIVER_NOT_EXPORTED flag");
        }

        if (!onlyProtectedBroadcasts) {
            if (receiver == null && !explicitExportStateDefined) {
                // sticky broadcast, no flag specified (flag isn't required)
                flags |= Context.RECEIVER_EXPORTED;
            } else if (requireExplicitFlagForDynamicReceivers && !explicitExportStateDefined) {
                throw new SecurityException(
                        callerPackage + ": One of RECEIVER_EXPORTED or "
                                + "RECEIVER_NOT_EXPORTED should be specified when a receiver "
                                + "isn't being registered exclusively for system broadcasts");
                // Assume default behavior-- flag check is not enforced
            } else if (!requireExplicitFlagForDynamicReceivers && (
                    (flags & Context.RECEIVER_NOT_EXPORTED) == 0)) {
                // Change is not enabled, assume exported unless otherwise specified.
                flags |= Context.RECEIVER_EXPORTED;
            }
        } else if ((flags & Context.RECEIVER_NOT_EXPORTED) == 0) {
            flags |= Context.RECEIVER_EXPORTED;
        }

        // Dynamic receivers are exported by default for versions prior to T
        final boolean exported = (flags & Context.RECEIVER_EXPORTED) != 0;

        ArrayList<StickyBroadcast> allSticky = null;
        if (stickyBroadcasts != null) {
            final ContentResolver resolver = mContext.getContentResolver();
            // Look for any matching sticky broadcasts...
            for (int i = 0, N = stickyBroadcasts.size(); i < N; i++) {
                final StickyBroadcast broadcast = stickyBroadcasts.get(i);
                Intent intent = broadcast.intent;
                // Don't provided intents that aren't available to instant apps.
                if (instantApp &&
                        (intent.getFlags() & Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS) == 0) {
                    continue;
                }
                // If intent has scheme "content", it will need to access
                // provider that needs to lock mProviderMap in ActivityThread
                // and also it may need to wait application response, so we
                // cannot lock ActivityManagerService here.
                final int match;
                if (Flags.avoidResolvingType()) {
                    match = filter.match(intent.getAction(), broadcast.resolvedDataType,
                        intent.getScheme(), intent.getData(), intent.getCategories(),
                        TAG, false /* supportsWildcards */, null /* ignoreActions */,
                        intent.getExtras());
                } else {
                    match = filter.match(resolver, intent, true, TAG);
                }
                if (match >= 0) {
                    if (allSticky == null) {
                        allSticky = new ArrayList<>();
                    }
                    allSticky.add(broadcast);
                }
            }
        }

        // The first sticky in the list is returned directly back to the client.
        Intent sticky = allSticky != null ? allSticky.get(0).intent : null;
        if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Register receiver " + filter + ": " + sticky);
        if (receiver == null) {
            return sticky;
        }

        // SafetyNet logging for b/177931370. If any process other than system_server tries to
        // listen to this broadcast action, then log it.
        if (callingPid != Process.myPid()) {
            if (filter.hasAction("com.android.server.net.action.SNOOZE_WARNING")
                    || filter.hasAction("com.android.server.net.action.SNOOZE_RAPID")) {
                EventLog.writeEvent(0x534e4554, "177931370", callingUid, "");
            }
        }

        synchronized (this) {
            IApplicationThread thread;
            if (callerApp != null && ((thread = callerApp.getThread()) == null
                    || thread.asBinder() != caller.asBinder())) {
                // Original caller already died
                return null;
            }
            ReceiverList rl = mRegisteredReceivers.get(receiver.asBinder());
            if (rl == null) {
                rl = new ReceiverList(this, callerApp, callingPid, callingUid,
                        userId, receiver);
                if (rl.app != null) {
                    final int totalReceiversForApp = rl.app.mReceivers.numberOfReceivers();
                    if (totalReceiversForApp >= MAX_RECEIVERS_ALLOWED_PER_APP) {
                        throw new IllegalStateException("Too many receivers, total of "
                                + totalReceiversForApp + ", registered for pid: "
                                + rl.pid + ", callerPackage: " + callerPackage);
                    }
                    rl.app.mReceivers.addReceiver(rl);
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
            BroadcastFilter bf = new BroadcastFilter(filter, rl, callerPackage, callerFeatureId,
                    receiverId, permission, callingUid, userId, instantApp, visibleToInstantApps,
                    exported);
            if (rl.containsFilter(filter)) {
                Slog.w(TAG, "Receiver with filter " + filter
                        + " already registered for pid " + rl.pid
                        + ", callerPackage is " + callerPackage);
            } else {
                rl.add(bf);
                if (!bf.debugCheck()) {
                    Slog.w(TAG, "==> For Dynamic broadcast");
                }
                mReceiverResolver.addFilter(getPackageManagerInternal().snapshot(), bf);
            }

            // Enqueue broadcasts for all existing stickies that match
            // this filter.
            if (allSticky != null) {
                ArrayList receivers = new ArrayList();
                receivers.add(bf);
                sticky = null;

                final int stickyCount = allSticky.size();
                for (int i = 0; i < stickyCount; i++) {
                    final StickyBroadcast broadcast = allSticky.get(i);
                    final int originalStickyCallingUid = allSticky.get(i).originalCallingUid;
                    // TODO(b/281889567): consider using checkComponentPermission instead of
                    //  canAccessUnexportedComponents
                    if (sticky == null && (exported || originalStickyCallingUid == callingUid
                            || ActivityManager.canAccessUnexportedComponents(
                            originalStickyCallingUid))) {
                        sticky = broadcast.intent;
                    }
                    BroadcastQueue queue = mBroadcastQueue;
                    BroadcastRecord r = new BroadcastRecord(queue, broadcast.intent, null,
                            null, null, -1, -1, false, null, null, null, null, OP_NONE,
                            BroadcastOptions.makeWithDeferUntilActive(broadcast.deferUntilActive),
                            receivers, null, null, 0, null, null, false, true, true, -1,
                            originalStickyCallingUid, BackgroundStartPrivileges.NONE,
                            false /* only PRE_BOOT_COMPLETED should be exempt, no stickies */,
                            null /* filterExtrasForReceiver */,
                            broadcast.originalCallingAppProcessState);
                    queue.enqueueBroadcastLocked(r);
                }
            }

            return sticky;
        }
    }

    public void unregisterReceiver(IIntentReceiver receiver) {
        traceUnregistrationBegin(receiver);
        try {
            unregisterReceiverTraced(receiver);
        } finally {
            traceUnregistrationEnd();
        }
    }

    private static void traceUnregistrationBegin(IIntentReceiver receiver) {
        if (!Flags.traceReceiverRegistration()) {
            return;
        }
        if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                    TextUtils.formatSimple("unregisterReceiver: %d/%s", Binder.getCallingUid(),
                            receiver == null ? "null" : receiver.asBinder()));
        }
    }

    private static void traceUnregistrationEnd() {
        if (!Flags.traceReceiverRegistration()) {
            return;
        }
        if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    private void unregisterReceiverTraced(IIntentReceiver receiver) {
        if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Unregister receiver: " + receiver);

        final long origId = Binder.clearCallingIdentity();
        try {
            boolean doTrim = false;
            synchronized(this) {
                ReceiverList rl = mRegisteredReceivers.get(receiver.asBinder());
                if (rl != null) {
                    final BroadcastRecord r = rl.curBroadcast;
                    if (r != null) {
                        final boolean doNext = r.queue.finishReceiverLocked(
                                rl.app, r.resultCode, r.resultData, r.resultExtras,
                                r.resultAbort, false);
                        if (doNext) {
                            doTrim = true;
                        }
                    }
                    if (rl.app != null) {
                        rl.app.mReceivers.removeReceiver(rl);
                    }
                    removeReceiverLocked(rl);
                    if (rl.linkedToDeath) {
                        rl.linkedToDeath = false;
                        rl.receiver.asBinder().unlinkToDeath(rl, 0);
                    }
                }

                // If we actually concluded any broadcasts, we might now be able
                // to trim the recipients' apps from our working set
                if (doTrim) {
                    trimApplicationsLocked(false, OOM_ADJ_REASON_FINISH_RECEIVER);
                    return;
                }
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
            int callingUid, int[] users, int[] broadcastAllowList) {
        // TODO: come back and remove this assumption to triage all broadcasts
        long pmFlags = STOCK_PM_FLAGS | MATCH_DEBUG_TRIAGED_MISSING;

        List<ResolveInfo> receivers = null;
        HashSet<ComponentName> singleUserReceivers = null;
        boolean scannedFirstReceivers = false;
        for (int user : users) {
            // Skip users that have Shell restrictions
            if (callingUid == SHELL_UID
                    && mUserController.hasUserRestriction(
                    UserManager.DISALLOW_DEBUGGING_FEATURES, user)) {
                continue;
            }
            List<ResolveInfo> newReceivers = mPackageManagerInt.queryIntentReceivers(
                    intent, resolvedType, pmFlags, callingUid, user, true /* forSend */);
            if (user != UserHandle.USER_SYSTEM && newReceivers != null) {
                // If this is not the system user, we need to check for
                // any receivers that should be filtered out.
                for (int i = 0; i < newReceivers.size(); i++) {
                    ResolveInfo ri = newReceivers.get(i);
                    if ((ri.activityInfo.flags & ActivityInfo.FLAG_SYSTEM_USER_ONLY) != 0) {
                        newReceivers.remove(i);
                        i--;
                    }
                }
            }
            // Replace the alias receivers with their targets.
            if (newReceivers != null) {
                for (int i = newReceivers.size() - 1; i >= 0; i--) {
                    final ResolveInfo ri = newReceivers.get(i);
                    final Resolution<ResolveInfo> resolution =
                            mComponentAliasResolver.resolveReceiver(intent, ri, resolvedType,
                                    pmFlags, user, callingUid, true /* forSend */);
                    if (resolution == null) {
                        // It was an alias, but the target was not found.
                        newReceivers.remove(i);
                        continue;
                    }
                    if (resolution.isAlias()) {
                        newReceivers.set(i, resolution.getTarget());
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
                    for (int i = 0; i < receivers.size(); i++) {
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
                for (int i = 0; i < newReceivers.size(); i++) {
                    ResolveInfo ri = newReceivers.get(i);
                    if ((ri.activityInfo.flags & ActivityInfo.FLAG_SINGLE_USER) != 0) {
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
        if (receivers != null && broadcastAllowList != null) {
            for (int i = receivers.size() - 1; i >= 0; i--) {
                final int receiverAppId = UserHandle.getAppId(
                        receivers.get(i).activityInfo.applicationInfo.uid);
                if (receiverAppId >= Process.FIRST_APPLICATION_UID
                        && Arrays.binarySearch(broadcastAllowList, receiverAppId) < 0) {
                    receivers.remove(i);
                }
            }
        }
        return receivers;
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
                || TelephonyManager.ACTION_REQUEST_OMADM_CONFIGURATION_UPDATE.equals(action)
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
                    if (bf.exported && bf.requiredPermission == null) {
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

    // Apply permission policy around the use of specific broadcast options
    void enforceBroadcastOptionPermissionsInternal(
            @Nullable Bundle options, int callingUid) {
        enforceBroadcastOptionPermissionsInternal(BroadcastOptions.fromBundleNullable(options),
                callingUid);
    }

    void enforceBroadcastOptionPermissionsInternal(
            @Nullable BroadcastOptions options, int callingUid) {
        if (options != null && callingUid != Process.SYSTEM_UID) {
            if (options.isAlarmBroadcast()) {
                if (DEBUG_BROADCAST_LIGHT) {
                    Slog.w(TAG, "Non-system caller " + callingUid
                            + " may not flag broadcast as alarm");
                }
                throw new SecurityException(
                        "Non-system callers may not flag broadcasts as alarm");
            }
            if (options.isInteractive()) {
                enforceCallingPermission(
                        android.Manifest.permission.BROADCAST_OPTION_INTERACTIVE,
                        "setInteractive");
            }
        }
    }

    @GuardedBy("this")
    final int broadcastIntentLocked(ProcessRecord callerApp,
            String callerPackage, String callerFeatureId, Intent intent, String resolvedType,
            IIntentReceiver resultTo, int resultCode, String resultData,
            Bundle resultExtras, String[] requiredPermissions, String[] excludedPermissions,
            String[] excludedPackages, int appOp, Bundle bOptions, boolean ordered,
            boolean sticky, int callingPid,
            int callingUid, int realCallingUid, int realCallingPid, int userId) {
        return broadcastIntentLocked(callerApp, callerPackage, callerFeatureId, intent,
                resolvedType, null, resultTo, resultCode, resultData, resultExtras,
                requiredPermissions, excludedPermissions, excludedPackages, appOp, bOptions,
                ordered, sticky, callingPid, callingUid, realCallingUid, realCallingPid, userId,
                BackgroundStartPrivileges.NONE,
                null /* broadcastAllowList */, null /* filterExtrasForReceiver */);
    }

    @GuardedBy("this")
    final int broadcastIntentLocked(ProcessRecord callerApp, String callerPackage,
            @Nullable String callerFeatureId, Intent intent, String resolvedType,
            ProcessRecord resultToApp, IIntentReceiver resultTo, int resultCode, String resultData,
            Bundle resultExtras, String[] requiredPermissions,
            String[] excludedPermissions, String[] excludedPackages, int appOp, Bundle bOptions,
            boolean ordered, boolean sticky, int callingPid, int callingUid,
            int realCallingUid, int realCallingPid, int userId,
            BackgroundStartPrivileges backgroundStartPrivileges,
            @Nullable int[] broadcastAllowList,
            @Nullable BiFunction<Integer, Bundle, Bundle> filterExtrasForReceiver) {
        final int cookie = BroadcastQueue.traceBegin("broadcastIntentLockedTraced");
        final int res = broadcastIntentLockedTraced(callerApp, callerPackage, callerFeatureId,
                intent, resolvedType, resultToApp, resultTo, resultCode, resultData, resultExtras,
                requiredPermissions, excludedPermissions, excludedPackages, appOp,
                BroadcastOptions.fromBundleNullable(bOptions), ordered, sticky,
                callingPid, callingUid, realCallingUid, realCallingPid, userId,
                backgroundStartPrivileges, broadcastAllowList, filterExtrasForReceiver);
        BroadcastQueue.traceEnd(cookie);
        return res;
    }

    @GuardedBy("this")
    final int broadcastIntentLockedTraced(ProcessRecord callerApp, String callerPackage,
            @Nullable String callerFeatureId, Intent intent, String resolvedType,
            ProcessRecord resultToApp, IIntentReceiver resultTo, int resultCode, String resultData,
            Bundle resultExtras, String[] requiredPermissions,
            String[] excludedPermissions, String[] excludedPackages, int appOp,
            BroadcastOptions brOptions, boolean ordered, boolean sticky, int callingPid,
            int callingUid, int realCallingUid, int realCallingPid, int userId,
            BackgroundStartPrivileges backgroundStartPrivileges,
            @Nullable int[] broadcastAllowList,
            @Nullable BiFunction<Integer, Bundle, Bundle> filterExtrasForReceiver) {
        // Ensure all internal loopers are registered for idle checks
        BroadcastLoopers.addMyLooper();

        if (Process.isSdkSandboxUid(realCallingUid)) {
            final SdkSandboxManagerLocal sdkSandboxManagerLocal = LocalManagerRegistry.getManager(
                    SdkSandboxManagerLocal.class);
            if (sdkSandboxManagerLocal == null) {
                throw new IllegalStateException("SdkSandboxManagerLocal not found when sending"
                        + " a broadcast from an SDK sandbox uid.");
            }
            if (!sdkSandboxManagerLocal.canSendBroadcast(intent)) {
                throw new SecurityException(
                        "Intent " + intent.getAction() + " may not be broadcast from an SDK sandbox"
                        + " uid. Given caller package " + callerPackage + " (pid=" + callingPid
                        + ", realCallingUid=" + realCallingUid + ", callingUid= " + callingUid
                        + ")");
            }
        }

        if ((resultTo != null) && (resultToApp == null)) {
            if (resultTo.asBinder() instanceof BinderProxy) {
                // Warn when requesting results without a way to deliver them
                Slog.wtf(TAG, "Sending broadcast " + intent.getAction()
                        + " with resultTo requires resultToApp", new Throwable());
            } else {
                // If not a BinderProxy above, then resultTo is an in-process
                // receiver, so splice in system_server process
                resultToApp = getProcessRecordLocked("system", SYSTEM_UID);
            }
        }

        intent = new Intent(intent);

        final boolean callerInstantApp = isInstantApp(callerApp, callerPackage, callingUid);
        // Instant Apps cannot use FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS
        if (callerInstantApp) {
            intent.setFlags(intent.getFlags() & ~Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
        }

        if (userId == UserHandle.USER_ALL && broadcastAllowList != null) {
                Slog.e(TAG, "broadcastAllowList only applies when sending to individual users. "
                        + "Assuming restrictive whitelist.");
                broadcastAllowList = new int[]{};
        }

        // By default broadcasts do not go to stopped apps.
        intent.addFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);

        // If we have not finished booting, don't allow this to launch new processes.
        if (!mProcessesReady && (intent.getFlags()&Intent.FLAG_RECEIVER_BOOT_UPGRADE) == 0) {
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        }

        if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG_BROADCAST,
                (sticky ? "Broadcast sticky: ": "Broadcast: ") + intent
                        + " ordered=" + ordered + " userid=" + userId
                        + " options=" + (brOptions == null ? "null" : brOptions.toBundle()));
        if ((resultTo != null) && !ordered) {
            if (!UserHandle.isCore(callingUid)) {
                String msg = "Unauthorized unordered resultTo broadcast "
                             + intent + " sent from uid " + callingUid;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }
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
                scheduleCanceledResultTo(resultToApp, resultTo, intent, userId,
                        brOptions, callingUid, callerPackage);
                return ActivityManager.BROADCAST_FAILED_USER_STOPPED;
            }
        }

        final String action = intent.getAction();
        if (brOptions != null) {
            if (brOptions.getTemporaryAppAllowlistDuration() > 0) {
                // See if the caller is allowed to do this.  Note we are checking against
                // the actual real caller (not whoever provided the operation as say a
                // PendingIntent), because that who is actually supplied the arguments.
                if (checkComponentPermission(CHANGE_DEVICE_IDLE_TEMP_WHITELIST,
                        realCallingPid, realCallingUid, -1, true)
                        != PackageManager.PERMISSION_GRANTED
                        && checkComponentPermission(START_ACTIVITIES_FROM_BACKGROUND,
                        realCallingPid, realCallingUid, -1, true)
                        != PackageManager.PERMISSION_GRANTED
                        && checkComponentPermission(START_FOREGROUND_SERVICES_FROM_BACKGROUND,
                        realCallingPid, realCallingUid, -1, true)
                        != PackageManager.PERMISSION_GRANTED) {
                    String msg = "Permission Denial: " + intent.getAction()
                            + " broadcast from " + callerPackage + " (pid=" + callingPid
                            + ", uid=" + callingUid + ")"
                            + " requires "
                            + CHANGE_DEVICE_IDLE_TEMP_WHITELIST + " or "
                            + START_ACTIVITIES_FROM_BACKGROUND + " or "
                            + START_FOREGROUND_SERVICES_FROM_BACKGROUND;
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                }
            }
            if (brOptions.isDontSendToRestrictedApps()
                    && !isUidActiveLOSP(callingUid)
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
                    // We set the token to null since if it wasn't for it we'd allow anyway here
                    backgroundStartPrivileges = BackgroundStartPrivileges.ALLOW_BAL;
                }
            }

            if (brOptions.getIdForResponseEvent() > 0) {
                enforcePermission(android.Manifest.permission.ACCESS_BROADCAST_RESPONSE_STATS,
                        callingPid, callingUid, "recordResponseEventWhileInBackground");
            }
        }

        // Verify that protected broadcasts are only being sent by system code,
        // and that system code is only sending protected broadcasts.
        final boolean isProtectedBroadcast;
        try {
            isProtectedBroadcast = AppGlobals.getPackageManager().isProtectedBroadcast(action);
        } catch (RemoteException e) {
            Slog.w(TAG, "Remote exception", e);
            scheduleCanceledResultTo(resultToApp, resultTo, intent,
                    userId, brOptions, callingUid, callerPackage);
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

            // TODO: b/329211459 - Remove this after background remote intent is fixed.
            if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH)
                    && getWearRemoteIntentAction().equals(action)) {
                final int callerProcState = callerApp != null
                        ? callerApp.getCurProcState()
                        : ActivityManager.PROCESS_STATE_NONEXISTENT;
                if (ActivityManager.RunningAppProcessInfo.procStateToImportance(callerProcState)
                        > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    return ActivityManager.START_CANCELED;
                }
            }

            switch (action) {
                case Intent.ACTION_MEDIA_SCANNER_SCAN_FILE:
                    UserManagerInternal umInternal = LocalServices.getService(
                            UserManagerInternal.class);
                    UserInfo userInfo = umInternal.getUserInfo(userId);
                    if (userInfo != null && userInfo.isCloneProfile()) {
                        userId = umInternal.getProfileParentId(userId);
                    }
                    break;
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
                                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                                    mAppOpsService.resetAllModes(UserHandle.getUserId(uid),
                                            intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME));
                                } else {
                                    mAppOpsService.uidRemoved(uid);
                                    mServices.onUidRemovedLocked(uid);
                                }
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
                                            false, false, false, userId, "storage unmount");
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
                                                false, true, true, false, fullUninstall, false,
                                                userId, "pkg removed");
                                        getPackageManagerInternal()
                                                .onPackageProcessKilledForUninstall(ssp);
                                    } else {
                                        // Kill any app zygotes always, since they can't fork new
                                        // processes with references to the old code
                                        forceStopAppZygoteLocked(ssp, UserHandle.getAppId(
                                                intent.getIntExtra(Intent.EXTRA_UID, -1)),
                                                userId);
                                    }
                                    final int cmd = killProcess
                                            ? ApplicationThreadConstants.PACKAGE_REMOVED
                                            : ApplicationThreadConstants.PACKAGE_REMOVED_DONT_KILL;
                                    sendPackageBroadcastLocked(cmd,
                                            new String[] {ssp}, userId);
                                    if (fullUninstall) {
                                        // Remove all permissions granted from/to this package
                                        mUgmInternal.removeUriPermissionsForPackage(ssp, userId,
                                                true, false);

                                        mAtmInternal.removeRecentTasksByPackageName(ssp, userId);

                                        mServices.forceStopPackageLocked(ssp, userId);
                                        mAtmInternal.onPackageUninstalled(ssp, userId);
                                        mBatteryStatsService.notePackageUninstalled(ssp);
                                    }
                                } else {
                                    if (killProcess) {
                                        int reason;
                                        int subReason;
                                        if (replacing) {
                                            reason = ApplicationExitInfo.REASON_PACKAGE_UPDATED;
                                            subReason = ApplicationExitInfo.SUBREASON_UNKNOWN;
                                        } else {
                                            reason =
                                                    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE;
                                            subReason = ApplicationExitInfo.SUBREASON_UNKNOWN;
                                        }

                                        final int extraUid = intent.getIntExtra(Intent.EXTRA_UID,
                                                -1);
                                        synchronized (mProcLock) {
                                            mProcessList.killPackageProcessesLSP(ssp,
                                                    UserHandle.getAppId(extraUid),
                                                    userId, ProcessList.INVALID_ADJ,
                                                    reason,
                                                    subReason,
                                                    "change " + ssp);
                                        }
                                    }
                                    cleanupDisabledPackageComponentsLocked(ssp, userId,
                                            intent.getStringArrayExtra(
                                                    Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST));
                                    mServices.schedulePendingServiceStartLocked(ssp, userId);
                                }
                            }
                            break;
                        case Intent.ACTION_PACKAGES_SUSPENDED:
                        case Intent.ACTION_PACKAGES_UNSUSPENDED:
                            final boolean suspended = Intent.ACTION_PACKAGES_SUSPENDED.equals(
                                    intent.getAction());
                            final String[] packageNames = intent.getStringArrayExtra(
                                    Intent.EXTRA_CHANGED_PACKAGE_LIST);
                            final int userIdExtra = intent.getIntExtra(
                                    Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);

                            mAtmInternal.onPackagesSuspendedChanged(packageNames, suspended,
                                    userIdExtra);

                            final boolean quarantined = intent.getBooleanExtra(
                                    Intent.EXTRA_QUARANTINED, false);
                            if (suspended && quarantined && packageNames != null) {
                                for (int i = 0; i < packageNames.length; i++) {
                                    forceStopPackage(packageNames[i], userId,
                                            ActivityManager.FLAG_OR_STOPPED, "quarantined");
                                }
                            }

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
                            scheduleCanceledResultTo(resultToApp, resultTo, intent,
                                    userId, brOptions, callingUid, callerPackage);
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
                                    ai != null ? ai.longVersionCode : 0);
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
                        mAtmInternal.onPackageDataCleared(ssp, userId);
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
                    mBatteryStatsService.noteCurrentTimeChanged();
                    break;
                case ConnectivityManager.ACTION_CLEAR_DNS_CACHE:
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
                    scheduleCanceledResultTo(resultToApp, resultTo, intent,
                            userId, brOptions, callingUid, callerPackage);
                    return ActivityManager.BROADCAST_SUCCESS;
                case Intent.ACTION_PRE_BOOT_COMPLETED:
                    timeoutExempt = true;
                    break;
                case Intent.ACTION_CLOSE_SYSTEM_DIALOGS:
                    if (!mAtmInternal.checkCanCloseSystemDialogs(callingPid, callingUid,
                            callerPackage)) {
                        scheduleCanceledResultTo(resultToApp, resultTo, intent,
                                userId, brOptions, callingUid, callerPackage);
                        // Returning success seems to be the pattern here
                        return ActivityManager.BROADCAST_SUCCESS;
                    }
                    break;
            }

            if (Intent.ACTION_PACKAGE_ADDED.equals(action) ||
                    Intent.ACTION_PACKAGE_REMOVED.equals(action) ||
                    Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                final int uid = getUidFromIntent(intent);
                if (uid != -1) {
                    final UidRecord uidRec = mProcessList.getUidRecordLOSP(uid);
                    if (uidRec != null) {
                        uidRec.updateHasInternetPermission();
                    }
                }
            }
        }

        final int callerAppProcessState = getRealProcessStateLocked(callerApp, realCallingPid);
        // Add to the sticky list if requested.
        if (sticky) {
            if (checkPermission(android.Manifest.permission.BROADCAST_STICKY,
                    callingPid, callingUid)
                    != PackageManager.PERMISSION_GRANTED) {
                String msg =
                        "Permission Denial: broadcastIntent() requesting a sticky broadcast from"
                            + " pid="
                                + callingPid
                                + ", uid="
                                + callingUid
                                + " requires "
                                + android.Manifest.permission.BROADCAST_STICKY;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }
            if (requiredPermissions != null && requiredPermissions.length > 0) {
                Slog.w(TAG, "Can't broadcast sticky intent " + intent
                        + " and enforce permissions " + Arrays.toString(requiredPermissions));
                scheduleCanceledResultTo(resultToApp, resultTo, intent,
                        userId, brOptions, callingUid, callerPackage);
                return ActivityManager.BROADCAST_STICKY_CANT_HAVE_PERMISSION;
            }
            if (intent.getComponent() != null) {
                throw new SecurityException(
                        "Sticky broadcasts can't target a specific component");
            }
            synchronized (mStickyBroadcasts) {
                // We use userId directly here, since the "all" target is maintained
                // as a separate set of sticky broadcasts.
                if (userId != UserHandle.USER_ALL) {
                    // But first, if this is not a broadcast to all users, then
                    // make sure it doesn't conflict with an existing broadcast to
                    // all users.
                    ArrayMap<String, ArrayList<StickyBroadcast>> stickies = mStickyBroadcasts.get(
                            UserHandle.USER_ALL);
                    if (stickies != null) {
                        ArrayList<StickyBroadcast> list = stickies.get(intent.getAction());
                        if (list != null) {
                            int N = list.size();
                            int i;
                            for (i = 0; i < N; i++) {
                                if (intent.filterEquals(list.get(i).intent)) {
                                    throw new IllegalArgumentException("Sticky broadcast " + intent
                                            + " for user " + userId
                                            + " conflicts with existing global broadcast");
                                }
                            }
                        }
                    }
                }
                ArrayMap<String, ArrayList<StickyBroadcast>> stickies =
                        mStickyBroadcasts.get(userId);
                if (stickies == null) {
                    stickies = new ArrayMap<>();
                    mStickyBroadcasts.put(userId, stickies);
                }
                ArrayList<StickyBroadcast> list = stickies.get(intent.getAction());
                if (list == null) {
                    list = new ArrayList<>();
                    stickies.put(intent.getAction(), list);
                }
                final boolean deferUntilActive = BroadcastRecord.calculateDeferUntilActive(
                        callingUid, brOptions, resultTo, ordered,
                        BroadcastRecord.calculateUrgent(intent, brOptions));
                final int stickiesCount = list.size();
                int i;
                for (i = 0; i < stickiesCount; i++) {
                    if (intent.filterEquals(list.get(i).intent)) {
                        // This sticky already exists, replace it.
                        list.set(i, StickyBroadcast.create(new Intent(intent), deferUntilActive,
                                callingUid, callerAppProcessState, resolvedType));
                        break;
                    }
                }
                if (i >= stickiesCount) {
                    list.add(StickyBroadcast.create(new Intent(intent), deferUntilActive,
                            callingUid, callerAppProcessState, resolvedType));
                }
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
        final int cookie = BroadcastQueue.traceBegin("queryReceivers");
        List receivers = null;
        List<BroadcastFilter> registeredReceivers = null;
        // Need to resolve the intent to interested receivers...
        if ((intent.getFlags() & Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0) {
            receivers = collectReceiverComponents(
                    intent, resolvedType, callingUid, users, broadcastAllowList);
        }
        if (intent.getComponent() == null) {
            final PackageDataSnapshot snapshot = getPackageManagerInternal().snapshot();
            if (userId == UserHandle.USER_ALL && callingUid == SHELL_UID) {
                // Query one target user at a time, excluding shell-restricted users
                for (int i = 0; i < users.length; i++) {
                    if (mUserController.hasUserRestriction(
                            UserManager.DISALLOW_DEBUGGING_FEATURES, users[i])) {
                        continue;
                    }
                    List<BroadcastFilter> registeredReceiversForUser =
                            mReceiverResolver.queryIntent(snapshot, intent,
                                    resolvedType, false /*defaultOnly*/, users[i]);
                    if (registeredReceivers == null) {
                        registeredReceivers = registeredReceiversForUser;
                    } else if (registeredReceiversForUser != null) {
                        registeredReceivers.addAll(registeredReceiversForUser);
                    }
                }
            } else {
                registeredReceivers = mReceiverResolver.queryIntent(snapshot, intent,
                        resolvedType, false /*defaultOnly*/, userId);
            }
            if (registeredReceivers != null) {
                PackageManagerServiceUtils.applyNullActionBlocking(
                        mPlatformCompat, snapshot, registeredReceivers,
                        true, intent, callingUid);
            }
        }
        BroadcastQueue.traceEnd(cookie);

        final boolean replacePending =
                (intent.getFlags()&Intent.FLAG_RECEIVER_REPLACE_PENDING) != 0;

        if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Enqueueing broadcast: " + intent.getAction()
                + " replacePending=" + replacePending);
        if (registeredReceivers != null && broadcastAllowList != null) {
            // if a uid whitelist was provided, remove anything in the application space that wasn't
            // in it.
            for (int i = registeredReceivers.size() - 1; i >= 0; i--) {
                final int owningAppId = UserHandle.getAppId(registeredReceivers.get(i).owningUid);
                if (owningAppId >= Process.FIRST_APPLICATION_UID
                        && Arrays.binarySearch(broadcastAllowList, owningAppId) < 0) {
                    registeredReceivers.remove(i);
                }
            }
        }

        filterNonExportedComponents(intent, callingUid, callingPid, registeredReceivers,
                mPlatformCompat, callerPackage, resolvedType);
        int NR = registeredReceivers != null ? registeredReceivers.size() : 0;

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
            BroadcastQueue queue = mBroadcastQueue;
            filterNonExportedComponents(intent, callingUid, callingPid, receivers,
                    mPlatformCompat, callerPackage, resolvedType);
            BroadcastRecord r = new BroadcastRecord(queue, intent, callerApp, callerPackage,
                    callerFeatureId, callingPid, callingUid, callerInstantApp, resolvedType,
                    requiredPermissions, excludedPermissions, excludedPackages, appOp, brOptions,
                    receivers, resultToApp, resultTo, resultCode, resultData, resultExtras,
                    ordered, sticky, false, userId,
                    backgroundStartPrivileges, timeoutExempt, filterExtrasForReceiver,
                    callerAppProcessState);

            if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Enqueueing ordered broadcast " + r);
            queue.enqueueBroadcastLocked(r);
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

    @GuardedBy("this")
    private void scheduleCanceledResultTo(ProcessRecord resultToApp, IIntentReceiver resultTo,
            Intent intent, int userId, BroadcastOptions options, int callingUid,
            String callingPackage) {
        if (resultTo == null) {
            return;
        }
        final ProcessRecord app = resultToApp;
        final IApplicationThread thread  = (app != null) ? app.getOnewayThread() : null;
        if (thread != null) {
            try {
                final boolean shareIdentity = (options != null && options.isShareIdentityEnabled());
                thread.scheduleRegisteredReceiver(
                        resultTo, intent, Activity.RESULT_CANCELED, null, null,
                        false, false, true, userId, app.mState.getReportedProcState(),
                        shareIdentity ? callingUid : Process.INVALID_UID,
                        shareIdentity ? callingPackage : null);
            } catch (RemoteException e) {
                final String msg = "Failed to schedule result of " + intent + " via "
                        + app + ": " + e;
                app.killLocked("Can't schedule resultTo", ApplicationExitInfo.REASON_OTHER,
                        ApplicationExitInfo.SUBREASON_UNDELIVERED_BROADCAST, true);
                Slog.d(TAG, msg);
            }
        }
    }

    @GuardedBy("this")
    private int getRealProcessStateLocked(ProcessRecord app, int pid) {
        if (app == null) {
            synchronized (mPidsSelfLocked) {
                app = mPidsSelfLocked.get(pid);
            }
        }
        if (app != null && app.getThread() != null && !app.isKilled()) {
            return app.mState.getCurProcState();
        }
        return PROCESS_STATE_NONEXISTENT;
    }

    @VisibleForTesting
    ArrayList<StickyBroadcast> getStickyBroadcastsForTest(String action, int userId) {
        synchronized (mStickyBroadcasts) {
            final ArrayMap<String, ArrayList<StickyBroadcast>> stickyBroadcasts =
                    mStickyBroadcasts.get(userId);
            if (stickyBroadcasts == null) {
                return null;
            }
            return stickyBroadcasts.get(action);
        }
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

    final void notifyBroadcastFinishedLocked(@NonNull BroadcastRecord original) {
        final ApplicationInfo info = original.callerApp != null ? original.callerApp.info : null;
        final String callerPackage = info != null ? info.packageName : original.callerPackage;
        if (callerPackage != null) {
            mHandler.obtainMessage(ActivityManagerService.DISPATCH_SENDING_BROADCAST_EVENT,
                    original.callingUid, 0, callerPackage).sendToTarget();
        }
    }

    final Intent verifyBroadcastLocked(Intent intent) {
        if (intent != null) {
            // Refuse possible leaked file descriptors
            if (intent.hasFileDescriptors()) {
                throw new IllegalArgumentException("File descriptors passed in Intent");
            }
            // Remove existing mismatch flag so it can be properly updated later
            intent.removeExtendedFlags(Intent.EXTENDED_FLAG_FILTER_MISMATCH);
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

    /**
     * @deprecated Use {@link #broadcastIntentWithFeature}
     */
    @Deprecated
    public final int broadcastIntent(IApplicationThread caller,
            Intent intent, String resolvedType, IIntentReceiver resultTo,
            int resultCode, String resultData, Bundle resultExtras,
            String[] requiredPermissions, int appOp, Bundle bOptions,
            boolean serialized, boolean sticky, int userId) {
        return broadcastIntentWithFeature(caller, null, intent, resolvedType, resultTo, resultCode,
                resultData, resultExtras, requiredPermissions, null, null, appOp, bOptions,
                serialized, sticky, userId);
    }

    @Override
    public final int broadcastIntentWithFeature(IApplicationThread caller, String callingFeatureId,
            Intent intent, String resolvedType, IIntentReceiver resultTo,
            int resultCode, String resultData, Bundle resultExtras,
            String[] requiredPermissions, String[] excludedPermissions,
            String[] excludedPackages, int appOp, Bundle bOptions,
            boolean serialized, boolean sticky, int userId) {
        enforceNotIsolatedCaller("broadcastIntent");
        synchronized(this) {
            intent = verifyBroadcastLocked(intent);

            final ProcessRecord callerApp = getRecordForAppLOSP(caller);
            final int callingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();

            // We're delivering the result to the caller
            final ProcessRecord resultToApp = callerApp;

            // Permission regimes around sender-supplied broadcast options.
            enforceBroadcastOptionPermissionsInternal(bOptions, callingUid);

            final long origId = Binder.clearCallingIdentity();
            try {
                return broadcastIntentLocked(callerApp,
                        callerApp != null ? callerApp.info.packageName : null, callingFeatureId,
                        intent, resolvedType, resultToApp, resultTo, resultCode, resultData,
                        resultExtras, requiredPermissions, excludedPermissions, excludedPackages,
                        appOp, bOptions, serialized, sticky, callingPid, callingUid, callingUid,
                        callingPid, userId, BackgroundStartPrivileges.NONE, null, null);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    // Not the binder call surface
    int broadcastIntentInPackage(String packageName, @Nullable String featureId, int uid,
            int realCallingUid, int realCallingPid, Intent intent, String resolvedType,
            ProcessRecord resultToApp, IIntentReceiver resultTo, int resultCode,
            String resultData, Bundle resultExtras, String requiredPermission, Bundle bOptions,
            boolean serialized, boolean sticky, int userId,
            BackgroundStartPrivileges backgroundStartPrivileges,
            @Nullable int[] broadcastAllowList) {
        synchronized(this) {
            intent = verifyBroadcastLocked(intent);

            final long origId = Binder.clearCallingIdentity();
            String[] requiredPermissions = requiredPermission == null ? null
                    : new String[] {requiredPermission};
            try {
                return broadcastIntentLocked(null, packageName, featureId, intent, resolvedType,
                        resultToApp, resultTo, resultCode, resultData, resultExtras,
                        requiredPermissions, null, null, OP_NONE, bOptions, serialized, sticky, -1,
                        uid, realCallingUid, realCallingPid, userId,
                        backgroundStartPrivileges, broadcastAllowList,
                        null /* filterExtrasForReceiver */);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public final void unbroadcastIntent(IApplicationThread caller, Intent intent, int userId) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        userId = mUserController.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, true, ALLOW_NON_FULL, "removeStickyBroadcast", null);

        if (checkCallingPermission(android.Manifest.permission.BROADCAST_STICKY)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: unbroadcastIntent() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.BROADCAST_STICKY;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        synchronized (mStickyBroadcasts) {
            ArrayMap<String, ArrayList<StickyBroadcast>> stickies = mStickyBroadcasts.get(userId);
            if (stickies != null) {
                ArrayList<StickyBroadcast> list = stickies.get(intent.getAction());
                if (list != null) {
                    int N = list.size();
                    int i;
                    for (i = 0; i < N; i++) {
                        if (intent.filterEquals(list.get(i).intent)) {
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
        mBroadcastQueue.backgroundServicesFinishedLocked(userId);
    }

    public void finishReceiver(IBinder caller, int resultCode, String resultData,
            Bundle resultExtras, boolean resultAbort, int flags) {
        if (DEBUG_BROADCAST) Slog.v(TAG_BROADCAST, "Finish receiver: " + caller);

        // Refuse possible leaked file descriptors
        if (resultExtras != null && resultExtras.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Bundle");
        }

        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized(this) {
                final ProcessRecord callerApp = getRecordForAppLOSP(caller);
                if (callerApp == null) {
                    Slog.w(TAG, "finishReceiver: no app for " + caller);
                    return;
                }

                mBroadcastQueue.finishReceiverLocked(callerApp, resultCode,
                        resultData, resultExtras, resultAbort, true);
                // updateOomAdjLocked() will be done here
                trimApplicationsLocked(false, OOM_ADJ_REASON_FINISH_RECEIVER);
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
        final IPackageManager pm = AppGlobals.getPackageManager();

        synchronized(this) {
            InstrumentationInfo ii = null;
            ApplicationInfo ai = null;

            boolean noRestart = (flags & INSTR_FLAG_NO_RESTART) != 0;

            try {
                ii = pm.getInstrumentationInfoAsUser(className, STOCK_PM_FLAGS, userId);
                if (ii == null) {
                    reportStartInstrumentationFailureLocked(watcher, className,
                            "Unable to find instrumentation info for: " + className);
                    return false;
                }
                ai = pm.getApplicationInfo(ii.targetPackage, STOCK_PM_FLAGS, userId);
                if (ai == null) {
                    reportStartInstrumentationFailureLocked(watcher, className,
                            "Unable to find instrumentation target package: " + ii.targetPackage);
                    return false;
                }
            } catch (RemoteException e) {
            }

            if (ii.targetPackage.equals("android")) {
                if (!noRestart) {
                    reportStartInstrumentationFailureLocked(watcher, className,
                            "Cannot instrument system server without 'no-restart'");
                    return false;
                }
            } else if (!ai.hasCode()) {
                reportStartInstrumentationFailureLocked(watcher, className,
                        "Instrumentation target has no code: " + ii.targetPackage);
                return false;
            }

            int match = SIGNATURE_NO_MATCH;
            try {
                match = pm.checkSignatures(ii.targetPackage, ii.packageName, userId);
            } catch (RemoteException e) {
            }
            if (match < 0 && match != PackageManager.SIGNATURE_FIRST_NOT_SIGNED) {
                if (Build.IS_DEBUGGABLE && (callingUid == Process.ROOT_UID)
                        && (flags & INSTR_FLAG_ALWAYS_CHECK_SIGNATURE) == 0) {
                    Slog.w(TAG, "Instrumentation test " + ii.packageName
                            + " doesn't have a signature matching the target " + ii.targetPackage
                            + ", which would not be allowed on the production Android builds");
                } else {
                    String msg = "Permission Denial: starting instrumentation "
                            + className + " from pid="
                            + Binder.getCallingPid()
                            + ", uid=" + Binder.getCallingUid()
                            + " not allowed because package " + ii.packageName
                            + " does not have a signature matching the target "
                            + ii.targetPackage;
                    reportStartInstrumentationFailureLocked(watcher, className, msg);
                    throw new SecurityException(msg);
                }
            }

            if (!Build.IS_DEBUGGABLE && callingUid != ROOT_UID && callingUid != SHELL_UID
                    && callingUid != SYSTEM_UID && !hasActiveInstrumentationLocked(callingPid)) {
                // If it's not debug build and not called from root/shell/system uid, reject it.
                final String msg = "Permission Denial: instrumentation test "
                        + className + " from pid=" + callingPid + ", uid=" + callingUid
                        + ", pkgName=" + mInternal.getPackageNameByPid(callingPid)
                        + " not allowed because it's not started from SHELL";
                Slog.wtfQuiet(TAG, msg);
                reportStartInstrumentationFailureLocked(watcher, className, msg);
                throw new SecurityException(msg);
            }

            boolean disableHiddenApiChecks = ai.usesNonSdkApi()
                    || (flags & INSTR_FLAG_DISABLE_HIDDEN_API_CHECKS) != 0;
            boolean disableTestApiChecks = disableHiddenApiChecks
                    || (flags & INSTR_FLAG_DISABLE_TEST_API_CHECKS) != 0;

            if (disableHiddenApiChecks || disableTestApiChecks) {
                enforceCallingPermission(android.Manifest.permission.DISABLE_HIDDEN_API_CHECKS,
                        "disable hidden API checks");
            }

            if ((flags & ActivityManager.INSTR_FLAG_INSTRUMENT_SDK_SANDBOX) != 0) {
                return startInstrumentationOfSdkSandbox(
                        className,
                        profileFile,
                        arguments,
                        watcher,
                        uiAutomationConnection,
                        userId,
                        abiOverride,
                        ii,
                        ai,
                        noRestart,
                        disableHiddenApiChecks,
                        disableTestApiChecks,
                        (flags & ActivityManager.INSTR_FLAG_INSTRUMENT_SDK_IN_SANDBOX) != 0);
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
            activeInstr.mHasBackgroundForegroundServiceStartsPermission = checkPermission(
                    START_FOREGROUND_SERVICES_FROM_BACKGROUND, callingPid, callingUid)
                            == PackageManager.PERMISSION_GRANTED;
            activeInstr.mNoRestart = noRestart;

            final long origId = Binder.clearCallingIdentity();

            ProcessRecord app;
            synchronized (mProcLock) {
                if (noRestart) {
                    app = getProcessRecordLocked(ai.processName, ai.uid);
                } else {
                    // Instrumentation can kill and relaunch even persistent processes
                    forceStopPackageLocked(ii.targetPackage, -1, true, false, true, true, false,
                            false, userId, "start instr");
                    // Inform usage stats to make the target package active
                    if (mUsageStatsService != null) {
                        mUsageStatsService.reportEvent(ii.targetPackage, userId,
                                UsageEvents.Event.SYSTEM_INTERACTION);
                    }
                    app = addAppLocked(ai, defProcess, false, disableHiddenApiChecks,
                            disableTestApiChecks, abiOverride, ZYGOTE_POLICY_FLAG_EMPTY);
                    app.mProfile.addHostingComponentType(HOSTING_COMPONENT_TYPE_INSTRUMENTATION);
                }

                app.setActiveInstrumentation(activeInstr);
                activeInstr.mFinished = false;
                activeInstr.mSourceUid = callingUid;
                activeInstr.mRunningProcesses.add(app);
                if (!mActiveInstrumentation.contains(activeInstr)) {
                    mActiveInstrumentation.add(activeInstr);
                }
            }

            if ((flags & INSTR_FLAG_DISABLE_ISOLATED_STORAGE) != 0) {
                // Allow OP_NO_ISOLATED_STORAGE app op for the package running instrumentation with
                // --no-isolated-storage flag.
                mAppOpsService.setMode(AppOpsManager.OP_NO_ISOLATED_STORAGE, ai.uid,
                        ii.packageName, AppOpsManager.MODE_ALLOWED);
            }
            Binder.restoreCallingIdentity(origId);

            if (noRestart) {
                instrumentWithoutRestart(activeInstr, ai);
            }
        }

        return true;
    }

    @GuardedBy("this")
    private boolean hasActiveInstrumentationLocked(int pid) {
        if (pid == 0) {
            return false;
        }
        synchronized (mPidsSelfLocked) {
            ProcessRecord process = mPidsSelfLocked.get(pid);
            return process != null && process.getActiveInstrumentation() != null;
        }
    }

    @GuardedBy("this")
    private boolean startInstrumentationOfSdkSandbox(
            ComponentName className,
            String profileFile,
            Bundle arguments,
            IInstrumentationWatcher watcher,
            IUiAutomationConnection uiAutomationConnection,
            int userId,
            String abiOverride,
            InstrumentationInfo instrumentationInfo,
            ApplicationInfo sdkSandboxClientAppInfo,
            boolean noRestart,
            boolean disableHiddenApiChecks,
            boolean disableTestApiChecks,
            boolean isSdkInSandbox) {

        if (noRestart) {
            reportStartInstrumentationFailureLocked(
                    watcher,
                    className,
                    "Instrumenting sdk sandbox with --no-restart flag is not supported");
            return false;
        }

        final SdkSandboxManagerLocal sandboxManagerLocal =
                LocalManagerRegistry.getManager(SdkSandboxManagerLocal.class);
        if (sandboxManagerLocal == null) {
            reportStartInstrumentationFailureLocked(
                    watcher, className, "Can't locate SdkSandboxManagerLocal");
            return false;
        }

        final ApplicationInfo sdkSandboxInfo;
        final String processName;
        try {
            if (sdkSandboxInstrumentationInfo()) {
                sdkSandboxInfo =
                        sandboxManagerLocal.getSdkSandboxApplicationInfoForInstrumentation(
                                sdkSandboxClientAppInfo, isSdkInSandbox);
                processName = sdkSandboxInfo.processName;
            } else {
                final PackageManager pm = mContext.getPackageManager();
                sdkSandboxInfo =
                        pm.getApplicationInfoAsUser(pm.getSdkSandboxPackageName(), 0, userId);
                processName =
                        sandboxManagerLocal.getSdkSandboxProcessNameForInstrumentation(
                                sdkSandboxClientAppInfo);
                sdkSandboxInfo.uid = Process.toSdkSandboxUid(sdkSandboxClientAppInfo.uid);
            }
        } catch (NameNotFoundException e) {
            reportStartInstrumentationFailureLocked(
                    watcher, className, "Can't find SdkSandbox package");
            return false;
        }

        ActiveInstrumentation activeInstr = new ActiveInstrumentation(this);
        activeInstr.mClass = className;
        activeInstr.mTargetProcesses = new String[]{processName};
        activeInstr.mTargetInfo = sdkSandboxInfo;
        activeInstr.mIsSdkInSandbox = isSdkInSandbox;
        activeInstr.mProfileFile = profileFile;
        activeInstr.mArguments = arguments;
        activeInstr.mWatcher = watcher;
        activeInstr.mUiAutomationConnection = uiAutomationConnection;
        activeInstr.mResultClass = className;
        activeInstr.mHasBackgroundActivityStartsPermission =
                isSdkInSandbox
                        // TODO(b/261864298): consider using START_ACTIVITIES_FROM_BACKGROUND.
                        && checkPermission(
                                        android.Manifest.permission
                                                .START_ACTIVITIES_FROM_SDK_SANDBOX,
                                        Binder.getCallingPid(),
                                        Binder.getCallingUid())
                                == PackageManager.PERMISSION_GRANTED;
        activeInstr.mHasBackgroundForegroundServiceStartsPermission = false;
        // Instrumenting sdk sandbox without a restart is not supported
        activeInstr.mNoRestart = false;

        final int callingUid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        try {
            sandboxManagerLocal.notifyInstrumentationStarted(
                    sdkSandboxClientAppInfo.packageName, sdkSandboxClientAppInfo.uid);
            synchronized (mProcLock) {
                // Kill the package sdk sandbox process belong to. At this point sdk sandbox is
                // already killed.
                forceStopPackageLocked(
                        instrumentationInfo.targetPackage,
                        /* appId= */ -1,
                        /* callerWillRestart= */ true,
                        /* purgeCache= */ false,
                        /* doIt= */ true,
                        /* evenPersistent= */ true,
                        /* uninstalling= */ false,
                        /* packageStateStopped= */ false,
                        userId,
                        "start instr");

                ProcessRecord app = addAppLocked(
                        sdkSandboxInfo,
                        processName,
                        /* isolated= */ false,
                        /* isSdkSandbox= */ true,
                        sdkSandboxInfo.uid,
                        sdkSandboxClientAppInfo.packageName,
                        disableHiddenApiChecks,
                        disableTestApiChecks,
                        abiOverride,
                        ZYGOTE_POLICY_FLAG_EMPTY);

                app.setActiveInstrumentation(activeInstr);
                activeInstr.mFinished = false;
                activeInstr.mSourceUid = callingUid;
                activeInstr.mRunningProcesses.add(app);
                if (!mActiveInstrumentation.contains(activeInstr)) {
                    mActiveInstrumentation.add(activeInstr);
                }
                app.mProfile.addHostingComponentType(HOSTING_COMPONENT_TYPE_INSTRUMENTATION);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        return true;
    }

    private void instrumentWithoutRestart(ActiveInstrumentation activeInstr,
            ApplicationInfo targetInfo) {
        ProcessRecord pr;
        synchronized (this) {
            pr = getProcessRecordLocked(targetInfo.processName, targetInfo.uid);
        }

        try {
            pr.getThread().instrumentWithoutRestart(
                    activeInstr.mClass,
                    activeInstr.mArguments,
                    activeInstr.mWatcher,
                    activeInstr.mUiAutomationConnection,
                    targetInfo);
        } catch (RemoteException e) {
            Slog.i(TAG, "RemoteException from instrumentWithoutRestart", e);
        }
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
            ProcessRecord app = getRecordForAppLOSP(target);
            if (app == null) {
                Slog.w(TAG, "addInstrumentationResults: no app for " + target);
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            try {
                addInstrumentationResultsLocked(app, results);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @GuardedBy("this")
    void finishInstrumentationLocked(ProcessRecord app, int resultCode, Bundle results) {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "finishInstrumentationLocked()");
            final ActiveInstrumentation instr = app.getActiveInstrumentation();
            if (instr == null) {
                Slog.w(TAG, "finishInstrumentation called on non-instrumented: " + app);
                return;
            }

            synchronized (mProcLock) {
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
                        // Go back to the default mode of denying OP_NO_ISOLATED_STORAGE app op.
                        mAppOpsService.setMode(AppOpsManager.OP_NO_ISOLATED_STORAGE, app.uid,
                                app.info.packageName, AppOpsManager.MODE_ERRORED);
                        getAccessCheckDelegateHelper()
                                .onInstrumentationFinished(app.uid, app.info.packageName);
                        mHandler.obtainMessage(SHUTDOWN_UI_AUTOMATION_CONNECTION_MSG,
                                instr.mUiAutomationConnection).sendToTarget();
                    }
                    instr.mFinished = true;
                }

                instr.removeProcess(app);
                app.setActiveInstrumentation(null);
            }
            app.mProfile.clearHostingComponentType(HOSTING_COMPONENT_TYPE_INSTRUMENTATION);

            if (app.isSdkSandbox) {
                // For sharedUid apps this will kill all sdk sandbox processes, which is not ideal.
                // TODO(b/209061624): should we call ProcessList.removeProcessLocked instead?
                killUid(UserHandle.getAppId(app.uid), UserHandle.getUserId(app.uid),
                        "finished instr");
                final SdkSandboxManagerLocal sandboxManagerLocal =
                        LocalManagerRegistry.getManager(SdkSandboxManagerLocal.class);
                if (sandboxManagerLocal != null) {
                    sandboxManagerLocal.notifyInstrumentationFinished(
                            app.sdkSandboxClientAppPackage,
                            Process.getAppUidForSdkSandboxUid(app.uid));
                }
            } else if (!instr.mNoRestart) {
                forceStopPackageLocked(app.info.packageName, -1, false, false, true, true, false,
                        false, app.userId, "finished inst");
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    public void finishInstrumentation(IApplicationThread target,
            int resultCode, Bundle results) {
        int userId = UserHandle.getCallingUserId();
        // Refuse possible leaked file descriptors
        if (results != null && results.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            ProcessRecord app = getRecordForAppLOSP(target);
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
    public RootTaskInfo getFocusedRootTaskInfo() throws RemoteException {
        return mActivityTaskManager.getFocusedRootTaskInfo();
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
        updatePersistentConfigurationWithAttribution(values,
                Settings.getPackageNameForUid(mContext, Binder.getCallingUid()), null);
    }

    @Override
    public void updatePersistentConfigurationWithAttribution(Configuration values,
            String callingPackage, String callingAttributionTag) {
        enforceCallingPermission(CHANGE_CONFIGURATION, "updatePersistentConfiguration()");
        enforceWriteSettingsPermission("updatePersistentConfiguration()", callingPackage,
                callingAttributionTag);
        if (values == null) {
            throw new NullPointerException("Configuration must not be null");
        }

        int userId = UserHandle.getCallingUserId();

        if (UserManager.isVisibleBackgroundUsersEnabled() && userId != getCurrentUserId()) {
            // The check is added mainly for auto devices. On auto devices, it is possible that
            // multiple users are visible simultaneously using visible background users.
            // In such cases, it is desired that only the current user (not the visible background
            // user) can change the locale and other persistent settings of the device.
            Slog.w(TAG, "Only current user is allowed to update persistent configuration if "
                    + "visible background users are enabled. Current User" + getCurrentUserId()
                    + ". Calling User: " + userId);
            throw new SecurityException("Only current user is allowed to update persistent "
                    + "configuration.");
        }

        mActivityTaskManager.updatePersistentConfiguration(values, userId);
    }

    private void enforceWriteSettingsPermission(String func, String callingPackage,
            String callingAttributionTag) {
        int uid = Binder.getCallingUid();
        if (uid == ROOT_UID) {
            return;
        }

        if (Settings.checkAndNoteWriteSettingsOperation(mContext, uid,
                callingPackage, callingAttributionTag, false)) {
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
    public boolean updateMccMncConfiguration(String mcc, String mnc) {
        int mccInt, mncInt;
        try {
            mccInt = Integer.parseInt(mcc);
            mncInt = Integer.parseInt(mnc);
        } catch (NumberFormatException | StringIndexOutOfBoundsException ex) {
            Slog.e(TAG, "Error parsing mcc: " + mcc + " mnc: " + mnc + ". ex=" + ex);
            return false;
        }
        Configuration config = new Configuration();
        config.mcc = mccInt;
        config.mnc = mncInt == 0 ? Configuration.MNC_ZERO : mncInt;
        return mActivityTaskManager.updateConfiguration(config);
    }

    @Override
    public int getLaunchedFromUid(IBinder activityToken) {
        return ActivityClient.getInstance().getLaunchedFromUid(activityToken);
    }

    public String getLaunchedFromPackage(IBinder activityToken) {
        return ActivityClient.getInstance().getLaunchedFromPackage(activityToken);
    }

    // =========================================================
    // LIFETIME MANAGEMENT
    // =========================================================

    boolean isReceivingBroadcastLocked(ProcessRecord app, int[] outSchedGroup) {
        final int res = mBroadcastQueue.getPreferredSchedulingGroupLocked(app);
        outSchedGroup[0] = res;
        return res != ProcessList.SCHED_GROUP_UNDEFINED;
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

    void noteUidProcessState(final int uid, final int state,
                final @ProcessCapability int capability) {
        mBatteryStatsService.noteUidProcessState(uid, state);
        mAppOpsService.updateUidProcState(uid, state, capability);
        if (StatsPullAtomService.ENABLE_MOBILE_DATA_STATS_AGGREGATED_PULLER) {
            try {
                if (mStatsPullAtomServiceInternal == null) {
                    mStatsPullAtomServiceInternal = LocalServices.getService(
                            StatsPullAtomServiceInternal.class);
                }
                if (mStatsPullAtomServiceInternal != null) {
                    mStatsPullAtomServiceInternal.noteUidProcessState(uid, state);
                } else {
                    Slog.d(TAG, "StatsPullAtomService not ready yet");
                }
            } catch (Exception e) {
                Slog.e(TAG, "Exception during logging uid proc state change event", e);
            }
        }
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

    /**
     * Returns true if things are idle enough to perform GCs.
     */
    @GuardedBy("this")
    final boolean canGcNowLocked() {
        if (!mBroadcastQueue.isIdleLocked()) {
            return false;
        }
        return mAtmInternal.canGcNow();
    }

    private void checkExcessivePowerUsage() {
        updateCpuStatsNow();

        final boolean monitorPhantomProcs = mSystemReady && FeatureFlagUtils.isEnabled(mContext,
                SETTINGS_ENABLE_MONITOR_PHANTOM_PROCS);
        synchronized (mProcLock) {
            final boolean doCpuKills = mLastPowerCheckUptime != 0;
            final long curUptime = SystemClock.uptimeMillis();
            final long uptimeSince = curUptime - mLastPowerCheckUptime;
            mLastPowerCheckUptime = curUptime;
            mProcessList.forEachLruProcessesLOSP(false, app -> {
                if (app.getThread() == null) {
                    return;
                }
                if (app.mState.getSetProcState() >= ActivityManager.PROCESS_STATE_HOME) {
                    int cpuLimit;
                    long checkDur = curUptime - app.mState.getWhenUnimportant();
                    if (checkDur <= mConstants.POWER_CHECK_INTERVAL) {
                        cpuLimit = mConstants.POWER_CHECK_MAX_CPU_1;
                    } else if (checkDur <= (mConstants.POWER_CHECK_INTERVAL * 2)
                            || app.mState.getSetProcState() <= ActivityManager.PROCESS_STATE_HOME) {
                        cpuLimit = mConstants.POWER_CHECK_MAX_CPU_2;
                    } else if (checkDur <= (mConstants.POWER_CHECK_INTERVAL * 3)) {
                        cpuLimit = mConstants.POWER_CHECK_MAX_CPU_3;
                    } else {
                        cpuLimit = mConstants.POWER_CHECK_MAX_CPU_4;
                    }

                    updateAppProcessCpuTimeLPr(uptimeSince, doCpuKills, checkDur, cpuLimit, app);

                    if (monitorPhantomProcs) {
                        // Also check the phantom processes if there is any
                        updatePhantomProcessCpuTimeLPr(
                                uptimeSince, doCpuKills, checkDur, cpuLimit, app);
                    }
                }
            });
        }
    }

    @GuardedBy("mProcLock")
    private void updateAppProcessCpuTimeLPr(final long uptimeSince, final boolean doCpuKills,
            final long checkDur, final int cpuLimit, final ProcessRecord app) {
        synchronized (mAppProfiler.mProfilerLock) {
            final ProcessProfileRecord profile = app.mProfile;
            final long curCpuTime = profile.mCurCpuTime.get();
            final long lastCpuTime = profile.mLastCpuTime.get();
            if (lastCpuTime > 0) {
                final long cpuTimeUsed = curCpuTime - lastCpuTime;
                if (checkExcessivePowerUsageLPr(uptimeSince, doCpuKills, cpuTimeUsed,
                            app.processName, app.toShortString(), cpuLimit, app)) {
                    mHandler.post(() -> {
                        synchronized (ActivityManagerService.this) {
                            if (app.getThread() == null
                               || app.mState.getSetProcState() < ActivityManager.PROCESS_STATE_HOME) {
                                   return;
                            }
                            app.killLocked("excessive cpu " + cpuTimeUsed + " during "
                                    + uptimeSince + " dur=" + checkDur + " limit=" + cpuLimit,
                                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                                    ApplicationExitInfo.SUBREASON_EXCESSIVE_CPU,
                                    true);
                        }
                    });
                    profile.reportExcessiveCpu();
                }
            }

            profile.mLastCpuTime.set(curCpuTime);
        }
    }

    @GuardedBy("mProcLock")
    private void updatePhantomProcessCpuTimeLPr(final long uptimeSince, final boolean doCpuKills,
            final long checkDur, final int cpuLimit, final ProcessRecord app) {
        mPhantomProcessList.forEachPhantomProcessOfApp(app, r -> {
            if (r.mLastCputime > 0) {
                final long cpuTimeUsed = r.mCurrentCputime - r.mLastCputime;
                if (checkExcessivePowerUsageLPr(uptimeSince, doCpuKills, cpuTimeUsed,
                            app.processName, r.toString(), cpuLimit, app)) {
                    mHandler.post(() -> {
                        synchronized (ActivityManagerService.this) {
                            if (app.getThread() == null
                               || app.mState.getSetProcState() < ActivityManager.PROCESS_STATE_HOME) {
                                   return;
                            }
                            mPhantomProcessList.killPhantomProcessGroupLocked(app, r,
                                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                                    ApplicationExitInfo.SUBREASON_EXCESSIVE_CPU,
                                    "excessive cpu " + cpuTimeUsed + " during "
                                    + uptimeSince + " dur=" + checkDur + " limit=" + cpuLimit);
                        }
                    });
                    return false;
                }
            }
            r.mLastCputime = r.mCurrentCputime;
            return true;
        });
    }

    @GuardedBy("mProcLock")
    private boolean checkExcessivePowerUsageLPr(final long uptimeSince, boolean doCpuKills,
            final long cputimeUsed, final String processName, final String description,
            final int cpuLimit, final ProcessRecord app) {
        if (DEBUG_POWER && (uptimeSince > 0)) {
            StringBuilder sb = new StringBuilder(128);
            sb.append("CPU for ");
            sb.append(description);
            sb.append(": over ");
            TimeUtils.formatDuration(uptimeSince, sb);
            sb.append(" used ");
            TimeUtils.formatDuration(cputimeUsed, sb);
            sb.append(" (");
            sb.append((cputimeUsed * 100.0) / uptimeSince);
            sb.append("%)");
            Slog.i(TAG_POWER, sb.toString());
        }
        // If the process has used too much CPU over the last duration, the
        // user probably doesn't want this, so kill!
        if (doCpuKills && uptimeSince > 0) {
            if (((cputimeUsed * 100) / uptimeSince) >= cpuLimit) {
                mBatteryStatsService.reportExcessiveCpu(app.info.uid, app.processName,
                        uptimeSince, cputimeUsed);
                app.getPkgList().forEachPackageProcessStats(holder -> {
                    final ProcessState state = holder.state;
                    FrameworkStatsLog.write(
                            FrameworkStatsLog.EXCESSIVE_CPU_USAGE_REPORTED,
                            app.info.uid,
                            processName,
                            state != null ? state.getPackage() : app.info.packageName,
                            holder.appVersion);
                });
                return true;
            }
        }
        return false;
    }

    private boolean isEphemeralLocked(int uid) {
        final String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        if (packages == null || packages.length != 1) { // Ephemeral apps cannot share uid
            return false;
        }
        return getPackageManagerInternal().isPackageEphemeral(
                UserHandle.getUserId(uid), packages[0]);
    }

    @GuardedBy("this")
    void enqueueUidChangeLocked(UidRecord uidRec, int uid, int change) {
        uid = uidRec != null ? uidRec.getUid() : uid;
        if (uid < 0) {
            throw new IllegalArgumentException("No UidRecord or uid");
        }

        final int procState = uidRec != null
                ? uidRec.getSetProcState() : PROCESS_STATE_NONEXISTENT;
        final int procAdj = uidRec != null
                ? uidRec.getMinProcAdj() : ProcessList.INVALID_ADJ;
        final long procStateSeq = uidRec != null ? uidRec.curProcStateSeq : 0;
        final int capability = uidRec != null ? uidRec.getSetCapability() : 0;
        final boolean ephemeral = uidRec != null ? uidRec.isEphemeral() : isEphemeralLocked(uid);

        if (uidRec != null && uidRec.isIdle() && (change & UidRecord.CHANGE_IDLE) != 0) {
            mProcessList.killAppIfBgRestrictedAndCachedIdleLocked(uidRec);
        }

        if (uidRec != null && !uidRec.isIdle() && (change & UidRecord.CHANGE_GONE) != 0) {
            // If this uid is going away, and we haven't yet reported it is gone,
            // then do so now.
            change |= UidRecord.CHANGE_IDLE;
        }
        final int enqueuedChange = mUidObserverController.enqueueUidChange(
                uidRec == null ? null : uidRec.pendingChange,
                uid, change, procState, procAdj, procStateSeq, capability, ephemeral);
        if (uidRec != null) {
            uidRec.setLastReportedChange(enqueuedChange);
        }

        // Directly update the power manager, since we sit on top of it and it is critical
        // it be kept in sync (so wake locks will be held as soon as appropriate).
        if (mLocalPowerManager != null) {
            // TODO: dispatch cached/uncached changes here, so we don't need to report
            // all proc state changes.
            if ((enqueuedChange & UidRecord.CHANGE_ACTIVE) != 0) {
                mLocalPowerManager.uidActive(uid);
            }
            if ((enqueuedChange & UidRecord.CHANGE_IDLE) != 0) {
                mLocalPowerManager.uidIdle(uid);
            }
            if ((enqueuedChange & UidRecord.CHANGE_GONE) != 0) {
                mLocalPowerManager.uidGone(uid);
            } else if ((enqueuedChange & UidRecord.CHANGE_PROCSTATE) != 0) {
                mLocalPowerManager.updateUidProcState(uid, procState);
            }
        }
    }

    @GuardedBy(anyOf = {"this", "mProcLock"})
    final void setProcessTrackerStateLOSP(ProcessRecord proc, int memFactor) {
        if (proc.getThread() != null) {
            proc.mProfile.setProcessTrackerState(
                    proc.mState.getReportedProcState(), memFactor);
        }
    }

    @GuardedBy("this")
    final void clearProcessForegroundLocked(ProcessRecord proc) {
        updateProcessForegroundLocked(proc, /* isForeground =*/ false,
                /* fgsTypes =*/0, /* hasTypeNoneFgs =*/false, /* oomAdj= */ false);
    }

    @GuardedBy("this")
    final void updateProcessForegroundLocked(ProcessRecord proc, boolean isForeground,
            int fgServiceTypes, boolean hasTypeNoneFgs, boolean oomAdj) {
        final ProcessServiceRecord psr = proc.mServices;
        final boolean foregroundStateChanged = isForeground != psr.hasForegroundServices();
        if (foregroundStateChanged
                || !psr.areForegroundServiceTypesSame(fgServiceTypes, hasTypeNoneFgs)) {
            if (foregroundStateChanged) {
                // Notify internal listeners.
                for (int i = mForegroundServiceStateListeners.size() - 1; i >= 0; i--) {
                    mForegroundServiceStateListeners.get(i).onForegroundServiceStateChanged(
                            proc.info.packageName, proc.info.uid, proc.getPid(), isForeground);
                }
            }
            psr.setHasForegroundServices(isForeground, fgServiceTypes, hasTypeNoneFgs);
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

            psr.setReportedForegroundServiceTypes(fgServiceTypes);
            ProcessChangeItem item = mProcessList.enqueueProcessChangeItemLocked(
                    proc.getPid(), proc.info.uid);
            item.changes |= ProcessChangeItem.CHANGE_FOREGROUND_SERVICES;
            item.foregroundServiceTypes = fgServiceTypes;
        }
        if (oomAdj) {
            updateOomAdjLocked(proc, OOM_ADJ_REASON_UI_VISIBILITY);
        }
    }

    // TODO(b/111541062): This method is only used for updating OOM adjustments. We need to update
    // the logic there and in mBatteryStatsService to make them aware of multiple resumed activities
    @Nullable
    ProcessRecord getTopApp() {
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
        synchronized (mCurResumedAppLock) {
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
        }
        return r;
    }

    /**
     * Enqueue the given process into a todo list, and the caller should
     * call {@link #updateOomAdjPendingTargetsLocked} to kick off a pass of the oom adj update.
     */
    @GuardedBy("this")
    void enqueueOomAdjTargetLocked(ProcessRecord app) {
        mOomAdjuster.enqueueOomAdjTargetLocked(app);
    }

    /**
     * Remove the given process into a todo list.
     */
    @GuardedBy("this")
    void removeOomAdjTargetLocked(ProcessRecord app, boolean procDied) {
        mOomAdjuster.removeOomAdjTargetLocked(app, procDied);
    }

    /**
     * Kick off an oom adj update pass for the pending targets which are enqueued via
     * {@link #enqueueOomAdjTargetLocked}.
     */
    @GuardedBy("this")
    void updateOomAdjPendingTargetsLocked(@OomAdjReason int oomAdjReason) {
        mOomAdjuster.updateOomAdjPendingTargetsLocked(oomAdjReason);
    }

    static final class ProcStatsRunnable implements Runnable {
        private final ActivityManagerService mService;
        private final ProcessStatsService mProcessStats;

        ProcStatsRunnable(ActivityManagerService service, ProcessStatsService mProcessStats) {
            this.mService = service;
            this.mProcessStats = mProcessStats;
        }

        @Override public void run() {
            mProcessStats.writeStateAsync();
        }
    }

    @GuardedBy("this")
    final void updateOomAdjLocked(@OomAdjReason int oomAdjReason) {
        mOomAdjuster.updateOomAdjLocked(oomAdjReason);
    }

    /**
     * Update OomAdj for a specific process and its reachable processes.
     *
     * @param app The process to update
     * @param oomAdjReason
     * @return whether updateOomAdjLocked(app) was successful.
     */
    @GuardedBy("this")
    final boolean updateOomAdjLocked(ProcessRecord app, @OomAdjReason int oomAdjReason) {
        return mOomAdjuster.updateOomAdjLocked(app, oomAdjReason);
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
        final long callingId = Binder.clearCallingIdentity();
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

            synchronized (this) {
                try {
                    if (mLocalPowerManager != null) {
                        mLocalPowerManager.startUidChanges();
                    }
                    final int appId = UserHandle.getAppId(pkgUid);
                    for (int i = mProcessList.mActiveUids.size() - 1; i >= 0; i--) {
                        final UidRecord uidRec = mProcessList.mActiveUids.valueAt(i);
                        final long bgTime = uidRec.getLastBackgroundTime();
                        if (bgTime > 0 && !uidRec.isIdle()) {
                            final int uid = uidRec.getUid();
                            if (UserHandle.getAppId(uid) == appId) {
                                if (userId == UserHandle.USER_ALL
                                        || userId == UserHandle.getUserId(uid)) {
                                    EventLogTags.writeAmUidIdle(uid);
                                    synchronized (mProcLock) {
                                        uidRec.setIdle(true);
                                        uidRec.setSetIdle(true);
                                    }
                                    Slog.w(TAG, "Idling uid " + UserHandle.formatUid(uid)
                                            + " from package " + packageName + " user " + userId);
                                    doStopUidLocked(uid, uidRec);
                                }
                            }
                        }
                    }
                } finally {
                    if (mLocalPowerManager != null) {
                        mLocalPowerManager.finishUidChanges();
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void setDeterministicUidIdle(boolean deterministic) {
        synchronized (this) {
            mDeterministicUidIdle = deterministic;
        }
    }

    /** Make the currently active UIDs idle after a certain grace period. */
    final void idleUids() {
        synchronized (this) {
            mOomAdjuster.idleUidsLocked();
        }
    }

    final void runInBackgroundDisabled(int uid) {
        synchronized (this) {
            UidRecord uidRec = mProcessList.getUidRecordLOSP(uid);
            if (uidRec != null) {
                // This uid is actually running...  should it be considered background now?
                if (uidRec.isIdle()) {
                    doStopUidLocked(uidRec.getUid(), uidRec);
                }
            } else {
                // This uid isn't actually running...  still send a report about it being "stopped".
                doStopUidLocked(uid, null);
            }
        }
    }

    final void cameraActiveChanged(@UserIdInt int uid, boolean active) {
        synchronized (mActiveCameraUids) {
            final int curIndex = mActiveCameraUids.indexOf(uid);
            if (active) {
                if (curIndex < 0) {
                    mActiveCameraUids.add(uid);
                }
            } else {
                if (curIndex >= 0) {
                    mActiveCameraUids.remove(curIndex);
                }
            }
        }

        if (com.android.window.flags.Flags.fifoPriorityForMajorUiProcesses()) {
            synchronized (mProcLock) {
                adjustFifoProcessesIfNeeded(uid, !active /* allowFifo */);
            }
        }
    }

    final boolean isCameraActiveForUid(@UserIdInt int uid) {
        synchronized (mActiveCameraUids) {
            return mActiveCameraUids.indexOf(uid) >= 0;
        }
    }

    /**
     * This is called when the given uid is using camera. If the uid has top process state, then
     * cancel the FIFO priority of the high priority processes.
     */
    @VisibleForTesting
    @GuardedBy("mProcLock")
    void adjustFifoProcessesIfNeeded(int preemptiveUid, boolean allowSpecifiedFifo) {
        if (allowSpecifiedFifo == mAllowSpecifiedFifoScheduling) {
            return;
        }
        if (!allowSpecifiedFifo) {
            final UidRecord uidRec = mProcessList.mActiveUids.get(preemptiveUid);
            if (uidRec == null || uidRec.getCurProcState() > PROCESS_STATE_TOP) {
                // To avoid frequent switching by background camera usages, e.g. face unlock,
                // face detection (auto rotation), screen attention (keep screen on).
                return;
            }
        }
        mAllowSpecifiedFifoScheduling = allowSpecifiedFifo;
        for (int i = mSpecifiedFifoProcesses.size() - 1; i >= 0; i--) {
            final ProcessRecord proc = mSpecifiedFifoProcesses.get(i);
            if (proc.mState.getSetSchedGroup() != ProcessList.SCHED_GROUP_TOP_APP) {
                continue;
            }
            setFifoPriority(proc, allowSpecifiedFifo /* enable */);
        }
    }

    @GuardedBy("this")
    final void doStopUidLocked(int uid, final UidRecord uidRec) {
        mServices.stopInBackgroundLocked(uid);
        enqueueUidChangeLocked(uidRec, uid, UidRecord.CHANGE_IDLE | UidRecord.CHANGE_PROCSTATE);
    }

    /**
     * Allowlists {@code targetUid} to temporarily bypass Power Save mode.
     */
    @GuardedBy("this")
    void tempAllowlistForPendingIntentLocked(int callerPid, int callerUid, int targetUid,
            long duration, int type, @ReasonCode int reasonCode, String reason) {
        if (DEBUG_ALLOWLISTS) {
            Slog.d(TAG, "tempAllowlistForPendingIntentLocked(" + callerPid + ", " + callerUid + ", "
                    + targetUid + ", " + duration + ", " + type + ")");
        }

        synchronized (mPidsSelfLocked) {
            final ProcessRecord pr = mPidsSelfLocked.get(callerPid);
            if (pr == null) {
                Slog.w(TAG, "tempAllowlistForPendingIntentLocked() no ProcessRecord for pid "
                        + callerPid);
                return;
            }
            if (!pr.mServices.mAllowlistManager) {
                if (checkPermission(CHANGE_DEVICE_IDLE_TEMP_WHITELIST, callerPid, callerUid)
                        != PackageManager.PERMISSION_GRANTED
                        && checkPermission(START_ACTIVITIES_FROM_BACKGROUND, callerPid, callerUid)
                        != PackageManager.PERMISSION_GRANTED
                        && checkPermission(START_FOREGROUND_SERVICES_FROM_BACKGROUND, callerPid,
                        callerUid) != PackageManager.PERMISSION_GRANTED) {
                    if (DEBUG_ALLOWLISTS) {
                        Slog.d(TAG, "tempAllowlistForPendingIntentLocked() for target " + targetUid
                                + ": pid " + callerPid + " is not allowed");
                    }
                    return;
                }
            }
        }

        tempAllowlistUidLocked(targetUid, duration, reasonCode, reason, type, callerUid);
    }

    /**
     * Allowlists {@code targetUid} to temporarily bypass Power Save mode.
     */
    @GuardedBy("this")
    void tempAllowlistUidLocked(int targetUid, long duration, @ReasonCode int reasonCode,
            String reason, @TempAllowListType int type, int callingUid) {
        synchronized (mProcLock) {
            // The temp allowlist type could change according to the reasonCode.
            if (mLocalDeviceIdleController != null) {
                type = mLocalDeviceIdleController.getTempAllowListType(reasonCode, type);
            }
            if (type == TEMPORARY_ALLOW_LIST_TYPE_NONE) {
                return;
            }
            mPendingTempAllowlist.put(targetUid,
                    new PendingTempAllowlist(targetUid, duration, reasonCode, reason, type,
                            callingUid));
            setUidTempAllowlistStateLSP(targetUid, true);
            mUiHandler.obtainMessage(PUSH_TEMP_ALLOWLIST_UI_MSG).sendToTarget();

            if (type == TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED) {
                mFgsStartTempAllowList.add(targetUid, duration,
                        new FgsTempAllowListItem(duration, reasonCode, reason, callingUid));
            }
        }
    }

    void pushTempAllowlist() {
        final int N;
        final PendingTempAllowlist[] list;

        // First copy out the pending changes...  we need to leave them in the map for now,
        // in case someone needs to check what is coming up while we don't have the lock held.
        synchronized (this) {
            synchronized (mProcLock) {
                N = mPendingTempAllowlist.size();
                list = new PendingTempAllowlist[N];
                for (int i = 0; i < N; i++) {
                    list[i] = mPendingTempAllowlist.valueAt(i);
                }
            }
        }

        // Now safely dispatch changes to device idle controller.  Skip this if we're early
        // in boot and the controller hasn't yet been brought online:  we do not apply
        // device idle policy anyway at this phase.
        if (mLocalDeviceIdleController != null) {
            for (int i = 0; i < N; i++) {
                PendingTempAllowlist ptw = list[i];
                mLocalDeviceIdleController.addPowerSaveTempWhitelistAppDirect(ptw.targetUid,
                        ptw.duration, ptw.type, false, ptw.reasonCode, ptw.tag,
                        ptw.callingUid);
            }
        }

        // And now we can safely remove them from the map.
        synchronized (this) {
            synchronized (mProcLock) {
                for (int i = 0; i < N; i++) {
                    PendingTempAllowlist ptw = list[i];
                    int index = mPendingTempAllowlist.indexOfKey(ptw.targetUid);
                    if (index >= 0 && mPendingTempAllowlist.valueAt(index) == ptw) {
                        mPendingTempAllowlist.removeAt(index);
                    }
                }
            }
        }
    }

    @GuardedBy({"this", "mProcLock"})
    final void setUidTempAllowlistStateLSP(int uid, boolean onAllowlist) {
        mOomAdjuster.setUidTempAllowlistStateLSP(uid, onAllowlist);
    }

    private void trimApplications(boolean forceFullOomAdj, @OomAdjReason int oomAdjReason) {
        synchronized (this) {
            trimApplicationsLocked(forceFullOomAdj, oomAdjReason);
        }
    }

    @GuardedBy("this")
    private void trimApplicationsLocked(boolean forceFullOomAdj, @OomAdjReason int oomAdjReason) {
        // First remove any unused application processes whose package
        // has been removed.
        boolean didSomething = false;
        for (int i = mProcessList.mRemovedProcesses.size() - 1; i >= 0; i--) {
            final ProcessRecord app = mProcessList.mRemovedProcesses.get(i);
            if (!app.hasActivitiesOrRecentTasks()
                    && app.mReceivers.numberOfCurReceivers() == 0
                    && app.mServices.numberOfRunningServices() == 0) {
                final IApplicationThread thread = app.getThread();
                Slog.i(TAG, "Exiting empty application process "
                        + app.toShortString() + " ("
                        + (thread != null ? thread.asBinder() : null)
                        + ")\n");
                final int pid = app.getPid();
                if (pid > 0 && pid != MY_PID) {
                    app.killLocked("empty",
                            ApplicationExitInfo.REASON_OTHER,
                            ApplicationExitInfo.SUBREASON_TRIM_EMPTY,
                            false);
                } else if (thread != null) {
                    try {
                        thread.scheduleExit();
                    } catch (Exception e) {
                        // Ignore exceptions.
                    }
                }
                didSomething = true;
                cleanUpApplicationRecordLocked(app, pid, false, true, -1, false /*replacingPid*/,
                        false /* fromBinderDied */);
                mProcessList.mRemovedProcesses.remove(i);

                if (app.isPersistent()) {
                    addAppLocked(app.info, null, false, null /* ABI override */,
                            ZYGOTE_POLICY_FLAG_BATCH_LAUNCH);
                    app.mProfile.addHostingComponentType(HOSTING_COMPONENT_TYPE_PERSISTENT);
                }
            }
        }

        // Now update the oom adj for all processes. Don't skip this, since other callers
        // might be depending on it.
        if (didSomething || forceFullOomAdj) {
            updateOomAdjLocked(oomAdjReason);
        } else {
            // Process any pending oomAdj targets, it'll be a no-op if nothing is pending.
            updateOomAdjPendingTargetsLocked(oomAdjReason);
        }
    }

    /** This method sends the specified signal to each of the persistent apps */
    public void signalPersistentProcesses(final int sig) throws RemoteException {
        if (sig != SIGNAL_USR1) {
            throw new SecurityException("Only SIGNAL_USR1 is allowed");
        }

        if (checkCallingPermission(android.Manifest.permission.SIGNAL_PERSISTENT_PROCESSES)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.SIGNAL_PERSISTENT_PROCESSES);
        }

        synchronized (mProcLock) {
            mProcessList.forEachLruProcessesLOSP(false, app -> {
                if (app.getThread() != null && app.isPersistent()) {
                    sendSignal(app.getPid(), sig);
                }
            });
        }
    }

    public boolean profileControl(String process, int userId, boolean start,
            ProfilerInfo profilerInfo, int profileType) throws RemoteException {
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
        synchronized (mProcLock) {
            if (process != null) {
                proc = findProcessLOSP(process, userId, "profileControl");
            }

            if (start && (proc == null || proc.getThread() == null)) {
                throw new IllegalArgumentException("Unknown process: " + process);
            }
        }

        synchronized (mAppProfiler.mProfilerLock) {
            return mAppProfiler.profileControlLPf(proc, start, profilerInfo, profileType);
        }
    }

    @GuardedBy(anyOf = {"this", "mProcLock"})
    private ProcessRecord findProcessLOSP(String process, int userId, String callName) {
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
            ArrayMap<String, SparseArray<ProcessRecord>> all =
                    mProcessList.getProcessNamesLOSP().getMap();
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
            boolean runGc, String dumpBitmaps,
            String path, ParcelFileDescriptor fd, RemoteCallback finishCallback) {
        try {
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

            synchronized (this) {
                ProcessRecord proc = findProcessLOSP(process, userId, "dumpHeap");
                IApplicationThread thread;
                if (proc == null || (thread = proc.getThread()) == null) {
                    throw new IllegalArgumentException("Unknown process: " + process);
                }

                enforceDebuggable(proc);

                mOomAdjuster.mCachedAppOptimizer.enableFreezer(false);

                final RemoteCallback intermediateCallback = new RemoteCallback(
                        new RemoteCallback.OnResultListener() {
                        @Override
                        public void onResult(Bundle result) {
                            finishCallback.sendResult(result);
                            mOomAdjuster.mCachedAppOptimizer.enableFreezer(true);
                        }
                    }, null);

                thread.dumpHeap(managed, mallocInfo, runGc, dumpBitmaps,
                                path, fd, intermediateCallback);
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

    /**
     * Dump the resources structure for the given process
     *
     * @param process The process to dump resource info for
     * @param fd The FileDescriptor to dump it into
     * @throws RemoteException
     */
    public boolean dumpResources(String process, ParcelFileDescriptor fd, RemoteCallback callback)
            throws RemoteException {
        synchronized (this) {
            ProcessRecord proc = findProcessLOSP(process, UserHandle.USER_CURRENT, "dumpResources");
            IApplicationThread thread;
            if (proc == null || (thread = proc.getThread()) == null) {
                throw new IllegalArgumentException("Unknown process: " + process);
            }
            thread.dumpResources(fd, callback);
            return true;
        }
    }

    /**
     * Dump the resources structure for all processes
     *
     * @param fd The FileDescriptor to dump it into
     * @throws RemoteException
     */
    public void dumpAllResources(ParcelFileDescriptor fd, PrintWriter pw) throws RemoteException {
        final ArrayList<ProcessRecord> processes = new ArrayList<>();
        synchronized (this) {
            processes.addAll(mProcessList.getLruProcessesLOSP());
        }
        for (int i = 0, size = processes.size(); i < size; i++) {
            ProcessRecord app = processes.get(i);
            pw.println(String.format("Resources History for %s (%s)",
                    app.processName,
                    app.info.packageName));
            if (app.mOptRecord.isFrozen()) {
                pw.println("  Skipping frozen process");
                pw.flush();
                continue;
            }
            pw.flush();
            try {
                TransferPipe tp = new TransferPipe("  ");
                try {
                    IApplicationThread thread = app.getThread();
                    if (thread != null) {
                        app.getThread().dumpResources(tp.getWriteFd(), null);
                        tp.go(fd.getFileDescriptor(), 2000);
                    } else {
                        pw.println(String.format(
                                "  Resources history for %s (%s) failed, no thread",
                                app.processName,
                                app.info.packageName));
                    }
                } finally {
                    tp.kill();
                }
            } catch (IOException e) {
                pw.println("  " + e.getMessage());
                pw.flush();
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
                enforceDebuggable(proc);
                processName = proc.processName;
                uid = proc.uid;
                if (reportPackage != null && !proc.getPkgList().containsKey(reportPackage)) {
                    throw new SecurityException("Package " + reportPackage + " is not running in "
                            + proc);
                }
            }
        }
        mAppProfiler.setDumpHeapDebugLimit(processName, uid, maxMemSize, reportPackage);
    }

    @Override
    public void dumpHeapFinished(String path) {
        mAppProfiler.dumpHeapFinished(path, Binder.getCallingPid());
    }

    /** In this method we try to acquire our lock to make sure that we have not deadlocked */
    public void monitor() {
        synchronized (this) { }
    }

    void onCoreSettingsChange(Bundle settings) {
        synchronized (mProcLock) {
            mProcessList.updateCoreSettingsLOSP(settings);
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
        return mUserController.startUser(userId, USER_START_MODE_BACKGROUND, unlockListener);
    }

    @Override
    public boolean startUserInForegroundWithListener(final int userId,
            @Nullable IProgressListener unlockListener) {
        // Permission check done inside UserController.
        return mUserController.startUser(userId, USER_START_MODE_FOREGROUND, unlockListener);
    }

    @Override
    public boolean startUserInBackgroundVisibleOnDisplay(int userId, int displayId,
            @Nullable IProgressListener unlockListener) {
        int[] displayIds = getDisplayIdsForStartingVisibleBackgroundUsers();
        boolean validDisplay = false;
        if (displayIds != null) {
            for (int i = 0; i < displayIds.length; i++) {
                if (displayId == displayIds[i]) {
                    validDisplay = true;
                    break;
                }
            }
        }
        if (!validDisplay) {
            throw new IllegalArgumentException("Invalid display (" + displayId + ") to start user. "
                    + "Valid options are: " + Arrays.toString(displayIds));
        }

        if (DEBUG_MU) {
            Slogf.d(TAG_MU, "Calling startUserOnSecondaryDisplay(%d, %d, %s) using injector %s",
                    userId, displayId, unlockListener, mInjector);
        }
        // Permission check done inside UserController.
        return mInjector.startUserInBackgroundVisibleOnDisplay(userId, displayId, unlockListener);
    }

    @Override
    public int[] getDisplayIdsForStartingVisibleBackgroundUsers() {
        enforceCallingHasAtLeastOnePermission("getDisplayIdsForStartingVisibleBackgroundUsers()",
                MANAGE_USERS, INTERACT_ACROSS_USERS);
        return mInjector.getDisplayIdsForStartingVisibleBackgroundUsers();
    }

    /** @deprecated see the AIDL documentation {@inheritDoc} */
    @Override
    @Deprecated
    public boolean unlockUser(@UserIdInt int userId, @Nullable byte[] token,
            @Nullable byte[] secret, @Nullable IProgressListener listener) {
        return mUserController.unlockUser(userId, listener);
    }

    @Override
    public boolean unlockUser2(@UserIdInt int userId, @Nullable IProgressListener listener) {
        return mUserController.unlockUser(userId, listener);
    }

    @Override
    public boolean switchUser(final int targetUserId) {
        return mUserController.switchUser(targetUserId);
    }

    @Override
    public String getSwitchingFromUserMessage() {
        return mUserController.getSwitchingFromSystemUserMessage();
    }

    @Override
    public String getSwitchingToUserMessage() {
        return mUserController.getSwitchingToSystemUserMessage();
    }

    @Override
    public void setStopUserOnSwitch(@StopUserOnSwitch int value) {
        mUserController.setStopUserOnSwitch(value);
    }

    /** @deprecated use {@link #stopUserWithCallback(int, IStopUserCallback)} instead */
    @Deprecated
    @Override
    public int stopUser(final int userId,
            boolean stopProfileRegardlessOfParent, final IStopUserCallback callback) {
        return stopUserExceptCertainProfiles(userId, stopProfileRegardlessOfParent, callback);
    }

    /** Stops the given user. */
    @Override
    public int stopUserWithCallback(@UserIdInt int userId, @Nullable IStopUserCallback callback) {
        return mUserController.stopUser(userId, /* allowDelayedLocking= */ false,
                /* callback= */ callback, /* keyEvictedCallback= */ null);
    }

    /**
     * Stops the given user.
     *
     * Usually, callers can just use @link{#stopUserWithCallback(int, IStopUserCallback)} instead.
     *
     * @param stopProfileRegardlessOfParent whether to stop the profile regardless of who its
     *                                      parent is, e.g. even if the parent is the current user;
     *                                      its value is irrelevant for non-profile users.
     */
    @Override
    public int stopUserExceptCertainProfiles(@UserIdInt int userId,
            boolean stopProfileRegardlessOfParent, @Nullable IStopUserCallback callback) {
        return mUserController.stopUser(userId,
                stopProfileRegardlessOfParent, /* allowDelayedLocking= */ false,
                /* callback= */ callback, /* keyEvictedCallback= */ null);
    }

    /**
     * Stops user but allow delayed locking. Delayed locking keeps user unlocked even after
     * stopping only if {@code config_multiuserDelayUserDataLocking} overlay is set true.
     *
     * <p>When delayed locking is not enabled through the overlay, this call becomes the same
     * with {@link #stopUserWithCallback(int, IStopUserCallback)} call.
     *
     * @param userId User id to stop.
     * @param callback Callback called when user has stopped.
     *
     * @return {@link ActivityManager#USER_OP_SUCCESS} when user is stopped successfully. Returns
     *         other {@code ActivityManager#USER_OP_*} codes for failure.
     *
     */
    // TODO(b/302662311): Add javadoc changes corresponding to the user property that allows
    // delayed locking behavior once the private space flag is finalized.
    @Override
    public int stopUserWithDelayedLocking(@UserIdInt int userId, IStopUserCallback callback) {
        return mUserController.stopUser(userId, /* allowDelayedLocking= */ true,
                /* callback= */ callback, /* keyEvictedCallback= */ null);
    }

    @Override
    public boolean startProfile(@UserIdInt int userId) {
        return mUserController.startProfile(userId, /* evenWhenDisabled= */ false,
                /* unlockListener= */ null);
    }

    @Override
    public boolean startProfileWithListener(@UserIdInt int userId,
            @Nullable IProgressListener unlockListener) {
        return mUserController.startProfile(userId, /* evenWhenDisabled= */ false, unlockListener);
    }

    @Override
    public boolean stopProfile(@UserIdInt int userId) {
        return mUserController.stopProfile(userId);
    }

    @Override
    public UserInfo getCurrentUser() {
        return mUserController.getCurrentUser();
    }

    @Override
    public @UserIdInt int getCurrentUserId() {
        return mUserController.getCurrentUserIdChecked();
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

    @GuardedBy("mProcLock")
    private boolean processSanityChecksLPr(ProcessRecord process, IApplicationThread thread) {
        if (process == null || thread == null) {
            return false;
        }

        return Build.IS_DEBUGGABLE || process.isDebuggable();
    }

    public boolean startBinderTracking() throws RemoteException {
        // TODO: hijacking SET_ACTIVITY_WATCHER, but should be changed to its own
        // permission (same as profileControl).
        if (checkCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.SET_ACTIVITY_WATCHER);
        }

        synchronized (mProcLock) {
            mBinderTransactionTrackingEnabled = true;
            mProcessList.forEachLruProcessesLOSP(true, process -> {
                final IApplicationThread thread = process.getThread();
                if (!processSanityChecksLPr(process, thread)) {
                    return;
                }
                try {
                    thread.startBinderTracking();
                } catch (RemoteException e) {
                    Log.v(TAG, "Process disappared");
                }
            });
        }
        return true;
    }

    @Override
    public boolean stopBinderTrackingAndDump(final ParcelFileDescriptor fd) throws RemoteException {
        // TODO: hijacking SET_ACTIVITY_WATCHER, but should be changed to its own
        // permission (same as profileControl).
        if (checkCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.SET_ACTIVITY_WATCHER);
        }

        boolean closeFd = true;
        try {
            synchronized (mProcLock) {
                if (fd == null) {
                    throw new IllegalArgumentException("null fd");
                }
                mBinderTransactionTrackingEnabled = false;

                PrintWriter pw = new FastPrintWriter(new FileOutputStream(fd.getFileDescriptor()));
                pw.println("Binder transaction traces for all processes.\n");
                mProcessList.forEachLruProcessesLOSP(true, process -> {
                    final IApplicationThread thread = process.getThread();
                    if (!processSanityChecksLPr(process, thread)) {
                        return;
                    }

                    pw.println("Traces for process: " + process.processName);
                    pw.flush();
                    try {
                        TransferPipe tp = new TransferPipe();
                        try {
                            thread.stopBinderTrackingAndDump(tp.getWriteFd());
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
                });
                closeFd = false;
                return true;
            }
        } finally {
            if (fd != null && closeFd) {
                try {
                    fd.close();
                } catch (IOException e) {
                }
            }
        }
    }

    void onProcessFreezableChangedLocked(ProcessRecord app) {
        mBroadcastQueue.onProcessFreezableChangedLocked(app);
    }

    @VisibleForTesting
    public final class LocalService extends ActivityManagerInternal
            implements ActivityManagerLocal {

        @Override
        public List<PendingIntentStats> getPendingIntentStats() {
            return mPendingIntentController.dumpPendingIntentStatsForStatsd();
        }

        @Override
        public Pair<String, String> getAppProfileStatsForDebugging(long time, int lines) {
            return mAppProfiler.getAppProfileStatsForDebugging(time, lines);
        }

        @Override
        public String checkContentProviderAccess(String authority, int userId) {
            return mCpHelper.checkContentProviderAccess(authority, userId);
        }

        @Override
        public int checkContentProviderUriPermission(Uri uri, int userId,
                int callingUid, int modeFlags) {
            return mCpHelper.checkContentProviderUriPermission(uri, userId, callingUid, modeFlags);
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
        public ComponentName startSdkSandboxService(Intent service, int clientAppUid,
                String clientAppPackage, String processName) throws RemoteException {
            validateSdkSandboxParams(service, clientAppUid, clientAppPackage, processName);
            if (mAppOpsService.checkPackage(clientAppUid, clientAppPackage)
                    != AppOpsManager.MODE_ALLOWED) {
                throw new IllegalArgumentException("uid does not belong to provided package");
            }
            // TODO(b/269598719): Is passing the application thread of the system_server alright?
            // e.g. the sandbox getting privileged access due to this.
            ComponentName cn = ActivityManagerService.this.startService(
                    mContext.getIApplicationThread(), service,
                    service.resolveTypeIfNeeded(mContext.getContentResolver()), false,
                    mContext.getOpPackageName(), mContext.getAttributionTag(),
                    UserHandle.getUserId(clientAppUid), true, clientAppUid, clientAppPackage,
                    processName);
            if (cn != null) {
                if (cn.getPackageName().equals("!")) {
                    throw new SecurityException(
                            "Not allowed to start service " + service
                                    + " without permission " + cn.getClassName());
                } else if (cn.getPackageName().equals("!!")) {
                    throw new SecurityException(
                            "Unable to start service " + service
                                    + ": " + cn.getClassName());
                } else if (cn.getPackageName().equals("?")) {
                    throw ServiceStartNotAllowedException.newInstance(false,
                            "Not allowed to start service " + service + ": "
                                    + cn.getClassName());
                }
            }

            return cn;
        }

        @Override
        public boolean stopSdkSandboxService(Intent service, int clientAppUid,
                String clientAppPackage, String processName) {
            validateSdkSandboxParams(service, clientAppUid, clientAppPackage, processName);
            int res = ActivityManagerService.this.stopService(
                    mContext.getIApplicationThread(), service,
                    service.resolveTypeIfNeeded(mContext.getContentResolver()),
                    UserHandle.getUserId(clientAppUid), true, clientAppUid, clientAppPackage,
                    processName);
            if (res < 0) {
                throw new SecurityException(
                        "Not allowed to stop service " + service);
            }
            return res != 0;
        }

        @Override
        public boolean bindSdkSandboxService(Intent service, ServiceConnection conn,
                int clientAppUid, IBinder clientApplicationThread, String clientAppPackage,
                String processName, int flags)
                throws RemoteException {
            return bindSdkSandboxServiceInternal(service, conn, clientAppUid,
                    clientApplicationThread, clientAppPackage, processName,
                    Integer.toUnsignedLong(flags));
        }

        @Override
        public boolean bindSdkSandboxService(Intent service, ServiceConnection conn,
                int clientAppUid, IBinder clientApplicationThread, String clientAppPackage,
                String processName, BindServiceFlags flags) throws RemoteException {
            return bindSdkSandboxServiceInternal(service, conn, clientAppUid,
                    clientApplicationThread, clientAppPackage, processName, flags.getValue());
        }

        private boolean bindSdkSandboxServiceInternal(Intent service, ServiceConnection conn,
                int clientAppUid, IBinder clientApplicationThread, String clientAppPackage,
                String processName, long flags)
                throws RemoteException {
            validateSdkSandboxParams(service, clientAppUid, clientAppPackage, processName);
            if (mAppOpsService.checkPackage(clientAppUid, clientAppPackage)
                    != AppOpsManager.MODE_ALLOWED) {
                throw new IllegalArgumentException("uid does not belong to provided package");
            }
            if (conn == null) {
                throw new IllegalArgumentException("connection is null");
            }

            Handler handler = mContext.getMainThreadHandler();
            IApplicationThread clientApplicationThreadVerified = null;
            if (clientApplicationThread != null) {
                // Make sure this is a valid application process
                synchronized (this) {
                    final ProcessRecord rec = getRecordForAppLOSP(clientApplicationThread);
                    if (rec == null) {
                        // This could happen if the calling process has disappeared; no need for the
                        // sandbox to be even started in this case.
                        Slog.i(TAG, "clientApplicationThread process not found.");
                        return false;
                    }
                    if (rec.info.uid != clientAppUid) {
                        throw new IllegalArgumentException("clientApplicationThread does not match "
                                + " client uid");
                    }
                    clientApplicationThreadVerified = rec.getThread();
                }
            }
            final IServiceConnection sd = mContext.getServiceDispatcher(conn, handler,
                    flags);
            service.prepareToLeaveProcess(mContext);
            return ActivityManagerService.this.bindServiceInstance(
                    mContext.getIApplicationThread(), mContext.getActivityToken(), service,
                    service.resolveTypeIfNeeded(mContext.getContentResolver()), sd,
                    flags,
                    processName, /*isSdkSandboxService*/ true, clientAppUid, clientAppPackage,
                    clientApplicationThreadVerified, mContext.getOpPackageName(),
                    UserHandle.getUserId(clientAppUid)) != 0;
        }

        private void validateSdkSandboxParams(Intent service, int clientAppUid,
                String clientAppPackage, String processName) {
            if (service == null) {
                throw new IllegalArgumentException("intent is null");
            }
            if (clientAppPackage == null) {
                throw new IllegalArgumentException("clientAppPackage is null");
            }
            if (processName == null) {
                throw new IllegalArgumentException("processName is null");
            }
            if (service.getComponent() == null) {
                throw new IllegalArgumentException("service must specify explicit component");
            }
            if (!UserHandle.isApp(clientAppUid)) {
                throw new IllegalArgumentException("uid is not within application range");
            }
        }

        @Override
        public boolean bindSdkSandboxService(Intent service, ServiceConnection conn,
                int clientAppUid, String clientAppPackage, String processName, int flags)
                throws RemoteException {
            return bindSdkSandboxService(service, conn, clientAppUid,
                    null /* clientApplicationThread */, clientAppPackage, processName, flags);
        }

        @Override
        public void killSdkSandboxClientAppProcess(IBinder clientApplicationThreadBinder) {
            synchronized (ActivityManagerService.this) {
                ProcessRecord r = getRecordForAppLOSP(clientApplicationThreadBinder);
                if (r != null) {
                    r.killLocked(
                            "sdk sandbox died",
                            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
                            ApplicationExitInfo.SUBREASON_SDK_SANDBOX_DIED,
                            true);
                }
            }
        }

        @Override
        public void onUserRemoved(@UserIdInt int userId) {
            // Clean up any ActivityTaskManager state (by telling it the user is stopped)
            mAtmInternal.onUserStopped(userId);
            // Clean up various services by removing the user
            mBatteryStatsService.onUserRemoved(userId);

            if (isHomeLaunchDelayable()) {
                synchronized (mThemeOverlayReadyUsers) {
                    mThemeOverlayReadyUsers.remove(userId);
                }
            }
        }

        @Override
        public boolean startUserInBackground(final int userId) {
            return ActivityManagerService.this.startUserInBackground(userId);
        }

        @Override
        public void killForegroundAppsForUser(@UserIdInt int userId) {
            final ArrayList<ProcessRecord> procs = new ArrayList<>();
            synchronized (mProcLock) {
                final int numOfProcs = mProcessList.getProcessNamesLOSP().getMap().size();
                for (int ip = 0; ip < numOfProcs; ip++) {
                    final SparseArray<ProcessRecord> apps =
                            mProcessList.getProcessNamesLOSP().getMap().valueAt(ip);
                    final int NA = apps.size();
                    for (int ia = 0; ia < NA; ia++) {
                        final ProcessRecord app = apps.valueAt(ia);
                        if (app.isPersistent()) {
                            // We don't kill persistent processes.
                            continue;
                        }
                        if (app.isRemoved()
                                || (app.userId == userId && app.mState.hasForegroundActivities())) {
                            procs.add(app);
                        }
                    }
                }
            }

            final int numOfProcs = procs.size();
            if (numOfProcs > 0) {
                synchronized (ActivityManagerService.this) {
                    for (int i = 0; i < numOfProcs; i++) {
                        mProcessList.removeProcessLocked(procs.get(i), false, true,
                                ApplicationExitInfo.REASON_OTHER,
                                ApplicationExitInfo.SUBREASON_KILL_ALL_FG,
                                "kill all fg");
                    }
                }
            }
        }

        @Override
        public void setPendingIntentAllowlistDuration(IIntentSender target, IBinder allowlistToken,
                long duration, int type, @ReasonCode int reasonCode, @Nullable String reason) {
            mPendingIntentController.setPendingIntentAllowlistDuration(target, allowlistToken,
                    duration, type, reasonCode, reason);
        }

        @Override
        public int getPendingIntentFlags(IIntentSender target) {
            return mPendingIntentController.getPendingIntentFlags(target);
        }

        @Override
        public int[] getStartedUserIds() {
            return mUserController.getStartedUserArray();
        }

        @Override
        public void setPendingIntentAllowBgActivityStarts(IIntentSender target,
                IBinder allowlistToken, int flags) {
            if (!(target instanceof PendingIntentRecord)) {
                Slog.w(TAG, "setPendingIntentAllowBgActivityStarts():"
                        + " not a PendingIntentRecord: " + target);
                return;
            }
            synchronized (ActivityManagerService.this) {
                ((PendingIntentRecord) target).setAllowBgActivityStarts(allowlistToken, flags);
            }
        }

        @Override
        public void clearPendingIntentAllowBgActivityStarts(IIntentSender target,
                IBinder allowlistToken) {
            if (!(target instanceof PendingIntentRecord)) {
                Slog.w(TAG, "clearPendingIntentAllowBgActivityStarts():"
                        + " not a PendingIntentRecord: " + target);
                return;
            }
            synchronized (ActivityManagerService.this) {
                ((PendingIntentRecord) target).clearAllowBgActivityStarts(allowlistToken);
            }
        }

        @Override
        public void setDeviceIdleAllowlist(int[] allAppids, int[] exceptIdleAppids) {
            synchronized (ActivityManagerService.this) {
                synchronized (mProcLock) {
                    mDeviceIdleAllowlist = allAppids;
                    mDeviceIdleExceptIdleAllowlist = exceptIdleAppids;
                    mAppRestrictionController.setDeviceIdleAllowlist(allAppids, exceptIdleAppids);
                }
            }
        }

        @Override
        public void updateDeviceIdleTempAllowlist(@Nullable int[] appids, int changingUid,
                boolean adding, long durationMs, @TempAllowListType int type,
                @ReasonCode int reasonCode, @Nullable String reason, int callingUid) {
            synchronized (ActivityManagerService.this) {
                synchronized (mProcLock) {
                    if (appids != null) {
                        mDeviceIdleTempAllowlist = appids;
                    }
                    if (adding) {
                        if (type == TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED) {
                            // Note, the device idle temp-allowlist are by app-ids, but here
                            // mFgsStartTempAllowList contains UIDs.
                            mFgsStartTempAllowList.add(changingUid, durationMs,
                                    new FgsTempAllowListItem(durationMs, reasonCode, reason,
                                    callingUid));
                        }
                    } else {
                        mFgsStartTempAllowList.removeUid(changingUid);
                    }
                    setUidTempAllowlistStateLSP(changingUid, adding);
                }
            }
        }

        @Override
        public int getUidProcessState(int uid) {
            return getUidState(uid);
        }

        @Override
        public Map<Integer, String> getProcessesWithPendingBindMounts(int userId) {
            return mProcessList.getProcessesWithPendingBindMounts(userId);
        }

        @Override
        public boolean isSystemReady() {
            // no need to synchronize(this) just to read & return the value
            return mSystemReady;
        }

        @Override
        public void enforceBroadcastOptionsPermissions(Bundle options, int callingUid) {
            enforceBroadcastOptionPermissionsInternal(options, callingUid);
        }

        /**
         * Returns package name by pid.
         */
        @Override
        @Nullable
        public String getPackageNameByPid(int pid) {
            synchronized (mPidsSelfLocked) {
                final ProcessRecord app = mPidsSelfLocked.get(pid);

                if (app != null && app.info != null) {
                    return app.info.packageName;
                }

                return null;
            }
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
                if (pr.mState.hasOverlayUi() == hasOverlayUi) {
                    return;
                }
                pr.mState.setHasOverlayUi(hasOverlayUi);
                //Slog.i(TAG, "Setting hasOverlayUi=" + pr.hasOverlayUi + " for pid=" + pid);
                updateOomAdjLocked(pr, OOM_ADJ_REASON_UI_VISIBILITY);
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
            synchronized (mProcLock) {
                record = mProcessList.getUidRecordLOSP(uid);
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
                if (record.procStateSeqWaitingForNetwork != 0
                        && procStateSeq >= record.procStateSeqWaitingForNetwork) {
                    if (DEBUG_NETWORK) {
                        Slog.d(TAG_NETWORK, "Notifying all blocking threads for uid: " + uid
                                + ", procStateSeq: " + procStateSeq
                                + ", procStateSeqWaitingForNetwork: "
                                + record.procStateSeqWaitingForNetwork);
                    }
                    record.networkStateLock.notifyAll();
                }
            }
        }

        @Override
        public void onUidBlockedReasonsChanged(int uid, int blockedReasons) {
            synchronized (mUidNetworkBlockedReasons) {
                if (blockedReasons == BLOCKED_REASON_NONE) {
                    mUidNetworkBlockedReasons.delete(uid);
                } else {
                    mUidNetworkBlockedReasons.put(uid, blockedReasons);
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
            return mUserController.getMaxRunningUsers();
        }

        @Override
        public boolean isUidActive(int uid) {
            synchronized (mProcLock) {
                return isUidActiveLOSP(uid);
            }
        }

        @Override
        public List<ProcessMemoryState> getMemoryStateForProcesses() {
            List<ProcessMemoryState> processMemoryStates = new ArrayList<>();
            synchronized (mPidsSelfLocked) {
                for (int i = 0, size = mPidsSelfLocked.size(); i < size; i++) {
                    final ProcessRecord r = mPidsSelfLocked.valueAt(i);
                    processMemoryStates.add(new ProcessMemoryState(
                            r.uid, r.getPid(), r.processName, r.mState.getCurAdj(),
                            r.mServices.hasForegroundServices(),
                            r.mProfile.getCurrentHostingComponentTypes(),
                            r.mProfile.getHistoricalHostingComponentTypes()));
                }
            }
            return processMemoryStates;
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
        public Pair<Integer, Integer> getCurrentAndTargetUserIds() {
            return mUserController.getCurrentAndTargetUserIds();
        }

        @Override
        public int getCurrentUserId() {
            return mUserController.getCurrentUserId();
        }

        @Override
        public boolean isUserRunning(int userId, int flags) {
            // Holding am lock isn't required to call into user controller.
            return mUserController.isUserRunning(userId, flags);
        }

        @Override
        public void trimApplications() {
            ActivityManagerService.this.trimApplications(true, OOM_ADJ_REASON_ACTIVITY);
        }

        public void killProcessesForRemovedTask(ArrayList<Object> procsToKill) {
            synchronized (ActivityManagerService.this) {
                for (int i = 0; i < procsToKill.size(); i++) {
                    final WindowProcessController wpc =
                            (WindowProcessController) procsToKill.get(i);
                    final ProcessRecord pr = (ProcessRecord) wpc.mOwner;
                    if (ActivityManager.isProcStateBackground(pr.mState.getSetProcState())
                            && pr.mReceivers.numberOfCurReceivers() == 0
                            && !pr.mState.hasStartedServices()) {
                        pr.killLocked("remove task", ApplicationExitInfo.REASON_USER_REQUESTED,
                                ApplicationExitInfo.SUBREASON_REMOVE_TASK, true);
                    } else {
                        // We delay killing processes that are not in the background or running a
                        // receiver.
                        pr.setWaitingToKill("remove task");
                    }
                }
            }
        }

        @Override
        public void killProcess(String processName, int uid, String reason) {
            synchronized (ActivityManagerService.this) {
                final ProcessRecord proc = getProcessRecordLocked(processName, uid);
                if (proc != null) {
                    mProcessList.removeProcessLocked(proc, false /* callerWillRestart */,
                            true /* allowRestart */,  ApplicationExitInfo.REASON_OTHER, reason);
                }
            }
        }

        @Override
        public boolean hasRunningActivity(int uid, @Nullable String packageName) {
            if (packageName == null) return false;

            synchronized (mProcLock) {
                return mProcessList.searchEachLruProcessesLOSP(true, app -> {
                    if (app.uid == uid
                            && app.getWindowProcessController().hasRunningActivity(packageName)) {
                        return Boolean.TRUE;
                    }
                    return null;
                }) != null;
            }
        }

        @Override
        public void updateOomAdj(@OomAdjReason int oomAdjReason) {
            synchronized (ActivityManagerService.this) {
                ActivityManagerService.this.updateOomAdjLocked(oomAdjReason);
            }
        }

        @Override
        public void updateCpuStats() {
            ActivityManagerService.this.updateCpuStats();
        }

        @Override
        public void updateBatteryStats(ComponentName activity, int uid, int userId,
                boolean resumed) {
            ActivityManagerService.this.updateBatteryStats(activity, uid, userId, resumed);
        }

        @Override
        public void updateActivityUsageStats(ComponentName activity, int userId, int event,
                IBinder appToken, ComponentName taskRoot, ActivityId activityId) {
            ActivityManagerService.this.updateActivityUsageStats(activity, userId, event,
                    appToken, taskRoot, activityId);
        }

        @Override
        public void updateForegroundTimeIfOnBattery(
                String packageName, int uid, long cpuTimeDiff) {
            mBatteryStatsService.updateForegroundTimeIfOnBattery(packageName, uid, cpuTimeDiff);
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
        public void noteAlarmFinish(PendingIntent ps, WorkSource workSource, int sourceUid,
                String tag) {
            ActivityManagerService.this.noteAlarmFinish((ps != null) ? ps.getTarget() : null,
                    workSource, sourceUid, tag);
        }

        @Override
        public void noteAlarmStart(PendingIntent ps, WorkSource workSource, int sourceUid,
                String tag) {
            ActivityManagerService.this.noteAlarmStart((ps != null) ? ps.getTarget() : null,
                    workSource, sourceUid, tag);
        }

        @Override
        public void noteWakeupAlarm(PendingIntent ps, WorkSource workSource, int sourceUid,
                String sourcePkg, String tag) {
            ActivityManagerService.this.noteWakeupAlarm((ps != null) ? ps.getTarget() : null,
                    workSource, sourceUid, sourcePkg, tag);
        }

        @Override
        public boolean isAppStartModeDisabled(int uid, String packageName) {
            return ActivityManagerService.this.isAppStartModeDisabled(uid, packageName);
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
            synchronized (mAppProfiler.mProfilerLock) {
                mAppProfiler.scheduleAppGcsLPf();
            }
        }

        @Override
        public int getTaskIdForActivity(IBinder token, boolean onlyRoot) {
            return ActivityManagerService.this.getTaskForActivity(token, onlyRoot);
        }

        @Override
        public ActivityPresentationInfo getActivityPresentationInfo(IBinder token) {
            final ActivityClient ac = ActivityClient.getInstance();
            return new ActivityPresentationInfo(ac.getTaskForActivity(token,
                    /*onlyRoot=*/ false), ac.getDisplayId(token),
                    mAtmInternal.getActivityName(token));
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
        public void tempAllowlistForPendingIntent(int callerPid, int callerUid, int targetUid,
                long duration, int type, @ReasonCode int reasonCode, String reason) {
            synchronized (ActivityManagerService.this) {
                ActivityManagerService.this.tempAllowlistForPendingIntentLocked(
                        callerPid, callerUid, targetUid, duration, type, reasonCode, reason);
            }
        }

        @Override
        public int broadcastIntentInPackage(String packageName, @Nullable String featureId, int uid,
                int realCallingUid, int realCallingPid, Intent intent, String resolvedType,
                IApplicationThread resultToThread, IIntentReceiver resultTo, int resultCode,
                String resultData, Bundle resultExtras, String requiredPermission, Bundle bOptions,
                boolean serialized, boolean sticky, int userId,
                BackgroundStartPrivileges backgroundStartPrivileges,
                @Nullable int[] broadcastAllowList) {
            synchronized (ActivityManagerService.this) {
                final ProcessRecord resultToApp = getRecordForAppLOSP(resultToThread);
                return ActivityManagerService.this.broadcastIntentInPackage(packageName, featureId,
                        uid, realCallingUid, realCallingPid, intent, resolvedType, resultToApp,
                        resultTo, resultCode, resultData, resultExtras, requiredPermission,
                        bOptions, serialized, sticky, userId,
                        backgroundStartPrivileges,
                        broadcastAllowList);
            }
        }

        @Override
        public int broadcastIntent(Intent intent,
                IIntentReceiver resultTo,
                String[] requiredPermissions,
                boolean serialized, int userId, int[] appIdAllowList,
                @Nullable BiFunction<Integer, Bundle, Bundle> filterExtrasForReceiver,
                @Nullable Bundle bOptions) {
            synchronized (ActivityManagerService.this) {
                intent = verifyBroadcastLocked(intent);

                final int callingPid = Binder.getCallingPid();
                final int callingUid = Binder.getCallingUid();
                final long origId = Binder.clearCallingIdentity();
                try {
                    return ActivityManagerService.this.broadcastIntentLocked(null /*callerApp*/,
                            null /*callerPackage*/, null /*callingFeatureId*/, intent,
                            null /* resolvedType */, null /* resultToApp */, resultTo,
                            0 /* resultCode */, null /* resultData */,
                            null /* resultExtras */, requiredPermissions,
                            null /*excludedPermissions*/, null /*excludedPackages*/,
                            AppOpsManager.OP_NONE, bOptions /*options*/, serialized,
                            false /*sticky*/, callingPid, callingUid, callingUid, callingPid,
                            userId, BackgroundStartPrivileges.NONE,
                            appIdAllowList, filterExtrasForReceiver);
                } finally {
                    Binder.restoreCallingIdentity(origId);
                }
            }
        }

        @Override
        public int broadcastIntentWithCallback(Intent intent,
                IIntentReceiver resultTo,
                String[] requiredPermissions,
                int userId, int[] appIdAllowList,
                @Nullable BiFunction<Integer, Bundle, Bundle> filterExtrasForReceiver,
                @Nullable Bundle bOptions) {
            return broadcastIntent(intent, resultTo, requiredPermissions, false /* serialized */,
                    userId, appIdAllowList, filterExtrasForReceiver, bOptions);
        }

        @Override
        public ComponentName startServiceInPackage(int uid, Intent service, String resolvedType,
                boolean fgRequired, String callingPackage, @Nullable String callingFeatureId,
                int userId, BackgroundStartPrivileges backgroundStartPrivileges)
                throws TransactionTooLargeException {
            if (DEBUG_SERVICE) {
                Slog.v(TAG_SERVICE,
                        "startServiceInPackage: " + service + " type=" + resolvedType);
            }
            final long origId = Binder.clearCallingIdentity();
            ComponentName res;
            try {
                if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
                    Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "startServiceInPackage: "
                            + "intent=" + service + ", caller=" + callingPackage
                            + ", fgRequired=" + fgRequired);
                }
                synchronized (ActivityManagerService.this) {
                    res = mServices.startServiceLocked(null, service,
                            resolvedType, -1, uid, fgRequired, callingPackage,
                            callingFeatureId, userId,
                            backgroundStartPrivileges);
                }
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                Binder.restoreCallingIdentity(origId);
            }
            return res;
        }

        // The arguments here are untyped because the base ActivityManagerInternal class
        // doesn't have compile-time visibility into ActivityServiceConnectionHolder or
        // ConnectionRecord.
        @Override
        public void disconnectActivityFromServices(Object connectionHolder) {
            // 'connectionHolder' is an untyped ActivityServiceConnectionsHolder
            final ActivityServiceConnectionsHolder holder =
                    (ActivityServiceConnectionsHolder) connectionHolder;
            synchronized (ActivityManagerService.this) {
                synchronized (mProcLock) {
                    holder.forEachConnection(cr -> mServices.removeConnectionLocked(
                            (ConnectionRecord) cr, null /* skipApp */, holder /* skipAct */,
                            false /* enqueueOomAdj */));
                }
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

        public boolean isBackgroundActivityStartsEnabled() {
            return mConstants.mFlagBackgroundActivityStartsEnabled;
        }

        @Override
        public BackgroundStartPrivileges getBackgroundStartPrivileges(int uid) {
            return ActivityManagerService.this.getBackgroundStartPrivileges(uid);
        }

        @Override
        public boolean canScheduleUserInitiatedJobs(int uid, int pid, String pkgName) {
            return ActivityManagerService.this.canScheduleUserInitiatedJobs(uid, pid, pkgName);
        }

        public void reportCurKeyguardUsageEvent(boolean keyguardShowing) {
            ActivityManagerService.this.reportGlobalUsageEvent(keyguardShowing
                    ? UsageEvents.Event.KEYGUARD_SHOWN
                    : UsageEvents.Event.KEYGUARD_HIDDEN);
        }

        @Override
        public void monitor() {
            ActivityManagerService.this.monitor();
        }

        @Override
        public long inputDispatchingTimedOut(int pid, boolean aboveSystem,
                TimeoutRecord timeoutRecord) {
            return ActivityManagerService.this.inputDispatchingTimedOut(pid, aboveSystem,
                    timeoutRecord);
        }

        @Override
        public boolean inputDispatchingTimedOut(Object proc, String activityShortComponentName,
                ApplicationInfo aInfo, String parentShortComponentName, Object parentProc,
                boolean aboveSystem, TimeoutRecord timeoutRecord) {
            return ActivityManagerService.this.inputDispatchingTimedOut((ProcessRecord) proc,
                    activityShortComponentName, aInfo, parentShortComponentName,
                    (WindowProcessController) parentProc, aboveSystem, timeoutRecord);
        }

        @Override
        public void inputDispatchingResumed(int pid) {
            final ProcessRecord proc;
            synchronized (mPidsSelfLocked) {
                proc = mPidsSelfLocked.get(pid);
            }
            if (proc != null) {
                mAppErrors.handleDismissAnrDialogs(proc);
            }
        }

        @Override
        public void rescheduleAnrDialog(Object data) {
            Message msg = Message.obtain();
            msg.what = SHOW_NOT_RESPONDING_UI_MSG;
            msg.obj = (AppNotRespondingDialog.Data) data;

            mUiHandler.sendMessageDelayed(msg, InputConstants.DEFAULT_DISPATCHING_TIMEOUT_MILLIS);
        }

        @Override
        public void broadcastGlobalConfigurationChanged(int changes, boolean initLocale) {
            synchronized (ActivityManagerService.this) {
                Intent intent = new Intent(Intent.ACTION_CONFIGURATION_CHANGED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                        | Intent.FLAG_RECEIVER_REPLACE_PENDING
                        | Intent.FLAG_RECEIVER_FOREGROUND
                        | Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
                final Bundle configChangedOptions = new BroadcastOptions()
                        .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT)
                        .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE)
                        .toBundle();
                broadcastIntentLocked(null, null, null, intent, null, null, 0, null, null, null,
                        null, null, OP_NONE, configChangedOptions, false, false, MY_PID, SYSTEM_UID,
                        Binder.getCallingUid(), Binder.getCallingPid(), UserHandle.USER_ALL);
                if ((changes & ActivityInfo.CONFIG_LOCALE) != 0) {
                    intent = new Intent(Intent.ACTION_LOCALE_CHANGED);
                    intent.addFlags(Intent.FLAG_RECEIVER_OFFLOAD_FOREGROUND
                            | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND
                            | Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
                    if (initLocale || !mProcessesReady) {
                        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                    }
                    final BroadcastOptions bOptions = BroadcastOptions.makeBasic();
                    bOptions.setTemporaryAppAllowlist(mInternal.getBootTimeTempAllowListDuration(),
                            TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                            PowerExemptionManager.REASON_LOCALE_CHANGED, "");
                    bOptions.setDeliveryGroupPolicy(
                            BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT);
                    bOptions.setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE);
                    broadcastIntentLocked(null, null, null, intent, null, null, 0, null, null, null,
                            null, null, OP_NONE, bOptions.toBundle(), false, false, MY_PID,
                            SYSTEM_UID, Binder.getCallingUid(), Binder.getCallingPid(),
                            UserHandle.USER_ALL);
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
                    broadcastIntentLocked(null, null, null, intent, null, null, 0, null, null,
                            permissions, null, null, OP_NONE, null, false, false, MY_PID,
                            SYSTEM_UID, Binder.getCallingUid(), Binder.getCallingPid(),
                            UserHandle.USER_ALL);
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

                final BroadcastOptions options = new BroadcastOptions()
                        .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT)
                        .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE);
                if (reason != null) {
                    options.setDeliveryGroupMatchingKey(Intent.ACTION_CLOSE_SYSTEM_DIALOGS, reason);
                }
                broadcastIntentLocked(null, null, null, intent, null, null, 0, null, null, null,
                        null, null, OP_NONE, options.toBundle(), false, false, -1, SYSTEM_UID,
                        Binder.getCallingUid(), Binder.getCallingPid(), UserHandle.USER_ALL);
            }
        }

        @Override
        public void appNotResponding(@NonNull String processName, int uid,
                @NonNull TimeoutRecord timeoutRecord) {
            ActivityManagerService.this.appNotResponding(processName, uid, timeoutRecord);
        }

        @Override
        public void killAllBackgroundProcessesExcept(int minTargetSdk, int maxProcState) {
            synchronized (ActivityManagerService.this) {
                ActivityManagerService.this.killAllBackgroundProcessesExcept(
                        minTargetSdk, maxProcState);
            }
        }

        @Override
        public void startProcess(String processName, ApplicationInfo info, boolean knownToBeDead,
                boolean isTop, String hostingType, ComponentName hostingName) {
            try {
                if (Trace.isTagEnabled(Trace.TRACE_TAG_ACTIVITY_MANAGER)) {
                    Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "startProcess:"
                            + processName);
                }
                synchronized (ActivityManagerService.this) {
                    // If the process is known as top app, set a hint so when the process is
                    // started, the top priority can be applied immediately to avoid cpu being
                    // preempted by other processes before attaching the process of top app.
                    HostingRecord hostingRecord =
                            new HostingRecord(hostingType, hostingName, isTop);
                    ProcessRecord rec = getProcessRecordLocked(processName, info.uid);
                    ProcessRecord app = startProcessLocked(processName, info, knownToBeDead,
                            0 /* intentFlags */, hostingRecord,
                            ZYGOTE_POLICY_FLAG_LATENCY_SENSITIVE, false /* allowWhileBooting */,
                            false /* isolated */);
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
                        boolean suspend =
                                (startFlags & ActivityManager.START_FLAG_DEBUG_SUSPEND) != 0;
                        setDebugApp(aInfo.processName, true, false, suspend);
                    }

                    if ((startFlags & ActivityManager.START_FLAG_NATIVE_DEBUGGING) != 0) {
                        setNativeDebuggingAppLocked(aInfo.applicationInfo, aInfo.processName);
                    }

                    if ((startFlags & ActivityManager.START_FLAG_TRACK_ALLOCATION) != 0) {
                        setTrackAllocationApp(aInfo.applicationInfo, aInfo.processName);
                    }

                    if (profilerInfo != null) {
                        setProfileApp(aInfo.applicationInfo, aInfo.processName, profilerInfo, null);
                    }
                    wmLock.notify();
                }
            }
        }

        @Override
        public int getStorageMountMode(int pid, int uid) {
            if (uid == SHELL_UID || uid == ROOT_UID) {
                return Zygote.MOUNT_EXTERNAL_DEFAULT;
            }
            synchronized (mPidsSelfLocked) {
                final ProcessRecord pr = mPidsSelfLocked.get(pid);
                return pr == null ? Zygote.MOUNT_EXTERNAL_NONE : pr.getMountMode();
            }
        }

        @Override
        public boolean isAppForeground(int uid) {
            return ActivityManagerService.this.isAppForeground(uid);
        }

        @Override
        public boolean isAppBad(final String processName, final int uid) {
            return ActivityManagerService.this.isAppBad(processName, uid);
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
                final UidRecord uidRec = mProcessList.mActiveUids.get(uid);
                if (uidRec == null) {
                    return false;
                }
                for (int i = uidRec.getNumOfProcs() - 1; i >= 0; i--) {
                    final ProcessRecord app = uidRec.getProcessRecordByIndex(i);
                    if ((app.mServices.containsAnyForegroundServiceTypes(foregroundServicetype))) {
                        return true;
                    }
                }
                return false;
            }
        }

        @Override
        public boolean hasForegroundServiceNotification(String pkg, int userId,
                String channelId) {
            synchronized (ActivityManagerService.this) {
                return mServices.hasForegroundServiceNotificationLocked(pkg, userId, channelId);
            }
        }

        @Override
        public ServiceNotificationPolicy applyForegroundServiceNotification(
                Notification notification, String tag, int id, String pkg, int userId) {
            synchronized (ActivityManagerService.this) {
                return mServices.applyForegroundServiceNotificationLocked(notification,
                        tag, id, pkg, userId);
            }
        }

        @Override
        public void onForegroundServiceNotificationUpdate(boolean shown,
                Notification notification, int id, String pkg, @UserIdInt int userId) {
            synchronized (ActivityManagerService.this) {
                mServices.onForegroundServiceNotificationUpdateLocked(shown,
                        notification, id, pkg, userId);
            }
        }

        @Override
        public void stopAppForUser(String pkg, @UserIdInt int userId) {
            ActivityManagerService.this.stopAppForUserInternal(pkg, userId);
        }

        @Override
        public void registerProcessObserver(IProcessObserver processObserver) {
            ActivityManagerService.this.registerProcessObserver(processObserver);
        }

        @Override
        public void unregisterProcessObserver(IProcessObserver processObserver) {
            ActivityManagerService.this.unregisterProcessObserver(processObserver);
        }

        @Override
        public int getInstrumentationSourceUid(int uid) {
            synchronized (mProcLock) {
                for (int i = mActiveInstrumentation.size() - 1; i >= 0; i--) {
                    ActiveInstrumentation activeInst = mActiveInstrumentation.get(i);
                    if (!activeInst.mFinished && activeInst.mTargetInfo != null
                            && activeInst.mTargetInfo.uid == uid) {
                        return activeInst.mSourceUid;
                    }
                }
            }
            return INVALID_UID;
        }

        @Override
        public void setDeviceOwnerUid(int uid) {
            mDeviceOwnerUid = uid;
        }

        @Override
        public boolean isDeviceOwner(int uid) {
            int cachedUid = mDeviceOwnerUid;
            return uid >= 0 && cachedUid == uid;
        }


        @Override
        public void setProfileOwnerUid(ArraySet<Integer> profileOwnerUids) {
            synchronized (ActivityManagerService.this) {
                mProfileOwnerUids = profileOwnerUids;
            }
        }

        @Override
        public boolean isProfileOwner(int uid) {
            synchronized (ActivityManagerService.this) {
                return mProfileOwnerUids != null && mProfileOwnerUids.indexOf(uid) >= 0;
            }
        }

        @Override
        public void setCompanionAppUids(int userId, Set<Integer> companionAppUids) {
            synchronized (ActivityManagerService.this) {
                mCompanionAppUidsMap.put(userId, companionAppUids);
            }
        }

        @Override
        public boolean isAssociatedCompanionApp(int userId, int uid) {
            final Set<Integer> allUids = mCompanionAppUidsMap.get(userId);
            if (allUids == null) {
                return false;
            }
            return allUids.contains(uid);
        }

        @Override
        public void addPendingTopUid(int uid, int pid, @Nullable IApplicationThread thread) {
            final boolean isNewPending = mPendingStartActivityUids.add(uid, pid);
            // If the next top activity is in cached and frozen mode, WM should raise its priority
            // to unfreeze it. This is done by calling AMS.updateOomAdj that will lower its oom adj.
            // However, WM cannot hold the AMS clock here so the updateOomAdj operation is performed
            // in a separate thread asynchronously. Therefore WM can't guarantee AMS will unfreeze
            // next top activity on time. This race will fail the following binder transactions WM
            // sends to the activity. After this race issue between WM/ATMS and AMS is solved, this
            // workaround can be removed. (b/213288355)
            if (isNewPending) {
                mOomAdjuster.mCachedAppOptimizer.unfreezeProcess(pid, OOM_ADJ_REASON_ACTIVITY);
            }
            // We need to update the network rules for the app coming to the top state so that
            // it can access network when the device or the app is in a restricted state
            // (e.g. battery/data saver) but since waiting for updateOomAdj to complete and then
            // informing NetworkPolicyManager might get delayed, informing the state change as soon
            // as we know app is going to come to the top state.
            if (isNewPending && mNetworkPolicyUidObserver != null) {
                try {
                    final long procStateSeq = mProcessList.getNextProcStateSeq();
                    mNetworkPolicyUidObserver.onUidStateChanged(uid, PROCESS_STATE_TOP,
                            procStateSeq, PROCESS_CAPABILITY_ALL);
                    if (thread != null && shouldWaitForNetworkRulesUpdate(uid)) {
                        thread.setNetworkBlockSeq(procStateSeq);
                    }
                } catch (RemoteException e) {
                    Slog.d(TAG, "Error calling setNetworkBlockSeq", e);
                }
            }
        }

        private boolean shouldWaitForNetworkRulesUpdate(int uid) {
            synchronized (mUidNetworkBlockedReasons) {
                // TODO: We can reuse this data in
                // ProcessList#incrementProcStateSeqAndNotifyAppsLOSP instead of calling into
                // NetworkManagementService.
                final int uidBlockedReasons = mUidNetworkBlockedReasons.get(
                        uid, BLOCKED_REASON_NONE);
                // We should only inform the uid to block if it is currently blocked but will be
                // unblocked once it comes to the TOP state.
                return uidBlockedReasons != BLOCKED_REASON_NONE
                        && updateBlockedReasonsWithProcState(uidBlockedReasons, PROCESS_STATE_TOP)
                        == BLOCKED_REASON_NONE;
            }
        }

        @Override
        public void deletePendingTopUid(int uid, long nowElapsed) {
            mPendingStartActivityUids.delete(uid, nowElapsed);
        }

        @Override
        public boolean isPendingTopUid(int uid) {
            return mPendingStartActivityUids.isPendingTopUid(uid);
        }

        @Override
        public Intent getIntentForIntentSender(IIntentSender sender) {
            return ActivityManagerService.this.getIntentForIntentSender(sender);
        }

        @Override
        public PendingIntent getPendingIntentActivityAsApp(
                int requestCode, @NonNull Intent intent, int flags, Bundle options,
                String ownerPkg, int ownerUid) {
            return getPendingIntentActivityAsApp(requestCode, new Intent[] { intent }, flags,
                    options, ownerPkg, ownerUid);
        }

        @Override
        public PendingIntent getPendingIntentActivityAsApp(
                int requestCode, @NonNull Intent[] intents, int flags, Bundle options,
                String ownerPkg, int ownerUid) {
            // system callers must explicitly set mutability state
            final boolean flagImmutableSet = (flags & PendingIntent.FLAG_IMMUTABLE) != 0;
            final boolean flagMutableSet = (flags & PendingIntent.FLAG_MUTABLE) != 0;
            if (flagImmutableSet == flagMutableSet) {
                throw new IllegalArgumentException(
                        "Must set exactly one of FLAG_IMMUTABLE or FLAG_MUTABLE");
            }

            final Context context = ActivityManagerService.this.mContext;
            final ContentResolver resolver = context.getContentResolver();
            final int len = intents.length;
            final String[] resolvedTypes = new String[len];
            for (int i = 0; i < len; i++) {
                final Intent intent = intents[i];
                resolvedTypes[i] = intent.resolveTypeIfNeeded(resolver);
                intent.migrateExtraStreamToClipData(context);
                intent.prepareToLeaveProcess(context);
            }
            IIntentSender target =
                    ActivityManagerService.this.getIntentSenderWithFeatureAsApp(
                            INTENT_SENDER_ACTIVITY, ownerPkg,
                            context.getAttributionTag(), null, null, requestCode,
                            intents,
                            resolvedTypes,
                            flags, options, UserHandle.getUserId(ownerUid), ownerUid);
            return target != null ? new PendingIntent(target) : null;
        }

        @Override
        public long getBootTimeTempAllowListDuration() {
            // Do not lock ActivityManagerService.this here, this API is called by
            // PackageManagerService.
            return mConstants.mBootTimeTempAllowlistDuration;
        }

        @Override
        public void registerAnrController(AnrController controller) {
            mActivityTaskManager.registerAnrController(controller);
        }

        @Override
        public void unregisterAnrController(AnrController controller) {
            mActivityTaskManager.unregisterAnrController(controller);
        }

        @Override
        public boolean canStartForegroundService(int pid, int uid, @NonNull String packageName) {
            synchronized (ActivityManagerService.this) {
                return mServices.canStartForegroundServiceLocked(pid, uid, packageName);
            }
        }

        @Override
        public void tempAllowWhileInUsePermissionInFgs(int uid, long durationMs) {
            mFgsWhileInUseTempAllowList.add(uid, durationMs, "");
        }

        @Override
        public boolean isTempAllowlistedForFgsWhileInUse(int uid) {
            return mFgsWhileInUseTempAllowList.isAllowed(uid);
        }

        @Override
        public boolean canAllowWhileInUsePermissionInFgs(int pid, int uid,
                @NonNull String packageName) {
            synchronized (ActivityManagerService.this) {
                return mServices.canAllowWhileInUsePermissionInFgsLocked(pid, uid, packageName);
            }
        }

        @Override
        public @TempAllowListType int getPushMessagingOverQuotaBehavior() {
            synchronized (ActivityManagerService.this) {
                return mConstants.mPushMessagingOverQuotaBehavior;
            }
        }

        @Override
        public int getServiceStartForegroundTimeout() {
            return mConstants.mServiceStartForegroundTimeoutMs;
        }

        @Override
        public int getUidCapability(int uid) {
            synchronized (ActivityManagerService.this) {
                UidRecord uidRecord = mProcessList.getUidRecordLOSP(uid);
                if (uidRecord == null) {
                    throw new IllegalArgumentException("uid record for " + uid + " not found");
                }
                return uidRecord.getCurCapability();
            }
        }

        /**
         * @return The PID list of the isolated process with packages matching the given uid.
         */
        @Nullable
        public List<Integer> getIsolatedProcesses(int uid) {
            synchronized (ActivityManagerService.this) {
                return mProcessList.getIsolatedProcessesLocked(uid);
            }
        }

        /** @see ActivityManagerService#sendIntentSender */
        @Override
        public int sendIntentSender(IIntentSender target, IBinder allowlistToken, int code,
                Intent intent, String resolvedType,
                IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
            return ActivityManagerService.this.sendIntentSender(null, target, allowlistToken, code,
                    intent, resolvedType, finishedReceiver, requiredPermission, options);
        }

        @Override
        public void setVoiceInteractionManagerProvider(
                @Nullable ActivityManagerInternal.VoiceInteractionManagerProvider provider) {
            ActivityManagerService.this.setVoiceInteractionManagerProvider(provider);
        }

        @Override
        public void setStopUserOnSwitch(int value) {
            ActivityManagerService.this.setStopUserOnSwitch(value);
        }

        @Override
        public @RestrictionLevel int getRestrictionLevel(int uid) {
            return mAppRestrictionController.getRestrictionLevel(uid);
        }

        @Override
        public @RestrictionLevel int getRestrictionLevel(String pkg, @UserIdInt int userId) {
            return mAppRestrictionController.getRestrictionLevel(pkg, userId);
        }

        @Override
        public boolean isBgAutoRestrictedBucketFeatureFlagEnabled() {
            return mAppRestrictionController.isBgAutoRestrictedBucketFeatureFlagEnabled();
        }

        @Override
        public void addAppBackgroundRestrictionListener(
                @NonNull ActivityManagerInternal.AppBackgroundRestrictionListener listener) {
            mAppRestrictionController.addAppBackgroundRestrictionListener(listener);
        }

        @Override
        public void addForegroundServiceStateListener(
                @NonNull ForegroundServiceStateListener listener) {
            synchronized (ActivityManagerService.this) {
                mForegroundServiceStateListeners.add(listener);
            }
        }

        @Override
        public void addBroadcastEventListener(@NonNull BroadcastEventListener listener) {
            // It's a CopyOnWriteArrayList, so no lock is needed.
            mBroadcastEventListeners.add(listener);
        }

        @Override
        public void addBindServiceEventListener(@NonNull BindServiceEventListener listener) {
            // It's a CopyOnWriteArrayList, so no lock is needed.
            mBindServiceEventListeners.add(listener);
        }

        @Override
        public void restart() {
            ActivityManagerService.this.restart();
        }

        @Override
        public void registerNetworkPolicyUidObserver(@NonNull IUidObserver observer,
                int which, int cutpoint, @NonNull String callingPackage) {
            mNetworkPolicyUidObserver = observer;
            mUidObserverController.register(observer, which, cutpoint, callingPackage,
                    Binder.getCallingUid(), /*uids*/null);
        }

        @Override
        public boolean startForegroundServiceDelegate(
                @NonNull ForegroundServiceDelegationOptions options,
                @Nullable ServiceConnection connection) {
            synchronized (ActivityManagerService.this) {
                return mServices.startForegroundServiceDelegateLocked(options, connection);
            }
        }

        @Override
        public void stopForegroundServiceDelegate(
                @NonNull ForegroundServiceDelegationOptions options) {
            synchronized (ActivityManagerService.this) {
                mServices.stopForegroundServiceDelegateLocked(options);
            }
        }

        @Override
        public void stopForegroundServiceDelegate(@NonNull ServiceConnection connection) {
            synchronized (ActivityManagerService.this) {
                mServices.stopForegroundServiceDelegateLocked(connection);
            }
        }

        @Override
        public ArraySet<String> getClientPackages(String servicePackageName) {
            synchronized (ActivityManagerService.this) {
                return mServices.getClientPackagesLocked(servicePackageName);
            }
        }

        @Override
        public IUnsafeIntentStrictModeCallback getRegisteredStrictModeCallback(int callingPid) {
            return mStrictModeCallbacks.get(callingPid);
        }

        @Override
        public void unregisterStrictModeCallback(int callingPid) {
            mStrictModeCallbacks.remove(callingPid);
        }

        @Override
        public boolean startProfileEvenWhenDisabled(@UserIdInt int userId) {
            return mUserController.startProfile(userId, /* evenWhenDisabled= */ true,
                    /* unlockListener= */ null);
        }

        @Override
        public void logFgsApiBegin(@ForegroundServiceApiType int apiType,
                int uid, int pid) {
            synchronized (this) {
                mServices.logFgsApiBeginLocked(apiType, uid, pid);
            }
        }

        @Override
        public void logFgsApiEnd(@ForegroundServiceApiType int apiType,
                int uid, int pid) {
            synchronized (this) {
                mServices.logFgsApiEndLocked(apiType, uid, pid);
            }
        }

        @Override
        public void notifyMediaProjectionEvent(int uid, @NonNull IBinder projectionToken,
                @MediaProjectionTokenEvent int event) {
            ActivityManagerService.this.notifyMediaProjectionEvent(uid, projectionToken, event);
        }

        @Override
        @NonNull
        public StatsEvent getCachedAppsHighWatermarkStats(int atomTag, boolean resetAfterPull) {
            return mAppProfiler.mCachedAppsWatermarkData.getCachedAppsHighWatermarkStats(
                    atomTag, resetAfterPull);
        }

        @Override
        public boolean clearApplicationUserData(final String packageName, boolean keepState,
                boolean isRestore, final IPackageDataObserver observer, int userId) {
            return ActivityManagerService.this.clearApplicationUserData(packageName, keepState,
                    isRestore, observer, userId);
        }

        @Override
        public boolean shouldDelayHomeLaunch(int userId) {
            if (!isHomeLaunchDelayable()) return false;

            synchronized (mThemeOverlayReadyUsers) {
                return !ActivityManagerService.this.mThemeOverlayReadyUsers.contains(userId);
            }
        }

        @Override
        public void addStartInfoTimestamp(int key, long timestampNs, int uid, int pid,
                int userId) {
            // For the simplification, we don't support USER_ALL nor USER_CURRENT here.
            if (userId == UserHandle.USER_ALL || userId == UserHandle.USER_CURRENT) {
                throw new IllegalArgumentException("Unsupported userId");
            }

            mUserController.handleIncomingUser(pid, uid, userId, true,
                    ALLOW_NON_FULL, "addStartInfoTimestampSystem", null);

            addStartInfoTimestampInternal(key, timestampNs, userId, uid);
        }

        @Override
        public void killApplicationSync(String pkgName, int appId, int userId,
                String reason, int exitInfoReason) {
            if (pkgName == null) {
                return;
            }
            // Make sure the uid is valid.
            if (appId < 0) {
                Slog.w(TAG, "Invalid appid specified for pkg : " + pkgName);
                return;
            }
            synchronized (ActivityManagerService.this) {
                ActivityManagerService.this.forceStopPackageLocked(pkgName, appId,
                        /* callerWillRestart= */ false, /*purgeCache= */ false,
                        /* doit= */ true, /* evenPersistent= */ false,
                        /* uninstalling= */ false, /* packageStateStopped= */ false,
                        userId, reason, exitInfoReason);
            }
        }
    }

    long inputDispatchingTimedOut(int pid, final boolean aboveSystem, TimeoutRecord timeoutRecord) {
        if (checkCallingPermission(FILTER_EVENTS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission " + FILTER_EVENTS);
        }
        ProcessRecord proc;
        timeoutRecord.mLatencyTracker.waitingOnPidLockStarted();
        synchronized (mPidsSelfLocked) {
            timeoutRecord.mLatencyTracker.waitingOnPidLockEnded();
            proc = mPidsSelfLocked.get(pid);
        }
        final long timeoutMillis = proc != null ? proc.getInputDispatchingTimeoutMillis() :
                DEFAULT_DISPATCHING_TIMEOUT_MILLIS;

        if (inputDispatchingTimedOut(proc, null, null, null, null, aboveSystem, timeoutRecord)) {
            return 0;
        }

        return timeoutMillis;
    }

    /**
     * Handle input dispatching timeouts.
     * @return whether input dispatching should be aborted or not.
     */
    boolean inputDispatchingTimedOut(ProcessRecord proc, String activityShortComponentName,
            ApplicationInfo aInfo, String parentShortComponentName,
            WindowProcessController parentProcess, boolean aboveSystem,
            TimeoutRecord timeoutRecord) {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "inputDispatchingTimedOut()");
            if (checkCallingPermission(FILTER_EVENTS) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires permission " + FILTER_EVENTS);
            }

            if (proc != null) {
                timeoutRecord.mLatencyTracker.waitingOnAMSLockStarted();
                synchronized (this) {
                    timeoutRecord.mLatencyTracker.waitingOnAMSLockEnded();
                    if (proc.isDebugging()) {
                        return false;
                    }

                    if (proc.getActiveInstrumentation() != null) {
                        Bundle info = new Bundle();
                        info.putString("shortMsg", "keyDispatchingTimedOut");
                        info.putString("longMsg", timeoutRecord.mReason);
                        finishInstrumentationLocked(proc, Activity.RESULT_CANCELED, info);
                        return true;
                    }
                }
                mAnrHelper.appNotResponding(proc, activityShortComponentName, aInfo,
                        parentShortComponentName, parentProcess, aboveSystem, timeoutRecord,
                        /*isContinuousAnr*/ true);
            }

            return true;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
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
        synchronized (mProcLock) {
            record = mProcessList.getUidRecordLOSP(callingUid);
            if (record == null) {
                return;
            }
        }
        synchronized (record.networkStateLock) {
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
                record.procStateSeqWaitingForNetwork = procStateSeq;
                record.networkStateLock.wait(mConstants.mNetworkAccessTimeoutMs);
                record.procStateSeqWaitingForNetwork = 0;
                final long totalTime = SystemClock.uptimeMillis() - startTime;
                if (totalTime >= mConstants.mNetworkAccessTimeoutMs || DEBUG_NETWORK) {
                    Slog.wtf(TAG_NETWORK, "Total time waited for network rules to get updated: "
                            + totalTime + ". Uid: " + callingUid + " procStateSeq: "
                            + procStateSeq + " UidRec: " + record
                            + " validateUidRec: "
                            + mUidObserverController.getValidateUidRecord(callingUid));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void waitForBroadcastIdle() {
        waitForBroadcastIdle(LOG_WRITER_INFO, false);
    }

    void waitForBroadcastIdle(@NonNull PrintWriter pw, boolean flushBroadcastLoopers) {
        enforceCallingPermission(permission.DUMP, "waitForBroadcastIdle()");
        if (flushBroadcastLoopers) {
            BroadcastLoopers.waitForIdle(pw);
        }
        mBroadcastQueue.waitForIdle(pw);
        pw.println("All broadcast queues are idle!");
        pw.flush();
    }

    @Override
    public void waitForBroadcastBarrier() {
        waitForBroadcastBarrier(LOG_WRITER_INFO, false, false);
    }

    void waitForBroadcastBarrier(@NonNull PrintWriter pw,
            boolean flushBroadcastLoopers, boolean flushApplicationThreads) {
        enforceCallingPermission(permission.DUMP, "waitForBroadcastBarrier()");
        if (flushBroadcastLoopers) {
            BroadcastLoopers.waitForBarrier(pw);
        }
        mBroadcastQueue.waitForBarrier(pw);
        if (flushApplicationThreads) {
            waitForApplicationBarrier(pw);
        }
    }

    /**
     * Wait for all pending {@link IApplicationThread} events to be processed in
     * all currently running apps.
     */
    void waitForApplicationBarrier(@NonNull PrintWriter pw) {
        final CountDownLatch finishedLatch = new CountDownLatch(1);
        final AtomicInteger pingCount = new AtomicInteger(0);
        final AtomicInteger pongCount = new AtomicInteger(0);
        final RemoteCallback pongCallback = new RemoteCallback((result) -> {
            if (pongCount.incrementAndGet() == pingCount.get()) {
                finishedLatch.countDown();
            }
        });

        // Insert an extra "ping" as a sentinel value to guard us from finishing
        // too quickly in parallel below
        pingCount.incrementAndGet();

        synchronized (ActivityManagerService.this) {
            synchronized (mProcLock) {
                final ArrayMap<String, SparseArray<ProcessRecord>> pmap =
                        mProcessList.getProcessNamesLOSP().getMap();
                final int numProc = pmap.size();
                for (int iProc = 0; iProc < numProc; iProc++) {
                    final SparseArray<ProcessRecord> apps = pmap.valueAt(iProc);
                    for (int iApp = 0, numApps = apps.size(); iApp < numApps; iApp++) {
                        final ProcessRecord app = apps.valueAt(iApp);
                        final IApplicationThread thread = app.getOnewayThread();
                        if (thread != null) {
                            mOomAdjuster.mCachedAppOptimizer.unfreezeTemporarily(app,
                                    CachedAppOptimizer.UNFREEZE_REASON_PING);
                            pingCount.incrementAndGet();
                            try {
                                thread.schedulePing(pongCallback);
                            } catch (RemoteException ignored) {
                                // When we failed to ping remote process, pretend as
                                // if we received the expected pong
                                pongCallback.sendResult(null);
                            }
                        }
                    }
                }
            }
        }

        // Now that we've dispatched all "ping" events above, we can send our
        // "pong" sentinel value
        pongCallback.sendResult(null);

        // Wait for any remaining "pong" events to trickle in
        for (int i = 0; i < 30; i++) {
            try {
                if (finishedLatch.await(1, TimeUnit.SECONDS)) {
                    pw.println("Finished application barriers!");
                    pw.flush();
                    return;
                } else {
                    pw.println("Waiting for application barriers, at " + pongCount.get() + " of "
                            + pingCount.get() + "...");
                    pw.flush();
                }
            } catch (InterruptedException ignored) {
            }
        }
        pw.println("Gave up waiting for application barriers!");
        pw.flush();
    }

    void waitForBroadcastDispatch(@NonNull PrintWriter pw, @NonNull Intent intent) {
        enforceCallingPermission(permission.DUMP, "waitForBroadcastDispatch");
        mBroadcastQueue.waitForDispatched(intent, pw);
    }

    void setIgnoreDeliveryGroupPolicy(@NonNull String broadcastAction) {
        Objects.requireNonNull(broadcastAction);
        enforceCallingPermission(permission.DUMP, "waitForBroadcastBarrier()");
        synchronized (mDeliveryGroupPolicyIgnoredActions) {
            mDeliveryGroupPolicyIgnoredActions.add(broadcastAction);
        }
    }

    void clearIgnoreDeliveryGroupPolicy(@NonNull String broadcastAction) {
        Objects.requireNonNull(broadcastAction);
        enforceCallingPermission(permission.DUMP, "waitForBroadcastBarrier()");
        synchronized (mDeliveryGroupPolicyIgnoredActions) {
            mDeliveryGroupPolicyIgnoredActions.remove(broadcastAction);
        }
    }

    boolean shouldIgnoreDeliveryGroupPolicy(@Nullable String broadcastAction) {
        if (broadcastAction == null) {
            return false;
        }
        synchronized (mDeliveryGroupPolicyIgnoredActions) {
            return mDeliveryGroupPolicyIgnoredActions.contains(broadcastAction);
        }
    }

    void dumpDeliveryGroupPolicyIgnoredActions(IndentingPrintWriter ipw) {
        synchronized (mDeliveryGroupPolicyIgnoredActions) {
            ipw.println(mDeliveryGroupPolicyIgnoredActions);
        }
    }

    @Override
    public void forceDelayBroadcastDelivery(@NonNull String targetPackage,
            long delayedDurationMs) {
        Objects.requireNonNull(targetPackage);
        Preconditions.checkArgumentNonnegative(delayedDurationMs);
        enforceCallingPermission(permission.DUMP, "forceDelayBroadcastDelivery()");

        mBroadcastQueue.forceDelayBroadcastDelivery(targetPackage, delayedDurationMs);
    }

    @Override
    public boolean isProcessFrozen(int pid) {
        enforceCallingPermission(permission.DUMP, "isProcessFrozen()");
        return mOomAdjuster.mCachedAppOptimizer.isProcessFrozen(pid);
    }

    @Override
    @ReasonCode
    public int getBackgroundRestrictionExemptionReason(int uid) {
        enforceCallingPermission(android.Manifest.permission.DEVICE_POWER,
                "getBackgroundRestrictionExemptionReason()");
        return mAppRestrictionController.getBackgroundRestrictionExemptionReason(uid);
    }

    /**
     * Set an app's background restriction level.
     * This interface is intended for the shell command to use.
     */
    void setBackgroundRestrictionLevel(String packageName, int uid, int userId,
            @RestrictionLevel int level, int reason, int subReason) {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != SYSTEM_UID && callingUid != ROOT_UID && callingUid != SHELL_UID) {
            throw new SecurityException(
                    "No permission to change app restriction level");
        }
        final long callingId = Binder.clearCallingIdentity();
        try {
            final int curBucket = mUsageStatsService.getAppStandbyBucket(packageName, userId,
                    SystemClock.elapsedRealtime());
            mAppRestrictionController.applyRestrictionLevel(packageName, uid, level,
                    null /* trackerInfo */, curBucket, true /* allowUpdateBucket */,
                    reason, subReason);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    /**
     * Log the reason for changing an app restriction. Purely used for logging purposes and does not
     * cause any change to app state.
     *
     * @see ActivityManager#noteAppRestrictionEnabled(String, int, int, boolean, int,
     *          String, int, long)
     */
    @Override
    public void noteAppRestrictionEnabled(String packageName, int uid,
            @RestrictionLevel int restrictionType, boolean enabled,
            @ActivityManager.RestrictionReason int reason, String subReason,
            @ActivityManager.RestrictionSource int source, long threshold) {
        if (!android.app.Flags.appRestrictionsApi()) return;

        enforceCallingPermission(android.Manifest.permission.DEVICE_POWER,
                "noteAppRestrictionEnabled()");

        final int userId = UserHandle.getCallingUserId();
        final long callingId = Binder.clearCallingIdentity();
        try {
            if (uid == INVALID_UID) {
                uid = mPackageManagerInt.getPackageUid(packageName, 0, userId);
            }
            mAppRestrictionController.noteAppRestrictionEnabled(packageName, uid, restrictionType,
                    enabled, reason, subReason, source, threshold);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    /**
     * Get an app's background restriction level.
     * This interface is intended for the shell command to use.
     */
    @RestrictionLevel int getBackgroundRestrictionLevel(String packageName, int userId) {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != SYSTEM_UID && callingUid != ROOT_UID && callingUid != SHELL_UID) {
            throw new SecurityException(
                    "Don't have permission to query app background restriction level");
        }
        final long callingId = Binder.clearCallingIdentity();
        try {
            return mInternal.getRestrictionLevel(packageName, userId);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    /**
     * Start/stop foreground service delegate on a app's process.
     * This interface is intended for the shell command to use.
     */
    void setForegroundServiceDelegate(String packageName, int uid, boolean isStart,
            @ForegroundServiceDelegationOptions.DelegationService int delegateService,
            String clientInstanceName) {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != SYSTEM_UID && callingUid != ROOT_UID && callingUid != SHELL_UID) {
            throw new SecurityException(
                    "No permission to start/stop foreground service delegate");
        }
        final long callingId = Binder.clearCallingIdentity();
        try {
            boolean foundPid = false;
            synchronized (this) {
                ArrayList<ForegroundServiceDelegationOptions> delegates = new ArrayList<>();
                synchronized (mPidsSelfLocked) {
                    for (int i = 0; i < mPidsSelfLocked.size(); i++) {
                        final ProcessRecord p = mPidsSelfLocked.valueAt(i);
                        final IApplicationThread thread = p.getThread();
                        if (p.uid == uid && thread != null) {
                            foundPid = true;
                            int pid = mPidsSelfLocked.keyAt(i);
                            ForegroundServiceDelegationOptions options =
                                    new ForegroundServiceDelegationOptions(pid, uid, packageName,
                                            null /* clientAppThread */,
                                            false /* isSticky */,
                                            clientInstanceName, 0 /* foregroundServiceType */,
                                            delegateService);
                            delegates.add(options);
                        }
                    }
                }
                for (int i = delegates.size() - 1; i >= 0; i--) {
                    final ForegroundServiceDelegationOptions options = delegates.get(i);
                    if (isStart) {
                        mInternal.startForegroundServiceDelegate(options,
                                null /* connection */);
                    } else {
                        mInternal.stopForegroundServiceDelegate(options);
                    }
                }
            }
            if (!foundPid) {
                Slog.e(TAG, "setForegroundServiceDelegate can not find process for packageName:"
                        + packageName + " uid:" + uid);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    /**
     * Force the settings cache to be loaded
     */
    void refreshSettingsCache() {
        mCoreSettingsObserver.onChange(true);
    }

    /**
     * Reset the dropbox rate limiter here and in BootReceiver
     */
    void resetDropboxRateLimiter() {
        mDropboxRateLimiter.reset();
        BootReceiver.resetDropboxRateLimiter();
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

        final long callingId = Binder.clearCallingIdentity();
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
                synchronized (mProcLock) {
                    mProcessList.killPackageProcessesLSP(packageName, UserHandle.getAppId(pkgUid),
                            userId, ProcessList.FOREGROUND_APP_ADJ,
                            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
                            ApplicationExitInfo.SUBREASON_UNKNOWN,
                            "dep: " + packageName);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public int restartUserInBackground(int userId, int userStartMode) {
        return mUserController.restartUser(userId, userStartMode);
    }

    @Override
    public void scheduleApplicationInfoChanged(List<String> packageNames, int userId) {
        enforceCallingPermission(android.Manifest.permission.CHANGE_CONFIGURATION,
                "scheduleApplicationInfoChanged()");

        final long origId = Binder.clearCallingIdentity();
        try {
            final boolean updateFrameworkRes = packageNames.contains("android");
            synchronized (mProcLock) {
                updateApplicationInfoLOSP(packageNames, updateFrameworkRes, userId);
            }

            AppWidgetManagerInternal widgets = LocalServices.getService(
                    AppWidgetManagerInternal.class);
            if (widgets != null) {
                widgets.applyResourceOverlaysToWidgets(new HashSet<>(packageNames), userId,
                        updateFrameworkRes);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Synchronously update the system ActivityThread, bypassing any deferred threading so any
     * resources and overlaid values are available immediately.
     */
    public void updateSystemUiContext() {
        final PackageManagerInternal packageManagerInternal = getPackageManagerInternal();

        ApplicationInfo ai = packageManagerInternal.getApplicationInfo("android",
                GET_SHARED_LIBRARY_FILES, Binder.getCallingUid(), UserHandle.USER_SYSTEM);
        ActivityThread.currentActivityThread().handleSystemApplicationInfoChanged(ai);
    }

    @GuardedBy(anyOf = {"this", "mProcLock"})
    private void updateApplicationInfoLOSP(@NonNull List<String> packagesToUpdate,
            boolean updateFrameworkRes, int userId) {
        if (updateFrameworkRes) {
            ParsingPackageUtils.readConfigUseRoundIcon(null);
        }

        mProcessList.updateApplicationInfoLOSP(packagesToUpdate, userId, updateFrameworkRes);

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
     * Update the binder call heavy hitter watcher per the new configuration
     */
    void scheduleUpdateBinderHeavyHitterWatcherConfig() {
        // There are two sets of configs: the default watcher and the auto sampler,
        // the default one takes precedence. System would kick off auto sampler when there is
        // an anomaly (i.e., consecutive ANRs), but it'll be stopped automatically after a while.
        mHandler.post(() -> {
            final boolean enabled;
            final int batchSize;
            final float threshold;
            final BinderCallHeavyHitterListener listener;
            synchronized (mProcLock) {
                if (mConstants.BINDER_HEAVY_HITTER_WATCHER_ENABLED) {
                    // Default watcher takes precedence, ignore the auto sampler.
                    mHandler.removeMessages(BINDER_HEAVYHITTER_AUTOSAMPLER_TIMEOUT_MSG);
                    // Set the watcher with the default watcher's config
                    enabled = true;
                    batchSize = mConstants.BINDER_HEAVY_HITTER_WATCHER_BATCHSIZE;
                    threshold = mConstants.BINDER_HEAVY_HITTER_WATCHER_THRESHOLD;
                    listener = (a, b, c, d) -> mHandler.post(
                            () -> handleBinderHeavyHitters(a, b, c, d));
                } else if (mHandler.hasMessages(BINDER_HEAVYHITTER_AUTOSAMPLER_TIMEOUT_MSG)) {
                    // There is an ongoing auto sampler session, update it
                    enabled = mConstants.BINDER_HEAVY_HITTER_AUTO_SAMPLER_ENABLED;
                    batchSize = mConstants.BINDER_HEAVY_HITTER_AUTO_SAMPLER_BATCHSIZE;
                    threshold = mConstants.BINDER_HEAVY_HITTER_AUTO_SAMPLER_THRESHOLD;
                    listener = (a, b, c, d) -> mHandler.post(
                            () -> handleBinderHeavyHitters(a, b, c, d));
                } else {
                    // Stop it
                    enabled = false;
                    batchSize = 0;
                    threshold = 0.0f;
                    listener = null;
                }
            }
            Binder.setHeavyHitterWatcherConfig(enabled, batchSize, threshold, listener);
        });
    }

    /**
     * Kick off the watcher to run for given timeout, it could be throttled however.
     */
    void scheduleBinderHeavyHitterAutoSampler() {
        mHandler.post(() -> {
            final int batchSize;
            final float threshold;
            final long now;
            synchronized (mProcLock) {
                if (!mConstants.BINDER_HEAVY_HITTER_AUTO_SAMPLER_ENABLED) {
                    // It's configured OFF
                    return;
                }
                if (mConstants.BINDER_HEAVY_HITTER_WATCHER_ENABLED) {
                    // If the default watcher is active already, don't start the auto sampler
                    return;
                }
                now = SystemClock.uptimeMillis();
                if (mLastBinderHeavyHitterAutoSamplerStart
                        + BINDER_HEAVY_HITTER_AUTO_SAMPLER_THROTTLE_MS > now) {
                    // Too frequent, throttle it
                    return;
                }
                batchSize = mConstants.BINDER_HEAVY_HITTER_AUTO_SAMPLER_BATCHSIZE;
                threshold = mConstants.BINDER_HEAVY_HITTER_AUTO_SAMPLER_THRESHOLD;
            }
            // No lock is needed because we are accessing these variables in handle thread only.
            mLastBinderHeavyHitterAutoSamplerStart = now;
            // Start the watcher with the auto sampler's config.
            Binder.setHeavyHitterWatcherConfig(true, batchSize, threshold,
                    (a, b, c, d) -> mHandler.post(() -> handleBinderHeavyHitters(a, b, c, d)));
            // Schedule to stop it after given timeout.
            mHandler.sendMessageDelayed(mHandler.obtainMessage(
                    BINDER_HEAVYHITTER_AUTOSAMPLER_TIMEOUT_MSG),
                    BINDER_HEAVY_HITTER_AUTO_SAMPLER_DURATION_MS);
        });
    }

    /**
     * Stop the binder heavy hitter auto sampler after given timeout.
     */
    private void handleBinderHeavyHitterAutoSamplerTimeOut() {
        synchronized (mProcLock) {
            if (mConstants.BINDER_HEAVY_HITTER_WATCHER_ENABLED) {
                // The default watcher is ON, don't bother to stop it.
                return;
            }
        }
        Binder.setHeavyHitterWatcherConfig(false, 0, 0.0f, null);
    }

    /**
     * Handle the heavy hitters
     */
    private void handleBinderHeavyHitters(@NonNull final List<HeavyHitterContainer> hitters,
            final int totalBinderCalls, final float threshold, final long timeSpan) {
        final int size = hitters.size();
        if (size == 0) {
            return;
        }
        // Simply log it for now
        final String pfmt = "%.1f%%";
        final BinderTransactionNameResolver resolver = new BinderTransactionNameResolver();
        final StringBuilder sb = new StringBuilder("Excessive incoming binder calls(>")
                .append(String.format(pfmt, threshold * 100))
                .append(',').append(totalBinderCalls)
                .append(',').append(timeSpan)
                .append("ms): ");
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            final HeavyHitterContainer container = hitters.get(i);
            sb.append('[').append(container.mUid)
                    .append(',').append(container.mClass.getName())
                    .append(',').append(resolver.getMethodName(container.mClass, container.mCode))
                    .append(',').append(container.mCode)
                    .append(',').append(String.format(pfmt, container.mFrequency * 100))
                    .append(']');
        }
        Slog.w(TAG, sb.toString());
    }

    /**
     * Attach an agent to the specified process (proces name or PID)
     */
    public void attachAgent(String process, String path) {
        try {
            synchronized (mProcLock) {
                ProcessRecord proc = findProcessLOSP(process, UserHandle.USER_SYSTEM,
                        "attachAgent");
                IApplicationThread thread;
                if (proc == null || (thread = proc.getThread()) == null) {
                    throw new IllegalArgumentException("Unknown process: " + process);
                }

                enforceDebuggable(proc);

                thread.attachAgent(path);
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
        if (mUsageStatsService != null) {
            mUsageStatsService.prepareForPossibleShutdown();
        }
    }

    @VisibleForTesting
    public static class Injector {
        private final Context mContext;
        private NetworkManagementInternal mNmi;

        private UserController mUserController;

        public Injector(Context context) {
            mContext = context;
        }

        public Context getContext() {
            return mContext;
        }

        public AppOpsService getAppOpsService(File recentAccessesFile, File storageFile,
                Handler handler) {
            return new AppOpsService(recentAccessesFile, storageFile, handler, getContext());
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

        /**
         * Called by {@code AMS.getDisplayIdsForStartingVisibleBackgroundUsers()}.
         */
        // NOTE: ideally Injector should have no complex logic, but if this logic was moved to AMS,
        // it could not be tested with the existing ActivityManagerServiceTest (as DisplayManager,
        // DisplayInfo, etc... are final and UserManager.isUsersOnSecondaryDisplaysEnabled is
        // static).
        // So, the logic was added here, and tested on ActivityManagerServiceInjectorTest (which
        // was added on FrameworksMockingServicesTests and hence uses Extended Mockito to mock
        // final and static stuff)
        @Nullable
        public int[] getDisplayIdsForStartingVisibleBackgroundUsers() {
            if (!UserManager.isVisibleBackgroundUsersEnabled()) {
                Slogf.w(TAG, "getDisplayIdsForStartingVisibleBackgroundUsers(): not supported");
                return null;
            }

            // NOTE: DisplayManagerInternal doesn't have a method to list all displays
            DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);

            Display[] allDisplays = displayManager.getDisplays();

            // allDisplays should contain at least Display.DEFAULT_DISPLAY, but it's better to
            // double check, just in case...
            if (allDisplays == null || allDisplays.length == 0) {
                Slogf.wtf(TAG, "displayManager (%s) returned no displays", displayManager);
                return null;
            }
            boolean hasDefaultDisplay = false;
            for (Display display : allDisplays) {
                if (display.getDisplayId() == Display.DEFAULT_DISPLAY) {
                    hasDefaultDisplay = true;
                    break;
                }
            }
            if (!hasDefaultDisplay) {
                Slogf.wtf(TAG, "displayManager (%s) has %d displays (%s), but none has id "
                        + "DEFAULT_DISPLAY (%d)", displayManager, allDisplays.length,
                        Arrays.toString(allDisplays), Display.DEFAULT_DISPLAY);
                return null;
            }

            boolean allowOnDefaultDisplay = UserManager
                    .isVisibleBackgroundUsersOnDefaultDisplayEnabled();
            int displaysSize = allDisplays.length;
            if (!allowOnDefaultDisplay) {
                displaysSize--;
            }
            int[] displayIds = new int[displaysSize];

            int numberValidDisplays = 0;
            for (Display display : allDisplays) {
                int displayId = display.getDisplayId();
                // TODO(b/247592632): check other properties like isSecure or proper display type
                if (display.isValid() && ((display.getFlags() & Display.FLAG_PRIVATE) == 0)
                        && (allowOnDefaultDisplay || displayId != Display.DEFAULT_DISPLAY)) {
                    displayIds[numberValidDisplays++] = displayId;
                }
            }

            if (numberValidDisplays == 0) {
                // TODO(b/247580038): remove this workaround once a virtual display on Car's
                // KitchenSink (or other app) can be used while running CTS tests on devices that
                // don't have a real display.
                // STOPSHIP: if not removed, it should at least be unit tested
                String testingProp = "fw.display_ids_for_starting_users_for_testing_purposes";
                int displayId = SystemProperties.getInt(testingProp, Display.DEFAULT_DISPLAY);
                if (allowOnDefaultDisplay && displayId == Display.DEFAULT_DISPLAY
                        || displayId > 0) {
                    Slogf.w(TAG, "getDisplayIdsForStartingVisibleBackgroundUsers(): no valid "
                            + "display found, but returning %d as set by property %s", displayId,
                            testingProp);
                    return new int[] { displayId };
                }
                Slogf.e(TAG, "getDisplayIdsForStartingVisibleBackgroundUsers(): no valid display"
                        + " on %s", Arrays.toString(allDisplays));
                return null;
            }

            if (numberValidDisplays != displayIds.length) {
                int[] validDisplayIds = new int[numberValidDisplays];
                System.arraycopy(displayIds, 0, validDisplayIds, 0, numberValidDisplays);
                if (DEBUG_MU) {
                    Slogf.d(TAG, "getDisplayIdsForStartingVisibleBackgroundUsers(): returning "
                            + "only valid displays (%d instead of %d): %s", numberValidDisplays,
                            displayIds.length, Arrays.toString(validDisplayIds));
                }
                return validDisplayIds;
            }

            if (DEBUG_MU) {
                Slogf.d(TAG, "getDisplayIdsForStartingVisibleBackgroundUsers(): returning all (but "
                        + "DEFAULT_DISPLAY) displays : %s", Arrays.toString(displayIds));
            }
            return displayIds;
        }

        /**
         * Called by {@code AMS.startUserInBackgroundVisibleOnDisplay()}.
         */
        public boolean startUserInBackgroundVisibleOnDisplay(int userId, int displayId,
                @Nullable IProgressListener unlockProgressListener) {
            return mUserController.startUserVisibleOnDisplay(userId, displayId,
                    unlockProgressListener);
        }

        /**
         * Return the process list instance
         */
        public ProcessList getProcessList(ActivityManagerService service) {
            return new ProcessList();
        }

        /**
         * Returns the {@link BatteryStatsService} instance
         */
        public BatteryStatsService getBatteryStatsService() {
            return new BatteryStatsService(mContext, SystemServiceManager.ensureSystemDir());
        }

        /**
         * Returns the {@link ActiveServices} instance
         */
        public ActiveServices getActiveServices(ActivityManagerService service) {
            return new ActiveServices(service);
        }

        private boolean ensureHasNetworkManagementInternal() {
            if (mNmi == null) {
                mNmi = LocalServices.getService(NetworkManagementInternal.class);
            }
            return mNmi != null;
        }

        public BroadcastQueue getBroadcastQueue(ActivityManagerService service) {
            // Broadcast policy parameters
            final BroadcastConstants foreConstants = new BroadcastConstants(
                    Settings.Global.BROADCAST_FG_CONSTANTS);
            foreConstants.TIMEOUT = BROADCAST_FG_TIMEOUT;

            final BroadcastConstants backConstants = new BroadcastConstants(
                    Settings.Global.BROADCAST_BG_CONSTANTS);
            backConstants.TIMEOUT = BROADCAST_BG_TIMEOUT;

            return new BroadcastQueueModernImpl(service, service.mHandler,
                        foreConstants, backConstants);
        }

        /** @see Binder#getCallingUid */
        public int getCallingUid() {
            return Binder.getCallingUid();
        }

        /** @see Binder#getCallingPid */
        public int getCallingPid() {
            return Binder.getCallingPid();
        }

        /** @see Binder#clearCallingIdentity */
        public long clearCallingIdentity() {
            return Binder.clearCallingIdentity();
        }

        /** @see Binder#clearCallingIdentity */
        public void restoreCallingIdentity(long ident) {
            Binder.restoreCallingIdentity(ident);
        }

        /** @return the default instance of AppErrors */
        public AppErrors getAppErrors() {
            return null;
        }

        /** @return the default instance of intent firewall */
        public IntentFirewall getIntentFirewall() {
            return null;
        }
    }

    @Override
    public void startDelegateShellPermissionIdentity(int delegateUid,
            @Nullable String[] permissions) {
        getAccessCheckDelegateHelper()
                .startDelegateShellPermissionIdentity(delegateUid, permissions);
    }

    @Override
    public void stopDelegateShellPermissionIdentity() {
        getAccessCheckDelegateHelper().stopDelegateShellPermissionIdentity();
    }

    @Override
    public List<String> getDelegatedShellPermissions() {
        return getAccessCheckDelegateHelper().getDelegatedShellPermissions();
    }

    @Override
    public void addOverridePermissionState(int originatingUid, int uid, String permission,
            int result) {
        getAccessCheckDelegateHelper()
                .addOverridePermissionState(originatingUid, uid, permission, result);
    }

    @Override
    public void removeOverridePermissionState(int originatingUid, int uid, String permission) {
        getAccessCheckDelegateHelper()
                .removeOverridePermissionState(originatingUid, uid, permission);
    }

    @Override
    public void clearOverridePermissionStates(int originatingUid, int uid) {
        getAccessCheckDelegateHelper().clearOverridePermissionStates(originatingUid, uid);
    }

    @Override
    public void clearAllOverridePermissionStates(int originatingUid) {
        getAccessCheckDelegateHelper().clearAllOverridePermissionStates(originatingUid);
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

    @Override
    public ParcelFileDescriptor getLifeMonitor() {
        if (!isCallerShell()) {
            throw new SecurityException("Only shell can call it");
        }
        synchronized (mProcLock) {
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

    @Override
    public void setActivityLocusContext(ComponentName activity, LocusId locusId, IBinder appToken) {
        final int callingUid = Binder.getCallingUid();
        final int userId = UserHandle.getCallingUserId();
        if (getPackageManagerInternal().getPackageUid(activity.getPackageName(),
                /*flags=*/ 0, userId) != callingUid) {
            throw new SecurityException("Calling uid " + callingUid + " cannot set locusId"
                    + "for package " + activity.getPackageName());
        }
        mActivityTaskManager.setLocusId(locusId, appToken);
        if (mUsageStatsService != null) {
            mUsageStatsService.reportLocusUpdate(activity, userId, locusId, appToken);
        }
    }

    @Override
    public boolean isAppFreezerSupported() {
        final long token = Binder.clearCallingIdentity();

        try {
            return CachedAppOptimizer.isFreezerSupported();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean isAppFreezerEnabled() {
        return mOomAdjuster.mCachedAppOptimizer.useFreezer();
    }

    public boolean isAppFreezerExemptInstPkg() {
        return mOomAdjuster.mCachedAppOptimizer.freezerExemptInstPkg();
    }

    /**
     * Resets the state of the {@link com.android.server.am.AppErrors} instance.
     * This is intended for testing within the CTS only and is protected by
     * android.permission.RESET_APP_ERRORS.
     */
    @Override
    public void resetAppErrors() {
        enforceCallingPermission(Manifest.permission.RESET_APP_ERRORS, "resetAppErrors");
        mAppErrors.resetState();
    }

    @Override
    public boolean enableAppFreezer(boolean enable) {
        int callerUid = Binder.getCallingUid();

        // Only system can toggle the freezer state
        if (callerUid == SYSTEM_UID || Build.IS_DEBUGGABLE) {
            return mOomAdjuster.mCachedAppOptimizer.enableFreezer(enable);
        } else {
            throw new SecurityException("Caller uid " + callerUid + " cannot set freezer state ");
        }
    }

    /**
     * Suppress or reenable the rate limit on foreground service notification deferral.
     * @param enable false to suppress rate-limit policy; true to reenable it.
     */
    @Override
    public boolean enableFgsNotificationRateLimit(boolean enable) {
        enforceCallingPermission(permission.WRITE_DEVICE_CONFIG,
                "enableFgsNotificationRateLimit");
        synchronized (this) {
            return mServices.enableFgsNotificationRateLimitLocked(enable);
        }
    }

    /**
     * Holds the AM lock for the specified amount of milliseconds.
     * Intended for use by the tests that need to imitate lock contention.
     * The token should be obtained by
     * {@link android.content.pm.PackageManager#getHoldLockToken()}.
     */
    @Override
    public void holdLock(IBinder token, int durationMs) {
        getTestUtilityServiceLocked().verifyHoldLockToken(token);

        synchronized (this) {
            SystemClock.sleep(durationMs);
        }
    }

    static void traceBegin(long traceTag, String methodName, String subInfo) {
        if (Trace.isTagEnabled(traceTag)) {
            Trace.traceBegin(traceTag, methodName + subInfo);
        }
    }

    /**
     * Gets an {@code int} argument from the given {@code index} on {@code args}, logging an error
     * message on {@code pw} when it cannot be parsed.
     *
     * Returns {@code int} argument or {@code invalidValue} if it could not be parsed.
     */
    private static int getIntArg(PrintWriter pw, String[] args, int index, int invalidValue) {
        if (index > args.length) {
            pw.println("Missing argument");
            return invalidValue;
        }
        String arg = args[index];
        try {
            return Integer.parseInt(arg);
        } catch (Exception e) {
            pw.printf("Non-numeric argument at index %d: %s\n", index, arg);
            return invalidValue;
        }
    }

    private void notifyMediaProjectionEvent(int uid, @NonNull IBinder projectionToken,
            @MediaProjectionTokenEvent int event) {
        synchronized (mMediaProjectionTokenMap) {
            final int index = mMediaProjectionTokenMap.indexOfKey(uid);
            ArraySet<IBinder> tokens;
            if (event == MEDIA_PROJECTION_TOKEN_EVENT_CREATED) {
                if (index < 0) {
                    tokens = new ArraySet();
                    mMediaProjectionTokenMap.put(uid, tokens);
                } else {
                    tokens = mMediaProjectionTokenMap.valueAt(index);
                }
                tokens.add(projectionToken);
            } else if (event == MEDIA_PROJECTION_TOKEN_EVENT_DESTROYED && index >= 0) {
                tokens = mMediaProjectionTokenMap.valueAt(index);
                tokens.remove(projectionToken);
                if (tokens.isEmpty()) {
                    mMediaProjectionTokenMap.removeAt(index);
                }
            }
        }
    }

    /**
     * @return {@code true} if the MediaProjectionManagerService has created a media projection
     *         for the given {@code uid} because the user has granted the permission;
     *         it doesn't necessarily mean it has started the projection.
     *
     * <p>It doesn't check the {@link AppOpsManager#OP_PROJECT_MEDIA}.</p>
     */
    boolean isAllowedMediaProjectionNoOpCheck(int uid) {
        synchronized (mMediaProjectionTokenMap) {
            final int index = mMediaProjectionTokenMap.indexOfKey(uid);
            return index >= 0 && !mMediaProjectionTokenMap.valueAt(index).isEmpty();
        }
    }

    /**
     * Deal with binder transactions to frozen apps.
     *
     * @param debugPid The binder transaction sender
     * @param code The binder transaction code
     * @param flags The binder transaction flags
     * @param err The binder transaction error
     */
    @Override
    public void frozenBinderTransactionDetected(int debugPid, int code, int flags, int err) {
        final ProcessRecord app;
        synchronized (mPidsSelfLocked) {
            app = mPidsSelfLocked.get(debugPid);
        }
        mOomAdjuster.mCachedAppOptimizer.binderError(debugPid, app, code, flags, err);
    }

    @GuardedBy("this")
    void enqueuePendingTopAppIfNecessaryLocked() {
        mPendingStartActivityUids.enqueuePendingTopAppIfNecessaryLocked(this);
    }

    @GuardedBy("this")
    void clearPendingTopAppLocked() {
        mPendingStartActivityUids.clear();
    }
}
