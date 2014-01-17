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

#include "AmbientShadow.h"
#include "ShadowTessellator.h"
#include "SpotShadow.h"

namespace android {
namespace uirenderer {

template<typename T>
static inline T max(T a, T b) {
    return a > b ? a : b;
}

// TODO: Support path as the input of the polygon instead of the rect's width
// and height. And the z values need to be computed according to the
// transformation for each vertex.
/**
 * Generate the polygon for the caster.
 *
 * @param width the width of the caster
 * @param height the height of the caster
 * @param casterTransform transformation info of the caster
 * @param polygon return the caster's polygon
 *
 */
void ShadowTessellator::generateCasterPolygon(float width, float height,
        const mat4& casterTransform, int vertexCount, Vector3* polygon) {
    Rect blockRect(0, 0, width, height);
    polygon[0].x = blockRect.left;
    polygon[0].y = blockRect.top;
    polygon[0].z = 0;
    polygon[1].x = blockRect.right;
    polygon[1].y = blockRect.top;
    polygon[1].z = 0;
    polygon[2].x = blockRect.right;
    polygon[2].y = blockRect.bottom;
    polygon[2].z = 0;
    polygon[3].x = blockRect.left;
    polygon[3].y = blockRect.bottom;
    polygon[3].z = 0;
    casterTransform.mapPoint3d(polygon[0]);
    casterTransform.mapPoint3d(polygon[1]);
    casterTransform.mapPoint3d(polygon[2]);
    casterTransform.mapPoint3d(polygon[3]);
}

void ShadowTessellator::tessellateAmbientShadow(float width, float height,
        const mat4& casterTransform, VertexBuffer& shadowVertexBuffer) {

    const int vertexCount = 4;
    Vector3 polygon[vertexCount];
    generateCasterPolygon(width, height, casterTransform, vertexCount, polygon);

    // A bunch of parameters to tweak the shadow.
    // TODO: Allow some of these changable by debug settings or APIs.
    const int rays = 128;
    const int layers = 2;
    const float strength = 0.5;
    const float heightFactor = 128;
    const float geomFactor = 64;

    AmbientShadow::createAmbientShadow(polygon, vertexCount, rays, layers, strength,
            heightFactor, geomFactor, shadowVertexBuffer);

}

void ShadowTessellator::tessellateSpotShadow(float width, float height,
        int screenWidth, int screenHeight,
        const mat4& casterTransform, VertexBuffer& shadowVertexBuffer) {
    const int vertexCount = 4;
    Vector3 polygon[vertexCount];
    generateCasterPolygon(width, height, casterTransform, vertexCount, polygon);

    // A bunch of parameters to tweak the shadow.
    // TODO: Allow some of these changable by debug settings or APIs.
    const int rays = 256;
    const int layers = 2;
    const float strength = 0.5;
    int maximal = max(screenWidth, screenHeight);
    Vector3 lightCenter(screenWidth / 2, 0, maximal);
#if DEBUG_SHADOW
    ALOGD("light center %f %f %f", lightCenter.x, lightCenter.y, lightCenter.z);
#endif
    const float lightSize = maximal / 8;
    const int lightVertexCount = 16;

    SpotShadow::createSpotShadow(polygon, vertexCount, lightCenter, lightSize,
            lightVertexCount, rays, layers, strength, shadowVertexBuffer);

}
}; // namespace uirenderer
}; // namespace android
