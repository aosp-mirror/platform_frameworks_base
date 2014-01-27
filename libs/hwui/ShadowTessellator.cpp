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

void ShadowTessellator::tessellateAmbientShadow(const Vector3* casterPolygon, int casterVertexCount,
        VertexBuffer& shadowVertexBuffer) {
    // A bunch of parameters to tweak the shadow.
    // TODO: Allow some of these changable by debug settings or APIs.
    const int rays = 128;
    const int layers = 2;
    const float strength = 0.5;
    const float heightFactor = 128;
    const float geomFactor = 64;

    AmbientShadow::createAmbientShadow(casterPolygon, casterVertexCount, rays, layers, strength,
            heightFactor, geomFactor, shadowVertexBuffer);

}

void ShadowTessellator::tessellateSpotShadow(const Vector3* casterPolygon, int casterVertexCount,
        const Vector3& lightPosScale, const mat4& receiverTransform,
        int screenWidth, int screenHeight, VertexBuffer& shadowVertexBuffer) {
    // A bunch of parameters to tweak the shadow.
    // TODO: Allow some of these changable by debug settings or APIs.
    const int rays = 256;
    const int layers = 2;
    const float strength = 0.5;
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

    SpotShadow::createSpotShadow(casterPolygon, casterVertexCount, lightCenter, lightSize,
            lightVertexCount, rays, layers, strength, shadowVertexBuffer);

}
}; // namespace uirenderer
}; // namespace android
