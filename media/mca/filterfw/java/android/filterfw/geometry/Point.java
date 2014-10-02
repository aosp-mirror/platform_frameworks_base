/*
 * Copyright (C) 2011 The Android Open Source Project
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


package android.filterfw.geometry;

import java.lang.Math;

/**
 * @hide
 */
public class Point {

    public float x;
    public float y;

    public Point() {
    }

    public Point(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public void set(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public boolean IsInUnitRange() {
        return x >= 0.0f && x <= 1.0f &&
               y >= 0.0f && y <= 1.0f;
    }

    public Point plus(float x, float y) {
        return new Point(this.x + x, this.y + y);
    }

    public Point plus(Point point) {
        return this.plus(point.x, point.y);
    }

    public Point minus(float x, float y) {
        return new Point(this.x - x, this.y - y);
    }

    public Point minus(Point point) {
        return this.minus(point.x, point.y);
    }

    public Point times(float s) {
        return new Point(this.x * s, this.y * s);
    }

    public Point mult(float x, float y) {
        return new Point(this.x * x, this.y * y);
    }

    public float length() {
        return (float)Math.hypot(x, y);
    }

    public float distanceTo(Point p) {
        return p.minus(this).length();
    }

    public Point scaledTo(float length) {
        return this.times(length / this.length());
    }

    public Point normalize() {
        return this.scaledTo(1.0f);
    }

    public Point rotated90(int count) {
        float nx = this.x;
        float ny = this.y;
        for (int i = 0; i < count; ++i) {
            float ox = nx;
            nx = ny;
            ny = -ox;
        }
        return new Point(nx, ny);
    }

    public Point rotated(float radians) {
        // TODO(renn): Optimize: Keep cache of cos/sin values
        return new Point((float)(Math.cos(radians) * x - Math.sin(radians) * y),
                         (float)(Math.sin(radians) * x + Math.cos(radians) * y));
    }

    public Point rotatedAround(Point center, float radians) {
        return this.minus(center).rotated(radians).plus(center);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ")";
    }
}
