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

import java.util.concurrent.TimeUnit;

/**
 * Represents future work required to extract signals from notifications for ranking.
 *
 * {@hide}
 */
public abstract class RankingReconsideration implements Runnable {
    private static final long IMMEDIATE = 0l;

    private static final int START = 0;
    private static final int RUNNING = 1;
    private static final int DONE = 2;
    private static final int CANCELLED = 3;

    private int mState;
    private long mDelay;
    protected String mKey;

    public RankingReconsideration(String key) {
        this(key, IMMEDIATE);
    }

    public RankingReconsideration(String key, long delay) {
        mDelay = delay;
        mKey = key;
        mState = START;
    }

    public String getKey() {
        return mKey;
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

    public long getDelay(TimeUnit unit) {
        return unit.convert(mDelay, TimeUnit.MILLISECONDS);
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        if (mState == START) {  // can't cancel if running or done
            mState = CANCELLED;
            return true;
        }
        return false;
    }

    public boolean isCancelled() {
        return mState == CANCELLED;
    }

    public boolean isDone() {
        return mState == DONE;
    }

    /**
     * Analyse the notification.  This will be called on a worker thread. To
     * avoid concurrency issues, do not use held references to modify the
     * {@link NotificationRecord}.
     */
    public abstract void work();

    /**
     * Apply any computed changes to the notification record.  This method will be
     * called on the main service thread, synchronized on the mNotificationList.
     * @param record The locked record to be updated.
     */
    public abstract void applyChangesLocked(NotificationRecord record);
}
