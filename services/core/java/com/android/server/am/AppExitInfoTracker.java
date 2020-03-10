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
import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PROCESSES;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.app.ApplicationExitInfo;
import android.app.ApplicationExitInfo.Reason;
import android.app.ApplicationExitInfo.SubReason;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.icu.text.SimpleDateFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
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
import com.android.server.IoThread;
import com.android.server.ServiceThread;
import com.android.server.SystemServiceManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * A class to manage all the {@link android.app.ApplicationExitInfo} records.
 */
public final class AppExitInfoTracker {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppExitInfoTracker" : TAG_AM;

    /**
     * Interval of persisting the app exit info to persistent storage.
     */
    private static final long APP_EXIT_INFO_PERSIST_INTERVAL = TimeUnit.MINUTES.toMillis(30);

    /** These are actions that the forEachPackage should take after each iteration */
    private static final int FOREACH_ACTION_NONE = 0;
    private static final int FOREACH_ACTION_REMOVE_PACKAGE = 1;
    private static final int FOREACH_ACTION_STOP_ITERATION = 2;

    private static final int APP_EXIT_RAW_INFO_POOL_SIZE = 8;

    @VisibleForTesting
    static final String APP_EXIT_INFO_FILE = "procexitinfo";

    private final Object mLock = new Object();

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
    @GuardedBy("mLock")
    boolean mAppExitInfoLoaded = false;

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
        mProcExitInfoFile = new File(SystemServiceManager.ensureSystemDir(), APP_EXIT_INFO_FILE);

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

        synchronized (mLock) {
            if (!mAppExitInfoLoaded) {
                return;
            }
            mKillHandler.obtainMessage(KillHandler.MSG_PROC_DIED, obtainRawRecordLocked(app))
                    .sendToTarget();
        }
    }

    void scheduleNoteAppKill(final ProcessRecord app, final @Reason int reason,
            final @SubReason int subReason, final String msg) {
        synchronized (mLock) {
            if (!mAppExitInfoLoaded) {
                return;
            }
            if (app == null || app.info == null) {
                return;
            }

            ApplicationExitInfo raw = obtainRawRecordLocked(app);
            raw.setReason(reason);
            raw.setSubReason(subReason);
            raw.setDescription(msg);
            mKillHandler.obtainMessage(KillHandler.MSG_APP_KILL, raw).sendToTarget();
        }
    }

    void scheduleNoteAppKill(final int pid, final int uid, final @Reason int reason,
            final @SubReason int subReason, final String msg) {
        synchronized (mLock) {
            if (!mAppExitInfoLoaded) {
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
    void scheduleNoteLmkdProcKilled(final int pid, final int uid) {
        mKillHandler.obtainMessage(KillHandler.MSG_LMKD_PROC_KILLED, pid, uid)
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

            ApplicationExitInfo info = getExitInfo(raw.getPackageName(),
                    raw.getPackageUid(), raw.getPid());

            // query zygote and lmkd to get the exit info, and clear the saved info
            Pair<Long, Object> zygote = mAppExitInfoSourceZygote.remove(
                    raw.getPid(), raw.getRealUid());
            Pair<Long, Object> lmkd = mAppExitInfoSourceLmkd.remove(
                    raw.getPid(), raw.getRealUid());
            mIsolatedUidRecords.removeIsolatedUid(raw.getRealUid());

            if (info == null) {
                info = addExitInfoLocked(raw);
            }

            if (lmkd != null) {
                updateExistingExitInfoRecordLocked(info, null,
                        ApplicationExitInfo.REASON_LOW_MEMORY);
            } else if (zygote != null) {
                updateExistingExitInfoRecordLocked(info, (Integer) zygote.second, null);
            }
        }
    }

    /**
     * Make note when ActivityManagerService decides to kill an application process.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    void handleNoteAppKillLocked(final ApplicationExitInfo raw) {
        ApplicationExitInfo info = getExitInfo(
                raw.getPackageName(), raw.getPackageUid(), raw.getPid());

        if (info == null) {
            addExitInfoLocked(raw);
        } else {
            // always override the existing info since we are now more informational.
            info.setReason(raw.getReason());
            info.setSubReason(raw.getSubReason());
            info.setStatus(0);
            info.setTimestamp(System.currentTimeMillis());
            info.setDescription(raw.getDescription());
        }
    }

    @GuardedBy("mLock")
    private ApplicationExitInfo addExitInfoLocked(ApplicationExitInfo raw) {
        if (!mAppExitInfoLoaded) {
            Slog.w(TAG, "Skipping saving the exit info due to ongoing loading from storage");
            return null;
        }

        final ApplicationExitInfo info = new ApplicationExitInfo(raw);
        final String[] packages = raw.getPackageList();
        final int uid = raw.getPackageUid();
        for (int i = 0; i < packages.length; i++) {
            addExitInfoInner(packages[i], uid, info);
        }

        schedulePersistProcessExitInfo(false);

        return info;
    }

    /**
     * Update an existing {@link android.app.ApplicationExitInfo} record with given information.
     */
    @GuardedBy("mLock")
    private void updateExistingExitInfoRecordLocked(ApplicationExitInfo info,
            Integer status, Integer reason) {
        if (info == null || !isFresh(info.getTimestamp())) {
            // if the record is way outdated, don't update it then (because of potential pid reuse)
            return;
        }
        if (status != null) {
            if (OsConstants.WIFEXITED(status)) {
                info.setReason(ApplicationExitInfo.REASON_EXIT_SELF);
                info.setStatus(OsConstants.WEXITSTATUS(status));
            } else if (OsConstants.WIFSIGNALED(status)) {
                if (info.getReason() == ApplicationExitInfo.REASON_UNKNOWN) {
                    info.setReason(ApplicationExitInfo.REASON_SIGNALED);
                    info.setStatus(OsConstants.WTERMSIG(status));
                } else if (info.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    info.setStatus(OsConstants.WTERMSIG(status));
                }
            }
        }
        if (reason != null) {
            info.setReason(reason);
        }
    }

    /**
     * Update an existing {@link android.app.ApplicationExitInfo} record with given information.
     *
     * @return true if a recond is updated
     */
    private boolean updateExitInfoIfNecessary(int pid, int uid, Integer status, Integer reason) {
        synchronized (mLock) {
            if (UserHandle.isIsolated(uid)) {
                Integer k = mIsolatedUidRecords.getUidByIsolatedUid(uid);
                if (k != null) {
                    uid = k;
                }
            }
            ArrayList<ApplicationExitInfo> tlist = mTmpInfoList;
            tlist.clear();
            final int targetUid = uid;
            forEachPackage((packageName, records) -> {
                AppExitInfoContainer container = records.get(targetUid);
                if (container == null) {
                    return FOREACH_ACTION_NONE;
                }
                tlist.clear();
                container.getExitInfoLocked(pid, 1, tlist);
                if (tlist.size() == 0) {
                    return FOREACH_ACTION_NONE;
                }
                ApplicationExitInfo info = tlist.get(0);
                if (info.getRealUid() != targetUid) {
                    tlist.clear();
                    return FOREACH_ACTION_NONE;
                }
                // Okay found it, update its reason.
                updateExistingExitInfoRecordLocked(info, status, reason);

                return FOREACH_ACTION_STOP_ITERATION;
            });
            return tlist.size() > 0;
        }
    }

    /**
     * Get the exit info with matching package name, filterUid and filterPid (if > 0)
     */
    @VisibleForTesting
    void getExitInfo(final String packageName, final int filterUid,
            final int filterPid, final int maxNum, final ArrayList<ApplicationExitInfo> results) {
        synchronized (mLock) {
            boolean emptyPackageName = TextUtils.isEmpty(packageName);
            if (!emptyPackageName) {
                // fast path
                AppExitInfoContainer container = mData.get(packageName, filterUid);
                if (container != null) {
                    container.getExitInfoLocked(filterPid, maxNum, results);
                }
            } else {
                // slow path
                final ArrayList<ApplicationExitInfo> list = mTmpInfoList2;
                list.clear();
                // get all packages
                forEachPackage((name, records) -> {
                    AppExitInfoContainer container = records.get(filterUid);
                    if (container != null) {
                        mTmpInfoList.clear();
                        results.addAll(container.toListLocked(mTmpInfoList, filterPid));
                    }
                    return AppExitInfoTracker.FOREACH_ACTION_NONE;
                });

                Collections.sort(list, (a, b) -> (int) (b.getTimestamp() - a.getTimestamp()));
                int size = list.size();
                if (maxNum > 0) {
                    size = Math.min(size, maxNum);
                }
                for (int i = 0; i < size; i++) {
                    results.add(list.get(i));
                }
                list.clear();
            }
        }
    }

    /**
     * Return the first matching exit info record, for internal use, the parameters are not supposed
     * to be empty.
     */
    private ApplicationExitInfo getExitInfo(final String packageName,
            final int filterUid, final int filterPid) {
        synchronized (mLock) {
            ArrayList<ApplicationExitInfo> list = mTmpInfoList;
            list.clear();
            getExitInfo(packageName, filterUid, filterPid, 1, list);

            ApplicationExitInfo info = list.size() > 0 ? list.get(0) : null;
            list.clear();
            return info;
        }
    }

    @VisibleForTesting
    void onUserRemoved(int userId) {
        mAppExitInfoSourceZygote.removeByUserId(userId);
        mAppExitInfoSourceLmkd.removeByUserId(userId);
        mIsolatedUidRecords.removeByUserId(userId);
        removeByUserId(userId);
        schedulePersistProcessExitInfo(true);
    }

    @VisibleForTesting
    void onPackageRemoved(String packageName, int uid, boolean allUsers) {
        if (packageName != null) {
            final boolean removeUid = TextUtils.isEmpty(
                    mService.mPackageManagerInt.getNameForUid(uid));
            if (removeUid) {
                mAppExitInfoSourceZygote.removeByUid(uid, allUsers);
                mAppExitInfoSourceLmkd.removeByUid(uid, allUsers);
                mIsolatedUidRecords.removeAppUid(uid, allUsers);
            }
            removePackage(packageName, allUsers ? UserHandle.USER_ALL : UserHandle.getUserId(uid));
            schedulePersistProcessExitInfo(true);
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
            synchronized (mLock) {
                mAppExitInfoLoaded = true;
            }
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
        } catch (IOException | IllegalArgumentException | WireTypeMismatchException e) {
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
            mAppExitInfoLoaded = true;
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
                forEachPackage((packageName, records) -> {
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
        }
    }

    /**
     * Helper function for shell command
     */
    void clearHistoryProcessExitInfo(String packageName, int userId) {
        synchronized (mLock) {
            if (TextUtils.isEmpty(packageName)) {
                if (userId == UserHandle.USER_ALL) {
                    mData.getMap().clear();
                } else {
                    removeByUserId(userId);
                }
            } else {
                removePackage(packageName, userId);
            }
        }
        schedulePersistProcessExitInfo(true);
    }

    void dumpHistoryProcessExitInfo(PrintWriter pw, String packageName) {
        pw.println("ACTIVITY MANAGER LRU PROCESSES (dumpsys activity exit-info)");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        synchronized (mLock) {
            pw.println("Last Timestamp of Persistence Into Persistent Storage: "
                    + sdf.format(new Date(mLastAppExitInfoPersistTimestamp)));
            if (TextUtils.isEmpty(packageName)) {
                forEachPackage((name, records) -> {
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
            pw.println(prefix + "  Historical Process Exit for userId=" + array.keyAt(i));
            array.valueAt(i).dumpLocked(pw, prefix + "    ", sdf);
        }
    }

    private void addExitInfoInner(String packageName, int userId, ApplicationExitInfo info) {
        synchronized (mLock) {
            AppExitInfoContainer container = mData.get(packageName, userId);
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
                mData.put(packageName, userId, container);
            }
            container.addExitInfoLocked(info);
        }
    }

    private void forEachPackage(
            BiFunction<String, SparseArray<AppExitInfoContainer>, Integer> callback) {
        if (callback != null) {
            synchronized (mLock) {
                ArrayMap<String, SparseArray<AppExitInfoContainer>> map = mData.getMap();
                for (int i = map.size() - 1; i >= 0; i--) {
                    switch (callback.apply(map.keyAt(i), map.valueAt(i))) {
                        case FOREACH_ACTION_REMOVE_PACKAGE:
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
    }

    private void removePackage(String packageName, int userId) {
        synchronized (mLock) {
            if (userId == UserHandle.USER_ALL) {
                mData.getMap().remove(packageName);
            } else {
                ArrayMap<String, SparseArray<AppExitInfoContainer>> map =
                        mData.getMap();
                SparseArray<AppExitInfoContainer> array = map.get(packageName);
                if (array == null) {
                    return;
                }
                for (int i = array.size() - 1; i >= 0; i--) {
                    if (UserHandle.getUserId(array.keyAt(i)) == userId) {
                        array.removeAt(i);
                        break;
                    }
                }
                if (array.size() == 0) {
                    map.remove(packageName);
                }
            }
        }
    }

    private void removeByUserId(final int userId) {
        if (userId == UserHandle.USER_ALL) {
            synchronized (mLock) {
                mData.getMap().clear();
            }
            return;
        }
        forEachPackage((packageName, records) -> {
            for (int i = records.size() - 1; i >= 0; i--) {
                if (UserHandle.getUserId(records.keyAt(i)) == userId) {
                    records.removeAt(i);
                    break;
                }
            }
            return records.size() == 0 ? FOREACH_ACTION_REMOVE_PACKAGE : FOREACH_ACTION_NONE;
        });
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    ApplicationExitInfo obtainRawRecordLocked(ProcessRecord app) {
        ApplicationExitInfo info = mRawRecordsPool.acquire();
        if (info == null) {
            info = new ApplicationExitInfo();
        }

        final int definingUid = app.hostingRecord != null ? app.hostingRecord.getDefiningUid() : 0;
        info.setPid(app.pid);
        info.setRealUid(app.uid);
        info.setPackageUid(app.info.uid);
        info.setDefiningUid(definingUid > 0 ? definingUid : app.info.uid);
        info.setProcessName(app.processName);
        info.setConnectionGroup(app.connectionGroup);
        info.setPackageName(app.info.packageName);
        info.setPackageList(app.getPackageList());
        info.setReason(ApplicationExitInfo.REASON_UNKNOWN);
        info.setStatus(0);
        info.setImportance(procStateToImportance(app.setProcState));
        info.setPss(app.lastPss);
        info.setRss(app.mLastRss);
        info.setTimestamp(System.currentTimeMillis());

        return info;
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    void recycleRawRecordLocked(ApplicationExitInfo info) {
        info.setProcessName(null);
        info.setDescription(null);
        info.setPackageList(null);

        mRawRecordsPool.release(info);
    }

    /**
     * A container class of {@link android.app.ApplicationExitInfo}
     */
    final class AppExitInfoContainer {
        private SparseArray<ApplicationExitInfo> mInfos; // index is pid
        private int mMaxCapacity;
        private int mUid; // Application uid, not isolated uid.

        AppExitInfoContainer(final int maxCapacity) {
            mInfos = new SparseArray<ApplicationExitInfo>();
            mMaxCapacity = maxCapacity;
        }

        @GuardedBy("mLock")
        void getExitInfoLocked(final int filterPid, final int maxNum,
                ArrayList<ApplicationExitInfo> results) {
            if (filterPid > 0) {
                ApplicationExitInfo r = mInfos.get(filterPid);
                if (r != null) {
                    results.add(r);
                }
            } else {
                final int numRep = mInfos.size();
                if (maxNum <= 0 || numRep <= maxNum) {
                    // Return all records.
                    for (int i = 0; i < numRep; i++) {
                        results.add(mInfos.valueAt(i));
                    }
                    Collections.sort(results,
                            (a, b) -> (int) (b.getTimestamp() - a.getTimestamp()));
                } else {
                    if (maxNum == 1) {
                        // Most of the caller might be only interested with the most recent one
                        ApplicationExitInfo r = mInfos.valueAt(0);
                        for (int i = 1; i < numRep; i++) {
                            ApplicationExitInfo t = mInfos.valueAt(i);
                            if (r.getTimestamp() < t.getTimestamp()) {
                                r = t;
                            }
                        }
                        results.add(r);
                    } else {
                        // Huh, need to sort it out then.
                        ArrayList<ApplicationExitInfo> list = mTmpInfoList2;
                        list.clear();
                        for (int i = 0; i < numRep; i++) {
                            list.add(mInfos.valueAt(i));
                        }
                        Collections.sort(list,
                                (a, b) -> (int) (b.getTimestamp() - a.getTimestamp()));
                        for (int i = 0; i < maxNum; i++) {
                            results.add(list.get(i));
                        }
                        list.clear();
                    }
                }
            }
        }

        @GuardedBy("mLock")
        void addExitInfoLocked(ApplicationExitInfo info) {
            int size;
            if ((size = mInfos.size()) >= mMaxCapacity) {
                int oldestIndex = -1;
                long oldestTimeStamp = Long.MAX_VALUE;
                for (int i = 0; i < size; i++) {
                    ApplicationExitInfo r = mInfos.valueAt(i);
                    if (r.getTimestamp() < oldestTimeStamp) {
                        oldestTimeStamp = r.getTimestamp();
                        oldestIndex = i;
                    }
                }
                if (oldestIndex >= 0) {
                    mInfos.removeAt(oldestIndex);
                }
            }
            mInfos.append(info.getPid(), info);
        }

        @GuardedBy("mLock")
        void dumpLocked(PrintWriter pw, String prefix, SimpleDateFormat sdf) {
            ArrayList<ApplicationExitInfo> list = new ArrayList<ApplicationExitInfo>();
            for (int i = mInfos.size() - 1; i >= 0; i--) {
                list.add(mInfos.valueAt(i));
            }
            Collections.sort(list, (a, b) -> (int) (b.getTimestamp() - a.getTimestamp()));
            int size = list.size();
            for (int i = 0; i < size; i++) {
                list.get(i).dump(pw, prefix + "  ", "#" + i, sdf);
            }
        }

        @GuardedBy("mLock")
        void writeToProto(ProtoOutputStream proto, long fieldId) {
            long token = proto.start(fieldId);
            proto.write(AppsExitInfoProto.Package.User.UID, mUid);
            int size = mInfos.size();
            for (int i = 0; i < size; i++) {
                mInfos.valueAt(i).writeToProto(proto, AppsExitInfoProto.Package.User.APP_EXIT_INFO);
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
                    case (int) AppsExitInfoProto.Package.User.UID:
                        mUid = proto.readInt(AppsExitInfoProto.Package.User.UID);
                        break;
                    case (int) AppsExitInfoProto.Package.User.APP_EXIT_INFO:
                        ApplicationExitInfo info = new ApplicationExitInfo();
                        info.readFromProto(proto, AppsExitInfoProto.Package.User.APP_EXIT_INFO);
                        mInfos.put(info.getPid(), info);
                        break;
                }
            }
            proto.end(token);
            return mUid;
        }

        @GuardedBy("mLock")
        List<ApplicationExitInfo> toListLocked(List<ApplicationExitInfo> list, int filterPid) {
            if (list == null) {
                list = new ArrayList<ApplicationExitInfo>();
            }
            for (int i = mInfos.size() - 1; i >= 0; i--) {
                if (filterPid == 0 || filterPid == mInfos.keyAt(i)) {
                    list.add(mInfos.valueAt(i));
                }
            }
            return list;
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

        int removeIsolatedUid(int isolatedUid) {
            if (!UserHandle.isIsolated(isolatedUid)) {
                return isolatedUid;
            }
            synchronized (mLock) {
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

        KillHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_LMKD_PROC_KILLED:
                    mAppExitInfoSourceLmkd.onProcDied(msg.arg1 /* pid */, msg.arg2 /* uid */,
                            null /* status */);
                    break;
                case MSG_CHILD_PROC_DIED:
                    mAppExitInfoSourceZygote.onProcDied(msg.arg1 /* pid */, msg.arg2 /* uid */,
                            (Integer) msg.obj /* status */);
                    break;
                case MSG_PROC_DIED: {
                    ApplicationExitInfo raw = (ApplicationExitInfo) msg.obj;
                    synchronized (mLock) {
                        handleNoteProcessDiedLocked(raw);
                        recycleRawRecordLocked(raw);
                    }
                }
                break;
                case MSG_APP_KILL: {
                    ApplicationExitInfo raw = (ApplicationExitInfo) msg.obj;
                    synchronized (mLock) {
                        handleNoteAppKillLocked(raw);
                        recycleRawRecordLocked(raw);
                    }
                }
                break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    private static boolean isFresh(long timestamp) {
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

        void add(int pid, int uid, Object extra) {
            if (UserHandle.isIsolated(uid)) {
                Integer k = mIsolatedUidRecords.getUidByIsolatedUid(uid);
                if (k != null) {
                    uid = k;
                }
            }

            synchronized (mLock) {
                SparseArray<Pair<Long, Object>> array = mData.get(uid);
                if (array == null) {
                    array = new SparseArray<Pair<Long, Object>>();
                    mData.put(uid, array);
                }
                array.put(pid, new Pair<Long, Object>(System.currentTimeMillis(), extra));
            }
        }

        Pair<Long, Object> remove(int pid, int uid) {
            if (UserHandle.isIsolated(uid)) {
                Integer k = mIsolatedUidRecords.getUidByIsolatedUid(uid);
                if (k != null) {
                    uid = k;
                }
            }

            synchronized (mLock) {
                SparseArray<Pair<Long, Object>> array = mData.get(uid);
                if (array != null) {
                    Pair<Long, Object> p = array.get(pid);
                    if (p != null) {
                        array.remove(pid);
                        return isFresh(p.first) ? p : null;
                    }
                }
            }
            return null;
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

        void removeByUid(int uid, boolean allUsers) {
            if (UserHandle.isIsolated(uid)) {
                Integer k = mIsolatedUidRecords.getUidByIsolatedUid(uid);
                if (k != null) {
                    uid = k;
                }
            }

            if (allUsers) {
                uid = UserHandle.getAppId(uid);
                synchronized (mLock) {
                    for (int i = mData.size() - 1; i >= 0; i--) {
                        if (UserHandle.getAppId(mData.keyAt(i)) == uid) {
                            mData.removeAt(i);
                        }
                    }
                }
            } else {
                synchronized (mLock) {
                    mData.remove(uid);
                }
            }
        }

        void setOnProcDiedListener(BiConsumer<Integer, Integer> listener) {
            synchronized (mLock) {
                mProcDiedListener = listener;
            }
        }

        void onProcDied(final int pid, final int uid, final Integer status) {
            if (DEBUG_PROCESSES) {
                Slog.i(TAG, mTag + ": proc died: pid=" + pid + " uid=" + uid
                        + ", status=" + status);
            }

            if (mService == null) {
                return;
            }

            // Unlikely but possible: the record has been created
            // Let's update it if we could find a ApplicationExitInfo record
            if (!updateExitInfoIfNecessary(pid, uid, status, mPresetReason)) {
                add(pid, uid, status);
            }

            // Notify any interesed party regarding the lmkd kills
            synchronized (mLock) {
                final BiConsumer<Integer, Integer> listener = mProcDiedListener;
                if (listener != null) {
                    mService.mHandler.post(()-> listener.accept(pid, uid));
                }
            }
        }
    }
}
