package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_INPUT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.os.Debug;
import android.os.IBinder;
import android.util.Slog;
import android.view.KeyEvent;
import android.view.WindowManager;

import com.android.server.input.InputManagerService;

import java.io.PrintWriter;

final class InputManagerCallback implements InputManagerService.WindowManagerCallbacks {
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
            WindowState windowState = mService.windowForClientLocked(null, token, false);
            if (windowState != null) {
                Slog.i(TAG_WM, "WINDOW DIED " + windowState);
                windowState.removeIfPossible();
            }
        }
    }

    /**
     * Notifies the window manager about an application that is not responding.
     * Returns a new timeout to continue waiting in nanoseconds, or 0 to abort dispatch.
     *
     * Called by the InputManager.
     */
    @Override
    public long notifyANR(IBinder token, String reason) {
        AppWindowToken appWindowToken = null;
        WindowState windowState = null;
        boolean aboveSystem = false;
        synchronized (mService.mGlobalLock) {
            if (token != null) {
                windowState = mService.windowForClientLocked(null, token, false);
                if (windowState != null) {
                    appWindowToken = windowState.mAppToken;
                }
            }

            if (windowState != null) {
                Slog.i(TAG_WM, "Input event dispatching timed out "
                        + "sending to " + windowState.mAttrs.getTitle()
                        + ".  Reason: " + reason);
                // Figure out whether this window is layered above system windows.
                // We need to do this here to help the activity manager know how to
                // layer its ANR dialog.
                int systemAlertLayer = mService.mPolicy.getWindowLayerFromTypeLw(
                        TYPE_APPLICATION_OVERLAY, windowState.mOwnerCanAddInternalSystemWindow);
                aboveSystem = windowState.mBaseLayer > systemAlertLayer;
            } else if (appWindowToken != null) {
                Slog.i(TAG_WM, "Input event dispatching timed out "
                        + "sending to application " + appWindowToken.stringName
                        + ".  Reason: " + reason);
            } else {
                Slog.i(TAG_WM, "Input event dispatching timed out "
                        + ".  Reason: " + reason);
            }

            mService.saveANRStateLocked(appWindowToken, windowState, reason);
        }

        // All the calls below need to happen without the WM lock held since they call into AM.
        mService.mAtmInternal.saveANRState(reason);

        if (appWindowToken != null && appWindowToken.appToken != null) {
            // Notify the activity manager about the timeout and let it decide whether
            // to abort dispatching or keep waiting.
            final boolean abort = appWindowToken.keyDispatchingTimedOut(reason,
                    (windowState != null) ? windowState.mSession.mPid : -1);
            if (!abort) {
                // The activity manager declined to abort dispatching.
                // Wait a bit longer and timeout again later.
                return appWindowToken.mInputDispatchingTimeoutNanos;
            }
        } else if (windowState != null) {
            // Notify the activity manager about the timeout and let it decide whether
            // to abort dispatching or keep waiting.
            long timeout = mService.mAmInternal.inputDispatchingTimedOut(
                    windowState.mSession.mPid, aboveSystem, reason);
            if (timeout >= 0) {
                // The activity manager declined to abort dispatching.
                // Wait a bit longer and timeout again later.
                return timeout * 1000000L; // nanoseconds
            }
        }
        return 0; // abort dispatching
    }

    /** Notifies that the input device configuration has changed. */
    @Override
    public void notifyConfigurationChanged() {
        // TODO(multi-display): Notify proper displays that are associated with this input device.
        mService.sendNewConfiguration(DEFAULT_DISPLAY);

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

    /**
     * Provides an opportunity for the window manager policy to intercept early motion event
     * processing when the device is in a non-interactive state since these events are normally
     * dropped.
     */
    @Override
    public int interceptMotionBeforeQueueingNonInteractive(long whenNanos, int policyFlags) {
        return mService.mPolicy.interceptMotionBeforeQueueingNonInteractive(
                whenNanos, policyFlags);
    }

    /**
     * Provides an opportunity for the window manager policy to process a key before
     * ordinary dispatch.
     */
    @Override
    public long interceptKeyBeforeDispatching(
            IBinder focus, KeyEvent event, int policyFlags) {
        WindowState windowState = mService.windowForClientLocked(null, focus, false);
        return mService.mPolicy.interceptKeyBeforeDispatching(windowState, event, policyFlags);
    }

    /**
     * Provides an opportunity for the window manager policy to process a key that
     * the application did not handle.
     */
    @Override
    public KeyEvent dispatchUnhandledKey(
            IBinder focus, KeyEvent event, int policyFlags) {
        WindowState windowState = mService.windowForClientLocked(null, focus, false);
        return mService.mPolicy.dispatchUnhandledKey(windowState, event, policyFlags);
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
