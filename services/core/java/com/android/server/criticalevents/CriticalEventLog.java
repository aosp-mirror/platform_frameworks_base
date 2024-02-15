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

package com.android.server.criticalevents;

import android.os.Handler;
import android.os.HandlerThread;
import android.server.ServerProtoEnums;
import android.util.Slog;

import com.android.framework.protobuf.nano.MessageNanoPrinter;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.RingBuffer;
import com.android.server.criticalevents.nano.CriticalEventLogProto;
import com.android.server.criticalevents.nano.CriticalEventLogStorageProto;
import com.android.server.criticalevents.nano.CriticalEventProto;
import com.android.server.criticalevents.nano.CriticalEventProto.AppNotResponding;
import com.android.server.criticalevents.nano.CriticalEventProto.HalfWatchdog;
import com.android.server.criticalevents.nano.CriticalEventProto.InstallPackages;
import com.android.server.criticalevents.nano.CriticalEventProto.JavaCrash;
import com.android.server.criticalevents.nano.CriticalEventProto.NativeCrash;
import com.android.server.criticalevents.nano.CriticalEventProto.SystemServerStarted;
import com.android.server.criticalevents.nano.CriticalEventProto.Watchdog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

/**
 * Log of recent critical events such as Watchdogs.
 *
 * For use in ANR reports to show recent events that may help to debug the ANR. In particular,
 * the presence of recent critical events signal that the device was already in a had state.
 *
 * This class needs to be thread safe since it's used as a singleton.
 */
public class CriticalEventLog {
    private static final String TAG = CriticalEventLog.class.getSimpleName();

    /** UID for system_server. */
    private static final int AID_SYSTEM = 1000;

    private static CriticalEventLog sInstance;

    /** Name of the file the log is saved to. */
    @VisibleForTesting
    static final String FILENAME = "critical_event_log.pb";

    /** Timestamp when the log was last saved (or attempted to be saved) to disk. */
    private long mLastSaveAttemptMs = 0;

    /** File the log is saved to. */
    private final File mLogFile;

    /** Ring buffer containing the log events. */
    private final ThreadSafeRingBuffer<CriticalEventProto> mEvents;

    /** Max age of events to include in the output log proto. */
    private final int mWindowMs;

    /** Minimum time between consecutive saves of the log to disk. */
    private final long mMinTimeBetweenSavesMs;

    /** Whether to load and save the log synchronously with no delay. Only set to true in tests. */
    private final boolean mLoadAndSaveImmediately;

    private final Handler mHandler;

    private final Runnable mSaveRunnable = this::saveLogToFileNow;

    @VisibleForTesting
    CriticalEventLog(String logDir, int capacity, int windowMs, long minTimeBetweenSavesMs,
            boolean loadAndSaveImmediately, ILogLoader logLoader) {
        mLogFile = Paths.get(logDir, FILENAME).toFile();
        mWindowMs = windowMs;
        mMinTimeBetweenSavesMs = minTimeBetweenSavesMs;
        mLoadAndSaveImmediately = loadAndSaveImmediately;

        mEvents = new ThreadSafeRingBuffer<>(CriticalEventProto.class, capacity);

        HandlerThread thread = new HandlerThread("CriticalEventLogIO");
        thread.start();
        mHandler = new Handler(thread.getLooper());

        final Runnable loadEvents = () -> logLoader.load(mLogFile, mEvents);
        if (!mLoadAndSaveImmediately) {
            mHandler.post(loadEvents);
        } else {
            loadEvents.run();
        }
    }

    /** Returns a new instance with production settings. */
    private CriticalEventLog() {
        this(
                /* logDir= */"/data/misc/critical-events",
                /* capacity= */ 20,
                /* windowMs= */ (int) Duration.ofMinutes(5).toMillis(),
                /* minTimeBetweenSavesMs= */ Duration.ofSeconds(2).toMillis(),
                /* loadAndSaveImmediately= */ false,
                new LogLoader());
    }

    /** Returns the singleton instance. */
    public static CriticalEventLog getInstance() {
        if (sInstance == null) {
            sInstance = new CriticalEventLog();
        }
        return sInstance;
    }

    /**
     * Ensures the singleton instance has been instantiated.
     *
     * Use this to eagerly instantiate the log (which loads the previous events from disk).
     * Otherwise this will occur lazily when the first event is logged, by which time the device may
     * be under load.
     */
    public static void init() {
        getInstance();
    }

    @VisibleForTesting
    protected long getWallTimeMillis() {
        return System.currentTimeMillis();
    }

    /** Logs when one or more packages are installed. */
    public void logInstallPackagesStarted() {
        CriticalEventProto event = new CriticalEventProto();
        event.setInstallPackages(new InstallPackages());
        log(event);
    }

    /** Logs when system server started. */
    public void logSystemServerStarted() {
        CriticalEventProto event = new CriticalEventProto();
        event.setSystemServerStarted(new SystemServerStarted());
        log(event);
    }

    /** Logs a watchdog. */
    public void logWatchdog(String subject, UUID uuid) {
        Watchdog watchdog = new Watchdog();
        watchdog.subject = subject;
        watchdog.uuid = uuid.toString();
        CriticalEventProto event = new CriticalEventProto();
        event.setWatchdog(watchdog);
        log(event);
    }

    /** Logs a half-watchdog. */
    public void logHalfWatchdog(String subject) {
        HalfWatchdog halfWatchdog = new HalfWatchdog();
        halfWatchdog.subject = subject;
        CriticalEventProto event = new CriticalEventProto();
        event.setHalfWatchdog(halfWatchdog);
        log(event);
    }

    /**
     * Logs an ANR.
     *
     * @param subject          the ANR subject line.
     * @param processClassEnum {@link android.server.ServerProtoEnums} value for the ANRing process.
     * @param processName      name of the ANRing process.
     * @param uid              uid of the ANRing process.
     * @param pid              pid of the ANRing process.
     */
    public void logAnr(String subject, int processClassEnum, String processName, int uid, int pid) {
        AppNotResponding anr = new AppNotResponding();
        anr.subject = subject;
        anr.processClass = processClassEnum;
        anr.process = processName;
        anr.uid = uid;
        anr.pid = pid;
        CriticalEventProto event = new CriticalEventProto();
        event.setAnr(anr);
        log(event);
    }

    /**
     * Logs a java crash.
     *
     * @param exceptionClass   the crash exception class.
     * @param processClassEnum {@link android.server.ServerProtoEnums} value for the crashed
     *                         process.
     * @param processName      name of the crashed process.
     * @param uid              uid of the crashed process.
     * @param pid              pid of the crashed process.
     */
    public void logJavaCrash(String exceptionClass, int processClassEnum, String processName,
            int uid, int pid) {
        JavaCrash crash = new JavaCrash();
        crash.exceptionClass = exceptionClass;
        crash.processClass = processClassEnum;
        crash.process = processName;
        crash.uid = uid;
        crash.pid = pid;
        CriticalEventProto event = new CriticalEventProto();
        event.setJavaCrash(crash);
        log(event);
    }

    /**
     * Logs a native crash.
     *
     * @param processClassEnum {@link android.server.ServerProtoEnums} value for the crashed
     *                         process.
     * @param processName      name of the crashed process.
     * @param uid              uid of the crashed process.
     * @param pid              pid of the crashed process.
     */
    public void logNativeCrash(int processClassEnum, String processName, int uid, int pid) {
        NativeCrash crash = new NativeCrash();
        crash.processClass = processClassEnum;
        crash.process = processName;
        crash.uid = uid;
        crash.pid = pid;
        CriticalEventProto event = new CriticalEventProto();
        event.setNativeCrash(crash);
        log(event);
    }

    private void log(CriticalEventProto event) {
        event.timestampMs = getWallTimeMillis();
        appendAndSave(event);
    }

    @VisibleForTesting
    void appendAndSave(CriticalEventProto event) {
        mEvents.append(event);
        saveLogToFile();
    }

    /**
     * Returns recent critical events in text format to include in system server ANR stack trace
     * file.
     *
     * Includes all events in the ring buffer with age less than or equal to {@code mWindowMs}.
     */
    public String logLinesForSystemServerTraceFile() {
        return logLinesForTraceFile(ServerProtoEnums.SYSTEM_SERVER, "AID_SYSTEM", AID_SYSTEM);
    }

    /**
     * Returns recent critical events in text format to include in logs such as ANR stack trace
     * files.
     *
     * Includes all events in the ring buffer with age less than or equal to {@code mWindowMs}.
     *
     * Some data in the returned log may be redacted for privacy. For example, a log for a data
     * app will not include specific crash information for a different data app. See
     * {@link LogSanitizer} for more information.
     *
     * @param traceProcessClassEnum {@link android.server.ServerProtoEnums} value for the process
     *                              the ANR trace file is for.
     * @param traceProcessName      name of the process the ANR trace file is for.
     * @param traceUid              uid of the process the ANR trace file is for.
     */
    public String logLinesForTraceFile(int traceProcessClassEnum, String traceProcessName,
            int traceUid) {
        CriticalEventLogProto outputLogProto = getOutputLogProto(traceProcessClassEnum,
                traceProcessName, traceUid);
        return new StringBuilder()
                .append("--- CriticalEventLog ---\n")
                .append(MessageNanoPrinter.print(outputLogProto))
                .append('\n').toString();
    }

    /**
     * Returns a proto containing recent critical events.
     *
     * Includes all events in the ring buffer with age less than or equal to {@code mWindowMs}.
     */
    @VisibleForTesting
    protected CriticalEventLogProto getOutputLogProto(int traceProcessClassEnum,
            String traceProcessName, int traceUid) {
        CriticalEventLogProto log = new CriticalEventLogProto();
        log.timestampMs = getWallTimeMillis();
        log.windowMs = mWindowMs;
        log.capacity = mEvents.capacity();

        CriticalEventProto[] events = recentEventsWithMinTimestamp(log.timestampMs - mWindowMs);
        LogSanitizer sanitizer = new LogSanitizer(traceProcessClassEnum, traceProcessName,
                traceUid);
        for (int i = 0; i < events.length; i++) {
            events[i] = sanitizer.process(events[i]);
        }
        log.events = events;

        return log;
    }

    /**
     * Returns the most recent logged events, starting with the first event that has a timestamp
     * greater than or equal to {@code minTimestampMs}.
     *
     * If no events have a timestamp greater than or equal to {@code minTimestampMs}, returns an
     * empty array.
     */
    private CriticalEventProto[] recentEventsWithMinTimestamp(long minTimestampMs) {
        // allEvents are in insertion order, i.e. in order of when the relevant log___() function
        // was called.
        // This means that if the system clock changed (e.g. a NITZ update) allEvents may not be
        // strictly ordered by timestamp. In this case we are permissive and start with the
        // first event that has a timestamp in the desired range.
        CriticalEventProto[] allEvents = mEvents.toArray();
        for (int i = 0; i < allEvents.length; i++) {
            if (allEvents[i].timestampMs >= minTimestampMs) {
                return Arrays.copyOfRange(allEvents, i, allEvents.length);
            }
        }
        return new CriticalEventProto[]{};
    }

    private void saveLogToFile() {
        if (mLoadAndSaveImmediately) {
            saveLogToFileNow();
            return;
        }
        if (mHandler.hasCallbacks(mSaveRunnable)) {
            // An earlier save is already scheduled so don't need to schedule an additional one.
            return;
        }

        if (!mHandler.postDelayed(mSaveRunnable, saveDelayMs())) {
            Slog.w(TAG, "Error scheduling save");
        }
    }

    /**
     * Returns the delay in milliseconds when scheduling a save on the handler thread.
     *
     * Returns a value in the range [0, {@code minTimeBetweenSavesMs}] such that the time between
     * consecutive saves does not exceed {@code minTimeBetweenSavesMs}.
     *
     * This means that if the last save occurred a long time ago, or if no previous saves
     * have occurred then the returned delay will be zero.
     */
    @VisibleForTesting
    protected long saveDelayMs() {
        final long nowMs = getWallTimeMillis();
        return Math.max(0,
                mLastSaveAttemptMs + mMinTimeBetweenSavesMs - nowMs);
    }

    @VisibleForTesting
    protected void saveLogToFileNow() {
        mLastSaveAttemptMs = getWallTimeMillis();

        File logDir = mLogFile.getParentFile();
        if (!logDir.exists()) {
            if (!logDir.mkdir()) {
                Slog.e(TAG, "Error creating log directory: " + logDir.getPath());
                return;
            }
        }

        if (!mLogFile.exists()) {
            try {
                mLogFile.createNewFile();
            } catch (IOException e) {
                Slog.e(TAG, "Error creating log file", e);
                return;
            }
        }

        CriticalEventLogStorageProto logProto = new CriticalEventLogStorageProto();
        logProto.events = mEvents.toArray();

        final byte[] bytes = CriticalEventLogStorageProto.toByteArray(logProto);
        try (FileOutputStream stream = new FileOutputStream(mLogFile, false)) {
            stream.write(bytes);
        } catch (IOException e) {
            Slog.e(TAG, "Error saving log to disk.", e);
        }
    }

    @VisibleForTesting
    protected static class ThreadSafeRingBuffer<T> {
        private final int mCapacity;
        private final RingBuffer<T> mBuffer;

        ThreadSafeRingBuffer(Class<T> clazz, int capacity) {
            this.mCapacity = capacity;
            this.mBuffer = new RingBuffer<>(clazz, capacity);
        }

        synchronized void append(T t) {
            mBuffer.append(t);
        }

        synchronized T[] toArray() {
            return mBuffer.toArray();
        }

        int capacity() {
            return mCapacity;
        }
    }

    /** Loads log events from disk into a ring buffer. */
    protected interface ILogLoader {
        void load(File logFile, ThreadSafeRingBuffer<CriticalEventProto> buffer);
    }

    /** Loads log events from disk into a ring buffer. */
    static class LogLoader implements ILogLoader {
        @Override
        public void load(File logFile,
                ThreadSafeRingBuffer<CriticalEventProto> buffer) {
            for (CriticalEventProto event : loadLogFromFile(logFile).events) {
                buffer.append(event);
            }
        }

        private static CriticalEventLogStorageProto loadLogFromFile(File logFile) {
            if (!logFile.exists()) {
                Slog.i(TAG, "No log found, returning empty log proto.");
                return new CriticalEventLogStorageProto();
            }

            try {
                return CriticalEventLogStorageProto.parseFrom(
                        Files.readAllBytes(logFile.toPath()));
            } catch (IOException e) {
                Slog.e(TAG, "Error reading log from disk.", e);
                return new CriticalEventLogStorageProto();
            }
        }
    }

    /**
     * Redacts private data app fields from the critical event protos.
     *
     * When a critical event log is requested, this class is used to redact specific information
     * so that the trace file for a data app does not leak private information about other data
     * apps.
     */
    private static class LogSanitizer {
        /**
         * The {@link CriticalEventProto.ProcessClass} of the process the output trace file is for.
         */
        int mTraceProcessClassEnum;

        /** The name of the process that the output trace file is for. */
        String mTraceProcessName;

        /** The uid of the process that the output trace file is for. */
        int mTraceUid;

        LogSanitizer(int traceProcessClassEnum, String traceProcessName, int traceUid) {
            mTraceProcessClassEnum = traceProcessClassEnum;
            mTraceProcessName = traceProcessName;
            mTraceUid = traceUid;
        }

        /**
         * Redacts information from a critical event proto where necessary.
         *
         * This function does not mutate its input. If redaction happens, it returns a new proto.
         * Otherwise, it returns the original proto.
         */
        CriticalEventProto process(CriticalEventProto event) {
            if (event.hasAnr()) {
                AppNotResponding anr = event.getAnr();
                if (shouldSanitize(anr.processClass, anr.process, anr.uid)) {
                    return sanitizeAnr(event);
                }
            } else if (event.hasJavaCrash()) {
                JavaCrash crash = event.getJavaCrash();
                if (shouldSanitize(crash.processClass, crash.process, crash.uid)) {
                    return sanitizeJavaCrash(event);
                }
            } else if (event.hasNativeCrash()) {
                NativeCrash crash = event.getNativeCrash();
                if (shouldSanitize(crash.processClass, crash.process, crash.uid)) {
                    return sanitizeNativeCrash(event);
                }
            }
            // No redaction needed.
            return event;
        }

        private boolean shouldSanitize(int processClassEnum, String processName, int uid) {
            boolean sameApp = processName != null && processName.equals(mTraceProcessName)
                    && mTraceUid == uid;

            // Only sanitize when both the critical event and trace file are for different data
            // apps.
            return processClassEnum == CriticalEventProto.DATA_APP
                    && mTraceProcessClassEnum == CriticalEventProto.DATA_APP
                    && !sameApp;
        }

        private static CriticalEventProto sanitizeAnr(CriticalEventProto base) {
            AppNotResponding anr = new AppNotResponding();
            // Do not set subject and process.
            anr.processClass = base.getAnr().processClass;
            anr.uid = base.getAnr().uid;
            anr.pid = base.getAnr().pid;

            CriticalEventProto sanitized = sanitizeCriticalEventProto(base);
            sanitized.setAnr(anr);
            return sanitized;
        }

        private static CriticalEventProto sanitizeJavaCrash(CriticalEventProto base) {
            JavaCrash crash = new JavaCrash();
            // Do not set exceptionClass and process.
            crash.processClass = base.getJavaCrash().processClass;
            crash.uid = base.getJavaCrash().uid;
            crash.pid = base.getJavaCrash().pid;

            CriticalEventProto sanitized = sanitizeCriticalEventProto(base);
            sanitized.setJavaCrash(crash);
            return sanitized;
        }

        private static CriticalEventProto sanitizeNativeCrash(CriticalEventProto base) {
            NativeCrash crash = new NativeCrash();
            // Do not set process.
            crash.processClass = base.getNativeCrash().processClass;
            crash.uid = base.getNativeCrash().uid;
            crash.pid = base.getNativeCrash().pid;

            CriticalEventProto sanitized = sanitizeCriticalEventProto(base);
            sanitized.setNativeCrash(crash);
            return sanitized;
        }

        private static CriticalEventProto sanitizeCriticalEventProto(CriticalEventProto base) {
            CriticalEventProto sanitized = new CriticalEventProto();
            sanitized.timestampMs = base.timestampMs;
            return sanitized;
        }
    }
}
