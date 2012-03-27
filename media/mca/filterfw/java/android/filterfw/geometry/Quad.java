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

import android.filterfw.geometry.Point;

import java.lang.Float;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @hide
 */
public class Quad {

    public Point p0;
    public Point p1;
    public Point p2;
    public Point p3;

    public Quad() {
    }

    public Quad(Point p0, Point p1, Point p2, Point p3) {
        this.p0 = p0;
        this.p1 = p1;
        this.p2 = p2;
        this.p3 = p3;
    }

    public boolean IsInUnitRange() {
        return p0.IsInUnitRange() &&
               p1.IsInUnitRange() &&
               p2.IsInUnitRange() &&
               p3.IsInUnitRange();
    }

    public Quad translated(Point t) {
        return new Quad(p0.plus(t), p1.plus(t), p2.plus(t), p3.plus(t));
    }

    public Quad translated(float x, float y) {
        return new Quad(p0.plus(x, y), p1.plus(x, y), p2.plus(x, y), p3.plus(x, y));
    }

    public Quad scaled(float s) {
        return new Quad(p0.times(s), p1.times(s), p2.times(s), p3.times(s));
    }

    public Quad scaled(float x, float y) {
        return new Quad(p0.mult(x, y), p1.mult(x, y), p2.mult(x, y), p3.mult(x, y));
    }

    public Rectangle boundingBox() {
        List<Float> xs = Arrays.asList(p0.x, p1.x, p2.x, p3.x);
        List<Float> ys = Arrays.asList(p0.y, p1.y, p2.y, p3.y);
        float x0 = Collections.min(xs);
        float y0 = Collections.min(ys);
        float x1 = Collections.max(xs);
        float y1 = Collections.max(ys);
        return new Rectangle(x0, y0, x1 - x0, y1 - y0);
    }

    public float getBoundingWidth() {
        List<Float> xs = Arrays.asList(p0.x, p1.x, p2.x, p3.x);
        return Collections.max(xs) - Collections.min(xs);
    }

    public float getBoundingHeight() {
        List<Float> ys = Arrays.asList(p0.y, p1.y, p2.y, p3.y);
        return Collections.max(ys) - Collections.min(ys);
    }

    @Override
    public String toString() {
        return "{" + p0 + ", " + p1 + ", " + p2 + ", " + p3 + "}";
    }
}
