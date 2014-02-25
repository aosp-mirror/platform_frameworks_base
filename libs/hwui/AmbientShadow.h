
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

#ifndef ANDROID_HWUI_AMBIENT_SHADOW_H
#define ANDROID_HWUI_AMBIENT_SHADOW_H

#include "Debug.h"
#include "Vector.h"
#include "VertexBuffer.h"

namespace android {
namespace uirenderer {

/**
 * AmbientShadow is used to calculate the ambient shadow value around a polygon.
 *
 * TODO: calculateIntersection() now is O(N*M), where N is the number of
 * polygon's vertics and M is the number of rays. In fact, by staring tracing
 * the vertex from the previous intersection, the algorithm can be O(N + M);
 */
class AmbientShadow {
public:
    static void createAmbientShadow(const Vector3* poly, int polyLength,
            const Vector3& centroid3d, float heightFactor, float geomFactor,
            VertexBuffer& shadowVertexBuffer);

private:
    static void calculateRayDirections(int rays, Vector2* dir);

    static void calculateIntersection(const Vector3* poly, int nbVertices,
            const Vector3& start, const Vector2& dir, int& outEdgeIndex,
            float& outEdgeFraction, float& outRayDist);

    static void calculateNormal(int rays, int currentRayIndex, const Vector2* dir,
            const float* rayDist, Vector2& normal);
}; // AmbientShadow

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_AMBIENT_SHADOW_H
