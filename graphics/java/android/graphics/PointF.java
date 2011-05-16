/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.graphics;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.FloatMath;


/**
 * PointF holds two float coordinates
 */
public class PointF implements Parcelable {
    public float x;
    public float y;
    
    public PointF() {}

    public PointF(float x, float y) {
        this.x = x;
        this.y = y; 
    }
    
    public PointF(Point p) { 
        this.x = p.x;
        this.y = p.y;
    }
    
    /**
     * Set the point's x and y coordinates
     */
    public final void set(float x, float y) {
        this.x = x;
        this.y = y;
    }
    
    /**
     * Set the point's x and y coordinates to the coordinates of p
     */
    public final void set(PointF p) { 
        this.x = p.x;
        this.y = p.y;
    }
    
    public final void negate() { 
        x = -x;
        y = -y; 
    }
    
    public final void offset(float dx, float dy) {
        x += dx;
        y += dy;
    }
    
    /**
     * Returns true if the point's coordinates equal (x,y)
     */
    public final boolean equals(float x, float y) { 
        return this.x == x && this.y == y; 
    }

    /**
     * Return the euclidian distance from (0,0) to the point
     */
    public final float length() { 
        return length(x, y); 
    }
    
    /**
     * Returns the euclidian distance from (0,0) to (x,y)
     */
    public static float length(float x, float y) {
        return FloatMath.sqrt(x * x + y * y);
    }

    /**
     * Parcelable interface methods
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Write this point to the specified parcel. To restore a point from
     * a parcel, use readFromParcel()
     * @param out The parcel to write the point's coordinates into
     */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeFloat(x);
        out.writeFloat(y);
    }

    public static final Parcelable.Creator<PointF> CREATOR = new Parcelable.Creator<PointF>() {
        /**
         * Return a new point from the data in the specified parcel.
         */
        public PointF createFromParcel(Parcel in) {
            PointF r = new PointF();
            r.readFromParcel(in);
            return r;
        }

        /**
         * Return an array of rectangles of the specified size.
         */
        public PointF[] newArray(int size) {
            return new PointF[size];
        }
    };

    /**
     * Set the point's coordinates from the data stored in the specified
     * parcel. To write a point to a parcel, call writeToParcel().
     *
     * @param in The parcel to read the point's coordinates from
     */
    public void readFromParcel(Parcel in) {
        x = in.readFloat();
        y = in.readFloat();
    }
}
