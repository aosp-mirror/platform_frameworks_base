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

package android.window;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.SurfaceControl;

/**
 * Data object for the TaskFragment info provided when a TaskFragment is presented to an organizer.
 * @hide
 */
public final class TaskFragmentAppearedInfo implements Parcelable {

    @NonNull
    private final TaskFragmentInfo mTaskFragmentInfo;

    @NonNull
    private final SurfaceControl mLeash;

    public TaskFragmentAppearedInfo(
            @NonNull TaskFragmentInfo taskFragmentInfo, @NonNull SurfaceControl leash) {
        mTaskFragmentInfo = taskFragmentInfo;
        mLeash = leash;
    }

    public TaskFragmentInfo getTaskFragmentInfo() {
        return mTaskFragmentInfo;
    }

    public SurfaceControl getLeash() {
        return mLeash;
    }

    private TaskFragmentAppearedInfo(Parcel in) {
        mTaskFragmentInfo = in.readTypedObject(TaskFragmentInfo.CREATOR);
        mLeash = in.readTypedObject(SurfaceControl.CREATOR);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mTaskFragmentInfo, flags);
        dest.writeTypedObject(mLeash, flags);
    }

    @NonNull
    public static final Creator<TaskFragmentAppearedInfo> CREATOR =
            new Creator<TaskFragmentAppearedInfo>() {
                @Override
                public TaskFragmentAppearedInfo createFromParcel(Parcel in) {
                    return new TaskFragmentAppearedInfo(in);
                }

                @Override
                public TaskFragmentAppearedInfo[] newArray(int size) {
                    return new TaskFragmentAppearedInfo[size];
                }
            };

    @Override
    public String toString() {
        return "TaskFragmentAppearedInfo{"
                + " taskFragmentInfo=" + mTaskFragmentInfo
                + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
