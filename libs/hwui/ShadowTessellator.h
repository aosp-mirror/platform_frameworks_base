
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
#include "OpenGLRenderer.h"
#include "VertexBuffer.h"

namespace android {
namespace uirenderer {

// All SHADOW_* are used to define all the geometry property of shadows.
// Use a simplified example to illustrate the geometry setup here.
// Assuming we use 6 rays and only 1 layer, Then we will have 2 hexagons, which
// are 0 to 5 and 6 to 11. The area between them will be the penumbra area, and
// the area inside the 2nd hexagon is the umbra.
// Ambient shadow is using only 1 layer for opaque caster, otherwise, spot
// shadow and ambient shadow are using 2 layers.
// Triange strip indices for penumbra area: (0, 6, 1, 7, 2, 8, 3, 9, 4, 10, 5, 11, 0, 6)
//                 0
//
//      5          6         1
//           11         7
//
//           10         8
//      4          9         2
//
//                 3

// The total number of rays starting from the centroid of shadow area, in order
// to generate the shadow geometry.
#define SHADOW_RAY_COUNT 128

// The total number of all the vertices representing the shadow.
// For the case we only have 1 layer, then we will just fill only 2/3 of it.
#define SHADOW_VERTEX_COUNT (3 * SHADOW_RAY_COUNT)

// The total number of indices used for drawing the shadow geometry as triangle strips.
// Depending on the mode we are drawing, we can have 1 layer or 2 layers.
// Therefore, we only build the longer index buffer.
#define TWO_POLY_RING_SHADOW_INDEX_COUNT (4 * (SHADOW_RAY_COUNT + 1))
#define ONE_POLY_RING_SHADOW_INDEX_COUNT (2 * (SHADOW_RAY_COUNT + 1))

#define MAX_SHADOW_INDEX_COUNT TWO_POLY_RING_SHADOW_INDEX_COUNT

#define SHADOW_MIN_CASTER_Z 0.001f

#define MINIMAL_DELTA_THETA (M_PI / 180 / 1000)

class ShadowTessellator {
public:
    static void tessellateAmbientShadow(bool isCasterOpaque,
            const Vector3* casterPolygon, int casterVertexCount,
            const Vector3& centroid3d,  const Rect& casterBounds,
            const Rect& localClip, float maxZ, VertexBuffer& shadowVertexBuffer);

    static void tessellateSpotShadow(bool isCasterOpaque,
            const Vector3* casterPolygon, int casterVertexCount, const Vector3& casterCentroid,
            const mat4& receiverTransform, const Vector3& lightCenter, int lightRadius,
            const Rect& casterBounds, const Rect& localClip, VertexBuffer& shadowVertexBuffer);

    static void generateShadowIndices(uint16_t*  shadowIndices);

    static Vector2 centroid2d(const Vector2* poly, int polyLength);

    static bool isClockwise(const Vector2* polygon, int len);

    static Vector2 calculateNormal(const Vector2& p1, const Vector2& p2);
    /**
     * Determine whether the path is clockwise, using the control points.
     *
     * TODO: Given the skia is using inverted Y coordinate, shadow system needs
     * to convert to the same coordinate to avoid the extra reverse.
     *
     * @param path The path to be examined.
     */
    static bool isClockwisePath(const SkPath &path);

    /**
     * Reverse the vertex array.
     *
     * @param polygon The vertex array to be reversed.
     * @param len The length of the vertex array.
     */
    static void reverseVertexArray(Vertex* polygon, int len);

    static int getExtraVertexNumber(const Vector2& vector1, const Vector2& vector2,
            float divisor);

    static void checkOverflow(int used, int total, const char* bufferName);
}; // ShadowTessellator

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_SHADOW_TESSELLATOR_H
