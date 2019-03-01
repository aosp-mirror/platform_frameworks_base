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

package android.service.contentcapture;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A container class for data taken from a snapshot of an activity.
 *
 * @hide
 */
@SystemApi
@TestApi
public final class SnapshotData implements Parcelable {

    private final @NonNull Bundle mAssistData;
    private final @NonNull AssistStructure mAssistStructure;
    private final @Nullable AssistContent mAssistContent;

    /**
     * Creates a new instance.
     *
     * @hide
     */
    public SnapshotData(@NonNull Bundle assistData, @NonNull AssistStructure assistStructure,
            @Nullable AssistContent assistContent) {
        mAssistData = assistData;
        mAssistStructure = assistStructure;
        mAssistContent = assistContent;
    }

    SnapshotData(@NonNull Parcel parcel) {
        mAssistData = parcel.readBundle();
        mAssistStructure = parcel.readParcelable(null);
        mAssistContent = parcel.readParcelable(null);
    }

    /**
     * Returns the assist data for this snapshot.
     */
    @NonNull
    public Bundle getAssistData() {
        return mAssistData;
    }

    /**
     * Returns the assist structure for this snapshot.
     */
    @NonNull
    public AssistStructure getAssistStructure() {
        return mAssistStructure;
    }

    /**
     * Returns the assist context for this snapshot.
     */
    @Nullable
    public AssistContent getAssistContent() {
        return mAssistContent;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeBundle(mAssistData);
        parcel.writeParcelable(mAssistStructure, flags);
        parcel.writeParcelable(mAssistContent, flags);
    }

    public static final Creator<SnapshotData> CREATOR =
            new Creator<SnapshotData>() {

        @Override
        @NonNull
        public SnapshotData createFromParcel(@NonNull Parcel parcel) {
            return new SnapshotData(parcel);
        }

        @Override
        @NonNull
        public SnapshotData[] newArray(int size) {
            return new SnapshotData[size];
        }
    };
}
