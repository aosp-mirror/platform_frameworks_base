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
import android.os.Debug;
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
import com.android.internal.os.Clock;
import com.android.internal.os.MonotonicClock;
import com.android.server.IoThread;
import com.android.server.ServiceThread;
import com.android.server.SystemServiceManager;
import com.android.server.wm.WindowProcessController;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

/** A class to manage all the {@link android.app.ApplicationStartInfo} records. */
public final class AppStartInfoTracker {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppStartInfoTracker" : TAG_AM;
    private static final boolean DEBUG = false;

    /** Interval of persisting the app start info to persistent storage. */
    private static final long APP_START_INFO_PERSIST_INTERVAL = TimeUnit.MINUTES.toMillis(30);

    /** These are actions that the forEach* should take after each iteration */
    private static final int FOREACH_ACTION_NONE = 0;
    private static final int FOREACH_ACTION_REMOVE_ITEM = 1;
    private static final int FOREACH_ACTION_STOP_ITERATION = 2;
    private static final int FOREACH_ACTION_REMOVE_AND_STOP_ITERATION = 3;

    private static final String MONITORING_MODE_EMPTY_TEXT = "No records";

    @VisibleForTesting static final int APP_START_INFO_HISTORY_LIST_SIZE = 16;

    /**
     * The max number of records that can be present in {@link mInProgressRecords}.
     *
     * The magic number of 5 records is expected to be enough because this covers in progress
     * activity starts only, of which more than a 1-2 at a time is very uncommon/unlikely.
     */
    @VisibleForTesting static final int MAX_IN_PROGRESS_RECORDS = 5;

    private static final int APP_START_INFO_MONITORING_MODE_LIST_SIZE = 100;

    @VisibleForTesting static final String APP_START_STORE_DIR = "procstartstore";

    @VisibleForTesting static final String APP_START_INFO_FILE = "procstartinfo";

    @VisibleForTesting final Object mLock = new Object();

    @VisibleForTesting boolean mEnabled = false;

    /**
     * Monotonic clock which does not reset on reboot.
     *
     * Time for offset is persisted along with records, see {@link #persistProcessStartInfo}.
     * This does not follow the recommendation of {@link MonotonicClock} to persist on shutdown as
     * it's ok in this case to lose any time change past the last persist as records added since
     * then will be lost as well and the purpose of this clock is to keep records in order.
     */
    @VisibleForTesting MonotonicClock mMonotonicClock = null;

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
    final ArrayMap<Long, ApplicationStartInfo> mInProgressRecords = new ArrayMap<>();

    /** Temporary list of keys present in {@link mInProgressRecords} for sorting. */
    @GuardedBy("mLock")
    @VisibleForTesting
    final ArrayList<Integer> mTemporaryInProgressIndexes = new ArrayList<>();

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

        if (mMonotonicClock == null) {
            // This should only happen if there are no persisted records, or if the records were
            // persisted by a version without the monotonic clock. Either way, create a new clock
            // with no offset. In the case of records with no monotonic time the value will default
            // to 0 and all new records will correctly end up in front of them.
            mMonotonicClock = new MonotonicClock(Clock.SYSTEM_CLOCK.elapsedRealtime(),
                    Clock.SYSTEM_CLOCK);
        }
    }

    /**
     * Trim in progress records structure to acceptable size. To be called after each time a new
     * record is added.
     *
     * This is necessary both for robustness, as well as because the call to
     * {@link onReportFullyDrawn} which triggers the removal in the success case is not guaranteed.
     *
     * <p class="note"> Note: this is the expected path for removal of in progress records for
     * successful activity triggered starts that don't report fully drawn. It is *not* only an edge
     * case.</p>
     */
    @GuardedBy("mLock")
    private void maybeTrimInProgressRecordsLocked() {
        if (mInProgressRecords.size() <= MAX_IN_PROGRESS_RECORDS) {
            // Size is acceptable, do nothing.
            return;
        }

        // Make sure the temporary list is empty.
        mTemporaryInProgressIndexes.clear();

        // Populate the list with indexes for size of {@link mInProgressRecords}.
        for (int i = 0; i < mInProgressRecords.size(); i++) {
            mTemporaryInProgressIndexes.add(i, i);
        }

        // Sort the index collection by value of the corresponding key in {@link mInProgressRecords}
        // from smallest to largest.
        Collections.sort(mTemporaryInProgressIndexes, (a, b) -> Long.compare(
                mInProgressRecords.keyAt(a), mInProgressRecords.keyAt(b)));

        if (mTemporaryInProgressIndexes.size() == MAX_IN_PROGRESS_RECORDS + 1) {
            // Only removing a single record so don't bother sorting again as we don't have to worry
            // about indexes changing.
            mInProgressRecords.removeAt(mTemporaryInProgressIndexes.get(0));
        } else {
            // Removing more than 1 record, remove the records we want to keep from the list and
            // then sort again so we can remove in reverse order of indexes.
            mTemporaryInProgressIndexes.subList(
                    mTemporaryInProgressIndexes.size() - MAX_IN_PROGRESS_RECORDS,
                    mTemporaryInProgressIndexes.size()).clear();
            Collections.sort(mTemporaryInProgressIndexes);

            // Remove all remaining record indexes in reverse order to avoid changing the already
            // calculated indexes.
            for (int i = mTemporaryInProgressIndexes.size() - 1; i >= 0; i--) {
                mInProgressRecords.removeAt(mTemporaryInProgressIndexes.get(i));
            }
        }

        // Clear the temorary list.
        mTemporaryInProgressIndexes.clear();
    }

    /**
     * Should only be called for Activity launch sequences from an instance of
     * {@link ActivityMetricsLaunchObserver}.
     */
    void onActivityIntentStarted(@NonNull Intent intent, long timestampNanos) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            ApplicationStartInfo start = new ApplicationStartInfo(getMonotonicTime());
            start.setStartupState(ApplicationStartInfo.STARTUP_STATE_STARTED);
            start.setIntent(intent);
            start.setStartType(ApplicationStartInfo.START_TYPE_UNSET);
            start.addStartupTimestamp(ApplicationStartInfo.START_TIMESTAMP_LAUNCH, timestampNanos);

            if (android.app.Flags.appStartInfoComponent()) {
                start.setStartComponent(ApplicationStartInfo.START_COMPONENT_ACTIVITY);
            }

            // TODO: handle possible alarm activity start.
            if (intent != null && intent.getCategories() != null
                    && intent.getCategories().contains(Intent.CATEGORY_LAUNCHER)) {
                start.setReason(ApplicationStartInfo.START_REASON_LAUNCHER);
            } else {
                start.setReason(ApplicationStartInfo.START_REASON_START_ACTIVITY);
            }
            mInProgressRecords.put(timestampNanos, start);
            maybeTrimInProgressRecordsLocked();
        }
    }

    /**
     * Should only be called for Activity launch sequences from an instance of
     * {@link ActivityMetricsLaunchObserver}.
     */
    void onActivityIntentFailed(long id) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            int index = mInProgressRecords.indexOfKey(id);
            if (index < 0) {
                return;
            }
            ApplicationStartInfo info = mInProgressRecords.valueAt(index);
            if (info == null) {
                mInProgressRecords.removeAt(index);
                return;
            }
            info.setStartupState(ApplicationStartInfo.STARTUP_STATE_ERROR);
            mInProgressRecords.removeAt(index);
        }
    }

    /**
     * Should only be called for Activity launch sequences from an instance of
     * {@link ActivityMetricsLaunchObserver}.
     */
    void onActivityLaunched(long id, ComponentName name, long temperature, ProcessRecord app) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            int index = mInProgressRecords.indexOfKey(id);
            if (index < 0) {
                return;
            }
            ApplicationStartInfo info = mInProgressRecords.valueAt(index);
            if (info == null || app == null) {
                mInProgressRecords.removeAt(index);
                return;
            }
            info.setStartType((int) temperature);
            addBaseFieldsFromProcessRecord(info, app);
            ApplicationStartInfo newInfo = addStartInfoLocked(info);
            if (newInfo == null) {
                // newInfo can be null if records are added before load from storage is
                // complete. In this case the newly added record will be lost.
                mInProgressRecords.removeAt(index);
            } else {
                mInProgressRecords.setValueAt(index, newInfo);
            }
        }
    }

    /**
     * Should only be called for Activity launch sequences from an instance of
     * {@link ActivityMetricsLaunchObserver}.
     */
    void onActivityLaunchCancelled(long id) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            int index = mInProgressRecords.indexOfKey(id);
            if (index < 0) {
                return;
            }
            ApplicationStartInfo info = mInProgressRecords.valueAt(index);
            if (info == null) {
                mInProgressRecords.removeAt(index);
                return;
            }
            info.setStartupState(ApplicationStartInfo.STARTUP_STATE_ERROR);
            mInProgressRecords.removeAt(index);
        }
    }

    /**
     * Should only be called for Activity launch sequences from an instance of
     * {@link ActivityMetricsLaunchObserver}.
     */
    void onActivityLaunchFinished(long id, ComponentName name, long timestampNanos,
            int launchMode) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            int index = mInProgressRecords.indexOfKey(id);
            if (index < 0) {
                return;
            }
            ApplicationStartInfo info = mInProgressRecords.valueAt(index);
            if (info == null) {
                mInProgressRecords.removeAt(index);
                return;
            }
            info.setLaunchMode(launchMode);
            if (!android.app.Flags.appStartInfoTimestamps()) {
                info.setStartupState(ApplicationStartInfo.STARTUP_STATE_FIRST_FRAME_DRAWN);
                checkCompletenessAndCallback(info);
            }
        }
    }

    /**
     * Should only be called for Activity launch sequences from an instance of
     * {@link ActivityMetricsLaunchObserver}.
     */
    void onActivityReportFullyDrawn(long id, long timestampNanos) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            int index = mInProgressRecords.indexOfKey(id);
            if (index < 0) {
                return;
            }
            ApplicationStartInfo info = mInProgressRecords.valueAt(index);
            if (info == null) {
                mInProgressRecords.removeAt(index);
                return;
            }
            info.addStartupTimestamp(ApplicationStartInfo.START_TIMESTAMP_FULLY_DRAWN,
                    timestampNanos);
            mInProgressRecords.removeAt(index);
        }
    }

    public void handleProcessServiceStart(long startTimeNs, ProcessRecord app,
                ServiceRecord serviceRecord) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            ApplicationStartInfo start = new ApplicationStartInfo(getMonotonicTime());
            addBaseFieldsFromProcessRecord(start, app);
            start.setStartupState(ApplicationStartInfo.STARTUP_STATE_STARTED);
            start.addStartupTimestamp(
                    ApplicationStartInfo.START_TIMESTAMP_LAUNCH, startTimeNs);
            start.setStartType(ApplicationStartInfo.START_TYPE_COLD);

            if (android.app.Flags.appStartInfoComponent()) {
                start.setStartComponent(ApplicationStartInfo.START_COMPONENT_SERVICE);
            }

            // TODO: handle possible alarm service start.
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

    /** Process a broadcast triggered app start. */
    public void handleProcessBroadcastStart(long startTimeNs, ProcessRecord app, Intent intent,
                boolean isAlarm) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            ApplicationStartInfo start = new ApplicationStartInfo(getMonotonicTime());
            addBaseFieldsFromProcessRecord(start, app);
            start.setStartupState(ApplicationStartInfo.STARTUP_STATE_STARTED);
            start.addStartupTimestamp(
                    ApplicationStartInfo.START_TIMESTAMP_LAUNCH, startTimeNs);
            start.setStartType(ApplicationStartInfo.START_TYPE_COLD);
            if (isAlarm) {
                start.setReason(ApplicationStartInfo.START_REASON_ALARM);
            } else {
                start.setReason(ApplicationStartInfo.START_REASON_BROADCAST);
            }
            start.setIntent(intent);

            if (android.app.Flags.appStartInfoComponent()) {
                start.setStartComponent(ApplicationStartInfo.START_COMPONENT_BROADCAST);
            }

            addStartInfoLocked(start);
        }
    }

    /** Process a content provider triggered app start. */
    public void handleProcessContentProviderStart(long startTimeNs, ProcessRecord app) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            ApplicationStartInfo start = new ApplicationStartInfo(getMonotonicTime());
            addBaseFieldsFromProcessRecord(start, app);
            start.setStartupState(ApplicationStartInfo.STARTUP_STATE_STARTED);
            start.addStartupTimestamp(
                    ApplicationStartInfo.START_TIMESTAMP_LAUNCH, startTimeNs);
            start.setStartType(ApplicationStartInfo.START_TYPE_COLD);
            start.setReason(ApplicationStartInfo.START_REASON_CONTENT_PROVIDER);

            if (android.app.Flags.appStartInfoComponent()) {
                start.setStartComponent(ApplicationStartInfo.START_COMPONENT_CONTENT_PROVIDER);
            }

            addStartInfoLocked(start);
        }
    }

    public void handleProcessBackupStart(long startTimeNs, ProcessRecord app,
                BackupRecord backupRecord, boolean cold) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }
            ApplicationStartInfo start = new ApplicationStartInfo(getMonotonicTime());
            addBaseFieldsFromProcessRecord(start, app);
            start.setStartupState(ApplicationStartInfo.STARTUP_STATE_STARTED);
            start.addStartupTimestamp(
                ApplicationStartInfo.START_TIMESTAMP_LAUNCH, startTimeNs);
            start.setStartType(cold ? ApplicationStartInfo.START_TYPE_COLD
                    : ApplicationStartInfo.START_TYPE_WARM);
            start.setReason(ApplicationStartInfo.START_REASON_BACKUP);

            if (android.app.Flags.appStartInfoComponent()) {
                start.setStartComponent(ApplicationStartInfo.START_COMPONENT_OTHER);
            }

            addStartInfoLocked(start);
        }
    }

    private void addBaseFieldsFromProcessRecord(ApplicationStartInfo start, ProcessRecord app) {
        if (app == null) {
            if (DEBUG) {
                Slog.w(TAG,
                        "app is null in addBaseFieldsFromProcessRecord: " + Debug.getCallers(4));
            }
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
            // state in the ProcessRecord.
            final WindowProcessController wpc = app.getWindowProcessController();
            start.setForceStopped(app.wasForceStopped()
                    || (wpc != null ? wpc.wasForceStopped() : false));
        }
    }

    /**
     * Helper functions for monitoring shell command.
     * > adb shell am start-info-detailed-monitoring [package-name]
     */
    void configureDetailedMonitoring(PrintWriter pw, String packageName, int userId) {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }

            forEachPackageLocked((name, records) -> {
                for (int i = 0; i < records.size(); i++) {
                    records.valueAt(i).disableAppMonitoringMode();
                }
                return AppStartInfoTracker.FOREACH_ACTION_NONE;
            });

            if (TextUtils.isEmpty(packageName)) {
                pw.println("ActivityManager AppStartInfo detailed monitoring disabled");
            } else {
                SparseArray<AppStartInfoContainer> array = mData.getMap().get(packageName);
                if (array != null) {
                    for (int i = 0; i < array.size(); i++) {
                        array.valueAt(i).enableAppMonitoringModeForUser(userId);
                    }
                    pw.println("ActivityManager AppStartInfo detailed monitoring enabled for "
                            + packageName);
                } else {
                    pw.println("Package " + packageName + " not found");
                }
            }
        }
    }

    void addTimestampToStart(ProcessRecord app, long timeNs, int key) {
        addTimestampToStart(app.info.packageName, app.uid, timeNs, key);
    }

    void addTimestampToStart(String packageName, int uid, long timeNs, int key) {
        if (!mEnabled) {
            return;
        }
        synchronized (mLock) {
            AppStartInfoContainer container = mData.get(packageName, uid);
            if (container == null) {
                // Record was not created, discard new data.
                if (DEBUG) {
                    Slog.d(TAG, "No container found for package=" + packageName + " and uid=" + uid
                            + ". Discarding timestamp key=" + key + " val=" + timeNs);
                }
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
     * Called whenever a potentially final piece of data is added to a {@link ApplicationStartInfo}
     * object. Checks for completeness and triggers callback if a callback has been registered and
     * the object is complete.
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
                            Long.compare(b.getMonoticCreationTimeMs(),
                                    a.getMonoticCreationTimeMs()));
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

    /**
     * Run provided callback for each packake in start info dataset.
     *
     * @return whether the for each completed naturally, false if it was stopped manually.
     */
    @GuardedBy("mLock")
    private boolean forEachPackageLocked(
            BiFunction<String, SparseArray<AppStartInfoContainer>, Integer> callback) {
        if (callback != null) {
            ArrayMap<String, SparseArray<AppStartInfoContainer>> map = mData.getMap();
            for (int i = map.size() - 1; i >= 0; i--) {
                switch (callback.apply(map.keyAt(i), map.valueAt(i))) {
                    case FOREACH_ACTION_REMOVE_ITEM:
                        map.removeAt(i);
                        break;
                    case FOREACH_ACTION_STOP_ITERATION:
                        return false;
                    case FOREACH_ACTION_REMOVE_AND_STOP_ITERATION:
                        map.removeAt(i);
                        return false;
                    case FOREACH_ACTION_NONE:
                    default:
                        break;
                }
            }
        }
        return true;
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
                    case (int) AppsStartInfoProto.MONOTONIC_TIME:
                        long monotonicTime = proto.readLong(AppsStartInfoProto.MONOTONIC_TIME);
                        mMonotonicClock = new MonotonicClock(monotonicTime, Clock.SYSTEM_CLOCK);
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
                    int uid = container.readFromProto(proto, AppsStartInfoProto.Package.USERS,
                            pkgName);
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
        boolean succeeded;
        long now = System.currentTimeMillis();
        try {
            out = af.startWrite();
            ProtoOutputStream proto = new ProtoOutputStream(out);
            proto.write(AppsStartInfoProto.LAST_UPDATE_TIMESTAMP, now);
            synchronized (mLock) {
                succeeded = forEachPackageLocked(
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
                                    // There was likely an issue with this record that won't resolve
                                    // next time we try to persist so remove it. Also stop iteration
                                    // as we failed the write and need to start again from scratch.
                                    return AppStartInfoTracker
                                            .FOREACH_ACTION_REMOVE_AND_STOP_ITERATION;
                                }
                            }
                            proto.end(token);
                            return AppStartInfoTracker.FOREACH_ACTION_NONE;
                        });
                if (succeeded) {
                    mLastAppStartInfoPersistTimestamp = now;
                }
            }
            proto.write(AppsStartInfoProto.MONOTONIC_TIME, getMonotonicTime());
            if (succeeded) {
                proto.flush();
                af.finishWrite(out);
            } else {
                af.failWrite(out);
            }
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
            mInProgressRecords.clear();
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

    private long getMonotonicTime() {
        if (mMonotonicClock == null) {
            // This should never happen. Return 0 to not interfere with past or future records.
            return 0;
        }
        return mMonotonicClock.monotonicTime();
    }

    /** A container class of (@link android.app.ApplicationStartInfo) */
    final class AppStartInfoContainer {
        private ArrayList<ApplicationStartInfo> mInfos; // Always kept sorted by first timestamp.
        private int mMaxCapacity;
        private int mUid;
        private boolean mMonitoringModeEnabled = false;

        AppStartInfoContainer(final int maxCapacity) {
            mInfos = new ArrayList<ApplicationStartInfo>();
            mMaxCapacity = maxCapacity;
        }

        int getMaxCapacity() {
            return mMonitoringModeEnabled ? APP_START_INFO_MONITORING_MODE_LIST_SIZE : mMaxCapacity;
        }

        @GuardedBy("mLock")
        void enableAppMonitoringModeForUser(int userId) {
            if (UserHandle.getUserId(mUid) == userId) {
                mMonitoringModeEnabled = true;
            }
        }

        @GuardedBy("mLock")
        void disableAppMonitoringMode() {
            mMonitoringModeEnabled = false;

            // Capacity is reduced by turning off monitoring mode. Check if array size is within
            // new lower limits and trim extraneous records if it is not.
            if (mInfos.size() <= getMaxCapacity()) {
                return;
            }

            // Sort records so we can remove the least recent ones.
            Collections.sort(mInfos, (a, b) ->
                    Long.compare(b.getMonoticCreationTimeMs(), a.getMonoticCreationTimeMs()));

            // Remove records and trim list object back to size.
            mInfos.subList(0, mInfos.size() - getMaxCapacity()).clear();
            mInfos.trimToSize();
        }

        @GuardedBy("mLock")
        void getStartInfoLocked(
                final int filterPid, final int maxNum, ArrayList<ApplicationStartInfo> results) {
            results.addAll(mInfos.size() <= maxNum ? 0 : mInfos.size() - maxNum, mInfos);
        }

        @GuardedBy("mLock")
        void addStartInfoLocked(ApplicationStartInfo info) {
            int size = mInfos.size();
            if (size >= getMaxCapacity()) {
                // Remove oldest record if size is over max capacity.
                int oldestIndex = -1;
                long oldestTimeStamp = Long.MAX_VALUE;
                for (int i = 0; i < size; i++) {
                    ApplicationStartInfo startInfo = mInfos.get(i);
                    if (startInfo.getMonoticCreationTimeMs() < oldestTimeStamp) {
                        oldestTimeStamp = startInfo.getMonoticCreationTimeMs();
                        oldestIndex = i;
                    }
                }
                if (oldestIndex >= 0) {
                    mInfos.remove(oldestIndex);
                }
            }
            mInfos.add(info);
            Collections.sort(mInfos, (a, b) ->
                    Long.compare(b.getMonoticCreationTimeMs(), a.getMonoticCreationTimeMs()));
        }

        /**
         * Add the provided key/timestamp to the most recent start record, if it is currently
         * accepting new timestamps.
         *
         * Will also update the start records startup state and trigger the completion listener when
         * appropriate.
         */
        @GuardedBy("mLock")
        void addTimestampToStartLocked(int key, long timestampNs) {
            if (mInfos.isEmpty()) {
                if (DEBUG) Slog.d(TAG, "No records to add to.");
                return;
            }

            // Records are sorted newest to oldest, grab record at index 0.
            ApplicationStartInfo startInfo = mInfos.get(0);

            if (!isAddTimestampAllowed(startInfo, key, timestampNs)) {
                return;
            }

            startInfo.addStartupTimestamp(key, timestampNs);

            if (key == ApplicationStartInfo.START_TIMESTAMP_FIRST_FRAME
                    && android.app.Flags.appStartInfoTimestamps()) {
                startInfo.setStartupState(ApplicationStartInfo.STARTUP_STATE_FIRST_FRAME_DRAWN);
                checkCompletenessAndCallback(startInfo);
            }
        }

        private boolean isAddTimestampAllowed(ApplicationStartInfo startInfo, int key,
                long timestampNs) {
            int startupState = startInfo.getStartupState();

            // If startup state is error then don't accept any further timestamps.
            if (startupState == ApplicationStartInfo.STARTUP_STATE_ERROR) {
                if (DEBUG) Slog.d(TAG, "Startup state is error, not accepting new timestamps.");
                return false;
            }

            Map<Integer, Long> timestamps = startInfo.getStartupTimestamps();

            if (startupState == ApplicationStartInfo.STARTUP_STATE_FIRST_FRAME_DRAWN) {
                switch (key) {
                    case ApplicationStartInfo.START_TIMESTAMP_FULLY_DRAWN:
                        // Allowed, continue to confirm it's not already added.
                        break;
                    case ApplicationStartInfo.START_TIMESTAMP_INITIAL_RENDERTHREAD_FRAME:
                        Long firstFrameTimeNs = timestamps
                                .get(ApplicationStartInfo.START_TIMESTAMP_FIRST_FRAME);
                        if (firstFrameTimeNs == null) {
                            // This should never happen. State can't be first frame drawn if first
                            // frame timestamp was not provided.
                            return false;
                        }

                        if (timestampNs > firstFrameTimeNs) {
                            // Initial renderthread frame has to occur before first frame.
                            return false;
                        }

                        // Allowed, continue to confirm it's not already added.
                        break;
                    case ApplicationStartInfo.START_TIMESTAMP_SURFACEFLINGER_COMPOSITION_COMPLETE:
                        // Allowed, continue to confirm it's not already added.
                        break;
                    default:
                        return false;
                }
            }

            if (timestamps.get(key) != null) {
                // Timestamp should not occur more than once for a given start.
                return false;
            }

            return true;
        }

        @GuardedBy("mLock")
        void dumpLocked(PrintWriter pw, String prefix, SimpleDateFormat sdf) {
            if (mMonitoringModeEnabled) {
                // For monitoring mode, calculate the average start time for each start state to
                // add to output.
                List<Long> coldStartTimes = new ArrayList<>();
                List<Long> warmStartTimes = new ArrayList<>();
                List<Long> hotStartTimes = new ArrayList<>();

                for (int i = 0; i < mInfos.size(); i++) {
                    ApplicationStartInfo startInfo = mInfos.get(i);
                    Map<Integer, Long> timestamps = startInfo.getStartupTimestamps();

                    // Confirm required timestamps exist.
                    if (timestamps.containsKey(ApplicationStartInfo.START_TIMESTAMP_LAUNCH)
                            && timestamps.containsKey(
                            ApplicationStartInfo.START_TIMESTAMP_FIRST_FRAME)) {
                        // Add timestamp to correct collection.
                        long time = timestamps.get(ApplicationStartInfo.START_TIMESTAMP_FIRST_FRAME)
                                - timestamps.get(ApplicationStartInfo.START_TIMESTAMP_LAUNCH);
                        switch (startInfo.getStartType()) {
                            case ApplicationStartInfo.START_TYPE_COLD:
                                coldStartTimes.add(time);
                                break;
                            case ApplicationStartInfo.START_TYPE_WARM:
                                warmStartTimes.add(time);
                                break;
                            case ApplicationStartInfo.START_TYPE_HOT:
                                hotStartTimes.add(time);
                                break;
                        }
                    }
                }

                pw.println(prefix + "  Average Start Time in ns for Cold Starts: "
                        + (coldStartTimes.isEmpty()  ? MONITORING_MODE_EMPTY_TEXT
                                : calculateAverage(coldStartTimes)));
                pw.println(prefix + "  Average Start Time in ns for Warm Starts: "
                        + (warmStartTimes.isEmpty() ? MONITORING_MODE_EMPTY_TEXT
                                : calculateAverage(warmStartTimes)));
                pw.println(prefix + "  Average Start Time in ns for Hot Starts: "
                        + (hotStartTimes.isEmpty() ? MONITORING_MODE_EMPTY_TEXT
                                : calculateAverage(hotStartTimes)));
            }

            int size = mInfos.size();
            for (int i = 0; i < size; i++) {
                mInfos.get(i).dump(pw, prefix + "  ", "#" + i, sdf);
            }
        }

        private long calculateAverage(List<Long> vals) {
            return (long) vals.stream().mapToDouble(a -> a).average().orElse(0.0);
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
            proto.write(AppsStartInfoProto.Package.User.MONITORING_ENABLED, mMonitoringModeEnabled);
            proto.end(token);
        }

        int readFromProto(ProtoInputStream proto, long fieldId, String packageName)
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
                        // Create record with monotonic time 0 in case the persisted record does not
                        // have a create time.
                        ApplicationStartInfo info = new ApplicationStartInfo(0);
                        info.readFromProto(proto, AppsStartInfoProto.Package.User.APP_START_INFO);
                        info.setPackageName(packageName);
                        mInfos.add(info);
                        break;
                    case (int) AppsStartInfoProto.Package.User.MONITORING_ENABLED:
                        mMonitoringModeEnabled = proto.readBoolean(
                            AppsStartInfoProto.Package.User.MONITORING_ENABLED);
                        break;
                }
            }
            proto.end(token);
            return mUid;
        }
    }
}
