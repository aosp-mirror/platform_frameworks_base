
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
#include "VertexBuffer.h"

namespace android {
namespace uirenderer {

// All SHADOW_* are used to define all the geometry property of shadows.
// Use a simplified example to illustrate the geometry setup here.
// Assuming we use 6 rays and only 1 layer, Then we will have 2 hexagons, which
// are 0 to 5 and 6 to 11. The area between them will be the penumbra area, and
// the area inside the 2nd hexagon is the umbra.
// Also, we need to add the centroid "12" to draw the umbra area as triangle fans.
//
// Triange strip indices for penumbra area: (0, 6, 1, 7, 2, 8, 3, 9, 4, 10, 5, 11, 0, 6)
// Triange strip indices for numbra area: (6, 12, 7, 12, 8, 12, 9, 12, 10, 12, 11, 12, 6)
//                 0
//
//      5          6         1
//           11         7
//                12
//           10         8
//      4          9         2
//
//                 3

// The total number of rays starting from the centroid of shadow area, in order
// to generate the shadow geometry.
#define SHADOW_RAY_COUNT 128

// The total number of all the vertices representing the shadow.
#define SHADOW_VERTEX_COUNT (2 * SHADOW_RAY_COUNT + 1)

// The total number of indices used for drawing the shadow geometry as triangle strips.
#define SHADOW_INDEX_COUNT (2 * SHADOW_RAY_COUNT + 1 + 2 * (SHADOW_RAY_COUNT + 1))

#define SHADOW_MIN_CASTER_Z 0.001f

class ShadowTessellator {
public:
    static void tessellateAmbientShadow(const Vector3* casterPolygon,
            int casterVertexCount, const Vector3& centroid3d,
            VertexBuffer& shadowVertexBuffer);

    static void tessellateSpotShadow(const Vector3* casterPolygon, int casterVertexCount,
            const Vector3& lightPosScale, const mat4& receiverTransform,
            int screenWidth, int screenHeight, VertexBuffer& shadowVertexBuffer);

    static void generateShadowIndices(uint16_t*  shadowIndices);

    static Vector2 centroid2d(const Vector2* poly, int polyLength);
}; // ShadowTessellator

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_SHADOW_TESSELLATOR_H
