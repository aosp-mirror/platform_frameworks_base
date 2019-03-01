/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.hardware.camera2.utils;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * @hide
 */
public class LongParcelable implements Parcelable {
    private long number;

    public LongParcelable() {
        this.number = 0;
    }

    public LongParcelable(long number) {
        this.number = number;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<LongParcelable> CREATOR =
            new Parcelable.Creator<LongParcelable>() {
        @Override
        public LongParcelable createFromParcel(Parcel in) {
            return new LongParcelable(in);
        }

        @Override
        public LongParcelable[] newArray(int size) {
            return new LongParcelable[size];
        }
    };

    private LongParcelable(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(number);
    }

    public void readFromParcel(Parcel in) {
        number = in.readLong();
    }

    public long getNumber() {
        return number;
    }

    public void setNumber(long number) {
        this.number = number;
    }

}
