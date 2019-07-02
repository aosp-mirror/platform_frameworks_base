/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.IntentFilter;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * Information about an instant application intent filter.
 * @hide
 */
@SystemApi
public final class InstantAppIntentFilter implements Parcelable {
    private final String mSplitName;
    /** The filters used to match domain */
    private final List<IntentFilter> mFilters = new ArrayList<IntentFilter>();

    public InstantAppIntentFilter(@Nullable String splitName, @NonNull List<IntentFilter> filters) {
        if (filters == null || filters.size() == 0) {
            throw new IllegalArgumentException();
        }
        mSplitName = splitName;
        mFilters.addAll(filters);
    }

    InstantAppIntentFilter(Parcel in) {
        mSplitName = in.readString();
        in.readList(mFilters, null /*loader*/);
    }

    public String getSplitName() {
        return mSplitName;
    }

    public List<IntentFilter> getFilters() {
        return mFilters;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mSplitName);
        out.writeList(mFilters);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<InstantAppIntentFilter> CREATOR
            = new Parcelable.Creator<InstantAppIntentFilter>() {
        @Override
        public InstantAppIntentFilter createFromParcel(Parcel in) {
            return new InstantAppIntentFilter(in);
        }
        @Override
        public InstantAppIntentFilter[] newArray(int size) {
            return new InstantAppIntentFilter[size];
        }
    };
}
