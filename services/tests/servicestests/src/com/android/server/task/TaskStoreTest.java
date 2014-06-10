package com.android.server.task;


import android.content.ComponentName;
import android.content.Context;
import android.app.job.JobInfo;
import android.app.job.JobInfo.Builder;
import android.os.PersistableBundle;
import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;
import android.util.Log;

import com.android.server.job.JobMapReadFinishedListener;
import com.android.server.job.JobStore;
import com.android.server.job.controllers.JobStatus;

import java.util.List;

/**
 * Test reading and writing correctly from file.
 */
public class TaskStoreTest extends AndroidTestCase {
    private static final String TAG = "TaskStoreTest";
    private static final String TEST_PREFIX = "_test_";
    // private static final int USER_NON_0 = 3;
    private static final int SOME_UID = 34234;
    private ComponentName mComponent;
    private static final long IO_WAIT = 600L;

    JobStore mTaskStoreUnderTest;
    Context mTestContext;
    JobMapReadFinishedListener mTaskMapReadFinishedListenerStub =
            new JobMapReadFinishedListener() {
        @Override
        public void onJobMapReadFinished(List<JobStatus> tasks) {
            // do nothing.
        }
    };

    @Override
    public void setUp() throws Exception {
        mTestContext = new RenamingDelegatingContext(getContext(), TEST_PREFIX);
        Log.d(TAG, "Saving tasks to '" + mTestContext.getFilesDir() + "'");
        mTaskStoreUnderTest = JobStore.initAndGetForTesting(mTestContext,
                mTestContext.getFilesDir(), mTaskMapReadFinishedListenerStub);
        mComponent = new ComponentName(getContext().getPackageName(), StubClass.class.getName());
    }

    @Override
    public void tearDown() throws Exception {
        mTaskStoreUnderTest.clear();
    }

    public void testMaybeWriteStatusToDisk() throws Exception {
        int taskId = 5;
        long runByMillis = 20000L; // 20s
        long runFromMillis = 2000L; // 2s
        long initialBackoff = 10000L; // 10s

        final JobInfo task = new Builder(taskId, mComponent)
                .setRequiresCharging(true)
                .setRequiredNetworkCapabilities(JobInfo.NetworkType.ANY)
                .setBackoffCriteria(initialBackoff, JobInfo.BackoffPolicy.EXPONENTIAL)
                .setOverrideDeadline(runByMillis)
                .setMinimumLatency(runFromMillis)
                .build();
        final JobStatus ts = new JobStatus(task, SOME_UID, true /* persisted */);
        mTaskStoreUnderTest.add(ts);
        Thread.sleep(IO_WAIT);
        // Manually load tasks from xml file.
        mTaskStoreUnderTest.readJobMapFromDisk(new JobMapReadFinishedListener() {
            @Override
            public void onJobMapReadFinished(List<JobStatus> tasks) {
                assertEquals("Didn't get expected number of persisted tasks.", 1, tasks.size());
                JobStatus loadedTaskStatus = tasks.get(0);
                assertTasksEqual(task, loadedTaskStatus.getJob());
                assertEquals("Different uids.", SOME_UID, tasks.get(0).getUid());
                compareTimestampsSubjectToIoLatency("Early run-times not the same after read.",
                        ts.getEarliestRunTime(), loadedTaskStatus.getEarliestRunTime());
                compareTimestampsSubjectToIoLatency("Late run-times not the same after read.",
                        ts.getLatestRunTimeElapsed(), loadedTaskStatus.getLatestRunTimeElapsed());
            }
        });

    }

    public void testWritingTwoFilesToDisk() throws Exception {
        final JobInfo task1 = new Builder(8, mComponent)
                .setRequiresDeviceIdle(true)
                .setPeriodic(10000L)
                .setRequiresCharging(true)
                .build();
        final JobInfo task2 = new Builder(12, mComponent)
                .setMinimumLatency(5000L)
                .setBackoffCriteria(15000L, JobInfo.BackoffPolicy.LINEAR)
                .setOverrideDeadline(30000L)
                .setRequiredNetworkCapabilities(JobInfo.NetworkType.UNMETERED)
                .build();
        final JobStatus taskStatus1 = new JobStatus(task1, SOME_UID, true /* persisted */);
        final JobStatus taskStatus2 = new JobStatus(task2, SOME_UID, true /* persisted */);
        mTaskStoreUnderTest.add(taskStatus1);
        mTaskStoreUnderTest.add(taskStatus2);
        Thread.sleep(IO_WAIT);
        mTaskStoreUnderTest.readJobMapFromDisk(new JobMapReadFinishedListener() {
            @Override
            public void onJobMapReadFinished(List<JobStatus> tasks) {
                assertEquals("Incorrect # of persisted tasks.", 2, tasks.size());
                JobStatus loaded1 = tasks.get(0);
                JobStatus loaded2 = tasks.get(1);
                assertTasksEqual(task1, loaded1.getJob());
                assertTasksEqual(task2, loaded2.getJob());

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
        });

    }

    public void testWritingTaskWithExtras() throws Exception {
        JobInfo.Builder b = new Builder(8, mComponent)
                .setRequiresDeviceIdle(true)
                .setPeriodic(10000L)
                .setRequiresCharging(true);

        PersistableBundle extras = new PersistableBundle();
        extras.putDouble("hello", 3.2);
        extras.putString("hi", "there");
        extras.putInt("into", 3);
        b.setExtras(extras);
        final JobInfo task = b.build();
        JobStatus taskStatus = new JobStatus(task, SOME_UID, true /* persisted */);

        mTaskStoreUnderTest.add(taskStatus);
        Thread.sleep(IO_WAIT);
        mTaskStoreUnderTest.readJobMapFromDisk(new JobMapReadFinishedListener() {
            @Override
            public void onJobMapReadFinished(List<JobStatus> tasks) {
                assertEquals("Incorrect # of persisted tasks.", 1, tasks.size());
                JobStatus loaded = tasks.get(0);
                assertTasksEqual(task, loaded.getJob());
            }
        });

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
        assertEquals("Invalid idle constraint.", first.isRequireDeviceIdle(),
                second.isRequireDeviceIdle());
        assertEquals("Invalid unmetered constraint.",
                first.getNetworkCapabilities() == JobInfo.NetworkType.UNMETERED,
                second.getNetworkCapabilities() == JobInfo.NetworkType.UNMETERED);
        assertEquals("Invalid connectivity constraint.",
                first.getNetworkCapabilities() == JobInfo.NetworkType.ANY,
                second.getNetworkCapabilities() == JobInfo.NetworkType.ANY);
        assertEquals("Invalid deadline constraint.",
                first.hasLateConstraint(),
                second.hasLateConstraint());
        assertEquals("Invalid delay constraint.",
                first.hasEarlyConstraint(),
                second.hasEarlyConstraint());
        assertEquals("Extras don't match",
                first.getExtras().toString(), second.getExtras().toString());
    }

    /**
     * When comparing timestamps before and after DB read/writes (to make sure we're saving/loading
     * the correct values), there is some latency involved that terrorises a naive assertEquals().
     * We define a <code>DELTA_MILLIS</code> as a function variable here to make this comparision
     * more reasonable.
     */
    private void compareTimestampsSubjectToIoLatency(String error, long ts1, long ts2) {
        final long DELTA_MILLIS = 700L;  // We allow up to 700ms of latency for IO read/writes.
        assertTrue(error, Math.abs(ts1 - ts2) < DELTA_MILLIS + IO_WAIT);
    }

    private static class StubClass {}

}