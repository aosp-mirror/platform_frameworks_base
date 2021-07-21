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

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.app.ActivityManager.RunningTaskInfo;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.SurfaceControl;

/**
 * Data object for the task info provided when a task is presented to an organizer.
 * @hide
 */
@TestApi
public final class TaskAppearedInfo implements Parcelable {

    @NonNull
    private final RunningTaskInfo mTaskInfo;

    @NonNull
    private final SurfaceControl mLeash;

    @NonNull
    public static final Creator<TaskAppearedInfo> CREATOR = new Creator<TaskAppearedInfo>() {
        @Override
        public TaskAppearedInfo createFromParcel(Parcel source) {
            final RunningTaskInfo taskInfo = source.readTypedObject(RunningTaskInfo.CREATOR);
            final SurfaceControl leash = source.readTypedObject(SurfaceControl.CREATOR);
            return new TaskAppearedInfo(taskInfo, leash);
        }

        @Override
        public TaskAppearedInfo[] newArray(int size) {
            return new TaskAppearedInfo[size];
        }

    };

    public TaskAppearedInfo(@NonNull RunningTaskInfo taskInfo, @NonNull SurfaceControl leash) {
        mTaskInfo = taskInfo;
        mLeash = leash;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mTaskInfo, flags);
        dest.writeTypedObject(mLeash, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @return the task info.
     */
    @NonNull
    public RunningTaskInfo getTaskInfo() {
        return mTaskInfo;
    }

    /**
     * @return the leash for the task.
     */
    @NonNull
    public SurfaceControl getLeash() {
        return mLeash;
    }
}
