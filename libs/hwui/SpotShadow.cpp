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

// The highest z value can't be higher than (CASTER_Z_CAP_RATIO * light.z)
#define CASTER_Z_CAP_RATIO 0.95f

// When there is no umbra, then just fake the umbra using
// centroid * (1 - FAKE_UMBRA_SIZE_RATIO) + outline * FAKE_UMBRA_SIZE_RATIO
#define FAKE_UMBRA_SIZE_RATIO 0.05f

// When the polygon is about 90 vertices, the penumbra + umbra can reach 270 rays.
// That is consider pretty fine tessllated polygon so far.
// This is just to prevent using too much some memory when edge slicing is not
// needed any more.
#define FINE_TESSELLATED_POLYGON_RAY_NUMBER 270
/**
 * Extra vertices for the corner for smoother corner.
 * Only for outer loop.
 * Note that we use such extra memory to avoid an extra loop.
 */
// For half circle, we could add EXTRA_VERTEX_PER_PI vertices.
// Set to 1 if we don't want to have any.
#define SPOT_EXTRA_CORNER_VERTEX_PER_PI 18

// For the whole polygon, the sum of all the deltas b/t normals is 2 * M_PI,
// therefore, the maximum number of extra vertices will be twice bigger.
#define SPOT_MAX_EXTRA_CORNER_VERTEX_NUMBER  (2 * SPOT_EXTRA_CORNER_VERTEX_PER_PI)

// For each RADIANS_DIVISOR, we would allocate one more vertex b/t the normals.
#define SPOT_CORNER_RADIANS_DIVISOR (M_PI / SPOT_EXTRA_CORNER_VERTEX_PER_PI)


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
 * For each vertex, we need to keep track of its angle, whether it is penumbra or
 * umbra, and its corresponding vertex index.
 */
struct SpotShadow::VertexAngleData {
    // The angle to the vertex from the centroid.
    float mAngle;
    // True is the vertex comes from penumbra, otherwise it comes from umbra.
    bool mIsPenumbra;
    // The index of the vertex described by this data.
    int mVertexIndex;
    void set(float angle, bool isPenumbra, int index) {
        mAngle = angle;
        mIsPenumbra = isPenumbra;
        mVertexIndex = index;
    }
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

        if (((endY > testy) != (startY > testy))
            && (testx < (startX - endX) * (testy - endY)
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
 * From light center, project one vertex to the z=0 surface and get the outline.
 *
 * @param outline The result which is the outline position.
 * @param lightCenter The center of light.
 * @param polyVertex The input polygon's vertex.
 *
 * @return float The ratio of (polygon.z / light.z - polygon.z)
 */
float SpotShadow::projectCasterToOutline(Vector2& outline,
        const Vector3& lightCenter, const Vector3& polyVertex) {
    float lightToPolyZ = lightCenter.z - polyVertex.z;
    float ratioZ = CASTER_Z_CAP_RATIO;
    if (lightToPolyZ != 0) {
        // If any caster's vertex is almost above the light, we just keep it as 95%
        // of the height of the light.
        ratioZ = MathUtils::clamp(polyVertex.z / lightToPolyZ, 0.0f, CASTER_Z_CAP_RATIO);
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
    if (CC_UNLIKELY(lightCenter.z <= 0)) {
        ALOGW("Relative Light Z is not positive. No spot shadow!");
        return;
    }
    if (CC_UNLIKELY(polyLength < 3)) {
#if DEBUG_SHADOW
        ALOGW("Invalid polygon length. No spot shadow!");
#endif
        return;
    }
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
    // Then each polygon's vertex produce at minmal 2 penumbra vertices.
    // Since the size can be dynamic here, we keep track of the size and update
    // the real size at the end.
    int allocatedPenumbraLength = 2 * polyLength + SPOT_MAX_EXTRA_CORNER_VERTEX_NUMBER;
    Vector2 penumbra[allocatedPenumbraLength];
    int totalExtraCornerSliceNumber = 0;

    Vector2 umbra[polyLength];

    // When centroid is covered by all circles from outline, then we consider
    // the umbra is invalid, and we will tune down the shadow strength.
    bool hasValidUmbra = true;
    // We need the minimal of RaitoVI to decrease the spot shadow strength accordingly.
    float minRaitoVI = FLT_MAX;

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

        const Vector2& previousNormal = outlineData[preNormalIndex].normal;
        const Vector2& currentNormal = outlineData[i].normal;

        // Depending on how roundness we want for each corner, we can subdivide
        // further here and/or introduce some heuristic to decide how much the
        // subdivision should be.
        int currentExtraSliceNumber = ShadowTessellator::getExtraVertexNumber(
                previousNormal, currentNormal, SPOT_CORNER_RADIANS_DIVISOR);

        int currentCornerSliceNumber = 1 + currentExtraSliceNumber;
        totalExtraCornerSliceNumber += currentExtraSliceNumber;
#if DEBUG_SHADOW
        ALOGD("currentExtraSliceNumber should be %d", currentExtraSliceNumber);
        ALOGD("currentCornerSliceNumber should be %d", currentCornerSliceNumber);
        ALOGD("totalCornerSliceNumber is %d", totalExtraCornerSliceNumber);
#endif
        if (CC_UNLIKELY(totalExtraCornerSliceNumber > SPOT_MAX_EXTRA_CORNER_VERTEX_NUMBER)) {
            currentCornerSliceNumber = 1;
        }
        for (int k = 0; k <= currentCornerSliceNumber; k++) {
            Vector2 avgNormal =
                    (previousNormal * (currentCornerSliceNumber - k) + currentNormal * k) /
                    currentCornerSliceNumber;
            avgNormal.normalize();
            penumbra[penumbraIndex++] = outlineData[i].position +
                    avgNormal * outlineData[i].radius;
        }


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
        // When all of the outline circles cover the the outline centroid, (like I is
        // on the other side of C), there is no real umbra any more, so we just fake
        // a small area around the centroid as the umbra, and tune down the spot
        // shadow's umbra strength to simulate the effect the whole shadow will
        // become lighter in this case.
        // The ratio can be simulated by using the inverse of maximum of ratioVI for
        // all (V).
        float distOutline = (outlineData[i].position - outlineCentroid).length();
        if (CC_UNLIKELY(distOutline == 0)) {
            // If the outline has 0 area, then there is no spot shadow anyway.
            ALOGW("Outline has 0 area, no spot shadow!");
            return;
        }

        float ratioVI = outlineData[i].radius / distOutline;
        minRaitoVI = MathUtils::min(minRaitoVI, ratioVI);
        if (ratioVI >= (1 - FAKE_UMBRA_SIZE_RATIO)) {
            ratioVI = (1 - FAKE_UMBRA_SIZE_RATIO);
        }
        // When we know we don't have valid umbra, don't bother to compute the
        // values below. But we can't skip the loop yet since we want to know the
        // maximum ratio.
        float ratioIC = 1 - ratioVI;
        umbra[i] = outlineData[i].position * ratioIC + outlineCentroid * ratioVI;
    }

    hasValidUmbra = (minRaitoVI <= 1.0);
    float shadowStrengthScale = 1.0;
    if (!hasValidUmbra) {
#if DEBUG_SHADOW
        ALOGW("The object is too close to the light or too small, no real umbra!");
#endif
        for (int i = 0; i < polyLength; i++) {
            umbra[i] = outlineData[i].position * FAKE_UMBRA_SIZE_RATIO +
                    outlineCentroid * (1 - FAKE_UMBRA_SIZE_RATIO);
        }
        shadowStrengthScale = 1.0 / minRaitoVI;
    }

    int penumbraLength = penumbraIndex;
    int umbraLength = polyLength;

#if DEBUG_SHADOW
    ALOGD("penumbraLength is %d , allocatedPenumbraLength %d", penumbraLength, allocatedPenumbraLength);
    dumpPolygon(poly, polyLength, "input poly");
    dumpPolygon(penumbra, penumbraLength, "penumbra");
    dumpPolygon(umbra, umbraLength, "umbra");
    ALOGD("hasValidUmbra is %d and shadowStrengthScale is %f", hasValidUmbra, shadowStrengthScale);
#endif

    // The penumbra and umbra needs to be in convex shape to keep consistency
    // and quality.
    // Since we are still shooting rays to penumbra, it needs to be convex.
    // Umbra can be represented as a fan from the centroid, but visually umbra
    // looks nicer when it is convex.
    Vector2 finalUmbra[umbraLength];
    Vector2 finalPenumbra[penumbraLength];
    int finalUmbraLength = hull(umbra, umbraLength, finalUmbra);
    int finalPenumbraLength = hull(penumbra, penumbraLength, finalPenumbra);

    generateTriangleStrip(isCasterOpaque, shadowStrengthScale, finalPenumbra,
            finalPenumbraLength, finalUmbra, finalUmbraLength, poly, polyLength,
            shadowTriangleStrip, outlineCentroid);

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

/**
 * Generate a array of the angleData for either umbra or penumbra vertices.
 *
 * This array will be merged and used to guide where to shoot the rays, in clockwise order.
 *
 * @param angleDataList The result array of angle data.
 *
 * @return int The maximum angle's index in the array.
 */
int SpotShadow::setupAngleList(VertexAngleData* angleDataList,
        int polyLength, const Vector2* polygon, const Vector2& centroid,
        bool isPenumbra, const char* name) {
    float maxAngle = FLT_MIN;
    int maxAngleIndex = 0;
    for (int i = 0; i < polyLength; i++) {
        float currentAngle = angle(polygon[i], centroid);
        if (currentAngle > maxAngle) {
            maxAngle = currentAngle;
            maxAngleIndex = i;
        }
        angleDataList[i].set(currentAngle, isPenumbra, i);
#if DEBUG_SHADOW
        ALOGD("%s AngleList i %d %f", name, i, currentAngle);
#endif
    }
    return maxAngleIndex;
}

/**
 * Make sure the polygons are indeed in clockwise order.
 *
 * Possible reasons to return false: 1. The input polygon is not setup properly. 2. The hull
 * algorithm is not able to generate it properly.
 *
 * Anyway, since the algorithm depends on the clockwise, when these kind of unexpected error
 * situation is found, we need to detect it and early return without corrupting the memory.
 *
 * @return bool True if the angle list is actually from big to small.
 */
bool SpotShadow::checkClockwise(int indexOfMaxAngle, int listLength, VertexAngleData* angleList,
        const char* name) {
    int currentIndex = indexOfMaxAngle;
#if DEBUG_SHADOW
    ALOGD("max index %d", currentIndex);
#endif
    for (int i = 0; i < listLength - 1; i++) {
        // TODO: Cache the last angle.
        float currentAngle = angleList[currentIndex].mAngle;
        float nextAngle = angleList[(currentIndex + 1) % listLength].mAngle;
        if (currentAngle < nextAngle) {
#if DEBUG_SHADOW
            ALOGE("%s, is not CW, at index %d", name, currentIndex);
#endif
            return false;
        }
        currentIndex = (currentIndex + 1) % listLength;
    }
    return true;
}

/**
 * Check the polygon is clockwise.
 *
 * @return bool True is the polygon is clockwise.
 */
bool SpotShadow::checkPolyClockwise(int polyAngleLength, int maxPolyAngleIndex,
        const float* polyAngleList) {
    bool isPolyCW = true;
    // Starting from maxPolyAngleIndex , check around to make sure angle decrease.
    for (int i = 0; i < polyAngleLength - 1; i++) {
        float currentAngle = polyAngleList[(i + maxPolyAngleIndex) % polyAngleLength];
        float nextAngle = polyAngleList[(i + maxPolyAngleIndex + 1) % polyAngleLength];
        if (currentAngle < nextAngle) {
            isPolyCW = false;
        }
    }
    return isPolyCW;
}

/**
 * Given the sorted array of all the vertices angle data, calculate for each
 * vertices, the offset value to array element which represent the start edge
 * of the polygon we need to shoot the ray at.
 *
 * TODO: Calculate this for umbra and penumbra in one loop using one single array.
 *
 * @param distances The result of the array distance counter.
 */
void SpotShadow::calculateDistanceCounter(bool needsOffsetToUmbra, int angleLength,
        const VertexAngleData* allVerticesAngleData, int* distances) {

    bool firstVertexIsPenumbra = allVerticesAngleData[0].mIsPenumbra;
    // If we want distance to inner, then we just set to 0 when we see inner.
    bool needsSearch = needsOffsetToUmbra ? firstVertexIsPenumbra : !firstVertexIsPenumbra;
    int distanceCounter = 0;
    if (needsSearch) {
        int foundIndex = -1;
        for (int i = (angleLength - 1); i >= 0; i--) {
            bool currentIsOuter = allVerticesAngleData[i].mIsPenumbra;
            // If we need distance to inner, then we need to find a inner vertex.
            if (currentIsOuter != firstVertexIsPenumbra) {
                foundIndex = i;
                break;
            }
        }
        LOG_ALWAYS_FATAL_IF(foundIndex == -1, "Wrong index found, means either"
                " umbra or penumbra's length is 0");
        distanceCounter = angleLength - foundIndex;
    }
#if DEBUG_SHADOW
    ALOGD("distances[0] is %d", distanceCounter);
#endif

    distances[0] = distanceCounter; // means never see a target poly

    for (int i = 1; i < angleLength; i++) {
        bool firstVertexIsPenumbra = allVerticesAngleData[i].mIsPenumbra;
        // When we needs for distance for each outer vertex to inner, then we
        // increase the distance when seeing outer vertices. Otherwise, we clear
        // to 0.
        bool needsIncrement = needsOffsetToUmbra ? firstVertexIsPenumbra : !firstVertexIsPenumbra;
        // If counter is not -1, that means we have seen an other polygon's vertex.
        if (needsIncrement && distanceCounter != -1) {
            distanceCounter++;
        } else {
            distanceCounter = 0;
        }
        distances[i] = distanceCounter;
    }
}

/**
 * Given umbra and penumbra angle data list, merge them by sorting the angle
 * from the biggest to smallest.
 *
 * @param allVerticesAngleData The result array of merged angle data.
 */
void SpotShadow::mergeAngleList(int maxUmbraAngleIndex, int maxPenumbraAngleIndex,
        const VertexAngleData* umbraAngleList, int umbraLength,
        const VertexAngleData* penumbraAngleList, int penumbraLength,
        VertexAngleData* allVerticesAngleData) {

    int totalRayNumber = umbraLength + penumbraLength;
    int umbraIndex = maxUmbraAngleIndex;
    int penumbraIndex = maxPenumbraAngleIndex;

    float currentUmbraAngle = umbraAngleList[umbraIndex].mAngle;
    float currentPenumbraAngle = penumbraAngleList[penumbraIndex].mAngle;

    // TODO: Clean this up using a while loop with 2 iterators.
    for (int i = 0; i < totalRayNumber; i++) {
        if (currentUmbraAngle > currentPenumbraAngle) {
            allVerticesAngleData[i] = umbraAngleList[umbraIndex];
            umbraIndex = (umbraIndex + 1) % umbraLength;

            // If umbraIndex round back, that means we are running out of
            // umbra vertices to merge, so just copy all the penumbra leftover.
            // Otherwise, we update the currentUmbraAngle.
            if (umbraIndex != maxUmbraAngleIndex) {
                currentUmbraAngle = umbraAngleList[umbraIndex].mAngle;
            } else {
                for (int j = i + 1; j < totalRayNumber; j++) {
                    allVerticesAngleData[j] = penumbraAngleList[penumbraIndex];
                    penumbraIndex = (penumbraIndex + 1) % penumbraLength;
                }
                break;
            }
        } else {
            allVerticesAngleData[i] = penumbraAngleList[penumbraIndex];
            penumbraIndex = (penumbraIndex + 1) % penumbraLength;
            // If penumbraIndex round back, that means we are running out of
            // penumbra vertices to merge, so just copy all the umbra leftover.
            // Otherwise, we update the currentPenumbraAngle.
            if (penumbraIndex != maxPenumbraAngleIndex) {
                currentPenumbraAngle = penumbraAngleList[penumbraIndex].mAngle;
            } else {
                for (int j = i + 1; j < totalRayNumber; j++) {
                    allVerticesAngleData[j] = umbraAngleList[umbraIndex];
                    umbraIndex = (umbraIndex + 1) % umbraLength;
                }
                break;
            }
        }
    }
}

#if DEBUG_SHADOW
/**
 * DEBUG ONLY: Verify all the offset compuation is correctly done by examining
 * each vertex and its neighbor.
 */
static void verifyDistanceCounter(const VertexAngleData* allVerticesAngleData,
        const int* distances, int angleLength, const char* name) {
    int currentDistance = distances[0];
    for (int i = 1; i < angleLength; i++) {
        if (distances[i] != INT_MIN) {
            if (!((currentDistance + 1) == distances[i]
                || distances[i] == 0)) {
                ALOGE("Wrong distance found at i %d name %s", i, name);
            }
            currentDistance = distances[i];
            if (currentDistance != 0) {
                bool currentOuter = allVerticesAngleData[i].mIsPenumbra;
                for (int j = 1; j <= (currentDistance - 1); j++) {
                    bool neigborOuter =
                            allVerticesAngleData[(i + angleLength - j) % angleLength].mIsPenumbra;
                    if (neigborOuter != currentOuter) {
                        ALOGE("Wrong distance found at i %d name %s", i, name);
                    }
                }
                bool oppositeOuter =
                    allVerticesAngleData[(i + angleLength - currentDistance) % angleLength].mIsPenumbra;
                if (oppositeOuter == currentOuter) {
                    ALOGE("Wrong distance found at i %d name %s", i, name);
                }
            }
        }
    }
}

/**
 * DEBUG ONLY: Verify all the angle data compuated are  is correctly done
 */
static void verifyAngleData(int totalRayNumber, const VertexAngleData* allVerticesAngleData,
        const int* distancesToInner, const int* distancesToOuter,
        const VertexAngleData* umbraAngleList, int maxUmbraAngleIndex, int umbraLength,
        const VertexAngleData* penumbraAngleList, int maxPenumbraAngleIndex,
        int penumbraLength) {
    for (int i = 0; i < totalRayNumber; i++) {
        ALOGD("currentAngleList i %d, angle %f, isInner %d, index %d distancesToInner"
              " %d distancesToOuter %d", i, allVerticesAngleData[i].mAngle,
                !allVerticesAngleData[i].mIsPenumbra,
                allVerticesAngleData[i].mVertexIndex, distancesToInner[i], distancesToOuter[i]);
    }

    verifyDistanceCounter(allVerticesAngleData, distancesToInner, totalRayNumber, "distancesToInner");
    verifyDistanceCounter(allVerticesAngleData, distancesToOuter, totalRayNumber, "distancesToOuter");

    for (int i = 0; i < totalRayNumber; i++) {
        if ((distancesToInner[i] * distancesToOuter[i]) != 0) {
            ALOGE("distancesToInner wrong at index %d distancesToInner[i] %d,"
                    " distancesToOuter[i] %d", i, distancesToInner[i], distancesToOuter[i]);
        }
    }
    int currentUmbraVertexIndex =
            umbraAngleList[maxUmbraAngleIndex].mVertexIndex;
    int currentPenumbraVertexIndex =
            penumbraAngleList[maxPenumbraAngleIndex].mVertexIndex;
    for (int i = 0; i < totalRayNumber; i++) {
        if (allVerticesAngleData[i].mIsPenumbra == true) {
            if (allVerticesAngleData[i].mVertexIndex != currentPenumbraVertexIndex) {
                ALOGW("wrong penumbra indexing i %d allVerticesAngleData[i].mVertexIndex %d "
                        "currentpenumbraVertexIndex %d", i,
                        allVerticesAngleData[i].mVertexIndex, currentPenumbraVertexIndex);
            }
            currentPenumbraVertexIndex = (currentPenumbraVertexIndex + 1) % penumbraLength;
        } else {
            if (allVerticesAngleData[i].mVertexIndex != currentUmbraVertexIndex) {
                ALOGW("wrong umbra indexing i %d allVerticesAngleData[i].mVertexIndex %d "
                        "currentUmbraVertexIndex %d", i,
                        allVerticesAngleData[i].mVertexIndex, currentUmbraVertexIndex);
            }
            currentUmbraVertexIndex = (currentUmbraVertexIndex + 1) % umbraLength;
        }
    }
    for (int i = 0; i < totalRayNumber - 1; i++) {
        float currentAngle = allVerticesAngleData[i].mAngle;
        float nextAngle = allVerticesAngleData[(i + 1) % totalRayNumber].mAngle;
        if (currentAngle < nextAngle) {
            ALOGE("Unexpected angle values!, currentAngle nextAngle %f %f", currentAngle, nextAngle);
        }
    }
}
#endif

/**
 * In order to compute the occluded umbra, we need to setup the angle data list
 * for the polygon data. Since we only store one poly vertex per polygon vertex,
 * this array only needs to be a float array which are the angles for each vertex.
 *
 * @param polyAngleList The result list
 *
 * @return int The index for the maximum angle in this array.
 */
int SpotShadow::setupPolyAngleList(float* polyAngleList, int polyAngleLength,
        const Vector2* poly2d, const Vector2& centroid) {
    int maxPolyAngleIndex = -1;
    float maxPolyAngle = -FLT_MAX;
    for (int i = 0; i < polyAngleLength; i++) {
        polyAngleList[i] = angle(poly2d[i], centroid);
        if (polyAngleList[i] > maxPolyAngle) {
            maxPolyAngle = polyAngleList[i];
            maxPolyAngleIndex = i;
        }
    }
    return maxPolyAngleIndex;
}

/**
 * For umbra and penumbra, given the offset info and the current ray number,
 * find the right edge index (the (starting vertex) for the ray to shoot at.
 *
 * @return int The index of the starting vertex of the edge.
 */
inline int SpotShadow::getEdgeStartIndex(const int* offsets, int rayIndex, int totalRayNumber,
        const VertexAngleData* allVerticesAngleData) {
    int tempOffset = offsets[rayIndex];
    int targetRayIndex = (rayIndex - tempOffset + totalRayNumber) % totalRayNumber;
    return allVerticesAngleData[targetRayIndex].mVertexIndex;
}

/**
 * For the occluded umbra, given the array of angles, find the index of the
 * starting vertex of the edge, for the ray to shoo at.
 *
 * TODO: Save the last result to shorten the search distance.
 *
 * @return int The index of the starting vertex of the edge.
 */
inline int SpotShadow::getPolyEdgeStartIndex(int maxPolyAngleIndex, int polyLength,
        const float* polyAngleList, float rayAngle) {
    int minPolyAngleIndex  = (maxPolyAngleIndex + polyLength - 1) % polyLength;
    int resultIndex = -1;
    if (rayAngle > polyAngleList[maxPolyAngleIndex]
        || rayAngle <= polyAngleList[minPolyAngleIndex]) {
        resultIndex = minPolyAngleIndex;
    } else {
        for (int i = 0; i < polyLength - 1; i++) {
            int currentIndex = (maxPolyAngleIndex + i) % polyLength;
            int nextIndex = (maxPolyAngleIndex + i + 1) % polyLength;
            if (rayAngle <= polyAngleList[currentIndex]
                && rayAngle > polyAngleList[nextIndex]) {
                resultIndex = currentIndex;
            }
        }
    }
    if (CC_UNLIKELY(resultIndex == -1)) {
        // TODO: Add more error handling here.
        ALOGE("Wrong index found, means no edge can't be found for rayAngle %f", rayAngle);
    }
    return resultIndex;
}

/**
 * Convert the incoming polygons into arrays of vertices, for each ray.
 * Ray only shoots when there is one vertex either on penumbra on umbra.
 *
 * Finally, it will generate vertices per ray for umbra, penumbra and optionally
 * occludedUmbra.
 *
 * Return true (success) when all vertices are generated
 */
int SpotShadow::convertPolysToVerticesPerRay(
        bool hasOccludedUmbraArea, const Vector2* poly2d, int polyLength,
        const Vector2* umbra, int umbraLength, const Vector2* penumbra,
        int penumbraLength, const Vector2& centroid,
        Vector2* umbraVerticesPerRay, Vector2* penumbraVerticesPerRay,
        Vector2* occludedUmbraVerticesPerRay) {
    int totalRayNumber = umbraLength + penumbraLength;

    // For incoming umbra / penumbra polygons, we will build an intermediate data
    // structure to help us sort all the vertices according to the vertices.
    // Using this data structure, we can tell where (the angle) to shoot the ray,
    // whether we shoot at penumbra edge or umbra edge, and which edge to shoot at.
    //
    // We first parse each vertices and generate a table of VertexAngleData.
    // Based on that, we create 2 arrays telling us which edge to shoot at.
    VertexAngleData allVerticesAngleData[totalRayNumber];
    VertexAngleData umbraAngleList[umbraLength];
    VertexAngleData penumbraAngleList[penumbraLength];

    int polyAngleLength = hasOccludedUmbraArea ? polyLength : 0;
    float polyAngleList[polyAngleLength];

    const int maxUmbraAngleIndex =
            setupAngleList(umbraAngleList, umbraLength, umbra, centroid, false, "umbra");
    const int maxPenumbraAngleIndex =
            setupAngleList(penumbraAngleList, penumbraLength, penumbra, centroid, true, "penumbra");
    const int maxPolyAngleIndex = setupPolyAngleList(polyAngleList, polyAngleLength, poly2d, centroid);

    // Check all the polygons here are CW.
    bool isPolyCW = checkPolyClockwise(polyAngleLength, maxPolyAngleIndex, polyAngleList);
    bool isUmbraCW = checkClockwise(maxUmbraAngleIndex, umbraLength,
            umbraAngleList, "umbra");
    bool isPenumbraCW = checkClockwise(maxPenumbraAngleIndex, penumbraLength,
            penumbraAngleList, "penumbra");

    if (!isUmbraCW || !isPenumbraCW || !isPolyCW) {
#if DEBUG_SHADOW
        ALOGE("One polygon is not CW isUmbraCW %d isPenumbraCW %d isPolyCW %d",
                isUmbraCW, isPenumbraCW, isPolyCW);
#endif
        return false;
    }

    mergeAngleList(maxUmbraAngleIndex, maxPenumbraAngleIndex,
            umbraAngleList, umbraLength, penumbraAngleList, penumbraLength,
            allVerticesAngleData);

    // Calculate the offset to the left most Inner vertex for each outerVertex.
    // Then the offset to the left most Outer vertex for each innerVertex.
    int offsetToInner[totalRayNumber];
    int offsetToOuter[totalRayNumber];
    calculateDistanceCounter(true, totalRayNumber, allVerticesAngleData, offsetToInner);
    calculateDistanceCounter(false, totalRayNumber, allVerticesAngleData, offsetToOuter);

    // Generate both umbraVerticesPerRay and penumbraVerticesPerRay
    for (int i = 0; i < totalRayNumber; i++) {
        float rayAngle = allVerticesAngleData[i].mAngle;
        bool isUmbraVertex = !allVerticesAngleData[i].mIsPenumbra;

        float dx = cosf(rayAngle);
        float dy = sinf(rayAngle);
        float distanceToIntersectUmbra = -1;

        if (isUmbraVertex) {
            // We can just copy umbra easily, and calculate the distance for the
            // occluded umbra computation.
            int startUmbraIndex = allVerticesAngleData[i].mVertexIndex;
            umbraVerticesPerRay[i] = umbra[startUmbraIndex];
            if (hasOccludedUmbraArea) {
                distanceToIntersectUmbra = (umbraVerticesPerRay[i] - centroid).length();
            }

            //shoot ray to penumbra only
            int startPenumbraIndex = getEdgeStartIndex(offsetToOuter, i, totalRayNumber,
                    allVerticesAngleData);
            float distanceToIntersectPenumbra = rayIntersectPoints(centroid, dx, dy,
                    penumbra[startPenumbraIndex],
                    penumbra[(startPenumbraIndex + 1) % penumbraLength]);
            if (distanceToIntersectPenumbra < 0) {
#if DEBUG_SHADOW
                ALOGW("convertPolyToRayDist for penumbra failed rayAngle %f dx %f dy %f",
                        rayAngle, dx, dy);
#endif
                distanceToIntersectPenumbra = 0;
            }
            penumbraVerticesPerRay[i].x = centroid.x + dx * distanceToIntersectPenumbra;
            penumbraVerticesPerRay[i].y = centroid.y + dy * distanceToIntersectPenumbra;
        } else {
            // We can just copy the penumbra
            int startPenumbraIndex = allVerticesAngleData[i].mVertexIndex;
            penumbraVerticesPerRay[i] = penumbra[startPenumbraIndex];

            // And shoot ray to umbra only
            int startUmbraIndex = getEdgeStartIndex(offsetToInner, i, totalRayNumber,
                    allVerticesAngleData);

            distanceToIntersectUmbra = rayIntersectPoints(centroid, dx, dy,
                    umbra[startUmbraIndex], umbra[(startUmbraIndex + 1) % umbraLength]);
            if (distanceToIntersectUmbra < 0) {
#if DEBUG_SHADOW
                ALOGW("convertPolyToRayDist for umbra failed rayAngle %f dx %f dy %f",
                        rayAngle, dx, dy);
#endif
                distanceToIntersectUmbra = 0;
            }
            umbraVerticesPerRay[i].x = centroid.x + dx * distanceToIntersectUmbra;
            umbraVerticesPerRay[i].y = centroid.y + dy * distanceToIntersectUmbra;
        }

        if (hasOccludedUmbraArea) {
            // Shoot the same ray to the poly2d, and get the distance.
            int startPolyIndex = getPolyEdgeStartIndex(maxPolyAngleIndex, polyLength,
                    polyAngleList, rayAngle);

            float distanceToIntersectPoly = rayIntersectPoints(centroid, dx, dy,
                    poly2d[startPolyIndex], poly2d[(startPolyIndex + 1) % polyLength]);
            if (distanceToIntersectPoly < 0) {
                distanceToIntersectPoly = 0;
            }
            distanceToIntersectPoly = MathUtils::min(distanceToIntersectUmbra, distanceToIntersectPoly);
            occludedUmbraVerticesPerRay[i].x = centroid.x + dx * distanceToIntersectPoly;
            occludedUmbraVerticesPerRay[i].y = centroid.y + dy * distanceToIntersectPoly;
        }
    }

#if DEBUG_SHADOW
    verifyAngleData(totalRayNumber, allVerticesAngleData, offsetToInner,
            offsetToOuter,  umbraAngleList, maxUmbraAngleIndex,  umbraLength,
            penumbraAngleList,  maxPenumbraAngleIndex, penumbraLength);
#endif
    return true; // success

}

/**
 * Generate a triangle strip given two convex polygon
**/
void SpotShadow::generateTriangleStrip(bool isCasterOpaque, float shadowStrengthScale,
        Vector2* penumbra, int penumbraLength, Vector2* umbra, int umbraLength,
        const Vector3* poly, int polyLength, VertexBuffer& shadowTriangleStrip,
        const Vector2& centroid) {

    bool hasOccludedUmbraArea = false;
    Vector2 poly2d[polyLength];

    if (isCasterOpaque) {
        for (int i = 0; i < polyLength; i++) {
            poly2d[i].x = poly[i].x;
            poly2d[i].y = poly[i].y;
        }
        // Make sure the centroid is inside the umbra, otherwise, fall back to the
        // approach as if there is no occluded umbra area.
        if (testPointInsidePolygon(centroid, poly2d, polyLength)) {
            hasOccludedUmbraArea = true;
        }
    }

    int totalRayNum = umbraLength + penumbraLength;
    Vector2 umbraVertices[totalRayNum];
    Vector2 penumbraVertices[totalRayNum];
    Vector2 occludedUmbraVertices[totalRayNum];
    bool convertSuccess = convertPolysToVerticesPerRay(hasOccludedUmbraArea, poly2d,
            polyLength, umbra, umbraLength, penumbra, penumbraLength,
            centroid, umbraVertices, penumbraVertices, occludedUmbraVertices);
    if (!convertSuccess) {
        return;
    }

    // Minimal value is 1, for each vertex show up once.
    // The bigger this value is , the smoother the look is, but more memory
    // is consumed.
    // When the ray number is high, that means the polygon has been fine
    // tessellated, we don't need this extra slice, just keep it as 1.
    int sliceNumberPerEdge = (totalRayNum > FINE_TESSELLATED_POLYGON_RAY_NUMBER) ? 1 : 2;

    // For each polygon, we at most add (totalRayNum * sliceNumberPerEdge) vertices.
    int slicedVertexCountPerPolygon = totalRayNum * sliceNumberPerEdge;
    int totalVertexCount = slicedVertexCountPerPolygon * 2 + totalRayNum;
    int totalIndexCount = 2 * (slicedVertexCountPerPolygon * 2 + 2);
    AlphaVertex* shadowVertices =
            shadowTriangleStrip.alloc<AlphaVertex>(totalVertexCount);
    uint16_t* indexBuffer =
            shadowTriangleStrip.allocIndices<uint16_t>(totalIndexCount);

    int indexBufferIndex = 0;
    int vertexBufferIndex = 0;

    uint16_t slicedUmbraVertexIndex[totalRayNum * sliceNumberPerEdge];
    // Should be something like 0 0 0  1 1 1 2 3 3 3...
    int rayNumberPerSlicedUmbra[totalRayNum * sliceNumberPerEdge];
    int realUmbraVertexCount = 0;
    for (int i = 0; i < totalRayNum; i++) {
        Vector2 currentPenumbra = penumbraVertices[i];
        Vector2 currentUmbra = umbraVertices[i];

        Vector2 nextPenumbra = penumbraVertices[(i + 1) % totalRayNum];
        Vector2 nextUmbra = umbraVertices[(i + 1) % totalRayNum];
        // NextUmbra/Penumbra will be done in the next loop!!
        for (int weight = 0; weight < sliceNumberPerEdge; weight++) {
            const Vector2& slicedPenumbra = (currentPenumbra * (sliceNumberPerEdge - weight)
                + nextPenumbra * weight) / sliceNumberPerEdge;

            const Vector2& slicedUmbra = (currentUmbra * (sliceNumberPerEdge - weight)
                + nextUmbra * weight) / sliceNumberPerEdge;

            // In the vertex buffer, we fill the Penumbra first, then umbra.
            indexBuffer[indexBufferIndex++] = vertexBufferIndex;
            AlphaVertex::set(&shadowVertices[vertexBufferIndex++], slicedPenumbra.x,
                    slicedPenumbra.y, 0.0f);

            // When we add umbra vertex, we need to remember its current ray number.
            // And its own vertexBufferIndex. This is for occluded umbra usage.
            indexBuffer[indexBufferIndex++] = vertexBufferIndex;
            rayNumberPerSlicedUmbra[realUmbraVertexCount] = i;
            slicedUmbraVertexIndex[realUmbraVertexCount] = vertexBufferIndex;
            realUmbraVertexCount++;
            AlphaVertex::set(&shadowVertices[vertexBufferIndex++], slicedUmbra.x,
                    slicedUmbra.y, M_PI);
        }
    }

    indexBuffer[indexBufferIndex++] = 0;
    //RealUmbraVertexIndex[0] must be 1, so we connect back well at the
    //beginning of occluded area.
    indexBuffer[indexBufferIndex++] = 1;

    float occludedUmbraAlpha = M_PI;
    if (hasOccludedUmbraArea) {
        // Now the occludedUmbra area;
        int currentRayNumber = -1;
        int firstOccludedUmbraIndex = -1;
        for (int i = 0; i < realUmbraVertexCount; i++) {
            indexBuffer[indexBufferIndex++] = slicedUmbraVertexIndex[i];

            // If the occludedUmbra vertex has not been added yet, then add it.
            // Otherwise, just use the previously added occludedUmbra vertices.
            if (rayNumberPerSlicedUmbra[i] != currentRayNumber) {
                currentRayNumber++;
                indexBuffer[indexBufferIndex++] = vertexBufferIndex;
                // We need to remember the begining of the occludedUmbra vertices
                // to close this loop.
                if (currentRayNumber == 0) {
                    firstOccludedUmbraIndex = vertexBufferIndex;
                }
                AlphaVertex::set(&shadowVertices[vertexBufferIndex++],
                        occludedUmbraVertices[currentRayNumber].x,
                        occludedUmbraVertices[currentRayNumber].y,
                        occludedUmbraAlpha);
            } else {
                indexBuffer[indexBufferIndex++] = (vertexBufferIndex - 1);
            }
        }
        // Close the loop here!
        indexBuffer[indexBufferIndex++] = slicedUmbraVertexIndex[0];
        indexBuffer[indexBufferIndex++] = firstOccludedUmbraIndex;
    } else {
        int lastCentroidIndex = vertexBufferIndex;
        AlphaVertex::set(&shadowVertices[vertexBufferIndex++], centroid.x,
                centroid.y, occludedUmbraAlpha);
        for (int i = 0; i < realUmbraVertexCount; i++) {
            indexBuffer[indexBufferIndex++] = slicedUmbraVertexIndex[i];
            indexBuffer[indexBufferIndex++] = lastCentroidIndex;
        }
        // Close the loop here!
        indexBuffer[indexBufferIndex++] = slicedUmbraVertexIndex[0];
        indexBuffer[indexBufferIndex++] = lastCentroidIndex;
    }

#if DEBUG_SHADOW
    ALOGD("allocated IB %d allocated VB is %d", totalIndexCount, totalVertexCount);
    ALOGD("IB index %d VB index is %d", indexBufferIndex, vertexBufferIndex);
    for (int i = 0; i < vertexBufferIndex; i++) {
        ALOGD("vertexBuffer i %d, (%f, %f %f)", i, shadowVertices[i].x, shadowVertices[i].y,
                shadowVertices[i].alpha);
    }
    for (int i = 0; i < indexBufferIndex; i++) {
        ALOGD("indexBuffer i %d, indexBuffer[i] %d", i, indexBuffer[i]);
    }
#endif

    // At the end, update the real index and vertex buffer size.
    shadowTriangleStrip.updateVertexCount(vertexBufferIndex);
    shadowTriangleStrip.updateIndexCount(indexBufferIndex);
    ShadowTessellator::checkOverflow(vertexBufferIndex, totalVertexCount, "Spot Vertex Buffer");
    ShadowTessellator::checkOverflow(indexBufferIndex, totalIndexCount, "Spot Index Buffer");

    shadowTriangleStrip.setMode(VertexBuffer::kIndices);
    shadowTriangleStrip.computeBounds<AlphaVertex>();
}

#if DEBUG_SHADOW

#define TEST_POINT_NUMBER 128
/**
 * Calculate the bounds for generating random test points.
 */
void SpotShadow::updateBound(const Vector2 inVector, Vector2& lowerBound,
        Vector2& upperBound) {
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
