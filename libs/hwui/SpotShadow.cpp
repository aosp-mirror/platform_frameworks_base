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

static const float EPSILON = 1e-7;

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

    float divisor = (dx * (p1.y - p2.y) + dy * p2.x - dy * p1.x);
    if (divisor == 0) return -1.0f; // error, invalid divisor

#if DEBUG_SHADOW
    float interpVal = (dx * (p1.y - rayOrigin.y) + dy * rayOrigin.x - dy * p1.x) / divisor;
    if (interpVal < 0 || interpVal > 1) {
        ALOGW("rayIntersectPoints is hitting outside the segment %f", interpVal);
    }
#endif

    float distance = (p1.x * (rayOrigin.y - p2.y) + p2.x * (p1.y - rayOrigin.y) +
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
bool SpotShadow::ccw(float ax, float ay, float bx, float by,
        float cx, float cy) {
    return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax) > EPSILON;
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
    float testx = testPoint.x;
    float testy = testPoint.y;
    for (int i = 0, j = len - 1; i < len; j = i++) {
        float startX = poly[j].x;
        float startY = poly[j].y;
        float endX = poly[i].x;
        float endY = poly[i].y;

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
        float angle = 2 * i * M_PI / points;
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

// Index pair is meant for storing the tessellation information for the penumbra
// area. One index must come from exterior tangent of the circles, the other one
// must come from the interior tangent of the circles.
struct IndexPair {
    int outerIndex;
    int innerIndex;
};

// For one penumbra vertex, find the cloest umbra vertex and return its index.
inline int getClosestUmbraIndex(const Vector2& pivot, const Vector2* polygon, int polygonLength) {
    float minLengthSquared = FLT_MAX;
    int resultIndex = -1;
    bool hasDecreased = false;
    // Starting with some negative offset, assuming both umbra and penumbra are starting
    // at the same angle, this can help to find the result faster.
    // Normally, loop 3 times, we can find the closest point.
    int offset = polygonLength - 2;
    for (int i = 0; i < polygonLength; i++) {
        int currentIndex = (i + offset) % polygonLength;
        float currentLengthSquared = (pivot - polygon[currentIndex]).lengthSquared();
        if (currentLengthSquared < minLengthSquared) {
            if (minLengthSquared != FLT_MAX) {
                hasDecreased = true;
            }
            minLengthSquared = currentLengthSquared;
            resultIndex = currentIndex;
        } else if (currentLengthSquared > minLengthSquared && hasDecreased) {
            // Early break b/c we have found the closet one and now the length
            // is increasing again.
            break;
        }
    }
    if(resultIndex == -1) {
        ALOGE("resultIndex is -1, the polygon must be invalid!");
        resultIndex = 0;
    }
    return resultIndex;
}

// Allow some epsilon here since the later ray intersection did allow for some small
// floating point error, when the intersection point is slightly outside the segment.
inline bool sameDirections(bool isPositiveCross, float a, float b) {
    if (isPositiveCross) {
        return a >= -EPSILON && b >= -EPSILON;
    } else {
        return a <= EPSILON && b <= EPSILON;
    }
}

// Find the right polygon edge to shoot the ray at.
inline int findPolyIndex(bool isPositiveCross, int startPolyIndex, const Vector2& umbraDir,
        const Vector2* polyToCentroid, int polyLength) {
    // Make sure we loop with a bound.
    for (int i = 0; i < polyLength; i++) {
        int currentIndex = (i + startPolyIndex) % polyLength;
        const Vector2& currentToCentroid = polyToCentroid[currentIndex];
        const Vector2& nextToCentroid = polyToCentroid[(currentIndex + 1) % polyLength];

        float currentCrossUmbra = currentToCentroid.cross(umbraDir);
        float umbraCrossNext = umbraDir.cross(nextToCentroid);
        if (sameDirections(isPositiveCross, currentCrossUmbra, umbraCrossNext)) {
#if DEBUG_SHADOW
            ALOGD("findPolyIndex loop %d times , index %d", i, currentIndex );
#endif
            return currentIndex;
        }
    }
    LOG_ALWAYS_FATAL("Can't find the right polygon's edge from startPolyIndex %d", startPolyIndex);
    return -1;
}

// Generate the index pair for penumbra / umbra vertices, and more penumbra vertices
// if needed.
inline void genNewPenumbraAndPairWithUmbra(const Vector2* penumbra, int penumbraLength,
        const Vector2* umbra, int umbraLength, Vector2* newPenumbra, int& newPenumbraIndex,
        IndexPair* verticesPair, int& verticesPairIndex) {
    // In order to keep everything in just one loop, we need to pre-compute the
    // closest umbra vertex for the last penumbra vertex.
    int previousClosestUmbraIndex = getClosestUmbraIndex(penumbra[penumbraLength - 1],
            umbra, umbraLength);
    for (int i = 0; i < penumbraLength; i++) {
        const Vector2& currentPenumbraVertex = penumbra[i];
        // For current penumbra vertex, starting from previousClosestUmbraIndex,
        // then check the next one until the distance increase.
        // The last one before the increase is the umbra vertex we need to pair with.
        float currentLengthSquared =
                (currentPenumbraVertex - umbra[previousClosestUmbraIndex]).lengthSquared();
        int currentClosestUmbraIndex = previousClosestUmbraIndex;
        int indexDelta = 0;
        for (int j = 1; j < umbraLength; j++) {
            int newUmbraIndex = (previousClosestUmbraIndex + j) % umbraLength;
            float newLengthSquared = (currentPenumbraVertex - umbra[newUmbraIndex]).lengthSquared();
            if (newLengthSquared > currentLengthSquared) {
                // currentClosestUmbraIndex is the umbra vertex's index which has
                // currently found smallest distance, so we can simply break here.
                break;
            } else {
                currentLengthSquared = newLengthSquared;
                indexDelta++;
                currentClosestUmbraIndex = newUmbraIndex;
            }
        }

        if (indexDelta > 1) {
            // For those umbra don't have  penumbra, generate new penumbra vertices by interpolation.
            //
            // Assuming Pi for penumbra vertices, and Ui for umbra vertices.
            // In the case like below P1 paired with U1 and P2 paired with  U5.
            // U2 to U4 are unpaired umbra vertices.
            //
            // P1                                        P2
            // |                                          |
            // U1     U2                   U3     U4     U5
            //
            // We will need to generate 3 more penumbra vertices P1.1, P1.2, P1.3
            // to pair with U2 to U4.
            //
            // P1     P1.1                P1.2   P1.3    P2
            // |       |                   |      |      |
            // U1     U2                   U3     U4     U5
            //
            // That distance ratio b/t Ui to U1 and Ui to U5 decides its paired penumbra
            // vertex's location.
            int newPenumbraNumber = indexDelta - 1;

            float accumulatedDeltaLength[newPenumbraNumber];
            float totalDeltaLength = 0;

            // To save time, cache the previous umbra vertex info outside the loop
            // and update each loop.
            Vector2 previousClosestUmbra = umbra[previousClosestUmbraIndex];
            Vector2 skippedUmbra;
            // Use umbra data to precompute the length b/t unpaired umbra vertices,
            // and its ratio against the total length.
            for (int k = 0; k < indexDelta; k++) {
                int skippedUmbraIndex = (previousClosestUmbraIndex + k + 1) % umbraLength;
                skippedUmbra = umbra[skippedUmbraIndex];
                float currentDeltaLength = (skippedUmbra - previousClosestUmbra).length();

                totalDeltaLength += currentDeltaLength;
                accumulatedDeltaLength[k] = totalDeltaLength;

                previousClosestUmbra = skippedUmbra;
            }

            const Vector2& previousPenumbra = penumbra[(i + penumbraLength - 1) % penumbraLength];
            // Then for each unpaired umbra vertex, create a new penumbra by the ratio,
            // and pair them togehter.
            for (int k = 0; k < newPenumbraNumber; k++) {
                float weightForCurrentPenumbra = 1.0f;
                if (totalDeltaLength != 0.0f) {
                    weightForCurrentPenumbra = accumulatedDeltaLength[k] / totalDeltaLength;
                }
                float weightForPreviousPenumbra = 1.0f - weightForCurrentPenumbra;

                Vector2 interpolatedPenumbra = currentPenumbraVertex * weightForCurrentPenumbra +
                    previousPenumbra * weightForPreviousPenumbra;

                int skippedUmbraIndex = (previousClosestUmbraIndex + k + 1) % umbraLength;
                verticesPair[verticesPairIndex++] = {newPenumbraIndex, skippedUmbraIndex};
                newPenumbra[newPenumbraIndex++] = interpolatedPenumbra;
            }
        }
        verticesPair[verticesPairIndex++] = {newPenumbraIndex, currentClosestUmbraIndex};
        newPenumbra[newPenumbraIndex++] = currentPenumbraVertex;

        previousClosestUmbraIndex = currentClosestUmbraIndex;
    }
}

// Precompute all the polygon's vector, return true if the reference cross product is positive.
inline bool genPolyToCentroid(const Vector2* poly2d, int polyLength,
        const Vector2& centroid, Vector2* polyToCentroid) {
    for (int j = 0; j < polyLength; j++) {
        polyToCentroid[j] = poly2d[j] - centroid;
        // Normalize these vectors such that we can use epsilon comparison after
        // computing their cross products with another normalized vector.
        polyToCentroid[j].normalize();
    }
    float refCrossProduct = 0;
    for (int j = 0; j < polyLength; j++) {
        refCrossProduct = polyToCentroid[j].cross(polyToCentroid[(j + 1) % polyLength]);
        if (refCrossProduct != 0) {
            break;
        }
    }

    return refCrossProduct > 0;
}

// For one umbra vertex, shoot an ray from centroid to it.
// If the ray hit the polygon first, then return the intersection point as the
// closer vertex.
inline Vector2 getCloserVertex(const Vector2& umbraVertex, const Vector2& centroid,
        const Vector2* poly2d, int polyLength, const Vector2* polyToCentroid,
        bool isPositiveCross, int& previousPolyIndex) {
    Vector2 umbraToCentroid = umbraVertex - centroid;
    float distanceToUmbra = umbraToCentroid.length();
    umbraToCentroid = umbraToCentroid / distanceToUmbra;

    // previousPolyIndex is updated for each item such that we can minimize the
    // looping inside findPolyIndex();
    previousPolyIndex = findPolyIndex(isPositiveCross, previousPolyIndex,
            umbraToCentroid, polyToCentroid, polyLength);

    float dx = umbraToCentroid.x;
    float dy = umbraToCentroid.y;
    float distanceToIntersectPoly = rayIntersectPoints(centroid, dx, dy,
            poly2d[previousPolyIndex], poly2d[(previousPolyIndex + 1) % polyLength]);
    if (distanceToIntersectPoly < 0) {
        distanceToIntersectPoly = 0;
    }

    // Pick the closer one as the occluded area vertex.
    Vector2 closerVertex;
    if (distanceToIntersectPoly < distanceToUmbra) {
        closerVertex.x = centroid.x + dx * distanceToIntersectPoly;
        closerVertex.y = centroid.y + dy * distanceToIntersectPoly;
    } else {
        closerVertex = umbraVertex;
    }

    return closerVertex;
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

    // For each penumbra vertex, find its corresponding closest umbra vertex index.
    //
    // Penumbra Vertices marked as Pi
    // Umbra Vertices marked as Ui
    //                                            (P3)
    //          (P2)                               |     ' (P4)
    //   (P1)'   |                                 |   '
    //         ' |                                 | '
    // (P0)  ------------------------------------------------(P5)
    //           | (U0)                            |(U1)
    //           |                                 |
    //           |                                 |(U2)     (P5.1)
    //           |                                 |
    //           |                                 |
    //           |                                 |
    //           |                                 |
    //           |                                 |
    //           |                                 |
    //       (U4)-----------------------------------(U3)      (P6)
    //
    // At least, like P0, P1, P2, they will find the matching umbra as U0.
    // If we jump over some umbra vertex without matching penumbra vertex, then
    // we will generate some new penumbra vertex by interpolation. Like P6 is
    // matching U3, but U2 is not matched with any penumbra vertex.
    // So interpolate P5.1 out and match U2.
    // In this way, every umbra vertex will have a matching penumbra vertex.
    //
    // The total pair number can be as high as umbraLength + penumbraLength.
    const int maxNewPenumbraLength = umbraLength + penumbraLength;
    IndexPair verticesPair[maxNewPenumbraLength];
    int verticesPairIndex = 0;

    // Cache all the existing penumbra vertices and newly interpolated vertices into a
    // a new array.
    Vector2 newPenumbra[maxNewPenumbraLength];
    int newPenumbraIndex = 0;

    // For each penumbra vertex, find its closet umbra vertex by comparing the
    // neighbor umbra vertices.
    genNewPenumbraAndPairWithUmbra(penumbra, penumbraLength, umbra, umbraLength, newPenumbra,
            newPenumbraIndex, verticesPair, verticesPairIndex);
    ShadowTessellator::checkOverflow(verticesPairIndex, maxNewPenumbraLength, "Spot pair");
    ShadowTessellator::checkOverflow(newPenumbraIndex, maxNewPenumbraLength, "Spot new penumbra");
#if DEBUG_SHADOW
    for (int i = 0; i < umbraLength; i++) {
        ALOGD("umbra i %d,  [%f, %f]", i, umbra[i].x, umbra[i].y);
    }
    for (int i = 0; i < newPenumbraIndex; i++) {
        ALOGD("new penumbra i %d,  [%f, %f]", i, newPenumbra[i].x, newPenumbra[i].y);
    }
    for (int i = 0; i < verticesPairIndex; i++) {
        ALOGD("index i %d,  [%d, %d]", i, verticesPair[i].outerIndex, verticesPair[i].innerIndex);
    }
#endif

    // For the size of vertex buffer, we need 3 rings, one has newPenumbraSize,
    // one has umbraLength, the last one has at most umbraLength.
    //
    // For the size of index buffer, the umbra area needs (2 * umbraLength + 2).
    // The penumbra one can vary a bit, but it is bounded by (2 * verticesPairIndex + 2).
    // And 2 more for jumping between penumbra to umbra.
    const int newPenumbraLength = newPenumbraIndex;
    const int totalVertexCount = newPenumbraLength + umbraLength * 2;
    const int totalIndexCount = 2 * umbraLength + 2 * verticesPairIndex + 6;
    AlphaVertex* shadowVertices =
            shadowTriangleStrip.alloc<AlphaVertex>(totalVertexCount);
    uint16_t* indexBuffer =
            shadowTriangleStrip.allocIndices<uint16_t>(totalIndexCount);
    int vertexBufferIndex = 0;
    int indexBufferIndex = 0;

    // Fill the IB and VB for the penumbra area.
    for (int i = 0; i < newPenumbraLength; i++) {
        AlphaVertex::set(&shadowVertices[vertexBufferIndex++], newPenumbra[i].x,
                newPenumbra[i].y, 0.0f);
    }
    for (int i = 0; i < umbraLength; i++) {
        AlphaVertex::set(&shadowVertices[vertexBufferIndex++], umbra[i].x, umbra[i].y,
                M_PI);
    }

    for (int i = 0; i < verticesPairIndex; i++) {
        indexBuffer[indexBufferIndex++] = verticesPair[i].outerIndex;
        // All umbra index need to be offseted by newPenumbraSize.
        indexBuffer[indexBufferIndex++] = verticesPair[i].innerIndex + newPenumbraLength;
    }
    indexBuffer[indexBufferIndex++] = verticesPair[0].outerIndex;
    indexBuffer[indexBufferIndex++] = verticesPair[0].innerIndex + newPenumbraLength;

    // Now fill the IB and VB for the umbra area.
    // First duplicated the index from previous strip and the first one for the
    // degenerated triangles.
    indexBuffer[indexBufferIndex] = indexBuffer[indexBufferIndex - 1];
    indexBufferIndex++;
    indexBuffer[indexBufferIndex++] = newPenumbraLength + 0;
    // Save the first VB index for umbra area in order to close the loop.
    int savedStartIndex = vertexBufferIndex;

    if (hasOccludedUmbraArea) {
        // Precompute all the polygon's vector, and the reference cross product,
        // in order to find the right polygon edge for the ray to intersect.
        Vector2 polyToCentroid[polyLength];
        bool isPositiveCross = genPolyToCentroid(poly2d, polyLength, centroid, polyToCentroid);

        // Because both the umbra and polygon are going in the same direction,
        // we can save the previous polygon index to make sure we have less polygon
        // vertex to compute for each ray.
        int previousPolyIndex = 0;
        for (int i = 0; i < umbraLength; i++) {
            // Shoot a ray from centroid to each umbra vertices and pick the one with
            // shorter distance to the centroid, b/t the umbra vertex or the intersection point.
            Vector2 closerVertex = getCloserVertex(umbra[i], centroid, poly2d, polyLength,
                    polyToCentroid, isPositiveCross, previousPolyIndex);

            // We already stored the umbra vertices, just need to add the occlued umbra's ones.
            indexBuffer[indexBufferIndex++] = newPenumbraLength + i;
            indexBuffer[indexBufferIndex++] = vertexBufferIndex;
            AlphaVertex::set(&shadowVertices[vertexBufferIndex++],
                    closerVertex.x, closerVertex.y, M_PI);
        }
    } else {
        // If there is no occluded umbra at all, then draw the triangle fan
        // starting from the centroid to all umbra vertices.
        int lastCentroidIndex = vertexBufferIndex;
        AlphaVertex::set(&shadowVertices[vertexBufferIndex++], centroid.x,
                centroid.y, M_PI);
        for (int i = 0; i < umbraLength; i++) {
            indexBuffer[indexBufferIndex++] = newPenumbraLength + i;
            indexBuffer[indexBufferIndex++] = lastCentroidIndex;
        }
    }
    // Closing the umbra area triangle's loop here.
    indexBuffer[indexBufferIndex++] = newPenumbraLength;
    indexBuffer[indexBufferIndex++] = savedStartIndex;

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

        float delta = (float(middle.x) - start.x) * (float(end.y) - start.y) -
                (float(middle.y) - start.y) * (float(end.x) - start.x);
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
        float randomX = rand() / float(RAND_MAX);
        float randomY = rand() / float(RAND_MAX);

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
