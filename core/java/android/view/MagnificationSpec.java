/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.view;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Pools.SynchronizedPool;

/**
 * This class represents spec for performing screen magnification.
 *
 * @hide
 */
public class MagnificationSpec implements Parcelable {
    private static final int MAX_POOL_SIZE = 20;
    private static final SynchronizedPool<MagnificationSpec> sPool =
            new SynchronizedPool<MagnificationSpec>(MAX_POOL_SIZE);

    public float scale = 1.0f;
    public float offsetX;
    public float offsetY;

    private MagnificationSpec() {
        /* do nothing - reducing visibility */
    }

    public void initialize(float scale, float offsetX, float offsetY) {
        if (scale < 1) {
            throw new IllegalArgumentException("Scale must be greater than or equal to one!");
        }
        this.scale = scale;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    public boolean isNop() {
        return scale == 1.0f && offsetX == 0 && offsetY == 0;
    }

    public static MagnificationSpec obtain(MagnificationSpec other) {
        MagnificationSpec info = obtain();
        info.scale = other.scale;
        info.offsetX = other.offsetX;
        info.offsetY = other.offsetY;
        return info;
    }

    public static MagnificationSpec obtain() {
        MagnificationSpec spec = sPool.acquire();
        return (spec != null) ? spec : new MagnificationSpec();
    }

    public void recycle() {
        clear();
        sPool.release(this);
    }

    public void clear() {
       scale = 1.0f;
       offsetX = 0.0f;
       offsetY = 0.0f;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeFloat(scale);
        parcel.writeFloat(offsetX);
        parcel.writeFloat(offsetY);
        recycle();
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("<scale:");
        builder.append(scale);
        builder.append(",offsetX:");
        builder.append(offsetX);
        builder.append(",offsetY:");
        builder.append(offsetY);
        builder.append(">");
        return builder.toString();
    }

    private void initFromParcel(Parcel parcel) {
        scale = parcel.readFloat();
        offsetX = parcel.readFloat();
        offsetY = parcel.readFloat();
    }

    public static final Creator<MagnificationSpec> CREATOR = new Creator<MagnificationSpec>() {
        @Override
        public MagnificationSpec[] newArray(int size) {
            return new MagnificationSpec[size];
        }

        @Override
        public MagnificationSpec createFromParcel(Parcel parcel) {
            MagnificationSpec spec = MagnificationSpec.obtain();
            spec.initFromParcel(parcel);
            return spec;
        }
    };
}
