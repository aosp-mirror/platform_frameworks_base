/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.window.StartingWindowInfo.TYPE_PARAMETER_ACTIVITY_CREATED;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_ACTIVITY_DRAWN;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_ALLOW_HANDLE_SOLID_COLOR_SCREEN;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_ALLOW_TASK_SNAPSHOT;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_APP_PREFERS_ICON;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_LEGACY_SPLASH_SCREEN;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_NEW_TASK;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_PROCESS_RUNNING;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_TASK_SWITCH;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_USE_SOLID_COLOR_SPLASH_SCREEN;

import static com.android.server.wm.ActivityRecord.STARTING_WINDOW_TYPE_SNAPSHOT;
import static com.android.server.wm.ActivityRecord.STARTING_WINDOW_TYPE_SPLASH_SCREEN;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.pm.ApplicationInfo;
import android.os.UserHandle;
import android.util.Slog;
import android.window.ITaskOrganizer;
import android.window.SplashScreenView;
import android.window.TaskSnapshot;

import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * Managing to create and release a starting window surface.
 */
public class StartingSurfaceController {
    private static final String TAG = TAG_WITH_CLASS_NAME
            ? StartingSurfaceController.class.getSimpleName() : TAG_WM;
    /**
     * Application is allowed to receive the
     * {@link
     * android.window.SplashScreen.OnExitAnimationListener#onSplashScreenExit(SplashScreenView)}
     * callback, even when the splash screen only shows a solid color.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = android.os.Build.VERSION_CODES.TIRAMISU)
    private static final long ALLOW_COPY_SOLID_COLOR_VIEW = 205907456L;

    private final WindowManagerService mService;
    private final SplashScreenExceptionList mSplashScreenExceptionsList;

    // Cache status while deferring add starting window
    boolean mInitProcessRunning;
    boolean mInitNewTask;
    boolean mInitTaskSwitch;
    private final ArrayList<DeferringStartingWindowRecord> mDeferringAddStartActivities =
            new ArrayList<>();
    private boolean mDeferringAddStartingWindow;

    public StartingSurfaceController(WindowManagerService wm) {
        mService = wm;
        mSplashScreenExceptionsList = new SplashScreenExceptionList(wm.mContext.getMainExecutor());
    }

    StartingSurface createSplashScreenStartingSurface(ActivityRecord activity, int theme) {
        synchronized (mService.mGlobalLock) {
            final Task task = activity.getTask();
            final TaskOrganizerController controller =
                    mService.mAtmService.mTaskOrganizerController;
            if (task != null && controller.addStartingWindow(task, activity, theme,
                    null /* taskSnapshot */)) {
                return new StartingSurface(task, controller.getTaskOrganizer());
            }
        }
        return null;
    }

    /**
     * @see SplashScreenExceptionList#isException(String, int, Supplier)
     */
    boolean isExceptionApp(@NonNull String packageName, int targetSdk,
            @Nullable Supplier<ApplicationInfo> infoProvider) {
        return mSplashScreenExceptionsList.isException(packageName, targetSdk, infoProvider);
    }

    static int makeStartingWindowTypeParameter(boolean newTask, boolean taskSwitch,
            boolean processRunning, boolean allowTaskSnapshot, boolean activityCreated,
            boolean isSolidColor, boolean useLegacy, boolean activityDrawn, int startingWindowType,
            boolean appPrefersIcon, String packageName, int userId) {
        int parameter = 0;
        if (newTask) {
            parameter |= TYPE_PARAMETER_NEW_TASK;
        }
        if (taskSwitch) {
            parameter |= TYPE_PARAMETER_TASK_SWITCH;
        }
        if (processRunning) {
            parameter |= TYPE_PARAMETER_PROCESS_RUNNING;
        }
        if (allowTaskSnapshot) {
            parameter |= TYPE_PARAMETER_ALLOW_TASK_SNAPSHOT;
        }
        if (activityCreated || startingWindowType == STARTING_WINDOW_TYPE_SNAPSHOT) {
            parameter |= TYPE_PARAMETER_ACTIVITY_CREATED;
        }
        if (isSolidColor) {
            parameter |= TYPE_PARAMETER_USE_SOLID_COLOR_SPLASH_SCREEN;
        }
        if (useLegacy) {
            parameter |= TYPE_PARAMETER_LEGACY_SPLASH_SCREEN;
        }
        if (activityDrawn) {
            parameter |= TYPE_PARAMETER_ACTIVITY_DRAWN;
        }
        if (startingWindowType == STARTING_WINDOW_TYPE_SPLASH_SCREEN
                && CompatChanges.isChangeEnabled(ALLOW_COPY_SOLID_COLOR_VIEW, packageName,
                UserHandle.of(userId))) {
            parameter |= TYPE_PARAMETER_ALLOW_HANDLE_SOLID_COLOR_SCREEN;
        }
        if (appPrefersIcon) {
            parameter |= TYPE_PARAMETER_APP_PREFERS_ICON;
        }
        return parameter;
    }

    StartingSurface createTaskSnapshotSurface(ActivityRecord activity, TaskSnapshot taskSnapshot) {
        final WindowState topFullscreenOpaqueWindow;
        final Task task;
        synchronized (mService.mGlobalLock) {
            task = activity.getTask();
            if (task == null) {
                Slog.w(TAG, "TaskSnapshotSurface.create: Failed to find task for activity="
                        + activity);
                return null;
            }
            final ActivityRecord topFullscreenActivity =
                    activity.getTask().getTopFullscreenActivity();
            if (topFullscreenActivity == null) {
                Slog.w(TAG, "TaskSnapshotSurface.create: Failed to find top fullscreen for task="
                        + task);
                return null;
            }
            topFullscreenOpaqueWindow = topFullscreenActivity.getTopFullscreenOpaqueWindow();
            if (topFullscreenOpaqueWindow == null) {
                Slog.w(TAG, "TaskSnapshotSurface.create: no opaque window in "
                        + topFullscreenActivity);
                return null;
            }
            if (activity.mDisplayContent.getRotation() != taskSnapshot.getRotation()) {
                // The snapshot should have been checked by ActivityRecord#isSnapshotCompatible
                // that the activity will be updated to the same rotation as the snapshot. Since
                // the transition is not started yet, fixed rotation transform needs to be applied
                // earlier to make the snapshot show in a rotated container.
                activity.mDisplayContent.handleTopActivityLaunchingInDifferentOrientation(
                        activity, false /* checkOpening */);
            }
            final TaskOrganizerController controller =
                    mService.mAtmService.mTaskOrganizerController;
            if (controller.addStartingWindow(task, activity, 0 /* launchTheme */, taskSnapshot)) {
                return new StartingSurface(task, controller.getTaskOrganizer());
            }
            return null;
        }
    }

    private static final class DeferringStartingWindowRecord {
        final ActivityRecord mDeferring;
        final ActivityRecord mPrev;
        final ActivityRecord mSource;

        DeferringStartingWindowRecord(ActivityRecord deferring, ActivityRecord prev,
                ActivityRecord source) {
            mDeferring = deferring;
            mPrev = prev;
            mSource = source;
        }
    }

    /**
     * Shows a starting window while starting a new activity. Do not use this method to create a
     * starting window for an existing activity.
     */
    void showStartingWindow(ActivityRecord target, ActivityRecord prev,
            boolean newTask, boolean isTaskSwitch, ActivityRecord source) {
        if (mDeferringAddStartingWindow) {
            addDeferringRecord(target, prev, newTask, isTaskSwitch, source);
        } else {
            target.showStartingWindow(prev, newTask, isTaskSwitch, true /* startActivity */,
                    source);
        }
    }

    /**
     * Queueing the starting activity status while deferring add starting window.
     * @see Task#startActivityLocked
     */
    private void addDeferringRecord(ActivityRecord deferring, ActivityRecord prev,
            boolean newTask, boolean isTaskSwitch, ActivityRecord source) {
        // Set newTask, taskSwitch, processRunning form first activity because those can change
        // after first activity started.
        if (mDeferringAddStartActivities.isEmpty()) {
            mInitProcessRunning = deferring.isProcessRunning();
            mInitNewTask = newTask;
            mInitTaskSwitch = isTaskSwitch;
        }
        mDeferringAddStartActivities.add(new DeferringStartingWindowRecord(
                deferring, prev, source));
    }

    private void showStartingWindowFromDeferringActivities(ActivityOptions topOptions) {
        // Attempt to add starting window from the top-most activity.
        for (int i = mDeferringAddStartActivities.size() - 1; i >= 0; --i) {
            final DeferringStartingWindowRecord next = mDeferringAddStartActivities.get(i);
            if (next.mDeferring.getTask() == null) {
                Slog.e(TAG, "No task exists: " + next.mDeferring.shortComponentName
                        + " parent: " + next.mDeferring.getParent());
                continue;
            }
            next.mDeferring.showStartingWindow(next.mPrev, mInitNewTask, mInitTaskSwitch,
                    mInitProcessRunning, true /* startActivity */, next.mSource, topOptions);
            // If one succeeds, it is done.
            if (next.mDeferring.mStartingData != null) {
                break;
            }
        }
        mDeferringAddStartActivities.clear();
    }

    /**
     * Begin deferring add starting window in one pass.
     * This is used to deferring add starting window while starting multiples activities because
     * system only need to provide a starting window to the top-visible activity.
     * Most call {@link #endDeferAddStartingWindow} when starting activities process finished.
     * @see #endDeferAddStartingWindow()
     */
    void beginDeferAddStartingWindow() {
        mDeferringAddStartingWindow = true;
    }

    /**
     * End deferring add starting window.
     */
    void endDeferAddStartingWindow(ActivityOptions topOptions) {
        mDeferringAddStartingWindow = false;
        showStartingWindowFromDeferringActivities(topOptions);
    }

    final class StartingSurface {
        private final Task mTask;
        // The task organizer which hold the client side reference of this surface.
        final ITaskOrganizer mTaskOrganizer;

        StartingSurface(Task task, ITaskOrganizer taskOrganizer) {
            mTask = task;
            mTaskOrganizer = taskOrganizer;
        }

        /**
         * Removes the starting window surface. Do not hold the window manager lock when calling
         * this method!
         *
         * @param animate Whether need to play the default exit animation for starting window.
         * @param hasImeSurface Whether the starting window has IME surface.
         */
        public void remove(boolean animate, boolean hasImeSurface) {
            synchronized (mService.mGlobalLock) {
                mService.mAtmService.mTaskOrganizerController.removeStartingWindow(mTask,
                        mTaskOrganizer, animate, hasImeSurface);
            }
        }
    }
}
