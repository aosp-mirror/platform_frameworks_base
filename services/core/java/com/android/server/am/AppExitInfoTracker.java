/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.app.ActivityManager.RunningAppProcessInfo.procStateToImportance;
import static android.app.ActivityManagerInternal.ALLOW_NON_FULL;
import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PROCESSES;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.Nullable;
import android.app.ApplicationExitInfo;
import android.app.ApplicationExitInfo.Reason;
import android.app.ApplicationExitInfo.SubReason;
import android.app.IAppTraceRetriever;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.icu.text.SimpleDateFormat;
import android.os.Binder;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Pair;
import android.util.Pools.SynchronizedPool;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;
import android.util.proto.WireTypeMismatchException;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ProcessMap;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemServiceManager;
import com.android.server.os.NativeTombstoneManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

/**
 * A class to manage all the {@link android.app.ApplicationExitInfo} records.
 */
public final class AppExitInfoTracker {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppExitInfoTracker" : TAG_AM;

    /**
     * Interval of persisting the app exit info to persistent storage.
     */
    private static final long APP_EXIT_INFO_PERSIST_INTERVAL = TimeUnit.MINUTES.toMillis(30);

    /** These are actions that the forEach* should take after each iteration */
    @VisibleForTesting static final int FOREACH_ACTION_NONE = 0;
    @VisibleForTesting static final int FOREACH_ACTION_REMOVE_ITEM = 1;
    @VisibleForTesting static final int FOREACH_ACTION_STOP_ITERATION = 2;

    private static final int APP_EXIT_RAW_INFO_POOL_SIZE = 8;

    /**
     * How long we're going to hold before logging an app exit info into statsd;
     * we do this is because there could be multiple sources signaling an app exit, we'd like to
     * gather the most accurate information before logging into statsd.
     */
    private static final long APP_EXIT_INFO_STATSD_LOG_DEBOUNCE = TimeUnit.SECONDS.toMillis(15);

    @VisibleForTesting
    static final String APP_EXIT_STORE_DIR = "procexitstore";

    @VisibleForTesting
    static final String APP_EXIT_INFO_FILE = "procexitinfo";

    private static final String APP_TRACE_FILE_SUFFIX = ".gz";

    @VisibleForTesting final Object mLock = new Object();

    /**
     * Initialized in {@link #init} and read-only after that.
     */
    private ActivityManagerService mService;

    /**
     * Initialized in {@link #init} and read-only after that.
     */
    private KillHandler mKillHandler;

    /**
     * The task to persist app process exit info
     */
    @GuardedBy("mLock")
    private Runnable mAppExitInfoPersistTask = null;

    /**
     * Last time(in ms) since epoch that the app exit info was persisted into persistent storage.
     */
    @GuardedBy("mLock")
    private long mLastAppExitInfoPersistTimestamp = 0L;

    /**
     * Retention policy: keep up to X historical exit info per package.
     *
     * Initialized in {@link #init} and read-only after that.
     * Not lock is needed.
     */
    private int mAppExitInfoHistoryListSize;

    /*
     * PackageName/uid -> [pid/info, ...] holder, the uid here is the package uid.
     */
    @GuardedBy("mLock")
    private final ProcessMap<AppExitInfoContainer> mData;

    /** A pool of raw {@link android.app.ApplicationExitInfo} records. */
    @GuardedBy("mLock")
    private final SynchronizedPool<ApplicationExitInfo> mRawRecordsPool;

    /**
     * Wheather or not we've loaded the historical app process exit info from
     * persistent storage.
     */
    @VisibleForTesting
    AtomicBoolean mAppExitInfoLoaded = new AtomicBoolean();

    /**
     * Temporary list being used to filter/sort intermediate results in {@link #getExitInfo}.
     */
    @GuardedBy("mLock")
    final ArrayList<ApplicationExitInfo> mTmpInfoList = new ArrayList<ApplicationExitInfo>();

    /**
     * Temporary list being used to filter/sort intermediate results in {@link #getExitInfo}.
     */
    @GuardedBy("mLock")
    final ArrayList<ApplicationExitInfo> mTmpInfoList2 = new ArrayList<ApplicationExitInfo>();

    /**
     * The path to the directory which includes the historical proc exit info file
     * as specified in {@link #mProcExitInfoFile}, as well as the associated trace files.
     */
    @VisibleForTesting
    File mProcExitStoreDir;

    /**
     * The path to the historical proc exit info file, persisted in the storage.
     */
    @VisibleForTesting
    File mProcExitInfoFile;

    /**
     * Mapping between the isolated UID to its application uid.
     */
    final IsolatedUidRecords mIsolatedUidRecords =
            new IsolatedUidRecords();

    /**
     * Bookkeeping app process exit info from Zygote.
     */
    final AppExitInfoExternalSource mAppExitInfoSourceZygote =
            new AppExitInfoExternalSource("zygote", null);

    /**
     * Bookkeeping low memory kills info from lmkd.
     */
    final AppExitInfoExternalSource mAppExitInfoSourceLmkd =
            new AppExitInfoExternalSource("lmkd", ApplicationExitInfo.REASON_LOW_MEMORY);

    /**
     * The active per-UID/PID state data set by
     * {@link android.app.ActivityManager#setProcessStateSummary};
     * these state data are to be "claimed" when its process dies, by then the data will be moved
     * from this list to the new instance of ApplicationExitInfo.
     *
     * <p> The mapping here is UID -> PID -> state </p>
     *
     * @see android.app.ActivityManager#setProcessStateSummary(byte[])
     */
    @GuardedBy("mLock")
    final SparseArray<SparseArray<byte[]>> mActiveAppStateSummary = new SparseArray<>();

    /**
     * The active per-UID/PID trace file when an ANR occurs but the process hasn't been killed yet,
     * each record is a path to the actual trace file;  these files are to be "claimed"
     * when its process dies, by then the "ownership" of the files will be transferred
     * from this list to the new instance of ApplicationExitInfo.
     *
     * <p> The mapping here is UID -> PID -> file </p>
     */
    @GuardedBy("mLock")
    final SparseArray<SparseArray<File>> mActiveAppTraces = new SparseArray<>();

    /**
     * The implementation of the interface IAppTraceRetriever.
     */
    final AppTraceRetriever mAppTraceRetriever = new AppTraceRetriever();

    AppExitInfoTracker() {
        mData = new ProcessMap<AppExitInfoContainer>();
        mRawRecordsPool = new SynchronizedPool<ApplicationExitInfo>(APP_EXIT_RAW_INFO_POOL_SIZE);
    }

    void init(ActivityManagerService service) {
        mService = service;
        ServiceThread thread = new ServiceThread(TAG + ":killHandler",
                THREAD_PRIORITY_BACKGROUND, true /* allowIo */);
        thread.start();
        mKillHandler = new KillHandler(thread.getLooper());

        mProcExitStoreDir = new File(SystemServiceManager.ensureSystemDir(), APP_EXIT_STORE_DIR);
        if (!FileUtils.createDir(mProcExitStoreDir)) {
            Slog.e(TAG, "Unable to create " + mProcExitStoreDir);
            return;
        }
        mProcExitInfoFile = new File(mProcExitStoreDir, APP_EXIT_INFO_FILE);

        mAppExitInfoHistoryListSize = service.mContext.getResources().getInteger(
                com.android.internal.R.integer.config_app_exit_info_history_list_size);
    }

    void onSystemReady() {
        registerForUserRemoval();
        registerForPackageRemoval();
        IoThread.getHandler().post(() -> {
            // Read the sysprop set by lmkd and set this to persist so app could read it.
            SystemProperties.set("persist.sys.lmk.reportkills",
                    Boolean.toString(SystemProperties.getBoolean("sys.lmk.reportkills", false)));
            loadExistingProcessExitInfo();
        });
    }

    void scheduleNoteProcessDied(final ProcessRecord app) {
        if (app == null || app.info == null) {
            return;
        }

        if (!mAppExitInfoLoaded.get()) {
            return;
        }
        mKillHandler.obtainMessage(KillHandler.MSG_PROC_DIED,
                obtainRawRecord(app, System.currentTimeMillis())).sendToTarget();
    }

    void scheduleNoteAppKill(final ProcessRecord app, final @Reason int reason,
            final @SubReason int subReason, final String msg) {
        if (!mAppExitInfoLoaded.get()) {
            return;
        }
        if (app == null || app.info == null) {
            return;
        }

        ApplicationExitInfo raw = obtainRawRecord(app, System.currentTimeMillis());
        raw.setReason(reason);
        raw.setSubReason(subReason);
        raw.setDescription(msg);
        mKillHandler.obtainMessage(KillHandler.MSG_APP_KILL, raw).sendToTarget();
    }

    void scheduleNoteAppRecoverableCrash(final ProcessRecord app) {
        if (!mAppExitInfoLoaded.get() || app == null || app.info == null) return;

        ApplicationExitInfo raw = obtainRawRecord(app, System.currentTimeMillis());
        raw.setReason(ApplicationExitInfo.REASON_CRASH_NATIVE);
        raw.setSubReason(ApplicationExitInfo.SUBREASON_UNKNOWN);
        raw.setDescription("recoverable_crash");
        mKillHandler.obtainMessage(KillHandler.MSG_APP_RECOVERABLE_CRASH, raw).sendToTarget();
    }

    void scheduleNoteAppKill(final int pid, final int uid, final @Reason int reason,
            final @SubReason int subReason, final String msg) {
        if (!mAppExitInfoLoaded.get()) {
            return;
        }
        ProcessRecord app;
        synchronized (mService.mPidsSelfLocked) {
            app = mService.mPidsSelfLocked.get(pid);
        }
        if (app == null) {
            if (DEBUG_PROCESSES) {
                Slog.w(TAG, "Skipping saving the kill reason for pid " + pid
                        + "(uid=" + uid + ") since its process record is not found");
            }
        } else {
            scheduleNoteAppKill(app, reason, subReason, msg);
        }
    }

    interface LmkdKillListener {
        /**
         * Called when there is a process kill by lmkd.
         */
        void onLmkdKillOccurred(int pid, int uid);
    }

    void setLmkdKillListener(final LmkdKillListener listener) {
        synchronized (mLock) {
            mAppExitInfoSourceLmkd.setOnProcDiedListener((pid, uid) ->
                    listener.onLmkdKillOccurred(pid, uid));
        }
    }

    /** Called when there is a low memory kill */
    void scheduleNoteLmkdProcKilled(final int pid, final int uid, final int rssKb) {
        mKillHandler.obtainMessage(KillHandler.MSG_LMKD_PROC_KILLED, pid, uid, Long.valueOf(rssKb))
                .sendToTarget();
    }

    private void scheduleChildProcDied(int pid, int uid, int status) {
        mKillHandler.obtainMessage(KillHandler.MSG_CHILD_PROC_DIED, pid, uid, (Integer) status)
                .sendToTarget();
    }

    /** Calls when zygote sends us SIGCHLD */
    void handleZygoteSigChld(int pid, int uid, int status) {
        if (DEBUG_PROCESSES) {
            Slog.i(TAG, "Got SIGCHLD from zygote: pid=" + pid + ", uid=" + uid
                    + ", status=" + Integer.toHexString(status));
        }
        scheduleChildProcDied(pid, uid, status);
    }

    /**
     * Main routine to create or update the {@link android.app.ApplicationExitInfo} for the given
     * ProcessRecord, also query the zygote and lmkd records to make the information more accurate.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    void handleNoteProcessDiedLocked(final ApplicationExitInfo raw) {
        if (raw != null) {
            if (DEBUG_PROCESSES) {
                Slog.i(TAG, "Update process exit info for " + raw.getPackageName()
                        + "(" + raw.getPid() + "/u" + raw.getRealUid() + ")");
            }

            ApplicationExitInfo info = getExitInfoLocked(raw.getPackageName(),
                    raw.getPackageUid(), raw.getPid());

            // query zygote and lmkd to get the exit info, and clear the saved info
            Pair<Long, Object> zygote = mAppExitInfoSourceZygote.remove(
                    raw.getPid(), raw.getRealUid());
            Pair<Long, Object> lmkd = mAppExitInfoSourceLmkd.remove(
                    raw.getPid(), raw.getRealUid());
            mIsolatedUidRecords.removeIsolatedUidLocked(raw.getRealUid());

            if (info == null) {
                info = addExitInfoLocked(raw);
            }

            if (lmkd != null) {
                updateExistingExitInfoRecordLocked(info, null,
                        ApplicationExitInfo.REASON_LOW_MEMORY, (Long) lmkd.second);
            } else if (zygote != null) {
                updateExistingExitInfoRecordLocked(info, (Integer) zygote.second, null, null);
            } else {
                scheduleLogToStatsdLocked(info, false);
            }
        }
    }

    /**
     * Certain types of crashes should not be updated. This could end up deleting valuable
     * information, for example, if a test application crashes and then the `am instrument`
     * finishes, then the crash whould be replaced with a `reason == USER_REQUESTED`
     * ApplicationExitInfo from ActivityManager, and the original crash would be lost.
     */
    private boolean preventExitInfoUpdate(final ApplicationExitInfo exitInfo) {
        switch (exitInfo.getReason()) {
            case ApplicationExitInfo.REASON_ANR:
            case ApplicationExitInfo.REASON_CRASH:
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return true;
            default:
                return false;
        }
    }

    /**
     * Make note when ActivityManagerService decides to kill an application process.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    void handleNoteAppKillLocked(final ApplicationExitInfo raw) {
        ApplicationExitInfo info = getExitInfoLocked(
                raw.getPackageName(), raw.getPackageUid(), raw.getPid());

        if (info == null || preventExitInfoUpdate(info)) {
            info = addExitInfoLocked(raw);
        } else {
            // Override the existing info since we have more information.
            info.setReason(raw.getReason());
            info.setSubReason(raw.getSubReason());
            info.setStatus(0);
            info.setTimestamp(System.currentTimeMillis());
            info.setDescription(raw.getDescription());
        }
        scheduleLogToStatsdLocked(info, true);
    }

    @GuardedBy("mLock")
    private ApplicationExitInfo addExitInfoLocked(ApplicationExitInfo raw) {
        if (!mAppExitInfoLoaded.get()) {
            Slog.w(TAG, "Skipping saving the exit info due to ongoing loading from storage");
            return null;
        }

        final ApplicationExitInfo info = new ApplicationExitInfo(raw);
        final String[] packages = raw.getPackageList();
        int uid = raw.getRealUid();
        if (UserHandle.isIsolated(uid)) {
            Integer k = mIsolatedUidRecords.getUidByIsolatedUid(uid);
            if (k != null) {
                uid = k;
            }
        }
        for (int i = 0; i < packages.length; i++) {
            addExitInfoInnerLocked(packages[i], uid, info);
        }

        // SDK sandbox exits are stored under both real and package UID
        if (Process.isSdkSandboxUid(uid)) {
            for (int i = 0; i < packages.length; i++) {
                addExitInfoInnerLocked(packages[i], raw.getPackageUid(), info);
            }
        }

        schedulePersistProcessExitInfo(false);

        return info;
    }

    /**
     * Update an existing {@link android.app.ApplicationExitInfo} record with given information.
     */
    @GuardedBy("mLock")
    private void updateExistingExitInfoRecordLocked(ApplicationExitInfo info,
            Integer status, Integer reason, Long rssKb) {
        if (info == null || !isFresh(info.getTimestamp())) {
            // if the record is way outdated, don't update it then (because of potential pid reuse)
            return;
        }
        boolean immediateLog = false;
        if (status != null) {
            if (OsConstants.WIFEXITED(status)) {
                info.setReason(ApplicationExitInfo.REASON_EXIT_SELF);
                info.setStatus(OsConstants.WEXITSTATUS(status));
                immediateLog = true;
            } else if (OsConstants.WIFSIGNALED(status)) {
                if (info.getReason() == ApplicationExitInfo.REASON_UNKNOWN) {
                    info.setReason(ApplicationExitInfo.REASON_SIGNALED);
                    info.setStatus(OsConstants.WTERMSIG(status));
                } else if (info.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    info.setStatus(OsConstants.WTERMSIG(status));
                    immediateLog = true;
                }
            }
        }
        if (reason != null) {
            info.setReason(reason);
            if (reason == ApplicationExitInfo.REASON_LOW_MEMORY) {
                immediateLog = true;
            }
        }
        if (rssKb != null) {
            info.setRss(rssKb.longValue());
        }
        scheduleLogToStatsdLocked(info, immediateLog);
    }

    /**
     * Update an existing {@link android.app.ApplicationExitInfo} record with given information.
     *
     * @return true if a recond is updated
     */
    @GuardedBy("mLock")
    private boolean updateExitInfoIfNecessaryLocked(
            int pid, int uid, Integer status, Integer reason, Long rssKb) {
        Integer k = mIsolatedUidRecords.getUidByIsolatedUid(uid);
        if (k != null) {
            uid = k;
        }
        final int targetUid = uid;
        // Launder the modification bit through a `final` array, as Java doesn't allow you to mutate
        // a captured boolean inside of a lambda.
        final boolean[] isModified = {false};
        forEachPackageLocked((packageName, records) -> {
            AppExitInfoContainer container = records.get(targetUid);
            if (container == null) {
                return FOREACH_ACTION_NONE;
            }
            mTmpInfoList.clear();
            container.getExitInfosLocked(pid, /* maxNum */ 0, mTmpInfoList);
            if (mTmpInfoList.size() == 0) {
                return FOREACH_ACTION_NONE;
            }

            for (int i = 0, size = mTmpInfoList.size(); i < size; i++) {
                ApplicationExitInfo info = mTmpInfoList.get(i);
                if (info.getRealUid() != targetUid) {
                    continue;
                }
                // We only update the most recent `ApplicationExitInfo` for this pid, which will
                // always be the first one we se as `getExitInfosLocked()` returns them sorted
                // by most-recent-first.
                isModified[0] = true;
                updateExistingExitInfoRecordLocked(info, status, reason, rssKb);
                return FOREACH_ACTION_STOP_ITERATION;
            }
            return FOREACH_ACTION_NONE;
        });
        mTmpInfoList.clear();
        return isModified[0];
    }

    /**
     * Get the exit info with matching package name, filterUid and filterPid (if > 0)
     */
    @VisibleForTesting
    void getExitInfo(final String packageName, final int filterUid, final int filterPid,
            final int maxNum, final List<ApplicationExitInfo> results) {
        final long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                if (!TextUtils.isEmpty(packageName)) {
                    // Fast path - just a single package.
                    AppExitInfoContainer container = mData.get(packageName, filterUid);
                    if (container != null) {
                        container.getExitInfosLocked(filterPid, maxNum, results);
                    }
                    return;
                }

                // Slow path - get all the packages.
                forEachPackageLocked((name, records) -> {
                    AppExitInfoContainer container = records.get(filterUid);
                    if (container != null) {
                        container.getExitInfosLocked(filterPid, /* maxNum */ 0, results);
                    }
                    return AppExitInfoTracker.FOREACH_ACTION_NONE;
                });

                // And while the results for each package are sorted, we should
                // sort over and trim the quantity of global results as well.
                Collections.sort(
                        results, (a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
                if (maxNum <= 0) {
                    return;
                }

                int elementsToRemove = results.size() - maxNum;
                for (int i = 0; i < elementsToRemove; i++) {
                    results.removeLast();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Return the first matching exit info record, for internal use, the parameters are not supposed
     * to be empty.
     */
    @GuardedBy("mLock")
    private ApplicationExitInfo getExitInfoLocked(final String packageName,
            final int filterUid, final int filterPid) {
        mTmpInfoList.clear();
        getExitInfo(packageName, filterUid, filterPid, 1, mTmpInfoList);
        ApplicationExitInfo info = mTmpInfoList.size() > 0 ? mTmpInfoList.getFirst() : null;
        mTmpInfoList.clear();
        return info;
    }

    @VisibleForTesting
    void onUserRemoved(int userId) {
        mAppExitInfoSourceZygote.removeByUserId(userId);
        mAppExitInfoSourceLmkd.removeByUserId(userId);
        mIsolatedUidRecords.removeByUserId(userId);
        synchronized (mLock) {
            removeByUserIdLocked(userId);
            schedulePersistProcessExitInfo(true);
        }
    }

    @VisibleForTesting
    void onPackageRemoved(String packageName, int uid, boolean allUsers) {
        if (packageName != null) {
            final boolean removeUid = TextUtils.isEmpty(
                    mService.mPackageManagerInt.getNameForUid(uid));
            synchronized (mLock) {
                if (removeUid) {
                    mAppExitInfoSourceZygote.removeByUidLocked(uid, allUsers);
                    mAppExitInfoSourceLmkd.removeByUidLocked(uid, allUsers);
                    mIsolatedUidRecords.removeAppUid(uid, allUsers);
                }
                removePackageLocked(packageName, uid, removeUid,
                        allUsers ? UserHandle.USER_ALL : UserHandle.getUserId(uid));
                schedulePersistProcessExitInfo(true);
            }
        }
    }

    private void registerForUserRemoval() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_REMOVED);
        mService.mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (userId < 1) return;
                onUserRemoved(userId);
            }
        }, filter, null, mKillHandler);
    }

    private void registerForPackageRemoval() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        mService.mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean replacing = intent.getBooleanExtra(
                        Intent.EXTRA_REPLACING, false);
                if (replacing) {
                    return;
                }
                int uid = intent.getIntExtra(Intent.EXTRA_UID, UserHandle.USER_NULL);
                boolean allUsers = intent.getBooleanExtra(
                        Intent.EXTRA_REMOVED_FOR_ALL_USERS, false);
                onPackageRemoved(intent.getData().getSchemeSpecificPart(), uid, allUsers);
            }
        }, filter, null, mKillHandler);
    }

    /**
     * Load the existing {@link android.app.ApplicationExitInfo} records from persistent storage.
     */
    @VisibleForTesting
    void loadExistingProcessExitInfo() {
        if (!mProcExitInfoFile.canRead()) {
            mAppExitInfoLoaded.set(true);
            return;
        }

        FileInputStream fin = null;
        try {
            AtomicFile af = new AtomicFile(mProcExitInfoFile);
            fin = af.openRead();
            ProtoInputStream proto = new ProtoInputStream(fin);
            for (int next = proto.nextField();
                    next != ProtoInputStream.NO_MORE_FIELDS;
                    next = proto.nextField()) {
                switch (next) {
                    case (int) AppsExitInfoProto.LAST_UPDATE_TIMESTAMP:
                        synchronized (mLock) {
                            mLastAppExitInfoPersistTimestamp =
                                    proto.readLong(AppsExitInfoProto.LAST_UPDATE_TIMESTAMP);
                        }
                        break;
                    case (int) AppsExitInfoProto.PACKAGES:
                        loadPackagesFromProto(proto, next);
                        break;
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Error in loading historical app exit info from persistent storage: " + e);
        } finally {
            if (fin != null) {
                try {
                    fin.close();
                } catch (IOException e) {
                }
            }
        }
        synchronized (mLock) {
            pruneAnrTracesIfNecessaryLocked();
            mAppExitInfoLoaded.set(true);
        }
    }

    private void loadPackagesFromProto(ProtoInputStream proto, long fieldId)
            throws IOException, WireTypeMismatchException {
        long token = proto.start(fieldId);
        String pkgName = "";
        for (int next = proto.nextField();
                next != ProtoInputStream.NO_MORE_FIELDS;
                next = proto.nextField()) {
            switch (next) {
                case (int) AppsExitInfoProto.Package.PACKAGE_NAME:
                    pkgName = proto.readString(AppsExitInfoProto.Package.PACKAGE_NAME);
                    break;
                case (int) AppsExitInfoProto.Package.USERS:
                    AppExitInfoContainer container = new AppExitInfoContainer(
                            mAppExitInfoHistoryListSize);
                    int uid = container.readFromProto(proto, AppsExitInfoProto.Package.USERS);
                    synchronized (mLock) {
                        mData.put(pkgName, uid, container);
                    }
                    break;
            }
        }
        proto.end(token);
    }

    /**
     * Persist the existing {@link android.app.ApplicationExitInfo} records to storage.
     */
    @VisibleForTesting
    void persistProcessExitInfo() {
        AtomicFile af = new AtomicFile(mProcExitInfoFile);
        FileOutputStream out = null;
        long now = System.currentTimeMillis();
        try {
            out = af.startWrite();
            ProtoOutputStream proto = new ProtoOutputStream(out);
            proto.write(AppsExitInfoProto.LAST_UPDATE_TIMESTAMP, now);
            synchronized (mLock) {
                forEachPackageLocked((packageName, records) -> {
                    long token = proto.start(AppsExitInfoProto.PACKAGES);
                    proto.write(AppsExitInfoProto.Package.PACKAGE_NAME, packageName);
                    int uidArraySize = records.size();
                    for (int j = 0; j < uidArraySize; j++) {
                        records.valueAt(j).writeToProto(proto, AppsExitInfoProto.Package.USERS);
                    }
                    proto.end(token);
                    return AppExitInfoTracker.FOREACH_ACTION_NONE;
                });
                mLastAppExitInfoPersistTimestamp = now;
            }
            proto.flush();
            af.finishWrite(out);
        } catch (IOException e) {
            Slog.w(TAG, "Unable to write historical app exit info into persistent storage: " + e);
            af.failWrite(out);
        }
        synchronized (mLock) {
            mAppExitInfoPersistTask = null;
        }
    }

    /**
     * Schedule a task to persist the {@link android.app.ApplicationExitInfo} records to storage.
     */
    @VisibleForTesting
    void schedulePersistProcessExitInfo(boolean immediately) {
        synchronized (mLock) {
            if (mAppExitInfoPersistTask == null || immediately) {
                if (mAppExitInfoPersistTask != null) {
                    IoThread.getHandler().removeCallbacks(mAppExitInfoPersistTask);
                }
                mAppExitInfoPersistTask = this::persistProcessExitInfo;
                IoThread.getHandler().postDelayed(mAppExitInfoPersistTask,
                        immediately ? 0 : APP_EXIT_INFO_PERSIST_INTERVAL);
            }
        }
    }

    /**
     * Helper function for testing only.
     */
    @VisibleForTesting
    void clearProcessExitInfo(boolean removeFile) {
        synchronized (mLock) {
            if (mAppExitInfoPersistTask != null) {
                IoThread.getHandler().removeCallbacks(mAppExitInfoPersistTask);
                mAppExitInfoPersistTask = null;
            }
            if (removeFile && mProcExitInfoFile != null) {
                mProcExitInfoFile.delete();
            }
            mData.getMap().clear();
            mActiveAppStateSummary.clear();
            mActiveAppTraces.clear();
            pruneAnrTracesIfNecessaryLocked();
        }
    }

    /**
     * Helper function for shell command
     */
    void clearHistoryProcessExitInfo(String packageName, int userId) {
        NativeTombstoneManager tombstoneService = LocalServices.getService(
                NativeTombstoneManager.class);
        Optional<Integer> appId = Optional.empty();

        if (TextUtils.isEmpty(packageName)) {
            synchronized (mLock) {
                removeByUserIdLocked(userId);
            }
        } else {
            final int uid = mService.mPackageManagerInt.getPackageUid(packageName,
                    PackageManager.MATCH_ALL, userId);
            appId = Optional.of(UserHandle.getAppId(uid));
            synchronized (mLock) {
                removePackageLocked(packageName, uid, true, userId);
            }
        }

        tombstoneService.purge(Optional.of(userId), appId);
        schedulePersistProcessExitInfo(true);
    }

    void dumpHistoryProcessExitInfo(PrintWriter pw, String packageName) {
        pw.println("ACTIVITY MANAGER PROCESS EXIT INFO (dumpsys activity exit-info)");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        synchronized (mLock) {
            pw.println("Last Timestamp of Persistence Into Persistent Storage: "
                    + sdf.format(new Date(mLastAppExitInfoPersistTimestamp)));
            if (TextUtils.isEmpty(packageName)) {
                forEachPackageLocked((name, records) -> {
                    dumpHistoryProcessExitInfoLocked(pw, "  ", name, records, sdf);
                    return AppExitInfoTracker.FOREACH_ACTION_NONE;
                });
            } else {
                SparseArray<AppExitInfoContainer> array = mData.getMap().get(packageName);
                if (array != null) {
                    dumpHistoryProcessExitInfoLocked(pw, "  ", packageName, array, sdf);
                }
            }
        }
    }

    @GuardedBy("mLock")
    private void dumpHistoryProcessExitInfoLocked(PrintWriter pw, String prefix,
            String packageName, SparseArray<AppExitInfoContainer> array,
            SimpleDateFormat sdf) {
        pw.println(prefix + "package: " + packageName);
        int size = array.size();
        for (int i = 0; i < size; i++) {
            pw.println(prefix + "  Historical Process Exit for uid=" + array.keyAt(i));
            array.valueAt(i).dumpLocked(pw, prefix + "    ", sdf);
        }
    }

    @GuardedBy("mLock")
    private void addExitInfoInnerLocked(String packageName, int uid, ApplicationExitInfo info) {
        AppExitInfoContainer container = mData.get(packageName, uid);
        if (container == null) {
            container = new AppExitInfoContainer(mAppExitInfoHistoryListSize);
            if (UserHandle.isIsolated(info.getRealUid())) {
                Integer k = mIsolatedUidRecords.getUidByIsolatedUid(info.getRealUid());
                if (k != null) {
                    container.mUid = k;
                }
            } else {
                container.mUid = info.getRealUid();
            }
            mData.put(packageName, uid, container);
        }
        container.addExitInfoLocked(info);
    }

    @GuardedBy("mLock")
    private void scheduleLogToStatsdLocked(ApplicationExitInfo info, boolean immediate) {
        if (info.isLoggedInStatsd()) {
            return;
        }
        if (immediate) {
            mKillHandler.removeMessages(KillHandler.MSG_STATSD_LOG, info);
            performLogToStatsdLocked(info);
        } else if (!mKillHandler.hasMessages(KillHandler.MSG_STATSD_LOG, info)) {
            mKillHandler.sendMessageDelayed(mKillHandler.obtainMessage(
                    KillHandler.MSG_STATSD_LOG, info), APP_EXIT_INFO_STATSD_LOG_DEBOUNCE);
        }
    }

    @GuardedBy("mLock")
    private void performLogToStatsdLocked(ApplicationExitInfo info) {
        if (info.isLoggedInStatsd()) {
            return;
        }
        info.setLoggedInStatsd(true);
        final String pkgName = info.getPackageName();
        String processName = info.getProcessName();
        if (TextUtils.equals(pkgName, processName)) {
            // Omit the process name here to save space
            processName = null;
        } else if (processName != null && pkgName != null && processName.startsWith(pkgName)) {
            // Strip the prefix to save space
            processName = processName.substring(pkgName.length());
        }
        FrameworkStatsLog.write(FrameworkStatsLog.APP_PROCESS_DIED,
                info.getPackageUid(), processName, info.getReason(), info.getSubReason(),
                info.getImportance(), (int) info.getPss(), (int) info.getRss(),
                info.hasForegroundServices());
    }

    @GuardedBy("mLock")
    private void forEachPackageLocked(
            BiFunction<String, SparseArray<AppExitInfoContainer>, Integer> callback) {
        if (callback != null) {
            ArrayMap<String, SparseArray<AppExitInfoContainer>> map = mData.getMap();
            for (int i = map.size() - 1; i >= 0; i--) {
                switch (callback.apply(map.keyAt(i), map.valueAt(i))) {
                    case FOREACH_ACTION_REMOVE_ITEM:
                        final SparseArray<AppExitInfoContainer> records = map.valueAt(i);
                        for (int j = records.size() - 1; j >= 0; j--) {
                            records.valueAt(j).destroyLocked();
                        }
                        map.removeAt(i);
                        break;
                    case FOREACH_ACTION_STOP_ITERATION:
                        i = 0;
                        break;
                    case FOREACH_ACTION_NONE:
                    default:
                        break;
                }
            }
        }
    }

    @GuardedBy("mLock")
    private void removePackageLocked(String packageName, int uid, boolean removeUid, int userId) {
        if (removeUid) {
            mActiveAppStateSummary.remove(uid);
            final int idx = mActiveAppTraces.indexOfKey(uid);
            if (idx >= 0) {
                final SparseArray<File> array = mActiveAppTraces.valueAt(idx);
                for (int i = array.size() - 1; i >= 0; i--) {
                    array.valueAt(i).delete();
                }
                mActiveAppTraces.removeAt(idx);
            }
        }
        ArrayMap<String, SparseArray<AppExitInfoContainer>> map = mData.getMap();
        SparseArray<AppExitInfoContainer> array = map.get(packageName);
        if (array == null) {
            return;
        }
        if (userId == UserHandle.USER_ALL) {
            for (int i = array.size() - 1; i >= 0; i--) {
                array.valueAt(i).destroyLocked();
            }
            mData.getMap().remove(packageName);
        } else {
            for (int i = array.size() - 1; i >= 0; i--) {
                if (UserHandle.getUserId(array.keyAt(i)) == userId) {
                    array.valueAt(i).destroyLocked();
                    array.removeAt(i);
                    break;
                }
            }
            if (array.size() == 0) {
                map.remove(packageName);
            }
        }
    }

    @GuardedBy("mLock")
    private void removeByUserIdLocked(final int userId) {
        if (userId == UserHandle.USER_ALL) {
            mData.getMap().clear();
            mActiveAppStateSummary.clear();
            mActiveAppTraces.clear();
            pruneAnrTracesIfNecessaryLocked();
            return;
        }
        removeFromSparse2dArray(mActiveAppStateSummary,
                (v) -> UserHandle.getUserId(v) == userId, null, null);
        removeFromSparse2dArray(mActiveAppTraces,
                (v) -> UserHandle.getUserId(v) == userId, null, (v) -> v.delete());
        forEachPackageLocked((packageName, records) -> {
            for (int i = records.size() - 1; i >= 0; i--) {
                if (UserHandle.getUserId(records.keyAt(i)) == userId) {
                    records.valueAt(i).destroyLocked();
                    records.removeAt(i);
                    break;
                }
            }
            return records.size() == 0 ? FOREACH_ACTION_REMOVE_ITEM : FOREACH_ACTION_NONE;
        });
    }

    @VisibleForTesting
    ApplicationExitInfo obtainRawRecord(ProcessRecord app, @CurrentTimeMillisLong long timestamp) {
        ApplicationExitInfo info = mRawRecordsPool.acquire();
        if (info == null) {
            info = new ApplicationExitInfo();
        }

        synchronized (mService.mProcLock) {
            final int definingUid = app.getHostingRecord() != null
                    ? app.getHostingRecord().getDefiningUid() : 0;
            info.setPid(app.getPid());
            info.setRealUid(app.uid);
            info.setPackageUid(app.info.uid);
            info.setDefiningUid(definingUid > 0 ? definingUid : app.info.uid);
            info.setProcessName(app.processName);
            info.setConnectionGroup(app.mServices.getConnectionGroup());
            info.setPackageName(app.info.packageName);
            info.setPackageList(app.getPackageList());
            info.setReason(ApplicationExitInfo.REASON_UNKNOWN);
            info.setSubReason(ApplicationExitInfo.SUBREASON_UNKNOWN);
            info.setStatus(0);
            info.setImportance(procStateToImportance(app.mState.getReportedProcState()));
            info.setPss(app.mProfile.getLastPss());
            info.setRss(app.mProfile.getLastRss());
            info.setTimestamp(timestamp);
            info.setHasForegroundServices(app.mServices.hasReportedForegroundServices());
        }

        return info;
    }

    @VisibleForTesting
    void recycleRawRecord(ApplicationExitInfo info) {
        info.setProcessName(null);
        info.setDescription(null);
        info.setPackageList(null);

        mRawRecordsPool.release(info);
    }

    /**
     * Called from {@link ActivityManagerService#setProcessStateSummary}.
     */
    @VisibleForTesting
    void setProcessStateSummary(int uid, final int pid, final byte[] data) {
        synchronized (mLock) {
            Integer k = mIsolatedUidRecords.getUidByIsolatedUid(uid);
            if (k != null) {
                uid = k;
            }
            putToSparse2dArray(mActiveAppStateSummary, uid, pid, data, SparseArray::new, null);
        }
    }

    @VisibleForTesting
    @Nullable byte[] getProcessStateSummary(int uid, final int pid) {
        synchronized (mLock) {
            Integer k = mIsolatedUidRecords.getUidByIsolatedUid(uid);
            if (k != null) {
                uid = k;
            }
            int index = mActiveAppStateSummary.indexOfKey(uid);
            if (index < 0) {
                return null;
            }
            return mActiveAppStateSummary.valueAt(index).get(pid);
        }
    }

    /**
     * Called from ProcessRecord when an ANR occurred and the ANR trace is taken.
     */
    void scheduleLogAnrTrace(final int pid, final int uid, final String[] packageList,
            final File traceFile, final long startOff, final long endOff) {
        mKillHandler.sendMessage(PooledLambda.obtainMessage(
                this::handleLogAnrTrace, pid, uid, packageList,
                traceFile, startOff, endOff));
    }

    /**
     * Copy and compress the given ANR trace file
     */
    @VisibleForTesting
    void handleLogAnrTrace(final int pid, int uid, final String[] packageList,
            final File traceFile, final long startOff, final long endOff) {
        if (!traceFile.exists() || ArrayUtils.isEmpty(packageList)) {
            return;
        }
        final long size = traceFile.length();
        final long length = endOff - startOff;
        if (startOff >= size || endOff > size || length <= 0) {
            return;
        }

        final File outFile = new File(mProcExitStoreDir, traceFile.getName()
                + APP_TRACE_FILE_SUFFIX);
        // Copy & compress
        if (copyToGzFile(traceFile, outFile, startOff, length)) {
            // Wrote successfully.
            synchronized (mLock) {
                Integer k = mIsolatedUidRecords.getUidByIsolatedUid(uid);
                if (k != null) {
                    uid = k;
                }
                if (DEBUG_PROCESSES) {
                    Slog.i(TAG, "Stored ANR traces of " + pid + "/u" + uid + " in " + outFile);
                }
                boolean pending = true;
                // Unlikely but possible: the app has died
                for (int i = 0; i < packageList.length; i++) {
                    final AppExitInfoContainer container = mData.get(packageList[i], uid);
                    // Try to see if we could append this trace to an existing record
                    if (container != null && container.appendTraceIfNecessaryLocked(pid, outFile)) {
                        // Okay someone took it
                        pending = false;
                    }
                }
                if (pending) {
                    // Save it into a temporary list for later use (when the app dies).
                    putToSparse2dArray(mActiveAppTraces, uid, pid, outFile,
                            SparseArray::new, (v) -> v.delete());
                }
            }
        }
    }

    /**
     * Copy the given portion of the file into a gz file.
     *
     * @param inFile The source file.
     * @param outFile The destination file, which will be compressed in gzip format.
     * @param start The start offset where the copy should start from.
     * @param length The number of bytes that should be copied.
     * @return If the copy was successful or not.
     */
    private static boolean copyToGzFile(final File inFile, final File outFile,
            final long start, final long length) {
        long remaining = length;
        try (
            BufferedInputStream in = new BufferedInputStream(new FileInputStream(inFile));
            GZIPOutputStream out = new GZIPOutputStream(new BufferedOutputStream(
                    new FileOutputStream(outFile)))) {
            final byte[] buffer = new byte[8192];
            in.skip(start);
            while (remaining > 0) {
                int t = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (t < 0) {
                    break;
                }
                out.write(buffer, 0, t);
                remaining -= t;
            }
        } catch (IOException e) {
            if (DEBUG_PROCESSES) {
                Slog.e(TAG, "Error in copying ANR trace from " + inFile + " to " + outFile, e);
            }
            return false;
        }
        return remaining == 0 && outFile.exists();
    }

    /**
     * In case there is any orphan ANR trace file, remove it.
     */
    @GuardedBy("mLock")
    private void pruneAnrTracesIfNecessaryLocked() {
        final ArraySet<String> allFiles = new ArraySet();
        final File[] files = mProcExitStoreDir.listFiles((f) -> {
            final String name = f.getName();
            boolean trace = name.startsWith(StackTracesDumpHelper.ANR_FILE_PREFIX)
                    && name.endsWith(APP_TRACE_FILE_SUFFIX);
            if (trace) {
                allFiles.add(name);
            }
            return trace;
        });
        if (ArrayUtils.isEmpty(files)) {
            return;
        }
        // Find out the owners from the existing records
        forEachPackageLocked((name, records) -> {
            for (int i = records.size() - 1; i >= 0; i--) {
                final AppExitInfoContainer container = records.valueAt(i);
                container.forEachRecordLocked((info) -> {
                    final File traceFile = info.getTraceFile();
                    if (traceFile != null) {
                        allFiles.remove(traceFile.getName());
                    }
                    return FOREACH_ACTION_NONE;
                });
            }
            return AppExitInfoTracker.FOREACH_ACTION_NONE;
        });
        // See if there is any active process owns it.
        forEachSparse2dArray(mActiveAppTraces, (v) -> allFiles.remove(v.getName()));

        // Remove orphan traces if nobody claims it.
        for (int i = allFiles.size() - 1; i >= 0; i--) {
            (new File(mProcExitStoreDir, allFiles.valueAt(i))).delete();
        }
    }

    /**
     * A utility function to add the given value to the given 2d SparseArray
     */
    private static <T extends SparseArray<U>, U> void putToSparse2dArray(final SparseArray<T> array,
            final int outerKey, final int innerKey, final U value, final Supplier<T> newInstance,
            final Consumer<U> actionToOldValue) {
        int idx = array.indexOfKey(outerKey);
        T innerArray = null;
        if (idx < 0) {
            innerArray = newInstance.get();
            array.put(outerKey, innerArray);
        } else {
            innerArray = array.valueAt(idx);
        }
        idx = innerArray.indexOfKey(innerKey);
        if (idx >= 0) {
            if (actionToOldValue != null) {
                actionToOldValue.accept(innerArray.valueAt(idx));
            }
            innerArray.setValueAt(idx, value);
        } else {
            innerArray.put(innerKey, value);
        }
    }

    /**
     * A utility function to iterate through the given 2d SparseArray
     */
    private static <T extends SparseArray<U>, U> void forEachSparse2dArray(
            final SparseArray<T> array, final Consumer<U> action) {
        if (action != null) {
            for (int i = array.size() - 1; i >= 0; i--) {
                T innerArray = array.valueAt(i);
                if (innerArray == null) {
                    continue;
                }
                for (int j = innerArray.size() - 1; j >= 0; j--) {
                    action.accept(innerArray.valueAt(j));
                }
            }
        }
    }

    /**
     * A utility function to remove elements from the given 2d SparseArray
     */
    private static <T extends SparseArray<U>, U> void removeFromSparse2dArray(
            final SparseArray<T> array, final Predicate<Integer> outerPredicate,
            final Predicate<Integer> innerPredicate, final Consumer<U> action) {
        for (int i = array.size() - 1; i >= 0; i--) {
            if (outerPredicate == null || outerPredicate.test(array.keyAt(i))) {
                final T innerArray = array.valueAt(i);
                if (innerArray == null) {
                    continue;
                }
                for (int j = innerArray.size() - 1; j >= 0; j--) {
                    if (innerPredicate == null || innerPredicate.test(innerArray.keyAt(j))) {
                        if (action != null) {
                            action.accept(innerArray.valueAt(j));
                        }
                        innerArray.removeAt(j);
                    }
                }
                if (innerArray.size() == 0) {
                    array.removeAt(i);
                }
            }
        }
    }

    /**
     * A utility function to find and remove elements from the given 2d SparseArray.
     */
    private static <T extends SparseArray<U>, U> U findAndRemoveFromSparse2dArray(
            final SparseArray<T> array, final int outerKey, final int innerKey) {
        final int idx = array.indexOfKey(outerKey);
        if (idx >= 0) {
            T p = array.valueAt(idx);
            if (p == null) {
                return null;
            }
            final int innerIdx = p.indexOfKey(innerKey);
            if (innerIdx >= 0) {
                final U ret = p.valueAt(innerIdx);
                p.removeAt(innerIdx);
                if (p.size() == 0) {
                    array.removeAt(idx);
                }
                return ret;
            }
        }
        return null;
    }

    /**
     * A container class of {@link android.app.ApplicationExitInfo}
     */
    final class AppExitInfoContainer {
        private ArrayList<ApplicationExitInfo> mExitInfos;
        private int mMaxCapacity;
        private int mUid; // Application uid, not isolated uid.

        AppExitInfoContainer(final int maxCapacity) {
            mExitInfos = new ArrayList<ApplicationExitInfo>();
            mMaxCapacity = maxCapacity;
        }

        @VisibleForTesting
        @GuardedBy("mLock")
        void getExitInfosLocked(
                final int filterPid, final int maxNum, List<ApplicationExitInfo> results) {
            if (mExitInfos.size() == 0) {
                return;
            }

            // Most of the callers might only be interested with the most recent
            // ApplicationExitInfo, and so we can special case an O(n) walk.
            if (maxNum == 1) {
                ApplicationExitInfo result = null;
                for (int i = 0, size = mExitInfos.size(); i < size; i++) {
                    ApplicationExitInfo info = mExitInfos.get(i);
                    if (filterPid > 0 && info.getPid() != filterPid) {
                        continue;
                    }

                    if (result == null || result.getTimestamp() < info.getTimestamp()) {
                        result = info;
                    }
                }
                if (result != null) {
                    results.add(result);
                }
                return;
            }

            mTmpInfoList2.clear();
            if (filterPid <= 0) {
                mTmpInfoList2.addAll(mExitInfos);
            } else {
                for (int i = 0, size = mExitInfos.size(); i < size; i++) {
                    ApplicationExitInfo info = mExitInfos.get(i);
                    if (info.getPid() == filterPid) {
                        mTmpInfoList2.add(info);
                    }
                }
            }

            Collections.sort(
                    mTmpInfoList2, (a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
            if (maxNum <= 0) {
                results.addAll(mTmpInfoList2);
                return;
            }

            int elementsToRemove = mTmpInfoList2.size() - maxNum;
            for (int i = 0; i < elementsToRemove; i++) {
                mTmpInfoList2.removeLast();
            }
            results.addAll(mTmpInfoList2);
        }

        @VisibleForTesting
        @GuardedBy("mLock")
        void addExitInfoLocked(ApplicationExitInfo info) {
            // Claim the state information if there is any
            int uid = info.getPackageUid();
            // SDK sandbox app states and app traces are stored under real UID
            if (Process.isSdkSandboxUid(info.getRealUid())) {
                uid = info.getRealUid();
            }
            final int pid = info.getPid();
            if (info.getProcessStateSummary() == null) {
                info.setProcessStateSummary(findAndRemoveFromSparse2dArray(
                        mActiveAppStateSummary, uid, pid));
            }
            if (info.getTraceFile() == null) {
                info.setTraceFile(findAndRemoveFromSparse2dArray(mActiveAppTraces, uid, pid));
            }
            info.setAppTraceRetriever(mAppTraceRetriever);

            mExitInfos.add(info);
            if (mExitInfos.size() <= mMaxCapacity) {
                return;
            }

            ApplicationExitInfo oldest = null;
            for (int i = 0, size = mExitInfos.size(); i < size; i++) {
                ApplicationExitInfo info2 = mExitInfos.get(i);
                if (oldest == null || info2.getTimestamp() < oldest.getTimestamp()) {
                    oldest = info2;
                }
            }
            File traceFile = oldest.getTraceFile();
            if (traceFile != null) {
                traceFile.delete();
            }
            mExitInfos.remove(oldest);
        }

        @GuardedBy("mLock")
        ApplicationExitInfo getLastExitInfoForPid(final int pid) {
            mTmpInfoList.clear();
            getExitInfosLocked(pid, /* maxNum */ 1, mTmpInfoList);
            ApplicationExitInfo info = mTmpInfoList.size() == 0 ? null : mTmpInfoList.getFirst();
            mTmpInfoList.clear();
            return info;
        }

        @GuardedBy("mLock")
        boolean appendTraceIfNecessaryLocked(final int pid, final File traceFile) {
            final ApplicationExitInfo r = getLastExitInfoForPid(pid);
            if (r != null) {
                r.setTraceFile(traceFile);
                r.setAppTraceRetriever(mAppTraceRetriever);
                return true;
            }
            return false;
        }

        @GuardedBy("mLock")
        void destroyLocked() {
            for (int i = 0, size = mExitInfos.size(); i < size; i++) {
                ApplicationExitInfo info = mExitInfos.get(i);
                final File traceFile = info.getTraceFile();
                if (traceFile != null) {
                    traceFile.delete();
                }
                info.setTraceFile(null);
                info.setAppTraceRetriever(null);
            }
        }

        /**
         * Go through each record in an *unspecified* order, execute `callback()` on each element,
         * and potentially do some action (stopping iteration, removing the element, etc.) based on
         * the return value of the callback.
         */
        @GuardedBy("mLock")
        void forEachRecordLocked(final Function<ApplicationExitInfo, Integer> callback) {
            if (callback == null) return;
            for (int i = mExitInfos.size() - 1; i >= 0; i--) {
                ApplicationExitInfo info = mExitInfos.get(i);
                switch (callback.apply(info)) {
                    case FOREACH_ACTION_STOP_ITERATION: return;
                    case FOREACH_ACTION_REMOVE_ITEM:
                        File traceFile;
                        if ((traceFile = info.getTraceFile()) != null) {
                            traceFile.delete();
                        }
                        mExitInfos.remove(info);
                        break;
                }
            }
        }

        @GuardedBy("mLock")
        void dumpLocked(PrintWriter pw, String prefix, SimpleDateFormat sdf) {
            mTmpInfoList.clear();
            getExitInfosLocked(/* filterPid */ 0, /* maxNum */ 0, mTmpInfoList);
            for (int i = 0, size = mTmpInfoList.size(); i < size; i++) {
                mTmpInfoList.get(i).dump(pw, prefix + "  ", "#" + i, sdf);
            }
            mTmpInfoList.clear();
        }

        @GuardedBy("mLock")
        void writeToProto(ProtoOutputStream proto, long fieldId) {
            long token = proto.start(fieldId);
            proto.write(AppsExitInfoProto.Package.User.UID, mUid);
            for (int i = 0, size = mExitInfos.size(); i < size; i++) {
                mExitInfos.get(i).writeToProto(proto, AppsExitInfoProto.Package.User.APP_EXIT_INFO);
            }
            proto.end(token);
        }

        int readFromProto(ProtoInputStream proto, long fieldId)
                throws IOException, WireTypeMismatchException {
            long token = proto.start(fieldId);
            for (int next = proto.nextField();
                    next != ProtoInputStream.NO_MORE_FIELDS;
                    next = proto.nextField()) {
                switch (next) {
                    case (int) AppsExitInfoProto.Package.User.UID: {
                        mUid = proto.readInt(AppsExitInfoProto.Package.User.UID);
                        break;
                    }
                    case (int) AppsExitInfoProto.Package.User.APP_EXIT_INFO: {
                        ApplicationExitInfo info = new ApplicationExitInfo();
                        info.readFromProto(proto, AppsExitInfoProto.Package.User.APP_EXIT_INFO);
                        mExitInfos.add(info);
                        break;
                    }
                }
            }
            proto.end(token);
            return mUid;
        }
    }

    /**
     * Maintains the mapping between real UID and the application uid.
     */
    final class IsolatedUidRecords {
        /**
         * A mapping from application uid (with the userId) to isolated uids.
         */
        @GuardedBy("mLock")
        private final SparseArray<ArraySet<Integer>> mUidToIsolatedUidMap;

        /**
         * A mapping from isolated uids to application uid (with the userId)
         */
        @GuardedBy("mLock")
        private final SparseArray<Integer> mIsolatedUidToUidMap;

        IsolatedUidRecords() {
            mUidToIsolatedUidMap = new SparseArray<ArraySet<Integer>>();
            mIsolatedUidToUidMap = new SparseArray<Integer>();
        }

        void addIsolatedUid(int isolatedUid, int uid) {
            synchronized (mLock) {
                ArraySet<Integer> set = mUidToIsolatedUidMap.get(uid);
                if (set == null) {
                    set = new ArraySet<Integer>();
                    mUidToIsolatedUidMap.put(uid, set);
                }
                set.add(isolatedUid);

                mIsolatedUidToUidMap.put(isolatedUid, uid);
            }
        }

        void removeIsolatedUid(int isolatedUid, int uid) {
            synchronized (mLock) {
                final int index = mUidToIsolatedUidMap.indexOfKey(uid);
                if (index >= 0) {
                    final ArraySet<Integer> set = mUidToIsolatedUidMap.valueAt(index);
                    set.remove(isolatedUid);
                    if (set.isEmpty()) {
                        mUidToIsolatedUidMap.removeAt(index);
                    }
                }
                mIsolatedUidToUidMap.remove(isolatedUid);
            }
        }

        @GuardedBy("mLock")
        Integer getUidByIsolatedUid(int isolatedUid) {
            if (UserHandle.isIsolated(isolatedUid)) {
                synchronized (mLock) {
                    return mIsolatedUidToUidMap.get(isolatedUid);
                }
            }
            return isolatedUid;
        }

        @GuardedBy("mLock")
        private void removeAppUidLocked(int uid) {
            ArraySet<Integer> set = mUidToIsolatedUidMap.get(uid);
            if (set != null) {
                for (int i = set.size() - 1; i >= 0; i--) {
                    int isolatedUid = set.removeAt(i);
                    mIsolatedUidToUidMap.remove(isolatedUid);
                }
            }
        }

        @VisibleForTesting
        void removeAppUid(int uid, boolean allUsers) {
            synchronized (mLock) {
                if (allUsers) {
                    uid = UserHandle.getAppId(uid);
                    for (int i = mUidToIsolatedUidMap.size() - 1; i >= 0; i--) {
                        int u = mUidToIsolatedUidMap.keyAt(i);
                        if (uid == UserHandle.getAppId(u)) {
                            removeAppUidLocked(u);
                        }
                        mUidToIsolatedUidMap.removeAt(i);
                    }
                } else {
                    removeAppUidLocked(uid);
                    mUidToIsolatedUidMap.remove(uid);
                }
            }
        }

        @GuardedBy("mLock")
        int removeIsolatedUidLocked(int isolatedUid) {
            if (!UserHandle.isIsolated(isolatedUid)) {
                return isolatedUid;
            }
            int uid = mIsolatedUidToUidMap.get(isolatedUid, -1);
            if (uid == -1) {
                return isolatedUid;
            }
            mIsolatedUidToUidMap.remove(isolatedUid);
            ArraySet<Integer> set = mUidToIsolatedUidMap.get(uid);
            if (set != null) {
                set.remove(isolatedUid);
            }
            // let the ArraySet stay in the mUidToIsolatedUidMap even if it's empty
            return uid;
        }

        void removeByUserId(int userId) {
            if (userId == UserHandle.USER_CURRENT) {
                userId = mService.mUserController.getCurrentUserId();
            }
            synchronized (mLock) {
                if (userId == UserHandle.USER_ALL) {
                    mIsolatedUidToUidMap.clear();
                    mUidToIsolatedUidMap.clear();
                    return;
                }
                for (int i = mIsolatedUidToUidMap.size() - 1; i >= 0; i--) {
                    int isolatedUid = mIsolatedUidToUidMap.keyAt(i);
                    int uid = mIsolatedUidToUidMap.valueAt(i);
                    if (UserHandle.getUserId(uid) == userId) {
                        mIsolatedUidToUidMap.removeAt(i);
                        mUidToIsolatedUidMap.remove(uid);
                    }
                }
            }
        }
    }

    final class KillHandler extends Handler {
        static final int MSG_LMKD_PROC_KILLED = 4101;
        static final int MSG_CHILD_PROC_DIED = 4102;
        static final int MSG_PROC_DIED = 4103;
        static final int MSG_APP_KILL = 4104;
        static final int MSG_STATSD_LOG = 4105;
        static final int MSG_APP_RECOVERABLE_CRASH = 4106;

        KillHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_LMKD_PROC_KILLED:
                    mAppExitInfoSourceLmkd.onProcDied(msg.arg1 /* pid */, msg.arg2 /* uid */,
                            null /* status */, (Long) msg.obj /* rss_kb */);
                    break;
                case MSG_CHILD_PROC_DIED:
                    mAppExitInfoSourceZygote.onProcDied(msg.arg1 /* pid */, msg.arg2 /* uid */,
                            (Integer) msg.obj /* status */, null /* rss_kb */);
                    break;
                case MSG_PROC_DIED: {
                    ApplicationExitInfo raw = (ApplicationExitInfo) msg.obj;
                    synchronized (mLock) {
                        handleNoteProcessDiedLocked(raw);
                    }
                    recycleRawRecord(raw);
                }
                break;
                case MSG_APP_KILL: {
                    ApplicationExitInfo raw = (ApplicationExitInfo) msg.obj;
                    synchronized (mLock) {
                        handleNoteAppKillLocked(raw);
                    }
                    recycleRawRecord(raw);
                }
                break;
                case MSG_STATSD_LOG: {
                    synchronized (mLock) {
                        performLogToStatsdLocked((ApplicationExitInfo) msg.obj);
                    }
                }
                break;
                case MSG_APP_RECOVERABLE_CRASH: {
                    ApplicationExitInfo raw = (ApplicationExitInfo) msg.obj;
                    synchronized (mLock) {
                        // Unlike MSG_APP_KILL, this is a recoverable crash, and
                        // so we want to bypass the statsd app-kill logging.
                        // Hence, call `addExitInfoLocked()` directly instead of
                        // `handleNoteAppKillLocked()`.
                        addExitInfoLocked(raw);
                    }
                    recycleRawRecord(raw);
                }
                break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    @VisibleForTesting
    boolean isFresh(long timestamp) {
        // A process could be dying but being stuck in some state, i.e.,
        // being TRACED by tombstoned, thus the zygote receives SIGCHILD
        // way after we already knew the kill (maybe because we did the kill :P),
        // so here check if the last known kill information is "fresh" enough.
        long now = System.currentTimeMillis();

        return (timestamp + AppExitInfoExternalSource.APP_EXIT_INFO_FRESHNESS_MS) >= now;
    }

    /**
     * Keep the raw information about app kills from external sources, i.e., lmkd
     */
    final class AppExitInfoExternalSource {
        private static final long APP_EXIT_INFO_FRESHNESS_MS = 300 * 1000;

        /**
         * A mapping between uid -> pid -> {timestamp, extra info(Nullable)}.
         * The uid here is the application uid, not the isolated uid.
         */
        @GuardedBy("mLock")
        private final SparseArray<SparseArray<Pair<Long, Object>>> mData;

        /** A tag for logging only */
        private final String mTag;

        /** A preset reason in case a proc dies */
        private final Integer mPresetReason;

        /** A callback that will be notified when a proc dies */
        private BiConsumer<Integer, Integer> mProcDiedListener;

        AppExitInfoExternalSource(String tag, Integer reason) {
            mData = new SparseArray<SparseArray<Pair<Long, Object>>>();
            mTag = tag;
            mPresetReason = reason;
        }

        @GuardedBy("mLock")
        private void addLocked(int pid, int uid, Object extra) {
            Integer k = mIsolatedUidRecords.getUidByIsolatedUid(uid);
            if (k != null) {
                uid = k;
            }

            SparseArray<Pair<Long, Object>> array = mData.get(uid);
            if (array == null) {
                array = new SparseArray<Pair<Long, Object>>();
                mData.put(uid, array);
            }
            array.put(pid, new Pair<Long, Object>(System.currentTimeMillis(), extra));
        }

        @VisibleForTesting
        Pair<Long, Object> remove(int pid, int uid) {
            synchronized (mLock) {
                Integer k = mIsolatedUidRecords.getUidByIsolatedUid(uid);
                if (k != null) {
                    uid = k;
                }

                SparseArray<Pair<Long, Object>> array = mData.get(uid);
                if (array != null) {
                    Pair<Long, Object> p = array.get(pid);
                    if (p != null) {
                        array.remove(pid);
                        return isFresh(p.first) ? p : null;
                    }
                }
                return null;
            }
        }

        void removeByUserId(int userId) {
            if (userId == UserHandle.USER_CURRENT) {
                userId = mService.mUserController.getCurrentUserId();
            }
            synchronized (mLock) {
                if (userId == UserHandle.USER_ALL) {
                    mData.clear();
                    return;
                }
                for (int i = mData.size() - 1; i >= 0; i--) {
                    int uid = mData.keyAt(i);
                    if (UserHandle.getUserId(uid) == userId) {
                        mData.removeAt(i);
                    }
                }
            }
        }

        @GuardedBy("mLock")
        void removeByUidLocked(int uid, boolean allUsers) {
            if (UserHandle.isIsolated(uid)) {
                Integer k = mIsolatedUidRecords.getUidByIsolatedUid(uid);
                if (k != null) {
                    uid = k;
                }
            }

            if (allUsers) {
                uid = UserHandle.getAppId(uid);
                for (int i = mData.size() - 1; i >= 0; i--) {
                    if (UserHandle.getAppId(mData.keyAt(i)) == uid) {
                        mData.removeAt(i);
                    }
                }
            } else {
                mData.remove(uid);
            }
        }

        void setOnProcDiedListener(BiConsumer<Integer, Integer> listener) {
            synchronized (mLock) {
                mProcDiedListener = listener;
            }
        }

        void onProcDied(final int pid, final int uid, final Integer status, final Long rssKb) {
            if (DEBUG_PROCESSES) {
                Slog.i(TAG, mTag + ": proc died: pid=" + pid + " uid=" + uid
                        + ", status=" + status);
            }

            if (mService == null) {
                return;
            }

            // Unlikely but possible: the record has been created
            // Let's update it if we could find a ApplicationExitInfo record
            synchronized (mLock) {
                if (!updateExitInfoIfNecessaryLocked(pid, uid, status, mPresetReason, rssKb)) {
                    if (rssKb != null) {
                        addLocked(pid, uid, rssKb);     // lmkd
                    } else {
                        addLocked(pid, uid, status);    // zygote
                    }
                }

                // Notify any interesed party regarding the lmkd kills
                final BiConsumer<Integer, Integer> listener = mProcDiedListener;
                if (listener != null) {
                    mService.mHandler.post(()-> listener.accept(pid, uid));
                }
            }
        }
    }

    /**
     * The implementation to the IAppTraceRetriever interface.
     */
    @VisibleForTesting
    class AppTraceRetriever extends IAppTraceRetriever.Stub {
        @Override
        public ParcelFileDescriptor getTraceFileDescriptor(final String packageName,
                final int uid, final int pid) {
            mService.enforceNotIsolatedCaller("getTraceFileDescriptor");

            if (TextUtils.isEmpty(packageName)) {
                throw new IllegalArgumentException("Invalid package name");
            }
            final int callingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();
            final int userId = UserHandle.getUserId(uid);

            mService.mUserController.handleIncomingUser(callingPid, callingUid, userId, true,
                    ALLOW_NON_FULL, "getTraceFileDescriptor", null);
            final int filterUid = mService.enforceDumpPermissionForPackage(packageName, userId,
                    callingUid, "getTraceFileDescriptor");
            if (filterUid != Process.INVALID_UID) {
                synchronized (mLock) {
                    final ApplicationExitInfo info = getExitInfoLocked(packageName, filterUid, pid);
                    if (info == null) {
                        return null;
                    }
                    final File traceFile = info.getTraceFile();
                    if (traceFile == null) {
                        return null;
                    }
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        // The fd will be closed after being written into Parcel
                        return ParcelFileDescriptor.open(traceFile,
                                ParcelFileDescriptor.MODE_READ_ONLY);
                    } catch (FileNotFoundException e) {
                        return null;
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
            }
            return null;
        }
    }
}
