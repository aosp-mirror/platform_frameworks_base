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

package com.android.server.backup.encryption.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ExecutorService which needs to be stepped through the jobs in its' queue.
 *
 * <p>This is a deliberately simple implementation because it's only used in testing. The queued
 * jobs are run on the main thread to eliminate any race condition bugs.
 */
public class QueuingNonAutomaticExecutorService extends AbstractExecutorService {

    private List<Runnable> mWaitingJobs = new ArrayList<>();
    private int mWaitingJobCount = 0;

    @Override
    public void shutdown() {
        mWaitingJobCount = mWaitingJobs.size();
        mWaitingJobs = null; // This will force an error if jobs are submitted after shutdown
    }

    @Override
    public List<Runnable> shutdownNow() {
        List<Runnable> queuedJobs = mWaitingJobs;
        shutdown();
        return queuedJobs;
    }

    @Override
    public boolean isShutdown() {
        return mWaitingJobs == null;
    }

    @Override
    public boolean isTerminated() {
        return mWaitingJobs == null && mWaitingJobCount == 0;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long expiry = System.currentTimeMillis() + unit.toMillis(timeout);
        for (Runnable job : mWaitingJobs) {
            if (System.currentTimeMillis() > expiry) {
                return false;
            }

            job.run();
        }
        return true;
    }

    @Override
    public void execute(Runnable command) {
        mWaitingJobs.add(command);
    }

    public void runNext() {
        if (mWaitingJobs.isEmpty()) {
            throw new IllegalStateException("Attempted to run jobs on an empty paused executor");
        }

        mWaitingJobs.remove(0).run();
    }
}
