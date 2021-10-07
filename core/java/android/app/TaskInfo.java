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

import static android.app.ActivityTaskManager.INVALID_TASK_ID;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Intent;
import android.content.LocusId;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.DisplayCutout;
import android.window.TaskSnapshot;
import android.window.WindowContainerToken;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Stores information about a particular Task.
 */
public class TaskInfo {
    private static final String TAG = "TaskInfo";

    /**
     * The id of the user the task was running as if this is a leaf task. The id of the current
     * running user of the system otherwise.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public int userId;

    /**
     * The identifier for this task.
     */
    public int taskId;

    /**
     * Whether or not this task has any running activities.
     */
    public boolean isRunning;

    /**
     * The base intent of the task (generally the intent that launched the task). This intent can
     * be used to relaunch the task (if it is no longer running) or brought to the front if it is.
     */
    @NonNull
    public Intent baseIntent;

    /**
     * The component of the first activity in the task, can be considered the "application" of this
     * task.
     */
    @Nullable
    public ComponentName baseActivity;

    /**
     * The component of the top activity in the task, currently showing to the user.
     */
    @Nullable
    public ComponentName topActivity;

    /**
     * The component of the target activity if this task was started from an activity alias.
     * Otherwise, this is null.
     */
    @Nullable
    public ComponentName origActivity;

    /**
     * The component of the activity that started this task (may be the component of the activity
     * alias).
     * @hide
     */
    @Nullable
    public ComponentName realActivity;

    /**
     * The number of activities in this task (including running).
     */
    public int numActivities;

    /**
     * The last time this task was active since boot (including time spent in sleep).
     * @hide
     */
    @UnsupportedAppUsage
    public long lastActiveTime;

    /**
     * The id of the display this task is associated with.
     * @hide
     */
    public int displayId;

    /**
     * The recent activity values for the highest activity in the stack to have set the values.
     * {@link Activity#setTaskDescription(android.app.ActivityManager.TaskDescription)}.
     */
    @Nullable
    public ActivityManager.TaskDescription taskDescription;

    /**
     * The locusId of the task.
     * @hide
     */
    @Nullable
    public LocusId mTopActivityLocusId;

    /**
     * True if the task can go in the split-screen primary stack.
     * @hide
     */
    @UnsupportedAppUsage
    public boolean supportsSplitScreenMultiWindow;

    /**
     * Whether this task supports multi windowing modes based on the device settings and the
     * root activity resizability and configuration.
     * @hide
     */
    public boolean supportsMultiWindow;

    /**
     * The resize mode of the task. See {@link ActivityInfo#resizeMode}.
     * @hide
     */
    @UnsupportedAppUsage
    public int resizeMode;

    /**
     * The current configuration of the task.
     * @hide
     */
    @NonNull
    @UnsupportedAppUsage
    public final Configuration configuration = new Configuration();

    /**
     * Used as an opaque identifier for this task.
     * @hide
     */
    @NonNull
    public WindowContainerToken token;

    /**
     * The PictureInPictureParams for the Task, if set.
     * @hide
     */
    @Nullable
    public PictureInPictureParams pictureInPictureParams;

    /**
     * The {@link Rect} copied from {@link DisplayCutout#getSafeInsets()} if the cutout is not of
     * (LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES, LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS),
     * {@code null} otherwise.
     * @hide
     */
    @Nullable
    public Rect displayCutoutInsets;

    /**
     * The activity type of the top activity in this task.
     * @hide
     */
    public @WindowConfiguration.ActivityType int topActivityType;

    /**
     * The {@link ActivityInfo} of the top activity in this task.
     * @hide
     */
    @Nullable
    public ActivityInfo topActivityInfo;

    /**
     * Whether the direct top activity is in size compat mode on foreground.
     * @hide
     */
    public boolean topActivityInSizeCompat;

    /**
     * Whether this task is resizable. Unlike {@link #resizeMode} (which is what the top activity
     * supports), this is what the system actually uses for resizability based on other policy and
     * developer options.
     * @hide
     */
    public boolean isResizeable;

    /**
     * Relative position of the task's top left corner in the parent container.
     * @hide
     */
    public Point positionInParent;

    /**
     * The launch cookies associated with activities in this task if any.
     * @see ActivityOptions#setLaunchCookie(IBinder)
     * @hide
     */
    public ArrayList<IBinder> launchCookies = new ArrayList<>();

    /**
     * The identifier of the parent task that is created by organizer, otherwise
     * {@link ActivityTaskManager#INVALID_TASK_ID}.
     * @hide
     */
    public int parentTaskId;

    /**
     * Whether this task is focused.
     * @hide
     */
    public boolean isFocused;

    /**
     * Whether this task is visible.
     * @hide
     */
    public boolean isVisible;

    TaskInfo() {
        // Do nothing
    }

    private TaskInfo(Parcel source) {
        readFromParcel(source);
    }

    /**
     * @param isLowResolution
     * @return
     * @hide
     */
    public TaskSnapshot getTaskSnapshot(boolean isLowResolution) {
        try {
            return ActivityTaskManager.getService().getTaskSnapshot(taskId, isLowResolution);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get task snapshot, taskId=" + taskId, e);
            return null;
        }
    }

    /** @hide */
    @NonNull
    @TestApi
    public WindowContainerToken getToken() {
        return token;
    }

    /** @hide */
    @NonNull
    @TestApi
    public Configuration getConfiguration() {
        return configuration;
    }

    /** @hide */
    @Nullable
    @TestApi
    public PictureInPictureParams getPictureInPictureParams() {
        return pictureInPictureParams;
    }

    /** @hide */
    @WindowConfiguration.WindowingMode
    public int getWindowingMode() {
        return configuration.windowConfiguration.getWindowingMode();
    }

    /** @hide */
    @WindowConfiguration.ActivityType
    public int getActivityType() {
        return configuration.windowConfiguration.getActivityType();
    }

    /** @hide */
    public void addLaunchCookie(IBinder cookie) {
        if (cookie == null || launchCookies.contains(cookie)) return;
        launchCookies.add(cookie);
    }

    /**
     * @return {@code true} if this task contains the launch cookie.
     * @hide
     */
    @TestApi
    public boolean containsLaunchCookie(@NonNull IBinder cookie) {
        return launchCookies.contains(cookie);
    }

    /**
     * @return The parent task id of this task.
     * @hide
     */
    @TestApi
    public int getParentTaskId() {
        return parentTaskId;
    }

    /** @hide */
    @TestApi
    public boolean hasParentTask() {
        return parentTaskId != INVALID_TASK_ID;
    }

    /**
      * Returns {@code true} if parameters that are important for task organizers have changed
      * and {@link com.android.server.wm.TaskOrginizerController} needs to notify listeners
      * about that.
      * @hide
      */
    public boolean equalsForTaskOrganizer(@Nullable TaskInfo that) {
        if (that == null) {
            return false;
        }
        return topActivityType == that.topActivityType
                && isResizeable == that.isResizeable
                && supportsMultiWindow == that.supportsMultiWindow
                && Objects.equals(positionInParent, that.positionInParent)
                && Objects.equals(pictureInPictureParams, that.pictureInPictureParams)
                && Objects.equals(displayCutoutInsets, that.displayCutoutInsets)
                && getWindowingMode() == that.getWindowingMode()
                && Objects.equals(taskDescription, that.taskDescription)
                && isFocused == that.isFocused
                && isVisible == that.isVisible;
    }

    /**
     * @return {@code true} if parameters that are important for size compat have changed.
     * @hide
     */
    public boolean equalsForSizeCompat(@Nullable TaskInfo that) {
        if (that == null) {
            return false;
        }
        return displayId == that.displayId
                && taskId == that.taskId
                && topActivityInSizeCompat == that.topActivityInSizeCompat
                // Bounds are important if top activity is in size compat
                && (!topActivityInSizeCompat || configuration.windowConfiguration.getBounds()
                    .equals(that.configuration.windowConfiguration.getBounds()))
                && (!topActivityInSizeCompat || configuration.getLayoutDirection()
                    == that.configuration.getLayoutDirection())
                && (!topActivityInSizeCompat || isVisible == that.isVisible);
    }

    /**
     * Reads the TaskInfo from a parcel.
     */
    void readFromParcel(Parcel source) {
        userId = source.readInt();
        taskId = source.readInt();
        displayId = source.readInt();
        isRunning = source.readBoolean();
        baseIntent = source.readTypedObject(Intent.CREATOR);
        baseActivity = ComponentName.readFromParcel(source);
        topActivity = ComponentName.readFromParcel(source);
        origActivity = ComponentName.readFromParcel(source);
        realActivity = ComponentName.readFromParcel(source);

        numActivities = source.readInt();
        lastActiveTime = source.readLong();

        taskDescription = source.readTypedObject(ActivityManager.TaskDescription.CREATOR);
        supportsSplitScreenMultiWindow = source.readBoolean();
        supportsMultiWindow = source.readBoolean();
        resizeMode = source.readInt();
        configuration.readFromParcel(source);
        token = WindowContainerToken.CREATOR.createFromParcel(source);
        topActivityType = source.readInt();
        pictureInPictureParams = source.readTypedObject(PictureInPictureParams.CREATOR);
        displayCutoutInsets = source.readTypedObject(Rect.CREATOR);
        topActivityInfo = source.readTypedObject(ActivityInfo.CREATOR);
        isResizeable = source.readBoolean();
        source.readBinderList(launchCookies);
        positionInParent = source.readTypedObject(Point.CREATOR);
        parentTaskId = source.readInt();
        isFocused = source.readBoolean();
        isVisible = source.readBoolean();
        topActivityInSizeCompat = source.readBoolean();
        mTopActivityLocusId = source.readTypedObject(LocusId.CREATOR);
    }

    /**
     * Writes the TaskInfo to a parcel.
     */
    void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(userId);
        dest.writeInt(taskId);
        dest.writeInt(displayId);
        dest.writeBoolean(isRunning);
        dest.writeTypedObject(baseIntent, 0);

        ComponentName.writeToParcel(baseActivity, dest);
        ComponentName.writeToParcel(topActivity, dest);
        ComponentName.writeToParcel(origActivity, dest);
        ComponentName.writeToParcel(realActivity, dest);

        dest.writeInt(numActivities);
        dest.writeLong(lastActiveTime);

        dest.writeTypedObject(taskDescription, flags);
        dest.writeBoolean(supportsSplitScreenMultiWindow);
        dest.writeBoolean(supportsMultiWindow);
        dest.writeInt(resizeMode);
        configuration.writeToParcel(dest, flags);
        token.writeToParcel(dest, flags);
        dest.writeInt(topActivityType);
        dest.writeTypedObject(pictureInPictureParams, flags);
        dest.writeTypedObject(displayCutoutInsets, flags);
        dest.writeTypedObject(topActivityInfo, flags);
        dest.writeBoolean(isResizeable);
        dest.writeBinderList(launchCookies);
        dest.writeTypedObject(positionInParent, flags);
        dest.writeInt(parentTaskId);
        dest.writeBoolean(isFocused);
        dest.writeBoolean(isVisible);
        dest.writeBoolean(topActivityInSizeCompat);
        dest.writeTypedObject(mTopActivityLocusId, flags);
    }

    @Override
    public String toString() {
        return "TaskInfo{userId=" + userId + " taskId=" + taskId
                + " displayId=" + displayId
                + " isRunning=" + isRunning
                + " baseIntent=" + baseIntent + " baseActivity=" + baseActivity
                + " topActivity=" + topActivity + " origActivity=" + origActivity
                + " realActivity=" + realActivity
                + " numActivities=" + numActivities
                + " lastActiveTime=" + lastActiveTime
                + " supportsSplitScreenMultiWindow=" + supportsSplitScreenMultiWindow
                + " supportsMultiWindow=" + supportsMultiWindow
                + " resizeMode=" + resizeMode
                + " isResizeable=" + isResizeable
                + " token=" + token
                + " topActivityType=" + topActivityType
                + " pictureInPictureParams=" + pictureInPictureParams
                + " displayCutoutSafeInsets=" + displayCutoutInsets
                + " topActivityInfo=" + topActivityInfo
                + " launchCookies=" + launchCookies
                + " positionInParent=" + positionInParent
                + " parentTaskId=" + parentTaskId
                + " isFocused=" + isFocused
                + " isVisible=" + isVisible
                + " topActivityInSizeCompat=" + topActivityInSizeCompat
                + " locusId= " + mTopActivityLocusId
                + "}";
    }
}
