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
import android.os.HandlerExecutor;
import android.os.Message;

import java.util.concurrent.TimeUnit;

/**
 * Implementations of {@link DelayableExecutor} for SystemUI.
 */
public class ExecutorImpl extends HandlerExecutor implements DelayableExecutor {
    private final Handler mHandler;

    public ExecutorImpl(Handler handler) {
        super(handler);
        mHandler = handler;
    }

    @Override
    public Runnable executeDelayed(Runnable r, long delay, TimeUnit unit) {
        Object token = new Object();
        Message m = mHandler.obtainMessage(0, token);
        mHandler.sendMessageDelayed(m, unit.toMillis(delay));

        return () -> mHandler.removeCallbacksAndMessages(token);
    }

    @Override
    public Runnable executeAtTime(Runnable r, long uptimeMillis, TimeUnit unit) {
        Object token = new Object();
        Message m = mHandler.obtainMessage(0, token);
        mHandler.sendMessageAtTime(m, unit.toMillis(uptimeMillis));

        return () -> mHandler.removeCallbacksAndMessages(token);
    }
}
