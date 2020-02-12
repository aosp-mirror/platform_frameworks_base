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
package android.content.pm;

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * Equivalent to List<ProviderInfo>, but it "squashes" the ApplicationInfo in the elements.
 *
 * @hide
 */
@TestApi
public final class ProviderInfoList implements Parcelable {
    private final List<ProviderInfo> mList;

    private ProviderInfoList(Parcel source) {
        final ArrayList<ProviderInfo> list = new ArrayList<>();
        source.readTypedList(list, ProviderInfo.CREATOR);
        mList = list;
    }

    private ProviderInfoList(List<ProviderInfo> list) {
        mList = list;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // Allow ApplicationInfo to be squashed.
        final boolean prevAllowSquashing = dest.allowSquashing();
        dest.writeTypedList(mList, flags);
        dest.restoreAllowSquashing(prevAllowSquashing);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ProviderInfoList> CREATOR
            = new Parcelable.Creator<ProviderInfoList>() {
        @Override
        public ProviderInfoList createFromParcel(@NonNull Parcel source) {
            return new ProviderInfoList(source);
        }

        @Override
        public ProviderInfoList[] newArray(int size) {
            return new ProviderInfoList[size];
        }
    };

    /**
     * Return the stored list.
     */
    @NonNull
    public List<ProviderInfo> getList() {
        return mList;
    }

    /**
     * Create a new instance with a {@code list}. The passed list will be shared with the new
     * instance, so the caller shouldn't modify it.
     */
    @NonNull
    public static ProviderInfoList fromList(@NonNull List<ProviderInfo> list) {
        return new ProviderInfoList(list);
    }
}
