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
package android.app.prediction;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * The id for an app prediction session. See {@link AppPredictor}.
 *
 * @hide
 */
@SystemApi
@TestApi
public final class AppPredictionSessionId implements Parcelable {

    private final String mId;
    private final int mUserId;

    /**
     * Creates a new id for a prediction session.
     *
     * @hide
     */
    public AppPredictionSessionId(@NonNull final String id, final int userId) {
        mId = id;
        mUserId = userId;
    }

    private AppPredictionSessionId(Parcel p) {
        mId = p.readString();
        mUserId = p.readInt();
    }

    /**
     * @hide
     */
    public int getUserId() {
        return mUserId;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!getClass().equals(o != null ? o.getClass() : null)) return false;

        AppPredictionSessionId other = (AppPredictionSessionId) o;
        return mId.equals(other.mId) && mUserId == other.mUserId;
    }

    @Override
    public @NonNull String toString() {
        return mId + "," + mUserId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mUserId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeInt(mUserId);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<AppPredictionSessionId> CREATOR =
            new Parcelable.Creator<AppPredictionSessionId>() {
                public AppPredictionSessionId createFromParcel(Parcel parcel) {
                    return new AppPredictionSessionId(parcel);
                }

                public AppPredictionSessionId[] newArray(int size) {
                    return new AppPredictionSessionId[size];
                }
            };
}
