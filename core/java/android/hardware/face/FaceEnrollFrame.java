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

package android.hardware.face;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.face.FaceEnrollStages.FaceEnrollStage;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Data model for a frame captured during face enrollment.
 *
 * @hide
 */
public final class FaceEnrollFrame implements Parcelable {
    @Nullable private final FaceEnrollCell mCell;
    @FaceEnrollStage private final int mStage;
    @NonNull private final FaceDataFrame mData;

    /**
     * Data model for a frame captured during face enrollment.
     *
     * @param cell The cell captured during this frame of enrollment, if any.
     * @param stage An integer representing the current stage of enrollment.
     * @param data Information about the current frame.
     */
    public FaceEnrollFrame(
            @Nullable FaceEnrollCell cell,
            @FaceEnrollStage int stage,
            @NonNull FaceDataFrame data) {
        mCell = cell;
        mStage = stage;
        mData = data;
    }

    /**
     * @return The cell captured during this frame of enrollment, if any.
     */
    @Nullable
    public FaceEnrollCell getCell() {
        return mCell;
    }

    /**
     * @return An integer representing the current stage of enrollment.
     */
    @FaceEnrollStage
    public int getStage() {
        return mStage;
    }

    /**
     * @return Information about the current frame.
     */
    @NonNull
    public FaceDataFrame getData() {
        return mData;
    }

    private FaceEnrollFrame(@NonNull Parcel source) {
        mCell = source.readParcelable(FaceEnrollCell.class.getClassLoader(), android.hardware.face.FaceEnrollCell.class);
        mStage = source.readInt();
        mData = source.readParcelable(FaceDataFrame.class.getClassLoader(), android.hardware.face.FaceDataFrame.class);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mCell, flags);
        dest.writeInt(mStage);
        dest.writeParcelable(mData, flags);
    }

    public static final Creator<FaceEnrollFrame> CREATOR = new Creator<FaceEnrollFrame>() {
        @Override
        public FaceEnrollFrame createFromParcel(Parcel source) {
            return new FaceEnrollFrame(source);
        }

        @Override
        public FaceEnrollFrame[] newArray(int size) {
            return new FaceEnrollFrame[size];
        }
    };
}
