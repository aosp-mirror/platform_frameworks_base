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
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
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
    private static final int MAX_DUMP_FILES = 20;
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z");
    private static final File[] EMPTY_FILE_ARRAY = {};

    private final ArrayList<CheckPoint> mCheckPoints;
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
            public int maxDumpFiles() {
                return MAX_DUMP_FILES;
            }

            @Override
            public IActivityManager activityManager() {
                return ActivityManager.getService();
            }
        });
    }

    @VisibleForTesting
    ShutdownCheckPoints(Injector injector) {
        mCheckPoints = new ArrayList<>();
        mInjector = injector;
    }

    /** Records the stack trace of this {@link Thread} as a shutdown check point. */
    public static void recordCheckPoint(@Nullable String reason) {
        INSTANCE.recordCheckPointInternal(reason);
    }

    /** Records the pid of the caller process as a shutdown check point. */
    public static void recordCheckPoint(int callerProcessId, @Nullable String reason) {
        INSTANCE.recordCheckPointInternal(callerProcessId, reason);
    }

    /** Records the {@link android.content.Intent} name and package as a shutdown check point. */
    public static void recordCheckPoint(
            String intentName, String packageName, @Nullable String reason) {
        INSTANCE.recordCheckPointInternal(intentName, packageName, reason);
    }

    /** Serializes the recorded check points and writes them to given {@code printWriter}. */
    public static void dump(PrintWriter printWriter) {
        INSTANCE.dumpInternal(printWriter);
    }

    /**
     * Creates a {@link Thread} that calls {@link #dump(PrintWriter)} on a rotating file created
     * from given {@code baseFile} and a timestamp suffix. Older dump files are also deleted by this
     * thread.
     */
    public static Thread newDumpThread(File baseFile) {
        return INSTANCE.newDumpThreadInternal(baseFile);
    }

    @VisibleForTesting
    void recordCheckPointInternal(@Nullable String reason) {
        recordCheckPointInternal(new SystemServerCheckPoint(mInjector.currentTimeMillis(), reason));
        Slog.v(TAG, "System server shutdown checkpoint recorded");
    }

    @VisibleForTesting
    void recordCheckPointInternal(int callerProcessId, @Nullable String reason) {
        long timestamp = mInjector.currentTimeMillis();
        recordCheckPointInternal(callerProcessId == Process.myPid()
                ? new SystemServerCheckPoint(timestamp, reason)
                : new BinderCheckPoint(timestamp, callerProcessId, reason));
        Slog.v(TAG, "Binder shutdown checkpoint recorded with pid=" + callerProcessId);
    }

    @VisibleForTesting
    void recordCheckPointInternal(String intentName, String packageName, @Nullable String reason) {
        long timestamp = mInjector.currentTimeMillis();
        recordCheckPointInternal("android".equals(packageName)
                ? new SystemServerCheckPoint(timestamp, reason)
                : new IntentCheckPoint(timestamp, intentName, packageName, reason));
        Slog.v(TAG, String.format("Shutdown intent checkpoint recorded intent=%s from package=%s",
                intentName, packageName));
    }

    private void recordCheckPointInternal(CheckPoint checkPoint) {
        synchronized (mCheckPoints) {
            mCheckPoints.add(checkPoint);
            if (mCheckPoints.size() > mInjector.maxCheckPoints()) mCheckPoints.remove(0);
        }
    }

    @VisibleForTesting
    void dumpInternal(PrintWriter printWriter) {
        final List<CheckPoint> records;
        synchronized (mCheckPoints) {
            records = new ArrayList<>(mCheckPoints);
        }
        for (CheckPoint record : records) {
            record.dump(mInjector, printWriter);
            printWriter.println();
        }
    }

    @VisibleForTesting
    Thread newDumpThreadInternal(File baseFile) {
        return new FileDumperThread(this, baseFile, mInjector.maxDumpFiles());
    }

    /** Injector used by {@link ShutdownCheckPoints} for testing purposes. */
    @VisibleForTesting
    interface Injector {

        long currentTimeMillis();

        int maxCheckPoints();

        int maxDumpFiles();

        IActivityManager activityManager();
    }

    /** Representation of a generic shutdown call, which can be serialized. */
    private abstract static class CheckPoint {

        private final long mTimestamp;
        @Nullable private final String mReason;

        CheckPoint(long timestamp, @Nullable String reason) {
            mTimestamp = timestamp;
            mReason = reason;
        }

        final void dump(Injector injector, PrintWriter printWriter) {
            printWriter.print("Shutdown request from ");
            printWriter.print(getOrigin());
            if (mReason != null) {
                printWriter.print(" for reason ");
                printWriter.print(mReason);
            }
            printWriter.print(" at ");
            printWriter.print(DATE_FORMAT.format(new Date(mTimestamp)));
            printWriter.println(" (epoch=" + mTimestamp + ")");
            dumpDetails(injector, printWriter);
        }

        abstract String getOrigin();

        abstract void dumpDetails(Injector injector, PrintWriter printWriter);
    }

    /** Representation of a shutdown call from the system server, with stack trace. */
    private static class SystemServerCheckPoint extends CheckPoint {

        private final StackTraceElement[] mStackTraceElements;

        SystemServerCheckPoint(long timestamp, @Nullable String reason) {
            super(timestamp, reason);
            mStackTraceElements = Thread.currentThread().getStackTrace();
        }

        @Override
        String getOrigin() {
            return "SYSTEM";
        }

        @Override
        void dumpDetails(Injector injector, PrintWriter printWriter) {
            String methodName = findMethodName();
            printWriter.println(methodName == null ? "Failed to get method name" : methodName);
            printStackTrace(printWriter);
        }

        @Nullable
        String findMethodName() {
            int idx = findCallSiteIndex();
            if (idx < mStackTraceElements.length) {
                StackTraceElement element = mStackTraceElements[idx];
                return String.format("%s.%s", element.getClassName(), element.getMethodName());
            }
            return null;
        }

        void printStackTrace(PrintWriter printWriter) {
            // Skip the call site line, as it's already considered with findMethodName.
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

        BinderCheckPoint(long timestamp, int callerProcessId, @Nullable String reason) {
            super(timestamp, reason);
            mCallerProcessId = callerProcessId;
        }

        @Override
        String getOrigin() {
            return "BINDER";
        }

        @Override
        void dumpDetails(Injector injector, PrintWriter printWriter) {
            String methodName = findMethodName();
            printWriter.println(methodName == null ? "Failed to get method name" : methodName);

            String processName = findProcessName(injector.activityManager());
            printWriter.print("From process ");
            printWriter.print(processName == null ? "?" : processName);
            printWriter.println(" (pid=" + mCallerProcessId + ")");
        }

        @Nullable
        private String findProcessName(@Nullable IActivityManager activityManager) {
            try {
                List<ActivityManager.RunningAppProcessInfo> runningProcesses = null;
                if (activityManager != null) {
                    runningProcesses = activityManager.getRunningAppProcesses();
                } else {
                    Slog.v(TAG, "No ActivityManager to find name of process with pid="
                        + mCallerProcessId);
                }
                if (runningProcesses != null) {
                    for (ActivityManager.RunningAppProcessInfo processInfo : runningProcesses) {
                        if (processInfo.pid == mCallerProcessId) {
                            return processInfo.processName;
                        }
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

        IntentCheckPoint(
                long timestamp, String intentName, String packageName, @Nullable String reason) {
            super(timestamp, reason);
            mIntentName = intentName;
            mPackageName = packageName;
        }

        @Override
        String getOrigin() {
            return "INTENT";
        }

        @Override
        void dumpDetails(Injector injector, PrintWriter printWriter) {
            printWriter.print("Intent: ");
            printWriter.println(mIntentName);
            printWriter.print("Package: ");
            printWriter.println(mPackageName);
        }
    }

    /**
     * Thread that writes {@link ShutdownCheckPoints#dumpInternal(PrintWriter)} to a new file and
     * deletes old ones to keep the total number of files down to a given limit.
     */
    private static final class FileDumperThread extends Thread {

        private final ShutdownCheckPoints mInstance;
        private final File mBaseFile;
        private final int mFileCountLimit;

        FileDumperThread(ShutdownCheckPoints instance, File baseFile, int fileCountLimit) {
            mInstance = instance;
            mBaseFile = baseFile;
            mFileCountLimit = fileCountLimit;
        }

        @Override
        public void run() {
            mBaseFile.getParentFile().mkdirs();
            File[] checkPointFiles = listCheckPointsFiles();

            int filesToDelete = checkPointFiles.length - mFileCountLimit + 1;
            for (int i = 0; i < filesToDelete; i++) {
                checkPointFiles[i].delete();
            }

            File nextCheckPointsFile = new File(String.format("%s-%d",
                    mBaseFile.getAbsolutePath(), System.currentTimeMillis()));
            writeCheckpoints(nextCheckPointsFile);
        }

        private File[] listCheckPointsFiles() {
            String filePrefix = mBaseFile.getName() + "-";
            File[] files = mBaseFile.getParentFile().listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    if (!name.startsWith(filePrefix)) {
                        return false;
                    }
                    try {
                        Long.valueOf(name.substring(filePrefix.length()));
                    } catch (NumberFormatException e) {
                        return false;
                    }
                    return true;
                }
            });
            if (files == null) {
                return EMPTY_FILE_ARRAY;
            }
            Arrays.sort(files);
            return files;
        }

        private void writeCheckpoints(File file) {
            AtomicFile tmpFile = new AtomicFile(mBaseFile);
            FileOutputStream fos = null;
            try {
                fos = tmpFile.startWrite();
                PrintWriter pw = new PrintWriter(fos);
                mInstance.dumpInternal(pw);
                pw.flush();
                tmpFile.finishWrite(fos); // This also closes the output stream.
            } catch (IOException e) {
                Log.e(TAG, "Failed to write shutdown checkpoints", e);
                if (fos != null) {
                    tmpFile.failWrite(fos); // This also closes the output stream.
                }
            }
            mBaseFile.renameTo(file);
        }
    }
}
