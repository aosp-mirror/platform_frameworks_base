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
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.INPUT_CONSUMER_NAVIGATION;
import static android.view.WindowManager.INPUT_CONSUMER_PIP;
import static android.view.WindowManager.INPUT_CONSUMER_RECENTS_ANIMATION;
import static android.view.WindowManager.INPUT_CONSUMER_WALLPAPER;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_DISABLE_WALLPAPER_TOUCH_EVENTS;
import static android.view.WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_FOCUS_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_INPUT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.Trace;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.InputChannel;
import android.view.InputEventReceiver;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;

import com.android.server.policy.WindowManagerPolicy;
import com.android.server.protolog.common.ProtoLog;

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
    private boolean mUpdateInputWindowsImmediately;

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
                mInputMonitor.mInputConsumers.remove(mName);
                hide(mInputMonitor.mInputTransaction);
                mInputMonitor.updateInputWindowsLw(true /* force */);
            }
        }

        @Override
        public void dispose() {
            synchronized (mService.mGlobalLock) {
                disposeChannelsLw(mInputMonitor.mInputTransaction);
                mInputEventReceiver.dispose();
                mInputMonitor.updateInputWindowsLw(true /* force */);
            }
        }
    }

    private class UpdateInputWindows implements Runnable {
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

                // Add all windows on the default display.
                mUpdateInputForAllWindowsConsumer.updateInputWindows(inDrag);
            }
        }
    }

    private final UpdateInputWindows mUpdateInputWindows = new UpdateInputWindows();

    InputMonitor(WindowManagerService service, DisplayContent displayContent) {
        mService = service;
        mDisplayContent = displayContent;
        mDisplayId = displayContent.getDisplayId();
        mInputTransaction = mService.mTransactionFactory.get();
        mHandler = mService.mAnimationHandler;
        mUpdateInputForAllWindowsConsumer = new UpdateInputForAllWindowsConsumer();
    }

    void onDisplayRemoved() {
        mHandler.removeCallbacks(mUpdateInputWindows);
        mHandler.post(() -> {
            // Make sure any pending setInputWindowInfo transactions are completed. That prevents
            // the timing of updating input info of removed display after cleanup.
            mService.mTransactionFactory.get().syncInputWindows().apply();
            // It calls InputDispatcher::setInputWindows directly.
            mService.mInputManager.onDisplayRemoved(mDisplayId);
        });
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
            consumer.disposeChannelsLw(mInputTransaction);
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
        if (!name.contentEquals(INPUT_CONSUMER_NAVIGATION)) {
            throw new IllegalArgumentException("Illegal input consumer : " + name
                    + ", display: " + mDisplayId);
        }

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
            case INPUT_CONSUMER_RECENTS_ANIMATION:
                break;
            default:
                throw new IllegalArgumentException("Illegal input consumer : " + name
                        + ", display: " + mDisplayId);
        }
        addInputConsumer(name, consumer);
    }


    void populateInputWindowHandle(final InputWindowHandle inputWindowHandle,
            final WindowState child, int flags, final int type, final boolean isVisible,
            final boolean hasFocus, final boolean hasWallpaper) {
        // Add a window to our list of input windows.
        inputWindowHandle.name = child.toString();
        inputWindowHandle.inputApplicationHandle = child.mActivityRecord != null
                ? child.mActivityRecord.getInputApplicationHandle(false /* update */) : null;
        flags = child.getSurfaceTouchableRegion(inputWindowHandle, flags);
        inputWindowHandle.layoutParamsFlags = flags;
        inputWindowHandle.layoutParamsType = type;
        inputWindowHandle.dispatchingTimeoutNanos = child.getInputDispatchingTimeoutNanos();
        inputWindowHandle.visible = isVisible;
        inputWindowHandle.canReceiveKeys = child.canReceiveKeys();
        inputWindowHandle.hasFocus = hasFocus;
        inputWindowHandle.hasWallpaper = hasWallpaper;
        inputWindowHandle.paused = child.mActivityRecord != null ? child.mActivityRecord.paused : false;
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

        /**
         * If the window is in a TaskManaged by a TaskOrganizer then most cropping
         * will be applied using the SurfaceControl hierarchy from the Organizer.
         * This means we need to make sure that these changes in crop are reflected
         * in the input windows, and so ensure this flag is set so that
         * the input crop always reflects the surface hierarchy.
         * we may have some issues with modal-windows, but I guess we can
         * cross that bridge when we come to implementing full-screen TaskOrg
         */
        if (child.getTask() != null && child.getTask().isOrganized()) {
            inputWindowHandle.replaceTouchableRegionWithCrop(null /* Use this surfaces crop */);
        }

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

    /**
     * Immediately update the input transaction and merge into the passing Transaction that could be
     * collected and applied later.
     */
    void updateInputWindowsImmediately(SurfaceControl.Transaction t) {
        mHandler.removeCallbacks(mUpdateInputWindows);
        mUpdateInputWindowsImmediately = true;
        mUpdateInputWindows.run();
        mUpdateInputWindowsImmediately = false;
        t.merge(mInputTransaction);
    }

    /**
     * Called when the current input focus changes.
     * Layer assignment is assumed to be complete by the time this is called.
     */
    public void setInputFocusLw(WindowState newWindow, boolean updateInputWindows) {
        ProtoLog.d(WM_DEBUG_FOCUS_LIGHT, "Input focus has changed to %s", newWindow);

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

    public void setFocusedAppLw(ActivityRecord newApp) {
        // Focused app has changed.
        mService.mInputManager.setFocusedApplication(mDisplayId,
                newApp != null ? newApp.getInputApplicationHandle(true /* update */) : null);
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
        InputConsumerImpl mNavInputConsumer;
        InputConsumerImpl mPipInputConsumer;
        InputConsumerImpl mWallpaperInputConsumer;
        InputConsumerImpl mRecentsAnimationInputConsumer;

        private boolean mAddNavInputConsumerHandle;
        private boolean mAddPipInputConsumerHandle;
        private boolean mAddWallpaperInputConsumerHandle;
        private boolean mAddRecentsAnimationInputConsumerHandle;

        boolean mInDrag;
        WallpaperController mWallpaperController;

        // An invalid window handle that tells SurfaceFlinger not update the input info.
        final InputWindowHandle mInvalidInputWindow = new InputWindowHandle(null, mDisplayId);

        private void updateInputWindows(boolean inDrag) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "updateInputWindows");

            mNavInputConsumer = getInputConsumer(INPUT_CONSUMER_NAVIGATION);
            mPipInputConsumer = getInputConsumer(INPUT_CONSUMER_PIP);
            mWallpaperInputConsumer = getInputConsumer(INPUT_CONSUMER_WALLPAPER);
            mRecentsAnimationInputConsumer = getInputConsumer(INPUT_CONSUMER_RECENTS_ANIMATION);

            mAddNavInputConsumerHandle = mNavInputConsumer != null;
            mAddPipInputConsumerHandle = mPipInputConsumer != null;
            mAddWallpaperInputConsumerHandle = mWallpaperInputConsumer != null;
            mAddRecentsAnimationInputConsumerHandle = mRecentsAnimationInputConsumer != null;

            mTmpRect.setEmpty();
            mDisableWallpaperTouchEvents = false;
            mInDrag = inDrag;
            mWallpaperController = mDisplayContent.mWallpaperController;

            resetInputConsumers(mInputTransaction);

            mDisplayContent.forAllWindows(this,
                    true /* traverseTopToBottom */);

            if (!mUpdateInputWindowsImmediately) {
                mDisplayContent.getPendingTransaction().merge(mInputTransaction);
                mDisplayContent.scheduleAnimation();
            }

            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }

        @Override
        public void accept(WindowState w) {
            final InputChannel inputChannel = w.mInputChannel;
            final InputWindowHandle inputWindowHandle = w.mInputWindowHandle;
            final RecentsAnimationController recentsAnimationController =
                    mService.getRecentsAnimationController();
            final boolean shouldApplyRecentsInputConsumer = recentsAnimationController != null
                    && recentsAnimationController.shouldApplyInputConsumer(w.mActivityRecord);
            final int type = w.mAttrs.type;
            final boolean isVisible = w.isVisibleLw();
            if (inputChannel == null || inputWindowHandle == null || w.mRemoved
                    || (w.cantReceiveTouchInput() && !shouldApplyRecentsInputConsumer)) {
                if (w.mWinAnimator.hasSurface()) {
                    // Assign an InputInfo with type to the overlay window which can't receive input
                    // event. This is used to omit Surfaces from occlusion detection.
                    populateOverlayInputInfo(mInvalidInputWindow, w.getName(), type, isVisible);
                    mInputTransaction.setInputWindowInfo(
                            w.mWinAnimator.mSurfaceController.getClientViewRootSurface(),
                            mInvalidInputWindow);
                    return;
                }
                // Skip this window because it cannot possibly receive input.
                return;
            }

            final int flags = w.mAttrs.flags;
            final int privateFlags = w.mAttrs.privateFlags;
            final boolean hasFocus = w.isFocused();

            if (mAddRecentsAnimationInputConsumerHandle && shouldApplyRecentsInputConsumer) {
                if (recentsAnimationController.updateInputConsumerForApp(
                        mRecentsAnimationInputConsumer.mWindowHandle, hasFocus)) {
                    mRecentsAnimationInputConsumer.show(mInputTransaction, w);
                    mAddRecentsAnimationInputConsumerHandle = false;
                }
            }

            if (w.inPinnedWindowingMode()) {
                if (mAddPipInputConsumerHandle) {
                    final Task rootTask = w.getTask().getRootTask();
                    mPipInputConsumer.mWindowHandle.replaceTouchableRegionWithCrop(
                            rootTask.getSurfaceControl());
                    // We set the layer to z=MAX-1 so that it's always on top.
                    mPipInputConsumer.reparent(mInputTransaction, rootTask);
                    mPipInputConsumer.show(mInputTransaction, Integer.MAX_VALUE - 1);
                    mAddPipInputConsumerHandle = false;
                }
            }

            if (mAddNavInputConsumerHandle) {
                mNavInputConsumer.show(mInputTransaction, w);
                mAddNavInputConsumerHandle = false;
            }

            if (mAddWallpaperInputConsumerHandle) {
                if (w.mAttrs.type == TYPE_WALLPAPER && w.isVisibleLw()) {
                    // Add the wallpaper input consumer above the first visible wallpaper.
                    mWallpaperInputConsumer.show(mInputTransaction, w);
                    mAddWallpaperInputConsumerHandle = false;
                }
            }

            if ((privateFlags & PRIVATE_FLAG_DISABLE_WALLPAPER_TOUCH_EVENTS) != 0) {
                mDisableWallpaperTouchEvents = true;
            }
            final boolean hasWallpaper = mWallpaperController.isWallpaperTarget(w)
                    && !mService.mPolicy.isKeyguardShowing()
                    && !mDisableWallpaperTouchEvents;

            // If there's a drag in progress and 'child' is a potential drop target,
            // make sure it's been told about the drag
            if (mInDrag && isVisible && w.getDisplayContent().isDefaultDisplay) {
                mService.mDragDropController.sendDragStartedIfNeededLocked(w);
            }

            populateInputWindowHandle(
                    inputWindowHandle, w, flags, type, isVisible, hasFocus, hasWallpaper);

            // register key interception info
            mService.mKeyInterceptionInfoForToken.put(inputWindowHandle.token,
                    w.getKeyInterceptionInfo());

            if (w.mWinAnimator.hasSurface()) {
                mInputTransaction.setInputWindowInfo(
                    w.mWinAnimator.mSurfaceController.getClientViewRootSurface(),
                    inputWindowHandle);
            }
        }
    }

    // This would reset InputWindowHandle fields to prevent it could be found by input event.
    // We need to check if any new field of InputWindowHandle could impact the result.
    private static void populateOverlayInputInfo(final InputWindowHandle inputWindowHandle,
            final String name, final int type, final boolean isVisible) {
        inputWindowHandle.name = name;
        inputWindowHandle.layoutParamsType = type;
        inputWindowHandle.dispatchingTimeoutNanos =
                WindowManagerService.DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;
        inputWindowHandle.visible = isVisible;
        inputWindowHandle.canReceiveKeys = false;
        inputWindowHandle.hasFocus = false;
        inputWindowHandle.inputFeatures = INPUT_FEATURE_NO_INPUT_CHANNEL;
        inputWindowHandle.scaleFactor = 1;
        inputWindowHandle.layoutParamsFlags =
                FLAG_NOT_TOUCH_MODAL | FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE;
        inputWindowHandle.portalToDisplayId = INVALID_DISPLAY;
        inputWindowHandle.touchableRegion.setEmpty();
        inputWindowHandle.setTouchableRegionCrop(null);
    }

    /**
     * Helper function to generate an InputInfo with type SECURE_SYSTEM_OVERLAY. This input
     * info will not have an input channel or be touchable, but is used to omit Surfaces
     * from occlusion detection, so that System global overlays like the Watermark aren't
     * counted by the InputDispatcher as occluding applications below.
     */
    static void setTrustedOverlayInputInfo(SurfaceControl sc, SurfaceControl.Transaction t,
            int displayId, String name) {
        InputWindowHandle inputWindowHandle = new InputWindowHandle(null, displayId);
        populateOverlayInputInfo(inputWindowHandle, name, TYPE_SECURE_SYSTEM_OVERLAY, true);
        t.setInputWindowInfo(sc, inputWindowHandle);
    }
}
