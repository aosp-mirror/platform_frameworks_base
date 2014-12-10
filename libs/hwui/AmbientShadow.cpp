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

/**
 * Extra vertices for the corner for smoother corner.
 * Only for outer vertices.
 * Note that we use such extra memory to avoid an extra loop.
 */
// For half circle, we could add EXTRA_VERTEX_PER_PI vertices.
// Set to 1 if we don't want to have any.
#define EXTRA_CORNER_VERTEX_PER_PI 12

// For the whole polygon, the sum of all the deltas b/t normals is 2 * M_PI,
// therefore, the maximum number of extra vertices will be twice bigger.
#define MAX_EXTRA_CORNER_VERTEX_NUMBER  (2 * EXTRA_CORNER_VERTEX_PER_PI)

// For each RADIANS_DIVISOR, we would allocate one more vertex b/t the normals.
#define CORNER_RADIANS_DIVISOR (M_PI / EXTRA_CORNER_VERTEX_PER_PI)

/**
 * Extra vertices for the Edge for interpolation artifacts.
 * Same value for both inner and outer vertices.
 */
#define EXTRA_EDGE_VERTEX_PER_PI 50

#define MAX_EXTRA_EDGE_VERTEX_NUMBER  (2 * EXTRA_EDGE_VERTEX_PER_PI)

#define EDGE_RADIANS_DIVISOR  (M_PI / EXTRA_EDGE_VERTEX_PER_PI)

/**
 * Other constants:
 */
// For the edge of the penumbra, the opacity is 0.
#define OUTER_OPACITY (0.0f)

// Once the alpha difference is greater than this threshold, we will allocate extra
// edge vertices.
// If this is set to negative value, then all the edge will be tessellated.
#define ALPHA_THRESHOLD (0.1f / 255.0f)

#include <math.h>
#include <utils/Log.h>
#include <utils/Vector.h>

#include "AmbientShadow.h"
#include "ShadowTessellator.h"
#include "Vertex.h"
#include "utils/MathUtils.h"

namespace android {
namespace uirenderer {

/**
 *  Local utility functions.
 */
inline Vector2 getNormalFromVertices(const Vector3* vertices, int current, int next) {
    // Convert from Vector3 to Vector2 first.
    Vector2 currentVertex = { vertices[current].x, vertices[current].y };
    Vector2 nextVertex = { vertices[next].x, vertices[next].y };

    return ShadowTessellator::calculateNormal(currentVertex, nextVertex);
}

// The input z value will be converted to be non-negative inside.
// The output must be ranged from 0 to 1.
inline float getAlphaFromFactoredZ(float factoredZ) {
    return 1.0 / (1 + MathUtils::max(factoredZ, 0.0f));
}

inline float getTransformedAlphaFromAlpha(float alpha) {
    return acosf(1.0f - 2.0f * alpha);
}

// The output is ranged from 0 to M_PI.
inline float getTransformedAlphaFromFactoredZ(float factoredZ) {
    return getTransformedAlphaFromAlpha(getAlphaFromFactoredZ(factoredZ));
}

inline int getEdgeExtraAndUpdateSpike(Vector2* currentSpike,
        const Vector3& secondVertex, const Vector3& centroid) {
    Vector2 secondSpike  = {secondVertex.x - centroid.x, secondVertex.y - centroid.y};
    secondSpike.normalize();

    int result = ShadowTessellator::getExtraVertexNumber(secondSpike, *currentSpike,
            EDGE_RADIANS_DIVISOR);
    *currentSpike = secondSpike;
    return result;
}

// Given the caster's vertex count, compute all the buffers size depending on
// whether or not the caster is opaque.
inline void computeBufferSize(int* totalVertexCount, int* totalIndexCount,
        int* totalUmbraCount, int casterVertexCount, bool isCasterOpaque) {
    // Compute the size of the vertex buffer.
    int outerVertexCount = casterVertexCount * 2 + MAX_EXTRA_CORNER_VERTEX_NUMBER +
        MAX_EXTRA_EDGE_VERTEX_NUMBER;
    int innerVertexCount = casterVertexCount + MAX_EXTRA_EDGE_VERTEX_NUMBER;
    *totalVertexCount = outerVertexCount + innerVertexCount;

    // Compute the size of the index buffer.
    *totalIndexCount = 2 * outerVertexCount + 2;

    // Compute the size of the umber buffer.
    // For translucent object, keep track of the umbra(inner) vertex in order to draw
    // inside. We only need to store the index information.
    *totalUmbraCount = 0;
    if (!isCasterOpaque) {
        // Add the centroid if occluder is translucent.
        *totalVertexCount++;
        *totalIndexCount += 2 * innerVertexCount + 1;
        *totalUmbraCount = innerVertexCount;
    }
}

inline bool needsExtraForEdge(float firstAlpha, float secondAlpha) {
    return abs(firstAlpha - secondAlpha) > ALPHA_THRESHOLD;
}

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
 *
 * An simple illustration:
 * For now let's mark the outer vertex as Pi, the inner as Vi, the centroid as C.
 *
 * First project the occluder to the Z=0 surface.
 * Then we got all the inner vertices. And we compute the normal for each edge.
 * According to the normal, we generate outer vertices. E.g: We generate P1 / P4
 * as extra corner vertices to make the corner looks round and smoother.
 *
 * Due to the fact that the alpha is not linear interpolated along the inner
 * edge, when the alpha is different, we may add extra vertices such as P2.1, P2.2,
 * V0.1, V0.2 to avoid the visual artifacts.
 *
 *                                            (P3)
 *          (P2)     (P2.1)     (P2.2)         |     ' (P4)
 *   (P1)'   |        |           |            |   '
 *         ' |        |           |            | '
 * (P0)  ------------------------------------------------(P5)
 *           | (V0)   (V0.1)    (V0.2)         |(V1)
 *           |                                 |
 *           |                                 |
 *           |               (C)               |
 *           |                                 |
 *           |                                 |
 *           |                                 |
 *           |                                 |
 *        (V3)-----------------------------------(V2)
 */
void AmbientShadow::createAmbientShadow(bool isCasterOpaque,
        const Vector3* casterVertices, int casterVertexCount, const Vector3& centroid3d,
        float heightFactor, float geomFactor, VertexBuffer& shadowVertexBuffer) {
    shadowVertexBuffer.setMode(VertexBuffer::kIndices);

    // In order to computer the outer vertices in one loop, we need pre-compute
    // the normal by the vertex (n - 1) to vertex 0, and the spike and alpha value
    // for vertex 0.
    Vector2 previousNormal = getNormalFromVertices(casterVertices,
            casterVertexCount - 1 , 0);
    Vector2 currentSpike = {casterVertices[0].x - centroid3d.x,
        casterVertices[0].y - centroid3d.y};
    currentSpike.normalize();
    float currentAlpha = getAlphaFromFactoredZ(casterVertices[0].z * heightFactor);

    // Preparing all the output data.
    int totalVertexCount, totalIndexCount, totalUmbraCount;
    computeBufferSize(&totalVertexCount, &totalIndexCount, &totalUmbraCount,
            casterVertexCount, isCasterOpaque);
    AlphaVertex* shadowVertices =
            shadowVertexBuffer.alloc<AlphaVertex>(totalVertexCount);
    int vertexBufferIndex = 0;
    uint16_t* indexBuffer = shadowVertexBuffer.allocIndices<uint16_t>(totalIndexCount);
    int indexBufferIndex = 0;
    uint16_t umbraVertices[totalUmbraCount];
    int umbraIndex = 0;

    for (int i = 0; i < casterVertexCount; i++)  {
        // Corner: first figure out the extra vertices we need for the corner.
        const Vector3& innerVertex = casterVertices[i];
        Vector2 currentNormal = getNormalFromVertices(casterVertices, i,
                (i + 1) % casterVertexCount);

        int extraVerticesNumber = ShadowTessellator::getExtraVertexNumber(currentNormal,
                previousNormal, CORNER_RADIANS_DIVISOR);

        float expansionDist = innerVertex.z * heightFactor * geomFactor;
        const int cornerSlicesNumber = extraVerticesNumber + 1; // Minimal as 1.
#if DEBUG_SHADOW
        ALOGD("cornerSlicesNumber is %d", cornerSlicesNumber);
#endif

        // Corner: fill the corner Vertex Buffer(VB) and Index Buffer(IB).
        // We fill the inner vertex first, such that we can fill the index buffer
        // inside the loop.
        int currentInnerVertexIndex = vertexBufferIndex;
        if (!isCasterOpaque) {
            umbraVertices[umbraIndex++] = vertexBufferIndex;
        }
        AlphaVertex::set(&shadowVertices[vertexBufferIndex++], casterVertices[i].x,
                casterVertices[i].y,
                getTransformedAlphaFromAlpha(currentAlpha));

        const Vector3& innerStart = casterVertices[i];

        // outerStart is the first outer vertex for this inner vertex.
        // outerLast is the last outer vertex for this inner vertex.
        Vector2 outerStart = {0, 0};
        Vector2 outerLast = {0, 0};
        // This will create vertices from [0, cornerSlicesNumber] inclusively,
        // which means minimally 2 vertices even without the extra ones.
        for (int j = 0; j <= cornerSlicesNumber; j++) {
            Vector2 averageNormal =
                previousNormal * (cornerSlicesNumber - j) + currentNormal * j;
            averageNormal /= cornerSlicesNumber;
            averageNormal.normalize();
            Vector2 outerVertex;
            outerVertex.x = innerVertex.x + averageNormal.x * expansionDist;
            outerVertex.y = innerVertex.y + averageNormal.y * expansionDist;

            indexBuffer[indexBufferIndex++] = vertexBufferIndex;
            indexBuffer[indexBufferIndex++] = currentInnerVertexIndex;
            AlphaVertex::set(&shadowVertices[vertexBufferIndex++], outerVertex.x,
                    outerVertex.y, OUTER_OPACITY);

            if (j == 0) {
                outerStart = outerVertex;
            } else if (j == cornerSlicesNumber) {
                outerLast = outerVertex;
            }
        }
        previousNormal = currentNormal;

        // Edge: first figure out the extra vertices needed for the edge.
        const Vector3& innerNext = casterVertices[(i + 1) % casterVertexCount];
        float nextAlpha = getAlphaFromFactoredZ(innerNext.z * heightFactor);
        if (needsExtraForEdge(currentAlpha, nextAlpha)) {
            // TODO: See if we can / should cache this outer vertex across the loop.
            Vector2 outerNext;
            float expansionDist = innerNext.z * heightFactor * geomFactor;
            outerNext.x = innerNext.x + currentNormal.x * expansionDist;
            outerNext.y = innerNext.y + currentNormal.y * expansionDist;

            // Compute the angle and see how many extra points we need.
            int extraVerticesNumber = getEdgeExtraAndUpdateSpike(&currentSpike,
                    innerNext, centroid3d);
#if DEBUG_SHADOW
            ALOGD("extraVerticesNumber %d for edge %d", extraVerticesNumber, i);
#endif
            // Edge: fill the edge's VB and IB.
            // This will create vertices pair from [1, extraVerticesNumber - 1].
            // If there is no extra vertices created here, the edge will be drawn
            // as just 2 triangles.
            for (int k = 1; k < extraVerticesNumber; k++) {
                int startWeight = extraVerticesNumber - k;
                Vector2 currentOuter =
                    (outerLast * startWeight + outerNext * k) / extraVerticesNumber;
                indexBuffer[indexBufferIndex++] = vertexBufferIndex;
                AlphaVertex::set(&shadowVertices[vertexBufferIndex++], currentOuter.x,
                        currentOuter.y, OUTER_OPACITY);

                if (!isCasterOpaque) {
                    umbraVertices[umbraIndex++] = vertexBufferIndex;
                }
                Vector3 currentInner =
                    (innerStart * startWeight + innerNext * k) / extraVerticesNumber;
                indexBuffer[indexBufferIndex++] = vertexBufferIndex;
                AlphaVertex::set(&shadowVertices[vertexBufferIndex++], currentInner.x,
                        currentInner.y,
                        getTransformedAlphaFromFactoredZ(currentInner.z * heightFactor));
            }
        }
        currentAlpha = nextAlpha;
    }

    indexBuffer[indexBufferIndex++] = 1;
    indexBuffer[indexBufferIndex++] = 0;

    if (!isCasterOpaque) {
        // Add the centroid as the last one in the vertex buffer.
        float centroidOpacity =
            getTransformedAlphaFromFactoredZ(centroid3d.z * heightFactor);
        int centroidIndex = vertexBufferIndex;
        AlphaVertex::set(&shadowVertices[vertexBufferIndex++], centroid3d.x,
                centroid3d.y, centroidOpacity);

        for (int i = 0; i < umbraIndex; i++) {
            // Note that umbraVertices[0] is always 0.
            // So the start and the end of the umbra are using the "0".
            // And penumbra ended with 0, so a degenerated triangle is formed b/t
            // the umbra and penumbra.
            indexBuffer[indexBufferIndex++] = umbraVertices[i];
            indexBuffer[indexBufferIndex++] = centroidIndex;
        }
        indexBuffer[indexBufferIndex++] = 0;
    }

    // At the end, update the real index and vertex buffer size.
    shadowVertexBuffer.updateVertexCount(vertexBufferIndex);
    shadowVertexBuffer.updateIndexCount(indexBufferIndex);
    shadowVertexBuffer.computeBounds<AlphaVertex>();

    ShadowTessellator::checkOverflow(vertexBufferIndex, totalVertexCount, "Ambient Vertex Buffer");
    ShadowTessellator::checkOverflow(indexBufferIndex, totalIndexCount, "Ambient Index Buffer");
    ShadowTessellator::checkOverflow(umbraIndex, totalUmbraCount, "Ambient Umbra Buffer");

#if DEBUG_SHADOW
    for (int i = 0; i < vertexBufferIndex; i++) {
        ALOGD("vertexBuffer i %d, (%f, %f %f)", i, shadowVertices[i].x, shadowVertices[i].y,
                shadowVertices[i].alpha);
    }
    for (int i = 0; i < indexBufferIndex; i++) {
        ALOGD("indexBuffer i %d, indexBuffer[i] %d", i, indexBuffer[i]);
    }
#endif
}

}; // namespace uirenderer
}; // namespace android
