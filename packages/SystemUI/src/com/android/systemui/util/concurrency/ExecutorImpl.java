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

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Implementations of {@link DelayableExecutor} for SystemUI.
 */
public class ExecutorImpl implements DelayableExecutor {
    private final Handler mHandler;

    ExecutorImpl(Looper looper) {
        mHandler = new Handler(looper, this::onHandleMessage);
    }

    @Override
    public void execute(Runnable command) {
        if (!mHandler.post(command)) {
            throw new RejectedExecutionException(mHandler + " is shutting down");
        }
    }

    @Override
    public Runnable executeDelayed(Runnable r, long delay, TimeUnit unit) {
        ExecutionToken token = new ExecutionToken(r);
        Message m = mHandler.obtainMessage(MSG_EXECUTE_RUNNABLE, token);
        mHandler.sendMessageDelayed(m, unit.toMillis(delay));

        return token;
    }

    @Override
    public Runnable executeAtTime(Runnable r, long uptimeMillis, TimeUnit unit) {
        ExecutionToken token = new ExecutionToken(r);
        Message m = mHandler.obtainMessage(MSG_EXECUTE_RUNNABLE, token);
        mHandler.sendMessageAtTime(m, unit.toMillis(uptimeMillis));

        return token;
    }

    private boolean onHandleMessage(Message msg) {
        if (msg.what == MSG_EXECUTE_RUNNABLE) {
            ExecutionToken token = (ExecutionToken) msg.obj;
            token.runnable.run();
        } else {
            throw new IllegalStateException("Unrecognized message: " + msg.what);
        }
        return true;
    }

    private class ExecutionToken implements Runnable {
        public final Runnable runnable;

        private ExecutionToken(Runnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public void run() {
            mHandler.removeCallbacksAndMessages(this);
        }
    }

    private static final int MSG_EXECUTE_RUNNABLE = 0;
}
