/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.soundtrigger_middleware;

import android.annotation.NonNull;
import android.os.Handler;
import android.os.HandlerThread;

/**
 * A simple timer, similar to java.util.Timer, but using the "uptime clock".
 *
 * Example usage:
 * UptimeTimer timer = new UptimeTimer("TimerThread");
 * UptimeTimer.Task task = timer.createTask(() -> { ... }, 100);
 * ...
 * // optionally, some time later:
 * task.cancel();
 */
class UptimeTimer {
    private final Handler mHandler;
    private final HandlerThread mHandlerThread;

    interface Task {
        void cancel();
    }

    UptimeTimer(String threadName) {
        mHandlerThread = new HandlerThread(threadName);
        mHandlerThread.start();
        // Blocks until looper init
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    // Note, this method is not internally synchronized.
    // This is safe since Handlers are internally synchronized.
    Task createTask(@NonNull Runnable runnable, long uptimeMs) {
        Object token = new Object();
        TaskImpl task = new TaskImpl(mHandler, token);
        mHandler.postDelayed(runnable, token, uptimeMs);
        return task;
    }

    void quit() {
        mHandlerThread.quitSafely();
    }

    private static class TaskImpl implements Task {
        private final Handler mHandler;
        private final Object mToken;

        public TaskImpl(Handler handler, Object token) {
            mHandler = handler;
            mToken = token;
        }

        @Override
        public void cancel() {
            mHandler.removeCallbacksAndMessages(mToken);
        }
    };
}
