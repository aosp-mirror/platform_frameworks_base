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
import android.filterfw.geometry.Quad;

/**
 * @hide
 */
public class Rectangle extends Quad {

    public Rectangle() {
    }

    public Rectangle(float x, float y, float width, float height) {
        super(new Point(x, y),
              new Point(x + width, y),
              new Point(x, y + height),
              new Point(x + width, y + height));
    }

    public Rectangle(Point origin, Point size) {
        super(origin,
              origin.plus(size.x, 0.0f),
              origin.plus(0.0f, size.y),
              origin.plus(size.x, size.y));
    }

    public static Rectangle fromRotatedRect(Point center, Point size, float rotation) {
        Point p0 = new Point(center.x - size.x/2f, center.y - size.y/2f);
        Point p1 = new Point(center.x + size.x/2f, center.y - size.y/2f);
        Point p2 = new Point(center.x - size.x/2f, center.y + size.y/2f);
        Point p3 = new Point(center.x + size.x/2f, center.y + size.y/2f);
        return new Rectangle(p0.rotatedAround(center, rotation),
                             p1.rotatedAround(center, rotation),
                             p2.rotatedAround(center, rotation),
                             p3.rotatedAround(center, rotation));
    }

    private Rectangle(Point p0, Point p1, Point p2, Point p3) {
        super(p0, p1, p2, p3);
    }

    public static Rectangle fromCenterVerticalAxis(Point center, Point vAxis, Point size) {
        Point dy = vAxis.scaledTo(size.y / 2.0f);
        Point dx = vAxis.rotated90(1).scaledTo(size.x / 2.0f);
        return new Rectangle(center.minus(dx).minus(dy),
                             center.plus(dx).minus(dy),
                             center.minus(dx).plus(dy),
                             center.plus(dx).plus(dy));
    }

    public float getWidth() {
        return p1.minus(p0).length();
    }

    public float getHeight() {
        return p2.minus(p0).length();
    }

    public Point center() {
        return p0.plus(p1).plus(p2).plus(p3).times(0.25f);
    }

    @Override
    public Rectangle scaled(float s) {
        return new Rectangle(p0.times(s), p1.times(s), p2.times(s), p3.times(s));
    }

    @Override
    public Rectangle scaled(float x, float y) {
        return new Rectangle(p0.mult(x, y), p1.mult(x, y), p2.mult(x, y), p3.mult(x, y));
    }

    //public Rectangle rotated(float radians) {
      // TODO: Implement this.
    //}

}
