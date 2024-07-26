/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.nfc;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents a single available Nfc antenna
 * on an Android device.
 */
public final class AvailableNfcAntenna implements Parcelable {
    /**
     * Location of the antenna on the Y axis in millimeters.
     * 0 is the top-left when the user is facing the screen
     * and the device orientation is Portrait.
     */
    private final int mLocationX;
    /**
     * Location of the antenna on the Y axis in millimeters.
     * 0 is the top-left when the user is facing the screen
     * and the device orientation is Portrait.
     */
    private final int mLocationY;

    public AvailableNfcAntenna(int locationX, int locationY) {
        this.mLocationX = locationX;
        this.mLocationY = locationY;
    }

    /**
     * Location of the antenna on the X axis in millimeters.
     * 0 is the top-left when the user is facing the screen
     * and the device orientation is Portrait.
     */
    public int getLocationX() {
        return mLocationX;
    }

    /**
     * Location of the antenna on the Y axis in millimeters.
     * 0 is the top-left when the user is facing the screen
     * and the device orientation is Portrait.
     */
    public int getLocationY() {
        return mLocationY;
    }

    private AvailableNfcAntenna(Parcel in) {
        this.mLocationX = in.readInt();
        this.mLocationY = in.readInt();
    }

    public static final @android.annotation.NonNull Parcelable.Creator<AvailableNfcAntenna>
            CREATOR = new Parcelable.Creator<AvailableNfcAntenna>() {
                @Override
                public AvailableNfcAntenna createFromParcel(Parcel in) {
                    return new AvailableNfcAntenna(in);
                }

                @Override
                public AvailableNfcAntenna[] newArray(int size) {
                    return new AvailableNfcAntenna[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mLocationX);
        dest.writeInt(mLocationY);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + mLocationX;
        result = prime * result + mLocationY;
        return result;
    }

    /**
     * Returns true if the specified AvailableNfcAntenna contains
     * identical specifications.
     */
    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        AvailableNfcAntenna other = (AvailableNfcAntenna) obj;
        if (this.mLocationX != other.mLocationX) return false;
        return this.mLocationY == other.mLocationY;
    }

    @Override
    public String toString() {
        return "AvailableNfcAntenna " + "x: " + mLocationX + " y: " + mLocationY;
    }
}
