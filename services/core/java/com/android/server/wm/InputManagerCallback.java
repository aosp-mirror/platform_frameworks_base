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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_INPUT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.H.ON_POINTER_DOWN_OUTSIDE_FOCUS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.gui.StalledTransactionInfo;
import android.os.Debug;
import android.os.IBinder;
import android.os.Trace;
import android.util.Slog;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.InputApplicationHandle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.WindowManagerPolicyConstants;

import com.android.internal.os.TimeoutRecord;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.input.InputManagerService;

import java.io.PrintWriter;
import java.util.OptionalInt;

final class InputManagerCallback implements InputManagerService.WindowManagerCallbacks {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "InputManagerCallback" : TAG_WM;

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

    /**
     * The last input devices info which may affect display configuration. This is a quick lookup
     * to detect interested changes without entering WM lock.
     */
    private SparseIntArray mLastInputConfigurationSources;

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
     * Notifies the window manager about an application that is not responding because it has
     * no focused window.
     *
     * Called by the InputManager.
     */
    @Override
    public void notifyNoFocusedWindowAnr(@NonNull InputApplicationHandle applicationHandle) {
        TimeoutRecord timeoutRecord = TimeoutRecord.forInputDispatchNoFocusedWindow(
                timeoutMessage(OptionalInt.empty(), "Application does not have a focused window"));
        mService.mAnrController.notifyAppUnresponsive(applicationHandle, timeoutRecord);
    }

    @Override
    public void notifyWindowUnresponsive(@NonNull IBinder token, @NonNull OptionalInt pid,
            String reason) {
        TimeoutRecord timeoutRecord = TimeoutRecord.forInputDispatchWindowUnresponsive(
                timeoutMessage(pid, reason));
        mService.mAnrController.notifyWindowUnresponsive(token, pid, timeoutRecord);
    }

    @Override
    public void notifyWindowResponsive(@NonNull IBinder token, @NonNull OptionalInt pid) {
        mService.mAnrController.notifyWindowResponsive(token, pid);
    }

    /** Notifies that the input device configuration has changed. */
    @Override
    public void notifyConfigurationChanged() {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "notifyConfigurationChanged");
        final boolean changed = !com.android.window.flags.Flags.filterIrrelevantInputDeviceChange()
                || updateLastInputConfigurationSources();

        // Even if the input devices are not changed, there could be other pending changes
        // during booting. It's fine to apply earlier.
        if (changed || !mService.mDisplayEnabled) {
            synchronized (mService.mGlobalLock) {
                Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "inputDeviceConfigChanged");
                mService.mRoot.forAllDisplays(DisplayContent::sendNewConfiguration);
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            }
        }

        synchronized (mInputDevicesReadyMonitor) {
            if (!mInputDevicesReady) {
                mInputDevicesReady = true;
                mInputDevicesReadyMonitor.notifyAll();
            }
        }
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    /** Returns {@code true} if the change of input devices may affect display configuration. */
    private boolean updateLastInputConfigurationSources() {
        final InputDevice[] devices = mService.mInputManager.getInputDevices();
        final SparseIntArray newSources = new SparseIntArray(8);
        final SparseIntArray lastSources = mLastInputConfigurationSources;
        boolean changed = lastSources == null;
        for (InputDevice device : devices) {
            final String descriptor = device.getDescriptor();
            if (descriptor == null || device.isVirtual()) {
                continue;
            }
            final int key = descriptor.hashCode();
            // The interested attributes from DisplayContent#computeScreenConfiguration.
            int newSourceHash = device.getSources();
            newSourceHash = newSourceHash * 31 + device.getKeyboardType();
            newSourceHash = newSourceHash * 31 + device.getAssociatedDisplayId();
            newSourceHash = newSourceHash * 31 + (device.isExternal() ? 1 : 0);
            newSourceHash = newSourceHash * 31 + (device.isEnabled() ? 1 : 0);
            newSources.put(key, newSourceHash);
            if (lastSources != null && !changed) {
                final int lastSource = lastSources.get(key, 0 /* valueIfKeyNotFound */);
                if (lastSource != newSourceHash) {
                    changed = true;
                }
            }
        }
        if (lastSources != null && lastSources.size() != newSources.size()) {
            changed = true;
        }
        mLastInputConfigurationSources = newSources;
        return changed;
    }

    /** Notifies that the pointer location configuration has changed. */
    @Override
    public void notifyPointerLocationChanged(boolean pointerLocationEnabled) {
        if (mService.mPointerLocationEnabled == pointerLocationEnabled) {
            return;
        }

        synchronized (mService.mGlobalLock) {
            mService.mPointerLocationEnabled = pointerLocationEnabled;
            mService.mRoot.forAllDisplayPolicies(
                    p -> p.setPointerLocationEnabled(mService.mPointerLocationEnabled)
            );
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
    public int interceptMotionBeforeQueueingNonInteractive(int displayId, int source, int action,
            long whenNanos, int policyFlags) {
        return mService.mPolicy.interceptMotionBeforeQueueingNonInteractive(
                displayId, source, action, whenNanos, policyFlags);
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
    public boolean interceptUnhandledKey(KeyEvent event, IBinder focusedToken) {
        return mService.mPolicy.interceptUnhandledKey(event, focusedToken);
    }

    /** Callback to get pointer layer. */
    @Override
    public int getPointerLayer() {
        return mService.mPolicy.getWindowLayerFromTypeLw(WindowManager.LayoutParams.TYPE_POINTER)
                * WindowManagerPolicyConstants.TYPE_LAYER_MULTIPLIER
                + WindowManagerPolicyConstants.TYPE_LAYER_OFFSET;
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
                if (displayContent.getDisplayInfo().state == Display.STATE_OFF) {
                    continue;
                }
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
    public void notifyFocusChanged(IBinder oldToken, IBinder newToken) {
        mService.mH.sendMessage(PooledLambda.obtainMessage(
                mService::reportFocusChanged, oldToken, newToken));
    }

    @Override
    public void notifyDropWindow(IBinder token, float x, float y) {
        mService.mH.sendMessage(PooledLambda.obtainMessage(
                mService.mDragDropController::reportDropWindow, token, x, y));
    }

    @Override
    public SurfaceControl getParentSurfaceForPointers(int displayId) {
        synchronized (mService.mGlobalLock) {
            final DisplayContent dc = mService.mRoot.getDisplayContent(displayId);
            if (dc == null) {
                Slog.e(TAG, "Failed to get parent surface for pointers on display " + displayId
                        + " - DisplayContent not found.");
                return null;
            }
            return dc.getOverlayLayer();
        }
    }

    @Override
    @Nullable
    public SurfaceControl createSurfaceForGestureMonitor(String name, int displayId) {
        synchronized (mService.mGlobalLock) {
            final DisplayContent dc = mService.mRoot.getDisplayContent(displayId);
            if (dc == null) {
                Slog.e(TAG, "Failed to create a gesture monitor on display: " + displayId
                        + " - DisplayContent not found.");
                return null;
            }
            final SurfaceControl inputOverlay = dc.getInputOverlayLayer();
            if (inputOverlay == null) {
                Slog.e(TAG, "Failed to create a gesture monitor on display: " + displayId
                        + " - Input overlay layer is not initialized.");
                return null;
            }
            return mService.makeSurfaceBuilder()
                    .setContainerLayer()
                    .setName(name)
                    .setCallsite("createSurfaceForGestureMonitor")
                    .setParent(inputOverlay)
                    .setCallsite("InputManagerCallback.createSurfaceForGestureMonitor")
                    .build();
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

    private String timeoutMessage(OptionalInt pid, String reason) {
        String message = (reason == null) ? "Input dispatching timed out."
                : String.format("Input dispatching timed out (%s).", reason);
        if (pid.isEmpty()) {
            return message;
        }
        StalledTransactionInfo stalledTransactionInfo =
                SurfaceControl.getStalledTransactionInfo(pid.getAsInt());
        if (stalledTransactionInfo == null) {
            return message;
        }
        return String.format("%s Buffer processing for the associated surface is stuck due to an "
                + "unsignaled fence (window=%s, bufferId=0x%016X, frameNumber=%s). This "
                + "potentially indicates a GPU hang.", message, stalledTransactionInfo.layerName,
                stalledTransactionInfo.bufferId, stalledTransactionInfo.frameNumber);
    }

    void dump(PrintWriter pw, String prefix) {
        if (mInputFreezeReason != null) {
            pw.println(prefix + "mInputFreezeReason=" + mInputFreezeReason);
        }
    }
}
