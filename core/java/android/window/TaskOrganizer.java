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
import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.app.ActivityManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.SurfaceControl;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Interface for ActivityTaskManager/WindowManager to delegate control of tasks.
 * @hide
 */
@TestApi
public class TaskOrganizer extends WindowOrganizer {

    private final ITaskOrganizerController mTaskOrganizerController;
    // Callbacks WM Core are posted on this executor if it isn't null, otherwise direct calls are
    // made on the incoming binder call.
    private final Executor mExecutor;

    public TaskOrganizer() {
        this(null /*taskOrganizerController*/, null /*executor*/);
    }

    /** @hide */
    @VisibleForTesting
    public TaskOrganizer(ITaskOrganizerController taskOrganizerController, Executor executor) {
        mExecutor = executor != null ? executor : Runnable::run;
        mTaskOrganizerController = taskOrganizerController != null
                ? taskOrganizerController : getController();
    }

    /**
     * Register a TaskOrganizer to manage tasks as they enter a supported windowing mode.
     *
     * @return a list of the tasks that should be managed by the organizer, not including tasks
     *         created via {@link #createRootTask}.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    @CallSuper
    @NonNull
    public List<TaskAppearedInfo> registerOrganizer() {
        try {
            return mTaskOrganizerController.registerTaskOrganizer(mInterface).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Unregisters a previously registered task organizer. */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    @CallSuper
    public void unregisterOrganizer() {
        try {
            mTaskOrganizerController.unregisterTaskOrganizer(mInterface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called when a Task is starting and the system would like to show a UI to indicate that an
     * application is starting. The client is responsible to add/remove the starting window if it
     * has create a starting window for the Task.
     *
     * @param info The information about the Task that's available
     * @hide
     */
    @BinderThread
    public void addStartingWindow(@NonNull StartingWindowInfo info) {}

    /**
     * Called when the Task want to remove the starting window.
     * @param removalInfo The information used to remove the starting window.
     * @hide
     */
    @BinderThread
    public void removeStartingWindow(@NonNull StartingWindowRemovalInfo removalInfo) {}

    /**
     * Called when the Task want to copy the splash screen.
     */
    @BinderThread
    public void copySplashScreenView(int taskId) {}

    /**
     * Notify the shell ({@link com.android.wm.shell.ShellTaskOrganizer} that the client has
     * removed the splash screen view.
     * @see com.android.wm.shell.ShellTaskOrganizer#onAppSplashScreenViewRemoved(int)
     * @see SplashScreenView#remove()
     */
    @BinderThread
    public void onAppSplashScreenViewRemoved(int taskId) {
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

    /** @hide */
    @BinderThread
    public void onImeDrawnOnTask(int taskId) {}

    /**
     * Creates a persistent root task in WM for a particular windowing-mode.
     * @param displayId The display to create the root task on.
     * @param windowingMode Windowing mode to put the root task in.
     * @param launchCookie Launch cookie to associate with the task so that is can be identified
     *                     when the {@link ITaskOrganizer#onTaskAppeared} callback is called.
     * @param removeWithTaskOrganizer True if this task should be removed when organizer destroyed.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    public void createRootTask(int displayId, int windowingMode, @Nullable IBinder launchCookie,
            boolean removeWithTaskOrganizer) {
        try {
            mTaskOrganizerController.createRootTask(displayId, windowingMode, launchCookie,
                    removeWithTaskOrganizer);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a persistent root task in WM for a particular windowing-mode.
     * @param displayId The display to create the root task on.
     * @param windowingMode Windowing mode to put the root task in.
     * @param launchCookie Launch cookie to associate with the task so that is can be identified
     *                     when the {@link ITaskOrganizer#onTaskAppeared} callback is called.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    @Nullable
    public void createRootTask(int displayId, int windowingMode, @Nullable IBinder launchCookie) {
        createRootTask(displayId, windowingMode, launchCookie, false /* removeWithTaskOrganizer */);
    }

    /** Deletes a persistent root task in WM */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    public boolean deleteRootTask(@NonNull WindowContainerToken task) {
        try {
            return mTaskOrganizerController.deleteRootTask(task);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Gets direct child tasks (ordered from top-to-bottom) */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    @Nullable
    @SuppressLint("NullableCollection")
    public List<ActivityManager.RunningTaskInfo> getChildTasks(
            @NonNull WindowContainerToken parent, @NonNull int[] activityTypes) {
        try {
            return mTaskOrganizerController.getChildTasks(parent, activityTypes);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Gets all root tasks on a display (ordered from top-to-bottom) */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    @Nullable
    @SuppressLint("NullableCollection")
    public List<ActivityManager.RunningTaskInfo> getRootTasks(
            int displayId, @NonNull int[] activityTypes) {
        try {
            return mTaskOrganizerController.getRootTasks(displayId, activityTypes);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Get the {@link WindowContainerToken} of the task which contains the current ime target */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    @Nullable
    public WindowContainerToken getImeTarget(int display) {
        try {
            return mTaskOrganizerController.getImeTarget(display);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests that the given task organizer is notified when back is pressed on the root activity
     * of one of its controlled tasks.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    public void setInterceptBackPressedOnTaskRoot(@NonNull WindowContainerToken task,
            boolean interceptBackPressed) {
        try {
            mTaskOrganizerController.setInterceptBackPressedOnTaskRoot(task, interceptBackPressed);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Restarts the top activity in the given task by killing its process if it is visible.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    public void restartTaskTopActivityProcessIfVisible(@NonNull WindowContainerToken task) {
        try {
            mTaskOrganizerController.restartTaskTopActivityProcessIfVisible(task);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the executor to run callbacks on.
     * @hide
     */
    @NonNull
    public Executor getExecutor() {
        return mExecutor;
    }

    private final ITaskOrganizer mInterface = new ITaskOrganizer.Stub() {
        @Override
        public void addStartingWindow(StartingWindowInfo windowInfo) {
            mExecutor.execute(() -> TaskOrganizer.this.addStartingWindow(windowInfo));
        }

        @Override
        public void removeStartingWindow(StartingWindowRemovalInfo removalInfo) {
            mExecutor.execute(() -> TaskOrganizer.this.removeStartingWindow(removalInfo));
        }

        @Override
        public void copySplashScreenView(int taskId)  {
            mExecutor.execute(() -> TaskOrganizer.this.copySplashScreenView(taskId));
        }

        @Override
        public void onAppSplashScreenViewRemoved(int taskId) {
            mExecutor.execute(() -> TaskOrganizer.this.onAppSplashScreenViewRemoved(taskId));
        }

        @Override
        public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
            mExecutor.execute(() -> TaskOrganizer.this.onTaskAppeared(taskInfo, leash));
        }

        @Override
        public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
            mExecutor.execute(() -> TaskOrganizer.this.onTaskVanished(taskInfo));
        }

        @Override
        public void onTaskInfoChanged(ActivityManager.RunningTaskInfo info) {
            mExecutor.execute(() -> TaskOrganizer.this.onTaskInfoChanged(info));
        }

        @Override
        public void onBackPressedOnTaskRoot(ActivityManager.RunningTaskInfo info) {
            mExecutor.execute(() -> TaskOrganizer.this.onBackPressedOnTaskRoot(info));
        }

        @Override
        public void onImeDrawnOnTask(int taskId) {
            mExecutor.execute(() -> TaskOrganizer.this.onImeDrawnOnTask(taskId));
        }
    };

    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    private ITaskOrganizerController getController() {
        try {
            return getWindowOrganizerController().getTaskOrganizerController();
        } catch (RemoteException e) {
            return null;
        }
    }
}
