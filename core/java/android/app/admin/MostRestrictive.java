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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Class to identify a most restrictive resolution mechanism that is used to resolve the enforced
 * policy when being set by multiple admins (see {@link PolicyState#getResolutionMechanism()}).
 *
 * @hide
 */
@TestApi
public final class MostRestrictive<V> extends ResolutionMechanism<V> {

    private final List<PolicyValue<V>> mMostToLeastRestrictive;

    /**
     * @hide
     */
    public MostRestrictive(@NonNull List<PolicyValue<V>> mostToLeastRestrictive) {
        mMostToLeastRestrictive = new ArrayList<>(mostToLeastRestrictive);
    }

    /**
     * Returns an ordered list of most to least restrictive values for a certain policy.
     */
    @NonNull
    public List<V> getMostToLeastRestrictiveValues() {
        return mMostToLeastRestrictive.stream().map(PolicyValue::getValue).toList();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        try {
            MostRestrictive<V> other = (MostRestrictive<V>) o;
            return Objects.equals(mMostToLeastRestrictive, other.mMostToLeastRestrictive);
        } catch (ClassCastException exception) {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return mMostToLeastRestrictive.hashCode();
    }

    /**
     * @hide
     */
    public MostRestrictive(Parcel source) {
        mMostToLeastRestrictive = new ArrayList<>();
        int size = source.readInt();
        for (int i = 0; i < size; i++) {
            mMostToLeastRestrictive.add(source.readParcelable(PolicyValue.class.getClassLoader()));
        }
    }

    @Override
    public String toString() {
        return "MostRestrictive { mMostToLeastRestrictive= " + mMostToLeastRestrictive + " }";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mMostToLeastRestrictive.size());
        for (PolicyValue<V> entry : mMostToLeastRestrictive) {
            dest.writeParcelable(entry, flags);
        }
    }

    @NonNull
    public static final Parcelable.Creator<MostRestrictive<?>> CREATOR =
            new Parcelable.Creator<MostRestrictive<?>>() {
                @Override
                public MostRestrictive<?> createFromParcel(Parcel source) {
                    return new MostRestrictive<>(source);
                }

                @Override
                public MostRestrictive<?>[] newArray(int size) {
                    return new MostRestrictive[size];
                }
            };
}
