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
import static android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION;
import static android.view.WindowManager.INPUT_CONSUMER_PIP;
import static android.view.WindowManager.INPUT_CONSUMER_RECENTS_ANIMATION;
import static android.view.WindowManager.INPUT_CONSUMER_WALLPAPER;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;
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
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_FOCUS_LIGHT;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_INPUT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.LOGTAG_INPUT_FOCUS;

import static java.lang.Integer.MAX_VALUE;

import android.annotation.Nullable;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Handler;
import android.os.IBinder;
import android.os.InputConfig;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.util.EventLog;
import android.util.Slog;
import android.view.InputChannel;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.protolog.common.ProtoLog;
import com.android.server.LocalServices;
import com.android.server.inputmethod.InputMethodManagerInternal;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.function.Consumer;

final class InputMonitor {
    private final WindowManagerService mService;

    // Current input focus token for keys and other non-touch events.  May be null.
    IBinder mInputFocus = null;
    long mInputFocusRequestTimeMillis = 0;

    // When true, need to call updateInputWindowsLw().
    private boolean mUpdateInputWindowsNeeded = true;
    private boolean mUpdateInputWindowsPending;
    private boolean mUpdateInputWindowsImmediately;

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
    private final ArrayList<InputConsumerImpl> mInputConsumers = new ArrayList<>();

    /**
     * Set when recents (overview) is active as part of a shell transition. While set, any focus
     * going to the recents activity will be redirected to the Recents input consumer. Since we
     * draw the live-tile above the recents activity, we also need to provide that activity as a
     * z-layering reference so that we can place the recents input consumer above it.
     */
    private WeakReference<ActivityRecord> mActiveRecentsActivity = null;
    private WeakReference<ActivityRecord> mActiveRecentsLayerRef = null;

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
        mService.mTransactionFactory.get()
            // Make sure any pending setInputWindowInfo transactions are completed. That
            // prevents the timing of updating input info of removed display after cleanup.
            .addWindowInfosReportedListener(() ->
                // It calls InputDispatcher::setInputWindows directly.
                mService.mInputManager.onDisplayRemoved(mDisplayId))
            .apply();
        mDisplayRemoved = true;
    }

    private void addInputConsumer(InputConsumerImpl consumer) {
        mInputConsumers.add(consumer);
        consumer.linkToDeathRecipient();
        consumer.layout(mInputTransaction, mDisplayWidth, mDisplayHeight);
        updateInputWindowsLw(true /* force */);
    }

    boolean destroyInputConsumer(IBinder token) {
        for (int i = 0; i < mInputConsumers.size(); i++) {
            final InputConsumerImpl consumer = mInputConsumers.get(i);
            if (consumer != null && consumer.mToken == token) {
                consumer.disposeChannelsLw(mInputTransaction);
                mInputConsumers.remove(consumer);
                updateInputWindowsLw(true /* force */);
                return true;
            }
        }
        return false;
    }

    InputConsumerImpl getInputConsumer(String name) {
        // Search in reverse order as the latest input consumer with the name takes precedence
        for (int i = mInputConsumers.size() - 1; i >= 0; i--) {
            final InputConsumerImpl consumer = mInputConsumers.get(i);
            if (consumer.mName.equals(name)) {
                return consumer;
            }
        }
        return null;
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
                mInputConsumers.get(i).layout(mInputTransaction, dw, dh);
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
            mInputConsumers.get(i).hide(t);
        }
    }

    void createInputConsumer(IBinder token, String name, InputChannel inputChannel, int clientPid,
            UserHandle clientUser) {
        final InputConsumerImpl existingConsumer = getInputConsumer(name);
        if (existingConsumer != null && existingConsumer.mClientUser.equals(clientUser)) {
            throw new IllegalStateException("Existing input consumer found with name: " + name
                    + ", display: " + mDisplayId + ", user: " + clientUser);
        }

        final InputConsumerImpl consumer = new InputConsumerImpl(mService, token, name,
                inputChannel, clientPid, clientUser, mDisplayId, mInputTransaction);
        switch (name) {
            case INPUT_CONSUMER_WALLPAPER:
                consumer.mWindowHandle.inputConfig |= InputConfig.DUPLICATE_TOUCH_TO_WALLPAPER;
                break;
            case INPUT_CONSUMER_PIP:
                // This is a valid consumer type, but we don't need any additional configurations.
                break;
            case INPUT_CONSUMER_RECENTS_ANIMATION:
                consumer.mWindowHandle.inputConfig &= ~InputConfig.NOT_FOCUSABLE;
                break;
            default:
                throw new IllegalArgumentException("Illegal input consumer : " + name
                        + ", display: " + mDisplayId);
        }
        addInputConsumer(consumer);
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
        inputWindowHandle.setPaused(w.mActivityRecord != null && w.mActivityRecord.paused);
        inputWindowHandle.setWindowToken(w.mClient.asBinder());

        inputWindowHandle.setName(w.getName());

        // Update layout params flags to force the window to be not touch modal. We do this to
        // restrict the window's touchable region to the task even if it requests touches outside
        // its window bounds. An example is a dialog in primary split should get touches outside its
        // window within the primary task but should not get any touches going to the secondary
        // task.
        int flags = w.mAttrs.flags;
        if (w.mAttrs.isModal()) {
            flags = flags | FLAG_NOT_TOUCH_MODAL;
        }
        inputWindowHandle.setLayoutParamsFlags(flags);
        inputWindowHandle.setInputConfigMasked(
                InputConfigAdapter.getInputConfigFromWindowParams(
                        w.mAttrs.type, flags, w.mAttrs.inputFeatures),
                InputConfigAdapter.getMask());

        final boolean focusable = w.canReceiveKeys()
                && (mDisplayContent.hasOwnFocus() || mDisplayContent.isOnTop());
        inputWindowHandle.setFocusable(focusable);

        final boolean hasWallpaper = mDisplayContent.mWallpaperController.isWallpaperTarget(w)
                && !mService.mPolicy.isKeyguardShowing()
                && w.mAttrs.areWallpaperTouchEventsEnabled();
        inputWindowHandle.setHasWallpaper(hasWallpaper);

        // Surface insets are hardcoded to be the same in all directions
        // and we could probably deprecate the "left/right/top/bottom" concept.
        // we avoid reintroducing this concept by just choosing one of them here.
        inputWindowHandle.setSurfaceInset(w.mAttrs.surfaceInsets.left);

        // If we are scaling the window, input coordinates need to be inversely scaled to map from
        // what is on screen to what is actually being touched in the UI.
        inputWindowHandle.setScaleFactor(w.mGlobalScale != 1f ? (1f / w.mGlobalScale) : 1f);

        boolean useSurfaceBoundsAsTouchRegion = false;
        SurfaceControl touchableRegionCrop = null;
        final Task task = w.getTask();
        if (task != null) {
            if (task.isOrganized() && task.getWindowingMode() != WINDOWING_MODE_FULLSCREEN) {
                // If the window is in a TaskManaged by a TaskOrganizer then most cropping will
                // be applied using the SurfaceControl hierarchy from the Organizer. This means
                // we need to make sure that these changes in crop are reflected in the input
                // windows, and so ensure this flag is set so that the input crop always reflects
                // the surface hierarchy. However, we only want to set this when the client did
                // not already provide a touchable region, so that we don't ignore the one provided.
                if (w.mTouchableInsets != TOUCHABLE_INSETS_REGION) {
                    useSurfaceBoundsAsTouchRegion = true;
                }

                if (w.mAttrs.isModal()) {
                    TaskFragment parent = w.getTaskFragment();
                    touchableRegionCrop = parent != null ? parent.getSurfaceControl() : null;
                }
            } else if (task.cropWindowsToRootTaskBounds() && !w.inFreeformWindowingMode()) {
                touchableRegionCrop = task.getRootTask().getSurfaceControl();
            }
        }
        inputWindowHandle.setReplaceTouchableRegionWithCrop(useSurfaceBoundsAsTouchRegion);
        inputWindowHandle.setTouchableRegionCrop(touchableRegionCrop);

        if (!useSurfaceBoundsAsTouchRegion) {
            w.getSurfaceTouchableRegion(mTmpRegion, w.mAttrs);
            inputWindowHandle.setTouchableRegion(mTmpRegion);
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
     * Inform InputMonitor when recents is active so it can enable the recents input consumer.
     * @param activity The active recents activity. {@code null} means recents is not active.
     * @param layer An activity whose Z-layer is used as a reference for how to sort the consumer.
     */
    void setActiveRecents(@Nullable ActivityRecord activity, @Nullable ActivityRecord layer) {
        final boolean clear = activity == null;
        final boolean wasActive = mActiveRecentsActivity != null && mActiveRecentsLayerRef != null;
        mActiveRecentsActivity = clear ? null : new WeakReference<>(activity);
        mActiveRecentsLayerRef = clear ? null : new WeakReference<>(layer);
        if (clear && wasActive) {
            setUpdateInputWindowsNeededLw();
        }
    }

    private static <T> T getWeak(WeakReference<T> ref) {
        return ref != null ? ref.get() : null;
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
            // Apply recents input consumer when the focusing window is in recents animation.
            final boolean shouldApplyRecentsInputConsumer = (recentsAnimationController != null
                    && recentsAnimationController.shouldApplyInputConsumer(focus.mActivityRecord))
                    // Shell transitions doesn't use RecentsAnimationController but we still
                    // have carryover legacy logic that relies on the consumer.
                    || (getWeak(mActiveRecentsActivity) != null && focus.inTransition()
                            // only take focus from the recents activity to avoid intercepting
                            // events before the gesture officially starts.
                            && focus.isActivityTypeHomeOrRecents());
            if (shouldApplyRecentsInputConsumer) {
                if (mInputFocus != recentsAnimationInputConsumer.mWindowHandle.token) {
                    requestFocus(recentsAnimationInputConsumer.mWindowHandle.token,
                            recentsAnimationInputConsumer.mName);
                }
                if (mDisplayContent.mInputMethodWindow != null
                        && mDisplayContent.mInputMethodWindow.isVisible()) {
                    // Hiding IME/IME icon when recents input consumer gain focus.
                    final boolean isImeAttachedToApp = mDisplayContent.isImeAttachedToApp();
                    if (!isImeAttachedToApp) {
                        // Hiding IME if IME window is not attached to app since it's not proper to
                        // snapshot Task with IME window to animate together in this case.
                        final InputMethodManagerInternal inputMethodManagerInternal =
                                LocalServices.getService(InputMethodManagerInternal.class);
                        if (inputMethodManagerInternal != null) {
                            inputMethodManagerInternal.hideCurrentInputMethod(
                                    SoftInputShowHideReason.HIDE_RECENTS_ANIMATION);
                        }
                        // Ensure removing the IME snapshot when the app no longer to show on the
                        // task snapshot (also taking the new task snaphot to update the overview).
                        final ActivityRecord app = mDisplayContent.getImeInputTarget() != null
                                ? mDisplayContent.getImeInputTarget().getActivityRecord() : null;
                        if (app != null) {
                            mDisplayContent.removeImeSurfaceImmediately();
                            if (app.getTask() != null) {
                                mDisplayContent.mAtmService.takeTaskSnapshot(app.getTask().mTaskId,
                                        true /* updateCache */);
                            }
                        }
                    } else {
                        // Disable IME icon explicitly when IME attached to the app in case
                        // IME icon might flickering while swiping to the next app task still
                        // in animating before the next app window focused, or IME icon
                        // persists on the bottom when swiping the task to recents.
                        InputMethodManagerInternal.get().updateImeWindowStatus(
                                true /* disableImeIcon */);
                    }
                }
                return;
            }
        }

        final IBinder focusToken = focus != null ? focus.mInputChannelToken : null;
        if (focusToken == null) {
            if (recentsAnimationInputConsumer != null
                    && recentsAnimationInputConsumer.mWindowHandle != null
                    && mInputFocus == recentsAnimationInputConsumer.mWindowHandle.token) {
                // Avoid removing input focus from recentsAnimationInputConsumer.
                // When the recents animation input consumer has the input focus,
                // mInputFocus does not match to mDisplayContent.mCurrentFocus. Making it to be
                // a special case, that do not remove the input focus from it when
                // mDisplayContent.mCurrentFocus is null. This special case should be removed
                // once recentAnimationInputConsumer is removed.
                return;
            }
            // When an app is focused, but its window is not showing yet, remove the input focus
            // from the current window. This enforces the input focus to match
            // mDisplayContent.mCurrentFocus. However, if more special cases are discovered that
            // the input focus and mDisplayContent.mCurrentFocus are expected to mismatch,
            // the whole logic of how and when to revoke focus needs to be checked.
            if (mDisplayContent.mFocusedApp != null && mInputFocus != null) {
                ProtoLog.v(WM_DEBUG_FOCUS_LIGHT, "App %s is focused,"
                        + " but the window is not ready. Start a transaction to remove focus from"
                        + " the window of non-focused apps.",
                        mDisplayContent.mFocusedApp.getName());
                EventLog.writeEvent(LOGTAG_INPUT_FOCUS, "Requesting to set focus to null window",
                        "reason=UpdateInputWindows");
                mInputTransaction.removeCurrentInputFocus(mDisplayId);
            }
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
        mInputFocusRequestTimeMillis = SystemClock.uptimeMillis();
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
        if (!mInputConsumers.isEmpty()) {
            pw.println(prefix + "InputConsumers:");
            for (int i = 0; i < mInputConsumers.size(); i++) {
                final InputConsumerImpl consumer = mInputConsumers.get(i);
                consumer.dump(pw, consumer.mName, prefix);
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

        private boolean mInDrag;
        private final Rect mTmpRect = new Rect();

        private void updateInputWindows(boolean inDrag) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "updateInputWindows");

            mPipInputConsumer = getInputConsumer(INPUT_CONSUMER_PIP);
            mWallpaperInputConsumer = getInputConsumer(INPUT_CONSUMER_WALLPAPER);
            mRecentsAnimationInputConsumer = getInputConsumer(INPUT_CONSUMER_RECENTS_ANIMATION);

            mAddPipInputConsumerHandle = mPipInputConsumer != null;
            mAddWallpaperInputConsumerHandle = mWallpaperInputConsumer != null;
            mAddRecentsAnimationInputConsumerHandle = mRecentsAnimationInputConsumer != null;

            mInDrag = inDrag;

            resetInputConsumers(mInputTransaction);
            // Update recents input consumer layer if active
            final ActivityRecord activeRecents = getWeak(mActiveRecentsActivity);
            if (mAddRecentsAnimationInputConsumerHandle && activeRecents != null
                    && activeRecents.getSurfaceControl() != null) {
                WindowContainer layer = getWeak(mActiveRecentsLayerRef);
                layer = layer != null ? layer : activeRecents;
                // Handle edge-case for SUW where windows don't exist yet
                if (layer.getSurfaceControl() != null) {
                    final WindowState targetAppMainWindow = activeRecents.findMainWindow();
                    if (targetAppMainWindow != null) {
                        targetAppMainWindow.getBounds(mTmpRect);
                        mRecentsAnimationInputConsumer.mWindowHandle.touchableRegion.set(mTmpRect);
                    }
                    mRecentsAnimationInputConsumer.show(mInputTransaction, layer);
                    mAddRecentsAnimationInputConsumerHandle = false;
                }
            }
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
            if (w.mInputChannelToken == null || w.mRemoved || !w.canReceiveTouchInput()) {
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

            // This only works for legacy transitions.
            final RecentsAnimationController recentsAnimationController =
                    mService.getRecentsAnimationController();
            final boolean shouldApplyRecentsInputConsumer = recentsAnimationController != null
                    && recentsAnimationController.shouldApplyInputConsumer(w.mActivityRecord);
            if (mAddRecentsAnimationInputConsumerHandle && shouldApplyRecentsInputConsumer) {
                if (recentsAnimationController.updateInputConsumerForApp(
                        mRecentsAnimationInputConsumer.mWindowHandle)) {
                    final DisplayArea targetDA =
                            recentsAnimationController.getTargetAppDisplayArea();
                    if (targetDA != null) {
                        mRecentsAnimationInputConsumer.reparent(mInputTransaction, targetDA);
                        mRecentsAnimationInputConsumer.show(mInputTransaction, MAX_VALUE - 2);
                        mAddRecentsAnimationInputConsumerHandle = false;
                    }
                }
            }

            if (w.inPinnedWindowingMode()) {
                if (mAddPipInputConsumerHandle) {
                    final Task rootTask = w.getTask().getRootTask();
                    mPipInputConsumer.mWindowHandle.replaceTouchableRegionWithCrop(
                            rootTask.getSurfaceControl());
                    final DisplayArea targetDA = rootTask.getDisplayArea();
                    // We set the layer to z=MAX-1 so that it's always on top.
                    if (targetDA != null) {
                        mPipInputConsumer.layout(mInputTransaction, rootTask.getBounds());
                        mPipInputConsumer.reparent(mInputTransaction, targetDA);
                        mPipInputConsumer.show(mInputTransaction, MAX_VALUE - 1);
                        mAddPipInputConsumerHandle = false;
                    }
                }
            }

            if (mAddWallpaperInputConsumerHandle) {
                if (w.mAttrs.type == TYPE_WALLPAPER && w.isVisible()) {
                    mWallpaperInputConsumer.mWindowHandle
                            .replaceTouchableRegionWithCrop(null /* use this surface's bounds */);
                    // Add the wallpaper input consumer above the first visible wallpaper.
                    mWallpaperInputConsumer.show(mInputTransaction, w);
                    mAddWallpaperInputConsumerHandle = false;
                }
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
        populateOverlayInputInfo(inputWindowHandle);
        inputWindowHandle.setTouchOcclusionMode(w.getTouchOcclusionMode());
    }

    // This would reset InputWindowHandle fields to prevent it could be found by input event.
    // We need to check if any new field of InputWindowHandle could impact the result.
    @VisibleForTesting
    static void populateOverlayInputInfo(InputWindowHandleWrapper inputWindowHandle) {
        inputWindowHandle.setDispatchingTimeoutMillis(0); // It should never receive input.
        inputWindowHandle.setFocusable(false);
        // The input window handle without input channel must not have a token.
        inputWindowHandle.setToken(null);
        inputWindowHandle.setScaleFactor(1f);
        final int defaultType = WindowManager.LayoutParams.TYPE_APPLICATION;
        inputWindowHandle.setLayoutParamsType(defaultType);
        inputWindowHandle.setInputConfigMasked(
                InputConfigAdapter.getInputConfigFromWindowParams(
                        defaultType,
                        FLAG_NOT_TOUCHABLE,
                        INPUT_FEATURE_NO_INPUT_CHANNEL),
                InputConfigAdapter.getMask());
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
        inputWindowHandle.setTrustedOverlay(t, sc, true);
        populateOverlayInputInfo(inputWindowHandle);
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
                || type == TYPE_VOICE_INTERACTION
                || type == TYPE_STATUS_BAR_ADDITIONAL;
    }
}
