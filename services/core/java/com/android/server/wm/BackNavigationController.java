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
import android.window.IOnBackInvokedCallback;
import android.window.TaskSnapshot;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.server.LocalServices;

/**
 * Controller to handle actions related to the back gesture on the server side.
 */
class BackNavigationController {

    private static final String TAG = "BackNavigationController";
    // By default, enable new back dispatching without any animations.
    private static final int BACK_PREDICTABILITY_PROP =
            SystemProperties.getInt("persist.debug.back_predictability", 1);
    private static final int ANIMATIONS_MASK = 1 << 1;
    private static final int SCREENSHOT_MASK = 1 << 2;

    @Nullable
    private TaskSnapshotController mTaskSnapshotController;

    /**
     * Returns true if the back predictability feature is enabled
     */
    static boolean isEnabled() {
        return BACK_PREDICTABILITY_PROP > 0;
    }

    static boolean isScreenshotEnabled() {
        return (BACK_PREDICTABILITY_PROP & SCREENSHOT_MASK) != 0;
    }

    private static boolean isAnimationEnabled() {
        return (BACK_PREDICTABILITY_PROP & ANIMATIONS_MASK) != 0;
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
        Task prevTask = null;
        ActivityRecord prev;
        WindowContainer<?> removedWindowContainer = null;
        ActivityRecord activityRecord = null;
        ActivityRecord prevTaskTopActivity = null;
        Task task = null;
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

            window = wmService.windowForClientLocked(null, focusedWindowToken,
                    false /* throwOnError */);

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

            if (window == null) {
                window = wmService.getFocusedWindowLocked();
                ProtoLog.d(WM_DEBUG_BACK_PREVIEW,
                        "Focused window found using wmService.getFocusedWindowLocked()");
            }

            if (window == null) {
                // We don't have any focused window, fallback ont the top task of the focused
                // display.
                ProtoLog.w(WM_DEBUG_BACK_PREVIEW,
                        "No focused window, defaulting to top task's window");
                task = wmService.mAtmService.getTopDisplayFocusedRootTask();
                window = task.getWindow(WindowState::isFocused);
            }

            // Now let's find if this window has a callback from the client side.
            IOnBackInvokedCallback applicationCallback = null;
            IOnBackInvokedCallback systemCallback = null;
            if (window != null) {
                activityRecord = window.mActivityRecord;
                task = window.getTask();
                applicationCallback = window.getApplicationOnBackInvokedCallback();
                if (applicationCallback != null) {
                    backType = BackNavigationInfo.TYPE_CALLBACK;
                    infoBuilder.setOnBackInvokedCallback(applicationCallback);
                } else {
                    systemCallback = window.getSystemOnBackInvokedCallback();
                    infoBuilder.setOnBackInvokedCallback(systemCallback);
                }
            }

            ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "startBackNavigation task=%s, "
                            + "topRunningActivity=%s, applicationBackCallback=%s, "
                            + "systemBackCallback=%s, currentFocus=%s",
                    task, activityRecord, applicationCallback, systemCallback, window);

            if (window == null) {
                Slog.e(TAG, "Window is null, returning null.");
                return null;
            }

            if (systemCallback == null && applicationCallback == null) {
                Slog.e(TAG, "No callback registered, returning null.");
                return null;
            }

            // If we don't need to set up the animation, we return early. This is the case when
            // - We have an application callback.
            // - We don't have any ActivityRecord or Task to animate.
            // - The IME is opened, and we just need to close it.
            // - The home activity is the focused activity.
            if (backType == BackNavigationInfo.TYPE_CALLBACK
                    || activityRecord == null
                    || task == null
                    || task.getDisplayContent().getImeContainer().isVisible()
                    || activityRecord.isActivityTypeHome()) {
                return infoBuilder
                        .setType(backType)
                        .build();
            }

            // We don't have an application callback, let's find the destination of the back gesture
            Task finalTask = task;
            prev = task.getActivity(
                    (r) -> !r.finishing && r.getTask() == finalTask && !r.isTopRunningActivity());
            if (window.getParent().getChildCount() > 1 && window.getParent().getChildAt(0)
                    != window) {
                // Are we the top window of our parent? If not, we are a window on top of the
                // activity, we won't close the activity.
                backType = BackNavigationInfo.TYPE_DIALOG_CLOSE;
                removedWindowContainer = window;
            } else if (prev != null) {
                // We have another Activity in the same task to go to
                backType = BackNavigationInfo.TYPE_CROSS_ACTIVITY;
                removedWindowContainer = activityRecord;
            } else if (task.returnsToHomeRootTask()) {
                // Our Task should bring back to home
                removedWindowContainer = task;
                backType = BackNavigationInfo.TYPE_RETURN_TO_HOME;
            } else if (activityRecord.isRootOfTask()) {
                // TODO(208789724): Create single source of truth for this, maybe in
                //  RootWindowContainer
                // TODO: Also check Task.shouldUpRecreateTaskLocked() for prev logic
                prevTask = task.mRootWindowContainer.getTaskBelow(task);
                removedWindowContainer = task;
                if (prevTask.isActivityTypeHome()) {
                    backType = BackNavigationInfo.TYPE_RETURN_TO_HOME;
                } else {
                    prev = prevTask.getTopNonFinishingActivity();
                    backType = BackNavigationInfo.TYPE_CROSS_TASK;
                }
            }
            infoBuilder.setType(backType);

            prevTaskId = prevTask != null ? prevTask.mTaskId : 0;
            prevUserId = prevTask != null ? prevTask.mUserId : 0;

            ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "Previous Destination is Activity:%s Task:%s "
                            + "removedContainer:%s, backType=%s",
                    prev != null ? prev.mActivityComponent : null,
                    prevTask != null ? prevTask.getName() : null,
                    removedWindowContainer,
                    BackNavigationInfo.typeToString(backType));

            // For now, we only animate when going home.
            boolean prepareAnimation = backType == BackNavigationInfo.TYPE_RETURN_TO_HOME
                    // Only create a new leash if no leash has been created.
                    // Otherwise return null for animation target to avoid conflict.
                    && !removedWindowContainer.hasCommittedReparentToAnimationLeash();

            if (prepareAnimation) {
                taskWindowConfiguration = task.getTaskInfo().configuration.windowConfiguration;

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
                        activityRecord,
                        task, animLeash);
                infoBuilder.setDepartingAnimationTarget(topAppTarget);
            }

            //TODO(207481538) Remove once the infrastructure to support per-activity screenshot is
            // implemented. For now we simply have the mBackScreenshots hash map that dumbly
            // saves the screenshots.
            if (needsScreenshot(backType) && prev != null && prev.mActivityComponent != null) {
                screenshotBuffer = getActivitySnapshot(task, prev.mActivityComponent);
            }

            if (backType == BackNavigationInfo.TYPE_RETURN_TO_HOME && isAnimationEnabled()) {
                task.mBackGestureStarted = true;
                // Make launcher show from behind by marking its top activity as visible and
                // launch-behind to bump its visibility for the duration of the back gesture.
                prevTaskTopActivity = prevTask.getTopNonFinishingActivity();
                if (prevTaskTopActivity != null) {
                    if (!prevTaskTopActivity.mVisibleRequested) {
                        prevTaskTopActivity.setVisibility(true);
                    }
                    prevTaskTopActivity.mLaunchTaskBehind = true;
                    ProtoLog.d(WM_DEBUG_BACK_PREVIEW,
                            "Setting Activity.mLauncherTaskBehind to true. Activity=%s",
                            prevTaskTopActivity);
                    prevTaskTopActivity.mRootWindowContainer.ensureActivitiesVisible(
                            null /* starting */, 0 /* configChanges */,
                            false /* preserveWindows */);
                }
            }
        } // Release wm Lock

        // Find a screenshot of the previous activity if we actually have an animation
        if (topAppTarget != null && needsScreenshot(backType) && prevTask != null
                && screenshotBuffer == null) {
            SurfaceControl.Builder builder = new SurfaceControl.Builder()
                    .setName("BackPreview Screenshot for " + prev)
                    .setParent(animationLeashParent)
                    .setHidden(false)
                    .setBLASTLayer();
            infoBuilder.setScreenshotSurface(builder.build());
            screenshotBuffer = getTaskSnapshot(prevTaskId, prevUserId);
            infoBuilder.setScreenshotBuffer(screenshotBuffer);


            // The Animation leash needs to be above the screenshot surface, but the animation leash
            // needs to be added before to be in the synchronized block.
            tx.setLayer(topAppTarget.leash, 1);
            tx.apply();


            WindowContainer<?> finalRemovedWindowContainer = removedWindowContainer;
            try {
                activityRecord.token.linkToDeath(
                        () -> resetSurfaces(finalRemovedWindowContainer), 0);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to link to death", e);
                resetSurfaces(removedWindowContainer);
                return null;
            }

            RemoteCallback onBackNavigationDone = new RemoteCallback(
                    result -> resetSurfaces(finalRemovedWindowContainer
                    ));
            infoBuilder.setOnBackNavigationDone(onBackNavigationDone);
        }
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
            Bundle result, WindowContainer windowContainer, int backType,
            Task task, ActivityRecord prevTaskTopActivity) {
        SurfaceControl surfaceControl = windowContainer.getSurfaceControl();
        boolean triggerBack = result != null
                ? result.getBoolean(BackNavigationInfo.KEY_TRIGGER_BACK)
                : false;
        ProtoLog.d(WM_DEBUG_BACK_PREVIEW, "onBackNavigationDone backType=%s, "
                + "task=%s, prevTaskTopActivity=%s", backType, task, prevTaskTopActivity);

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
            if (prevTaskTopActivity != null && !triggerBack) {
                // Restore the launch-behind state.
                task.mTaskSupervisor.scheduleLaunchTaskBehindComplete(prevTaskTopActivity.token);
                prevTaskTopActivity.mLaunchTaskBehind = false;
                ProtoLog.d(WM_DEBUG_BACK_PREVIEW,
                        "Setting Activity.mLauncherTaskBehind to false. Activity=%s",
                        prevTaskTopActivity);
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
