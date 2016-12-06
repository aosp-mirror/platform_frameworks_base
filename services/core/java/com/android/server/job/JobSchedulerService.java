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

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AppGlobals;
import android.app.IUidObserver;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.job.IJobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.KeyValueListParser;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;

import com.android.internal.app.IBatteryStats;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.util.ArrayUtils;
import com.android.server.DeviceIdleController;
import com.android.server.LocalServices;
import com.android.server.job.JobStore.JobStatusFunctor;
import com.android.server.job.controllers.AppIdleController;
import com.android.server.job.controllers.BatteryController;
import com.android.server.job.controllers.ConnectivityController;
import com.android.server.job.controllers.ContentObserverController;
import com.android.server.job.controllers.DeviceIdleJobsController;
import com.android.server.job.controllers.IdleController;
import com.android.server.job.controllers.JobStatus;
import com.android.server.job.controllers.StateController;
import com.android.server.job.controllers.TimeController;

import libcore.util.EmptyArray;

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
public final class JobSchedulerService extends com.android.server.SystemService
        implements StateChangedListener, JobCompletedListener {
    static final String TAG = "JobSchedulerService";
    public static final boolean DEBUG = false;

    /** The maximum number of concurrent jobs we run at one time. */
    private static final int MAX_JOB_CONTEXTS_COUNT = 16;
    /** Enforce a per-app limit on scheduled jobs? */
    private static final boolean ENFORCE_MAX_JOBS = true;
    /** The maximum number of jobs that we allow an unprivileged app to schedule */
    private static final int MAX_JOBS_PER_APP = 100;


    /** Global local for all job scheduler state. */
    final Object mLock = new Object();
    /** Master list of jobs. */
    final JobStore mJobs;
    /** Tracking amount of time each package runs for. */
    final JobPackageTracker mJobPackageTracker = new JobPackageTracker();

    static final int MSG_JOB_EXPIRED = 0;
    static final int MSG_CHECK_JOB = 1;
    static final int MSG_STOP_JOB = 2;
    static final int MSG_CHECK_JOB_GREEDY = 3;

    /**
     * Track Services that have currently active or pending jobs. The index is provided by
     * {@link JobStatus#getServiceToken()}
     */
    final List<JobServiceContext> mActiveServices = new ArrayList<>();
    /** List of controllers that will notify this service of updates to jobs. */
    List<StateController> mControllers;
    /**
     * Queue of pending jobs. The JobServiceContext class will receive jobs from this list
     * when ready to execute them.
     */
    final ArrayList<JobStatus> mPendingJobs = new ArrayList<>();

    int[] mStartedUsers = EmptyArray.INT;

    final JobHandler mHandler;
    final JobSchedulerStub mJobSchedulerStub;

    IBatteryStats mBatteryStats;
    PowerManager mPowerManager;
    DeviceIdleController.LocalService mLocalDeviceIdleController;

    /**
     * Set to true once we are allowed to run third party apps.
     */
    boolean mReadyToRock;

    /**
     * What we last reported to DeviceIdleController about whether we are active.
     */
    boolean mReportedActive;

    /**
     * Current limit on the number of concurrent JobServiceContext entries we want to
     * keep actively running a job.
     */
    int mMaxActiveJobs = 1;

    /**
     * Which uids are currently in the foreground.
     */
    final SparseIntArray mUidPriorityOverride = new SparseIntArray();

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

    /**
     * All times are in milliseconds. These constants are kept synchronized with the system
     * global Settings. Any access to this class or its fields should be done while
     * holding the JobSchedulerService.mLock lock.
     */
    private final class Constants extends ContentObserver {
        // Key names stored in the settings value.
        private static final String KEY_MIN_IDLE_COUNT = "min_idle_count";
        private static final String KEY_MIN_CHARGING_COUNT = "min_charging_count";
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

        private static final int DEFAULT_MIN_IDLE_COUNT = 1;
        private static final int DEFAULT_MIN_CHARGING_COUNT = 1;
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

        private ContentResolver mResolver;
        private final KeyValueListParser mParser = new KeyValueListParser(',');

        public Constants(Handler handler) {
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
                    mParser.setString(Settings.Global.getString(mResolver,
                            Settings.Global.ALARM_MANAGER_CONSTANTS));
                } catch (IllegalArgumentException e) {
                    // Failed to parse the settings string, log this and move on
                    // with defaults.
                    Slog.e(TAG, "Bad device idle settings", e);
                }

                MIN_IDLE_COUNT = mParser.getInt(KEY_MIN_IDLE_COUNT,
                        DEFAULT_MIN_IDLE_COUNT);
                MIN_CHARGING_COUNT = mParser.getInt(KEY_MIN_CHARGING_COUNT,
                        DEFAULT_MIN_CHARGING_COUNT);
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
            }
        }

        void dump(PrintWriter pw) {
            pw.println("  Settings:");

            pw.print("    "); pw.print(KEY_MIN_IDLE_COUNT); pw.print("=");
            pw.print(MIN_IDLE_COUNT); pw.println();

            pw.print("    "); pw.print(KEY_MIN_CHARGING_COUNT); pw.print("=");
            pw.print(MIN_CHARGING_COUNT); pw.println();

            pw.print("    "); pw.print(KEY_MIN_CONNECTIVITY_COUNT); pw.print("=");
            pw.print(MIN_CONNECTIVITY_COUNT); pw.println();

            pw.print("    "); pw.print(KEY_MIN_CONTENT_COUNT); pw.print("=");
            pw.print(MIN_CONTENT_COUNT); pw.println();

            pw.print("    "); pw.print(KEY_MIN_READY_JOBS_COUNT); pw.print("=");
            pw.print(MIN_READY_JOBS_COUNT); pw.println();

            pw.print("    "); pw.print(KEY_HEAVY_USE_FACTOR); pw.print("=");
            pw.print(HEAVY_USE_FACTOR); pw.println();

            pw.print("    "); pw.print(KEY_MODERATE_USE_FACTOR); pw.print("=");
            pw.print(MODERATE_USE_FACTOR); pw.println();

            pw.print("    "); pw.print(KEY_FG_JOB_COUNT); pw.print("=");
            pw.print(FG_JOB_COUNT); pw.println();

            pw.print("    "); pw.print(KEY_BG_NORMAL_JOB_COUNT); pw.print("=");
            pw.print(BG_NORMAL_JOB_COUNT); pw.println();

            pw.print("    "); pw.print(KEY_BG_MODERATE_JOB_COUNT); pw.print("=");
            pw.print(BG_MODERATE_JOB_COUNT); pw.println();

            pw.print("    "); pw.print(KEY_BG_LOW_JOB_COUNT); pw.print("=");
            pw.print(BG_LOW_JOB_COUNT); pw.println();

            pw.print("    "); pw.print(KEY_BG_CRITICAL_JOB_COUNT); pw.print("=");
            pw.print(BG_CRITICAL_JOB_COUNT); pw.println();
        }
    }

    final Constants mConstants;

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
            if (Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
                // Purge the app's jobs if the whole package was just disabled.  When this is
                // the case the component name will be a bare package name.
                final String pkgName = getPackageName(intent);
                final int pkgUid = intent.getIntExtra(Intent.EXTRA_UID, -1);
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
                                        cancelJobsForUid(pkgUid, true);
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
                    cancelJobsForUid(uidRemoved, true);
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
                final int pkgUid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                final String pkgName = intent.getData().getSchemeSpecificPart();
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
                final int pkgUid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                final String pkgName = intent.getData().getSchemeSpecificPart();
                if (pkgUid != -1) {
                    if (DEBUG) {
                        Slog.d(TAG, "Removing jobs for pkg " + pkgName + " at uid " + pkgUid);
                    }
                    cancelJobsForPackageAndUid(pkgName, pkgUid);
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
        @Override public void onUidStateChanged(int uid, int procState) throws RemoteException {
            updateUidState(uid, procState);
        }

        @Override public void onUidGone(int uid) throws RemoteException {
            updateUidState(uid, ActivityManager.PROCESS_STATE_CACHED_EMPTY);
        }

        @Override public void onUidActive(int uid) throws RemoteException {
        }

        @Override public void onUidIdle(int uid) throws RemoteException {
            cancelJobsForUid(uid, false);
        }
    };

    public Object getLock() {
        return mLock;
    }

    public JobStore getJobStore() {
        return mJobs;
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
     * Entry point from client to schedule the provided job.
     * This cancels the job if it's already been scheduled, and replaces it with the one provided.
     * @param job JobInfo object containing execution parameters
     * @param uId The package identifier of the application this job is for.
     * @return Result of this operation. See <code>JobScheduler#RESULT_*</code> return codes.
     */
    public int schedule(JobInfo job, int uId) {
        return scheduleAsPackage(job, uId, null, -1, null);
    }

    public int scheduleAsPackage(JobInfo job, int uId, String packageName, int userId,
            String tag) {
        JobStatus jobStatus = JobStatus.createFromJobInfo(job, uId, packageName, userId, tag);
        try {
            if (ActivityManagerNative.getDefault().getAppStartMode(uId,
                    job.getService().getPackageName()) == ActivityManager.APP_START_MODE_DISABLED) {
                Slog.w(TAG, "Not scheduling job " + uId + ":" + job.toString()
                        + " -- package not allowed to start");
                return JobScheduler.RESULT_FAILURE;
            }
        } catch (RemoteException e) {
        }
        if (DEBUG) Slog.d(TAG, "SCHEDULE: " + jobStatus.toShortString());
        JobStatus toCancel;
        synchronized (mLock) {
            // Jobs on behalf of others don't apply to the per-app job cap
            if (ENFORCE_MAX_JOBS && packageName == null) {
                if (mJobs.countJobsForUid(uId) > MAX_JOBS_PER_APP) {
                    Slog.w(TAG, "Too many jobs for uid " + uId);
                    throw new IllegalStateException("Apps may not schedule more than "
                                + MAX_JOBS_PER_APP + " distinct jobs");
                }
            }

            toCancel = mJobs.getJobByUidAndJobId(uId, job.getId());
            if (toCancel != null) {
                cancelJobImpl(toCancel, jobStatus);
            }
            startTrackingJob(jobStatus, toCancel);
        }
        mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();
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
        List<JobStatus> jobsForUser;
        synchronized (mLock) {
            jobsForUser = mJobs.getJobsByUser(userHandle);
        }
        for (int i=0; i<jobsForUser.size(); i++) {
            JobStatus toRemove = jobsForUser.get(i);
            cancelJobImpl(toRemove, null);
        }
    }

    void cancelJobsForPackageAndUid(String pkgName, int uid) {
        List<JobStatus> jobsForUid;
        synchronized (mLock) {
            jobsForUid = mJobs.getJobsByUid(uid);
        }
        for (int i = jobsForUid.size() - 1; i >= 0; i--) {
            final JobStatus job = jobsForUid.get(i);
            if (job.getSourcePackageName().equals(pkgName)) {
                cancelJobImpl(job, null);
            }
        }
    }

    /**
     * Entry point from client to cancel all jobs originating from their uid.
     * This will remove the job from the master list, and cancel the job if it was staged for
     * execution or being executed.
     * @param uid Uid to check against for removal of a job.
     * @param forceAll If true, all jobs for the uid will be canceled; if false, only those
     * whose apps are stopped.
     */
    public void cancelJobsForUid(int uid, boolean forceAll) {
        List<JobStatus> jobsForUid;
        synchronized (mLock) {
            jobsForUid = mJobs.getJobsByUid(uid);
        }
        for (int i=0; i<jobsForUid.size(); i++) {
            JobStatus toRemove = jobsForUid.get(i);
            if (!forceAll) {
                String packageName = toRemove.getServiceComponent().getPackageName();
                try {
                    if (ActivityManagerNative.getDefault().getAppStartMode(uid, packageName)
                            != ActivityManager.APP_START_MODE_DISABLED) {
                        continue;
                    }
                } catch (RemoteException e) {
                }
            }
            cancelJobImpl(toRemove, null);
        }
    }

    /**
     * Entry point from client to cancel the job corresponding to the jobId provided.
     * This will remove the job from the master list, and cancel the job if it was staged for
     * execution or being executed.
     * @param uid Uid of the calling client.
     * @param jobId Id of the job, provided at schedule-time.
     */
    public void cancelJob(int uid, int jobId) {
        JobStatus toCancel;
        synchronized (mLock) {
            toCancel = mJobs.getJobByUidAndJobId(uid, jobId);
        }
        if (toCancel != null) {
            cancelJobImpl(toCancel, null);
        }
    }

    private void cancelJobImpl(JobStatus cancelled, JobStatus incomingJob) {
        if (DEBUG) Slog.d(TAG, "CANCEL: " + cancelled.toShortString());
        stopTrackingJob(cancelled, incomingJob, true /* writeBack */);
        synchronized (mLock) {
            // Remove from pending queue.
            if (mPendingJobs.remove(cancelled)) {
                mJobPackageTracker.noteNonpending(cancelled);
            }
            // Cancel if running.
            stopJobOnServiceContextLocked(cancelled, JobParameters.REASON_CANCELED);
            reportActive();
        }
    }

    void updateUidState(int uid, int procState) {
        synchronized (mLock) {
            if (procState == ActivityManager.PROCESS_STATE_TOP) {
                // Only use this if we are exactly the top app.  All others can live
                // with just the foreground priority.  This means that persistent processes
                // can never be the top app priority...  that is fine.
                mUidPriorityOverride.put(uid, JobInfo.PRIORITY_TOP_APP);
            } else if (procState <= ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE) {
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
                    final JobStatus executing = jsc.getRunningJob();
                    if (executing != null
                            && (executing.getFlags() & JobInfo.FLAG_WILL_BE_FOREGROUND) == 0) {
                        jsc.cancelExecutingJob(JobParameters.REASON_DEVICE_IDLE);
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
                }
                mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();
            }
        }
    }

    void reportActive() {
        // active is true if pending queue contains jobs OR some job is running.
        boolean active = mPendingJobs.size() > 0;
        if (mPendingJobs.size() <= 0) {
            for (int i=0; i<mActiveServices.size(); i++) {
                final JobServiceContext jsc = mActiveServices.get(i);
                final JobStatus job = jsc.getRunningJob();
                if (job != null
                        && (job.getJob().getFlags() & JobInfo.FLAG_WILL_BE_FOREGROUND) == 0
                        && !job.dozeWhitelisted) {
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
        mHandler = new JobHandler(context.getMainLooper());
        mConstants = new Constants(mHandler);
        mJobSchedulerStub = new JobSchedulerStub();
        mJobs = JobStore.initAndGet(this);

        // Create the controllers.
        mControllers = new ArrayList<StateController>();
        mControllers.add(ConnectivityController.get(this));
        mControllers.add(TimeController.get(this));
        mControllers.add(IdleController.get(this));
        mControllers.add(BatteryController.get(this));
        mControllers.add(AppIdleController.get(this));
        mControllers.add(ContentObserverController.get(this));
        mControllers.add(DeviceIdleJobsController.get(this));
    }

    @Override
    public void onStart() {
        publishLocalService(JobSchedulerInternal.class, new LocalService());
        publishBinderService(Context.JOB_SCHEDULER_SERVICE, mJobSchedulerStub);
    }

    @Override
    public void onBootPhase(int phase) {
        if (PHASE_SYSTEM_SERVICES_READY == phase) {
            mConstants.start(getContext().getContentResolver());
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
            mPowerManager = (PowerManager)getContext().getSystemService(Context.POWER_SERVICE);
            try {
                ActivityManagerNative.getDefault().registerUidObserver(mUidObserver,
                        ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE
                        | ActivityManager.UID_OBSERVER_IDLE);
            } catch (RemoteException e) {
                // ignored; both services live in system_server
            }
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
                mJobs.forEachJob(new JobStatusFunctor() {
                    @Override
                    public void process(JobStatus job) {
                        for (int controller = 0; controller < mControllers.size(); controller++) {
                            final StateController sc = mControllers.get(controller);
                            sc.maybeStartTrackingJobLocked(job, null);
                        }
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
    private void startTrackingJob(JobStatus jobStatus, JobStatus lastJob) {
        synchronized (mLock) {
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
    }

    /**
     * Called when we want to remove a JobStatus object that we've finished executing. Returns the
     * object removed.
     */
    private boolean stopTrackingJob(JobStatus jobStatus, JobStatus incomingJob,
            boolean writeBack) {
        synchronized (mLock) {
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
    }

    private boolean stopJobOnServiceContextLocked(JobStatus job, int reason) {
        for (int i=0; i<mActiveServices.size(); i++) {
            JobServiceContext jsc = mActiveServices.get(i);
            final JobStatus executing = jsc.getRunningJob();
            if (executing != null && executing.matches(job.getUid(), job.getJobId())) {
                jsc.cancelExecutingJob(reason);
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
            // The 'unsafe' direct-internal-reference running-job inspector is okay to
            // use here because we are already holding the necessary lock *and* we
            // immediately discard the returned object reference, if any; we return
            // only a boolean state indicator to the caller.
            final JobStatus running = serviceContext.getRunningJobUnsafeLocked();
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
     * @see JobHandler#maybeQueueReadyJobsForExecutionLockedH
     */
    private JobStatus getRescheduleJobForFailure(JobStatus failureToReschedule) {
        final long elapsedNowMillis = SystemClock.elapsedRealtime();
        final JobInfo job = failureToReschedule.getJob();

        final long initialBackoffMillis = job.getInitialBackoffMillis();
        final int backoffAttempts = failureToReschedule.getNumFailures() + 1;
        long delayMillis;

        switch (job.getBackoffPolicy()) {
            case JobInfo.BACKOFF_POLICY_LINEAR:
                delayMillis = initialBackoffMillis * backoffAttempts;
                break;
            default:
                if (DEBUG) {
                    Slog.v(TAG, "Unrecognised back-off policy, defaulting to exponential.");
                }
            case JobInfo.BACKOFF_POLICY_EXPONENTIAL:
                delayMillis =
                        (long) Math.scalb(initialBackoffMillis, backoffAttempts - 1);
                break;
        }
        delayMillis =
                Math.min(delayMillis, JobInfo.MAX_BACKOFF_DELAY_MILLIS);
        JobStatus newJob = new JobStatus(failureToReschedule, elapsedNowMillis + delayMillis,
                JobStatus.NO_LATEST_RUNTIME, backoffAttempts);
        for (int ic=0; ic<mControllers.size(); ic++) {
            StateController controller = mControllers.get(ic);
            controller.rescheduleForFailure(newJob, failureToReschedule);
        }
        return newJob;
    }

    /**
     * Called after a periodic has executed so we can reschedule it. We take the last execution
     * time of the job to be the time of completion (i.e. the time at which this function is
     * called).
     * This could be inaccurate b/c the job can run for as long as
     * {@link com.android.server.job.JobServiceContext#EXECUTING_TIMESLICE_MILLIS}, but will lead
     * to underscheduling at least, rather than if we had taken the last execution time to be the
     * start of the execution.
     * @return A new job representing the execution criteria for this instantiation of the
     * recurring job.
     */
    private JobStatus getRescheduleJobForPeriodic(JobStatus periodicToReschedule) {
        final long elapsedNow = SystemClock.elapsedRealtime();
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
        return new JobStatus(periodicToReschedule, newEarliestRunTimeElapsed,
                newLatestRuntimeElapsed, 0 /* backoffAttempt */);
    }

    // JobCompletedListener implementations.

    /**
     * A job just finished executing. We fetch the
     * {@link com.android.server.job.controllers.JobStatus} from the store and depending on
     * whether we want to reschedule we readd it to the controllers.
     * @param jobStatus Completed job.
     * @param needsReschedule Whether the implementing class should reschedule this job.
     */
    @Override
    public void onJobCompleted(JobStatus jobStatus, boolean needsReschedule) {
        if (DEBUG) {
            Slog.d(TAG, "Completed " + jobStatus + ", reschedule=" + needsReschedule);
        }
        // Do not write back immediately if this is a periodic job. The job may get lost if system
        // shuts down before it is added back.
        if (!stopTrackingJob(jobStatus, null, !jobStatus.getJob().isPeriodic())) {
            if (DEBUG) {
                Slog.d(TAG, "Could not find job to remove. Was job removed while executing?");
            }
            // We still want to check for jobs to execute, because this job may have
            // scheduled a new job under the same job id, and now we can run it.
            mHandler.obtainMessage(MSG_CHECK_JOB_GREEDY).sendToTarget();
            return;
        }
        // Note: there is a small window of time in here where, when rescheduling a job,
        // we will stop monitoring its content providers.  This should be fixed by stopping
        // the old job after scheduling the new one, but since we have no lock held here
        // that may cause ordering problems if the app removes jobStatus while in here.
        if (needsReschedule) {
            JobStatus rescheduled = getRescheduleJobForFailure(jobStatus);
            startTrackingJob(rescheduled, jobStatus);
        } else if (jobStatus.getJob().isPeriodic()) {
            JobStatus rescheduledPeriodic = getRescheduleJobForPeriodic(jobStatus);
            startTrackingJob(rescheduledPeriodic, jobStatus);
        }
        reportActive();
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

    private class JobHandler extends Handler {

        public JobHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            synchronized (mLock) {
                if (!mReadyToRock) {
                    return;
                }
            }
            switch (message.what) {
                case MSG_JOB_EXPIRED:
                    synchronized (mLock) {
                        JobStatus runNow = (JobStatus) message.obj;
                        // runNow can be null, which is a controller's way of indicating that its
                        // state is such that all ready jobs should be run immediately.
                        if (runNow != null && !mPendingJobs.contains(runNow)
                                && mJobs.containsJob(runNow)) {
                            mJobPackageTracker.notePending(runNow);
                            mPendingJobs.add(runNow);
                        }
                        queueReadyJobsForExecutionLockedH();
                    }
                    break;
                case MSG_CHECK_JOB:
                    synchronized (mLock) {
                        if (mReportedActive) {
                            // if jobs are currently being run, queue all ready jobs for execution.
                            queueReadyJobsForExecutionLockedH();
                        } else {
                            // Check the list of jobs and run some of them if we feel inclined.
                            maybeQueueReadyJobsForExecutionLockedH();
                        }
                    }
                    break;
                case MSG_CHECK_JOB_GREEDY:
                    synchronized (mLock) {
                        queueReadyJobsForExecutionLockedH();
                    }
                    break;
                case MSG_STOP_JOB:
                    cancelJobImpl((JobStatus)message.obj, null);
                    break;
            }
            maybeRunPendingJobsH();
            // Don't remove JOB_EXPIRED in case one came along while processing the queue.
            removeMessages(MSG_CHECK_JOB);
        }

        /**
         * Run through list of jobs and execute all possible - at least one is expired so we do
         * as many as we can.
         */
        private void queueReadyJobsForExecutionLockedH() {
            if (DEBUG) {
                Slog.d(TAG, "queuing all ready jobs for execution:");
            }
            noteJobsNonpending(mPendingJobs);
            mPendingJobs.clear();
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

        class ReadyJobQueueFunctor implements JobStatusFunctor {
            ArrayList<JobStatus> newReadyJobs;

            @Override
            public void process(JobStatus job) {
                if (isReadyToBeExecutedLocked(job)) {
                    if (DEBUG) {
                        Slog.d(TAG, "    queued " + job.toShortString());
                    }
                    if (newReadyJobs == null) {
                        newReadyJobs = new ArrayList<JobStatus>();
                    }
                    newReadyJobs.add(job);
                } else if (areJobConstraintsNotSatisfiedLocked(job)) {
                    stopJobOnServiceContextLocked(job,
                            JobParameters.REASON_CONSTRAINTS_NOT_SATISFIED);
                }
            }

            public void postProcess() {
                if (newReadyJobs != null) {
                    noteJobsPending(newReadyJobs);
                    mPendingJobs.addAll(newReadyJobs);
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
        class MaybeReadyJobQueueFunctor implements JobStatusFunctor {
            int chargingCount;
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
            public void process(JobStatus job) {
                if (isReadyToBeExecutedLocked(job)) {
                    try {
                        if (ActivityManagerNative.getDefault().getAppStartMode(job.getUid(),
                                job.getJob().getService().getPackageName())
                                == ActivityManager.APP_START_MODE_DISABLED) {
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
                    if (job.hasConnectivityConstraint() || job.hasUnmeteredConstraint()
                            || job.hasNotRoamingConstraint()) {
                        connectivityCount++;
                    }
                    if (job.hasChargingConstraint()) {
                        chargingCount++;
                    }
                    if (job.hasContentTriggerConstraint()) {
                        contentCount++;
                    }
                    if (runnableJobs == null) {
                        runnableJobs = new ArrayList<>();
                    }
                    runnableJobs.add(job);
                } else if (areJobConstraintsNotSatisfiedLocked(job)) {
                    stopJobOnServiceContextLocked(job,
                            JobParameters.REASON_CONSTRAINTS_NOT_SATISFIED);
                }
            }

            public void postProcess() {
                if (backoffCount > 0 ||
                        idleCount >= mConstants.MIN_IDLE_COUNT ||
                        connectivityCount >= mConstants.MIN_CONNECTIVITY_COUNT ||
                        chargingCount >= mConstants.MIN_CHARGING_COUNT ||
                        contentCount >= mConstants.MIN_CONTENT_COUNT ||
                        (runnableJobs != null
                                && runnableJobs.size() >= mConstants.MIN_READY_JOBS_COUNT)) {
                    if (DEBUG) {
                        Slog.d(TAG, "maybeQueueReadyJobsForExecutionLockedH: Running jobs.");
                    }
                    noteJobsPending(runnableJobs);
                    mPendingJobs.addAll(runnableJobs);
                } else {
                    if (DEBUG) {
                        Slog.d(TAG, "maybeQueueReadyJobsForExecutionLockedH: Not running anything.");
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
                contentCount = 0;
                runnableJobs = null;
            }
        }
        private final MaybeReadyJobQueueFunctor mMaybeQueueFunctor = new MaybeReadyJobQueueFunctor();

        private void maybeQueueReadyJobsForExecutionLockedH() {
            if (DEBUG) Slog.d(TAG, "Maybe queuing ready jobs...");

            noteJobsNonpending(mPendingJobs);
            mPendingJobs.clear();
            mJobs.forEachJob(mMaybeQueueFunctor);
            mMaybeQueueFunctor.postProcess();
        }

        /**
         * Criteria for moving a job into the pending queue:
         *      - It's ready.
         *      - It's not pending.
         *      - It's not already running on a JSC.
         *      - The user that requested the job is running.
         *      - The component is enabled and runnable.
         */
        private boolean isReadyToBeExecutedLocked(JobStatus job) {
            final boolean jobReady = job.isReady();
            final boolean jobPending = mPendingJobs.contains(job);
            final boolean jobActive = isCurrentlyActiveLocked(job);

            final int userId = job.getUserId();
            final boolean userStarted = ArrayUtils.contains(mStartedUsers, userId);
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
                        + " ready=" + jobReady + " pending=" + jobPending
                        + " active=" + jobActive + " userStarted=" + userStarted
                        + " componentPresent=" + componentPresent);
            }
            return userStarted && componentPresent && jobReady && !jobPending && !jobActive;
        }

        /**
         * Criteria for cancelling an active job:
         *      - It's not ready
         *      - It's running on a JSC.
         */
        private boolean areJobConstraintsNotSatisfiedLocked(JobStatus job) {
            return !job.isReady() && isCurrentlyActiveLocked(job);
        }

        /**
         * Reconcile jobs in the pending queue against available execution contexts.
         * A controller can force a job into the pending queue even if it's already running, but
         * here is where we decide whether to actually execute it.
         */
        private void maybeRunPendingJobsH() {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "pending queue: " + mPendingJobs.size() + " jobs.");
                }
                assignJobsToContextsLocked();
                reportActive();
            }
        }
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
            memLevel = ActivityManagerNative.getDefault().getMemoryTrimLevel();
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
            final JobStatus status = js.getRunningJob();
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
                JobStatus js = mActiveServices.get(i).getRunningJob();
                if (js != null) {
                    if (DEBUG) {
                        Slog.d(TAG, "preempting job: " + mActiveServices.get(i).getRunningJob());
                    }
                    // preferredUid will be set to uid of currently running job.
                    mActiveServices.get(i).preemptExecutingJob();
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
         * Returns a list of all pending jobs. A running job is not considered pending. Periodic
         * jobs are always considered pending.
         */
        @Override
        public List<JobInfo> getSystemScheduledPendingJobs() {
            synchronized (mLock) {
                final List<JobInfo> pendingJobs = new ArrayList<JobInfo>();
                mJobs.forEachJob(Process.SYSTEM_UID, new JobStatusFunctor() {
                    @Override
                    public void process(JobStatus job) {
                        if (job.getJob().isPeriodic() || !isCurrentlyActiveLocked(job)) {
                            pendingJobs.add(job.getJob());
                        }
                    }
                });
                return pendingJobs;
            }
        }
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

        // IJobScheduler implementation
        @Override
        public int schedule(JobInfo job) throws RemoteException {
            if (DEBUG) {
                Slog.d(TAG, "Scheduling job: " + job.toString());
            }
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();

            enforceValidJobRequest(uid, job);
            if (job.isPersisted()) {
                if (!canPersistJobs(pid, uid)) {
                    throw new IllegalArgumentException("Error: requested job be persisted without"
                            + " holding RECEIVE_BOOT_COMPLETED permission.");
                }
            }

            if ((job.getFlags() & JobInfo.FLAG_WILL_BE_FOREGROUND) != 0) {
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.CONNECTIVITY_INTERNAL, TAG);
            }

            long ident = Binder.clearCallingIdentity();
            try {
                return JobSchedulerService.this.schedule(job, uid);
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
                        + " on behalf of " + packageName);
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

            if ((job.getFlags() & JobInfo.FLAG_WILL_BE_FOREGROUND) != 0) {
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.CONNECTIVITY_INTERNAL, TAG);
            }

            long ident = Binder.clearCallingIdentity();
            try {
                return JobSchedulerService.this.scheduleAsPackage(job, callerUid,
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
                JobSchedulerService.this.cancelJobsForUid(uid, true);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void cancel(int jobId) throws RemoteException {
            final int uid = Binder.getCallingUid();

            long ident = Binder.clearCallingIdentity();
            try {
                JobSchedulerService.this.cancelJob(uid, jobId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * "dumpsys" infrastructure
         */
        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            getContext().enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);

            long identityToken = Binder.clearCallingIdentity();
            try {
                JobSchedulerService.this.dumpInternal(pw, args);
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ResultReceiver resultReceiver) throws RemoteException {
                (new JobSchedulerShellCommand(JobSchedulerService.this)).exec(
                        this, in, out, err, args, resultReceiver);
        }
    };

    // Shell command infrastructure: run the given job immediately
    int executeRunCommand(String pkgName, int userId, int jobId, boolean force) {
        if (DEBUG) {
            Slog.v(TAG, "executeRunCommand(): " + pkgName + "/" + userId
                    + " " + jobId + " f=" + force);
        }

        try {
            final int uid = AppGlobals.getPackageManager().getPackageUid(pkgName, 0, userId);
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

                mHandler.obtainMessage(MSG_CHECK_JOB_GREEDY).sendToTarget();
            }
        } catch (RemoteException e) {
            // can't happen
        }
        return 0;
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

    void dumpInternal(final PrintWriter pw, String[] args) {
        int filterUid = -1;
        if (!ArrayUtils.isEmpty(args)) {
            int opti = 0;
            while (opti < args.length) {
                String arg = args[opti];
                if ("-h".equals(arg)) {
                    dumpHelp(pw);
                    return;
                } else if ("-a".equals(arg)) {
                    // Ignore, we always dump all.
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
                            PackageManager.MATCH_UNINSTALLED_PACKAGES);
                } catch (NameNotFoundException ignored) {
                    pw.println("Invalid package: " + pkg);
                    return;
                }
            }
        }

        final int filterUidFinal = UserHandle.getAppId(filterUid);
        final long now = SystemClock.elapsedRealtime();
        synchronized (mLock) {
            mConstants.dump(pw);
            pw.println();
            pw.println("Started users: " + Arrays.toString(mStartedUsers));
            pw.print("Registered ");
            pw.print(mJobs.size());
            pw.println(" jobs:");
            if (mJobs.size() > 0) {
                final List<JobStatus> jobs = mJobs.mJobSet.getAllJobs();
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
                for (JobStatus job : jobs) {
                    pw.print("  JOB #"); job.printUniqueId(pw); pw.print(": ");
                    pw.println(job.toShortStringExceptUniqueId());

                    // Skip printing details if the caller requested a filter
                    if (!job.shouldDump(filterUidFinal)) {
                        continue;
                    }

                    job.dump(pw, "    ", true);
                    pw.print("    Ready: ");
                    pw.print(mHandler.isReadyToBeExecutedLocked(job));
                    pw.print(" (job=");
                    pw.print(job.isReady());
                    pw.print(" pending=");
                    pw.print(mPendingJobs.contains(job));
                    pw.print(" active=");
                    pw.print(isCurrentlyActiveLocked(job));
                    pw.print(" user=");
                    pw.print(ArrayUtils.contains(mStartedUsers, job.getUserId()));
                    pw.println(")");
                }
            } else {
                pw.println("  None.");
            }
            for (int i=0; i<mControllers.size(); i++) {
                pw.println();
                mControllers.get(i).dumpControllerStateLocked(pw, filterUidFinal);
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
                job.dump(pw, "    ", false);
                int priority = evaluateJobPriorityLocked(job);
                if (priority != JobInfo.PRIORITY_DEFAULT) {
                    pw.print("    Evaluated priority: "); pw.println(priority);
                }
                pw.print("    Tag: "); pw.println(job.getTag());
            }
            pw.println();
            pw.println("Active jobs:");
            for (int i=0; i<mActiveServices.size(); i++) {
                JobServiceContext jsc = mActiveServices.get(i);
                pw.print("  Slot #"); pw.print(i); pw.print(": ");
                if (jsc.getRunningJob() == null) {
                    pw.println("inactive");
                    continue;
                } else {
                    pw.println(jsc.getRunningJob().toShortString());
                    pw.print("    Running for: ");
                    TimeUtils.formatDuration(now - jsc.getExecutionStartTimeElapsed(), pw);
                    pw.print(", timeout at: ");
                    TimeUtils.formatDuration(jsc.getTimeoutElapsed() - now, pw);
                    pw.println();
                    jsc.getRunningJob().dump(pw, "    ", false);
                    int priority = evaluateJobPriorityLocked(jsc.getRunningJob());
                    if (priority != JobInfo.PRIORITY_DEFAULT) {
                        pw.print("    Evaluated priority: "); pw.println(priority);
                    }
                }
            }
            if (filterUid == -1) {
                pw.println();
                pw.print("mReadyToRock="); pw.println(mReadyToRock);
                pw.print("mReportedActive="); pw.println(mReportedActive);
                pw.print("mMaxActiveJobs="); pw.println(mMaxActiveJobs);
            }
        }
        pw.println();
    }
}
