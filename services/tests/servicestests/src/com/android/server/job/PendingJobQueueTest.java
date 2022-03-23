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

package com.android.server.job;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.job.JobInfo;
import android.content.ComponentName;
import android.platform.test.annotations.LargeTest;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseLongArray;

import com.android.server.job.controllers.JobStatus;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class PendingJobQueueTest {
    private static final String TAG = PendingJobQueueTest.class.getSimpleName();

    private static final int[] sRegJobPriorities = {
            JobInfo.PRIORITY_HIGH, JobInfo.PRIORITY_DEFAULT,
            JobInfo.PRIORITY_LOW, JobInfo.PRIORITY_MIN
    };

    private static JobInfo.Builder createJobInfo(int jobId) {
        return new JobInfo.Builder(jobId, new ComponentName("foo", "bar"));
    }

    private JobStatus createJobStatus(String testTag, JobInfo.Builder jobInfoBuilder,
            int callingUid) {
        return JobStatus.createFromJobInfo(
                jobInfoBuilder.build(), callingUid, "com.android.test", 0, testTag);
    }

    @Test
    public void testAdd() {
        List<JobStatus> jobs = new ArrayList<>();
        jobs.add(createJobStatus("testAdd", createJobInfo(1), 1));
        jobs.add(createJobStatus("testAdd", createJobInfo(2), 2));
        jobs.add(createJobStatus("testAdd", createJobInfo(3).setExpedited(true), 3));
        jobs.add(createJobStatus("testAdd", createJobInfo(4), 4));
        jobs.add(createJobStatus("testAdd", createJobInfo(5).setExpedited(true), 5));

        PendingJobQueue jobQueue = new PendingJobQueue();
        for (int i = 0; i < jobs.size(); ++i) {
            jobQueue.add(jobs.get(i));
            assertEquals(i + 1, jobQueue.size());
        }

        JobStatus job;
        while ((job = jobQueue.next()) != null) {
            jobs.remove(job);
        }
        assertEquals(0, jobs.size());
    }

    @Test
    public void testAddAll() {
        List<JobStatus> jobs = new ArrayList<>();
        jobs.add(createJobStatus("testAddAll", createJobInfo(1), 1));
        jobs.add(createJobStatus("testAddAll", createJobInfo(2), 2));
        jobs.add(createJobStatus("testAddAll", createJobInfo(3).setExpedited(true), 3));
        jobs.add(createJobStatus("testAddAll", createJobInfo(4), 4));
        jobs.add(createJobStatus("testAddAll", createJobInfo(5).setExpedited(true), 5));

        PendingJobQueue jobQueue = new PendingJobQueue();
        jobQueue.addAll(jobs);
        assertEquals(jobs.size(), jobQueue.size());

        JobStatus job;
        while ((job = jobQueue.next()) != null) {
            jobs.remove(job);
        }
        assertEquals(0, jobs.size());
    }

    @Test
    public void testClear() {
        List<JobStatus> jobs = new ArrayList<>();
        jobs.add(createJobStatus("testClear", createJobInfo(1), 1));
        jobs.add(createJobStatus("testClear", createJobInfo(2), 2));
        jobs.add(createJobStatus("testClear", createJobInfo(3).setExpedited(true), 3));
        jobs.add(createJobStatus("testClear", createJobInfo(4), 4));
        jobs.add(createJobStatus("testClear", createJobInfo(5).setExpedited(true), 5));

        PendingJobQueue jobQueue = new PendingJobQueue();
        jobQueue.addAll(jobs);
        assertEquals(jobs.size(), jobQueue.size());
        assertNotNull(jobQueue.next());

        jobQueue.clear();
        assertEquals(0, jobQueue.size());
        assertNull(jobQueue.next());
    }

    @Test
    public void testRemove() {
        List<JobStatus> jobs = new ArrayList<>();
        jobs.add(createJobStatus("testRemove", createJobInfo(1), 1));
        jobs.add(createJobStatus("testRemove", createJobInfo(2), 2));
        jobs.add(createJobStatus("testRemove", createJobInfo(3).setExpedited(true), 3));
        jobs.add(createJobStatus("testRemove", createJobInfo(4), 4));
        jobs.add(createJobStatus("testRemove", createJobInfo(5).setExpedited(true), 5));

        PendingJobQueue jobQueue = new PendingJobQueue();
        jobQueue.addAll(jobs);

        for (int i = 0; i < jobs.size(); ++i) {
            jobQueue.remove(jobs.get(i));
            assertEquals(jobs.size() - i - 1, jobQueue.size());
        }
        assertNull(jobQueue.next());
    }

    @Test
    public void testPendingJobSorting() {
        PendingJobQueue jobQueue = new PendingJobQueue();

        // First letter in job variable name indicate regular (r) or expedited (e).
        // Capital letters in job variable name indicate the app/UID.
        // Numbers in job variable name indicate the enqueue time.
        // Expected sort order:
        //   eA7 > rA1 > eB6 > rB2 > eC3 > rD4 > eE5 > eF9 > rF8 > eC11 > rC10 > rG12 > rG13 > eE14
        // Intentions:
        //   * A jobs let us test skipping both regular and expedited jobs of other apps
        //   * B jobs let us test skipping only regular job of another app without going too far
        //   * C jobs test that regular jobs don't skip over other app's jobs and that EJs only
        //     skip up to level of the earliest regular job
        //   * E jobs test that expedited jobs don't skip the line when the app has no regular jobs
        //   * F jobs test correct expedited/regular ordering doesn't push jobs too high in list
        //   * G jobs test correct ordering for regular jobs
        //   * H job tests correct behavior when enqueue times are the same
        JobStatus rA1 = createJobStatus("testPendingJobSorting", createJobInfo(1), 1);
        JobStatus rB2 = createJobStatus("testPendingJobSorting", createJobInfo(2), 2);
        JobStatus eC3 = createJobStatus("testPendingJobSorting",
                createJobInfo(3).setExpedited(true), 3);
        JobStatus rD4 = createJobStatus("testPendingJobSorting", createJobInfo(4), 4);
        JobStatus eE5 = createJobStatus("testPendingJobSorting",
                createJobInfo(5).setExpedited(true), 5);
        JobStatus eB6 = createJobStatus("testPendingJobSorting",
                createJobInfo(6).setExpedited(true), 2);
        JobStatus eA7 = createJobStatus("testPendingJobSorting",
                createJobInfo(7).setExpedited(true), 1);
        JobStatus rH8 = createJobStatus("testPendingJobSorting", createJobInfo(8), 8);
        JobStatus rF8 = createJobStatus("testPendingJobSorting", createJobInfo(8), 6);
        JobStatus eF9 = createJobStatus("testPendingJobSorting",
                createJobInfo(9).setExpedited(true), 6);
        JobStatus rC10 = createJobStatus("testPendingJobSorting", createJobInfo(10), 3);
        JobStatus eC11 = createJobStatus("testPendingJobSorting",
                createJobInfo(11).setExpedited(true), 3);
        JobStatus rG12 = createJobStatus("testPendingJobSorting", createJobInfo(12), 7);
        JobStatus rG13 = createJobStatus("testPendingJobSorting", createJobInfo(13), 7);
        JobStatus eE14 = createJobStatus("testPendingJobSorting",
                createJobInfo(14).setExpedited(true), 5);

        rA1.enqueueTime = 10;
        rB2.enqueueTime = 20;
        eC3.enqueueTime = 30;
        rD4.enqueueTime = 40;
        eE5.enqueueTime = 50;
        eB6.enqueueTime = 60;
        eA7.enqueueTime = 70;
        rF8.enqueueTime = 80;
        rH8.enqueueTime = 80;
        eF9.enqueueTime = 90;
        rC10.enqueueTime = 100;
        eC11.enqueueTime = 110;
        rG12.enqueueTime = 120;
        rG13.enqueueTime = 130;
        eE14.enqueueTime = 140;

        // Add in random order so sorting is apparent.
        jobQueue.add(eC3);
        jobQueue.add(eE5);
        jobQueue.add(rA1);
        jobQueue.add(rG13);
        jobQueue.add(rD4);
        jobQueue.add(eA7);
        jobQueue.add(rG12);
        jobQueue.add(rH8);
        jobQueue.add(rF8);
        jobQueue.add(eB6);
        jobQueue.add(eE14);
        jobQueue.add(eF9);
        jobQueue.add(rB2);
        jobQueue.add(rC10);
        jobQueue.add(eC11);

        checkPendingJobInvariants(jobQueue);
        final JobStatus[] expectedOrder = new JobStatus[]{
                eA7, rA1, eB6, rB2, eC3, rD4, eE5, eF9, rH8, rF8, eC11, rC10, rG12, rG13, eE14};
        int idx = 0;
        JobStatus job;
        while ((job = jobQueue.next()) != null) {
            assertEquals("List wasn't correctly sorted @ index " + idx,
                    expectedOrder[idx].getJobId(), job.getJobId());
            idx++;
        }
    }

    @Test
    public void testPendingJobSorting_Random() {
        PendingJobQueue jobQueue = new PendingJobQueue();
        Random random = new Random(1); // Always use the same series of pseudo random values.

        for (int i = 0; i < 5000; ++i) {
            JobStatus job = createJobStatus("testPendingJobSorting_Random",
                    createJobInfo(i).setExpedited(random.nextBoolean()), random.nextInt(250));
            job.enqueueTime = random.nextInt(1_000_000);
            jobQueue.add(job);
        }

        checkPendingJobInvariants(jobQueue);
    }

    @Test
    public void testPendingJobSortingTransitivity() {
        PendingJobQueue jobQueue = new PendingJobQueue();
        // Always use the same series of pseudo random values.
        for (int seed : new int[]{1337, 7357, 606, 6357, 41106010, 3, 2, 1}) {
            Random random = new Random(seed);

            jobQueue.clear();

            for (int i = 0; i < 300; ++i) {
                JobStatus job = createJobStatus("testPendingJobSortingTransitivity",
                        createJobInfo(i).setExpedited(random.nextBoolean()), random.nextInt(50));
                job.enqueueTime = random.nextInt(1_000_000);
                job.overrideState = random.nextInt(4);
                jobQueue.add(job);
            }

            checkPendingJobInvariants(jobQueue);
        }
    }

    @Test
    @LargeTest
    public void testPendingJobSortingTransitivity_Concentrated() {
        PendingJobQueue jobQueue = new PendingJobQueue();
        // Always use the same series of pseudo random values.
        for (int seed : new int[]{1337, 6000, 637739, 6357, 1, 7, 13}) {
            Random random = new Random(seed);

            jobQueue.clear();

            for (int i = 0; i < 300; ++i) {
                JobStatus job = createJobStatus("testPendingJobSortingTransitivity_Concentrated",
                        createJobInfo(i).setExpedited(random.nextFloat() < .03),
                        random.nextInt(20));
                job.enqueueTime = random.nextInt(250);
                job.overrideState = random.nextFloat() < .01
                        ? JobStatus.OVERRIDE_SORTING : JobStatus.OVERRIDE_NONE;
                jobQueue.add(job);
                Log.d(TAG, testJobToString(job));
            }

            checkPendingJobInvariants(jobQueue);
        }
    }

    @Test
    public void testPendingJobSorting_Random_WithPriority() {
        PendingJobQueue jobQueue = new PendingJobQueue();
        Random random = new Random(1); // Always use the same series of pseudo random values.

        for (int i = 0; i < 5000; ++i) {
            final boolean isEj = random.nextBoolean();
            final int priority;
            if (isEj) {
                priority = random.nextBoolean() ? JobInfo.PRIORITY_MAX : JobInfo.PRIORITY_HIGH;
            } else {
                priority = sRegJobPriorities[random.nextInt(sRegJobPriorities.length)];
            }
            JobStatus job = createJobStatus("testPendingJobSorting_Random_WithPriority",
                    createJobInfo(i).setExpedited(isEj).setPriority(priority),
                    random.nextInt(250));
            job.enqueueTime = random.nextInt(1_000_000);
            jobQueue.add(job);
        }

        checkPendingJobInvariants(jobQueue);
    }

    @Test
    public void testPendingJobSortingTransitivity_WithPriority() {
        PendingJobQueue jobQueue = new PendingJobQueue();
        // Always use the same series of pseudo random values.
        for (int seed : new int[]{1337, 7357, 606, 6357, 41106010, 3, 2, 1}) {
            Random random = new Random(seed);

            jobQueue.clear();

            for (int i = 0; i < 300; ++i) {
                final boolean isEj = random.nextBoolean();
                final int priority;
                if (isEj) {
                    priority = random.nextBoolean() ? JobInfo.PRIORITY_MAX : JobInfo.PRIORITY_HIGH;
                } else {
                    priority = sRegJobPriorities[random.nextInt(sRegJobPriorities.length)];
                }
                JobStatus job = createJobStatus("testPendingJobSortingTransitivity_WithPriority",
                        createJobInfo(i).setExpedited(isEj).setPriority(priority),
                        random.nextInt(50));
                job.enqueueTime = random.nextInt(1_000_000);
                job.overrideState = random.nextInt(4);
                jobQueue.add(job);
            }

            checkPendingJobInvariants(jobQueue);
        }
    }

    @Test
    @LargeTest
    public void testPendingJobSortingTransitivity_Concentrated_WithPriority() {
        PendingJobQueue jobQueue = new PendingJobQueue();
        // Always use the same series of pseudo random values.
        for (int seed : new int[]{1337, 6000, 637739, 6357, 1, 7, 13}) {
            Random random = new Random(seed);

            jobQueue.clear();

            for (int i = 0; i < 300; ++i) {
                final boolean isEj = random.nextFloat() < .03;
                final int priority;
                if (isEj) {
                    priority = random.nextBoolean() ? JobInfo.PRIORITY_MAX : JobInfo.PRIORITY_HIGH;
                } else {
                    priority = sRegJobPriorities[random.nextInt(sRegJobPriorities.length)];
                }
                JobStatus job = createJobStatus(
                        "testPendingJobSortingTransitivity_Concentrated_WithPriority",
                        createJobInfo(i).setExpedited(isEj).setPriority(priority),
                        random.nextInt(20));
                job.enqueueTime = random.nextInt(250);
                job.overrideState = random.nextFloat() < .01
                        ? JobStatus.OVERRIDE_SORTING : JobStatus.OVERRIDE_NONE;
                jobQueue.add(job);
                Log.d(TAG, testJobToString(job));
            }

            checkPendingJobInvariants(jobQueue);
        }
    }

    private void checkPendingJobInvariants(PendingJobQueue jobQueue) {
        final SparseBooleanArray regJobSeen = new SparseBooleanArray();
        // Latest priority enqueue times seen for each priority for each app.
        final SparseArray<SparseLongArray> latestPriorityRegEnqueueTimesPerUid =
                new SparseArray<>();
        final SparseArray<SparseLongArray> latestPriorityEjEnqueueTimesPerUid = new SparseArray<>();
        final int noEntry = -1;
        int prevOverrideState = noEntry;

        JobStatus job;
        jobQueue.resetIterator();
        while ((job = jobQueue.next()) != null) {
            final int uid = job.getSourceUid();

            // Invariant #1: All jobs are sorted by override state
            // Invariant #2: All jobs (for a UID) are sorted by priority order
            // Invariant #3: Jobs (for a UID) with the same priority are sorted by enqueue time.
            // Invariant #4: EJs (for a UID) should be before regular jobs

            // Invariant 1
            if (prevOverrideState != job.overrideState) {
                if (prevOverrideState != noEntry) {
                    assertTrue(prevOverrideState > job.overrideState);
                }
                // Override state can make ordering weird. Clear the other cached states
                // to avoid confusion in the other checks.
                latestPriorityEjEnqueueTimesPerUid.clear();
                latestPriorityRegEnqueueTimesPerUid.clear();
                regJobSeen.clear();
                prevOverrideState = job.overrideState;
            }

            final int priority = job.getEffectivePriority();
            final SparseArray<SparseLongArray> latestPriorityEnqueueTimesPerUid =
                    job.isRequestedExpeditedJob()
                            ? latestPriorityEjEnqueueTimesPerUid
                            : latestPriorityRegEnqueueTimesPerUid;
            SparseLongArray latestPriorityEnqueueTimes = latestPriorityEnqueueTimesPerUid.get(uid);
            if (latestPriorityEnqueueTimes != null) {
                // Invariant 2
                for (int p = priority - 1; p >= JobInfo.PRIORITY_MIN; --p) {
                    // If we haven't seen the priority, there shouldn't be an entry in the array.
                    assertEquals("Jobs not properly sorted by priority for uid " + uid,
                            noEntry, latestPriorityEnqueueTimes.get(p, noEntry));
                }

                // Invariant 3
                final long lastSeenPriorityEnqueueTime =
                        latestPriorityEnqueueTimes.get(priority, noEntry);
                if (lastSeenPriorityEnqueueTime != noEntry) {
                    assertTrue("Jobs with same priority for uid " + uid
                                    + " not sorted by enqueue time: "
                                    + lastSeenPriorityEnqueueTime + " before " + job.enqueueTime,
                            lastSeenPriorityEnqueueTime <= job.enqueueTime);
                }
            } else {
                latestPriorityEnqueueTimes = new SparseLongArray();
                latestPriorityEnqueueTimesPerUid.put(uid, latestPriorityEnqueueTimes);
            }
            latestPriorityEnqueueTimes.put(priority, job.enqueueTime);

            // Invariant 4
            if (!job.isRequestedExpeditedJob()) {
                regJobSeen.put(uid, true);
            } else if (regJobSeen.get(uid)) {
                fail("UID " + uid + " had an EJ ordered after a regular job");
            }
        }
    }

    private static String testJobToString(JobStatus job) {
        return "testJob " + job.getSourceUid() + "/" + job.getJobId()
                + "/o" + job.overrideState
                + "/p" + job.getEffectivePriority()
                + "/b" + job.lastEvaluatedBias
                + "/" + job.isRequestedExpeditedJob() + "@" + job.enqueueTime;
    }
}
