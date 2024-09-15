/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.telephony;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.DropBoxManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * A persistent logger backend that stores logs in Android DropBoxManager
 *
 * @hide
 */
public class DropBoxManagerLoggerBackend implements PersistentLoggerBackend {

    private static final String TAG = "DropBoxManagerLoggerBackend";
    // Separate tag reference to be explicitly used for dropboxmanager instead of logcat logging
    private static final String DROPBOX_TAG = "DropBoxManagerLoggerBackend";
    private static final DateTimeFormatter LOG_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS");
    private static final ZoneId LOCAL_ZONE_ID = ZoneId.systemDefault();
    private static final int BUFFER_SIZE_BYTES = 500 * 1024; // 500 KB
    private static final int MIN_BUFFER_BYTES_FOR_FLUSH = 5 * 1024; // 5 KB

    private static DropBoxManagerLoggerBackend sInstance;

    private final DropBoxManager mDropBoxManager;
    private final Object mBufferLock = new Object();
    @GuardedBy("mBufferLock")
    private final StringBuilder mLogBuffer = new StringBuilder();
    private long mBufferStartTime = -1L;
    private final HandlerThread mHandlerThread = new HandlerThread(DROPBOX_TAG);
    private final Handler mHandler;
    // Flag for determining if logging is enabled as a general feature
    private final boolean mDropBoxManagerLoggingEnabled;
    // Flag for controlling if logging is enabled at runtime
    private boolean mIsLoggingEnabled = false;

    /**
     * Returns a singleton instance of {@code DropBoxManagerLoggerBackend} that will log to
     * DropBoxManager if the config_dropboxmanager_persistent_logging_enabled resource config is
     * enabled.
     * @param context Android context
     */
    @Nullable
    public static synchronized DropBoxManagerLoggerBackend getInstance(@NonNull Context context) {
        if (sInstance == null) {
            sInstance = new DropBoxManagerLoggerBackend(context);
        }
        return sInstance;
    }

    private DropBoxManagerLoggerBackend(@NonNull Context context) {
        mDropBoxManager = context.getSystemService(DropBoxManager.class);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mDropBoxManagerLoggingEnabled = persistentLoggingEnabled(context);
    }

    private boolean persistentLoggingEnabled(@NonNull Context context) {
        try {
            return context.getResources().getBoolean(
                    R.bool.config_dropboxmanager_persistent_logging_enabled);
        } catch (RuntimeException e) {
            Log.w(TAG, "Persistent logging config not found");
            return false;
        }
    }

    /**
     * Enable or disable logging to DropBoxManager
     * @param isLoggingEnabled Whether logging should be enabled
     */
    public void setLoggingEnabled(boolean isLoggingEnabled) {
        Log.i(DROPBOX_TAG, "toggle logging: " + isLoggingEnabled);
        mIsLoggingEnabled = isLoggingEnabled;
    }

    /**
     * Persist a DEBUG log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     */
    public void debug(@NonNull String tag, @NonNull String msg) {
        if (!mDropBoxManagerLoggingEnabled) {
            return;
        }
        bufferLog("D", tag, msg, Optional.empty());
    }

    /**
     * Persist a INFO log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     */
    public void info(@NonNull String tag, @NonNull String msg) {
        if (!mDropBoxManagerLoggingEnabled) {
            return;
        }
        bufferLog("I", tag, msg, Optional.empty());
    }

    /**
     * Persist a WARN log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     */
    public void warn(@NonNull String tag, @NonNull String msg) {
        if (!mDropBoxManagerLoggingEnabled) {
            return;
        }
        bufferLog("W", tag, msg, Optional.empty());
    }

    /**
     * Persist a WARN log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     * @param t An exception to log.
     */
    public void warn(@NonNull String tag, @NonNull String msg, @NonNull Throwable t) {
        if (!mDropBoxManagerLoggingEnabled) {
            return;
        }
        bufferLog("W", tag, msg, Optional.of(t));
    }

    /**
     * Persist a ERROR log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     */
    public void error(@NonNull String tag, @NonNull String msg) {
        if (!mDropBoxManagerLoggingEnabled) {
            return;
        }
        bufferLog("E", tag, msg, Optional.empty());
    }

    /**
     * Persist a ERROR log message.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     * @param t An exception to log.
     */
    public void error(@NonNull String tag, @NonNull String msg, @NonNull Throwable t) {
        if (!mDropBoxManagerLoggingEnabled) {
            return;
        }
        bufferLog("E", tag, msg, Optional.of(t));
    }

    private synchronized void bufferLog(
            @NonNull String level,
            @NonNull String tag,
            @NonNull String msg,
            Optional<Throwable> t) {
        if (!mIsLoggingEnabled) {
            return;
        }

        if (mBufferStartTime == -1L) {
            mBufferStartTime = System.currentTimeMillis();
        }

        synchronized (mBufferLock) {
            mLogBuffer
                    .append(formatLog(level, tag, msg, t))
                    .append("\n");

            if (mLogBuffer.length() >= BUFFER_SIZE_BYTES) {
                flushAsync();
            }
        }
    }

    private String formatLog(
            @NonNull String level,
            @NonNull String tag,
            @NonNull String msg,
            Optional<Throwable> t) {
        // Expected format = "$Timestamp $Level $Tag: $Message"
        return formatTimestamp(System.currentTimeMillis()) + " " + level + " " + tag + ": "
                + t.map(throwable -> msg + ": " + Log.getStackTraceString(throwable)).orElse(msg);
    }

    private String formatTimestamp(long currentTimeMillis) {
        return Instant.ofEpochMilli(currentTimeMillis)
                .atZone(LOCAL_ZONE_ID)
                .format(LOG_TIMESTAMP_FORMATTER);
    }

    /**
     * Flushes all buffered logs into DropBoxManager as a single log record with a tag of
     * {@link #DROPBOX_TAG} asynchronously. Should be invoked sparingly as DropBoxManager has
     * device-level limitations on the number files that can be stored.
     */
    public void flushAsync() {
        if (!mDropBoxManagerLoggingEnabled) {
            return;
        }

        mHandler.post(this::flush);
    };

    /**
     * Flushes all buffered logs into DropBoxManager as a single log record with a tag of
     * {@link #DROPBOX_TAG}. Should be invoked sparingly as DropBoxManager has device-level
     * limitations on the number files that can be stored.
     */
    public void flush() {
        if (!mDropBoxManagerLoggingEnabled) {
            return;
        }

        synchronized (mBufferLock) {
            if (mLogBuffer.length() < MIN_BUFFER_BYTES_FOR_FLUSH) {
                return;
            }

            Log.d(DROPBOX_TAG, "Flushing logs from "
                    + formatTimestamp(mBufferStartTime) + " to "
                    + formatTimestamp(System.currentTimeMillis()));

            try {
                mDropBoxManager.addText(DROPBOX_TAG, mLogBuffer.toString());
            } catch (Exception e) {
                Log.w(DROPBOX_TAG, "Failed to flush logs of length "
                        + mLogBuffer.length() + " to DropBoxManager", e);
            }
            mLogBuffer.setLength(0);
        }
        mBufferStartTime = -1L;
    }
}
