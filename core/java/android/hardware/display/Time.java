/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.hardware.display;

import android.os.Parcel;
import android.os.Parcelable;

import java.time.LocalTime;

/**
 * @hide
 */
public final class Time implements Parcelable {

    private final int mHour;
    private final int mMinute;
    private final int mSecond;
    private final int mNano;

    public Time(LocalTime localTime) {
        mHour = localTime.getHour();
        mMinute = localTime.getMinute();
        mSecond = localTime.getSecond();
        mNano = localTime.getNano();
    }

    public Time(Parcel parcel) {
        mHour = parcel.readInt();
        mMinute = parcel.readInt();
        mSecond = parcel.readInt();
        mNano = parcel.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int parcelableFlags) {
        parcel.writeInt(mHour);
        parcel.writeInt(mMinute);
        parcel.writeInt(mSecond);
        parcel.writeInt(mNano);
    }

    public LocalTime getLocalTime() {
        return LocalTime.of(mHour, mMinute, mSecond, mNano);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<Time> CREATOR = new Parcelable.Creator<Time>() {

        @Override
        public Time createFromParcel(Parcel source) {
            return new Time(source);
        }

        @Override
        public Time[] newArray(int size) {
            return new Time[size];
        }
    };
}
