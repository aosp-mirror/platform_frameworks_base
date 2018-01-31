/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * Information about an ephemeral application intent filter.
 * @hide
 * @removed
 */
@Deprecated
@SystemApi
public final class EphemeralIntentFilter implements Parcelable {
    private final InstantAppIntentFilter mInstantAppIntentFilter;

    public EphemeralIntentFilter(@Nullable String splitName, @NonNull List<IntentFilter> filters) {
        mInstantAppIntentFilter = new InstantAppIntentFilter(splitName, filters);
    }

    EphemeralIntentFilter(@NonNull InstantAppIntentFilter intentFilter) {
        mInstantAppIntentFilter = intentFilter;
    }

    EphemeralIntentFilter(Parcel in) {
        mInstantAppIntentFilter = in.readParcelable(null /*loader*/);
    }

    public String getSplitName() {
        return mInstantAppIntentFilter.getSplitName();
    }

    public List<IntentFilter> getFilters() {
        return mInstantAppIntentFilter.getFilters();
    }

    /** @hide */
    InstantAppIntentFilter getInstantAppIntentFilter() {
        return mInstantAppIntentFilter;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeParcelable(mInstantAppIntentFilter, flags);
    }

    public static final Parcelable.Creator<EphemeralIntentFilter> CREATOR
            = new Parcelable.Creator<EphemeralIntentFilter>() {
        @Override
        public EphemeralIntentFilter createFromParcel(Parcel in) {
            return new EphemeralIntentFilter(in);
        }
        @Override
        public EphemeralIntentFilter[] newArray(int size) {
            return new EphemeralIntentFilter[size];
        }
    };
}
