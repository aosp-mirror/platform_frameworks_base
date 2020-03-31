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

package com.android.server.am;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.wm.WindowProcessController;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The helper class to handle no response process. An independent thread will be created on demand
 * so the caller can report the ANR without worrying about taking long time.
 */
class AnrHelper {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "AnrHelper" : TAG_AM;

    /**
     * If the system is extremely slow somehow that the ANR has been pending too long for more than
     * this time, the information might be outdated. So we only the dump the unresponsive process
     * instead of including other processes to avoid making the system more busy.
     */
    private static final long EXPIRED_REPORT_TIME_MS = TimeUnit.MINUTES.toMillis(1);

    @GuardedBy("mAnrRecords")
    private final ArrayList<AnrRecord> mAnrRecords = new ArrayList<>();
    private final AtomicBoolean mRunning = new AtomicBoolean(false);

    void appNotResponding(ProcessRecord anrProcess, String annotation) {
        appNotResponding(anrProcess, null /* activityShortComponentName */, null /* aInfo */,
                null /* parentShortComponentName */, null /* parentProcess */,
                false /* aboveSystem */, annotation);
    }

    void appNotResponding(ProcessRecord anrProcess, String activityShortComponentName,
            ApplicationInfo aInfo, String parentShortComponentName,
            WindowProcessController parentProcess, boolean aboveSystem, String annotation) {
        synchronized (mAnrRecords) {
            mAnrRecords.add(new AnrRecord(anrProcess, activityShortComponentName, aInfo,
                    parentShortComponentName, parentProcess, aboveSystem, annotation));
        }
        startAnrConsumerIfNeeded();
    }

    private void startAnrConsumerIfNeeded() {
        if (mRunning.compareAndSet(false, true)) {
            new AnrConsumerThread().start();
        }
    }

    /**
     * The thread to execute {@link ProcessRecord#appNotResponding}. It will terminate if all
     * records are handled.
     */
    private class AnrConsumerThread extends Thread {
        AnrConsumerThread() {
            super("AnrConsumer");
        }

        private AnrRecord next() {
            synchronized (mAnrRecords) {
                return mAnrRecords.isEmpty() ? null : mAnrRecords.remove(0);
            }
        }

        @Override
        public void run() {
            AnrRecord r;
            while ((r = next()) != null) {
                final long startTime = SystemClock.uptimeMillis();
                // If there are many ANR at the same time, the latency may be larger. If the latency
                // is too large, the stack trace might not be meaningful.
                final long reportLatency = startTime - r.mTimestamp;
                final boolean onlyDumpSelf = reportLatency > EXPIRED_REPORT_TIME_MS;
                r.appNotResponding(onlyDumpSelf);
                final long endTime = SystemClock.uptimeMillis();
                Slog.d(TAG, "Completed ANR of " + r.mApp.processName + " in "
                        + (endTime - startTime) + "ms, latency " + reportLatency
                        + (onlyDumpSelf ? "ms" : "ms (expired, only dump ANR app)"));
            }

            mRunning.set(false);
            synchronized (mAnrRecords) {
                // The race should be unlikely to happen. Just to make sure we don't miss.
                if (!mAnrRecords.isEmpty()) {
                    startAnrConsumerIfNeeded();
                }
            }
        }
    }

    private static class AnrRecord {
        final ProcessRecord mApp;
        final String mActivityShortComponentName;
        final String mParentShortComponentName;
        final String mAnnotation;
        final ApplicationInfo mAppInfo;
        final WindowProcessController mParentProcess;
        final boolean mAboveSystem;
        final long mTimestamp = SystemClock.uptimeMillis();

        AnrRecord(ProcessRecord anrProcess, String activityShortComponentName,
                ApplicationInfo aInfo, String parentShortComponentName,
                WindowProcessController parentProcess, boolean aboveSystem, String annotation) {
            mApp = anrProcess;
            mActivityShortComponentName = activityShortComponentName;
            mParentShortComponentName = parentShortComponentName;
            mAnnotation = annotation;
            mAppInfo = aInfo;
            mParentProcess = parentProcess;
            mAboveSystem = aboveSystem;
        }

        void appNotResponding(boolean onlyDumpSelf) {
            mApp.appNotResponding(mActivityShortComponentName, mAppInfo,
                    mParentShortComponentName, mParentProcess, mAboveSystem, mAnnotation,
                    onlyDumpSelf);
        }
    }
}
