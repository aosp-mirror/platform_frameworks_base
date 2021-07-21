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
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_BGUSER_IMPORTANT;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_EJ;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_FGS;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_NONE;
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_TOP;
import static com.android.server.job.JobConcurrencyManager.workTypeToString;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.annotation.NonNull;
import android.util.Log;
import android.util.Pair;
import android.util.SparseIntArray;

import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.job.JobConcurrencyManager.WorkCountTracker;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Test for {@link WorkCountTracker}.
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class WorkCountTrackerTest {
    private static final String TAG = "WorkCountTrackerTest";

    private static final double[] EQUAL_PROBABILITY_CDF =
            buildWorkTypeCdf(1.0 / NUM_WORK_TYPES, 1.0 / NUM_WORK_TYPES, 1.0 / NUM_WORK_TYPES,
                    1.0 / NUM_WORK_TYPES, 1.0 / NUM_WORK_TYPES, 1.0 / NUM_WORK_TYPES);

    private Random mRandom;
    private WorkCountTracker mWorkCountTracker;

    @Before
    public void setUp() {
        mRandom = new Random(1); // Always use the same series of pseudo random values.
        mWorkCountTracker = new WorkCountTracker();
    }

    @NonNull
    private static double[] buildWorkTypeCdf(
            double pTop, double pFgs, double pEj, double pBg, double pBgUserImp, double pBgUser) {
        return buildCdf(pTop, pFgs, pEj, pBg, pBgUserImp, pBgUser);
    }

    @NonNull
    private static double[] buildCdf(double... probs) {
        if (probs.length == 0) {
            throw new IllegalArgumentException("Must supply at least one probability");
        }
        double[] cdf = new double[probs.length];
        double sum = 0;

        for (int i = 0; i < probs.length; ++i) {
            sum += probs[i];
            cdf[i] = sum;
        }

        if (Double.compare(1, sum) != 0) {
            Log.e(TAG, "probabilities don't sum to one: " + sum);
            // 1.0/6 doesn't work well in code :/
            cdf[cdf.length - 1] = 1;
        }
        return cdf;
    }

    static int getRandomIndex(double[] cdf, double rand) {
        for (int i = cdf.length - 1; i >= 0; --i) {
            if (rand < cdf[i] && (i == 0 || rand > cdf[i - 1])) {
                return i;
            }
        }
        throw new IllegalStateException("Couldn't pick random index");
    }

    @JobConcurrencyManager.WorkType
    static int getRandomWorkType(double[] cdf, double rand) {
        final int index = getRandomIndex(cdf, rand);
        switch (index) {
            case 0:
                return WORK_TYPE_TOP;
            case 1:
                return WORK_TYPE_FGS;
            case 2:
                return WORK_TYPE_EJ;
            case 3:
                return WORK_TYPE_BG;
            case 4:
                return WORK_TYPE_BGUSER_IMPORTANT;
            case 5:
                return WORK_TYPE_BGUSER;
            default:
                throw new IllegalStateException("Unknown work type");
        }
    }

    /**
     * Represents running and pending jobs.
     */
    class Jobs {
        public final SparseIntArray running = new SparseIntArray();
        public final SparseIntArray pending = new SparseIntArray();
        public final List<Integer> pendingMultiTypes = new ArrayList<>();

        /**
         * @param probStart   Probability of starting a job
         * @param typeCdf     The CDF representing the probability of each work type
         * @param numTypesCdf The CDF representing the probability of a job having X different
         *                    work types. Each index i represents i+1 work types (ie. index 0 = 1
         *                    work type, index 3 = 4 work types).
         */
        public void maybeEnqueueJobs(double probStart, double[] typeCdf, double[] numTypesCdf) {
            assertThat(numTypesCdf.length).isAtMost(NUM_WORK_TYPES);
            assertThat(numTypesCdf.length).isAtLeast(1);

            while (mRandom.nextDouble() < probStart) {
                final int numTypes = getRandomIndex(numTypesCdf, mRandom.nextDouble()) + 1;
                int types = WORK_TYPE_NONE;
                for (int i = 0; i < numTypes; ++i) {
                    types |= getRandomWorkType(typeCdf, mRandom.nextDouble());
                }
                addPending(types, 1);
            }
        }

        void addPending(int allWorkTypes, int num) {
            for (int n = 0; n < num; ++n) {
                for (int i = 0; i < 32; ++i) {
                    final int type = 1 << i;
                    if ((allWorkTypes & type) != 0) {
                        pending.put(type, pending.get(type) + 1);
                    }
                }
                pendingMultiTypes.add(allWorkTypes);
            }
        }

        void removePending(int allWorkTypes) {
            for (int i = 0; i < 32; ++i) {
                final int type = 1 << i;
                if ((allWorkTypes & type) != 0) {
                    pending.put(type, pending.get(type) - 1);
                }
            }
            pendingMultiTypes.remove(Integer.valueOf(allWorkTypes));
        }

        public void maybeFinishJobs(double probStop) {
            for (int i = running.size() - 1; i >= 0; --i) {
                final int workType = running.keyAt(i);
                for (int c = running.valueAt(i); c > 0; --c) {
                    if (mRandom.nextDouble() < probStop) {
                        running.put(workType, running.get(workType) - 1);
                        mWorkCountTracker.onJobFinished(workType);
                    }
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

    private int getPendingMultiType(Jobs jobs, @JobConcurrencyManager.WorkType int workType) {
        for (int multiType : jobs.pendingMultiTypes) {
            if ((multiType & workType) != 0) {
                return multiType;
            }
        }
        throw new IllegalStateException("No pending multi type with work type: " + workType);
    }

    private void startPendingJobs(Jobs jobs) {
        while (hasStartablePendingJob(jobs)) {
            final int workType = getRandomWorkType(EQUAL_PROBABILITY_CDF, mRandom.nextDouble());

            if (jobs.pending.get(workType) > 0) {
                final int pendingMultiType = getPendingMultiType(jobs, workType);
                final int startingWorkType = mWorkCountTracker.canJobStart(pendingMultiType);
                if (startingWorkType == WORK_TYPE_NONE) {
                    continue;
                }

                jobs.removePending(pendingMultiType);
                jobs.running.put(startingWorkType, jobs.running.get(startingWorkType) + 1);
                mWorkCountTracker.stageJob(startingWorkType, pendingMultiType);
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
            double probStart, double[] typeCdf, double[] numTypesCdf, double probStop) {
        int minExpected = 0;
        for (Pair<Integer, Integer> minLimit : minLimits) {
            minExpected = Math.min(minLimit.second, minExpected);
        }
        for (int i = 0; i < numTests; i++) {
            jobs.maybeFinishJobs(probStop);
            jobs.maybeEnqueueJobs(probStart, typeCdf, numTypesCdf);

            recount(jobs, totalMax, minLimits, maxLimits);
            final int numPending = jobs.pendingMultiTypes.size();
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
            assertThat(totalRunning).isAtLeast(Math.min(minExpected, numPending));
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
                EQUAL_PROBABILITY_CDF, EQUAL_PROBABILITY_CDF, probStop);
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
        final double[] cdf = buildWorkTypeCdf(0.5, 0, 0, 0.5, 0, 0);
        final double[] numTypesCdf = buildCdf(.5, .3, .15, .05);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart,
                cdf, numTypesCdf, probStop);
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
        final double[] cdf = buildWorkTypeCdf(1.0 / 3, 0, 0, 1.0 / 3, 0, 1.0 / 3);
        final double[] numTypesCdf = buildCdf(.75, .2, .05);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart,
                cdf, numTypesCdf, probStop);
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
        final double[] cdf = buildWorkTypeCdf(1.0 / 3, 0, 0, 1.0 / 3, 0, 1.0 / 3);
        final double[] numTypesCdf = buildCdf(.05, .95);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart,
                cdf, numTypesCdf, probStop);
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
        final double[] cdf = buildWorkTypeCdf(0.1, 0, 0, 0.8, 0.02, .08);
        final double[] numTypesCdf = buildCdf(.5, .3, .15, .05);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart,
                cdf, numTypesCdf, probStop);
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
        final double[] cdf = buildWorkTypeCdf(0.85, 0.05, 0, 0.1, 0, 0);
        final double[] numTypesCdf = buildCdf(1);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart,
                cdf, numTypesCdf, probStop);
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
        final double[] cdf = buildWorkTypeCdf(0.1, 0, 0, 0.1, 0.05, .75);
        final double[] numTypesCdf = buildCdf(0.5, 0.5);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart,
                cdf, numTypesCdf, probStop);
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
        final double[] cdf = buildWorkTypeCdf(0.8, 0.1, 0, 0.05, 0, 0.05);
        final double[] numTypesCdf = buildCdf(1);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart,
                cdf, numTypesCdf, probStop);
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
        final double[] cdf = buildWorkTypeCdf(0, 0, 0, 0.5, 0, 0.5);
        final double[] numTypesCdf = buildCdf(1);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart,
                cdf, numTypesCdf, probStop);
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
        final double[] cdf = buildWorkTypeCdf(0, 0, 0, 0.1, 0, 0.9);
        final double[] numTypesCdf = buildCdf(0.9, 0.1);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart,
                cdf, numTypesCdf, probStop);
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
        final double[] cdf = buildWorkTypeCdf(0, 0, 0, 0.9, 0, 0.1);
        final double[] numTypesCdf = buildCdf(1);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart,
                cdf, numTypesCdf, probStop);
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
        final double[] cdf = buildWorkTypeCdf(0.5, 0, 0.5, 0, 0, 0);
        final double[] numTypesCdf = buildCdf(0.1, 0.7, 0.2);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart,
                cdf, numTypesCdf, probStop);
    }

    @Test
    @LargeTest
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
        final double probStop = 0.13;
        final double[] numTypesCdf = buildCdf(0, 0.05, 0.1, 0.7, 0.1, 0.05);
        final double probStart = 0.87;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart,
                EQUAL_PROBABILITY_CDF, numTypesCdf, probStop);
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
        final double[] cdf = buildWorkTypeCdf(.1, 0, 0.5, 0.35, 0, 0.05);
        final double[] numTypesCdf = buildCdf(1);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart,
                cdf, numTypesCdf, probStop);
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
        final double[] cdf = buildWorkTypeCdf(0.01, 0.09, 0.4, 0.1, 0, 0.4);
        final double[] numTypesCdf = buildCdf(0.7, 0.3);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart,
                cdf, numTypesCdf, probStop);
    }

    @Test
    public void testRandom16() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 7;
        final List<Pair<Integer, Integer>> maxLimits =
                List.of(Pair.create(WORK_TYPE_EJ, 5), Pair.create(WORK_TYPE_BG, 4),
                        Pair.create(WORK_TYPE_BGUSER_IMPORTANT, 1),
                        Pair.create(WORK_TYPE_BGUSER, 1));
        final List<Pair<Integer, Integer>> minLimits =
                List.of(Pair.create(WORK_TYPE_EJ, 3), Pair.create(WORK_TYPE_BG, 2));
        final double probStop = 0.4;
        final double[] cdf = buildWorkTypeCdf(0.01, 0.09, 0.25, 0.05, 0.3, 0.3);
        final double[] numTypesCdf = buildCdf(0.7, 0.3);
        final double probStart = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits, probStart,
                cdf, numTypesCdf, probStop);
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
            jobs.addPending(pend.first, pend.second);
        }

        recount(jobs, totalMax, minLimits, maxLimits);
        startPendingJobs(jobs);

        for (Pair<Integer, Integer> run : resultRunning) {
            assertWithMessage(
                    "Incorrect running result for work type " + workTypeToString(run.first))
                    .that(jobs.running.get(run.first)).isEqualTo(run.second);
        }
        for (Pair<Integer, Integer> pend : resultPending) {
            assertWithMessage(
                    "Incorrect pending result for work type " + workTypeToString(pend.first))
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

        Log.d(TAG, "START***#*#*#*#*#*#**#*");
        // Test multi-types
        checkSimple(6,
                /* min */ List.of(Pair.create(WORK_TYPE_EJ, 2), Pair.create(WORK_TYPE_BG, 2)),
                /* max */ List.of(
                        Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 1)),
                /* run */ List.of(),
                /* pen */ List.of(
                        // 2 of these as TOP, 1 as EJ
                        Pair.create(WORK_TYPE_TOP | WORK_TYPE_EJ, 3),
                        // 1 as EJ, 2 as BG
                        Pair.create(WORK_TYPE_EJ | WORK_TYPE_BG, 3),
                        Pair.create(WORK_TYPE_BG, 4),
                        Pair.create(WORK_TYPE_BGUSER, 1)),
                /* resRun */ List.of(Pair.create(WORK_TYPE_TOP, 2),
                        Pair.create(WORK_TYPE_EJ, 2), Pair.create(WORK_TYPE_BG, 2)),
                /* resPen */ List.of(
                        // Not checking BG count because the test starts jobs in random order
                        // and if it tries to start 4 BG jobs (2 will run as EJ from EJ|BG), but
                        // the resulting pending will be 3 BG instead of 4 BG.
                        Pair.create(WORK_TYPE_BGUSER, 1)));
    }

    /** Tests that the counter updates properly when jobs are stopped. */
    @Test
    public void testJobLifecycleLoop() {
        final Jobs jobs = new Jobs();
        jobs.addPending(WORK_TYPE_TOP, 11);
        jobs.addPending(WORK_TYPE_BG, 10);

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

    /** Tests that the counter updates properly when jobs are stopped. */
    @Test
    public void testJobLifecycleLoop_Multitype() {
        final Jobs jobs = new Jobs();
        jobs.addPending(WORK_TYPE_TOP, 6); // a
        jobs.addPending(WORK_TYPE_TOP | WORK_TYPE_EJ, 5); // b
        jobs.addPending(WORK_TYPE_BG, 10); // c

        final int totalMax = 8;
        final List<Pair<Integer, Integer>> minLimits =
                List.of(Pair.create(WORK_TYPE_EJ, 1), Pair.create(WORK_TYPE_BG, 1));
        final List<Pair<Integer, Integer>> maxLimits = List.of(Pair.create(WORK_TYPE_BG, 5));

        recount(jobs, totalMax, minLimits, maxLimits);

        assertThat(jobs.pending.get(WORK_TYPE_TOP)).isEqualTo(11);
        assertThat(jobs.pending.get(WORK_TYPE_EJ)).isEqualTo(5);
        assertThat(jobs.pending.get(WORK_TYPE_BG)).isEqualTo(10);

        startPendingJobs(jobs);

        assertThat(jobs.running.get(WORK_TYPE_TOP)).isEqualTo(6);
        assertThat(jobs.running.get(WORK_TYPE_EJ)).isEqualTo(1);
        assertThat(jobs.running.get(WORK_TYPE_BG)).isEqualTo(1);
        assertThat(jobs.pending.get(WORK_TYPE_TOP)).isEqualTo(4);
        // Since starting happens in random order, all EJs could have run first.
        assertThat(jobs.pending.get(WORK_TYPE_BG)).isEqualTo(9);

        // Stop all jobs
        jobs.maybeFinishJobs(1);

        assertThat(mWorkCountTracker.canJobStart(WORK_TYPE_TOP)).isEqualTo(WORK_TYPE_TOP);
        assertThat(mWorkCountTracker.canJobStart(WORK_TYPE_EJ)).isEqualTo(WORK_TYPE_EJ);
        assertThat(mWorkCountTracker.canJobStart(WORK_TYPE_BG)).isEqualTo(WORK_TYPE_BG);

        startPendingJobs(jobs);

        assertThat(jobs.running.get(WORK_TYPE_TOP) + jobs.running.get(WORK_TYPE_EJ)).isEqualTo(4);
        // Depending on the order jobs start, we may run all TOP/EJ combos as TOP and reserve a slot
        // for EJ, which would reduce BG count to 3 instead of 4.
        assertThat(jobs.running.get(WORK_TYPE_BG)).isAtLeast(3);
        assertThat(jobs.running.get(WORK_TYPE_BG)).isAtMost(4);
        assertThat(jobs.pending.get(WORK_TYPE_TOP)).isEqualTo(0);
        assertThat(jobs.pending.get(WORK_TYPE_EJ)).isEqualTo(0);
        assertThat(jobs.pending.get(WORK_TYPE_BG)).isAtLeast(5);
        assertThat(jobs.pending.get(WORK_TYPE_BG)).isAtMost(6);

        // Stop only a bg job and make sure the counter only allows another bg job to start.
        jobs.running.put(WORK_TYPE_BG, jobs.running.get(WORK_TYPE_BG) - 1);
        mWorkCountTracker.onJobFinished(WORK_TYPE_BG);

        assertThat(mWorkCountTracker.canJobStart(WORK_TYPE_TOP)).isEqualTo(WORK_TYPE_NONE);
        // Depending on the order jobs start, we may run all TOP/EJ combos as TOP and reserve a slot
        // for EJ, which would reduce BG count to 3 instead of 4.
        assertThat(mWorkCountTracker.canJobStart(WORK_TYPE_BG)).isEqualTo(WORK_TYPE_BG);

        startPendingJobs(jobs);

        assertThat(jobs.running.get(WORK_TYPE_TOP) + jobs.running.get(WORK_TYPE_EJ)).isEqualTo(4);
        assertThat(jobs.running.get(WORK_TYPE_BG)).isAtLeast(3);
        assertThat(jobs.running.get(WORK_TYPE_BG)).isAtMost(4);
        assertThat(jobs.pending.get(WORK_TYPE_TOP)).isEqualTo(0);
        assertThat(jobs.pending.get(WORK_TYPE_EJ)).isEqualTo(0);
        assertThat(jobs.pending.get(WORK_TYPE_BG)).isAtLeast(4);
        assertThat(jobs.pending.get(WORK_TYPE_BG)).isAtMost(5);
    }

    /** Tests that the counter updates properly when jobs are stopped. */
    @Test
    public void testJobLifecycleLoop_Multitype_RandomOrder() {
        final Jobs jobs = new Jobs();
        SparseIntArray multiToCount = new SparseIntArray();
        multiToCount.put(WORK_TYPE_TOP, 6); // a
        multiToCount.put(WORK_TYPE_TOP | WORK_TYPE_EJ, 5); // b
        multiToCount.put(WORK_TYPE_EJ | WORK_TYPE_BG, 5); // c
        multiToCount.put(WORK_TYPE_BG, 5); // d
        while (multiToCount.size() > 0) {
            final int index = mRandom.nextInt(multiToCount.size());
            final int count = multiToCount.valueAt(index);
            jobs.addPending(multiToCount.keyAt(index), 1);
            if (count <= 1) {
                multiToCount.removeAt(index);
            } else {
                multiToCount.put(multiToCount.keyAt(index), count - 1);
            }
        }

        final int totalMax = 8;
        final List<Pair<Integer, Integer>> minLimits =
                List.of(Pair.create(WORK_TYPE_EJ, 1), Pair.create(WORK_TYPE_BG, 1));
        final List<Pair<Integer, Integer>> maxLimits = List.of(Pair.create(WORK_TYPE_BG, 5));

        recount(jobs, totalMax, minLimits, maxLimits);

        assertThat(jobs.pending.get(WORK_TYPE_TOP)).isEqualTo(11);
        assertThat(jobs.pending.get(WORK_TYPE_EJ)).isEqualTo(10);
        assertThat(jobs.pending.get(WORK_TYPE_BG)).isEqualTo(10);

        startPendingJobs(jobs);

        // Random order, but we should have 6 TOP, 1 EJ, and 1 BG running.
        assertThat(jobs.running.get(WORK_TYPE_TOP)).isEqualTo(6);
        assertThat(jobs.running.get(WORK_TYPE_EJ)).isEqualTo(1);
        assertThat(jobs.running.get(WORK_TYPE_BG)).isEqualTo(1);
        // If run the TOP jobs as TOP first, and a TOP|EJ job as EJ, then we'll have 4 TOP jobs
        // remaining.
        assertThat(jobs.pending.get(WORK_TYPE_TOP)).isAtLeast(4);
        // If we end up running the TOP|EJ jobs as TOP first, then we'll have 5 TOP jobs remaining.
        assertThat(jobs.pending.get(WORK_TYPE_TOP)).isAtMost(5);
        // Can't equate pending EJ since some could be running as TOP and BG
        assertThat(jobs.pending.get(WORK_TYPE_EJ)).isAtLeast(2);
        assertThat(jobs.pending.get(WORK_TYPE_BG)).isAtLeast(8);
        assertThat(jobs.pending.get(WORK_TYPE_BG)).isAtMost(9);

        // Stop all jobs
        jobs.maybeFinishJobs(1);

        assertThat(mWorkCountTracker.canJobStart(WORK_TYPE_TOP)).isEqualTo(WORK_TYPE_TOP);
        assertThat(mWorkCountTracker.canJobStart(WORK_TYPE_EJ)).isEqualTo(WORK_TYPE_EJ);
        assertThat(mWorkCountTracker.canJobStart(WORK_TYPE_BG)).isEqualTo(WORK_TYPE_BG);

        startPendingJobs(jobs);

        // Random order, but we should have 4 TOP, 1 EJ, and 1 BG running.
        assertThat(jobs.running.get(WORK_TYPE_TOP)).isAtLeast(1);
        assertThat(jobs.running.get(WORK_TYPE_EJ)).isAtLeast(1);
        assertThat(jobs.running.get(WORK_TYPE_BG)).isEqualTo(1);
        // At this point, all TOP should be running (or have already run).
        assertThat(jobs.pending.get(WORK_TYPE_TOP)).isEqualTo(0);
        assertThat(jobs.pending.get(WORK_TYPE_EJ)).isAtLeast(2);
        assertThat(jobs.pending.get(WORK_TYPE_BG)).isEqualTo(5);

        // Stop only a bg job and make sure the counter only allows another bg job to start.
        jobs.running.put(WORK_TYPE_BG, jobs.running.get(WORK_TYPE_BG) - 1);
        mWorkCountTracker.onJobFinished(WORK_TYPE_BG);

        assertThat(mWorkCountTracker.canJobStart(WORK_TYPE_TOP)).isEqualTo(WORK_TYPE_NONE);
        assertThat(mWorkCountTracker.canJobStart(WORK_TYPE_EJ)).isEqualTo(WORK_TYPE_NONE);
        assertThat(mWorkCountTracker.canJobStart(WORK_TYPE_BG)).isEqualTo(WORK_TYPE_BG);

        startPendingJobs(jobs);

        assertThat(jobs.running.get(WORK_TYPE_TOP)).isAtLeast(1);
        assertThat(jobs.running.get(WORK_TYPE_EJ)).isAtLeast(1);
        assertThat(jobs.running.get(WORK_TYPE_BG)).isEqualTo(1);
        assertThat(jobs.pending.get(WORK_TYPE_TOP)).isEqualTo(0);
        assertThat(jobs.pending.get(WORK_TYPE_EJ)).isAtLeast(1);
        assertThat(jobs.pending.get(WORK_TYPE_BG)).isEqualTo(4);
    }
}
