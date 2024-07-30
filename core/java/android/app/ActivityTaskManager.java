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

import static android.view.Display.INVALID_DISPLAY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.DisplayMetrics;
import android.util.Singleton;
import android.view.RemoteAnimationDefinition;
import android.window.SplashScreenView.SplashScreenViewParcelable;

import java.util.List;

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
     * Invalid task ID.
     * @hide
     */
    public static final int INVALID_TASK_ID = -1;

    /**
     * Invalid windowing mode.
     * @hide
     */
    public static final int INVALID_WINDOWING_MODE = -1;

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

    /**
     * Extra included on intents that contain an EXTRA_INTENT, with options that the contained
     * intent may want to be started with.  Type is Bundle.
     * TODO: remove once the ChooserActivity moves to systemui
     * @hide
     */
    public static final String EXTRA_OPTIONS = "android.app.extra.OPTIONS";

    /**
     * Extra included on intents that contain an EXTRA_INTENT, use this boolean value for the
     * parameter of the same name when starting the contained intent.
     * TODO: remove once the ChooserActivity moves to systemui
     * @hide
     */
    public static final String EXTRA_IGNORE_TARGET_SECURITY =
            "android.app.extra.EXTRA_IGNORE_TARGET_SECURITY";

    /** The minimal size of a display's long-edge needed to support split-screen multi-window. */
    public static final int DEFAULT_MINIMAL_SPLIT_SCREEN_DISPLAY_SIZE_DP = 440;

    private static int sMaxRecentTasks = -1;

    private static final Singleton<ActivityTaskManager> sInstance =
            new Singleton<ActivityTaskManager>() {
                @Override
                protected ActivityTaskManager create() {
                    return new ActivityTaskManager();
                }
            };

    private ActivityTaskManager() {
    }

    /** @hide */
    public static ActivityTaskManager getInstance() {
        return sInstance.get();
    }

    /** @hide */
    public static IActivityTaskManager getService() {
        return IActivityTaskManagerSingleton.get();
    }

    @UnsupportedAppUsage(trackingBug = 129726065)
    private static final Singleton<IActivityTaskManager> IActivityTaskManagerSingleton =
            new Singleton<IActivityTaskManager>() {
                @Override
                protected IActivityTaskManager create() {
                    final IBinder b = ServiceManager.getService(Context.ACTIVITY_TASK_SERVICE);
                    return IActivityTaskManager.Stub.asInterface(b);
                }
            };

    /**
     * Removes root tasks in the windowing modes from the system if they are of activity type
     * ACTIVITY_TYPE_STANDARD or ACTIVITY_TYPE_UNDEFINED
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    public void removeRootTasksInWindowingModes(@NonNull int[] windowingModes) {
        try {
            getService().removeRootTasksInWindowingModes(windowingModes);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Removes root tasks of the activity types from the Default TDA of all displays. */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    public void removeRootTasksWithActivityTypes(@NonNull int[] activityTypes) {
        try {
            getService().removeRootTasksWithActivityTypes(activityTypes);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes all visible recent tasks from the system.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.REMOVE_TASKS)
    public void removeAllVisibleRecentTasks() {
        try {
            getService().removeAllVisibleRecentTasks();
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
     * Notify the server that splash screen of the given task has been copied"
     *
     * @param taskId Id of task to handle the material to reconstruct the splash screen view.
     * @param parcelable Used to reconstruct the view, null means the surface is un-copyable.
     * @hide
     */
    public void onSplashScreenViewCopyFinished(int taskId,
            @Nullable SplashScreenViewParcelable parcelable) {
        try {
            getService().onSplashScreenViewCopyFinished(taskId, parcelable);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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

    /**
     * Returns {@code true} if the display the context is associated with supports split screen
     * multi-window.
     *
     * @throws UnsupportedOperationException if the supplied {@link Context} is not associated with
     * a display.
     */
    public static boolean supportsSplitScreenMultiWindow(Context context) {
        DisplayMetrics dm = new DisplayMetrics();
        context.getDisplay().getRealMetrics(dm);

        int widthDp = (int) (dm.widthPixels / dm.density);
        int heightDp = (int) (dm.heightPixels / dm.density);
        if (Math.max(widthDp, heightDp) < DEFAULT_MINIMAL_SPLIT_SCREEN_DISPLAY_SIZE_DP) {
            return false;
        }

        return supportsMultiWindow(context)
                && Resources.getSystem().getBoolean(
                com.android.internal.R.bool.config_supportsSplitScreenMultiWindow);
    }

    /**
     * Start to enter lock task mode for given task by system(UI).
     * @param taskId Id of task to lock.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    public void startSystemLockTaskMode(int taskId) {
        try {
            getService().startSystemLockTaskMode(taskId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Stop lock task mode by system(UI).
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    public void stopSystemLockTaskMode() {
        try {
            getService().stopSystemLockTaskMode();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Move task to root task with given id.
     * @param taskId Id of the task to move.
     * @param rootTaskId Id of the rootTask for task moving.
     * @param toTop Whether the given task should shown to top of stack.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    public void moveTaskToRootTask(int taskId, int rootTaskId, boolean toTop) {
        try {
            getService().moveTaskToRootTask(taskId, rootTaskId, toTop);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Resize task to given bounds.
     * @param taskId Id of task to resize.
     * @param bounds Bounds to resize task.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    public void resizeTask(int taskId, Rect bounds) {
        try {
            getService().resizeTask(taskId, bounds, RESIZE_MODE_SYSTEM);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Clears launch params for the given package.
     * @param packageNames the names of the packages of which the launch params are to be cleared
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    public void clearLaunchParamsForPackages(List<String> packageNames) {
        try {
            getService().clearLaunchParamsForPackages(packageNames);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * @return whether the UI mode of the given config supports error dialogs (ANR, crash, etc).
     * @hide
     */
    public static boolean currentUiModeSupportsErrorDialogs(@NonNull Configuration config) {
        int modeType = config.uiMode & Configuration.UI_MODE_TYPE_MASK;
        return (modeType != Configuration.UI_MODE_TYPE_CAR
                && !(modeType == Configuration.UI_MODE_TYPE_WATCH && Build.IS_USER)
                && modeType != Configuration.UI_MODE_TYPE_TELEVISION
                && modeType != Configuration.UI_MODE_TYPE_VR_HEADSET);
    }

    /** @return whether the current UI mode supports error dialogs (ANR, crash, etc). */
    public static boolean currentUiModeSupportsErrorDialogs(@NonNull Context context) {
        final Configuration config = context.getResources().getConfiguration();
        return currentUiModeSupportsErrorDialogs(config);
    }

    /** @return max allowed number of actions in picture-in-picture mode. */
    public static int getMaxNumPictureInPictureActions(@NonNull Context context) {
        return context.getResources().getInteger(
                com.android.internal.R.integer.config_pictureInPictureMaxNumberOfActions);
    }

    /**
     * @return List of running tasks.
     * @hide
     */
    public List<ActivityManager.RunningTaskInfo> getTasks(int maxNum) {
        return getTasks(maxNum, false /* filterForVisibleRecents */, false /* keepIntentExtra */,
                INVALID_DISPLAY);
    }

    /**
     * @return List of running tasks that can be filtered by visibility in recents.
     * @hide
     */
    public List<ActivityManager.RunningTaskInfo> getTasks(
            int maxNum, boolean filterOnlyVisibleRecents) {
        return getTasks(maxNum, filterOnlyVisibleRecents, false /* keepIntentExtra */,
                INVALID_DISPLAY);
    }

    /**
     * @return List of running tasks that can be filtered by visibility in recents and keep intent
     * extra.
     * @hide
     */
    public List<ActivityManager.RunningTaskInfo> getTasks(
            int maxNum, boolean filterOnlyVisibleRecents, boolean keepIntentExtra) {
        return getTasks(maxNum, filterOnlyVisibleRecents, keepIntentExtra, INVALID_DISPLAY);
    }

    /**
     * @return List of running tasks that can be filtered by visibility and displayId in recents
     * and keep intent extra.
     * @param displayId the target display id, or {@link INVALID_DISPLAY} not to filter by displayId
     * @hide
     */
    public List<ActivityManager.RunningTaskInfo> getTasks(
            int maxNum, boolean filterOnlyVisibleRecents, boolean keepIntentExtra, int displayId) {
        try {
            return getService().getTasks(maxNum, filterOnlyVisibleRecents, keepIntentExtra,
                    displayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @return List of recent tasks.
     * @hide
     */
    public List<ActivityManager.RecentTaskInfo> getRecentTasks(
            int maxNum, int flags, int userId) {
        try {
            return getService().getRecentTasks(maxNum, flags, userId).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void registerTaskStackListener(TaskStackListener listener) {
        try {
            getService().registerTaskStackListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void unregisterTaskStackListener(TaskStackListener listener) {
        try {
            getService().unregisterTaskStackListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public Rect getTaskBounds(int taskId) {
        try {
            return getService().getTaskBounds(taskId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers remote animations for a display.
     * @hide
     */
    public void registerRemoteAnimationsForDisplay(
            int displayId, RemoteAnimationDefinition definition) {
        try {
            getService().registerRemoteAnimationsForDisplay(displayId, definition);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public boolean isInLockTaskMode() {
        try {
            return getService().isInLockTaskMode();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Removes task by a given taskId */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_TASKS)
    public boolean removeTask(int taskId) {
        try {
            return getService().removeTask(taskId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Detaches the navigation bar from the app it was attached to during a transition.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS)
    public void detachNavigationBarFromApp(@NonNull IBinder transition) {
        try {
            getService().detachNavigationBarFromApp(transition);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Update the list of packages allowed in lock task mode. */
    @RequiresPermission(android.Manifest.permission.UPDATE_LOCK_TASK_PACKAGES)
    public void updateLockTaskPackages(@NonNull Context context, @NonNull String[] packages) {
        try {
            getService().updateLockTaskPackages(context.getUserId(), packages);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Information you can retrieve about a root task in the system.
     * @hide
     */
    public static class RootTaskInfo extends TaskInfo implements Parcelable {
        // TODO(b/148895075): Move some of the fields to TaskInfo.
        public Rect bounds = new Rect();
        public int[] childTaskIds;
        public String[] childTaskNames;
        public Rect[] childTaskBounds;
        public int[] childTaskUserIds;
        public boolean visible;
        // Index of the stack in the display's stack list, can be used for comparison of stack order
        public int position;

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeTypedObject(bounds, flags);
            dest.writeIntArray(childTaskIds);
            dest.writeStringArray(childTaskNames);
            dest.writeTypedArray(childTaskBounds, flags);
            dest.writeIntArray(childTaskUserIds);
            dest.writeInt(visible ? 1 : 0);
            dest.writeInt(position);
            super.writeToParcel(dest, flags);
        }

        @Override
        void readFromParcel(Parcel source) {
            bounds = source.readTypedObject(Rect.CREATOR);
            childTaskIds = source.createIntArray();
            childTaskNames = source.createStringArray();
            childTaskBounds = source.createTypedArray(Rect.CREATOR);
            childTaskUserIds = source.createIntArray();
            visible = source.readInt() > 0;
            position = source.readInt();
            super.readFromParcel(source);
        }

        public static final @NonNull Creator<RootTaskInfo> CREATOR = new Creator<>() {
            @Override
            public RootTaskInfo createFromParcel(Parcel source) {
                return new RootTaskInfo(source);
            }

            @Override
            public RootTaskInfo[] newArray(int size) {
                return new RootTaskInfo[size];
            }
        };

        public RootTaskInfo() {
        }

        private RootTaskInfo(Parcel source) {
            readFromParcel(source);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(256);
            sb.append("RootTask id="); sb.append(taskId);
            sb.append(" bounds="); sb.append(bounds.toShortString());
            sb.append(" displayId="); sb.append(displayId);
            sb.append(" userId="); sb.append(userId);
            sb.append("\n");

            sb.append(" configuration="); sb.append(configuration);
            sb.append("\n");

            for (int i = 0; i < childTaskIds.length; ++i) {
                sb.append("  taskId="); sb.append(childTaskIds[i]);
                sb.append(": "); sb.append(childTaskNames[i]);
                if (childTaskBounds != null) {
                    sb.append(" bounds="); sb.append(childTaskBounds[i].toShortString());
                }
                sb.append(" userId=").append(childTaskUserIds[i]);
                sb.append(" visible=").append(visible);
                if (topActivity != null) {
                    sb.append(" topActivity=").append(topActivity);
                }
                sb.append("\n");
            }
            return sb.toString();
        }
    }
}
