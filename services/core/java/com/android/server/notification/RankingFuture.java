/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.server.notification;

import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public abstract class RankingFuture
        implements ScheduledFuture<NotificationManagerService.NotificationRecord> {
    private static final long IMMEDIATE = 0l;

    private static final int START = 0;
    private static final int RUNNING = 1;
    private static final int DONE = 2;
    private static final int CANCELLED = 3;

    private int mState;
    private long mDelay;
    protected NotificationManagerService.NotificationRecord mRecord;

    public RankingFuture(NotificationManagerService.NotificationRecord record) {
        this(record, IMMEDIATE);
    }

    public RankingFuture(NotificationManagerService.NotificationRecord record, long delay) {
        mDelay = delay;
        mRecord = record;
        mState = START;
    }

    public void run() {
        if (mState == START) {
            mState = RUNNING;

            work();

            mState = DONE;
            synchronized (this) {
                notifyAll();
            }
        }
    }

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(mDelay, TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed another) {
        return Long.compare(getDelay(TimeUnit.MILLISECONDS),
                another.getDelay(TimeUnit.MILLISECONDS));
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (mState == START) {  // can't cancel if running or done
            mState = CANCELLED;
            return true;
        }
        return false;
    }

    @Override
    public boolean isCancelled() {
        return mState == CANCELLED;
    }

    @Override
    public boolean isDone() {
        return mState == DONE;
    }

    @Override
    public NotificationManagerService.NotificationRecord get()
            throws InterruptedException, ExecutionException {
        while (!isDone()) {
            synchronized (this) {
                this.wait();
            }
        }
        return mRecord;
    }

    @Override
    public NotificationManagerService.NotificationRecord get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        long timeoutMillis = unit.convert(timeout, TimeUnit.MILLISECONDS);
        long start = System.currentTimeMillis();
        long now = System.currentTimeMillis();
        while (!isDone() && (now - start) < timeoutMillis) {
            try {
                wait(timeoutMillis - (now - start));
            } catch (InterruptedException e) {
                now = System.currentTimeMillis();
            }
        }
        return mRecord;
    }

    public abstract void work();
}
