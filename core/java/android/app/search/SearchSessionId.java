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

package android.app.search;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * The id for an search session. See {@link SearchSession}.
 *
 * @hide
 */
@SystemApi
public final class SearchSessionId implements Parcelable {

    private final String mId;
    private final int mUserId;

    /**
     * Creates a new id for a search session.
     *
     * @hide
     */
    public SearchSessionId(@NonNull final String id, final int userId) {
        mId = id;
        mUserId = userId;
    }

    private SearchSessionId(Parcel p) {
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

        SearchSessionId other = (SearchSessionId) o;
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
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeInt(mUserId);
    }

    public static final @NonNull Creator<SearchSessionId> CREATOR =
            new Creator<SearchSessionId>() {
                public SearchSessionId createFromParcel(Parcel parcel) {
                    return new SearchSessionId(parcel);
                }

                public SearchSessionId[] newArray(int size) {
                    return new SearchSessionId[size];
                }
            };
}
