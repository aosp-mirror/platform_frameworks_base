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

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.INPUT_CONSUMER_PIP;
import static android.view.WindowManager.INPUT_CONSUMER_RECENTS_ANIMATION;
import static android.view.WindowManager.INPUT_CONSUMER_WALLPAPER;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_DISABLE_WALLPAPER_TOUCH_EVENTS;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_CONSUMER;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_MAGNIFICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
import static android.view.WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_FOCUS_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_INPUT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.LOGTAG_INPUT_FOCUS;

import android.graphics.Rect;
import android.graphics.Region;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Trace;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.EventLog;
import android.util.Slog;
import android.view.InputChannel;
import android.view.InputEventReceiver;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;

import java.io.PrintWriter;
import java.util.Set;
import java.util.function.Consumer;

final class InputMonitor {
    private final WindowManagerService mService;

    // Current input focus token for keys and other non-touch events.  May be null.
    private IBinder mInputFocus = null;

    // When true, need to call updateInputWindowsLw().
    private boolean mUpdateInputWindowsNeeded = true;
    private boolean mUpdateInputWindowsPending;
    private boolean mUpdateInputWindowsImmediately;

    private boolean mDisableWallpaperTouchEvents;
    private final Region mTmpRegion = new Region();
    private final UpdateInputForAllWindowsConsumer mUpdateInputForAllWindowsConsumer;

    private final int mDisplayId;
    private final DisplayContent mDisplayContent;
    private boolean mDisplayRemoved;
    private int mDisplayWidth;
    private int mDisplayHeight;

    private final SurfaceControl.Transaction mInputTransaction;
    private final Handler mHandler;

    /**
     * The set of input consumer added to the window manager by name, which consumes input events
     * for the windows below it.
     */
    private final ArrayMap<String, InputConsumerImpl> mInputConsumers = new ArrayMap();

    /**
     * Representation of a input consumer that the policy has added to the window manager to consume
     * input events going to windows below it.
     */
    static final class EventReceiverInputConsumer extends InputConsumerImpl {
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

        /** Removes the input consumer from the window manager. */
        void dismiss() {
            synchronized (mService.mGlobalLock) {
                mInputMonitor.mInputConsumers.remove(mName);
                hide(mInputMonitor.mInputTransaction);
                mInputMonitor.updateInputWindowsLw(true /* force */);
            }
        }

        /** Disposes the input consumer and input receiver from the associated thread. */
        void dispose() {
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
        consumer.layout(mInputTransaction, mDisplayWidth, mDisplayHeight);
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
        if (mDisplayWidth == dw && mDisplayHeight == dh) {
            return;
        }
        mDisplayWidth = dw;
        mDisplayHeight = dh;
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
                consumer.mWindowHandle.focusable = true;
                break;
            default:
                throw new IllegalArgumentException("Illegal input consumer : " + name
                        + ", display: " + mDisplayId);
        }
        addInputConsumer(name, consumer);
    }

    @VisibleForTesting
    void populateInputWindowHandle(final InputWindowHandleWrapper inputWindowHandle,
            final WindowState w) {
        // Add a window to our list of input windows.
        inputWindowHandle.setInputApplicationHandle(w.mActivityRecord != null
                ? w.mActivityRecord.getInputApplicationHandle(false /* update */) : null);
        inputWindowHandle.setToken(w.mInputChannelToken);
        inputWindowHandle.setDispatchingTimeoutMillis(w.getInputDispatchingTimeoutMillis());
        inputWindowHandle.setTouchOcclusionMode(w.getTouchOcclusionMode());
        inputWindowHandle.setInputFeatures(w.mAttrs.inputFeatures);
        inputWindowHandle.setPaused(w.mActivityRecord != null && w.mActivityRecord.paused);
        inputWindowHandle.setVisible(w.isVisible());

        final boolean focusable = w.canReceiveKeys()
                && (mService.mPerDisplayFocusEnabled || mDisplayContent.isOnTop());
        inputWindowHandle.setFocusable(focusable);

        final boolean hasWallpaper = mDisplayContent.mWallpaperController.isWallpaperTarget(w)
                && !mService.mPolicy.isKeyguardShowing()
                && !mDisableWallpaperTouchEvents;
        inputWindowHandle.setHasWallpaper(hasWallpaper);

        final Rect frame = w.getFrame();
        inputWindowHandle.setFrame(frame.left, frame.top, frame.right, frame.bottom);

        // Surface insets are hardcoded to be the same in all directions
        // and we could probably deprecate the "left/right/top/bottom" concept.
        // we avoid reintroducing this concept by just choosing one of them here.
        inputWindowHandle.setSurfaceInset(w.mAttrs.surfaceInsets.left);

        // If we are scaling the window, input coordinates need to be inversely scaled to map from
        // what is on screen to what is actually being touched in the UI.
        inputWindowHandle.setScaleFactor(w.mGlobalScale != 1f ? (1f / w.mGlobalScale) : 1f);

        final int flags = w.getSurfaceTouchableRegion(mTmpRegion, w.mAttrs.flags);
        inputWindowHandle.setTouchableRegion(mTmpRegion);
        inputWindowHandle.setLayoutParamsFlags(flags);

        boolean useSurfaceCrop = false;
        final Task task = w.getTask();
        if (task != null) {
            // TODO(b/165794636): Remove the special case for freeform window once drag resizing is
            // handled by WM shell.
            if (task.isOrganized() && task.getWindowingMode() != WINDOWING_MODE_FULLSCREEN
                        && !task.inFreeformWindowingMode()) {
                // If the window is in a TaskManaged by a TaskOrganizer then most cropping will
                // be applied using the SurfaceControl hierarchy from the Organizer. This means
                // we need to make sure that these changes in crop are reflected in the input
                // windows, and so ensure this flag is set so that the input crop always reflects
                // the surface hierarchy.
                // TODO(b/168252846): we have some issues with modal-windows, so we need to cross
                // that bridge now that we organize full-screen Tasks.
                inputWindowHandle.setTouchableRegionCrop(null /* Use this surfaces crop */);
                inputWindowHandle.setReplaceTouchableRegionWithCrop(true);
                useSurfaceCrop = true;
            } else if (task.cropWindowsToRootTaskBounds() && !w.inFreeformWindowingMode()) {
                inputWindowHandle.setTouchableRegionCrop(task.getRootTask().getSurfaceControl());
                inputWindowHandle.setReplaceTouchableRegionWithCrop(false);
                useSurfaceCrop = true;
            }
        }
        if (!useSurfaceCrop) {
            inputWindowHandle.setReplaceTouchableRegionWithCrop(false);
            inputWindowHandle.setTouchableRegionCrop(null);
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
     * Called when the current input focus changes. Will apply it in next updateInputWindows.
     * Layer assignment is assumed to be complete by the time this is called.
     */
    void setInputFocusLw(WindowState newWindow, boolean updateInputWindows) {
        ProtoLog.v(WM_DEBUG_FOCUS_LIGHT, "Input focus has changed to %s display=%d",
                newWindow, mDisplayId);
        final IBinder focus = newWindow != null ? newWindow.mInputChannelToken : null;
        if (focus == mInputFocus) {
            return;
        }

        if (newWindow != null && newWindow.canReceiveKeys()) {
            // Displaying a window implicitly causes dispatching to be unpaused.
            // This is to protect against bugs if someone pauses dispatching but
            // forgets to resume.
            newWindow.mToken.paused = false;
        }

        setUpdateInputWindowsNeededLw();

        if (updateInputWindows) {
            updateInputWindowsLw(false /*force*/);
        }
    }

    /**
     * Called when the current input focus changes.
     */
    private void updateInputFocusRequest(InputConsumerImpl recentsAnimationInputConsumer) {
        final WindowState focus = mDisplayContent.mCurrentFocus;
        // Request focus for the recents animation input consumer if an input consumer should
        // be applied for the window.
        if (recentsAnimationInputConsumer != null && focus != null) {
            final RecentsAnimationController recentsAnimationController =
                    mService.getRecentsAnimationController();
            final boolean shouldApplyRecentsInputConsumer = recentsAnimationController != null
                    && recentsAnimationController.shouldApplyInputConsumer(focus.mActivityRecord);
            if (shouldApplyRecentsInputConsumer) {
                requestFocus(recentsAnimationInputConsumer.mWindowHandle.token,
                        recentsAnimationInputConsumer.mName);
                return;
            }
        }

        final IBinder focusToken = focus != null ? focus.mInputChannelToken : null;
        if (focusToken == null) {
            mInputFocus = null;
            return;
        }

        if (!focus.mWinAnimator.hasSurface() || !focus.mInputWindowHandle.isFocusable()) {
            ProtoLog.v(WM_DEBUG_FOCUS_LIGHT, "Focus not requested for window=%s"
                    + " because it has no surface or is not focusable.", focus);
            mInputFocus = null;
            return;
        }

        requestFocus(focusToken, focus.getName());
    }

    private void requestFocus(IBinder focusToken, String windowName) {
        if (focusToken == mInputFocus) {
            return;
        }

        mInputFocus = focusToken;
        mInputTransaction.setFocusedWindow(mInputFocus, windowName, mDisplayId);
        EventLog.writeEvent(LOGTAG_INPUT_FOCUS, "Focus request " + windowName,
                "reason=UpdateInputWindows");
        ProtoLog.v(WM_DEBUG_FOCUS_LIGHT, "Focus requested for window=%s", windowName);
    }

    void setFocusedAppLw(ActivityRecord newApp) {
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
        InputConsumerImpl mPipInputConsumer;
        InputConsumerImpl mWallpaperInputConsumer;
        InputConsumerImpl mRecentsAnimationInputConsumer;

        private boolean mAddPipInputConsumerHandle;
        private boolean mAddWallpaperInputConsumerHandle;
        private boolean mAddRecentsAnimationInputConsumerHandle;

        boolean mInDrag;

        private void updateInputWindows(boolean inDrag) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "updateInputWindows");

            mPipInputConsumer = getInputConsumer(INPUT_CONSUMER_PIP);
            mWallpaperInputConsumer = getInputConsumer(INPUT_CONSUMER_WALLPAPER);
            mRecentsAnimationInputConsumer = getInputConsumer(INPUT_CONSUMER_RECENTS_ANIMATION);

            mAddPipInputConsumerHandle = mPipInputConsumer != null;
            mAddWallpaperInputConsumerHandle = mWallpaperInputConsumer != null;
            mAddRecentsAnimationInputConsumerHandle = mRecentsAnimationInputConsumer != null;

            mDisableWallpaperTouchEvents = false;
            mInDrag = inDrag;

            resetInputConsumers(mInputTransaction);
            mDisplayContent.forAllWindows(this, true /* traverseTopToBottom */);
            updateInputFocusRequest(mRecentsAnimationInputConsumer);

            if (!mUpdateInputWindowsImmediately) {
                mDisplayContent.getPendingTransaction().merge(mInputTransaction);
                mDisplayContent.scheduleAnimation();
            }

            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }

        @Override
        public void accept(WindowState w) {
            final InputWindowHandleWrapper inputWindowHandle = w.mInputWindowHandle;
            final RecentsAnimationController recentsAnimationController =
                    mService.getRecentsAnimationController();
            final boolean shouldApplyRecentsInputConsumer = recentsAnimationController != null
                    && recentsAnimationController.shouldApplyInputConsumer(w.mActivityRecord);
            if (w.mInputChannelToken == null || w.mRemoved
                    || (!w.canReceiveTouchInput() && !shouldApplyRecentsInputConsumer)) {
                if (w.mWinAnimator.hasSurface()) {
                    // Make sure the input info can't receive input event. It may be omitted from
                    // occlusion detection depending on the type or if it's a trusted overlay.
                    populateOverlayInputInfo(inputWindowHandle, w);
                    setInputWindowInfoIfNeeded(mInputTransaction,
                            w.mWinAnimator.mSurfaceController.mSurfaceControl, inputWindowHandle);
                    return;
                }
                // Skip this window because it cannot possibly receive input.
                return;
            }

            final int privateFlags = w.mAttrs.privateFlags;

            if (mAddRecentsAnimationInputConsumerHandle && shouldApplyRecentsInputConsumer) {
                if (recentsAnimationController.updateInputConsumerForApp(
                        mRecentsAnimationInputConsumer.mWindowHandle)) {
                    mRecentsAnimationInputConsumer.show(mInputTransaction,
                            recentsAnimationController.getHighestLayerActivity());
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

            if (mAddWallpaperInputConsumerHandle) {
                if (w.mAttrs.type == TYPE_WALLPAPER && w.isVisible()) {
                    // Add the wallpaper input consumer above the first visible wallpaper.
                    mWallpaperInputConsumer.show(mInputTransaction, w);
                    mAddWallpaperInputConsumerHandle = false;
                }
            }

            if ((privateFlags & PRIVATE_FLAG_DISABLE_WALLPAPER_TOUCH_EVENTS) != 0) {
                mDisableWallpaperTouchEvents = true;
            }

            // If there's a drag in progress and 'child' is a potential drop target,
            // make sure it's been told about the drag
            if (mInDrag && w.isVisible() && w.getDisplayContent().isDefaultDisplay) {
                mService.mDragDropController.sendDragStartedIfNeededLocked(w);
            }

            // register key interception info
            mService.mKeyInterceptionInfoForToken.put(w.mInputChannelToken,
                    w.getKeyInterceptionInfo());

            if (w.mWinAnimator.hasSurface()) {
                populateInputWindowHandle(inputWindowHandle, w);
                setInputWindowInfoIfNeeded(mInputTransaction,
                        w.mWinAnimator.mSurfaceController.mSurfaceControl, inputWindowHandle);
            }
        }
    }

    @VisibleForTesting
    static void setInputWindowInfoIfNeeded(SurfaceControl.Transaction t, SurfaceControl sc,
            InputWindowHandleWrapper inputWindowHandle) {
        if (DEBUG_INPUT) {
            Slog.d(TAG_WM, "Update InputWindowHandle: " + inputWindowHandle);
        }
        if (inputWindowHandle.isChanged()) {
            inputWindowHandle.applyChangesToSurface(t, sc);
        }
    }

    static void populateOverlayInputInfo(InputWindowHandleWrapper inputWindowHandle,
            WindowState w) {
        populateOverlayInputInfo(inputWindowHandle, w.isVisible());
        inputWindowHandle.setTouchOcclusionMode(w.getTouchOcclusionMode());
    }

    // This would reset InputWindowHandle fields to prevent it could be found by input event.
    // We need to check if any new field of InputWindowHandle could impact the result.
    @VisibleForTesting
    static void populateOverlayInputInfo(InputWindowHandleWrapper inputWindowHandle,
            boolean isVisible) {
        inputWindowHandle.setDispatchingTimeoutMillis(0); // It should never receive input.
        inputWindowHandle.setVisible(isVisible);
        inputWindowHandle.setFocusable(false);
        inputWindowHandle.setInputFeatures(INPUT_FEATURE_NO_INPUT_CHANNEL);
        // The input window handle without input channel must not have a token.
        inputWindowHandle.setToken(null);
        inputWindowHandle.setScaleFactor(1f);
        inputWindowHandle.setLayoutParamsFlags(
                FLAG_NOT_TOUCH_MODAL | FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE);
        inputWindowHandle.setPortalToDisplayId(INVALID_DISPLAY);
        inputWindowHandle.clearTouchableRegion();
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
        final InputWindowHandleWrapper inputWindowHandle = new InputWindowHandleWrapper(
                new InputWindowHandle(null /* inputApplicationHandle */, displayId));
        inputWindowHandle.setName(name);
        inputWindowHandle.setLayoutParamsType(TYPE_SECURE_SYSTEM_OVERLAY);
        inputWindowHandle.setTrustedOverlay(true);
        populateOverlayInputInfo(inputWindowHandle, true /* isVisible */);
        setInputWindowInfoIfNeeded(t, sc, inputWindowHandle);
    }

    static boolean isTrustedOverlay(int type) {
        return type == TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY
                || type == TYPE_INPUT_METHOD || type == TYPE_INPUT_METHOD_DIALOG
                || type == TYPE_MAGNIFICATION_OVERLAY || type == TYPE_STATUS_BAR
                || type == TYPE_NOTIFICATION_SHADE
                || type == TYPE_NAVIGATION_BAR
                || type == TYPE_NAVIGATION_BAR_PANEL
                || type == TYPE_SECURE_SYSTEM_OVERLAY
                || type == TYPE_DOCK_DIVIDER
                || type == TYPE_ACCESSIBILITY_OVERLAY
                || type == TYPE_INPUT_CONSUMER
                || type == TYPE_VOICE_INTERACTION;
    }
}
