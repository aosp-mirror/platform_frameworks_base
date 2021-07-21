/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.os.HandlerThread;
import android.os.Looper;

import java.util.concurrent.Executor;

import javax.inject.Inject;

class ThreadFactoryImpl implements ThreadFactory {
    @Inject
    ThreadFactoryImpl() {}

    @Override
    public Looper buildLooperOnNewThread(String threadName) {
        HandlerThread handlerThread = new HandlerThread(threadName);
        handlerThread.start();
        return handlerThread.getLooper();
    }

    @Override
    public Handler buildHandlerOnNewThread(String threadName) {
        return new Handler(buildLooperOnNewThread(threadName));
    }

    @Override
    public Executor buildExecutorOnNewThread(String threadName) {
        return buildDelayableExecutorOnNewThread(threadName);
    }

    @Override
    public DelayableExecutor buildDelayableExecutorOnNewThread(String threadName) {
        HandlerThread handlerThread = new HandlerThread(threadName);
        handlerThread.start();
        return buildDelayableExecutorOnLooper(handlerThread.getLooper());
    }

    @Override
    public DelayableExecutor buildDelayableExecutorOnHandler(Handler handler) {
        return buildDelayableExecutorOnLooper(handler.getLooper());
    }

    @Override
    public DelayableExecutor buildDelayableExecutorOnLooper(Looper looper) {
        return new ExecutorImpl(looper);
    }
}
