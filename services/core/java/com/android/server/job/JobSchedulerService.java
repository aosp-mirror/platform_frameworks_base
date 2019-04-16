/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.job;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;

import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.IUidObserver;
import android.app.job.IJobScheduler;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobProtoEnums;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.job.JobWorkItem;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageStatsManagerInternal;
import android.app.usage.UsageStatsManagerInternal.AppIdleStateChangeListener;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.BatteryStats;
import android.os.BatteryStatsInternal;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.util.KeyValueListParser;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.StatsLog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.AppStateTracker;
import com.android.server.DeviceIdleController;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerServiceDumpProto.ActiveJob;
import com.android.server.job.JobSchedulerServiceDumpProto.PendingJob;
import com.android.server.job.JobSchedulerServiceDumpProto.RegisteredJob;
import com.android.server.job.controllers.BackgroundJobsController;
import com.android.server.job.controllers.BatteryController;
import com.android.server.job.controllers.ConnectivityController;
import com.android.server.job.controllers.ContentObserverController;
import com.android.server.job.controllers.DeviceIdleJobsController;
import com.android.server.job.controllers.IdleController;
import com.android.server.job.controllers.JobStatus;
import com.android.server.job.controllers.StateController;
import com.android.server.job.controllers.StorageController;
import com.android.server.job.controllers.TimeController;

import libcore.util.EmptyArray;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Responsible for taking jobs representing work to be performed by a client app, and determining
 * based on the criteria specified when that job should be run against the client application's
 * endpoint.
 * Implements logic for scheduling, and rescheduling jobs. The JobSchedulerService knows nothing
 * about constraints, or the state of active jobs. It receives callbacks from the various
 * controllers and completed jobs and operates accordingly.
 *
 * Note on locking: Any operations that manipulate {@link #mJobs} need to lock on that object.
 * Any function with the suffix 'Locked' also needs to lock on {@link #mJobs}.
 * @hide
 */
public class JobSchedulerService extends com.android.server.SystemService
        implements StateChangedListener, JobCompletedListener {
    public static final String TAG = "JobScheduler";
    public static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    public static final boolean DEBUG_STANDBY = DEBUG || false;

    /** The maximum number of concurrent jobs we run at one time. */
    private static final int MAX_JOB_CONTEXTS_COUNT = 16;
    /** Enforce a per-app limit on scheduled jobs? */
    private static final boolean ENFORCE_MAX_JOBS = true;
    /** The maximum number of jobs that we allow an unprivileged app to schedule */
    private static final int MAX_JOBS_PER_APP = 100;

    @VisibleForTesting
    public static Clock sSystemClock = Clock.systemUTC();
    @VisibleForTesting
    public static Clock sUptimeMillisClock = SystemClock.uptimeMillisClock();
    @VisibleForTesting
    public static Clock sElapsedRealtimeClock = SystemClock.elapsedRealtimeClock();

    /** Global local for all job scheduler state. */
    final Object mLock = new Object();
    /** Master list of jobs. */
    final JobStore mJobs;
    /** Tracking the standby bucket state of each app */
    final StandbyTracker mStandbyTracker;
    /** Tracking amount of time each package runs for. */
    final JobPackageTracker mJobPackageTracker = new JobPackageTracker();

    static final int MSG_JOB_EXPIRED = 0;
    static final int MSG_CHECK_JOB = 1;
    static final int MSG_STOP_JOB = 2;
    static final int MSG_CHECK_JOB_GREEDY = 3;
    static final int MSG_UID_STATE_CHANGED = 4;
    static final int MSG_UID_GONE = 5;
    static final int MSG_UID_ACTIVE = 6;
    static final int MSG_UID_IDLE = 7;

    /**
     * Track Services that have currently active or pending jobs. The index is provided by
     * {@link JobStatus#getServiceToken()}
     */
    final List<JobServiceContext> mActiveServices = new ArrayList<>();

    /** List of controllers that will notify this service of updates to jobs. */
    private final List<StateController> mControllers;
    /** Need direct access to this for testing. */
    private final BatteryController mBatteryController;
    /** Need direct access to this for testing. */
    private final StorageController mStorageController;
    /** Need directly for sending uid state changes */
    private final DeviceIdleJobsController mDeviceIdleJobsController;

    /**
     * Queue of pending jobs. The JobServiceContext class will receive jobs from this list
     * when ready to execute them.
     */
    final ArrayList<JobStatus> mPendingJobs = new ArrayList<>();

    int[] mStartedUsers = EmptyArray.INT;

    final JobHandler mHandler;
    final JobSchedulerStub mJobSchedulerStub;

    PackageManagerInternal mLocalPM;
    ActivityManagerInternal mActivityManagerInternal;
    IBatteryStats mBatteryStats;
    DeviceIdleController.LocalService mLocalDeviceIdleController;
    AppStateTracker mAppStateTracker;
    final UsageStatsManagerInternal mUsageStats;

    /**
     * Set to true once we are allowed to run third party apps.
     */
    boolean mReadyToRock;

    /**
     * What we last reported to DeviceIdleController about whether we are active.
     */
    boolean mReportedActive;

    /**
     * Are we currently in device-wide standby parole?
     */
    volatile boolean mInParole;

    /**
     * Current limit on the number of concurrent JobServiceContext entries we want to
     * keep actively running a job.
     */
    int mMaxActiveJobs = 1;

    /**
     * A mapping of which uids are currently in the foreground to their effective priority.
     */
    final SparseIntArray mUidPriorityOverride = new SparseIntArray();

    /**
     * Which uids are currently performing backups, so we shouldn't allow their jobs to run.
     */
    final SparseIntArray mBackingUpUids = new SparseIntArray();

    /**
     * Count standby heartbeats, and keep track of which beat each bucket's jobs will
     * next become runnable.  Index into this array is by normalized bucket:
     * { ACTIVE, WORKING, FREQUENT, RARE, NEVER }.  The ACTIVE and NEVER bucket
     * milestones are not updated: ACTIVE apps get jobs whenever they ask for them,
     * and NEVER apps don't get them at all.
     */
    final long[] mNextBucketHeartbeat = { 0, 0, 0, 0, Long.MAX_VALUE };
    long mHeartbeat = 0;
    long mLastHeartbeatTime = sElapsedRealtimeClock.millis();

    /**
     * Named indices into the STANDBY_BEATS array, for clarity in referring to
     * specific buckets' bookkeeping.
     */
    static final int ACTIVE_INDEX = 0;
    static final int WORKING_INDEX = 1;
    static final int FREQUENT_INDEX = 2;
    static final int RARE_INDEX = 3;
    static final int NEVER_INDEX = 4;

    /**
     * Bookkeeping about when jobs last run.  We keep our own record in heartbeat time,
     * rather than rely on Usage Stats' timestamps, because heartbeat time can be
     * manipulated for testing purposes and we need job runnability to track that rather
     * than real time.
     *
     * Outer SparseArray slices by user handle; inner map of package name to heartbeat
     * is a HashMap<> rather than ArrayMap<> because we expect O(hundreds) of keys
     * and it will be accessed in a known-hot code path.
     */
    final SparseArray<HashMap<String, Long>> mLastJobHeartbeats = new SparseArray<>();

    static final String HEARTBEAT_TAG = "*job.heartbeat*";
    final HeartbeatAlarmListener mHeartbeatAlarm = new HeartbeatAlarmListener();

    // -- Pre-allocated temporaries only for use in assignJobsToContextsLocked --

    /**
     * This array essentially stores the state of mActiveServices array.
     * The ith index stores the job present on the ith JobServiceContext.
     * We manipulate this array until we arrive at what jobs should be running on
     * what JobServiceContext.
     */
    JobStatus[] mTmpAssignContextIdToJobMap = new JobStatus[MAX_JOB_CONTEXTS_COUNT];
    /**
     * Indicates whether we need to act on this jobContext id
     */
    boolean[] mTmpAssignAct = new boolean[MAX_JOB_CONTEXTS_COUNT];
    /**
     * The uid whose jobs we would like to assign to a context.
     */
    int[] mTmpAssignPreferredUidForContext = new int[MAX_JOB_CONTEXTS_COUNT];

    private class ConstantsObserver extends ContentObserver {
        private ContentResolver mResolver;

        public ConstantsObserver(Handler handler) {
            super(handler);
        }

        public void start(ContentResolver resolver) {
            mResolver = resolver;
            mResolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.JOB_SCHEDULER_CONSTANTS), false, this);
            updateConstants();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateConstants();
        }

        private void updateConstants() {
            synchronized (mLock) {
                try {
                    mConstants.updateConstantsLocked(Settings.Global.getString(mResolver,
                            Settings.Global.JOB_SCHEDULER_CONSTANTS));
                } catch (IllegalArgumentException e) {
                    // Failed to parse the settings string, log this and move on
                    // with defaults.
                    Slog.e(TAG, "Bad jobscheduler settings", e);
                }
            }

            // Reset the heartbeat alarm based on the new heartbeat duration
            setNextHeartbeatAlarm();
        }
    }

    /**
     * All times are in milliseconds. These constants are kept synchronized with the system
     * global Settings. Any access to this class or its fields should be done while
     * holding the JobSchedulerService.mLock lock.
     */
    public static class Constants {
        // Key names stored in the settings value.
        private static final String KEY_MIN_IDLE_COUNT = "min_idle_count";
        private static final String KEY_MIN_CHARGING_COUNT = "min_charging_count";
        private static final String KEY_MIN_BATTERY_NOT_LOW_COUNT = "min_battery_not_low_count";
        private static final String KEY_MIN_STORAGE_NOT_LOW_COUNT = "min_storage_not_low_count";
        private static final String KEY_MIN_CONNECTIVITY_COUNT = "min_connectivity_count";
        private static final String KEY_MIN_CONTENT_COUNT = "min_content_count";
        private static final String KEY_MIN_READY_JOBS_COUNT = "min_ready_jobs_count";
        private static final String KEY_HEAVY_USE_FACTOR = "heavy_use_factor";
        private static final String KEY_MODERATE_USE_FACTOR = "moderate_use_factor";
        private static final String KEY_FG_JOB_COUNT = "fg_job_count";
        private static final String KEY_BG_NORMAL_JOB_COUNT = "bg_normal_job_count";
        private static final String KEY_BG_MODERATE_JOB_COUNT = "bg_moderate_job_count";
        private static final String KEY_BG_LOW_JOB_COUNT = "bg_low_job_count";
        private static final String KEY_BG_CRITICAL_JOB_COUNT = "bg_critical_job_count";
        private static final String KEY_MAX_STANDARD_RESCHEDULE_COUNT
                = "max_standard_reschedule_count";
        private static final String KEY_MAX_WORK_RESCHEDULE_COUNT = "max_work_reschedule_count";
        private static final String KEY_MIN_LINEAR_BACKOFF_TIME = "min_linear_backoff_time";
        private static final String KEY_MIN_EXP_BACKOFF_TIME = "min_exp_backoff_time";
        private static final String KEY_STANDBY_HEARTBEAT_TIME = "standby_heartbeat_time";
        private static final String KEY_STANDBY_WORKING_BEATS = "standby_working_beats";
        private static final String KEY_STANDBY_FREQUENT_BEATS = "standby_frequent_beats";
        private static final String KEY_STANDBY_RARE_BEATS = "standby_rare_beats";
        private static final String KEY_CONN_CONGESTION_DELAY_FRAC = "conn_congestion_delay_frac";
        private static final String KEY_CONN_PREFETCH_RELAX_FRAC = "conn_prefetch_relax_frac";

        private static final int DEFAULT_MIN_IDLE_COUNT = 1;
        private static final int DEFAULT_MIN_CHARGING_COUNT = 1;
        private static final int DEFAULT_MIN_BATTERY_NOT_LOW_COUNT = 1;
        private static final int DEFAULT_MIN_STORAGE_NOT_LOW_COUNT = 1;
        private static final int DEFAULT_MIN_CONNECTIVITY_COUNT = 1;
        private static final int DEFAULT_MIN_CONTENT_COUNT = 1;
        private static final int DEFAULT_MIN_READY_JOBS_COUNT = 1;
        private static final float DEFAULT_HEAVY_USE_FACTOR = .9f;
        private static final float DEFAULT_MODERATE_USE_FACTOR = .5f;
        private static final int DEFAULT_FG_JOB_COUNT = 4;
        private static final int DEFAULT_BG_NORMAL_JOB_COUNT = 6;
        private static final int DEFAULT_BG_MODERATE_JOB_COUNT = 4;
        private static final int DEFAULT_BG_LOW_JOB_COUNT = 1;
        private static final int DEFAULT_BG_CRITICAL_JOB_COUNT = 1;
        private static final int DEFAULT_MAX_STANDARD_RESCHEDULE_COUNT = Integer.MAX_VALUE;
        private static final int DEFAULT_MAX_WORK_RESCHEDULE_COUNT = Integer.MAX_VALUE;
        private static final long DEFAULT_MIN_LINEAR_BACKOFF_TIME = JobInfo.MIN_BACKOFF_MILLIS;
        private static final long DEFAULT_MIN_EXP_BACKOFF_TIME = JobInfo.MIN_BACKOFF_MILLIS;
        private static final long DEFAULT_STANDBY_HEARTBEAT_TIME = 11 * 60 * 1000L;
        private static final int DEFAULT_STANDBY_WORKING_BEATS = 11;  // ~ 2 hours, with 11min beats
        private static final int DEFAULT_STANDBY_FREQUENT_BEATS = 43; // ~ 8 hours
        private static final int DEFAULT_STANDBY_RARE_BEATS = 130; // ~ 24 hours
        private static final float DEFAULT_CONN_CONGESTION_DELAY_FRAC = 0.5f;
        private static final float DEFAULT_CONN_PREFETCH_RELAX_FRAC = 0.5f;

        /**
         * Minimum # of idle jobs that must be ready in order to force the JMS to schedule things
         * early.
         */
        int MIN_IDLE_COUNT = DEFAULT_MIN_IDLE_COUNT;
        /**
         * Minimum # of charging jobs that must be ready in order to force the JMS to schedule
         * things early.
         */
        int MIN_CHARGING_COUNT = DEFAULT_MIN_CHARGING_COUNT;
        /**
         * Minimum # of "battery not low" jobs that must be ready in order to force the JMS to
         * schedule things early.
         */
        int MIN_BATTERY_NOT_LOW_COUNT = DEFAULT_MIN_BATTERY_NOT_LOW_COUNT;
        /**
         * Minimum # of "storage not low" jobs that must be ready in order to force the JMS to
         * schedule things early.
         */
        int MIN_STORAGE_NOT_LOW_COUNT = DEFAULT_MIN_STORAGE_NOT_LOW_COUNT;
        /**
         * Minimum # of connectivity jobs that must be ready in order to force the JMS to schedule
         * things early.  1 == Run connectivity jobs as soon as ready.
         */
        int MIN_CONNECTIVITY_COUNT = DEFAULT_MIN_CONNECTIVITY_COUNT;
        /**
         * Minimum # of content trigger jobs that must be ready in order to force the JMS to
         * schedule things early.
         */
        int MIN_CONTENT_COUNT = DEFAULT_MIN_CONTENT_COUNT;
        /**
         * Minimum # of jobs (with no particular constraints) for which the JMS will be happy
         * running some work early.  This (and thus the other min counts) is now set to 1, to
         * prevent any batching at this level.  Since we now do batching through doze, that is
         * a much better mechanism.
         */
        int MIN_READY_JOBS_COUNT = DEFAULT_MIN_READY_JOBS_COUNT;
        /**
         * This is the job execution factor that is considered to be heavy use of the system.
         */
        float HEAVY_USE_FACTOR = DEFAULT_HEAVY_USE_FACTOR;
        /**
         * This is the job execution factor that is considered to be moderate use of the system.
         */
        float MODERATE_USE_FACTOR = DEFAULT_MODERATE_USE_FACTOR;
        /**
         * The number of MAX_JOB_CONTEXTS_COUNT we reserve for the foreground app.
         */
        int FG_JOB_COUNT = DEFAULT_FG_JOB_COUNT;
        /**
         * The maximum number of background jobs we allow when the system is in a normal
         * memory state.
         */
        int BG_NORMAL_JOB_COUNT = DEFAULT_BG_NORMAL_JOB_COUNT;
        /**
         * The maximum number of background jobs we allow when the system is in a moderate
         * memory state.
         */
        int BG_MODERATE_JOB_COUNT = DEFAULT_BG_MODERATE_JOB_COUNT;
        /**
         * The maximum number of background jobs we allow when the system is in a low
         * memory state.
         */
        int BG_LOW_JOB_COUNT = DEFAULT_BG_LOW_JOB_COUNT;
        /**
         * The maximum number of background jobs we allow when the system is in a critical
         * memory state.
         */
        int BG_CRITICAL_JOB_COUNT = DEFAULT_BG_CRITICAL_JOB_COUNT;
        /**
         * The maximum number of times we allow a job to have itself rescheduled before
         * giving up on it, for standard jobs.
         */
        int MAX_STANDARD_RESCHEDULE_COUNT = DEFAULT_MAX_STANDARD_RESCHEDULE_COUNT;
        /**
         * The maximum number of times we allow a job to have itself rescheduled before
         * giving up on it, for jobs that are executing work.
         */
        int MAX_WORK_RESCHEDULE_COUNT = DEFAULT_MAX_WORK_RESCHEDULE_COUNT;
        /**
         * The minimum backoff time to allow for linear backoff.
         */
        long MIN_LINEAR_BACKOFF_TIME = DEFAULT_MIN_LINEAR_BACKOFF_TIME;
        /**
         * The minimum backoff time to allow for exponential backoff.
         */
        long MIN_EXP_BACKOFF_TIME = DEFAULT_MIN_EXP_BACKOFF_TIME;
        /**
         * How often we recalculate runnability based on apps' standby bucket assignment.
         * This should be prime relative to common time interval lengths such as a quarter-
         * hour or day, so that the heartbeat drifts relative to wall-clock milestones.
         */
        long STANDBY_HEARTBEAT_TIME = DEFAULT_STANDBY_HEARTBEAT_TIME;
        /**
         * Mapping: standby bucket -> number of heartbeats between each sweep of that
         * bucket's jobs.
         *
         * Bucket assignments as recorded in the JobStatus objects are normalized to be
         * indices into this array, rather than the raw constants used
         * by AppIdleHistory.
         */
        final int[] STANDBY_BEATS = {
                0,
                DEFAULT_STANDBY_WORKING_BEATS,
                DEFAULT_STANDBY_FREQUENT_BEATS,
                DEFAULT_STANDBY_RARE_BEATS
        };
        /**
         * The fraction of a job's running window that must pass before we
         * consider running it when the network is congested.
         */
        public float CONN_CONGESTION_DELAY_FRAC = DEFAULT_CONN_CONGESTION_DELAY_FRAC;
        /**
         * The fraction of a prefetch job's running window that must pass before
         * we consider matching it against a metered network.
         */
        public float CONN_PREFETCH_RELAX_FRAC = DEFAULT_CONN_PREFETCH_RELAX_FRAC;

        private final KeyValueListParser mParser = new KeyValueListParser(',');

        void updateConstantsLocked(String value) {
            try {
                mParser.setString(value);
            } catch (Exception e) {
                // Failed to parse the settings string, log this and move on
                // with defaults.
                Slog.e(TAG, "Bad jobscheduler settings", e);
            }

            MIN_IDLE_COUNT = mParser.getInt(KEY_MIN_IDLE_COUNT,
                    DEFAULT_MIN_IDLE_COUNT);
            MIN_CHARGING_COUNT = mParser.getInt(KEY_MIN_CHARGING_COUNT,
                    DEFAULT_MIN_CHARGING_COUNT);
            MIN_BATTERY_NOT_LOW_COUNT = mParser.getInt(KEY_MIN_BATTERY_NOT_LOW_COUNT,
                    DEFAULT_MIN_BATTERY_NOT_LOW_COUNT);
            MIN_STORAGE_NOT_LOW_COUNT = mParser.getInt(KEY_MIN_STORAGE_NOT_LOW_COUNT,
                    DEFAULT_MIN_STORAGE_NOT_LOW_COUNT);
            MIN_CONNECTIVITY_COUNT = mParser.getInt(KEY_MIN_CONNECTIVITY_COUNT,
                    DEFAULT_MIN_CONNECTIVITY_COUNT);
            MIN_CONTENT_COUNT = mParser.getInt(KEY_MIN_CONTENT_COUNT,
                    DEFAULT_MIN_CONTENT_COUNT);
            MIN_READY_JOBS_COUNT = mParser.getInt(KEY_MIN_READY_JOBS_COUNT,
                    DEFAULT_MIN_READY_JOBS_COUNT);
            HEAVY_USE_FACTOR = mParser.getFloat(KEY_HEAVY_USE_FACTOR,
                    DEFAULT_HEAVY_USE_FACTOR);
            MODERATE_USE_FACTOR = mParser.getFloat(KEY_MODERATE_USE_FACTOR,
                    DEFAULT_MODERATE_USE_FACTOR);
            FG_JOB_COUNT = mParser.getInt(KEY_FG_JOB_COUNT,
                    DEFAULT_FG_JOB_COUNT);
            BG_NORMAL_JOB_COUNT = mParser.getInt(KEY_BG_NORMAL_JOB_COUNT,
                    DEFAULT_BG_NORMAL_JOB_COUNT);
            if ((FG_JOB_COUNT+BG_NORMAL_JOB_COUNT) > MAX_JOB_CONTEXTS_COUNT) {
                BG_NORMAL_JOB_COUNT = MAX_JOB_CONTEXTS_COUNT - FG_JOB_COUNT;
            }
            BG_MODERATE_JOB_COUNT = mParser.getInt(KEY_BG_MODERATE_JOB_COUNT,
                    DEFAULT_BG_MODERATE_JOB_COUNT);
            if ((FG_JOB_COUNT+BG_MODERATE_JOB_COUNT) > MAX_JOB_CONTEXTS_COUNT) {
                BG_MODERATE_JOB_COUNT = MAX_JOB_CONTEXTS_COUNT - FG_JOB_COUNT;
            }
            BG_LOW_JOB_COUNT = mParser.getInt(KEY_BG_LOW_JOB_COUNT,
                    DEFAULT_BG_LOW_JOB_COUNT);
            if ((FG_JOB_COUNT+BG_LOW_JOB_COUNT) > MAX_JOB_CONTEXTS_COUNT) {
                BG_LOW_JOB_COUNT = MAX_JOB_CONTEXTS_COUNT - FG_JOB_COUNT;
            }
            BG_CRITICAL_JOB_COUNT = mParser.getInt(KEY_BG_CRITICAL_JOB_COUNT,
                    DEFAULT_BG_CRITICAL_JOB_COUNT);
            if ((FG_JOB_COUNT+BG_CRITICAL_JOB_COUNT) > MAX_JOB_CONTEXTS_COUNT) {
                BG_CRITICAL_JOB_COUNT = MAX_JOB_CONTEXTS_COUNT - FG_JOB_COUNT;
            }
            MAX_STANDARD_RESCHEDULE_COUNT = mParser.getInt(KEY_MAX_STANDARD_RESCHEDULE_COUNT,
                    DEFAULT_MAX_STANDARD_RESCHEDULE_COUNT);
            MAX_WORK_RESCHEDULE_COUNT = mParser.getInt(KEY_MAX_WORK_RESCHEDULE_COUNT,
                    DEFAULT_MAX_WORK_RESCHEDULE_COUNT);
            MIN_LINEAR_BACKOFF_TIME = mParser.getDurationMillis(KEY_MIN_LINEAR_BACKOFF_TIME,
                    DEFAULT_MIN_LINEAR_BACKOFF_TIME);
            MIN_EXP_BACKOFF_TIME = mParser.getDurationMillis(KEY_MIN_EXP_BACKOFF_TIME,
                    DEFAULT_MIN_EXP_BACKOFF_TIME);
            STANDBY_HEARTBEAT_TIME = mParser.getDurationMillis(KEY_STANDBY_HEARTBEAT_TIME,
                    DEFAULT_STANDBY_HEARTBEAT_TIME);
            STANDBY_BEATS[WORKING_INDEX] = mParser.getInt(KEY_STANDBY_WORKING_BEATS,
                    DEFAULT_STANDBY_WORKING_BEATS);
            STANDBY_BEATS[FREQUENT_INDEX] = mParser.getInt(KEY_STANDBY_FREQUENT_BEATS,
                    DEFAULT_STANDBY_FREQUENT_BEATS);
            STANDBY_BEATS[RARE_INDEX] = mParser.getInt(KEY_STANDBY_RARE_BEATS,
                    DEFAULT_STANDBY_RARE_BEATS);
            CONN_CONGESTION_DELAY_FRAC = mParser.getFloat(KEY_CONN_CONGESTION_DELAY_FRAC,
                    DEFAULT_CONN_CONGESTION_DELAY_FRAC);
            CONN_PREFETCH_RELAX_FRAC = mParser.getFloat(KEY_CONN_PREFETCH_RELAX_FRAC,
                    DEFAULT_CONN_PREFETCH_RELAX_FRAC);
        }

        void dump(IndentingPrintWriter pw) {
            pw.println("Settings:");
            pw.increaseIndent();
            pw.printPair(KEY_MIN_IDLE_COUNT, MIN_IDLE_COUNT).println();
            pw.printPair(KEY_MIN_CHARGING_COUNT, MIN_CHARGING_COUNT).println();
            pw.printPair(KEY_MIN_BATTERY_NOT_LOW_COUNT, MIN_BATTERY_NOT_LOW_COUNT).println();
            pw.printPair(KEY_MIN_STORAGE_NOT_LOW_COUNT, MIN_STORAGE_NOT_LOW_COUNT).println();
            pw.printPair(KEY_MIN_CONNECTIVITY_COUNT, MIN_CONNECTIVITY_COUNT).println();
            pw.printPair(KEY_MIN_CONTENT_COUNT, MIN_CONTENT_COUNT).println();
            pw.printPair(KEY_MIN_READY_JOBS_COUNT, MIN_READY_JOBS_COUNT).println();
            pw.printPair(KEY_HEAVY_USE_FACTOR, HEAVY_USE_FACTOR).println();
            pw.printPair(KEY_MODERATE_USE_FACTOR, MODERATE_USE_FACTOR).println();
            pw.printPair(KEY_FG_JOB_COUNT, FG_JOB_COUNT).println();
            pw.printPair(KEY_BG_NORMAL_JOB_COUNT, BG_NORMAL_JOB_COUNT).println();
            pw.printPair(KEY_BG_MODERATE_JOB_COUNT, BG_MODERATE_JOB_COUNT).println();
            pw.printPair(KEY_BG_LOW_JOB_COUNT, BG_LOW_JOB_COUNT).println();
            pw.printPair(KEY_BG_CRITICAL_JOB_COUNT, BG_CRITICAL_JOB_COUNT).println();
            pw.printPair(KEY_MAX_STANDARD_RESCHEDULE_COUNT, MAX_STANDARD_RESCHEDULE_COUNT).println();
            pw.printPair(KEY_MAX_WORK_RESCHEDULE_COUNT, MAX_WORK_RESCHEDULE_COUNT).println();
            pw.printPair(KEY_MIN_LINEAR_BACKOFF_TIME, MIN_LINEAR_BACKOFF_TIME).println();
            pw.printPair(KEY_MIN_EXP_BACKOFF_TIME, MIN_EXP_BACKOFF_TIME).println();
            pw.printPair(KEY_STANDBY_HEARTBEAT_TIME, STANDBY_HEARTBEAT_TIME).println();
            pw.print("standby_beats={");
            pw.print(STANDBY_BEATS[0]);
            for (int i = 1; i < STANDBY_BEATS.length; i++) {
                pw.print(", ");
                pw.print(STANDBY_BEATS[i]);
            }
            pw.println('}');
            pw.printPair(KEY_CONN_CONGESTION_DELAY_FRAC, CONN_CONGESTION_DELAY_FRAC).println();
            pw.printPair(KEY_CONN_PREFETCH_RELAX_FRAC, CONN_PREFETCH_RELAX_FRAC).println();
            pw.decreaseIndent();
        }

        void dump(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);
            proto.write(ConstantsProto.MIN_IDLE_COUNT, MIN_IDLE_COUNT);
            proto.write(ConstantsProto.MIN_CHARGING_COUNT, MIN_CHARGING_COUNT);
            proto.write(ConstantsProto.MIN_BATTERY_NOT_LOW_COUNT, MIN_BATTERY_NOT_LOW_COUNT);
            proto.write(ConstantsProto.MIN_STORAGE_NOT_LOW_COUNT, MIN_STORAGE_NOT_LOW_COUNT);
            proto.write(ConstantsProto.MIN_CONNECTIVITY_COUNT, MIN_CONNECTIVITY_COUNT);
            proto.write(ConstantsProto.MIN_CONTENT_COUNT, MIN_CONTENT_COUNT);
            proto.write(ConstantsProto.MIN_READY_JOBS_COUNT, MIN_READY_JOBS_COUNT);
            proto.write(ConstantsProto.HEAVY_USE_FACTOR, HEAVY_USE_FACTOR);
            proto.write(ConstantsProto.MODERATE_USE_FACTOR, MODERATE_USE_FACTOR);
            proto.write(ConstantsProto.FG_JOB_COUNT, FG_JOB_COUNT);
            proto.write(ConstantsProto.BG_NORMAL_JOB_COUNT, BG_NORMAL_JOB_COUNT);
            proto.write(ConstantsProto.BG_MODERATE_JOB_COUNT, BG_MODERATE_JOB_COUNT);
            proto.write(ConstantsProto.BG_LOW_JOB_COUNT, BG_LOW_JOB_COUNT);
            proto.write(ConstantsProto.BG_CRITICAL_JOB_COUNT, BG_CRITICAL_JOB_COUNT);
            proto.write(ConstantsProto.MAX_STANDARD_RESCHEDULE_COUNT, MAX_STANDARD_RESCHEDULE_COUNT);
            proto.write(ConstantsProto.MAX_WORK_RESCHEDULE_COUNT, MAX_WORK_RESCHEDULE_COUNT);
            proto.write(ConstantsProto.MIN_LINEAR_BACKOFF_TIME_MS, MIN_LINEAR_BACKOFF_TIME);
            proto.write(ConstantsProto.MIN_EXP_BACKOFF_TIME_MS, MIN_EXP_BACKOFF_TIME);
            proto.write(ConstantsProto.STANDBY_HEARTBEAT_TIME_MS, STANDBY_HEARTBEAT_TIME);
            for (int period : STANDBY_BEATS) {
                proto.write(ConstantsProto.STANDBY_BEATS, period);
            }
            proto.write(ConstantsProto.CONN_CONGESTION_DELAY_FRAC, CONN_CONGESTION_DELAY_FRAC);
            proto.write(ConstantsProto.CONN_PREFETCH_RELAX_FRAC, CONN_PREFETCH_RELAX_FRAC);
            proto.end(token);
        }
    }

    final Constants mConstants;
    final ConstantsObserver mConstantsObserver;

    static final Comparator<JobStatus> mEnqueueTimeComparator = (o1, o2) -> {
        if (o1.enqueueTime < o2.enqueueTime) {
            return -1;
        }
        return o1.enqueueTime > o2.enqueueTime ? 1 : 0;
    };

    static <T> void addOrderedItem(ArrayList<T> array, T newItem, Comparator<T> comparator) {
        int where = Collections.binarySearch(array, newItem, comparator);
        if (where < 0) {
            where = ~where;
        }
        array.add(where, newItem);
    }

    /**
     * Cleans up outstanding jobs when a package is removed. Even if it's being replaced later we
     * still clean up. On reinstall the package will have a new uid.
     */
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (DEBUG) {
                Slog.d(TAG, "Receieved: " + action);
            }
            final String pkgName = getPackageName(intent);
            final int pkgUid = intent.getIntExtra(Intent.EXTRA_UID, -1);

            if (Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
                // Purge the app's jobs if the whole package was just disabled.  When this is
                // the case the component name will be a bare package name.
                if (pkgName != null && pkgUid != -1) {
                    final String[] changedComponents = intent.getStringArrayExtra(
                            Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST);
                    if (changedComponents != null) {
                        for (String component : changedComponents) {
                            if (component.equals(pkgName)) {
                                if (DEBUG) {
                                    Slog.d(TAG, "Package state change: " + pkgName);
                                }
                                try {
                                    final int userId = UserHandle.getUserId(pkgUid);
                                    IPackageManager pm = AppGlobals.getPackageManager();
                                    final int state = pm.getApplicationEnabledSetting(pkgName, userId);
                                    if (state == COMPONENT_ENABLED_STATE_DISABLED
                                            || state ==  COMPONENT_ENABLED_STATE_DISABLED_USER) {
                                        if (DEBUG) {
                                            Slog.d(TAG, "Removing jobs for package " + pkgName
                                                    + " in user " + userId);
                                        }
                                        cancelJobsForPackageAndUid(pkgName, pkgUid,
                                                "app disabled");
                                    }
                                } catch (RemoteException|IllegalArgumentException e) {
                                    /*
                                     * IllegalArgumentException means that the package doesn't exist.
                                     * This arises when PACKAGE_CHANGED broadcast delivery has lagged
                                     * behind outright uninstall, so by the time we try to act it's gone.
                                     * We don't need to act on this PACKAGE_CHANGED when this happens;
                                     * we'll get a PACKAGE_REMOVED later and clean up then.
                                     *
                                     * RemoteException can't actually happen; the package manager is
                                     * running in this same process.
                                     */
                                }
                                break;
                            }
                        }
                    }
                } else {
                    Slog.w(TAG, "PACKAGE_CHANGED for " + pkgName + " / uid " + pkgUid);
                }
            } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                // If this is an outright uninstall rather than the first half of an
                // app update sequence, cancel the jobs associated with the app.
                if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    int uidRemoved = intent.getIntExtra(Intent.EXTRA_UID, -1);
                    if (DEBUG) {
                        Slog.d(TAG, "Removing jobs for uid: " + uidRemoved);
                    }
                    cancelJobsForPackageAndUid(pkgName, uidRemoved, "app uninstalled");
                }
            } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                if (DEBUG) {
                    Slog.d(TAG, "Removing jobs for user: " + userId);
                }
                cancelJobsForUser(userId);
            } else if (Intent.ACTION_QUERY_PACKAGE_RESTART.equals(action)) {
                // Has this package scheduled any jobs, such that we will take action
                // if it were to be force-stopped?
                if (pkgUid != -1) {
                    List<JobStatus> jobsForUid;
                    synchronized (mLock) {
                        jobsForUid = mJobs.getJobsByUid(pkgUid);
                    }
                    for (int i = jobsForUid.size() - 1; i >= 0; i--) {
                        if (jobsForUid.get(i).getSourcePackageName().equals(pkgName)) {
                            if (DEBUG) {
                                Slog.d(TAG, "Restart query: package " + pkgName + " at uid "
                                        + pkgUid + " has jobs");
                            }
                            setResultCode(Activity.RESULT_OK);
                            break;
                        }
                    }
                }
            } else if (Intent.ACTION_PACKAGE_RESTARTED.equals(action)) {
                // possible force-stop
                if (pkgUid != -1) {
                    if (DEBUG) {
                        Slog.d(TAG, "Removing jobs for pkg " + pkgName + " at uid " + pkgUid);
                    }
                    cancelJobsForPackageAndUid(pkgName, pkgUid, "app force stopped");
                }
            }
        }
    };

    private String getPackageName(Intent intent) {
        Uri uri = intent.getData();
        String pkg = uri != null ? uri.getSchemeSpecificPart() : null;
        return pkg;
    }

    final private IUidObserver mUidObserver = new IUidObserver.Stub() {
        @Override public void onUidStateChanged(int uid, int procState, long procStateSeq) {
            mHandler.obtainMessage(MSG_UID_STATE_CHANGED, uid, procState).sendToTarget();
        }

        @Override public void onUidGone(int uid, boolean disabled) {
            mHandler.obtainMessage(MSG_UID_GONE, uid, disabled ? 1 : 0).sendToTarget();
        }

        @Override public void onUidActive(int uid) throws RemoteException {
            mHandler.obtainMessage(MSG_UID_ACTIVE, uid, 0).sendToTarget();
        }

        @Override public void onUidIdle(int uid, boolean disabled) {
            mHandler.obtainMessage(MSG_UID_IDLE, uid, disabled ? 1 : 0).sendToTarget();
        }

        @Override public void onUidCachedChanged(int uid, boolean cached) {
        }
    };

    public Context getTestableContext() {
        return getContext();
    }

    public Object getLock() {
        return mLock;
    }

    public JobStore getJobStore() {
        return mJobs;
    }

    public Constants getConstants() {
        return mConstants;
    }

    @Override
    public void onStartUser(int userHandle) {
        mStartedUsers = ArrayUtils.appendInt(mStartedUsers, userHandle);
        // Let's kick any outstanding jobs for this user.
        mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();
    }

    @Override
    public void onUnlockUser(int userHandle) {
        // Let's kick any outstanding jobs for this user.
        mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();
    }

    @Override
    public void onStopUser(int userHandle) {
        mStartedUsers = ArrayUtils.removeInt(mStartedUsers, userHandle);
    }

    /**
     * Return whether an UID is active or idle.
     */
    private boolean isUidActive(int uid) {
        return mAppStateTracker.isUidActiveSynced(uid);
    }

    private final Predicate<Integer> mIsUidActivePredicate = this::isUidActive;

    public int scheduleAsPackage(JobInfo job, JobWorkItem work, int uId, String packageName,
            int userId, String tag) {
        try {
            if (ActivityManager.getService().isAppStartModeDisabled(uId,
                    job.getService().getPackageName())) {
                Slog.w(TAG, "Not scheduling job " + uId + ":" + job.toString()
                        + " -- package not allowed to start");
                return JobScheduler.RESULT_FAILURE;
            }
        } catch (RemoteException e) {
        }

        synchronized (mLock) {
            final JobStatus toCancel = mJobs.getJobByUidAndJobId(uId, job.getId());

            if (work != null && toCancel != null) {
                // Fast path: we are adding work to an existing job, and the JobInfo is not
                // changing.  We can just directly enqueue this work in to the job.
                if (toCancel.getJob().equals(job)) {

                    toCancel.enqueueWorkLocked(ActivityManager.getService(), work);

                    // If any of work item is enqueued when the source is in the foreground,
                    // exempt the entire job.
                    toCancel.maybeAddForegroundExemption(mIsUidActivePredicate);

                    return JobScheduler.RESULT_SUCCESS;
                }
            }

            JobStatus jobStatus = JobStatus.createFromJobInfo(job, uId, packageName, userId, tag);

            // Give exemption if the source is in the foreground just now.
            // Note if it's a sync job, this method is called on the handler so it's not exactly
            // the state when requestSync() was called, but that should be fine because of the
            // 1 minute foreground grace period.
            jobStatus.maybeAddForegroundExemption(mIsUidActivePredicate);

            if (DEBUG) Slog.d(TAG, "SCHEDULE: " + jobStatus.toShortString());
            // Jobs on behalf of others don't apply to the per-app job cap
            if (ENFORCE_MAX_JOBS && packageName == null) {
                if (mJobs.countJobsForUid(uId) > MAX_JOBS_PER_APP) {
                    Slog.w(TAG, "Too many jobs for uid " + uId);
                    throw new IllegalStateException("Apps may not schedule more than "
                                + MAX_JOBS_PER_APP + " distinct jobs");
                }
            }

            // This may throw a SecurityException.
            jobStatus.prepareLocked(ActivityManager.getService());

            if (work != null) {
                // If work has been supplied, enqueue it into the new job.
                jobStatus.enqueueWorkLocked(ActivityManager.getService(), work);
            }

            if (toCancel != null) {
                // Implicitly replaces the existing job record with the new instance
                cancelJobImplLocked(toCancel, jobStatus, "job rescheduled by app");
            } else {
                startTrackingJobLocked(jobStatus, null);
            }
            StatsLog.write_non_chained(StatsLog.SCHEDULED_JOB_STATE_CHANGED,
                    uId, null, jobStatus.getBatteryName(),
                    StatsLog.SCHEDULED_JOB_STATE_CHANGED__STATE__SCHEDULED,
                    JobProtoEnums.STOP_REASON_CANCELLED);

            // If the job is immediately ready to run, then we can just immediately
            // put it in the pending list and try to schedule it.  This is especially
            // important for jobs with a 0 deadline constraint, since they will happen a fair
            // amount, we want to handle them as quickly as possible, and semantically we want to
            // make sure we have started holding the wake lock for the job before returning to
            // the caller.
            // If the job is not yet ready to run, there is nothing more to do -- we are
            // now just waiting for one of its controllers to change state and schedule
            // the job appropriately.
            if (isReadyToBeExecutedLocked(jobStatus)) {
                // This is a new job, we can just immediately put it on the pending
                // list and try to run it.
                mJobPackageTracker.notePending(jobStatus);
                addOrderedItem(mPendingJobs, jobStatus, mEnqueueTimeComparator);
                maybeRunPendingJobsLocked();
            }
        }
        return JobScheduler.RESULT_SUCCESS;
    }

    public List<JobInfo> getPendingJobs(int uid) {
        synchronized (mLock) {
            List<JobStatus> jobs = mJobs.getJobsByUid(uid);
            ArrayList<JobInfo> outList = new ArrayList<JobInfo>(jobs.size());
            for (int i = jobs.size() - 1; i >= 0; i--) {
                JobStatus job = jobs.get(i);
                outList.add(job.getJob());
            }
            return outList;
        }
    }

    public JobInfo getPendingJob(int uid, int jobId) {
        synchronized (mLock) {
            List<JobStatus> jobs = mJobs.getJobsByUid(uid);
            for (int i = jobs.size() - 1; i >= 0; i--) {
                JobStatus job = jobs.get(i);
                if (job.getJobId() == jobId) {
                    return job.getJob();
                }
            }
            return null;
        }
    }

    void cancelJobsForUser(int userHandle) {
        synchronized (mLock) {
            final List<JobStatus> jobsForUser = mJobs.getJobsByUser(userHandle);
            for (int i=0; i<jobsForUser.size(); i++) {
                JobStatus toRemove = jobsForUser.get(i);
                cancelJobImplLocked(toRemove, null, "user removed");
            }
        }
    }

    private void cancelJobsForNonExistentUsers() {
        UserManagerInternal umi = LocalServices.getService(UserManagerInternal.class);
        synchronized (mLock) {
            mJobs.removeJobsOfNonUsers(umi.getUserIds());
        }
    }

    void cancelJobsForPackageAndUid(String pkgName, int uid, String reason) {
        if ("android".equals(pkgName)) {
            Slog.wtfStack(TAG, "Can't cancel all jobs for system package");
            return;
        }
        synchronized (mLock) {
            final List<JobStatus> jobsForUid = mJobs.getJobsByUid(uid);
            for (int i = jobsForUid.size() - 1; i >= 0; i--) {
                final JobStatus job = jobsForUid.get(i);
                if (job.getSourcePackageName().equals(pkgName)) {
                    cancelJobImplLocked(job, null, reason);
                }
            }
        }
    }

    /**
     * Entry point from client to cancel all jobs originating from their uid.
     * This will remove the job from the master list, and cancel the job if it was staged for
     * execution or being executed.
     * @param uid Uid to check against for removal of a job.
     *
     */
    public boolean cancelJobsForUid(int uid, String reason) {
        if (uid == Process.SYSTEM_UID) {
            Slog.wtfStack(TAG, "Can't cancel all jobs for system uid");
            return false;
        }

        boolean jobsCanceled = false;
        synchronized (mLock) {
            final List<JobStatus> jobsForUid = mJobs.getJobsByUid(uid);
            for (int i=0; i<jobsForUid.size(); i++) {
                JobStatus toRemove = jobsForUid.get(i);
                cancelJobImplLocked(toRemove, null, reason);
                jobsCanceled = true;
            }
        }
        return jobsCanceled;
    }

    /**
     * Entry point from client to cancel the job corresponding to the jobId provided.
     * This will remove the job from the master list, and cancel the job if it was staged for
     * execution or being executed.
     * @param uid Uid of the calling client.
     * @param jobId Id of the job, provided at schedule-time.
     */
    public boolean cancelJob(int uid, int jobId, int callingUid) {
        JobStatus toCancel;
        synchronized (mLock) {
            toCancel = mJobs.getJobByUidAndJobId(uid, jobId);
            if (toCancel != null) {
                cancelJobImplLocked(toCancel, null,
                        "cancel() called by app, callingUid=" + callingUid
                        + " uid=" + uid + " jobId=" + jobId);
            }
            return (toCancel != null);
        }
    }

    /**
     * Cancel the given job, stopping it if it's currently executing.  If {@code incomingJob}
     * is null, the cancelled job is removed outright from the system.  If
     * {@code incomingJob} is non-null, it replaces {@code cancelled} in the store of
     * currently scheduled jobs.
     */
    private void cancelJobImplLocked(JobStatus cancelled, JobStatus incomingJob, String reason) {
        if (DEBUG) Slog.d(TAG, "CANCEL: " + cancelled.toShortString());
        cancelled.unprepareLocked(ActivityManager.getService());
        stopTrackingJobLocked(cancelled, incomingJob, true /* writeBack */);
        // Remove from pending queue.
        if (mPendingJobs.remove(cancelled)) {
            mJobPackageTracker.noteNonpending(cancelled);
        }
        // Cancel if running.
        stopJobOnServiceContextLocked(cancelled, JobParameters.REASON_CANCELED, reason);
        // If this is a replacement, bring in the new version of the job
        if (incomingJob != null) {
            if (DEBUG) Slog.i(TAG, "Tracking replacement job " + incomingJob.toShortString());
            startTrackingJobLocked(incomingJob, cancelled);
        }
        reportActiveLocked();
    }

    void updateUidState(int uid, int procState) {
        synchronized (mLock) {
            if (procState == ActivityManager.PROCESS_STATE_TOP) {
                // Only use this if we are exactly the top app.  All others can live
                // with just the foreground priority.  This means that persistent processes
                // can never be the top app priority...  that is fine.
                mUidPriorityOverride.put(uid, JobInfo.PRIORITY_TOP_APP);
            } else if (procState <= ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE) {
                mUidPriorityOverride.put(uid, JobInfo.PRIORITY_FOREGROUND_APP);
            } else {
                mUidPriorityOverride.delete(uid);
            }
        }
    }

    @Override
    public void onDeviceIdleStateChanged(boolean deviceIdle) {
        synchronized (mLock) {
            if (deviceIdle) {
                // When becoming idle, make sure no jobs are actively running,
                // except those using the idle exemption flag.
                for (int i=0; i<mActiveServices.size(); i++) {
                    JobServiceContext jsc = mActiveServices.get(i);
                    final JobStatus executing = jsc.getRunningJobLocked();
                    if (executing != null
                            && (executing.getFlags() & JobInfo.FLAG_WILL_BE_FOREGROUND) == 0) {
                        jsc.cancelExecutingJobLocked(JobParameters.REASON_DEVICE_IDLE,
                                "cancelled due to doze");
                    }
                }
            } else {
                // When coming out of idle, allow thing to start back up.
                if (mReadyToRock) {
                    if (mLocalDeviceIdleController != null) {
                        if (!mReportedActive) {
                            mReportedActive = true;
                            mLocalDeviceIdleController.setJobsActive(true);
                        }
                    }
                    mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();
                }
            }
        }
    }

    void reportActiveLocked() {
        // active is true if pending queue contains jobs OR some job is running.
        boolean active = mPendingJobs.size() > 0;
        if (mPendingJobs.size() <= 0) {
            for (int i=0; i<mActiveServices.size(); i++) {
                final JobServiceContext jsc = mActiveServices.get(i);
                final JobStatus job = jsc.getRunningJobLocked();
                if (job != null
                        && (job.getJob().getFlags() & JobInfo.FLAG_WILL_BE_FOREGROUND) == 0
                        && !job.dozeWhitelisted
                        && !job.uidActive) {
                    // We will report active if we have a job running and it is not an exception
                    // due to being in the foreground or whitelisted.
                    active = true;
                    break;
                }
            }
        }

        if (mReportedActive != active) {
            mReportedActive = active;
            if (mLocalDeviceIdleController != null) {
                mLocalDeviceIdleController.setJobsActive(active);
            }
        }
    }

    void reportAppUsage(String packageName, int userId) {
        // This app just transitioned into interactive use or near equivalent, so we should
        // take a look at its job state for feedback purposes.
    }

    /**
     * Initializes the system service.
     * <p>
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     * </p>
     *
     * @param context The system server context.
     */
    public JobSchedulerService(Context context) {
        super(context);

        mLocalPM = LocalServices.getService(PackageManagerInternal.class);
        mActivityManagerInternal = Preconditions.checkNotNull(
                LocalServices.getService(ActivityManagerInternal.class));

        mHandler = new JobHandler(context.getMainLooper());
        mConstants = new Constants();
        mConstantsObserver = new ConstantsObserver(mHandler);
        mJobSchedulerStub = new JobSchedulerStub();

        // Set up the app standby bucketing tracker
        mStandbyTracker = new StandbyTracker();
        mUsageStats = LocalServices.getService(UsageStatsManagerInternal.class);
        mUsageStats.addAppIdleStateChangeListener(mStandbyTracker);

        // The job store needs to call back
        publishLocalService(JobSchedulerInternal.class, new LocalService());

        // Initialize the job store and set up any persisted jobs
        mJobs = JobStore.initAndGet(this);

        // Create the controllers.
        mControllers = new ArrayList<StateController>();
        mControllers.add(new ConnectivityController(this));
        mControllers.add(new TimeController(this));
        mControllers.add(new IdleController(this));
        mBatteryController = new BatteryController(this);
        mControllers.add(mBatteryController);
        mStorageController = new StorageController(this);
        mControllers.add(mStorageController);
        mControllers.add(new BackgroundJobsController(this));
        mControllers.add(new ContentObserverController(this));
        mDeviceIdleJobsController = new DeviceIdleJobsController(this);
        mControllers.add(mDeviceIdleJobsController);

        // If the job store determined that it can't yet reschedule persisted jobs,
        // we need to start watching the clock.
        if (!mJobs.jobTimesInflatedValid()) {
            Slog.w(TAG, "!!! RTC not yet good; tracking time updates for job scheduling");
            context.registerReceiver(mTimeSetReceiver, new IntentFilter(Intent.ACTION_TIME_CHANGED));
        }
    }

    private final BroadcastReceiver mTimeSetReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_TIME_CHANGED.equals(intent.getAction())) {
                // When we reach clock sanity, recalculate the temporal windows
                // of all affected jobs.
                if (mJobs.clockNowValidToInflate(sSystemClock.millis())) {
                    Slog.i(TAG, "RTC now valid; recalculating persisted job windows");

                    // We've done our job now, so stop watching the time.
                    context.unregisterReceiver(this);

                    // And kick off the work to update the affected jobs, using a secondary
                    // thread instead of chugging away here on the main looper thread.
                    FgThread.getHandler().post(mJobTimeUpdater);
                }
            }
        }
    };

    private final Runnable mJobTimeUpdater = () -> {
        final ArrayList<JobStatus> toRemove = new ArrayList<>();
        final ArrayList<JobStatus> toAdd = new ArrayList<>();
        synchronized (mLock) {
            // Note: we intentionally both look up the existing affected jobs and replace them
            // with recalculated ones inside the same lock lifetime.
            getJobStore().getRtcCorrectedJobsLocked(toAdd, toRemove);

            // Now, at each position [i], we have both the existing JobStatus
            // and the one that replaces it.
            final int N = toAdd.size();
            for (int i = 0; i < N; i++) {
                final JobStatus oldJob = toRemove.get(i);
                final JobStatus newJob = toAdd.get(i);
                if (DEBUG) {
                    Slog.v(TAG, "  replacing " + oldJob + " with " + newJob);
                }
                cancelJobImplLocked(oldJob, newJob, "deferred rtc calculation");
            }
        }
    };

    @Override
    public void onStart() {
        publishBinderService(Context.JOB_SCHEDULER_SERVICE, mJobSchedulerStub);
    }

    @Override
    public void onBootPhase(int phase) {
        if (PHASE_SYSTEM_SERVICES_READY == phase) {
            mConstantsObserver.start(getContext().getContentResolver());

            mAppStateTracker = Preconditions.checkNotNull(
                    LocalServices.getService(AppStateTracker.class));
            setNextHeartbeatAlarm();

            // Register br for package removals and user removals.
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
            filter.addAction(Intent.ACTION_QUERY_PACKAGE_RESTART);
            filter.addDataScheme("package");
            getContext().registerReceiverAsUser(
                    mBroadcastReceiver, UserHandle.ALL, filter, null, null);
            final IntentFilter userFilter = new IntentFilter(Intent.ACTION_USER_REMOVED);
            getContext().registerReceiverAsUser(
                    mBroadcastReceiver, UserHandle.ALL, userFilter, null, null);
            try {
                ActivityManager.getService().registerUidObserver(mUidObserver,
                        ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE
                        | ActivityManager.UID_OBSERVER_IDLE | ActivityManager.UID_OBSERVER_ACTIVE,
                        ActivityManager.PROCESS_STATE_UNKNOWN, null);
            } catch (RemoteException e) {
                // ignored; both services live in system_server
            }
            // Remove any jobs that are not associated with any of the current users.
            cancelJobsForNonExistentUsers();
        } else if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            synchronized (mLock) {
                // Let's go!
                mReadyToRock = true;
                mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService(
                        BatteryStats.SERVICE_NAME));
                mLocalDeviceIdleController
                        = LocalServices.getService(DeviceIdleController.LocalService.class);
                // Create the "runners".
                for (int i = 0; i < MAX_JOB_CONTEXTS_COUNT; i++) {
                    mActiveServices.add(
                            new JobServiceContext(this, mBatteryStats, mJobPackageTracker,
                                    getContext().getMainLooper()));
                }
                // Attach jobs to their controllers.
                mJobs.forEachJob((job) -> {
                    for (int controller = 0; controller < mControllers.size(); controller++) {
                        final StateController sc = mControllers.get(controller);
                        sc.maybeStartTrackingJobLocked(job, null);
                    }
                });
                // GO GO GO!
                mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();
            }
        }
    }

    /**
     * Called when we have a job status object that we need to insert in our
     * {@link com.android.server.job.JobStore}, and make sure all the relevant controllers know
     * about.
     */
    private void startTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        if (!jobStatus.isPreparedLocked()) {
            Slog.wtf(TAG, "Not yet prepared when started tracking: " + jobStatus);
        }
        jobStatus.enqueueTime = sElapsedRealtimeClock.millis();
        final boolean update = mJobs.add(jobStatus);
        if (mReadyToRock) {
            for (int i = 0; i < mControllers.size(); i++) {
                StateController controller = mControllers.get(i);
                if (update) {
                    controller.maybeStopTrackingJobLocked(jobStatus, null, true);
                }
                controller.maybeStartTrackingJobLocked(jobStatus, lastJob);
            }
        }
    }

    /**
     * Called when we want to remove a JobStatus object that we've finished executing. Returns the
     * object removed.
     */
    private boolean stopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
            boolean writeBack) {
        // Deal with any remaining work items in the old job.
        jobStatus.stopTrackingJobLocked(ActivityManager.getService(), incomingJob);

        // Remove from store as well as controllers.
        final boolean removed = mJobs.remove(jobStatus, writeBack);
        if (removed && mReadyToRock) {
            for (int i=0; i<mControllers.size(); i++) {
                StateController controller = mControllers.get(i);
                controller.maybeStopTrackingJobLocked(jobStatus, incomingJob, false);
            }
        }
        return removed;
    }

    private boolean stopJobOnServiceContextLocked(JobStatus job, int reason, String debugReason) {
        for (int i=0; i<mActiveServices.size(); i++) {
            JobServiceContext jsc = mActiveServices.get(i);
            final JobStatus executing = jsc.getRunningJobLocked();
            if (executing != null && executing.matches(job.getUid(), job.getJobId())) {
                jsc.cancelExecutingJobLocked(reason, debugReason);
                return true;
            }
        }
        return false;
    }

    /**
     * @param job JobStatus we are querying against.
     * @return Whether or not the job represented by the status object is currently being run or
     * is pending.
     */
    private boolean isCurrentlyActiveLocked(JobStatus job) {
        for (int i=0; i<mActiveServices.size(); i++) {
            JobServiceContext serviceContext = mActiveServices.get(i);
            final JobStatus running = serviceContext.getRunningJobLocked();
            if (running != null && running.matches(job.getUid(), job.getJobId())) {
                return true;
            }
        }
        return false;
    }

    void noteJobsPending(List<JobStatus> jobs) {
        for (int i = jobs.size() - 1; i >= 0; i--) {
            JobStatus job = jobs.get(i);
            mJobPackageTracker.notePending(job);
        }
    }

    void noteJobsNonpending(List<JobStatus> jobs) {
        for (int i = jobs.size() - 1; i >= 0; i--) {
            JobStatus job = jobs.get(i);
            mJobPackageTracker.noteNonpending(job);
        }
    }

    /**
     * Reschedules the given job based on the job's backoff policy. It doesn't make sense to
     * specify an override deadline on a failed job (the failed job will run even though it's not
     * ready), so we reschedule it with {@link JobStatus#NO_LATEST_RUNTIME}, but specify that any
     * ready job with {@link JobStatus#numFailures} > 0 will be executed.
     *
     * @param failureToReschedule Provided job status that we will reschedule.
     * @return A newly instantiated JobStatus with the same constraints as the last job except
     * with adjusted timing constraints.
     *
     * @see #maybeQueueReadyJobsForExecutionLocked
     */
    private JobStatus getRescheduleJobForFailureLocked(JobStatus failureToReschedule) {
        final long elapsedNowMillis = sElapsedRealtimeClock.millis();
        final JobInfo job = failureToReschedule.getJob();

        final long initialBackoffMillis = job.getInitialBackoffMillis();
        final int backoffAttempts = failureToReschedule.getNumFailures() + 1;
        long delayMillis;

        if (failureToReschedule.hasWorkLocked()) {
            if (backoffAttempts > mConstants.MAX_WORK_RESCHEDULE_COUNT) {
                Slog.w(TAG, "Not rescheduling " + failureToReschedule + ": attempt #"
                        + backoffAttempts + " > work limit "
                        + mConstants.MAX_STANDARD_RESCHEDULE_COUNT);
                return null;
            }
        } else if (backoffAttempts > mConstants.MAX_STANDARD_RESCHEDULE_COUNT) {
            Slog.w(TAG, "Not rescheduling " + failureToReschedule + ": attempt #"
                    + backoffAttempts + " > std limit " + mConstants.MAX_STANDARD_RESCHEDULE_COUNT);
            return null;
        }

        switch (job.getBackoffPolicy()) {
            case JobInfo.BACKOFF_POLICY_LINEAR: {
                long backoff = initialBackoffMillis;
                if (backoff < mConstants.MIN_LINEAR_BACKOFF_TIME) {
                    backoff = mConstants.MIN_LINEAR_BACKOFF_TIME;
                }
                delayMillis = backoff * backoffAttempts;
            } break;
            default:
                if (DEBUG) {
                    Slog.v(TAG, "Unrecognised back-off policy, defaulting to exponential.");
                }
            case JobInfo.BACKOFF_POLICY_EXPONENTIAL: {
                long backoff = initialBackoffMillis;
                if (backoff < mConstants.MIN_EXP_BACKOFF_TIME) {
                    backoff = mConstants.MIN_EXP_BACKOFF_TIME;
                }
                delayMillis = (long) Math.scalb(backoff, backoffAttempts - 1);
            } break;
        }
        delayMillis =
                Math.min(delayMillis, JobInfo.MAX_BACKOFF_DELAY_MILLIS);
        JobStatus newJob = new JobStatus(failureToReschedule, getCurrentHeartbeat(),
                elapsedNowMillis + delayMillis,
                JobStatus.NO_LATEST_RUNTIME, backoffAttempts,
                failureToReschedule.getLastSuccessfulRunTime(), sSystemClock.millis());
        for (int ic=0; ic<mControllers.size(); ic++) {
            StateController controller = mControllers.get(ic);
            controller.rescheduleForFailureLocked(newJob, failureToReschedule);
        }
        return newJob;
    }

    /**
     * Called after a periodic has executed so we can reschedule it. We take the last execution
     * time of the job to be the time of completion (i.e. the time at which this function is
     * called).
     * <p>This could be inaccurate b/c the job can run for as long as
     * {@link com.android.server.job.JobServiceContext#EXECUTING_TIMESLICE_MILLIS}, but will lead
     * to underscheduling at least, rather than if we had taken the last execution time to be the
     * start of the execution.
     * <p>Unlike a reschedule prior to execution, in this case we advance the next-heartbeat
     * tracking as though the job were newly-scheduled.
     * @return A new job representing the execution criteria for this instantiation of the
     * recurring job.
     */
    private JobStatus getRescheduleJobForPeriodic(JobStatus periodicToReschedule) {
        final long elapsedNow = sElapsedRealtimeClock.millis();
        // Compute how much of the period is remaining.
        long runEarly = 0L;

        // If this periodic was rescheduled it won't have a deadline.
        if (periodicToReschedule.hasDeadlineConstraint()) {
            runEarly = Math.max(periodicToReschedule.getLatestRunTimeElapsed() - elapsedNow, 0L);
        }
        long flex = periodicToReschedule.getJob().getFlexMillis();
        long period = periodicToReschedule.getJob().getIntervalMillis();
        long newLatestRuntimeElapsed = elapsedNow + runEarly + period;
        long newEarliestRunTimeElapsed = newLatestRuntimeElapsed - flex;

        if (DEBUG) {
            Slog.v(TAG, "Rescheduling executed periodic. New execution window [" +
                    newEarliestRunTimeElapsed/1000 + ", " + newLatestRuntimeElapsed/1000 + "]s");
        }
        return new JobStatus(periodicToReschedule, getCurrentHeartbeat(),
                newEarliestRunTimeElapsed, newLatestRuntimeElapsed,
                0 /* backoffAttempt */,
                sSystemClock.millis() /* lastSuccessfulRunTime */,
                periodicToReschedule.getLastFailedRunTime());
    }

    /*
     * We default to "long enough ago that every bucket's jobs are immediately runnable" to
     * avoid starvation of apps in uncommon-use buckets that might arise from repeated
     * reboot behavior.
     */
    long heartbeatWhenJobsLastRun(String packageName, final @UserIdInt int userId) {
        // The furthest back in pre-boot time that we need to bother with
        long heartbeat = -mConstants.STANDBY_BEATS[RARE_INDEX];
        boolean cacheHit = false;
        synchronized (mLock) {
            HashMap<String, Long> jobPackages = mLastJobHeartbeats.get(userId);
            if (jobPackages != null) {
                long cachedValue = jobPackages.getOrDefault(packageName, Long.MAX_VALUE);
                if (cachedValue < Long.MAX_VALUE) {
                    cacheHit = true;
                    heartbeat = cachedValue;
                }
            }
            if (!cacheHit) {
                // We haven't seen it yet; ask usage stats about it
                final long timeSinceJob = mUsageStats.getTimeSinceLastJobRun(packageName, userId);
                if (timeSinceJob < Long.MAX_VALUE) {
                    // Usage stats knows about it from before, so calculate back from that
                    // and go from there.
                    heartbeat = mHeartbeat - (timeSinceJob / mConstants.STANDBY_HEARTBEAT_TIME);
                }
                // If usage stats returned its "not found" MAX_VALUE, we still have the
                // negative default 'heartbeat' value we established above
                setLastJobHeartbeatLocked(packageName, userId, heartbeat);
            }
        }
        if (DEBUG_STANDBY) {
            Slog.v(TAG, "Last job heartbeat " + heartbeat + " for "
                    + packageName + "/" + userId);
        }
        return heartbeat;
    }

    long heartbeatWhenJobsLastRun(JobStatus job) {
        return heartbeatWhenJobsLastRun(job.getSourcePackageName(), job.getSourceUserId());
    }

    void setLastJobHeartbeatLocked(String packageName, int userId, long heartbeat) {
        HashMap<String, Long> jobPackages = mLastJobHeartbeats.get(userId);
        if (jobPackages == null) {
            jobPackages = new HashMap<>();
            mLastJobHeartbeats.put(userId, jobPackages);
        }
        jobPackages.put(packageName, heartbeat);
    }

    // JobCompletedListener implementations.

    /**
     * A job just finished executing. We fetch the
     * {@link com.android.server.job.controllers.JobStatus} from the store and depending on
     * whether we want to reschedule we re-add it to the controllers.
     * @param jobStatus Completed job.
     * @param needsReschedule Whether the implementing class should reschedule this job.
     */
    @Override
    public void onJobCompletedLocked(JobStatus jobStatus, boolean needsReschedule) {
        if (DEBUG) {
            Slog.d(TAG, "Completed " + jobStatus + ", reschedule=" + needsReschedule);
        }

        // If the job wants to be rescheduled, we first need to make the next upcoming
        // job so we can transfer any appropriate state over from the previous job when
        // we stop it.
        final JobStatus rescheduledJob = needsReschedule
                ? getRescheduleJobForFailureLocked(jobStatus) : null;

        // Do not write back immediately if this is a periodic job. The job may get lost if system
        // shuts down before it is added back.
        if (!stopTrackingJobLocked(jobStatus, rescheduledJob, !jobStatus.getJob().isPeriodic())) {
            if (DEBUG) {
                Slog.d(TAG, "Could not find job to remove. Was job removed while executing?");
            }
            // We still want to check for jobs to execute, because this job may have
            // scheduled a new job under the same job id, and now we can run it.
            mHandler.obtainMessage(MSG_CHECK_JOB_GREEDY).sendToTarget();
            return;
        }

        if (rescheduledJob != null) {
            try {
                rescheduledJob.prepareLocked(ActivityManager.getService());
            } catch (SecurityException e) {
                Slog.w(TAG, "Unable to regrant job permissions for " + rescheduledJob);
            }
            startTrackingJobLocked(rescheduledJob, jobStatus);
        } else if (jobStatus.getJob().isPeriodic()) {
            JobStatus rescheduledPeriodic = getRescheduleJobForPeriodic(jobStatus);
            try {
                rescheduledPeriodic.prepareLocked(ActivityManager.getService());
            } catch (SecurityException e) {
                Slog.w(TAG, "Unable to regrant job permissions for " + rescheduledPeriodic);
            }
            startTrackingJobLocked(rescheduledPeriodic, jobStatus);
        }
        jobStatus.unprepareLocked(ActivityManager.getService());
        reportActiveLocked();
        mHandler.obtainMessage(MSG_CHECK_JOB_GREEDY).sendToTarget();
    }

    // StateChangedListener implementations.

    /**
     * Posts a message to the {@link com.android.server.job.JobSchedulerService.JobHandler} that
     * some controller's state has changed, so as to run through the list of jobs and start/stop
     * any that are eligible.
     */
    @Override
    public void onControllerStateChanged() {
        mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();
    }

    @Override
    public void onRunJobNow(JobStatus jobStatus) {
        mHandler.obtainMessage(MSG_JOB_EXPIRED, jobStatus).sendToTarget();
    }

    final private class JobHandler extends Handler {

        public JobHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            synchronized (mLock) {
                if (!mReadyToRock) {
                    return;
                }
                switch (message.what) {
                    case MSG_JOB_EXPIRED: {
                        JobStatus runNow = (JobStatus) message.obj;
                        // runNow can be null, which is a controller's way of indicating that its
                        // state is such that all ready jobs should be run immediately.
                        if (runNow != null && isReadyToBeExecutedLocked(runNow)) {
                            mJobPackageTracker.notePending(runNow);
                            addOrderedItem(mPendingJobs, runNow, mEnqueueTimeComparator);
                        } else {
                            queueReadyJobsForExecutionLocked();
                        }
                    } break;
                    case MSG_CHECK_JOB:
                        removeMessages(MSG_CHECK_JOB);
                        if (mReportedActive) {
                            // if jobs are currently being run, queue all ready jobs for execution.
                            queueReadyJobsForExecutionLocked();
                        } else {
                            // Check the list of jobs and run some of them if we feel inclined.
                            maybeQueueReadyJobsForExecutionLocked();
                        }
                        break;
                    case MSG_CHECK_JOB_GREEDY:
                        queueReadyJobsForExecutionLocked();
                        break;
                    case MSG_STOP_JOB:
                        cancelJobImplLocked((JobStatus) message.obj, null,
                                "app no longer allowed to run");
                        break;

                    case MSG_UID_STATE_CHANGED: {
                        final int uid = message.arg1;
                        final int procState = message.arg2;
                        updateUidState(uid, procState);
                        break;
                    }
                    case MSG_UID_GONE: {
                        final int uid = message.arg1;
                        final boolean disabled = message.arg2 != 0;
                        updateUidState(uid, ActivityManager.PROCESS_STATE_CACHED_EMPTY);
                        if (disabled) {
                            cancelJobsForUid(uid, "uid gone");
                        }
                        synchronized (mLock) {
                            mDeviceIdleJobsController.setUidActiveLocked(uid, false);
                        }
                        break;
                    }
                    case MSG_UID_ACTIVE: {
                        final int uid = message.arg1;
                        synchronized (mLock) {
                            mDeviceIdleJobsController.setUidActiveLocked(uid, true);
                        }
                        break;
                    }
                    case MSG_UID_IDLE: {
                        final int uid = message.arg1;
                        final boolean disabled = message.arg2 != 0;
                        if (disabled) {
                            cancelJobsForUid(uid, "app uid idle");
                        }
                        synchronized (mLock) {
                            mDeviceIdleJobsController.setUidActiveLocked(uid, false);
                        }
                        break;
                    }

                }
                maybeRunPendingJobsLocked();
                // Don't remove JOB_EXPIRED in case one came along while processing the queue.
            }
        }
    }

    private void stopNonReadyActiveJobsLocked() {
        for (int i=0; i<mActiveServices.size(); i++) {
            JobServiceContext serviceContext = mActiveServices.get(i);
            final JobStatus running = serviceContext.getRunningJobLocked();
            if (running != null && !running.isReady()) {
                serviceContext.cancelExecutingJobLocked(
                        JobParameters.REASON_CONSTRAINTS_NOT_SATISFIED,
                        "cancelled due to unsatisfied constraints");
            }
        }
    }

    /**
     * Run through list of jobs and execute all possible - at least one is expired so we do
     * as many as we can.
     */
    private void queueReadyJobsForExecutionLocked() {
        if (DEBUG) {
            Slog.d(TAG, "queuing all ready jobs for execution:");
        }
        noteJobsNonpending(mPendingJobs);
        mPendingJobs.clear();
        stopNonReadyActiveJobsLocked();
        mJobs.forEachJob(mReadyQueueFunctor);
        mReadyQueueFunctor.postProcess();

        if (DEBUG) {
            final int queuedJobs = mPendingJobs.size();
            if (queuedJobs == 0) {
                Slog.d(TAG, "No jobs pending.");
            } else {
                Slog.d(TAG, queuedJobs + " jobs queued.");
            }
        }
    }

    final class ReadyJobQueueFunctor implements Consumer<JobStatus> {
        ArrayList<JobStatus> newReadyJobs;

        @Override
        public void accept(JobStatus job) {
            if (isReadyToBeExecutedLocked(job)) {
                if (DEBUG) {
                    Slog.d(TAG, "    queued " + job.toShortString());
                }
                if (newReadyJobs == null) {
                    newReadyJobs = new ArrayList<JobStatus>();
                }
                newReadyJobs.add(job);
            }
        }

        public void postProcess() {
            if (newReadyJobs != null) {
                noteJobsPending(newReadyJobs);
                mPendingJobs.addAll(newReadyJobs);
                if (mPendingJobs.size() > 1) {
                    mPendingJobs.sort(mEnqueueTimeComparator);
                }
            }
            newReadyJobs = null;
        }
    }
    private final ReadyJobQueueFunctor mReadyQueueFunctor = new ReadyJobQueueFunctor();

    /**
     * The state of at least one job has changed. Here is where we could enforce various
     * policies on when we want to execute jobs.
     * Right now the policy is such:
     * If >1 of the ready jobs is idle mode we send all of them off
     * if more than 2 network connectivity jobs are ready we send them all off.
     * If more than 4 jobs total are ready we send them all off.
     * TODO: It would be nice to consolidate these sort of high-level policies somewhere.
     */
    final class MaybeReadyJobQueueFunctor implements Consumer<JobStatus> {
        int chargingCount;
        int batteryNotLowCount;
        int storageNotLowCount;
        int idleCount;
        int backoffCount;
        int connectivityCount;
        int contentCount;
        List<JobStatus> runnableJobs;

        public MaybeReadyJobQueueFunctor() {
            reset();
        }

        // Functor method invoked for each job via JobStore.forEachJob()
        @Override
        public void accept(JobStatus job) {
            if (isReadyToBeExecutedLocked(job)) {
                try {
                    if (ActivityManager.getService().isAppStartModeDisabled(job.getUid(),
                            job.getJob().getService().getPackageName())) {
                        Slog.w(TAG, "Aborting job " + job.getUid() + ":"
                                + job.getJob().toString() + " -- package not allowed to start");
                        mHandler.obtainMessage(MSG_STOP_JOB, job).sendToTarget();
                        return;
                    }
                } catch (RemoteException e) {
                }
                if (job.getNumFailures() > 0) {
                    backoffCount++;
                }
                if (job.hasIdleConstraint()) {
                    idleCount++;
                }
                if (job.hasConnectivityConstraint()) {
                    connectivityCount++;
                }
                if (job.hasChargingConstraint()) {
                    chargingCount++;
                }
                if (job.hasBatteryNotLowConstraint()) {
                    batteryNotLowCount++;
                }
                if (job.hasStorageNotLowConstraint()) {
                    storageNotLowCount++;
                }
                if (job.hasContentTriggerConstraint()) {
                    contentCount++;
                }
                if (runnableJobs == null) {
                    runnableJobs = new ArrayList<>();
                }
                runnableJobs.add(job);
            }
        }

        public void postProcess() {
            if (backoffCount > 0 ||
                    idleCount >= mConstants.MIN_IDLE_COUNT ||
                    connectivityCount >= mConstants.MIN_CONNECTIVITY_COUNT ||
                    chargingCount >= mConstants.MIN_CHARGING_COUNT ||
                    batteryNotLowCount >= mConstants.MIN_BATTERY_NOT_LOW_COUNT ||
                    storageNotLowCount >= mConstants.MIN_STORAGE_NOT_LOW_COUNT ||
                    contentCount >= mConstants.MIN_CONTENT_COUNT ||
                    (runnableJobs != null
                            && runnableJobs.size() >= mConstants.MIN_READY_JOBS_COUNT)) {
                if (DEBUG) {
                    Slog.d(TAG, "maybeQueueReadyJobsForExecutionLocked: Running jobs.");
                }
                noteJobsPending(runnableJobs);
                mPendingJobs.addAll(runnableJobs);
                if (mPendingJobs.size() > 1) {
                    mPendingJobs.sort(mEnqueueTimeComparator);
                }
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "maybeQueueReadyJobsForExecutionLocked: Not running anything.");
                }
            }

            // Be ready for next time
            reset();
        }

        private void reset() {
            chargingCount = 0;
            idleCount =  0;
            backoffCount = 0;
            connectivityCount = 0;
            batteryNotLowCount = 0;
            storageNotLowCount = 0;
            contentCount = 0;
            runnableJobs = null;
        }
    }
    private final MaybeReadyJobQueueFunctor mMaybeQueueFunctor = new MaybeReadyJobQueueFunctor();

    private void maybeQueueReadyJobsForExecutionLocked() {
        if (DEBUG) Slog.d(TAG, "Maybe queuing ready jobs...");

        noteJobsNonpending(mPendingJobs);
        mPendingJobs.clear();
        stopNonReadyActiveJobsLocked();
        mJobs.forEachJob(mMaybeQueueFunctor);
        mMaybeQueueFunctor.postProcess();
    }

    /**
     * Heartbeat tracking.  The heartbeat alarm is intentionally non-wakeup.
     */
    class HeartbeatAlarmListener implements AlarmManager.OnAlarmListener {

        @Override
        public void onAlarm() {
            synchronized (mLock) {
                final long sinceLast = sElapsedRealtimeClock.millis() - mLastHeartbeatTime;
                final long beatsElapsed = sinceLast / mConstants.STANDBY_HEARTBEAT_TIME;
                if (beatsElapsed > 0) {
                    mLastHeartbeatTime += beatsElapsed * mConstants.STANDBY_HEARTBEAT_TIME;
                    advanceHeartbeatLocked(beatsElapsed);
                }
            }
            setNextHeartbeatAlarm();
        }
    }

    // Intentionally does not touch the alarm timing
    void advanceHeartbeatLocked(long beatsElapsed) {
        mHeartbeat += beatsElapsed;
        if (DEBUG_STANDBY) {
            Slog.v(TAG, "Advancing standby heartbeat by " + beatsElapsed
                    + " to " + mHeartbeat);
        }
        // Don't update ACTIVE or NEVER bucket milestones.  Note that mHeartbeat
        // will be equal to mNextBucketHeartbeat[bucket] for one beat, during which
        // new jobs scheduled by apps in that bucket will be permitted to run
        // immediately.
        boolean didAdvanceBucket = false;
        for (int i = 1; i < mNextBucketHeartbeat.length - 1; i++) {
            // Did we reach or cross a bucket boundary?
            if (mHeartbeat >= mNextBucketHeartbeat[i]) {
                didAdvanceBucket = true;
            }
            while (mHeartbeat > mNextBucketHeartbeat[i]) {
                mNextBucketHeartbeat[i] += mConstants.STANDBY_BEATS[i];
            }
            if (DEBUG_STANDBY) {
                Slog.v(TAG, "   Bucket " + i + " next heartbeat "
                        + mNextBucketHeartbeat[i]);
            }
        }

        if (didAdvanceBucket) {
            if (DEBUG_STANDBY) {
                Slog.v(TAG, "Hit bucket boundary; reevaluating job runnability");
            }
            mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();
        }
    }

    void setNextHeartbeatAlarm() {
        final long heartbeatLength;
        synchronized (mLock) {
            heartbeatLength = mConstants.STANDBY_HEARTBEAT_TIME;
        }
        final long now = sElapsedRealtimeClock.millis();
        final long nextBeatOrdinal = (now + heartbeatLength) / heartbeatLength;
        final long nextHeartbeat = nextBeatOrdinal * heartbeatLength;
        if (DEBUG_STANDBY) {
            Slog.i(TAG, "Setting heartbeat alarm for " + nextHeartbeat
                    + " = " + TimeUtils.formatDuration(nextHeartbeat - now));
        }
        AlarmManager am = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
        am.setExact(AlarmManager.ELAPSED_REALTIME, nextHeartbeat,
                HEARTBEAT_TAG, mHeartbeatAlarm, mHandler);
    }

    /**
     * Criteria for moving a job into the pending queue:
     *      - It's ready.
     *      - It's not pending.
     *      - It's not already running on a JSC.
     *      - The user that requested the job is running.
     *      - The job's standby bucket has come due to be runnable.
     *      - The component is enabled and runnable.
     */
    private boolean isReadyToBeExecutedLocked(JobStatus job) {
        final boolean jobReady = job.isReady();

        if (DEBUG) {
            Slog.v(TAG, "isReadyToBeExecutedLocked: " + job.toShortString()
                    + " ready=" + jobReady);
        }

        // This is a condition that is very likely to be false (most jobs that are
        // scheduled are sitting there, not ready yet) and very cheap to check (just
        // a few conditions on data in JobStatus).
        if (!jobReady) {
            if (job.getSourcePackageName().equals("android.jobscheduler.cts.jobtestapp")) {
                Slog.v(TAG, "    NOT READY: " + job);
            }
            return false;
        }

        final boolean jobExists = mJobs.containsJob(job);

        final int userId = job.getUserId();
        final boolean userStarted = ArrayUtils.contains(mStartedUsers, userId);

        if (DEBUG) {
            Slog.v(TAG, "isReadyToBeExecutedLocked: " + job.toShortString()
                    + " exists=" + jobExists + " userStarted=" + userStarted);
        }

        // These are also fairly cheap to check, though they typically will not
        // be conditions we fail.
        if (!jobExists || !userStarted) {
            return false;
        }

        final boolean jobPending = mPendingJobs.contains(job);
        final boolean jobActive = isCurrentlyActiveLocked(job);

        if (DEBUG) {
            Slog.v(TAG, "isReadyToBeExecutedLocked: " + job.toShortString()
                    + " pending=" + jobPending + " active=" + jobActive);
        }

        // These can be a little more expensive (especially jobActive, since we need to
        // go through the array of all potentially active jobs), so we are doing them
        // later...  but still before checking with the package manager!
        if (jobPending || jobActive) {
            return false;
        }

        // If the app is in a non-active standby bucket, make sure we've waited
        // an appropriate amount of time since the last invocation.  During device-
        // wide parole, standby bucketing is ignored.
        //
        // Jobs in 'active' apps are not subject to standby, nor are jobs that are
        // specifically marked as exempt.
        if (DEBUG_STANDBY) {
            Slog.v(TAG, "isReadyToBeExecutedLocked: " + job.toShortString()
                    + " parole=" + mInParole + " active=" + job.uidActive
                    + " exempt=" + job.getJob().isExemptedFromAppStandby());
        }
        if (!mInParole
                && !job.uidActive
                && !job.getJob().isExemptedFromAppStandby()) {
            final int bucket = job.getStandbyBucket();
            if (DEBUG_STANDBY) {
                Slog.v(TAG, "  bucket=" + bucket + " heartbeat=" + mHeartbeat
                        + " next=" + mNextBucketHeartbeat[bucket]);
            }
            if (mHeartbeat < mNextBucketHeartbeat[bucket]) {
                // Only skip this job if the app is still waiting for the end of its nominal
                // bucket interval.  Once it's waited that long, we let it go ahead and clear.
                // The final (NEVER) bucket is special; we never age those apps' jobs into
                // runnability.
                final long appLastRan = heartbeatWhenJobsLastRun(job);
                if (bucket >= mConstants.STANDBY_BEATS.length
                        || (mHeartbeat > appLastRan
                                && mHeartbeat < appLastRan + mConstants.STANDBY_BEATS[bucket])) {
                    // TODO: log/trace that we're deferring the job due to bucketing if we hit this
                    if (job.getWhenStandbyDeferred() == 0) {
                        if (DEBUG_STANDBY) {
                            Slog.v(TAG, "Bucket deferral: " + mHeartbeat + " < "
                                    + (appLastRan + mConstants.STANDBY_BEATS[bucket])
                                    + " for " + job);
                        }
                        job.setWhenStandbyDeferred(sElapsedRealtimeClock.millis());
                    }
                    return false;
                } else {
                    if (DEBUG_STANDBY) {
                        Slog.v(TAG, "Bucket deferred job aged into runnability at "
                                + mHeartbeat + " : " + job);
                    }
                }
            }
        }

        // The expensive check last: validate that the defined package+service is
        // still present & viable.
        final boolean componentPresent;
        try {
            componentPresent = (AppGlobals.getPackageManager().getServiceInfo(
                    job.getServiceComponent(), PackageManager.MATCH_DEBUG_TRIAGED_MISSING,
                    userId) != null);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }

        if (DEBUG) {
            Slog.v(TAG, "isReadyToBeExecutedLocked: " + job.toShortString()
                    + " componentPresent=" + componentPresent);
        }

        // Everything else checked out so far, so this is the final yes/no check
        return componentPresent;
    }

    /**
     * Reconcile jobs in the pending queue against available execution contexts.
     * A controller can force a job into the pending queue even if it's already running, but
     * here is where we decide whether to actually execute it.
     */
    private void maybeRunPendingJobsLocked() {
        if (DEBUG) {
            Slog.d(TAG, "pending queue: " + mPendingJobs.size() + " jobs.");
        }
        assignJobsToContextsLocked();
        reportActiveLocked();
    }

    private int adjustJobPriority(int curPriority, JobStatus job) {
        if (curPriority < JobInfo.PRIORITY_TOP_APP) {
            float factor = mJobPackageTracker.getLoadFactor(job);
            if (factor >= mConstants.HEAVY_USE_FACTOR) {
                curPriority += JobInfo.PRIORITY_ADJ_ALWAYS_RUNNING;
            } else if (factor >= mConstants.MODERATE_USE_FACTOR) {
                curPriority += JobInfo.PRIORITY_ADJ_OFTEN_RUNNING;
            }
        }
        return curPriority;
    }

    private int evaluateJobPriorityLocked(JobStatus job) {
        int priority = job.getPriority();
        if (priority >= JobInfo.PRIORITY_FOREGROUND_APP) {
            return adjustJobPriority(priority, job);
        }
        int override = mUidPriorityOverride.get(job.getSourceUid(), 0);
        if (override != 0) {
            return adjustJobPriority(override, job);
        }
        return adjustJobPriority(priority, job);
    }

    /**
     * Takes jobs from pending queue and runs them on available contexts.
     * If no contexts are available, preempts lower priority jobs to
     * run higher priority ones.
     * Lock on mJobs before calling this function.
     */
    private void assignJobsToContextsLocked() {
        if (DEBUG) {
            Slog.d(TAG, printPendingQueue());
        }

        int memLevel;
        try {
            memLevel = ActivityManager.getService().getMemoryTrimLevel();
        } catch (RemoteException e) {
            memLevel = ProcessStats.ADJ_MEM_FACTOR_NORMAL;
        }
        switch (memLevel) {
            case ProcessStats.ADJ_MEM_FACTOR_MODERATE:
                mMaxActiveJobs = mConstants.BG_MODERATE_JOB_COUNT;
                break;
            case ProcessStats.ADJ_MEM_FACTOR_LOW:
                mMaxActiveJobs = mConstants.BG_LOW_JOB_COUNT;
                break;
            case ProcessStats.ADJ_MEM_FACTOR_CRITICAL:
                mMaxActiveJobs = mConstants.BG_CRITICAL_JOB_COUNT;
                break;
            default:
                mMaxActiveJobs = mConstants.BG_NORMAL_JOB_COUNT;
                break;
        }

        JobStatus[] contextIdToJobMap = mTmpAssignContextIdToJobMap;
        boolean[] act = mTmpAssignAct;
        int[] preferredUidForContext = mTmpAssignPreferredUidForContext;
        int numActive = 0;
        int numForeground = 0;
        for (int i=0; i<MAX_JOB_CONTEXTS_COUNT; i++) {
            final JobServiceContext js = mActiveServices.get(i);
            final JobStatus status = js.getRunningJobLocked();
            if ((contextIdToJobMap[i] = status) != null) {
                numActive++;
                if (status.lastEvaluatedPriority >= JobInfo.PRIORITY_TOP_APP) {
                    numForeground++;
                }
            }
            act[i] = false;
            preferredUidForContext[i] = js.getPreferredUid();
        }
        if (DEBUG) {
            Slog.d(TAG, printContextIdToJobMap(contextIdToJobMap, "running jobs initial"));
        }
        for (int i=0; i<mPendingJobs.size(); i++) {
            JobStatus nextPending = mPendingJobs.get(i);

            // If job is already running, go to next job.
            int jobRunningContext = findJobContextIdFromMap(nextPending, contextIdToJobMap);
            if (jobRunningContext != -1) {
                continue;
            }

            final int priority = evaluateJobPriorityLocked(nextPending);
            nextPending.lastEvaluatedPriority = priority;

            // Find a context for nextPending. The context should be available OR
            // it should have lowest priority among all running jobs
            // (sharing the same Uid as nextPending)
            int minPriority = Integer.MAX_VALUE;
            int minPriorityContextId = -1;
            for (int j=0; j<MAX_JOB_CONTEXTS_COUNT; j++) {
                JobStatus job = contextIdToJobMap[j];
                int preferredUid = preferredUidForContext[j];
                if (job == null) {
                    if ((numActive < mMaxActiveJobs ||
                            (priority >= JobInfo.PRIORITY_TOP_APP &&
                                    numForeground < mConstants.FG_JOB_COUNT)) &&
                            (preferredUid == nextPending.getUid() ||
                                    preferredUid == JobServiceContext.NO_PREFERRED_UID)) {
                        // This slot is free, and we haven't yet hit the limit on
                        // concurrent jobs...  we can just throw the job in to here.
                        minPriorityContextId = j;
                        break;
                    }
                    // No job on this context, but nextPending can't run here because
                    // the context has a preferred Uid or we have reached the limit on
                    // concurrent jobs.
                    continue;
                }
                if (job.getUid() != nextPending.getUid()) {
                    continue;
                }
                if (evaluateJobPriorityLocked(job) >= nextPending.lastEvaluatedPriority) {
                    continue;
                }
                if (minPriority > nextPending.lastEvaluatedPriority) {
                    minPriority = nextPending.lastEvaluatedPriority;
                    minPriorityContextId = j;
                }
            }
            if (minPriorityContextId != -1) {
                contextIdToJobMap[minPriorityContextId] = nextPending;
                act[minPriorityContextId] = true;
                numActive++;
                if (priority >= JobInfo.PRIORITY_TOP_APP) {
                    numForeground++;
                }
            }
        }
        if (DEBUG) {
            Slog.d(TAG, printContextIdToJobMap(contextIdToJobMap, "running jobs final"));
        }
        mJobPackageTracker.noteConcurrency(numActive, numForeground);
        for (int i=0; i<MAX_JOB_CONTEXTS_COUNT; i++) {
            boolean preservePreferredUid = false;
            if (act[i]) {
                JobStatus js = mActiveServices.get(i).getRunningJobLocked();
                if (js != null) {
                    if (DEBUG) {
                        Slog.d(TAG, "preempting job: " + mActiveServices.get(i).getRunningJobLocked());
                    }
                    // preferredUid will be set to uid of currently running job.
                    mActiveServices.get(i).preemptExecutingJobLocked();
                    preservePreferredUid = true;
                } else {
                    final JobStatus pendingJob = contextIdToJobMap[i];
                    if (DEBUG) {
                        Slog.d(TAG, "About to run job on context "
                                + String.valueOf(i) + ", job: " + pendingJob);
                    }
                    for (int ic=0; ic<mControllers.size(); ic++) {
                        mControllers.get(ic).prepareForExecutionLocked(pendingJob);
                    }
                    if (!mActiveServices.get(i).executeRunnableJob(pendingJob)) {
                        Slog.d(TAG, "Error executing " + pendingJob);
                    }
                    if (mPendingJobs.remove(pendingJob)) {
                        mJobPackageTracker.noteNonpending(pendingJob);
                    }
                }
            }
            if (!preservePreferredUid) {
                mActiveServices.get(i).clearPreferredUid();
            }
        }
    }

    int findJobContextIdFromMap(JobStatus jobStatus, JobStatus[] map) {
        for (int i=0; i<map.length; i++) {
            if (map[i] != null && map[i].matches(jobStatus.getUid(), jobStatus.getJobId())) {
                return i;
            }
        }
        return -1;
    }

    final class LocalService implements JobSchedulerInternal {

        /**
         * The current bucket heartbeat ordinal
         */
        public long currentHeartbeat() {
            return getCurrentHeartbeat();
        }

        /**
         * Heartbeat ordinal at which the given standby bucket's jobs next become runnable
         */
        public long nextHeartbeatForBucket(int bucket) {
            synchronized (mLock) {
                return mNextBucketHeartbeat[bucket];
            }
        }

        /**
         * Heartbeat ordinal for the given app.  This is typically the heartbeat at which
         * the app last ran jobs, so that a newly-scheduled job in an app that hasn't run
         * jobs in a long time is immediately runnable even if the app is bucketed into
         * an infrequent time allocation.
         */
        public long baseHeartbeatForApp(String packageName, @UserIdInt int userId,
                final int appStandbyBucket) {
            if (appStandbyBucket == 0 ||
                    appStandbyBucket >= mConstants.STANDBY_BEATS.length) {
                // ACTIVE => everything can be run right away
                // NEVER => we won't run them anyway, so let them go in the future
                // as soon as the app enters normal use
                if (DEBUG_STANDBY) {
                    Slog.v(TAG, "Base heartbeat forced ZERO for new job in "
                            + packageName + "/" + userId);
                }
                return 0;
            }

            final long baseHeartbeat = heartbeatWhenJobsLastRun(packageName, userId);
            if (DEBUG_STANDBY) {
                Slog.v(TAG, "Base heartbeat " + baseHeartbeat + " for new job in "
                        + packageName + "/" + userId);
            }
            return baseHeartbeat;
        }

        public void noteJobStart(String packageName, int userId) {
            synchronized (mLock) {
                setLastJobHeartbeatLocked(packageName, userId, mHeartbeat);
            }
        }

        /**
         * Returns a list of all pending jobs. A running job is not considered pending. Periodic
         * jobs are always considered pending.
         */
        @Override
        public List<JobInfo> getSystemScheduledPendingJobs() {
            synchronized (mLock) {
                final List<JobInfo> pendingJobs = new ArrayList<JobInfo>();
                mJobs.forEachJob(Process.SYSTEM_UID, (job) -> {
                    if (job.getJob().isPeriodic() || !isCurrentlyActiveLocked(job)) {
                        pendingJobs.add(job.getJob());
                    }
                });
                return pendingJobs;
            }
        }

        @Override
        public void cancelJobsForUid(int uid, String reason) {
            JobSchedulerService.this.cancelJobsForUid(uid, reason);
        }

        @Override
        public void addBackingUpUid(int uid) {
            synchronized (mLock) {
                // No need to actually do anything here, since for a full backup the
                // activity manager will kill the process which will kill the job (and
                // cause it to restart, but now it can't run).
                mBackingUpUids.put(uid, uid);
            }
        }

        @Override
        public void removeBackingUpUid(int uid) {
            synchronized (mLock) {
                mBackingUpUids.delete(uid);
                // If there are any jobs for this uid, we need to rebuild the pending list
                // in case they are now ready to run.
                if (mJobs.countJobsForUid(uid) > 0) {
                    mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();
                }
            }
        }

        @Override
        public void clearAllBackingUpUids() {
            synchronized (mLock) {
                if (mBackingUpUids.size() > 0) {
                    mBackingUpUids.clear();
                    mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();
                }
            }
        }

        @Override
        public void reportAppUsage(String packageName, int userId) {
            JobSchedulerService.this.reportAppUsage(packageName, userId);
        }

        @Override
        public JobStorePersistStats getPersistStats() {
            synchronized (mLock) {
                return new JobStorePersistStats(mJobs.getPersistStats());
            }
        }
    }

    /**
     * Tracking of app assignments to standby buckets
     */
    final class StandbyTracker extends AppIdleStateChangeListener {

        // AppIdleStateChangeListener interface for live updates

        @Override
        public void onAppIdleStateChanged(final String packageName, final @UserIdInt int userId,
                boolean idle, int bucket, int reason) {
            final int uid = mLocalPM.getPackageUid(packageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
            if (uid < 0) {
                if (DEBUG_STANDBY) {
                    Slog.i(TAG, "App idle state change for unknown app "
                            + packageName + "/" + userId);
                }
                return;
            }

            final int bucketIndex = standbyBucketToBucketIndex(bucket);
            // update job bookkeeping out of band
            BackgroundThread.getHandler().post(() -> {
                if (DEBUG_STANDBY) {
                    Slog.i(TAG, "Moving uid " + uid + " to bucketIndex " + bucketIndex);
                }
                synchronized (mLock) {
                    mJobs.forEachJobForSourceUid(uid, job -> {
                        // double-check uid vs package name to disambiguate shared uids
                        if (packageName.equals(job.getSourcePackageName())) {
                            job.setStandbyBucket(bucketIndex);
                        }
                    });
                    onControllerStateChanged();
                }
            });
        }

        @Override
        public void onParoleStateChanged(boolean isParoleOn) {
            if (DEBUG_STANDBY) {
                Slog.i(TAG, "Global parole state now " + (isParoleOn ? "ON" : "OFF"));
            }
            mInParole = isParoleOn;
        }

        @Override
        public void onUserInteractionStarted(String packageName, int userId) {
            final int uid = mLocalPM.getPackageUid(packageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
            if (uid < 0) {
                // Quietly ignore; the case is already logged elsewhere
                return;
            }

            long sinceLast = mUsageStats.getTimeSinceLastJobRun(packageName, userId);
            if (sinceLast > 2 * DateUtils.DAY_IN_MILLIS) {
                // Too long ago, not worth logging
                sinceLast = 0L;
            }
            final DeferredJobCounter counter = new DeferredJobCounter();
            synchronized (mLock) {
                mJobs.forEachJobForSourceUid(uid, counter);
            }
            if (counter.numDeferred() > 0 || sinceLast > 0) {
                BatteryStatsInternal mBatteryStatsInternal = LocalServices.getService
                        (BatteryStatsInternal.class);
                mBatteryStatsInternal.noteJobsDeferred(uid, counter.numDeferred(), sinceLast);
            }
        }
    }

    static class DeferredJobCounter implements Consumer<JobStatus> {
        private int mDeferred = 0;

        public int numDeferred() {
            return mDeferred;
        }

        @Override
        public void accept(JobStatus job) {
            if (job.getWhenStandbyDeferred() > 0) {
                mDeferred++;
            }
        }
    }

    public static int standbyBucketToBucketIndex(int bucket) {
        // Normalize AppStandby constants to indices into our bookkeeping
        if (bucket == UsageStatsManager.STANDBY_BUCKET_NEVER) return NEVER_INDEX;
        else if (bucket > UsageStatsManager.STANDBY_BUCKET_FREQUENT) return RARE_INDEX;
        else if (bucket > UsageStatsManager.STANDBY_BUCKET_WORKING_SET) return FREQUENT_INDEX;
        else if (bucket > UsageStatsManager.STANDBY_BUCKET_ACTIVE) return WORKING_INDEX;
        else return ACTIVE_INDEX;
    }

    // Static to support external callers
    public static int standbyBucketForPackage(String packageName, int userId, long elapsedNow) {
        UsageStatsManagerInternal usageStats = LocalServices.getService(
                UsageStatsManagerInternal.class);
        int bucket = usageStats != null
                ? usageStats.getAppStandbyBucket(packageName, userId, elapsedNow)
                : 0;

        bucket = standbyBucketToBucketIndex(bucket);

        if (DEBUG_STANDBY) {
            Slog.v(TAG, packageName + "/" + userId + " standby bucket index: " + bucket);
        }
        return bucket;
    }

    /**
     * Binder stub trampoline implementation
     */
    final class JobSchedulerStub extends IJobScheduler.Stub {
        /** Cache determination of whether a given app can persist jobs
         * key is uid of the calling app; value is undetermined/true/false
         */
        private final SparseArray<Boolean> mPersistCache = new SparseArray<Boolean>();

        // Enforce that only the app itself (or shared uid participant) can schedule a
        // job that runs one of the app's services, as well as verifying that the
        // named service properly requires the BIND_JOB_SERVICE permission
        private void enforceValidJobRequest(int uid, JobInfo job) {
            final IPackageManager pm = AppGlobals.getPackageManager();
            final ComponentName service = job.getService();
            try {
                ServiceInfo si = pm.getServiceInfo(service,
                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                        UserHandle.getUserId(uid));
                if (si == null) {
                    throw new IllegalArgumentException("No such service " + service);
                }
                if (si.applicationInfo.uid != uid) {
                    throw new IllegalArgumentException("uid " + uid +
                            " cannot schedule job in " + service.getPackageName());
                }
                if (!JobService.PERMISSION_BIND.equals(si.permission)) {
                    throw new IllegalArgumentException("Scheduled service " + service
                            + " does not require android.permission.BIND_JOB_SERVICE permission");
                }
            } catch (RemoteException e) {
                // Can't happen; the Package Manager is in this same process
            }
        }

        private boolean canPersistJobs(int pid, int uid) {
            // If we get this far we're good to go; all we need to do now is check
            // whether the app is allowed to persist its scheduled work.
            final boolean canPersist;
            synchronized (mPersistCache) {
                Boolean cached = mPersistCache.get(uid);
                if (cached != null) {
                    canPersist = cached.booleanValue();
                } else {
                    // Persisting jobs is tantamount to running at boot, so we permit
                    // it when the app has declared that it uses the RECEIVE_BOOT_COMPLETED
                    // permission
                    int result = getContext().checkPermission(
                            android.Manifest.permission.RECEIVE_BOOT_COMPLETED, pid, uid);
                    canPersist = (result == PackageManager.PERMISSION_GRANTED);
                    mPersistCache.put(uid, canPersist);
                }
            }
            return canPersist;
        }

        private void validateJobFlags(JobInfo job, int callingUid) {
            if ((job.getFlags() & JobInfo.FLAG_WILL_BE_FOREGROUND) != 0) {
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.CONNECTIVITY_INTERNAL, TAG);
            }
            if ((job.getFlags() & JobInfo.FLAG_EXEMPT_FROM_APP_STANDBY) != 0) {
                if (callingUid != Process.SYSTEM_UID) {
                    throw new SecurityException("Job has invalid flags");
                }
                if (job.isPeriodic()) {
                    Slog.wtf(TAG, "Periodic jobs mustn't have"
                            + " FLAG_EXEMPT_FROM_APP_STANDBY. Job=" + job);
                }
            }
        }

        // IJobScheduler implementation
        @Override
        public int schedule(JobInfo job) throws RemoteException {
            if (DEBUG) {
                Slog.d(TAG, "Scheduling job: " + job.toString());
            }
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final int userId = UserHandle.getUserId(uid);

            enforceValidJobRequest(uid, job);
            if (job.isPersisted()) {
                if (!canPersistJobs(pid, uid)) {
                    throw new IllegalArgumentException("Error: requested job be persisted without"
                            + " holding RECEIVE_BOOT_COMPLETED permission.");
                }
            }

            validateJobFlags(job, uid);

            long ident = Binder.clearCallingIdentity();
            try {
                return JobSchedulerService.this.scheduleAsPackage(job, null, uid, null, userId,
                        null);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        // IJobScheduler implementation
        @Override
        public int enqueue(JobInfo job, JobWorkItem work) throws RemoteException {
            if (DEBUG) {
                Slog.d(TAG, "Enqueueing job: " + job.toString() + " work: " + work);
            }
            final int uid = Binder.getCallingUid();
            final int userId = UserHandle.getUserId(uid);

            enforceValidJobRequest(uid, job);
            if (job.isPersisted()) {
                throw new IllegalArgumentException("Can't enqueue work for persisted jobs");
            }
            if (work == null) {
                throw new NullPointerException("work is null");
            }

            validateJobFlags(job, uid);

            long ident = Binder.clearCallingIdentity();
            try {
                return JobSchedulerService.this.scheduleAsPackage(job, work, uid, null, userId,
                        null);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public int scheduleAsPackage(JobInfo job, String packageName, int userId, String tag)
                throws RemoteException {
            final int callerUid = Binder.getCallingUid();
            if (DEBUG) {
                Slog.d(TAG, "Caller uid " + callerUid + " scheduling job: " + job.toString()
                        + " on behalf of " + packageName + "/");
            }

            if (packageName == null) {
                throw new NullPointerException("Must specify a package for scheduleAsPackage()");
            }

            int mayScheduleForOthers = getContext().checkCallingOrSelfPermission(
                    android.Manifest.permission.UPDATE_DEVICE_STATS);
            if (mayScheduleForOthers != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Caller uid " + callerUid
                        + " not permitted to schedule jobs for other apps");
            }

            validateJobFlags(job, callerUid);

            long ident = Binder.clearCallingIdentity();
            try {
                return JobSchedulerService.this.scheduleAsPackage(job, null, callerUid,
                        packageName, userId, tag);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public List<JobInfo> getAllPendingJobs() throws RemoteException {
            final int uid = Binder.getCallingUid();

            long ident = Binder.clearCallingIdentity();
            try {
                return JobSchedulerService.this.getPendingJobs(uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public JobInfo getPendingJob(int jobId) throws RemoteException {
            final int uid = Binder.getCallingUid();

            long ident = Binder.clearCallingIdentity();
            try {
                return JobSchedulerService.this.getPendingJob(uid, jobId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void cancelAll() throws RemoteException {
            final int uid = Binder.getCallingUid();
            long ident = Binder.clearCallingIdentity();
            try {
                JobSchedulerService.this.cancelJobsForUid(uid,
                        "cancelAll() called by app, callingUid=" + uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void cancel(int jobId) throws RemoteException {
            final int uid = Binder.getCallingUid();

            long ident = Binder.clearCallingIdentity();
            try {
                JobSchedulerService.this.cancelJob(uid, jobId, uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * "dumpsys" infrastructure
         */
        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), TAG, pw)) return;

            int filterUid = -1;
            boolean proto = false;
            if (!ArrayUtils.isEmpty(args)) {
                int opti = 0;
                while (opti < args.length) {
                    String arg = args[opti];
                    if ("-h".equals(arg)) {
                        dumpHelp(pw);
                        return;
                    } else if ("-a".equals(arg)) {
                        // Ignore, we always dump all.
                    } else if ("--proto".equals(arg)) {
                        proto = true;
                    } else if (arg.length() > 0 && arg.charAt(0) == '-') {
                        pw.println("Unknown option: " + arg);
                        return;
                    } else {
                        break;
                    }
                    opti++;
                }
                if (opti < args.length) {
                    String pkg = args[opti];
                    try {
                        filterUid = getContext().getPackageManager().getPackageUid(pkg,
                                PackageManager.MATCH_ANY_USER);
                    } catch (NameNotFoundException ignored) {
                        pw.println("Invalid package: " + pkg);
                        return;
                    }
                }
            }

            final long identityToken = Binder.clearCallingIdentity();
            try {
                if (proto) {
                    JobSchedulerService.this.dumpInternalProto(fd, filterUid);
                } else {
                    JobSchedulerService.this.dumpInternal(new IndentingPrintWriter(pw, "  "),
                            filterUid);
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
                (new JobSchedulerShellCommand(JobSchedulerService.this)).exec(
                        this, in, out, err, args, callback, resultReceiver);
        }
    };

    // Shell command infrastructure: run the given job immediately
    int executeRunCommand(String pkgName, int userId, int jobId, boolean force) {
        if (DEBUG) {
            Slog.v(TAG, "executeRunCommand(): " + pkgName + "/" + userId
                    + " " + jobId + " f=" + force);
        }

        try {
            final int uid = AppGlobals.getPackageManager().getPackageUid(pkgName, 0,
                    userId != UserHandle.USER_ALL ? userId : UserHandle.USER_SYSTEM);
            if (uid < 0) {
                return JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
            }

            synchronized (mLock) {
                final JobStatus js = mJobs.getJobByUidAndJobId(uid, jobId);
                if (js == null) {
                    return JobSchedulerShellCommand.CMD_ERR_NO_JOB;
                }

                js.overrideState = (force) ? JobStatus.OVERRIDE_FULL : JobStatus.OVERRIDE_SOFT;
                if (!js.isConstraintsSatisfied()) {
                    js.overrideState = 0;
                    return JobSchedulerShellCommand.CMD_ERR_CONSTRAINTS;
                }

                queueReadyJobsForExecutionLocked();
                maybeRunPendingJobsLocked();
            }
        } catch (RemoteException e) {
            // can't happen
        }
        return 0;
    }

    // Shell command infrastructure: immediately timeout currently executing jobs
    int executeTimeoutCommand(PrintWriter pw, String pkgName, int userId,
            boolean hasJobId, int jobId) {
        if (DEBUG) {
            Slog.v(TAG, "executeTimeoutCommand(): " + pkgName + "/" + userId + " " + jobId);
        }

        synchronized (mLock) {
            boolean foundSome = false;
            for (int i=0; i<mActiveServices.size(); i++) {
                final JobServiceContext jc = mActiveServices.get(i);
                final JobStatus js = jc.getRunningJobLocked();
                if (jc.timeoutIfExecutingLocked(pkgName, userId, hasJobId, jobId, "shell")) {
                    foundSome = true;
                    pw.print("Timing out: ");
                    js.printUniqueId(pw);
                    pw.print(" ");
                    pw.println(js.getServiceComponent().flattenToShortString());
                }
            }
            if (!foundSome) {
                pw.println("No matching executing jobs found.");
            }
        }
        return 0;
    }

    // Shell command infrastructure: cancel a scheduled job
    int executeCancelCommand(PrintWriter pw, String pkgName, int userId,
            boolean hasJobId, int jobId) {
        if (DEBUG) {
            Slog.v(TAG, "executeCancelCommand(): " + pkgName + "/" + userId + " " + jobId);
        }

        int pkgUid = -1;
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            pkgUid = pm.getPackageUid(pkgName, 0, userId);
        } catch (RemoteException e) { /* can't happen */ }

        if (pkgUid < 0) {
            pw.println("Package " + pkgName + " not found.");
            return JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
        }

        if (!hasJobId) {
            pw.println("Canceling all jobs for " + pkgName + " in user " + userId);
            if (!cancelJobsForUid(pkgUid, "cancel shell command for package")) {
                pw.println("No matching jobs found.");
            }
        } else {
            pw.println("Canceling job " + pkgName + "/#" + jobId + " in user " + userId);
            if (!cancelJob(pkgUid, jobId, Process.SHELL_UID)) {
                pw.println("No matching job found.");
            }
        }

        return 0;
    }

    void setMonitorBattery(boolean enabled) {
        synchronized (mLock) {
            if (mBatteryController != null) {
                mBatteryController.getTracker().setMonitorBatteryLocked(enabled);
            }
        }
    }

    int getBatterySeq() {
        synchronized (mLock) {
            return mBatteryController != null ? mBatteryController.getTracker().getSeq() : -1;
        }
    }

    boolean getBatteryCharging() {
        synchronized (mLock) {
            return mBatteryController != null
                    ? mBatteryController.getTracker().isOnStablePower() : false;
        }
    }

    boolean getBatteryNotLow() {
        synchronized (mLock) {
            return mBatteryController != null
                    ? mBatteryController.getTracker().isBatteryNotLow() : false;
        }
    }

    int getStorageSeq() {
        synchronized (mLock) {
            return mStorageController != null ? mStorageController.getTracker().getSeq() : -1;
        }
    }

    boolean getStorageNotLow() {
        synchronized (mLock) {
            return mStorageController != null
                    ? mStorageController.getTracker().isStorageNotLow() : false;
        }
    }

    long getCurrentHeartbeat() {
        synchronized (mLock) {
            return mHeartbeat;
        }
    }

    // Shell command infrastructure
    int getJobState(PrintWriter pw, String pkgName, int userId, int jobId) {
        try {
            final int uid = AppGlobals.getPackageManager().getPackageUid(pkgName, 0,
                    userId != UserHandle.USER_ALL ? userId : UserHandle.USER_SYSTEM);
            if (uid < 0) {
                pw.print("unknown("); pw.print(pkgName); pw.println(")");
                return JobSchedulerShellCommand.CMD_ERR_NO_PACKAGE;
            }

            synchronized (mLock) {
                final JobStatus js = mJobs.getJobByUidAndJobId(uid, jobId);
                if (DEBUG) Slog.d(TAG, "get-job-state " + uid + "/" + jobId + ": " + js);
                if (js == null) {
                    pw.print("unknown("); UserHandle.formatUid(pw, uid);
                    pw.print("/jid"); pw.print(jobId); pw.println(")");
                    return JobSchedulerShellCommand.CMD_ERR_NO_JOB;
                }

                boolean printed = false;
                if (mPendingJobs.contains(js)) {
                    pw.print("pending");
                    printed = true;
                }
                if (isCurrentlyActiveLocked(js)) {
                    if (printed) {
                        pw.print(" ");
                    }
                    printed = true;
                    pw.println("active");
                }
                if (!ArrayUtils.contains(mStartedUsers, js.getUserId())) {
                    if (printed) {
                        pw.print(" ");
                    }
                    printed = true;
                    pw.println("user-stopped");
                }
                if (mBackingUpUids.indexOfKey(js.getSourceUid()) >= 0) {
                    if (printed) {
                        pw.print(" ");
                    }
                    printed = true;
                    pw.println("backing-up");
                }
                boolean componentPresent = false;
                try {
                    componentPresent = (AppGlobals.getPackageManager().getServiceInfo(
                            js.getServiceComponent(),
                            PackageManager.MATCH_DEBUG_TRIAGED_MISSING,
                            js.getUserId()) != null);
                } catch (RemoteException e) {
                }
                if (!componentPresent) {
                    if (printed) {
                        pw.print(" ");
                    }
                    printed = true;
                    pw.println("no-component");
                }
                if (js.isReady()) {
                    if (printed) {
                        pw.print(" ");
                    }
                    printed = true;
                    pw.println("ready");
                }
                if (!printed) {
                    pw.print("waiting");
                }
                pw.println();
            }
        } catch (RemoteException e) {
            // can't happen
        }
        return 0;
    }

    // Shell command infrastructure
    int executeHeartbeatCommand(PrintWriter pw, int numBeats) {
        if (numBeats < 1) {
            pw.println(getCurrentHeartbeat());
            return 0;
        }

        pw.print("Advancing standby heartbeat by ");
        pw.println(numBeats);
        synchronized (mLock) {
            advanceHeartbeatLocked(numBeats);
        }
        return 0;
    }

    void triggerDockState(boolean idleState) {
        final Intent dockIntent;
        if (idleState) {
            dockIntent = new Intent(Intent.ACTION_DOCK_IDLE);
        } else {
            dockIntent = new Intent(Intent.ACTION_DOCK_ACTIVE);
        }
        dockIntent.setPackage("android");
        dockIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND);
        getContext().sendBroadcastAsUser(dockIntent, UserHandle.ALL);
    }

    private String printContextIdToJobMap(JobStatus[] map, String initial) {
        StringBuilder s = new StringBuilder(initial + ": ");
        for (int i=0; i<map.length; i++) {
            s.append("(")
                    .append(map[i] == null? -1: map[i].getJobId())
                    .append(map[i] == null? -1: map[i].getUid())
                    .append(")" );
        }
        return s.toString();
    }

    private String printPendingQueue() {
        StringBuilder s = new StringBuilder("Pending queue: ");
        Iterator<JobStatus> it = mPendingJobs.iterator();
        while (it.hasNext()) {
            JobStatus js = it.next();
            s.append("(")
                    .append(js.getJob().getId())
                    .append(", ")
                    .append(js.getUid())
                    .append(") ");
        }
        return s.toString();
    }

    static void dumpHelp(PrintWriter pw) {
        pw.println("Job Scheduler (jobscheduler) dump options:");
        pw.println("  [-h] [package] ...");
        pw.println("    -h: print this help");
        pw.println("  [package] is an optional package name to limit the output to.");
    }

    /** Sort jobs by caller UID, then by Job ID. */
    private static void sortJobs(List<JobStatus> jobs) {
        Collections.sort(jobs, new Comparator<JobStatus>() {
            @Override
            public int compare(JobStatus o1, JobStatus o2) {
                int uid1 = o1.getUid();
                int uid2 = o2.getUid();
                int id1 = o1.getJobId();
                int id2 = o2.getJobId();
                if (uid1 != uid2) {
                    return uid1 < uid2 ? -1 : 1;
                }
                return id1 < id2 ? -1 : (id1 > id2 ? 1 : 0);
            }
        });
    }

    void dumpInternal(final IndentingPrintWriter pw, int filterUid) {
        final int filterUidFinal = UserHandle.getAppId(filterUid);
        final long nowElapsed = sElapsedRealtimeClock.millis();
        final long nowUptime = sUptimeMillisClock.millis();
        final Predicate<JobStatus> predicate = (js) -> {
            return filterUidFinal == -1 || UserHandle.getAppId(js.getUid()) == filterUidFinal
                    || UserHandle.getAppId(js.getSourceUid()) == filterUidFinal;
        };
        synchronized (mLock) {
            mConstants.dump(pw);
            pw.println();

            pw.println("  Heartbeat:");
            pw.print("    Current:    "); pw.println(mHeartbeat);
            pw.println("    Next");
            pw.print("      ACTIVE:   "); pw.println(mNextBucketHeartbeat[0]);
            pw.print("      WORKING:  "); pw.println(mNextBucketHeartbeat[1]);
            pw.print("      FREQUENT: "); pw.println(mNextBucketHeartbeat[2]);
            pw.print("      RARE:     "); pw.println(mNextBucketHeartbeat[3]);
            pw.print("    Last heartbeat: ");
            TimeUtils.formatDuration(mLastHeartbeatTime, nowElapsed, pw);
            pw.println();
            pw.print("    Next heartbeat: ");
            TimeUtils.formatDuration(mLastHeartbeatTime + mConstants.STANDBY_HEARTBEAT_TIME,
                    nowElapsed, pw);
            pw.println();
            pw.print("    In parole?: ");
            pw.print(mInParole);
            pw.println();
            pw.println();

            pw.println("Started users: " + Arrays.toString(mStartedUsers));
            pw.print("Registered ");
            pw.print(mJobs.size());
            pw.println(" jobs:");
            if (mJobs.size() > 0) {
                final List<JobStatus> jobs = mJobs.mJobSet.getAllJobs();
                sortJobs(jobs);
                for (JobStatus job : jobs) {
                    pw.print("  JOB #"); job.printUniqueId(pw); pw.print(": ");
                    pw.println(job.toShortStringExceptUniqueId());

                    // Skip printing details if the caller requested a filter
                    if (!predicate.test(job)) {
                        continue;
                    }

                    job.dump(pw, "    ", true, nowElapsed);
                    pw.print("    Last run heartbeat: ");
                    pw.print(heartbeatWhenJobsLastRun(job));
                    pw.println();

                    pw.print("    Ready: ");
                    pw.print(isReadyToBeExecutedLocked(job));
                    pw.print(" (job=");
                    pw.print(job.isReady());
                    pw.print(" user=");
                    pw.print(ArrayUtils.contains(mStartedUsers, job.getUserId()));
                    pw.print(" !pending=");
                    pw.print(!mPendingJobs.contains(job));
                    pw.print(" !active=");
                    pw.print(!isCurrentlyActiveLocked(job));
                    pw.print(" !backingup=");
                    pw.print(!(mBackingUpUids.indexOfKey(job.getSourceUid()) >= 0));
                    pw.print(" comp=");
                    boolean componentPresent = false;
                    try {
                        componentPresent = (AppGlobals.getPackageManager().getServiceInfo(
                                job.getServiceComponent(),
                                PackageManager.MATCH_DEBUG_TRIAGED_MISSING,
                                job.getUserId()) != null);
                    } catch (RemoteException e) {
                    }
                    pw.print(componentPresent);
                    pw.println(")");
                }
            } else {
                pw.println("  None.");
            }
            for (int i=0; i<mControllers.size(); i++) {
                pw.println();
                pw.println(mControllers.get(i).getClass().getSimpleName() + ":");
                pw.increaseIndent();
                mControllers.get(i).dumpControllerStateLocked(pw, predicate);
                pw.decreaseIndent();
            }
            pw.println();
            pw.println("Uid priority overrides:");
            for (int i=0; i< mUidPriorityOverride.size(); i++) {
                int uid = mUidPriorityOverride.keyAt(i);
                if (filterUidFinal == -1 || filterUidFinal == UserHandle.getAppId(uid)) {
                    pw.print("  "); pw.print(UserHandle.formatUid(uid));
                    pw.print(": "); pw.println(mUidPriorityOverride.valueAt(i));
                }
            }
            if (mBackingUpUids.size() > 0) {
                pw.println();
                pw.println("Backing up uids:");
                boolean first = true;
                for (int i = 0; i < mBackingUpUids.size(); i++) {
                    int uid = mBackingUpUids.keyAt(i);
                    if (filterUidFinal == -1 || filterUidFinal == UserHandle.getAppId(uid)) {
                        if (first) {
                            pw.print("  ");
                            first = false;
                        } else {
                            pw.print(", ");
                        }
                        pw.print(UserHandle.formatUid(uid));
                    }
                }
                pw.println();
            }
            pw.println();
            mJobPackageTracker.dump(pw, "", filterUidFinal);
            pw.println();
            if (mJobPackageTracker.dumpHistory(pw, "", filterUidFinal)) {
                pw.println();
            }
            pw.println("Pending queue:");
            for (int i=0; i<mPendingJobs.size(); i++) {
                JobStatus job = mPendingJobs.get(i);
                pw.print("  Pending #"); pw.print(i); pw.print(": ");
                pw.println(job.toShortString());
                job.dump(pw, "    ", false, nowElapsed);
                int priority = evaluateJobPriorityLocked(job);
                if (priority != JobInfo.PRIORITY_DEFAULT) {
                    pw.print("    Evaluated priority: "); pw.println(priority);
                }
                pw.print("    Tag: "); pw.println(job.getTag());
                pw.print("    Enq: ");
                TimeUtils.formatDuration(job.madePending - nowUptime, pw);
                pw.println();
            }
            pw.println();
            pw.println("Active jobs:");
            for (int i=0; i<mActiveServices.size(); i++) {
                JobServiceContext jsc = mActiveServices.get(i);
                pw.print("  Slot #"); pw.print(i); pw.print(": ");
                final JobStatus job = jsc.getRunningJobLocked();
                if (job == null) {
                    if (jsc.mStoppedReason != null) {
                        pw.print("inactive since ");
                        TimeUtils.formatDuration(jsc.mStoppedTime, nowElapsed, pw);
                        pw.print(", stopped because: ");
                        pw.println(jsc.mStoppedReason);
                    } else {
                        pw.println("inactive");
                    }
                    continue;
                } else {
                    pw.println(job.toShortString());
                    pw.print("    Running for: ");
                    TimeUtils.formatDuration(nowElapsed - jsc.getExecutionStartTimeElapsed(), pw);
                    pw.print(", timeout at: ");
                    TimeUtils.formatDuration(jsc.getTimeoutElapsed() - nowElapsed, pw);
                    pw.println();
                    job.dump(pw, "    ", false, nowElapsed);
                    int priority = evaluateJobPriorityLocked(jsc.getRunningJobLocked());
                    if (priority != JobInfo.PRIORITY_DEFAULT) {
                        pw.print("    Evaluated priority: "); pw.println(priority);
                    }
                    pw.print("    Active at ");
                    TimeUtils.formatDuration(job.madeActive - nowUptime, pw);
                    pw.print(", pending for ");
                    TimeUtils.formatDuration(job.madeActive - job.madePending, pw);
                    pw.println();
                }
            }
            if (filterUid == -1) {
                pw.println();
                pw.print("mReadyToRock="); pw.println(mReadyToRock);
                pw.print("mReportedActive="); pw.println(mReportedActive);
                pw.print("mMaxActiveJobs="); pw.println(mMaxActiveJobs);
            }
            pw.println();
            pw.print("PersistStats: ");
            pw.println(mJobs.getPersistStats());
        }
        pw.println();
    }

    void dumpInternalProto(final FileDescriptor fd, int filterUid) {
        ProtoOutputStream proto = new ProtoOutputStream(fd);
        final int filterUidFinal = UserHandle.getAppId(filterUid);
        final long nowElapsed = sElapsedRealtimeClock.millis();
        final long nowUptime = sUptimeMillisClock.millis();
        final Predicate<JobStatus> predicate = (js) -> {
            return filterUidFinal == -1 || UserHandle.getAppId(js.getUid()) == filterUidFinal
                    || UserHandle.getAppId(js.getSourceUid()) == filterUidFinal;
        };

        synchronized (mLock) {
            mConstants.dump(proto, JobSchedulerServiceDumpProto.SETTINGS);
            proto.write(JobSchedulerServiceDumpProto.CURRENT_HEARTBEAT, mHeartbeat);
            proto.write(JobSchedulerServiceDumpProto.NEXT_HEARTBEAT, mNextBucketHeartbeat[0]);
            proto.write(JobSchedulerServiceDumpProto.NEXT_HEARTBEAT, mNextBucketHeartbeat[1]);
            proto.write(JobSchedulerServiceDumpProto.NEXT_HEARTBEAT, mNextBucketHeartbeat[2]);
            proto.write(JobSchedulerServiceDumpProto.NEXT_HEARTBEAT, mNextBucketHeartbeat[3]);
            proto.write(JobSchedulerServiceDumpProto.LAST_HEARTBEAT_TIME_MILLIS,
                    mLastHeartbeatTime - nowUptime);
            proto.write(JobSchedulerServiceDumpProto.NEXT_HEARTBEAT_TIME_MILLIS,
                    mLastHeartbeatTime + mConstants.STANDBY_HEARTBEAT_TIME - nowUptime);
            proto.write(JobSchedulerServiceDumpProto.IN_PAROLE, mInParole);

            for (int u : mStartedUsers) {
                proto.write(JobSchedulerServiceDumpProto.STARTED_USERS, u);
            }
            if (mJobs.size() > 0) {
                final List<JobStatus> jobs = mJobs.mJobSet.getAllJobs();
                sortJobs(jobs);
                for (JobStatus job : jobs) {
                    final long rjToken = proto.start(JobSchedulerServiceDumpProto.REGISTERED_JOBS);
                    job.writeToShortProto(proto, JobSchedulerServiceDumpProto.RegisteredJob.INFO);

                    // Skip printing details if the caller requested a filter
                    if (!predicate.test(job)) {
                        continue;
                    }

                    job.dump(proto, JobSchedulerServiceDumpProto.RegisteredJob.DUMP, true, nowElapsed);

                    // isReadyToBeExecuted
                    proto.write(JobSchedulerServiceDumpProto.RegisteredJob.IS_JOB_READY,
                            job.isReady());
                    proto.write(JobSchedulerServiceDumpProto.RegisteredJob.IS_USER_STARTED,
                            ArrayUtils.contains(mStartedUsers, job.getUserId()));
                    proto.write(JobSchedulerServiceDumpProto.RegisteredJob.IS_JOB_PENDING,
                            mPendingJobs.contains(job));
                    proto.write(JobSchedulerServiceDumpProto.RegisteredJob.IS_JOB_CURRENTLY_ACTIVE,
                            isCurrentlyActiveLocked(job));
                    proto.write(JobSchedulerServiceDumpProto.RegisteredJob.IS_UID_BACKING_UP,
                            mBackingUpUids.indexOfKey(job.getSourceUid()) >= 0);
                    boolean componentPresent = false;
                    try {
                        componentPresent = (AppGlobals.getPackageManager().getServiceInfo(
                                job.getServiceComponent(),
                                PackageManager.MATCH_DEBUG_TRIAGED_MISSING,
                                job.getUserId()) != null);
                    } catch (RemoteException e) {
                    }
                    proto.write(JobSchedulerServiceDumpProto.RegisteredJob.IS_COMPONENT_PRESENT,
                            componentPresent);
                    proto.write(RegisteredJob.LAST_RUN_HEARTBEAT, heartbeatWhenJobsLastRun(job));

                    proto.end(rjToken);
                }
            }
            for (StateController controller : mControllers) {
                controller.dumpControllerStateLocked(
                        proto, JobSchedulerServiceDumpProto.CONTROLLERS, predicate);
            }
            for (int i=0; i< mUidPriorityOverride.size(); i++) {
                int uid = mUidPriorityOverride.keyAt(i);
                if (filterUidFinal == -1 || filterUidFinal == UserHandle.getAppId(uid)) {
                    long pToken = proto.start(JobSchedulerServiceDumpProto.PRIORITY_OVERRIDES);
                    proto.write(JobSchedulerServiceDumpProto.PriorityOverride.UID, uid);
                    proto.write(JobSchedulerServiceDumpProto.PriorityOverride.OVERRIDE_VALUE,
                            mUidPriorityOverride.valueAt(i));
                    proto.end(pToken);
                }
            }
            for (int i = 0; i < mBackingUpUids.size(); i++) {
                int uid = mBackingUpUids.keyAt(i);
                if (filterUidFinal == -1 || filterUidFinal == UserHandle.getAppId(uid)) {
                    proto.write(JobSchedulerServiceDumpProto.BACKING_UP_UIDS, uid);
                }
            }

            mJobPackageTracker.dump(proto, JobSchedulerServiceDumpProto.PACKAGE_TRACKER,
                    filterUidFinal);
            mJobPackageTracker.dumpHistory(proto, JobSchedulerServiceDumpProto.HISTORY,
                    filterUidFinal);

            for (JobStatus job : mPendingJobs) {
                final long pjToken = proto.start(JobSchedulerServiceDumpProto.PENDING_JOBS);

                job.writeToShortProto(proto, PendingJob.INFO);
                job.dump(proto, PendingJob.DUMP, false, nowElapsed);
                int priority = evaluateJobPriorityLocked(job);
                if (priority != JobInfo.PRIORITY_DEFAULT) {
                    proto.write(PendingJob.EVALUATED_PRIORITY, priority);
                }
                proto.write(PendingJob.ENQUEUED_DURATION_MS, nowUptime - job.madePending);

                proto.end(pjToken);
            }
            for (JobServiceContext jsc : mActiveServices) {
                final long ajToken = proto.start(JobSchedulerServiceDumpProto.ACTIVE_JOBS);
                final JobStatus job = jsc.getRunningJobLocked();

                if (job == null) {
                    final long ijToken = proto.start(ActiveJob.INACTIVE);

                        proto.write(ActiveJob.InactiveJob.TIME_SINCE_STOPPED_MS,
                                nowElapsed - jsc.mStoppedTime);
                    if (jsc.mStoppedReason != null) {
                        proto.write(ActiveJob.InactiveJob.STOPPED_REASON,
                                jsc.mStoppedReason);
                    }

                    proto.end(ijToken);
                } else {
                    final long rjToken = proto.start(ActiveJob.RUNNING);

                    job.writeToShortProto(proto, ActiveJob.RunningJob.INFO);

                    proto.write(ActiveJob.RunningJob.RUNNING_DURATION_MS,
                            nowElapsed - jsc.getExecutionStartTimeElapsed());
                    proto.write(ActiveJob.RunningJob.TIME_UNTIL_TIMEOUT_MS,
                            jsc.getTimeoutElapsed() - nowElapsed);

                    job.dump(proto, ActiveJob.RunningJob.DUMP, false, nowElapsed);

                    int priority = evaluateJobPriorityLocked(jsc.getRunningJobLocked());
                    if (priority != JobInfo.PRIORITY_DEFAULT) {
                        proto.write(ActiveJob.RunningJob.EVALUATED_PRIORITY, priority);
                    }

                    proto.write(ActiveJob.RunningJob.TIME_SINCE_MADE_ACTIVE_MS,
                            nowUptime - job.madeActive);
                    proto.write(ActiveJob.RunningJob.PENDING_DURATION_MS,
                            job.madeActive - job.madePending);

                    proto.end(rjToken);
                }
                proto.end(ajToken);
            }
            if (filterUid == -1) {
                proto.write(JobSchedulerServiceDumpProto.IS_READY_TO_ROCK, mReadyToRock);
                proto.write(JobSchedulerServiceDumpProto.REPORTED_ACTIVE, mReportedActive);
                proto.write(JobSchedulerServiceDumpProto.MAX_ACTIVE_JOBS, mMaxActiveJobs);
            }
        }

        proto.flush();
    }
}
