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

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.START_TASKS_FROM_RECENTS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static com.android.internal.util.XmlUtils.readBooleanAttribute;
import static com.android.internal.util.XmlUtils.readIntAttribute;
import static com.android.internal.util.XmlUtils.readLongAttribute;
import static com.android.internal.util.XmlUtils.writeBooleanAttribute;
import static com.android.internal.util.XmlUtils.writeIntAttribute;
import static com.android.internal.util.XmlUtils.writeLongAttribute;
import static com.android.server.Watchdog.NATIVE_STACKS_OF_INTEREST;
import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;
import static com.android.server.am.ActivityStackSupervisor.HOME_STACK_ID;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.IActivityContainer;
import android.app.IActivityContainerCallback;
import android.app.IAppTask;
import android.app.admin.DevicePolicyManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManagerInternal;
import android.appwidget.AppWidgetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.BatteryStats;
import android.os.PersistableBundle;
import android.service.voice.IVoiceInteractionSession;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseIntArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IAppOpsService;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.app.ProcessMap;
import com.android.internal.app.ProcessStats;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.os.TransferPipe;
import com.android.internal.os.Zygote;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.MemInfoReader;
import com.android.internal.util.Preconditions;
import com.android.server.AppOpsService;
import com.android.server.AttributeCache;
import com.android.server.IntentResolver;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;
import com.android.server.Watchdog;
import com.android.server.am.ActivityStack.ActivityState;
import com.android.server.firewall.IntentFirewall;
import com.android.server.pm.UserManagerService;
import com.android.server.wm.AppTransition;
import com.android.server.wm.WindowManagerService;
import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityManager.StackInfo;
import android.app.ActivityManagerInternal;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.ActivityThread;
import android.app.AlertDialog;
import android.app.AppGlobals;
import android.app.ApplicationErrorReport;
import android.app.Dialog;
import android.app.IActivityController;
import android.app.IApplicationThread;
import android.app.IInstrumentationWatcher;
import android.app.INotificationManager;
import android.app.IProcessObserver;
import android.app.IServiceConnection;
import android.app.IStopUserCallback;
import android.app.IUiAutomationConnection;
import android.app.IUserSwitchObserver;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.backup.IBackupManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IContentProvider;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageManager;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PathPermission;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.net.Proxy;
import android.net.ProxyInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.DropBoxManager;
import android.os.Environment;
import android.os.FactoryTest;
import android.os.FileObserver;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPermissionController;
import android.os.IRemoteCallback;
import android.os.IUserManager;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UpdateLock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.AtomicFile;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.Xml;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

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
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
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

    private static final String USER_DATA_DIR = "/data/user/";
    // File that stores last updated system version and called preboot receivers
    static final String CALLED_PRE_BOOTS_FILENAME = "called_pre_boots.dat";

    static final String TAG = "ActivityManager";
    static final String TAG_MU = "ActivityManagerServiceMU";
    static final boolean DEBUG = false;
    static final boolean localLOGV = DEBUG;
    static final boolean DEBUG_BACKUP = localLOGV || false;
    static final boolean DEBUG_BROADCAST = localLOGV || false;
    static final boolean DEBUG_BROADCAST_LIGHT = DEBUG_BROADCAST || false;
    static final boolean DEBUG_BACKGROUND_BROADCAST = DEBUG_BROADCAST || false;
    static final boolean DEBUG_CLEANUP = localLOGV || false;
    static final boolean DEBUG_CONFIGURATION = localLOGV || false;
    static final boolean DEBUG_FOCUS = false;
    static final boolean DEBUG_IMMERSIVE = localLOGV || false;
    static final boolean DEBUG_MU = localLOGV || false;
    static final boolean DEBUG_OOM_ADJ = localLOGV || false;
    static final boolean DEBUG_LRU = localLOGV || false;
    static final boolean DEBUG_PAUSE = localLOGV || false;
    static final boolean DEBUG_POWER = localLOGV || false;
    static final boolean DEBUG_POWER_QUICK = DEBUG_POWER || false;
    static final boolean DEBUG_PROCESS_OBSERVERS = localLOGV || false;
    static final boolean DEBUG_PROCESSES = localLOGV || false;
    static final boolean DEBUG_PROVIDER = localLOGV || false;
    static final boolean DEBUG_RESULTS = localLOGV || false;
    static final boolean DEBUG_SERVICE = localLOGV || false;
    static final boolean DEBUG_SERVICE_EXECUTING = localLOGV || false;
    static final boolean DEBUG_STACK = localLOGV || false;
    static final boolean DEBUG_SWITCH = localLOGV || false;
    static final boolean DEBUG_TASKS = localLOGV || false;
    static final boolean DEBUG_THUMBNAILS = localLOGV || false;
    static final boolean DEBUG_TRANSITION = localLOGV || false;
    static final boolean DEBUG_URI_PERMISSION = localLOGV || false;
    static final boolean DEBUG_USER_LEAVING = localLOGV || false;
    static final boolean DEBUG_VISBILITY = localLOGV || false;
    static final boolean DEBUG_PSS = localLOGV || false;
    static final boolean DEBUG_LOCKSCREEN = localLOGV || false;
    static final boolean DEBUG_RECENTS = localLOGV || false;
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

    static final boolean IS_USER_BUILD = "user".equals(Build.TYPE);

    // Maximum number of recent tasks that we can remember.
    static final int MAX_RECENT_TASKS = ActivityManager.isLowRamDeviceStatic() ? 100 : 200;

    // Maximum number recent bitmaps to keep in memory.
    static final int MAX_RECENT_BITMAPS = 5;

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
    static final int PROC_START_TIMEOUT_WITH_WRAPPER = 1200*1000;

    // How long to wait after going idle before forcing apps to GC.
    static final int GC_TIMEOUT = 5*1000;

    // The minimum amount of time between successive GC requests for a process.
    static final int GC_MIN_INTERVAL = 60*1000;

    // The minimum amount of time between successive PSS requests for a process.
    static final int FULL_PSS_MIN_INTERVAL = 10*60*1000;

    // The minimum amount of time between successive PSS requests for a process
    // when the request is due to the memory state being lowered.
    static final int FULL_PSS_LOWERED_INTERVAL = 2*60*1000;

    // The rate at which we check for apps using excessive power -- 15 mins.
    static final int POWER_CHECK_DELAY = (DEBUG_POWER_QUICK ? 2 : 15) * 60*1000;

    // The minimum sample duration we will allow before deciding we have
    // enough data on wake locks to start killing things.
    static final int WAKE_LOCK_MIN_CHECK_DURATION = (DEBUG_POWER_QUICK ? 1 : 5) * 60*1000;

    // The minimum sample duration we will allow before deciding we have
    // enough data on CPU usage to start killing things.
    static final int CPU_MIN_CHECK_DURATION = (DEBUG_POWER_QUICK ? 1 : 5) * 60*1000;

    // How long we allow a receiver to run before giving up on it.
    static final int BROADCAST_FG_TIMEOUT = 10*1000;
    static final int BROADCAST_BG_TIMEOUT = 60*1000;

    // How long we wait until we timeout on key dispatching.
    static final int KEY_DISPATCHING_TIMEOUT = 5*1000;

    // How long we wait until we timeout on key dispatching during instrumentation.
    static final int INSTRUMENTATION_KEY_DISPATCHING_TIMEOUT = 60*1000;

    // Amount of time we wait for observers to handle a user switch before
    // giving up on them and unfreezing the screen.
    static final int USER_SWITCH_TIMEOUT = 2*1000;

    // Maximum number of users we allow to be running at a time.
    static final int MAX_RUNNING_USERS = 3;

    // How long to wait in getAssistContextExtras for the activity and foreground services
    // to respond with the result.
    static final int PENDING_ASSIST_EXTRAS_TIMEOUT = 500;

    // Maximum number of persisted Uri grants a package is allowed
    static final int MAX_PERSISTED_URI_GRANTS = 128;

    static final int MY_PID = Process.myPid();

    static final String[] EMPTY_STRING_ARRAY = new String[0];

    // How many bytes to write into the dropbox log before truncating
    static final int DROPBOX_MAX_SIZE = 256 * 1024;

    // Access modes for handleIncomingUser.
    static final int ALLOW_NON_FULL = 0;
    static final int ALLOW_NON_FULL_IN_PROFILE = 1;
    static final int ALLOW_FULL_ONLY = 2;

    static final int LAST_PREBOOT_DELIVERED_FILE_VERSION = 10000;

    /** All system services */
    SystemServiceManager mSystemServiceManager;

    /** Run all ActivityStacks through this */
    ActivityStackSupervisor mStackSupervisor;

    public IntentFirewall mIntentFirewall;

    // Whether we should show our dialogs (ANR, crash, etc) or just perform their
    // default actuion automatically.  Important for devices without direct input
    // devices.
    private boolean mShowDialogs = true;

    BroadcastQueue mFgBroadcastQueue;
    BroadcastQueue mBgBroadcastQueue;
    // Convenient for easy iteration over the queues. Foreground is first
    // so that dispatch of foreground broadcasts gets precedence.
    final BroadcastQueue[] mBroadcastQueues = new BroadcastQueue[2];

    BroadcastQueue broadcastQueueForIntent(Intent intent) {
        final boolean isFg = (intent.getFlags() & Intent.FLAG_RECEIVER_FOREGROUND) != 0;
        if (DEBUG_BACKGROUND_BROADCAST) {
            Slog.i(TAG, "Broadcast intent " + intent + " on "
                    + (isFg ? "foreground" : "background")
                    + " queue");
        }
        return (isFg) ? mFgBroadcastQueue : mBgBroadcastQueue;
    }

    BroadcastRecord broadcastRecordForReceiverLocked(IBinder receiver) {
        for (BroadcastQueue queue : mBroadcastQueues) {
            BroadcastRecord r = queue.getMatchingOrderedReceiver(receiver);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /**
     * Activity we have told the window manager to have key focus.
     */
    ActivityRecord mFocusedActivity = null;

    /**
     * List of intents that were used to start the most recent tasks.
     */
    ArrayList<TaskRecord> mRecentTasks;
    ArraySet<TaskRecord> mTmpRecents = new ArraySet<TaskRecord>();

    /**
     * For addAppTask: cached of the last activity component that was added.
     */
    ComponentName mLastAddedTaskComponent;

    /**
     * For addAppTask: cached of the last activity uid that was added.
     */
    int mLastAddedTaskUid;

    /**
     * For addAppTask: cached of the last ActivityInfo that was added.
     */
    ActivityInfo mLastAddedTaskActivity;

    public class PendingAssistExtras extends Binder implements Runnable {
        public final ActivityRecord activity;
        public boolean haveResult = false;
        public Bundle result = null;
        public PendingAssistExtras(ActivityRecord _activity) {
            activity = _activity;
        }
        @Override
        public void run() {
            Slog.w(TAG, "getAssistContextExtras failed: timeout retrieving from " + activity);
            synchronized (this) {
                haveResult = true;
                notifyAll();
            }
        }
    }

    final ArrayList<PendingAssistExtras> mPendingAssistExtras
            = new ArrayList<PendingAssistExtras>();

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
     * Tracking long-term execution of processes to look for abuse and other
     * bad app behavior.
     */
    final ProcessStatsService mProcessStats;

    /**
     * The currently running isolated processes.
     */
    final SparseArray<ProcessRecord> mIsolatedProcesses = new SparseArray<ProcessRecord>();

    /**
     * Counter for assigning isolated process uids, to avoid frequently reusing the
     * same ones.
     */
    int mNextIsolatedProcessUid = 0;

    /**
     * The currently running heavy-weight process, if any.
     */
    ProcessRecord mHeavyWeightProcess = null;

    /**
     * The last time that various processes have crashed.
     */
    final ProcessMap<Long> mProcessCrashTimes = new ProcessMap<Long>();

    /**
     * Information about a process that is currently marked as bad.
     */
    static final class BadProcessInfo {
        BadProcessInfo(long time, String shortMsg, String longMsg, String stack) {
            this.time = time;
            this.shortMsg = shortMsg;
            this.longMsg = longMsg;
            this.stack = stack;
        }

        final long time;
        final String shortMsg;
        final String longMsg;
        final String stack;
    }

    /**
     * Set of applications that we consider to be bad, and will reject
     * incoming broadcasts from (which the user has no control over).
     * Processes are added to this set when they have crashed twice within
     * a minimum amount of time; they are removed from it when they are
     * later restarted (hopefully due to some user action).  The value is the
     * time it was added to the list.
     */
    final ProcessMap<BadProcessInfo> mBadProcesses = new ProcessMap<BadProcessInfo>();

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
    final SparseArray<ForegroundToken> mForegroundProcesses = new SparseArray<ForegroundToken>();

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
     * Processes that are being forcibly torn down.
     */
    final ArrayList<ProcessRecord> mRemovedProcesses = new ArrayList<ProcessRecord>();

    /**
     * List of running applications, sorted by recent usage.
     * The first entry in the list is the least recently used.
     */
    final ArrayList<ProcessRecord> mLruProcesses = new ArrayList<ProcessRecord>();

    /**
     * Where in mLruProcesses that the processes hosting activities start.
     */
    int mLruProcessActivityStart = 0;

    /**
     * Where in mLruProcesses that the processes hosting services start.
     * This is after (lower index) than mLruProcessesActivityStart.
     */
    int mLruProcessServiceStart = 0;

    /**
     * List of processes that should gc as soon as things are idle.
     */
    final ArrayList<ProcessRecord> mProcessesToGc = new ArrayList<ProcessRecord>();

    /**
     * Processes we want to collect PSS data from.
     */
    final ArrayList<ProcessRecord> mPendingPssProcesses = new ArrayList<ProcessRecord>();

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
     * This is the process holding what we currently consider to be
     * the "home" activity.
     */
    ProcessRecord mHomeProcess;

    /**
     * This is the process holding the activity the user last visited that
     * is in a different process from the one they are currently in.
     */
    ProcessRecord mPreviousProcess;

    /**
     * The time at which the previous process was last visible.
     */
    long mPreviousProcessVisibleTime;

    /**
     * Which uses have been started, so are allowed to run code.
     */
    final SparseArray<UserStartedState> mStartedUsers = new SparseArray<UserStartedState>();

    /**
     * LRU list of history of current users.  Most recently current is at the end.
     */
    final ArrayList<Integer> mUserLru = new ArrayList<Integer>();

    /**
     * Constant array of the users that are currently started.
     */
    int[] mStartedUserArray = new int[] { 0 };

    /**
     * Registered observers of the user switching mechanics.
     */
    final RemoteCallbackList<IUserSwitchObserver> mUserSwitchObservers
            = new RemoteCallbackList<IUserSwitchObserver>();

    /**
     * Currently active user switch.
     */
    Object mCurUserSwitchCallback;

    /**
     * Packages that the user has asked to have run in screen size
     * compatibility mode instead of filling the screen.
     */
    final CompatModePackages mCompatModePackages;

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
     * Keeps track of all IIntentReceivers that have been registered for
     * broadcasts.  Hash keys are the receiver IBinder, hash value is
     * a ReceiverList.
     */
    final HashMap<IBinder, ReceiverList> mRegisteredReceivers =
            new HashMap<IBinder, ReceiverList>();

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

    /**
     * Backup/restore process management
     */
    String mBackupAppName = null;
    BackupRecord mBackupTarget = null;

    final ProviderMap mProviderMap;

    /**
     * List of content providers who have clients waiting for them.  The
     * application is currently being launched and the provider will be
     * removed from this list once it is published.
     */
    final ArrayList<ContentProviderRecord> mLaunchingProviders
            = new ArrayList<ContentProviderRecord>();

    /**
     * File storing persisted {@link #mGrantedUriPermissions}.
     */
    private final AtomicFile mGrantFile;

    /** XML constants used in {@link #mGrantFile} */
    private static final String TAG_URI_GRANTS = "uri-grants";
    private static final String TAG_URI_GRANT = "uri-grant";
    private static final String ATTR_USER_HANDLE = "userHandle";
    private static final String ATTR_SOURCE_USER_ID = "sourceUserId";
    private static final String ATTR_TARGET_USER_ID = "targetUserId";
    private static final String ATTR_SOURCE_PKG = "sourcePkg";
    private static final String ATTR_TARGET_PKG = "targetPkg";
    private static final String ATTR_URI = "uri";
    private static final String ATTR_MODE_FLAGS = "modeFlags";
    private static final String ATTR_CREATED_TIME = "createdTime";
    private static final String ATTR_PREFIX = "prefix";

    /**
     * Global set of specific {@link Uri} permissions that have been granted.
     * This optimized lookup structure maps from {@link UriPermission#targetUid}
     * to {@link UriPermission#uri} to {@link UriPermission}.
     */
    @GuardedBy("this")
    private final SparseArray<ArrayMap<GrantUri, UriPermission>>
            mGrantedUriPermissions = new SparseArray<ArrayMap<GrantUri, UriPermission>>();

    public static class GrantUri {
        public final int sourceUserId;
        public final Uri uri;
        public boolean prefix;

        public GrantUri(int sourceUserId, Uri uri, boolean prefix) {
            this.sourceUserId = sourceUserId;
            this.uri = uri;
            this.prefix = prefix;
        }

        @Override
        public int hashCode() {
            return toString().hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof GrantUri) {
                GrantUri other = (GrantUri) o;
                return uri.equals(other.uri) && (sourceUserId == other.sourceUserId)
                        && prefix == other.prefix;
            }
            return false;
        }

        @Override
        public String toString() {
            String result = Integer.toString(sourceUserId) + " @ " + uri.toString();
            if (prefix) result += " [prefix]";
            return result;
        }

        public String toSafeString() {
            String result = Integer.toString(sourceUserId) + " @ " + uri.toSafeString();
            if (prefix) result += " [prefix]";
            return result;
        }

        public static GrantUri resolve(int defaultSourceUserHandle, Uri uri) {
            return new GrantUri(ContentProvider.getUserIdFromUri(uri, defaultSourceUserHandle),
                    ContentProvider.getUriWithoutUserId(uri), false);
        }
    }

    CoreSettingsObserver mCoreSettingsObserver;

    /**
     * Thread-local storage used to carry caller permissions over through
     * indirect content-provider access.
     */
    private class Identity {
        public int pid;
        public int uid;

        Identity(int _pid, int _uid) {
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
     * Information about and control over application operations
     */
    final AppOpsService mAppOpsService;

    /**
     * Save recent tasks information across reboots.
     */
    final TaskPersister mTaskPersister;

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
    ComponentName mTopComponent;
    String mTopAction = Intent.ACTION_MAIN;
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
    private boolean mSleeping = false;

    /**
     * Set while we are running a voice interaction.  This overrides
     * sleeping while it is active.
     */
    private boolean mRunningVoice = false;

    /**
     * State of external calls telling us if the device is asleep.
     */
    private boolean mWentToSleep = false;

    /**
     * State of external call telling us if the lock screen is shown.
     */
    private boolean mLockScreenShown = false;

    /**
     * Set if we are shutting down the system, similar to sleeping.
     */
    boolean mShuttingDown = false;

    /**
     * Current sequence id for oom_adj computation traversal.
     */
    int mAdjSeq = 0;

    /**
     * Current sequence id for process LRU updating.
     */
    int mLruSeq = 0;

    /**
     * Keep track of the non-cached/empty process we last found, to help
     * determine how to distribute cached/empty processes next time.
     */
    int mNumNonCachedProcs = 0;

    /**
     * Keep track of the number of cached hidden procs, to balance oom adj
     * distribution between those and empty procs.
     */
    int mNumCachedHiddenProcs = 0;

    /**
     * Keep track of the number of service processes we last found, to
     * determine on the next iteration which should be B services.
     */
    int mNumServiceProcs = 0;
    int mNewNumAServiceProcs = 0;
    int mNewNumServiceProcs = 0;

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
     * This is set if we had to do a delayed dexopt of an app before launching
     * it, to increase the ANR timeouts in that case.
     */
    boolean mDidDexOpt;

    /**
     * Set if the systemServer made a call to enterSafeMode.
     */
    boolean mSafeMode;

    String mDebugApp = null;
    boolean mWaitForDebugger = false;
    boolean mDebugTransient = false;
    String mOrigDebugApp = null;
    boolean mOrigWaitForDebugger = false;
    boolean mAlwaysFinishActivities = false;
    IActivityController mController = null;
    String mProfileApp = null;
    ProcessRecord mProfileProc = null;
    String mProfileFile;
    ParcelFileDescriptor mProfileFd;
    int mProfileType = 0;
    boolean mAutoStopProfiler = false;
    String mOpenGlTraceApp = null;

    static class ProcessChangeItem {
        static final int CHANGE_ACTIVITIES = 1<<0;
        static final int CHANGE_PROCESS_STATE = 1<<1;
        int changes;
        int uid;
        int pid;
        int processState;
        boolean foregroundActivities;
    }

    final RemoteCallbackList<IProcessObserver> mProcessObservers
            = new RemoteCallbackList<IProcessObserver>();
    ProcessChangeItem[] mActiveProcessChanges = new ProcessChangeItem[5];

    final ArrayList<ProcessChangeItem> mPendingProcessChanges
            = new ArrayList<ProcessChangeItem>();
    final ArrayList<ProcessChangeItem> mAvailProcessChanges
            = new ArrayList<ProcessChangeItem>();

    /**
     * Runtime CPU use collection thread.  This object's lock is used to
     * protect all related state.
     */
    final Thread mProcessCpuThread;

    /**
     * Used to collect process stats when showing not responding dialog.
     * Protected by mProcessCpuThread.
     */
    final ProcessCpuTracker mProcessCpuTracker = new ProcessCpuTracker(
            MONITOR_THREAD_CPU_USAGE);
    final AtomicLong mLastCpuTime = new AtomicLong(0);
    final AtomicBoolean mProcessCpuMutexFree = new AtomicBoolean(true);

    long mLastWriteTime = 0;

    /**
     * Used to retain an update lock when the foreground activity is in
     * immersive mode.
     */
    final UpdateLock mUpdateLock = new UpdateLock("immersive");

    /**
     * Set to true after the system has finished booting.
     */
    boolean mBooted = false;

    int mProcessLimit = ProcessList.MAX_CACHED_APPS;
    int mProcessLimitOverride = -1;

    WindowManagerService mWindowManager;

    final ActivityThread mSystemThread;

    int mCurrentUserId = 0;
    int[] mCurrentProfileIds = new int[] {UserHandle.USER_OWNER}; // Accessed by ActivityStack

    /**
     * Mapping from each known user ID to the profile group ID it is associated with.
     */
    SparseIntArray mUserProfileGroupIdsSelfLocked = new SparseIntArray();

    private UserManagerService mUserManager;

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

        @Override
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
    static final int CLEAR_DNS_CACHE_MSG = 28;
    static final int UPDATE_HTTP_PROXY_MSG = 29;
    static final int SHOW_COMPAT_MODE_DIALOG_MSG = 30;
    static final int DISPATCH_PROCESSES_CHANGED = 31;
    static final int DISPATCH_PROCESS_DIED = 32;
    static final int REPORT_MEM_USAGE_MSG = 33;
    static final int REPORT_USER_SWITCH_MSG = 34;
    static final int CONTINUE_USER_SWITCH_MSG = 35;
    static final int USER_SWITCH_TIMEOUT_MSG = 36;
    static final int IMMERSIVE_MODE_LOCK_MSG = 37;
    static final int PERSIST_URI_GRANTS_MSG = 38;
    static final int REQUEST_ALL_PSS_MSG = 39;
    static final int START_PROFILES_MSG = 40;
    static final int UPDATE_TIME = 41;
    static final int SYSTEM_USER_START_MSG = 42;
    static final int SYSTEM_USER_CURRENT_MSG = 43;
    static final int ENTER_ANIMATION_COMPLETE_MSG = 44;
    static final int ENABLE_SCREEN_AFTER_BOOT_MSG = 45;
    static final int START_USER_SWITCH_MSG = 46;

    static final int FIRST_ACTIVITY_STACK_MSG = 100;
    static final int FIRST_BROADCAST_QUEUE_MSG = 200;
    static final int FIRST_COMPAT_MODE_MSG = 300;
    static final int FIRST_SUPERVISOR_STACK_MSG = 100;

    AlertDialog mUidAlert;
    CompatModeDialog mCompatModeDialog;
    long mLastMemUsageReportTime = 0;

    private LockToAppRequestDialog mLockToAppRequest;

    /**
     * Flag whether the current user is a "monkey", i.e. whether
     * the UI is driven by a UI automation tool.
     */
    private boolean mUserIsMonkey;

    /** Flag whether the device has a recents UI */
    final boolean mHasRecents;

    final int mThumbnailWidth;
    final int mThumbnailHeight;

    final ServiceThread mHandlerThread;
    final MainHandler mHandler;

    final class MainHandler extends Handler {
        public MainHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case SHOW_ERROR_MSG: {
                HashMap<String, Object> data = (HashMap<String, Object>) msg.obj;
                boolean showBackground = Settings.Secure.getInt(mContext.getContentResolver(),
                        Settings.Secure.ANR_SHOW_BACKGROUND, 0) != 0;
                synchronized (ActivityManagerService.this) {
                    ProcessRecord proc = (ProcessRecord)data.get("app");
                    AppErrorResult res = (AppErrorResult) data.get("result");
                    if (proc != null && proc.crashDialog != null) {
                        Slog.e(TAG, "App already has crash dialog: " + proc);
                        if (res != null) {
                            res.set(0);
                        }
                        return;
                    }
                    boolean isBackground = (UserHandle.getAppId(proc.uid)
                            >= Process.FIRST_APPLICATION_UID
                            && proc.pid != MY_PID);
                    for (int userId : mCurrentProfileIds) {
                        isBackground &= (proc.userId != userId);
                    }
                    if (isBackground && !showBackground) {
                        Slog.w(TAG, "Skipping crash dialog of " + proc + ": background");
                        if (res != null) {
                            res.set(0);
                        }
                        return;
                    }
                    if (mShowDialogs && !mSleeping && !mShuttingDown) {
                        Dialog d = new AppErrorDialog(mContext,
                                ActivityManagerService.this, res, proc);
                        d.show();
                        proc.crashDialog = d;
                    } else {
                        // The device is asleep, so just pretend that the user
                        // saw a crash dialog and hit "force quit".
                        if (res != null) {
                            res.set(0);
                        }
                    }
                }

                ensureBootCompleted();
            } break;
            case SHOW_NOT_RESPONDING_MSG: {
                synchronized (ActivityManagerService.this) {
                    HashMap<String, Object> data = (HashMap<String, Object>) msg.obj;
                    ProcessRecord proc = (ProcessRecord)data.get("app");
                    if (proc != null && proc.anrDialog != null) {
                        Slog.e(TAG, "App already has anr dialog: " + proc);
                        return;
                    }

                    Intent intent = new Intent("android.intent.action.ANR");
                    if (!mProcessesReady) {
                        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                                | Intent.FLAG_RECEIVER_FOREGROUND);
                    }
                    broadcastIntentLocked(null, null, intent,
                            null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                            false, false, MY_PID, Process.SYSTEM_UID, 0 /* TODO: Verify */);

                    if (mShowDialogs) {
                        Dialog d = new AppNotRespondingDialog(ActivityManagerService.this,
                                mContext, proc, (ActivityRecord)data.get("activity"),
                                msg.arg1 != 0);
                        d.show();
                        proc.anrDialog = d;
                    } else {
                        // Just kill the app if there is no dialog to be shown.
                        killAppAtUsersRequest(proc, null);
                    }
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
                    if (mShowDialogs && !mSleeping && !mShuttingDown) {
                        Dialog d = new StrictModeViolationDialog(mContext,
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
            case SERVICE_TIMEOUT_MSG: {
                if (mDidDexOpt) {
                    mDidDexOpt = false;
                    Message nmsg = mHandler.obtainMessage(SERVICE_TIMEOUT_MSG);
                    nmsg.obj = msg.obj;
                    mHandler.sendMessageDelayed(nmsg, ActiveServices.SERVICE_TIMEOUT);
                    return;
                }
                mServices.serviceTimeout((ProcessRecord)msg.obj);
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
            case CLEAR_DNS_CACHE_MSG: {
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
            case UPDATE_HTTP_PROXY_MSG: {
                ProxyInfo proxy = (ProxyInfo)msg.obj;
                String host = "";
                String port = "";
                String exclList = "";
                Uri pacFileUrl = Uri.EMPTY;
                if (proxy != null) {
                    host = proxy.getHost();
                    port = Integer.toString(proxy.getPort());
                    exclList = proxy.getExclusionListAsString();
                    pacFileUrl = proxy.getPacFileUrl();
                }
                synchronized (ActivityManagerService.this) {
                    for (int i = mLruProcesses.size() - 1 ; i >= 0 ; i--) {
                        ProcessRecord r = mLruProcesses.get(i);
                        if (r.thread != null) {
                            try {
                                r.thread.setHttpProxy(host, port, exclList, pacFileUrl);
                            } catch (RemoteException ex) {
                                Slog.w(TAG, "Failed to update http proxy for: " +
                                        r.info.processName);
                            }
                        }
                    }
                }
            } break;
            case SHOW_UID_ERROR_MSG: {
                String title = "System UIDs Inconsistent";
                String text = "UIDs on the system are inconsistent, you need to wipe your"
                        + " data partition or your device will be unstable.";
                Log.e(TAG, title + ": " + text);
                if (mShowDialogs) {
                    // XXX This is a temporary dialog, no need to localize.
                    AlertDialog d = new BaseErrorDialog(mContext);
                    d.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
                    d.setCancelable(false);
                    d.setTitle(title);
                    d.setMessage(text);
                    d.setButton(DialogInterface.BUTTON_POSITIVE, "I'm Feeling Lucky",
                            mHandler.obtainMessage(IM_FEELING_LUCKY_MSG));
                    mUidAlert = d;
                    d.show();
                }
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
                    mStackSupervisor.doPendingActivityLaunchesLocked(true);
                }
            } break;
            case KILL_APPLICATION_MSG: {
                synchronized (ActivityManagerService.this) {
                    int appid = msg.arg1;
                    boolean restart = (msg.arg2 == 1);
                    Bundle bundle = (Bundle)msg.obj;
                    String pkg = bundle.getString("pkg");
                    String reason = bundle.getString("reason");
                    forceStopPackageLocked(pkg, appid, restart, false, true, false,
                            false, UserHandle.USER_ALL, reason);
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
                    notification.color = mContext.getResources().getColor(
                            com.android.internal.R.color.system_notification_accent_color);
                    notification.setLatestEventInfo(context, text,
                            mContext.getText(R.string.heavy_weight_notification_detail),
                            PendingIntent.getActivityAsUser(mContext, 0, root.intent,
                                    PendingIntent.FLAG_CANCEL_CURRENT, null,
                                    new UserHandle(root.userId)));

                    try {
                        int[] outId = new int[1];
                        inm.enqueueNotificationWithTag("android", "android", null,
                                R.string.heavy_weight_notification,
                                notification, outId, root.userId);
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
                    inm.cancelNotificationWithTag("android", null,
                            R.string.heavy_weight_notification,  msg.arg1);
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
            case DISPATCH_PROCESSES_CHANGED: {
                dispatchProcessesChanged();
                break;
            }
            case DISPATCH_PROCESS_DIED: {
                final int pid = msg.arg1;
                final int uid = msg.arg2;
                dispatchProcessDied(pid, uid);
                break;
            }
            case REPORT_MEM_USAGE_MSG: {
                final ArrayList<ProcessMemInfo> memInfos = (ArrayList<ProcessMemInfo>)msg.obj;
                Thread thread = new Thread() {
                    @Override public void run() {
                        final SparseArray<ProcessMemInfo> infoMap
                                = new SparseArray<ProcessMemInfo>(memInfos.size());
                        for (int i=0, N=memInfos.size(); i<N; i++) {
                            ProcessMemInfo mi = memInfos.get(i);
                            infoMap.put(mi.pid, mi);
                        }
                        updateCpuStatsNow();
                        synchronized (mProcessCpuThread) {
                            final int N = mProcessCpuTracker.countStats();
                            for (int i=0; i<N; i++) {
                                ProcessCpuTracker.Stats st = mProcessCpuTracker.getStats(i);
                                if (st.vsize > 0) {
                                    long pss = Debug.getPss(st.pid, null);
                                    if (pss > 0) {
                                        if (infoMap.indexOfKey(st.pid) < 0) {
                                            ProcessMemInfo mi = new ProcessMemInfo(st.name, st.pid,
                                                    ProcessList.NATIVE_ADJ, -1, "native", null);
                                            mi.pss = pss;
                                            memInfos.add(mi);
                                        }
                                    }
                                }
                            }
                        }

                        long totalPss = 0;
                        for (int i=0, N=memInfos.size(); i<N; i++) {
                            ProcessMemInfo mi = memInfos.get(i);
                            if (mi.pss == 0) {
                                mi.pss = Debug.getPss(mi.pid, null);
                            }
                            totalPss += mi.pss;
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

                        StringBuilder logBuilder = new StringBuilder(1024);
                        logBuilder.append("Low on memory:\n");

                        boolean firstLine = true;
                        int lastOomAdj = Integer.MIN_VALUE;
                        for (int i=0, N=memInfos.size(); i<N; i++) {
                            ProcessMemInfo mi = memInfos.get(i);

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

                            logBuilder.append("  ");
                            logBuilder.append(ProcessList.makeOomAdjString(mi.oomAdj));
                            logBuilder.append(' ');
                            logBuilder.append(ProcessList.makeProcStateString(mi.procState));
                            logBuilder.append(' ');
                            ProcessList.appendRamKb(logBuilder, mi.pss);
                            logBuilder.append(" kB: ");
                            logBuilder.append(mi.name);
                            logBuilder.append(" (");
                            logBuilder.append(mi.pid);
                            logBuilder.append(") ");
                            logBuilder.append(mi.adjType);
                            logBuilder.append('\n');
                            if (mi.adjReason != null) {
                                logBuilder.append("                      ");
                                logBuilder.append(mi.adjReason);
                                logBuilder.append('\n');
                            }
                        }

                        logBuilder.append("           ");
                        ProcessList.appendRamKb(logBuilder, totalPss);
                        logBuilder.append(" kB: TOTAL\n");

                        long[] infos = new long[Debug.MEMINFO_COUNT];
                        Debug.getMemInfo(infos);
                        logBuilder.append("  MemInfo: ");
                        logBuilder.append(infos[Debug.MEMINFO_SLAB]).append(" kB slab, ");
                        logBuilder.append(infos[Debug.MEMINFO_SHMEM]).append(" kB shmem, ");
                        logBuilder.append(infos[Debug.MEMINFO_BUFFERS]).append(" kB buffers, ");
                        logBuilder.append(infos[Debug.MEMINFO_CACHED]).append(" kB cached, ");
                        logBuilder.append(infos[Debug.MEMINFO_FREE]).append(" kB free\n");
                        if (infos[Debug.MEMINFO_ZRAM_TOTAL] != 0) {
                            logBuilder.append("  ZRAM: ");
                            logBuilder.append(infos[Debug.MEMINFO_ZRAM_TOTAL]);
                            logBuilder.append(" kB RAM, ");
                            logBuilder.append(infos[Debug.MEMINFO_SWAP_TOTAL]);
                            logBuilder.append(" kB swap total, ");
                            logBuilder.append(infos[Debug.MEMINFO_SWAP_FREE]);
                            logBuilder.append(" kB swap free\n");
                        }
                        Slog.i(TAG, logBuilder.toString());

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
                        dropBuilder.append(stack);
                        dropBuilder.append('\n');
                        dropBuilder.append('\n');
                        dropBuilder.append(logBuilder);
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
                            dumpProcessesLocked(null, catPw, emptyArgs, 0, false, null);
                            catPw.println();
                            mServices.dumpServicesLocked(null, catPw, emptyArgs, 0,
                                    false, false, null);
                            catPw.println();
                            dumpActivitiesLocked(null, catPw, emptyArgs, 0, false, false, null);
                            catPw.flush();
                        }
                        dropBuilder.append(catSw.toString());
                        addErrorToDropBox("lowmem", null, "system_server", null,
                                null, tag.toString(), dropBuilder.toString(), null, null);
                        //Slog.i(TAG, "Sent to dropbox:");
                        //Slog.i(TAG, dropBuilder.toString());
                        synchronized (ActivityManagerService.this) {
                            long now = SystemClock.uptimeMillis();
                            if (mLastMemUsageReportTime < now) {
                                mLastMemUsageReportTime = now;
                            }
                        }
                    }
                };
                thread.start();
                break;
            }
            case START_USER_SWITCH_MSG: {
                showUserSwitchDialog(msg.arg1, (String) msg.obj);
                break;
            }
            case REPORT_USER_SWITCH_MSG: {
                dispatchUserSwitch((UserStartedState) msg.obj, msg.arg1, msg.arg2);
                break;
            }
            case CONTINUE_USER_SWITCH_MSG: {
                continueUserSwitch((UserStartedState) msg.obj, msg.arg1, msg.arg2);
                break;
            }
            case USER_SWITCH_TIMEOUT_MSG: {
                timeoutUserSwitch((UserStartedState) msg.obj, msg.arg1, msg.arg2);
                break;
            }
            case IMMERSIVE_MODE_LOCK_MSG: {
                final boolean nextState = (msg.arg1 != 0);
                if (mUpdateLock.isHeld() != nextState) {
                    if (DEBUG_IMMERSIVE) {
                        final ActivityRecord r = (ActivityRecord) msg.obj;
                        Slog.d(TAG, "Applying new update lock state '" + nextState + "' for " + r);
                    }
                    if (nextState) {
                        mUpdateLock.acquire();
                    } else {
                        mUpdateLock.release();
                    }
                }
                break;
            }
            case PERSIST_URI_GRANTS_MSG: {
                writeGrantedUriPermissions();
                break;
            }
            case REQUEST_ALL_PSS_MSG: {
                requestPssAllProcsLocked(SystemClock.uptimeMillis(), true, false);
                break;
            }
            case START_PROFILES_MSG: {
                synchronized (ActivityManagerService.this) {
                    startProfilesLocked();
                }
                break;
            }
            case UPDATE_TIME: {
                synchronized (ActivityManagerService.this) {
                    for (int i = mLruProcesses.size() - 1 ; i >= 0 ; i--) {
                        ProcessRecord r = mLruProcesses.get(i);
                        if (r.thread != null) {
                            try {
                                r.thread.updateTimePrefs(msg.arg1 == 0 ? false : true);
                            } catch (RemoteException ex) {
                                Slog.w(TAG, "Failed to update preferences for: " + r.info.processName);
                            }
                        }
                    }
                }
                break;
            }
            case SYSTEM_USER_START_MSG: {
                mBatteryStatsService.noteEvent(BatteryStats.HistoryItem.EVENT_USER_RUNNING_START,
                        Integer.toString(msg.arg1), msg.arg1);
                mSystemServiceManager.startUser(msg.arg1);
                break;
            }
            case SYSTEM_USER_CURRENT_MSG: {
                mBatteryStatsService.noteEvent(
                        BatteryStats.HistoryItem.EVENT_USER_FOREGROUND_FINISH,
                        Integer.toString(msg.arg2), msg.arg2);
                mBatteryStatsService.noteEvent(
                        BatteryStats.HistoryItem.EVENT_USER_FOREGROUND_START,
                        Integer.toString(msg.arg1), msg.arg1);
                mSystemServiceManager.switchUser(msg.arg1);
                mLockToAppRequest.clearPrompt();
                break;
            }
            case ENTER_ANIMATION_COMPLETE_MSG: {
                synchronized (ActivityManagerService.this) {
                    ActivityRecord r = ActivityRecord.forToken((IBinder) msg.obj);
                    if (r != null && r.app != null && r.app.thread != null) {
                        try {
                            r.app.thread.scheduleEnterAnimationComplete(r.appToken);
                        } catch (RemoteException e) {
                        }
                    }
                }
                break;
            }
            case ENABLE_SCREEN_AFTER_BOOT_MSG: {
                enableScreenAfterBoot();
                break;
            }
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
                    synchronized (mProcessCpuThread) {
                        final int N = mProcessCpuTracker.countStats();
                        for (int j=0; j<N; j++) {
                            ProcessCpuTracker.Stats st = mProcessCpuTracker.getStats(j);
                            if (st.vsize <= 0 || st.uid >= Process.FIRST_APPLICATION_UID) {
                                // This is definitely an application process; skip it.
                                continue;
                            }
                            synchronized (mPidsSelfLocked) {
                                if (mPidsSelfLocked.indexOfKey(st.pid) >= 0) {
                                    // This is one of our own processes; skip it.
                                    continue;
                                }
                            }
                            nativeTotalPss += Debug.getPss(st.pid, null);
                        }
                    }
                    memInfo.readMemInfo();
                    synchronized (this) {
                        if (DEBUG_PSS) Slog.d(TAG, "Collected native and kernel memory in "
                                + (SystemClock.uptimeMillis()-start) + "ms");
                        mProcessStats.addSysMemUsageLocked(memInfo.getCachedSizeKb(),
                                memInfo.getFreeSizeKb(), memInfo.getZramTotalSizeKb(),
                                memInfo.getBuffersSizeKb()+memInfo.getShmemSizeKb()
                                        +memInfo.getSlabSizeKb(),
                                nativeTotalPss);
                    }
                }

                int i=0, num=0;
                long[] tmp = new long[1];
                do {
                    ProcessRecord proc;
                    int procState;
                    int pid;
                    synchronized (ActivityManagerService.this) {
                        if (i >= mPendingPssProcesses.size()) {
                            if (DEBUG_PSS) Slog.d(TAG, "Collected PSS of " + num + " of " + i
                                    + " processes in " + (SystemClock.uptimeMillis()-start) + "ms");
                            mPendingPssProcesses.clear();
                            return;
                        }
                        proc = mPendingPssProcesses.get(i);
                        procState = proc.pssProcState;
                        if (proc.thread != null && procState == proc.setProcState) {
                            pid = proc.pid;
                        } else {
                            proc = null;
                            pid = 0;
                        }
                        i++;
                    }
                    if (proc != null) {
                        long pss = Debug.getPss(pid, tmp);
                        synchronized (ActivityManagerService.this) {
                            if (proc.thread != null && proc.setProcState == procState
                                    && proc.pid == pid) {
                                num++;
                                proc.lastPssTime = SystemClock.uptimeMillis();
                                proc.baseProcessTracker.addPss(pss, tmp[0], true, proc.pkgList);
                                if (DEBUG_PSS) Slog.d(TAG, "PSS of " + proc.toShortString()
                                        + ": " + pss + " lastPss=" + proc.lastPss
                                        + " state=" + ProcessList.makeProcStateString(procState));
                                if (proc.initialIdlePss == 0) {
                                    proc.initialIdlePss = pss;
                                }
                                proc.lastPss = pss;
                                if (procState >= ActivityManager.PROCESS_STATE_HOME) {
                                    proc.lastCachedPss = pss;
                                }
                            }
                        }
                    }
                } while (true);
            }
            }
        }
    };

    /**
     * Monitor for package changes and update our internal state.
     */
    private final PackageMonitor mPackageMonitor = new PackageMonitor() {
        @Override
        public void onPackageRemoved(String packageName, int uid) {
            // Remove all tasks with activities in the specified package from the list of recent tasks
            synchronized (ActivityManagerService.this) {
                for (int i = mRecentTasks.size() - 1; i >= 0; i--) {
                    TaskRecord tr = mRecentTasks.get(i);
                    ComponentName cn = tr.intent.getComponent();
                    if (cn != null && cn.getPackageName().equals(packageName)) {
                        // If the package name matches, remove the task and kill the process
                        removeTaskByIdLocked(tr.taskId, ActivityManager.REMOVE_TASK_KILL_PROCESS);
                    }
                }
            }
        }

        @Override
        public boolean onPackageChanged(String packageName, int uid, String[] components) {
            onPackageModified(packageName);
            return true;
        }

        @Override
        public void onPackageModified(String packageName) {
            final PackageManager pm = mContext.getPackageManager();
            final ArrayList<Pair<Intent, Integer>> recentTaskIntents =
                    new ArrayList<Pair<Intent, Integer>>();
            final ArrayList<Integer> tasksToRemove = new ArrayList<Integer>();
            // Copy the list of recent tasks so that we don't hold onto the lock on
            // ActivityManagerService for long periods while checking if components exist.
            synchronized (ActivityManagerService.this) {
                for (int i = mRecentTasks.size() - 1; i >= 0; i--) {
                    TaskRecord tr = mRecentTasks.get(i);
                    recentTaskIntents.add(new Pair<Intent, Integer>(tr.intent, tr.taskId));
                }
            }
            // Check the recent tasks and filter out all tasks with components that no longer exist.
            Intent tmpI = new Intent();
            for (int i = recentTaskIntents.size() - 1; i >= 0; i--) {
                Pair<Intent, Integer> p = recentTaskIntents.get(i);
                ComponentName cn = p.first.getComponent();
                if (cn != null && cn.getPackageName().equals(packageName)) {
                    try {
                        // Add the task to the list to remove if the component no longer exists
                        tmpI.setComponent(cn);
                        if (pm.queryIntentActivities(tmpI, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) {
                            tasksToRemove.add(p.second);
                        }
                    } catch (Exception e) {}
                }
            }
            // Prune all the tasks with removed components from the list of recent tasks
            synchronized (ActivityManagerService.this) {
                for (int i = tasksToRemove.size() - 1; i >= 0; i--) {
                    // Remove the task but don't kill the process (since other components in that
                    // package may still be running and in the background)
                    removeTaskByIdLocked(tasksToRemove.get(i), 0);
                }
            }
        }

        @Override
        public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
            // Force stop the specified packages
            if (packages != null) {
                for (String pkg : packages) {
                    synchronized (ActivityManagerService.this) {
                        if (forceStopPackageLocked(pkg, -1, false, false, false, false, false, 0,
                                "finished booting")) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    };

    public void setSystemProcess() {
        try {
            ServiceManager.addService(Context.ACTIVITY_SERVICE, this, true);
            ServiceManager.addService(ProcessStats.SERVICE_NAME, mProcessStats);
            ServiceManager.addService("meminfo", new MemBinder(this));
            ServiceManager.addService("gfxinfo", new GraphicsBinder(this));
            ServiceManager.addService("dbinfo", new DbBinder(this));
            if (MONITOR_CPU_USAGE) {
                ServiceManager.addService("cpuinfo", new CpuBinder(this));
            }
            ServiceManager.addService("permission", new PermissionController(this));

            ApplicationInfo info = mContext.getPackageManager().getApplicationInfo(
                    "android", STOCK_PM_FLAGS);
            mSystemThread.installSystemApplicationInfo(info, getClass().getClassLoader());

            synchronized (this) {
                ProcessRecord app = newProcessRecordLocked(info, info.processName, false, 0);
                app.persistent = true;
                app.pid = MY_PID;
                app.maxAdj = ProcessList.SYSTEM_ADJ;
                app.makeActive(mSystemThread.getApplicationThread(), mProcessStats);
                mProcessNames.put(app.processName, app.uid, app);
                synchronized (mPidsSelfLocked) {
                    mPidsSelfLocked.put(app.pid, app);
                }
                updateLruProcessLocked(app, false, null);
                updateOomAdjLocked();
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(
                    "Unable to find android system package", e);
        }
    }

    public void setWindowManager(WindowManagerService wm) {
        mWindowManager = wm;
        mStackSupervisor.setWindowManager(wm);
    }

    public void setUsageStatsManager(UsageStatsManagerInternal usageStatsManager) {
        mUsageStatsService = usageStatsManager;
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
        MemBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (mActivityManagerService.checkCallingPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump meminfo from from pid="
                        + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                        + " without permission " + android.Manifest.permission.DUMP);
                return;
            }

            mActivityManagerService.dumpApplicationMemoryUsage(fd, pw, "  ", args, false, null);
        }
    }

    static class GraphicsBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        GraphicsBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (mActivityManagerService.checkCallingPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump gfxinfo from from pid="
                        + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                        + " without permission " + android.Manifest.permission.DUMP);
                return;
            }

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
            if (mActivityManagerService.checkCallingPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump dbinfo from from pid="
                        + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                        + " without permission " + android.Manifest.permission.DUMP);
                return;
            }

            mActivityManagerService.dumpDbInfo(fd, pw, args);
        }
    }

    static class CpuBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        CpuBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (mActivityManagerService.checkCallingPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump cpuinfo from from pid="
                        + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                        + " without permission " + android.Manifest.permission.DUMP);
                return;
            }

            synchronized (mActivityManagerService.mProcessCpuThread) {
                pw.print(mActivityManagerService.mProcessCpuTracker.printCurrentLoad());
                pw.print(mActivityManagerService.mProcessCpuTracker.printCurrentState(
                        SystemClock.uptimeMillis()));
            }
        }
    }

    public static final class Lifecycle extends SystemService {
        private final ActivityManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            mService = new ActivityManagerService(context);
        }

        @Override
        public void onStart() {
            mService.start();
        }

        public ActivityManagerService getService() {
            return mService;
        }
    }

    // Note: This method is invoked on the main thread but may need to attach various
    // handlers to other threads.  So take care to be explicit about the looper.
    public ActivityManagerService(Context systemContext) {
        mContext = systemContext;
        mFactoryTest = FactoryTest.getMode();
        mSystemThread = ActivityThread.currentActivityThread();

        Slog.i(TAG, "Memory class: " + ActivityManager.staticGetMemoryClass());

        mHandlerThread = new ServiceThread(TAG,
                android.os.Process.THREAD_PRIORITY_FOREGROUND, false /*allowIo*/);
        mHandlerThread.start();
        mHandler = new MainHandler(mHandlerThread.getLooper());

        mFgBroadcastQueue = new BroadcastQueue(this, mHandler,
                "foreground", BROADCAST_FG_TIMEOUT, false);
        mBgBroadcastQueue = new BroadcastQueue(this, mHandler,
                "background", BROADCAST_BG_TIMEOUT, true);
        mBroadcastQueues[0] = mFgBroadcastQueue;
        mBroadcastQueues[1] = mBgBroadcastQueue;

        mServices = new ActiveServices(this);
        mProviderMap = new ProviderMap(this);

        // TODO: Move creation of battery stats service outside of activity manager service.
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        systemDir.mkdirs();
        mBatteryStatsService = new BatteryStatsService(systemDir, mHandler);
        mBatteryStatsService.getActiveStatistics().readLocked();
        mBatteryStatsService.getActiveStatistics().writeAsyncLocked();
        mOnBattery = DEBUG_POWER ? true
                : mBatteryStatsService.getActiveStatistics().getIsOnBattery();
        mBatteryStatsService.getActiveStatistics().setCallback(this);

        mProcessStats = new ProcessStatsService(this, new File(systemDir, "procstats"));

        mAppOpsService = new AppOpsService(new File(systemDir, "appops.xml"), mHandler);

        mGrantFile = new AtomicFile(new File(systemDir, "urigrants.xml"));

        // User 0 is the first and only user that runs at boot.
        mStartedUsers.put(0, new UserStartedState(new UserHandle(0), true));
        mUserLru.add(Integer.valueOf(0));
        updateStartedUserArrayLocked();

        GL_ES_VERSION = SystemProperties.getInt("ro.opengles.version",
            ConfigurationInfo.GL_ES_VERSION_UNDEFINED);

        mConfiguration.setToDefaults();
        mConfiguration.setLocale(Locale.getDefault());

        mConfigurationSeq = mConfiguration.seq = 1;
        mProcessCpuTracker.init();

        final Resources res = mContext.getResources();
        mHasRecents = res.getBoolean(com.android.internal.R.bool.config_hasRecents);
        mThumbnailWidth = res.getDimensionPixelSize(com.android.internal.R.dimen.thumbnail_width);
        mThumbnailHeight = res.getDimensionPixelSize(com.android.internal.R.dimen.thumbnail_height);

        mCompatModePackages = new CompatModePackages(this, systemDir, mHandler);
        mIntentFirewall = new IntentFirewall(new IntentFirewallInterface(), mHandler);
        mStackSupervisor = new ActivityStackSupervisor(this);
        mTaskPersister = new TaskPersister(systemDir, mStackSupervisor);

        mProcessCpuThread = new Thread("CpuTracker") {
            @Override
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

        mLockToAppRequest = new LockToAppRequestDialog(mContext, this);

        Watchdog.getInstance().addMonitor(this);
        Watchdog.getInstance().addThread(mHandler);
    }

    public void setSystemServiceManager(SystemServiceManager mgr) {
        mSystemServiceManager = mgr;
    }

    private void start() {
        Process.removeAllProcessGroups();
        mProcessCpuThread.start();

        mBatteryStatsService.publish(mContext);
        mAppOpsService.publish(mContext);
        Slog.d("AppOps", "AppOpsService published");
        LocalServices.addService(ActivityManagerInternal.class, new LocalService());
    }

    public void initPowerManagement() {
        mStackSupervisor.initPowerManagement();
        mBatteryStatsService.initPowerManagement();
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == SYSPROPS_TRANSACTION) {
            // We need to tell all apps about the system property change.
            ArrayList<IBinder> procs = new ArrayList<IBinder>();
            synchronized(this) {
                final int NP = mProcessNames.getMap().size();
                for (int ip=0; ip<NP; ip++) {
                    SparseArray<ProcessRecord> apps = mProcessNames.getMap().valueAt(ip);
                    final int NA = apps.size();
                    for (int ia=0; ia<NA; ia++) {
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
                    procs.get(i).transact(IBinder.SYSPROPS_TRANSACTION, data2, null, 0);
                } catch (RemoteException e) {
                }
                data2.recycle();
            }
        }
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            // The activity manager only throws security exceptions, so let's
            // log all others.
            if (!(e instanceof SecurityException)) {
                Slog.wtf(TAG, "Activity Manager Crash", e);
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
        synchronized (mProcessCpuThread) {
            mProcessCpuMutexFree.set(false);
            final long now = SystemClock.uptimeMillis();
            boolean haveNewCpuStats = false;

            if (MONITOR_CPU_USAGE &&
                    mLastCpuTime.get() < (now-MONITOR_CPU_MIN_TIME)) {
                mLastCpuTime.set(now);
                haveNewCpuStats = true;
                mProcessCpuTracker.update();
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

            long[] cpuSpeedTimes = mProcessCpuTracker.getLastCpuSpeedTimes();
            final BatteryStatsImpl bstats = mBatteryStatsService.getActiveStatistics();
            synchronized(bstats) {
                synchronized(mPidsSelfLocked) {
                    if (haveNewCpuStats) {
                        if (mOnBattery) {
                            int perc = bstats.startAddingCpuLocked();
                            int totalUTime = 0;
                            int totalSTime = 0;
                            final int N = mProcessCpuTracker.countStats();
                            for (int i=0; i<N; i++) {
                                ProcessCpuTracker.Stats st = mProcessCpuTracker.getStats(i);
                                if (!st.working) {
                                    continue;
                                }
                                ProcessRecord pr = mPidsSelfLocked.get(st.pid);
                                int otherUTime = (st.rel_utime*perc)/100;
                                int otherSTime = (st.rel_stime*perc)/100;
                                totalUTime += otherUTime;
                                totalSTime += otherSTime;
                                if (pr != null) {
                                    BatteryStatsImpl.Uid.Proc ps = pr.curProcBatteryStats;
                                    if (ps == null || !ps.isActive()) {
                                        pr.curProcBatteryStats = ps = bstats.getProcessStatsLocked(
                                                pr.info.uid, pr.processName);
                                    }
                                    ps.addCpuTimeLocked(st.rel_utime-otherUTime,
                                            st.rel_stime-otherSTime);
                                    ps.addSpeedStepTimes(cpuSpeedTimes);
                                    pr.curCpuTime += (st.rel_utime+st.rel_stime) * 10;
                                } else {
                                    BatteryStatsImpl.Uid.Proc ps = st.batteryStats;
                                    if (ps == null || !ps.isActive()) {
                                        st.batteryStats = ps = bstats.getProcessStatsLocked(
                                                bstats.mapUid(st.uid), st.name);
                                    }
                                    ps.addCpuTimeLocked(st.rel_utime-otherUTime,
                                            st.rel_stime-otherSTime);
                                    ps.addSpeedStepTimes(cpuSpeedTimes);
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
            if (DEBUG_FOCUS) Slog.d(TAG, "setFocusedActivityLocked: r=" + r);
            mFocusedActivity = r;
            if (r.task != null && r.task.voiceInteractor != null) {
                startRunningVoiceLocked();
            } else {
                finishRunningVoiceLocked();
            }
            mStackSupervisor.setFocusedStack(r);
            if (r != null) {
                mWindowManager.setFocusedApp(r.appToken, true);
            }
            applyUpdateLockStateLocked(r);
        }
    }

    final void clearFocusedActivity(ActivityRecord r) {
        if (mFocusedActivity == r) {
            mFocusedActivity = null;
        }
    }

    @Override
    public void setFocusedStack(int stackId) {
        if (DEBUG_FOCUS) Slog.d(TAG, "setFocusedStack: stackId=" + stackId);
        synchronized (ActivityManagerService.this) {
            ActivityStack stack = mStackSupervisor.getStack(stackId);
            if (stack != null) {
                ActivityRecord r = stack.topRunningActivityLocked(null);
                if (r != null) {
                    setFocusedActivityLocked(r);
                }
            }
        }
    }

    @Override
    public void notifyActivityDrawn(IBinder token) {
        if (DEBUG_VISBILITY) Slog.d(TAG, "notifyActivityDrawn: token=" + token);
        synchronized (this) {
            ActivityRecord r= mStackSupervisor.isInAnyStackLocked(token);
            if (r != null) {
                r.task.stack.notifyActivityDrawnLocked(r);
            }
        }
    }

    final void applyUpdateLockStateLocked(ActivityRecord r) {
        // Modifications to the UpdateLock state are done on our handler, outside
        // the activity manager's locks.  The new state is determined based on the
        // state *now* of the relevant activity record.  The object is passed to
        // the handler solely for logging detail, not to be consulted/modified.
        final boolean nextState = r != null && r.immersive;
        mHandler.sendMessage(
                mHandler.obtainMessage(IMMERSIVE_MODE_LOCK_MSG, (nextState) ? 1 : 0, 0, r));
    }

    final void showAskCompatModeDialogLocked(ActivityRecord r) {
        Message msg = Message.obtain();
        msg.what = SHOW_COMPAT_MODE_DIALOG_MSG;
        msg.obj = r.task.askedCompatMode ? null : r;
        mHandler.sendMessage(msg);
    }

    private final int updateLruProcessInternalLocked(ProcessRecord app, long now, int index,
            String what, Object obj, ProcessRecord srcApp) {
        app.lastActivityTime = now;

        if (app.activities.size() > 0) {
            // Don't want to touch dependent processes that are hosting activities.
            return index;
        }

        int lrui = mLruProcesses.lastIndexOf(app);
        if (lrui < 0) {
            Slog.wtf(TAG, "Adding dependent process " + app + " not on LRU list: "
                    + what + " " + obj + " from " + srcApp);
            return index;
        }

        if (lrui >= index) {
            // Don't want to cause this to move dependent processes *back* in the
            // list as if they were less frequently used.
            return index;
        }

        if (lrui >= mLruProcessActivityStart) {
            // Don't want to touch dependent processes that are hosting activities.
            return index;
        }

        mLruProcesses.remove(lrui);
        if (index > 0) {
            index--;
        }
        if (DEBUG_LRU) Slog.d(TAG, "Moving dep from " + lrui + " to " + index
                + " in LRU list: " + app);
        mLruProcesses.add(index, app);
        return index;
    }

    final void removeLruProcessLocked(ProcessRecord app) {
        int lrui = mLruProcesses.lastIndexOf(app);
        if (lrui >= 0) {
            if (lrui <= mLruProcessActivityStart) {
                mLruProcessActivityStart--;
            }
            if (lrui <= mLruProcessServiceStart) {
                mLruProcessServiceStart--;
            }
            mLruProcesses.remove(lrui);
        }
    }

    final void updateLruProcessLocked(ProcessRecord app, boolean activityChange,
            ProcessRecord client) {
        final boolean hasActivity = app.activities.size() > 0 || app.hasClientActivities
                || app.treatLikeActivity;
        final boolean hasService = false; // not impl yet. app.services.size() > 0;
        if (!activityChange && hasActivity) {
            // The process has activities, so we are only allowing activity-based adjustments
            // to move it.  It should be kept in the front of the list with other
            // processes that have activities, and we don't want those to change their
            // order except due to activity operations.
            return;
        }

        mLruSeq++;
        final long now = SystemClock.uptimeMillis();
        app.lastActivityTime = now;

        // First a quick reject: if the app is already at the position we will
        // put it, then there is nothing to do.
        if (hasActivity) {
            final int N = mLruProcesses.size();
            if (N > 0 && mLruProcesses.get(N-1) == app) {
                if (DEBUG_LRU) Slog.d(TAG, "Not moving, already top activity: " + app);
                return;
            }
        } else {
            if (mLruProcessServiceStart > 0
                    && mLruProcesses.get(mLruProcessServiceStart-1) == app) {
                if (DEBUG_LRU) Slog.d(TAG, "Not moving, already top other: " + app);
                return;
            }
        }

        int lrui = mLruProcesses.lastIndexOf(app);

        if (app.persistent && lrui >= 0) {
            // We don't care about the position of persistent processes, as long as
            // they are in the list.
            if (DEBUG_LRU) Slog.d(TAG, "Not moving, persistent: " + app);
            return;
        }

        /* In progress: compute new position first, so we can avoid doing work
           if the process is not actually going to move.  Not yet working.
        int addIndex;
        int nextIndex;
        boolean inActivity = false, inService = false;
        if (hasActivity) {
            // Process has activities, put it at the very tipsy-top.
            addIndex = mLruProcesses.size();
            nextIndex = mLruProcessServiceStart;
            inActivity = true;
        } else if (hasService) {
            // Process has services, put it at the top of the service list.
            addIndex = mLruProcessActivityStart;
            nextIndex = mLruProcessServiceStart;
            inActivity = true;
            inService = true;
        } else  {
            // Process not otherwise of interest, it goes to the top of the non-service area.
            addIndex = mLruProcessServiceStart;
            if (client != null) {
                int clientIndex = mLruProcesses.lastIndexOf(client);
                if (clientIndex < 0) Slog.d(TAG, "Unknown client " + client + " when updating "
                        + app);
                if (clientIndex >= 0 && addIndex > clientIndex) {
                    addIndex = clientIndex;
                }
            }
            nextIndex = addIndex > 0 ? addIndex-1 : addIndex;
        }

        Slog.d(TAG, "Update LRU at " + lrui + " to " + addIndex + " (act="
                + mLruProcessActivityStart + "): " + app);
        */

        if (lrui >= 0) {
            if (lrui < mLruProcessActivityStart) {
                mLruProcessActivityStart--;
            }
            if (lrui < mLruProcessServiceStart) {
                mLruProcessServiceStart--;
            }
            /*
            if (addIndex > lrui) {
                addIndex--;
            }
            if (nextIndex > lrui) {
                nextIndex--;
            }
            */
            mLruProcesses.remove(lrui);
        }

        /*
        mLruProcesses.add(addIndex, app);
        if (inActivity) {
            mLruProcessActivityStart++;
        }
        if (inService) {
            mLruProcessActivityStart++;
        }
        */

        int nextIndex;
        if (hasActivity) {
            final int N = mLruProcesses.size();
            if (app.activities.size() == 0 && mLruProcessActivityStart < (N-1)) {
                // Process doesn't have activities, but has clients with
                // activities...  move it up, but one below the top (the top
                // should always have a real activity).
                if (DEBUG_LRU) Slog.d(TAG, "Adding to second-top of LRU activity list: " + app);
                mLruProcesses.add(N-1, app);
                // To keep it from spamming the LRU list (by making a bunch of clients),
                // we will push down any other entries owned by the app.
                final int uid = app.info.uid;
                for (int i=N-2; i>mLruProcessActivityStart; i--) {
                    ProcessRecord subProc = mLruProcesses.get(i);
                    if (subProc.info.uid == uid) {
                        // We want to push this one down the list.  If the process after
                        // it is for the same uid, however, don't do so, because we don't
                        // want them internally to be re-ordered.
                        if (mLruProcesses.get(i-1).info.uid != uid) {
                            if (DEBUG_LRU) Slog.d(TAG, "Pushing uid " + uid + " swapping at " + i
                                    + ": " + mLruProcesses.get(i) + " : " + mLruProcesses.get(i-1));
                            ProcessRecord tmp = mLruProcesses.get(i);
                            mLruProcesses.set(i, mLruProcesses.get(i-1));
                            mLruProcesses.set(i-1, tmp);
                            i--;
                        }
                    } else {
                        // A gap, we can stop here.
                        break;
                    }
                }
            } else {
                // Process has activities, put it at the very tipsy-top.
                if (DEBUG_LRU) Slog.d(TAG, "Adding to top of LRU activity list: " + app);
                mLruProcesses.add(app);
            }
            nextIndex = mLruProcessServiceStart;
        } else if (hasService) {
            // Process has services, put it at the top of the service list.
            if (DEBUG_LRU) Slog.d(TAG, "Adding to top of LRU service list: " + app);
            mLruProcesses.add(mLruProcessActivityStart, app);
            nextIndex = mLruProcessServiceStart;
            mLruProcessActivityStart++;
        } else  {
            // Process not otherwise of interest, it goes to the top of the non-service area.
            int index = mLruProcessServiceStart;
            if (client != null) {
                // If there is a client, don't allow the process to be moved up higher
                // in the list than that client.
                int clientIndex = mLruProcesses.lastIndexOf(client);
                if (DEBUG_LRU && clientIndex < 0) Slog.d(TAG, "Unknown client " + client
                        + " when updating " + app);
                if (clientIndex <= lrui) {
                    // Don't allow the client index restriction to push it down farther in the
                    // list than it already is.
                    clientIndex = lrui;
                }
                if (clientIndex >= 0 && index > clientIndex) {
                    index = clientIndex;
                }
            }
            if (DEBUG_LRU) Slog.d(TAG, "Adding at " + index + " of LRU list: " + app);
            mLruProcesses.add(index, app);
            nextIndex = index-1;
            mLruProcessActivityStart++;
            mLruProcessServiceStart++;
        }

        // If the app is currently using a content provider or service,
        // bump those processes as well.
        for (int j=app.connections.size()-1; j>=0; j--) {
            ConnectionRecord cr = app.connections.valueAt(j);
            if (cr.binding != null && !cr.serviceDead && cr.binding.service != null
                    && cr.binding.service.app != null
                    && cr.binding.service.app.lruSeq != mLruSeq
                    && !cr.binding.service.app.persistent) {
                nextIndex = updateLruProcessInternalLocked(cr.binding.service.app, now, nextIndex,
                        "service connection", cr, app);
            }
        }
        for (int j=app.conProviders.size()-1; j>=0; j--) {
            ContentProviderRecord cpr = app.conProviders.get(j).provider;
            if (cpr.proc != null && cpr.proc.lruSeq != mLruSeq && !cpr.proc.persistent) {
                nextIndex = updateLruProcessInternalLocked(cpr.proc, now, nextIndex,
                        "provider reference", cpr, app);
            }
        }
    }

    final ProcessRecord getProcessRecordLocked(String processName, int uid, boolean keepIfLarge) {
        if (uid == Process.SYSTEM_UID) {
            // The system gets to run in any process.  If there are multiple
            // processes with the same uid, just pick the first (this
            // should never happen).
            SparseArray<ProcessRecord> procs = mProcessNames.getMap().get(processName);
            if (procs == null) return null;
            final int N = procs.size();
            for (int i = 0; i < N; i++) {
                if (UserHandle.isSameUser(procs.keyAt(i), uid)) return procs.valueAt(i);
            }
        }
        ProcessRecord proc = mProcessNames.get(processName, uid);
        if (false && proc != null && !keepIfLarge
                && proc.setProcState >= ActivityManager.PROCESS_STATE_CACHED_EMPTY
                && proc.lastCachedPss >= 4000) {
            // Turn this condition on to cause killing to happen regularly, for testing.
            if (proc.baseProcessTracker != null) {
                proc.baseProcessTracker.reportCachedKill(proc.pkgList, proc.lastCachedPss);
            }
            killUnneededProcessLocked(proc, Long.toString(proc.lastCachedPss)
                    + "k from cached");
        } else if (proc != null && !keepIfLarge
                && mLastMemoryLevel > ProcessStats.ADJ_MEM_FACTOR_NORMAL
                && proc.setProcState >= ActivityManager.PROCESS_STATE_CACHED_EMPTY) {
            if (DEBUG_PSS) Slog.d(TAG, "May not keep " + proc + ": pss=" + proc.lastCachedPss);
            if (proc.lastCachedPss >= mProcessList.getCachedRestoreThresholdKb()) {
                if (proc.baseProcessTracker != null) {
                    proc.baseProcessTracker.reportCachedKill(proc.pkgList, proc.lastCachedPss);
                }
                killUnneededProcessLocked(proc, Long.toString(proc.lastCachedPss)
                        + "k from cached");
            }
        }
        return proc;
    }

    void ensurePackageDexOpt(String packageName) {
        IPackageManager pm = AppGlobals.getPackageManager();
        try {
            if (pm.performDexOptIfNeeded(packageName, null /* instruction set */)) {
                mDidDexOpt = true;
            }
        } catch (RemoteException e) {
        }
    }

    boolean isNextTransitionForward() {
        int transit = mWindowManager.getPendingAppTransition();
        return transit == AppTransition.TRANSIT_ACTIVITY_OPEN
                || transit == AppTransition.TRANSIT_TASK_OPEN
                || transit == AppTransition.TRANSIT_TASK_TO_FRONT;
    }

    int startIsolatedProcess(String entryPoint, String[] entryPointArgs,
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
            info.uid = Process.SYSTEM_UID;
            info.processName = processName;
            info.className = entryPoint;
            info.packageName = "android";
            ProcessRecord proc = startProcessLocked(processName, info /* info */,
                    false /* knownToBeDead */, 0 /* intentFlags */, ""  /* hostingType */,
                    null /* hostingName */, true /* allowWhileBooting */, true /* isolated */,
                    uid, true /* keepIfLarge */, abiOverride, entryPoint, entryPointArgs,
                    crashHandler);
            return proc != null ? proc.pid : 0;
        }
    }

    final ProcessRecord startProcessLocked(String processName,
            ApplicationInfo info, boolean knownToBeDead, int intentFlags,
            String hostingType, ComponentName hostingName, boolean allowWhileBooting,
            boolean isolated, boolean keepIfLarge) {
        return startProcessLocked(processName, info, knownToBeDead, intentFlags, hostingType,
                hostingName, allowWhileBooting, isolated, 0 /* isolatedUid */, keepIfLarge,
                null /* ABI override */, null /* entryPoint */, null /* entryPointArgs */,
                null /* crashHandler */);
    }

    final ProcessRecord startProcessLocked(String processName, ApplicationInfo info,
            boolean knownToBeDead, int intentFlags, String hostingType, ComponentName hostingName,
            boolean allowWhileBooting, boolean isolated, int isolatedUid, boolean keepIfLarge,
            String abiOverride, String entryPoint, String[] entryPointArgs, Runnable crashHandler) {
        ProcessRecord app;
        if (!isolated) {
            app = getProcessRecordLocked(processName, info.uid, keepIfLarge);
        } else {
            // If this is an isolated process, it can't re-use an existing process.
            app = null;
        }
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
                app.addPackage(info.packageName, info.versionCode, mProcessStats);
                return app;
            }

            // An application record is attached to a previous process,
            // clean it up now.
            if (DEBUG_PROCESSES || DEBUG_CLEANUP) Slog.v(TAG, "App died: " + app);
            Process.killProcessGroup(app.info.uid, app.pid);
            handleAppDiedLocked(app, true, true);
        }

        String hostingNameStr = hostingName != null
                ? hostingName.flattenToShortString() : null;

        if (!isolated) {
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
                    EventLog.writeEvent(EventLogTags.AM_PROC_GOOD,
                            UserHandle.getUserId(info.uid), info.uid,
                            info.processName);
                    mBadProcesses.remove(info.processName, info.uid);
                    if (app != null) {
                        app.bad = false;
                    }
                }
            }
        }

        if (app == null) {
            app = newProcessRecordLocked(info, processName, isolated, isolatedUid);
            app.crashHandler = crashHandler;
            if (app == null) {
                Slog.w(TAG, "Failed making new process record for "
                        + processName + "/" + info.uid + " isolated=" + isolated);
                return null;
            }
            mProcessNames.put(processName, app.uid, app);
            if (isolated) {
                mIsolatedProcesses.put(app.uid, app);
            }
        } else {
            // If this is a new package in the process, add the package to the list
            app.addPackage(info.packageName, info.versionCode, mProcessStats);
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

        startProcessLocked(
                app, hostingType, hostingNameStr, abiOverride, entryPoint, entryPointArgs);
        return (app.pid != 0) ? app : null;
    }

    boolean isAllowedWhileBooting(ApplicationInfo ai) {
        return (ai.flags&ApplicationInfo.FLAG_PERSISTENT) != 0;
    }

    private final void startProcessLocked(ProcessRecord app,
            String hostingType, String hostingNameStr) {
        startProcessLocked(app, hostingType, hostingNameStr, null /* abiOverride */,
                null /* entryPoint */, null /* entryPointArgs */);
    }

    private final void startProcessLocked(ProcessRecord app, String hostingType,
            String hostingNameStr, String abiOverride, String entryPoint, String[] entryPointArgs) {
        if (app.pid > 0 && app.pid != MY_PID) {
            synchronized (mPidsSelfLocked) {
                mPidsSelfLocked.remove(app.pid);
                mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);
            }
            app.setPid(0);
        }

        if (DEBUG_PROCESSES && mProcessesOnHold.contains(app)) Slog.v(TAG,
                "startProcessLocked removing on hold: " + app);
        mProcessesOnHold.remove(app);

        updateCpuStats();

        try {
            int uid = app.uid;

            int[] gids = null;
            int mountExternal = Zygote.MOUNT_EXTERNAL_NONE;
            if (!app.isolated) {
                int[] permGids = null;
                try {
                    final PackageManager pm = mContext.getPackageManager();
                    permGids = pm.getPackageGids(app.info.packageName);

                    if (Environment.isExternalStorageEmulated()) {
                        if (pm.checkPermission(
                                android.Manifest.permission.ACCESS_ALL_EXTERNAL_STORAGE,
                                app.info.packageName) == PERMISSION_GRANTED) {
                            mountExternal = Zygote.MOUNT_EXTERNAL_MULTIUSER_ALL;
                        } else {
                            mountExternal = Zygote.MOUNT_EXTERNAL_MULTIUSER;
                        }
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    Slog.w(TAG, "Unable to retrieve gids", e);
                }

                /*
                 * Add shared application and profile GIDs so applications can share some
                 * resources like shared libraries and access user-wide resources
                 */
                if (permGids == null) {
                    gids = new int[2];
                } else {
                    gids = new int[permGids.length + 2];
                    System.arraycopy(permGids, 0, gids, 2, permGids.length);
                }
                gids[0] = UserHandle.getSharedAppGid(UserHandle.getAppId(uid));
                gids[1] = UserHandle.getUserGid(UserHandle.getUserId(uid));
            }
            if (mFactoryTest != FactoryTest.FACTORY_TEST_OFF) {
                if (mFactoryTest == FactoryTest.FACTORY_TEST_LOW_LEVEL
                        && mTopComponent != null
                        && app.processName.equals(mTopComponent.getPackageName())) {
                    uid = 0;
                }
                if (mFactoryTest == FactoryTest.FACTORY_TEST_HIGH_LEVEL
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
                mSafeMode == true) {
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

            String requiredAbi = (abiOverride != null) ? abiOverride : app.info.primaryCpuAbi;
            if (requiredAbi == null) {
                requiredAbi = Build.SUPPORTED_ABIS[0];
            }

            // Start the process.  It will either succeed and return a result containing
            // the PID of the new process, or else throw a RuntimeException.
            boolean isActivityProcess = (entryPoint == null);
            if (entryPoint == null) entryPoint = "android.app.ActivityThread";
            Process.ProcessStartResult startResult = Process.start(entryPoint,
                    app.processName, uid, uid, gids, debugFlags, mountExternal,
                    app.info.targetSdkVersion, app.info.seinfo, requiredAbi, entryPointArgs);

            if (app.isolated) {
                mBatteryStatsService.addIsolatedUid(app.uid, app.info.uid);
            }
            mBatteryStatsService.noteProcessStart(app.processName, app.info.uid);

            EventLog.writeEvent(EventLogTags.AM_PROC_START,
                    UserHandle.getUserId(uid), startResult.pid, uid,
                    app.processName, hostingType,
                    hostingNameStr != null ? hostingNameStr : "");

            if (app.persistent) {
                Watchdog.getInstance().processStarted(app.processName, startResult.pid);
            }

            StringBuilder buf = mStringBuilder;
            buf.setLength(0);
            buf.append("Start proc ");
            buf.append(app.processName);
            if (!isActivityProcess) {
                buf.append(" [");
                buf.append(entryPoint);
                buf.append("]");
            }
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
            if (requiredAbi != null) {
                buf.append(" abi=");
                buf.append(requiredAbi);
            }
            Slog.i(TAG, buf.toString());
            app.setPid(startResult.pid);
            app.usingWrapper = startResult.usingWrapper;
            app.removed = false;
            app.killedByAm = false;
            synchronized (mPidsSelfLocked) {
                this.mPidsSelfLocked.put(startResult.pid, app);
                if (isActivityProcess) {
                    Message msg = mHandler.obtainMessage(PROC_START_TIMEOUT_MSG);
                    msg.obj = app;
                    mHandler.sendMessageDelayed(msg, startResult.usingWrapper
                            ? PROC_START_TIMEOUT_WITH_WRAPPER : PROC_START_TIMEOUT);
                }
            }
        } catch (RuntimeException e) {
            // XXX do better error recovery.
            app.setPid(0);
            mBatteryStatsService.noteProcessFinish(app.processName, app.info.uid);
            if (app.isolated) {
                mBatteryStatsService.removeIsolatedUid(app.uid, app.info.uid);
            }
            Slog.e(TAG, "Failure starting process " + app.processName, e);
        }
    }

    void updateUsageStats(ActivityRecord component, boolean resumed) {
        if (DEBUG_SWITCH) Slog.d(TAG, "updateUsageStats: comp=" + component + "res=" + resumed);
        final BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
        if (resumed) {
            if (mUsageStatsService != null) {
                mUsageStatsService.reportEvent(component.realActivity, component.userId,
                        System.currentTimeMillis(),
                        UsageEvents.Event.MOVE_TO_FOREGROUND);
            }
            synchronized (stats) {
                stats.noteActivityResumedLocked(component.app.uid);
            }
        } else {
            if (mUsageStatsService != null) {
                mUsageStatsService.reportEvent(component.realActivity, component.userId,
                        System.currentTimeMillis(),
                        UsageEvents.Event.MOVE_TO_BACKGROUND);
            }
            synchronized (stats) {
                stats.noteActivityPausedLocked(component.app.uid);
            }
        }
    }

    Intent getHomeIntent() {
        Intent intent = new Intent(mTopAction, mTopData != null ? Uri.parse(mTopData) : null);
        intent.setComponent(mTopComponent);
        if (mFactoryTest != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
            intent.addCategory(Intent.CATEGORY_HOME);
        }
        return intent;
    }

    boolean startHomeActivityLocked(int userId) {
        if (mFactoryTest == FactoryTest.FACTORY_TEST_LOW_LEVEL
                && mTopAction == null) {
            // We are running in factory test mode, but unable to find
            // the factory test app, so just sit around displaying the
            // error message and don't try to start anything.
            return false;
        }
        Intent intent = getHomeIntent();
        ActivityInfo aInfo =
            resolveActivityInfo(intent, STOCK_PM_FLAGS, userId);
        if (aInfo != null) {
            intent.setComponent(new ComponentName(
                    aInfo.applicationInfo.packageName, aInfo.name));
            // Don't do this if the home app is currently being
            // instrumented.
            aInfo = new ActivityInfo(aInfo);
            aInfo.applicationInfo = getAppInfoForUser(aInfo.applicationInfo, userId);
            ProcessRecord app = getProcessRecordLocked(aInfo.processName,
                    aInfo.applicationInfo.uid, true);
            if (app == null || app.instrumentationClass == null) {
                intent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_NEW_TASK);
                mStackSupervisor.startHomeActivity(intent, aInfo);
            }
        }

        return true;
    }

    private ActivityInfo resolveActivityInfo(Intent intent, int flags, int userId) {
        ActivityInfo ai = null;
        ComponentName comp = intent.getComponent();
        try {
            if (comp != null) {
                ai = AppGlobals.getPackageManager().getActivityInfo(comp, flags, userId);
            } else {
                ResolveInfo info = AppGlobals.getPackageManager().resolveIntent(
                        intent,
                        intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                            flags, userId);

                if (info != null) {
                    ai = info.activityInfo;
                }
            }
        } catch (RemoteException e) {
            // ignore
        }

        return ai;
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
        if (mFactoryTest != FactoryTest.FACTORY_TEST_LOW_LEVEL &&
                Settings.Global.getInt(resolver,
                        Settings.Global.DEVICE_PROVISIONED, 0) != 0) {
            mCheckedForSetup = true;

            // See if we should be showing the platform update setup UI.
            Intent intent = new Intent(Intent.ACTION_UPGRADE_SETUP);
            List<ResolveInfo> ris = mContext.getPackageManager()
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
                    mStackSupervisor.startActivityLocked(null, intent, null, ri.activityInfo,
                            null, null, null, null, 0, 0, 0, null, 0, null, false, null, null);
                }
            }
        }
    }

    CompatibilityInfo compatibilityInfoForPackageLocked(ApplicationInfo ai) {
        return mCompatModePackages.compatibilityInfoForPackageLocked(ai);
    }

    void enforceNotIsolatedCaller(String caller) {
        if (UserHandle.isIsolated(Binder.getCallingUid())) {
            throw new SecurityException("Isolated process not allowed to call " + caller);
        }
    }

    @Override
    public int getFrontActivityScreenCompatMode() {
        enforceNotIsolatedCaller("getFrontActivityScreenCompatMode");
        synchronized (this) {
            return mCompatModePackages.getFrontActivityScreenCompatModeLocked();
        }
    }

    @Override
    public void setFrontActivityScreenCompatMode(int mode) {
        enforceCallingPermission(android.Manifest.permission.SET_SCREEN_COMPATIBILITY,
                "setFrontActivityScreenCompatMode");
        synchronized (this) {
            mCompatModePackages.setFrontActivityScreenCompatModeLocked(mode);
        }
    }

    @Override
    public int getPackageScreenCompatMode(String packageName) {
        enforceNotIsolatedCaller("getPackageScreenCompatMode");
        synchronized (this) {
            return mCompatModePackages.getPackageScreenCompatModeLocked(packageName);
        }
    }

    @Override
    public void setPackageScreenCompatMode(String packageName, int mode) {
        enforceCallingPermission(android.Manifest.permission.SET_SCREEN_COMPATIBILITY,
                "setPackageScreenCompatMode");
        synchronized (this) {
            mCompatModePackages.setPackageScreenCompatModeLocked(packageName, mode);
        }
    }

    @Override
    public boolean getPackageAskScreenCompat(String packageName) {
        enforceNotIsolatedCaller("getPackageAskScreenCompat");
        synchronized (this) {
            return mCompatModePackages.getPackageAskCompatModeLocked(packageName);
        }
    }

    @Override
    public void setPackageAskScreenCompat(String packageName, boolean ask) {
        enforceCallingPermission(android.Manifest.permission.SET_SCREEN_COMPATIBILITY,
                "setPackageAskScreenCompat");
        synchronized (this) {
            mCompatModePackages.setPackageAskCompatModeLocked(packageName, ask);
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
            mAvailProcessChanges.addAll(mPendingProcessChanges);
            mPendingProcessChanges.clear();
            if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG, "*** Delivering " + N + " process changes");
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
                            if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG, "ACTIVITIES CHANGED pid="
                                    + item.pid + " uid=" + item.uid + ": "
                                    + item.foregroundActivities);
                            observer.onForegroundActivitiesChanged(item.pid, item.uid,
                                    item.foregroundActivities);
                        }
                        if ((item.changes&ProcessChangeItem.CHANGE_PROCESS_STATE) != 0) {
                            if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG, "PROCSTATE CHANGED pid="
                                    + item.pid + " uid=" + item.uid + ": " + item.processState);
                            observer.onProcessStateChanged(item.pid, item.uid, item.processState);
                        }
                    }
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

    @Override
    public final int startActivity(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int startFlags,
            String profileFile, ParcelFileDescriptor profileFd, Bundle options) {
        return startActivityAsUser(caller, callingPackage, intent, resolvedType, resultTo,
                resultWho, requestCode,
                startFlags, profileFile, profileFd, options, UserHandle.getCallingUserId());
    }

    @Override
    public final int startActivityAsUser(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int startFlags,
            String profileFile, ParcelFileDescriptor profileFd, Bundle options, int userId) {
        enforceNotIsolatedCaller("startActivity");
        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId,
                false, ALLOW_FULL_ONLY, "startActivity", null);
        // TODO: Switch to user app stacks here.
        return mStackSupervisor.startActivityMayWait(caller, -1, callingPackage, intent, resolvedType,
                null, null, resultTo, resultWho, requestCode, startFlags, profileFile, profileFd,
                null, null, options, userId, null);
    }

    @Override
    public final int startActivityAsCaller(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int startFlags,
            String profileFile, ParcelFileDescriptor profileFd, Bundle options) {

        // This is very dangerous -- it allows you to perform a start activity (including
        // permission grants) as any app that may launch one of your own activities.  So
        // we will only allow this to be done from activities that are part of the core framework,
        // and then only when they are running as the system.
        final ActivityRecord sourceRecord;
        final int targetUid;
        final String targetPackage;
        synchronized (this) {
            if (resultTo == null) {
                throw new SecurityException("Must be called from an activity");
            }
            sourceRecord = mStackSupervisor.isInAnyStackLocked(resultTo);
            if (sourceRecord == null) {
                throw new SecurityException("Called with bad activity token: " + resultTo);
            }
            if (!sourceRecord.info.packageName.equals("android")) {
                throw new SecurityException(
                        "Must be called from an activity that is declared in the android package");
            }
            if (sourceRecord.app == null) {
                throw new SecurityException("Called without a process attached to activity");
            }
            if (UserHandle.getAppId(sourceRecord.app.uid) != Process.SYSTEM_UID) {
                // This is still okay, as long as this activity is running under the
                // uid of the original calling activity.
                if (sourceRecord.app.uid != sourceRecord.launchedFromUid) {
                    throw new SecurityException(
                            "Calling activity in uid " + sourceRecord.app.uid
                                    + " must be system uid or original calling uid "
                                    + sourceRecord.launchedFromUid);
                }
            }
            targetUid = sourceRecord.launchedFromUid;
            targetPackage = sourceRecord.launchedFromPackage;
        }

        // TODO: Switch to user app stacks here.
        try {
            int ret = mStackSupervisor.startActivityMayWait(null, targetUid, targetPackage, intent,
                    resolvedType, null, null, resultTo, resultWho, requestCode, startFlags,
                    null, null, null, null, options, UserHandle.getUserId(sourceRecord.app.uid),
                    null);
            return ret;
        } catch (SecurityException e) {
            // XXX need to figure out how to propagate to original app.
            // A SecurityException here is generally actually a fault of the original
            // calling activity (such as a fairly granting permissions), so propagate it
            // back to them.
            /*
            StringBuilder msg = new StringBuilder();
            msg.append("While launching");
            msg.append(intent.toString());
            msg.append(": ");
            msg.append(e.getMessage());
            */
            throw e;
        }
    }

    @Override
    public final WaitResult startActivityAndWait(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int startFlags, String profileFile,
            ParcelFileDescriptor profileFd, Bundle options, int userId) {
        enforceNotIsolatedCaller("startActivityAndWait");
        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId,
                false, ALLOW_FULL_ONLY, "startActivityAndWait", null);
        WaitResult res = new WaitResult();
        // TODO: Switch to user app stacks here.
        mStackSupervisor.startActivityMayWait(caller, -1, callingPackage, intent, resolvedType,
                null, null, resultTo, resultWho, requestCode, startFlags, profileFile, profileFd,
                res, null, options, userId, null);
        return res;
    }

    @Override
    public final int startActivityWithConfig(IApplicationThread caller, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int startFlags, Configuration config,
            Bundle options, int userId) {
        enforceNotIsolatedCaller("startActivityWithConfig");
        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId,
                false, ALLOW_FULL_ONLY, "startActivityWithConfig", null);
        // TODO: Switch to user app stacks here.
        int ret = mStackSupervisor.startActivityMayWait(caller, -1, callingPackage, intent,
                resolvedType, null, null, resultTo, resultWho, requestCode, startFlags,
                null, null, null, config, options, userId, null);
        return ret;
    }

    @Override
    public int startActivityIntentSender(IApplicationThread caller,
            IntentSender intent, Intent fillInIntent, String resolvedType,
            IBinder resultTo, String resultWho, int requestCode,
            int flagsMask, int flagsValues, Bundle options) {
        enforceNotIsolatedCaller("startActivityIntentSender");
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
            final ActivityStack stack = getFocusedStack();
            if (stack.mResumedActivity != null &&
                    stack.mResumedActivity.info.applicationInfo.uid == Binder.getCallingUid()) {
                mAppSwitchesAllowedTime = 0;
            }
        }
        int ret = pir.sendInner(0, fillInIntent, resolvedType, null, null,
                resultTo, resultWho, requestCode, flagsMask, flagsValues, options, null);
        return ret;
    }

    @Override
    public int startVoiceActivity(String callingPackage, int callingPid, int callingUid,
            Intent intent, String resolvedType, IVoiceInteractionSession session,
            IVoiceInteractor interactor, int startFlags, String profileFile,
            ParcelFileDescriptor profileFd, Bundle options, int userId) {
        if (checkCallingPermission(Manifest.permission.BIND_VOICE_INTERACTION)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: startVoiceActivity() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.BIND_VOICE_INTERACTION;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        if (session == null || interactor == null) {
            throw new NullPointerException("null session or interactor");
        }
        userId = handleIncomingUser(callingPid, callingUid, userId,
                false, ALLOW_FULL_ONLY, "startVoiceActivity", null);
        // TODO: Switch to user app stacks here.
        return mStackSupervisor.startActivityMayWait(null, callingUid, callingPackage, intent,
                resolvedType, session, interactor, null, null, 0, startFlags,
                profileFile, profileFd, null, null, options, userId, null);
    }

    @Override
    public boolean startNextMatchingActivity(IBinder callingActivity,
            Intent intent, Bundle options) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized (this) {
            final ActivityRecord r = ActivityRecord.isInStackLocked(callingActivity);
            if (r == null) {
                ActivityOptions.abort(options);
                return false;
            }
            if (r.app == null || r.app.thread == null) {
                // The caller is not running...  d'oh!
                ActivityOptions.abort(options);
                return false;
            }
            intent = new Intent(intent);
            // The caller is not allowed to change the data.
            intent.setDataAndType(r.intent.getData(), r.intent.getType());
            // And we are resetting to find the next component...
            intent.setComponent(null);

            final boolean debug = ((intent.getFlags() & Intent.FLAG_DEBUG_LOG_RESOLUTION) != 0);

            ActivityInfo aInfo = null;
            try {
                List<ResolveInfo> resolves =
                    AppGlobals.getPackageManager().queryIntentActivities(
                            intent, r.resolvedType,
                            PackageManager.MATCH_DEFAULT_ONLY | STOCK_PM_FLAGS,
                            UserHandle.getCallingUserId());

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
                        if (debug) {
                            Slog.v(TAG, "Next matching activity: found current " + r.packageName
                                    + "/" + r.info.name);
                            Slog.v(TAG, "Next matching activity: next is " + aInfo.packageName
                                    + "/" + aInfo.name);
                        }
                        break;
                    }
                }
            } catch (RemoteException e) {
            }

            if (aInfo == null) {
                // Nobody who is next!
                ActivityOptions.abort(options);
                if (debug) Slog.d(TAG, "Next matching activity: nothing found");
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
            int res = mStackSupervisor.startActivityLocked(r.app.thread, intent,
                    r.resolvedType, aInfo, null, null, resultTo != null ? resultTo.appToken : null,
                    resultWho, requestCode, -1, r.launchedFromUid, r.launchedFromPackage, 0,
                    options, false, null, null);
            Binder.restoreCallingIdentity(origId);

            r.finishing = wasFinishing;
            if (res != ActivityManager.START_SUCCESS) {
                return false;
            }
            return true;
        }
    }

    @Override
    public final int startActivityFromRecents(int taskId, Bundle options) {
        if (checkCallingPermission(START_TASKS_FROM_RECENTS) != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: startActivityFromRecents called without " +
                    START_TASKS_FROM_RECENTS;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        final int callingUid;
        final String callingPackage;
        final Intent intent;
        final int userId;
        synchronized (this) {
            final TaskRecord task = recentTaskForIdLocked(taskId);
            if (task == null) {
                throw new ActivityNotFoundException("Task " + taskId + " not found.");
            }
            callingUid = task.mCallingUid;
            callingPackage = task.mCallingPackage;
            intent = task.intent;
            userId = task.userId;
        }
        return startActivityInPackage(callingUid, callingPackage, intent, null, null, null, 0, 0,
                options, userId, null);
    }

    final int startActivityInPackage(int uid, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int startFlags, Bundle options, int userId,
                    IActivityContainer container) {

        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId,
                false, ALLOW_FULL_ONLY, "startActivityInPackage", null);

        // TODO: Switch to user app stacks here.
        int ret = mStackSupervisor.startActivityMayWait(null, uid, callingPackage, intent, resolvedType,
                null, null, resultTo, resultWho, requestCode, startFlags,
                null, null, null, null, options, userId, container);
        return ret;
    }

    @Override
    public final int startActivities(IApplicationThread caller, String callingPackage,
            Intent[] intents, String[] resolvedTypes, IBinder resultTo, Bundle options,
            int userId) {
        enforceNotIsolatedCaller("startActivities");
        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId,
                false, ALLOW_FULL_ONLY, "startActivity", null);
        // TODO: Switch to user app stacks here.
        int ret = mStackSupervisor.startActivities(caller, -1, callingPackage, intents,
                resolvedTypes, resultTo, options, userId);
        return ret;
    }

    final int startActivitiesInPackage(int uid, String callingPackage,
            Intent[] intents, String[] resolvedTypes, IBinder resultTo,
            Bundle options, int userId) {

        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId,
                false, ALLOW_FULL_ONLY, "startActivityInPackage", null);
        // TODO: Switch to user app stacks here.
        int ret = mStackSupervisor.startActivities(null, uid, callingPackage, intents, resolvedTypes,
                resultTo, options, userId);
        return ret;
    }

    //explicitly remove thd old information in mRecentTasks when removing existing user.
    private void removeRecentTasksForUserLocked(int userId) {
        if(userId <= 0) {
            Slog.i(TAG, "Can't remove recent task on user " + userId);
            return;
        }

        for (int i = mRecentTasks.size() - 1; i >= 0; --i) {
            TaskRecord tr = mRecentTasks.get(i);
            if (tr.userId == userId) {
                if(DEBUG_TASKS) Slog.i(TAG, "remove RecentTask " + tr
                        + " when finishing user" + userId);
                tr.disposeThumbnail();
                mRecentTasks.remove(i);
            }
        }

        // Remove tasks from persistent storage.
        mTaskPersister.wakeup(null, true);
    }

    final void addRecentTaskLocked(TaskRecord task) {
        final int N = mRecentTasks.size();
        // Quick case: check if the top-most recent task is the same.
        if (N > 0 && mRecentTasks.get(0) == task) {
            return;
        }
        // Another quick case: never add voice sessions.
        if (task.voiceSession != null) {
            return;
        }

        trimRecentsForTask(task, true);

        if (N >= MAX_RECENT_TASKS) {
            final TaskRecord tr = mRecentTasks.remove(N - 1);
            tr.disposeThumbnail();
            tr.closeRecentsChain();
        }
        mRecentTasks.add(0, task);
    }

    /**
     * If needed, remove oldest existing entries in recents that are for the same kind
     * of task as the given one.
     */
    int trimRecentsForTask(TaskRecord task, boolean doTrim) {
        int N = mRecentTasks.size();
        final Intent intent = task.intent;
        final boolean document = intent != null && intent.isDocument();

        int maxRecents = task.maxRecents - 1;
        for (int i=0; i<N; i++) {
            final TaskRecord tr = mRecentTasks.get(i);
            if (task != tr) {
                if (task.userId != tr.userId) {
                    continue;
                }
                if (i > MAX_RECENT_BITMAPS) {
                    tr.freeLastThumbnail();
                }
                final Intent trIntent = tr.intent;
                if ((task.affinity == null || !task.affinity.equals(tr.affinity)) &&
                    (intent == null || !intent.filterEquals(trIntent))) {
                    continue;
                }
                final boolean trIsDocument = trIntent != null && trIntent.isDocument();
                if (document && trIsDocument) {
                    // These are the same document activity (not necessarily the same doc).
                    if (maxRecents > 0) {
                        --maxRecents;
                        continue;
                    }
                    // Hit the maximum number of documents for this task. Fall through
                    // and remove this document from recents.
                } else if (document || trIsDocument) {
                    // Only one of these is a document. Not the droid we're looking for.
                    continue;
                }
            }

            if (!doTrim) {
                // If the caller is not actually asking for a trim, just tell them we reached
                // a point where the trim would happen.
                return i;
            }

            // Either task and tr are the same or, their affinities match or their intents match
            // and neither of them is a document, or they are documents using the same activity
            // and their maxRecents has been reached.
            tr.disposeThumbnail();
            mRecentTasks.remove(i);
            if (task != tr) {
                tr.closeRecentsChain();
            }
            i--;
            N--;
            if (task.intent == null) {
                // If the new recent task we are adding is not fully
                // specified, then replace it with the existing recent task.
                task = tr;
            }
            notifyTaskPersisterLocked(tr, false);
        }

        return -1;
    }

    @Override
    public void reportActivityFullyDrawn(IBinder token) {
        synchronized (this) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return;
            }
            r.reportFullyDrawnLocked();
        }
    }

    @Override
    public void setRequestedOrientation(IBinder token, int requestedOrientation) {
        synchronized (this) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            mWindowManager.setAppOrientation(r.appToken, requestedOrientation);
            Configuration config = mWindowManager.updateOrientationFromAppTokens(
                    mConfiguration, r.mayFreezeScreenLocked(r.app) ? r.appToken : null);
            if (config != null) {
                r.frozenBeforeDestroy = true;
                if (!updateConfigurationLocked(config, r, false, false)) {
                    mStackSupervisor.resumeTopActivitiesLocked();
                }
            }
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public int getRequestedOrientation(IBinder token) {
        synchronized (this) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            }
            return mWindowManager.getAppOrientation(r.appToken);
        }
    }

    /**
     * This is the internal entry point for handling Activity.finish().
     *
     * @param token The Binder token referencing the Activity we want to finish.
     * @param resultCode Result code, if any, from this Activity.
     * @param resultData Result data (Intent), if any, from this Activity.
     * @param finishTask Whether to finish the task associated with this Activity.  Only applies to
     *            the root Activity in the task.
     *
     * @return Returns true if the activity successfully finished, or false if it is still running.
     */
    @Override
    public final boolean finishActivity(IBinder token, int resultCode, Intent resultData,
            boolean finishTask) {
        // Refuse possible leaked file descriptors
        if (resultData != null && resultData.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return true;
            }
            // Keep track of the root activity of the task before we finish it
            TaskRecord tr = r.task;
            ActivityRecord rootR = tr.getRootActivity();
            // Do not allow task to finish in Lock Task mode.
            if (tr == mStackSupervisor.mLockTaskModeTask) {
                if (rootR == r) {
                    mStackSupervisor.showLockTaskToast();
                    return false;
                }
            }
            if (mController != null) {
                // Find the first activity that is not finishing.
                ActivityRecord next = r.task.stack.topRunningActivityLocked(token, 0);
                if (next != null) {
                    // ask watcher if this is allowed
                    boolean resumeOK = true;
                    try {
                        resumeOK = mController.activityResuming(next.packageName);
                    } catch (RemoteException e) {
                        mController = null;
                        Watchdog.getInstance().setActivityController(null);
                    }

                    if (!resumeOK) {
                        return false;
                    }
                }
            }
            final long origId = Binder.clearCallingIdentity();
            try {
                boolean res;
                if (finishTask && r == rootR) {
                    // If requested, remove the task that is associated to this activity only if it
                    // was the root activity in the task.  The result code and data is ignored because
                    // we don't support returning them across task boundaries.
                    res = removeTaskByIdLocked(tr.taskId, 0);
                } else {
                    res = tr.stack.requestFinishActivityLocked(token, resultCode,
                            resultData, "app-request", true);
                }
                return res;
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
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

        synchronized(this) {
            if (mHeavyWeightProcess == null) {
                return;
            }

            ArrayList<ActivityRecord> activities = new ArrayList<ActivityRecord>(
                    mHeavyWeightProcess.activities);
            for (int i=0; i<activities.size(); i++) {
                ActivityRecord r = activities.get(i);
                if (!r.finishing) {
                    r.task.stack.finishActivityLocked(r, Activity.RESULT_CANCELED,
                            null, "finish-heavy", true);
                }
            }

            mHandler.sendMessage(mHandler.obtainMessage(CANCEL_HEAVY_NOTIFICATION_MSG,
                    mHeavyWeightProcess.userId, 0));
            mHeavyWeightProcess = null;
        }
    }

    @Override
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
                    if (p.uid != uid) {
                        continue;
                    }
                    if (p.pid == initialPid) {
                        proc = p;
                        break;
                    }
                    if (p.pkgList.containsKey(packageName)) {
                        proc = p;
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

    @Override
    public final void finishSubActivity(IBinder token, String resultWho,
            int requestCode) {
        synchronized(this) {
            final long origId = Binder.clearCallingIdentity();
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r != null) {
                r.task.stack.finishSubActivityLocked(r, resultWho, requestCode);
            }
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public boolean finishActivityAffinity(IBinder token) {
        synchronized(this) {
            final long origId = Binder.clearCallingIdentity();
            try {
                ActivityRecord r = ActivityRecord.isInStackLocked(token);

                ActivityRecord rootR = r.task.getRootActivity();
                // Do not allow task to finish in Lock Task mode.
                if (r.task == mStackSupervisor.mLockTaskModeTask) {
                    if (rootR == r) {
                        mStackSupervisor.showLockTaskToast();
                        return false;
                    }
                }
                boolean res = false;
                if (r != null) {
                    res = r.task.stack.finishActivityAffinityLocked(r);
                }
                return res;
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public void finishVoiceTask(IVoiceInteractionSession session) {
        synchronized(this) {
            final long origId = Binder.clearCallingIdentity();
            try {
                mStackSupervisor.finishVoiceTask(session);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }

    }

    @Override
    public boolean willActivityBeVisible(IBinder token) {
        synchronized(this) {
            ActivityStack stack = ActivityRecord.getStackLocked(token);
            if (stack != null) {
                return stack.willActivityBeVisibleLocked(token);
            }
            return false;
        }
    }

    @Override
    public void overridePendingTransition(IBinder token, String packageName,
            int enterAnim, int exitAnim) {
        synchronized(this) {
            ActivityRecord self = ActivityRecord.isInStackLocked(token);
            if (self == null) {
                return;
            }

            final long origId = Binder.clearCallingIdentity();

            if (self.state == ActivityState.RESUMED
                    || self.state == ActivityState.PAUSING) {
                mWindowManager.overridePendingAppTransition(packageName,
                        enterAnim, exitAnim, null);
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
        int pid = app.pid;
        cleanUpApplicationRecordLocked(app, restarting, allowRestart, -1);
        if (!restarting) {
            removeLruProcessLocked(app);
            if (pid > 0) {
                ProcessList.remove(pid);
            }
        }

        if (mProfileProc == app) {
            clearProfilerLocked();
        }

        // Remove this application's activities from active lists.
        boolean hasVisibleActivities = mStackSupervisor.handleAppDiedLocked(app);

        app.activities.clear();

        if (app.instrumentationClass != null) {
            Slog.w(TAG, "Crash of app " + app.processName
                  + " running instrumentation " + app.instrumentationClass);
            Bundle info = new Bundle();
            info.putString("shortMsg", "Process crashed.");
            finishInstrumentationLocked(app, Activity.RESULT_CANCELED, info);
        }

        if (!restarting) {
            if (!mStackSupervisor.resumeTopActivitiesLocked()) {
                // If there was nothing to resume, and we are not already
                // restarting this process, but there is a visible activity that
                // is hosted by the process...  then make sure all visible
                // activities are running, taking care of restarting this
                // process.
                if (hasVisibleActivities) {
                    mStackSupervisor.ensureActivitiesVisibleLocked(null, 0);
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

    final void doLowMemReportIfNeededLocked(ProcessRecord dyingProc) {
        // If there are no longer any background processes running,
        // and the app that died was not running instrumentation,
        // then tell everyone we are now low on memory.
        boolean haveBg = false;
        for (int i=mLruProcesses.size()-1; i>=0; i--) {
            ProcessRecord rec = mLruProcesses.get(i);
            if (rec.thread != null
                    && rec.setProcState >= ActivityManager.PROCESS_STATE_CACHED_ACTIVITY) {
                haveBg = true;
                break;
            }
        }

        if (!haveBg) {
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
                    = doReport ? new ArrayList<ProcessMemInfo>(mLruProcesses.size()) : null;
            EventLog.writeEvent(EventLogTags.AM_LOW_MEMORY, mLruProcesses.size());
            long now = SystemClock.uptimeMillis();
            for (int i=mLruProcesses.size()-1; i>=0; i--) {
                ProcessRecord rec = mLruProcesses.get(i);
                if (rec == dyingProc || rec.thread == null) {
                    continue;
                }
                if (doReport) {
                    memInfos.add(new ProcessMemInfo(rec.processName, rec.pid, rec.setAdj,
                            rec.setProcState, rec.adjType, rec.makeAdjReason()));
                }
                if ((rec.lastLowMemory+GC_MIN_INTERVAL) <= now) {
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

    final void appDiedLocked(ProcessRecord app) {
       appDiedLocked(app, app.pid, app.thread);
    }

    final void appDiedLocked(ProcessRecord app, int pid,
            IApplicationThread thread) {

        BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
        synchronized (stats) {
            stats.noteProcessDiedLocked(app.info.uid, pid);
        }

        Process.killProcessGroup(app.info.uid, pid);

        // Clean up already done if the process has been re-started.
        if (app.pid == pid && app.thread != null &&
                app.thread.asBinder() == thread.asBinder()) {
            boolean doLowMem = app.instrumentationClass == null;
            boolean doOomAdj = doLowMem;
            if (!app.killedByAm) {
                Slog.i(TAG, "Process " + app.processName + " (pid " + pid
                        + ") has died.");
                mAllowLowerMemLevel = true;
            } else {
                // Note that we always want to do oom adj to update our state with the
                // new number of procs.
                mAllowLowerMemLevel = false;
                doLowMem = false;
            }
            EventLog.writeEvent(EventLogTags.AM_PROC_DIED, app.userId, app.pid, app.processName);
            if (DEBUG_CLEANUP) Slog.v(
                TAG, "Dying app: " + app + ", pid: " + pid
                + ", thread: " + thread.asBinder());
            handleAppDiedLocked(app, false, true);

            if (doOomAdj) {
                updateOomAdjLocked();
            }
            if (doLowMem) {
                doLowMemReportIfNeededLocked(app);
            }
        } else if (app.pid != pid) {
            // A new process has already been started.
            Slog.i(TAG, "Process " + app.processName + " (pid " + pid
                    + ") has died and restarted (pid " + app.pid + ").");
            EventLog.writeEvent(EventLogTags.AM_PROC_DIED, app.userId, app.pid, app.processName);
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
     * @param nativeProcs optional list of native process names to dump stack crawls
     * @return file containing stack traces, or null if no dump file is configured
     */
    public static File dumpStackTraces(boolean clearTraces, ArrayList<Integer> firstPids,
            ProcessCpuTracker processCpuTracker, SparseArray<Boolean> lastPids, String[] nativeProcs) {
        String tracesPath = SystemProperties.get("dalvik.vm.stack-trace-file", null);
        if (tracesPath == null || tracesPath.length() == 0) {
            return null;
        }

        File tracesFile = new File(tracesPath);
        try {
            File tracesDir = tracesFile.getParentFile();
            if (!tracesDir.exists()) {
                tracesFile.mkdirs();
                if (!SELinux.restorecon(tracesDir)) {
                    return null;
                }
            }
            FileUtils.setPermissions(tracesDir.getPath(), 0775, -1, -1);  // drwxrwxr-x

            if (clearTraces && tracesFile.exists()) tracesFile.delete();
            tracesFile.createNewFile();
            FileUtils.setPermissions(tracesFile.getPath(), 0666, -1, -1); // -rw-rw-rw-
        } catch (IOException e) {
            Slog.w(TAG, "Unable to prepare ANR traces file: " + tracesPath, e);
            return null;
        }

        dumpStackTraces(tracesPath, firstPids, processCpuTracker, lastPids, nativeProcs);
        return tracesFile;
    }

    private static void dumpStackTraces(String tracesPath, ArrayList<Integer> firstPids,
            ProcessCpuTracker processCpuTracker, SparseArray<Boolean> lastPids, String[] nativeProcs) {
        // Use a FileObserver to detect when traces finish writing.
        // The order of traces is considered important to maintain for legibility.
        FileObserver observer = new FileObserver(tracesPath, FileObserver.CLOSE_WRITE) {
            @Override
            public synchronized void onEvent(int event, String path) { notify(); }
        };

        try {
            observer.startWatching();

            // First collect all of the stacks of the most important pids.
            if (firstPids != null) {
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
            }

            // Next collect the stacks of the native pids
            if (nativeProcs != null) {
                int[] pids = Process.getPidsForCommands(nativeProcs);
                if (pids != null) {
                    for (int pid : pids) {
                        Debug.dumpNativeBacktraceToFile(pid, tracesPath);
                    }
                }
            }

            // Lastly, measure CPU usage.
            if (processCpuTracker != null) {
                processCpuTracker.init();
                System.gc();
                processCpuTracker.update();
                try {
                    synchronized (processCpuTracker) {
                        processCpuTracker.wait(500); // measure over 1/2 second.
                    }
                } catch (InterruptedException e) {
                }
                processCpuTracker.update();

                // We'll take the stack crawls of just the top apps using CPU.
                final int N = processCpuTracker.countWorkingStats();
                int numProcs = 0;
                for (int i=0; i<N && numProcs<5; i++) {
                    ProcessCpuTracker.Stats stats = processCpuTracker.getWorkingStats(i);
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
        } finally {
            observer.stopWatching();
        }
    }

    final void logAppTooSlow(ProcessRecord app, long startTime, String msg) {
        if (true || IS_USER_BUILD) {
            return;
        }
        String tracesPath = SystemProperties.get("dalvik.vm.stack-trace-file", null);
        if (tracesPath == null || tracesPath.length() == 0) {
            return;
        }

        StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
        StrictMode.allowThreadDiskWrites();
        try {
            final File tracesFile = new File(tracesPath);
            final File tracesDir = tracesFile.getParentFile();
            final File tracesTmp = new File(tracesDir, "__tmp__");
            try {
                if (!tracesDir.exists()) {
                    tracesFile.mkdirs();
                    if (!SELinux.restorecon(tracesDir.getPath())) {
                        return;
                    }
                }
                FileUtils.setPermissions(tracesDir.getPath(), 0775, -1, -1);  // drwxrwxr-x

                if (tracesFile.exists()) {
                    tracesTmp.delete();
                    tracesFile.renameTo(tracesTmp);
                }
                StringBuilder sb = new StringBuilder();
                Time tobj = new Time();
                tobj.set(System.currentTimeMillis());
                sb.append(tobj.format("%Y-%m-%d %H:%M:%S"));
                sb.append(": ");
                TimeUtils.formatDuration(SystemClock.uptimeMillis()-startTime, sb);
                sb.append(" since ");
                sb.append(msg);
                FileOutputStream fos = new FileOutputStream(tracesFile);
                fos.write(sb.toString().getBytes());
                if (app == null) {
                    fos.write("\n*** No application process!".getBytes());
                }
                fos.close();
                FileUtils.setPermissions(tracesFile.getPath(), 0666, -1, -1); // -rw-rw-rw-
            } catch (IOException e) {
                Slog.w(TAG, "Unable to prepare slow app traces file: " + tracesPath, e);
                return;
            }

            if (app != null) {
                ArrayList<Integer> firstPids = new ArrayList<Integer>();
                firstPids.add(app.pid);
                dumpStackTraces(tracesPath, firstPids, null, null, null);
            }

            File lastTracesFile = null;
            File curTracesFile = null;
            for (int i=9; i>=0; i--) {
                String name = String.format(Locale.US, "slow%02d.txt", i);
                curTracesFile = new File(tracesDir, name);
                if (curTracesFile.exists()) {
                    if (lastTracesFile != null) {
                        curTracesFile.renameTo(lastTracesFile);
                    } else {
                        curTracesFile.delete();
                    }
                }
                lastTracesFile = curTracesFile;
            }
            tracesFile.renameTo(curTracesFile);
            if (tracesTmp.exists()) {
                tracesTmp.renameTo(tracesFile);
            }
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
    }

    final void appNotResponding(ProcessRecord app, ActivityRecord activity,
            ActivityRecord parent, boolean aboveSystem, final String annotation) {
        ArrayList<Integer> firstPids = new ArrayList<Integer>(5);
        SparseArray<Boolean> lastPids = new SparseArray<Boolean>(20);

        if (mController != null) {
            try {
                // 0 == continue, -1 = kill process immediately
                int res = mController.appEarlyNotResponding(app.processName, app.pid, annotation);
                if (res < 0 && app.pid != MY_PID) {
                    Process.killProcess(app.pid);
                    Process.killProcessGroup(app.info.uid, app.pid);
                }
            } catch (RemoteException e) {
                mController = null;
                Watchdog.getInstance().setActivityController(null);
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
            EventLog.writeEvent(EventLogTags.AM_ANR, app.userId, app.pid,
                    app.processName, app.info.flags, annotation);

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
        StringBuilder info = new StringBuilder();
        info.setLength(0);
        info.append("ANR in ").append(app.processName);
        if (activity != null && activity.shortComponentName != null) {
            info.append(" (").append(activity.shortComponentName).append(")");
        }
        info.append("\n");
        info.append("PID: ").append(app.pid).append("\n");
        if (annotation != null) {
            info.append("Reason: ").append(annotation).append("\n");
        }
        if (parent != null && parent != activity) {
            info.append("Parent: ").append(parent.shortComponentName).append("\n");
        }

        final ProcessCpuTracker processCpuTracker = new ProcessCpuTracker(true);

        File tracesFile = dumpStackTraces(true, firstPids, processCpuTracker, lastPids,
                NATIVE_STACKS_OF_INTEREST);

        String cpuInfo = null;
        if (MONITOR_CPU_USAGE) {
            updateCpuStatsNow();
            synchronized (mProcessCpuThread) {
                cpuInfo = mProcessCpuTracker.printCurrentState(anrTime);
            }
            info.append(processCpuTracker.printCurrentLoad());
            info.append(cpuInfo);
        }

        info.append(processCpuTracker.printCurrentState(anrTime));

        Slog.e(TAG, info.toString());
        if (tracesFile == null) {
            // There is no trace file, so dump (only) the alleged culprit's threads to the log
            Process.sendSignal(app.pid, Process.SIGNAL_QUIT);
        }

        addErrorToDropBox("anr", app, app.processName, activity, parent, annotation,
                cpuInfo, tracesFile, null);

        if (mController != null) {
            try {
                // 0 == show dialog, 1 = keep waiting, -1 = kill process immediately
                int res = mController.appNotResponding(app.processName, app.pid, info.toString());
                if (res != 0) {
                    if (res < 0 && app.pid != MY_PID) {
                        Process.killProcess(app.pid);
                        Process.killProcessGroup(app.info.uid, app.pid);
                    } else {
                        synchronized (this) {
                            mServices.scheduleServiceTimeoutLocked(app);
                        }
                    }
                    return;
                }
            } catch (RemoteException e) {
                mController = null;
                Watchdog.getInstance().setActivityController(null);
            }
        }

        // Unless configured otherwise, swallow ANRs in background processes & kill the process.
        boolean showBackground = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ANR_SHOW_BACKGROUND, 0) != 0;

        synchronized (this) {
            if (!showBackground && !app.isInterestingToUserLocked() && app.pid != MY_PID) {
                killUnneededProcessLocked(app, "background ANR");
                return;
            }

            // Set the app's notResponding state, and look up the errorReportReceiver
            makeAppNotRespondingLocked(app,
                    activity != null ? activity.shortComponentName : null,
                    annotation != null ? "ANR " + annotation : "ANR",
                    info.toString());

            // Bring up the infamous App Not Responding dialog
            Message msg = Message.obtain();
            HashMap<String, Object> map = new HashMap<String, Object>();
            msg.what = SHOW_NOT_RESPONDING_MSG;
            msg.obj = map;
            msg.arg1 = aboveSystem ? 1 : 0;
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

    @Override
    public boolean clearApplicationUserData(final String packageName,
            final IPackageDataObserver observer, int userId) {
        enforceNotIsolatedCaller("clearApplicationUserData");
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        userId = handleIncomingUser(pid, uid,
                userId, false, ALLOW_FULL_ONLY, "clearApplicationUserData", null);
        long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            int pkgUid = -1;
            synchronized(this) {
                try {
                    pkgUid = pm.getPackageUid(packageName, userId);
                } catch (RemoteException e) {
                }
                if (pkgUid == -1) {
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
                if (uid == pkgUid || checkComponentPermission(
                        android.Manifest.permission.CLEAR_APP_USER_DATA,
                        pid, uid, -1, true)
                        == PackageManager.PERMISSION_GRANTED) {
                    forceStopPackageLocked(packageName, pkgUid, "clear data");
                } else {
                    throw new SecurityException("PID " + pid + " does not have permission "
                            + android.Manifest.permission.CLEAR_APP_USER_DATA + " to clear data"
                                    + " of package " + packageName);
                }

                // Remove all tasks match the cleared application package and user
                for (int i = mRecentTasks.size() - 1; i >= 0; i--) {
                    final TaskRecord tr = mRecentTasks.get(i);
                    final String taskPackageName =
                            tr.getBaseIntent().getComponent().getPackageName();
                    if (tr.userId != userId) continue;
                    if (!taskPackageName.equals(packageName)) continue;
                    removeTaskByIdLocked(tr.taskId, 0);
                }
            }

            try {
                // Clear application user data
                pm.clearApplicationUserData(packageName, observer, userId);

                synchronized(this) {
                    // Remove all permissions granted from/to this package
                    removeUriPermissionsForPackageLocked(packageName, userId, true);
                }

                Intent intent = new Intent(Intent.ACTION_PACKAGE_DATA_CLEARED,
                        Uri.fromParts("package", packageName, null));
                intent.putExtra(Intent.EXTRA_UID, pkgUid);
                broadcastIntentInPackage("android", Process.SYSTEM_UID, intent,
                        null, null, 0, null, null, null, false, false, userId);
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

        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, true, ALLOW_FULL_ONLY, "killBackgroundProcesses", null);
        long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            synchronized(this) {
                int appId = -1;
                try {
                    appId = UserHandle.getAppId(pm.getPackageUid(packageName, 0));
                } catch (RemoteException e) {
                }
                if (appId == -1) {
                    Slog.w(TAG, "Invalid packageName: " + packageName);
                    return;
                }
                killPackageProcessesLocked(packageName, appId, userId,
                        ProcessList.SERVICE_ADJ, false, true, true, false, "kill background");
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void killAllBackgroundProcesses() {
        if (checkCallingPermission(android.Manifest.permission.KILL_BACKGROUND_PROCESSES)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: killAllBackgroundProcesses() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.KILL_BACKGROUND_PROCESSES;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        long callingId = Binder.clearCallingIdentity();
        try {
            synchronized(this) {
                ArrayList<ProcessRecord> procs = new ArrayList<ProcessRecord>();
                final int NP = mProcessNames.getMap().size();
                for (int ip=0; ip<NP; ip++) {
                    SparseArray<ProcessRecord> apps = mProcessNames.getMap().valueAt(ip);
                    final int NA = apps.size();
                    for (int ia=0; ia<NA; ia++) {
                        ProcessRecord app = apps.valueAt(ia);
                        if (app.persistent) {
                            // we don't kill persistent processes
                            continue;
                        }
                        if (app.removed) {
                            procs.add(app);
                        } else if (app.setAdj >= ProcessList.CACHED_APP_MIN_ADJ) {
                            app.removed = true;
                            procs.add(app);
                        }
                    }
                }

                int N = procs.size();
                for (int i=0; i<N; i++) {
                    removeProcessLocked(procs.get(i), false, true, "kill all background");
                }
                mAllowLowerMemLevel = true;
                updateOomAdjLocked();
                doLowMemReportIfNeededLocked(null);
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
        userId = handleIncomingUser(callingPid, Binder.getCallingUid(),
                userId, true, ALLOW_FULL_ONLY, "forceStopPackage", null);
        long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            synchronized(this) {
                int[] users = userId == UserHandle.USER_ALL
                        ? getUsersLocked() : new int[] { userId };
                for (int user : users) {
                    int pkgUid = -1;
                    try {
                        pkgUid = pm.getPackageUid(packageName, user);
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
                    if (isUserRunningLocked(user, false)) {
                        forceStopPackageLocked(packageName, pkgUid, "from pid " + callingPid);
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
            if (callingPid == Process.myPid()) {
                //  Yeah, um, no.
                Slog.w(TAG, "Can't addPackageDependency on system process");
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
    public void killApplicationWithAppId(String pkg, int appid, String reason) {
        if (pkg == null) {
            return;
        }
        // Make sure the uid is valid.
        if (appid < 0) {
            Slog.w(TAG, "Invalid appid specified for pkg : " + pkg);
            return;
        }
        int callerUid = Binder.getCallingUid();
        // Only the system server can kill an application
        if (callerUid == Process.SYSTEM_UID) {
            // Post an aysnc message to kill the application
            Message msg = mHandler.obtainMessage(KILL_APPLICATION_MSG);
            msg.arg1 = appid;
            msg.arg2 = 0;
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
        enforceNotIsolatedCaller("closeSystemDialogs");

        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                // Only allow this from foreground processes, so that background
                // applications can't abuse it to prevent system UI from being shown.
                if (uid >= Process.FIRST_APPLICATION_UID) {
                    ProcessRecord proc;
                    synchronized (mPidsSelfLocked) {
                        proc = mPidsSelfLocked.get(pid);
                    }
                    if (proc.curRawAdj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                        Slog.w(TAG, "Ignoring closeSystemDialogs " + reason
                                + " from background process " + proc);
                        return;
                    }
                }
                closeSystemDialogsLocked(reason);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void closeSystemDialogsLocked(String reason) {
        Intent intent = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                | Intent.FLAG_RECEIVER_FOREGROUND);
        if (reason != null) {
            intent.putExtra("reason", reason);
        }
        mWindowManager.closeSystemDialogs(reason);

        mStackSupervisor.closeSystemDialogsLocked();

        broadcastIntentLocked(null, null, intent, null,
                null, 0, null, null, null, AppOpsManager.OP_NONE, false, false, -1,
                Process.SYSTEM_UID, UserHandle.USER_ALL);
    }

    @Override
    public Debug.MemoryInfo[] getProcessMemoryInfo(int[] pids) {
        enforceNotIsolatedCaller("getProcessMemoryInfo");
        Debug.MemoryInfo[] infos = new Debug.MemoryInfo[pids.length];
        for (int i=pids.length-1; i>=0; i--) {
            ProcessRecord proc;
            int oomAdj;
            synchronized (this) {
                synchronized (mPidsSelfLocked) {
                    proc = mPidsSelfLocked.get(pids[i]);
                    oomAdj = proc != null ? proc.setAdj : 0;
                }
            }
            infos[i] = new Debug.MemoryInfo();
            Debug.getMemoryInfo(pids[i], infos[i]);
            if (proc != null) {
                synchronized (this) {
                    if (proc.thread != null && proc.setAdj == oomAdj) {
                        // Record this for posterity if the process has been stable.
                        proc.baseProcessTracker.addPss(infos[i].getTotalPss(),
                                infos[i].getTotalUss(), false, proc.pkgList);
                    }
                }
            }
        }
        return infos;
    }

    @Override
    public long[] getProcessPss(int[] pids) {
        enforceNotIsolatedCaller("getProcessPss");
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
            long[] tmpUss = new long[1];
            pss[i] = Debug.getPss(pids[i], tmpUss);
            if (proc != null) {
                synchronized (this) {
                    if (proc.thread != null && proc.setAdj == oomAdj) {
                        // Record this for posterity if the process has been stable.
                        proc.baseProcessTracker.addPss(pss[i], tmpUss[0], false, proc.pkgList);
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
        if (callerUid == Process.SYSTEM_UID) {
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

    private void forceStopPackageLocked(final String packageName, int uid, String reason) {
        forceStopPackageLocked(packageName, UserHandle.getAppId(uid), false,
                false, true, false, false, UserHandle.getUserId(uid), reason);
        Intent intent = new Intent(Intent.ACTION_PACKAGE_RESTARTED,
                Uri.fromParts("package", packageName, null));
        if (!mProcessesReady) {
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                    | Intent.FLAG_RECEIVER_FOREGROUND);
        }
        intent.putExtra(Intent.EXTRA_UID, uid);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, UserHandle.getUserId(uid));
        broadcastIntentLocked(null, null, intent,
                null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                false, false,
                MY_PID, Process.SYSTEM_UID, UserHandle.getUserId(uid));
    }

    private void forceStopUserLocked(int userId, String reason) {
        forceStopPackageLocked(null, -1, false, false, true, false, false, userId, reason);
        Intent intent = new Intent(Intent.ACTION_USER_STOPPED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                | Intent.FLAG_RECEIVER_FOREGROUND);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        broadcastIntentLocked(null, null, intent,
                null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                false, false,
                MY_PID, Process.SYSTEM_UID, UserHandle.USER_ALL);
    }

    private final boolean killPackageProcessesLocked(String packageName, int appId,
            int userId, int minOomAdj, boolean callerWillRestart, boolean allowRestart,
            boolean doit, boolean evenPersistent, String reason) {
        ArrayList<ProcessRecord> procs = new ArrayList<ProcessRecord>();

        // Remove all processes this package may have touched: all with the
        // same UID (except for the system or root user), and all whose name
        // matches the package name.
        final int NP = mProcessNames.getMap().size();
        for (int ip=0; ip<NP; ip++) {
            SparseArray<ProcessRecord> apps = mProcessNames.getMap().valueAt(ip);
            final int NA = apps.size();
            for (int ia=0; ia<NA; ia++) {
                ProcessRecord app = apps.valueAt(ia);
                if (app.persistent && !evenPersistent) {
                    // we don't kill persistent processes
                    continue;
                }
                if (app.removed) {
                    if (doit) {
                        procs.add(app);
                    }
                    continue;
                }

                // Skip process if it doesn't meet our oom adj requirement.
                if (app.setAdj < minOomAdj) {
                    continue;
                }

                // If no package is specified, we call all processes under the
                // give user id.
                if (packageName == null) {
                    if (app.userId != userId) {
                        continue;
                    }
                    if (appId >= 0 && UserHandle.getAppId(app.uid) != appId) {
                        continue;
                    }
                // Package has been specified, we want to hit all processes
                // that match it.  We need to qualify this by the processes
                // that are running under the specified app and user ID.
                } else {
                    final boolean isDep = app.pkgDeps != null
                            && app.pkgDeps.contains(packageName);
                    if (!isDep && UserHandle.getAppId(app.uid) != appId) {
                        continue;
                    }
                    if (userId != UserHandle.USER_ALL && app.userId != userId) {
                        continue;
                    }
                    if (!app.pkgList.containsKey(packageName) && !isDep) {
                        continue;
                    }
                }

                // Process has passed all conditions, kill it!
                if (!doit) {
                    return true;
                }
                app.removed = true;
                procs.add(app);
            }
        }

        int N = procs.size();
        for (int i=0; i<N; i++) {
            removeProcessLocked(procs.get(i), callerWillRestart, allowRestart, reason);
        }
        updateOomAdjLocked();
        return N > 0;
    }

    private final boolean forceStopPackageLocked(String name, int appId,
            boolean callerWillRestart, boolean purgeCache, boolean doit,
            boolean evenPersistent, boolean uninstalling, int userId, String reason) {
        int i;
        int N;

        if (userId == UserHandle.USER_ALL && name == null) {
            Slog.w(TAG, "Can't force stop all processes of all users, that is insane!");
        }

        if (appId < 0 && name != null) {
            try {
                appId = UserHandle.getAppId(
                        AppGlobals.getPackageManager().getPackageUid(name, 0));
            } catch (RemoteException e) {
            }
        }

        if (doit) {
            if (name != null) {
                Slog.i(TAG, "Force stopping " + name + " appid=" + appId
                        + " user=" + userId + ": " + reason);
            } else {
                Slog.i(TAG, "Force stopping u" + userId + ": " + reason);
            }

            final ArrayMap<String, SparseArray<Long>> pmap = mProcessCrashTimes.getMap();
            for (int ip=pmap.size()-1; ip>=0; ip--) {
                SparseArray<Long> ba = pmap.valueAt(ip);
                for (i=ba.size()-1; i>=0; i--) {
                    boolean remove = false;
                    final int entUid = ba.keyAt(i);
                    if (name != null) {
                        if (userId == UserHandle.USER_ALL) {
                            if (UserHandle.getAppId(entUid) == appId) {
                                remove = true;
                            }
                        } else {
                            if (entUid == UserHandle.getUid(userId, appId)) {
                                remove = true;
                            }
                        }
                    } else if (UserHandle.getUserId(entUid) == userId) {
                        remove = true;
                    }
                    if (remove) {
                        ba.removeAt(i);
                    }
                }
                if (ba.size() == 0) {
                    pmap.removeAt(ip);
                }
            }
        }

        boolean didSomething = killPackageProcessesLocked(name, appId, userId,
                -100, callerWillRestart, true, doit, evenPersistent,
                name == null ? ("stop user " + userId) : ("stop " + name));

        if (mStackSupervisor.forceStopPackageLocked(name, doit, evenPersistent, userId)) {
            if (!doit) {
                return true;
            }
            didSomething = true;
        }

        if (mServices.forceStopLocked(name, userId, evenPersistent, doit)) {
            if (!doit) {
                return true;
            }
            didSomething = true;
        }

        if (name == null) {
            // Remove all sticky broadcasts from this user.
            mStickyBroadcasts.remove(userId);
        }

        ArrayList<ContentProviderRecord> providers = new ArrayList<ContentProviderRecord>();
        if (mProviderMap.collectForceStopProviders(name, appId, doit, evenPersistent,
                userId, providers)) {
            if (!doit) {
                return true;
            }
            didSomething = true;
        }
        N = providers.size();
        for (i=0; i<N; i++) {
            removeDyingProviderLocked(null, providers.get(i), true);
        }

        // Remove transient permissions granted from/to this package/user
        removeUriPermissionsForPackageLocked(name, userId, false);

        if (name == null || uninstalling) {
            // Remove pending intents.  For now we only do this when force
            // stopping users, because we have some problems when doing this
            // for packages -- app widgets are not currently cleaned up for
            // such packages, so they can be left with bad pending intents.
            if (mIntentSenderRecords.size() > 0) {
                Iterator<WeakReference<PendingIntentRecord>> it
                        = mIntentSenderRecords.values().iterator();
                while (it.hasNext()) {
                    WeakReference<PendingIntentRecord> wpir = it.next();
                    if (wpir == null) {
                        it.remove();
                        continue;
                    }
                    PendingIntentRecord pir = wpir.get();
                    if (pir == null) {
                        it.remove();
                        continue;
                    }
                    if (name == null) {
                        // Stopping user, remove all objects for the user.
                        if (pir.key.userId != userId) {
                            // Not the same user, skip it.
                            continue;
                        }
                    } else {
                        if (UserHandle.getAppId(pir.uid) != appId) {
                            // Different app id, skip it.
                            continue;
                        }
                        if (userId != UserHandle.USER_ALL && pir.key.userId != userId) {
                            // Different user, skip it.
                            continue;
                        }
                        if (!pir.key.packageName.equals(name)) {
                            // Different package, skip it.
                            continue;
                        }
                    }
                    if (!doit) {
                        return true;
                    }
                    didSomething = true;
                    it.remove();
                    pir.canceled = true;
                    if (pir.key.activity != null) {
                        pir.key.activity.pendingResults.remove(pir.ref);
                    }
                }
            }
        }

        if (doit) {
            if (purgeCache && name != null) {
                AttributeCache ac = AttributeCache.instance();
                if (ac != null) {
                    ac.removePackage(name);
                }
            }
            if (mBooted) {
                mStackSupervisor.resumeTopActivitiesLocked();
                mStackSupervisor.scheduleIdleLocked();
            }
        }

        return didSomething;
    }

    private final boolean removeProcessLocked(ProcessRecord app,
            boolean callerWillRestart, boolean allowRestart, String reason) {
        final String name = app.processName;
        final int uid = app.uid;
        if (DEBUG_PROCESSES) Slog.d(
            TAG, "Force removing proc " + app.toShortString() + " (" + name
            + "/" + uid + ")");

        mProcessNames.remove(name, uid);
        mIsolatedProcesses.remove(app.uid);
        if (mHeavyWeightProcess == app) {
            mHandler.sendMessage(mHandler.obtainMessage(CANCEL_HEAVY_NOTIFICATION_MSG,
                    mHeavyWeightProcess.userId, 0));
            mHeavyWeightProcess = null;
        }
        boolean needRestart = false;
        if (app.pid > 0 && app.pid != MY_PID) {
            int pid = app.pid;
            synchronized (mPidsSelfLocked) {
                mPidsSelfLocked.remove(pid);
                mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);
            }
            mBatteryStatsService.noteProcessFinish(app.processName, app.info.uid);
            if (app.isolated) {
                mBatteryStatsService.removeIsolatedUid(app.uid, app.info.uid);
            }
            killUnneededProcessLocked(app, reason);
            Process.killProcessGroup(app.info.uid, app.pid);
            handleAppDiedLocked(app, true, allowRestart);
            removeLruProcessLocked(app);

            if (app.persistent && !app.isolated) {
                if (!callerWillRestart) {
                    addAppLocked(app.info, false, null /* ABI override */);
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
            EventLog.writeEvent(EventLogTags.AM_PROCESS_START_TIMEOUT, app.userId,
                    pid, app.uid, app.processName);
            mProcessNames.remove(app.processName, app.uid);
            mIsolatedProcesses.remove(app.uid);
            if (mHeavyWeightProcess == app) {
                mHandler.sendMessage(mHandler.obtainMessage(CANCEL_HEAVY_NOTIFICATION_MSG,
                        mHeavyWeightProcess.userId, 0));
                mHeavyWeightProcess = null;
            }
            mBatteryStatsService.noteProcessFinish(app.processName, app.info.uid);
            if (app.isolated) {
                mBatteryStatsService.removeIsolatedUid(app.uid, app.info.uid);
            }
            // Take care of any launching providers waiting for this process.
            checkAppInLaunchingProvidersLocked(app, true);
            // Take care of any services that are waiting for the process.
            mServices.processStartTimedOutLocked(app);
            killUnneededProcessLocked(app, "start timeout");
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
            if (isPendingBroadcastProcessLocked(pid)) {
                Slog.w(TAG, "Unattached app died before broadcast acknowledged, skipping");
                skipPendingBroadcastLocked(pid);
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
                //TODO: Process.killProcessGroup(app.info.uid, pid);
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

        final String processName = app.processName;
        try {
            AppDeathRecipient adr = new AppDeathRecipient(
                    app, pid, thread);
            thread.asBinder().linkToDeath(adr, 0);
            app.deathRecipient = adr;
        } catch (RemoteException e) {
            app.resetPackageList(mProcessStats);
            startProcessLocked(app, "link fail", processName);
            return false;
        }

        EventLog.writeEvent(EventLogTags.AM_PROC_BOUND, app.userId, app.pid, app.processName);

        app.makeActive(thread, mProcessStats);
        app.curAdj = app.setAdj = -100;
        app.curSchedGroup = app.setSchedGroup = Process.THREAD_GROUP_DEFAULT;
        app.forcingToForeground = null;
        updateProcessForegroundLocked(app, false, false);
        app.hasShownUi = false;
        app.debugging = false;
        app.cached = false;

        mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);

        boolean normalMode = mProcessesReady || isAllowedWhileBooting(app.info);
        List<ProviderInfo> providers = normalMode ? generateApplicationProvidersLocked(app) : null;

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
            String profileFile = app.instrumentationProfileFile;
            ParcelFileDescriptor profileFd = null;
            boolean profileAutoStop = false;
            if (mProfileApp != null && mProfileApp.equals(processName)) {
                mProfileProc = app;
                profileFile = mProfileFile;
                profileFd = mProfileFd;
                profileAutoStop = mAutoStopProfiler;
            }
            boolean enableOpenGlTrace = false;
            if (mOpenGlTraceApp != null && mOpenGlTraceApp.equals(processName)) {
                enableOpenGlTrace = true;
                mOpenGlTraceApp = null;
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
            if (profileFd != null) {
                profileFd = profileFd.dup();
            }
            thread.bindApplication(processName, appInfo, providers,
                    app.instrumentationClass, profileFile, profileFd, profileAutoStop,
                    app.instrumentationArguments, app.instrumentationWatcher,
                    app.instrumentationUiAutomationConnection, testMode, enableOpenGlTrace,
                    isRestrictedBackupMode || !normalMode, app.persistent,
                    new Configuration(mConfiguration), app.compat, getCommonServicesLocked(),
                    mCoreSettingsObserver.getCoreSettingsLocked());
            updateLruProcessLocked(app, false, null);
            app.lastRequestedGc = app.lastLowMemory = SystemClock.uptimeMillis();
        } catch (Exception e) {
            // todo: Yikes!  What should we do?  For now we will try to
            // start another process, but that could easily get us in
            // an infinite loop of restarting processes...
            Slog.w(TAG, "Exception thrown during bind!", e);

            app.resetPackageList(mProcessStats);
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
        if (normalMode) {
            try {
                if (mStackSupervisor.attachApplicationLocked(app)) {
                    didSomething = true;
                }
            } catch (Exception e) {
                badApp = true;
            }
        }

        // Find any services that should be running in this process...
        if (!badApp) {
            try {
                didSomething |= mServices.attachApplicationLocked(app, processName);
            } catch (Exception e) {
                badApp = true;
            }
        }

        // Check if a next-broadcast receiver is in this process...
        if (!badApp && isPendingBroadcastProcessLocked(pid)) {
            try {
                didSomething |= sendPendingBroadcastsLocked(app);
            } catch (Exception e) {
                // If the app died trying to launch the receiver we declare it 'bad'
                badApp = true;
            }
        }

        // Check whether the next backup agent is in this process...
        if (!badApp && mBackupTarget != null && mBackupTarget.appInfo.uid == app.uid) {
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

    @Override
    public final void attachApplication(IApplicationThread thread) {
        synchronized (this) {
            int callingPid = Binder.getCallingPid();
            final long origId = Binder.clearCallingIdentity();
            attachApplicationLocked(thread, callingPid);
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public final void activityIdle(IBinder token, Configuration config, boolean stopProfiling) {
        final long origId = Binder.clearCallingIdentity();
        synchronized (this) {
            ActivityStack stack = ActivityRecord.getStackLocked(token);
            if (stack != null) {
                ActivityRecord r =
                        mStackSupervisor.activityIdleInternalLocked(token, false, config);
                if (stopProfiling) {
                    if ((mProfileProc == r.app) && (mProfileFd != null)) {
                        try {
                            mProfileFd.close();
                        } catch (IOException e) {
                        }
                        clearProfilerLocked();
                    }
                }
            }
        }
        Binder.restoreCallingIdentity(origId);
    }

    void postEnableScreenAfterBootLocked() {
        mHandler.sendEmptyMessage(ENABLE_SCREEN_AFTER_BOOT_MSG);
    }

    void enableScreenAfterBoot() {
        EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_ENABLE_SCREEN,
                SystemClock.uptimeMillis());
        mWindowManager.enableScreenAfterBoot();

        synchronized (this) {
            updateEventDispatchingLocked();
        }
    }

    @Override
    public void showBootMessage(final CharSequence msg, final boolean always) {
        enforceNotIsolatedCaller("showBootMessage");
        mWindowManager.showBootMessage(msg, always);
    }

    @Override
    public void keyguardWaitingForActivityDrawn() {
        enforceNotIsolatedCaller("keyguardWaitingForActivityDrawn");
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                if (DEBUG_LOCKSCREEN) logLockScreen("");
                mWindowManager.keyguardWaitingForActivityDrawn();
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    final void finishBooting() {
        // Register receivers to handle package update events
        mPackageMonitor.register(mContext, Looper.getMainLooper(), false);

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
                    if (DEBUG_PROCESSES) Slog.v(TAG, "Starting process on hold: "
                            + procs.get(ip));
                    startProcessLocked(procs.get(ip), "on-hold", null);
                }
            }
            
            if (mFactoryTest != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
                // Start looking for apps that are abusing wake locks.
                Message nmsg = mHandler.obtainMessage(CHECK_EXCESSIVE_WAKE_LOCKS_MSG);
                mHandler.sendMessageDelayed(nmsg, POWER_CHECK_DELAY);
                // Tell anyone interested that we are done booting!
                SystemProperties.set("sys.boot_completed", "1");
                SystemProperties.set("dev.bootcomplete", "1");
                for (int i=0; i<mStartedUsers.size(); i++) {
                    UserStartedState uss = mStartedUsers.valueAt(i);
                    if (uss.mState == UserStartedState.STATE_BOOTING) {
                        uss.mState = UserStartedState.STATE_RUNNING;
                        final int userId = mStartedUsers.keyAt(i);
                        Intent intent = new Intent(Intent.ACTION_BOOT_COMPLETED, null);
                        intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
                        intent.addFlags(Intent.FLAG_RECEIVER_NO_ABORT);
                        broadcastIntentLocked(null, null, intent, null,
                                new IIntentReceiver.Stub() {
                                    @Override
                                    public void performReceive(Intent intent, int resultCode,
                                            String data, Bundle extras, boolean ordered,
                                            boolean sticky, int sendingUser) {
                                        synchronized (ActivityManagerService.this) {
                                            requestPssAllProcsLocked(SystemClock.uptimeMillis(),
                                                    true, false);
                                        }
                                    }
                                },
                                0, null, null,
                                android.Manifest.permission.RECEIVE_BOOT_COMPLETED,
                                AppOpsManager.OP_NONE, true, false, MY_PID, Process.SYSTEM_UID,
                                userId);
                    }
                }
                scheduleStartProfilesLocked();
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

    @Override
    public final void activityResumed(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        synchronized(this) {
            ActivityStack stack = ActivityRecord.getStackLocked(token);
            if (stack != null) {
                ActivityRecord.activityResumedLocked(token);
            }
        }
        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public final void activityPaused(IBinder token, PersistableBundle persistentState) {
        final long origId = Binder.clearCallingIdentity();
        synchronized(this) {
            ActivityStack stack = ActivityRecord.getStackLocked(token);
            if (stack != null) {
                stack.activityPausedLocked(token, false, persistentState);
            }
        }
        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public final void activityStopped(IBinder token, Bundle icicle,
            PersistableBundle persistentState, CharSequence description) {
        if (localLOGV) Slog.v(TAG, "Activity stopped: token=" + token);

        // Refuse possible leaked file descriptors
        if (icicle != null && icicle.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Bundle");
        }

        final long origId = Binder.clearCallingIdentity();

        synchronized (this) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r != null) {
                r.task.stack.activityStoppedLocked(r, icicle, persistentState, description);
            }
        }

        trimApplications();

        Binder.restoreCallingIdentity(origId);
    }

    @Override
    public final void activityDestroyed(IBinder token) {
        if (DEBUG_SWITCH) Slog.v(TAG, "ACTIVITY DESTROYED: " + token);
        synchronized (this) {
            ActivityStack stack = ActivityRecord.getStackLocked(token);
            if (stack != null) {
                stack.activityDestroyedLocked(token);
            }
        }
    }

    @Override
    public final void backgroundResourcesReleased(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                ActivityStack stack = ActivityRecord.getStackLocked(token);
                if (stack != null) {
                    stack.backgroundResourcesReleased(token);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public final void notifyLaunchTaskBehindComplete(IBinder token) {
        mStackSupervisor.scheduleLaunchTaskBehindComplete(token);
    }

    @Override
    public final void notifyEnterAnimationComplete(IBinder token) {
        mHandler.sendMessage(mHandler.obtainMessage(ENTER_ANIMATION_COMPLETE_MSG, token));
    }

    @Override
    public String getCallingPackage(IBinder token) {
        synchronized (this) {
            ActivityRecord r = getCallingRecordLocked(token);
            return r != null ? r.info.packageName : null;
        }
    }

    @Override
    public ComponentName getCallingActivity(IBinder token) {
        synchronized (this) {
            ActivityRecord r = getCallingRecordLocked(token);
            return r != null ? r.intent.getComponent() : null;
        }
    }

    private ActivityRecord getCallingRecordLocked(IBinder token) {
        ActivityRecord r = ActivityRecord.isInStackLocked(token);
        if (r == null) {
            return null;
        }
        return r.resultTo;
    }

    @Override
    public ComponentName getActivityClassForToken(IBinder token) {
        synchronized(this) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return null;
            }
            return r.intent.getComponent();
        }
    }

    @Override
    public String getPackageForToken(IBinder token) {
        synchronized(this) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                return null;
            }
            return r.packageName;
        }
    }

    @Override
    public IIntentSender getIntentSender(int type,
            String packageName, IBinder token, String resultWho,
            int requestCode, Intent[] intents, String[] resolvedTypes,
            int flags, Bundle options, int userId) {
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
        if (options != null) {
            if (options.hasFileDescriptors()) {
                throw new IllegalArgumentException("File descriptors passed in options");
            }
        }
        
        synchronized(this) {
            int callingUid = Binder.getCallingUid();
            int origUserId = userId;
            userId = handleIncomingUser(Binder.getCallingPid(), callingUid, userId,
                    type == ActivityManager.INTENT_SENDER_BROADCAST,
                    ALLOW_NON_FULL, "getIntentSender", null);
            if (origUserId == UserHandle.USER_CURRENT) {
                // We don't want to evaluate this until the pending intent is
                // actually executed.  However, we do want to always do the
                // security checking for it above.
                userId = UserHandle.USER_CURRENT;
            }
            try {
                if (callingUid != 0 && callingUid != Process.SYSTEM_UID) {
                    int uid = AppGlobals.getPackageManager()
                            .getPackageUid(packageName, UserHandle.getUserId(callingUid));
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

                return getIntentSenderLocked(type, packageName, callingUid, userId,
                        token, resultWho, requestCode, intents, resolvedTypes, flags, options);
                
            } catch (RemoteException e) {
                throw new SecurityException(e);
            }
        }
    }

    IIntentSender getIntentSenderLocked(int type, String packageName,
            int callingUid, int userId, IBinder token, String resultWho,
            int requestCode, Intent[] intents, String[] resolvedTypes, int flags,
            Bundle options) {
        if (DEBUG_MU)
            Slog.v(TAG_MU, "getIntentSenderLocked(): uid=" + callingUid);
        ActivityRecord activity = null;
        if (type == ActivityManager.INTENT_SENDER_ACTIVITY_RESULT) {
            activity = ActivityRecord.isInStackLocked(token);
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
                requestCode, intents, resolvedTypes, flags, options, userId);
        WeakReference<PendingIntentRecord> ref;
        ref = mIntentSenderRecords.get(key);
        PendingIntentRecord rec = ref != null ? ref.get() : null;
        if (rec != null) {
            if (!cancelCurrent) {
                if (updateCurrent) {
                    if (rec.key.requestIntent != null) {
                        rec.key.requestIntent.replaceExtras(intents != null ?
                                intents[intents.length - 1] : null);
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
        if (type == ActivityManager.INTENT_SENDER_ACTIVITY_RESULT) {
            if (activity.pendingResults == null) {
                activity.pendingResults
                        = new HashSet<WeakReference<PendingIntentRecord>>();
            }
            activity.pendingResults.add(rec.ref);
        }
        return rec;
    }

    @Override
    public void cancelIntentSender(IIntentSender sender) {
        if (!(sender instanceof PendingIntentRecord)) {
            return;
        }
        synchronized(this) {
            PendingIntentRecord rec = (PendingIntentRecord)sender;
            try {
                int uid = AppGlobals.getPackageManager()
                        .getPackageUid(rec.key.packageName, UserHandle.getCallingUserId());
                if (!UserHandle.isSameApp(uid, Binder.getCallingUid())) {
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
    public Intent getIntentForIntentSender(IIntentSender pendingResult) {
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
            Intent intent = res.key.requestIntent;
            if (intent != null) {
                if (res.lastTag != null && res.lastTagPrefix == prefix && (res.lastTagPrefix == null
                        || res.lastTagPrefix.equals(prefix))) {
                    return res.lastTag;
                }
                res.lastTagPrefix = prefix;
                StringBuilder sb = new StringBuilder(128);
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
        } catch (ClassCastException e) {
        }
        return null;
    }

    @Override
    public void setProcessLimit(int max) {
        enforceCallingPermission(android.Manifest.permission.SET_PROCESS_LIMIT,
                "setProcessLimit()");
        synchronized (this) {
            mProcessLimit = max < 0 ? ProcessList.MAX_CACHED_APPS : max;
            mProcessLimitOverride = max;
        }
        trimApplications();
    }

    @Override
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
                updateProcessForegroundLocked(pr, false, false);
            }
            updateOomAdjLocked();
        }
    }

    @Override
    public void setProcessForeground(IBinder token, int pid, boolean isForeground) {
        enforceCallingPermission(android.Manifest.permission.SET_PROCESS_LIMIT,
                "setProcessForeground()");
        synchronized(this) {
            boolean changed = false;
            
            synchronized (mPidsSelfLocked) {
                ProcessRecord pr = mPidsSelfLocked.get(pid);
                if (pr == null && isForeground) {
                    Slog.w(TAG, "setProcessForeground called on unknown pid: " + pid);
                    return;
                }
                ForegroundToken oldToken = mForegroundProcesses.get(pid);
                if (oldToken != null) {
                    oldToken.token.unlinkToDeath(oldToken, 0);
                    mForegroundProcesses.remove(pid);
                    if (pr != null) {
                        pr.forcingToForeground = null;
                    }
                    changed = true;
                }
                if (isForeground && token != null) {
                    ForegroundToken newToken = new ForegroundToken() {
                        @Override
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

        @Override
        public boolean checkPermission(String permission, int pid, int uid) {
            return mActivityManagerService.checkPermission(permission, pid,
                    uid) == PackageManager.PERMISSION_GRANTED;
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
        return checkComponentPermission(permission, pid, UserHandle.getAppId(uid), -1, true);
    }

    /**
     * Binder IPC calls go through the public entry point.
     * This can be called with or without the global lock held.
     */
    int checkCallingPermission(String permission) {
        return checkPermission(permission,
                Binder.getCallingPid(),
                UserHandle.getAppId(Binder.getCallingUid()));
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
     * Determine if UID is holding permissions required to access {@link Uri} in
     * the given {@link ProviderInfo}. Final permission checking is always done
     * in {@link ContentProvider}.
     */
    private final boolean checkHoldingPermissionsLocked(
            IPackageManager pm, ProviderInfo pi, GrantUri grantUri, int uid, final int modeFlags) {
        if (DEBUG_URI_PERMISSION) Slog.v(TAG,
                "checkHoldingPermissionsLocked: uri=" + grantUri + " uid=" + uid);
        if (UserHandle.getUserId(uid) != grantUri.sourceUserId) {
            if (ActivityManager.checkComponentPermission(INTERACT_ACROSS_USERS, uid, -1, true)
                    != PERMISSION_GRANTED) {
                return false;
            }
        }
        return checkHoldingPermissionsInternalLocked(pm, pi, grantUri, uid, modeFlags, true);
    }

    private final boolean checkHoldingPermissionsInternalLocked(IPackageManager pm, ProviderInfo pi,
            GrantUri grantUri, int uid, final int modeFlags, boolean considerUidPermissions) {
        if (pi.applicationInfo.uid == uid) {
            return true;
        } else if (!pi.exported) {
            return false;
        }

        boolean readMet = (modeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0;
        boolean writeMet = (modeFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0;
        try {
            // check if target holds top-level <provider> permissions
            if (!readMet && pi.readPermission != null && considerUidPermissions
                    && (pm.checkUidPermission(pi.readPermission, uid) == PERMISSION_GRANTED)) {
                readMet = true;
            }
            if (!writeMet && pi.writePermission != null && considerUidPermissions
                    && (pm.checkUidPermission(pi.writePermission, uid) == PERMISSION_GRANTED)) {
                writeMet = true;
            }

            // track if unprotected read/write is allowed; any denied
            // <path-permission> below removes this ability
            boolean allowDefaultRead = pi.readPermission == null;
            boolean allowDefaultWrite = pi.writePermission == null;

            // check if target holds any <path-permission> that match uri
            final PathPermission[] pps = pi.pathPermissions;
            if (pps != null) {
                final String path = grantUri.uri.getPath();
                int i = pps.length;
                while (i > 0 && (!readMet || !writeMet)) {
                    i--;
                    PathPermission pp = pps[i];
                    if (pp.match(path)) {
                        if (!readMet) {
                            final String pprperm = pp.getReadPermission();
                            if (DEBUG_URI_PERMISSION) Slog.v(TAG, "Checking read perm for "
                                    + pprperm + " for " + pp.getPath()
                                    + ": match=" + pp.match(path)
                                    + " check=" + pm.checkUidPermission(pprperm, uid));
                            if (pprperm != null) {
                                if (considerUidPermissions && pm.checkUidPermission(pprperm, uid)
                                        == PERMISSION_GRANTED) {
                                    readMet = true;
                                } else {
                                    allowDefaultRead = false;
                                }
                            }
                        }
                        if (!writeMet) {
                            final String ppwperm = pp.getWritePermission();
                            if (DEBUG_URI_PERMISSION) Slog.v(TAG, "Checking write perm "
                                    + ppwperm + " for " + pp.getPath()
                                    + ": match=" + pp.match(path)
                                    + " check=" + pm.checkUidPermission(ppwperm, uid));
                            if (ppwperm != null) {
                                if (considerUidPermissions && pm.checkUidPermission(ppwperm, uid)
                                        == PERMISSION_GRANTED) {
                                    writeMet = true;
                                } else {
                                    allowDefaultWrite = false;
                                }
                            }
                        }
                    }
                }
            }

            // grant unprotected <provider> read/write, if not blocked by
            // <path-permission> above
            if (allowDefaultRead) readMet = true;
            if (allowDefaultWrite) writeMet = true;

        } catch (RemoteException e) {
            return false;
        }

        return readMet && writeMet;
    }

    private ProviderInfo getProviderInfoLocked(String authority, int userHandle) {
        ProviderInfo pi = null;
        ContentProviderRecord cpr = mProviderMap.getProviderByName(authority, userHandle);
        if (cpr != null) {
            pi = cpr.info;
        } else {
            try {
                pi = AppGlobals.getPackageManager().resolveContentProvider(
                        authority, PackageManager.GET_URI_PERMISSION_PATTERNS, userHandle);
            } catch (RemoteException ex) {
            }
        }
        return pi;
    }

    private UriPermission findUriPermissionLocked(int targetUid, GrantUri grantUri) {
        final ArrayMap<GrantUri, UriPermission> targetUris = mGrantedUriPermissions.get(targetUid);
        if (targetUris != null) {
            return targetUris.get(grantUri);
        }
        return null;
    }

    private UriPermission findOrCreateUriPermissionLocked(String sourcePkg,
            String targetPkg, int targetUid, GrantUri grantUri) {
        ArrayMap<GrantUri, UriPermission> targetUris = mGrantedUriPermissions.get(targetUid);
        if (targetUris == null) {
            targetUris = Maps.newArrayMap();
            mGrantedUriPermissions.put(targetUid, targetUris);
        }

        UriPermission perm = targetUris.get(grantUri);
        if (perm == null) {
            perm = new UriPermission(sourcePkg, targetPkg, targetUid, grantUri);
            targetUris.put(grantUri, perm);
        }

        return perm;
    }

    private final boolean checkUriPermissionLocked(GrantUri grantUri, int uid,
            final int modeFlags) {
        final boolean persistable = (modeFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0;
        final int minStrength = persistable ? UriPermission.STRENGTH_PERSISTABLE
                : UriPermission.STRENGTH_OWNED;

        // Root gets to do everything.
        if (uid == 0) {
            return true;
        }

        final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.get(uid);
        if (perms == null) return false;

        // First look for exact match
        final UriPermission exactPerm = perms.get(grantUri);
        if (exactPerm != null && exactPerm.getStrength(modeFlags) >= minStrength) {
            return true;
        }

        // No exact match, look for prefixes
        final int N = perms.size();
        for (int i = 0; i < N; i++) {
            final UriPermission perm = perms.valueAt(i);
            if (perm.uri.prefix && grantUri.uri.isPathPrefixMatch(perm.uri.uri)
                    && perm.getStrength(modeFlags) >= minStrength) {
                return true;
            }
        }

        return false;
    }

    @Override
    public int checkUriPermission(Uri uri, int pid, int uid,
            final int modeFlags, int userId) {
        enforceNotIsolatedCaller("checkUriPermission");

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
        synchronized (this) {
            return checkUriPermissionLocked(new GrantUri(userId, uri, false), uid, modeFlags)
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
     * If you already know the uid of the target, you can supply it in
     * lastTargetUid else set that to -1.
     */
    int checkGrantUriPermissionLocked(int callingUid, String targetPkg, GrantUri grantUri,
            final int modeFlags, int lastTargetUid) {
        if (!Intent.isAccessUriMode(modeFlags)) {
            return -1;
        }

        if (targetPkg != null) {
            if (DEBUG_URI_PERMISSION) Slog.v(TAG,
                    "Checking grant " + targetPkg + " permission to " + grantUri);
        }
        
        final IPackageManager pm = AppGlobals.getPackageManager();

        // If this is not a content: uri, we can't do anything with it.
        if (!ContentResolver.SCHEME_CONTENT.equals(grantUri.uri.getScheme())) {
            if (DEBUG_URI_PERMISSION) Slog.v(TAG, 
                    "Can't grant URI permission for non-content URI: " + grantUri);
            return -1;
        }

        final String authority = grantUri.uri.getAuthority();
        final ProviderInfo pi = getProviderInfoLocked(authority, grantUri.sourceUserId);
        if (pi == null) {
            Slog.w(TAG, "No content provider found for permission check: " +
                    grantUri.uri.toSafeString());
            return -1;
        }

        int targetUid = lastTargetUid;
        if (targetUid < 0 && targetPkg != null) {
            try {
                targetUid = pm.getPackageUid(targetPkg, UserHandle.getUserId(callingUid));
                if (targetUid < 0) {
                    if (DEBUG_URI_PERMISSION) Slog.v(TAG,
                            "Can't grant URI permission no uid for: " + targetPkg);
                    return -1;
                }
            } catch (RemoteException ex) {
                return -1;
            }
        }

        if (targetUid >= 0) {
            // First...  does the target actually need this permission?
            if (checkHoldingPermissionsLocked(pm, pi, grantUri, targetUid, modeFlags)) {
                // No need to grant the target this permission.
                if (DEBUG_URI_PERMISSION) Slog.v(TAG,
                        "Target " + targetPkg + " already has full permission to " + grantUri);
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

        /* There is a special cross user grant if:
         * - The target is on another user.
         * - Apps on the current user can access the uri without any uid permissions.
         * In this case, we grant a uri permission, even if the ContentProvider does not normally
         * grant uri permissions.
         */
        boolean specialCrossUserGrant = UserHandle.getUserId(targetUid) != grantUri.sourceUserId
                && checkHoldingPermissionsInternalLocked(pm, pi, grantUri, callingUid,
                modeFlags, false /*without considering the uid permissions*/);

        // Second...  is the provider allowing granting of URI permissions?
        if (!specialCrossUserGrant) {
            if (!pi.grantUriPermissions) {
                throw new SecurityException("Provider " + pi.packageName
                        + "/" + pi.name
                        + " does not allow granting of Uri permissions (uri "
                        + grantUri + ")");
            }
            if (pi.uriPermissionPatterns != null) {
                final int N = pi.uriPermissionPatterns.length;
                boolean allowed = false;
                for (int i=0; i<N; i++) {
                    if (pi.uriPermissionPatterns[i] != null
                            && pi.uriPermissionPatterns[i].match(grantUri.uri.getPath())) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) {
                    throw new SecurityException("Provider " + pi.packageName
                            + "/" + pi.name
                            + " does not allow granting of permission to path of Uri "
                            + grantUri);
                }
            }
        }

        // Third...  does the caller itself have permission to access
        // this uri?
        if (UserHandle.getAppId(callingUid) != Process.SYSTEM_UID) {
            if (!checkHoldingPermissionsLocked(pm, pi, grantUri, callingUid, modeFlags)) {
                // Require they hold a strong enough Uri permission
                if (!checkUriPermissionLocked(grantUri, callingUid, modeFlags)) {
                    throw new SecurityException("Uid " + callingUid
                            + " does not have permission to uri " + grantUri);
                }
            }
        }
        return targetUid;
    }

    @Override
    public int checkGrantUriPermission(int callingUid, String targetPkg, Uri uri,
            final int modeFlags, int userId) {
        enforceNotIsolatedCaller("checkGrantUriPermission");
        synchronized(this) {
            return checkGrantUriPermissionLocked(callingUid, targetPkg,
                    new GrantUri(userId, uri, false), modeFlags, -1);
        }
    }

    void grantUriPermissionUncheckedLocked(int targetUid, String targetPkg, GrantUri grantUri,
            final int modeFlags, UriPermissionOwner owner) {
        if (!Intent.isAccessUriMode(modeFlags)) {
            return;
        }

        // So here we are: the caller has the assumed permission
        // to the uri, and the target doesn't.  Let's now give this to
        // the target.

        if (DEBUG_URI_PERMISSION) Slog.v(TAG, 
                "Granting " + targetPkg + "/" + targetUid + " permission to " + grantUri);

        final String authority = grantUri.uri.getAuthority();
        final ProviderInfo pi = getProviderInfoLocked(authority, grantUri.sourceUserId);
        if (pi == null) {
            Slog.w(TAG, "No content provider found for grant: " + grantUri.toSafeString());
            return;
        }

        if ((modeFlags & Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) != 0) {
            grantUri.prefix = true;
        }
        final UriPermission perm = findOrCreateUriPermissionLocked(
                pi.packageName, targetPkg, targetUid, grantUri);
        perm.grantModes(modeFlags, owner);
    }

    void grantUriPermissionLocked(int callingUid, String targetPkg, GrantUri grantUri,
            final int modeFlags, UriPermissionOwner owner, int targetUserId) {
        if (targetPkg == null) {
            throw new NullPointerException("targetPkg");
        }
        int targetUid;
        final IPackageManager pm = AppGlobals.getPackageManager();
        try {
            targetUid = pm.getPackageUid(targetPkg, targetUserId);
        } catch (RemoteException ex) {
            return;
        }

        targetUid = checkGrantUriPermissionLocked(callingUid, targetPkg, grantUri, modeFlags,
                targetUid);
        if (targetUid < 0) {
            return;
        }

        grantUriPermissionUncheckedLocked(targetUid, targetPkg, grantUri, modeFlags,
                owner);
    }

    static class NeededUriGrants extends ArrayList<GrantUri> {
        final String targetPkg;
        final int targetUid;
        final int flags;

        NeededUriGrants(String targetPkg, int targetUid, int flags) {
            this.targetPkg = targetPkg;
            this.targetUid = targetUid;
            this.flags = flags;
        }
    }

    /**
     * Like checkGrantUriPermissionLocked, but takes an Intent.
     */
    NeededUriGrants checkGrantUriPermissionFromIntentLocked(int callingUid,
            String targetPkg, Intent intent, int mode, NeededUriGrants needed, int targetUserId) {
        if (DEBUG_URI_PERMISSION) Slog.v(TAG,
                "Checking URI perm to data=" + (intent != null ? intent.getData() : null)
                + " clip=" + (intent != null ? intent.getClipData() : null)
                + " from " + intent + "; flags=0x"
                + Integer.toHexString(intent != null ? intent.getFlags() : 0));

        if (targetPkg == null) {
            throw new NullPointerException("targetPkg");
        }

        if (intent == null) {
            return null;
        }
        Uri data = intent.getData();
        ClipData clip = intent.getClipData();
        if (data == null && clip == null) {
            return null;
        }
        // Default userId for uris in the intent (if they don't specify it themselves)
        int contentUserHint = intent.getContentUserHint();
        if (contentUserHint == UserHandle.USER_CURRENT) {
            contentUserHint = UserHandle.getUserId(callingUid);
        }
        final IPackageManager pm = AppGlobals.getPackageManager();
        int targetUid;
        if (needed != null) {
            targetUid = needed.targetUid;
        } else {
            try {
                targetUid = pm.getPackageUid(targetPkg, targetUserId);
            } catch (RemoteException ex) {
                return null;
            }
            if (targetUid < 0) {
                if (DEBUG_URI_PERMISSION) {
                    Slog.v(TAG, "Can't grant URI permission no uid for: " + targetPkg
                            + " on user " + targetUserId);
                }
                return null;
            }
        }
        if (data != null) {
            GrantUri grantUri = GrantUri.resolve(contentUserHint, data);
            targetUid = checkGrantUriPermissionLocked(callingUid, targetPkg, grantUri, mode,
                    targetUid);
            if (targetUid > 0) {
                if (needed == null) {
                    needed = new NeededUriGrants(targetPkg, targetUid, mode);
                }
                needed.add(grantUri);
            }
        }
        if (clip != null) {
            for (int i=0; i<clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) {
                    GrantUri grantUri = GrantUri.resolve(contentUserHint, uri);
                    targetUid = checkGrantUriPermissionLocked(callingUid, targetPkg, grantUri, mode,
                            targetUid);
                    if (targetUid > 0) {
                        if (needed == null) {
                            needed = new NeededUriGrants(targetPkg, targetUid, mode);
                        }
                        needed.add(grantUri);
                    }
                } else {
                    Intent clipIntent = clip.getItemAt(i).getIntent();
                    if (clipIntent != null) {
                        NeededUriGrants newNeeded = checkGrantUriPermissionFromIntentLocked(
                                callingUid, targetPkg, clipIntent, mode, needed, targetUserId);
                        if (newNeeded != null) {
                            needed = newNeeded;
                        }
                    }
                }
            }
        }

        return needed;
    }

    /**
     * Like grantUriPermissionUncheckedLocked, but takes an Intent.
     */
    void grantUriPermissionUncheckedFromIntentLocked(NeededUriGrants needed,
            UriPermissionOwner owner) {
        if (needed != null) {
            for (int i=0; i<needed.size(); i++) {
                GrantUri grantUri = needed.get(i);
                grantUriPermissionUncheckedLocked(needed.targetUid, needed.targetPkg,
                        grantUri, needed.flags, owner);
            }
        }
    }

    void grantUriPermissionFromIntentLocked(int callingUid,
            String targetPkg, Intent intent, UriPermissionOwner owner, int targetUserId) {
        NeededUriGrants needed = checkGrantUriPermissionFromIntentLocked(callingUid, targetPkg,
                intent, intent != null ? intent.getFlags() : 0, null, targetUserId);
        if (needed == null) {
            return;
        }

        grantUriPermissionUncheckedFromIntentLocked(needed, owner);
    }

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

            grantUriPermissionLocked(r.uid, targetPkg, grantUri, modeFlags, null,
                    UserHandle.getUserId(r.uid));
        }
    }

    void removeUriPermissionIfNeededLocked(UriPermission perm) {
        if (perm.modeFlags == 0) {
            final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.get(
                    perm.targetUid);
            if (perms != null) {
                if (DEBUG_URI_PERMISSION) Slog.v(TAG, 
                        "Removing " + perm.targetUid + " permission to " + perm.uri);

                perms.remove(perm.uri);
                if (perms.isEmpty()) {
                    mGrantedUriPermissions.remove(perm.targetUid);
                }
            }
        }
    }

    private void revokeUriPermissionLocked(int callingUid, GrantUri grantUri, final int modeFlags) {
        if (DEBUG_URI_PERMISSION) Slog.v(TAG, "Revoking all granted permissions to " + grantUri);

        final IPackageManager pm = AppGlobals.getPackageManager();
        final String authority = grantUri.uri.getAuthority();
        final ProviderInfo pi = getProviderInfoLocked(authority, grantUri.sourceUserId);
        if (pi == null) {
            Slog.w(TAG, "No content provider found for permission revoke: "
                    + grantUri.toSafeString());
            return;
        }

        // Does the caller have this permission on the URI?
        if (!checkHoldingPermissionsLocked(pm, pi, grantUri, callingUid, modeFlags)) {
            // Right now, if you are not the original owner of the permission,
            // you are not allowed to revoke it.
            //if (!checkUriPermissionLocked(uri, callingUid, modeFlags)) {
                throw new SecurityException("Uid " + callingUid
                        + " does not have permission to uri " + grantUri);
            //}
        }

        boolean persistChanged = false;

        // Go through all of the permissions and remove any that match.
        int N = mGrantedUriPermissions.size();
        for (int i = 0; i < N; i++) {
            final int targetUid = mGrantedUriPermissions.keyAt(i);
            final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.valueAt(i);

            for (Iterator<UriPermission> it = perms.values().iterator(); it.hasNext();) {
                final UriPermission perm = it.next();
                if (perm.uri.sourceUserId == grantUri.sourceUserId
                        && perm.uri.uri.isPathPrefixMatch(grantUri.uri)) {
                    if (DEBUG_URI_PERMISSION)
                        Slog.v(TAG,
                                "Revoking " + perm.targetUid + " permission to " + perm.uri);
                    persistChanged |= perm.revokeModes(
                            modeFlags | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    if (perm.modeFlags == 0) {
                        it.remove();
                    }
                }
            }

            if (perms.isEmpty()) {
                mGrantedUriPermissions.remove(targetUid);
                N--;
                i--;
            }
        }

        if (persistChanged) {
            schedulePersistUriGrants();
        }
    }

    @Override
    public void revokeUriPermission(IApplicationThread caller, Uri uri, final int modeFlags,
            int userId) {
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

            final IPackageManager pm = AppGlobals.getPackageManager();
            final String authority = uri.getAuthority();
            final ProviderInfo pi = getProviderInfoLocked(authority, userId);
            if (pi == null) {
                Slog.w(TAG, "No content provider found for permission revoke: "
                        + uri.toSafeString());
                return;
            }

            revokeUriPermissionLocked(r.uid, new GrantUri(userId, uri, false), modeFlags);
        }
    }

    /**
     * Remove any {@link UriPermission} granted <em>from</em> or <em>to</em> the
     * given package.
     *
     * @param packageName Package name to match, or {@code null} to apply to all
     *            packages.
     * @param userHandle User to match, or {@link UserHandle#USER_ALL} to apply
     *            to all users.
     * @param persistable If persistable grants should be removed.
     */
    private void removeUriPermissionsForPackageLocked(
            String packageName, int userHandle, boolean persistable) {
        if (userHandle == UserHandle.USER_ALL && packageName == null) {
            throw new IllegalArgumentException("Must narrow by either package or user");
        }

        boolean persistChanged = false;

        int N = mGrantedUriPermissions.size();
        for (int i = 0; i < N; i++) {
            final int targetUid = mGrantedUriPermissions.keyAt(i);
            final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.valueAt(i);

            // Only inspect grants matching user
            if (userHandle == UserHandle.USER_ALL
                    || userHandle == UserHandle.getUserId(targetUid)) {
                for (Iterator<UriPermission> it = perms.values().iterator(); it.hasNext();) {
                    final UriPermission perm = it.next();

                    // Only inspect grants matching package
                    if (packageName == null || perm.sourcePkg.equals(packageName)
                            || perm.targetPkg.equals(packageName)) {
                        persistChanged |= perm.revokeModes(
                                persistable ? ~0 : ~Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

                        // Only remove when no modes remain; any persisted grants
                        // will keep this alive.
                        if (perm.modeFlags == 0) {
                            it.remove();
                        }
                    }
                }

                if (perms.isEmpty()) {
                    mGrantedUriPermissions.remove(targetUid);
                    N--;
                    i--;
                }
            }
        }

        if (persistChanged) {
            schedulePersistUriGrants();
        }
    }

    @Override
    public IBinder newUriPermissionOwner(String name) {
        enforceNotIsolatedCaller("newUriPermissionOwner");
        synchronized(this) {
            UriPermissionOwner owner = new UriPermissionOwner(this, name);
            return owner.getExternalTokenLocked();
        }
    }

    @Override
    public void grantUriPermissionFromOwner(IBinder token, int fromUid, String targetPkg, Uri uri,
            final int modeFlags, int sourceUserId, int targetUserId) {
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

            grantUriPermissionLocked(fromUid, targetPkg, new GrantUri(sourceUserId, uri, false),
                    modeFlags, owner, targetUserId);
        }
    }

    @Override
    public void revokeUriPermissionFromOwner(IBinder token, Uri uri, int mode, int userId) {
        synchronized(this) {
            UriPermissionOwner owner = UriPermissionOwner.fromExternalToken(token);
            if (owner == null) {
                throw new IllegalArgumentException("Unknown owner: " + token);
            }

            if (uri == null) {
                owner.removeUriPermissionsLocked(mode);
            } else {
                owner.removeUriPermissionLocked(new GrantUri(userId, uri, false), mode);
            }
        }
    }

    private void schedulePersistUriGrants() {
        if (!mHandler.hasMessages(PERSIST_URI_GRANTS_MSG)) {
            mHandler.sendMessageDelayed(mHandler.obtainMessage(PERSIST_URI_GRANTS_MSG),
                    10 * DateUtils.SECOND_IN_MILLIS);
        }
    }

    private void writeGrantedUriPermissions() {
        if (DEBUG_URI_PERMISSION) Slog.v(TAG, "writeGrantedUriPermissions()");

        // Snapshot permissions so we can persist without lock
        ArrayList<UriPermission.Snapshot> persist = Lists.newArrayList();
        synchronized (this) {
            final int size = mGrantedUriPermissions.size();
            for (int i = 0; i < size; i++) {
                final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.valueAt(i);
                for (UriPermission perm : perms.values()) {
                    if (perm.persistedModeFlags != 0) {
                        persist.add(perm.snapshot());
                    }
                }
            }
        }

        FileOutputStream fos = null;
        try {
            fos = mGrantFile.startWrite();

            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, "utf-8");
            out.startDocument(null, true);
            out.startTag(null, TAG_URI_GRANTS);
            for (UriPermission.Snapshot perm : persist) {
                out.startTag(null, TAG_URI_GRANT);
                writeIntAttribute(out, ATTR_SOURCE_USER_ID, perm.uri.sourceUserId);
                writeIntAttribute(out, ATTR_TARGET_USER_ID, perm.targetUserId);
                out.attribute(null, ATTR_SOURCE_PKG, perm.sourcePkg);
                out.attribute(null, ATTR_TARGET_PKG, perm.targetPkg);
                out.attribute(null, ATTR_URI, String.valueOf(perm.uri.uri));
                writeBooleanAttribute(out, ATTR_PREFIX, perm.uri.prefix);
                writeIntAttribute(out, ATTR_MODE_FLAGS, perm.persistedModeFlags);
                writeLongAttribute(out, ATTR_CREATED_TIME, perm.persistedCreateTime);
                out.endTag(null, TAG_URI_GRANT);
            }
            out.endTag(null, TAG_URI_GRANTS);
            out.endDocument();

            mGrantFile.finishWrite(fos);
        } catch (IOException e) {
            if (fos != null) {
                mGrantFile.failWrite(fos);
            }
        }
    }

    private void readGrantedUriPermissionsLocked() {
        if (DEBUG_URI_PERMISSION) Slog.v(TAG, "readGrantedUriPermissions()");

        final long now = System.currentTimeMillis();

        FileInputStream fis = null;
        try {
            fis = mGrantFile.openRead();
            final XmlPullParser in = Xml.newPullParser();
            in.setInput(fis, null);

            int type;
            while ((type = in.next()) != END_DOCUMENT) {
                final String tag = in.getName();
                if (type == START_TAG) {
                    if (TAG_URI_GRANT.equals(tag)) {
                        final int sourceUserId;
                        final int targetUserId;
                        final int userHandle = readIntAttribute(in,
                                ATTR_USER_HANDLE, UserHandle.USER_NULL);
                        if (userHandle != UserHandle.USER_NULL) {
                            // For backwards compatibility.
                            sourceUserId = userHandle;
                            targetUserId = userHandle;
                        } else {
                            sourceUserId = readIntAttribute(in, ATTR_SOURCE_USER_ID);
                            targetUserId = readIntAttribute(in, ATTR_TARGET_USER_ID);
                        }
                        final String sourcePkg = in.getAttributeValue(null, ATTR_SOURCE_PKG);
                        final String targetPkg = in.getAttributeValue(null, ATTR_TARGET_PKG);
                        final Uri uri = Uri.parse(in.getAttributeValue(null, ATTR_URI));
                        final boolean prefix = readBooleanAttribute(in, ATTR_PREFIX);
                        final int modeFlags = readIntAttribute(in, ATTR_MODE_FLAGS);
                        final long createdTime = readLongAttribute(in, ATTR_CREATED_TIME, now);

                        // Sanity check that provider still belongs to source package
                        final ProviderInfo pi = getProviderInfoLocked(
                                uri.getAuthority(), sourceUserId);
                        if (pi != null && sourcePkg.equals(pi.packageName)) {
                            int targetUid = -1;
                            try {
                                targetUid = AppGlobals.getPackageManager()
                                        .getPackageUid(targetPkg, targetUserId);
                            } catch (RemoteException e) {
                            }
                            if (targetUid != -1) {
                                final UriPermission perm = findOrCreateUriPermissionLocked(
                                        sourcePkg, targetPkg, targetUid,
                                        new GrantUri(sourceUserId, uri, prefix));
                                perm.initPersistedModes(modeFlags, createdTime);
                            }
                        } else {
                            Slog.w(TAG, "Persisted grant for " + uri + " had source " + sourcePkg
                                    + " but instead found " + pi);
                        }
                    }
                }
            }
        } catch (FileNotFoundException e) {
            // Missing grants is okay
        } catch (IOException e) {
            Log.wtf(TAG, "Failed reading Uri grants", e);
        } catch (XmlPullParserException e) {
            Log.wtf(TAG, "Failed reading Uri grants", e);
        } finally {
            IoUtils.closeQuietly(fis);
        }
    }

    @Override
    public void takePersistableUriPermission(Uri uri, final int modeFlags, int userId) {
        enforceNotIsolatedCaller("takePersistableUriPermission");

        Preconditions.checkFlagsArgument(modeFlags,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        synchronized (this) {
            final int callingUid = Binder.getCallingUid();
            boolean persistChanged = false;
            GrantUri grantUri = new GrantUri(userId, uri, false);

            UriPermission exactPerm = findUriPermissionLocked(callingUid,
                    new GrantUri(userId, uri, false));
            UriPermission prefixPerm = findUriPermissionLocked(callingUid,
                    new GrantUri(userId, uri, true));

            final boolean exactValid = (exactPerm != null)
                    && ((modeFlags & exactPerm.persistableModeFlags) == modeFlags);
            final boolean prefixValid = (prefixPerm != null)
                    && ((modeFlags & prefixPerm.persistableModeFlags) == modeFlags);

            if (!(exactValid || prefixValid)) {
                throw new SecurityException("No persistable permission grants found for UID "
                        + callingUid + " and Uri " + grantUri.toSafeString());
            }

            if (exactValid) {
                persistChanged |= exactPerm.takePersistableModes(modeFlags);
            }
            if (prefixValid) {
                persistChanged |= prefixPerm.takePersistableModes(modeFlags);
            }

            persistChanged |= maybePrunePersistedUriGrantsLocked(callingUid);

            if (persistChanged) {
                schedulePersistUriGrants();
            }
        }
    }

    @Override
    public void releasePersistableUriPermission(Uri uri, final int modeFlags, int userId) {
        enforceNotIsolatedCaller("releasePersistableUriPermission");

        Preconditions.checkFlagsArgument(modeFlags,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        synchronized (this) {
            final int callingUid = Binder.getCallingUid();
            boolean persistChanged = false;

            UriPermission exactPerm = findUriPermissionLocked(callingUid,
                    new GrantUri(userId, uri, false));
            UriPermission prefixPerm = findUriPermissionLocked(callingUid,
                    new GrantUri(userId, uri, true));
            if (exactPerm == null && prefixPerm == null) {
                throw new SecurityException("No permission grants found for UID " + callingUid
                        + " and Uri " + uri.toSafeString());
            }

            if (exactPerm != null) {
                persistChanged |= exactPerm.releasePersistableModes(modeFlags);
                removeUriPermissionIfNeededLocked(exactPerm);
            }
            if (prefixPerm != null) {
                persistChanged |= prefixPerm.releasePersistableModes(modeFlags);
                removeUriPermissionIfNeededLocked(prefixPerm);
            }

            if (persistChanged) {
                schedulePersistUriGrants();
            }
        }
    }

    /**
     * Prune any older {@link UriPermission} for the given UID until outstanding
     * persisted grants are below {@link #MAX_PERSISTED_URI_GRANTS}.
     *
     * @return if any mutations occured that require persisting.
     */
    private boolean maybePrunePersistedUriGrantsLocked(int uid) {
        final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.get(uid);
        if (perms == null) return false;
        if (perms.size() < MAX_PERSISTED_URI_GRANTS) return false;

        final ArrayList<UriPermission> persisted = Lists.newArrayList();
        for (UriPermission perm : perms.values()) {
            if (perm.persistedModeFlags != 0) {
                persisted.add(perm);
            }
        }

        final int trimCount = persisted.size() - MAX_PERSISTED_URI_GRANTS;
        if (trimCount <= 0) return false;

        Collections.sort(persisted, new UriPermission.PersistedTimeComparator());
        for (int i = 0; i < trimCount; i++) {
            final UriPermission perm = persisted.get(i);

            if (DEBUG_URI_PERMISSION) {
                Slog.v(TAG, "Trimming grant created at " + perm.persistedCreateTime);
            }

            perm.releasePersistableModes(~0);
            removeUriPermissionIfNeededLocked(perm);
        }

        return true;
    }

    @Override
    public ParceledListSlice<android.content.UriPermission> getPersistedUriPermissions(
            String packageName, boolean incoming) {
        enforceNotIsolatedCaller("getPersistedUriPermissions");
        Preconditions.checkNotNull(packageName, "packageName");

        final int callingUid = Binder.getCallingUid();
        final IPackageManager pm = AppGlobals.getPackageManager();
        try {
            final int packageUid = pm.getPackageUid(packageName, UserHandle.getUserId(callingUid));
            if (packageUid != callingUid) {
                throw new SecurityException(
                        "Package " + packageName + " does not belong to calling UID " + callingUid);
            }
        } catch (RemoteException e) {
            throw new SecurityException("Failed to verify package name ownership");
        }

        final ArrayList<android.content.UriPermission> result = Lists.newArrayList();
        synchronized (this) {
            if (incoming) {
                final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.get(
                        callingUid);
                if (perms == null) {
                    Slog.w(TAG, "No permission grants found for " + packageName);
                } else {
                    for (UriPermission perm : perms.values()) {
                        if (packageName.equals(perm.targetPkg) && perm.persistedModeFlags != 0) {
                            result.add(perm.buildPersistedPublicApiObject());
                        }
                    }
                }
            } else {
                final int size = mGrantedUriPermissions.size();
                for (int i = 0; i < size; i++) {
                    final ArrayMap<GrantUri, UriPermission> perms =
                            mGrantedUriPermissions.valueAt(i);
                    for (UriPermission perm : perms.values()) {
                        if (packageName.equals(perm.sourcePkg) && perm.persistedModeFlags != 0) {
                            result.add(perm.buildPersistedPublicApiObject());
                        }
                    }
                }
            }
        }
        return new ParceledListSlice<android.content.UriPermission>(result);
    }

    @Override
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

    @Override
    public void getMemoryInfo(ActivityManager.MemoryInfo outInfo) {
        final long homeAppMem = mProcessList.getMemLevel(ProcessList.HOME_APP_ADJ);
        final long cachedAppMem = mProcessList.getMemLevel(ProcessList.CACHED_APP_MIN_ADJ);
        outInfo.availMem = Process.getFreeMemory();
        outInfo.totalMem = Process.getTotalMemory();
        outInfo.threshold = homeAppMem;
        outInfo.lowMemory = outInfo.availMem < (homeAppMem + ((cachedAppMem-homeAppMem)/2));
        outInfo.hiddenAppThreshold = cachedAppMem;
        outInfo.secondaryServerThreshold = mProcessList.getMemLevel(
                ProcessList.SERVICE_ADJ);
        outInfo.visibleAppThreshold = mProcessList.getMemLevel(
                ProcessList.VISIBLE_APP_ADJ);
        outInfo.foregroundAppThreshold = mProcessList.getMemLevel(
                ProcessList.FOREGROUND_APP_ADJ);
    }
    
    // =========================================================
    // TASK MANAGEMENT
    // =========================================================

    @Override
    public List<IAppTask> getAppTasks() {
        final PackageManager pm = mContext.getPackageManager();
        int callingUid = Binder.getCallingUid();
        long ident = Binder.clearCallingIdentity();

        // Compose the list of packages for this id to test against
        HashSet<String> packages = new HashSet<String>();
        String[] uidPackages = pm.getPackagesForUid(callingUid);
        for (int i = 0; i < uidPackages.length; i++) {
            packages.add(uidPackages[i]);
        }

        synchronized(this) {
            ArrayList<IAppTask> list = new ArrayList<IAppTask>();
            try {
                if (localLOGV) Slog.v(TAG, "getAppTasks");

                final int N = mRecentTasks.size();
                for (int i = 0; i < N; i++) {
                    TaskRecord tr = mRecentTasks.get(i);
                    // Skip tasks that do not match the package name
                    if (packages.contains(tr.getBaseIntent().getComponent().getPackageName())) {
                        ActivityManager.RecentTaskInfo taskInfo =
                                createRecentTaskInfoFromTaskRecord(tr);
                        AppTaskImpl taskImpl = new AppTaskImpl(taskInfo.persistentId, callingUid);
                        list.add(taskImpl);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            return list;
        }
    }

    @Override
    public List<RunningTaskInfo> getTasks(int maxNum, int flags) {
        final int callingUid = Binder.getCallingUid();
        ArrayList<RunningTaskInfo> list = new ArrayList<RunningTaskInfo>();

        synchronized(this) {
            if (localLOGV) Slog.v(
                TAG, "getTasks: max=" + maxNum + ", flags=" + flags);

            final boolean allowed = checkCallingPermission(
                    android.Manifest.permission.GET_TASKS)
                    == PackageManager.PERMISSION_GRANTED;
            if (!allowed) {
                Slog.w(TAG, "getTasks: caller " + callingUid
                        + " does not hold GET_TASKS; limiting output");
            }

            // TODO: Improve with MRU list from all ActivityStacks.
            mStackSupervisor.getTasksLocked(maxNum, list, callingUid, allowed);
        }

        return list;
    }

    TaskRecord getMostRecentTask() {
        return mRecentTasks.get(0);
    }

    /**
     * Creates a new RecentTaskInfo from a TaskRecord.
     */
    private ActivityManager.RecentTaskInfo createRecentTaskInfoFromTaskRecord(TaskRecord tr) {
        // Update the task description to reflect any changes in the task stack
        tr.updateTaskDescription();

        // Compose the recent task info
        ActivityManager.RecentTaskInfo rti = new ActivityManager.RecentTaskInfo();
        rti.id = tr.getTopActivity() == null ? -1 : tr.taskId;
        rti.persistentId = tr.taskId;
        rti.baseIntent = new Intent(tr.getBaseIntent());
        rti.origActivity = tr.origActivity;
        rti.description = tr.lastDescription;
        rti.stackId = tr.stack != null ? tr.stack.mStackId : -1;
        rti.userId = tr.userId;
        rti.taskDescription = new ActivityManager.TaskDescription(tr.lastTaskDescription);
        rti.firstActiveTime = tr.firstActiveTime;
        rti.lastActiveTime = tr.lastActiveTime;
        rti.affiliatedTaskId = tr.mAffiliatedTaskId;
        rti.affiliatedTaskColor = tr.mAffiliatedTaskColor;
        return rti;
    }

    @Override
    public List<ActivityManager.RecentTaskInfo> getRecentTasks(int maxNum, int flags, int userId) {
        final int callingUid = Binder.getCallingUid();
        userId = handleIncomingUser(Binder.getCallingPid(), callingUid, userId,
                false, ALLOW_FULL_ONLY, "getRecentTasks", null);

        final boolean includeProfiles = (flags & ActivityManager.RECENT_INCLUDE_PROFILES) != 0;
        final boolean withExcluded = (flags&ActivityManager.RECENT_WITH_EXCLUDED) != 0;
        synchronized (this) {
            final boolean allowed = checkCallingPermission(android.Manifest.permission.GET_TASKS)
                    == PackageManager.PERMISSION_GRANTED;
            if (!allowed) {
                Slog.w(TAG, "getRecentTasks: caller " + callingUid
                        + " does not hold GET_TASKS; limiting output");
            }
            final boolean detailed = checkCallingPermission(
                    android.Manifest.permission.GET_DETAILED_TASKS)
                    == PackageManager.PERMISSION_GRANTED;

            IPackageManager pm = AppGlobals.getPackageManager();

            final int N = mRecentTasks.size();
            ArrayList<ActivityManager.RecentTaskInfo> res
                    = new ArrayList<ActivityManager.RecentTaskInfo>(
                            maxNum < N ? maxNum : N);

            final Set<Integer> includedUsers;
            if (includeProfiles) {
                includedUsers = getProfileIdsLocked(userId);
            } else {
                includedUsers = new HashSet<Integer>();
            }
            includedUsers.add(Integer.valueOf(userId));

            // Regroup affiliated tasks together.
            for (int i = 0; i < N; ) {
                TaskRecord task = mRecentTasks.remove(i);
                if (mTmpRecents.contains(task)) {
                    continue;
                }
                int affiliatedTaskId = task.mAffiliatedTaskId;
                while (true) {
                    TaskRecord next = task.mNextAffiliate;
                    if (next == null) {
                        break;
                    }
                    if (next.mAffiliatedTaskId != affiliatedTaskId) {
                        Slog.e(TAG, "Error in Recents: next.affiliatedTaskId=" +
                                next.mAffiliatedTaskId + " affiliatedTaskId=" + affiliatedTaskId);
                        task.setNextAffiliate(null);
                        if (next.mPrevAffiliate == task) {
                            next.setPrevAffiliate(null);
                        }
                        break;
                    }
                    if (next.mPrevAffiliate != task) {
                        Slog.e(TAG, "Error in Recents chain prev.mNextAffiliate=" +
                                next.mPrevAffiliate + " task=" + task);
                        next.setPrevAffiliate(null);
                        break;
                    }
                    if (!mRecentTasks.contains(next)) {
                        Slog.e(TAG, "Error in Recents: next=" + next + " not in mRecentTasks");
                        task.setNextAffiliate(null);
                        if (next.mPrevAffiliate == task) {
                            next.setPrevAffiliate(null);
                        }
                        break;
                    }
                    task = next;
                }
                // task is now the end of the list
                do {
                    mRecentTasks.remove(task);
                    mRecentTasks.add(i++, task);
                    mTmpRecents.add(task);
                } while ((task = task.mPrevAffiliate) != null);
            }
            mTmpRecents.clear();
            // mRecentTasks is now in sorted, affiliated order.

            for (int i=0; i<N && maxNum > 0; i++) {
                TaskRecord tr = mRecentTasks.get(i);
                // Only add calling user or related users recent tasks
                if (!includedUsers.contains(Integer.valueOf(tr.userId))) {
                    if (DEBUG_RECENTS) Slog.d(TAG, "Skipping, not user: " + tr);
                    continue;
                }

                // Return the entry if desired by the caller.  We always return
                // the first entry, because callers always expect this to be the
                // foreground app.  We may filter others if the caller has
                // not supplied RECENT_WITH_EXCLUDED and there is some reason
                // we should exclude the entry.

                if (i == 0
                        || withExcluded
                        || (tr.intent == null)
                        || ((tr.intent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                                == 0)) {
                    if (!allowed) {
                        // If the caller doesn't have the GET_TASKS permission, then only
                        // allow them to see a small subset of tasks -- their own and home.
                        if (!tr.isHomeTask() && tr.creatorUid != callingUid) {
                            if (DEBUG_RECENTS) Slog.d(TAG, "Skipping, not allowed: " + tr);
                            continue;
                        }
                    }
                    if (tr.autoRemoveRecents && tr.getTopActivity() == null) {
                        // Don't include auto remove tasks that are finished or finishing.
                        if (DEBUG_RECENTS) Slog.d(TAG, "Skipping, auto-remove without activity: "
                                + tr);
                        continue;
                    }

                    ActivityManager.RecentTaskInfo rti = createRecentTaskInfoFromTaskRecord(tr);
                    if (!detailed) {
                        rti.baseIntent.replaceExtras((Bundle)null);
                    }

                    if ((flags&ActivityManager.RECENT_IGNORE_UNAVAILABLE) != 0) {
                        // Check whether this activity is currently available.
                        try {
                            if (rti.origActivity != null) {
                                if (pm.getActivityInfo(rti.origActivity, 0, userId)
                                        == null) {
                                    if (DEBUG_RECENTS) Slog.d(TAG, "Skipping, unavail orig act: "
                                            + tr);
                                    continue;
                                }
                            } else if (rti.baseIntent != null) {
                                if (pm.queryIntentActivities(rti.baseIntent,
                                        null, 0, userId) == null) {
                                    if (DEBUG_RECENTS) Slog.d(TAG, "Skipping, unavail intent: "
                                            + tr);
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

    private TaskRecord recentTaskForIdLocked(int id) {
        final int N = mRecentTasks.size();
            for (int i=0; i<N; i++) {
                TaskRecord tr = mRecentTasks.get(i);
                if (tr.taskId == id) {
                    return tr;
                }
            }
            return null;
    }

    @Override
    public ActivityManager.TaskThumbnail getTaskThumbnail(int id) {
        synchronized (this) {
            enforceCallingPermission(android.Manifest.permission.READ_FRAME_BUFFER,
                    "getTaskThumbnail()");
            TaskRecord tr = recentTaskForIdLocked(id);
            if (tr != null) {
                return tr.getTaskThumbnailLocked();
            }
        }
        return null;
    }

    @Override
    public int addAppTask(IBinder activityToken, Intent intent,
            ActivityManager.TaskDescription description, Bitmap thumbnail) throws RemoteException {
        final int callingUid = Binder.getCallingUid();
        final long callingIdent = Binder.clearCallingIdentity();

        try {
            synchronized (this) {
                ActivityRecord r = ActivityRecord.isInStackLocked(activityToken);
                if (r == null) {
                    throw new IllegalArgumentException("Activity does not exist; token="
                            + activityToken);
                }
                ComponentName comp = intent.getComponent();
                if (comp == null) {
                    throw new IllegalArgumentException("Intent " + intent
                            + " must specify explicit component");
                }
                if (thumbnail.getWidth() != mThumbnailWidth
                        || thumbnail.getHeight() != mThumbnailHeight) {
                    throw new IllegalArgumentException("Bad thumbnail size: got "
                            + thumbnail.getWidth() + "x" + thumbnail.getHeight() + ", require "
                            + mThumbnailWidth + "x" + mThumbnailHeight);
                }
                if (intent.getSelector() != null) {
                    intent.setSelector(null);
                }
                if (intent.getSourceBounds() != null) {
                    intent.setSourceBounds(null);
                }
                if ((intent.getFlags()&Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0) {
                    if ((intent.getFlags()&Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS) == 0) {
                        // The caller has added this as an auto-remove task...  that makes no
                        // sense, so turn off auto-remove.
                        intent.addFlags(Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS);
                    }
                } else if ((intent.getFlags()&Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
                    // Must be a new task.
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                if (!comp.equals(mLastAddedTaskComponent) || callingUid != mLastAddedTaskUid) {
                    mLastAddedTaskActivity = null;
                }
                ActivityInfo ainfo = mLastAddedTaskActivity;
                if (ainfo == null) {
                    ainfo = mLastAddedTaskActivity = AppGlobals.getPackageManager().getActivityInfo(
                            comp, 0, UserHandle.getUserId(callingUid));
                    if (ainfo.applicationInfo.uid != callingUid) {
                        throw new SecurityException(
                                "Can't add task for another application: target uid="
                                + ainfo.applicationInfo.uid + ", calling uid=" + callingUid);
                    }
                }

                TaskRecord task = new TaskRecord(this, mStackSupervisor.getNextTaskId(), ainfo,
                        intent, description);

                int trimIdx = trimRecentsForTask(task, false);
                if (trimIdx >= 0) {
                    // If this would have caused a trim, then we'll abort because that
                    // means it would be added at the end of the list but then just removed.
                    return -1;
                }

                final int N = mRecentTasks.size();
                if (N >= (MAX_RECENT_TASKS-1)) {
                    final TaskRecord tr = mRecentTasks.remove(N - 1);
                    tr.disposeThumbnail();
                    tr.closeRecentsChain();
                }

                mRecentTasks.add(task);
                r.task.stack.addTask(task, false, false);

                task.setLastThumbnail(thumbnail);
                task.freeLastThumbnail();

                return task.taskId;
            }
        } finally {
            Binder.restoreCallingIdentity(callingIdent);
        }
    }

    @Override
    public Point getAppTaskThumbnailSize() {
        synchronized (this) {
            return new Point(mThumbnailWidth,  mThumbnailHeight);
        }
    }

    @Override
    public void setTaskDescription(IBinder token, ActivityManager.TaskDescription td) {
        synchronized (this) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r != null) {
                r.taskDescription = td;
                r.task.updateTaskDescription();
            }
        }
    }

    private void killUnneededProcessLocked(ProcessRecord pr, String reason) {
        if (!pr.killedByAm) {
            Slog.i(TAG, "Killing " + pr.toShortString() + " (adj " + pr.setAdj + "): " + reason);
            EventLog.writeEvent(EventLogTags.AM_KILL, pr.userId, pr.pid,
                    pr.processName, pr.setAdj, reason);
            pr.killedByAm = true;
            Process.killProcessQuiet(pr.pid);
            Process.killProcessGroup(pr.info.uid, pr.pid);
        }
    }

    private void cleanUpRemovedTaskLocked(TaskRecord tr, int flags) {
        tr.disposeThumbnail();
        mRecentTasks.remove(tr);
        tr.closeRecentsChain();
        final boolean killProcesses = (flags&ActivityManager.REMOVE_TASK_KILL_PROCESS) != 0;
        Intent baseIntent = new Intent(
                tr.intent != null ? tr.intent : tr.affinityIntent);
        ComponentName component = baseIntent.getComponent();
        if (component == null) {
            Slog.w(TAG, "Now component for base intent of task: " + tr);
            return;
        }

        // Find any running services associated with this app.
        mServices.cleanUpRemovedTaskLocked(tr, component, baseIntent);

        if (killProcesses) {
            // Find any running processes associated with this app.
            final String pkg = component.getPackageName();
            ArrayList<ProcessRecord> procs = new ArrayList<ProcessRecord>();
            ArrayMap<String, SparseArray<ProcessRecord>> pmap = mProcessNames.getMap();
            for (int i=0; i<pmap.size(); i++) {
                SparseArray<ProcessRecord> uids = pmap.valueAt(i);
                for (int j=0; j<uids.size(); j++) {
                    ProcessRecord proc = uids.valueAt(j);
                    if (proc.userId != tr.userId) {
                        continue;
                    }
                    if (!proc.pkgList.containsKey(pkg)) {
                        continue;
                    }
                    procs.add(proc);
                }
            }

            // Kill the running processes.
            for (int i=0; i<procs.size(); i++) {
                ProcessRecord pr = procs.get(i);
                if (pr == mHomeProcess) {
                    // Don't kill the home process along with tasks from the same package.
                    continue;
                }
                if (pr.setSchedGroup == Process.THREAD_GROUP_BG_NONINTERACTIVE) {
                    killUnneededProcessLocked(pr, "remove task");
                } else {
                    pr.waitingToKill = "remove task";
                }
            }
        }
    }

    /**
     * Removes the task with the specified task id.
     *
     * @param taskId Identifier of the task to be removed.
     * @param flags Additional operational flags.  May be 0 or
     * {@link ActivityManager#REMOVE_TASK_KILL_PROCESS}.
     * @return Returns true if the given task was found and removed.
     */
    private boolean removeTaskByIdLocked(int taskId, int flags) {
        TaskRecord tr = recentTaskForIdLocked(taskId);
        if (tr != null) {
            tr.removeTaskActivitiesLocked();
            cleanUpRemovedTaskLocked(tr, flags);
            if (tr.isPersistable) {
                notifyTaskPersisterLocked(null, true);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean removeTask(int taskId, int flags) {
        synchronized (this) {
            enforceCallingPermission(android.Manifest.permission.REMOVE_TASKS,
                    "removeTask()");
            long ident = Binder.clearCallingIdentity();
            try {
                return removeTaskByIdLocked(taskId, flags);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
    
    /**
     * TODO: Add mController hook
     */
    @Override
    public void moveTaskToFront(int taskId, int flags, Bundle options) {
        enforceCallingPermission(android.Manifest.permission.REORDER_TASKS,
                "moveTaskToFront()");

        if (DEBUG_STACK) Slog.d(TAG, "moveTaskToFront: moving taskId=" + taskId);
        synchronized(this) {
            if (!checkAppSwitchAllowedLocked(Binder.getCallingPid(),
                    Binder.getCallingUid(), "Task to front")) {
                ActivityOptions.abort(options);
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            try {
                final TaskRecord task = mStackSupervisor.anyTaskForIdLocked(taskId);
                if (task == null) {
                    return;
                }
                if (mStackSupervisor.isLockTaskModeViolation(task)) {
                    mStackSupervisor.showLockTaskToast();
                    Slog.e(TAG, "moveTaskToFront: Attempt to violate Lock Task Mode");
                    return;
                }
                final ActivityRecord prev = mStackSupervisor.topRunningActivityLocked();
                if (prev != null && prev.isRecentsActivity()) {
                    task.setTaskToReturnTo(ActivityRecord.RECENTS_ACTIVITY_TYPE);
                }
                mStackSupervisor.findTaskToMoveToFrontLocked(task, flags, options);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
            ActivityOptions.abort(options);
        }
    }

    @Override
    public void moveTaskToBack(int taskId) {
        enforceCallingPermission(android.Manifest.permission.REORDER_TASKS,
                "moveTaskToBack()");

        synchronized(this) {
            TaskRecord tr = recentTaskForIdLocked(taskId);
            if (tr != null) {
                if (DEBUG_STACK) Slog.d(TAG, "moveTaskToBack: moving task=" + tr);
                ActivityStack stack = tr.stack;
                if (stack.mResumedActivity != null && stack.mResumedActivity.task == tr) {
                    if (!checkAppSwitchAllowedLocked(Binder.getCallingPid(),
                            Binder.getCallingUid(), "Task to back")) {
                        return;
                    }
                }
                final long origId = Binder.clearCallingIdentity();
                try {
                    stack.moveTaskToBackLocked(taskId, null);
                } finally {
                    Binder.restoreCallingIdentity(origId);
                }
            }
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
    @Override
    public boolean moveActivityTaskToBack(IBinder token, boolean nonRoot) {
        enforceNotIsolatedCaller("moveActivityTaskToBack");
        synchronized(this) {
            final long origId = Binder.clearCallingIdentity();
            int taskId = ActivityRecord.getTaskForActivityLocked(token, !nonRoot);
            if (taskId >= 0) {
                return ActivityRecord.getStackLocked(token).moveTaskToBackLocked(taskId, null);
            }
            Binder.restoreCallingIdentity(origId);
        }
        return false;
    }

    @Override
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

    @Override
    public IBinder getHomeActivityToken() throws RemoteException {
        enforceCallingPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS,
                "getHomeActivityToken()");
        synchronized (this) {
            return mStackSupervisor.getHomeActivityToken();
        }
    }

    @Override
    public IActivityContainer createActivityContainer(IBinder parentActivityToken,
            IActivityContainerCallback callback) throws RemoteException {
        enforceCallingPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS,
                "createActivityContainer()");
        synchronized (this) {
            if (parentActivityToken == null) {
                throw new IllegalArgumentException("parent token must not be null");
            }
            ActivityRecord r = ActivityRecord.forToken(parentActivityToken);
            if (r == null) {
                return null;
            }
            if (callback == null) {
                throw new IllegalArgumentException("callback must not be null");
            }
            return mStackSupervisor.createActivityContainer(r, callback);
        }
    }

    @Override
    public void deleteActivityContainer(IActivityContainer container) throws RemoteException {
        enforceCallingPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS,
                "deleteActivityContainer()");
        synchronized (this) {
            mStackSupervisor.deleteActivityContainer(container);
        }
    }

    @Override
    public IActivityContainer getEnclosingActivityContainer(IBinder activityToken)
            throws RemoteException {
        synchronized (this) {
            ActivityStack stack = ActivityRecord.getStackLocked(activityToken);
            if (stack != null) {
                return stack.mActivityContainer;
            }
            return null;
        }
    }

    @Override
    public void moveTaskToStack(int taskId, int stackId, boolean toTop) {
        enforceCallingPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS,
                "moveTaskToStack()");
        if (stackId == HOME_STACK_ID) {
            Slog.e(TAG, "moveTaskToStack: Attempt to move task " + taskId + " to home stack",
                    new RuntimeException("here").fillInStackTrace());
        }
        synchronized (this) {
            long ident = Binder.clearCallingIdentity();
            try {
                if (DEBUG_STACK) Slog.d(TAG, "moveTaskToStack: moving task=" + taskId + " to stackId="
                        + stackId + " toTop=" + toTop);
                mStackSupervisor.moveTaskToStack(taskId, stackId, toTop);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void resizeStack(int stackBoxId, Rect bounds) {
        enforceCallingPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS,
                "resizeStackBox()");
        long ident = Binder.clearCallingIdentity();
        try {
            mWindowManager.resizeStack(stackBoxId, bounds);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public List<StackInfo> getAllStackInfos() {
        enforceCallingPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS,
                "getAllStackInfos()");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                return mStackSupervisor.getAllStackInfosLocked();
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public StackInfo getStackInfo(int stackId) {
        enforceCallingPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS,
                "getStackInfo()");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                return mStackSupervisor.getStackInfoLocked(stackId);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean isInHomeStack(int taskId) {
        enforceCallingPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS,
                "getStackInfo()");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                TaskRecord tr = recentTaskForIdLocked(taskId);
                return tr != null && tr.stack != null && tr.stack.isHomeStack();
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public int getTaskForActivity(IBinder token, boolean onlyRoot) {
        synchronized(this) {
            return ActivityRecord.getTaskForActivityLocked(token, onlyRoot);
        }
    }

    private boolean isLockTaskAuthorized(String pkg) {
        final DevicePolicyManager dpm = (DevicePolicyManager)
                mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        try {
            int uid = mContext.getPackageManager().getPackageUid(pkg,
                    Binder.getCallingUserHandle().getIdentifier());
            return (uid == Binder.getCallingUid()) && dpm != null && dpm.isLockTaskPermitted(pkg);
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    void startLockTaskMode(TaskRecord task) {
        final String pkg;
        synchronized (this) {
            pkg = task.intent.getComponent().getPackageName();
        }
        boolean isSystemInitiated = Binder.getCallingUid() == Process.SYSTEM_UID;
        if (!isSystemInitiated && !isLockTaskAuthorized(pkg)) {
            final TaskRecord taskRecord = task;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mLockToAppRequest.showLockTaskPrompt(taskRecord);
                }
            });
            return;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                // Since we lost lock on task, make sure it is still there.
                task = mStackSupervisor.anyTaskForIdLocked(task.taskId);
                if (task != null) {
                    if (!isSystemInitiated
                            && ((mFocusedActivity == null) || (task != mFocusedActivity.task))) {
                        throw new IllegalArgumentException("Invalid task, not in foreground");
                    }
                    mStackSupervisor.setLockTaskModeLocked(task, !isSystemInitiated);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void startLockTaskMode(int taskId) {
        final TaskRecord task;
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                task = mStackSupervisor.anyTaskForIdLocked(taskId);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        if (task != null) {
            startLockTaskMode(task);
        }
    }

    @Override
    public void startLockTaskMode(IBinder token) {
        final TaskRecord task;
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                final ActivityRecord r = ActivityRecord.forToken(token);
                if (r == null) {
                    return;
                }
                task = r.task;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        if (task != null) {
            startLockTaskMode(task);
        }
    }

    @Override
    public void startLockTaskModeOnCurrent() throws RemoteException {
        checkCallingPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS);
        ActivityRecord r = null;
        synchronized (this) {
            r = mStackSupervisor.topRunningActivityLocked();
        }
        startLockTaskMode(r.task);
    }

    @Override
    public void stopLockTaskMode() {
        // Verify that the user matches the package of the intent for the TaskRecord
        // we are locked to or systtem.  This will ensure the same caller for startLockTaskMode
        // and stopLockTaskMode.
        final int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SYSTEM_UID) {
            try {
                String pkg =
                        mStackSupervisor.mLockTaskModeTask.intent.getComponent().getPackageName();
                int uid = mContext.getPackageManager().getPackageUid(pkg,
                        Binder.getCallingUserHandle().getIdentifier());
                if (uid != callingUid) {
                    throw new SecurityException("Invalid uid, expected " + uid);
                }
            } catch (NameNotFoundException e) {
                Log.d(TAG, "stopLockTaskMode " + e);
                return;
            }
        }
        long ident = Binder.clearCallingIdentity();
        try {
            Log.d(TAG, "stopLockTaskMode");
            // Stop lock task
            synchronized (this) {
                mStackSupervisor.setLockTaskModeLocked(null, false);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void stopLockTaskModeOnCurrent() throws RemoteException {
        checkCallingPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS);
        long ident = Binder.clearCallingIdentity();
        try {
            stopLockTaskMode();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean isInLockTaskMode() {
        synchronized (this) {
            return mStackSupervisor.isInLockTaskMode();
        }
    }

    // =========================================================
    // CONTENT PROVIDERS
    // =========================================================

    private final List<ProviderInfo> generateApplicationProvidersLocked(ProcessRecord app) {
        List<ProviderInfo> providers = null;
        try {
            providers = AppGlobals.getPackageManager().
                queryContentProviders(app.processName, app.uid,
                        STOCK_PM_FLAGS | PackageManager.GET_URI_PERMISSION_PATTERNS);
        } catch (RemoteException ex) {
        }
        if (DEBUG_MU)
            Slog.v(TAG_MU, "generateApplicationProvidersLocked, app.info.uid = " + app.uid);
        int userId = app.userId;
        if (providers != null) {
            int N = providers.size();
            app.pubProviders.ensureCapacity(N + app.pubProviders.size());
            for (int i=0; i<N; i++) {
                ProviderInfo cpi =
                    (ProviderInfo)providers.get(i);
                boolean singleton = isSingleton(cpi.processName, cpi.applicationInfo,
                        cpi.name, cpi.flags);
                if (singleton && UserHandle.getUserId(app.uid) != 0) {
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
                if (DEBUG_MU)
                    Slog.v(TAG_MU, "generateApplicationProvidersLocked, cpi.uid = " + cpr.uid);
                app.pubProviders.put(cpi.name, cpr);
                if (!cpi.multiprocess || !"android".equals(cpi.packageName)) {
                    // Don't add this if it is a platform component that is marked
                    // to run in multiple processes, because this is actually
                    // part of the framework so doesn't make sense to track as a
                    // separate apk in the process.
                    app.addPackage(cpi.applicationInfo.packageName, cpi.applicationInfo.versionCode,
                            mProcessStats);
                }
                ensurePackageDexOpt(cpi.applicationInfo.packageName);
            }
        }
        return providers;
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
            int tmpTargetUserId = unsafeConvertIncomingUser(userId);
            if (tmpTargetUserId != UserHandle.getUserId(callingUid)) {
                if (checkAuthorityGrants(callingUid, cpi, tmpTargetUserId, checkUser)) {
                    return null;
                }
                checkedGrants = true;
            }
            userId = handleIncomingUser(callingPid, callingUid, userId,
                    false, ALLOW_NON_FULL,
                    "checkContentProviderPermissionLocked " + cpi.authority, null);
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
        if (!checkedGrants && checkAuthorityGrants(callingUid, cpi, userId, checkUser)) {
            return null;
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

    /**
     * Returns if the ContentProvider has granted a uri to callingUid
     */
    boolean checkAuthorityGrants(int callingUid, ProviderInfo cpi, int userId, boolean checkUser) {
        final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.get(callingUid);
        if (perms != null) {
            for (int i=perms.size()-1; i>=0; i--) {
                GrantUri grantUri = perms.keyAt(i);
                if (grantUri.sourceUserId == userId || !checkUser) {
                    if (matchesProvider(grantUri.uri, cpi)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns true if the uri authority is one of the authorities specified in the provider.
     */
    boolean matchesProvider(Uri uri, ProviderInfo cpi) {
        String uriAuth = uri.getAuthority();
        String cpiAuth = cpi.authority;
        if (cpiAuth.indexOf(';') == -1) {
            return cpiAuth.equals(uriAuth);
        }
        String[] cpiAuths = cpiAuth.split(";");
        int length = cpiAuths.length;
        for (int i = 0; i < length; i++) {
            if (cpiAuths[i].equals(uriAuth)) return true;
        }
        return false;
    }

    ContentProviderConnection incProviderCountLocked(ProcessRecord r,
            final ContentProviderRecord cpr, IBinder externalProcessToken, boolean stable) {
        if (r != null) {
            for (int i=0; i<r.conProviders.size(); i++) {
                ContentProviderConnection conn = r.conProviders.get(i);
                if (conn.provider == cpr) {
                    if (DEBUG_PROVIDER) Slog.v(TAG,
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
            ContentProviderConnection conn = new ContentProviderConnection(cpr, r);
            if (stable) {
                conn.stableCount = 1;
                conn.numStableIncs = 1;
            } else {
                conn.unstableCount = 1;
                conn.numUnstableIncs = 1;
            }
            cpr.connections.add(conn);
            r.conProviders.add(conn);
            return conn;
        }
        cpr.addExternalProcessHandleLocked(externalProcessToken);
        return null;
    }

    boolean decProviderCountLocked(ContentProviderConnection conn,
            ContentProviderRecord cpr, IBinder externalProcessToken, boolean stable) {
        if (conn != null) {
            cpr = conn.provider;
            if (DEBUG_PROVIDER) Slog.v(TAG,
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
                cpr.connections.remove(conn);
                conn.client.conProviders.remove(conn);
                return true;
            }
            return false;
        }
        cpr.removeExternalProcessHandleLocked(externalProcessToken);
        return false;
    }

    private final ContentProviderHolder getContentProviderImpl(IApplicationThread caller,
            String name, IBinder token, boolean stable, int userId) {
        ContentProviderRecord cpr;
        ContentProviderConnection conn = null;
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

            boolean checkCrossUser = true;

            // First check if this content provider has been published...
            cpr = mProviderMap.getProviderByName(name, userId);
            // If that didn't work, check if it exists for user 0 and then
            // verify that it's a singleton provider before using it.
            if (cpr == null && userId != UserHandle.USER_OWNER) {
                cpr = mProviderMap.getProviderByName(name, UserHandle.USER_OWNER);
                if (cpr != null) {
                    cpi = cpr.info;
                    if (isSingleton(cpi.processName, cpi.applicationInfo,
                            cpi.name, cpi.flags)
                            && isValidSingletonCall(r.uid, cpi.applicationInfo.uid)) {
                        userId = UserHandle.USER_OWNER;
                        checkCrossUser = false;
                    } else {
                        cpr = null;
                        cpi = null;
                    }
                }
            }

            boolean providerRunning = cpr != null;
            if (providerRunning) {
                cpi = cpr.info;
                String msg;
                if ((msg = checkContentProviderPermissionLocked(cpi, r, userId, checkCrossUser))
                        != null) {
                    throw new SecurityException(msg);
                }

                if (r != null && cpr.canRunHere(r)) {
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

                final long origId = Binder.clearCallingIdentity();

                // In this case the provider instance already exists, so we can
                // return it right away.
                conn = incProviderCountLocked(r, cpr, token, stable);
                if (conn != null && (conn.stableCount+conn.unstableCount) == 1) {
                    if (cpr.proc != null && r.setAdj <= ProcessList.PERCEPTIBLE_APP_ADJ) {
                        // If this is a perceptible app accessing the provider,
                        // make sure to count it as being accessed and thus
                        // back up on the LRU list.  This is good because
                        // content providers are often expensive to start.
                        updateLruProcessLocked(cpr.proc, false, null);
                    }
                }

                if (cpr.proc != null) {
                    if (false) {
                        if (cpr.name.flattenToShortString().equals(
                                "com.android.providers.calendar/.CalendarProvider2")) {
                            Slog.v(TAG, "****************** KILLING "
                                + cpr.name.flattenToShortString());
                            Process.killProcess(cpr.proc.pid);
                        }
                    }
                    boolean success = updateOomAdjLocked(cpr.proc);
                    if (DEBUG_PROVIDER) Slog.i(TAG, "Adjust success: " + success);
                    // NOTE: there is still a race here where a signal could be
                    // pending on the process even though we managed to update its
                    // adj level.  Not sure what to do about this, but at least
                    // the race is now smaller.
                    if (!success) {
                        // Uh oh...  it looks like the provider's process
                        // has been killed on us.  We need to wait for a new
                        // process to be started, and make sure its death
                        // doesn't kill our process.
                        Slog.i(TAG,
                                "Existing provider " + cpr.name.flattenToShortString()
                                + " is crashing; detaching " + r);
                        boolean lastRef = decProviderCountLocked(conn, cpr, token, stable);
                        appDiedLocked(cpr.proc);
                        if (!lastRef) {
                            // This wasn't the last ref our process had on
                            // the provider...  we have now been killed, bail.
                            return null;
                        }
                        providerRunning = false;
                        conn = null;
                    }
                }

                Binder.restoreCallingIdentity(origId);
            }

            boolean singleton;
            if (!providerRunning) {
                try {
                    cpi = AppGlobals.getPackageManager().
                        resolveContentProvider(name,
                            STOCK_PM_FLAGS | PackageManager.GET_URI_PERMISSION_PATTERNS, userId);
                } catch (RemoteException ex) {
                }
                if (cpi == null) {
                    return null;
                }
                // If the provider is a singleton AND
                // (it's a call within the same user || the provider is a
                // privileged app)
                // Then allow connecting to the singleton provider
                singleton = isSingleton(cpi.processName, cpi.applicationInfo,
                        cpi.name, cpi.flags)
                        && isValidSingletonCall(r.uid, cpi.applicationInfo.uid);
                if (singleton) {
                    userId = UserHandle.USER_OWNER;
                }
                cpi.applicationInfo = getAppInfoForUser(cpi.applicationInfo, userId);

                String msg;
                if ((msg = checkContentProviderPermissionLocked(cpi, r, userId, !singleton))
                        != null) {
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

                // Make sure that the user who owns this provider is started.  If not,
                // we don't want to allow it to run.
                if (mStartedUsers.get(userId) == null) {
                    Slog.w(TAG, "Unable to launch app "
                            + cpi.applicationInfo.packageName + "/"
                            + cpi.applicationInfo.uid + " for provider "
                            + name + ": user " + userId + " is stopped");
                    return null;
                }

                ComponentName comp = new ComponentName(cpi.packageName, cpi.name);
                cpr = mProviderMap.getProviderByClass(comp, userId);
                final boolean firstClass = cpr == null;
                if (firstClass) {
                    try {
                        ApplicationInfo ai =
                            AppGlobals.getPackageManager().
                                getApplicationInfo(
                                        cpi.applicationInfo.packageName,
                                        STOCK_PM_FLAGS, userId);
                        if (ai == null) {
                            Slog.w(TAG, "No package info for content provider "
                                    + cpi.name);
                            return null;
                        }
                        ai = getAppInfoForUser(ai, userId);
                        cpr = new ContentProviderRecord(this, cpi, ai, comp, singleton);
                    } catch (RemoteException ex) {
                        // pm is in same process, this will never happen.
                    }
                }

                if (r != null && cpr.canRunHere(r)) {
                    // If this is a multiprocess provider, then just return its
                    // info and allow the caller to instantiate it.  Only do
                    // this if the provider is the same user as the caller's
                    // process, or can run as root (so can be in any process).
                    return cpr.newHolder(null);
                }

                if (DEBUG_PROVIDER) {
                    RuntimeException e = new RuntimeException("here");
                    Slog.w(TAG, "LAUNCHING REMOTE PROVIDER (myuid " + (r != null ? r.uid : null)
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
                                    cpr.appInfo.packageName, false, userId);
                        } catch (RemoteException e) {
                        } catch (IllegalArgumentException e) {
                            Slog.w(TAG, "Failed trying to unstop package "
                                    + cpr.appInfo.packageName + ": " + e);
                        }

                        // Use existing process if already started
                        ProcessRecord proc = getProcessRecordLocked(
                                cpi.processName, cpr.appInfo.uid, false);
                        if (proc != null && proc.thread != null) {
                            if (DEBUG_PROVIDER) {
                                Slog.d(TAG, "Installing in existing process " + proc);
                            }
                            proc.pubProviders.put(cpi.name, cpr);
                            try {
                                proc.thread.scheduleInstallProvider(cpi);
                            } catch (RemoteException e) {
                            }
                        } else {
                            proc = startProcessLocked(cpi.processName,
                                    cpr.appInfo, false, 0, "content provider",
                                    new ComponentName(cpi.applicationInfo.packageName,
                                            cpi.name), false, false, false);
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

                // Make sure the provider is published (the same provider class
                // may be published under multiple names).
                if (firstClass) {
                    mProviderMap.putProviderByClass(comp, cpr);
                }

                mProviderMap.putProviderByName(name, cpr);
                conn = incProviderCountLocked(r, cpr, token, stable);
                if (conn != null) {
                    conn.waiting = true;
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
                            UserHandle.getUserId(cpi.applicationInfo.uid),
                            cpi.applicationInfo.packageName,
                            cpi.applicationInfo.uid, name);
                    return null;
                }
                try {
                    if (DEBUG_MU) {
                        Slog.v(TAG_MU, "Waiting to start provider " + cpr + " launchingApp="
                                + cpr.launchingApp);
                    }
                    if (conn != null) {
                        conn.waiting = true;
                    }
                    cpr.wait();
                } catch (InterruptedException ex) {
                } finally {
                    if (conn != null) {
                        conn.waiting = false;
                    }
                }
            }
        }
        return cpr != null ? cpr.newHolder(conn) : null;
    }

    @Override
    public final ContentProviderHolder getContentProvider(
            IApplicationThread caller, String name, int userId, boolean stable) {
        enforceNotIsolatedCaller("getContentProvider");
        if (caller == null) {
            String msg = "null IApplicationThread when getting content provider "
                    + name;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        // The incoming user check is now handled in checkContentProviderPermissionLocked() to deal
        // with cross-user grant.
        return getContentProviderImpl(caller, name, null, stable, userId);
    }

    public ContentProviderHolder getContentProviderExternal(
            String name, int userId, IBinder token) {
        enforceCallingPermission(android.Manifest.permission.ACCESS_CONTENT_PROVIDERS_EXTERNALLY,
            "Do not have permission in call getContentProviderExternal()");
        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId,
                false, ALLOW_FULL_ONLY, "getContentProvider", null);
        return getContentProviderExternalUnchecked(name, token, userId);
    }

    private ContentProviderHolder getContentProviderExternalUnchecked(String name,
            IBinder token, int userId) {
        return getContentProviderImpl(null, name, token, true, userId);
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

    public void removeContentProviderExternal(String name, IBinder token) {
        enforceCallingPermission(android.Manifest.permission.ACCESS_CONTENT_PROVIDERS_EXTERNALLY,
            "Do not have permission in call removeContentProviderExternal()");
        removeContentProviderExternalUnchecked(name, token, UserHandle.getCallingUserId());
    }

    private void removeContentProviderExternalUnchecked(String name, IBinder token, int userId) {
        synchronized (this) {
            ContentProviderRecord cpr = mProviderMap.getProviderByName(name, userId);
            if(cpr == null) {
                //remove from mProvidersByClass
                if(localLOGV) Slog.v(TAG, name+" content provider not found in providers list");
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
            if (DEBUG_MU)
                Slog.v(TAG_MU, "ProcessRecord uid = " + r.uid);
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
                if (DEBUG_MU)
                    Slog.v(TAG_MU, "ContentProviderRecord uid = " + dst.uid);
                if (dst != null) {
                    ComponentName comp = new ComponentName(dst.info.packageName, dst.info.name);
                    mProviderMap.putProviderByClass(comp, dst);
                    String names[] = dst.info.authority.split(";");
                    for (int j = 0; j < names.length; j++) {
                        mProviderMap.putProviderByName(names[j], dst);
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
                        dst.proc = r;
                        dst.notifyAll();
                    }
                    updateOomAdjLocked(r);
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
            Slog.i(TAG, "Process " + proc.processName + " (pid " + proc.pid
                    + ") early provider death");
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
        enforceCallingPermission(
                android.Manifest.permission.REMOVE_TASKS, "appNotRespondingViaProvider()");

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

        final long token = Binder.clearCallingIdentity();
        try {
            appNotResponding(host, null, null, false, "ContentProvider not responding");
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public final void installSystemProviders() {
        List<ProviderInfo> providers;
        synchronized (this) {
            ProcessRecord app = mProcessNames.get("system", Process.SYSTEM_UID);
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

        mCoreSettingsObserver = new CoreSettingsObserver(this);

        //mUsageStatsService.monitorPackages();
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
        userId = unsafeConvertIncomingUser(userId);
        if (UserHandle.getUserId(callingUid) != userId) {
            if (checkComponentPermission(INTERACT_ACROSS_USERS, callingPid,
                    callingUid, -1, true) == PackageManager.PERMISSION_GRANTED
                    || checkComponentPermission(INTERACT_ACROSS_USERS_FULL, callingPid,
                    callingUid, -1, true) == PackageManager.PERMISSION_GRANTED) {
                clearedIdentity = true;
                ident = Binder.clearCallingIdentity();
            }
        }
        ContentProviderHolder holder = null;
        try {
            holder = getContentProviderExternalUnchecked(name, null, userId);
            if (holder != null) {
                return holder.provider.getType(uri);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Content provider dead retrieving " + uri, e);
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

    // =========================================================
    // GLOBAL MANAGEMENT
    // =========================================================

    final ProcessRecord newProcessRecordLocked(ApplicationInfo info, String customProcess,
            boolean isolated, int isolatedUid) {
        String proc = customProcess != null ? customProcess : info.processName;
        BatteryStatsImpl.Uid.Proc ps = null;
        BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
        int uid = info.uid;
        if (isolated) {
            if (isolatedUid == 0) {
                int userId = UserHandle.getUserId(uid);
                int stepsLeft = Process.LAST_ISOLATED_UID - Process.FIRST_ISOLATED_UID + 1;
                while (true) {
                    if (mNextIsolatedProcessUid < Process.FIRST_ISOLATED_UID
                            || mNextIsolatedProcessUid > Process.LAST_ISOLATED_UID) {
                        mNextIsolatedProcessUid = Process.FIRST_ISOLATED_UID;
                    }
                    uid = UserHandle.getUid(userId, mNextIsolatedProcessUid);
                    mNextIsolatedProcessUid++;
                    if (mIsolatedProcesses.indexOfKey(uid) < 0) {
                        // No process for this uid, use it.
                        break;
                    }
                    stepsLeft--;
                    if (stepsLeft <= 0) {
                        return null;
                    }
                }
            } else {
                // Special case for startIsolatedProcess (internal only), where
                // the uid of the isolated process is specified by the caller.
                uid = isolatedUid;
            }
        }
        return new ProcessRecord(stats, info, proc, uid);
    }

    final ProcessRecord addAppLocked(ApplicationInfo info, boolean isolated,
            String abiOverride) {
        ProcessRecord app;
        if (!isolated) {
            app = getProcessRecordLocked(info.processName, info.uid, true);
        } else {
            app = null;
        }

        if (app == null) {
            app = newProcessRecordLocked(info, null, isolated, 0);
            mProcessNames.put(info.processName, app.uid, app);
            if (isolated) {
                mIsolatedProcesses.put(app.uid, app);
            }
            updateLruProcessLocked(app, false, null);
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

        if ((info.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_PERSISTENT))
                == (ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_PERSISTENT)) {
            app.persistent = true;
            app.maxAdj = ProcessList.PERSISTENT_PROC_ADJ;
        }
        if (app.thread == null && mPersistentStartingProcesses.indexOf(app) < 0) {
            mPersistentStartingProcesses.add(app);
            startProcessLocked(app, "added application", app.processName, abiOverride,
                    null /* entryPoint */, null /* entryPointArgs */);
        }

        return app;
    }

    public void unhandledBack() {
        enforceCallingPermission(android.Manifest.permission.FORCE_BACK,
                "unhandledBack()");

        synchronized(this) {
            final long origId = Binder.clearCallingIdentity();
            try {
                getFocusedStack().unhandledBackLocked();
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    public ParcelFileDescriptor openContentUri(Uri uri) throws RemoteException {
        enforceNotIsolatedCaller("openContentUri");
        final int userId = UserHandle.getCallingUserId();
        String name = uri.getAuthority();
        ContentProviderHolder cph = getContentProviderExternalUnchecked(name, null, userId);
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
                pfd = cph.provider.openFile(null, uri, "r", null);
            } catch (FileNotFoundException e) {
                // do nothing; pfd will be returned null
            } finally {
                // Ensure that whatever happens, we clean up the identity state
                sCallerIdentity.remove();
            }

            // We've got the fd now, so we're done with the provider.
            removeContentProviderExternalUnchecked(name, null, userId);
        } else {
            Slog.d(TAG, "Failed to get provider for authority '" + name + "'");
        }
        return pfd;
    }

    // Actually is sleeping or shutting down or whatever else in the future
    // is an inactive state.
    public boolean isSleepingOrShuttingDown() {
        return mSleeping || mShuttingDown;
    }

    public boolean isSleeping() {
        return mSleeping;
    }

    void goingToSleep() {
        synchronized(this) {
            mWentToSleep = true;
            updateEventDispatchingLocked();
            goToSleepIfNeededLocked();
        }
    }

    void finishRunningVoiceLocked() {
        if (mRunningVoice) {
            mRunningVoice = false;
            goToSleepIfNeededLocked();
        }
    }

    void goToSleepIfNeededLocked() {
        if (mWentToSleep && !mRunningVoice) {
            if (!mSleeping) {
                mSleeping = true;
                mStackSupervisor.goingToSleepLocked();

                // Initialize the wake times of all processes.
                checkExcessivePowerUsageLocked(false);
                mHandler.removeMessages(CHECK_EXCESSIVE_WAKE_LOCKS_MSG);
                Message nmsg = mHandler.obtainMessage(CHECK_EXCESSIVE_WAKE_LOCKS_MSG);
                mHandler.sendMessageDelayed(nmsg, POWER_CHECK_DELAY);
            }
        }
    }

    void notifyTaskPersisterLocked(TaskRecord task, boolean flush) {
        if (task != null && task.stack != null && task.stack.isHomeStack()) {
            // Never persist the home stack.
            return;
        }
        mTaskPersister.wakeup(task, flush);
    }

    @Override
    public boolean shutdown(int timeout) {
        if (checkCallingPermission(android.Manifest.permission.SHUTDOWN)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.SHUTDOWN);
        }

        boolean timedout = false;

        synchronized(this) {
            mShuttingDown = true;
            updateEventDispatchingLocked();
            timedout = mStackSupervisor.shutdownLocked(timeout);
        }

        mAppOpsService.shutdown();
        if (mUsageStatsService != null) {
            mUsageStatsService.prepareShutdown();
        }
        mBatteryStatsService.shutdown();
        synchronized (this) {
            mProcessStats.shutdownLocked();
        }
        notifyTaskPersisterLocked(null, true);

        return timedout;
    }
    
    public final void activitySlept(IBinder token) {
        if (localLOGV) Slog.v(TAG, "Activity slept: token=" + token);

        final long origId = Binder.clearCallingIdentity();

        synchronized (this) {
            final ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r != null) {
                mStackSupervisor.activitySleptLocked(r);
            }
        }

        Binder.restoreCallingIdentity(origId);
    }

    void logLockScreen(String msg) {
        if (DEBUG_LOCKSCREEN) Slog.d(TAG, Debug.getCallers(2) + ":" + msg +
                " mLockScreenShown=" + mLockScreenShown + " mWentToSleep=" +
                mWentToSleep + " mSleeping=" + mSleeping);
    }

    private void comeOutOfSleepIfNeededLocked() {
        if ((!mWentToSleep && !mLockScreenShown) || mRunningVoice) {
            if (mSleeping) {
                mSleeping = false;
                mStackSupervisor.comeOutOfSleepIfNeededLocked();
            }
        }
    }

    void wakingUp() {
        synchronized(this) {
            mWentToSleep = false;
            updateEventDispatchingLocked();
            comeOutOfSleepIfNeededLocked();
        }
    }

    void startRunningVoiceLocked() {
        if (!mRunningVoice) {
            mRunningVoice = true;
            comeOutOfSleepIfNeededLocked();
        }
    }

    private void updateEventDispatchingLocked() {
        mWindowManager.setEventDispatching(mBooted && !mWentToSleep && !mShuttingDown);
    }

    public void setLockScreenShown(boolean shown) {
        if (checkCallingPermission(android.Manifest.permission.DEVICE_POWER)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.DEVICE_POWER);
        }

        synchronized(this) {
            long ident = Binder.clearCallingIdentity();
            try {
                if (DEBUG_LOCKSCREEN) logLockScreen(" shown=" + shown);
                mLockScreenShown = shown;
                comeOutOfSleepIfNeededLocked();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
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

    void setOpenGlTraceApp(ApplicationInfo app, String processName) {
        synchronized (this) {
            boolean isDebuggable = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
            if (!isDebuggable) {
                if ((app.flags&ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
                    throw new SecurityException("Process not debuggable: " + app.packageName);
                }
            }

            mOpenGlTraceApp = processName;
        }
    }

    void setProfileApp(ApplicationInfo app, String processName, String profileFile,
            ParcelFileDescriptor profileFd, boolean autoStopProfiler) {
        synchronized (this) {
            boolean isDebuggable = "1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"));
            if (!isDebuggable) {
                if ((app.flags&ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
                    throw new SecurityException("Process not debuggable: " + app.packageName);
                }
            }
            mProfileApp = processName;
            mProfileFile = profileFile;
            if (mProfileFd != null) {
                try {
                    mProfileFd.close();
                } catch (IOException e) {
                }
                mProfileFd = null;
            }
            mProfileFd = profileFd;
            mProfileType = 0;
            mAutoStopProfiler = autoStopProfiler;
        }
    }

    @Override
    public void setAlwaysFinish(boolean enabled) {
        enforceCallingPermission(android.Manifest.permission.SET_ALWAYS_FINISH,
                "setAlwaysFinish()");

        Settings.Global.putInt(
                mContext.getContentResolver(),
                Settings.Global.ALWAYS_FINISH_ACTIVITIES, enabled ? 1 : 0);
        
        synchronized (this) {
            mAlwaysFinishActivities = enabled;
        }
    }

    @Override
    public void setActivityController(IActivityController controller) {
        enforceCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER,
                "setActivityController()");
        synchronized (this) {
            mController = controller;
            Watchdog.getInstance().setActivityController(controller);
        }
    }

    @Override
    public void setUserIsMonkey(boolean userIsMonkey) {
        synchronized (this) {
            synchronized (mPidsSelfLocked) {
                final int callingPid = Binder.getCallingPid();
                ProcessRecord precessRecord = mPidsSelfLocked.get(callingPid);
                if (precessRecord == null) {
                    throw new SecurityException("Unknown process: " + callingPid);
                }
                if (precessRecord.instrumentationUiAutomationConnection  == null) {
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
            return (mUserIsMonkey || mController != null);
        }
    }

    public void requestBugReport() {
        enforceCallingPermission(android.Manifest.permission.DUMP, "requestBugReport");
        SystemProperties.set("ctl.start", "bugreport");
    }

    public static long getInputDispatchingTimeoutLocked(ActivityRecord r) {
        return r != null ? getInputDispatchingTimeoutLocked(r.app) : KEY_DISPATCHING_TIMEOUT;
    }

    public static long getInputDispatchingTimeoutLocked(ProcessRecord r) {
        if (r != null && (r.instrumentationClass != null || r.usingWrapper)) {
            return INSTRUMENTATION_KEY_DISPATCHING_TIMEOUT;
        }
        return KEY_DISPATCHING_TIMEOUT;
    }

    @Override
    public long inputDispatchingTimedOut(int pid, final boolean aboveSystem, String reason) {
        if (checkCallingPermission(android.Manifest.permission.FILTER_EVENTS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.FILTER_EVENTS);
        }
        ProcessRecord proc;
        long timeout;
        synchronized (this) {
            synchronized (mPidsSelfLocked) {
                proc = mPidsSelfLocked.get(pid);
            }
            timeout = getInputDispatchingTimeoutLocked(proc);
        }

        if (!inputDispatchingTimedOut(proc, null, null, aboveSystem, reason)) {
            return -1;
        }

        return timeout;
    }

    /**
     * Handle input dispatching timeouts.
     * Returns whether input dispatching should be aborted or not.
     */
    public boolean inputDispatchingTimedOut(final ProcessRecord proc,
            final ActivityRecord activity, final ActivityRecord parent,
            final boolean aboveSystem, String reason) {
        if (checkCallingPermission(android.Manifest.permission.FILTER_EVENTS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.FILTER_EVENTS);
        }

        final String annotation;
        if (reason == null) {
            annotation = "Input dispatching timed out";
        } else {
            annotation = "Input dispatching timed out (" + reason + ")";
        }

        if (proc != null) {
            synchronized (this) {
                if (proc.debugging) {
                    return false;
                }

                if (mDidDexOpt) {
                    // Give more time since we were dexopting.
                    mDidDexOpt = false;
                    return false;
                }

                if (proc.instrumentationClass != null) {
                    Bundle info = new Bundle();
                    info.putString("shortMsg", "keyDispatchingTimedOut");
                    info.putString("longMsg", annotation);
                    finishInstrumentationLocked(proc, Activity.RESULT_CANCELED, info);
                    return true;
                }
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    appNotResponding(proc, activity, parent, aboveSystem, annotation);
                }
            });
        }

        return true;
    }

    public Bundle getAssistContextExtras(int requestType) {
        enforceCallingPermission(android.Manifest.permission.GET_TOP_ACTIVITY_INFO,
                "getAssistContextExtras()");
        PendingAssistExtras pae;
        Bundle extras = new Bundle();
        synchronized (this) {
            ActivityRecord activity = getFocusedStack().mResumedActivity;
            if (activity == null) {
                Slog.w(TAG, "getAssistContextExtras failed: no resumed activity");
                return null;
            }
            extras.putString(Intent.EXTRA_ASSIST_PACKAGE, activity.packageName);
            if (activity.app == null || activity.app.thread == null) {
                Slog.w(TAG, "getAssistContextExtras failed: no process for " + activity);
                return extras;
            }
            if (activity.app.pid == Binder.getCallingPid()) {
                Slog.w(TAG, "getAssistContextExtras failed: request process same as " + activity);
                return extras;
            }
            pae = new PendingAssistExtras(activity);
            try {
                activity.app.thread.requestAssistContextExtras(activity.appToken, pae,
                        requestType);
                mPendingAssistExtras.add(pae);
                mHandler.postDelayed(pae, PENDING_ASSIST_EXTRAS_TIMEOUT);
            } catch (RemoteException e) {
                Slog.w(TAG, "getAssistContextExtras failed: crash calling " + activity);
                return extras;
            }
        }
        synchronized (pae) {
            while (!pae.haveResult) {
                try {
                    pae.wait();
                } catch (InterruptedException e) {
                }
            }
            if (pae.result != null) {
                extras.putBundle(Intent.EXTRA_ASSIST_CONTEXT, pae.result);
            }
        }
        synchronized (this) {
            mPendingAssistExtras.remove(pae);
            mHandler.removeCallbacks(pae);
        }
        return extras;
    }

    public void reportAssistContextExtras(IBinder token, Bundle extras) {
        PendingAssistExtras pae = (PendingAssistExtras)token;
        synchronized (pae) {
            pae.result = extras;
            pae.haveResult = true;
            pae.notifyAll();
        }
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
    public boolean convertFromTranslucent(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                final ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    return false;
                }
                if (r.changeWindowTranslucency(true)) {
                    mWindowManager.setAppFullscreen(token, true);
                    r.task.stack.releaseBackgroundResources();
                    mStackSupervisor.ensureActivitiesVisibleLocked(null, 0);
                    return true;
                }
                return false;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public boolean convertToTranslucent(IBinder token, ActivityOptions options) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                final ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r == null) {
                    return false;
                }
                int index = r.task.mActivities.lastIndexOf(r);
                if (index > 0) {
                    ActivityRecord under = r.task.mActivities.get(index - 1);
                    under.returningOptions = options;
                }
                if (r.changeWindowTranslucency(false)) {
                    r.task.stack.convertToTranslucent(r);
                    mWindowManager.setAppFullscreen(token, false);
                    mStackSupervisor.ensureActivitiesVisibleLocked(null, 0);
                    return true;
                } else {
                    mStackSupervisor.ensureActivitiesVisibleLocked(null, 0);
                    return false;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public boolean requestVisibleBehind(IBinder token, boolean visible) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                final ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r != null) {
                    return mStackSupervisor.requestVisibleBehindLocked(r, visible);
                }
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public boolean isBackgroundVisibleBehind(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                final ActivityStack stack = ActivityRecord.getStackLocked(token);
                final boolean visible = stack == null ? false : stack.hasVisibleBehindActivity();
                if (ActivityStackSupervisor.DEBUG_VISIBLE_BEHIND) Slog.d(TAG,
                        "isBackgroundVisibleBehind: stack=" + stack + " visible=" + visible);
                return visible;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public ActivityOptions getActivityOptions(IBinder token) {
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                final ActivityRecord r = ActivityRecord.isInStackLocked(token);
                if (r != null) {
                    final ActivityOptions activityOptions = r.pendingOptions;
                    r.pendingOptions = null;
                    return activityOptions;
                }
                return null;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void setImmersive(IBinder token, boolean immersive) {
        synchronized(this) {
            final ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                throw new IllegalArgumentException();
            }
            r.immersive = immersive;

            // update associated state if we're frontmost
            if (r == mFocusedActivity) {
                if (DEBUG_IMMERSIVE) {
                    Slog.d(TAG, "Frontmost changed immersion: "+ r);
                }
                applyUpdateLockStateLocked(r);
            }
        }
    }

    @Override
    public boolean isImmersive(IBinder token) {
        synchronized (this) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                throw new IllegalArgumentException();
            }
            return r.immersive;
        }
    }

    public boolean isTopActivityImmersive() {
        enforceNotIsolatedCaller("startActivity");
        synchronized (this) {
            ActivityRecord r = getFocusedStack().topRunningActivityLocked(null);
            return (r != null) ? r.immersive : false;
        }
    }

    @Override
    public boolean isTopOfTask(IBinder token) {
        synchronized (this) {
            ActivityRecord r = ActivityRecord.isInStackLocked(token);
            if (r == null) {
                throw new IllegalArgumentException();
            }
            return r.task.getTopActivity() == r;
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

    public void noteWakeupAlarm(IIntentSender sender, int sourceUid, String sourcePkg) {
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
                    stats.getPackageStatsLocked(sourceUid >= 0 ? sourceUid : uid,
                            sourcePkg != null ? sourcePkg : rec.key.packageName);
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
                    killUnneededProcessLocked(proc, reason);
                    killed = true;
                }
            }
        }
        return killed;
    }

    @Override
    public void killUid(int uid, String reason) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("killUid only available to the system");
        }
        synchronized (this) {
            killPackageProcessesLocked(null, UserHandle.getAppId(uid), UserHandle.getUserId(uid),
                    ProcessList.FOREGROUND_APP_ADJ-1, false, true, true, false,
                    reason != null ? reason : "kill uid");
        }
    }

    @Override
    public boolean killProcessesBelowForeground(String reason) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("killProcessesBelowForeground() only available to system");
        }

        return killProcessesBelowAdj(ProcessList.FOREGROUND_APP_ADJ, reason);
    }

    private boolean killProcessesBelowAdj(int belowAdj, String reason) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
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
                    killUnneededProcessLocked(proc, reason);
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
                Process.killProcess(Process.myPid());
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

            for (int i = mLruProcesses.size() - 1 ; i >= 0 ; i--) {
                ProcessRecord proc = mLruProcesses.get(i);
                if (proc.notCachedSinceIdle) {
                    if (proc.setProcState > ActivityManager.PROCESS_STATE_TOP
                            && proc.setProcState <= ActivityManager.PROCESS_STATE_SERVICE) {
                        if (doKilling && proc.initialIdlePss != 0
                                && proc.lastPss > ((proc.initialIdlePss*3)/2)) {
                            killUnneededProcessLocked(proc, "idle maint (pss " + proc.lastPss
                                    + " from " + proc.initialIdlePss + ")");
                        }
                    }
                } else if (proc.setProcState < ActivityManager.PROCESS_STATE_HOME) {
                    proc.notCachedSinceIdle = true;
                    proc.initialIdlePss = 0;
                    proc.nextPssTime = ProcessList.computeNextPssTime(proc.curProcState, true,
                            isSleeping(), now);
                }
            }

            mHandler.removeMessages(REQUEST_ALL_PSS_MSG);
            mHandler.sendEmptyMessageDelayed(REQUEST_ALL_PSS_MSG, 2*60*1000);
        }
    }

    private void retrieveSettings() {
        final ContentResolver resolver = mContext.getContentResolver();
        String debugApp = Settings.Global.getString(
            resolver, Settings.Global.DEBUG_APP);
        boolean waitForDebugger = Settings.Global.getInt(
            resolver, Settings.Global.WAIT_FOR_DEBUGGER, 0) != 0;
        boolean alwaysFinishActivities = Settings.Global.getInt(
            resolver, Settings.Global.ALWAYS_FINISH_ACTIVITIES, 0) != 0;
        boolean forceRtl = Settings.Global.getInt(
                resolver, Settings.Global.DEVELOPMENT_FORCE_RTL, 0) != 0;
        // Transfer any global setting for forcing RTL layout, into a System Property
        SystemProperties.set(Settings.Global.DEVELOPMENT_FORCE_RTL, forceRtl ? "1":"0");

        Configuration configuration = new Configuration();
        Settings.System.getConfiguration(resolver, configuration);
        if (forceRtl) {
            // This will take care of setting the correct layout direction flags
            configuration.setLayoutDirection(configuration.locale);
        }

        synchronized (this) {
            mDebugApp = mOrigDebugApp = debugApp;
            mWaitForDebugger = mOrigWaitForDebugger = waitForDebugger;
            mAlwaysFinishActivities = alwaysFinishActivities;
            // This happens before any activities are started, so we can
            // change mConfiguration in-place.
            updateConfigurationLocked(configuration, null, false, true);
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
        File fname = new File(systemDir, CALLED_PRE_BOOTS_FILENAME);
        return fname;
    }

    private static ArrayList<ComponentName> readLastDonePreBootReceivers() {
        ArrayList<ComponentName> lastDoneReceivers = new ArrayList<ComponentName>();
        File file = getCalledPreBootReceiversFile();
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            DataInputStream dis = new DataInputStream(new BufferedInputStream(fis, 2048));
            int fvers = dis.readInt();
            if (fvers == LAST_PREBOOT_DELIVERED_FILE_VERSION) {
                String vers = dis.readUTF();
                String codename = dis.readUTF();
                String build = dis.readUTF();
                if (android.os.Build.VERSION.RELEASE.equals(vers)
                        && android.os.Build.VERSION.CODENAME.equals(codename)
                        && android.os.Build.VERSION.INCREMENTAL.equals(build)) {
                    int num = dis.readInt();
                    while (num > 0) {
                        num--;
                        String pkg = dis.readUTF();
                        String cls = dis.readUTF();
                        lastDoneReceivers.add(new ComponentName(pkg, cls));
                    }
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
            fos = new FileOutputStream(file);
            dos = new DataOutputStream(new BufferedOutputStream(fos, 2048));
            dos.writeInt(LAST_PREBOOT_DELIVERED_FILE_VERSION);
            dos.writeUTF(android.os.Build.VERSION.RELEASE);
            dos.writeUTF(android.os.Build.VERSION.CODENAME);
            dos.writeUTF(android.os.Build.VERSION.INCREMENTAL);
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

    private boolean deliverPreBootCompleted(final Runnable onFinishCallback,
            ArrayList<ComponentName> doneReceivers, int userId) {
        boolean waitingUpdate = false;
        Intent intent = new Intent(Intent.ACTION_PRE_BOOT_COMPLETED);
        List<ResolveInfo> ris = null;
        try {
            ris = AppGlobals.getPackageManager().queryIntentReceivers(
                    intent, null, 0, userId);
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

            // For User 0, load the version number. When delivering to a new user, deliver
            // to all receivers.
            if (userId == UserHandle.USER_OWNER) {
                ArrayList<ComponentName> lastDoneReceivers = readLastDonePreBootReceivers();
                for (int i=0; i<ris.size(); i++) {
                    ActivityInfo ai = ris.get(i).activityInfo;
                    ComponentName comp = new ComponentName(ai.packageName, ai.name);
                    if (lastDoneReceivers.contains(comp)) {
                        // We already did the pre boot receiver for this app with the current
                        // platform version, so don't do it again...
                        ris.remove(i);
                        i--;
                        // ...however, do keep it as one that has been done, so we don't
                        // forget about it when rewriting the file of last done receivers.
                        doneReceivers.add(comp);
                    }
                }
            }

            // If primary user, send broadcast to all available users, else just to userId
            final int[] users = userId == UserHandle.USER_OWNER ? getUsersLocked()
                    : new int[] { userId };
            for (int i = 0; i < ris.size(); i++) {
                ActivityInfo ai = ris.get(i).activityInfo;
                ComponentName comp = new ComponentName(ai.packageName, ai.name);
                doneReceivers.add(comp);
                intent.setComponent(comp);
                for (int j=0; j<users.length; j++) {
                    IIntentReceiver finisher = null;
                    // On last receiver and user, set up a completion callback
                    if (i == ris.size() - 1 && j == users.length - 1 && onFinishCallback != null) {
                        finisher = new IIntentReceiver.Stub() {
                            public void performReceive(Intent intent, int resultCode,
                                    String data, Bundle extras, boolean ordered,
                                    boolean sticky, int sendingUser) {
                                // The raw IIntentReceiver interface is called
                                // with the AM lock held, so redispatch to
                                // execute our code without the lock.
                                mHandler.post(onFinishCallback);
                            }
                        };
                    }
                    Slog.i(TAG, "Sending system update to " + intent.getComponent()
                            + " for user " + users[j]);
                    broadcastIntentLocked(null, null, intent, null, finisher,
                            0, null, null, null, AppOpsManager.OP_NONE,
                            true, false, MY_PID, Process.SYSTEM_UID,
                            users[j]);
                    if (finisher != null) {
                        waitingUpdate = true;
                    }
                }
            }
        }

        return waitingUpdate;
    }

    public void systemReady(final Runnable goingCallback) {
        synchronized(this) {
            if (mSystemReady) {
                // If we're done calling all the receivers, run the next "boot phase" passed in
                // by the SystemServer
                if (goingCallback != null) {
                    goingCallback.run();
                }
                return;
            }

            // Make sure we have the current profile info, since it is needed for
            // security checks.
            updateCurrentProfileIdsLocked();

            if (mRecentTasks == null) {
                mRecentTasks = mTaskPersister.restoreTasksLocked();
                if (!mRecentTasks.isEmpty()) {
                    mStackSupervisor.createStackForRestoredTaskHistory(mRecentTasks);
                }
                mTaskPersister.startPersisting();
            }

            // Check to see if there are any update receivers to run.
            if (!mDidUpdate) {
                if (mWaitingUpdate) {
                    return;
                }
                final ArrayList<ComponentName> doneReceivers = new ArrayList<ComponentName>();
                mWaitingUpdate = deliverPreBootCompleted(new Runnable() {
                    public void run() {
                        synchronized (ActivityManagerService.this) {
                            mDidUpdate = true;
                        }
                        writeLastDonePreBootReceivers(doneReceivers);
                        showBootMessage(mContext.getText(
                                R.string.android_upgrading_complete),
                                false);
                        systemReady(goingCallback);
                    }
                }, doneReceivers, UserHandle.USER_OWNER);

                if (mWaitingUpdate) {
                    return;
                }
                mDidUpdate = true;
            }

            mAppOpsService.systemReady();
            mSystemReady = true;
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
                    removeProcessLocked(proc, true, false, "system update done");
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
            
            if (mFactoryTest == FactoryTest.FACTORY_TEST_LOW_LEVEL) {
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

        synchronized (this) {
            readGrantedUriPermissionsLocked();
        }

        if (goingCallback != null) goingCallback.run();

        mBatteryStatsService.noteEvent(BatteryStats.HistoryItem.EVENT_USER_RUNNING_START,
                Integer.toString(mCurrentUserId), mCurrentUserId);
        mBatteryStatsService.noteEvent(BatteryStats.HistoryItem.EVENT_USER_FOREGROUND_START,
                Integer.toString(mCurrentUserId), mCurrentUserId);
        mSystemServiceManager.startUser(mCurrentUserId);

        synchronized (this) {
            if (mFactoryTest != FactoryTest.FACTORY_TEST_LOW_LEVEL) {
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
                                addAppLocked(info, false, null /* ABI override */);
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

            long ident = Binder.clearCallingIdentity();
            try {
                Intent intent = new Intent(Intent.ACTION_USER_STARTED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                        | Intent.FLAG_RECEIVER_FOREGROUND);
                intent.putExtra(Intent.EXTRA_USER_HANDLE, mCurrentUserId);
                broadcastIntentLocked(null, null, intent,
                        null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                        false, false, MY_PID, Process.SYSTEM_UID, mCurrentUserId);
                intent = new Intent(Intent.ACTION_USER_STARTING);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                intent.putExtra(Intent.EXTRA_USER_HANDLE, mCurrentUserId);
                broadcastIntentLocked(null, null, intent,
                        null, new IIntentReceiver.Stub() {
                            @Override
                            public void performReceive(Intent intent, int resultCode, String data,
                                    Bundle extras, boolean ordered, boolean sticky, int sendingUser)
                                    throws RemoteException {
                            }
                        }, 0, null, null,
                        INTERACT_ACROSS_USERS, AppOpsManager.OP_NONE,
                        true, false, MY_PID, Process.SYSTEM_UID, UserHandle.USER_ALL);
            } catch (Throwable t) {
                Slog.wtf(TAG, "Failed sending first user broadcasts", t);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            mStackSupervisor.resumeTopActivitiesLocked();
            sendUserSwitchBroadcastsLocked(-1, mCurrentUserId);
        }
    }

    private boolean makeAppCrashingLocked(ProcessRecord app,
            String shortMsg, String longMsg, String stackTrace) {
        app.crashing = true;
        app.crashingReport = generateProcessError(app,
                ActivityManager.ProcessErrorStateInfo.CRASHED, null, shortMsg, longMsg, stackTrace);
        startAppProblemLocked(app);
        app.stopFreezingAllLocked();
        return handleAppCrashLocked(app, shortMsg, longMsg, stackTrace);
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
                handleAppCrashLocked(app, null, null, null);
                killUnneededProcessLocked(app, "user request after error");
            }
        }
    }

    private boolean handleAppCrashLocked(ProcessRecord app, String shortMsg, String longMsg,
            String stackTrace) {
        long now = SystemClock.uptimeMillis();

        Long crashTime;
        if (!app.isolated) {
            crashTime = mProcessCrashTimes.get(app.info.processName, app.uid);
        } else {
            crashTime = null;
        }
        if (crashTime != null && now < crashTime+ProcessList.MIN_CRASH_INTERVAL) {
            // This process loses!
            Slog.w(TAG, "Process " + app.info.processName
                    + " has crashed too many times: killing!");
            EventLog.writeEvent(EventLogTags.AM_PROCESS_CRASHED_TOO_MUCH,
                    app.userId, app.info.processName, app.uid);
            mStackSupervisor.handleAppCrashLocked(app);
            if (!app.persistent) {
                // We don't want to start this process again until the user
                // explicitly does so...  but for persistent process, we really
                // need to keep it running.  If a persistent process is actually
                // repeatedly crashing, then badness for everyone.
                EventLog.writeEvent(EventLogTags.AM_PROC_BAD, app.userId, app.uid,
                        app.info.processName);
                if (!app.isolated) {
                    // XXX We don't have a way to mark isolated processes
                    // as bad, since they don't have a peristent identity.
                    mBadProcesses.put(app.info.processName, app.uid,
                            new BadProcessInfo(now, shortMsg, longMsg, stackTrace));
                    mProcessCrashTimes.remove(app.info.processName, app.uid);
                }
                app.bad = true;
                app.removed = true;
                // Don't let services in this process be restarted and potentially
                // annoy the user repeatedly.  Unless it is persistent, since those
                // processes run critical code.
                removeProcessLocked(app, false, false, "crash");
                mStackSupervisor.resumeTopActivitiesLocked();
                return false;
            }
            mStackSupervisor.resumeTopActivitiesLocked();
        } else {
            mStackSupervisor.finishTopRunningActivityLocked(app);
        }

        // Bump up the crash count of any services currently running in the proc.
        for (int i=app.services.size()-1; i>=0; i--) {
            // Any services running in the application need to be placed
            // back in the pending list.
            ServiceRecord sr = app.services.valueAt(i);
            sr.crashCount++;
        }

        // If the crashing process is what we consider to be the "home process" and it has been
        // replaced by a third-party app, clear the package preferred activities from packages
        // with a home activity running in the process to prevent a repeatedly crashing app
        // from blocking the user to manually clear the list.
        final ArrayList<ActivityRecord> activities = app.activities;
        if (app == mHomeProcess && activities.size() > 0
                    && (mHomeProcess.info.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ActivityRecord r = activities.get(activityNdx);
                if (r.isHomeActivity()) {
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

        if (!app.isolated) {
            // XXX Can't keep track of crash times for isolated processes,
            // because they don't have a perisistent identity.
            mProcessCrashTimes.put(app.info.processName, app.uid, now);
        }

        if (app.crashHandler != null) mHandler.post(app.crashHandler);
        return true;
    }

    void startAppProblemLocked(ProcessRecord app) {
        // If this app is not running under the current user, then we
        // can't give it a report button because that would require
        // launching the report UI under a different user.
        app.errorReportReceiver = null;

        for (int userId : mCurrentProfileIds) {
            if (app.userId == userId) {
                app.errorReportReceiver = ApplicationErrorReport.getErrorReportReceiver(
                        mContext, app.info.packageName, app.info.flags);
            }
        }
        skipCurrentReceiverLocked(app);
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
    public void handleApplicationCrash(IBinder app, ApplicationErrorReport.CrashInfo crashInfo) {
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

        addErrorToDropBox(eventType, r, processName, null, null, null, null, null, crashInfo);

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
        final String processName = process == null ? "unknown" : process.processName;
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
        final String processName = app == null ? "system_server"
                : (r == null ? "unknown" : r.processName);

        EventLog.writeEvent(EventLogTags.AM_WTF,
                UserHandle.getUserId(Binder.getCallingUid()), Binder.getCallingPid(),
                processName,
                r == null ? -1 : r.info.flags,
                tag, crashInfo.exceptionMessage);

        addErrorToDropBox("wtf", r, processName, null, null, tag, null, null, crashInfo);

        if (r != null && r.pid != Process.myPid() &&
                Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.WTF_IS_FATAL, 0) != 0) {
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
            final int NP = mProcessNames.getMap().size();
            for (int ip=0; ip<NP; ip++) {
                SparseArray<ProcessRecord> apps = mProcessNames.getMap().valueAt(ip);
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
            int flags = process.info.flags;
            IPackageManager pm = AppGlobals.getPackageManager();
            sb.append("Flags: 0x").append(Integer.toString(flags, 16)).append("\n");
            for (int ip=0; ip<process.pkgList.size(); ip++) {
                String pkg = process.pkgList.keyAt(ip);
                sb.append("Package: ").append(pkg);
                try {
                    PackageInfo pi = pm.getPackageInfo(pkg, 0, UserHandle.getCallingUserId());
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
            ProcessRecord process, String processName, ActivityRecord activity,
            ActivityRecord parent, String subject,
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
        appendDropBoxProcessHeaders(process, processName, sb);
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
                        sb.append(FileUtils.readTextFile(logFile, DROPBOX_MAX_SIZE,
                                    "\n\n[[TRUNCATED]]"));
                    } catch (IOException e) {
                        Slog.e(TAG, "Error reading " + logFile, e);
                    }
                }
                if (crashInfo != null && crashInfo.stackTrace != null) {
                    sb.append(crashInfo.stackTrace);
                }

                String setting = Settings.Global.ERROR_LOGCAT_PREFIX + dropboxTag;
                int lines = Settings.Global.getInt(mContext.getContentResolver(), setting, 0);
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

        if (process == null) {
            // If process is null, we are being called from some internal code
            // and may be about to die -- run this synchronously.
            worker.run();
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
                    int uid = r != null ? r.info.uid : Binder.getCallingUid();
                    if (!mController.appCrashed(name, pid,
                            shortMsg, longMsg, timeMillis, crashInfo.stackTrace)) {
                        if ("1".equals(SystemProperties.get(SYSTEM_DEBUGGABLE, "0"))
                                && "Native crash".equals(crashInfo.exceptionClassName)) {
                            Slog.w(TAG, "Skip killing native crashed app " + name
                                    + "(" + pid + ") during testing");
                        } else {
                            Slog.w(TAG, "Force-killing crashed app " + name
                                    + " at watcher's request");
                            Process.killProcess(pid);
                            if (r != null) {
                                Process.killProcessGroup(uid, pid);
                            }
                        }
                        return;
                    }
                } catch (RemoteException e) {
                    mController = null;
                    Watchdog.getInstance().setActivityController(null);
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
            if (r != null && !r.isolated) {
                // XXX Can't keep track of crash time for isolated processes,
                // since they don't have a persistent identity.
                mProcessCrashTimes.put(r.info.processName, r.uid,
                        SystemClock.uptimeMillis());
            }
            if (res == AppErrorDialog.FORCE_QUIT_AND_REPORT) {
                appErrorIntent = createAppErrorIntentLocked(r, timeMillis, crashInfo);
            }
        }

        if (appErrorIntent != null) {
            try {
                mContext.startActivityAsUser(appErrorIntent, new UserHandle(r.userId));
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

        if (!r.crashing && !r.notResponding && !r.forceCrashReport) {
            return null;
        }

        ApplicationErrorReport report = new ApplicationErrorReport();
        report.packageName = r.info.packageName;
        report.installerPackageName = r.errorReportReceiver.getPackageName();
        report.processName = r.processName;
        report.time = timeMillis;
        report.systemApp = (r.info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

        if (r.crashing || r.forceCrashReport) {
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
        enforceNotIsolatedCaller("getProcessesInErrorState");
        // assume our apps are happy - lazy create the list
        List<ActivityManager.ProcessErrorStateInfo> errList = null;

        final boolean allUsers = ActivityManager.checkUidPermission(INTERACT_ACROSS_USERS_FULL,
                Binder.getCallingUid()) == PackageManager.PERMISSION_GRANTED;
        int userId = UserHandle.getUserId(Binder.getCallingUid());

        synchronized (this) {

            // iterate across all processes
            for (int i=mLruProcesses.size()-1; i>=0; i--) {
                ProcessRecord app = mLruProcesses.get(i);
                if (!allUsers && app.userId != userId) {
                    continue;
                }
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

    static int procStateToImportance(int procState, int memAdj,
            ActivityManager.RunningAppProcessInfo currApp) {
        int imp = ActivityManager.RunningAppProcessInfo.procStateToImportance(procState);
        if (imp == ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND) {
            currApp.lru = memAdj;
        } else {
            currApp.lru = 0;
        }
        return imp;
    }

    private void fillInProcMemInfo(ProcessRecord app,
            ActivityManager.RunningAppProcessInfo outInfo) {
        outInfo.pid = app.pid;
        outInfo.uid = app.info.uid;
        if (mHeavyWeightProcess == app) {
            outInfo.flags |= ActivityManager.RunningAppProcessInfo.FLAG_CANT_SAVE_STATE;
        }
        if (app.persistent) {
            outInfo.flags |= ActivityManager.RunningAppProcessInfo.FLAG_PERSISTENT;
        }
        if (app.activities.size() > 0) {
            outInfo.flags |= ActivityManager.RunningAppProcessInfo.FLAG_HAS_ACTIVITIES;
        }
        outInfo.lastTrimLevel = app.trimMemoryLevel;
        int adj = app.curAdj;
        int procState = app.curProcState;
        outInfo.importance = procStateToImportance(procState, adj, outInfo);
        outInfo.importanceReasonCode = app.adjTypeCode;
        outInfo.processState = app.curProcState;
    }

    public List<ActivityManager.RunningAppProcessInfo> getRunningAppProcesses() {
        enforceNotIsolatedCaller("getRunningAppProcesses");
        // Lazy instantiation of list
        List<ActivityManager.RunningAppProcessInfo> runList = null;
        final boolean allUsers = ActivityManager.checkUidPermission(INTERACT_ACROSS_USERS_FULL,
                Binder.getCallingUid()) == PackageManager.PERMISSION_GRANTED;
        int userId = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (this) {
            // Iterate across all processes
            for (int i=mLruProcesses.size()-1; i>=0; i--) {
                ProcessRecord app = mLruProcesses.get(i);
                if (!allUsers && app.userId != userId) {
                    continue;
                }
                if ((app.thread != null) && (!app.crashing && !app.notResponding)) {
                    // Generate process state info for running application
                    ActivityManager.RunningAppProcessInfo currApp = 
                        new ActivityManager.RunningAppProcessInfo(app.processName,
                                app.pid, app.getPackageList());
                    fillInProcMemInfo(app, currApp);
                    if (app.adjSource instanceof ProcessRecord) {
                        currApp.importanceReasonPid = ((ProcessRecord)app.adjSource).pid;
                        currApp.importanceReasonImportance =
                                ActivityManager.RunningAppProcessInfo.procStateToImportance(
                                        app.adjSourceProcState);
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
    public void getMyMemoryState(ActivityManager.RunningAppProcessInfo outInfo) {
        enforceNotIsolatedCaller("getMyMemoryState");
        synchronized (this) {
            ProcessRecord proc;
            synchronized (mPidsSelfLocked) {
                proc = mPidsSelfLocked.get(Binder.getCallingPid());
            }
            fillInProcMemInfo(proc, outInfo);
        }
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
            } else if ("-h".equals(opt)) {
                pw.println("Activity manager dump options:");
                pw.println("  [-a] [-c] [-h] [cmd] ...");
                pw.println("  cmd may be one of:");
                pw.println("    a[ctivities]: activity stack state");
                pw.println("    r[recents]: recent activities state");
                pw.println("    b[roadcasts] [PACKAGE_NAME] [history [-s]]: broadcast state");
                pw.println("    i[ntents] [PACKAGE_NAME]: pending intent state");
                pw.println("    p[rocesses] [PACKAGE_NAME]: process state");
                pw.println("    o[om]: out of memory management");
                pw.println("    prov[iders] [COMP_SPEC ...]: content provider state");
                pw.println("    provider [COMP_SPEC]: provider client-side state");
                pw.println("    s[ervices] [COMP_SPEC ...]: service state");
                pw.println("    service [COMP_SPEC]: service client-side state");
                pw.println("    package [PACKAGE_NAME]: all state related to given package");
                pw.println("    all: dump all activities");
                pw.println("    top: dump the top activity");
                pw.println("  cmd may also be a COMP_SPEC to dump activities.");
                pw.println("  COMP_SPEC may be a component name (com.foo/.myApp),");
                pw.println("    a partial substring in a component name, a");
                pw.println("    hex object identifier.");
                pw.println("  -a: include all available server state.");
                pw.println("  -c: include client state.");
                return;
            } else {
                pw.println("Unknown argument: " + opt + "; use -h for help");
            }
        }

        long origId = Binder.clearCallingIdentity();
        boolean more = false;
        // Is the caller requesting to dump a particular piece of data?
        if (opti < args.length) {
            String cmd = args[opti];
            opti++;
            if ("activities".equals(cmd) || "a".equals(cmd)) {
                synchronized (this) {
                    dumpActivitiesLocked(fd, pw, args, opti, true, dumpClient, null);
                }
            } else if ("recents".equals(cmd) || "r".equals(cmd)) {
                synchronized (this) {
                    dumpRecentsLocked(fd, pw, args, opti, true, null);
                }
            } else if ("broadcasts".equals(cmd) || "b".equals(cmd)) {
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
                synchronized (this) {
                    dumpBroadcastsLocked(fd, pw, args, opti, true, name);
                }
            } else if ("intents".equals(cmd) || "i".equals(cmd)) {
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
                synchronized (this) {
                    dumpPendingIntentsLocked(fd, pw, args, opti, true, name);
                }
            } else if ("processes".equals(cmd) || "p".equals(cmd)) {
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
                synchronized (this) {
                    dumpProcessesLocked(fd, pw, args, opti, true, name);
                }
            } else if ("oom".equals(cmd) || "o".equals(cmd)) {
                synchronized (this) {
                    dumpOomLocked(fd, pw, args, opti, true);
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
            } else if ("services".equals(cmd) || "s".equals(cmd)) {
                synchronized (this) {
                    mServices.dumpServicesLocked(fd, pw, args, opti, true, dumpClient, null);
                }
            } else {
                // Dumping a single activity?
                if (!dumpActivity(fd, pw, cmd, args, opti, dumpAll)) {
                    pw.println("Bad activity command, or no activities match: " + cmd);
                    pw.println("Use -h for help.");
                }
            }
            if (!more) {
                Binder.restoreCallingIdentity(origId);
                return;
            }
        }

        // No piece of data specified, dump everything.
        synchronized (this) {
            dumpPendingIntentsLocked(fd, pw, args, opti, dumpAll, dumpPackage);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            dumpBroadcastsLocked(fd, pw, args, opti, dumpAll, dumpPackage);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            dumpProvidersLocked(fd, pw, args, opti, dumpAll, dumpPackage);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            mServices.dumpServicesLocked(fd, pw, args, opti, dumpAll, dumpClient, dumpPackage);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            dumpRecentsLocked(fd, pw, args, opti, dumpAll, dumpPackage);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            dumpActivitiesLocked(fd, pw, args, opti, dumpAll, dumpClient, dumpPackage);
            pw.println();
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
            }
            dumpProcessesLocked(fd, pw, args, opti, dumpAll, dumpPackage);
        }
        Binder.restoreCallingIdentity(origId);
    }

    void dumpActivitiesLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, boolean dumpClient, String dumpPackage) {
        pw.println("ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)");

        boolean printedAnything = mStackSupervisor.dumpActivitiesLocked(fd, pw, dumpAll, dumpClient,
                dumpPackage);
        boolean needSep = printedAnything;

        boolean printed = ActivityStackSupervisor.printThisActivity(pw, mFocusedActivity,
                dumpPackage, needSep, "  mFocusedActivity: ");
        if (printed) {
            printedAnything = true;
            needSep = false;
        }

        if (dumpPackage == null) {
            if (needSep) {
                pw.println();
            }
            needSep = true;
            printedAnything = true;
            mStackSupervisor.dump(pw, "  ");
        }

        if (!printedAnything) {
            pw.println("  (nothing)");
        }
    }

    void dumpRecentsLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage) {
        pw.println("ACTIVITY MANAGER RECENT ACTIVITIES (dumpsys activity recents)");

        boolean printedAnything = false;

        if (mRecentTasks.size() > 0) {
            boolean printedHeader = false;

            final int N = mRecentTasks.size();
            for (int i=0; i<N; i++) {
                TaskRecord tr = mRecentTasks.get(i);
                if (dumpPackage != null) {
                    if (tr.realActivity == null ||
                            !dumpPackage.equals(tr.realActivity)) {
                        continue;
                    }
                }
                if (!printedHeader) {
                    pw.println("  Recent tasks:");
                    printedHeader = true;
                    printedAnything = true;
                }
                pw.print("  * Recent #"); pw.print(i); pw.print(": ");
                        pw.println(tr);
                if (dumpAll) {
                    mRecentTasks.get(i).dump(pw, "    ");
                }
            }
        }

        if (!printedAnything) {
            pw.println("  (nothing)");
        }
    }

    void dumpProcessesLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage) {
        boolean needSep = false;
        boolean printedAnything = false;
        int numPers = 0;

        pw.println("ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)");

        if (dumpAll) {
            final int NP = mProcessNames.getMap().size();
            for (int ip=0; ip<NP; ip++) {
                SparseArray<ProcessRecord> procs = mProcessNames.getMap().valueAt(ip);
                final int NA = procs.size();
                for (int ia=0; ia<NA; ia++) {
                    ProcessRecord r = procs.valueAt(ia);
                    if (dumpPackage != null && !r.pkgList.containsKey(dumpPackage)) {
                        continue;
                    }
                    if (!needSep) {
                        pw.println("  All known processes:");
                        needSep = true;
                        printedAnything = true;
                    }
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

        if (mIsolatedProcesses.size() > 0) {
            boolean printed = false;
            for (int i=0; i<mIsolatedProcesses.size(); i++) {
                ProcessRecord r = mIsolatedProcesses.valueAt(i);
                if (dumpPackage != null && !r.pkgList.containsKey(dumpPackage)) {
                    continue;
                }
                if (!printed) {
                    if (needSep) {
                        pw.println();
                    }
                    pw.println("  Isolated process list (sorted by uid):");
                    printedAnything = true;
                    printed = true;
                    needSep = true;
                }
                pw.println(String.format("%sIsolated #%2d: %s",
                        "    ", i, r.toString()));
            }
        }

        if (mLruProcesses.size() > 0) {
            if (needSep) {
                pw.println();
            }
            pw.print("  Process LRU list (sorted by oom_adj, "); pw.print(mLruProcesses.size());
                    pw.print(" total, non-act at ");
                    pw.print(mLruProcesses.size()-mLruProcessActivityStart);
                    pw.print(", non-svc at ");
                    pw.print(mLruProcesses.size()-mLruProcessServiceStart);
                    pw.println("):");
            dumpProcessOomList(pw, this, mLruProcesses, "    ", "Proc", "PERS", false, dumpPackage);
            needSep = true;
            printedAnything = true;
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
                        printedAnything = true;
                    }
                    pw.print("    PID #"); pw.print(mPidsSelfLocked.keyAt(i));
                        pw.print(": "); pw.println(mPidsSelfLocked.valueAt(i));
                }
            }
        }
        
        if (mForegroundProcesses.size() > 0) {
            synchronized (mPidsSelfLocked) {
                boolean printed = false;
                for (int i=0; i<mForegroundProcesses.size(); i++) {
                    ProcessRecord r = mPidsSelfLocked.get( 
                            mForegroundProcesses.valueAt(i).pid);
                    if (dumpPackage != null && (r == null
                            || !r.pkgList.containsKey(dumpPackage))) {
                        continue;
                    }
                    if (!printed) {
                        if (needSep) pw.println();
                        needSep = true;
                        pw.println("  Foreground Processes:");
                        printed = true;
                        printedAnything = true;
                    }
                    pw.print("    PID #"); pw.print(mForegroundProcesses.keyAt(i));
                            pw.print(": "); pw.println(mForegroundProcesses.valueAt(i));
                }
            }
        }
        
        if (mPersistentStartingProcesses.size() > 0) {
            if (needSep) pw.println();
            needSep = true;
            printedAnything = true;
            pw.println("  Persisent processes that are starting:");
            dumpProcessList(pw, this, mPersistentStartingProcesses, "    ",
                    "Starting Norm", "Restarting PERS", dumpPackage);
        }

        if (mRemovedProcesses.size() > 0) {
            if (needSep) pw.println();
            needSep = true;
            printedAnything = true;
            pw.println("  Processes that are being removed:");
            dumpProcessList(pw, this, mRemovedProcesses, "    ",
                    "Removed Norm", "Removed PERS", dumpPackage);
        }
        
        if (mProcessesOnHold.size() > 0) {
            if (needSep) pw.println();
            needSep = true;
            printedAnything = true;
            pw.println("  Processes that are on old until the system is ready:");
            dumpProcessList(pw, this, mProcessesOnHold, "    ",
                    "OnHold Norm", "OnHold PERS", dumpPackage);
        }

        needSep = dumpProcessesToGc(fd, pw, args, opti, needSep, dumpAll, dumpPackage);
        
        if (mProcessCrashTimes.getMap().size() > 0) {
            boolean printed = false;
            long now = SystemClock.uptimeMillis();
            final ArrayMap<String, SparseArray<Long>> pmap = mProcessCrashTimes.getMap();
            final int NP = pmap.size();
            for (int ip=0; ip<NP; ip++) {
                String pname = pmap.keyAt(ip);
                SparseArray<Long> uids = pmap.valueAt(ip);
                final int N = uids.size();
                for (int i=0; i<N; i++) {
                    int puid = uids.keyAt(i);
                    ProcessRecord r = mProcessNames.get(pname, puid);
                    if (dumpPackage != null && (r == null
                            || !r.pkgList.containsKey(dumpPackage))) {
                        continue;
                    }
                    if (!printed) {
                        if (needSep) pw.println();
                        needSep = true;
                        pw.println("  Time since processes crashed:");
                        printed = true;
                        printedAnything = true;
                    }
                    pw.print("    Process "); pw.print(pname);
                            pw.print(" uid "); pw.print(puid);
                            pw.print(": last crashed ");
                            TimeUtils.formatDuration(now-uids.valueAt(i), pw);
                            pw.println(" ago");
                }
            }
        }

        if (mBadProcesses.getMap().size() > 0) {
            boolean printed = false;
            final ArrayMap<String, SparseArray<BadProcessInfo>> pmap = mBadProcesses.getMap();
            final int NP = pmap.size();
            for (int ip=0; ip<NP; ip++) {
                String pname = pmap.keyAt(ip);
                SparseArray<BadProcessInfo> uids = pmap.valueAt(ip);
                final int N = uids.size();
                for (int i=0; i<N; i++) {
                    int puid = uids.keyAt(i);
                    ProcessRecord r = mProcessNames.get(pname, puid);
                    if (dumpPackage != null && (r == null
                            || !r.pkgList.containsKey(dumpPackage))) {
                        continue;
                    }
                    if (!printed) {
                        if (needSep) pw.println();
                        needSep = true;
                        pw.println("  Bad processes:");
                        printedAnything = true;
                    }
                    BadProcessInfo info = uids.valueAt(i);
                    pw.print("    Bad process "); pw.print(pname);
                            pw.print(" uid "); pw.print(puid);
                            pw.print(": crashed at time "); pw.println(info.time);
                    if (info.shortMsg != null) {
                        pw.print("      Short msg: "); pw.println(info.shortMsg);
                    }
                    if (info.longMsg != null) {
                        pw.print("      Long msg: "); pw.println(info.longMsg);
                    }
                    if (info.stack != null) {
                        pw.println("      Stack:");
                        int lastPos = 0;
                        for (int pos=0; pos<info.stack.length(); pos++) {
                            if (info.stack.charAt(pos) == '\n') {
                                pw.print("        ");
                                pw.write(info.stack, lastPos, pos-lastPos);
                                pw.println();
                                lastPos = pos+1;
                            }
                        }
                        if (lastPos < info.stack.length()) {
                            pw.print("        ");
                            pw.write(info.stack, lastPos, info.stack.length()-lastPos);
                            pw.println();
                        }
                    }
                }
            }
        }

        if (dumpPackage == null) {
            pw.println();
            needSep = false;
            pw.println("  mStartedUsers:");
            for (int i=0; i<mStartedUsers.size(); i++) {
                UserStartedState uss = mStartedUsers.valueAt(i);
                pw.print("    User #"); pw.print(uss.mHandle.getIdentifier());
                        pw.print(": "); uss.dump("", pw);
            }
            pw.print("  mStartedUserArray: [");
            for (int i=0; i<mStartedUserArray.length; i++) {
                if (i > 0) pw.print(", ");
                pw.print(mStartedUserArray[i]);
            }
            pw.println("]");
            pw.print("  mUserLru: [");
            for (int i=0; i<mUserLru.size(); i++) {
                if (i > 0) pw.print(", ");
                pw.print(mUserLru.get(i));
            }
            pw.println("]");
            if (dumpAll) {
                pw.print("  mStartedUserArray: "); pw.println(Arrays.toString(mStartedUserArray));
            }
            synchronized (mUserProfileGroupIdsSelfLocked) {
                if (mUserProfileGroupIdsSelfLocked.size() > 0) {
                    pw.println("  mUserProfileGroupIds:");
                    for (int i=0; i<mUserProfileGroupIdsSelfLocked.size(); i++) {
                        pw.print("    User #");
                        pw.print(mUserProfileGroupIdsSelfLocked.keyAt(i));
                        pw.print(" -> profile #");
                        pw.println(mUserProfileGroupIdsSelfLocked.valueAt(i));
                    }
                }
            }
        }
        if (mHomeProcess != null && (dumpPackage == null
                || mHomeProcess.pkgList.containsKey(dumpPackage))) {
            if (needSep) {
                pw.println();
                needSep = false;
            }
            pw.println("  mHomeProcess: " + mHomeProcess);
        }
        if (mPreviousProcess != null && (dumpPackage == null
                || mPreviousProcess.pkgList.containsKey(dumpPackage))) {
            if (needSep) {
                pw.println();
                needSep = false;
            }
            pw.println("  mPreviousProcess: " + mPreviousProcess);
        }
        if (dumpAll) {
            StringBuilder sb = new StringBuilder(128);
            sb.append("  mPreviousProcessVisibleTime: ");
            TimeUtils.formatDuration(mPreviousProcessVisibleTime, sb);
            pw.println(sb);
        }
        if (mHeavyWeightProcess != null && (dumpPackage == null
                || mHeavyWeightProcess.pkgList.containsKey(dumpPackage))) {
            if (needSep) {
                pw.println();
                needSep = false;
            }
            pw.println("  mHeavyWeightProcess: " + mHeavyWeightProcess);
        }
        if (dumpPackage == null) {
            pw.println("  mConfiguration: " + mConfiguration);
        }
        if (dumpAll) {
            pw.println("  mConfigWillChange: " + getFocusedStack().mConfigWillChange);
            if (mCompatModePackages.getPackages().size() > 0) {
                boolean printed = false;
                for (Map.Entry<String, Integer> entry
                        : mCompatModePackages.getPackages().entrySet()) {
                    String pkg = entry.getKey();
                    int mode = entry.getValue();
                    if (dumpPackage != null && !dumpPackage.equals(pkg)) {
                        continue;
                    }
                    if (!printed) {
                        pw.println("  mScreenCompatPackages:");
                        printed = true;
                    }
                    pw.print("    "); pw.print(pkg); pw.print(": ");
                            pw.print(mode); pw.println();
                }
            }
        }
        if (dumpPackage == null) {
            if (mSleeping || mWentToSleep || mLockScreenShown) {
                pw.println("  mSleeping=" + mSleeping + " mWentToSleep=" + mWentToSleep
                        + " mLockScreenShown " + mLockScreenShown);
            }
            if (mShuttingDown || mRunningVoice) {
                pw.print("  mShuttingDown=" + mShuttingDown + " mRunningVoice=" + mRunningVoice);
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
        if (mOpenGlTraceApp != null) {
            if (dumpPackage == null || dumpPackage.equals(mOpenGlTraceApp)) {
                if (needSep) {
                    pw.println();
                    needSep = false;
                }
                pw.println("  mOpenGlTraceApp=" + mOpenGlTraceApp);
            }
        }
        if (mProfileApp != null || mProfileProc != null || mProfileFile != null
                || mProfileFd != null) {
            if (dumpPackage == null || dumpPackage.equals(mProfileApp)) {
                if (needSep) {
                    pw.println();
                    needSep = false;
                }
                pw.println("  mProfileApp=" + mProfileApp + " mProfileProc=" + mProfileProc);
                pw.println("  mProfileFile=" + mProfileFile + " mProfileFd=" + mProfileFd);
                pw.println("  mProfileType=" + mProfileType + " mAutoStopProfiler="
                        + mAutoStopProfiler);
            }
        }
        if (dumpPackage == null) {
            if (mAlwaysFinishActivities || mController != null) {
                pw.println("  mAlwaysFinishActivities=" + mAlwaysFinishActivities
                        + " mController=" + mController);
            }
            if (dumpAll) {
                pw.println("  Total persistent processes: " + numPers);
                pw.println("  mProcessesReady=" + mProcessesReady
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
                pw.println("  mGoingToSleep=" + mStackSupervisor.mGoingToSleep);
                pw.println("  mLaunchingActivity=" + mStackSupervisor.mLaunchingActivity);
                pw.println("  mAdjSeq=" + mAdjSeq + " mLruSeq=" + mLruSeq);
                pw.println("  mNumNonCachedProcs=" + mNumNonCachedProcs
                        + " (" + mLruProcesses.size() + " total)"
                        + " mNumCachedHiddenProcs=" + mNumCachedHiddenProcs
                        + " mNumServiceProcs=" + mNumServiceProcs
                        + " mNewNumServiceProcs=" + mNewNumServiceProcs);
                pw.println("  mAllowLowerMemLevel=" + mAllowLowerMemLevel
                        + " mLastMemoryLevel" + mLastMemoryLevel
                        + " mLastNumProcesses" + mLastNumProcesses);
                long now = SystemClock.uptimeMillis();
                pw.print("  mLastIdleTime=");
                        TimeUtils.formatDuration(now, mLastIdleTime, pw);
                        pw.print(" mLowRamSinceLastIdle=");
                        TimeUtils.formatDuration(getLowRamTimeSinceIdle(now), pw);
                        pw.println();
            }
        }

        if (!printedAnything) {
            pw.println("  (nothing)");
        }
    }

    boolean dumpProcessesToGc(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean needSep, boolean dumpAll, String dumpPackage) {
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
        pw.print(mProcessList.getMemLevel(adj)/1024);
        pw.println(" kB)");
    }

    boolean dumpOomLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll) {
        boolean needSep = false;

        if (mLruProcesses.size() > 0) {
            if (needSep) pw.println();
            needSep = true;
            pw.println("  OOM levels:");
            printOomLevel(pw, "SYSTEM_ADJ", ProcessList.SYSTEM_ADJ);
            printOomLevel(pw, "PERSISTENT_PROC_ADJ", ProcessList.PERSISTENT_PROC_ADJ);
            printOomLevel(pw, "FOREGROUND_APP_ADJ", ProcessList.FOREGROUND_APP_ADJ);
            printOomLevel(pw, "VISIBLE_APP_ADJ", ProcessList.VISIBLE_APP_ADJ);
            printOomLevel(pw, "PERCEPTIBLE_APP_ADJ", ProcessList.PERCEPTIBLE_APP_ADJ);
            printOomLevel(pw, "BACKUP_APP_ADJ", ProcessList.BACKUP_APP_ADJ);
            printOomLevel(pw, "HEAVY_WEIGHT_APP_ADJ", ProcessList.HEAVY_WEIGHT_APP_ADJ);
            printOomLevel(pw, "SERVICE_ADJ", ProcessList.SERVICE_ADJ);
            printOomLevel(pw, "HOME_APP_ADJ", ProcessList.HOME_APP_ADJ);
            printOomLevel(pw, "PREVIOUS_APP_ADJ", ProcessList.PREVIOUS_APP_ADJ);
            printOomLevel(pw, "SERVICE_B_ADJ", ProcessList.SERVICE_B_ADJ);
            printOomLevel(pw, "CACHED_APP_MIN_ADJ", ProcessList.CACHED_APP_MIN_ADJ);
            printOomLevel(pw, "CACHED_APP_MAX_ADJ", ProcessList.CACHED_APP_MAX_ADJ);

            if (needSep) pw.println();
            pw.print("  Process OOM control ("); pw.print(mLruProcesses.size());
                    pw.print(" total, non-act at ");
                    pw.print(mLruProcesses.size()-mLruProcessActivityStart);
                    pw.print(", non-svc at ");
                    pw.print(mLruProcesses.size()-mLruProcessServiceStart);
                    pw.println("):");
            dumpProcessOomList(pw, this, mLruProcesses, "    ", "Proc", "PERS", true, null);
            needSep = true;
        }

        dumpProcessesToGc(fd, pw, args, opti, needSep, dumpAll, null);

        pw.println();
        pw.println("  mHomeProcess: " + mHomeProcess);
        pw.println("  mPreviousProcess: " + mPreviousProcess);
        if (mHeavyWeightProcess != null) {
            pw.println("  mHeavyWeightProcess: " + mHeavyWeightProcess);
        }

        return true;
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

    static class ItemMatcher {
        ArrayList<ComponentName> components;
        ArrayList<String> strings;
        ArrayList<Integer> objects;
        boolean all;
        
        ItemMatcher() {
            all = true;
        }

        void build(String name) {
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

        int build(String[] args, int opti) {
            for (; opti<args.length; opti++) {
                String name = args[opti];
                if ("--".equals(name)) {
                    return opti+1;
                }
                build(name);
            }
            return opti;
        }

        boolean match(Object object, ComponentName comp) {
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

    /**
     * There are three things that cmd can be:
     *  - a flattened component name that matches an existing activity
     *  - the cmd arg isn't the flattened component name of an existing activity:
     *    dump all activity whose component contains the cmd as a substring
     *  - A hex number of the ActivityRecord object instance.
     */
    protected boolean dumpActivity(FileDescriptor fd, PrintWriter pw, String name, String[] args,
            int opti, boolean dumpAll) {
        ArrayList<ActivityRecord> activities;
        
        synchronized (this) {
            activities = mStackSupervisor.getDumpActivitiesLocked(name);
        }

        if (activities.size() <= 0) {
            return false;
        }

        String[] newArgs = new String[args.length - opti];
        System.arraycopy(args, opti, newArgs, 0, args.length - opti);

        TaskRecord lastTask = null;
        boolean needSep = false;
        for (int i=activities.size()-1; i>=0; i--) {
            ActivityRecord r = activities.get(i);
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
                    r.app.thread.dumpActivity(tp.getWriteFd().getFileDescriptor(),
                            r.appToken, innerPrefix, args);
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
                    "    ", dumpPackage, false)) {
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

        if (mGrantedUriPermissions.size() > 0) {
            boolean printed = false;
            int dumpUid = -2;
            if (dumpPackage != null) {
                try {
                    dumpUid = mContext.getPackageManager().getPackageUid(dumpPackage, 0);
                } catch (NameNotFoundException e) {
                    dumpUid = -1;
                }
            }
            for (int i=0; i<mGrantedUriPermissions.size(); i++) {
                int uid = mGrantedUriPermissions.keyAt(i);
                if (dumpUid >= -1 && UserHandle.getAppId(uid) != dumpUid) {
                    continue;
                }
                final ArrayMap<GrantUri, UriPermission> perms = mGrantedUriPermissions.valueAt(i);
                if (!printed) {
                    if (needSep) pw.println();
                    needSep = true;
                    pw.println("  Granted Uri Permissions:");
                    printed = true;
                    printedAnything = true;
                }
                pw.print("  * UID "); pw.print(uid); pw.println(" holds:");
                for (UriPermission perm : perms.values()) {
                    pw.print("    "); pw.println(perm);
                    if (dumpAll) {
                        perm.dump(pw, "      ");
                    }
                }
            }
        }

        if (!printedAnything) {
            pw.println("  (nothing)");
        }
    }

    void dumpPendingIntentsLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage) {
        boolean printed = false;

        pw.println("ACTIVITY MANAGER PENDING INTENTS (dumpsys activity intents)");

        if (mIntentSenderRecords.size() > 0) {
            Iterator<WeakReference<PendingIntentRecord>> it
                    = mIntentSenderRecords.values().iterator();
            while (it.hasNext()) {
                WeakReference<PendingIntentRecord> ref = it.next();
                PendingIntentRecord rec = ref != null ? ref.get(): null;
                if (dumpPackage != null && (rec == null
                        || !dumpPackage.equals(rec.key.packageName))) {
                    continue;
                }
                printed = true;
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

        if (!printed) {
            pw.println("  (nothing)");
        }
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
                    prefix, (r.persistent ? persistentLabel : normalLabel),
                    i, r.toString()));
            if (r.persistent) {
                numPers++;
            }
        }
        return numPers;
    }

    private static final boolean dumpProcessOomList(PrintWriter pw,
            ActivityManagerService service, List<ProcessRecord> origList,
            String prefix, String normalLabel, String persistentLabel,
            boolean inclDetails, String dumpPackage) {

        ArrayList<Pair<ProcessRecord, Integer>> list
                = new ArrayList<Pair<ProcessRecord, Integer>>(origList.size());
        for (int i=0; i<origList.size(); i++) {
            ProcessRecord r = origList.get(i);
            if (dumpPackage != null && !r.pkgList.containsKey(dumpPackage)) {
                continue;
            }
            list.add(new Pair<ProcessRecord, Integer>(origList.get(i), i));
        }

        if (list.size() <= 0) {
            return false;
        }

        Comparator<Pair<ProcessRecord, Integer>> comparator
                = new Comparator<Pair<ProcessRecord, Integer>>() {
            @Override
            public int compare(Pair<ProcessRecord, Integer> object1,
                    Pair<ProcessRecord, Integer> object2) {
                if (object1.first.setAdj != object2.first.setAdj) {
                    return object1.first.setAdj > object2.first.setAdj ? -1 : 1;
                }
                if (object1.second.intValue() != object2.second.intValue()) {
                    return object1.second.intValue() > object2.second.intValue() ? -1 : 1;
                }
                return 0;
            }
        };

        Collections.sort(list, comparator);

        final long curRealtime = SystemClock.elapsedRealtime();
        final long realtimeSince = curRealtime - service.mLastPowerCheckRealtime;
        final long curUptime = SystemClock.uptimeMillis();
        final long uptimeSince = curUptime - service.mLastPowerCheckUptime;

        for (int i=list.size()-1; i>=0; i--) {
            ProcessRecord r = list.get(i).first;
            String oomAdj = ProcessList.makeOomAdjString(r.setAdj);
            char schedGroup;
            switch (r.setSchedGroup) {
                case Process.THREAD_GROUP_BG_NONINTERACTIVE:
                    schedGroup = 'B';
                    break;
                case Process.THREAD_GROUP_DEFAULT:
                    schedGroup = 'F';
                    break;
                default:
                    schedGroup = '?';
                    break;
            }
            char foreground;
            if (r.foregroundActivities) {
                foreground = 'A';
            } else if (r.foregroundServices) {
                foreground = 'S';
            } else {
                foreground = ' ';
            }
            String procState = ProcessList.makeProcStateString(r.curProcState);
            pw.print(prefix);
            pw.print(r.persistent ? persistentLabel : normalLabel);
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
                pw.print(" curRaw="); pw.print(r.curRawAdj);
                pw.print(" setRaw="); pw.print(r.setRawAdj);
                pw.print(" cur="); pw.print(r.curAdj);
                pw.print(" set="); pw.println(r.setAdj);
                pw.print(prefix);
                pw.print("    ");
                pw.print("state: cur="); pw.print(ProcessList.makeProcStateString(r.curProcState));
                pw.print(" set="); pw.print(ProcessList.makeProcStateString(r.setProcState));
                pw.print(" lastPss="); pw.print(r.lastPss);
                pw.print(" lastCachedPss="); pw.println(r.lastCachedPss);
                pw.print(prefix);
                pw.print("    ");
                pw.print("cached="); pw.print(r.cached);
                pw.print(" empty="); pw.print(r.empty);
                pw.print(" hasAboveClient="); pw.println(r.hasAboveClient);

                if (r.setProcState >= ActivityManager.PROCESS_STATE_SERVICE) {
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
        return true;
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

    final void dumpDbInfo(FileDescriptor fd, PrintWriter pw, String[] args) {
        ArrayList<ProcessRecord> procs = collectProcesses(pw, 0, args);
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
                        r.thread.dumpDbInfo(tp.getWriteFd().getFileDescriptor(), args);
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
        final int id;
        final boolean hasActivities;
        ArrayList<MemItem> subitems;

        public MemItem(String _label, String _shortLabel, long _pss, int _id,
                boolean _hasActivities) {
            isProc = true;
            label = _label;
            shortLabel = _shortLabel;
            pss = _pss;
            id = _id;
            hasActivities = _hasActivities;
        }

        public MemItem(String _label, String _shortLabel, long _pss, int _id) {
            isProc = false;
            label = _label;
            shortLabel = _shortLabel;
            pss = _pss;
            id = _id;
            hasActivities = false;
        }
    }

    static final void dumpMemItems(PrintWriter pw, String prefix, String tag,
            ArrayList<MemItem> items, boolean sort, boolean isCompact) {
        if (sort && !isCompact) {
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
            if (!isCompact) {
                pw.print(prefix); pw.printf("%7d kB: ", mi.pss); pw.println(mi.label);
            } else if (mi.isProc) {
                pw.print("proc,"); pw.print(tag); pw.print(","); pw.print(mi.shortLabel);
                pw.print(","); pw.print(mi.id); pw.print(","); pw.print(mi.pss);
                pw.println(mi.hasActivities ? ",a" : ",e");
            } else {
                pw.print(tag); pw.print(","); pw.print(mi.shortLabel); pw.print(",");
                pw.println(mi.pss);
            }
            if (mi.subitems != null) {
                dumpMemItems(pw, prefix + "           ", mi.shortLabel, mi.subitems,
                        true, isCompact);
            }
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
            ProcessList.SYSTEM_ADJ, ProcessList.PERSISTENT_PROC_ADJ, ProcessList.FOREGROUND_APP_ADJ,
            ProcessList.VISIBLE_APP_ADJ, ProcessList.PERCEPTIBLE_APP_ADJ,
            ProcessList.BACKUP_APP_ADJ, ProcessList.HEAVY_WEIGHT_APP_ADJ,
            ProcessList.SERVICE_ADJ, ProcessList.HOME_APP_ADJ,
            ProcessList.PREVIOUS_APP_ADJ, ProcessList.SERVICE_B_ADJ, ProcessList.CACHED_APP_MAX_ADJ
    };
    static final String[] DUMP_MEM_OOM_LABEL = new String[] {
            "Native",
            "System", "Persistent", "Foreground",
            "Visible", "Perceptible",
            "Heavy Weight", "Backup",
            "A Services", "Home",
            "Previous", "B Services", "Cached"
    };
    static final String[] DUMP_MEM_OOM_COMPACT_LABEL = new String[] {
            "native",
            "sys", "pers", "fore",
            "vis", "percept",
            "heavy", "backup",
            "servicea", "home",
            "prev", "serviceb", "cached"
    };

    private final void dumpApplicationMemoryUsageHeader(PrintWriter pw, long uptime,
            long realtime, boolean isCheckinRequest, boolean isCompact) {
        if (isCheckinRequest || isCompact) {
            // short checkin version
            pw.print("time,"); pw.print(uptime); pw.print(","); pw.println(realtime);
        } else {
            pw.println("Applications Memory Usage (kB):");
            pw.println("Uptime: " + uptime + " Realtime: " + realtime);
        }
    }

    final void dumpApplicationMemoryUsage(FileDescriptor fd,
            PrintWriter pw, String prefix, String[] args, boolean brief, PrintWriter categoryPw) {
        boolean dumpDetails = false;
        boolean dumpFullDetails = false;
        boolean dumpDalvik = false;
        boolean oomOnly = false;
        boolean isCompact = false;
        boolean localOnly = false;
        
        int opti = 0;
        while (opti < args.length) {
            String opt = args[opti];
            if (opt == null || opt.length() <= 0 || opt.charAt(0) != '-') {
                break;
            }
            opti++;
            if ("-a".equals(opt)) {
                dumpDetails = true;
                dumpFullDetails = true;
                dumpDalvik = true;
            } else if ("-d".equals(opt)) {
                dumpDalvik = true;
            } else if ("-c".equals(opt)) {
                isCompact = true;
            } else if ("--oom".equals(opt)) {
                oomOnly = true;
            } else if ("--local".equals(opt)) {
                localOnly = true;
            } else if ("-h".equals(opt)) {
                pw.println("meminfo dump options: [-a] [-d] [-c] [--oom] [process]");
                pw.println("  -a: include all available information for each process.");
                pw.println("  -d: include dalvik details when dumping process details.");
                pw.println("  -c: dump in a compact machine-parseable representation.");
                pw.println("  --oom: only show processes organized by oom adj.");
                pw.println("  --local: only collect details locally, don't call process.");
                pw.println("If [process] is specified it can be the name or ");
                pw.println("pid of a specific process to dump.");
                return;
            } else {
                pw.println("Unknown argument: " + opt + "; use -h for help");
            }
        }
        
        final boolean isCheckinRequest = scanArgs(args, "--checkin");
        long uptime = SystemClock.uptimeMillis();
        long realtime = SystemClock.elapsedRealtime();
        final long[] tmpLong = new long[1];

        ArrayList<ProcessRecord> procs = collectProcesses(pw, opti, args);
        if (procs == null) {
            // No Java processes.  Maybe they want to print a native process.
            if (args != null && args.length > opti
                    && args[opti].charAt(0) != '-') {
                ArrayList<ProcessCpuTracker.Stats> nativeProcs
                        = new ArrayList<ProcessCpuTracker.Stats>();
                updateCpuStatsNow();
                int findPid = -1;
                try {
                    findPid = Integer.parseInt(args[opti]);
                } catch (NumberFormatException e) {
                }
                synchronized (mProcessCpuThread) {
                    final int N = mProcessCpuTracker.countStats();
                    for (int i=0; i<N; i++) {
                        ProcessCpuTracker.Stats st = mProcessCpuTracker.getStats(i);
                        if (st.pid == findPid || (st.baseName != null
                                && st.baseName.equals(args[opti]))) {
                            nativeProcs.add(st);
                        }
                    }
                }
                if (nativeProcs.size() > 0) {
                    dumpApplicationMemoryUsageHeader(pw, uptime, realtime, isCheckinRequest,
                            isCompact);
                    Debug.MemoryInfo mi = null;
                    for (int i = nativeProcs.size() - 1 ; i >= 0 ; i--) {
                        final ProcessCpuTracker.Stats r = nativeProcs.get(i);
                        final int pid = r.pid;
                        if (!isCheckinRequest && dumpDetails) {
                            pw.println("\n** MEMINFO in pid " + pid + " [" + r.baseName + "] **");
                        }
                        if (mi == null) {
                            mi = new Debug.MemoryInfo();
                        }
                        if (dumpDetails || (!brief && !oomOnly)) {
                            Debug.getMemoryInfo(pid, mi);
                        } else {
                            mi.dalvikPss = (int)Debug.getPss(pid, tmpLong);
                            mi.dalvikPrivateDirty = (int)tmpLong[0];
                        }
                        ActivityThread.dumpMemInfoTable(pw, mi, isCheckinRequest, dumpFullDetails,
                                dumpDalvik, pid, r.baseName, 0, 0, 0, 0, 0, 0);
                        if (isCheckinRequest) {
                            pw.println();
                        }
                    }
                    return;
                }
            }
            pw.println("No process found for: " + args[opti]);
            return;
        }

        if (!brief && !oomOnly && (procs.size() == 1 || isCheckinRequest)) {
            dumpDetails = true;
        }

        dumpApplicationMemoryUsageHeader(pw, uptime, realtime, isCheckinRequest, isCompact);

        String[] innerArgs = new String[args.length-opti];
        System.arraycopy(args, opti, innerArgs, 0, args.length-opti);

        ArrayList<MemItem> procMems = new ArrayList<MemItem>();
        final SparseArray<MemItem> procMemsMap = new SparseArray<MemItem>();
        long nativePss=0, dalvikPss=0, otherPss=0;
        long[] miscPss = new long[Debug.MemoryInfo.NUM_OTHER_STATS];

        long oomPss[] = new long[DUMP_MEM_OOM_LABEL.length];
        ArrayList<MemItem>[] oomProcs = (ArrayList<MemItem>[])
                new ArrayList[DUMP_MEM_OOM_LABEL.length];

        long totalPss = 0;
        long cachedPss = 0;

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
                hasActivities = r.activities.size() > 0;
            }
            if (thread != null) {
                if (!isCheckinRequest && dumpDetails) {
                    pw.println("\n** MEMINFO in pid " + pid + " [" + r.processName + "] **");
                }
                if (mi == null) {
                    mi = new Debug.MemoryInfo();
                }
                if (dumpDetails || (!brief && !oomOnly)) {
                    Debug.getMemoryInfo(pid, mi);
                } else {
                    mi.dalvikPss = (int)Debug.getPss(pid, tmpLong);
                    mi.dalvikPrivateDirty = (int)tmpLong[0];
                }
                if (dumpDetails) {
                    if (localOnly) {
                        ActivityThread.dumpMemInfoTable(pw, mi, isCheckinRequest, dumpFullDetails,
                                dumpDalvik, pid, r.processName, 0, 0, 0, 0, 0, 0);
                        if (isCheckinRequest) {
                            pw.println();
                        }
                    } else {
                        try {
                            pw.flush();
                            thread.dumpMemInfo(fd, mi, isCheckinRequest, dumpFullDetails,
                                    dumpDalvik, innerArgs);
                        } catch (RemoteException e) {
                            if (!isCheckinRequest) {
                                pw.println("Got RemoteException!");
                                pw.flush();
                            }
                        }
                    }
                }

                final long myTotalPss = mi.getTotalPss();
                final long myTotalUss = mi.getTotalUss();

                synchronized (this) {
                    if (r.thread != null && oomAdj == r.getSetAdjWithServices()) {
                        // Record this for posterity if the process has been stable.
                        r.baseProcessTracker.addPss(myTotalPss, myTotalUss, true, r.pkgList);
                    }
                }

                if (!isCheckinRequest && mi != null) {
                    totalPss += myTotalPss;
                    MemItem pssItem = new MemItem(r.processName + " (pid " + pid +
                            (hasActivities ? " / activities)" : ")"),
                            r.processName, myTotalPss, pid, hasActivities);
                    procMems.add(pssItem);
                    procMemsMap.put(pid, pssItem);

                    nativePss += mi.nativePss;
                    dalvikPss += mi.dalvikPss;
                    otherPss += mi.otherPss;
                    for (int j=0; j<Debug.MemoryInfo.NUM_OTHER_STATS; j++) {
                        long mem = mi.getOtherPss(j);
                        miscPss[j] += mem;
                        otherPss -= mem;
                    }

                    if (oomAdj >= ProcessList.CACHED_APP_MIN_ADJ) {
                        cachedPss += myTotalPss;
                    }

                    for (int oomIndex=0; oomIndex<oomPss.length; oomIndex++) {
                        if (oomAdj <= DUMP_MEM_OOM_ADJ[oomIndex]
                                || oomIndex == (oomPss.length-1)) {
                            oomPss[oomIndex] += myTotalPss;
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

        if (!isCheckinRequest && procs.size() > 1) {
            // If we are showing aggregations, also look for native processes to
            // include so that our aggregations are more accurate.
            updateCpuStatsNow();
            synchronized (mProcessCpuThread) {
                final int N = mProcessCpuTracker.countStats();
                for (int i=0; i<N; i++) {
                    ProcessCpuTracker.Stats st = mProcessCpuTracker.getStats(i);
                    if (st.vsize > 0 && procMemsMap.indexOfKey(st.pid) < 0) {
                        if (mi == null) {
                            mi = new Debug.MemoryInfo();
                        }
                        if (!brief && !oomOnly) {
                            Debug.getMemoryInfo(st.pid, mi);
                        } else {
                            mi.nativePss = (int)Debug.getPss(st.pid, tmpLong);
                            mi.nativePrivateDirty = (int)tmpLong[0];
                        }

                        final long myTotalPss = mi.getTotalPss();
                        totalPss += myTotalPss;
                        nativeProcTotalPss += myTotalPss;

                        MemItem pssItem = new MemItem(st.name + " (pid " + st.pid + ")",
                                st.name, myTotalPss, st.pid, false);
                        procMems.add(pssItem);

                        nativePss += mi.nativePss;
                        dalvikPss += mi.dalvikPss;
                        otherPss += mi.otherPss;
                        for (int j=0; j<Debug.MemoryInfo.NUM_OTHER_STATS; j++) {
                            long mem = mi.getOtherPss(j);
                            miscPss[j] += mem;
                            otherPss -= mem;
                        }
                        oomPss[0] += myTotalPss;
                        if (oomProcs[0] == null) {
                            oomProcs[0] = new ArrayList<MemItem>();
                        }
                        oomProcs[0].add(pssItem);
                    }
                }
            }

            ArrayList<MemItem> catMems = new ArrayList<MemItem>();

            catMems.add(new MemItem("Native", "Native", nativePss, -1));
            catMems.add(new MemItem("Dalvik", "Dalvik", dalvikPss, -2));
            catMems.add(new MemItem("Unknown", "Unknown", otherPss, -3));
            for (int j=0; j<Debug.MemoryInfo.NUM_OTHER_STATS; j++) {
                String label = Debug.MemoryInfo.getOtherLabel(j);
                catMems.add(new MemItem(label, label, miscPss[j], j));
            }

            ArrayList<MemItem> oomMems = new ArrayList<MemItem>();
            for (int j=0; j<oomPss.length; j++) {
                if (oomPss[j] != 0) {
                    String label = isCompact ? DUMP_MEM_OOM_COMPACT_LABEL[j]
                            : DUMP_MEM_OOM_LABEL[j];
                    MemItem item = new MemItem(label, label, oomPss[j],
                            DUMP_MEM_OOM_ADJ[j]);
                    item.subitems = oomProcs[j];
                    oomMems.add(item);
                }
            }

            if (!brief && !oomOnly && !isCompact) {
                pw.println();
                pw.println("Total PSS by process:");
                dumpMemItems(pw, "  ", "proc", procMems, true, isCompact);
                pw.println();
            }
            if (!isCompact) {
                pw.println("Total PSS by OOM adjustment:");
            }
            dumpMemItems(pw, "  ", "oom", oomMems, false, isCompact);
            if (!brief && !oomOnly) {
                PrintWriter out = categoryPw != null ? categoryPw : pw;
                if (!isCompact) {
                    out.println();
                    out.println("Total PSS by category:");
                }
                dumpMemItems(out, "  ", "cat", catMems, true, isCompact);
            }
            if (!isCompact) {
                pw.println();
            }
            MemInfoReader memInfo = new MemInfoReader();
            memInfo.readMemInfo();
            if (nativeProcTotalPss > 0) {
                synchronized (this) {
                    mProcessStats.addSysMemUsageLocked(memInfo.getCachedSizeKb(),
                            memInfo.getFreeSizeKb(), memInfo.getZramTotalSizeKb(),
                            memInfo.getBuffersSizeKb()+memInfo.getShmemSizeKb()+memInfo.getSlabSizeKb(),
                            nativeProcTotalPss);
                }
            }
            if (!brief) {
                if (!isCompact) {
                    pw.print("Total RAM: "); pw.print(memInfo.getTotalSizeKb());
                    pw.print(" kB (status ");
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
                    pw.print(" Free RAM: "); pw.print(cachedPss + memInfo.getCachedSizeKb()
                            + memInfo.getFreeSizeKb()); pw.print(" kB (");
                            pw.print(cachedPss); pw.print(" cached pss + ");
                            pw.print(memInfo.getCachedSizeKb()); pw.print(" cached + ");
                            pw.print(memInfo.getFreeSizeKb()); pw.println(" free)");
                } else {
                    pw.print("ram,"); pw.print(memInfo.getTotalSizeKb()); pw.print(",");
                    pw.print(cachedPss + memInfo.getCachedSizeKb()
                            + memInfo.getFreeSizeKb()); pw.print(",");
                    pw.println(totalPss - cachedPss);
                }
            }
            if (!isCompact) {
                pw.print(" Used RAM: "); pw.print(totalPss - cachedPss
                        + memInfo.getBuffersSizeKb() + memInfo.getShmemSizeKb()
                        + memInfo.getSlabSizeKb()); pw.print(" kB (");
                        pw.print(totalPss - cachedPss); pw.print(" used pss + ");
                        pw.print(memInfo.getBuffersSizeKb()); pw.print(" buffers + ");
                        pw.print(memInfo.getShmemSizeKb()); pw.print(" shmem + ");
                        pw.print(memInfo.getSlabSizeKb()); pw.println(" slab)");
                pw.print(" Lost RAM: "); pw.print(memInfo.getTotalSizeKb()
                        - totalPss - memInfo.getFreeSizeKb() - memInfo.getCachedSizeKb()
                        - memInfo.getBuffersSizeKb() - memInfo.getShmemSizeKb()
                        - memInfo.getSlabSizeKb()); pw.println(" kB");
            }
            if (!brief) {
                if (memInfo.getZramTotalSizeKb() != 0) {
                    if (!isCompact) {
                        pw.print("     ZRAM: "); pw.print(memInfo.getZramTotalSizeKb());
                                pw.print(" kB physical used for ");
                                pw.print(memInfo.getSwapTotalSizeKb()
                                        - memInfo.getSwapFreeSizeKb());
                                pw.print(" kB in swap (");
                                pw.print(memInfo.getSwapTotalSizeKb());
                                pw.println(" kB total swap)");
                    } else {
                        pw.print("zram,"); pw.print(memInfo.getZramTotalSizeKb()); pw.print(",");
                                pw.print(memInfo.getSwapTotalSizeKb()); pw.print(",");
                                pw.println(memInfo.getSwapFreeSizeKb());
                    }
                }
                final int[] SINGLE_LONG_FORMAT = new int[] {
                    Process.PROC_SPACE_TERM|Process.PROC_OUT_LONG
                };
                long[] longOut = new long[1];
                Process.readProcFile("/sys/kernel/mm/ksm/pages_shared",
                        SINGLE_LONG_FORMAT, null, longOut, null);
                long shared = longOut[0] * ProcessList.PAGE_SIZE / 1024;
                longOut[0] = 0;
                Process.readProcFile("/sys/kernel/mm/ksm/pages_sharing",
                        SINGLE_LONG_FORMAT, null, longOut, null);
                long sharing = longOut[0] * ProcessList.PAGE_SIZE / 1024;
                longOut[0] = 0;
                Process.readProcFile("/sys/kernel/mm/ksm/pages_unshared",
                        SINGLE_LONG_FORMAT, null, longOut, null);
                long unshared = longOut[0] * ProcessList.PAGE_SIZE / 1024;
                longOut[0] = 0;
                Process.readProcFile("/sys/kernel/mm/ksm/pages_volatile",
                        SINGLE_LONG_FORMAT, null, longOut, null);
                long voltile = longOut[0] * ProcessList.PAGE_SIZE / 1024;
                if (!isCompact) {
                    if (sharing != 0 || shared != 0 || unshared != 0 || voltile != 0) {
                        pw.print("      KSM: "); pw.print(sharing);
                                pw.print(" kB saved from shared ");
                                pw.print(shared); pw.println(" kB");
                        pw.print("           "); pw.print(unshared); pw.print(" kB unshared; ");
                                pw.print(voltile); pw.println(" kB volatile");
                    }
                    pw.print("   Tuning: ");
                    pw.print(ActivityManager.staticGetMemoryClass());
                    pw.print(" (large ");
                    pw.print(ActivityManager.staticGetLargeMemoryClass());
                    pw.print("), oom ");
                    pw.print(mProcessList.getMemLevel(ProcessList.CACHED_APP_MAX_ADJ)/1024);
                    pw.print(" kB");
                    pw.print(", restore limit ");
                    pw.print(mProcessList.getCachedRestoreThresholdKb());
                    pw.print(" kB");
                    if (ActivityManager.isLowRamDeviceStatic()) {
                        pw.print(" (low-ram)");
                    }
                    if (ActivityManager.isHighEndGfx()) {
                        pw.print(" (high-end-gfx)");
                    }
                    pw.println();
                } else {
                    pw.print("ksm,"); pw.print(sharing); pw.print(",");
                    pw.print(shared); pw.print(","); pw.print(unshared); pw.print(",");
                    pw.println(voltile);
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

        for (int i=0; i<cpr.connections.size(); i++) {
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
                if (!capp.persistent && capp.thread != null
                        && capp.pid != 0
                        && capp.pid != MY_PID) {
                    killUnneededProcessLocked(capp, "depends on provider "
                            + cpr.name.flattenToShortString()
                            + " in dying proc " + (proc != null ? proc.processName : "??"));
                }
            } else if (capp.thread != null && conn.provider.provider != null) {
                try {
                    capp.thread.unstableProviderDied(conn.provider.provider.asBinder());
                } catch (RemoteException e) {
                }
                // In the protocol here, we don't expect the client to correctly
                // clean up this connection, we'll just remove it.
                cpr.connections.remove(i);
                conn.client.conProviders.remove(conn);
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
     */
    private final void cleanUpApplicationRecordLocked(ProcessRecord app,
            boolean restarting, boolean allowRestart, int index) {
        if (index >= 0) {
            removeLruProcessLocked(app);
            ProcessList.remove(app.pid);
        }

        mProcessesToGc.remove(app);
        mPendingPssProcesses.remove(app);

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

        app.crashing = false;
        app.notResponding = false;

        app.resetPackageList(mProcessStats);
        app.unlinkDeathRecipient();
        app.makeInactive(mProcessStats);
        app.waitingToKill = null;
        app.forcingToForeground = null;
        updateProcessForegroundLocked(app, false, false);
        app.foregroundActivities = false;
        app.hasShownUi = false;
        app.treatLikeActivity = false;
        app.hasAboveClient = false;
        app.hasClientActivities = false;

        mServices.killServicesLocked(app, allowRestart);

        boolean restart = false;

        // Remove published content providers.
        for (int i=app.pubProviders.size()-1; i>=0; i--) {
            ContentProviderRecord cpr = app.pubProviders.valueAt(i);
            final boolean always = app.bad || !allowRestart;
            if (removeDyingProviderLocked(app, cpr, always) || always) {
                // We left the provider in the launching list, need to
                // restart it.
                restart = true;
            }

            cpr.provider = null;
            cpr.proc = null;
        }
        app.pubProviders.clear();

        // Take care of any launching providers waiting for this process.
        if (checkAppInLaunchingProvidersLocked(app, false)) {
            restart = true;
        }

        // Unregister from connected content providers.
        if (!app.conProviders.isEmpty()) {
            for (int i=0; i<app.conProviders.size(); i++) {
                ContentProviderConnection conn = app.conProviders.get(i);
                conn.provider.connections.remove(conn);
            }
            app.conProviders.clear();
        }

        // At this point there may be remaining entries in mLaunchingProviders
        // where we were the only one waiting, so they are no longer of use.
        // Look for these and clean up if found.
        // XXX Commented out for now.  Trying to figure out a way to reproduce
        // the actual situation to identify what is actually going on.
        if (false) {
            for (int i=0; i<mLaunchingProviders.size(); i++) {
                ContentProviderRecord cpr = (ContentProviderRecord)
                        mLaunchingProviders.get(i);
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
        for (int i=app.receivers.size()-1; i>=0; i--) {
            removeReceiverLocked(app.receivers.valueAt(i));
        }
        app.receivers.clear();

        // If the app is undergoing backup, tell the backup manager about it
        if (mBackupTarget != null && app.pid == mBackupTarget.app.pid) {
            if (DEBUG_BACKUP || DEBUG_CLEANUP) Slog.d(TAG, "App "
                    + mBackupTarget.appInfo + " died during backup");
            try {
                IBackupManager bm = IBackupManager.Stub.asInterface(
                        ServiceManager.getService(Context.BACKUP_SERVICE));
                bm.agentDisconnected(app.info.packageName);
            } catch (RemoteException e) {
                // can't happen; backup manager is local
            }
        }

        for (int i = mPendingProcessChanges.size()-1; i>=0; i--) {
            ProcessChangeItem item = mPendingProcessChanges.get(i);
            if (item.pid == app.pid) {
                mPendingProcessChanges.remove(i);
                mAvailProcessChanges.add(item);
            }
        }
        mHandler.obtainMessage(DISPATCH_PROCESS_DIED, app.pid, app.info.uid, null).sendToTarget();

        // If the caller is restarting this app, then leave it in its
        // current lists and let the caller take care of it.
        if (restarting) {
            return;
        }

        if (!app.persistent || app.isolated) {
            if (DEBUG_PROCESSES || DEBUG_CLEANUP) Slog.v(TAG,
                    "Removing non-persistent process during cleanup: " + app);
            mProcessNames.remove(app.processName, app.uid);
            mIsolatedProcesses.remove(app.uid);
            if (mHeavyWeightProcess == app) {
                mHandler.sendMessage(mHandler.obtainMessage(CANCEL_HEAVY_NOTIFICATION_MSG,
                        mHeavyWeightProcess.userId, 0));
                mHeavyWeightProcess = null;
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
        if ((DEBUG_PROCESSES || DEBUG_CLEANUP) && mProcessesOnHold.contains(app)) Slog.v(TAG,
                "Clean-up removing on hold: " + app);
        mProcessesOnHold.remove(app);

        if (app == mHomeProcess) {
            mHomeProcess = null;
        }
        if (app == mPreviousProcess) {
            mPreviousProcess = null;
        }

        if (restart && !app.isolated) {
            // We have components that still need to be running in the
            // process, so re-launch it.
            mProcessNames.put(app.processName, app.uid, app);
            startProcessLocked(app, "restart", app.processName);
        } else if (app.pid > 0 && app.pid != MY_PID) {
            // Goodbye!
            boolean removed;
            synchronized (mPidsSelfLocked) {
                mPidsSelfLocked.remove(app.pid);
                mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);
            }
            mBatteryStatsService.noteProcessFinish(app.processName, app.info.uid);
            if (app.isolated) {
                mBatteryStatsService.removeIsolatedUid(app.uid, app.info.uid);
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
                    removeDyingProviderLocked(app, cpr, true);
                    // cpr should have been removed from mLaunchingProviders
                    NL = mLaunchingProviders.size();
                    i--;
                }
            }
        }
        return restart;
    }
    
    // =========================================================
    // SERVICES
    // =========================================================

    @Override
    public List<ActivityManager.RunningServiceInfo> getServices(int maxNum,
            int flags) {
        enforceNotIsolatedCaller("getServices");
        synchronized (this) {
            return mServices.getRunningServiceInfoLocked(maxNum, flags);
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
            String resolvedType, int userId) {
        enforceNotIsolatedCaller("startService");
        // Refuse possible leaked file descriptors
        if (service != null && service.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        if (DEBUG_SERVICE)
            Slog.v(TAG, "startService: " + service + " type=" + resolvedType);
        synchronized(this) {
            final int callingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();
            final long origId = Binder.clearCallingIdentity();
            ComponentName res = mServices.startServiceLocked(caller, service,
                    resolvedType, callingPid, callingUid, userId);
            Binder.restoreCallingIdentity(origId);
            return res;
        }
    }

    ComponentName startServiceInPackage(int uid,
            Intent service, String resolvedType, int userId) {
        synchronized(this) {
            if (DEBUG_SERVICE)
                Slog.v(TAG, "startServiceInPackage: " + service + " type=" + resolvedType);
            final long origId = Binder.clearCallingIdentity();
            ComponentName res = mServices.startServiceLocked(null, service,
                    resolvedType, -1, uid, userId);
            Binder.restoreCallingIdentity(origId);
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
    public IBinder peekService(Intent service, String resolvedType) {
        enforceNotIsolatedCaller("peekService");
        // Refuse possible leaked file descriptors
        if (service != null && service.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        synchronized(this) {
            return mServices.peekServiceLocked(service, resolvedType);
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
            int id, Notification notification, boolean removeNotification) {
        synchronized(this) {
            mServices.setServiceForegroundLocked(className, token, id, notification,
                    removeNotification);
        }
    }

    @Override
    public int handleIncomingUser(int callingPid, int callingUid, int userId, boolean allowAll,
            boolean requireFull, String name, String callerPackage) {
        return handleIncomingUser(callingPid, callingUid, userId, allowAll,
                requireFull ? ALLOW_FULL_ONLY : ALLOW_NON_FULL, name, callerPackage);
    }

    int unsafeConvertIncomingUser(int userId) {
        return (userId == UserHandle.USER_CURRENT || userId == UserHandle.USER_CURRENT_OR_SELF)
                ? mCurrentUserId : userId;
    }

    int handleIncomingUser(int callingPid, int callingUid, int userId, boolean allowAll,
            int allowMode, String name, String callerPackage) {
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (callingUserId == userId) {
            return userId;
        }

        // Note that we may be accessing mCurrentUserId outside of a lock...
        // shouldn't be a big deal, if this is being called outside
        // of a locked context there is intrinsically a race with
        // the value the caller will receive and someone else changing it.
        // We assume that USER_CURRENT_OR_SELF will use the current user; later
        // we will switch to the calling user if access to the current user fails.
        int targetUserId = unsafeConvertIncomingUser(userId);

        if (callingUid != 0 && callingUid != Process.SYSTEM_UID) {
            final boolean allow;
            if (checkComponentPermission(INTERACT_ACROSS_USERS_FULL, callingPid,
                    callingUid, -1, true) == PackageManager.PERMISSION_GRANTED) {
                // If the caller has this permission, they always pass go.  And collect $200.
                allow = true;
            } else if (allowMode == ALLOW_FULL_ONLY) {
                // We require full access, sucks to be you.
                allow = false;
            } else if (checkComponentPermission(INTERACT_ACROSS_USERS, callingPid,
                    callingUid, -1, true) != PackageManager.PERMISSION_GRANTED) {
                // If the caller does not have either permission, they are always doomed.
                allow = false;
            } else if (allowMode == ALLOW_NON_FULL) {
                // We are blanket allowing non-full access, you lucky caller!
                allow = true;
            } else if (allowMode == ALLOW_NON_FULL_IN_PROFILE) {
                // We may or may not allow this depending on whether the two users are
                // in the same profile.
                synchronized (mUserProfileGroupIdsSelfLocked) {
                    int callingProfile = mUserProfileGroupIdsSelfLocked.get(callingUserId,
                            UserInfo.NO_PROFILE_GROUP_ID);
                    int targetProfile = mUserProfileGroupIdsSelfLocked.get(targetUserId,
                            UserInfo.NO_PROFILE_GROUP_ID);
                    allow = callingProfile != UserInfo.NO_PROFILE_GROUP_ID
                            && callingProfile == targetProfile;
                }
            } else {
                throw new IllegalArgumentException("Unknown mode: " + allowMode);
            }
            if (!allow) {
                if (userId == UserHandle.USER_CURRENT_OR_SELF) {
                    // In this case, they would like to just execute as their
                    // owner user instead of failing.
                    targetUserId = callingUserId;
                } else {
                    StringBuilder builder = new StringBuilder(128);
                    builder.append("Permission Denial: ");
                    builder.append(name);
                    if (callerPackage != null) {
                        builder.append(" from ");
                        builder.append(callerPackage);
                    }
                    builder.append(" asks to run as user ");
                    builder.append(userId);
                    builder.append(" but is calling from user ");
                    builder.append(UserHandle.getUserId(callingUid));
                    builder.append("; this requires ");
                    builder.append(INTERACT_ACROSS_USERS_FULL);
                    if (allowMode != ALLOW_FULL_ONLY) {
                        builder.append(" or ");
                        builder.append(INTERACT_ACROSS_USERS);
                    }
                    String msg = builder.toString();
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                }
            }
        }
        if (!allowAll && targetUserId < 0) {
            throw new IllegalArgumentException(
                    "Call does not support special user #" + targetUserId);
        }
        return targetUserId;
    }

    boolean isSingleton(String componentProcessName, ApplicationInfo aInfo,
            String className, int flags) {
        boolean result = false;
        // For apps that don't have pre-defined UIDs, check for permission
        if (UserHandle.getAppId(aInfo.uid) >= Process.FIRST_APPLICATION_UID) {
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
        } else if (UserHandle.isSameApp(aInfo.uid, Process.PHONE_UID)
                && (flags & ServiceInfo.FLAG_SINGLE_USER) != 0) {
            // Phone app is allowed to export singleuser providers.
            result = true;
        } else {
            // App with pre-defined UID, check if it's a persistent app
            result = (aInfo.flags & ApplicationInfo.FLAG_PERSISTENT) != 0;
        }
        if (DEBUG_MU) {
            Slog.v(TAG, "isSingleton(" + componentProcessName + ", " + aInfo
                    + ", " + className + ", 0x" + Integer.toHexString(flags) + ") = " + result);
        }
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
                || componentAppId == Process.SYSTEM_UID
                || componentAppId == Process.PHONE_UID
                || ActivityManager.checkUidPermission(INTERACT_ACROSS_USERS_FULL, componentUid)
                        == PackageManager.PERMISSION_GRANTED;
    }

    public int bindService(IApplicationThread caller, IBinder token,
            Intent service, String resolvedType,
            IServiceConnection connection, int flags, int userId) {
        enforceNotIsolatedCaller("bindService");
        // Refuse possible leaked file descriptors
        if (service != null && service.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            return mServices.bindServiceLocked(caller, token, service, resolvedType,
                    connection, flags, userId);
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
    public boolean bindBackupAgent(ApplicationInfo app, int backupMode) {
        if (DEBUG_BACKUP) Slog.v(TAG, "bindBackupAgent: app=" + app + " mode=" + backupMode);
        enforceCallingPermission("android.permission.CONFIRM_FULL_BACKUP", "bindBackupAgent");

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
                        app.packageName, false, UserHandle.getUserId(app.uid));
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
                    false, 0, "backup", hostingName, false, false, false);
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

    @Override
    public void clearPendingBackup() {
        if (DEBUG_BACKUP) Slog.v(TAG, "clearPendingBackup");
        enforceCallingPermission("android.permission.BACKUP", "clearPendingBackup");

        synchronized (this) {
            mBackupTarget = null;
            mBackupAppName = null;
        }
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
            try {
                if (mBackupAppName == null) {
                    Slog.w(TAG, "Unbinding backup agent with no active backup");
                    return;
                }

                if (!mBackupAppName.equals(appInfo.packageName)) {
                    Slog.e(TAG, "Unbind of " + appInfo + " but is not the current backup target");
                    return;
                }

                // Not backing this app up any more; reset its OOM adjustment
                final ProcessRecord proc = mBackupTarget.app;
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
            } finally {
                mBackupTarget = null;
                mBackupAppName = null;
            }
        }
    }
    // =========================================================
    // BROADCASTS
    // =========================================================

    private final List getStickiesLocked(String action, IntentFilter filter,
            List cur, int userId) {
        final ContentResolver resolver = mContext.getContentResolver();
        ArrayMap<String, ArrayList<Intent>> stickies = mStickyBroadcasts.get(userId);
        if (stickies == null) {
            return cur;
        }
        final ArrayList<Intent> list = stickies.get(action);
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

    boolean isPendingBroadcastProcessLocked(int pid) {
        return mFgBroadcastQueue.isPendingBroadcastProcessLocked(pid)
                || mBgBroadcastQueue.isPendingBroadcastProcessLocked(pid);
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
            IIntentReceiver receiver, IntentFilter filter, String permission, int userId) {
        enforceNotIsolatedCaller("registerReceiver");
        int callingUid;
        int callingPid;
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

            userId = this.handleIncomingUser(callingPid, callingUid, userId,
                    true, ALLOW_FULL_ONLY, "registerReceiver", callerPackage);

            List allSticky = null;

            // Look for any matching sticky broadcasts...
            Iterator actions = filter.actionsIterator();
            if (actions != null) {
                while (actions.hasNext()) {
                    String action = (String)actions.next();
                    allSticky = getStickiesLocked(action, filter, allSticky,
                            UserHandle.USER_ALL);
                    allSticky = getStickiesLocked(action, filter, allSticky,
                            UserHandle.getUserId(callingUid));
                }
            } else {
                allSticky = getStickiesLocked(null, filter, allSticky,
                        UserHandle.USER_ALL);
                allSticky = getStickiesLocked(null, filter, allSticky,
                        UserHandle.getUserId(callingUid));
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
                rl = new ReceiverList(this, callerApp, callingPid, callingUid,
                        userId, receiver);
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
            } else if (rl.uid != callingUid) {
                throw new IllegalArgumentException(
                        "Receiver requested to register for uid " + callingUid
                        + " was previously registered for uid " + rl.uid);
            } else if (rl.pid != callingPid) {
                throw new IllegalArgumentException(
                        "Receiver requested to register for pid " + callingPid
                        + " was previously registered for pid " + rl.pid);
            } else if (rl.userId != userId) {
                throw new IllegalArgumentException(
                        "Receiver requested to register for user " + userId
                        + " was previously registered for user " + rl.userId);
            }
            BroadcastFilter bf = new BroadcastFilter(filter, rl, callerPackage,
                    permission, callingUid, userId);
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
                    BroadcastQueue queue = broadcastQueueForIntent(intent);
                    BroadcastRecord r = new BroadcastRecord(queue, intent, null,
                            null, -1, -1, null, null, AppOpsManager.OP_NONE, receivers, null, 0,
                            null, null, false, true, true, -1);
                    queue.enqueueParallelBroadcastLocked(r);
                    queue.scheduleBroadcastsLocked();
                }
            }

            return sticky;
        }
    }

    public void unregisterReceiver(IIntentReceiver receiver) {
        if (DEBUG_BROADCAST) Slog.v(TAG, "Unregister receiver: " + receiver);

        final long origId = Binder.clearCallingIdentity();
        try {
            boolean doTrim = false;

            synchronized(this) {
                ReceiverList rl = mRegisteredReceivers.get(receiver.asBinder());
                if (rl != null) {
                    if (rl.curBroadcast != null) {
                        BroadcastRecord r = rl.curBroadcast;
                        final boolean doNext = finishReceiverLocked(
                                receiver.asBinder(), r.resultCode, r.resultData,
                                r.resultExtras, r.resultAbort);
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
        int N = rl.size();
        for (int i=0; i<N; i++) {
            mReceiverResolver.removeFilter(rl.get(i));
        }
    }
    
    private final void sendPackageBroadcastLocked(int cmd, String[] packages, int userId) {
        for (int i = mLruProcesses.size() - 1 ; i >= 0 ; i--) {
            ProcessRecord r = mLruProcesses.get(i);
            if (r.thread != null && (userId == UserHandle.USER_ALL || r.userId == userId)) {
                try {
                    r.thread.dispatchPackageBroadcast(cmd, packages);
                } catch (RemoteException ex) {
                }
            }
        }
    }

    private List<ResolveInfo> collectReceiverComponents(Intent intent, String resolvedType,
            int[] users) {
        List<ResolveInfo> receivers = null;
        try {
            HashSet<ComponentName> singleUserReceivers = null;
            boolean scannedFirstReceivers = false;
            for (int user : users) {
                List<ResolveInfo> newReceivers = AppGlobals.getPackageManager()
                        .queryIntentReceivers(intent, resolvedType, STOCK_PM_FLAGS, user);
                if (user != 0 && newReceivers != null) {
                    // If this is not the primary user, we need to check for
                    // any receivers that should be filtered out.
                    for (int i=0; i<newReceivers.size(); i++) {
                        ResolveInfo ri = newReceivers.get(i);
                        if ((ri.activityInfo.flags&ActivityInfo.FLAG_PRIMARY_USER_ONLY) != 0) {
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

    private final int broadcastIntentLocked(ProcessRecord callerApp,
            String callerPackage, Intent intent, String resolvedType,
            IIntentReceiver resultTo, int resultCode, String resultData,
            Bundle map, String requiredPermission, int appOp,
            boolean ordered, boolean sticky, int callingPid, int callingUid,
            int userId) {
        intent = new Intent(intent);

        // By default broadcasts do not go to stopped apps.
        intent.addFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);

        if (DEBUG_BROADCAST_LIGHT) Slog.v(
            TAG, (sticky ? "Broadcast sticky: ": "Broadcast: ") + intent
            + " ordered=" + ordered + " userid=" + userId);
        if ((resultTo != null) && !ordered) {
            Slog.w(TAG, "Broadcast " + intent + " not ordered but result callback requested!");
        }

        userId = handleIncomingUser(callingPid, callingUid, userId,
                true, ALLOW_NON_FULL, "broadcast", callerPackage);

        // Make sure that the user who is receiving this broadcast is started.
        // If not, we will just skip it.


        if (userId != UserHandle.USER_ALL && mStartedUsers.get(userId) == null) {
            if (callingUid != Process.SYSTEM_UID || (intent.getFlags()
                    & Intent.FLAG_RECEIVER_BOOT_UPGRADE) == 0) {
                Slog.w(TAG, "Skipping broadcast of " + intent
                        + ": user " + userId + " is stopped");
                return ActivityManager.BROADCAST_SUCCESS;
            }
        }

        /*
         * Prevent non-system code (defined here to be non-persistent
         * processes) from sending protected broadcasts.
         */
        int callingAppId = UserHandle.getAppId(callingUid);
        if (callingAppId == Process.SYSTEM_UID || callingAppId == Process.PHONE_UID
            || callingAppId == Process.SHELL_UID || callingAppId == Process.BLUETOOTH_UID
            || callingAppId == Process.NFC_UID || callingUid == 0) {
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
                } else if (AppWidgetManager.ACTION_APPWIDGET_CONFIGURE.equals(intent.getAction())) {
                    // Special case for compatibility: we don't want apps to send this,
                    // but historically it has not been protected and apps may be using it
                    // to poke their own app widget.  So, instead of making it protected,
                    // just limit it to the caller.
                    if (callerApp == null) {
                        String msg = "Permission Denial: not allowed to send broadcast "
                                + intent.getAction() + " from unknown caller.";
                        Slog.w(TAG, msg);
                        throw new SecurityException(msg);
                    } else if (intent.getComponent() != null) {
                        // They are good enough to send to an explicit component...  verify
                        // it is being sent to the calling app.
                        if (!intent.getComponent().getPackageName().equals(
                                callerApp.info.packageName)) {
                            String msg = "Permission Denial: not allowed to send broadcast "
                                    + intent.getAction() + " to "
                                    + intent.getComponent().getPackageName() + " from "
                                    + callerApp.info.packageName;
                            Slog.w(TAG, msg);
                            throw new SecurityException(msg);
                        }
                    } else {
                        // Limit broadcast to their own package.
                        intent.setPackage(callerApp.info.packageName);
                    }
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Remote exception", e);
                return ActivityManager.BROADCAST_SUCCESS;
            }
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
                        mAppOpsService.uidRemoved(uid);
                    }
                } else {
                    // If resources are unavailable just force stop all
                    // those packages and flush the attribute cache as well.
                    if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(intent.getAction())) {
                        String list[] = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                        if (list != null && (list.length > 0)) {
                            for (String pkg : list) {
                                forceStopPackageLocked(pkg, -1, false, true, true, false, false, userId,
                                        "storage unmount");
                            }
                            sendPackageBroadcastLocked(
                                    IApplicationThread.EXTERNAL_STORAGE_UNAVAILABLE, list, userId);
                        }
                    } else {
                        Uri data = intent.getData();
                        String ssp;
                        if (data != null && (ssp=data.getSchemeSpecificPart()) != null) {
                            boolean removed = Intent.ACTION_PACKAGE_REMOVED.equals(
                                    intent.getAction());
                            boolean fullUninstall = removed &&
                                    !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                            if (!intent.getBooleanExtra(Intent.EXTRA_DONT_KILL_APP, false)) {
                                forceStopPackageLocked(ssp, UserHandle.getAppId(
                                        intent.getIntExtra(Intent.EXTRA_UID, -1)), false, true, true,
                                        false, fullUninstall, userId,
                                        removed ? "pkg removed" : "pkg changed");
                            }
                            if (removed) {
                                sendPackageBroadcastLocked(IApplicationThread.PACKAGE_REMOVED,
                                        new String[] {ssp}, userId);
                                if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                                    mAppOpsService.packageRemoved(
                                            intent.getIntExtra(Intent.EXTRA_UID, -1), ssp);

                                    // Remove all permissions granted from/to this package
                                    removeUriPermissionsForPackageLocked(ssp, userId, true);
                                }
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
        if (Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())) {
            mHandler.sendEmptyMessage(UPDATE_TIME_ZONE);
        }

        /*
         * If the user set the time, let all running processes know.
         */
        if (Intent.ACTION_TIME_CHANGED.equals(intent.getAction())) {
            final int is24Hour = intent.getBooleanExtra(
                    Intent.EXTRA_TIME_PREF_24_HOUR_FORMAT, false) ? 1 : 0;
            mHandler.sendMessage(mHandler.obtainMessage(UPDATE_TIME, is24Hour, 0));
        }

        if (Intent.ACTION_CLEAR_DNS_CACHE.equals(intent.getAction())) {
            mHandler.sendEmptyMessage(CLEAR_DNS_CACHE_MSG);
        }

        if (Proxy.PROXY_CHANGE_ACTION.equals(intent.getAction())) {
            ProxyInfo proxy = intent.getParcelableExtra(Proxy.EXTRA_PROXY_INFO);
            mHandler.sendMessage(mHandler.obtainMessage(UPDATE_HTTP_PROXY_MSG, proxy));
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
                stickies = new ArrayMap<String, ArrayList<Intent>>();
                mStickyBroadcasts.put(userId, stickies);
            }
            ArrayList<Intent> list = stickies.get(intent.getAction());
            if (list == null) {
                list = new ArrayList<Intent>();
                stickies.put(intent.getAction(), list);
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

        int[] users;
        if (userId == UserHandle.USER_ALL) {
            // Caller wants broadcast to go to all started users.
            users = mStartedUserArray;
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
            receivers = collectReceiverComponents(intent, resolvedType, users);
        }
        if (intent.getComponent() == null) {
            registeredReceivers = mReceiverResolver.queryIntent(intent,
                    resolvedType, false, userId);
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
            final BroadcastQueue queue = broadcastQueueForIntent(intent);
            BroadcastRecord r = new BroadcastRecord(queue, intent, callerApp,
                    callerPackage, callingPid, callingUid, resolvedType, requiredPermission,
                    appOp, registeredReceivers, resultTo, resultCode, resultData, map,
                    ordered, sticky, false, userId);
            if (DEBUG_BROADCAST) Slog.v(
                    TAG, "Enqueueing parallel broadcast " + r);
            final boolean replaced = replacePending && queue.replaceParallelBroadcastLocked(r);
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

        if ((receivers != null && receivers.size() > 0)
                || resultTo != null) {
            BroadcastQueue queue = broadcastQueueForIntent(intent);
            BroadcastRecord r = new BroadcastRecord(queue, intent, callerApp,
                    callerPackage, callingPid, callingUid, resolvedType,
                    requiredPermission, appOp, receivers, resultTo, resultCode,
                    resultData, map, ordered, sticky, false, userId);
            if (DEBUG_BROADCAST) Slog.v(
                    TAG, "Enqueueing ordered broadcast " + r
                    + ": prev had " + queue.mOrderedBroadcasts.size());
            if (DEBUG_BROADCAST) {
                int seq = r.intent.getIntExtra("seq", -1);
                Slog.i(TAG, "Enqueueing broadcast " + r.intent.getAction() + " seq=" + seq);
            }
            boolean replaced = replacePending && queue.replaceOrderedBroadcastLocked(r); 
            if (!replaced) {
                queue.enqueueOrderedBroadcastLocked(r);
                queue.scheduleBroadcastsLocked();
            }
        }

        return ActivityManager.BROADCAST_SUCCESS;
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
            String requiredPermission, int appOp, boolean serialized, boolean sticky, int userId) {
        enforceNotIsolatedCaller("broadcastIntent");
        synchronized(this) {
            intent = verifyBroadcastLocked(intent);
            
            final ProcessRecord callerApp = getRecordForAppLocked(caller);
            final int callingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();
            final long origId = Binder.clearCallingIdentity();
            int res = broadcastIntentLocked(callerApp,
                    callerApp != null ? callerApp.info.packageName : null,
                    intent, resolvedType, resultTo,
                    resultCode, resultData, map, requiredPermission, appOp, serialized, sticky,
                    callingPid, callingUid, userId);
            Binder.restoreCallingIdentity(origId);
            return res;
        }
    }

    int broadcastIntentInPackage(String packageName, int uid,
            Intent intent, String resolvedType, IIntentReceiver resultTo,
            int resultCode, String resultData, Bundle map,
            String requiredPermission, boolean serialized, boolean sticky, int userId) {
        synchronized(this) {
            intent = verifyBroadcastLocked(intent);

            final long origId = Binder.clearCallingIdentity();
            int res = broadcastIntentLocked(null, packageName, intent, resolvedType,
                    resultTo, resultCode, resultData, map, requiredPermission,
                    AppOpsManager.OP_NONE, serialized, sticky, -1, uid, userId);
            Binder.restoreCallingIdentity(origId);
            return res;
        }
    }

    public final void unbroadcastIntent(IApplicationThread caller, Intent intent, int userId) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
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

    private final boolean finishReceiverLocked(IBinder receiver, int resultCode,
            String resultData, Bundle resultExtras, boolean resultAbort) {
        final BroadcastRecord r = broadcastRecordForReceiverLocked(receiver);
        if (r == null) {
            Slog.w(TAG, "finishReceiver called but not found on queue");
            return false;
        }

        return r.queue.finishReceiverLocked(r, resultCode, resultData, resultExtras, resultAbort, false);
    }

    void backgroundServicesFinishedLocked(int userId) {
        for (BroadcastQueue queue : mBroadcastQueues) {
            queue.backgroundServicesFinishedLocked(userId);
        }
    }

    public void finishReceiver(IBinder who, int resultCode, String resultData,
            Bundle resultExtras, boolean resultAbort) {
        if (DEBUG_BROADCAST) Slog.v(TAG, "Finish receiver: " + who);

        // Refuse possible leaked file descriptors
        if (resultExtras != null && resultExtras.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Bundle");
        }

        final long origId = Binder.clearCallingIdentity();
        try {
            boolean doNext = false;
            BroadcastRecord r;

            synchronized(this) {
                r = broadcastRecordForReceiverLocked(who);
                if (r != null) {
                    doNext = r.queue.finishReceiverLocked(r, resultCode,
                        resultData, resultExtras, resultAbort, true);
                }
            }

            if (doNext) {
                r.queue.processNextBroadcast(false);
            }
            trimApplications();
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
        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
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
            // Instrumentation can kill and relaunch even persistent processes
            forceStopPackageLocked(ii.targetPackage, -1, true, false, true, true, false, userId,
                    "start instr");
            ProcessRecord app = addAppLocked(ai, false, abiOverride);
            app.instrumentationClass = className;
            app.instrumentationInfo = ai;
            app.instrumentationProfileFile = profileFile;
            app.instrumentationArguments = arguments;
            app.instrumentationWatcher = watcher;
            app.instrumentationUiAutomationConnection = uiAutomationConnection;
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
        if (app.instrumentationUiAutomationConnection != null) {
            try {
                app.instrumentationUiAutomationConnection.shutdown();
            } catch (RemoteException re) {
                /* ignore */
            }
            // Only a UiAutomation can set this flag and now that
            // it is finished we make sure it is reset to its default.
            mUserIsMonkey = false;
        }
        app.instrumentationWatcher = null;
        app.instrumentationUiAutomationConnection = null;
        app.instrumentationClass = null;
        app.instrumentationInfo = null;
        app.instrumentationProfileFile = null;
        app.instrumentationArguments = null;

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

    ActivityStack getFocusedStack() {
        return mStackSupervisor.getFocusedStack();
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
            updateConfigurationLocked(values, null, true, false);
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
            updateConfigurationLocked(values, null, false, false);
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
    boolean updateConfigurationLocked(Configuration values,
            ActivityRecord starting, boolean persistent, boolean initLocale) {
        int changes = 0;

        if (values != null) {
            Configuration newConfig = new Configuration(mConfiguration);
            changes = newConfig.updateFrom(values);
            if (changes != 0) {
                if (DEBUG_SWITCH || DEBUG_CONFIGURATION) {
                    Slog.i(TAG, "Updating configuration to: " + values);
                }
                
                EventLog.writeEvent(EventLogTags.CONFIGURATION_CHANGED, changes);

                if (values.locale != null && !initLocale) {
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
                Slog.i(TAG, "Config changes=" + Integer.toHexString(changes) + " " + newConfig);
                //mUsageStatsService.noteStartConfig(newConfig);

                final Configuration configCopy = new Configuration(mConfiguration);
                
                // TODO: If our config changes, should we auto dismiss any currently
                // showing dialogs?
                mShowDialogs = shouldShowDialogs(newConfig);

                AttributeCache ac = AttributeCache.instance();
                if (ac != null) {
                    ac.updateConfiguration(configCopy);
                }

                // Make sure all resources in our process are updated
                // right now, so that anyone who is going to retrieve
                // resource values after we return will be sure to get
                // the new ones.  This is especially important during
                // boot, where the first config change needs to guarantee
                // all resources have that config before following boot
                // code is executed.
                mSystemThread.applyConfigurationToResources(configCopy);

                if (persistent && Settings.System.hasInterestingConfigurationChanges(changes)) {
                    Message msg = mHandler.obtainMessage(UPDATE_CONFIGURATION_MSG);
                    msg.obj = new Configuration(configCopy);
                    mHandler.sendMessage(msg);
                }

                for (int i=mLruProcesses.size()-1; i>=0; i--) {
                    ProcessRecord app = mLruProcesses.get(i);
                    try {
                        if (app.thread != null) {
                            if (DEBUG_CONFIGURATION) Slog.v(TAG, "Sending to proc "
                                    + app.processName + " new config " + mConfiguration);
                            app.thread.scheduleConfigurationChanged(configCopy);
                        }
                    } catch (Exception e) {
                    }
                }
                Intent intent = new Intent(Intent.ACTION_CONFIGURATION_CHANGED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                        | Intent.FLAG_RECEIVER_REPLACE_PENDING
                        | Intent.FLAG_RECEIVER_FOREGROUND);
                broadcastIntentLocked(null, null, intent, null, null, 0, null, null,
                        null, AppOpsManager.OP_NONE, false, false, MY_PID,
                        Process.SYSTEM_UID, UserHandle.USER_ALL);
                if ((changes&ActivityInfo.CONFIG_LOCALE) != 0) {
                    intent = new Intent(Intent.ACTION_LOCALE_CHANGED);
                    intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    broadcastIntentLocked(null, null, intent,
                            null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                            false, false, MY_PID, Process.SYSTEM_UID, UserHandle.USER_ALL);
                }
            }
        }

        boolean kept = true;
        final ActivityStack mainStack = mStackSupervisor.getFocusedStack();
        // mainStack is null during startup.
        if (mainStack != null) {
            if (changes != 0 && starting == null) {
                // If the configuration changed, and the caller is not already
                // in the process of starting an activity, then find the top
                // activity to check if its configuration needs to change.
                starting = mainStack.topRunningActivityLocked(null);
            }

            if (starting != null) {
                kept = mainStack.ensureActivityConfigurationLocked(starting, changes);
                // And we need to make sure at this point that all other activities
                // are made visible with the correct configuration.
                mStackSupervisor.ensureActivitiesVisibleLocked(starting, changes);
            }
        }

        if (values != null && mWindowManager != null) {
            mWindowManager.setNewConfiguration(mConfiguration);
        }

        return kept;
    }

    /**
     * Decide based on the configuration whether we should shouw the ANR,
     * crash, etc dialogs.  The idea is that if there is no affordnace to
     * press the on-screen buttons, we shouldn't show the dialog.
     *
     * A thought: SystemUI might also want to get told about this, the Power
     * dialog / global actions also might want different behaviors.
     */
    private static final boolean shouldShowDialogs(Configuration config) {
        return !(config.keyboard == Configuration.KEYBOARD_NOKEYS
                && config.touchscreen == Configuration.TOUCHSCREEN_NOTOUCH);
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

    @Override
    public boolean shouldUpRecreateTask(IBinder token, String destAffinity) {
        synchronized (this) {
            ActivityRecord srec = ActivityRecord.forToken(token);
            if (srec.task != null && srec.task.stack != null) {
                return srec.task.stack.shouldUpRecreateTaskLocked(srec, destAffinity);
            }
        }
        return false;
    }

    public boolean navigateUpTo(IBinder token, Intent destIntent, int resultCode,
            Intent resultData) {

        synchronized (this) {
            final ActivityStack stack = ActivityRecord.getStackLocked(token);
            if (stack != null) {
                return stack.navigateUpToLocked(token, destIntent, resultCode, resultData);
            }
            return false;
        }
    }

    public int getLaunchedFromUid(IBinder activityToken) {
        ActivityRecord srec = ActivityRecord.forToken(activityToken);
        if (srec == null) {
            return -1;
        }
        return srec.launchedFromUid;
    }

    public String getLaunchedFromPackage(IBinder activityToken) {
        ActivityRecord srec = ActivityRecord.forToken(activityToken);
        if (srec == null) {
            return null;
        }
        return srec.launchedFromPackage;
    }

    // =========================================================
    // LIFETIME MANAGEMENT
    // =========================================================

    // Returns which broadcast queue the app is the current [or imminent] receiver
    // on, or 'null' if the app is not an active broadcast recipient.
    private BroadcastQueue isReceivingBroadcast(ProcessRecord app) {
        BroadcastRecord r = app.curReceiver;
        if (r != null) {
            return r.queue;
        }

        // It's not the current receiver, but it might be starting up to become one
        synchronized (this) {
            for (BroadcastQueue queue : mBroadcastQueues) {
                r = queue.mPendingBroadcast;
                if (r != null && r.curApp == app) {
                    // found it; report which queue it's in
                    return queue;
                }
            }
        }

        return null;
    }

    private final int computeOomAdjLocked(ProcessRecord app, int cachedAdj, ProcessRecord TOP_APP,
            boolean doingAll, long now) {
        if (mAdjSeq == app.adjSeq) {
            // This adjustment has already been computed.
            return app.curRawAdj;
        }

        if (app.thread == null) {
            app.adjSeq = mAdjSeq;
            app.curSchedGroup = Process.THREAD_GROUP_BG_NONINTERACTIVE;
            app.curProcState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
            return (app.curAdj=app.curRawAdj=ProcessList.CACHED_APP_MAX_ADJ);
        }

        app.adjTypeCode = ActivityManager.RunningAppProcessInfo.REASON_UNKNOWN;
        app.adjSource = null;
        app.adjTarget = null;
        app.empty = false;
        app.cached = false;

        final int activitiesSize = app.activities.size();

        if (app.maxAdj <= ProcessList.FOREGROUND_APP_ADJ) {
            // The max adjustment doesn't allow this app to be anything
            // below foreground, so it is not worth doing work for it.
            app.adjType = "fixed";
            app.adjSeq = mAdjSeq;
            app.curRawAdj = app.maxAdj;
            app.foregroundActivities = false;
            app.curSchedGroup = Process.THREAD_GROUP_DEFAULT;
            app.curProcState = ActivityManager.PROCESS_STATE_PERSISTENT;
            // System processes can do UI, and when they do we want to have
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
                    }
                }
            }
            if (!app.systemNoUi) {
                app.curProcState = ActivityManager.PROCESS_STATE_PERSISTENT_UI;
            }
            return (app.curAdj=app.maxAdj);
        }

        app.systemNoUi = false;

        // Determine the importance of the process, starting with most
        // important to least, and assign an appropriate OOM adjustment.
        int adj;
        int schedGroup;
        int procState;
        boolean foregroundActivities = false;
        BroadcastQueue queue;
        if (app == TOP_APP) {
            // The last app on the list is the foreground app.
            adj = ProcessList.FOREGROUND_APP_ADJ;
            schedGroup = Process.THREAD_GROUP_DEFAULT;
            app.adjType = "top-activity";
            foregroundActivities = true;
            procState = ActivityManager.PROCESS_STATE_TOP;
        } else if (app.instrumentationClass != null) {
            // Don't want to kill running instrumentation.
            adj = ProcessList.FOREGROUND_APP_ADJ;
            schedGroup = Process.THREAD_GROUP_DEFAULT;
            app.adjType = "instrumentation";
            procState = ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
        } else if ((queue = isReceivingBroadcast(app)) != null) {
            // An app that is currently receiving a broadcast also
            // counts as being in the foreground for OOM killer purposes.
            // It's placed in a sched group based on the nature of the
            // broadcast as reflected by which queue it's active in.
            adj = ProcessList.FOREGROUND_APP_ADJ;
            schedGroup = (queue == mFgBroadcastQueue)
                    ? Process.THREAD_GROUP_DEFAULT : Process.THREAD_GROUP_BG_NONINTERACTIVE;
            app.adjType = "broadcast";
            procState = ActivityManager.PROCESS_STATE_RECEIVER;
        } else if (app.executingServices.size() > 0) {
            // An app that is currently executing a service callback also
            // counts as being in the foreground.
            adj = ProcessList.FOREGROUND_APP_ADJ;
            schedGroup = app.execServicesFg ?
                    Process.THREAD_GROUP_DEFAULT : Process.THREAD_GROUP_BG_NONINTERACTIVE;
            app.adjType = "exec-service";
            procState = ActivityManager.PROCESS_STATE_SERVICE;
            //Slog.i(TAG, "EXEC " + (app.execServicesFg ? "FG" : "BG") + ": " + app);
        } else {
            // As far as we know the process is empty.  We may change our mind later.
            schedGroup = Process.THREAD_GROUP_BG_NONINTERACTIVE;
            // At this point we don't actually know the adjustment.  Use the cached adj
            // value that the caller wants us to.
            adj = cachedAdj;
            procState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
            app.cached = true;
            app.empty = true;
            app.adjType = "cch-empty";
        }

        // Examine all activities if not already foreground.
        if (!foregroundActivities && activitiesSize > 0) {
            for (int j = 0; j < activitiesSize; j++) {
                final ActivityRecord r = app.activities.get(j);
                if (r.app != app) {
                    Slog.w(TAG, "Wtf, activity " + r + " in proc activity list not using proc "
                            + app + "?!?");
                    continue;
                }
                if (r.visible) {
                    // App has a visible activity; only upgrade adjustment.
                    if (adj > ProcessList.VISIBLE_APP_ADJ) {
                        adj = ProcessList.VISIBLE_APP_ADJ;
                        app.adjType = "visible";
                    }
                    if (procState > ActivityManager.PROCESS_STATE_TOP) {
                        procState = ActivityManager.PROCESS_STATE_TOP;
                    }
                    schedGroup = Process.THREAD_GROUP_DEFAULT;
                    app.cached = false;
                    app.empty = false;
                    foregroundActivities = true;
                    break;
                } else if (r.state == ActivityState.PAUSING || r.state == ActivityState.PAUSED) {
                    if (adj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                        adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                        app.adjType = "pausing";
                    }
                    if (procState > ActivityManager.PROCESS_STATE_TOP) {
                        procState = ActivityManager.PROCESS_STATE_TOP;
                    }
                    schedGroup = Process.THREAD_GROUP_DEFAULT;
                    app.cached = false;
                    app.empty = false;
                    foregroundActivities = true;
                } else if (r.state == ActivityState.STOPPING) {
                    if (adj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                        adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                        app.adjType = "stopping";
                    }
                    // For the process state, we will at this point consider the
                    // process to be cached.  It will be cached either as an activity
                    // or empty depending on whether the activity is finishing.  We do
                    // this so that we can treat the process as cached for purposes of
                    // memory trimming (determing current memory level, trim command to
                    // send to process) since there can be an arbitrary number of stopping
                    // processes and they should soon all go into the cached state.
                    if (!r.finishing) {
                        if (procState > ActivityManager.PROCESS_STATE_CACHED_ACTIVITY) {
                            procState = ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
                        }
                    }
                    app.cached = false;
                    app.empty = false;
                    foregroundActivities = true;
                } else {
                    if (procState > ActivityManager.PROCESS_STATE_CACHED_ACTIVITY) {
                        procState = ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
                        app.adjType = "cch-act";
                    }
                }
            }
        }

        if (adj > ProcessList.PERCEPTIBLE_APP_ADJ) {
            if (app.foregroundServices) {
                // The user is aware of this app, so make it visible.
                adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                procState = ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
                app.cached = false;
                app.adjType = "fg-service";
                schedGroup = Process.THREAD_GROUP_DEFAULT;
            } else if (app.forcingToForeground != null) {
                // The user is aware of this app, so make it visible.
                adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                procState = ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
                app.cached = false;
                app.adjType = "force-fg";
                app.adjSource = app.forcingToForeground;
                schedGroup = Process.THREAD_GROUP_DEFAULT;
            }
        }

        if (app == mHeavyWeightProcess) {
            if (adj > ProcessList.HEAVY_WEIGHT_APP_ADJ) {
                // We don't want to kill the current heavy-weight process.
                adj = ProcessList.HEAVY_WEIGHT_APP_ADJ;
                schedGroup = Process.THREAD_GROUP_BG_NONINTERACTIVE;
                app.cached = false;
                app.adjType = "heavy";
            }
            if (procState > ActivityManager.PROCESS_STATE_HEAVY_WEIGHT) {
                procState = ActivityManager.PROCESS_STATE_HEAVY_WEIGHT;
            }
        }

        if (app == mHomeProcess) {
            if (adj > ProcessList.HOME_APP_ADJ) {
                // This process is hosting what we currently consider to be the
                // home app, so we don't want to let it go into the background.
                adj = ProcessList.HOME_APP_ADJ;
                schedGroup = Process.THREAD_GROUP_BG_NONINTERACTIVE;
                app.cached = false;
                app.adjType = "home";
            }
            if (procState > ActivityManager.PROCESS_STATE_HOME) {
                procState = ActivityManager.PROCESS_STATE_HOME;
            }
        }

        if (app == mPreviousProcess && app.activities.size() > 0) {
            if (adj > ProcessList.PREVIOUS_APP_ADJ) {
                // This was the previous process that showed UI to the user.
                // We want to try to keep it around more aggressively, to give
                // a good experience around switching between two apps.
                adj = ProcessList.PREVIOUS_APP_ADJ;
                schedGroup = Process.THREAD_GROUP_BG_NONINTERACTIVE;
                app.cached = false;
                app.adjType = "previous";
            }
            if (procState > ActivityManager.PROCESS_STATE_LAST_ACTIVITY) {
                procState = ActivityManager.PROCESS_STATE_LAST_ACTIVITY;
            }
        }

        if (false) Slog.i(TAG, "OOM " + app + ": initial adj=" + adj
                + " reason=" + app.adjType);

        // By default, we use the computed adjustment.  It may be changed if
        // there are applications dependent on our services or providers, but
        // this gives us a baseline and makes sure we don't get into an
        // infinite recursion.
        app.adjSeq = mAdjSeq;
        app.curRawAdj = adj;
        app.hasStartedServices = false;

        if (mBackupTarget != null && app == mBackupTarget.app) {
            // If possible we want to avoid killing apps while they're being backed up
            if (adj > ProcessList.BACKUP_APP_ADJ) {
                if (DEBUG_BACKUP) Slog.v(TAG, "oom BACKUP_APP_ADJ for " + app);
                adj = ProcessList.BACKUP_APP_ADJ;
                if (procState > ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND) {
                    procState = ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND;
                }
                app.adjType = "backup";
                app.cached = false;
            }
            if (procState > ActivityManager.PROCESS_STATE_BACKUP) {
                procState = ActivityManager.PROCESS_STATE_BACKUP;
            }
        }

        boolean mayBeTop = false;

        for (int is = app.services.size()-1;
                is >= 0 && (adj > ProcessList.FOREGROUND_APP_ADJ
                        || schedGroup == Process.THREAD_GROUP_BG_NONINTERACTIVE
                        || procState > ActivityManager.PROCESS_STATE_TOP);
                is--) {
            ServiceRecord s = app.services.valueAt(is);
            if (s.startRequested) {
                app.hasStartedServices = true;
                if (procState > ActivityManager.PROCESS_STATE_SERVICE) {
                    procState = ActivityManager.PROCESS_STATE_SERVICE;
                }
                if (app.hasShownUi && app != mHomeProcess) {
                    // If this process has shown some UI, let it immediately
                    // go to the LRU list because it may be pretty heavy with
                    // UI stuff.  We'll tag it with a label just to help
                    // debug and understand what is going on.
                    if (adj > ProcessList.SERVICE_ADJ) {
                        app.adjType = "cch-started-ui-services";
                    }
                } else {
                    if (now < (s.lastActivity + ActiveServices.MAX_SERVICE_INACTIVITY)) {
                        // This service has seen some activity within
                        // recent memory, so we will keep its process ahead
                        // of the background processes.
                        if (adj > ProcessList.SERVICE_ADJ) {
                            adj = ProcessList.SERVICE_ADJ;
                            app.adjType = "started-services";
                            app.cached = false;
                        }
                    }
                    // If we have let the service slide into the background
                    // state, still have some text describing what it is doing
                    // even though the service no longer has an impact.
                    if (adj > ProcessList.SERVICE_ADJ) {
                        app.adjType = "cch-started-services";
                    }
                }
            }
            for (int conni = s.connections.size()-1;
                    conni >= 0 && (adj > ProcessList.FOREGROUND_APP_ADJ
                            || schedGroup == Process.THREAD_GROUP_BG_NONINTERACTIVE
                            || procState > ActivityManager.PROCESS_STATE_TOP);
                    conni--) {
                ArrayList<ConnectionRecord> clist = s.connections.valueAt(conni);
                for (int i = 0;
                        i < clist.size() && (adj > ProcessList.FOREGROUND_APP_ADJ
                                || schedGroup == Process.THREAD_GROUP_BG_NONINTERACTIVE
                                || procState > ActivityManager.PROCESS_STATE_TOP);
                        i++) {
                    // XXX should compute this based on the max of
                    // all connected clients.
                    ConnectionRecord cr = clist.get(i);
                    if (cr.binding.client == app) {
                        // Binding to ourself is not interesting.
                        continue;
                    }
                    if ((cr.flags&Context.BIND_WAIVE_PRIORITY) == 0) {
                        ProcessRecord client = cr.binding.client;
                        int clientAdj = computeOomAdjLocked(client, cachedAdj,
                                TOP_APP, doingAll, now);
                        int clientProcState = client.curProcState;
                        if (clientProcState >= ActivityManager.PROCESS_STATE_CACHED_ACTIVITY) {
                            // If the other app is cached for any reason, for purposes here
                            // we are going to consider it empty.  The specific cached state
                            // doesn't propagate except under certain conditions.
                            clientProcState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
                        }
                        String adjType = null;
                        if ((cr.flags&Context.BIND_ALLOW_OOM_MANAGEMENT) != 0) {
                            // Not doing bind OOM management, so treat
                            // this guy more like a started service.
                            if (app.hasShownUi && app != mHomeProcess) {
                                // If this process has shown some UI, let it immediately
                                // go to the LRU list because it may be pretty heavy with
                                // UI stuff.  We'll tag it with a label just to help
                                // debug and understand what is going on.
                                if (adj > clientAdj) {
                                    adjType = "cch-bound-ui-services";
                                }
                                app.cached = false;
                                clientAdj = adj;
                                clientProcState = procState;
                            } else {
                                if (now >= (s.lastActivity
                                        + ActiveServices.MAX_SERVICE_INACTIVITY)) {
                                    // This service has not seen activity within
                                    // recent memory, so allow it to drop to the
                                    // LRU list if there is no other reason to keep
                                    // it around.  We'll also tag it with a label just
                                    // to help debug and undertand what is going on.
                                    if (adj > clientAdj) {
                                        adjType = "cch-bound-services";
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
                            if (app.hasShownUi && app != mHomeProcess
                                    && clientAdj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                                adjType = "cch-bound-ui-services";
                            } else {
                                if ((cr.flags&(Context.BIND_ABOVE_CLIENT
                                        |Context.BIND_IMPORTANT)) != 0) {
                                    adj = clientAdj;
                                } else if ((cr.flags&Context.BIND_NOT_VISIBLE) != 0
                                        && clientAdj < ProcessList.PERCEPTIBLE_APP_ADJ
                                        && adj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                                    adj = ProcessList.PERCEPTIBLE_APP_ADJ;
                                } else if (clientAdj > ProcessList.VISIBLE_APP_ADJ) {
                                    adj = clientAdj;
                                } else {
                                    if (adj > ProcessList.VISIBLE_APP_ADJ) {
                                        adj = ProcessList.VISIBLE_APP_ADJ;
                                    }
                                }
                                if (!client.cached) {
                                    app.cached = false;
                                }
                                adjType = "service";
                            }
                        }
                        if ((cr.flags&Context.BIND_NOT_FOREGROUND) == 0) {
                            if (client.curSchedGroup == Process.THREAD_GROUP_DEFAULT) {
                                schedGroup = Process.THREAD_GROUP_DEFAULT;
                            }
                            if (clientProcState <= ActivityManager.PROCESS_STATE_TOP) {
                                if (clientProcState == ActivityManager.PROCESS_STATE_TOP) {
                                    // Special handling of clients who are in the top state.
                                    // We *may* want to consider this process to be in the
                                    // top state as well, but only if there is not another
                                    // reason for it to be running.  Being on the top is a
                                    // special state, meaning you are specifically running
                                    // for the current top app.  If the process is already
                                    // running in the background for some other reason, it
                                    // is more important to continue considering it to be
                                    // in the background state.
                                    mayBeTop = true;
                                    clientProcState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
                                } else {
                                    // Special handling for above-top states (persistent
                                    // processes).  These should not bring the current process
                                    // into the top state, since they are not on top.  Instead
                                    // give them the best state after that.
                                    clientProcState =
                                            ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
                                }
                            }
                        } else {
                            if (clientProcState <
                                    ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND) {
                                clientProcState =
                                        ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND;
                            }
                        }
                        if (procState > clientProcState) {
                            procState = clientProcState;
                        }
                        if (procState < ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
                                && (cr.flags&Context.BIND_SHOWING_UI) != 0) {
                            app.pendingUiClean = true;
                        }
                        if (adjType != null) {
                            app.adjType = adjType;
                            app.adjTypeCode = ActivityManager.RunningAppProcessInfo
                                    .REASON_SERVICE_IN_USE;
                            app.adjSource = cr.binding.client;
                            app.adjSourceProcState = clientProcState;
                            app.adjTarget = s.name;
                        }
                    }
                    if ((cr.flags&Context.BIND_TREAT_LIKE_ACTIVITY) != 0) {
                        app.treatLikeActivity = true;
                    }
                    final ActivityRecord a = cr.activity;
                    if ((cr.flags&Context.BIND_ADJUST_WITH_ACTIVITY) != 0) {
                        if (a != null && adj > ProcessList.FOREGROUND_APP_ADJ &&
                                (a.visible || a.state == ActivityState.RESUMED
                                 || a.state == ActivityState.PAUSING)) {
                            adj = ProcessList.FOREGROUND_APP_ADJ;
                            if ((cr.flags&Context.BIND_NOT_FOREGROUND) == 0) {
                                schedGroup = Process.THREAD_GROUP_DEFAULT;
                            }
                            app.cached = false;
                            app.adjType = "service";
                            app.adjTypeCode = ActivityManager.RunningAppProcessInfo
                                    .REASON_SERVICE_IN_USE;
                            app.adjSource = a;
                            app.adjSourceProcState = procState;
                            app.adjTarget = s.name;
                        }
                    }
                }
            }
        }

        for (int provi = app.pubProviders.size()-1;
                provi >= 0 && (adj > ProcessList.FOREGROUND_APP_ADJ
                        || schedGroup == Process.THREAD_GROUP_BG_NONINTERACTIVE
                        || procState > ActivityManager.PROCESS_STATE_TOP);
                provi--) {
            ContentProviderRecord cpr = app.pubProviders.valueAt(provi);
            for (int i = cpr.connections.size()-1;
                    i >= 0 && (adj > ProcessList.FOREGROUND_APP_ADJ
                            || schedGroup == Process.THREAD_GROUP_BG_NONINTERACTIVE
                            || procState > ActivityManager.PROCESS_STATE_TOP);
                    i--) {
                ContentProviderConnection conn = cpr.connections.get(i);
                ProcessRecord client = conn.client;
                if (client == app) {
                    // Being our own client is not interesting.
                    continue;
                }
                int clientAdj = computeOomAdjLocked(client, cachedAdj, TOP_APP, doingAll, now);
                int clientProcState = client.curProcState;
                if (clientProcState >= ActivityManager.PROCESS_STATE_CACHED_ACTIVITY) {
                    // If the other app is cached for any reason, for purposes here
                    // we are going to consider it empty.
                    clientProcState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
                }
                if (adj > clientAdj) {
                    if (app.hasShownUi && app != mHomeProcess
                            && clientAdj > ProcessList.PERCEPTIBLE_APP_ADJ) {
                        app.adjType = "cch-ui-provider";
                    } else {
                        adj = clientAdj > ProcessList.FOREGROUND_APP_ADJ
                                ? clientAdj : ProcessList.FOREGROUND_APP_ADJ;
                        app.adjType = "provider";
                    }
                    app.cached &= client.cached;
                    app.adjTypeCode = ActivityManager.RunningAppProcessInfo
                            .REASON_PROVIDER_IN_USE;
                    app.adjSource = client;
                    app.adjSourceProcState = clientProcState;
                    app.adjTarget = cpr.name;
                }
                if (clientProcState <= ActivityManager.PROCESS_STATE_TOP) {
                    if (clientProcState == ActivityManager.PROCESS_STATE_TOP) {
                        // Special handling of clients who are in the top state.
                        // We *may* want to consider this process to be in the
                        // top state as well, but only if there is not another
                        // reason for it to be running.  Being on the top is a
                        // special state, meaning you are specifically running
                        // for the current top app.  If the process is already
                        // running in the background for some other reason, it
                        // is more important to continue considering it to be
                        // in the background state.
                        mayBeTop = true;
                        clientProcState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
                    } else {
                        // Special handling for above-top states (persistent
                        // processes).  These should not bring the current process
                        // into the top state, since they are not on top.  Instead
                        // give them the best state after that.
                        clientProcState =
                                ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
                    }
                }
                if (procState > clientProcState) {
                    procState = clientProcState;
                }
                if (client.curSchedGroup == Process.THREAD_GROUP_DEFAULT) {
                    schedGroup = Process.THREAD_GROUP_DEFAULT;
                }
            }
            // If the provider has external (non-framework) process
            // dependencies, ensure that its adjustment is at least
            // FOREGROUND_APP_ADJ.
            if (cpr.hasExternalProcessHandles()) {
                if (adj > ProcessList.FOREGROUND_APP_ADJ) {
                    adj = ProcessList.FOREGROUND_APP_ADJ;
                    schedGroup = Process.THREAD_GROUP_DEFAULT;
                    app.cached = false;
                    app.adjType = "provider";
                    app.adjTarget = cpr.name;
                }
                if (procState > ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND) {
                    procState = ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
                }
            }
        }

        if (mayBeTop && procState > ActivityManager.PROCESS_STATE_TOP) {
            // A client of one of our services or providers is in the top state.  We
            // *may* want to be in the top state, but not if we are already running in
            // the background for some other reason.  For the decision here, we are going
            // to pick out a few specific states that we want to remain in when a client
            // is top (states that tend to be longer-term) and otherwise allow it to go
            // to the top state.
            switch (procState) {
                case ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND:
                case ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND:
                case ActivityManager.PROCESS_STATE_SERVICE:
                    // These all are longer-term states, so pull them up to the top
                    // of the background states, but not all the way to the top state.
                    procState = ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
                    break;
                default:
                    // Otherwise, top is a better choice, so take it.
                    procState = ActivityManager.PROCESS_STATE_TOP;
                    break;
            }
        }

        if (procState >= ActivityManager.PROCESS_STATE_CACHED_EMPTY) {
            if (app.hasClientActivities) {
                // This is a cached process, but with client activities.  Mark it so.
                procState = ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT;
                app.adjType = "cch-client-act";
            } else if (app.treatLikeActivity) {
                // This is a cached process, but somebody wants us to treat it like it has
                // an activity, okay!
                procState = ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
                app.adjType = "cch-as-act";
            }
        }

        if (adj == ProcessList.SERVICE_ADJ) {
            if (doingAll) {
                app.serviceb = mNewNumAServiceProcs > (mNumServiceProcs/3);
                mNewNumServiceProcs++;
                //Slog.i(TAG, "ADJ " + app + " serviceb=" + app.serviceb);
                if (!app.serviceb) {
                    // This service isn't far enough down on the LRU list to
                    // normally be a B service, but if we are low on RAM and it
                    // is large we want to force it down since we would prefer to
                    // keep launcher over it.
                    if (mLastMemoryLevel > ProcessStats.ADJ_MEM_FACTOR_NORMAL
                            && app.lastPss >= mProcessList.getCachedRestoreThresholdKb()) {
                        app.serviceHighRam = true;
                        app.serviceb = true;
                        //Slog.i(TAG, "ADJ " + app + " high ram!");
                    } else {
                        mNewNumAServiceProcs++;
                        //Slog.i(TAG, "ADJ " + app + " not high ram!");
                    }
                } else {
                    app.serviceHighRam = false;
                }
            }
            if (app.serviceb) {
                adj = ProcessList.SERVICE_B_ADJ;
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

        // Do final modification to adj.  Everything we do between here and applying
        // the final setAdj must be done in this function, because we will also use
        // it when computing the final cached adj later.  Note that we don't need to
        // worry about this for max adj above, since max adj will always be used to
        // keep it out of the cached vaues.
        app.curAdj = app.modifyRawOomAdj(adj);
        app.curSchedGroup = schedGroup;
        app.curProcState = procState;
        app.foregroundActivities = foregroundActivities;

        return app.curRawAdj;
    }

    /**
     * Schedule PSS collection of a process.
     */
    void requestPssLocked(ProcessRecord proc, int procState) {
        if (mPendingPssProcesses.contains(proc)) {
            return;
        }
        if (mPendingPssProcesses.size() == 0) {
            mBgHandler.sendEmptyMessage(COLLECT_PSS_BG_MSG);
        }
        if (DEBUG_PSS) Slog.d(TAG, "Requesting PSS of: " + proc);
        proc.pssProcState = procState;
        mPendingPssProcesses.add(proc);
    }

    /**
     * Schedule PSS collection of all processes.
     */
    void requestPssAllProcsLocked(long now, boolean always, boolean memLowered) {
        if (!always) {
            if (now < (mLastFullPssTime +
                    (memLowered ? FULL_PSS_LOWERED_INTERVAL : FULL_PSS_MIN_INTERVAL))) {
                return;
            }
        }
        if (DEBUG_PSS) Slog.d(TAG, "Requesting PSS of all procs!  memLowered=" + memLowered);
        mLastFullPssTime = now;
        mFullPssPending = true;
        mPendingPssProcesses.ensureCapacity(mLruProcesses.size());
        mPendingPssProcesses.clear();
        for (int i=mLruProcesses.size()-1; i>=0; i--) {
            ProcessRecord app = mLruProcesses.get(i);
            if (memLowered || now > (app.lastStateTime+ProcessList.PSS_ALL_INTERVAL)) {
                app.pssProcState = app.setProcState;
                app.nextPssTime = ProcessList.computeNextPssTime(app.curProcState, true,
                        isSleeping(), now);
                mPendingPssProcesses.add(app);
            }
        }
        mBgHandler.sendEmptyMessage(COLLECT_PSS_BG_MSG);
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
        boolean processingBroadcasts = false;
        for (BroadcastQueue q : mBroadcastQueues) {
            if (q.mParallelBroadcasts.size() != 0 || q.mOrderedBroadcasts.size() != 0) {
                processingBroadcasts = true;
            }
        }
        return !processingBroadcasts
                && (isSleeping() || mStackSupervisor.allResumedActivitiesIdle());
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
            if (app.setProcState >= ActivityManager.PROCESS_STATE_HOME) {
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
                // that sounds bad.  Kill!
                if (doWakeKills && realtimeSince > 0
                        && ((wtimeUsed*100)/realtimeSince) >= 50) {
                    synchronized (stats) {
                        stats.reportExcessiveWakeLocked(app.info.uid, app.processName,
                                realtimeSince, wtimeUsed);
                    }
                    killUnneededProcessLocked(app, "excessive wake held " + wtimeUsed
                            + " during " + realtimeSince);
                    app.baseProcessTracker.reportExcessiveWake(app.pkgList);
                } else if (doCpuKills && uptimeSince > 0
                        && ((cputimeUsed*100)/uptimeSince) >= 25) {
                    synchronized (stats) {
                        stats.reportExcessiveCpuLocked(app.info.uid, app.processName,
                                uptimeSince, cputimeUsed);
                    }
                    killUnneededProcessLocked(app, "excessive cpu " + cputimeUsed
                            + " during " + uptimeSince);
                    app.baseProcessTracker.reportExcessiveCpu(app.pkgList);
                } else {
                    app.lastWakeTime = wtime;
                    app.lastCpuTime = app.curCpuTime;
                }
            }
        }
    }

    private final boolean applyOomAdjLocked(ProcessRecord app,
            ProcessRecord TOP_APP, boolean doingAll, long now) {
        boolean success = true;

        if (app.curRawAdj != app.setRawAdj) {
            app.setRawAdj = app.curRawAdj;
        }

        int changes = 0;

        if (app.curAdj != app.setAdj) {
            ProcessList.setOomAdj(app.pid, app.info.uid, app.curAdj);
            if (DEBUG_SWITCH || DEBUG_OOM_ADJ) Slog.v(
                TAG, "Set " + app.pid + " " + app.processName +
                " adj " + app.curAdj + ": " + app.adjType);
            app.setAdj = app.curAdj;
        }

        if (app.setSchedGroup != app.curSchedGroup) {
            app.setSchedGroup = app.curSchedGroup;
            if (DEBUG_SWITCH || DEBUG_OOM_ADJ) Slog.v(TAG,
                    "Setting process group of " + app.processName
                    + " to " + app.curSchedGroup);
            if (app.waitingToKill != null &&
                    app.setSchedGroup == Process.THREAD_GROUP_BG_NONINTERACTIVE) {
                killUnneededProcessLocked(app, app.waitingToKill);
                success = false;
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
                Process.setSwappiness(app.pid,
                        app.curSchedGroup <= Process.THREAD_GROUP_BG_NONINTERACTIVE);
            }
        }
        if (app.repForegroundActivities != app.foregroundActivities) {
            app.repForegroundActivities = app.foregroundActivities;
            changes |= ProcessChangeItem.CHANGE_ACTIVITIES;
        }
        if (app.repProcState != app.curProcState) {
            app.repProcState = app.curProcState;
            changes |= ProcessChangeItem.CHANGE_PROCESS_STATE;
            if (app.thread != null) {
                try {
                    if (false) {
                        //RuntimeException h = new RuntimeException("here");
                        Slog.i(TAG, "Sending new process state " + app.repProcState
                                + " to " + app /*, h*/);
                    }
                    app.thread.setProcessState(app.repProcState);
                } catch (RemoteException e) {
                }
            }
        }
        if (app.setProcState < 0 || ProcessList.procStatesDifferForMem(app.curProcState,
                app.setProcState)) {
            app.lastStateTime = now;
            app.nextPssTime = ProcessList.computeNextPssTime(app.curProcState, true,
                    isSleeping(), now);
            if (DEBUG_PSS) Slog.d(TAG, "Process state change from "
                    + ProcessList.makeProcStateString(app.setProcState) + " to "
                    + ProcessList.makeProcStateString(app.curProcState) + " next pss in "
                    + (app.nextPssTime-now) + ": " + app);
        } else {
            if (now > app.nextPssTime || (now > (app.lastPssTime+ProcessList.PSS_MAX_INTERVAL)
                    && now > (app.lastStateTime+ProcessList.PSS_MIN_TIME_FROM_STATE_CHANGE))) {
                requestPssLocked(app, app.setProcState);
                app.nextPssTime = ProcessList.computeNextPssTime(app.curProcState, false,
                        isSleeping(), now);
            } else if (false && DEBUG_PSS) {
                Slog.d(TAG, "Not requesting PSS of " + app + ": next=" + (app.nextPssTime-now));
            }
        }
        if (app.setProcState != app.curProcState) {
            if (DEBUG_SWITCH || DEBUG_OOM_ADJ) Slog.v(TAG,
                    "Proc state change of " + app.processName
                    + " to " + app.curProcState);
            boolean setImportant = app.setProcState < ActivityManager.PROCESS_STATE_SERVICE;
            boolean curImportant = app.curProcState < ActivityManager.PROCESS_STATE_SERVICE;
            if (setImportant && !curImportant) {
                // This app is no longer something we consider important enough to allow to
                // use arbitrary amounts of battery power.  Note
                // its current wake lock time to later know to kill it if
                // it is not behaving well.
                BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
                synchronized (stats) {
                    app.lastWakeTime = stats.getProcessWakeTime(app.info.uid,
                            app.pid, SystemClock.elapsedRealtime());
                }
                app.lastCpuTime = app.curCpuTime;

            }
            app.setProcState = app.curProcState;
            if (app.setProcState >= ActivityManager.PROCESS_STATE_HOME) {
                app.notCachedSinceIdle = false;
            }
            if (!doingAll) {
                setProcessTrackerStateLocked(app, mProcessStats.getMemFactorLocked(), now);
            } else {
                app.procStateChanged = true;
            }
        }

        if (changes != 0) {
            if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG, "Changes in " + app + ": " + changes);
            int i = mPendingProcessChanges.size()-1;
            ProcessChangeItem item = null;
            while (i >= 0) {
                item = mPendingProcessChanges.get(i);
                if (item.pid == app.pid) {
                    if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG, "Re-using existing item: " + item);
                    break;
                }
                i--;
            }
            if (i < 0) {
                // No existing item in pending changes; need a new one.
                final int NA = mAvailProcessChanges.size();
                if (NA > 0) {
                    item = mAvailProcessChanges.remove(NA-1);
                    if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG, "Retreiving available item: " + item);
                } else {
                    item = new ProcessChangeItem();
                    if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG, "Allocating new item: " + item);
                }
                item.changes = 0;
                item.pid = app.pid;
                item.uid = app.info.uid;
                if (mPendingProcessChanges.size() == 0) {
                    if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG,
                            "*** Enqueueing dispatch processes changed!");
                    mHandler.obtainMessage(DISPATCH_PROCESSES_CHANGED).sendToTarget();
                }
                mPendingProcessChanges.add(item);
            }
            item.changes |= changes;
            item.processState = app.repProcState;
            item.foregroundActivities = app.repForegroundActivities;
            if (DEBUG_PROCESS_OBSERVERS) Slog.i(TAG, "Item "
                    + Integer.toHexString(System.identityHashCode(item))
                    + " " + app.toShortString() + ": changes=" + item.changes
                    + " procState=" + item.processState
                    + " foreground=" + item.foregroundActivities
                    + " type=" + app.adjType + " source=" + app.adjSource
                    + " target=" + app.adjTarget);
        }

        return success;
    }

    private final void setProcessTrackerStateLocked(ProcessRecord proc, int memFactor, long now) {
        if (proc.thread != null) {
            if (proc.baseProcessTracker != null) {
                proc.baseProcessTracker.setState(proc.repProcState, memFactor, now, proc.pkgList);
            }
            if (proc.repProcState >= 0) {
                mBatteryStatsService.noteProcessState(proc.processName, proc.info.uid,
                        proc.repProcState);
            }
        }
    }

    private final boolean updateOomAdjLocked(ProcessRecord app, int cachedAdj,
            ProcessRecord TOP_APP, boolean doingAll, long now) {
        if (app.thread == null) {
            return false;
        }

        computeOomAdjLocked(app, cachedAdj, TOP_APP, doingAll, now);

        return applyOomAdjLocked(app, TOP_APP, doingAll, now);
    }

    final void updateProcessForegroundLocked(ProcessRecord proc, boolean isForeground,
            boolean oomAdj) {
        if (isForeground != proc.foregroundServices) {
            proc.foregroundServices = isForeground;
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
    }

    private final ActivityRecord resumedAppLocked() {
        ActivityRecord act = mStackSupervisor.resumedAppLocked();
        String pkg;
        int uid;
        if (act != null) {
            pkg = act.packageName;
            uid = act.info.applicationInfo.uid;
        } else {
            pkg = null;
            uid = -1;
        }
        // Has the UID or resumed package name changed?
        if (uid != mCurResumedUid || (pkg != mCurResumedPackage
                && (pkg == null || !pkg.equals(mCurResumedPackage)))) {
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
        }
        return act;
    }

    final boolean updateOomAdjLocked(ProcessRecord app) {
        final ActivityRecord TOP_ACT = resumedAppLocked();
        final ProcessRecord TOP_APP = TOP_ACT != null ? TOP_ACT.app : null;
        final boolean wasCached = app.cached;

        mAdjSeq++;

        // This is the desired cached adjusment we want to tell it to use.
        // If our app is currently cached, we know it, and that is it.  Otherwise,
        // we don't know it yet, and it needs to now be cached we will then
        // need to do a complete oom adj.
        final int cachedAdj = app.curRawAdj >= ProcessList.CACHED_APP_MIN_ADJ
                ? app.curRawAdj : ProcessList.UNKNOWN_ADJ;
        boolean success = updateOomAdjLocked(app, cachedAdj, TOP_APP, false,
                SystemClock.uptimeMillis());
        if (wasCached != app.cached || app.curRawAdj == ProcessList.UNKNOWN_ADJ) {
            // Changed to/from cached state, so apps after it in the LRU
            // list may also be changed.
            updateOomAdjLocked();
        }
        return success;
    }

    final void updateOomAdjLocked() {
        final ActivityRecord TOP_ACT = resumedAppLocked();
        final ProcessRecord TOP_APP = TOP_ACT != null ? TOP_ACT.app : null;
        final long now = SystemClock.uptimeMillis();
        final long oldTime = now - ProcessList.MAX_EMPTY_TIME;
        final int N = mLruProcesses.size();

        if (false) {
            RuntimeException e = new RuntimeException();
            e.fillInStackTrace();
            Slog.i(TAG, "updateOomAdj: top=" + TOP_ACT, e);
        }

        mAdjSeq++;
        mNewNumServiceProcs = 0;
        mNewNumAServiceProcs = 0;

        final int emptyProcessLimit;
        final int cachedProcessLimit;
        if (mProcessLimit <= 0) {
            emptyProcessLimit = cachedProcessLimit = 0;
        } else if (mProcessLimit == 1) {
            emptyProcessLimit = 1;
            cachedProcessLimit = 0;
        } else {
            emptyProcessLimit = ProcessList.computeEmptyProcessLimit(mProcessLimit);
            cachedProcessLimit = mProcessLimit - emptyProcessLimit;
        }

        // Let's determine how many processes we have running vs.
        // how many slots we have for background processes; we may want
        // to put multiple processes in a slot of there are enough of
        // them.
        int numSlots = (ProcessList.CACHED_APP_MAX_ADJ
                - ProcessList.CACHED_APP_MIN_ADJ + 1) / 2;
        int numEmptyProcs = N - mNumNonCachedProcs - mNumCachedHiddenProcs;
        if (numEmptyProcs > cachedProcessLimit) {
            // If there are more empty processes than our limit on cached
            // processes, then use the cached process limit for the factor.
            // This ensures that the really old empty processes get pushed
            // down to the bottom, so if we are running low on memory we will
            // have a better chance at keeping around more cached processes
            // instead of a gazillion empty processes.
            numEmptyProcs = cachedProcessLimit;
        }
        int emptyFactor = numEmptyProcs/numSlots;
        if (emptyFactor < 1) emptyFactor = 1;
        int cachedFactor = (mNumCachedHiddenProcs > 0 ? mNumCachedHiddenProcs : 1)/numSlots;
        if (cachedFactor < 1) cachedFactor = 1;
        int stepCached = 0;
        int stepEmpty = 0;
        int numCached = 0;
        int numEmpty = 0;
        int numTrimming = 0;

        mNumNonCachedProcs = 0;
        mNumCachedHiddenProcs = 0;

        // First update the OOM adjustment for each of the
        // application processes based on their current state.
        int curCachedAdj = ProcessList.CACHED_APP_MIN_ADJ;
        int nextCachedAdj = curCachedAdj+1;
        int curEmptyAdj = ProcessList.CACHED_APP_MIN_ADJ;
        int nextEmptyAdj = curEmptyAdj+2;
        for (int i=N-1; i>=0; i--) {
            ProcessRecord app = mLruProcesses.get(i);
            if (!app.killedByAm && app.thread != null) {
                app.procStateChanged = false;
                computeOomAdjLocked(app, ProcessList.UNKNOWN_ADJ, TOP_APP, true, now);

                // If we haven't yet assigned the final cached adj
                // to the process, do that now.
                if (app.curAdj >= ProcessList.UNKNOWN_ADJ) {
                    switch (app.curProcState) {
                        case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY:
                        case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
                            // This process is a cached process holding activities...
                            // assign it the next cached value for that type, and then
                            // step that cached level.
                            app.curRawAdj = curCachedAdj;
                            app.curAdj = app.modifyRawOomAdj(curCachedAdj);
                            if (DEBUG_LRU && false) Slog.d(TAG, "Assigning activity LRU #" + i
                                    + " adj: " + app.curAdj + " (curCachedAdj=" + curCachedAdj
                                    + ")");
                            if (curCachedAdj != nextCachedAdj) {
                                stepCached++;
                                if (stepCached >= cachedFactor) {
                                    stepCached = 0;
                                    curCachedAdj = nextCachedAdj;
                                    nextCachedAdj += 2;
                                    if (nextCachedAdj > ProcessList.CACHED_APP_MAX_ADJ) {
                                        nextCachedAdj = ProcessList.CACHED_APP_MAX_ADJ;
                                    }
                                }
                            }
                            break;
                        default:
                            // For everything else, assign next empty cached process
                            // level and bump that up.  Note that this means that
                            // long-running services that have dropped down to the
                            // cached level will be treated as empty (since their process
                            // state is still as a service), which is what we want.
                            app.curRawAdj = curEmptyAdj;
                            app.curAdj = app.modifyRawOomAdj(curEmptyAdj);
                            if (DEBUG_LRU && false) Slog.d(TAG, "Assigning empty LRU #" + i
                                    + " adj: " + app.curAdj + " (curEmptyAdj=" + curEmptyAdj
                                    + ")");
                            if (curEmptyAdj != nextEmptyAdj) {
                                stepEmpty++;
                                if (stepEmpty >= emptyFactor) {
                                    stepEmpty = 0;
                                    curEmptyAdj = nextEmptyAdj;
                                    nextEmptyAdj += 2;
                                    if (nextEmptyAdj > ProcessList.CACHED_APP_MAX_ADJ) {
                                        nextEmptyAdj = ProcessList.CACHED_APP_MAX_ADJ;
                                    }
                                }
                            }
                            break;
                    }
                }

                applyOomAdjLocked(app, TOP_APP, true, now);

                // Count the number of process types.
                switch (app.curProcState) {
                    case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY:
                    case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
                        mNumCachedHiddenProcs++;
                        numCached++;
                        if (numCached > cachedProcessLimit) {
                            killUnneededProcessLocked(app, "cached #" + numCached);
                        }
                        break;
                    case ActivityManager.PROCESS_STATE_CACHED_EMPTY:
                        if (numEmpty > ProcessList.TRIM_EMPTY_APPS
                                && app.lastActivityTime < oldTime) {
                            killUnneededProcessLocked(app, "empty for "
                                    + ((oldTime + ProcessList.MAX_EMPTY_TIME - app.lastActivityTime)
                                    / 1000) + "s");
                        } else {
                            numEmpty++;
                            if (numEmpty > emptyProcessLimit) {
                                killUnneededProcessLocked(app, "empty #" + numEmpty);
                            }
                        }
                        break;
                    default:
                        mNumNonCachedProcs++;
                        break;
                }

                if (app.isolated && app.services.size() <= 0) {
                    // If this is an isolated process, and there are no
                    // services running in it, then the process is no longer
                    // needed.  We agressively kill these because we can by
                    // definition not re-use the same process again, and it is
                    // good to avoid having whatever code was running in them
                    // left sitting around after no longer needed.
                    killUnneededProcessLocked(app, "isolated not needed");
                }

                if (app.curProcState >= ActivityManager.PROCESS_STATE_HOME
                        && !app.killedByAm) {
                    numTrimming++;
                }
            }
        }

        mNumServiceProcs = mNewNumServiceProcs;

        // Now determine the memory trimming level of background processes.
        // Unfortunately we need to start at the back of the list to do this
        // properly.  We only do this if the number of background apps we
        // are managing to keep around is less than half the maximum we desire;
        // if we are keeping a good number around, we'll let them use whatever
        // memory they want.
        final int numCachedAndEmpty = numCached + numEmpty;
        int memFactor;
        if (numCached <= ProcessList.TRIM_CACHED_APPS
                && numEmpty <= ProcessList.TRIM_EMPTY_APPS) {
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
        if (DEBUG_OOM_ADJ) Slog.d(TAG, "oom: memFactor=" + memFactor + " last=" + mLastMemoryLevel
                + " allowLow=" + mAllowLowerMemLevel + " numProcs=" + mLruProcesses.size()
                + " last=" + mLastNumProcesses);
        if (memFactor > mLastMemoryLevel) {
            if (!mAllowLowerMemLevel || mLruProcesses.size() >= mLastNumProcesses) {
                memFactor = mLastMemoryLevel;
                if (DEBUG_OOM_ADJ) Slog.d(TAG, "Keeping last mem factor!");
            }
        }
        mLastMemoryLevel = memFactor;
        mLastNumProcesses = mLruProcesses.size();
        boolean allChanged = mProcessStats.setMemFactorLocked(memFactor, !isSleeping(), now);
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
            if (mHomeProcess != null) minFactor++;
            if (mPreviousProcess != null) minFactor++;
            if (factor < minFactor) factor = minFactor;
            int curLevel = ComponentCallbacks2.TRIM_MEMORY_COMPLETE;
            for (int i=N-1; i>=0; i--) {
                ProcessRecord app = mLruProcesses.get(i);
                if (allChanged || app.procStateChanged) {
                    setProcessTrackerStateLocked(app, trackerMemFactor, now);
                    app.procStateChanged = false;
                }
                if (app.curProcState >= ActivityManager.PROCESS_STATE_HOME
                        && !app.killedByAm) {
                    if (app.trimMemoryLevel < curLevel && app.thread != null) {
                        try {
                            if (DEBUG_SWITCH || DEBUG_OOM_ADJ) Slog.v(TAG,
                                    "Trimming memory of " + app.processName
                                    + " to " + curLevel);
                            app.thread.scheduleTrimMemory(curLevel);
                        } catch (RemoteException e) {
                        }
                        if (false) {
                            // For now we won't do this; our memory trimming seems
                            // to be good enough at this point that destroying
                            // activities causes more harm than good.
                            if (curLevel >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
                                    && app != mHomeProcess && app != mPreviousProcess) {
                                // Need to do this on its own message because the stack may not
                                // be in a consistent state at this point.
                                // For these apps we will also finish their activities
                                // to help them free memory.
                                mStackSupervisor.scheduleDestroyAllActivities(app, "trim");
                            }
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
                } else if (app.curProcState == ActivityManager.PROCESS_STATE_HEAVY_WEIGHT) {
                    if (app.trimMemoryLevel < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                            && app.thread != null) {
                        try {
                            if (DEBUG_SWITCH || DEBUG_OOM_ADJ) Slog.v(TAG,
                                    "Trimming memory of heavy-weight " + app.processName
                                    + " to " + ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
                            app.thread.scheduleTrimMemory(
                                    ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
                        } catch (RemoteException e) {
                        }
                    }
                    app.trimMemoryLevel = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND;
                } else {
                    if ((app.curProcState >= ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
                            || app.systemNoUi) && app.pendingUiClean) {
                        // If this application is now in the background and it
                        // had done UI, then give it the special trim level to
                        // have it free UI resources.
                        final int level = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN;
                        if (app.trimMemoryLevel < level && app.thread != null) {
                            try {
                                if (DEBUG_SWITCH || DEBUG_OOM_ADJ) Slog.v(TAG,
                                        "Trimming memory of bg-ui " + app.processName
                                        + " to " + level);
                                app.thread.scheduleTrimMemory(level);
                            } catch (RemoteException e) {
                            }
                        }
                        app.pendingUiClean = false;
                    }
                    if (app.trimMemoryLevel < fgTrimLevel && app.thread != null) {
                        try {
                            if (DEBUG_SWITCH || DEBUG_OOM_ADJ) Slog.v(TAG,
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
                ProcessRecord app = mLruProcesses.get(i);
                if (allChanged || app.procStateChanged) {
                    setProcessTrackerStateLocked(app, trackerMemFactor, now);
                    app.procStateChanged = false;
                }
                if ((app.curProcState >= ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
                        || app.systemNoUi) && app.pendingUiClean) {
                    if (app.trimMemoryLevel < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                            && app.thread != null) {
                        try {
                            if (DEBUG_SWITCH || DEBUG_OOM_ADJ) Slog.v(TAG,
                                    "Trimming memory of ui hidden " + app.processName
                                    + " to " + ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
                            app.thread.scheduleTrimMemory(
                                    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
                        } catch (RemoteException e) {
                        }
                    }
                    app.pendingUiClean = false;
                }
                app.trimMemoryLevel = 0;
            }
        }

        if (mAlwaysFinishActivities) {
            // Need to do this on its own message because the stack may not
            // be in a consistent state at this point.
            mStackSupervisor.scheduleDestroyAllActivities(null, "always-finish");
        }

        if (allChanged) {
            requestPssAllProcsLocked(now, false, mProcessStats.isMemFactorLowered());
        }

        if (mProcessStats.shouldWriteNowLocked(now)) {
            mHandler.post(new Runnable() {
                @Override public void run() {
                    synchronized (ActivityManagerService.this) {
                        mProcessStats.writeStateAsyncLocked();
                    }
                }
            });
        }

        if (DEBUG_OOM_ADJ) {
            Slog.d(TAG, "Did OOM ADJ in " + (SystemClock.uptimeMillis()-now) + "ms");
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
                        EventLog.writeEvent(EventLogTags.AM_KILL, app.userId, app.pid,
                                app.processName, app.setAdj, "empty");
                        app.killedByAm = true;
                        Process.killProcessQuiet(app.pid);
                        Process.killProcessGroup(app.info.uid, app.pid);
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
                        addAppLocked(app.info, false, null /* ABI override */);
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

    private void stopProfilerLocked(ProcessRecord proc, String path, int profileType) {
        if (proc == null || proc == mProfileProc) {
            proc = mProfileProc;
            path = mProfileFile;
            profileType = mProfileType;
            clearProfilerLocked();
        }
        if (proc == null) {
            return;
        }
        try {
            proc.thread.profilerControl(false, path, null, profileType);
        } catch (RemoteException e) {
            throw new IllegalStateException("Process disappeared");
        }
    }

    private void clearProfilerLocked() {
        if (mProfileFd != null) {
            try {
                mProfileFd.close();
            } catch (IOException e) {
            }
        }
        mProfileApp = null;
        mProfileProc = null;
        mProfileFile = null;
        mProfileType = 0;
        mAutoStopProfiler = false;
    }

    public boolean profileControl(String process, int userId, boolean start,
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
                if (process != null) {
                    proc = findProcessLocked(process, userId, "profileControl");
                }

                if (start && (proc == null || proc.thread == null)) {
                    throw new IllegalArgumentException("Unknown process: " + process);
                }

                if (start) {
                    stopProfilerLocked(null, null, 0);
                    setProfileApp(proc.info, proc.processName, path, fd, false);
                    mProfileProc = proc;
                    mProfileType = profileType;
                    try {
                        fd = fd.dup();
                    } catch (IOException e) {
                        fd = null;
                    }
                    proc.thread.profilerControl(start, path, fd, profileType);
                    fd = null;
                    mProfileFd = null;
                } else {
                    stopProfilerLocked(proc, path, profileType);
                    if (fd != null) {
                        try {
                            fd.close();
                        } catch (IOException e) {
                        }
                    }
                }

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

    private ProcessRecord findProcessLocked(String process, int userId, String callName) {
        userId = handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
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
                    = mProcessNames.getMap();
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

    public boolean dumpHeap(String process, int userId, boolean managed,
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

    void onCoreSettingsChange(Bundle settings) {
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

    /**
     * Start user, if its not already running, but don't bring it to foreground.
     */
    @Override
    public boolean startUserInBackground(final int userId) {
        return startUser(userId, /* foreground */ false);
    }

    /**
     * Start user, if its not already running, and bring it to foreground.
     */
    boolean startUserInForeground(final int userId, Dialog dlg) {
        boolean result = startUser(userId, /* foreground */ true);
        dlg.dismiss();
        return result;
    }

    /**
     * Refreshes the list of users related to the current user when either a
     * user switch happens or when a new related user is started in the
     * background.
     */
    private void updateCurrentProfileIdsLocked() {
        final List<UserInfo> profiles = getUserManagerLocked().getProfiles(
                mCurrentUserId, false /* enabledOnly */);
        int[] currentProfileIds = new int[profiles.size()]; // profiles will not be null
        for (int i = 0; i < currentProfileIds.length; i++) {
            currentProfileIds[i] = profiles.get(i).id;
        }
        mCurrentProfileIds = currentProfileIds;

        synchronized (mUserProfileGroupIdsSelfLocked) {
            mUserProfileGroupIdsSelfLocked.clear();
            final List<UserInfo> users = getUserManagerLocked().getUsers(false);
            for (int i = 0; i < users.size(); i++) {
                UserInfo user = users.get(i);
                if (user.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID) {
                    mUserProfileGroupIdsSelfLocked.put(user.id, user.profileGroupId);
                }
            }
        }
    }

    private Set getProfileIdsLocked(int userId) {
        Set userIds = new HashSet<Integer>();
        final List<UserInfo> profiles = getUserManagerLocked().getProfiles(
                userId, false /* enabledOnly */);
        for (UserInfo user : profiles) {
            userIds.add(Integer.valueOf(user.id));
        }
        return userIds;
    }

    @Override
    public boolean switchUser(final int userId) {
        String userName;
        synchronized (this) {
            UserInfo userInfo = getUserManagerLocked().getUserInfo(userId);
            if (userInfo == null) {
                Slog.w(TAG, "No user info for user #" + userId);
                return false;
            }
            if (userInfo.isManagedProfile()) {
                Slog.w(TAG, "Cannot switch to User #" + userId + ": not a full user");
                return false;
            }
            userName = userInfo.name;
        }
        mHandler.removeMessages(START_USER_SWITCH_MSG);
        mHandler.sendMessage(mHandler.obtainMessage(START_USER_SWITCH_MSG, userId, 0, userName));
        return true;
    }

    private void showUserSwitchDialog(int userId, String userName) {
        // The dialog will show and then initiate the user switch by calling startUserInForeground
        Dialog d = new UserSwitchingDialog(this, mContext, userId, userName,
                true /* above system */);
        d.show();
    }

    private boolean startUser(final int userId, final boolean foreground) {
        if (checkCallingPermission(INTERACT_ACROSS_USERS_FULL)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: switchUser() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + INTERACT_ACROSS_USERS_FULL;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        if (DEBUG_MU) Slog.i(TAG_MU, "starting userid:" + userId + " fore:" + foreground);

        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                final int oldUserId = mCurrentUserId;
                if (oldUserId == userId) {
                    return true;
                }

                mStackSupervisor.setLockTaskModeLocked(null, false);

                final UserInfo userInfo = getUserManagerLocked().getUserInfo(userId);
                if (userInfo == null) {
                    Slog.w(TAG, "No user info for user #" + userId);
                    return false;
                }
                if (foreground && userInfo.isManagedProfile()) {
                    Slog.w(TAG, "Cannot switch to User #" + userId + ": not a full user");
                    return false;
                }

                if (foreground) {
                    mWindowManager.startFreezingScreen(R.anim.screen_user_exit,
                            R.anim.screen_user_enter);
                }

                boolean needStart = false;

                // If the user we are switching to is not currently started, then
                // we need to start it now.
                if (mStartedUsers.get(userId) == null) {
                    mStartedUsers.put(userId, new UserStartedState(new UserHandle(userId), false));
                    updateStartedUserArrayLocked();
                    needStart = true;
                }

                final Integer userIdInt = Integer.valueOf(userId);
                mUserLru.remove(userIdInt);
                mUserLru.add(userIdInt);

                if (foreground) {
                    mCurrentUserId = userId;
                    updateCurrentProfileIdsLocked();
                    mWindowManager.setCurrentUser(userId, mCurrentProfileIds);
                    // Once the internal notion of the active user has switched, we lock the device
                    // with the option to show the user switcher on the keyguard.
                    mWindowManager.lockNow(null);
                } else {
                    final Integer currentUserIdInt = Integer.valueOf(mCurrentUserId);
                    updateCurrentProfileIdsLocked();
                    mWindowManager.setCurrentProfileIds(mCurrentProfileIds);
                    mUserLru.remove(currentUserIdInt);
                    mUserLru.add(currentUserIdInt);
                }

                final UserStartedState uss = mStartedUsers.get(userId);

                // Make sure user is in the started state.  If it is currently
                // stopping, we need to knock that off.
                if (uss.mState == UserStartedState.STATE_STOPPING) {
                    // If we are stopping, we haven't sent ACTION_SHUTDOWN,
                    // so we can just fairly silently bring the user back from
                    // the almost-dead.
                    uss.mState = UserStartedState.STATE_RUNNING;
                    updateStartedUserArrayLocked();
                    needStart = true;
                } else if (uss.mState == UserStartedState.STATE_SHUTDOWN) {
                    // This means ACTION_SHUTDOWN has been sent, so we will
                    // need to treat this as a new boot of the user.
                    uss.mState = UserStartedState.STATE_BOOTING;
                    updateStartedUserArrayLocked();
                    needStart = true;
                }

                if (uss.mState == UserStartedState.STATE_BOOTING) {
                    // Booting up a new user, need to tell system services about it.
                    // Note that this is on the same handler as scheduling of broadcasts,
                    // which is important because it needs to go first.
                    mHandler.sendMessage(mHandler.obtainMessage(SYSTEM_USER_START_MSG, userId, 0));
                }

                if (foreground) {
                    mHandler.sendMessage(mHandler.obtainMessage(SYSTEM_USER_CURRENT_MSG, userId,
                            oldUserId));
                    mHandler.removeMessages(REPORT_USER_SWITCH_MSG);
                    mHandler.removeMessages(USER_SWITCH_TIMEOUT_MSG);
                    mHandler.sendMessage(mHandler.obtainMessage(REPORT_USER_SWITCH_MSG,
                            oldUserId, userId, uss));
                    mHandler.sendMessageDelayed(mHandler.obtainMessage(USER_SWITCH_TIMEOUT_MSG,
                            oldUserId, userId, uss), USER_SWITCH_TIMEOUT);
                }

                if (needStart) {
                    // Send USER_STARTED broadcast
                    Intent intent = new Intent(Intent.ACTION_USER_STARTED);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                            | Intent.FLAG_RECEIVER_FOREGROUND);
                    intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
                    broadcastIntentLocked(null, null, intent,
                            null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                            false, false, MY_PID, Process.SYSTEM_UID, userId);
                }

                if ((userInfo.flags&UserInfo.FLAG_INITIALIZED) == 0) {
                    if (userId != UserHandle.USER_OWNER) {
                        Intent intent = new Intent(Intent.ACTION_USER_INITIALIZE);
                        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                        broadcastIntentLocked(null, null, intent, null,
                                new IIntentReceiver.Stub() {
                                    public void performReceive(Intent intent, int resultCode,
                                            String data, Bundle extras, boolean ordered,
                                            boolean sticky, int sendingUser) {
                                        onUserInitialized(uss, foreground, oldUserId, userId);
                                    }
                                }, 0, null, null, null, AppOpsManager.OP_NONE,
                                true, false, MY_PID, Process.SYSTEM_UID,
                                userId);
                        uss.initializing = true;
                    } else {
                        getUserManagerLocked().makeInitialized(userInfo.id);
                    }
                }

                if (foreground) {
                    if (!uss.initializing) {
                        moveUserToForeground(uss, oldUserId, userId);
                    }
                } else {
                    mStackSupervisor.startBackgroundUserLocked(userId, uss);
                }

                if (needStart) {
                    Intent intent = new Intent(Intent.ACTION_USER_STARTING);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                    intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
                    broadcastIntentLocked(null, null, intent,
                            null, new IIntentReceiver.Stub() {
                                @Override
                                public void performReceive(Intent intent, int resultCode, String data,
                                        Bundle extras, boolean ordered, boolean sticky, int sendingUser)
                                        throws RemoteException {
                                }
                            }, 0, null, null,
                            INTERACT_ACROSS_USERS, AppOpsManager.OP_NONE,
                            true, false, MY_PID, Process.SYSTEM_UID, UserHandle.USER_ALL);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return true;
    }

    void sendUserSwitchBroadcastsLocked(int oldUserId, int newUserId) {
        long ident = Binder.clearCallingIdentity();
        try {
            Intent intent;
            if (oldUserId >= 0) {
                // Send USER_BACKGROUND broadcast to all profiles of the outgoing user
                List<UserInfo> profiles = mUserManager.getProfiles(oldUserId, false);
                int count = profiles.size();
                for (int i = 0; i < count; i++) {
                    int profileUserId = profiles.get(i).id;
                    intent = new Intent(Intent.ACTION_USER_BACKGROUND);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                            | Intent.FLAG_RECEIVER_FOREGROUND);
                    intent.putExtra(Intent.EXTRA_USER_HANDLE, profileUserId);
                    broadcastIntentLocked(null, null, intent,
                            null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                            false, false, MY_PID, Process.SYSTEM_UID, profileUserId);
                }
            }
            if (newUserId >= 0) {
                // Send USER_FOREGROUND broadcast to all profiles of the incoming user
                List<UserInfo> profiles = mUserManager.getProfiles(newUserId, false);
                int count = profiles.size();
                for (int i = 0; i < count; i++) {
                    int profileUserId = profiles.get(i).id;
                    intent = new Intent(Intent.ACTION_USER_FOREGROUND);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                            | Intent.FLAG_RECEIVER_FOREGROUND);
                    intent.putExtra(Intent.EXTRA_USER_HANDLE, profileUserId);
                    broadcastIntentLocked(null, null, intent,
                            null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                            false, false, MY_PID, Process.SYSTEM_UID, profileUserId);
                }
                intent = new Intent(Intent.ACTION_USER_SWITCHED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                        | Intent.FLAG_RECEIVER_FOREGROUND);
                intent.putExtra(Intent.EXTRA_USER_HANDLE, newUserId);
                broadcastIntentLocked(null, null, intent,
                        null, null, 0, null, null,
                        android.Manifest.permission.MANAGE_USERS, AppOpsManager.OP_NONE,
                        false, false, MY_PID, Process.SYSTEM_UID, UserHandle.USER_ALL);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    void dispatchUserSwitch(final UserStartedState uss, final int oldUserId,
            final int newUserId) {
        final int N = mUserSwitchObservers.beginBroadcast();
        if (N > 0) {
            final IRemoteCallback callback = new IRemoteCallback.Stub() {
                int mCount = 0;
                @Override
                public void sendResult(Bundle data) throws RemoteException {
                    synchronized (ActivityManagerService.this) {
                        if (mCurUserSwitchCallback == this) {
                            mCount++;
                            if (mCount == N) {
                                sendContinueUserSwitchLocked(uss, oldUserId, newUserId);
                            }
                        }
                    }
                }
            };
            synchronized (this) {
                uss.switching = true;
                mCurUserSwitchCallback = callback;
            }
            for (int i=0; i<N; i++) {
                try {
                    mUserSwitchObservers.getBroadcastItem(i).onUserSwitching(
                            newUserId, callback);
                } catch (RemoteException e) {
                }
            }
        } else {
            synchronized (this) {
                sendContinueUserSwitchLocked(uss, oldUserId, newUserId);
            }
        }
        mUserSwitchObservers.finishBroadcast();
    }

    void timeoutUserSwitch(UserStartedState uss, int oldUserId, int newUserId) {
        synchronized (this) {
            Slog.w(TAG, "User switch timeout: from " + oldUserId + " to " + newUserId);
            sendContinueUserSwitchLocked(uss, oldUserId, newUserId);
        }
    }

    void sendContinueUserSwitchLocked(UserStartedState uss, int oldUserId, int newUserId) {
        mCurUserSwitchCallback = null;
        mHandler.removeMessages(USER_SWITCH_TIMEOUT_MSG);
        mHandler.sendMessage(mHandler.obtainMessage(CONTINUE_USER_SWITCH_MSG,
                oldUserId, newUserId, uss));
    }

    void onUserInitialized(UserStartedState uss, boolean foreground, int oldUserId, int newUserId) {
        synchronized (this) {
            if (foreground) {
                moveUserToForeground(uss, oldUserId, newUserId);
            }
        }

        completeSwitchAndInitalize(uss, newUserId, true, false);
    }

    void moveUserToForeground(UserStartedState uss, int oldUserId, int newUserId) {
        boolean homeInFront = mStackSupervisor.switchUserLocked(newUserId, uss);
        if (homeInFront) {
            startHomeActivityLocked(newUserId);
        } else {
            mStackSupervisor.resumeTopActivitiesLocked();
        }
        EventLogTags.writeAmSwitchUser(newUserId);
        getUserManagerLocked().userForeground(newUserId);
        sendUserSwitchBroadcastsLocked(oldUserId, newUserId);
    }

    void continueUserSwitch(UserStartedState uss, int oldUserId, int newUserId) {
        completeSwitchAndInitalize(uss, newUserId, false, true);
    }

    void completeSwitchAndInitalize(UserStartedState uss, int newUserId,
            boolean clearInitializing, boolean clearSwitching) {
        boolean unfrozen = false;
        synchronized (this) {
            if (clearInitializing) {
                uss.initializing = false;
                getUserManagerLocked().makeInitialized(uss.mHandle.getIdentifier());
            }
            if (clearSwitching) {
                uss.switching = false;
            }
            if (!uss.switching && !uss.initializing) {
                mWindowManager.stopFreezingScreen();
                unfrozen = true;
            }
        }
        if (unfrozen) {
            final int N = mUserSwitchObservers.beginBroadcast();
            for (int i=0; i<N; i++) {
                try {
                    mUserSwitchObservers.getBroadcastItem(i).onUserSwitchComplete(newUserId);
                } catch (RemoteException e) {
                }
            }
            mUserSwitchObservers.finishBroadcast();
        }
    }

    void scheduleStartProfilesLocked() {
        if (!mHandler.hasMessages(START_PROFILES_MSG)) {
            mHandler.sendMessageDelayed(mHandler.obtainMessage(START_PROFILES_MSG),
                    DateUtils.SECOND_IN_MILLIS);
        }
    }

    void startProfilesLocked() {
        if (DEBUG_MU) Slog.i(TAG_MU, "startProfilesLocked");
        List<UserInfo> profiles = getUserManagerLocked().getProfiles(
                mCurrentUserId, false /* enabledOnly */);
        List<UserInfo> toStart = new ArrayList<UserInfo>(profiles.size());
        for (UserInfo user : profiles) {
            if ((user.flags & UserInfo.FLAG_INITIALIZED) == UserInfo.FLAG_INITIALIZED
                    && user.id != mCurrentUserId) {
                toStart.add(user);
            }
        }
        final int n = toStart.size();
        int i = 0;
        for (; i < n && i < (MAX_RUNNING_USERS - 1); ++i) {
            startUserInBackground(toStart.get(i).id);
        }
        if (i < n) {
            Slog.w(TAG_MU, "More profiles than MAX_RUNNING_USERS");
        }
    }

    void finishUserBoot(UserStartedState uss) {
        synchronized (this) {
            if (uss.mState == UserStartedState.STATE_BOOTING
                    && mStartedUsers.get(uss.mHandle.getIdentifier()) == uss) {
                uss.mState = UserStartedState.STATE_RUNNING;
                final int userId = uss.mHandle.getIdentifier();
                Intent intent = new Intent(Intent.ACTION_BOOT_COMPLETED, null);
                intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
                intent.addFlags(Intent.FLAG_RECEIVER_NO_ABORT);
                broadcastIntentLocked(null, null, intent,
                        null, null, 0, null, null,
                        android.Manifest.permission.RECEIVE_BOOT_COMPLETED, AppOpsManager.OP_NONE,
                        true, false, MY_PID, Process.SYSTEM_UID, userId);
            }
        }
    }

    void finishUserSwitch(UserStartedState uss) {
        synchronized (this) {
            finishUserBoot(uss);

            startProfilesLocked();

            int num = mUserLru.size();
            int i = 0;
            while (num > MAX_RUNNING_USERS && i < mUserLru.size()) {
                Integer oldUserId = mUserLru.get(i);
                UserStartedState oldUss = mStartedUsers.get(oldUserId);
                if (oldUss == null) {
                    // Shouldn't happen, but be sane if it does.
                    mUserLru.remove(i);
                    num--;
                    continue;
                }
                if (oldUss.mState == UserStartedState.STATE_STOPPING
                        || oldUss.mState == UserStartedState.STATE_SHUTDOWN) {
                    // This user is already stopping, doesn't count.
                    num--;
                    i++;
                    continue;
                }
                if (oldUserId == UserHandle.USER_OWNER || oldUserId == mCurrentUserId) {
                    // Owner and current can't be stopped, but count as running.
                    i++;
                    continue;
                }
                // This is a user to be stopped.
                stopUserLocked(oldUserId, null);
                num--;
                i++;
            }
        }
    }

    @Override
    public int stopUser(final int userId, final IStopUserCallback callback) {
        if (checkCallingPermission(INTERACT_ACROSS_USERS_FULL)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: switchUser() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + INTERACT_ACROSS_USERS_FULL;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("Can't stop primary user " + userId);
        }
        synchronized (this) {
            return stopUserLocked(userId, callback);
        }
    }

    private int stopUserLocked(final int userId, final IStopUserCallback callback) {
        if (DEBUG_MU) Slog.i(TAG_MU, "stopUserLocked userId=" + userId);
        if (mCurrentUserId == userId) {
            return ActivityManager.USER_OP_IS_CURRENT;
        }

        final UserStartedState uss = mStartedUsers.get(userId);
        if (uss == null) {
            // User is not started, nothing to do...  but we do need to
            // callback if requested.
            if (callback != null) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            callback.userStopped(userId);
                        } catch (RemoteException e) {
                        }
                    }
                });
            }
            return ActivityManager.USER_OP_SUCCESS;
        }

        if (callback != null) {
            uss.mStopCallbacks.add(callback);
        }

        if (uss.mState != UserStartedState.STATE_STOPPING
                && uss.mState != UserStartedState.STATE_SHUTDOWN) {
            uss.mState = UserStartedState.STATE_STOPPING;
            updateStartedUserArrayLocked();

            long ident = Binder.clearCallingIdentity();
            try {
                // We are going to broadcast ACTION_USER_STOPPING and then
                // once that is done send a final ACTION_SHUTDOWN and then
                // stop the user.
                final Intent stoppingIntent = new Intent(Intent.ACTION_USER_STOPPING);
                stoppingIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                stoppingIntent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
                stoppingIntent.putExtra(Intent.EXTRA_SHUTDOWN_USERSPACE_ONLY, true);
                final Intent shutdownIntent = new Intent(Intent.ACTION_SHUTDOWN);
                // This is the result receiver for the final shutdown broadcast.
                final IIntentReceiver shutdownReceiver = new IIntentReceiver.Stub() {
                    @Override
                    public void performReceive(Intent intent, int resultCode, String data,
                            Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
                        finishUserStop(uss);
                    }
                };
                // This is the result receiver for the initial stopping broadcast.
                final IIntentReceiver stoppingReceiver = new IIntentReceiver.Stub() {
                    @Override
                    public void performReceive(Intent intent, int resultCode, String data,
                            Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
                        // On to the next.
                        synchronized (ActivityManagerService.this) {
                            if (uss.mState != UserStartedState.STATE_STOPPING) {
                                // Whoops, we are being started back up.  Abort, abort!
                                return;
                            }
                            uss.mState = UserStartedState.STATE_SHUTDOWN;
                        }
                        mBatteryStatsService.noteEvent(
                                BatteryStats.HistoryItem.EVENT_USER_RUNNING_FINISH,
                                Integer.toString(userId), userId);
                        mSystemServiceManager.stopUser(userId);
                        broadcastIntentLocked(null, null, shutdownIntent,
                                null, shutdownReceiver, 0, null, null, null, AppOpsManager.OP_NONE,
                                true, false, MY_PID, Process.SYSTEM_UID, userId);
                    }
                };
                // Kick things off.
                broadcastIntentLocked(null, null, stoppingIntent,
                        null, stoppingReceiver, 0, null, null,
                        INTERACT_ACROSS_USERS, AppOpsManager.OP_NONE,
                        true, false, MY_PID, Process.SYSTEM_UID, UserHandle.USER_ALL);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        return ActivityManager.USER_OP_SUCCESS;
    }

    void finishUserStop(UserStartedState uss) {
        final int userId = uss.mHandle.getIdentifier();
        boolean stopped;
        ArrayList<IStopUserCallback> callbacks;
        synchronized (this) {
            callbacks = new ArrayList<IStopUserCallback>(uss.mStopCallbacks);
            if (mStartedUsers.get(userId) != uss) {
                stopped = false;
            } else if (uss.mState != UserStartedState.STATE_SHUTDOWN) {
                stopped = false;
            } else {
                stopped = true;
                // User can no longer run.
                mStartedUsers.remove(userId);
                mUserLru.remove(Integer.valueOf(userId));
                updateStartedUserArrayLocked();

                // Clean up all state and processes associated with the user.
                // Kill all the processes for the user.
                forceStopUserLocked(userId, "finish user");
            }

            // Explicitly remove the old information in mRecentTasks.
            removeRecentTasksForUserLocked(userId);
        }

        for (int i=0; i<callbacks.size(); i++) {
            try {
                if (stopped) callbacks.get(i).userStopped(userId);
                else callbacks.get(i).userStopAborted(userId);
            } catch (RemoteException e) {
            }
        }

        if (stopped) {
            mSystemServiceManager.cleanupUser(userId);
            synchronized (this) {
                mStackSupervisor.removeUserLocked(userId);
            }
        }
    }

    @Override
    public UserInfo getCurrentUser() {
        if ((checkCallingPermission(INTERACT_ACROSS_USERS)
                != PackageManager.PERMISSION_GRANTED) && (
                checkCallingPermission(INTERACT_ACROSS_USERS_FULL)
                != PackageManager.PERMISSION_GRANTED)) {
            String msg = "Permission Denial: getCurrentUser() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + INTERACT_ACROSS_USERS;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        synchronized (this) {
            return getUserManagerLocked().getUserInfo(mCurrentUserId);
        }
    }

    int getCurrentUserIdLocked() {
        return mCurrentUserId;
    }

    @Override
    public boolean isUserRunning(int userId, boolean orStopped) {
        if (checkCallingPermission(INTERACT_ACROSS_USERS)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: isUserRunning() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + INTERACT_ACROSS_USERS;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        synchronized (this) {
            return isUserRunningLocked(userId, orStopped);
        }
    }

    boolean isUserRunningLocked(int userId, boolean orStopped) {
        UserStartedState state = mStartedUsers.get(userId);
        if (state == null) {
            return false;
        }
        if (orStopped) {
            return true;
        }
        return state.mState != UserStartedState.STATE_STOPPING
                && state.mState != UserStartedState.STATE_SHUTDOWN;
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
        synchronized (this) {
            return mStartedUserArray;
        }
    }

    private void updateStartedUserArrayLocked() {
        int num = 0;
        for (int i=0; i<mStartedUsers.size();  i++) {
            UserStartedState uss = mStartedUsers.valueAt(i);
            // This list does not include stopping users.
            if (uss.mState != UserStartedState.STATE_STOPPING
                    && uss.mState != UserStartedState.STATE_SHUTDOWN) {
                num++;
            }
        }
        mStartedUserArray = new int[num];
        num = 0;
        for (int i=0; i<mStartedUsers.size();  i++) {
            UserStartedState uss = mStartedUsers.valueAt(i);
            if (uss.mState != UserStartedState.STATE_STOPPING
                    && uss.mState != UserStartedState.STATE_SHUTDOWN) {
                mStartedUserArray[num] = mStartedUsers.keyAt(i);
                num++;
            }
        }
    }

    @Override
    public void registerUserSwitchObserver(IUserSwitchObserver observer) {
        if (checkCallingPermission(INTERACT_ACROSS_USERS_FULL)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: registerUserSwitchObserver() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + INTERACT_ACROSS_USERS_FULL;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        mUserSwitchObservers.register(observer);
    }

    @Override
    public void unregisterUserSwitchObserver(IUserSwitchObserver observer) {
        mUserSwitchObservers.unregister(observer);
    }

    private boolean userExists(int userId) {
        if (userId == 0) {
            return true;
        }
        UserManagerService ums = getUserManagerLocked();
        return ums != null ? (ums.getUserInfo(userId) != null) : false;
    }

    int[] getUsersLocked() {
        UserManagerService ums = getUserManagerLocked();
        return ums != null ? ums.getUserIds() : new int[] { 0 };
    }

    UserManagerService getUserManagerLocked() {
        if (mUserManager == null) {
            IBinder b = ServiceManager.getService(Context.USER_SERVICE);
            mUserManager = (UserManagerService)IUserManager.Stub.asInterface(b);
        }
        return mUserManager;
    }

    private int applyUserId(int uid, int userId) {
        return UserHandle.getUid(userId, uid);
    }

    ApplicationInfo getAppInfoForUser(ApplicationInfo info, int userId) {
        if (info == null) return null;
        ApplicationInfo newInfo = new ApplicationInfo(info);
        newInfo.uid = applyUserId(info.uid, userId);
        newInfo.dataDir = USER_DATA_DIR + userId + "/"
                + info.packageName;
        return newInfo;
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

    private final class LocalService extends ActivityManagerInternal {
        @Override
        public void goingToSleep() {
            ActivityManagerService.this.goingToSleep();
        }

        @Override
        public void wakingUp() {
            ActivityManagerService.this.wakingUp();
        }

        @Override
        public int startIsolatedProcess(String entryPoint, String[] entryPointArgs,
                String processName, String abiOverride, int uid, Runnable crashHandler) {
            return ActivityManagerService.this.startIsolatedProcess(entryPoint, entryPointArgs,
                    processName, abiOverride, uid, crashHandler);
        }
    }

    /**
     * An implementation of IAppTask, that allows an app to manage its own tasks via
     * {@link android.app.ActivityManager.AppTask}.  We keep track of the callingUid to ensure that
     * only the process that calls getAppTasks() can call the AppTask methods.
     */
    class AppTaskImpl extends IAppTask.Stub {
        private int mTaskId;
        private int mCallingUid;

        public AppTaskImpl(int taskId, int callingUid) {
            mTaskId = taskId;
            mCallingUid = callingUid;
        }

        private void checkCaller() {
            if (mCallingUid != Binder.getCallingUid()) {
                throw new SecurityException("Caller " + mCallingUid
                        + " does not match caller of getAppTasks(): " + Binder.getCallingUid());
            }
        }

        @Override
        public void finishAndRemoveTask() {
            checkCaller();

            synchronized (ActivityManagerService.this) {
                long origId = Binder.clearCallingIdentity();
                try {
                    TaskRecord tr = recentTaskForIdLocked(mTaskId);
                    if (tr != null) {
                        // Only kill the process if we are not a new document
                        int flags = tr.getBaseIntent().getFlags();
                        boolean isDocument = (flags & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) ==
                                Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
                        removeTaskByIdLocked(mTaskId,
                                !isDocument ? ActivityManager.REMOVE_TASK_KILL_PROCESS : 0);
                    }
                } finally {
                    Binder.restoreCallingIdentity(origId);
                }
            }
        }

        @Override
        public ActivityManager.RecentTaskInfo getTaskInfo() {
            checkCaller();

            synchronized (ActivityManagerService.this) {
                long origId = Binder.clearCallingIdentity();
                try {
                    TaskRecord tr = recentTaskForIdLocked(mTaskId);
                    if (tr != null) {
                        return createRecentTaskInfoFromTaskRecord(tr);
                    }
                } finally {
                    Binder.restoreCallingIdentity(origId);
                }
                return null;
            }
        }

        @Override
        public void setExcludeFromRecents(boolean exclude) {
            checkCaller();

            synchronized (ActivityManagerService.this) {
                long origId = Binder.clearCallingIdentity();
                try {
                    TaskRecord tr = recentTaskForIdLocked(mTaskId);
                    if (tr != null) {
                        Intent intent = tr.getBaseIntent();
                        if (exclude) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                        } else {
                            intent.setFlags(intent.getFlags()
                                    & ~Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(origId);
                }
            }
        }
    }
}
