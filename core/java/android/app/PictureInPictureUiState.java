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

package android.app;

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Used by {@link Activity#onPictureInPictureUiStateChanged(PictureInPictureUiState)}.
 */
public final class PictureInPictureUiState implements Parcelable {

    private boolean mIsStashed;

    /** {@hide} */
    PictureInPictureUiState(Parcel in) {
        mIsStashed = in.readBoolean();
    }

    /** {@hide} */
    @TestApi
    public PictureInPictureUiState(boolean isStashed) {
        mIsStashed = isStashed;
    }

    /**
     * Returns whether Picture-in-Picture is stashed or not.
     */
    public boolean isStashed() {
        return mIsStashed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PictureInPictureUiState)) return false;
        PictureInPictureUiState that = (PictureInPictureUiState) o;
        return Objects.equals(mIsStashed, that.mIsStashed);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIsStashed);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeBoolean(mIsStashed);
    }

    public static final @android.annotation.NonNull Creator<PictureInPictureUiState> CREATOR =
            new Creator<PictureInPictureUiState>() {
                public PictureInPictureUiState createFromParcel(Parcel in) {
                    return new PictureInPictureUiState(in);
                }
                public PictureInPictureUiState[] newArray(int size) {
                    return new PictureInPictureUiState[size];
                }
            };
}
