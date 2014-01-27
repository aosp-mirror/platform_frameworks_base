
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

#ifndef ANDROID_HWUI_SHADOW_TESSELLATOR_H
#define ANDROID_HWUI_SHADOW_TESSELLATOR_H

#include "Debug.h"
#include "Matrix.h"

namespace android {
namespace uirenderer {

class ShadowTessellator {
public:
    static void tessellateAmbientShadow(const Vector3* casterPolygon, int casterVertexCount,
            VertexBuffer& shadowVertexBuffer);

    static void tessellateSpotShadow(const Vector3* casterPolygon, int casterVertexCount,
            const Vector3& lightPosScale, const mat4& receiverTransform,
            int screenWidth, int screenHeight, VertexBuffer& shadowVertexBuffer);
}; // ShadowTessellator

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_SHADOW_TESSELLATOR_H
