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

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_BACK_PREVIEW;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.WindowConfiguration;
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
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.window.BackNavigationInfo;
import android.window.OnBackInvokedCallbackInfo;
import android.window.TaskSnapshot;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.server.LocalServices;

/**
 * Controller to handle actions related to the back gesture on the server side.
 */
class BackNavigationController {

    private static final String TAG = "BackNavigationController";
    @Nullable
    private TaskSnapshotController mTaskSnapshotController;

    /**
     * Returns true if the back predictability feature is enabled
     */
    static boolean isEnabled() {
        return SystemProperties.getInt("persist.wm.debug.predictive_back", 1) != 0;
    }

    static boolean isScreenshotEnabled() {
        return SystemProperties.getInt("persist.wm.debug.predictive_back_screenshot", 0) != 0;
    }

    private static boolean isAnimationEnabled() {
        return SystemProperties.getInt("persist.wm.debug.predictive_back_anim", 0) != 0;
    }

    /**
     * Set up the necessary leashes and build a {@link BackNavigationInfo} instance for an upcoming
     * back gesture animation.
     *
     * @return a {@link BackNavigationInfo} instance containing the required leashes and metadata
     * for the animation, or null if we don't know how to animate the current window and need to
     * fallback on dispatching the key event.
     */
    @Nullable
    BackNavigationInfo startBackNavigation(@NonNull WindowManagerService wmService) {
        return startBackNavigation(wmService, null);
    }

    /**
     * @param tx, a transaction to be used for the attaching the animation leash.
     *            This is used in tests. If null, the object will be initialized with a new {@link
     *            SurfaceControl.Transaction}
     * @see #startBackNavigation(WindowManagerService)
     */
    @VisibleForTesting
    @Nullable
    BackNavigationInfo startBackNavigation(WindowManagerService wmService,
            @Nullable SurfaceControl.Transaction tx) {

        if (tx == null) {
            tx = new SurfaceControl.Transaction();
        }

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
        SurfaceControl animationLeashParent = null;
        HardwareBuffer screenshotBuffer = null;
        RemoteAnimationTarget topAppTarget = null;
        int prevTaskId;
        int prevUserId;

        BackNavigationInfo.Builder infoBuilder = new BackNavigationInfo.Builder();
        synchronized (wmService.mGlobalLock) {
            WindowState window;
            WindowConfiguration taskWindowConfiguration;
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

            OnBackInvokedCallbackInfo overrideCallbackInfo = null;
            if (window != null) {
                // This is needed to bridge the old and new back behavior with recents.  While in
                // Overview with live tile enabled, the previous app is technically focused but we
                // add an input consumer to capture all input that would otherwise go to the apps
                // being controlled by the animation. This means that the window resolved is not
                // the right window to consume back while in overview, so we need to route it to
                // launcher and use the legacy behavior of injecting KEYCODE_BACK since the existing
                // compat callback in VRI only works when the window is focused.
                final RecentsAnimationController recentsAnimationController =
                        wmService.getRecentsAnimationController();
                if (recentsAnimationController != null
                        && recentsAnimationController.shouldApplyInputConsumer(
                        window.getActivityRecord())) {
                    window = recentsAnimationController.getTargetAppMainWindow();
                    overrideCallbackInfo = recentsAnimationController.getBackInvokedInfo();
                    ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "Current focused window being animated by "
                            + "recents. Overriding back callback to recents controller callback.");
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
                callbackInfo = overrideCallbackInfo != null
                        ? overrideCallbackInfo
                        : window.getOnBackInvokedCallbackInfo();
                if (callbackInfo == null) {
                    Slog.e(TAG, "No callback registered, returning null.");
                    return null;
                }
                if (!callbackInfo.isSystemCallback()) {
                    backType = BackNavigationInfo.TYPE_CALLBACK;
                }
                infoBuilder.setOnBackInvokedCallback(callbackInfo.getCallback());
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
                return infoBuilder
                        .setType(backType)
                        .build();
            }

            // We don't have an application callback, let's find the destination of the back gesture
            Task finalTask = currentTask;
            prevActivity = currentTask.getActivity(
                    (r) -> !r.finishing && r.getTask() == finalTask && !r.isTopRunningActivity());
            if (window.getParent().getChildCount() > 1 && window.getParent().getChildAt(0)
                    != window) {
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

            prevTaskId = prevTask != null ? prevTask.mTaskId : 0;
            prevUserId = prevTask != null ? prevTask.mUserId : 0;

            ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "Previous Destination is Activity:%s Task:%s "
                            + "removedContainer:%s, backType=%s",
                    prevActivity != null ? prevActivity.mActivityComponent : null,
                    prevTask != null ? prevTask.getName() : null,
                    removedWindowContainer,
                    BackNavigationInfo.typeToString(backType));

            // For now, we only animate when going home.
            boolean prepareAnimation = backType == BackNavigationInfo.TYPE_RETURN_TO_HOME
                    // Only create a new leash if no leash has been created.
                    // Otherwise return null for animation target to avoid conflict.
                    && !removedWindowContainer.hasCommittedReparentToAnimationLeash();

            if (prepareAnimation) {
                taskWindowConfiguration =
                        currentTask.getTaskInfo().configuration.windowConfiguration;

                infoBuilder.setTaskWindowConfiguration(taskWindowConfiguration);
                // Prepare a leash to animate the current top window
                // TODO(b/220934562): Use surface animator to better manage animation conflicts.
                SurfaceControl animLeash = removedWindowContainer.makeAnimationLeash()
                        .setName("BackPreview Leash for " + removedWindowContainer)
                        .setHidden(false)
                        .setBLASTLayer()
                        .build();
                removedWindowContainer.reparentSurfaceControl(tx, animLeash);
                animationLeashParent = removedWindowContainer.getAnimationLeashParent();
                topAppTarget = createRemoteAnimationTargetLocked(removedWindowContainer,
                        currentActivity,
                        currentTask, animLeash);
                infoBuilder.setDepartingAnimationTarget(topAppTarget);
            }

            //TODO(207481538) Remove once the infrastructure to support per-activity screenshot is
            // implemented. For now we simply have the mBackScreenshots hash map that dumbly
            // saves the screenshots.
            if (needsScreenshot(backType) && prevActivity != null
                    && prevActivity.mActivityComponent != null) {
                screenshotBuffer =
                        getActivitySnapshot(currentTask, prevActivity.mActivityComponent);
            }

            // Special handling for back to home animation
            if (backType == BackNavigationInfo.TYPE_RETURN_TO_HOME && isAnimationEnabled()
                    && prevTask != null) {
                currentTask.mBackGestureStarted = true;
                // Make launcher show from behind by marking its top activity as visible and
                // launch-behind to bump its visibility for the duration of the back gesture.
                prevActivity = prevTask.getTopNonFinishingActivity();
                if (prevActivity != null) {
                    if (!prevActivity.mVisibleRequested) {
                        prevActivity.setVisibility(true);
                    }
                    prevActivity.mLaunchTaskBehind = true;
                    ProtoLog.d(WM_DEBUG_BACK_PREVIEW,
                            "Setting Activity.mLauncherTaskBehind to true. Activity=%s",
                            prevActivity);
                    prevActivity.mRootWindowContainer.ensureActivitiesVisible(
                            null /* starting */, 0 /* configChanges */,
                            false /* preserveWindows */);
                }
            }
        } // Release wm Lock

        // Find a screenshot of the previous activity if we actually have an animation
        if (topAppTarget != null && needsScreenshot(backType) && prevTask != null
                && screenshotBuffer == null) {
            SurfaceControl.Builder builder = new SurfaceControl.Builder()
                    .setName("BackPreview Screenshot for " + prevActivity)
                    .setParent(animationLeashParent)
                    .setHidden(false)
                    .setBLASTLayer();
            infoBuilder.setScreenshotSurface(builder.build());
            screenshotBuffer = getTaskSnapshot(prevTaskId, prevUserId);
            infoBuilder.setScreenshotBuffer(screenshotBuffer);


            // The Animation leash needs to be above the screenshot surface, but the animation leash
            // needs to be added before to be in the synchronized block.
            tx.setLayer(topAppTarget.leash, 1);
        }

        WindowContainer<?> finalRemovedWindowContainer = removedWindowContainer;
        if (finalRemovedWindowContainer != null) {
            try {
                currentActivity.token.linkToDeath(
                        () -> resetSurfaces(finalRemovedWindowContainer), 0);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to link to death", e);
                resetSurfaces(removedWindowContainer);
                return null;
            }

            int finalBackType = backType;
            ActivityRecord finalprevActivity = prevActivity;
            Task finalTask = currentTask;
            RemoteCallback onBackNavigationDone = new RemoteCallback(result -> onBackNavigationDone(
                    result, finalRemovedWindowContainer, finalBackType, finalTask,
                    finalprevActivity));
            infoBuilder.setOnBackNavigationDone(onBackNavigationDone);
        }

        tx.apply();
        return infoBuilder.build();
    }

    @NonNull
    private static RemoteAnimationTarget createRemoteAnimationTargetLocked(
            WindowContainer<?> removedWindowContainer,
            ActivityRecord activityRecord, Task task, SurfaceControl animLeash) {
        return new RemoteAnimationTarget(
                task.mTaskId,
                RemoteAnimationTarget.MODE_CLOSING,
                animLeash,
                false /* isTransluscent */,
                new Rect() /* clipRect */,
                new Rect() /* contentInsets */,
                activityRecord.getPrefixOrderIndex(),
                new Point(0, 0) /* position */,
                new Rect() /* localBounds */,
                new Rect() /* screenSpaceBounds */,
                removedWindowContainer.getWindowConfiguration(),
                true /* isNotInRecent */,
                null,
                null,
                task.getTaskInfo(),
                false,
                activityRecord.windowType);
    }

    private void onBackNavigationDone(
            Bundle result, WindowContainer<?> windowContainer, int backType,
            Task task, ActivityRecord prevActivity) {
        SurfaceControl surfaceControl = windowContainer.getSurfaceControl();
        boolean triggerBack = result != null && result.getBoolean(
                BackNavigationInfo.KEY_TRIGGER_BACK);
        ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "onBackNavigationDone backType=%s, "
                + "task=%s, prevActivity=%s", backType, task, prevActivity);

        if (backType == BackNavigationInfo.TYPE_RETURN_TO_HOME && isAnimationEnabled()) {
            if (triggerBack) {
                if (surfaceControl != null && surfaceControl.isValid()) {
                    // When going back to home, hide the task surface before it is re-parented to
                    // avoid flicker.
                    SurfaceControl.Transaction t = windowContainer.getSyncTransaction();
                    t.hide(surfaceControl);
                    t.apply();
                }
            }
            if (prevActivity != null && !triggerBack) {
                // Restore the launch-behind state.
                task.mTaskSupervisor.scheduleLaunchTaskBehindComplete(prevActivity.token);
                prevActivity.mLaunchTaskBehind = false;
                ProtoLog.d(WM_DEBUG_BACK_PREVIEW,
                        "Setting Activity.mLauncherTaskBehind to false. Activity=%s",
                        prevActivity);
            }
        } else {
            task.mBackGestureStarted = false;
        }
        resetSurfaces(windowContainer);
    }

    private HardwareBuffer getActivitySnapshot(@NonNull Task task,
            ComponentName activityComponent) {
        // Check if we have a screenshot of the previous activity, indexed by its
        // component name.
        SurfaceControl.ScreenshotHardwareBuffer backBuffer = task.mBackScreenshots
                .get(activityComponent.flattenToString());
        return backBuffer != null ? backBuffer.getHardwareBuffer() : null;

    }

    private HardwareBuffer getTaskSnapshot(int taskId, int userId) {
        if (mTaskSnapshotController == null) {
            return null;
        }
        TaskSnapshot snapshot = mTaskSnapshotController.getSnapshot(taskId,
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

    private void resetSurfaces(@NonNull WindowContainer<?> windowContainer) {
        synchronized (windowContainer.mWmService.mGlobalLock) {
            ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "Back: Reset surfaces");
            SurfaceControl.Transaction tx = windowContainer.getSyncTransaction();
            SurfaceControl surfaceControl = windowContainer.getSurfaceControl();
            if (surfaceControl != null) {
                tx.reparent(surfaceControl,
                        windowContainer.getParent().getSurfaceControl());
                tx.apply();
            }
        }
    }

    void setTaskSnapshotController(@Nullable TaskSnapshotController taskSnapshotController) {
        mTaskSnapshotController = taskSnapshotController;
    }
}
