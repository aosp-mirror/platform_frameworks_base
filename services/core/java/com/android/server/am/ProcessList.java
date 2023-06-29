/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static android.app.ActivityManager.PROCESS_CAPABILITY_NONE;
import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_PROCESS_END;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_RESTRICTION_CHANGE;
import static android.app.ActivityThread.PROC_START_SEQ_IDENT;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AUTO;
import static android.net.NetworkPolicyManager.isProcStateAllowedWhileIdleOrPowerSaveMode;
import static android.net.NetworkPolicyManager.isProcStateAllowedWhileOnRestrictBackground;
import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT;
import static android.os.Process.SYSTEM_UID;
import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
import static android.os.Process.ZYGOTE_POLICY_FLAG_EMPTY;
import static android.os.Process.getAdvertisedMem;
import static android.os.Process.getFreeMemory;
import static android.os.Process.getTotalMemory;
import static android.os.Process.killProcessQuiet;
import static android.os.Process.startWebView;
import static android.system.OsConstants.*;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_LRU;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_NETWORK;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PROCESSES;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PROCESS_OBSERVERS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_PROCESS_OBSERVERS;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityManagerService.DISPATCH_PROCESSES_CHANGED_UI_MSG;
import static com.android.server.am.ActivityManagerService.DISPATCH_PROCESS_DIED_UI_MSG;
import static com.android.server.am.ActivityManagerService.IDLE_UIDS_MSG;
import static com.android.server.am.ActivityManagerService.KILL_APP_ZYGOTE_DELAY_MS;
import static com.android.server.am.ActivityManagerService.KILL_APP_ZYGOTE_MSG;
import static com.android.server.am.ActivityManagerService.PERSISTENT_MASK;
import static com.android.server.am.ActivityManagerService.PROC_START_TIMEOUT;
import static com.android.server.am.ActivityManagerService.PROC_START_TIMEOUT_MSG;
import static com.android.server.am.ActivityManagerService.PROC_START_TIMEOUT_WITH_WRAPPER;
import static com.android.server.am.ActivityManagerService.STOCK_PM_FLAGS;
import static com.android.server.am.ActivityManagerService.TAG_LRU;
import static com.android.server.am.ActivityManagerService.TAG_NETWORK;
import static com.android.server.am.ActivityManagerService.TAG_PROCESSES;
import static com.android.server.am.ActivityManagerService.TAG_UID_OBSERVERS;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.ProcessCapability;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.AppProtoEnums;
import android.app.ApplicationExitInfo;
import android.app.ApplicationExitInfo.Reason;
import android.app.ApplicationExitInfo.SubReason;
import android.app.IApplicationThread;
import android.app.IProcessObserver;
import android.app.UidObserver;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.res.Resources;
import android.graphics.Point;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.AppZygote;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.DropBoxManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.OomKillRecord;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.storage.StorageManagerInternal;
import android.provider.DeviceConfig;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.EventLog;
import android.util.LongSparseArray;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.view.Display;

import com.android.internal.annotations.CompositeRWLock;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ProcessMap;
import com.android.internal.os.Zygote;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.MemInfoReader;
import com.android.server.AppStateTracker;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemConfig;
import com.android.server.Watchdog;
import com.android.server.am.ActivityManagerService.ProcessChangeItem;
import com.android.server.compat.PlatformCompat;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.wm.ActivityServiceConnectionsHolder;
import com.android.server.wm.WindowManagerService;
import com.android.server.wm.WindowProcessController;

import dalvik.system.VMRuntime;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Activity manager code dealing with processes.
 */
public final class ProcessList {
    static final String TAG = TAG_WITH_CLASS_NAME ? "ProcessList" : TAG_AM;

    static final String TAG_PROCESS_OBSERVERS = TAG + POSTFIX_PROCESS_OBSERVERS;

    // A system property to control if app data isolation is enabled.
    static final String ANDROID_APP_DATA_ISOLATION_ENABLED_PROPERTY =
            "persist.zygote.app_data_isolation";

    // A system property to control if obb app data isolation is enabled in vold.
    static final String ANDROID_VOLD_APP_DATA_ISOLATION_ENABLED_PROPERTY =
            "persist.sys.vold_app_data_isolation_enabled";

    private static final String APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS = ":isSdkSandboxNext";

    // OOM adjustments for processes in various states:

    // Uninitialized value for any major or minor adj fields
    public static final int INVALID_ADJ = -10000;

    // Adjustment used in certain places where we don't know it yet.
    // (Generally this is something that is going to be cached, but we
    // don't know the exact value in the cached range to assign yet.)
    public static final int UNKNOWN_ADJ = 1001;

    // This is a process only hosting activities that are not visible,
    // so it can be killed without any disruption.
    public static final int CACHED_APP_MAX_ADJ = 999;
    public static final int CACHED_APP_MIN_ADJ = 900;

    // This is the oom_adj level that we allow to die first. This cannot be equal to
    // CACHED_APP_MAX_ADJ unless processes are actively being assigned an oom_score_adj of
    // CACHED_APP_MAX_ADJ.
    public static final int CACHED_APP_LMK_FIRST_ADJ = 950;

    // Number of levels we have available for different service connection group importance
    // levels.
    static final int CACHED_APP_IMPORTANCE_LEVELS = 5;

    // The B list of SERVICE_ADJ -- these are the old and decrepit
    // services that aren't as shiny and interesting as the ones in the A list.
    public static final int SERVICE_B_ADJ = 800;

    // This is the process of the previous application that the user was in.
    // This process is kept above other things, because it is very common to
    // switch back to the previous app.  This is important both for recent
    // task switch (toggling between the two top recent apps) as well as normal
    // UI flow such as clicking on a URI in the e-mail app to view in the browser,
    // and then pressing back to return to e-mail.
    public static final int PREVIOUS_APP_ADJ = 700;

    // This is a process holding the home application -- we want to try
    // avoiding killing it, even if it would normally be in the background,
    // because the user interacts with it so much.
    public static final int HOME_APP_ADJ = 600;

    // This is a process holding an application service -- killing it will not
    // have much of an impact as far as the user is concerned.
    public static final int SERVICE_ADJ = 500;

    // This is a process with a heavy-weight application.  It is in the
    // background, but we want to try to avoid killing it.  Value set in
    // system/rootdir/init.rc on startup.
    public static final int HEAVY_WEIGHT_APP_ADJ = 400;

    // This is a process currently hosting a backup operation.  Killing it
    // is not entirely fatal but is generally a bad idea.
    public static final int BACKUP_APP_ADJ = 300;

    // This is a process bound by the system (or other app) that's more important than services but
    // not so perceptible that it affects the user immediately if killed.
    public static final int PERCEPTIBLE_LOW_APP_ADJ = 250;

    // This is a process hosting services that are not perceptible to the user but the
    // client (system) binding to it requested to treat it as if it is perceptible and avoid killing
    // it if possible.
    public static final int PERCEPTIBLE_MEDIUM_APP_ADJ = 225;

    // This is a process only hosting components that are perceptible to the
    // user, and we really want to avoid killing them, but they are not
    // immediately visible. An example is background music playback.
    public static final int PERCEPTIBLE_APP_ADJ = 200;

    // This is a process only hosting activities that are visible to the
    // user, so we'd prefer they don't disappear.
    public static final int VISIBLE_APP_ADJ = 100;
    static final int VISIBLE_APP_LAYER_MAX = PERCEPTIBLE_APP_ADJ - VISIBLE_APP_ADJ - 1;

    // This is a process that was recently TOP and moved to FGS. Continue to treat it almost
    // like a foreground app for a while.
    // @see TOP_TO_FGS_GRACE_PERIOD
    public static final int PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ = 50;

    // This is the process running the current foreground app.  We'd really
    // rather not kill it!
    public static final int FOREGROUND_APP_ADJ = 0;

    // This is a process that the system or a persistent process has bound to,
    // and indicated it is important.
    public static final int PERSISTENT_SERVICE_ADJ = -700;

    // This is a system persistent process, such as telephony.  Definitely
    // don't want to kill it, but doing so is not completely fatal.
    public static final int PERSISTENT_PROC_ADJ = -800;

    // The system process runs at the default adjustment.
    public static final int SYSTEM_ADJ = -900;

    // Special code for native processes that are not being managed by the system (so
    // don't have an oom adj assigned by the system).
    public static final int NATIVE_ADJ = -1000;

    // Memory page size.
    static final int PAGE_SIZE = (int) Os.sysconf(OsConstants._SC_PAGESIZE);

    // Activity manager's version of an undefined schedule group
    static final int SCHED_GROUP_UNDEFINED = Integer.MIN_VALUE;
    // Activity manager's version of Process.THREAD_GROUP_BACKGROUND
    static final int SCHED_GROUP_BACKGROUND = 0;
      // Activity manager's version of Process.THREAD_GROUP_RESTRICTED
    static final int SCHED_GROUP_RESTRICTED = 1;
    // Activity manager's version of Process.THREAD_GROUP_DEFAULT
    static final int SCHED_GROUP_DEFAULT = 2;
    // Activity manager's version of Process.THREAD_GROUP_TOP_APP
    public static final int SCHED_GROUP_TOP_APP = 3;
    // Activity manager's version of Process.THREAD_GROUP_TOP_APP
    // Disambiguate between actual top app and processes bound to the top app
    static final int SCHED_GROUP_TOP_APP_BOUND = 4;

    // The minimum number of cached apps we want to be able to keep around,
    // without empty apps being able to push them out of memory.
    static final int MIN_CACHED_APPS = 2;

    // Threshold of number of cached+empty where we consider memory critical.
    static final int TRIM_CRITICAL_THRESHOLD = 3;

    // Threshold of number of cached+empty where we consider memory critical.
    static final int TRIM_LOW_THRESHOLD = 5;

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

    // If true, then we pass the flag to ART to load the app image startup cache.
    private static final String PROPERTY_USE_APP_IMAGE_STARTUP_CACHE =
            "persist.device_config.runtime_native.use_app_image_startup_cache";

    // The socket path for zygote to send unsolicited msg.
    // Must keep sync with com_android_internal_os_Zygote.cpp.
    private static final String UNSOL_ZYGOTE_MSG_SOCKET_PATH = "/data/system/unsolzygotesocket";

    // Low Memory Killer Daemon command codes.
    // These must be kept in sync with lmk_cmd definitions in lmkd.h
    //
    // LMK_TARGET <minfree> <minkillprio> ... (up to 6 pairs)
    // LMK_PROCPRIO <pid> <uid> <prio>
    // LMK_PROCREMOVE <pid>
    // LMK_PROCPURGE
    // LMK_GETKILLCNT
    // LMK_SUBSCRIBE
    // LMK_PROCKILL
    // LMK_UPDATE_PROPS
    // LMK_KILL_OCCURRED
    // LMK_STATE_CHANGED
    static final byte LMK_TARGET = 0;
    static final byte LMK_PROCPRIO = 1;
    static final byte LMK_PROCREMOVE = 2;
    static final byte LMK_PROCPURGE = 3;
    static final byte LMK_GETKILLCNT = 4;
    static final byte LMK_SUBSCRIBE = 5;
    static final byte LMK_PROCKILL = 6; // Note: this is an unsolicited command
    static final byte LMK_UPDATE_PROPS = 7;
    static final byte LMK_KILL_OCCURRED = 8; // Msg to subscribed clients on kill occurred event
    static final byte LMK_STATE_CHANGED = 9; // Msg to subscribed clients on state changed
    static final byte LMK_START_MONITORING = 9; // Start monitoring if delayed earlier

    // Low Memory Killer Daemon command codes.
    // These must be kept in sync with async_event_type definitions in lmkd.h
    //
    static final int LMK_ASYNC_EVENT_KILL = 0;
    static final int LMK_ASYNC_EVENT_STAT = 1;

    // lmkd reconnect delay in msecs
    private static final long LMKD_RECONNECT_DELAY_MS = 1000;

    /**
     * Apps have no access to the private data directories of any other app, even if the other
     * app has made them world-readable.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.Q)
    private static final long APP_DATA_DIRECTORY_ISOLATION = 143937733; // See b/143937733

    ActivityManagerService mService = null;

    // To kill process groups asynchronously
    static KillHandler sKillHandler = null;
    static ServiceThread sKillThread = null;

    // These are the various interesting memory levels that we will give to
    // the OOM killer.  Note that the OOM killer only supports 6 slots, so we
    // can't give it a different value for every possible kind of process.
    private final int[] mOomAdj = new int[] {
            FOREGROUND_APP_ADJ, VISIBLE_APP_ADJ, PERCEPTIBLE_APP_ADJ,
            PERCEPTIBLE_LOW_APP_ADJ, CACHED_APP_MIN_ADJ, CACHED_APP_LMK_FIRST_ADJ
    };
    // These are the low-end OOM level limits.  This is appropriate for an
    // HVGA or smaller phone with less than 512MB.  Values are in KB.
    private final int[] mOomMinFreeLow = new int[] {
            12288, 18432, 24576,
            36864, 43008, 49152
    };
    // These are the high-end OOM level limits.  This is appropriate for a
    // 1280x800 or larger screen with around 1GB RAM.  Values are in KB.
    private final int[] mOomMinFreeHigh = new int[] {
            73728, 92160, 110592,
            129024, 147456, 184320
    };
    // The actual OOM killer memory levels we are using.
    private final int[] mOomMinFree = new int[mOomAdj.length];

    private final long mTotalMemMb;

    private long mCachedRestoreLevel;

    private boolean mHaveDisplaySize;

    private static LmkdConnection sLmkdConnection = null;

    private static OomConnection sOomConnection = null;

    private boolean mOomLevelsSet = false;

    private boolean mAppDataIsolationEnabled = false;

    private boolean mVoldAppDataIsolationEnabled = false;

    private ArrayList<String> mAppDataIsolationAllowlistedApps;

    /**
     * Temporary to avoid allocations.  Protected by main lock.
     */
    @GuardedBy("mService")
    final StringBuilder mStringBuilder = new StringBuilder(256);

    /**
     * A global counter for generating sequence numbers.
     * This value will be used when incrementing sequence numbers in individual uidRecords.
     *
     * Having a global counter ensures that seq numbers are monotonically increasing for a
     * particular uid even when the uidRecord is re-created.
     */
    @VisibleForTesting
    volatile long mProcStateSeqCounter = 0;

    /**
     * A global counter for generating sequence numbers to uniquely identify pending process starts.
     */
    @GuardedBy("mService")
    private long mProcStartSeqCounter = 0;

    /**
     * Contains {@link ProcessRecord} objects for pending process starts.
     *
     * Mapping: {@link #mProcStartSeqCounter} -> {@link ProcessRecord}
     */
    @GuardedBy("mService")
    final LongSparseArray<ProcessRecord> mPendingStarts = new LongSparseArray<>();

    /**
     * List of running applications, sorted by recent usage.
     * The first entry in the list is the least recently used.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private final ArrayList<ProcessRecord> mLruProcesses = new ArrayList<ProcessRecord>();

    /**
     * Where in mLruProcesses that the processes hosting activities start.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private int mLruProcessActivityStart = 0;

    /**
     * Where in mLruProcesses that the processes hosting services start.
     * This is after (lower index) than mLruProcessesActivityStart.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private int mLruProcessServiceStart = 0;

    /**
     * Current sequence id for process LRU updating.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private int mLruSeq = 0;

    @CompositeRWLock({"mService", "mProcLock"})
    ActiveUids mActiveUids;

    /**
     * The currently running isolated processes.
     */
    @GuardedBy("mService")
    final SparseArray<ProcessRecord> mIsolatedProcesses = new SparseArray<>();

    /**
     * The currently running application zygotes.
     */
    @GuardedBy("mService")
    final ProcessMap<AppZygote> mAppZygotes = new ProcessMap<AppZygote>();

    /**
     * The currently running SDK sandbox processes for a uid.
     */
    @GuardedBy("mService")
    final SparseArray<ArrayList<ProcessRecord>> mSdkSandboxes = new SparseArray<>();

    /**
     * Managees the {@link android.app.ApplicationExitInfo} records.
     */
    @GuardedBy("mAppExitInfoTracker")
    final AppExitInfoTracker mAppExitInfoTracker = new AppExitInfoTracker();

    /**
     * The processes that are forked off an application zygote.
     */
    @GuardedBy("mService")
    final ArrayMap<AppZygote, ArrayList<ProcessRecord>> mAppZygoteProcesses =
            new ArrayMap<AppZygote, ArrayList<ProcessRecord>>();

    /**
     * The list of apps in background restricted mode.
     */
    @GuardedBy("mService")
    final ArraySet<ProcessRecord> mAppsInBackgroundRestricted = new ArraySet<>();

    private PlatformCompat mPlatformCompat = null;

    /**
     * The server socket in system_server, zygote will connect to it
     * in order to send unsolicited messages to system_server.
     */
    private LocalSocket mSystemServerSocketForZygote;

    /**
     * Maximum number of bytes that an incoming unsolicited zygote message could be.
     * To be updated if new message type needs to be supported.
     */
    private static final int MAX_ZYGOTE_UNSOLICITED_MESSAGE_SIZE = 16;

    /**
     * The buffer to be used to receive the incoming unsolicited zygote message.
     */
    private final byte[] mZygoteUnsolicitedMessage = new byte[MAX_ZYGOTE_UNSOLICITED_MESSAGE_SIZE];

    /**
     * The buffer to be used to receive the SIGCHLD data, it includes pid/uid/status.
     */
    private final int[] mZygoteSigChldMessage = new int[3];

    ActivityManagerGlobalLock mProcLock;

    private static final String PROPERTY_APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS =
            "apply_sdk_sandbox_next_restrictions";
    private static final boolean DEFAULT_APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS = false;

    @GuardedBy("mService")
    private ProcessListSettingsListener mProcessListSettingsListener;

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    ProcessListSettingsListener getProcessListSettingsListener() {
        synchronized (mService) {
            if (mProcessListSettingsListener == null) {
                mProcessListSettingsListener = new ProcessListSettingsListener(mService.mContext);
                mProcessListSettingsListener.registerObserver();
            }
            return mProcessListSettingsListener;
        }
    }

    static class ProcessListSettingsListener implements DeviceConfig.OnPropertiesChangedListener {

        private final Context mContext;
        private final Object mLock = new Object();

        @GuardedBy("mLock")
        private boolean mSdkSandboxApplyRestrictionsNext =
                DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ADSERVICES,
                PROPERTY_APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS,
                DEFAULT_APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS);

        ProcessListSettingsListener(Context context) {
            mContext = context;
        }

        private void registerObserver() {
            DeviceConfig.addOnPropertiesChangedListener(
                    DeviceConfig.NAMESPACE_ADSERVICES, mContext.getMainExecutor(), this);
        }

        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
        void unregisterObserver() {
            DeviceConfig.removeOnPropertiesChangedListener(this);
        }

        boolean applySdkSandboxRestrictionsNext() {
            synchronized (mLock) {
                return mSdkSandboxApplyRestrictionsNext;
            }
        }

        @Override
        public void onPropertiesChanged(@NonNull DeviceConfig.Properties properties) {
            synchronized (mLock) {
                for (String name : properties.getKeyset()) {
                    if (name == null) {
                        continue;
                    }

                    switch (name) {
                        case PROPERTY_APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS:
                            mSdkSandboxApplyRestrictionsNext =
                                properties.getBoolean(
                                    PROPERTY_APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS,
                                    DEFAULT_APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS);
                            break;
                        default:
                    }
                }
            }
        }
    }

    final class IsolatedUidRange {
        @VisibleForTesting
        public final int mFirstUid;
        @VisibleForTesting
        public final int mLastUid;

        @GuardedBy("ProcessList.this.mService")
        private final SparseBooleanArray mUidUsed = new SparseBooleanArray();

        @GuardedBy("ProcessList.this.mService")
        private int mNextUid;

        IsolatedUidRange(int firstUid, int lastUid) {
            mFirstUid = firstUid;
            mLastUid = lastUid;
            mNextUid = firstUid;
        }

        @GuardedBy("ProcessList.this.mService")
        int allocateIsolatedUidLocked(int userId) {
            int uid;
            int stepsLeft = (mLastUid - mFirstUid + 1);
            for (int i = 0; i < stepsLeft; ++i) {
                if (mNextUid < mFirstUid || mNextUid > mLastUid) {
                    mNextUid = mFirstUid;
                }
                uid = UserHandle.getUid(userId, mNextUid);
                mNextUid++;
                if (!mUidUsed.get(uid, false)) {
                    mUidUsed.put(uid, true);
                    return uid;
                }
            }
            return -1;
        }

        @GuardedBy("ProcessList.this.mService")
        void freeIsolatedUidLocked(int uid) {
            mUidUsed.delete(uid);
        }
    };

    /**
     * A class that allocates ranges of isolated UIDs per application, and keeps track of them.
     */
    final class IsolatedUidRangeAllocator {
        private final int mFirstUid;
        private final int mNumUidRanges;
        private final int mNumUidsPerRange;
        /**
         * We map the uid range [mFirstUid, mFirstUid + mNumUidRanges * mNumUidsPerRange)
         * back to an underlying bitset of [0, mNumUidRanges) and allocate out of that.
         */
        @GuardedBy("ProcessList.this.mService")
        private final BitSet mAvailableUidRanges;
        @GuardedBy("ProcessList.this.mService")
        private final ProcessMap<IsolatedUidRange> mAppRanges = new ProcessMap<IsolatedUidRange>();

        IsolatedUidRangeAllocator(int firstUid, int lastUid, int numUidsPerRange) {
            mFirstUid = firstUid;
            mNumUidsPerRange = numUidsPerRange;
            mNumUidRanges = (lastUid - firstUid + 1) / numUidsPerRange;
            mAvailableUidRanges = new BitSet(mNumUidRanges);
            // Mark all as available
            mAvailableUidRanges.set(0, mNumUidRanges);
        }

        @GuardedBy("ProcessList.this.mService")
        IsolatedUidRange getIsolatedUidRangeLocked(String processName, int uid) {
            return mAppRanges.get(processName, uid);
        }

        @GuardedBy("ProcessList.this.mService")
        IsolatedUidRange getOrCreateIsolatedUidRangeLocked(String processName, int uid) {
            IsolatedUidRange range = getIsolatedUidRangeLocked(processName, uid);
            if (range == null) {
                int uidRangeIndex = mAvailableUidRanges.nextSetBit(0);
                if (uidRangeIndex < 0) {
                    // No free range
                    return null;
                }
                mAvailableUidRanges.clear(uidRangeIndex);
                int actualUid = mFirstUid + uidRangeIndex * mNumUidsPerRange;
                range = new IsolatedUidRange(actualUid, actualUid + mNumUidsPerRange - 1);
                mAppRanges.put(processName, uid, range);
            }
            return range;
        }

        @GuardedBy("ProcessList.this.mService")
        void freeUidRangeLocked(ApplicationInfo info) {
            // Find the UID range
            IsolatedUidRange range = mAppRanges.get(info.processName, info.uid);
            if (range != null) {
                // Map back to starting uid
                final int uidRangeIndex = (range.mFirstUid - mFirstUid) / mNumUidsPerRange;
                // Mark it as available in the underlying bitset
                mAvailableUidRanges.set(uidRangeIndex);
                // And the map
                mAppRanges.remove(info.processName, info.uid);
            }
        }
    }

    /**
     * The available isolated UIDs for processes that are not spawned from an application zygote.
     */
    @VisibleForTesting
    @GuardedBy("mService")
    IsolatedUidRange mGlobalIsolatedUids = new IsolatedUidRange(Process.FIRST_ISOLATED_UID,
            Process.LAST_ISOLATED_UID);

    /**
     * An allocator for isolated UID ranges for apps that use an application zygote.
     */
    @VisibleForTesting
    @GuardedBy("mService")
    IsolatedUidRangeAllocator mAppIsolatedUidRangeAllocator =
            new IsolatedUidRangeAllocator(Process.FIRST_APP_ZYGOTE_ISOLATED_UID,
                    Process.LAST_APP_ZYGOTE_ISOLATED_UID, Process.NUM_UIDS_PER_APP_ZYGOTE);

    /**
     * Processes that are being forcibly torn down.
     */
    @GuardedBy("mService")
    final ArrayList<ProcessRecord> mRemovedProcesses = new ArrayList<ProcessRecord>();

    /**
     * Processes that are killed by us and being waiting for the death notification.
     */
    @GuardedBy("mService")
    final ProcessMap<ProcessRecord> mDyingProcesses = new ProcessMap<>();

    // Self locked with the inner lock within the RemoteCallbackList
    private final RemoteCallbackList<IProcessObserver> mProcessObservers =
            new RemoteCallbackList<>();

    // No lock is needed as it's accessed from single thread only
    private ProcessChangeItem[] mActiveProcessChanges = new ProcessChangeItem[5];

    @GuardedBy("mProcessChangeLock")
    private final ArrayList<ProcessChangeItem> mPendingProcessChanges = new ArrayList<>();

    @GuardedBy("mProcessChangeLock")
    final ArrayList<ProcessChangeItem> mAvailProcessChanges = new ArrayList<>();

    /**
     * A dedicated lock for dispatching the process changes as it occurs frequently
     */
    private final Object mProcessChangeLock = new Object();

    /**
     * All of the applications we currently have running organized by name.
     * The keys are strings of the application package name (as
     * returned by the package manager), and the keys are ApplicationRecord
     * objects.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private final MyProcessMap mProcessNames = new MyProcessMap();

    final class MyProcessMap extends ProcessMap<ProcessRecord> {
        @Override
        public ProcessRecord put(String name, int uid, ProcessRecord value) {
            final ProcessRecord r = super.put(name, uid, value);
            mService.mAtmInternal.onProcessAdded(r.getWindowProcessController());
            return r;
        }

        @Override
        public ProcessRecord remove(String name, int uid) {
            final ProcessRecord r = super.remove(name, uid);
            mService.mAtmInternal.onProcessRemoved(name, uid);
            return r;
        }
    }

    final class KillHandler extends Handler {
        static final int KILL_PROCESS_GROUP_MSG = 4000;
        static final int LMKD_RECONNECT_MSG = 4001;

        public KillHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case KILL_PROCESS_GROUP_MSG:
                    Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "killProcessGroup");
                    Process.killProcessGroup(msg.arg1 /* uid */, msg.arg2 /* pid */);
                    Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                    break;
                case LMKD_RECONNECT_MSG:
                    if (!sLmkdConnection.connect()) {
                        Slog.i(TAG, "Failed to connect to lmkd, retry after " +
                                LMKD_RECONNECT_DELAY_MS + " ms");
                        // retry after LMKD_RECONNECT_DELAY_MS
                        sKillHandler.sendMessageDelayed(sKillHandler.obtainMessage(
                                KillHandler.LMKD_RECONNECT_MSG), LMKD_RECONNECT_DELAY_MS);
                    }
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    /**
     * A runner to handle the imperceptible killings.
     */
    ImperceptibleKillRunner mImperceptibleKillRunner;

    ////////////////////  END FIELDS  ////////////////////

    ProcessList() {
        MemInfoReader minfo = new MemInfoReader();
        minfo.readMemInfo();
        mTotalMemMb = minfo.getTotalSize()/(1024*1024);
        updateOomLevels(0, 0, false);
    }

    void init(ActivityManagerService service, ActiveUids activeUids,
            PlatformCompat platformCompat) {
        mService = service;
        mActiveUids = activeUids;
        mPlatformCompat = platformCompat;
        mProcLock = service.mProcLock;
        // Get this after boot, and won't be changed until it's rebooted, as we don't
        // want some apps enabled while some apps disabled
        mAppDataIsolationEnabled =
                SystemProperties.getBoolean(ANDROID_APP_DATA_ISOLATION_ENABLED_PROPERTY, true);
        mVoldAppDataIsolationEnabled = SystemProperties.getBoolean(
                ANDROID_VOLD_APP_DATA_ISOLATION_ENABLED_PROPERTY, false);
        mAppDataIsolationAllowlistedApps = new ArrayList<>(
                SystemConfig.getInstance().getAppDataIsolationWhitelistedApps());

        if (sKillHandler == null) {
            sKillThread = new ServiceThread(TAG + ":kill",
                    THREAD_PRIORITY_BACKGROUND, true /* allowIo */);
            sKillThread.start();
            sKillHandler = new KillHandler(sKillThread.getLooper());
            sOomConnection = new OomConnection(new OomConnection.OomConnectionListener() {
                @Override
                public void handleOomEvent(OomKillRecord[] oomKills) {
                    for (OomKillRecord oomKill: oomKills) {
                        synchronized (mProcLock) {
                            noteAppKill(
                                oomKill.getPid(),
                                oomKill.getUid(),
                                ApplicationExitInfo.REASON_LOW_MEMORY,
                                ApplicationExitInfo.SUBREASON_OOM_KILL,
                                "oom");
                        }
                    }
                }
            });
            sLmkdConnection = new LmkdConnection(sKillThread.getLooper().getQueue(),
                    new LmkdConnection.LmkdConnectionListener() {
                        @Override
                        public boolean onConnect(OutputStream ostream) {
                            Slog.i(TAG, "Connection with lmkd established");
                            return onLmkdConnect(ostream);
                        }

                        @Override
                        public void onDisconnect() {
                            Slog.w(TAG, "Lost connection to lmkd");
                            // start reconnection after delay to let lmkd restart
                            sKillHandler.sendMessageDelayed(sKillHandler.obtainMessage(
                                    KillHandler.LMKD_RECONNECT_MSG), LMKD_RECONNECT_DELAY_MS);
                        }

                        @Override
                        public boolean isReplyExpected(ByteBuffer replyBuf,
                                ByteBuffer dataReceived, int receivedLen) {
                            // compare the preambule (currently one integer) to check if
                            // this is the reply packet we are waiting for
                            return (receivedLen == replyBuf.array().length &&
                                    dataReceived.getInt(0) == replyBuf.getInt(0));
                        }

                        @Override
                        public boolean handleUnsolicitedMessage(DataInputStream inputData,
                                int receivedLen) {
                            if (receivedLen < 4) {
                                return false;
                            }

                            try {
                                switch (inputData.readInt()) {
                                    case LMK_PROCKILL:
                                        if (receivedLen != 12) {
                                            return false;
                                        }
                                        final int pid = inputData.readInt();
                                        final int uid = inputData.readInt();
                                        mAppExitInfoTracker.scheduleNoteLmkdProcKilled(pid, uid);
                                        return true;
                                    case LMK_KILL_OCCURRED:
                                        if (receivedLen
                                                < LmkdStatsReporter.KILL_OCCURRED_MSG_SIZE) {
                                            return false;
                                        }
                                        // Note: directly access
                                        // ActiveServices.sNumForegroundServices, do not try to
                                        // hold AMS lock here, otherwise it is a potential deadlock.
                                        Pair<Integer, Integer> foregroundServices =
                                                ActiveServices.sNumForegroundServices.get();
                                        LmkdStatsReporter.logKillOccurred(inputData,
                                                foregroundServices.first,
                                                foregroundServices.second);
                                        return true;
                                    case LMK_STATE_CHANGED:
                                        if (receivedLen
                                                != LmkdStatsReporter.STATE_CHANGED_MSG_SIZE) {
                                            return false;
                                        }
                                        final int state = inputData.readInt();
                                        LmkdStatsReporter.logStateChanged(state);
                                        return true;
                                    default:
                                        return false;
                                }
                            } catch (IOException e) {
                                Slog.e(TAG, "Invalid buffer data. Failed to log LMK_KILL_OCCURRED");
                            }
                            return false;
                        }
                    }
            );
            // Start listening on incoming connections from zygotes.
            mSystemServerSocketForZygote = createSystemServerSocketForZygote();
            if (mSystemServerSocketForZygote != null) {
                sKillHandler.getLooper().getQueue().addOnFileDescriptorEventListener(
                        mSystemServerSocketForZygote.getFileDescriptor(),
                        EVENT_INPUT, this::handleZygoteMessages);
            }
            mAppExitInfoTracker.init(mService);
            mImperceptibleKillRunner = new ImperceptibleKillRunner(sKillThread.getLooper());
        }
    }

    void onSystemReady() {
        mAppExitInfoTracker.onSystemReady();
    }

    void applyDisplaySize(WindowManagerService wm) {
        if (!mHaveDisplaySize) {
            Point p = new Point();
            // TODO(multi-display): Compute based on sum of all connected displays' resolutions.
            wm.getBaseDisplaySize(Display.DEFAULT_DISPLAY, p);
            if (p.x != 0 && p.y != 0) {
                updateOomLevels(p.x, p.y, true);
                mHaveDisplaySize = true;
            }
        }
    }

    /**
     * Get a map of pid and package name that process of that pid Android/data and Android/obb
     * directory is not mounted to lowerfs to speed up access.
     */
    Map<Integer, String> getProcessesWithPendingBindMounts(int userId) {
        final Map<Integer, String> pidPackageMap = new HashMap<>();
        synchronized (mProcLock) {
            for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
                final ProcessRecord record = mLruProcesses.get(i);
                if (record.userId != userId || !record.isBindMountPending()) {
                    continue;
                }
                final int pid = record.getPid();
                // It can happen when app process is starting, but zygote work is not done yet so
                // system does not this pid record yet.
                if (pid == 0) {
                    throw new IllegalStateException("Pending process is not started yet,"
                            + "retry later");
                }
                pidPackageMap.put(pid, record.info.packageName);
            }
        }
        return pidPackageMap;
    }

    private void updateOomLevels(int displayWidth, int displayHeight, boolean write) {
        // Scale buckets from avail memory: at 300MB we use the lowest values to
        // 700MB or more for the top values.
        float scaleMem = ((float) (mTotalMemMb - 350)) / (700 - 350);

        // Scale buckets from screen size.
        int minSize = 480 * 800;  //  384000
        int maxSize = 1280 * 800; // 1024000  230400 870400  .264
        float scaleDisp = ((float)(displayWidth * displayHeight) - minSize) / (maxSize - minSize);
        if (false) {
            Slog.i("XXXXXX", "scaleMem=" + scaleMem);
            Slog.i("XXXXXX", "scaleDisp=" + scaleDisp + " dw=" + displayWidth
                    + " dh=" + displayHeight);
        }

        float scale = scaleMem > scaleDisp ? scaleMem : scaleDisp;
        if (scale < 0) scale = 0;
        else if (scale > 1) scale = 1;
        int minfree_adj = Resources.getSystem().getInteger(
                com.android.internal.R.integer.config_lowMemoryKillerMinFreeKbytesAdjust);
        int minfree_abs = Resources.getSystem().getInteger(
                com.android.internal.R.integer.config_lowMemoryKillerMinFreeKbytesAbsolute);
        if (false) {
            Slog.i("XXXXXX", "minfree_adj=" + minfree_adj + " minfree_abs=" + minfree_abs);
        }

        final boolean is64bit = Build.SUPPORTED_64_BIT_ABIS.length > 0;

        for (int i = 0; i < mOomAdj.length; i++) {
            int low = mOomMinFreeLow[i];
            int high = mOomMinFreeHigh[i];
            if (is64bit) {
                // Increase the high min-free levels for cached processes for 64-bit
                if (i == 4) high = (high * 3) / 2;
                else if (i == 5) high = (high * 7) / 4;
            }
            mOomMinFree[i] = (int)(low + ((high - low) * scale));
        }

        if (minfree_abs >= 0) {
            for (int i = 0; i < mOomAdj.length; i++) {
                mOomMinFree[i] = (int)((float)minfree_abs * mOomMinFree[i]
                        / mOomMinFree[mOomAdj.length - 1]);
            }
        }

        if (minfree_adj != 0) {
            for (int i = 0; i < mOomAdj.length; i++) {
                mOomMinFree[i] += (int)((float) minfree_adj * mOomMinFree[i]
                        / mOomMinFree[mOomAdj.length - 1]);
                if (mOomMinFree[i] < 0) {
                    mOomMinFree[i] = 0;
                }
            }
        }

        // The maximum size we will restore a process from cached to background, when under
        // memory duress, is 1/3 the size we have reserved for kernel caches and other overhead
        // before killing background processes.
        mCachedRestoreLevel = (getMemLevel(ProcessList.CACHED_APP_MAX_ADJ) / 1024) / 3;

        // Ask the kernel to try to keep enough memory free to allocate 3 full
        // screen 32bpp buffers without entering direct reclaim.
        int reserve = displayWidth * displayHeight * 4 * 3 / 1024;
        int reserve_adj = Resources.getSystem().getInteger(
                com.android.internal.R.integer.config_extraFreeKbytesAdjust);
        int reserve_abs = Resources.getSystem().getInteger(
                com.android.internal.R.integer.config_extraFreeKbytesAbsolute);

        if (reserve_abs >= 0) {
            reserve = reserve_abs;
        }

        if (reserve_adj != 0) {
            reserve += reserve_adj;
            if (reserve < 0) {
                reserve = 0;
            }
        }

        if (write) {
            ByteBuffer buf = ByteBuffer.allocate(4 * (2 * mOomAdj.length + 1));
            buf.putInt(LMK_TARGET);
            for (int i = 0; i < mOomAdj.length; i++) {
                buf.putInt((mOomMinFree[i] * 1024)/PAGE_SIZE);
                buf.putInt(mOomAdj[i]);
            }

            writeLmkd(buf, null);
            SystemProperties.set("sys.sysctl.extra_free_kbytes", Integer.toString(reserve));
            mOomLevelsSet = true;
        }
        // GB: 2048,3072,4096,6144,7168,8192
        // HC: 8192,10240,12288,14336,16384,20480
    }

    public static int computeEmptyProcessLimit(int totalProcessLimit) {
        return totalProcessLimit/2;
    }

    private static String buildOomTag(String prefix, String compactPrefix, String space, int val,
            int base, boolean compact) {
        final int diff = val - base;
        if (diff == 0) {
            if (compact) {
                return compactPrefix;
            }
            if (space == null) return prefix;
            return prefix + space;
        }
        if (diff < 10) {
            return prefix + (compact ? "+" : "+ ") + Integer.toString(diff);
        }
        return prefix + "+" + Integer.toString(diff);
    }

    public static String makeOomAdjString(int setAdj, boolean compact) {
        if (setAdj >= ProcessList.CACHED_APP_MIN_ADJ) {
            return buildOomTag("cch", "cch", "   ", setAdj,
                    ProcessList.CACHED_APP_MIN_ADJ, compact);
        } else if (setAdj >= ProcessList.SERVICE_B_ADJ) {
            return buildOomTag("svcb  ", "svcb", null, setAdj,
                    ProcessList.SERVICE_B_ADJ, compact);
        } else if (setAdj >= ProcessList.PREVIOUS_APP_ADJ) {
            return buildOomTag("prev  ", "prev", null, setAdj,
                    ProcessList.PREVIOUS_APP_ADJ, compact);
        } else if (setAdj >= ProcessList.HOME_APP_ADJ) {
            return buildOomTag("home  ", "home", null, setAdj,
                    ProcessList.HOME_APP_ADJ, compact);
        } else if (setAdj >= ProcessList.SERVICE_ADJ) {
            return buildOomTag("svc   ", "svc", null, setAdj,
                    ProcessList.SERVICE_ADJ, compact);
        } else if (setAdj >= ProcessList.HEAVY_WEIGHT_APP_ADJ) {
            return buildOomTag("hvy   ", "hvy", null, setAdj,
                    ProcessList.HEAVY_WEIGHT_APP_ADJ, compact);
        } else if (setAdj >= ProcessList.BACKUP_APP_ADJ) {
            return buildOomTag("bkup  ", "bkup", null, setAdj,
                    ProcessList.BACKUP_APP_ADJ, compact);
        } else if (setAdj >= ProcessList.PERCEPTIBLE_LOW_APP_ADJ) {
            return buildOomTag("prcl  ", "prcl", null, setAdj,
                    ProcessList.PERCEPTIBLE_LOW_APP_ADJ, compact);
        } else if (setAdj >= ProcessList.PERCEPTIBLE_MEDIUM_APP_ADJ) {
            return buildOomTag("prcm  ", "prcm", null, setAdj,
                    ProcessList.PERCEPTIBLE_MEDIUM_APP_ADJ, compact);
        } else if (setAdj >= ProcessList.PERCEPTIBLE_APP_ADJ) {
            return buildOomTag("prcp  ", "prcp", null, setAdj,
                    ProcessList.PERCEPTIBLE_APP_ADJ, compact);
        } else if (setAdj >= ProcessList.VISIBLE_APP_ADJ) {
            return buildOomTag("vis", "vis", "   ", setAdj,
                    ProcessList.VISIBLE_APP_ADJ, compact);
        } else if (setAdj >= ProcessList.FOREGROUND_APP_ADJ) {
            return buildOomTag("fg ", "fg ", "   ", setAdj,
                    ProcessList.FOREGROUND_APP_ADJ, compact);
        } else if (setAdj >= ProcessList.PERSISTENT_SERVICE_ADJ) {
            return buildOomTag("psvc  ", "psvc", null, setAdj,
                    ProcessList.PERSISTENT_SERVICE_ADJ, compact);
        } else if (setAdj >= ProcessList.PERSISTENT_PROC_ADJ) {
            return buildOomTag("pers  ", "pers", null, setAdj,
                    ProcessList.PERSISTENT_PROC_ADJ, compact);
        } else if (setAdj >= ProcessList.SYSTEM_ADJ) {
            return buildOomTag("sys   ", "sys", null, setAdj,
                    ProcessList.SYSTEM_ADJ, compact);
        } else if (setAdj >= ProcessList.NATIVE_ADJ) {
            return buildOomTag("ntv  ", "ntv", null, setAdj,
                    ProcessList.NATIVE_ADJ, compact);
        } else {
            return Integer.toString(setAdj);
        }
    }

    public static String makeProcStateString(int curProcState) {
        return ActivityManager.procStateToString(curProcState);
    }

    public static int makeProcStateProtoEnum(int curProcState) {
        switch (curProcState) {
            case ActivityManager.PROCESS_STATE_PERSISTENT:
                return AppProtoEnums.PROCESS_STATE_PERSISTENT;
            case ActivityManager.PROCESS_STATE_PERSISTENT_UI:
                return AppProtoEnums.PROCESS_STATE_PERSISTENT_UI;
            case ActivityManager.PROCESS_STATE_TOP:
                return AppProtoEnums.PROCESS_STATE_TOP;
            case ActivityManager.PROCESS_STATE_BOUND_TOP:
                return AppProtoEnums.PROCESS_STATE_BOUND_TOP;
            case ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE:
                return AppProtoEnums.PROCESS_STATE_FOREGROUND_SERVICE;
            case ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE:
                return AppProtoEnums.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
            case ActivityManager.PROCESS_STATE_TOP_SLEEPING:
                return AppProtoEnums.PROCESS_STATE_TOP_SLEEPING;
            case ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND:
                return AppProtoEnums.PROCESS_STATE_IMPORTANT_FOREGROUND;
            case ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND:
                return AppProtoEnums.PROCESS_STATE_IMPORTANT_BACKGROUND;
            case ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND:
                return AppProtoEnums.PROCESS_STATE_TRANSIENT_BACKGROUND;
            case ActivityManager.PROCESS_STATE_BACKUP:
                return AppProtoEnums.PROCESS_STATE_BACKUP;
            case ActivityManager.PROCESS_STATE_HEAVY_WEIGHT:
                return AppProtoEnums.PROCESS_STATE_HEAVY_WEIGHT;
            case ActivityManager.PROCESS_STATE_SERVICE:
                return AppProtoEnums.PROCESS_STATE_SERVICE;
            case ActivityManager.PROCESS_STATE_RECEIVER:
                return AppProtoEnums.PROCESS_STATE_RECEIVER;
            case ActivityManager.PROCESS_STATE_HOME:
                return AppProtoEnums.PROCESS_STATE_HOME;
            case ActivityManager.PROCESS_STATE_LAST_ACTIVITY:
                return AppProtoEnums.PROCESS_STATE_LAST_ACTIVITY;
            case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY:
                return AppProtoEnums.PROCESS_STATE_CACHED_ACTIVITY;
            case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
                return AppProtoEnums.PROCESS_STATE_CACHED_ACTIVITY_CLIENT;
            case ActivityManager.PROCESS_STATE_CACHED_RECENT:
                return AppProtoEnums.PROCESS_STATE_CACHED_RECENT;
            case ActivityManager.PROCESS_STATE_CACHED_EMPTY:
                return AppProtoEnums.PROCESS_STATE_CACHED_EMPTY;
            case ActivityManager.PROCESS_STATE_NONEXISTENT:
                return AppProtoEnums.PROCESS_STATE_NONEXISTENT;
            case ActivityManager.PROCESS_STATE_UNKNOWN:
                return AppProtoEnums.PROCESS_STATE_UNKNOWN;
            default:
                return AppProtoEnums.PROCESS_STATE_UNKNOWN_TO_PROTO;
        }
    }

    public static void appendRamKb(StringBuilder sb, long ramKb) {
        for (int j = 0, fact = 10; j < 6; j++, fact *= 10) {
            if (ramKb < fact) {
                sb.append(' ');
            }
        }
        sb.append(ramKb);
    }

    // How long after a state change that it is safe to collect PSS without it being dirty.
    public static final int PSS_SAFE_TIME_FROM_STATE_CHANGE = 1000;

    // The minimum time interval after a state change it is safe to collect PSS.
    public static final int PSS_MIN_TIME_FROM_STATE_CHANGE = 15*1000;

    // The maximum amount of time we want to go between PSS collections.
    public static final int PSS_MAX_INTERVAL = 60*60*1000;

    // The minimum amount of time between successive PSS requests for *all* processes.
    public static final int PSS_ALL_INTERVAL = 20*60*1000;

    // The amount of time until PSS when a persistent process first appears.
    private static final int PSS_FIRST_PERSISTENT_INTERVAL = 30*1000;

    // The amount of time until PSS when a process first becomes top.
    private static final int PSS_FIRST_TOP_INTERVAL = 10*1000;

    // The amount of time until PSS when a process first goes into the background.
    private static final int PSS_FIRST_BACKGROUND_INTERVAL = 20*1000;

    // The amount of time until PSS when a process first becomes cached.
    private static final int PSS_FIRST_CACHED_INTERVAL = 20*1000;

    // The amount of time until PSS when an important process stays in the same state.
    private static final int PSS_SAME_PERSISTENT_INTERVAL = 10*60*1000;

    // The amount of time until PSS when the top process stays in the same state.
    private static final int PSS_SAME_TOP_INTERVAL = 1*60*1000;

    // The amount of time until PSS when an important process stays in the same state.
    private static final int PSS_SAME_IMPORTANT_INTERVAL = 10*60*1000;

    // The amount of time until PSS when a service process stays in the same state.
    private static final int PSS_SAME_SERVICE_INTERVAL = 5*60*1000;

    // The amount of time until PSS when a cached process stays in the same state.
    private static final int PSS_SAME_CACHED_INTERVAL = 10*60*1000;

    // The amount of time until PSS when a persistent process first appears.
    private static final int PSS_FIRST_ASLEEP_PERSISTENT_INTERVAL = 1*60*1000;

    // The amount of time until PSS when a process first becomes top.
    private static final int PSS_FIRST_ASLEEP_TOP_INTERVAL = 20*1000;

    // The amount of time until PSS when a process first goes into the background.
    private static final int PSS_FIRST_ASLEEP_BACKGROUND_INTERVAL = 30*1000;

    // The amount of time until PSS when a process first becomes cached.
    private static final int PSS_FIRST_ASLEEP_CACHED_INTERVAL = 1*60*1000;

    // The minimum time interval after a state change it is safe to collect PSS.
    public static final int PSS_TEST_MIN_TIME_FROM_STATE_CHANGE = 10*1000;

    // The amount of time during testing until PSS when a process first becomes top.
    private static final int PSS_TEST_FIRST_TOP_INTERVAL = 3*1000;

    // The amount of time during testing until PSS when a process first goes into the background.
    private static final int PSS_TEST_FIRST_BACKGROUND_INTERVAL = 5*1000;

    // The amount of time during testing until PSS when an important process stays in same state.
    private static final int PSS_TEST_SAME_IMPORTANT_INTERVAL = 10*1000;

    // The amount of time during testing until PSS when a background process stays in same state.
    private static final int PSS_TEST_SAME_BACKGROUND_INTERVAL = 15*1000;

    public static final int PROC_MEM_PERSISTENT = 0;
    public static final int PROC_MEM_TOP = 1;
    public static final int PROC_MEM_IMPORTANT = 2;
    public static final int PROC_MEM_SERVICE = 3;
    public static final int PROC_MEM_CACHED = 4;
    public static final int PROC_MEM_NUM = 5;

    // Map large set of system process states to
    private static final int[] sProcStateToProcMem = new int[] {
        PROC_MEM_PERSISTENT,            // ActivityManager.PROCESS_STATE_PERSISTENT
        PROC_MEM_PERSISTENT,            // ActivityManager.PROCESS_STATE_PERSISTENT_UI
        PROC_MEM_TOP,                   // ActivityManager.PROCESS_STATE_TOP
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE
        PROC_MEM_TOP,                   // ActivityManager.PROCESS_STATE_BOUND_TOP
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_BACKUP
        PROC_MEM_SERVICE,               // ActivityManager.PROCESS_STATE_SERVICE
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_RECEIVER
        PROC_MEM_TOP,                   // ActivityManager.PROCESS_STATE_TOP_SLEEPING
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_HEAVY_WEIGHT
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_HOME
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_LAST_ACTIVITY
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_CACHED_ACTIVITY
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_CACHED_RECENT
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_CACHED_EMPTY
    };

    private static final long[] sFirstAwakePssTimes = new long[] {
        PSS_FIRST_PERSISTENT_INTERVAL,  // PROC_MEM_PERSISTENT
        PSS_FIRST_TOP_INTERVAL,         // PROC_MEM_TOP
        PSS_FIRST_BACKGROUND_INTERVAL,  // PROC_MEM_IMPORTANT
        PSS_FIRST_BACKGROUND_INTERVAL,  // PROC_MEM_SERVICE
        PSS_FIRST_CACHED_INTERVAL,      // PROC_MEM_CACHED
    };

    private static final long[] sSameAwakePssTimes = new long[] {
        PSS_SAME_PERSISTENT_INTERVAL,   // PROC_MEM_PERSISTENT
        PSS_SAME_TOP_INTERVAL,          // PROC_MEM_TOP
        PSS_SAME_IMPORTANT_INTERVAL,    // PROC_MEM_IMPORTANT
        PSS_SAME_SERVICE_INTERVAL,      // PROC_MEM_SERVICE
        PSS_SAME_CACHED_INTERVAL,       // PROC_MEM_CACHED
    };

    private static final long[] sFirstAsleepPssTimes = new long[] {
        PSS_FIRST_ASLEEP_PERSISTENT_INTERVAL,   // PROC_MEM_PERSISTENT
        PSS_FIRST_ASLEEP_TOP_INTERVAL,          // PROC_MEM_TOP
        PSS_FIRST_ASLEEP_BACKGROUND_INTERVAL,   // PROC_MEM_IMPORTANT
        PSS_FIRST_ASLEEP_BACKGROUND_INTERVAL,   // PROC_MEM_SERVICE
        PSS_FIRST_ASLEEP_CACHED_INTERVAL,       // PROC_MEM_CACHED
    };

    private static final long[] sSameAsleepPssTimes = new long[] {
        PSS_SAME_PERSISTENT_INTERVAL,   // PROC_MEM_PERSISTENT
        PSS_SAME_TOP_INTERVAL,          // PROC_MEM_TOP
        PSS_SAME_IMPORTANT_INTERVAL,    // PROC_MEM_IMPORTANT
        PSS_SAME_SERVICE_INTERVAL,      // PROC_MEM_SERVICE
        PSS_SAME_CACHED_INTERVAL,       // PROC_MEM_CACHED
    };

    private static final long[] sTestFirstPssTimes = new long[] {
        PSS_TEST_FIRST_TOP_INTERVAL,        // PROC_MEM_PERSISTENT
        PSS_TEST_FIRST_TOP_INTERVAL,        // PROC_MEM_TOP
        PSS_TEST_FIRST_BACKGROUND_INTERVAL, // PROC_MEM_IMPORTANT
        PSS_TEST_FIRST_BACKGROUND_INTERVAL, // PROC_MEM_SERVICE
        PSS_TEST_FIRST_BACKGROUND_INTERVAL, // PROC_MEM_CACHED
    };

    private static final long[] sTestSamePssTimes = new long[] {
        PSS_TEST_SAME_BACKGROUND_INTERVAL,  // PROC_MEM_PERSISTENT
        PSS_TEST_SAME_IMPORTANT_INTERVAL,   // PROC_MEM_TOP
        PSS_TEST_SAME_IMPORTANT_INTERVAL,   // PROC_MEM_IMPORTANT
        PSS_TEST_SAME_BACKGROUND_INTERVAL,  // PROC_MEM_SERVICE
        PSS_TEST_SAME_BACKGROUND_INTERVAL,  // PROC_MEM_CACHED
    };

    public static final class ProcStateMemTracker {
        final int[] mHighestMem = new int[PROC_MEM_NUM];
        final float[] mScalingFactor = new float[PROC_MEM_NUM];
        int mTotalHighestMem = PROC_MEM_CACHED;

        int mPendingMemState;
        int mPendingHighestMemState;
        float mPendingScalingFactor;

        public ProcStateMemTracker() {
            for (int i = PROC_MEM_PERSISTENT; i < PROC_MEM_NUM; i++) {
                mHighestMem[i] = PROC_MEM_NUM;
                mScalingFactor[i] = 1.0f;
            }
            mPendingMemState = -1;
        }

        public void dumpLine(PrintWriter pw) {
            pw.print("best=");
            pw.print(mTotalHighestMem);
            pw.print(" (");
            boolean needSep = false;
            for (int i = 0; i < PROC_MEM_NUM; i++) {
                if (mHighestMem[i] < PROC_MEM_NUM) {
                    if (needSep) {
                        pw.print(", ");
                        needSep = false;
                    }
                    pw.print(i);
                    pw.print("=");
                    pw.print(mHighestMem[i]);
                    pw.print(" ");
                    pw.print(mScalingFactor[i]);
                    pw.print("x");
                    needSep = true;
                }
            }
            pw.print(")");
            if (mPendingMemState >= 0) {
                pw.print(" / pending state=");
                pw.print(mPendingMemState);
                pw.print(" highest=");
                pw.print(mPendingHighestMemState);
                pw.print(" ");
                pw.print(mPendingScalingFactor);
                pw.print("x");
            }
            pw.println();
        }
    }

    public static boolean procStatesDifferForMem(int procState1, int procState2) {
        return sProcStateToProcMem[procState1] != sProcStateToProcMem[procState2];
    }

    public static long minTimeFromStateChange(boolean test) {
        return test ? PSS_TEST_MIN_TIME_FROM_STATE_CHANGE : PSS_MIN_TIME_FROM_STATE_CHANGE;
    }

    public static long computeNextPssTime(int procState, ProcStateMemTracker tracker, boolean test,
            boolean sleeping, long now) {
        boolean first;
        float scalingFactor;
        final int memState = sProcStateToProcMem[procState];
        if (tracker != null) {
            final int highestMemState = memState < tracker.mTotalHighestMem
                    ? memState : tracker.mTotalHighestMem;
            first = highestMemState < tracker.mHighestMem[memState];
            tracker.mPendingMemState = memState;
            tracker.mPendingHighestMemState = highestMemState;
            if (first) {
                tracker.mPendingScalingFactor = scalingFactor = 1.0f;
            } else {
                scalingFactor = tracker.mScalingFactor[memState];
                tracker.mPendingScalingFactor = scalingFactor * 1.5f;
            }
        } else {
            first = true;
            scalingFactor = 1.0f;
        }
        final long[] table = test
                ? (first
                ? sTestFirstPssTimes
                : sTestSamePssTimes)
                : (first
                ? (sleeping ? sFirstAsleepPssTimes : sFirstAwakePssTimes)
                : (sleeping ? sSameAsleepPssTimes : sSameAwakePssTimes));
        long delay = (long)(table[memState] * scalingFactor);
        if (delay > PSS_MAX_INTERVAL) {
            delay = PSS_MAX_INTERVAL;
        }
        return now + delay;
    }

    long getMemLevel(int adjustment) {
        for (int i = 0; i < mOomAdj.length; i++) {
            if (adjustment <= mOomAdj[i]) {
                return mOomMinFree[i] * 1024;
            }
        }
        return mOomMinFree[mOomAdj.length - 1] * 1024;
    }

    /**
     * Return the maximum pss size in kb that we consider a process acceptable to
     * restore from its cached state for running in the background when RAM is low.
     */
    long getCachedRestoreThresholdKb() {
        return mCachedRestoreLevel;
    }

    /**
     * Set the out-of-memory badness adjustment for a process.
     * If {@code pid <= 0}, this method will be a no-op.
     *
     * @param pid The process identifier to set.
     * @param uid The uid of the app
     * @param amt Adjustment value -- lmkd allows -1000 to +1000
     *
     * {@hide}
     */
    public static void setOomAdj(int pid, int uid, int amt) {
        // This indicates that the process is not started yet and so no need to proceed further.
        if (pid <= 0) {
            return;
        }
        if (amt == UNKNOWN_ADJ)
            return;

        long start = SystemClock.elapsedRealtime();
        ByteBuffer buf = ByteBuffer.allocate(4 * 4);
        buf.putInt(LMK_PROCPRIO);
        buf.putInt(pid);
        buf.putInt(uid);
        buf.putInt(amt);
        writeLmkd(buf, null);
        long now = SystemClock.elapsedRealtime();
        if ((now-start) > 250) {
            Slog.w("ActivityManager", "SLOW OOM ADJ: " + (now-start) + "ms for pid " + pid
                    + " = " + amt);
        }
    }

    /*
     * {@hide}
     */
    public static final void remove(int pid) {
        // This indicates that the process is not started yet and so no need to proceed further.
        if (pid <= 0) {
            return;
        }
        ByteBuffer buf = ByteBuffer.allocate(4 * 2);
        buf.putInt(LMK_PROCREMOVE);
        buf.putInt(pid);
        writeLmkd(buf, null);
    }

    /*
     * {@hide}
     */
    public static final Integer getLmkdKillCount(int min_oom_adj, int max_oom_adj) {
        ByteBuffer buf = ByteBuffer.allocate(4 * 3);
        ByteBuffer repl = ByteBuffer.allocate(4 * 2);
        buf.putInt(LMK_GETKILLCNT);
        buf.putInt(min_oom_adj);
        buf.putInt(max_oom_adj);
        // indicate what we are waiting for
        repl.putInt(LMK_GETKILLCNT);
        repl.rewind();
        if (writeLmkd(buf, repl) && repl.getInt() == LMK_GETKILLCNT) {
            return new Integer(repl.getInt());
        }
        return null;
    }

    public boolean onLmkdConnect(OutputStream ostream) {
        try {
            // Purge any previously registered pids
            ByteBuffer buf = ByteBuffer.allocate(4);
            buf.putInt(LMK_PROCPURGE);
            ostream.write(buf.array(), 0, buf.position());
            if (mOomLevelsSet) {
                // Reset oom_adj levels
                buf = ByteBuffer.allocate(4 * (2 * mOomAdj.length + 1));
                buf.putInt(LMK_TARGET);
                for (int i = 0; i < mOomAdj.length; i++) {
                    buf.putInt((mOomMinFree[i] * 1024)/PAGE_SIZE);
                    buf.putInt(mOomAdj[i]);
                }
                ostream.write(buf.array(), 0, buf.position());
            }
            // Subscribe for kill event notifications
            buf = ByteBuffer.allocate(4 * 2);
            buf.putInt(LMK_SUBSCRIBE);
            buf.putInt(LMK_ASYNC_EVENT_KILL);
            ostream.write(buf.array(), 0, buf.position());

            // Subscribe for stats event notifications
            buf = ByteBuffer.allocate(4 * 2);
            buf.putInt(LMK_SUBSCRIBE);
            buf.putInt(LMK_ASYNC_EVENT_STAT);
            ostream.write(buf.array(), 0, buf.position());
        } catch (IOException ex) {
            return false;
        }
        return true;
    }

    /**
     * {@hide}
     */
    public static void startPsiMonitoringAfterBoot() {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putInt(LMK_START_MONITORING);
        writeLmkd(buf, null);
    }

    private static boolean writeLmkd(ByteBuffer buf, ByteBuffer repl) {
        if (!sLmkdConnection.isConnected()) {
            // try to connect immediately and then keep retrying
            sKillHandler.sendMessage(
                    sKillHandler.obtainMessage(KillHandler.LMKD_RECONNECT_MSG));

            // wait for connection retrying 3 times (up to 3 seconds)
            if (!sLmkdConnection.waitForConnection(3 * LMKD_RECONNECT_DELAY_MS)) {
                return false;
            }
        }

        return sLmkdConnection.exchange(buf, repl);
    }

    static void killProcessGroup(int uid, int pid) {
        /* static; one-time init here */
        if (sKillHandler != null) {
            sKillHandler.sendMessage(
                    sKillHandler.obtainMessage(KillHandler.KILL_PROCESS_GROUP_MSG, uid, pid));
        } else {
            Slog.w(TAG, "Asked to kill process group before system bringup!");
            Process.killProcessGroup(uid, pid);
        }
    }

    @GuardedBy("mService")
    ProcessRecord getProcessRecordLocked(String processName, int uid) {
        if (uid == SYSTEM_UID) {
            // The system gets to run in any process.  If there are multiple
            // processes with the same uid, just pick the first (this
            // should never happen).
            SparseArray<ProcessRecord> procs = mProcessNames.getMap().get(processName);
            if (procs == null) return null;
            final int procCount = procs.size();
            for (int i = 0; i < procCount; i++) {
                final int procUid = procs.keyAt(i);
                if (!UserHandle.isCore(procUid) || !UserHandle.isSameUser(procUid, uid)) {
                    // Don't use an app process or different user process for system component.
                    continue;
                }
                return procs.valueAt(i);
            }
        }
        return mProcessNames.get(processName, uid);
    }

    void getMemoryInfo(ActivityManager.MemoryInfo outInfo) {
        final long homeAppMem = getMemLevel(HOME_APP_ADJ);
        final long cachedAppMem = getMemLevel(CACHED_APP_MIN_ADJ);
        outInfo.advertisedMem = getAdvertisedMem();
        outInfo.availMem = getFreeMemory();
        outInfo.totalMem = getTotalMemory();
        outInfo.threshold = homeAppMem;
        outInfo.lowMemory = outInfo.availMem < (homeAppMem + ((cachedAppMem-homeAppMem)/2));
        outInfo.hiddenAppThreshold = cachedAppMem;
        outInfo.secondaryServerThreshold = getMemLevel(SERVICE_ADJ);
        outInfo.visibleAppThreshold = getMemLevel(VISIBLE_APP_ADJ);
        outInfo.foregroundAppThreshold = getMemLevel(FOREGROUND_APP_ADJ);
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    ProcessRecord findAppProcessLOSP(IBinder app, String reason) {
        final int NP = mProcessNames.getMap().size();
        for (int ip = 0; ip < NP; ip++) {
            SparseArray<ProcessRecord> apps = mProcessNames.getMap().valueAt(ip);
            final int NA = apps.size();
            for (int ia = 0; ia < NA; ia++) {
                ProcessRecord p = apps.valueAt(ia);
                final IApplicationThread thread = p.getThread();
                if (thread != null && thread.asBinder() == app) {
                    return p;
                }
            }
        }

        Slog.w(TAG, "Can't find mystery application for " + reason
                + " from pid=" + Binder.getCallingPid()
                + " uid=" + Binder.getCallingUid() + ": " + app);
        return null;
    }

    private void checkSlow(long startTime, String where) {
        long now = SystemClock.uptimeMillis();
        if ((now - startTime) > 50) {
            // If we are taking more than 50ms, log about it.
            Slog.w(TAG, "Slow operation: " + (now - startTime) + "ms so far, now at " + where);
        }
    }

    private int[] computeGidsForProcess(int mountExternal, int uid, int[] permGids,
            boolean externalStorageAccess) {
        ArrayList<Integer> gidList = new ArrayList<>(permGids.length + 5);

        final int sharedAppGid = UserHandle.getSharedAppGid(UserHandle.getAppId(uid));
        final int cacheAppGid = UserHandle.getCacheAppGid(UserHandle.getAppId(uid));
        final int userGid = UserHandle.getUserGid(UserHandle.getUserId(uid));

        // Add shared application and profile GIDs so applications can share some
        // resources like shared libraries and access user-wide resources
        for (int permGid : permGids) {
            gidList.add(permGid);
        }
        if (sharedAppGid != UserHandle.ERR_GID) {
            gidList.add(sharedAppGid);
        }
        if (cacheAppGid != UserHandle.ERR_GID) {
            gidList.add(cacheAppGid);
        }
        if (userGid != UserHandle.ERR_GID) {
            gidList.add(userGid);
        }
        if (mountExternal == Zygote.MOUNT_EXTERNAL_ANDROID_WRITABLE
                || mountExternal == Zygote.MOUNT_EXTERNAL_PASS_THROUGH) {
            // For DownloadProviders and MTP: To grant access to /sdcard/Android/
            // And a special case for the FUSE daemon since it runs an MTP server and should have
            // access to Android/
            // Note that we must add in the user id, because sdcardfs synthesizes this permission
            // based on the user
            gidList.add(UserHandle.getUid(UserHandle.getUserId(uid), Process.SDCARD_RW_GID));

            // For devices without sdcardfs, these GIDs are needed instead; note that we
            // consciously don't add the user_id in the GID, since these apps are anyway
            // isolated to only their own user
            gidList.add(Process.EXT_DATA_RW_GID);
            gidList.add(Process.EXT_OBB_RW_GID);
        }
        if (mountExternal == Zygote.MOUNT_EXTERNAL_INSTALLER) {
            // For devices without sdcardfs, this GID is needed to allow installers access to OBBs
            gidList.add(Process.EXT_OBB_RW_GID);
        }
        if (mountExternal == Zygote.MOUNT_EXTERNAL_PASS_THROUGH) {
            // For the FUSE daemon: To grant access to the lower filesystem.
            // EmulatedVolumes: /data/media and /mnt/expand/<volume>/data/media
            // PublicVolumes: /mnt/media_rw/<volume>
            gidList.add(Process.MEDIA_RW_GID);
        }
        if (externalStorageAccess) {
            // Apps with MANAGE_EXTERNAL_STORAGE PERMISSION need the external_storage gid to access
            // USB OTG (unreliable) volumes on /mnt/media_rw/<vol name>
            gidList.add(Process.EXTERNAL_STORAGE_GID);
        }

        int[] gidArray = new int[gidList.size()];
        for (int i = 0; i < gidArray.length; i++) {
            gidArray[i] = gidList.get(i);
        }
        return gidArray;
    }

    /**
     * @return {@code true} if process start is successful, false otherwise.
     */
    @GuardedBy("mService")
    boolean startProcessLocked(ProcessRecord app, HostingRecord hostingRecord,
            int zygotePolicyFlags, boolean disableHiddenApiChecks, boolean disableTestApiChecks,
            String abiOverride) {
        if (app.isPendingStart()) {
            return true;
        }
        final long startUptime = SystemClock.uptimeMillis();
        final long startElapsedTime = SystemClock.elapsedRealtime();
        if (app.getPid() > 0 && app.getPid() != ActivityManagerService.MY_PID) {
            checkSlow(startUptime, "startProcess: removing from pids map");
            mService.removePidLocked(app.getPid(), app);
            app.setBindMountPending(false);
            mService.mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);
            checkSlow(startUptime, "startProcess: done removing from pids map");
            app.setPid(0);
            app.setStartSeq(0);
        }
        // Clear any residual death recipient link as the ProcessRecord could be reused.
        app.unlinkDeathRecipient();
        app.setDyingPid(0);

        if (DEBUG_PROCESSES && mService.mProcessesOnHold.contains(app)) Slog.v(
                TAG_PROCESSES,
                "startProcessLocked removing on hold: " + app);
        mService.mProcessesOnHold.remove(app);

        checkSlow(startUptime, "startProcess: starting to update cpu stats");
        mService.updateCpuStats();
        checkSlow(startUptime, "startProcess: done updating cpu stats");

        try {
            final int userId = UserHandle.getUserId(app.uid);
            try {
                AppGlobals.getPackageManager().checkPackageStartable(app.info.packageName, userId);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }

            int uid = app.uid;
            int[] gids = null;
            int mountExternal = Zygote.MOUNT_EXTERNAL_NONE;
            boolean externalStorageAccess = false;
            if (!app.isolated) {
                int[] permGids = null;
                try {
                    checkSlow(startUptime, "startProcess: getting gids from package manager");
                    final IPackageManager pm = AppGlobals.getPackageManager();
                    permGids = pm.getPackageGids(app.info.packageName,
                            MATCH_DIRECT_BOOT_AUTO, app.userId);
                    StorageManagerInternal storageManagerInternal = LocalServices.getService(
                            StorageManagerInternal.class);
                    mountExternal = storageManagerInternal.getExternalStorageMountMode(uid,
                            app.info.packageName);
                    externalStorageAccess = storageManagerInternal.hasExternalStorageAccess(uid,
                            app.info.packageName);
                    if (mService.isAppFreezerExemptInstPkg()
                            && pm.checkPermission(Manifest.permission.INSTALL_PACKAGES,
                            app.info.packageName, userId)
                            == PackageManager.PERMISSION_GRANTED) {
                        Slog.i(TAG, app.info.packageName + " is exempt from freezer");
                        app.mOptRecord.setFreezeExempt(true);
                    }
                } catch (RemoteException e) {
                    throw e.rethrowAsRuntimeException();
                }

                // Remove any gids needed if the process has been denied permissions.
                // NOTE: eventually we should probably have the package manager pre-compute
                // this for us?
                if (app.processInfo != null && app.processInfo.deniedPermissions != null) {
                    for (int i = app.processInfo.deniedPermissions.size() - 1; i >= 0; i--) {
                        int[] denyGids = mService.mPackageManagerInt.getPermissionGids(
                                app.processInfo.deniedPermissions.valueAt(i), app.userId);
                        if (denyGids != null) {
                            for (int gid : denyGids) {
                                permGids = ArrayUtils.removeInt(permGids, gid);
                            }
                        }
                    }
                }

                gids = computeGidsForProcess(mountExternal, uid, permGids, externalStorageAccess);
            }
            app.setMountMode(mountExternal);
            checkSlow(startUptime, "startProcess: building args");
            if (app.getWindowProcessController().isFactoryTestProcess()) {
                uid = 0;
            }
            int runtimeFlags = 0;

            boolean debuggableFlag = (app.info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            boolean isProfileableByShell = app.info.isProfileableByShell();
            boolean isProfileable = app.info.isProfileable();

            if (app.isSdkSandbox) {
                ApplicationInfo clientInfo = app.getClientInfoForSdkSandbox();
                if (clientInfo != null) {
                    debuggableFlag |= (clientInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                    isProfileableByShell |= clientInfo.isProfileableByShell();
                    isProfileable |= clientInfo.isProfileable();
                }
            }

            if (debuggableFlag) {
                runtimeFlags |= Zygote.DEBUG_ENABLE_JDWP;
                runtimeFlags |= Zygote.DEBUG_ENABLE_PTRACE;
                runtimeFlags |= Zygote.DEBUG_JAVA_DEBUGGABLE;
                // Also turn on CheckJNI for debuggable apps. It's quite
                // awkward to turn on otherwise.
                runtimeFlags |= Zygote.DEBUG_ENABLE_CHECKJNI;

                // Check if the developer does not want ART verification
                if (android.provider.Settings.Global.getInt(mService.mContext.getContentResolver(),
                        android.provider.Settings.Global.ART_VERIFIER_VERIFY_DEBUGGABLE, 1) == 0) {
                    runtimeFlags |= Zygote.DISABLE_VERIFIER;
                    Slog.w(TAG_PROCESSES, app + ": ART verification disabled");
                }
            }
            // Run the app in safe mode if its manifest requests so or the
            // system is booted in safe mode.
            if ((app.info.flags & ApplicationInfo.FLAG_VM_SAFE_MODE) != 0 || mService.mSafeMode) {
                runtimeFlags |= Zygote.DEBUG_ENABLE_SAFEMODE;
            }
            if (isProfileableByShell) {
                runtimeFlags |= Zygote.PROFILE_FROM_SHELL;
            }
            if (isProfileable) {
                runtimeFlags |= Zygote.PROFILEABLE;
            }
            if ("1".equals(SystemProperties.get("debug.checkjni"))) {
                runtimeFlags |= Zygote.DEBUG_ENABLE_CHECKJNI;
            }
            String genDebugInfoProperty = SystemProperties.get("debug.generate-debug-info");
            if ("1".equals(genDebugInfoProperty) || "true".equals(genDebugInfoProperty)) {
                runtimeFlags |= Zygote.DEBUG_GENERATE_DEBUG_INFO;
            }
            String genMiniDebugInfoProperty = SystemProperties.get("dalvik.vm.minidebuginfo");
            if ("1".equals(genMiniDebugInfoProperty) || "true".equals(genMiniDebugInfoProperty)) {
                runtimeFlags |= Zygote.DEBUG_GENERATE_MINI_DEBUG_INFO;
            }
            if ("1".equals(SystemProperties.get("debug.jni.logging"))) {
                runtimeFlags |= Zygote.DEBUG_ENABLE_JNI_LOGGING;
            }
            if ("1".equals(SystemProperties.get("debug.assert"))) {
                runtimeFlags |= Zygote.DEBUG_ENABLE_ASSERT;
            }
            if ("1".equals(SystemProperties.get("debug.ignoreappsignalhandler"))) {
                runtimeFlags |= Zygote.DEBUG_IGNORE_APP_SIGNAL_HANDLER;
            }
            if (mService.mNativeDebuggingApp != null
                    && mService.mNativeDebuggingApp.equals(app.processName)) {
                // Enable all debug flags required by the native debugger.
                runtimeFlags |= Zygote.DEBUG_ALWAYS_JIT;          // Don't interpret anything
                runtimeFlags |= Zygote.DEBUG_GENERATE_DEBUG_INFO; // Generate debug info
                runtimeFlags |= Zygote.DEBUG_NATIVE_DEBUGGABLE;   // Disbale optimizations
                mService.mNativeDebuggingApp = null;
            }

            if (app.info.isEmbeddedDexUsed()) {
                runtimeFlags |= Zygote.ONLY_USE_SYSTEM_OAT_FILES;
            }

            if (!disableHiddenApiChecks && !mService.mHiddenApiBlacklist.isDisabled()) {
                app.info.maybeUpdateHiddenApiEnforcementPolicy(
                        mService.mHiddenApiBlacklist.getPolicy());
                @ApplicationInfo.HiddenApiEnforcementPolicy int policy =
                        app.info.getHiddenApiEnforcementPolicy();
                int policyBits = (policy << Zygote.API_ENFORCEMENT_POLICY_SHIFT);
                if ((policyBits & Zygote.API_ENFORCEMENT_POLICY_MASK) != policyBits) {
                    throw new IllegalStateException("Invalid API policy: " + policy);
                }
                runtimeFlags |= policyBits;

                if (disableTestApiChecks) {
                    runtimeFlags |= Zygote.DISABLE_TEST_API_ENFORCEMENT_POLICY;
                }
            }

            String useAppImageCache = SystemProperties.get(
                    PROPERTY_USE_APP_IMAGE_STARTUP_CACHE, "");
            // Property defaults to true currently.
            if (!TextUtils.isEmpty(useAppImageCache) && !useAppImageCache.equals("false")) {
                runtimeFlags |= Zygote.USE_APP_IMAGE_STARTUP_CACHE;
            }

            String invokeWith = null;
            if (debuggableFlag) {
                // Debuggable apps may include a wrapper script with their library directory.
                String wrapperFileName = app.info.nativeLibraryDir + "/wrap.sh";
                StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
                try {
                    if (new File(wrapperFileName).exists()) {
                        invokeWith = "/system/bin/logwrapper " + wrapperFileName;
                    }
                } finally {
                    StrictMode.setThreadPolicy(oldPolicy);
                }
            }

            String requiredAbi = (abiOverride != null) ? abiOverride : app.info.primaryCpuAbi;
            if (requiredAbi == null) {
                requiredAbi = Build.SUPPORTED_ABIS[0];
            }

            String instructionSet = null;
            if (app.info.primaryCpuAbi != null) {
                // If ABI override is specified, use the isa derived from the value of ABI override.
                // Otherwise, use the isa derived from primary ABI
                instructionSet = VMRuntime.getInstructionSet(requiredAbi);
            }

            app.setGids(gids);
            app.setRequiredAbi(requiredAbi);
            app.setInstructionSet(instructionSet);

            // If this was an external service, the package name and uid in the passed in
            // ApplicationInfo have been changed to match those of the calling package;
            // that will incorrectly apply compat feature overrides for the calling package instead
            // of the defining one.
            ApplicationInfo definingAppInfo;
            if (hostingRecord.getDefiningPackageName() != null) {
                definingAppInfo = new ApplicationInfo(app.info);
                definingAppInfo.packageName = hostingRecord.getDefiningPackageName();
                definingAppInfo.uid = uid;
            } else {
                definingAppInfo = app.info;
            }

            runtimeFlags |= Zygote.getMemorySafetyRuntimeFlags(
                    definingAppInfo, app.processInfo, instructionSet, mPlatformCompat);

            // the per-user SELinux context must be set
            if (TextUtils.isEmpty(app.info.seInfoUser)) {
                Slog.wtf(ActivityManagerService.TAG, "SELinux tag not defined",
                        new IllegalStateException("SELinux tag not defined for "
                                + app.info.packageName + " (uid " + app.uid + ")"));
            }

            String seInfo = updateSeInfo(app);

            // Start the process.  It will either succeed and return a result containing
            // the PID of the new process, or else throw a RuntimeException.
            final String entryPoint = "android.app.ActivityThread";

            return startProcessLocked(hostingRecord, entryPoint, app, uid, gids,
                    runtimeFlags, zygotePolicyFlags, mountExternal, seInfo, requiredAbi,
                    instructionSet, invokeWith, startUptime, startElapsedTime);
        } catch (RuntimeException e) {
            Slog.e(ActivityManagerService.TAG, "Failure starting process " + app.processName, e);

            // Something went very wrong while trying to start this process; one
            // common case is when the package is frozen due to an active
            // upgrade. To recover, clean up any active bookkeeping related to
            // starting this process. (We already invoked this method once when
            // the package was initially frozen through KILL_APPLICATION_MSG, so
            // it doesn't hurt to use it again.)
            mService.forceStopPackageLocked(app.info.packageName, UserHandle.getAppId(app.uid),
                    false, false, true, false, false, app.userId, "start failure");
            return false;
        }
    }

    @VisibleForTesting
    @GuardedBy("mService")
    String updateSeInfo(ProcessRecord app) {
        String extraInfo = "";
        // By the time the first the SDK sandbox process is started, device config service
        // should be available.
        if (app.isSdkSandbox
                && getProcessListSettingsListener().applySdkSandboxRestrictionsNext()) {
            extraInfo = APPLY_SDK_SANDBOX_NEXT_RESTRICTIONS;
        }

        return app.info.seInfo
                + (TextUtils.isEmpty(app.info.seInfoUser) ? "" : app.info.seInfoUser) + extraInfo;
    }


    @GuardedBy("mService")
    boolean startProcessLocked(HostingRecord hostingRecord, String entryPoint, ProcessRecord app,
            int uid, int[] gids, int runtimeFlags, int zygotePolicyFlags, int mountExternal,
            String seInfo, String requiredAbi, String instructionSet, String invokeWith,
            long startUptime, long startElapsedTime) {
        app.setPendingStart(true);
        app.setRemoved(false);
        synchronized (mProcLock) {
            app.setKilledByAm(false);
            app.setKilled(false);
        }
        if (app.getStartSeq() != 0) {
            Slog.wtf(TAG, "startProcessLocked processName:" + app.processName
                    + " with non-zero startSeq:" + app.getStartSeq());
        }
        if (app.getPid() != 0) {
            Slog.wtf(TAG, "startProcessLocked processName:" + app.processName
                    + " with non-zero pid:" + app.getPid());
        }
        app.setDisabledCompatChanges(null);
        if (mPlatformCompat != null) {
            app.setDisabledCompatChanges(mPlatformCompat.getDisabledChanges(app.info));
        }
        final long startSeq = ++mProcStartSeqCounter;
        app.setStartSeq(startSeq);
        app.setStartParams(uid, hostingRecord, seInfo, startUptime, startElapsedTime);
        app.setUsingWrapper(invokeWith != null
                || Zygote.getWrapProperty(app.processName) != null);
        mPendingStarts.put(startSeq, app);

        if (mService.mConstants.FLAG_PROCESS_START_ASYNC) {
            if (DEBUG_PROCESSES) Slog.i(TAG_PROCESSES,
                    "Posting procStart msg for " + app.toShortString());
            mService.mProcStartHandler.post(() -> handleProcessStart(
                    app, entryPoint, gids, runtimeFlags, zygotePolicyFlags, mountExternal,
                    requiredAbi, instructionSet, invokeWith, startSeq));
            return true;
        } else {
            try {
                final Process.ProcessStartResult startResult = startProcess(hostingRecord,
                        entryPoint, app,
                        uid, gids, runtimeFlags, zygotePolicyFlags, mountExternal, seInfo,
                        requiredAbi, instructionSet, invokeWith, startUptime);
                handleProcessStartedLocked(app, startResult.pid, startResult.usingWrapper,
                        startSeq, false);
            } catch (RuntimeException e) {
                Slog.e(ActivityManagerService.TAG, "Failure starting process "
                        + app.processName, e);
                app.setPendingStart(false);
                mService.forceStopPackageLocked(app.info.packageName, UserHandle.getAppId(app.uid),
                        false, false, true, false, false, app.userId, "start failure");
            }
            return app.getPid() > 0;
        }
    }

    /**
     * Main handler routine to start the given process from the ProcStartHandler.
     *
     * <p>Note: this function doesn't hold the global AM lock intentionally.</p>
     */
    private void handleProcessStart(final ProcessRecord app, final String entryPoint,
            final int[] gids, final int runtimeFlags, int zygotePolicyFlags,
            final int mountExternal, final String requiredAbi, final String instructionSet,
            final String invokeWith, final long startSeq) {
        final Runnable startRunnable = () -> {
            try {
                final Process.ProcessStartResult startResult = startProcess(app.getHostingRecord(),
                        entryPoint, app, app.getStartUid(), gids, runtimeFlags, zygotePolicyFlags,
                        mountExternal, app.getSeInfo(), requiredAbi, instructionSet, invokeWith,
                        app.getStartTime());

                synchronized (mService) {
                    handleProcessStartedLocked(app, startResult, startSeq);
                }
            } catch (RuntimeException e) {
                synchronized (mService) {
                    Slog.e(ActivityManagerService.TAG, "Failure starting process "
                            + app.processName, e);
                    mPendingStarts.remove(startSeq);
                    app.setPendingStart(false);
                    mService.forceStopPackageLocked(app.info.packageName,
                            UserHandle.getAppId(app.uid),
                            false, false, true, false, false, app.userId, "start failure");
                }
            }
        };
        // Use local reference since we are not using locks here
        final ProcessRecord predecessor = app.mPredecessor;
        if (predecessor != null && predecessor.getDyingPid() > 0) {
            handleProcessStartWithPredecessor(predecessor, startRunnable);
        } else {
            // Kick off the process start for real.
            startRunnable.run();
        }
    }

    /**
     * Handle the case where the given process is killed but still not gone, but we'd need to start
     * the new instance of it.
     */
    private void handleProcessStartWithPredecessor(final ProcessRecord predecessor,
            final Runnable successorStartRunnable) {
        // If there is a preceding instance of the process, wait for its death with a timeout.
        if (predecessor.mSuccessorStartRunnable != null) {
            // It's been watched already, this shouldn't happen.
            Slog.wtf(TAG, "We've been watching for the death of " + predecessor);
            return;
        }
        predecessor.mSuccessorStartRunnable = successorStartRunnable;
        mService.mProcStartHandler.sendMessageDelayed(mService.mProcStartHandler.obtainMessage(
                ProcStartHandler.MSG_PROCESS_KILL_TIMEOUT, predecessor),
                mService.mConstants.mProcessKillTimeoutMs);
    }

    static final class ProcStartHandler extends Handler {
        static final int MSG_PROCESS_DIED = 1;
        static final int MSG_PROCESS_KILL_TIMEOUT = 2;

        private final ActivityManagerService mService;

        ProcStartHandler(ActivityManagerService service, Looper looper) {
            super(looper);
            mService = service;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PROCESS_DIED:
                    mService.mProcessList.handlePredecessorProcDied((ProcessRecord) msg.obj);
                    break;
                case MSG_PROCESS_KILL_TIMEOUT:
                    synchronized (mService) {
                        mService.handleProcessStartOrKillTimeoutLocked((ProcessRecord) msg.obj,
                                /* isKillTimeout */ true);
                    }
                    break;
            }
        }
    }

    /**
     * Called when the dying process we're waiting for is really gone.
     */
    private void handlePredecessorProcDied(ProcessRecord app) {
        if (DEBUG_PROCESSES) {
            Slog.i(TAG, app.toString() + " is really gone now");
        }

        // Now kick off the subsequent process start if there is any.
        final Runnable start = app.mSuccessorStartRunnable;
        if (start != null) {
            app.mSuccessorStartRunnable = null;
            start.run();
        }
    }

    @GuardedBy("mService")
    public void killAppZygoteIfNeededLocked(AppZygote appZygote, boolean force) {
        final ApplicationInfo appInfo = appZygote.getAppInfo();
        ArrayList<ProcessRecord> zygoteProcesses = mAppZygoteProcesses.get(appZygote);
        if (zygoteProcesses != null && (force || zygoteProcesses.size() == 0)) {
            // Only remove if no longer in use now, or forced kill
            mAppZygotes.remove(appInfo.processName, appInfo.uid);
            mAppZygoteProcesses.remove(appZygote);
            mAppIsolatedUidRangeAllocator.freeUidRangeLocked(appInfo);
            appZygote.stopZygote();
        }
    }

    @GuardedBy("mService")
    private void removeProcessFromAppZygoteLocked(final ProcessRecord app) {
        // Free the isolated uid for this process
        final IsolatedUidRange appUidRange =
                mAppIsolatedUidRangeAllocator.getIsolatedUidRangeLocked(app.info.processName,
                        app.getHostingRecord().getDefiningUid());
        if (appUidRange != null) {
            appUidRange.freeIsolatedUidLocked(app.uid);
        }

        final AppZygote appZygote = mAppZygotes.get(app.info.processName,
                app.getHostingRecord().getDefiningUid());
        if (appZygote != null) {
            ArrayList<ProcessRecord> zygoteProcesses = mAppZygoteProcesses.get(appZygote);
            zygoteProcesses.remove(app);
            if (zygoteProcesses.size() == 0) {
                mService.mHandler.removeMessages(KILL_APP_ZYGOTE_MSG);
                if (app.isRemoved()) {
                    // If we stopped this process because the package hosting it was removed,
                    // there's no point in delaying the app zygote kill.
                    killAppZygoteIfNeededLocked(appZygote, false /* force */);
                } else {
                    Message msg = mService.mHandler.obtainMessage(KILL_APP_ZYGOTE_MSG);
                    msg.obj = appZygote;
                    mService.mHandler.sendMessageDelayed(msg, KILL_APP_ZYGOTE_DELAY_MS);
                }
            }
        }
    }

    private AppZygote createAppZygoteForProcessIfNeeded(final ProcessRecord app) {
        synchronized (mService) {
            // The UID for the app zygote should be the UID of the application hosting
            // the service.
            final int uid = app.getHostingRecord().getDefiningUid();
            AppZygote appZygote = mAppZygotes.get(app.info.processName, uid);
            final ArrayList<ProcessRecord> zygoteProcessList;
            if (appZygote == null) {
                if (DEBUG_PROCESSES) {
                    Slog.d(TAG_PROCESSES, "Creating new app zygote.");
                }
                final IsolatedUidRange uidRange =
                        mAppIsolatedUidRangeAllocator.getIsolatedUidRangeLocked(
                                app.info.processName, app.getHostingRecord().getDefiningUid());
                final int userId = UserHandle.getUserId(uid);
                // Create the app-zygote and provide it with the UID-range it's allowed
                // to setresuid/setresgid to.
                final int firstUid = UserHandle.getUid(userId, uidRange.mFirstUid);
                final int lastUid = UserHandle.getUid(userId, uidRange.mLastUid);
                ApplicationInfo appInfo = new ApplicationInfo(app.info);
                // If this was an external service, the package name and uid in the passed in
                // ApplicationInfo have been changed to match those of the calling package;
                // that is not what we want for the AppZygote though, which needs to have the
                // packageName and uid of the defining application. This is because the
                // preloading only makes sense in the context of the defining application,
                // not the calling one.
                appInfo.packageName = app.getHostingRecord().getDefiningPackageName();
                appInfo.uid = uid;
                appZygote = new AppZygote(appInfo, app.processInfo, uid, firstUid, lastUid);
                mAppZygotes.put(app.info.processName, uid, appZygote);
                zygoteProcessList = new ArrayList<ProcessRecord>();
                mAppZygoteProcesses.put(appZygote, zygoteProcessList);
            } else {
                if (DEBUG_PROCESSES) {
                    Slog.d(TAG_PROCESSES, "Reusing existing app zygote.");
                }
                mService.mHandler.removeMessages(KILL_APP_ZYGOTE_MSG, appZygote);
                zygoteProcessList = mAppZygoteProcesses.get(appZygote);
            }
            // Note that we already add the app to mAppZygoteProcesses here;
            // this is so that another thread can't come in and kill the zygote
            // before we've even tried to start the process. If the process launch
            // goes wrong, we'll clean this up in removeProcessNameLocked()
            zygoteProcessList.add(app);

            return appZygote;
        }
    }

    private Map<String, Pair<String, Long>> getPackageAppDataInfoMap(PackageManagerInternal pmInt,
            String[] packages, int uid) {
        Map<String, Pair<String, Long>> result = new ArrayMap<>(packages.length);
        int userId = UserHandle.getUserId(uid);
        for (String packageName : packages) {
            final PackageStateInternal packageState = pmInt.getPackageStateInternal(packageName);
            if (packageState == null) {
                Slog.w(TAG, "Unknown package:" + packageName);
                continue;
            }
            String volumeUuid = packageState.getVolumeUuid();
            long inode = packageState.getUserStateOrDefault(userId).getCeDataInode();
            if (inode == 0) {
                Slog.w(TAG, packageName + " inode == 0 (b/152760674)");
                return null;
            }
            result.put(packageName, Pair.create(volumeUuid, inode));
        }

        return result;
    }

    private boolean needsStorageDataIsolation(StorageManagerInternal storageManagerInternal,
            ProcessRecord app) {
        final int mountMode = app.getMountMode();
        return mVoldAppDataIsolationEnabled && UserHandle.isApp(app.uid)
                && !storageManagerInternal.isExternalStorageService(app.uid)
                // Special mounting mode doesn't need to have data isolation as they won't
                // access /mnt/user anyway.
                && mountMode != Zygote.MOUNT_EXTERNAL_ANDROID_WRITABLE
                && mountMode != Zygote.MOUNT_EXTERNAL_PASS_THROUGH
                && mountMode != Zygote.MOUNT_EXTERNAL_INSTALLER
                && mountMode != Zygote.MOUNT_EXTERNAL_NONE;
    }

    private Process.ProcessStartResult startProcess(HostingRecord hostingRecord, String entryPoint,
            ProcessRecord app, int uid, int[] gids, int runtimeFlags, int zygotePolicyFlags,
            int mountExternal, String seInfo, String requiredAbi, String instructionSet,
            String invokeWith, long startTime) {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "Start proc: " +
                    app.processName);
            checkSlow(startTime, "startProcess: asking zygote to start proc");
            final boolean isTopApp = hostingRecord.isTopApp();
            if (isTopApp) {
                // Use has-foreground-activities as a temporary hint so the current scheduling
                // group won't be lost when the process is attaching. The actual state will be
                // refreshed when computing oom-adj.
                app.mState.setHasForegroundActivities(true);
            }

            Map<String, Pair<String, Long>> pkgDataInfoMap;
            Map<String, Pair<String, Long>> allowlistedAppDataInfoMap;
            boolean bindMountAppStorageDirs = false;
            boolean bindMountAppsData = mAppDataIsolationEnabled
                    && (UserHandle.isApp(app.uid) || UserHandle.isIsolated(app.uid)
                        || app.isSdkSandbox)
                    && mPlatformCompat.isChangeEnabled(APP_DATA_DIRECTORY_ISOLATION, app.info);

            // Get all packages belongs to the same shared uid. sharedPackages is empty array
            // if it doesn't have shared uid.
            final PackageManagerInternal pmInt = mService.getPackageManagerInternal();

            // In the case of sdk sandbox, the pkgDataInfoMap of only the client app associated with
            // the sandbox is required to handle app visibility restrictions for the sandbox.
            final String[] targetPackagesList;
            if (app.isSdkSandbox) {
                targetPackagesList = new String[]{app.sdkSandboxClientAppPackage};
            } else {
                final String[] sharedPackages = pmInt.getSharedUserPackagesForPackage(
                        app.info.packageName, app.userId);
                targetPackagesList = sharedPackages.length == 0
                        ? new String[]{app.info.packageName} : sharedPackages;
            }

            final boolean hasAppStorage = hasAppStorage(pmInt, app.info.packageName);

            pkgDataInfoMap = getPackageAppDataInfoMap(pmInt, targetPackagesList, uid);
            if (pkgDataInfoMap == null) {
                // TODO(b/152760674): Handle inode == 0 case properly, now we just give it a
                // tmp free pass.
                bindMountAppsData = false;
            }

            // Remove all packages in pkgDataInfoMap from mAppDataIsolationAllowlistedApps, so
            // it won't be mounted twice.
            final Set<String> allowlistedApps = new ArraySet<>(mAppDataIsolationAllowlistedApps);
            for (String pkg : targetPackagesList) {
                allowlistedApps.remove(pkg);
            }

            allowlistedAppDataInfoMap = getPackageAppDataInfoMap(pmInt,
                    allowlistedApps.toArray(new String[0]), uid);
            if (allowlistedAppDataInfoMap == null) {
                // TODO(b/152760674): Handle inode == 0 case properly, now we just give it a
                // tmp free pass.
                bindMountAppsData = false;
            }

            if (!hasAppStorage && !app.isSdkSandbox) {
                bindMountAppsData = false;
                pkgDataInfoMap = null;
                allowlistedAppDataInfoMap = null;
            }

            int userId = UserHandle.getUserId(uid);
            StorageManagerInternal storageManagerInternal = LocalServices.getService(
                    StorageManagerInternal.class);
            if (needsStorageDataIsolation(storageManagerInternal, app)) {
                // We will run prepareStorageDirs() after we trigger zygote fork, so it won't
                // slow down app starting speed as those dirs might not be cached.
                if (pkgDataInfoMap != null && storageManagerInternal.isFuseMounted(userId)) {
                    bindMountAppStorageDirs = true;
                } else {
                    // Fuse is not mounted or inode == 0,
                    // so we won't mount it in zygote, but resume the mount after unlocking device.
                    app.setBindMountPending(true);
                    bindMountAppStorageDirs = false;
                }
            }

            // If it's an isolated process, it should not even mount its own app data directories,
            // since it has no access to them anyway.
            if (app.isolated) {
                pkgDataInfoMap = null;
                allowlistedAppDataInfoMap = null;
            }

            AppStateTracker ast = mService.mServices.mAppStateTracker;
            if (ast != null) {
                final boolean inBgRestricted = ast.isAppBackgroundRestricted(
                        app.info.uid, app.info.packageName);
                if (inBgRestricted) {
                    synchronized (mService) {
                        mAppsInBackgroundRestricted.add(app);
                    }
                }
                app.mState.setBackgroundRestricted(inBgRestricted);
            }

            final Process.ProcessStartResult startResult;
            boolean regularZygote = false;
            app.mProcessGroupCreated = false;
            app.mSkipProcessGroupCreation = false;
            if (hostingRecord.usesWebviewZygote()) {
                startResult = startWebView(entryPoint,
                        app.processName, uid, uid, gids, runtimeFlags, mountExternal,
                        app.info.targetSdkVersion, seInfo, requiredAbi, instructionSet,
                        app.info.dataDir, null, app.info.packageName,
                        app.getDisabledCompatChanges(),
                        new String[]{PROC_START_SEQ_IDENT + app.getStartSeq()});
            } else if (hostingRecord.usesAppZygote()) {
                final AppZygote appZygote = createAppZygoteForProcessIfNeeded(app);

                // We can't isolate app data and storage data as parent zygote already did that.
                startResult = appZygote.getProcess().start(entryPoint,
                        app.processName, uid, uid, gids, runtimeFlags, mountExternal,
                        app.info.targetSdkVersion, seInfo, requiredAbi, instructionSet,
                        app.info.dataDir, null, app.info.packageName,
                        /*zygotePolicyFlags=*/ ZYGOTE_POLICY_FLAG_EMPTY, isTopApp,
                        app.getDisabledCompatChanges(), pkgDataInfoMap, allowlistedAppDataInfoMap,
                        false, false,
                        new String[]{PROC_START_SEQ_IDENT + app.getStartSeq()});
            } else {
                regularZygote = true;
                startResult = Process.start(entryPoint,
                        app.processName, uid, uid, gids, runtimeFlags, mountExternal,
                        app.info.targetSdkVersion, seInfo, requiredAbi, instructionSet,
                        app.info.dataDir, invokeWith, app.info.packageName, zygotePolicyFlags,
                        isTopApp, app.getDisabledCompatChanges(), pkgDataInfoMap,
                        allowlistedAppDataInfoMap, bindMountAppsData, bindMountAppStorageDirs,
                        new String[]{PROC_START_SEQ_IDENT + app.getStartSeq()});
                // By now the process group should have been created by zygote.
                app.mProcessGroupCreated = true;
            }

            if (!regularZygote) {
                // webview and app zygote don't have the permission to create the nodes
                synchronized (app) {
                    if (!app.mSkipProcessGroupCreation) {
                        // If we're not told to skip the process group creation, go create it.
                        final int res = Process.createProcessGroup(uid, startResult.pid);
                        if (res < 0) {
                            if (res == -OsConstants.ESRCH) {
                                Slog.e(ActivityManagerService.TAG,
                                        "Unable to create process group for "
                                        + app.processName + " (" + startResult.pid + ")");
                            } else {
                                throw new AssertionError("Unable to create process group for "
                                    + app.processName + " (" + startResult.pid + ")");
                            }
                        } else {
                            app.mProcessGroupCreated = true;
                        }
                    }
                }
            }

            // This runs after Process.start() as this method may block app process starting time
            // if dir is not cached. Running this method after Process.start() can make it
            // cache the dir asynchronously, so zygote can use it without waiting for it.
            if (bindMountAppStorageDirs) {
                storageManagerInternal.prepareStorageDirs(userId, pkgDataInfoMap.keySet(),
                        app.processName);
            }
            checkSlow(startTime, "startProcess: returned from zygote!");
            return startResult;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    private boolean hasAppStorage(PackageManagerInternal pmInt, String packageName) {
        final AndroidPackage pkg = pmInt.getPackage(packageName);
        if (pkg == null) {
            Slog.w(TAG, "Unknown package " + packageName);
            return false;
        }
        final PackageManager.Property noAppStorageProp =
                    pkg.getProperties().get(PackageManager.PROPERTY_NO_APP_DATA_STORAGE);
        return noAppStorageProp == null || !noAppStorageProp.getBoolean();
    }

    @GuardedBy("mService")
    void startProcessLocked(ProcessRecord app, HostingRecord hostingRecord, int zygotePolicyFlags) {
        startProcessLocked(app, hostingRecord, zygotePolicyFlags, null /* abiOverride */);
    }

    @GuardedBy("mService")
    boolean startProcessLocked(ProcessRecord app, HostingRecord hostingRecord,
            int zygotePolicyFlags, String abiOverride) {
        return startProcessLocked(app, hostingRecord, zygotePolicyFlags,
                false /* disableHiddenApiChecks */, false /* disableTestApiChecks */,
                abiOverride);
    }

    @GuardedBy("mService")
    ProcessRecord startProcessLocked(String processName, ApplicationInfo info,
            boolean knownToBeDead, int intentFlags, HostingRecord hostingRecord,
            int zygotePolicyFlags, boolean allowWhileBooting, boolean isolated, int isolatedUid,
            boolean isSdkSandbox, int sdkSandboxUid, String sdkSandboxClientAppPackage,
            String abiOverride, String entryPoint, String[] entryPointArgs, Runnable crashHandler) {
        long startTime = SystemClock.uptimeMillis();
        ProcessRecord app;
        if (!isolated) {
            app = getProcessRecordLocked(processName, info.uid);
            checkSlow(startTime, "startProcess: after getProcessRecord");

            if ((intentFlags & Intent.FLAG_FROM_BACKGROUND) != 0) {
                // If we are in the background, then check to see if this process
                // is bad.  If so, we will just silently fail.
                if (mService.mAppErrors.isBadProcess(processName, info.uid)) {
                    if (DEBUG_PROCESSES) Slog.v(TAG, "Bad process: " + info.uid
                            + "/" + processName);
                    return null;
                }
            } else {
                // When the user is explicitly starting a process, then clear its
                // crash count so that we won't make it bad until they see at
                // least one crash dialog again, and make the process good again
                // if it had been bad.
                if (DEBUG_PROCESSES) Slog.v(TAG, "Clearing bad process: " + info.uid
                        + "/" + processName);
                mService.mAppErrors.resetProcessCrashTime(processName, info.uid);
                if (mService.mAppErrors.isBadProcess(processName, info.uid)) {
                    EventLog.writeEvent(EventLogTags.AM_PROC_GOOD,
                            UserHandle.getUserId(info.uid), info.uid,
                            info.processName);
                    mService.mAppErrors.clearBadProcess(processName, info.uid);
                    if (app != null) {
                        app.mErrorState.setBad(false);
                    }
                }
            }
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
        if (DEBUG_PROCESSES) Slog.v(TAG_PROCESSES, "startProcess: name=" + processName
                + " app=" + app + " knownToBeDead=" + knownToBeDead
                + " thread=" + (app != null ? app.getThread() : null)
                + " pid=" + (app != null ? app.getPid() : -1));
        ProcessRecord predecessor = null;
        if (app != null && app.getPid() > 0) {
            if ((!knownToBeDead && !app.isKilled()) || app.getThread() == null) {
                // We already have the app running, or are waiting for it to
                // come up (we have a pid but not yet its thread), so keep it.
                if (DEBUG_PROCESSES) Slog.v(TAG_PROCESSES, "App already running: " + app);
                // If this is a new package in the process, add the package to the list
                app.addPackage(info.packageName, info.longVersionCode, mService.mProcessStats);
                checkSlow(startTime, "startProcess: done, added package to proc");
                return app;
            }

            // An application record is attached to a previous process,
            // clean it up now.
            if (DEBUG_PROCESSES) Slog.v(TAG_PROCESSES, "App died: " + app);
            checkSlow(startTime, "startProcess: bad proc running, killing");
            ProcessList.killProcessGroup(app.uid, app.getPid());
            checkSlow(startTime, "startProcess: done killing old proc");

            if (!app.isKilled()) {
                // Throw a wtf if it's not killed
                Slog.wtf(TAG_PROCESSES, app.toString() + " is attached to a previous process");
            } else {
                Slog.w(TAG_PROCESSES, app.toString() + " is attached to a previous process");
            }
            // We are not going to re-use the ProcessRecord, as we haven't dealt with the cleanup
            // routine of it yet, but we'd set it as the predecessor of the new process.
            predecessor = app;
            app = null;
        } else if (!isolated) {
            // This app may have been removed from process name maps, probably because we killed it
            // and did the cleanup before the actual death notification. Check the dying processes.
            predecessor = mDyingProcesses.get(processName, info.uid);
            if (predecessor != null) {
                // The process record could have existed but its pid is set to 0. In this case,
                // the 'app' and 'predecessor' could end up pointing to the same instance;
                // so make sure we check this case here.
                if (app != null && app != predecessor) {
                    app.mPredecessor = predecessor;
                    predecessor.mSuccessor = app;
                } else {
                    app = null;
                }
                Slog.w(TAG_PROCESSES, predecessor.toString() + " is attached to a previous process "
                        + predecessor.getDyingPid());
            }
        }

        if (app == null) {
            checkSlow(startTime, "startProcess: creating new process record");
            app = newProcessRecordLocked(info, processName, isolated, isolatedUid, isSdkSandbox,
                    sdkSandboxUid, sdkSandboxClientAppPackage, hostingRecord);
            if (app == null) {
                Slog.w(TAG, "Failed making new process record for "
                        + processName + "/" + info.uid + " isolated=" + isolated);
                return null;
            }
            app.mErrorState.setCrashHandler(crashHandler);
            app.setIsolatedEntryPoint(entryPoint);
            app.setIsolatedEntryPointArgs(entryPointArgs);
            if (predecessor != null) {
                app.mPredecessor = predecessor;
                predecessor.mSuccessor = app;
            }
            checkSlow(startTime, "startProcess: done creating new process record");
        } else {
            // If this is a new package in the process, add the package to the list
            app.addPackage(info.packageName, info.longVersionCode, mService.mProcessStats);
            checkSlow(startTime, "startProcess: added package to existing proc");
        }

        // If the system is not ready yet, then hold off on starting this
        // process until it is.
        if (!mService.mProcessesReady
                && !mService.isAllowedWhileBooting(info)
                && !allowWhileBooting) {
            if (!mService.mProcessesOnHold.contains(app)) {
                mService.mProcessesOnHold.add(app);
            }
            if (DEBUG_PROCESSES) Slog.v(TAG_PROCESSES,
                    "System not ready, putting on hold: " + app);
            checkSlow(startTime, "startProcess: returning with proc on hold");
            return app;
        }

        checkSlow(startTime, "startProcess: stepping in to startProcess");
        final boolean success =
                startProcessLocked(app, hostingRecord, zygotePolicyFlags, abiOverride);
        checkSlow(startTime, "startProcess: done starting proc!");
        return success ? app : null;
    }

    @GuardedBy("mService")
    String isProcStartValidLocked(ProcessRecord app, long expectedStartSeq) {
        StringBuilder sb = null;
        if (app.isKilledByAm()) {
            if (sb == null) sb = new StringBuilder();
            sb.append("killedByAm=true;");
        }
        if (mProcessNames.get(app.processName, app.uid) != app) {
            if (sb == null) sb = new StringBuilder();
            sb.append("No entry in mProcessNames;");
        }
        if (!app.isPendingStart()) {
            if (sb == null) sb = new StringBuilder();
            sb.append("pendingStart=false;");
        }
        if (app.getStartSeq() > expectedStartSeq) {
            if (sb == null) sb = new StringBuilder();
            sb.append("seq=" + app.getStartSeq() + ",expected=" + expectedStartSeq + ";");
        }
        try {
            AppGlobals.getPackageManager().checkPackageStartable(app.info.packageName, app.userId);
        } catch (RemoteException e) {
            // unexpected; ignore
        } catch (SecurityException e) {
            if (mService.mConstants.FLAG_PROCESS_START_ASYNC) {
                if (sb == null) sb = new StringBuilder();
                sb.append("Package is frozen;");
            } else {
                // we're not being started async and so should throw to the caller.
                throw e;
            }
        }
        return sb == null ? null : sb.toString();
    }

    @GuardedBy("mService")
    private boolean handleProcessStartedLocked(ProcessRecord pending,
            Process.ProcessStartResult startResult, long expectedStartSeq) {
        // Indicates that this process start has been taken care of.
        if (mPendingStarts.get(expectedStartSeq) == null) {
            if (pending.getPid() == startResult.pid) {
                pending.setUsingWrapper(startResult.usingWrapper);
                // TODO: Update already existing clients of usingWrapper
            }
            return false;
        }
        return handleProcessStartedLocked(pending, startResult.pid, startResult.usingWrapper,
                expectedStartSeq, false);
    }

    @GuardedBy("mService")
    boolean handleProcessStartedLocked(ProcessRecord app, int pid, boolean usingWrapper,
            long expectedStartSeq, boolean procAttached) {
        mPendingStarts.remove(expectedStartSeq);
        final String reason = isProcStartValidLocked(app, expectedStartSeq);
        if (reason != null) {
            Slog.w(TAG_PROCESSES, app + " start not valid, killing pid=" +
                    pid
                    + ", " + reason);
            app.setPendingStart(false);
            killProcessQuiet(pid);
            final int appPid = app.getPid();
            if (appPid != 0) {
                Process.killProcessGroup(app.uid, appPid);
            }
            noteAppKill(app, ApplicationExitInfo.REASON_OTHER,
                    ApplicationExitInfo.SUBREASON_INVALID_START, reason);
            return false;
        }
        mService.mBatteryStatsService.noteProcessStart(app.processName, app.info.uid);
        checkSlow(app.getStartTime(), "startProcess: done updating battery stats");

        EventLog.writeEvent(EventLogTags.AM_PROC_START,
                UserHandle.getUserId(app.getStartUid()), pid, app.getStartUid(),
                app.processName, app.getHostingRecord().getType(),
                app.getHostingRecord().getName() != null ? app.getHostingRecord().getName() : "");

        try {
            AppGlobals.getPackageManager().logAppProcessStartIfNeeded(app.info.packageName,
                    app.processName, app.uid, app.getSeInfo(), app.info.sourceDir, pid);
        } catch (RemoteException ex) {
            // Ignore
        }

        Watchdog.getInstance().processStarted(app.processName, pid);

        checkSlow(app.getStartTime(), "startProcess: building log message");
        StringBuilder buf = mStringBuilder;
        buf.setLength(0);
        buf.append("Start proc ");
        buf.append(pid);
        buf.append(':');
        buf.append(app.processName);
        buf.append('/');
        UserHandle.formatUid(buf, app.getStartUid());
        if (app.getIsolatedEntryPoint() != null) {
            buf.append(" [");
            buf.append(app.getIsolatedEntryPoint());
            buf.append("]");
        }
        buf.append(" for ");
        buf.append(app.getHostingRecord().getType());
        if (app.getHostingRecord().getName() != null) {
            buf.append(" ");
            buf.append(app.getHostingRecord().getName());
        }
        mService.reportUidInfoMessageLocked(TAG, buf.toString(), app.getStartUid());
        synchronized (mProcLock) {
            app.setPid(pid);
            app.setUsingWrapper(usingWrapper);
            app.setPendingStart(false);
        }
        checkSlow(app.getStartTime(), "startProcess: starting to update pids map");
        ProcessRecord oldApp;
        synchronized (mService.mPidsSelfLocked) {
            oldApp = mService.mPidsSelfLocked.get(pid);
        }
        // If there is already an app occupying that pid that hasn't been cleaned up
        if (oldApp != null && !app.isolated) {
            // Clean up anything relating to this pid first
            Slog.wtf(TAG, "handleProcessStartedLocked process:" + app.processName
                    + " startSeq:" + app.getStartSeq()
                    + " pid:" + pid
                    + " belongs to another existing app:" + oldApp.processName
                    + " startSeq:" + oldApp.getStartSeq());
            mService.cleanUpApplicationRecordLocked(oldApp, pid, false, false, -1,
                    true /*replacingPid*/, false /* fromBinderDied */);
        }
        mService.addPidLocked(app);
        synchronized (mService.mPidsSelfLocked) {
            if (!procAttached) {
                Message msg = mService.mHandler.obtainMessage(PROC_START_TIMEOUT_MSG);
                msg.obj = app;
                mService.mHandler.sendMessageDelayed(msg, usingWrapper
                        ? PROC_START_TIMEOUT_WITH_WRAPPER : PROC_START_TIMEOUT);
            }
        }
        checkSlow(app.getStartTime(), "startProcess: done updating pids map");
        return true;
    }

    @GuardedBy("mService")
    void removeLruProcessLocked(ProcessRecord app) {
        int lrui = mLruProcesses.lastIndexOf(app);
        if (lrui >= 0) {
            synchronized (mProcLock) {
                if (!app.isKilled()) {
                    if (app.isPersistent()) {
                        Slog.w(TAG, "Removing persistent process that hasn't been killed: " + app);
                    } else {
                        Slog.wtfStack(TAG, "Removing process that hasn't been killed: " + app);
                        if (app.getPid() > 0) {
                            killProcessQuiet(app.getPid());
                            ProcessList.killProcessGroup(app.uid, app.getPid());
                            noteAppKill(app, ApplicationExitInfo.REASON_OTHER,
                                    ApplicationExitInfo.SUBREASON_REMOVE_LRU, "hasn't been killed");
                        } else {
                            app.setPendingStart(false);
                        }
                    }
                }
                if (lrui < mLruProcessActivityStart) {
                    mLruProcessActivityStart--;
                }
                if (lrui < mLruProcessServiceStart) {
                    mLruProcessServiceStart--;
                }
                mLruProcesses.remove(lrui);
            }
        }
        mService.removeOomAdjTargetLocked(app, true);
    }

    @GuardedBy({"mService", "mProcLock"})
    boolean killPackageProcessesLSP(String packageName, int appId, int userId, int minOomAdj,
            int reasonCode, int subReason, String reason) {
        return killPackageProcessesLSP(packageName, appId, userId, minOomAdj,
                false /* callerWillRestart */, true /* allowRestart */, true /* doit */,
                false /* evenPersistent */, false /* setRemoved */, false /* uninstalling */,
                reasonCode, subReason, reason);
    }

    @GuardedBy("mService")
    void killAppZygotesLocked(String packageName, int appId, int userId, boolean force) {
        // See if there are any app zygotes running for this packageName / UID combination,
        // and kill it if so.
        final ArrayList<AppZygote> zygotesToKill = new ArrayList<>();
        for (SparseArray<AppZygote> appZygotes : mAppZygotes.getMap().values()) {
            for (int i = 0; i < appZygotes.size(); ++i) {
                final int appZygoteUid = appZygotes.keyAt(i);
                if (userId != UserHandle.USER_ALL && UserHandle.getUserId(appZygoteUid) != userId) {
                    continue;
                }
                if (appId >= 0 && UserHandle.getAppId(appZygoteUid) != appId) {
                    continue;
                }
                final AppZygote appZygote = appZygotes.valueAt(i);
                if (packageName != null
                        && !packageName.equals(appZygote.getAppInfo().packageName)) {
                    continue;
                }
                zygotesToKill.add(appZygote);
            }
        }
        for (AppZygote appZygote : zygotesToKill) {
            killAppZygoteIfNeededLocked(appZygote, force);
        }
    }

    private static boolean freezePackageCgroup(int packageUID, boolean freeze) {
        try {
            Process.freezeCgroupUid(packageUID, freeze);
        } catch (RuntimeException e) {
            final String logtxt = freeze ? "freeze" : "unfreeze";
            Slog.e(TAG, "Unable to " + logtxt + " cgroup uid: " + packageUID + ": " + e);
            return false;
        }
        return true;
    }

    private static void freezeBinderAndPackageCgroup(ArrayList<Pair<ProcessRecord, Boolean>> procs,
                                                     int packageUID) {
        // Freeze all binder processes under the target UID (whose cgroup is about to be frozen).
        // Since we're going to kill these, we don't need to unfreze them later.
        // The procs list may not include all processes under the UID cgroup, but unincluded
        // processes (forks) should not be Binder users.
        int N = procs.size();
        for (int i = 0; i < N; i++) {
            final int uid = procs.get(i).first.uid;
            final int pid = procs.get(i).first.getPid();
            int nRetries = 0;
            // We only freeze the cgroup of the target package, so we do not need to freeze the
            // Binder interfaces of dependant processes in other UIDs.
            if (pid > 0 && uid == packageUID) {
                try {
                    int rc;
                    do {
                        rc = CachedAppOptimizer.freezeBinder(pid, true, 10 /* timeout_ms */);
                    } while (rc == -EAGAIN && nRetries++ < 1);
                    if (rc != 0) Slog.e(TAG, "Unable to freeze binder for " + pid + ": " + rc);
                } catch (RuntimeException e) {
                    Slog.e(TAG, "Unable to freeze binder for " + pid + ": " + e);
                }
            }
        }

        // We freeze the entire UID (parent) cgroup so that newly-specialized processes also freeze
        // despite being added to a new child cgroup. The cgroups of package dependant processes are
        // not frozen, since it's possible this would freeze processes with no dependency on the
        // package being killed here.
        freezePackageCgroup(packageUID, true);
    }

    @GuardedBy({"mService", "mProcLock"})
    boolean killPackageProcessesLSP(String packageName, int appId,
            int userId, int minOomAdj, boolean callerWillRestart, boolean allowRestart,
            boolean doit, boolean evenPersistent, boolean setRemoved, boolean uninstalling,
            int reasonCode, int subReason, String reason) {
        final PackageManagerInternal pm = mService.getPackageManagerInternal();
        final ArrayList<Pair<ProcessRecord, Boolean>> procs = new ArrayList<>();

        // Remove all processes this package may have touched: all with the
        // same UID (except for the system or root user), and all whose name
        // matches the package name.
        final int NP = mProcessNames.getMap().size();
        for (int ip = 0; ip < NP; ip++) {
            SparseArray<ProcessRecord> apps = mProcessNames.getMap().valueAt(ip);
            final int NA = apps.size();
            for (int ia = 0; ia < NA; ia++) {
                ProcessRecord app = apps.valueAt(ia);
                if (app.isPersistent() && !evenPersistent) {
                    // we don't kill persistent processes
                    continue;
                }
                if (app.isRemoved()) {
                    if (doit) {
                        boolean shouldAllowRestart = false;
                        if (!uninstalling && packageName != null) {
                            // This package has a dependency on the given package being stopped,
                            // while it's not being frozen nor uninstalled, allow to restart it.
                            shouldAllowRestart = !app.getPkgList().containsKey(packageName)
                                    && app.getPkgDeps() != null
                                    && app.getPkgDeps().contains(packageName)
                                    && app.info != null
                                    && !pm.isPackageFrozen(app.info.packageName, app.uid,
                                            app.userId);
                        }
                        procs.add(new Pair<>(app, shouldAllowRestart));
                    }
                    continue;
                }

                // Skip process if it doesn't meet our oom adj requirement.
                if (app.mState.getSetAdj() < minOomAdj) {
                    // Note it is still possible to have a process with oom adj 0 in the killed
                    // processes, but it does not mean misjudgment. E.g. a bound service process
                    // and its client activity process are both in the background, so they are
                    // collected to be killed. If the client activity is killed first, the service
                    // may be scheduled to unbind and become an executing service (oom adj 0).
                    continue;
                }

                boolean shouldAllowRestart = false;

                // If no package is specified, we call all processes under the
                // given user id.
                if (packageName == null) {
                    if (userId != UserHandle.USER_ALL && app.userId != userId) {
                        continue;
                    }
                    if (appId >= 0 && UserHandle.getAppId(app.uid) != appId) {
                        continue;
                    }
                    // Package has been specified, we want to hit all processes
                    // that match it.  We need to qualify this by the processes
                    // that are running under the specified app and user ID.
                } else {
                    final boolean isDep = app.getPkgDeps() != null
                            && app.getPkgDeps().contains(packageName);
                    if (!isDep && UserHandle.getAppId(app.uid) != appId) {
                        continue;
                    }
                    if (userId != UserHandle.USER_ALL && app.userId != userId) {
                        continue;
                    }
                    final boolean isInPkgList = app.getPkgList().containsKey(packageName);
                    if (!isInPkgList && !isDep) {
                        continue;
                    }
                    if (!isInPkgList && isDep && !uninstalling && app.info != null
                            && !pm.isPackageFrozen(app.info.packageName, app.uid, app.userId)) {
                        // This package has a dependency on the given package being stopped,
                        // while it's not being frozen nor uninstalled, allow to restart it.
                        shouldAllowRestart = true;
                    }
                }

                // Process has passed all conditions, kill it!
                if (!doit) {
                    return true;
                }
                if (setRemoved) {
                    app.setRemoved(true);
                }
                procs.add(new Pair<>(app, shouldAllowRestart));
            }
        }

        final int packageUID = UserHandle.getUid(userId, appId);
        final boolean doFreeze = appId >= Process.FIRST_APPLICATION_UID
                              && appId <= Process.LAST_APPLICATION_UID;
        if (doFreeze) {
            freezeBinderAndPackageCgroup(procs, packageUID);
        }

        int N = procs.size();
        for (int i=0; i<N; i++) {
            final Pair<ProcessRecord, Boolean> proc = procs.get(i);
            removeProcessLocked(proc.first, callerWillRestart, allowRestart || proc.second,
                    reasonCode, subReason, reason, !doFreeze /* async */);
        }
        killAppZygotesLocked(packageName, appId, userId, false /* force */);
        mService.updateOomAdjLocked(OOM_ADJ_REASON_PROCESS_END);
        if (doFreeze) {
            freezePackageCgroup(packageUID, false);
        }
        return N > 0;
    }

    @GuardedBy("mService")
    boolean removeProcessLocked(ProcessRecord app,
            boolean callerWillRestart, boolean allowRestart, int reasonCode, String reason) {
        return removeProcessLocked(app, callerWillRestart, allowRestart, reasonCode,
                ApplicationExitInfo.SUBREASON_UNKNOWN, reason, true);
    }

    @GuardedBy("mService")
    boolean removeProcessLocked(ProcessRecord app, boolean callerWillRestart,
            boolean allowRestart, int reasonCode, int subReason, String reason) {
        return removeProcessLocked(app, callerWillRestart, allowRestart, reasonCode, subReason,
                reason, true);
    }

    @GuardedBy("mService")
    boolean removeProcessLocked(ProcessRecord app, boolean callerWillRestart,
            boolean allowRestart, int reasonCode, int subReason, String reason, boolean async) {
        final String name = app.processName;
        final int uid = app.uid;
        if (DEBUG_PROCESSES) Slog.d(TAG_PROCESSES,
                "Force removing proc " + app.toShortString() + " (" + name + "/" + uid + ")");

        ProcessRecord old = mProcessNames.get(name, uid);
        if (old != app) {
            // This process is no longer active, so nothing to do.
            Slog.w(TAG, "Ignoring remove of inactive process: " + app);
            return false;
        }
        removeProcessNameLocked(name, uid);
        mService.mAtmInternal.clearHeavyWeightProcessIfEquals(app.getWindowProcessController());

        boolean needRestart = false;
        final int pid = app.getPid();
        if ((pid > 0 && pid != ActivityManagerService.MY_PID)
                || (pid == 0 && app.isPendingStart())) {
            if (pid > 0) {
                mService.removePidLocked(pid, app);
                app.setBindMountPending(false);
                mService.mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);
                mService.mBatteryStatsService.noteProcessFinish(app.processName, app.info.uid);
                if (app.isolated) {
                    mService.mBatteryStatsService.removeIsolatedUid(app.uid, app.info.uid);
                    mService.getPackageManagerInternal().removeIsolatedUid(app.uid);
                }
            }
            boolean willRestart = false;
            if (app.isPersistent() && !app.isolated) {
                if (!callerWillRestart) {
                    willRestart = true;
                } else {
                    needRestart = true;
                }
            }
            app.killLocked(reason, reasonCode, subReason, true, async);
            mService.handleAppDiedLocked(app, pid, willRestart, allowRestart,
                    false /* fromBinderDied */);
            if (willRestart) {
                removeLruProcessLocked(app);
                mService.addAppLocked(app.info, null, false, null /* ABI override */,
                        ZYGOTE_POLICY_FLAG_EMPTY);
            }
        } else {
            mRemovedProcesses.add(app);
        }

        return needRestart;
    }

    @GuardedBy("mService")
    void addProcessNameLocked(ProcessRecord proc) {
        // We shouldn't already have a process under this name, but just in case we
        // need to clean up whatever may be there now.
        synchronized (mProcLock) {
            ProcessRecord old = removeProcessNameLocked(proc.processName, proc.uid);
            if (old == proc && proc.isPersistent()) {
                // We are re-adding a persistent process.  Whatevs!  Just leave it there.
                Slog.w(TAG, "Re-adding persistent process " + proc);
            } else if (old != null) {
                if (old.isKilled()) {
                    // The old process has been killed, we probably haven't had
                    // a chance to clean up the old record, just log a warning
                    Slog.w(TAG, "Existing proc " + old + " was killed "
                            + (SystemClock.uptimeMillis() - old.getKillTime())
                            + "ms ago when adding " + proc);
                } else {
                    Slog.wtf(TAG, "Already have existing proc " + old + " when adding " + proc);
                }
            }
            UidRecord uidRec = mActiveUids.get(proc.uid);
            if (uidRec == null) {
                uidRec = new UidRecord(proc.uid, mService);
                // This is the first appearance of the uid, report it now!
                if (DEBUG_UID_OBSERVERS) {
                    Slog.i(TAG_UID_OBSERVERS, "Creating new process uid: " + uidRec);
                }
                if (Arrays.binarySearch(mService.mDeviceIdleTempAllowlist,
                            UserHandle.getAppId(proc.uid)) >= 0
                        || mService.mPendingTempAllowlist.indexOfKey(proc.uid) >= 0) {
                    uidRec.setCurAllowListed(true);
                    uidRec.setSetAllowListed(true);
                }
                uidRec.updateHasInternetPermission();
                mActiveUids.put(proc.uid, uidRec);
                EventLogTags.writeAmUidRunning(uidRec.getUid());
                mService.noteUidProcessState(uidRec.getUid(), uidRec.getCurProcState(),
                        uidRec.getCurCapability());
            }
            proc.setUidRecord(uidRec);
            uidRec.addProcess(proc);

            // Reset render thread tid if it was already set, so new process can set it again.
            proc.setRenderThreadTid(0);
            mProcessNames.put(proc.processName, proc.uid, proc);
        }
        if (proc.isolated) {
            mIsolatedProcesses.put(proc.uid, proc);
        }
        if (proc.isSdkSandbox) {
            ArrayList<ProcessRecord> sdkSandboxes = mSdkSandboxes.get(proc.uid);
            if (sdkSandboxes == null) {
                sdkSandboxes = new ArrayList<>();
            }
            sdkSandboxes.add(proc);
            mSdkSandboxes.put(Process.getAppUidForSdkSandboxUid(proc.uid), sdkSandboxes);
        }
    }

    @GuardedBy("mService")
    private IsolatedUidRange getOrCreateIsolatedUidRangeLocked(ApplicationInfo info,
            HostingRecord hostingRecord) {
        if (hostingRecord == null || !hostingRecord.usesAppZygote()) {
            // Allocate an isolated UID from the global range
            return mGlobalIsolatedUids;
        } else {
            return mAppIsolatedUidRangeAllocator.getOrCreateIsolatedUidRangeLocked(
                    info.processName, hostingRecord.getDefiningUid());
        }
    }

    ProcessRecord getSharedIsolatedProcess(String processName, int uid, String packageName) {
        for (int i = 0, size = mIsolatedProcesses.size(); i < size; i++) {
            final ProcessRecord app = mIsolatedProcesses.valueAt(i);
            if (app.info.uid == uid && app.info.packageName.equals(packageName)
                    && app.processName.equals(processName)) {
                return app;
            }
        }
        return null;
    }
    @Nullable
    @GuardedBy("mService")
    List<Integer> getIsolatedProcessesLocked(int uid) {
        List<Integer> ret = null;
        for (int i = 0, size = mIsolatedProcesses.size(); i < size; i++) {
            final ProcessRecord app = mIsolatedProcesses.valueAt(i);
            if (app.info.uid == uid) {
                if (ret == null) {
                    ret = new ArrayList<>();
                }
                ret.add(app.getPid());
            }
        }
        return ret;
    }

    /**
     * Returns the associated SDK sandbox processes for a UID. Note that this does
     * NOT return a copy, so callers should not modify the result, or use it outside
     * of the lock scope.
     *
     * @param uid UID to return sansdbox processes for
     */
    @Nullable
    @GuardedBy("mService")
    List<ProcessRecord> getSdkSandboxProcessesForAppLocked(int uid) {
        return mSdkSandboxes.get(uid);
    }

    @GuardedBy("mService")
    ProcessRecord newProcessRecordLocked(ApplicationInfo info, String customProcess,
            boolean isolated, int isolatedUid, boolean isSdkSandbox, int sdkSandboxUid,
            String sdkSandboxClientAppPackage, HostingRecord hostingRecord) {
        String proc = customProcess != null ? customProcess : info.processName;
        final int userId = UserHandle.getUserId(info.uid);
        int uid = info.uid;
        if (isSdkSandbox) {
            uid = sdkSandboxUid;
        }
        if (Process.isSdkSandboxUid(uid) && (!isSdkSandbox || sdkSandboxClientAppPackage == null)) {
            Slog.e(TAG, "Abort creating new sandbox process as required parameters are missing.");
            return null;
        }
        if (isolated) {
            if (isolatedUid == 0) {
                IsolatedUidRange uidRange = getOrCreateIsolatedUidRangeLocked(info, hostingRecord);
                if (uidRange == null) {
                    return null;
                }
                uid = uidRange.allocateIsolatedUidLocked(userId);
                if (uid == -1) {
                    return null;
                }
            } else {
                // Special case for startIsolatedProcess (internal only), where
                // the uid of the isolated process is specified by the caller.
                uid = isolatedUid;
            }
            mAppExitInfoTracker.mIsolatedUidRecords.addIsolatedUid(uid, info.uid);
            mService.getPackageManagerInternal().addIsolatedUid(uid, info.uid);

            // Register the isolated UID with this application so BatteryStats knows to
            // attribute resource usage to the application.
            //
            // NOTE: This is done here before addProcessNameLocked, which will tell BatteryStats
            // about the process state of the isolated UID *before* it is registered with the
            // owning application.
            mService.mBatteryStatsService.addIsolatedUid(uid, info.uid);
            FrameworkStatsLog.write(FrameworkStatsLog.ISOLATED_UID_CHANGED, info.uid, uid,
                    FrameworkStatsLog.ISOLATED_UID_CHANGED__EVENT__CREATED);
        }
        final ProcessRecord r = new ProcessRecord(mService, info, proc, uid,
                sdkSandboxClientAppPackage,
                hostingRecord.getDefiningUid(), hostingRecord.getDefiningProcessName());
        final ProcessStateRecord state = r.mState;

        if (!isolated && !isSdkSandbox
                && userId == UserHandle.USER_SYSTEM
                && (info.flags & PERSISTENT_MASK) == PERSISTENT_MASK
                && (TextUtils.equals(proc, info.processName))) {
            // The system process is initialized to SCHED_GROUP_DEFAULT in init.rc.
            state.setCurrentSchedulingGroup(ProcessList.SCHED_GROUP_DEFAULT);
            state.setSetSchedGroup(ProcessList.SCHED_GROUP_DEFAULT);
            r.setPersistent(true);
            state.setMaxAdj(ProcessList.PERSISTENT_PROC_ADJ);
        }
        if (isolated && isolatedUid != 0) {
            // Special case for startIsolatedProcess (internal only) - assume the process
            // is required by the system server to prevent it being killed.
            state.setMaxAdj(ProcessList.PERSISTENT_SERVICE_ADJ);
        }
        addProcessNameLocked(r);
        return r;
    }

    @GuardedBy("mService")
    ProcessRecord removeProcessNameLocked(final String name, final int uid) {
        return removeProcessNameLocked(name, uid, null);
    }

    @GuardedBy("mService")
    ProcessRecord removeProcessNameLocked(final String name, final int uid,
            final ProcessRecord expecting) {
        ProcessRecord old = mProcessNames.get(name, uid);
        final ProcessRecord record = expecting != null ? expecting : old;
        synchronized (mProcLock) {
            // Only actually remove when the currently recorded value matches the
            // record that we expected; if it doesn't match then we raced with a
            // newly created process and we don't want to destroy the new one.
            if ((expecting == null) || (old == expecting)) {
                mProcessNames.remove(name, uid);
            }
            if (record != null) {
                final UidRecord uidRecord = record.getUidRecord();
                if (uidRecord != null) {
                    uidRecord.removeProcess(record);
                    if (uidRecord.getNumOfProcs() == 0) {
                        // No more processes using this uid, tell clients it is gone.
                        if (DEBUG_UID_OBSERVERS) {
                            Slog.i(TAG_UID_OBSERVERS, "No more processes in " + uidRecord);
                        }
                        mService.enqueueUidChangeLocked(uidRecord, -1,
                                UidRecord.CHANGE_GONE | UidRecord.CHANGE_PROCSTATE);
                        EventLogTags.writeAmUidStopped(uid);
                        mActiveUids.remove(uid);
                        mService.mFgsStartTempAllowList.removeUid(record.info.uid);
                        mService.noteUidProcessState(uid, ActivityManager.PROCESS_STATE_NONEXISTENT,
                                ActivityManager.PROCESS_CAPABILITY_NONE);
                    }
                    record.setUidRecord(null);
                }
            }
        }
        mIsolatedProcesses.remove(uid);
        mGlobalIsolatedUids.freeIsolatedUidLocked(uid);
        // Remove the (expected) ProcessRecord from the app zygote
        if (record != null && record.appZygote) {
            removeProcessFromAppZygoteLocked(record);
        }
        if (record != null && record.isSdkSandbox) {
            final int appUid = Process.getAppUidForSdkSandboxUid(uid);
            final ArrayList<ProcessRecord> sdkSandboxesForUid = mSdkSandboxes.get(appUid);
            if (sdkSandboxesForUid != null) {
                sdkSandboxesForUid.remove(record);
                if (sdkSandboxesForUid.size() == 0) {
                    mSdkSandboxes.remove(appUid);
                }
            }
        }
        mAppsInBackgroundRestricted.remove(record);

        return old;
    }

    /** Call setCoreSettings on all LRU processes, with the new settings. */
    @GuardedBy(anyOf = {"mService", "mProcLock"})
    void updateCoreSettingsLOSP(Bundle settings) {
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord processRecord = mLruProcesses.get(i);
            final IApplicationThread thread = processRecord.getThread();
            try {
                if (thread != null) {
                    thread.setCoreSettings(settings);
                }
            } catch (RemoteException re) {
                /* ignore */
            }
        }
    }

    /**
     * Kill all background processes except for ones with targetSdk lower than minTargetSdk and
     * procstate lower than maxProcState.
     * @param minTargetSdk
     * @param maxProcState
     */
    @GuardedBy({"mService", "mProcLock"})
    void killAllBackgroundProcessesExceptLSP(int minTargetSdk, int maxProcState) {
        final ArrayList<ProcessRecord> procs = new ArrayList<>();
        final int NP = mProcessNames.getMap().size();
        for (int ip = 0; ip < NP; ip++) {
            final SparseArray<ProcessRecord> apps = mProcessNames.getMap().valueAt(ip);
            final int NA = apps.size();
            for (int ia = 0; ia < NA; ia++) {
                final ProcessRecord app = apps.valueAt(ia);
                if (app.isRemoved()
                        || ((minTargetSdk < 0 || app.info.targetSdkVersion < minTargetSdk)
                        && (maxProcState < 0 || app.mState.getSetProcState() > maxProcState))) {
                    procs.add(app);
                }
            }
        }

        final int N = procs.size();
        for (int i = 0; i < N; i++) {
            removeProcessLocked(procs.get(i), false, true, ApplicationExitInfo.REASON_OTHER,
                    ApplicationExitInfo.SUBREASON_KILL_ALL_BG_EXCEPT, "kill all background except");
        }
    }

    /**
     * Call updateTimePrefs on all LRU processes
     * @param timePref The time pref to pass to each process
     */
    @GuardedBy(anyOf = {"mService", "mProcLock"})
    void updateAllTimePrefsLOSP(int timePref) {
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord r = mLruProcesses.get(i);
            final IApplicationThread thread = r.getThread();
            if (thread != null) {
                try {
                    thread.updateTimePrefs(timePref);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to update preferences for: "
                            + r.info.processName);
                }
            }
        }
    }

    void setAllHttpProxy() {
        // Update the HTTP proxy for each application thread.
        synchronized (mProcLock) {
            for (int i = mLruProcesses.size() - 1 ; i >= 0 ; i--) {
                ProcessRecord r = mLruProcesses.get(i);
                IApplicationThread thread = r.getThread();
                // Don't dispatch to isolated processes as they can't access ConnectivityManager and
                // don't have network privileges anyway. Exclude system server and update it
                // separately outside the AMS lock, to avoid deadlock with Connectivity Service.
                if (r.getPid() != ActivityManagerService.MY_PID && thread != null && !r.isolated) {
                    try {
                        thread.updateHttpProxy();
                    } catch (RemoteException ex) {
                        Slog.w(TAG, "Failed to update http proxy for: "
                                + r.info.processName);
                    }
                }
            }
        }
        ActivityThread.updateHttpProxy(mService.mContext);
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    void clearAllDnsCacheLOSP() {
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord r = mLruProcesses.get(i);
            final IApplicationThread thread = r.getThread();
            if (thread != null) {
                try {
                    thread.clearDnsCache();
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to clear dns cache for: " + r.info.processName);
                }
            }
        }
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    void handleAllTrustStorageUpdateLOSP() {
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord r = mLruProcesses.get(i);
            final IApplicationThread thread = r.getThread();
            if (thread != null) {
                try {
                    thread.handleTrustStorageUpdate();
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to handle trust storage update for: " +
                            r.info.processName);
                }
            }
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    private int updateLruProcessInternalLSP(ProcessRecord app, long now, int index,
            int lruSeq, String what, Object obj, ProcessRecord srcApp) {
        app.setLastActivityTime(now);

        if (app.hasActivitiesOrRecentTasks()) {
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

        if (lrui >= mLruProcessActivityStart && index < mLruProcessActivityStart) {
            // Don't want to touch dependent processes that are hosting activities.
            return index;
        }

        mLruProcesses.remove(lrui);
        if (index > 0) {
            index--;
        }
        if (DEBUG_LRU) Slog.d(TAG_LRU, "Moving dep from " + lrui + " to " + index
                + " in LRU list: " + app);
        mLruProcesses.add(index, app);
        app.setLruSeq(lruSeq);
        return index;
    }

    /**
     * Handle the case where we are inserting a process hosting client activities:
     * Make sure any groups have their order match their importance, and take care of
     * distributing old clients across other activity processes so they can't spam
     * the LRU list.  Processing of the list will be restricted by the indices provided,
     * and not extend out of them.
     *
     * @param topApp The app at the top that has just been inserted in to the list.
     * @param topI The position in the list where topApp was inserted; this is the start (at the
     *             top) where we are going to do our processing.
     * @param bottomI The last position at which we will be processing; this is the end position
     *                of whichever section of the LRU list we are in.  Nothing past it will be
     *                touched.
     * @param endIndex The current end of the top being processed.  Typically topI - 1.  That is,
     *                 where we are going to start potentially adjusting other entries in the list.
     */
    @GuardedBy({"mService", "mProcLock"})
    private void updateClientActivitiesOrderingLSP(final ProcessRecord topApp, final int topI,
            final int bottomI, int endIndex) {
        final ProcessServiceRecord topPsr = topApp.mServices;
        if (topApp.hasActivitiesOrRecentTasks() || topPsr.isTreatedLikeActivity()
                || !topPsr.hasClientActivities()) {
            // If this is not a special process that has client activities, then there is
            // nothing to do.
            return;
        }

        final int uid = topApp.info.uid;
        final int topConnectionGroup = topPsr.getConnectionGroup();
        if (topConnectionGroup > 0) {
            int endImportance = topPsr.getConnectionImportance();
            for (int i = endIndex; i >= bottomI; i--) {
                final ProcessRecord subProc = mLruProcesses.get(i);
                final ProcessServiceRecord subPsr = subProc.mServices;
                final int subConnectionGroup = subPsr.getConnectionGroup();
                final int subConnectionImportance = subPsr.getConnectionImportance();
                if (subProc.info.uid == uid
                        && subConnectionGroup == topConnectionGroup) {
                    if (i == endIndex && subConnectionImportance >= endImportance) {
                        // This process is already in the group, and its importance
                        // is not as strong as the process before it, so keep it
                        // correctly positioned in the group.
                        if (DEBUG_LRU) Slog.d(TAG_LRU,
                                "Keeping in-place above " + subProc
                                        + " endImportance=" + endImportance
                                        + " group=" + subConnectionGroup
                                        + " importance=" + subConnectionImportance);
                        endIndex--;
                        endImportance = subConnectionImportance;
                    } else {
                        // We want to pull this up to be with the rest of the group,
                        // and order within the group by importance.
                        if (DEBUG_LRU) Slog.d(TAG_LRU,
                                "Pulling up " + subProc
                                        + " to position in group with importance="
                                        + subConnectionImportance);
                        boolean moved = false;
                        for (int pos = topI; pos > endIndex; pos--) {
                            final ProcessRecord posProc = mLruProcesses.get(pos);
                            if (subConnectionImportance
                                    <= posProc.mServices.getConnectionImportance()) {
                                mLruProcesses.remove(i);
                                mLruProcesses.add(pos, subProc);
                                if (DEBUG_LRU) Slog.d(TAG_LRU,
                                        "Moving " + subProc
                                                + " from position " + i + " to above " + posProc
                                                + " @ " + pos);
                                moved = true;
                                endIndex--;
                                break;
                            }
                        }
                        if (!moved) {
                            // Goes to the end of the group.
                            mLruProcesses.remove(i);
                            mLruProcesses.add(endIndex, subProc);
                            if (DEBUG_LRU) Slog.d(TAG_LRU,
                                    "Moving " + subProc
                                            + " from position " + i + " to end of group @ "
                                            + endIndex);
                            endIndex--;
                            endImportance = subConnectionImportance;
                        }
                    }
                }
            }

        }
        // To keep it from spamming the LRU list (by making a bunch of clients),
        // we will distribute other entries owned by it to be in-between other apps.
        int i = endIndex;
        while (i >= bottomI) {
            ProcessRecord subProc = mLruProcesses.get(i);
            final ProcessServiceRecord subPsr = subProc.mServices;
            final int subConnectionGroup = subPsr.getConnectionGroup();
            if (DEBUG_LRU) Slog.d(TAG_LRU,
                    "Looking to spread old procs, at " + subProc + " @ " + i);
            if (subProc.info.uid != uid) {
                // This is a different app...  if we have gone through some of the
                // target app, pull this up to be before them.  We want to pull up
                // one activity process, but any number of non-activity processes.
                if (i < endIndex) {
                    boolean hasActivity = false;
                    int connUid = 0;
                    int connGroup = 0;
                    while (i >= bottomI) {
                        mLruProcesses.remove(i);
                        mLruProcesses.add(endIndex, subProc);
                        if (DEBUG_LRU) Slog.d(TAG_LRU,
                                "Different app, moving to " + endIndex);
                        i--;
                        if (i < bottomI) {
                            break;
                        }
                        subProc = mLruProcesses.get(i);
                        if (DEBUG_LRU) Slog.d(TAG_LRU,
                                "Looking at next app at " + i + ": " + subProc);
                        if (subProc.hasActivitiesOrRecentTasks()
                                || subPsr.isTreatedLikeActivity()) {
                            if (DEBUG_LRU) Slog.d(TAG_LRU,
                                    "This is hosting an activity!");
                            if (hasActivity) {
                                // Already found an activity, done.
                                if (DEBUG_LRU) Slog.d(TAG_LRU,
                                        "Already found an activity, done");
                                break;
                            }
                            hasActivity = true;
                        } else if (subPsr.hasClientActivities()) {
                            if (DEBUG_LRU) Slog.d(TAG_LRU,
                                    "This is a client of an activity");
                            if (hasActivity) {
                                if (connUid == 0 || connUid != subProc.info.uid) {
                                    // Already have an activity that is not from from a client
                                    // connection or is a different client connection, done.
                                    if (DEBUG_LRU) Slog.d(TAG_LRU,
                                            "Already found a different activity: connUid="
                                            + connUid + " uid=" + subProc.info.uid);
                                    break;
                                } else if (connGroup == 0 || connGroup != subConnectionGroup) {
                                    // Previously saw a different group or not from a group,
                                    // want to treat these as different things.
                                    if (DEBUG_LRU) Slog.d(TAG_LRU,
                                            "Already found a different group: connGroup="
                                            + connGroup + " group=" + subConnectionGroup);
                                    break;
                                }
                            } else {
                                if (DEBUG_LRU) Slog.d(TAG_LRU,
                                        "This is an activity client!  uid="
                                        + subProc.info.uid + " group=" + subConnectionGroup);
                                hasActivity = true;
                                connUid = subProc.info.uid;
                                connGroup = subConnectionGroup;
                            }
                        }
                        endIndex--;
                    }
                }
                // Find the end of the next group of processes for target app.  This
                // is after any entries of different apps (so we don't change the existing
                // relative order of apps) and then after the next last group of processes
                // of the target app.
                for (endIndex--; endIndex >= bottomI; endIndex--) {
                    final ProcessRecord endProc = mLruProcesses.get(endIndex);
                    if (endProc.info.uid == uid) {
                        if (DEBUG_LRU) Slog.d(TAG_LRU,
                                "Found next group of app: " + endProc + " @ "
                                        + endIndex);
                        break;
                    }
                }
                if (endIndex >= bottomI) {
                    final ProcessRecord endProc = mLruProcesses.get(endIndex);
                    final ProcessServiceRecord endPsr = endProc.mServices;
                    final int endConnectionGroup = endPsr.getConnectionGroup();
                    for (endIndex--; endIndex >= bottomI; endIndex--) {
                        final ProcessRecord nextEndProc = mLruProcesses.get(endIndex);
                        final int nextConnectionGroup = nextEndProc.mServices.getConnectionGroup();
                        if (nextEndProc.info.uid != uid
                                || nextConnectionGroup != endConnectionGroup) {
                            if (DEBUG_LRU) Slog.d(TAG_LRU,
                                    "Found next group or app: " + nextEndProc + " @ "
                                            + endIndex + " group=" + nextConnectionGroup);
                            break;
                        }
                    }
                }
                if (DEBUG_LRU) Slog.d(TAG_LRU,
                        "Bumping scan position to " + endIndex);
                i = endIndex;
            } else {
                i--;
            }
        }
    }

    @GuardedBy("mService")
    void updateLruProcessLocked(ProcessRecord app, boolean activityChange, ProcessRecord client) {
        final ProcessServiceRecord psr = app.mServices;
        final boolean hasActivity = app.hasActivitiesOrRecentTasks() || psr.hasClientActivities()
                || psr.isTreatedLikeActivity();
        final boolean hasService = false; // not impl yet. app.services.size() > 0;
        if (!activityChange && hasActivity) {
            // The process has activities, so we are only allowing activity-based adjustments
            // to move it.  It should be kept in the front of the list with other
            // processes that have activities, and we don't want those to change their
            // order except due to activity operations.
            return;
        }

        if (app.getPid() == 0 && !app.isPendingStart()) {
            // This process has been killed and its cleanup is done, don't proceed the LRU update.
            return;
        }

        synchronized (mProcLock) {
            updateLruProcessLSP(app, client, hasActivity, hasService);
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    private void updateLruProcessLSP(ProcessRecord app, ProcessRecord client,
            boolean hasActivity, boolean hasService) {
        mLruSeq++;
        final long now = SystemClock.uptimeMillis();
        final ProcessServiceRecord psr = app.mServices;
        app.setLastActivityTime(now);

        // First a quick reject: if the app is already at the position we will
        // put it, then there is nothing to do.
        if (hasActivity) {
            final int N = mLruProcesses.size();
            if (N > 0 && mLruProcesses.get(N - 1) == app) {
                if (DEBUG_LRU) Slog.d(TAG_LRU, "Not moving, already top activity: " + app);
                return;
            }
        } else {
            if (mLruProcessServiceStart > 0
                    && mLruProcesses.get(mLruProcessServiceStart-1) == app) {
                if (DEBUG_LRU) Slog.d(TAG_LRU, "Not moving, already top other: " + app);
                return;
            }
        }

        int lrui = mLruProcesses.lastIndexOf(app);

        if (app.isPersistent() && lrui >= 0) {
            // We don't care about the position of persistent processes, as long as
            // they are in the list.
            if (DEBUG_LRU) Slog.d(TAG_LRU, "Not moving, persistent: " + app);
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
        int nextActivityIndex = -1;
        if (hasActivity) {
            final int N = mLruProcesses.size();
            nextIndex = mLruProcessServiceStart;
            if (!app.hasActivitiesOrRecentTasks() && !psr.isTreatedLikeActivity()
                    && mLruProcessActivityStart < (N - 1)) {
                // Process doesn't have activities, but has clients with
                // activities...  move it up, but below the app that is binding to it.
                if (DEBUG_LRU) Slog.d(TAG_LRU,
                        "Adding to second-top of LRU activity list: " + app
                        + " group=" + psr.getConnectionGroup()
                        + " importance=" + psr.getConnectionImportance());
                int pos = N - 1;
                while (pos > mLruProcessActivityStart) {
                    final ProcessRecord posproc = mLruProcesses.get(pos);
                    if (posproc.info.uid == app.info.uid) {
                        // Technically this app could have multiple processes with different
                        // activities and so we should be looking for the actual process that
                        // is bound to the target proc...  but I don't really care, do you?
                        break;
                    }
                    pos--;
                }
                mLruProcesses.add(pos, app);
                // If this process is part of a group, need to pull up any other processes
                // in that group to be with it.
                int endIndex = pos - 1;
                if (endIndex < mLruProcessActivityStart) {
                    endIndex = mLruProcessActivityStart;
                }
                nextActivityIndex = endIndex;
                updateClientActivitiesOrderingLSP(app, pos, mLruProcessActivityStart, endIndex);
            } else {
                // Process has activities, put it at the very tipsy-top.
                if (DEBUG_LRU) Slog.d(TAG_LRU, "Adding to top of LRU activity list: " + app);
                mLruProcesses.add(app);
                nextActivityIndex = mLruProcesses.size() - 1;
            }
        } else if (hasService) {
            // Process has services, put it at the top of the service list.
            if (DEBUG_LRU) Slog.d(TAG_LRU, "Adding to top of LRU service list: " + app);
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
                if (DEBUG_LRU && clientIndex < 0) Slog.d(TAG_LRU, "Unknown client " + client
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
            if (DEBUG_LRU) Slog.d(TAG_LRU, "Adding at " + index + " of LRU list: " + app);
            mLruProcesses.add(index, app);
            nextIndex = index - 1;
            mLruProcessActivityStart++;
            mLruProcessServiceStart++;
            if (index > 1) {
                updateClientActivitiesOrderingLSP(app, mLruProcessServiceStart - 1, 0, index - 1);
            }
        }

        app.setLruSeq(mLruSeq);

        // If the app is currently using a content provider or service,
        // bump those processes as well.
        for (int j = psr.numberOfConnections() - 1; j >= 0; j--) {
            ConnectionRecord cr = psr.getConnectionAt(j);
            if (cr.binding != null && !cr.serviceDead && cr.binding.service != null
                    && cr.binding.service.app != null
                    && cr.binding.service.app.getLruSeq() != mLruSeq
                    && cr.notHasFlag(Context.BIND_REDUCTION_FLAGS)
                    && !cr.binding.service.app.isPersistent()) {
                if (cr.binding.service.app.mServices.hasClientActivities()) {
                    if (nextActivityIndex >= 0) {
                        nextActivityIndex = updateLruProcessInternalLSP(cr.binding.service.app,
                                now,
                                nextActivityIndex, mLruSeq,
                                "service connection", cr, app);
                    }
                } else {
                    nextIndex = updateLruProcessInternalLSP(cr.binding.service.app,
                            now,
                            nextIndex, mLruSeq,
                            "service connection", cr, app);
                }
            }
        }
        final ProcessProviderRecord ppr = app.mProviders;
        for (int j = ppr.numberOfProviderConnections() - 1; j >= 0; j--) {
            ContentProviderRecord cpr = ppr.getProviderConnectionAt(j).provider;
            if (cpr.proc != null && cpr.proc.getLruSeq() != mLruSeq && !cpr.proc.isPersistent()) {
                nextIndex = updateLruProcessInternalLSP(cpr.proc, now, nextIndex, mLruSeq,
                        "provider reference", cpr, app);
            }
        }
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    ProcessRecord getLRURecordForAppLOSP(IApplicationThread thread) {
        if (thread == null) {
            return null;
        }
        return getLRURecordForAppLOSP(thread.asBinder());
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    ProcessRecord getLRURecordForAppLOSP(IBinder threadBinder) {
        if (threadBinder == null) {
            return null;
        }
        // Find the application record.
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            final ProcessRecord rec = mLruProcesses.get(i);
            final IApplicationThread t = rec.getThread();
            if (t != null && t.asBinder() == threadBinder) {
                return rec;
            }
        }
        return null;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean haveBackgroundProcessLOSP() {
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            final ProcessRecord rec = mLruProcesses.get(i);
            if (rec.getThread() != null
                    && rec.mState.getSetProcState() >= PROCESS_STATE_CACHED_ACTIVITY) {
                return true;
            }
        }
        return false;
    }

    private static int procStateToImportance(int procState, int memAdj,
            ActivityManager.RunningAppProcessInfo currApp,
            int clientTargetSdk) {
        int imp = ActivityManager.RunningAppProcessInfo.procStateToImportanceForTargetSdk(
                procState, clientTargetSdk);
        if (imp == ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND) {
            currApp.lru = memAdj;
        } else {
            currApp.lru = 0;
        }
        return imp;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    void fillInProcMemInfoLOSP(ProcessRecord app,
            ActivityManager.RunningAppProcessInfo outInfo,
            int clientTargetSdk) {
        outInfo.pid = app.getPid();
        outInfo.uid = app.info.uid;
        if (app.getWindowProcessController().isHeavyWeightProcess()) {
            outInfo.flags |= ActivityManager.RunningAppProcessInfo.FLAG_CANT_SAVE_STATE;
        }
        if (app.isPersistent()) {
            outInfo.flags |= ActivityManager.RunningAppProcessInfo.FLAG_PERSISTENT;
        }
        if (app.hasActivities()) {
            outInfo.flags |= ActivityManager.RunningAppProcessInfo.FLAG_HAS_ACTIVITIES;
        }
        outInfo.lastTrimLevel = app.mProfile.getTrimMemoryLevel();
        final ProcessStateRecord state = app.mState;
        int adj = state.getCurAdj();
        int procState = state.getCurProcState();
        outInfo.importance = procStateToImportance(procState, adj, outInfo,
                clientTargetSdk);
        outInfo.importanceReasonCode = state.getAdjTypeCode();
        outInfo.processState = procState;
        outInfo.isFocused = (app == mService.getTopApp());
        outInfo.lastActivityTime = app.getLastActivityTime();
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    List<ActivityManager.RunningAppProcessInfo> getRunningAppProcessesLOSP(boolean allUsers,
            int userId, boolean allUids, int callingUid, int clientTargetSdk) {
        // Lazy instantiation of list
        List<ActivityManager.RunningAppProcessInfo> runList = null;

        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord app = mLruProcesses.get(i);
            final ProcessStateRecord state = app.mState;
            final ProcessErrorStateRecord errState = app.mErrorState;
            if ((!allUsers && app.userId != userId)
                    || (!allUids && app.uid != callingUid)) {
                continue;
            }
            if ((app.getThread() != null)
                    && (!errState.isCrashing() && !errState.isNotResponding())) {
                // Generate process state info for running application
                ActivityManager.RunningAppProcessInfo currApp =
                        new ActivityManager.RunningAppProcessInfo(app.processName,
                                app.getPid(), app.getPackageList());
                if (app.getPkgDeps() != null) {
                    final int size = app.getPkgDeps().size();
                    currApp.pkgDeps = app.getPkgDeps().toArray(new String[size]);
                }
                fillInProcMemInfoLOSP(app, currApp, clientTargetSdk);
                if (state.getAdjSource() instanceof ProcessRecord) {
                    currApp.importanceReasonPid = ((ProcessRecord) state.getAdjSource()).getPid();
                    currApp.importanceReasonImportance =
                            ActivityManager.RunningAppProcessInfo.procStateToImportance(
                                    state.getAdjSourceProcState());
                } else if (state.getAdjSource() instanceof ActivityServiceConnectionsHolder) {
                    final ActivityServiceConnectionsHolder r =
                            (ActivityServiceConnectionsHolder) state.getAdjSource();
                    final int pid = r.getActivityPid();
                    if (pid != -1) {
                        currApp.importanceReasonPid = pid;
                    }
                }
                if (state.getAdjTarget() instanceof ComponentName) {
                    currApp.importanceReasonComponent = (ComponentName) state.getAdjTarget();
                }
                //Slog.v(TAG, "Proc " + app.processName + ": imp=" + currApp.importance
                //        + " lru=" + currApp.lru);
                if (runList == null) {
                    runList = new ArrayList<>();
                }
                runList.add(currApp);
            }
        }
        return runList;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getLruSizeLOSP() {
        return mLruProcesses.size();
    }

    /**
     * Return the reference to the LRU list, call this function for read-only access
     */
    @GuardedBy(anyOf = {"mService", "mProcLock"})
    ArrayList<ProcessRecord> getLruProcessesLOSP() {
        return mLruProcesses;
    }

    /**
     * Return the reference to the LRU list, call this function for read/write access
     */
    @GuardedBy({"mService", "mProcLock"})
    ArrayList<ProcessRecord> getLruProcessesLSP() {
        return mLruProcesses;
    }

    /**
     * For test only
     */
    @VisibleForTesting
    @GuardedBy({"mService", "mProcLock"})
    void setLruProcessServiceStartLSP(int pos) {
        mLruProcessServiceStart = pos;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getLruProcessServiceStartLOSP() {
        return mLruProcessServiceStart;
    }

    /**
     * Iterate the whole LRU list, invoke the given {@code callback} with each of the ProcessRecord
     * in that list.
     *
     * @param iterateForward If {@code true}, iterate the LRU list from the least recent used
     *                       to most recent used ProcessRecord.
     * @param callback The callback interface to accept the current ProcessRecord.
     */
    @GuardedBy(anyOf = {"mService", "mProcLock"})
    void forEachLruProcessesLOSP(boolean iterateForward,
            @NonNull Consumer<ProcessRecord> callback) {
        if (iterateForward) {
            for (int i = 0, size = mLruProcesses.size(); i < size; i++) {
                callback.accept(mLruProcesses.get(i));
            }
        } else {
            for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
                callback.accept(mLruProcesses.get(i));
            }
        }
    }

    /**
     * Search in the LRU list, invoke the given {@code callback} with each of the ProcessRecord
     * in that list; if the callback returns a non-null object, halt the search, return that
     * object as the return value of this search function.
     *
     * @param iterateForward If {@code true}, iterate the LRU list from the least recent used
     *                       to most recent used ProcessRecord.
     * @param callback The callback interface to accept the current ProcessRecord; if it returns
     *                 a non-null object, the search will be halted and this object will be used
     *                 as the return value of this search function.
     */
    @GuardedBy(anyOf = {"mService", "mProcLock"})
    <R> R searchEachLruProcessesLOSP(boolean iterateForward,
            @NonNull Function<ProcessRecord, R> callback) {
        if (iterateForward) {
            for (int i = 0, size = mLruProcesses.size(); i < size; i++) {
                R r;
                if ((r = callback.apply(mLruProcesses.get(i))) != null) {
                    return r;
                }
            }
        } else {
            for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
                R r;
                if ((r = callback.apply(mLruProcesses.get(i))) != null) {
                    return r;
                }
            }
        }
        return null;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean isInLruListLOSP(ProcessRecord app) {
        return mLruProcesses.contains(app);
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getLruSeqLOSP() {
        return mLruSeq;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    MyProcessMap getProcessNamesLOSP() {
        return mProcessNames;
    }

    @GuardedBy("mService")
    void dumpLruListHeaderLocked(PrintWriter pw) {
        pw.print("  Process LRU list (sorted by oom_adj, "); pw.print(mLruProcesses.size());
        pw.print(" total, non-act at ");
        pw.print(mLruProcesses.size() - mLruProcessActivityStart);
        pw.print(", non-svc at ");
        pw.print(mLruProcesses.size() - mLruProcessServiceStart);
        pw.println("):");
    }

    @GuardedBy("mService")
    private void dumpLruEntryLocked(PrintWriter pw, int index, ProcessRecord proc, String prefix) {
        pw.print(prefix);
        pw.print('#');
        if (index < 10) {
            pw.print(' ');
        }
        pw.print(index);
        pw.print(": ");
        pw.print(makeOomAdjString(proc.mState.getSetAdj(), false));
        pw.print(' ');
        pw.print(makeProcStateString(proc.mState.getCurProcState()));
        pw.print(' ');
        ActivityManager.printCapabilitiesSummary(pw, proc.mState.getCurCapability());
        pw.print(' ');
        pw.print(proc.toShortString());
        final ProcessServiceRecord psr = proc.mServices;
        if (proc.hasActivitiesOrRecentTasks() || psr.hasClientActivities()
                || psr.isTreatedLikeActivity()) {
            pw.print(" act:");
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
            if (psr.hasClientActivities()) {
                if (printed) {
                    pw.print("|");
                }
                pw.print("client");
                printed = true;
            }
            if (psr.isTreatedLikeActivity()) {
                if (printed) {
                    pw.print("|");
                }
                pw.print("treated");
            }
        }
        pw.println();
    }

    @GuardedBy("mService")
    boolean dumpLruLocked(PrintWriter pw, String dumpPackage, String prefix) {
        final int lruSize = mLruProcesses.size();
        final String innerPrefix;
        if (prefix == null) {
            pw.println("ACTIVITY MANAGER LRU PROCESSES (dumpsys activity lru)");
            innerPrefix = "  ";
        } else {
            boolean haveAny = false;
            for (int i = lruSize - 1; i >= 0; i--) {
                final ProcessRecord r = mLruProcesses.get(i);
                if (dumpPackage != null && !r.getPkgList().containsKey(dumpPackage)) {
                    continue;
                }
                haveAny = true;
                break;
            }
            if (!haveAny) {
                return false;
            }
            pw.print(prefix);
            pw.println("Raw LRU list (dumpsys activity lru):");
            innerPrefix = prefix + "  ";
        }
        int i;
        boolean first = true;
        for (i = lruSize - 1; i >= mLruProcessActivityStart; i--) {
            final ProcessRecord r = mLruProcesses.get(i);
            if (dumpPackage != null && !r.getPkgList().containsKey(dumpPackage)) {
                continue;
            }
            if (first) {
                pw.print(innerPrefix);
                pw.println("Activities:");
                first = false;
            }
            dumpLruEntryLocked(pw, i, r, innerPrefix);
        }
        first = true;
        for (; i >= mLruProcessServiceStart; i--) {
            final ProcessRecord r = mLruProcesses.get(i);
            if (dumpPackage != null && !r.getPkgList().containsKey(dumpPackage)) {
                continue;
            }
            if (first) {
                pw.print(innerPrefix);
                pw.println("Services:");
                first = false;
            }
            dumpLruEntryLocked(pw, i, r, innerPrefix);
        }
        first = true;
        for (; i >= 0; i--) {
            final ProcessRecord r = mLruProcesses.get(i);
            if (dumpPackage != null && !r.getPkgList().containsKey(dumpPackage)) {
                continue;
            }
            if (first) {
                pw.print(innerPrefix);
                pw.println("Other:");
                first = false;
            }
            dumpLruEntryLocked(pw, i, r, innerPrefix);
        }
        return true;
    }

    @GuardedBy({"mService", "mProcLock"})
    void dumpProcessesLSP(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage, int dumpAppId) {
        boolean needSep = false;
        int numPers = 0;

        pw.println("ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)");

        if (dumpAll || dumpPackage != null) {
            final int numOfNames = mProcessNames.getMap().size();
            for (int ip = 0; ip < numOfNames; ip++) {
                SparseArray<ProcessRecord> procs = mProcessNames.getMap().valueAt(ip);
                for (int ia = 0, size = procs.size(); ia < size; ia++) {
                    ProcessRecord r = procs.valueAt(ia);
                    if (dumpPackage != null && !r.getPkgList().containsKey(dumpPackage)) {
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

        if (mIsolatedProcesses.size() > 0) {
            boolean printed = false;
            for (int i = 0, size = mIsolatedProcesses.size(); i < size; i++) {
                ProcessRecord r = mIsolatedProcesses.valueAt(i);
                if (dumpPackage != null && !r.getPkgList().containsKey(dumpPackage)) {
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

        needSep = mService.dumpActiveInstruments(pw, dumpPackage, needSep);

        if (dumpOomLocked(fd, pw, needSep, args, opti, dumpAll, dumpPackage, false)) {
            needSep = true;
        }

        if (mActiveUids.size() > 0) {
            needSep |= mActiveUids.dump(pw, dumpPackage, dumpAppId,
                    "UID states:", needSep);
        }

        if (dumpAll) {
            needSep |= mService.mUidObserverController.dumpValidateUids(pw,
                    dumpPackage, dumpAppId, "UID validation:", needSep);
        }

        if (needSep) {
            pw.println();
        }
        if (dumpLruLocked(pw, dumpPackage, "  ")) {
            needSep = true;
        }

        if (getLruSizeLOSP() > 0) {
            if (needSep) {
                pw.println();
            }
            dumpLruListHeaderLocked(pw);
            dumpProcessOomList(pw, mService, mLruProcesses, "    ", "Proc", "PERS", false,
                    dumpPackage);
            needSep = true;
        }

        mService.dumpOtherProcessesInfoLSP(fd, pw, dumpAll, dumpPackage, dumpAppId, numPers,
                needSep);
    }

    @GuardedBy({"mService", "mProcLock"})
    void writeProcessesToProtoLSP(ProtoOutputStream proto, String dumpPackage) {
        int numPers = 0;

        final int numOfNames = mProcessNames.getMap().size();
        for (int ip = 0; ip < numOfNames; ip++) {
            SparseArray<ProcessRecord> procs = mProcessNames.getMap().valueAt(ip);
            for (int ia = 0, size = procs.size(); ia < size; ia++) {
                ProcessRecord r = procs.valueAt(ia);
                if (dumpPackage != null && !r.getPkgList().containsKey(dumpPackage)) {
                    continue;
                }
                r.dumpDebug(proto, ActivityManagerServiceDumpProcessesProto.PROCS,
                        mLruProcesses.indexOf(r)
                );
                if (r.isPersistent()) {
                    numPers++;
                }
            }
        }

        for (int i = 0, size = mIsolatedProcesses.size(); i < size; i++) {
            ProcessRecord r = mIsolatedProcesses.valueAt(i);
            if (dumpPackage != null && !r.getPkgList().containsKey(dumpPackage)) {
                continue;
            }
            r.dumpDebug(proto, ActivityManagerServiceDumpProcessesProto.ISOLATED_PROCS,
                    mLruProcesses.indexOf(r)
            );
        }

        final int dumpAppId = mService.getAppId(dumpPackage);
        mActiveUids.dumpProto(proto, dumpPackage, dumpAppId,
                ActivityManagerServiceDumpProcessesProto.ACTIVE_UIDS);

        if (getLruSizeLOSP() > 0) {
            long lruToken = proto.start(ActivityManagerServiceDumpProcessesProto.LRU_PROCS);
            int total = getLruSizeLOSP();
            proto.write(ActivityManagerServiceDumpProcessesProto.LruProcesses.SIZE, total);
            proto.write(ActivityManagerServiceDumpProcessesProto.LruProcesses.NON_ACT_AT,
                    total - mLruProcessActivityStart);
            proto.write(ActivityManagerServiceDumpProcessesProto.LruProcesses.NON_SVC_AT,
                    total - mLruProcessServiceStart);
            writeProcessOomListToProto(proto,
                    ActivityManagerServiceDumpProcessesProto.LruProcesses.LIST, mService,
                    mLruProcesses, true, dumpPackage);
            proto.end(lruToken);
        }

        mService.writeOtherProcessesInfoToProtoLSP(proto, dumpPackage, dumpAppId, numPers);
    }

    private static ArrayList<Pair<ProcessRecord, Integer>> sortProcessOomList(
            List<ProcessRecord> origList, String dumpPackage) {
        ArrayList<Pair<ProcessRecord, Integer>> list =
                new ArrayList<Pair<ProcessRecord, Integer>>(origList.size());
        for (int i = 0, size = origList.size(); i < size; i++) {
            ProcessRecord r = origList.get(i);
            if (dumpPackage != null && !r.getPkgList().containsKey(dumpPackage)) {
                continue;
            }
            list.add(new Pair<ProcessRecord, Integer>(origList.get(i), i));
        }

        Comparator<Pair<ProcessRecord, Integer>> comparator =
                new Comparator<Pair<ProcessRecord, Integer>>() {
            @Override
            public int compare(Pair<ProcessRecord, Integer> object1,
                    Pair<ProcessRecord, Integer> object2) {
                final int adj = object2.first.mState.getSetAdj() - object1.first.mState.getSetAdj();
                if (adj != 0) {
                    return adj;
                }
                final int procState = object2.first.mState.getSetProcState()
                        - object1.first.mState.getSetProcState();
                if (procState != 0) {
                    return procState;
                }
                final int val = object2.second - object1.second;
                if (val != 0) {
                    return val;
                }
                return 0;
            }
        };

        Collections.sort(list, comparator);
        return list;
    }

    private static boolean writeProcessOomListToProto(ProtoOutputStream proto, long fieldId,
            ActivityManagerService service, List<ProcessRecord> origList,
            boolean inclDetails, String dumpPackage) {
        ArrayList<Pair<ProcessRecord, Integer>> list = sortProcessOomList(origList, dumpPackage);
        if (list.isEmpty()) return false;

        final long curUptime = SystemClock.uptimeMillis();

        for (int i = list.size() - 1; i >= 0; i--) {
            ProcessRecord r = list.get(i).first;
            final ProcessStateRecord state = r.mState;
            final ProcessServiceRecord psr = r.mServices;
            long token = proto.start(fieldId);
            String oomAdj = makeOomAdjString(state.getSetAdj(), true);
            proto.write(ProcessOomProto.PERSISTENT, r.isPersistent());
            proto.write(ProcessOomProto.NUM, (origList.size() - 1) - list.get(i).second);
            proto.write(ProcessOomProto.OOM_ADJ, oomAdj);
            int schedGroup = ProcessOomProto.SCHED_GROUP_UNKNOWN;
            switch (state.getSetSchedGroup()) {
                case SCHED_GROUP_BACKGROUND:
                    schedGroup = ProcessOomProto.SCHED_GROUP_BACKGROUND;
                    break;
                case SCHED_GROUP_DEFAULT:
                    schedGroup = ProcessOomProto.SCHED_GROUP_DEFAULT;
                    break;
                case SCHED_GROUP_TOP_APP:
                    schedGroup = ProcessOomProto.SCHED_GROUP_TOP_APP;
                    break;
                case SCHED_GROUP_TOP_APP_BOUND:
                    schedGroup = ProcessOomProto.SCHED_GROUP_TOP_APP_BOUND;
                    break;
            }
            if (schedGroup != ProcessOomProto.SCHED_GROUP_UNKNOWN) {
                proto.write(ProcessOomProto.SCHED_GROUP, schedGroup);
            }
            if (state.hasForegroundActivities()) {
                proto.write(ProcessOomProto.ACTIVITIES, true);
            } else if (psr.hasForegroundServices()) {
                proto.write(ProcessOomProto.SERVICES, true);
            }
            proto.write(ProcessOomProto.STATE,
                    makeProcStateProtoEnum(state.getCurProcState()));
            proto.write(ProcessOomProto.TRIM_MEMORY_LEVEL, r.mProfile.getTrimMemoryLevel());
            r.dumpDebug(proto, ProcessOomProto.PROC);
            proto.write(ProcessOomProto.ADJ_TYPE, state.getAdjType());
            if (state.getAdjSource() != null || state.getAdjTarget() != null) {
                if (state.getAdjTarget() instanceof  ComponentName) {
                    ComponentName cn = (ComponentName) state.getAdjTarget();
                    cn.dumpDebug(proto, ProcessOomProto.ADJ_TARGET_COMPONENT_NAME);
                } else if (state.getAdjTarget() != null) {
                    proto.write(ProcessOomProto.ADJ_TARGET_OBJECT, state.getAdjTarget().toString());
                }
                if (state.getAdjSource() instanceof ProcessRecord) {
                    ProcessRecord p = (ProcessRecord) state.getAdjSource();
                    p.dumpDebug(proto, ProcessOomProto.ADJ_SOURCE_PROC);
                } else if (state.getAdjSource() != null) {
                    proto.write(ProcessOomProto.ADJ_SOURCE_OBJECT, state.getAdjSource().toString());
                }
            }
            if (inclDetails) {
                long detailToken = proto.start(ProcessOomProto.DETAIL);
                proto.write(ProcessOomProto.Detail.MAX_ADJ, state.getMaxAdj());
                proto.write(ProcessOomProto.Detail.CUR_RAW_ADJ, state.getCurRawAdj());
                proto.write(ProcessOomProto.Detail.SET_RAW_ADJ, state.getSetRawAdj());
                proto.write(ProcessOomProto.Detail.CUR_ADJ, state.getCurAdj());
                proto.write(ProcessOomProto.Detail.SET_ADJ, state.getSetAdj());
                proto.write(ProcessOomProto.Detail.CURRENT_STATE,
                        makeProcStateProtoEnum(state.getCurProcState()));
                proto.write(ProcessOomProto.Detail.SET_STATE,
                        makeProcStateProtoEnum(state.getSetProcState()));
                proto.write(ProcessOomProto.Detail.LAST_PSS, DebugUtils.sizeValueToString(
                        r.mProfile.getLastPss() * 1024, new StringBuilder()));
                proto.write(ProcessOomProto.Detail.LAST_SWAP_PSS, DebugUtils.sizeValueToString(
                        r.mProfile.getLastSwapPss() * 1024, new StringBuilder()));
                proto.write(ProcessOomProto.Detail.LAST_CACHED_PSS, DebugUtils.sizeValueToString(
                        r.mProfile.getLastCachedPss() * 1024, new StringBuilder()));
                proto.write(ProcessOomProto.Detail.CACHED, state.isCached());
                proto.write(ProcessOomProto.Detail.EMPTY, state.isEmpty());
                proto.write(ProcessOomProto.Detail.HAS_ABOVE_CLIENT, psr.hasAboveClient());

                if (state.getSetProcState() >= ActivityManager.PROCESS_STATE_SERVICE) {
                    long lastCpuTime = r.mProfile.mLastCpuTime.get();
                    long uptimeSince = curUptime - service.mLastPowerCheckUptime;
                    if (lastCpuTime != 0 && uptimeSince > 0) {
                        long timeUsed = r.mProfile.mCurCpuTime.get() - lastCpuTime;
                        long cpuTimeToken = proto.start(ProcessOomProto.Detail.SERVICE_RUN_TIME);
                        proto.write(ProcessOomProto.Detail.CpuRunTime.OVER_MS, uptimeSince);
                        proto.write(ProcessOomProto.Detail.CpuRunTime.USED_MS, timeUsed);
                        proto.write(ProcessOomProto.Detail.CpuRunTime.ULTILIZATION,
                                (100.0 * timeUsed) / uptimeSince);
                        proto.end(cpuTimeToken);
                    }
                }
                proto.end(detailToken);
            }
            proto.end(token);
        }

        return true;
    }

    private static boolean dumpProcessOomList(PrintWriter pw,
            ActivityManagerService service, List<ProcessRecord> origList,
            String prefix, String normalLabel, String persistentLabel,
            boolean inclDetails, String dumpPackage) {

        ArrayList<Pair<ProcessRecord, Integer>> list = sortProcessOomList(origList, dumpPackage);
        if (list.isEmpty()) return false;

        final long curUptime = SystemClock.uptimeMillis();
        final long uptimeSince = curUptime - service.mLastPowerCheckUptime;

        for (int i = list.size() - 1; i >= 0; i--) {
            ProcessRecord r = list.get(i).first;
            final ProcessStateRecord state = r.mState;
            final ProcessServiceRecord psr = r.mServices;
            String oomAdj = makeOomAdjString(state.getSetAdj(), false);
            char schedGroup;
            switch (state.getSetSchedGroup()) {
                case SCHED_GROUP_BACKGROUND:
                    schedGroup = 'b';
                    break;
                case SCHED_GROUP_DEFAULT:
                    schedGroup = 'F';
                    break;
                case SCHED_GROUP_TOP_APP:
                    schedGroup = 'T';
                    break;
                case SCHED_GROUP_RESTRICTED:
                    schedGroup = 'R';
                    break;
                case SCHED_GROUP_TOP_APP_BOUND:
                    schedGroup = 'B';
                    break;
                default:
                    schedGroup = '?';
                    break;
            }
            char foreground;
            if (state.hasForegroundActivities()) {
                foreground = 'A';
            } else if (psr.hasForegroundServices()) {
                foreground = 'S';
            } else {
                foreground = ' ';
            }
            String procState = makeProcStateString(state.getCurProcState());
            pw.print(prefix);
            pw.print(r.isPersistent() ? persistentLabel : normalLabel);
            pw.print(" #");
            int num = (origList.size() - 1) - list.get(i).second;
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
            pw.print(' ');
            ActivityManager.printCapabilitiesSummary(pw, state.getCurCapability());
            pw.print(' ');
            pw.print(" t:");
            if (r.mProfile.getTrimMemoryLevel() < 10) pw.print(' ');
            pw.print(r.mProfile.getTrimMemoryLevel());
            pw.print(' ');
            pw.print(r.toShortString());
            pw.print(" (");
            pw.print(state.getAdjType());
            pw.println(')');
            if (state.getAdjSource() != null || state.getAdjTarget() != null) {
                pw.print(prefix);
                pw.print("    ");
                if (state.getAdjTarget() instanceof ComponentName) {
                    pw.print(((ComponentName) state.getAdjTarget()).flattenToShortString());
                } else if (state.getAdjTarget() != null) {
                    pw.print(state.getAdjTarget().toString());
                } else {
                    pw.print("{null}");
                }
                pw.print("<=");
                if (state.getAdjSource() instanceof ProcessRecord) {
                    pw.print("Proc{");
                    pw.print(((ProcessRecord) state.getAdjSource()).toShortString());
                    pw.println("}");
                } else if (state.getAdjSource() != null) {
                    pw.println(state.getAdjSource().toString());
                } else {
                    pw.println("{null}");
                }
            }
            if (inclDetails) {
                pw.print(prefix);
                pw.print("    ");
                pw.print("oom: max="); pw.print(state.getMaxAdj());
                pw.print(" curRaw="); pw.print(state.getCurRawAdj());
                pw.print(" setRaw="); pw.print(state.getSetRawAdj());
                pw.print(" cur="); pw.print(state.getCurAdj());
                pw.print(" set="); pw.println(state.getSetAdj());
                pw.print(prefix);
                pw.print("    ");
                pw.print("state: cur="); pw.print(makeProcStateString(state.getCurProcState()));
                pw.print(" set="); pw.print(makeProcStateString(state.getSetProcState()));
                pw.print(" lastPss=");
                DebugUtils.printSizeValue(pw, r.mProfile.getLastPss() * 1024);
                pw.print(" lastSwapPss=");
                DebugUtils.printSizeValue(pw, r.mProfile.getLastSwapPss() * 1024);
                pw.print(" lastCachedPss=");
                DebugUtils.printSizeValue(pw, r.mProfile.getLastCachedPss() * 1024);
                pw.println();
                pw.print(prefix);
                pw.print("    ");
                pw.print("cached="); pw.print(state.isCached());
                pw.print(" empty="); pw.print(state.isEmpty());
                pw.print(" hasAboveClient="); pw.println(psr.hasAboveClient());

                if (state.getSetProcState() >= ActivityManager.PROCESS_STATE_SERVICE) {
                    long lastCpuTime = r.mProfile.mLastCpuTime.get();
                    if (lastCpuTime != 0 && uptimeSince > 0) {
                        long timeUsed = r.mProfile.mCurCpuTime.get() - lastCpuTime;
                        pw.print(prefix);
                        pw.print("    ");
                        pw.print("run cpu over ");
                        TimeUtils.formatDuration(uptimeSince, pw);
                        pw.print(" used ");
                        TimeUtils.formatDuration(timeUsed, pw);
                        pw.print(" (");
                        pw.print((timeUsed * 100) / uptimeSince);
                        pw.println("%)");
                    }
                }
            }
        }
        return true;
    }

    private void printOomLevel(PrintWriter pw, String name, int adj) {
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
        pw.print(ActivityManagerService.stringifySize(getMemLevel(adj), 1024));
        pw.println(")");
    }

    @GuardedBy("mService")
    boolean dumpOomLocked(FileDescriptor fd, PrintWriter pw, boolean needSep, String[] args,
            int opti, boolean dumpAll, String dumpPackage, boolean inclGc) {
        if (getLruSizeLOSP() > 0) {
            if (needSep) pw.println();
            needSep = true;
            pw.println("  OOM levels:");
            printOomLevel(pw, "SYSTEM_ADJ", SYSTEM_ADJ);
            printOomLevel(pw, "PERSISTENT_PROC_ADJ", PERSISTENT_PROC_ADJ);
            printOomLevel(pw, "PERSISTENT_SERVICE_ADJ", PERSISTENT_SERVICE_ADJ);
            printOomLevel(pw, "FOREGROUND_APP_ADJ", FOREGROUND_APP_ADJ);
            printOomLevel(pw, "VISIBLE_APP_ADJ", VISIBLE_APP_ADJ);
            printOomLevel(pw, "PERCEPTIBLE_APP_ADJ", PERCEPTIBLE_APP_ADJ);
            printOomLevel(pw, "PERCEPTIBLE_MEDIUM_APP_ADJ", PERCEPTIBLE_MEDIUM_APP_ADJ);
            printOomLevel(pw, "PERCEPTIBLE_LOW_APP_ADJ", PERCEPTIBLE_LOW_APP_ADJ);
            printOomLevel(pw, "BACKUP_APP_ADJ", BACKUP_APP_ADJ);
            printOomLevel(pw, "HEAVY_WEIGHT_APP_ADJ", HEAVY_WEIGHT_APP_ADJ);
            printOomLevel(pw, "SERVICE_ADJ", SERVICE_ADJ);
            printOomLevel(pw, "HOME_APP_ADJ", HOME_APP_ADJ);
            printOomLevel(pw, "PREVIOUS_APP_ADJ", PREVIOUS_APP_ADJ);
            printOomLevel(pw, "SERVICE_B_ADJ", SERVICE_B_ADJ);
            printOomLevel(pw, "CACHED_APP_MIN_ADJ", CACHED_APP_MIN_ADJ);
            printOomLevel(pw, "CACHED_APP_MAX_ADJ", CACHED_APP_MAX_ADJ);

            if (needSep) pw.println();
            pw.print("  Process OOM control ("); pw.print(getLruSizeLOSP());
            pw.print(" total, non-act at ");
            pw.print(getLruSizeLOSP() - mLruProcessActivityStart);
            pw.print(", non-svc at ");
            pw.print(getLruSizeLOSP() - mLruProcessServiceStart);
            pw.println("):");
            dumpProcessOomList(pw, mService, mLruProcesses,
                    "    ", "Proc", "PERS", true, dumpPackage);
            needSep = true;
        }

        synchronized (mService.mAppProfiler.mProfilerLock) {
            mService.mAppProfiler.dumpProcessesToGc(pw, needSep, dumpPackage);
        }

        pw.println();
        mService.mAtmInternal.dumpForOom(pw);

        return true;
    }

    void registerProcessObserver(IProcessObserver observer) {
        mProcessObservers.register(observer);
    }

    void unregisterProcessObserver(IProcessObserver observer) {
        mProcessObservers.unregister(observer);
    }

    void dispatchProcessesChanged() {
        int numOfChanges;
        synchronized (mProcessChangeLock) {
            numOfChanges = mPendingProcessChanges.size();
            if (mActiveProcessChanges.length < numOfChanges) {
                mActiveProcessChanges = new ProcessChangeItem[numOfChanges];
            }
            mPendingProcessChanges.toArray(mActiveProcessChanges);
            mPendingProcessChanges.clear();
            if (DEBUG_PROCESS_OBSERVERS) {
                Slog.i(TAG_PROCESS_OBSERVERS,
                        "*** Delivering " + numOfChanges + " process changes");
            }
        }

        int i = mProcessObservers.beginBroadcast();
        while (i > 0) {
            i--;
            final IProcessObserver observer = mProcessObservers.getBroadcastItem(i);
            if (observer != null) {
                try {
                    for (int j = 0; j < numOfChanges; j++) {
                        ProcessChangeItem item = mActiveProcessChanges[j];
                        if ((item.changes & ProcessChangeItem.CHANGE_ACTIVITIES) != 0) {
                            if (DEBUG_PROCESS_OBSERVERS) {
                                Slog.i(TAG_PROCESS_OBSERVERS,
                                        "ACTIVITIES CHANGED pid=" + item.pid + " uid="
                                        + item.uid + ": " + item.foregroundActivities);
                            }
                            observer.onForegroundActivitiesChanged(item.pid, item.uid,
                                    item.foregroundActivities);
                        }
                        if ((item.changes & ProcessChangeItem.CHANGE_FOREGROUND_SERVICES) != 0) {
                            if (DEBUG_PROCESS_OBSERVERS) {
                                Slog.i(TAG_PROCESS_OBSERVERS,
                                        "FOREGROUND SERVICES CHANGED pid=" + item.pid + " uid="
                                        + item.uid + ": " + item.foregroundServiceTypes);
                            }
                            observer.onForegroundServicesChanged(item.pid, item.uid,
                                    item.foregroundServiceTypes);
                        }
                    }
                } catch (RemoteException e) {
                }
            }
        }
        mProcessObservers.finishBroadcast();

        synchronized (mProcessChangeLock) {
            for (int j = 0; j < numOfChanges; j++) {
                mAvailProcessChanges.add(mActiveProcessChanges[j]);
            }
        }
    }

    @GuardedBy("mService")
    ProcessChangeItem enqueueProcessChangeItemLocked(int pid, int uid) {
        synchronized (mProcessChangeLock) {
            int i = mPendingProcessChanges.size() - 1;
            ActivityManagerService.ProcessChangeItem item = null;
            while (i >= 0) {
                item = mPendingProcessChanges.get(i);
                if (item.pid == pid) {
                    if (DEBUG_PROCESS_OBSERVERS) {
                        Slog.i(TAG_PROCESS_OBSERVERS, "Re-using existing item: " + item);
                    }
                    break;
                }
                i--;
            }

            if (i < 0) {
                // No existing item in pending changes; need a new one.
                final int num = mAvailProcessChanges.size();
                if (num > 0) {
                    item = mAvailProcessChanges.remove(num - 1);
                    if (DEBUG_PROCESS_OBSERVERS) {
                        Slog.i(TAG_PROCESS_OBSERVERS, "Retrieving available item: " + item);
                    }
                } else {
                    item = new ActivityManagerService.ProcessChangeItem();
                    if (DEBUG_PROCESS_OBSERVERS) {
                        Slog.i(TAG_PROCESS_OBSERVERS, "Allocating new item: " + item);
                    }
                }
                item.changes = 0;
                item.pid = pid;
                item.uid = uid;
                if (mPendingProcessChanges.size() == 0) {
                    if (DEBUG_PROCESS_OBSERVERS) {
                        Slog.i(TAG_PROCESS_OBSERVERS, "*** Enqueueing dispatch processes changed!");
                    }
                    mService.mUiHandler.obtainMessage(DISPATCH_PROCESSES_CHANGED_UI_MSG)
                            .sendToTarget();
                }
                mPendingProcessChanges.add(item);
            }

            return item;
        }
    }

    @GuardedBy("mService")
    void scheduleDispatchProcessDiedLocked(int pid, int uid) {
        synchronized (mProcessChangeLock) {
            for (int i = mPendingProcessChanges.size() - 1; i >= 0; i--) {
                ProcessChangeItem item = mPendingProcessChanges.get(i);
                if (pid > 0 && item.pid == pid) {
                    mPendingProcessChanges.remove(i);
                    mAvailProcessChanges.add(item);
                }
            }
            mService.mUiHandler.obtainMessage(DISPATCH_PROCESS_DIED_UI_MSG, pid, uid,
                    null).sendToTarget();
        }
    }

    void dispatchProcessDied(int pid, int uid) {
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

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    ArrayList<ProcessRecord> collectProcessesLOSP(int start, boolean allPkgs, String[] args) {
        ArrayList<ProcessRecord> procs;
        if (args != null && args.length > start
                && args[start].charAt(0) != '-') {
            procs = new ArrayList<ProcessRecord>();
            int pid = -1;
            try {
                pid = Integer.parseInt(args[start]);
            } catch (NumberFormatException e) {
            }
            for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
                ProcessRecord proc = mLruProcesses.get(i);
                if (proc.getPid() > 0 && proc.getPid() == pid) {
                    procs.add(proc);
                } else if (allPkgs && proc.getPkgList() != null
                        && proc.getPkgList().containsKey(args[start])) {
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
        return procs;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    void updateApplicationInfoLOSP(List<String> packagesToUpdate, int userId,
            boolean updateFrameworkRes) {
        final ArrayList<WindowProcessController> targetProcesses = new ArrayList<>();
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            final ProcessRecord app = mLruProcesses.get(i);
            if (app.getThread() == null) {
                continue;
            }

            if (userId != UserHandle.USER_ALL && app.userId != userId) {
                continue;
            }

            app.getPkgList().forEachPackage(packageName -> {
                if (updateFrameworkRes || packagesToUpdate.contains(packageName)) {
                    try {
                        final ApplicationInfo ai = AppGlobals.getPackageManager()
                                .getApplicationInfo(packageName, STOCK_PM_FLAGS, app.userId);
                        if (ai != null) {
                            if (ai.packageName.equals(app.info.packageName)) {
                                app.info = ai;
                                PlatformCompatCache.getInstance()
                                        .onApplicationInfoChanged(ai);
                            }
                            app.getThread().scheduleApplicationInfoChanged(ai);
                            targetProcesses.add(app.getWindowProcessController());
                        }
                    } catch (RemoteException e) {
                        Slog.w(TAG, String.format("Failed to update %s ApplicationInfo for %s",
                                    packageName, app));
                    }
                }
            });
        }

        mService.mActivityTaskManager.updateAssetConfiguration(targetProcesses, updateFrameworkRes);
    }

    @GuardedBy("mService")
    void sendPackageBroadcastLocked(int cmd, String[] packages, int userId) {
        boolean foundProcess = false;
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord r = mLruProcesses.get(i);
            final IApplicationThread thread = r.getThread();
            if (thread != null && (userId == UserHandle.USER_ALL || r.userId == userId)) {
                try {
                    for (int index = packages.length - 1; index >= 0 && !foundProcess; index--) {
                        if (packages[index].equals(r.info.packageName)) {
                            foundProcess = true;
                        }
                    }
                    thread.dispatchPackageBroadcast(cmd, packages);
                } catch (RemoteException ex) {
                }
            }
        }

        if (!foundProcess) {
            try {
                AppGlobals.getPackageManager().notifyPackagesReplacedReceived(packages);
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Returns the uid's process state or {@link ActivityManager#PROCESS_STATE_NONEXISTENT}
     * if not running
     */
    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getUidProcStateLOSP(int uid) {
        UidRecord uidRec = mActiveUids.get(uid);
        return uidRec == null ? PROCESS_STATE_NONEXISTENT : uidRec.getCurProcState();
    }

    /**
     * Returns the uid's process capability or {@link ActivityManager#PROCESS_CAPABILITY_NONE}
     * if not running
     */
    @GuardedBy(anyOf = {"mService", "mProcLock"})
    @ProcessCapability int getUidProcessCapabilityLOSP(int uid) {
        UidRecord uidRec = mActiveUids.get(uid);
        return uidRec == null ? PROCESS_CAPABILITY_NONE : uidRec.getCurCapability();
    }

    /** Returns the UidRecord for the given uid, if it exists. */
    @GuardedBy(anyOf = {"mService", "mProcLock"})
    UidRecord getUidRecordLOSP(int uid) {
        return mActiveUids.get(uid);
    }

    /**
     * Call {@link ActivityManagerService#doStopUidLocked}
     * (which will also stop background services) for all idle UIDs.
     */
    @GuardedBy("mService")
    void doStopUidForIdleUidsLocked() {
        final int size = mActiveUids.size();
        for (int i = 0; i < size; i++) {
            final int uid = mActiveUids.keyAt(i);
            if (UserHandle.isCore(uid)) {
                continue;
            }
            final UidRecord uidRec = mActiveUids.valueAt(i);
            if (!uidRec.isIdle()) {
                continue;
            }
            mService.doStopUidLocked(uidRec.getUid(), uidRec);
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
    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getBlockStateForUid(UidRecord uidRec) {
        // Denotes whether uid's process state is currently allowed network access.
        final boolean isAllowed =
                isProcStateAllowedWhileIdleOrPowerSaveMode(uidRec.getCurProcState(),
                        uidRec.getCurCapability())
                || isProcStateAllowedWhileOnRestrictBackground(uidRec.getCurProcState(),
                        uidRec.getCurCapability());
        // Denotes whether uid's process state was previously allowed network access.
        final boolean wasAllowed =
                isProcStateAllowedWhileIdleOrPowerSaveMode(uidRec.getSetProcState(),
                        uidRec.getSetCapability())
                || isProcStateAllowedWhileOnRestrictBackground(uidRec.getSetProcState(),
                        uidRec.getSetCapability());

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

    /**
     * Increments the {@link UidRecord#curProcStateSeq} for all uids using global seq counter
     * {@link ProcessList#mProcStateSeqCounter} and checks if any uid is coming
     * from background to foreground or vice versa and if so, notifies the app if it needs to block.
     */
    @VisibleForTesting
    @GuardedBy(anyOf = {"mService", "mProcLock"})
    void incrementProcStateSeqAndNotifyAppsLOSP(ActiveUids activeUids) {
        for (int i = activeUids.size() - 1; i >= 0; --i) {
            final UidRecord uidRec = activeUids.valueAt(i);
            uidRec.curProcStateSeq = getNextProcStateSeq();
        }
        if (mService.mConstants.mNetworkAccessTimeoutMs <= 0) {
            return;
        }
        // Used for identifying which uids need to block for network.
        ArrayList<Integer> blockingUids = null;
        for (int i = activeUids.size() - 1; i >= 0; --i) {
            final UidRecord uidRec = activeUids.valueAt(i);
            // If the network is not restricted for uid, then nothing to do here.
            if (!mService.mInjector.isNetworkRestrictedForUid(uidRec.getUid())) {
                continue;
            }
            if (!UserHandle.isApp(uidRec.getUid()) || !uidRec.hasInternetPermission) {
                continue;
            }
            // If process state and capabilities are not changed, then there's nothing to do.
            if (uidRec.getSetProcState() == uidRec.getCurProcState()
                    && uidRec.getSetCapability() == uidRec.getCurCapability()) {
                continue;
            }
            final int blockState = getBlockStateForUid(uidRec);
            // No need to inform the app when the blockState is NETWORK_STATE_NO_CHANGE as
            // there's nothing the app needs to do in this scenario.
            if (blockState == NETWORK_STATE_NO_CHANGE) {
                continue;
            }
            synchronized (uidRec.networkStateLock) {
                if (blockState == NETWORK_STATE_BLOCK) {
                    if (blockingUids == null) {
                        blockingUids = new ArrayList<>();
                    }
                    blockingUids.add(uidRec.getUid());
                } else {
                    if (DEBUG_NETWORK) {
                        Slog.d(TAG_NETWORK, "uid going to background, notifying all blocking"
                                + " threads for uid: " + uidRec);
                    }
                    if (uidRec.procStateSeqWaitingForNetwork != 0) {
                        uidRec.networkStateLock.notifyAll();
                    }
                }
            }
        }

        // There are no uids that need to block, so nothing more to do.
        if (blockingUids == null) {
            return;
        }

        for (int i = mLruProcesses.size() - 1; i >= 0; --i) {
            final ProcessRecord app = mLruProcesses.get(i);
            if (!blockingUids.contains(app.uid)) {
                continue;
            }
            final IApplicationThread thread = app.getThread();
            if (!app.isKilledByAm() && thread != null) {
                final UidRecord uidRec = getUidRecordLOSP(app.uid);
                try {
                    if (DEBUG_NETWORK) {
                        Slog.d(TAG_NETWORK, "Informing app thread that it needs to block: "
                                + uidRec);
                    }
                    if (uidRec != null) {
                        thread.setNetworkBlockSeq(uidRec.curProcStateSeq);
                    }
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    long getNextProcStateSeq() {
        return ++mProcStateSeqCounter;
    }

    /**
     * Create a server socket in system_server, zygote will connect to it
     * in order to send unsolicited messages to system_server.
     */
    private LocalSocket createSystemServerSocketForZygote() {
        // The file system entity for this socket is created with 0666 perms, owned
        // by system:system. selinux restricts things so that only zygotes can
        // access it.
        final File socketFile = new File(UNSOL_ZYGOTE_MSG_SOCKET_PATH);
        if (socketFile.exists()) {
            socketFile.delete();
        }

        LocalSocket serverSocket = null;
        try {
            serverSocket = new LocalSocket(LocalSocket.SOCKET_DGRAM);
            serverSocket.bind(new LocalSocketAddress(
                    UNSOL_ZYGOTE_MSG_SOCKET_PATH, LocalSocketAddress.Namespace.FILESYSTEM));
            Os.chmod(UNSOL_ZYGOTE_MSG_SOCKET_PATH, 0666);
        } catch (Exception e) {
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ex) {
                }
                serverSocket = null;
            }
        }
        return serverSocket;
    }

    /**
     * Handle the unsolicited message from zygote.
     */
    private int handleZygoteMessages(FileDescriptor fd, int events) {
        final int eventFd = fd.getInt$();
        if ((events & EVENT_INPUT) != 0) {
            // An incoming message from zygote
            try {
                final int len = Os.read(fd, mZygoteUnsolicitedMessage, 0,
                        mZygoteUnsolicitedMessage.length);
                if (len > 0 && mZygoteSigChldMessage.length == Zygote.nativeParseSigChld(
                        mZygoteUnsolicitedMessage, len, mZygoteSigChldMessage)) {
                    mAppExitInfoTracker.handleZygoteSigChld(
                            mZygoteSigChldMessage[0] /* pid */,
                            mZygoteSigChldMessage[1] /* uid */,
                            mZygoteSigChldMessage[2] /* status */);
                }
            } catch (Exception e) {
                Slog.w(TAG, "Exception in reading unsolicited zygote message: " + e);
            }
        }
        return EVENT_INPUT;
    }

    /**
     * Handle the death notification if it's a dying app.
     *
     * @return {@code true} if it's a dying app that we were tracking.
     */
    @GuardedBy("mService")
    boolean handleDyingAppDeathLocked(ProcessRecord app, int pid) {
        if (mProcessNames.get(app.processName, app.uid) != app
                && mDyingProcesses.get(app.processName, app.uid) == app) {
            // App has been removed already, meaning cleanup has done.
            Slog.v(TAG, "Got obituary of " + pid + ":" + app.processName);
            app.unlinkDeathRecipient();
            // It's really gone now, let's remove from the dying process list.
            mDyingProcesses.remove(app.processName, app.uid);
            app.setDyingPid(0);
            handlePrecedingAppDiedLocked(app);
            // Remove from the LRU list if it's still there.
            removeLruProcessLocked(app);
            return true;
        }
        return false;
    }

    /**
     * Handle the case where the given app is a preceding instance of another process instance.
     *
     * @return {@code false} if this given app should not be allowed to restart.
     */
    @GuardedBy("mService")
    boolean handlePrecedingAppDiedLocked(ProcessRecord app) {
        if (app.mSuccessor != null) {
            // We don't allow restart with this ProcessRecord now,
            // because we have created a new one already.
            // If it's persistent, add the successor to mPersistentStartingProcesses
            if (app.isPersistent() && !app.isRemoved()) {
                if (mService.mPersistentStartingProcesses.indexOf(app.mSuccessor) < 0) {
                    mService.mPersistentStartingProcesses.add(app.mSuccessor);
                }
            }
            // clean up the field so the successor's proc starter could proceed.
            app.mSuccessor.mPredecessor = null;
            app.mSuccessor = null;
            // Remove any pending timeout msg.
            mService.mProcStartHandler.removeMessages(
                    ProcStartHandler.MSG_PROCESS_KILL_TIMEOUT, app);
            // Kick off the proc start for the succeeding instance
            mService.mProcStartHandler.obtainMessage(
                    ProcStartHandler.MSG_PROCESS_DIED, app).sendToTarget();
            return false;
        }
        return true;
    }

    @GuardedBy("mService")
    void updateBackgroundRestrictedForUidPackageLocked(int uid, String packageName,
            boolean restricted) {
        final UidRecord uidRec = getUidRecordLOSP(uid);
        if (uidRec != null) {
            final long nowElapsed = SystemClock.elapsedRealtime();
            uidRec.forEachProcess(app -> {
                if (TextUtils.equals(app.info.packageName, packageName)) {
                    app.mState.setBackgroundRestricted(restricted);
                    if (restricted) {
                        mAppsInBackgroundRestricted.add(app);
                        final long future = killAppIfBgRestrictedAndCachedIdleLocked(
                                app, nowElapsed);
                        if (future > 0
                                && (mService.mDeterministicUidIdle
                                        || !mService.mHandler.hasMessages(IDLE_UIDS_MSG))) {
                            mService.mHandler.sendEmptyMessageDelayed(IDLE_UIDS_MSG,
                                    future - nowElapsed);
                        }
                    } else {
                        mAppsInBackgroundRestricted.remove(app);
                    }
                    if (!app.isKilledByAm()) {
                        mService.enqueueOomAdjTargetLocked(app);
                    }
                }
            });
            /* Will be a no-op if nothing pending */
            mService.updateOomAdjPendingTargetsLocked(OOM_ADJ_REASON_RESTRICTION_CHANGE);
        }
    }

    /**
     * Kill the given app if it's in cached idle and background restricted mode.
     *
     * @return A future timestamp when the app should be killed at, or a 0 if it shouldn't
     * be killed or it has been killed.
     */
    @GuardedBy("mService")
    long killAppIfBgRestrictedAndCachedIdleLocked(ProcessRecord app, long nowElapsed) {
        final UidRecord uidRec = app.getUidRecord();
        final long lastCanKillTime = app.mState.getLastCanKillOnBgRestrictedAndIdleTime();
        if (!mService.mConstants.mKillBgRestrictedAndCachedIdle
                || app.isKilled() || app.getThread() == null || uidRec == null || !uidRec.isIdle()
                || !app.isCached() || app.mState.shouldNotKillOnBgRestrictedAndIdle()
                || !app.mState.isBackgroundRestricted() || lastCanKillTime == 0) {
            return 0;
        }
        final long future = lastCanKillTime
                + mService.mConstants.mKillBgRestrictedAndCachedIdleSettleTimeMs;
        if (future <= nowElapsed) {
            app.killLocked("cached idle & background restricted",
                    ApplicationExitInfo.REASON_OTHER,
                    ApplicationExitInfo.SUBREASON_CACHED_IDLE_FORCED_APP_STANDBY,
                    true);
            return 0;
        }
        return future;
    }

    /**
     * Called by {@link ActivityManagerService#enqueueUidChangeLocked} only, it doesn't schedule
     * the standy killing checks because it should have been scheduled before enqueueing UID idle
     * changed.
     */
    @GuardedBy("mService")
    void killAppIfBgRestrictedAndCachedIdleLocked(UidRecord uidRec) {
        final long nowElapsed = SystemClock.elapsedRealtime();
        uidRec.forEachProcess(app -> killAppIfBgRestrictedAndCachedIdleLocked(app, nowElapsed));
    }

    /**
     * Called by ActivityManagerService when a process died.
     */
    @GuardedBy("mService")
    void noteProcessDiedLocked(final ProcessRecord app) {
        if (DEBUG_PROCESSES) {
            Slog.i(TAG, "note: " + app + " died, saving the exit info");
        }

        Watchdog.getInstance().processDied(app.processName, app.getPid());
        if (app.getDeathRecipient() == null
                && mDyingProcesses.get(app.processName, app.uid) == app) {
            // If we've done unlinkDeathRecipient before calling into this, remove from dying list.
            mDyingProcesses.remove(app.processName, app.uid);
            app.setDyingPid(0);
        }
        mAppExitInfoTracker.scheduleNoteProcessDied(app);
    }

    /**
     * Called by ActivityManagerService when a recoverable native crash occurs.
     */
    @GuardedBy("mService")
    void noteAppRecoverableCrash(final ProcessRecord app) {
        if (DEBUG_PROCESSES) {
            Slog.i(TAG, "note: " + app + " has a recoverable native crash");
        }
        mAppExitInfoTracker.scheduleNoteAppRecoverableCrash(app);
    }

    /**
     * Called by ActivityManagerService when it decides to kill an application process.
     */
    @GuardedBy("mService")
    void noteAppKill(final ProcessRecord app, final @Reason int reason,
            final @SubReason int subReason, final String msg) {
        if (DEBUG_PROCESSES) {
            Slog.i(TAG, "note: " + app + " is being killed, reason: " + reason
                    + ", sub-reason: " + subReason + ", message: " + msg);
        }
        if (app.getPid() > 0 && !app.isolated && app.getDeathRecipient() != null) {
            // We are killing it, put it into the dying process list.
            mDyingProcesses.put(app.processName, app.uid, app);
            app.setDyingPid(app.getPid());
        }
        mAppExitInfoTracker.scheduleNoteAppKill(app, reason, subReason, msg);
    }

    @GuardedBy("mService")
    void noteAppKill(final int pid, final int uid, final @Reason int reason,
            final @SubReason int subReason, final String msg) {
        if (DEBUG_PROCESSES) {
            Slog.i(TAG, "note: " + pid + " is being killed, reason: " + reason
                    + ", sub-reason: " + subReason + ", message: " + msg);
        }

        final ProcessRecord app;
        synchronized (mService.mPidsSelfLocked) {
            app = mService.mPidsSelfLocked.get(pid);
        }
        if (app != null && app.uid == uid && !app.isolated && app.getDeathRecipient() != null) {
            // We are killing it, put it into the dying process list.
            mDyingProcesses.put(app.processName, uid, app);
            app.setDyingPid(app.getPid());
        }
        mAppExitInfoTracker.scheduleNoteAppKill(pid, uid, reason, subReason, msg);
    }

    /**
     * Schedule to kill the given pids when the device is idle
     */
    void killProcessesWhenImperceptible(int[] pids, String reason, int requester) {
        if (ArrayUtils.isEmpty(pids)) {
            return;
        }

        synchronized (mService) {
            ProcessRecord app;
            for (int i = 0; i < pids.length; i++) {
                synchronized (mService.mPidsSelfLocked) {
                    app = mService.mPidsSelfLocked.get(pids[i]);
                }
                if (app != null) {
                    mImperceptibleKillRunner.enqueueLocked(app, reason, requester);
                }
            }
        }
    }

    /**
     * Get the number of foreground services in all processes and number of processes that have
     * foreground service within.
     */
    Pair<Integer, Integer> getNumForegroundServices() {
        int numForegroundServices = 0;
        int procs = 0;
        synchronized (mService) {
            for (int i = 0, size = mLruProcesses.size(); i < size; i++) {
                ProcessRecord pr = mLruProcesses.get(i);
                int numFgs = pr.mServices.getNumForegroundServices();
                if (numFgs > 0) {
                    numForegroundServices += numFgs;
                    procs++;
                }
            }
        }
        return new Pair<>(numForegroundServices, procs);
    }

    private final class ImperceptibleKillRunner extends UidObserver {
        private static final String EXTRA_PID = "pid";
        private static final String EXTRA_UID = "uid";
        private static final String EXTRA_TIMESTAMP = "timestamp";
        private static final String EXTRA_REASON = "reason";
        private static final String EXTRA_REQUESTER = "requester";

        private static final String DROPBOX_TAG_IMPERCEPTIBLE_KILL = "imperceptible_app_kill";
        private static final boolean LOG_TO_DROPBOX = false;

        // uid -> killing information mapping
        private SparseArray<List<Bundle>> mWorkItems = new SparseArray<List<Bundle>>();

        // The last time the various processes have been killed by us.
        private ProcessMap<Long> mLastProcessKillTimes = new ProcessMap<>();

        // Device idle or not.
        private volatile boolean mIdle;
        private boolean mUidObserverEnabled;
        private Handler mHandler;
        private IdlenessReceiver mReceiver;

        private final class H extends Handler {
            static final int MSG_DEVICE_IDLE = 0;
            static final int MSG_UID_GONE = 1;
            static final int MSG_UID_STATE_CHANGED = 2;

            H(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_DEVICE_IDLE:
                        handleDeviceIdle();
                        break;
                    case MSG_UID_GONE:
                        handleUidGone(msg.arg1 /* uid */);
                        break;
                    case MSG_UID_STATE_CHANGED:
                        handleUidStateChanged(msg.arg1 /* uid */, msg.arg2 /* procState */);
                        break;
                }
            }
        }

        private final class IdlenessReceiver extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                final PowerManager pm = mService.mContext.getSystemService(PowerManager.class);
                switch (intent.getAction()) {
                    case PowerManager.ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED:
                        notifyDeviceIdleness(pm.isLightDeviceIdleMode());
                        break;
                    case PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED:
                        notifyDeviceIdleness(pm.isDeviceIdleMode());
                        break;
                }
            }
        }

        ImperceptibleKillRunner(Looper looper) {
            mHandler = new H(looper);
        }

        @GuardedBy("mService")
        boolean enqueueLocked(ProcessRecord app, String reason, int requester) {
            // Throttle the killing request for potential bad app to avoid cpu thrashing
            Long last = app.isolated ? null : mLastProcessKillTimes.get(app.processName, app.uid);
            if ((last != null) && (SystemClock.uptimeMillis()
                    < (last + ActivityManagerConstants.MIN_CRASH_INTERVAL))) {
                return false;
            }

            final Bundle bundle = new Bundle();
            bundle.putInt(EXTRA_PID, app.getPid());
            bundle.putInt(EXTRA_UID, app.uid);
            // Since the pid could be reused, let's get the actual start time of each process
            bundle.putLong(EXTRA_TIMESTAMP, app.getStartTime());
            bundle.putString(EXTRA_REASON, reason);
            bundle.putInt(EXTRA_REQUESTER, requester);
            List<Bundle> list = mWorkItems.get(app.uid);
            if (list == null) {
                list = new ArrayList<Bundle>();
                mWorkItems.put(app.uid, list);
            }
            list.add(bundle);
            if (mReceiver == null) {
                mReceiver = new IdlenessReceiver();
                IntentFilter filter = new IntentFilter(
                        PowerManager.ACTION_LIGHT_DEVICE_IDLE_MODE_CHANGED);
                filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
                mService.mContext.registerReceiver(mReceiver, filter);
            }
            return true;
        }

        void notifyDeviceIdleness(boolean idle) {
            // No lock is held regarding mIdle, this function is the only updater and caller
            // won't re-entry.
            boolean diff = mIdle != idle;
            mIdle = idle;
            if (diff && idle) {
                synchronized (mService) {
                    if (mWorkItems.size() > 0) {
                        mHandler.sendEmptyMessage(H.MSG_DEVICE_IDLE);
                    }
                }
            }
        }

        private void handleDeviceIdle() {
            final DropBoxManager dbox = mService.mContext.getSystemService(DropBoxManager.class);
            final boolean logToDropbox = LOG_TO_DROPBOX && dbox != null
                    && dbox.isTagEnabled(DROPBOX_TAG_IMPERCEPTIBLE_KILL);

            synchronized (mService) {
                final int size = mWorkItems.size();
                for (int i = size - 1; mIdle && i >= 0; i--) {
                    List<Bundle> list = mWorkItems.valueAt(i);
                    final int len = list.size();
                    for (int j = len - 1; mIdle && j >= 0; j--) {
                        Bundle bundle = list.get(j);
                        if (killProcessLocked(
                                bundle.getInt(EXTRA_PID),
                                bundle.getInt(EXTRA_UID),
                                bundle.getLong(EXTRA_TIMESTAMP),
                                bundle.getString(EXTRA_REASON),
                                bundle.getInt(EXTRA_REQUESTER),
                                dbox, logToDropbox)) {
                            list.remove(j);
                        }
                    }
                    if (list.size() == 0) {
                        mWorkItems.removeAt(i);
                    }
                }
                registerUidObserverIfNecessaryLocked();
            }
        }

        @GuardedBy("mService")
        private void registerUidObserverIfNecessaryLocked() {
            // If there are still works remaining, register UID observer
            if (!mUidObserverEnabled && mWorkItems.size() > 0) {
                mUidObserverEnabled = true;
                mService.registerUidObserver(this,
                        ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE,
                        ActivityManager.PROCESS_STATE_UNKNOWN, "android");
            } else if (mUidObserverEnabled && mWorkItems.size() == 0) {
                mUidObserverEnabled = false;
                mService.unregisterUidObserver(this);
            }
        }

        /**
         * Kill the given processes, if they are not exempted.
         *
         * @return True if the process is killed, or it's gone already, or we are not allowed to
         *         kill it (one of the packages in this process is being exempted).
         */
        @GuardedBy("mService")
        private boolean killProcessLocked(final int pid, final int uid, final long timestamp,
                final String reason, final int requester, final DropBoxManager dbox,
                final boolean logToDropbox) {
            ProcessRecord app = null;
            synchronized (mService.mPidsSelfLocked) {
                app = mService.mPidsSelfLocked.get(pid);
            }

            if (app == null || app.getPid() != pid || app.uid != uid
                    || app.getStartTime() != timestamp) {
                // This process record has been reused for another process, meaning the old process
                // has been gone.
                return true;
            }

            if (app.getPkgList().searchEachPackage(pkgName -> {
                if (mService.mConstants.IMPERCEPTIBLE_KILL_EXEMPT_PACKAGES.contains(pkgName)) {
                    // One of the packages in this process is exempted
                    return Boolean.TRUE;
                }
                return null;
            }) != null) {
                return true;
            }

            if (mService.mConstants.IMPERCEPTIBLE_KILL_EXEMPT_PROC_STATES.contains(
                    app.mState.getReportedProcState())) {
                // We need to reschedule it.
                return false;
            }

            app.killLocked(reason, ApplicationExitInfo.REASON_OTHER,
                    ApplicationExitInfo.SUBREASON_IMPERCEPTIBLE, true);

            if (!app.isolated) {
                mLastProcessKillTimes.put(app.processName, app.uid, SystemClock.uptimeMillis());
            }

            if (logToDropbox) {
                final long now = SystemClock.elapsedRealtime();
                final StringBuilder sb = new StringBuilder();
                mService.appendDropBoxProcessHeaders(app, app.processName, null, sb);
                sb.append("Reason: " + reason).append("\n");
                sb.append("Requester UID: " + requester).append("\n");
                dbox.addText(DROPBOX_TAG_IMPERCEPTIBLE_KILL, sb.toString());
            }
            return true;
        }

        private void handleUidStateChanged(int uid, int procState) {
            final DropBoxManager dbox = mService.mContext.getSystemService(DropBoxManager.class);
            final boolean logToDropbox = dbox != null
                    && dbox.isTagEnabled(DROPBOX_TAG_IMPERCEPTIBLE_KILL);
            synchronized (mService) {
                if (mIdle && !mService.mConstants
                        .IMPERCEPTIBLE_KILL_EXEMPT_PROC_STATES.contains(procState)) {
                    List<Bundle> list = mWorkItems.get(uid);
                    if (list != null) {
                        final int len = list.size();
                        for (int j = len - 1; mIdle && j >= 0; j--) {
                            Bundle bundle = list.get(j);
                            if (killProcessLocked(
                                    bundle.getInt(EXTRA_PID),
                                    bundle.getInt(EXTRA_UID),
                                    bundle.getLong(EXTRA_TIMESTAMP),
                                    bundle.getString(EXTRA_REASON),
                                    bundle.getInt(EXTRA_REQUESTER),
                                    dbox, logToDropbox)) {
                                list.remove(j);
                            }
                        }
                        if (list.size() == 0) {
                            mWorkItems.remove(uid);
                        }
                        registerUidObserverIfNecessaryLocked();
                    }
                }
            }
        }

        private void handleUidGone(int uid) {
            synchronized (mService) {
                mWorkItems.remove(uid);
                registerUidObserverIfNecessaryLocked();
            }
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            mHandler.obtainMessage(H.MSG_UID_GONE, uid, 0).sendToTarget();
        }

        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq, int capability) {
            mHandler.obtainMessage(H.MSG_UID_STATE_CHANGED, uid, procState).sendToTarget();
        }
    };
}
