package com.android.server.job;

import static android.net.NetworkCapabilities.NET_CAPABILITY_IMS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_OEM_PAID;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import static com.android.server.job.JobStore.JOB_FILE_SPLIT_PREFIX;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.job.JobInfo;
import android.app.job.JobInfo.Builder;
import android.app.job.JobWorkItem;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.test.RenamingDelegatingContext;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;
import com.android.server.job.JobStore.JobSet;
import com.android.server.job.controllers.JobStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Test reading and writing correctly from file.
 *
 * atest $ANDROID_BUILD_TOP/frameworks/base/services/tests/servicestests/src/com/android/server/job/JobStoreTest.java
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class JobStoreTest {
    private static final String TAG = "TaskStoreTest";
    private static final String TEST_PREFIX = "_test_";

    private static final int SOME_UID = android.os.Process.FIRST_APPLICATION_UID;
    private ComponentName mComponent;

    JobStore mTaskStoreUnderTest;
    Context mTestContext;

    private Context getContext() {
        return InstrumentationRegistry.getContext();
    }

    @Before
    public void setUp() throws Exception {
        mTestContext = new RenamingDelegatingContext(getContext(), TEST_PREFIX);
        Log.d(TAG, "Saving tasks to '" + mTestContext.getFilesDir() + "'");
        mTaskStoreUnderTest =
                JobStore.initAndGetForTesting(mTestContext, mTestContext.getFilesDir());
        mComponent = new ComponentName(getContext().getPackageName(), StubClass.class.getName());

        // Assume all packages are current SDK
        final PackageManagerInternal pm = mock(PackageManagerInternal.class);
        when(pm.getPackageTargetSdkVersion(anyString()))
                .thenReturn(Build.VERSION_CODES.CUR_DEVELOPMENT);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, pm);

        // Freeze the clocks at this moment in time
        JobSchedulerService.sSystemClock =
                Clock.fixed(Clock.systemUTC().instant(), ZoneOffset.UTC);
        JobSchedulerService.sUptimeMillisClock =
                Clock.fixed(SystemClock.uptimeClock().instant(), ZoneOffset.UTC);
        JobSchedulerService.sElapsedRealtimeClock =
                Clock.fixed(SystemClock.elapsedRealtimeClock().instant(), ZoneOffset.UTC);
    }

    @After
    public void tearDown() throws Exception {
        mTaskStoreUnderTest.clear();
        mTaskStoreUnderTest.waitForWriteToCompleteForTesting(5_000L);
    }

    private void setUseSplitFiles(boolean useSplitFiles) throws Exception {
        mTaskStoreUnderTest.setUseSplitFiles(useSplitFiles);
        waitForPendingIo();
    }

    private void waitForPendingIo() throws Exception {
        assertTrue("Timed out waiting for persistence I/O to complete",
                mTaskStoreUnderTest.waitForWriteToCompleteForTesting(5_000L));
    }

    /** Test that we properly remove the last job of an app from the persisted file. */
    @Test
    public void testRemovingLastJob_singleFile() throws Exception {
        setUseSplitFiles(false);
        runRemovingLastJob();
    }

    /** Test that we properly remove the last job of an app from the persisted file. */
    @Test
    public void testRemovingLastJob_splitFiles() throws Exception {
        setUseSplitFiles(true);
        runRemovingLastJob();
    }

    private void runRemovingLastJob() throws Exception {
        final JobInfo task1 = new Builder(8, mComponent)
                .setRequiresDeviceIdle(true)
                .setPeriodic(10000L)
                .setRequiresCharging(true)
                .setPersisted(true)
                .build();
        final JobInfo task2 = new Builder(12, mComponent)
                .setMinimumLatency(5000L)
                .setBackoffCriteria(15000L, JobInfo.BACKOFF_POLICY_LINEAR)
                .setOverrideDeadline(30000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPersisted(true)
                .build();
        final int uid1 = SOME_UID;
        final int uid2 = uid1 + 1;
        final JobStatus JobStatus1 = JobStatus.createFromJobInfo(task1, uid1, null, -1, null, null);
        final JobStatus JobStatus2 = JobStatus.createFromJobInfo(task2, uid2, null, -1, null, null);
        runWritingJobsToDisk(JobStatus1, JobStatus2);

        // Remove 1 job
        mTaskStoreUnderTest.remove(JobStatus1, true);
        waitForPendingIo();
        JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        assertEquals("Incorrect # of persisted tasks.", 1, jobStatusSet.size());
        JobStatus loaded = jobStatusSet.getAllJobs().iterator().next();

        assertJobsEqual(JobStatus2, loaded);
        assertTrue("JobStore#contains invalid.", mTaskStoreUnderTest.containsJob(JobStatus2));

        // Remove 2nd job
        mTaskStoreUnderTest.remove(JobStatus2, true);
        waitForPendingIo();
        jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        assertEquals("Incorrect # of persisted tasks.", 0, jobStatusSet.size());
    }

    /** Test that we properly clear the persisted file when all jobs are dropped. */
    @Test
    public void testClearJobs_singleFile() throws Exception {
        setUseSplitFiles(false);
        runClearJobs();
    }

    /** Test that we properly clear the persisted file when all jobs are dropped. */
    @Test
    public void testClearJobs_splitFiles() throws Exception {
        setUseSplitFiles(true);
        runClearJobs();
    }

    private void runClearJobs() throws Exception {
        final JobInfo task1 = new Builder(8, mComponent)
                .setRequiresDeviceIdle(true)
                .setPeriodic(10000L)
                .setRequiresCharging(true)
                .setPersisted(true)
                .build();
        final JobInfo task2 = new Builder(12, mComponent)
                .setMinimumLatency(5000L)
                .setBackoffCriteria(15000L, JobInfo.BACKOFF_POLICY_LINEAR)
                .setOverrideDeadline(30000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPersisted(true)
                .build();
        final int uid1 = SOME_UID;
        final int uid2 = uid1 + 1;
        final JobStatus JobStatus1 = JobStatus.createFromJobInfo(task1, uid1, null, -1, null, null);
        final JobStatus JobStatus2 = JobStatus.createFromJobInfo(task2, uid2, null, -1, null, null);
        runWritingJobsToDisk(JobStatus1, JobStatus2);

        // Remove all jobs
        mTaskStoreUnderTest.clear();
        waitForPendingIo();
        JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        assertEquals("Incorrect # of persisted tasks.", 0, jobStatusSet.size());
    }

    @Test
    public void testSkipExtraFiles() throws Exception {
        setUseSplitFiles(true);
        final JobInfo task1 = new Builder(8, mComponent)
                .setRequiresDeviceIdle(true)
                .setPeriodic(10000L)
                .setRequiresCharging(true)
                .setPersisted(true)
                .build();
        final JobInfo task2 = new Builder(12, mComponent)
                .setMinimumLatency(5000L)
                .setBackoffCriteria(15000L, JobInfo.BACKOFF_POLICY_LINEAR)
                .setOverrideDeadline(30000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPersisted(true)
                .build();
        final int uid1 = SOME_UID;
        final int uid2 = uid1 + 1;
        final JobStatus JobStatus1 = JobStatus.createFromJobInfo(task1, uid1, null, -1, null, null);
        final JobStatus JobStatus2 = JobStatus.createFromJobInfo(task2, uid2, null, -1, null, null);
        runWritingJobsToDisk(JobStatus1, JobStatus2);

        final File rootDir = new File(mTestContext.getFilesDir(), "system/job");
        final File file1 = new File(rootDir, JOB_FILE_SPLIT_PREFIX + uid1 + ".xml");
        final File file2 = new File(rootDir, JOB_FILE_SPLIT_PREFIX + uid2 + ".xml");

        Files.copy(file1.toPath(),
                new File(rootDir, JOB_FILE_SPLIT_PREFIX + uid1 + ".xml.bak").toPath());
        Files.copy(file1.toPath(), new File(rootDir, "random.xml").toPath());
        Files.copy(file2.toPath(),
                new File(rootDir, "blah" + JOB_FILE_SPLIT_PREFIX + uid1 + ".xml").toPath());

        JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        assertEquals("Incorrect # of persisted tasks.", 2, jobStatusSet.size());
    }

    /**
     * Test that dynamic constraints aren't written to disk.
     */
    @Test
    public void testDynamicConstraintsNotPersisted() throws Exception {
        JobInfo.Builder b = new Builder(42, mComponent).setPersisted(true);
        JobStatus js = JobStatus.createFromJobInfo(b.build(), SOME_UID, null, -1, null, null);
        js.addDynamicConstraints(JobStatus.CONSTRAINT_BATTERY_NOT_LOW
                | JobStatus.CONSTRAINT_CHARGING
                | JobStatus.CONSTRAINT_IDLE
                | JobStatus.CONSTRAINT_STORAGE_NOT_LOW);
        assertTrue(js.hasBatteryNotLowConstraint());
        assertTrue(js.hasChargingConstraint());
        assertTrue(js.hasIdleConstraint());
        assertTrue(js.hasStorageNotLowConstraint());
        mTaskStoreUnderTest.add(js);
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        assertEquals("Job count is incorrect.", 1, jobStatusSet.size());
        JobStatus loaded = jobStatusSet.getAllJobs().iterator().next();
        assertFalse(loaded.hasBatteryNotLowConstraint());
        assertFalse(loaded.hasChargingConstraint());
        assertFalse(loaded.hasIdleConstraint());
        assertFalse(loaded.hasStorageNotLowConstraint());
    }

    @Test
    public void testExtractUidFromJobFileName() {
        File file = new File(mTestContext.getFilesDir(), "randomName");
        assertEquals(JobStore.INVALID_UID, JobStore.extractUidFromJobFileName(file));

        file = new File(mTestContext.getFilesDir(), "jobs.xml");
        assertEquals(JobStore.INVALID_UID, JobStore.extractUidFromJobFileName(file));

        file = new File(mTestContext.getFilesDir(), ".xml");
        assertEquals(JobStore.INVALID_UID, JobStore.extractUidFromJobFileName(file));

        file = new File(mTestContext.getFilesDir(), "1000.xml");
        assertEquals(JobStore.INVALID_UID, JobStore.extractUidFromJobFileName(file));

        file = new File(mTestContext.getFilesDir(), "10000");
        assertEquals(JobStore.INVALID_UID, JobStore.extractUidFromJobFileName(file));

        file = new File(mTestContext.getFilesDir(), JOB_FILE_SPLIT_PREFIX);
        assertEquals(JobStore.INVALID_UID, JobStore.extractUidFromJobFileName(file));

        file = new File(mTestContext.getFilesDir(), JOB_FILE_SPLIT_PREFIX + "text.xml");
        assertEquals(JobStore.INVALID_UID, JobStore.extractUidFromJobFileName(file));

        file = new File(mTestContext.getFilesDir(), JOB_FILE_SPLIT_PREFIX + ".xml");
        assertEquals(JobStore.INVALID_UID, JobStore.extractUidFromJobFileName(file));

        file = new File(mTestContext.getFilesDir(), JOB_FILE_SPLIT_PREFIX + "-10123.xml");
        assertEquals(JobStore.INVALID_UID, JobStore.extractUidFromJobFileName(file));

        file = new File(mTestContext.getFilesDir(), JOB_FILE_SPLIT_PREFIX + "1.xml");
        assertEquals(1, JobStore.extractUidFromJobFileName(file));

        file = new File(mTestContext.getFilesDir(), JOB_FILE_SPLIT_PREFIX + "101023.xml");
        assertEquals(101023, JobStore.extractUidFromJobFileName(file));
    }

    @Test
    public void testStringToIntArrayAndIntArrayToString() {
        final int[] netCapabilitiesIntArray = { 1, 3, 5, 7, 9 };
        final String netCapabilitiesStr = "1,3,5,7,9";
        final String netCapabilitiesStrWithErrorInt = "1,3,a,7,9";
        final String emptyString = "";
        final String str1 = JobStore.intArrayToString(netCapabilitiesIntArray);
        assertArrayEquals(netCapabilitiesIntArray, JobStore.stringToIntArray(str1));
        assertEquals(0, JobStore.stringToIntArray(emptyString).length);
        assertThrows(NumberFormatException.class,
                () -> JobStore.stringToIntArray(netCapabilitiesStrWithErrorInt));
        assertEquals(netCapabilitiesStr, JobStore.intArrayToString(netCapabilitiesIntArray));
    }

    @Test
    public void testMaybeWriteStatusToDisk() throws Exception {
        int taskId = 5;
        long runByMillis = 20000L; // 20s
        long runFromMillis = 2000L; // 2s
        long initialBackoff = 10000L; // 10s

        final JobInfo task = new Builder(taskId, mComponent)
                .setRequiresCharging(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(initialBackoff, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setOverrideDeadline(runByMillis)
                .setMinimumLatency(runFromMillis)
                .setPersisted(true)
                .build();
        final JobStatus ts = JobStatus.createFromJobInfo(task, SOME_UID, null, -1, null, null);
        ts.addInternalFlags(JobStatus.INTERNAL_FLAG_HAS_FOREGROUND_EXEMPTION);
        mTaskStoreUnderTest.add(ts);
        waitForPendingIo();

        // Manually load tasks from xml file.
        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);

        assertEquals("Didn't get expected number of persisted tasks.", 1, jobStatusSet.size());
        final JobStatus loadedTaskStatus = jobStatusSet.getAllJobs().get(0);
        assertJobsEqual(ts, loadedTaskStatus);
        assertTrue("JobStore#contains invalid.", mTaskStoreUnderTest.containsJob(ts));
    }

    @Test
    public void testWritingTwoJobsToDisk_singleFile() throws Exception {
        setUseSplitFiles(false);
        runWritingTwoJobsToDisk();
    }

    @Test
    public void testWritingTwoJobsToDisk_splitFiles() throws Exception {
        setUseSplitFiles(true);
        runWritingTwoJobsToDisk();
    }

    private void runWritingTwoJobsToDisk() throws Exception {
        final JobInfo task1 = new Builder(8, mComponent)
                .setRequiresDeviceIdle(true)
                .setPeriodic(10000L)
                .setRequiresCharging(true)
                .setPersisted(true)
                .build();
        final JobInfo task2 = new Builder(12, mComponent)
                .setMinimumLatency(5000L)
                .setBackoffCriteria(15000L, JobInfo.BACKOFF_POLICY_LINEAR)
                .setOverrideDeadline(30000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPersisted(true)
                .build();
        final int uid1 = SOME_UID;
        final int uid2 = uid1 + 1;
        final JobStatus taskStatus1 =
                JobStatus.createFromJobInfo(task1, uid1, null, -1, null, null);
        final JobStatus taskStatus2 =
                JobStatus.createFromJobInfo(task2, uid2, null, -1, null, null);

        runWritingJobsToDisk(taskStatus1, taskStatus2);
    }

    private void runWritingJobsToDisk(JobStatus... jobStatuses) throws Exception {
        ArraySet<JobStatus> expectedJobs = new ArraySet<>();
        for (JobStatus jobStatus : jobStatuses) {
            mTaskStoreUnderTest.add(jobStatus);
            expectedJobs.add(jobStatus);
        }
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        assertEquals("Incorrect # of persisted tasks.", expectedJobs.size(), jobStatusSet.size());
        int count = 0;
        final int expectedCount = expectedJobs.size();
        for (JobStatus loaded : jobStatusSet.getAllJobs()) {
            count++;
            for (int i = 0; i < expectedJobs.size(); ++i) {
                JobStatus expected = expectedJobs.valueAt(i);

                try {
                    assertJobsEqual(expected, loaded);
                    expectedJobs.remove(expected);
                    break;
                } catch (AssertionError e) {
                    // Not equal. Move along.
                }
            }
        }
        assertEquals("Loaded more jobs than expected", expectedCount, count);
        if (expectedJobs.size() > 0) {
            fail("Not all expected jobs were restored");
        }
        for (JobStatus jobStatus : jobStatuses) {
            assertTrue("JobStore#contains invalid.", mTaskStoreUnderTest.containsJob(jobStatus));
        }
    }

    @Test
    public void testWritingTaskWithExtras() throws Exception {
        JobInfo.Builder b = new Builder(8, mComponent)
                .setRequiresDeviceIdle(true)
                .setPeriodic(10000L)
                .setRequiresCharging(true)
                .setPersisted(true);

        PersistableBundle extras = new PersistableBundle();
        extras.putDouble("hello", 3.2);
        extras.putString("hi", "there");
        extras.putInt("into", 3);
        b.setExtras(extras);
        final JobInfo task = b.build();
        JobStatus taskStatus = JobStatus.createFromJobInfo(task, SOME_UID, null, -1, null, null);

        mTaskStoreUnderTest.add(taskStatus);
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        assertEquals("Incorrect # of persisted tasks.", 1, jobStatusSet.size());
        JobStatus loaded = jobStatusSet.getAllJobs().iterator().next();
        assertJobsEqual(taskStatus, loaded);
    }

    @Test
    public void testWritingTaskWithSourcePackage() throws Exception {
        JobInfo.Builder b = new Builder(8, mComponent)
                .setRequiresDeviceIdle(true)
                .setPeriodic(10000L)
                .setRequiresCharging(true)
                .setPersisted(true);
        JobStatus taskStatus = JobStatus.createFromJobInfo(b.build(), SOME_UID,
                "com.android.test.app", 0, null, null);

        mTaskStoreUnderTest.add(taskStatus);
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        assertEquals("Incorrect # of persisted tasks.", 1, jobStatusSet.size());
        JobStatus loaded = jobStatusSet.getAllJobs().iterator().next();
        assertEquals("Source package not equal.", loaded.getSourcePackageName(),
                taskStatus.getSourcePackageName());
        assertEquals("Source user not equal.", loaded.getSourceUserId(),
                taskStatus.getSourceUserId());
    }

    @Test
    public void testWritingTaskWithFlex() throws Exception {
        JobInfo.Builder b = new Builder(8, mComponent)
                .setRequiresDeviceIdle(true)
                .setPeriodic(5*60*60*1000, 1*60*60*1000)
                .setRequiresCharging(true)
                .setPersisted(true);
        JobStatus taskStatus = JobStatus.createFromJobInfo(b.build(),
                SOME_UID, null, -1, null, null);

        mTaskStoreUnderTest.add(taskStatus);
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        assertEquals("Incorrect # of persisted tasks.", 1, jobStatusSet.size());
        JobStatus loaded = jobStatusSet.getAllJobs().iterator().next();
        assertEquals("Period not equal.", loaded.getJob().getIntervalMillis(),
                taskStatus.getJob().getIntervalMillis());
        assertEquals("Flex not equal.", loaded.getJob().getFlexMillis(),
                taskStatus.getJob().getFlexMillis());
    }

    @Test
    public void testMassivePeriodClampedOnRead() throws Exception {
        final long ONE_HOUR = 60*60*1000L; // flex
        final long TWO_HOURS = 2 * ONE_HOUR; // period
        JobInfo.Builder b = new Builder(8, mComponent)
                .setPeriodic(TWO_HOURS, ONE_HOUR)
                .setPersisted(true);
        final long rtcNow = System.currentTimeMillis();
        final long invalidLateRuntimeElapsedMillis =
                SystemClock.elapsedRealtime() + (TWO_HOURS * ONE_HOUR) + TWO_HOURS;  // > period+flex
        final long invalidEarlyRuntimeElapsedMillis =
                invalidLateRuntimeElapsedMillis - TWO_HOURS;  // Early is (late - period).
        final Pair<Long, Long> persistedExecutionTimesUTC = new Pair<>(rtcNow, rtcNow + ONE_HOUR);
        final JobStatus js = new JobStatus(b.build(), SOME_UID, "somePackage",
                0 /* sourceUserId */, 0, "someNamespace", "someTag",
                invalidEarlyRuntimeElapsedMillis, invalidLateRuntimeElapsedMillis,
                0 /* lastSuccessfulRunTime */, 0 /* lastFailedRunTime */,
                0 /* cumulativeExecutionTime */,
                persistedExecutionTimesUTC, 0 /* innerFlag */, 0 /* dynamicConstraints */);

        mTaskStoreUnderTest.add(js);
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        assertEquals("Incorrect # of persisted tasks.", 1, jobStatusSet.size());
        JobStatus loaded = jobStatusSet.getAllJobs().iterator().next();

        // Assert early runtime was clamped to be under now + period. We can do <= here b/c we'll
        // call SystemClock.elapsedRealtime after doing the disk i/o.
        final long newNowElapsed = SystemClock.elapsedRealtime();
        assertTrue("Early runtime wasn't correctly clamped.",
                loaded.getEarliestRunTime() <= newNowElapsed + TWO_HOURS);
        // Assert late runtime was clamped to be now + period + flex.
        assertTrue("Early runtime wasn't correctly clamped.",
                loaded.getEarliestRunTime() <= newNowElapsed + TWO_HOURS + ONE_HOUR);
    }

    @Test
    public void testBiasPersisted() throws Exception {
        JobInfo.Builder b = new Builder(92, mComponent)
                .setOverrideDeadline(5000)
                .setBias(42)
                .setPersisted(true);
        final JobStatus js = JobStatus.createFromJobInfo(b.build(), SOME_UID, null, -1, null, null);
        mTaskStoreUnderTest.add(js);
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        JobStatus loaded = jobStatusSet.getAllJobs().iterator().next();
        assertEquals("Bias not correctly persisted.", 42, loaded.getBias());
    }

    @Test
    public void testCumulativeExecutionTimePersisted() throws Exception {
        JobInfo ji = new Builder(53, mComponent).setPersisted(true).build();
        final JobStatus js = JobStatus.createFromJobInfo(ji, SOME_UID, null, -1, null, null);
        js.incrementCumulativeExecutionTime(1234567890);
        mTaskStoreUnderTest.add(js);
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        JobStatus loaded = jobStatusSet.getAllJobs().iterator().next();
        assertEquals("Cumulative execution time not correctly persisted.",
                1234567890, loaded.getCumulativeExecutionTimeMs());
    }

    @Test
    public void testDebugTagsPersisted() throws Exception {
        JobInfo ji = new Builder(53, mComponent)
                .setPersisted(true)
                .addDebugTag("a")
                .addDebugTag("b")
                .addDebugTag("c")
                .addDebugTag("d")
                .removeDebugTag("d")
                .build();
        final JobStatus js = JobStatus.createFromJobInfo(ji, SOME_UID, null, -1, null, null);
        mTaskStoreUnderTest.add(js);
        waitForPendingIo();

        Set<String> expectedTags = Set.of("a", "b", "c");

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        JobStatus loaded = jobStatusSet.getAllJobs().iterator().next();
        assertEquals("Debug tags not correctly persisted",
                expectedTags, loaded.getJob().getDebugTags());
    }

    @Test
    public void testNamespacePersisted() throws Exception {
        final String namespace = "my.test.namespace";
        JobInfo.Builder b = new Builder(93, mComponent)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true);
        final JobStatus js =
                JobStatus.createFromJobInfo(b.build(), SOME_UID, null, -1, namespace, null);
        mTaskStoreUnderTest.add(js);
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        JobStatus loaded = jobStatusSet.getAllJobs().iterator().next();
        assertEquals("Namespace not correctly persisted.", namespace, loaded.getNamespace());
    }

    @Test
    public void testPriorityPersisted() throws Exception {
        final JobInfo job = new Builder(92, mComponent)
                .setOverrideDeadline(5000)
                .setPriority(JobInfo.PRIORITY_MIN)
                .setPersisted(true)
                .build();
        final JobStatus js = JobStatus.createFromJobInfo(job, SOME_UID, null, -1, null, null);
        mTaskStoreUnderTest.add(js);
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        final JobStatus loaded = jobStatusSet.getAllJobs().iterator().next();
        assertEquals("Priority not correctly persisted.",
                JobInfo.PRIORITY_MIN, job.getPriority());
    }

    /**
     * Test that non persisted job is not written to disk.
     */
    @Test
    public void testNonPersistedTaskIsNotPersisted() throws Exception {
        JobInfo.Builder b = new Builder(42, mComponent)
                .setOverrideDeadline(10000)
                .setPersisted(false);
        JobStatus jsNonPersisted = JobStatus.createFromJobInfo(b.build(),
                SOME_UID, null, -1, null, null);
        mTaskStoreUnderTest.add(jsNonPersisted);
        b = new Builder(43, mComponent)
                .setOverrideDeadline(10000)
                .setPersisted(true);
        JobStatus jsPersisted = JobStatus.createFromJobInfo(b.build(),
                SOME_UID, null, -1, null, null);
        mTaskStoreUnderTest.add(jsPersisted);
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        assertEquals("Job count is incorrect.", 1, jobStatusSet.size());
        JobStatus jobStatus = jobStatusSet.getAllJobs().iterator().next();
        assertEquals("Wrong job persisted.", 43, jobStatus.getJobId());
    }

    @Test
    public void testRequiredNetworkType() throws Exception {
        assertPersistedEquals(new JobInfo.Builder(0, mComponent)
                .setPersisted(true)
                .setRequiresDeviceIdle(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE).build());
        assertPersistedEquals(new JobInfo.Builder(0, mComponent)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
        assertPersistedEquals(new JobInfo.Builder(0, mComponent)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).build());
        assertPersistedEquals(new JobInfo.Builder(0, mComponent)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NOT_ROAMING).build());
        assertPersistedEquals(new JobInfo.Builder(0, mComponent)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_CELLULAR).build());
    }

    @Test
    public void testRequiredNetwork() throws Exception {
        assertPersistedEquals(new JobInfo.Builder(0, mComponent)
                .setPersisted(true)
                .setRequiresDeviceIdle(true)
                .setRequiredNetwork(null).build());
        assertPersistedEquals(new JobInfo.Builder(0, mComponent)
                .setPersisted(true)
                .setRequiredNetwork(new NetworkRequest.Builder().build()).build());
        assertPersistedEquals(new JobInfo.Builder(0, mComponent)
                .setPersisted(true)
                .setRequiredNetwork(new NetworkRequest.Builder()
                        .addTransportType(TRANSPORT_WIFI).build())
                .build());
        assertPersistedEquals(new JobInfo.Builder(0, mComponent)
                .setPersisted(true)
                .setRequiredNetwork(new NetworkRequest.Builder()
                        .addCapability(NET_CAPABILITY_IMS)
                        .addForbiddenCapability(NET_CAPABILITY_OEM_PAID)
                        .build())
                .build());
    }

    @Test
    public void testTraceTagPersisted() throws Exception {
        JobInfo ji = new Builder(53, mComponent)
                .setPersisted(true)
                .setTraceTag("tag")
                .build();
        final JobStatus js = JobStatus.createFromJobInfo(ji, SOME_UID, null, -1, null, null);
        mTaskStoreUnderTest.add(js);
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        JobStatus loaded = jobStatusSet.getAllJobs().iterator().next();
        assertEquals("Trace tag not correctly persisted", "tag", loaded.getJob().getTraceTag());
    }

    @Test
    public void testEstimatedNetworkBytes() throws Exception {
        assertPersistedEquals(new JobInfo.Builder(0, mComponent)
                .setPersisted(true)
                .setRequiredNetwork(new NetworkRequest.Builder().build())
                .setEstimatedNetworkBytes(
                        JobInfo.NETWORK_BYTES_UNKNOWN, JobInfo.NETWORK_BYTES_UNKNOWN)
                .build());
        assertPersistedEquals(new JobInfo.Builder(0, mComponent)
                .setPersisted(true)
                .setRequiredNetwork(new NetworkRequest.Builder().build())
                .setEstimatedNetworkBytes(5, 15)
                .build());
    }

    @Test
    public void testMinimumNetworkChunkBytes() throws Exception {
        assertPersistedEquals(new JobInfo.Builder(0, mComponent)
                .setPersisted(true)
                .setRequiredNetwork(new NetworkRequest.Builder().build())
                .setMinimumNetworkChunkBytes(JobInfo.NETWORK_BYTES_UNKNOWN)
                .build());
        assertPersistedEquals(new JobInfo.Builder(0, mComponent)
                .setPersisted(true)
                .setRequiredNetwork(new NetworkRequest.Builder().build())
                .setMinimumNetworkChunkBytes(42)
                .build());
    }

    @Test
    public void testPersistedIdleConstraint() throws Exception {
        JobInfo.Builder b = new Builder(8, mComponent)
                .setRequiresDeviceIdle(true)
                .setPersisted(true);
        JobStatus taskStatus = JobStatus.createFromJobInfo(b.build(),
                SOME_UID, null, -1, null, null);

        mTaskStoreUnderTest.add(taskStatus);
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        assertEquals("Incorrect # of persisted tasks.", 1, jobStatusSet.size());
        JobStatus loaded = jobStatusSet.getAllJobs().iterator().next();
        assertEquals("Idle constraint not persisted correctly.",
                loaded.getJob().isRequireDeviceIdle(),
                taskStatus.getJob().isRequireDeviceIdle());
    }

    @Test
    public void testPersistedChargingConstraint() throws Exception {
        JobInfo.Builder b = new Builder(8, mComponent)
                .setRequiresCharging(true)
                .setPersisted(true);
        JobStatus taskStatus = JobStatus.createFromJobInfo(b.build(),
                SOME_UID, null, -1, null, null);

        mTaskStoreUnderTest.add(taskStatus);
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        assertEquals("Incorrect # of persisted tasks.", 1, jobStatusSet.size());
        JobStatus loaded = jobStatusSet.getAllJobs().iterator().next();
        assertEquals("Charging constraint not persisted correctly.",
                loaded.getJob().isRequireCharging(),
                taskStatus.getJob().isRequireCharging());
    }

    @Test
    public void testPersistedStorageNotLowConstraint() throws Exception {
        JobInfo.Builder b = new Builder(8, mComponent)
                .setRequiresStorageNotLow(true)
                .setPersisted(true);
        JobStatus taskStatus = JobStatus.createFromJobInfo(b.build(),
                SOME_UID, null, -1, null, null);

        mTaskStoreUnderTest.add(taskStatus);
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        assertEquals("Incorrect # of persisted tasks.", 1, jobStatusSet.size());
        JobStatus loaded = jobStatusSet.getAllJobs().iterator().next();
        assertEquals("Storage-not-low constraint not persisted correctly.",
                loaded.getJob().isRequireStorageNotLow(),
                taskStatus.getJob().isRequireStorageNotLow());
    }

    @Test
    public void testPersistedBatteryNotLowConstraint() throws Exception {
        JobInfo.Builder b = new Builder(8, mComponent)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true);
        JobStatus taskStatus = JobStatus.createFromJobInfo(b.build(),
                SOME_UID, null, -1, null, null);

        mTaskStoreUnderTest.add(taskStatus);
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        assertEquals("Incorrect # of persisted tasks.", 1, jobStatusSet.size());
        JobStatus loaded = jobStatusSet.getAllJobs().iterator().next();
        assertEquals("Battery-not-low constraint not persisted correctly.",
                loaded.getJob().isRequireBatteryNotLow(),
                taskStatus.getJob().isRequireBatteryNotLow());
    }

    @Test
    public void testJobWorkItems() throws Exception {
        JobWorkItem item1 = new JobWorkItem.Builder().build();
        item1.bumpDeliveryCount();
        PersistableBundle bundle = new PersistableBundle();
        bundle.putBoolean("test", true);
        JobWorkItem item2 = new JobWorkItem.Builder().setExtras(bundle).build();
        item2.bumpDeliveryCount();
        JobWorkItem item3 = new JobWorkItem.Builder().setEstimatedNetworkBytes(1, 2).build();
        JobWorkItem item4 = new JobWorkItem.Builder().setMinimumNetworkChunkBytes(3).build();
        JobWorkItem item5 = new JobWorkItem.Builder().build();

        JobInfo jobInfo = new JobInfo.Builder(0, mComponent)
                .setPersisted(true)
                .build();
        JobStatus jobStatus =
                JobStatus.createFromJobInfo(jobInfo, SOME_UID, null, -1, null, null);
        jobStatus.executingWork = new ArrayList<>(List.of(item1, item2));
        jobStatus.pendingWork = new ArrayList<>(List.of(item3, item4, item5));
        assertPersistedEquals(jobStatus);
    }

    /**
     * Helper function to kick a {@link JobInfo} through a persistence cycle and
     * assert that it's unchanged.
     */
    private void assertPersistedEquals(JobInfo firstInfo) throws Exception {
        assertPersistedEquals(
                JobStatus.createFromJobInfo(firstInfo, SOME_UID, null, -1, null, null));
    }

    private void assertPersistedEquals(JobStatus original) throws Exception {
        mTaskStoreUnderTest.clear();
        mTaskStoreUnderTest.add(original);
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        final JobStatus second = jobStatusSet.getAllJobs().iterator().next();
        assertJobsEqual(original, second);
    }

    /**
     * Helper function to throw an error if the provided JobStatus objects are not equal.
     */
    private void assertJobsEqual(JobStatus expected, JobStatus actual) {
        assertEquals(expected.getJob(), actual.getJob());

        // Source UID isn't persisted, but the rest of the app info is.
        assertEquals("Source package not equal",
                expected.getSourcePackageName(), actual.getSourcePackageName());
        assertEquals("Source user not equal", expected.getSourceUserId(), actual.getSourceUserId());
        assertEquals("Calling UID not equal", expected.getUid(), actual.getUid());
        assertEquals("Calling user not equal", expected.getUserId(), actual.getUserId());

        assertEquals(expected.getNamespace(), actual.getNamespace());

        assertEquals("Internal flags not equal",
                expected.getInternalFlags(), actual.getInternalFlags());

        // Check that the loaded task has the correct runtimes.
        compareTimestampsSubjectToIoLatency("Early run-times not the same after read.",
                expected.getEarliestRunTime(), actual.getEarliestRunTime());
        compareTimestampsSubjectToIoLatency("Late run-times not the same after read.",
                expected.getLatestRunTimeElapsed(), actual.getLatestRunTimeElapsed());

        assertEquals(expected.getCumulativeExecutionTimeMs(),
                actual.getCumulativeExecutionTimeMs());

        assertEquals(expected.hasWorkLocked(), actual.hasWorkLocked());
        if (expected.hasWorkLocked()) {
            List<JobWorkItem> allWork = new ArrayList<>();
            if (expected.executingWork != null) {
                allWork.addAll(expected.executingWork);
            }
            if (expected.pendingWork != null) {
                allWork.addAll(expected.pendingWork);
            }
            // All work for freshly loaded Job will be pending.
            assertNotNull(actual.pendingWork);
            assertTrue(ArrayUtils.isEmpty(actual.executingWork));
            assertEquals(allWork.size(), actual.pendingWork.size());
            for (int i = 0; i < allWork.size(); ++i) {
                JobWorkItem expectedItem = allWork.get(i);
                JobWorkItem actualItem = actual.pendingWork.get(i);
                assertJobWorkItemsEqual(expectedItem, actualItem);
            }
        }
    }

    private void assertJobWorkItemsEqual(JobWorkItem expected, JobWorkItem actual) {
        if (expected == null) {
            assertNull(actual);
            return;
        }
        assertNotNull(actual);
        assertEquals(expected.getDeliveryCount(), actual.getDeliveryCount());
        assertEquals(expected.getEstimatedNetworkDownloadBytes(),
                actual.getEstimatedNetworkDownloadBytes());
        assertEquals(expected.getEstimatedNetworkUploadBytes(),
                actual.getEstimatedNetworkUploadBytes());
        assertEquals(expected.getMinimumNetworkChunkBytes(), actual.getMinimumNetworkChunkBytes());
        if (expected.getIntent() == null) {
            assertNull(actual.getIntent());
        } else {
            // filterEquals() just so happens to check almost everything that is persisted to disk.
            assertTrue(expected.getIntent().filterEquals(actual.getIntent()));
            assertEquals(expected.getIntent().getFlags(), actual.getIntent().getFlags());
        }
        assertEquals(expected.getGrants(), actual.getGrants());
        PersistableBundle expectedExtras = expected.getExtras();
        PersistableBundle actualExtras = actual.getExtras();
        if (expectedExtras == null) {
            assertNull(actualExtras);
        } else {
            assertEquals(expectedExtras.size(), actualExtras.size());
            Set<String> keys = expectedExtras.keySet();
            for (String key : keys) {
                assertTrue(Objects.equals(expectedExtras.get(key), actualExtras.get(key)));
            }
        }
    }

    /**
     * When comparing timestamps before and after DB read/writes (to make sure we're saving/loading
     * the correct values), there is some latency involved that terrorises a naive assertEquals().
     * We define a <code>DELTA_MILLIS</code> as a function variable here to make this comparision
     * more reasonable.
     */
    private void compareTimestampsSubjectToIoLatency(String error, long ts1, long ts2) {
        final long DELTA_MILLIS = 700L;  // We allow up to 700ms of latency for IO read/writes.
        assertTrue(error, Math.abs(ts1 - ts2) < DELTA_MILLIS);
    }

    private static class StubClass {}
}
