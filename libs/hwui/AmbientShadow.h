
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
#include "OpenGLRenderer.h"
#include "Vector.h"
#include "VertexBuffer.h"

namespace android {
namespace uirenderer {

/**
 * AmbientShadow is used to calculate the ambient shadow value around a polygon.
 */
class AmbientShadow {
public:
    static void createAmbientShadow(bool isCasterOpaque, const Vector3* poly,
            int polyLength, const Vector3& centroid3d, float heightFactor,
            float geomFactor, VertexBuffer& shadowVertexBuffer);
}; // AmbientShadow

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_AMBIENT_SHADOW_H
