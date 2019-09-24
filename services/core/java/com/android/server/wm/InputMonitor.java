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

import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.WindowManager.INPUT_CONSUMER_NAVIGATION;
import static android.view.WindowManager.INPUT_CONSUMER_PIP;
import static android.view.WindowManager.INPUT_CONSUMER_RECENTS_ANIMATION;
import static android.view.WindowManager.INPUT_CONSUMER_WALLPAPER;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_DISABLE_WALLPAPER_TOUCH_EVENTS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_FOCUS_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_INPUT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TASK_POSITIONING;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.Trace;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.view.InputApplicationHandle;
import android.view.InputChannel;
import android.view.InputEventReceiver;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;

import com.android.server.AnimationThread;
import com.android.server.policy.WindowManagerPolicy;

import java.io.PrintWriter;
import java.util.Set;
import java.util.function.Consumer;

final class InputMonitor {
    private final WindowManagerService mService;

    // Current window with input focus for keys and other non-touch events.  May be null.
    private WindowState mInputFocus;

    // When true, need to call updateInputWindowsLw().
    private boolean mUpdateInputWindowsNeeded = true;
    private boolean mUpdateInputWindowsPending;
    private boolean mApplyImmediately;

    // Currently focused input window handle.
    private InputWindowHandle mFocusedInputWindowHandle;

    private boolean mDisableWallpaperTouchEvents;
    private final Rect mTmpRect = new Rect();
    private final UpdateInputForAllWindowsConsumer mUpdateInputForAllWindowsConsumer;

    private final int mDisplayId;
    private final DisplayContent mDisplayContent;
    private boolean mDisplayRemoved;

    private final SurfaceControl.Transaction mInputTransaction;
    private final Handler mHandler;

    /**
     * The set of input consumer added to the window manager by name, which consumes input events
     * for the windows below it.
     */
    private final ArrayMap<String, InputConsumerImpl> mInputConsumers = new ArrayMap();

    private static final class EventReceiverInputConsumer extends InputConsumerImpl
            implements WindowManagerPolicy.InputConsumer {
        private InputMonitor mInputMonitor;
        private final InputEventReceiver mInputEventReceiver;

        EventReceiverInputConsumer(WindowManagerService service, InputMonitor monitor,
                                   Looper looper, String name,
                                   InputEventReceiver.Factory inputEventReceiverFactory,
                                   int clientPid, UserHandle clientUser, int displayId) {
            super(service, null /* token */, name, null /* inputChannel */, clientPid, clientUser,
                    displayId);
            mInputMonitor = monitor;
            mInputEventReceiver = inputEventReceiverFactory.createInputEventReceiver(
                    mClientChannel, looper);
        }

        @Override
        public void dismiss() {
            synchronized (mService.mGlobalLock) {
                if (mInputMonitor.destroyInputConsumer(mWindowHandle.name)) {
                    mInputEventReceiver.dispose();
                }
            }
        }
    }

    private final Runnable mUpdateInputWindows = new Runnable() {
        @Override
        public void run() {
            synchronized (mService.mGlobalLock) {
                mUpdateInputWindowsPending = false;
                mUpdateInputWindowsNeeded = false;

                if (mDisplayRemoved) {
                    return;
                }

                // Populate the input window list with information about all of the windows that
                // could potentially receive input.
                // As an optimization, we could try to prune the list of windows but this turns
                // out to be difficult because only the native code knows for sure which window
                // currently has touch focus.

                // If there's a drag in flight, provide a pseudo-window to catch drag input
                final boolean inDrag = mService.mDragDropController.dragDropActiveLocked();
                final boolean inPositioning =
                        mService.mTaskPositioningController.isPositioningLocked();
                if (inPositioning) {
                    if (DEBUG_TASK_POSITIONING) {
                        Log.d(TAG_WM, "Inserting window handle for repositioning");
                    }
                    mService.mTaskPositioningController.showInputSurface(mInputTransaction,
                            mDisplayId);
                } else {
                    mService.mTaskPositioningController.hideInputSurface(mInputTransaction,
                            mDisplayId);
                }

                // Add all windows on the default display.
                mUpdateInputForAllWindowsConsumer.updateInputWindows(inDrag);
            }
        }
    };

    public InputMonitor(WindowManagerService service, int displayId) {
        mService = service;
        mDisplayContent = mService.mRoot.getDisplayContent(displayId);
        mDisplayId = displayId;
        mInputTransaction = mService.mTransactionFactory.get();
        mHandler = AnimationThread.getHandler();
        mUpdateInputForAllWindowsConsumer = new UpdateInputForAllWindowsConsumer();
    }

    void onDisplayRemoved() {
        mHandler.removeCallbacks(mUpdateInputWindows);
        mService.mInputManager.onDisplayRemoved(mDisplayId);
        mDisplayRemoved = true;
    }

    private void addInputConsumer(String name, InputConsumerImpl consumer) {
        mInputConsumers.put(name, consumer);
        consumer.linkToDeathRecipient();
        updateInputWindowsLw(true /* force */);
    }

    boolean destroyInputConsumer(String name) {
        if (disposeInputConsumer(mInputConsumers.remove(name))) {
            updateInputWindowsLw(true /* force */);
            return true;
        }
        return false;
    }

    private boolean disposeInputConsumer(InputConsumerImpl consumer) {
        if (consumer != null) {
            consumer.disposeChannelsLw();
            consumer.hide(mInputTransaction);
            return true;
        }
        return false;
    }

    InputConsumerImpl getInputConsumer(String name) {
        return mInputConsumers.get(name);
    }

    void layoutInputConsumers(int dw, int dh) {
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "layoutInputConsumer");
            for (int i = mInputConsumers.size() - 1; i >= 0; i--) {
                mInputConsumers.valueAt(i).layout(mInputTransaction, dw, dh);
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    // The visibility of the input consumers is recomputed each time we
    // update the input windows. We use a model where consumers begin invisible
    // (set so by this function) and must meet some condition for visibility on each update.
    void resetInputConsumers(SurfaceControl.Transaction t) {
        for (int i = mInputConsumers.size() - 1; i >= 0; i--) {
            mInputConsumers.valueAt(i).hide(t);
        }
    }

    WindowManagerPolicy.InputConsumer createInputConsumer(Looper looper, String name,
            InputEventReceiver.Factory inputEventReceiverFactory) {
        if (mInputConsumers.containsKey(name)) {
            throw new IllegalStateException("Existing input consumer found with name: " + name
                    + ", display: " + mDisplayId);
        }
        final EventReceiverInputConsumer consumer = new EventReceiverInputConsumer(mService,
                this, looper, name, inputEventReceiverFactory, Process.myPid(),
                UserHandle.SYSTEM, mDisplayId);
        addInputConsumer(name, consumer);
        return consumer;
    }

    void createInputConsumer(IBinder token, String name, InputChannel inputChannel, int clientPid,
            UserHandle clientUser) {
        if (mInputConsumers.containsKey(name)) {
            throw new IllegalStateException("Existing input consumer found with name: " + name
                    + ", display: " + mDisplayId);
        }

        final InputConsumerImpl consumer = new InputConsumerImpl(mService, token, name,
                inputChannel, clientPid, clientUser, mDisplayId);
        switch (name) {
            case INPUT_CONSUMER_WALLPAPER:
                consumer.mWindowHandle.hasWallpaper = true;
                break;
            case INPUT_CONSUMER_PIP:
                // The touchable region of the Pip input window is cropped to the bounds of the
                // stack, and we need FLAG_NOT_TOUCH_MODAL to ensure other events fall through
                consumer.mWindowHandle.layoutParamsFlags |= FLAG_NOT_TOUCH_MODAL;
                break;
        }
        addInputConsumer(name, consumer);
    }


    void populateInputWindowHandle(final InputWindowHandle inputWindowHandle,
            final WindowState child, int flags, final int type, final boolean isVisible,
            final boolean hasFocus, final boolean hasWallpaper) {
        // Add a window to our list of input windows.
        inputWindowHandle.name = child.toString();
        flags = child.getSurfaceTouchableRegion(inputWindowHandle, flags);
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
        inputWindowHandle.displayId = child.getDisplayId();

        final Rect frame = child.getFrameLw();
        inputWindowHandle.frameLeft = frame.left;
        inputWindowHandle.frameTop = frame.top;
        inputWindowHandle.frameRight = frame.right;
        inputWindowHandle.frameBottom = frame.bottom;

        // Surface insets are hardcoded to be the same in all directions
        // and we could probably deprecate the "left/right/top/bottom" concept.
        // we avoid reintroducing this concept by just choosing one of them here.
        inputWindowHandle.surfaceInset = child.getAttrs().surfaceInsets.left;

        if (child.mGlobalScale != 1) {
            // If we are scaling the window, input coordinates need
            // to be inversely scaled to map from what is on screen
            // to what is actually being touched in the UI.
            inputWindowHandle.scaleFactor = 1.0f/child.mGlobalScale;
        } else {
            inputWindowHandle.scaleFactor = 1;
        }

        if (DEBUG_INPUT) {
            Slog.d(TAG_WM, "addInputWindowHandle: "
                    + child + ", " + inputWindowHandle);
        }

        if (hasFocus) {
            mFocusedInputWindowHandle = inputWindowHandle;
        }
    }

    void setUpdateInputWindowsNeededLw() {
        mUpdateInputWindowsNeeded = true;
    }

    /* Updates the cached window information provided to the input dispatcher. */
    void updateInputWindowsLw(boolean force) {
        if (!force && !mUpdateInputWindowsNeeded) {
            return;
        }
        scheduleUpdateInputWindows();
    }

    private void scheduleUpdateInputWindows() {
        if (mDisplayRemoved) {
            return;
        }

        if (!mUpdateInputWindowsPending) {
            mUpdateInputWindowsPending = true;
            mHandler.post(mUpdateInputWindows);
        }
    }

    void updateInputWindowsImmediately() {
        mHandler.removeCallbacks(mUpdateInputWindows);
        mApplyImmediately = true;
        mUpdateInputWindows.run();
        mApplyImmediately = false;
    }

    /* Called when the current input focus changes.
     * Layer assignment is assumed to be complete by the time this is called.
     */
    public void setInputFocusLw(WindowState newWindow, boolean updateInputWindows) {
        if (DEBUG_FOCUS_LIGHT || DEBUG_INPUT) {
            Slog.d(TAG_WM, "Input focus has changed to " + newWindow);
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
            mService.mInputManager.setFocusedApplication(mDisplayId, null);
        } else {
            final InputApplicationHandle handle = newApp.mInputApplicationHandle;
            handle.name = newApp.toString();
            handle.dispatchingTimeoutNanos = newApp.mInputDispatchingTimeoutNanos;

            mService.mInputManager.setFocusedApplication(mDisplayId, handle);
        }
    }

    public void pauseDispatchingLw(WindowToken window) {
        if (! window.paused) {
            if (DEBUG_INPUT) {
                Slog.v(TAG_WM, "Pausing WindowToken " + window);
            }

            window.paused = true;
            updateInputWindowsLw(true /*force*/);
        }
    }

    public void resumeDispatchingLw(WindowToken window) {
        if (window.paused) {
            if (DEBUG_INPUT) {
                Slog.v(TAG_WM, "Resuming WindowToken " + window);
            }

            window.paused = false;
            updateInputWindowsLw(true /*force*/);
        }
    }

    void dump(PrintWriter pw, String prefix) {
        final Set<String> inputConsumerKeys = mInputConsumers.keySet();
        if (!inputConsumerKeys.isEmpty()) {
            pw.println(prefix + "InputConsumers:");
            for (String key : inputConsumerKeys) {
                mInputConsumers.get(key).dump(pw, key, prefix);
            }
        }
    }

    private final class UpdateInputForAllWindowsConsumer implements Consumer<WindowState> {
        InputConsumerImpl navInputConsumer;
        InputConsumerImpl pipInputConsumer;
        InputConsumerImpl wallpaperInputConsumer;
        InputConsumerImpl recentsAnimationInputConsumer;

        private boolean mAddInputConsumerHandle;
        private boolean mAddPipInputConsumerHandle;
        private boolean mAddWallpaperInputConsumerHandle;
        private boolean mAddRecentsAnimationInputConsumerHandle;

        boolean inDrag;
        WallpaperController wallpaperController;

        // An invalid window handle that tells SurfaceFlinger not update the input info.
        final InputWindowHandle mInvalidInputWindow = new InputWindowHandle(null, null, mDisplayId);

        private void updateInputWindows(boolean inDrag) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "updateInputWindows");

            navInputConsumer = getInputConsumer(INPUT_CONSUMER_NAVIGATION);
            pipInputConsumer = getInputConsumer(INPUT_CONSUMER_PIP);
            wallpaperInputConsumer = getInputConsumer(INPUT_CONSUMER_WALLPAPER);
            recentsAnimationInputConsumer = getInputConsumer(INPUT_CONSUMER_RECENTS_ANIMATION);

            mAddInputConsumerHandle = navInputConsumer != null;
            mAddPipInputConsumerHandle = pipInputConsumer != null;
            mAddWallpaperInputConsumerHandle = wallpaperInputConsumer != null;
            mAddRecentsAnimationInputConsumerHandle = recentsAnimationInputConsumer != null;

            mTmpRect.setEmpty();
            mDisableWallpaperTouchEvents = false;
            this.inDrag = inDrag;
            wallpaperController = mDisplayContent.mWallpaperController;

            resetInputConsumers(mInputTransaction);

            mDisplayContent.forAllWindows(this,
                    true /* traverseTopToBottom */);

            if (mAddWallpaperInputConsumerHandle) {
                wallpaperInputConsumer.show(mInputTransaction, 0);
            }

            if (mApplyImmediately) {
                mInputTransaction.apply();
            } else {
                mDisplayContent.getPendingTransaction().merge(mInputTransaction);
                mDisplayContent.scheduleAnimation();
            }

            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }

        @Override
        public void accept(WindowState w) {
            final InputChannel inputChannel = w.mInputChannel;
            final InputWindowHandle inputWindowHandle = w.mInputWindowHandle;
            if (inputChannel == null || inputWindowHandle == null || w.mRemoved
                    || w.cantReceiveTouchInput()) {
                if (w.mWinAnimator.hasSurface()) {
                    mInputTransaction.setInputWindowInfo(
                            w.mWinAnimator.mSurfaceController.mSurfaceControl, mInvalidInputWindow);
                }
                // Skip this window because it cannot possibly receive input.
                return;
            }

            final int flags = w.mAttrs.flags;
            final int privateFlags = w.mAttrs.privateFlags;
            final int type = w.mAttrs.type;
            final boolean hasFocus = w.isFocused();
            final boolean isVisible = w.isVisibleLw();

            if (mAddRecentsAnimationInputConsumerHandle) {
                final RecentsAnimationController recentsAnimationController =
                        mService.getRecentsAnimationController();
                if (recentsAnimationController != null
                        && recentsAnimationController.shouldApplyInputConsumer(w.mAppToken)) {
                    if (recentsAnimationController.updateInputConsumerForApp(
                            recentsAnimationInputConsumer.mWindowHandle, hasFocus)) {
                        recentsAnimationInputConsumer.show(mInputTransaction, w);
                        mAddRecentsAnimationInputConsumerHandle = false;
                    }
                }
            }

            if (w.inPinnedWindowingMode()) {
                if (mAddPipInputConsumerHandle) {
                    // Update the bounds of the Pip input consumer to match the window bounds.
                    w.getBounds(mTmpRect);
                    pipInputConsumer.layout(mInputTransaction, mTmpRect);

                    // The touchable region is relative to the surface top-left
                    mTmpRect.offsetTo(0, 0);
                    pipInputConsumer.mWindowHandle.touchableRegion.set(mTmpRect);
                    pipInputConsumer.show(mInputTransaction, w);
                    mAddPipInputConsumerHandle = false;
                }
            }

            if (mAddInputConsumerHandle
                    && inputWindowHandle.layer <= navInputConsumer.mWindowHandle.layer) {
                navInputConsumer.show(mInputTransaction, w);
                mAddInputConsumerHandle = false;
            }

            if (mAddWallpaperInputConsumerHandle) {
                if (w.mAttrs.type == TYPE_WALLPAPER && w.isVisibleLw()) {
                    // Add the wallpaper input consumer above the first visible wallpaper.
                    wallpaperInputConsumer.show(mInputTransaction, w);
                    mAddWallpaperInputConsumerHandle = false;
                }
            }

            if ((privateFlags & PRIVATE_FLAG_DISABLE_WALLPAPER_TOUCH_EVENTS) != 0) {
                mDisableWallpaperTouchEvents = true;
            }
            final boolean hasWallpaper = wallpaperController.isWallpaperTarget(w)
                    && (privateFlags & PRIVATE_FLAG_KEYGUARD) == 0
                    && !mDisableWallpaperTouchEvents;

            // If there's a drag in progress and 'child' is a potential drop target,
            // make sure it's been told about the drag
            if (inDrag && isVisible && w.getDisplayContent().isDefaultDisplay) {
                mService.mDragDropController.sendDragStartedIfNeededLocked(w);
            }

            populateInputWindowHandle(
                    inputWindowHandle, w, flags, type, isVisible, hasFocus, hasWallpaper);

            // register key interception info
            mService.mKeyInterceptionInfoForToken.put(inputWindowHandle.token,
                    w.getKeyInterceptionInfo());

            if (w.mWinAnimator.hasSurface()) {
                mInputTransaction.setInputWindowInfo(
                        w.mWinAnimator.mSurfaceController.mSurfaceControl, inputWindowHandle);
            }
        }
    }
}
