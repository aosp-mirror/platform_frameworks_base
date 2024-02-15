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

package android.content.pm;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Info about a package's states in Parcelable format.
 * @hide
 */
public class IncrementalStatesInfo implements Parcelable {
    private final boolean mIsLoading;
    private final float mProgress;

    private long mLoadingCompletedTime;

    public IncrementalStatesInfo(boolean isLoading, float progress, long loadingCompletedTime) {
        mIsLoading = isLoading;
        mProgress = progress;
        mLoadingCompletedTime = loadingCompletedTime;
    }

    private IncrementalStatesInfo(Parcel source) {
        mIsLoading = source.readBoolean();
        mProgress = source.readFloat();
        mLoadingCompletedTime = source.readLong();
    }

    public boolean isLoading() {
        return mIsLoading;
    }

    public float getProgress() {
        return mProgress;
    }

    public long getLoadingCompletedTime() {
        return mLoadingCompletedTime;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(mIsLoading);
        dest.writeFloat(mProgress);
        dest.writeLong(mLoadingCompletedTime);
    }

    public static final @android.annotation.NonNull Creator<IncrementalStatesInfo> CREATOR =
            new Creator<IncrementalStatesInfo>() {
                public IncrementalStatesInfo createFromParcel(Parcel source) {
                    return new IncrementalStatesInfo(source);
                }
                public IncrementalStatesInfo[] newArray(int size) {
                    return new IncrementalStatesInfo[size];
                }
            };
}
