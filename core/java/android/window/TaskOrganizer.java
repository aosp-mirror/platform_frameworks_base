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

package android.window;

import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.app.ActivityManager;
import android.os.RemoteException;
import android.util.Singleton;
import android.view.SurfaceControl;

import java.util.List;

/**
 * Interface for ActivityTaskManager/WindowManager to delegate control of tasks.
 * @hide
 */
@TestApi
public class TaskOrganizer extends WindowOrganizer {

    /**
     * Register a TaskOrganizer to manage tasks as they enter the given windowing mode.
     * If there was already a TaskOrganizer for this windowing mode it will be evicted
     * and receive taskVanished callbacks in the process.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    public final void registerOrganizer(int windowingMode) {
        try {
            getController().registerTaskOrganizer(mInterface, windowingMode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Unregisters a previously registered task organizer. */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    public final void unregisterOrganizer() {
        try {
            getController().unregisterTaskOrganizer(mInterface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called when a task with the registered windowing mode can be controlled by this task
     * organizer. For non-root tasks, the leash may initially be hidden so it is up to the organizer
     * to show this task.
     */
    @BinderThread
    public void onTaskAppeared(@NonNull ActivityManager.RunningTaskInfo taskInfo,
            @NonNull SurfaceControl leash) {}

    @BinderThread
    public void onTaskVanished(@NonNull ActivityManager.RunningTaskInfo taskInfo) {}

    @BinderThread
    public void onTaskInfoChanged(@NonNull ActivityManager.RunningTaskInfo taskInfo) {}

    @BinderThread
    public void onBackPressedOnTaskRoot(@NonNull ActivityManager.RunningTaskInfo taskInfo) {}

    /** Creates a persistent root task in WM for a particular windowing-mode. */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    @Nullable
    public static ActivityManager.RunningTaskInfo createRootTask(int displayId, int windowingMode) {
        try {
            return getController().createRootTask(displayId, windowingMode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Deletes a persistent root task in WM */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    public static boolean deleteRootTask(@NonNull WindowContainerToken task) {
        try {
            return getController().deleteRootTask(task);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Gets direct child tasks (ordered from top-to-bottom) */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    @Nullable
    public static List<ActivityManager.RunningTaskInfo> getChildTasks(
            @NonNull WindowContainerToken parent, @NonNull int[] activityTypes) {
        try {
            return getController().getChildTasks(parent, activityTypes);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Gets all root tasks on a display (ordered from top-to-bottom) */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    @Nullable
    public static List<ActivityManager.RunningTaskInfo> getRootTasks(
            int displayId, @NonNull int[] activityTypes) {
        try {
            return getController().getRootTasks(displayId, activityTypes);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Get the root task which contains the current ime target */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    @Nullable
    public static WindowContainerToken getImeTarget(int display) {
        try {
            return getController().getImeTarget(display);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set's the root task to launch new tasks into on a display. {@code null} means no launch
     * root and thus new tasks just end up directly on the display.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    public static void setLaunchRoot(int displayId, @NonNull WindowContainerToken root) {
        try {
            getController().setLaunchRoot(displayId, root);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests that the given task organizer is notified when back is pressed on the root activity
     * of one of its controlled tasks.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    public void setInterceptBackPressedOnTaskRoot(boolean interceptBackPressed) {
        try {
            getController().setInterceptBackPressedOnTaskRoot(mInterface, interceptBackPressed);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private final ITaskOrganizer mInterface = new ITaskOrganizer.Stub() {

        @Override
        public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
            TaskOrganizer.this.onTaskAppeared(taskInfo, leash);
        }

        @Override
        public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
            TaskOrganizer.this.onTaskVanished(taskInfo);
        }

        @Override
        public void onTaskInfoChanged(ActivityManager.RunningTaskInfo info) {
            TaskOrganizer.this.onTaskInfoChanged(info);
        }

        @Override
        public void onBackPressedOnTaskRoot(ActivityManager.RunningTaskInfo info) {
            TaskOrganizer.this.onBackPressedOnTaskRoot(info);
        }
    };

    private static ITaskOrganizerController getController() {
        return ITaskOrganizerControllerSingleton.get();
    }

    private static final Singleton<ITaskOrganizerController> ITaskOrganizerControllerSingleton =
            new Singleton<ITaskOrganizerController>() {
                @Override
                protected ITaskOrganizerController create() {
                    try {
                        return getWindowOrganizerController().getTaskOrganizerController();
                    } catch (RemoteException e) {
                        return null;
                    }
                }
            };
}
