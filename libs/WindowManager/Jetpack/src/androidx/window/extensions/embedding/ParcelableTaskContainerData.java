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

package androidx.window.extensions.embedding;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * This class holds the Parcelable data of a {@link TaskContainer}.
 */
class ParcelableTaskContainerData implements Parcelable {

    /**
     * A reference to the target {@link TaskContainer} that owns the data. This will not be
     * parcelled and will be {@code null} when the data is created from a parcel.
     */
    @Nullable
    final TaskContainer mTaskContainer;

    /**
     * The unique task id.
     */
    final int mTaskId;

    /**
     * The parcelable data of the active TaskFragmentContainers in this Task.
     * Note that this will only be populated before parcelling, and will not be copied when
     * making a new instance copy.
     */
    @NonNull
    private final List<ParcelableTaskFragmentContainerData>
            mParcelableTaskFragmentContainerDataList = new ArrayList<>();

    /**
     * The parcelable data of the SplitContainers in this Task.
     * Note that this will only be populated before parcelling, and will not be copied when
     * making a new instance copy.
     */
    @NonNull
    private final List<ParcelableSplitContainerData> mParcelableSplitContainerDataList =
            new ArrayList<>();

    ParcelableTaskContainerData(int taskId, @NonNull TaskContainer taskContainer) {
        if (taskId == INVALID_TASK_ID) {
            throw new IllegalArgumentException("Invalid Task id");
        }

        mTaskId = taskId;
        mTaskContainer = taskContainer;
    }

    ParcelableTaskContainerData(@NonNull ParcelableTaskContainerData data,
            @NonNull TaskContainer taskContainer) {
        mTaskId = data.mTaskId;
        mTaskContainer = taskContainer;
    }

    private ParcelableTaskContainerData(Parcel in) {
        mTaskId = in.readInt();
        mTaskContainer = null;
        in.readParcelableList(mParcelableTaskFragmentContainerDataList,
                ParcelableTaskFragmentContainerData.class.getClassLoader(),
                ParcelableTaskFragmentContainerData.class);
        in.readParcelableList(mParcelableSplitContainerDataList,
                ParcelableSplitContainerData.class.getClassLoader(),
                ParcelableSplitContainerData.class);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mTaskId);
        dest.writeParcelableList(getParcelableTaskFragmentContainerDataList(), flags);
        dest.writeParcelableList(getParcelableSplitContainerDataList(), flags);
    }

    @NonNull
    List<? extends ParcelableTaskFragmentContainerData>
            getParcelableTaskFragmentContainerDataList() {
        return mTaskContainer != null ? mTaskContainer.getParcelableTaskFragmentContainerDataList()
                : mParcelableTaskFragmentContainerDataList;
    }

    @NonNull
    List<? extends ParcelableSplitContainerData> getParcelableSplitContainerDataList() {
        return mTaskContainer != null ? mTaskContainer.getParcelableSplitContainerDataList()
                : mParcelableSplitContainerDataList;
    }

    @NonNull
    List<String> getSplitRuleTags() {
        final List<String> tags = new ArrayList<>();
        for (ParcelableSplitContainerData data : getParcelableSplitContainerDataList()) {
            tags.add(data.mSplitRuleTag);
        }
        return tags;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ParcelableTaskContainerData> CREATOR = new Creator<>() {
        @Override
        public ParcelableTaskContainerData createFromParcel(Parcel in) {
            return new ParcelableTaskContainerData(in);
        }

        @Override
        public ParcelableTaskContainerData[] newArray(int size) {
            return new ParcelableTaskContainerData[size];
        }
    };
}
