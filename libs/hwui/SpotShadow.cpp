/*
 * Copyright (C) 2014 The Android Open Source Project
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

#define LOG_TAG "OpenGLRenderer"

#define SHADOW_SHRINK_SCALE 0.1f
#define CASTER_Z_CAP_RATIO 0.95f
#define FAKE_UMBRA_SIZE_RATIO 0.01f
#define OCLLUDED_UMBRA_SHRINK_FACTOR 0.95f

#include <math.h>
#include <stdlib.h>
#include <utils/Log.h>

#include "ShadowTessellator.h"
#include "SpotShadow.h"
#include "Vertex.h"
#include "utils/MathUtils.h"

// TODO: After we settle down the new algorithm, we can remove the old one and
// its utility functions.
// Right now, we still need to keep it for comparison purpose and future expansion.
namespace android {
namespace uirenderer {

static const double EPSILON = 1e-7;

/**
 * For each polygon's vertex, the light center will project it to the receiver
 * as one of the outline vertex.
 * For each outline vertex, we need to store the position and normal.
 * Normal here is defined against the edge by the current vertex and the next vertex.
 */
struct OutlineData {
    Vector2 position;
    Vector2 normal;
    float radius;
};

/**
 * Calculate the angle between and x and a y coordinate.
 * The atan2 range from -PI to PI.
 */
static float angle(const Vector2& point, const Vector2& center) {
    return atan2(point.y - center.y, point.x - center.x);
}

/**
 * Calculate the intersection of a ray with the line segment defined by two points.
 *
 * Returns a negative value in error conditions.

 * @param rayOrigin The start of the ray
 * @param dx The x vector of the ray
 * @param dy The y vector of the ray
 * @param p1 The first point defining the line segment
 * @param p2 The second point defining the line segment
 * @return The distance along the ray if it intersects with the line segment, negative if otherwise
 */
static float rayIntersectPoints(const Vector2& rayOrigin, float dx, float dy,
        const Vector2& p1, const Vector2& p2) {
    // The math below is derived from solving this formula, basically the
    // intersection point should stay on both the ray and the edge of (p1, p2).
    // solve([p1x+t*(p2x-p1x)=dx*t2+px,p1y+t*(p2y-p1y)=dy*t2+py],[t,t2]);

    double divisor = (dx * (p1.y - p2.y) + dy * p2.x - dy * p1.x);
    if (divisor == 0) return -1.0f; // error, invalid divisor

#if DEBUG_SHADOW
    double interpVal = (dx * (p1.y - rayOrigin.y) + dy * rayOrigin.x - dy * p1.x) / divisor;
    if (interpVal < 0 || interpVal > 1) {
        ALOGW("rayIntersectPoints is hitting outside the segment %f", interpVal);
    }
#endif

    double distance = (p1.x * (rayOrigin.y - p2.y) + p2.x * (p1.y - rayOrigin.y) +
            rayOrigin.x * (p2.y - p1.y)) / divisor;

    return distance; // may be negative in error cases
}

/**
 * Sort points by their X coordinates
 *
 * @param points the points as a Vector2 array.
 * @param pointsLength the number of vertices of the polygon.
 */
void SpotShadow::xsort(Vector2* points, int pointsLength) {
    quicksortX(points, 0, pointsLength - 1);
}

/**
 * compute the convex hull of a collection of Points
 *
 * @param points the points as a Vector2 array.
 * @param pointsLength the number of vertices of the polygon.
 * @param retPoly pre allocated array of floats to put the vertices
 * @return the number of points in the polygon 0 if no intersection
 */
int SpotShadow::hull(Vector2* points, int pointsLength, Vector2* retPoly) {
    xsort(points, pointsLength);
    int n = pointsLength;
    Vector2 lUpper[n];
    lUpper[0] = points[0];
    lUpper[1] = points[1];

    int lUpperSize = 2;

    for (int i = 2; i < n; i++) {
        lUpper[lUpperSize] = points[i];
        lUpperSize++;

        while (lUpperSize > 2 && !ccw(
                lUpper[lUpperSize - 3].x, lUpper[lUpperSize - 3].y,
                lUpper[lUpperSize - 2].x, lUpper[lUpperSize - 2].y,
                lUpper[lUpperSize - 1].x, lUpper[lUpperSize - 1].y)) {
            // Remove the middle point of the three last
            lUpper[lUpperSize - 2].x = lUpper[lUpperSize - 1].x;
            lUpper[lUpperSize - 2].y = lUpper[lUpperSize - 1].y;
            lUpperSize--;
        }
    }

    Vector2 lLower[n];
    lLower[0] = points[n - 1];
    lLower[1] = points[n - 2];

    int lLowerSize = 2;

    for (int i = n - 3; i >= 0; i--) {
        lLower[lLowerSize] = points[i];
        lLowerSize++;

        while (lLowerSize > 2 && !ccw(
                lLower[lLowerSize - 3].x, lLower[lLowerSize - 3].y,
                lLower[lLowerSize - 2].x, lLower[lLowerSize - 2].y,
                lLower[lLowerSize - 1].x, lLower[lLowerSize - 1].y)) {
            // Remove the middle point of the three last
            lLower[lLowerSize - 2] = lLower[lLowerSize - 1];
            lLowerSize--;
        }
    }

    // output points in CW ordering
    const int total = lUpperSize + lLowerSize - 2;
    int outIndex = total - 1;
    for (int i = 0; i < lUpperSize; i++) {
        retPoly[outIndex] = lUpper[i];
        outIndex--;
    }

    for (int i = 1; i < lLowerSize - 1; i++) {
        retPoly[outIndex] = lLower[i];
        outIndex--;
    }
    // TODO: Add test harness which verify that all the points are inside the hull.
    return total;
}

/**
 * Test whether the 3 points form a counter clockwise turn.
 *
 * @return true if a right hand turn
 */
bool SpotShadow::ccw(double ax, double ay, double bx, double by,
        double cx, double cy) {
    return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax) > EPSILON;
}

/**
 * Calculates the intersection of poly1 with poly2 and put in poly2.
 * Note that both poly1 and poly2 must be in CW order already!
 *
 * @param poly1 The 1st polygon, as a Vector2 array.
 * @param poly1Length The number of vertices of 1st polygon.
 * @param poly2 The 2nd and output polygon, as a Vector2 array.
 * @param poly2Length The number of vertices of 2nd polygon.
 * @return number of vertices in output polygon as poly2.
 */
int SpotShadow::intersection(const Vector2* poly1, int poly1Length,
        Vector2* poly2, int poly2Length) {
#if DEBUG_SHADOW
    if (!ShadowTessellator::isClockwise(poly1, poly1Length)) {
        ALOGW("Poly1 is not clockwise! Intersection is wrong!");
    }
    if (!ShadowTessellator::isClockwise(poly2, poly2Length)) {
        ALOGW("Poly2 is not clockwise! Intersection is wrong!");
    }
#endif
    Vector2 poly[poly1Length * poly2Length + 2];
    int count = 0;
    int pcount = 0;

    // If one vertex from one polygon sits inside another polygon, add it and
    // count them.
    for (int i = 0; i < poly1Length; i++) {
        if (testPointInsidePolygon(poly1[i], poly2, poly2Length)) {
            poly[count] = poly1[i];
            count++;
            pcount++;

        }
    }

    int insidePoly2 = pcount;
    for (int i = 0; i < poly2Length; i++) {
        if (testPointInsidePolygon(poly2[i], poly1, poly1Length)) {
            poly[count] = poly2[i];
            count++;
        }
    }

    int insidePoly1 = count - insidePoly2;
    // If all vertices from poly1 are inside poly2, then just return poly1.
    if (insidePoly2 == poly1Length) {
        memcpy(poly2, poly1, poly1Length * sizeof(Vector2));
        return poly1Length;
    }

    // If all vertices from poly2 are inside poly1, then just return poly2.
    if (insidePoly1 == poly2Length) {
        return poly2Length;
    }

    // Since neither polygon fully contain the other one, we need to add all the
    // intersection points.
    Vector2 intersection = {0, 0};
    for (int i = 0; i < poly2Length; i++) {
        for (int j = 0; j < poly1Length; j++) {
            int poly2LineStart = i;
            int poly2LineEnd = ((i + 1) % poly2Length);
            int poly1LineStart = j;
            int poly1LineEnd = ((j + 1) % poly1Length);
            bool found = lineIntersection(
                    poly2[poly2LineStart].x, poly2[poly2LineStart].y,
                    poly2[poly2LineEnd].x, poly2[poly2LineEnd].y,
                    poly1[poly1LineStart].x, poly1[poly1LineStart].y,
                    poly1[poly1LineEnd].x, poly1[poly1LineEnd].y,
                    intersection);
            if (found) {
                poly[count].x = intersection.x;
                poly[count].y = intersection.y;
                count++;
            } else {
                Vector2 delta = poly2[i] - poly1[j];
                if (delta.lengthSquared() < EPSILON) {
                    poly[count] = poly2[i];
                    count++;
                }
            }
        }
    }

    if (count == 0) {
        return 0;
    }

    // Sort the result polygon around the center.
    Vector2 center = {0.0f, 0.0f};
    for (int i = 0; i < count; i++) {
        center += poly[i];
    }
    center /= count;
    sort(poly, count, center);

#if DEBUG_SHADOW
    // Since poly2 is overwritten as the result, we need to save a copy to do
    // our verification.
    Vector2 oldPoly2[poly2Length];
    int oldPoly2Length = poly2Length;
    memcpy(oldPoly2, poly2, sizeof(Vector2) * poly2Length);
#endif

    // Filter the result out from poly and put it into poly2.
    poly2[0] = poly[0];
    int lastOutputIndex = 0;
    for (int i = 1; i < count; i++) {
        Vector2 delta = poly[i] - poly2[lastOutputIndex];
        if (delta.lengthSquared() >= EPSILON) {
            poly2[++lastOutputIndex] = poly[i];
        } else {
            // If the vertices are too close, pick the inner one, because the
            // inner one is more likely to be an intersection point.
            Vector2 delta1 = poly[i] - center;
            Vector2 delta2 = poly2[lastOutputIndex] - center;
            if (delta1.lengthSquared() < delta2.lengthSquared()) {
                poly2[lastOutputIndex] = poly[i];
            }
        }
    }
    int resultLength = lastOutputIndex + 1;

#if DEBUG_SHADOW
    testConvex(poly2, resultLength, "intersection");
    testConvex(poly1, poly1Length, "input poly1");
    testConvex(oldPoly2, oldPoly2Length, "input poly2");

    testIntersection(poly1, poly1Length, oldPoly2, oldPoly2Length, poly2, resultLength);
#endif

    return resultLength;
}

/**
 * Sort points about a center point
 *
 * @param poly The in and out polyogon as a Vector2 array.
 * @param polyLength The number of vertices of the polygon.
 * @param center the center ctr[0] = x , ctr[1] = y to sort around.
 */
void SpotShadow::sort(Vector2* poly, int polyLength, const Vector2& center) {
    quicksortCirc(poly, 0, polyLength - 1, center);
}

/**
 * Swap points pointed to by i and j
 */
void SpotShadow::swap(Vector2* points, int i, int j) {
    Vector2 temp = points[i];
    points[i] = points[j];
    points[j] = temp;
}

/**
 * quick sort implementation about the center.
 */
void SpotShadow::quicksortCirc(Vector2* points, int low, int high,
        const Vector2& center) {
    int i = low, j = high;
    int p = low + (high - low) / 2;
    float pivot = angle(points[p], center);
    while (i <= j) {
        while (angle(points[i], center) > pivot) {
            i++;
        }
        while (angle(points[j], center) < pivot) {
            j--;
        }

        if (i <= j) {
            swap(points, i, j);
            i++;
            j--;
        }
    }
    if (low < j) quicksortCirc(points, low, j, center);
    if (i < high) quicksortCirc(points, i, high, center);
}

/**
 * Sort points by x axis
 *
 * @param points points to sort
 * @param low start index
 * @param high end index
 */
void SpotShadow::quicksortX(Vector2* points, int low, int high) {
    int i = low, j = high;
    int p = low + (high - low) / 2;
    float pivot = points[p].x;
    while (i <= j) {
        while (points[i].x < pivot) {
            i++;
        }
        while (points[j].x > pivot) {
            j--;
        }

        if (i <= j) {
            swap(points, i, j);
            i++;
            j--;
        }
    }
    if (low < j) quicksortX(points, low, j);
    if (i < high) quicksortX(points, i, high);
}

/**
 * Test whether a point is inside the polygon.
 *
 * @param testPoint the point to test
 * @param poly the polygon
 * @return true if the testPoint is inside the poly.
 */
bool SpotShadow::testPointInsidePolygon(const Vector2 testPoint,
        const Vector2* poly, int len) {
    bool c = false;
    double testx = testPoint.x;
    double testy = testPoint.y;
    for (int i = 0, j = len - 1; i < len; j = i++) {
        double startX = poly[j].x;
        double startY = poly[j].y;
        double endX = poly[i].x;
        double endY = poly[i].y;

        if (((endY > testy) != (startY > testy)) &&
            (testx < (startX - endX) * (testy - endY)
             / (startY - endY) + endX)) {
            c = !c;
        }
    }
    return c;
}

/**
 * Make the polygon turn clockwise.
 *
 * @param polygon the polygon as a Vector2 array.
 * @param len the number of points of the polygon
 */
void SpotShadow::makeClockwise(Vector2* polygon, int len) {
    if (polygon == 0  || len == 0) {
        return;
    }
    if (!ShadowTessellator::isClockwise(polygon, len)) {
        reverse(polygon, len);
    }
}

/**
 * Reverse the polygon
 *
 * @param polygon the polygon as a Vector2 array
 * @param len the number of points of the polygon
 */
void SpotShadow::reverse(Vector2* polygon, int len) {
    int n = len / 2;
    for (int i = 0; i < n; i++) {
        Vector2 tmp = polygon[i];
        int k = len - 1 - i;
        polygon[i] = polygon[k];
        polygon[k] = tmp;
    }
}

/**
 * Intersects two lines in parametric form. This function is called in a tight
 * loop, and we need double precision to get things right.
 *
 * @param x1 the x coordinate point 1 of line 1
 * @param y1 the y coordinate point 1 of line 1
 * @param x2 the x coordinate point 2 of line 1
 * @param y2 the y coordinate point 2 of line 1
 * @param x3 the x coordinate point 1 of line 2
 * @param y3 the y coordinate point 1 of line 2
 * @param x4 the x coordinate point 2 of line 2
 * @param y4 the y coordinate point 2 of line 2
 * @param ret the x,y location of the intersection
 * @return true if it found an intersection
 */
inline bool SpotShadow::lineIntersection(double x1, double y1, double x2, double y2,
        double x3, double y3, double x4, double y4, Vector2& ret) {
    double d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
    if (d == 0.0) return false;

    double dx = (x1 * y2 - y1 * x2);
    double dy = (x3 * y4 - y3 * x4);
    double x = (dx * (x3 - x4) - (x1 - x2) * dy) / d;
    double y = (dx * (y3 - y4) - (y1 - y2) * dy) / d;

    // The intersection should be in the middle of the point 1 and point 2,
    // likewise point 3 and point 4.
    if (((x - x1) * (x - x2) > EPSILON)
        || ((x - x3) * (x - x4) > EPSILON)
        || ((y - y1) * (y - y2) > EPSILON)
        || ((y - y3) * (y - y4) > EPSILON)) {
        // Not interesected
        return false;
    }
    ret.x = x;
    ret.y = y;
    return true;

}

/**
 * Compute a horizontal circular polygon about point (x , y , height) of radius
 * (size)
 *
 * @param points number of the points of the output polygon.
 * @param lightCenter the center of the light.
 * @param size the light size.
 * @param ret result polygon.
 */
void SpotShadow::computeLightPolygon(int points, const Vector3& lightCenter,
        float size, Vector3* ret) {
    // TODO: Caching all the sin / cos values and store them in a look up table.
    for (int i = 0; i < points; i++) {
        double angle = 2 * i * M_PI / points;
        ret[i].x = cosf(angle) * size + lightCenter.x;
        ret[i].y = sinf(angle) * size + lightCenter.y;
        ret[i].z = lightCenter.z;
    }
}

/**
* Generate the shadow from a spot light.
*
* @param poly x,y,z vertexes of a convex polygon that occludes the light source
* @param polyLength number of vertexes of the occluding polygon
* @param lightCenter the center of the light
* @param lightSize the radius of the light source
* @param lightVertexCount the vertex counter for the light polygon
* @param shadowTriangleStrip return an (x,y,alpha) triangle strip representing the shadow. Return
*                            empty strip if error.
*
*/

void SpotShadow::createSpotShadow_old(bool isCasterOpaque, const Vector3* poly,
        int polyLength, const Vector3& lightCenter, float lightSize,
        int lightVertexCount, VertexBuffer& retStrips) {
    Vector3 light[lightVertexCount * 3];
    computeLightPolygon(lightVertexCount, lightCenter, lightSize, light);
    computeSpotShadow_old(isCasterOpaque, light, lightVertexCount, lightCenter, poly,
            polyLength, retStrips);
}

/**
 * Generate the shadow spot light of shape lightPoly and a object poly
 *
 * @param lightPoly x,y,z vertex of a convex polygon that is the light source
 * @param lightPolyLength number of vertexes of the light source polygon
 * @param poly x,y,z vertexes of a convex polygon that occludes the light source
 * @param polyLength number of vertexes of the occluding polygon
 * @param shadowTriangleStrip return an (x,y,alpha) triangle strip representing the shadow. Return
 *                            empty strip if error.
 */
void SpotShadow::computeSpotShadow_old(bool isCasterOpaque, const Vector3* lightPoly,
        int lightPolyLength, const Vector3& lightCenter, const Vector3* poly, int polyLength,
        VertexBuffer& shadowTriangleStrip) {
    // Point clouds for all the shadowed vertices
    Vector2 shadowRegion[lightPolyLength * polyLength];
    // Shadow polygon from one point light.
    Vector2 outline[polyLength];
    Vector2 umbraMem[polyLength * lightPolyLength];
    Vector2* umbra = umbraMem;

    int umbraLength = 0;

    // Validate input, receiver is always at z = 0 plane.
    bool inputPolyPositionValid = true;
    for (int i = 0; i < polyLength; i++) {
        if (poly[i].z >= lightPoly[0].z) {
            inputPolyPositionValid = false;
            ALOGW("polygon above the light");
            break;
        }
    }

    // If the caster's position is invalid, don't draw anything.
    if (!inputPolyPositionValid) {
        return;
    }

    // Calculate the umbra polygon based on intersections of all outlines
    int k = 0;
    for (int j = 0; j < lightPolyLength; j++) {
        int m = 0;
        for (int i = 0; i < polyLength; i++) {
            // After validating the input, deltaZ is guaranteed to be positive.
            float deltaZ = lightPoly[j].z - poly[i].z;
            float ratioZ = lightPoly[j].z / deltaZ;
            float x = lightPoly[j].x - ratioZ * (lightPoly[j].x - poly[i].x);
            float y = lightPoly[j].y - ratioZ * (lightPoly[j].y - poly[i].y);

            Vector2 newPoint = {x, y};
            shadowRegion[k] = newPoint;
            outline[m] = newPoint;

            k++;
            m++;
        }

        // For the first light polygon's vertex, use the outline as the umbra.
        // Later on, use the intersection of the outline and existing umbra.
        if (umbraLength == 0) {
            for (int i = 0; i < polyLength; i++) {
                umbra[i] = outline[i];
            }
            umbraLength = polyLength;
        } else {
            int col = ((j * 255) / lightPolyLength);
            umbraLength = intersection(outline, polyLength, umbra, umbraLength);
            if (umbraLength == 0) {
                break;
            }
        }
    }

    // Generate the penumbra area using the hull of all shadow regions.
    int shadowRegionLength = k;
    Vector2 penumbra[k];
    int penumbraLength = hull(shadowRegion, shadowRegionLength, penumbra);

    Vector2 fakeUmbra[polyLength];
    if (umbraLength < 3) {
        // If there is no real umbra, make a fake one.
        for (int i = 0; i < polyLength; i++) {
            float deltaZ = lightCenter.z - poly[i].z;
            float ratioZ = lightCenter.z / deltaZ;
            float x = lightCenter.x - ratioZ * (lightCenter.x - poly[i].x);
            float y = lightCenter.y - ratioZ * (lightCenter.y - poly[i].y);

            fakeUmbra[i].x = x;
            fakeUmbra[i].y = y;
        }

        // Shrink the centroid's shadow by 10%.
        // TODO: Study the magic number of 10%.
        Vector2 shadowCentroid =
                ShadowTessellator::centroid2d(fakeUmbra, polyLength);
        for (int i = 0; i < polyLength; i++) {
            fakeUmbra[i] = shadowCentroid * (1.0f - SHADOW_SHRINK_SCALE) +
                    fakeUmbra[i] * SHADOW_SHRINK_SCALE;
        }
#if DEBUG_SHADOW
        ALOGD("No real umbra make a fake one, centroid2d =  %f , %f",
                shadowCentroid.x, shadowCentroid.y);
#endif
        // Set the fake umbra, whose size is the same as the original polygon.
        umbra = fakeUmbra;
        umbraLength = polyLength;
    }

    generateTriangleStrip(isCasterOpaque, 1.0, penumbra, penumbraLength, umbra,
            umbraLength, poly, polyLength, shadowTriangleStrip);
}

float SpotShadow::projectCasterToOutline(Vector2& outline,
        const Vector3& lightCenter, const Vector3& polyVertex) {
    float lightToPolyZ = lightCenter.z - polyVertex.z;
    float ratioZ = CASTER_Z_CAP_RATIO;
    if (lightToPolyZ != 0) {
        // If any caster's vertex is almost above the light, we just keep it as 95%
        // of the height of the light.
        ratioZ = MathUtils::min(polyVertex.z / lightToPolyZ, CASTER_Z_CAP_RATIO);
    }

    outline.x = polyVertex.x - ratioZ * (lightCenter.x - polyVertex.x);
    outline.y = polyVertex.y - ratioZ * (lightCenter.y - polyVertex.y);
    return ratioZ;
}

/**
 * Generate the shadow spot light of shape lightPoly and a object poly
 *
 * @param isCasterOpaque whether the caster is opaque
 * @param lightCenter the center of the light
 * @param lightSize the radius of the light
 * @param poly x,y,z vertexes of a convex polygon that occludes the light source
 * @param polyLength number of vertexes of the occluding polygon
 * @param shadowTriangleStrip return an (x,y,alpha) triangle strip representing the shadow. Return
 *                            empty strip if error.
 */
void SpotShadow::createSpotShadow(bool isCasterOpaque, const Vector3& lightCenter,
        float lightSize, const Vector3* poly, int polyLength, const Vector3& polyCentroid,
        VertexBuffer& shadowTriangleStrip) {
    OutlineData outlineData[polyLength];
    Vector2 outlineCentroid;
    // Calculate the projected outline for each polygon's vertices from the light center.
    //
    //                       O     Light
    //                      /
    //                    /
    //                   .     Polygon vertex
    //                 /
    //               /
    //              O     Outline vertices
    //
    // Ratio = (Poly - Outline) / (Light - Poly)
    // Outline.x = Poly.x - Ratio * (Light.x - Poly.x)
    // Outline's radius / Light's radius = Ratio

    // Compute the last outline vertex to make sure we can get the normal and outline
    // in one single loop.
    projectCasterToOutline(outlineData[polyLength - 1].position, lightCenter,
            poly[polyLength - 1]);

    // Take the outline's polygon, calculate the normal for each outline edge.
    int currentNormalIndex = polyLength - 1;
    int nextNormalIndex = 0;

    for (int i = 0; i < polyLength; i++) {
        float ratioZ = projectCasterToOutline(outlineData[i].position,
                lightCenter, poly[i]);
        outlineData[i].radius = ratioZ * lightSize;

        outlineData[currentNormalIndex].normal = ShadowTessellator::calculateNormal(
                outlineData[currentNormalIndex].position,
                outlineData[nextNormalIndex].position);
        currentNormalIndex = (currentNormalIndex + 1) % polyLength;
        nextNormalIndex++;
    }

    projectCasterToOutline(outlineCentroid, lightCenter, polyCentroid);

    int penumbraIndex = 0;
    int penumbraLength = polyLength * 3;
    Vector2 penumbra[penumbraLength];

    Vector2 umbra[polyLength];
    float distOutline = 0;
    float ratioVI = 0;

    bool hasValidUmbra = true;
    // We need the maxRatioVI to decrease the spot shadow strength accordingly.
    float maxRaitoVI = 1.0;

    for (int i = 0; i < polyLength; i++) {
        // Generate all the penumbra's vertices only using the (outline vertex + normal * radius)
        // There is no guarantee that the penumbra is still convex, but for
        // each outline vertex, it will connect to all its corresponding penumbra vertices as
        // triangle fans. And for neighber penumbra vertex, it will be a trapezoid.
        //
        // Penumbra Vertices marked as Pi
        // Outline Vertices marked as Vi
        //                                            (P3)
        //          (P2)                               |     ' (P4)
        //   (P1)'   |                                 |   '
        //         ' |                                 | '
        // (P0)  ------------------------------------------------(P5)
        //           | (V0)                            |(V1)
        //           |                                 |
        //           |                                 |
        //           |                                 |
        //           |                                 |
        //           |                                 |
        //           |                                 |
        //           |                                 |
        //           |                                 |
        //       (V3)-----------------------------------(V2)
        int preNormalIndex = (i + polyLength - 1) % polyLength;
        penumbra[penumbraIndex++] = outlineData[i].position +
            outlineData[preNormalIndex].normal * outlineData[i].radius;

        int currentNormalIndex = i;
        // (TODO) Depending on how roundness we want for each corner, we can subdivide
        // further here and/or introduce some heuristic to decide how much the
        // subdivision should be.
        Vector2 avgNormal =
            (outlineData[preNormalIndex].normal + outlineData[currentNormalIndex].normal) / 2;

        penumbra[penumbraIndex++] = outlineData[i].position +
            avgNormal * outlineData[i].radius;

        penumbra[penumbraIndex++] = outlineData[i].position +
            outlineData[currentNormalIndex].normal * outlineData[i].radius;

        // Compute the umbra by the intersection from the outline's centroid!
        //
        //       (V) ------------------------------------
        //           |          '                       |
        //           |         '                        |
        //           |       ' (I)                      |
        //           |    '                             |
        //           | '             (C)                |
        //           |                                  |
        //           |                                  |
        //           |                                  |
        //           |                                  |
        //           ------------------------------------
        //
        // Connect a line b/t the outline vertex (V) and the centroid (C), it will
        // intersect with the outline vertex's circle at point (I).
        // Now, ratioVI = VI / VC, ratioIC = IC / VC
        // Then the intersetion point can be computed as Ixy = Vxy * ratioIC + Cxy * ratioVI;
        //
        // When one of the outline circle cover the the outline centroid, (like I is
        // on the other side of C), there is no real umbra any more, so we just fake
        // a small area around the centroid as the umbra, and tune down the spot
        // shadow's umbra strength to simulate the effect the whole shadow will
        // become lighter in this case.
        // The ratio can be simulated by using the inverse of maximum of ratioVI for
        // all (V).
        distOutline = (outlineData[i].position - outlineCentroid).length();
        if (distOutline == 0) {
            // If the outline has 0 area, then there is no spot shadow anyway.
            ALOGW("Outline has 0 area, no spot shadow!");
            return;
        }
        ratioVI = outlineData[i].radius / distOutline;
        if (ratioVI >= 1.0) {
            maxRaitoVI = ratioVI;
            hasValidUmbra = false;
        }
        // When we know we don't have valid umbra, don't bother to compute the
        // values below. But we can't skip the loop yet since we want to know the
        // maximum ratio.
        if (hasValidUmbra) {
            float ratioIC = (distOutline - outlineData[i].radius) / distOutline;
            umbra[i] = outlineData[i].position * ratioIC + outlineCentroid * ratioVI;
        }
    }

    float shadowStrengthScale = 1.0;
    if (!hasValidUmbra) {
        ALOGW("The object is too close to the light or too small, no real umbra!");
        for (int i = 0; i < polyLength; i++) {
            umbra[i] = outlineData[i].position * FAKE_UMBRA_SIZE_RATIO +
                outlineCentroid * (1 - FAKE_UMBRA_SIZE_RATIO);
        }
        shadowStrengthScale = 1.0 / maxRaitoVI;
    }

#if DEBUG_SHADOW
    dumpPolygon(poly, polyLength, "input poly");
    dumpPolygon(outline, polyLength, "outline");
    dumpPolygon(penumbra, penumbraLength, "penumbra");
    dumpPolygon(umbra, polyLength, "umbra");
    ALOGD("hasValidUmbra is %d and shadowStrengthScale is %f", hasValidUmbra, shadowStrengthScale);
#endif

    generateTriangleStrip(isCasterOpaque, shadowStrengthScale, penumbra,
            penumbraLength, umbra, polyLength, poly, polyLength, shadowTriangleStrip);
}

/**
 * Converts a polygon specified with CW vertices into an array of distance-from-centroid values.
 *
 * Returns false in error conditions
 *
 * @param poly Array of vertices. Note that these *must* be CW.
 * @param polyLength The number of vertices in the polygon.
 * @param polyCentroid The centroid of the polygon, from which rays will be cast
 * @param rayDist The output array for the calculated distances, must be SHADOW_RAY_COUNT in size
 */
bool convertPolyToRayDist(const Vector2* poly, int polyLength, const Vector2& polyCentroid,
        float* rayDist) {
    const int rays = SHADOW_RAY_COUNT;
    const float step = M_PI * 2 / rays;

    const Vector2* lastVertex = &(poly[polyLength - 1]);
    float startAngle = angle(*lastVertex, polyCentroid);

    // Start with the ray that's closest to and less than startAngle
    int rayIndex = floor((startAngle - EPSILON) / step);
    rayIndex = (rayIndex + rays) % rays; // ensure positive

    for (int polyIndex = 0; polyIndex < polyLength; polyIndex++) {
        /*
         * For a given pair of vertices on the polygon, poly[i-1] and poly[i], the rays that
         * intersect these will be those that are between the two angles from the centroid that the
         * vertices define.
         *
         * Because the polygon vertices are stored clockwise, the closest ray with an angle
         * *smaller* than that defined by angle(poly[i], centroid) will be the first ray that does
         * not intersect with poly[i-1], poly[i].
         */
        float currentAngle = angle(poly[polyIndex], polyCentroid);

        // find first ray that will not intersect the line segment poly[i-1] & poly[i]
        int firstRayIndexOnNextSegment = floor((currentAngle - EPSILON) / step);
        firstRayIndexOnNextSegment = (firstRayIndexOnNextSegment + rays) % rays; // ensure positive

        // Iterate through all rays that intersect with poly[i-1], poly[i] line segment.
        // This may be 0 rays.
        while (rayIndex != firstRayIndexOnNextSegment) {
            float distanceToIntersect = rayIntersectPoints(polyCentroid,
                    cos(rayIndex * step),
                    sin(rayIndex * step),
                    *lastVertex, poly[polyIndex]);
            if (distanceToIntersect < 0) {
#if DEBUG_SHADOW
                ALOGW("ERROR: convertPolyToRayDist failed");
#endif
                return false; // error case, abort
            }

            rayDist[rayIndex] = distanceToIntersect;

            rayIndex = (rayIndex - 1 + rays) % rays;
        }
        lastVertex = &poly[polyIndex];
    }

   return true;
}

int SpotShadow::calculateOccludedUmbra(const Vector2* umbra, int umbraLength,
        const Vector3* poly, int polyLength, Vector2* occludedUmbra) {
    // Occluded umbra area is computed as the intersection of the projected 2D
    // poly and umbra.
    for (int i = 0; i < polyLength; i++) {
        occludedUmbra[i].x = poly[i].x;
        occludedUmbra[i].y = poly[i].y;
    }

    // Both umbra and incoming polygon are guaranteed to be CW, so we can call
    // intersection() directly.
    return intersection(umbra, umbraLength,
            occludedUmbra, polyLength);
}

/**
 * Generate a triangle strip given two convex polygons
 *
 * @param penumbra The outer polygon x,y vertexes
 * @param penumbraLength The number of vertexes in the outer polygon
 * @param umbra The inner outer polygon x,y vertexes
 * @param umbraLength The number of vertexes in the inner polygon
 * @param shadowTriangleStrip return an (x,y,alpha) triangle strip representing the shadow. Return
 *                            empty strip if error.
**/
void SpotShadow::generateTriangleStrip(bool isCasterOpaque, float shadowStrengthScale,
        const Vector2* penumbra, int penumbraLength, const Vector2* umbra, int umbraLength,
        const Vector3* poly, int polyLength, VertexBuffer& shadowTriangleStrip) {
    const int rays = SHADOW_RAY_COUNT;
    const int size = 2 * rays;
    const float step = M_PI * 2 / rays;
    // Centroid of the umbra.
    Vector2 centroid = ShadowTessellator::centroid2d(umbra, umbraLength);
#if DEBUG_SHADOW
    ALOGD("centroid2d =  %f , %f", centroid.x, centroid.y);
#endif
    // Intersection to the penumbra.
    float penumbraDistPerRay[rays];
    // Intersection to the umbra.
    float umbraDistPerRay[rays];
    // Intersection to the occluded umbra area.
    float occludedUmbraDistPerRay[rays];

    // convert CW polygons to ray distance encoding, aborting on conversion failure
    if (!convertPolyToRayDist(umbra, umbraLength, centroid, umbraDistPerRay)) return;
    if (!convertPolyToRayDist(penumbra, penumbraLength, centroid, penumbraDistPerRay)) return;

    bool hasOccludedUmbraArea = false;
    if (isCasterOpaque) {
        Vector2 occludedUmbra[polyLength + umbraLength];
        int occludedUmbraLength = calculateOccludedUmbra(umbra, umbraLength, poly, polyLength,
                occludedUmbra);
        // Make sure the centroid is inside the umbra, otherwise, fall back to the
        // approach as if there is no occluded umbra area.
        if (testPointInsidePolygon(centroid, occludedUmbra, occludedUmbraLength)) {
            hasOccludedUmbraArea = true;
            // Shrink the occluded umbra area to avoid pixel level artifacts.
            for (int i = 0; i < occludedUmbraLength; i ++) {
                occludedUmbra[i] = centroid + (occludedUmbra[i] - centroid) *
                        OCLLUDED_UMBRA_SHRINK_FACTOR;
            }
            if (!convertPolyToRayDist(occludedUmbra, occludedUmbraLength, centroid,
                    occludedUmbraDistPerRay)) {
                return;
            }
        }
    }
    AlphaVertex* shadowVertices =
            shadowTriangleStrip.alloc<AlphaVertex>(SHADOW_VERTEX_COUNT);

    // NOTE: Shadow alpha values are transformed when stored in alphavertices,
    // so that they can be consumed directly by gFS_Main_ApplyVertexAlphaShadowInterp
    float transformedMaxAlpha = M_PI * shadowStrengthScale;

    // Calculate the vertices (x, y, alpha) in the shadow area.
    AlphaVertex centroidXYA;
    AlphaVertex::set(&centroidXYA, centroid.x, centroid.y, transformedMaxAlpha);
    for (int rayIndex = 0; rayIndex < rays; rayIndex++) {
        float dx = cosf(step * rayIndex);
        float dy = sinf(step * rayIndex);

        // penumbra ring
        float penumbraDistance = penumbraDistPerRay[rayIndex];
        AlphaVertex::set(&shadowVertices[rayIndex],
                dx * penumbraDistance + centroid.x,
                dy * penumbraDistance + centroid.y, 0.0f);

        // umbra ring
        float umbraDistance = umbraDistPerRay[rayIndex];
        AlphaVertex::set(&shadowVertices[rays + rayIndex],
                dx * umbraDistance + centroid.x,
                dy * umbraDistance + centroid.y,
                transformedMaxAlpha);

        // occluded umbra ring
        if (hasOccludedUmbraArea) {
            float occludedUmbraDistance = occludedUmbraDistPerRay[rayIndex];
            AlphaVertex::set(&shadowVertices[2 * rays + rayIndex],
                    dx * occludedUmbraDistance + centroid.x,
                    dy * occludedUmbraDistance + centroid.y, transformedMaxAlpha);
        } else {
            // Put all vertices of the occluded umbra ring at the centroid.
            shadowVertices[2 * rays + rayIndex] = centroidXYA;
        }
    }
    shadowTriangleStrip.setMode(VertexBuffer::kTwoPolyRingShadow);
    shadowTriangleStrip.computeBounds<AlphaVertex>();
}

/**
 * This is only for experimental purpose.
 * After intersections are calculated, we could smooth the polygon if needed.
 * So far, we don't think it is more appealing yet.
 *
 * @param level The level of smoothness.
 * @param rays The total number of rays.
 * @param rayDist (In and Out) The distance for each ray.
 *
 */
void SpotShadow::smoothPolygon(int level, int rays, float* rayDist) {
    for (int k = 0; k < level; k++) {
        for (int i = 0; i < rays; i++) {
            float p1 = rayDist[(rays - 1 + i) % rays];
            float p2 = rayDist[i];
            float p3 = rayDist[(i + 1) % rays];
            rayDist[i] = (p1 + p2 * 2 + p3) / 4;
        }
    }
}

#if DEBUG_SHADOW

#define TEST_POINT_NUMBER 128

/**
 * Calculate the bounds for generating random test points.
 */
void SpotShadow::updateBound(const Vector2 inVector, Vector2& lowerBound,
        Vector2& upperBound ) {
    if (inVector.x < lowerBound.x) {
        lowerBound.x = inVector.x;
    }

    if (inVector.y < lowerBound.y) {
        lowerBound.y = inVector.y;
    }

    if (inVector.x > upperBound.x) {
        upperBound.x = inVector.x;
    }

    if (inVector.y > upperBound.y) {
        upperBound.y = inVector.y;
    }
}

/**
 * For debug purpose, when things go wrong, dump the whole polygon data.
 */
void SpotShadow::dumpPolygon(const Vector2* poly, int polyLength, const char* polyName) {
    for (int i = 0; i < polyLength; i++) {
        ALOGD("polygon %s i %d x %f y %f", polyName, i, poly[i].x, poly[i].y);
    }
}

/**
 * For debug purpose, when things go wrong, dump the whole polygon data.
 */
void SpotShadow::dumpPolygon(const Vector3* poly, int polyLength, const char* polyName) {
    for (int i = 0; i < polyLength; i++) {
        ALOGD("polygon %s i %d x %f y %f", polyName, i, poly[i].x, poly[i].y);
    }
}

/**
 * Test whether the polygon is convex.
 */
bool SpotShadow::testConvex(const Vector2* polygon, int polygonLength,
        const char* name) {
    bool isConvex = true;
    for (int i = 0; i < polygonLength; i++) {
        Vector2 start = polygon[i];
        Vector2 middle = polygon[(i + 1) % polygonLength];
        Vector2 end = polygon[(i + 2) % polygonLength];

        double delta = (double(middle.x) - start.x) * (double(end.y) - start.y) -
                (double(middle.y) - start.y) * (double(end.x) - start.x);
        bool isCCWOrCoLinear = (delta >= EPSILON);

        if (isCCWOrCoLinear) {
            ALOGW("(Error Type 2): polygon (%s) is not a convex b/c start (x %f, y %f),"
                    "middle (x %f, y %f) and end (x %f, y %f) , delta is %f !!!",
                    name, start.x, start.y, middle.x, middle.y, end.x, end.y, delta);
            isConvex = false;
            break;
        }
    }
    return isConvex;
}

/**
 * Test whether or not the polygon (intersection) is within the 2 input polygons.
 * Using Marte Carlo method, we generate a random point, and if it is inside the
 * intersection, then it must be inside both source polygons.
 */
void SpotShadow::testIntersection(const Vector2* poly1, int poly1Length,
        const Vector2* poly2, int poly2Length,
        const Vector2* intersection, int intersectionLength) {
    // Find the min and max of x and y.
    Vector2 lowerBound = {FLT_MAX, FLT_MAX};
    Vector2 upperBound = {-FLT_MAX, -FLT_MAX};
    for (int i = 0; i < poly1Length; i++) {
        updateBound(poly1[i], lowerBound, upperBound);
    }
    for (int i = 0; i < poly2Length; i++) {
        updateBound(poly2[i], lowerBound, upperBound);
    }

    bool dumpPoly = false;
    for (int k = 0; k < TEST_POINT_NUMBER; k++) {
        // Generate a random point between minX, minY and maxX, maxY.
        double randomX = rand() / double(RAND_MAX);
        double randomY = rand() / double(RAND_MAX);

        Vector2 testPoint;
        testPoint.x = lowerBound.x + randomX * (upperBound.x - lowerBound.x);
        testPoint.y = lowerBound.y + randomY * (upperBound.y - lowerBound.y);

        // If the random point is in both poly 1 and 2, then it must be intersection.
        if (testPointInsidePolygon(testPoint, intersection, intersectionLength)) {
            if (!testPointInsidePolygon(testPoint, poly1, poly1Length)) {
                dumpPoly = true;
                ALOGW("(Error Type 1): one point (%f, %f) in the intersection is"
                      " not in the poly1",
                        testPoint.x, testPoint.y);
            }

            if (!testPointInsidePolygon(testPoint, poly2, poly2Length)) {
                dumpPoly = true;
                ALOGW("(Error Type 1): one point (%f, %f) in the intersection is"
                      " not in the poly2",
                        testPoint.x, testPoint.y);
            }
        }
    }

    if (dumpPoly) {
        dumpPolygon(intersection, intersectionLength, "intersection");
        for (int i = 1; i < intersectionLength; i++) {
            Vector2 delta = intersection[i] - intersection[i - 1];
            ALOGD("Intersetion i, %d Vs i-1 is delta %f", i, delta.lengthSquared());
        }

        dumpPolygon(poly1, poly1Length, "poly 1");
        dumpPolygon(poly2, poly2Length, "poly 2");
    }
}
#endif

}; // namespace uirenderer
}; // namespace android
