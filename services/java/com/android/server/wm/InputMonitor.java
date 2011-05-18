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

import android.graphics.Rect;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;
import android.view.KeyEvent;
import android.view.WindowManager;

import java.util.ArrayList;

final class InputMonitor {
    private final WindowManagerService mService;
    
    // Current window with input focus for keys and other non-touch events.  May be null.
    private WindowState mInputFocus;
    
    // When true, prevents input dispatch from proceeding until set to false again.
    private boolean mInputDispatchFrozen;
    
    // When true, input dispatch proceeds normally.  Otherwise all events are dropped.
    private boolean mInputDispatchEnabled = true;

    // When true, need to call updateInputWindowsLw().
    private boolean mUpdateInputWindowsNeeded = true;

    // Temporary list of windows information to provide to the input dispatcher.
    private InputWindowList mTempInputWindows = new InputWindowList();
    
    // Temporary input application object to provide to the input dispatcher.
    private InputApplication mTempInputApplication = new InputApplication();
    
    // Set to true when the first input device configuration change notification
    // is received to indicate that the input devices are ready.
    private final Object mInputDevicesReadyMonitor = new Object();
    private boolean mInputDevicesReady;

    public InputMonitor(WindowManagerService service) {
        mService = service;
    }
    
    /* Notifies the window manager about a broken input channel.
     * 
     * Called by the InputManager.
     */
    public void notifyInputChannelBroken(InputWindowHandle inputWindowHandle) {
        if (inputWindowHandle == null) {
            return;
        }

        synchronized (mService.mWindowMap) {
            WindowState windowState = (WindowState) inputWindowHandle.windowState;
            Slog.i(WindowManagerService.TAG, "WINDOW DIED " + windowState);
            mService.removeWindowLocked(windowState.mSession, windowState);
        }
    }
    
    /* Notifies the window manager about an application that is not responding.
     * Returns a new timeout to continue waiting in nanoseconds, or 0 to abort dispatch.
     * 
     * Called by the InputManager.
     */
    public long notifyANR(InputApplicationHandle inputApplicationHandle,
            InputWindowHandle inputWindowHandle) {
        AppWindowToken appWindowToken = null;
        if (inputWindowHandle != null) {
            synchronized (mService.mWindowMap) {
                WindowState windowState = (WindowState) inputWindowHandle.windowState;
                if (windowState != null) {
                    Slog.i(WindowManagerService.TAG, "Input event dispatching timed out sending to "
                            + windowState.mAttrs.getTitle());
                    appWindowToken = windowState.mAppToken;
                }
            }
        }
        
        if (appWindowToken == null && inputApplicationHandle != null) {
            appWindowToken = inputApplicationHandle.appWindowToken;
            Slog.i(WindowManagerService.TAG, "Input event dispatching timed out sending to application "
                    + appWindowToken.stringName);
        }

        if (appWindowToken != null && appWindowToken.appToken != null) {
            try {
                // Notify the activity manager about the timeout and let it decide whether
                // to abort dispatching or keep waiting.
                boolean abort = appWindowToken.appToken.keyDispatchingTimedOut();
                if (! abort) {
                    // The activity manager declined to abort dispatching.
                    // Wait a bit longer and timeout again later.
                    return appWindowToken.inputDispatchingTimeoutNanos;
                }
            } catch (RemoteException ex) {
            }
        }
        return 0; // abort dispatching
    }

    private void addDragInputWindowLw(InputWindowList windowList) {
        final InputWindow inputWindow = windowList.add();
        inputWindow.inputChannel = mService.mDragState.mServerChannel;
        inputWindow.name = "drag";
        inputWindow.layoutParamsFlags = 0;
        inputWindow.layoutParamsType = WindowManager.LayoutParams.TYPE_DRAG;
        inputWindow.dispatchingTimeoutNanos = WindowManagerService.DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;
        inputWindow.visible = true;
        inputWindow.canReceiveKeys = false;
        inputWindow.hasFocus = true;
        inputWindow.hasWallpaper = false;
        inputWindow.paused = false;
        inputWindow.layer = mService.mDragState.getDragLayerLw();
        inputWindow.ownerPid = Process.myPid();
        inputWindow.ownerUid = Process.myUid();

        // The drag window covers the entire display
        inputWindow.frameLeft = 0;
        inputWindow.frameTop = 0;
        inputWindow.frameRight = mService.mDisplay.getRealWidth();
        inputWindow.frameBottom = mService.mDisplay.getRealHeight();

        // The drag window cannot receive new touches.
        inputWindow.touchableRegion.setEmpty();
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

        // Populate the input window list with information about all of the windows that
        // could potentially receive input.
        // As an optimization, we could try to prune the list of windows but this turns
        // out to be difficult because only the native code knows for sure which window
        // currently has touch focus.
        final ArrayList<WindowState> windows = mService.mWindows;

        // If there's a drag in flight, provide a pseudowindow to catch drag input
        final boolean inDrag = (mService.mDragState != null);
        if (inDrag) {
            if (WindowManagerService.DEBUG_DRAG) {
                Log.d(WindowManagerService.TAG, "Inserting drag window");
            }
            addDragInputWindowLw(mTempInputWindows);
        }

        final int N = windows.size();
        for (int i = N - 1; i >= 0; i--) {
            final WindowState child = windows.get(i);
            if (child.mInputChannel == null || child.mRemoved) {
                // Skip this window because it cannot possibly receive input.
                continue;
            }
            
            final int flags = child.mAttrs.flags;
            final int type = child.mAttrs.type;
            
            final boolean hasFocus = (child == mInputFocus);
            final boolean isVisible = child.isVisibleLw();
            final boolean hasWallpaper = (child == mService.mWallpaperTarget)
                    && (type != WindowManager.LayoutParams.TYPE_KEYGUARD);

            // If there's a drag in progress and 'child' is a potential drop target,
            // make sure it's been told about the drag
            if (inDrag && isVisible) {
                mService.mDragState.sendDragStartedIfNeededLw(child);
            }

            // Add a window to our list of input windows.
            final InputWindow inputWindow = mTempInputWindows.add();
            inputWindow.inputWindowHandle = child.mInputWindowHandle;
            inputWindow.inputChannel = child.mInputChannel;
            inputWindow.name = child.toString();
            inputWindow.layoutParamsFlags = flags;
            inputWindow.layoutParamsType = type;
            inputWindow.dispatchingTimeoutNanos = child.getInputDispatchingTimeoutNanos();
            inputWindow.visible = isVisible;
            inputWindow.canReceiveKeys = child.canReceiveKeys();
            inputWindow.hasFocus = hasFocus;
            inputWindow.hasWallpaper = hasWallpaper;
            inputWindow.paused = child.mAppToken != null ? child.mAppToken.paused : false;
            inputWindow.layer = child.mLayer;
            inputWindow.ownerPid = child.mSession.mPid;
            inputWindow.ownerUid = child.mSession.mUid;
            
            final Rect frame = child.mFrame;
            inputWindow.frameLeft = frame.left;
            inputWindow.frameTop = frame.top;
            inputWindow.frameRight = frame.right;
            inputWindow.frameBottom = frame.bottom;

            if (child.mGlobalScale != 1) {
                // If we are scaling the window, input coordinates need
                // to be inversely scaled to map from what is on screen
                // to what is actually being touched in the UI.
                inputWindow.scaleFactor = 1.0f/child.mGlobalScale;
            } else {
                inputWindow.scaleFactor = 1;
            }

            child.getTouchableRegion(inputWindow.touchableRegion);
        }

        // Send windows to native code.
        mService.mInputManager.setInputWindows(mTempInputWindows.toNullTerminatedArray());
        
        // Clear the list in preparation for the next round.
        // Also avoids keeping InputChannel objects referenced unnecessarily.
        mTempInputWindows.clear();
    }

    /* Notifies that the input device configuration has changed. */
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
    public void notifyLidSwitchChanged(long whenNanos, boolean lidOpen) {
        mService.mPolicy.notifyLidSwitchChanged(whenNanos, lidOpen);
    }
    
    /* Provides an opportunity for the window manager policy to intercept early key
     * processing as soon as the key has been read from the device. */
    public int interceptKeyBeforeQueueing(
            KeyEvent event, int policyFlags, boolean isScreenOn) {
        return mService.mPolicy.interceptKeyBeforeQueueing(event, policyFlags, isScreenOn);
    }

    /* Provides an opportunity for the window manager policy to intercept early
     * motion event processing when the screen is off since these events are normally
     * dropped. */
    public int interceptMotionBeforeQueueingWhenScreenOff(int policyFlags) {
        return mService.mPolicy.interceptMotionBeforeQueueingWhenScreenOff(policyFlags);
    }

    /* Provides an opportunity for the window manager policy to process a key before
     * ordinary dispatch. */
    public boolean interceptKeyBeforeDispatching(
            InputWindowHandle focus, KeyEvent event, int policyFlags) {
        WindowState windowState = focus != null ? (WindowState) focus.windowState : null;
        return mService.mPolicy.interceptKeyBeforeDispatching(windowState, event, policyFlags);
    }
    
    /* Provides an opportunity for the window manager policy to process a key that
     * the application did not handle. */
    public KeyEvent dispatchUnhandledKey(
            InputWindowHandle focus, KeyEvent event, int policyFlags) {
        WindowState windowState = focus != null ? (WindowState) focus.windowState : null;
        return mService.mPolicy.dispatchUnhandledKey(windowState, event, policyFlags);
    }
    
    /* Called when the current input focus changes.
     * Layer assignment is assumed to be complete by the time this is called.
     */
    public void setInputFocusLw(WindowState newWindow, boolean updateInputWindows) {
        if (WindowManagerService.DEBUG_INPUT) {
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
            mTempInputApplication.inputApplicationHandle = newApp.mInputApplicationHandle;
            mTempInputApplication.name = newApp.toString();
            mTempInputApplication.dispatchingTimeoutNanos =
                    newApp.inputDispatchingTimeoutNanos;

            mService.mInputManager.setFocusedApplication(mTempInputApplication);

            mTempInputApplication.recycle();
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