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

package android.app.usage;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** @hide */
public final class BroadcastResponseStatsList implements Parcelable {
    private List<BroadcastResponseStats> mBroadcastResponseStats;

    public BroadcastResponseStatsList(
            @NonNull List<BroadcastResponseStats> broadcastResponseStats) {
        mBroadcastResponseStats = broadcastResponseStats;
    }

    private BroadcastResponseStatsList(@NonNull Parcel in) {
        mBroadcastResponseStats = new ArrayList<>();
        final byte[] bytes = in.readBlob();
        final Parcel data = Parcel.obtain();
        try {
            data.unmarshall(bytes, 0, bytes.length);
            data.setDataPosition(0);
            data.readTypedList(mBroadcastResponseStats, BroadcastResponseStats.CREATOR);
        } finally {
            data.recycle();
        }
    }

    @NonNull
    public List<BroadcastResponseStats> getList() {
        return mBroadcastResponseStats == null ? Collections.emptyList() : mBroadcastResponseStats;
    }

    @Override
    public @ContentsFlags int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, @WriteFlags int flags) {
        final Parcel data = Parcel.obtain();
        try {
            data.writeTypedList(mBroadcastResponseStats);
            dest.writeBlob(data.marshall());
        } finally {
            data.recycle();
        }
    }

    public static final @NonNull Creator<BroadcastResponseStatsList> CREATOR =
            new Creator<BroadcastResponseStatsList>() {
                @Override
                public @NonNull BroadcastResponseStatsList createFromParcel(
                        @NonNull Parcel source) {
                    return new BroadcastResponseStatsList(source);
                }

                @Override
                public @NonNull BroadcastResponseStatsList[] newArray(int size) {
                    return new BroadcastResponseStatsList[size];
                }
            };
}
