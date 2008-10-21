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

import android.util.FloatMath;


/**
 * PointF holds two float coordinates
 */
public class PointF {
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
}

