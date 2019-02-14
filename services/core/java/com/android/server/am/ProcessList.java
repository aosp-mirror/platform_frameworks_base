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

import static android.app.ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.ActivityThread.PROC_START_SEQ_IDENT;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AUTO;
import static android.os.Process.SYSTEM_UID;
import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
import static android.os.Process.getFreeMemory;
import static android.os.Process.getTotalMemory;
import static android.os.Process.killProcessQuiet;
import static android.os.Process.startWebView;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_LRU;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PROCESSES;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PSS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_UID_OBSERVERS;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityManagerService.KILL_APP_ZYGOTE_DELAY_MS;
import static com.android.server.am.ActivityManagerService.KILL_APP_ZYGOTE_MSG;
import static com.android.server.am.ActivityManagerService.PERSISTENT_MASK;
import static com.android.server.am.ActivityManagerService.PROC_START_TIMEOUT;
import static com.android.server.am.ActivityManagerService.PROC_START_TIMEOUT_MSG;
import static com.android.server.am.ActivityManagerService.PROC_START_TIMEOUT_WITH_WRAPPER;
import static com.android.server.am.ActivityManagerService.STOCK_PM_FLAGS;
import static com.android.server.am.ActivityManagerService.TAG_LRU;
import static com.android.server.am.ActivityManagerService.TAG_PROCESSES;
import static com.android.server.am.ActivityManagerService.TAG_PSS;
import static com.android.server.am.ActivityManagerService.TAG_UID_OBSERVERS;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.AppProtoEnums;
import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.res.Resources;
import android.graphics.Point;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.AppZygote;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.StrictMode;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.EventLog;
import android.util.LongSparseArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.StatsLog;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ProcessMap;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.os.Zygote;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.MemInfoReader;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.Watchdog;
import com.android.server.pm.dex.DexManager;
import com.android.server.wm.ActivityServiceConnectionsHolder;
import com.android.server.wm.WindowManagerService;

import dalvik.system.VMRuntime;

import libcore.io.IoUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * Activity manager code dealing with processes.
 *
 * Method naming convention:
 * <ul>
 * <li> Methods suffixed with "LS" should be called within the {@link #sLmkdSocketLock} lock.
 * </ul>
 */
public final class ProcessList {
    static final String TAG = TAG_WITH_CLASS_NAME ? "ProcessList" : TAG_AM;

    // The minimum time we allow between crashes, for us to consider this
    // application to be bad and stop and its services and reject broadcasts.
    static final int MIN_CRASH_INTERVAL = 60 * 1000;

    // OOM adjustments for processes in various states:

    // Uninitialized value for any major or minor adj fields
    static final int INVALID_ADJ = -10000;

    // Adjustment used in certain places where we don't know it yet.
    // (Generally this is something that is going to be cached, but we
    // don't know the exact value in the cached range to assign yet.)
    static final int UNKNOWN_ADJ = 1001;

    // This is a process only hosting activities that are not visible,
    // so it can be killed without any disruption.
    static final int CACHED_APP_MAX_ADJ = 999;
    static final int CACHED_APP_MIN_ADJ = 900;

    // This is the oom_adj level that we allow to die first. This cannot be equal to
    // CACHED_APP_MAX_ADJ unless processes are actively being assigned an oom_score_adj of
    // CACHED_APP_MAX_ADJ.
    static final int CACHED_APP_LMK_FIRST_ADJ = 950;

    // Number of levels we have available for different service connection group importance
    // levels.
    static final int CACHED_APP_IMPORTANCE_LEVELS = 5;

    // The B list of SERVICE_ADJ -- these are the old and decrepit
    // services that aren't as shiny and interesting as the ones in the A list.
    static final int SERVICE_B_ADJ = 800;

    // This is the process of the previous application that the user was in.
    // This process is kept above other things, because it is very common to
    // switch back to the previous app.  This is important both for recent
    // task switch (toggling between the two top recent apps) as well as normal
    // UI flow such as clicking on a URI in the e-mail app to view in the browser,
    // and then pressing back to return to e-mail.
    static final int PREVIOUS_APP_ADJ = 700;

    // This is a process holding the home application -- we want to try
    // avoiding killing it, even if it would normally be in the background,
    // because the user interacts with it so much.
    static final int HOME_APP_ADJ = 600;

    // This is a process holding an application service -- killing it will not
    // have much of an impact as far as the user is concerned.
    static final int SERVICE_ADJ = 500;

    // This is a process with a heavy-weight application.  It is in the
    // background, but we want to try to avoid killing it.  Value set in
    // system/rootdir/init.rc on startup.
    static final int HEAVY_WEIGHT_APP_ADJ = 400;

    // This is a process currently hosting a backup operation.  Killing it
    // is not entirely fatal but is generally a bad idea.
    static final int BACKUP_APP_ADJ = 300;

    // This is a process bound by the system that's more important than services but not so
    // perceptible that it affects the user immediately if killed.
    static final int PERCEPTIBLE_LOW_APP_ADJ = 250;

    // This is a process only hosting components that are perceptible to the
    // user, and we really want to avoid killing them, but they are not
    // immediately visible. An example is background music playback.
    static final int PERCEPTIBLE_APP_ADJ = 200;

    // This is a process only hosting activities that are visible to the
    // user, so we'd prefer they don't disappear.
    static final int VISIBLE_APP_ADJ = 100;
    static final int VISIBLE_APP_LAYER_MAX = PERCEPTIBLE_APP_ADJ - VISIBLE_APP_ADJ - 1;

    // This is a process that was recently TOP and moved to FGS. Continue to treat it almost
    // like a foreground app for a while.
    // @see TOP_TO_FGS_GRACE_PERIOD
    static final int PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ = 50;

    // This is the process running the current foreground app.  We'd really
    // rather not kill it!
    static final int FOREGROUND_APP_ADJ = 0;

    // This is a process that the system or a persistent process has bound to,
    // and indicated it is important.
    static final int PERSISTENT_SERVICE_ADJ = -700;

    // This is a system persistent process, such as telephony.  Definitely
    // don't want to kill it, but doing so is not completely fatal.
    static final int PERSISTENT_PROC_ADJ = -800;

    // The system process runs at the default adjustment.
    static final int SYSTEM_ADJ = -900;

    // Special code for native processes that are not being managed by the system (so
    // don't have an oom adj assigned by the system).
    static final int NATIVE_ADJ = -1000;

    // Memory pages are 4K.
    static final int PAGE_SIZE = 4 * 1024;

    // Activity manager's version of Process.THREAD_GROUP_BG_NONINTERACTIVE
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

    // We allow empty processes to stick around for at most 30 minutes.
    static final long MAX_EMPTY_TIME = 30 * 60 * 1000;

    // Threshold of number of cached+empty where we consider memory critical.
    static final int TRIM_CRITICAL_THRESHOLD = 3;

    // Threshold of number of cached+empty where we consider memory critical.
    static final int TRIM_LOW_THRESHOLD = 5;

    // Low Memory Killer Daemon command codes.
    // These must be kept in sync with the definitions in lmkd.c
    //
    // LMK_TARGET <minfree> <minkillprio> ... (up to 6 pairs)
    // LMK_PROCPRIO <pid> <uid> <prio>
    // LMK_PROCREMOVE <pid>
    // LMK_PROCPURGE
    // LMK_GETKILLCNT
    static final byte LMK_TARGET = 0;
    static final byte LMK_PROCPRIO = 1;
    static final byte LMK_PROCREMOVE = 2;
    static final byte LMK_PROCPURGE = 3;
    static final byte LMK_GETKILLCNT = 4;

    ActivityManagerService mService = null;

    // To kill process groups asynchronously
    static KillHandler sKillHandler = null;
    static ServiceThread sKillThread = null;

    // These are the various interesting memory levels that we will give to
    // the OOM killer.  Note that the OOM killer only supports 6 slots, so we
    // can't give it a different value for every possible kind of process.
    private final int[] mOomAdj = new int[] {
            FOREGROUND_APP_ADJ, VISIBLE_APP_ADJ, PERCEPTIBLE_APP_ADJ,
            BACKUP_APP_ADJ, CACHED_APP_MIN_ADJ, CACHED_APP_LMK_FIRST_ADJ
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

    private static Object sLmkdSocketLock = new Object();

    @GuardedBy("sLmkdSocketLock")
    private static LocalSocket sLmkdSocket;

    @GuardedBy("sLmkdSocketLock")
    private static OutputStream sLmkdOutputStream;

    @GuardedBy("sLmkdSocketLock")
    private static InputStream sLmkdInputStream;

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
    @GuardedBy("mService")
    @VisibleForTesting
    long mProcStateSeqCounter = 0;

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
     * Current sequence id for process LRU updating.
     */
    int mLruSeq = 0;

    ActiveUids mActiveUids;

    /**
     * The currently running isolated processes.
     */
    final SparseArray<ProcessRecord> mIsolatedProcesses = new SparseArray<>();

    /**
     * The currently running application zygotes.
     */
    final ProcessMap<AppZygote> mAppZygotes = new ProcessMap<AppZygote>();

    /**
     * The processes that are forked off an application zygote.
     */
    final ArrayMap<AppZygote, ArrayList<ProcessRecord>> mAppZygoteProcesses =
            new ArrayMap<AppZygote, ArrayList<ProcessRecord>>();

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
            // Strip out userId
            final int appId = UserHandle.getAppId(uid);
            mUidUsed.delete(appId);
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
        IsolatedUidRange getIsolatedUidRangeLocked(ApplicationInfo info) {
            return mAppRanges.get(info.processName, info.uid);
        }

        @GuardedBy("ProcessList.this.mService")
        IsolatedUidRange getOrCreateIsolatedUidRangeLocked(ApplicationInfo info) {
            IsolatedUidRange range = getIsolatedUidRangeLocked(info);
            if (range == null) {
                int uidRangeIndex = mAvailableUidRanges.nextSetBit(0);
                if (uidRangeIndex < 0) {
                    // No free range
                    return null;
                }
                mAvailableUidRanges.clear(uidRangeIndex);
                int actualUid = mFirstUid + uidRangeIndex * mNumUidsPerRange;
                range = new IsolatedUidRange(actualUid, actualUid + mNumUidsPerRange - 1);
                mAppRanges.put(info.processName, info.uid, range);
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
    IsolatedUidRange mGlobalIsolatedUids = new IsolatedUidRange(Process.FIRST_ISOLATED_UID,
            Process.LAST_ISOLATED_UID);

    /**
     * An allocator for isolated UID ranges for apps that use an application zygote.
     */
    @VisibleForTesting
    IsolatedUidRangeAllocator mAppIsolatedUidRangeAllocator =
            new IsolatedUidRangeAllocator(Process.FIRST_APP_ZYGOTE_ISOLATED_UID,
                    Process.LAST_APP_ZYGOTE_ISOLATED_UID, Process.NUM_UIDS_PER_APP_ZYGOTE);

    /**
     * Processes that are being forcibly torn down.
     */
    final ArrayList<ProcessRecord> mRemovedProcesses = new ArrayList<ProcessRecord>();

    /**
     * All of the applications we currently have running organized by name.
     * The keys are strings of the application package name (as
     * returned by the package manager), and the keys are ApplicationRecord
     * objects.
     */
    final MyProcessMap mProcessNames = new MyProcessMap();

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

                default:
                    super.handleMessage(msg);
            }
        }
    }

    ////////////////////  END FIELDS  ////////////////////

    ProcessList() {
        MemInfoReader minfo = new MemInfoReader();
        minfo.readMemInfo();
        mTotalMemMb = minfo.getTotalSize()/(1024*1024);
        updateOomLevels(0, 0, false);
    }

    void init(ActivityManagerService service, ActiveUids activeUids) {
        mService = service;
        mActiveUids = activeUids;

        if (sKillHandler == null) {
            sKillThread = new ServiceThread(TAG + ":kill",
                    THREAD_PRIORITY_BACKGROUND, true /* allowIo */);
            sKillThread.start();
            sKillHandler = new KillHandler(sKillThread.getLooper());
        }
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
        } else if (setAdj >= ProcessList.PERCEPTIBLE_APP_ADJ) {
            return buildOomTag("prcp  ", "prcp", null, setAdj,
                    ProcessList.PERCEPTIBLE_APP_ADJ, compact);
        } else if (setAdj >= ProcessList.VISIBLE_APP_ADJ) {
            return buildOomTag("vis", "vis", "   ", setAdj,
                    ProcessList.VISIBLE_APP_ADJ, compact);
        } else if (setAdj >= ProcessList.FOREGROUND_APP_ADJ) {
            return buildOomTag("fore  ", "fore", null, setAdj,
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
        String procState;
        switch (curProcState) {
            case ActivityManager.PROCESS_STATE_PERSISTENT:
                procState = "PER ";
                break;
            case ActivityManager.PROCESS_STATE_PERSISTENT_UI:
                procState = "PERU";
                break;
            case ActivityManager.PROCESS_STATE_TOP:
                procState = "TOP ";
                break;
            case ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE_LOCATION:
                procState = "FGSL";
                break;
            case ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE:
                procState = "FGS ";
                break;
            case ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE:
                procState = "BFGS";
                break;
            case ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND:
                procState = "IMPF";
                break;
            case ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND:
                procState = "IMPB";
                break;
            case ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND:
                procState = "TRNB";
                break;
            case ActivityManager.PROCESS_STATE_BACKUP:
                procState = "BKUP";
                break;
            case ActivityManager.PROCESS_STATE_SERVICE:
                procState = "SVC ";
                break;
            case ActivityManager.PROCESS_STATE_RECEIVER:
                procState = "RCVR";
                break;
            case ActivityManager.PROCESS_STATE_TOP_SLEEPING:
                procState = "TPSL";
                break;
            case ActivityManager.PROCESS_STATE_HEAVY_WEIGHT:
                procState = "HVY ";
                break;
            case ActivityManager.PROCESS_STATE_HOME:
                procState = "HOME";
                break;
            case ActivityManager.PROCESS_STATE_LAST_ACTIVITY:
                procState = "LAST";
                break;
            case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY:
                procState = "CAC ";
                break;
            case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
                procState = "CACC";
                break;
            case ActivityManager.PROCESS_STATE_CACHED_RECENT:
                procState = "CRE ";
                break;
            case ActivityManager.PROCESS_STATE_CACHED_EMPTY:
                procState = "CEM ";
                break;
            case ActivityManager.PROCESS_STATE_NONEXISTENT:
                procState = "NONE";
                break;
            default:
                procState = "??";
                break;
        }
        return procState;
    }

    public static int makeProcStateProtoEnum(int curProcState) {
        switch (curProcState) {
            case ActivityManager.PROCESS_STATE_PERSISTENT:
                return AppProtoEnums.PROCESS_STATE_PERSISTENT;
            case ActivityManager.PROCESS_STATE_PERSISTENT_UI:
                return AppProtoEnums.PROCESS_STATE_PERSISTENT_UI;
            case ActivityManager.PROCESS_STATE_TOP:
                return AppProtoEnums.PROCESS_STATE_TOP;
            case ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE_LOCATION:
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
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE_LOCATION
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE
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

    public static void commitNextPssTime(ProcStateMemTracker tracker) {
        if (tracker.mPendingMemState >= 0) {
            tracker.mHighestMem[tracker.mPendingMemState] = tracker.mPendingHighestMemState;
            tracker.mScalingFactor[tracker.mPendingMemState] = tracker.mPendingScalingFactor;
            tracker.mTotalHighestMem = tracker.mPendingHighestMemState;
            tracker.mPendingMemState = -1;
        }
    }

    public static void abortNextPssTime(ProcStateMemTracker tracker) {
        tracker.mPendingMemState = -1;
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
     * @param amt Adjustment value -- lmkd allows -16 to +15.
     *
     * {@hide}
     */
    public static final void setOomAdj(int pid, int uid, int amt) {
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
        if (writeLmkd(buf, repl)) {
            int i = repl.getInt();
            if (i != LMK_GETKILLCNT) {
                Slog.e("ActivityManager", "Failed to get kill count, code mismatch");
                return null;
            }
            return new Integer(repl.getInt());
        }
        return null;
    }

    @GuardedBy("sLmkdSocketLock")
    private static boolean openLmkdSocketLS() {
        try {
            sLmkdSocket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET);
            sLmkdSocket.connect(
                new LocalSocketAddress("lmkd",
                        LocalSocketAddress.Namespace.RESERVED));
            sLmkdOutputStream = sLmkdSocket.getOutputStream();
            sLmkdInputStream = sLmkdSocket.getInputStream();
        } catch (IOException ex) {
            Slog.w(TAG, "lowmemorykiller daemon socket open failed");
            sLmkdSocket = null;
            return false;
        }

        return true;
    }

    // Never call directly, use writeLmkd() instead
    @GuardedBy("sLmkdSocketLock")
    private static boolean writeLmkdCommandLS(ByteBuffer buf) {
        try {
            sLmkdOutputStream.write(buf.array(), 0, buf.position());
        } catch (IOException ex) {
            Slog.w(TAG, "Error writing to lowmemorykiller socket");
            IoUtils.closeQuietly(sLmkdSocket);
            sLmkdSocket = null;
            return false;
        }
        return true;
    }

    // Never call directly, use writeLmkd() instead
    @GuardedBy("sLmkdSocketLock")
    private static boolean readLmkdReplyLS(ByteBuffer buf) {
        int len;
        try {
            len = sLmkdInputStream.read(buf.array(), 0, buf.array().length);
            if (len == buf.array().length) {
                return true;
            }
        } catch (IOException ex) {
            Slog.w(TAG, "Error reading from lowmemorykiller socket");
        }

        IoUtils.closeQuietly(sLmkdSocket);
        sLmkdSocket = null;
        return false;
    }

    private static boolean writeLmkd(ByteBuffer buf, ByteBuffer repl) {
        synchronized (sLmkdSocketLock) {
            for (int i = 0; i < 3; i++) {
                if (sLmkdSocket == null) {
                    if (openLmkdSocketLS() == false) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                        }
                        continue;
                    }

                    // Purge any previously registered pids
                    ByteBuffer purge_buf = ByteBuffer.allocate(4);
                    purge_buf.putInt(LMK_PROCPURGE);
                    if (writeLmkdCommandLS(purge_buf) == false) {
                        // Write failed, skip the rest and retry
                        continue;
                    }
                }
                if (writeLmkdCommandLS(buf) && (repl == null || readLmkdReplyLS(repl))) {
                    return true;
                }
            }
        }
        return false;
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

    final ProcessRecord getProcessRecordLocked(String processName, int uid, boolean
            keepIfLarge) {
        if (uid == SYSTEM_UID) {
            // The system gets to run in any process.  If there are multiple
            // processes with the same uid, just pick the first (this
            // should never happen).
            SparseArray<ProcessRecord> procs = mProcessNames.getMap().get(processName);
            if (procs == null) return null;
            final int procCount = procs.size();
            for (int i = 0; i < procCount; i++) {
                final int procUid = procs.keyAt(i);
                if (UserHandle.isApp(procUid) || !UserHandle.isSameUser(procUid, uid)) {
                    // Don't use an app process or different user process for system component.
                    continue;
                }
                return procs.valueAt(i);
            }
        }
        ProcessRecord proc = mProcessNames.get(processName, uid);
        if (false && proc != null && !keepIfLarge
                && proc.setProcState >= ActivityManager.PROCESS_STATE_CACHED_EMPTY
                && proc.lastCachedPss >= 4000) {
            // Turn this condition on to cause killing to happen regularly, for testing.
            if (proc.baseProcessTracker != null) {
                proc.baseProcessTracker.reportCachedKill(proc.pkgList.mPkgList, proc.lastCachedPss);
                for (int ipkg = proc.pkgList.size() - 1; ipkg >= 0; ipkg--) {
                    ProcessStats.ProcessStateHolder holder = proc.pkgList.valueAt(ipkg);
                    StatsLog.write(StatsLog.CACHED_KILL_REPORTED,
                            proc.info.uid,
                            holder.state.getName(),
                            holder.state.getPackage(),
                            proc.lastCachedPss, holder.appVersion);
                }
            }
            proc.kill(Long.toString(proc.lastCachedPss) + "k from cached", true);
        } else if (proc != null && !keepIfLarge
                && mService.mLastMemoryLevel > ProcessStats.ADJ_MEM_FACTOR_NORMAL
                && proc.setProcState >= ActivityManager.PROCESS_STATE_CACHED_EMPTY) {
            if (DEBUG_PSS) Slog.d(TAG_PSS, "May not keep " + proc + ": pss=" + proc
                    .lastCachedPss);
            if (proc.lastCachedPss >= getCachedRestoreThresholdKb()) {
                if (proc.baseProcessTracker != null) {
                    proc.baseProcessTracker.reportCachedKill(proc.pkgList.mPkgList,
                            proc.lastCachedPss);
                    for (int ipkg = proc.pkgList.size() - 1; ipkg >= 0; ipkg--) {
                        ProcessStats.ProcessStateHolder holder = proc.pkgList.valueAt(ipkg);
                        StatsLog.write(StatsLog.CACHED_KILL_REPORTED,
                                proc.info.uid,
                                holder.state.getName(),
                                holder.state.getPackage(),
                                proc.lastCachedPss, holder.appVersion);
                    }
                }
                proc.kill(Long.toString(proc.lastCachedPss) + "k from cached", true);
            }
        }
        return proc;
    }

    void getMemoryInfo(ActivityManager.MemoryInfo outInfo) {
        final long homeAppMem = getMemLevel(HOME_APP_ADJ);
        final long cachedAppMem = getMemLevel(CACHED_APP_MIN_ADJ);
        outInfo.availMem = getFreeMemory();
        outInfo.totalMem = getTotalMemory();
        outInfo.threshold = homeAppMem;
        outInfo.lowMemory = outInfo.availMem < (homeAppMem + ((cachedAppMem-homeAppMem)/2));
        outInfo.hiddenAppThreshold = cachedAppMem;
        outInfo.secondaryServerThreshold = getMemLevel(SERVICE_ADJ);
        outInfo.visibleAppThreshold = getMemLevel(VISIBLE_APP_ADJ);
        outInfo.foregroundAppThreshold = getMemLevel(FOREGROUND_APP_ADJ);
    }

    ProcessRecord findAppProcessLocked(IBinder app, String reason) {
        final int NP = mProcessNames.getMap().size();
        for (int ip = 0; ip < NP; ip++) {
            SparseArray<ProcessRecord> apps = mProcessNames.getMap().valueAt(ip);
            final int NA = apps.size();
            for (int ia = 0; ia < NA; ia++) {
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

    private void checkSlow(long startTime, String where) {
        long now = SystemClock.uptimeMillis();
        if ((now - startTime) > 50) {
            // If we are taking more than 50ms, log about it.
            Slog.w(TAG, "Slow operation: " + (now - startTime) + "ms so far, now at " + where);
        }
    }

    /**
     * @return {@code true} if process start is successful, false otherwise.
     * @param app
     * @param hostingType
     * @param hostingNameStr
     * @param disableHiddenApiChecks
     * @param abiOverride
     */
    @GuardedBy("mService")
    boolean startProcessLocked(ProcessRecord app, String hostingType,
            String hostingNameStr, boolean disableHiddenApiChecks, boolean mountExtStorageFull,
            String abiOverride) {
        if (app.pendingStart) {
            return true;
        }
        long startTime = SystemClock.elapsedRealtime();
        if (app.pid > 0 && app.pid != ActivityManagerService.MY_PID) {
            checkSlow(startTime, "startProcess: removing from pids map");
            mService.mPidsSelfLocked.remove(app.pid);
            mService.mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);
            checkSlow(startTime, "startProcess: done removing from pids map");
            app.setPid(0);
        }

        if (DEBUG_PROCESSES && mService.mProcessesOnHold.contains(app)) Slog.v(
                TAG_PROCESSES,
                "startProcessLocked removing on hold: " + app);
        mService.mProcessesOnHold.remove(app);

        checkSlow(startTime, "startProcess: starting to update cpu stats");
        mService.updateCpuStats();
        checkSlow(startTime, "startProcess: done updating cpu stats");

        try {
            try {
                final int userId = UserHandle.getUserId(app.uid);
                AppGlobals.getPackageManager().checkPackageStartable(app.info.packageName, userId);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }

            int uid = app.uid;
            int[] gids = null;
            int mountExternal = Zygote.MOUNT_EXTERNAL_NONE;
            if (!app.isolated) {
                int[] permGids = null;
                try {
                    checkSlow(startTime, "startProcess: getting gids from package manager");
                    final IPackageManager pm = AppGlobals.getPackageManager();
                    permGids = pm.getPackageGids(app.info.packageName,
                            MATCH_DIRECT_BOOT_AUTO, app.userId);
                    if (StorageManager.hasIsolatedStorage() && mountExtStorageFull) {
                        mountExternal = Zygote.MOUNT_EXTERNAL_FULL;
                    } else {
                        StorageManagerInternal storageManagerInternal = LocalServices.getService(
                                StorageManagerInternal.class);
                        mountExternal = storageManagerInternal.getExternalStorageMountMode(uid,
                                app.info.packageName);
                    }
                } catch (RemoteException e) {
                    throw e.rethrowAsRuntimeException();
                }

                /*
                 * Add shared application and profile GIDs so applications can share some
                 * resources like shared libraries and access user-wide resources
                 */
                if (ArrayUtils.isEmpty(permGids)) {
                    gids = new int[3];
                } else {
                    gids = new int[permGids.length + 3];
                    System.arraycopy(permGids, 0, gids, 3, permGids.length);
                }
                gids[0] = UserHandle.getSharedAppGid(UserHandle.getAppId(uid));
                gids[1] = UserHandle.getCacheAppGid(UserHandle.getAppId(uid));
                gids[2] = UserHandle.getUserGid(UserHandle.getUserId(uid));

                // Replace any invalid GIDs
                if (gids[0] == UserHandle.ERR_GID) gids[0] = gids[2];
                if (gids[1] == UserHandle.ERR_GID) gids[1] = gids[2];
            }
            app.mountMode = mountExternal;
            checkSlow(startTime, "startProcess: building args");
            if (mService.mAtmInternal.isFactoryTestProcess(app.getWindowProcessController())) {
                uid = 0;
            }
            int runtimeFlags = 0;
            if ((app.info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                runtimeFlags |= Zygote.DEBUG_ENABLE_JDWP;
                runtimeFlags |= Zygote.DEBUG_JAVA_DEBUGGABLE;
                // Also turn on CheckJNI for debuggable apps. It's quite
                // awkward to turn on otherwise.
                runtimeFlags |= Zygote.DEBUG_ENABLE_CHECKJNI;
            }
            // Run the app in safe mode if its manifest requests so or the
            // system is booted in safe mode.
            if ((app.info.flags & ApplicationInfo.FLAG_VM_SAFE_MODE) != 0 ||
                    mService.mSafeMode == true) {
                runtimeFlags |= Zygote.DEBUG_ENABLE_SAFEMODE;
            }
            if ((app.info.privateFlags & ApplicationInfo.PRIVATE_FLAG_PROFILEABLE_BY_SHELL) != 0) {
                runtimeFlags |= Zygote.PROFILE_FROM_SHELL;
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
            if (mService.mNativeDebuggingApp != null
                    && mService.mNativeDebuggingApp.equals(app.processName)) {
                // Enable all debug flags required by the native debugger.
                runtimeFlags |= Zygote.DEBUG_ALWAYS_JIT;          // Don't interpret anything
                runtimeFlags |= Zygote.DEBUG_GENERATE_DEBUG_INFO; // Generate debug info
                runtimeFlags |= Zygote.DEBUG_NATIVE_DEBUGGABLE;   // Disbale optimizations
                mService.mNativeDebuggingApp = null;
            }

            if (app.info.isEmbeddedDexUsed()
                    || (app.info.isPrivilegedApp()
                        && DexManager.isPackageSelectedToRunOob(app.pkgList.mPkgList.keySet()))) {
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
            }

            String invokeWith = null;
            if ((app.info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
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
                instructionSet = VMRuntime.getInstructionSet(app.info.primaryCpuAbi);
            }

            app.gids = gids;
            app.setRequiredAbi(requiredAbi);
            app.instructionSet = instructionSet;

            // the per-user SELinux context must be set
            if (TextUtils.isEmpty(app.info.seInfoUser)) {
                Slog.wtf(ActivityManagerService.TAG, "SELinux tag not defined",
                        new IllegalStateException("SELinux tag not defined for "
                                + app.info.packageName + " (uid " + app.uid + ")"));
            }
            final String seInfo = app.info.seInfo
                    + (TextUtils.isEmpty(app.info.seInfoUser) ? "" : app.info.seInfoUser);
            // Start the process.  It will either succeed and return a result containing
            // the PID of the new process, or else throw a RuntimeException.
            final String entryPoint = "android.app.ActivityThread";

            return startProcessLocked(hostingType, hostingNameStr, entryPoint, app, uid, gids,
                    runtimeFlags, mountExternal, seInfo, requiredAbi, instructionSet, invokeWith,
                    startTime);
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

    @GuardedBy("mService")
    boolean startProcessLocked(String hostingType, String hostingNameStr,
            String entryPoint,
            ProcessRecord app, int uid, int[] gids, int runtimeFlags, int mountExternal,
            String seInfo, String requiredAbi, String instructionSet, String invokeWith,
            long startTime) {
        app.pendingStart = true;
        app.killedByAm = false;
        app.removed = false;
        app.killed = false;
        final long startSeq = app.startSeq = ++mProcStartSeqCounter;
        app.setStartParams(uid, hostingType, hostingNameStr, seInfo, startTime);
        if (mService.mConstants.FLAG_PROCESS_START_ASYNC) {
            if (DEBUG_PROCESSES) Slog.i(TAG_PROCESSES,
                    "Posting procStart msg for " + app.toShortString());
            mService.mProcStartHandler.post(() -> {
                try {
                    synchronized (mService) {
                        final String reason = isProcStartValidLocked(app, startSeq);
                        if (reason != null) {
                            Slog.w(TAG_PROCESSES, app + " not valid anymore,"
                                    + " don't start process, " + reason);
                            app.pendingStart = false;
                            return;
                        }
                        app.setUsingWrapper(invokeWith != null
                                || SystemProperties.get("wrap." + app.processName) != null);
                        mPendingStarts.put(startSeq, app);
                    }
                    final Process.ProcessStartResult startResult = startProcess(app.hostingType,
                            entryPoint, app, app.startUid, gids, runtimeFlags, mountExternal,
                            app.seInfo, requiredAbi, instructionSet, invokeWith, app.startTime);
                    synchronized (mService) {
                        handleProcessStartedLocked(app, startResult, startSeq);
                    }
                } catch (RuntimeException e) {
                    synchronized (mService) {
                        Slog.e(ActivityManagerService.TAG, "Failure starting process "
                                + app.processName, e);
                        mPendingStarts.remove(startSeq);
                        app.pendingStart = false;
                        mService.forceStopPackageLocked(app.info.packageName,
                                UserHandle.getAppId(app.uid),
                                false, false, true, false, false, app.userId, "start failure");
                    }
                }
            });
            return true;
        } else {
            try {
                final Process.ProcessStartResult startResult = startProcess(hostingType,
                        entryPoint, app,
                        uid, gids, runtimeFlags, mountExternal, seInfo, requiredAbi, instructionSet,
                        invokeWith, startTime);
                handleProcessStartedLocked(app, startResult.pid, startResult.usingWrapper,
                        startSeq, false);
            } catch (RuntimeException e) {
                Slog.e(ActivityManagerService.TAG, "Failure starting process "
                        + app.processName, e);
                app.pendingStart = false;
                mService.forceStopPackageLocked(app.info.packageName, UserHandle.getAppId(app.uid),
                        false, false, true, false, false, app.userId, "start failure");
            }
            return app.pid > 0;
        }
    }

    @GuardedBy("mService")
    public void killAppZygoteIfNeededLocked(AppZygote appZygote) {
        final ApplicationInfo appInfo = appZygote.getAppInfo();
        ArrayList<ProcessRecord> zygoteProcesses = mAppZygoteProcesses.get(appZygote);
        if (zygoteProcesses != null && zygoteProcesses.size() == 0) {
            // Only remove if no longer in use now
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
                mAppIsolatedUidRangeAllocator.getIsolatedUidRangeLocked(app.info);
        if (appUidRange != null) {
            appUidRange.freeIsolatedUidLocked(app.uid);
        }

        final AppZygote appZygote = mAppZygotes.get(app.info.processName, app.info.uid);
        if (appZygote != null) {
            ArrayList<ProcessRecord> zygoteProcesses = mAppZygoteProcesses.get(appZygote);
            zygoteProcesses.remove(app);
            if (zygoteProcesses.size() == 0) {
                mService.mHandler.removeMessages(KILL_APP_ZYGOTE_MSG);
                Message msg = mService.mHandler.obtainMessage(KILL_APP_ZYGOTE_MSG);
                msg.obj = appZygote;
                mService.mHandler.sendMessageDelayed(msg, KILL_APP_ZYGOTE_DELAY_MS);
            }
        }
    }

    private AppZygote createAppZygoteForProcessIfNeeded(final ProcessRecord app) {
        synchronized (mService) {
            AppZygote appZygote = mAppZygotes.get(app.info.processName, app.info.uid);
            final ArrayList<ProcessRecord> zygoteProcessList;
            if (appZygote == null) {
                final IsolatedUidRange uidRange =
                        mAppIsolatedUidRangeAllocator.getIsolatedUidRangeLocked(app.info);
                final int userId = UserHandle.getUserId(app.info.uid);
                // Create the app-zygote and provide it with the UID-range it's allowed
                // to setresuid/setresgid to.
                final int firstUid = UserHandle.getUid(userId, uidRange.mFirstUid);
                final int lastUid = UserHandle.getUid(userId, uidRange.mLastUid);
                appZygote = new AppZygote(app.info, app.info.uid, firstUid, lastUid);
                mAppZygotes.put(app.info.processName, app.info.uid, appZygote);
                zygoteProcessList = new ArrayList<ProcessRecord>();
                mAppZygoteProcesses.put(appZygote, zygoteProcessList);
            } else {
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

    private Process.ProcessStartResult startProcess(String hostingType, String entryPoint,
            ProcessRecord app, int uid, int[] gids, int runtimeFlags, int mountExternal,
            String seInfo, String requiredAbi, String instructionSet, String invokeWith,
            long startTime) {
        try {
            final String[] packageNames = mService.mContext.getPackageManager()
                    .getPackagesForUid(uid);
            final StorageManagerInternal storageManagerInternal =
                    LocalServices.getService(StorageManagerInternal.class);
            final String[] visibleVolIds = storageManagerInternal
                    .getVisibleVolumesForUser(UserHandle.getUserId(uid));
            final String sandboxId = storageManagerInternal.getSandboxId(app.info.packageName);
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "Start proc: " +
                    app.processName);
            checkSlow(startTime, "startProcess: asking zygote to start proc");
            final Process.ProcessStartResult startResult;
            if (hostingType.equals("webview_service")) {
                startResult = startWebView(entryPoint,
                        app.processName, uid, uid, gids, runtimeFlags, mountExternal,
                        app.info.targetSdkVersion, seInfo, requiredAbi, instructionSet,
                        app.info.dataDir, null, app.info.packageName,
                        packageNames, visibleVolIds, sandboxId,
                        new String[] {PROC_START_SEQ_IDENT + app.startSeq});
            } else if (hostingType.equals("app_zygote")) {
                final AppZygote appZygote = createAppZygoteForProcessIfNeeded(app);

                startResult = appZygote.getProcess().start(entryPoint,
                        app.processName, uid, uid, gids, runtimeFlags, mountExternal,
                        app.info.targetSdkVersion, seInfo, requiredAbi, instructionSet,
                        app.info.dataDir, null, app.info.packageName,
                        packageNames, visibleVolIds, sandboxId, /*useBlastulaPool=*/ false,
                        new String[] {PROC_START_SEQ_IDENT + app.startSeq});
            } else {
                startResult = Process.start(entryPoint,
                        app.processName, uid, uid, gids, runtimeFlags, mountExternal,
                        app.info.targetSdkVersion, seInfo, requiredAbi, instructionSet,
                        app.info.dataDir, invokeWith, app.info.packageName,
                        packageNames, visibleVolIds, sandboxId,
                        new String[] {PROC_START_SEQ_IDENT + app.startSeq});
            }
            checkSlow(startTime, "startProcess: returned from zygote!");
            return startResult;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    @GuardedBy("mService")
    final void startProcessLocked(ProcessRecord app,
            String hostingType, String hostingNameStr) {
        startProcessLocked(app, hostingType, hostingNameStr, null /* abiOverride */);
    }

    @GuardedBy("mService")
    final boolean startProcessLocked(ProcessRecord app,
            String hostingType, String hostingNameStr, String abiOverride) {
        return startProcessLocked(app, hostingType, hostingNameStr,
                false /* disableHiddenApiChecks */, false /* mountExtStorageFull */, abiOverride);
    }

    @GuardedBy("mService")
    final ProcessRecord startProcessLocked(String processName, ApplicationInfo info,
            boolean knownToBeDead, int intentFlags, String hostingType, ComponentName hostingName,
            boolean allowWhileBooting, boolean isolated, int isolatedUid, boolean keepIfLarge,
            String abiOverride, String entryPoint, String[] entryPointArgs, Runnable crashHandler) {
        long startTime = SystemClock.elapsedRealtime();
        ProcessRecord app;
        if (!isolated) {
            app = getProcessRecordLocked(processName, info.uid, keepIfLarge);
            checkSlow(startTime, "startProcess: after getProcessRecord");

            if ((intentFlags & Intent.FLAG_FROM_BACKGROUND) != 0) {
                // If we are in the background, then check to see if this process
                // is bad.  If so, we will just silently fail.
                if (mService.mAppErrors.isBadProcessLocked(info)) {
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
                mService.mAppErrors.resetProcessCrashTimeLocked(info);
                if (mService.mAppErrors.isBadProcessLocked(info)) {
                    EventLog.writeEvent(EventLogTags.AM_PROC_GOOD,
                            UserHandle.getUserId(info.uid), info.uid,
                            info.processName);
                    mService.mAppErrors.clearBadProcessLocked(info);
                    if (app != null) {
                        app.bad = false;
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
                + " thread=" + (app != null ? app.thread : null)
                + " pid=" + (app != null ? app.pid : -1));
        if (app != null && app.pid > 0) {
            if ((!knownToBeDead && !app.killed) || app.thread == null) {
                // We already have the app running, or are waiting for it to
                // come up (we have a pid but not yet its thread), so keep it.
                if (DEBUG_PROCESSES) Slog.v(TAG_PROCESSES, "App already running: " + app);
                // If this is a new package in the process, add the package to the list
                app.addPackage(info.packageName, info.versionCode, mService.mProcessStats);
                checkSlow(startTime, "startProcess: done, added package to proc");
                return app;
            }

            // An application record is attached to a previous process,
            // clean it up now.
            if (DEBUG_PROCESSES) Slog.v(TAG_PROCESSES, "App died: " + app);
            checkSlow(startTime, "startProcess: bad proc running, killing");
            ProcessList.killProcessGroup(app.uid, app.pid);
            mService.handleAppDiedLocked(app, true, true);
            checkSlow(startTime, "startProcess: done killing old proc");
        }

        String hostingNameStr = hostingName != null
                ? hostingName.flattenToShortString() : null;

        if (app == null) {
            final boolean fromAppZygote = "app_zygote".equals(hostingType);
            checkSlow(startTime, "startProcess: creating new process record");
            app = newProcessRecordLocked(info, processName, isolated, isolatedUid, fromAppZygote);
            if (app == null) {
                Slog.w(TAG, "Failed making new process record for "
                        + processName + "/" + info.uid + " isolated=" + isolated);
                return null;
            }
            app.crashHandler = crashHandler;
            app.isolatedEntryPoint = entryPoint;
            app.isolatedEntryPointArgs = entryPointArgs;
            checkSlow(startTime, "startProcess: done creating new process record");
        } else {
            // If this is a new package in the process, add the package to the list
            app.addPackage(info.packageName, info.versionCode, mService.mProcessStats);
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
        final boolean success = startProcessLocked(app, hostingType, hostingNameStr,
                abiOverride);
        checkSlow(startTime, "startProcess: done starting proc!");
        return success ? app : null;
    }

    @GuardedBy("mService")
    private String isProcStartValidLocked(ProcessRecord app, long expectedStartSeq) {
        StringBuilder sb = null;
        if (app.killedByAm) {
            if (sb == null) sb = new StringBuilder();
            sb.append("killedByAm=true;");
        }
        if (mProcessNames.get(app.processName, app.uid) != app) {
            if (sb == null) sb = new StringBuilder();
            sb.append("No entry in mProcessNames;");
        }
        if (!app.pendingStart) {
            if (sb == null) sb = new StringBuilder();
            sb.append("pendingStart=false;");
        }
        if (app.startSeq > expectedStartSeq) {
            if (sb == null) sb = new StringBuilder();
            sb.append("seq=" + app.startSeq + ",expected=" + expectedStartSeq + ";");
        }
        return sb == null ? null : sb.toString();
    }

    @GuardedBy("mService")
    private boolean handleProcessStartedLocked(ProcessRecord pending,
            Process.ProcessStartResult startResult, long expectedStartSeq) {
        // Indicates that this process start has been taken care of.
        if (mPendingStarts.get(expectedStartSeq) == null) {
            if (pending.pid == startResult.pid) {
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
            app.pendingStart = false;
            killProcessQuiet(pid);
            Process.killProcessGroup(app.uid, app.pid);
            return false;
        }
        mService.mBatteryStatsService.noteProcessStart(app.processName, app.info.uid);
        checkSlow(app.startTime, "startProcess: done updating battery stats");

        EventLog.writeEvent(EventLogTags.AM_PROC_START,
                UserHandle.getUserId(app.startUid), pid, app.startUid,
                app.processName, app.hostingType,
                app.hostingNameStr != null ? app.hostingNameStr : "");

        try {
            AppGlobals.getPackageManager().logAppProcessStartIfNeeded(app.processName, app.uid,
                    app.seInfo, app.info.sourceDir, pid);
        } catch (RemoteException ex) {
            // Ignore
        }

        if (app.isPersistent()) {
            Watchdog.getInstance().processStarted(app.processName, pid);
        }

        checkSlow(app.startTime, "startProcess: building log message");
        StringBuilder buf = mStringBuilder;
        buf.setLength(0);
        buf.append("Start proc ");
        buf.append(pid);
        buf.append(':');
        buf.append(app.processName);
        buf.append('/');
        UserHandle.formatUid(buf, app.startUid);
        if (app.isolatedEntryPoint != null) {
            buf.append(" [");
            buf.append(app.isolatedEntryPoint);
            buf.append("]");
        }
        buf.append(" for ");
        buf.append(app.hostingType);
        if (app.hostingNameStr != null) {
            buf.append(" ");
            buf.append(app.hostingNameStr);
        }
        mService.reportUidInfoMessageLocked(TAG, buf.toString(), app.startUid);
        app.setPid(pid);
        app.setUsingWrapper(usingWrapper);
        app.pendingStart = false;
        checkSlow(app.startTime, "startProcess: starting to update pids map");
        ProcessRecord oldApp;
        synchronized (mService.mPidsSelfLocked) {
            oldApp = mService.mPidsSelfLocked.get(pid);
        }
        // If there is already an app occupying that pid that hasn't been cleaned up
        if (oldApp != null && !app.isolated) {
            // Clean up anything relating to this pid first
            Slog.w(TAG, "Reusing pid " + pid
                    + " while app is still mapped to it");
            mService.cleanUpApplicationRecordLocked(oldApp, false, false, -1,
                    true /*replacingPid*/);
        }
        mService.mPidsSelfLocked.put(pid, app);
        synchronized (mService.mPidsSelfLocked) {
            if (!procAttached) {
                Message msg = mService.mHandler.obtainMessage(PROC_START_TIMEOUT_MSG);
                msg.obj = app;
                mService.mHandler.sendMessageDelayed(msg, usingWrapper
                        ? PROC_START_TIMEOUT_WITH_WRAPPER : PROC_START_TIMEOUT);
            }
        }
        checkSlow(app.startTime, "startProcess: done updating pids map");
        return true;
    }

    final void removeLruProcessLocked(ProcessRecord app) {
        int lrui = mLruProcesses.lastIndexOf(app);
        if (lrui >= 0) {
            if (!app.killed) {
                if (app.isPersistent()) {
                    Slog.w(TAG, "Removing persistent process that hasn't been killed: " + app);
                } else {
                    Slog.wtfStack(TAG, "Removing process that hasn't been killed: " + app);
                    if (app.pid > 0) {
                        killProcessQuiet(app.pid);
                        ProcessList.killProcessGroup(app.uid, app.pid);
                    } else {
                        app.pendingStart = false;
                    }
                }
            }
            if (lrui <= mLruProcessActivityStart) {
                mLruProcessActivityStart--;
            }
            if (lrui <= mLruProcessServiceStart) {
                mLruProcessServiceStart--;
            }
            mLruProcesses.remove(lrui);
        }
    }

    void killAllBackgroundProcessesLocked() {
        final ArrayList<ProcessRecord> procs = new ArrayList<>();
        final int NP = mProcessNames.getMap().size();
        for (int ip = 0; ip < NP; ip++) {
            final SparseArray<ProcessRecord> apps = mProcessNames.getMap().valueAt(ip);
            final int NA = apps.size();
            for (int ia = 0; ia < NA; ia++) {
                final ProcessRecord app = apps.valueAt(ia);
                if (app.isPersistent()) {
                    // We don't kill persistent processes.
                    continue;
                }
                if (app.removed || app.setAdj >= ProcessList.CACHED_APP_MIN_ADJ) {
                    procs.add(app);
                }
            }
        }
    }

    @GuardedBy("mService")
    boolean killPackageProcessesLocked(String packageName, int appId, int userId, int minOomAdj,
            String reason) {
        return killPackageProcessesLocked(packageName, appId, userId, minOomAdj,
                false /* callerWillRestart */, true /* allowRestart */, true /* doit */,
                false /* evenPersistent */, false /* setRemoved */, reason);
    }

    @GuardedBy("mService")
    final boolean killPackageProcessesLocked(String packageName, int appId,
            int userId, int minOomAdj, boolean callerWillRestart, boolean allowRestart,
            boolean doit, boolean evenPersistent, boolean setRemoved, String reason) {
        ArrayList<ProcessRecord> procs = new ArrayList<>();

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
                if (setRemoved) {
                    app.removed = true;
                }
                procs.add(app);
            }
        }

        int N = procs.size();
        for (int i=0; i<N; i++) {
            removeProcessLocked(procs.get(i), callerWillRestart, allowRestart, reason);
        }
        mService.updateOomAdjLocked();
        return N > 0;
    }
    @GuardedBy("mService")
    boolean removeProcessLocked(ProcessRecord app,
            boolean callerWillRestart, boolean allowRestart, String reason) {
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
        if ((app.pid > 0 && app.pid != ActivityManagerService.MY_PID) || (app.pid == 0 && app
                .pendingStart)) {
            int pid = app.pid;
            if (pid > 0) {
                mService.mPidsSelfLocked.remove(pid);
                mService.mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);
                mService.mBatteryStatsService.noteProcessFinish(app.processName, app.info.uid);
                if (app.isolated) {
                    mService.mBatteryStatsService.removeIsolatedUid(app.uid, app.info.uid);
                    mService.getPackageManagerInternalLocked().removeIsolatedUid(app.uid);
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
            app.kill(reason, true);
            mService.handleAppDiedLocked(app, willRestart, allowRestart);
            if (willRestart) {
                removeLruProcessLocked(app);
                mService.addAppLocked(app.info, null, false, null /* ABI override */);
            }
        } else {
            mRemovedProcesses.add(app);
        }

        return needRestart;
    }

    @GuardedBy("mService")
    final void addProcessNameLocked(ProcessRecord proc) {
        // We shouldn't already have a process under this name, but just in case we
        // need to clean up whatever may be there now.
        ProcessRecord old = removeProcessNameLocked(proc.processName, proc.uid);
        if (old == proc && proc.isPersistent()) {
            // We are re-adding a persistent process.  Whatevs!  Just leave it there.
            Slog.w(TAG, "Re-adding persistent process " + proc);
        } else if (old != null) {
            Slog.wtf(TAG, "Already have existing proc " + old + " when adding " + proc);
        }
        UidRecord uidRec = mActiveUids.get(proc.uid);
        if (uidRec == null) {
            uidRec = new UidRecord(proc.uid);
            // This is the first appearance of the uid, report it now!
            if (DEBUG_UID_OBSERVERS) Slog.i(TAG_UID_OBSERVERS,
                    "Creating new process uid: " + uidRec);
            if (Arrays.binarySearch(mService.mDeviceIdleTempWhitelist,
                    UserHandle.getAppId(proc.uid)) >= 0
                    || mService.mPendingTempWhitelist.indexOfKey(proc.uid) >= 0) {
                uidRec.setWhitelist = uidRec.curWhitelist = true;
            }
            uidRec.updateHasInternetPermission();
            mActiveUids.put(proc.uid, uidRec);
            EventLogTags.writeAmUidRunning(uidRec.uid);
            mService.noteUidProcessState(uidRec.uid, uidRec.getCurProcState());
        }
        proc.uidRecord = uidRec;

        // Reset render thread tid if it was already set, so new process can set it again.
        proc.renderThreadTid = 0;
        uidRec.numProcs++;
        mProcessNames.put(proc.processName, proc.uid, proc);
        if (proc.isolated) {
            mIsolatedProcesses.put(proc.uid, proc);
        }
    }

    @GuardedBy("mService")
    private IsolatedUidRange getOrCreateIsolatedUidRangeLocked(ApplicationInfo info,
            boolean fromAppZygote) {
        if (!fromAppZygote) {
            // Allocate an isolated UID from the global range
            return mGlobalIsolatedUids;
        } else {
            return mAppIsolatedUidRangeAllocator.getOrCreateIsolatedUidRangeLocked(info);
        }
    }

    @GuardedBy("mService")
    final ProcessRecord newProcessRecordLocked(ApplicationInfo info, String customProcess,
            boolean isolated, int isolatedUid, boolean fromAppZygote) {
        String proc = customProcess != null ? customProcess : info.processName;
        final int userId = UserHandle.getUserId(info.uid);
        int uid = info.uid;
        if (isolated) {
            if (isolatedUid == 0) {
                IsolatedUidRange uidRange = getOrCreateIsolatedUidRangeLocked(info, fromAppZygote);
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
            mService.getPackageManagerInternalLocked().addIsolatedUid(uid, info.uid);

            // Register the isolated UID with this application so BatteryStats knows to
            // attribute resource usage to the application.
            //
            // NOTE: This is done here before addProcessNameLocked, which will tell BatteryStats
            // about the process state of the isolated UID *before* it is registered with the
            // owning application.
            mService.mBatteryStatsService.addIsolatedUid(uid, info.uid);
            StatsLog.write(StatsLog.ISOLATED_UID_CHANGED, info.uid, uid,
                    StatsLog.ISOLATED_UID_CHANGED__EVENT__CREATED);
        }
        final ProcessRecord r = new ProcessRecord(mService, info, proc, uid);

        if (!mService.mBooted && !mService.mBooting
                && userId == UserHandle.USER_SYSTEM
                && (info.flags & PERSISTENT_MASK) == PERSISTENT_MASK) {
            // The system process is initialized to SCHED_GROUP_DEFAULT in init.rc.
            r.setCurrentSchedulingGroup(ProcessList.SCHED_GROUP_DEFAULT);
            r.setSchedGroup = ProcessList.SCHED_GROUP_DEFAULT;
            r.setPersistent(true);
            r.maxAdj = ProcessList.PERSISTENT_PROC_ADJ;
        }
        if (isolated && isolatedUid != 0) {
            // Special case for startIsolatedProcess (internal only) - assume the process
            // is required by the system server to prevent it being killed.
            r.maxAdj = ProcessList.PERSISTENT_SERVICE_ADJ;
        }
        addProcessNameLocked(r);
        return r;
    }

    @GuardedBy("mService")
    final ProcessRecord removeProcessNameLocked(final String name, final int uid) {
        return removeProcessNameLocked(name, uid, null);
    }

    @GuardedBy("mService")
    final ProcessRecord removeProcessNameLocked(final String name, final int uid,
            final ProcessRecord expecting) {
        ProcessRecord old = mProcessNames.get(name, uid);
        // Only actually remove when the currently recorded value matches the
        // record that we expected; if it doesn't match then we raced with a
        // newly created process and we don't want to destroy the new one.
        if ((expecting == null) || (old == expecting)) {
            mProcessNames.remove(name, uid);
        }
        if (old != null && old.uidRecord != null) {
            old.uidRecord.numProcs--;
            if (old.uidRecord.numProcs == 0) {
                // No more processes using this uid, tell clients it is gone.
                if (DEBUG_UID_OBSERVERS) Slog.i(TAG_UID_OBSERVERS,
                        "No more processes in " + old.uidRecord);
                mService.enqueueUidChangeLocked(old.uidRecord, -1, UidRecord.CHANGE_GONE);
                EventLogTags.writeAmUidStopped(uid);
                mActiveUids.remove(uid);
                mService.noteUidProcessState(uid, ActivityManager.PROCESS_STATE_NONEXISTENT);
            }
            old.uidRecord = null;
        }
        mIsolatedProcesses.remove(uid);
        mGlobalIsolatedUids.freeIsolatedUidLocked(uid);
        // Remove the (expected) ProcessRecord from the app zygote
        final ProcessRecord record = expecting != null ? expecting : old;
        if (record != null && record.appZygote) {
            removeProcessFromAppZygoteLocked(record);
        }

        return old;
    }

    /** Call setCoreSettings on all LRU processes, with the new settings. */
    @GuardedBy("mService")
    void updateCoreSettingsLocked(Bundle settings) {
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

    /**
     * Kill all background processes except for ones with targetSdk lower than minTargetSdk and
     * procstate lower than maxProcState.
     * @param minTargetSdk
     * @param maxProcState
     */
    @GuardedBy("mService")
    void killAllBackgroundProcessesExceptLocked(int minTargetSdk, int maxProcState) {
        final ArrayList<ProcessRecord> procs = new ArrayList<>();
        final int NP = mProcessNames.getMap().size();
        for (int ip = 0; ip < NP; ip++) {
            final SparseArray<ProcessRecord> apps = mProcessNames.getMap().valueAt(ip);
            final int NA = apps.size();
            for (int ia = 0; ia < NA; ia++) {
                final ProcessRecord app = apps.valueAt(ia);
                if (app.removed || ((minTargetSdk < 0 || app.info.targetSdkVersion < minTargetSdk)
                        && (maxProcState < 0 || app.setProcState > maxProcState))) {
                    procs.add(app);
                }
            }
        }

        final int N = procs.size();
        for (int i = 0; i < N; i++) {
            removeProcessLocked(procs.get(i), false, true, "kill all background except");
        }
    }

    /**
     * Call updateTimePrefs on all LRU processes
     * @param timePref The time pref to pass to each process
     */
    @GuardedBy("mService")
    void updateAllTimePrefsLocked(int timePref) {
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord r = mLruProcesses.get(i);
            if (r.thread != null) {
                try {
                    r.thread.updateTimePrefs(timePref);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to update preferences for: "
                            + r.info.processName);
                }
            }
        }
    }

    @GuardedBy("mService")
    void setAllHttpProxyLocked() {
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord r = mLruProcesses.get(i);
            // Don't dispatch to isolated processes as they can't access
            // ConnectivityManager and don't have network privileges anyway.
            if (r.thread != null && !r.isolated) {
                try {
                    r.thread.updateHttpProxy();
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to update http proxy for: " +
                            r.info.processName);
                }
            }
        }
    }

    @GuardedBy("mService")
    void clearAllDnsCacheLocked() {
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
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

    @GuardedBy("mService")
    void handleAllTrustStorageUpdateLocked() {
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord r = mLruProcesses.get(i);
            if (r.thread != null) {
                try {
                    r.thread.handleTrustStorageUpdate();
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to handle trust storage update for: " +
                            r.info.processName);
                }
            }
        }
    }

    @GuardedBy("mService")
    int updateLruProcessInternalLocked(ProcessRecord app, long now, int index,
            int lruSeq, String what, Object obj, ProcessRecord srcApp) {
        app.lastActivityTime = now;

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
        app.lruSeq = lruSeq;
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
    private void updateClientActivitiesOrdering(final ProcessRecord topApp, final int topI,
            final int bottomI, int endIndex) {
        if (topApp.hasActivitiesOrRecentTasks() || topApp.treatLikeActivity
                || !topApp.hasClientActivities()) {
            // If this is not a special process that has client activities, then there is
            // nothing to do.
            return;
        }

        final int uid = topApp.info.uid;
        if (topApp.connectionGroup > 0) {
            int endImportance = topApp.connectionImportance;
            for (int i = endIndex; i >= bottomI; i--) {
                final ProcessRecord subProc = mLruProcesses.get(i);
                if (subProc.info.uid == uid
                        && subProc.connectionGroup == topApp.connectionGroup) {
                    if (i == endIndex && subProc.connectionImportance >= endImportance) {
                        // This process is already in the group, and its importance
                        // is not as strong as the process before it, so keep it
                        // correctly positioned in the group.
                        if (DEBUG_LRU) Slog.d(TAG_LRU,
                                "Keeping in-place above " + subProc
                                        + " endImportance=" + endImportance
                                        + " group=" + subProc.connectionGroup
                                        + " importance=" + subProc.connectionImportance);
                        endIndex--;
                        endImportance = subProc.connectionImportance;
                    } else {
                        // We want to pull this up to be with the rest of the group,
                        // and order within the group by importance.
                        if (DEBUG_LRU) Slog.d(TAG_LRU,
                                "Pulling up " + subProc
                                        + " to position in group with importance="
                                        + subProc.connectionImportance);
                        boolean moved = false;
                        for (int pos = topI; pos > endIndex; pos--) {
                            final ProcessRecord posProc = mLruProcesses.get(pos);
                            if (subProc.connectionImportance
                                    <= posProc.connectionImportance) {
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
                            mLruProcesses.add(endIndex - 1, subProc);
                            if (DEBUG_LRU) Slog.d(TAG_LRU,
                                    "Moving " + subProc
                                            + " from position " + i + " to end of group @ "
                                            + endIndex);
                            endIndex--;
                            endImportance = subProc.connectionImportance;
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
                        if (subProc.hasActivitiesOrRecentTasks() || subProc.treatLikeActivity) {
                            if (DEBUG_LRU) Slog.d(TAG_LRU,
                                    "This is hosting an activity!");
                            if (hasActivity) {
                                // Already found an activity, done.
                                if (DEBUG_LRU) Slog.d(TAG_LRU,
                                        "Already found an activity, done");
                                break;
                            }
                            hasActivity = true;
                        } else if (subProc.hasClientActivities()) {
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
                                } else if (connGroup == 0 || connGroup != subProc.connectionGroup) {
                                    // Previously saw a different group or not from a group,
                                    // want to treat these as different things.
                                    if (DEBUG_LRU) Slog.d(TAG_LRU,
                                            "Already found a different group: connGroup="
                                            + connGroup + " group=" + subProc.connectionGroup);
                                    break;
                                }
                            } else {
                                if (DEBUG_LRU) Slog.d(TAG_LRU,
                                        "This is an activity client!  uid="
                                        + subProc.info.uid + " group=" + subProc.connectionGroup);
                                hasActivity = true;
                                connUid = subProc.info.uid;
                                connGroup = subProc.connectionGroup;
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
                    for (endIndex--; endIndex >= bottomI; endIndex--) {
                        final ProcessRecord nextEndProc = mLruProcesses.get(endIndex);
                        if (nextEndProc.info.uid != uid
                                || nextEndProc.connectionGroup != endProc.connectionGroup) {
                            if (DEBUG_LRU) Slog.d(TAG_LRU,
                                    "Found next group or app: " + nextEndProc + " @ "
                                            + endIndex + " group=" + nextEndProc.connectionGroup);
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

    final void updateLruProcessLocked(ProcessRecord app, boolean activityChange,
            ProcessRecord client) {
        final boolean hasActivity = app.hasActivitiesOrRecentTasks() || app.hasClientActivities()
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
            if (!app.hasActivitiesOrRecentTasks() && !app.treatLikeActivity
                    && mLruProcessActivityStart < (N - 1)) {
                // Process doesn't have activities, but has clients with
                // activities...  move it up, but below the app that is binding to it.
                if (DEBUG_LRU) Slog.d(TAG_LRU,
                        "Adding to second-top of LRU activity list: " + app
                        + " group=" + app.connectionGroup
                        + " importance=" + app.connectionImportance);
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
                updateClientActivitiesOrdering(app, pos, mLruProcessActivityStart, endIndex);
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
                updateClientActivitiesOrdering(app, mLruProcessServiceStart - 1, 0, index - 1);
            }
        }

        app.lruSeq = mLruSeq;

        // If the app is currently using a content provider or service,
        // bump those processes as well.
        for (int j = app.connections.size() - 1; j >= 0; j--) {
            ConnectionRecord cr = app.connections.valueAt(j);
            if (cr.binding != null && !cr.serviceDead && cr.binding.service != null
                    && cr.binding.service.app != null
                    && cr.binding.service.app.lruSeq != mLruSeq
                    && (cr.flags & Context.BIND_REDUCTION_FLAGS) == 0
                    && !cr.binding.service.app.isPersistent()) {
                if (cr.binding.service.app.hasClientActivities()) {
                    if (nextActivityIndex >= 0) {
                        nextActivityIndex = updateLruProcessInternalLocked(cr.binding.service.app,
                                now,
                                nextActivityIndex, mLruSeq,
                                "service connection", cr, app);
                    }
                } else {
                    nextIndex = updateLruProcessInternalLocked(cr.binding.service.app,
                            now,
                            nextIndex, mLruSeq,
                            "service connection", cr, app);
                }
            }
        }
        for (int j = app.conProviders.size() - 1; j >= 0; j--) {
            ContentProviderRecord cpr = app.conProviders.get(j).provider;
            if (cpr.proc != null && cpr.proc.lruSeq != mLruSeq && !cpr.proc.isPersistent()) {
                nextIndex = updateLruProcessInternalLocked(cpr.proc, now, nextIndex, mLruSeq,
                        "provider reference", cpr, app);
            }
        }
    }

    final ProcessRecord getLRURecordForAppLocked(IApplicationThread thread) {
        final IBinder threadBinder = thread.asBinder();
        // Find the application record.
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            final ProcessRecord rec = mLruProcesses.get(i);
            if (rec.thread != null && rec.thread.asBinder() == threadBinder) {
                return rec;
            }
        }
        return null;
    }

    boolean haveBackgroundProcessLocked() {
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            final ProcessRecord rec = mLruProcesses.get(i);
            if (rec.thread != null
                    && rec.setProcState >= PROCESS_STATE_CACHED_ACTIVITY) {
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

    @GuardedBy("mService")
    void fillInProcMemInfoLocked(ProcessRecord app,
            ActivityManager.RunningAppProcessInfo outInfo,
            int clientTargetSdk) {
        outInfo.pid = app.pid;
        outInfo.uid = app.info.uid;
        if (mService.mAtmInternal.isHeavyWeightProcess(app.getWindowProcessController())) {
            outInfo.flags |= ActivityManager.RunningAppProcessInfo.FLAG_CANT_SAVE_STATE;
        }
        if (app.isPersistent()) {
            outInfo.flags |= ActivityManager.RunningAppProcessInfo.FLAG_PERSISTENT;
        }
        if (app.hasActivities()) {
            outInfo.flags |= ActivityManager.RunningAppProcessInfo.FLAG_HAS_ACTIVITIES;
        }
        outInfo.lastTrimLevel = app.trimMemoryLevel;
        int adj = app.curAdj;
        int procState = app.getCurProcState();
        outInfo.importance = procStateToImportance(procState, adj, outInfo,
                clientTargetSdk);
        outInfo.importanceReasonCode = app.adjTypeCode;
        outInfo.processState = app.getCurProcState();
        outInfo.isFocused = (app == mService.getTopAppLocked());
        outInfo.lastActivityTime = app.lastActivityTime;
    }

    @GuardedBy("mService")
    List<ActivityManager.RunningAppProcessInfo> getRunningAppProcessesLocked(boolean allUsers,
            int userId, boolean allUids, int callingUid, int clientTargetSdk) {
        // Lazy instantiation of list
        List<ActivityManager.RunningAppProcessInfo> runList = null;

        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord app = mLruProcesses.get(i);
            if ((!allUsers && app.userId != userId)
                    || (!allUids && app.uid != callingUid)) {
                continue;
            }
            if ((app.thread != null) && (!app.isCrashing() && !app.isNotResponding())) {
                // Generate process state info for running application
                ActivityManager.RunningAppProcessInfo currApp =
                        new ActivityManager.RunningAppProcessInfo(app.processName,
                                app.pid, app.getPackageList());
                fillInProcMemInfoLocked(app, currApp, clientTargetSdk);
                if (app.adjSource instanceof ProcessRecord) {
                    currApp.importanceReasonPid = ((ProcessRecord)app.adjSource).pid;
                    currApp.importanceReasonImportance =
                            ActivityManager.RunningAppProcessInfo.procStateToImportance(
                                    app.adjSourceProcState);
                } else if (app.adjSource instanceof ActivityServiceConnectionsHolder) {
                    final ActivityServiceConnectionsHolder r =
                            (ActivityServiceConnectionsHolder) app.adjSource;
                    final int pid = r.getActivityPid();
                    if (pid != -1) {
                        currApp.importanceReasonPid = pid;
                    }
                }
                if (app.adjTarget instanceof ComponentName) {
                    currApp.importanceReasonComponent = (ComponentName)app.adjTarget;
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

    @GuardedBy("mService")
    int getLruSizeLocked() {
        return mLruProcesses.size();
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
    ArrayList<ProcessRecord> collectProcessesLocked(int start, boolean allPkgs, String[] args) {
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
                if (proc.pid > 0 && proc.pid == pid) {
                    procs.add(proc);
                } else if (allPkgs && proc.pkgList != null
                        && proc.pkgList.containsKey(args[start])) {
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

    @GuardedBy("mService")
    void updateApplicationInfoLocked(List<String> packagesToUpdate, int userId,
            boolean updateFrameworkRes) {
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            final ProcessRecord app = mLruProcesses.get(i);
            if (app.thread == null) {
                continue;
            }

            if (userId != UserHandle.USER_ALL && app.userId != userId) {
                continue;
            }

            final int packageCount = app.pkgList.size();
            for (int j = 0; j < packageCount; j++) {
                final String packageName = app.pkgList.keyAt(j);
                if (updateFrameworkRes || packagesToUpdate.contains(packageName)) {
                    try {
                        final ApplicationInfo ai = AppGlobals.getPackageManager()
                                .getApplicationInfo(packageName, STOCK_PM_FLAGS, app.userId);
                        if (ai != null) {
                            app.thread.scheduleApplicationInfoChanged(ai);
                        }
                    } catch (RemoteException e) {
                        Slog.w(TAG, String.format("Failed to update %s ApplicationInfo for %s",
                                packageName, app));
                    }
                }
            }
        }
    }

    @GuardedBy("mService")
    void sendPackageBroadcastLocked(int cmd, String[] packages, int userId) {
        for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
            ProcessRecord r = mLruProcesses.get(i);
            if (r.thread != null && (userId == UserHandle.USER_ALL || r.userId == userId)) {
                try {
                    r.thread.dispatchPackageBroadcast(cmd, packages);
                } catch (RemoteException ex) {
                }
            }
        }
    }

    /** Returns the uid's process state or PROCESS_STATE_NONEXISTENT if not running */
    @GuardedBy("mService")
    int getUidProcStateLocked(int uid) {
        UidRecord uidRec = mActiveUids.get(uid);
        return uidRec == null ? PROCESS_STATE_NONEXISTENT : uidRec.getCurProcState();
    }

    /** Returns the UidRecord for the given uid, if it exists. */
    @GuardedBy("mService")
    UidRecord getUidRecordLocked(int uid) {
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
            if (!uidRec.idle) {
                continue;
            }
            mService.doStopUidLocked(uidRec.uid, uidRec);
        }
    }
}
