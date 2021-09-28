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
import android.os.Looper;

import java.util.concurrent.atomic.AtomicReference;

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
    private Handler mHandler = null;

    interface Task {
        void cancel();
    }

    UptimeTimer(String threadName) {
        new Thread(this::threadFunc, threadName).start();
        synchronized (this) {
            while (mHandler == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    Task createTask(@NonNull Runnable runnable, long uptimeMs) {
        TaskImpl task = new TaskImpl(runnable);
        mHandler.postDelayed(task, uptimeMs);
        return task;
    }

    private void threadFunc() {
        Looper.prepare();
        synchronized (this) {
            mHandler = new Handler(Looper.myLooper());
            notifyAll();
        }
        Looper.loop();
    }

    private static class TaskImpl implements Task, Runnable {
        private AtomicReference<Runnable> mRunnable = new AtomicReference<>();

        TaskImpl(@NonNull Runnable runnable) {
            mRunnable.set(runnable);
        }

        @Override
        public void cancel() {
            mRunnable.set(null);
        }

        @Override
        public void run() {
            Runnable runnable = mRunnable.get();
            if (runnable != null) {
                runnable.run();
            }
        }
    }
}
