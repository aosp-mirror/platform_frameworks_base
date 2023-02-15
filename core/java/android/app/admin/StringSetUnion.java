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

import java.util.Set;

/**
 * Class to identify a union resolution mechanism for {@code Set<String>} policies, it's used to
 * resolve the enforced policy when being set by multiple admins (see
 * {@link PolicyState#getResolutionMechanism()}).
 *
 * @hide
 */
@TestApi
public final class StringSetUnion extends ResolutionMechanism<Set<String>> {

    /**
     * Union resolution for policies represented {@code Set<String>} which resolves as the union of
     * all sets.
     */
    @NonNull
    public static final StringSetUnion STRING_SET_UNION = new StringSetUnion();

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
        return "StringSetUnion {}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {}

    @NonNull
    public static final Parcelable.Creator<StringSetUnion> CREATOR =
            new Parcelable.Creator<StringSetUnion>() {
                @Override
                public StringSetUnion createFromParcel(Parcel source) {
                    return new StringSetUnion();
                }

                @Override
                public StringSetUnion[] newArray(int size) {
                    return new StringSetUnion[size];
                }
            };
}
