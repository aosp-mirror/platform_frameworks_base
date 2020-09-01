package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static com.android.server.wm.ActivityRecord.INVALID_PID;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_INPUT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.ON_POINTER_DOWN_OUTSIDE_FOCUS;

import android.os.Build;
import android.os.Debug;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.IWindow;
import android.view.InputApplicationHandle;
import android.view.KeyEvent;
import android.view.WindowManager;

import com.android.server.am.ActivityManagerService;
import com.android.server.input.InputManagerService;
import com.android.server.wm.EmbeddedWindowController.EmbeddedWindow;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class InputManagerCallback implements InputManagerService.WindowManagerCallbacks {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "InputManagerCallback" : TAG_WM;

    /** Prevent spamming the traces because pre-dump cannot aware duplicated ANR. */
    private static final long PRE_DUMP_MIN_INTERVAL_MS = TimeUnit.SECONDS.toMillis(20);
    /** The timeout to detect if a monitor is held for a while. */
    private static final long PRE_DUMP_MONITOR_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(1);
    /** The last time pre-dump was executed. */
    private volatile long mLastPreDumpTimeMs;

    private final WindowManagerService mService;

    // Set to true when the first input device configuration change notification
    // is received to indicate that the input devices are ready.
    private final Object mInputDevicesReadyMonitor = new Object();
    private boolean mInputDevicesReady;

    // When true, prevents input dispatch from proceeding until set to false again.
    private boolean mInputDispatchFrozen;

    // The reason the input is currently frozen or null if the input isn't frozen.
    private String mInputFreezeReason = null;

    // When true, input dispatch proceeds normally.  Otherwise all events are dropped.
    // Initially false, so that input does not get dispatched until boot is finished at
    // which point the ActivityManager will enable dispatching.
    private boolean mInputDispatchEnabled;

    // TODO(b/141749603)) investigate if this can be part of client focus change dispatch
    // Tracks the currently focused window used to update pointer capture state in clients
    private AtomicReference<IWindow> mFocusedWindow = new AtomicReference<>();

    // Tracks focused window pointer capture state
    private boolean mFocusedWindowHasCapture;

    public InputManagerCallback(WindowManagerService service) {
        mService = service;
    }

    /**
     * Notifies the window manager about a broken input channel.
     *
     * Called by the InputManager.
     */
    @Override
    public void notifyInputChannelBroken(IBinder token) {
        if (token == null) {
            return;
        }

        synchronized (mService.mGlobalLock) {
            WindowState windowState = mService.mInputToWindowMap.get(token);
            if (windowState != null) {
                Slog.i(TAG_WM, "WINDOW DIED " + windowState);
                windowState.removeIfPossible();
            }
        }
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

    /**
     * Notifies the window manager about an application that is not responding.
     * Returns a new timeout to continue waiting in nanoseconds, or 0 to abort dispatch.
     *
     * Called by the InputManager.
     */
    @Override
    public long notifyANR(InputApplicationHandle inputApplicationHandle, IBinder token,
            String reason) {
        final long startTime = SystemClock.uptimeMillis();
        try {
            return notifyANRInner(inputApplicationHandle, token, reason);
        } finally {
            // Log the time because the method is called from InputDispatcher thread. It shouldn't
            // take too long that may affect input response time.
            Slog.d(TAG_WM, "notifyANR took " + (SystemClock.uptimeMillis() - startTime) + "ms");
        }
    }

    private long notifyANRInner(InputApplicationHandle inputApplicationHandle, IBinder token,
            String reason) {
        ActivityRecord activity = null;
        WindowState windowState = null;
        boolean aboveSystem = false;
        int windowPid = INVALID_PID;

        preDumpIfLockTooSlow();

        //TODO(b/141764879) Limit scope of wm lock when input calls notifyANR
        synchronized (mService.mGlobalLock) {

            // Check if we can blame a window
            if (token != null) {
                windowState = mService.mInputToWindowMap.get(token);
                if (windowState != null) {
                    activity = windowState.mActivityRecord;
                    windowPid = windowState.mSession.mPid;
                    // Figure out whether this window is layered above system windows.
                    // We need to do this here to help the activity manager know how to
                    // layer its ANR dialog.
                    aboveSystem = isWindowAboveSystem(windowState);
                }
            }

            // Check if we can blame an embedded window
            if (token != null && windowState == null) {
                EmbeddedWindow embeddedWindow = mService.mEmbeddedWindowController.get(token);
                if (embeddedWindow != null) {
                    windowPid = embeddedWindow.mOwnerPid;
                    WindowState hostWindowState = embeddedWindow.mHostWindowState;
                    if (hostWindowState == null) {
                        // The embedded window has no host window and we cannot easily determine
                        // its z order. Try to place the anr dialog as high as possible.
                        aboveSystem = true;
                    } else {
                        aboveSystem = isWindowAboveSystem(hostWindowState);
                    }
                }
            }

            // Check if we can blame an activity. If we don't have an activity to blame, pull out
            // the token passed in via input application handle. This can happen if there are no
            // focused windows but input dispatcher knows the focused app.
            if (activity == null && inputApplicationHandle != null) {
                activity = ActivityRecord.forTokenLocked(inputApplicationHandle.token);
            }

            if (windowState != null) {
                Slog.i(TAG_WM, "Input event dispatching timed out "
                        + "sending to " + windowState.mAttrs.getTitle()
                        + ".  Reason: " + reason);
            } else if (activity != null) {
                Slog.i(TAG_WM, "Input event dispatching timed out "
                        + "sending to application " + activity.stringName
                        + ".  Reason: " + reason);
            } else {
                Slog.i(TAG_WM, "Input event dispatching timed out "
                        + ".  Reason: " + reason);
            }

            mService.saveANRStateLocked(activity, windowState, reason);
        }

        // All the calls below need to happen without the WM lock held since they call into AM.
        mService.mAtmInternal.saveANRState(reason);

        if (activity != null && activity.appToken != null) {
            // Notify the activity manager about the timeout and let it decide whether
            // to abort dispatching or keep waiting.
            final boolean abort = activity.keyDispatchingTimedOut(reason, windowPid);
            if (!abort) {
                // The activity manager declined to abort dispatching.
                // Wait a bit longer and timeout again later.
                return activity.mInputDispatchingTimeoutNanos;
            }
        } else if (windowState != null || windowPid != INVALID_PID) {
            // Notify the activity manager about the timeout and let it decide whether
            // to abort dispatching or keep waiting.
            long timeout = mService.mAmInternal.inputDispatchingTimedOut(windowPid, aboveSystem,
                    reason);
            if (timeout >= 0) {
                // The activity manager declined to abort dispatching.
                // Wait a bit longer and timeout again later.
                return timeout * 1000000L; // nanoseconds
            }
        }
        return 0; // abort dispatching
    }

    private boolean isWindowAboveSystem(WindowState windowState) {
        int systemAlertLayer = mService.mPolicy.getWindowLayerFromTypeLw(
                TYPE_APPLICATION_OVERLAY, windowState.mOwnerCanAddInternalSystemWindow);
        return windowState.mBaseLayer > systemAlertLayer;
    }

    /** Notifies that the input device configuration has changed. */
    @Override
    public void notifyConfigurationChanged() {
        // TODO(multi-display): Notify proper displays that are associated with this input device.

        synchronized (mService.mGlobalLock) {
            mService.getDefaultDisplayContentLocked().sendNewConfiguration();
        }

        synchronized (mInputDevicesReadyMonitor) {
            if (!mInputDevicesReady) {
                mInputDevicesReady = true;
                mInputDevicesReadyMonitor.notifyAll();
            }
        }
    }

    /** Notifies that the lid switch changed state. */
    @Override
    public void notifyLidSwitchChanged(long whenNanos, boolean lidOpen) {
        mService.mPolicy.notifyLidSwitchChanged(whenNanos, lidOpen);
    }

    /** Notifies that the camera lens cover state has changed. */
    @Override
    public void notifyCameraLensCoverSwitchChanged(long whenNanos, boolean lensCovered) {
        mService.mPolicy.notifyCameraLensCoverSwitchChanged(whenNanos, lensCovered);
    }

    /**
     * Provides an opportunity for the window manager policy to intercept early key
     * processing as soon as the key has been read from the device.
     */
    @Override
    public int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags) {
        return mService.mPolicy.interceptKeyBeforeQueueing(event, policyFlags);
    }

    /** {@inheritDoc} */
    @Override
    public int interceptMotionBeforeQueueingNonInteractive(int displayId, long whenNanos,
            int policyFlags) {
        return mService.mPolicy.interceptMotionBeforeQueueingNonInteractive(
                displayId, whenNanos, policyFlags);
    }

    /**
     * Provides an opportunity for the window manager policy to process a key before
     * ordinary dispatch.
     */
    @Override
    public long interceptKeyBeforeDispatching(
            IBinder focusedToken, KeyEvent event, int policyFlags) {
        return mService.mPolicy.interceptKeyBeforeDispatching(focusedToken, event, policyFlags);
    }

    /**
     * Provides an opportunity for the window manager policy to process a key that
     * the application did not handle.
     */
    @Override
    public KeyEvent dispatchUnhandledKey(
            IBinder focusedToken, KeyEvent event, int policyFlags) {
        return mService.mPolicy.dispatchUnhandledKey(focusedToken, event, policyFlags);
    }

    /** Callback to get pointer layer. */
    @Override
    public int getPointerLayer() {
        return mService.mPolicy.getWindowLayerFromTypeLw(WindowManager.LayoutParams.TYPE_POINTER)
                * WindowManagerService.TYPE_LAYER_MULTIPLIER
                + WindowManagerService.TYPE_LAYER_OFFSET;
    }

    /** Callback to get pointer display id. */
    @Override
    public int getPointerDisplayId() {
        synchronized (mService.mGlobalLock) {
            // If desktop mode is not enabled, show on the default display.
            if (!mService.mForceDesktopModeOnExternalDisplays) {
                return DEFAULT_DISPLAY;
            }

            // Look for the topmost freeform display.
            int firstExternalDisplayId = DEFAULT_DISPLAY;
            for (int i = mService.mRoot.mChildren.size() - 1; i >= 0; --i) {
                final DisplayContent displayContent = mService.mRoot.mChildren.get(i);
                // Heuristic solution here. Currently when "Freeform windows" developer option is
                // enabled we automatically put secondary displays in freeform mode and emulating
                // "desktop mode". It also makes sense to show the pointer on the same display.
                if (displayContent.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
                    return displayContent.getDisplayId();
                }

                if (firstExternalDisplayId == DEFAULT_DISPLAY
                        && displayContent.getDisplayId() != DEFAULT_DISPLAY) {
                    firstExternalDisplayId = displayContent.getDisplayId();
                }
            }

            // Look for the topmost non-default display
            return firstExternalDisplayId;
        }
    }

    @Override
    public void onPointerDownOutsideFocus(IBinder touchedToken) {
        mService.mH.obtainMessage(ON_POINTER_DOWN_OUTSIDE_FOCUS, touchedToken).sendToTarget();
    }

    @Override
    public boolean notifyFocusChanged(IBinder oldToken, IBinder newToken) {
        boolean requestRefreshConfiguration = false;
        final IWindow newFocusedWindow;
        final WindowState win;

        // TODO(b/141749603) investigate if this can be part of client focus change dispatch
        synchronized (mService.mGlobalLock) {
            win = mService.mInputToWindowMap.get(newToken);
        }
        newFocusedWindow = (win != null) ? win.mClient : null;

        final IWindow focusedWindow = mFocusedWindow.get();
        if (focusedWindow != null) {
            if (newFocusedWindow != null
                    && newFocusedWindow.asBinder() == focusedWindow.asBinder()) {
                Slog.w(TAG, "notifyFocusChanged called with unchanged mFocusedWindow="
                        + focusedWindow);
                return false;
            }
            requestRefreshConfiguration = dispatchPointerCaptureChanged(focusedWindow, false);
        }
        mFocusedWindow.set(newFocusedWindow);
        return requestRefreshConfiguration;
    }

    @Override
    public boolean requestPointerCapture(IBinder windowToken, boolean enabled) {
        final IWindow focusedWindow = mFocusedWindow.get();
        if (focusedWindow == null || focusedWindow.asBinder() != windowToken) {
            Slog.e(TAG, "requestPointerCapture called for a window that has no focus: "
                    + windowToken);
            return false;
        }
        if (mFocusedWindowHasCapture == enabled) {
            Slog.i(TAG, "requestPointerCapture: already " + (enabled ? "enabled" : "disabled"));
            return false;
        }
        return dispatchPointerCaptureChanged(focusedWindow, enabled);
    }

    private boolean dispatchPointerCaptureChanged(IWindow focusedWindow, boolean enabled) {
        if (mFocusedWindowHasCapture != enabled) {
            mFocusedWindowHasCapture = enabled;
            try {
                focusedWindow.dispatchPointerCaptureChanged(enabled);
            } catch (RemoteException ex) {
                /* ignore */
            }
            return true;
        }
        return false;
    }

    /** Waits until the built-in input devices have been configured. */
    public boolean waitForInputDevicesReady(long timeoutMillis) {
        synchronized (mInputDevicesReadyMonitor) {
            if (!mInputDevicesReady) {
                try {
                    mInputDevicesReadyMonitor.wait(timeoutMillis);
                } catch (InterruptedException ex) {
                }
            }
            return mInputDevicesReady;
        }
    }

    public void freezeInputDispatchingLw() {
        if (!mInputDispatchFrozen) {
            if (DEBUG_INPUT) {
                Slog.v(TAG_WM, "Freezing input dispatching");
            }

            mInputDispatchFrozen = true;

            if (DEBUG_INPUT) {
                mInputFreezeReason = Debug.getCallers(6);
            }
            updateInputDispatchModeLw();
        }
    }

    public void thawInputDispatchingLw() {
        if (mInputDispatchFrozen) {
            if (DEBUG_INPUT) {
                Slog.v(TAG_WM, "Thawing input dispatching");
            }

            mInputDispatchFrozen = false;
            mInputFreezeReason = null;
            updateInputDispatchModeLw();
        }
    }

    public void setEventDispatchingLw(boolean enabled) {
        if (mInputDispatchEnabled != enabled) {
            if (DEBUG_INPUT) {
                Slog.v(TAG_WM, "Setting event dispatching to " + enabled);
            }

            mInputDispatchEnabled = enabled;
            updateInputDispatchModeLw();
        }
    }

    private void updateInputDispatchModeLw() {
        mService.mInputManager.setInputDispatchMode(mInputDispatchEnabled, mInputDispatchFrozen);
    }

    void dump(PrintWriter pw, String prefix) {
        if (mInputFreezeReason != null) {
            pw.println(prefix + "mInputFreezeReason=" + mInputFreezeReason);
        }
    }
}
