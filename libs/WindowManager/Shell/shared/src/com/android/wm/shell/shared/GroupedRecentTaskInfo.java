/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.shared;

import android.annotation.IntDef;
import android.app.ActivityManager;
import android.app.WindowConfiguration;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.shared.split.SplitBounds;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Simple container for recent tasks.  May contain either a single or pair of tasks.
 */
public class GroupedRecentTaskInfo implements Parcelable {

    public static final int TYPE_SINGLE = 1;
    public static final int TYPE_SPLIT = 2;
    public static final int TYPE_FREEFORM = 3;

    @IntDef(prefix = {"TYPE_"}, value = {
            TYPE_SINGLE,
            TYPE_SPLIT,
            TYPE_FREEFORM
    })
    public @interface GroupType {}

    @NonNull
    private final ActivityManager.RecentTaskInfo[] mTasks;
    @Nullable
    private final SplitBounds mSplitBounds;
    @GroupType
    private final int mType;
    // TODO(b/348332802): move isMinimized inside each Task object instead once we have a
    //  replacement for RecentTaskInfo
    private final int[] mMinimizedTaskIds;

    /**
     * Create new for a single task
     */
    public static GroupedRecentTaskInfo forSingleTask(
            @NonNull ActivityManager.RecentTaskInfo task) {
        return new GroupedRecentTaskInfo(new ActivityManager.RecentTaskInfo[]{task}, null,
                TYPE_SINGLE, null /* minimizedFreeformTasks */);
    }

    /**
     * Create new for a pair of tasks in split screen
     */
    public static GroupedRecentTaskInfo forSplitTasks(@NonNull ActivityManager.RecentTaskInfo task1,
            @NonNull ActivityManager.RecentTaskInfo task2, @Nullable SplitBounds splitBounds) {
        return new GroupedRecentTaskInfo(new ActivityManager.RecentTaskInfo[]{task1, task2},
                splitBounds, TYPE_SPLIT, null /* minimizedFreeformTasks */);
    }

    /**
     * Create new for a group of freeform tasks
     */
    public static GroupedRecentTaskInfo forFreeformTasks(
            @NonNull ActivityManager.RecentTaskInfo[] tasks,
            @NonNull Set<Integer> minimizedFreeformTasks) {
        return new GroupedRecentTaskInfo(
                tasks,
                null /* splitBounds */,
                TYPE_FREEFORM,
                minimizedFreeformTasks.stream().mapToInt(i -> i).toArray());
    }

    private GroupedRecentTaskInfo(
            @NonNull ActivityManager.RecentTaskInfo[] tasks,
            @Nullable SplitBounds splitBounds,
            @GroupType int type,
            @Nullable int[] minimizedFreeformTaskIds) {
        mTasks = tasks;
        mSplitBounds = splitBounds;
        mType = type;
        mMinimizedTaskIds = minimizedFreeformTaskIds;
        ensureAllMinimizedIdsPresent(tasks, minimizedFreeformTaskIds);
    }

    private static void ensureAllMinimizedIdsPresent(
            @NonNull ActivityManager.RecentTaskInfo[] tasks,
            @Nullable int[] minimizedFreeformTaskIds) {
        if (minimizedFreeformTaskIds == null) {
            return;
        }
        if (!Arrays.stream(minimizedFreeformTaskIds).allMatch(
                taskId -> Arrays.stream(tasks).anyMatch(task -> task.taskId == taskId))) {
            throw new IllegalArgumentException("Minimized task IDs contain non-existent Task ID.");
        }
    }

    GroupedRecentTaskInfo(Parcel parcel) {
        mTasks = parcel.createTypedArray(ActivityManager.RecentTaskInfo.CREATOR);
        mSplitBounds = parcel.readTypedObject(SplitBounds.CREATOR);
        mType = parcel.readInt();
        mMinimizedTaskIds = parcel.createIntArray();
    }

    /**
     * Get primary {@link ActivityManager.RecentTaskInfo}
     */
    @NonNull
    public ActivityManager.RecentTaskInfo getTaskInfo1() {
        return mTasks[0];
    }

    /**
     * Get secondary {@link ActivityManager.RecentTaskInfo}.
     *
     * Used in split screen.
     */
    @Nullable
    public ActivityManager.RecentTaskInfo getTaskInfo2() {
        if (mTasks.length > 1) {
            return mTasks[1];
        }
        return null;
    }

    /**
     * Get all {@link ActivityManager.RecentTaskInfo}s grouped together.
     */
    @NonNull
    public List<ActivityManager.RecentTaskInfo> getTaskInfoList() {
        return Arrays.asList(mTasks);
    }

    /**
     * Return {@link SplitBounds} if this is a split screen entry or {@code null}
     */
    @Nullable
    public SplitBounds getSplitBounds() {
        return mSplitBounds;
    }

    /**
     * Get type of this recents entry. One of {@link GroupType}
     */
    @GroupType
    public int getType() {
        return mType;
    }

    public int[] getMinimizedTaskIds() {
        return mMinimizedTaskIds;
    }

    @Override
    public String toString() {
        StringBuilder taskString = new StringBuilder();
        for (int i = 0; i < mTasks.length; i++) {
            if (i == 0) {
                taskString.append("Task");
            } else {
                taskString.append(", Task");
            }
            taskString.append(i + 1).append(": ").append(getTaskInfo(mTasks[i]));
        }
        if (mSplitBounds != null) {
            taskString.append(", SplitBounds: ").append(mSplitBounds);
        }
        taskString.append(", Type=");
        switch (mType) {
            case TYPE_SINGLE:
                taskString.append("TYPE_SINGLE");
                break;
            case TYPE_SPLIT:
                taskString.append("TYPE_SPLIT");
                break;
            case TYPE_FREEFORM:
                taskString.append("TYPE_FREEFORM");
                break;
        }
        taskString.append(", Minimized Task IDs: ");
        taskString.append(Arrays.toString(mMinimizedTaskIds));
        return taskString.toString();
    }

    private String getTaskInfo(ActivityManager.RecentTaskInfo taskInfo) {
        if (taskInfo == null) {
            return null;
        }
        return "id=" + taskInfo.taskId
                + " baseIntent=" + (taskInfo.baseIntent != null
                        ? taskInfo.baseIntent.getComponent()
                        : "null")
                + " winMode=" + WindowConfiguration.windowingModeToString(
                        taskInfo.getWindowingMode());
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeTypedArray(mTasks, flags);
        parcel.writeTypedObject(mSplitBounds, flags);
        parcel.writeInt(mType);
        parcel.writeIntArray(mMinimizedTaskIds);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Creator<GroupedRecentTaskInfo> CREATOR =
            new Creator<GroupedRecentTaskInfo>() {
        public GroupedRecentTaskInfo createFromParcel(Parcel source) {
            return new GroupedRecentTaskInfo(source);
        }
        public GroupedRecentTaskInfo[] newArray(int size) {
            return new GroupedRecentTaskInfo[size];
        }
    };
}