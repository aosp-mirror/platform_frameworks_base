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

import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_BG;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_NONE;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_TOP;

import static com.google.common.truth.Truth.assertThat;

import android.util.Log;
import android.util.Pair;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.job.JobConcurrencyManager.WorkCountTracker;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Random;

/**
 * Test for {@link WorkCountTracker}.
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class WorkCountTrackerTest {
    private static final String TAG = "WorkerCountTrackerTest";

    private Random mRandom;
    private WorkCountTracker mWorkCountTracker;

    @Before
    public void setUp() {
        mRandom = new Random(1); // Always use the same series of pseudo random values.
        mWorkCountTracker = new WorkCountTracker();
    }

    /**
     * Represents running and pending jobs.
     */
    class Jobs {
        public int runningFg;
        public int runningBg;
        public int pendingFg;
        public int pendingBg;

        public void maybeEnqueueJobs(double startRatio, double fgJobRatio) {
            while (mRandom.nextDouble() < startRatio) {
                if (mRandom.nextDouble() < fgJobRatio) {
                    pendingFg++;
                } else {
                    pendingBg++;
                }
            }
        }

        public void maybeFinishJobs(double stopRatio) {
            for (int i = runningBg; i > 0; i--) {
                if (mRandom.nextDouble() < stopRatio) {
                    runningBg--;
                }
            }
            for (int i = runningFg; i > 0; i--) {
                if (mRandom.nextDouble() < stopRatio) {
                    runningFg--;
                }
            }
        }
    }


    private void startPendingJobs(Jobs jobs, int totalMax, int maxBg, int minBg) {
        mWorkCountTracker.setConfig(new JobConcurrencyManager.WorkTypeConfig("critical",
                totalMax,
                // defaultMin
                List.of(Pair.create(WORK_TYPE_TOP, totalMax - maxBg),
                        Pair.create(WORK_TYPE_BG, minBg)),
                // defaultMax
                List.of(Pair.create(WORK_TYPE_BG, maxBg))));
        mWorkCountTracker.resetCounts();

        for (int i = 0; i < jobs.runningFg; i++) {
            mWorkCountTracker.incrementRunningJobCount(WORK_TYPE_TOP);
        }
        for (int i = 0; i < jobs.runningBg; i++) {
            mWorkCountTracker.incrementRunningJobCount(WORK_TYPE_BG);
        }

        for (int i = 0; i < jobs.pendingFg; i++) {
            mWorkCountTracker.incrementPendingJobCount(WORK_TYPE_TOP);
        }
        for (int i = 0; i < jobs.pendingBg; i++) {
            mWorkCountTracker.incrementPendingJobCount(WORK_TYPE_BG);
        }

        mWorkCountTracker.onCountDone();

        while ((jobs.pendingFg > 0
                && mWorkCountTracker.canJobStart(WORK_TYPE_TOP) != WORK_TYPE_NONE)
                || (jobs.pendingBg > 0
                && mWorkCountTracker.canJobStart(WORK_TYPE_BG) != WORK_TYPE_NONE)) {
            final boolean isStartingFg = mRandom.nextBoolean();

            if (isStartingFg) {
                if (jobs.pendingFg > 0
                        && mWorkCountTracker.canJobStart(WORK_TYPE_TOP) != WORK_TYPE_NONE) {
                    jobs.pendingFg--;
                    jobs.runningFg++;
                    mWorkCountTracker.stageJob(WORK_TYPE_TOP);
                    mWorkCountTracker.onJobStarted(WORK_TYPE_TOP);
                }
            } else {
                if (jobs.pendingBg > 0
                        && mWorkCountTracker.canJobStart(WORK_TYPE_BG) != WORK_TYPE_NONE) {
                    jobs.pendingBg--;
                    jobs.runningBg++;
                    mWorkCountTracker.stageJob(WORK_TYPE_BG);
                    mWorkCountTracker.onJobStarted(WORK_TYPE_BG);
                }
            }
        }

        Log.i(TAG, "" + mWorkCountTracker);
    }

    /**
     * Used by the following testRandom* tests.
     */
    private void checkRandom(Jobs jobs, int numTests, int totalMax, int maxBg, int minBg,
            double startRatio, double fgJobRatio, double stopRatio) {
        for (int i = 0; i < numTests; i++) {

            jobs.maybeFinishJobs(stopRatio);
            jobs.maybeEnqueueJobs(startRatio, fgJobRatio);

            startPendingJobs(jobs, totalMax, maxBg, minBg);

            assertThat(jobs.runningFg).isAtMost(totalMax);
            assertThat(jobs.runningBg).isAtMost(totalMax);
            assertThat(jobs.runningFg + jobs.runningBg).isAtMost(totalMax);
            assertThat(jobs.runningBg).isAtMost(maxBg);
        }
    }

    /**
     * Randomly enqueue / stop jobs and make sure we won't run more jobs than we should.
     */
    @Test
    public void testRandom1() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 6;
        final int maxBg = 4;
        final int minBg = 2;
        final double stopRatio = 0.1;
        final double fgJobRatio = 0.5;
        final double startRatio = 0.1;

        checkRandom(jobs, numTests, totalMax, maxBg, minBg, startRatio, fgJobRatio , stopRatio);
    }

    @Test
    public void testRandom2() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 2;
        final int maxBg = 2;
        final int minBg = 0;
        final double stopRatio = 0.5;
        final double fgJobRatio = 0.5;
        final double startRatio = 0.5;

        checkRandom(jobs, numTests, totalMax, maxBg, minBg, startRatio, fgJobRatio, stopRatio);
    }

    @Test
    public void testRandom3() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 2;
        final int maxBg = 2;
        final int minBg = 2;
        final double stopRatio = 0.5;
        final double fgJobRatio = 0.5;
        final double startRatio = 0.5;

        checkRandom(jobs, numTests, totalMax, maxBg, minBg, startRatio, fgJobRatio, stopRatio);
    }

    @Test
    public void testRandom4() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 10;
        final int maxBg = 2;
        final int minBg = 0;
        final double stopRatio = 0.5;
        final double fgJobRatio = 0.5;
        final double startRatio = 0.5;

        checkRandom(jobs, numTests, totalMax, maxBg, minBg, startRatio, fgJobRatio, stopRatio);
    }

    @Test
    public void testRandom5() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 6;
        final int maxBg = 4;
        final int minBg = 2;
        final double stopRatio = 0.5;
        final double fgJobRatio = 0.1;
        final double startRatio = 0.5;

        checkRandom(jobs, numTests, totalMax, maxBg, minBg, startRatio, fgJobRatio, stopRatio);
    }

    @Test
    public void testRandom6() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 6;
        final int maxBg = 4;
        final int minBg = 2;
        final double stopRatio = 0.5;
        final double fgJobRatio = 0.9;
        final double startRatio = 0.5;

        checkRandom(jobs, numTests, totalMax, maxBg, minBg, startRatio, fgJobRatio, stopRatio);
    }

    @Test
    public void testRandom7() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 6;
        final int maxBg = 4;
        final int minBg = 2;
        final double stopRatio = 0.4;
        final double fgJobRatio = 0.1;
        final double startRatio = 0.5;

        checkRandom(jobs, numTests, totalMax, maxBg, minBg, startRatio, fgJobRatio, stopRatio);
    }

    @Test
    public void testRandom8() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 6;
        final int maxBg = 4;
        final int minBg = 2;
        final double stopRatio = 0.4;
        final double fgJobRatio = 0.9;
        final double startRatio = 0.5;

        checkRandom(jobs, numTests, totalMax, maxBg, minBg, startRatio, fgJobRatio, stopRatio);
    }

    /** Used by the following tests */
    private void checkSimple(int totalMax, int maxBg, int minBg,
            int runningFg, int runningBg, int pendingFg, int pendingBg,
            int resultRunningFg, int resultRunningBg, int resultPendingFg, int resultPendingBg) {
        final Jobs jobs = new Jobs();
        jobs.runningFg = runningFg;
        jobs.runningBg = runningBg;
        jobs.pendingFg = pendingFg;
        jobs.pendingBg = pendingBg;

        startPendingJobs(jobs, totalMax, maxBg, minBg);

//        fail(mWorkerCountTracker.toString());
        assertThat(jobs.runningFg).isEqualTo(resultRunningFg);
        assertThat(jobs.runningBg).isEqualTo(resultRunningBg);

        assertThat(jobs.pendingFg).isEqualTo(resultPendingFg);
        assertThat(jobs.pendingBg).isEqualTo(resultPendingBg);
    }


    @Test
    public void testBasic() {
        // Args are:
        // First 3: Total-max, bg-max, bg-min.
        // Next 2:  Running FG / BG
        // Next 2:  Pending FG / BG
        // Next 4:  Result running FG / BG, pending FG/BG.
        checkSimple(6, 4, 2, /*run=*/ 0, 0, /*pen=*/ 1, 0, /*res run/pen=*/ 1, 0, 0, 0);

        checkSimple(6, 4, 2, /*run=*/ 0, 0, /*pen=*/ 10, 0, /*res run/pen=*/ 6, 0, 4, 0);

        // When there are BG jobs pending, 2 (min-BG) jobs should run.
        checkSimple(6, 4, 2, /*run=*/ 0, 0, /*pen=*/ 10, 1, /*res run/pen=*/ 5, 1, 5, 0);
        checkSimple(6, 4, 2, /*run=*/ 0, 0, /*pen=*/ 10, 3, /*res run/pen=*/ 4, 2, 6, 1);

        checkSimple(8, 6, 2, /*run=*/ 0, 0, /*pen=*/ 0, 49, /*res run/pen=*/ 0, 6, 0, 43);

        checkSimple(6, 4, 2, /*run=*/ 6, 0, /*pen=*/ 10, 3, /*res run/pen=*/ 6, 0, 10, 3);
    }
}
