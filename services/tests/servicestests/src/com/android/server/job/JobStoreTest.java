package com.android.server.job;

import static android.net.NetworkCapabilities.NET_CAPABILITY_IMS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_OEM_PAID;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.job.JobInfo;
import android.app.job.JobInfo.Builder;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.test.RenamingDelegatingContext;
import android.util.Log;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.HexDump;
import com.android.server.LocalServices;
import com.android.server.job.JobStore.JobSet;
import com.android.server.job.controllers.JobStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Iterator;

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

    private void waitForPendingIo() throws Exception {
        assertTrue("Timed out waiting for persistence I/O to complete",
                mTaskStoreUnderTest.waitForWriteToCompleteForTesting(5_000L));
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
        final JobStatus ts = JobStatus.createFromJobInfo(task, SOME_UID, null, -1, null);
        ts.addInternalFlags(JobStatus.INTERNAL_FLAG_HAS_FOREGROUND_EXEMPTION);
        mTaskStoreUnderTest.add(ts);
        waitForPendingIo();

        // Manually load tasks from xml file.
        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);

        assertEquals("Didn't get expected number of persisted tasks.", 1, jobStatusSet.size());
        final JobStatus loadedTaskStatus = jobStatusSet.getAllJobs().get(0);
        assertTasksEqual(task, loadedTaskStatus.getJob());
        assertTrue("JobStore#contains invalid.", mTaskStoreUnderTest.containsJob(ts));
        assertEquals("Different uids.", SOME_UID, loadedTaskStatus.getUid());
        assertEquals(JobStatus.INTERNAL_FLAG_HAS_FOREGROUND_EXEMPTION,
                loadedTaskStatus.getInternalFlags());
        compareTimestampsSubjectToIoLatency("Early run-times not the same after read.",
                ts.getEarliestRunTime(), loadedTaskStatus.getEarliestRunTime());
        compareTimestampsSubjectToIoLatency("Late run-times not the same after read.",
                ts.getLatestRunTimeElapsed(), loadedTaskStatus.getLatestRunTimeElapsed());
    }

    @Test
    public void testWritingTwoFilesToDisk() throws Exception {
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
        final JobStatus taskStatus1 = JobStatus.createFromJobInfo(task1, SOME_UID, null, -1, null);
        final JobStatus taskStatus2 = JobStatus.createFromJobInfo(task2, SOME_UID, null, -1, null);
        mTaskStoreUnderTest.add(taskStatus1);
        mTaskStoreUnderTest.add(taskStatus2);
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        assertEquals("Incorrect # of persisted tasks.", 2, jobStatusSet.size());
        Iterator<JobStatus> it = jobStatusSet.getAllJobs().iterator();
        JobStatus loaded1 = it.next();
        JobStatus loaded2 = it.next();

        // Reverse them so we know which comparison to make.
        if (loaded1.getJobId() != 8) {
            JobStatus tmp = loaded1;
            loaded1 = loaded2;
            loaded2 = tmp;
        }

        assertTasksEqual(task1, loaded1.getJob());
        assertTasksEqual(task2, loaded2.getJob());
        assertTrue("JobStore#contains invalid.", mTaskStoreUnderTest.containsJob(taskStatus1));
        assertTrue("JobStore#contains invalid.", mTaskStoreUnderTest.containsJob(taskStatus2));
        // Check that the loaded task has the correct runtimes.
        compareTimestampsSubjectToIoLatency("Early run-times not the same after read.",
                taskStatus1.getEarliestRunTime(), loaded1.getEarliestRunTime());
        compareTimestampsSubjectToIoLatency("Late run-times not the same after read.",
                taskStatus1.getLatestRunTimeElapsed(), loaded1.getLatestRunTimeElapsed());
        compareTimestampsSubjectToIoLatency("Early run-times not the same after read.",
                taskStatus2.getEarliestRunTime(), loaded2.getEarliestRunTime());
        compareTimestampsSubjectToIoLatency("Late run-times not the same after read.",
                taskStatus2.getLatestRunTimeElapsed(), loaded2.getLatestRunTimeElapsed());
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
        JobStatus taskStatus = JobStatus.createFromJobInfo(task, SOME_UID, null, -1, null);

        mTaskStoreUnderTest.add(taskStatus);
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        assertEquals("Incorrect # of persisted tasks.", 1, jobStatusSet.size());
        JobStatus loaded = jobStatusSet.getAllJobs().iterator().next();
        assertTasksEqual(task, loaded.getJob());
    }

    @Test
    public void testWritingTaskWithSourcePackage() throws Exception {
        JobInfo.Builder b = new Builder(8, mComponent)
                .setRequiresDeviceIdle(true)
                .setPeriodic(10000L)
                .setRequiresCharging(true)
                .setPersisted(true);
        JobStatus taskStatus = JobStatus.createFromJobInfo(b.build(), SOME_UID,
                "com.google.android.gms", 0, null);

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
        JobStatus taskStatus = JobStatus.createFromJobInfo(b.build(), SOME_UID, null, -1, null);

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
                0 /* sourceUserId */, 0, "someTag",
                invalidEarlyRuntimeElapsedMillis, invalidLateRuntimeElapsedMillis,
                0 /* lastSuccessfulRunTime */, 0 /* lastFailedRunTime */,
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
        final JobStatus js = JobStatus.createFromJobInfo(b.build(), SOME_UID, null, -1, null);
        mTaskStoreUnderTest.add(js);
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        JobStatus loaded = jobStatusSet.getAllJobs().iterator().next();
        assertEquals("Bias not correctly persisted.", 42, loaded.getBias());
    }

    @Test
    public void testPriorityPersisted() throws Exception {
        final JobInfo job = new Builder(92, mComponent)
                .setOverrideDeadline(5000)
                .setPriority(JobInfo.PRIORITY_MIN)
                .setPersisted(true)
                .build();
        final JobStatus js = JobStatus.createFromJobInfo(job, SOME_UID, null, -1, null);
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
        JobStatus jsNonPersisted = JobStatus.createFromJobInfo(b.build(), SOME_UID, null, -1, null);
        mTaskStoreUnderTest.add(jsNonPersisted);
        b = new Builder(43, mComponent)
                .setOverrideDeadline(10000)
                .setPersisted(true);
        JobStatus jsPersisted = JobStatus.createFromJobInfo(b.build(), SOME_UID, null, -1, null);
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
    public void testPersistedIdleConstraint() throws Exception {
        JobInfo.Builder b = new Builder(8, mComponent)
                .setRequiresDeviceIdle(true)
                .setPersisted(true);
        JobStatus taskStatus = JobStatus.createFromJobInfo(b.build(), SOME_UID, null, -1, null);

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
        JobStatus taskStatus = JobStatus.createFromJobInfo(b.build(), SOME_UID, null, -1, null);

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
        JobStatus taskStatus = JobStatus.createFromJobInfo(b.build(), SOME_UID, null, -1, null);

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
        JobStatus taskStatus = JobStatus.createFromJobInfo(b.build(), SOME_UID, null, -1, null);

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

    /**
     * Helper function to kick a {@link JobInfo} through a persistence cycle and
     * assert that it's unchanged.
     */
    private void assertPersistedEquals(JobInfo firstInfo) throws Exception {
        mTaskStoreUnderTest.clear();
        JobStatus first = JobStatus.createFromJobInfo(firstInfo, SOME_UID, null, -1, null);
        mTaskStoreUnderTest.add(first);
        waitForPendingIo();

        final JobSet jobStatusSet = new JobSet();
        mTaskStoreUnderTest.readJobMapFromDisk(jobStatusSet, true);
        final JobStatus second = jobStatusSet.getAllJobs().iterator().next();
        assertTasksEqual(first.getJob(), second.getJob());
    }

    /**
     * Helper function to throw an error if the provided task and TaskStatus objects are not equal.
     */
    private void assertTasksEqual(JobInfo first, JobInfo second) {
        assertEquals("Different task ids.", first.getId(), second.getId());
        assertEquals("Different components.", first.getService(), second.getService());
        assertEquals("Different periodic status.", first.isPeriodic(), second.isPeriodic());
        assertEquals("Different period.", first.getIntervalMillis(), second.getIntervalMillis());
        assertEquals("Different inital backoff.", first.getInitialBackoffMillis(),
                second.getInitialBackoffMillis());
        assertEquals("Different backoff policy.", first.getBackoffPolicy(),
                second.getBackoffPolicy());

        assertEquals("Invalid charging constraint.", first.isRequireCharging(),
                second.isRequireCharging());
        assertEquals("Invalid battery not low constraint.", first.isRequireBatteryNotLow(),
                second.isRequireBatteryNotLow());
        assertEquals("Invalid idle constraint.", first.isRequireDeviceIdle(),
                second.isRequireDeviceIdle());
        assertEquals("Invalid network type.",
                first.getNetworkType(), second.getNetworkType());
        assertEquals("Invalid network.",
                first.getRequiredNetwork(), second.getRequiredNetwork());
        assertEquals("Invalid deadline constraint.",
                first.hasLateConstraint(),
                second.hasLateConstraint());
        assertEquals("Invalid delay constraint.",
                first.hasEarlyConstraint(),
                second.hasEarlyConstraint());
        assertEquals("Extras don't match",
                first.getExtras().toString(), second.getExtras().toString());
        assertEquals("Transient xtras don't match",
                first.getTransientExtras().toString(), second.getTransientExtras().toString());

        // Since people can forget to add tests here for new fields, do one last
        // validity check based on bits-on-wire equality.
        final byte[] firstBytes = marshall(first);
        final byte[] secondBytes = marshall(second);
        if (!Arrays.equals(firstBytes, secondBytes)) {
            Log.w(TAG, "First: " + HexDump.dumpHexString(firstBytes));
            Log.w(TAG, "Second: " + HexDump.dumpHexString(secondBytes));
            fail("Raw JobInfo aren't equal; see logs for details");
        }
    }

    private static byte[] marshall(Parcelable p) {
        final Parcel parcel = Parcel.obtain();
        try {
            p.writeToParcel(parcel, 0);
            return parcel.marshall();
        } finally {
            parcel.recycle();
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
