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

import static android.app.job.JobInfo.NETWORK_TYPE_ANY;
import static android.app.job.JobInfo.NETWORK_TYPE_NONE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.job.JobInfo;
import android.content.ComponentName;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArrayMap;
import android.util.SparseBooleanArray;
import android.util.SparseLongArray;

import androidx.test.filters.LargeTest;

import com.android.server.job.controllers.JobStatus;

import org.junit.Test;

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
        return createJobStatus(testTag, jobInfoBuilder, callingUid, "PJQTest");
    }

    private JobStatus createJobStatus(String testTag, JobInfo.Builder jobInfoBuilder,
            int callingUid, String namespace) {
        return JobStatus.createFromJobInfo(
                jobInfoBuilder.build(), callingUid, "com.android.test", 0, namespace, testTag);
    }

    @Test
    public void testAdd() {
        ArraySet<JobStatus> jobs = new ArraySet<>();
        jobs.add(createJobStatus("testAdd", createJobInfo(1), 1));
        jobs.add(createJobStatus("testAdd", createJobInfo(2), 2));
        jobs.add(createJobStatus("testAdd", createJobInfo(3).setExpedited(true), 3));
        jobs.add(createJobStatus("testAdd", createJobInfo(4), 4));
        jobs.add(createJobStatus("testAdd", createJobInfo(5).setExpedited(true), 5));

        PendingJobQueue jobQueue = new PendingJobQueue();
        for (int i = 0; i < jobs.size(); ++i) {
            jobQueue.add(jobs.valueAt(i));
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
        ArraySet<JobStatus> jobs = new ArraySet<>();
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
        ArraySet<JobStatus> jobs = new ArraySet<>();
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
    public void testContains() {
        JobStatus joba1 = createJobStatus("testRemove", createJobInfo(1), 1);
        JobStatus joba2 = createJobStatus("testRemove", createJobInfo(2), 1);
        JobStatus jobb1 = createJobStatus("testRemove", createJobInfo(3).setExpedited(true), 2);
        JobStatus jobb2 = createJobStatus("testRemove",
                createJobInfo(4).setPriority(JobInfo.PRIORITY_MIN), 2);

        // Make joba1 and joba2 sort-equivalent
        joba1.enqueueTime = 3;
        joba2.enqueueTime = 3;
        jobb1.enqueueTime = 4;
        jobb2.enqueueTime = 1;

        PendingJobQueue jobQueue = new PendingJobQueue();

        assertFalse(jobQueue.contains(joba1));
        assertFalse(jobQueue.contains(joba2));
        assertFalse(jobQueue.contains(jobb1));
        assertFalse(jobQueue.contains(jobb2));

        jobQueue.add(joba1);

        assertTrue(jobQueue.contains(joba1));
        assertFalse(jobQueue.contains(joba2));
        assertFalse(jobQueue.contains(jobb1));
        assertFalse(jobQueue.contains(jobb2));

        jobQueue.add(jobb1);

        assertTrue(jobQueue.contains(joba1));
        assertFalse(jobQueue.contains(joba2));
        assertTrue(jobQueue.contains(jobb1));
        assertFalse(jobQueue.contains(jobb2));

        jobQueue.add(jobb2);

        assertTrue(jobQueue.contains(joba1));
        assertFalse(jobQueue.contains(joba2));
        assertTrue(jobQueue.contains(jobb1));
        assertTrue(jobQueue.contains(jobb2));

        jobQueue.add(joba2);

        assertTrue(jobQueue.contains(joba1));
        assertTrue(jobQueue.contains(joba2));
        assertTrue(jobQueue.contains(jobb1));
        assertTrue(jobQueue.contains(jobb2));
    }

    @Test
    public void testRemove() {
        ArraySet<JobStatus> jobs = new ArraySet<>();
        jobs.add(createJobStatus("testRemove", createJobInfo(1), 1));
        jobs.add(createJobStatus("testRemove", createJobInfo(2), 2));
        jobs.add(createJobStatus("testRemove", createJobInfo(3).setExpedited(true), 3));
        jobs.add(createJobStatus("testRemove", createJobInfo(4), 4));
        jobs.add(createJobStatus("testRemove", createJobInfo(5).setExpedited(true), 5));

        PendingJobQueue jobQueue = new PendingJobQueue();
        jobQueue.addAll(jobs);

        ArraySet<JobStatus> removed = new ArraySet<>();
        JobStatus job;
        for (int i = 0; i < jobs.size(); ++i) {
            jobQueue.remove(jobs.valueAt(i));
            removed.add(jobs.valueAt(i));

            assertEquals(jobs.size() - i - 1, jobQueue.size());

            jobQueue.resetIterator();
            while ((job = jobQueue.next()) != null) {
                assertFalse("Queue retained a removed job " + testJobToString(job),
                        removed.contains(job));
            }
        }
        assertNull(jobQueue.next());
        assertEquals(0, jobQueue.size());
    }

    @Test
    public void testRemove_duringIteration() {
        ArraySet<JobStatus> jobs = new ArraySet<>();
        jobs.add(createJobStatus("testRemove", createJobInfo(1), 1));
        jobs.add(createJobStatus("testRemove", createJobInfo(2), 2));
        jobs.add(createJobStatus("testRemove", createJobInfo(3).setExpedited(true), 3));
        jobs.add(createJobStatus("testRemove", createJobInfo(4), 4));
        jobs.add(createJobStatus("testRemove", createJobInfo(5).setExpedited(true), 5));

        PendingJobQueue jobQueue = new PendingJobQueue();
        jobQueue.addAll(jobs);

        ArraySet<JobStatus> removed = new ArraySet<>();
        JobStatus job;
        jobQueue.resetIterator();
        while ((job = jobQueue.next()) != null) {
            jobQueue.remove(job);
            removed.add(job);
            assertFalse("Queue retained a removed job " + testJobToString(job),
                    jobQueue.contains(job));
        }
        assertNull(jobQueue.next());
        assertEquals(0, jobQueue.size());
    }

    @Test
    public void testRemove_outOfOrder() {
        ArraySet<JobStatus> jobs = new ArraySet<>();
        JobStatus job1 = createJobStatus("testRemove", createJobInfo(1), 1);
        JobStatus job2 = createJobStatus("testRemove", createJobInfo(2), 1);
        JobStatus job3 = createJobStatus("testRemove", createJobInfo(3).setExpedited(true), 1);
        JobStatus job4 = createJobStatus("testRemove",
                createJobInfo(4).setPriority(JobInfo.PRIORITY_MIN), 1);
        JobStatus job5 = createJobStatus("testRemove", createJobInfo(5).setExpedited(true), 1);

        // Enqueue order (by ID): 4, 5, 3, {1,2 -- at the same time}
        job1.enqueueTime = 3;
        job2.enqueueTime = 3;
        job3.enqueueTime = 4;
        job4.enqueueTime = 1;
        job5.enqueueTime = 2;

        // 1 & 2 have the same enqueue time (could happen at boot), so ordering won't be consistent
        // between the two
        // Result job order should be (by ID): 5, 3, {1,2}, {1,2}, 4

        // Intended removal order (by ID): 5, 3, 2, 1, 4
        jobs.add(job5);
        jobs.add(job3);
        jobs.add(job2);
        jobs.add(job1);
        jobs.add(job4);

        PendingJobQueue jobQueue = new PendingJobQueue();
        jobQueue.addAll(jobs);

        ArraySet<JobStatus> removed = new ArraySet<>();
        JobStatus job;
        while ((job = jobQueue.next()) != null) {
            Log.d(TAG, testJobToString(job));
        }
        for (int i = 0; i < jobs.size(); ++i) {
            jobQueue.remove(jobs.valueAt(i));
            removed.add(jobs.valueAt(i));

            assertEquals(jobs.size() - i - 1, jobQueue.size());

            jobQueue.resetIterator();
            while ((job = jobQueue.next()) != null) {
                assertFalse("Queue retained a removed job " + testJobToString(job),
                        removed.contains(job));
            }
        }
        assertNull(jobQueue.next());

        // Intended removal order (by ID): 3, 1, 2, 5, 4
        jobs.clear();
        jobs.add(job3);
        jobs.add(job1);
        jobs.add(job5);
        jobs.add(job2);
        jobs.add(job4);

        jobQueue.addAll(jobs);

        removed.clear();
        for (int i = 0; i < jobs.size(); ++i) {
            jobQueue.remove(jobs.valueAt(i));
            removed.add(jobs.valueAt(i));

            assertEquals(jobs.size() - i - 1, jobQueue.size());

            jobQueue.resetIterator();
            while ((job = jobQueue.next()) != null) {
                assertFalse("Queue retained a removed job " + testJobToString(job),
                        removed.contains(job));
            }
        }
        assertNull(jobQueue.next());

        // Intended removal order (by ID): 3, 2, 1, 4, 5
        jobs.clear();
        jobs.add(job3);
        jobs.add(job2);
        jobs.add(job1);
        jobs.add(job4);
        jobs.add(job5);

        jobQueue.addAll(jobs);

        removed.clear();
        for (int i = 0; i < jobs.size(); ++i) {
            jobQueue.remove(jobs.valueAt(i));
            removed.add(jobs.valueAt(i));

            assertEquals(jobs.size() - i - 1, jobQueue.size());

            jobQueue.resetIterator();
            while ((job = jobQueue.next()) != null) {
                assertFalse("Queue retained a removed job " + testJobToString(job),
                        removed.contains(job));
            }
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

        JobStatus job;
        final JobStatus[] expectedPureOrder = new JobStatus[]{
                eC3, rD4, eE5, eB6, rB2, eA7, rA1, rH8, eF9, rF8, eC11, rC10, rG12, rG13, eE14};
        int idx = 0;
        jobQueue.setOptimizeIteration(false);
        checkPendingJobInvariants(jobQueue);
        jobQueue.resetIterator();
        while ((job = jobQueue.next()) != null) {
            assertEquals("List wasn't correctly sorted @ index " + idx,
                    expectedPureOrder[idx].getJobId(), job.getJobId());
            idx++;
        }

        final JobStatus[] expectedOptimizedOrder = new JobStatus[]{
                eC3, eC11, rD4, eE5, eE14, eB6, rB2, eA7, rA1, rH8, eF9, rF8, rC10, rG12, rG13};
        idx = 0;
        jobQueue.setOptimizeIteration(true);
        checkPendingJobInvariants(jobQueue);
        jobQueue.resetIterator();
        while ((job = jobQueue.next()) != null) {
            assertEquals("Optimized list wasn't correctly sorted @ index " + idx,
                    expectedOptimizedOrder[idx].getJobId(), job.getJobId());
            idx++;
        }
    }

    @Test
    public void testPendingJobSorting_namespacing() {
        PendingJobQueue jobQueue = new PendingJobQueue();

        // First letter in job variable name indicate regular (r) or expedited (e).
        // Capital letters in job variable name indicate the app/UID.
        // Third letter (x, y, z) indicates the namespace.
        // Numbers in job variable name indicate the enqueue time.
        // Expected sort order:
        //   eCx3 > rDx4 > eBy6 > rBy2 > eAy7 > rAx1 > eCy8 > rEz9 > rEz5
        // Intentions:
        //   * A jobs test expedited is before regular, regardless of namespace
        //   * B jobs test expedited is before regular, in the same namespace
        //   * C jobs test sorting by priority with different namespaces
        //   * E jobs test sorting by priority in the same namespace
        final String namespaceX = null;
        final String namespaceY = "y";
        final String namespaceZ = "z";
        JobStatus rAx1 = createJobStatus("testPendingJobSorting",
                createJobInfo(1), 1, namespaceX);
        JobStatus rBy2 = createJobStatus("testPendingJobSorting",
                createJobInfo(2), 2, namespaceY);
        JobStatus eCx3 = createJobStatus("testPendingJobSorting",
                createJobInfo(3).setExpedited(true).setPriority(JobInfo.PRIORITY_HIGH),
                3, namespaceX);
        JobStatus rDx4 = createJobStatus("testPendingJobSorting",
                createJobInfo(4), 4, namespaceX);
        JobStatus rEz5 = createJobStatus("testPendingJobSorting",
                createJobInfo(5).setPriority(JobInfo.PRIORITY_LOW), 5, namespaceZ);
        JobStatus eBy6 = createJobStatus("testPendingJobSorting",
                createJobInfo(6).setExpedited(true), 2, namespaceY);
        JobStatus eAy7 = createJobStatus("testPendingJobSorting",
                createJobInfo(7).setExpedited(true), 1, namespaceY);
        JobStatus eCy8 = createJobStatus("testPendingJobSorting",
                createJobInfo(8).setExpedited(true).setPriority(JobInfo.PRIORITY_MAX),
                3, namespaceY);
        JobStatus rEz9 = createJobStatus("testPendingJobSorting",
                createJobInfo(9).setPriority(JobInfo.PRIORITY_HIGH), 5, namespaceZ);

        rAx1.enqueueTime = 10;
        rBy2.enqueueTime = 20;
        eCx3.enqueueTime = 30;
        rDx4.enqueueTime = 40;
        rEz5.enqueueTime = 50;
        eBy6.enqueueTime = 60;
        eAy7.enqueueTime = 70;
        eCy8.enqueueTime = 80;
        rEz9.enqueueTime = 90;

        // Add in random order so sorting is apparent.
        jobQueue.add(rEz9);
        jobQueue.add(eCy8);
        jobQueue.add(rDx4);
        jobQueue.add(rEz5);
        jobQueue.add(rBy2);
        jobQueue.add(rAx1);
        jobQueue.add(eCx3);
        jobQueue.add(eBy6);
        jobQueue.add(eAy7);

        JobStatus job;
        final JobStatus[] expectedPureOrder = new JobStatus[]{
                eCx3, rDx4, eBy6, rBy2, eAy7, rAx1, eCy8, rEz9, rEz5};
        int idx = 0;
        jobQueue.setOptimizeIteration(false);
        checkPendingJobInvariants(jobQueue);
        jobQueue.resetIterator();
        while ((job = jobQueue.next()) != null) {
            assertEquals("List wasn't correctly sorted @ index " + idx,
                    expectedPureOrder[idx].getJobId(), job.getJobId());
            idx++;
        }

        final JobStatus[] expectedOptimizedOrder = new JobStatus[]{
                eCx3, eCy8, rDx4, eBy6, rBy2, eAy7, rAx1, rEz9, rEz5};
        idx = 0;
        jobQueue.setOptimizeIteration(true);
        checkPendingJobInvariants(jobQueue);
        jobQueue.resetIterator();
        while ((job = jobQueue.next()) != null) {
            assertEquals("Optimized list wasn't correctly sorted @ index " + idx,
                    expectedOptimizedOrder[idx].getJobId(), job.getJobId());
            idx++;
        }
    }

    @Test
    public void testPendingJobSorting_Random() {
        PendingJobQueue jobQueue = new PendingJobQueue();
        Random random = new Random(1); // Always use the same series of pseudo random values.

        for (int i = 0; i < 5000; ++i) {
            final boolean ui = random.nextBoolean();
            final boolean ej = !ui && random.nextBoolean();
            JobStatus job = createJobStatus("testPendingJobSorting_Random",
                    createJobInfo(i).setExpedited(ej).setUserInitiated(ui)
                            .setRequiredNetworkType(ui ? NETWORK_TYPE_ANY : NETWORK_TYPE_NONE),
                    random.nextInt(250));
            job.enqueueTime = random.nextInt(1_000_000);
            jobQueue.add(job);
        }

        checkPendingJobInvariants(jobQueue);
    }

    @Test
    public void testPendingJobSorting_Random_namespacing() {
        PendingJobQueue jobQueue = new PendingJobQueue();
        Random random = new Random(1); // Always use the same series of pseudo random values.

        for (int i = 0; i < 5000; ++i) {
            JobStatus job = createJobStatus("testPendingJobSorting_Random",
                    createJobInfo(i).setExpedited(random.nextBoolean()), random.nextInt(250),
                    "namespace" + random.nextInt(5));
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
                final boolean ui = random.nextBoolean();
                final boolean ej = !ui && random.nextBoolean();
                JobStatus job = createJobStatus("testPendingJobSortingTransitivity",
                        createJobInfo(i).setExpedited(ej).setUserInitiated(ui)
                                .setRequiredNetworkType(ui ? NETWORK_TYPE_ANY : NETWORK_TYPE_NONE),
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
    public void testPendingJobSortingTransitivity_Concentrated() {
        PendingJobQueue jobQueue = new PendingJobQueue();
        // Always use the same series of pseudo random values.
        for (int seed : new int[]{1337, 6000, 637739, 6357, 1, 7, 13}) {
            Random random = new Random(seed);

            jobQueue.clear();

            for (int i = 0; i < 300; ++i) {
                final boolean ui = random.nextFloat() < .02;
                final boolean ej = !ui && random.nextFloat() < .03;
                JobStatus job = createJobStatus("testPendingJobSortingTransitivity_Concentrated",
                        createJobInfo(i).setExpedited(ej).setUserInitiated(ui)
                                .setRequiredNetworkType(ui ? NETWORK_TYPE_ANY : NETWORK_TYPE_NONE),
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
        final SparseBooleanArray eJobSeen = new SparseBooleanArray();
        final SparseBooleanArray regJobSeen = new SparseBooleanArray();
        // Latest priority enqueue times seen for each priority+namespace for each app.
        final SparseArrayMap<String, SparseLongArray> latestPriorityRegEnqueueTimesPerUid =
                new SparseArrayMap<>();
        final SparseArrayMap<String, SparseLongArray> latestPriorityEjEnqueueTimesPerUid =
                new SparseArrayMap<>();
        final int noEntry = -1;
        int prevOverrideState = noEntry;

        JobStatus job;
        jobQueue.resetIterator();
        int count = 0;
        while ((job = jobQueue.next()) != null) {
            count++;
            final int uid = job.getSourceUid();

            // Invariant #1: All jobs are sorted by override state
            // Invariant #2: All jobs (for a UID) are sorted by priority order
            // Invariant #3: Jobs (for a UID) with the same priority are sorted by enqueue time.
            // Invariant #4: User-initiated jobs (for a UID) should be before all other jobs.
            // Invariant #5: EJs (for a UID) should be before regular jobs

            // Invariant 1
            if (prevOverrideState != job.overrideState) {
                if (prevOverrideState != noEntry) {
                    assertTrue(prevOverrideState > job.overrideState);
                }
                // Override state can make ordering weird. Clear the other cached states
                // to avoid confusion in the other checks.
                latestPriorityEjEnqueueTimesPerUid.clear();
                latestPriorityRegEnqueueTimesPerUid.clear();
                eJobSeen.clear();
                regJobSeen.clear();
                prevOverrideState = job.overrideState;
            }

            final int priority = job.getEffectivePriority();
            final SparseArrayMap<String, SparseLongArray> latestPriorityEnqueueTimesPerUid =
                    job.isRequestedExpeditedJob()
                            ? latestPriorityEjEnqueueTimesPerUid
                            : latestPriorityRegEnqueueTimesPerUid;
            SparseLongArray latestPriorityEnqueueTimes =
                    latestPriorityEnqueueTimesPerUid.get(uid, job.getNamespace());
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
                latestPriorityEnqueueTimesPerUid.add(
                        uid, job.getNamespace(), latestPriorityEnqueueTimes);
            }
            latestPriorityEnqueueTimes.put(priority, job.enqueueTime);

            if (job.isRequestedExpeditedJob()) {
                eJobSeen.put(uid, true);
            } else if (!job.getJob().isUserInitiated()) {
                regJobSeen.put(uid, true);
            }

            // Invariant 4
            if (job.getJob().isUserInitiated()) {
                if (eJobSeen.get(uid)) {
                    fail("UID " + uid + " had a UIJ ordered after an EJ");
                }
                if (regJobSeen.get(uid)) {
                    fail("UID " + uid + " had a UIJ ordered after a regular job");
                }
            }

            // Invariant 5
            if (job.isRequestedExpeditedJob() && regJobSeen.get(uid)) {
                fail("UID " + uid + " had an EJ ordered after a regular job");
            }
        }
        assertEquals("Iterator didn't go through all jobs", jobQueue.size(), count);
    }

    private static String testJobToString(JobStatus job) {
        return "testJob " + job.getSourceUid() + "/" + job.getNamespace() + "/" + job.getJobId()
                + "/o" + job.overrideState
                + "/p" + job.getEffectivePriority()
                + "/b" + job.lastEvaluatedBias
                + "/"
                + (job.isRequestedExpeditedJob()
                        ? "e" : (job.getJob().isUserInitiated() ? "u" : "r"))
                + "@" + job.enqueueTime;
    }
}
