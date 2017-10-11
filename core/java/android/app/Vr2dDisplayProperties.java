/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.app;

import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.PrintWriter;

/**
 * Display properties to be used by VR mode when creating a virtual display.
 *
 * @hide
 */
public class Vr2dDisplayProperties implements Parcelable {

   /**
    * The actual width, height and dpi.
    */
    private final int mWidth;
    private final int mHeight;
    private final int mDpi;

    public Vr2dDisplayProperties(int width, int height, int dpi) {
        mWidth = width;
        mHeight = height;
        mDpi = dpi;
    }

    @Override
    public int hashCode() {
        int result = getWidth();
        result = 31 * result + getHeight();
        result = 31 * result + getDpi();
        return result;
    }

    @Override
    public String toString() {
        return "Vr2dDisplayProperties{" +
                "mWidth=" + mWidth +
                ", mHeight=" + mHeight +
                ", mDpi=" + mDpi +
                "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Vr2dDisplayProperties that = (Vr2dDisplayProperties) o;

        if (getWidth() != that.getWidth()) return false;
        if (getHeight() != that.getHeight()) return false;
        return getDpi() == that.getDpi();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mWidth);
        dest.writeInt(mHeight);
        dest.writeInt(mDpi);
    }

    public static final Parcelable.Creator<Vr2dDisplayProperties> CREATOR
            = new Parcelable.Creator<Vr2dDisplayProperties>() {
        @Override
        public Vr2dDisplayProperties createFromParcel(Parcel source) {
            return new Vr2dDisplayProperties(source);
        }

        @Override
        public Vr2dDisplayProperties[] newArray(int size) {
            return new Vr2dDisplayProperties[size];
        }
    };

    private Vr2dDisplayProperties(Parcel source) {
        mWidth = source.readInt();
        mHeight = source.readInt();
        mDpi = source.readInt();
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "Vr2dDisplayProperties:");
        pw.println(prefix + "  width=" + mWidth);
        pw.println(prefix + "  height=" + mHeight);
        pw.println(prefix + "  dpi=" + mDpi);
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public int getDpi() {
        return mDpi;
    }
}
