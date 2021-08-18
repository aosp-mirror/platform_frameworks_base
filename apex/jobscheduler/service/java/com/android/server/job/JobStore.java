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

import static android.net.NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED;
import static android.net.NetworkCapabilities.TRANSPORT_TEST;

import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;
import static com.android.server.job.JobSchedulerService.sSystemClock;

import android.annotation.Nullable;
import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.net.NetworkRequest;
import android.os.Environment;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SystemConfigFileCommitEventLogger;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.BitUtils;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerInternal.JobStorePersistStats;
import com.android.server.job.controllers.JobStatus;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Maintains the master list of jobs that the job scheduler is tracking. These jobs are compared by
 * reference, so none of the functions in this class should make a copy.
 * Also handles read/write of persisted jobs.
 *
 * Note on locking:
 *      All callers to this class must <strong>lock on the class object they are calling</strong>.
 *      This is important b/c {@link com.android.server.job.JobStore.WriteJobsMapToDiskRunnable}
 *      and {@link com.android.server.job.JobStore.ReadJobMapFromDiskRunnable} lock on that
 *      object.
 *
 * Test:
 * atest $ANDROID_BUILD_TOP/frameworks/base/services/tests/servicestests/src/com/android/server/job/JobStoreTest.java
 */
public final class JobStore {
    private static final String TAG = "JobStore";
    private static final boolean DEBUG = JobSchedulerService.DEBUG;

    /** Threshold to adjust how often we want to write to the db. */
    private static final long JOB_PERSIST_DELAY = 2000L;

    final Object mLock;
    final Object mWriteScheduleLock;    // used solely for invariants around write scheduling
    final JobSet mJobSet; // per-caller-uid and per-source-uid tracking
    final Context mContext;

    // Bookkeeping around incorrect boot-time system clock
    private final long mXmlTimestamp;
    private boolean mRtcGood;

    @GuardedBy("mWriteScheduleLock")
    private boolean mWriteScheduled;

    @GuardedBy("mWriteScheduleLock")
    private boolean mWriteInProgress;

    private static final Object sSingletonLock = new Object();
    private final SystemConfigFileCommitEventLogger mEventLogger;
    private final AtomicFile mJobsFile;
    /** Handler backed by IoThread for writing to disk. */
    private final Handler mIoHandler = IoThread.getHandler();
    private static JobStore sSingleton;

    private JobStorePersistStats mPersistInfo = new JobStorePersistStats();

    /** Used by the {@link JobSchedulerService} to instantiate the JobStore. */
    static JobStore initAndGet(JobSchedulerService jobManagerService) {
        synchronized (sSingletonLock) {
            if (sSingleton == null) {
                sSingleton = new JobStore(jobManagerService.getContext(),
                        jobManagerService.getLock(), Environment.getDataDirectory());
            }
            return sSingleton;
        }
    }

    /**
     * @return A freshly initialized job store object, with no loaded jobs.
     */
    @VisibleForTesting
    public static JobStore initAndGetForTesting(Context context, File dataDir) {
        JobStore jobStoreUnderTest = new JobStore(context, new Object(), dataDir);
        jobStoreUnderTest.clear();
        return jobStoreUnderTest;
    }

    /**
     * Construct the instance of the job store. This results in a blocking read from disk.
     */
    private JobStore(Context context, Object lock, File dataDir) {
        mLock = lock;
        mWriteScheduleLock = new Object();
        mContext = context;

        File systemDir = new File(dataDir, "system");
        File jobDir = new File(systemDir, "job");
        jobDir.mkdirs();
        mEventLogger = new SystemConfigFileCommitEventLogger("jobs");
        mJobsFile = new AtomicFile(new File(jobDir, "jobs.xml"), mEventLogger);

        mJobSet = new JobSet();

        // If the current RTC is earlier than the timestamp on our persisted jobs file,
        // we suspect that the RTC is uninitialized and so we cannot draw conclusions
        // about persisted job scheduling.
        //
        // Note that if the persisted jobs file does not exist, we proceed with the
        // assumption that the RTC is good.  This is less work and is safe: if the
        // clock updates to sanity then we'll be saving the persisted jobs file in that
        // correct state, which is normal; or we'll wind up writing the jobs file with
        // an incorrect historical timestamp.  That's fine; at worst we'll reboot with
        // a *correct* timestamp, see a bunch of overdue jobs, and run them; then
        // settle into normal operation.
        mXmlTimestamp = mJobsFile.getLastModifiedTime();
        mRtcGood = (sSystemClock.millis() > mXmlTimestamp);

        readJobMapFromDisk(mJobSet, mRtcGood);
    }

    public boolean jobTimesInflatedValid() {
        return mRtcGood;
    }

    public boolean clockNowValidToInflate(long now) {
        return now >= mXmlTimestamp;
    }

    /**
     * Find all the jobs that were affected by RTC clock uncertainty at boot time.  Returns
     * parallel lists of the existing JobStatus objects and of new, equivalent JobStatus instances
     * with now-corrected time bounds.
     */
    public void getRtcCorrectedJobsLocked(final ArrayList<JobStatus> toAdd,
            final ArrayList<JobStatus> toRemove) {
        final long elapsedNow = sElapsedRealtimeClock.millis();

        // Find the jobs that need to be fixed up, collecting them for post-iteration
        // replacement with their new versions
        forEachJob(job -> {
            final Pair<Long, Long> utcTimes = job.getPersistedUtcTimes();
            if (utcTimes != null) {
                Pair<Long, Long> elapsedRuntimes =
                        convertRtcBoundsToElapsed(utcTimes, elapsedNow);
                JobStatus newJob = new JobStatus(job,
                        elapsedRuntimes.first, elapsedRuntimes.second,
                        0, job.getLastSuccessfulRunTime(), job.getLastFailedRunTime());
                newJob.prepareLocked();
                toAdd.add(newJob);
                toRemove.add(job);
            }
        });
    }

    /**
     * Add a job to the master list, persisting it if necessary. If the JobStatus already exists,
     * it will be replaced.
     * @param jobStatus Job to add.
     * @return Whether or not an equivalent JobStatus was replaced by this operation.
     */
    public boolean add(JobStatus jobStatus) {
        boolean replaced = mJobSet.remove(jobStatus);
        mJobSet.add(jobStatus);
        if (jobStatus.isPersisted()) {
            maybeWriteStatusToDiskAsync();
        }
        if (DEBUG) {
            Slog.d(TAG, "Added job status to store: " + jobStatus);
        }
        return replaced;
    }

    boolean containsJob(JobStatus jobStatus) {
        return mJobSet.contains(jobStatus);
    }

    public int size() {
        return mJobSet.size();
    }

    public JobStorePersistStats getPersistStats() {
        return mPersistInfo;
    }

    public int countJobsForUid(int uid) {
        return mJobSet.countJobsForUid(uid);
    }

    /**
     * Remove the provided job. Will also delete the job if it was persisted.
     * @param removeFromPersisted If true, the job will be removed from the persisted job list
     *                            immediately (if it was persisted).
     * @return Whether or not the job existed to be removed.
     */
    public boolean remove(JobStatus jobStatus, boolean removeFromPersisted) {
        boolean removed = mJobSet.remove(jobStatus);
        if (!removed) {
            if (DEBUG) {
                Slog.d(TAG, "Couldn't remove job: didn't exist: " + jobStatus);
            }
            return false;
        }
        if (removeFromPersisted && jobStatus.isPersisted()) {
            maybeWriteStatusToDiskAsync();
        }
        return removed;
    }

    /**
     * Remove the jobs of users not specified in the keepUserIds.
     * @param keepUserIds Array of User IDs whose jobs should be kept and not removed.
     */
    public void removeJobsOfUnlistedUsers(int[] keepUserIds) {
        mJobSet.removeJobsOfUnlistedUsers(keepUserIds);
    }

    @VisibleForTesting
    public void clear() {
        mJobSet.clear();
        maybeWriteStatusToDiskAsync();
    }

    /**
     * @param userHandle User for whom we are querying the list of jobs.
     * @return A list of all the jobs scheduled for the provided user. Never null.
     */
    public List<JobStatus> getJobsByUser(int userHandle) {
        return mJobSet.getJobsByUser(userHandle);
    }

    /**
     * @param uid Uid of the requesting app.
     * @return All JobStatus objects for a given uid from the master list. Never null.
     */
    public List<JobStatus> getJobsByUid(int uid) {
        return mJobSet.getJobsByUid(uid);
    }

    /**
     * @param uid Uid of the requesting app.
     * @param jobId Job id, specified at schedule-time.
     * @return the JobStatus that matches the provided uId and jobId, or null if none found.
     */
    public JobStatus getJobByUidAndJobId(int uid, int jobId) {
        return mJobSet.get(uid, jobId);
    }

    /**
     * Iterate over the set of all jobs, invoking the supplied functor on each.  This is for
     * customers who need to examine each job; we'd much rather not have to generate
     * transient unified collections for them to iterate over and then discard, or creating
     * iterators every time a client needs to perform a sweep.
     */
    public void forEachJob(Consumer<JobStatus> functor) {
        mJobSet.forEachJob(null, functor);
    }

    public void forEachJob(@Nullable Predicate<JobStatus> filterPredicate,
            Consumer<JobStatus> functor) {
        mJobSet.forEachJob(filterPredicate, functor);
    }

    public void forEachJob(int uid, Consumer<JobStatus> functor) {
        mJobSet.forEachJob(uid, functor);
    }

    public void forEachJobForSourceUid(int sourceUid, Consumer<JobStatus> functor) {
        mJobSet.forEachJobForSourceUid(sourceUid, functor);
    }

    /** Version of the db schema. */
    private static final int JOBS_FILE_VERSION = 0;
    /** Tag corresponds to constraints this job needs. */
    private static final String XML_TAG_PARAMS_CONSTRAINTS = "constraints";
    /** Tag corresponds to execution parameters. */
    private static final String XML_TAG_PERIODIC = "periodic";
    private static final String XML_TAG_ONEOFF = "one-off";
    private static final String XML_TAG_EXTRAS = "extras";

    /**
     * Every time the state changes we write all the jobs in one swath, instead of trying to
     * track incremental changes.
     */
    private void maybeWriteStatusToDiskAsync() {
        synchronized (mWriteScheduleLock) {
            if (!mWriteScheduled) {
                if (DEBUG) {
                    Slog.v(TAG, "Scheduling persist of jobs to disk.");
                }
                mIoHandler.postDelayed(mWriteRunnable, JOB_PERSIST_DELAY);
                mWriteScheduled = true;
            }
        }
    }

    @VisibleForTesting
    public void readJobMapFromDisk(JobSet jobSet, boolean rtcGood) {
        new ReadJobMapFromDiskRunnable(jobSet, rtcGood).run();
    }

    /** Write persisted JobStore state to disk synchronously. Should only be used for testing. */
    @VisibleForTesting
    public void writeStatusToDiskForTesting() {
        synchronized (mWriteScheduleLock) {
            if (mWriteScheduled) {
                throw new IllegalStateException("An asynchronous write is already scheduled.");
            }

            mWriteScheduled = true;
            mWriteRunnable.run();
        }
    }

    /**
     * Wait for any pending write to the persistent store to clear
     * @param maxWaitMillis Maximum time from present to wait
     * @return {@code true} if I/O cleared as expected, {@code false} if the wait
     *     timed out before the pending write completed.
     */
    @VisibleForTesting
    public boolean waitForWriteToCompleteForTesting(long maxWaitMillis) {
        final long start = SystemClock.uptimeMillis();
        final long end = start + maxWaitMillis;
        synchronized (mWriteScheduleLock) {
            while (mWriteScheduled || mWriteInProgress) {
                final long now = SystemClock.uptimeMillis();
                if (now >= end) {
                    // still not done and we've hit the end; failure
                    return false;
                }
                try {
                    mWriteScheduleLock.wait(now - start + maxWaitMillis);
                } catch (InterruptedException e) {
                    // Spurious; keep waiting
                    break;
                }
            }
        }
        return true;
    }

    /**
     * Returns a single string representation of the contents of the specified intArray.
     * If the intArray is [1, 2, 4] as the input, the return result will be the string "1,2,4".
     */
    @VisibleForTesting
    static String intArrayToString(int[] values) {
        final StringJoiner sj = new StringJoiner(",");
        for (final int value : values) {
            sj.add(String.valueOf(value));
        }
        return sj.toString();
    }


   /**
    * Converts a string containing a comma-separated list of decimal representations
    * of ints into an array of int. If the string is not correctly formatted,
    * or if any value doesn't fit into an int, NumberFormatException is thrown.
    */
    @VisibleForTesting
    static int[] stringToIntArray(String str) {
        if (TextUtils.isEmpty(str)) return new int[0];
        final String[] arr = str.split(",");
        final int[] values = new int[arr.length];
        for (int i = 0; i < arr.length; i++) {
            values[i] = Integer.parseInt(arr[i]);
        }
        return values;
    }

    /**
     * Runnable that writes {@link #mJobSet} out to xml.
     * NOTE: This Runnable locks on mLock
     */
    private final Runnable mWriteRunnable = new Runnable() {
        @Override
        public void run() {
            final long startElapsed = sElapsedRealtimeClock.millis();
            final List<JobStatus> storeCopy = new ArrayList<JobStatus>();
            // Intentionally allow new scheduling of a write operation *before* we clone
            // the job set.  If we reset it to false after cloning, there's a window in
            // which no new write will be scheduled but mLock is not held, i.e. a new
            // job might appear and fail to be recognized as needing a persist.  The
            // potential cost is one redundant write of an identical set of jobs in the
            // rare case of that specific race, but by doing it this way we avoid quite
            // a bit of lock contention.
            synchronized (mWriteScheduleLock) {
                mWriteScheduled = false;
                if (mWriteInProgress) {
                    // Another runnable is currently writing. Postpone this new write task.
                    maybeWriteStatusToDiskAsync();
                    return;
                }
                mWriteInProgress = true;
            }
            synchronized (mLock) {
                // Clone the jobs so we can release the lock before writing.
                mJobSet.forEachJob(null, (job) -> {
                    if (job.isPersisted()) {
                        storeCopy.add(new JobStatus(job));
                    }
                });
            }
            writeJobsMapImpl(storeCopy);
            if (DEBUG) {
                Slog.v(TAG, "Finished writing, took " + (sElapsedRealtimeClock.millis()
                        - startElapsed) + "ms");
            }
            synchronized (mWriteScheduleLock) {
                mWriteInProgress = false;
                mWriteScheduleLock.notifyAll();
            }
        }

        private void writeJobsMapImpl(List<JobStatus> jobList) {
            int numJobs = 0;
            int numSystemJobs = 0;
            int numSyncJobs = 0;
            mEventLogger.setStartTime(SystemClock.uptimeMillis());
            try (FileOutputStream fos = mJobsFile.startWrite()) {
                TypedXmlSerializer out = Xml.resolveSerializer(fos);
                out.startDocument(null, true);
                out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

                out.startTag(null, "job-info");
                out.attribute(null, "version", Integer.toString(JOBS_FILE_VERSION));
                for (int i=0; i<jobList.size(); i++) {
                    JobStatus jobStatus = jobList.get(i);
                    if (DEBUG) {
                        Slog.d(TAG, "Saving job " + jobStatus.getJobId());
                    }
                    out.startTag(null, "job");
                    addAttributesToJobTag(out, jobStatus);
                    writeConstraintsToXml(out, jobStatus);
                    writeExecutionCriteriaToXml(out, jobStatus);
                    writeBundleToXml(jobStatus.getJob().getExtras(), out);
                    out.endTag(null, "job");

                    numJobs++;
                    if (jobStatus.getUid() == Process.SYSTEM_UID) {
                        numSystemJobs++;
                        if (isSyncJob(jobStatus)) {
                            numSyncJobs++;
                        }
                    }
                }
                out.endTag(null, "job-info");
                out.endDocument();

                mJobsFile.finishWrite(fos);
            } catch (IOException e) {
                if (DEBUG) {
                    Slog.v(TAG, "Error writing out job data.", e);
                }
            } catch (XmlPullParserException e) {
                if (DEBUG) {
                    Slog.d(TAG, "Error persisting bundle.", e);
                }
            } finally {
                mPersistInfo.countAllJobsSaved = numJobs;
                mPersistInfo.countSystemServerJobsSaved = numSystemJobs;
                mPersistInfo.countSystemSyncManagerJobsSaved = numSyncJobs;
            }
        }

        /** Write out a tag with data comprising the required fields and priority of this job and
         * its client.
         */
        private void addAttributesToJobTag(XmlSerializer out, JobStatus jobStatus)
                throws IOException {
            out.attribute(null, "jobid", Integer.toString(jobStatus.getJobId()));
            out.attribute(null, "package", jobStatus.getServiceComponent().getPackageName());
            out.attribute(null, "class", jobStatus.getServiceComponent().getClassName());
            if (jobStatus.getSourcePackageName() != null) {
                out.attribute(null, "sourcePackageName", jobStatus.getSourcePackageName());
            }
            if (jobStatus.getSourceTag() != null) {
                out.attribute(null, "sourceTag", jobStatus.getSourceTag());
            }
            out.attribute(null, "sourceUserId", String.valueOf(jobStatus.getSourceUserId()));
            out.attribute(null, "uid", Integer.toString(jobStatus.getUid()));
            out.attribute(null, "priority", String.valueOf(jobStatus.getPriority()));
            out.attribute(null, "flags", String.valueOf(jobStatus.getFlags()));
            if (jobStatus.getInternalFlags() != 0) {
                out.attribute(null, "internalFlags", String.valueOf(jobStatus.getInternalFlags()));
            }

            out.attribute(null, "lastSuccessfulRunTime",
                    String.valueOf(jobStatus.getLastSuccessfulRunTime()));
            out.attribute(null, "lastFailedRunTime",
                    String.valueOf(jobStatus.getLastFailedRunTime()));
        }

        private void writeBundleToXml(PersistableBundle extras, XmlSerializer out)
                throws IOException, XmlPullParserException {
            out.startTag(null, XML_TAG_EXTRAS);
            PersistableBundle extrasCopy = deepCopyBundle(extras, 10);
            extrasCopy.saveToXml(out);
            out.endTag(null, XML_TAG_EXTRAS);
        }

        private PersistableBundle deepCopyBundle(PersistableBundle bundle, int maxDepth) {
            if (maxDepth <= 0) {
                return null;
            }
            PersistableBundle copy = (PersistableBundle) bundle.clone();
            Set<String> keySet = bundle.keySet();
            for (String key: keySet) {
                Object o = copy.get(key);
                if (o instanceof PersistableBundle) {
                    PersistableBundle bCopy = deepCopyBundle((PersistableBundle) o, maxDepth-1);
                    copy.putPersistableBundle(key, bCopy);
                }
            }
            return copy;
        }

        /**
         * Write out a tag with data identifying this job's constraints. If the constraint isn't here
         * it doesn't apply.
         * TODO: b/183455312 Update this code to use proper serialization for NetworkRequest,
         *       because currently store is not including everything (like, UIDs, bandwidth,
         *       signal strength etc. are lost).
         */
        private void writeConstraintsToXml(XmlSerializer out, JobStatus jobStatus) throws IOException {
            out.startTag(null, XML_TAG_PARAMS_CONSTRAINTS);
            if (jobStatus.hasConnectivityConstraint()) {
                final NetworkRequest network = jobStatus.getJob().getRequiredNetwork();
                out.attribute(null, "net-capabilities-csv", intArrayToString(
                        network.getCapabilities()));
                out.attribute(null, "net-forbidden-capabilities-csv", intArrayToString(
                        network.getForbiddenCapabilities()));
                out.attribute(null, "net-transport-types-csv", intArrayToString(
                        network.getTransportTypes()));
            }
            if (jobStatus.hasIdleConstraint()) {
                out.attribute(null, "idle", Boolean.toString(true));
            }
            if (jobStatus.hasChargingConstraint()) {
                out.attribute(null, "charging", Boolean.toString(true));
            }
            if (jobStatus.hasBatteryNotLowConstraint()) {
                out.attribute(null, "battery-not-low", Boolean.toString(true));
            }
            if (jobStatus.hasStorageNotLowConstraint()) {
                out.attribute(null, "storage-not-low", Boolean.toString(true));
            }
            out.endTag(null, XML_TAG_PARAMS_CONSTRAINTS);
        }

        private void writeExecutionCriteriaToXml(XmlSerializer out, JobStatus jobStatus)
                throws IOException {
            final JobInfo job = jobStatus.getJob();
            if (jobStatus.getJob().isPeriodic()) {
                out.startTag(null, XML_TAG_PERIODIC);
                out.attribute(null, "period", Long.toString(job.getIntervalMillis()));
                out.attribute(null, "flex", Long.toString(job.getFlexMillis()));
            } else {
                out.startTag(null, XML_TAG_ONEOFF);
            }

            // If we still have the persisted times, we need to record those directly because
            // we haven't yet been able to calculate the usual elapsed-timebase bounds
            // correctly due to wall-clock uncertainty.
            Pair <Long, Long> utcJobTimes = jobStatus.getPersistedUtcTimes();
            if (DEBUG && utcJobTimes != null) {
                Slog.i(TAG, "storing original UTC timestamps for " + jobStatus);
            }

            final long nowRTC = sSystemClock.millis();
            final long nowElapsed = sElapsedRealtimeClock.millis();
            if (jobStatus.hasDeadlineConstraint()) {
                // Wall clock deadline.
                final long deadlineWallclock = (utcJobTimes == null)
                        ? nowRTC + (jobStatus.getLatestRunTimeElapsed() - nowElapsed)
                        : utcJobTimes.second;
                out.attribute(null, "deadline", Long.toString(deadlineWallclock));
            }
            if (jobStatus.hasTimingDelayConstraint()) {
                final long delayWallclock = (utcJobTimes == null)
                        ? nowRTC + (jobStatus.getEarliestRunTime() - nowElapsed)
                        : utcJobTimes.first;
                out.attribute(null, "delay", Long.toString(delayWallclock));
            }

            // Only write out back-off policy if it differs from the default.
            // This also helps the case where the job is idle -> these aren't allowed to specify
            // back-off.
            if (jobStatus.getJob().getInitialBackoffMillis() != JobInfo.DEFAULT_INITIAL_BACKOFF_MILLIS
                    || jobStatus.getJob().getBackoffPolicy() != JobInfo.DEFAULT_BACKOFF_POLICY) {
                out.attribute(null, "backoff-policy", Integer.toString(job.getBackoffPolicy()));
                out.attribute(null, "initial-backoff", Long.toString(job.getInitialBackoffMillis()));
            }
            if (job.isPeriodic()) {
                out.endTag(null, XML_TAG_PERIODIC);
            } else {
                out.endTag(null, XML_TAG_ONEOFF);
            }
        }
    };

    /**
     * Translate the supplied RTC times to the elapsed timebase, with clamping appropriate
     * to interpreting them as a job's delay + deadline times for alarm-setting purposes.
     * @param rtcTimes a Pair<Long, Long> in which {@code first} is the "delay" earliest
     *     allowable runtime for the job, and {@code second} is the "deadline" time at which
     *     the job becomes overdue.
     */
    private static Pair<Long, Long> convertRtcBoundsToElapsed(Pair<Long, Long> rtcTimes,
            long nowElapsed) {
        final long nowWallclock = sSystemClock.millis();
        final long earliest = (rtcTimes.first > JobStatus.NO_EARLIEST_RUNTIME)
                ? nowElapsed + Math.max(rtcTimes.first - nowWallclock, 0)
                : JobStatus.NO_EARLIEST_RUNTIME;
        final long latest = (rtcTimes.second < JobStatus.NO_LATEST_RUNTIME)
                ? nowElapsed + Math.max(rtcTimes.second - nowWallclock, 0)
                : JobStatus.NO_LATEST_RUNTIME;
        return Pair.create(earliest, latest);
    }

    private static boolean isSyncJob(JobStatus status) {
        return com.android.server.content.SyncJobService.class.getName()
                .equals(status.getServiceComponent().getClassName());
    }

    /**
     * Runnable that reads list of persisted job from xml. This is run once at start up, so doesn't
     * need to go through {@link JobStore#add(com.android.server.job.controllers.JobStatus)}.
     */
    private final class ReadJobMapFromDiskRunnable implements Runnable {
        private final JobSet jobSet;
        private final boolean rtcGood;

        /**
         * @param jobSet Reference to the (empty) set of JobStatus objects that back the JobStore,
         *               so that after disk read we can populate it directly.
         */
        ReadJobMapFromDiskRunnable(JobSet jobSet, boolean rtcIsGood) {
            this.jobSet = jobSet;
            this.rtcGood = rtcIsGood;
        }

        @Override
        public void run() {
            int numJobs = 0;
            int numSystemJobs = 0;
            int numSyncJobs = 0;
            List<JobStatus> jobs;
            try (FileInputStream fis = mJobsFile.openRead()) {
                synchronized (mLock) {
                    jobs = readJobMapImpl(fis, rtcGood);
                    if (jobs != null) {
                        long now = sElapsedRealtimeClock.millis();
                        for (int i=0; i<jobs.size(); i++) {
                            JobStatus js = jobs.get(i);
                            js.prepareLocked();
                            js.enqueueTime = now;
                            this.jobSet.add(js);

                            numJobs++;
                            if (js.getUid() == Process.SYSTEM_UID) {
                                numSystemJobs++;
                                if (isSyncJob(js)) {
                                    numSyncJobs++;
                                }
                            }
                        }
                    }
                }
            } catch (FileNotFoundException e) {
                if (DEBUG) {
                    Slog.d(TAG, "Could not find jobs file, probably there was nothing to load.");
                }
            } catch (XmlPullParserException | IOException e) {
                Slog.wtf(TAG, "Error jobstore xml.", e);
            } finally {
                if (mPersistInfo.countAllJobsLoaded < 0) { // Only set them once.
                    mPersistInfo.countAllJobsLoaded = numJobs;
                    mPersistInfo.countSystemServerJobsLoaded = numSystemJobs;
                    mPersistInfo.countSystemSyncManagerJobsLoaded = numSyncJobs;
                }
            }
            Slog.i(TAG, "Read " + numJobs + " jobs");
        }

        private List<JobStatus> readJobMapImpl(InputStream fis, boolean rtcIsGood)
                throws XmlPullParserException, IOException {
            XmlPullParser parser = Xml.resolvePullParser(fis);

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG &&
                    eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next();
                Slog.d(TAG, "Start tag: " + parser.getName());
            }
            if (eventType == XmlPullParser.END_DOCUMENT) {
                if (DEBUG) {
                    Slog.d(TAG, "No persisted jobs.");
                }
                return null;
            }

            String tagName = parser.getName();
            if ("job-info".equals(tagName)) {
                final List<JobStatus> jobs = new ArrayList<JobStatus>();
                // Read in version info.
                try {
                    int version = Integer.parseInt(parser.getAttributeValue(null, "version"));
                    if (version != JOBS_FILE_VERSION) {
                        Slog.d(TAG, "Invalid version number, aborting jobs file read.");
                        return null;
                    }
                } catch (NumberFormatException e) {
                    Slog.e(TAG, "Invalid version number, aborting jobs file read.");
                    return null;
                }
                eventType = parser.next();
                do {
                    // Read each <job/>
                    if (eventType == XmlPullParser.START_TAG) {
                        tagName = parser.getName();
                        // Start reading job.
                        if ("job".equals(tagName)) {
                            JobStatus persistedJob = restoreJobFromXml(rtcIsGood, parser);
                            if (persistedJob != null) {
                                if (DEBUG) {
                                    Slog.d(TAG, "Read out " + persistedJob);
                                }
                                jobs.add(persistedJob);
                            } else {
                                Slog.d(TAG, "Error reading job from file.");
                            }
                        }
                    }
                    eventType = parser.next();
                } while (eventType != XmlPullParser.END_DOCUMENT);
                return jobs;
            }
            return null;
        }

        /**
         * @param parser Xml parser at the beginning of a "<job/>" tag. The next "parser.next()" call
         *               will take the parser into the body of the job tag.
         * @return Newly instantiated job holding all the information we just read out of the xml tag.
         */
        private JobStatus restoreJobFromXml(boolean rtcIsGood, XmlPullParser parser)
                throws XmlPullParserException, IOException {
            JobInfo.Builder jobBuilder;
            int uid, sourceUserId;
            long lastSuccessfulRunTime;
            long lastFailedRunTime;
            int internalFlags = 0;

            // Read out job identifier attributes and priority.
            try {
                jobBuilder = buildBuilderFromXml(parser);
                jobBuilder.setPersisted(true);
                uid = Integer.parseInt(parser.getAttributeValue(null, "uid"));

                String val = parser.getAttributeValue(null, "priority");
                if (val != null) {
                    jobBuilder.setPriority(Integer.parseInt(val));
                }
                val = parser.getAttributeValue(null, "flags");
                if (val != null) {
                    jobBuilder.setFlags(Integer.parseInt(val));
                }
                val = parser.getAttributeValue(null, "internalFlags");
                if (val != null) {
                    internalFlags = Integer.parseInt(val);
                }
                val = parser.getAttributeValue(null, "sourceUserId");
                sourceUserId = val == null ? -1 : Integer.parseInt(val);

                val = parser.getAttributeValue(null, "lastSuccessfulRunTime");
                lastSuccessfulRunTime = val == null ? 0 : Long.parseLong(val);

                val = parser.getAttributeValue(null, "lastFailedRunTime");
                lastFailedRunTime = val == null ? 0 : Long.parseLong(val);
            } catch (NumberFormatException e) {
                Slog.e(TAG, "Error parsing job's required fields, skipping");
                return null;
            }

            String sourcePackageName = parser.getAttributeValue(null, "sourcePackageName");
            final String sourceTag = parser.getAttributeValue(null, "sourceTag");

            int eventType;
            // Read out constraints tag.
            do {
                eventType = parser.next();
            } while (eventType == XmlPullParser.TEXT);  // Push through to next START_TAG.

            if (!(eventType == XmlPullParser.START_TAG &&
                    XML_TAG_PARAMS_CONSTRAINTS.equals(parser.getName()))) {
                // Expecting a <constraints> start tag.
                return null;
            }
            try {
                buildConstraintsFromXml(jobBuilder, parser);
            } catch (NumberFormatException e) {
                Slog.d(TAG, "Error reading constraints, skipping.");
                return null;
            } catch (XmlPullParserException e) {
                Slog.d(TAG, "Error Parser Exception.", e);
                return null;
            } catch (IOException e) {
                Slog.d(TAG, "Error I/O Exception.", e);
                return null;
            }

            parser.next(); // Consume </constraints>

            // Read out execution parameters tag.
            do {
                eventType = parser.next();
            } while (eventType == XmlPullParser.TEXT);
            if (eventType != XmlPullParser.START_TAG) {
                return null;
            }

            // Tuple of (earliest runtime, latest runtime) in UTC.
            final Pair<Long, Long> rtcRuntimes;
            try {
                rtcRuntimes = buildRtcExecutionTimesFromXml(parser);
            } catch (NumberFormatException e) {
                if (DEBUG) {
                    Slog.d(TAG, "Error parsing execution time parameters, skipping.");
                }
                return null;
            }

            final long elapsedNow = sElapsedRealtimeClock.millis();
            Pair<Long, Long> elapsedRuntimes = convertRtcBoundsToElapsed(rtcRuntimes, elapsedNow);

            if (XML_TAG_PERIODIC.equals(parser.getName())) {
                try {
                    String val = parser.getAttributeValue(null, "period");
                    final long periodMillis = Long.parseLong(val);
                    val = parser.getAttributeValue(null, "flex");
                    final long flexMillis = (val != null) ? Long.valueOf(val) : periodMillis;
                    jobBuilder.setPeriodic(periodMillis, flexMillis);
                    // As a sanity check, cap the recreated run time to be no later than flex+period
                    // from now. This is the latest the periodic could be pushed out. This could
                    // happen if the periodic ran early (at flex time before period), and then the
                    // device rebooted.
                    if (elapsedRuntimes.second > elapsedNow + periodMillis + flexMillis) {
                        final long clampedLateRuntimeElapsed = elapsedNow + flexMillis
                                + periodMillis;
                        final long clampedEarlyRuntimeElapsed = clampedLateRuntimeElapsed
                                - flexMillis;
                        Slog.w(TAG,
                                String.format("Periodic job for uid='%d' persisted run-time is" +
                                                " too big [%s, %s]. Clamping to [%s,%s]",
                                        uid,
                                        DateUtils.formatElapsedTime(elapsedRuntimes.first / 1000),
                                        DateUtils.formatElapsedTime(elapsedRuntimes.second / 1000),
                                        DateUtils.formatElapsedTime(
                                                clampedEarlyRuntimeElapsed / 1000),
                                        DateUtils.formatElapsedTime(
                                                clampedLateRuntimeElapsed / 1000))
                        );
                        elapsedRuntimes =
                                Pair.create(clampedEarlyRuntimeElapsed, clampedLateRuntimeElapsed);
                    }
                } catch (NumberFormatException e) {
                    Slog.d(TAG, "Error reading periodic execution criteria, skipping.");
                    return null;
                }
            } else if (XML_TAG_ONEOFF.equals(parser.getName())) {
                try {
                    if (elapsedRuntimes.first != JobStatus.NO_EARLIEST_RUNTIME) {
                        jobBuilder.setMinimumLatency(elapsedRuntimes.first - elapsedNow);
                    }
                    if (elapsedRuntimes.second != JobStatus.NO_LATEST_RUNTIME) {
                        jobBuilder.setOverrideDeadline(
                                elapsedRuntimes.second - elapsedNow);
                    }
                } catch (NumberFormatException e) {
                    Slog.d(TAG, "Error reading job execution criteria, skipping.");
                    return null;
                }
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "Invalid parameter tag, skipping - " + parser.getName());
                }
                // Expecting a parameters start tag.
                return null;
            }
            maybeBuildBackoffPolicyFromXml(jobBuilder, parser);

            parser.nextTag(); // Consume parameters end tag.

            // Read out extras Bundle.
            do {
                eventType = parser.next();
            } while (eventType == XmlPullParser.TEXT);
            if (!(eventType == XmlPullParser.START_TAG
                    && XML_TAG_EXTRAS.equals(parser.getName()))) {
                if (DEBUG) {
                    Slog.d(TAG, "Error reading extras, skipping.");
                }
                return null;
            }

            PersistableBundle extras = PersistableBundle.restoreFromXml(parser);
            jobBuilder.setExtras(extras);
            parser.nextTag(); // Consume </extras>

            final JobInfo builtJob;
            try {
                builtJob = jobBuilder.build();
            } catch (Exception e) {
                Slog.w(TAG, "Unable to build job from XML, ignoring: "
                        + jobBuilder.summarize());
                return null;
            }

            // Migrate sync jobs forward from earlier, incomplete representation
            if ("android".equals(sourcePackageName)
                    && extras != null
                    && extras.getBoolean("SyncManagerJob", false)) {
                sourcePackageName = extras.getString("owningPackage", sourcePackageName);
                if (DEBUG) {
                    Slog.i(TAG, "Fixing up sync job source package name from 'android' to '"
                            + sourcePackageName + "'");
                }
            }

            // And now we're done
            JobSchedulerInternal service = LocalServices.getService(JobSchedulerInternal.class);
            final int appBucket = JobSchedulerService.standbyBucketForPackage(sourcePackageName,
                    sourceUserId, elapsedNow);
            JobStatus js = new JobStatus(
                    jobBuilder.build(), uid, sourcePackageName, sourceUserId,
                    appBucket, sourceTag,
                    elapsedRuntimes.first, elapsedRuntimes.second,
                    lastSuccessfulRunTime, lastFailedRunTime,
                    (rtcIsGood) ? null : rtcRuntimes, internalFlags, /* dynamicConstraints */ 0);
            return js;
        }

        private JobInfo.Builder buildBuilderFromXml(XmlPullParser parser) throws NumberFormatException {
            // Pull out required fields from <job> attributes.
            int jobId = Integer.parseInt(parser.getAttributeValue(null, "jobid"));
            String packageName = parser.getAttributeValue(null, "package");
            String className = parser.getAttributeValue(null, "class");
            ComponentName cname = new ComponentName(packageName, className);

            return new JobInfo.Builder(jobId, cname);
        }

        /**
         * In S, there has been a change in format to make the code more robust and more
         * maintainable.
         * If the capabities are bits 4, 14, 15, the format in R, it is a long string as
         * netCapabilitiesLong = '49168' from the old XML file attribute "net-capabilities".
         * The format in S is the int array string as netCapabilitiesIntArray = '4,14,15'
         * from the new XML file attribute "net-capabilities-array".
         * For backward compatibility, when reading old XML the old format is still supported in
         * reading, but in order to avoid issues with OEM-defined flags, the accepted capabilities
         * are limited to that(maxNetCapabilityInR & maxTransportInR) defined in R.
         */
        private void buildConstraintsFromXml(JobInfo.Builder jobBuilder, XmlPullParser parser)
                throws XmlPullParserException, IOException {
            String val;
            String netCapabilitiesLong = null;
            String netForbiddenCapabilitiesLong = null;
            String netTransportTypesLong = null;

            final String netCapabilitiesIntArray = parser.getAttributeValue(
                    null, "net-capabilities-csv");
            final String netForbiddenCapabilitiesIntArray = parser.getAttributeValue(
                    null, "net-forbidden-capabilities-csv");
            final String netTransportTypesIntArray = parser.getAttributeValue(
                    null, "net-transport-types-csv");
            if (netCapabilitiesIntArray == null || netTransportTypesIntArray == null) {
                netCapabilitiesLong = parser.getAttributeValue(null, "net-capabilities");
                netForbiddenCapabilitiesLong = parser.getAttributeValue(
                        null, "net-unwanted-capabilities");
                netTransportTypesLong = parser.getAttributeValue(null, "net-transport-types");
            }

            if ((netCapabilitiesIntArray != null) && (netTransportTypesIntArray != null)) {
                final NetworkRequest.Builder builder = new NetworkRequest.Builder()
                        .clearCapabilities();

                for (int capability : stringToIntArray(netCapabilitiesIntArray)) {
                    builder.addCapability(capability);
                }

                for (int forbiddenCapability : stringToIntArray(netForbiddenCapabilitiesIntArray)) {
                    builder.addForbiddenCapability(forbiddenCapability);
                }

                for (int transport : stringToIntArray(netTransportTypesIntArray)) {
                    builder.addTransportType(transport);
                }
                jobBuilder.setRequiredNetwork(builder.build());
            } else if (netCapabilitiesLong != null && netTransportTypesLong != null) {
                final NetworkRequest.Builder builder = new NetworkRequest.Builder()
                        .clearCapabilities();
                final int maxNetCapabilityInR = NET_CAPABILITY_TEMPORARILY_NOT_METERED;
                // We're okay throwing NFE here; caught by caller
                for (int capability : BitUtils.unpackBits(Long.parseLong(
                        netCapabilitiesLong))) {
                    if (capability <= maxNetCapabilityInR) {
                        builder.addCapability(capability);
                    }
                }
                for (int forbiddenCapability : BitUtils.unpackBits(Long.parseLong(
                        netForbiddenCapabilitiesLong))) {
                    if (forbiddenCapability <= maxNetCapabilityInR) {
                        builder.addForbiddenCapability(forbiddenCapability);
                    }
                }

                final int maxTransportInR = TRANSPORT_TEST;
                for (int transport : BitUtils.unpackBits(Long.parseLong(
                        netTransportTypesLong))) {
                    if (transport <= maxTransportInR) {
                        builder.addTransportType(transport);
                    }
                }
                jobBuilder.setRequiredNetwork(builder.build());
            } else {
                // Read legacy values
                val = parser.getAttributeValue(null, "connectivity");
                if (val != null) {
                    jobBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
                }
                val = parser.getAttributeValue(null, "metered");
                if (val != null) {
                    jobBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_METERED);
                }
                val = parser.getAttributeValue(null, "unmetered");
                if (val != null) {
                    jobBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);
                }
                val = parser.getAttributeValue(null, "not-roaming");
                if (val != null) {
                    jobBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_NOT_ROAMING);
                }
            }

            val = parser.getAttributeValue(null, "idle");
            if (val != null) {
                jobBuilder.setRequiresDeviceIdle(true);
            }
            val = parser.getAttributeValue(null, "charging");
            if (val != null) {
                jobBuilder.setRequiresCharging(true);
            }
            val = parser.getAttributeValue(null, "battery-not-low");
            if (val != null) {
                jobBuilder.setRequiresBatteryNotLow(true);
            }
            val = parser.getAttributeValue(null, "storage-not-low");
            if (val != null) {
                jobBuilder.setRequiresStorageNotLow(true);
            }
        }

        /**
         * Builds the back-off policy out of the params tag. These attributes may not exist, depending
         * on whether the back-off was set when the job was first scheduled.
         */
        private void maybeBuildBackoffPolicyFromXml(JobInfo.Builder jobBuilder, XmlPullParser parser) {
            String val = parser.getAttributeValue(null, "initial-backoff");
            if (val != null) {
                long initialBackoff = Long.parseLong(val);
                val = parser.getAttributeValue(null, "backoff-policy");
                int backoffPolicy = Integer.parseInt(val);  // Will throw NFE which we catch higher up.
                jobBuilder.setBackoffCriteria(initialBackoff, backoffPolicy);
            }
        }

        /**
         * Extract a job's earliest/latest run time data from XML.  These are returned in
         * unadjusted UTC wall clock time, because we do not yet know whether the system
         * clock is reliable for purposes of calculating deltas from 'now'.
         *
         * @param parser
         * @return A Pair of timestamps in UTC wall-clock time.  The first is the earliest
         *     time at which the job is to become runnable, and the second is the deadline at
         *     which it becomes overdue to execute.
         * @throws NumberFormatException
         */
        private Pair<Long, Long> buildRtcExecutionTimesFromXml(XmlPullParser parser)
                throws NumberFormatException {
            String val;
            // Pull out execution time data.
            val = parser.getAttributeValue(null, "delay");
            final long earliestRunTimeRtc = (val != null)
                    ? Long.parseLong(val)
                    : JobStatus.NO_EARLIEST_RUNTIME;
            val = parser.getAttributeValue(null, "deadline");
            final long latestRunTimeRtc = (val != null)
                    ? Long.parseLong(val)
                    : JobStatus.NO_LATEST_RUNTIME;
            return Pair.create(earliestRunTimeRtc, latestRunTimeRtc);
        }
    }

    /** Set of all tracked jobs. */
    @VisibleForTesting
    public static final class JobSet {
        @VisibleForTesting // Key is the getUid() originator of the jobs in each sheaf
        final SparseArray<ArraySet<JobStatus>> mJobs;

        @VisibleForTesting // Same data but with the key as getSourceUid() of the jobs in each sheaf
        final SparseArray<ArraySet<JobStatus>> mJobsPerSourceUid;

        public JobSet() {
            mJobs = new SparseArray<ArraySet<JobStatus>>();
            mJobsPerSourceUid = new SparseArray<>();
        }

        public List<JobStatus> getJobsByUid(int uid) {
            ArrayList<JobStatus> matchingJobs = new ArrayList<JobStatus>();
            ArraySet<JobStatus> jobs = mJobs.get(uid);
            if (jobs != null) {
                matchingJobs.addAll(jobs);
            }
            return matchingJobs;
        }

        // By user, not by uid, so we need to traverse by key and check
        public List<JobStatus> getJobsByUser(int userId) {
            final ArrayList<JobStatus> result = new ArrayList<JobStatus>();
            for (int i = mJobsPerSourceUid.size() - 1; i >= 0; i--) {
                if (UserHandle.getUserId(mJobsPerSourceUid.keyAt(i)) == userId) {
                    final ArraySet<JobStatus> jobs = mJobsPerSourceUid.valueAt(i);
                    if (jobs != null) {
                        result.addAll(jobs);
                    }
                }
            }
            return result;
        }

        public boolean add(JobStatus job) {
            final int uid = job.getUid();
            final int sourceUid = job.getSourceUid();
            ArraySet<JobStatus> jobs = mJobs.get(uid);
            if (jobs == null) {
                jobs = new ArraySet<JobStatus>();
                mJobs.put(uid, jobs);
            }
            ArraySet<JobStatus> jobsForSourceUid = mJobsPerSourceUid.get(sourceUid);
            if (jobsForSourceUid == null) {
                jobsForSourceUid = new ArraySet<>();
                mJobsPerSourceUid.put(sourceUid, jobsForSourceUid);
            }
            final boolean added = jobs.add(job);
            final boolean addedInSource = jobsForSourceUid.add(job);
            if (added != addedInSource) {
                Slog.wtf(TAG, "mJobs and mJobsPerSourceUid mismatch; caller= " + added
                        + " source= " + addedInSource);
            }
            return added || addedInSource;
        }

        public boolean remove(JobStatus job) {
            final int uid = job.getUid();
            final ArraySet<JobStatus> jobs = mJobs.get(uid);
            final int sourceUid = job.getSourceUid();
            final ArraySet<JobStatus> jobsForSourceUid = mJobsPerSourceUid.get(sourceUid);
            final boolean didRemove = jobs != null && jobs.remove(job);
            final boolean sourceRemove = jobsForSourceUid != null && jobsForSourceUid.remove(job);
            if (didRemove != sourceRemove) {
                Slog.wtf(TAG, "Job presence mismatch; caller=" + didRemove
                        + " source=" + sourceRemove);
            }
            if (didRemove || sourceRemove) {
                // no more jobs for this uid?  let the now-empty set objects be GC'd.
                if (jobs != null && jobs.size() == 0) {
                    mJobs.remove(uid);
                }
                if (jobsForSourceUid != null && jobsForSourceUid.size() == 0) {
                    mJobsPerSourceUid.remove(sourceUid);
                }
                return true;
            }
            return false;
        }

        /**
         * Removes the jobs of all users not specified by the keepUserIds of user ids.
         * This will remove jobs scheduled *by* and *for* any unlisted users.
         */
        public void removeJobsOfUnlistedUsers(final int[] keepUserIds) {
            final Predicate<JobStatus> noSourceUser =
                    job -> !ArrayUtils.contains(keepUserIds, job.getSourceUserId());
            final Predicate<JobStatus> noCallingUser =
                    job -> !ArrayUtils.contains(keepUserIds, job.getUserId());
            removeAll(noSourceUser.or(noCallingUser));
        }

        private void removeAll(Predicate<JobStatus> predicate) {
            for (int jobSetIndex = mJobs.size() - 1; jobSetIndex >= 0; jobSetIndex--) {
                final ArraySet<JobStatus> jobs = mJobs.valueAt(jobSetIndex);
                jobs.removeIf(predicate);
                if (jobs.size() == 0) {
                    mJobs.removeAt(jobSetIndex);
                }
            }
            for (int jobSetIndex = mJobsPerSourceUid.size() - 1; jobSetIndex >= 0; jobSetIndex--) {
                final ArraySet<JobStatus> jobs = mJobsPerSourceUid.valueAt(jobSetIndex);
                jobs.removeIf(predicate);
                if (jobs.size() == 0) {
                    mJobsPerSourceUid.removeAt(jobSetIndex);
                }
            }
        }

        public boolean contains(JobStatus job) {
            final int uid = job.getUid();
            ArraySet<JobStatus> jobs = mJobs.get(uid);
            return jobs != null && jobs.contains(job);
        }

        public JobStatus get(int uid, int jobId) {
            ArraySet<JobStatus> jobs = mJobs.get(uid);
            if (jobs != null) {
                for (int i = jobs.size() - 1; i >= 0; i--) {
                    JobStatus job = jobs.valueAt(i);
                    if (job.getJobId() == jobId) {
                        return job;
                    }
                }
            }
            return null;
        }

        // Inefficient; use only for testing
        public List<JobStatus> getAllJobs() {
            ArrayList<JobStatus> allJobs = new ArrayList<JobStatus>(size());
            for (int i = mJobs.size() - 1; i >= 0; i--) {
                ArraySet<JobStatus> jobs = mJobs.valueAt(i);
                if (jobs != null) {
                    // Use a for loop over the ArraySet, so we don't need to make its
                    // optional collection class iterator implementation or have to go
                    // through a temporary array from toArray().
                    for (int j = jobs.size() - 1; j >= 0; j--) {
                        allJobs.add(jobs.valueAt(j));
                    }
                }
            }
            return allJobs;
        }

        public void clear() {
            mJobs.clear();
            mJobsPerSourceUid.clear();
        }

        public int size() {
            int total = 0;
            for (int i = mJobs.size() - 1; i >= 0; i--) {
                total += mJobs.valueAt(i).size();
            }
            return total;
        }

        // We only want to count the jobs that this uid has scheduled on its own
        // behalf, not those that the app has scheduled on someone else's behalf.
        public int countJobsForUid(int uid) {
            int total = 0;
            ArraySet<JobStatus> jobs = mJobs.get(uid);
            if (jobs != null) {
                for (int i = jobs.size() - 1; i >= 0; i--) {
                    JobStatus job = jobs.valueAt(i);
                    if (job.getUid() == job.getSourceUid()) {
                        total++;
                    }
                }
            }
            return total;
        }

        public void forEachJob(@Nullable Predicate<JobStatus> filterPredicate,
                Consumer<JobStatus> functor) {
            for (int uidIndex = mJobs.size() - 1; uidIndex >= 0; uidIndex--) {
                ArraySet<JobStatus> jobs = mJobs.valueAt(uidIndex);
                if (jobs != null) {
                    for (int i = jobs.size() - 1; i >= 0; i--) {
                        final JobStatus jobStatus = jobs.valueAt(i);
                        if ((filterPredicate == null) || filterPredicate.test(jobStatus)) {
                            functor.accept(jobStatus);
                        }
                    }
                }
            }
        }

        public void forEachJob(int callingUid, Consumer<JobStatus> functor) {
            ArraySet<JobStatus> jobs = mJobs.get(callingUid);
            if (jobs != null) {
                for (int i = jobs.size() - 1; i >= 0; i--) {
                    functor.accept(jobs.valueAt(i));
                }
            }
        }

        public void forEachJobForSourceUid(int sourceUid, Consumer<JobStatus> functor) {
            final ArraySet<JobStatus> jobs = mJobsPerSourceUid.get(sourceUid);
            if (jobs != null) {
                for (int i = jobs.size() - 1; i >= 0; i--) {
                    functor.accept(jobs.valueAt(i));
                }
            }
        }
    }
}
