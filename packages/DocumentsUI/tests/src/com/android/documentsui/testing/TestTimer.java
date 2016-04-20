/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui.testing;

import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Timer;
import java.util.TimerTask;

/**
 * A {@link Timer} for testing that can dial its clock hands to any future time.
 */
public class TestTimer extends Timer {

    private long mNow = 0;

    private final LinkedList<Task> mTaskList = new LinkedList<>();

    public void fastForwardTo(long time) {
        if (time < mNow) {
            throw new IllegalArgumentException("Can't fast forward to past.");
        }

        mNow = time;
        while (!mTaskList.isEmpty() && mTaskList.getFirst().mExecuteTime <= mNow) {
            Task task = mTaskList.getFirst();
            if (!task.isCancelled()) {
                task.run();
            }
            mTaskList.removeFirst();
        }
    }

    @Override
    public void cancel() {
        mTaskList.clear();
    }

    @Override
    public int purge() {
        int count = 0;
        Iterator<Task> iter = mTaskList.iterator();
        while (iter.hasNext()) {
            Task task = iter.next();
            if (task.isCancelled()) {
                iter.remove();
                ++count;
            }
        }
        return count;
    }

    @Override
    public void schedule(TimerTask task, Date time) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void schedule(TimerTask task, Date firstTime, long period) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void schedule(TimerTask task, long delay) {
        long executeTime = mNow + delay;
        Task testTimerTask = (Task) task;
        testTimerTask.mExecuteTime = executeTime;

        ListIterator<Task> iter = mTaskList.listIterator(0);
        while (iter.hasNext()) {
            if (iter.next().mExecuteTime >= executeTime) {
                break;
            }
        }
        iter.add(testTimerTask);
    }

    @Override
    public void schedule(TimerTask task, long delay, long period) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void scheduleAtFixedRate(TimerTask task, Date firstTime, long period) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void scheduleAtFixedRate(TimerTask task, long delay, long period) {
        throw new UnsupportedOperationException();
    }

    public static class Task extends TimerTask {
        private boolean mIsCancelled;
        private long mExecuteTime;

        private TimerTask mDelegate;

        public Task(TimerTask delegate) {
            mDelegate = delegate;
        }

        @Override
        public boolean cancel() {
            mIsCancelled = true;
            return mDelegate.cancel();
        }

        @Override
        public void run() {
            mDelegate.run();
        }

        boolean isCancelled() {
            return mIsCancelled;
        }
    }
}
