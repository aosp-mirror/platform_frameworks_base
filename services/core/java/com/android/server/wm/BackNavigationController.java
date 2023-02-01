/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.view.RemoteAnimationTarget.MODE_CLOSING;
import static android.view.RemoteAnimationTarget.MODE_OPENING;
import static android.view.WindowManager.LayoutParams.INVALID_WINDOW_TYPE;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_TO_BACK;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_BACK_PREVIEW;
import static com.android.server.wm.BackNavigationProto.ANIMATION_IN_PROGRESS;
import static com.android.server.wm.BackNavigationProto.LAST_BACK_TYPE;
import static com.android.server.wm.BackNavigationProto.SHOW_WALLPAPER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.ArraySet;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.IWindowFocusObserver;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.window.BackAnimationAdapter;
import android.window.BackNavigationInfo;
import android.window.IBackAnimationFinishedCallback;
import android.window.OnBackInvokedCallbackInfo;
import android.window.TaskSnapshot;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.server.LocalServices;

import java.util.ArrayList;

/**
 * Controller to handle actions related to the back gesture on the server side.
 */
class BackNavigationController {
    private static final String TAG = "BackNavigationController";
    private WindowManagerService mWindowManagerService;
    private IWindowFocusObserver mFocusObserver;
    private boolean mBackAnimationInProgress;
    private @BackNavigationInfo.BackTargetType int mLastBackType;
    private boolean mShowWallpaper;
    private Runnable mPendingAnimation;

    private final AnimationTargets mAnimationTargets = new AnimationTargets();
    private final ArrayList<WindowContainer> mTmpOpenApps = new ArrayList<>();
    private final ArrayList<WindowContainer> mTmpCloseApps = new ArrayList<>();

    /**
     * true if the back predictability feature is enabled
     */
    static final boolean sPredictBackEnable =
            SystemProperties.getBoolean("persist.wm.debug.predictive_back", true);

    static boolean isScreenshotEnabled() {
        return SystemProperties.getInt("persist.wm.debug.predictive_back_screenshot", 0) != 0;
    }

    /**
     * Set up the necessary leashes and build a {@link BackNavigationInfo} instance for an upcoming
     * back gesture animation.
     *
     * @return a {@link BackNavigationInfo} instance containing the required leashes and metadata
     * for the animation, or null if we don't know how to animate the current window and need to
     * fallback on dispatching the key event.
     */
    @VisibleForTesting
    @Nullable
    BackNavigationInfo startBackNavigation(
            IWindowFocusObserver observer, BackAnimationAdapter adapter) {
        if (!sPredictBackEnable) {
            return null;
        }
        final WindowManagerService wmService = mWindowManagerService;
        mFocusObserver = observer;

        int backType = BackNavigationInfo.TYPE_UNDEFINED;

        // The currently visible activity (if any).
        ActivityRecord currentActivity = null;

        // The currently visible task (if any).
        Task currentTask = null;

        // The previous task we're going back to. Can be the same as currentTask, if there are
        // multiple Activities in the Stack.
        Task prevTask = null;

        // The previous activity we're going back to. This can be either a child of currentTask
        // if there are more than one Activity in currentTask, or a child of prevTask, if
        // currentActivity is the last child of currentTask.
        ActivityRecord prevActivity;
        WindowContainer<?> removedWindowContainer = null;
        WindowState window;

        BackNavigationInfo.Builder infoBuilder = new BackNavigationInfo.Builder();
        synchronized (wmService.mGlobalLock) {
            WindowManagerInternal windowManagerInternal =
                    LocalServices.getService(WindowManagerInternal.class);
            IBinder focusedWindowToken = windowManagerInternal.getFocusedWindowToken();

            window = wmService.getFocusedWindowLocked();

            if (window == null) {
                EmbeddedWindowController.EmbeddedWindow embeddedWindow =
                        wmService.mEmbeddedWindowController.getByFocusToken(focusedWindowToken);
                if (embeddedWindow != null) {
                    ProtoLog.d(WM_DEBUG_BACK_PREVIEW,
                            "Current focused window is embeddedWindow. Dispatch KEYCODE_BACK.");
                    return null;
                }
            }

            // Lets first gather the states of things
            //  - What is our current window ?
            //  - Does it has an Activity and a Task ?
            // TODO Temp workaround for Sysui until b/221071505 is fixed
            if (window != null) {
                ProtoLog.d(WM_DEBUG_BACK_PREVIEW,
                        "Focused window found using getFocusedWindowToken");
            }

            if (window != null) {
                // This is needed to bridge the old and new back behavior with recents.  While in
                // Overview with live tile enabled, the previous app is technically focused but we
                // add an input consumer to capture all input that would otherwise go to the apps
                // being controlled by the animation. This means that the window resolved is not
                // the right window to consume back while in overview, so we need to route it to
                // launcher and use the legacy behavior of injecting KEYCODE_BACK since the existing
                // compat callback in VRI only works when the window is focused.
                // This symptom also happen while shell transition enabled, we can check that by
                // isTransientLaunch to know whether the focus window is point to live tile.
                final RecentsAnimationController recentsAnimationController =
                        wmService.getRecentsAnimationController();
                final ActivityRecord ar = window.mActivityRecord;
                if ((ar != null && ar.isActivityTypeHomeOrRecents()
                        && ar.mTransitionController.isTransientLaunch(ar))
                        || (recentsAnimationController != null
                        && recentsAnimationController.shouldApplyInputConsumer(ar))) {
                    ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "Current focused window being animated by "
                            + "recents. Overriding back callback to recents controller callback.");
                    return null;
                }

                if (!window.isDrawn()) {
                    ProtoLog.d(WM_DEBUG_BACK_PREVIEW,
                            "Focused window didn't have a valid surface drawn.");
                    return null;
                }
            }

            if (window == null) {
                // We don't have any focused window, fallback ont the top currentTask of the focused
                // display.
                ProtoLog.w(WM_DEBUG_BACK_PREVIEW,
                        "No focused window, defaulting to top current task's window");
                currentTask = wmService.mAtmService.getTopDisplayFocusedRootTask();
                window = currentTask.getWindow(WindowState::isFocused);
            }

            // Now let's find if this window has a callback from the client side.
            OnBackInvokedCallbackInfo callbackInfo = null;
            if (window != null) {
                currentActivity = window.mActivityRecord;
                currentTask = window.getTask();
                callbackInfo = window.getOnBackInvokedCallbackInfo();
                if (callbackInfo == null) {
                    Slog.e(TAG, "No callback registered, returning null.");
                    return null;
                }
                if (!callbackInfo.isSystemCallback()) {
                    backType = BackNavigationInfo.TYPE_CALLBACK;
                }
                infoBuilder.setOnBackInvokedCallback(callbackInfo.getCallback());
                if (mFocusObserver != null) {
                    window.registerFocusObserver(mFocusObserver);
                }
            }

            ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "startBackNavigation currentTask=%s, "
                            + "topRunningActivity=%s, callbackInfo=%s, currentFocus=%s",
                    currentTask, currentActivity, callbackInfo, window);

            if (window == null) {
                Slog.e(TAG, "Window is null, returning null.");
                return null;
            }

            // If we don't need to set up the animation, we return early. This is the case when
            // - We have an application callback.
            // - We don't have any ActivityRecord or Task to animate.
            // - The IME is opened, and we just need to close it.
            // - The home activity is the focused activity.
            // - The current activity will do shared element transition when exiting.
            if (backType == BackNavigationInfo.TYPE_CALLBACK
                    || currentActivity == null
                    || currentTask == null
                    || currentActivity.isActivityTypeHome()
                    || currentActivity.mHasSceneTransition) {
                infoBuilder.setType(BackNavigationInfo.TYPE_CALLBACK);
                final WindowState finalFocusedWindow = window;
                infoBuilder.setOnBackNavigationDone(new RemoteCallback(result ->
                        onBackNavigationDone(result, finalFocusedWindow,
                                BackNavigationInfo.TYPE_CALLBACK)));
                mLastBackType = BackNavigationInfo.TYPE_CALLBACK;
                return infoBuilder.build();
            }

            mBackAnimationInProgress = true;
            // We don't have an application callback, let's find the destination of the back gesture
            // The search logic should align with ActivityClientController#finishActivity
            prevActivity = currentTask.topRunningActivity(currentActivity.token, INVALID_TASK_ID);
            final boolean isOccluded = isKeyguardOccluded(window);
            // TODO Dialog window does not need to attach on activity, check
            // window.mAttrs.type != TYPE_BASE_APPLICATION
            if ((window.getParent().getChildCount() > 1
                    && window.getParent().getChildAt(0) != window)) {
                // Are we the top window of our parent? If not, we are a window on top of the
                // activity, we won't close the activity.
                backType = BackNavigationInfo.TYPE_DIALOG_CLOSE;
                removedWindowContainer = window;
            } else if (prevActivity != null) {
                if (!isOccluded || prevActivity.canShowWhenLocked()) {
                    // We have another Activity in the same currentTask to go to
                    backType = BackNavigationInfo.TYPE_CROSS_ACTIVITY;
                    removedWindowContainer = currentActivity;
                    prevTask = prevActivity.getTask();
                } else {
                    backType = BackNavigationInfo.TYPE_CALLBACK;
                }
            } else if (currentTask.returnsToHomeRootTask()) {
                if (isOccluded) {
                    backType = BackNavigationInfo.TYPE_CALLBACK;
                } else {
                    // Our Task should bring back to home
                    removedWindowContainer = currentTask;
                    prevTask = currentTask.getDisplayArea().getRootHomeTask();
                    backType = BackNavigationInfo.TYPE_RETURN_TO_HOME;
                    mShowWallpaper = true;
                }
            } else if (currentActivity.isRootOfTask()) {
                // TODO(208789724): Create single source of truth for this, maybe in
                //  RootWindowContainer
                prevTask = currentTask.mRootWindowContainer.getTask(Task::showToCurrentUser,
                        currentTask, false /*includeBoundary*/, true /*traverseTopToBottom*/);
                removedWindowContainer = currentTask;
                // If it reaches the top activity, we will check the below task from parent.
                // If it's null or multi-window, fallback the type to TYPE_CALLBACK.
                // or set the type to proper value when it's return to home or another task.
                if (prevTask == null || prevTask.inMultiWindowMode()) {
                    backType = BackNavigationInfo.TYPE_CALLBACK;
                } else {
                    prevActivity = prevTask.getTopNonFinishingActivity();
                    if (prevActivity == null || (isOccluded && !prevActivity.canShowWhenLocked())) {
                        backType = BackNavigationInfo.TYPE_CALLBACK;
                    } else if (prevTask.isActivityTypeHome()) {
                        backType = BackNavigationInfo.TYPE_RETURN_TO_HOME;
                        mShowWallpaper = true;
                    } else {
                        backType = BackNavigationInfo.TYPE_CROSS_TASK;
                    }
                }
            }
            infoBuilder.setType(backType);

            ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "Previous Destination is Activity:%s Task:%s "
                            + "removedContainer:%s, backType=%s",
                    prevActivity != null ? prevActivity.mActivityComponent : null,
                    prevTask != null ? prevTask.getName() : null,
                    removedWindowContainer,
                    BackNavigationInfo.typeToString(backType));

            // For now, we only animate when going home, cross task or cross-activity.
            boolean prepareAnimation =
                    (backType == BackNavigationInfo.TYPE_RETURN_TO_HOME
                            || backType == BackNavigationInfo.TYPE_CROSS_TASK
                            || backType == BackNavigationInfo.TYPE_CROSS_ACTIVITY)
                    && adapter != null;

            // Only prepare animation if no leash has been created (no animation is running).
            // TODO(b/241808055): Cancel animation when preparing back animation.
            if (prepareAnimation
                    && (removedWindowContainer.hasCommittedReparentToAnimationLeash()
                            || removedWindowContainer.mTransitionController.inTransition())) {
                Slog.w(TAG, "Can't prepare back animation due to another animation is running.");
                prepareAnimation = false;
            }

            if (prepareAnimation) {
                prepareAnimationIfNeeded(currentTask, prevTask, prevActivity,
                        removedWindowContainer, backType, adapter);
            }
            infoBuilder.setPrepareRemoteAnimation(prepareAnimation);
        } // Release wm Lock

        WindowContainer<?> finalRemovedWindowContainer = removedWindowContainer;
        if (finalRemovedWindowContainer != null) {
            final int finalBackType = backType;
            final WindowState finalFocusedWindow = window;
            RemoteCallback onBackNavigationDone = new RemoteCallback(result -> onBackNavigationDone(
                    result, finalFocusedWindow, finalBackType));
            infoBuilder.setOnBackNavigationDone(onBackNavigationDone);
        }
        mLastBackType = backType;
        return infoBuilder.build();
    }

    boolean isWaitBackTransition() {
        return mAnimationTargets.mComposed && mAnimationTargets.mWaitTransition;
    }

    boolean isKeyguardOccluded(WindowState focusWindow) {
        final KeyguardController kc = mWindowManagerService.mAtmService.mKeyguardController;
        final int displayId = focusWindow.getDisplayId();
        return kc.isKeyguardLocked(displayId) && kc.isDisplayOccluded(displayId);
    }

    // For legacy transition.
    /**
     *  Once we find the transition targets match back animation targets, remove the target from
     *  list, so that transition won't count them in since the close animation was finished.
     *
     *  @return {@code true} if the participants of this transition was animated by back gesture
     *  animations, and shouldn't join next transition.
     */
    boolean removeIfContainsBackAnimationTargets(ArraySet<ActivityRecord> openApps,
            ArraySet<ActivityRecord> closeApps) {
        if (!isWaitBackTransition()) {
            return false;
        }
        mTmpCloseApps.addAll(closeApps);
        boolean result = false;
        // Note: TmpOpenApps is empty. Unlike shell transition, the open apps will be removed from
        // mOpeningApps if there is no visibility change.
        if (mAnimationTargets.containsBackAnimationTargets(mTmpOpenApps, mTmpCloseApps)) {
            // remove close target from close list, open target from open list;
            // but the open target can be in close list.
            for (int i = openApps.size() - 1; i >= 0; --i) {
                final ActivityRecord ar = openApps.valueAt(i);
                if (mAnimationTargets.isTarget(ar, true /* open */)) {
                    openApps.removeAt(i);
                }
            }
            for (int i = closeApps.size() - 1; i >= 0; --i) {
                final ActivityRecord ar = closeApps.valueAt(i);
                if (mAnimationTargets.isTarget(ar, false /* open */)) {
                    closeApps.removeAt(i);
                }
            }
            result = true;
        }
        mTmpCloseApps.clear();
        return result;
    }

    // For shell transition
    /**
     *  Check whether the transition targets was animated by back gesture animation.
     *  Because the opening target could request to do other stuff at onResume, so it could become
     *  close target for a transition. So the condition here is
     *  The closing target should only exist in close list, but the opening target can be either in
     *  open or close list.
     *  @return {@code true} if the participants of this transition was animated by back gesture
     *  animations, and shouldn't join next transition.
     */
    boolean containsBackAnimationTargets(Transition transition) {
        if (!mAnimationTargets.mComposed
                || (transition.mType != TRANSIT_CLOSE && transition.mType != TRANSIT_TO_BACK)) {
            return false;
        }
        final ArraySet<WindowContainer> targets = transition.mParticipants;
        for (int i = targets.size() - 1; i >= 0; --i) {
            final WindowContainer wc = targets.valueAt(i);
            if (wc.asActivityRecord() == null && wc.asTask() == null) {
                continue;
            }
            // WC can be visible due to setLaunchBehind
            if (wc.isVisibleRequested()) {
                mTmpOpenApps.add(wc);
            } else {
                mTmpCloseApps.add(wc);
            }
        }
        final boolean result = mAnimationTargets.containsBackAnimationTargets(
                mTmpOpenApps, mTmpCloseApps);
        mTmpOpenApps.clear();
        mTmpCloseApps.clear();
        return result;
    }

    boolean isMonitorTransitionTarget(WindowContainer wc) {
        if (!mAnimationTargets.mComposed || !mAnimationTargets.mWaitTransition) {
            return false;
        }
        return mAnimationTargets.isTarget(wc, wc.isVisibleRequested() /* open */);
    }

    /**
     * Cleanup animation, this can either happen when transition ready or finish.
     * @param cleanupTransaction The transaction which the caller want to apply the internal
     *                           cleanup together.
     */
    void clearBackAnimations(SurfaceControl.Transaction cleanupTransaction) {
        mAnimationTargets.clearBackAnimateTarget(cleanupTransaction);
    }

    /**
     * TODO: Animation composer
     * prepareAnimationIfNeeded will become too complicated in order to support
     * ActivityRecord/WindowState, using a factory class to create the RemoteAnimationTargets for
     * different scenario.
     */
    private static class AnimationTargets {
        ActivityRecord mCloseTarget; // Must be activity
        WindowContainer mOpenTarget; // Can be activity or task if activity was removed
        private boolean mComposed;
        private boolean mWaitTransition;
        private int mSwitchType = UNKNOWN;
        private SurfaceControl.Transaction mFinishedTransaction;

        private static final int UNKNOWN = 0;
        private static final int TASK_SWITCH = 1;
        private static final int ACTIVITY_SWITCH = 2;

        void reset(@NonNull WindowContainer close, @NonNull WindowContainer open) {
            clearBackAnimateTarget(null);
            if (close == null || open == null) {
                Slog.e(TAG, "reset animation with null target close: "
                        + close + " open: " + open);
                return;
            }
            if (close.asActivityRecord() != null && open.asActivityRecord() != null
                    && (close.asActivityRecord().getTask() == open.asActivityRecord().getTask())) {
                mSwitchType = ACTIVITY_SWITCH;
                mCloseTarget = close.asActivityRecord();
            } else if (close.asTask() != null && open.asTask() != null
                    && close.asTask() != open.asTask()) {
                mSwitchType = TASK_SWITCH;
                mCloseTarget = close.asTask().getTopNonFinishingActivity();
            } else {
                mSwitchType = UNKNOWN;
                return;
            }

            mOpenTarget = open;
            mComposed = false;
            mWaitTransition = false;
        }

        void composeNewAnimations(@NonNull WindowContainer close, @NonNull WindowContainer open) {
            reset(close, open);
            if (mSwitchType == UNKNOWN || mComposed || mCloseTarget == mOpenTarget
                    || mCloseTarget == null || mOpenTarget == null) {
                return;
            }
            mComposed = true;
            mWaitTransition = false;
        }

        boolean containTarget(ArrayList<WindowContainer> wcs, boolean open) {
            for (int i = wcs.size() - 1; i >= 0; --i) {
                if (isTarget(wcs.get(i), open)) {
                    return true;
                }
            }
            return wcs.isEmpty();
        }

        boolean isTarget(WindowContainer wc, boolean open) {
            if (open) {
                return wc == mOpenTarget || mOpenTarget.hasChild(wc);
            }
            if (mSwitchType == TASK_SWITCH) {
                return  wc == mCloseTarget
                        || (wc.asTask() != null && wc.hasChild(mCloseTarget));
            } else if (mSwitchType == ACTIVITY_SWITCH) {
                return wc == mCloseTarget;
            }
            return false;
        }

        boolean setFinishTransaction(SurfaceControl.Transaction finishTransaction) {
            if (!mComposed) {
                return false;
            }
            mFinishedTransaction = finishTransaction;
            return true;
        }

        void finishPresentAnimations(SurfaceControl.Transaction t) {
            if (!mComposed) {
                return;
            }
            final SurfaceControl.Transaction pt = t != null ? t
                    : mOpenTarget.getPendingTransaction();
            if (mFinishedTransaction != null) {
                pt.merge(mFinishedTransaction);
                mFinishedTransaction = null;
            }
        }

        void clearBackAnimateTarget(SurfaceControl.Transaction cleanupTransaction) {
            finishPresentAnimations(cleanupTransaction);
            mCloseTarget = null;
            mOpenTarget = null;
            mComposed = false;
            mWaitTransition = false;
            mSwitchType = UNKNOWN;
            if (mFinishedTransaction != null) {
                Slog.w(TAG, "Clear back animation, found un-processed finished transaction");
                if (cleanupTransaction != null) {
                    cleanupTransaction.merge(mFinishedTransaction);
                } else {
                    mFinishedTransaction.apply();
                }
                mFinishedTransaction = null;
            }
        }

        // The close target must in close list
        // The open target can either in close or open list
        boolean containsBackAnimationTargets(ArrayList<WindowContainer> openApps,
                ArrayList<WindowContainer> closeApps) {
            return containTarget(closeApps, false /* open */)
                    && (containTarget(openApps, true /* open */)
                    || containTarget(openApps, false /* open */));
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder(128);
            sb.append("AnimationTargets{");
            sb.append(" mOpenTarget= ");
            sb.append(mOpenTarget);
            sb.append(" mCloseTarget= ");
            sb.append(mCloseTarget);
            sb.append(" mSwitchType= ");
            sb.append(mSwitchType);
            sb.append(" mComposed= ");
            sb.append(mComposed);
            sb.append(" mWaitTransition= ");
            sb.append(mWaitTransition);
            sb.append('}');
            return sb.toString();
        }
    }

    private void prepareAnimationIfNeeded(Task currentTask,
            Task prevTask, ActivityRecord prevActivity, WindowContainer<?> removedWindowContainer,
            int backType, BackAnimationAdapter adapter) {
        final ArrayList<SurfaceControl> leashes = new ArrayList<>();
        final SurfaceControl.Transaction startedTransaction = currentTask.getPendingTransaction();
        final SurfaceControl.Transaction finishedTransaction = new SurfaceControl.Transaction();
        // Prepare a leash to animate for the departing window
        final SurfaceControl animLeash = currentTask.makeAnimationLeash()
                .setName("BackPreview Leash for " + currentTask)
                .setHidden(false)
                .build();
        removedWindowContainer.reparentSurfaceControl(startedTransaction, animLeash);

        final RemoteAnimationTarget topAppTarget = createRemoteAnimationTargetLocked(
                currentTask, animLeash, MODE_CLOSING);

        // reset leash after animation finished.
        leashes.add(animLeash);
        removedWindowContainer.reparentSurfaceControl(finishedTransaction,
                removedWindowContainer.getParentSurfaceControl());

        // Prepare a leash to animate for the entering window.
        RemoteAnimationTarget behindAppTarget = null;
        if (needsScreenshot(backType)) {
            HardwareBuffer screenshotBuffer = null;
            Task backTargetTask = prevTask;
            switch(backType) {
                case BackNavigationInfo.TYPE_CROSS_TASK:
                    int prevTaskId = prevTask != null ? prevTask.mTaskId : 0;
                    int prevUserId = prevTask != null ? prevTask.mUserId : 0;
                    screenshotBuffer = getTaskSnapshot(prevTaskId, prevUserId);
                    break;
                case BackNavigationInfo.TYPE_CROSS_ACTIVITY:
                    if (prevActivity != null && prevActivity.mActivityComponent != null) {
                        screenshotBuffer = getActivitySnapshot(currentTask, prevActivity);
                    }
                    backTargetTask = currentTask;
                    break;
            }

            // Find a screenshot of the previous activity if we actually have an animation
            SurfaceControl animationLeashParent = removedWindowContainer.getAnimationLeashParent();
            if (screenshotBuffer != null) {
                final SurfaceControl screenshotSurface = new SurfaceControl.Builder()
                        .setName("BackPreview Screenshot for " + prevActivity)
                        .setHidden(false)
                        .setParent(animationLeashParent)
                        .setBLASTLayer()
                        .build();
                startedTransaction.setBuffer(screenshotSurface, screenshotBuffer);

                // The Animation leash needs to be above the screenshot surface, but the animation
                // leash needs to be added before to be in the synchronized block.
                startedTransaction.setLayer(topAppTarget.leash, 1);

                behindAppTarget =
                        createRemoteAnimationTargetLocked(
                                backTargetTask, screenshotSurface, MODE_OPENING);

                // reset leash after animation finished.
                leashes.add(screenshotSurface);
            }
        } else if (prevTask != null && prevActivity != null) {
            // Make previous task show from behind by marking its top activity as visible
            // and launch-behind to bump its visibility for the duration of the back gesture.
            setLaunchBehind(prevActivity);

            final SurfaceControl leash = prevActivity.makeAnimationLeash()
                    .setName("BackPreview Leash for " + prevActivity)
                    .setHidden(false)
                    .build();
            prevActivity.reparentSurfaceControl(startedTransaction, leash);
            behindAppTarget = createRemoteAnimationTargetLocked(
                    prevTask, leash, MODE_OPENING);

            // reset leash after animation finished.
            leashes.add(leash);
            prevActivity.reparentSurfaceControl(finishedTransaction,
                    prevActivity.getParentSurfaceControl());
        }

        if (mShowWallpaper) {
            currentTask.getDisplayContent().mWallpaperController.adjustWallpaperWindows();
            // TODO(b/241808055): If the current animation need to show wallpaper and animate the
            //  wallpaper, start the wallpaper animation to collect wallpaper target and deliver it
            //  to the back animation controller.
        }

        final RemoteAnimationTarget[] targets = (behindAppTarget == null)
                ? new RemoteAnimationTarget[] {topAppTarget}
                : new RemoteAnimationTarget[] {topAppTarget, behindAppTarget};

        final ActivityRecord finalPrevActivity = prevActivity;
        final IBackAnimationFinishedCallback callback =
                new IBackAnimationFinishedCallback.Stub() {
                    @Override
                    public void onAnimationFinished(boolean triggerBack) {
                        for (SurfaceControl sc: leashes) {
                            finishedTransaction.remove(sc);
                        }
                        synchronized (mWindowManagerService.mGlobalLock) {
                            if (triggerBack) {
                                final SurfaceControl surfaceControl =
                                        removedWindowContainer.getSurfaceControl();
                                if (surfaceControl != null && surfaceControl.isValid()) {
                                    // The animation is finish and start waiting for transition,
                                    // hide the task surface before it re-parented to avoid flicker.
                                    finishedTransaction.hide(surfaceControl);
                                }
                            } else if (!needsScreenshot(backType)) {
                                restoreLaunchBehind(finalPrevActivity);
                            }
                            if (!mAnimationTargets.setFinishTransaction(finishedTransaction)) {
                                finishedTransaction.apply();
                            }
                            if (!triggerBack) {
                                mAnimationTargets.clearBackAnimateTarget(null);
                            } else {
                                mAnimationTargets.mWaitTransition = true;
                            }
                        }
                        // TODO Add timeout monitor if transition didn't happen
                    }
                };
        if (backType == BackNavigationInfo.TYPE_CROSS_ACTIVITY) {
            mAnimationTargets.composeNewAnimations(removedWindowContainer, prevActivity);
        } else if (backType == BackNavigationInfo.TYPE_RETURN_TO_HOME
                || backType == BackNavigationInfo.TYPE_CROSS_TASK) {
            mAnimationTargets.composeNewAnimations(removedWindowContainer, prevTask);
        }
        scheduleAnimationLocked(backType, targets, adapter, callback);
    }

    @NonNull
    private static RemoteAnimationTarget createRemoteAnimationTargetLocked(
            Task task, SurfaceControl animLeash, int mode) {
        ActivityRecord topApp = task.getTopRealVisibleActivity();
        if (topApp == null) {
            topApp = task.getTopNonFinishingActivity();
        }

        final WindowState mainWindow = topApp != null
                ? topApp.findMainWindow()
                : null;
        int windowType = INVALID_WINDOW_TYPE;
        if (mainWindow != null) {
            windowType = mainWindow.getWindowType();
        }

        Rect bounds = new Rect(task.getBounds());
        Rect localBounds = new Rect(bounds);
        Point tmpPos = new Point();
        task.getRelativePosition(tmpPos);
        localBounds.offsetTo(tmpPos.x, tmpPos.y);

        return new RemoteAnimationTarget(
                task.mTaskId,
                mode,
                animLeash,
                false /* isTransluscent */,
                new Rect() /* clipRect */,
                new Rect() /* contentInsets */,
                task.getPrefixOrderIndex(),
                tmpPos /* position */,
                localBounds /* localBounds */,
                bounds /* screenSpaceBounds */,
                task.getWindowConfiguration(),
                true /* isNotInRecent */,
                null,
                null,
                task.getTaskInfo(),
                false,
                windowType);
    }

    @VisibleForTesting
    void scheduleAnimationLocked(@BackNavigationInfo.BackTargetType int type,
            RemoteAnimationTarget[] targets, BackAnimationAdapter backAnimationAdapter,
            IBackAnimationFinishedCallback callback) {
        mPendingAnimation = () -> {
            try {
                backAnimationAdapter.getRunner().onAnimationStart(type,
                        targets, null, null, callback);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        };
        mWindowManagerService.mWindowPlacerLocked.requestTraversal();
    }

    void checkAnimationReady(WallpaperController wallpaperController) {
        if (!mBackAnimationInProgress) {
            return;
        }

        final boolean wallpaperReady = !mShowWallpaper
                || (wallpaperController.getWallpaperTarget() != null
                && wallpaperController.wallpaperTransitionReady());
        if (wallpaperReady && mPendingAnimation != null) {
            startAnimation();
        }
    }

    void startAnimation() {
        if (mPendingAnimation != null) {
            mPendingAnimation.run();
            mPendingAnimation = null;
        }
    }

    private void onBackNavigationDone(Bundle result, WindowState focusedWindow, int backType) {
        boolean triggerBack = result != null && result.getBoolean(
                BackNavigationInfo.KEY_TRIGGER_BACK);
        ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "onBackNavigationDone backType=%s, "
                + "triggerBack=%b", backType, triggerBack);

        if (mFocusObserver != null) {
            focusedWindow.unregisterFocusObserver(mFocusObserver);
            mFocusObserver = null;
        }
        mBackAnimationInProgress = false;
        mShowWallpaper = false;
    }

    private HardwareBuffer getActivitySnapshot(@NonNull Task task, ActivityRecord r) {
        return task.getSnapshotForActivityRecord(r);
    }

    private HardwareBuffer getTaskSnapshot(int taskId, int userId) {
        if (mWindowManagerService.mTaskSnapshotController == null) {
            return null;
        }
        TaskSnapshot snapshot = mWindowManagerService.mTaskSnapshotController.getSnapshot(taskId,
                userId, true /* restoreFromDisk */, false  /* isLowResolution */);
        return snapshot != null ? snapshot.getHardwareBuffer() : null;
    }

    private boolean needsScreenshot(int backType) {
        if (!isScreenshotEnabled()) {
            return false;
        }
        switch (backType) {
            case BackNavigationInfo.TYPE_RETURN_TO_HOME:
            case BackNavigationInfo.TYPE_DIALOG_CLOSE:
                return false;
        }
        return true;
    }

    void setWindowManager(WindowManagerService wm) {
        mWindowManagerService = wm;
    }

    private void setLaunchBehind(ActivityRecord activity) {
        if (activity == null) {
            return;
        }
        if (!activity.isVisibleRequested()) {
            activity.setVisibility(true);
        }
        activity.mLaunchTaskBehind = true;

        // Handle fixed rotation launching app.
        final DisplayContent dc = activity.mDisplayContent;
        dc.rotateInDifferentOrientationIfNeeded(activity);
        if (activity.hasFixedRotationTransform()) {
            // Set the record so we can recognize it to continue to update display orientation
            // if the previous activity becomes the top later.
            dc.setFixedRotationLaunchingApp(activity,
                    activity.getWindowConfiguration().getRotation());
        }

        ProtoLog.d(WM_DEBUG_BACK_PREVIEW,
                "Setting Activity.mLauncherTaskBehind to true. Activity=%s", activity);
        activity.mTaskSupervisor.mStoppingActivities.remove(activity);
        activity.getDisplayContent().ensureActivitiesVisible(null /* starting */,
                0 /* configChanges */, false /* preserveWindows */, true);
    }

    private void restoreLaunchBehind(ActivityRecord activity) {
        if (activity == null) {
            return;
        }

        activity.mDisplayContent.continueUpdateOrientationForDiffOrienLaunchingApp();

        // Restore the launch-behind state.
        activity.mTaskSupervisor.scheduleLaunchTaskBehindComplete(activity.token);
        activity.mLaunchTaskBehind = false;
        ProtoLog.d(WM_DEBUG_BACK_PREVIEW,
                "Setting Activity.mLauncherTaskBehind to false. Activity=%s",
                activity);
    }

    boolean isWallpaperVisible(WindowState w) {
        return mAnimationTargets.mComposed && mShowWallpaper
                && w.mAttrs.type == TYPE_BASE_APPLICATION && w.mActivityRecord != null
                && mAnimationTargets.isTarget(w.mActivityRecord, true /* open */);
    }

    // Called from WindowManagerService to write to a protocol buffer output stream.
    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(ANIMATION_IN_PROGRESS, mBackAnimationInProgress);
        proto.write(LAST_BACK_TYPE, mLastBackType);
        proto.write(SHOW_WALLPAPER, mShowWallpaper);
        proto.end(token);
    }
}
