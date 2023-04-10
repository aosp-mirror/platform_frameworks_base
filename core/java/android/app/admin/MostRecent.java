/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app.admin;


import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Class to identify a most-recent-setter wins resolution mechanism that is used to resolve the
 * enforced policy when being set by multiple admins (see
 * {@link PolicyState#getResolutionMechanism()}).
 *
 * @hide
 */
@TestApi
public final class MostRecent<V> extends ResolutionMechanism<V> {

    /**
     * Indicates that the most recent setter of the policy wins the resolution.
     */
    @NonNull
    public static final MostRecent<?> MOST_RECENT = new MostRecent<>();

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        return o != null && getClass() == o.getClass();
    }

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public String toString() {
        return "MostRecent {}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {}

    @NonNull
    public static final Parcelable.Creator<MostRecent<?>> CREATOR =
            new Parcelable.Creator<MostRecent<?>>() {
                @Override
                public MostRecent<?> createFromParcel(Parcel source) {
                    return new MostRecent<>();
                }

                @Override
                public MostRecent<?>[] newArray(int size) {
                    return new MostRecent[size];
                }
            };
}
