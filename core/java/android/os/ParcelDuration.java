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

package android.os;

import android.annotation.NonNull;

import java.time.Duration;

/**
 * Parcelable version of {@link Duration} that can be used in binder calls.
 *
 * @hide
 */
public final class ParcelDuration implements Parcelable {

    private final long mSeconds;
    private final int mNanos;

    /**
     * Construct a Duration object using the given millisecond value.
     *
     * @hide
     */
    public ParcelDuration(long ms) {
        this(Duration.ofMillis(ms));
    }

    /**
     * Wrap a {@link Duration} instance.
     *
     * @param duration The {@link Duration} instance to wrap.
     */
    public ParcelDuration(@NonNull Duration duration) {
        mSeconds = duration.getSeconds();
        mNanos = duration.getNano();
    }

    private ParcelDuration(@NonNull Parcel parcel) {
        mSeconds = parcel.readLong();
        mNanos = parcel.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int parcelableFlags) {
        parcel.writeLong(mSeconds);
        parcel.writeInt(mNanos);
    }

    /**
     * Returns a {@link Duration} instance that's equivalent to this Duration's length.
     *
     * @return a {@link Duration} instance of identical length.
     */
    @NonNull
    public Duration getDuration() {
        return Duration.ofSeconds(mSeconds, mNanos);
    }

    @Override
    @NonNull
    public String toString() {
        return getDuration().toString();
    }

    /**
     * Creator for Duration.
     */
    @NonNull
    public static final Parcelable.Creator<ParcelDuration> CREATOR =
            new Parcelable.Creator<ParcelDuration>() {

        @Override
        @NonNull
        public ParcelDuration createFromParcel(@NonNull Parcel source) {
            return new ParcelDuration(source);
        }

        @Override
        @NonNull
        public ParcelDuration[] newArray(int size) {
            return new ParcelDuration[size];
        }
    };
}
