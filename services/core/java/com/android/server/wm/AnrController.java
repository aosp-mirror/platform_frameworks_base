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
package com.android.server.wm;


import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static com.android.server.wm.ActivityRecord.INVALID_PID;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.NonNull;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.view.InputApplicationHandle;

import com.android.server.am.ActivityManagerService;
import com.android.server.criticalevents.CriticalEventLog;

import java.io.File;
import java.util.ArrayList;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Translates input channel tokens and app tokens to ProcessRecords and PIDs that AMS can use to
 * blame unresponsive apps. This class also handles dumping WMS state when an app becomes
 * unresponsive.
 */
class AnrController {
    /** Prevent spamming the traces because pre-dump cannot aware duplicated ANR. */
    private static final long PRE_DUMP_MIN_INTERVAL_MS = TimeUnit.SECONDS.toMillis(20);
    /** The timeout to detect if a monitor is held for a while. */
    private static final long PRE_DUMP_MONITOR_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(1);
    /** The last time pre-dump was executed. */
    private volatile long mLastPreDumpTimeMs;

    private final SparseArray<ActivityRecord> mUnresponsiveAppByDisplay = new SparseArray<>();

    private final WindowManagerService mService;
    AnrController(WindowManagerService service) {
        mService = service;
    }

    void notifyAppUnresponsive(InputApplicationHandle applicationHandle, String reason) {
        preDumpIfLockTooSlow();
        final ActivityRecord activity;
        synchronized (mService.mGlobalLock) {
            activity = ActivityRecord.forTokenLocked(applicationHandle.token);
            if (activity == null) {
                Slog.e(TAG_WM, "Unknown app appToken:" + applicationHandle.name
                        + ". Dropping notifyNoFocusedWindowAnr request");
                return;
            }
            Slog.i(TAG_WM, "ANR in " + activity.getName() + ".  Reason: " + reason);
            dumpAnrStateLocked(activity, null /* windowState */, reason);
            mUnresponsiveAppByDisplay.put(activity.getDisplayId(), activity);
        }
        activity.inputDispatchingTimedOut(reason, INVALID_PID);
    }


    /**
     * Notify a window was unresponsive.
     *
     * @param token - the input token of the window
     * @param pid - the pid of the window, if known
     * @param reason - the reason for the window being unresponsive
     */
    void notifyWindowUnresponsive(@NonNull IBinder token, @NonNull OptionalInt pid,
            @NonNull String reason) {
        if (notifyWindowUnresponsive(token, reason)) {
            return;
        }
        if (!pid.isPresent()) {
            Slog.w(TAG_WM, "Failed to notify that window token=" + token + " was unresponsive.");
            return;
        }
        notifyWindowUnresponsive(pid.getAsInt(), reason);
    }

    /**
     * Notify a window identified by its input token was unresponsive.
     *
     * @return true if the window was identified by the given input token and the request was
     *         handled, false otherwise.
     */
    private boolean notifyWindowUnresponsive(@NonNull IBinder inputToken, String reason) {
        preDumpIfLockTooSlow();
        final int pid;
        final boolean aboveSystem;
        final ActivityRecord activity;
        synchronized (mService.mGlobalLock) {
            InputTarget target = mService.getInputTargetFromToken(inputToken);
            if (target == null) {
                return false;
            }
            WindowState windowState = target.getWindowState();
            pid = target.getPid();
            // Blame the activity if the input token belongs to the window. If the target is
            // embedded, then we will blame the pid instead.
            activity = (windowState.mInputChannelToken == inputToken)
                    ? windowState.mActivityRecord : null;
            Slog.i(TAG_WM, "ANR in " + target + ". Reason:" + reason);
            aboveSystem = isWindowAboveSystem(windowState);
            dumpAnrStateLocked(activity, windowState, reason);
        }
        if (activity != null) {
            activity.inputDispatchingTimedOut(reason, pid);
        } else {
            mService.mAmInternal.inputDispatchingTimedOut(pid, aboveSystem, reason);
        }
        return true;
    }

    /**
     * Notify a window owned by the provided pid was unresponsive.
     */
    private void notifyWindowUnresponsive(int pid, String reason) {
        Slog.i(TAG_WM, "ANR in input window owned by pid=" + pid + ". Reason: " + reason);
        dumpAnrStateLocked(null /* activity */, null /* windowState */, reason);

        // We cannot determine the z-order of the window, so place the anr dialog as high
        // as possible.
        mService.mAmInternal.inputDispatchingTimedOut(pid, true /*aboveSystem*/, reason);
    }

    /**
     * Notify a window was responsive after previously being unresponsive.
     *
     * @param token - the input token of the window
     * @param pid - the pid of the window, if known
     */
    void notifyWindowResponsive(@NonNull IBinder token, @NonNull OptionalInt pid) {
        if (notifyWindowResponsive(token)) {
            return;
        }
        if (!pid.isPresent()) {
            Slog.w(TAG_WM, "Failed to notify that window token=" + token + " was responsive.");
            return;
        }
        notifyWindowResponsive(pid.getAsInt());
    }

    /**
     * Notify a window identified by its input token was responsive after previously being
     * unresponsive.
     *
     * @return true if the window was identified by the given input token and the request was
     *         handled, false otherwise.
     */
    private boolean notifyWindowResponsive(@NonNull IBinder inputToken) {
        final int pid;
        synchronized (mService.mGlobalLock) {
            InputTarget target = mService.getInputTargetFromToken(inputToken);
            if (target == null) {
                return false;
            }
            pid = target.getPid();
        }
        mService.mAmInternal.inputDispatchingResumed(pid);
        return true;
    }

    /**
     * Notify a window owned by the provided pid was responsive after previously being unresponsive.
     */
    private void notifyWindowResponsive(int pid) {
        mService.mAmInternal.inputDispatchingResumed(pid);
    }

    /**
     * If we reported an unresponsive apps to AMS, notify AMS that the app is now responsive if a
     * window belonging to the app gets focused.
     * <p>
     * @param newFocus new focused window
     */
    void onFocusChanged(WindowState newFocus) {
        ActivityRecord unresponsiveApp;
        synchronized (mService.mGlobalLock) {
            unresponsiveApp = mUnresponsiveAppByDisplay.get(newFocus.getDisplayId());
            if (unresponsiveApp == null || unresponsiveApp != newFocus.mActivityRecord) {
                return;
            }
        }
        mService.mAmInternal.inputDispatchingResumed(unresponsiveApp.getPid());
    }

    /**
     * Pre-dump stack trace if the locks of activity manager or window manager (they may be locked
     * in the path of reporting ANR) cannot be acquired in time. That provides the stack traces
     * before the real blocking symptom has gone.
     * <p>
     * Do not hold the {@link WindowManagerGlobalLock} while calling this method.
     */
    private void preDumpIfLockTooSlow() {
        if (!Build.IS_DEBUGGABLE)  {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        if (mLastPreDumpTimeMs > 0 && now - mLastPreDumpTimeMs < PRE_DUMP_MIN_INTERVAL_MS) {
            return;
        }

        final boolean[] shouldDumpSf = { true };
        final ArrayMap<String, Runnable> monitors = new ArrayMap<>(2);
        monitors.put(TAG_WM, mService::monitor);
        monitors.put("ActivityManager", mService.mAmInternal::monitor);
        final CountDownLatch latch = new CountDownLatch(monitors.size());
        // The pre-dump will execute if one of the monitors doesn't complete within the timeout.
        for (int i = 0; i < monitors.size(); i++) {
            final String name = monitors.keyAt(i);
            final Runnable monitor = monitors.valueAt(i);
            // Always create new thread to avoid noise of existing threads. Suppose here won't
            // create too many threads because it means that watchdog will be triggered first.
            new Thread() {
                @Override
                public void run() {
                    monitor.run();
                    latch.countDown();
                    final long elapsed = SystemClock.uptimeMillis() - now;
                    if (elapsed > PRE_DUMP_MONITOR_TIMEOUT_MS) {
                        Slog.i(TAG_WM, "Pre-dump acquired " + name + " in " + elapsed + "ms");
                    } else if (TAG_WM.equals(name)) {
                        // Window manager is the main client of SurfaceFlinger. If window manager
                        // is responsive, the stack traces of SurfaceFlinger may not be important.
                        shouldDumpSf[0] = false;
                    }
                };
            }.start();
        }
        try {
            if (latch.await(PRE_DUMP_MONITOR_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException ignored) { }
        mLastPreDumpTimeMs = now;
        Slog.i(TAG_WM, "Pre-dump for unresponsive");

        final ArrayList<Integer> firstPids = new ArrayList<>(1);
        firstPids.add(WindowManagerService.MY_PID);
        ArrayList<Integer> nativePids = null;
        final int[] pids = shouldDumpSf[0]
                ? Process.getPidsForCommands(new String[] { "/system/bin/surfaceflinger" })
                : null;
        if (pids != null) {
            nativePids = new ArrayList<>(1);
            for (int pid : pids) {
                nativePids.add(pid);
            }
        }

        String criticalEvents = CriticalEventLog.getInstance().logLinesForSystemServerTraceFile();
        final File tracesFile = ActivityManagerService.dumpStackTraces(firstPids,
                null /* processCpuTracker */, null /* lastPids */, nativePids,
                null /* logExceptionCreatingFile */, "Pre-dump", criticalEvents);
        if (tracesFile != null) {
            tracesFile.renameTo(new File(tracesFile.getParent(), tracesFile.getName() + "_pre"));
        }
    }

    private void dumpAnrStateLocked(ActivityRecord activity, WindowState windowState,
                                    String reason) {
        mService.saveANRStateLocked(activity, windowState, reason);
        mService.mAtmService.saveANRState(reason);
    }

    private boolean isWindowAboveSystem(@NonNull WindowState windowState) {
        int systemAlertLayer = mService.mPolicy.getWindowLayerFromTypeLw(
                TYPE_APPLICATION_OVERLAY, windowState.mOwnerCanAddInternalSystemWindow);
        return windowState.mBaseLayer > systemAlertLayer;
    }
}
