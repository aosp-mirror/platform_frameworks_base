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

import static android.app.ApplicationStartInfo.START_TIMESTAMP_LAUNCH;
import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.NonNull;
import android.app.ApplicationStartInfo;
import android.app.Flags;
import android.app.IApplicationStartInfoCompleteListener;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.icu.text.SimpleDateFormat;
import android.os.Binder;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
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
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

/** A class to manage all the {@link android.app.ApplicationStartInfo} records. */
public final class AppStartInfoTracker {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppStartInfoTracker" : TAG_AM;

    /** Interval of persisting the app start info to persistent storage. */
    private static final long APP_START_INFO_PERSIST_INTERVAL = TimeUnit.MINUTES.toMillis(30);

    /** These are actions that the forEach* should take after each iteration */
    private static final int FOREACH_ACTION_NONE = 0;
    private static final int FOREACH_ACTION_REMOVE_ITEM = 1;
    private static final int FOREACH_ACTION_STOP_ITERATION = 2;

    @VisibleForTesting static final int APP_START_INFO_HISTORY_LIST_SIZE = 16;

    @VisibleForTesting static final String APP_START_STORE_DIR = "procstartstore";

    @VisibleForTesting static final String APP_START_INFO_FILE = "procstartinfo";

    @VisibleForTesting final Object mLock = new Object();

    @VisibleForTesting boolean mEnabled = false;

    /** Initialized in {@link #init} and read-only after that. */
    @VisibleForTesting ActivityManagerService mService;

    /** Initialized in {@link #init} and read-only after that. */
    private Handler mHandler;

    /** The task to persist app process start info */
    @GuardedBy("mLock")
    private Runnable mAppStartInfoPersistTask = null;

    /**
     * Last time(in ms) since epoch that the app start info was persisted into persistent storage.
     */
    @GuardedBy("mLock")
    private long mLastAppStartInfoPersistTimestamp = 0L;

    /**
     * Retention policy: keep up to X historical start info per package.
     *
     * <p>Initialized in {@link #init} and read-only after that. No lock is needed.
     */
    @VisibleForTesting int mAppStartInfoHistoryListSize;

    @GuardedBy("mLock")
    private final ProcessMap<AppStartInfoContainer> mData;

    /** UID as key. */
    @GuardedBy("mLock")
    private final SparseArray<ArrayList<ApplicationStartInfoCompleteCallback>> mCallbacks;

    /**
     * Whether or not we've loaded the historical app process start info from persistent storage.
     */
    @VisibleForTesting AtomicBoolean mAppStartInfoLoaded = new AtomicBoolean();

    /** Temporary list being used to filter/sort intermediate results in {@link #getStartInfo}. */
    @GuardedBy("mLock")
    final ArrayList<ApplicationStartInfo> mTmpStartInfoList = new ArrayList<>();

    /**
     * The path to the directory which includes the historical proc start info file as specified in
     * {@link #mProcStartInfoFile}.
     */
    @VisibleForTesting File mProcStartStoreDir;

    /** The path to the historical proc start info file, persisted in the storage. */
    @VisibleForTesting File mProcStartInfoFile;


    /**
     * Temporary list of records that have not been completed.
     *
     * Key is timestamp of launch from {@link #ActivityMetricsLaunchObserver}.
     */
    @GuardedBy("mLock")
    @VisibleForTesting
    final ArrayMap<Long, ApplicationStartInfo> mInProgRecords = new ArrayMap<>();

    AppStartInfoTracker() {
        mCallbacks = new SparseArray<>();
        mData = new ProcessMap<AppStartInfoContainer>();
    }

    void init(ActivityManagerService service) {
        mService = service;

        ServiceThread thread =
                new ServiceThread(TAG + ":handler", THREAD_PRIORITY_BACKGROUND, true /* allowIo */);
        thread.start();
        mHandler = new Handler(thread.getLooper());

        mProcStartStoreDir = new File(SystemServiceManager.ensureSystemDir(), APP_START_STORE_DIR);
        if (!FileUtils.createDir(mProcStartStoreDir)) {
            Slog.e(TAG, "Unable to create " + mProcStartStoreDir);
            return;
        }
        mProcStartInfoFile = new File(mProcStartStoreDir, APP_START_INFO_FILE);

        mAppStartInfoHistoryListSize = APP_START_INFO_HISTORY_LIST_SIZE;
    }

    void onSystemReady() {
        mEnabled = Flags.appStartInfo();
        if (!mEnabled) {
            return;
        }

        registerForUserRemoval();
        registerForPackageRemoval();
        IoThread.getHandler().post(() -> {
            loadExistingProcessStartInfo();
        });
    }

    void onIntentStarted(@NonNull Intent intent, long timestampNanos) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            ApplicationStartInfo start = new ApplicationStartInfo();
            start.setStartupState(ApplicationStartInfo.STARTUP_STATE_STARTED);
            start.setIntent(intent);
            start.setStartType(ApplicationStartInfo.START_TYPE_UNSET);
            start.addStartupTimestamp(ApplicationStartInfo.START_TIMESTAMP_LAUNCH, timestampNanos);
            if (intent != null && intent.getCategories() != null
                    && intent.getCategories().contains(Intent.CATEGORY_LAUNCHER)) {
                start.setReason(ApplicationStartInfo.START_REASON_LAUNCHER);
            } else {
                start.setReason(ApplicationStartInfo.START_REASON_START_ACTIVITY);
            }
            mInProgRecords.put(timestampNanos, start);
        }
    }

    void onIntentFailed(long id) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            int index = mInProgRecords.indexOfKey(id);
            if (index < 0) {
                return;
            }
            ApplicationStartInfo info = mInProgRecords.valueAt(index);
            if (info == null) {
                mInProgRecords.removeAt(index);
                return;
            }
            info.setStartupState(ApplicationStartInfo.STARTUP_STATE_ERROR);
            mInProgRecords.removeAt(index);
        }
    }

    void onActivityLaunched(long id, ComponentName name, long temperature, ProcessRecord app) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            int index = mInProgRecords.indexOfKey(id);
            if (index < 0) {
                return;
            }
            ApplicationStartInfo info = mInProgRecords.valueAt(index);
            if (info == null || app == null) {
                mInProgRecords.removeAt(index);
                return;
            }
            info.setStartType((int) temperature);
            addBaseFieldsFromProcessRecord(info, app);
            ApplicationStartInfo newInfo = addStartInfoLocked(info);
            if (newInfo == null) {
                // newInfo can be null if records are added before load from storage is
                // complete. In this case the newly added record will be lost.
                mInProgRecords.removeAt(index);
            } else {
                mInProgRecords.setValueAt(index, newInfo);
            }
        }
    }

    void onActivityLaunchCancelled(long id) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            int index = mInProgRecords.indexOfKey(id);
            if (index < 0) {
                return;
            }
            ApplicationStartInfo info = mInProgRecords.valueAt(index);
            if (info == null) {
                mInProgRecords.removeAt(index);
                return;
            }
            info.setStartupState(ApplicationStartInfo.STARTUP_STATE_ERROR);
            mInProgRecords.removeAt(index);
        }
    }

    void onActivityLaunchFinished(long id, ComponentName name, long timestampNanos,
            int launchMode) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            int index = mInProgRecords.indexOfKey(id);
            if (index < 0) {
                return;
            }
            ApplicationStartInfo info = mInProgRecords.valueAt(index);
            if (info == null) {
                mInProgRecords.removeAt(index);
                return;
            }
            info.setStartupState(ApplicationStartInfo.STARTUP_STATE_FIRST_FRAME_DRAWN);
            info.setLaunchMode(launchMode);
            checkCompletenessAndCallback(info);
        }
    }

    void onReportFullyDrawn(long id, long timestampNanos) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            int index = mInProgRecords.indexOfKey(id);
            if (index < 0) {
                return;
            }
            ApplicationStartInfo info = mInProgRecords.valueAt(index);
            if (info == null) {
                mInProgRecords.removeAt(index);
                return;
            }
            info.addStartupTimestamp(ApplicationStartInfo.START_TIMESTAMP_FULLY_DRAWN,
                    timestampNanos);
            mInProgRecords.removeAt(index);
        }
    }

    public void handleProcessServiceStart(long startTimeNs, ProcessRecord app,
                ServiceRecord serviceRecord, boolean cold) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            ApplicationStartInfo start = new ApplicationStartInfo();
            addBaseFieldsFromProcessRecord(start, app);
            start.setStartupState(ApplicationStartInfo.STARTUP_STATE_STARTED);
            start.addStartupTimestamp(
                    ApplicationStartInfo.START_TIMESTAMP_LAUNCH, startTimeNs);
            start.setStartType(cold ? ApplicationStartInfo.START_TYPE_COLD
                    : ApplicationStartInfo.START_TYPE_WARM);
            start.setReason(serviceRecord.permission != null
                    && serviceRecord.permission.contains("android.permission.BIND_JOB_SERVICE")
                    ? ApplicationStartInfo.START_REASON_JOB
                    : ApplicationStartInfo.START_REASON_SERVICE);
            if (serviceRecord.intent != null) {
                start.setIntent(serviceRecord.intent.getIntent());
            }
            addStartInfoLocked(start);
        }
    }

    public void handleProcessBroadcastStart(long startTimeNs, ProcessRecord app,
                BroadcastRecord broadcast, boolean cold) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            ApplicationStartInfo start = new ApplicationStartInfo();
            addBaseFieldsFromProcessRecord(start, app);
            start.setStartupState(ApplicationStartInfo.STARTUP_STATE_STARTED);
            start.addStartupTimestamp(
                    ApplicationStartInfo.START_TIMESTAMP_LAUNCH, startTimeNs);
            start.setStartType(cold ? ApplicationStartInfo.START_TYPE_COLD
                    : ApplicationStartInfo.START_TYPE_WARM);
            if (broadcast == null) {
                start.setReason(ApplicationStartInfo.START_REASON_BROADCAST);
            } else if (broadcast.alarm) {
                start.setReason(ApplicationStartInfo.START_REASON_ALARM);
            } else if (broadcast.pushMessage || broadcast.pushMessageOverQuota) {
                start.setReason(ApplicationStartInfo.START_REASON_PUSH);
            } else if (Intent.ACTION_BOOT_COMPLETED.equals(broadcast.intent.getAction())) {
                start.setReason(ApplicationStartInfo.START_REASON_BOOT_COMPLETE);
            } else {
                start.setReason(ApplicationStartInfo.START_REASON_BROADCAST);
            }
            start.setIntent(broadcast != null ? broadcast.intent : null);
            addStartInfoLocked(start);
        }
    }

    public void handleProcessContentProviderStart(long startTimeNs, ProcessRecord app,
                boolean cold) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            ApplicationStartInfo start = new ApplicationStartInfo();
            addBaseFieldsFromProcessRecord(start, app);
            start.setStartupState(ApplicationStartInfo.STARTUP_STATE_STARTED);
            start.addStartupTimestamp(
                    ApplicationStartInfo.START_TIMESTAMP_LAUNCH, startTimeNs);
            start.setStartType(cold ? ApplicationStartInfo.START_TYPE_COLD
                    : ApplicationStartInfo.START_TYPE_WARM);
            start.setReason(ApplicationStartInfo.START_REASON_CONTENT_PROVIDER);
            addStartInfoLocked(start);
        }
    }

    public void handleProcessBackupStart(long startTimeNs, ProcessRecord app,
                BackupRecord backupRecord, boolean cold) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            ApplicationStartInfo start = new ApplicationStartInfo();
            addBaseFieldsFromProcessRecord(start, app);
            start.setStartupState(ApplicationStartInfo.STARTUP_STATE_STARTED);
            start.addStartupTimestamp(
                ApplicationStartInfo.START_TIMESTAMP_LAUNCH, startTimeNs);
            start.setStartType(cold ? ApplicationStartInfo.START_TYPE_COLD
                    : ApplicationStartInfo.START_TYPE_WARM);
            start.setReason(ApplicationStartInfo.START_REASON_BACKUP);
            addStartInfoLocked(start);
        }
    }

    private void addBaseFieldsFromProcessRecord(ApplicationStartInfo start, ProcessRecord app) {
        if (app == null) {
            return;
        }
        final int definingUid = app.getHostingRecord() != null
                ? app.getHostingRecord().getDefiningUid() : 0;
        start.setPid(app.getPid());
        start.setRealUid(app.uid);
        start.setPackageUid(app.info.uid);
        start.setDefiningUid(definingUid > 0 ? definingUid : app.info.uid);
        start.setProcessName(app.processName);
        start.setPackageName(app.info.packageName);
        if (android.content.pm.Flags.stayStopped()) {
            // TODO: Verify this is created at the right time to have the correct force-stopped
            // state in the ProcessRecord. Also use the WindowProcessRecord if activity.
            start.setForceStopped(app.wasForceStopped());
        }
    }

    void reportApplicationOnCreateTimeNanos(ProcessRecord app, long timeNs) {
        if (!mEnabled) {
            return;
        }
        addTimestampToStart(app, timeNs,
                ApplicationStartInfo.START_TIMESTAMP_APPLICATION_ONCREATE);
    }

    /** Report a bind application timestamp to add to {@link ApplicationStartInfo}. */
    public void reportBindApplicationTimeNanos(ProcessRecord app, long timeNs) {
        addTimestampToStart(app, timeNs,
                ApplicationStartInfo.START_TIMESTAMP_BIND_APPLICATION);
    }

    void reportFirstFrameTimeNanos(ProcessRecord app, long timeNs) {
        if (!mEnabled) {
            return;
        }
        addTimestampToStart(app, timeNs,
                ApplicationStartInfo.START_TIMESTAMP_FIRST_FRAME);
    }

    void reportFullyDrawnTimeNanos(ProcessRecord app, long timeNs) {
        if (!mEnabled) {
            return;
        }
        addTimestampToStart(app, timeNs,
                ApplicationStartInfo.START_TIMESTAMP_FULLY_DRAWN);
    }

    void reportFullyDrawnTimeNanos(String processName, int uid, long timeNs) {
        if (!mEnabled) {
            return;
        }
        addTimestampToStart(processName, uid, timeNs,
                ApplicationStartInfo.START_TIMESTAMP_FULLY_DRAWN);
    }

    private void addTimestampToStart(ProcessRecord app, long timeNs, int key) {
        addTimestampToStart(app.info.packageName, app.uid, timeNs, key);
    }

    private void addTimestampToStart(String packageName, int uid, long timeNs, int key) {
        synchronized (mLock) {
            AppStartInfoContainer container = mData.get(packageName, uid);
            if (container == null) {
                // Record was not created, discard new data.
                return;
            }
            container.addTimestampToStartLocked(key, timeNs);
        }
    }

    @GuardedBy("mLock")
    private ApplicationStartInfo addStartInfoLocked(ApplicationStartInfo raw) {
        if (!mAppStartInfoLoaded.get()) {
            //records added before initial load from storage will be lost.
            Slog.w(TAG, "Skipping saving the start info due to ongoing loading from storage");
            return null;
        }

        final ApplicationStartInfo info = new ApplicationStartInfo(raw);

        AppStartInfoContainer container = mData.get(raw.getPackageName(), raw.getRealUid());
        if (container == null) {
            container = new AppStartInfoContainer(mAppStartInfoHistoryListSize);
            container.mUid = info.getRealUid();
            mData.put(raw.getPackageName(), raw.getRealUid(), container);
        }
        container.addStartInfoLocked(info);

        schedulePersistProcessStartInfo(false);

        return info;
    }

    /**
     * Called whenever data is added to a {@link ApplicationStartInfo} object. Checks for
     * completeness and triggers callback if a callback has been registered and the object
     * is complete.
     */
    private void checkCompletenessAndCallback(ApplicationStartInfo startInfo) {
        synchronized (mLock) {
            if (startInfo.getStartupState()
                    == ApplicationStartInfo.STARTUP_STATE_FIRST_FRAME_DRAWN) {
                final List<ApplicationStartInfoCompleteCallback> callbacks =
                        mCallbacks.get(startInfo.getRealUid());
                if (callbacks == null) {
                    return;
                }
                final int size = callbacks.size();
                for (int i = 0; i < size; i++) {
                    if (callbacks.get(i) != null) {
                        callbacks.get(i).onApplicationStartInfoComplete(startInfo);
                    }
                }
                mCallbacks.remove(startInfo.getRealUid());
            }
        }
    }

    void getStartInfo(String packageName, int filterUid, int filterPid,
            int maxNum, ArrayList<ApplicationStartInfo> results) {
        if (!mEnabled) {
            return;
        }
        if (maxNum == 0) {
            maxNum = APP_START_INFO_HISTORY_LIST_SIZE;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                boolean emptyPackageName = TextUtils.isEmpty(packageName);
                if (!emptyPackageName) {
                    // fast path
                    AppStartInfoContainer container = mData.get(packageName, filterUid);
                    if (container != null) {
                        container.getStartInfoLocked(filterPid, maxNum, results);
                    }
                } else {
                    // slow path
                    final ArrayList<ApplicationStartInfo> list = mTmpStartInfoList;
                    list.clear();
                    // get all packages
                    forEachPackageLocked(
                            (name, records) -> {
                                AppStartInfoContainer container = records.get(filterUid);
                                if (container != null) {
                                    list.addAll(container.mInfos);
                                }
                                return AppStartInfoTracker.FOREACH_ACTION_NONE;
                            });

                    Collections.sort(
                            list, (a, b) ->
                            Long.compare(getStartTimestamp(b), getStartTimestamp(a)));
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
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    final class ApplicationStartInfoCompleteCallback implements DeathRecipient {
        private final int mUid;
        private final IApplicationStartInfoCompleteListener mCallback;

        ApplicationStartInfoCompleteCallback(IApplicationStartInfoCompleteListener callback,
                int uid) {
            mCallback = callback;
            mUid = uid;
            try {
                mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                /*ignored*/
            }
        }

        void onApplicationStartInfoComplete(ApplicationStartInfo startInfo) {
            try {
                mCallback.onApplicationStartInfoComplete(startInfo);
            } catch (RemoteException e) {
                /*ignored*/
            }
        }

        void unlinkToDeath() {
            mCallback.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            removeStartInfoCompleteListener(mCallback, mUid, false);
        }
    }

    void addStartInfoCompleteListener(
            final IApplicationStartInfoCompleteListener listener, final int uid) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            ArrayList<ApplicationStartInfoCompleteCallback> callbacks = mCallbacks.get(uid);
            if (callbacks == null) {
                mCallbacks.set(uid,
                        callbacks = new ArrayList<ApplicationStartInfoCompleteCallback>());
            }
            callbacks.add(new ApplicationStartInfoCompleteCallback(listener, uid));
        }
    }

    void removeStartInfoCompleteListener(
            final IApplicationStartInfoCompleteListener listener, final int uid,
            boolean unlinkDeathRecipient) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            final ArrayList<ApplicationStartInfoCompleteCallback> callbacks = mCallbacks.get(uid);
            if (callbacks == null) {
                return;
            }
            final int size  = callbacks.size();
            int index;
            for (index = 0; index < size; index++) {
                final ApplicationStartInfoCompleteCallback callback = callbacks.get(index);
                if (callback.mCallback == listener) {
                    if (unlinkDeathRecipient) {
                        callback.unlinkToDeath();
                    }
                    break;
                }
            }
            if (index < size) {
                callbacks.remove(index);
            }
            if (callbacks.isEmpty()) {
                mCallbacks.remove(uid);
            }
        }
    }

    @GuardedBy("mLock")
    private void forEachPackageLocked(
            BiFunction<String, SparseArray<AppStartInfoContainer>, Integer> callback) {
        if (callback != null) {
            ArrayMap<String, SparseArray<AppStartInfoContainer>> map = mData.getMap();
            for (int i = map.size() - 1; i >= 0; i--) {
                switch (callback.apply(map.keyAt(i), map.valueAt(i))) {
                    case FOREACH_ACTION_REMOVE_ITEM:
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
        ArrayMap<String, SparseArray<AppStartInfoContainer>> map = mData.getMap();
        SparseArray<AppStartInfoContainer> array = map.get(packageName);
        if (array == null) {
            return;
        }
        if (userId == UserHandle.USER_ALL) {
            mData.getMap().remove(packageName);
        } else {
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

    @GuardedBy("mLock")
    private void removeByUserIdLocked(final int userId) {
        if (userId == UserHandle.USER_ALL) {
            mData.getMap().clear();
            return;
        }
        forEachPackageLocked(
                (packageName, records) -> {
                    for (int i = records.size() - 1; i >= 0; i--) {
                        if (UserHandle.getUserId(records.keyAt(i)) == userId) {
                            records.removeAt(i);
                            break;
                        }
                    }
                    return records.size() == 0 ? FOREACH_ACTION_REMOVE_ITEM : FOREACH_ACTION_NONE;
                });
    }

    @VisibleForTesting
    void onUserRemoved(int userId) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            removeByUserIdLocked(userId);
            schedulePersistProcessStartInfo(true);
        }
    }

    @VisibleForTesting
    void onPackageRemoved(String packageName, int uid, boolean allUsers) {
        if (!mEnabled) {
            return;
        }
        if (packageName != null) {
            final boolean removeUid =
                    TextUtils.isEmpty(mService.mPackageManagerInt.getNameForUid(uid));
            synchronized (mLock) {
                removePackageLocked(
                        packageName,
                        uid,
                        removeUid,
                        allUsers ? UserHandle.USER_ALL : UserHandle.getUserId(uid));
                schedulePersistProcessStartInfo(true);
            }
        }
    }

    private void registerForUserRemoval() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_REMOVED);
        mService.mContext.registerReceiverForAllUsers(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                        if (userId < 1) return;
                        onUserRemoved(userId);
                    }
                },
                filter,
                null,
                mHandler);
    }

    private void registerForPackageRemoval() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        mService.mContext.registerReceiverForAllUsers(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                        if (replacing) {
                            return;
                        }
                        int uid = intent.getIntExtra(Intent.EXTRA_UID, UserHandle.USER_NULL);
                        boolean allUsers =
                                intent.getBooleanExtra(Intent.EXTRA_REMOVED_FOR_ALL_USERS, false);
                        onPackageRemoved(intent.getData().getSchemeSpecificPart(), uid, allUsers);
                    }
                },
                filter,
                null,
                mHandler);
    }

    /**
     * Load the existing {@link android.app.ApplicationStartInfo} records from persistent storage.
     */
    @VisibleForTesting
    void loadExistingProcessStartInfo() {
        if (!mEnabled) {
            return;
        }
        if (!mProcStartInfoFile.canRead()) {
            // If file can't be read, mark complete so we can begin accepting new records.
            mAppStartInfoLoaded.set(true);
            return;
        }

        FileInputStream fin = null;
        try {
            AtomicFile af = new AtomicFile(mProcStartInfoFile);
            fin = af.openRead();
            ProtoInputStream proto = new ProtoInputStream(fin);
            for (int next = proto.nextField();
                    next != ProtoInputStream.NO_MORE_FIELDS;
                    next = proto.nextField()) {
                switch (next) {
                    case (int) AppsStartInfoProto.LAST_UPDATE_TIMESTAMP:
                        synchronized (mLock) {
                            mLastAppStartInfoPersistTimestamp =
                                    proto.readLong(AppsStartInfoProto.LAST_UPDATE_TIMESTAMP);
                        }
                        break;
                    case (int) AppsStartInfoProto.PACKAGES:
                        loadPackagesFromProto(proto, next);
                        break;
                }
            }
        } catch (IOException | IllegalArgumentException | WireTypeMismatchException
                | ClassNotFoundException e) {
            Slog.w(TAG, "Error in loading historical app start info from persistent storage: " + e);
        } finally {
            if (fin != null) {
                try {
                    fin.close();
                } catch (IOException e) {
                }
            }
        }
        mAppStartInfoLoaded.set(true);
    }

    private void loadPackagesFromProto(ProtoInputStream proto, long fieldId)
            throws IOException, WireTypeMismatchException, ClassNotFoundException {
        long token = proto.start(fieldId);
        String pkgName = "";
        for (int next = proto.nextField();
                next != ProtoInputStream.NO_MORE_FIELDS;
                next = proto.nextField()) {
            switch (next) {
                case (int) AppsStartInfoProto.Package.PACKAGE_NAME:
                    pkgName = proto.readString(AppsStartInfoProto.Package.PACKAGE_NAME);
                    break;
                case (int) AppsStartInfoProto.Package.USERS:
                    AppStartInfoContainer container =
                            new AppStartInfoContainer(mAppStartInfoHistoryListSize);
                    int uid = container.readFromProto(proto, AppsStartInfoProto.Package.USERS);
                    synchronized (mLock) {
                        mData.put(pkgName, uid, container);
                    }
                    break;
            }
        }
        proto.end(token);
    }

    /** Persist the existing {@link android.app.ApplicationStartInfo} records to storage. */
    @VisibleForTesting
    void persistProcessStartInfo() {
        if (!mEnabled) {
            return;
        }
        AtomicFile af = new AtomicFile(mProcStartInfoFile);
        FileOutputStream out = null;
        long now = System.currentTimeMillis();
        try {
            out = af.startWrite();
            ProtoOutputStream proto = new ProtoOutputStream(out);
            proto.write(AppsStartInfoProto.LAST_UPDATE_TIMESTAMP, now);
            synchronized (mLock) {
                forEachPackageLocked(
                        (packageName, records) -> {
                            long token = proto.start(AppsStartInfoProto.PACKAGES);
                            proto.write(AppsStartInfoProto.Package.PACKAGE_NAME, packageName);
                            int uidArraySize = records.size();
                            for (int j = 0; j < uidArraySize; j++) {
                                try {
                                    records.valueAt(j)
                                        .writeToProto(proto, AppsStartInfoProto.Package.USERS);
                                } catch (IOException e) {
                                    Slog.w(TAG, "Unable to write app start info into persistent"
                                            + "storage: " + e);
                                }
                            }
                            proto.end(token);
                            return AppStartInfoTracker.FOREACH_ACTION_NONE;
                        });
                mLastAppStartInfoPersistTimestamp = now;
            }
            proto.flush();
            af.finishWrite(out);
        } catch (IOException e) {
            Slog.w(TAG, "Unable to write historical app start info into persistent storage: " + e);
            af.failWrite(out);
        }
        synchronized (mLock) {
            mAppStartInfoPersistTask = null;
        }
    }

    /**
     * Schedule a task to persist the {@link android.app.ApplicationStartInfo} records to storage.
     */
    @VisibleForTesting
    void schedulePersistProcessStartInfo(boolean immediately) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            if (mAppStartInfoPersistTask == null || immediately) {
                if (mAppStartInfoPersistTask != null) {
                    IoThread.getHandler().removeCallbacks(mAppStartInfoPersistTask);
                }
                mAppStartInfoPersistTask = this::persistProcessStartInfo;
                IoThread.getHandler()
                        .postDelayed(
                                mAppStartInfoPersistTask,
                                immediately ? 0 : APP_START_INFO_PERSIST_INTERVAL);
            }
        }
    }

    /** Helper function for testing only. */
    @VisibleForTesting
    void clearProcessStartInfo(boolean removeFile) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            if (mAppStartInfoPersistTask != null) {
                IoThread.getHandler().removeCallbacks(mAppStartInfoPersistTask);
                mAppStartInfoPersistTask = null;
            }
            if (removeFile && mProcStartInfoFile != null) {
                mProcStartInfoFile.delete();
            }
            mData.getMap().clear();
            mInProgRecords.clear();
        }
    }

    /**
     * Helper functions for shell command.
     * > adb shell dumpsys activity clear-start-info [package-name]
     */
    void clearHistoryProcessStartInfo(String packageName, int userId) {
        if (!mEnabled) {
            return;
        }
        Optional<Integer> appId = Optional.empty();
        if (TextUtils.isEmpty(packageName)) {
            synchronized (mLock) {
                removeByUserIdLocked(userId);
            }
        } else {
            final int uid =
                    mService.mPackageManagerInt.getPackageUid(
                            packageName, PackageManager.MATCH_ALL, userId);
            appId = Optional.of(UserHandle.getAppId(uid));
            synchronized (mLock) {
                removePackageLocked(packageName, uid, true, userId);
            }
        }
        schedulePersistProcessStartInfo(true);
    }

    /**
     * Helper functions for shell command.
     * > adb shell dumpsys activity start-info [package-name]
     */
    void dumpHistoryProcessStartInfo(PrintWriter pw, String packageName) {
        if (!mEnabled) {
            return;
        }
        pw.println("ACTIVITY MANAGER LRU PROCESSES (dumpsys activity start-info)");
        SimpleDateFormat sdf = new SimpleDateFormat();
        synchronized (mLock) {
            pw.println("Last Timestamp of Persistence Into Persistent Storage: "
                    + sdf.format(new Date(mLastAppStartInfoPersistTimestamp)));
            if (TextUtils.isEmpty(packageName)) {
                forEachPackageLocked((name, records) -> {
                    dumpHistoryProcessStartInfoLocked(pw, "  ", name, records, sdf);
                    return AppStartInfoTracker.FOREACH_ACTION_NONE;
                });
            } else {
                SparseArray<AppStartInfoContainer> array = mData.getMap().get(packageName);
                if (array != null) {
                    dumpHistoryProcessStartInfoLocked(pw, "  ", packageName, array, sdf);
                }
            }
        }
    }

    @GuardedBy("mLock")
    private void dumpHistoryProcessStartInfoLocked(PrintWriter pw, String prefix,
            String packageName, SparseArray<AppStartInfoContainer> array,
            SimpleDateFormat sdf) {
        pw.println(prefix + "package: " + packageName);
        int size = array.size();
        for (int i = 0; i < size; i++) {
            pw.println(prefix + "  Historical Process Start for userId=" + array.keyAt(i));
            array.valueAt(i).dumpLocked(pw, prefix + "    ", sdf);
        }
    }

    /** Convenience method to obtain timestamp of beginning of start.*/
    private static long getStartTimestamp(ApplicationStartInfo startInfo) {
        if (startInfo.getStartupTimestamps() == null
                    || !startInfo.getStartupTimestamps().containsKey(START_TIMESTAMP_LAUNCH)) {
            return -1;
        }
        return startInfo.getStartupTimestamps().get(START_TIMESTAMP_LAUNCH);
    }

    /** A container class of (@link android.app.ApplicationStartInfo) */
    final class AppStartInfoContainer {
        private List<ApplicationStartInfo> mInfos; // Always kept sorted by first timestamp.
        private int mMaxCapacity;
        private int mUid;

        AppStartInfoContainer(final int maxCapacity) {
            mInfos = new ArrayList<ApplicationStartInfo>();
            mMaxCapacity = maxCapacity;
        }

        @GuardedBy("mLock")
        void getStartInfoLocked(
                final int filterPid, final int maxNum, ArrayList<ApplicationStartInfo> results) {
            results.addAll(mInfos.size() <= maxNum ? 0 : mInfos.size() - maxNum, mInfos);
        }

        @GuardedBy("mLock")
        void addStartInfoLocked(ApplicationStartInfo info) {
            int size = mInfos.size();
            if (size >= mMaxCapacity) {
                // Remove oldest record if size is over max capacity.
                int oldestIndex = -1;
                long oldestTimeStamp = Long.MAX_VALUE;
                for (int i = 0; i < size; i++) {
                    ApplicationStartInfo startInfo = mInfos.get(i);
                    if (getStartTimestamp(startInfo) < oldestTimeStamp) {
                        oldestTimeStamp = getStartTimestamp(startInfo);
                        oldestIndex = i;
                    }
                }
                if (oldestIndex >= 0) {
                    mInfos.remove(oldestIndex);
                }
            }
            mInfos.add(info);
            Collections.sort(mInfos, (a, b) ->
                    Long.compare(getStartTimestamp(b), getStartTimestamp(a)));
        }

        @GuardedBy("mLock")
        void addTimestampToStartLocked(int key, long timestampNs) {
            int index = mInfos.size() - 1;
            int startupState = mInfos.get(index).getStartupState();
            if (startupState == ApplicationStartInfo.STARTUP_STATE_STARTED
                    || key == ApplicationStartInfo.START_TIMESTAMP_FULLY_DRAWN) {
                mInfos.get(index).addStartupTimestamp(key, timestampNs);
            }
        }

        @GuardedBy("mLock")
        void dumpLocked(PrintWriter pw, String prefix, SimpleDateFormat sdf) {
            int size = mInfos.size();
            for (int i = 0; i < size; i++) {
                mInfos.get(i).dump(pw, prefix + "  ", "#" + i, sdf);
            }
        }

        @GuardedBy("mLock")
        void writeToProto(ProtoOutputStream proto, long fieldId) throws IOException {
            long token = proto.start(fieldId);
            proto.write(AppsStartInfoProto.Package.User.UID, mUid);
            int size = mInfos.size();
            for (int i = 0; i < size; i++) {
                mInfos.get(i)
                        .writeToProto(proto, AppsStartInfoProto.Package.User.APP_START_INFO);
            }
            proto.end(token);
        }

        int readFromProto(ProtoInputStream proto, long fieldId)
                throws IOException, WireTypeMismatchException, ClassNotFoundException {
            long token = proto.start(fieldId);
            for (int next = proto.nextField();
                    next != ProtoInputStream.NO_MORE_FIELDS;
                    next = proto.nextField()) {
                switch (next) {
                    case (int) AppsStartInfoProto.Package.User.UID:
                        mUid = proto.readInt(AppsStartInfoProto.Package.User.UID);
                        break;
                    case (int) AppsStartInfoProto.Package.User.APP_START_INFO:
                        ApplicationStartInfo info = new ApplicationStartInfo();
                        info.readFromProto(proto, AppsStartInfoProto.Package.User.APP_START_INFO);
                        mInfos.add(info);
                        break;
                }
            }
            proto.end(token);
            return mUid;
        }
    }
}
