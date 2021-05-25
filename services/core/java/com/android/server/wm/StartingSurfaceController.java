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
import static android.window.StartingWindowInfo.TYPE_PARAMETER_ALLOW_TASK_SNAPSHOT;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_NEW_TASK;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_PROCESS_RUNNING;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_TASK_SWITCH;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_USE_EMPTY_SPLASH_SCREEN;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.SystemProperties;
import android.util.Slog;
import android.window.TaskSnapshot;

import com.android.server.policy.WindowManagerPolicy.StartingSurface;

/**
 * Managing to create and release a starting window surface.
 */
public class StartingSurfaceController {
    private static final String TAG = TAG_WITH_CLASS_NAME
            ? StartingSurfaceController.class.getSimpleName() : TAG_WM;
    /** Set to {@code true} to enable shell starting surface drawer. */
    static final boolean DEBUG_ENABLE_SHELL_DRAWER =
            SystemProperties.getBoolean("persist.debug.shell_starting_surface", true);
    private final WindowManagerService mService;

    public StartingSurfaceController(WindowManagerService wm) {
        mService = wm;
    }

    StartingSurface createSplashScreenStartingSurface(ActivityRecord activity, String packageName,
            int theme, CompatibilityInfo compatInfo, CharSequence nonLocalizedLabel, int labelRes,
            int icon, int logo, int windowFlags, Configuration overrideConfig, int displayId) {
        if (!DEBUG_ENABLE_SHELL_DRAWER) {
            return mService.mPolicy.addSplashScreen(activity.token, activity.mUserId, packageName,
                    theme, compatInfo, nonLocalizedLabel, labelRes, icon, logo, windowFlags,
                    overrideConfig, displayId);
        }

        synchronized (mService.mGlobalLock) {
            final Task task = activity.getTask();
            if (task != null && mService.mAtmService.mTaskOrganizerController.addStartingWindow(
                    task, activity.token, theme, null /* taskSnapshot */)) {
                return new ShellStartingSurface(task);
            }
        }
        return null;
    }

    int makeStartingWindowTypeParameter(boolean newTask, boolean taskSwitch,
            boolean processRunning, boolean allowTaskSnapshot, boolean activityCreated,
            boolean useEmpty) {
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
        if (activityCreated) {
            parameter |= TYPE_PARAMETER_ACTIVITY_CREATED;
        }
        if (useEmpty) {
            parameter |= TYPE_PARAMETER_USE_EMPTY_SPLASH_SCREEN;
        }
        return parameter;
    }

    StartingSurface createTaskSnapshotSurface(ActivityRecord activity, TaskSnapshot taskSnapshot) {
        final WindowState topFullscreenOpaqueWindow;
        final Task task;
        synchronized (mService.mGlobalLock) {
            final WindowState mainWindow = activity.findMainWindow();
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
            if (mainWindow == null || topFullscreenOpaqueWindow == null) {
                Slog.w(TAG, "TaskSnapshotSurface.create: Failed to find main window for activity="
                        + activity);
                return null;
            }
            if (topFullscreenActivity.getWindowConfiguration().getRotation()
                    != taskSnapshot.getRotation()
                    // Use normal rotation to avoid flickering of IME window in old orientation.
                    && !taskSnapshot.hasImeSurface()) {
                // The snapshot should have been checked by ActivityRecord#isSnapshotCompatible
                // that the activity will be updated to the same rotation as the snapshot. Since
                // the transition is not started yet, fixed rotation transform needs to be applied
                // earlier to make the snapshot show in a rotated container.
                activity.mDisplayContent.handleTopActivityLaunchingInDifferentOrientation(
                        topFullscreenActivity, false /* checkOpening */);
            }
            if (DEBUG_ENABLE_SHELL_DRAWER) {
                mService.mAtmService.mTaskOrganizerController.addStartingWindow(task,
                        activity.token, 0 /* launchTheme */, taskSnapshot);
                return new ShellStartingSurface(task);
            }
        }
        return mService.mTaskSnapshotController.createStartingSurface(activity, taskSnapshot);
    }


    private final class ShellStartingSurface implements StartingSurface {
        private final Task mTask;

        ShellStartingSurface(Task task) {
            mTask = task;
        }

        @Override
        public void remove(boolean animate) {
            synchronized (mService.mGlobalLock) {
                mService.mAtmService.mTaskOrganizerController.removeStartingWindow(mTask, animate);
            }
        }
    }
}
