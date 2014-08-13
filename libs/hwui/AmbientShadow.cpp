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
#include "ShadowTessellator.h"
#include "Vertex.h"

namespace android {
namespace uirenderer {

/**
 * Calculate the shadows as a triangle strips while alpha value as the
 * shadow values.
 *
 * @param isCasterOpaque Whether the caster is opaque.
 * @param vertices The shadow caster's polygon, which is represented in a Vector3
 *                  array.
 * @param vertexCount The length of caster's polygon in terms of number of
 *                    vertices.
 * @param centroid3d The centroid of the shadow caster.
 * @param heightFactor The factor showing the higher the object, the lighter the
 *                     shadow.
 * @param geomFactor The factor scaling the geometry expansion along the normal.
 *
 * @param shadowVertexBuffer Return an floating point array of (x, y, a)
 *               triangle strips mode.
 */
void AmbientShadow::createAmbientShadow(bool isCasterOpaque,
        const Vector3* vertices, int vertexCount, const Vector3& centroid3d,
        float heightFactor, float geomFactor, VertexBuffer& shadowVertexBuffer) {
    const int rays = SHADOW_RAY_COUNT;
    // Validate the inputs.
    if (vertexCount < 3 || heightFactor <= 0 || rays <= 0
        || geomFactor <= 0) {
#if DEBUG_SHADOW
        ALOGW("Invalid input for createAmbientShadow(), early return!");
#endif
        return;
    }

    Vector<Vector2> dir; // TODO: use C++11 unique_ptr
    dir.setCapacity(rays);
    float rayDist[rays];
    float rayHeight[rays];
    calculateRayDirections(rays, vertices, vertexCount, centroid3d, dir.editArray());

    // Calculate the length and height of the points along the edge.
    //
    // The math here is:
    // Intersect each ray (starting from the centroid) with the polygon.
    for (int i = 0; i < rays; i++) {
        int edgeIndex;
        float edgeFraction;
        float rayDistance;
        calculateIntersection(vertices, vertexCount, centroid3d, dir[i], edgeIndex,
                edgeFraction, rayDistance);
        rayDist[i] = rayDistance;
        if (edgeIndex < 0 || edgeIndex >= vertexCount) {
#if DEBUG_SHADOW
            ALOGW("Invalid edgeIndex!");
#endif
            edgeIndex = 0;
        }
        float h1 = vertices[edgeIndex].z;
        float h2 = vertices[((edgeIndex + 1) % vertexCount)].z;
        rayHeight[i] = h1 + edgeFraction * (h2 - h1);
    }

    // The output buffer length basically is roughly rays * layers, but since we
    // need triangle strips, so we need to duplicate vertices to accomplish that.
    AlphaVertex* shadowVertices =
            shadowVertexBuffer.alloc<AlphaVertex>(SHADOW_VERTEX_COUNT);

    // Calculate the vertex of the shadows.
    //
    // The math here is:
    // Along the edges of the polygon, for each intersection point P (generated above),
    // calculate the normal N, which should be perpendicular to the edge of the
    // polygon (represented by the neighbor intersection points) .
    // Shadow's vertices will be generated as : P + N * scale.
    const Vector2 centroid2d = {centroid3d.x, centroid3d.y};
    for (int rayIndex = 0; rayIndex < rays; rayIndex++) {
        Vector2 normal = {1.0f, 0.0f};
        calculateNormal(rays, rayIndex, dir.array(), rayDist, normal);

        // The vertex should be start from rayDist[i] then scale the
        // normalizeNormal!
        Vector2 intersection = dir[rayIndex] * rayDist[rayIndex] +
                centroid2d;

        // outer ring of points, expanded based upon height of each ray intersection
        float expansionDist = rayHeight[rayIndex] * heightFactor *
                geomFactor;
        AlphaVertex::set(&shadowVertices[rayIndex],
                intersection.x + normal.x * expansionDist,
                intersection.y + normal.y * expansionDist,
                0.0f);

        // inner ring of points
        float opacity = 1.0 / (1 + rayHeight[rayIndex] * heightFactor);
        // NOTE: Shadow alpha values are transformed when stored in alphavertices,
        // so that they can be consumed directly by gFS_Main_ApplyVertexAlphaShadowInterp
        float transformedOpacity = acos(1.0f - 2.0f * opacity);
        AlphaVertex::set(&shadowVertices[rays + rayIndex],
                intersection.x,
                intersection.y,
                transformedOpacity);
    }

    if (isCasterOpaque) {
        // skip inner ring, calc bounds over filled portion of buffer
        shadowVertexBuffer.computeBounds<AlphaVertex>(2 * rays);
        shadowVertexBuffer.setMode(VertexBuffer::kOnePolyRingShadow);
    } else {
        // If caster isn't opaque, we need to to fill the umbra by storing the umbra's
        // centroid in the innermost ring of vertices.
        float centroidAlpha = 1.0 / (1 + centroid3d.z * heightFactor);
        AlphaVertex centroidXYA;
        AlphaVertex::set(&centroidXYA, centroid2d.x, centroid2d.y, centroidAlpha);
        for (int rayIndex = 0; rayIndex < rays; rayIndex++) {
            shadowVertices[2 * rays + rayIndex] = centroidXYA;
        }
        // calc bounds over entire buffer
        shadowVertexBuffer.computeBounds<AlphaVertex>();
        shadowVertexBuffer.setMode(VertexBuffer::kTwoPolyRingShadow);
    }

#if DEBUG_SHADOW
    for (int i = 0; i < SHADOW_VERTEX_COUNT; i++) {
        ALOGD("ambient shadow value: i %d, (x:%f, y:%f, a:%f)", i, shadowVertices[i].x,
                shadowVertices[i].y, shadowVertices[i].alpha);
    }
#endif
}

/**
 * Generate an array of rays' direction vectors.
 * To make sure the vertices generated are clockwise, the directions are from PI
 * to -PI.
 *
 * @param rays The number of rays shooting out from the centroid.
 * @param vertices Vertices of the polygon.
 * @param vertexCount The number of vertices.
 * @param centroid3d The centroid of the polygon.
 * @param dir Return the array of ray vectors.
 */
void AmbientShadow::calculateRayDirections(const int rays, const Vector3* vertices,
        const int vertexCount, const Vector3& centroid3d, Vector2* dir) {
    // If we don't have enough rays, then fall back to the uniform distribution.
    if (vertexCount * 2 > rays) {
        float deltaAngle = 2 * M_PI / rays;
        for (int i = 0; i < rays; i++) {
            dir[i].x = cosf(M_PI - deltaAngle * i);
            dir[i].y = sinf(M_PI - deltaAngle * i);
        }
        return;
    }

    // If we have enough rays, then we assign each vertices a ray, and distribute
    // the rest uniformly.
    float rayThetas[rays];

    const int uniformRayCount = rays - vertexCount;
    const float deltaAngle = 2 * M_PI / uniformRayCount;

    // We have to generate all the vertices' theta anyway and we also need to
    // find the minimal, so let's precompute it first.
    // Since the incoming polygon is clockwise, we can find the dip to identify
    // the minimal theta.
    float polyThetas[vertexCount];
    int maxPolyThetaIndex = 0;
    for (int i = 0; i < vertexCount; i++) {
        polyThetas[i] = atan2(vertices[i].y - centroid3d.y,
                vertices[i].x - centroid3d.x);
        if (i > 0 && polyThetas[i] > polyThetas[i - 1]) {
            maxPolyThetaIndex = i;
        }
    }

    // Both poly's thetas and uniform thetas are in decrease order(clockwise)
    // from PI to -PI.
    int polyThetaIndex = maxPolyThetaIndex;
    float polyTheta = polyThetas[maxPolyThetaIndex];
    int uniformThetaIndex = 0;
    float uniformTheta = M_PI;
    for (int i = 0; i < rays; i++) {
        // Compare both thetas and pick the smaller one and move on.
        bool hasThetaCollision = abs(polyTheta - uniformTheta) < MINIMAL_DELTA_THETA;
        if (polyTheta > uniformTheta || hasThetaCollision) {
            if (hasThetaCollision) {
                // Shift the uniformTheta to middle way between current polyTheta
                // and next uniform theta. The next uniform theta can wrap around
                // to exactly PI safely here.
                // Note that neither polyTheta nor uniformTheta can be FLT_MAX
                // due to the hasThetaCollision is true.
                uniformTheta = (polyTheta +  M_PI - deltaAngle * (uniformThetaIndex + 1)) / 2;
#if DEBUG_SHADOW
                ALOGD("Shifted uniformTheta to %f", uniformTheta);
#endif
            }
            rayThetas[i] = polyTheta;
            polyThetaIndex = (polyThetaIndex + 1) % vertexCount;
            if (polyThetaIndex != maxPolyThetaIndex) {
                polyTheta = polyThetas[polyThetaIndex];
            } else {
                // out of poly points.
                polyTheta = - FLT_MAX;
            }
        } else {
            rayThetas[i] = uniformTheta;
            uniformThetaIndex++;
            if (uniformThetaIndex < uniformRayCount) {
                uniformTheta = M_PI - deltaAngle * uniformThetaIndex;
            } else {
                // out of uniform points.
                uniformTheta = - FLT_MAX;
            }
        }
    }

    for (int i = 0; i < rays; i++) {
#if DEBUG_SHADOW
        ALOGD("No. %d : %f", i, rayThetas[i] * 180 / M_PI);
#endif
        // TODO: Fix the intersection precision problem and remvoe the delta added
        // here.
        dir[i].x = cosf(rayThetas[i] + MINIMAL_DELTA_THETA);
        dir[i].y = sinf(rayThetas[i] + MINIMAL_DELTA_THETA);
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
        const Vector3& start, const Vector2& dir, int& outEdgeIndex,
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

    // Now the rays are going CW around the poly.
    Vector2 delta = p2 - p1;
    if (delta.length() != 0) {
        delta.normalize();
        // Calculate the normal , which is CCW 90 rotate to the delta.
        normal.x = - delta.y;
        normal.y = delta.x;
    }
}

}; // namespace uirenderer
}; // namespace android
