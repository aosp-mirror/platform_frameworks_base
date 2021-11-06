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

package com.android.wm.shell.util;

import android.app.ActivityManager;
import android.app.WindowConfiguration;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Simple container for recent tasks.  May contain either a single or pair of tasks.
 */
public class GroupedRecentTaskInfo implements Parcelable {
    public @NonNull ActivityManager.RecentTaskInfo mTaskInfo1;
    public @Nullable ActivityManager.RecentTaskInfo mTaskInfo2;

    public GroupedRecentTaskInfo(@NonNull ActivityManager.RecentTaskInfo task1) {
        this(task1, null);
    }

    public GroupedRecentTaskInfo(@NonNull ActivityManager.RecentTaskInfo task1,
            @Nullable ActivityManager.RecentTaskInfo task2) {
        mTaskInfo1 = task1;
        mTaskInfo2 = task2;
    }

    GroupedRecentTaskInfo(Parcel parcel) {
        mTaskInfo1 = parcel.readTypedObject(ActivityManager.RecentTaskInfo.CREATOR);
        mTaskInfo2 = parcel.readTypedObject(ActivityManager.RecentTaskInfo.CREATOR);
    }

    @Override
    public String toString() {
        return "Task1: " + getTaskInfo(mTaskInfo1) + ", Task2: " + getTaskInfo(mTaskInfo2);
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
        parcel.writeTypedObject(mTaskInfo1, flags);
        parcel.writeTypedObject(mTaskInfo2, flags);
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