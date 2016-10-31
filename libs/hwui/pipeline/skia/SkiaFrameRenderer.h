/*
 * Copyright (C) 2016 The Android Open Source Project
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

#pragma once

namespace android {
namespace uirenderer {
namespace skiapipeline {

/**
 * TODO: this is a stub that will be added in a subsquent CL
 */
class SkiaFrameRenderer {
public:

    static bool skpCaptureEnabled() { return false; }

    // TODO avoids unused compile error but we need to pass this to the reorder drawables!
    static float getLightRadius() {
        return 1.0f;
    }

    static uint8_t getAmbientShadowAlpha() {
        return 1;
    }

    static uint8_t getSpotShadowAlpha() {
        return 1;
    }

    static Vector3 getLightCenter() {
        Vector3 result;
        result.x = result.y = result.z = 1.0f;
        return result;
    }

};

}; // namespace skiapipeline
}; // namespace uirenderer
}; // namespace android
