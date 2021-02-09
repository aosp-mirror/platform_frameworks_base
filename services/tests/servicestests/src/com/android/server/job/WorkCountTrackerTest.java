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

import static com.android.server.job.JobConcurrencyManager.NUM_WORK_TYPES;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_BG;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_BGUSER;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_EJ;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_NONE;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_TOP;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.annotation.NonNull;
import android.util.Pair;
import android.util.SparseIntArray;

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

    private static final double[] EQUAL_PROBABILITY_CDF =
            buildCdf(1.0 / NUM_WORK_TYPES, 1.0 / NUM_WORK_TYPES, 1.0 / NUM_WORK_TYPES,
                    1.0 / NUM_WORK_TYPES);

    private Random mRandom;
    private WorkCountTracker mWorkCountTracker;

    @Before
    public void setUp() {
        mRandom = new Random(1); // Always use the same series of pseudo random values.
        mWorkCountTracker = new WorkCountTracker();
    }

    @NonNull
    private static double[] buildCdf(double pTop, double pEj, double pBg, double pBgUser) {
        double[] cdf = new double[JobConcurrencyManager.NUM_WORK_TYPES];
        double sum = 0;

        sum += pTop;
        cdf[0] = sum;
        sum += pEj;
        cdf[1] = sum;
        sum += pBg;
        cdf[2] = sum;
        sum += pBgUser;
        cdf[3] = sum;

        if (Double.compare(1, sum) != 0) {
            throw new IllegalArgumentException("probabilities don't sum to one: " + sum);
        }
        return cdf;
    }

    @JobConcurrencyManager.WorkType
    static int getRandomWorkType(double[] cdf, double rand) {
        for (int i = cdf.length - 1; i >= 0; --i) {
            if (rand < cdf[i] && (i == 0 || rand > cdf[i - 1])) {
                switch (i) {
                    case 0:
                        return WORK_TYPE_TOP;
                    case 1:
                        return WORK_TYPE_EJ;
                    case 2:
                        return WORK_TYPE_BG;
                    case 3:
                        return WORK_TYPE_BGUSER;
                    default:
                        throw new IllegalStateException("Unknown work type");
                }
            }
        }
        throw new IllegalStateException("Couldn't pick random work type");
    }

    /**
     * Represents running and pending jobs.
     */
    class Jobs {
        public final SparseIntArray running = new SparseIntArray();
        public final SparseIntArray pending = new SparseIntArray();

        public void maybeEnqueueJobs(double probStart, double[] typeCdf) {
            while (mRandom.nextDouble() < probStart) {
                final int workType = getRandomWorkType(typeCdf, mRandom.nextDouble());
                pending.put(workType, pending.get(workType) + 1);
            }
        }

        public void maybeFinishJobs(double probStop) {
            for (int i = running.get(WORK_TYPE_BG); i > 0; i--) {
                if (mRandom.nextDouble() < probStop) {
                    running.put(WORK_TYPE_BG, running.get(WORK_TYPE_BG) - 1);
                    mWorkCountTracker.onJobFinished(WORK_TYPE_BG);
                }
            }
            for (int i = running.get(WORK_TYPE_TOP); i > 0; i--) {
                if (mRandom.nextDouble() < probStop) {
                    running.put(WORK_TYPE_TOP, running.get(WORK_TYPE_TOP) - 1);
                    mWorkCountTracker.onJobFinished(WORK_TYPE_TOP);
                }
            }
        }
    }

    private void recount(Jobs jobs, int totalMax,
            @NonNull List<Pair<Integer, Integer>> minLimits,
            @NonNull List<Pair<Integer, Integer>> maxLimits) {
        mWorkCountTracker.setConfig(new JobConcurrencyManager.WorkTypeConfig(
                "test", totalMax, minLimits, maxLimits));
        mWorkCountTracker.resetCounts();

        for (int i = 0; i < jobs.running.size(); ++i) {
            final int workType = jobs.running.keyAt(i);
            final int count = jobs.running.valueAt(i);

            for (int c = 0; c < count; ++c) {
                mWorkCountTracker.incrementRunningJobCount(workType);
            }
        }
        for (int i = 0; i < jobs.pending.size(); ++i) {
            final int workType = jobs.pending.keyAt(i);
            final int count = jobs.pending.valueAt(i);

            for (int c = 0; c < count; ++c) {
                mWorkCountTracker.incrementPendingJobCount(workType);
            }
        }

        mWorkCountTracker.onCountDone();
    }

    private boolean hasStartablePendingJob(Jobs jobs) {
        for (int i = 0; i < jobs.pending.size(); ++i) {
            if (jobs.pending.valueAt(i) > 0
                    && mWorkCountTracker.canJobStart(jobs.pending.keyAt(i)) != WORK_TYPE_NONE) {
                return true;
            }
        }
        return false;
    }

    private void startPendingJobs(Jobs jobs) {
        while (hasStartablePendingJob(jobs)) {
            final int startingWorkType =
                    getRandomWorkType(EQUAL_PROBABILITY_CDF, mRandom.nextDouble());

            if (jobs.pending.get(startingWorkType) > 0
                    && mWorkCountTracker.canJobStart(startingWorkType) != WORK_TYPE_NONE) {
                jobs.pending.put(startingWorkType, jobs.pending.get(startingWorkType) - 1);
                jobs.running.put(startingWorkType, jobs.running.get(startingWorkType) + 1);
                mWorkCountTracker.stageJob(startingWorkType);
                mWorkCountTracker.onJobStarted(startingWorkType);
            }
        }
    }

    /**
     * Used by the following testRandom* tests.
     */
    private void checkRandom(Jobs jobs, int numTests, int totalMax,
            @NonNull List<Pair<Integer, Integer>> minLimits,
            @NonNull List<Pair<Integer, Integer>> maxLimits,
            double probStart, double[] typeCdf, double probStop) {
        for (int i = 0; i < numTests; i++) {
            jobs.maybeFinishJobs(probStop);
            jobs.maybeEnqueueJobs(probStart, typeCdf);

            recount(jobs, totalMax, minLimits, maxLimits);
            startPendingJobs(jobs);

            int totalRunning = 0;
            for (int r = 0; r < jobs.running.size(); ++r) {
                final int numRunning = jobs.running.valueAt(r);
                assertWithMessage(
                        "Work type " + jobs.running.keyAt(r) + " is running too many jobs")
                        .that(numRunning).isAtMost(totalMax);
                totalRunning += numRunning;
            }
            assertThat(totalRunning).isAtMost(totalMax);
            for (Pair<Integer, Integer> maxLimit : maxLimits) {
                assertWithMessage("Work type " + maxLimit.first + " is running too many jobs")
                        .that(jobs.running.get(maxLimit.first)).isAtMost(maxLimit.second);
            }
        }
    }

    /**
     * Randomly enqueue / stop jobs and make sure we won't run more jobs than we should.
     */
    @Test
    public void testRandom1() {
        assertThat(EQUAL_PROBABILITY_CDF.length).isEqualTo(NUM_WORK_TYPES);

        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 6;
        final List<Pair<Integer, Integer>> maxLimits = List.of(Pair.create(WORK_TYPE_BG, 4));
        final List<Pair<Integer, Integer>> minLimits = List.of(Pair.create(WORK_TYPE_BG, 2));
        final double probStop = 0.1;
        final double probStart = 0.1;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart,
                EQUAL_PROBABILITY_CDF, probStop);
    }

    @Test
    public void testRandom2() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 2;
        final List<Pair<Integer, Integer>> maxLimits =
                List.of(Pair.create(WORK_TYPE_BG, 2), Pair.create(WORK_TYPE_BGUSER, 1));
        final List<Pair<Integer, Integer>> minLimits = List.of();
        final double probStop = 0.5;
        final double[] cdf = buildCdf(0.5, 0, 0.5, 0);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart, cdf, probStop);
    }

    @Test
    public void testRandom3() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 2;
        final List<Pair<Integer, Integer>> maxLimits =
                List.of(Pair.create(WORK_TYPE_BG, 2), Pair.create(WORK_TYPE_BGUSER, 1));
        final List<Pair<Integer, Integer>> minLimits = List.of(Pair.create(WORK_TYPE_BG, 2));
        final double probStop = 0.5;
        final double[] cdf = buildCdf(1.0 / 3, 0, 1.0 / 3, 1.0 / 3);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart, cdf, probStop);
    }

    @Test
    public void testRandom4() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 10;
        final List<Pair<Integer, Integer>> maxLimits =
                List.of(Pair.create(WORK_TYPE_BG, 2), Pair.create(WORK_TYPE_BGUSER, 1));
        final List<Pair<Integer, Integer>> minLimits = List.of();
        final double probStop = 0.5;
        final double[] cdf = buildCdf(1.0 / 3, 0, 1.0 / 3, 1.0 / 3);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart, cdf, probStop);
    }

    @Test
    public void testRandom5() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 6;
        final List<Pair<Integer, Integer>> maxLimits =
                List.of(Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 2));
        final List<Pair<Integer, Integer>> minLimits = List.of(Pair.create(WORK_TYPE_BG, 2));
        final double probStop = 0.5;
        final double[] cdf = buildCdf(0.1, 0, 0.8, .1);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart, cdf, probStop);
    }

    @Test
    public void testRandom6() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 6;
        final List<Pair<Integer, Integer>> maxLimits =
                List.of(Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 2));
        final List<Pair<Integer, Integer>> minLimits = List.of(Pair.create(WORK_TYPE_BG, 2));
        final double probStop = 0.5;
        final double[] cdf = buildCdf(0.9, 0, 0.1, 0);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart, cdf, probStop);
    }

    @Test
    public void testRandom7() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 6;
        final List<Pair<Integer, Integer>> maxLimits =
                List.of(Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 2));
        final List<Pair<Integer, Integer>> minLimits = List.of(Pair.create(WORK_TYPE_BG, 2));
        final double probStop = 0.4;
        final double[] cdf = buildCdf(0.1, 0, 0.1, .8);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart, cdf, probStop);
    }

    @Test
    public void testRandom8() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 6;
        final List<Pair<Integer, Integer>> maxLimits =
                List.of(Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 2));
        final List<Pair<Integer, Integer>> minLimits =
                List.of(Pair.create(WORK_TYPE_BG, 2), Pair.create(WORK_TYPE_BGUSER, 1));
        final double probStop = 0.4;
        final double[] cdf = buildCdf(0.9, 0, 0.05, 0.05);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart, cdf, probStop);
    }

    @Test
    public void testRandom9() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 6;
        final List<Pair<Integer, Integer>> maxLimits =
                List.of(Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 2));
        final List<Pair<Integer, Integer>> minLimits =
                List.of(Pair.create(WORK_TYPE_BG, 2), Pair.create(WORK_TYPE_BGUSER, 1));
        final double probStop = 0.5;
        final double[] cdf = buildCdf(0, 0, 0.5, 0.5);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart, cdf, probStop);
    }

    @Test
    public void testRandom10() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 6;
        final List<Pair<Integer, Integer>> maxLimits =
                List.of(Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 2));
        final List<Pair<Integer, Integer>> minLimits =
                List.of(Pair.create(WORK_TYPE_BG, 2), Pair.create(WORK_TYPE_BGUSER, 1));
        final double probStop = 0.5;
        final double[] cdf = buildCdf(0, 0, 0.1, 0.9);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart, cdf, probStop);
    }

    @Test
    public void testRandom11() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 6;
        final List<Pair<Integer, Integer>> maxLimits =
                List.of(Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 2));
        final List<Pair<Integer, Integer>> minLimits =
                List.of(Pair.create(WORK_TYPE_BG, 2), Pair.create(WORK_TYPE_BGUSER, 1));
        final double probStop = 0.5;
        final double[] cdf = buildCdf(0, 0, 0.9, 0.1);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart, cdf, probStop);
    }

    @Test
    public void testRandom12() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 6;
        final List<Pair<Integer, Integer>> maxLimits = List.of(Pair.create(WORK_TYPE_BG, 4));
        final List<Pair<Integer, Integer>> minLimits =
                List.of(Pair.create(WORK_TYPE_EJ, 2), Pair.create(WORK_TYPE_BG, 2));
        final double probStop = 0.4;
        final double[] cdf = buildCdf(0.5, 0.5, 0, 0);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart, cdf, probStop);
    }

    @Test
    public void testRandom13() {
        assertThat(EQUAL_PROBABILITY_CDF.length).isEqualTo(NUM_WORK_TYPES);

        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 13;
        final List<Pair<Integer, Integer>> maxLimits = List.of(
                Pair.create(WORK_TYPE_EJ, 5), Pair.create(WORK_TYPE_BG, 4),
                Pair.create(WORK_TYPE_BGUSER, 3));
        final List<Pair<Integer, Integer>> minLimits =
                List.of(Pair.create(WORK_TYPE_EJ, 2), Pair.create(WORK_TYPE_BG, 1));
        final double probStop = 0.01;
        final double probStart = 0.99;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart,
                EQUAL_PROBABILITY_CDF, probStop);
    }

    @Test
    public void testRandom14() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 6;
        final List<Pair<Integer, Integer>> maxLimits =
                List.of(Pair.create(WORK_TYPE_EJ, 5), Pair.create(WORK_TYPE_BG, 4));
        final List<Pair<Integer, Integer>> minLimits = List.of(Pair.create(WORK_TYPE_BG, 2));
        final double probStop = 0.4;
        final double[] cdf = buildCdf(.1, 0.5, 0.35, 0.05);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart, cdf, probStop);
    }

    @Test
    public void testRandom15() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 6;
        final List<Pair<Integer, Integer>> maxLimits =
                List.of(Pair.create(WORK_TYPE_EJ, 5), Pair.create(WORK_TYPE_BG, 4),
                        Pair.create(WORK_TYPE_BGUSER, 1));
        final List<Pair<Integer, Integer>> minLimits =
                List.of(Pair.create(WORK_TYPE_EJ, 3), Pair.create(WORK_TYPE_BG, 2));
        final double probStop = 0.4;
        final double[] cdf = buildCdf(0.01, 0.49, 0.1, 0.4);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart, cdf, probStop);
    }

    /** Used by the following tests */
    private void checkSimple(int totalMax,
            @NonNull List<Pair<Integer, Integer>> minLimits,
            @NonNull List<Pair<Integer, Integer>> maxLimits,
            @NonNull List<Pair<Integer, Integer>> running,
            @NonNull List<Pair<Integer, Integer>> pending,
            @NonNull List<Pair<Integer, Integer>> resultRunning,
            @NonNull List<Pair<Integer, Integer>> resultPending) {
        final Jobs jobs = new Jobs();
        for (Pair<Integer, Integer> run : running) {
            jobs.running.put(run.first, run.second);
        }
        for (Pair<Integer, Integer> pend : pending) {
            jobs.pending.put(pend.first, pend.second);
        }

        recount(jobs, totalMax, minLimits, maxLimits);
        startPendingJobs(jobs);

        for (Pair<Integer, Integer> run : resultRunning) {
            assertWithMessage("Incorrect running result for work type " + run.first)
                    .that(jobs.running.get(run.first)).isEqualTo(run.second);
        }
        for (Pair<Integer, Integer> pend : resultPending) {
            assertWithMessage("Incorrect pending result for work type " + pend.first)
                    .that(jobs.pending.get(pend.first)).isEqualTo(pend.second);
        }
    }

    @Test
    public void testBasic() {
        checkSimple(6,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 2)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 2)),
                /* run */ List.of(),
                /* pen */ List.of(Pair.create(WORK_TYPE_TOP, 1)),
                /* resRun */ List.of(Pair.create(WORK_TYPE_TOP, 1)),
                /* resPen */ List.of());

        checkSimple(6,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 2)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 2)),
                /* run */ List.of(),
                /* pen */ List.of(Pair.create(WORK_TYPE_TOP, 10)),
                /* resRun */ List.of(Pair.create(WORK_TYPE_TOP, 6)),
                /* resPen */ List.of(Pair.create(WORK_TYPE_TOP, 4)));

        // When there are BG jobs pending, 2 (min-BG) jobs should run.
        checkSimple(6,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 2)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 2)),
                /* run */ List.of(),
                /* pen */ List.of(Pair.create(WORK_TYPE_TOP, 10), Pair.create(WORK_TYPE_BG, 1)),
                /* resRun */ List.of(Pair.create(WORK_TYPE_TOP, 5), Pair.create(WORK_TYPE_BG, 1)),
                /* resPen */ List.of(Pair.create(WORK_TYPE_TOP, 5)));
        checkSimple(6,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 2)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 4)),
                /* run */ List.of(),
                /* pen */ List.of(Pair.create(WORK_TYPE_TOP, 10), Pair.create(WORK_TYPE_BG, 3)),
                /* resRun */ List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_BG, 2)),
                /* resPen */ List.of(Pair.create(WORK_TYPE_TOP, 6), Pair.create(WORK_TYPE_BG, 1)));

        checkSimple(8,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 2)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 6)),
                /* run */ List.of(),
                /* pen */ List.of(Pair.create(WORK_TYPE_BG, 49)),
                /* resRun */ List.of(Pair.create(WORK_TYPE_BG, 6)),
                /* resPen */ List.of(Pair.create(WORK_TYPE_BG, 43)));

        checkSimple(8,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 2)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 6)),
                /* run */ List.of(Pair.create(WORK_TYPE_TOP, 2), Pair.create(WORK_TYPE_BG, 4)),
                /* pen */ List.of(Pair.create(WORK_TYPE_BG, 49)),
                /* resRun */ List.of(Pair.create(WORK_TYPE_TOP, 2), Pair.create(WORK_TYPE_BG, 6)),
                /* resPen */ List.of(Pair.create(WORK_TYPE_BG, 47)));

        checkSimple(8,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 2)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 6)),
                /* run */ List.of(Pair.create(WORK_TYPE_TOP, 2), Pair.create(WORK_TYPE_BG, 4)),
                /* pen */ List.of(Pair.create(WORK_TYPE_TOP, 49), Pair.create(WORK_TYPE_BG, 49)),
                /* resRun */ List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_BG, 4)),
                /* resPen */ List.of(Pair.create(WORK_TYPE_TOP, 47), Pair.create(WORK_TYPE_BG, 49))
        );


        checkSimple(8,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 2)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 6), Pair.create(WORK_TYPE_BG, 6)),
                /* run */ List.of(Pair.create(WORK_TYPE_TOP, 2), Pair.create(WORK_TYPE_BG, 4)),
                /* pen */ List.of(Pair.create(WORK_TYPE_TOP, 10), Pair.create(WORK_TYPE_BG, 49)),
                /* resRun */ List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_BG, 4)),
                /* resPen */ List.of(Pair.create(WORK_TYPE_TOP, 8), Pair.create(WORK_TYPE_BG, 49)));

        checkSimple(8,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 2)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 6), Pair.create(WORK_TYPE_BG, 6)),
                /* run */ List.of(Pair.create(WORK_TYPE_TOP, 2), Pair.create(WORK_TYPE_BG, 1)),
                /* pen */ List.of(Pair.create(WORK_TYPE_TOP, 10), Pair.create(WORK_TYPE_BG, 49)),
                /* resRun */ List.of(Pair.create(WORK_TYPE_TOP, 6), Pair.create(WORK_TYPE_BG, 2)),
                /* resPen */ List.of(Pair.create(WORK_TYPE_TOP, 6), Pair.create(WORK_TYPE_BG, 48)));

        checkSimple(8,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 1)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 6), Pair.create(WORK_TYPE_BG, 2)),
                /* run */ List.of(Pair.create(WORK_TYPE_BG, 6)),
                /* pen */ List.of(Pair.create(WORK_TYPE_TOP, 10), Pair.create(WORK_TYPE_BG, 49)),
                /* resRun */ List.of(Pair.create(WORK_TYPE_TOP, 2), Pair.create(WORK_TYPE_BG, 6)),
                /* resPen */ List.of(Pair.create(WORK_TYPE_TOP, 8), Pair.create(WORK_TYPE_BG, 49)));

        checkSimple(8,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 1)),
                /* max */ List.of(Pair.create(WORK_TYPE_TOP, 6), Pair.create(WORK_TYPE_BG, 2)),
                /* run */ List.of(Pair.create(WORK_TYPE_BG, 6)),
                /* pen */ List.of(Pair.create(WORK_TYPE_TOP, 10), Pair.create(WORK_TYPE_BG, 49)),
                /* resRun */ List.of(Pair.create(WORK_TYPE_TOP, 2), Pair.create(WORK_TYPE_BG, 6)),
                /* resPen */ List.of(Pair.create(WORK_TYPE_TOP, 8), Pair.create(WORK_TYPE_BG, 49)));

        checkSimple(6,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 2)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 4)),
                /* run */ List.of(Pair.create(WORK_TYPE_TOP, 6)),
                /* pen */ List.of(Pair.create(WORK_TYPE_TOP, 10), Pair.create(WORK_TYPE_BG, 3)),
                /* resRun */ List.of(Pair.create(WORK_TYPE_TOP, 6)),
                /* resPen */ List.of(Pair.create(WORK_TYPE_TOP, 10), Pair.create(WORK_TYPE_BG, 3)));

        checkSimple(8,
                /* min */ List.of(Pair.create(WORK_TYPE_EJ, 2), Pair.create(WORK_TYPE_BG, 2)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 4)),
                /* run */ List.of(Pair.create(WORK_TYPE_TOP, 6)),
                /* pen */ List.of(Pair.create(WORK_TYPE_TOP, 10), Pair.create(WORK_TYPE_EJ, 5),
                        Pair.create(WORK_TYPE_BG, 3)),
                /* resRun */ List.of(Pair.create(WORK_TYPE_TOP, 6), Pair.create(WORK_TYPE_EJ, 2)),
                /* resPen */ List.of(Pair.create(WORK_TYPE_TOP, 10), Pair.create(WORK_TYPE_BG, 3),
                        Pair.create(WORK_TYPE_BG, 3)));

        // This could happen if we lower the effective config due to higher memory pressure after
        // we've already started running jobs. We shouldn't stop already running jobs, but also
        // shouldn't start new ones.
        checkSimple(5,
                /* min */ List.of(),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 1)),
                /* run */ List.of(Pair.create(WORK_TYPE_BG, 6)),
                /* pen */ List.of(Pair.create(WORK_TYPE_TOP, 10), Pair.create(WORK_TYPE_BG, 3)),
                /* resRun */ List.of(Pair.create(WORK_TYPE_BG, 6)),
                /* resPen */ List.of(Pair.create(WORK_TYPE_TOP, 10), Pair.create(WORK_TYPE_BG, 3)));

        checkSimple(6,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 2)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 2)),
                /* run */ List.of(Pair.create(WORK_TYPE_BG, 2)),
                /* pen */ List.of(Pair.create(WORK_TYPE_TOP, 10),
                        Pair.create(WORK_TYPE_BG, 3),
                        Pair.create(WORK_TYPE_BGUSER, 3)),
                /* resRun */ List.of(Pair.create(WORK_TYPE_TOP, 4), Pair.create(WORK_TYPE_BG, 2)),
                /* resPen */ List.of(Pair.create(WORK_TYPE_TOP, 6),
                        Pair.create(WORK_TYPE_BG, 3),
                        Pair.create(WORK_TYPE_BGUSER, 3)));

        checkSimple(6,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 2)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 3)),
                /* run */ List.of(Pair.create(WORK_TYPE_BG, 2)),
                /* pen */ List.of(Pair.create(WORK_TYPE_BG, 3), Pair.create(WORK_TYPE_BGUSER, 3)),
                /* resRun */ List.of(
                        Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 2)),
                /* resPen */ List.of(
                        Pair.create(WORK_TYPE_BG, 1), Pair.create(WORK_TYPE_BGUSER, 1)));

        checkSimple(6,
                /* min */ List.of(Pair.create(WORK_TYPE_BG, 2)),
                /* max */ List.of(Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 1)),
                /* run */ List.of(Pair.create(WORK_TYPE_BG, 2)),
                /* pen */ List.of(Pair.create(WORK_TYPE_BG, 3), Pair.create(WORK_TYPE_BGUSER, 3)),
                /* resRun */ List.of(
                        Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 1)),
                /* resPen */ List.of(
                        Pair.create(WORK_TYPE_BG, 1), Pair.create(WORK_TYPE_BGUSER, 2)));
    }

    /** Tests that the counter updates properly when jobs are stopped. */
    @Test
    public void testJobLifecycleLoop() {
        final Jobs jobs = new Jobs();
        jobs.pending.put(WORK_TYPE_TOP, 11);
        jobs.pending.put(WORK_TYPE_BG, 10);

        final int totalMax = 6;
        final List<Pair<Integer, Integer>> minLimits = List.of(Pair.create(WORK_TYPE_BG, 1));
        final List<Pair<Integer, Integer>> maxLimits = List.of(Pair.create(WORK_TYPE_BG, 5));

        recount(jobs, totalMax, minLimits, maxLimits);

        startPendingJobs(jobs);

        assertThat(jobs.running.get(WORK_TYPE_TOP)).isEqualTo(5);
        assertThat(jobs.running.get(WORK_TYPE_BG)).isEqualTo(1);
        assertThat(jobs.pending.get(WORK_TYPE_TOP)).isEqualTo(6);
        assertThat(jobs.pending.get(WORK_TYPE_BG)).isEqualTo(9);

        // Stop all jobs
        jobs.maybeFinishJobs(1);

        assertThat(mWorkCountTracker.canJobStart(WORK_TYPE_TOP)).isEqualTo(WORK_TYPE_TOP);
        assertThat(mWorkCountTracker.canJobStart(WORK_TYPE_BG)).isEqualTo(WORK_TYPE_BG);

        startPendingJobs(jobs);

        assertThat(jobs.running.get(WORK_TYPE_TOP)).isEqualTo(5);
        assertThat(jobs.running.get(WORK_TYPE_BG)).isEqualTo(1);
        assertThat(jobs.pending.get(WORK_TYPE_TOP)).isEqualTo(1);
        assertThat(jobs.pending.get(WORK_TYPE_BG)).isEqualTo(8);

        // Stop only a bg job and make sure the counter only allows another bg job to start.
        jobs.running.put(WORK_TYPE_BG, jobs.running.get(WORK_TYPE_BG) - 1);
        mWorkCountTracker.onJobFinished(WORK_TYPE_BG);

        assertThat(mWorkCountTracker.canJobStart(WORK_TYPE_TOP)).isEqualTo(WORK_TYPE_NONE);
        assertThat(mWorkCountTracker.canJobStart(WORK_TYPE_BG)).isEqualTo(WORK_TYPE_BG);

        startPendingJobs(jobs);

        assertThat(jobs.running.get(WORK_TYPE_TOP)).isEqualTo(5);
        assertThat(jobs.running.get(WORK_TYPE_BG)).isEqualTo(1);
        assertThat(jobs.pending.get(WORK_TYPE_TOP)).isEqualTo(1);
        assertThat(jobs.pending.get(WORK_TYPE_BG)).isEqualTo(7);

        // Stop only a top job and make sure the counter only allows another top job to start.
        jobs.running.put(WORK_TYPE_TOP, jobs.running.get(WORK_TYPE_TOP) - 1);
        mWorkCountTracker.onJobFinished(WORK_TYPE_TOP);

        assertThat(mWorkCountTracker.canJobStart(WORK_TYPE_TOP)).isEqualTo(WORK_TYPE_TOP);
        assertThat(mWorkCountTracker.canJobStart(WORK_TYPE_BG)).isEqualTo(WORK_TYPE_NONE);

        startPendingJobs(jobs);

        assertThat(jobs.running.get(WORK_TYPE_TOP)).isEqualTo(5);
        assertThat(jobs.running.get(WORK_TYPE_BG)).isEqualTo(1);
        assertThat(jobs.pending.get(WORK_TYPE_TOP)).isEqualTo(0);
        assertThat(jobs.pending.get(WORK_TYPE_BG)).isEqualTo(7);

        // Now that there are no more TOP jobs pending, BG should be able to start when TOP stops.
        for (int i = jobs.running.get(WORK_TYPE_TOP); i > 0; --i) {
            jobs.running.put(WORK_TYPE_TOP, jobs.running.get(WORK_TYPE_TOP) - 1);
            mWorkCountTracker.onJobFinished(WORK_TYPE_TOP);

            assertThat(mWorkCountTracker.canJobStart(WORK_TYPE_BG)).isEqualTo(WORK_TYPE_BG);
        }

        startPendingJobs(jobs);

        assertThat(jobs.running.get(WORK_TYPE_TOP)).isEqualTo(0);
        assertThat(jobs.running.get(WORK_TYPE_BG)).isEqualTo(5);
        assertThat(jobs.pending.get(WORK_TYPE_TOP)).isEqualTo(0);
        assertThat(jobs.pending.get(WORK_TYPE_BG)).isEqualTo(3);
    }
}
