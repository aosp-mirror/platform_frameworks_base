/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.hardware.face;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A matrix cell, corresponding to a desired face image, that may be captured during enrollment.
 *
 * @hide
 */
public final class FaceEnrollCell implements Parcelable {
    private final int mX;
    private final int mY;
    private final int mZ;

    /**
     * A matrix cell, corresponding to a desired face image, that may be captured during enrollment.
     *
     * @param x The horizontal coordinate of this cell.
     * @param y The vertical coordinate of this cell.
     * @param z The depth coordinate of this cell.
     */
    public FaceEnrollCell(int x, int y, int z) {
        mX = x;
        mY = y;
        mZ = z;
    }

    /**
     * @return The horizontal coordinate of this cell.
     */
    public int getX() {
        return mX;
    }

    /**
     * @return The vertical coordinate of this cell.
     */
    public int getY() {
        return mY;
    }

    /**
     * @return The depth coordinate of this cell.
     */
    public int getZ() {
        return mZ;
    }

    private FaceEnrollCell(@NonNull Parcel source) {
        mX = source.readInt();
        mY = source.readInt();
        mZ = source.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mX);
        dest.writeInt(mY);
        dest.writeInt(mZ);
    }

    public static final Creator<FaceEnrollCell> CREATOR = new Creator<FaceEnrollCell>() {
        @Override
        public FaceEnrollCell createFromParcel(Parcel source) {
            return new FaceEnrollCell(source);
        }

        @Override
        public FaceEnrollCell[] newArray(int size) {
            return new FaceEnrollCell[size];
        }
    };
}
