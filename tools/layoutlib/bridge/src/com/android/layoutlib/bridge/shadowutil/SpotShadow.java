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

package com.android.layoutlib.bridge.shadowutil;

import android.annotation.NonNull;

public class SpotShadow {

    private static float rayIntersectPoly(@NonNull float[] poly, int polyLength, float px, float py,
            float dx, float dy) {
        int p1 = polyLength - 1;
        for (int p2 = 0; p2 < polyLength; p2++) {
            float p1x = poly[p1 * 2 + 0];
            float p1y = poly[p1 * 2 + 1];
            float p2x = poly[p2 * 2 + 0];
            float p2y = poly[p2 * 2 + 1];
            float div = (dx * (p1y - p2y) + dy * p2x - dy * p1x);
            if (div != 0) {
                float t = (dx * (p1y - py) + dy * px - dy * p1x) / (div);
                if (t >= 0 && t <= 1) {
                    float t2 = (p1x * (py - p2y) + p2x * (p1y - py) + px * (p2y - p1y)) / div;
                    if (t2 > 0) {
                        return t2;
                    }
                }
            }
            p1 = p2;
        }
        return Float.NaN;
    }

    private static void centroid2d(@NonNull float[] poly, int len, @NonNull float[] ret) {
        float sumX = 0;
        float sumY = 0;
        int p1 = len - 1;
        float area = 0;
        for (int p2 = 0; p2 < len; p2++) {
            float x1 = poly[p1 * 2 + 0];
            float y1 = poly[p1 * 2 + 1];
            float x2 = poly[p2 * 2 + 0];
            float y2 = poly[p2 * 2 + 1];
            float a = (x1 * y2 - x2 * y1);
            sumX += (x1 + x2) * a;
            sumY += (y1 + y2) * a;
            area += a;
            p1 = p2;
        }

        float centroidX = sumX / (3 * area);
        float centroidY = sumY / (3 * area);
        ret[0] = centroidX;
        ret[1] = centroidY;
    }

    /**
     * calculates the Centroid of a 3d polygon
     * @param poly The flatten 3d vertices coordinates of polygon, the format is like
     * [x0, y0, z0, x1, y1, z1, x2, ...]
     * @param len The number of polygon vertices. So the length of poly should be len * 3.
     * @param ret The array used to sotre the result. The length should be 3.
     */
    private static void centroid3d(@NonNull float[] poly, int len, @NonNull float[] ret) {
        int n = len - 1;
        double area = 0;
        double cx = 0, cy = 0, cz = 0;
        for (int i = 1; i < n; i++) {
            int k = i + 1;
            float a0 = poly[i * 3 + 0] - poly[0 * 3 + 0];
            float a1 = poly[i * 3 + 1] - poly[0 * 3 + 1];
            float a2 = poly[i * 3 + 2] - poly[0 * 3 + 2];
            float b0 = poly[k * 3 + 0] - poly[0 * 3 + 0];
            float b1 = poly[k * 3 + 1] - poly[0 * 3 + 1];
            float b2 = poly[k * 3 + 2] - poly[0 * 3 + 2];
            float c0 = a1 * b2 - b1 * a2;
            float c1 = a2 * b0 - b2 * a0;
            float c2 = a0 * b1 - b0 * a1;
            double areaOfTriangle = Math.sqrt(c0 * c0 + c1 * c1 + c2 * c2);
            area += areaOfTriangle;
            cx += areaOfTriangle * (poly[i * 3 + 0] + poly[k * 3 + 0] + poly[0 * 3 + 0]);
            cy += areaOfTriangle * (poly[i * 3 + 1] + poly[k * 3 + 1] + poly[0 * 3 + 1]);
            cz += areaOfTriangle * (poly[i * 3 + 2] + poly[k * 3 + 2] + poly[0 * 3 + 2]);
        }
        ret[0] = (float) (cx / (3 * area));
        ret[1] = (float) (cy / (3 * area));
        ret[2] = (float) (cz / (3 * area));
    }

    /**
     * Extracts the convex hull of a polygon.
     * @param points The vertices coordinates of polygon
     * @param pointsLength The number of polygon vertices. So the length of poly should be len * 3.
     * @param retPoly retPoly is at most the size of the input polygon
     * @return The number of points in the retPolygon
     */
    private static int hull(@NonNull float[] points, int pointsLength, @NonNull float[] retPoly) {
        quicksortX(points, 0, pointsLength - 1);
        int n = pointsLength;
        float[] lUpper = new float[n * 2];
        lUpper[0] = points[0];
        lUpper[1] = points[1];
        lUpper[2] = points[2];
        lUpper[3] = points[3];

        int lUpperSize = 2;

        for (int i = 2; i < n; i++) {
            lUpper[lUpperSize * 2 + 0] = points[i * 2 + 0];
            lUpper[lUpperSize * 2 + 1] = points[i * 2 + 1];
            lUpperSize++;

            while (lUpperSize > 2 &&
                    !rightTurn(lUpper[(lUpperSize - 3) * 2], lUpper[(lUpperSize - 3) * 2 + 1],
                            lUpper[(lUpperSize - 2) * 2], lUpper[(lUpperSize - 2) * 2 + 1],
                            lUpper[(lUpperSize - 1) * 2], lUpper[(lUpperSize - 1) * 2 + 1])) {
                // Remove the middle point of the three last
                lUpper[(lUpperSize - 2) * 2 + 0] = lUpper[(lUpperSize - 1) * 2 + 0];
                lUpper[(lUpperSize - 2) * 2 + 1] = lUpper[(lUpperSize - 1) * 2 + 1];
                lUpperSize--;
            }
        }

        float[] lLower = new float[n * 2];
        lLower[0] = points[(n - 1) * 2 + 0];
        lLower[1] = points[(n - 1) * 2 + 1];
        lLower[2] = points[(n - 2) * 2 + 0];
        lLower[3] = points[(n - 2) * 2 + 1];

        int lLowerSize = 2;

        for (int i = n - 3; i >= 0; i--) {
            lLower[lLowerSize * 2 + 0] = points[i * 2 + 0];
            lLower[lLowerSize * 2 + 1] = points[i * 2 + 1];
            lLowerSize++;

            while (lLowerSize > 2 &&
                    !rightTurn(lLower[(lLowerSize - 3) * 2], lLower[(lLowerSize - 3) * 2 + 1],
                            lLower[(lLowerSize - 2) * 2], lLower[(lLowerSize - 2) * 2 + 1],
                            lLower[(lLowerSize - 1) * 2], lLower[(lLowerSize - 1) * 2 + 1])) {
                // Remove the middle point of the three last
                lLower[(lLowerSize - 2) * 2 + 0] = lLower[(lLowerSize - 1) * 2 + 0];
                lLower[(lLowerSize - 2) * 2 + 1] = lLower[(lLowerSize - 1) * 2 + 1];
                lLowerSize--;
            }
        }

        int count = 0;
        for (int i = 0; i < lUpperSize; i++) {
            retPoly[count * 2 + 0] = lUpper[i * 2 + 0];
            retPoly[count * 2 + 1] = lUpper[i * 2 + 1];
            count++;
        }
        for (int i = 1; i < lLowerSize - 1; i++) {
            retPoly[count * 2 + 0] = lLower[i * 2 + 0];
            retPoly[count * 2 + 1] = lLower[i * 2 + 1];
            count++;
        }
        return count;
    }

    private static boolean rightTurn(float ax, float ay, float bx, float by, float cx, float cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax) > 0.00001;
    }

    /**
     * calculates the intersection of poly1 with poly2 and put in poly2
     * @param poly1 The flatten 2d coordinates of polygon
     * @param poly1length The vertices number of poly1
     * @param poly2 The flatten 2d coordinates of polygon
     * @param poly2length The vertices number of poly2
     * @return number of vertices in poly2
     */
    private static int intersection(@NonNull float[] poly1, int poly1length, @NonNull float[] poly2,
            int poly2length) {
        makeClockwise(poly1, poly1length);
        makeClockwise(poly2, poly2length);
        float[] poly = new float[(poly1length * poly2length + 2) * 2];
        int count = 0;
        int pCount = 0;
        for (int i = 0; i < poly1length; i++) {
            if (pointInsidePolygon(poly1[i * 2 + 0], poly1[i * 2 + 1], poly2, poly2length)) {
                poly[count * 2 + 0] = poly1[i * 2 + 0];
                poly[count * 2 + 1] = poly1[i * 2 + 1];
                count++;
                pCount++;
            }
        }
        int fromP1 = pCount;
        for (int i = 0; i < poly2length; i++) {
            if (pointInsidePolygon(poly2[i * 2 + 0], poly2[i * 2 + 1], poly1, poly1length)) {
                poly[count * 2 + 0] = poly2[i * 2 + 0];
                poly[count * 2 + 1] = poly2[i * 2 + 1];
                count++;
            }
        }
        int fromP2 = count - fromP1;
        if (fromP1 == poly1length) { // use p1
            for (int i = 0; i < poly1length; i++) {
                poly2[i * 2 + 0] = poly1[i * 2 + 0];
                poly2[i * 2 + 1] = poly1[i * 2 + 1];
            }
            return poly1length;
        }
        if (fromP2 == poly2length) { // use p2
            return poly2length;
        }
        float[] intersection = new float[2];
        for (int i = 0; i < poly2length; i++) {
            for (int j = 0; j < poly1length; j++) {
                int i1_by_2 = i * 2;
                int i2_by_2 = ((i + 1) % poly2length) * 2;
                int j1_by_2 = j * 2;
                int j2_by_2 = ((j + 1) % poly1length) * 2;
                boolean found =
                        lineIntersection(poly2[i1_by_2 + 0], poly2[i1_by_2 + 1], poly2[i2_by_2 + 0],
                                poly2[i2_by_2 + 1], poly1[j1_by_2 + 0], poly1[j1_by_2 + 1],
                                poly1[j2_by_2 + 0], poly1[j2_by_2 + 1], intersection);
                if (found) {
                    poly[count * 2 + 0] = intersection[0];
                    poly[count * 2 + 1] = intersection[1];
                    count++;
                } else {
                    float dx = poly2[i * 2 + 0] - poly1[j * 2 + 0];
                    float dy = poly2[i * 2 + 1] - poly1[j * 2 + 1];

                    if (dx * dx + dy * dy < 0.01) {
                        poly[count * 2 + 0] = poly2[i * 2 + 0];
                        poly[count * 2 + 1] = poly2[i * 2 + 1];
                        count++;
                    }
                }
            }
        }
        if (count == 0) {
            return 0;
        }
        float avgX = 0;
        float avgY = 0;
        for (int i = 0; i < count; i++) {
            avgX += poly[i * 2 + 0];
            avgY += poly[i * 2 + 1];
        }
        avgX /= count;
        avgY /= count;

        float[] ctr = new float[]{avgX, avgY};
        sort(poly, count, ctr);
        int size = count;

        poly2[0] = poly[0];
        poly2[1] = poly[1];

        count = 1;
        for (int i = 1; i < size; i++) {
            float dx = poly[i * 2 + 0] - poly[(i - 1) * 2 + 0];
            float dy = poly[i * 2 + 1] - poly[(i - 1) * 2 + 1];
            if (dx * dx + dy * dy >= 0.01) {
                poly2[count * 2 + 0] = poly[i * 2 + 0];
                poly2[count * 2 + 1] = poly[i * 2 + 1];
                count++;
            }
        }
        return count;
    }

    public static void sort(@NonNull float[] poly, int polyLength, @NonNull float[] ctr) {
        quicksortCircle(poly, 0, polyLength - 1, ctr);
    }

    public static float angle(float x1, float y1, @NonNull float[] ctr) {
        return -(float) Math.atan2(x1 - ctr[0], y1 - ctr[1]);
    }

    private static void swapPair(@NonNull float[] points, int i, int j) {
        float x = points[i * 2 + 0];
        float y = points[i * 2 + 1];
        points[i * 2 + 0] = points[j * 2 + 0];
        points[i * 2 + 1] = points[j * 2 + 1];
        points[j * 2 + 0] = x;
        points[j * 2 + 1] = y;
    }

    private static void quicksortCircle(@NonNull float[] points, int low, int high,
            @NonNull float[] ctr) {
        int i = low, j = high;
        int p = low + (high - low) / 2;
        float pivot = angle(points[p * 2], points[p * 2 + 1], ctr);
        while (i <= j) {
            while (angle(points[i * 2 + 0], points[i * 2 + 1], ctr) < pivot) {
                i++;
            }
            while (angle(points[j * 2 + 0], points[j * 2 + 1], ctr) > pivot) {
                j--;
            }
            if (i <= j) {
                swapPair(points, i, j);
                i++;
                j--;
            }
        }
        if (low < j) {
            quicksortCircle(points, low, j, ctr);
        }
        if (i < high) {
            quicksortCircle(points, i, high, ctr);
        }
    }

    /**
     * This function do Quick Sort by comparing X axis only.<br>
     * Note that the input values of points are paired coordinates, e.g. {@code [x0, y0, x1, y1, x2,
     * y2, ...]).}
     * @param points The input point pairs. Every {@code (2 * i, 2 * i + 1)} points are pairs.
     * @param low lowest index used to do quick sort sort
     * @param high highest index used to do quick sort
     */
    private static void quicksortX(@NonNull float[] points, int low, int high) {
        int i = low, j = high;
        int p = low + (high - low) / 2;
        float pivot = points[p * 2];
        while (i <= j) {
            while (points[i * 2 + 0] < pivot) {
                i++;
            }
            while (points[j * 2 + 0] > pivot) {
                j--;
            }

            if (i <= j) {
                swapPair(points, i, j);
                i++;
                j--;
            }
        }
        if (low < j) {
            quicksortX(points, low, j);
        }
        if (i < high) {
            quicksortX(points, i, high);
        }
    }

    private static boolean pointInsidePolygon(float x, float y, @NonNull float[] poly, int len) {
        boolean c = false;
        float testX = x;
        float testY = y;
        for (int i = 0, j = len - 1; i < len; j = i++) {
            if (((poly[i * 2 + 1] > testY) != (poly[j * 2 + 1] > testY)) && (testX <
                    (poly[j * 2 + 0] - poly[i * 2 + 0]) * (testY - poly[i * 2 + 1]) /
                            (poly[j * 2 + 1] - poly[i * 2 + 1]) + poly[i * 2 + 0])) {
                c = !c;
            }
        }
        return c;
    }

    private static void makeClockwise(@NonNull float[] polygon, int len) {
        if (polygon == null || len == 0) {
            return;
        }
        if (!isClockwise(polygon, len)) {
            reverse(polygon, len);
        }
    }

    private static boolean isClockwise(@NonNull float[] polygon, int len) {
        float sum = 0;
        float p1x = polygon[(len - 1) * 2 + 0];
        float p1y = polygon[(len - 1) * 2 + 1];
        for (int i = 0; i < len; i++) {
            float p2x = polygon[i * 2 + 0];
            float p2y = polygon[i * 2 + 1];
            sum += p1x * p2y - p2x * p1y;
            p1x = p2x;
            p1y = p2y;
        }
        return sum < 0;
    }

    private static void reverse(@NonNull float[] polygon, int len) {
        int n = len / 2;
        for (int i = 0; i < n; i++) {
            float tmp0 = polygon[i * 2 + 0];
            float tmp1 = polygon[i * 2 + 1];
            int k = len - 1 - i;
            polygon[i * 2 + 0] = polygon[k * 2 + 0];
            polygon[i * 2 + 1] = polygon[k * 2 + 1];
            polygon[k * 2 + 0] = tmp0;
            polygon[k * 2 + 1] = tmp1;
        }
    }

    /**
     * Intersects two lines in parametric form.
     */
    private static final boolean lineIntersection(float x1, float y1, float x2, float y2, float x3,
            float y3, float x4, float y4, @NonNull float[] ret) {
        float d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (d == 0.000f) {
            return false;
        }

        float dx = (x1 * y2 - y1 * x2);
        float dy = (x3 * y4 - y3 * x4);
        float x = (dx * (x3 - x4) - (x1 - x2) * dy) / d;
        float y = (dx * (y3 - y4) - (y1 - y2) * dy) / d;

        if (((x - x1) * (x - x2) > 0.0000001) || ((x - x3) * (x - x4) > 0.0000001) ||
                ((y - y1) * (y - y2) > 0.0000001) || ((y - y3) * (y - y4) > 0.0000001)) {
            return false;
        }
        ret[0] = x;
        ret[1] = y;
        return true;
    }

    @NonNull
    public static float[] calculateLight(float size, int points, float x, float y, float height) {
        float[] ret = new float[points * 3];
        for (int i = 0; i < points; i++) {
            double angle = 2 * i * Math.PI / points;
            ret[i * 3 + 0] = (float) Math.cos(angle) * size + x;
            ret[i * 3 + 1] = (float) Math.sin(angle) * size + y;
            ret[i * 3 + 2] = (height);
        }
        return ret;
    }

    /**
     layers indicates the number of circular strips to generate.
     Strength is how dark a shadow to generate.

    /**
     * This calculates a collection of triangles that represent the shadow cast by a polygonal
     * light source (lightPoly) hitting a convex polygon (poly).
     * @param lightPoly The flatten 3d coordinates of light
     * @param lightPolyLength The vertices number of light polygon.
     * @param poly The flatten 3d coordinates of item
     * @param polyLength The vertices number of light polygon.
     * @param rays defines the number of points around the perimeter of the shadow to generate
     * @param layers The number of shadow's fading.
     * @param strength The factor of the black color of shadow.
     * @param retStrips Used to store the calculated shadow strength
     * @return true if the params is able to calculate a shadow, else false.
     */
    public static boolean calcShadow(@NonNull float[] lightPoly, int lightPolyLength,
            @NonNull float[] poly, int polyLength, int rays, int layers, float strength,
            @NonNull float[] retStrips) {
        float[] shadowRegion = new float[lightPolyLength * polyLength * 2];
        float[] outline = new float[polyLength * 2];
        float[] umbra = new float[polyLength * lightPolyLength * 2];
        int umbraLength = 0;

        int k = 0;
        for (int j = 0; j < lightPolyLength; j++) {
            int m = 0;
            for (int i = 0; i < polyLength; i++) {
                float t = lightPoly[j * 3 + 2] - poly[i * 3 + 2];
                if (t == 0) {
                    return false;
                }
                t = lightPoly[j * 3 + 2] / t;
                float x = lightPoly[j * 3 + 0] - t * (lightPoly[j * 3 + 0] - poly[i * 3 + 0]);
                float y = lightPoly[j * 3 + 1] - t * (lightPoly[j * 3 + 1] - poly[i * 3 + 1]);

                shadowRegion[k * 2 + 0] = x;
                shadowRegion[k * 2 + 1] = y;
                outline[m * 2 + 0] = x;
                outline[m * 2 + 1] = y;

                k++;
                m++;
            }
            if (umbraLength == 0) {
                System.arraycopy(outline, 0, umbra, 0, polyLength);
                umbraLength = polyLength;
            } else {
                umbraLength = intersection(outline, polyLength, umbra, umbraLength);
                if (umbraLength == 0) {
                    break;
                }
            }
        }
        int shadowRegionLength = k;

        float[] penumbra = new float[k * 2];
        int penumbraLength = hull(shadowRegion, shadowRegionLength, penumbra);
        if (umbraLength < 3) {// no real umbra make a fake one
            float[] p = new float[3];
            centroid3d(lightPoly, lightPolyLength, p);
            float[] centShadow = new float[polyLength * 2];
            for (int i = 0; i < polyLength; i++) {
                float t = p[2] - poly[i * 3 + 2];
                if (t == 0) {
                    return false;
                }
                t = p[2] / t;
                float x = p[0] - t * (p[0] - poly[i * 3 + 0]);
                float y = p[1] - t * (p[1] - poly[i * 3 + 1]);

                centShadow[i * 2 + 0] = x;
                centShadow[i * 2 + 1] = y;
            }
            float[] c = new float[2];
            centroid2d(centShadow, polyLength, c);
            for (int i = 0; i < polyLength; i++) {
                centShadow[i * 2 + 0] = (c[0] * 9 + centShadow[i * 2 + 0]) / 10;
                centShadow[i * 2 + 1] = (c[1] * 9 + centShadow[i * 2 + 1]) / 10;
            }
            umbra = centShadow; // fake umbra
            umbraLength = polyLength; // same size as the original polygon
        }

        triangulateConcentricPolygon(penumbra, penumbraLength, umbra, umbraLength, rays, layers,
                strength, retStrips);
        return true;
    }

    /**
     * triangulate concentric circles.
     * This takes the inner and outer polygons of the umbra and penumbra and triangulates it.
     * @param penumbra The 2d flatten vertices of penumbra polygons.
     * @param penumbraLength The number of vertices in penumbra.
     * @param umbra The 2d flatten vertices of umbra polygons.
     * @param umbraLength The number of vertices in umbra.
     * @param rays defines the number of points around the perimeter of the shadow to generate
     * @param layers The number of shadow's fading.
     * @param strength The factor of the black color of shadow.
     * @param retStrips Used to store the calculated shadow strength.
     */
    private static void triangulateConcentricPolygon(@NonNull float[] penumbra, int penumbraLength,
            @NonNull float[] umbra, int umbraLength, int rays, int layers, float strength,
            @NonNull float[] retStrips) {
        int rings = layers + 1;
        double step = Math.PI * 2 / rays;
        float[] retXY = new float[2];
        centroid2d(umbra, umbraLength, retXY);
        float cx = retXY[0];
        float cy = retXY[1];

        float[] t1 = new float[rays];
        float[] t2 = new float[rays];

        for (int i = 0; i < rays; i++) {
            float dx = (float) Math.cos(Math.PI / 4 + step * i);
            float dy = (float) Math.sin(Math.PI / 4 + step * i);
            t2[i] = rayIntersectPoly(umbra, umbraLength, cx, cy, dx, dy);
            t1[i] = rayIntersectPoly(penumbra, penumbraLength, cx, cy, dx, dy);
        }

        int p = 0;
        // Calculate the vertex
        for (int r = 0; r < layers; r++) {
            int startP = p;
            for (int i = 0; i < rays; i++) {
                float dx = (float) Math.cos(Math.PI / 4 + step * i);
                float dy = (float) Math.sin(Math.PI / 4 + step * i);

                for (int j = r; j < (r + 2); j++) {
                    float jf = j / (float) (rings - 1);
                    float t = t1[i] + jf * (t2[i] - t1[i]);
                    float op = (jf + 1 - 1 / (1 + (t - t1[i]) * (t - t1[i]))) / 2;
                    retStrips[p * 3 + 0] = dx * t + cx;
                    retStrips[p * 3 + 1] = dy * t + cy;
                    retStrips[p * 3 + 2] = jf * op * strength;
                    p++;

                }
            }
            retStrips[p * 3 + 0] = retStrips[startP * 3 + 0];
            retStrips[p * 3 + 1] = retStrips[startP * 3 + 1];
            retStrips[p * 3 + 2] = retStrips[startP * 3 + 2];
            p++;
            startP++;
            retStrips[p * 3 + 0] = retStrips[startP * 3 + 0];
            retStrips[p * 3 + 1] = retStrips[startP * 3 + 1];
            retStrips[p * 3 + 2] = retStrips[startP * 3 + 2];
            p++;
        }
        int oldP = p - 1;
        retStrips[p * 3 + 0] = retStrips[oldP * 3 + 0];
        retStrips[p * 3 + 1] = retStrips[oldP * 3 + 1];
        retStrips[p * 3 + 2] = retStrips[oldP * 3 + 2];
        p++;

        // Skip the first point here, then make it same as last point later.
        oldP = p;
        p++;
        for (int k = 0; k < rays; k++) {
            int i = k / 2;
            if ((k & 1) == 1) { // traverse the inside in a zig zag pattern
                // for strips
                i = rays - i - 1;
            }
            float dx = (float) Math.cos(Math.PI / 4 + step * i);
            float dy = (float) Math.sin(Math.PI / 4 + step * i);

            float jf = 1;

            float t = t1[i] + jf * (t2[i] - t1[i]);
            float op = (jf + 1 - 1 / (1 + (t - t1[i]) * (t - t1[i]))) / 2;

            retStrips[p * 3 + 0] = dx * t + cx;
            retStrips[p * 3 + 1] = dy * t + cy;
            retStrips[p * 3 + 2] = jf * op * strength;
            p++;
        }
        p = oldP;
        retStrips[p * 3 + 0] = retStrips[oldP * 3 + 0];
        retStrips[p * 3 + 1] = retStrips[oldP * 3 + 1];
        retStrips[p * 3 + 2] = retStrips[oldP * 3 + 2];
    }

    public static int getStripSize(int rays, int layers) {
        return (2 + rays + ((layers) * 2 * (rays + 1)));
    }
}
