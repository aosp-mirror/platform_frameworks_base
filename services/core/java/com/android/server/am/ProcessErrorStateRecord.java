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

import static android.os.Process.SYSTEM_UID;

import static com.android.server.Watchdog.NATIVE_STACKS_OF_INTEREST;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_ANR;
import static com.android.server.am.ActivityManagerService.MY_PID;
import static com.android.server.am.ProcessRecord.TAG;
import static com.android.internal.os.ProcfsMemoryUtil.readMemorySnapshotFromProcfs;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AnrController;
import android.app.ApplicationErrorReport;
import android.app.ApplicationExitInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IncrementalStatesInfo;
import android.content.pm.PackageManagerInternal;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.incremental.IIncrementalService;
import android.os.incremental.IncrementalManager;
import android.os.incremental.IncrementalMetrics;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.CompositeRWLock;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.os.TimeoutRecord;
import com.android.internal.os.anr.AnrLatencyTracker;
import com.android.internal.util.FrameworkStatsLog;
import com.android.modules.expresslog.Counter;
import com.android.server.ResourcePressureUtil;
import com.android.server.criticalevents.CriticalEventLog;
import com.android.internal.os.ProcfsMemoryUtil.MemorySnapshot;
import com.android.server.wm.WindowProcessController;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;


/**
 * The error state of the process, such as if it's crashing/ANR etc.
 */
class ProcessErrorStateRecord {
    final ProcessRecord mApp;
    private final ActivityManagerService mService;

    private final ActivityManagerGlobalLock mProcLock;

    /**
     * True if disabled in the bad process list.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mBad;

    /**
     * Are we in the process of crashing?
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mCrashing;

    /**
     * Suppress normal auto-dismiss of crash dialog &amp; report UI?
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mForceCrashReport;

    /**
     * Does the app have a not responding dialog?
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mNotResponding;

    /**
     * The report about crash of the app, generated &amp; stored when an app gets into a crash.
     * Will be "null" when all is OK.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private ActivityManager.ProcessErrorStateInfo mCrashingReport;

    /**
     * The report about ANR of the app, generated &amp; stored when an app gets into an ANR.
     * Will be "null" when all is OK.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private ActivityManager.ProcessErrorStateInfo mNotRespondingReport;

    /**
     * Controller for error dialogs.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private final ErrorDialogController mDialogController;

    /**
     * Who will be notified of the error. This is usually an activity in the
     * app that installed the package.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private ComponentName mErrorReportReceiver;

    /**
     * ANR dialog data used to dismiss any visible ANR dialogs if the app becomes responsive.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private AppNotRespondingDialog.Data mAnrData;

    /**
     * Annotation from process killed due to an ANR.
     */
    @GuardedBy("mService")
    private String mAnrAnnotation;

    /**
     * Optional local handler to be invoked in the process crash.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private Runnable mCrashHandler;

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean isBad() {
        return mBad;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setBad(boolean bad) {
        mBad = bad;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean isCrashing() {
        return mCrashing;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setCrashing(boolean crashing) {
        mCrashing = crashing;
        mApp.getWindowProcessController().setCrashing(crashing);
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean isForceCrashReport() {
        return mForceCrashReport;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setForceCrashReport(boolean forceCrashReport) {
        mForceCrashReport = forceCrashReport;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean isNotResponding() {
        return mNotResponding;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setNotResponding(boolean notResponding) {
        mNotResponding = notResponding;
        mApp.getWindowProcessController().setNotResponding(notResponding);
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    Runnable getCrashHandler() {
        return mCrashHandler;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setCrashHandler(Runnable crashHandler) {
        mCrashHandler = crashHandler;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    ActivityManager.ProcessErrorStateInfo getCrashingReport() {
        return mCrashingReport;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setCrashingReport(ActivityManager.ProcessErrorStateInfo crashingReport) {
        mCrashingReport = crashingReport;
    }

    @GuardedBy("mService")
    String getAnrAnnotation() {
        return mAnrAnnotation;
    }

    @GuardedBy("mService")
    void setAnrAnnotation(String anrAnnotation) {
        mAnrAnnotation = anrAnnotation;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    ActivityManager.ProcessErrorStateInfo getNotRespondingReport() {
        return mNotRespondingReport;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setNotRespondingReport(ActivityManager.ProcessErrorStateInfo notRespondingReport) {
        mNotRespondingReport = notRespondingReport;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    ComponentName getErrorReportReceiver() {
        return mErrorReportReceiver;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setErrorReportReceiver(ComponentName errorReportReceiver) {
        mErrorReportReceiver = errorReportReceiver;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    ErrorDialogController getDialogController() {
        return mDialogController;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setAnrData(AppNotRespondingDialog.Data data) {
        mAnrData = data;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    AppNotRespondingDialog.Data getAnrData() {
        return mAnrData;
    }

    ProcessErrorStateRecord(ProcessRecord app) {
        mApp = app;
        mService = app.mService;
        mProcLock = mService.mProcLock;
        mDialogController = new ErrorDialogController(app);
    }

    @GuardedBy("mService")
    boolean skipAnrLocked(String annotation) {
        // PowerManager.reboot() can block for a long time, so ignore ANRs while shutting down.
        if (mService.mAtmInternal.isShuttingDown()) {
            Slog.i(TAG, "During shutdown skipping ANR: " + this + " " + annotation);
            return true;
        } else if (isNotResponding()) {
            Slog.i(TAG, "Skipping duplicate ANR: " + this + " " + annotation);
            return true;
        } else if (isCrashing()) {
            Slog.i(TAG, "Crashing app skipping ANR: " + this + " " + annotation);
            return true;
        } else if (mApp.isKilledByAm()) {
            Slog.i(TAG, "App already killed by AM skipping ANR: " + this + " " + annotation);
            return true;
        } else if (mApp.isKilled()) {
            Slog.i(TAG, "Skipping died app ANR: " + this + " " + annotation);
            return true;
        }
        return false;
    }

    void appNotResponding(String activityShortComponentName, ApplicationInfo aInfo,
            String parentShortComponentName, WindowProcessController parentProcess,
            boolean aboveSystem, TimeoutRecord timeoutRecord,
            ExecutorService auxiliaryTaskExecutor, boolean onlyDumpSelf,
            boolean isContinuousAnr, Future<File> firstPidFilePromise) {
        String annotation = timeoutRecord.mReason;
        AnrLatencyTracker latencyTracker = timeoutRecord.mLatencyTracker;
        Future<?> updateCpuStatsNowFirstCall = null;

        ArrayList<Integer> firstPids = new ArrayList<>(5);
        SparseBooleanArray lastPids = new SparseBooleanArray(20);
        ActivityManagerService.VolatileDropboxEntryStates volatileDropboxEntriyStates = null;

        // Release the expired timer preparatory to starting the dump or returning without dumping.
        timeoutRecord.closeExpiredTimer();

        if (mApp.isDebugging()) {
            Slog.i(TAG, "Skipping debugged app ANR: " + this + " " + annotation);
            return;
        }

        mApp.getWindowProcessController().appEarlyNotResponding(annotation, () -> {
            latencyTracker.waitingOnAMSLockStarted();
            synchronized (mService) {
                latencyTracker.waitingOnAMSLockEnded();
                // Store annotation here as instance below races with this killLocked.
                setAnrAnnotation(annotation);
                mApp.killLocked("anr", ApplicationExitInfo.REASON_ANR, true);
            }
        });

        long anrTime = SystemClock.uptimeMillis();

        if (isMonitorCpuUsage()) {
            updateCpuStatsNowFirstCall = auxiliaryTaskExecutor.submit(
                    () -> {
                    latencyTracker.updateCpuStatsNowCalled();
                    mService.updateCpuStatsNow();
                    latencyTracker.updateCpuStatsNowReturned();
                });

        }

        final boolean isSilentAnr;
        final int pid;
        final UUID errorId;
        latencyTracker.waitingOnAMSLockStarted();
        synchronized (mService) {
            latencyTracker.waitingOnAMSLockEnded();
            // Get the process's pid after obtaining the global lock.
            pid = mApp.getPid();
            // Store annotation here as instance above will not be hit on all paths.
            setAnrAnnotation(annotation);

            Counter.logIncrement("stability_anr.value_total_anrs");
            if (skipAnrLocked(annotation)) {
                latencyTracker.anrSkippedProcessErrorStateRecordAppNotResponding();
                Counter.logIncrement("stability_anr.value_skipped_anrs");
                return;
            }
            // In case we come through here for the same app before completing
            // this one, mark as anring now so we will bail out.
            latencyTracker.waitingOnProcLockStarted();
            synchronized (mProcLock) {
                latencyTracker.waitingOnProcLockEnded();
                setNotResponding(true);

                ZonedDateTime timestamp = null;
                if (timeoutRecord != null && timeoutRecord.mEndUptimeMillis > 0) {
                    long millisSinceEndUptimeMs = anrTime - timeoutRecord.mEndUptimeMillis;
                    timestamp = Instant.now().minusMillis(millisSinceEndUptimeMs)
                                    .atZone(ZoneId.systemDefault());
                }

                volatileDropboxEntriyStates =
                        ActivityManagerService.VolatileDropboxEntryStates
                                .withProcessFrozenStateAndTimestamp(
                                        mApp.mOptRecord.isFrozen(), timestamp);
            }

            // Log the ANR to the event log.
            EventLog.writeEvent(EventLogTags.AM_ANR, mApp.userId, pid, mApp.processName,
                    mApp.info.flags, annotation);

            if (mService.mTraceErrorLogger != null
                    && mService.mTraceErrorLogger.isAddErrorIdEnabled()) {
                errorId = mService.mTraceErrorLogger.generateErrorId();
                mService.mTraceErrorLogger.addProcessInfoAndErrorIdToTrace(
                        mApp.processName, pid, errorId);
                mService.mTraceErrorLogger.addSubjectToTrace(annotation, errorId);
            } else {
                errorId = null;
            }

            // This atom is only logged with the purpose of triggering Perfetto and the logging
            // needs to happen as close as possible to the time when the ANR is detected.
            // Also, it needs to be logged after adding the error id to the trace, to make sure
            // the error id is present in the trace when the Perfetto trace is captured.
            FrameworkStatsLog.write(FrameworkStatsLog.ANR_OCCURRED_PROCESSING_STARTED,
                    mApp.processName);

            // Dump thread traces as quickly as we can, starting with "interesting" processes.
            firstPids.add(pid);

            // Don't dump other PIDs if it's a background ANR or is requested to only dump self.
            // Note that the primary pid is added here just in case, as it should normally be
            // dumped on the early dump thread, and would only be dumped on the Anr consumer thread
            // as a fallback.
            isSilentAnr = isSilentAnr();
            if (!isSilentAnr && !onlyDumpSelf) {
                int parentPid = pid;
                if (parentProcess != null && parentProcess.getPid() > 0) {
                    parentPid = parentProcess.getPid();
                }
                if (parentPid != pid) firstPids.add(parentPid);

                if (MY_PID != pid && MY_PID != parentPid) firstPids.add(MY_PID);

                final int ppid = parentPid;
                mService.mProcessList.forEachLruProcessesLOSP(false, r -> {
                    if (r != null && r.getThread() != null) {
                        int myPid = r.getPid();
                        if (myPid > 0 && myPid != pid && myPid != ppid && myPid != MY_PID) {
                            if (r.isPersistent()) {
                                firstPids.add(myPid);
                                if (DEBUG_ANR) Slog.i(TAG, "Adding persistent proc: " + r);
                            } else if (r.mServices.isTreatedLikeActivity()) {
                                firstPids.add(myPid);
                                if (DEBUG_ANR) Slog.i(TAG, "Adding likely IME: " + r);
                            } else {
                                lastPids.put(myPid, true);
                                if (DEBUG_ANR) Slog.i(TAG, "Adding ANR proc: " + r);
                            }
                        }
                    }
                });
            }
        }
        // Build memory headers for the ANRing process.
        LinkedHashMap<String, String> memoryHeaders = buildMemoryHeadersFor(pid);

        // Get critical event log before logging the ANR so that it doesn't occur in the log.
        latencyTracker.criticalEventLogStarted();
        final String criticalEventLog =
                CriticalEventLog.getInstance().logLinesForTraceFile(
                        mApp.getProcessClassEnum(), mApp.processName, mApp.uid);
        latencyTracker.criticalEventLogEnded();
        CriticalEventLog.getInstance().logAnr(annotation, mApp.getProcessClassEnum(),
                mApp.processName, mApp.uid, mApp.mPid);

        // Log the ANR to the main log.
        StringBuilder info = new StringBuilder();
        info.setLength(0);
        info.append("ANR in ").append(mApp.processName);
        if (activityShortComponentName != null) {
            info.append(" (").append(activityShortComponentName).append(")");
        }
        info.append("\n");
        info.append("PID: ").append(pid).append("\n");
        if (annotation != null) {
            info.append("Reason: ").append(annotation).append("\n");
        }
        if (parentShortComponentName != null
                && parentShortComponentName.equals(activityShortComponentName)) {
            info.append("Parent: ").append(parentShortComponentName).append("\n");
        }
        if (errorId != null) {
            info.append("ErrorId: ").append(errorId.toString()).append("\n");
        }
        info.append("Frozen: ").append(mApp.mOptRecord.isFrozen()).append("\n");

        // Retrieve controller with max ANR delay from AnrControllers
        // Note that we retrieve the controller before dumping stacks because dumping stacks can
        // take a few seconds, after which the cause of the ANR delay might have completed and
        // there might no longer be a valid ANR controller to cancel the dialog in that case
        AnrController anrController = mService.mActivityTaskManager.getAnrController(aInfo);
        long anrDialogDelayMs = 0;
        if (anrController != null) {
            String packageName = aInfo.packageName;
            int uid = aInfo.uid;
            anrDialogDelayMs = anrController.getAnrDelayMillis(packageName, uid);
            // Might execute an async binder call to a system app to show an interim
            // ANR progress UI
            anrController.onAnrDelayStarted(packageName, uid);
            Slog.i(TAG, "ANR delay of " + anrDialogDelayMs + "ms started for " + packageName);
        }

        StringBuilder report = new StringBuilder();

        latencyTracker.currentPsiStateCalled();
        String currentPsiState = ResourcePressureUtil.currentPsiState();
        latencyTracker.currentPsiStateReturned();
        report.append(currentPsiState);
        // The 'processCpuTracker' variable is a shared resource that might be initialized and
        // updated in a different thread. In order to prevent thread visibility issues, which
        // can occur when one thread does not immediately see the changes made to
        // 'processCpuTracker' by another thread, it is necessary to use synchronization whenever
        // 'processCpuTracker' is accessed or modified.
        ProcessCpuTracker processCpuTracker = new ProcessCpuTracker(true);

        // We push the native pids collection task to the helper thread through
        // the Anr auxiliary task executor, and wait on it later after dumping the first pids
        Future<ArrayList<Integer>> nativePidsFuture =
                auxiliaryTaskExecutor.submit(
                    () -> {
                        latencyTracker.nativePidCollectionStarted();
                        // don't dump native PIDs for background ANRs unless
                        // it is the process of interest
                        String[] nativeProcs = null;
                        boolean isSystemApp = mApp.info.isSystemApp() || mApp.info.isSystemExt();
                        // Do not collect system daemons dumps as this is not likely to be useful
                        // for non-system apps.
                        if (!isSystemApp || isSilentAnr || onlyDumpSelf) {
                            for (int i = 0; i < NATIVE_STACKS_OF_INTEREST.length; i++) {
                                if (NATIVE_STACKS_OF_INTEREST[i].equals(mApp.processName)) {
                                    nativeProcs = new String[] { mApp.processName };
                                    break;
                                }
                            }
                        } else {
                            nativeProcs = NATIVE_STACKS_OF_INTEREST;
                        }

                        int[] pids = nativeProcs == null
                                ? null : Process.getPidsForCommands(nativeProcs);
                        ArrayList<Integer> nativePids = null;

                        if (pids != null) {
                            nativePids = new ArrayList<>(pids.length);
                            for (int i : pids) {
                                nativePids.add(i);
                            }
                        }
                        latencyTracker.nativePidCollectionEnded();
                        return nativePids;
                    });

        // For background ANRs, don't pass the ProcessCpuTracker to
        // avoid spending 1/2 second collecting stats to rank lastPids.
        StringWriter tracesFileException = new StringWriter();
        // To hold the start and end offset to the ANR trace file respectively.
        final AtomicLong firstPidEndOffset = new AtomicLong(-1);
        File tracesFile = StackTracesDumpHelper.dumpStackTraces(firstPids,
                isSilentAnr ? null : processCpuTracker, isSilentAnr ? null : lastPids,
                nativePidsFuture, tracesFileException, firstPidEndOffset, annotation,
                criticalEventLog, memoryHeaders, auxiliaryTaskExecutor, firstPidFilePromise,
                latencyTracker);

        if (isMonitorCpuUsage()) {
            // Wait for the first call to finish
            try {
                updateCpuStatsNowFirstCall.get();
            } catch (ExecutionException e) {
                Slog.w(TAG, "Failed to update the CPU stats", e.getCause());
            } catch (InterruptedException e) {
                Slog.w(TAG, "Interrupted while updating the CPU stats", e);
            }
            mService.updateCpuStatsNow();
            mService.mAppProfiler.printCurrentCpuState(report, anrTime);
            synchronized (processCpuTracker) {
                info.append(processCpuTracker.printCurrentLoad());
            }
            info.append(report);
        }
        report.append(tracesFileException.getBuffer());

        synchronized (processCpuTracker) {
            info.append(processCpuTracker.printCurrentState(anrTime));
        }

        Slog.e(TAG, info.toString());
        if (tracesFile == null) {
            // There is no trace file, so dump (only) the alleged culprit's threads to the log
            Process.sendSignal(pid, Process.SIGNAL_QUIT);
        } else if (firstPidEndOffset.get() > 0) {
            // We've dumped into the trace file successfully
            // We pass the start and end offsets of the first section of
            // the ANR file (the headers and first process dump)
            final long startOffset = 0L;
            final long endOffset = firstPidEndOffset.get();
            mService.mProcessList.mAppExitInfoTracker.scheduleLogAnrTrace(
                    pid, mApp.uid, mApp.getPackageList(), tracesFile, startOffset, endOffset);
        }

        // Check if package is still being loaded
        float loadingProgress = 1;
        IncrementalMetrics incrementalMetrics = null;
        final PackageManagerInternal packageManagerInternal = mService.getPackageManagerInternal();
        if (mApp.info != null && mApp.info.packageName != null && packageManagerInternal != null) {
            IncrementalStatesInfo incrementalStatesInfo =
                    packageManagerInternal.getIncrementalStatesInfo(
                            mApp.info.packageName, SYSTEM_UID, mApp.userId);
            if (incrementalStatesInfo != null) {
                loadingProgress = incrementalStatesInfo.getProgress();
            }
            final String codePath = mApp.info.getCodePath();
            if (codePath != null && !codePath.isEmpty()
                    && IncrementalManager.isIncrementalPath(codePath)) {
                // Report in the main log that the incremental package is still loading
                Slog.e(TAG, "App ANR on incremental package " + mApp.info.packageName
                        + " which is " + ((int) (loadingProgress * 100)) + "% loaded.");
                final IBinder incrementalService = ServiceManager.getService(
                        Context.INCREMENTAL_SERVICE);
                if (incrementalService != null) {
                    final IncrementalManager incrementalManager = new IncrementalManager(
                            IIncrementalService.Stub.asInterface(incrementalService));
                    incrementalMetrics = incrementalManager.getMetrics(codePath);
                }
            }
        }
        if (incrementalMetrics != null) {
            // Report in the main log about the incremental package
            info.append("Package is ").append((int) (loadingProgress * 100)).append("% loaded.\n");
        }

        FrameworkStatsLog.write(FrameworkStatsLog.ANR_OCCURRED, mApp.uid, mApp.processName,
                activityShortComponentName == null ? "unknown" : activityShortComponentName,
                annotation,
                (mApp.info != null) ? (mApp.info.isInstantApp()
                        ? FrameworkStatsLog.ANROCCURRED__IS_INSTANT_APP__TRUE
                        : FrameworkStatsLog.ANROCCURRED__IS_INSTANT_APP__FALSE)
                        : FrameworkStatsLog.ANROCCURRED__IS_INSTANT_APP__UNAVAILABLE,
                mApp.isInterestingToUserLocked()
                        ? FrameworkStatsLog.ANROCCURRED__FOREGROUND_STATE__FOREGROUND
                        : FrameworkStatsLog.ANROCCURRED__FOREGROUND_STATE__BACKGROUND,
                mApp.getProcessClassEnum(),
                (mApp.info != null) ? mApp.info.packageName : "",
                incrementalMetrics != null /* isIncremental */, loadingProgress,
                incrementalMetrics != null ? incrementalMetrics.getMillisSinceOldestPendingRead()
                        : -1,
                incrementalMetrics != null ? incrementalMetrics.getStorageHealthStatusCode()
                        : -1,
                incrementalMetrics != null ? incrementalMetrics.getDataLoaderStatusCode()
                        : -1,
                incrementalMetrics != null && incrementalMetrics.getReadLogsEnabled(),
                incrementalMetrics != null ? incrementalMetrics.getMillisSinceLastDataLoaderBind()
                        : -1,
                incrementalMetrics != null ? incrementalMetrics.getDataLoaderBindDelayMillis()
                        : -1,
                incrementalMetrics != null ? incrementalMetrics.getTotalDelayedReads()
                        : -1,
                incrementalMetrics != null ? incrementalMetrics.getTotalFailedReads()
                        : -1,
                incrementalMetrics != null ? incrementalMetrics.getLastReadErrorUid()
                        : -1,
                incrementalMetrics != null ? incrementalMetrics.getMillisSinceLastReadError()
                        : -1,
                incrementalMetrics != null ? incrementalMetrics.getLastReadErrorNumber()
                        : 0,
                incrementalMetrics != null ? incrementalMetrics.getTotalDelayedReadsDurationMillis()
                        : -1);
        final ProcessRecord parentPr = parentProcess != null
                ? (ProcessRecord) parentProcess.mOwner : null;
        mService.addErrorToDropBox("anr", mApp, mApp.processName, activityShortComponentName,
                parentShortComponentName, parentPr, null, report.toString(), tracesFile,
                null, new Float(loadingProgress), incrementalMetrics, errorId,
                volatileDropboxEntriyStates);

        if (mApp.getWindowProcessController().appNotResponding(info.toString(),
                () -> {
                    synchronized (mService) {
                        mApp.killLocked("anr", ApplicationExitInfo.REASON_ANR, true);
                    }
                },
                () -> {
                    synchronized (mService) {
                        mService.mServices.scheduleServiceTimeoutLocked(mApp);
                    }
                })) {
            return;
        }

        synchronized (mService) {
            // mBatteryStatsService can be null if the AMS is constructed with injector only. This
            // will only happen in tests.
            if (mService.mBatteryStatsService != null) {
                mService.mBatteryStatsService.noteProcessAnr(mApp.processName, mApp.uid);
            }

            if (isSilentAnr() && !mApp.isDebugging()) {
                mApp.killLocked("bg anr", ApplicationExitInfo.REASON_ANR, true);
                return;
            }

            synchronized (mProcLock) {
                // Set the app's notResponding state, and look up the errorReportReceiver
                makeAppNotRespondingLSP(activityShortComponentName,
                        annotation != null ? "ANR " + annotation : "ANR", info.toString());
                mDialogController.setAnrController(anrController);
            }

            // mUiHandler can be null if the AMS is constructed with injector only. This will only
            // happen in tests.
            if (mService.mUiHandler != null) {
                // Bring up the infamous App Not Responding dialog
                Message msg = Message.obtain();
                msg.what = ActivityManagerService.SHOW_NOT_RESPONDING_UI_MSG;
                msg.obj = new AppNotRespondingDialog.Data(mApp, aInfo, aboveSystem,
                        isContinuousAnr);

                mService.mUiHandler.sendMessageDelayed(msg, anrDialogDelayMs);
            }
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    private void makeAppNotRespondingLSP(String activity, String shortMsg, String longMsg) {
        setNotResponding(true);
        // mAppErrors can be null if the AMS is constructed with injector only. This will only
        // happen in tests.
        if (mService.mAppErrors != null) {
            mNotRespondingReport = mService.mAppErrors.generateProcessError(mApp,
                    ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING,
                    activity, shortMsg, longMsg, null);
        }
        startAppProblemLSP();
        mApp.getWindowProcessController().stopFreezingActivities();
    }

    @GuardedBy({"mService", "mProcLock"})
    void startAppProblemLSP() {
        // If this app is not running under the current user, then we can't give it a report button
        // because that would require launching the report UI under a different user.
        mErrorReportReceiver = null;

        for (int userId : mService.mUserController.getCurrentProfileIds()) {
            if (mApp.userId == userId) {
                mErrorReportReceiver = ApplicationErrorReport.getErrorReportReceiver(
                        mService.mContext, mApp.info.packageName, mApp.info.flags);
            }
        }
        mService.getBroadcastQueue().onApplicationProblemLocked(mApp);
    }

    @GuardedBy("mService")
    private boolean isInterestingForBackgroundTraces() {
        // The system_server is always considered interesting.
        if (mApp.getPid() == MY_PID) {
            return true;
        }

        // A package is considered interesting if any of the following is true :
        //
        // - It's displaying an activity.
        // - It's the SystemUI.
        // - It has an overlay or a top UI visible.
        //
        // NOTE: The check whether a given ProcessRecord belongs to the systemui
        // process is a bit of a kludge, but the same pattern seems repeated at
        // several places in the system server.
        return mApp.isInterestingToUserLocked()
                || (mApp.info != null && "com.android.systemui".equals(mApp.info.packageName))
                || (mApp.mState.hasTopUi() || mApp.mState.hasOverlayUi());
    }

    private boolean getShowBackground() {
        final ContentResolver resolver = mService.mContext.getContentResolver();
        return Settings.Secure.getIntForUser(resolver,
            Settings.Secure.ANR_SHOW_BACKGROUND,
            0,
            resolver.getUserId()) != 0;
    }

    private @Nullable LinkedHashMap<String, String> buildMemoryHeadersFor(int pid) {
        if (pid <= 0) {
            Slog.i(TAG, "Memory header requested with invalid pid: " + pid);
            return null;
        }
        MemorySnapshot snapshot = readMemorySnapshotFromProcfs(pid);
        if (snapshot == null) {
            Slog.i(TAG, "Failed to get memory snapshot for pid:" + pid);
            return null;
        }

        LinkedHashMap<String, String> memoryHeaders = new LinkedHashMap<>();
        memoryHeaders.put("RssHwmKb", Integer.toString(snapshot.rssHighWaterMarkInKilobytes));
        memoryHeaders.put("RssKb", Integer.toString(snapshot.rssInKilobytes));
        memoryHeaders.put("RssAnonKb", Integer.toString(snapshot.anonRssInKilobytes));
        memoryHeaders.put("RssShmemKb", Integer.toString(snapshot.rssShmemKilobytes));
        memoryHeaders.put("VmSwapKb", Integer.toString(snapshot.swapInKilobytes));
        return memoryHeaders;
    }
    /**
     * Unless configured otherwise, swallow ANRs in background processes & kill the process.
     * Non-private access is for tests only.
     */
    @VisibleForTesting
    @GuardedBy("mService")
    boolean isSilentAnr() {
        return !getShowBackground() && !isInterestingForBackgroundTraces();
    }

    /** Non-private access is for tests only. */
    @VisibleForTesting
    boolean isMonitorCpuUsage() {
        return mService.mAppProfiler.MONITOR_CPU_USAGE;
    }

    @GuardedBy({"mService", "mProcLock"})
    void onCleanupApplicationRecordLSP() {
        // Dismiss any open dialogs.
        getDialogController().clearAllErrorDialogs();

        setCrashing(false);
        setNotResponding(false);
    }

    void dump(PrintWriter pw, String prefix, long nowUptime) {
        synchronized (mProcLock) {
            if (mCrashing || mDialogController.hasCrashDialogs() || mNotResponding
                    || mDialogController.hasAnrDialogs() || mBad) {
                pw.print(prefix);
                pw.print(" mCrashing=" + mCrashing);
                pw.print(" " + mDialogController.getCrashDialogs());
                pw.print(" mNotResponding=" + mNotResponding);
                pw.print(" " + mDialogController.getAnrDialogs());
                pw.print(" bad=" + mBad);

                // mCrashing or mNotResponding is always set before errorReportReceiver
                if (mErrorReportReceiver != null) {
                    pw.print(" errorReportReceiver=");
                    pw.print(mErrorReportReceiver.flattenToShortString());
                }
                pw.println();
            }
        }
    }
}
