/*
 * Copyright (C) 2013 The Android Open Source Project
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

#include <math.h>
#include <utils/Log.h>
#include <utils/Vector.h>

#include "AmbientShadow.h"
#include "Vertex.h"

namespace android {
namespace uirenderer {

/**
 * Calculate the shadows as a triangle strips while alpha value as the
 * shadow values.
 *
 * @param vertices The shadow caster's polygon, which is represented in a Vector3
 *                  array.
 * @param vertexCount The length of caster's polygon in terms of number of
 *                    vertices.
 * @param rays The number of rays shooting out from the centroid.
 * @param layers The number of rings outside the polygon.
 * @param strength The darkness of the shadow, the higher, the darker.
 * @param heightFactor The factor showing the higher the object, the lighter the
 *                     shadow.
 * @param geomFactor The factor scaling the geometry expansion along the normal.
 *
 * @param shadowVertexBuffer Return an floating point array of (x, y, a)
 *               triangle strips mode.
 */
void AmbientShadow::createAmbientShadow(const Vector3* vertices, int vertexCount,
        int rays, int layers, float strength, float heightFactor, float geomFactor,
        VertexBuffer& shadowVertexBuffer) {

    // Validate the inputs.
    if (strength <= 0 || heightFactor <= 0 || layers <= 0 || rays <= 0
        || geomFactor <= 0) {
#if DEBUG_SHADOW
        ALOGE("Invalid input for createAmbientShadow(), early return!");
#endif
        return;
    }
    int rings = layers + 1;
    int size = rays * rings;
    Vector2 centroid;
    calculatePolygonCentroid(vertices, vertexCount, centroid);

    Vector<Vector2> dir; // TODO: use C++11 unique_ptr
    dir.setCapacity(rays);
    float rayDist[rays];
    float rayHeight[rays];
    calculateRayDirections(rays, dir.editArray());

    // Calculate the length and height of the points along the edge.
    //
    // The math here is:
    // Intersect each ray (starting from the centroid) with the polygon.
    for (int i = 0; i < rays; i++) {
        int edgeIndex;
        float edgeFraction;
        float rayDistance;
        calculateIntersection(vertices, vertexCount, centroid, dir[i], edgeIndex,
                edgeFraction, rayDistance);
        rayDist[i] = rayDistance;
        if (edgeIndex < 0 || edgeIndex >= vertexCount) {
#if DEBUG_SHADOW
            ALOGE("Invalid edgeIndex!");
#endif
            edgeIndex = 0;
        }
        float h1 = vertices[edgeIndex].z;
        float h2 = vertices[((edgeIndex + 1) % vertexCount)].z;
        rayHeight[i] = h1 + edgeFraction * (h2 - h1);
    }

    // The output buffer length basically is roughly rays * layers, but since we
    // need triangle strips, so we need to duplicate vertices to accomplish that.
    const int shadowVertexCount = (2 + rays + ((layers) * 2 * (rays + 1)));
    AlphaVertex* shadowVertices = shadowVertexBuffer.alloc<AlphaVertex>(shadowVertexCount);

    // Calculate the vertex of the shadows.
    //
    // The math here is:
    // Along the edges of the polygon, for each intersection point P (generated above),
    // calculate the normal N, which should be perpendicular to the edge of the
    // polygon (represented by the neighbor intersection points) .
    // Shadow's vertices will be generated as : P + N * scale.
    int currentIndex = 0;
    for (int r = 0; r < layers; r++) {
        int firstInLayer = currentIndex;
        for (int i = 0; i < rays; i++) {

            Vector2 normal(1.0f, 0.0f);
            calculateNormal(rays, i, dir.array(), rayDist, normal);

            float opacity = strength * (0.5f) / (1 + rayHeight[i] / heightFactor);

            // The vertex should be start from rayDist[i] then scale the
            // normalizeNormal!
            Vector2 intersection = dir[i] * rayDist[i] + centroid;

            // Use 2 rings' vertices to complete one layer's strip
            for (int j = r; j < (r + 2); j++) {
                float jf = j / (float)(rings - 1);

                float expansionDist = rayHeight[i] / heightFactor * geomFactor * jf;
                AlphaVertex::set(&shadowVertices[currentIndex],
                        intersection.x + normal.x * expansionDist,
                        intersection.y + normal.y * expansionDist,
                        (1 - jf) * opacity);
                currentIndex++;
            }
        }

        // From one layer to the next, we need to duplicate the vertex to
        // continue as a single strip.
        shadowVertices[currentIndex] = shadowVertices[firstInLayer];
        currentIndex++;
        shadowVertices[currentIndex] = shadowVertices[firstInLayer + 1];
        currentIndex++;
    }

    // After all rings are done, we need to jump into the polygon.
    // In order to keep everything in a strip, we need to duplicate the last one
    // of the rings and the first one inside the polygon.
    int lastInRings = currentIndex - 1;
    shadowVertices[currentIndex] = shadowVertices[lastInRings];
    currentIndex++;

    // We skip one and fill it back after we finish the internal triangles.
    currentIndex++;
    int firstInternal = currentIndex;

    // Combine the internal area of the polygon into a triangle strip, too.
    // The basic idea is zig zag between the intersection points.
    // 0 -> (n - 1) -> 1 -> (n - 2) ...
    for (int k = 0; k < rays; k++) {
        int  i = k / 2;
        if ((k & 1) == 1) { // traverse the inside in a zig zag pattern for strips
            i = rays - i - 1;
        }
        float cast = rayDist[i] * (1 + rayHeight[i] / heightFactor);
        float opacity = strength * (0.5f) / (1 + rayHeight[i] / heightFactor);
        float t = rayDist[i];

        AlphaVertex::set(&shadowVertices[currentIndex], dir[i].x * t + centroid.x,
                dir[i].y * t + centroid.y, opacity);
        currentIndex++;
    }

    currentIndex = firstInternal - 1;
    shadowVertices[currentIndex] = shadowVertices[firstInternal];
}

/**
 * Calculate the centroid of a given polygon.
 *
 * @param vertices The shadow caster's polygon, which is represented in a
 *                 straight Vector3 array.
 * @param vertexCount The length of caster's polygon in terms of number of vertices.
 *
 * @param centroid Return the centroid of the polygon.
 */
void AmbientShadow::calculatePolygonCentroid(const Vector3* vertices, int vertexCount,
        Vector2& centroid) {
    float sumx = 0;
    float sumy = 0;
    int p1 = vertexCount - 1;
    float area = 0;
    for (int p2 = 0; p2 < vertexCount; p2++) {
        float x1 = vertices[p1].x;
        float y1 = vertices[p1].y;
        float x2 = vertices[p2].x;
        float y2 = vertices[p2].y;
        float a = (x1 * y2 - x2 * y1);
        sumx += (x1 + x2) * a;
        sumy += (y1 + y2) * a;
        area += a;
        p1 = p2;
    }

    if (area == 0) {
#if DEBUG_SHADOW
        ALOGE("Area is 0!");
#endif
        centroid.x = vertices[0].x;
        centroid.y = vertices[0].y;
    } else {
        centroid.x = sumx / (3 * area);
        centroid.y = sumy / (3 * area);
    }
}

/**
 * Generate an array of rays' direction vectors.
 *
 * @param rays The number of rays shooting out from the centroid.
 * @param dir Return the array of ray vectors.
 */
void AmbientShadow::calculateRayDirections(int rays, Vector2* dir) {
    float deltaAngle = 2 * M_PI / rays;

    for (int i = 0; i < rays; i++) {
        dir[i].x = sinf(deltaAngle * i);
        dir[i].y = cosf(deltaAngle * i);
    }
}

/**
 * Calculate the intersection of a ray hitting the polygon.
 *
 * @param vertices The shadow caster's polygon, which is represented in a
 *                 Vector3 array.
 * @param vertexCount The length of caster's polygon in terms of number of vertices.
 * @param start The starting point of the ray.
 * @param dir The direction vector of the ray.
 *
 * @param outEdgeIndex Return the index of the segment (or index of the starting
 *                     vertex) that ray intersect with.
 * @param outEdgeFraction Return the fraction offset from the segment starting
 *                        index.
 * @param outRayDist Return the ray distance from centroid to the intersection.
 */
void AmbientShadow::calculateIntersection(const Vector3* vertices, int vertexCount,
        const Vector2& start, const Vector2& dir, int& outEdgeIndex,
        float& outEdgeFraction, float& outRayDist) {
    float startX = start.x;
    float startY = start.y;
    float dirX = dir.x;
    float dirY = dir.y;
    // Start the search from the last edge from poly[len-1] to poly[0].
    int p1 = vertexCount - 1;

    for (int p2 = 0; p2 < vertexCount; p2++) {
        float p1x = vertices[p1].x;
        float p1y = vertices[p1].y;
        float p2x = vertices[p2].x;
        float p2y = vertices[p2].y;

        // The math here is derived from:
        // f(t, v) = p1x * (1 - t) + p2x * t - (startX + dirX * v) = 0;
        // g(t, v) = p1y * (1 - t) + p2y * t - (startY + dirY * v) = 0;
        float div = (dirX * (p1y - p2y) + dirY * p2x - dirY * p1x);
        if (div != 0) {
            float t = (dirX * (p1y - startY) + dirY * startX - dirY * p1x) / (div);
            if (t > 0 && t <= 1) {
                float t2 = (p1x * (startY - p2y)
                            + p2x * (p1y - startY)
                            + startX * (p2y - p1y)) / div;
                if (t2 > 0) {
                    outEdgeIndex = p1;
                    outRayDist = t2;
                    outEdgeFraction = t;
                    return;
                }
            }
        }
        p1 = p2;
    }
    return;
};

/**
 * Calculate the normal at the intersection point between a ray and the polygon.
 *
 * @param rays The total number of rays.
 * @param currentRayIndex The index of the ray which the normal is based on.
 * @param dir The array of the all the rays directions.
 * @param rayDist The pre-computed ray distances array.
 *
 * @param normal Return the normal.
 */
void AmbientShadow::calculateNormal(int rays, int currentRayIndex,
        const Vector2* dir, const float* rayDist, Vector2& normal) {
    int preIndex = (currentRayIndex - 1 + rays) % rays;
    int postIndex = (currentRayIndex + 1) % rays;
    Vector2 p1 = dir[preIndex] * rayDist[preIndex];
    Vector2 p2 = dir[postIndex] * rayDist[postIndex];

    // Now the V (deltaX, deltaY) is the vector going CW around the poly.
    Vector2 delta = p2 - p1;
    if (delta.length() != 0) {
        delta.normalize();
        // Calculate the normal , which is CCW 90 rotate to the V.
        // 90 degrees CCW about z-axis: (x, y, z) -> (-y, x, z)
        normal.x = -delta.y;
        normal.y = delta.x;
    }
}

}; // namespace uirenderer
}; // namespace android
