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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * The id for a prediction target.
 * @hide
 */
@SystemApi
public final class AppTargetId implements Parcelable {

    @NonNull
    private final String mId;

    /**
     * @hide
     */
    public AppTargetId(@NonNull String id) {
        mId = id;
    }

    private AppTargetId(Parcel parcel) {
        mId = parcel.readString();
    }

    /**
     * Returns the id.
     * @hide
     */
    @NonNull
    public String getId() {
        return mId;
    }

    @Override
    public boolean equals(Object o) {
        if (!getClass().equals(o != null ? o.getClass() : null)) return false;

        AppTargetId other = (AppTargetId) o;
        return mId.equals(other.mId);
    }

    @Override
    public int hashCode() {
        // Ensure that the id has a consistent hash
        return mId.hashCode();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mId);
    }

    /**
     * @see Creator
     */
    public static final Creator<AppTargetId> CREATOR =
            new Creator<AppTargetId>() {
                public AppTargetId createFromParcel(Parcel parcel) {
                    return new AppTargetId(parcel);
                }

                public AppTargetId[] newArray(int size) {
                    return new AppTargetId[size];
                }
            };
}
