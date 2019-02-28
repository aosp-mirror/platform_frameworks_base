/*
 * Copyright (C) 2018 The Android Open Source Project
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

/** @hide */
public final class Curve implements Parcelable {
    private final float[] mX;
    private final float[] mY;

    public Curve(float[] x, float[] y) {
        mX = x;
        mY = y;
    }

    public float[] getX() {
        return mX;
    }

    public float[] getY() {
        return mY;
    }

    public static final @android.annotation.NonNull Creator<Curve> CREATOR = new Creator<Curve>() {
        public Curve createFromParcel(Parcel in) {
            float[] x = in.createFloatArray();
            float[] y = in.createFloatArray();
            return new Curve(x, y);
        }

        public Curve[] newArray(int size) {
            return new Curve[size];
        }
    };

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeFloatArray(mX);
        out.writeFloatArray(mY);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        final int size = mX.length;
        for (int i = 0; i < size; i++) {
            if (i != 0) {
                sb.append(", ");
            }
            sb.append("(").append(mX[i]).append(", ").append(mY[i]).append(")");
        }
        sb.append("]");
        return sb.toString();
    }
}
