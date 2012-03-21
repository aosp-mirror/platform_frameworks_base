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
package android.webkit;

import android.graphics.PointF;

/**
 * A quadrilateral, determined by four points, clockwise order. Typically
 * p1 is "top-left" and p4 is "bottom-left" following webkit's rectangle-to-
 * FloatQuad conversion.
 */
class QuadF {
    public PointF p1;
    public PointF p2;
    public PointF p3;
    public PointF p4;

    public QuadF() {
        p1 = new PointF();
        p2 = new PointF();
        p3 = new PointF();
        p4 = new PointF();
    }

    public void offset(float dx, float dy) {
        p1.offset(dx, dy);
        p2.offset(dx, dy);
        p3.offset(dx, dy);
        p4.offset(dx, dy);
    }

    /**
     * Determines if the quadrilateral contains the given point. This does
     * not work if the quadrilateral is self-intersecting or if any inner
     * angle is reflex (greater than 180 degrees).
     */
    public boolean containsPoint(float x, float y) {
        return isPointInTriangle(x, y, p1, p2, p3) ||
                isPointInTriangle(x, y, p1, p3, p4);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder("QuadF(");
        s.append(p1.x).append(",").append(p1.y);
        s.append(" - ");
        s.append(p2.x).append(",").append(p2.y);
        s.append(" - ");
        s.append(p3.x).append(",").append(p3.y);
        s.append(" - ");
        s.append(p4.x).append(",").append(p4.y);
        s.append(")");
        return s.toString();
    }

    private static boolean isPointInTriangle(float x0, float y0,
            PointF r1, PointF r2, PointF r3) {
        // Use the barycentric technique
        float x13 = r1.x - r3.x;
        float y13 = r1.y - r3.y;
        float x23 = r2.x - r3.x;
        float y23 = r2.y - r3.y;
        float x03 = x0 - r3.x;
        float y03 = y0 - r3.y;

        float determinant = (y23 * x13) - (x23 * y13);
        float lambda1 = ((y23 * x03) - (x23 * y03))/determinant;
        float lambda2 = ((x13 * y03) - (y13 * x03))/determinant;
        float lambda3 = 1 - lambda1 - lambda2;
        return lambda1 >= 0.0f && lambda2 >= 0.0f && lambda3 >= 0.0f;
    }
}
