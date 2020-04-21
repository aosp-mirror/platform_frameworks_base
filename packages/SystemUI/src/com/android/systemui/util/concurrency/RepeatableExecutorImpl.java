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

package com.android.systemui.util.concurrency;

import java.util.concurrent.TimeUnit;

/**
 * Implementation of {@link RepeatableExecutor} for SystemUI.
 */
class RepeatableExecutorImpl implements RepeatableExecutor {

    private final DelayableExecutor mExecutor;

    RepeatableExecutorImpl(DelayableExecutor executor) {
        mExecutor = executor;
    }

    @Override
    public void execute(Runnable command) {
        mExecutor.execute(command);
    }

    @Override
    public Runnable executeRepeatedly(Runnable r, long initDelay, long delay, TimeUnit unit) {
        ExecutionToken token = new ExecutionToken(r, delay, unit);
        token.start(initDelay, unit);
        return token::cancel;
    }

    private class ExecutionToken implements Runnable {
        private final Runnable mCommand;
        private final long mDelay;
        private final TimeUnit mUnit;
        private final Object mLock = new Object();
        private Runnable mCancel;

        ExecutionToken(Runnable r, long delay, TimeUnit unit) {
            mCommand = r;
            mDelay = delay;
            mUnit = unit;
        }

        @Override
        public void run() {
            mCommand.run();
            synchronized (mLock) {
                if (mCancel != null) {
                    mCancel = mExecutor.executeDelayed(this, mDelay, mUnit);
                }
            }
        }

        /** Starts execution that will repeat the command until {@link cancel}. */
        public void start(long startDelay, TimeUnit unit) {
            synchronized (mLock) {
                mCancel = mExecutor.executeDelayed(this, startDelay, unit);
            }
        }

        /** Cancel repeated execution of command. */
        public void cancel() {
            synchronized (mLock) {
                if (mCancel != null) {
                    mCancel.run();
                    mCancel = null;
                }
            }
        }
    }
}
