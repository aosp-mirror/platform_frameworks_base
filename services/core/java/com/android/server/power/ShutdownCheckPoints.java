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

package com.android.server.power;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.os.Process;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

/**
 * The shutdown check points are a recording of more detailed information of the origin of calls to
 * system shutdown and reboot framework methods.
 *
 * @hide
 */
public final class ShutdownCheckPoints {

    private static final String TAG = "ShutdownCheckPoints";

    private static final ShutdownCheckPoints INSTANCE = new ShutdownCheckPoints();

    private static final int MAX_CHECK_POINTS = 100;
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z");

    private final LinkedList<CheckPoint> mCheckPoints;
    private final Injector mInjector;

    private ShutdownCheckPoints() {
        this(new Injector() {
            @Override
            public long currentTimeMillis() {
                return System.currentTimeMillis();
            }

            @Override
            public int maxCheckPoints() {
                return MAX_CHECK_POINTS;
            }

            @Override
            public IActivityManager activityManager() {
                return ActivityManager.getService();
            }
        });
    }

    @VisibleForTesting
    ShutdownCheckPoints(Injector injector) {
        mCheckPoints = new LinkedList<>();
        mInjector = injector;
    }

    /** Records the stack trace of this {@link Thread} as a shutdown check point. */
    public static void recordCheckPoint() {
        INSTANCE.recordCheckPointInternal();
    }

    /** Records the pid of the caller process as a shutdown check point. */
    public static void recordCheckPoint(int callerProcessId) {
        INSTANCE.recordCheckPointInternal(callerProcessId);
    }

    /** Records the {@link android.content.Intent} name and package as a shutdown check point. */
    public static void recordCheckPoint(String intentName, String packageName) {
        INSTANCE.recordCheckPointInternal(intentName, packageName);
    }

    /** Serializes the recorded check points and writes them to given {@code printWriter}. */
    public static void dump(PrintWriter printWriter) {
        INSTANCE.dumpInternal(printWriter);
    }

    @VisibleForTesting
    void recordCheckPointInternal() {
        recordCheckPointInternal(new SystemServerCheckPoint(mInjector));
        Slog.v(TAG, "System server shutdown checkpoint recorded");
    }

    @VisibleForTesting
    void recordCheckPointInternal(int callerProcessId) {
        recordCheckPointInternal(callerProcessId == Process.myPid()
                ? new SystemServerCheckPoint(mInjector)
                : new BinderCheckPoint(mInjector, callerProcessId));
        Slog.v(TAG, "Binder shutdown checkpoint recorded with pid=" + callerProcessId);
    }

    @VisibleForTesting
    void recordCheckPointInternal(String intentName, String packageName) {
        recordCheckPointInternal("android".equals(packageName)
                ? new SystemServerCheckPoint(mInjector)
                : new IntentCheckPoint(mInjector, intentName, packageName));
        Slog.v(TAG, String.format("Shutdown intent checkpoint recorded intent=%s from package=%s",
                intentName, packageName));
    }

    private void recordCheckPointInternal(CheckPoint checkPoint) {
        synchronized (mCheckPoints) {
            mCheckPoints.addLast(checkPoint);
            if (mCheckPoints.size() > mInjector.maxCheckPoints()) mCheckPoints.removeFirst();
        }
    }

    @VisibleForTesting
    void dumpInternal(PrintWriter printWriter) {
        final List<CheckPoint> records;
        synchronized (mCheckPoints) {
            records = new ArrayList<>(mCheckPoints);
        }
        for (CheckPoint record : records) {
            record.dump(printWriter);
            printWriter.println();
        }
    }

    /** Injector used by {@link ShutdownCheckPoints} for testing purposes. */
    @VisibleForTesting
    interface Injector {
        long currentTimeMillis();

        int maxCheckPoints();

        IActivityManager activityManager();
    }

    /** Representation of a generic shutdown call, which can be serialized. */
    private abstract static class CheckPoint {

        private final long mTimestamp;

        CheckPoint(Injector injector) {
            mTimestamp = injector.currentTimeMillis();
        }

        final void dump(PrintWriter printWriter) {
            printWriter.print("Shutdown request from ");
            printWriter.print(getOrigin());
            printWriter.print(" at ");
            printWriter.print(DATE_FORMAT.format(new Date(mTimestamp)));
            printWriter.println(" (epoch=" + mTimestamp + ")");
            dumpDetails(printWriter);
        }

        abstract String getOrigin();

        abstract void dumpDetails(PrintWriter printWriter);
    }

    /** Representation of a shutdown call from the system server, with stack trace. */
    private static class SystemServerCheckPoint extends CheckPoint {

        private final StackTraceElement[] mStackTraceElements;

        SystemServerCheckPoint(Injector injector) {
            super(injector);
            mStackTraceElements = Thread.currentThread().getStackTrace();
        }

        @Override
        String getOrigin() {
            return "SYSTEM";
        }

        @Override
        void dumpDetails(PrintWriter printWriter) {
            String methodName = getMethodName();
            printWriter.println(methodName == null ? "Failed to get method name" : methodName);
            printStackTrace(printWriter);
        }

        @Nullable
        String getMethodName() {
            int idx = findCallSiteIndex();
            if (idx < mStackTraceElements.length) {
                StackTraceElement element = mStackTraceElements[idx];
                return String.format("%s.%s", element.getClassName(), element.getMethodName());
            }
            return null;
        }

        void printStackTrace(PrintWriter printWriter) {
            // Skip the call site line, as it's already considered with getMethodName.
            for (int i = findCallSiteIndex() + 1; i < mStackTraceElements.length; i++) {
                printWriter.print(" at ");
                printWriter.println(mStackTraceElements[i]);
            }
        }

        private int findCallSiteIndex() {
            String className = ShutdownCheckPoints.class.getCanonicalName();
            int idx = 0;
            // Skip system trace lines until finding ShutdownCheckPoints call site.
            while (idx < mStackTraceElements.length
                    && !mStackTraceElements[idx].getClassName().equals(className)) {
                ++idx;
            }
            // Skip trace lines from ShutdownCheckPoints class.
            while (idx < mStackTraceElements.length
                    && mStackTraceElements[idx].getClassName().equals(className)) {
                ++idx;
            }
            return idx;
        }
    }

    /** Representation of a shutdown call to {@link android.os.Binder}, with caller process id. */
    private static class BinderCheckPoint extends SystemServerCheckPoint {
        private final int mCallerProcessId;
        private final IActivityManager mActivityManager;

        BinderCheckPoint(Injector injector, int callerProcessId) {
            super(injector);
            mCallerProcessId = callerProcessId;
            mActivityManager = injector.activityManager();
        }

        @Override
        String getOrigin() {
            return "BINDER";
        }

        @Override
        void dumpDetails(PrintWriter printWriter) {
            String methodName = getMethodName();
            printWriter.println(methodName == null ? "Failed to get method name" : methodName);

            String processName = getProcessName();
            printWriter.print("From process ");
            printWriter.print(processName == null ? "?" : processName);
            printWriter.println(" (pid=" + mCallerProcessId + ")");
        }

        @Nullable
        String getProcessName() {
            try {
                List<ActivityManager.RunningAppProcessInfo> runningProcesses =
                        mActivityManager.getRunningAppProcesses();
                for (ActivityManager.RunningAppProcessInfo processInfo : runningProcesses) {
                    if (processInfo.pid == mCallerProcessId) {
                        return processInfo.processName;
                    }
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to get running app processes from ActivityManager", e);
            }
            return null;
        }
    }

    /** Representation of a shutdown call with {@link android.content.Intent}. */
    private static class IntentCheckPoint extends CheckPoint {
        private final String mIntentName;
        private final String mPackageName;

        IntentCheckPoint(Injector injector, String intentName, String packageName) {
            super(injector);
            mIntentName = intentName;
            mPackageName = packageName;
        }

        @Override
        String getOrigin() {
            return "INTENT";
        }

        @Override
        void dumpDetails(PrintWriter printWriter) {
            printWriter.print("Intent: ");
            printWriter.println(mIntentName);
            printWriter.print("Package: ");
            printWriter.println(mPackageName);
        }
    }
}
