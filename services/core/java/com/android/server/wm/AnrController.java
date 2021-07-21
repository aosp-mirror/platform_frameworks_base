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

import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.view.InputApplicationHandle;

import com.android.server.am.ActivityManagerService;
import com.android.server.wm.EmbeddedWindowController.EmbeddedWindow;

import java.io.File;
import java.util.ArrayList;
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

    void notifyWindowUnresponsive(IBinder inputToken, String reason) {
        preDumpIfLockTooSlow();
        final int pid;
        final boolean aboveSystem;
        final ActivityRecord activity;
        synchronized (mService.mGlobalLock) {
            WindowState windowState = mService.mInputToWindowMap.get(inputToken);
            if (windowState != null) {
                pid = windowState.mSession.mPid;
                activity = windowState.mActivityRecord;
                Slog.i(TAG_WM, "ANR in " + windowState.mAttrs.getTitle() + ". Reason:" + reason);
            } else {
                EmbeddedWindow embeddedWindow = mService.mEmbeddedWindowController.get(inputToken);
                if (embeddedWindow == null) {
                    Slog.e(TAG_WM, "Unknown token, dropping notifyConnectionUnresponsive request");
                    return;
                }
                pid = embeddedWindow.mOwnerPid;
                windowState = embeddedWindow.mHostWindowState;
                activity = null; // Don't blame the host process, instead blame the embedded pid.
            }
            aboveSystem = isWindowAboveSystem(windowState);
            dumpAnrStateLocked(activity, windowState, reason);
        }
        if (activity != null) {
            activity.inputDispatchingTimedOut(reason, pid);
        } else {
            mService.mAmInternal.inputDispatchingTimedOut(pid, aboveSystem, reason);
        }
    }

    void notifyWindowResponsive(IBinder inputToken) {
        final int pid;
        synchronized (mService.mGlobalLock) {
            WindowState windowState = mService.mInputToWindowMap.get(inputToken);
            if (windowState != null) {
                pid = windowState.mSession.mPid;
            } else {
                // Check if the token belongs to an embedded window.
                EmbeddedWindow embeddedWindow = mService.mEmbeddedWindowController.get(inputToken);
                if (embeddedWindow == null) {
                    Slog.e(TAG_WM,
                            "Unknown token, dropping notifyWindowConnectionResponsive request");
                    return;
                }
                pid = embeddedWindow.mOwnerPid;
            }
        }
        mService.mAmInternal.inputDispatchingResumed(pid);
    }

    void notifyGestureMonitorUnresponsive(int gestureMonitorPid, String reason) {
        preDumpIfLockTooSlow();
        synchronized (mService.mGlobalLock) {
            Slog.i(TAG_WM, "ANR in gesture monitor owned by pid:" + gestureMonitorPid
                    + ".  Reason: " + reason);
            dumpAnrStateLocked(null /* activity */, null /* windowState */, reason);
        }
        mService.mAmInternal.inputDispatchingTimedOut(gestureMonitorPid, /* aboveSystem */ true,
                reason);
    }

    void notifyGestureMonitorResponsive(int gestureMonitorPid) {
        mService.mAmInternal.inputDispatchingResumed(gestureMonitorPid);
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
        firstPids.add(ActivityManagerService.MY_PID);
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

        final File tracesFile = ActivityManagerService.dumpStackTraces(firstPids,
                null /* processCpuTracker */, null /* lastPids */, nativePids,
                null /* logExceptionCreatingFile */);
        if (tracesFile != null) {
            tracesFile.renameTo(new File(tracesFile.getParent(), tracesFile.getName() + "_pre"));
        }
    }

    private void dumpAnrStateLocked(ActivityRecord activity, WindowState windowState,
                                    String reason) {
        mService.saveANRStateLocked(activity, windowState, reason);
        mService.mAtmInternal.saveANRState(reason);
    }

    private boolean isWindowAboveSystem(WindowState windowState) {
        if (windowState == null) {
            // If the window state is not available we cannot easily determine its z order. Try to
            // place the anr dialog as high as possible.
            return true;
        }
        int systemAlertLayer = mService.mPolicy.getWindowLayerFromTypeLw(
                TYPE_APPLICATION_OVERLAY, windowState.mOwnerCanAddInternalSystemWindow);
        return windowState.mBaseLayer > systemAlertLayer;
    }
}
