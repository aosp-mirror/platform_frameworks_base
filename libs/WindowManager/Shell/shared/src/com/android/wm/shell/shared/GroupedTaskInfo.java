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
import android.app.ActivityManager.RecentTaskInfo;
import android.app.TaskInfo;
import android.app.WindowConfiguration;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.shared.split.SplitBounds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Simple container for recent tasks which should be presented as a single task within the
 * Overview UI.
 */
public class GroupedTaskInfo implements Parcelable {

    public static final int TYPE_FULLSCREEN = 1;
    public static final int TYPE_SPLIT = 2;
    public static final int TYPE_FREEFORM = 3;

    @IntDef(prefix = {"TYPE_"}, value = {
            TYPE_FULLSCREEN,
            TYPE_SPLIT,
            TYPE_FREEFORM
    })
    public @interface GroupType {}

    /**
     * The type of this particular task info, can be one of TYPE_FULLSCREEN, TYPE_SPLIT or
     * TYPE_FREEFORM.
     */
    @GroupType
    protected final int mType;

    /**
     * The list of tasks associated with this single recent task info.
     * TYPE_FULLSCREEN: Contains the stack of tasks associated with a single "task" in overview
     * TYPE_SPLIT: Contains the two split roots of each side
     * TYPE_FREEFORM: Contains the set of tasks currently in freeform mode
     */
    @NonNull
    protected final List<TaskInfo> mTasks;

    /**
     * Only set for TYPE_SPLIT.
     *
     * Information about the split bounds.
     */
    @Nullable
    protected final SplitBounds mSplitBounds;

    /**
     * Only set for TYPE_FREEFORM.
     *
     * TODO(b/348332802): move isMinimized inside each Task object instead once we have a
     *  replacement for RecentTaskInfo
     */
    @Nullable
    protected final int[] mMinimizedTaskIds;

    /**
     * Create new for a stack of fullscreen tasks
     */
    public static GroupedTaskInfo forFullscreenTasks(@NonNull TaskInfo task) {
        return new GroupedTaskInfo(List.of(task), null, TYPE_FULLSCREEN,
                null /* minimizedFreeformTasks */);
    }

    /**
     * Create new for a pair of tasks in split screen
     */
    public static GroupedTaskInfo forSplitTasks(@NonNull TaskInfo task1,
                    @NonNull TaskInfo task2, @Nullable SplitBounds splitBounds) {
        return new GroupedTaskInfo(List.of(task1, task2), splitBounds, TYPE_SPLIT,
                null /* minimizedFreeformTasks */);
    }

    /**
     * Create new for a group of freeform tasks
     */
    public static GroupedTaskInfo forFreeformTasks(
                    @NonNull List<TaskInfo> tasks,
                    @NonNull Set<Integer> minimizedFreeformTasks) {
        return new GroupedTaskInfo(tasks, null /* splitBounds */, TYPE_FREEFORM,
                minimizedFreeformTasks.stream().mapToInt(i -> i).toArray());
    }

    private GroupedTaskInfo(
            @NonNull List<TaskInfo> tasks,
            @Nullable SplitBounds splitBounds,
            @GroupType int type,
            @Nullable int[] minimizedFreeformTaskIds) {
        mTasks = tasks;
        mSplitBounds = splitBounds;
        mType = type;
        mMinimizedTaskIds = minimizedFreeformTaskIds;
        ensureAllMinimizedIdsPresent(tasks, minimizedFreeformTaskIds);
    }

    private void ensureAllMinimizedIdsPresent(
            @NonNull List<TaskInfo> tasks,
            @Nullable int[] minimizedFreeformTaskIds) {
        if (minimizedFreeformTaskIds == null) {
            return;
        }
        if (!Arrays.stream(minimizedFreeformTaskIds).allMatch(
                taskId -> tasks.stream().anyMatch(task -> task.taskId == taskId))) {
            throw new IllegalArgumentException("Minimized task IDs contain non-existent Task ID.");
        }
    }

    protected GroupedTaskInfo(@NonNull Parcel parcel) {
        mTasks = new ArrayList();
        final int numTasks = parcel.readInt();
        for (int i = 0; i < numTasks; i++) {
            mTasks.add(new TaskInfo(parcel));
        }
        mSplitBounds = parcel.readTypedObject(SplitBounds.CREATOR);
        mType = parcel.readInt();
        mMinimizedTaskIds = parcel.createIntArray();
    }

    /**
     * Get primary {@link RecentTaskInfo}
     */
    @NonNull
    public TaskInfo getTaskInfo1() {
        return mTasks.getFirst();
    }

    /**
     * Get secondary {@link RecentTaskInfo}.
     *
     * Used in split screen.
     */
    @Nullable
    public TaskInfo getTaskInfo2() {
        if (mTasks.size() > 1) {
            return mTasks.get(1);
        }
        return null;
    }

    /**
     * @return The task info for the task in this group with the given {@code taskId}.
     */
    @Nullable
    public TaskInfo getTaskById(int taskId) {
        return mTasks.stream()
                .filter(task -> task.taskId == taskId)
                .findFirst().orElse(null);
    }

    /**
     * Get all {@link RecentTaskInfo}s grouped together.
     */
    @NonNull
    public List<TaskInfo> getTaskInfoList() {
        return mTasks;
    }

    /**
     * @return Whether this grouped task contains a task with the given {@code taskId}.
     */
    public boolean containsTask(int taskId) {
        return mTasks.stream()
                .anyMatch((task -> task.taskId == taskId));
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

    @Nullable
    public int[] getMinimizedTaskIds() {
        return mMinimizedTaskIds;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof GroupedTaskInfo)) {
            return false;
        }
        GroupedTaskInfo other = (GroupedTaskInfo) obj;
        return mType == other.mType
                && Objects.equals(mTasks, other.mTasks)
                && Objects.equals(mSplitBounds, other.mSplitBounds)
                && Arrays.equals(mMinimizedTaskIds, other.mMinimizedTaskIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mTasks, mSplitBounds, Arrays.hashCode(mMinimizedTaskIds));
    }

    @Override
    public String toString() {
        StringBuilder taskString = new StringBuilder();
        for (int i = 0; i < mTasks.size(); i++) {
            if (i == 0) {
                taskString.append("Task");
            } else {
                taskString.append(", Task");
            }
            taskString.append(i + 1).append(": ").append(getTaskInfo(mTasks.get(i)));
        }
        if (mSplitBounds != null) {
            taskString.append(", SplitBounds: ").append(mSplitBounds);
        }
        taskString.append(", Type=");
        switch (mType) {
            case TYPE_FULLSCREEN:
                taskString.append("TYPE_FULLSCREEN");
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

    private String getTaskInfo(TaskInfo taskInfo) {
        if (taskInfo == null) {
            return null;
        }
        return "id=" + taskInfo.taskId
                + " baseIntent=" +
                        (taskInfo.baseIntent != null && taskInfo.baseIntent.getComponent() != null
                                ? taskInfo.baseIntent.getComponent().flattenToString()
                                : "null")
                + " winMode=" + WindowConfiguration.windowingModeToString(
                        taskInfo.getWindowingMode());
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        // We don't use the parcel list methods because we want to only write the TaskInfo state
        // and not the subclasses (Recents/RunningTaskInfo) whose fields are all deprecated
        parcel.writeInt(mTasks.size());
        for (int i = 0; i < mTasks.size(); i++) {
            mTasks.get(i).writeTaskToParcel(parcel, flags);
        }
        parcel.writeTypedObject(mSplitBounds, flags);
        parcel.writeInt(mType);
        parcel.writeIntArray(mMinimizedTaskIds);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<GroupedTaskInfo> CREATOR = new Creator() {
        @Override
        public GroupedTaskInfo createFromParcel(Parcel in) {
            return new GroupedTaskInfo(in);
        }

        @Override
        public GroupedTaskInfo[] newArray(int size) {
            return new GroupedTaskInfo[size];
        }
    };
}
