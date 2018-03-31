/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.content;

import android.app.job.JobParameters;
import android.os.Build;
import android.os.Environment;
import android.os.FileUtils;
import android.os.SystemProperties;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import libcore.io.IoUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Implements a rotating file logger for the sync manager, which is enabled only on userdebug/eng
 * builds (unless debug.synclog is set to 1).
 *
 * Note this class could be used for other purposes too, but in general we don't want various
 * system components to log to files, so it's put in a local package here.
 */
public class SyncLogger {
    private static final String TAG = "SyncLogger";

    private static SyncLogger sInstance;

    // Special UID used for logging to denote the self process.
    public static final int CALLING_UID_SELF = -1;

    SyncLogger() {
    }

    /**
     * @return the singleton instance.
     */
    public static synchronized SyncLogger getInstance() {
        if (sInstance == null) {
            final boolean enable =
                    Build.IS_DEBUGGABLE
                    || "1".equals(SystemProperties.get("debug.synclog"))
                    || Log.isLoggable(TAG, Log.VERBOSE);
            if (enable) {
                sInstance = new RotatingFileLogger();
            } else {
                sInstance = new SyncLogger();
            }
        }
        return sInstance;
    }

    /**
     * Write strings to the log file.
     */
    public void log(Object... message) {
    }

    /**
     * Remove old log files.
     */
    public void purgeOldLogs() {
        // The default implementation is no-op.
    }

    public String jobParametersToString(JobParameters params) {
        // The default implementation is no-op.
        return "";
    }

    /**
     * Dump all existing log files into a given writer.
     */
    public void dumpAll(PrintWriter pw) {
    }

    /**
     * @return whether log is enabled or not.
     */
    public boolean enabled() {
        return false;
    }

    /**
     * Actual implementation which is only used on userdebug/eng builds (by default).
     */
    private static class RotatingFileLogger extends SyncLogger {
        private final Object mLock = new Object();

        private final long mKeepAgeMs = TimeUnit.DAYS.toMillis(7);

        private static final SimpleDateFormat sTimestampFormat
                = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

        private static final SimpleDateFormat sFilenameDateFormat
                = new SimpleDateFormat("yyyy-MM-dd");

        @GuardedBy("mLock")
        private final Date mCachedDate = new Date();

        @GuardedBy("mLock")
        private final StringBuilder mStringBuilder = new StringBuilder();

        private final File mLogPath;

        @GuardedBy("mLock")
        private long mCurrentLogFileDayTimestamp;

        @GuardedBy("mLock")
        private Writer mLogWriter;

        @GuardedBy("mLock")
        private boolean mErrorShown;

        private static final boolean DO_LOGCAT = Log.isLoggable(TAG, Log.DEBUG);

        RotatingFileLogger() {
            mLogPath = new File(Environment.getDataSystemDirectory(), "syncmanager-log");
        }

        @Override
        public boolean enabled() {
            return true;
        }

        private void handleException(String message, Exception e) {
            if (!mErrorShown) {
                Slog.e(TAG, message, e);
                mErrorShown = true;
            }
        }

        @Override
        public void log(Object... message) {
            if (message == null) {
                return;
            }
            synchronized (mLock) {
                final long now = System.currentTimeMillis();
                openLogLocked(now);
                if (mLogWriter == null) {
                    return; // Couldn't open log file?
                }

                mStringBuilder.setLength(0);
                mCachedDate.setTime(now);
                mStringBuilder.append(sTimestampFormat.format(mCachedDate));
                mStringBuilder.append(' ');

                mStringBuilder.append(android.os.Process.myTid());
                mStringBuilder.append(' ');

                final int messageStart = mStringBuilder.length();

                for (Object o : message) {
                    mStringBuilder.append(o);
                }
                mStringBuilder.append('\n');

                try {
                    mLogWriter.append(mStringBuilder);
                    mLogWriter.flush();

                    // Also write on logcat.
                    if (DO_LOGCAT) {
                        Log.d(TAG, mStringBuilder.substring(messageStart));
                    }
                } catch (IOException e) {
                    handleException("Failed to write log", e);
                }
            }
        }

        @GuardedBy("mLock")
        private void openLogLocked(long now) {
            // If we already have a log file opened and the date has't changed, just use it.
            final long day = now % DateUtils.DAY_IN_MILLIS;
            if ((mLogWriter != null) && (day == mCurrentLogFileDayTimestamp)) {
                return;
            }

            // Otherwise create a new log file.
            closeCurrentLogLocked();

            mCurrentLogFileDayTimestamp = day;

            mCachedDate.setTime(now);
            final String filename = "synclog-" + sFilenameDateFormat.format(mCachedDate) + ".log";
            final File file = new File(mLogPath, filename);

            file.getParentFile().mkdirs();

            try {
                mLogWriter = new FileWriter(file, /* append= */ true);
            } catch (IOException e) {
                handleException("Failed to open log file: " + file, e);
            }
        }

        @GuardedBy("mLock")
        private void closeCurrentLogLocked() {
            IoUtils.closeQuietly(mLogWriter);
            mLogWriter = null;
        }

        @Override
        public void purgeOldLogs() {
            synchronized (mLock) {
                FileUtils.deleteOlderFiles(mLogPath, /* keepCount= */ 1, mKeepAgeMs);
            }
        }

        @Override
        public String jobParametersToString(JobParameters params) {
            return SyncJobService.jobParametersToString(params);
        }

        @Override
        public void dumpAll(PrintWriter pw) {
            synchronized (mLock) {
                final String[] files = mLogPath.list();
                if (files == null || (files.length == 0)) {
                    return;
                }
                Arrays.sort(files);

                for (String file : files) {
                    dumpFile(pw, new File(mLogPath, file));
                }
            }
        }

        private void dumpFile(PrintWriter pw, File file) {
            Slog.w(TAG, "Dumping " + file);
            final char[] buffer = new char[32 * 1024];

            try (Reader in = new BufferedReader(new FileReader(file))) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0) {
                        pw.write(buffer, 0, read);
                    }
                }
            } catch (IOException e) {
            }
        }
    }
}
