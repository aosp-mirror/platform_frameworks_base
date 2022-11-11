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
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_TO_BACK;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_BACK_PREVIEW;
import static com.android.server.wm.BackNavigationProto.ANIMATION_IN_PROGRESS;
import static com.android.server.wm.BackNavigationProto.LAST_BACK_TYPE;
import static com.android.server.wm.BackNavigationProto.SHOW_WALLPAPER;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_PREDICT_BACK;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Point;
import android.graphics.Rect;
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
import android.view.WindowInsets;
import android.window.BackAnimationAdapter;
import android.window.BackNavigationInfo;
import android.window.IBackAnimationFinishedCallback;
import android.window.OnBackInvokedCallbackInfo;
import android.window.TaskSnapshot;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.server.LocalServices;
import com.android.server.wm.utils.InsetUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.function.Consumer;

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

    private AnimationHandler mAnimationHandler;
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
            if (backType == BackNavigationInfo.TYPE_CALLBACK
                    || currentActivity == null
                    || currentTask == null
                    || currentActivity.isActivityTypeHome()) {
                infoBuilder.setType(BackNavigationInfo.TYPE_CALLBACK);
                final WindowState finalFocusedWindow = window;
                infoBuilder.setOnBackNavigationDone(new RemoteCallback(result ->
                        onBackNavigationDone(result, finalFocusedWindow,
                                BackNavigationInfo.TYPE_CALLBACK)));
                mLastBackType = backType;
                return infoBuilder.setType(backType).build();
            }

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
                mPendingAnimation = mAnimationHandler.scheduleAnimation(backType, adapter,
                        currentTask, prevTask, currentActivity, prevActivity);
                prepareAnimation = mPendingAnimation != null;
                mBackAnimationInProgress = prepareAnimation;
                if (prepareAnimation) {
                    mWindowManagerService.mWindowPlacerLocked.requestTraversal();
                    if (mShowWallpaper) {
                        currentTask.getDisplayContent().mWallpaperController
                                .adjustWallpaperWindows();
                    }
                }
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
        return mAnimationHandler.mComposed && mAnimationHandler.mWaitTransition;
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
        if (mAnimationHandler.containsBackAnimationTargets(mTmpOpenApps, mTmpCloseApps)) {
            // remove close target from close list, open target from open list;
            // but the open target can be in close list.
            for (int i = openApps.size() - 1; i >= 0; --i) {
                final ActivityRecord ar = openApps.valueAt(i);
                if (mAnimationHandler.isTarget(ar, true /* open */)) {
                    openApps.removeAt(i);
                    mAnimationHandler.mOpenTransitionTargetMatch = true;
                }
            }
            for (int i = closeApps.size() - 1; i >= 0; --i) {
                final ActivityRecord ar = closeApps.valueAt(i);
                if (mAnimationHandler.isTarget(ar, false /* open */)) {
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
        if (!mAnimationHandler.mComposed
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
        final boolean result = mAnimationHandler.containsBackAnimationTargets(
                mTmpOpenApps, mTmpCloseApps);
        if (result) {
            mAnimationHandler.mOpenTransitionTargetMatch =
                    mAnimationHandler.containTarget(mTmpOpenApps, true);
        }
        mTmpOpenApps.clear();
        mTmpCloseApps.clear();
        return result;
    }

    boolean isMonitorTransitionTarget(WindowContainer wc) {
        if (!mAnimationHandler.mComposed || !mAnimationHandler.mWaitTransition) {
            return false;
        }
        return mAnimationHandler.isTarget(wc, wc.isVisibleRequested() /* open */);
    }

    /**
     * Cleanup animation, this can either happen when transition ready or finish.
     * @param cleanupTransaction The transaction which the caller want to apply the internal
     *                           cleanup together.
     */
    void clearBackAnimations(SurfaceControl.Transaction cleanupTransaction) {
        mAnimationHandler.clearBackAnimateTarget(cleanupTransaction);
    }

    /**
     * Create and handling animations status for an open/close animation targets.
     */
    private static class AnimationHandler {
        private final WindowManagerService mWindowManagerService;
        private BackWindowAnimationAdaptor mCloseAdaptor;
        private BackWindowAnimationAdaptor mOpenAdaptor;
        private boolean mComposed;
        private boolean mWaitTransition;
        private int mSwitchType = UNKNOWN;
        private SurfaceControl.Transaction mFinishedTransaction;
        // This will be set before transition happen, to know whether the real opening target
        // exactly match animating target. When target match, reparent the starting surface to
        // the opening target like starting window do.
        private boolean mOpenTransitionTargetMatch;
        // The starting surface task Id. Used to clear the starting surface if the animation has
        // request one during animating.
        private int mRequestedStartingSurfaceTaskId;
        private SurfaceControl mStartingSurface;

        AnimationHandler(WindowManagerService wms) {
            mWindowManagerService = wms;
        }
        private static final int UNKNOWN = 0;
        private static final int TASK_SWITCH = 1;
        private static final int ACTIVITY_SWITCH = 2;

        private void initiate(WindowContainer close, WindowContainer open)  {
            WindowContainer closeTarget;
            if (close.asActivityRecord() != null && open.asActivityRecord() != null
                    && (close.asActivityRecord().getTask() == open.asActivityRecord().getTask())) {
                mSwitchType = ACTIVITY_SWITCH;
                closeTarget = close.asActivityRecord();
            } else if (close.asTask() != null && open.asTask() != null
                    && close.asTask() != open.asTask()) {
                mSwitchType = TASK_SWITCH;
                closeTarget = close.asTask().getTopNonFinishingActivity();
            } else {
                mSwitchType = UNKNOWN;
                return;
            }

            mCloseAdaptor = createAdaptor(closeTarget, false /* isOpen */);
            mOpenAdaptor = createAdaptor(open, true /* isOpen */);

            if (mCloseAdaptor.mAnimationTarget == null || mOpenAdaptor.mAnimationTarget == null) {
                Slog.w(TAG, "composeNewAnimations fail, skip");
                clearBackAnimateTarget(null /* cleanupTransaction */);
            }
        }

        boolean composeAnimations(@NonNull WindowContainer close, @NonNull WindowContainer open) {
            clearBackAnimateTarget(null /* cleanupTransaction */);
            if (close == null || open == null) {
                Slog.e(TAG, "reset animation with null target close: "
                        + close + " open: " + open);
                return false;
            }
            initiate(close, open);
            if (mSwitchType == UNKNOWN) {
                return false;
            }
            mComposed = true;
            mWaitTransition = false;
            return true;
        }

        RemoteAnimationTarget[] getAnimationTargets() {
            return mComposed ? new RemoteAnimationTarget[] {
                    mCloseAdaptor.mAnimationTarget, mOpenAdaptor.mAnimationTarget} : null;
        }

        boolean isSupportWindowlessSurface() {
            return mWindowManagerService.mAtmService.mTaskOrganizerController
                    .isSupportWindowlessStartingSurface();
        }

        void createStartingSurface(TaskSnapshot snapshot) {
            if (!mComposed) {
                return;
            }

            final ActivityRecord topActivity = getTopOpenActivity();
            if (topActivity == null) {
                Slog.e(TAG, "createStartingSurface fail, no open activity: " + this);
                return;
            }
            // TODO (b/257857570) draw snapshot by starting surface.
        }

        private ActivityRecord getTopOpenActivity() {
            if (mSwitchType == ACTIVITY_SWITCH) {
                return mOpenAdaptor.mTarget.asActivityRecord();
            } else if (mSwitchType == TASK_SWITCH) {
                return mOpenAdaptor.mTarget.asTask().getTopNonFinishingActivity();
            }
            return null;
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
                return wc == mOpenAdaptor.mTarget || mOpenAdaptor.mTarget.hasChild(wc);
            }
            if (mSwitchType == TASK_SWITCH) {
                return  wc == mCloseAdaptor.mTarget
                        || (wc.asTask() != null && wc.hasChild(mCloseAdaptor.mTarget));
            } else if (mSwitchType == ACTIVITY_SWITCH) {
                return wc == mCloseAdaptor.mTarget;
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
                    : mOpenAdaptor.mTarget.getPendingTransaction();
            if (mFinishedTransaction != null) {
                pt.merge(mFinishedTransaction);
                mFinishedTransaction = null;
            }
            cleanUpWindowlessSurface();

            if (mCloseAdaptor != null) {
                mCloseAdaptor.mTarget.cancelAnimation();
                mCloseAdaptor = null;
            }
            if (mOpenAdaptor != null) {
                mOpenAdaptor.mTarget.cancelAnimation();
                mOpenAdaptor = null;
            }
        }

        private void cleanUpWindowlessSurface() {
            final ActivityRecord ar = getTopOpenActivity();
            if (ar == null) {
                Slog.w(TAG, "finishPresentAnimations without top activity: " + this);
            }
            final SurfaceControl.Transaction pendingT = ar != null ? ar.getPendingTransaction()
                    : mOpenAdaptor.mTarget.getPendingTransaction();
            // ensure open target is visible before cancel animation.
            mOpenTransitionTargetMatch &= ar != null;
            if (mOpenTransitionTargetMatch) {
                pendingT.show(ar.getSurfaceControl());
            }
            if (mRequestedStartingSurfaceTaskId != 0) {
                // If open target match, reparent to open activity
                if (mStartingSurface != null && mOpenTransitionTargetMatch) {
                    pendingT.reparent(mStartingSurface, ar.getSurfaceControl());
                }
                // remove starting surface.
                mStartingSurface = null;
                // TODO (b/257857570) draw snapshot by starting surface.
                mRequestedStartingSurfaceTaskId = 0;
            }
        }

        void clearBackAnimateTarget(SurfaceControl.Transaction cleanupTransaction) {
            finishPresentAnimations(cleanupTransaction);
            mComposed = false;
            mWaitTransition = false;
            mOpenTransitionTargetMatch = false;
            mRequestedStartingSurfaceTaskId = 0;
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
            return "AnimationTargets{"
                    + " openTarget= "
                    + mOpenAdaptor.mTarget
                    + " closeTarget= "
                    + mCloseAdaptor.mTarget
                    + " mSwitchType= "
                    + mSwitchType
                    + " mComposed= "
                    + mComposed
                    + " mWaitTransition= "
                    + mWaitTransition
                    + '}';
        }

        private static BackWindowAnimationAdaptor createAdaptor(
                WindowContainer target, boolean isOpen) {
            final BackWindowAnimationAdaptor adaptor =
                    new BackWindowAnimationAdaptor(target, isOpen);
            target.startAnimation(target.getPendingTransaction(), adaptor, false /* hidden */,
                    ANIMATION_TYPE_PREDICT_BACK);
            return adaptor;
        }

        private static class BackWindowAnimationAdaptor implements AnimationAdapter {
            SurfaceControl mCapturedLeash;
            private final Rect mBounds = new Rect();
            private final WindowContainer mTarget;
            private final boolean mIsOpen;
            private RemoteAnimationTarget mAnimationTarget;

            BackWindowAnimationAdaptor(WindowContainer closeTarget, boolean isOpen) {
                mBounds.set(closeTarget.getBounds());
                mTarget = closeTarget;
                mIsOpen = isOpen;
            }
            @Override
            public boolean getShowWallpaper() {
                return false;
            }

            @Override
            public void startAnimation(SurfaceControl animationLeash, SurfaceControl.Transaction t,
                    int type, SurfaceAnimator.OnAnimationFinishedCallback finishCallback) {
                mCapturedLeash = animationLeash;
                createRemoteAnimationTarget(mIsOpen);
            }

            @Override
            public void onAnimationCancelled(SurfaceControl animationLeash) {
                if (mCapturedLeash == animationLeash) {
                    mCapturedLeash = null;
                }
            }

            @Override
            public long getDurationHint() {
                return 0;
            }

            @Override
            public long getStatusBarTransitionsStartTime() {
                return 0;
            }

            @Override
            public void dump(PrintWriter pw, String prefix) {
                pw.print(prefix + "BackWindowAnimationAdaptor mCapturedLeash=");
                pw.print(mCapturedLeash);
                pw.println();
            }

            @Override
            public void dumpDebug(ProtoOutputStream proto) {

            }

            RemoteAnimationTarget createRemoteAnimationTarget(boolean isOpen) {
                if (mAnimationTarget != null) {
                    return mAnimationTarget;
                }
                Task t = mTarget.asTask();
                final ActivityRecord r = t != null ? t.getTopNonFinishingActivity()
                        : mTarget.asActivityRecord();
                if (t == null && r != null) {
                    t = r.getTask();
                }
                if (t == null || r == null) {
                    Slog.e(TAG, "createRemoteAnimationTarget fail " + mTarget);
                    return null;
                }
                final WindowState mainWindow = r.findMainWindow();
                Rect insets;
                if (mainWindow != null) {
                    insets = mainWindow.getInsetsStateWithVisibilityOverride().calculateInsets(
                            mBounds, WindowInsets.Type.systemBars(),
                            false /* ignoreVisibility */).toRect();
                    InsetUtils.addInsets(insets, mainWindow.mActivityRecord.getLetterboxInsets());
                } else {
                    insets = new Rect();
                }
                final int mode = isOpen ? MODE_OPENING : MODE_CLOSING;
                mAnimationTarget = new RemoteAnimationTarget(t.mTaskId, mode, mCapturedLeash,
                        !r.fillsParent(), new Rect(),
                        insets, r.getPrefixOrderIndex(), new Point(mBounds.left, mBounds.top),
                        mBounds, mBounds, t.getWindowConfiguration(),
                        true /* isNotInRecents */, null, null, t.getTaskInfo(),
                        r.checkEnterPictureInPictureAppOpsState());
                return mAnimationTarget;
            }
        }

        Runnable scheduleAnimation(int backType, BackAnimationAdapter adapter,
                Task currentTask, Task previousTask, ActivityRecord currentActivity,
                ActivityRecord previousActivity) {
            switch (backType) {
                case BackNavigationInfo.TYPE_RETURN_TO_HOME:
                    return new ScheduleAnimationBuilder(backType, adapter)
                            .setIsLaunchBehind(true)
                            .setComposeTarget(currentTask, previousTask)
                            .build();
                case BackNavigationInfo.TYPE_CROSS_ACTIVITY:
                    return new ScheduleAnimationBuilder(backType, adapter)
                            .setComposeTarget(currentActivity, previousActivity)
                            .setOpeningSnapshot(getActivitySnapshot(previousActivity)).build();
                case BackNavigationInfo.TYPE_CROSS_TASK:
                    return new ScheduleAnimationBuilder(backType, adapter)
                            .setComposeTarget(currentTask, previousTask)
                            .setOpeningSnapshot(getTaskSnapshot(previousTask)).build();
            }
            return null;
        }

        private class ScheduleAnimationBuilder {
            final int mType;
            final BackAnimationAdapter mBackAnimationAdapter;
            WindowContainer mCloseTarget;
            WindowContainer mOpenTarget;
            TaskSnapshot mOpenSnapshot;
            boolean mIsLaunchBehind;

            ScheduleAnimationBuilder(int type, BackAnimationAdapter backAnimationAdapter) {
                mType = type;
                mBackAnimationAdapter = backAnimationAdapter;
            }

            ScheduleAnimationBuilder setComposeTarget(WindowContainer close, WindowContainer open) {
                mCloseTarget = close;
                mOpenTarget = open;
                return this;
            }

            ScheduleAnimationBuilder setOpeningSnapshot(TaskSnapshot snapshot) {
                mOpenSnapshot = snapshot;
                return this;
            }

            ScheduleAnimationBuilder setIsLaunchBehind(boolean launchBehind) {
                mIsLaunchBehind = launchBehind;
                return this;
            }

            Runnable build() {
                if (mOpenTarget == null || mCloseTarget == null) {
                    return null;
                }
                final boolean shouldLaunchBehind = mIsLaunchBehind || !isSupportWindowlessSurface();
                final ActivityRecord launchBehindActivity = !shouldLaunchBehind ? null
                        : mOpenTarget.asTask() != null
                                ? mOpenTarget.asTask().getTopNonFinishingActivity()
                                : mOpenTarget.asActivityRecord() != null
                                        ? mOpenTarget.asActivityRecord() : null;
                if (shouldLaunchBehind && launchBehindActivity == null) {
                    Slog.e(TAG, "No opening activity");
                    return null;
                }

                if (!composeAnimations(mCloseTarget, mOpenTarget)) {
                    return null;
                }
                if (launchBehindActivity != null) {
                    setLaunchBehind(launchBehindActivity);
                } else {
                    createStartingSurface(mOpenSnapshot);
                }

                final IBackAnimationFinishedCallback callback = makeAnimationFinishedCallback(
                        launchBehindActivity != null ? triggerBack -> {
                            if (!triggerBack) {
                                restoreLaunchBehind(launchBehindActivity);
                            }
                        } : null,
                        mCloseTarget);
                final RemoteAnimationTarget[] targets = getAnimationTargets();

                return () -> {
                    try {
                        mBackAnimationAdapter.getRunner().onAnimationStart(mType,
                                targets, null, null, callback);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                };
            }

            private IBackAnimationFinishedCallback makeAnimationFinishedCallback(
                    Consumer<Boolean> b, WindowContainer closeTarget) {
                return new IBackAnimationFinishedCallback.Stub() {
                    @Override
                    public void onAnimationFinished(boolean triggerBack) {
                        final SurfaceControl.Transaction finishedTransaction =
                                new SurfaceControl.Transaction();
                        synchronized (mWindowManagerService.mGlobalLock) {
                            if (b != null) {
                                b.accept(triggerBack);
                            }
                            if (triggerBack) {
                                final SurfaceControl surfaceControl =
                                        closeTarget.getSurfaceControl();
                                if (surfaceControl != null && surfaceControl.isValid()) {
                                    // Hide the close target surface when transition start.
                                    finishedTransaction.hide(surfaceControl);
                                }
                            }
                            if (!setFinishTransaction(finishedTransaction)) {
                                finishedTransaction.apply();
                            }
                            if (!triggerBack) {
                                clearBackAnimateTarget(
                                        null /* cleanupTransaction */);
                            } else {
                                mWaitTransition = true;
                            }
                        }
                        // TODO Add timeout monitor if transition didn't happen
                    }
                };
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
                    // Set the record so we can recognize it to continue to update display
                    // orientation if the previous activity becomes the top later.
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
        }
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

    private static TaskSnapshot getActivitySnapshot(@NonNull ActivityRecord r) {
        if (!isScreenshotEnabled()) {
            return null;
        }
        // Check if we have a screenshot of the previous activity, indexed by its
        // component name.
        // TODO return TaskSnapshot when feature complete.
//        final HardwareBuffer hw = r.getTask().getSnapshotForActivityRecord(r);
        return null;
    }

    private static TaskSnapshot getTaskSnapshot(Task task) {
        if (!isScreenshotEnabled()) {
            return null;
        }
        // Don't read from disk!!
        return  task.mRootWindowContainer.mWindowManager.mTaskSnapshotController.getSnapshot(
                task.mTaskId, task.mUserId, false /* restoreFromDisk */,
                false /* isLowResolution */);
    }

    void setWindowManager(WindowManagerService wm) {
        mWindowManagerService = wm;
        mAnimationHandler = new AnimationHandler(wm);
    }

    boolean isWallpaperVisible(WindowState w) {
        return mAnimationHandler.mComposed && mShowWallpaper
                && w.mAttrs.type == TYPE_BASE_APPLICATION && w.mActivityRecord != null
                && mAnimationHandler.isTarget(w.mActivityRecord, true /* open */);
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
