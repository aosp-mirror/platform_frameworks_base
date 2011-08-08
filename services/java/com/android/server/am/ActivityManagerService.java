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

import com.android.internal.R;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.ProcessStats;
import com.android.server.AttributeCache;
import com.android.server.IntentResolver;
import com.android.server.ProcessMap;
import com.android.server.SystemServer;
import com.android.server.Watchdog;
import com.android.server.am.ActivityStack.ActivityState;
import com.android.server.wm.WindowManagerService;

import dalvik.system.Zygote;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.ActivityThread;
import android.app.AlertDialog;
import android.app.AppGlobals;
import android.app.ApplicationErrorReport;
import android.app.Dialog;
import android.app.IActivityController;
import android.app.IActivityWatcher;
import android.app.IApplicationThread;
import android.app.IInstrumentationWatcher;
import android.app.INotificationManager;
import android.app.IProcessObserver;
import android.app.IServiceConnection;
import android.app.IThumbnailReceiver;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.backup.IBackupManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageManager;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PathPermission;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Proxy;
import android.net.ProxyProperties;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.DropBoxManager;
import android.os.Environment;
import android.os.FileObserver;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPermissionController;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Slog;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.IllegalStateException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ActivityManagerService extends ActivityManagerNative
        implements Watchdog.Monitor, BatteryStatsImpl.BatteryCallback {
    static final String TAG = "ActivityManager";
    static final boolean DEBUG = false;
    static final boolean localLOGV = DEBUG;
    static final boolean DEBUG_SWITCH = localLOGV || false;
    static final boolean DEBUG_TASKS = localLOGV || false;
    static final boolean DEBUG_PAUSE = localLOGV || false;
    static final boolean DEBUG_OOM_ADJ = localLOGV || false;
    static final boolean DEBUG_TRANSITION = localLOGV || false;
    static final boolean DEBUG_BROADCAST = localLOGV || false;
    static final boolean DEBUG_BROADCAST_LIGHT = DEBUG_BROADCAST || false;
    static final boolean DEBUG_SERVICE = localLOGV || false;
    static final boolean DEBUG_SERVICE_EXECUTING = localLOGV || false;
    static final boolean DEBUG_VISBILITY = localLOGV || false;
    static final boolean DEBUG_PROCESSES = localLOGV || false;
    static final boolean DEBUG_PROVIDER = localLOGV || false;
    static final boolean DEBUG_URI_PERMISSION = localLOGV || false;
    static final boolean DEBUG_USER_LEAVING = localLOGV || false;
    static final boolean DEBUG_RESULTS = localLOGV || false;
    static final boolean DEBUG_BACKUP = localLOGV || true;
    static final boolean DEBUG_CONFIGURATION = localLOGV || false;
    static final boolean DEBUG_POWER = localLOGV || false;
    static final boolean DEBUG_POWER_QUICK = DEBUG_POWER || false;
    static final boolean VALIDATE_TOKENS = false;
    static final boolean SHOW_ACTIVITY_START_TIME = true;
    
    // Control over CPU and battery monitoring.
    static final long BATTERY_STATS_TIME = 30*60*1000;      // write battery stats every 30 minutes.
    static final boolean MONITOR_CPU_USAGE = true;
    static final long MONITOR_CPU_MIN_TIME = 5*1000;        // don't sample cpu less than every 5 seconds.
    static final long MONITOR_CPU_MAX_TIME = 0x0fffffff;    // wait possibly forever for next cpu sample.
    static final boolean MONITOR_THREAD_CPU_USAGE = false;

    // The flags that are set for all calls we make to the package manager.
    static final int STOCK_PM_FLAGS = PackageManager.GET_SHARED_LIBRARY_FILES;
    
    private static final String SYSTEM_DEBUGGABLE = "ro.debuggable";

    // Maximum number of recent tasks that we can remember.
    static final int MAX_RECENT_TASKS = 20;
    
    // Amount of time after a call to stopAppSwitches() during which we will
    // prevent further untrusted switches from happening.
    static final long APP_SWITCH_DELAY_TIME = 5*1000;

    // How long we wait for a launched process to attach to the activity manager
    // before we decide it's never going to come up for real.
    static final int PROC_START_TIMEOUT = 10*1000;

    // How long we wait for a launched process to attach to the activity manager
    // before we decide it's never going to come up for real, when the process was
    // started with a wrapper for instrumentation (such as Valgrind) because it
    // could take much longer than usual.
    static final int PROC_START_TIMEOUT_WITH_WRAPPER = 300*1000;

    // How long to wait after going idle before forcing apps to GC.
    static final int GC_TIMEOUT = 5*1000;

    // The minimum amount of time between successive GC requests for a process.
    static final int GC_MIN_INTERVAL = 60*1000;

    // The rate at which we check for apps using excessive power -- 15 mins.
    static final int POWER_CHECK_DELAY = (DEBUG_POWER_QUICK ? 2 : 15) * 60*1000;

    // The minimum sample duration we will allow before deciding we have
    // enough data on wake locks to start killing things.
    static final int WAKE_LOCK_MIN_CHECK_DURATION = (DEBUG_POWER_QUICK ? 1 : 5) * 60*1000;

    // The minimum sample duration we will allow before deciding we have
    // enough data on CPU usage to start killing things.
    static final int CPU_MIN_CHECK_DURATION = (DEBUG_POWER_QUICK ? 1 : 5) * 60*1000;

    // How long we allow a receiver to run before giving up on it.
    static final int BROADCAST_TIMEOUT = 10*1000;

    // How long we wait for a service to finish executing.
    static final int SERVICE_TIMEOUT = 20*1000;

    // How long a service needs to be running until restarting its process
    // is no longer considered to be a relaunch of the service.
    static final int SERVICE_RESTART_DURATION = 5*1000;

    // How long a service needs to be running until it will start back at
    // SERVICE_RESTART_DURATION after being killed.
    static final int SERVICE_RESET_RUN_DURATION = 60*1000;

    // Multiplying factor to increase restart duration time by, for each time
    // a service is killed before it has run for SERVICE_RESET_RUN_DURATION.
    static final int SERVICE_RESTART_DURATION_FACTOR = 4;
    
    // The minimum amount of time between restarting services that we allow.
    // That is, when multiple services are restarting, we won't allow each
    // to restart less than this amount of time from the last one.
    static final int SERVICE_MIN_RESTART_TIME_BETWEEN = 10*1000;

    // Maximum amount of time for there to be no activity on a service before
    // we consider it non-essential and allow its process to go on the
    // LRU background list.
    static final int MAX_SERVICE_INACTIVITY = 30*60*1000;
    
    // How long we wait until we timeout on key dispatching.
    static final int KEY_DISPATCHING_TIMEOUT = 5*1000;

    // How long we wait until we timeout on key dispatching during instrumentation.
    static final int INSTRUMENTATION_KEY_DISPATCHING_TIMEOUT = 60*1000;

    static final int MY_PID = Process.myPid();
    
    static final String[] EMPTY_STRING_ARRAY = new String[0];

    public ActivityStack mMainStack;
    
    /**
     * Description of a request to start a new activity, which has been held
     * due to app switches being disabled.
     */
    static class PendingActivityLaunch {
        ActivityRecord r;
        ActivityRecord sourceRecord;
        Uri[] grantedUriPermissions;
        int grantedMode;
        boolean onlyIfNeeded;
    }
    
    final ArrayList<PendingActivityLaunch> mPendingActivityLaunches
            = new ArrayList<PendingActivityLaunch>();
    
    /**
     * List of all active broadcasts that are to be executed immediately
     * (without waiting for another broadcast to finish).  Currently this only
     * contains broadcasts to registered receivers, to avoid spinning up
     * a bunch of processes to execute IntentReceiver components.
     */
    final ArrayList<BroadcastRecord> mParallelBroadcasts
            = new ArrayList<BroadcastRecord>();

    /**
     * List of all active broadcasts that are to be executed one at a time.
     * The object at the top of the list is the currently activity broadcasts;
     * those after it are waiting for the top to finish..
     */
    final ArrayList<BroadcastRecord> mOrderedBroadcasts
            = new ArrayList<BroadcastRecord>();

    /**
     * Historical data of past broadcasts, for debugging.
     */
    static final int MAX_BROADCAST_HISTORY = 100;
    final BroadcastRecord[] mBroadcastHistory
            = new BroadcastRecord[MAX_BROADCAST_HISTORY];

    /**
     * Set when we current have a BROADCAST_INTENT_MSG in flight.
     */
    boolean mBroadcastsScheduled = false;

    /**
     * Activity we have told the window manager to have key focus.
     */
    ActivityRecord mFocusedActivity = null;
    /**
     * List of intents that were used to start the most recent tasks.
     */
    final ArrayList<TaskRecord> mRecentTasks = new ArrayList<TaskRecord>();

    /**
     * Process management.
     */
    final ProcessList mProcessList = new ProcessList();

    /**
     * All of the applications we currently have running organized by name.
     * The keys are strings of the application package name (as
     * returned by the package manager), and the keys are ApplicationRecord
     * objects.
     */
    final ProcessMap<ProcessRecord> mProcessNames = new ProcessMap<ProcessRecord>();

    /**
     * The currently running heavy-weight process, if any.
     */
    ProcessRecord mHeavyWeightProcess = null;
    
    /**
     * The last time that various processes have crashed.
     */
    final ProcessMap<Long> mProcessCrashTimes = new ProcessMap<Long>();

    /**
     * Set of applications that we consider to be bad, and will reject
     * incoming broadcasts from (which the user has no control over).
     * Processes are added to this set when they have crashed twice within
     * a minimum amount of time; they are removed from it when they are
     * later restarted (hopefully due to some user action).  The value is the
     * time it was added to the list.
     */
    final ProcessMap<Long> mBadProcesses = new ProcessMap<Long>();

    /**
     * All of the processes we currently have running organized by pid.
     * The keys are the pid running the application.
     *
     * <p>NOTE: This object is protected by its own lock, NOT the global
     * activity manager lock!
     */
    final SparseArray<ProcessRecord> mPidsSelfLocked = new SparseArray<ProcessRecord>();

    /**
     * All of the processes that have been forced to be foreground.  The key
     * is the pid of the caller who requested it (we hold a death
     * link on it).
     */
    abstract class ForegroundToken implements IBinder.DeathRecipient {
        int pid;
        IBinder token;
    }
    final SparseArray<ForegroundToken> mForegroundProcesses
            = new SparseArray<ForegroundToken>();
    
    /**
     * List of records for processes that someone had tried to start before the
     * system was ready.  We don't start them at that point, but ensure they
     * are started by the time booting is complete.
     */
    final ArrayList<ProcessRecord> mProcessesOnHold
            = new ArrayList<ProcessRecord>();

    /**
     * List of persistent applications that are in the process
     * of being started.
     */
    final ArrayList<ProcessRecord> mPersistentStartingProcesses
            = new ArrayList<ProcessRecord>();

    /**
     * Processes that are being forcibly torn down.
     */
    final ArrayList<ProcessRecord> mRemovedProcesses
            = new ArrayList<ProcessRecord>();

    /**
     * List of running applications, sorted by recent usage.
     * The first entry in the list is the least recently used.
     * It contains ApplicationRecord objects.  This list does NOT include
     * any persistent application records (since we never want to exit them).
     */
    final ArrayList<ProcessRecord> mLruProcesses
            = new ArrayList<ProcessRecord>();

    /**
     * List of processes that should gc as soon as things are idle.
     */
    final ArrayList<ProcessRecord> mProcessesToGc
            = new ArrayList<ProcessRecord>();

    /**
     * This is the process holding what we currently consider to be
     * the "home" activity.
     */
    ProcessRecord mHomeProcess;
    
    /**
     * Packages that the user has asked to have run in screen size
     * compatibility mode instead of filling the screen.
     */
    final CompatModePackages mCompatModePackages;

    /**
     * Set of PendingResultRecord objects that are currently active.
     */
    final HashSet mPendingResultRecords = new HashSet();

    /**
     * Set of IntentSenderRecord objects that are currently active.
     */
    final HashMap<PendingIntentRecord.Key, WeakReference<PendingIntentRecord>> mIntentSenderRecords
            = new HashMap<PendingIntentRecord.Key, WeakReference<PendingIntentRecord>>();

    /**
     * Fingerprints (hashCode()) of stack traces that we've
     * already logged DropBox entries for.  Guarded by itself.  If
     * something (rogue user app) forces this over
     * MAX_DUP_SUPPRESSED_STACKS entries, the contents are cleared.
     */
    private final HashSet<Integer> mAlreadyLoggedViolatedStacks = new HashSet<Integer>();
    private static final int MAX_DUP_SUPPRESSED_STACKS = 5000;

    /**
     * Strict Mode background batched logging state.
     *
     * The string buffer is guarded by itself, and its lock is also
     * used to determine if another batched write is already
     * in-flight.
     */
    private final StringBuilder mStrictModeBuffer = new StringBuilder();

    /**
     * True if we have a pending unexpired BROADCAST_TIMEOUT_MSG posted to our handler.
     */
    private boolean mPendingBroadcastTimeoutMessage;

    /**
     * Intent broadcast that we have tried to start, but are
     * waiting for its application's process to be created.  We only
     * need one (instead of a list) because we always process broadcasts
     * one at a time, so no others can be started while waiting for this
     * one.
     */
    BroadcastRecord mPendingBroadcast = null;

    /**
     * The receiver index that is pending, to restart the broadcast if needed.
     */
    int mPendingBroadcastRecvIndex;

    /**
     * Keeps track of all IIntentReceivers that have been registered for
     * broadcasts.  Hash keys are the receiver IBinder, hash value is
     * a ReceiverList.
     */
    final HashMap mRegisteredReceivers = new HashMap();

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
            for (int i=dest.size()-1; i>=0; i--) {
                if (dest.get(i).receiverList.receiver.asBinder() == target) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected String packageForFilter(BroadcastFilter filter) {
            return filter.packageName;
        }
    };

    /**
     * State of all active sticky broadcasts.  Keys are the action of the
     * sticky Intent, values are an ArrayList of all broadcasted intents with
     * that action (which should usually be one).
     */
    final HashMap<String, ArrayList<Intent>> mStickyBroadcasts =
            new HashMap<String, ArrayList<Intent>>();

    /**
     * All currently running services.
     */
    final HashMap<ComponentName, ServiceRecord> mServices =
        new HashMap<ComponentName, ServiceRecord>();

    /**
     * All currently running services indexed by the Intent used to start them.
     */
    final HashMap<Intent.FilterComparison, ServiceRecord> mServicesByIntent =
        new HashMap<Intent.FilterComparison, ServiceRecord>();

    /**
     * All currently bound service connections.  Keys are the IBinder of
     * the client's IServiceConnection.
     */
    final HashMap<IBinder, ArrayList<ConnectionRecord>> mServiceConnections
            = new HashMap<IBinder, ArrayList<ConnectionRecord>>();

    /**
     * List of services that we have been asked to start,
     * but haven't yet been able to.  It is used to hold start requests
     * while waiting for their corresponding application thread to get
     * going.
     */
    final ArrayList<ServiceRecord> mPendingServices
            = new ArrayList<ServiceRecord>();

    /**
     * List of services that are scheduled to restart following a crash.
     */
    final ArrayList<ServiceRecord> mRestartingServices
            = new ArrayList<ServiceRecord>();

    /**
     * List of services that are in the process of being stopped.
     */
    final ArrayList<ServiceRecord> mStoppingServices
            = new ArrayList<ServiceRecord>();

    /**
     * Backup/restore process management
     */
    String mBackupAppName = null;
    BackupRecord mBackupTarget = null;

    /**
     * List of PendingThumbnailsRecord objects of clients who are still
     * waiting to receive all of the thumbnails for a task.
     */
    final ArrayList mPendingThumbnails = new ArrayList();

    /**
     * List of HistoryRecord objects that have been finished and must
     * still report back to a pending thumbnail receiver.
     */
    final ArrayList mCancelledThumbnails = new ArrayList();

    /**
     * All of the currently running global content providers.  Keys are a
     * string containing the provider name and values are a
     * ContentProviderRecord object containing the data about it.  Note
     * that a single provider may be published under multiple names, so
     * there may be multiple entries here for a single one in mProvidersByClass.
     */
    final HashMap<String, ContentProviderRecord> mProvidersByName
            = new HashMap<String, ContentProviderRecord>();

    /**
     * All of the currently running global content providers.  Keys are a
     * string containing the provider's implementation class and values are a
     * ContentProviderRecord object containing the data about it.
     */
    final HashMap<String, ContentProviderRecord> mProvidersByClass
            = new HashMap<String, ContentProviderRecord>();

    /**
     * List of content providers who have clients waiting for them.  The
     * application is currently being launched and the provider will be
     * removed from this list once it is published.
     */
    final ArrayList<ContentProviderRecord> mLaunchingProviders
            = new ArrayList<ContentProviderRecord>();

    /**
     * Global set of specific Uri permissions that have been granted.
     */
    final private SparseArray<HashMap<Uri, UriPermission>> mGrantedUriPermissions
            = new SparseArray<HashMap<Uri, UriPermission>>();

    CoreSettingsObserver mCoreSettingsObserver;

    /**
     * Thread-local storage used to carry caller permissions over through
     * indirect content-provider access.
     * @see #ActivityManagerService.openContentUri()
     */
    private class Identity {
        public int pid;
        public int uid;

        Identity(int _pid, int _uid) {
            pid = _pid;
            uid = _uid;
        }
    }
    private static ThreadLocal<Identity> sCallerIdentity = new ThreadLocal<Identity>();

    /**
     * All information we have collected about the runtime performance of
     * any user id that can impact battery performance.
     */
    final BatteryStatsService mBatteryStatsService;
    
    /**
     * information about component usage
     */
    final UsageStatsService mUsageStatsService;

    /**
     * Current configuration information.  HistoryRecord objects are given
     * a reference to this object to indicate which configuration they are
     * currently running in, so this object must be kept immutable.
     */
    Configuration mConfiguration = new Configuration();

    /**
     * Current sequencing integer of the configuration, for skipping old
     * configurations.
     */
    int mConfigurationSeq = 0;
    
    /**
     * Hardware-reported OpenGLES version.
     */
    final int GL_ES_VERSION;

    /**
     * List of initialization arguments to pass to all processes when binding applications to them.
     * For example, references to the commonly used services.
     */
    HashMap<String, IBinder> mAppBindArgs;

    /**
     * Temporary to avoid allocations.  Protected by main lock.
     */
    final StringBuilder mStringBuilder = new StringBuilder(256);
    
    /**
     * Used to control how we initialize the service.
     */
    boolean mStartRunning = false;
    ComponentName mTopComponent;
    String mTopAction;
    String mTopData;
    boolean mProcessesReady = false;
    boolean mSystemReady = false;
    boolean mBooting = false;
    boolean mWaitingUpdate = false;
    boolean mDidUpdate = false;
    boolean mOnBattery = false;
    boolean mLaunchWarningShown = false;

    Context mContext;

    int mFactoryTest;

    boolean mCheckedForSetup;
    
    /**
     * The time at which we will allow normal application switches again,
     * after a call to {@link #stopAppSwitches()}.
     */
    long mAppSwitchesAllowedTime;

    /**
     * This is set to true after the first switch after mAppSwitchesAllowedTime
     * is set; any switches after that will clear the time.
     */
    boolean mDidAppSwitch;
    
    /**
     * Last time (in realtime) at which we checked for power usage.
     */
    long mLastPowerCheckRealtime;

    /**
     * Last time (in uptime) at which we checked for power usage.
     */
    long mLastPowerCheckUptime;

    /**
     * Set while we are wanting to sleep, to prevent any
     * activities from being started/resumed.
     */
    boolean mSleeping = false;

    /**
     * Set if we are shutting down the system, similar to sleeping.
     */
    boolean mShuttingDown = false;

    /**
     * Task identifier that activities are currently being started
     * in.  Incremented each time a new task is created.
     * todo: Replace this with a TokenSpace class that generates non-repeating
     * integers that won't wrap.
     */
    int mCurTask = 1;

    /**
     * Current sequence id for oom_adj computation traversal.
     */
    int mAdjSeq = 0;

    /**
     * Current sequence id for process LRU updating.
     */
    int mLruSeq = 0;

    /**
     * System monitoring: number of processes that died since the last
     * N procs were started.
     */
    int[] mProcDeaths = new int[20];
    
    /**
     * This is set if we had to do a delayed dexopt of an app before launching
     * it, to increasing the ANR timeouts in that case.
     */
    boolean mDidDexOpt;
    
    String mDebugApp = null;
    boolean mWaitForDebugger = false;
    boolean mDebugTransient = false;
    String mOrigDebugApp = null;
    boolean mOrigWaitForDebugger = false;
    boolean mAlwaysFinishActivities = false;
    IActivityController mController = null;

    final RemoteCallbackList<IActivityWatcher> mWatchers
            = new RemoteCallbackList<IActivityWatcher>();

    final RemoteCallbackList<IProcessObserver> mProcessObservers
            = new RemoteCallbackList<IProcessObserver>();

    /**
     * Callback of last caller to {@link #requestPss}.
     */
    Runnable mRequestPssCallback;

    /**
     * Remaining processes for which we are waiting results from the last
     * call to {@link #requestPss}.
     */
    final ArrayList<ProcessRecord> mRequestPssList
            = new ArrayList<ProcessRecord>();
    
    /**
     * Runtime statistics collection thread.  This object's lock is used to
     * protect all related state.
     */
    final Thread mProcessStatsThread;
    
    /**
     * Used to collect process stats when showing not responding dialog.
     * Protected by mProcessStatsThread.
     */
    final ProcessStats mProcessStats = new ProcessStats(
            MONITOR_THREAD_CPU_USAGE);
    final AtomicLong mLastCpuTime = new AtomicLong(0);
    final AtomicBoolean mProcessStatsMutexFree = new AtomicBoolean(true);

    long mLastWriteTime = 0;

    /**
     * Set to true after the system has finished booting.
     */
    boolean mBooted = false;

    int mProcessLimit = ProcessList.MAX_HIDDEN_APPS;
    int mProcessLimitOverride = -1;

    WindowManagerService mWindowManager;

    static ActivityManagerService mSelf;
    static ActivityThread mSystemThread;

    private final class AppDeathRecipient implements IBinder.DeathRecipient {
        final ProcessRecord mApp;
        final int mPid;
        final IApplicationThread mAppThread;

        AppDeathRecipient(ProcessRecord app, int pid,
                IApplicationThread thread) {
            if (localLOGV) Slog.v(
                TAG, "New death recipient " + this
                + " for thread " + thread.asBinder());
            mApp = app;
            mPid = pid;
            mAppThread = thread;
        }

        public void binderDied() {
            if (localLOGV) Slog.v(
                TAG, "Death received in " + this
                + " for thread " + mAppThread.asBinder());
            synchronized(ActivityManagerService.this) {
                appDiedLocked(mApp, mPid, mAppThread);
            }
        }
    }

    static final int SHOW_ERROR_MSG = 1;
    static final int SHOW_NOT_RESPONDING_MSG = 2;
    static final int SHOW_FACTORY_ERROR_MSG = 3;
    static final int UPDATE_CONFIGURATION_MSG = 4;
    static final int GC_BACKGROUND_PROCESSES_MSG = 5;
    static final int WAIT_FOR_DEBUGGER_MSG = 6;
    static final int BROADCAST_INTENT_MSG = 7;
    static final int BROADCAST_TIMEOUT_MSG = 8;
    static final int SERVICE_TIMEOUT_MSG = 12;
    static final int UPDATE_TIME_ZONE = 13;
    static final int SHOW_UID_ERROR_MSG = 14;
    static final int IM_FEELING_LUCKY_MSG = 15;
    static final int PROC_START_TIMEOUT_MSG = 20;
    static final int DO_PENDING_ACTIVITY_LAUNCHES_MSG = 21;
    static final int KILL_APPLICATION_MSG = 22;
    static final int FINALIZE_PENDING_INTENT_MSG = 23;
    static final int POST_HEAVY_NOTIFICATION_MSG = 24;
    static final int CANCEL_HEAVY_NOTIFICATION_MSG = 25;
    static final int SHOW_STRICT_MODE_VIOLATION_MSG = 26;
    static final int CHECK_EXCESSIVE_WAKE_LOCKS_MSG = 27;
    static final int CLEAR_DNS_CACHE = 28;
    static final int UPDATE_HTTP_PROXY = 29;
    static final int SHOW_COMPAT_MODE_DIALOG_MSG = 30;
    static final int DISPATCH_FOREGROUND_ACTIVITIES_CHANGED = 31;
    static final int DISPATCH_PROCESS_DIED = 32;

    AlertDialog mUidAlert;
    CompatModeDialog mCompatModeDialog;

    final Handler mHandler = new Handler() {
        //public Handler() {
        //    if (localLOGV) Slog.v(TAG, "Handler started!");
        //}

        public void handleMessage(Message msg) {
            switch (msg.what) {
            case SHOW_ERROR_MSG: {
                HashMap data = (HashMap) msg.obj;
                synchronized (ActivityManagerService.this) {
                    ProcessRecord proc = (ProcessRecord)data.get("app");
                    if (proc != null && proc.crashDialog != null) {
                        Slog.e(TAG, "App already has crash dialog: " + proc);
                        return;
                    }
                    AppErrorResult res = (AppErrorResult) data.get("result");
                    if (!mSleeping && !mShuttingDown) {
                        Dialog d = new AppErrorDialog(mContext, res, proc);
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
            case SHOW_NOT_RESPONDING_MSG: {
                synchronized (ActivityManagerService.this) {
                    HashMap data = (HashMap) msg.obj;
                    ProcessRecord proc = (ProcessRecord)data.get("app");
                    if (proc != null && proc.anrDialog != null) {
                        Slog.e(TAG, "App already has anr dialog: " + proc);
                        return;
                    }
                    
                    Intent intent = new Intent("android.intent.action.ANR");
                    if (!mProcessesReady) {
                        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                    }
                    broadcastIntentLocked(null, null, intent,
                            null, null, 0, null, null, null,
                            false, false, MY_PID, Process.SYSTEM_UID);

                    Dialog d = new AppNotRespondingDialog(ActivityManagerService.this,
                            mContext, proc, (ActivityRecord)data.get("activity"));
                    d.show();
                    proc.anrDialog = d;
                }
                
                ensureBootCompleted();
            } break;
            case SHOW_STRICT_MODE_VIOLATION_MSG: {
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
                    if (!mSleeping && !mShuttingDown) {
                        Dialog d = new StrictModeViolationDialog(mContext, res, proc);
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
            case SHOW_FACTORY_ERROR_MSG: {
                Dialog d = new FactoryErrorDialog(
                    mContext, msg.getData().getCharSequence("msg"));
                d.show();
                ensureBootCompleted();
            } break;
            case UPDATE_CONFIGURATION_MSG: {
                final ContentResolver resolver = mContext.getContentResolver();
                Settings.System.putConfiguration(resolver, (Configuration)msg.obj);
            } break;
            case GC_BACKGROUND_PROCESSES_MSG: {
                synchronized (ActivityManagerService.this) {
                    performAppGcsIfAppropriateLocked();
                }
            } break;
            case WAIT_FOR_DEBUGGER_MSG: {
                synchronized (ActivityManagerService.this) {
                    ProcessRecord app = (ProcessRecord)msg.obj;
                    if (msg.arg1 != 0) {
                        if (!app.waitedForDebugger) {
                            Dialog d = new AppWaitingForDebuggerDialog(
                                    ActivityManagerService.this,
                                    mContext, app);
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
            case BROADCAST_INTENT_MSG: {
                if (DEBUG_BROADCAST) Slog.v(
                        TAG, "Received BROADCAST_INTENT_MSG");
                processNextBroadcast(true);
            } break;
            case BROADCAST_TIMEOUT_MSG: {
                synchronized (ActivityManagerService.this) {
                    broadcastTimeoutLocked(true);
                }
            } break;
            case SERVICE_TIMEOUT_MSG: {
                if (mDidDexOpt) {
                    mDidDexOpt = false;
                    Message nmsg = mHandler.obtainMessage(SERVICE_TIMEOUT_MSG);
                    nmsg.obj = msg.obj;
                    mHandler.sendMessageDelayed(nmsg, SERVICE_TIMEOUT);
                    return;
                }
                serviceTimeout((ProcessRecord)msg.obj);
            } break;
            case UPDATE_TIME_ZONE: {
                synchronized (ActivityManagerService.this) {
                    for (int i = mLruProcesses.size() - 1 ; i >= 0 ; i--) {
                        ProcessRecord r = mLruProcesses.get(i);
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
            case CLEAR_DNS_CACHE: {
                synchronized (ActivityManagerService.this) {
                    for (int i = mLruProcesses.size() - 1 ; i >= 0 ; i--) {
                        ProcessRecord r = mLruProcesses.get(i);
                        if (r.thread != null) {
                            try {
                                r.thread.clearDnsCache();
                            } catch (RemoteException ex) {
                                Slog.w(TAG, "Failed to clear dns cache for: " + r.info.processName);
                            }
                        }
                    }
                }
            } break;
            case UPDATE_HTTP_PROXY: {
                ProxyProperties proxy = (ProxyProperties)msg.obj;
                String host = "";
                String port = "";
                String exclList = "";
                if (proxy != null) {
                    host = proxy.getHost();
                    port = Integer.toString(proxy.getPort());
                    exclList = proxy.getExclusionList();
                }
                synchronized (ActivityManagerService.this) {
                    for (int i = mLruProcesses.size() - 1 ; i >= 0 ; i--) {
                        ProcessRecord r = mLruProcesses.get(i);
                        if (r.thread != null) {
                            try {
                                r.thread.setHttpProxy(host, port, exclList);
                            } catch (RemoteException ex) {
                                Slog.w(TAG, "Failed to update http proxy for: " +
                                        r.info.processName);
                            }
                        }
                    }
                }
            } break;
            case SHOW_UID_ERROR_MSG: {
                // XXX This is a temporary dialog, no need to localize.
                AlertDialog d = new BaseErrorDialog(mContext);
                d.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
                d.setCancelable(false);
                d.setTitle("System UIDs Inconsistent");
                d.setMessage("UIDs on the system are inconsistent, you need to wipe your data partition or your device will be unstable.");
                d.setButton(DialogInterface.BUTTON_POSITIVE, "I'm Feeling Lucky",
                        mHandler.obtainMessage(IM_FEELING_LUCKY_MSG));
                mUidAlert = d;
                d.show();
            } break;
            case IM_FEELING_LUCKY_MSG: {
                if (mUidAlert != null) {
                    mUidAlert.dismiss();
                    mUidAlert = null;
                }
            } break;
            case PROC_START_TIMEOUT_MSG: {
                if (mDidDexOpt) {
                    mDidDexOpt = false;
                    Message nmsg = mHandler.obtainMessage(PROC_START_TIMEOUT_MSG);
                    nmsg.obj = msg.obj;
                    mHandler.sendMessageDelayed(nmsg, PROC_START_TIMEOUT);
                    return;
                }
                ProcessRecord app = (ProcessRecord)msg.obj;
                synchronized (ActivityManagerService.this) {
                    processStartTimedOutLocked(app);
                }
            } break;
            case DO_PENDING_ACTIVITY_LAUNCHES_MSG: {
                synchronized (ActivityManagerService.this) {
                    doPendingActivityLaunchesLocked(true);
                }
            } break;
            case KILL_APPLICATION_MSG: {
                synchronized (ActivityManagerService.this) {
                    int uid = msg.arg1;
                    boolean restart = (msg.arg2 == 1);
                    String pkg = (String) msg.obj;
                    forceStopPackageLocked(pkg, uid, restart, false, true);
                }
            } break;
            case FINALIZE_PENDING_INTENT_MSG: {
                ((PendingIntentRecord)msg.obj).completeFinalize();
            } break;
            case POST_HEAVY_NOTIFICATION_MSG: {
                INotificationManager inm = NotificationManager.getService();
                if (inm == null) {
                    return;
                }
                
                ActivityRecord root = (ActivityRecord)msg.obj;
                ProcessRecord process = root.app;
                if (process == null) {
                    return;
                }
                
                try {
                    Context context = mContext.createPackageContext(process.info.packageName, 0);
                    String text = mContext.getString(R.string.heavy_weight_notification,
                            context.getApplicationInfo().loadLabel(context.getPackageManager()));
                    Notification notification = new Notification();
                    notification.icon = com.android.internal.R.drawable.stat_sys_adb; //context.getApplicationInfo().icon;
                    notification.when = 0;
                    notification.flags = Notification.FLAG_ONGOING_EVENT;
                    notification.tickerText = text;
                    notification.defaults = 0; // please be quiet
                    notification.sound = null;
                    notification.vibrate = null;
                    notification.setLatestEventInfo(context, text,
                            mContext.getText(R.string.heavy_weight_notification_detail),
                            PendingIntent.getActivity(mContext, 0, root.intent,
                                    PendingIntent.FLAG_CANCEL_CURRENT));
                    
                    try {
                        int[] outId = new int[1];
                        inm.enqueueNotification("android", R.string.heavy_weight_notification,
                                notification, outId);
                    } catch (RuntimeException e) {
                        Slog.w(ActivityManagerService.TAG,
                                "Error showing notification for heavy-weight app", e);
                    } catch (RemoteException e) {
                    }
                } catch (NameNotFoundException e) {
                    Slog.w(TAG, "Unable to create context for heavy notification", e);
                }
            } break;
            case CANCEL_HEAVY_NOTIFICATION_MSG: {
                INotificationManager inm = NotificationManager.getService();
                if (inm == null) {
                    return;
                }
                try {
                    inm.cancelNotification("android",
                            R.string.heavy_weight_notification);
                } catch (RuntimeException e) {
                    Slog.w(ActivityManagerService.TAG,
                            "Error canceling notification for service", e);
                } catch (RemoteException e) {
                }
            } break;
            case CHECK_EXCESSIVE_WAKE_LOCKS_MSG: {
                synchronized (ActivityManagerService.this) {
                    checkExcessivePowerUsageLocked(true);
                    removeMessages(CHECK_EXCESSIVE_WAKE_LOCKS_MSG);
                    Message nmsg = obtainMessage(CHECK_EXCESSIVE_WAKE_LOCKS_MSG);
                    sendMessageDelayed(nmsg, POWER_CHECK_DELAY);
                }
            } break;
            case SHOW_COMPAT_MODE_DIALOG_MSG: {
                synchronized (ActivityManagerService.this) {
                    ActivityRecord ar = (ActivityRecord)msg.obj;
                    if (mCompatModeDialog != null) {
                        if (mCompatModeDialog.mAppInfo.packageName.equals(
                                ar.info.applicationInfo.packageName)) {
                            return;
                        }
                        mCompatModeDialog.dismiss();
                        mCompatModeDialog = null;
                    }
                    if (ar != null && false) {
                        if (mCompatModePackages.getPackageAskCompatModeLocked(
                                ar.packageName)) {
                            int mode = mCompatModePackages.computeCompatModeLocked(
                                    ar.info.applicationInfo);
                            if (mode == ActivityManager.COMPAT_MODE_DISABLED
                                    || mode == ActivityManager.COMPAT_MODE_ENABLED) {
                                mCompatModeDialog = new CompatModeDialog(
                                        ActivityManagerService.this, mContext,
                                        ar.info.applicationInfo);
                                mCompatModeDialog.show();
                            }
                        }
                    }
                }
                break;
            }
            case DISPATCH_FOREGROUND_ACTIVITIES_CHANGED: {
                final int pid = msg.arg1;
                final int uid = msg.arg2;
                final boolean foregroundActivities = (Boolean) msg.obj;
                dispatchForegroundActivitiesChanged(pid, uid, foregroundActivities);
                break;
            }
            case DISPATCH_PROCESS_DIED: {
                final int pid = msg.arg1;
                final int uid = msg.arg2;
                dispatchProcessDied(pid, uid);
                break;
            }
            }
        }
    };

    public static void setSystemProcess() {
        try {
            ActivityManagerService m = mSelf;
            
            ServiceManager.addService("activity", m);
            ServiceManager.addService("meminfo", new MemBinder(m));
            ServiceManager.addService("gfxinfo", new GraphicsBinder(m));
            if (MONITOR_CPU_USAGE) {
                ServiceManager.addService("cpuinfo", new CpuBinder(m));
            }
            ServiceManager.addService("permission", new PermissionController(m));

            ApplicationInfo info =
                mSelf.mContext.getPackageManager().getApplicationInfo(
                        "android", STOCK_PM_FLAGS);
            mSystemThread.installSystemApplicationInfo(info);
       
            synchronized (mSelf) {
                ProcessRecord app = mSelf.newProcessRecordLocked(
                        mSystemThread.getApplicationThread(), info,
                        info.processName);
                app.persistent = true;
                app.pid = MY_PID;
                app.maxAdj = ProcessList.SYSTEM_ADJ;
                mSelf.mProcessNames.put(app.processName, app.info.uid, app);
                synchronized (mSelf.mPidsSelfLocked) {
                    mSelf.mPidsSelfLocked.put(app.pid, app);
                }
                mSelf.updateLruProcessLocked(app, true, true);
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(
                    "Unable to find android system package", e);
        }
    }

    public void setWindowManager(WindowManagerService wm) {
        mWindowManager = wm;
    }

    public static final Context main(int factoryTest) {
        AThread thr = new AThread();
        thr.start();

        synchronized (thr) {
            while (thr.mService == null) {
                try {
                    thr.wait();
                } catch (InterruptedException e) {
                }
            }
        }

        ActivityManagerService m = thr.mService;
        mSelf = m;
        ActivityThread at = ActivityThread.systemMain();
        mSystemThread = at;
        Context context = at.getSystemContext();
        context.setTheme(android.R.style.Theme_Holo);
        m.mContext = context;
        m.mFactoryTest = factoryTest;
        m.mMainStack = new ActivityStack(m, context, true);
        
        m.mBatteryStatsService.publish(context);
        m.mUsageStatsService.publish(context);
        
        synchronized (thr) {
            thr.mReady = true;
            thr.notifyAll();
        }

        m.startRunning(null, null, null, null);
        
        return context;
    }

    public static ActivityManagerService self() {
        return mSelf;
    }
    
    static class AThread extends Thread {
        ActivityManagerService mService;
        boolean mReady = false;

        public AThread() {
            super("ActivityManager");
        }

        public void run() {
            Looper.prepare();

            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_FOREGROUND);
            android.os.Process.setCanSelfBackground(false);

            ActivityManagerService m = new ActivityManagerService();

            synchronized (this) {
                mService = m;
                notifyAll();
            }

            synchronized (this) {
                while (!mReady) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                    }
                }
            }

            // For debug builds, log event loop stalls to dropbox for analysis.
            if (StrictMode.conditionallyEnableDebugLogging()) {
                Slog.i(TAG, "Enabled StrictMode logging for AThread's Looper");
            }

            Looper.loop();
        }
    }

    static class MemBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        MemBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            mActivityManagerService.dumpApplicationMemoryUsage(fd, pw, "  ", args);
        }
    }

    static class GraphicsBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        GraphicsBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            mActivityManagerService.dumpGraphicsHardwareUsage(fd, pw, args);
        }
    }

    static class CpuBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        CpuBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            synchronized (mActivityManagerService.mProcessStatsThread) {
                pw.print(mActivityManagerService.mProcessStats.printCurrentLoad());
                pw.print(mActivityManagerService.mProcessStats.printCurrentState(
                        SystemClock.uptimeMillis()));
            }
        }
    }

    private ActivityManagerService() {
        Slog.i(TAG, "Memory class: " + ActivityManager.staticGetMemoryClass());
        
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        systemDir.mkdirs();
        mBatteryStatsService = new BatteryStatsService(new File(
                systemDir, "batterystats.bin").toString());
        mBatteryStatsService.getActiveStatistics().readLocked();
        mBatteryStatsService.getActiveStatistics().writeAsyncLocked();
        mOnBattery = DEBUG_POWER ? true
                : mBatteryStatsService.getActiveStatistics().getIsOnBattery();
        mBatteryStatsService.getActiveStatistics().setCallback(this);
        
        mUsageStatsService = new UsageStatsService(new File(
                systemDir, "usagestats").toString());

        GL_ES_VERSION = SystemProperties.getInt("ro.opengles.version",
            ConfigurationInfo.GL_ES_VERSION_UNDEFINED);

        mConfiguration.setToDefaults();
        mConfiguration.locale = Locale.getDefault();
        mProcessStats.init();
        
        mCompatModePackages = new CompatModePackages(this, systemDir);

        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);

        mProcessStatsThread = new Thread("ProcessStats") {
            public void run() {
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
                                    mProcessStatsMutexFree.set(true);
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
        mProcessStatsThread.start();
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            // The activity manager only throws security exceptions, so let's
            // log all others.
            if (!(e instanceof SecurityException)) {
                Slog.e(TAG, "Activity Manager Crash", e);
            }
            throw e;
        }
    }

    void updateCpuStats() {
        final long now = SystemClock.uptimeMillis();
        if (mLastCpuTime.get() >= now - MONITOR_CPU_MIN_TIME) {
            return;
        }
        if (mProcessStatsMutexFree.compareAndSet(true, false)) {
            synchronized (mProcessStatsThread) {
                mProcessStatsThread.notify();
            }
        }
    }

    void updateCpuStatsNow() {
        synchronized (mProcessStatsThread) {
            mProcessStatsMutexFree.set(false);
            final long now = SystemClock.uptimeMillis();
            boolean haveNewCpuStats = false;

            if (MONITOR_CPU_USAGE &&
                    mLastCpuTime.get() < (now-MONITOR_CPU_MIN_TIME)) {
                mLastCpuTime.set(now);
                haveNewCpuStats = true;
                mProcessStats.update();
                //Slog.i(TAG, mProcessStats.printCurrentState());
                //Slog.i(TAG, "Total CPU usage: "
                //        + mProcessStats.getTotalCpuPercent() + "%");

                // Slog the cpu usage if the property is set.
                if ("true".equals(SystemProperties.get("events.cpu"))) {
                    int user = mProcessStats.getLastUserTime();
                    int system = mProcessStats.getLastSystemTime();
                    int iowait = mProcessStats.getLastIoWaitTime();
                    int irq = mProcessStats.getLastIrqTime();
                    int softIrq = mProcessStats.getLastSoftIrqTime();
                    int idle = mProcessStats.getLastIdleTime();

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
            
            long[] cpuSpeedTimes = mProcessStats.getLastCpuSpeedTimes();
            final BatteryStatsImpl bstats = mBatteryStatsService.getActiveStatistics();
            synchronized(bstats) {
                synchronized(mPidsSelfLocked) {
                    if (haveNewCpuStats) {
                        if (mOnBattery) {
                            int perc = bstats.startAddingCpuLocked();
                            int totalUTime = 0;
                            int totalSTime = 0;
                            final int N = mProcessStats.countStats();
                            for (int i=0; i<N; i++) {
                                ProcessStats.Stats st = mProcessStats.getStats(i);
                                if (!st.working) {
                                    continue;
                                }
                                ProcessRecord pr = mPidsSelfLocked.get(st.pid);
                                int otherUTime = (st.rel_utime*perc)/100;
                                int otherSTime = (st.rel_stime*perc)/100;
                                totalUTime += otherUTime;
                                totalSTime += otherSTime;
                                if (pr != null) {
                                    BatteryStatsImpl.Uid.Proc ps = pr.batteryStats;
                                    ps.addCpuTimeLocked(st.rel_utime-otherUTime,
                                            st.rel_stime-otherSTime);
                                    ps.addSpeedStepTimes(cpuSpeedTimes);
                                    pr.curCpuTime += (st.rel_utime+st.rel_stime) * 10;
                                } else {
                                    BatteryStatsImpl.Uid.Proc ps =
                                            bstats.getProcessStatsLocked(st.name, st.pid);
                                    if (ps != null) {
                                        ps.addCpuTimeLocked(st.rel_utime-otherUTime,
                                                st.rel_stime-otherSTime);
                                        ps.addSpeedStepTimes(cpuSpeedTimes);
                                    }
                                }
                            }
                            bstats.finishAddingCpuLocked(perc, totalUTime,
                                    totalSTime, cpuSpeedTimes);
                        }
                    }
                }

                if (mLastWriteTime < (now-BATTERY_STATS_TIME)) {
                    mLastWriteTime = now;
                    mBatteryStatsService.getActiveStatistics().writeAsyncLocked();
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
        }
    }

    /**
     * Initialize the application bind args. These are passed to each
     * process when the bindApplication() IPC is sent to the process. They're
     * lazily setup to make sure the services are running when they're asked for.
     */
    private HashMap<String, IBinder> getCommonServicesLocked() {
        if (mAppBindArgs == null) {
            mAppBindArgs = new HashMap<String, IBinder>();

            // Setup the application init args
            mAppBindArgs.put("package", ServiceManager.getService("package"));
            mAppBindArgs.put("window", ServiceManager.getService("window"));
            mAppBindArgs.put(Context.ALARM_SERVICE,
                    ServiceManager.getService(Context.ALARM_SERVICE));
        }
        return mAppBindArgs;
    }

    final void setFocusedActivityLocked(ActivityRecord r) {
        if (mFocusedActivity != r) {
            mFocusedActivity = r;
            mWindowManager.setFocusedApp(r, true);
        }
    }

    private final void updateLruProcessInternalLocked(ProcessRecord app,
            boolean oomAdj, boolean updateActivityTime, int bestPos) {
        // put it on the LRU to keep track of when it should be exited.
        int lrui = mLruProcesses.indexOf(app);
        if (lrui >= 0) mLruProcesses.remove(lrui);
        
        int i = mLruProcesses.size()-1;
        int skipTop = 0;
        
        app.lruSeq = mLruSeq;
        
        // compute the new weight for this process.
        if (updateActivityTime) {
            app.lastActivityTime = SystemClock.uptimeMillis();
        }
        if (app.activities.size() > 0) {
            // If this process has activities, we more strongly want to keep
            // it around.
            app.lruWeight = app.lastActivityTime;
        } else if (app.pubProviders.size() > 0) {
            // If this process contains content providers, we want to keep
            // it a little more strongly.
            app.lruWeight = app.lastActivityTime - ProcessList.CONTENT_APP_IDLE_OFFSET;
            // Also don't let it kick out the first few "real" hidden processes.
            skipTop = ProcessList.MIN_HIDDEN_APPS;
        } else {
            // If this process doesn't have activities, we less strongly
            // want to keep it around, and generally want to avoid getting
            // in front of any very recently used activities.
            app.lruWeight = app.lastActivityTime - ProcessList.EMPTY_APP_IDLE_OFFSET;
            // Also don't let it kick out the first few "real" hidden processes.
            skipTop = ProcessList.MIN_HIDDEN_APPS;
        }
        
        while (i >= 0) {
            ProcessRecord p = mLruProcesses.get(i);
            // If this app shouldn't be in front of the first N background
            // apps, then skip over that many that are currently hidden.
            if (skipTop > 0 && p.setAdj >= ProcessList.HIDDEN_APP_MIN_ADJ) {
                skipTop--;
            }
            if (p.lruWeight <= app.lruWeight || i < bestPos) {
                mLruProcesses.add(i+1, app);
                break;
            }
            i--;
        }
        if (i < 0) {
            mLruProcesses.add(0, app);
        }
        
        // If the app is currently using a content provider or service,
        // bump those processes as well.
        if (app.connections.size() > 0) {
            for (ConnectionRecord cr : app.connections) {
                if (cr.binding != null && cr.binding.service != null
                        && cr.binding.service.app != null
                        && cr.binding.service.app.lruSeq != mLruSeq) {
                    updateLruProcessInternalLocked(cr.binding.service.app, oomAdj,
                            updateActivityTime, i+1);
                }
            }
        }
        if (app.conProviders.size() > 0) {
            for (ContentProviderRecord cpr : app.conProviders.keySet()) {
                if (cpr.app != null && cpr.app.lruSeq != mLruSeq) {
                    updateLruProcessInternalLocked(cpr.app, oomAdj,
                            updateActivityTime, i+1);
                }
            }
        }
        
        //Slog.i(TAG, "Putting proc to front: " + app.processName);
        if (oomAdj) {
            updateOomAdjLocked();
        }
    }

    final void updateLruProcessLocked(ProcessRecord app,
            boolean oomAdj, boolean updateActivityTime) {
        mLruSeq++;
        updateLruProcessInternalLocked(app, oomAdj, updateActivityTime, 0);
    }

    final ProcessRecord getProcessRecordLocked(
            String processName, int uid) {
        if (uid == Process.SYSTEM_UID) {
            // The system gets to run in any process.  If there are multiple
            // processes with the same uid, just pick the first (this
            // should never happen).
            SparseArray<ProcessRecord> procs = mProcessNames.getMap().get(
                    processName);
            return procs != null ? procs.valueAt(0) : null;
        }
        ProcessRecord proc = mProcessNames.get(processName, uid);
        return proc;
    }

    void ensurePackageDexOpt(String packageName) {
        IPackageManager pm = AppGlobals.getPackageManager();
        try {
            if (pm.performDexOpt(packageName)) {
                mDidDexOpt = true;
            }
        } catch (RemoteException e) {
        }
    }
    
    boolean isNextTransitionForward() {
        int transit = mWindowManager.getPendingAppTransition();
        return transit == WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN
                || transit == WindowManagerPolicy.TRANSIT_TASK_OPEN
                || transit == WindowManagerPolicy.TRANSIT_TASK_TO_FRONT;
    }
    
    final ProcessRecord startProcessLocked(String processName,
            ApplicationInfo info, boolean knownToBeDead, int intentFlags,
            String hostingType, ComponentName hostingName, boolean allowWhileBooting) {
        ProcessRecord app = getProcessRecordLocked(processName, info.uid);
        // We don't have to do anything more if:
        // (1) There is an existing application record; and
        // (2) The caller doesn't think it is dead, OR there is no thread
        //     object attached to it so we know it couldn't have crashed; and
        // (3) There is a pid assigned to it, so it is either starting or
        //     already running.
        if (DEBUG_PROCESSES) Slog.v(TAG, "startProcess: name=" + processName
                + " app=" + app + " knownToBeDead=" + knownToBeDead
                + " thread=" + (app != null ? app.thread : null)
                + " pid=" + (app != null ? app.pid : -1));
        if (app != null && app.pid > 0) {
            if (!knownToBeDead || app.thread == null) {
                // We already have the app running, or are waiting for it to
                // come up (we have a pid but not yet its thread), so keep it.
                if (DEBUG_PROCESSES) Slog.v(TAG, "App already running: " + app);
                // If this is a new package in the process, add the package to the list
                app.addPackage(info.packageName);
                return app;
            } else {
                // An application record is attached to a previous process,
                // clean it up now.
                if (DEBUG_PROCESSES) Slog.v(TAG, "App died: " + app);
                handleAppDiedLocked(app, true, true);
            }
        }

        String hostingNameStr = hostingName != null
                ? hostingName.flattenToShortString() : null;
        
        if ((intentFlags&Intent.FLAG_FROM_BACKGROUND) != 0) {
            // If we are in the background, then check to see if this process
            // is bad.  If so, we will just silently fail.
            if (mBadProcesses.get(info.processName, info.uid) != null) {
                if (DEBUG_PROCESSES) Slog.v(TAG, "Bad process: " + info.uid
                        + "/" + info.processName);
                return null;
            }
        } else {
            // When the user is explicitly starting a process, then clear its
            // crash count so that we won't make it bad until they see at
            // least one crash dialog again, and make the process good again
            // if it had been bad.
            if (DEBUG_PROCESSES) Slog.v(TAG, "Clearing bad process: " + info.uid
                    + "/" + info.processName);
            mProcessCrashTimes.remove(info.processName, info.uid);
            if (mBadProcesses.get(info.processName, info.uid) != null) {
                EventLog.writeEvent(EventLogTags.AM_PROC_GOOD, info.uid,
                        info.processName);
                mBadProcesses.remove(info.processName, info.uid);
                if (app != null) {
                    app.bad = false;
                }
            }
        }
        
        if (app == null) {
            app = newProcessRecordLocked(null, info, processName);
            mProcessNames.put(processName, info.uid, app);
        } else {
            // If this is a new package in the process, add the package to the list
            app.addPackage(info.packageName);
        }

        // If the system is not ready yet, then hold off on starting this
        // process until it is.
        if (!mProcessesReady
                && !isAllowedWhileBooting(info)
                && !allowWhileBooting) {
            if (!mProcessesOnHold.contains(app)) {
                mProcessesOnHold.add(app);
            }
            if (DEBUG_PROCESSES) Slog.v(TAG, "System not ready, putting on hold: " + app);
            return app;
        }

        startProcessLocked(app, hostingType, hostingNameStr);
        return (app.pid != 0) ? app : null;
    }

    boolean isAllowedWhileBooting(ApplicationInfo ai) {
        return (ai.flags&ApplicationInfo.FLAG_PERSISTENT) != 0;
    }
    
    private final void startProcessLocked(ProcessRecord app,
            String hostingType, String hostingNameStr) {
        if (app.pid > 0 && app.pid != MY_PID) {
            synchronized (mPidsSelfLocked) {
                mPidsSelfLocked.remove(app.pid);
                mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);
            }
            app.pid = 0;
        }

        if (DEBUG_PROCESSES && mProcessesOnHold.contains(app)) Slog.v(TAG,
                "startProcessLocked removing on hold: " + app);
        mProcessesOnHold.remove(app);

        updateCpuStats();
        
        System.arraycopy(mProcDeaths, 0, mProcDeaths, 1, mProcDeaths.length-1);
        mProcDeaths[0] = 0;
        
        try {
            int uid = app.info.uid;
            int[] gids = null;
            try {
                gids = mContext.getPackageManager().getPackageGids(
                        app.info.packageName);
            } catch (PackageManager.NameNotFoundException e) {
                Slog.w(TAG, "Unable to retrieve gids", e);
            }
            if (mFactoryTest != SystemServer.FACTORY_TEST_OFF) {
                if (mFactoryTest == SystemServer.FACTORY_TEST_LOW_LEVEL
                        && mTopComponent != null
                        && app.processName.equals(mTopComponent.getPackageName())) {
                    uid = 0;
                }
                if (mFactoryTest == SystemServer.FACTORY_TEST_HIGH_LEVEL
                        && (app.info.flags&ApplicationInfo.FLAG_FACTORY_TEST) != 0) {
                    uid = 0;
                }
            }
            int debugFlags = 0;
            if ((app.info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                debugFlags |= Zygote.DEBUG_ENABLE_DEBUGGER;
                // Also turn on CheckJNI for debuggable apps. It's quite
                // awkward to turn on otherwise.
                debugFlags |= Zygote.DEBUG_ENABLE_CHECKJNI;
            }
            // Run the app in safe mode if its manifest requests so or the
            // system is booted in safe mode.
            if ((app.info.flags & ApplicationInfo.FLAG_VM_SAFE_MODE) != 0 ||
                Zygote.systemInSafeMode == true) {
                debugFlags |= Zygote.DEBUG_ENABLE_SAFEMODE;
            }
            if ("1".equals(SystemProperties.get("debug.checkjni"))) {
                debugFlags |= Zygote.DEBUG_ENABLE_CHECKJNI;
            }
            if ("1".equals(SystemProperties.get("debug.jni.logging"))) {
                debugFlags |= Zygote.DEBUG_ENABLE_JNI_LOGGING;
            }
            if ("1".equals(SystemProperties.get("debug.assert"))) {
                debugFlags |= Zygote.DEBUG_ENABLE_ASSERT;
            }

            // Start the process.  It will either succeed and return a result containing
            // the PID of the new process, or else throw a RuntimeException.
            Process.ProcessStartResult startResult = Process.start("android.app.ActivityThread",
                    app.processName, uid, uid, gids, debugFlags,
                    app.info.targetSdkVersion, null);

            BatteryStatsImpl bs = app.batteryStats.getBatteryStats();
            synchronized (bs) {
                if (bs.isOnBattery()) {
                    app.batteryStats.incStartsLocked();
                }
            }
            
            EventLog.writeEvent(EventLogTags.AM_PROC_START, startResult.pid, uid,
                    app.processName, hostingType,
                    hostingNameStr != null ? hostingNameStr : "");
            
            if (app.persistent) {
                Watchdog.getInstance().processStarted(app.processName, startResult.pid);
            }
            
            StringBuilder buf = mStringBuilder;
            buf.setLength(0);
            buf.append("Start proc ");
            buf.append(app.processName);
            buf.append(" for ");
            buf.append(hostingType);
            if (hostingNameStr != null) {
                buf.append(" ");
                buf.append(hostingNameStr);
            }
            buf.append(": pid=");
            buf.append(startResult.pid);
            buf.append(" uid=");
            buf.append(uid);
            buf.append(" gids={");
            if (gids != null) {
                for (int gi=0; gi<gids.length; gi++) {
                    if (gi != 0) buf.append(", ");
                    buf.append(gids[gi]);

                }
            }
            buf.append("}");
            Slog.i(TAG, buf.toString());
            app.pid = startResult.pid;
            app.usingWrapper = startResult.usingWrapper;
            app.removed = false;
            synchronized (mPidsSelfLocked) {
                this.mPidsSelfLocked.put(startResult.pid, app);
                Message msg = mHandler.obtainMessage(PROC_START_TIMEOUT_MSG);
                msg.obj = app;
                mHandler.sendMessageDelayed(msg, startResult.usingWrapper
                        ? PROC_START_TIMEOUT_WITH_WRAPPER : PROC_START_TIMEOUT);
            }
        } catch (RuntimeException e) {
            // XXX do better error recovery.
            app.pid = 0;
            Slog.e(TAG, "Failure starting process " + app.processName, e);
        }
    }

    void updateUsageStats(ActivityRecord resumedComponent, boolean resumed) {
        if (resumed) {
            mUsageStatsService.noteResumeComponent(resumedComponent.realActivity);
        } else {
            mUsageStatsService.notePauseComponent(resumedComponent.realActivity);
        }
    }

    boolean startHomeActivityLocked() {
        if (mFactoryTest == SystemServer.FACTORY_TEST_LOW_LEVEL
                && mTopAction == null) {
            // We are running in factory test mode, but unable to find
            // the factory test app, so just sit around displaying the
            // error message and don't try to start anything.
            return false;
        }
        Intent intent = new Intent(
            mTopAction,
            mTopData != null ? Uri.parse(mTopData) : null);
        intent.setComponent(mTopComponent);
        if (mFactoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL) {
            intent.addCategory(Intent.CATEGORY_HOME);
        }
        ActivityInfo aInfo =
            intent.resolveActivityInfo(mContext.getPackageManager(),
                    STOCK_PM_FLAGS);
        if (aInfo != null) {
            intent.setComponent(new ComponentName(
                    aInfo.applicationInfo.packageName, aInfo.name));
            // Don't do this if the home app is currently being
            // instrumented.
            ProcessRecord app = getProcessRecordLocked(aInfo.processName,
                    aInfo.applicationInfo.uid);
            if (app == null || app.instrumentationClass == null) {
                intent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_NEW_TASK);
                mMainStack.startActivityLocked(null, intent, null, null, 0, aInfo,
                        null, null, 0, 0, 0, false, false, null);
            }
        }
        
        
        return true;
    }
    
    /**
     * Starts the "new version setup screen" if appropriate.
     */
    void startSetupActivityLocked() {
        // Only do this once per boot.
        if (mCheckedForSetup) {
            return;
        }
        
        // We will show this screen if the current one is a different
        // version than the last one shown, and we are not running in
        // low-level factory test mode.
        final ContentResolver resolver = mContext.getContentResolver();
        if (mFactoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL &&
                Settings.Secure.getInt(resolver,
                        Settings.Secure.DEVICE_PROVISIONED, 0) != 0) {
            mCheckedForSetup = true;
            
            // See if we should be showing the platform update setup UI.
            Intent intent = new Intent(Intent.ACTION_UPGRADE_SETUP);
            List<ResolveInfo> ris = mSelf.mContext.getPackageManager()
                    .queryIntentActivities(intent, PackageManager.GET_META_DATA);
            
            // We don't allow third party apps to replace this.
            ResolveInfo ri = null;
            for (int i=0; ris != null && i<ris.size(); i++) {
                if ((ris.get(i).activityInfo.applicationInfo.flags
                        & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    ri = ris.get(i);
                    break;
                }
            }
            
            if (ri != null) {
                String vers = ri.activityInfo.metaData != null
                        ? ri.activityInfo.metaData.getString(Intent.METADATA_SETUP_VERSION)
                        : null;
                if (vers == null && ri.activityInfo.applicationInfo.metaData != null) {
                    vers = ri.activityInfo.applicationInfo.metaData.getString(
                            Intent.METADATA_SETUP_VERSION);
                }
                String lastVers = Settings.Secure.getString(
                        resolver, Settings.Secure.LAST_SETUP_SHOWN);
                if (vers != null && !vers.equals(lastVers)) {
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setComponent(new ComponentName(
                            ri.activityInfo.packageName, ri.activityInfo.name));
                    mMainStack.startActivityLocked(null, intent, null, null, 0, ri.activityInfo,
                            null, null, 0, 0, 0, false, false, null);
                }
            }
        }
    }
    
    CompatibilityInfo compatibilityInfoForPackageLocked(ApplicationInfo ai) {
        return mCompatModePackages.compatibilityInfoForPackageLocked(ai);
    }

    public int getFrontActivityScreenCompatMode() {
        synchronized (this) {
            return mCompatModePackages.getFrontActivityScreenCompatModeLocked();
        }
    }

    public void setFrontActivityScreenCompatMode(int mode) {
        synchronized (this) {
            mCompatModePackages.setFrontActivityScreenCompatModeLocked(mode);
        }
    }

    public int getPackageScreenCompatMode(String packageName) {
        synchronized (this) {
            return mCompatModePackages.getPackageScreenCompatModeLocked(packageName);
        }
    }

    public void setPackageScreenCompatMode(String packageName, int mode) {
        synchronized (this) {
            mCompatModePackages.setPackageScreenCompatModeLocked(packageName, mode);
        }
    }

    public boolean getPackageAskScreenCompat(String packageName) {
        synchronized (this) {
            return mCompatModePackages.getPackageAskCompatModeLocked(packageName);
        }
    }

    public void setPackageAskScreenCompat(String packageName, boolean ask) {
        synchronized (this) {
            mCompatModePackages.setPackageAskCompatModeLocked(packageName, ask);
        }
    }

    void reportResumedActivityLocked(ActivityRecord r) {
        //Slog.i(TAG, "**** REPORT RESUME: " + r);
        
        final int identHash = System.identityHashCode(r);
        updateUsageStats(r, true);
        
        int i = mWatchers.beginBroadcast();
        while (i > 0) {
            i--;
            IActivityWatcher w = mWatchers.getBroadcastItem(i);
            if (w != null) {
                try {
                    w.activityResuming(identHash);
                } catch (RemoteException e) {
                }
            }
        }
        mWatchers.finishBroadcast();
    }

    private void dispatchForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) {
        int i = mProcessObservers.beginBroadcast();
        while (i > 0) {
            i--;
            final IProcessObserver observer = mProcessObservers.getBroadcastItem(i);
            if (observer != null) {
                try {
                    observer.onForegroundActivitiesChanged(pid, uid, foregroundActivities);
                } catch (RemoteException e) {
                }
            }
        }
        mProcessObservers.finishBroadcast();
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

    final void doPendingActivityLaunchesLocked(boolean doResume) {
        final int N = mPendingActivityLaunches.size();
        if (N <= 0) {
            return;
        }
        for (int i=0; i<N; i++) {
            PendingActivityLaunch pal = mPendingActivityLaunches.get(i);
            mMainStack.startActivityUncheckedLocked(pal.r, pal.sourceRecord,
                    pal.grantedUriPermissions, pal.grantedMode, pal.onlyIfNeeded,
                    doResume && i == (N-1));
        }
        mPendingActivityLaunches.clear();
    }

    public final int startActivity(IApplicationThread caller,
            Intent intent, String resolvedType, Uri[] grantedUriPermissions,
            int grantedMode, IBinder resultTo,
            String resultWho, int requestCode, boolean onlyIfNeeded,
            boolean debug) {
        return mMainStack.startActivityMayWait(caller, -1, intent, resolvedType,
                grantedUriPermissions, grantedMode, resultTo, resultWho,
                requestCode, onlyIfNeeded, debug, null, null);
    }

    public final WaitResult startActivityAndWait(IApplicationThread caller,
            Intent intent, String resolvedType, Uri[] grantedUriPermissions,
            int grantedMode, IBinder resultTo,
            String resultWho, int requestCode, boolean onlyIfNeeded,
            boolean debug) {
        WaitResult res = new WaitResult();
        mMainStack.startActivityMayWait(caller, -1, intent, resolvedType,
                grantedUriPermissions, grantedMode, resultTo, resultWho,
                requestCode, onlyIfNeeded, debug, res, null);
        return res;
    }
    
    public final int startActivityWithConfig(IApplicationThread caller,
            Intent intent, String resolvedType, Uri[] grantedUriPermissions,
            int grantedMode, IBinder resultTo,
            String resultWho, int requestCode, boolean onlyIfNeeded,
            boolean debug, Configuration config) {
        return mMainStack.startActivityMayWait(caller, -1, intent, resolvedType,
                grantedUriPermissions, grantedMode, resultTo, resultWho,
                requestCode, onlyIfNeeded, debug, null, config);
    }

    public int startActivityIntentSender(IApplicationThread caller,
            IntentSender intent, Intent fillInIntent, String resolvedType,
            IBinder resultTo, String resultWho, int requestCode,
            int flagsMask, int flagsValues) {
        // Refuse possible leaked file descriptors
        if (fillInIntent != null && fillInIntent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        
        IIntentSender sender = intent.getTarget();
        if (!(sender instanceof PendingIntentRecord)) {
            throw new IllegalArgumentException("Bad PendingIntent object");
        }
        
        PendingIntentRecord pir = (PendingIntentRecord)sender;
        
        synchronized (this) {
            // If this is coming from the currently resumed activity, it is
            // effectively saying that app switches are allowed at this point.
            if (mMainStack.mResumedActivity != null
                    && mMainStack.mResumedActivity.info.applicationInfo.uid ==
                            Binder.getCallingUid()) {
                mAppSwitchesAllowedTime = 0;
            }
        }
        
        return pir.sendInner(0, fillInIntent, resolvedType, null,
                null, resultTo, resultWho, requestCode, flagsMask, flagsValues);
    }
    
    public boolean startNextMatchingActivity(IBinder callingActivity,
            Intent intent) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized (this) {
            ActivityRecord r = mMainStack.isInStackLocked(callingActivity);
            if (r == null) {
                return false;
            }
            if (r.app == null || r.app.thread == null) {
                // The caller is not running...  d'oh!
                return false;
            }
            intent = new Intent(intent);
            // The caller is not allowed to change the data.
            intent.setDataAndType(r.intent.getData(), r.intent.getType());
            // And we are resetting to find the next component...
            intent.setComponent(null);

            ActivityInfo aInfo = null;
            try {
                List<ResolveInfo> resolves =
                    AppGlobals.getPackageManager().queryIntentActivities(
                            intent, r.resolvedType,
                            PackageManager.MATCH_DEFAULT_ONLY | STOCK_PM_FLAGS);

                // Look for the original activity in the list...
                final int N = resolves != null ? resolves.size() : 0;
                for (int i=0; i<N; i++) {
                    ResolveInfo rInfo = resolves.get(i);
                    if (rInfo.activityInfo.packageName.equals(r.packageName)
                            && rInfo.activityInfo.name.equals(r.info.name)) {
                        // We found the current one...  the next matching is
                        // after it.
                        i++;
                        if (i<N) {
                            aInfo = resolves.get(i).activityInfo;
                        }
                        break;
                    }
                }
            } catch (RemoteException e) {
            }

            if (aInfo == null) {
                // Nobody who is next!
                return false;
            }

            intent.setComponent(new ComponentName(
                    aInfo.applicationInfo.packageName, aInfo.name));
            intent.setFlags(intent.getFlags()&~(
                    Intent.FLAG_ACTIVITY_FORWARD_RESULT|
                    Intent.FLAG_ACTIVITY_CLEAR_TOP|
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK|
                    Intent.FLAG_ACTIVITY_NEW_TASK));

            // Okay now we need to start the new activity, replacing the
            // currently running activity.  This is a little tricky because
            // we want to start the new one as if the current one is finished,
            // but not finish the current one first so that there is no flicker.
            // And thus...
            final boolean wasFinishing = r.finishing;
            r.finishing = true;

            // Propagate reply information over to the new activity.
            final ActivityRecord resultTo = r.resultTo;
            final String resultWho = r.resultWho;
            final int requestCode = r.requestCode;
            r.resultTo = null;
            if (resultTo != null) {
                resultTo.removeResultsLocked(r, resultWho, requestCode);
            }

            final long origId = Binder.clearCallingIdentity();
            // XXX we are not dealing with propagating grantedUriPermissions...
            // those are not yet exposed to user code, so there is no need.
            int res = mMainStack.startActivityLocked(r.app.thread, intent,
                    r.resolvedType, null, 0, aInfo, resultTo, resultWho,
                    requestCode, -1, r.launchedFromUid, false, false, null);
            Binder.restoreCallingIdentity(origId);

            r.finishing = wasFinishing;
            if (res != START_SUCCESS) {
                return false;
            }
            return true;
        }
    }

    public final int startActivityInPackage(int uid,
            Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, boolean onlyIfNeeded) {
        
        // This is so super not safe, that only the system (or okay root)
        // can do it.
        final int callingUid = Binder.getCallingUid();
        if (callingUid != 0 && callingUid != Process.myUid()) {
            throw new SecurityException(
                    "startActivityInPackage only available to the system");
        }

        return mMainStack.startActivityMayWait(null, uid, intent, resolvedType,
                null, 0, resultTo, resultWho, requestCode, onlyIfNeeded, false, null, null);
    }

    public final int startActivities(IApplicationThread caller,
            Intent[] intents, String[] resolvedTypes, IBinder resultTo) {
        return mMainStack.startActivities(caller, -1, intents, resolvedTypes, resultTo);
    }

    public final int startActivitiesInPackage(int uid,
            Intent[] intents, String[] resolvedTypes, IBinder resultTo) {

        // This is so super not safe, that only the system (or okay root)
        // can do it.
        final int callingUid = Binder.getCallingUid();
        if (callingUid != 0 && callingUid != Process.myUid()) {
            throw new SecurityException(
                    "startActivityInPackage only available to the system");
        }

        return mMainStack.startActivities(null, uid, intents, resolvedTypes, resultTo);
    }

    final void addRecentTaskLocked(TaskRecord task) {
        int N = mRecentTasks.size();
        // Quick case: check if the top-most recent task is the same.
        if (N > 0 && mRecentTasks.get(0) == task) {
            return;
        }
        // Remove any existing entries that are the same kind of task.
        for (int i=0; i<N; i++) {
            TaskRecord tr = mRecentTasks.get(i);
            if ((task.affinity != null && task.affinity.equals(tr.affinity))
                    || (task.intent != null && task.intent.filterEquals(tr.intent))) {
                mRecentTasks.remove(i);
                i--;
                N--;
                if (task.intent == null) {
                    // If the new recent task we are adding is not fully
                    // specified, then replace it with the existing recent task.
                    task = tr;
                }
            }
        }
        if (N >= MAX_RECENT_TASKS) {
            mRecentTasks.remove(N-1);
        }
        mRecentTasks.add(0, task);
    }

    public void setRequestedOrientation(IBinder token,
            int requestedOrientation) {
        synchronized (this) {
            ActivityRecord r = mMainStack.isInStackLocked(token);
            if (r == null) {
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            mWindowManager.setAppOrientation(r, requestedOrientation);
            Configuration config = mWindowManager.updateOrientationFromAppTokens(
                    mConfiguration,
                    r.mayFreezeScreenLocked(r.app) ? r : null);
            if (config != null) {
                r.frozenBeforeDestroy = true;
                if (!updateConfigurationLocked(config, r, false)) {
                    mMainStack.resumeTopActivityLocked(null);
                }
            }
            Binder.restoreCallingIdentity(origId);
        }
    }

    public int getRequestedOrientation(IBinder token) {
        synchronized (this) {
            ActivityRecord r = mMainStack.isInStackLocked(token);
            if (r == null) {
                return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            }
            return mWindowManager.getAppOrientation(r);
        }
    }

    /**
     * This is the internal entry point for handling Activity.finish().
     * 
     * @param token The Binder token referencing the Activity we want to finish.
     * @param resultCode Result code, if any, from this Activity.
     * @param resultData Result data (Intent), if any, from this Activity.
     * 
     * @return Returns true if the activity successfully finished, or false if it is still running.
     */
    public final boolean finishActivity(IBinder token, int resultCode, Intent resultData) {
        // Refuse possible leaked file descriptors
        if (resultData != null && resultData.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            if (mController != null) {
                // Find the first activity that is not finishing.
                ActivityRecord next = mMainStack.topRunningActivityLocked(token, 0);
                if (next != null) {
                    // ask watcher if this is allowed
                    boolean resumeOK = true;
                    try {
                        resumeOK = mController.activityResuming(next.packageName);
                    } catch (RemoteException e) {
                        mController = null;
                    }
    
                    if (!resumeOK) {
                        return false;
                    }
                }
            }
            final long origId = Binder.clearCallingIdentity();
            boolean res = mMainStack.requestFinishActivityLocked(token, resultCode,
                    resultData, "app-request");
            Binder.restoreCallingIdentity(origId);
            return res;
        }
    }

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
        
        synchronized(this) {
            if (mHeavyWeightProcess == null) {
                return;
            }
            
            ArrayList<ActivityRecord> activities = new ArrayList<ActivityRecord>(
                    mHeavyWeightProcess.activities);
            for (int i=0; i<activities.size(); i++) {
                ActivityRecord r = activities.get(i);
                if (!r.finishing) {
                    int index = mMainStack.indexOfTokenLocked(r);
                    if (index >= 0) {
                        mMainStack.finishActivityLocked(r, index, Activity.RESULT_CANCELED,
                                null, "finish-heavy");
                    }
                }
            }
            
            mHeavyWeightProcess = null;
            mHandler.sendEmptyMessage(CANCEL_HEAVY_NOTIFICATION_MSG);
        }
    }
    
    public void crashApplication(int uid, int initialPid, String packageName,
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
            ProcessRecord proc = null;
            
            // Figure out which process to kill.  We don't trust that initialPid
            // still has any relation to current pids, so must scan through the
            // list.
            synchronized (mPidsSelfLocked) {
                for (int i=0; i<mPidsSelfLocked.size(); i++) {
                    ProcessRecord p = mPidsSelfLocked.valueAt(i);
                    if (p.info.uid != uid) {
                        continue;
                    }
                    if (p.pid == initialPid) {
                        proc = p;
                        break;
                    }
                    for (String str : p.pkgList) {
                        if (str.equals(packageName)) {
                            proc = p;
                        }
                    }
                }
            }
            
            if (proc == null) {
                Slog.w(TAG, "crashApplication: nothing for uid=" + uid
                        + " initialPid=" + initialPid
                        + " packageName=" + packageName);
                return;
            }
            
            if (proc.thread != null) {
                if (proc.pid == Process.myPid()) {
                    Log.w(TAG, "crashApplication: trying to crash self!");
                    return;
                }
                long ident = Binder.clearCallingIdentity();
                try {
                    proc.thread.scheduleCrash(message);
                } catch (RemoteException e) {
                }
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
    
    public final void finishSubActivity(IBinder token, String resultWho,
            int requestCode) {
        synchronized(this) {
            ActivityRecord self = mMainStack.isInStackLocked(token);
            if (self == null) {
                return;
            }

            final long origId = Binder.clearCallingIdentity();

            int i;
            for (i=mMainStack.mHistory.size()-1; i>=0; i--) {
                ActivityRecord r = (ActivityRecord)mMainStack.mHistory.get(i);
                if (r.resultTo == self && r.requestCode == requestCode) {
                    if ((r.resultWho == null && resultWho == null) ||
                        (r.resultWho != null && r.resultWho.equals(resultWho))) {
                        mMainStack.finishActivityLocked(r, i,
                                Activity.RESULT_CANCELED, null, "request-sub");
                    }
                }
            }

            Binder.restoreCallingIdentity(origId);
        }
    }

    public boolean willActivityBeVisible(IBinder token) {
        synchronized(this) {
            int i;
            for (i=mMainStack.mHistory.size()-1; i>=0; i--) {
                ActivityRecord r = (ActivityRecord)mMainStack.mHistory.get(i);
                if (r == token) {
                    return true;
                }
                if (r.fullscreen && !r.finishing) {
                    return false;
                }
            }
            return true;
        }
    }
    
    public void overridePendingTransition(IBinder token, String packageName,
            int enterAnim, int exitAnim) {
        synchronized(this) {
            ActivityRecord self = mMainStack.isInStackLocked(token);
            if (self == null) {
                return;
            }

            final long origId = Binder.clearCallingIdentity();
            
            if (self.state == ActivityState.RESUMED
                    || self.state == ActivityState.PAUSING) {
                mWindowManager.overridePendingAppTransition(packageName,
                        enterAnim, exitAnim);
            }
            
            Binder.restoreCallingIdentity(origId);
        }
    }
    
    /**
     * Main function for removing an existing process from the activity manager
     * as a result of that process going away.  Clears out all connections
     * to the process.
     */
    private final void handleAppDiedLocked(ProcessRecord app,
            boolean restarting, boolean allowRestart) {
        cleanUpApplicationRecordLocked(app, restarting, allowRestart, -1);
        if (!restarting) {
            mLruProcesses.remove(app);
        }

        // Just in case...
        if (mMainStack.mPausingActivity != null && mMainStack.mPausingActivity.app == app) {
            if (DEBUG_PAUSE) Slog.v(TAG, "App died while pausing: " +mMainStack.mPausingActivity);
            mMainStack.mPausingActivity = null;
        }
        if (mMainStack.mLastPausedActivity != null && mMainStack.mLastPausedActivity.app == app) {
            mMainStack.mLastPausedActivity = null;
        }

        // Remove this application's activities from active lists.
        mMainStack.removeHistoryRecordsForAppLocked(app);

        boolean atTop = true;
        boolean hasVisibleActivities = false;

        // Clean out the history list.
        int i = mMainStack.mHistory.size();
        if (localLOGV) Slog.v(
            TAG, "Removing app " + app + " from history with " + i + " entries");
        while (i > 0) {
            i--;
            ActivityRecord r = (ActivityRecord)mMainStack.mHistory.get(i);
            if (localLOGV) Slog.v(
                TAG, "Record #" + i + " " + r + ": app=" + r.app);
            if (r.app == app) {
                if ((!r.haveState && !r.stateNotNeeded) || r.finishing) {
                    if (localLOGV) Slog.v(
                        TAG, "Removing this entry!  frozen=" + r.haveState
                        + " finishing=" + r.finishing);
                    r.makeFinishing();
                    mMainStack.mHistory.remove(i);
                    r.takeFromHistory();
                    mWindowManager.removeAppToken(r);
                    if (VALIDATE_TOKENS) {
                        mWindowManager.validateAppTokens(mMainStack.mHistory);
                    }
                    r.removeUriPermissionsLocked();

                } else {
                    // We have the current state for this activity, so
                    // it can be restarted later when needed.
                    if (localLOGV) Slog.v(
                        TAG, "Keeping entry, setting app to null");
                    if (r.visible) {
                        hasVisibleActivities = true;
                    }
                    r.app = null;
                    r.nowVisible = false;
                    if (!r.haveState) {
                        r.icicle = null;
                    }
                }

                r.stack.cleanUpActivityLocked(r, true, true);
            }
            atTop = false;
        }

        app.activities.clear();
        
        if (app.instrumentationClass != null) {
            Slog.w(TAG, "Crash of app " + app.processName
                  + " running instrumentation " + app.instrumentationClass);
            Bundle info = new Bundle();
            info.putString("shortMsg", "Process crashed.");
            finishInstrumentationLocked(app, Activity.RESULT_CANCELED, info);
        }

        if (!restarting) {
            if (!mMainStack.resumeTopActivityLocked(null)) {
                // If there was nothing to resume, and we are not already
                // restarting this process, but there is a visible activity that
                // is hosted by the process...  then make sure all visible
                // activities are running, taking care of restarting this
                // process.
                if (hasVisibleActivities) {
                    mMainStack.ensureActivitiesVisibleLocked(null, 0);
                }
            }
        }
    }

    private final int getLRURecordIndexForAppLocked(IApplicationThread thread) {
        IBinder threadBinder = thread.asBinder();

        // Find the application record.
        for (int i=mLruProcesses.size()-1; i>=0; i--) {
            ProcessRecord rec = mLruProcesses.get(i);
            if (rec.thread != null && rec.thread.asBinder() == threadBinder) {
                return i;
            }
        }
        return -1;
    }

    final ProcessRecord getRecordForAppLocked(
            IApplicationThread thread) {
        if (thread == null) {
            return null;
        }

        int appIndex = getLRURecordIndexForAppLocked(thread);
        return appIndex >= 0 ? mLruProcesses.get(appIndex) : null;
    }

    final void appDiedLocked(ProcessRecord app, int pid,
            IApplicationThread thread) {

        mProcDeaths[0]++;
        
        BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
        synchronized (stats) {
            stats.noteProcessDiedLocked(app.info.uid, pid);
        }

        // Clean up already done if the process has been re-started.
        if (app.pid == pid && app.thread != null &&
                app.thread.asBinder() == thread.asBinder()) {
            if (!app.killedBackground) {
                Slog.i(TAG, "Process " + app.processName + " (pid " + pid
                        + ") has died.");
            }
            EventLog.writeEvent(EventLogTags.AM_PROC_DIED, app.pid, app.processName);
            if (localLOGV) Slog.v(
                TAG, "Dying app: " + app + ", pid: " + pid
                + ", thread: " + thread.asBinder());
            boolean doLowMem = app.instrumentationClass == null;
            handleAppDiedLocked(app, false, true);

            if (doLowMem) {
                // If there are no longer any background processes running,
                // and the app that died was not running instrumentation,
                // then tell everyone we are now low on memory.
                boolean haveBg = false;
                for (int i=mLruProcesses.size()-1; i>=0; i--) {
                    ProcessRecord rec = mLruProcesses.get(i);
                    if (rec.thread != null && rec.setAdj >= ProcessList.HIDDEN_APP_MIN_ADJ) {
                        haveBg = true;
                        break;
                    }
                }
                
                if (!haveBg) {
                    Slog.i(TAG, "Low Memory: No more background processes.");
                    EventLog.writeEvent(EventLogTags.AM_LOW_MEMORY, mLruProcesses.size());
                    long now = SystemClock.uptimeMillis();
                    for (int i=mLruProcesses.size()-1; i>=0; i--) {
                        ProcessRecord rec = mLruProcesses.get(i);
                        if (rec != app && rec.thread != null &&
                                (rec.lastLowMemory+GC_MIN_INTERVAL) <= now) {
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
                    scheduleAppGcsLocked();
                }
            }
        } else if (app.pid != pid) {
            // A new process has already been started.
            Slog.i(TAG, "Process " + app.processName + " (pid " + pid
                    + ") has died and restarted (pid " + app.pid + ").");
            EventLog.writeEvent(EventLogTags.AM_PROC_DIED, app.pid, app.processName);
        } else if (DEBUG_PROCESSES) {
            Slog.d(TAG, "Received spurious death notification for thread "
                    + thread.asBinder());
        }
    }

    /**
     * If a stack trace dump file is configured, dump process stack traces.
     * @param clearTraces causes the dump file to be erased prior to the new
     *    traces being written, if true; when false, the new traces will be
     *    appended to any existing file content.
     * @param firstPids of dalvik VM processes to dump stack traces for first
     * @param lastPids of dalvik VM processes to dump stack traces for last
     * @return file containing stack traces, or null if no dump file is configured
     */
    public static File dumpStackTraces(boolean clearTraces, ArrayList<Integer> firstPids,
            ProcessStats processStats, SparseArray<Boolean> lastPids) {
        String tracesPath = SystemProperties.get("dalvik.vm.stack-trace-file", null);
        if (tracesPath == null || tracesPath.length() == 0) {
            return null;
        }

        File tracesFile = new File(tracesPath);
        try {
            File tracesDir = tracesFile.getParentFile();
            if (!tracesDir.exists()) tracesFile.mkdirs();
            FileUtils.setPermissions(tracesDir.getPath(), 0775, -1, -1);  // drwxrwxr-x

            if (clearTraces && tracesFile.exists()) tracesFile.delete();
            tracesFile.createNewFile();
            FileUtils.setPermissions(tracesFile.getPath(), 0666, -1, -1); // -rw-rw-rw-
        } catch (IOException e) {
            Slog.w(TAG, "Unable to prepare ANR traces file: " + tracesPath, e);
            return null;
        }

        // Use a FileObserver to detect when traces finish writing.
        // The order of traces is considered important to maintain for legibility.
        FileObserver observer = new FileObserver(tracesPath, FileObserver.CLOSE_WRITE) {
            public synchronized void onEvent(int event, String path) { notify(); }
        };

        try {
            observer.startWatching();

            // First collect all of the stacks of the most important pids.
            try {
                int num = firstPids.size();
                for (int i = 0; i < num; i++) {
                    synchronized (observer) {
                        Process.sendSignal(firstPids.get(i), Process.SIGNAL_QUIT);
                        observer.wait(200);  // Wait for write-close, give up after 200msec
                    }
                }
            } catch (InterruptedException e) {
                Log.wtf(TAG, e);
            }

            // Next measure CPU usage.
            if (processStats != null) {
                processStats.init();
                System.gc();
                processStats.update();
                try {
                    synchronized (processStats) {
                        processStats.wait(500); // measure over 1/2 second.
                    }
                } catch (InterruptedException e) {
                }
                processStats.update();

                // We'll take the stack crawls of just the top apps using CPU.
                final int N = processStats.countWorkingStats();
                int numProcs = 0;
                for (int i=0; i<N && numProcs<5; i++) {
                    ProcessStats.Stats stats = processStats.getWorkingStats(i);
                    if (lastPids.indexOfKey(stats.pid) >= 0) {
                        numProcs++;
                        try {
                            synchronized (observer) {
                                Process.sendSignal(stats.pid, Process.SIGNAL_QUIT);
                                observer.wait(200);  // Wait for write-close, give up after 200msec
                            }
                        } catch (InterruptedException e) {
                            Log.wtf(TAG, e);
                        }

                    }
                }
            }

            return tracesFile;

        } finally {
            observer.stopWatching();
        }
    }

    private final class AppNotResponding implements Runnable {
        private final ProcessRecord mApp;
        private final String mAnnotation;

        public AppNotResponding(ProcessRecord app, String annotation) {
            mApp = app;
            mAnnotation = annotation;
        }

        @Override
        public void run() {
            appNotResponding(mApp, null, null, mAnnotation);
        }
    }

    final void appNotResponding(ProcessRecord app, ActivityRecord activity,
            ActivityRecord parent, final String annotation) {
        ArrayList<Integer> firstPids = new ArrayList<Integer>(5);
        SparseArray<Boolean> lastPids = new SparseArray<Boolean>(20);

        if (mController != null) {
            try {
                // 0 == continue, -1 = kill process immediately
                int res = mController.appEarlyNotResponding(app.processName, app.pid, annotation);
                if (res < 0 && app.pid != MY_PID) Process.killProcess(app.pid);
            } catch (RemoteException e) {
                mController = null;
            }
        }

        long anrTime = SystemClock.uptimeMillis();
        if (MONITOR_CPU_USAGE) {
            updateCpuStatsNow();
        }
        
        synchronized (this) {
            // PowerManager.reboot() can block for a long time, so ignore ANRs while shutting down.
            if (mShuttingDown) {
                Slog.i(TAG, "During shutdown skipping ANR: " + app + " " + annotation);
                return;
            } else if (app.notResponding) {
                Slog.i(TAG, "Skipping duplicate ANR: " + app + " " + annotation);
                return;
            } else if (app.crashing) {
                Slog.i(TAG, "Crashing app skipping ANR: " + app + " " + annotation);
                return;
            }
            
            // In case we come through here for the same app before completing
            // this one, mark as anring now so we will bail out.
            app.notResponding = true;

            // Log the ANR to the event log.
            EventLog.writeEvent(EventLogTags.AM_ANR, app.pid, app.processName, app.info.flags,
                    annotation);

            // Dump thread traces as quickly as we can, starting with "interesting" processes.
            firstPids.add(app.pid);
    
            int parentPid = app.pid;
            if (parent != null && parent.app != null && parent.app.pid > 0) parentPid = parent.app.pid;
            if (parentPid != app.pid) firstPids.add(parentPid);
    
            if (MY_PID != app.pid && MY_PID != parentPid) firstPids.add(MY_PID);

            for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
                ProcessRecord r = mLruProcesses.get(i);
                if (r != null && r.thread != null) {
                    int pid = r.pid;
                    if (pid > 0 && pid != app.pid && pid != parentPid && pid != MY_PID) {
                        if (r.persistent) {
                            firstPids.add(pid);
                        } else {
                            lastPids.put(pid, Boolean.TRUE);
                        }
                    }
                }
            }
        }

        // Log the ANR to the main log.
        StringBuilder info = mStringBuilder;
        info.setLength(0);
        info.append("ANR in ").append(app.processName);
        if (activity != null && activity.shortComponentName != null) {
            info.append(" (").append(activity.shortComponentName).append(")");
        }
        info.append("\n");
        if (annotation != null) {
            info.append("Reason: ").append(annotation).append("\n");
        }
        if (parent != null && parent != activity) {
            info.append("Parent: ").append(parent.shortComponentName).append("\n");
        }

        final ProcessStats processStats = new ProcessStats(true);

        File tracesFile = dumpStackTraces(true, firstPids, processStats, lastPids);

        String cpuInfo = null;
        if (MONITOR_CPU_USAGE) {
            updateCpuStatsNow();
            synchronized (mProcessStatsThread) {
                cpuInfo = mProcessStats.printCurrentState(anrTime);
            }
            info.append(processStats.printCurrentLoad());
            info.append(cpuInfo);
        }

        info.append(processStats.printCurrentState(anrTime));

        Slog.e(TAG, info.toString());
        if (tracesFile == null) {
            // There is no trace file, so dump (only) the alleged culprit's threads to the log
            Process.sendSignal(app.pid, Process.SIGNAL_QUIT);
        }

        addErrorToDropBox("anr", app, activity, parent, annotation, cpuInfo, tracesFile, null);

        if (mController != null) {
            try {
                // 0 == show dialog, 1 = keep waiting, -1 = kill process immediately
                int res = mController.appNotResponding(app.processName, app.pid, info.toString());
                if (res != 0) {
                    if (res < 0 && app.pid != MY_PID) Process.killProcess(app.pid);
                    return;
                }
            } catch (RemoteException e) {
                mController = null;
            }
        }

        // Unless configured otherwise, swallow ANRs in background processes & kill the process.
        boolean showBackground = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ANR_SHOW_BACKGROUND, 0) != 0;
        
        synchronized (this) {
            if (!showBackground && !app.isInterestingToUserLocked() && app.pid != MY_PID) {
                Slog.w(TAG, "Killing " + app + ": background ANR");
                EventLog.writeEvent(EventLogTags.AM_KILL, app.pid,
                        app.processName, app.setAdj, "background ANR");
                Process.killProcessQuiet(app.pid);
                return;
            }
    
            // Set the app's notResponding state, and look up the errorReportReceiver
            makeAppNotRespondingLocked(app,
                    activity != null ? activity.shortComponentName : null,
                    annotation != null ? "ANR " + annotation : "ANR",
                    info.toString());
    
            // Bring up the infamous App Not Responding dialog
            Message msg = Message.obtain();
            HashMap map = new HashMap();
            msg.what = SHOW_NOT_RESPONDING_MSG;
            msg.obj = map;
            map.put("app", app);
            if (activity != null) {
                map.put("activity", activity);
            }
    
            mHandler.sendMessage(msg);
        }
    }

    final void showLaunchWarningLocked(final ActivityRecord cur, final ActivityRecord next) {
        if (!mLaunchWarningShown) {
            mLaunchWarningShown = true;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    synchronized (ActivityManagerService.this) {
                        final Dialog d = new LaunchWarningWindow(mContext, cur, next);
                        d.show();
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                synchronized (ActivityManagerService.this) {
                                    d.dismiss();
                                    mLaunchWarningShown = false;
                                }
                            }
                        }, 4000);
                    }
                }
            });
        }
    }
    
    public boolean clearApplicationUserData(final String packageName,
            final IPackageDataObserver observer) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            int pkgUid = -1;
            synchronized(this) {
                try {
                    pkgUid = pm.getPackageUid(packageName);
                } catch (RemoteException e) {
                }
                if (pkgUid == -1) {
                    Slog.w(TAG, "Invalid packageName:" + packageName);
                    return false;
                }
                if (uid == pkgUid || checkComponentPermission(
                        android.Manifest.permission.CLEAR_APP_USER_DATA,
                        pid, uid, -1, true)
                        == PackageManager.PERMISSION_GRANTED) {
                    forceStopPackageLocked(packageName, pkgUid);
                } else {
                    throw new SecurityException(pid+" does not have permission:"+
                            android.Manifest.permission.CLEAR_APP_USER_DATA+" to clear data" +
                                    "for process:"+packageName);
                }
            }
            
            try {
                //clear application user data
                pm.clearApplicationUserData(packageName, observer);
                Intent intent = new Intent(Intent.ACTION_PACKAGE_DATA_CLEARED,
                        Uri.fromParts("package", packageName, null));
                intent.putExtra(Intent.EXTRA_UID, pkgUid);
                broadcastIntentInPackage("android", Process.SYSTEM_UID, intent,
                        null, null, 0, null, null, null, false, false);
            } catch (RemoteException e) {
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
        return true;
    }

    public void killBackgroundProcesses(final String packageName) {
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
        
        long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            int pkgUid = -1;
            synchronized(this) {
                try {
                    pkgUid = pm.getPackageUid(packageName);
                } catch (RemoteException e) {
                }
                if (pkgUid == -1) {
                    Slog.w(TAG, "Invalid packageName: " + packageName);
                    return;
                }
                killPackageProcessesLocked(packageName, pkgUid,
                        ProcessList.SECONDARY_SERVER_ADJ, false, true, true);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    public void forceStopPackage(final String packageName) {
        if (checkCallingPermission(android.Manifest.permission.FORCE_STOP_PACKAGES)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: forceStopPackage() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.FORCE_STOP_PACKAGES;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        
        long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            int pkgUid = -1;
            synchronized(this) {
                try {
                    pkgUid = pm.getPackageUid(packageName);
                } catch (RemoteException e) {
                }
                if (pkgUid == -1) {
                    Slog.w(TAG, "Invalid packageName: " + packageName);
                    return;
                }
                forceStopPackageLocked(packageName, pkgUid);
                try {
                    pm.setPackageStoppedState(packageName, true);
                } catch (RemoteException e) {
                } catch (IllegalArgumentException e) {
                    Slog.w(TAG, "Failed trying to unstop package "
                            + packageName + ": " + e);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    /*
     * The pkg name and uid have to be specified.
     * @see android.app.IActivityManager#killApplicationWithUid(java.lang.String, int)
     */
    public void killApplicationWithUid(String pkg, int uid) {
        if (pkg == null) {
            return;
        }
        // Make sure the uid is valid.
        if (uid < 0) {
            Slog.w(TAG, "Invalid uid specified for pkg : " + pkg);
            return;
        }
        int callerUid = Binder.getCallingUid();
        // Only the system server can kill an application
        if (callerUid == Process.SYSTEM_UID) {
            // Post an aysnc message to kill the application
            Message msg = mHandler.obtainMessage(KILL_APPLICATION_MSG);
            msg.arg1 = uid;
            msg.arg2 = 0;
            msg.obj = pkg;
            mHandler.sendMessage(msg);
        } else {
            throw new SecurityException(callerUid + " cannot kill pkg: " +
                    pkg);
        }
    }

    public void closeSystemDialogs(String reason) {
        Intent intent = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        if (reason != null) {
            intent.putExtra("reason", reason);
        }
        
        final int uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        synchronized (this) {
            int i = mWatchers.beginBroadcast();
            while (i > 0) {
                i--;
                IActivityWatcher w = mWatchers.getBroadcastItem(i);
                if (w != null) {
                    try {
                        w.closingSystemDialogs(reason);
                    } catch (RemoteException e) {
                    }
                }
            }
            mWatchers.finishBroadcast();
            
            mWindowManager.closeSystemDialogs(reason);
            
            for (i=mMainStack.mHistory.size()-1; i>=0; i--) {
                ActivityRecord r = (ActivityRecord)mMainStack.mHistory.get(i);
                if ((r.info.flags&ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS) != 0) {
                    r.stack.finishActivityLocked(r, i,
                            Activity.RESULT_CANCELED, null, "close-sys");
                }
            }
            
            broadcastIntentLocked(null, null, intent, null,
                    null, 0, null, null, null, false, false, -1, uid);
        }
        Binder.restoreCallingIdentity(origId);
    }
    
    public Debug.MemoryInfo[] getProcessMemoryInfo(int[] pids)
            throws RemoteException {
        Debug.MemoryInfo[] infos = new Debug.MemoryInfo[pids.length];
        for (int i=pids.length-1; i>=0; i--) {
            infos[i] = new Debug.MemoryInfo();
            Debug.getMemoryInfo(pids[i], infos[i]);
        }
        return infos;
    }

    public long[] getProcessPss(int[] pids) throws RemoteException {
        long[] pss = new long[pids.length];
        for (int i=pids.length-1; i>=0; i--) {
            pss[i] = Debug.getPss(pids[i]);
        }
        return pss;
    }

    public void killApplicationProcess(String processName, int uid) {
        if (processName == null) {
            return;
        }

        int callerUid = Binder.getCallingUid();
        // Only the system server can kill an application
        if (callerUid == Process.SYSTEM_UID) {
            synchronized (this) {
                ProcessRecord app = getProcessRecordLocked(processName, uid);
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

    private void forceStopPackageLocked(final String packageName, int uid) {
        forceStopPackageLocked(packageName, uid, false, false, true);
        Intent intent = new Intent(Intent.ACTION_PACKAGE_RESTARTED,
                Uri.fromParts("package", packageName, null));
        if (!mProcessesReady) {
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        }
        intent.putExtra(Intent.EXTRA_UID, uid);
        broadcastIntentLocked(null, null, intent,
                null, null, 0, null, null, null,
                false, false, MY_PID, Process.SYSTEM_UID);
    }
    
    private final boolean killPackageProcessesLocked(String packageName, int uid,
            int minOomAdj, boolean callerWillRestart, boolean allowRestart, boolean doit) {
        ArrayList<ProcessRecord> procs = new ArrayList<ProcessRecord>();

        // Remove all processes this package may have touched: all with the
        // same UID (except for the system or root user), and all whose name
        // matches the package name.
        final String procNamePrefix = packageName + ":";
        for (SparseArray<ProcessRecord> apps : mProcessNames.getMap().values()) {
            final int NA = apps.size();
            for (int ia=0; ia<NA; ia++) {
                ProcessRecord app = apps.valueAt(ia);
                if (app.persistent) {
                    // we don't kill persistent processes
                    continue;
                }
                if (app.removed) {
                    if (doit) {
                        procs.add(app);
                    }
                } else if ((uid > 0 && uid != Process.SYSTEM_UID && app.info.uid == uid)
                        || app.processName.equals(packageName)
                        || app.processName.startsWith(procNamePrefix)) {
                    if (app.setAdj >= minOomAdj) {
                        if (!doit) {
                            return true;
                        }
                        app.removed = true;
                        procs.add(app);
                    }
                }
            }
        }
        
        int N = procs.size();
        for (int i=0; i<N; i++) {
            removeProcessLocked(procs.get(i), callerWillRestart, allowRestart);
        }
        return N > 0;
    }

    private final boolean forceStopPackageLocked(String name, int uid,
            boolean callerWillRestart, boolean purgeCache, boolean doit) {
        int i;
        int N;

        if (uid < 0) {
            try {
                uid = AppGlobals.getPackageManager().getPackageUid(name);
            } catch (RemoteException e) {
            }
        }

        if (doit) {
            Slog.i(TAG, "Force stopping package " + name + " uid=" + uid);

            Iterator<SparseArray<Long>> badApps = mProcessCrashTimes.getMap().values().iterator();
            while (badApps.hasNext()) {
                SparseArray<Long> ba = badApps.next();
                if (ba.get(uid) != null) {
                    badApps.remove();
                }
            }
        }
        
        boolean didSomething = killPackageProcessesLocked(name, uid, -100,
                callerWillRestart, false, doit);
        
        for (i=mMainStack.mHistory.size()-1; i>=0; i--) {
            ActivityRecord r = (ActivityRecord)mMainStack.mHistory.get(i);
            if (r.packageName.equals(name)
                    && (r.app == null || !r.app.persistent)) {
                if (!doit) {
                    return true;
                }
                didSomething = true;
                Slog.i(TAG, "  Force finishing activity " + r);
                if (r.app != null) {
                    r.app.removed = true;
                }
                r.app = null;
                r.stack.finishActivityLocked(r, i, Activity.RESULT_CANCELED, null, "uninstall");
            }
        }

        ArrayList<ServiceRecord> services = new ArrayList<ServiceRecord>();
        for (ServiceRecord service : mServices.values()) {
            if (service.packageName.equals(name)
                    && (service.app == null || !service.app.persistent)) {
                if (!doit) {
                    return true;
                }
                didSomething = true;
                Slog.i(TAG, "  Force stopping service " + service);
                if (service.app != null) {
                    service.app.removed = true;
                }
                service.app = null;
                services.add(service);
            }
        }

        N = services.size();
        for (i=0; i<N; i++) {
            bringDownServiceLocked(services.get(i), true);
        }
        
        if (doit) {
            if (purgeCache) {
                AttributeCache ac = AttributeCache.instance();
                if (ac != null) {
                    ac.removePackage(name);
                }
            }
            mMainStack.resumeTopActivityLocked(null);
        }
        
        return didSomething;
    }

    private final boolean removeProcessLocked(ProcessRecord app,
            boolean callerWillRestart, boolean allowRestart) {
        final String name = app.processName;
        final int uid = app.info.uid;
        if (DEBUG_PROCESSES) Slog.d(
            TAG, "Force removing process " + app + " (" + name
            + "/" + uid + ")");

        mProcessNames.remove(name, uid);
        if (mHeavyWeightProcess == app) {
            mHeavyWeightProcess = null;
            mHandler.sendEmptyMessage(CANCEL_HEAVY_NOTIFICATION_MSG);
        }
        boolean needRestart = false;
        if (app.pid > 0 && app.pid != MY_PID) {
            int pid = app.pid;
            synchronized (mPidsSelfLocked) {
                mPidsSelfLocked.remove(pid);
                mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);
            }
            handleAppDiedLocked(app, true, allowRestart);
            mLruProcesses.remove(app);
            Process.killProcess(pid);
            
            if (app.persistent) {
                if (!callerWillRestart) {
                    addAppLocked(app.info);
                } else {
                    needRestart = true;
                }
            }
        } else {
            mRemovedProcesses.add(app);
        }
        
        return needRestart;
    }

    private final void processStartTimedOutLocked(ProcessRecord app) {
        final int pid = app.pid;
        boolean gone = false;
        synchronized (mPidsSelfLocked) {
            ProcessRecord knownApp = mPidsSelfLocked.get(pid);
            if (knownApp != null && knownApp.thread == null) {
                mPidsSelfLocked.remove(pid);
                gone = true;
            }        
        }
        
        if (gone) {
            Slog.w(TAG, "Process " + app + " failed to attach");
            EventLog.writeEvent(EventLogTags.AM_PROCESS_START_TIMEOUT, pid, app.info.uid,
                    app.processName);
            mProcessNames.remove(app.processName, app.info.uid);
            if (mHeavyWeightProcess == app) {
                mHeavyWeightProcess = null;
                mHandler.sendEmptyMessage(CANCEL_HEAVY_NOTIFICATION_MSG);
            }
            // Take care of any launching providers waiting for this process.
            checkAppInLaunchingProvidersLocked(app, true);
            // Take care of any services that are waiting for the process.
            for (int i=0; i<mPendingServices.size(); i++) {
                ServiceRecord sr = mPendingServices.get(i);
                if (app.info.uid == sr.appInfo.uid
                        && app.processName.equals(sr.processName)) {
                    Slog.w(TAG, "Forcing bringing down service: " + sr);
                    mPendingServices.remove(i);
                    i--;
                    bringDownServiceLocked(sr, true);
                }
            }
            EventLog.writeEvent(EventLogTags.AM_KILL, pid,
                    app.processName, app.setAdj, "start timeout");
            Process.killProcessQuiet(pid);
            if (mBackupTarget != null && mBackupTarget.app.pid == pid) {
                Slog.w(TAG, "Unattached app died before backup, skipping");
                try {
                    IBackupManager bm = IBackupManager.Stub.asInterface(
                            ServiceManager.getService(Context.BACKUP_SERVICE));
                    bm.agentDisconnected(app.info.packageName);
                } catch (RemoteException e) {
                    // Can't happen; the backup manager is local
                }
            }
            if (mPendingBroadcast != null && mPendingBroadcast.curApp.pid == pid) {
                Slog.w(TAG, "Unattached app died before broadcast acknowledged, skipping");
                mPendingBroadcast.state = BroadcastRecord.IDLE;
                mPendingBroadcast.nextReceiver = mPendingBroadcastRecvIndex;
                mPendingBroadcast = null;
                scheduleBroadcastsLocked();
            }
        } else {
            Slog.w(TAG, "Spurious process start timeout - pid not known for " + app);
        }
    }

    private final boolean attachApplicationLocked(IApplicationThread thread,
            int pid) {

        // Find the application record that is being attached...  either via
        // the pid if we are running in multiple processes, or just pull the
        // next app record if we are emulating process with anonymous threads.
        ProcessRecord app;
        if (pid != MY_PID && pid >= 0) {
            synchronized (mPidsSelfLocked) {
                app = mPidsSelfLocked.get(pid);
            }
        } else {
            app = null;
        }

        if (app == null) {
            Slog.w(TAG, "No pending application record for pid " + pid
                    + " (IApplicationThread " + thread + "); dropping process");
            EventLog.writeEvent(EventLogTags.AM_DROP_PROCESS, pid);
            if (pid > 0 && pid != MY_PID) {
                Process.killProcessQuiet(pid);
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

        if (localLOGV) Slog.v(
                TAG, "Binding process pid " + pid + " to record " + app);

        String processName = app.processName;
        try {
            AppDeathRecipient adr = new AppDeathRecipient(
                    app, pid, thread);
            thread.asBinder().linkToDeath(adr, 0);
            app.deathRecipient = adr;
        } catch (RemoteException e) {
            app.resetPackageList();
            startProcessLocked(app, "link fail", processName);
            return false;
        }

        EventLog.writeEvent(EventLogTags.AM_PROC_BOUND, app.pid, app.processName);
        
        app.thread = thread;
        app.curAdj = app.setAdj = -100;
        app.curSchedGroup = Process.THREAD_GROUP_DEFAULT;
        app.setSchedGroup = Process.THREAD_GROUP_BG_NONINTERACTIVE;
        app.forcingToForeground = null;
        app.foregroundServices = false;
        app.hasShownUi = false;
        app.debugging = false;

        mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);

        boolean normalMode = mProcessesReady || isAllowedWhileBooting(app.info);
        List providers = normalMode ? generateApplicationProvidersLocked(app) : null;

        if (!normalMode) {
            Slog.i(TAG, "Launching preboot mode app: " + app);
        }
        
        if (localLOGV) Slog.v(
            TAG, "New app record " + app
            + " thread=" + thread.asBinder() + " pid=" + pid);
        try {
            int testMode = IApplicationThread.DEBUG_OFF;
            if (mDebugApp != null && mDebugApp.equals(processName)) {
                testMode = mWaitForDebugger
                    ? IApplicationThread.DEBUG_WAIT
                    : IApplicationThread.DEBUG_ON;
                app.debugging = true;
                if (mDebugTransient) {
                    mDebugApp = mOrigDebugApp;
                    mWaitForDebugger = mOrigWaitForDebugger;
                }
            }
            
            // If the app is being launched for restore or full backup, set it up specially
            boolean isRestrictedBackupMode = false;
            if (mBackupTarget != null && mBackupAppName.equals(processName)) {
                isRestrictedBackupMode = (mBackupTarget.backupMode == BackupRecord.RESTORE)
                        || (mBackupTarget.backupMode == BackupRecord.RESTORE_FULL)
                        || (mBackupTarget.backupMode == BackupRecord.BACKUP_FULL);
            }
            
            ensurePackageDexOpt(app.instrumentationInfo != null
                    ? app.instrumentationInfo.packageName
                    : app.info.packageName);
            if (app.instrumentationClass != null) {
                ensurePackageDexOpt(app.instrumentationClass.getPackageName());
            }
            if (DEBUG_CONFIGURATION) Slog.v(TAG, "Binding proc "
                    + processName + " with config " + mConfiguration);
            ApplicationInfo appInfo = app.instrumentationInfo != null
                    ? app.instrumentationInfo : app.info;
            app.compat = compatibilityInfoForPackageLocked(appInfo);
            thread.bindApplication(processName, appInfo, providers,
                    app.instrumentationClass, app.instrumentationProfileFile,
                    app.instrumentationArguments, app.instrumentationWatcher, testMode, 
                    isRestrictedBackupMode || !normalMode,
                    mConfiguration, app.compat, getCommonServicesLocked(),
                    mCoreSettingsObserver.getCoreSettingsLocked());
            updateLruProcessLocked(app, false, true);
            app.lastRequestedGc = app.lastLowMemory = SystemClock.uptimeMillis();
        } catch (Exception e) {
            // todo: Yikes!  What should we do?  For now we will try to
            // start another process, but that could easily get us in
            // an infinite loop of restarting processes...
            Slog.w(TAG, "Exception thrown during bind!", e);

            app.resetPackageList();
            app.unlinkDeathRecipient();
            startProcessLocked(app, "bind fail", processName);
            return false;
        }

        // Remove this record from the list of starting applications.
        mPersistentStartingProcesses.remove(app);
        if (DEBUG_PROCESSES && mProcessesOnHold.contains(app)) Slog.v(TAG,
                "Attach application locked removing on hold: " + app);
        mProcessesOnHold.remove(app);

        boolean badApp = false;
        boolean didSomething = false;

        // See if the top visible activity is waiting to run in this process...
        ActivityRecord hr = mMainStack.topRunningActivityLocked(null);
        if (hr != null && normalMode) {
            if (hr.app == null && app.info.uid == hr.info.applicationInfo.uid
                    && processName.equals(hr.processName)) {
                try {
                    if (mMainStack.realStartActivityLocked(hr, app, true, true)) {
                        didSomething = true;
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Exception in new application when starting activity "
                          + hr.intent.getComponent().flattenToShortString(), e);
                    badApp = true;
                }
            } else {
                mMainStack.ensureActivitiesVisibleLocked(hr, null, processName, 0);
            }
        }

        // Find any services that should be running in this process...
        if (!badApp && mPendingServices.size() > 0) {
            ServiceRecord sr = null;
            try {
                for (int i=0; i<mPendingServices.size(); i++) {
                    sr = mPendingServices.get(i);
                    if (app.info.uid != sr.appInfo.uid
                            || !processName.equals(sr.processName)) {
                        continue;
                    }

                    mPendingServices.remove(i);
                    i--;
                    realStartServiceLocked(sr, app);
                    didSomething = true;
                }
            } catch (Exception e) {
                Slog.w(TAG, "Exception in new application when starting service "
                      + sr.shortName, e);
                badApp = true;
            }
        }

        // Check if the next broadcast receiver is in this process...
        BroadcastRecord br = mPendingBroadcast;
        if (!badApp && br != null && br.curApp == app) {
            try {
                mPendingBroadcast = null;
                processCurBroadcastLocked(br, app);
                didSomething = true;
            } catch (Exception e) {
                Slog.w(TAG, "Exception in new application when starting receiver "
                      + br.curComponent.flattenToShortString(), e);
                badApp = true;
                logBroadcastReceiverDiscardLocked(br);
                finishReceiverLocked(br.receiver, br.resultCode, br.resultData,
                        br.resultExtras, br.resultAbort, true);
                scheduleBroadcastsLocked();
                // We need to reset the state if we fails to start the receiver.
                br.state = BroadcastRecord.IDLE;
            }
        }

        // Check whether the next backup agent is in this process...
        if (!badApp && mBackupTarget != null && mBackupTarget.appInfo.uid == app.info.uid) {
            if (DEBUG_BACKUP) Slog.v(TAG, "New app is backup target, launching agent for " + app);
            ensurePackageDexOpt(mBackupTarget.appInfo.packageName);
            try {
                thread.scheduleCreateBackupAgent(mBackupTarget.appInfo,
                        compatibilityInfoForPackageLocked(mBackupTarget.appInfo),
                        mBackupTarget.backupMode);
            } catch (Exception e) {
                Slog.w(TAG, "Exception scheduling backup agent creation: ");
                e.printStackTrace();
            }
        }

        if (badApp) {
            // todo: Also need to kill application to deal with all
            // kinds of exceptions.
            handleAppDiedLocked(app, false, true);
            return false;
        }

        if (!didSomething) {
            updateOomAdjLocked();
        }

        return true;
    }

    public final void attachApplication(IApplicationThread thread) {
        synchronized (this) {
            int callingPid = Binder.getCallingPid();
            final long origId = Binder.clearCallingIdentity();
            attachApplicationLocked(thread, callingPid);
            Binder.restoreCallingIdentity(origId);
        }
    }

    public final void activityIdle(IBinder token, Configuration config) {
        final long origId = Binder.clearCallingIdentity();
        mMainStack.activityIdleInternal(token, false, config);
        Binder.restoreCallingIdentity(origId);
    }

    void enableScreenAfterBoot() {
        EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_ENABLE_SCREEN,
                SystemClock.uptimeMillis());
        mWindowManager.enableScreenAfterBoot();
    }

    final void finishBooting() {
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
                          if (forceStopPackageLocked(pkg, -1, false, false, false)) {
                              setResultCode(Activity.RESULT_OK);
                              return;
                          }
                       }
                    }
                }
            }
        }, pkgFilter);
        
        synchronized (this) {
            // Ensure that any processes we had put on hold are now started
            // up.
            final int NP = mProcessesOnHold.size();
            if (NP > 0) {
                ArrayList<ProcessRecord> procs =
                    new ArrayList<ProcessRecord>(mProcessesOnHold);
                for (int ip=0; ip<NP; ip++) {
                    if (DEBUG_PROCESSES) Slog.v(TAG, "Starting process on hold: "
                            + procs.get(ip));
                    startProcessLocked(procs.get(ip), "on-hold", null);
                }
            }
            
            if (mFactoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL) {
                // Start looking for apps that are abusing wake locks.
                Message nmsg = mHandler.obtainMessage(CHECK_EXCESSIVE_WAKE_LOCKS_MSG);
                mHandler.sendMessageDelayed(nmsg, POWER_CHECK_DELAY);
                // Tell anyone interested that we are done booting!
                SystemProperties.set("sys.boot_completed", "1");
                broadcastIntentLocked(null, null,
                        new Intent(Intent.ACTION_BOOT_COMPLETED, null),
                        null, null, 0, null, null,
                        android.Manifest.permission.RECEIVE_BOOT_COMPLETED,
                        false, false, MY_PID, Process.SYSTEM_UID);
            }
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
            enableScreenAfterBoot();
        }
    }
    
    public final void activityPaused(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        mMainStack.activityPaused(token, false);
        Binder.restoreCallingIdentity(origId);
    }

    public final void activityStopped(IBinder token, Bundle icicle, Bitmap thumbnail,
            CharSequence description) {
        if (localLOGV) Slog.v(
            TAG, "Activity stopped: token=" + token);

        // Refuse possible leaked file descriptors
        if (icicle != null && icicle.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Bundle");
        }

        ActivityRecord r = null;

        final long origId = Binder.clearCallingIdentity();

        synchronized (this) {
            r = mMainStack.isInStackLocked(token);
            if (r != null) {
                r.stack.activityStoppedLocked(r, icicle, thumbnail, description);
            }
        }

        if (r != null) {
            sendPendingThumbnail(r, null, null, null, false);
        }

        trimApplications();

        Binder.restoreCallingIdentity(origId);
    }

    public final void activityDestroyed(IBinder token) {
        if (DEBUG_SWITCH) Slog.v(TAG, "ACTIVITY DESTROYED: " + token);
        mMainStack.activityDestroyed(token);
    }
    
    public String getCallingPackage(IBinder token) {
        synchronized (this) {
            ActivityRecord r = getCallingRecordLocked(token);
            return r != null && r.app != null ? r.info.packageName : null;
        }
    }

    public ComponentName getCallingActivity(IBinder token) {
        synchronized (this) {
            ActivityRecord r = getCallingRecordLocked(token);
            return r != null ? r.intent.getComponent() : null;
        }
    }

    private ActivityRecord getCallingRecordLocked(IBinder token) {
        ActivityRecord r = mMainStack.isInStackLocked(token);
        if (r == null) {
            return null;
        }
        return r.resultTo;
    }

    public ComponentName getActivityClassForToken(IBinder token) {
        synchronized(this) {
            ActivityRecord r = mMainStack.isInStackLocked(token);
            if (r == null) {
                return null;
            }
            return r.intent.getComponent();
        }
    }

    public String getPackageForToken(IBinder token) {
        synchronized(this) {
            ActivityRecord r = mMainStack.isInStackLocked(token);
            if (r == null) {
                return null;
            }
            return r.packageName;
        }
    }

    public IIntentSender getIntentSender(int type,
            String packageName, IBinder token, String resultWho,
            int requestCode, Intent[] intents, String[] resolvedTypes, int flags) {
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
                    if (type == INTENT_SENDER_BROADCAST &&
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
        
        synchronized(this) {
            int callingUid = Binder.getCallingUid();
            try {
                if (callingUid != 0 && callingUid != Process.SYSTEM_UID) {
                    int uid = AppGlobals.getPackageManager()
                            .getPackageUid(packageName);
                    if (uid != Binder.getCallingUid()) {
                        String msg = "Permission Denial: getIntentSender() from pid="
                            + Binder.getCallingPid()
                            + ", uid=" + Binder.getCallingUid()
                            + ", (need uid=" + uid + ")"
                            + " is not allowed to send as package " + packageName;
                        Slog.w(TAG, msg);
                        throw new SecurityException(msg);
                    }
                }
                
                return getIntentSenderLocked(type, packageName, callingUid,
                        token, resultWho, requestCode, intents, resolvedTypes, flags);
                
            } catch (RemoteException e) {
                throw new SecurityException(e);
            }
        }
    }
    
    IIntentSender getIntentSenderLocked(int type,
            String packageName, int callingUid, IBinder token, String resultWho,
            int requestCode, Intent[] intents, String[] resolvedTypes, int flags) {
        ActivityRecord activity = null;
        if (type == INTENT_SENDER_ACTIVITY_RESULT) {
            activity = mMainStack.isInStackLocked(token);
            if (activity == null) {
                return null;
            }
            if (activity.finishing) {
                return null;
            }
        }

        final boolean noCreate = (flags&PendingIntent.FLAG_NO_CREATE) != 0;
        final boolean cancelCurrent = (flags&PendingIntent.FLAG_CANCEL_CURRENT) != 0;
        final boolean updateCurrent = (flags&PendingIntent.FLAG_UPDATE_CURRENT) != 0;
        flags &= ~(PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_CANCEL_CURRENT
                |PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntentRecord.Key key = new PendingIntentRecord.Key(
                type, packageName, activity, resultWho,
                requestCode, intents, resolvedTypes, flags);
        WeakReference<PendingIntentRecord> ref;
        ref = mIntentSenderRecords.get(key);
        PendingIntentRecord rec = ref != null ? ref.get() : null;
        if (rec != null) {
            if (!cancelCurrent) {
                if (updateCurrent) {
                    if (rec.key.requestIntent != null) {
                        rec.key.requestIntent.replaceExtras(intents != null ? intents[0] : null);
                    }
                    if (intents != null) {
                        intents[intents.length-1] = rec.key.requestIntent;
                        rec.key.allIntents = intents;
                        rec.key.allResolvedTypes = resolvedTypes;
                    } else {
                        rec.key.allIntents = null;
                        rec.key.allResolvedTypes = null;
                    }
                }
                return rec;
            }
            rec.canceled = true;
            mIntentSenderRecords.remove(key);
        }
        if (noCreate) {
            return rec;
        }
        rec = new PendingIntentRecord(this, key, callingUid);
        mIntentSenderRecords.put(key, rec.ref);
        if (type == INTENT_SENDER_ACTIVITY_RESULT) {
            if (activity.pendingResults == null) {
                activity.pendingResults
                        = new HashSet<WeakReference<PendingIntentRecord>>();
            }
            activity.pendingResults.add(rec.ref);
        }
        return rec;
    }

    public void cancelIntentSender(IIntentSender sender) {
        if (!(sender instanceof PendingIntentRecord)) {
            return;
        }
        synchronized(this) {
            PendingIntentRecord rec = (PendingIntentRecord)sender;
            try {
                int uid = AppGlobals.getPackageManager()
                        .getPackageUid(rec.key.packageName);
                if (uid != Binder.getCallingUid()) {
                    String msg = "Permission Denial: cancelIntentSender() from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " is not allowed to cancel packges "
                        + rec.key.packageName;
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                }
            } catch (RemoteException e) {
                throw new SecurityException(e);
            }
            cancelIntentSenderLocked(rec, true);
        }
    }

    void cancelIntentSenderLocked(PendingIntentRecord rec, boolean cleanActivity) {
        rec.canceled = true;
        mIntentSenderRecords.remove(rec.key);
        if (cleanActivity && rec.key.activity != null) {
            rec.key.activity.pendingResults.remove(rec.ref);
        }
    }

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

    public void setProcessLimit(int max) {
        enforceCallingPermission(android.Manifest.permission.SET_PROCESS_LIMIT,
                "setProcessLimit()");
        synchronized (this) {
            mProcessLimit = max < 0 ? ProcessList.MAX_HIDDEN_APPS : max;
            mProcessLimitOverride = max;
        }
        trimApplications();
    }

    public int getProcessLimit() {
        synchronized (this) {
            return mProcessLimitOverride;
        }
    }

    void foregroundTokenDied(ForegroundToken token) {
        synchronized (ActivityManagerService.this) {
            synchronized (mPidsSelfLocked) {
                ForegroundToken cur
                    = mForegroundProcesses.get(token.pid);
                if (cur != token) {
                    return;
                }
                mForegroundProcesses.remove(token.pid);
                ProcessRecord pr = mPidsSelfLocked.get(token.pid);
                if (pr == null) {
                    return;
                }
                pr.forcingToForeground = null;
                pr.foregroundServices = false;
            }
            updateOomAdjLocked();
        }
    }
    
    public void setProcessForeground(IBinder token, int pid, boolean isForeground) {
        enforceCallingPermission(android.Manifest.permission.SET_PROCESS_LIMIT,
                "setProcessForeground()");
        synchronized(this) {
            boolean changed = false;
            
            synchronized (mPidsSelfLocked) {
                ProcessRecord pr = mPidsSelfLocked.get(pid);
                if (pr == null) {
                    Slog.w(TAG, "setProcessForeground called on unknown pid: " + pid);
                    return;
                }
                ForegroundToken oldToken = mForegroundProcesses.get(pid);
                if (oldToken != null) {
                    oldToken.token.unlinkToDeath(oldToken, 0);
                    mForegroundProcesses.remove(pid);
                    pr.forcingToForeground = null;
                    changed = true;
                }
                if (isForeground && token != null) {
                    ForegroundToken newToken = new ForegroundToken() {
                        public void binderDied() {
                            foregroundTokenDied(this);
                        }
                    };
                    newToken.pid = pid;
                    newToken.token = token;
                    try {
                        token.linkToDeath(newToken, 0);
                        mForegroundProcesses.put(pid, newToken);
                        pr.forcingToForeground = token;
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
    
    // =========================================================
    // PERMISSIONS
    // =========================================================

    static class PermissionController extends IPermissionController.Stub {
        ActivityManagerService mActivityManagerService;
        PermissionController(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        public boolean checkPermission(String permission, int pid, int uid) {
            return mActivityManagerService.checkPermission(permission, pid,
                    uid) == PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * This can be called with or without the global lock held.
     */
    int checkComponentPermission(String permission, int pid, int uid,
            int owningUid, boolean exported) {
        // We might be performing an operation on behalf of an indirect binder
        // invocation, e.g. via {@link #openContentUri}.  Check and adjust the
        // client identity accordingly before proceeding.
        Identity tlsIdentity = sCallerIdentity.get();
        if (tlsIdentity != null) {
            Slog.d(TAG, "checkComponentPermission() adjusting {pid,uid} to {"
                    + tlsIdentity.pid + "," + tlsIdentity.uid + "}");
            uid = tlsIdentity.uid;
            pid = tlsIdentity.pid;
        }

        // Root, system server and our own process get to do everything.
        if (uid == 0 || uid == Process.SYSTEM_UID || pid == MY_PID) {
            return PackageManager.PERMISSION_GRANTED;
        }
        // If there is a uid that owns whatever is being accessed, it has
        // blanket access to it regardless of the permissions it requires.
        if (owningUid >= 0 && uid == owningUid) {
            return PackageManager.PERMISSION_GRANTED;
        }
        // If the target is not exported, then nobody else can get to it.
        if (!exported) {
            Slog.w(TAG, "Permission denied: checkComponentPermission() owningUid=" + owningUid);
            return PackageManager.PERMISSION_DENIED;
        }
        if (permission == null) {
            return PackageManager.PERMISSION_GRANTED;
        }
        try {
            return AppGlobals.getPackageManager()
                    .checkUidPermission(permission, uid);
        } catch (RemoteException e) {
            // Should never happen, but if it does... deny!
            Slog.e(TAG, "PackageManager is dead?!?", e);
        }
        return PackageManager.PERMISSION_DENIED;
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
    public int checkPermission(String permission, int pid, int uid) {
        if (permission == null) {
            return PackageManager.PERMISSION_DENIED;
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

    private final boolean checkHoldingPermissionsLocked(IPackageManager pm,
            ProviderInfo pi, Uri uri, int uid, int modeFlags) {
        boolean readPerm = (modeFlags&Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0;
        boolean writePerm = (modeFlags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0;
        if (DEBUG_URI_PERMISSION) Slog.v(TAG,
                "checkHoldingPermissionsLocked: uri=" + uri + " uid=" + uid);
        try {
            // Is the component private from the target uid?
            final boolean prv = !pi.exported && pi.applicationInfo.uid != uid;

            // Acceptable if the there is no read permission needed from the
            // target or the target is holding the read permission.
            if (!readPerm) {
                if ((!prv && pi.readPermission == null) ||
                        (pm.checkUidPermission(pi.readPermission, uid)
                                == PackageManager.PERMISSION_GRANTED)) {
                    readPerm = true;
                }
            }

            // Acceptable if the there is no write permission needed from the
            // target or the target is holding the read permission.
            if (!writePerm) {
                if (!prv && (pi.writePermission == null) ||
                        (pm.checkUidPermission(pi.writePermission, uid)
                                == PackageManager.PERMISSION_GRANTED)) {
                    writePerm = true;
                }
            }

            // Acceptable if there is a path permission matching the URI that
            // the target holds the permission on.
            PathPermission[] pps = pi.pathPermissions;
            if (pps != null && (!readPerm || !writePerm)) {
                final String path = uri.getPath();
                int i = pps.length;
                while (i > 0 && (!readPerm || !writePerm)) {
                    i--;
                    PathPermission pp = pps[i];
                    if (!readPerm) {
                        final String pprperm = pp.getReadPermission();
                        if (DEBUG_URI_PERMISSION) Slog.v(TAG, "Checking read perm for "
                                + pprperm + " for " + pp.getPath()
                                + ": match=" + pp.match(path)
                                + " check=" + pm.checkUidPermission(pprperm, uid));
                        if (pprperm != null && pp.match(path) &&
                                (pm.checkUidPermission(pprperm, uid)
                                        == PackageManager.PERMISSION_GRANTED)) {
                            readPerm = true;
                        }
                    }
                    if (!writePerm) {
                        final String ppwperm = pp.getWritePermission();
                        if (DEBUG_URI_PERMISSION) Slog.v(TAG, "Checking write perm "
                                + ppwperm + " for " + pp.getPath()
                                + ": match=" + pp.match(path)
                                + " check=" + pm.checkUidPermission(ppwperm, uid));
                        if (ppwperm != null && pp.match(path) &&
                                (pm.checkUidPermission(ppwperm, uid)
                                        == PackageManager.PERMISSION_GRANTED)) {
                            writePerm = true;
                        }
                    }
                }
            }
        } catch (RemoteException e) {
            return false;
        }

        return readPerm && writePerm;
    }

    private final boolean checkUriPermissionLocked(Uri uri, int uid,
            int modeFlags) {
        // Root gets to do everything.
        if (uid == 0) {
            return true;
        }
        HashMap<Uri, UriPermission> perms = mGrantedUriPermissions.get(uid);
        if (perms == null) return false;
        UriPermission perm = perms.get(uri);
        if (perm == null) return false;
        return (modeFlags&perm.modeFlags) == modeFlags;
    }

    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags) {
        // Another redirected-binder-call permissions check as in
        // {@link checkComponentPermission}.
        Identity tlsIdentity = sCallerIdentity.get();
        if (tlsIdentity != null) {
            uid = tlsIdentity.uid;
            pid = tlsIdentity.pid;
        }

        // Our own process gets to do everything.
        if (pid == MY_PID) {
            return PackageManager.PERMISSION_GRANTED;
        }
        synchronized(this) {
            return checkUriPermissionLocked(uri, uid, modeFlags)
                    ? PackageManager.PERMISSION_GRANTED
                    : PackageManager.PERMISSION_DENIED;
        }
    }

    /**
     * Check if the targetPkg can be granted permission to access uri by
     * the callingUid using the given modeFlags.  Throws a security exception
     * if callingUid is not allowed to do this.  Returns the uid of the target
     * if the URI permission grant should be performed; returns -1 if it is not
     * needed (for example targetPkg already has permission to access the URI).
     */
    int checkGrantUriPermissionLocked(int callingUid, String targetPkg,
            Uri uri, int modeFlags) {
        modeFlags &= (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (modeFlags == 0) {
            return -1;
        }

        if (targetPkg != null) {
            if (DEBUG_URI_PERMISSION) Slog.v(TAG,
                    "Checking grant " + targetPkg + " permission to " + uri);
        }
        
        final IPackageManager pm = AppGlobals.getPackageManager();

        // If this is not a content: uri, we can't do anything with it.
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            if (DEBUG_URI_PERMISSION) Slog.v(TAG, 
                    "Can't grant URI permission for non-content URI: " + uri);
            return -1;
        }

        String name = uri.getAuthority();
        ProviderInfo pi = null;
        ContentProviderRecord cpr = mProvidersByName.get(name);
        if (cpr != null) {
            pi = cpr.info;
        } else {
            try {
                pi = pm.resolveContentProvider(name,
                        PackageManager.GET_URI_PERMISSION_PATTERNS);
            } catch (RemoteException ex) {
            }
        }
        if (pi == null) {
            Slog.w(TAG, "No content provider found for: " + name);
            return -1;
        }

        int targetUid;
        if (targetPkg != null) {
            try {
                targetUid = pm.getPackageUid(targetPkg);
                if (targetUid < 0) {
                    if (DEBUG_URI_PERMISSION) Slog.v(TAG,
                            "Can't grant URI permission no uid for: " + targetPkg);
                    return -1;
                }
            } catch (RemoteException ex) {
                return -1;
            }
        } else {
            targetUid = -1;
        }

        if (targetUid >= 0) {
            // First...  does the target actually need this permission?
            if (checkHoldingPermissionsLocked(pm, pi, uri, targetUid, modeFlags)) {
                // No need to grant the target this permission.
                if (DEBUG_URI_PERMISSION) Slog.v(TAG,
                        "Target " + targetPkg + " already has full permission to " + uri);
                return -1;
            }
        } else {
            // First...  there is no target package, so can anyone access it?
            boolean allowed = pi.exported;
            if ((modeFlags&Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                if (pi.readPermission != null) {
                    allowed = false;
                }
            }
            if ((modeFlags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                if (pi.writePermission != null) {
                    allowed = false;
                }
            }
            if (allowed) {
                return -1;
            }
        }

        // Second...  is the provider allowing granting of URI permissions?
        if (!pi.grantUriPermissions) {
            throw new SecurityException("Provider " + pi.packageName
                    + "/" + pi.name
                    + " does not allow granting of Uri permissions (uri "
                    + uri + ")");
        }
        if (pi.uriPermissionPatterns != null) {
            final int N = pi.uriPermissionPatterns.length;
            boolean allowed = false;
            for (int i=0; i<N; i++) {
                if (pi.uriPermissionPatterns[i] != null
                        && pi.uriPermissionPatterns[i].match(uri.getPath())) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw new SecurityException("Provider " + pi.packageName
                        + "/" + pi.name
                        + " does not allow granting of permission to path of Uri "
                        + uri);
            }
        }

        // Third...  does the caller itself have permission to access
        // this uri?
        if (callingUid != Process.myUid()) {
            if (!checkHoldingPermissionsLocked(pm, pi, uri, callingUid, modeFlags)) {
                if (!checkUriPermissionLocked(uri, callingUid, modeFlags)) {
                    throw new SecurityException("Uid " + callingUid
                            + " does not have permission to uri " + uri);
                }
            }
        }

        return targetUid;
    }

    public int checkGrantUriPermission(int callingUid, String targetPkg,
            Uri uri, int modeFlags) {
        synchronized(this) {
            return checkGrantUriPermissionLocked(callingUid, targetPkg, uri, modeFlags);
        }
    }

    void grantUriPermissionUncheckedLocked(int targetUid, String targetPkg,
            Uri uri, int modeFlags, UriPermissionOwner owner) {
        modeFlags &= (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (modeFlags == 0) {
            return;
        }

        // So here we are: the caller has the assumed permission
        // to the uri, and the target doesn't.  Let's now give this to
        // the target.

        if (DEBUG_URI_PERMISSION) Slog.v(TAG, 
                "Granting " + targetPkg + "/" + targetUid + " permission to " + uri);
        
        HashMap<Uri, UriPermission> targetUris
                = mGrantedUriPermissions.get(targetUid);
        if (targetUris == null) {
            targetUris = new HashMap<Uri, UriPermission>();
            mGrantedUriPermissions.put(targetUid, targetUris);
        }

        UriPermission perm = targetUris.get(uri);
        if (perm == null) {
            perm = new UriPermission(targetUid, uri);
            targetUris.put(uri, perm);
        }

        perm.modeFlags |= modeFlags;
        if (owner == null) {
            perm.globalModeFlags |= modeFlags;
        } else {
            if ((modeFlags&Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                 perm.readOwners.add(owner);
                 owner.addReadPermission(perm);
            }
            if ((modeFlags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                 perm.writeOwners.add(owner);
                 owner.addWritePermission(perm);
            }
        }
    }

    void grantUriPermissionLocked(int callingUid,
            String targetPkg, Uri uri, int modeFlags, UriPermissionOwner owner) {
        if (targetPkg == null) {
            throw new NullPointerException("targetPkg");
        }

        int targetUid = checkGrantUriPermissionLocked(callingUid, targetPkg, uri, modeFlags);
        if (targetUid < 0) {
            return;
        }

        grantUriPermissionUncheckedLocked(targetUid, targetPkg, uri, modeFlags, owner);
    }

    /**
     * Like checkGrantUriPermissionLocked, but takes an Intent.
     */
    int checkGrantUriPermissionFromIntentLocked(int callingUid,
            String targetPkg, Intent intent) {
        if (DEBUG_URI_PERMISSION) Slog.v(TAG,
                "Checking URI perm to " + (intent != null ? intent.getData() : null)
                + " from " + intent + "; flags=0x"
                + Integer.toHexString(intent != null ? intent.getFlags() : 0));

        if (targetPkg == null) {
            throw new NullPointerException("targetPkg");
        }

        if (intent == null) {
            return -1;
        }
        Uri data = intent.getData();
        if (data == null) {
            return -1;
        }
        return checkGrantUriPermissionLocked(callingUid, targetPkg, data,
                intent.getFlags());
    }

    /**
     * Like grantUriPermissionUncheckedLocked, but takes an Intent.
     */
    void grantUriPermissionUncheckedFromIntentLocked(int targetUid,
            String targetPkg, Intent intent, UriPermissionOwner owner) {
        grantUriPermissionUncheckedLocked(targetUid, targetPkg, intent.getData(),
                intent.getFlags(), owner);
    }

    void grantUriPermissionFromIntentLocked(int callingUid,
            String targetPkg, Intent intent, UriPermissionOwner owner) {
        int targetUid = checkGrantUriPermissionFromIntentLocked(callingUid, targetPkg, intent);
        if (targetUid < 0) {
            return;
        }

        grantUriPermissionUncheckedFromIntentLocked(targetUid, targetPkg, intent, owner);
    }

    public void grantUriPermission(IApplicationThread caller, String targetPkg,
            Uri uri, int modeFlags) {
        synchronized(this) {
            final ProcessRecord r = getRecordForAppLocked(caller);
            if (r == null) {
                throw new SecurityException("Unable to find app for caller "
                        + caller
                        + " when granting permission to uri " + uri);
            }
            if (targetPkg == null) {
                throw new IllegalArgumentException("null target");
            }
            if (uri == null) {
                throw new IllegalArgumentException("null uri");
            }

            grantUriPermissionLocked(r.info.uid, targetPkg, uri, modeFlags,
                    null);
        }
    }

    void removeUriPermissionIfNeededLocked(UriPermission perm) {
        if ((perm.modeFlags&(Intent.FLAG_GRANT_READ_URI_PERMISSION
                |Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) == 0) {
            HashMap<Uri, UriPermission> perms
                    = mGrantedUriPermissions.get(perm.uid);
            if (perms != null) {
                if (DEBUG_URI_PERMISSION) Slog.v(TAG, 
                        "Removing " + perm.uid + " permission to " + perm.uri);
                perms.remove(perm.uri);
                if (perms.size() == 0) {
                    mGrantedUriPermissions.remove(perm.uid);
                }
            }
        }
    }

    private void revokeUriPermissionLocked(int callingUid, Uri uri,
            int modeFlags) {
        modeFlags &= (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (modeFlags == 0) {
            return;
        }

        if (DEBUG_URI_PERMISSION) Slog.v(TAG, 
                "Revoking all granted permissions to " + uri);
        
        final IPackageManager pm = AppGlobals.getPackageManager();

        final String authority = uri.getAuthority();
        ProviderInfo pi = null;
        ContentProviderRecord cpr = mProvidersByName.get(authority);
        if (cpr != null) {
            pi = cpr.info;
        } else {
            try {
                pi = pm.resolveContentProvider(authority,
                        PackageManager.GET_URI_PERMISSION_PATTERNS);
            } catch (RemoteException ex) {
            }
        }
        if (pi == null) {
            Slog.w(TAG, "No content provider found for: " + authority);
            return;
        }

        // Does the caller have this permission on the URI?
        if (!checkHoldingPermissionsLocked(pm, pi, uri, callingUid, modeFlags)) {
            // Right now, if you are not the original owner of the permission,
            // you are not allowed to revoke it.
            //if (!checkUriPermissionLocked(uri, callingUid, modeFlags)) {
                throw new SecurityException("Uid " + callingUid
                        + " does not have permission to uri " + uri);
            //}
        }

        // Go through all of the permissions and remove any that match.
        final List<String> SEGMENTS = uri.getPathSegments();
        if (SEGMENTS != null) {
            final int NS = SEGMENTS.size();
            int N = mGrantedUriPermissions.size();
            for (int i=0; i<N; i++) {
                HashMap<Uri, UriPermission> perms
                        = mGrantedUriPermissions.valueAt(i);
                Iterator<UriPermission> it = perms.values().iterator();
            toploop:
                while (it.hasNext()) {
                    UriPermission perm = it.next();
                    Uri targetUri = perm.uri;
                    if (!authority.equals(targetUri.getAuthority())) {
                        continue;
                    }
                    List<String> targetSegments = targetUri.getPathSegments();
                    if (targetSegments == null) {
                        continue;
                    }
                    if (targetSegments.size() < NS) {
                        continue;
                    }
                    for (int j=0; j<NS; j++) {
                        if (!SEGMENTS.get(j).equals(targetSegments.get(j))) {
                            continue toploop;
                        }
                    }
                    if (DEBUG_URI_PERMISSION) Slog.v(TAG, 
                            "Revoking " + perm.uid + " permission to " + perm.uri);
                    perm.clearModes(modeFlags);
                    if (perm.modeFlags == 0) {
                        it.remove();
                    }
                }
                if (perms.size() == 0) {
                    mGrantedUriPermissions.remove(
                            mGrantedUriPermissions.keyAt(i));
                    N--;
                    i--;
                }
            }
        }
    }

    public void revokeUriPermission(IApplicationThread caller, Uri uri,
            int modeFlags) {
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

            modeFlags &= (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (modeFlags == 0) {
                return;
            }

            final IPackageManager pm = AppGlobals.getPackageManager();

            final String authority = uri.getAuthority();
            ProviderInfo pi = null;
            ContentProviderRecord cpr = mProvidersByName.get(authority);
            if (cpr != null) {
                pi = cpr.info;
            } else {
                try {
                    pi = pm.resolveContentProvider(authority,
                            PackageManager.GET_URI_PERMISSION_PATTERNS);
                } catch (RemoteException ex) {
                }
            }
            if (pi == null) {
                Slog.w(TAG, "No content provider found for: " + authority);
                return;
            }

            revokeUriPermissionLocked(r.info.uid, uri, modeFlags);
        }
    }

    @Override
    public IBinder newUriPermissionOwner(String name) {
        synchronized(this) {
            UriPermissionOwner owner = new UriPermissionOwner(this, name);
            return owner.getExternalTokenLocked();
        }
    }

    @Override
    public void grantUriPermissionFromOwner(IBinder token, int fromUid, String targetPkg,
            Uri uri, int modeFlags) {
        synchronized(this) {
            UriPermissionOwner owner = UriPermissionOwner.fromExternalToken(token);
            if (owner == null) {
                throw new IllegalArgumentException("Unknown owner: " + token);
            }
            if (fromUid != Binder.getCallingUid()) {
                if (Binder.getCallingUid() != Process.myUid()) {
                    // Only system code can grant URI permissions on behalf
                    // of other users.
                    throw new SecurityException("nice try");
                }
            }
            if (targetPkg == null) {
                throw new IllegalArgumentException("null target");
            }
            if (uri == null) {
                throw new IllegalArgumentException("null uri");
            }

            grantUriPermissionLocked(fromUid, targetPkg, uri, modeFlags, owner);
        }
    }

    @Override
    public void revokeUriPermissionFromOwner(IBinder token, Uri uri, int mode) {
        synchronized(this) {
            UriPermissionOwner owner = UriPermissionOwner.fromExternalToken(token);
            if (owner == null) {
                throw new IllegalArgumentException("Unknown owner: " + token);
            }

            if (uri == null) {
                owner.removeUriPermissionsLocked(mode);
            } else {
                owner.removeUriPermissionLocked(uri, mode);
            }
        }
    }

    public void showWaitingForDebugger(IApplicationThread who, boolean waiting) {
        synchronized (this) {
            ProcessRecord app =
                who != null ? getRecordForAppLocked(who) : null;
            if (app == null) return;

            Message msg = Message.obtain();
            msg.what = WAIT_FOR_DEBUGGER_MSG;
            msg.obj = app;
            msg.arg1 = waiting ? 1 : 0;
            mHandler.sendMessage(msg);
        }
    }

    public void getMemoryInfo(ActivityManager.MemoryInfo outInfo) {
        final long homeAppMem = mProcessList.getMemLevel(ProcessList.HOME_APP_ADJ);
        final long hiddenAppMem = mProcessList.getMemLevel(ProcessList.HIDDEN_APP_MIN_ADJ);
        outInfo.availMem = Process.getFreeMemory();
        outInfo.threshold = homeAppMem;
        outInfo.lowMemory = outInfo.availMem < (homeAppMem + ((hiddenAppMem-homeAppMem)/2));
        outInfo.hiddenAppThreshold = hiddenAppMem;
        outInfo.secondaryServerThreshold = mProcessList.getMemLevel(
                ProcessList.SECONDARY_SERVER_ADJ);
        outInfo.visibleAppThreshold = mProcessList.getMemLevel(
                ProcessList.VISIBLE_APP_ADJ);
        outInfo.foregroundAppThreshold = mProcessList.getMemLevel(
                ProcessList.FOREGROUND_APP_ADJ);
    }
    
    // =========================================================
    // TASK MANAGEMENT
    // =========================================================

    public List getTasks(int maxNum, int flags,
                         IThumbnailReceiver receiver) {
        ArrayList list = new ArrayList();

        PendingThumbnailsRecord pending = null;
        IApplicationThread topThumbnail = null;
        ActivityRecord topRecord = null;

        synchronized(this) {
            if (localLOGV) Slog.v(
                TAG, "getTasks: max=" + maxNum + ", flags=" + flags
                + ", receiver=" + receiver);

            if (checkCallingPermission(android.Manifest.permission.GET_TASKS)
                    != PackageManager.PERMISSION_GRANTED) {
                if (receiver != null) {
                    // If the caller wants to wait for pending thumbnails,
                    // it ain't gonna get them.
                    try {
                        receiver.finished();
                    } catch (RemoteException ex) {
                    }
                }
                String msg = "Permission Denial: getTasks() from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " requires " + android.Manifest.permission.GET_TASKS;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }

            int pos = mMainStack.mHistory.size()-1;
            ActivityRecord next =
                pos >= 0 ? (ActivityRecord)mMainStack.mHistory.get(pos) : null;
            ActivityRecord top = null;
            TaskRecord curTask = null;
            int numActivities = 0;
            int numRunning = 0;
            while (pos >= 0 && maxNum > 0) {
                final ActivityRecord r = next;
                pos--;
                next = pos >= 0 ? (ActivityRecord)mMainStack.mHistory.get(pos) : null;

                // Initialize state for next task if needed.
                if (top == null ||
                        (top.state == ActivityState.INITIALIZING
                            && top.task == r.task)) {
                    top = r;
                    curTask = r.task;
                    numActivities = numRunning = 0;
                }

                // Add 'r' into the current task.
                numActivities++;
                if (r.app != null && r.app.thread != null) {
                    numRunning++;
                }

                if (localLOGV) Slog.v(
                    TAG, r.intent.getComponent().flattenToShortString()
                    + ": task=" + r.task);

                // If the next one is a different task, generate a new
                // TaskInfo entry for what we have.
                if (next == null || next.task != curTask) {
                    ActivityManager.RunningTaskInfo ci
                            = new ActivityManager.RunningTaskInfo();
                    ci.id = curTask.taskId;
                    ci.baseActivity = r.intent.getComponent();
                    ci.topActivity = top.intent.getComponent();
                    if (top.thumbHolder != null) {
                        ci.description = top.thumbHolder.lastDescription;
                    }
                    ci.numActivities = numActivities;
                    ci.numRunning = numRunning;
                    //System.out.println(
                    //    "#" + maxNum + ": " + " descr=" + ci.description);
                    if (ci.thumbnail == null && receiver != null) {
                        if (localLOGV) Slog.v(
                            TAG, "State=" + top.state + "Idle=" + top.idle
                            + " app=" + top.app
                            + " thr=" + (top.app != null ? top.app.thread : null));
                        if (top.state == ActivityState.RESUMED
                                || top.state == ActivityState.PAUSING) {
                            if (top.idle && top.app != null
                                && top.app.thread != null) {
                                topRecord = top;
                                topThumbnail = top.app.thread;
                            } else {
                                top.thumbnailNeeded = true;
                            }
                        }
                        if (pending == null) {
                            pending = new PendingThumbnailsRecord(receiver);
                        }
                        pending.pendingRecords.add(top);
                    }
                    list.add(ci);
                    maxNum--;
                    top = null;
                }
            }

            if (pending != null) {
                mPendingThumbnails.add(pending);
            }
        }

        if (localLOGV) Slog.v(TAG, "We have pending thumbnails: " + pending);

        if (topThumbnail != null) {
            if (localLOGV) Slog.v(TAG, "Requesting top thumbnail");
            try {
                topThumbnail.requestThumbnail(topRecord);
            } catch (Exception e) {
                Slog.w(TAG, "Exception thrown when requesting thumbnail", e);
                sendPendingThumbnail(null, topRecord, null, null, true);
            }
        }

        if (pending == null && receiver != null) {
            // In this case all thumbnails were available and the client
            // is being asked to be told when the remaining ones come in...
            // which is unusually, since the top-most currently running
            // activity should never have a canned thumbnail!  Oh well.
            try {
                receiver.finished();
            } catch (RemoteException ex) {
            }
        }

        return list;
    }

    public List<ActivityManager.RecentTaskInfo> getRecentTasks(int maxNum,
            int flags) {
        synchronized (this) {
            enforceCallingPermission(android.Manifest.permission.GET_TASKS,
                    "getRecentTasks()");

            IPackageManager pm = AppGlobals.getPackageManager();
            
            final int N = mRecentTasks.size();
            ArrayList<ActivityManager.RecentTaskInfo> res
                    = new ArrayList<ActivityManager.RecentTaskInfo>(
                            maxNum < N ? maxNum : N);
            for (int i=0; i<N && maxNum > 0; i++) {
                TaskRecord tr = mRecentTasks.get(i);
                if (((flags&ActivityManager.RECENT_WITH_EXCLUDED) != 0)
                        || (tr.intent == null)
                        || ((tr.intent.getFlags()
                                &Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) == 0)) {
                    ActivityManager.RecentTaskInfo rti
                            = new ActivityManager.RecentTaskInfo();
                    rti.id = tr.numActivities > 0 ? tr.taskId : -1;
                    rti.persistentId = tr.taskId;
                    rti.baseIntent = new Intent(
                            tr.intent != null ? tr.intent : tr.affinityIntent);
                    rti.origActivity = tr.origActivity;
                    rti.description = tr.lastDescription;
                    
                    if ((flags&ActivityManager.RECENT_IGNORE_UNAVAILABLE) != 0) {
                        // Check whether this activity is currently available.
                        try {
                            if (rti.origActivity != null) {
                                if (pm.getActivityInfo(rti.origActivity, 0) == null) {
                                    continue;
                                }
                            } else if (rti.baseIntent != null) {
                                if (pm.queryIntentActivities(rti.baseIntent,
                                        null, 0) == null) {
                                    continue;
                                }
                            }
                        } catch (RemoteException e) {
                            // Will never happen.
                        }
                    }
                    
                    res.add(rti);
                    maxNum--;
                }
            }
            return res;
        }
    }

    private TaskRecord taskForIdLocked(int id) {
        final int N = mRecentTasks.size();
        for (int i=0; i<N; i++) {
            TaskRecord tr = mRecentTasks.get(i);
            if (tr.taskId == id) {
                return tr;
            }
        }
        return null;
    }

    public ActivityManager.TaskThumbnails getTaskThumbnails(int id) {
        synchronized (this) {
            enforceCallingPermission(android.Manifest.permission.READ_FRAME_BUFFER,
                    "getTaskThumbnails()");
            TaskRecord tr = taskForIdLocked(id);
            if (tr != null) {
                return mMainStack.getTaskThumbnailsLocked(tr);
            }
        }
        return null;
    }

    public boolean removeSubTask(int taskId, int subTaskIndex) {
        synchronized (this) {
            enforceCallingPermission(android.Manifest.permission.REMOVE_TASKS,
                    "removeSubTask()");
            long ident = Binder.clearCallingIdentity();
            try {
                return mMainStack.removeTaskActivitiesLocked(taskId, subTaskIndex) != null;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private void cleanUpRemovedTaskLocked(ActivityRecord root, boolean killProcesses) {
        TaskRecord tr = root.task;
        Intent baseIntent = new Intent(
                tr.intent != null ? tr.intent : tr.affinityIntent);
        ComponentName component = baseIntent.getComponent();
        if (component == null) {
            Slog.w(TAG, "Now component for base intent of task: " + tr);
            return;
        }

        // Find any running services associated with this app.
        ArrayList<ServiceRecord> services = new ArrayList<ServiceRecord>();
        for (ServiceRecord sr : mServices.values()) {
            if (sr.packageName.equals(component.getPackageName())) {
                services.add(sr);
            }
        }

        // Take care of any running services associated with the app.
        for (int i=0; i<services.size(); i++) {
            ServiceRecord sr = services.get(i);
            if (sr.startRequested) {
                if ((sr.serviceInfo.flags&ServiceInfo.FLAG_STOP_WITH_TASK) != 0) {
                    Slog.i(TAG, "Stopping service " + sr.shortName + ": remove task");
                    stopServiceLocked(sr);
                } else {
                    sr.pendingStarts.add(new ServiceRecord.StartItem(sr, true,
                            sr.makeNextStartId(), baseIntent, -1));
                    if (sr.app != null && sr.app.thread != null) {
                        sendServiceArgsLocked(sr, false);
                    }
                }
            }
        }

        if (killProcesses) {
            // Find any running processes associated with this app.
            ArrayList<ProcessRecord> procs = new ArrayList<ProcessRecord>();
            SparseArray<ProcessRecord> appProcs
                    = mProcessNames.getMap().get(component.getPackageName());
            if (appProcs != null) {
                for (int i=0; i<appProcs.size(); i++) {
                    procs.add(appProcs.valueAt(i));
                }
            }

            // Kill the running processes.
            for (int i=0; i<procs.size(); i++) {
                ProcessRecord pr = procs.get(i);
                if (pr.setSchedGroup == Process.THREAD_GROUP_BG_NONINTERACTIVE) {
                    Slog.i(TAG, "Killing " + pr.toShortString() + ": remove task");
                    EventLog.writeEvent(EventLogTags.AM_KILL, pr.pid,
                            pr.processName, pr.setAdj, "remove task");
                    Process.killProcessQuiet(pr.pid);
                } else {
                    pr.waitingToKill = "remove task";
                }
            }
        }
    }

    public boolean removeTask(int taskId, int flags) {
        synchronized (this) {
            enforceCallingPermission(android.Manifest.permission.REMOVE_TASKS,
                    "removeTask()");
            long ident = Binder.clearCallingIdentity();
            try {
                ActivityRecord r = mMainStack.removeTaskActivitiesLocked(taskId, -1);
                if (r != null) {
                    mRecentTasks.remove(r.task);
                    cleanUpRemovedTaskLocked(r,
                            (flags&ActivityManager.REMOVE_TASK_KILL_PROCESS) != 0);
                    return true;
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
        return false;
    }
    
    private final int findAffinityTaskTopLocked(int startIndex, String affinity) {
        int j;
        TaskRecord startTask = ((ActivityRecord)mMainStack.mHistory.get(startIndex)).task; 
        TaskRecord jt = startTask;
        
        // First look backwards
        for (j=startIndex-1; j>=0; j--) {
            ActivityRecord r = (ActivityRecord)mMainStack.mHistory.get(j);
            if (r.task != jt) {
                jt = r.task;
                if (affinity.equals(jt.affinity)) {
                    return j;
                }
            }
        }
        
        // Now look forwards
        final int N = mMainStack.mHistory.size();
        jt = startTask;
        for (j=startIndex+1; j<N; j++) {
            ActivityRecord r = (ActivityRecord)mMainStack.mHistory.get(j);
            if (r.task != jt) {
                if (affinity.equals(jt.affinity)) {
                    return j;
                }
                jt = r.task;
            }
        }
        
        // Might it be at the top?
        if (affinity.equals(((ActivityRecord)mMainStack.mHistory.get(N-1)).task.affinity)) {
            return N-1;
        }
        
        return -1;
    }
    
    /**
     * TODO: Add mController hook
     */
    public void moveTaskToFront(int task, int flags) {
        enforceCallingPermission(android.Manifest.permission.REORDER_TASKS,
                "moveTaskToFront()");

        synchronized(this) {
            if (!checkAppSwitchAllowedLocked(Binder.getCallingPid(),
                    Binder.getCallingUid(), "Task to front")) {
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            try {
                TaskRecord tr = taskForIdLocked(task);
                if (tr != null) {
                    if ((flags&ActivityManager.MOVE_TASK_NO_USER_ACTION) == 0) {
                        mMainStack.mUserLeaving = true;
                    }
                    if ((flags&ActivityManager.MOVE_TASK_WITH_HOME) != 0) {
                        // Caller wants the home activity moved with it.  To accomplish this,
                        // we'll just move the home task to the top first.
                        mMainStack.moveHomeToFrontLocked();
                    }
                    mMainStack.moveTaskToFrontLocked(tr, null);
                    return;
                }
                for (int i=mMainStack.mHistory.size()-1; i>=0; i--) {
                    ActivityRecord hr = (ActivityRecord)mMainStack.mHistory.get(i);
                    if (hr.task.taskId == task) {
                        if ((flags&ActivityManager.MOVE_TASK_NO_USER_ACTION) == 0) {
                            mMainStack.mUserLeaving = true;
                        }
                        if ((flags&ActivityManager.MOVE_TASK_WITH_HOME) != 0) {
                            // Caller wants the home activity moved with it.  To accomplish this,
                            // we'll just move the home task to the top first.
                            mMainStack.moveHomeToFrontLocked();
                        }
                        mMainStack.moveTaskToFrontLocked(hr.task, null);
                        return;
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    public void moveTaskToBack(int task) {
        enforceCallingPermission(android.Manifest.permission.REORDER_TASKS,
                "moveTaskToBack()");

        synchronized(this) {
            if (mMainStack.mResumedActivity != null
                    && mMainStack.mResumedActivity.task.taskId == task) {
                if (!checkAppSwitchAllowedLocked(Binder.getCallingPid(),
                        Binder.getCallingUid(), "Task to back")) {
                    return;
                }
            }
            final long origId = Binder.clearCallingIdentity();
            mMainStack.moveTaskToBackLocked(task, null);
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Moves an activity, and all of the other activities within the same task, to the bottom
     * of the history stack.  The activity's order within the task is unchanged.
     * 
     * @param token A reference to the activity we wish to move
     * @param nonRoot If false then this only works if the activity is the root
     *                of a task; if true it will work for any activity in a task.
     * @return Returns true if the move completed, false if not.
     */
    public boolean moveActivityTaskToBack(IBinder token, boolean nonRoot) {
        synchronized(this) {
            final long origId = Binder.clearCallingIdentity();
            int taskId = getTaskForActivityLocked(token, !nonRoot);
            if (taskId >= 0) {
                return mMainStack.moveTaskToBackLocked(taskId, null);
            }
            Binder.restoreCallingIdentity(origId);
        }
        return false;
    }

    public void moveTaskBackwards(int task) {
        enforceCallingPermission(android.Manifest.permission.REORDER_TASKS,
                "moveTaskBackwards()");

        synchronized(this) {
            if (!checkAppSwitchAllowedLocked(Binder.getCallingPid(),
                    Binder.getCallingUid(), "Task backwards")) {
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            moveTaskBackwardsLocked(task);
            Binder.restoreCallingIdentity(origId);
        }
    }

    private final void moveTaskBackwardsLocked(int task) {
        Slog.e(TAG, "moveTaskBackwards not yet implemented!");
    }

    public int getTaskForActivity(IBinder token, boolean onlyRoot) {
        synchronized(this) {
            return getTaskForActivityLocked(token, onlyRoot);
        }
    }

    int getTaskForActivityLocked(IBinder token, boolean onlyRoot) {
        final int N = mMainStack.mHistory.size();
        TaskRecord lastTask = null;
        for (int i=0; i<N; i++) {
            ActivityRecord r = (ActivityRecord)mMainStack.mHistory.get(i);
            if (r == token) {
                if (!onlyRoot || lastTask != r.task) {
                    return r.task.taskId;
                }
                return -1;
            }
            lastTask = r.task;
        }

        return -1;
    }

    public void finishOtherInstances(IBinder token, ComponentName className) {
        synchronized(this) {
            final long origId = Binder.clearCallingIdentity();

            int N = mMainStack.mHistory.size();
            TaskRecord lastTask = null;
            for (int i=0; i<N; i++) {
                ActivityRecord r = (ActivityRecord)mMainStack.mHistory.get(i);
                if (r.realActivity.equals(className)
                        && r != token && lastTask != r.task) {
                    if (r.stack.finishActivityLocked(r, i, Activity.RESULT_CANCELED,
                            null, "others")) {
                        i--;
                        N--;
                    }
                }
                lastTask = r.task;
            }

            Binder.restoreCallingIdentity(origId);
        }
    }

    // =========================================================
    // THUMBNAILS
    // =========================================================

    public void reportThumbnail(IBinder token,
            Bitmap thumbnail, CharSequence description) {
        //System.out.println("Report thumbnail for " + token + ": " + thumbnail);
        final long origId = Binder.clearCallingIdentity();
        sendPendingThumbnail(null, token, thumbnail, description, true);
        Binder.restoreCallingIdentity(origId);
    }

    final void sendPendingThumbnail(ActivityRecord r, IBinder token,
            Bitmap thumbnail, CharSequence description, boolean always) {
        TaskRecord task = null;
        ArrayList receivers = null;

        //System.out.println("Send pending thumbnail: " + r);

        synchronized(this) {
            if (r == null) {
                r = mMainStack.isInStackLocked(token);
                if (r == null) {
                    return;
                }
            }
            if (thumbnail == null && r.thumbHolder != null) {
                thumbnail = r.thumbHolder.lastThumbnail;
                description = r.thumbHolder.lastDescription;
            }
            if (thumbnail == null && !always) {
                // If there is no thumbnail, and this entry is not actually
                // going away, then abort for now and pick up the next
                // thumbnail we get.
                return;
            }
            task = r.task;

            int N = mPendingThumbnails.size();
            int i=0;
            while (i<N) {
                PendingThumbnailsRecord pr =
                    (PendingThumbnailsRecord)mPendingThumbnails.get(i);
                //System.out.println("Looking in " + pr.pendingRecords);
                if (pr.pendingRecords.remove(r)) {
                    if (receivers == null) {
                        receivers = new ArrayList();
                    }
                    receivers.add(pr);
                    if (pr.pendingRecords.size() == 0) {
                        pr.finished = true;
                        mPendingThumbnails.remove(i);
                        N--;
                        continue;
                    }
                }
                i++;
            }
        }

        if (receivers != null) {
            final int N = receivers.size();
            for (int i=0; i<N; i++) {
                try {
                    PendingThumbnailsRecord pr =
                        (PendingThumbnailsRecord)receivers.get(i);
                    pr.receiver.newThumbnail(
                        task != null ? task.taskId : -1, thumbnail, description);
                    if (pr.finished) {
                        pr.receiver.finished();
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Exception thrown when sending thumbnail", e);
                }
            }
        }
    }

    // =========================================================
    // CONTENT PROVIDERS
    // =========================================================

    private final List<ProviderInfo> generateApplicationProvidersLocked(ProcessRecord app) {
        List<ProviderInfo> providers = null;
        try {
            providers = AppGlobals.getPackageManager().
                queryContentProviders(app.processName, app.info.uid,
                        STOCK_PM_FLAGS | PackageManager.GET_URI_PERMISSION_PATTERNS);
        } catch (RemoteException ex) {
        }
        if (providers != null) {
            final int N = providers.size();
            for (int i=0; i<N; i++) {
                ProviderInfo cpi =
                    (ProviderInfo)providers.get(i);
                ContentProviderRecord cpr = mProvidersByClass.get(cpi.name);
                if (cpr == null) {
                    cpr = new ContentProviderRecord(cpi, app.info);
                    mProvidersByClass.put(cpi.name, cpr);
                }
                app.pubProviders.put(cpi.name, cpr);
                app.addPackage(cpi.applicationInfo.packageName);
                ensurePackageDexOpt(cpi.applicationInfo.packageName);
            }
        }
        return providers;
    }

    private final String checkContentProviderPermissionLocked(
            ProviderInfo cpi, ProcessRecord r) {
        final int callingPid = (r != null) ? r.pid : Binder.getCallingPid();
        final int callingUid = (r != null) ? r.info.uid : Binder.getCallingUid();
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
                if (checkComponentPermission(pp.getReadPermission(), callingPid, callingUid,
                        cpi.applicationInfo.uid, cpi.exported)
                        == PackageManager.PERMISSION_GRANTED) {
                    return null;
                }
                if (checkComponentPermission(pp.getWritePermission(), callingPid, callingUid,
                        cpi.applicationInfo.uid, cpi.exported)
                        == PackageManager.PERMISSION_GRANTED) {
                    return null;
                }
            }
        }
        
        HashMap<Uri, UriPermission> perms = mGrantedUriPermissions.get(callingUid);
        if (perms != null) {
            for (Map.Entry<Uri, UriPermission> uri : perms.entrySet()) {
                if (uri.getKey().getAuthority().equals(cpi.authority)) {
                    return null;
                }
            }
        }

        String msg;
        if (!cpi.exported) {
            msg = "Permission Denial: opening provider " + cpi.name
                    + " from " + (r != null ? r : "(null)") + " (pid=" + callingPid
                    + ", uid=" + callingUid + ") that is not exported from uid "
                    + cpi.applicationInfo.uid;
        } else {
            msg = "Permission Denial: opening provider " + cpi.name
                    + " from " + (r != null ? r : "(null)") + " (pid=" + callingPid
                    + ", uid=" + callingUid + ") requires "
                    + cpi.readPermission + " or " + cpi.writePermission;
        }
        Slog.w(TAG, msg);
        return msg;
    }

    private final ContentProviderHolder getContentProviderImpl(
        IApplicationThread caller, String name) {
        ContentProviderRecord cpr;
        ProviderInfo cpi = null;

        synchronized(this) {
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

            // First check if this content provider has been published...
            cpr = mProvidersByName.get(name);
            if (cpr != null) {
                cpi = cpr.info;
                String msg;
                if ((msg=checkContentProviderPermissionLocked(cpi, r)) != null) {
                    throw new SecurityException(msg);
                }

                if (r != null && cpr.canRunHere(r)) {
                    // This provider has been published or is in the process
                    // of being published...  but it is also allowed to run
                    // in the caller's process, so don't make a connection
                    // and just let the caller instantiate its own instance.
                    if (cpr.provider != null) {
                        // don't give caller the provider object, it needs
                        // to make its own.
                        cpr = new ContentProviderRecord(cpr);
                    }
                    return cpr;
                }

                final long origId = Binder.clearCallingIdentity();

                // In this case the provider instance already exists, so we can
                // return it right away.
                if (r != null) {
                    if (DEBUG_PROVIDER) Slog.v(TAG,
                            "Adding provider requested by "
                            + r.processName + " from process "
                            + cpr.info.processName);
                    Integer cnt = r.conProviders.get(cpr);
                    if (cnt == null) {
                        r.conProviders.put(cpr, new Integer(1));
                    } else {
                        r.conProviders.put(cpr, new Integer(cnt.intValue()+1));
                    }
                    cpr.clients.add(r);
                    if (cpr.app != null && r.setAdj <= ProcessList.PERCEPTIBLE_APP_ADJ) {
                        // If this is a perceptible app accessing the provider,
                        // make sure to count it as being accessed and thus
                        // back up on the LRU list.  This is good because
                        // content providers are often expensive to start.
                        updateLruProcessLocked(cpr.app, false, true);
                    }
                } else {
                    cpr.externals++;
                }

                if (cpr.app != null) {
                    updateOomAdjLocked(cpr.app);
                }

                Binder.restoreCallingIdentity(origId);

            } else {
                try {
                    cpi = AppGlobals.getPackageManager().
                        resolveContentProvider(name,
                                STOCK_PM_FLAGS | PackageManager.GET_URI_PERMISSION_PATTERNS);
                } catch (RemoteException ex) {
                }
                if (cpi == null) {
                    return null;
                }

                String msg;
                if ((msg=checkContentProviderPermissionLocked(cpi, r)) != null) {
                    throw new SecurityException(msg);
                }

                if (!mProcessesReady && !mDidUpdate && !mWaitingUpdate
                        && !cpi.processName.equals("system")) {
                    // If this content provider does not run in the system
                    // process, and the system is not yet ready to run other
                    // processes, then fail fast instead of hanging.
                    throw new IllegalArgumentException(
                            "Attempt to launch content provider before system ready");
                }
                
                cpr = mProvidersByClass.get(cpi.name);
                final boolean firstClass = cpr == null;
                if (firstClass) {
                    try {
                        ApplicationInfo ai =
                            AppGlobals.getPackageManager().
                                getApplicationInfo(
                                        cpi.applicationInfo.packageName,
                                        STOCK_PM_FLAGS);
                        if (ai == null) {
                            Slog.w(TAG, "No package info for content provider "
                                    + cpi.name);
                            return null;
                        }
                        cpr = new ContentProviderRecord(cpi, ai);
                    } catch (RemoteException ex) {
                        // pm is in same process, this will never happen.
                    }
                }

                if (r != null && cpr.canRunHere(r)) {
                    // If this is a multiprocess provider, then just return its
                    // info and allow the caller to instantiate it.  Only do
                    // this if the provider is the same user as the caller's
                    // process, or can run as root (so can be in any process).
                    return cpr;
                }

                if (DEBUG_PROVIDER) {
                    RuntimeException e = new RuntimeException("here");
                    Slog.w(TAG, "LAUNCHING REMOTE PROVIDER (myuid " + r.info.uid
                          + " pruid " + cpr.appInfo.uid + "): " + cpr.info.name, e);
                }

                // This is single process, and our app is now connecting to it.
                // See if we are already in the process of launching this
                // provider.
                final int N = mLaunchingProviders.size();
                int i;
                for (i=0; i<N; i++) {
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
                            AppGlobals.getPackageManager().setPackageStoppedState(
                                    cpr.appInfo.packageName, false);
                        } catch (RemoteException e) {
                        } catch (IllegalArgumentException e) {
                            Slog.w(TAG, "Failed trying to unstop package "
                                    + cpr.appInfo.packageName + ": " + e);
                        }

                        ProcessRecord proc = startProcessLocked(cpi.processName,
                                cpr.appInfo, false, 0, "content provider",
                                new ComponentName(cpi.applicationInfo.packageName,
                                        cpi.name), false);
                        if (proc == null) {
                            Slog.w(TAG, "Unable to launch app "
                                    + cpi.applicationInfo.packageName + "/"
                                    + cpi.applicationInfo.uid + " for provider "
                                    + name + ": process is bad");
                            return null;
                        }
                        cpr.launchingApp = proc;
                        mLaunchingProviders.add(cpr);
                    } finally {
                        Binder.restoreCallingIdentity(origId);
                    }
                }

                // Make sure the provider is published (the same provider class
                // may be published under multiple names).
                if (firstClass) {
                    mProvidersByClass.put(cpi.name, cpr);
                }
                mProvidersByName.put(name, cpr);

                if (r != null) {
                    if (DEBUG_PROVIDER) Slog.v(TAG,
                            "Adding provider requested by "
                            + r.processName + " from process "
                            + cpr.info.processName);
                    Integer cnt = r.conProviders.get(cpr);
                    if (cnt == null) {
                        r.conProviders.put(cpr, new Integer(1));
                    } else {
                        r.conProviders.put(cpr, new Integer(cnt.intValue()+1));
                    }
                    cpr.clients.add(r);
                } else {
                    cpr.externals++;
                }
            }
        }

        // Wait for the provider to be published...
        synchronized (cpr) {
            while (cpr.provider == null) {
                if (cpr.launchingApp == null) {
                    Slog.w(TAG, "Unable to launch app "
                            + cpi.applicationInfo.packageName + "/"
                            + cpi.applicationInfo.uid + " for provider "
                            + name + ": launching app became null");
                    EventLog.writeEvent(EventLogTags.AM_PROVIDER_LOST_PROCESS,
                            cpi.applicationInfo.packageName,
                            cpi.applicationInfo.uid, name);
                    return null;
                }
                try {
                    cpr.wait();
                } catch (InterruptedException ex) {
                }
            }
        }
        return cpr;
    }

    public final ContentProviderHolder getContentProvider(
            IApplicationThread caller, String name) {
        if (caller == null) {
            String msg = "null IApplicationThread when getting content provider "
                    + name;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        return getContentProviderImpl(caller, name);
    }

    private ContentProviderHolder getContentProviderExternal(String name) {
        return getContentProviderImpl(null, name);
    }

    /**
     * Drop a content provider from a ProcessRecord's bookkeeping
     * @param cpr
     */
    public void removeContentProvider(IApplicationThread caller, String name) {
        synchronized (this) {
            ContentProviderRecord cpr = mProvidersByName.get(name);
            if(cpr == null) {
                // remove from mProvidersByClass
                if (DEBUG_PROVIDER) Slog.v(TAG, name +
                        " provider not found in providers list");
                return;
            }
            final ProcessRecord r = getRecordForAppLocked(caller);
            if (r == null) {
                throw new SecurityException(
                        "Unable to find app for caller " + caller +
                        " when removing content provider " + name);
            }
            //update content provider record entry info
            ContentProviderRecord localCpr = mProvidersByClass.get(cpr.info.name);
            if (DEBUG_PROVIDER) Slog.v(TAG, "Removing provider requested by "
                    + r.info.processName + " from process "
                    + localCpr.appInfo.processName);
            if (localCpr.app == r) {
                //should not happen. taken care of as a local provider
                Slog.w(TAG, "removeContentProvider called on local provider: "
                        + cpr.info.name + " in process " + r.processName);
                return;
            } else {
                Integer cnt = r.conProviders.get(localCpr);
                if (cnt == null || cnt.intValue() <= 1) {
                    localCpr.clients.remove(r);
                    r.conProviders.remove(localCpr);
                } else {
                    r.conProviders.put(localCpr, new Integer(cnt.intValue()-1));
                }
            }
            updateOomAdjLocked();
        }
    }

    private void removeContentProviderExternal(String name) {
        synchronized (this) {
            ContentProviderRecord cpr = mProvidersByName.get(name);
            if(cpr == null) {
                //remove from mProvidersByClass
                if(localLOGV) Slog.v(TAG, name+" content provider not found in providers list");
                return;
            }

            //update content provider record entry info
            ContentProviderRecord localCpr = mProvidersByClass.get(cpr.info.name);
            localCpr.externals--;
            if (localCpr.externals < 0) {
                Slog.e(TAG, "Externals < 0 for content provider " + localCpr);
            }
            updateOomAdjLocked();
        }
    }
    
    public final void publishContentProviders(IApplicationThread caller,
            List<ContentProviderHolder> providers) {
        if (providers == null) {
            return;
        }

        synchronized(this) {
            final ProcessRecord r = getRecordForAppLocked(caller);
            if (r == null) {
                throw new SecurityException(
                        "Unable to find app for caller " + caller
                      + " (pid=" + Binder.getCallingPid()
                      + ") when publishing content providers");
            }

            final long origId = Binder.clearCallingIdentity();

            final int N = providers.size();
            for (int i=0; i<N; i++) {
                ContentProviderHolder src = providers.get(i);
                if (src == null || src.info == null || src.provider == null) {
                    continue;
                }
                ContentProviderRecord dst = r.pubProviders.get(src.info.name);
                if (dst != null) {
                    mProvidersByClass.put(dst.info.name, dst);
                    String names[] = dst.info.authority.split(";");
                    for (int j = 0; j < names.length; j++) {
                        mProvidersByName.put(names[j], dst);
                    }

                    int NL = mLaunchingProviders.size();
                    int j;
                    for (j=0; j<NL; j++) {
                        if (mLaunchingProviders.get(j) == dst) {
                            mLaunchingProviders.remove(j);
                            j--;
                            NL--;
                        }
                    }
                    synchronized (dst) {
                        dst.provider = src.provider;
                        dst.app = r;
                        dst.notifyAll();
                    }
                    updateOomAdjLocked(r);
                }
            }

            Binder.restoreCallingIdentity(origId);
        }
    }

    public static final void installSystemProviders() {
        List<ProviderInfo> providers;
        synchronized (mSelf) {
            ProcessRecord app = mSelf.mProcessNames.get("system", Process.SYSTEM_UID);
            providers = mSelf.generateApplicationProvidersLocked(app);
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

        mSelf.mCoreSettingsObserver = new CoreSettingsObserver(mSelf);

        mSelf.mUsageStatsService.monitorPackages();
    }

    /**
     * Allows app to retrieve the MIME type of a URI without having permission
     * to access its content provider.
     *
     * CTS tests for this functionality can be run with "runtest cts-appsecurity".
     *
     * Test cases are at cts/tests/appsecurity-tests/test-apps/UsePermissionDiffCert/
     *     src/com/android/cts/usespermissiondiffcertapp/AccessPermissionWithDiffSigTest.java
     */
    public String getProviderMimeType(Uri uri) {
        final String name = uri.getAuthority();
        final long ident = Binder.clearCallingIdentity();
        ContentProviderHolder holder = null;

        try {
            holder = getContentProviderExternal(name);
            if (holder != null) {
                return holder.provider.getType(uri);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Content provider dead retrieving " + uri, e);
            return null;
        } finally {
            if (holder != null) {
                removeContentProviderExternal(name);
            }
            Binder.restoreCallingIdentity(ident);
        }

        return null;
    }

    // =========================================================
    // GLOBAL MANAGEMENT
    // =========================================================

    final ProcessRecord newProcessRecordLocked(IApplicationThread thread,
            ApplicationInfo info, String customProcess) {
        String proc = customProcess != null ? customProcess : info.processName;
        BatteryStatsImpl.Uid.Proc ps = null;
        BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
        synchronized (stats) {
            ps = stats.getProcessStatsLocked(info.uid, proc);
        }
        return new ProcessRecord(ps, thread, info, proc);
    }

    final ProcessRecord addAppLocked(ApplicationInfo info) {
        ProcessRecord app = getProcessRecordLocked(info.processName, info.uid);

        if (app == null) {
            app = newProcessRecordLocked(null, info, null);
            mProcessNames.put(info.processName, info.uid, app);
            updateLruProcessLocked(app, true, true);
        }

        // This package really, really can not be stopped.
        try {
            AppGlobals.getPackageManager().setPackageStoppedState(
                    info.packageName, false);
        } catch (RemoteException e) {
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Failed trying to unstop package "
                    + info.packageName + ": " + e);
        }

        if ((info.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_PERSISTENT))
                == (ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_PERSISTENT)) {
            app.persistent = true;
            app.maxAdj = ProcessList.CORE_SERVER_ADJ;
        }
        if (app.thread == null && mPersistentStartingProcesses.indexOf(app) < 0) {
            mPersistentStartingProcesses.add(app);
            startProcessLocked(app, "added application", app.processName);
        }

        return app;
    }

    public void unhandledBack() {
        enforceCallingPermission(android.Manifest.permission.FORCE_BACK,
                "unhandledBack()");

        synchronized(this) {
            int count = mMainStack.mHistory.size();
            if (DEBUG_SWITCH) Slog.d(
                TAG, "Performing unhandledBack(): stack size = " + count);
            if (count > 1) {
                final long origId = Binder.clearCallingIdentity();
                mMainStack.finishActivityLocked((ActivityRecord)mMainStack.mHistory.get(count-1),
                        count-1, Activity.RESULT_CANCELED, null, "unhandled-back");
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    public ParcelFileDescriptor openContentUri(Uri uri) throws RemoteException {
        String name = uri.getAuthority();
        ContentProviderHolder cph = getContentProviderExternal(name);
        ParcelFileDescriptor pfd = null;
        if (cph != null) {
            // We record the binder invoker's uid in thread-local storage before
            // going to the content provider to open the file.  Later, in the code
            // that handles all permissions checks, we look for this uid and use
            // that rather than the Activity Manager's own uid.  The effect is that
            // we do the check against the caller's permissions even though it looks
            // to the content provider like the Activity Manager itself is making
            // the request.
            sCallerIdentity.set(new Identity(
                    Binder.getCallingPid(), Binder.getCallingUid()));
            try {
                pfd = cph.provider.openFile(uri, "r");
            } catch (FileNotFoundException e) {
                // do nothing; pfd will be returned null
            } finally {
                // Ensure that whatever happens, we clean up the identity state
                sCallerIdentity.remove();
            }

            // We've got the fd now, so we're done with the provider.
            removeContentProviderExternal(name);
        } else {
            Slog.d(TAG, "Failed to get provider for authority '" + name + "'");
        }
        return pfd;
    }

    // Actually is sleeping or shutting down or whatever else in the future
    // is an inactive state.
    public boolean isSleeping() {
        return mSleeping || mShuttingDown;
    }

    public void goingToSleep() {
        synchronized(this) {
            mSleeping = true;
            mWindowManager.setEventDispatching(false);

            mMainStack.stopIfSleepingLocked();

            // Initialize the wake times of all processes.
            checkExcessivePowerUsageLocked(false);
            mHandler.removeMessages(CHECK_EXCESSIVE_WAKE_LOCKS_MSG);
            Message nmsg = mHandler.obtainMessage(CHECK_EXCESSIVE_WAKE_LOCKS_MSG);
            mHandler.sendMessageDelayed(nmsg, POWER_CHECK_DELAY);
        }
    }

    public boolean shutdown(int timeout) {
        if (checkCallingPermission(android.Manifest.permission.SHUTDOWN)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.SHUTDOWN);
        }
        
        boolean timedout = false;
        
        synchronized(this) {
            mShuttingDown = true;
            mWindowManager.setEventDispatching(false);

            if (mMainStack.mResumedActivity != null) {
                mMainStack.stopIfSleepingLocked();
                final long endTime = System.currentTimeMillis() + timeout;
                while (mMainStack.mResumedActivity != null
                        || mMainStack.mPausingActivity != null) {
                    long delay = endTime - System.currentTimeMillis();
                    if (delay <= 0) {
                        Slog.w(TAG, "Activity manager shutdown timed out");
                        timedout = true;
                        break;
                    }
                    try {
                        this.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
        }
        
        mUsageStatsService.shutdown();
        mBatteryStatsService.shutdown();
        
        return timedout;
    }
    
    public final void activitySlept(IBinder token) {
        if (localLOGV) Slog.v(
            TAG, "Activity slept: token=" + token);

        ActivityRecord r = null;

        final long origId = Binder.clearCallingIdentity();

        synchronized (this) {
            r = mMainStack.isInStackLocked(token);
            if (r != null) {
                mMainStack.activitySleptLocked(r);
            }
        }

        Binder.restoreCallingIdentity(origId);
    }

    public void wakingUp() {
        synchronized(this) {
            mWindowManager.setEventDispatching(true);
            mSleeping = false;
            mMainStack.awakeFromSleepingLocked();
            mMainStack.resumeTopActivityLocked(null);
        }
    }

    public void stopAppSwitches() {
        if (checkCallingPermission(android.Manifest.permission.STOP_APP_SWITCHES)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.STOP_APP_SWITCHES);
        }
        
        synchronized(this) {
            mAppSwitchesAllowedTime = SystemClock.uptimeMillis()
                    + APP_SWITCH_DELAY_TIME;
            mDidAppSwitch = false;
            mHandler.removeMessages(DO_PENDING_ACTIVITY_LAUNCHES_MSG);
            Message msg = mHandler.obtainMessage(DO_PENDING_ACTIVITY_LAUNCHES_MSG);
            mHandler.sendMessageDelayed(msg, APP_SWITCH_DELAY_TIME);
        }
    }
    
    public void resumeAppSwitches() {
        if (checkCallingPermission(android.Manifest.permission.STOP_APP_SWITCHES)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.STOP_APP_SWITCHES);
        }
        
        synchronized(this) {
            // Note that we don't execute any pending app switches... we will
            // let those wait until either the timeout, or the next start
            // activity request.
            mAppSwitchesAllowedTime = 0;
        }
    }
    
    boolean checkAppSwitchAllowedLocked(int callingPid, int callingUid,
            String name) {
        if (mAppSwitchesAllowedTime < SystemClock.uptimeMillis()) {
            return true;
        }
            
        final int perm = checkComponentPermission(
                android.Manifest.permission.STOP_APP_SWITCHES, callingPid,
                callingUid, -1, true);
        if (perm == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        
        Slog.w(TAG, name + " request from " + callingUid + " stopped");
        return false;
    }
    
    public void setDebugApp(String packageName, boolean waitForDebugger,
            boolean persistent) {
        enforceCallingPermission(android.Manifest.permission.SET_DEBUG_APP,
                "setDebugApp()");

        // Note that this is not really thread safe if there are multiple
        // callers into it at the same time, but that's not a situation we
        // care about.
        if (persistent) {
            final ContentResolver resolver = mContext.getContentResolver();
            Settings.System.putString(
                resolver, Settings.System.DEBUG_APP,
                packageName);
            Settings.System.putInt(
                resolver, Settings.System.WAIT_FOR_DEBUGGER,
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
                final long origId = Binder.clearCallingIdentity();
                forceStopPackageLocked(packageName, -1, false, false, true);
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    public void setAlwaysFinish(boolean enabled) {
        enforceCallingPermission(android.Manifest.permission.SET_ALWAYS_FINISH,
                "setAlwaysFinish()");

        Settings.System.putInt(
                mContext.getContentResolver(),
                Settings.System.ALWAYS_FINISH_ACTIVITIES, enabled ? 1 : 0);
        
        synchronized (this) {
            mAlwaysFinishActivities = enabled;
        }
    }

    public void setActivityController(IActivityController controller) {
        enforceCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER,
                "setActivityController()");
        synchronized (this) {
            mController = controller;
        }
    }

    public boolean isUserAMonkey() {
        // For now the fact that there is a controller implies
        // we have a monkey.
        synchronized (this) {
            return mController != null;
        }
    }
    
    public void registerActivityWatcher(IActivityWatcher watcher) {
        synchronized (this) {
            mWatchers.register(watcher);
        }
    }

    public void unregisterActivityWatcher(IActivityWatcher watcher) {
        synchronized (this) {
            mWatchers.unregister(watcher);
        }
    }

    public void registerProcessObserver(IProcessObserver observer) {
        mProcessObservers.register(observer);
    }

    public void unregisterProcessObserver(IProcessObserver observer) {
        mProcessObservers.unregister(observer);
    }

    public void setImmersive(IBinder token, boolean immersive) {
        synchronized(this) {
            ActivityRecord r = mMainStack.isInStackLocked(token);
            if (r == null) {
                throw new IllegalArgumentException();
            }
            r.immersive = immersive;
        }
    }

    public boolean isImmersive(IBinder token) {
        synchronized (this) {
            ActivityRecord r = mMainStack.isInStackLocked(token);
            if (r == null) {
                throw new IllegalArgumentException();
            }
            return r.immersive;
        }
    }

    public boolean isTopActivityImmersive() {
        synchronized (this) {
            ActivityRecord r = mMainStack.topRunningActivityLocked(null);
            return (r != null) ? r.immersive : false;
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
        }
    }

    public final void showSafeModeOverlay() {
        View v = LayoutInflater.from(mContext).inflate(
                com.android.internal.R.layout.safe_mode, null);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.type = WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        lp.format = v.getBackground().getOpacity();
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        ((WindowManager)mContext.getSystemService(
                Context.WINDOW_SERVICE)).addView(v, lp);
    }

    public void noteWakeupAlarm(IIntentSender sender) {
        if (!(sender instanceof PendingIntentRecord)) {
            return;
        }
        BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
        synchronized (stats) {
            if (mBatteryStatsService.isOnBattery()) {
                mBatteryStatsService.enforceCallingPermission();
                PendingIntentRecord rec = (PendingIntentRecord)sender;
                int MY_UID = Binder.getCallingUid();
                int uid = rec.uid == MY_UID ? Process.SYSTEM_UID : rec.uid;
                BatteryStatsImpl.Uid.Pkg pkg =
                    stats.getPackageStatsLocked(uid, rec.key.packageName);
                pkg.incWakeupsLocked();
            }
        }
    }

    public boolean killPids(int[] pids, String pReason, boolean secure) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("killPids only available to the system");
        }
        String reason = (pReason == null) ? "Unknown" : pReason;
        // XXX Note: don't acquire main activity lock here, because the window
        // manager calls in with its locks held.
        
        boolean killed = false;
        synchronized (mPidsSelfLocked) {
            int[] types = new int[pids.length];
            int worstType = 0;
            for (int i=0; i<pids.length; i++) {
                ProcessRecord proc = mPidsSelfLocked.get(pids[i]);
                if (proc != null) {
                    int type = proc.setAdj;
                    types[i] = type;
                    if (type > worstType) {
                        worstType = type;
                    }
                }
            }
            
            // If the worst oom_adj is somewhere in the hidden proc LRU range,
            // then constrain it so we will kill all hidden procs.
            if (worstType < ProcessList.EMPTY_APP_ADJ && worstType > ProcessList.HIDDEN_APP_MIN_ADJ) {
                worstType = ProcessList.HIDDEN_APP_MIN_ADJ;
            }

            // If this is not a secure call, don't let it kill processes that
            // are important.
            if (!secure && worstType < ProcessList.SECONDARY_SERVER_ADJ) {
                worstType = ProcessList.SECONDARY_SERVER_ADJ;
            }

            Slog.w(TAG, "Killing processes " + reason + " at adjustment " + worstType);
            for (int i=0; i<pids.length; i++) {
                ProcessRecord proc = mPidsSelfLocked.get(pids[i]);
                if (proc == null) {
                    continue;
                }
                int adj = proc.setAdj;
                if (adj >= worstType && !proc.killedBackground) {
                    Slog.w(TAG, "Killing " + proc + " (adj " + adj + "): " + reason);
                    EventLog.writeEvent(EventLogTags.AM_KILL, proc.pid,
                            proc.processName, adj, reason);
                    killed = true;
                    proc.killedBackground = true;
                    Process.killProcessQuiet(pids[i]);
                }
            }
        }
        return killed;
    }
    
    public final void startRunning(String pkg, String cls, String action,
            String data) {
        synchronized(this) {
            if (mStartRunning) {
                return;
            }
            mStartRunning = true;
            mTopComponent = pkg != null && cls != null
                    ? new ComponentName(pkg, cls) : null;
            mTopAction = action != null ? action : Intent.ACTION_MAIN;
            mTopData = data;
            if (!mSystemReady) {
                return;
            }
        }

        systemReady(null);
    }

    private void retrieveSettings() {
        final ContentResolver resolver = mContext.getContentResolver();
        String debugApp = Settings.System.getString(
            resolver, Settings.System.DEBUG_APP);
        boolean waitForDebugger = Settings.System.getInt(
            resolver, Settings.System.WAIT_FOR_DEBUGGER, 0) != 0;
        boolean alwaysFinishActivities = Settings.System.getInt(
            resolver, Settings.System.ALWAYS_FINISH_ACTIVITIES, 0) != 0;

        Configuration configuration = new Configuration();
        Settings.System.getConfiguration(resolver, configuration);

        synchronized (this) {
            mDebugApp = mOrigDebugApp = debugApp;
            mWaitForDebugger = mOrigWaitForDebugger = waitForDebugger;
            mAlwaysFinishActivities = alwaysFinishActivities;
            // This happens before any activities are started, so we can
            // change mConfiguration in-place.
            mConfiguration.updateFrom(configuration);
            mConfigurationSeq = mConfiguration.seq = 1;
            if (DEBUG_CONFIGURATION) Slog.v(TAG, "Initial config: " + mConfiguration);
        }
    }

    public boolean testIsSystemReady() {
        // no need to synchronize(this) just to read & return the value
        return mSystemReady;
    }
    
    private static File getCalledPreBootReceiversFile() {
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        File fname = new File(systemDir, "called_pre_boots.dat");
        return fname;
    }
    
    private static ArrayList<ComponentName> readLastDonePreBootReceivers() {
        ArrayList<ComponentName> lastDoneReceivers = new ArrayList<ComponentName>();
        File file = getCalledPreBootReceiversFile();
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            DataInputStream dis = new DataInputStream(new BufferedInputStream(fis, 2048));
            int vers = dis.readInt();
            String codename = dis.readUTF();
            if (vers == android.os.Build.VERSION.SDK_INT
                    && codename.equals(android.os.Build.VERSION.CODENAME)) {
                int num = dis.readInt();
                while (num > 0) {
                    num--;
                    String pkg = dis.readUTF();
                    String cls = dis.readUTF();
                    lastDoneReceivers.add(new ComponentName(pkg, cls));
                }
            }
        } catch (FileNotFoundException e) {
        } catch (IOException e) {
            Slog.w(TAG, "Failure reading last done pre-boot receivers", e);
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                }
            }
        }
        return lastDoneReceivers;
    }
    
    private static void writeLastDonePreBootReceivers(ArrayList<ComponentName> list) {
        File file = getCalledPreBootReceiversFile();
        FileOutputStream fos = null;
        DataOutputStream dos = null;
        try {
            Slog.i(TAG, "Writing new set of last done pre-boot receivers...");
            fos = new FileOutputStream(file);
            dos = new DataOutputStream(new BufferedOutputStream(fos, 2048));
            dos.writeInt(android.os.Build.VERSION.SDK_INT);
            dos.writeUTF(android.os.Build.VERSION.CODENAME);
            dos.writeInt(list.size());
            for (int i=0; i<list.size(); i++) {
                dos.writeUTF(list.get(i).getPackageName());
                dos.writeUTF(list.get(i).getClassName());
            }
        } catch (IOException e) {
            Slog.w(TAG, "Failure writing last done pre-boot receivers", e);
            file.delete();
        } finally {
            FileUtils.sync(fos);
            if (dos != null) {
                try {
                    dos.close();
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }
    }
    
    public void systemReady(final Runnable goingCallback) {
        synchronized(this) {
            if (mSystemReady) {
                if (goingCallback != null) goingCallback.run();
                return;
            }
            
            // Check to see if there are any update receivers to run.
            if (!mDidUpdate) {
                if (mWaitingUpdate) {
                    return;
                }
                Intent intent = new Intent(Intent.ACTION_PRE_BOOT_COMPLETED);
                List<ResolveInfo> ris = null;
                try {
                    ris = AppGlobals.getPackageManager().queryIntentReceivers(
                                intent, null, 0);
                } catch (RemoteException e) {
                }
                if (ris != null) {
                    for (int i=ris.size()-1; i>=0; i--) {
                        if ((ris.get(i).activityInfo.applicationInfo.flags
                                &ApplicationInfo.FLAG_SYSTEM) == 0) {
                            ris.remove(i);
                        }
                    }
                    intent.addFlags(Intent.FLAG_RECEIVER_BOOT_UPGRADE);
                    
                    ArrayList<ComponentName> lastDoneReceivers = readLastDonePreBootReceivers();
                    
                    final ArrayList<ComponentName> doneReceivers = new ArrayList<ComponentName>();
                    for (int i=0; i<ris.size(); i++) {
                        ActivityInfo ai = ris.get(i).activityInfo;
                        ComponentName comp = new ComponentName(ai.packageName, ai.name);
                        if (lastDoneReceivers.contains(comp)) {
                            ris.remove(i);
                            i--;
                        }
                    }
                    
                    for (int i=0; i<ris.size(); i++) {
                        ActivityInfo ai = ris.get(i).activityInfo;
                        ComponentName comp = new ComponentName(ai.packageName, ai.name);
                        doneReceivers.add(comp);
                        intent.setComponent(comp);
                        IIntentReceiver finisher = null;
                        if (i == ris.size()-1) {
                            finisher = new IIntentReceiver.Stub() {
                                public void performReceive(Intent intent, int resultCode,
                                        String data, Bundle extras, boolean ordered,
                                        boolean sticky) {
                                    // The raw IIntentReceiver interface is called
                                    // with the AM lock held, so redispatch to
                                    // execute our code without the lock.
                                    mHandler.post(new Runnable() {
                                        public void run() {
                                            synchronized (ActivityManagerService.this) {
                                                mDidUpdate = true;
                                            }
                                            writeLastDonePreBootReceivers(doneReceivers);
                                            systemReady(goingCallback);
                                        }
                                    });
                                }
                            };
                        }
                        Slog.i(TAG, "Sending system update to: " + intent.getComponent());
                        broadcastIntentLocked(null, null, intent, null, finisher,
                                0, null, null, null, true, false, MY_PID, Process.SYSTEM_UID);
                        if (finisher != null) {
                            mWaitingUpdate = true;
                        }
                    }
                }
                if (mWaitingUpdate) {
                    return;
                }
                mDidUpdate = true;
            }
            
            mSystemReady = true;
            if (!mStartRunning) {
                return;
            }
        }

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
                    removeProcessLocked(proc, true, false);
                }
            }
            
            // Now that we have cleaned up any update processes, we
            // are ready to start launching real processes and know that
            // we won't trample on them any more.
            mProcessesReady = true;
        }
        
        Slog.i(TAG, "System now ready");
        EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_AMS_READY,
            SystemClock.uptimeMillis());

        synchronized(this) {
            // Make sure we have no pre-ready processes sitting around.
            
            if (mFactoryTest == SystemServer.FACTORY_TEST_LOW_LEVEL) {
                ResolveInfo ri = mContext.getPackageManager()
                        .resolveActivity(new Intent(Intent.ACTION_FACTORY_TEST),
                                STOCK_PM_FLAGS);
                CharSequence errorMsg = null;
                if (ri != null) {
                    ActivityInfo ai = ri.activityInfo;
                    ApplicationInfo app = ai.applicationInfo;
                    if ((app.flags&ApplicationInfo.FLAG_SYSTEM) != 0) {
                        mTopAction = Intent.ACTION_FACTORY_TEST;
                        mTopData = null;
                        mTopComponent = new ComponentName(app.packageName,
                                ai.name);
                    } else {
                        errorMsg = mContext.getResources().getText(
                                com.android.internal.R.string.factorytest_not_system);
                    }
                } else {
                    errorMsg = mContext.getResources().getText(
                            com.android.internal.R.string.factorytest_no_action);
                }
                if (errorMsg != null) {
                    mTopAction = null;
                    mTopData = null;
                    mTopComponent = null;
                    Message msg = Message.obtain();
                    msg.what = SHOW_FACTORY_ERROR_MSG;
                    msg.getData().putCharSequence("msg", errorMsg);
                    mHandler.sendMessage(msg);
                }
            }
        }

        retrieveSettings();

        if (goingCallback != null) goingCallback.run();
        
        synchronized (this) {
            if (mFactoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL) {
                try {
                    List apps = AppGlobals.getPackageManager().
                        getPersistentApplications(STOCK_PM_FLAGS);
                    if (apps != null) {
                        int N = apps.size();
                        int i;
                        for (i=0; i<N; i++) {
                            ApplicationInfo info
                                = (ApplicationInfo)apps.get(i);
                            if (info != null &&
                                    !info.packageName.equals("android")) {
                                addAppLocked(info);
                            }
                        }
                    }
                } catch (RemoteException ex) {
                    // pm is in same process, this will never happen.
                }
            }

            // Start up initial activity.
            mBooting = true;
            
            try {
                if (AppGlobals.getPackageManager().hasSystemUidErrors()) {
                    Message msg = Message.obtain();
                    msg.what = SHOW_UID_ERROR_MSG;
                    mHandler.sendMessage(msg);
                }
            } catch (RemoteException e) {
            }

            mMainStack.resumeTopActivityLocked(null);
        }
    }

    private boolean makeAppCrashingLocked(ProcessRecord app,
            String shortMsg, String longMsg, String stackTrace) {
        app.crashing = true;
        app.crashingReport = generateProcessError(app,
                ActivityManager.ProcessErrorStateInfo.CRASHED, null, shortMsg, longMsg, stackTrace);
        startAppProblemLocked(app);
        app.stopFreezingAllLocked();
        return handleAppCrashLocked(app);
    }

    private void makeAppNotRespondingLocked(ProcessRecord app,
            String activity, String shortMsg, String longMsg) {
        app.notResponding = true;
        app.notRespondingReport = generateProcessError(app,
                ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING,
                activity, shortMsg, longMsg, null);
        startAppProblemLocked(app);
        app.stopFreezingAllLocked();
    }
    
    /**
     * Generate a process error record, suitable for attachment to a ProcessRecord.
     * 
     * @param app The ProcessRecord in which the error occurred.
     * @param condition Crashing, Application Not Responding, etc.  Values are defined in 
     *                      ActivityManager.AppErrorStateInfo
     * @param activity The activity associated with the crash, if known.
     * @param shortMsg Short message describing the crash.
     * @param longMsg Long message describing the crash.
     * @param stackTrace Full crash stack trace, may be null.
     *
     * @return Returns a fully-formed AppErrorStateInfo record.
     */
    private ActivityManager.ProcessErrorStateInfo generateProcessError(ProcessRecord app, 
            int condition, String activity, String shortMsg, String longMsg, String stackTrace) {
        ActivityManager.ProcessErrorStateInfo report = new ActivityManager.ProcessErrorStateInfo();

        report.condition = condition;
        report.processName = app.processName;
        report.pid = app.pid;
        report.uid = app.info.uid;
        report.tag = activity;
        report.shortMsg = shortMsg;
        report.longMsg = longMsg;
        report.stackTrace = stackTrace;

        return report;
    }

    void killAppAtUsersRequest(ProcessRecord app, Dialog fromDialog) {
        synchronized (this) {
            app.crashing = false;
            app.crashingReport = null;
            app.notResponding = false;
            app.notRespondingReport = null;
            if (app.anrDialog == fromDialog) {
                app.anrDialog = null;
            }
            if (app.waitDialog == fromDialog) {
                app.waitDialog = null;
            }
            if (app.pid > 0 && app.pid != MY_PID) {
                handleAppCrashLocked(app);
                Slog.i(ActivityManagerService.TAG, "Killing " + app + ": user's request");
                EventLog.writeEvent(EventLogTags.AM_KILL, app.pid,
                        app.processName, app.setAdj, "user's request after error");
                Process.killProcessQuiet(app.pid);
            }
        }
    }

    private boolean handleAppCrashLocked(ProcessRecord app) {
        long now = SystemClock.uptimeMillis();

        Long crashTime = mProcessCrashTimes.get(app.info.processName,
                app.info.uid);
        if (crashTime != null && now < crashTime+ProcessList.MIN_CRASH_INTERVAL) {
            // This process loses!
            Slog.w(TAG, "Process " + app.info.processName
                    + " has crashed too many times: killing!");
            EventLog.writeEvent(EventLogTags.AM_PROCESS_CRASHED_TOO_MUCH,
                    app.info.processName, app.info.uid);
            for (int i=mMainStack.mHistory.size()-1; i>=0; i--) {
                ActivityRecord r = (ActivityRecord)mMainStack.mHistory.get(i);
                if (r.app == app) {
                    Slog.w(TAG, "  Force finishing activity "
                        + r.intent.getComponent().flattenToShortString());
                    r.stack.finishActivityLocked(r, i, Activity.RESULT_CANCELED, null, "crashed");
                }
            }
            if (!app.persistent) {
                // We don't want to start this process again until the user
                // explicitly does so...  but for persistent process, we really
                // need to keep it running.  If a persistent process is actually
                // repeatedly crashing, then badness for everyone.
                EventLog.writeEvent(EventLogTags.AM_PROC_BAD, app.info.uid,
                        app.info.processName);
                mBadProcesses.put(app.info.processName, app.info.uid, now);
                app.bad = true;
                mProcessCrashTimes.remove(app.info.processName, app.info.uid);
                app.removed = true;
                // Don't let services in this process be restarted and potentially
                // annoy the user repeatedly.  Unless it is persistent, since those
                // processes run critical code.
                removeProcessLocked(app, false, false);
                mMainStack.resumeTopActivityLocked(null);
                return false;
            }
            mMainStack.resumeTopActivityLocked(null);
        } else {
            ActivityRecord r = mMainStack.topRunningActivityLocked(null);
            if (r.app == app) {
                // If the top running activity is from this crashing
                // process, then terminate it to avoid getting in a loop.
                Slog.w(TAG, "  Force finishing activity "
                        + r.intent.getComponent().flattenToShortString());
                int index = mMainStack.indexOfTokenLocked(r);
                r.stack.finishActivityLocked(r, index,
                        Activity.RESULT_CANCELED, null, "crashed");
                // Also terminate any activities below it that aren't yet
                // stopped, to avoid a situation where one will get
                // re-start our crashing activity once it gets resumed again.
                index--;
                if (index >= 0) {
                    r = (ActivityRecord)mMainStack.mHistory.get(index);
                    if (r.state == ActivityState.RESUMED
                            || r.state == ActivityState.PAUSING
                            || r.state == ActivityState.PAUSED) {
                        if (!r.isHomeActivity || mHomeProcess != r.app) {
                            Slog.w(TAG, "  Force finishing activity "
                                    + r.intent.getComponent().flattenToShortString());
                            r.stack.finishActivityLocked(r, index,
                                    Activity.RESULT_CANCELED, null, "crashed");
                        }
                    }
                }
            }
        }

        // Bump up the crash count of any services currently running in the proc.
        if (app.services.size() != 0) {
            // Any services running in the application need to be placed
            // back in the pending list.
            Iterator<ServiceRecord> it = app.services.iterator();
            while (it.hasNext()) {
                ServiceRecord sr = it.next();
                sr.crashCount++;
            }
        }

        // If the crashing process is what we consider to be the "home process" and it has been
        // replaced by a third-party app, clear the package preferred activities from packages
        // with a home activity running in the process to prevent a repeatedly crashing app
        // from blocking the user to manually clear the list.
        if (app == mHomeProcess && mHomeProcess.activities.size() > 0
                    && (mHomeProcess.info.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
            Iterator it = mHomeProcess.activities.iterator();
            while (it.hasNext()) {
                ActivityRecord r = (ActivityRecord)it.next();
                if (r.isHomeActivity) {
                    Log.i(TAG, "Clearing package preferred activities from " + r.packageName);
                    try {
                        ActivityThread.getPackageManager()
                                .clearPackagePreferredActivities(r.packageName);
                    } catch (RemoteException c) {
                        // pm is in same process, this will never happen.
                    }
                }
            }
        }

        mProcessCrashTimes.put(app.info.processName, app.info.uid, now);
        return true;
    }

    void startAppProblemLocked(ProcessRecord app) {
        app.errorReportReceiver = ApplicationErrorReport.getErrorReportReceiver(
                mContext, app.info.packageName, app.info.flags);
        skipCurrentReceiverLocked(app);
    }

    void skipCurrentReceiverLocked(ProcessRecord app) {
        boolean reschedule = false;
        BroadcastRecord r = app.curReceiver;
        if (r != null) {
            // The current broadcast is waiting for this app's receiver
            // to be finished.  Looks like that's not going to happen, so
            // let the broadcast continue.
            logBroadcastReceiverDiscardLocked(r);
            finishReceiverLocked(r.receiver, r.resultCode, r.resultData,
                    r.resultExtras, r.resultAbort, true);
            reschedule = true;
        }
        r = mPendingBroadcast;
        if (r != null && r.curApp == app) {
            if (DEBUG_BROADCAST) Slog.v(TAG,
                    "skip & discard pending app " + r);
            logBroadcastReceiverDiscardLocked(r);
            finishReceiverLocked(r.receiver, r.resultCode, r.resultData,
                    r.resultExtras, r.resultAbort, true);
            reschedule = true;
        }
        if (reschedule) {
            scheduleBroadcastsLocked();
        }
    }

    /**
     * Used by {@link com.android.internal.os.RuntimeInit} to report when an application crashes.
     * The application process will exit immediately after this call returns.
     * @param app object of the crashing app, null for the system server
     * @param crashInfo describing the exception
     */
    public void handleApplicationCrash(IBinder app, ApplicationErrorReport.CrashInfo crashInfo) {
        ProcessRecord r = findAppProcess(app, "Crash");

        EventLog.writeEvent(EventLogTags.AM_CRASH, Binder.getCallingPid(),
                app == null ? "system" : (r == null ? "unknown" : r.processName),
                r == null ? -1 : r.info.flags,
                crashInfo.exceptionClassName,
                crashInfo.exceptionMessage,
                crashInfo.throwFileName,
                crashInfo.throwLineNumber);

        addErrorToDropBox("crash", r, null, null, null, null, null, crashInfo);

        crashApplication(r, crashInfo);
    }

    public void handleApplicationStrictModeViolation(
            IBinder app,
            int violationMask,
            StrictMode.ViolationInfo info) {
        ProcessRecord r = findAppProcess(app, "StrictMode");
        if (r == null) {
            return;
        }

        if ((violationMask & StrictMode.PENALTY_DROPBOX) != 0) {
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

        if ((violationMask & StrictMode.PENALTY_DIALOG) != 0) {
            AppErrorResult result = new AppErrorResult();
            synchronized (this) {
                final long origId = Binder.clearCallingIdentity();

                Message msg = Message.obtain();
                msg.what = SHOW_STRICT_MODE_VIOLATION_MSG;
                HashMap<String, Object> data = new HashMap<String, Object>();
                data.put("result", result);
                data.put("app", r);
                data.put("violationMask", violationMask);
                data.put("info", info);
                msg.obj = data;
                mHandler.sendMessage(msg);

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
        final String dropboxTag = isSystemApp ? "system_app_strictmode" : "data_app_strictmode";
        final DropBoxManager dbox = (DropBoxManager)
                mContext.getSystemService(Context.DROPBOX_SERVICE);

        // Exit early if the dropbox isn't configured to accept this report type.
        if (dbox == null || !dbox.isTagEnabled(dropboxTag)) return;

        boolean bufferWasEmpty;
        boolean needsFlush;
        final StringBuilder sb = isSystemApp ? mStrictModeBuffer : new StringBuilder(1024);
        synchronized (sb) {
            bufferWasEmpty = sb.length() == 0;
            appendDropBoxProcessHeaders(process, sb);
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
            if (info.crashInfo != null && info.crashInfo.stackTrace != null) {
                sb.append(info.crashInfo.stackTrace);
            }
            sb.append("\n");

            // Only buffer up to ~64k.  Various logging bits truncate
            // things at 128k.
            needsFlush = (sb.length() > 64 * 1024);
        }

        // Flush immediately if the buffer's grown too large, or this
        // is a non-system app.  Non-system apps are isolated with a
        // different tag & policy and not batched.
        //
        // Batching is useful during internal testing with
        // StrictMode settings turned up high.  Without batching,
        // thousands of separate files could be created on boot.
        if (!isSystemApp || needsFlush) {
            new Thread("Error dump: " + dropboxTag) {
                @Override
                public void run() {
                    String report;
                    synchronized (sb) {
                        report = sb.toString();
                        sb.delete(0, sb.length());
                        sb.trimToSize();
                    }
                    if (report.length() != 0) {
                        dbox.addText(dropboxTag, report);
                    }
                }
            }.start();
            return;
        }

        // System app batching:
        if (!bufferWasEmpty) {
            // An existing dropbox-writing thread is outstanding, so
            // we don't need to start it up.  The existing thread will
            // catch the buffer appends we just did.
            return;
        }

        // Worker thread to both batch writes and to avoid blocking the caller on I/O.
        // (After this point, we shouldn't access AMS internal data structures.)
        new Thread("Error dump: " + dropboxTag) {
            @Override
            public void run() {
                // 5 second sleep to let stacks arrive and be batched together
                try {
                    Thread.sleep(5000);  // 5 seconds
                } catch (InterruptedException e) {}

                String errorReport;
                synchronized (mStrictModeBuffer) {
                    errorReport = mStrictModeBuffer.toString();
                    if (errorReport.length() == 0) {
                        return;
                    }
                    mStrictModeBuffer.delete(0, mStrictModeBuffer.length());
                    mStrictModeBuffer.trimToSize();
                }
                dbox.addText(dropboxTag, errorReport);
            }
        }.start();
    }

    /**
     * Used by {@link Log} via {@link com.android.internal.os.RuntimeInit} to report serious errors.
     * @param app object of the crashing app, null for the system server
     * @param tag reported by the caller
     * @param crashInfo describing the context of the error
     * @return true if the process should exit immediately (WTF is fatal)
     */
    public boolean handleApplicationWtf(IBinder app, String tag,
            ApplicationErrorReport.CrashInfo crashInfo) {
        ProcessRecord r = findAppProcess(app, "WTF");

        EventLog.writeEvent(EventLogTags.AM_WTF, Binder.getCallingPid(),
                app == null ? "system" : (r == null ? "unknown" : r.processName),
                r == null ? -1 : r.info.flags,
                tag, crashInfo.exceptionMessage);

        addErrorToDropBox("wtf", r, null, null, tag, null, null, crashInfo);

        if (r != null && r.pid != Process.myPid() &&
                Settings.Secure.getInt(mContext.getContentResolver(),
                        Settings.Secure.WTF_IS_FATAL, 0) != 0) {
            crashApplication(r, crashInfo);
            return true;
        } else {
            return false;
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

        synchronized (this) {
            for (SparseArray<ProcessRecord> apps : mProcessNames.getMap().values()) {
                final int NA = apps.size();
                for (int ia=0; ia<NA; ia++) {
                    ProcessRecord p = apps.valueAt(ia);
                    if (p.thread != null && p.thread.asBinder() == app) {
                        return p;
                    }
                }
            }

            Slog.w(TAG, "Can't find mystery application for " + reason
                    + " from pid=" + Binder.getCallingPid()
                    + " uid=" + Binder.getCallingUid() + ": " + app);
            return null;
        }
    }

    /**
     * Utility function for addErrorToDropBox and handleStrictModeViolation's logging
     * to append various headers to the dropbox log text.
     */
    private void appendDropBoxProcessHeaders(ProcessRecord process, StringBuilder sb) {
        // Watchdog thread ends up invoking this function (with
        // a null ProcessRecord) to add the stack file to dropbox.
        // Do not acquire a lock on this (am) in such cases, as it
        // could cause a potential deadlock, if and when watchdog
        // is invoked due to unavailability of lock on am and it
        // would prevent watchdog from killing system_server.
        if (process == null) {
            sb.append("Process: system_server\n");
            return;
        }
        // Note: ProcessRecord 'process' is guarded by the service
        // instance.  (notably process.pkgList, which could otherwise change
        // concurrently during execution of this method)
        synchronized (this) {
            if (process.pid == MY_PID) {
                sb.append("Process: system_server\n");
            } else {
                sb.append("Process: ").append(process.processName).append("\n");
            }
            int flags = process.info.flags;
            IPackageManager pm = AppGlobals.getPackageManager();
            sb.append("Flags: 0x").append(Integer.toString(flags, 16)).append("\n");
            for (String pkg : process.pkgList) {
                sb.append("Package: ").append(pkg);
                try {
                    PackageInfo pi = pm.getPackageInfo(pkg, 0);
                    if (pi != null) {
                        sb.append(" v").append(pi.versionCode);
                        if (pi.versionName != null) {
                            sb.append(" (").append(pi.versionName).append(")");
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error getting package info: " + pkg, e);
                }
                sb.append("\n");
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

    /**
     * Write a description of an error (crash, WTF, ANR) to the drop box.
     * @param eventType to include in the drop box tag ("crash", "wtf", etc.)
     * @param process which caused the error, null means the system server
     * @param activity which triggered the error, null if unknown
     * @param parent activity related to the error, null if unknown
     * @param subject line related to the error, null if absent
     * @param report in long form describing the error, null if absent
     * @param logFile to include in the report, null if none
     * @param crashInfo giving an application stack trace, null if absent
     */
    public void addErrorToDropBox(String eventType,
            ProcessRecord process, ActivityRecord activity, ActivityRecord parent, String subject,
            final String report, final File logFile,
            final ApplicationErrorReport.CrashInfo crashInfo) {
        // NOTE -- this must never acquire the ActivityManagerService lock,
        // otherwise the watchdog may be prevented from resetting the system.

        final String dropboxTag = processClass(process) + "_" + eventType;
        final DropBoxManager dbox = (DropBoxManager)
                mContext.getSystemService(Context.DROPBOX_SERVICE);

        // Exit early if the dropbox isn't configured to accept this report type.
        if (dbox == null || !dbox.isTagEnabled(dropboxTag)) return;

        final StringBuilder sb = new StringBuilder(1024);
        appendDropBoxProcessHeaders(process, sb);
        if (activity != null) {
            sb.append("Activity: ").append(activity.shortComponentName).append("\n");
        }
        if (parent != null && parent.app != null && parent.app.pid != process.pid) {
            sb.append("Parent-Process: ").append(parent.app.processName).append("\n");
        }
        if (parent != null && parent != activity) {
            sb.append("Parent-Activity: ").append(parent.shortComponentName).append("\n");
        }
        if (subject != null) {
            sb.append("Subject: ").append(subject).append("\n");
        }
        sb.append("Build: ").append(Build.FINGERPRINT).append("\n");
        if (Debug.isDebuggerConnected()) {
            sb.append("Debugger: Connected\n");
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
                if (logFile != null) {
                    try {
                        sb.append(FileUtils.readTextFile(logFile, 128 * 1024, "\n\n[[TRUNCATED]]"));
                    } catch (IOException e) {
                        Slog.e(TAG, "Error reading " + logFile, e);
                    }
                }
                if (crashInfo != null && crashInfo.stackTrace != null) {
                    sb.append(crashInfo.stackTrace);
                }

                String setting = Settings.Secure.ERROR_LOGCAT_PREFIX + dropboxTag;
                int lines = Settings.Secure.getInt(mContext.getContentResolver(), setting, 0);
                if (lines > 0) {
                    sb.append("\n");

                    // Merge several logcat streams, and take the last N lines
                    InputStreamReader input = null;
                    try {
                        java.lang.Process logcat = new ProcessBuilder("/system/bin/logcat",
                                "-v", "time", "-b", "events", "-b", "system", "-b", "main",
                                "-t", String.valueOf(lines)).redirectErrorStream(true).start();

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

        if (process == null || process.pid == MY_PID) {
            worker.run();  // We may be about to die -- need to run this synchronously
        } else {
            worker.start();
        }
    }

    /**
     * Bring up the "unexpected error" dialog box for a crashing app.
     * Deal with edge cases (intercepts from instrumented applications,
     * ActivityController, error intent receivers, that sort of thing).
     * @param r the application crashing
     * @param crashInfo describing the failure
     */
    private void crashApplication(ProcessRecord r, ApplicationErrorReport.CrashInfo crashInfo) {
        long timeMillis = System.currentTimeMillis();
        String shortMsg = crashInfo.exceptionClassName;
        String longMsg = crashInfo.exceptionMessage;
        String stackTrace = crashInfo.stackTrace;
        if (shortMsg != null && longMsg != null) {
            longMsg = shortMsg + ": " + longMsg;
        } else if (shortMsg != null) {
            longMsg = shortMsg;
        }

        AppErrorResult result = new AppErrorResult();
        synchronized (this) {
            if (mController != null) {
                try {
                    String name = r != null ? r.processName : null;
                    int pid = r != null ? r.pid : Binder.getCallingPid();
                    if (!mController.appCrashed(name, pid,
                            shortMsg, longMsg, timeMillis, crashInfo.stackTrace)) {
                        Slog.w(TAG, "Force-killing crashed app " + name
                                + " at watcher's request");
                        Process.killProcess(pid);
                        return;
                    }
                } catch (RemoteException e) {
                    mController = null;
                }
            }

            final long origId = Binder.clearCallingIdentity();

            // If this process is running instrumentation, finish it.
            if (r != null && r.instrumentationClass != null) {
                Slog.w(TAG, "Error in app " + r.processName
                      + " running instrumentation " + r.instrumentationClass + ":");
                if (shortMsg != null) Slog.w(TAG, "  " + shortMsg);
                if (longMsg != null) Slog.w(TAG, "  " + longMsg);
                Bundle info = new Bundle();
                info.putString("shortMsg", shortMsg);
                info.putString("longMsg", longMsg);
                finishInstrumentationLocked(r, Activity.RESULT_CANCELED, info);
                Binder.restoreCallingIdentity(origId);
                return;
            }

            // If we can't identify the process or it's already exceeded its crash quota,
            // quit right away without showing a crash dialog.
            if (r == null || !makeAppCrashingLocked(r, shortMsg, longMsg, stackTrace)) {
                Binder.restoreCallingIdentity(origId);
                return;
            }

            Message msg = Message.obtain();
            msg.what = SHOW_ERROR_MSG;
            HashMap data = new HashMap();
            data.put("result", result);
            data.put("app", r);
            msg.obj = data;
            mHandler.sendMessage(msg);

            Binder.restoreCallingIdentity(origId);
        }

        int res = result.get();

        Intent appErrorIntent = null;
        synchronized (this) {
            if (r != null) {
                mProcessCrashTimes.put(r.info.processName, r.info.uid,
                        SystemClock.uptimeMillis());
            }
            if (res == AppErrorDialog.FORCE_QUIT_AND_REPORT) {
                appErrorIntent = createAppErrorIntentLocked(r, timeMillis, crashInfo);
            }
        }

        if (appErrorIntent != null) {
            try {
                mContext.startActivity(appErrorIntent);
            } catch (ActivityNotFoundException e) {
                Slog.w(TAG, "bug report receiver dissappeared", e);
            }
        }
    }

    Intent createAppErrorIntentLocked(ProcessRecord r,
            long timeMillis, ApplicationErrorReport.CrashInfo crashInfo) {
        ApplicationErrorReport report = createAppErrorReportLocked(r, timeMillis, crashInfo);
        if (report == null) {
            return null;
        }
        Intent result = new Intent(Intent.ACTION_APP_ERROR);
        result.setComponent(r.errorReportReceiver);
        result.putExtra(Intent.EXTRA_BUG_REPORT, report);
        result.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return result;
    }

    private ApplicationErrorReport createAppErrorReportLocked(ProcessRecord r,
            long timeMillis, ApplicationErrorReport.CrashInfo crashInfo) {
        if (r.errorReportReceiver == null) {
            return null;
        }

        if (!r.crashing && !r.notResponding) {
            return null;
        }

        ApplicationErrorReport report = new ApplicationErrorReport();
        report.packageName = r.info.packageName;
        report.installerPackageName = r.errorReportReceiver.getPackageName();
        report.processName = r.processName;
        report.time = timeMillis;
        report.systemApp = (r.info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

        if (r.crashing) {
            report.type = ApplicationErrorReport.TYPE_CRASH;
            report.crashInfo = crashInfo;
        } else if (r.notResponding) {
            report.type = ApplicationErrorReport.TYPE_ANR;
            report.anrInfo = new ApplicationErrorReport.AnrInfo();

            report.anrInfo.activity = r.notRespondingReport.tag;
            report.anrInfo.cause = r.notRespondingReport.shortMsg;
            report.anrInfo.info = r.notRespondingReport.longMsg;
        }

        return report;
    }

    public List<ActivityManager.ProcessErrorStateInfo> getProcessesInErrorState() {
        // assume our apps are happy - lazy create the list
        List<ActivityManager.ProcessErrorStateInfo> errList = null;

        synchronized (this) {

            // iterate across all processes
            for (int i=mLruProcesses.size()-1; i>=0; i--) {
                ProcessRecord app = mLruProcesses.get(i);
                if ((app.thread != null) && (app.crashing || app.notResponding)) {
                    // This one's in trouble, so we'll generate a report for it
                    // crashes are higher priority (in case there's a crash *and* an anr)
                    ActivityManager.ProcessErrorStateInfo report = null;
                    if (app.crashing) {
                        report = app.crashingReport;
                    } else if (app.notResponding) {
                        report = app.notRespondingReport;
                    }
                    
                    if (report != null) {
                        if (errList == null) {
                            errList = new ArrayList<ActivityManager.ProcessErrorStateInfo>(1);
                        }
                        errList.add(report);
                    } else {
                        Slog.w(TAG, "Missing app error report, app = " + app.processName + 
                                " crashing = " + app.crashing +
                                " notResponding = " + app.notResponding);
                    }
                }
            }
        }

        return errList;
    }
    
    public List<ActivityManager.RunningAppProcessInfo> getRunningAppProcesses() {
        // Lazy instantiation of list
        List<ActivityManager.RunningAppProcessInfo> runList = null;
        synchronized (this) {
            // Iterate across all processes
            for (int i=mLruProcesses.size()-1; i>=0; i--) {
                ProcessRecord app = mLruProcesses.get(i);
                if ((app.thread != null) && (!app.crashing && !app.notResponding)) {
                    // Generate process state info for running application
                    ActivityManager.RunningAppProcessInfo currApp = 
                        new ActivityManager.RunningAppProcessInfo(app.processName,
                                app.pid, app.getPackageList());
                    currApp.uid = app.info.uid;
                    if (mHeavyWeightProcess == app) {
                        currApp.flags |= ActivityManager.RunningAppProcessInfo.FLAG_CANT_SAVE_STATE;
                    }
                    if (app.persistent) {
                        currApp.flags |= ActivityManager.RunningAppProcessInfo.FLAG_PERSISTENT;
                    }
                    int adj = app.curAdj;
                    if (adj >= ProcessList.EMPTY_APP_ADJ) {
                        currApp.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY;
                    } else if (adj >= ProcessList.HIDDEN_APP_MIN_ADJ) {
                        currApp.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND;
                        currApp.lru = adj - ProcessList.HIDDEN_APP_MIN_ADJ + 1;
                    } else if (adj >= ProcessList.HOME_APP_ADJ) {
                        currApp.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND;
                        currApp.lru = 0;
                    } else if (adj >= ProcessList.SECONDARY_SERVER_ADJ) {
                        currApp.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE;
                    } else if (adj >= ProcessList.HEAVY_WEIGHT_APP_ADJ) {
                        currApp.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE;
                    } else if (adj >= ProcessList.PERCEPTIBLE_APP_ADJ) {
                        currApp.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE;
                    } else if (adj >= ProcessList.VISIBLE_APP_ADJ) {
                        currApp.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
                    } else {
                        currApp.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                    }
                    currApp.importanceReasonCode = app.adjTypeCode;
                    if (app.adjSource instanceof ProcessRecord) {
                        currApp.importanceReasonPid = ((ProcessRecord)app.adjSource).pid;
                    } else if (app.adjSource instanceof ActivityRecord) {
                        ActivityRecord r = (ActivityRecord)app.adjSource;
                        if (r.app != null) currApp.importanceReasonPid = r.app.pid;
                    }
                    if (app.adjTarget instanceof ComponentName) {
                        currApp.importanceReasonComponent = (ComponentName)app.adjTarget;
                    }
                    //Slog.v(TAG, "Proc " + app.processName + ": imp=" + currApp.importance
                    //        + " lru=" + currApp.lru);
                    if (runList == null) {
                        runList = new ArrayList<ActivityManager.RunningAppProcessInfo>();
                    }
                    runList.add(currApp);
                }
            }
        }
        return runList;
    }

    public List<ApplicationInfo> getRunningExternalApplications() {
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
                    ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
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
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (checkCallingPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ActivityManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " without permission "
                    + android.Manifest.permission.DUMP);
            return;
        }
        
        boolean dumpAll = false;
        boolean dumpClient = false;
        
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
            } else if ("-h".equals(opt)) {
                pw.println("Activity manager dump options:");
                pw.println("  [-a] [-c] [-h] [cmd] ...");
                pw.println("  cmd may be one of:");
                pw.println("    a[ctivities]: activity stack state");
                pw.println("    b[roadcasts]: broadcast state");
                pw.println("    i[ntents]: pending intent state");
                pw.println("    p[rocesses]: process state");
                pw.println("    o[om]: out of memory management");
                pw.println("    prov[iders]: content provider state");
                pw.println("    s[ervices]: service state");
                pw.println("    service [COMP_SPEC]: service client-side state");
                pw.println("  cmd may also be a COMP_SPEC to dump activities.");
                pw.println("  COMP_SPEC may also be a component name (com.foo/.myApp),");
                pw.println("    a partial substring in a component name, an");
                pw.println("    ActivityRecord hex object identifier, or");
                pw.println("    \"all\" for all objects, or");
                pw.println("    \"top\" for the top activity.");
                pw.println("  -a: include all available server state.");
                pw.println("  -c: include client state.");
                return;
            } else {
                pw.println("Unknown argument: " + opt + "; use -h for help");
            }
        }
        
        // Is the caller requesting to dump a particular piece of data?
        if (opti < args.length) {
            String cmd = args[opti];
            opti++;
            if ("activities".equals(cmd) || "a".equals(cmd)) {
                synchronized (this) {
                    dumpActivitiesLocked(fd, pw, args, opti, true, dumpClient);
                }
                return;
            } else if ("broadcasts".equals(cmd) || "b".equals(cmd)) {
                synchronized (this) {
                    dumpBroadcastsLocked(fd, pw, args, opti, true);
                }
                return;
            } else if ("intents".equals(cmd) || "i".equals(cmd)) {
                synchronized (this) {
                    dumpPendingIntentsLocked(fd, pw, args, opti, true);
                }
                return;
            } else if ("processes".equals(cmd) || "p".equals(cmd)) {
                synchronized (this) {
                    dumpProcessesLocked(fd, pw, args, opti, true);
                }
                return;
            } else if ("oom".equals(cmd) || "o".equals(cmd)) {
                synchronized (this) {
                    dumpOomLocked(fd, pw, args, opti, true);
                }
                return;
            } else if ("providers".equals(cmd) || "prov".equals(cmd)) {
                synchronized (this) {
                    dumpProvidersLocked(fd, pw, args, opti, true);
                }
                return;
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
                    if (args.length > 2) System.arraycopy(args, opti, newArgs, 0, args.length - opti);
                }
                if (!dumpService(fd, pw, name, newArgs, 0, dumpAll)) {
                    pw.println("No services match: " + name);
                    pw.println("Use -h for help.");
                }
                return;
            } else if ("services".equals(cmd) || "s".equals(cmd)) {
                synchronized (this) {
                    dumpServicesLocked(fd, pw, args, opti, true, dumpClient);
                }
                return;
            } else {
                // Dumping a single activity?
                if (!dumpActivity(fd, pw, cmd, args, opti, dumpAll)) {
                    pw.println("Bad activity command, or no activities match: " + cmd);
                    pw.println("Use -h for help.");
                }
                return;
            }
        }
        
        // No piece of data specified, dump everything.
        synchronized (this) {
            boolean needSep;
            needSep = dumpPendingIntentsLocked(fd, pw, args, opti, dumpAll);
            if (needSep) {
                pw.println(" ");
            }
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            needSep = dumpBroadcastsLocked(fd, pw, args, opti, dumpAll);
            if (needSep) {
                pw.println(" ");
            }
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            needSep = dumpProvidersLocked(fd, pw, args, opti, dumpAll);
            if (needSep) {
                pw.println(" ");
            }
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            needSep = dumpServicesLocked(fd, pw, args, opti, dumpAll, dumpClient);
            if (needSep) {
                pw.println(" ");
            }
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            needSep = dumpActivitiesLocked(fd, pw, args, opti, dumpAll, dumpClient);
            if (needSep) {
                pw.println(" ");
            }
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            dumpProcessesLocked(fd, pw, args, opti, dumpAll);
        }
    }
    
    boolean dumpActivitiesLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, boolean dumpClient) {
        pw.println("ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)");
        pw.println("  Main stack:");
        dumpHistoryList(fd, pw, mMainStack.mHistory, "  ", "Hist", true, !dumpAll, dumpClient);
        pw.println(" ");
        pw.println("  Running activities (most recent first):");
        dumpHistoryList(fd, pw, mMainStack.mLRUActivities, "  ", "Run", false, !dumpAll, false);
        if (mMainStack.mWaitingVisibleActivities.size() > 0) {
            pw.println(" ");
            pw.println("  Activities waiting for another to become visible:");
            dumpHistoryList(fd, pw, mMainStack.mWaitingVisibleActivities, "  ", "Wait", false,
                    !dumpAll, false);
        }
        if (mMainStack.mStoppingActivities.size() > 0) {
            pw.println(" ");
            pw.println("  Activities waiting to stop:");
            dumpHistoryList(fd, pw, mMainStack.mStoppingActivities, "  ", "Stop", false,
                    !dumpAll, false);
        }
        if (mMainStack.mGoingToSleepActivities.size() > 0) {
            pw.println(" ");
            pw.println("  Activities waiting to sleep:");
            dumpHistoryList(fd, pw, mMainStack.mGoingToSleepActivities, "  ", "Sleep", false,
                    !dumpAll, false);
        }
        if (mMainStack.mFinishingActivities.size() > 0) {
            pw.println(" ");
            pw.println("  Activities waiting to finish:");
            dumpHistoryList(fd, pw, mMainStack.mFinishingActivities, "  ", "Fin", false,
                    !dumpAll, false);
        }

        pw.println(" ");
        if (mMainStack.mPausingActivity != null) {
            pw.println("  mPausingActivity: " + mMainStack.mPausingActivity);
        }
        pw.println("  mResumedActivity: " + mMainStack.mResumedActivity);
        pw.println("  mFocusedActivity: " + mFocusedActivity);
        if (dumpAll) {
            pw.println("  mLastPausedActivity: " + mMainStack.mLastPausedActivity);
            pw.println("  mSleepTimeout: " + mMainStack.mSleepTimeout);
        }

        if (mRecentTasks.size() > 0) {
            pw.println();
            pw.println("  Recent tasks:");

            final int N = mRecentTasks.size();
            for (int i=0; i<N; i++) {
                TaskRecord tr = mRecentTasks.get(i);
                pw.print("  * Recent #"); pw.print(i); pw.print(": ");
                        pw.println(tr);
                if (dumpAll) {
                    mRecentTasks.get(i).dump(pw, "    ");
                }
            }
        }
        
        if (dumpAll) {
            pw.println(" ");
            pw.println("  mCurTask: " + mCurTask);
        }
        
        return true;
    }

    boolean dumpProcessesLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll) {
        boolean needSep = false;
        int numPers = 0;

        pw.println("ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)");

        if (dumpAll) {
            for (SparseArray<ProcessRecord> procs : mProcessNames.getMap().values()) {
                final int NA = procs.size();
                for (int ia=0; ia<NA; ia++) {
                    if (!needSep) {
                        pw.println("  All known processes:");
                        needSep = true;
                    }
                    ProcessRecord r = procs.valueAt(ia);
                    pw.print(r.persistent ? "  *PERS*" : "  *APP*");
                        pw.print(" UID "); pw.print(procs.keyAt(ia));
                        pw.print(" "); pw.println(r);
                    r.dump(pw, "    ");
                    if (r.persistent) {
                        numPers++;
                    }
                }
            }
        }
        
        if (mLruProcesses.size() > 0) {
            if (needSep) pw.println(" ");
            needSep = true;
            pw.println("  Process LRU list (most recent first):");
            dumpProcessOomList(pw, this, mLruProcesses, "    ",
                    "Proc", "PERS", false);
            needSep = true;
        }

        if (dumpAll) {
            synchronized (mPidsSelfLocked) {
                if (mPidsSelfLocked.size() > 0) {
                    if (needSep) pw.println(" ");
                    needSep = true;
                    pw.println("  PID mappings:");
                    for (int i=0; i<mPidsSelfLocked.size(); i++) {
                        pw.print("    PID #"); pw.print(mPidsSelfLocked.keyAt(i));
                            pw.print(": "); pw.println(mPidsSelfLocked.valueAt(i));
                    }
                }
            }
        }
        
        if (mForegroundProcesses.size() > 0) {
            if (needSep) pw.println(" ");
            needSep = true;
            pw.println("  Foreground Processes:");
            for (int i=0; i<mForegroundProcesses.size(); i++) {
                pw.print("    PID #"); pw.print(mForegroundProcesses.keyAt(i));
                        pw.print(": "); pw.println(mForegroundProcesses.valueAt(i));
            }
        }
        
        if (mPersistentStartingProcesses.size() > 0) {
            if (needSep) pw.println(" ");
            needSep = true;
            pw.println("  Persisent processes that are starting:");
            dumpProcessList(pw, this, mPersistentStartingProcesses, "    ",
                    "Starting Norm", "Restarting PERS");
        }

        if (mRemovedProcesses.size() > 0) {
            if (needSep) pw.println(" ");
            needSep = true;
            pw.println("  Processes that are being removed:");
            dumpProcessList(pw, this, mRemovedProcesses, "    ",
                    "Removed Norm", "Removed PERS");
        }
        
        if (mProcessesOnHold.size() > 0) {
            if (needSep) pw.println(" ");
            needSep = true;
            pw.println("  Processes that are on old until the system is ready:");
            dumpProcessList(pw, this, mProcessesOnHold, "    ",
                    "OnHold Norm", "OnHold PERS");
        }

        needSep = dumpProcessesToGc(fd, pw, args, opti, needSep, dumpAll);
        
        if (mProcessCrashTimes.getMap().size() > 0) {
            if (needSep) pw.println(" ");
            needSep = true;
            pw.println("  Time since processes crashed:");
            long now = SystemClock.uptimeMillis();
            for (Map.Entry<String, SparseArray<Long>> procs
                    : mProcessCrashTimes.getMap().entrySet()) {
                SparseArray<Long> uids = procs.getValue();
                final int N = uids.size();
                for (int i=0; i<N; i++) {
                    pw.print("    Process "); pw.print(procs.getKey());
                            pw.print(" uid "); pw.print(uids.keyAt(i));
                            pw.print(": last crashed ");
                            pw.print((now-uids.valueAt(i)));
                            pw.println(" ms ago");
                }
            }
        }

        if (mBadProcesses.getMap().size() > 0) {
            if (needSep) pw.println(" ");
            needSep = true;
            pw.println("  Bad processes:");
            for (Map.Entry<String, SparseArray<Long>> procs
                    : mBadProcesses.getMap().entrySet()) {
                SparseArray<Long> uids = procs.getValue();
                final int N = uids.size();
                for (int i=0; i<N; i++) {
                    pw.print("    Bad process "); pw.print(procs.getKey());
                            pw.print(" uid "); pw.print(uids.keyAt(i));
                            pw.print(": crashed at time ");
                            pw.println(uids.valueAt(i));
                }
            }
        }

        pw.println();
        pw.println("  mHomeProcess: " + mHomeProcess);
        if (mHeavyWeightProcess != null) {
            pw.println("  mHeavyWeightProcess: " + mHeavyWeightProcess);
        }
        pw.println("  mConfiguration: " + mConfiguration);
        if (dumpAll) {
            pw.println("  mConfigWillChange: " + mMainStack.mConfigWillChange);
            if (mCompatModePackages.getPackages().size() > 0) {
                pw.println("  mScreenCompatPackages:");
                for (Map.Entry<String, Integer> entry
                        : mCompatModePackages.getPackages().entrySet()) {
                    String pkg = entry.getKey();
                    int mode = entry.getValue();
                    pw.print("    "); pw.print(pkg); pw.print(": ");
                            pw.print(mode); pw.println();
                }
            }
        }
        pw.println("  mSleeping=" + mSleeping + " mShuttingDown=" + mShuttingDown);
        if (mDebugApp != null || mOrigDebugApp != null || mDebugTransient
                || mOrigWaitForDebugger) {
            pw.println("  mDebugApp=" + mDebugApp + "/orig=" + mOrigDebugApp
                    + " mDebugTransient=" + mDebugTransient
                    + " mOrigWaitForDebugger=" + mOrigWaitForDebugger);
        }
        if (mAlwaysFinishActivities || mController != null) {
            pw.println("  mAlwaysFinishActivities=" + mAlwaysFinishActivities
                    + " mController=" + mController);
        }
        if (dumpAll) {
            pw.println("  Total persistent processes: " + numPers);
            pw.println("  mStartRunning=" + mStartRunning
                    + " mProcessesReady=" + mProcessesReady
                    + " mSystemReady=" + mSystemReady);
            pw.println("  mBooting=" + mBooting
                    + " mBooted=" + mBooted
                    + " mFactoryTest=" + mFactoryTest);
            pw.print("  mLastPowerCheckRealtime=");
                    TimeUtils.formatDuration(mLastPowerCheckRealtime, pw);
                    pw.println("");
            pw.print("  mLastPowerCheckUptime=");
                    TimeUtils.formatDuration(mLastPowerCheckUptime, pw);
                    pw.println("");
            pw.println("  mGoingToSleep=" + mMainStack.mGoingToSleep);
            pw.println("  mLaunchingActivity=" + mMainStack.mLaunchingActivity);
            pw.println("  mAdjSeq=" + mAdjSeq + " mLruSeq=" + mLruSeq);
        }
        
        return true;
    }

    boolean dumpProcessesToGc(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean needSep, boolean dumpAll) {
        if (mProcessesToGc.size() > 0) {
            if (needSep) pw.println(" ");
            needSep = true;
            pw.println("  Processes that are waiting to GC:");
            long now = SystemClock.uptimeMillis();
            for (int i=0; i<mProcessesToGc.size(); i++) {
                ProcessRecord proc = mProcessesToGc.get(i);
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

    boolean dumpOomLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll) {
        boolean needSep = false;

        if (mLruProcesses.size() > 0) {
            ArrayList<ProcessRecord> procs = new ArrayList<ProcessRecord>(mLruProcesses);

            Comparator<ProcessRecord> comparator = new Comparator<ProcessRecord>() {
                @Override
                public int compare(ProcessRecord object1, ProcessRecord object2) {
                    if (object1.setAdj != object2.setAdj) {
                        return object1.setAdj > object2.setAdj ? -1 : 1;
                    }
                    if (object1.setSchedGroup != object2.setSchedGroup) {
                        return object1.setSchedGroup > object2.setSchedGroup ? -1 : 1;
                    }
                    if (object1.keeping != object2.keeping) {
                        return object1.keeping ? -1 : 1;
                    }
                    if (object1.pid != object2.pid) {
                        return object1.pid > object2.pid ? -1 : 1;
                    }
                    return 0;
                }
            };

            Collections.sort(procs, comparator);

            if (needSep) pw.println(" ");
            needSep = true;
            pw.println("  OOM levels:");
            pw.print("    SYSTEM_ADJ: "); pw.println(ProcessList.SYSTEM_ADJ);
            pw.print("    CORE_SERVER_ADJ: "); pw.println(ProcessList.CORE_SERVER_ADJ);
            pw.print("    FOREGROUND_APP_ADJ: "); pw.println(ProcessList.FOREGROUND_APP_ADJ);
            pw.print("    VISIBLE_APP_ADJ: "); pw.println(ProcessList.VISIBLE_APP_ADJ);
            pw.print("    PERCEPTIBLE_APP_ADJ: "); pw.println(ProcessList.PERCEPTIBLE_APP_ADJ);
            pw.print("    HEAVY_WEIGHT_APP_ADJ: "); pw.println(ProcessList.HEAVY_WEIGHT_APP_ADJ);
            pw.print("    BACKUP_APP_ADJ: "); pw.println(ProcessList.BACKUP_APP_ADJ);
            pw.print("    SECONDARY_SERVER_ADJ: "); pw.println(ProcessList.SECONDARY_SERVER_ADJ);
            pw.print("    HOME_APP_ADJ: "); pw.println(ProcessList.HOME_APP_ADJ);
            pw.print("    HIDDEN_APP_MIN_ADJ: "); pw.println(ProcessList.HIDDEN_APP_MIN_ADJ);
            pw.print("    EMPTY_APP_ADJ: "); pw.println(ProcessList.EMPTY_APP_ADJ);

            if (needSep) pw.println(" ");
            needSep = true;
            pw.println("  Process OOM control:");
            dumpProcessOomList(pw, this, procs, "    ",
                    "Proc", "PERS", true);
            needSep = true;
        }

        needSep = dumpProcessesToGc(fd, pw, args, opti, needSep, dumpAll);

        pw.println();
        pw.println("  mHomeProcess: " + mHomeProcess);
        if (mHeavyWeightProcess != null) {
            pw.println("  mHeavyWeightProcess: " + mHeavyWeightProcess);
        }

        return true;
    }

    /**
     * There are three ways to call this:
     *  - no service specified: dump all the services
     *  - a flattened component name that matched an existing service was specified as the
     *    first arg: dump that one service
     *  - the first arg isn't the flattened component name of an existing service:
     *    dump all services whose component contains the first arg as a substring
     */
    protected boolean dumpService(FileDescriptor fd, PrintWriter pw, String name, String[] args,
            int opti, boolean dumpAll) {
        ArrayList<ServiceRecord> services = new ArrayList<ServiceRecord>();

        if ("all".equals(name)) {
            synchronized (this) {
                for (ServiceRecord r1 : mServices.values()) {
                    services.add(r1);
                }
            }
        } else {
            ComponentName componentName = name != null
                    ? ComponentName.unflattenFromString(name) : null;
            int objectId = 0;
            if (componentName == null) {
                // Not a '/' separated full component name; maybe an object ID?
                try {
                    objectId = Integer.parseInt(name, 16);
                    name = null;
                    componentName = null;
                } catch (RuntimeException e) {
                }
            }

            synchronized (this) {
                for (ServiceRecord r1 : mServices.values()) {
                    if (componentName != null) {
                        if (r1.name.equals(componentName)) {
                            services.add(r1);
                        }
                    } else if (name != null) {
                        if (r1.name.flattenToString().contains(name)) {
                            services.add(r1);
                        }
                    } else if (System.identityHashCode(r1) == objectId) {
                        services.add(r1);
                    }
                }
            }
        }

        if (services.size() <= 0) {
            return false;
        }

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
        synchronized (this) {
            pw.print(prefix); pw.print("SERVICE ");
                    pw.print(r.shortName); pw.print(" ");
                    pw.print(Integer.toHexString(System.identityHashCode(r)));
                    pw.print(" pid=");
                    if (r.app != null) pw.println(r.app.pid);
                    else pw.println("(not running)");
            if (dumpAll) {
                r.dump(pw, innerPrefix);
            }
        }
        if (r.app != null && r.app.thread != null) {
            pw.print(prefix); pw.println("  Client:");
            pw.flush();
            try {
                TransferPipe tp = new TransferPipe();
                try {
                    r.app.thread.dumpService(tp.getWriteFd().getFileDescriptor(), r, args);
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
     * There are three things that cmd can be:
     *  - a flattened component name that matched an existing activity
     *  - the cmd arg isn't the flattened component name of an existing activity:
     *    dump all activity whose component contains the cmd as a substring
     *  - A hex number of the ActivityRecord object instance.
     */
    protected boolean dumpActivity(FileDescriptor fd, PrintWriter pw, String name, String[] args,
            int opti, boolean dumpAll) {
        ArrayList<ActivityRecord> activities = new ArrayList<ActivityRecord>();

        if ("all".equals(name)) {
            synchronized (this) {
                for (ActivityRecord r1 : (ArrayList<ActivityRecord>)mMainStack.mHistory) {
                    activities.add(r1);
                }
            }
        } else if ("top".equals(name)) {
            synchronized (this) {
                final int N = mMainStack.mHistory.size();
                if (N > 0) {
                    activities.add((ActivityRecord)mMainStack.mHistory.get(N-1));
                }
            }
        } else {
            ComponentName componentName = ComponentName.unflattenFromString(name);
            int objectId = 0;
            if (componentName == null) {
                // Not a '/' separated full component name; maybe an object ID?
                try {
                    objectId = Integer.parseInt(name, 16);
                    name = null;
                    componentName = null;
                } catch (RuntimeException e) {
                }
            }

            synchronized (this) {
                for (ActivityRecord r1 : (ArrayList<ActivityRecord>)mMainStack.mHistory) {
                    if (componentName != null) {
                        if (r1.intent.getComponent().equals(componentName)) {
                            activities.add(r1);
                        }
                    } else if (name != null) {
                        if (r1.intent.getComponent().flattenToString().contains(name)) {
                            activities.add(r1);
                        }
                    } else if (System.identityHashCode(r1) == objectId) {
                        activities.add(r1);
                    }
                }
            }
        }

        if (activities.size() <= 0) {
            return false;
        }

        String[] newArgs = new String[args.length - opti];
        if (args.length > 2) System.arraycopy(args, opti, newArgs, 0, args.length - opti);

        TaskRecord lastTask = null;
        boolean needSep = false;
        for (int i=activities.size()-1; i>=0; i--) {
            ActivityRecord r = (ActivityRecord)activities.get(i);
            if (needSep) {
                pw.println();
            }
            needSep = true;
            synchronized (this) {
                if (lastTask != r.task) {
                    lastTask = r.task;
                    pw.print("TASK "); pw.print(lastTask.affinity);
                            pw.print(" id="); pw.println(lastTask.taskId);
                    if (dumpAll) {
                        lastTask.dump(pw, "  ");
                    }
                }
            }
            dumpActivity("  ", fd, pw, activities.get(i), newArgs, dumpAll);
        }
        return true;
    }

    /**
     * Invokes IApplicationThread.dumpActivity() on the thread of the specified activity if
     * there is a thread associated with the activity.
     */
    private void dumpActivity(String prefix, FileDescriptor fd, PrintWriter pw,
            final ActivityRecord r, String[] args, boolean dumpAll) {
        String innerPrefix = prefix + "  ";
        synchronized (this) {
            pw.print(prefix); pw.print("ACTIVITY "); pw.print(r.shortComponentName);
                    pw.print(" "); pw.print(Integer.toHexString(System.identityHashCode(r)));
                    pw.print(" pid=");
                    if (r.app != null) pw.println(r.app.pid);
                    else pw.println("(not running)");
            if (dumpAll) {
                r.dump(pw, innerPrefix);
            }
        }
        if (r.app != null && r.app.thread != null) {
            // flush anything that is already in the PrintWriter since the thread is going
            // to write to the file descriptor directly
            pw.flush();
            try {
                TransferPipe tp = new TransferPipe();
                try {
                    r.app.thread.dumpActivity(tp.getWriteFd().getFileDescriptor(), r,
                            innerPrefix, args);
                    tp.go(fd);
                } finally {
                    tp.kill();
                }
            } catch (IOException e) {
                pw.println(innerPrefix + "Failure while dumping the activity: " + e);
            } catch (RemoteException e) {
                pw.println(innerPrefix + "Got a RemoteException while dumping the activity");
            }
        }
    }

    boolean dumpBroadcastsLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll) {
        boolean needSep = false;
        
        pw.println("ACTIVITY MANAGER BROADCAST STATE (dumpsys activity broadcasts)");
        if (dumpAll) {
            if (mRegisteredReceivers.size() > 0) {
                pw.println("  Registered Receivers:");
                Iterator it = mRegisteredReceivers.values().iterator();
                while (it.hasNext()) {
                    ReceiverList r = (ReceiverList)it.next();
                    pw.print("  * "); pw.println(r);
                    r.dump(pw, "    ");
                }
            }
    
            pw.println();
            pw.println("  Receiver Resolver Table:");
            mReceiverResolver.dump(pw, null, "    ", null, false);
            needSep = true;
        }
        
        if (mParallelBroadcasts.size() > 0 || mOrderedBroadcasts.size() > 0
                || mPendingBroadcast != null) {
            if (mParallelBroadcasts.size() > 0) {
                pw.println();
                pw.println("  Active broadcasts:");
            }
            for (int i=mParallelBroadcasts.size()-1; i>=0; i--) {
                pw.println("  Broadcast #" + i + ":");
                mParallelBroadcasts.get(i).dump(pw, "    ");
            }
            if (mOrderedBroadcasts.size() > 0) {
                pw.println();
                pw.println("  Active ordered broadcasts:");
            }
            for (int i=mOrderedBroadcasts.size()-1; i>=0; i--) {
                pw.println("  Serialized Broadcast #" + i + ":");
                mOrderedBroadcasts.get(i).dump(pw, "    ");
            }
            pw.println();
            pw.println("  Pending broadcast:");
            if (mPendingBroadcast != null) {
                mPendingBroadcast.dump(pw, "    ");
            } else {
                pw.println("    (null)");
            }
            needSep = true;
        }

        if (needSep) {
            pw.println();
        }
        pw.println("  Historical broadcasts:");
        for (int i=0; i<MAX_BROADCAST_HISTORY; i++) {
            BroadcastRecord r = mBroadcastHistory[i];
            if (r == null) {
                break;
            }
            if (dumpAll) {
                pw.print("  Historical Broadcast #"); pw.print(i); pw.println(":");
                r.dump(pw, "    ");
            } else {
                if (i >= 50) {
                    pw.println("  ...");
                    break;
                }
                pw.print("  #"); pw.print(i); pw.print(": "); pw.println(r);
            }
        }
        needSep = true;
        
        if (mStickyBroadcasts != null) {
            pw.println();
            pw.println("  Sticky broadcasts:");
            StringBuilder sb = new StringBuilder(128);
            for (Map.Entry<String, ArrayList<Intent>> ent
                    : mStickyBroadcasts.entrySet()) {
                pw.print("  * Sticky action "); pw.print(ent.getKey());
                if (dumpAll) {
                    pw.println(":");
                    ArrayList<Intent> intents = ent.getValue();
                    final int N = intents.size();
                    for (int i=0; i<N; i++) {
                        sb.setLength(0);
                        sb.append("    Intent: ");
                        intents.get(i).toShortString(sb, true, false);
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
            needSep = true;
        }
        
        if (dumpAll) {
            pw.println();
            pw.println("  mBroadcastsScheduled=" + mBroadcastsScheduled);
            pw.println("  mHandler:");
            mHandler.dump(new PrintWriterPrinter(pw), "    ");
            needSep = true;
        }
        
        return needSep;
    }

    boolean dumpServicesLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, boolean dumpClient) {
        boolean needSep = false;

        pw.println("ACTIVITY MANAGER SERVICES (dumpsys activity services)");
        if (mServices.size() > 0) {
            pw.println("  Active services:");
            long nowReal = SystemClock.elapsedRealtime();
            Iterator<ServiceRecord> it = mServices.values().iterator();
            needSep = false;
            while (it.hasNext()) {
                ServiceRecord r = it.next();
                if (needSep) {
                    pw.println();
                }
                pw.print("  * "); pw.println(r);
                if (dumpAll) {
                    r.dump(pw, "    ");
                    needSep = true;
                } else {
                    pw.print("    app="); pw.println(r.app);
                    pw.print("    created=");
                        TimeUtils.formatDuration(r.createTime, nowReal, pw);
                        pw.print(" started="); pw.print(r.startRequested);
                        pw.print(" connections="); pw.println(r.connections.size());
                }
                if (dumpClient && r.app != null && r.app.thread != null) {
                    pw.println("    Client:");
                    pw.flush();
                    try {
                        TransferPipe tp = new TransferPipe();
                        try {
                            r.app.thread.dumpService(
                                    tp.getWriteFd().getFileDescriptor(), r, args);
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
            }
            needSep = true;
        }

        if (mPendingServices.size() > 0) {
            if (needSep) pw.println(" ");
            pw.println("  Pending services:");
            for (int i=0; i<mPendingServices.size(); i++) {
                ServiceRecord r = mPendingServices.get(i);
                pw.print("  * Pending "); pw.println(r);
                r.dump(pw, "    ");
            }
            needSep = true;
        }

        if (mRestartingServices.size() > 0) {
            if (needSep) pw.println(" ");
            pw.println("  Restarting services:");
            for (int i=0; i<mRestartingServices.size(); i++) {
                ServiceRecord r = mRestartingServices.get(i);
                pw.print("  * Restarting "); pw.println(r);
                r.dump(pw, "    ");
            }
            needSep = true;
        }

        if (mStoppingServices.size() > 0) {
            if (needSep) pw.println(" ");
            pw.println("  Stopping services:");
            for (int i=0; i<mStoppingServices.size(); i++) {
                ServiceRecord r = mStoppingServices.get(i);
                pw.print("  * Stopping "); pw.println(r);
                r.dump(pw, "    ");
            }
            needSep = true;
        }

        if (dumpAll) {
            if (mServiceConnections.size() > 0) {
                if (needSep) pw.println(" ");
                pw.println("  Connection bindings to services:");
                Iterator<ArrayList<ConnectionRecord>> it
                        = mServiceConnections.values().iterator();
                while (it.hasNext()) {
                    ArrayList<ConnectionRecord> r = it.next();
                    for (int i=0; i<r.size(); i++) {
                        pw.print("  * "); pw.println(r.get(i));
                        r.get(i).dump(pw, "    ");
                    }
                }
                needSep = true;
            }
        }
        
        return needSep;
    }

    boolean dumpProvidersLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll) {
        boolean needSep = false;

        pw.println("ACTIVITY MANAGER CONTENT PROVIDERS (dumpsys activity providers)");
        if (mProvidersByClass.size() > 0) {
            if (needSep) pw.println(" ");
            pw.println("  Published content providers (by class):");
            Iterator<Map.Entry<String, ContentProviderRecord>> it
                    = mProvidersByClass.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, ContentProviderRecord> e = it.next();
                ContentProviderRecord r = e.getValue();
                if (dumpAll) {
                    pw.print("  * "); pw.println(r);
                    r.dump(pw, "    ");
                } else {
                    pw.print("  * "); pw.print(r.name.toShortString());
                    if (r.app != null) {
                        pw.println(":");
                        pw.print("      "); pw.println(r.app);
                    } else {
                        pw.println();
                    }
                }
            }
            needSep = true;
        }
    
        if (dumpAll) {
            if (mProvidersByName.size() > 0) {
                pw.println(" ");
                pw.println("  Authority to provider mappings:");
                Iterator<Map.Entry<String, ContentProviderRecord>> it
                        = mProvidersByName.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, ContentProviderRecord> e = it.next();
                    ContentProviderRecord r = e.getValue();
                    pw.print("  "); pw.print(e.getKey()); pw.print(": ");
                            pw.println(r);
                }
                needSep = true;
            }
        }

        if (mLaunchingProviders.size() > 0) {
            if (needSep) pw.println(" ");
            pw.println("  Launching content providers:");
            for (int i=mLaunchingProviders.size()-1; i>=0; i--) {
                pw.print("  Launching #"); pw.print(i); pw.print(": ");
                        pw.println(mLaunchingProviders.get(i));
            }
            needSep = true;
        }

        if (mGrantedUriPermissions.size() > 0) {
            pw.println();
            pw.println("Granted Uri Permissions:");
            for (int i=0; i<mGrantedUriPermissions.size(); i++) {
                int uid = mGrantedUriPermissions.keyAt(i);
                HashMap<Uri, UriPermission> perms
                        = mGrantedUriPermissions.valueAt(i);
                pw.print("  * UID "); pw.print(uid);
                        pw.println(" holds:");
                for (UriPermission perm : perms.values()) {
                    pw.print("    "); pw.println(perm);
                    if (dumpAll) {
                        perm.dump(pw, "      ");
                    }
                }
            }
            needSep = true;
        }
        
        return needSep;
    }

    boolean dumpPendingIntentsLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll) {
        boolean needSep = false;
        
        if (this.mIntentSenderRecords.size() > 0) {
            pw.println("ACTIVITY MANAGER PENDING INTENTS (dumpsys activity intents)");
            Iterator<WeakReference<PendingIntentRecord>> it
                    = mIntentSenderRecords.values().iterator();
            while (it.hasNext()) {
                WeakReference<PendingIntentRecord> ref = it.next();
                PendingIntentRecord rec = ref != null ? ref.get(): null;
                needSep = true;
                if (rec != null) {
                    pw.print("  * "); pw.println(rec);
                    if (dumpAll) {
                        rec.dump(pw, "    ");
                    }
                } else {
                    pw.print("  * "); pw.println(ref);
                }
            }
        }
        
        return needSep;
    }

    private static final void dumpHistoryList(FileDescriptor fd, PrintWriter pw, List list,
            String prefix, String label, boolean complete, boolean brief, boolean client) {
        TaskRecord lastTask = null;
        boolean needNL = false;
        final String innerPrefix = prefix + "      ";
        final String[] args = new String[0];
        for (int i=list.size()-1; i>=0; i--) {
            final ActivityRecord r = (ActivityRecord)list.get(i);
            final boolean full = !brief && (complete || !r.isInHistory());
            if (needNL) {
                pw.println(" ");
                needNL = false;
            }
            if (lastTask != r.task) {
                lastTask = r.task;
                pw.print(prefix);
                pw.print(full ? "* " : "  ");
                pw.println(lastTask);
                if (full) {
                    lastTask.dump(pw, prefix + "  ");
                } else if (complete) {
                    // Complete + brief == give a summary.  Isn't that obvious?!?
                    if (lastTask.intent != null) {
                        pw.print(prefix); pw.print("  "); pw.println(lastTask.intent);
                    }
                }
            }
            pw.print(prefix); pw.print(full ? "  * " : "    "); pw.print(label);
            pw.print(" #"); pw.print(i); pw.print(": ");
            pw.println(r);
            if (full) {
                r.dump(pw, innerPrefix);
            } else if (complete) {
                // Complete + brief == give a summary.  Isn't that obvious?!?
                pw.print(innerPrefix); pw.println(r.intent);
                if (r.app != null) {
                    pw.print(innerPrefix); pw.println(r.app);
                }
            }
            if (client && r.app != null && r.app.thread != null) {
                // flush anything that is already in the PrintWriter since the thread is going
                // to write to the file descriptor directly
                pw.flush();
                try {
                    TransferPipe tp = new TransferPipe();
                    try {
                        r.app.thread.dumpActivity(tp.getWriteFd().getFileDescriptor(), r,
                                innerPrefix, args);
                        // Short timeout, since blocking here can
                        // deadlock with the application.
                        tp.go(fd, 2000);
                    } finally {
                        tp.kill();
                    }
                } catch (IOException e) {
                    pw.println(innerPrefix + "Failure while dumping the activity: " + e);
                } catch (RemoteException e) {
                    pw.println(innerPrefix + "Got a RemoteException while dumping the activity");
                }
                needNL = true;
            }
        }
    }

    private static String buildOomTag(String prefix, String space, int val, int base) {
        if (val == base) {
            if (space == null) return prefix;
            return prefix + "  ";
        }
        return prefix + "+" + Integer.toString(val-base);
    }
    
    private static final int dumpProcessList(PrintWriter pw,
            ActivityManagerService service, List list,
            String prefix, String normalLabel, String persistentLabel) {
        int numPers = 0;
        final int N = list.size()-1;
        for (int i=N; i>=0; i--) {
            ProcessRecord r = (ProcessRecord)list.get(i);
            pw.println(String.format("%s%s #%2d: %s",
                    prefix, (r.persistent ? persistentLabel : normalLabel),
                    i, r.toString()));
            if (r.persistent) {
                numPers++;
            }
        }
        return numPers;
    }

    private static final void dumpProcessOomList(PrintWriter pw,
            ActivityManagerService service, List<ProcessRecord> list,
            String prefix, String normalLabel, String persistentLabel,
            boolean inclDetails) {

        final long curRealtime = SystemClock.elapsedRealtime();
        final long realtimeSince = curRealtime - service.mLastPowerCheckRealtime;
        final long curUptime = SystemClock.uptimeMillis();
        final long uptimeSince = curUptime - service.mLastPowerCheckUptime;

        final int N = list.size()-1;
        for (int i=N; i>=0; i--) {
            ProcessRecord r = list.get(i);
            String oomAdj;
            if (r.setAdj >= ProcessList.EMPTY_APP_ADJ) {
                oomAdj = buildOomTag("empty", null, r.setAdj, ProcessList.EMPTY_APP_ADJ);
            } else if (r.setAdj >= ProcessList.HIDDEN_APP_MIN_ADJ) {
                oomAdj = buildOomTag("bak", "  ", r.setAdj, ProcessList.HIDDEN_APP_MIN_ADJ);
            } else if (r.setAdj >= ProcessList.HOME_APP_ADJ) {
                oomAdj = buildOomTag("home ", null, r.setAdj, ProcessList.HOME_APP_ADJ);
            } else if (r.setAdj >= ProcessList.SECONDARY_SERVER_ADJ) {
                oomAdj = buildOomTag("svc", "  ", r.setAdj, ProcessList.SECONDARY_SERVER_ADJ);
            } else if (r.setAdj >= ProcessList.BACKUP_APP_ADJ) {
                oomAdj = buildOomTag("bckup", null, r.setAdj, ProcessList.BACKUP_APP_ADJ);
            } else if (r.setAdj >= ProcessList.HEAVY_WEIGHT_APP_ADJ) {
                oomAdj = buildOomTag("hvy  ", null, r.setAdj, ProcessList.HEAVY_WEIGHT_APP_ADJ);
            } else if (r.setAdj >= ProcessList.PERCEPTIBLE_APP_ADJ) {
                oomAdj = buildOomTag("prcp ", null, r.setAdj, ProcessList.PERCEPTIBLE_APP_ADJ);
            } else if (r.setAdj >= ProcessList.VISIBLE_APP_ADJ) {
                oomAdj = buildOomTag("vis  ", null, r.setAdj, ProcessList.VISIBLE_APP_ADJ);
            } else if (r.setAdj >= ProcessList.FOREGROUND_APP_ADJ) {
                oomAdj = buildOomTag("fore ", null, r.setAdj, ProcessList.FOREGROUND_APP_ADJ);
            } else if (r.setAdj >= ProcessList.CORE_SERVER_ADJ) {
                oomAdj = buildOomTag("core ", null, r.setAdj, ProcessList.CORE_SERVER_ADJ);
            } else if (r.setAdj >= ProcessList.SYSTEM_ADJ) {
                oomAdj = buildOomTag("sys  ", null, r.setAdj, ProcessList.SYSTEM_ADJ);
            } else {
                oomAdj = Integer.toString(r.setAdj);
            }
            String schedGroup;
            switch (r.setSchedGroup) {
                case Process.THREAD_GROUP_BG_NONINTERACTIVE:
                    schedGroup = "B";
                    break;
                case Process.THREAD_GROUP_DEFAULT:
                    schedGroup = "F";
                    break;
                default:
                    schedGroup = Integer.toString(r.setSchedGroup);
                    break;
            }
            String foreground;
            if (r.foregroundActivities) {
                foreground = "A";
            } else if (r.foregroundServices) {
                foreground = "S";
            } else {
                foreground = " ";
            }
            pw.println(String.format("%s%s #%2d: adj=%s/%s%s trm=%2d %s (%s)",
                    prefix, (r.persistent ? persistentLabel : normalLabel),
                    N-i, oomAdj, schedGroup, foreground, r.trimMemoryLevel,
                    r.toShortString(), r.adjType));
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
                pw.print(" hidden="); pw.print(r.hiddenAdj);
                pw.print(" curRaw="); pw.print(r.curRawAdj);
                pw.print(" setRaw="); pw.print(r.setRawAdj);
                pw.print(" cur="); pw.print(r.curAdj);
                pw.print(" set="); pw.println(r.setAdj);
                pw.print(prefix);
                pw.print("    ");
                pw.print("keeping="); pw.print(r.keeping);
                pw.print(" hidden="); pw.print(r.hidden);
                pw.print(" empty="); pw.print(r.empty);
                pw.print(" hasAboveClient="); pw.println(r.hasAboveClient);

                if (!r.keeping) {
                    if (r.lastWakeTime != 0) {
                        long wtime;
                        BatteryStatsImpl stats = service.mBatteryStatsService.getActiveStatistics();
                        synchronized (stats) {
                            wtime = stats.getProcessWakeTime(r.info.uid,
                                    r.pid, curRealtime);
                        }
                        long timeUsed = wtime - r.lastWakeTime;
                        pw.print(prefix);
                        pw.print("    ");
                        pw.print("keep awake over ");
                        TimeUtils.formatDuration(realtimeSince, pw);
                        pw.print(" used ");
                        TimeUtils.formatDuration(timeUsed, pw);
                        pw.print(" (");
                        pw.print((timeUsed*100)/realtimeSince);
                        pw.println("%)");
                    }
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
    }

    ArrayList<ProcessRecord> collectProcesses(PrintWriter pw, int start, String[] args) {
        ArrayList<ProcessRecord> procs;
        synchronized (this) {
            if (args != null && args.length > start
                    && args[start].charAt(0) != '-') {
                procs = new ArrayList<ProcessRecord>();
                int pid = -1;
                try {
                    pid = Integer.parseInt(args[start]);
                } catch (NumberFormatException e) {

                }
                for (int i=mLruProcesses.size()-1; i>=0; i--) {
                    ProcessRecord proc = mLruProcesses.get(i);
                    if (proc.pid == pid) {
                        procs.add(proc);
                    } else if (proc.processName.equals(args[start])) {
                        procs.add(proc);
                    }
                }
                if (procs.size() <= 0) {
                    pw.println("No process found for: " + args[start]);
                    return null;
                }
            } else {
                procs = new ArrayList<ProcessRecord>(mLruProcesses);
            }
        }
        return procs;
    }

    final void dumpGraphicsHardwareUsage(FileDescriptor fd,
            PrintWriter pw, String[] args) {
        ArrayList<ProcessRecord> procs = collectProcesses(pw, 0, args);
        if (procs == null) {
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
                        r.thread.dumpGfxInfo(tp.getWriteFd().getFileDescriptor(), args);
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
        final String label;
        final long pss;

        public MemItem(String _label, long _pss) {
            label = _label;
            pss = _pss;
        }
    }

    final void dumpMemItems(PrintWriter pw, String prefix, ArrayList<MemItem> items,
            boolean sort) {
        if (sort) {
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

        for (int i=0; i<items.size(); i++) {
            MemItem mi = items.get(i);
            pw.print(prefix); pw.printf("%8d Kb: ", mi.pss); pw.println(mi.label);
        }
    }

    final void dumpApplicationMemoryUsage(FileDescriptor fd,
            PrintWriter pw, String prefix, String[] args) {
        boolean dumpAll = false;
        
        int opti = 0;
        while (opti < args.length) {
            String opt = args[opti];
            if (opt == null || opt.length() <= 0 || opt.charAt(0) != '-') {
                break;
            }
            opti++;
            if ("-a".equals(opt)) {
                dumpAll = true;
            } else if ("-h".equals(opt)) {
                pw.println("meminfo dump options: [-a] [process]");
                pw.println("  -a: include all available information for each process.");
                pw.println("If [process] is specified it can be the name or ");
                pw.println("pid of a specific process to dump.");
                return;
            } else {
                pw.println("Unknown argument: " + opt + "; use -h for help");
            }
        }
        
        ArrayList<ProcessRecord> procs = collectProcesses(pw, opti, args);
        if (procs == null) {
            return;
        }

        final boolean isCheckinRequest = scanArgs(args, "--checkin");
        long uptime = SystemClock.uptimeMillis();
        long realtime = SystemClock.elapsedRealtime();

        if (procs.size() == 1 || isCheckinRequest) {
            dumpAll = true;
        }

        if (isCheckinRequest) {
            // short checkin version
            pw.println(uptime + "," + realtime);
            pw.flush();
        } else {
            pw.println("Applications Memory Usage (kB):");
            pw.println("Uptime: " + uptime + " Realtime: " + realtime);
        }

        String[] innerArgs = new String[args.length-opti];
        System.arraycopy(args, opti, innerArgs, 0, args.length-opti);

        ArrayList<MemItem> procMems = new ArrayList<MemItem>();
        long nativePss=0, dalvikPss=0, otherPss=0;
        long[] miscPss = new long[Debug.MemoryInfo.NUM_OTHER_STATS];

        final int[] oomAdj = new int[] {
            ProcessList.SYSTEM_ADJ, ProcessList.CORE_SERVER_ADJ, ProcessList.FOREGROUND_APP_ADJ,
            ProcessList.VISIBLE_APP_ADJ, ProcessList.PERCEPTIBLE_APP_ADJ, ProcessList.HEAVY_WEIGHT_APP_ADJ,
            ProcessList.BACKUP_APP_ADJ, ProcessList.SECONDARY_SERVER_ADJ, ProcessList.HOME_APP_ADJ, ProcessList.EMPTY_APP_ADJ
        };
        final String[] oomLabel = new String[] {
                "System", "Persistent", "Foreground",
                "Visible", "Perceptible", "Heavy Weight",
                "Backup", "Services", "Home", "Background"
        };
        long oomPss[] = new long[oomLabel.length];

        long totalPss = 0;

        for (int i = procs.size() - 1 ; i >= 0 ; i--) {
            ProcessRecord r = procs.get(i);
            if (r.thread != null) {
                if (!isCheckinRequest && dumpAll) {
                    pw.println("\n** MEMINFO in pid " + r.pid + " [" + r.processName + "] **");
                    pw.flush();
                }
                Debug.MemoryInfo mi = null;
                if (dumpAll) {
                    try {
                        mi = r.thread.dumpMemInfo(fd, isCheckinRequest, dumpAll, innerArgs);
                    } catch (RemoteException e) {
                        if (!isCheckinRequest) {
                            pw.println("Got RemoteException!");
                            pw.flush();
                        }
                    }
                } else {
                    mi = new Debug.MemoryInfo();
                    Debug.getMemoryInfo(r.pid, mi);
                }

                if (!isCheckinRequest && mi != null) {
                    long myTotalPss = mi.getTotalPss();
                    totalPss += myTotalPss;
                    procMems.add(new MemItem(r.processName + " (pid " + r.pid + ")", myTotalPss));

                    nativePss += mi.nativePss;
                    dalvikPss += mi.dalvikPss;
                    otherPss += mi.otherPss;
                    for (int j=0; j<Debug.MemoryInfo.NUM_OTHER_STATS; j++) {
                        long mem = mi.getOtherPss(j);
                        miscPss[j] += mem;
                        otherPss -= mem;
                    }

                    for (int oomIndex=0; oomIndex<oomPss.length; oomIndex++) {
                        if (r.setAdj <= oomAdj[oomIndex] || oomIndex == (oomPss.length-1)) {
                            oomPss[oomIndex] += myTotalPss;
                            break;
                        }
                    }
                }
            }
        }

        if (!isCheckinRequest && procs.size() > 1) {
            ArrayList<MemItem> catMems = new ArrayList<MemItem>();

            catMems.add(new MemItem("Native", nativePss));
            catMems.add(new MemItem("Dalvik", dalvikPss));
            catMems.add(new MemItem("Unknown", otherPss));
            for (int j=0; j<Debug.MemoryInfo.NUM_OTHER_STATS; j++) {
                catMems.add(new MemItem(Debug.MemoryInfo.getOtherLabel(j), miscPss[j]));
            }

            ArrayList<MemItem> oomMems = new ArrayList<MemItem>();
            for (int j=0; j<oomPss.length; j++) {
                if (oomPss[j] != 0) {
                    oomMems.add(new MemItem(oomLabel[j], oomPss[j]));
                }
            }

            pw.println();
            pw.println("Total PSS by process:");
            dumpMemItems(pw, "  ", procMems, true);
            pw.println();
            pw.println("Total PSS by OOM adjustment:");
            dumpMemItems(pw, "  ", oomMems, false);
            pw.println();
            pw.println("Total PSS by category:");
            dumpMemItems(pw, "  ", catMems, true);
            pw.println();
            pw.print("Total PSS: "); pw.print(totalPss); pw.println(" Kb");
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

    private final void killServicesLocked(ProcessRecord app,
            boolean allowRestart) {
        // Report disconnected services.
        if (false) {
            // XXX we are letting the client link to the service for
            // death notifications.
            if (app.services.size() > 0) {
                Iterator<ServiceRecord> it = app.services.iterator();
                while (it.hasNext()) {
                    ServiceRecord r = it.next();
                    if (r.connections.size() > 0) {
                        Iterator<ArrayList<ConnectionRecord>> jt
                                = r.connections.values().iterator();
                        while (jt.hasNext()) {
                            ArrayList<ConnectionRecord> cl = jt.next();
                            for (int i=0; i<cl.size(); i++) {
                                ConnectionRecord c = cl.get(i);
                                if (c.binding.client != app) {
                                    try {
                                        //c.conn.connected(r.className, null);
                                    } catch (Exception e) {
                                        // todo: this should be asynchronous!
                                        Slog.w(TAG, "Exception thrown disconnected servce "
                                              + r.shortName
                                              + " from app " + app.processName, e);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Clean up any connections this application has to other services.
        if (app.connections.size() > 0) {
            Iterator<ConnectionRecord> it = app.connections.iterator();
            while (it.hasNext()) {
                ConnectionRecord r = it.next();
                removeConnectionLocked(r, app, null);
            }
        }
        app.connections.clear();

        if (app.services.size() != 0) {
            // Any services running in the application need to be placed
            // back in the pending list.
            Iterator<ServiceRecord> it = app.services.iterator();
            while (it.hasNext()) {
                ServiceRecord sr = it.next();
                synchronized (sr.stats.getBatteryStats()) {
                    sr.stats.stopLaunchedLocked();
                }
                sr.app = null;
                sr.executeNesting = 0;
                if (mStoppingServices.remove(sr)) {
                    if (DEBUG_SERVICE) Slog.v(TAG, "killServices remove stopping " + sr);
                }
                
                boolean hasClients = sr.bindings.size() > 0;
                if (hasClients) {
                    Iterator<IntentBindRecord> bindings
                            = sr.bindings.values().iterator();
                    while (bindings.hasNext()) {
                        IntentBindRecord b = bindings.next();
                        if (DEBUG_SERVICE) Slog.v(TAG, "Killing binding " + b
                                + ": shouldUnbind=" + b.hasBound);
                        b.binder = null;
                        b.requested = b.received = b.hasBound = false;
                    }
                }

                if (sr.crashCount >= 2 && (sr.serviceInfo.applicationInfo.flags
                        &ApplicationInfo.FLAG_PERSISTENT) == 0) {
                    Slog.w(TAG, "Service crashed " + sr.crashCount
                            + " times, stopping: " + sr);
                    EventLog.writeEvent(EventLogTags.AM_SERVICE_CRASHED_TOO_MUCH,
                            sr.crashCount, sr.shortName, app.pid);
                    bringDownServiceLocked(sr, true);
                } else if (!allowRestart) {
                    bringDownServiceLocked(sr, true);
                } else {
                    boolean canceled = scheduleServiceRestartLocked(sr, true);
                    
                    // Should the service remain running?  Note that in the
                    // extreme case of so many attempts to deliver a command
                    // that it failed we also will stop it here.
                    if (sr.startRequested && (sr.stopIfKilled || canceled)) {
                        if (sr.pendingStarts.size() == 0) {
                            sr.startRequested = false;
                            if (!hasClients) {
                                // Whoops, no reason to restart!
                                bringDownServiceLocked(sr, true);
                            }
                        }
                    }
                }
            }

            if (!allowRestart) {
                app.services.clear();
            }
        }

        // Make sure we have no more records on the stopping list.
        int i = mStoppingServices.size();
        while (i > 0) {
            i--;
            ServiceRecord sr = mStoppingServices.get(i);
            if (sr.app == app) {
                mStoppingServices.remove(i);
                if (DEBUG_SERVICE) Slog.v(TAG, "killServices remove stopping " + sr);
            }
        }
        
        app.executingServices.clear();
    }

    private final void removeDyingProviderLocked(ProcessRecord proc,
            ContentProviderRecord cpr) {
        synchronized (cpr) {
            cpr.launchingApp = null;
            cpr.notifyAll();
        }
        
        mProvidersByClass.remove(cpr.info.name);
        String names[] = cpr.info.authority.split(";");
        for (int j = 0; j < names.length; j++) {
            mProvidersByName.remove(names[j]);
        }
        
        Iterator<ProcessRecord> cit = cpr.clients.iterator();
        while (cit.hasNext()) {
            ProcessRecord capp = cit.next();
            if (!capp.persistent && capp.thread != null
                    && capp.pid != 0
                    && capp.pid != MY_PID) {
                Slog.i(TAG, "Kill " + capp.processName
                        + " (pid " + capp.pid + "): provider " + cpr.info.name
                        + " in dying process " + proc.processName);
                EventLog.writeEvent(EventLogTags.AM_KILL, capp.pid,
                        capp.processName, capp.setAdj, "dying provider " + proc.processName);
                Process.killProcessQuiet(capp.pid);
            }
        }
        
        mLaunchingProviders.remove(cpr);
    }
    
    /**
     * Main code for cleaning up a process when it has gone away.  This is
     * called both as a result of the process dying, or directly when stopping 
     * a process when running in single process mode.
     */
    private final void cleanUpApplicationRecordLocked(ProcessRecord app,
            boolean restarting, boolean allowRestart, int index) {
        if (index >= 0) {
            mLruProcesses.remove(index);
        }

        mProcessesToGc.remove(app);
        
        // Dismiss any open dialogs.
        if (app.crashDialog != null) {
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

        app.crashing = false;
        app.notResponding = false;
        
        app.resetPackageList();
        app.unlinkDeathRecipient();
        app.thread = null;
        app.forcingToForeground = null;
        app.foregroundServices = false;
        app.foregroundActivities = false;
        app.hasShownUi = false;
        app.hasAboveClient = false;

        killServicesLocked(app, allowRestart);

        boolean restart = false;

        int NL = mLaunchingProviders.size();
        
        // Remove published content providers.
        if (!app.pubProviders.isEmpty()) {
            Iterator<ContentProviderRecord> it = app.pubProviders.values().iterator();
            while (it.hasNext()) {
                ContentProviderRecord cpr = it.next();
                cpr.provider = null;
                cpr.app = null;

                // See if someone is waiting for this provider...  in which
                // case we don't remove it, but just let it restart.
                int i = 0;
                if (!app.bad && allowRestart) {
                    for (; i<NL; i++) {
                        if (mLaunchingProviders.get(i) == cpr) {
                            restart = true;
                            break;
                        }
                    }
                } else {
                    i = NL;
                }

                if (i >= NL) {
                    removeDyingProviderLocked(app, cpr);
                    NL = mLaunchingProviders.size();
                }
            }
            app.pubProviders.clear();
        }
        
        // Take care of any launching providers waiting for this process.
        if (checkAppInLaunchingProvidersLocked(app, false)) {
            restart = true;
        }
        
        // Unregister from connected content providers.
        if (!app.conProviders.isEmpty()) {
            Iterator it = app.conProviders.keySet().iterator();
            while (it.hasNext()) {
                ContentProviderRecord cpr = (ContentProviderRecord)it.next();
                cpr.clients.remove(app);
            }
            app.conProviders.clear();
        }

        // At this point there may be remaining entries in mLaunchingProviders
        // where we were the only one waiting, so they are no longer of use.
        // Look for these and clean up if found.
        // XXX Commented out for now.  Trying to figure out a way to reproduce
        // the actual situation to identify what is actually going on.
        if (false) {
            for (int i=0; i<NL; i++) {
                ContentProviderRecord cpr = (ContentProviderRecord)
                        mLaunchingProviders.get(i);
                if (cpr.clients.size() <= 0 && cpr.externals <= 0) {
                    synchronized (cpr) {
                        cpr.launchingApp = null;
                        cpr.notifyAll();
                    }
                }
            }
        }
        
        skipCurrentReceiverLocked(app);

        // Unregister any receivers.
        if (app.receivers.size() > 0) {
            Iterator<ReceiverList> it = app.receivers.iterator();
            while (it.hasNext()) {
                removeReceiverLocked(it.next());
            }
            app.receivers.clear();
        }
        
        // If the app is undergoing backup, tell the backup manager about it
        if (mBackupTarget != null && app.pid == mBackupTarget.app.pid) {
            if (DEBUG_BACKUP) Slog.d(TAG, "App " + mBackupTarget.appInfo + " died during backup");
            try {
                IBackupManager bm = IBackupManager.Stub.asInterface(
                        ServiceManager.getService(Context.BACKUP_SERVICE));
                bm.agentDisconnected(app.info.packageName);
            } catch (RemoteException e) {
                // can't happen; backup manager is local
            }
        }

        mHandler.obtainMessage(DISPATCH_PROCESS_DIED, app.pid, app.info.uid, null).sendToTarget();

        // If the caller is restarting this app, then leave it in its
        // current lists and let the caller take care of it.
        if (restarting) {
            return;
        }

        if (!app.persistent) {
            if (DEBUG_PROCESSES) Slog.v(TAG,
                    "Removing non-persistent process during cleanup: " + app);
            mProcessNames.remove(app.processName, app.info.uid);
            if (mHeavyWeightProcess == app) {
                mHeavyWeightProcess = null;
                mHandler.sendEmptyMessage(CANCEL_HEAVY_NOTIFICATION_MSG);
            }
        } else if (!app.removed) {
            // This app is persistent, so we need to keep its record around.
            // If it is not already on the pending app list, add it there
            // and start a new process for it.
            if (mPersistentStartingProcesses.indexOf(app) < 0) {
                mPersistentStartingProcesses.add(app);
                restart = true;
            }
        }
        if (DEBUG_PROCESSES && mProcessesOnHold.contains(app)) Slog.v(TAG,
                "Clean-up removing on hold: " + app);
        mProcessesOnHold.remove(app);

        if (app == mHomeProcess) {
            mHomeProcess = null;
        }
        
        if (restart) {
            // We have components that still need to be running in the
            // process, so re-launch it.
            mProcessNames.put(app.processName, app.info.uid, app);
            startProcessLocked(app, "restart", app.processName);
        } else if (app.pid > 0 && app.pid != MY_PID) {
            // Goodbye!
            synchronized (mPidsSelfLocked) {
                mPidsSelfLocked.remove(app.pid);
                mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);
            }
            app.setPid(0);
        }
    }

    boolean checkAppInLaunchingProvidersLocked(ProcessRecord app, boolean alwaysBad) {
        // Look through the content providers we are waiting to have launched,
        // and if any run in this process then either schedule a restart of
        // the process or kill the client waiting for it if this process has
        // gone bad.
        int NL = mLaunchingProviders.size();
        boolean restart = false;
        for (int i=0; i<NL; i++) {
            ContentProviderRecord cpr = mLaunchingProviders.get(i);
            if (cpr.launchingApp == app) {
                if (!alwaysBad && !app.bad) {
                    restart = true;
                } else {
                    removeDyingProviderLocked(app, cpr);
                    NL = mLaunchingProviders.size();
                }
            }
        }
        return restart;
    }
    
    // =========================================================
    // SERVICES
    // =========================================================

    ActivityManager.RunningServiceInfo makeRunningServiceInfoLocked(ServiceRecord r) {
        ActivityManager.RunningServiceInfo info =
            new ActivityManager.RunningServiceInfo();
        info.service = r.name;
        if (r.app != null) {
            info.pid = r.app.pid;
        }
        info.uid = r.appInfo.uid;
        info.process = r.processName;
        info.foreground = r.isForeground;
        info.activeSince = r.createTime;
        info.started = r.startRequested;
        info.clientCount = r.connections.size();
        info.crashCount = r.crashCount;
        info.lastActivityTime = r.lastActivity;
        if (r.isForeground) {
            info.flags |= ActivityManager.RunningServiceInfo.FLAG_FOREGROUND;
        }
        if (r.startRequested) {
            info.flags |= ActivityManager.RunningServiceInfo.FLAG_STARTED;
        }
        if (r.app != null && r.app.pid == MY_PID) {
            info.flags |= ActivityManager.RunningServiceInfo.FLAG_SYSTEM_PROCESS;
        }
        if (r.app != null && r.app.persistent) {
            info.flags |= ActivityManager.RunningServiceInfo.FLAG_PERSISTENT_PROCESS;
        }

        for (ArrayList<ConnectionRecord> connl : r.connections.values()) {
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
    
    public List<ActivityManager.RunningServiceInfo> getServices(int maxNum,
            int flags) {
        synchronized (this) {
            ArrayList<ActivityManager.RunningServiceInfo> res
                    = new ArrayList<ActivityManager.RunningServiceInfo>();
            
            if (mServices.size() > 0) {
                Iterator<ServiceRecord> it = mServices.values().iterator();
                while (it.hasNext() && res.size() < maxNum) {
                    res.add(makeRunningServiceInfoLocked(it.next()));
                }
            }

            for (int i=0; i<mRestartingServices.size() && res.size() < maxNum; i++) {
                ServiceRecord r = mRestartingServices.get(i);
                ActivityManager.RunningServiceInfo info =
                        makeRunningServiceInfoLocked(r);
                info.restarting = r.nextRestartTime;
                res.add(info);
            }
            
            return res;
        }
    }

    public PendingIntent getRunningServiceControlPanel(ComponentName name) {
        synchronized (this) {
            ServiceRecord r = mServices.get(name);
            if (r != null) {
                for (ArrayList<ConnectionRecord> conn : r.connections.values()) {
                    for (int i=0; i<conn.size(); i++) {
                        if (conn.get(i).clientIntent != null) {
                            return conn.get(i).clientIntent;
                        }
                    }
                }
            }
        }
        return null;
    }
    
    private final ServiceRecord findServiceLocked(ComponentName name,
            IBinder token) {
        ServiceRecord r = mServices.get(name);
        return r == token ? r : null;
    }

    private final class ServiceLookupResult {
        final ServiceRecord record;
        final String permission;

        ServiceLookupResult(ServiceRecord _record, String _permission) {
            record = _record;
            permission = _permission;
        }
    };

    private ServiceLookupResult findServiceLocked(Intent service,
            String resolvedType) {
        ServiceRecord r = null;
        if (service.getComponent() != null) {
            r = mServices.get(service.getComponent());
        }
        if (r == null) {
            Intent.FilterComparison filter = new Intent.FilterComparison(service);
            r = mServicesByIntent.get(filter);
        }

        if (r == null) {
            try {
                ResolveInfo rInfo =
                    AppGlobals.getPackageManager().resolveService(
                            service, resolvedType, 0);
                ServiceInfo sInfo =
                    rInfo != null ? rInfo.serviceInfo : null;
                if (sInfo == null) {
                    return null;
                }

                ComponentName name = new ComponentName(
                        sInfo.applicationInfo.packageName, sInfo.name);
                r = mServices.get(name);
            } catch (RemoteException ex) {
                // pm is in same process, this will never happen.
            }
        }
        if (r != null) {
            int callingPid = Binder.getCallingPid();
            int callingUid = Binder.getCallingUid();
            if (checkComponentPermission(r.permission,
                    callingPid, callingUid, r.appInfo.uid, r.exported)
                    != PackageManager.PERMISSION_GRANTED) {
                if (!r.exported) {
                    Slog.w(TAG, "Permission Denial: Accessing service " + r.name
                            + " from pid=" + callingPid
                            + ", uid=" + callingUid
                            + " that is not exported from uid " + r.appInfo.uid);
                    return new ServiceLookupResult(null, "not exported from uid "
                            + r.appInfo.uid);
                }
                Slog.w(TAG, "Permission Denial: Accessing service " + r.name
                        + " from pid=" + callingPid
                        + ", uid=" + callingUid
                        + " requires " + r.permission);
                return new ServiceLookupResult(null, r.permission);
            }
            return new ServiceLookupResult(r, null);
        }
        return null;
    }

    private class ServiceRestarter implements Runnable {
        private ServiceRecord mService;

        void setService(ServiceRecord service) {
            mService = service;
        }

        public void run() {
            synchronized(ActivityManagerService.this) {
                performServiceRestartLocked(mService);
            }
        }
    }

    private ServiceLookupResult retrieveServiceLocked(Intent service,
            String resolvedType, int callingPid, int callingUid) {
        ServiceRecord r = null;
        if (service.getComponent() != null) {
            r = mServices.get(service.getComponent());
        }
        Intent.FilterComparison filter = new Intent.FilterComparison(service);
        r = mServicesByIntent.get(filter);
        if (r == null) {
            try {
                ResolveInfo rInfo =
                    AppGlobals.getPackageManager().resolveService(
                            service, resolvedType, STOCK_PM_FLAGS);
                ServiceInfo sInfo =
                    rInfo != null ? rInfo.serviceInfo : null;
                if (sInfo == null) {
                    Slog.w(TAG, "Unable to start service " + service +
                          ": not found");
                    return null;
                }

                ComponentName name = new ComponentName(
                        sInfo.applicationInfo.packageName, sInfo.name);
                r = mServices.get(name);
                if (r == null) {
                    filter = new Intent.FilterComparison(service.cloneFilter());
                    ServiceRestarter res = new ServiceRestarter();
                    BatteryStatsImpl.Uid.Pkg.Serv ss = null;
                    BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
                    synchronized (stats) {
                        ss = stats.getServiceStatsLocked(
                                sInfo.applicationInfo.uid, sInfo.packageName,
                                sInfo.name);
                    }
                    r = new ServiceRecord(this, ss, name, filter, sInfo, res);
                    res.setService(r);
                    mServices.put(name, r);
                    mServicesByIntent.put(filter, r);
                    
                    // Make sure this component isn't in the pending list.
                    int N = mPendingServices.size();
                    for (int i=0; i<N; i++) {
                        ServiceRecord pr = mPendingServices.get(i);
                        if (pr.name.equals(name)) {
                            mPendingServices.remove(i);
                            i--;
                            N--;
                        }
                    }
                }
            } catch (RemoteException ex) {
                // pm is in same process, this will never happen.
            }
        }
        if (r != null) {
            if (checkComponentPermission(r.permission,
                    callingPid, callingUid, r.appInfo.uid, r.exported)
                    != PackageManager.PERMISSION_GRANTED) {
                if (!r.exported) {
                    Slog.w(TAG, "Permission Denial: Accessing service " + r.name
                            + " from pid=" + callingPid
                            + ", uid=" + callingUid
                            + " that is not exported from uid " + r.appInfo.uid);
                    return new ServiceLookupResult(null, "not exported from uid "
                            + r.appInfo.uid);
                }
                Slog.w(TAG, "Permission Denial: Accessing service " + r.name
                        + " from pid=" + callingPid
                        + ", uid=" + callingUid
                        + " requires " + r.permission);
                return new ServiceLookupResult(null, r.permission);
            }
            return new ServiceLookupResult(r, null);
        }
        return null;
    }

    private final void bumpServiceExecutingLocked(ServiceRecord r, String why) {
        if (DEBUG_SERVICE) Log.v(TAG, ">>> EXECUTING "
                + why + " of " + r + " in app " + r.app);
        else if (DEBUG_SERVICE_EXECUTING) Log.v(TAG, ">>> EXECUTING "
                + why + " of " + r.shortName);
        long now = SystemClock.uptimeMillis();
        if (r.executeNesting == 0 && r.app != null) {
            if (r.app.executingServices.size() == 0) {
                Message msg = mHandler.obtainMessage(SERVICE_TIMEOUT_MSG);
                msg.obj = r.app;
                mHandler.sendMessageAtTime(msg, now+SERVICE_TIMEOUT);
            }
            r.app.executingServices.add(r);
        }
        r.executeNesting++;
        r.executingStart = now;
    }

    private final void sendServiceArgsLocked(ServiceRecord r,
            boolean oomAdjusted) {
        final int N = r.pendingStarts.size();
        if (N == 0) {
            return;
        }

        while (r.pendingStarts.size() > 0) {
            try {
                ServiceRecord.StartItem si = r.pendingStarts.remove(0);
                if (DEBUG_SERVICE) Slog.v(TAG, "Sending arguments to: "
                        + r + " " + r.intent + " args=" + si.intent);
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
                if (si.targetPermissionUid >= 0 && si.intent != null) {
                    grantUriPermissionUncheckedFromIntentLocked(si.targetPermissionUid,
                            r.packageName, si.intent, si.getUriPermissionsLocked());
                }
                bumpServiceExecutingLocked(r, "start");
                if (!oomAdjusted) {
                    oomAdjusted = true;
                    updateOomAdjLocked(r.app);
                }
                int flags = 0;
                if (si.deliveryCount > 0) {
                    flags |= Service.START_FLAG_RETRY;
                }
                if (si.doneExecutingCount > 0) {
                    flags |= Service.START_FLAG_REDELIVERY;
                }
                r.app.thread.scheduleServiceArgs(r, si.taskRemoved, si.id, flags, si.intent);
            } catch (RemoteException e) {
                // Remote process gone...  we'll let the normal cleanup take
                // care of this.
                if (DEBUG_SERVICE) Slog.v(TAG, "Crashed while scheduling start: " + r);
                break;
            } catch (Exception e) {
                Slog.w(TAG, "Unexpected exception", e);
                break;
            }
        }
    }

    private final boolean requestServiceBindingLocked(ServiceRecord r,
            IntentBindRecord i, boolean rebind) {
        if (r.app == null || r.app.thread == null) {
            // If service is not currently running, can't yet bind.
            return false;
        }
        if ((!i.requested || rebind) && i.apps.size() > 0) {
            try {
                bumpServiceExecutingLocked(r, "bind");
                r.app.thread.scheduleBindService(r, i.intent.getIntent(), rebind);
                if (!rebind) {
                    i.requested = true;
                }
                i.hasBound = true;
                i.doRebind = false;
            } catch (RemoteException e) {
                if (DEBUG_SERVICE) Slog.v(TAG, "Crashed while binding " + r);
                return false;
            }
        }
        return true;
    }

    private final void requestServiceBindingsLocked(ServiceRecord r) {
        Iterator<IntentBindRecord> bindings = r.bindings.values().iterator();
        while (bindings.hasNext()) {
            IntentBindRecord i = bindings.next();
            if (!requestServiceBindingLocked(r, i, false)) {
                break;
            }
        }
    }

    private final void realStartServiceLocked(ServiceRecord r,
            ProcessRecord app) throws RemoteException {
        if (app.thread == null) {
            throw new RemoteException();
        }

        r.app = app;
        r.restartTime = r.lastActivity = SystemClock.uptimeMillis();

        app.services.add(r);
        bumpServiceExecutingLocked(r, "create");
        updateLruProcessLocked(app, true, true);

        boolean created = false;
        try {
            mStringBuilder.setLength(0);
            r.intent.getIntent().toShortString(mStringBuilder, false, true);
            EventLog.writeEvent(EventLogTags.AM_CREATE_SERVICE,
                    System.identityHashCode(r), r.shortName,
                    mStringBuilder.toString(), r.app.pid);
            synchronized (r.stats.getBatteryStats()) {
                r.stats.startLaunchedLocked();
            }
            ensurePackageDexOpt(r.serviceInfo.packageName);
            app.thread.scheduleCreateService(r, r.serviceInfo,
                    compatibilityInfoForPackageLocked(r.serviceInfo.applicationInfo));
            r.postNotification();
            created = true;
        } finally {
            if (!created) {
                app.services.remove(r);
                scheduleServiceRestartLocked(r, false);
            }
        }

        requestServiceBindingsLocked(r);
        
        // If the service is in the started state, and there are no
        // pending arguments, then fake up one so its onStartCommand() will
        // be called.
        if (r.startRequested && r.callStart && r.pendingStarts.size() == 0) {
            r.pendingStarts.add(new ServiceRecord.StartItem(r, false, r.makeNextStartId(),
                    null, -1));
        }
        
        sendServiceArgsLocked(r, true);
    }

    private final boolean scheduleServiceRestartLocked(ServiceRecord r,
            boolean allowCancel) {
        boolean canceled = false;
        
        final long now = SystemClock.uptimeMillis();
        long minDuration = SERVICE_RESTART_DURATION;
        long resetTime = SERVICE_RESET_RUN_DURATION;
        
        if ((r.serviceInfo.applicationInfo.flags
                &ApplicationInfo.FLAG_PERSISTENT) != 0) {
            minDuration /= 4;
        }
        
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
                            + r.name);
                    canceled = true;
                }
            }
            r.deliveredStarts.clear();
        }
        
        r.totalRestartCount++;
        if (r.restartDelay == 0) {
            r.restartCount++;
            r.restartDelay = minDuration;
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
                if ((r.serviceInfo.applicationInfo.flags
                        &ApplicationInfo.FLAG_PERSISTENT) != 0) {
                    // Services in peristent processes will restart much more
                    // quickly, since they are pretty important.  (Think SystemUI).
                    r.restartDelay += minDuration/2;
                } else {
                    r.restartDelay *= SERVICE_RESTART_DURATION_FACTOR;
                    if (r.restartDelay < minDuration) {
                        r.restartDelay = minDuration;
                    }
                }
            }
        }
        
        r.nextRestartTime = now + r.restartDelay;
        
        // Make sure that we don't end up restarting a bunch of services
        // all at the same time.
        boolean repeat;
        do {
            repeat = false;
            for (int i=mRestartingServices.size()-1; i>=0; i--) {
                ServiceRecord r2 = mRestartingServices.get(i);
                if (r2 != r && r.nextRestartTime
                        >= (r2.nextRestartTime-SERVICE_MIN_RESTART_TIME_BETWEEN)
                        && r.nextRestartTime
                        < (r2.nextRestartTime+SERVICE_MIN_RESTART_TIME_BETWEEN)) {
                    r.nextRestartTime = r2.nextRestartTime + SERVICE_MIN_RESTART_TIME_BETWEEN;
                    r.restartDelay = r.nextRestartTime - now;
                    repeat = true;
                    break;
                }
            }
        } while (repeat);
        
        if (!mRestartingServices.contains(r)) {
            mRestartingServices.add(r);
        }
        
        r.cancelNotification();
        
        mHandler.removeCallbacks(r.restarter);
        mHandler.postAtTime(r.restarter, r.nextRestartTime);
        r.nextRestartTime = SystemClock.uptimeMillis() + r.restartDelay;
        Slog.w(TAG, "Scheduling restart of crashed service "
                + r.shortName + " in " + r.restartDelay + "ms");
        EventLog.writeEvent(EventLogTags.AM_SCHEDULE_SERVICE_RESTART,
                r.shortName, r.restartDelay);

        return canceled;
    }

    final void performServiceRestartLocked(ServiceRecord r) {
        if (!mRestartingServices.contains(r)) {
            return;
        }
        bringUpServiceLocked(r, r.intent.getIntent().getFlags(), true);
    }

    private final boolean unscheduleServiceRestartLocked(ServiceRecord r) {
        if (r.restartDelay == 0) {
            return false;
        }
        r.resetRestartCounter();
        mRestartingServices.remove(r);
        mHandler.removeCallbacks(r.restarter);
        return true;
    }

    private final boolean bringUpServiceLocked(ServiceRecord r,
            int intentFlags, boolean whileRestarting) {
        //Slog.i(TAG, "Bring up service:");
        //r.dump("  ");

        if (r.app != null && r.app.thread != null) {
            sendServiceArgsLocked(r, false);
            return true;
        }

        if (!whileRestarting && r.restartDelay > 0) {
            // If waiting for a restart, then do nothing.
            return true;
        }

        if (DEBUG_SERVICE) Slog.v(TAG, "Bringing up " + r + " " + r.intent);

        // We are now bringing the service up, so no longer in the
        // restarting state.
        mRestartingServices.remove(r);
        
        // Service is now being launched, its package can't be stopped.
        try {
            AppGlobals.getPackageManager().setPackageStoppedState(
                    r.packageName, false);
        } catch (RemoteException e) {
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Failed trying to unstop package "
                    + r.packageName + ": " + e);
        }

        final String appName = r.processName;
        ProcessRecord app = getProcessRecordLocked(appName, r.appInfo.uid);
        if (app != null && app.thread != null) {
            try {
                app.addPackage(r.appInfo.packageName);
                realStartServiceLocked(r, app);
                return true;
            } catch (RemoteException e) {
                Slog.w(TAG, "Exception when starting service " + r.shortName, e);
            }

            // If a dead object exception was thrown -- fall through to
            // restart the application.
        }

        // Not running -- get it started, and enqueue this service record
        // to be executed when the app comes up.
        if (startProcessLocked(appName, r.appInfo, true, intentFlags,
                "service", r.name, false) == null) {
            Slog.w(TAG, "Unable to launch app "
                    + r.appInfo.packageName + "/"
                    + r.appInfo.uid + " for service "
                    + r.intent.getIntent() + ": process is bad");
            bringDownServiceLocked(r, true);
            return false;
        }
        
        if (!mPendingServices.contains(r)) {
            mPendingServices.add(r);
        }
        
        return true;
    }

    private final void bringDownServiceLocked(ServiceRecord r, boolean force) {
        //Slog.i(TAG, "Bring down service:");
        //r.dump("  ");

        // Does it still need to run?
        if (!force && r.startRequested) {
            return;
        }
        if (r.connections.size() > 0) {
            if (!force) {
                // XXX should probably keep a count of the number of auto-create
                // connections directly in the service.
                Iterator<ArrayList<ConnectionRecord>> it = r.connections.values().iterator();
                while (it.hasNext()) {
                    ArrayList<ConnectionRecord> cr = it.next();
                    for (int i=0; i<cr.size(); i++) {
                        if ((cr.get(i).flags&Context.BIND_AUTO_CREATE) != 0) {
                            return;
                        }
                    }
                }
            }

            // Report to all of the connections that the service is no longer
            // available.
            Iterator<ArrayList<ConnectionRecord>> it = r.connections.values().iterator();
            while (it.hasNext()) {
                ArrayList<ConnectionRecord> c = it.next();
                for (int i=0; i<c.size(); i++) {
                    ConnectionRecord cr = c.get(i);
                    // There is still a connection to the service that is
                    // being brought down.  Mark it as dead.
                    cr.serviceDead = true;
                    try {
                        cr.conn.connected(r.name, null);
                    } catch (Exception e) {
                        Slog.w(TAG, "Failure disconnecting service " + r.name +
                              " to connection " + c.get(i).conn.asBinder() +
                              " (in " + c.get(i).binding.client.processName + ")", e);
                    }
                }
            }
        }

        // Tell the service that it has been unbound.
        if (r.bindings.size() > 0 && r.app != null && r.app.thread != null) {
            Iterator<IntentBindRecord> it = r.bindings.values().iterator();
            while (it.hasNext()) {
                IntentBindRecord ibr = it.next();
                if (DEBUG_SERVICE) Slog.v(TAG, "Bringing down binding " + ibr
                        + ": hasBound=" + ibr.hasBound);
                if (r.app != null && r.app.thread != null && ibr.hasBound) {
                    try {
                        bumpServiceExecutingLocked(r, "bring down unbind");
                        updateOomAdjLocked(r.app);
                        ibr.hasBound = false;
                        r.app.thread.scheduleUnbindService(r,
                                ibr.intent.getIntent());
                    } catch (Exception e) {
                        Slog.w(TAG, "Exception when unbinding service "
                                + r.shortName, e);
                        serviceDoneExecutingLocked(r, true);
                    }
                }
            }
        }

        if (DEBUG_SERVICE) Slog.v(TAG, "Bringing down " + r + " " + r.intent);
        EventLog.writeEvent(EventLogTags.AM_DESTROY_SERVICE,
                System.identityHashCode(r), r.shortName,
                (r.app != null) ? r.app.pid : -1);

        mServices.remove(r.name);
        mServicesByIntent.remove(r.intent);
        r.totalRestartCount = 0;
        unscheduleServiceRestartLocked(r);

        // Also make sure it is not on the pending list.
        int N = mPendingServices.size();
        for (int i=0; i<N; i++) {
            if (mPendingServices.get(i) == r) {
                mPendingServices.remove(i);
                if (DEBUG_SERVICE) Slog.v(TAG, "Removed pending: " + r);
                i--;
                N--;
            }
        }

        r.cancelNotification();
        r.isForeground = false;
        r.foregroundId = 0;
        r.foregroundNoti = null;
        
        // Clear start entries.
        r.clearDeliveredStartsLocked();
        r.pendingStarts.clear();
        
        if (r.app != null) {
            synchronized (r.stats.getBatteryStats()) {
                r.stats.stopLaunchedLocked();
            }
            r.app.services.remove(r);
            if (r.app.thread != null) {
                try {
                    bumpServiceExecutingLocked(r, "stop");
                    mStoppingServices.add(r);
                    updateOomAdjLocked(r.app);
                    r.app.thread.scheduleStopService(r);
                } catch (Exception e) {
                    Slog.w(TAG, "Exception when stopping service "
                            + r.shortName, e);
                    serviceDoneExecutingLocked(r, true);
                }
                updateServiceForegroundLocked(r.app, false);
            } else {
                if (DEBUG_SERVICE) Slog.v(
                    TAG, "Removed service that has no process: " + r);
            }
        } else {
            if (DEBUG_SERVICE) Slog.v(
                TAG, "Removed service that is not running: " + r);
        }

        if (r.bindings.size() > 0) {
            r.bindings.clear();
        }

        if (r.restarter instanceof ServiceRestarter) {
           ((ServiceRestarter)r.restarter).setService(null);
        }
    }

    ComponentName startServiceLocked(IApplicationThread caller,
            Intent service, String resolvedType,
            int callingPid, int callingUid) {
        synchronized(this) {
            if (DEBUG_SERVICE) Slog.v(TAG, "startService: " + service
                    + " type=" + resolvedType + " args=" + service.getExtras());

            if (caller != null) {
                final ProcessRecord callerApp = getRecordForAppLocked(caller);
                if (callerApp == null) {
                    throw new SecurityException(
                            "Unable to find app for caller " + caller
                            + " (pid=" + Binder.getCallingPid()
                            + ") when starting service " + service);
                }
            }

            ServiceLookupResult res =
                retrieveServiceLocked(service, resolvedType,
                        callingPid, callingUid);
            if (res == null) {
                return null;
            }
            if (res.record == null) {
                return new ComponentName("!", res.permission != null
                        ? res.permission : "private to package");
            }
            ServiceRecord r = res.record;
            int targetPermissionUid = checkGrantUriPermissionFromIntentLocked(
                    callingUid, r.packageName, service);
            if (unscheduleServiceRestartLocked(r)) {
                if (DEBUG_SERVICE) Slog.v(TAG, "START SERVICE WHILE RESTART PENDING: " + r);
            }
            r.startRequested = true;
            r.callStart = false;
            r.pendingStarts.add(new ServiceRecord.StartItem(r, false, r.makeNextStartId(),
                    service, targetPermissionUid));
            r.lastActivity = SystemClock.uptimeMillis();
            synchronized (r.stats.getBatteryStats()) {
                r.stats.startRunningLocked();
            }
            if (!bringUpServiceLocked(r, service.getFlags(), false)) {
                return new ComponentName("!", "Service process is bad");
            }
            return r.name;
        }
    }

    public ComponentName startService(IApplicationThread caller, Intent service,
            String resolvedType) {
        // Refuse possible leaked file descriptors
        if (service != null && service.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            final int callingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();
            final long origId = Binder.clearCallingIdentity();
            ComponentName res = startServiceLocked(caller, service,
                    resolvedType, callingPid, callingUid);
            Binder.restoreCallingIdentity(origId);
            return res;
        }
    }

    ComponentName startServiceInPackage(int uid,
            Intent service, String resolvedType) {
        synchronized(this) {
            final long origId = Binder.clearCallingIdentity();
            ComponentName res = startServiceLocked(null, service,
                    resolvedType, -1, uid);
            Binder.restoreCallingIdentity(origId);
            return res;
        }
    }

    private void stopServiceLocked(ServiceRecord service) {
        synchronized (service.stats.getBatteryStats()) {
            service.stats.stopRunningLocked();
        }
        service.startRequested = false;
        service.callStart = false;
        bringDownServiceLocked(service, false);
    }

    public int stopService(IApplicationThread caller, Intent service,
            String resolvedType) {
        // Refuse possible leaked file descriptors
        if (service != null && service.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            if (DEBUG_SERVICE) Slog.v(TAG, "stopService: " + service
                    + " type=" + resolvedType);

            final ProcessRecord callerApp = getRecordForAppLocked(caller);
            if (caller != null && callerApp == null) {
                throw new SecurityException(
                        "Unable to find app for caller " + caller
                        + " (pid=" + Binder.getCallingPid()
                        + ") when stopping service " + service);
            }

            // If this service is active, make sure it is stopped.
            ServiceLookupResult r = findServiceLocked(service, resolvedType);
            if (r != null) {
                if (r.record != null) {
                    final long origId = Binder.clearCallingIdentity();
                    try {
                        stopServiceLocked(r.record);
                    } finally {
                        Binder.restoreCallingIdentity(origId);
                    }
                    return 1;
                }
                return -1;
            }
        }

        return 0;
    }

    public IBinder peekService(Intent service, String resolvedType) {
        // Refuse possible leaked file descriptors
        if (service != null && service.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        IBinder ret = null;

        synchronized(this) {
            ServiceLookupResult r = findServiceLocked(service, resolvedType);
            
            if (r != null) {
                // r.record is null if findServiceLocked() failed the caller permission check
                if (r.record == null) {
                    throw new SecurityException(
                            "Permission Denial: Accessing service " + r.record.name
                            + " from pid=" + Binder.getCallingPid()
                            + ", uid=" + Binder.getCallingUid()
                            + " requires " + r.permission);
                }
                IntentBindRecord ib = r.record.bindings.get(r.record.intent);
                if (ib != null) {
                    ret = ib.binder;
                }
            }
        }

        return ret;
    }
    
    public boolean stopServiceToken(ComponentName className, IBinder token,
            int startId) {
        synchronized(this) {
            if (DEBUG_SERVICE) Slog.v(TAG, "stopServiceToken: " + className
                    + " " + token + " startId=" + startId);
            ServiceRecord r = findServiceLocked(className, token);
            if (r != null) {
                if (startId >= 0) {
                    // Asked to only stop if done with all work.  Note that
                    // to avoid leaks, we will take this as dropping all
                    // start items up to and including this one.
                    ServiceRecord.StartItem si = r.findDeliveredStart(startId, false);
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
                
                synchronized (r.stats.getBatteryStats()) {
                    r.stats.stopRunningLocked();
                    r.startRequested = false;
                    r.callStart = false;
                }
                final long origId = Binder.clearCallingIdentity();
                bringDownServiceLocked(r, false);
                Binder.restoreCallingIdentity(origId);
                return true;
            }
        }
        return false;
    }

    public void setServiceForeground(ComponentName className, IBinder token,
            int id, Notification notification, boolean removeNotification) {
        final long origId = Binder.clearCallingIdentity();
        try {
        synchronized(this) {
            ServiceRecord r = findServiceLocked(className, token);
            if (r != null) {
                if (id != 0) {
                    if (notification == null) {
                        throw new IllegalArgumentException("null notification");
                    }
                    if (r.foregroundId != id) {
                        r.cancelNotification();
                        r.foregroundId = id;
                    }
                    notification.flags |= Notification.FLAG_FOREGROUND_SERVICE;
                    r.foregroundNoti = notification;
                    r.isForeground = true;
                    r.postNotification();
                    if (r.app != null) {
                        updateServiceForegroundLocked(r.app, true);
                    }
                } else {
                    if (r.isForeground) {
                        r.isForeground = false;
                        if (r.app != null) {
                            updateLruProcessLocked(r.app, false, true);
                            updateServiceForegroundLocked(r.app, true);
                        }
                    }
                    if (removeNotification) {
                        r.cancelNotification();
                        r.foregroundId = 0;
                        r.foregroundNoti = null;
                    }
                }
            }
        }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public void updateServiceForegroundLocked(ProcessRecord proc, boolean oomAdj) {
        boolean anyForeground = false;
        for (ServiceRecord sr : proc.services) {
            if (sr.isForeground) {
                anyForeground = true;
                break;
            }
        }
        if (anyForeground != proc.foregroundServices) {
            proc.foregroundServices = anyForeground;
            if (oomAdj) {
                updateOomAdjLocked();
            }
        }
    }
    
    public int bindService(IApplicationThread caller, IBinder token,
            Intent service, String resolvedType,
            IServiceConnection connection, int flags) {
        // Refuse possible leaked file descriptors
        if (service != null && service.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            if (DEBUG_SERVICE) Slog.v(TAG, "bindService: " + service
                    + " type=" + resolvedType + " conn=" + connection.asBinder()
                    + " flags=0x" + Integer.toHexString(flags));
            final ProcessRecord callerApp = getRecordForAppLocked(caller);
            if (callerApp == null) {
                throw new SecurityException(
                        "Unable to find app for caller " + caller
                        + " (pid=" + Binder.getCallingPid()
                        + ") when binding service " + service);
            }

            ActivityRecord activity = null;
            if (token != null) {
                activity = mMainStack.isInStackLocked(token);
                if (activity == null) {
                    Slog.w(TAG, "Binding with unknown activity: " + token);
                    return 0;
                }
            }

            int clientLabel = 0;
            PendingIntent clientIntent = null;
            
            if (callerApp.info.uid == Process.SYSTEM_UID) {
                // Hacky kind of thing -- allow system stuff to tell us
                // what they are, so we can report this elsewhere for
                // others to know why certain services are running.
                try {
                    clientIntent = (PendingIntent)service.getParcelableExtra(
                            Intent.EXTRA_CLIENT_INTENT);
                } catch (RuntimeException e) {
                }
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
            
            ServiceLookupResult res =
                retrieveServiceLocked(service, resolvedType,
                        Binder.getCallingPid(), Binder.getCallingUid());
            if (res == null) {
                return 0;
            }
            if (res.record == null) {
                return -1;
            }
            ServiceRecord s = res.record;

            final long origId = Binder.clearCallingIdentity();

            if (unscheduleServiceRestartLocked(s)) {
                if (DEBUG_SERVICE) Slog.v(TAG, "BIND SERVICE WHILE RESTART PENDING: "
                        + s);
            }

            AppBindRecord b = s.retrieveAppBindingLocked(service, callerApp);
            ConnectionRecord c = new ConnectionRecord(b, activity,
                    connection, flags, clientLabel, clientIntent);

            IBinder binder = connection.asBinder();
            ArrayList<ConnectionRecord> clist = s.connections.get(binder);
            if (clist == null) {
                clist = new ArrayList<ConnectionRecord>();
                s.connections.put(binder, clist);
            }
            clist.add(c);
            b.connections.add(c);
            if (activity != null) {
                if (activity.connections == null) {
                    activity.connections = new HashSet<ConnectionRecord>();
                }
                activity.connections.add(c);
            }
            b.client.connections.add(c);
            if ((c.flags&Context.BIND_ABOVE_CLIENT) != 0) {
                b.client.hasAboveClient = true;
            }
            clist = mServiceConnections.get(binder);
            if (clist == null) {
                clist = new ArrayList<ConnectionRecord>();
                mServiceConnections.put(binder, clist);
            }
            clist.add(c);

            if ((flags&Context.BIND_AUTO_CREATE) != 0) {
                s.lastActivity = SystemClock.uptimeMillis();
                if (!bringUpServiceLocked(s, service.getFlags(), false)) {
                    return 0;
                }
            }

            if (s.app != null) {
                // This could have made the service more important.
                updateOomAdjLocked(s.app);
            }

            if (DEBUG_SERVICE) Slog.v(TAG, "Bind " + s + " with " + b
                    + ": received=" + b.intent.received
                    + " apps=" + b.intent.apps.size()
                    + " doRebind=" + b.intent.doRebind);

            if (s.app != null && b.intent.received) {
                // Service is already running, so we can immediately
                // publish the connection.
                try {
                    c.conn.connected(s.name, b.intent.binder);
                } catch (Exception e) {
                    Slog.w(TAG, "Failure sending service " + s.shortName
                            + " to connection " + c.conn.asBinder()
                            + " (in " + c.binding.client.processName + ")", e);
                }

                // If this is the first app connected back to this binding,
                // and the service had previously asked to be told when
                // rebound, then do so.
                if (b.intent.apps.size() == 1 && b.intent.doRebind) {
                    requestServiceBindingLocked(s, b.intent, true);
                }
            } else if (!b.intent.requested) {
                requestServiceBindingLocked(s, b.intent, false);
            }

            Binder.restoreCallingIdentity(origId);
        }

        return 1;
    }

    void removeConnectionLocked(
        ConnectionRecord c, ProcessRecord skipApp, ActivityRecord skipAct) {
        IBinder binder = c.conn.asBinder();
        AppBindRecord b = c.binding;
        ServiceRecord s = b.service;
        ArrayList<ConnectionRecord> clist = s.connections.get(binder);
        if (clist != null) {
            clist.remove(c);
            if (clist.size() == 0) {
                s.connections.remove(binder);
            }
        }
        b.connections.remove(c);
        if (c.activity != null && c.activity != skipAct) {
            if (c.activity.connections != null) {
                c.activity.connections.remove(c);
            }
        }
        if (b.client != skipApp) {
            b.client.connections.remove(c);
            if ((c.flags&Context.BIND_ABOVE_CLIENT) != 0) {
                b.client.updateHasAboveClientLocked();
            }
        }
        clist = mServiceConnections.get(binder);
        if (clist != null) {
            clist.remove(c);
            if (clist.size() == 0) {
                mServiceConnections.remove(binder);
            }
        }

        if (b.connections.size() == 0) {
            b.intent.apps.remove(b.client);
        }

        if (!c.serviceDead) {
            if (DEBUG_SERVICE) Slog.v(TAG, "Disconnecting binding " + b.intent
                    + ": shouldUnbind=" + b.intent.hasBound);
            if (s.app != null && s.app.thread != null && b.intent.apps.size() == 0
                    && b.intent.hasBound) {
                try {
                    bumpServiceExecutingLocked(s, "unbind");
                    updateOomAdjLocked(s.app);
                    b.intent.hasBound = false;
                    // Assume the client doesn't want to know about a rebind;
                    // we will deal with that later if it asks for one.
                    b.intent.doRebind = false;
                    s.app.thread.scheduleUnbindService(s, b.intent.intent.getIntent());
                } catch (Exception e) {
                    Slog.w(TAG, "Exception when unbinding service " + s.shortName, e);
                    serviceDoneExecutingLocked(s, true);
                }
            }
    
            if ((c.flags&Context.BIND_AUTO_CREATE) != 0) {
                bringDownServiceLocked(s, false);
            }
        }
    }

    public boolean unbindService(IServiceConnection connection) {
        synchronized (this) {
            IBinder binder = connection.asBinder();
            if (DEBUG_SERVICE) Slog.v(TAG, "unbindService: conn=" + binder);
            ArrayList<ConnectionRecord> clist = mServiceConnections.get(binder);
            if (clist == null) {
                Slog.w(TAG, "Unbind failed: could not find connection for "
                      + connection.asBinder());
                return false;
            }

            final long origId = Binder.clearCallingIdentity();

            while (clist.size() > 0) {
                ConnectionRecord r = clist.get(0);
                removeConnectionLocked(r, null, null);

                if (r.binding.service.app != null) {
                    // This could have made the service less important.
                    updateOomAdjLocked(r.binding.service.app);
                }
            }

            Binder.restoreCallingIdentity(origId);
        }

        return true;
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
            ServiceRecord r = (ServiceRecord)token;

            final long origId = Binder.clearCallingIdentity();

            if (DEBUG_SERVICE) Slog.v(TAG, "PUBLISHING " + r
                    + " " + intent + ": " + service);
            if (r != null) {
                Intent.FilterComparison filter
                        = new Intent.FilterComparison(intent);
                IntentBindRecord b = r.bindings.get(filter);
                if (b != null && !b.received) {
                    b.binder = service;
                    b.requested = true;
                    b.received = true;
                    if (r.connections.size() > 0) {
                        Iterator<ArrayList<ConnectionRecord>> it
                                = r.connections.values().iterator();
                        while (it.hasNext()) {
                            ArrayList<ConnectionRecord> clist = it.next();
                            for (int i=0; i<clist.size(); i++) {
                                ConnectionRecord c = clist.get(i);
                                if (!filter.equals(c.binding.intent.intent)) {
                                    if (DEBUG_SERVICE) Slog.v(
                                            TAG, "Not publishing to: " + c);
                                    if (DEBUG_SERVICE) Slog.v(
                                            TAG, "Bound intent: " + c.binding.intent.intent);
                                    if (DEBUG_SERVICE) Slog.v(
                                            TAG, "Published intent: " + intent);
                                    continue;
                                }
                                if (DEBUG_SERVICE) Slog.v(TAG, "Publishing to: " + c);
                                try {
                                    c.conn.connected(r.name, service);
                                } catch (Exception e) {
                                    Slog.w(TAG, "Failure sending service " + r.name +
                                          " to connection " + c.conn.asBinder() +
                                          " (in " + c.binding.client.processName + ")", e);
                                }
                            }
                        }
                    }
                }

                serviceDoneExecutingLocked(r, mStoppingServices.contains(r));

                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    public void unbindFinished(IBinder token, Intent intent, boolean doRebind) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            if (!(token instanceof ServiceRecord)) {
                throw new IllegalArgumentException("Invalid service token");
            }
            ServiceRecord r = (ServiceRecord)token;

            final long origId = Binder.clearCallingIdentity();

            if (r != null) {
                Intent.FilterComparison filter
                        = new Intent.FilterComparison(intent);
                IntentBindRecord b = r.bindings.get(filter);
                if (DEBUG_SERVICE) Slog.v(TAG, "unbindFinished in " + r
                        + " at " + b + ": apps="
                        + (b != null ? b.apps.size() : 0));
                if (b != null) {
                    if (b.apps.size() > 0) {
                        // Applications have already bound since the last
                        // unbind, so just rebind right here.
                        requestServiceBindingLocked(r, b, true);
                    } else {
                        // Note to tell the service the next time there is
                        // a new client.
                        b.doRebind = true;
                    }
                }

                serviceDoneExecutingLocked(r, mStoppingServices.contains(r));

                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    public void serviceDoneExecuting(IBinder token, int type, int startId, int res) {
        synchronized(this) {
            if (!(token instanceof ServiceRecord)) {
                throw new IllegalArgumentException("Invalid service token");
            }
            ServiceRecord r = (ServiceRecord)token;
            boolean inStopping = mStoppingServices.contains(token);
            if (r != null) {
                if (r != token) {
                    Slog.w(TAG, "Done executing service " + r.name
                          + " with incorrect token: given " + token
                          + ", expected " + r);
                    return;
                }

                if (type == 1) {
                    // This is a call from a service start...  take care of
                    // book-keeping.
                    r.callStart = true;
                    switch (res) {
                        case Service.START_STICKY_COMPATIBILITY:
                        case Service.START_STICKY: {
                            // We are done with the associated start arguments.
                            r.findDeliveredStart(startId, true);
                            // Don't stop if killed.
                            r.stopIfKilled = false;
                            break;
                        }
                        case Service.START_NOT_STICKY: {
                            // We are done with the associated start arguments.
                            r.findDeliveredStart(startId, true);
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
                            ServiceRecord.StartItem si = r.findDeliveredStart(startId, false);
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
                            r.findDeliveredStart(startId, true);
                            break;
                        }
                        default:
                            throw new IllegalArgumentException(
                                    "Unknown service start result: " + res);
                    }
                    if (res == Service.START_STICKY_COMPATIBILITY) {
                        r.callStart = false;
                    }
                }
                
                final long origId = Binder.clearCallingIdentity();
                serviceDoneExecutingLocked(r, inStopping);
                Binder.restoreCallingIdentity(origId);
            } else {
                Slog.w(TAG, "Done executing unknown service from pid "
                        + Binder.getCallingPid());
            }
        }
    }

    public void serviceDoneExecutingLocked(ServiceRecord r, boolean inStopping) {
        if (DEBUG_SERVICE) Slog.v(TAG, "<<< DONE EXECUTING " + r
                + ": nesting=" + r.executeNesting
                + ", inStopping=" + inStopping + ", app=" + r.app);
        else if (DEBUG_SERVICE_EXECUTING) Slog.v(TAG, "<<< DONE EXECUTING " + r.shortName);
        r.executeNesting--;
        if (r.executeNesting <= 0 && r.app != null) {
            if (DEBUG_SERVICE) Slog.v(TAG,
                    "Nesting at 0 of " + r.shortName);
            r.app.executingServices.remove(r);
            if (r.app.executingServices.size() == 0) {
                if (DEBUG_SERVICE || DEBUG_SERVICE_EXECUTING) Slog.v(TAG,
                        "No more executingServices of " + r.shortName);
                mHandler.removeMessages(SERVICE_TIMEOUT_MSG, r.app);
            }
            if (inStopping) {
                if (DEBUG_SERVICE) Slog.v(TAG,
                        "doneExecuting remove stopping " + r);
                mStoppingServices.remove(r);
                r.bindings.clear();
            }
            updateOomAdjLocked(r.app);
        }
    }

    void serviceTimeout(ProcessRecord proc) {
        String anrMessage = null;
        
        synchronized(this) {
            if (proc.executingServices.size() == 0 || proc.thread == null) {
                return;
            }
            long maxTime = SystemClock.uptimeMillis() - SERVICE_TIMEOUT;
            Iterator<ServiceRecord> it = proc.executingServices.iterator();
            ServiceRecord timeout = null;
            long nextTime = 0;
            while (it.hasNext()) {
                ServiceRecord sr = it.next();
                if (sr.executingStart < maxTime) {
                    timeout = sr;
                    break;
                }
                if (sr.executingStart > nextTime) {
                    nextTime = sr.executingStart;
                }
            }
            if (timeout != null && mLruProcesses.contains(proc)) {
                Slog.w(TAG, "Timeout executing service: " + timeout);
                anrMessage = "Executing service " + timeout.shortName;
            } else {
                Message msg = mHandler.obtainMessage(SERVICE_TIMEOUT_MSG);
                msg.obj = proc;
                mHandler.sendMessageAtTime(msg, nextTime+SERVICE_TIMEOUT);
            }
        }
        
        if (anrMessage != null) {
            appNotResponding(proc, null, null, anrMessage);
        }
    }
    
    // =========================================================
    // BACKUP AND RESTORE
    // =========================================================
    
    // Cause the target app to be launched if necessary and its backup agent
    // instantiated.  The backup agent will invoke backupAgentCreated() on the
    // activity manager to announce its creation.
    public boolean bindBackupAgent(ApplicationInfo app, int backupMode) {
        if (DEBUG_BACKUP) Slog.v(TAG, "startBackupAgent: app=" + app + " mode=" + backupMode);
        enforceCallingPermission("android.permission.BACKUP", "startBackupAgent");

        synchronized(this) {
            // !!! TODO: currently no check here that we're already bound
            BatteryStatsImpl.Uid.Pkg.Serv ss = null;
            BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
            synchronized (stats) {
                ss = stats.getServiceStatsLocked(app.uid, app.packageName, app.name);
            }

            // Backup agent is now in use, its package can't be stopped.
            try {
                AppGlobals.getPackageManager().setPackageStoppedState(
                        app.packageName, false);
            } catch (RemoteException e) {
            } catch (IllegalArgumentException e) {
                Slog.w(TAG, "Failed trying to unstop package "
                        + app.packageName + ": " + e);
            }

            BackupRecord r = new BackupRecord(ss, app, backupMode);
            ComponentName hostingName = (backupMode == IApplicationThread.BACKUP_MODE_INCREMENTAL)
                    ? new ComponentName(app.packageName, app.backupAgentName)
                    : new ComponentName("android", "FullBackupAgent");
            // startProcessLocked() returns existing proc's record if it's already running
            ProcessRecord proc = startProcessLocked(app.processName, app,
                    false, 0, "backup", hostingName, false);
            if (proc == null) {
                Slog.e(TAG, "Unable to start backup agent process " + r);
                return false;
            }

            r.app = proc;
            mBackupTarget = r;
            mBackupAppName = app.packageName;

            // Try not to kill the process during backup
            updateOomAdjLocked(proc);

            // If the process is already attached, schedule the creation of the backup agent now.
            // If it is not yet live, this will be done when it attaches to the framework.
            if (proc.thread != null) {
                if (DEBUG_BACKUP) Slog.v(TAG, "Agent proc already running: " + proc);
                try {
                    proc.thread.scheduleCreateBackupAgent(app,
                            compatibilityInfoForPackageLocked(app), backupMode);
                } catch (RemoteException e) {
                    // Will time out on the backup manager side
                }
            } else {
                if (DEBUG_BACKUP) Slog.v(TAG, "Agent proc not running, waiting for attach");
            }
            // Invariants: at this point, the target app process exists and the application
            // is either already running or in the process of coming up.  mBackupTarget and
            // mBackupAppName describe the app, so that when it binds back to the AM we
            // know that it's scheduled for a backup-agent operation.
        }
        
        return true;
    }

    // A backup agent has just come up                    
    public void backupAgentCreated(String agentPackageName, IBinder agent) {
        if (DEBUG_BACKUP) Slog.v(TAG, "backupAgentCreated: " + agentPackageName
                + " = " + agent);

        synchronized(this) {
            if (!agentPackageName.equals(mBackupAppName)) {
                Slog.e(TAG, "Backup agent created for " + agentPackageName + " but not requested!");
                return;
            }
        }

        long oldIdent = Binder.clearCallingIdentity();
        try {
            IBackupManager bm = IBackupManager.Stub.asInterface(
                    ServiceManager.getService(Context.BACKUP_SERVICE));
            bm.agentConnected(agentPackageName, agent);
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
        if (DEBUG_BACKUP) Slog.v(TAG, "unbindBackupAgent: " + appInfo);
        if (appInfo == null) {
            Slog.w(TAG, "unbind backup agent for null app");
            return;
        }

        synchronized(this) {
            if (mBackupAppName == null) {
                Slog.w(TAG, "Unbinding backup agent with no active backup");
                return;
            }

            if (!mBackupAppName.equals(appInfo.packageName)) {
                Slog.e(TAG, "Unbind of " + appInfo + " but is not the current backup target");
                return;
            }

            ProcessRecord proc = mBackupTarget.app;
            mBackupTarget = null;
            mBackupAppName = null;

            // Not backing this app up any more; reset its OOM adjustment
            updateOomAdjLocked(proc);

            // If the app crashed during backup, 'thread' will be null here
            if (proc.thread != null) {
                try {
                    proc.thread.scheduleDestroyBackupAgent(appInfo,
                            compatibilityInfoForPackageLocked(appInfo));
                } catch (Exception e) {
                    Slog.e(TAG, "Exception when unbinding backup agent:");
                    e.printStackTrace();
                }
            }
        }
    }
    // =========================================================
    // BROADCASTS
    // =========================================================

    private final List getStickiesLocked(String action, IntentFilter filter,
            List cur) {
        final ContentResolver resolver = mContext.getContentResolver();
        final ArrayList<Intent> list = mStickyBroadcasts.get(action);
        if (list == null) {
            return cur;
        }
        int N = list.size();
        for (int i=0; i<N; i++) {
            Intent intent = list.get(i);
            if (filter.match(resolver, intent, true, TAG) >= 0) {
                if (cur == null) {
                    cur = new ArrayList<Intent>();
                }
                cur.add(intent);
            }
        }
        return cur;
    }

    private final void scheduleBroadcastsLocked() {
        if (DEBUG_BROADCAST) Slog.v(TAG, "Schedule broadcasts: current="
                + mBroadcastsScheduled);

        if (mBroadcastsScheduled) {
            return;
        }
        mHandler.sendEmptyMessage(BROADCAST_INTENT_MSG);
        mBroadcastsScheduled = true;
    }

    public Intent registerReceiver(IApplicationThread caller, String callerPackage,
            IIntentReceiver receiver, IntentFilter filter, String permission) {
        synchronized(this) {
            ProcessRecord callerApp = null;
            if (caller != null) {
                callerApp = getRecordForAppLocked(caller);
                if (callerApp == null) {
                    throw new SecurityException(
                            "Unable to find app for caller " + caller
                            + " (pid=" + Binder.getCallingPid()
                            + ") when registering receiver " + receiver);
                }
                if (callerApp.info.uid != Process.SYSTEM_UID &&
                        !callerApp.pkgList.contains(callerPackage)) {
                    throw new SecurityException("Given caller package " + callerPackage
                            + " is not running in process " + callerApp);
                }
            } else {
                callerPackage = null;
            }

            List allSticky = null;

            // Look for any matching sticky broadcasts...
            Iterator actions = filter.actionsIterator();
            if (actions != null) {
                while (actions.hasNext()) {
                    String action = (String)actions.next();
                    allSticky = getStickiesLocked(action, filter, allSticky);
                }
            } else {
                allSticky = getStickiesLocked(null, filter, allSticky);
            }

            // The first sticky in the list is returned directly back to
            // the client.
            Intent sticky = allSticky != null ? (Intent)allSticky.get(0) : null;

            if (DEBUG_BROADCAST) Slog.v(TAG, "Register receiver " + filter
                    + ": " + sticky);

            if (receiver == null) {
                return sticky;
            }

            ReceiverList rl
                = (ReceiverList)mRegisteredReceivers.get(receiver.asBinder());
            if (rl == null) {
                rl = new ReceiverList(this, callerApp,
                        Binder.getCallingPid(),
                        Binder.getCallingUid(), receiver);
                if (rl.app != null) {
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
            }
            BroadcastFilter bf = new BroadcastFilter(filter, rl, callerPackage, permission);
            rl.add(bf);
            if (!bf.debugCheck()) {
                Slog.w(TAG, "==> For Dynamic broadast");
            }
            mReceiverResolver.addFilter(bf);

            // Enqueue broadcasts for all existing stickies that match
            // this filter.
            if (allSticky != null) {
                ArrayList receivers = new ArrayList();
                receivers.add(bf);

                int N = allSticky.size();
                for (int i=0; i<N; i++) {
                    Intent intent = (Intent)allSticky.get(i);
                    BroadcastRecord r = new BroadcastRecord(intent, null,
                            null, -1, -1, null, receivers, null, 0, null, null,
                            false, true, true);
                    if (mParallelBroadcasts.size() == 0) {
                        scheduleBroadcastsLocked();
                    }
                    mParallelBroadcasts.add(r);
                }
            }

            return sticky;
        }
    }

    public void unregisterReceiver(IIntentReceiver receiver) {
        if (DEBUG_BROADCAST) Slog.v(TAG, "Unregister receiver: " + receiver);

        boolean doNext = false;

        synchronized(this) {
            ReceiverList rl
                = (ReceiverList)mRegisteredReceivers.get(receiver.asBinder());
            if (rl != null) {
                if (rl.curBroadcast != null) {
                    BroadcastRecord r = rl.curBroadcast;
                    doNext = finishReceiverLocked(
                        receiver.asBinder(), r.resultCode, r.resultData,
                        r.resultExtras, r.resultAbort, true);
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

        if (!doNext) {
            return;
        }
        
        final long origId = Binder.clearCallingIdentity();
        processNextBroadcast(false);
        trimApplications();
        Binder.restoreCallingIdentity(origId);
    }

    void removeReceiverLocked(ReceiverList rl) {
        mRegisteredReceivers.remove(rl.receiver.asBinder());
        int N = rl.size();
        for (int i=0; i<N; i++) {
            mReceiverResolver.removeFilter(rl.get(i));
        }
    }
    
    private final void sendPackageBroadcastLocked(int cmd, String[] packages) {
        for (int i = mLruProcesses.size() - 1 ; i >= 0 ; i--) {
            ProcessRecord r = mLruProcesses.get(i);
            if (r.thread != null) {
                try {
                    r.thread.dispatchPackageBroadcast(cmd, packages);
                } catch (RemoteException ex) {
                }
            }
        }
    }
    
    private final int broadcastIntentLocked(ProcessRecord callerApp,
            String callerPackage, Intent intent, String resolvedType,
            IIntentReceiver resultTo, int resultCode, String resultData,
            Bundle map, String requiredPermission,
            boolean ordered, boolean sticky, int callingPid, int callingUid) {
        intent = new Intent(intent);

        // By default broadcasts do not go to stopped apps.
        intent.addFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);

        if (DEBUG_BROADCAST_LIGHT) Slog.v(
            TAG, (sticky ? "Broadcast sticky: ": "Broadcast: ") + intent
            + " ordered=" + ordered);
        if ((resultTo != null) && !ordered) {
            Slog.w(TAG, "Broadcast " + intent + " not ordered but result callback requested!");
        }
        
        // Handle special intents: if this broadcast is from the package
        // manager about a package being removed, we need to remove all of
        // its activities from the history stack.
        final boolean uidRemoved = Intent.ACTION_UID_REMOVED.equals(
                intent.getAction());
        if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                || Intent.ACTION_PACKAGE_CHANGED.equals(intent.getAction())
                || Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(intent.getAction())
                || uidRemoved) {
            if (checkComponentPermission(
                    android.Manifest.permission.BROADCAST_PACKAGE_REMOVED,
                    callingPid, callingUid, -1, true)
                    == PackageManager.PERMISSION_GRANTED) {
                if (uidRemoved) {
                    final Bundle intentExtras = intent.getExtras();
                    final int uid = intentExtras != null
                            ? intentExtras.getInt(Intent.EXTRA_UID) : -1;
                    if (uid >= 0) {
                        BatteryStatsImpl bs = mBatteryStatsService.getActiveStatistics();
                        synchronized (bs) {
                            bs.removeUidStatsLocked(uid);
                        }
                    }
                } else {
                    // If resources are unvailble just force stop all
                    // those packages and flush the attribute cache as well.
                    if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(intent.getAction())) {
                        String list[] = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                        if (list != null && (list.length > 0)) {
                            for (String pkg : list) {
                                forceStopPackageLocked(pkg, -1, false, true, true);
                            }
                            sendPackageBroadcastLocked(
                                    IApplicationThread.EXTERNAL_STORAGE_UNAVAILABLE, list);
                        }
                    } else {
                        Uri data = intent.getData();
                        String ssp;
                        if (data != null && (ssp=data.getSchemeSpecificPart()) != null) {
                            if (!intent.getBooleanExtra(Intent.EXTRA_DONT_KILL_APP, false)) {
                                forceStopPackageLocked(ssp,
                                        intent.getIntExtra(Intent.EXTRA_UID, -1), false, true, true);
                            }
                            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
                                sendPackageBroadcastLocked(IApplicationThread.PACKAGE_REMOVED,
                                        new String[] {ssp});
                            }
                        }
                    }
                }
            } else {
                String msg = "Permission Denial: " + intent.getAction()
                        + " broadcast from " + callerPackage + " (pid=" + callingPid
                        + ", uid=" + callingUid + ")"
                        + " requires "
                        + android.Manifest.permission.BROADCAST_PACKAGE_REMOVED;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }

        // Special case for adding a package: by default turn on compatibility
        // mode.
        } else if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
            Uri data = intent.getData();
            String ssp;
            if (data != null && (ssp=data.getSchemeSpecificPart()) != null) {
                mCompatModePackages.handlePackageAddedLocked(ssp,
                        intent.getBooleanExtra(Intent.EXTRA_REPLACING, false));
            }
        }

        /*
         * If this is the time zone changed action, queue up a message that will reset the timezone
         * of all currently running processes. This message will get queued up before the broadcast
         * happens.
         */
        if (intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())) {
            mHandler.sendEmptyMessage(UPDATE_TIME_ZONE);
        }

        if (intent.ACTION_CLEAR_DNS_CACHE.equals(intent.getAction())) {
            mHandler.sendEmptyMessage(CLEAR_DNS_CACHE);
        }

        if (Proxy.PROXY_CHANGE_ACTION.equals(intent.getAction())) {
            ProxyProperties proxy = intent.getParcelableExtra("proxy");
            mHandler.sendMessage(mHandler.obtainMessage(UPDATE_HTTP_PROXY, proxy));
        }

        /*
         * Prevent non-system code (defined here to be non-persistent
         * processes) from sending protected broadcasts.
         */
        if (callingUid == Process.SYSTEM_UID || callingUid == Process.PHONE_UID
                || callingUid == Process.SHELL_UID || callingUid == 0) {
            // Always okay.
        } else if (callerApp == null || !callerApp.persistent) {
            try {
                if (AppGlobals.getPackageManager().isProtectedBroadcast(
                        intent.getAction())) {
                    String msg = "Permission Denial: not allowed to send broadcast "
                            + intent.getAction() + " from pid="
                            + callingPid + ", uid=" + callingUid;
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Remote exception", e);
                return BROADCAST_SUCCESS;
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
            if (requiredPermission != null) {
                Slog.w(TAG, "Can't broadcast sticky intent " + intent
                        + " and enforce permission " + requiredPermission);
                return BROADCAST_STICKY_CANT_HAVE_PERMISSION;
            }
            if (intent.getComponent() != null) {
                throw new SecurityException(
                        "Sticky broadcasts can't target a specific component");
            }
            ArrayList<Intent> list = mStickyBroadcasts.get(intent.getAction());
            if (list == null) {
                list = new ArrayList<Intent>();
                mStickyBroadcasts.put(intent.getAction(), list);
            }
            int N = list.size();
            int i;
            for (i=0; i<N; i++) {
                if (intent.filterEquals(list.get(i))) {
                    // This sticky already exists, replace it.
                    list.set(i, new Intent(intent));
                    break;
                }
            }
            if (i >= N) {
                list.add(new Intent(intent));
            }
        }

        // Figure out who all will receive this broadcast.
        List receivers = null;
        List<BroadcastFilter> registeredReceivers = null;
        try {
            if (intent.getComponent() != null) {
                // Broadcast is going to one specific receiver class...
                ActivityInfo ai = AppGlobals.getPackageManager().
                    getReceiverInfo(intent.getComponent(), STOCK_PM_FLAGS);
                if (ai != null) {
                    receivers = new ArrayList();
                    ResolveInfo ri = new ResolveInfo();
                    ri.activityInfo = ai;
                    receivers.add(ri);
                }
            } else {
                // Need to resolve the intent to interested receivers...
                if ((intent.getFlags()&Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                         == 0) {
                    receivers =
                        AppGlobals.getPackageManager().queryIntentReceivers(
                                intent, resolvedType, STOCK_PM_FLAGS);
                }
                registeredReceivers = mReceiverResolver.queryIntent(intent, resolvedType, false);
            }
        } catch (RemoteException ex) {
            // pm is in same process, this will never happen.
        }

        final boolean replacePending =
                (intent.getFlags()&Intent.FLAG_RECEIVER_REPLACE_PENDING) != 0;
        
        if (DEBUG_BROADCAST) Slog.v(TAG, "Enqueing broadcast: " + intent.getAction()
                + " replacePending=" + replacePending);
        
        int NR = registeredReceivers != null ? registeredReceivers.size() : 0;
        if (!ordered && NR > 0) {
            // If we are not serializing this broadcast, then send the
            // registered receivers separately so they don't wait for the
            // components to be launched.
            BroadcastRecord r = new BroadcastRecord(intent, callerApp,
                    callerPackage, callingPid, callingUid, requiredPermission,
                    registeredReceivers, resultTo, resultCode, resultData, map,
                    ordered, sticky, false);
            if (DEBUG_BROADCAST) Slog.v(
                    TAG, "Enqueueing parallel broadcast " + r
                    + ": prev had " + mParallelBroadcasts.size());
            boolean replaced = false;
            if (replacePending) {
                for (int i=mParallelBroadcasts.size()-1; i>=0; i--) {
                    if (intent.filterEquals(mParallelBroadcasts.get(i).intent)) {
                        if (DEBUG_BROADCAST) Slog.v(TAG,
                                "***** DROPPING PARALLEL: " + intent);
                        mParallelBroadcasts.set(i, r);
                        replaced = true;
                        break;
                    }
                }
            }
            if (!replaced) {
                mParallelBroadcasts.add(r);
                scheduleBroadcastsLocked();
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

        if ((receivers != null && receivers.size() > 0)
                || resultTo != null) {
            BroadcastRecord r = new BroadcastRecord(intent, callerApp,
                    callerPackage, callingPid, callingUid, requiredPermission,
                    receivers, resultTo, resultCode, resultData, map, ordered,
                    sticky, false);
            if (DEBUG_BROADCAST) Slog.v(
                    TAG, "Enqueueing ordered broadcast " + r
                    + ": prev had " + mOrderedBroadcasts.size());
            if (DEBUG_BROADCAST) {
                int seq = r.intent.getIntExtra("seq", -1);
                Slog.i(TAG, "Enqueueing broadcast " + r.intent.getAction() + " seq=" + seq);
            }
            boolean replaced = false;
            if (replacePending) {
                for (int i=mOrderedBroadcasts.size()-1; i>0; i--) {
                    if (intent.filterEquals(mOrderedBroadcasts.get(i).intent)) {
                        if (DEBUG_BROADCAST) Slog.v(TAG,
                                "***** DROPPING ORDERED: " + intent);
                        mOrderedBroadcasts.set(i, r);
                        replaced = true;
                        break;
                    }
                }
            }
            if (!replaced) {
                mOrderedBroadcasts.add(r);
                scheduleBroadcastsLocked();
            }
        }

        return BROADCAST_SUCCESS;
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
                intent = new Intent(intent);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
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

        return intent;
    }

    public final int broadcastIntent(IApplicationThread caller,
            Intent intent, String resolvedType, IIntentReceiver resultTo,
            int resultCode, String resultData, Bundle map,
            String requiredPermission, boolean serialized, boolean sticky) {
        synchronized(this) {
            intent = verifyBroadcastLocked(intent);
            
            final ProcessRecord callerApp = getRecordForAppLocked(caller);
            final int callingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();
            final long origId = Binder.clearCallingIdentity();
            int res = broadcastIntentLocked(callerApp,
                    callerApp != null ? callerApp.info.packageName : null,
                    intent, resolvedType, resultTo,
                    resultCode, resultData, map, requiredPermission, serialized,
                    sticky, callingPid, callingUid);
            Binder.restoreCallingIdentity(origId);
            return res;
        }
    }

    int broadcastIntentInPackage(String packageName, int uid,
            Intent intent, String resolvedType, IIntentReceiver resultTo,
            int resultCode, String resultData, Bundle map,
            String requiredPermission, boolean serialized, boolean sticky) {
        synchronized(this) {
            intent = verifyBroadcastLocked(intent);

            final long origId = Binder.clearCallingIdentity();
            int res = broadcastIntentLocked(null, packageName, intent, resolvedType,
                    resultTo, resultCode, resultData, map, requiredPermission,
                    serialized, sticky, -1, uid);
            Binder.restoreCallingIdentity(origId);
            return res;
        }
    }

    public final void unbroadcastIntent(IApplicationThread caller,
            Intent intent) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

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
            ArrayList<Intent> list = mStickyBroadcasts.get(intent.getAction());
            if (list != null) {
                int N = list.size();
                int i;
                for (i=0; i<N; i++) {
                    if (intent.filterEquals(list.get(i))) {
                        list.remove(i);
                        break;
                    }
                }
            }
        }
    }

    private final boolean finishReceiverLocked(IBinder receiver, int resultCode,
            String resultData, Bundle resultExtras, boolean resultAbort,
            boolean explicit) {
        if (mOrderedBroadcasts.size() == 0) {
            if (explicit) {
                Slog.w(TAG, "finishReceiver called but no pending broadcasts");
            }
            return false;
        }
        BroadcastRecord r = mOrderedBroadcasts.get(0);
        if (r.receiver == null) {
            if (explicit) {
                Slog.w(TAG, "finishReceiver called but none active");
            }
            return false;
        }
        if (r.receiver != receiver) {
            Slog.w(TAG, "finishReceiver called but active receiver is different");
            return false;
        }
        int state = r.state;
        r.state = BroadcastRecord.IDLE;
        if (state == BroadcastRecord.IDLE) {
            if (explicit) {
                Slog.w(TAG, "finishReceiver called but state is IDLE");
            }
        }
        r.receiver = null;
        r.intent.setComponent(null);
        if (r.curApp != null) {
            r.curApp.curReceiver = null;
        }
        if (r.curFilter != null) {
            r.curFilter.receiverList.curBroadcast = null;
        }
        r.curFilter = null;
        r.curApp = null;
        r.curComponent = null;
        r.curReceiver = null;
        mPendingBroadcast = null;

        r.resultCode = resultCode;
        r.resultData = resultData;
        r.resultExtras = resultExtras;
        r.resultAbort = resultAbort;

        // We will process the next receiver right now if this is finishing
        // an app receiver (which is always asynchronous) or after we have
        // come back from calling a receiver.
        return state == BroadcastRecord.APP_RECEIVE
                || state == BroadcastRecord.CALL_DONE_RECEIVE;
    }

    public void finishReceiver(IBinder who, int resultCode, String resultData,
            Bundle resultExtras, boolean resultAbort) {
        if (DEBUG_BROADCAST) Slog.v(TAG, "Finish receiver: " + who);

        // Refuse possible leaked file descriptors
        if (resultExtras != null && resultExtras.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Bundle");
        }

        boolean doNext;

        final long origId = Binder.clearCallingIdentity();

        synchronized(this) {
            doNext = finishReceiverLocked(
                who, resultCode, resultData, resultExtras, resultAbort, true);
        }

        if (doNext) {
            processNextBroadcast(false);
        }
        trimApplications();

        Binder.restoreCallingIdentity(origId);
    }

    private final void logBroadcastReceiverDiscardLocked(BroadcastRecord r) {
        if (r.nextReceiver > 0) {
            Object curReceiver = r.receivers.get(r.nextReceiver-1);
            if (curReceiver instanceof BroadcastFilter) {
                BroadcastFilter bf = (BroadcastFilter) curReceiver;
                EventLog.writeEvent(EventLogTags.AM_BROADCAST_DISCARD_FILTER,
                        System.identityHashCode(r),
                        r.intent.getAction(),
                        r.nextReceiver - 1,
                        System.identityHashCode(bf));
            } else {
                EventLog.writeEvent(EventLogTags.AM_BROADCAST_DISCARD_APP,
                        System.identityHashCode(r),
                        r.intent.getAction(),
                        r.nextReceiver - 1,
                        ((ResolveInfo)curReceiver).toString());
            }
        } else {
            Slog.w(TAG, "Discarding broadcast before first receiver is invoked: "
                    + r);
            EventLog.writeEvent(EventLogTags.AM_BROADCAST_DISCARD_APP,
                    System.identityHashCode(r),
                    r.intent.getAction(),
                    r.nextReceiver,
                    "NONE");
        }
    }

    private final void setBroadcastTimeoutLocked(long timeoutTime) {
        if (! mPendingBroadcastTimeoutMessage) {
            Message msg = mHandler.obtainMessage(BROADCAST_TIMEOUT_MSG);
            mHandler.sendMessageAtTime(msg, timeoutTime);
            mPendingBroadcastTimeoutMessage = true;
        }
    }

    private final void cancelBroadcastTimeoutLocked() {
        if (mPendingBroadcastTimeoutMessage) {
            mHandler.removeMessages(BROADCAST_TIMEOUT_MSG);
            mPendingBroadcastTimeoutMessage = false;
        }
    }

    private final void broadcastTimeoutLocked(boolean fromMsg) {
        if (fromMsg) {
            mPendingBroadcastTimeoutMessage = false;
        }

        if (mOrderedBroadcasts.size() == 0) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        BroadcastRecord r = mOrderedBroadcasts.get(0);
        if (fromMsg) {
            if (mDidDexOpt) {
                // Delay timeouts until dexopt finishes.
                mDidDexOpt = false;
                long timeoutTime = SystemClock.uptimeMillis() + BROADCAST_TIMEOUT;
                setBroadcastTimeoutLocked(timeoutTime);
                return;
            }
            if (! mProcessesReady) {
                // Only process broadcast timeouts if the system is ready. That way
                // PRE_BOOT_COMPLETED broadcasts can't timeout as they are intended
                // to do heavy lifting for system up.
                return;
            }

            long timeoutTime = r.receiverTime + BROADCAST_TIMEOUT;
            if (timeoutTime > now) {
                // We can observe premature timeouts because we do not cancel and reset the
                // broadcast timeout message after each receiver finishes.  Instead, we set up
                // an initial timeout then kick it down the road a little further as needed
                // when it expires.
                if (DEBUG_BROADCAST) Slog.v(TAG,
                        "Premature timeout @ " + now + ": resetting BROADCAST_TIMEOUT_MSG for "
                        + timeoutTime);
                setBroadcastTimeoutLocked(timeoutTime);
                return;
            }
        }

        Slog.w(TAG, "Timeout of broadcast " + r + " - receiver=" + r.receiver
                + ", started " + (now - r.receiverTime) + "ms ago");
        r.receiverTime = now;
        r.anrCount++;

        // Current receiver has passed its expiration date.
        if (r.nextReceiver <= 0) {
            Slog.w(TAG, "Timeout on receiver with nextReceiver <= 0");
            return;
        }

        ProcessRecord app = null;
        String anrMessage = null;

        Object curReceiver = r.receivers.get(r.nextReceiver-1);
        Slog.w(TAG, "Receiver during timeout: " + curReceiver);
        logBroadcastReceiverDiscardLocked(r);
        if (curReceiver instanceof BroadcastFilter) {
            BroadcastFilter bf = (BroadcastFilter)curReceiver;
            if (bf.receiverList.pid != 0
                    && bf.receiverList.pid != MY_PID) {
                synchronized (this.mPidsSelfLocked) {
                    app = this.mPidsSelfLocked.get(
                            bf.receiverList.pid);
                }
            }
        } else {
            app = r.curApp;
        }
        
        if (app != null) {
            anrMessage = "Broadcast of " + r.intent.toString();
        }

        if (mPendingBroadcast == r) {
            mPendingBroadcast = null;
        }

        // Move on to the next receiver.
        finishReceiverLocked(r.receiver, r.resultCode, r.resultData,
                r.resultExtras, r.resultAbort, true);
        scheduleBroadcastsLocked();

        if (anrMessage != null) {
            // Post the ANR to the handler since we do not want to process ANRs while
            // potentially holding our lock.
            mHandler.post(new AppNotResponding(app, anrMessage));
        }
    }

    private final void processCurBroadcastLocked(BroadcastRecord r,
            ProcessRecord app) throws RemoteException {
        if (DEBUG_BROADCAST)  Slog.v(TAG,
                "Process cur broadcast " + r + " for app " + app);
        if (app.thread == null) {
            throw new RemoteException();
        }
        r.receiver = app.thread.asBinder();
        r.curApp = app;
        app.curReceiver = r;
        updateLruProcessLocked(app, true, true);

        // Tell the application to launch this receiver.
        r.intent.setComponent(r.curComponent);

        boolean started = false;
        try {
            if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG,
                    "Delivering to component " + r.curComponent
                    + ": " + r);
            ensurePackageDexOpt(r.intent.getComponent().getPackageName());
            app.thread.scheduleReceiver(new Intent(r.intent), r.curReceiver,
                    compatibilityInfoForPackageLocked(r.curReceiver.applicationInfo),
                    r.resultCode, r.resultData, r.resultExtras, r.ordered);
            if (DEBUG_BROADCAST)  Slog.v(TAG,
                    "Process cur broadcast " + r + " DELIVERED for app " + app);
            started = true;
        } finally {
            if (!started) {
                if (DEBUG_BROADCAST)  Slog.v(TAG,
                        "Process cur broadcast " + r + ": NOT STARTED!");
                r.receiver = null;
                r.curApp = null;
                app.curReceiver = null;
            }
        }

    }

    static void performReceiveLocked(ProcessRecord app, IIntentReceiver receiver,
            Intent intent, int resultCode, String data, Bundle extras,
            boolean ordered, boolean sticky) throws RemoteException {
        // Send the intent to the receiver asynchronously using one-way binder calls.
        if (app != null && app.thread != null) {
            // If we have an app thread, do the call through that so it is
            // correctly ordered with other one-way calls.
            app.thread.scheduleRegisteredReceiver(receiver, intent, resultCode,
                    data, extras, ordered, sticky);
        } else {
            receiver.performReceive(intent, resultCode, data, extras, ordered, sticky);
        }
    }
    
    private final void deliverToRegisteredReceiverLocked(BroadcastRecord r,
            BroadcastFilter filter, boolean ordered) {
        boolean skip = false;
        if (filter.requiredPermission != null) {
            int perm = checkComponentPermission(filter.requiredPermission,
                    r.callingPid, r.callingUid, -1, true);
            if (perm != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "Permission Denial: broadcasting "
                        + r.intent.toString()
                        + " from " + r.callerPackage + " (pid="
                        + r.callingPid + ", uid=" + r.callingUid + ")"
                        + " requires " + filter.requiredPermission
                        + " due to registered receiver " + filter);
                skip = true;
            }
        }
        if (r.requiredPermission != null) {
            int perm = checkComponentPermission(r.requiredPermission,
                    filter.receiverList.pid, filter.receiverList.uid, -1, true);
            if (perm != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "Permission Denial: receiving "
                        + r.intent.toString()
                        + " to " + filter.receiverList.app
                        + " (pid=" + filter.receiverList.pid
                        + ", uid=" + filter.receiverList.uid + ")"
                        + " requires " + r.requiredPermission
                        + " due to sender " + r.callerPackage
                        + " (uid " + r.callingUid + ")");
                skip = true;
            }
        }

        if (!skip) {
            // If this is not being sent as an ordered broadcast, then we
            // don't want to touch the fields that keep track of the current
            // state of ordered broadcasts.
            if (ordered) {
                r.receiver = filter.receiverList.receiver.asBinder();
                r.curFilter = filter;
                filter.receiverList.curBroadcast = r;
                r.state = BroadcastRecord.CALL_IN_RECEIVE;
                if (filter.receiverList.app != null) {
                    // Bump hosting application to no longer be in background
                    // scheduling class.  Note that we can't do that if there
                    // isn't an app...  but we can only be in that case for
                    // things that directly call the IActivityManager API, which
                    // are already core system stuff so don't matter for this.
                    r.curApp = filter.receiverList.app;
                    filter.receiverList.app.curReceiver = r;
                    updateOomAdjLocked();
                }
            }
            try {
                if (DEBUG_BROADCAST_LIGHT) {
                    int seq = r.intent.getIntExtra("seq", -1);
                    Slog.i(TAG, "Delivering to " + filter
                            + " (seq=" + seq + "): " + r);
                }
                performReceiveLocked(filter.receiverList.app, filter.receiverList.receiver,
                    new Intent(r.intent), r.resultCode,
                    r.resultData, r.resultExtras, r.ordered, r.initialSticky);
                if (ordered) {
                    r.state = BroadcastRecord.CALL_DONE_RECEIVE;
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Failure sending broadcast " + r.intent, e);
                if (ordered) {
                    r.receiver = null;
                    r.curFilter = null;
                    filter.receiverList.curBroadcast = null;
                    if (filter.receiverList.app != null) {
                        filter.receiverList.app.curReceiver = null;
                    }
                }
            }
        }
    }

    private final void addBroadcastToHistoryLocked(BroadcastRecord r) {
        if (r.callingUid < 0) {
            // This was from a registerReceiver() call; ignore it.
            return;
        }
        System.arraycopy(mBroadcastHistory, 0, mBroadcastHistory, 1,
                MAX_BROADCAST_HISTORY-1);
        r.finishTime = SystemClock.uptimeMillis();
        mBroadcastHistory[0] = r;
    }
    
    private final void processNextBroadcast(boolean fromMsg) {
        synchronized(this) {
            BroadcastRecord r;

            if (DEBUG_BROADCAST) Slog.v(TAG, "processNextBroadcast: "
                    + mParallelBroadcasts.size() + " broadcasts, "
                    + mOrderedBroadcasts.size() + " ordered broadcasts");

            updateCpuStats();
            
            if (fromMsg) {
                mBroadcastsScheduled = false;
            }

            // First, deliver any non-serialized broadcasts right away.
            while (mParallelBroadcasts.size() > 0) {
                r = mParallelBroadcasts.remove(0);
                r.dispatchTime = SystemClock.uptimeMillis();
                final int N = r.receivers.size();
                if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG, "Processing parallel broadcast "
                        + r);
                for (int i=0; i<N; i++) {
                    Object target = r.receivers.get(i);
                    if (DEBUG_BROADCAST)  Slog.v(TAG,
                            "Delivering non-ordered to registered "
                            + target + ": " + r);
                    deliverToRegisteredReceiverLocked(r, (BroadcastFilter)target, false);
                }
                addBroadcastToHistoryLocked(r);
                if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG, "Done with parallel broadcast "
                        + r);
            }

            // Now take care of the next serialized one...

            // If we are waiting for a process to come up to handle the next
            // broadcast, then do nothing at this point.  Just in case, we
            // check that the process we're waiting for still exists.
            if (mPendingBroadcast != null) {
                if (DEBUG_BROADCAST_LIGHT) {
                    Slog.v(TAG, "processNextBroadcast: waiting for "
                            + mPendingBroadcast.curApp);
                }

                boolean isDead;
                synchronized (mPidsSelfLocked) {
                    isDead = (mPidsSelfLocked.get(mPendingBroadcast.curApp.pid) == null);
                }
                if (!isDead) {
                    // It's still alive, so keep waiting
                    return;
                } else {
                    Slog.w(TAG, "pending app " + mPendingBroadcast.curApp
                            + " died before responding to broadcast");
                    mPendingBroadcast.state = BroadcastRecord.IDLE;
                    mPendingBroadcast.nextReceiver = mPendingBroadcastRecvIndex;
                    mPendingBroadcast = null;
                }
            }

            boolean looped = false;
            
            do {
                if (mOrderedBroadcasts.size() == 0) {
                    // No more broadcasts pending, so all done!
                    scheduleAppGcsLocked();
                    if (looped) {
                        // If we had finished the last ordered broadcast, then
                        // make sure all processes have correct oom and sched
                        // adjustments.
                        updateOomAdjLocked();
                    }
                    return;
                }
                r = mOrderedBroadcasts.get(0);
                boolean forceReceive = false;

                // Ensure that even if something goes awry with the timeout
                // detection, we catch "hung" broadcasts here, discard them,
                // and continue to make progress.
                //
                // This is only done if the system is ready so that PRE_BOOT_COMPLETED
                // receivers don't get executed with timeouts. They're intended for
                // one time heavy lifting after system upgrades and can take
                // significant amounts of time.
                int numReceivers = (r.receivers != null) ? r.receivers.size() : 0;
                if (mProcessesReady && r.dispatchTime > 0) {
                    long now = SystemClock.uptimeMillis();
                    if ((numReceivers > 0) &&
                            (now > r.dispatchTime + (2*BROADCAST_TIMEOUT*numReceivers))) {
                        Slog.w(TAG, "Hung broadcast discarded after timeout failure:"
                                + " now=" + now
                                + " dispatchTime=" + r.dispatchTime
                                + " startTime=" + r.receiverTime
                                + " intent=" + r.intent
                                + " numReceivers=" + numReceivers
                                + " nextReceiver=" + r.nextReceiver
                                + " state=" + r.state);
                        broadcastTimeoutLocked(false); // forcibly finish this broadcast
                        forceReceive = true;
                        r.state = BroadcastRecord.IDLE;
                    }
                }

                if (r.state != BroadcastRecord.IDLE) {
                    if (DEBUG_BROADCAST) Slog.d(TAG,
                            "processNextBroadcast() called when not idle (state="
                            + r.state + ")");
                    return;
                }

                if (r.receivers == null || r.nextReceiver >= numReceivers
                        || r.resultAbort || forceReceive) {
                    // No more receivers for this broadcast!  Send the final
                    // result if requested...
                    if (r.resultTo != null) {
                        try {
                            if (DEBUG_BROADCAST) {
                                int seq = r.intent.getIntExtra("seq", -1);
                                Slog.i(TAG, "Finishing broadcast " + r.intent.getAction()
                                        + " seq=" + seq + " app=" + r.callerApp);
                            }
                            performReceiveLocked(r.callerApp, r.resultTo,
                                new Intent(r.intent), r.resultCode,
                                r.resultData, r.resultExtras, false, false);
                            // Set this to null so that the reference
                            // (local and remote) isnt kept in the mBroadcastHistory.
                            r.resultTo = null;
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Failure sending broadcast result of " + r.intent, e);
                        }
                    }
                    
                    if (DEBUG_BROADCAST) Slog.v(TAG, "Cancelling BROADCAST_TIMEOUT_MSG");
                    cancelBroadcastTimeoutLocked();

                    if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG, "Finished with ordered broadcast "
                            + r);
                    
                    // ... and on to the next...
                    addBroadcastToHistoryLocked(r);
                    mOrderedBroadcasts.remove(0);
                    r = null;
                    looped = true;
                    continue;
                }
            } while (r == null);

            // Get the next receiver...
            int recIdx = r.nextReceiver++;

            // Keep track of when this receiver started, and make sure there
            // is a timeout message pending to kill it if need be.
            r.receiverTime = SystemClock.uptimeMillis();
            if (recIdx == 0) {
                r.dispatchTime = r.receiverTime;

                if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG, "Processing ordered broadcast "
                        + r);
            }
            if (! mPendingBroadcastTimeoutMessage) {
                long timeoutTime = r.receiverTime + BROADCAST_TIMEOUT;
                if (DEBUG_BROADCAST) Slog.v(TAG,
                        "Submitting BROADCAST_TIMEOUT_MSG for " + r + " at " + timeoutTime);
                setBroadcastTimeoutLocked(timeoutTime);
            }

            Object nextReceiver = r.receivers.get(recIdx);
            if (nextReceiver instanceof BroadcastFilter) {
                // Simple case: this is a registered receiver who gets
                // a direct call.
                BroadcastFilter filter = (BroadcastFilter)nextReceiver;
                if (DEBUG_BROADCAST)  Slog.v(TAG,
                        "Delivering ordered to registered "
                        + filter + ": " + r);
                deliverToRegisteredReceiverLocked(r, filter, r.ordered);
                if (r.receiver == null || !r.ordered) {
                    // The receiver has already finished, so schedule to
                    // process the next one.
                    if (DEBUG_BROADCAST) Slog.v(TAG, "Quick finishing: ordered="
                            + r.ordered + " receiver=" + r.receiver);
                    r.state = BroadcastRecord.IDLE;
                    scheduleBroadcastsLocked();
                }
                return;
            }

            // Hard case: need to instantiate the receiver, possibly
            // starting its application process to host it.

            ResolveInfo info =
                (ResolveInfo)nextReceiver;

            boolean skip = false;
            int perm = checkComponentPermission(info.activityInfo.permission,
                    r.callingPid, r.callingUid, info.activityInfo.applicationInfo.uid,
                    info.activityInfo.exported);
            if (perm != PackageManager.PERMISSION_GRANTED) {
                if (!info.activityInfo.exported) {
                    Slog.w(TAG, "Permission Denial: broadcasting "
                            + r.intent.toString()
                            + " from " + r.callerPackage + " (pid=" + r.callingPid
                            + ", uid=" + r.callingUid + ")"
                            + " is not exported from uid " + info.activityInfo.applicationInfo.uid
                            + " due to receiver " + info.activityInfo.packageName
                            + "/" + info.activityInfo.name);
                } else {
                    Slog.w(TAG, "Permission Denial: broadcasting "
                            + r.intent.toString()
                            + " from " + r.callerPackage + " (pid=" + r.callingPid
                            + ", uid=" + r.callingUid + ")"
                            + " requires " + info.activityInfo.permission
                            + " due to receiver " + info.activityInfo.packageName
                            + "/" + info.activityInfo.name);
                }
                skip = true;
            }
            if (r.callingUid != Process.SYSTEM_UID &&
                r.requiredPermission != null) {
                try {
                    perm = AppGlobals.getPackageManager().
                            checkPermission(r.requiredPermission,
                                    info.activityInfo.applicationInfo.packageName);
                } catch (RemoteException e) {
                    perm = PackageManager.PERMISSION_DENIED;
                }
                if (perm != PackageManager.PERMISSION_GRANTED) {
                    Slog.w(TAG, "Permission Denial: receiving "
                            + r.intent + " to "
                            + info.activityInfo.applicationInfo.packageName
                            + " requires " + r.requiredPermission
                            + " due to sender " + r.callerPackage
                            + " (uid " + r.callingUid + ")");
                    skip = true;
                }
            }
            if (r.curApp != null && r.curApp.crashing) {
                // If the target process is crashing, just skip it.
                if (DEBUG_BROADCAST)  Slog.v(TAG,
                        "Skipping deliver ordered " + r + " to " + r.curApp
                        + ": process crashing");
                skip = true;
            }

            if (skip) {
                if (DEBUG_BROADCAST)  Slog.v(TAG,
                        "Skipping delivery of ordered " + r + " for whatever reason");
                r.receiver = null;
                r.curFilter = null;
                r.state = BroadcastRecord.IDLE;
                scheduleBroadcastsLocked();
                return;
            }

            r.state = BroadcastRecord.APP_RECEIVE;
            String targetProcess = info.activityInfo.processName;
            r.curComponent = new ComponentName(
                    info.activityInfo.applicationInfo.packageName,
                    info.activityInfo.name);
            r.curReceiver = info.activityInfo;

            // Broadcast is being executed, its package can't be stopped.
            try {
                AppGlobals.getPackageManager().setPackageStoppedState(
                        r.curComponent.getPackageName(), false);
            } catch (RemoteException e) {
            } catch (IllegalArgumentException e) {
                Slog.w(TAG, "Failed trying to unstop package "
                        + r.curComponent.getPackageName() + ": " + e);
            }

            // Is this receiver's application already running?
            ProcessRecord app = getProcessRecordLocked(targetProcess,
                    info.activityInfo.applicationInfo.uid);
            if (app != null && app.thread != null) {
                try {
                    app.addPackage(info.activityInfo.packageName);
                    processCurBroadcastLocked(r, app);
                    return;
                } catch (RemoteException e) {
                    Slog.w(TAG, "Exception when sending broadcast to "
                          + r.curComponent, e);
                }

                // If a dead object exception was thrown -- fall through to
                // restart the application.
            }

            // Not running -- get it started, to be executed when the app comes up.
            if (DEBUG_BROADCAST)  Slog.v(TAG,
                    "Need to start app " + targetProcess + " for broadcast " + r);
            if ((r.curApp=startProcessLocked(targetProcess,
                    info.activityInfo.applicationInfo, true,
                    r.intent.getFlags() | Intent.FLAG_FROM_BACKGROUND,
                    "broadcast", r.curComponent,
                    (r.intent.getFlags()&Intent.FLAG_RECEIVER_BOOT_UPGRADE) != 0))
                            == null) {
                // Ah, this recipient is unavailable.  Finish it if necessary,
                // and mark the broadcast record as ready for the next.
                Slog.w(TAG, "Unable to launch app "
                        + info.activityInfo.applicationInfo.packageName + "/"
                        + info.activityInfo.applicationInfo.uid + " for broadcast "
                        + r.intent + ": process is bad");
                logBroadcastReceiverDiscardLocked(r);
                finishReceiverLocked(r.receiver, r.resultCode, r.resultData,
                        r.resultExtras, r.resultAbort, true);
                scheduleBroadcastsLocked();
                r.state = BroadcastRecord.IDLE;
                return;
            }

            mPendingBroadcast = r;
            mPendingBroadcastRecvIndex = recIdx;
        }
    }

    // =========================================================
    // INSTRUMENTATION
    // =========================================================

    public boolean startInstrumentation(ComponentName className,
            String profileFile, int flags, Bundle arguments,
            IInstrumentationWatcher watcher) {
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
                ai = mContext.getPackageManager().getApplicationInfo(
                    ii.targetPackage, STOCK_PM_FLAGS);
            } catch (PackageManager.NameNotFoundException e) {
            }
            if (ii == null) {
                reportStartInstrumentationFailure(watcher, className,
                        "Unable to find instrumentation info for: " + className);
                return false;
            }
            if (ai == null) {
                reportStartInstrumentationFailure(watcher, className,
                        "Unable to find instrumentation target package: " + ii.targetPackage);
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
                reportStartInstrumentationFailure(watcher, className, msg);
                throw new SecurityException(msg);
            }

            final long origId = Binder.clearCallingIdentity();
            forceStopPackageLocked(ii.targetPackage, -1, true, false, true);
            ProcessRecord app = addAppLocked(ai);
            app.instrumentationClass = className;
            app.instrumentationInfo = ai;
            app.instrumentationProfileFile = profileFile;
            app.instrumentationArguments = arguments;
            app.instrumentationWatcher = watcher;
            app.instrumentationResultClass = className;
            Binder.restoreCallingIdentity(origId);
        }

        return true;
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
    private void reportStartInstrumentationFailure(IInstrumentationWatcher watcher, 
            ComponentName cn, String report) {
        Slog.w(TAG, report);
        try {
            if (watcher != null) {
                Bundle results = new Bundle();
                results.putString(Instrumentation.REPORT_KEY_IDENTIFIER, "ActivityManagerService");
                results.putString("Error", report);
                watcher.instrumentationStatus(cn, -1, results);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, e);
        }
    }

    void finishInstrumentationLocked(ProcessRecord app, int resultCode, Bundle results) {
        if (app.instrumentationWatcher != null) {
            try {
                // NOTE:  IInstrumentationWatcher *must* be oneway here
                app.instrumentationWatcher.instrumentationFinished(
                    app.instrumentationClass,
                    resultCode,
                    results);
            } catch (RemoteException e) {
            }
        }
        app.instrumentationWatcher = null;
        app.instrumentationClass = null;
        app.instrumentationInfo = null;
        app.instrumentationProfileFile = null;
        app.instrumentationArguments = null;

        forceStopPackageLocked(app.processName, -1, false, false, true);
    }

    public void finishInstrumentation(IApplicationThread target,
            int resultCode, Bundle results) {
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

    // =========================================================
    // CONFIGURATION
    // =========================================================
    
    public ConfigurationInfo getDeviceConfigurationInfo() {
        ConfigurationInfo config = new ConfigurationInfo();
        synchronized (this) {
            config.reqTouchScreen = mConfiguration.touchscreen;
            config.reqKeyboardType = mConfiguration.keyboard;
            config.reqNavigation = mConfiguration.navigation;
            if (mConfiguration.navigation == Configuration.NAVIGATION_DPAD
                    || mConfiguration.navigation == Configuration.NAVIGATION_TRACKBALL) {
                config.reqInputFeatures |= ConfigurationInfo.INPUT_FEATURE_FIVE_WAY_NAV;
            }
            if (mConfiguration.keyboard != Configuration.KEYBOARD_UNDEFINED
                    && mConfiguration.keyboard != Configuration.KEYBOARD_NOKEYS) {
                config.reqInputFeatures |= ConfigurationInfo.INPUT_FEATURE_HARD_KEYBOARD;
            }
            config.reqGlEsVersion = GL_ES_VERSION;
        }
        return config;
    }

    public Configuration getConfiguration() {
        Configuration ci;
        synchronized(this) {
            ci = new Configuration(mConfiguration);
        }
        return ci;
    }

    public void updatePersistentConfiguration(Configuration values) {
        enforceCallingPermission(android.Manifest.permission.CHANGE_CONFIGURATION,
                "updateConfiguration()");
        enforceCallingPermission(android.Manifest.permission.WRITE_SETTINGS,
                "updateConfiguration()");
        if (values == null) {
            throw new NullPointerException("Configuration must not be null");
        }

        synchronized(this) {
            final long origId = Binder.clearCallingIdentity();
            updateConfigurationLocked(values, null, true);
            Binder.restoreCallingIdentity(origId);
        }
    }

    public void updateConfiguration(Configuration values) {
        enforceCallingPermission(android.Manifest.permission.CHANGE_CONFIGURATION,
                "updateConfiguration()");

        synchronized(this) {
            if (values == null && mWindowManager != null) {
                // sentinel: fetch the current configuration from the window manager
                values = mWindowManager.computeNewConfiguration();
            }

            if (mWindowManager != null) {
                mProcessList.applyDisplaySize(mWindowManager);
            }

            final long origId = Binder.clearCallingIdentity();
            if (values != null) {
                Settings.System.clearConfiguration(values);
            }
            updateConfigurationLocked(values, null, false);
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Do either or both things: (1) change the current configuration, and (2)
     * make sure the given activity is running with the (now) current
     * configuration.  Returns true if the activity has been left running, or
     * false if <var>starting</var> is being destroyed to match the new
     * configuration.
     * @param persistent TODO
     */
    public boolean updateConfigurationLocked(Configuration values,
            ActivityRecord starting, boolean persistent) {
        int changes = 0;
        
        boolean kept = true;
        
        if (values != null) {
            Configuration newConfig = new Configuration(mConfiguration);
            changes = newConfig.updateFrom(values);
            if (changes != 0) {
                if (DEBUG_SWITCH || DEBUG_CONFIGURATION) {
                    Slog.i(TAG, "Updating configuration to: " + values);
                }
                
                EventLog.writeEvent(EventLogTags.CONFIGURATION_CHANGED, changes);

                if (values.locale != null) {
                    saveLocaleLocked(values.locale, 
                                     !values.locale.equals(mConfiguration.locale),
                                     values.userSetLocale);
                }

                mConfigurationSeq++;
                if (mConfigurationSeq <= 0) {
                    mConfigurationSeq = 1;
                }
                newConfig.seq = mConfigurationSeq;
                mConfiguration = newConfig;
                Slog.i(TAG, "Config changed: " + newConfig);
                
                AttributeCache ac = AttributeCache.instance();
                if (ac != null) {
                    ac.updateConfiguration(mConfiguration);
                }

                // Make sure all resources in our process are updated
                // right now, so that anyone who is going to retrieve
                // resource values after we return will be sure to get
                // the new ones.  This is especially important during
                // boot, where the first config change needs to guarantee
                // all resources have that config before following boot
                // code is executed.
                mSystemThread.applyConfigurationToResources(newConfig);

                if (persistent && Settings.System.hasInterestingConfigurationChanges(changes)) {
                    Message msg = mHandler.obtainMessage(UPDATE_CONFIGURATION_MSG);
                    msg.obj = new Configuration(mConfiguration);
                    mHandler.sendMessage(msg);
                }
        
                for (int i=mLruProcesses.size()-1; i>=0; i--) {
                    ProcessRecord app = mLruProcesses.get(i);
                    try {
                        if (app.thread != null) {
                            if (DEBUG_CONFIGURATION) Slog.v(TAG, "Sending to proc "
                                    + app.processName + " new config " + mConfiguration);
                            app.thread.scheduleConfigurationChanged(mConfiguration);
                        }
                    } catch (Exception e) {
                    }
                }
                Intent intent = new Intent(Intent.ACTION_CONFIGURATION_CHANGED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                        | Intent.FLAG_RECEIVER_REPLACE_PENDING);
                broadcastIntentLocked(null, null, intent, null, null, 0, null, null,
                        null, false, false, MY_PID, Process.SYSTEM_UID);
                if ((changes&ActivityInfo.CONFIG_LOCALE) != 0) {
                    broadcastIntentLocked(null, null,
                            new Intent(Intent.ACTION_LOCALE_CHANGED),
                            null, null, 0, null, null,
                            null, false, false, MY_PID, Process.SYSTEM_UID);
                }
            }
        }
        
        if (changes != 0 && starting == null) {
            // If the configuration changed, and the caller is not already
            // in the process of starting an activity, then find the top
            // activity to check if its configuration needs to change.
            starting = mMainStack.topRunningActivityLocked(null);
        }
        
        if (starting != null) {
            kept = mMainStack.ensureActivityConfigurationLocked(starting, changes);
            // And we need to make sure at this point that all other activities
            // are made visible with the correct configuration.
            mMainStack.ensureActivitiesVisibleLocked(starting, changes);
        }
        
        if (values != null && mWindowManager != null) {
            mWindowManager.setNewConfiguration(mConfiguration);
        }
        
        return kept;
    }
    
    /**
     * Save the locale.  You must be inside a synchronized (this) block.
     */
    private void saveLocaleLocked(Locale l, boolean isDiff, boolean isPersist) {
        if(isDiff) {
            SystemProperties.set("user.language", l.getLanguage());
            SystemProperties.set("user.region", l.getCountry());
        } 

        if(isPersist) {
            SystemProperties.set("persist.sys.language", l.getLanguage());
            SystemProperties.set("persist.sys.country", l.getCountry());
            SystemProperties.set("persist.sys.localevar", l.getVariant());
        }
    }

    // =========================================================
    // LIFETIME MANAGEMENT
    // =========================================================

    private final int computeOomAdjLocked(ProcessRecord app, int hiddenAdj,
            ProcessRecord TOP_APP, boolean recursed) {
        if (mAdjSeq == app.adjSeq) {
            // This adjustment has already been computed.  If we are calling
            // from the top, we may have already computed our adjustment with
            // an earlier hidden adjustment that isn't really for us... if
            // so, use the new hidden adjustment.
            if (!recursed && app.hidden) {
                app.curAdj = app.curRawAdj = hiddenAdj;
            }
            return app.curRawAdj;
        }

        if (app.thread == null) {
            app.adjSeq = mAdjSeq;
            app.curSchedGroup = Process.THREAD_GROUP_BG_NONINTERACTIVE;
            return (app.curAdj=ProcessList.EMPTY_APP_ADJ);
        }

        app.adjTypeCode = ActivityManager.RunningAppProcessInfo.REASON_UNKNOWN;
        app.adjSource = null;
        app.adjTarget = null;
        app.empty = false;
        app.hidden = false;

        final int activitiesSize = app.activities.size();

        if (app.maxAdj <= ProcessList.FOREGROUND_APP_ADJ) {
            // The max adjustment doesn't allow this app to be anything
            // below foreground, so it is not worth doing work for it.
            app.adjType = "fixed";
            app.adjSeq = mAdjSeq;
            app.curRawAdj = app.maxAdj;
            app.foregroundActivities = false;
            app.keeping = true;
            app.curSchedGroup = Process.THREAD_GROUP_DEFAULT;
            // System process can do UI, and when they do we want to have
            // them trim their memory after the user leaves the UI.  To
            // facilitate this, here we need to determine whether or not it
            // is currently showing UI.
            app.systemNoUi = true;
            if (app == TOP_APP) {
                app.systemNoUi = false;
            } else if (activitiesSize > 0) {
                for (int j = 0; j < activitiesSize; j++) {
                    final ActivityRecord r = app.activities.get(j);
                    if (r.visible) {
                        app.systemNoUi = false;
                        break;
                    }
                }
            }
            return (app.curAdj=app.maxAdj);
        }

        final boolean hadForegroundActivities = app.foregroundActivities;

        app.foregroundActivities = false;
        app.keeping = false;
        app.systemNoUi = false;

        // Determine the importance of the process, starting with most
        // important to least, and assign an appropriate OOM adjustment.
        int adj;
        int schedGroup;
        if (app == TOP_APP) {
            // The last app on the list is the foreground app.
            adj = ProcessList.FOREGROUND_APP_ADJ;
            schedGroup = Process.THREAD_GROUP_DEFAULT;
            app.adjType = "top-activity";
            app.foregroundActivities = true;
        } else if (app.instrumentationClass != null) {
            // Don't want to kill running instrumentation.
            adj = ProcessList.FOREGROUND_APP_ADJ;
            schedGroup = Process.THREAD_GROUP_DEFAULT;
            app.adjType = "instrumentation";
        } else if (app.curReceiver != null ||
                (mPendingBroadcast != null && mPendingBroadcast.curApp == app)) {
            // An app that is currently receiving a broadcast also
            // counts as being in the foreground.
            adj = ProcessList.FOREGROUND_APP_ADJ;
            schedGroup = Process.THREAD_GROUP_DEFAULT;
            app.adjType = "broadcast";
        } else if (app.executingServices.size() > 0) {
            // An app that is currently executing a service callback also
            // counts as being in the foreground.
            adj = ProcessList.FOREGROUND_APP_ADJ;
            schedGroup = Process.THREAD_GROUP_DEFAULT;
            app.adjType = "exec-service";
        } else if (activitiesSize > 0) {
            // This app is in the background with paused activities.
            // We inspect activities to potentially upgrade adjustment further below.
            adj = hiddenAdj;
            schedGroup = Process.THREAD_GROUP_BG_NONINTERACTIVE;
            app.hidden = true;
            app.adjType = "bg-activities";
        } else {
            // A very not-needed process.  If this is lower in the lru list,
            // we will push it in to the empty bucket.
            adj = hiddenAdj;
            schedGroup = Process.THREAD_GROUP_BG_NONINTERACTIVE;
            app.hidden = true;
            app.empty = true;
            app.adjType = "bg-empty";
        }

        // Examine all activities if not already foreground.
        if (!app.foregroundActivities && activitiesSize > 0) {
            for (int j = 0; j < activitiesSize; j++) {
                final ActivityRecord r = app.activities.get(j);
                if (r.visible) {
                    // App has a visible activity; only upgrade adjustment.
                    if (adj > ProcessList.VISIBLE_APP_ADJ) {
                        adj = ProcessList.VISIBLE_APP_ADJ;
                        app.adjType = "visible";
                    }
                    schedGroup = Process.THREAD_GROUP_DEFAULT;
                    app.hidden = false;
                    app.foregroundActivities = true;
                    break;
                } else if (r.state == ActivityState.PAUSING || r.state == ActivityState.PAUSED
                        || r.state == ActivityState.STOPPING) {
                    // Only upgrade adjustment.
                    if (adj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                        adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                        app.adjType = "stopping";
                    }
                    app.foregroundActivities = true;
                }
            }
        }

        if (adj > ProcessList.PERCEPTIBLE_APP_ADJ) {
            if (app.foregroundServices) {
                // The user is aware of this app, so make it visible.
                adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                app.adjType = "foreground-service";
                schedGroup = Process.THREAD_GROUP_DEFAULT;
            } else if (app.forcingToForeground != null) {
                // The user is aware of this app, so make it visible.
                adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                app.adjType = "force-foreground";
                app.adjSource = app.forcingToForeground;
                schedGroup = Process.THREAD_GROUP_DEFAULT;
            }
        }

        if (adj > ProcessList.HEAVY_WEIGHT_APP_ADJ && app == mHeavyWeightProcess) {
            // We don't want to kill the current heavy-weight process.
            adj = ProcessList.HEAVY_WEIGHT_APP_ADJ;
            schedGroup = Process.THREAD_GROUP_BG_NONINTERACTIVE;
            app.adjType = "heavy";
        }

        if (adj > ProcessList.HOME_APP_ADJ && app == mHomeProcess) {
            // This process is hosting what we currently consider to be the
            // home app, so we don't want to let it go into the background.
            adj = ProcessList.HOME_APP_ADJ;
            schedGroup = Process.THREAD_GROUP_BG_NONINTERACTIVE;
            app.adjType = "home";
        }
        
        //Slog.i(TAG, "OOM " + app + ": initial adj=" + adj);
        
        // By default, we use the computed adjustment.  It may be changed if
        // there are applications dependent on our services or providers, but
        // this gives us a baseline and makes sure we don't get into an
        // infinite recursion.
        app.adjSeq = mAdjSeq;
        app.curRawAdj = adj;

        if (mBackupTarget != null && app == mBackupTarget.app) {
            // If possible we want to avoid killing apps while they're being backed up
            if (adj > ProcessList.BACKUP_APP_ADJ) {
                if (DEBUG_BACKUP) Slog.v(TAG, "oom BACKUP_APP_ADJ for " + app);
                adj = ProcessList.BACKUP_APP_ADJ;
                app.adjType = "backup";
                app.hidden = false;
            }
        }

        if (app.services.size() != 0 && (adj > ProcessList.FOREGROUND_APP_ADJ
                || schedGroup == Process.THREAD_GROUP_BG_NONINTERACTIVE)) {
            final long now = SystemClock.uptimeMillis();
            // This process is more important if the top activity is
            // bound to the service.
            Iterator<ServiceRecord> jt = app.services.iterator();
            while (jt.hasNext() && adj > ProcessList.FOREGROUND_APP_ADJ) {
                ServiceRecord s = jt.next();
                if (s.startRequested) {
                    if (app.hasShownUi) {
                        // If this process has shown some UI, let it immediately
                        // go to the LRU list because it may be pretty heavy with
                        // UI stuff.  We'll tag it with a label just to help
                        // debug and understand what is going on.
                        if (adj > ProcessList.SECONDARY_SERVER_ADJ) {
                            app.adjType = "started-bg-ui-services";
                        }
                    } else {
                        if (now < (s.lastActivity+MAX_SERVICE_INACTIVITY)) {
                            // This service has seen some activity within
                            // recent memory, so we will keep its process ahead
                            // of the background processes.
                            if (adj > ProcessList.SECONDARY_SERVER_ADJ) {
                                adj = ProcessList.SECONDARY_SERVER_ADJ;
                                app.adjType = "started-services";
                                app.hidden = false;
                            }
                        }
                        // If we have let the service slide into the background
                        // state, still have some text describing what it is doing
                        // even though the service no longer has an impact.
                        if (adj > ProcessList.SECONDARY_SERVER_ADJ) {
                            app.adjType = "started-bg-services";
                        }
                    }
                    // Don't kill this process because it is doing work; it
                    // has said it is doing work.
                    app.keeping = true;
                }
                if (s.connections.size() > 0 && (adj > ProcessList.FOREGROUND_APP_ADJ
                        || schedGroup == Process.THREAD_GROUP_BG_NONINTERACTIVE)) {
                    Iterator<ArrayList<ConnectionRecord>> kt
                            = s.connections.values().iterator();
                    while (kt.hasNext() && adj > ProcessList.FOREGROUND_APP_ADJ) {
                        ArrayList<ConnectionRecord> clist = kt.next();
                        for (int i=0; i<clist.size() && adj > ProcessList.FOREGROUND_APP_ADJ; i++) {
                            // XXX should compute this based on the max of
                            // all connected clients.
                            ConnectionRecord cr = clist.get(i);
                            if (cr.binding.client == app) {
                                // Binding to ourself is not interesting.
                                continue;
                            }
                            if ((cr.flags&Context.BIND_WAIVE_PRIORITY) == 0) {
                                ProcessRecord client = cr.binding.client;
                                int clientAdj = adj;
                                int myHiddenAdj = hiddenAdj;
                                if (myHiddenAdj > client.hiddenAdj) {
                                    if (client.hiddenAdj >= ProcessList.VISIBLE_APP_ADJ) {
                                        myHiddenAdj = client.hiddenAdj;
                                    } else {
                                        myHiddenAdj = ProcessList.VISIBLE_APP_ADJ;
                                    }
                                }
                                clientAdj = computeOomAdjLocked(
                                    client, myHiddenAdj, TOP_APP, true);
                                String adjType = null;
                                if ((cr.flags&Context.BIND_ALLOW_OOM_MANAGEMENT) != 0) {
                                    // Not doing bind OOM management, so treat
                                    // this guy more like a started service.
                                    if (app.hasShownUi) {
                                        // If this process has shown some UI, let it immediately
                                        // go to the LRU list because it may be pretty heavy with
                                        // UI stuff.  We'll tag it with a label just to help
                                        // debug and understand what is going on.
                                        if (adj > clientAdj) {
                                            adjType = "bound-bg-ui-services";
                                        }
                                        clientAdj = adj;
                                    } else {
                                        if (now >= (s.lastActivity+MAX_SERVICE_INACTIVITY)) {
                                            // This service has not seen activity within
                                            // recent memory, so allow it to drop to the
                                            // LRU list if there is no other reason to keep
                                            // it around.  We'll also tag it with a label just
                                            // to help debug and undertand what is going on.
                                            if (adj > clientAdj) {
                                                adjType = "bound-bg-services";
                                            }
                                            clientAdj = adj;
                                        }
                                    }
                                }
                                if (adj > clientAdj) {
                                    // If this process has recently shown UI, and
                                    // the process that is binding to it is less
                                    // important than being visible, then we don't
                                    // care about the binding as much as we care
                                    // about letting this process get into the LRU
                                    // list to be killed and restarted if needed for
                                    // memory.
                                    if (app.hasShownUi && clientAdj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                                        adjType = "bound-bg-ui-services";
                                    } else {
                                        if ((cr.flags&(Context.BIND_ABOVE_CLIENT
                                                |Context.BIND_IMPORTANT)) != 0) {
                                            adj = clientAdj;
                                        } else if (clientAdj >= ProcessList.VISIBLE_APP_ADJ) {
                                            adj = clientAdj;
                                        } else {
                                            adj = ProcessList.VISIBLE_APP_ADJ;
                                        }
                                        if (!client.hidden) {
                                            app.hidden = false;
                                        }
                                        if (client.keeping) {
                                            app.keeping = true;
                                        }
                                        adjType = "service";
                                    }
                                }
                                if (adjType != null) {
                                    app.adjType = adjType;
                                    app.adjTypeCode = ActivityManager.RunningAppProcessInfo
                                            .REASON_SERVICE_IN_USE;
                                    app.adjSource = cr.binding.client;
                                    app.adjTarget = s.name;
                                }
                                if ((cr.flags&Context.BIND_NOT_FOREGROUND) == 0) {
                                    if (client.curSchedGroup == Process.THREAD_GROUP_DEFAULT) {
                                        schedGroup = Process.THREAD_GROUP_DEFAULT;
                                    }
                                }
                            }
                            if ((cr.flags&Context.BIND_ADJUST_WITH_ACTIVITY) != 0) {
                                ActivityRecord a = cr.activity;
                                if (a != null && adj > ProcessList.FOREGROUND_APP_ADJ &&
                                        (a.visible || a.state == ActivityState.RESUMED
                                         || a.state == ActivityState.PAUSING)) {
                                    adj = ProcessList.FOREGROUND_APP_ADJ;
                                    if ((cr.flags&Context.BIND_NOT_FOREGROUND) == 0) {
                                        schedGroup = Process.THREAD_GROUP_DEFAULT;
                                    }
                                    app.hidden = false;
                                    app.adjType = "service";
                                    app.adjTypeCode = ActivityManager.RunningAppProcessInfo
                                            .REASON_SERVICE_IN_USE;
                                    app.adjSource = a;
                                    app.adjTarget = s.name;
                                }
                            }
                        }
                    }
                }
            }
            
            // Finally, if this process has active services running in it, we
            // would like to avoid killing it unless it would prevent the current
            // application from running.  By default we put the process in
            // with the rest of the background processes; as we scan through
            // its services we may bump it up from there.
            if (adj > hiddenAdj) {
                adj = hiddenAdj;
                app.hidden = false;
                app.adjType = "bg-services";
            }
        }

        if (app.pubProviders.size() != 0 && (adj > ProcessList.FOREGROUND_APP_ADJ
                || schedGroup == Process.THREAD_GROUP_BG_NONINTERACTIVE)) {
            Iterator<ContentProviderRecord> jt = app.pubProviders.values().iterator();
            while (jt.hasNext() && (adj > ProcessList.FOREGROUND_APP_ADJ
                    || schedGroup == Process.THREAD_GROUP_BG_NONINTERACTIVE)) {
                ContentProviderRecord cpr = jt.next();
                if (cpr.clients.size() != 0) {
                    Iterator<ProcessRecord> kt = cpr.clients.iterator();
                    while (kt.hasNext() && adj > ProcessList.FOREGROUND_APP_ADJ) {
                        ProcessRecord client = kt.next();
                        if (client == app) {
                            // Being our own client is not interesting.
                            continue;
                        }
                        int myHiddenAdj = hiddenAdj;
                        if (myHiddenAdj > client.hiddenAdj) {
                            if (client.hiddenAdj > ProcessList.FOREGROUND_APP_ADJ) {
                                myHiddenAdj = client.hiddenAdj;
                            } else {
                                myHiddenAdj = ProcessList.FOREGROUND_APP_ADJ;
                            }
                        }
                        int clientAdj = computeOomAdjLocked(
                            client, myHiddenAdj, TOP_APP, true);
                        if (adj > clientAdj) {
                            if (app.hasShownUi && clientAdj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                                app.adjType = "bg-ui-provider";
                            } else {
                                adj = clientAdj > ProcessList.FOREGROUND_APP_ADJ
                                        ? clientAdj : ProcessList.FOREGROUND_APP_ADJ;
                                app.adjType = "provider";
                            }
                            if (!client.hidden) {
                                app.hidden = false;
                            }
                            if (client.keeping) {
                                app.keeping = true;
                            }
                            app.adjTypeCode = ActivityManager.RunningAppProcessInfo
                                    .REASON_PROVIDER_IN_USE;
                            app.adjSource = client;
                            app.adjTarget = cpr.name;
                        }
                        if (client.curSchedGroup == Process.THREAD_GROUP_DEFAULT) {
                            schedGroup = Process.THREAD_GROUP_DEFAULT;
                        }
                    }
                }
                // If the provider has external (non-framework) process
                // dependencies, ensure that its adjustment is at least
                // FOREGROUND_APP_ADJ.
                if (cpr.externals != 0) {
                    if (adj > ProcessList.FOREGROUND_APP_ADJ) {
                        adj = ProcessList.FOREGROUND_APP_ADJ;
                        schedGroup = Process.THREAD_GROUP_DEFAULT;
                        app.hidden = false;
                        app.keeping = true;
                        app.adjType = "provider";
                        app.adjTarget = cpr.name;
                    }
                }
            }
        }

        app.curRawAdj = adj;
        
        //Slog.i(TAG, "OOM ADJ " + app + ": pid=" + app.pid +
        //      " adj=" + adj + " curAdj=" + app.curAdj + " maxAdj=" + app.maxAdj);
        if (adj > app.maxAdj) {
            adj = app.maxAdj;
            if (app.maxAdj <= ProcessList.PERCEPTIBLE_APP_ADJ) {
                schedGroup = Process.THREAD_GROUP_DEFAULT;
            }
        }
        if (adj < ProcessList.HIDDEN_APP_MIN_ADJ) {
            app.keeping = true;
        }

        if (app.hasAboveClient) {
            // If this process has bound to any services with BIND_ABOVE_CLIENT,
            // then we need to drop its adjustment to be lower than the service's
            // in order to honor the request.  We want to drop it by one adjustment
            // level...  but there is special meaning applied to various levels so
            // we will skip some of them.
            if (adj < ProcessList.FOREGROUND_APP_ADJ) {
                // System process will not get dropped, ever
            } else if (adj < ProcessList.VISIBLE_APP_ADJ) {
                adj = ProcessList.VISIBLE_APP_ADJ;
            } else if (adj < ProcessList.PERCEPTIBLE_APP_ADJ) {
                adj = ProcessList.PERCEPTIBLE_APP_ADJ;
            } else if (adj < ProcessList.HIDDEN_APP_MIN_ADJ) {
                adj = ProcessList.HIDDEN_APP_MIN_ADJ;
            } else if (adj < ProcessList.EMPTY_APP_ADJ) {
                adj++;
            }
        }

        app.curAdj = adj;
        app.curSchedGroup = schedGroup;

        if (hadForegroundActivities != app.foregroundActivities) {
            mHandler.obtainMessage(DISPATCH_FOREGROUND_ACTIVITIES_CHANGED, app.pid, app.info.uid,
                    app.foregroundActivities).sendToTarget();
        }

        return app.curRawAdj;
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
        return mParallelBroadcasts.size() == 0
                && mOrderedBroadcasts.size() == 0
                && (mSleeping || (mMainStack.mResumedActivity != null &&
                        mMainStack.mResumedActivity.idle));
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
                if (proc.curRawAdj > ProcessList.PERCEPTIBLE_APP_ADJ || proc.reportLowMemory) {
                    if ((proc.lastRequestedGc+GC_MIN_INTERVAL)
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
            
            long when = proc.lastRequestedGc + GC_MIN_INTERVAL;
            long now = SystemClock.uptimeMillis();
            if (when < (now+GC_TIMEOUT)) {
                when = now + GC_TIMEOUT;
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
        if ((app.lastRequestedGc+GC_MIN_INTERVAL) > now) {
            return;
        }
        if (!mProcessesToGc.contains(app)) {
            addProcessToGcListLocked(app);
            scheduleAppGcsLocked();
        }
    }

    final void checkExcessivePowerUsageLocked(boolean doKills) {
        updateCpuStatsNow();

        BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
        boolean doWakeKills = doKills;
        boolean doCpuKills = doKills;
        if (mLastPowerCheckRealtime == 0) {
            doWakeKills = false;
        }
        if (mLastPowerCheckUptime == 0) {
            doCpuKills = false;
        }
        if (stats.isScreenOn()) {
            doWakeKills = false;
        }
        final long curRealtime = SystemClock.elapsedRealtime();
        final long realtimeSince = curRealtime - mLastPowerCheckRealtime;
        final long curUptime = SystemClock.uptimeMillis();
        final long uptimeSince = curUptime - mLastPowerCheckUptime;
        mLastPowerCheckRealtime = curRealtime;
        mLastPowerCheckUptime = curUptime;
        if (realtimeSince < WAKE_LOCK_MIN_CHECK_DURATION) {
            doWakeKills = false;
        }
        if (uptimeSince < CPU_MIN_CHECK_DURATION) {
            doCpuKills = false;
        }
        int i = mLruProcesses.size();
        while (i > 0) {
            i--;
            ProcessRecord app = mLruProcesses.get(i);
            if (!app.keeping) {
                long wtime;
                synchronized (stats) {
                    wtime = stats.getProcessWakeTime(app.info.uid,
                            app.pid, curRealtime);
                }
                long wtimeUsed = wtime - app.lastWakeTime;
                long cputimeUsed = app.curCpuTime - app.lastCpuTime;
                if (DEBUG_POWER) {
                    StringBuilder sb = new StringBuilder(128);
                    sb.append("Wake for ");
                    app.toShortString(sb);
                    sb.append(": over ");
                    TimeUtils.formatDuration(realtimeSince, sb);
                    sb.append(" used ");
                    TimeUtils.formatDuration(wtimeUsed, sb);
                    sb.append(" (");
                    sb.append((wtimeUsed*100)/realtimeSince);
                    sb.append("%)");
                    Slog.i(TAG, sb.toString());
                    sb.setLength(0);
                    sb.append("CPU for ");
                    app.toShortString(sb);
                    sb.append(": over ");
                    TimeUtils.formatDuration(uptimeSince, sb);
                    sb.append(" used ");
                    TimeUtils.formatDuration(cputimeUsed, sb);
                    sb.append(" (");
                    sb.append((cputimeUsed*100)/uptimeSince);
                    sb.append("%)");
                    Slog.i(TAG, sb.toString());
                }
                // If a process has held a wake lock for more
                // than 50% of the time during this period,
                // that sounds pad.  Kill!
                if (doWakeKills && realtimeSince > 0
                        && ((wtimeUsed*100)/realtimeSince) >= 50) {
                    synchronized (stats) {
                        stats.reportExcessiveWakeLocked(app.info.uid, app.processName,
                                realtimeSince, wtimeUsed);
                    }
                    Slog.w(TAG, "Excessive wake lock in " + app.processName
                            + " (pid " + app.pid + "): held " + wtimeUsed
                            + " during " + realtimeSince);
                    EventLog.writeEvent(EventLogTags.AM_KILL, app.pid,
                            app.processName, app.setAdj, "excessive wake lock");
                    Process.killProcessQuiet(app.pid);
                } else if (doCpuKills && uptimeSince > 0
                        && ((cputimeUsed*100)/uptimeSince) >= 50) {
                    synchronized (stats) {
                        stats.reportExcessiveCpuLocked(app.info.uid, app.processName,
                                uptimeSince, cputimeUsed);
                    }
                    Slog.w(TAG, "Excessive CPU in " + app.processName
                            + " (pid " + app.pid + "): used " + cputimeUsed
                            + " during " + uptimeSince);
                    EventLog.writeEvent(EventLogTags.AM_KILL, app.pid,
                            app.processName, app.setAdj, "excessive cpu");
                    Process.killProcessQuiet(app.pid);
                } else {
                    app.lastWakeTime = wtime;
                    app.lastCpuTime = app.curCpuTime;
                }
            }
        }
    }

    private final void updateOomAdjLocked(
            ProcessRecord app, int hiddenAdj, ProcessRecord TOP_APP) {
        app.hiddenAdj = hiddenAdj;

        if (app.thread == null) {
            return;
        }

        final boolean wasKeeping = app.keeping;

        computeOomAdjLocked(app, hiddenAdj, TOP_APP, false);

        if (app.curRawAdj != app.setRawAdj) {
            if (app.curRawAdj > ProcessList.FOREGROUND_APP_ADJ
                    && app.setRawAdj <= ProcessList.FOREGROUND_APP_ADJ) {
                // If this app is transitioning from foreground to
                // non-foreground, have it do a gc.
                scheduleAppGcLocked(app);
            } else if (app.curRawAdj >= ProcessList.HIDDEN_APP_MIN_ADJ
                    && app.setRawAdj < ProcessList.HIDDEN_APP_MIN_ADJ) {
                // Likewise do a gc when an app is moving in to the
                // background (such as a service stopping).
                scheduleAppGcLocked(app);
            }

            if (wasKeeping && !app.keeping) {
                // This app is no longer something we want to keep.  Note
                // its current wake lock time to later know to kill it if
                // it is not behaving well.
                BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
                synchronized (stats) {
                    app.lastWakeTime = stats.getProcessWakeTime(app.info.uid,
                            app.pid, SystemClock.elapsedRealtime());
                }
                app.lastCpuTime = app.curCpuTime;
            }

            app.setRawAdj = app.curRawAdj;
        }
        if (app.curAdj != app.setAdj) {
            if (Process.setOomAdj(app.pid, app.curAdj)) {
                if (DEBUG_SWITCH || DEBUG_OOM_ADJ) Slog.v(
                    TAG, "Set app " + app.processName +
                    " oom adj to " + app.curAdj + " because " + app.adjType);
                app.setAdj = app.curAdj;
            } else {
                Slog.w(TAG, "Failed setting oom adj of " + app + " to " + app.curAdj);
            }
        }
        if (app.setSchedGroup != app.curSchedGroup) {
            app.setSchedGroup = app.curSchedGroup;
            if (DEBUG_SWITCH || DEBUG_OOM_ADJ) Slog.v(TAG,
                    "Setting process group of " + app.processName
                    + " to " + app.curSchedGroup);
            if (app.waitingToKill != null &&
                    app.setSchedGroup == Process.THREAD_GROUP_BG_NONINTERACTIVE) {
                Slog.i(TAG, "Killing " + app.toShortString() + ": " + app.waitingToKill);
                EventLog.writeEvent(EventLogTags.AM_KILL, app.pid,
                        app.processName, app.setAdj, app.waitingToKill);
                Process.killProcessQuiet(app.pid);
            } else {
                if (true) {
                    long oldId = Binder.clearCallingIdentity();
                    try {
                        Process.setProcessGroup(app.pid, app.curSchedGroup);
                    } catch (Exception e) {
                        Slog.w(TAG, "Failed setting process group of " + app.pid
                                + " to " + app.curSchedGroup);
                        e.printStackTrace();
                    } finally {
                        Binder.restoreCallingIdentity(oldId);
                    }
                } else {
                    if (app.thread != null) {
                        try {
                            app.thread.setSchedulingGroup(app.curSchedGroup);
                        } catch (RemoteException e) {
                        }
                    }
                }
            }
        }
    }

    private final ActivityRecord resumedAppLocked() {
        ActivityRecord resumedActivity = mMainStack.mResumedActivity;
        if (resumedActivity == null || resumedActivity.app == null) {
            resumedActivity = mMainStack.mPausingActivity;
            if (resumedActivity == null || resumedActivity.app == null) {
                resumedActivity = mMainStack.topRunningActivityLocked(null);
            }
        }
        return resumedActivity;
    }

    private final void updateOomAdjLocked(ProcessRecord app) {
        final ActivityRecord TOP_ACT = resumedAppLocked();
        final ProcessRecord TOP_APP = TOP_ACT != null ? TOP_ACT.app : null;
        int curAdj = app.curAdj;
        final boolean wasHidden = curAdj >= ProcessList.HIDDEN_APP_MIN_ADJ
            && curAdj <= ProcessList.HIDDEN_APP_MAX_ADJ;

        mAdjSeq++;

        updateOomAdjLocked(app, app.hiddenAdj, TOP_APP);
        final boolean nowHidden = app.curAdj >= ProcessList.HIDDEN_APP_MIN_ADJ
            && app.curAdj <= ProcessList.HIDDEN_APP_MAX_ADJ;
        if (nowHidden != wasHidden) {
            // Changed to/from hidden state, so apps after it in the LRU
            // list may also be changed.
            updateOomAdjLocked();
        }
    }

    final void updateOomAdjLocked() {
        final ActivityRecord TOP_ACT = resumedAppLocked();
        final ProcessRecord TOP_APP = TOP_ACT != null ? TOP_ACT.app : null;

        if (false) {
            RuntimeException e = new RuntimeException();
            e.fillInStackTrace();
            Slog.i(TAG, "updateOomAdj: top=" + TOP_ACT, e);
        }

        mAdjSeq++;

        // Let's determine how many processes we have running vs.
        // how many slots we have for background processes; we may want
        // to put multiple processes in a slot of there are enough of
        // them.
        int numSlots = ProcessList.HIDDEN_APP_MAX_ADJ - ProcessList.HIDDEN_APP_MIN_ADJ + 1;
        int factor = (mLruProcesses.size()-4)/numSlots;
        if (factor < 1) factor = 1;
        int step = 0;
        int numHidden = 0;
        
        // First update the OOM adjustment for each of the
        // application processes based on their current state.
        int i = mLruProcesses.size();
        int curHiddenAdj = ProcessList.HIDDEN_APP_MIN_ADJ;
        int numBg = 0;
        while (i > 0) {
            i--;
            ProcessRecord app = mLruProcesses.get(i);
            //Slog.i(TAG, "OOM " + app + ": cur hidden=" + curHiddenAdj);
            updateOomAdjLocked(app, curHiddenAdj, TOP_APP);
            if (curHiddenAdj < ProcessList.EMPTY_APP_ADJ
                && app.curAdj == curHiddenAdj) {
                step++;
                if (step >= factor) {
                    step = 0;
                    curHiddenAdj++;
                }
            }
            if (!app.killedBackground) {
                if (app.curAdj >= ProcessList.HIDDEN_APP_MIN_ADJ) {
                    numHidden++;
                    if (numHidden > mProcessLimit) {
                        Slog.i(TAG, "No longer want " + app.processName
                                + " (pid " + app.pid + "): hidden #" + numHidden);
                        EventLog.writeEvent(EventLogTags.AM_KILL, app.pid,
                                app.processName, app.setAdj, "too many background");
                        app.killedBackground = true;
                        Process.killProcessQuiet(app.pid);
                    } else {
                        numBg++;
                    }
                } else if (app.curAdj >= ProcessList.HOME_APP_ADJ) {
                    numBg++;
                }
            }
        }

        // Now determine the memory trimming level of background processes.
        // Unfortunately we need to start at the back of the list to do this
        // properly.  We only do this if the number of background apps we
        // are managing to keep around is less than half the maximum we desire;
        // if we are keeping a good number around, we'll let them use whatever
        // memory they want.
        if (numHidden <= (ProcessList.MAX_HIDDEN_APPS/2)) {
            final int N = mLruProcesses.size();
            factor = numBg/3;
            step = 0;
            int curLevel = ComponentCallbacks2.TRIM_MEMORY_COMPLETE;
            for (i=0; i<N; i++) {
                ProcessRecord app = mLruProcesses.get(i);
                if (app.curAdj >= ProcessList.HIDDEN_APP_MIN_ADJ && !app.killedBackground) {
                    if (app.trimMemoryLevel < curLevel && app.thread != null) {
                        try {
                            app.thread.scheduleTrimMemory(curLevel);
                        } catch (RemoteException e) {
                        }
                        if (curLevel >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                            // For these apps we will also finish their activities
                            // to help them free memory.
                            mMainStack.destroyActivitiesLocked(app, false);
                        }
                    }
                    app.trimMemoryLevel = curLevel;
                    step++;
                    if (step >= factor) {
                        switch (curLevel) {
                            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                                curLevel = ComponentCallbacks2.TRIM_MEMORY_MODERATE;
                                break;
                            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                                curLevel = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND;
                                break;
                        }
                    }
                } else if (app.curAdj == ProcessList.HEAVY_WEIGHT_APP_ADJ) {
                    if (app.trimMemoryLevel < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                            && app.thread != null) {
                        try {
                            app.thread.scheduleTrimMemory(
                                    ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
                        } catch (RemoteException e) {
                        }
                    }
                    app.trimMemoryLevel = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND;
                } else if ((app.curAdj > ProcessList.VISIBLE_APP_ADJ || app.systemNoUi)
                        && app.pendingUiClean) {
                    if (app.trimMemoryLevel < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                            && app.thread != null) {
                        try {
                            app.thread.scheduleTrimMemory(
                                    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
                        } catch (RemoteException e) {
                        }
                    }
                    app.trimMemoryLevel = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN;
                    app.pendingUiClean = false;
                } else {
                    app.trimMemoryLevel = 0;
                }
            }
        } else {
            final int N = mLruProcesses.size();
            for (i=0; i<N; i++) {
                ProcessRecord app = mLruProcesses.get(i);
                if ((app.curAdj > ProcessList.VISIBLE_APP_ADJ || app.systemNoUi)
                        && app.pendingUiClean) {
                    if (app.trimMemoryLevel < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                            && app.thread != null) {
                        try {
                            app.thread.scheduleTrimMemory(
                                    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
                        } catch (RemoteException e) {
                        }
                    }
                    app.trimMemoryLevel = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN;
                    app.pendingUiClean = false;
                } else {
                    app.trimMemoryLevel = 0;
                }
            }
        }

        if (mAlwaysFinishActivities) {
            mMainStack.destroyActivitiesLocked(null, false);
        }
    }

    final void trimApplications() {
        synchronized (this) {
            int i;

            // First remove any unused application processes whose package
            // has been removed.
            for (i=mRemovedProcesses.size()-1; i>=0; i--) {
                final ProcessRecord app = mRemovedProcesses.get(i);
                if (app.activities.size() == 0
                        && app.curReceiver == null && app.services.size() == 0) {
                    Slog.i(
                        TAG, "Exiting empty application process "
                        + app.processName + " ("
                        + (app.thread != null ? app.thread.asBinder() : null)
                        + ")\n");
                    if (app.pid > 0 && app.pid != MY_PID) {
                        EventLog.writeEvent(EventLogTags.AM_KILL, app.pid,
                                app.processName, app.setAdj, "empty");
                        Process.killProcessQuiet(app.pid);
                    } else {
                        try {
                            app.thread.scheduleExit();
                        } catch (Exception e) {
                            // Ignore exceptions.
                        }
                    }
                    cleanUpApplicationRecordLocked(app, false, true, -1);
                    mRemovedProcesses.remove(i);
                    
                    if (app.persistent) {
                        if (app.persistent) {
                            addAppLocked(app.info);
                        }
                    }
                }
            }

            // Now update the oom adj for all processes.
            updateOomAdjLocked();
        }
    }

    /** This method sends the specified signal to each of the persistent apps */
    public void signalPersistentProcesses(int sig) throws RemoteException {
        if (sig != Process.SIGNAL_USR1) {
            throw new SecurityException("Only SIGNAL_USR1 is allowed");
        }

        synchronized (this) {
            if (checkCallingPermission(android.Manifest.permission.SIGNAL_PERSISTENT_PROCESSES)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires permission "
                        + android.Manifest.permission.SIGNAL_PERSISTENT_PROCESSES);
            }

            for (int i = mLruProcesses.size() - 1 ; i >= 0 ; i--) {
                ProcessRecord r = mLruProcesses.get(i);
                if (r.thread != null && r.persistent) {
                    Process.sendSignal(r.pid, sig);
                }
            }
        }
    }

    public boolean profileControl(String process, boolean start,
            String path, ParcelFileDescriptor fd, int profileType) throws RemoteException {

        try {
            synchronized (this) {
                // note: hijacking SET_ACTIVITY_WATCHER, but should be changed to
                // its own permission.
                if (checkCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER)
                        != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Requires permission "
                            + android.Manifest.permission.SET_ACTIVITY_WATCHER);
                }
                
                if (start && fd == null) {
                    throw new IllegalArgumentException("null fd");
                }
                
                ProcessRecord proc = null;
                try {
                    int pid = Integer.parseInt(process);
                    synchronized (mPidsSelfLocked) {
                        proc = mPidsSelfLocked.get(pid);
                    }
                } catch (NumberFormatException e) {
                }
                
                if (proc == null) {
                    HashMap<String, SparseArray<ProcessRecord>> all
                            = mProcessNames.getMap();
                    SparseArray<ProcessRecord> procs = all.get(process);
                    if (procs != null && procs.size() > 0) {
                        proc = procs.valueAt(0);
                    }
                }
                
                if (proc == null || proc.thread == null) {
                    throw new IllegalArgumentException("Unknown process: " + process);
                }
                
                boolean isDebuggable = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
                if (!isDebuggable) {
                    if ((proc.info.flags&ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
                        throw new SecurityException("Process not debuggable: " + proc);
                    }
                }
            
                proc.thread.profilerControl(start, path, fd, profileType);
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

    public boolean dumpHeap(String process, boolean managed,
            String path, ParcelFileDescriptor fd) throws RemoteException {

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

                ProcessRecord proc = null;
                try {
                    int pid = Integer.parseInt(process);
                    synchronized (mPidsSelfLocked) {
                        proc = mPidsSelfLocked.get(pid);
                    }
                } catch (NumberFormatException e) {
                }

                if (proc == null) {
                    HashMap<String, SparseArray<ProcessRecord>> all
                            = mProcessNames.getMap();
                    SparseArray<ProcessRecord> procs = all.get(process);
                    if (procs != null && procs.size() > 0) {
                        proc = procs.valueAt(0);
                    }
                }

                if (proc == null || proc.thread == null) {
                    throw new IllegalArgumentException("Unknown process: " + process);
                }

                boolean isDebuggable = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
                if (!isDebuggable) {
                    if ((proc.info.flags&ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
                        throw new SecurityException("Process not debuggable: " + proc);
                    }
                }

                proc.thread.dumpHeap(managed, path, fd);
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

    /** In this method we try to acquire our lock to make sure that we have not deadlocked */
    public void monitor() {
        synchronized (this) { }
    }

    public void onCoreSettingsChange(Bundle settings) {
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord processRecord = mLruProcesses.get(i);
            try {
                if (processRecord.thread != null) {
                    processRecord.thread.setCoreSettings(settings);
                }
            } catch (RemoteException re) {
                /* ignore */
            }
        }
    }

    // Multi-user methods

    public boolean switchUser(int userid) {
        // TODO
        return true;
    }
}
