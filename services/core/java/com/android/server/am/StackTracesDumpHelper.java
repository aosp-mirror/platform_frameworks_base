/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.text.format.DateUtils.DAY_IN_MILLIS;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_ANR;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.NonNull;
import android.os.Build;
import android.os.Debug;
import android.os.FileUtils;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.os.anr.AnrLatencyTracker;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;


/**
 * A helper for dumping stack traces.
 */
public class StackTracesDumpHelper {
    static final String TAG = TAG_WITH_CLASS_NAME ? "StackTracesDumpHelper" : TAG_AM;

    @GuardedBy("StackTracesDumpHelper.class")
    private static final SimpleDateFormat ANR_FILE_DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS");

    static final String ANR_FILE_PREFIX = "anr_";
    static final String ANR_TEMP_FILE_PREFIX = "temp_anr_";

    public static final String ANR_TRACE_DIR = "/data/anr";
    private static final int NATIVE_DUMP_TIMEOUT_MS =
            2000 * Build.HW_TIMEOUT_MULTIPLIER; // 2 seconds;
    private static final int JAVA_DUMP_MINIMUM_SIZE = 100; // 100 bytes.
    // The time limit for a single process's dump
    private static final int TEMP_DUMP_TIME_LIMIT =
            10 * 1000 * Build.HW_TIMEOUT_MULTIPLIER; // 10 seconds


    /**
     * If a stack trace dump file is configured, dump process stack traces.
     * @param firstPids of dalvik VM processes to dump stack traces for first
     * @param lastPids of dalvik VM processes to dump stack traces for last
     * @param nativePidsFuture optional future for a list of native pids to dump stack crawls
     * @param logExceptionCreatingFile optional writer to which we log errors creating the file
     * @param auxiliaryTaskExecutor executor to execute auxiliary tasks on
     * @param latencyTracker the latency tracker instance of the current ANR.
     */
    public static File dumpStackTraces(ArrayList<Integer> firstPids,
            ProcessCpuTracker processCpuTracker, SparseBooleanArray lastPids,
            Future<ArrayList<Integer>> nativePidsFuture, StringWriter logExceptionCreatingFile,
            @NonNull Executor auxiliaryTaskExecutor, AnrLatencyTracker latencyTracker) {
        return dumpStackTraces(firstPids, processCpuTracker, lastPids, nativePidsFuture,
                logExceptionCreatingFile, null, null, null, null, auxiliaryTaskExecutor, null,
                latencyTracker);
    }

    /**
     * @param subject the subject of the dumped traces
     * @param criticalEventSection the critical event log, passed as a string
     */
    public static File dumpStackTraces(ArrayList<Integer> firstPids,
            ProcessCpuTracker processCpuTracker, SparseBooleanArray lastPids,
            Future<ArrayList<Integer>> nativePidsFuture, StringWriter logExceptionCreatingFile,
            String subject, String criticalEventSection, @NonNull Executor auxiliaryTaskExecutor,
            AnrLatencyTracker latencyTracker) {
        return dumpStackTraces(firstPids, processCpuTracker, lastPids, nativePidsFuture,
                logExceptionCreatingFile, null, subject, criticalEventSection,
                /* memoryHeaders= */ null, auxiliaryTaskExecutor, null, latencyTracker);
    }

    /**
     * @param firstPidEndOffset Optional, when it's set, it receives the start/end offset
     *                        of the very first pid to be dumped.
     */
    /* package */ static File dumpStackTraces(ArrayList<Integer> firstPids,
            ProcessCpuTracker processCpuTracker, SparseBooleanArray lastPids,
            Future<ArrayList<Integer>> nativePidsFuture, StringWriter logExceptionCreatingFile,
            AtomicLong firstPidEndOffset, String subject, String criticalEventSection,
            String memoryHeaders, @NonNull Executor auxiliaryTaskExecutor,
           Future<File> firstPidFilePromise, AnrLatencyTracker latencyTracker) {
        try {

            if (latencyTracker != null) {
                latencyTracker.dumpStackTracesStarted();
            }

            Slog.i(TAG, "dumpStackTraces pids=" + lastPids);

            // Measure CPU usage as soon as we're called in order to get a realistic sampling
            // of the top users at the time of the request.
            Supplier<ArrayList<Integer>> extraPidsSupplier = processCpuTracker != null
                    ? () -> getExtraPids(processCpuTracker, lastPids, latencyTracker) : null;
            Future<ArrayList<Integer>> extraPidsFuture = null;
            if (extraPidsSupplier != null) {
                extraPidsFuture =
                        CompletableFuture.supplyAsync(extraPidsSupplier, auxiliaryTaskExecutor);
            }

            final File tracesDir = new File(ANR_TRACE_DIR);

            // NOTE: We should consider creating the file in native code atomically once we've
            // gotten rid of the old scheme of dumping and lot of the code that deals with paths
            // can be removed.
            File tracesFile;
            try {
                tracesFile = createAnrDumpFile(tracesDir);
            } catch (IOException e) {
                Slog.w(TAG, "Exception creating ANR dump file:", e);
                if (logExceptionCreatingFile != null) {
                    logExceptionCreatingFile.append(
                            "----- Exception creating ANR dump file -----\n");
                    e.printStackTrace(new PrintWriter(logExceptionCreatingFile));
                }
                if (latencyTracker != null) {
                    latencyTracker.anrSkippedDumpStackTraces();
                }
                return null;
            }

            if (subject != null || criticalEventSection != null || memoryHeaders != null) {
                appendtoANRFile(tracesFile.getAbsolutePath(),
                        (subject != null ? "Subject: " + subject + "\n" : "")
                        + (memoryHeaders != null ? memoryHeaders + "\n\n" : "")
                        + (criticalEventSection != null ? criticalEventSection : ""));
            }

            long firstPidEndPos = dumpStackTraces(
                    tracesFile.getAbsolutePath(), firstPids, nativePidsFuture,
                    extraPidsFuture, firstPidFilePromise, latencyTracker);
            if (firstPidEndOffset != null) {
                firstPidEndOffset.set(firstPidEndPos);
            }
            // Each set of ANR traces is written to a separate file and dumpstate will process
            // all such files and add them to a captured bug report if they're recent enough.
            maybePruneOldTraces(tracesDir);

            return tracesFile;
        } finally {
            if (latencyTracker != null) {
                latencyTracker.dumpStackTracesEnded();
            }
        }
    }

    /**
     * @return The end offset of the trace of the very first PID
     */
    public static long dumpStackTraces(String tracesFile,
            ArrayList<Integer> firstPids, Future<ArrayList<Integer>> nativePidsFuture,
            Future<ArrayList<Integer>> extraPidsFuture, Future<File> firstPidFilePromise,
            AnrLatencyTracker latencyTracker) {

        Slog.i(TAG, "Dumping to " + tracesFile);

        // We don't need any sort of inotify based monitoring when we're dumping traces via
        // tombstoned. Data is piped to an "intercept" FD installed in tombstoned so we're in full
        // control of all writes to the file in question.

        // We must complete all stack dumps within 20 seconds.
        long remainingTime = 20 * 1000 * Build.HW_TIMEOUT_MULTIPLIER;

        // As applications are usually interested with the ANR stack traces, but we can't share
        // with them the stack traces other than their own stacks. So after the very first PID is
        // dumped, remember the current file size.
        long firstPidEnd = -1;

        // Was the first pid copied from the temporary file that was created in the predump phase?
        boolean firstPidTempDumpCopied = false;

        // First copy the first pid's dump from the temporary file it was dumped into earlier,
        // The first pid should always exist in firstPids but we check the size just in case.
        if (firstPidFilePromise != null && firstPids != null && firstPids.size() > 0) {
            final int primaryPid = firstPids.get(0);
            final long start = SystemClock.elapsedRealtime();
            firstPidTempDumpCopied = copyFirstPidTempDump(tracesFile, firstPidFilePromise,
                    remainingTime, latencyTracker);
            final long timeTaken = SystemClock.elapsedRealtime() - start;
            remainingTime -= timeTaken;
            if (remainingTime <= 0) {
                Slog.e(TAG, "Aborting stack trace dump (currently copying primary pid" + primaryPid
                        + "); deadline exceeded.");
                return firstPidEnd;
            }
             // We don't copy ANR traces from the system_server intentionally.
            if (firstPidTempDumpCopied && primaryPid != ActivityManagerService.MY_PID) {
                firstPidEnd = new File(tracesFile).length();
            }
            // Append the Durations/latency comma separated array after the first PID.
            if (firstPidTempDumpCopied && latencyTracker != null) {
                appendtoANRFile(tracesFile,
                        latencyTracker.dumpAsCommaSeparatedArrayWithHeader());
            }
        }
        // Next collect all of the stacks of the most important pids.
        if (firstPids != null)  {
            if (latencyTracker != null) {
                latencyTracker.dumpingFirstPidsStarted();
            }

            int num = firstPids.size();
            for (int i = firstPidTempDumpCopied ? 1 : 0; i < num; i++) {
                final int pid = firstPids.get(i);
                // We don't copy ANR traces from the system_server intentionally.
                final boolean firstPid = i == 0 && ActivityManagerService.MY_PID != pid;
                Slog.i(TAG, "Collecting stacks for pid " + pid);
                final long timeTaken = dumpJavaTracesTombstoned(pid, tracesFile, remainingTime,
                        latencyTracker);
                remainingTime -= timeTaken;
                if (remainingTime <= 0) {
                    Slog.e(TAG, "Aborting stack trace dump (current firstPid=" + pid
                            + "); deadline exceeded.");
                    return firstPidEnd;
                }

                if (firstPid) {
                    firstPidEnd = new File(tracesFile).length();
                    // Full latency dump
                    if (latencyTracker != null) {
                        appendtoANRFile(tracesFile,
                                latencyTracker.dumpAsCommaSeparatedArrayWithHeader());
                    }
                }
                if (DEBUG_ANR) {
                    Slog.d(TAG, "Done with pid " + firstPids.get(i) + " in " + timeTaken + "ms");
                }
            }
            if (latencyTracker != null) {
                latencyTracker.dumpingFirstPidsEnded();
            }
        }

        // Next collect the stacks of the native pids
        ArrayList<Integer> nativePids = collectPids(nativePidsFuture, "native pids");

        Slog.i(TAG, "dumpStackTraces nativepids=" + nativePids);

        if (nativePids != null) {
            if (latencyTracker != null) {
                latencyTracker.dumpingNativePidsStarted();
            }
            for (int pid : nativePids) {
                Slog.i(TAG, "Collecting stacks for native pid " + pid);
                final long nativeDumpTimeoutMs = Math.min(NATIVE_DUMP_TIMEOUT_MS, remainingTime);

                if (latencyTracker != null) {
                    latencyTracker.dumpingPidStarted(pid);
                }
                final long start = SystemClock.elapsedRealtime();
                Debug.dumpNativeBacktraceToFileTimeout(
                        pid, tracesFile, (int) (nativeDumpTimeoutMs / 1000));
                final long timeTaken = SystemClock.elapsedRealtime() - start;
                if (latencyTracker != null) {
                    latencyTracker.dumpingPidEnded();
                }
                remainingTime -= timeTaken;
                if (remainingTime <= 0) {
                    Slog.e(TAG, "Aborting stack trace dump (current native pid=" + pid
                            + "); deadline exceeded.");
                    return firstPidEnd;
                }

                if (DEBUG_ANR) {
                    Slog.d(TAG, "Done with native pid " + pid + " in " + timeTaken + "ms");
                }
            }
            if (latencyTracker != null) {
                latencyTracker.dumpingNativePidsEnded();
            }
        }

        // Lastly, dump stacks for all extra PIDs from the CPU tracker.
        ArrayList<Integer> extraPids = collectPids(extraPidsFuture, "extra pids");

        if (extraPidsFuture != null) {
            try {
                extraPids = extraPidsFuture.get();
            } catch (ExecutionException e) {
                Slog.w(TAG, "Failed to collect extra pids", e.getCause());
            } catch (InterruptedException e) {
                Slog.w(TAG, "Interrupted while collecting extra pids", e);
            }
        }
        Slog.i(TAG, "dumpStackTraces extraPids=" + extraPids);

        if (extraPids != null) {
            if (latencyTracker != null) {
                latencyTracker.dumpingExtraPidsStarted();
            }
            for (int pid : extraPids) {
                Slog.i(TAG, "Collecting stacks for extra pid " + pid);
                final long timeTaken = dumpJavaTracesTombstoned(pid, tracesFile, remainingTime,
                        latencyTracker);
                remainingTime -= timeTaken;
                if (remainingTime <= 0) {
                    Slog.e(TAG, "Aborting stack trace dump (current extra pid=" + pid
                            + "); deadline exceeded.");
                    return firstPidEnd;
                }

                if (DEBUG_ANR) {
                    Slog.d(TAG, "Done with extra pid " + pid + " in " + timeTaken + "ms");
                }
            }
            if (latencyTracker != null) {
                latencyTracker.dumpingExtraPidsEnded();
            }
        }
        // Append the dumping footer with the current uptime
        appendtoANRFile(tracesFile, "----- dumping ended at " + SystemClock.uptimeMillis() + "\n");
        Slog.i(TAG, "Done dumping");

        return firstPidEnd;
    }

    /**
     * Dumps the supplied pid to a temporary file.
     * @param pid the PID to be dumped
     * @param latencyTracker the latency tracker instance of the current ANR.
     */
    public static File dumpStackTracesTempFile(int pid, AnrLatencyTracker latencyTracker) {
        try {
            if (latencyTracker != null) {
                latencyTracker.dumpStackTracesTempFileStarted();
            }

            File tmpTracesFile;
            try {
                tmpTracesFile = File.createTempFile(ANR_TEMP_FILE_PREFIX, ".txt",
                        new File(ANR_TRACE_DIR));
                Slog.d(TAG, "created ANR temporary file:" + tmpTracesFile.getAbsolutePath());
            } catch (IOException e) {
                Slog.w(TAG, "Exception creating temporary ANR dump file:", e);
                if (latencyTracker != null) {
                    latencyTracker.dumpStackTracesTempFileCreationFailed();
                }
                return null;
            }

            Slog.i(TAG, "Collecting stacks for pid " + pid + " into temporary file "
                    + tmpTracesFile.getName());
            if (latencyTracker != null) {
                latencyTracker.dumpingPidStarted(pid);
            }
            final long timeTaken = dumpJavaTracesTombstoned(pid, tmpTracesFile.getAbsolutePath(),
                    TEMP_DUMP_TIME_LIMIT);
            if (latencyTracker != null) {
                latencyTracker.dumpingPidEnded();
            }
            if (TEMP_DUMP_TIME_LIMIT <= timeTaken) {
                Slog.e(TAG, "Aborted stack trace dump (current primary pid=" + pid
                        + "); deadline exceeded.");
                if (latencyTracker != null) {
                    latencyTracker.dumpStackTracesTempFileTimedOut();
                }
            }
            if (DEBUG_ANR) {
                Slog.d(TAG, "Done with primary pid " + pid + " in " + timeTaken + "ms"
                        + " dumped into temporary file " + tmpTracesFile.getName());
            }
            return tmpTracesFile;
        } finally {
            if (latencyTracker != null) {
                latencyTracker.dumpStackTracesTempFileEnded();
            }
        }
    }

    private static boolean copyFirstPidTempDump(String tracesFile, Future<File> firstPidFilePromise,
            long timeLimitMs, AnrLatencyTracker latencyTracker) {

        boolean copySucceeded = false;
        try (FileOutputStream fos = new FileOutputStream(tracesFile, true))  {
            if (latencyTracker != null) {
                latencyTracker.copyingFirstPidStarted();
            }
            final File tempfile = firstPidFilePromise.get(timeLimitMs, TimeUnit.MILLISECONDS);
            if (tempfile != null) {
                Files.copy(tempfile.toPath(), fos);
                // Delete the temporary first pid dump file
                tempfile.delete();
                copySucceeded = true;
                return copySucceeded;
            }
            return false;
        } catch (ExecutionException e) {
            Slog.w(TAG, "Failed to collect the first pid's predump to the main ANR file",
                    e.getCause());
            return false;
        } catch (InterruptedException e) {
            Slog.w(TAG, "Interrupted while collecting the first pid's predump"
                    + " to the main ANR file", e);
            return false;
        } catch (IOException e) {
            Slog.w(TAG, "Failed to read the first pid's predump file", e);
            return false;
        } catch (TimeoutException e) {
            Slog.w(TAG, "Copying the first pid timed out", e);
            return false;
        } finally {
            if (latencyTracker != null) {
                latencyTracker.copyingFirstPidEnded(copySucceeded);
            }
        }
    }

    private static synchronized File createAnrDumpFile(File tracesDir) throws IOException {
        final String formattedDate = ANR_FILE_DATE_FORMAT.format(new Date());
        final File anrFile = new File(tracesDir, ANR_FILE_PREFIX + formattedDate);

        if (anrFile.createNewFile()) {
            FileUtils.setPermissions(anrFile.getAbsolutePath(), 0600, -1, -1); // -rw-------
            return anrFile;
        } else {
            throw new IOException("Unable to create ANR dump file: createNewFile failed");
        }
    }

    private static ArrayList<Integer> getExtraPids(ProcessCpuTracker processCpuTracker,
            SparseBooleanArray lastPids, AnrLatencyTracker latencyTracker) {
        if (latencyTracker != null) {
            latencyTracker.processCpuTrackerMethodsCalled();
        }
        ArrayList<Integer> extraPids = new ArrayList<>();
        synchronized (processCpuTracker) {
            processCpuTracker.init();
        }
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {
        }

        synchronized (processCpuTracker) {
            processCpuTracker.update();
            // We'll take the stack crawls of just the top apps using CPU.
            final int workingStatsNumber = processCpuTracker.countWorkingStats();
            for (int i = 0; i < workingStatsNumber && extraPids.size() < 2; i++) {
                ProcessCpuTracker.Stats stats = processCpuTracker.getWorkingStats(i);
                if (lastPids.indexOfKey(stats.pid) >= 0) {
                    if (DEBUG_ANR) {
                        Slog.d(TAG, "Collecting stacks for extra pid " + stats.pid);
                    }

                    extraPids.add(stats.pid);
                } else {
                    Slog.i(TAG,
                            "Skipping next CPU consuming process, not a java proc: "
                            + stats.pid);
                }
            }
        }
        if (latencyTracker != null) {
            latencyTracker.processCpuTrackerMethodsReturned();
        }
        return extraPids;
    }

    /**
     * Prune all trace files that are more than a day old.
     *
     * NOTE: It might make sense to move this functionality to tombstoned eventually, along with a
     * shift away from anr_XX and tombstone_XX to a more descriptive name. We do it here for now
     * since it's the system_server that creates trace files for most ANRs.
     */
    private static void maybePruneOldTraces(File tracesDir) {
        final File[] files = tracesDir.listFiles();
        if (files == null) return;

        final int max = SystemProperties.getInt("tombstoned.max_anr_count", 64);
        final long now = System.currentTimeMillis();
        try {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            for (int i = 0; i < files.length; ++i) {
                if (i > max || (now - files[i].lastModified()) > DAY_IN_MILLIS) {
                    if (!files[i].delete()) {
                        Slog.w(TAG, "Unable to prune stale trace file: " + files[i]);
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            // The modification times changed while we were sorting. Bail...
            // https://issuetracker.google.com/169836837
            Slog.w(TAG, "tombstone modification times changed while sorting; not pruning", e);
        }
    }

    private static long dumpJavaTracesTombstoned(int pid, String fileName, long timeoutMs,
            AnrLatencyTracker latencyTracker) {
        try {
            if (latencyTracker != null) {
                latencyTracker.dumpingPidStarted(pid);
            }
            return dumpJavaTracesTombstoned(pid, fileName, timeoutMs);
        } finally {
            if (latencyTracker != null) {
                latencyTracker.dumpingPidEnded();
            }
        }
    }

    /**
     * Dump java traces for process {@code pid} to the specified file. If java trace dumping
     * fails, a native backtrace is attempted. Note that the timeout {@code timeoutMs} only applies
     * to the java section of the trace, a further {@code NATIVE_DUMP_TIMEOUT_MS} might be spent
     * attempting to obtain native traces in the case of a failure. Returns the total time spent
     * capturing traces.
     */
    private static long dumpJavaTracesTombstoned(int pid, String fileName, long timeoutMs) {
        final long timeStart = SystemClock.elapsedRealtime();
        int headerSize = writeUptimeStartHeaderForPid(pid, fileName);
        boolean javaSuccess = Debug.dumpJavaBacktraceToFileTimeout(pid, fileName,
                (int) (timeoutMs / 1000));
        if (javaSuccess) {
            // Check that something is in the file, actually. Try-catch should not be necessary,
            // but better safe than sorry.
            try {
                long size = new File(fileName).length();
                if ((size - headerSize) < JAVA_DUMP_MINIMUM_SIZE) {
                    Slog.w(TAG, "Successfully created Java ANR file is empty!");
                    javaSuccess = false;
                }
            } catch (Exception e) {
                Slog.w(TAG, "Unable to get ANR file size", e);
                javaSuccess = false;
            }
        }
        if (!javaSuccess) {
            Slog.w(TAG, "Dumping Java threads failed, initiating native stack dump.");
            if (!Debug.dumpNativeBacktraceToFileTimeout(pid, fileName,
                    (NATIVE_DUMP_TIMEOUT_MS / 1000))) {
                Slog.w(TAG, "Native stack dump failed!");
            }
        }

        return SystemClock.elapsedRealtime() - timeStart;
    }

    private static int appendtoANRFile(String fileName, String text) {
        try (FileOutputStream fos = new FileOutputStream(fileName, true)) {
            byte[] header = text.getBytes(StandardCharsets.UTF_8);
            fos.write(header);
            return header.length;
        } catch (IOException e) {
            Slog.w(TAG, "Exception writing to ANR dump file:", e);
            return 0;
        }
    }

    /*
     * Writes a header containing the process id and the current system uptime.
     */
    private static int writeUptimeStartHeaderForPid(int pid, String fileName) {
        return appendtoANRFile(fileName, "----- dumping pid: " + pid + " at "
            + SystemClock.uptimeMillis() + "\n");
    }

    private static ArrayList<Integer> collectPids(Future<ArrayList<Integer>> pidsFuture,
            String logName) {

        ArrayList<Integer> pids = null;

        if (pidsFuture == null) {
            return pids;
        }
        try {
            pids = pidsFuture.get();
        } catch (ExecutionException e) {
            Slog.w(TAG, "Failed to collect " + logName, e.getCause());
        } catch (InterruptedException e) {
            Slog.w(TAG, "Interrupted while collecting " + logName , e);
        }
        return pids;
    }

}
