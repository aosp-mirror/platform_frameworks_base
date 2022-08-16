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

import static android.view.RemoteAnimationTarget.MODE_CLOSING;
import static android.view.RemoteAnimationTarget.MODE_OPENING;
import static android.view.WindowManager.LayoutParams.INVALID_WINDOW_TYPE;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_BACK_PREVIEW;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Slog;
import android.view.IWindowFocusObserver;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.window.BackAnimationAdapter;
import android.window.BackNavigationInfo;
import android.window.IBackAnimationFinishedCallback;
import android.window.OnBackInvokedCallbackInfo;
import android.window.ScreenCapture;
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

    /**
     * Returns true if the back predictability feature is enabled
     */
    static boolean isEnabled() {
        return SystemProperties.getInt("persist.wm.debug.predictive_back", 1) != 0;
    }

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

                return infoBuilder.setType(backType).build();
            }

            // We don't have an application callback, let's find the destination of the back gesture
            Task finalTask = currentTask;
            prevActivity = currentTask.getActivity(
                    (r) -> !r.finishing && r.getTask() == finalTask && !r.isTopRunningActivity());
            // TODO Dialog window does not need to attach on activity, check
            // window.mAttrs.type != TYPE_BASE_APPLICATION
            if ((window.getParent().getChildCount() > 1
                    && window.getParent().getChildAt(0) != window)) {
                // Are we the top window of our parent? If not, we are a window on top of the
                // activity, we won't close the activity.
                backType = BackNavigationInfo.TYPE_DIALOG_CLOSE;
                removedWindowContainer = window;
            } else if (prevActivity != null) {
                // We have another Activity in the same currentTask to go to
                backType = BackNavigationInfo.TYPE_CROSS_ACTIVITY;
                removedWindowContainer = currentActivity;
            } else if (currentTask.returnsToHomeRootTask()) {
                // Our Task should bring back to home
                removedWindowContainer = currentTask;
                backType = BackNavigationInfo.TYPE_RETURN_TO_HOME;
            } else if (currentActivity.isRootOfTask()) {
                // TODO(208789724): Create single source of truth for this, maybe in
                //  RootWindowContainer
                // TODO: Also check Task.shouldUpRecreateTaskLocked() for prevActivity logic
                prevTask = currentTask.mRootWindowContainer.getTaskBelow(currentTask);
                removedWindowContainer = currentTask;
                prevActivity = prevTask.getTopNonFinishingActivity();
                if (prevTask.isActivityTypeHome()) {
                    backType = BackNavigationInfo.TYPE_RETURN_TO_HOME;
                } else {
                    backType = BackNavigationInfo.TYPE_CROSS_TASK;
                }
            }
            infoBuilder.setType(backType);

            ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "Previous Destination is Activity:%s Task:%s "
                            + "removedContainer:%s, backType=%s",
                    prevActivity != null ? prevActivity.mActivityComponent : null,
                    prevTask != null ? prevTask.getName() : null,
                    removedWindowContainer,
                    BackNavigationInfo.typeToString(backType));

            // For now, we only animate when going home.
            boolean prepareAnimation = backType == BackNavigationInfo.TYPE_RETURN_TO_HOME
                    && adapter != null;

            // Only prepare animation if no leash has been created (no animation is running).
            // TODO(b/241808055): Cancel animation when preparing back animation.
            if (prepareAnimation
                    && removedWindowContainer.hasCommittedReparentToAnimationLeash()) {
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

        return infoBuilder.build();
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
            switch(backType) {
                case BackNavigationInfo.TYPE_CROSS_TASK:
                    int prevTaskId = prevTask != null ? prevTask.mTaskId : 0;
                    int prevUserId = prevTask != null ? prevTask.mUserId : 0;
                    screenshotBuffer = getTaskSnapshot(prevTaskId, prevUserId);
                    break;
                case BackNavigationInfo.TYPE_CROSS_ACTIVITY:
                    //TODO(207481538) Remove once the infrastructure to support per-activity
                    // screenshot is implemented. For now we simply have the mBackScreenshots hash
                    // map that dumbly saves the screenshots.
                    if (prevActivity != null
                            && prevActivity.mActivityComponent != null) {
                        screenshotBuffer =
                                getActivitySnapshot(currentTask, prevActivity.mActivityComponent);
                    }
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

                behindAppTarget = createRemoteAnimationTargetLocked(
                        prevTask, screenshotSurface, MODE_OPENING);

                // reset leash after animation finished.
                leashes.add(screenshotSurface);
            }
        } else if (prevTask != null) {
            // Special handling for preventing next transition.
            currentTask.mBackGestureStarted = true;
            prevActivity = prevTask.getTopNonFinishingActivity();
            if (prevActivity != null) {
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
                                    // When going back to home, hide the task surface before it is
                                    // re-parented to avoid flicker.
                                    finishedTransaction.hide(surfaceControl);
                                }
                            } else if (!needsScreenshot(backType)) {
                                restoreLaunchBehind(finalPrevActivity);
                            }
                        }
                        finishedTransaction.apply();
                    }
                };

        startAnimation(backType, targets, adapter, callback);
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
    void startAnimation(@BackNavigationInfo.BackTargetType int type,
            RemoteAnimationTarget[] targets, BackAnimationAdapter backAnimationAdapter,
            IBackAnimationFinishedCallback callback) {
        mWindowManagerService.mAnimator.addAfterPrepareSurfacesRunnable(() -> {
            try {
                backAnimationAdapter.getRunner().onAnimationStart(type,
                        targets, null, null, callback);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        });
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
    }

    private HardwareBuffer getActivitySnapshot(@NonNull Task task,
            ComponentName activityComponent) {
        // Check if we have a screenshot of the previous activity, indexed by its
        // component name.
        ScreenCapture.ScreenshotHardwareBuffer backBuffer = task.mBackScreenshots
                .get(activityComponent.flattenToString());
        return backBuffer != null ? backBuffer.getHardwareBuffer() : null;

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
        if (!activity.mVisibleRequested) {
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
