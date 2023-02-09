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
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Class to identify a top priority resolution mechanism that is used to resolve the enforced
 * policy when being set by multiple admins (see {@link PolicyState#getResolutionMechanism()}).
 *
 * <p>Priorities are defined based on the calling admin's {@link Authority}.
 *
 * @hide
 */
@TestApi
public final class TopPriority<V> extends ResolutionMechanism<V> {

    private final List<String> mHighestToLowestPriorityAuthorities;

    /**
     * @hide
     */
    public TopPriority(@NonNull List<String> highestToLowestPriorityAuthorities) {
        mHighestToLowestPriorityAuthorities = Objects.requireNonNull(
                highestToLowestPriorityAuthorities);
    }

    /**
     * Returns an ordered list of authorities from highest priority to lowest priority for a
     * certain policy.
     */
    @NonNull
    List<String> getHighestToLowestPriorityAuthorities() {
        return mHighestToLowestPriorityAuthorities;
    }

    @Override
    public String toString() {
        return "TopPriority { mHighestToLowestPriorityAuthorities= "
                + mHighestToLowestPriorityAuthorities + " }";
    }
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStringArray(mHighestToLowestPriorityAuthorities.toArray(new String[0]));
    }

    @NonNull
    public static final Parcelable.Creator<TopPriority<?>> CREATOR =
            new Parcelable.Creator<TopPriority<?>>() {
                @Override
                public TopPriority<?> createFromParcel(Parcel source) {
                    String[] highestToLowestPriorityAuthorities = source.readStringArray();
                    return new TopPriority<>(
                            Arrays.stream(highestToLowestPriorityAuthorities).toList());
                }

                @Override
                public TopPriority<?>[] newArray(int size) {
                    return new TopPriority[size];
                }
            };

}
