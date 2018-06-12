/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.app;

import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Singleton;

/**
 * This class gives information about, and interacts with activities and their containers like task,
 * stacks, and displays.
 *
 * @hide
 */
@TestApi
@SystemService(Context.ACTIVITY_TASK_SERVICE)
public class ActivityTaskManager {

    /** Invalid stack ID. */
    public static final int INVALID_STACK_ID = -1;

    /**
     * Parameter to {@link IActivityTaskManager#setTaskWindowingModeSplitScreenPrimary} which
     * specifies the position of the created docked stack at the top half of the screen if
     * in portrait mode or at the left half of the screen if in landscape mode.
     */
    public static final int SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT = 0;

    /**
     * Parameter to {@link IActivityTaskManager#setTaskWindowingModeSplitScreenPrimary} which
     * specifies the position of the created docked stack at the bottom half of the screen if
     * in portrait mode or at the right half of the screen if in landscape mode.
     */
    public static final int SPLIT_SCREEN_CREATE_MODE_BOTTOM_OR_RIGHT = 1;

    /**
     * Input parameter to {@link IActivityTaskManager#resizeTask} which indicates
     * that the resize doesn't need to preserve the window, and can be skipped if bounds
     * is unchanged. This mode is used by window manager in most cases.
     * @hide
     */
    public static final int RESIZE_MODE_SYSTEM = 0;

    /**
     * Input parameter to {@link IActivityTaskManager#resizeTask} which indicates
     * that the resize should preserve the window if possible.
     * @hide
     */
    public static final int RESIZE_MODE_PRESERVE_WINDOW   = (0x1 << 0);

    /**
     * Input parameter to {@link IActivityTaskManager#resizeTask} used when the
     * resize is due to a drag action.
     * @hide
     */
    public static final int RESIZE_MODE_USER = RESIZE_MODE_PRESERVE_WINDOW;

    /**
     * Input parameter to {@link IActivityTaskManager#resizeTask} used by window
     * manager during a screen rotation.
     * @hide
     */
    public static final int RESIZE_MODE_SYSTEM_SCREEN_ROTATION = RESIZE_MODE_PRESERVE_WINDOW;

    /**
     * Input parameter to {@link IActivityTaskManager#resizeTask} which indicates
     * that the resize should be performed even if the bounds appears unchanged.
     * @hide
     */
    public static final int RESIZE_MODE_FORCED = (0x1 << 1);

    /**
     * Input parameter to {@link IActivityTaskManager#resizeTask} which indicates
     * that the resize should preserve the window if possible, and should not be skipped
     * even if the bounds is unchanged. Usually used to force a resizing when a drag action
     * is ending.
     * @hide
     */
    public static final int RESIZE_MODE_USER_FORCED =
            RESIZE_MODE_PRESERVE_WINDOW | RESIZE_MODE_FORCED;

    private static int sMaxRecentTasks = -1;

    ActivityTaskManager(Context context, Handler handler) {
    }

    /** @hide */
    public static IActivityTaskManager getService() {
        return IActivityTaskManagerSingleton.get();
    }

    private static final Singleton<IActivityTaskManager> IActivityTaskManagerSingleton =
            new Singleton<IActivityTaskManager>() {
                @Override
                protected IActivityTaskManager create() {
                    final IBinder b = ServiceManager.getService(Context.ACTIVITY_TASK_SERVICE);
                    return IActivityTaskManager.Stub.asInterface(b);
                }
            };

    /**
     * Sets the windowing mode for a specific task. Only works on tasks of type
     * {@link WindowConfiguration#ACTIVITY_TYPE_STANDARD}
     * @param taskId The id of the task to set the windowing mode for.
     * @param windowingMode The windowing mode to set for the task.
     * @param toTop If the task should be moved to the top once the windowing mode changes.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    public void setTaskWindowingMode(int taskId, int windowingMode, boolean toTop)
            throws SecurityException {
        try {
            getService().setTaskWindowingMode(taskId, windowingMode, toTop);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Moves the input task to the primary-split-screen stack.
     * @param taskId Id of task to move.
     * @param createMode The mode the primary split screen stack should be created in if it doesn't
     *                   exist already. See
     *                   {@link ActivityTaskManager#SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT}
     *                   and
     *                   {@link android.app.ActivityManager
     *                        #SPLIT_SCREEN_CREATE_MODE_BOTTOM_OR_RIGHT}
     * @param toTop If the task and stack should be moved to the top.
     * @param animate Whether we should play an animation for the moving the task
     * @param initialBounds If the primary stack gets created, it will use these bounds for the
     *                      docked stack. Pass {@code null} to use default bounds.
     * @param showRecents If the recents activity should be shown on the other side of the task
     *                    going into split-screen mode.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    public void setTaskWindowingModeSplitScreenPrimary(int taskId, int createMode, boolean toTop,
            boolean animate, Rect initialBounds, boolean showRecents) throws SecurityException {
        try {
            getService().setTaskWindowingModeSplitScreenPrimary(taskId, createMode, toTop, animate,
                    initialBounds, showRecents);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Resizes the input stack id to the given bounds.
     * @param stackId Id of the stack to resize.
     * @param bounds Bounds to resize the stack to or {@code null} for fullscreen.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    public void resizeStack(int stackId, Rect bounds) throws SecurityException {
        try {
            getService().resizeStack(stackId, bounds, false /* allowResizeInDockedMode */,
                    false /* preserveWindows */, false /* animate */, -1 /* animationDuration */);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes stacks in the windowing modes from the system if they are of activity type
     * ACTIVITY_TYPE_STANDARD or ACTIVITY_TYPE_UNDEFINED
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    public void removeStacksInWindowingModes(int[] windowingModes) throws SecurityException {
        try {
            getService().removeStacksInWindowingModes(windowingModes);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Removes stack of the activity types from the system. */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    public void removeStacksWithActivityTypes(int[] activityTypes) throws SecurityException {
        try {
            getService().removeStacksWithActivityTypes(activityTypes);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the maximum number of recents entries that we will maintain and show.
     * @hide
     */
    public static int getMaxRecentTasksStatic() {
        if (sMaxRecentTasks < 0) {
            return sMaxRecentTasks = ActivityManager.isLowRamDeviceStatic() ? 36 : 48;
        }
        return sMaxRecentTasks;
    }

    /**
     * Return the default limit on the number of recents that an app can make.
     * @hide
     */
    public static int getDefaultAppRecentsLimitStatic() {
        return getMaxRecentTasksStatic() / 6;
    }

    /**
     * Return the maximum limit on the number of recents that an app can make.
     * @hide
     */
    public static int getMaxAppRecentsLimitStatic() {
        return getMaxRecentTasksStatic() / 2;
    }

    /**
     * Returns true if the system supports at least one form of multi-window.
     * E.g. freeform, split-screen, picture-in-picture.
     */
    public static boolean supportsMultiWindow(Context context) {
        // On watches, multi-window is used to present essential system UI, and thus it must be
        // supported regardless of device memory characteristics.
        boolean isWatch = context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WATCH);
        return (!ActivityManager.isLowRamDeviceStatic() || isWatch)
                && Resources.getSystem().getBoolean(
                com.android.internal.R.bool.config_supportsMultiWindow);
    }

    /** Returns true if the system supports split screen multi-window. */
    public static boolean supportsSplitScreenMultiWindow(Context context) {
        return supportsMultiWindow(context)
                && Resources.getSystem().getBoolean(
                com.android.internal.R.bool.config_supportsSplitScreenMultiWindow);
    }
}
