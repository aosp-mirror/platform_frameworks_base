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

import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.RuntimeInit;
import com.android.server.IntentResolver;
import com.android.server.ProcessMap;
import com.android.server.ProcessStats;
import com.android.server.SystemServer;
import com.android.server.Watchdog;
import com.android.server.WindowManagerService;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.ActivityThread;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.IActivityWatcher;
import android.app.IApplicationThread;
import android.app.IInstrumentationWatcher;
import android.app.IIntentReceiver;
import android.app.IIntentSender;
import android.app.IServiceConnection;
import android.app.IThumbnailReceiver;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.app.ResultInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageManager;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPermissionController;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Checkin;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Config;
import android.util.EventLog;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;

import dalvik.system.Zygote;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.lang.IllegalStateException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ActivityManagerService extends ActivityManagerNative implements Watchdog.Monitor {
    static final String TAG = "ActivityManager";
    static final boolean DEBUG = false;
    static final boolean localLOGV = DEBUG ? Config.LOGD : Config.LOGV;
    static final boolean DEBUG_SWITCH = localLOGV || false;
    static final boolean DEBUG_TASKS = localLOGV || false;
    static final boolean DEBUG_PAUSE = localLOGV || false;
    static final boolean DEBUG_OOM_ADJ = localLOGV || false;
    static final boolean DEBUG_TRANSITION = localLOGV || false;
    static final boolean DEBUG_BROADCAST = localLOGV || false;
    static final boolean DEBUG_SERVICE = localLOGV || false;
    static final boolean DEBUG_VISBILITY = localLOGV || false;
    static final boolean DEBUG_PROCESSES = localLOGV || false;
    static final boolean DEBUG_USER_LEAVING = localLOGV || false;
    static final boolean DEBUG_RESULTS = localLOGV || false;
    static final boolean VALIDATE_TOKENS = false;
    static final boolean SHOW_ACTIVITY_START_TIME = true;
    
    // Control over CPU and battery monitoring.
    static final long BATTERY_STATS_TIME = 30*60*1000;      // write battery stats every 30 minutes.
    static final boolean MONITOR_CPU_USAGE = true;
    static final long MONITOR_CPU_MIN_TIME = 5*1000;        // don't sample cpu less than every 5 seconds.
    static final long MONITOR_CPU_MAX_TIME = 0x0fffffff;    // wait possibly forever for next cpu sample.
    static final boolean MONITOR_THREAD_CPU_USAGE = false;

    // Event log tags
    static final int LOG_CONFIGURATION_CHANGED = 2719;
    static final int LOG_CPU = 2721;
    static final int LOG_AM_FINISH_ACTIVITY = 30001;
    static final int LOG_TASK_TO_FRONT = 30002;
    static final int LOG_AM_NEW_INTENT = 30003;
    static final int LOG_AM_CREATE_TASK = 30004;
    static final int LOG_AM_CREATE_ACTIVITY = 30005;
    static final int LOG_AM_RESTART_ACTIVITY = 30006;
    static final int LOG_AM_RESUME_ACTIVITY = 30007;
    static final int LOG_ANR = 30008;
    static final int LOG_ACTIVITY_LAUNCH_TIME = 30009;
    static final int LOG_AM_PROCESS_BOUND = 30010;
    static final int LOG_AM_PROCESS_DIED = 30011;
    static final int LOG_AM_FAILED_TO_PAUSE_ACTIVITY = 30012;
    static final int LOG_AM_PAUSE_ACTIVITY = 30013;
    static final int LOG_AM_PROCESS_START = 30014;
    static final int LOG_AM_PROCESS_BAD = 30015;
    static final int LOG_AM_PROCESS_GOOD = 30016;
    static final int LOG_AM_LOW_MEMORY = 30017;
    static final int LOG_AM_DESTROY_ACTIVITY = 30018;
    static final int LOG_AM_RELAUNCH_RESUME_ACTIVITY = 30019;
    static final int LOG_AM_RELAUNCH_ACTIVITY = 30020;
    static final int LOG_AM_KILL_FOR_MEMORY = 30023;
    static final int LOG_AM_BROADCAST_DISCARD_FILTER = 30024;
    static final int LOG_AM_BROADCAST_DISCARD_APP = 30025;
    static final int LOG_AM_CREATE_SERVICE = 30030;
    static final int LOG_AM_DESTROY_SERVICE = 30031;
    static final int LOG_AM_PROCESS_CRASHED_TOO_MUCH = 30032;
    static final int LOG_AM_DROP_PROCESS = 30033;
    static final int LOG_AM_SERVICE_CRASHED_TOO_MUCH = 30034;
    static final int LOG_AM_SCHEDULE_SERVICE_RESTART = 30035;
    static final int LOG_AM_PROVIDER_LOST_PROCESS = 30036;
    
    static final int LOG_BOOT_PROGRESS_AMS_READY = 3040;
    static final int LOG_BOOT_PROGRESS_ENABLE_SCREEN = 3050;

    private static final String SYSTEM_SECURE = "ro.secure";

    // This is the maximum number of application processes we would like
    // to have running.  Due to the asynchronous nature of things, we can
    // temporarily go beyond this limit.
    static final int MAX_PROCESSES = 2;

    // Set to false to leave processes running indefinitely, relying on
    // the kernel killing them as resources are required.
    static final boolean ENFORCE_PROCESS_LIMIT = false;

    // This is the maximum number of activities that we would like to have
    // running at a given time.
    static final int MAX_ACTIVITIES = 20;

    // Maximum number of recent tasks that we can remember.
    static final int MAX_RECENT_TASKS = 20;

    // How long until we reset a task when the user returns to it.  Currently
    // 30 minutes.
    static final long ACTIVITY_INACTIVE_RESET_TIME = 1000*60*30;
    
    // Set to true to disable the icon that is shown while a new activity
    // is being started.
    static final boolean SHOW_APP_STARTING_ICON = true;

    // How long we wait until giving up on the last activity to pause.  This
    // is short because it directly impacts the responsiveness of starting the
    // next activity.
    static final int PAUSE_TIMEOUT = 500;

    /**
     * How long we can hold the launch wake lock before giving up.
     */
    static final int LAUNCH_TIMEOUT = 10*1000;

    // How long we wait for a launched process to attach to the activity manager
    // before we decide it's never going to come up for real.
    static final int PROC_START_TIMEOUT = 10*1000;

    // How long we wait until giving up on the last activity telling us it
    // is idle.
    static final int IDLE_TIMEOUT = 10*1000;

    // How long to wait after going idle before forcing apps to GC.
    static final int GC_TIMEOUT = 5*1000;

    // How long we wait until giving up on an activity telling us it has
    // finished destroying itself.
    static final int DESTROY_TIMEOUT = 10*1000;
    
    // How long we allow a receiver to run before giving up on it.
    static final int BROADCAST_TIMEOUT = 10*1000;

    // How long we wait for a service to finish executing.
    static final int SERVICE_TIMEOUT = 20*1000;

    // How long a service needs to be running until restarting its process
    // is no longer considered to be a relaunch of the service.
    static final int SERVICE_RESTART_DURATION = 5*1000;

    // Maximum amount of time for there to be no activity on a service before
    // we consider it non-essential and allow its process to go on the
    // LRU background list.
    static final int MAX_SERVICE_INACTIVITY = 10*60*1000;
    
    // How long we wait until we timeout on key dispatching.
    static final int KEY_DISPATCHING_TIMEOUT = 5*1000;

    // The minimum time we allow between crashes, for us to consider this
    // application to be bad and stop and its services and reject broadcasts.
    static final int MIN_CRASH_INTERVAL = 60*1000;

    // How long we wait until we timeout on key dispatching during instrumentation.
    static final int INSTRUMENTATION_KEY_DISPATCHING_TIMEOUT = 60*1000;

    // OOM adjustments for processes in various states:

    // This is a process without anything currently running in it.  Definitely
    // the first to go! Value set in system/rootdir/init.rc on startup.
    // This value is initalized in the constructor, careful when refering to
    // this static variable externally.
    static int EMPTY_APP_ADJ;

    // This is a process with a content provider that does not have any clients
    // attached to it.  If it did have any clients, its adjustment would be the
    // one for the highest-priority of those processes.
    static int CONTENT_PROVIDER_ADJ;

    // This is a process only hosting activities that are not visible,
    // so it can be killed without any disruption. Value set in
    // system/rootdir/init.rc on startup.
    final int HIDDEN_APP_MAX_ADJ;
    static int HIDDEN_APP_MIN_ADJ;

    // This is a process holding the home application -- we want to try
    // avoiding killing it, even if it would normally be in the background,
    // because the user interacts with it so much.
    final int HOME_APP_ADJ;

    // This is a process holding a secondary server -- killing it will not
    // have much of an impact as far as the user is concerned. Value set in
    // system/rootdir/init.rc on startup.
    final int SECONDARY_SERVER_ADJ;

    // This is a process only hosting activities that are visible to the
    // user, so we'd prefer they don't disappear. Value set in
    // system/rootdir/init.rc on startup.
    final int VISIBLE_APP_ADJ;

    // This is the process running the current foreground app.  We'd really
    // rather not kill it! Value set in system/rootdir/init.rc on startup.
    final int FOREGROUND_APP_ADJ;

    // This is a process running a core server, such as telephony.  Definitely
    // don't want to kill it, but doing so is not completely fatal.
    static final int CORE_SERVER_ADJ = -12;

    // The system process runs at the default adjustment.
    static final int SYSTEM_ADJ = -16;

    // Memory pages are 4K.
    static final int PAGE_SIZE = 4*1024;
    
    // Corresponding memory levels for above adjustments.
    final int EMPTY_APP_MEM;
    final int HIDDEN_APP_MEM;
    final int HOME_APP_MEM;
    final int SECONDARY_SERVER_MEM;
    final int VISIBLE_APP_MEM;
    final int FOREGROUND_APP_MEM;
    
    final int MY_PID;
    
    static final String[] EMPTY_STRING_ARRAY = new String[0];

    enum ActivityState {
        INITIALIZING,
        RESUMED,
        PAUSING,
        PAUSED,
        STOPPING,
        STOPPED,
        FINISHING,
        DESTROYING,
        DESTROYED
    }

    /**
     * The back history of all previous (and possibly still
     * running) activities.  It contains HistoryRecord objects.
     */
    final ArrayList mHistory = new ArrayList();

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
     * Set when we current have a BROADCAST_INTENT_MSG in flight.
     */
    boolean mBroadcastsScheduled = false;

    /**
     * Set to indicate whether to issue an onUserLeaving callback when a
     * newly launched activity is being brought in front of us.
     */
    boolean mUserLeaving = false;

    /**
     * When we are in the process of pausing an activity, before starting the
     * next one, this variable holds the activity that is currently being paused.
     */
    HistoryRecord mPausingActivity = null;

    /**
     * Current activity that is resumed, or null if there is none.
     */
    HistoryRecord mResumedActivity = null;

    /**
     * Activity we have told the window manager to have key focus.
     */
    HistoryRecord mFocusedActivity = null;

    /**
     * This is the last activity that we put into the paused state.  This is
     * used to determine if we need to do an activity transition while sleeping,
     * when we normally hold the top activity paused.
     */
    HistoryRecord mLastPausedActivity = null;

    /**
     * List of activities that are waiting for a new activity
     * to become visible before completing whatever operation they are
     * supposed to do.
     */
    final ArrayList mWaitingVisibleActivities = new ArrayList();

    /**
     * List of activities that are ready to be stopped, but waiting
     * for the next activity to settle down before doing so.  It contains
     * HistoryRecord objects.
     */
    final ArrayList<HistoryRecord> mStoppingActivities
            = new ArrayList<HistoryRecord>();

    /**
     * List of intents that were used to start the most recent tasks.
     */
    final ArrayList<TaskRecord> mRecentTasks
            = new ArrayList<TaskRecord>();

    /**
     * List of activities that are ready to be finished, but waiting
     * for the previous activity to settle down before doing so.  It contains
     * HistoryRecord objects.
     */
    final ArrayList mFinishingActivities = new ArrayList();

    /**
     * All of the applications we currently have running organized by name.
     * The keys are strings of the application package name (as
     * returned by the package manager), and the keys are ApplicationRecord
     * objects.
     */
    final ProcessMap<ProcessRecord> mProcessNames
            = new ProcessMap<ProcessRecord>();

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
    final SparseArray<ProcessRecord> mPidsSelfLocked
            = new SparseArray<ProcessRecord>();

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
     * List of records for processes that we have started and are waiting
     * for them to call back.  This is really only needed when running in
     * single processes mode, in which case we do not have a unique pid for
     * each process.
     */
    final ArrayList<ProcessRecord> mStartingProcesses
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
    final ArrayList<ProcessRecord> mLRUProcesses
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
    private ProcessRecord mHomeProcess;
    
    /**
     * List of running activities, sorted by recent usage.
     * The first entry in the list is the least recently used.
     * It contains HistoryRecord objects.
     */
    private final ArrayList mLRUActivities = new ArrayList();

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
     * Intent broadcast that we have tried to start, but are
     * waiting for its application's process to be created.  We only
     * need one (instead of a list) because we always process broadcasts
     * one at a time, so no others can be started while waiting for this
     * one.
     */
    BroadcastRecord mPendingBroadcast = null;

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
    final HashMap<IBinder, ConnectionRecord> mServiceConnections
            = new HashMap<IBinder, ConnectionRecord>();

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
    final HashMap mProvidersByName = new HashMap();

    /**
     * All of the currently running global content providers.  Keys are a
     * string containing the provider's implementation class and values are a
     * ContentProviderRecord object containing the data about it.
     */
    final HashMap mProvidersByClass = new HashMap();

    /**
     * List of content providers who have clients waiting for them.  The
     * application is currently being launched and the provider will be
     * removed from this list once it is published.
     */
    final ArrayList mLaunchingProviders = new ArrayList();

    /**
     * Global set of specific Uri permissions that have been granted.
     */
    final private SparseArray<HashMap<Uri, UriPermission>> mGrantedUriPermissions
            = new SparseArray<HashMap<Uri, UriPermission>>();

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
     * List of initialization arguments to pass to all processes when binding applications to them.
     * For example, references to the commonly used services.
     */
    HashMap<String, IBinder> mAppBindArgs;

    /**
     * Used to control how we initialize the service.
     */
    boolean mStartRunning = false;
    ComponentName mTopComponent;
    String mTopAction;
    String mTopData;
    boolean mSystemReady = false;
    boolean mBooting = false;

    Context mContext;

    int mFactoryTest;

    /**
     * Set while we are wanting to sleep, to prevent any
     * activities from being started/resumed.
     */
    boolean mSleeping = false;

    /**
     * Set when the system is going to sleep, until we have
     * successfully paused the current activity and released our wake lock.
     * At that point the system is allowed to actually sleep.
     */
    PowerManager.WakeLock mGoingToSleep;

    /**
     * We don't want to allow the device to go to sleep while in the process
     * of launching an activity.  This is primarily to allow alarm intent
     * receivers to launch an activity and get that to run before the device
     * goes back to sleep.
     */
    PowerManager.WakeLock mLaunchingActivity;

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
     * Set to true if the ANDROID_SIMPLE_PROCESS_MANAGEMENT envvar
     * is set, indicating the user wants processes started in such a way
     * that they can use ANDROID_PROCESS_WRAPPER and know what will be
     * running in each process (thus no pre-initialized process, etc).
     */
    boolean mSimpleProcessManagement = false;

    /**
     * System monitoring: number of processes that died since the last
     * N procs were started.
     */
    int[] mProcDeaths = new int[20];
    
    String mDebugApp = null;
    boolean mWaitForDebugger = false;
    boolean mDebugTransient = false;
    String mOrigDebugApp = null;
    boolean mOrigWaitForDebugger = false;
    boolean mAlwaysFinishActivities = false;
    IActivityWatcher mWatcher = null;

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
    long mLastCpuTime = 0;
    long mLastWriteTime = 0;

    /**
     * Set to true after the system has finished booting.
     */
    boolean mBooted = false;

    int mProcessLimit = 0;

    WindowManagerService mWindowManager;

    static ActivityManagerService mSelf;
    static ActivityThread mSystemThread;

    private final class AppDeathRecipient implements IBinder.DeathRecipient {
        final ProcessRecord mApp;
        final int mPid;
        final IApplicationThread mAppThread;

        AppDeathRecipient(ProcessRecord app, int pid,
                IApplicationThread thread) {
            if (localLOGV) Log.v(
                TAG, "New death recipient " + this
                + " for thread " + thread.asBinder());
            mApp = app;
            mPid = pid;
            mAppThread = thread;
        }

        public void binderDied() {
            if (localLOGV) Log.v(
                TAG, "Death received in " + this
                + " for thread " + mAppThread.asBinder());
            removeRequestedPss(mApp);
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
    static final int PAUSE_TIMEOUT_MSG = 9;
    static final int IDLE_TIMEOUT_MSG = 10;
    static final int IDLE_NOW_MSG = 11;
    static final int SERVICE_TIMEOUT_MSG = 12;
    static final int UPDATE_TIME_ZONE = 13;
    static final int SHOW_UID_ERROR_MSG = 14;
    static final int IM_FEELING_LUCKY_MSG = 15;
    static final int LAUNCH_TIMEOUT_MSG = 16;
    static final int DESTROY_TIMEOUT_MSG = 17;
    static final int SERVICE_ERROR_MSG = 18;
    static final int RESUME_TOP_ACTIVITY_MSG = 19;
    static final int PROC_START_TIMEOUT_MSG = 20;

    AlertDialog mUidAlert;

    final Handler mHandler = new Handler() {
        //public Handler() {
        //    if (localLOGV) Log.v(TAG, "Handler started!");
        //}

        public void handleMessage(Message msg) {
            switch (msg.what) {
            case SHOW_ERROR_MSG: {
                HashMap data = (HashMap) msg.obj;
                byte[] crashData = (byte[])data.get("crashData");
                if (crashData != null) {
                    // This needs to be *un*synchronized to avoid deadlock.
                    ContentResolver resolver = mContext.getContentResolver();
                    Checkin.reportCrash(resolver, crashData);
                }
                synchronized (ActivityManagerService.this) {
                    ProcessRecord proc = (ProcessRecord)data.get("app");
                    if (proc != null && proc.crashDialog != null) {
                        Log.e(TAG, "App already has crash dialog: " + proc);
                        return;
                    }
                    AppErrorResult res = (AppErrorResult) data.get("result");
                    if (!mSleeping) {
                        Dialog d = new AppErrorDialog(
                                mContext, res, proc,
                                (Integer)data.get("flags"),
                                (String)data.get("shortMsg"),
                                (String)data.get("longMsg"));
                        d.show();
                        proc.crashDialog = d;
                    } else {
                        // The device is asleep, so just pretend that the user
                        // saw a crash dialog and hit "force quit".
                        res.set(0);
                    }
                }
            } break;
            case SHOW_NOT_RESPONDING_MSG: {
                synchronized (ActivityManagerService.this) {
                    HashMap data = (HashMap) msg.obj;
                    ProcessRecord proc = (ProcessRecord)data.get("app");
                    if (proc != null && proc.anrDialog != null) {
                        Log.e(TAG, "App already has anr dialog: " + proc);
                        return;
                    }
                    
                    broadcastIntentLocked(null, null, new Intent("android.intent.action.ANR"),
                            null, null, 0, null, null, null,
                            false, false, MY_PID, Process.SYSTEM_UID);

                    Dialog d = new AppNotRespondingDialog(ActivityManagerService.this,
                            mContext, proc, (HistoryRecord)data.get("activity"));
                    d.show();
                    proc.anrDialog = d;
                }
            } break;
            case SHOW_FACTORY_ERROR_MSG: {
                Dialog d = new FactoryErrorDialog(
                    mContext, msg.getData().getCharSequence("msg"));
                d.show();
                enableScreenAfterBoot();
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
                if (DEBUG_BROADCAST) Log.v(
                        TAG, "Received BROADCAST_INTENT_MSG");
                processNextBroadcast(true);
            } break;
            case BROADCAST_TIMEOUT_MSG: {
                broadcastTimeout();
            } break;
            case PAUSE_TIMEOUT_MSG: {
                IBinder token = (IBinder)msg.obj;
                // We don't at this point know if the activity is fullscreen,
                // so we need to be conservative and assume it isn't.
                Log.w(TAG, "Activity pause timeout for " + token);
                activityPaused(token, null, true);
            } break;
            case IDLE_TIMEOUT_MSG: {
                IBinder token = (IBinder)msg.obj;
                // We don't at this point know if the activity is fullscreen,
                // so we need to be conservative and assume it isn't.
                Log.w(TAG, "Activity idle timeout for " + token);
                activityIdleInternal(token, true);
            } break;
            case DESTROY_TIMEOUT_MSG: {
                IBinder token = (IBinder)msg.obj;
                // We don't at this point know if the activity is fullscreen,
                // so we need to be conservative and assume it isn't.
                Log.w(TAG, "Activity destroy timeout for " + token);
                activityDestroyed(token);
            } break;
            case IDLE_NOW_MSG: {
                IBinder token = (IBinder)msg.obj;
                activityIdle(token);
            } break;
            case SERVICE_TIMEOUT_MSG: {
                serviceTimeout((ProcessRecord)msg.obj);
            } break;
            case UPDATE_TIME_ZONE: {
                synchronized (ActivityManagerService.this) {
                    for (int i = mLRUProcesses.size() - 1 ; i >= 0 ; i--) {
                        ProcessRecord r = mLRUProcesses.get(i);
                        if (r.thread != null) {
                            try {
                                r.thread.updateTimeZone();
                            } catch (RemoteException ex) {
                                Log.w(TAG, "Failed to update time zone for: " + r.info.processName);
                            }
                        }
                    }
                }
                break;
            }
            case SHOW_UID_ERROR_MSG: {
                // XXX This is a temporary dialog, no need to localize.
                AlertDialog d = new BaseErrorDialog(mContext);
                d.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
                d.setCancelable(false);
                d.setTitle("System UIDs Inconsistent");
                d.setMessage("UIDs on the system are inconsistent, you need to wipe your data partition or your device will be unstable.");
                d.setButton("I'm Feeling Lucky",
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
            case LAUNCH_TIMEOUT_MSG: {
                synchronized (ActivityManagerService.this) {
                    if (mLaunchingActivity.isHeld()) {
                        Log.w(TAG, "Launch timeout has expired, giving up wake lock!");
                        mLaunchingActivity.release();
                    }
                }
            } break;
            case SERVICE_ERROR_MSG: {
                ServiceRecord srv = (ServiceRecord)msg.obj;
                // This needs to be *un*synchronized to avoid deadlock.
                Checkin.logEvent(mContext.getContentResolver(),
                        Checkin.Events.Tag.SYSTEM_SERVICE_LOOPING,
                        srv.name.toShortString());
            } break;
            case RESUME_TOP_ACTIVITY_MSG: {
                synchronized (ActivityManagerService.this) {
                    resumeTopActivityLocked(null);
                }
            }
            case PROC_START_TIMEOUT_MSG: {
                ProcessRecord app = (ProcessRecord)msg.obj;
                synchronized (ActivityManagerService.this) {
                    processStartTimedOutLocked(app);
                }
            }
            }
        }
    };

    public static void setSystemProcess() {
        try {
            ActivityManagerService m = mSelf;
            
            ServiceManager.addService("activity", m);
            ServiceManager.addService("meminfo", new MemBinder(m));
            if (MONITOR_CPU_USAGE) {
                ServiceManager.addService("cpuinfo", new CpuBinder(m));
            }
            ServiceManager.addService("activity.broadcasts", new BroadcastsBinder(m));
            ServiceManager.addService("activity.services", new ServicesBinder(m));
            ServiceManager.addService("activity.senders", new SendersBinder(m));
            ServiceManager.addService("activity.providers", new ProvidersBinder(m));
            ServiceManager.addService("permission", new PermissionController(m));

            ApplicationInfo info =
                mSelf.mContext.getPackageManager().getApplicationInfo(
                        "android", PackageManager.GET_SHARED_LIBRARY_FILES);
            synchronized (mSelf) {
                ProcessRecord app = mSelf.newProcessRecordLocked(
                        mSystemThread.getApplicationThread(), info,
                        info.processName);
                app.persistent = true;
                app.pid = Process.myPid();
                app.maxAdj = SYSTEM_ADJ;
                mSelf.mProcessNames.put(app.processName, app.info.uid, app);
                synchronized (mSelf.mPidsSelfLocked) {
                    mSelf.mPidsSelfLocked.put(app.pid, app);
                }
                mSelf.updateLRUListLocked(app, true);
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
        m.mContext = context;
        m.mFactoryTest = factoryTest;
        PowerManager pm =
            (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        m.mGoingToSleep = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ActivityManager-Sleep");
        m.mLaunchingActivity = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ActivityManager-Launch");
        m.mLaunchingActivity.setReferenceCounted(false);
        
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

            Looper.loop();
        }
    }

    static class BroadcastsBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        BroadcastsBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            mActivityManagerService.dumpBroadcasts(pw);
        }
    }

    static class ServicesBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        ServicesBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            mActivityManagerService.dumpServices(pw);
        }
    }

    static class SendersBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        SendersBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            mActivityManagerService.dumpSenders(pw);
        }
    }

    static class ProvidersBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        ProvidersBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            mActivityManagerService.dumpProviders(pw);
        }
    }

    static class MemBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        MemBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            ActivityManagerService service = mActivityManagerService;
            ArrayList<ProcessRecord> procs;
            synchronized (mActivityManagerService) {
                if (args != null && args.length > 0
                        && args[0].charAt(0) != '-') {
                    procs = new ArrayList<ProcessRecord>();
                    int pid = -1;
                    try {
                        pid = Integer.parseInt(args[0]);
                    } catch (NumberFormatException e) {
                        
                    }
                    for (int i=0; i<service.mLRUProcesses.size(); i++) {
                        ProcessRecord proc = service.mLRUProcesses.get(i);
                        if (proc.pid == pid) {
                            procs.add(proc);
                        } else if (proc.processName.equals(args[0])) {
                            procs.add(proc);
                        }
                    }
                    if (procs.size() <= 0) {
                        pw.println("No process found for: " + args[0]);
                        return;
                    }
                } else {
                    procs = service.mLRUProcesses;
                }
            }
            dumpApplicationMemoryUsage(fd, pw, procs, "  ", args);
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
                pw.print(mActivityManagerService.mProcessStats.printCurrentState());
            }
        }
    }

    private ActivityManagerService() {
        String v = System.getenv("ANDROID_SIMPLE_PROCESS_MANAGEMENT");
        if (v != null && Integer.getInteger(v) != 0) {
            mSimpleProcessManagement = true;
        }
        v = System.getenv("ANDROID_DEBUG_APP");
        if (v != null) {
            mSimpleProcessManagement = true;
        }

        MY_PID = Process.myPid();
        
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        systemDir.mkdirs();
        mBatteryStatsService = new BatteryStatsService(new File(
                systemDir, "batterystats.bin").toString());
        mBatteryStatsService.getActiveStatistics().readLocked();
        mBatteryStatsService.getActiveStatistics().writeLocked();
        
        mUsageStatsService = new UsageStatsService( new File(
                systemDir, "usagestats.bin").toString());

        mConfiguration.makeDefault();
        mProcessStats.init();
        
        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);

        // These values are set in system/rootdir/init.rc on startup.
        FOREGROUND_APP_ADJ =
            Integer.valueOf(SystemProperties.get("ro.FOREGROUND_APP_ADJ"));
        VISIBLE_APP_ADJ =
            Integer.valueOf(SystemProperties.get("ro.VISIBLE_APP_ADJ"));
        SECONDARY_SERVER_ADJ =
            Integer.valueOf(SystemProperties.get("ro.SECONDARY_SERVER_ADJ"));
        HOME_APP_ADJ =
            Integer.valueOf(SystemProperties.get("ro.HOME_APP_ADJ"));
        HIDDEN_APP_MIN_ADJ =
            Integer.valueOf(SystemProperties.get("ro.HIDDEN_APP_MIN_ADJ"));
        CONTENT_PROVIDER_ADJ =
            Integer.valueOf(SystemProperties.get("ro.CONTENT_PROVIDER_ADJ"));
        HIDDEN_APP_MAX_ADJ = CONTENT_PROVIDER_ADJ-1;
        EMPTY_APP_ADJ =
            Integer.valueOf(SystemProperties.get("ro.EMPTY_APP_ADJ"));
        FOREGROUND_APP_MEM =
            Integer.valueOf(SystemProperties.get("ro.FOREGROUND_APP_MEM"))*PAGE_SIZE;
        VISIBLE_APP_MEM =
            Integer.valueOf(SystemProperties.get("ro.VISIBLE_APP_MEM"))*PAGE_SIZE;
        SECONDARY_SERVER_MEM =
            Integer.valueOf(SystemProperties.get("ro.SECONDARY_SERVER_MEM"))*PAGE_SIZE;
        HOME_APP_MEM =
            Integer.valueOf(SystemProperties.get("ro.HOME_APP_MEM"))*PAGE_SIZE;
        HIDDEN_APP_MEM =
            Integer.valueOf(SystemProperties.get("ro.HIDDEN_APP_MEM"))*PAGE_SIZE;
        EMPTY_APP_MEM =
            Integer.valueOf(SystemProperties.get("ro.EMPTY_APP_MEM"))*PAGE_SIZE;

        mProcessStatsThread = new Thread("ProcessStats") {
            public void run() {
                while (true) {
                    try {
                        try {
                            synchronized(this) {
                                final long now = SystemClock.uptimeMillis();
                                long nextCpuDelay = (mLastCpuTime+MONITOR_CPU_MAX_TIME)-now;
                                long nextWriteDelay = (mLastWriteTime+BATTERY_STATS_TIME)-now;
                                //Log.i(TAG, "Cpu delay=" + nextCpuDelay
                                //        + ", write delay=" + nextWriteDelay);
                                if (nextWriteDelay < nextCpuDelay) {
                                    nextCpuDelay = nextWriteDelay;
                                }
                                if (nextCpuDelay > 0) {
                                    this.wait(nextCpuDelay);
                                }
                            }
                        } catch (InterruptedException e) {
                        }
                        
                        updateCpuStatsNow();
                    } catch (Exception e) {
                        Log.e(TAG, "Unexpected exception collecting process stats", e);
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
                Log.e(TAG, "Activity Manager Crash", e);
            }
            throw e;
        }
    }

    void updateCpuStats() {
        synchronized (mProcessStatsThread) {
            final long now = SystemClock.uptimeMillis();
            if (mLastCpuTime < (now-MONITOR_CPU_MIN_TIME)) {
                mProcessStatsThread.notify();
            }
        }
    }
    
    void updateCpuStatsNow() {
        synchronized (mProcessStatsThread) {
            final long now = SystemClock.uptimeMillis();
            boolean haveNewCpuStats = false;
            
            if (MONITOR_CPU_USAGE &&
                    mLastCpuTime < (now-MONITOR_CPU_MIN_TIME)) {
                mLastCpuTime = now;
                haveNewCpuStats = true;
                mProcessStats.update();
                //Log.i(TAG, mProcessStats.printCurrentState());
                //Log.i(TAG, "Total CPU usage: "
                //        + mProcessStats.getTotalCpuPercent() + "%");

                // Log the cpu usage if the property is set.
                if ("true".equals(SystemProperties.get("events.cpu"))) {
                    int user = mProcessStats.getLastUserTime();
                    int system = mProcessStats.getLastSystemTime();
                    int iowait = mProcessStats.getLastIoWaitTime();
                    int irq = mProcessStats.getLastIrqTime();
                    int softIrq = mProcessStats.getLastSoftIrqTime();
                    int idle = mProcessStats.getLastIdleTime();

                    int total = user + system + iowait + irq + softIrq + idle;
                    if (total == 0) total = 1;

                    EventLog.writeEvent(LOG_CPU,
                            ((user+system+iowait+irq+softIrq) * 100) / total,
                            (user * 100) / total,
                            (system * 100) / total,
                            (iowait * 100) / total,
                            (irq * 100) / total,
                            (softIrq * 100) / total);
                }
            }
            
            synchronized(mBatteryStatsService.getActiveStatistics()) {
                synchronized(mPidsSelfLocked) {
                    if (haveNewCpuStats) {
                        if (mBatteryStatsService.isOnBattery()) {
                            final int N = mProcessStats.countWorkingStats();
                            for (int i=0; i<N; i++) {
                                ProcessStats.Stats st
                                        = mProcessStats.getWorkingStats(i);
                                ProcessRecord pr = mPidsSelfLocked.get(st.pid);
                                if (pr != null) {
                                    BatteryStatsImpl.Uid.Proc ps = pr.batteryStats;
                                    ps.addCpuTimeLocked(st.rel_utime, st.rel_stime);
                                }
                            }
                        }
                    }
                }
        
                if (mLastWriteTime < (now-BATTERY_STATS_TIME)) {
                    mLastWriteTime = now;
                    mBatteryStatsService.getActiveStatistics().writeLocked();
                }
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

    private final void setFocusedActivityLocked(HistoryRecord r) {
        if (mFocusedActivity != r) {
            mFocusedActivity = r;
            mWindowManager.setFocusedApp(r, true);
        }
    }

    private final void updateLRUListLocked(ProcessRecord app,
            boolean oomAdj) {
        // put it on the LRU to keep track of when it should be exited.
        int lrui = mLRUProcesses.indexOf(app);
        if (lrui >= 0) mLRUProcesses.remove(lrui);
        mLRUProcesses.add(app);
        //Log.i(TAG, "Putting proc to front: " + app.processName);
        if (oomAdj) {
            updateOomAdjLocked();
        }
    }

    private final boolean updateLRUListLocked(HistoryRecord r) {
        final boolean hadit = mLRUActivities.remove(r);
        mLRUActivities.add(r);
        return hadit;
    }

    private final HistoryRecord topRunningActivityLocked(HistoryRecord notTop) {
        int i = mHistory.size()-1;
        while (i >= 0) {
            HistoryRecord r = (HistoryRecord)mHistory.get(i);
            if (!r.finishing && r != notTop) {
                return r;
            }
            i--;
        }
        return null;
    }

    /**
     * This is a simplified version of topRunningActivityLocked that provides a number of
     * optional skip-over modes.  It is intended for use with the ActivityWatcher hook only.
     * 
     * @param token If non-null, any history records matching this token will be skipped.
     * @param taskId If non-zero, we'll attempt to skip over records with the same task ID.
     * 
     * @return Returns the HistoryRecord of the next activity on the stack.
     */
    private final HistoryRecord topRunningActivityLocked(IBinder token, int taskId) {
        int i = mHistory.size()-1;
        while (i >= 0) {
            HistoryRecord r = (HistoryRecord)mHistory.get(i);
            // Note: the taskId check depends on real taskId fields being non-zero
            if (!r.finishing && (token != r) && (taskId != r.task.taskId)) {
                return r;
            }
            i--;
        }
        return null;
    }

    private final ProcessRecord getProcessRecordLocked(
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

    private boolean isNextTransitionForward() {
        int transit = mWindowManager.getPendingAppTransition();
        return transit == WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN
                || transit == WindowManagerPolicy.TRANSIT_TASK_OPEN
                || transit == WindowManagerPolicy.TRANSIT_TASK_TO_FRONT;
    }
    
    private final boolean realStartActivityLocked(HistoryRecord r,
            ProcessRecord app, boolean andResume, boolean checkConfig)
            throws RemoteException {

        r.startFreezingScreenLocked(app, 0);
        mWindowManager.setAppVisibility(r, true);

        // Have the window manager re-evaluate the orientation of
        // the screen based on the new activity order.  Note that
        // as a result of this, it can call back into the activity
        // manager with a new orientation.  We don't care about that,
        // because the activity is not currently running so we are
        // just restarting it anyway.
        if (checkConfig) {
            Configuration config = mWindowManager.updateOrientationFromAppTokens(
                    mConfiguration,
                    r.mayFreezeScreenLocked(app) ? r : null);
            updateConfigurationLocked(config, r);
        }

        r.app = app;

        if (localLOGV) Log.v(TAG, "Launching: " + r);

        int idx = app.activities.indexOf(r);
        if (idx < 0) {
            app.activities.add(r);
        }
        updateLRUListLocked(app, true);

        try {
            if (app.thread == null) {
                throw new RemoteException();
            }
            List<ResultInfo> results = null;
            List<Intent> newIntents = null;
            if (andResume) {
                results = r.results;
                newIntents = r.newIntents;
            }
            if (DEBUG_SWITCH) Log.v(TAG, "Launching: " + r
                    + " icicle=" + r.icicle
                    + " with results=" + results + " newIntents=" + newIntents
                    + " andResume=" + andResume);
            if (andResume) {
                EventLog.writeEvent(LOG_AM_RESTART_ACTIVITY,
                        System.identityHashCode(r),
                        r.task.taskId, r.shortComponentName);
            }
            if (r.isHomeActivity) {
                mHomeProcess = app;
            }
            app.thread.scheduleLaunchActivity(new Intent(r.intent), r,
                    r.info, r.icicle, results, newIntents, !andResume,
                    isNextTransitionForward());
            // Update usage stats for launched activity
            updateUsageStats(r, true);
        } catch (RemoteException e) {
            if (r.launchFailed) {
                // This is the second time we failed -- finish activity
                // and give up.
                Log.e(TAG, "Second failure launching "
                      + r.intent.getComponent().flattenToShortString()
                      + ", giving up", e);
                appDiedLocked(app, app.pid, app.thread);
                requestFinishActivityLocked(r, Activity.RESULT_CANCELED, null,
                        "2nd-crash");
                return false;
            }

            // This is the first time we failed -- restart process and
            // retry.
            app.activities.remove(r);
            throw e;
        }

        r.launchFailed = false;
        if (updateLRUListLocked(r)) {
            Log.w(TAG, "Activity " + r
                  + " being launched, but already in LRU list");
        }

        if (andResume) {
            // As part of the process of launching, ActivityThread also performs
            // a resume.
            r.state = ActivityState.RESUMED;
            r.icicle = null;
            r.haveState = false;
            r.stopped = false;
            mResumedActivity = r;
            r.task.touchActiveTime();
            completeResumeLocked(r);
            pauseIfSleepingLocked();                
        } else {
            // This activity is not starting in the resumed state... which
            // should look like we asked it to pause+stop (but remain visible),
            // and it has done so and reported back the current icicle and
            // other state.
            r.state = ActivityState.STOPPED;
            r.stopped = true;
        }

        return true;
    }

    private final void startSpecificActivityLocked(HistoryRecord r,
            boolean andResume, boolean checkConfig) {
        // Is this activity's application already running?
        ProcessRecord app = getProcessRecordLocked(r.processName,
                r.info.applicationInfo.uid);
        
        if (r.startTime == 0) {
            r.startTime = SystemClock.uptimeMillis();
        }
        
        if (app != null && app.thread != null) {
            try {
                realStartActivityLocked(r, app, andResume, checkConfig);
                return;
            } catch (RemoteException e) {
                Log.w(TAG, "Exception when starting activity "
                        + r.intent.getComponent().flattenToShortString(), e);
            }

            // If a dead object exception was thrown -- fall through to
            // restart the application.
        }

        startProcessLocked(r.processName, r.info.applicationInfo, true, 0,
                "activity", r.intent.getComponent());
    }

    private final ProcessRecord startProcessLocked(String processName,
            ApplicationInfo info, boolean knownToBeDead, int intentFlags,
            String hostingType, ComponentName hostingName) {
        ProcessRecord app = getProcessRecordLocked(processName, info.uid);
        // We don't have to do anything more if:
        // (1) There is an existing application record; and
        // (2) The caller doesn't think it is dead, OR there is no thread
        //     object attached to it so we know it couldn't have crashed; and
        // (3) There is a pid assigned to it, so it is either starting or
        //     already running.
        if (DEBUG_PROCESSES) Log.v(TAG, "startProcess: name=" + processName
                + " app=" + app + " knownToBeDead=" + knownToBeDead
                + " thread=" + (app != null ? app.thread : null)
                + " pid=" + (app != null ? app.pid : -1));
        if (app != null &&
                (!knownToBeDead || app.thread == null) && app.pid > 0) {
            return app;
        }
        
        String hostingNameStr = hostingName != null
                ? hostingName.flattenToShortString() : null;
        
        if ((intentFlags&Intent.FLAG_FROM_BACKGROUND) != 0) {
            // If we are in the background, then check to see if this process
            // is bad.  If so, we will just silently fail.
            if (mBadProcesses.get(info.processName, info.uid) != null) {
                return null;
            }
        } else {
            // When the user is explicitly starting a process, then clear its
            // crash count so that we won't make it bad until they see at
            // least one crash dialog again, and make the process good again
            // if it had been bad.
            mProcessCrashTimes.remove(info.processName, info.uid);
            if (mBadProcesses.get(info.processName, info.uid) != null) {
                EventLog.writeEvent(LOG_AM_PROCESS_GOOD, info.uid,
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
        if (!mSystemReady
                && (info.flags&ApplicationInfo.FLAG_PERSISTENT) == 0) {
            if (!mProcessesOnHold.contains(app)) {
                mProcessesOnHold.add(app);
            }
            return app;
        }

        startProcessLocked(app, hostingType, hostingNameStr);
        return (app.pid != 0) ? app : null;
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
                Log.w(TAG, "Unable to retrieve gids", e);
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
            }
            if ("1".equals(SystemProperties.get("debug.checkjni"))) {
                debugFlags |= Zygote.DEBUG_ENABLE_CHECKJNI;
            }
            if ("1".equals(SystemProperties.get("debug.assert"))) {
                debugFlags |= Zygote.DEBUG_ENABLE_ASSERT;
            }
            int pid = Process.start("android.app.ActivityThread",
                    mSimpleProcessManagement ? app.processName : null, uid, uid,
                    gids, debugFlags, null);
            BatteryStatsImpl bs = app.batteryStats.getBatteryStats();
            synchronized (bs) {
                if (bs.isOnBattery()) {
                    app.batteryStats.incStartsLocked();
                }
            }
            
            EventLog.writeEvent(LOG_AM_PROCESS_START, pid, uid,
                    app.processName, hostingType,
                    hostingNameStr != null ? hostingNameStr : "");
            
            if (app.persistent) {
                Watchdog.getInstance().processStarted(app, app.processName, pid);
            }
            
            StringBuilder buf = new StringBuilder(128);
            buf.append("Start proc ");
            buf.append(app.processName);
            buf.append(" for ");
            buf.append(hostingType);
            if (hostingNameStr != null) {
                buf.append(" ");
                buf.append(hostingNameStr);
            }
            buf.append(": pid=");
            buf.append(pid);
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
            Log.i(TAG, buf.toString());
            if (pid == 0 || pid == MY_PID) {
                // Processes are being emulated with threads.
                app.pid = MY_PID;
                app.removed = false;
                mStartingProcesses.add(app);
            } else if (pid > 0) {
                app.pid = pid;
                app.removed = false;
                synchronized (mPidsSelfLocked) {
                    this.mPidsSelfLocked.put(pid, app);
                    Message msg = mHandler.obtainMessage(PROC_START_TIMEOUT_MSG);
                    msg.obj = app;
                    mHandler.sendMessageDelayed(msg, PROC_START_TIMEOUT);
                }
            } else {
                app.pid = 0;
                RuntimeException e = new RuntimeException(
                        "Failure starting process " + app.processName
                        + ": returned pid=" + pid);
                Log.e(TAG, e.getMessage(), e);
            }
        } catch (RuntimeException e) {
            // XXX do better error recovery.
            app.pid = 0;
            Log.e(TAG, "Failure starting process " + app.processName, e);
        }
    }

    private final void startPausingLocked(boolean userLeaving, boolean uiSleeping) {
        if (mPausingActivity != null) {
            RuntimeException e = new RuntimeException();
            Log.e(TAG, "Trying to pause when pause is already pending for "
                  + mPausingActivity, e);
        }
        HistoryRecord prev = mResumedActivity;
        if (prev == null) {
            RuntimeException e = new RuntimeException();
            Log.e(TAG, "Trying to pause when nothing is resumed", e);
            resumeTopActivityLocked(null);
            return;
        }
        if (DEBUG_PAUSE) Log.v(TAG, "Start pausing: " + prev);
        mResumedActivity = null;
        mPausingActivity = prev;
        mLastPausedActivity = prev;
        prev.state = ActivityState.PAUSING;
        prev.task.touchActiveTime();

        updateCpuStats();
        
        if (prev.app != null && prev.app.thread != null) {
            if (DEBUG_PAUSE) Log.v(TAG, "Enqueueing pending pause: " + prev);
            try {
                EventLog.writeEvent(LOG_AM_PAUSE_ACTIVITY,
                        System.identityHashCode(prev),
                        prev.shortComponentName);
                prev.app.thread.schedulePauseActivity(prev, prev.finishing, userLeaving,
                        prev.configChangeFlags);
                updateUsageStats(prev, false);
            } catch (Exception e) {
                // Ignore exception, if process died other code will cleanup.
                Log.w(TAG, "Exception thrown during pause", e);
                mPausingActivity = null;
                mLastPausedActivity = null;
            }
        } else {
            mPausingActivity = null;
            mLastPausedActivity = null;
        }

        // If we are not going to sleep, we want to ensure the device is
        // awake until the next activity is started.
        if (!mSleeping) {
            mLaunchingActivity.acquire();
            if (!mHandler.hasMessages(LAUNCH_TIMEOUT_MSG)) {
                // To be safe, don't allow the wake lock to be held for too long.
                Message msg = mHandler.obtainMessage(LAUNCH_TIMEOUT_MSG);
                mHandler.sendMessageDelayed(msg, LAUNCH_TIMEOUT);
            }
        }


        if (mPausingActivity != null) {
            // Have the window manager pause its key dispatching until the new
            // activity has started.  If we're pausing the activity just because
            // the screen is being turned off and the UI is sleeping, don't interrupt
            // key dispatch; the same activity will pick it up again on wakeup.
            if (!uiSleeping) {
                prev.pauseKeyDispatchingLocked();
            } else {
                if (DEBUG_PAUSE) Log.v(TAG, "Key dispatch not paused for screen off");
            }

            // Schedule a pause timeout in case the app doesn't respond.
            // We don't give it much time because this directly impacts the
            // responsiveness seen by the user.
            Message msg = mHandler.obtainMessage(PAUSE_TIMEOUT_MSG);
            msg.obj = prev;
            mHandler.sendMessageDelayed(msg, PAUSE_TIMEOUT);
            if (DEBUG_PAUSE) Log.v(TAG, "Waiting for pause to complete...");
        } else {
            // This activity failed to schedule the
            // pause, so just treat it as being paused now.
            if (DEBUG_PAUSE) Log.v(TAG, "Activity not running, resuming next.");
            resumeTopActivityLocked(null);
        }
    }

    private final void completePauseLocked() {
        HistoryRecord prev = mPausingActivity;
        if (DEBUG_PAUSE) Log.v(TAG, "Complete pause: " + prev);
        
        if (prev != null) {
            if (prev.finishing) {
                if (DEBUG_PAUSE) Log.v(TAG, "Executing finish of activity: " + prev);
                prev = finishCurrentActivityLocked(prev, FINISH_AFTER_VISIBLE);
            } else if (prev.app != null) {
                if (DEBUG_PAUSE) Log.v(TAG, "Enqueueing pending stop: " + prev);
                if (prev.waitingVisible) {
                    prev.waitingVisible = false;
                    mWaitingVisibleActivities.remove(prev);
                    if (DEBUG_SWITCH || DEBUG_PAUSE) Log.v(
                            TAG, "Complete pause, no longer waiting: " + prev);
                }
                if (prev.configDestroy) {
                    // The previous is being paused because the configuration
                    // is changing, which means it is actually stopping...
                    // To juggle the fact that we are also starting a new
                    // instance right now, we need to first completely stop
                    // the current instance before starting the new one.
                    if (DEBUG_PAUSE) Log.v(TAG, "Destroying after pause: " + prev);
                    destroyActivityLocked(prev, true);
                } else {
                    mStoppingActivities.add(prev);
                    if (mStoppingActivities.size() > 3) {
                        // If we already have a few activities waiting to stop,
                        // then give up on things going idle and start clearing
                        // them out.
                        if (DEBUG_PAUSE) Log.v(TAG, "To many pending stops, forcing idle");
                        Message msg = Message.obtain();
                        msg.what = ActivityManagerService.IDLE_NOW_MSG;
                        mHandler.sendMessage(msg);
                    }
                }
            } else {
                if (DEBUG_PAUSE) Log.v(TAG, "App died during pause, not stopping: " + prev);
                prev = null;
            }
            mPausingActivity = null;
        }

        if (!mSleeping) {
            resumeTopActivityLocked(prev);
        } else {
            if (mGoingToSleep.isHeld()) {
                mGoingToSleep.release();
            }
        }
        
        if (prev != null) {
            prev.resumeKeyDispatchingLocked();
        }
    }

    /**
     * Once we know that we have asked an application to put an activity in
     * the resumed state (either by launching it or explicitly telling it),
     * this function updates the rest of our state to match that fact.
     */
    private final void completeResumeLocked(HistoryRecord next) {
        next.idle = false;
        next.results = null;
        next.newIntents = null;

        // schedule an idle timeout in case the app doesn't do it for us.
        Message msg = mHandler.obtainMessage(IDLE_TIMEOUT_MSG);
        msg.obj = next;
        mHandler.sendMessageDelayed(msg, IDLE_TIMEOUT);

        if (false) {
            // The activity was never told to pause, so just keep
            // things going as-is.  To maintain our own state,
            // we need to emulate it coming back and saying it is
            // idle.
            msg = mHandler.obtainMessage(IDLE_NOW_MSG);
            msg.obj = next;
            mHandler.sendMessage(msg);
        }

        next.thumbnail = null;
        setFocusedActivityLocked(next);
        next.resumeKeyDispatchingLocked();
        ensureActivitiesVisibleLocked(null, 0);
        mWindowManager.executeAppTransition();
    }

    /**
     * Make sure that all activities that need to be visible (that is, they
     * currently can be seen by the user) actually are.
     */
    private final void ensureActivitiesVisibleLocked(HistoryRecord top,
            HistoryRecord starting, String onlyThisProcess, int configChanges) {
        if (DEBUG_VISBILITY) Log.v(
                TAG, "ensureActivitiesVisible behind " + top
                + " configChanges=0x" + Integer.toHexString(configChanges));

        // If the top activity is not fullscreen, then we need to
        // make sure any activities under it are now visible.
        final int count = mHistory.size();
        int i = count-1;
        while (mHistory.get(i) != top) {
            i--;
        }
        HistoryRecord r;
        boolean behindFullscreen = false;
        for (; i>=0; i--) {
            r = (HistoryRecord)mHistory.get(i);
            if (DEBUG_VISBILITY) Log.v(
                    TAG, "Make visible? " + r + " finishing=" + r.finishing
                    + " state=" + r.state);
            if (r.finishing) {
                continue;
            }
            
            final boolean doThisProcess = onlyThisProcess == null
                    || onlyThisProcess.equals(r.processName);
            
            // First: if this is not the current activity being started, make
            // sure it matches the current configuration.
            if (r != starting && doThisProcess) {
                ensureActivityConfigurationLocked(r, 0);
            }
            
            if (r.app == null || r.app.thread == null) {
                if (onlyThisProcess == null
                        || onlyThisProcess.equals(r.processName)) {
                    // This activity needs to be visible, but isn't even
                    // running...  get it started, but don't resume it
                    // at this point.
                    if (DEBUG_VISBILITY) Log.v(
                            TAG, "Start and freeze screen for " + r);
                    if (r != starting) {
                        r.startFreezingScreenLocked(r.app, configChanges);
                    }
                    if (!r.visible) {
                        if (DEBUG_VISBILITY) Log.v(
                                TAG, "Starting and making visible: " + r);
                        mWindowManager.setAppVisibility(r, true);
                    }
                    if (r != starting) {
                        startSpecificActivityLocked(r, false, false);
                    }
                }

            } else if (r.visible) {
                // If this activity is already visible, then there is nothing
                // else to do here.
                if (DEBUG_VISBILITY) Log.v(
                        TAG, "Skipping: already visible at " + r);
                r.stopFreezingScreenLocked(false);

            } else if (onlyThisProcess == null) {
                // This activity is not currently visible, but is running.
                // Tell it to become visible.
                r.visible = true;
                if (r.state != ActivityState.RESUMED && r != starting) {
                    // If this activity is paused, tell it
                    // to now show its window.
                    if (DEBUG_VISBILITY) Log.v(
                            TAG, "Making visible and scheduling visibility: " + r);
                    try {
                        mWindowManager.setAppVisibility(r, true);
                        r.app.thread.scheduleWindowVisibility(r, true);
                        r.stopFreezingScreenLocked(false);
                    } catch (Exception e) {
                        // Just skip on any failure; we'll make it
                        // visible when it next restarts.
                        Log.w(TAG, "Exception thrown making visibile: "
                                + r.intent.getComponent(), e);
                    }
                }
            }

            // Aggregate current change flags.
            configChanges |= r.configChangeFlags;

            if (r.fullscreen) {
                // At this point, nothing else needs to be shown
                if (DEBUG_VISBILITY) Log.v(
                        TAG, "Stopping: fullscreen at " + r);
                behindFullscreen = true;
                i--;
                break;
            }
        }

        // Now for any activities that aren't visible to the user, make
        // sure they no longer are keeping the screen frozen.
        while (i >= 0) {
            r = (HistoryRecord)mHistory.get(i);
            if (DEBUG_VISBILITY) Log.v(
                    TAG, "Make invisible? " + r + " finishing=" + r.finishing
                    + " state=" + r.state
                    + " behindFullscreen=" + behindFullscreen);
            if (!r.finishing) {
                if (behindFullscreen) {
                    if (r.visible) {
                        if (DEBUG_VISBILITY) Log.v(
                                TAG, "Making invisible: " + r);
                        r.visible = false;
                        try {
                            mWindowManager.setAppVisibility(r, false);
                            if ((r.state == ActivityState.STOPPING
                                    || r.state == ActivityState.STOPPED)
                                    && r.app != null && r.app.thread != null) {
                                if (DEBUG_VISBILITY) Log.v(
                                        TAG, "Scheduling invisibility: " + r);
                                r.app.thread.scheduleWindowVisibility(r, false);
                            }
                        } catch (Exception e) {
                            // Just skip on any failure; we'll make it
                            // visible when it next restarts.
                            Log.w(TAG, "Exception thrown making hidden: "
                                    + r.intent.getComponent(), e);
                        }
                    } else {
                        if (DEBUG_VISBILITY) Log.v(
                                TAG, "Already invisible: " + r);
                    }
                } else if (r.fullscreen) {
                    if (DEBUG_VISBILITY) Log.v(
                            TAG, "Now behindFullscreen: " + r);
                    behindFullscreen = true;
                }
            }
            i--;
        }
    }

    /**
     * Version of ensureActivitiesVisible that can easily be called anywhere.
     */
    private final void ensureActivitiesVisibleLocked(HistoryRecord starting,
            int configChanges) {
        HistoryRecord r = topRunningActivityLocked(null);
        if (r != null) {
            ensureActivitiesVisibleLocked(r, starting, null, configChanges);
        }
    }
    
    private void updateUsageStats(HistoryRecord resumedComponent, boolean resumed) {
        if (resumed) {
            mUsageStatsService.noteResumeComponent(resumedComponent.realActivity);
        } else {
            mUsageStatsService.notePauseComponent(resumedComponent.realActivity);
        }
    }

    /**
     * Ensure that the top activity in the stack is resumed.
     *
     * @param prev The previously resumed activity, for when in the process
     * of pausing; can be null to call from elsewhere.
     *
     * @return Returns true if something is being resumed, or false if
     * nothing happened.
     */
    private final boolean resumeTopActivityLocked(HistoryRecord prev) {
        // Find the first activity that is not finishing.
        HistoryRecord next = topRunningActivityLocked(null);

        // Remember how we'll process this pause/resume situation, and ensure
        // that the state is reset however we wind up proceeding.
        final boolean userLeaving = mUserLeaving;
        mUserLeaving = false;

        if (next == null) {
            // There are no more activities!  Let's just start up the
            // Launcher...
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
                        PackageManager.GET_SHARED_LIBRARY_FILES);
            if (aInfo != null) {
                intent.setComponent(new ComponentName(
                        aInfo.applicationInfo.packageName, aInfo.name));
                // Don't do this if the home app is currently being
                // instrumented.
                ProcessRecord app = getProcessRecordLocked(aInfo.processName,
                        aInfo.applicationInfo.uid);
                if (app == null || app.instrumentationClass == null) {
                    intent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivityLocked(null, intent, null, null, 0, aInfo,
                            null, null, 0, 0, 0, false, false);
                }
            }
            return true;
        }

        // If the top activity is the resumed one, nothing to do.
        if (mResumedActivity == next && next.state == ActivityState.RESUMED) {
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            mWindowManager.executeAppTransition();
            return false;
        }

        // If we are sleeping, and there is no resumed activity, and the top
        // activity is paused, well that is the state we want.
        if (mSleeping && mLastPausedActivity == next && next.state == ActivityState.PAUSED) {
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            mWindowManager.executeAppTransition();
            return false;
        }
        
        // The activity may be waiting for stop, but that is no longer
        // appropriate for it.
        mStoppingActivities.remove(next);
        mWaitingVisibleActivities.remove(next);

        if (DEBUG_SWITCH) Log.v(TAG, "Resuming " + next);

        // If we are currently pausing an activity, then don't do anything
        // until that is done.
        if (mPausingActivity != null) {
            if (DEBUG_SWITCH) Log.v(TAG, "Skip resume: pausing=" + mPausingActivity);
            return false;
        }

        // We need to start pausing the current activity so the top one
        // can be resumed...
        if (mResumedActivity != null) {
            if (DEBUG_SWITCH) Log.v(TAG, "Skip resume: need to start pausing");
            startPausingLocked(userLeaving, false);
            return true;
        }

        if (prev != null && prev != next) {
            if (!prev.waitingVisible && next != null && !next.nowVisible) {
                prev.waitingVisible = true;
                mWaitingVisibleActivities.add(prev);
                if (DEBUG_SWITCH) Log.v(
                        TAG, "Resuming top, waiting visible to hide: " + prev);
            } else {
                // The next activity is already visible, so hide the previous
                // activity's windows right now so we can show the new one ASAP.
                // We only do this if the previous is finishing, which should mean
                // it is on top of the one being resumed so hiding it quickly
                // is good.  Otherwise, we want to do the normal route of allowing
                // the resumed activity to be shown so we can decide if the
                // previous should actually be hidden depending on whether the
                // new one is found to be full-screen or not.
                if (prev.finishing) {
                    mWindowManager.setAppVisibility(prev, false);
                    if (DEBUG_SWITCH) Log.v(TAG, "Not waiting for visible to hide: "
                            + prev + ", waitingVisible="
                            + (prev != null ? prev.waitingVisible : null)
                            + ", nowVisible=" + next.nowVisible);
                } else {
                    if (DEBUG_SWITCH) Log.v(TAG, "Previous already visible but still waiting to hide: "
                        + prev + ", waitingVisible="
                        + (prev != null ? prev.waitingVisible : null)
                        + ", nowVisible=" + next.nowVisible);
                }
            }
        }

        // We are starting up the next activity, so tell the window manager
        // that the previous one will be hidden soon.  This way it can know
        // to ignore it when computing the desired screen orientation.
        if (prev != null) {
            if (prev.finishing) {
                if (DEBUG_TRANSITION) Log.v(TAG,
                        "Prepare close transition: prev=" + prev);
                mWindowManager.prepareAppTransition(prev.task == next.task
                        ? WindowManagerPolicy.TRANSIT_ACTIVITY_CLOSE
                        : WindowManagerPolicy.TRANSIT_TASK_CLOSE);
                mWindowManager.setAppWillBeHidden(prev);
                mWindowManager.setAppVisibility(prev, false);
            } else {
                if (DEBUG_TRANSITION) Log.v(TAG,
                        "Prepare open transition: prev=" + prev);
                mWindowManager.prepareAppTransition(prev.task == next.task
                        ? WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN
                        : WindowManagerPolicy.TRANSIT_TASK_OPEN);
            }
            if (false) {
                mWindowManager.setAppWillBeHidden(prev);
                mWindowManager.setAppVisibility(prev, false);
            }
        } else if (mHistory.size() > 1) {
            if (DEBUG_TRANSITION) Log.v(TAG,
                    "Prepare open transition: no previous");
            mWindowManager.prepareAppTransition(WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN);
        }

        if (next.app != null && next.app.thread != null) {
            if (DEBUG_SWITCH) Log.v(TAG, "Resume running: " + next);

            // This activity is now becoming visible.
            mWindowManager.setAppVisibility(next, true);

            HistoryRecord lastResumedActivity = mResumedActivity;
            ActivityState lastState = next.state;

            updateCpuStats();
            
            next.state = ActivityState.RESUMED;
            mResumedActivity = next;
            next.task.touchActiveTime();
            updateLRUListLocked(next.app, true);
            updateLRUListLocked(next);

            // Have the window manager re-evaluate the orientation of
            // the screen based on the new activity order.
            Configuration config = mWindowManager.updateOrientationFromAppTokens(
                    mConfiguration,
                    next.mayFreezeScreenLocked(next.app) ? next : null);
            if (config != null) {
                next.frozenBeforeDestroy = true;
            }
            if (!updateConfigurationLocked(config, next)) {
                // The configuration update wasn't able to keep the existing
                // instance of the activity, and instead started a new one.
                // We should be all done, but let's just make sure our activity
                // is still at the top and schedule another run if something
                // weird happened.
                HistoryRecord nextNext = topRunningActivityLocked(null);
                if (DEBUG_SWITCH) Log.i(TAG,
                        "Activity config changed during resume: " + next
                        + ", new next: " + nextNext);
                if (nextNext != next) {
                    // Do over!
                    mHandler.sendEmptyMessage(RESUME_TOP_ACTIVITY_MSG);
                }
                mWindowManager.executeAppTransition();
                return true;
            }
            
            try {
                // Deliver all pending results.
                ArrayList a = next.results;
                if (a != null) {
                    final int N = a.size();
                    if (!next.finishing && N > 0) {
                        if (DEBUG_RESULTS) Log.v(
                                TAG, "Delivering results to " + next
                                + ": " + a);
                        next.app.thread.scheduleSendResult(next, a);
                    }
                }

                if (next.newIntents != null) {
                    next.app.thread.scheduleNewIntent(next.newIntents, next);
                }

                EventLog.writeEvent(LOG_AM_RESUME_ACTIVITY,
                        System.identityHashCode(next),
                        next.task.taskId, next.shortComponentName);
                updateUsageStats(next, true);
                
                next.app.thread.scheduleResumeActivity(next,
                        isNextTransitionForward());
                pauseIfSleepingLocked();

            } catch (Exception e) {
                // Whoops, need to restart this activity!
                next.state = lastState;
                mResumedActivity = lastResumedActivity;
                if (Config.LOGD) Log.d(TAG,
                        "Restarting because process died: " + next);
                if (!next.hasBeenLaunched) {
                    next.hasBeenLaunched = true;
                } else {
                    if (SHOW_APP_STARTING_ICON) {
                        mWindowManager.setAppStartingWindow(
                                next, next.packageName, next.theme,
                                next.nonLocalizedLabel,
                                next.labelRes, next.icon, null, true);
                    }
                }
                startSpecificActivityLocked(next, true, false);
                return true;
            }

            // From this point on, if something goes wrong there is no way
            // to recover the activity.
            try {
                next.visible = true;
                completeResumeLocked(next);
            } catch (Exception e) {
                // If any exception gets thrown, toss away this
                // activity and try the next one.
                Log.w(TAG, "Exception thrown during resume of " + next, e);
                requestFinishActivityLocked(next, Activity.RESULT_CANCELED, null,
                        "resume-exception");
                return true;
            }

            // Didn't need to use the icicle, and it is now out of date.
            next.icicle = null;
            next.haveState = false;
            next.stopped = false;

        } else {
            // Whoops, need to restart this activity!
            if (!next.hasBeenLaunched) {
                next.hasBeenLaunched = true;
            } else {
                if (SHOW_APP_STARTING_ICON) {
                    mWindowManager.setAppStartingWindow(
                            next, next.packageName, next.theme,
                            next.nonLocalizedLabel,
                            next.labelRes, next.icon, null, true);
                }
                if (DEBUG_SWITCH) Log.v(TAG, "Restarting: " + next);
            }
            startSpecificActivityLocked(next, true, true);
        }

        return true;
    }

    private final void startActivityLocked(HistoryRecord r, boolean newTask) {
        final int NH = mHistory.size();

        int addPos = -1;
        
        if (!newTask) {
            // If starting in an existing task, find where that is...
            HistoryRecord next = null;
            boolean startIt = true;
            for (int i = NH-1; i >= 0; i--) {
                HistoryRecord p = (HistoryRecord)mHistory.get(i);
                if (p.finishing) {
                    continue;
                }
                if (p.task == r.task) {
                    // Here it is!  Now, if this is not yet visible to the
                    // user, then just add it without starting; it will
                    // get started when the user navigates back to it.
                    addPos = i+1;
                    if (!startIt) {
                        mHistory.add(addPos, r);
                        r.inHistory = true;
                        r.task.numActivities++;
                        mWindowManager.addAppToken(addPos, r, r.task.taskId,
                                r.info.screenOrientation, r.fullscreen);
                        if (VALIDATE_TOKENS) {
                            mWindowManager.validateAppTokens(mHistory);
                        }
                        return;
                    }
                    break;
                }
                if (p.fullscreen) {
                    startIt = false;
                }
                next = p;
            }
        }

        // Place a new activity at top of stack, so it is next to interact
        // with the user.
        if (addPos < 0) {
            addPos = mHistory.size();
        }
        
        // If we are not placing the new activity frontmost, we do not want
        // to deliver the onUserLeaving callback to the actual frontmost
        // activity
        if (addPos < NH) {
            mUserLeaving = false;
            if (DEBUG_USER_LEAVING) Log.v(TAG, "startActivity() behind front, mUserLeaving=false");
        }
        
        // Slot the activity into the history stack and proceed
        mHistory.add(addPos, r);
        r.inHistory = true;
        r.frontOfTask = newTask;
        r.task.numActivities++;
        if (NH > 0) {
            // We want to show the starting preview window if we are
            // switching to a new task, or the next activity's process is
            // not currently running.
            boolean showStartingIcon = newTask;
            ProcessRecord proc = r.app;
            if (proc == null) {
                proc = mProcessNames.get(r.processName, r.info.applicationInfo.uid);
            }
            if (proc == null || proc.thread == null) {
                showStartingIcon = true;
            }
            if (DEBUG_TRANSITION) Log.v(TAG,
                    "Prepare open transition: starting " + r);
            mWindowManager.prepareAppTransition(newTask
                    ? WindowManagerPolicy.TRANSIT_TASK_OPEN
                    : WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN);
            mWindowManager.addAppToken(
                    addPos, r, r.task.taskId, r.info.screenOrientation, r.fullscreen);
            boolean doShow = true;
            if (newTask) {
                // Even though this activity is starting fresh, we still need
                // to reset it to make sure we apply affinities to move any
                // existing activities from other tasks in to it.
                // If the caller has requested that the target task be
                // reset, then do so.
                if ((r.intent.getFlags()
                        &Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
                    resetTaskIfNeededLocked(r, r);
                    doShow = topRunningActivityLocked(null) == r;
                }
            }
            if (SHOW_APP_STARTING_ICON && doShow) {
                // Figure out if we are transitioning from another activity that is
                // "has the same starting icon" as the next one.  This allows the
                // window manager to keep the previous window it had previously
                // created, if it still had one.
                HistoryRecord prev = mResumedActivity;
                if (prev != null) {
                    // We don't want to reuse the previous starting preview if:
                    // (1) The current activity is in a different task.
                    if (prev.task != r.task) prev = null;
                    // (2) The current activity is already displayed.
                    else if (prev.nowVisible) prev = null;
                }
                mWindowManager.setAppStartingWindow(
                        r, r.packageName, r.theme, r.nonLocalizedLabel,
                        r.labelRes, r.icon, prev, showStartingIcon);
            }
        } else {
            // If this is the first activity, don't do any fancy animations,
            // because there is nothing for it to animate on top of.
            mWindowManager.addAppToken(addPos, r, r.task.taskId,
                    r.info.screenOrientation, r.fullscreen);
        }
        if (VALIDATE_TOKENS) {
            mWindowManager.validateAppTokens(mHistory);
        }

        resumeTopActivityLocked(null);
    }

    /**
     * Perform clear operation as requested by
     * {@link Intent#FLAG_ACTIVITY_CLEAR_TOP}: assuming the top task on the
     * stack is the one that the new activity is being launched in, look for
     * an instance of that activity in the stack and, if found, finish all
     * activities on top of it and return the instance.
     *
     * @param newR Description of the new activity being started.
     * @return Returns the old activity that should be continue to be used,
     * or null if none was found.
     */
    private final HistoryRecord performClearTopTaskLocked(int taskId,
            HistoryRecord newR, boolean doClear) {
        int i = mHistory.size();
        while (i > 0) {
            i--;
            HistoryRecord r = (HistoryRecord)mHistory.get(i);
            if (r.finishing) {
                continue;
            }
            if (r.task.taskId != taskId) {
                return null;
            }
            if (r.realActivity.equals(newR.realActivity)) {
                // Here it is!  Now finish everything in front...
                HistoryRecord ret = r;
                if (doClear) {
                    while (i < (mHistory.size()-1)) {
                        i++;
                        r = (HistoryRecord)mHistory.get(i);
                        if (r.finishing) {
                            continue;
                        }
                        if (finishActivityLocked(r, i, Activity.RESULT_CANCELED,
                                null, "clear")) {
                            i--;
                        }
                    }
                }
                
                // Finally, if this is a normal launch mode (that is, not
                // expecting onNewIntent()), then we will finish the current
                // instance of the activity so a new fresh one can be started.
                if (ret.launchMode == ActivityInfo.LAUNCH_MULTIPLE) {
                    if (!ret.finishing) {
                        int index = indexOfTokenLocked(ret, false);
                        if (index >= 0) {
                            finishActivityLocked(ret, 0, Activity.RESULT_CANCELED,
                                    null, "clear");
                        }
                        return null;
                    }
                }
                
                return ret;
            }
        }

        return null;
    }

    /**
     * Find the activity in the history stack within the given task.  Returns
     * the index within the history at which it's found, or < 0 if not found.
     */
    private final int findActivityInHistoryLocked(HistoryRecord r, int task) {
        int i = mHistory.size();
        while (i > 0) {
            i--;
            HistoryRecord candidate = (HistoryRecord)mHistory.get(i);
            if (candidate.task.taskId != task) {
                break;
            }
            if (candidate.realActivity.equals(r.realActivity)) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Reorder the history stack so that the activity at the given index is
     * brought to the front.
     */
    private final HistoryRecord moveActivityToFrontLocked(int where) {
        HistoryRecord newTop = (HistoryRecord)mHistory.remove(where);
        int top = mHistory.size();
        HistoryRecord oldTop = (HistoryRecord)mHistory.get(top-1);
        mHistory.add(top, newTop);
        oldTop.frontOfTask = false;
        newTop.frontOfTask = true;
        return newTop;
    }

    /**
     * Deliver a new Intent to an existing activity, so that its onNewIntent()
     * method will be called at the proper time.
     */
    private final void deliverNewIntentLocked(HistoryRecord r, Intent intent) {
        boolean sent = false;
        if (r.state == ActivityState.RESUMED
                && r.app != null && r.app.thread != null) {
            try {
                ArrayList<Intent> ar = new ArrayList<Intent>();
                ar.add(new Intent(intent));
                r.app.thread.scheduleNewIntent(ar, r);
                sent = true;
            } catch (Exception e) {
                Log.w(TAG, "Exception thrown sending new intent to " + r, e);
            }
        }
        if (!sent) {
            r.addNewIntentLocked(new Intent(intent));
        }
    }

    private final void logStartActivity(int tag, HistoryRecord r,
            TaskRecord task) {
        EventLog.writeEvent(tag,
                System.identityHashCode(r), task.taskId,
                r.shortComponentName, r.intent.getAction(),
                r.intent.getType(), r.intent.getDataString(),
                r.intent.getFlags());
    }

    private final int startActivityLocked(IApplicationThread caller,
            Intent intent, String resolvedType,
            Uri[] grantedUriPermissions,
            int grantedMode, ActivityInfo aInfo, IBinder resultTo,
            String resultWho, int requestCode,
            int callingPid, int callingUid, boolean onlyIfNeeded,
            boolean componentSpecified) {
        Log.i(TAG, "Starting activity: " + intent);

        HistoryRecord sourceRecord = null;
        HistoryRecord resultRecord = null;
        if (resultTo != null) {
            int index = indexOfTokenLocked(resultTo, false);
            if (DEBUG_RESULTS) Log.v(
                TAG, "Sending result to " + resultTo + " (index " + index + ")");
            if (index >= 0) {
                sourceRecord = (HistoryRecord)mHistory.get(index);
                if (requestCode >= 0 && !sourceRecord.finishing) {
                    resultRecord = sourceRecord;
                }
            }
        }

        int launchFlags = intent.getFlags();

        if ((launchFlags&Intent.FLAG_ACTIVITY_FORWARD_RESULT) != 0
                && sourceRecord != null) {
            // Transfer the result target from the source activity to the new
            // one being started, including any failures.
            if (requestCode >= 0) {
                return START_FORWARD_AND_REQUEST_CONFLICT;
            }
            resultRecord = sourceRecord.resultTo;
            resultWho = sourceRecord.resultWho;
            requestCode = sourceRecord.requestCode;
            sourceRecord.resultTo = null;
            if (resultRecord != null) {
                resultRecord.removeResultsLocked(
                    sourceRecord, resultWho, requestCode);
            }
        }

        int err = START_SUCCESS;

        if (intent.getComponent() == null) {
            // We couldn't find a class that can handle the given Intent.
            // That's the end of that!
            err = START_INTENT_NOT_RESOLVED;
        }

        if (err == START_SUCCESS && aInfo == null) {
            // We couldn't find the specific class specified in the Intent.
            // Also the end of the line.
            err = START_CLASS_NOT_FOUND;
        }

        ProcessRecord callerApp = null;
        if (err == START_SUCCESS && caller != null) {
            callerApp = getRecordForAppLocked(caller);
            if (callerApp != null) {
                callingPid = callerApp.pid;
                callingUid = callerApp.info.uid;
            } else {
                Log.w(TAG, "Unable to find app for caller " + caller
                      + " (pid=" + callingPid + ") when starting: "
                      + intent.toString());
                err = START_PERMISSION_DENIED;
            }
        }

        if (err != START_SUCCESS) {
            if (resultRecord != null) {
                sendActivityResultLocked(-1,
                    resultRecord, resultWho, requestCode,
                    Activity.RESULT_CANCELED, null);
            }
            return err;
        }

        final int perm = checkComponentPermission(aInfo.permission, callingPid,
                callingUid, aInfo.exported ? -1 : aInfo.applicationInfo.uid);
        if (perm != PackageManager.PERMISSION_GRANTED) {
            if (resultRecord != null) {
                sendActivityResultLocked(-1,
                    resultRecord, resultWho, requestCode,
                    Activity.RESULT_CANCELED, null);
            }
            String msg = "Permission Denial: starting " + intent.toString()
                    + " from " + callerApp + " (pid=" + callingPid
                    + ", uid=" + callingUid + ")"
                    + " requires " + aInfo.permission;
            Log.w(TAG, msg);
            throw new SecurityException(msg);
        }

        if (mWatcher != null) {
            boolean abort = false;
            try {
                // The Intent we give to the watcher has the extra data
                // stripped off, since it can contain private information.
                Intent watchIntent = intent.cloneFilter();
                abort = !mWatcher.activityStarting(watchIntent,
                        aInfo.applicationInfo.packageName);
            } catch (RemoteException e) {
                mWatcher = null;
            }

            if (abort) {
                if (resultRecord != null) {
                    sendActivityResultLocked(-1,
                        resultRecord, resultWho, requestCode,
                        Activity.RESULT_CANCELED, null);
                }
                // We pretend to the caller that it was really started, but
                // they will just get a cancel result.
                return START_SUCCESS;
            }
        }

        HistoryRecord r = new HistoryRecord(this, callerApp, callingUid,
                intent, resolvedType, aInfo, mConfiguration,
                resultRecord, resultWho, requestCode, componentSpecified);
        r.startTime = SystemClock.uptimeMillis();

        HistoryRecord notTop = (launchFlags&Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP)
                != 0 ? r : null;

        // We'll invoke onUserLeaving before onPause only if the launching
        // activity did not explicitly state that this is an automated launch.
        mUserLeaving = (launchFlags&Intent.FLAG_ACTIVITY_NO_USER_ACTION) == 0;
        if (DEBUG_USER_LEAVING) Log.v(TAG,
                "startActivity() => mUserLeaving=" + mUserLeaving);
        
        // If the onlyIfNeeded flag is set, then we can do this if the activity
        // being launched is the same as the one making the call...  or, as
        // a special case, if we do not know the caller then we count the
        // current top activity as the caller.
        if (onlyIfNeeded) {
            HistoryRecord checkedCaller = sourceRecord;
            if (checkedCaller == null) {
                checkedCaller = topRunningActivityLocked(notTop);
            }
            if (!checkedCaller.realActivity.equals(r.realActivity)) {
                // Caller is not the same as launcher, so always needed.
                onlyIfNeeded = false;
            }
        }

        if (grantedUriPermissions != null && callingUid > 0) {
            for (int i=0; i<grantedUriPermissions.length; i++) {
                grantUriPermissionLocked(callingUid, r.packageName,
                        grantedUriPermissions[i], grantedMode, r);
            }
        }

        grantUriPermissionFromIntentLocked(callingUid, r.packageName,
                intent, r);

        if (sourceRecord == null) {
            // This activity is not being started from another...  in this
            // case we -always- start a new task.
            if ((launchFlags&Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
                Log.w(TAG, "startActivity called from non-Activity context; forcing Intent.FLAG_ACTIVITY_NEW_TASK for: "
                      + intent);
                launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
            }
        } else if (sourceRecord.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
            // The original activity who is starting us is running as a single
            // instance...  this new activity it is starting must go on its
            // own task.
            launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
        } else if (r.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE
                || r.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK) {
            // The activity being started is a single instance...  it always
            // gets launched into its own task.
            launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
        }

        if (resultRecord != null && (launchFlags&Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
            // For whatever reason this activity is being launched into a new
            // task...  yet the caller has requested a result back.  Well, that
            // is pretty messed up, so instead immediately send back a cancel
            // and let the new task continue launched as normal without a
            // dependency on its originator.
            Log.w(TAG, "Activity is launching as a new task, so cancelling activity result.");
            sendActivityResultLocked(-1,
                resultRecord, resultWho, requestCode,
                Activity.RESULT_CANCELED, null);
            r.resultTo = null;
            resultRecord = null;
        }

        boolean addingToTask = false;
        if (((launchFlags&Intent.FLAG_ACTIVITY_NEW_TASK) != 0 &&
                (launchFlags&Intent.FLAG_ACTIVITY_MULTIPLE_TASK) == 0)
                || r.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK
                || r.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
            // If bring to front is requested, and no result is requested, and
            // we can find a task that was started with this same
            // component, then instead of launching bring that one to the front.
            if (resultRecord == null) {
                // See if there is a task to bring to the front.  If this is
                // a SINGLE_INSTANCE activity, there can be one and only one
                // instance of it in the history, and it is always in its own
                // unique task, so we do a special search.
                HistoryRecord taskTop = r.launchMode != ActivityInfo.LAUNCH_SINGLE_INSTANCE
                        ? findTaskLocked(intent, r.info)
                        : findActivityLocked(intent, r.info);
                if (taskTop != null) {
                    if (taskTop.task.intent == null) {
                        // This task was started because of movement of
                        // the activity based on affinity...  now that we
                        // are actually launching it, we can assign the
                        // base intent.
                        taskTop.task.setIntent(intent, r.info);
                    }
                    // If the target task is not in the front, then we need
                    // to bring it to the front...  except...  well, with
                    // SINGLE_TASK_LAUNCH it's not entirely clear.  We'd like
                    // to have the same behavior as if a new instance was
                    // being started, which means not bringing it to the front
                    // if the caller is not itself in the front.
                    HistoryRecord curTop = topRunningActivityLocked(notTop);
                    if (curTop.task != taskTop.task) {
                        r.intent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
                        boolean callerAtFront = sourceRecord == null
                                || curTop.task == sourceRecord.task;
                        if (callerAtFront) {
                            // We really do want to push this one into the
                            // user's face, right now.
                            moveTaskToFrontLocked(taskTop.task);
                        }
                    }
                    // If the caller has requested that the target task be
                    // reset, then do so.
                    if ((launchFlags&Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
                        taskTop = resetTaskIfNeededLocked(taskTop, r);
                    }
                    if (onlyIfNeeded) {
                        // We don't need to start a new activity, and
                        // the client said not to do anything if that
                        // is the case, so this is it!  And for paranoia, make
                        // sure we have correctly resumed the top activity.
                        resumeTopActivityLocked(null);
                        return START_RETURN_INTENT_TO_CALLER;
                    }
                    if ((launchFlags&Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0
                            || r.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK
                            || r.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
                        // In this situation we want to remove all activities
                        // from the task up to the one being started.  In most
                        // cases this means we are resetting the task to its
                        // initial state.
                        HistoryRecord top = performClearTopTaskLocked(
                                taskTop.task.taskId, r, true);
                        if (top != null) {
                            if (top.frontOfTask) {
                                // Activity aliases may mean we use different
                                // intents for the top activity, so make sure
                                // the task now has the identity of the new
                                // intent.
                                top.task.setIntent(r.intent, r.info);
                            }
                            logStartActivity(LOG_AM_NEW_INTENT, r, top.task);
                            deliverNewIntentLocked(top, r.intent);
                        } else {
                            // A special case: we need to
                            // start the activity because it is not currently
                            // running, and the caller has asked to clear the
                            // current task to have this activity at the top.
                            addingToTask = true;
                            // Now pretend like this activity is being started
                            // by the top of its task, so it is put in the
                            // right place.
                            sourceRecord = taskTop;
                        }
                    } else if (r.realActivity.equals(taskTop.task.realActivity)) {
                        // In this case the top activity on the task is the
                        // same as the one being launched, so we take that
                        // as a request to bring the task to the foreground.
                        // If the top activity in the task is the root
                        // activity, deliver this new intent to it if it
                        // desires.
                        if ((launchFlags&Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0
                                && taskTop.realActivity.equals(r.realActivity)) {
                            logStartActivity(LOG_AM_NEW_INTENT, r, taskTop.task);
                            if (taskTop.frontOfTask) {
                                taskTop.task.setIntent(r.intent, r.info);
                            }
                            deliverNewIntentLocked(taskTop, r.intent);
                        } else if (!r.intent.filterEquals(taskTop.task.intent)) {
                            // In this case we are launching the root activity
                            // of the task, but with a different intent.  We
                            // should start a new instance on top.
                            addingToTask = true;
                            sourceRecord = taskTop;
                        }
                    } else if ((launchFlags&Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) == 0) {
                        // In this case an activity is being launched in to an
                        // existing task, without resetting that task.  This
                        // is typically the situation of launching an activity
                        // from a notification or shortcut.  We want to place
                        // the new activity on top of the current task.
                        addingToTask = true;
                        sourceRecord = taskTop;
                    } else if (!taskTop.task.rootWasReset) {
                        // In this case we are launching in to an existing task
                        // that has not yet been started from its front door.
                        // The current task has been brought to the front.
                        // Ideally, we'd probably like to place this new task
                        // at the bottom of its stack, but that's a little hard
                        // to do with the current organization of the code so
                        // for now we'll just drop it.
                        taskTop.task.setIntent(r.intent, r.info);
                    }
                    if (!addingToTask) {
                        // We didn't do anything...  but it was needed (a.k.a., client
                        // don't use that intent!)  And for paranoia, make
                        // sure we have correctly resumed the top activity.
                        resumeTopActivityLocked(null);
                        return START_TASK_TO_FRONT;
                    }
                }
            }
        }

        //String uri = r.intent.toURI();
        //Intent intent2 = new Intent(uri);
        //Log.i(TAG, "Given intent: " + r.intent);
        //Log.i(TAG, "URI is: " + uri);
        //Log.i(TAG, "To intent: " + intent2);

        if (r.packageName != null) {
            // If the activity being launched is the same as the one currently
            // at the top, then we need to check if it should only be launched
            // once.
            HistoryRecord top = topRunningActivityLocked(notTop);
            if (top != null && resultRecord == null) {
                if (top.realActivity.equals(r.realActivity)) {
                    if (top.app != null && top.app.thread != null) {
                        if ((launchFlags&Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0
                            || r.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP
                            || r.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK) {
                            logStartActivity(LOG_AM_NEW_INTENT, top, top.task);
                            // For paranoia, make sure we have correctly
                            // resumed the top activity.
                            resumeTopActivityLocked(null);
                            if (onlyIfNeeded) {
                                // We don't need to start a new activity, and
                                // the client said not to do anything if that
                                // is the case, so this is it!
                                return START_RETURN_INTENT_TO_CALLER;
                            }
                            deliverNewIntentLocked(top, r.intent);
                            return START_DELIVERED_TO_TOP;
                        }
                    }
                }
            }

        } else {
            if (resultRecord != null) {
                sendActivityResultLocked(-1,
                    resultRecord, resultWho, requestCode,
                    Activity.RESULT_CANCELED, null);
            }
            return START_CLASS_NOT_FOUND;
        }

        boolean newTask = false;

        // Should this be considered a new task?
        if (resultRecord == null && !addingToTask
                && (launchFlags&Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
            // todo: should do better management of integers.
            mCurTask++;
            if (mCurTask <= 0) {
                mCurTask = 1;
            }
            r.task = new TaskRecord(mCurTask, r.info, intent,
                    (r.info.flags&ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH) != 0);
            if (DEBUG_TASKS) Log.v(TAG, "Starting new activity " + r
                    + " in new task " + r.task);
            newTask = true;
            addRecentTask(r.task);
            
        } else if (sourceRecord != null) {
            if (!addingToTask &&
                    (launchFlags&Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0) {
                // In this case, we are adding the activity to an existing
                // task, but the caller has asked to clear that task if the
                // activity is already running.
                HistoryRecord top = performClearTopTaskLocked(
                        sourceRecord.task.taskId, r, true);
                if (top != null) {
                    logStartActivity(LOG_AM_NEW_INTENT, r, top.task);
                    deliverNewIntentLocked(top, r.intent);
                    // For paranoia, make sure we have correctly
                    // resumed the top activity.
                    resumeTopActivityLocked(null);
                    return START_DELIVERED_TO_TOP;
                }
            } else if (!addingToTask &&
                    (launchFlags&Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) != 0) {
                // In this case, we are launching an activity in our own task
                // that may already be running somewhere in the history, and
                // we want to shuffle it to the front of the stack if so.
                int where = findActivityInHistoryLocked(r, sourceRecord.task.taskId);
                if (where >= 0) {
                    HistoryRecord top = moveActivityToFrontLocked(where);
                    logStartActivity(LOG_AM_NEW_INTENT, r, top.task);
                    deliverNewIntentLocked(top, r.intent);
                    resumeTopActivityLocked(null);
                    return START_DELIVERED_TO_TOP;
                }
            }
            // An existing activity is starting this new activity, so we want
            // to keep the new one in the same task as the one that is starting
            // it.
            r.task = sourceRecord.task;
            if (DEBUG_TASKS) Log.v(TAG, "Starting new activity " + r
                    + " in existing task " + r.task);

        } else {
            // This not being started from an existing activity, and not part
            // of a new task...  just put it in the top task, though these days
            // this case should never happen.
            final int N = mHistory.size();
            HistoryRecord prev =
                N > 0 ? (HistoryRecord)mHistory.get(N-1) : null;
            r.task = prev != null
                ? prev.task
                : new TaskRecord(mCurTask, r.info, intent,
                        (r.info.flags&ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH) != 0);
            if (DEBUG_TASKS) Log.v(TAG, "Starting new activity " + r
                    + " in new guessed " + r.task);
        }
        if (newTask) {
            EventLog.writeEvent(LOG_AM_CREATE_TASK, r.task.taskId);
        }
        logStartActivity(LOG_AM_CREATE_ACTIVITY, r, r.task);
        startActivityLocked(r, newTask);
        return START_SUCCESS;
    }

    public final int startActivity(IApplicationThread caller,
            Intent intent, String resolvedType, Uri[] grantedUriPermissions,
            int grantedMode, IBinder resultTo,
            String resultWho, int requestCode, boolean onlyIfNeeded,
            boolean debug) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        final boolean componentSpecified = intent.getComponent() != null;
        
        // Don't modify the client's object!
        intent = new Intent(intent);

        // Collect information about the target of the Intent.
        // Must do this before locking, because resolving the intent
        // may require launching a process to run its content provider.
        ActivityInfo aInfo;
        try {
            ResolveInfo rInfo =
                ActivityThread.getPackageManager().resolveIntent(
                        intent, resolvedType,
                        PackageManager.MATCH_DEFAULT_ONLY
                        | PackageManager.GET_SHARED_LIBRARY_FILES);
            aInfo = rInfo != null ? rInfo.activityInfo : null;
        } catch (RemoteException e) {
            aInfo = null;
        }

        if (aInfo != null) {
            // Store the found target back into the intent, because now that
            // we have it we never want to do this again.  For example, if the
            // user navigates back to this point in the history, we should
            // always restart the exact same activity.
            intent.setComponent(new ComponentName(
                    aInfo.applicationInfo.packageName, aInfo.name));

            // Don't debug things in the system process
            if (debug) {
                if (!aInfo.processName.equals("system")) {
                    setDebugApp(aInfo.processName, true, false);
                }
            }
        }

        synchronized(this) {
            final long origId = Binder.clearCallingIdentity();
            int res = startActivityLocked(caller, intent, resolvedType,
                    grantedUriPermissions, grantedMode, aInfo,
                    resultTo, resultWho, requestCode, -1, -1,
                    onlyIfNeeded, componentSpecified);
            Binder.restoreCallingIdentity(origId);
            return res;
        }
    }

    public boolean startNextMatchingActivity(IBinder callingActivity,
            Intent intent) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized (this) {
            int index = indexOfTokenLocked(callingActivity, false);
            if (index < 0) {
                return false;
            }
            HistoryRecord r = (HistoryRecord)mHistory.get(index);
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
                    ActivityThread.getPackageManager().queryIntentActivities(
                            intent, r.resolvedType,
                            PackageManager.MATCH_DEFAULT_ONLY
                            | PackageManager.GET_SHARED_LIBRARY_FILES);

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
            final HistoryRecord resultTo = r.resultTo;
            final String resultWho = r.resultWho;
            final int requestCode = r.requestCode;
            r.resultTo = null;
            if (resultTo != null) {
                resultTo.removeResultsLocked(r, resultWho, requestCode);
            }

            final long origId = Binder.clearCallingIdentity();
            // XXX we are not dealing with propagating grantedUriPermissions...
            // those are not yet exposed to user code, so there is no need.
            int res = startActivityLocked(r.app.thread, intent,
                    r.resolvedType, null, 0, aInfo, resultTo, resultWho,
                    requestCode, -1, r.launchedFromUid, false, false);
            Binder.restoreCallingIdentity(origId);

            r.finishing = wasFinishing;
            if (res != START_SUCCESS) {
                return false;
            }
            return true;
        }
    }

    final int startActivityInPackage(int uid,
            Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, boolean onlyIfNeeded) {
        final boolean componentSpecified = intent.getComponent() != null;
        
        // Don't modify the client's object!
        intent = new Intent(intent);

        // Collect information about the target of the Intent.
        // Must do this before locking, because resolving the intent
        // may require launching a process to run its content provider.
        ActivityInfo aInfo;
        try {
            ResolveInfo rInfo =
                ActivityThread.getPackageManager().resolveIntent(
                        intent, resolvedType,
                        PackageManager.MATCH_DEFAULT_ONLY
                        | PackageManager.GET_SHARED_LIBRARY_FILES);
            aInfo = rInfo != null ? rInfo.activityInfo : null;
        } catch (RemoteException e) {
            aInfo = null;
        }

        if (aInfo != null) {
            // Store the found target back into the intent, because now that
            // we have it we never want to do this again.  For example, if the
            // user navigates back to this point in the history, we should
            // always restart the exact same activity.
            intent.setComponent(new ComponentName(
                    aInfo.applicationInfo.packageName, aInfo.name));
        }

        synchronized(this) {
            return startActivityLocked(null, intent, resolvedType,
                    null, 0, aInfo, resultTo, resultWho, requestCode, -1, uid,
                    onlyIfNeeded, componentSpecified);
        }
    }

    private final void addRecentTask(TaskRecord task) {
        // Remove any existing entries that are the same kind of task.
        int N = mRecentTasks.size();
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
            int index = indexOfTokenLocked(token, false);
            if (index < 0) {
                return;
            }
            HistoryRecord r = (HistoryRecord)mHistory.get(index);
            final long origId = Binder.clearCallingIdentity();
            mWindowManager.setAppOrientation(r, requestedOrientation);
            Configuration config = mWindowManager.updateOrientationFromAppTokens(
                    mConfiguration,
                    r.mayFreezeScreenLocked(r.app) ? r : null);
            if (config != null) {
                r.frozenBeforeDestroy = true;
                if (!updateConfigurationLocked(config, r)) {
                    resumeTopActivityLocked(null);
                }
            }
            Binder.restoreCallingIdentity(origId);
        }
    }

    public int getRequestedOrientation(IBinder token) {
        synchronized (this) {
            int index = indexOfTokenLocked(token, false);
            if (index < 0) {
                return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            }
            HistoryRecord r = (HistoryRecord)mHistory.get(index);
            return mWindowManager.getAppOrientation(r);
        }
    }

    private final void stopActivityLocked(HistoryRecord r) {
        if (DEBUG_SWITCH) Log.d(TAG, "Stopping: " + r);
        if ((r.intent.getFlags()&Intent.FLAG_ACTIVITY_NO_HISTORY) != 0
                || (r.info.flags&ActivityInfo.FLAG_NO_HISTORY) != 0) {
            if (!r.finishing) {
                requestFinishActivityLocked(r, Activity.RESULT_CANCELED, null,
                        "no-history");
            }
        } else if (r.app != null && r.app.thread != null) {
            if (mFocusedActivity == r) {
                setFocusedActivityLocked(topRunningActivityLocked(null));
            }
            r.resumeKeyDispatchingLocked();
            try {
                r.stopped = false;
                r.state = ActivityState.STOPPING;
                if (DEBUG_VISBILITY) Log.v(
                        TAG, "Stopping visible=" + r.visible + " for " + r);
                if (!r.visible) {
                    mWindowManager.setAppVisibility(r, false);
                }
                r.app.thread.scheduleStopActivity(r, r.visible, r.configChangeFlags);
            } catch (Exception e) {
                // Maybe just ignore exceptions here...  if the process
                // has crashed, our death notification will clean things
                // up.
                Log.w(TAG, "Exception thrown during pause", e);
                // Just in case, assume it to be stopped.
                r.stopped = true;
                r.state = ActivityState.STOPPED;
                if (r.configDestroy) {
                    destroyActivityLocked(r, true);
                }
            }
        }
    }

    /**
     * @return Returns true if the activity is being finished, false if for
     * some reason it is being left as-is.
     */
    private final boolean requestFinishActivityLocked(IBinder token, int resultCode,
            Intent resultData, String reason) {
        if (DEBUG_RESULTS) Log.v(
            TAG, "Finishing activity: token=" + token
            + ", result=" + resultCode + ", data=" + resultData);

        int index = indexOfTokenLocked(token, false);
        if (index < 0) {
            return false;
        }
        HistoryRecord r = (HistoryRecord)mHistory.get(index);

        // Is this the last activity left?
        boolean lastActivity = true;
        for (int i=mHistory.size()-1; i>=0; i--) {
            HistoryRecord p = (HistoryRecord)mHistory.get(i);
            if (!p.finishing && p != r) {
                lastActivity = false;
                break;
            }
        }
        
        // If this is the last activity, but it is the home activity, then
        // just don't finish it.
        if (lastActivity) {
            if (r.intent.hasCategory(Intent.CATEGORY_HOME)) {
                return false;
            }
        }
        
        finishActivityLocked(r, index, resultCode, resultData, reason);
        return true;
    }

    /**
     * @return Returns true if this activity has been removed from the history
     * list, or false if it is still in the list and will be removed later.
     */
    private final boolean finishActivityLocked(HistoryRecord r, int index,
            int resultCode, Intent resultData, String reason) {
        if (r.finishing) {
            Log.w(TAG, "Duplicate finish request for " + r);
            return false;
        }

        r.finishing = true;
        EventLog.writeEvent(LOG_AM_FINISH_ACTIVITY,
                System.identityHashCode(r),
                r.task.taskId, r.shortComponentName, reason);
        r.task.numActivities--;
        if (r.frontOfTask && index < (mHistory.size()-1)) {
            HistoryRecord next = (HistoryRecord)mHistory.get(index+1);
            if (next.task == r.task) {
                next.frontOfTask = true;
            }
        }

        r.pauseKeyDispatchingLocked();
        if (mFocusedActivity == r) {
            setFocusedActivityLocked(topRunningActivityLocked(null));
        }

        // send the result
        HistoryRecord resultTo = r.resultTo;
        if (resultTo != null) {
            if (DEBUG_RESULTS) Log.v(TAG, "Adding result to " + resultTo
                    + " who=" + r.resultWho + " req=" + r.requestCode
                    + " res=" + resultCode + " data=" + resultData);
            if (r.info.applicationInfo.uid > 0) {
                grantUriPermissionFromIntentLocked(r.info.applicationInfo.uid,
                        r.packageName, resultData, r);
            }
            resultTo.addResultLocked(r, r.resultWho, r.requestCode, resultCode,
                                     resultData);
            r.resultTo = null;
        }
        else if (DEBUG_RESULTS) Log.v(TAG, "No result destination from " + r);

        // Make sure this HistoryRecord is not holding on to other resources,
        // because clients have remote IPC references to this object so we
        // can't assume that will go away and want to avoid circular IPC refs.
        r.results = null;
        r.pendingResults = null;
        r.newIntents = null;
        r.icicle = null;
        
        if (mPendingThumbnails.size() > 0) {
            // There are clients waiting to receive thumbnails so, in case
            // this is an activity that someone is waiting for, add it
            // to the pending list so we can correctly update the clients.
            mCancelledThumbnails.add(r);
        }

        if (mResumedActivity == r) {
            boolean endTask = index <= 0
                    || ((HistoryRecord)mHistory.get(index-1)).task != r.task;
            if (DEBUG_TRANSITION) Log.v(TAG,
                    "Prepare close transition: finishing " + r);
            mWindowManager.prepareAppTransition(endTask
                    ? WindowManagerPolicy.TRANSIT_TASK_CLOSE
                    : WindowManagerPolicy.TRANSIT_ACTIVITY_CLOSE);
    
            // Tell window manager to prepare for this one to be removed.
            mWindowManager.setAppVisibility(r, false);
                
            if (mPausingActivity == null) {
                if (DEBUG_PAUSE) Log.v(TAG, "Finish needs to pause: " + r);
                if (DEBUG_USER_LEAVING) Log.v(TAG, "finish() => pause with userLeaving=false");
                startPausingLocked(false, false);
            }

        } else if (r.state != ActivityState.PAUSING) {
            // If the activity is PAUSING, we will complete the finish once
            // it is done pausing; else we can just directly finish it here.
            if (DEBUG_PAUSE) Log.v(TAG, "Finish not pausing: " + r);
            return finishCurrentActivityLocked(r, index,
                    FINISH_AFTER_PAUSE) == null;
        } else {
            if (DEBUG_PAUSE) Log.v(TAG, "Finish waiting for pause of: " + r);
        }

        return false;
    }

    private static final int FINISH_IMMEDIATELY = 0;
    private static final int FINISH_AFTER_PAUSE = 1;
    private static final int FINISH_AFTER_VISIBLE = 2;

    private final HistoryRecord finishCurrentActivityLocked(HistoryRecord r,
            int mode) {
        final int index = indexOfTokenLocked(r, false);
        if (index < 0) {
            return null;
        }

        return finishCurrentActivityLocked(r, index, mode);
    }

    private final HistoryRecord finishCurrentActivityLocked(HistoryRecord r,
            int index, int mode) {
        // First things first: if this activity is currently visible,
        // and the resumed activity is not yet visible, then hold off on
        // finishing until the resumed one becomes visible.
        if (mode == FINISH_AFTER_VISIBLE && r.nowVisible) {
            if (!mStoppingActivities.contains(r)) {
                mStoppingActivities.add(r);
                if (mStoppingActivities.size() > 3) {
                    // If we already have a few activities waiting to stop,
                    // then give up on things going idle and start clearing
                    // them out.
                    Message msg = Message.obtain();
                    msg.what = ActivityManagerService.IDLE_NOW_MSG;
                    mHandler.sendMessage(msg);
                }
            }
            r.state = ActivityState.STOPPING;
            updateOomAdjLocked();
            return r;
        }

        // make sure the record is cleaned out of other places.
        mStoppingActivities.remove(r);
        mWaitingVisibleActivities.remove(r);
        if (mResumedActivity == r) {
            mResumedActivity = null;
        }
        final ActivityState prevState = r.state;
        r.state = ActivityState.FINISHING;

        if (mode == FINISH_IMMEDIATELY
                || prevState == ActivityState.STOPPED
                || prevState == ActivityState.INITIALIZING) {
            // If this activity is already stopped, we can just finish
            // it right now.
            return destroyActivityLocked(r, true) ? null : r;
        } else {
            // Need to go through the full pause cycle to get this
            // activity into the stopped state and then finish it.
            if (localLOGV) Log.v(TAG, "Enqueueing pending finish: " + r);
            mFinishingActivities.add(r);
            resumeTopActivityLocked(null);
        }
        return r;
    }

    /**
     * This is the internal entry point for handling Activity.finish().
     * 
     * @param token The Binder token referencing the Activity we want to finish.
     * @param resultCode Result code, if any, from this Activity.
     * @param resultData Result data (Intent), if any, from this Activity.
     * 
     * @result Returns true if the activity successfully finished, or false if it is still running.
     */
    public final boolean finishActivity(IBinder token, int resultCode, Intent resultData) {
        // Refuse possible leaked file descriptors
        if (resultData != null && resultData.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            if (mWatcher != null) {
                // Find the first activity that is not finishing.
                HistoryRecord next = topRunningActivityLocked(token, 0);
                if (next != null) {
                    // ask watcher if this is allowed
                    boolean resumeOK = true;
                    try {
                        resumeOK = mWatcher.activityResuming(next.packageName);
                    } catch (RemoteException e) {
                        mWatcher = null;
                    }
    
                    if (!resumeOK) {
                        return false;
                    }
                }
            }
            final long origId = Binder.clearCallingIdentity();
            boolean res = requestFinishActivityLocked(token, resultCode,
                    resultData, "app-request");
            Binder.restoreCallingIdentity(origId);
            return res;
        }
    }

    void sendActivityResultLocked(int callingUid, HistoryRecord r,
            String resultWho, int requestCode, int resultCode, Intent data) {

        if (callingUid > 0) {
            grantUriPermissionFromIntentLocked(callingUid, r.packageName,
                    data, r);
        }

        if (DEBUG_RESULTS) Log.v(TAG, "Send activity result to " + r
                + " : who=" + resultWho + " req=" + requestCode
                + " res=" + resultCode + " data=" + data);
        if (mResumedActivity == r && r.app != null && r.app.thread != null) {
            try {
                ArrayList<ResultInfo> list = new ArrayList<ResultInfo>();
                list.add(new ResultInfo(resultWho, requestCode,
                        resultCode, data));
                r.app.thread.scheduleSendResult(r, list);
                return;
            } catch (Exception e) {
                Log.w(TAG, "Exception thrown sending result to " + r, e);
            }
        }

        r.addResultLocked(null, resultWho, requestCode, resultCode, data);
    }

    public final void finishSubActivity(IBinder token, String resultWho,
            int requestCode) {
        synchronized(this) {
            int index = indexOfTokenLocked(token, false);
            if (index < 0) {
                return;
            }
            HistoryRecord self = (HistoryRecord)mHistory.get(index);

            final long origId = Binder.clearCallingIdentity();

            int i;
            for (i=mHistory.size()-1; i>=0; i--) {
                HistoryRecord r = (HistoryRecord)mHistory.get(i);
                if (r.resultTo == self && r.requestCode == requestCode) {
                    if ((r.resultWho == null && resultWho == null) ||
                        (r.resultWho != null && r.resultWho.equals(resultWho))) {
                        finishActivityLocked(r, i,
                                Activity.RESULT_CANCELED, null, "request-sub");
                    }
                }
            }

            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Perform clean-up of service connections in an activity record.
     */
    private final void cleanUpActivityServicesLocked(HistoryRecord r) {
        // Throw away any services that have been bound by this activity.
        if (r.connections != null) {
            Iterator<ConnectionRecord> it = r.connections.iterator();
            while (it.hasNext()) {
                ConnectionRecord c = it.next();
                removeConnectionLocked(c, null, r);
            }
            r.connections = null;
        }
    }
    
    /**
     * Perform the common clean-up of an activity record.  This is called both
     * as part of destroyActivityLocked() (when destroying the client-side
     * representation) and cleaning things up as a result of its hosting
     * processing going away, in which case there is no remaining client-side
     * state to destroy so only the cleanup here is needed.
     */
    private final void cleanUpActivityLocked(HistoryRecord r, boolean cleanServices) {
        if (mResumedActivity == r) {
            mResumedActivity = null;
        }
        if (mFocusedActivity == r) {
            mFocusedActivity = null;
        }

        r.configDestroy = false;
        r.frozenBeforeDestroy = false;

        // Make sure this record is no longer in the pending finishes list.
        // This could happen, for example, if we are trimming activities
        // down to the max limit while they are still waiting to finish.
        mFinishingActivities.remove(r);
        mWaitingVisibleActivities.remove(r);
        
        // Remove any pending results.
        if (r.finishing && r.pendingResults != null) {
            for (WeakReference<PendingIntentRecord> apr : r.pendingResults) {
                PendingIntentRecord rec = apr.get();
                if (rec != null) {
                    cancelIntentSenderLocked(rec, false);
                }
            }
            r.pendingResults = null;
        }

        if (cleanServices) {
            cleanUpActivityServicesLocked(r);            
        }

        if (mPendingThumbnails.size() > 0) {
            // There are clients waiting to receive thumbnails so, in case
            // this is an activity that someone is waiting for, add it
            // to the pending list so we can correctly update the clients.
            mCancelledThumbnails.add(r);
        }

        // Get rid of any pending idle timeouts.
        mHandler.removeMessages(PAUSE_TIMEOUT_MSG, r);
        mHandler.removeMessages(IDLE_TIMEOUT_MSG, r);
    }

    private final void removeActivityFromHistoryLocked(HistoryRecord r) {
        if (r.state != ActivityState.DESTROYED) {
            mHistory.remove(r);
            r.inHistory = false;
            r.state = ActivityState.DESTROYED;
            mWindowManager.removeAppToken(r);
            if (VALIDATE_TOKENS) {
                mWindowManager.validateAppTokens(mHistory);
            }
            cleanUpActivityServicesLocked(r);
            removeActivityUriPermissionsLocked(r);
        }
    }
    
    /**
     * Destroy the current CLIENT SIDE instance of an activity.  This may be
     * called both when actually finishing an activity, or when performing
     * a configuration switch where we destroy the current client-side object
     * but then create a new client-side object for this same HistoryRecord.
     */
    private final boolean destroyActivityLocked(HistoryRecord r,
            boolean removeFromApp) {
        if (DEBUG_SWITCH) Log.v(
            TAG, "Removing activity: token=" + r
              + ", app=" + (r.app != null ? r.app.processName : "(null)"));
        EventLog.writeEvent(LOG_AM_DESTROY_ACTIVITY,
                System.identityHashCode(r),
                r.task.taskId, r.shortComponentName);

        boolean removedFromHistory = false;
        
        cleanUpActivityLocked(r, false);

        if (r.app != null) {
            if (removeFromApp) {
                int idx = r.app.activities.indexOf(r);
                if (idx >= 0) {
                    r.app.activities.remove(idx);
                }
                if (r.persistent) {
                    decPersistentCountLocked(r.app);
                }
            }

            boolean skipDestroy = false;
            
            try {
                if (DEBUG_SWITCH) Log.i(TAG, "Destroying: " + r);
                r.app.thread.scheduleDestroyActivity(r, r.finishing,
                        r.configChangeFlags);
            } catch (Exception e) {
                // We can just ignore exceptions here...  if the process
                // has crashed, our death notification will clean things
                // up.
                //Log.w(TAG, "Exception thrown during finish", e);
                if (r.finishing) {
                    removeActivityFromHistoryLocked(r);
                    removedFromHistory = true;
                    skipDestroy = true;
                }
            }

            r.app = null;
            r.nowVisible = false;
            
            if (r.finishing && !skipDestroy) {
                r.state = ActivityState.DESTROYING;
                Message msg = mHandler.obtainMessage(DESTROY_TIMEOUT_MSG);
                msg.obj = r;
                mHandler.sendMessageDelayed(msg, DESTROY_TIMEOUT);
            } else {
                r.state = ActivityState.DESTROYED;
            }
        } else {
            // remove this record from the history.
            if (r.finishing) {
                removeActivityFromHistoryLocked(r);
                removedFromHistory = true;
            } else {
                r.state = ActivityState.DESTROYED;
            }
        }

        r.configChangeFlags = 0;
        
        if (!mLRUActivities.remove(r)) {
            Log.w(TAG, "Activity " + r + " being finished, but not in LRU list");
        }
        
        return removedFromHistory;
    }

    private static void removeHistoryRecordsForAppLocked(ArrayList list,
                                                         ProcessRecord app)
    {
        int i = list.size();
        if (localLOGV) Log.v(
            TAG, "Removing app " + app + " from list " + list
            + " with " + i + " entries");
        while (i > 0) {
            i--;
            HistoryRecord r = (HistoryRecord)list.get(i);
            if (localLOGV) Log.v(
                TAG, "Record #" + i + " " + r + ": app=" + r.app);
            if (r.app == app) {
                if (localLOGV) Log.v(TAG, "Removing this entry!");
                list.remove(i);
            }
        }
    }

    /**
     * Main function for removing an existing process from the activity manager
     * as a result of that process going away.  Clears out all connections
     * to the process.
     */
    private final void handleAppDiedLocked(ProcessRecord app,
            boolean restarting) {
        cleanUpApplicationRecordLocked(app, restarting, -1);
        if (!restarting) {
            mLRUProcesses.remove(app);
        }

        // Just in case...
        if (mPausingActivity != null && mPausingActivity.app == app) {
            if (DEBUG_PAUSE) Log.v(TAG, "App died while pausing: " + mPausingActivity);
            mPausingActivity = null;
        }
        if (mLastPausedActivity != null && mLastPausedActivity.app == app) {
            mLastPausedActivity = null;
        }

        // Remove this application's activities from active lists.
        removeHistoryRecordsForAppLocked(mLRUActivities, app);
        removeHistoryRecordsForAppLocked(mStoppingActivities, app);
        removeHistoryRecordsForAppLocked(mWaitingVisibleActivities, app);
        removeHistoryRecordsForAppLocked(mFinishingActivities, app);

        boolean atTop = true;
        boolean hasVisibleActivities = false;

        // Clean out the history list.
        int i = mHistory.size();
        if (localLOGV) Log.v(
            TAG, "Removing app " + app + " from history with " + i + " entries");
        while (i > 0) {
            i--;
            HistoryRecord r = (HistoryRecord)mHistory.get(i);
            if (localLOGV) Log.v(
                TAG, "Record #" + i + " " + r + ": app=" + r.app);
            if (r.app == app) {
                if ((!r.haveState && !r.stateNotNeeded) || r.finishing) {
                    if (localLOGV) Log.v(
                        TAG, "Removing this entry!  frozen=" + r.haveState
                        + " finishing=" + r.finishing);
                    mHistory.remove(i);

                    r.inHistory = false;
                    mWindowManager.removeAppToken(r);
                    if (VALIDATE_TOKENS) {
                        mWindowManager.validateAppTokens(mHistory);
                    }
                    removeActivityUriPermissionsLocked(r);

                } else {
                    // We have the current state for this activity, so
                    // it can be restarted later when needed.
                    if (localLOGV) Log.v(
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

                cleanUpActivityLocked(r, true);
                r.state = ActivityState.STOPPED;
            }
            atTop = false;
        }

        app.activities.clear();
        
        if (app.instrumentationClass != null) {
            Log.w(TAG, "Crash of app " + app.processName
                  + " running instrumentation " + app.instrumentationClass);
            Bundle info = new Bundle();
            info.putString("shortMsg", "Process crashed.");
            finishInstrumentationLocked(app, Activity.RESULT_CANCELED, info);
        }

        if (!restarting) {
            if (!resumeTopActivityLocked(null)) {
                // If there was nothing to resume, and we are not already
                // restarting this process, but there is a visible activity that
                // is hosted by the process...  then make sure all visible
                // activities are running, taking care of restarting this
                // process.
                if (hasVisibleActivities) {
                    ensureActivitiesVisibleLocked(null, 0);
                }
            }
        }
    }

    private final int getLRURecordIndexForAppLocked(IApplicationThread thread) {
        IBinder threadBinder = thread.asBinder();

        // Find the application record.
        int count = mLRUProcesses.size();
        int i;
        for (i=0; i<count; i++) {
            ProcessRecord rec = mLRUProcesses.get(i);
            if (rec.thread != null && rec.thread.asBinder() == threadBinder) {
                return i;
            }
        }
        return -1;
    }

    private final ProcessRecord getRecordForAppLocked(
            IApplicationThread thread) {
        if (thread == null) {
            return null;
        }

        int appIndex = getLRURecordIndexForAppLocked(thread);
        return appIndex >= 0 ? mLRUProcesses.get(appIndex) : null;
    }

    private final void appDiedLocked(ProcessRecord app, int pid,
            IApplicationThread thread) {

        mProcDeaths[0]++;
        
        if (app.thread != null && app.thread.asBinder() == thread.asBinder()) {
            Log.i(TAG, "Process " + app.processName + " (pid " + pid
                    + ") has died.");
            EventLog.writeEvent(LOG_AM_PROCESS_DIED, app.pid, app.processName);
            if (localLOGV) Log.v(
                TAG, "Dying app: " + app + ", pid: " + pid
                + ", thread: " + thread.asBinder());
            boolean doLowMem = app.instrumentationClass == null;
            handleAppDiedLocked(app, false);

            if (doLowMem) {
                // If there are no longer any background processes running,
                // and the app that died was not running instrumentation,
                // then tell everyone we are now low on memory.
                boolean haveBg = false;
                int count = mLRUProcesses.size();
                int i;
                for (i=0; i<count; i++) {
                    ProcessRecord rec = mLRUProcesses.get(i);
                    if (rec.thread != null && rec.setAdj >= HIDDEN_APP_MIN_ADJ) {
                        haveBg = true;
                        break;
                    }
                }
                
                if (!haveBg) {
                    Log.i(TAG, "Low Memory: No more background processes.");
                    EventLog.writeEvent(LOG_AM_LOW_MEMORY, mLRUProcesses.size());
                    for (i=0; i<count; i++) {
                        ProcessRecord rec = mLRUProcesses.get(i);
                        if (rec.thread != null) {
                            rec.lastRequestedGc = SystemClock.uptimeMillis();
                            try {
                                rec.thread.scheduleLowMemory();
                            } catch (RemoteException e) {
                                // Don't care if the process is gone.
                            }
                        }
                    }
                }
            }
        } else if (Config.LOGD) {
            Log.d(TAG, "Received spurious death notification for thread "
                    + thread.asBinder());
        }
    }

    final String readFile(String filename) {
        try {
            FileInputStream fs = new FileInputStream(filename);
            byte[] inp = new byte[8192];
            int size = fs.read(inp);
            fs.close();
            return new String(inp, 0, 0, size);
        } catch (java.io.IOException e) {
        }
        return "";
    }

    final void appNotRespondingLocked(ProcessRecord app, HistoryRecord activity, 
            final String annotation) {
        if (app.notResponding || app.crashing) {
            return;
        }
        
        // Log the ANR to the event log.
        EventLog.writeEvent(LOG_ANR, app.pid, app.processName, annotation);
        
        // If we are on a secure build and the application is not interesting to the user (it is
        // not visible or in the background), just kill it instead of displaying a dialog.
        boolean isSecure = "1".equals(SystemProperties.get(SYSTEM_SECURE, "0"));
        if (isSecure && !app.isInterestingToUserLocked() && Process.myPid() != app.pid) {
            Process.killProcess(app.pid);
            return;
        }
        
        // DeviceMonitor.start();

        String processInfo = null;
        if (MONITOR_CPU_USAGE) {
            updateCpuStatsNow();
            synchronized (mProcessStatsThread) {
                processInfo = mProcessStats.printCurrentState();
            }
        }

        StringBuilder info = new StringBuilder();
        info.append("ANR (application not responding) in process: ");
        info.append(app.processName);
        if (annotation != null) {
            info.append("\nAnnotation: ");
            info.append(annotation);
        }
        if (MONITOR_CPU_USAGE) {
            info.append("\nCPU usage:\n");
            info.append(processInfo);
        }
        Log.i(TAG, info.toString());

        // The application is not responding. Dump as many thread traces as we can.
        boolean fileDump = prepareTraceFile(true);
        if (!fileDump) {
            // Dumping traces to the log, just dump the process that isn't responding so
            // we don't overflow the log
            Process.sendSignal(app.pid, Process.SIGNAL_QUIT);
        } else {
            // Dumping traces to a file so dump all active processes we know about
            synchronized (this) {
                for (int i = mLRUProcesses.size() - 1 ; i >= 0 ; i--) {
                    ProcessRecord r = mLRUProcesses.get(i);
                    if (r.thread != null) {
                        Process.sendSignal(r.pid, Process.SIGNAL_QUIT);
                    }
                }
            }
        }

        if (mWatcher != null) {
            try {
                int res = mWatcher.appNotResponding(app.processName,
                        app.pid, info.toString());
                if (res != 0) {
                    if (res < 0) {
                        // wait until the SIGQUIT has had a chance to process before killing the
                        // process.
                        try {
                            wait(2000);
                        } catch (InterruptedException e) {
                        }

                        Process.killProcess(app.pid);
                        return;
                    }
                }
            } catch (RemoteException e) {
                mWatcher = null;
            }
        }

        makeAppNotRespondingLocked(app,
                activity != null ? activity.shortComponentName : null,
                annotation != null ? "ANR " + annotation : "ANR",
                info.toString(), null);
        Message msg = Message.obtain();
        HashMap map = new HashMap();
        msg.what = SHOW_NOT_RESPONDING_MSG;
        msg.obj = map;
        map.put("app", app);
        if (activity != null) {
            map.put("activity", activity);
        }

        mHandler.sendMessage(msg);
        return;
    }

    /**
     * If a stack trace file has been configured, prepare the filesystem
     * by creating the directory if it doesn't exist and optionally
     * removing the old trace file.
     *
     * @param removeExisting If set, the existing trace file will be removed.
     * @return Returns true if the trace file preparations succeeded
     */
    public static boolean prepareTraceFile(boolean removeExisting) {
        String tracesPath = SystemProperties.get("dalvik.vm.stack-trace-file", null);
        boolean fileReady = false;
        if (!TextUtils.isEmpty(tracesPath)) {
            File f = new File(tracesPath);
            if (!f.exists()) {
                // Ensure the enclosing directory exists
                File dir = f.getParentFile();
                if (!dir.exists()) {
                    fileReady = dir.mkdirs();
                    FileUtils.setPermissions(dir.getAbsolutePath(),
                            FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IRWXO, -1, -1);
                } else if (dir.isDirectory()) {
                    fileReady = true;
                }
            } else if (removeExisting) {
                // Remove the previous traces file, so we don't fill the disk.
                // The VM will recreate it
                Log.i(TAG, "Removing old ANR trace file from " + tracesPath);
                fileReady = f.delete();
            }
        }

        return fileReady;
    }


    private final void decPersistentCountLocked(ProcessRecord app)
    {
        app.persistentActivities--;
        if (app.persistentActivities > 0) {
            // Still more of 'em...
            return;
        }
        if (app.persistent) {
            // Ah, but the application itself is persistent.  Whatever!
            return;
        }

        // App is no longer persistent...  make sure it and the ones
        // following it in the LRU list have the correc oom_adj.
        updateOomAdjLocked();
    }

    public void setPersistent(IBinder token, boolean isPersistent) {
        if (checkCallingPermission(android.Manifest.permission.PERSISTENT_ACTIVITY)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: setPersistent() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.PERSISTENT_ACTIVITY;
            Log.w(TAG, msg);
            throw new SecurityException(msg);
        }

        synchronized(this) {
            int index = indexOfTokenLocked(token, true);
            if (index < 0) {
                return;
            }
            HistoryRecord r = (HistoryRecord)mHistory.get(index);
            ProcessRecord app = r.app;

            if (localLOGV) Log.v(
                TAG, "Setting persistence " + isPersistent + ": " + r);

            if (isPersistent) {
                if (r.persistent) {
                    // Okay okay, I heard you already!
                    if (localLOGV) Log.v(TAG, "Already persistent!");
                    return;
                }
                r.persistent = true;
                app.persistentActivities++;
                if (localLOGV) Log.v(TAG, "Num persistent now: " + app.persistentActivities);
                if (app.persistentActivities > 1) {
                    // We aren't the first...
                    if (localLOGV) Log.v(TAG, "Not the first!");
                    return;
                }
                if (app.persistent) {
                    // This would be redundant.
                    if (localLOGV) Log.v(TAG, "App is persistent!");
                    return;
                }

                // App is now persistent...  make sure it and the ones
                // following it now have the correct oom_adj.
                final long origId = Binder.clearCallingIdentity();
                updateOomAdjLocked();
                Binder.restoreCallingIdentity(origId);

            } else {
                if (!r.persistent) {
                    // Okay okay, I heard you already!
                    return;
                }
                r.persistent = false;
                final long origId = Binder.clearCallingIdentity();
                decPersistentCountLocked(app);
                Binder.restoreCallingIdentity(origId);

            }
        }
    }
    
    public boolean clearApplicationUserData(final String packageName,
            final IPackageDataObserver observer) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = ActivityThread.getPackageManager();
            int pkgUid = -1;
            synchronized(this) {
                try {
                    pkgUid = pm.getPackageUid(packageName);
                } catch (RemoteException e) {
                }
                if (pkgUid == -1) {
                    Log.w(TAG, "Invalid packageName:" + packageName);
                    return false;
                }
                if (uid == pkgUid || checkComponentPermission(
                        android.Manifest.permission.CLEAR_APP_USER_DATA,
                        pid, uid, -1)
                        == PackageManager.PERMISSION_GRANTED) {
                    restartPackageLocked(packageName, pkgUid);
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
                broadcastIntentLocked(null, null, intent,
                        null, null, 0, null, null, null,
                        false, false, MY_PID, Process.SYSTEM_UID);
            } catch (RemoteException e) {
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
        return true;
    }

    public void restartPackage(final String packageName) {
        if (checkCallingPermission(android.Manifest.permission.RESTART_PACKAGES)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: restartPackage() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.RESTART_PACKAGES;
            Log.w(TAG, msg);
            throw new SecurityException(msg);
        }
        
        long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = ActivityThread.getPackageManager();
            int pkgUid = -1;
            synchronized(this) {
                try {
                    pkgUid = pm.getPackageUid(packageName);
                } catch (RemoteException e) {
                }
                if (pkgUid == -1) {
                    Log.w(TAG, "Invalid packageName: " + packageName);
                    return;
                }
                restartPackageLocked(packageName, pkgUid);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }
    
    private void restartPackageLocked(final String packageName, int uid) {
        uninstallPackageLocked(packageName, uid, false);
        Intent intent = new Intent(Intent.ACTION_PACKAGE_RESTARTED,
                Uri.fromParts("package", packageName, null));
        intent.putExtra(Intent.EXTRA_UID, uid);
        broadcastIntentLocked(null, null, intent,
                null, null, 0, null, null, null,
                false, false, MY_PID, Process.SYSTEM_UID);
    }
    
    private final void uninstallPackageLocked(String name, int uid,
            boolean callerWillRestart) {
        if (Config.LOGD) Log.d(TAG, "Uninstalling process " + name);

        int i, N;

        final String procNamePrefix = name + ":";
        if (uid < 0) {
            try {
                uid = ActivityThread.getPackageManager().getPackageUid(name);
            } catch (RemoteException e) {
            }
        }

        Iterator<SparseArray<Long>> badApps = mProcessCrashTimes.getMap().values().iterator();
        while (badApps.hasNext()) {
            SparseArray<Long> ba = badApps.next();
            if (ba.get(uid) != null) {
                badApps.remove();
            }
        }

        ArrayList<ProcessRecord> procs = new ArrayList<ProcessRecord>();

        // Remove all processes this package may have touched: all with the
        // same UID (except for the system or root user), and all whose name
        // matches the package name.
        for (SparseArray<ProcessRecord> apps : mProcessNames.getMap().values()) {
            final int NA = apps.size();
            for (int ia=0; ia<NA; ia++) {
                ProcessRecord app = apps.valueAt(ia);
                if (app.removed) {
                    procs.add(app);
                } else if ((uid > 0 && uid != Process.SYSTEM_UID && app.info.uid == uid)
                        || app.processName.equals(name)
                        || app.processName.startsWith(procNamePrefix)) {
                    app.removed = true;
                    procs.add(app);
                }
            }
        }

        N = procs.size();
        for (i=0; i<N; i++) {
            removeProcessLocked(procs.get(i), callerWillRestart);
        }
        
        for (i=mHistory.size()-1; i>=0; i--) {
            HistoryRecord r = (HistoryRecord)mHistory.get(i);
            if (r.packageName.equals(name)) {
                if (Config.LOGD) Log.d(
                    TAG, "  Force finishing activity "
                    + r.intent.getComponent().flattenToShortString());
                if (r.app != null) {
                    r.app.removed = true;
                }
                r.app = null;
                finishActivityLocked(r, i, Activity.RESULT_CANCELED, null, "uninstall");
            }
        }

        ArrayList<ServiceRecord> services = new ArrayList<ServiceRecord>();
        for (ServiceRecord service : mServices.values()) {
            if (service.packageName.equals(name)) {
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
        
        resumeTopActivityLocked(null);
    }

    private final boolean removeProcessLocked(ProcessRecord app, boolean callerWillRestart) {
        final String name = app.processName;
        final int uid = app.info.uid;
        if (Config.LOGD) Log.d(
            TAG, "Force removing process " + app + " (" + name
            + "/" + uid + ")");

        mProcessNames.remove(name, uid);
        boolean needRestart = false;
        if (app.pid > 0 && app.pid != MY_PID) {
            int pid = app.pid;
            synchronized (mPidsSelfLocked) {
                mPidsSelfLocked.remove(pid);
                mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);
            }
            handleAppDiedLocked(app, true);
            mLRUProcesses.remove(app);
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
            Log.w(TAG, "Process " + app + " failed to attach");
            mProcessNames.remove(app.processName, app.info.uid);
            Process.killProcess(pid);
            if (mPendingBroadcast != null && mPendingBroadcast.curApp.pid == pid) {
                Log.w(TAG, "Unattached app died before broadcast acknowledged, skipping");
                mPendingBroadcast = null;
                scheduleBroadcastsLocked();
            }
        } else {
            Log.w(TAG, "Spurious process start timeout - pid not known for " + app);
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
        } else if (mStartingProcesses.size() > 0) {
            app = mStartingProcesses.remove(0);
            app.pid = pid;
        } else {
            app = null;
        }

        if (app == null) {
            Log.w(TAG, "No pending application record for pid " + pid
                    + " (IApplicationThread " + thread + "); dropping process");
            EventLog.writeEvent(LOG_AM_DROP_PROCESS, pid);
            if (pid > 0 && pid != MY_PID) {
                Process.killProcess(pid);
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
            handleAppDiedLocked(app, true);
        }

        // Tell the process all about itself.

        if (localLOGV) Log.v(
                TAG, "Binding process pid " + pid + " to record " + app);

        String processName = app.processName;
        try {
            thread.asBinder().linkToDeath(new AppDeathRecipient(
                    app, pid, thread), 0);
        } catch (RemoteException e) {
            app.resetPackageList();
            startProcessLocked(app, "link fail", processName);
            return false;
        }

        EventLog.writeEvent(LOG_AM_PROCESS_BOUND, app.pid, app.processName);
        
        app.thread = thread;
        app.curAdj = app.setAdj = -100;
        app.forcingToForeground = null;
        app.foregroundServices = false;
        app.debugging = false;

        mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);

        List providers = generateApplicationProvidersLocked(app);

        if (localLOGV) Log.v(
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
            thread.bindApplication(processName, app.info, providers,
                    app.instrumentationClass, app.instrumentationProfileFile,
                    app.instrumentationArguments, app.instrumentationWatcher, testMode, 
                    mConfiguration, getCommonServicesLocked());
            updateLRUListLocked(app, false);
            app.lastRequestedGc = SystemClock.uptimeMillis();
        } catch (Exception e) {
            // todo: Yikes!  What should we do?  For now we will try to
            // start another process, but that could easily get us in
            // an infinite loop of restarting processes...
            Log.w(TAG, "Exception thrown during bind!", e);

            app.resetPackageList();
            startProcessLocked(app, "bind fail", processName);
            return false;
        }

        // Remove this record from the list of starting applications.
        mPersistentStartingProcesses.remove(app);
        mProcessesOnHold.remove(app);

        boolean badApp = false;
        boolean didSomething = false;

        // See if the top visible activity is waiting to run in this process...
        HistoryRecord hr = topRunningActivityLocked(null);
        if (hr != null) {
            if (hr.app == null && app.info.uid == hr.info.applicationInfo.uid
                    && processName.equals(hr.processName)) {
                try {
                    if (realStartActivityLocked(hr, app, true, true)) {
                        didSomething = true;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Exception in new application when starting activity "
                          + hr.intent.getComponent().flattenToShortString(), e);
                    badApp = true;
                }
            } else {
                ensureActivitiesVisibleLocked(hr, null, processName, 0);
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
                Log.w(TAG, "Exception in new application when starting service "
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
                Log.w(TAG, "Exception in new application when starting receiver "
                      + br.curComponent.flattenToShortString(), e);
                badApp = true;
                logBroadcastReceiverDiscard(br);
                finishReceiverLocked(br.receiver, br.resultCode, br.resultData,
                        br.resultExtras, br.resultAbort, true);
                scheduleBroadcastsLocked();
            }
        }

        if (badApp) {
            // todo: Also need to kill application to deal with all
            // kinds of exceptions.
            handleAppDiedLocked(app, false);
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

    public final void activityIdle(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        activityIdleInternal(token, false);
        Binder.restoreCallingIdentity(origId);
    }

    final ArrayList<HistoryRecord> processStoppingActivitiesLocked(
            boolean remove) {
        int N = mStoppingActivities.size();
        if (N <= 0) return null;

        ArrayList<HistoryRecord> stops = null;

        final boolean nowVisible = mResumedActivity != null
                && mResumedActivity.nowVisible
                && !mResumedActivity.waitingVisible;
        for (int i=0; i<N; i++) {
            HistoryRecord s = mStoppingActivities.get(i);
            if (localLOGV) Log.v(TAG, "Stopping " + s + ": nowVisible="
                    + nowVisible + " waitingVisible=" + s.waitingVisible
                    + " finishing=" + s.finishing);
            if (s.waitingVisible && nowVisible) {
                mWaitingVisibleActivities.remove(s);
                s.waitingVisible = false;
                if (s.finishing) {
                    // If this activity is finishing, it is sitting on top of
                    // everyone else but we now know it is no longer needed...
                    // so get rid of it.  Otherwise, we need to go through the
                    // normal flow and hide it once we determine that it is
                    // hidden by the activities in front of it.
                    if (localLOGV) Log.v(TAG, "Before stopping, can hide: " + s);
                    mWindowManager.setAppVisibility(s, false);
                }
            }
            if (!s.waitingVisible && remove) {
                if (localLOGV) Log.v(TAG, "Ready to stop: " + s);
                if (stops == null) {
                    stops = new ArrayList<HistoryRecord>();
                }
                stops.add(s);
                mStoppingActivities.remove(i);
                N--;
                i--;
            }
        }

        return stops;
    }

    void enableScreenAfterBoot() {
        mWindowManager.enableScreenAfterBoot();
    }

    final void activityIdleInternal(IBinder token, boolean fromTimeout) {
        if (localLOGV) Log.v(TAG, "Activity idle: " + token);

        ArrayList<HistoryRecord> stops = null;
        ArrayList<HistoryRecord> finishes = null;
        ArrayList<HistoryRecord> thumbnails = null;
        int NS = 0;
        int NF = 0;
        int NT = 0;
        IApplicationThread sendThumbnail = null;
        boolean booting = false;
        boolean enableScreen = false;

        synchronized (this) {
            if (token != null) {
                mHandler.removeMessages(IDLE_TIMEOUT_MSG, token);
            }

            // Get the activity record.
            int index = indexOfTokenLocked(token, false);
            if (index >= 0) {
                HistoryRecord r = (HistoryRecord)mHistory.get(index);

                // No longer need to keep the device awake.
                if (mResumedActivity == r && mLaunchingActivity.isHeld()) {
                    mHandler.removeMessages(LAUNCH_TIMEOUT_MSG);
                    mLaunchingActivity.release();
                }

                // We are now idle.  If someone is waiting for a thumbnail from
                // us, we can now deliver.
                r.idle = true;
                scheduleAppGcsLocked();
                if (r.thumbnailNeeded && r.app != null && r.app.thread != null) {
                    sendThumbnail = r.app.thread;
                    r.thumbnailNeeded = false;
                }

                // If this activity is fullscreen, set up to hide those under it.

                if (DEBUG_VISBILITY) Log.v(TAG, "Idle activity for " + r);
                ensureActivitiesVisibleLocked(null, 0);

                //Log.i(TAG, "IDLE: mBooted=" + mBooted + ", fromTimeout=" + fromTimeout);
                if (!mBooted && !fromTimeout) {
                    mBooted = true;
                    enableScreen = true;
                }
            }

            // Atomically retrieve all of the other things to do.
            stops = processStoppingActivitiesLocked(true);
            NS = stops != null ? stops.size() : 0;
            if ((NF=mFinishingActivities.size()) > 0) {
                finishes = new ArrayList<HistoryRecord>(mFinishingActivities);
                mFinishingActivities.clear();
            }
            if ((NT=mCancelledThumbnails.size()) > 0) {
                thumbnails = new ArrayList<HistoryRecord>(mCancelledThumbnails);
                mCancelledThumbnails.clear();
            }

            booting = mBooting;
            mBooting = false;
        }

        int i;

        // Send thumbnail if requested.
        if (sendThumbnail != null) {
            try {
                sendThumbnail.requestThumbnail(token);
            } catch (Exception e) {
                Log.w(TAG, "Exception thrown when requesting thumbnail", e);
                sendPendingThumbnail(null, token, null, null, true);
            }
        }

        // Stop any activities that are scheduled to do so but have been
        // waiting for the next one to start.
        for (i=0; i<NS; i++) {
            HistoryRecord r = (HistoryRecord)stops.get(i);
            synchronized (this) {
                if (r.finishing) {
                    finishCurrentActivityLocked(r, FINISH_IMMEDIATELY);
                } else {
                    stopActivityLocked(r);
                }
            }
        }

        // Finish any activities that are scheduled to do so but have been
        // waiting for the next one to start.
        for (i=0; i<NF; i++) {
            HistoryRecord r = (HistoryRecord)finishes.get(i);
            synchronized (this) {
                destroyActivityLocked(r, true);
            }
        }

        // Report back to any thumbnail receivers.
        for (i=0; i<NT; i++) {
            HistoryRecord r = (HistoryRecord)thumbnails.get(i);
            sendPendingThumbnail(r, null, null, null, true);
        }

        if (booting) {
            // Ensure that any processes we had put on hold are now started
            // up.
            final int NP = mProcessesOnHold.size();
            if (NP > 0) {
                ArrayList<ProcessRecord> procs =
                    new ArrayList<ProcessRecord>(mProcessesOnHold);
                for (int ip=0; ip<NP; ip++) {
                    this.startProcessLocked(procs.get(ip), "on-hold", null);
                }
            }
            if (mFactoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL) {
                // Tell anyone interested that we are done booting!
                synchronized (this) {
                    broadcastIntentLocked(null, null,
                            new Intent(Intent.ACTION_BOOT_COMPLETED, null),
                            null, null, 0, null, null,
                            android.Manifest.permission.RECEIVE_BOOT_COMPLETED,
                            false, false, MY_PID, Process.SYSTEM_UID);
                }
            }
        }

        trimApplications();
        //dump();
        //mWindowManager.dump();

        if (enableScreen) {
            EventLog.writeEvent(LOG_BOOT_PROGRESS_ENABLE_SCREEN,
                SystemClock.uptimeMillis());
            enableScreenAfterBoot();
        }
    }

    public final void activityPaused(IBinder token, Bundle icicle) {
        // Refuse possible leaked file descriptors
        if (icicle != null && icicle.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Bundle");
        }

        final long origId = Binder.clearCallingIdentity();
        activityPaused(token, icicle, false);
        Binder.restoreCallingIdentity(origId);
    }

    final void activityPaused(IBinder token, Bundle icicle, boolean timeout) {
        if (DEBUG_PAUSE) Log.v(
            TAG, "Activity paused: token=" + token + ", icicle=" + icicle
            + ", timeout=" + timeout);

        HistoryRecord r = null;

        synchronized (this) {
            int index = indexOfTokenLocked(token, false);
            if (index >= 0) {
                r = (HistoryRecord)mHistory.get(index);
                if (!timeout) {
                    r.icicle = icicle;
                    r.haveState = true;
                }
                mHandler.removeMessages(PAUSE_TIMEOUT_MSG, r);
                if (mPausingActivity == r) {
                    r.state = ActivityState.PAUSED;
                    completePauseLocked();
                } else {
                	EventLog.writeEvent(LOG_AM_FAILED_TO_PAUSE_ACTIVITY,
                	        System.identityHashCode(r), r.shortComponentName, 
                			mPausingActivity != null
                			    ? mPausingActivity.shortComponentName : "(none)");
                }
            }
        }
    }

    public final void activityStopped(IBinder token, Bitmap thumbnail,
            CharSequence description) {
        if (localLOGV) Log.v(
            TAG, "Activity stopped: token=" + token);

        HistoryRecord r = null;

        final long origId = Binder.clearCallingIdentity();

        synchronized (this) {
            int index = indexOfTokenLocked(token, false);
            if (index >= 0) {
                r = (HistoryRecord)mHistory.get(index);
                r.thumbnail = thumbnail;
                r.description = description;
                r.stopped = true;
                r.state = ActivityState.STOPPED;
                if (!r.finishing) {
                    if (r.configDestroy) {
                        destroyActivityLocked(r, true);
                        resumeTopActivityLocked(null);
                    }
                }
            }
        }

        if (r != null) {
            sendPendingThumbnail(r, null, null, null, false);
        }

        trimApplications();

        Binder.restoreCallingIdentity(origId);
    }

    public final void activityDestroyed(IBinder token) {
        if (DEBUG_SWITCH) Log.v(TAG, "ACTIVITY DESTROYED: " + token);
        synchronized (this) {
            mHandler.removeMessages(DESTROY_TIMEOUT_MSG, token);
            
            int index = indexOfTokenLocked(token, false);
            if (index >= 0) {
                HistoryRecord r = (HistoryRecord)mHistory.get(index);
                if (r.state == ActivityState.DESTROYING) {
                    final long origId = Binder.clearCallingIdentity();
                    removeActivityFromHistoryLocked(r);
                    Binder.restoreCallingIdentity(origId);
                }
            }
        }
    }
    
    public String getCallingPackage(IBinder token) {
        synchronized (this) {
            HistoryRecord r = getCallingRecordLocked(token);
            return r != null && r.app != null ? r.app.processName : null;
        }
    }

    public ComponentName getCallingActivity(IBinder token) {
        synchronized (this) {
            HistoryRecord r = getCallingRecordLocked(token);
            return r != null ? r.intent.getComponent() : null;
        }
    }

    private HistoryRecord getCallingRecordLocked(IBinder token) {
        int index = indexOfTokenLocked(token, true);
        if (index >= 0) {
            HistoryRecord r = (HistoryRecord)mHistory.get(index);
            if (r != null) {
                return r.resultTo;
            }
        }
        return null;
    }

    public ComponentName getActivityClassForToken(IBinder token) {
        synchronized(this) {
            int index = indexOfTokenLocked(token, false);
            if (index >= 0) {
                HistoryRecord r = (HistoryRecord)mHistory.get(index);
                return r.intent.getComponent();
            }
            return null;
        }
    }

    public String getPackageForToken(IBinder token) {
        synchronized(this) {
            int index = indexOfTokenLocked(token, false);
            if (index >= 0) {
                HistoryRecord r = (HistoryRecord)mHistory.get(index);
                return r.packageName;
            }
            return null;
        }
    }

    public IIntentSender getIntentSender(int type,
            String packageName, IBinder token, String resultWho,
            int requestCode, Intent intent, String resolvedType, int flags) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            int callingUid = Binder.getCallingUid();
            try {
                if (callingUid != 0 && callingUid != Process.SYSTEM_UID &&
                        Process.supportsProcesses()) {
                    int uid = ActivityThread.getPackageManager()
                            .getPackageUid(packageName);
                    if (uid != Binder.getCallingUid()) {
                        String msg = "Permission Denial: getIntentSender() from pid="
                            + Binder.getCallingPid()
                            + ", uid=" + Binder.getCallingUid()
                            + ", (need uid=" + uid + ")"
                            + " is not allowed to send as package " + packageName;
                        Log.w(TAG, msg);
                        throw new SecurityException(msg);
                    }
                }
            } catch (RemoteException e) {
                throw new SecurityException(e);
            }
            HistoryRecord activity = null;
            if (type == INTENT_SENDER_ACTIVITY_RESULT) {
                int index = indexOfTokenLocked(token, false);
                if (index < 0) {
                    return null;
                }
                activity = (HistoryRecord)mHistory.get(index);
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
                    requestCode, intent, resolvedType, flags);
            WeakReference<PendingIntentRecord> ref;
            ref = mIntentSenderRecords.get(key);
            PendingIntentRecord rec = ref != null ? ref.get() : null;
            if (rec != null) {
                if (!cancelCurrent) {
                    if (updateCurrent) {
                        rec.key.requestIntent.replaceExtras(intent);
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
    }

    public void cancelIntentSender(IIntentSender sender) {
        if (!(sender instanceof PendingIntentRecord)) {
            return;
        }
        synchronized(this) {
            PendingIntentRecord rec = (PendingIntentRecord)sender;
            try {
                int uid = ActivityThread.getPackageManager()
                        .getPackageUid(rec.key.packageName);
                if (uid != Binder.getCallingUid()) {
                    String msg = "Permission Denial: cancelIntentSender() from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " is not allowed to cancel packges "
                        + rec.key.packageName;
                    Log.w(TAG, msg);
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
        synchronized(this) {
            try {
                PendingIntentRecord res = (PendingIntentRecord)pendingResult;
                return res.key.packageName;
            } catch (ClassCastException e) {
            }
        }
        return null;
    }

    public void setProcessLimit(int max) {
        enforceCallingPermission(android.Manifest.permission.SET_PROCESS_LIMIT,
                "setProcessLimit()");
        mProcessLimit = max;
    }

    public int getProcessLimit() {
        return mProcessLimit;
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
                    Log.w(TAG, "setProcessForeground called on unknown pid: " + pid);
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
            int reqUid) {
        // We might be performing an operation on behalf of an indirect binder
        // invocation, e.g. via {@link #openContentUri}.  Check and adjust the
        // client identity accordingly before proceeding.
        Identity tlsIdentity = sCallerIdentity.get();
        if (tlsIdentity != null) {
            Log.d(TAG, "checkComponentPermission() adjusting {pid,uid} to {"
                    + tlsIdentity.pid + "," + tlsIdentity.uid + "}");
            uid = tlsIdentity.uid;
            pid = tlsIdentity.pid;
        }

        // Root, system server and our own process get to do everything.
        if (uid == 0 || uid == Process.SYSTEM_UID || pid == MY_PID ||
            !Process.supportsProcesses()) {
            return PackageManager.PERMISSION_GRANTED;
        }
        // If the target requires a specific UID, always fail for others.
        if (reqUid >= 0 && uid != reqUid) {
            return PackageManager.PERMISSION_DENIED;
        }
        if (permission == null) {
            return PackageManager.PERMISSION_GRANTED;
        }
        try {
            return ActivityThread.getPackageManager()
                    .checkUidPermission(permission, uid);
        } catch (RemoteException e) {
            // Should never happen, but if it does... deny!
            Log.e(TAG, "PackageManager is dead?!?", e);
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
        return checkComponentPermission(permission, pid, uid, -1);
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
        Log.w(TAG, msg);
        throw new SecurityException(msg);
    }

    private final boolean checkHoldingPermissionsLocked(IPackageManager pm,
            ProviderInfo pi, int uid, int modeFlags) {
        try {
            if ((modeFlags&Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                if ((pi.readPermission != null) &&
                        (pm.checkUidPermission(pi.readPermission, uid)
                                != PackageManager.PERMISSION_GRANTED)) {
                    return false;
                }
            }
            if ((modeFlags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                if ((pi.writePermission != null) &&
                        (pm.checkUidPermission(pi.writePermission, uid)
                                != PackageManager.PERMISSION_GRANTED)) {
                    return false;
                }
            }
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    private final boolean checkUriPermissionLocked(Uri uri, int uid,
            int modeFlags) {
        // Root gets to do everything.
        if (uid == 0 || !Process.supportsProcesses()) {
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

    private void grantUriPermissionLocked(int callingUid,
            String targetPkg, Uri uri, int modeFlags, HistoryRecord activity) {
        modeFlags &= (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (modeFlags == 0) {
            return;
        }

        final IPackageManager pm = ActivityThread.getPackageManager();

        // If this is not a content: uri, we can't do anything with it.
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            return;
        }

        String name = uri.getAuthority();
        ProviderInfo pi = null;
        ContentProviderRecord cpr
                = (ContentProviderRecord)mProvidersByName.get(name);
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
            Log.w(TAG, "No content provider found for: " + name);
            return;
        }

        int targetUid;
        try {
            targetUid = pm.getPackageUid(targetPkg);
            if (targetUid < 0) {
                return;
            }
        } catch (RemoteException ex) {
            return;
        }

        // First...  does the target actually need this permission?
        if (checkHoldingPermissionsLocked(pm, pi, targetUid, modeFlags)) {
            // No need to grant the target this permission.
            return;
        }

        // Second...  maybe someone else has already granted the
        // permission?
        if (checkUriPermissionLocked(uri, targetUid, modeFlags)) {
            // No need to grant the target this permission.
            return;
        }

        // Third...  is the provider allowing granting of URI permissions?
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

        // Fourth...  does the caller itself have permission to access
        // this uri?
        if (!checkHoldingPermissionsLocked(pm, pi, callingUid, modeFlags)) {
            if (!checkUriPermissionLocked(uri, callingUid, modeFlags)) {
                throw new SecurityException("Uid " + callingUid
                        + " does not have permission to uri " + uri);
            }
        }

        // Okay!  So here we are: the caller has the assumed permission
        // to the uri, and the target doesn't.  Let's now give this to
        // the target.

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
        if (activity == null) {
            perm.globalModeFlags |= modeFlags;
        } else if ((modeFlags&Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            perm.readActivities.add(activity);
            if (activity.readUriPermissions == null) {
                activity.readUriPermissions = new HashSet<UriPermission>();
            }
            activity.readUriPermissions.add(perm);
        } else if ((modeFlags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
            perm.writeActivities.add(activity);
            if (activity.writeUriPermissions == null) {
                activity.writeUriPermissions = new HashSet<UriPermission>();
            }
            activity.writeUriPermissions.add(perm);
        }
    }

    private void grantUriPermissionFromIntentLocked(int callingUid,
            String targetPkg, Intent intent, HistoryRecord activity) {
        if (intent == null) {
            return;
        }
        Uri data = intent.getData();
        if (data == null) {
            return;
        }
        grantUriPermissionLocked(callingUid, targetPkg, data,
                intent.getFlags(), activity);
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
                Log.w(TAG, "grantUriPermission: null target");
                return;
            }
            if (uri == null) {
                Log.w(TAG, "grantUriPermission: null uri");
                return;
            }

            grantUriPermissionLocked(r.info.uid, targetPkg, uri, modeFlags,
                    null);
        }
    }

    private void removeUriPermissionIfNeededLocked(UriPermission perm) {
        if ((perm.modeFlags&(Intent.FLAG_GRANT_READ_URI_PERMISSION
                |Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) == 0) {
            HashMap<Uri, UriPermission> perms
                    = mGrantedUriPermissions.get(perm.uid);
            if (perms != null) {
                perms.remove(perm.uri);
                if (perms.size() == 0) {
                    mGrantedUriPermissions.remove(perm.uid);
                }
            }
        }
    }

    private void removeActivityUriPermissionsLocked(HistoryRecord activity) {
        if (activity.readUriPermissions != null) {
            for (UriPermission perm : activity.readUriPermissions) {
                perm.readActivities.remove(activity);
                if (perm.readActivities.size() == 0 && (perm.globalModeFlags
                        &Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
                    perm.modeFlags &= ~Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    removeUriPermissionIfNeededLocked(perm);
                }
            }
        }
        if (activity.writeUriPermissions != null) {
            for (UriPermission perm : activity.writeUriPermissions) {
                perm.writeActivities.remove(activity);
                if (perm.writeActivities.size() == 0 && (perm.globalModeFlags
                        &Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
                    perm.modeFlags &= ~Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                    removeUriPermissionIfNeededLocked(perm);
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

        final IPackageManager pm = ActivityThread.getPackageManager();

        final String authority = uri.getAuthority();
        ProviderInfo pi = null;
        ContentProviderRecord cpr
                = (ContentProviderRecord)mProvidersByName.get(authority);
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
            Log.w(TAG, "No content provider found for: " + authority);
            return;
        }

        // Does the caller have this permission on the URI?
        if (!checkHoldingPermissionsLocked(pm, pi, callingUid, modeFlags)) {
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
                Log.w(TAG, "revokeUriPermission: null uri");
                return;
            }

            modeFlags &= (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (modeFlags == 0) {
                return;
            }

            final IPackageManager pm = ActivityThread.getPackageManager();

            final String authority = uri.getAuthority();
            ProviderInfo pi = null;
            ContentProviderRecord cpr
                    = (ContentProviderRecord)mProvidersByName.get(authority);
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
                Log.w(TAG, "No content provider found for: " + authority);
                return;
            }

            revokeUriPermissionLocked(r.info.uid, uri, modeFlags);
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
        outInfo.availMem = Process.getFreeMemory();
        outInfo.threshold = HOME_APP_MEM;
        outInfo.lowMemory = outInfo.availMem <
                (HOME_APP_MEM + ((HIDDEN_APP_MEM-HOME_APP_MEM)/2));
    }
    
    // =========================================================
    // TASK MANAGEMENT
    // =========================================================

    public List getTasks(int maxNum, int flags,
                         IThumbnailReceiver receiver) {
        ArrayList list = new ArrayList();

        PendingThumbnailsRecord pending = null;
        IApplicationThread topThumbnail = null;
        HistoryRecord topRecord = null;

        synchronized(this) {
            if (localLOGV) Log.v(
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
                Log.w(TAG, msg);
                throw new SecurityException(msg);
            }

            int pos = mHistory.size()-1;
            HistoryRecord next =
                pos >= 0 ? (HistoryRecord)mHistory.get(pos) : null;
            HistoryRecord top = null;
            CharSequence topDescription = null;
            TaskRecord curTask = null;
            int numActivities = 0;
            int numRunning = 0;
            while (pos >= 0 && maxNum > 0) {
                final HistoryRecord r = next;
                pos--;
                next = pos >= 0 ? (HistoryRecord)mHistory.get(pos) : null;

                // Initialize state for next task if needed.
                if (top == null ||
                        (top.state == ActivityState.INITIALIZING
                            && top.task == r.task)) {
                    top = r;
                    topDescription = r.description;
                    curTask = r.task;
                    numActivities = numRunning = 0;
                }

                // Add 'r' into the current task.
                numActivities++;
                if (r.app != null && r.app.thread != null) {
                    numRunning++;
                }
                if (topDescription == null) {
                    topDescription = r.description;
                }

                if (localLOGV) Log.v(
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
                    ci.thumbnail = top.thumbnail;
                    ci.description = topDescription;
                    ci.numActivities = numActivities;
                    ci.numRunning = numRunning;
                    //System.out.println(
                    //    "#" + maxNum + ": " + " descr=" + ci.description);
                    if (ci.thumbnail == null && receiver != null) {
                        if (localLOGV) Log.v(
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

        if (localLOGV) Log.v(TAG, "We have pending thumbnails: " + pending);

        if (topThumbnail != null) {
            if (localLOGV) Log.v(TAG, "Requesting top thumbnail");
            try {
                topThumbnail.requestThumbnail(topRecord);
            } catch (Exception e) {
                Log.w(TAG, "Exception thrown when requesting thumbnail", e);
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
                    rti.baseIntent = new Intent(
                            tr.intent != null ? tr.intent : tr.affinityIntent);
                    rti.origActivity = tr.origActivity;
                    res.add(rti);
                    maxNum--;
                }
            }
            return res;
        }
    }

    private final int findAffinityTaskTopLocked(int startIndex, String affinity) {
        int j;
        TaskRecord startTask = ((HistoryRecord)mHistory.get(startIndex)).task; 
        TaskRecord jt = startTask;
        
        // First look backwards
        for (j=startIndex-1; j>=0; j--) {
            HistoryRecord r = (HistoryRecord)mHistory.get(j);
            if (r.task != jt) {
                jt = r.task;
                if (affinity.equals(jt.affinity)) {
                    return j;
                }
            }
        }
        
        // Now look forwards
        final int N = mHistory.size();
        jt = startTask;
        for (j=startIndex+1; j<N; j++) {
            HistoryRecord r = (HistoryRecord)mHistory.get(j);
            if (r.task != jt) {
                if (affinity.equals(jt.affinity)) {
                    return j;
                }
                jt = r.task;
            }
        }
        
        // Might it be at the top?
        if (affinity.equals(((HistoryRecord)mHistory.get(N-1)).task.affinity)) {
            return N-1;
        }
        
        return -1;
    }
    
    /**
     * Perform a reset of the given task, if needed as part of launching it.
     * Returns the new HistoryRecord at the top of the task.
     */
    private final HistoryRecord resetTaskIfNeededLocked(HistoryRecord taskTop,
            HistoryRecord newActivity) {
        boolean forceReset = (newActivity.info.flags
                &ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH) != 0;
        if (taskTop.task.getInactiveDuration() > ACTIVITY_INACTIVE_RESET_TIME) {
            if ((newActivity.info.flags
                    &ActivityInfo.FLAG_ALWAYS_RETAIN_TASK_STATE) == 0) {
                forceReset = true;
            }
        }
        
        final TaskRecord task = taskTop.task;
        
        // We are going to move through the history list so that we can look
        // at each activity 'target' with 'below' either the interesting
        // activity immediately below it in the stack or null.
        HistoryRecord target = null;
        int targetI = 0;
        int taskTopI = -1;
        int replyChainEnd = -1;
        int lastReparentPos = -1;
        for (int i=mHistory.size()-1; i>=-1; i--) {
            HistoryRecord below = i >= 0 ? (HistoryRecord)mHistory.get(i) : null;
            
            if (below != null && below.finishing) {
                continue;
            }
            if (target == null) {
                target = below;
                targetI = i;
                // If we were in the middle of a reply chain before this
                // task, it doesn't appear like the root of the chain wants
                // anything interesting, so drop it.
                replyChainEnd = -1;
                continue;
            }
        
            final int flags = target.info.flags;
            
            final boolean finishOnTaskLaunch =
                (flags&ActivityInfo.FLAG_FINISH_ON_TASK_LAUNCH) != 0;
            final boolean allowTaskReparenting =
                (flags&ActivityInfo.FLAG_ALLOW_TASK_REPARENTING) != 0;
            
            if (target.task == task) {
                // We are inside of the task being reset...  we'll either
                // finish this activity, push it out for another task,
                // or leave it as-is.  We only do this
                // for activities that are not the root of the task (since
                // if we finish the root, we may no longer have the task!).
                if (taskTopI < 0) {
                    taskTopI = targetI;
                }
                if (below != null && below.task == task) {
                    final boolean clearWhenTaskReset =
                            (target.intent.getFlags()
                                    &Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET) != 0;
                    if (!finishOnTaskLaunch && !clearWhenTaskReset && target.resultTo != null) {
                        // If this activity is sending a reply to a previous
                        // activity, we can't do anything with it now until
                        // we reach the start of the reply chain.
                        // XXX note that we are assuming the result is always
                        // to the previous activity, which is almost always
                        // the case but we really shouldn't count on.
                        if (replyChainEnd < 0) {
                            replyChainEnd = targetI;
                        }
                    } else if (!finishOnTaskLaunch && !clearWhenTaskReset && allowTaskReparenting
                            && target.taskAffinity != null
                            && !target.taskAffinity.equals(task.affinity)) {
                        // If this activity has an affinity for another
                        // task, then we need to move it out of here.  We will
                        // move it as far out of the way as possible, to the
                        // bottom of the activity stack.  This also keeps it
                        // correctly ordered with any activities we previously
                        // moved.
                        HistoryRecord p = (HistoryRecord)mHistory.get(0);
                        if (target.taskAffinity != null
                                && target.taskAffinity.equals(p.task.affinity)) {
                            // If the activity currently at the bottom has the
                            // same task affinity as the one we are moving,
                            // then merge it into the same task.
                            target.task = p.task;
                            if (DEBUG_TASKS) Log.v(TAG, "Start pushing activity " + target
                                    + " out to bottom task " + p.task);
                        } else {
                            mCurTask++;
                            if (mCurTask <= 0) {
                                mCurTask = 1;
                            }
                            target.task = new TaskRecord(mCurTask, target.info, null,
                                    (target.info.flags&ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH) != 0);
                            target.task.affinityIntent = target.intent;
                            if (DEBUG_TASKS) Log.v(TAG, "Start pushing activity " + target
                                    + " out to new task " + target.task);
                        }
                        mWindowManager.setAppGroupId(target, task.taskId);
                        if (replyChainEnd < 0) {
                            replyChainEnd = targetI;
                        }
                        int dstPos = 0;
                        for (int srcPos=targetI; srcPos<=replyChainEnd; srcPos++) {
                            p = (HistoryRecord)mHistory.get(srcPos);
                            if (p.finishing) {
                                continue;
                            }
                            if (DEBUG_TASKS) Log.v(TAG, "Pushing next activity " + p
                                    + " out to target's task " + target.task);
                            task.numActivities--;
                            p.task = target.task;
                            target.task.numActivities++;
                            mHistory.remove(srcPos);
                            mHistory.add(dstPos, p);
                            mWindowManager.moveAppToken(dstPos, p);
                            mWindowManager.setAppGroupId(p, p.task.taskId);
                            dstPos++;
                            if (VALIDATE_TOKENS) {
                                mWindowManager.validateAppTokens(mHistory);
                            }
                            i++;
                        }
                        if (taskTop == p) {
                            taskTop = below;
                        }
                        if (taskTopI == replyChainEnd) {
                            taskTopI = -1;
                        }
                        replyChainEnd = -1;
                        addRecentTask(target.task);
                    } else if (forceReset || finishOnTaskLaunch
                            || clearWhenTaskReset) {
                        // If the activity should just be removed -- either
                        // because it asks for it, or the task should be
                        // cleared -- then finish it and anything that is
                        // part of its reply chain.
                        if (clearWhenTaskReset) {
                            // In this case, we want to finish this activity
                            // and everything above it, so be sneaky and pretend
                            // like these are all in the reply chain.
                            replyChainEnd = targetI+1;
                            while (replyChainEnd < mHistory.size() &&
                                    ((HistoryRecord)mHistory.get(
                                                replyChainEnd)).task == task) {
                                replyChainEnd++;
                            }
                            replyChainEnd--;
                        } else if (replyChainEnd < 0) {
                            replyChainEnd = targetI;
                        }
                        HistoryRecord p = null;
                        for (int srcPos=targetI; srcPos<=replyChainEnd; srcPos++) {
                            p = (HistoryRecord)mHistory.get(srcPos);
                            if (p.finishing) {
                                continue;
                            }
                            if (finishActivityLocked(p, srcPos,
                                    Activity.RESULT_CANCELED, null, "reset")) {
                                replyChainEnd--;
                                srcPos--;
                            }
                        }
                        if (taskTop == p) {
                            taskTop = below;
                        }
                        if (taskTopI == replyChainEnd) {
                            taskTopI = -1;
                        }
                        replyChainEnd = -1;
                    } else {
                        // If we were in the middle of a chain, well the
                        // activity that started it all doesn't want anything
                        // special, so leave it all as-is.
                        replyChainEnd = -1;
                    }
                } else {
                    // Reached the bottom of the task -- any reply chain
                    // should be left as-is.
                    replyChainEnd = -1;
                }
                
            } else if (target.resultTo != null) {
                // If this activity is sending a reply to a previous
                // activity, we can't do anything with it now until
                // we reach the start of the reply chain.
                // XXX note that we are assuming the result is always
                // to the previous activity, which is almost always
                // the case but we really shouldn't count on.
                if (replyChainEnd < 0) {
                    replyChainEnd = targetI;
                }

            } else if (taskTopI >= 0 && allowTaskReparenting
                    && task.affinity != null
                    && task.affinity.equals(target.taskAffinity)) {
                // We are inside of another task...  if this activity has
                // an affinity for our task, then either remove it if we are
                // clearing or move it over to our task.  Note that
                // we currently punt on the case where we are resetting a
                // task that is not at the top but who has activities above
                // with an affinity to it...  this is really not a normal
                // case, and we will need to later pull that task to the front
                // and usually at that point we will do the reset and pick
                // up those remaining activities.  (This only happens if
                // someone starts an activity in a new task from an activity
                // in a task that is not currently on top.)
                if (forceReset || finishOnTaskLaunch) {
                    if (replyChainEnd < 0) {
                        replyChainEnd = targetI;
                    }
                    HistoryRecord p = null;
                    for (int srcPos=targetI; srcPos<=replyChainEnd; srcPos++) {
                        p = (HistoryRecord)mHistory.get(srcPos);
                        if (p.finishing) {
                            continue;
                        }
                        if (finishActivityLocked(p, srcPos,
                                Activity.RESULT_CANCELED, null, "reset")) {
                            taskTopI--;
                            lastReparentPos--;
                            replyChainEnd--;
                            srcPos--;
                        }
                    }
                    replyChainEnd = -1;
                } else {
                    if (replyChainEnd < 0) {
                        replyChainEnd = targetI;
                    }
                    for (int srcPos=replyChainEnd; srcPos>=targetI; srcPos--) {
                        HistoryRecord p = (HistoryRecord)mHistory.get(srcPos);
                        if (p.finishing) {
                            continue;
                        }
                        if (lastReparentPos < 0) {
                            lastReparentPos = taskTopI;
                            taskTop = p;
                        } else {
                            lastReparentPos--;
                        }
                        mHistory.remove(srcPos);
                        p.task.numActivities--;
                        p.task = task;
                        mHistory.add(lastReparentPos, p);
                        if (DEBUG_TASKS) Log.v(TAG, "Pulling activity " + p
                                + " in to resetting task " + task);
                        task.numActivities++;
                        mWindowManager.moveAppToken(lastReparentPos, p);
                        mWindowManager.setAppGroupId(p, p.task.taskId);
                        if (VALIDATE_TOKENS) {
                            mWindowManager.validateAppTokens(mHistory);
                        }
                    }
                    replyChainEnd = -1;
                    
                    // Now we've moved it in to place...  but what if this is
                    // a singleTop activity and we have put it on top of another
                    // instance of the same activity?  Then we drop the instance
                    // below so it remains singleTop.
                    if (target.info.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP) {
                        for (int j=lastReparentPos-1; j>=0; j--) {
                            HistoryRecord p = (HistoryRecord)mHistory.get(j);
                            if (p.finishing) {
                                continue;
                            }
                            if (p.intent.getComponent().equals(target.intent.getComponent())) {
                                if (finishActivityLocked(p, j,
                                        Activity.RESULT_CANCELED, null, "replace")) {
                                    taskTopI--;
                                    lastReparentPos--;
                                }
                            }
                        }
                    }
                }
            }
            
            target = below;
            targetI = i;
        }
        
        return taskTop;
    }
    
    /**
     * TODO: Add mWatcher hook
     */
    public void moveTaskToFront(int task) {
        enforceCallingPermission(android.Manifest.permission.REORDER_TASKS,
                "moveTaskToFront()");

        synchronized(this) {
            final long origId = Binder.clearCallingIdentity();
            try {
                int N = mRecentTasks.size();
                for (int i=0; i<N; i++) {
                    TaskRecord tr = mRecentTasks.get(i);
                    if (tr.taskId == task) {
                        moveTaskToFrontLocked(tr);
                        return;
                    }
                }
                for (int i=mHistory.size()-1; i>=0; i--) {
                    HistoryRecord hr = (HistoryRecord)mHistory.get(i);
                    if (hr.task.taskId == task) {
                        moveTaskToFrontLocked(hr.task);
                        return;
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    private final void moveTaskToFrontLocked(TaskRecord tr) {
        if (DEBUG_SWITCH) Log.v(TAG, "moveTaskToFront: " + tr);

        final int task = tr.taskId;
        int top = mHistory.size()-1;

        if (top < 0 || ((HistoryRecord)mHistory.get(top)).task.taskId == task) {
            // nothing to do!
            return;
        }

        if (DEBUG_TRANSITION) Log.v(TAG,
                "Prepare to front transition: task=" + tr);
        mWindowManager.prepareAppTransition(WindowManagerPolicy.TRANSIT_TASK_TO_FRONT);
        
        ArrayList moved = new ArrayList();

        // Applying the affinities may have removed entries from the history,
        // so get the size again.
        top = mHistory.size()-1;
        int pos = top;

        // Shift all activities with this task up to the top
        // of the stack, keeping them in the same internal order.
        while (pos >= 0) {
            HistoryRecord r = (HistoryRecord)mHistory.get(pos);
            if (localLOGV) Log.v(
                TAG, "At " + pos + " ckp " + r.task + ": " + r);
            boolean first = true;
            if (r.task.taskId == task) {
                if (localLOGV) Log.v(TAG, "Removing and adding at " + top);
                mHistory.remove(pos);
                mHistory.add(top, r);
                moved.add(0, r);
                top--;
                if (first) {
                    addRecentTask(r.task);
                    first = false;
                }
            }
            pos--;
        }

        mWindowManager.moveAppTokensToTop(moved);
        if (VALIDATE_TOKENS) {
            mWindowManager.validateAppTokens(mHistory);
        }

        finishTaskMove(task);
        EventLog.writeEvent(LOG_TASK_TO_FRONT, task);
    }

    private final void finishTaskMove(int task) {
        resumeTopActivityLocked(null);
    }

    public void moveTaskToBack(int task) {
        enforceCallingPermission(android.Manifest.permission.REORDER_TASKS,
                "moveTaskToBack()");

        synchronized(this) {
            final long origId = Binder.clearCallingIdentity();
            moveTaskToBackLocked(task);
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
                return moveTaskToBackLocked(taskId);
            }
            Binder.restoreCallingIdentity(origId);
        }
        return false;
    }

    /**
     * Worker method for rearranging history stack.  Implements the function of moving all 
     * activities for a specific task (gathering them if disjoint) into a single group at the 
     * bottom of the stack.
     * 
     * If a watcher is installed, the action is preflighted and the watcher has an opportunity
     * to premeptively cancel the move.
     * 
     * @param task The taskId to collect and move to the bottom.
     * @return Returns true if the move completed, false if not.
     */
    private final boolean moveTaskToBackLocked(int task) {
        Log.i(TAG, "moveTaskToBack: " + task);
        
        // If we have a watcher, preflight the move before committing to it.  First check
        // for *other* available tasks, but if none are available, then try again allowing the
        // current task to be selected.
        if (mWatcher != null) {
            HistoryRecord next = topRunningActivityLocked(null, task);
            if (next == null) {
                next = topRunningActivityLocked(null, 0);
            }
            if (next != null) {
                // ask watcher if this is allowed
                boolean moveOK = true;
                try {
                    moveOK = mWatcher.activityResuming(next.packageName);
                } catch (RemoteException e) {
                    mWatcher = null;
                }
                if (!moveOK) {
                    return false;
                }
            }
        }

        ArrayList moved = new ArrayList();

        if (DEBUG_TRANSITION) Log.v(TAG,
                "Prepare to back transition: task=" + task);
        mWindowManager.prepareAppTransition(WindowManagerPolicy.TRANSIT_TASK_TO_BACK);
        
        final int N = mHistory.size();
        int bottom = 0;
        int pos = 0;

        // Shift all activities with this task down to the bottom
        // of the stack, keeping them in the same internal order.
        while (pos < N) {
            HistoryRecord r = (HistoryRecord)mHistory.get(pos);
            if (localLOGV) Log.v(
                TAG, "At " + pos + " ckp " + r.task + ": " + r);
            if (r.task.taskId == task) {
                if (localLOGV) Log.v(TAG, "Removing and adding at " + (N-1));
                mHistory.remove(pos);
                mHistory.add(bottom, r);
                moved.add(r);
                bottom++;
            }
            pos++;
        }

        mWindowManager.moveAppTokensToBottom(moved);
        if (VALIDATE_TOKENS) {
            mWindowManager.validateAppTokens(mHistory);
        }

        finishTaskMove(task);
        return true;
    }

    public void moveTaskBackwards(int task) {
        enforceCallingPermission(android.Manifest.permission.REORDER_TASKS,
                "moveTaskBackwards()");

        synchronized(this) {
            final long origId = Binder.clearCallingIdentity();
            moveTaskBackwardsLocked(task);
            Binder.restoreCallingIdentity(origId);
        }
    }

    private final void moveTaskBackwardsLocked(int task) {
        Log.e(TAG, "moveTaskBackwards not yet implemented!");
    }

    public int getTaskForActivity(IBinder token, boolean onlyRoot) {
        synchronized(this) {
            return getTaskForActivityLocked(token, onlyRoot);
        }
    }

    int getTaskForActivityLocked(IBinder token, boolean onlyRoot) {
        final int N = mHistory.size();
        TaskRecord lastTask = null;
        for (int i=0; i<N; i++) {
            HistoryRecord r = (HistoryRecord)mHistory.get(i);
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

    /**
     * Returns the top activity in any existing task matching the given
     * Intent.  Returns null if no such task is found.
     */
    private HistoryRecord findTaskLocked(Intent intent, ActivityInfo info) {
        ComponentName cls = intent.getComponent();
        if (info.targetActivity != null) {
            cls = new ComponentName(info.packageName, info.targetActivity);
        }

        TaskRecord cp = null;

        final int N = mHistory.size();
        for (int i=(N-1); i>=0; i--) {
            HistoryRecord r = (HistoryRecord)mHistory.get(i);
            if (!r.finishing && r.task != cp
                    && r.launchMode != ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
                cp = r.task;
                //Log.i(TAG, "Comparing existing cls=" + r.task.intent.getComponent().flattenToShortString()
                //        + "/aff=" + r.task.affinity + " to new cls="
                //        + intent.getComponent().flattenToShortString() + "/aff=" + taskAffinity);
                if (r.task.affinity != null) {
                    if (r.task.affinity.equals(info.taskAffinity)) {
                        //Log.i(TAG, "Found matching affinity!");
                        return r;
                    }
                } else if (r.task.intent != null
                        && r.task.intent.getComponent().equals(cls)) {
                    //Log.i(TAG, "Found matching class!");
                    //dump();
                    //Log.i(TAG, "For Intent " + intent + " bringing to top: " + r.intent);
                    return r;
                } else if (r.task.affinityIntent != null
                        && r.task.affinityIntent.getComponent().equals(cls)) {
                    //Log.i(TAG, "Found matching class!");
                    //dump();
                    //Log.i(TAG, "For Intent " + intent + " bringing to top: " + r.intent);
                    return r;
                }
            }
        }

        return null;
    }

    /**
     * Returns the first activity (starting from the top of the stack) that
     * is the same as the given activity.  Returns null if no such activity
     * is found.
     */
    private HistoryRecord findActivityLocked(Intent intent, ActivityInfo info) {
        ComponentName cls = intent.getComponent();
        if (info.targetActivity != null) {
            cls = new ComponentName(info.packageName, info.targetActivity);
        }

        final int N = mHistory.size();
        for (int i=(N-1); i>=0; i--) {
            HistoryRecord r = (HistoryRecord)mHistory.get(i);
            if (!r.finishing) {
                if (r.intent.getComponent().equals(cls)) {
                    //Log.i(TAG, "Found matching class!");
                    //dump();
                    //Log.i(TAG, "For Intent " + intent + " bringing to top: " + r.intent);
                    return r;
                }
            }
        }

        return null;
    }

    public void finishOtherInstances(IBinder token, ComponentName className) {
        synchronized(this) {
            final long origId = Binder.clearCallingIdentity();

            int N = mHistory.size();
            TaskRecord lastTask = null;
            for (int i=0; i<N; i++) {
                HistoryRecord r = (HistoryRecord)mHistory.get(i);
                if (r.realActivity.equals(className)
                        && r != token && lastTask != r.task) {
                    if (finishActivityLocked(r, i, Activity.RESULT_CANCELED,
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

    final void sendPendingThumbnail(HistoryRecord r, IBinder token,
            Bitmap thumbnail, CharSequence description, boolean always) {
        TaskRecord task = null;
        ArrayList receivers = null;

        //System.out.println("Send pending thumbnail: " + r);

        synchronized(this) {
            if (r == null) {
                int index = indexOfTokenLocked(token, false);
                if (index < 0) {
                    return;
                }
                r = (HistoryRecord)mHistory.get(index);
            }
            if (thumbnail == null) {
                thumbnail = r.thumbnail;
                description = r.description;
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
                    Log.w(TAG, "Exception thrown when sending thumbnail", e);
                }
            }
        }
    }

    // =========================================================
    // CONTENT PROVIDERS
    // =========================================================

    private final List generateApplicationProvidersLocked(ProcessRecord app) {
        List providers = null;
        try {
            providers = ActivityThread.getPackageManager().
                queryContentProviders(app.processName, app.info.uid,
                        PackageManager.GET_SHARED_LIBRARY_FILES
                        | PackageManager.GET_URI_PERMISSION_PATTERNS);
        } catch (RemoteException ex) {
        }
        if (providers != null) {
            final int N = providers.size();
            for (int i=0; i<N; i++) {
                ProviderInfo cpi =
                    (ProviderInfo)providers.get(i);
                ContentProviderRecord cpr =
                    (ContentProviderRecord)mProvidersByClass.get(cpi.name);
                if (cpr == null) {
                    cpr = new ContentProviderRecord(cpi, app.info);
                    mProvidersByClass.put(cpi.name, cpr);
                }
                app.pubProviders.put(cpi.name, cpr);
                app.addPackage(cpi.applicationInfo.packageName);
            }
        }
        return providers;
    }

    private final String checkContentProviderPermissionLocked(
            ProviderInfo cpi, ProcessRecord r, int mode) {
        final int callingPid = (r != null) ? r.pid : Binder.getCallingPid();
        final int callingUid = (r != null) ? r.info.uid : Binder.getCallingUid();
        if (checkComponentPermission(cpi.readPermission, callingPid, callingUid,
                cpi.exported ? -1 : cpi.applicationInfo.uid)
                == PackageManager.PERMISSION_GRANTED
                && mode == ParcelFileDescriptor.MODE_READ_ONLY || mode == -1) {
            return null;
        }
        if (checkComponentPermission(cpi.writePermission, callingPid, callingUid,
                cpi.exported ? -1 : cpi.applicationInfo.uid)
                == PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        String msg = "Permission Denial: opening provider " + cpi.name
                + " from " + (r != null ? r : "(null)") + " (pid=" + callingPid
                + ", uid=" + callingUid + ") requires "
                + cpi.readPermission + " or " + cpi.writePermission;
        Log.w(TAG, msg);
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
            cpr = (ContentProviderRecord)mProvidersByName.get(name);
            if (cpr != null) {
                cpi = cpr.info;
                if (checkContentProviderPermissionLocked(cpi, r, -1) != null) {
                    return new ContentProviderHolder(cpi,
                            cpi.readPermission != null
                                    ? cpi.readPermission : cpi.writePermission);
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

                // In this case the provider is a single instance, so we can
                // return it right away.
                if (r != null) {
                    r.conProviders.add(cpr);
                    cpr.clients.add(r);
                } else {
                    cpr.externals++;
                }

                if (cpr.app != null) {
                    updateOomAdjLocked(cpr.app);
                }

                Binder.restoreCallingIdentity(origId);

            } else {
                try {
                    cpi = ActivityThread.getPackageManager().
                        resolveContentProvider(name, PackageManager.GET_URI_PERMISSION_PATTERNS);
                } catch (RemoteException ex) {
                }
                if (cpi == null) {
                    return null;
                }

                if (checkContentProviderPermissionLocked(cpi, r, -1) != null) {
                    return new ContentProviderHolder(cpi,
                            cpi.readPermission != null
                                    ? cpi.readPermission : cpi.writePermission);
                }

                cpr = (ContentProviderRecord)mProvidersByClass.get(cpi.name);
                final boolean firstClass = cpr == null;
                if (firstClass) {
                    try {
                        ApplicationInfo ai =
                            ActivityThread.getPackageManager().
                                getApplicationInfo(
                                        cpi.applicationInfo.packageName,
                                        PackageManager.GET_SHARED_LIBRARY_FILES);
                        if (ai == null) {
                            Log.w(TAG, "No package info for content provider "
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

                if (false) {
                    RuntimeException e = new RuntimeException("foo");
                    //Log.w(TAG, "LAUNCHING REMOTE PROVIDER (myuid " + r.info.uid
                    //      + " pruid " + ai.uid + "): " + cpi.className, e);
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
                    if (false) {
                        final ContentProviderRecord rec =
                            (ContentProviderRecord)mLaunchingProviders.get(i);
                        if (rec.info.name.equals(cpr.info.name)) {
                            cpr = rec;
                            break;
                        }
                    }
                }

                // If the provider is not already being launched, then get it
                // started.
                if (i >= N) {
                    final long origId = Binder.clearCallingIdentity();
                    ProcessRecord proc = startProcessLocked(cpi.processName,
                            cpr.appInfo, false, 0, "content provider",
                            new ComponentName(cpi.applicationInfo.packageName,
                                    cpi.name));
                    if (proc == null) {
                        Log.w(TAG, "Unable to launch app "
                                + cpi.applicationInfo.packageName + "/"
                                + cpi.applicationInfo.uid + " for provider "
                                + name + ": process is bad");
                        return null;
                    }
                    cpr.launchingApp = proc;
                    mLaunchingProviders.add(cpr);
                    Binder.restoreCallingIdentity(origId);
                }

                // Make sure the provider is published (the same provider class
                // may be published under multiple names).
                if (firstClass) {
                    mProvidersByClass.put(cpi.name, cpr);
                }
                mProvidersByName.put(name, cpr);

                if (r != null) {
                    r.conProviders.add(cpr);
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
                    Log.w(TAG, "Unable to launch app "
                            + cpi.applicationInfo.packageName + "/"
                            + cpi.applicationInfo.uid + " for provider "
                            + name + ": launching app became null");
                    EventLog.writeEvent(LOG_AM_PROVIDER_LOST_PROCESS,
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
            Log.w(TAG, msg);
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
            ContentProviderRecord cpr = (ContentProviderRecord)mProvidersByName.get(name);
            if(cpr == null) {
                //remove from mProvidersByClass
                if(localLOGV) Log.v(TAG, name+" content provider not found in providers list");
                return;
            }
            final ProcessRecord r = getRecordForAppLocked(caller);
            if (r == null) {
                throw new SecurityException(
                        "Unable to find app for caller " + caller +
                        " when removing content provider " + name);
            }
            //update content provider record entry info
            ContentProviderRecord localCpr = (ContentProviderRecord) mProvidersByClass.get(cpr.info.name);
            if(localLOGV) Log.v(TAG, "Removing content provider requested by "+
                    r.info.processName+" from process "+localCpr.appInfo.processName);
            if(localCpr.appInfo.processName ==  r.info.processName) {
                //should not happen. taken care of as a local provider
                if(localLOGV) Log.v(TAG, "local provider doing nothing Ignoring other names");
                return;
            } else {
                localCpr.clients.remove(r);
                r.conProviders.remove(localCpr);
            }
            updateOomAdjLocked();
        }
    }

    private void removeContentProviderExternal(String name) {
        synchronized (this) {
            ContentProviderRecord cpr = (ContentProviderRecord)mProvidersByName.get(name);
            if(cpr == null) {
                //remove from mProvidersByClass
                if(localLOGV) Log.v(TAG, name+" content provider not found in providers list");
                return;
            }

            //update content provider record entry info
            ContentProviderRecord localCpr = (ContentProviderRecord) mProvidersByClass.get(cpr.info.name);
            localCpr.externals--;
            if (localCpr.externals < 0) {
                Log.e(TAG, "Externals < 0 for content provider " + localCpr);
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
                ContentProviderRecord dst =
                    (ContentProviderRecord)r.pubProviders.get(src.info.name);
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
        ProcessRecord app = mSelf.mProcessNames.get("system", Process.SYSTEM_UID);
        List providers = mSelf.generateApplicationProvidersLocked(app);
        mSystemThread.installSystemProviders(providers);
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
            updateLRUListLocked(app, true);
        }

        if ((info.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_PERSISTENT))
                == (ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_PERSISTENT)) {
            app.persistent = true;
            app.maxAdj = CORE_SERVER_ADJ;
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
            int count = mHistory.size();
            if (Config.LOGD) Log.d(
                TAG, "Performing unhandledBack(): stack size = " + count);
            if (count > 1) {
                final long origId = Binder.clearCallingIdentity();
                finishActivityLocked((HistoryRecord)mHistory.get(count-1),
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
            Log.d(TAG, "Failed to get provider for authority '" + name + "'");
        }
        return pfd;
    }

    public void goingToSleep() {
        synchronized(this) {
            mSleeping = true;
            mWindowManager.setEventDispatching(false);

            if (mResumedActivity != null) {
                pauseIfSleepingLocked();
            } else {
                Log.w(TAG, "goingToSleep with no resumed activity!");
            }
        }
    }

    void pauseIfSleepingLocked() {
        if (mSleeping) {
            if (!mGoingToSleep.isHeld()) {
                mGoingToSleep.acquire();
                if (mLaunchingActivity.isHeld()) {
                    mLaunchingActivity.release();
                    mHandler.removeMessages(LAUNCH_TIMEOUT_MSG);
                }
            }

            // If we are not currently pausing an activity, get the current
            // one to pause.  If we are pausing one, we will just let that stuff
            // run and release the wake lock when all done.
            if (mPausingActivity == null) {
                if (DEBUG_PAUSE) Log.v(TAG, "Sleep needs to pause...");
                if (DEBUG_USER_LEAVING) Log.v(TAG, "Sleep => pause with userLeaving=false");
                startPausingLocked(false, true);
            }
        }
    }
    
    public void wakingUp() {
        synchronized(this) {
            if (mGoingToSleep.isHeld()) {
                mGoingToSleep.release();
            }
            mWindowManager.setEventDispatching(true);
            mSleeping = false;
            resumeTopActivityLocked(null);
        }
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
                uninstallPackageLocked(packageName, -1, false);
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

    public void setActivityWatcher(IActivityWatcher watcher) {
        enforceCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER,
                "setActivityWatcher()");
        synchronized (this) {
            mWatcher = watcher;
        }
    }

    public final void enterSafeMode() {
        synchronized(this) {
            // It only makes sense to do this before the system is ready
            // and started launching other packages.
            if (!mSystemReady) {
                try {
                    ActivityThread.getPackageManager().enterSafeMode();
                } catch (RemoteException e) {
                }

                View v = LayoutInflater.from(mContext).inflate(
                        com.android.internal.R.layout.safe_mode, null);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
                lp.type = WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
                lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
                lp.format = v.getBackground().getOpacity();
                lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                ((WindowManager)mContext.getSystemService(
                        Context.WINDOW_SERVICE)).addView(v, lp);
            }
        }
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

    public boolean killPidsForMemory(int[] pids) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("killPidsForMemory only available to the system");
        }
        
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
            
            // If the worse oom_adj is somewhere in the hidden proc LRU range,
            // then constrain it so we will kill all hidden procs.
            if (worstType < EMPTY_APP_ADJ && worstType > HIDDEN_APP_MIN_ADJ) {
                worstType = HIDDEN_APP_MIN_ADJ;
            }
            Log.w(TAG, "Killing processes for memory at adjustment " + worstType);
            for (int i=0; i<pids.length; i++) {
                ProcessRecord proc = mPidsSelfLocked.get(pids[i]);
                if (proc == null) {
                    continue;
                }
                int adj = proc.setAdj;
                if (adj >= worstType) {
                    Log.w(TAG, "Killing for memory: " + proc + " (adj "
                            + adj + ")");
                    EventLog.writeEvent(LOG_AM_KILL_FOR_MEMORY, proc.pid,
                            proc.processName, adj);
                    killed = true;
                    Process.killProcess(pids[i]);
                }
            }
        }
        return killed;
    }
    
    public void reportPss(IApplicationThread caller, int pss) {
        Watchdog.PssRequestor req;
        String name;
        ProcessRecord callerApp;
        synchronized (this) {
            if (caller == null) {
                return;
            }
            callerApp = getRecordForAppLocked(caller);
            if (callerApp == null) {
                return;
            }
            callerApp.lastPss = pss;
            req = callerApp;
            name = callerApp.processName;
        }
        Watchdog.getInstance().reportPss(req, name, pss);
        if (!callerApp.persistent) {
            removeRequestedPss(callerApp);
        }
    }
    
    public void requestPss(Runnable completeCallback) {
        ArrayList<ProcessRecord> procs;
        synchronized (this) {
            mRequestPssCallback = completeCallback;
            mRequestPssList.clear();
            for (int i=mLRUProcesses.size()-1; i>=0; i--) {
                ProcessRecord proc = mLRUProcesses.get(i);
                if (!proc.persistent) {
                    mRequestPssList.add(proc);
                }
            }
            procs = new ArrayList<ProcessRecord>(mRequestPssList);
        }
        
        int oldPri = Process.getThreadPriority(Process.myTid()); 
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        for (int i=procs.size()-1; i>=0; i--) {
            ProcessRecord proc = procs.get(i);
            proc.lastPss = 0;
            proc.requestPss();
        }
        Process.setThreadPriority(oldPri);
    }
    
    void removeRequestedPss(ProcessRecord proc) {
        Runnable callback = null;
        synchronized (this) {
            if (mRequestPssList.remove(proc)) {
                if (mRequestPssList.size() == 0) {
                    callback = mRequestPssCallback;
                    mRequestPssCallback = null;
                }
            }
        }
        
        if (callback != null) {
            callback.run();
        }
    }
    
    public void collectPss(Watchdog.PssStats stats) {
        stats.mEmptyPss = 0;
        stats.mEmptyCount = 0;
        stats.mBackgroundPss = 0;
        stats.mBackgroundCount = 0;
        stats.mServicePss = 0;
        stats.mServiceCount = 0;
        stats.mVisiblePss = 0;
        stats.mVisibleCount = 0;
        stats.mForegroundPss = 0;
        stats.mForegroundCount = 0;
        stats.mNoPssCount = 0;
        synchronized (this) {
            int i;
            int NPD = mProcDeaths.length < stats.mProcDeaths.length
                    ? mProcDeaths.length : stats.mProcDeaths.length;
            int aggr = 0;
            for (i=0; i<NPD; i++) {
                aggr += mProcDeaths[i];
                stats.mProcDeaths[i] = aggr;
            }
            while (i<stats.mProcDeaths.length) {
                stats.mProcDeaths[i] = 0;
                i++;
            }
            
            for (i=mLRUProcesses.size()-1; i>=0; i--) {
                ProcessRecord proc = mLRUProcesses.get(i);
                if (proc.persistent) {
                    continue;
                }
                //Log.i(TAG, "Proc " + proc + ": pss=" + proc.lastPss);
                if (proc.lastPss == 0) {
                    stats.mNoPssCount++;
                    continue;
                }
                if (proc.setAdj == EMPTY_APP_ADJ) {
                    stats.mEmptyPss += proc.lastPss;
                    stats.mEmptyCount++;
                } else if (proc.setAdj == CONTENT_PROVIDER_ADJ) {
                    stats.mEmptyPss += proc.lastPss;
                    stats.mEmptyCount++;
                } else if (proc.setAdj >= HIDDEN_APP_MIN_ADJ) {
                    stats.mBackgroundPss += proc.lastPss;
                    stats.mBackgroundCount++;
                } else if (proc.setAdj >= VISIBLE_APP_ADJ) {
                    stats.mVisiblePss += proc.lastPss;
                    stats.mVisibleCount++;
                } else {
                    stats.mForegroundPss += proc.lastPss;
                    stats.mForegroundCount++;
                }
            }
        }
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

        systemReady();
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
        }
    }

    public boolean testIsSystemReady() {
        // no need to synchronize(this) just to read & return the value
        return mSystemReady;
    }
    
    public void systemReady() {
        // In the simulator, startRunning will never have been called, which
        // normally sets a few crucial variables. Do it here instead.
        if (!Process.supportsProcesses()) {
            mStartRunning = true;
            mTopAction = Intent.ACTION_MAIN;
        }

        synchronized(this) {
            if (mSystemReady) {
                return;
            }
            mSystemReady = true;
            if (!mStartRunning) {
                return;
            }
        }

        if (Config.LOGD) Log.d(TAG, "Start running!");
        EventLog.writeEvent(LOG_BOOT_PROGRESS_AMS_READY,
            SystemClock.uptimeMillis());

        synchronized(this) {
            if (mFactoryTest == SystemServer.FACTORY_TEST_LOW_LEVEL) {
                ResolveInfo ri = mContext.getPackageManager()
                        .resolveActivity(new Intent(Intent.ACTION_FACTORY_TEST),
                                0);
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

        synchronized (this) {
            if (mFactoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL) {
                try {
                    List apps = ActivityThread.getPackageManager().
                        getPersistentApplications(PackageManager.GET_SHARED_LIBRARY_FILES);
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

            try {
                if (ActivityThread.getPackageManager().hasSystemUidErrors()) {
                    Message msg = Message.obtain();
                    msg.what = SHOW_UID_ERROR_MSG;
                    mHandler.sendMessage(msg);
                }
            } catch (RemoteException e) {
            }

            // Start up initial activity.
            mBooting = true;
            resumeTopActivityLocked(null);
        }
    }

    boolean makeAppCrashingLocked(ProcessRecord app,
            String tag, String shortMsg, String longMsg, byte[] crashData) {
        app.crashing = true;
        app.crashingReport = generateProcessError(app, 
                ActivityManager.ProcessErrorStateInfo.CRASHED, tag, shortMsg, longMsg, crashData);
        startAppProblemLocked(app);
        app.stopFreezingAllLocked();
        return handleAppCrashLocked(app);
    }

    void makeAppNotRespondingLocked(ProcessRecord app,
            String tag, String shortMsg, String longMsg, byte[] crashData) {
        app.notResponding = true;
        app.notRespondingReport = generateProcessError(app, 
                ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING, tag, shortMsg, longMsg, 
                crashData);
        startAppProblemLocked(app);
        app.stopFreezingAllLocked();
    }
    
    /**
     * Generate a process error record, suitable for attachment to a ProcessRecord.
     * 
     * @param app The ProcessRecord in which the error occurred.
     * @param condition Crashing, Application Not Responding, etc.  Values are defined in 
     *                      ActivityManager.AppErrorStateInfo
     * @param tag The tag that was passed into handleApplicationError().  Typically the classname.
     * @param shortMsg Short message describing the crash.
     * @param longMsg Long message describing the crash.
     * @param crashData Raw data passed into handleApplicationError().  Typically a stack trace.
     * 
     * @return Returns a fully-formed AppErrorStateInfo record.
     */
    private ActivityManager.ProcessErrorStateInfo generateProcessError(ProcessRecord app, 
            int condition, String tag, String shortMsg, String longMsg, byte[] crashData) {
        ActivityManager.ProcessErrorStateInfo report = new ActivityManager.ProcessErrorStateInfo();
        
        report.condition = condition;
        report.processName = app.processName;
        report.pid = app.pid;
        report.uid = app.info.uid;
        report.tag = tag;
        report.shortMsg = shortMsg;
        report.longMsg = longMsg;
        report.crashData = crashData;

        return report;
    }

    void killAppAtUsersRequest(ProcessRecord app, Dialog fromDialog,
            boolean crashed) {
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
                if (crashed) {
                    handleAppCrashLocked(app);
                }
                Log.i(ActivityManagerService.TAG, "Killing process "
                        + app.processName
                        + " (pid=" + app.pid + ") at user's request");
                Process.killProcess(app.pid);
            }
            
        }
    }
    
    boolean handleAppCrashLocked(ProcessRecord app) {
        long now = SystemClock.uptimeMillis();

        Long crashTime = mProcessCrashTimes.get(app.info.processName,
                app.info.uid);
        if (crashTime != null && now < crashTime+MIN_CRASH_INTERVAL) {
            // This process loses!
            Log.w(TAG, "Process " + app.info.processName
                    + " has crashed too many times: killing!");
            EventLog.writeEvent(LOG_AM_PROCESS_CRASHED_TOO_MUCH,
                    app.info.processName, app.info.uid);
            killServicesLocked(app, false);
            for (int i=mHistory.size()-1; i>=0; i--) {
                HistoryRecord r = (HistoryRecord)mHistory.get(i);
                if (r.app == app) {
                    if (Config.LOGD) Log.d(
                        TAG, "  Force finishing activity "
                        + r.intent.getComponent().flattenToShortString());
                    finishActivityLocked(r, i, Activity.RESULT_CANCELED, null, "crashed");
                }
            }
            if (!app.persistent) {
                // We don't want to start this process again until the user
                // explicitly does so...  but for persistent process, we really
                // need to keep it running.  If a persistent process is actually
                // repeatedly crashing, then badness for everyone.
                EventLog.writeEvent(LOG_AM_PROCESS_BAD, app.info.uid,
                        app.info.processName);
                mBadProcesses.put(app.info.processName, app.info.uid, now);
                app.bad = true;
                mProcessCrashTimes.remove(app.info.processName, app.info.uid);
                app.removed = true;
                removeProcessLocked(app, false);
                return false;
            }
        }

        // Bump up the crash count of any services currently running in the proc.
        if (app.services.size() != 0) {
            // Any services running in the application need to be placed
            // back in the pending list.
            Iterator it = app.services.iterator();
            while (it.hasNext()) {
                ServiceRecord sr = (ServiceRecord)it.next();
                sr.crashCount++;
            }
        }
        
        mProcessCrashTimes.put(app.info.processName, app.info.uid, now);
        return true;
    }

    void startAppProblemLocked(ProcessRecord app) {
        skipCurrentReceiverLocked(app);
    }

    void skipCurrentReceiverLocked(ProcessRecord app) {
        boolean reschedule = false;
        BroadcastRecord r = app.curReceiver;
        if (r != null) {
            // The current broadcast is waiting for this app's receiver
            // to be finished.  Looks like that's not going to happen, so
            // let the broadcast continue.
            logBroadcastReceiverDiscard(r);
            finishReceiverLocked(r.receiver, r.resultCode, r.resultData,
                    r.resultExtras, r.resultAbort, true);
            reschedule = true;
        }
        r = mPendingBroadcast;
        if (r != null && r.curApp == app) {
            if (DEBUG_BROADCAST) Log.v(TAG,
                    "skip & discard pending app " + r);
            logBroadcastReceiverDiscard(r);
            finishReceiverLocked(r.receiver, r.resultCode, r.resultData,
                    r.resultExtras, r.resultAbort, true);
            reschedule = true;
        }
        if (reschedule) {
            scheduleBroadcastsLocked();
        }
    }

    public int handleApplicationError(IBinder app, int flags,
            String tag, String shortMsg, String longMsg, byte[] crashData) {
        AppErrorResult result = new AppErrorResult();

        ProcessRecord r = null;
        synchronized (this) {
            if (app != null) {
                for (SparseArray<ProcessRecord> apps : mProcessNames.getMap().values()) {
                    final int NA = apps.size();
                    for (int ia=0; ia<NA; ia++) {
                        ProcessRecord p = apps.valueAt(ia);
                        if (p.thread != null && p.thread.asBinder() == app) {
                            r = p;
                            break;
                        }
                    }
                }
            }

            if (r != null) {
                // The application has crashed. Send the SIGQUIT to the process so
                // that it can dump its state.
                Process.sendSignal(r.pid, Process.SIGNAL_QUIT);
                //Log.i(TAG, "Current system threads:");
                //Process.sendSignal(MY_PID, Process.SIGNAL_QUIT);
            }

            if (mWatcher != null) {
                try {
                    String name = r != null ? r.processName : null;
                    int pid = r != null ? r.pid : Binder.getCallingPid();
                    if (!mWatcher.appCrashed(name, pid,
                            shortMsg, longMsg, crashData)) {
                        Log.w(TAG, "Force-killing crashed app " + name
                                + " at watcher's request");
                        Process.killProcess(pid);
                        return 0;
                    }
                } catch (RemoteException e) {
                    mWatcher = null;
                }
            }

            final long origId = Binder.clearCallingIdentity();

            // If this process is running instrumentation, finish it.
            if (r != null && r.instrumentationClass != null) {
                Log.w(TAG, "Error in app " + r.processName
                      + " running instrumentation " + r.instrumentationClass + ":");
                if (shortMsg != null) Log.w(TAG, "  " + shortMsg);
                if (longMsg != null) Log.w(TAG, "  " + longMsg);
                Bundle info = new Bundle();
                info.putString("shortMsg", shortMsg);
                info.putString("longMsg", longMsg);
                finishInstrumentationLocked(r, Activity.RESULT_CANCELED, info);
                Binder.restoreCallingIdentity(origId);
                return 0;
            }

            if (r != null) {
                if (!makeAppCrashingLocked(r, tag, shortMsg, longMsg, crashData)) {
                    return 0;
                }
            } else {
                Log.w(TAG, "Some application object " + app + " tag " + tag
                        + " has crashed, but I don't know who it is.");
                Log.w(TAG, "ShortMsg:" + shortMsg);
                Log.w(TAG, "LongMsg:" + longMsg);
                Binder.restoreCallingIdentity(origId);
                return 0;
            }

            Message msg = Message.obtain();
            msg.what = SHOW_ERROR_MSG;
            HashMap data = new HashMap();
            data.put("result", result);
            data.put("app", r);
            data.put("flags", flags);
            data.put("shortMsg", shortMsg);
            data.put("longMsg", longMsg);
            if (r != null && (r.info.flags&ApplicationInfo.FLAG_SYSTEM) != 0) {
                // For system processes, submit crash data to the server.
                data.put("crashData", crashData);
            }
            msg.obj = data;
            mHandler.sendMessage(msg);

            Binder.restoreCallingIdentity(origId);
        }

        int res = result.get();

        synchronized (this) {
            if (r != null) {
                mProcessCrashTimes.put(r.info.processName, r.info.uid,
                        SystemClock.uptimeMillis());
            }
        }

        return res;
    }
    
    public List<ActivityManager.ProcessErrorStateInfo> getProcessesInErrorState() {
        // assume our apps are happy - lazy create the list
        List<ActivityManager.ProcessErrorStateInfo> errList = null;

        synchronized (this) {

            // iterate across all processes
            final int N = mLRUProcesses.size();
            for (int i = 0; i < N; i++) {
                ProcessRecord app = mLRUProcesses.get(i);
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
                        Log.w(TAG, "Missing app error report, app = " + app.processName + 
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
            final int N = mLRUProcesses.size();
            for (int i = 0; i < N; i++) {
                ProcessRecord app = mLRUProcesses.get(i);
                if ((app.thread != null) && (!app.crashing && !app.notResponding)) {
                    // Generate process state info for running application
                    ActivityManager.RunningAppProcessInfo currApp = 
                        new ActivityManager.RunningAppProcessInfo(app.processName,
                                app.pid, app.getPackageList());
                    int adj = app.curAdj;
                    if (adj >= CONTENT_PROVIDER_ADJ) {
                        currApp.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY;
                    } else if (adj >= HIDDEN_APP_MIN_ADJ) {
                        currApp.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND;
                        currApp.lru = adj - HIDDEN_APP_MIN_ADJ + 1;
                    } else if (adj >= HOME_APP_ADJ) {
                        currApp.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND;
                        currApp.lru = 0;
                    } else if (adj >= SECONDARY_SERVER_ADJ) {
                        currApp.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE;
                    } else if (adj >= VISIBLE_APP_ADJ) {
                        currApp.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
                    } else {
                        currApp.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                    }
                    //Log.v(TAG, "Proc " + app.processName + ": imp=" + currApp.importance
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

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (this) {
            if (checkCallingPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump ActivityManager from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " without permission "
                        + android.Manifest.permission.DUMP);
                return;
            }
            if (args.length != 0 && "service".equals(args[0])) {
                dumpService(fd, pw, args);
                return;
            }
            pw.println("Activities in Current Activity Manager State:");
            dumpHistoryList(pw, mHistory, "  ", "History");
            pw.println(" ");
            pw.println("  Running activities (most recent first):");
            dumpHistoryList(pw, mLRUActivities, "  ", "Running");
            if (mWaitingVisibleActivities.size() > 0) {
                pw.println(" ");
                pw.println("  Activities waiting for another to become visible:");
                dumpHistoryList(pw, mWaitingVisibleActivities, "  ", "Waiting");
            }
            if (mStoppingActivities.size() > 0) {
                pw.println(" ");
                pw.println("  Activities waiting to stop:");
                dumpHistoryList(pw, mStoppingActivities, "  ", "Stopping");
            }
            if (mFinishingActivities.size() > 0) {
                pw.println(" ");
                pw.println("  Activities waiting to finish:");
                dumpHistoryList(pw, mFinishingActivities, "  ", "Finishing");
            }

            pw.println(" ");
            pw.println("  mPausingActivity: " + mPausingActivity);
            pw.println("  mResumedActivity: " + mResumedActivity);
            pw.println("  mFocusedActivity: " + mFocusedActivity);
            pw.println("  mLastPausedActivity: " + mLastPausedActivity);

            if (mRecentTasks.size() > 0) {
                pw.println(" ");
                pw.println("Recent tasks in Current Activity Manager State:");

                final int N = mRecentTasks.size();
                for (int i=0; i<N; i++) {
                    pw.println("  Recent Task #" + i);
                    mRecentTasks.get(i).dump(pw, "    ");
                }
            }
            
            pw.println(" ");
            pw.println("  mCurTask: " + mCurTask);
            
            pw.println(" ");
            pw.println("Processes in Current Activity Manager State:");

            boolean needSep = false;
            int numPers = 0;

            for (SparseArray<ProcessRecord> procs : mProcessNames.getMap().values()) {
                final int NA = procs.size();
                for (int ia=0; ia<NA; ia++) {
                    if (!needSep) {
                        pw.println("  All known processes:");
                        needSep = true;
                    }
                    ProcessRecord r = procs.valueAt(ia);
                    pw.println((r.persistent ? "  *PERSISTENT* Process [" : "  Process [")
                            + r.processName + "] UID " + procs.keyAt(ia));
                    r.dump(pw, "    ");
                    if (r.persistent) {
                        numPers++;
                    }
                }
            }
            
            if (mLRUProcesses.size() > 0) {
                if (needSep) pw.println(" ");
                needSep = true;
                pw.println("  Running processes (most recent first):");
                dumpProcessList(pw, mLRUProcesses, "    ",
                        "Running Norm Proc", "Running PERS Proc", true);
                needSep = true;
            }

            synchronized (mPidsSelfLocked) {
                if (mPidsSelfLocked.size() > 0) {
                    if (needSep) pw.println(" ");
                    needSep = true;
                    pw.println("  PID mappings:");
                    for (int i=0; i<mPidsSelfLocked.size(); i++) {
                        pw.println("    PID #" + mPidsSelfLocked.keyAt(i)
                                + ": " + mPidsSelfLocked.valueAt(i));
                    }
                }
            }
            
            if (mForegroundProcesses.size() > 0) {
                if (needSep) pw.println(" ");
                needSep = true;
                pw.println("  Foreground Processes:");
                for (int i=0; i<mForegroundProcesses.size(); i++) {
                    pw.println("    PID #" + mForegroundProcesses.keyAt(i)
                            + ": " + mForegroundProcesses.valueAt(i));
                }
            }
            
            if (mPersistentStartingProcesses.size() > 0) {
                if (needSep) pw.println(" ");
                needSep = true;
                pw.println("  Persisent processes that are starting:");
                dumpProcessList(pw, mPersistentStartingProcesses, "    ",
                        "Starting Initial Proc", "Restarting PERS Proc", false);
            }

            if (mStartingProcesses.size() > 0) {
                if (needSep) pw.println(" ");
                needSep = true;
                pw.println("  Processes that are starting:");
                dumpProcessList(pw, mStartingProcesses, "    ",
                        "Starting Norm Proc", "Starting PERS Proc", false);
            }

            if (mRemovedProcesses.size() > 0) {
                if (needSep) pw.println(" ");
                needSep = true;
                pw.println("  Processes that are being removed:");
                dumpProcessList(pw, mRemovedProcesses, "    ",
                        "Removed Norm Proc", "Removed PERS Proc", false);
            }
            
            if (mProcessesOnHold.size() > 0) {
                if (needSep) pw.println(" ");
                needSep = true;
                pw.println("  Processes that are on old until the system is ready:");
                dumpProcessList(pw, mProcessesOnHold, "    ",
                        "OnHold Norm Proc", "OnHold PERS Proc", false);
            }

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
                        pw.println("    Process " + procs.getKey()
                                + " uid " + uids.keyAt(i)
                                + ": last crashed "
                                + (now-uids.valueAt(i)) + " ms ago");
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
                        pw.println("    Bad process " + procs.getKey()
                                + " uid " + uids.keyAt(i)
                                + ": crashed at time " + uids.valueAt(i));
                    }
                }
            }

            pw.println(" ");
            pw.println("  Total persistent processes: " + numPers);
            pw.println("  mHomeProcess: " + mHomeProcess);
            pw.println("  mConfiguration: " + mConfiguration);
            pw.println("  mStartRunning=" + mStartRunning
                    + " mSystemReady=" + mSystemReady
                    + " mBooting=" + mBooting
                    + " mBooted=" + mBooted
                    + " mFactoryTest=" + mFactoryTest);
            pw.println("  mSleeping=" + mSleeping);
            pw.println("  mGoingToSleep=" + mGoingToSleep);
            pw.println("  mLaunchingActivity=" + mLaunchingActivity);
            pw.println("  mDebugApp=" + mDebugApp + "/orig=" + mOrigDebugApp
                    + " mDebugTransient=" + mDebugTransient
                    + " mOrigWaitForDebugger=" + mOrigWaitForDebugger);
            pw.println("  mAlwaysFinishActivities=" + mAlwaysFinishActivities
                    + " mWatcher=" + mWatcher);
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
    protected void dumpService(FileDescriptor fd, PrintWriter pw, String[] args) {
        String[] newArgs;
        String componentNameString;
        ServiceRecord r;
        if (args.length == 1) {
            componentNameString = null;
            newArgs = EMPTY_STRING_ARRAY;
            r = null;
        } else {
            componentNameString = args[1];
            ComponentName componentName = ComponentName.unflattenFromString(componentNameString);
            r = componentName != null ? mServices.get(componentName) : null;
            newArgs = new String[args.length - 2];
            if (args.length > 2) System.arraycopy(args, 2, newArgs, 0, args.length - 2);
        }

        if (r != null) {
            dumpService(fd, pw, r, newArgs);
        } else {
            for (ServiceRecord r1 : mServices.values()) {
                if (componentNameString == null
                        || r1.name.flattenToString().contains(componentNameString)) {
                    dumpService(fd, pw, r1, newArgs);
                }
            }
        }
    }

    /**
     * Invokes IApplicationThread.dumpService() on the thread of the specified service if
     * there is a thread associated with the service.
     */
    private void dumpService(FileDescriptor fd, PrintWriter pw, ServiceRecord r, String[] args) {
        pw.println("  Service " + r.name.flattenToString());
        if (r.app != null && r.app.thread != null) {
            try {
                // flush anything that is already in the PrintWriter since the thread is going
                // to write to the file descriptor directly
                pw.flush();
                r.app.thread.dumpService(fd, r, args);
                pw.print("\n");
            } catch (RemoteException e) {
                pw.println("got a RemoteException while dumping the service");
            }
        }
    }

    void dumpBroadcasts(PrintWriter pw) {
        synchronized (this) {
            if (checkCallingPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump ActivityManager from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " without permission "
                        + android.Manifest.permission.DUMP);
                return;
            }
            pw.println("Broadcasts in Current Activity Manager State:");

            if (mRegisteredReceivers.size() > 0) {
                pw.println(" ");
                pw.println("  Registered Receivers:");
                Iterator it = mRegisteredReceivers.values().iterator();
                while (it.hasNext()) {
                    ReceiverList r = (ReceiverList)it.next();
                    pw.println("  Receiver " + r.receiver);
                    r.dump(pw, "    ");
                }
            }

            pw.println(" ");
            pw.println("Receiver Resolver Table:");
            mReceiverResolver.dump(new PrintWriterPrinter(pw), "  ");
            
            if (mParallelBroadcasts.size() > 0 || mOrderedBroadcasts.size() > 0
                    || mPendingBroadcast != null) {
                if (mParallelBroadcasts.size() > 0) {
                    pw.println(" ");
                    pw.println("  Active broadcasts:");
                }
                for (int i=mParallelBroadcasts.size()-1; i>=0; i--) {
                    pw.println("  Broadcast #" + i + ":");
                    mParallelBroadcasts.get(i).dump(pw, "    ");
                }
                if (mOrderedBroadcasts.size() > 0) {
                    pw.println(" ");
                    pw.println("  Active serialized broadcasts:");
                }
                for (int i=mOrderedBroadcasts.size()-1; i>=0; i--) {
                    pw.println("  Serialized Broadcast #" + i + ":");
                    mOrderedBroadcasts.get(i).dump(pw, "    ");
                }
                pw.println(" ");
                pw.println("  Pending broadcast:");
                if (mPendingBroadcast != null) {
                    mPendingBroadcast.dump(pw, "    ");
                } else {
                    pw.println("    (null)");
                }
            }

            pw.println(" ");
            pw.println("  mBroadcastsScheduled=" + mBroadcastsScheduled);
            if (mStickyBroadcasts != null) {
                pw.println(" ");
                pw.println("  Sticky broadcasts:");
                for (Map.Entry<String, ArrayList<Intent>> ent
                        : mStickyBroadcasts.entrySet()) {
                    pw.println("  Sticky action " + ent.getKey() + ":");
                    ArrayList<Intent> intents = ent.getValue();
                    final int N = intents.size();
                    for (int i=0; i<N; i++) {
                        pw.println("    " + intents.get(i));
                    }
                }
            }
            
            pw.println(" ");
            pw.println("  mHandler:");
            mHandler.dump(new PrintWriterPrinter(pw), "    ");
        }
    }

    void dumpServices(PrintWriter pw) {
        synchronized (this) {
            if (checkCallingPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump ActivityManager from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " without permission "
                        + android.Manifest.permission.DUMP);
                return;
            }
            pw.println("Services in Current Activity Manager State:");

            boolean needSep = false;

            if (mServices.size() > 0) {
                pw.println("  Active services:");
                Iterator<ServiceRecord> it = mServices.values().iterator();
                while (it.hasNext()) {
                    ServiceRecord r = it.next();
                    pw.println("  Service " + r.shortName);
                    r.dump(pw, "    ");
                }
                needSep = true;
            }

            if (mPendingServices.size() > 0) {
                if (needSep) pw.println(" ");
                pw.println("  Pending services:");
                for (int i=0; i<mPendingServices.size(); i++) {
                    ServiceRecord r = mPendingServices.get(i);
                    pw.println("  Pending Service " + r.shortName);
                    r.dump(pw, "    ");
                }
                needSep = true;
            }

            if (mRestartingServices.size() > 0) {
                if (needSep) pw.println(" ");
                pw.println("  Restarting services:");
                for (int i=0; i<mRestartingServices.size(); i++) {
                    ServiceRecord r = mRestartingServices.get(i);
                    pw.println("  Restarting Service " + r.shortName);
                    r.dump(pw, "    ");
                }
                needSep = true;
            }

            if (mStoppingServices.size() > 0) {
                if (needSep) pw.println(" ");
                pw.println("  Stopping services:");
                for (int i=0; i<mStoppingServices.size(); i++) {
                    ServiceRecord r = mStoppingServices.get(i);
                    pw.println("  Stopping Service " + r.shortName);
                    r.dump(pw, "    ");
                }
                needSep = true;
            }

            if (mServiceConnections.size() > 0) {
                if (needSep) pw.println(" ");
                pw.println("  Connection bindings to services:");
                Iterator<ConnectionRecord> it
                        = mServiceConnections.values().iterator();
                while (it.hasNext()) {
                    ConnectionRecord r = it.next();
                    pw.println("  " + r.binding.service.shortName
                          + " -> " + r.conn.asBinder());
                    r.dump(pw, "    ");
                }
            }
        }
    }

    void dumpProviders(PrintWriter pw) {
        synchronized (this) {
            if (checkCallingPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump ActivityManager from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " without permission "
                        + android.Manifest.permission.DUMP);
                return;
            }

            pw.println("Content Providers in Current Activity Manager State:");

            boolean needSep = false;

            if (mProvidersByName.size() > 0) {
                pw.println("  Published content providers (by name):");
                Iterator it = mProvidersByName.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry e = (Map.Entry)it.next();
                    ContentProviderRecord r = (ContentProviderRecord)e.getValue();
                    pw.println("  Provider " + (String)e.getKey());
                    r.dump(pw, "    ");
                }
                needSep = true;
            }

            if (mProvidersByClass.size() > 0) {
                if (needSep) pw.println(" ");
                pw.println("  Published content providers (by class):");
                Iterator it = mProvidersByClass.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry e = (Map.Entry)it.next();
                    ContentProviderRecord r = (ContentProviderRecord)e.getValue();
                    pw.println("  Provider " + (String)e.getKey());
                    r.dump(pw, "    ");
                }
                needSep = true;
            }

            if (mLaunchingProviders.size() > 0) {
                if (needSep) pw.println(" ");
                pw.println("  Launching content providers:");
                for (int i=mLaunchingProviders.size()-1; i>=0; i--) {
                    pw.println("  Provider #" + i + ":");
                    ((ContentProviderRecord)mLaunchingProviders.get(i)).dump(pw, "    ");
                }
                needSep = true;
            }

            pw.println();
            pw.println("Granted Uri Permissions:");
            for (int i=0; i<mGrantedUriPermissions.size(); i++) {
                int uid = mGrantedUriPermissions.keyAt(i);
                HashMap<Uri, UriPermission> perms
                        = mGrantedUriPermissions.valueAt(i);
                pw.println("  Uris granted to uid " + uid + ":");
                for (UriPermission perm : perms.values()) {
                    perm.dump(pw, "    ");
                }
            }
        }
    }

    void dumpSenders(PrintWriter pw) {
        synchronized (this) {
            if (checkCallingPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump ActivityManager from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " without permission "
                        + android.Manifest.permission.DUMP);
                return;
            }

            pw.println("Intent Senders in Current Activity Manager State:");

            if (this.mIntentSenderRecords.size() > 0) {
                Iterator<WeakReference<PendingIntentRecord>> it
                        = mIntentSenderRecords.values().iterator();
                while (it.hasNext()) {
                    WeakReference<PendingIntentRecord> ref = it.next();
                    PendingIntentRecord rec = ref != null ? ref.get(): null;
                    if (rec != null) {
                        pw.println("  IntentSender " + rec);
                        rec.dump(pw, "    ");
                    } else {
                        pw.println("  IntentSender " + ref);
                    }
                }
            }
        }
    }

    private static final void dumpHistoryList(PrintWriter pw, List list,
            String prefix, String label) {
        TaskRecord lastTask = null;
        for (int i=list.size()-1; i>=0; i--) {
            HistoryRecord r = (HistoryRecord)list.get(i);
            if (lastTask != r.task) {
                lastTask = r.task;
                lastTask.dump(pw, prefix + "  ");
            }
            pw.println(prefix + "    " + label + " #" + i + ":");
            r.dump(pw, prefix + "      ");
        }
    }

    private static final int dumpProcessList(PrintWriter pw, List list,
            String prefix, String normalLabel, String persistentLabel,
            boolean inclOomAdj) {
        int numPers = 0;
        for (int i=list.size()-1; i>=0; i--) {
            ProcessRecord r = (ProcessRecord)list.get(i);
            if (false) {
                pw.println(prefix + (r.persistent ? persistentLabel : normalLabel)
                      + " #" + i + ":");
                r.dump(pw, prefix + "  ");
            } else if (inclOomAdj) {
                pw.println(String.format("%s%s #%2d: oom_adj=%3d %s",
                        prefix, (r.persistent ? persistentLabel : normalLabel),
                        i, r.setAdj, r.toString()));
            } else {
                pw.println(String.format("%s%s #%2d: %s",
                        prefix, (r.persistent ? persistentLabel : normalLabel),
                        i, r.toString()));
            }
            if (r.persistent) {
                numPers++;
            }
        }
        return numPers;
    }

    private static final void dumpApplicationMemoryUsage(FileDescriptor fd,
            PrintWriter pw, List list, String prefix, String[] args) {
        final boolean isCheckinRequest = scanArgs(args, "-c");
        long uptime = SystemClock.uptimeMillis();
        long realtime = SystemClock.elapsedRealtime();
        
        if (isCheckinRequest) {
            // short checkin version
            pw.println(uptime + "," + realtime);
            pw.flush();
        } else {
            pw.println("Applications Memory Usage (kB):");
            pw.println("Uptime: " + uptime + " Realtime: " + realtime);
        }
        for (int i = list.size() - 1 ; i >= 0 ; i--) {
            ProcessRecord r = (ProcessRecord)list.get(i);
            if (r.thread != null) {
                if (!isCheckinRequest) {
                    pw.println("\n** MEMINFO in pid " + r.pid + " [" + r.processName + "] **");
                    pw.flush();
                }
                try {
                    r.thread.asBinder().dump(fd, args);
                } catch (RemoteException e) {
                    if (!isCheckinRequest) {
                        pw.println("Got RemoteException!");
                        pw.flush();
                    }
                }
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

    private final int indexOfTokenLocked(IBinder token, boolean required) {
        int count = mHistory.size();

        // convert the token to an entry in the history.
        HistoryRecord r = null;
        int index = -1;
        for (int i=count-1; i>=0; i--) {
            Object o = mHistory.get(i);
            if (o == token) {
                r = (HistoryRecord)o;
                index = i;
                break;
            }
        }
        if (index < 0 && required) {
            RuntimeInit.crash(TAG, new InvalidTokenException(token));
        }

        return index;
    }

    static class InvalidTokenException extends Exception {
        InvalidTokenException(IBinder token) {
            super("Bad activity token: " + token);
        }
    }

    private final void killServicesLocked(ProcessRecord app,
            boolean allowRestart) {
        // Report disconnected services.
        if (false) {
            // XXX we are letting the client link to the service for
            // death notifications.
            if (app.services.size() > 0) {
                Iterator it = app.services.iterator();
                while (it.hasNext()) {
                    ServiceRecord r = (ServiceRecord)it.next();
                    if (r.connections.size() > 0) {
                        Iterator<ConnectionRecord> jt
                                = r.connections.values().iterator();
                        while (jt.hasNext()) {
                            ConnectionRecord c = jt.next();
                            if (c.binding.client != app) {
                                try {
                                    //c.conn.connected(r.className, null);
                                } catch (Exception e) {
                                    // todo: this should be asynchronous!
                                    Log.w(TAG, "Exception thrown disconnected servce "
                                          + r.shortName
                                          + " from app " + app.processName, e);
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
            Iterator it = app.services.iterator();
            while (it.hasNext()) {
                ServiceRecord sr = (ServiceRecord)it.next();
                synchronized (sr.stats.getBatteryStats()) {
                    sr.stats.stopLaunchedLocked();
                }
                sr.app = null;
                sr.executeNesting = 0;
                mStoppingServices.remove(sr);
                if (sr.bindings.size() > 0) {
                    Iterator<IntentBindRecord> bindings
                            = sr.bindings.values().iterator();
                    while (bindings.hasNext()) {
                        IntentBindRecord b = bindings.next();
                        if (DEBUG_SERVICE) Log.v(TAG, "Killing binding " + b
                                + ": shouldUnbind=" + b.hasBound);
                        b.binder = null;
                        b.requested = b.received = b.hasBound = false;
                    }
                }

                if (sr.crashCount >= 2) {
                    Log.w(TAG, "Service crashed " + sr.crashCount
                            + " times, stopping: " + sr);
                    EventLog.writeEvent(LOG_AM_SERVICE_CRASHED_TOO_MUCH,
                            sr.crashCount, sr.shortName, app.pid);
                    bringDownServiceLocked(sr, true);
                } else if (!allowRestart) {
                    bringDownServiceLocked(sr, true);
                } else {
                    scheduleServiceRestartLocked(sr);
                }
            }

            if (!allowRestart) {
                app.services.clear();
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
                Log.i(TAG, "Killing app " + capp.processName
                        + " (pid " + capp.pid
                        + ") because provider " + cpr.info.name
                        + " is in dying process " + proc.processName);
                Process.killProcess(capp.pid);
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
            boolean restarting, int index) {
        if (index >= 0) {
            mLRUProcesses.remove(index);
        }

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
        app.thread = null;
        app.forcingToForeground = null;
        app.foregroundServices = false;

        killServicesLocked(app, true);

        boolean restart = false;

        int NL = mLaunchingProviders.size();
        
        // Remove published content providers.
        if (!app.pubProviders.isEmpty()) {
            Iterator it = app.pubProviders.values().iterator();
            while (it.hasNext()) {
                ContentProviderRecord cpr = (ContentProviderRecord)it.next();
                cpr.provider = null;
                cpr.app = null;

                // See if someone is waiting for this provider...  in which
                // case we don't remove it, but just let it restart.
                int i = 0;
                if (!app.bad) {
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
        
        // Look through the content providers we are waiting to have launched,
        // and if any run in this process then either schedule a restart of
        // the process or kill the client waiting for it if this process has
        // gone bad.
        for (int i=0; i<NL; i++) {
            ContentProviderRecord cpr = (ContentProviderRecord)
                    mLaunchingProviders.get(i);
            if (cpr.launchingApp == app) {
                if (!app.bad) {
                    restart = true;
                } else {
                    removeDyingProviderLocked(app, cpr);
                    NL = mLaunchingProviders.size();
                }
            }
        }

        // Unregister from connected content providers.
        if (!app.conProviders.isEmpty()) {
            Iterator it = app.conProviders.iterator();
            while (it.hasNext()) {
                ContentProviderRecord cpr = (ContentProviderRecord)it.next();
                cpr.clients.remove(app);
            }
            app.conProviders.clear();
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
        
        // If the caller is restarting this app, then leave it in its
        // current lists and let the caller take care of it.
        if (restarting) {
            return;
        }

        if (!app.persistent) {
            if (DEBUG_PROCESSES) Log.v(TAG,
                    "Removing non-persistent process during cleanup: " + app);
            mProcessNames.remove(app.processName, app.info.uid);
        } else if (!app.removed) {
            // This app is persistent, so we need to keep its record around.
            // If it is not already on the pending app list, add it there
            // and start a new process for it.
            app.thread = null;
            app.forcingToForeground = null;
            app.foregroundServices = false;
            if (mPersistentStartingProcesses.indexOf(app) < 0) {
                mPersistentStartingProcesses.add(app);
                restart = true;
            }
        }
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
            app.pid = 0;
        }
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
        info.process = r.processName;
        info.foreground = r.isForeground;
        info.activeSince = r.createTime;
        info.started = r.startRequested;
        info.clientCount = r.connections.size();
        info.crashCount = r.crashCount;
        info.lastActivityTime = r.lastActivity;
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
                    ActivityThread.getPackageManager().resolveService(
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
                    callingPid, callingUid, r.exported ? -1 : r.appInfo.uid)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Permission Denial: Accessing service " + r.name
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
                    ActivityThread.getPackageManager().resolveService(
                            service, resolvedType, PackageManager.GET_SHARED_LIBRARY_FILES);
                ServiceInfo sInfo =
                    rInfo != null ? rInfo.serviceInfo : null;
                if (sInfo == null) {
                    Log.w(TAG, "Unable to start service " + service +
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
                    r = new ServiceRecord(ss, name, filter, sInfo, res);
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
                    callingPid, callingUid, r.exported ? -1 : r.appInfo.uid)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Permission Denial: Accessing service " + r.name
                        + " from pid=" + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " requires " + r.permission);
                return new ServiceLookupResult(null, r.permission);
            }
            return new ServiceLookupResult(r, null);
        }
        return null;
    }

    private final void bumpServiceExecutingLocked(ServiceRecord r) {
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
        final int N = r.startArgs.size();
        if (N == 0) {
            return;
        }

        final int BASEID = r.lastStartId - N + 1;
        int i = 0;
        while (i < N) {
            try {
                Intent args = r.startArgs.get(i);
                if (DEBUG_SERVICE) Log.v(TAG, "Sending arguments to service: "
                        + r.name + " " + r.intent + " args=" + args);
                bumpServiceExecutingLocked(r);
                if (!oomAdjusted) {
                    oomAdjusted = true;
                    updateOomAdjLocked(r.app);
                }
                r.app.thread.scheduleServiceArgs(r, BASEID+i, args);
                i++;
            } catch (Exception e) {
                break;
            }
        }
        if (i == N) {
            r.startArgs.clear();
        } else {
            while (i > 0) {
                r.startArgs.remove(0);
                i--;
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
                bumpServiceExecutingLocked(r);
                if (DEBUG_SERVICE) Log.v(TAG, "Connecting binding " + i
                        + ": shouldUnbind=" + i.hasBound);
                r.app.thread.scheduleBindService(r, i.intent.getIntent(), rebind);
                if (!rebind) {
                    i.requested = true;
                }
                i.hasBound = true;
                i.doRebind = false;
            } catch (RemoteException e) {
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
        bumpServiceExecutingLocked(r);
        updateLRUListLocked(app, true);

        boolean created = false;
        try {
            if (DEBUG_SERVICE) Log.v(TAG, "Scheduling start service: "
                    + r.name + " " + r.intent);
            EventLog.writeEvent(LOG_AM_CREATE_SERVICE,
                    System.identityHashCode(r), r.shortName,
                    r.intent.getIntent().toString(), r.app.pid);
            synchronized (r.stats.getBatteryStats()) {
                r.stats.startLaunchedLocked();
            }
            app.thread.scheduleCreateService(r, r.serviceInfo);
            created = true;
        } finally {
            if (!created) {
                app.services.remove(r);
                scheduleServiceRestartLocked(r);
            }
        }

        requestServiceBindingsLocked(r);
        sendServiceArgsLocked(r, true);
    }

    private final void scheduleServiceRestartLocked(ServiceRecord r) {
        r.totalRestartCount++;
        if (r.restartDelay == 0) {
            r.restartCount++;
            r.restartDelay = SERVICE_RESTART_DURATION;
        } else {
            // If it has been a "reasonably long time" since the service
            // was started, then reset our restart duration back to
            // the beginning, so we don't infinitely increase the duration
            // on a service that just occasionally gets killed (which is
            // a normal case, due to process being killed to reclaim memory).
            long now = SystemClock.uptimeMillis();
            if (now > (r.restartTime+(SERVICE_RESTART_DURATION*2*2*2))) {
                r.restartCount = 1;
                r.restartDelay = SERVICE_RESTART_DURATION;
            } else {
                r.restartDelay *= 2;
            }
        }
        if (!mRestartingServices.contains(r)) {
            mRestartingServices.add(r);
        }
        mHandler.removeCallbacks(r.restarter);
        mHandler.postDelayed(r.restarter, r.restartDelay);
        r.nextRestartTime = SystemClock.uptimeMillis() + r.restartDelay;
        Log.w(TAG, "Scheduling restart of crashed service "
                + r.shortName + " in " + r.restartDelay + "ms");
        EventLog.writeEvent(LOG_AM_SCHEDULE_SERVICE_RESTART,
                r.shortName, r.restartDelay);

        Message msg = Message.obtain();
        msg.what = SERVICE_ERROR_MSG;
        msg.obj = r;
        mHandler.sendMessage(msg);
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
        //Log.i(TAG, "Bring up service:");
        //r.dump("  ");

        if (r.app != null) {
            sendServiceArgsLocked(r, false);
            return true;
        }

        if (!whileRestarting && r.restartDelay > 0) {
            // If waiting for a restart, then do nothing.
            return true;
        }

        if (DEBUG_SERVICE) Log.v(TAG, "Bringing up service " + r.name
                + " " + r.intent);

        final String appName = r.processName;
        ProcessRecord app = getProcessRecordLocked(appName, r.appInfo.uid);
        if (app != null && app.thread != null) {
            try {
                realStartServiceLocked(r, app);
                return true;
            } catch (RemoteException e) {
                Log.w(TAG, "Exception when starting service " + r.shortName, e);
            }

            // If a dead object exception was thrown -- fall through to
            // restart the application.
        }

        if (!mPendingServices.contains(r)) {
            // Not running -- get it started, and enqueue this service record
            // to be executed when the app comes up.
            if (startProcessLocked(appName, r.appInfo, true, intentFlags,
                    "service", r.name) == null) {
                Log.w(TAG, "Unable to launch app "
                        + r.appInfo.packageName + "/"
                        + r.appInfo.uid + " for service "
                        + r.intent.getIntent() + ": process is bad");
                bringDownServiceLocked(r, true);
                return false;
            }
            mPendingServices.add(r);
        }
        return true;
    }

    private final void bringDownServiceLocked(ServiceRecord r, boolean force) {
        //Log.i(TAG, "Bring down service:");
        //r.dump("  ");

        // Does it still need to run?
        if (!force && r.startRequested) {
            return;
        }
        if (r.connections.size() > 0) {
            if (!force) {
                // XXX should probably keep a count of the number of auto-create
                // connections directly in the service.
                Iterator<ConnectionRecord> it = r.connections.values().iterator();
                while (it.hasNext()) {
                    ConnectionRecord cr = it.next();
                    if ((cr.flags&Context.BIND_AUTO_CREATE) != 0) {
                        return;
                    }
                }
            }

            // Report to all of the connections that the service is no longer
            // available.
            Iterator<ConnectionRecord> it = r.connections.values().iterator();
            while (it.hasNext()) {
                ConnectionRecord c = it.next();
                try {
                    // todo: shouldn't be a synchronous call!
                    c.conn.connected(r.name, null);
                } catch (Exception e) {
                    Log.w(TAG, "Failure disconnecting service " + r.name +
                          " to connection " + c.conn.asBinder() +
                          " (in " + c.binding.client.processName + ")", e);
                }
            }
        }

        // Tell the service that it has been unbound.
        if (r.bindings.size() > 0 && r.app != null && r.app.thread != null) {
            Iterator<IntentBindRecord> it = r.bindings.values().iterator();
            while (it.hasNext()) {
                IntentBindRecord ibr = it.next();
                if (DEBUG_SERVICE) Log.v(TAG, "Bringing down binding " + ibr
                        + ": hasBound=" + ibr.hasBound);
                if (r.app != null && r.app.thread != null && ibr.hasBound) {
                    try {
                        bumpServiceExecutingLocked(r);
                        updateOomAdjLocked(r.app);
                        ibr.hasBound = false;
                        r.app.thread.scheduleUnbindService(r,
                                ibr.intent.getIntent());
                    } catch (Exception e) {
                        Log.w(TAG, "Exception when unbinding service "
                                + r.shortName, e);
                        serviceDoneExecutingLocked(r, true);
                    }
                }
            }
        }

        if (DEBUG_SERVICE) Log.v(TAG, "Bringing down service " + r.name
                 + " " + r.intent);
        EventLog.writeEvent(LOG_AM_DESTROY_SERVICE,
                System.identityHashCode(r), r.shortName,
                (r.app != null) ? r.app.pid : -1);

        mServices.remove(r.name);
        mServicesByIntent.remove(r.intent);
        if (localLOGV) Log.v(TAG, "BRING DOWN SERVICE: " + r.shortName);
        r.totalRestartCount = 0;
        unscheduleServiceRestartLocked(r);

        // Also make sure it is not on the pending list.
        int N = mPendingServices.size();
        for (int i=0; i<N; i++) {
            if (mPendingServices.get(i) == r) {
                mPendingServices.remove(i);
                if (DEBUG_SERVICE) Log.v(
                    TAG, "Removed pending service: " + r.shortName);
                i--;
                N--;
            }
        }

        if (r.app != null) {
            synchronized (r.stats.getBatteryStats()) {
                r.stats.stopLaunchedLocked();
            }
            r.app.services.remove(r);
            if (r.app.thread != null) {
                updateServiceForegroundLocked(r.app, false);
                try {
                    Log.i(TAG, "Stopping service: " + r.shortName);
                    bumpServiceExecutingLocked(r);
                    mStoppingServices.add(r);
                    updateOomAdjLocked(r.app);
                    r.app.thread.scheduleStopService(r);
                } catch (Exception e) {
                    Log.w(TAG, "Exception when stopping service "
                            + r.shortName, e);
                    serviceDoneExecutingLocked(r, true);
                }
            } else {
                if (DEBUG_SERVICE) Log.v(
                    TAG, "Removed service that has no process: " + r.shortName);
            }
        } else {
            if (DEBUG_SERVICE) Log.v(
                TAG, "Removed service that is not running: " + r.shortName);
        }
    }

    ComponentName startServiceLocked(IApplicationThread caller,
            Intent service, String resolvedType,
            int callingPid, int callingUid) {
        synchronized(this) {
            if (DEBUG_SERVICE) Log.v(TAG, "startService: " + service
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
            if (unscheduleServiceRestartLocked(r)) {
                if (DEBUG_SERVICE) Log.v(TAG, "START SERVICE WHILE RESTART PENDING: "
                        + r.shortName);
            }
            r.startRequested = true;
            r.startArgs.add(service);
            r.lastStartId++;
            if (r.lastStartId < 1) {
                r.lastStartId = 1;
            }
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

    public int stopService(IApplicationThread caller, Intent service,
            String resolvedType) {
        // Refuse possible leaked file descriptors
        if (service != null && service.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            if (DEBUG_SERVICE) Log.v(TAG, "stopService: " + service
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
                    synchronized (r.record.stats.getBatteryStats()) {
                        r.record.stats.stopRunningLocked();
                    }
                    r.record.startRequested = false;
                    final long origId = Binder.clearCallingIdentity();
                    bringDownServiceLocked(r.record, false);
                    Binder.restoreCallingIdentity(origId);
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
            if (DEBUG_SERVICE) Log.v(TAG, "stopServiceToken: " + className
                    + " " + token + " startId=" + startId);
            ServiceRecord r = findServiceLocked(className, token);
            if (r != null && (startId < 0 || r.lastStartId == startId)) {
                synchronized (r.stats.getBatteryStats()) {
                    r.stats.stopRunningLocked();
                    r.startRequested = false;
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
            boolean isForeground) {
        synchronized(this) {
            ServiceRecord r = findServiceLocked(className, token);
            if (r != null) {
                if (r.isForeground != isForeground) {
                    final long origId = Binder.clearCallingIdentity();
                    r.isForeground = isForeground;
                    if (r.app != null) {
                        updateServiceForegroundLocked(r.app, true);
                    }
                    Binder.restoreCallingIdentity(origId);
                }
            }
        }
    }

    public void updateServiceForegroundLocked(ProcessRecord proc, boolean oomAdj) {
        boolean anyForeground = false;
        for (ServiceRecord sr : (HashSet<ServiceRecord>)proc.services) {
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
            if (DEBUG_SERVICE) Log.v(TAG, "bindService: " + service
                    + " type=" + resolvedType + " conn=" + connection.asBinder()
                    + " flags=0x" + Integer.toHexString(flags));
            final ProcessRecord callerApp = getRecordForAppLocked(caller);
            if (callerApp == null) {
                throw new SecurityException(
                        "Unable to find app for caller " + caller
                        + " (pid=" + Binder.getCallingPid()
                        + ") when binding service " + service);
            }

            HistoryRecord activity = null;
            if (token != null) {
                int aindex = indexOfTokenLocked(token, false);
                if (aindex < 0) {
                    Log.w(TAG, "Binding with unknown activity: " + token);
                    return 0;
                }
                activity = (HistoryRecord)mHistory.get(aindex);
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
                if (DEBUG_SERVICE) Log.v(TAG, "BIND SERVICE WHILE RESTART PENDING: "
                        + s.shortName);
            }

            AppBindRecord b = s.retrieveAppBindingLocked(service, callerApp);
            ConnectionRecord c = new ConnectionRecord(b, activity,
                    connection, flags);

            IBinder binder = connection.asBinder();
            s.connections.put(binder, c);
            b.connections.add(c);
            if (activity != null) {
                if (activity.connections == null) {
                    activity.connections = new HashSet<ConnectionRecord>();
                }
                activity.connections.add(c);
            }
            b.client.connections.add(c);
            mServiceConnections.put(binder, c);

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

            if (DEBUG_SERVICE) Log.v(TAG, "Bind " + s + " with " + b
                    + ": received=" + b.intent.received
                    + " apps=" + b.intent.apps.size()
                    + " doRebind=" + b.intent.doRebind);

            if (s.app != null && b.intent.received) {
                // Service is already running, so we can immediately
                // publish the connection.
                try {
                    c.conn.connected(s.name, b.intent.binder);
                } catch (Exception e) {
                    Log.w(TAG, "Failure sending service " + s.shortName
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

    private void removeConnectionLocked(
        ConnectionRecord c, ProcessRecord skipApp, HistoryRecord skipAct) {
        IBinder binder = c.conn.asBinder();
        AppBindRecord b = c.binding;
        ServiceRecord s = b.service;
        s.connections.remove(binder);
        b.connections.remove(c);
        if (c.activity != null && c.activity != skipAct) {
            if (c.activity.connections != null) {
                c.activity.connections.remove(c);
            }
        }
        if (b.client != skipApp) {
            b.client.connections.remove(c);
        }
        mServiceConnections.remove(binder);

        if (b.connections.size() == 0) {
            b.intent.apps.remove(b.client);
        }

        if (DEBUG_SERVICE) Log.v(TAG, "Disconnecting binding " + b.intent
                + ": shouldUnbind=" + b.intent.hasBound);
        if (s.app != null && s.app.thread != null && b.intent.apps.size() == 0
                && b.intent.hasBound) {
            try {
                bumpServiceExecutingLocked(s);
                updateOomAdjLocked(s.app);
                b.intent.hasBound = false;
                // Assume the client doesn't want to know about a rebind;
                // we will deal with that later if it asks for one.
                b.intent.doRebind = false;
                s.app.thread.scheduleUnbindService(s, b.intent.intent.getIntent());
            } catch (Exception e) {
                Log.w(TAG, "Exception when unbinding service " + s.shortName, e);
                serviceDoneExecutingLocked(s, true);
            }
        }

        if ((c.flags&Context.BIND_AUTO_CREATE) != 0) {
            bringDownServiceLocked(s, false);
        }
    }

    public boolean unbindService(IServiceConnection connection) {
        synchronized (this) {
            IBinder binder = connection.asBinder();
            if (DEBUG_SERVICE) Log.v(TAG, "unbindService: conn=" + binder);
            ConnectionRecord r = mServiceConnections.get(binder);
            if (r == null) {
                Log.w(TAG, "Unbind failed: could not find connection for "
                      + connection.asBinder());
                return false;
            }

            final long origId = Binder.clearCallingIdentity();

            removeConnectionLocked(r, null, null);

            if (r.binding.service.app != null) {
                // This could have made the service less important.
                updateOomAdjLocked(r.binding.service.app);
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

            if (DEBUG_SERVICE) Log.v(TAG, "PUBLISHING SERVICE " + r.name
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
                        Iterator<ConnectionRecord> it
                                = r.connections.values().iterator();
                        while (it.hasNext()) {
                            ConnectionRecord c = it.next();
                            if (!filter.equals(c.binding.intent.intent)) {
                                if (DEBUG_SERVICE) Log.v(
                                        TAG, "Not publishing to: " + c);
                                if (DEBUG_SERVICE) Log.v(
                                        TAG, "Bound intent: " + c.binding.intent.intent);
                                if (DEBUG_SERVICE) Log.v(
                                        TAG, "Published intent: " + intent);
                                continue;
                            }
                            if (DEBUG_SERVICE) Log.v(TAG, "Publishing to: " + c);
                            try {
                                c.conn.connected(r.name, service);
                            } catch (Exception e) {
                                Log.w(TAG, "Failure sending service " + r.name +
                                      " to connection " + c.conn.asBinder() +
                                      " (in " + c.binding.client.processName + ")", e);
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
                if (DEBUG_SERVICE) Log.v(TAG, "unbindFinished in " + r
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

    public void serviceDoneExecuting(IBinder token) {
        synchronized(this) {
            if (!(token instanceof ServiceRecord)) {
                throw new IllegalArgumentException("Invalid service token");
            }
            ServiceRecord r = (ServiceRecord)token;
            boolean inStopping = mStoppingServices.contains(token);
            if (r != null) {
                if (DEBUG_SERVICE) Log.v(TAG, "DONE EXECUTING SERVICE " + r.name
                        + ": nesting=" + r.executeNesting
                        + ", inStopping=" + inStopping);
                if (r != token) {
                    Log.w(TAG, "Done executing service " + r.name
                          + " with incorrect token: given " + token
                          + ", expected " + r);
                    return;
                }

                final long origId = Binder.clearCallingIdentity();
                serviceDoneExecutingLocked(r, inStopping);
                Binder.restoreCallingIdentity(origId);
            } else {
                Log.w(TAG, "Done executing unknown service " + r.name
                        + " with token " + token);
            }
        }
    }

    public void serviceDoneExecutingLocked(ServiceRecord r, boolean inStopping) {
        r.executeNesting--;
        if (r.executeNesting <= 0 && r.app != null) {
            r.app.executingServices.remove(r);
            if (r.app.executingServices.size() == 0) {
                mHandler.removeMessages(SERVICE_TIMEOUT_MSG, r.app);
            }
            if (inStopping) {
                mStoppingServices.remove(r);
            }
            updateOomAdjLocked(r.app);
        }
    }

    void serviceTimeout(ProcessRecord proc) {
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
            if (timeout != null && mLRUProcesses.contains(proc)) {
                Log.w(TAG, "Timeout executing service: " + timeout);
                appNotRespondingLocked(proc, null, "Executing service "
                        + timeout.name);
            } else {
                Message msg = mHandler.obtainMessage(SERVICE_TIMEOUT_MSG);
                msg.obj = proc;
                mHandler.sendMessageAtTime(msg, nextTime+SERVICE_TIMEOUT);
            }
        }
    }
    
    // =========================================================
    // BROADCASTS
    // =========================================================

    private final List getStickies(String action, IntentFilter filter,
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
        if (DEBUG_BROADCAST) Log.v(TAG, "Schedule broadcasts: current="
                + mBroadcastsScheduled);

        if (mBroadcastsScheduled) {
            return;
        }
        mHandler.sendEmptyMessage(BROADCAST_INTENT_MSG);
        mBroadcastsScheduled = true;
    }

    public Intent registerReceiver(IApplicationThread caller,
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
            }

            List allSticky = null;

            // Look for any matching sticky broadcasts...
            Iterator actions = filter.actionsIterator();
            if (actions != null) {
                while (actions.hasNext()) {
                    String action = (String)actions.next();
                    allSticky = getStickies(action, filter, allSticky);
                }
            } else {
                allSticky = getStickies(null, filter, allSticky);
            }

            // The first sticky in the list is returned directly back to
            // the client.
            Intent sticky = allSticky != null ? (Intent)allSticky.get(0) : null;

            if (DEBUG_BROADCAST) Log.v(TAG, "Register receiver " + filter
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
            BroadcastFilter bf = new BroadcastFilter(filter, rl, permission);
            rl.add(bf);
            if (!bf.debugCheck()) {
                Log.w(TAG, "==> For Dynamic broadast");
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
                            false);
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
        if (DEBUG_BROADCAST) Log.v(TAG, "Unregister receiver: " + receiver);

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
    
    private final int broadcastIntentLocked(ProcessRecord callerApp,
            String callerPackage, Intent intent, String resolvedType,
            IIntentReceiver resultTo, int resultCode, String resultData,
            Bundle map, String requiredPermission,
            boolean ordered, boolean sticky, int callingPid, int callingUid) {
        intent = new Intent(intent);

        if (DEBUG_BROADCAST) Log.v(
            TAG, (sticky ? "Broadcast sticky: ": "Broadcast: ") + intent
            + " ordered=" + ordered);
        if ((resultTo != null) && !ordered) {
            Log.w(TAG, "Broadcast " + intent + " not ordered but result callback requested!");
        }
        
        // Handle special intents: if this broadcast is from the package
        // manager about a package being removed, we need to remove all of
        // its activities from the history stack.
        final boolean uidRemoved = intent.ACTION_UID_REMOVED.equals(
                intent.getAction());
        if (intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                || intent.ACTION_PACKAGE_CHANGED.equals(intent.getAction())
                || uidRemoved) {
            if (checkComponentPermission(
                    android.Manifest.permission.BROADCAST_PACKAGE_REMOVED,
                    callingPid, callingUid, -1)
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
                    Uri data = intent.getData();
                    String ssp;
                    if (data != null && (ssp=data.getSchemeSpecificPart()) != null) {
                        if (!intent.getBooleanExtra(Intent.EXTRA_DONT_KILL_APP, false)) {
                            uninstallPackageLocked(ssp,
                                    intent.getIntExtra(Intent.EXTRA_UID, -1), false);
                        }
                    }
                }
            } else {
                String msg = "Permission Denial: " + intent.getAction()
                        + " broadcast from " + callerPackage + " (pid=" + callingPid
                        + ", uid=" + callingUid + ")"
                        + " requires "
                        + android.Manifest.permission.BROADCAST_PACKAGE_REMOVED;
                Log.w(TAG, msg);
                throw new SecurityException(msg);
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

        // Add to the sticky list if requested.
        if (sticky) {
            if (checkPermission(android.Manifest.permission.BROADCAST_STICKY,
                    callingPid, callingUid)
                    != PackageManager.PERMISSION_GRANTED) {
                String msg = "Permission Denial: broadcastIntent() requesting a sticky broadcast from pid="
                        + callingPid + ", uid=" + callingUid
                        + " requires " + android.Manifest.permission.BROADCAST_STICKY;
                Log.w(TAG, msg);
                throw new SecurityException(msg);
            }
            if (requiredPermission != null) {
                Log.w(TAG, "Can't broadcast sticky intent " + intent
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

        final ContentResolver resolver = mContext.getContentResolver();

        // Figure out who all will receive this broadcast.
        List receivers = null;
        List<BroadcastFilter> registeredReceivers = null;
        try {
            if (intent.getComponent() != null) {
                // Broadcast is going to one specific receiver class...
                ActivityInfo ai = ActivityThread.getPackageManager().
                    getReceiverInfo(intent.getComponent(), 0);
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
                        ActivityThread.getPackageManager().queryIntentReceivers(
                                intent, resolvedType, PackageManager.GET_SHARED_LIBRARY_FILES);
                }
                registeredReceivers = mReceiverResolver.queryIntent(resolver,
                        intent, resolvedType, false);
            }
        } catch (RemoteException ex) {
            // pm is in same process, this will never happen.
        }

        int NR = registeredReceivers != null ? registeredReceivers.size() : 0;
        if (!ordered && NR > 0) {
            // If we are not serializing this broadcast, then send the
            // registered receivers separately so they don't wait for the
            // components to be launched.
            BroadcastRecord r = new BroadcastRecord(intent, callerApp,
                    callerPackage, callingPid, callingUid, requiredPermission,
                    registeredReceivers, resultTo, resultCode, resultData, map,
                    ordered);
            if (DEBUG_BROADCAST) Log.v(
                    TAG, "Enqueueing parallel broadcast " + r
                    + ": prev had " + mParallelBroadcasts.size());
            mParallelBroadcasts.add(r);
            scheduleBroadcastsLocked();
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
            boolean skip = false;
            if (intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                skip = true;
            } else if (intent.ACTION_PACKAGE_RESTARTED.equals(intent.getAction())) {
                skip = true;
            } else if (intent.ACTION_PACKAGE_DATA_CLEARED.equals(intent.getAction())) {
                skip = true;
            }
            String skipPackage = (skip && intent.getData() != null)
                    ? intent.getData().getSchemeSpecificPart()
                    : null;
            if (skipPackage != null && receivers != null) {
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
                    receivers, resultTo, resultCode, resultData, map, ordered);
            if (DEBUG_BROADCAST) Log.v(
                    TAG, "Enqueueing ordered broadcast " + r
                    + ": prev had " + mOrderedBroadcasts.size());
            if (DEBUG_BROADCAST) {
                int seq = r.intent.getIntExtra("seq", -1);
                Log.i(TAG, "Enqueueing broadcast " + r.intent.getAction() + " seq=" + seq);
            }
            mOrderedBroadcasts.add(r);
            scheduleBroadcastsLocked();
        }

        return BROADCAST_SUCCESS;
    }

    public final int broadcastIntent(IApplicationThread caller,
            Intent intent, String resolvedType, IIntentReceiver resultTo,
            int resultCode, String resultData, Bundle map,
            String requiredPermission, boolean serialized, boolean sticky) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            if (!mSystemReady) {
                // if the caller really truly claims to know what they're doing, go
                // ahead and allow the broadcast without launching any receivers
                int flags = intent.getFlags();
                if ((flags&Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT) != 0) {
                    intent = new Intent(intent);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                } else if ((flags&Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0){
                    Log.e(TAG, "Attempt to launch receivers of broadcast intent " + intent
                            + " before boot completion");
                    throw new IllegalStateException("Cannot broadcast before boot completed");
                }
            }
            
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
                Log.w(TAG, msg);
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
                Log.w(TAG, "finishReceiver called but no pending broadcasts");
            }
            return false;
        }
        BroadcastRecord r = mOrderedBroadcasts.get(0);
        if (r.receiver == null) {
            if (explicit) {
                Log.w(TAG, "finishReceiver called but none active");
            }
            return false;
        }
        if (r.receiver != receiver) {
            Log.w(TAG, "finishReceiver called but active receiver is different");
            return false;
        }
        int state = r.state;
        r.state = r.IDLE;
        if (state == r.IDLE) {
            if (explicit) {
                Log.w(TAG, "finishReceiver called but state is IDLE");
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
        if (DEBUG_BROADCAST) Log.v(TAG, "Finish receiver: " + who);

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

    private final void logBroadcastReceiverDiscard(BroadcastRecord r) {
        if (r.nextReceiver > 0) {
            Object curReceiver = r.receivers.get(r.nextReceiver-1);
            if (curReceiver instanceof BroadcastFilter) {
                BroadcastFilter bf = (BroadcastFilter) curReceiver;
                EventLog.writeEvent(LOG_AM_BROADCAST_DISCARD_FILTER,
                        System.identityHashCode(r),
                        r.intent.getAction(),
                        r.nextReceiver - 1,
                        System.identityHashCode(bf));
            } else {
                EventLog.writeEvent(LOG_AM_BROADCAST_DISCARD_APP,
                        System.identityHashCode(r),
                        r.intent.getAction(),
                        r.nextReceiver - 1,
                        ((ResolveInfo)curReceiver).toString());
            }
        } else {
            Log.w(TAG, "Discarding broadcast before first receiver is invoked: "
                    + r);
            EventLog.writeEvent(LOG_AM_BROADCAST_DISCARD_APP,
                    System.identityHashCode(r),
                    r.intent.getAction(),
                    r.nextReceiver,
                    "NONE");
        }
    }

    private final void broadcastTimeout() {
        synchronized (this) {
            if (mOrderedBroadcasts.size() == 0) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            BroadcastRecord r = mOrderedBroadcasts.get(0);
            if ((r.startTime+BROADCAST_TIMEOUT) > now) {
                if (DEBUG_BROADCAST) Log.v(TAG,
                        "Premature timeout @ " + now + ": resetting BROADCAST_TIMEOUT_MSG for "
                        + (r.startTime + BROADCAST_TIMEOUT));
                Message msg = mHandler.obtainMessage(BROADCAST_TIMEOUT_MSG);
                mHandler.sendMessageAtTime(msg, r.startTime+BROADCAST_TIMEOUT);
                return;
            }

            Log.w(TAG, "Timeout of broadcast " + r + " - receiver=" + r.receiver);
            r.startTime = now;
            r.anrCount++;

            // Current receiver has passed its expiration date.
            if (r.nextReceiver <= 0) {
                Log.w(TAG, "Timeout on receiver with nextReceiver <= 0");
                return;
            }

            ProcessRecord app = null;

            Object curReceiver = r.receivers.get(r.nextReceiver-1);
            Log.w(TAG, "Receiver during timeout: " + curReceiver);
            logBroadcastReceiverDiscard(r);
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
                appNotRespondingLocked(app, null, "Broadcast of " + r.intent.toString());
            }

            if (mPendingBroadcast == r) {
                mPendingBroadcast = null;
            }

            // Move on to the next receiver.
            finishReceiverLocked(r.receiver, r.resultCode, r.resultData,
                    r.resultExtras, r.resultAbort, true);
            scheduleBroadcastsLocked();
        }
    }

    private final void processCurBroadcastLocked(BroadcastRecord r,
            ProcessRecord app) throws RemoteException {
        if (app.thread == null) {
            throw new RemoteException();
        }
        r.receiver = app.thread.asBinder();
        r.curApp = app;
        app.curReceiver = r;
        updateLRUListLocked(app, true);

        // Tell the application to launch this receiver.
        r.intent.setComponent(r.curComponent);

        boolean started = false;
        try {
            if (DEBUG_BROADCAST) Log.v(TAG,
                    "Delivering to component " + r.curComponent
                    + ": " + r);
            app.thread.scheduleReceiver(new Intent(r.intent), r.curReceiver,
                    r.resultCode, r.resultData, r.resultExtras, r.ordered);
            started = true;
        } finally {
            if (!started) {
                r.receiver = null;
                r.curApp = null;
                app.curReceiver = null;
            }
        }

    }

    static void performReceive(ProcessRecord app, IIntentReceiver receiver,
            Intent intent, int resultCode, String data,
            Bundle extras, boolean ordered) throws RemoteException {
        if (app != null && app.thread != null) {
            // If we have an app thread, do the call through that so it is
            // correctly ordered with other one-way calls.
            app.thread.scheduleRegisteredReceiver(receiver, intent, resultCode,
                    data, extras, ordered);
        } else {
            receiver.performReceive(intent, resultCode, data, extras, ordered);
        }
    }
    
    private final void deliverToRegisteredReceiver(BroadcastRecord r,
            BroadcastFilter filter, boolean ordered) {
        boolean skip = false;
        if (filter.requiredPermission != null) {
            int perm = checkComponentPermission(filter.requiredPermission,
                    r.callingPid, r.callingUid, -1);
            if (perm != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Permission Denial: broadcasting "
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
                    filter.receiverList.pid, filter.receiverList.uid, -1);
            if (perm != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Permission Denial: receiving "
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
            }
            try {
                if (DEBUG_BROADCAST) {
                    int seq = r.intent.getIntExtra("seq", -1);
                    Log.i(TAG, "Sending broadcast " + r.intent.getAction() + " seq=" + seq
                            + " app=" + filter.receiverList.app);
                }
                performReceive(filter.receiverList.app, filter.receiverList.receiver,
                    new Intent(r.intent), r.resultCode,
                    r.resultData, r.resultExtras, r.ordered);
                if (ordered) {
                    r.state = BroadcastRecord.CALL_DONE_RECEIVE;
                }
            } catch (RemoteException e) {
                Log.w(TAG, "Failure sending broadcast " + r.intent, e);
                if (ordered) {
                    r.receiver = null;
                    r.curFilter = null;
                    filter.receiverList.curBroadcast = null;
                }
            }
        }
    }

    private final void processNextBroadcast(boolean fromMsg) {
        synchronized(this) {
            BroadcastRecord r;

            if (DEBUG_BROADCAST) Log.v(TAG, "processNextBroadcast: "
                    + mParallelBroadcasts.size() + " broadcasts, "
                    + mOrderedBroadcasts.size() + " serialized broadcasts");

            updateCpuStats();
            
            if (fromMsg) {
                mBroadcastsScheduled = false;
            }

            // First, deliver any non-serialized broadcasts right away.
            while (mParallelBroadcasts.size() > 0) {
                r = mParallelBroadcasts.remove(0);
                final int N = r.receivers.size();
                for (int i=0; i<N; i++) {
                    Object target = r.receivers.get(i);
                    if (DEBUG_BROADCAST)  Log.v(TAG,
                            "Delivering non-serialized to registered "
                            + target + ": " + r);
                    deliverToRegisteredReceiver(r, (BroadcastFilter)target, false);
                }
            }

            // Now take care of the next serialized one...

            // If we are waiting for a process to come up to handle the next
            // broadcast, then do nothing at this point.  Just in case, we
            // check that the process we're waiting for still exists.
            if (mPendingBroadcast != null) {
                Log.i(TAG, "processNextBroadcast: waiting for "
                        + mPendingBroadcast.curApp);

                boolean isDead;
                synchronized (mPidsSelfLocked) {
                    isDead = (mPidsSelfLocked.get(mPendingBroadcast.curApp.pid) == null);
                }
                if (!isDead) {
                    // It's still alive, so keep waiting
                    return;
                } else {
                    Log.w(TAG, "pending app " + mPendingBroadcast.curApp
                            + " died before responding to broadcast");
                    mPendingBroadcast = null;
                }
            }

            do {
                if (mOrderedBroadcasts.size() == 0) {
                    // No more broadcasts pending, so all done!
                    scheduleAppGcsLocked();
                    return;
                }
                r = mOrderedBroadcasts.get(0);
                boolean forceReceive = false;

                // Ensure that even if something goes awry with the timeout
                // detection, we catch "hung" broadcasts here, discard them,
                // and continue to make progress.  
                int numReceivers = (r.receivers != null) ? r.receivers.size() : 0;
                long now = SystemClock.uptimeMillis();
                if (r.dispatchTime > 0) {
                    if ((numReceivers > 0) &&
                            (now > r.dispatchTime + (2*BROADCAST_TIMEOUT*numReceivers))) {
                        Log.w(TAG, "Hung broadcast discarded after timeout failure:"
                                + " now=" + now
                                + " dispatchTime=" + r.dispatchTime
                                + " startTime=" + r.startTime
                                + " intent=" + r.intent
                                + " numReceivers=" + numReceivers
                                + " nextReceiver=" + r.nextReceiver
                                + " state=" + r.state);
                        broadcastTimeout(); // forcibly finish this broadcast
                        forceReceive = true;
                        r.state = BroadcastRecord.IDLE;
                    }
                }

                if (r.state != BroadcastRecord.IDLE) {
                    if (DEBUG_BROADCAST) Log.d(TAG,
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
                                Log.i(TAG, "Finishing broadcast " + r.intent.getAction()
                                        + " seq=" + seq + " app=" + r.callerApp);
                            }
                            performReceive(r.callerApp, r.resultTo,
                                new Intent(r.intent), r.resultCode,
                                r.resultData, r.resultExtras, false);
                        } catch (RemoteException e) {
                            Log.w(TAG, "Failure sending broadcast result of " + r.intent, e);
                        }
                    }
                    
                    if (DEBUG_BROADCAST) Log.v(TAG, "Cancelling BROADCAST_TIMEOUT_MSG");
                    mHandler.removeMessages(BROADCAST_TIMEOUT_MSG);

                    // ... and on to the next...
                    mOrderedBroadcasts.remove(0);
                    r = null;
                    continue;
                }
            } while (r == null);

            // Get the next receiver...
            int recIdx = r.nextReceiver++;

            // Keep track of when this receiver started, and make sure there
            // is a timeout message pending to kill it if need be.
            r.startTime = SystemClock.uptimeMillis();
            if (recIdx == 0) {
                r.dispatchTime = r.startTime;

                if (DEBUG_BROADCAST) Log.v(TAG,
                        "Submitting BROADCAST_TIMEOUT_MSG for "
                        + (r.startTime + BROADCAST_TIMEOUT));
                Message msg = mHandler.obtainMessage(BROADCAST_TIMEOUT_MSG);
                mHandler.sendMessageAtTime(msg, r.startTime+BROADCAST_TIMEOUT);
            }

            Object nextReceiver = r.receivers.get(recIdx);
            if (nextReceiver instanceof BroadcastFilter) {
                // Simple case: this is a registered receiver who gets
                // a direct call.
                BroadcastFilter filter = (BroadcastFilter)nextReceiver;
                if (DEBUG_BROADCAST)  Log.v(TAG,
                        "Delivering serialized to registered "
                        + filter + ": " + r);
                deliverToRegisteredReceiver(r, filter, r.ordered);
                if (r.receiver == null || !r.ordered) {
                    // The receiver has already finished, so schedule to
                    // process the next one.
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
                    r.callingPid, r.callingUid,
                    info.activityInfo.exported
                            ? -1 : info.activityInfo.applicationInfo.uid);
            if (perm != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Permission Denial: broadcasting "
                        + r.intent.toString()
                        + " from " + r.callerPackage + " (pid=" + r.callingPid
                        + ", uid=" + r.callingUid + ")"
                        + " requires " + info.activityInfo.permission
                        + " due to receiver " + info.activityInfo.packageName
                        + "/" + info.activityInfo.name);
                skip = true;
            }
            if (r.callingUid != Process.SYSTEM_UID &&
                r.requiredPermission != null) {
                try {
                    perm = ActivityThread.getPackageManager().
                            checkPermission(r.requiredPermission,
                                    info.activityInfo.applicationInfo.packageName);
                } catch (RemoteException e) {
                    perm = PackageManager.PERMISSION_DENIED;
                }
                if (perm != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "Permission Denial: receiving "
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
                skip = true;
            }

            if (skip) {
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

            // Is this receiver's application already running?
            ProcessRecord app = getProcessRecordLocked(targetProcess,
                    info.activityInfo.applicationInfo.uid);
            if (app != null && app.thread != null) {
                try {
                    processCurBroadcastLocked(r, app);
                    return;
                } catch (RemoteException e) {
                    Log.w(TAG, "Exception when sending broadcast to "
                          + r.curComponent, e);
                }

                // If a dead object exception was thrown -- fall through to
                // restart the application.
            }

            // Not running -- get it started, and enqueue this history record
            // to be executed when the app comes up.
            if ((r.curApp=startProcessLocked(targetProcess,
                    info.activityInfo.applicationInfo, true,
                    r.intent.getFlags() | Intent.FLAG_FROM_BACKGROUND,
                    "broadcast", r.curComponent)) == null) {
                // Ah, this recipient is unavailable.  Finish it if necessary,
                // and mark the broadcast record as ready for the next.
                Log.w(TAG, "Unable to launch app "
                        + info.activityInfo.applicationInfo.packageName + "/"
                        + info.activityInfo.applicationInfo.uid + " for broadcast "
                        + r.intent + ": process is bad");
                logBroadcastReceiverDiscard(r);
                finishReceiverLocked(r.receiver, r.resultCode, r.resultData,
                        r.resultExtras, r.resultAbort, true);
                scheduleBroadcastsLocked();
                r.state = BroadcastRecord.IDLE;
                return;
            }

            mPendingBroadcast = r;
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
                    className, 0);
                ai = mContext.getPackageManager().getApplicationInfo(
                    ii.targetPackage, PackageManager.GET_SHARED_LIBRARY_FILES);
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
            uninstallPackageLocked(ii.targetPackage, -1, true);
            ProcessRecord app = addAppLocked(ai);
            app.instrumentationClass = className;
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
        Log.w(TAG, report);
        try {
            if (watcher != null) {
                Bundle results = new Bundle();
                results.putString(Instrumentation.REPORT_KEY_IDENTIFIER, "ActivityManagerService");
                results.putString("Error", report);
                watcher.instrumentationStatus(cn, -1, results);
            }
        } catch (RemoteException e) {
            Log.w(TAG, e);
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
        app.instrumentationProfileFile = null;
        app.instrumentationArguments = null;

        uninstallPackageLocked(app.processName, -1, false);
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
                Log.w(TAG, "finishInstrumentation: no app for " + target);
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
            if (mConfiguration.navigation != Configuration.NAVIGATION_NONAV) {
                config.reqInputFeatures |= ConfigurationInfo.INPUT_FEATURE_FIVE_WAY_NAV;
            }
            if (mConfiguration.keyboard != Configuration.KEYBOARD_UNDEFINED) {
                config.reqInputFeatures |= ConfigurationInfo.INPUT_FEATURE_HARD_KEYBOARD;
            }
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

    public void updateConfiguration(Configuration values) {
        enforceCallingPermission(android.Manifest.permission.CHANGE_CONFIGURATION,
                "updateConfiguration()");

        synchronized(this) {
            if (values == null && mWindowManager != null) {
                // sentinel: fetch the current configuration from the window manager
                values = mWindowManager.computeNewConfiguration();
            }
            
            final long origId = Binder.clearCallingIdentity();
            updateConfigurationLocked(values, null);
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Do either or both things: (1) change the current configuration, and (2)
     * make sure the given activity is running with the (now) current
     * configuration.  Returns true if the activity has been left running, or
     * false if <var>starting</var> is being destroyed to match the new
     * configuration.
     */
    public boolean updateConfigurationLocked(Configuration values,
            HistoryRecord starting) {
        int changes = 0;
        
        boolean kept = true;
        
        if (values != null) {
            Configuration newConfig = new Configuration(mConfiguration);
            changes = newConfig.updateFrom(values);
            if (changes != 0) {
                if (DEBUG_SWITCH) {
                    Log.i(TAG, "Updating configuration to: " + values);
                }
                
                EventLog.writeEvent(LOG_CONFIGURATION_CHANGED, changes);

                if (values.locale != null) {
                    saveLocaleLocked(values.locale, 
                                     !values.locale.equals(mConfiguration.locale),
                                     values.userSetLocale);
                }

                mConfiguration = newConfig;

                Message msg = mHandler.obtainMessage(UPDATE_CONFIGURATION_MSG);
                msg.obj = new Configuration(mConfiguration);
                mHandler.sendMessage(msg);
        
                final int N = mLRUProcesses.size();
                for (int i=0; i<N; i++) {
                    ProcessRecord app = mLRUProcesses.get(i);
                    try {
                        if (app.thread != null) {
                            app.thread.scheduleConfigurationChanged(mConfiguration);
                        }
                    } catch (Exception e) {
                    }
                }
                Intent intent = new Intent(Intent.ACTION_CONFIGURATION_CHANGED);
                broadcastIntentLocked(null, null, intent, null, null, 0, null, null,
                        null, false, false, MY_PID, Process.SYSTEM_UID);
            }
        }
        
        if (changes != 0 && starting == null) {
            // If the configuration changed, and the caller is not already
            // in the process of starting an activity, then find the top
            // activity to check if its configuration needs to change.
            starting = topRunningActivityLocked(null);
        }
        
        if (starting != null) {
            kept = ensureActivityConfigurationLocked(starting, changes);
            if (kept) {
                // If this didn't result in the starting activity being
                // destroyed, then we need to make sure at this point that all
                // other activities are made visible.
                if (DEBUG_SWITCH) Log.i(TAG, "Config didn't destroy " + starting
                        + ", ensuring others are correct.");
                ensureActivitiesVisibleLocked(starting, changes);
            }
        }
        
        return kept;
    }

    private final boolean relaunchActivityLocked(HistoryRecord r,
            int changes, boolean andResume) {
        List<ResultInfo> results = null;
        List<Intent> newIntents = null;
        if (andResume) {
            results = r.results;
            newIntents = r.newIntents;
        }
        if (DEBUG_SWITCH) Log.v(TAG, "Relaunching: " + r
                + " with results=" + results + " newIntents=" + newIntents
                + " andResume=" + andResume);
        EventLog.writeEvent(andResume ? LOG_AM_RELAUNCH_RESUME_ACTIVITY
                : LOG_AM_RELAUNCH_ACTIVITY, System.identityHashCode(r),
                r.task.taskId, r.shortComponentName);
        
        r.startFreezingScreenLocked(r.app, 0);
        
        try {
            if (DEBUG_SWITCH) Log.i(TAG, "Switch is restarting resumed " + r);
            r.app.thread.scheduleRelaunchActivity(r, results, newIntents,
                    changes, !andResume);
            // Note: don't need to call pauseIfSleepingLocked() here, because
            // the caller will only pass in 'andResume' if this activity is
            // currently resumed, which implies we aren't sleeping.
        } catch (RemoteException e) {
            return false;
        }

        if (andResume) {
            r.results = null;
            r.newIntents = null;
        }

        return true;
    }

    /**
     * Make sure the given activity matches the current configuration.  Returns
     * false if the activity had to be destroyed.  Returns true if the
     * configuration is the same, or the activity will remain running as-is
     * for whatever reason.  Ensures the HistoryRecord is updated with the
     * correct configuration and all other bookkeeping is handled.
     */
    private final boolean ensureActivityConfigurationLocked(HistoryRecord r,
            int globalChanges) {
        if (DEBUG_SWITCH) Log.i(TAG, "Ensuring correct configuration: " + r);
        
        // Short circuit: if the two configurations are the exact same
        // object (the common case), then there is nothing to do.
        Configuration newConfig = mConfiguration;
        if (r.configuration == newConfig) {
            if (DEBUG_SWITCH) Log.i(TAG, "Configuration unchanged in " + r);
            return true;
        }
        
        // We don't worry about activities that are finishing.
        if (r.finishing) {
            if (DEBUG_SWITCH) Log.i(TAG,
                    "Configuration doesn't matter in finishing " + r);
            r.stopFreezingScreenLocked(false);
            return true;
        }
        
        // Okay we now are going to make this activity have the new config.
        // But then we need to figure out how it needs to deal with that.
        Configuration oldConfig = r.configuration;
        r.configuration = newConfig;
        
        // If the activity isn't currently running, just leave the new
        // configuration and it will pick that up next time it starts.
        if (r.app == null || r.app.thread == null) {
            if (DEBUG_SWITCH) Log.i(TAG,
                    "Configuration doesn't matter not running " + r);
            r.stopFreezingScreenLocked(false);
            return true;
        }
        
        // If the activity isn't persistent, there is a chance we will
        // need to restart it.
        if (!r.persistent) {

            // Figure out what has changed between the two configurations.
            int changes = oldConfig.diff(newConfig);
            if (DEBUG_SWITCH) {
                Log.i(TAG, "Checking to restart " + r.info.name + ": changed=0x"
                        + Integer.toHexString(changes) + ", handles=0x"
                        + Integer.toHexString(r.info.configChanges));
            }
            if ((changes&(~r.info.configChanges)) != 0) {
                // Aha, the activity isn't handling the change, so DIE DIE DIE.
                r.configChangeFlags |= changes;
                r.startFreezingScreenLocked(r.app, globalChanges);
                if (r.app == null || r.app.thread == null) {
                    if (DEBUG_SWITCH) Log.i(TAG, "Switch is destroying non-running " + r);
                    destroyActivityLocked(r, true);
                } else if (r.state == ActivityState.PAUSING) {
                    // A little annoying: we are waiting for this activity to
                    // finish pausing.  Let's not do anything now, but just
                    // flag that it needs to be restarted when done pausing.
                    r.configDestroy = true;
                    return true;
                } else if (r.state == ActivityState.RESUMED) {
                    // Try to optimize this case: the configuration is changing
                    // and we need to restart the top, resumed activity.
                    // Instead of doing the normal handshaking, just say
                    // "restart!".
                    if (DEBUG_SWITCH) Log.i(TAG, "Switch is restarting resumed " + r);
                    relaunchActivityLocked(r, r.configChangeFlags, true);
                    r.configChangeFlags = 0;
                } else {
                    if (DEBUG_SWITCH) Log.i(TAG, "Switch is restarting non-resumed " + r);
                    relaunchActivityLocked(r, r.configChangeFlags, false);
                    r.configChangeFlags = 0;
                }
                
                // All done...  tell the caller we weren't able to keep this
                // activity around.
                return false;
            }
        }
        
        // Default case: the activity can handle this new configuration, so
        // hand it over.  Note that we don't need to give it the new
        // configuration, since we always send configuration changes to all
        // process when they happen so it can just use whatever configuration
        // it last got.
        if (r.app != null && r.app.thread != null) {
            try {
                r.app.thread.scheduleActivityConfigurationChanged(r);
            } catch (RemoteException e) {
                // If process died, whatever.
            }
        }
        r.stopFreezingScreenLocked(false);
        
        return true;
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

    private final int computeOomAdjLocked(
        ProcessRecord app, int hiddenAdj, ProcessRecord TOP_APP) {
        if (mAdjSeq == app.adjSeq) {
            // This adjustment has already been computed.
            return app.curAdj;
        }

        if (app.thread == null) {
            app.adjSeq = mAdjSeq;
            return (app.curAdj=EMPTY_APP_ADJ);
        }

        app.isForeground = false;

        // Determine the importance of the process, starting with most
        // important to least, and assign an appropriate OOM adjustment.
        int adj;
        int N;
        if (app == TOP_APP || app.instrumentationClass != null
                || app.persistentActivities > 0) {
            // The last app on the list is the foreground app.
            adj = FOREGROUND_APP_ADJ;
            app.isForeground = true;
        } else if (app.curReceiver != null ||
                (mPendingBroadcast != null && mPendingBroadcast.curApp == app)) {
            // An app that is currently receiving a broadcast also
            // counts as being in the foreground.
            adj = FOREGROUND_APP_ADJ;
        } else if (app.executingServices.size() > 0) {
            // An app that is currently executing a service callback also
            // counts as being in the foreground.
            adj = FOREGROUND_APP_ADJ;
        } else if (app.foregroundServices || app.forcingToForeground != null) {
            // The user is aware of this app, so make it visible.
            adj = VISIBLE_APP_ADJ;
        } else if (app == mHomeProcess) {
            // This process is hosting what we currently consider to be the
            // home app, so we don't want to let it go into the background.
            adj = HOME_APP_ADJ;
        } else if ((N=app.activities.size()) != 0) {
            // This app is in the background with paused activities.
            adj = hiddenAdj;
            for (int j=0; j<N; j++) {
                if (((HistoryRecord)app.activities.get(j)).visible) {
                    // This app has a visible activity!
                    adj = VISIBLE_APP_ADJ;
                    break;
                }
            }
        } else {
            // A very not-needed process.
            adj = EMPTY_APP_ADJ;
        }

        // By default, we use the computed adjustment.  It may be changed if
        // there are applications dependent on our services or providers, but
        // this gives us a baseline and makes sure we don't get into an
        // infinite recursion.
        app.adjSeq = mAdjSeq;
        app.curRawAdj = adj;
        app.curAdj = adj <= app.maxAdj ? adj : app.maxAdj;

        if (app.services.size() != 0 && adj > FOREGROUND_APP_ADJ) {
            // If this process has active services running in it, we would
            // like to avoid killing it unless it would prevent the current
            // application from running.
            if (adj > hiddenAdj) {
                adj = hiddenAdj;
            }
            final long now = SystemClock.uptimeMillis();
            // This process is more important if the top activity is
            // bound to the service.
            Iterator jt = app.services.iterator();
            while (jt.hasNext() && adj > FOREGROUND_APP_ADJ) {
                ServiceRecord s = (ServiceRecord)jt.next();
                if (s.startRequested) {
                    if (now < (s.lastActivity+MAX_SERVICE_INACTIVITY)) {
                        // This service has seen some activity within
                        // recent memory, so we will keep its process ahead
                        // of the background processes.
                        if (adj > SECONDARY_SERVER_ADJ) {
                            adj = SECONDARY_SERVER_ADJ;
                        }
                    } else {
                        // This service has been inactive for too long, just
                        // put it with the rest of the background processes.
                        if (adj > hiddenAdj) {
                            adj = hiddenAdj;
                        }
                    }
                }
                if (s.connections.size() > 0 && adj > FOREGROUND_APP_ADJ) {
                    Iterator<ConnectionRecord> kt
                            = s.connections.values().iterator();
                    while (kt.hasNext() && adj > FOREGROUND_APP_ADJ) {
                        // XXX should compute this based on the max of
                        // all connected clients.
                        ConnectionRecord cr = kt.next();
                        if (cr.binding.client == app) {
                            // Binding to ourself is not interesting.
                            continue;
                        }
                        if ((cr.flags&Context.BIND_AUTO_CREATE) != 0) {
                            ProcessRecord client = cr.binding.client;
                            int myHiddenAdj = hiddenAdj;
                            if (myHiddenAdj > client.hiddenAdj) {
                                if (client.hiddenAdj > VISIBLE_APP_ADJ) {
                                    myHiddenAdj = client.hiddenAdj;
                                } else {
                                    myHiddenAdj = VISIBLE_APP_ADJ;
                                }
                            }
                            int clientAdj = computeOomAdjLocked(
                                client, myHiddenAdj, TOP_APP);
                            if (adj > clientAdj) {
                                adj = clientAdj > VISIBLE_APP_ADJ
                                        ? clientAdj : VISIBLE_APP_ADJ;
                            }
                        }
                        HistoryRecord a = cr.activity;
                        //if (a != null) {
                        //    Log.i(TAG, "Connection to " + a ": state=" + a.state);
                        //}
                        if (a != null && adj > FOREGROUND_APP_ADJ &&
                                (a.state == ActivityState.RESUMED
                                 || a.state == ActivityState.PAUSING)) {
                            adj = FOREGROUND_APP_ADJ;
                        }
                    }
                }
            }
        }

        if (app.pubProviders.size() != 0 && adj > FOREGROUND_APP_ADJ) {
            // If this process has published any content providers, then
            // its adjustment makes it at least as important as any of the
            // processes using those providers, and no less important than
            // CONTENT_PROVIDER_ADJ, which is just shy of EMPTY.
            if (adj > CONTENT_PROVIDER_ADJ) {
                adj = CONTENT_PROVIDER_ADJ;
            }
            Iterator jt = app.pubProviders.values().iterator();
            while (jt.hasNext() && adj > FOREGROUND_APP_ADJ) {
                ContentProviderRecord cpr = (ContentProviderRecord)jt.next();
                if (cpr.clients.size() != 0) {
                    Iterator<ProcessRecord> kt = cpr.clients.iterator();
                    while (kt.hasNext() && adj > FOREGROUND_APP_ADJ) {
                        ProcessRecord client = kt.next();
                        if (client == app) {
                            // Being our own client is not interesting.
                            continue;
                        }
                        int myHiddenAdj = hiddenAdj;
                        if (myHiddenAdj > client.hiddenAdj) {
                            if (client.hiddenAdj > FOREGROUND_APP_ADJ) {
                                myHiddenAdj = client.hiddenAdj;
                            } else {
                                myHiddenAdj = FOREGROUND_APP_ADJ;
                            }
                        }
                        int clientAdj = computeOomAdjLocked(
                            client, myHiddenAdj, TOP_APP);
                        if (adj > clientAdj) {
                            adj = clientAdj > FOREGROUND_APP_ADJ
                            ? clientAdj : FOREGROUND_APP_ADJ;
                        }
                    }
                }
                // If the provider has external (non-framework) process
                // dependencies, ensure that its adjustment is at least
                // FOREGROUND_APP_ADJ.
                if (cpr.externals != 0) {
                    if (adj > FOREGROUND_APP_ADJ) {
                        adj = FOREGROUND_APP_ADJ;
                    }
                }
            }
        }

        app.curRawAdj = adj;
        
        //Log.i(TAG, "OOM ADJ " + app + ": pid=" + app.pid +
        //      " adj=" + adj + " curAdj=" + app.curAdj + " maxAdj=" + app.maxAdj);
        if (adj > app.maxAdj) {
            adj = app.maxAdj;
        }

        app.curAdj = adj;

        return adj;
    }

    /**
     * Ask a given process to GC right now.
     */
    final void performAppGcLocked(ProcessRecord app) {
        try {
            app.lastRequestedGc = SystemClock.uptimeMillis();
            if (app.thread != null) {
                app.thread.processInBackground();
            }
        } catch (Exception e) {
            // whatever.
        }
    }
    
    /**
     * Returns true if things are idle enough to perform GCs.
     */
    private final boolean canGcNow() {
        return mParallelBroadcasts.size() == 0
                && mOrderedBroadcasts.size() == 0
                && (mSleeping || (mResumedActivity != null &&
                        mResumedActivity.idle));
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
        if (canGcNow()) {
            while (mProcessesToGc.size() > 0) {
                ProcessRecord proc = mProcessesToGc.remove(0);
                if (proc.curRawAdj > VISIBLE_APP_ADJ) {
                    // To avoid spamming the system, we will GC processes one
                    // at a time, waiting a few seconds between each.
                    performAppGcLocked(proc);
                    scheduleAppGcsLocked();
                    return;
                }
            }
        }
    }
    
    /**
     * If all looks good, perform GCs on all processes waiting for them.
     */
    final void performAppGcsIfAppropriateLocked() {
        if (canGcNow()) {
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
        Message msg = mHandler.obtainMessage(GC_BACKGROUND_PROCESSES_MSG);
        mHandler.sendMessageDelayed(msg, GC_TIMEOUT);
    }
    
    /**
     * Set up to ask a process to GC itself.  This will either do it
     * immediately, or put it on the list of processes to gc the next
     * time things are idle.
     */
    final void scheduleAppGcLocked(ProcessRecord app) {
        long now = SystemClock.uptimeMillis();
        if ((app.lastRequestedGc+5000) > now) {
            return;
        }
        if (!mProcessesToGc.contains(app)) {
            mProcessesToGc.add(app);
            scheduleAppGcsLocked();
        }
    }

    private final boolean updateOomAdjLocked(
        ProcessRecord app, int hiddenAdj, ProcessRecord TOP_APP) {
        app.hiddenAdj = hiddenAdj;

        if (app.thread == null) {
            return true;
        }

        int adj = computeOomAdjLocked(app, hiddenAdj, TOP_APP);

        //Log.i(TAG, "Computed adj " + adj + " for app " + app.processName);
        //Thread priority adjustment is disabled out to see
        //how the kernel scheduler performs.
        if (false) {
            if (app.pid != 0 && app.isForeground != app.setIsForeground) {
                app.setIsForeground = app.isForeground;
                if (app.pid != MY_PID) {
                    if (DEBUG_SWITCH || DEBUG_OOM_ADJ) Log.v(TAG, "Setting priority of " + app
                            + " to " + (app.isForeground
                            ? Process.THREAD_PRIORITY_FOREGROUND
                            : Process.THREAD_PRIORITY_DEFAULT));
                    try {
                        Process.setThreadPriority(app.pid, app.isForeground
                                ? Process.THREAD_PRIORITY_FOREGROUND
                                : Process.THREAD_PRIORITY_DEFAULT);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Exception trying to set priority of application thread "
                                + app.pid, e);
                    }
                }
            }
        }
        if (app.pid != 0 && app.pid != MY_PID) {
            if (app.curRawAdj != app.setRawAdj) {
                if (app.curRawAdj > FOREGROUND_APP_ADJ
                        && app.setRawAdj <= FOREGROUND_APP_ADJ) {
                    // If this app is transitioning from foreground to
                    // non-foreground, have it do a gc.
                    scheduleAppGcLocked(app);
                } else if (app.curRawAdj >= HIDDEN_APP_MIN_ADJ
                        && app.setRawAdj < HIDDEN_APP_MIN_ADJ) {
                    // Likewise do a gc when an app is moving in to the
                    // background (such as a service stopping).
                    scheduleAppGcLocked(app);
                }
                app.setRawAdj = app.curRawAdj;
            }
            if (adj != app.setAdj) {
                if (Process.setOomAdj(app.pid, adj)) {
                    if (DEBUG_SWITCH || DEBUG_OOM_ADJ) Log.v(
                        TAG, "Set app " + app.processName +
                        " oom adj to " + adj);
                    app.setAdj = adj;
                } else {
                    return false;
                }
            }
        }

        return true;
    }

    private final HistoryRecord resumedAppLocked() {
        HistoryRecord resumedActivity = mResumedActivity;
        if (resumedActivity == null || resumedActivity.app == null) {
            resumedActivity = mPausingActivity;
            if (resumedActivity == null || resumedActivity.app == null) {
                resumedActivity = topRunningActivityLocked(null);
            }
        }
        return resumedActivity;
    }

    private final boolean updateOomAdjLocked(ProcessRecord app) {
        final HistoryRecord TOP_ACT = resumedAppLocked();
        final ProcessRecord TOP_APP = TOP_ACT != null ? TOP_ACT.app : null;
        int curAdj = app.curAdj;
        final boolean wasHidden = app.curAdj >= HIDDEN_APP_MIN_ADJ
            && app.curAdj <= HIDDEN_APP_MAX_ADJ;

        mAdjSeq++;

        final boolean res = updateOomAdjLocked(app, app.hiddenAdj, TOP_APP);
        if (res) {
            final boolean nowHidden = app.curAdj >= HIDDEN_APP_MIN_ADJ
                && app.curAdj <= HIDDEN_APP_MAX_ADJ;
            if (nowHidden != wasHidden) {
                // Changed to/from hidden state, so apps after it in the LRU
                // list may also be changed.
                updateOomAdjLocked();
            }
        }
        return res;
    }

    private final boolean updateOomAdjLocked() {
        boolean didOomAdj = true;
        final HistoryRecord TOP_ACT = resumedAppLocked();
        final ProcessRecord TOP_APP = TOP_ACT != null ? TOP_ACT.app : null;

        if (false) {
            RuntimeException e = new RuntimeException();
            e.fillInStackTrace();
            Log.i(TAG, "updateOomAdj: top=" + TOP_ACT, e);
        }

        mAdjSeq++;

        // First try updating the OOM adjustment for each of the
        // application processes based on their current state.
        int i = mLRUProcesses.size();
        int curHiddenAdj = HIDDEN_APP_MIN_ADJ;
        while (i > 0) {
            i--;
            ProcessRecord app = mLRUProcesses.get(i);
            if (updateOomAdjLocked(app, curHiddenAdj, TOP_APP)) {
                if (curHiddenAdj < HIDDEN_APP_MAX_ADJ
                    && app.curAdj == curHiddenAdj) {
                    curHiddenAdj++;
                }
            } else {
                didOomAdj = false;
            }
        }

        // todo: for now pretend like OOM ADJ didn't work, because things
        // aren't behaving as expected on Linux -- it's not killing processes.
        return ENFORCE_PROCESS_LIMIT || mProcessLimit > 0 ? false : didOomAdj;
    }

    private final void trimApplications() {
        synchronized (this) {
            int i;

            // First remove any unused application processes whose package
            // has been removed.
            for (i=mRemovedProcesses.size()-1; i>=0; i--) {
                final ProcessRecord app = mRemovedProcesses.get(i);
                if (app.activities.size() == 0
                        && app.curReceiver == null && app.services.size() == 0) {
                    Log.i(
                        TAG, "Exiting empty application process "
                        + app.processName + " ("
                        + (app.thread != null ? app.thread.asBinder() : null)
                        + ")\n");
                    if (app.pid > 0 && app.pid != MY_PID) {
                        Process.killProcess(app.pid);
                    } else {
                        try {
                            app.thread.scheduleExit();
                        } catch (Exception e) {
                            // Ignore exceptions.
                        }
                    }
                    cleanUpApplicationRecordLocked(app, false, -1);
                    mRemovedProcesses.remove(i);
                    
                    if (app.persistent) {
                        if (app.persistent) {
                            addAppLocked(app.info);
                        }
                    }
                }
            }

            // Now try updating the OOM adjustment for each of the
            // application processes based on their current state.
            // If the setOomAdj() API is not supported, then go with our
            // back-up plan...
            if (!updateOomAdjLocked()) {

                // Count how many processes are running services.
                int numServiceProcs = 0;
                for (i=mLRUProcesses.size()-1; i>=0; i--) {
                    final ProcessRecord app = mLRUProcesses.get(i);

                    if (app.persistent || app.services.size() != 0
                            || app.curReceiver != null
                            || app.persistentActivities > 0) {
                        // Don't count processes holding services against our
                        // maximum process count.
                        if (localLOGV) Log.v(
                            TAG, "Not trimming app " + app + " with services: "
                            + app.services);
                        numServiceProcs++;
                    }
                }

                int curMaxProcs = mProcessLimit;
                if (curMaxProcs <= 0) curMaxProcs = MAX_PROCESSES;
                if (mAlwaysFinishActivities) {
                    curMaxProcs = 1;
                }
                curMaxProcs += numServiceProcs;

                // Quit as many processes as we can to get down to the desired
                // process count.  First remove any processes that no longer
                // have activites running in them.
                for (   i=0;
                        i<mLRUProcesses.size()
                            && mLRUProcesses.size() > curMaxProcs;
                        i++) {
                    final ProcessRecord app = mLRUProcesses.get(i);
                    // Quit an application only if it is not currently
                    // running any activities.
                    if (!app.persistent && app.activities.size() == 0
                            && app.curReceiver == null && app.services.size() == 0) {
                        Log.i(
                            TAG, "Exiting empty application process "
                            + app.processName + " ("
                            + (app.thread != null ? app.thread.asBinder() : null)
                            + ")\n");
                        if (app.pid > 0 && app.pid != MY_PID) {
                            Process.killProcess(app.pid);
                        } else {
                            try {
                                app.thread.scheduleExit();
                            } catch (Exception e) {
                                // Ignore exceptions.
                            }
                        }
                        // todo: For now we assume the application is not buggy
                        // or evil, and will quit as a result of our request.
                        // Eventually we need to drive this off of the death
                        // notification, and kill the process if it takes too long.
                        cleanUpApplicationRecordLocked(app, false, i);
                        i--;
                    }
                }

                // If we still have too many processes, now from the least
                // recently used process we start finishing activities.
                if (Config.LOGV) Log.v(
                    TAG, "*** NOW HAVE " + mLRUProcesses.size() +
                    " of " + curMaxProcs + " processes");
                for (   i=0;
                        i<mLRUProcesses.size()
                            && mLRUProcesses.size() > curMaxProcs;
                        i++) {
                    final ProcessRecord app = mLRUProcesses.get(i);
                    // Quit the application only if we have a state saved for
                    // all of its activities.
                    boolean canQuit = !app.persistent && app.curReceiver == null
                        && app.services.size() == 0
                        && app.persistentActivities == 0;
                    int NUMA = app.activities.size();
                    int j;
                    if (Config.LOGV) Log.v(
                        TAG, "Looking to quit " + app.processName);
                    for (j=0; j<NUMA && canQuit; j++) {
                        HistoryRecord r = (HistoryRecord)app.activities.get(j);
                        if (Config.LOGV) Log.v(
                            TAG, "  " + r.intent.getComponent().flattenToShortString()
                            + ": frozen=" + r.haveState + ", visible=" + r.visible);
                        canQuit = (r.haveState || !r.stateNotNeeded)
                                && !r.visible && r.stopped;
                    }
                    if (canQuit) {
                        // Finish all of the activities, and then the app itself.
                        for (j=0; j<NUMA; j++) {
                            HistoryRecord r = (HistoryRecord)app.activities.get(j);
                            if (!r.finishing) {
                                destroyActivityLocked(r, false);
                            }
                            r.resultTo = null;
                        }
                        Log.i(TAG, "Exiting application process "
                              + app.processName + " ("
                              + (app.thread != null ? app.thread.asBinder() : null)
                              + ")\n");
                        if (app.pid > 0 && app.pid != MY_PID) {
                            Process.killProcess(app.pid);
                        } else {
                            try {
                                app.thread.scheduleExit();
                            } catch (Exception e) {
                                // Ignore exceptions.
                            }
                        }
                        // todo: For now we assume the application is not buggy
                        // or evil, and will quit as a result of our request.
                        // Eventually we need to drive this off of the death
                        // notification, and kill the process if it takes too long.
                        cleanUpApplicationRecordLocked(app, false, i);
                        i--;
                        //dump();
                    }
                }

            }

            int curMaxActivities = MAX_ACTIVITIES;
            if (mAlwaysFinishActivities) {
                curMaxActivities = 1;
            }

            // Finally, if there are too many activities now running, try to
            // finish as many as we can to get back down to the limit.
            for (   i=0;
                    i<mLRUActivities.size()
                        && mLRUActivities.size() > curMaxActivities;
                    i++) {
                final HistoryRecord r
                    = (HistoryRecord)mLRUActivities.get(i);

                // We can finish this one if we have its icicle saved and
                // it is not persistent.
                if ((r.haveState || !r.stateNotNeeded) && !r.visible
                        && r.stopped && !r.persistent && !r.finishing) {
                    final int origSize = mLRUActivities.size();
                    destroyActivityLocked(r, true);

                    // This will remove it from the LRU list, so keep
                    // our index at the same value.  Note that this check to
                    // see if the size changes is just paranoia -- if
                    // something unexpected happens, we don't want to end up
                    // in an infinite loop.
                    if (origSize > mLRUActivities.size()) {
                        i--;
                    }
                }
            }
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

            for (int i = mLRUProcesses.size() - 1 ; i >= 0 ; i--) {
                ProcessRecord r = mLRUProcesses.get(i);
                if (r.thread != null && r.persistent) {
                    Process.sendSignal(r.pid, sig);
                }
            }
        }
    }

    public boolean profileControl(String process, boolean start,
            String path) throws RemoteException {

        synchronized (this) {
            // note: hijacking SET_ACTIVITY_WATCHER, but should be changed to
            // its own permission.
            if (checkCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires permission "
                        + android.Manifest.permission.SET_ACTIVITY_WATCHER);
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
            
            boolean isSecure = "1".equals(SystemProperties.get(SYSTEM_SECURE, "0"));
            if (isSecure) {
                if ((proc.info.flags&ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
                    throw new SecurityException("Process not debuggable: " + proc);
                }
            }
            
            try {
                proc.thread.profilerControl(start, path);
                return true;
            } catch (RemoteException e) {
                throw new IllegalStateException("Process disappeared");
            }
        }
    }
    
    /** In this method we try to acquire our lock to make sure that we have not deadlocked */
    public void monitor() {
        synchronized (this) { }
    }
}
