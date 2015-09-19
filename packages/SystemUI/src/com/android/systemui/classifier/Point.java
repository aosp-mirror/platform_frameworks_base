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
 * limitations under the License
 */

package com.android.systemui.classifier;

public class Point {
    public float x;
    public float y;
    public long timeOffsetNano;

    public Point(float x, float y) {
        this.x = x;
        this.y = y;
        this.timeOffsetNano = 0;
    }

    public Point(float x, float y, long timeOffsetNano) {
        this.x = x;
        this.y = y;
        this.timeOffsetNano = timeOffsetNano;
    }

    public boolean equals(Point p) {
        return x == p.x && y == p.y;
    }

    public float dist(Point a) {
        return (float) Math.hypot(a.x - x, a.y - y);
    }

    /**
     * Calculates the cross product of vec(this, a) and vec(this, b) where vec(x,y) is the
     * vector from point x to point y
     */
    public float crossProduct(Point a, Point b) {
        return (a.x - x) * (b.y - y) - (a.y - y) * (b.x - x);
    }

    /**
     * Calculates the dot product of vec(this, a) and vec(this, b) where vec(x,y) is the
     * vector from point x to point y
     */
    public float dotProduct(Point a, Point b) {
        return (a.x - x) * (b.x - x) + (a.y - y) * (b.y - y);
    }

    /**
     * Calculates the angle in radians created by points (a, this, b). If any two of these points
     * are the same, the method will return 0.0f
     *
     * @return the angle in radians
     */
    public float getAngle(Point a, Point b) {
        float dist1 = dist(a);
        float dist2 = dist(b);

        if (dist1 == 0.0f || dist2 == 0.0f) {
            return 0.0f;
        }

        float crossProduct = crossProduct(a, b);
        float dotProduct = dotProduct(a, b);
        float cos = Math.min(1.0f, Math.max(-1.0f, dotProduct / dist1 / dist2));
        float angle = (float) Math.acos(cos);
        if (crossProduct < 0.0) {
            angle = 2.0f * (float) Math.PI - angle;
        }
        return angle;
    }
}
