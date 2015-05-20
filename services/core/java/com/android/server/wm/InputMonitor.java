/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.app.ActivityManagerNative;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;
import android.view.Display;
import android.view.InputChannel;
import android.view.KeyEvent;
import android.view.WindowManager;

import com.android.server.input.InputApplicationHandle;
import com.android.server.input.InputManagerService;
import com.android.server.input.InputWindowHandle;

import java.util.Arrays;

final class InputMonitor implements InputManagerService.WindowManagerCallbacks {
    private final WindowManagerService mService;

    // Current window with input focus for keys and other non-touch events.  May be null.
    private WindowState mInputFocus;

    // When true, prevents input dispatch from proceeding until set to false again.
    private boolean mInputDispatchFrozen;

    // When true, input dispatch proceeds normally.  Otherwise all events are dropped.
    // Initially false, so that input does not get dispatched until boot is finished at
    // which point the ActivityManager will enable dispatching.
    private boolean mInputDispatchEnabled;

    // When true, need to call updateInputWindowsLw().
    private boolean mUpdateInputWindowsNeeded = true;

    // Array of window handles to provide to the input dispatcher.
    private InputWindowHandle[] mInputWindowHandles;
    private int mInputWindowHandleCount;

    // Set to true when the first input device configuration change notification
    // is received to indicate that the input devices are ready.
    private final Object mInputDevicesReadyMonitor = new Object();
    private boolean mInputDevicesReady;

    Rect mTmpRect = new Rect();

    public InputMonitor(WindowManagerService service) {
        mService = service;
    }
    
    /* Notifies the window manager about a broken input channel.
     * 
     * Called by the InputManager.
     */
    @Override
    public void notifyInputChannelBroken(InputWindowHandle inputWindowHandle) {
        if (inputWindowHandle == null) {
            return;
        }

        synchronized (mService.mWindowMap) {
            WindowState windowState = (WindowState) inputWindowHandle.windowState;
            if (windowState != null) {
                Slog.i(WindowManagerService.TAG, "WINDOW DIED " + windowState);
                mService.removeWindowLocked(windowState);
            }
        }
    }
    
    /* Notifies the window manager about an application that is not responding.
     * Returns a new timeout to continue waiting in nanoseconds, or 0 to abort dispatch.
     * 
     * Called by the InputManager.
     */
    @Override
    public long notifyANR(InputApplicationHandle inputApplicationHandle,
            InputWindowHandle inputWindowHandle, String reason) {
        AppWindowToken appWindowToken = null;
        WindowState windowState = null;
        boolean aboveSystem = false;
        synchronized (mService.mWindowMap) {
            if (inputWindowHandle != null) {
                windowState = (WindowState) inputWindowHandle.windowState;
                if (windowState != null) {
                    appWindowToken = windowState.mAppToken;
                }
            }
            if (appWindowToken == null && inputApplicationHandle != null) {
                appWindowToken = (AppWindowToken)inputApplicationHandle.appWindowToken;
            }

            if (windowState != null) {
                Slog.i(WindowManagerService.TAG, "Input event dispatching timed out "
                        + "sending to " + windowState.mAttrs.getTitle()
                        + ".  Reason: " + reason);
                // Figure out whether this window is layered above system windows.
                // We need to do this here to help the activity manager know how to
                // layer its ANR dialog.
                int systemAlertLayer = mService.mPolicy.windowTypeToLayerLw(
                        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
                aboveSystem = windowState.mBaseLayer > systemAlertLayer;
            } else if (appWindowToken != null) {
                Slog.i(WindowManagerService.TAG, "Input event dispatching timed out "
                        + "sending to application " + appWindowToken.stringName
                        + ".  Reason: " + reason);
            } else {
                Slog.i(WindowManagerService.TAG, "Input event dispatching timed out "
                        + ".  Reason: " + reason);
            }

            mService.saveANRStateLocked(appWindowToken, windowState, reason);
        }

        if (appWindowToken != null && appWindowToken.appToken != null) {
            try {
                // Notify the activity manager about the timeout and let it decide whether
                // to abort dispatching or keep waiting.
                boolean abort = appWindowToken.appToken.keyDispatchingTimedOut(reason);
                if (! abort) {
                    // The activity manager declined to abort dispatching.
                    // Wait a bit longer and timeout again later.
                    return appWindowToken.inputDispatchingTimeoutNanos;
                }
            } catch (RemoteException ex) {
            }
        } else if (windowState != null) {
            try {
                // Notify the activity manager about the timeout and let it decide whether
                // to abort dispatching or keep waiting.
                long timeout = ActivityManagerNative.getDefault().inputDispatchingTimedOut(
                        windowState.mSession.mPid, aboveSystem, reason);
                if (timeout >= 0) {
                    // The activity manager declined to abort dispatching.
                    // Wait a bit longer and timeout again later.
                    return timeout * 1000000L; // nanoseconds
                }
            } catch (RemoteException ex) {
            }
        }
        return 0; // abort dispatching
    }

    private void addInputWindowHandleLw(final InputWindowHandle windowHandle) {
        if (mInputWindowHandles == null) {
            mInputWindowHandles = new InputWindowHandle[16];
        }
        if (mInputWindowHandleCount >= mInputWindowHandles.length) {
            mInputWindowHandles = Arrays.copyOf(mInputWindowHandles,
                    mInputWindowHandleCount * 2);
        }
        mInputWindowHandles[mInputWindowHandleCount++] = windowHandle;
    }

    private void addInputWindowHandleLw(final InputWindowHandle inputWindowHandle,
            final WindowState child, int flags, final int type, final boolean isVisible,
            final boolean hasFocus, final boolean hasWallpaper) {
        // Add a window to our list of input windows.
        inputWindowHandle.name = child.toString();
        final boolean modal = (flags & (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)) == 0;
        if (modal && child.mAppToken != null) {
            // Limit the outer touch to the activity stack region.
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            child.getStackBounds(mTmpRect);
            inputWindowHandle.touchableRegion.set(mTmpRect);
        } else {
            // Not modal or full screen modal
            child.getTouchableRegion(inputWindowHandle.touchableRegion);
        }
        inputWindowHandle.layoutParamsFlags = flags;
        inputWindowHandle.layoutParamsType = type;
        inputWindowHandle.dispatchingTimeoutNanos = child.getInputDispatchingTimeoutNanos();
        inputWindowHandle.visible = isVisible;
        inputWindowHandle.canReceiveKeys = child.canReceiveKeys();
        inputWindowHandle.hasFocus = hasFocus;
        inputWindowHandle.hasWallpaper = hasWallpaper;
        inputWindowHandle.paused = child.mAppToken != null ? child.mAppToken.paused : false;
        inputWindowHandle.layer = child.mLayer;
        inputWindowHandle.ownerPid = child.mSession.mPid;
        inputWindowHandle.ownerUid = child.mSession.mUid;
        inputWindowHandle.inputFeatures = child.mAttrs.inputFeatures;

        final Rect frame = child.mFrame;
        inputWindowHandle.frameLeft = frame.left;
        inputWindowHandle.frameTop = frame.top;
        inputWindowHandle.frameRight = frame.right;
        inputWindowHandle.frameBottom = frame.bottom;

        if (child.mGlobalScale != 1) {
            // If we are scaling the window, input coordinates need
            // to be inversely scaled to map from what is on screen
            // to what is actually being touched in the UI.
            inputWindowHandle.scaleFactor = 1.0f/child.mGlobalScale;
        } else {
            inputWindowHandle.scaleFactor = 1;
        }


        addInputWindowHandleLw(inputWindowHandle);
    }

    private void clearInputWindowHandlesLw() {
        while (mInputWindowHandleCount != 0) {
            mInputWindowHandles[--mInputWindowHandleCount] = null;
        }
    }

    public void setUpdateInputWindowsNeededLw() {
        mUpdateInputWindowsNeeded = true;
    }

    /* Updates the cached window information provided to the input dispatcher. */
    public void updateInputWindowsLw(boolean force) {
        if (!force && !mUpdateInputWindowsNeeded) {
            return;
        }
        mUpdateInputWindowsNeeded = false;

        if (false) Slog.d(WindowManagerService.TAG, ">>>>>> ENTERED updateInputWindowsLw");

        // Populate the input window list with information about all of the windows that
        // could potentially receive input.
        // As an optimization, we could try to prune the list of windows but this turns
        // out to be difficult because only the native code knows for sure which window
        // currently has touch focus.
        boolean disableWallpaperTouchEvents = false;

        // If there's a drag in flight, provide a pseudowindow to catch drag input
        final boolean inDrag = (mService.mDragState != null);
        if (inDrag) {
            if (WindowManagerService.DEBUG_DRAG) {
                Log.d(WindowManagerService.TAG, "Inserting drag window");
            }
            final InputWindowHandle dragWindowHandle = mService.mDragState.mDragWindowHandle;
            if (dragWindowHandle != null) {
                addInputWindowHandleLw(dragWindowHandle);
            } else {
                Slog.w(WindowManagerService.TAG, "Drag is in progress but there is no "
                        + "drag window handle.");
            }
        }

        boolean addInputConsumerHandle = mService.mInputConsumer != null;

        // Add all windows on the default display.
        final int numDisplays = mService.mDisplayContents.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            WindowList windows = mService.mDisplayContents.valueAt(displayNdx).getWindowList();
            for (int winNdx = windows.size() - 1; winNdx >= 0; --winNdx) {
                final WindowState child = windows.get(winNdx);
                final InputChannel inputChannel = child.mInputChannel;
                final InputWindowHandle inputWindowHandle = child.mInputWindowHandle;
                if (inputChannel == null || inputWindowHandle == null || child.mRemoved) {
                    // Skip this window because it cannot possibly receive input.
                    continue;
                }
                if (addInputConsumerHandle
                        && inputWindowHandle.layer <= mService.mInputConsumer.mWindowHandle.layer) {
                    addInputWindowHandleLw(mService.mInputConsumer.mWindowHandle);
                    addInputConsumerHandle = false;
                }

                final int flags = child.mAttrs.flags;
                final int privateFlags = child.mAttrs.privateFlags;
                final int type = child.mAttrs.type;

                final boolean hasFocus = (child == mInputFocus);
                final boolean isVisible = child.isVisibleLw();
                if ((privateFlags
                        & WindowManager.LayoutParams.PRIVATE_FLAG_DISABLE_WALLPAPER_TOUCH_EVENTS)
                            != 0) {
                    disableWallpaperTouchEvents = true;
                }
                final boolean hasWallpaper = (child == mService.mWallpaperTarget)
                        && (privateFlags & WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD) == 0
                        && !disableWallpaperTouchEvents;
                final boolean onDefaultDisplay = (child.getDisplayId() == Display.DEFAULT_DISPLAY);

                // If there's a drag in progress and 'child' is a potential drop target,
                // make sure it's been told about the drag
                if (inDrag && isVisible && onDefaultDisplay) {
                    mService.mDragState.sendDragStartedIfNeededLw(child);
                }

                addInputWindowHandleLw(inputWindowHandle, child, flags, type, isVisible, hasFocus,
                        hasWallpaper);
            }
        }

        // Send windows to native code.
        mService.mInputManager.setInputWindows(mInputWindowHandles);

        // Clear the list in preparation for the next round.
        clearInputWindowHandlesLw();

        if (false) Slog.d(WindowManagerService.TAG, "<<<<<<< EXITED updateInputWindowsLw");
    }

    /* Notifies that the input device configuration has changed. */
    @Override
    public void notifyConfigurationChanged() {
        mService.sendNewConfiguration();

        synchronized (mInputDevicesReadyMonitor) {
            if (!mInputDevicesReady) {
                mInputDevicesReady = true;
                mInputDevicesReadyMonitor.notifyAll();
            }
        }
    }

    /* Waits until the built-in input devices have been configured. */
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

    /* Notifies that the lid switch changed state. */
    @Override
    public void notifyLidSwitchChanged(long whenNanos, boolean lidOpen) {
        mService.mPolicy.notifyLidSwitchChanged(whenNanos, lidOpen);
    }

    /* Notifies that the camera lens cover state has changed. */
    @Override
    public void notifyCameraLensCoverSwitchChanged(long whenNanos, boolean lensCovered) {
        mService.mPolicy.notifyCameraLensCoverSwitchChanged(whenNanos, lensCovered);
    }

    /* Provides an opportunity for the window manager policy to intercept early key
     * processing as soon as the key has been read from the device. */
    @Override
    public int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags) {
        return mService.mPolicy.interceptKeyBeforeQueueing(event, policyFlags);
    }

    /* Provides an opportunity for the window manager policy to intercept early motion event
     * processing when the device is in a non-interactive state since these events are normally
     * dropped. */
    @Override
    public int interceptMotionBeforeQueueingNonInteractive(long whenNanos, int policyFlags) {
        return mService.mPolicy.interceptMotionBeforeQueueingNonInteractive(
                whenNanos, policyFlags);
    }

    /* Provides an opportunity for the window manager policy to process a key before
     * ordinary dispatch. */
    @Override
    public long interceptKeyBeforeDispatching(
            InputWindowHandle focus, KeyEvent event, int policyFlags) {
        WindowState windowState = focus != null ? (WindowState) focus.windowState : null;
        return mService.mPolicy.interceptKeyBeforeDispatching(windowState, event, policyFlags);
    }

    /* Provides an opportunity for the window manager policy to process a key that
     * the application did not handle. */
    @Override
    public KeyEvent dispatchUnhandledKey(
            InputWindowHandle focus, KeyEvent event, int policyFlags) {
        WindowState windowState = focus != null ? (WindowState) focus.windowState : null;
        return mService.mPolicy.dispatchUnhandledKey(windowState, event, policyFlags);
    }

    /* Callback to get pointer layer. */
    @Override
    public int getPointerLayer() {
        return mService.mPolicy.windowTypeToLayerLw(WindowManager.LayoutParams.TYPE_POINTER)
                * WindowManagerService.TYPE_LAYER_MULTIPLIER
                + WindowManagerService.TYPE_LAYER_OFFSET;
    }

    /* Called when the current input focus changes.
     * Layer assignment is assumed to be complete by the time this is called.
     */
    public void setInputFocusLw(WindowState newWindow, boolean updateInputWindows) {
        if (WindowManagerService.DEBUG_FOCUS_LIGHT || WindowManagerService.DEBUG_INPUT) {
            Slog.d(WindowManagerService.TAG, "Input focus has changed to " + newWindow);
        }

        if (newWindow != mInputFocus) {
            if (newWindow != null && newWindow.canReceiveKeys()) {
                // Displaying a window implicitly causes dispatching to be unpaused.
                // This is to protect against bugs if someone pauses dispatching but
                // forgets to resume.
                newWindow.mToken.paused = false;
            }

            mInputFocus = newWindow;
            setUpdateInputWindowsNeededLw();

            if (updateInputWindows) {
                updateInputWindowsLw(false /*force*/);
            }
        }
    }

    public void setFocusedAppLw(AppWindowToken newApp) {
        // Focused app has changed.
        if (newApp == null) {
            mService.mInputManager.setFocusedApplication(null);
        } else {
            final InputApplicationHandle handle = newApp.mInputApplicationHandle;
            handle.name = newApp.toString();
            handle.dispatchingTimeoutNanos = newApp.inputDispatchingTimeoutNanos;

            mService.mInputManager.setFocusedApplication(handle);
        }
    }

    public void pauseDispatchingLw(WindowToken window) {
        if (! window.paused) {
            if (WindowManagerService.DEBUG_INPUT) {
                Slog.v(WindowManagerService.TAG, "Pausing WindowToken " + window);
            }
            
            window.paused = true;
            updateInputWindowsLw(true /*force*/);
        }
    }
    
    public void resumeDispatchingLw(WindowToken window) {
        if (window.paused) {
            if (WindowManagerService.DEBUG_INPUT) {
                Slog.v(WindowManagerService.TAG, "Resuming WindowToken " + window);
            }
            
            window.paused = false;
            updateInputWindowsLw(true /*force*/);
        }
    }
    
    public void freezeInputDispatchingLw() {
        if (! mInputDispatchFrozen) {
            if (WindowManagerService.DEBUG_INPUT) {
                Slog.v(WindowManagerService.TAG, "Freezing input dispatching");
            }
            
            mInputDispatchFrozen = true;
            updateInputDispatchModeLw();
        }
    }
    
    public void thawInputDispatchingLw() {
        if (mInputDispatchFrozen) {
            if (WindowManagerService.DEBUG_INPUT) {
                Slog.v(WindowManagerService.TAG, "Thawing input dispatching");
            }
            
            mInputDispatchFrozen = false;
            updateInputDispatchModeLw();
        }
    }
    
    public void setEventDispatchingLw(boolean enabled) {
        if (mInputDispatchEnabled != enabled) {
            if (WindowManagerService.DEBUG_INPUT) {
                Slog.v(WindowManagerService.TAG, "Setting event dispatching to " + enabled);
            }
            
            mInputDispatchEnabled = enabled;
            updateInputDispatchModeLw();
        }
    }
    
    private void updateInputDispatchModeLw() {
        mService.mInputManager.setInputDispatchMode(mInputDispatchEnabled, mInputDispatchFrozen);
    }
}
