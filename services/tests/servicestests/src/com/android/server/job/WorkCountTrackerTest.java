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
import static com.android.server.job.JobConcurrencyManager.WORK_TYPE_BGUSER;
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
        public final SparseIntArray running = new SparseIntArray();
        public final SparseIntArray pending = new SparseIntArray();

        public void maybeEnqueueJobs(double startRatio, double fgJobRatio, double fgUserJobRatio) {
            // fgUserJobRatio should always be at least fgJobRatio, otherwise no WORK_TYPE_BG will
            // be enqueued.
            while (mRandom.nextDouble() < startRatio) {
                final double random = mRandom.nextDouble();
                if (random < fgJobRatio) {
                    pending.put(WORK_TYPE_TOP, pending.get(WORK_TYPE_TOP) + 1);
                } else if (random < fgUserJobRatio) {
                    pending.put(WORK_TYPE_BG, pending.get(WORK_TYPE_BG) + 1);
                } else {
                    pending.put(WORK_TYPE_BGUSER, pending.get(WORK_TYPE_BGUSER) + 1);
                }
            }
        }

        public void maybeFinishJobs(double stopRatio) {
            for (int i = running.get(WORK_TYPE_BGUSER); i > 0; i--) {
                if (mRandom.nextDouble() < stopRatio) {
                    running.put(WORK_TYPE_BGUSER, running.get(WORK_TYPE_BGUSER) - 1);
                }
            }
            for (int i = running.get(WORK_TYPE_BG); i > 0; i--) {
                if (mRandom.nextDouble() < stopRatio) {
                    running.put(WORK_TYPE_BG, running.get(WORK_TYPE_BG) - 1);
                    mWorkCountTracker.onJobFinished(WORK_TYPE_BG);
                }
            }
            for (int i = running.get(WORK_TYPE_TOP); i > 0; i--) {
                if (mRandom.nextDouble() < stopRatio) {
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

    private void startPendingJobs(Jobs jobs) {
        while ((jobs.pending.get(WORK_TYPE_TOP) > 0
                && mWorkCountTracker.canJobStart(WORK_TYPE_TOP) != WORK_TYPE_NONE)
                || (jobs.pending.get(WORK_TYPE_BG) > 0
                && mWorkCountTracker.canJobStart(WORK_TYPE_BG) != WORK_TYPE_NONE)
                || (jobs.pending.get(WORK_TYPE_BGUSER) > 0
                && mWorkCountTracker.canJobStart(WORK_TYPE_BGUSER) != WORK_TYPE_NONE)) {
            final boolean isStartingFg = mRandom.nextBoolean();
            final boolean isStartingFgUser = mRandom.nextBoolean();

            if (isStartingFg) {
                if (jobs.pending.get(WORK_TYPE_TOP) > 0
                        && mWorkCountTracker.canJobStart(WORK_TYPE_TOP) != WORK_TYPE_NONE) {
                    jobs.pending.put(WORK_TYPE_TOP, jobs.pending.get(WORK_TYPE_TOP) - 1);
                    jobs.running.put(WORK_TYPE_TOP, jobs.running.get(WORK_TYPE_TOP) + 1);
                    mWorkCountTracker.stageJob(WORK_TYPE_TOP);
                    mWorkCountTracker.onJobStarted(WORK_TYPE_TOP);
                }
            } else if (isStartingFgUser) {
                if (jobs.pending.get(WORK_TYPE_BG) > 0
                        && mWorkCountTracker.canJobStart(WORK_TYPE_BG) != WORK_TYPE_NONE) {
                    jobs.pending.put(WORK_TYPE_BG, jobs.pending.get(WORK_TYPE_BG) - 1);
                    jobs.running.put(WORK_TYPE_BG, jobs.running.get(WORK_TYPE_BG) + 1);
                    mWorkCountTracker.stageJob(WORK_TYPE_BG);
                    mWorkCountTracker.onJobStarted(WORK_TYPE_BG);
                }
            } else {
                if (jobs.pending.get(WORK_TYPE_BGUSER) > 0
                        && mWorkCountTracker.canJobStart(WORK_TYPE_BGUSER) != WORK_TYPE_NONE) {
                    jobs.pending.put(WORK_TYPE_BGUSER, jobs.pending.get(WORK_TYPE_BGUSER) - 1);
                    jobs.running.put(WORK_TYPE_BGUSER, jobs.running.get(WORK_TYPE_BGUSER) + 1);
                    mWorkCountTracker.stageJob(WORK_TYPE_BGUSER);
                    mWorkCountTracker.onJobStarted(WORK_TYPE_BGUSER);
                }
            }
        }
    }

    /**
     * Used by the following testRandom* tests.
     */
    private void checkRandom(Jobs jobs, int numTests, int totalMax,
            @NonNull List<Pair<Integer, Integer>> minLimits,
            @NonNull List<Pair<Integer, Integer>> maxLimits,
            double startRatio, double fgJobRatio, double fgUserJobRatio, double stopRatio) {
        for (int i = 0; i < numTests; i++) {
            jobs.maybeFinishJobs(stopRatio);
            jobs.maybeEnqueueJobs(startRatio, fgJobRatio, fgUserJobRatio);

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
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 6;
        final List<Pair<Integer, Integer>> maxLimits =
                List.of(Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 2));
        final List<Pair<Integer, Integer>> minLimits =
                List.of(Pair.create(WORK_TYPE_BG, 2), Pair.create(WORK_TYPE_BGUSER, 1));
        final double stopRatio = 0.1;
        // WorkType probabilities:
        // WORK_TYPE_TOP -- 50%
        // WORK_TYPE_BG -- 50%
        // WORK_TYPE_BGUSER -- 0%
        final double fgJobRatio = 0.5;
        final double fgUserJobRatio = 1;
        final double startRatio = 0.1;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits,
                startRatio, fgJobRatio, fgUserJobRatio, stopRatio);
    }

    @Test
    public void testRandom2() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 2;
        final List<Pair<Integer, Integer>> maxLimits =
                List.of(Pair.create(WORK_TYPE_BG, 2), Pair.create(WORK_TYPE_BGUSER, 1));
        final List<Pair<Integer, Integer>> minLimits = List.of();
        final double stopRatio = 0.5;
        // WorkType probabilities:
        // WORK_TYPE_TOP -- 50%
        // WORK_TYPE_BG -- 50%
        // WORK_TYPE_BGUSER -- 0%
        final double fgJobRatio = 0.5;
        final double fgUserJobRatio = 1;
        final double startRatio = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits,
                startRatio, fgJobRatio, fgUserJobRatio, stopRatio);
    }

    @Test
    public void testRandom3() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 2;
        final List<Pair<Integer, Integer>> maxLimits =
                List.of(Pair.create(WORK_TYPE_BG, 2), Pair.create(WORK_TYPE_BGUSER, 1));
        final List<Pair<Integer, Integer>> minLimits = List.of(Pair.create(WORK_TYPE_BG, 2));
        final double stopRatio = 0.5;
        // WorkType probabilities:
        // WORK_TYPE_TOP -- 33%
        // WORK_TYPE_BG -- 33%
        // WORK_TYPE_BGUSER -- 33%
        final double fgJobRatio = 1 / 3.0;
        final double fgUserJobRatio = 2 / 3.0;
        final double startRatio = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits,
                startRatio, fgJobRatio, fgUserJobRatio, stopRatio);
    }

    @Test
    public void testRandom4() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 10;
        final List<Pair<Integer, Integer>> maxLimits =
                List.of(Pair.create(WORK_TYPE_BG, 2), Pair.create(WORK_TYPE_BGUSER, 1));
        final List<Pair<Integer, Integer>> minLimits = List.of();
        final double stopRatio = 0.5;
        // WorkType probabilities:
        // WORK_TYPE_TOP -- 33%
        // WORK_TYPE_BG -- 33%
        // WORK_TYPE_BGUSER -- 33%
        final double fgJobRatio = 1 / 3.0;
        final double fgUserJobRatio = 2 / 3.0;
        final double startRatio = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits,
                startRatio, fgJobRatio, fgUserJobRatio, stopRatio);
    }

    @Test
    public void testRandom5() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 6;
        final List<Pair<Integer, Integer>> maxLimits =
                List.of(Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 2));
        final List<Pair<Integer, Integer>> minLimits = List.of(Pair.create(WORK_TYPE_BG, 2));
        final double stopRatio = 0.5;
        // WorkType probabilities:
        // WORK_TYPE_TOP -- 10%
        // WORK_TYPE_BG -- 80%
        // WORK_TYPE_BGUSER -- 10%
        final double fgJobRatio = 0.1;
        final double fgUserJobRatio = 0.9;
        final double startRatio = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits,
                startRatio, fgJobRatio, fgUserJobRatio, stopRatio);
    }

    @Test
    public void testRandom6() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 6;
        final List<Pair<Integer, Integer>> maxLimits =
                List.of(Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 2));
        final List<Pair<Integer, Integer>> minLimits = List.of(Pair.create(WORK_TYPE_BG, 2));
        final double stopRatio = 0.5;
        // WorkType probabilities:
        // WORK_TYPE_TOP -- 90%
        // WORK_TYPE_BG -- 10%
        // WORK_TYPE_BGUSER -- 0%
        final double fgJobRatio = 0.9;
        final double fgUserJobRatio = 1;
        final double startRatio = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits,
                startRatio, fgJobRatio, fgUserJobRatio, stopRatio);
    }

    @Test
    public void testRandom7() {
        final Jobs jobs = new Jobs();

        final int numTests = 5000;
        final int totalMax = 6;
        final List<Pair<Integer, Integer>> maxLimits =
                List.of(Pair.create(WORK_TYPE_BG, 4), Pair.create(WORK_TYPE_BGUSER, 2));
        final List<Pair<Integer, Integer>> minLimits = List.of(Pair.create(WORK_TYPE_BG, 2));
        final double stopRatio = 0.4;
        // WorkType probabilities:
        // WORK_TYPE_TOP -- 10%
        // WORK_TYPE_BG -- 10%
        // WORK_TYPE_BGUSER -- 80%
        final double fgJobRatio = 0.1;
        final double fgUserJobRatio = 0.2;
        final double startRatio = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits,
                startRatio, fgJobRatio, fgUserJobRatio, stopRatio);
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
        final double stopRatio = 0.4;
        // WorkType probabilities:
        // WORK_TYPE_TOP -- 90%
        // WORK_TYPE_BG -- 5%
        // WORK_TYPE_BGUSER -- 5%
        final double fgJobRatio = 0.9;
        final double fgUserJobRatio = 0.95;
        final double startRatio = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits,
                startRatio, fgJobRatio, fgUserJobRatio, stopRatio);
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
        final double stopRatio = 0.5;
        // WorkType probabilities:
        // WORK_TYPE_TOP -- 0%
        // WORK_TYPE_BG -- 50%
        // WORK_TYPE_BGUSER -- 50%
        final double fgJobRatio = 0;
        final double fgUserJobRatio = 0.5;
        final double startRatio = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits,
                startRatio, fgJobRatio, fgUserJobRatio, stopRatio);
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
        final double stopRatio = 0.5;
        // WorkType probabilities:
        // WORK_TYPE_TOP -- 0%
        // WORK_TYPE_BG -- 10%
        // WORK_TYPE_BGUSER -- 90%
        final double fgJobRatio = 0;
        final double fgUserJobRatio = 0.1;
        final double startRatio = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits,
                startRatio, fgJobRatio, fgUserJobRatio, stopRatio);
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
        final double stopRatio = 0.5;
        // WorkType probabilities:
        // WORK_TYPE_TOP -- 0%
        // WORK_TYPE_BG -- 90%
        // WORK_TYPE_BGUSER -- 10%
        final double fgJobRatio = 0;
        final double fgUserJobRatio = 0.9;
        final double startRatio = 0.5;

        checkRandom(jobs, numTests, totalMax, minLimits, maxLimits,
                startRatio, fgJobRatio, fgUserJobRatio, stopRatio);
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
