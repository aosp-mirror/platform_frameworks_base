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
#define ATRACE_TAG ATRACE_TAG_VIEW

#include <math.h>
#include <utils/Log.h>
#include <utils/Trace.h>

#include "AmbientShadow.h"
#include "ShadowTessellator.h"
#include "SpotShadow.h"

namespace android {
namespace uirenderer {

template<typename T>
static inline T max(T a, T b) {
    return a > b ? a : b;
}

void ShadowTessellator::tessellateAmbientShadow(const Vector3* casterPolygon,
        int casterVertexCount, const Vector3& centroid3d,
        VertexBuffer& shadowVertexBuffer) {
    ATRACE_CALL();

    // A bunch of parameters to tweak the shadow.
    // TODO: Allow some of these changable by debug settings or APIs.
    const float heightFactor = 128;
    const float geomFactor = 64;

    AmbientShadow::createAmbientShadow(casterPolygon, casterVertexCount,
            centroid3d, heightFactor, geomFactor, shadowVertexBuffer);

}

void ShadowTessellator::tessellateSpotShadow(const Vector3* casterPolygon, int casterVertexCount,
        const Vector3& lightPosScale, const mat4& receiverTransform,
        int screenWidth, int screenHeight, VertexBuffer& shadowVertexBuffer) {
    ATRACE_CALL();

    // A bunch of parameters to tweak the shadow.
    // TODO: Allow some of these changable by debug settings or APIs.
    int maximal = max(screenWidth, screenHeight);
    Vector3 lightCenter(screenWidth * lightPosScale.x, screenHeight * lightPosScale.y,
            maximal * lightPosScale.z);
#if DEBUG_SHADOW
    ALOGD("light center %f %f %f", lightCenter.x, lightCenter.y, lightCenter.z);
#endif

    // light position (because it's in local space) needs to compensate for receiver transform
    // TODO: should apply to light orientation, not just position
    Matrix4 reverseReceiverTransform;
    reverseReceiverTransform.loadInverse(receiverTransform);
    reverseReceiverTransform.mapPoint3d(lightCenter);

    const float lightSize = maximal / 4;
    const int lightVertexCount = 16;

    SpotShadow::createSpotShadow(casterPolygon, casterVertexCount, lightCenter,
            lightSize, lightVertexCount, shadowVertexBuffer);

}

void ShadowTessellator::generateShadowIndices(uint16_t* shadowIndices) {
    int currentIndex = 0;
    const int layers = SHADOW_LAYER_COUNT;
    const int rays = SHADOW_RAY_COUNT;
    // For the penumbra area.
    for (int i = 0; i < layers; i++) {
        for (int j = 0; j < rays; j++) {
            shadowIndices[currentIndex++] = i * rays + j;
            shadowIndices[currentIndex++] = (i + 1) * rays + j;
        }
        // To close the loop, back to the ray 0.
        shadowIndices[currentIndex++] = i * rays;
        shadowIndices[currentIndex++] = (i + 1) * rays;
    }
    uint16_t base = layers * rays;
    uint16_t centroidIndex = (layers + 1) * rays;
    // For the umbra area, using strips to simulate the fans.
    for (int k = 0; k < rays; k++) {
        shadowIndices[currentIndex++] = base + k;
        shadowIndices[currentIndex++] = centroidIndex;
    }
    shadowIndices[currentIndex++] = base;

#if DEBUG_SHADOW
    if (currentIndex != SHADOW_INDEX_COUNT) {
        ALOGE("vertex index count is wrong. current %d, expected %d",
                currentIndex, SHADOW_INDEX_COUNT);
    }
    for (int i = 0; i < SHADOW_INDEX_COUNT; i++) {
        ALOGD("vertex index is (%d, %d)", i, shadowIndices[i]);
    }
#endif
}

/**
 * Calculate the centroid of a 2d polygon.
 *
 * @param poly The polygon, which is represented in a Vector2 array.
 * @param polyLength The length of the polygon in terms of number of vertices.
 * @return the centroid of the polygon.
 */
Vector2 ShadowTessellator::centroid2d(const Vector2* poly, int polyLength) {
    double sumx = 0;
    double sumy = 0;
    int p1 = polyLength - 1;
    double area = 0;
    for (int p2 = 0; p2 < polyLength; p2++) {
        double x1 = poly[p1].x;
        double y1 = poly[p1].y;
        double x2 = poly[p2].x;
        double y2 = poly[p2].y;
        double a = (x1 * y2 - x2 * y1);
        sumx += (x1 + x2) * a;
        sumy += (y1 + y2) * a;
        area += a;
        p1 = p2;
    }

    Vector2 centroid = poly[0];
    if (area != 0) {
        centroid = Vector2(sumx / (3 * area), sumy / (3 * area));
    } else {
        ALOGE("Area is 0 while computing centroid!");
    }
    return centroid;
}

}; // namespace uirenderer
}; // namespace android
