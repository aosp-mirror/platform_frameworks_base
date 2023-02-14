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
 * Class to identify a union resolution mechanism for flag policies, it's used to resolve the
 * enforced policy when being set by multiple admins (see
 * {@link PolicyState#getResolutionMechanism()}).
 *
 * @hide
 */
@TestApi
public final class FlagUnion extends ResolutionMechanism<Integer> {

    /**
     * Union resolution for policies represented as int flags which resolves as the union of all
     * flags.
     */
    @NonNull
    public static final FlagUnion FLAG_UNION = new FlagUnion();

    private FlagUnion(){};

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
        return "FlagUnion {}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {}

    @NonNull
    public static final Parcelable.Creator<FlagUnion> CREATOR =
            new Parcelable.Creator<FlagUnion>() {
                @Override
                public FlagUnion createFromParcel(Parcel source) {
                    return FLAG_UNION;
                }

                @Override
                public FlagUnion[] newArray(int size) {
                    return new FlagUnion[size];
                }
            };

}
