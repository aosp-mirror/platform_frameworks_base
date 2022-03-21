/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Pools;
import android.util.SparseArray;

import com.android.server.job.controllers.JobStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A utility class to maintain a sorted list of currently pending jobs. The sorting system is
 * modeled after topological sort, so the returned order may not always be consistent.
 */
class PendingJobQueue {
    private final Pools.Pool<AppJobQueue> mAppJobQueuePool = new Pools.SimplePool<>(8);

    /** Set of currently used queues, keyed by source UID. */
    private final SparseArray<AppJobQueue> mCurrentQueues = new SparseArray<>();
    /**
     * Same set of AppJobQueues as in {@link #mCurrentQueues}, but ordered by the next timestamp
     * to make iterating through the job list faster.
     */
    private final PriorityQueue<AppJobQueue> mOrderedQueues = new PriorityQueue<>(
            (ajq1, ajq2) -> {
                final long t1 = ajq1.peekNextTimestamp();
                final long t2 = ajq2.peekNextTimestamp();
                if (t1 == AppJobQueue.NO_NEXT_TIMESTAMP) {
                    if (t2 == AppJobQueue.NO_NEXT_TIMESTAMP) {
                        return 0;
                    }
                    return 1;
                } else if (t2 == AppJobQueue.NO_NEXT_TIMESTAMP) {
                    return -1;
                }
                return Long.compare(t1, t2);
            });

    private int mSize = 0;

    private boolean mNeedToResetIterators = false;

    void add(@NonNull JobStatus job) {
        final AppJobQueue ajq = getAppJobQueue(job.getSourceUid(), true);
        final long prevTimestamp = ajq.peekNextTimestamp();
        ajq.add(job);
        mSize++;
        if (prevTimestamp != ajq.peekNextTimestamp()) {
            mOrderedQueues.remove(ajq);
            mOrderedQueues.offer(ajq);
        }
    }

    void addAll(@NonNull List<JobStatus> jobs) {
        final SparseArray<List<JobStatus>> jobsByUid = new SparseArray<>();
        for (int i = jobs.size() - 1; i >= 0; --i) {
            final JobStatus job = jobs.get(i);
            List<JobStatus> appJobs = jobsByUid.get(job.getSourceUid());
            if (appJobs == null) {
                appJobs = new ArrayList<>();
                jobsByUid.put(job.getSourceUid(), appJobs);
            }
            appJobs.add(job);
        }
        for (int i = jobsByUid.size() - 1; i >= 0; --i) {
            final AppJobQueue ajq = getAppJobQueue(jobsByUid.keyAt(i), true);
            ajq.addAll(jobsByUid.valueAt(i));
        }
        mSize += jobs.size();
        mOrderedQueues.clear();
    }

    void clear() {
        mSize = 0;
        for (int i = mCurrentQueues.size() - 1; i >= 0; --i) {
            final AppJobQueue ajq = mCurrentQueues.valueAt(i);
            ajq.clear();
            mAppJobQueuePool.release(ajq);
        }
        mCurrentQueues.clear();
        mOrderedQueues.clear();
    }

    boolean contains(@NonNull JobStatus job) {
        final AppJobQueue ajq = mCurrentQueues.get(job.getSourceUid());
        if (ajq == null) {
            return false;
        }
        return ajq.contains(job);
    }

    private AppJobQueue getAppJobQueue(int uid, boolean create) {
        AppJobQueue ajq = mCurrentQueues.get(uid);
        if (ajq == null && create) {
            ajq = mAppJobQueuePool.acquire();
            if (ajq == null) {
                ajq = new AppJobQueue();
            }
            mCurrentQueues.put(uid, ajq);
        }
        return ajq;
    }

    @Nullable
    JobStatus next() {
        if (mNeedToResetIterators) {
            mOrderedQueues.clear();
            for (int i = mCurrentQueues.size() - 1; i >= 0; --i) {
                final AppJobQueue ajq = mCurrentQueues.valueAt(i);
                ajq.resetIterator(0);
                mOrderedQueues.offer(ajq);
            }
            mNeedToResetIterators = false;
        } else if (mOrderedQueues.size() == 0) {
            for (int i = mCurrentQueues.size() - 1; i >= 0; --i) {
                final AppJobQueue ajq = mCurrentQueues.valueAt(i);
                mOrderedQueues.offer(ajq);
            }
        }
        final AppJobQueue earliestQueue = mOrderedQueues.poll();
        if (earliestQueue != null) {
            JobStatus job = earliestQueue.next();
            mOrderedQueues.offer(earliestQueue);
            return job;
        }
        return null;
    }

    boolean remove(@NonNull JobStatus job) {
        final AppJobQueue ajq = getAppJobQueue(job.getSourceUid(), false);
        if (ajq == null) {
            return false;
        }

        final long prevTimestamp = ajq.peekNextTimestamp();
        if (!ajq.remove(job)) {
            return false;
        }

        mSize--;
        if (ajq.size() == 0) {
            mCurrentQueues.remove(job.getSourceUid());
            mOrderedQueues.remove(ajq);
            ajq.clear();
            mAppJobQueuePool.release(ajq);
        } else if (prevTimestamp != ajq.peekNextTimestamp()) {
            mOrderedQueues.remove(ajq);
            mOrderedQueues.offer(ajq);
        }

        return true;
    }

    /** Resets the iterating index to the front of the queue. */
    void resetIterator() {
        // Lazily reset the iterating indices (avoid looping through all the current queues until
        // absolutely necessary).
        mNeedToResetIterators = true;
    }

    int size() {
        return mSize;
    }

    private static final class AppJobQueue {
        static final long NO_NEXT_TIMESTAMP = -1L;

        private static class AdjustedJobStatus {
            public long adjustedEnqueueTime;
            public JobStatus job;

            void clear() {
                adjustedEnqueueTime = 0;
                job = null;
            }
        }

        private static final Comparator<AdjustedJobStatus> sJobComparator = (aj1, aj2) -> {
            if (aj1 == aj2) {
                return 0;
            }
            final JobStatus job1 = aj1.job;
            final JobStatus job2 = aj2.job;
            // Jobs with an override state set (via adb) should be put first as tests/developers
            // expect the jobs to run immediately.
            if (job1.overrideState != job2.overrideState) {
                // Higher override state (OVERRIDE_FULL) should be before lower state
                // (OVERRIDE_SOFT)
                return job2.overrideState - job1.overrideState;
            }

            final boolean job1EJ = job1.isRequestedExpeditedJob();
            final boolean job2EJ = job2.isRequestedExpeditedJob();
            if (job1EJ != job2EJ) {
                // Attempt to run requested expedited jobs ahead of regular jobs, regardless of
                // expedited job quota.
                return job1EJ ? -1 : 1;
            }

            final int job1Priority = job1.getEffectivePriority();
            final int job2Priority = job2.getEffectivePriority();
            if (job1Priority != job2Priority) {
                // Use the priority set by an app for intra-app job ordering. Higher
                // priority should be before lower priority.
                return job2Priority - job1Priority;
            }

            if (job1.lastEvaluatedBias != job2.lastEvaluatedBias) {
                // Higher bias should go first.
                return job2.lastEvaluatedBias - job1.lastEvaluatedBias;
            }

            if (job1.enqueueTime < job2.enqueueTime) {
                return -1;
            }
            return job1.enqueueTime > job2.enqueueTime ? 1 : 0;
        };

        private static final Pools.Pool<AdjustedJobStatus> mAdjustedJobStatusPool =
                new Pools.SimplePool<>(16);

        private final List<AdjustedJobStatus> mJobs = new ArrayList<>();
        private int mCurIndex = 0;

        void add(@NonNull JobStatus jobStatus) {
            AdjustedJobStatus adjustedJobStatus = mAdjustedJobStatusPool.acquire();
            if (adjustedJobStatus == null) {
                adjustedJobStatus = new AdjustedJobStatus();
            }
            adjustedJobStatus.adjustedEnqueueTime = jobStatus.enqueueTime;
            adjustedJobStatus.job = jobStatus;

            int where = Collections.binarySearch(mJobs, adjustedJobStatus, sJobComparator);
            if (where < 0) {
                where = ~where;
            }
            mJobs.add(where, adjustedJobStatus);
            if (where < mCurIndex) {
                // Shift the current index back to make sure the new job is evaluated on the next
                // iteration.
                mCurIndex = where;
            }

            if (where > 0) {
                final long prevTimestamp = mJobs.get(where - 1).adjustedEnqueueTime;
                adjustedJobStatus.adjustedEnqueueTime =
                        Math.max(prevTimestamp, adjustedJobStatus.adjustedEnqueueTime);
            }
            final int numJobs = mJobs.size();
            if (where < numJobs - 1) {
                // Potentially need to adjust following job timestamps as well.
                for (int i = where; i < numJobs; ++i) {
                    final AdjustedJobStatus ajs = mJobs.get(i);
                    if (adjustedJobStatus.adjustedEnqueueTime < ajs.adjustedEnqueueTime) {
                        // No further need to adjust.
                        break;
                    }
                    ajs.adjustedEnqueueTime = adjustedJobStatus.adjustedEnqueueTime;
                }
            }
        }

        void addAll(@NonNull List<JobStatus> jobs) {
            int earliestIndex = Integer.MAX_VALUE;

            for (int i = jobs.size() - 1; i >= 0; --i) {
                final JobStatus job = jobs.get(i);

                AdjustedJobStatus adjustedJobStatus = mAdjustedJobStatusPool.acquire();
                if (adjustedJobStatus == null) {
                    adjustedJobStatus = new AdjustedJobStatus();
                }
                adjustedJobStatus.adjustedEnqueueTime = job.enqueueTime;
                adjustedJobStatus.job = job;

                int where = Collections.binarySearch(mJobs, adjustedJobStatus, sJobComparator);
                if (where < 0) {
                    where = ~where;
                }
                mJobs.add(where, adjustedJobStatus);
                if (where < mCurIndex) {
                    // Shift the current index back to make sure the new job is evaluated on the
                    // next iteration.
                    mCurIndex = where;
                }
                earliestIndex = Math.min(earliestIndex, where);
            }

            final int numJobs = mJobs.size();
            for (int i = Math.max(earliestIndex, 1); i < numJobs; ++i) {
                final AdjustedJobStatus ajs = mJobs.get(i);
                final AdjustedJobStatus prev = mJobs.get(i - 1);
                ajs.adjustedEnqueueTime =
                        Math.max(ajs.adjustedEnqueueTime, prev.adjustedEnqueueTime);
            }
        }

        void clear() {
            mJobs.clear();
            mCurIndex = 0;
        }

        boolean contains(@NonNull JobStatus job) {
            return indexOf(job) >= 0;
        }

        private int indexOf(@NonNull JobStatus jobStatus) {
            AdjustedJobStatus adjustedJobStatus = mAdjustedJobStatusPool.acquire();
            if (adjustedJobStatus == null) {
                adjustedJobStatus = new AdjustedJobStatus();
            }
            adjustedJobStatus.adjustedEnqueueTime = jobStatus.enqueueTime;
            adjustedJobStatus.job = jobStatus;

            int where = Collections.binarySearch(mJobs, adjustedJobStatus, sJobComparator);
            adjustedJobStatus.clear();
            mAdjustedJobStatusPool.release(adjustedJobStatus);
            return where;
        }

        @Nullable
        JobStatus next() {
            if (mCurIndex >= mJobs.size()) {
                return null;
            }
            JobStatus next = mJobs.get(mCurIndex).job;
            mCurIndex++;
            return next;
        }

        long peekNextTimestamp() {
            if (mCurIndex >= mJobs.size()) {
                return NO_NEXT_TIMESTAMP;
            }
            return mJobs.get(mCurIndex).adjustedEnqueueTime;
        }

        boolean remove(@NonNull JobStatus jobStatus) {
            final int idx = indexOf(jobStatus);
            if (idx < 0) {
                // Doesn't exist...
                return false;
            }
            final AdjustedJobStatus adjustedJobStatus = mJobs.remove(idx);
            adjustedJobStatus.clear();
            mAdjustedJobStatusPool.release(adjustedJobStatus);
            if (idx < mCurIndex) {
                mCurIndex--;
            }
            return true;
        }

        /**
         * Resets the internal index to point to the first JobStatus whose adjusted time is equal to
         * or after the given timestamp.
         */
        void resetIterator(long earliestEnqueueTime) {
            if (earliestEnqueueTime == 0 || mJobs.size() == 0) {
                mCurIndex = 0;
                return;
            }

            // Binary search
            int low = 0;
            int high = mJobs.size() - 1;

            while (low < high) {
                int mid = (low + high) >>> 1;
                AdjustedJobStatus midVal = mJobs.get(mid);

                if (midVal.adjustedEnqueueTime < earliestEnqueueTime) {
                    low = mid + 1;
                } else if (midVal.adjustedEnqueueTime > earliestEnqueueTime) {
                    high = mid - 1;
                } else {
                    high = mid;
                }
            }
            mCurIndex = high;
        }

        int size() {
            return mJobs.size();
        }
    }
}
