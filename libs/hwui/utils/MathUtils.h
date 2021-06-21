/*
 * Copyright (C) 2014 The Android Open Source Project
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
#ifndef MATHUTILS_H
#define MATHUTILS_H

#include <math.h>
#include <algorithm>

namespace android {
namespace uirenderer {

class MathUtils {
public:
    static constexpr float NON_ZERO_EPSILON = 0.001f;
    static constexpr float ALPHA_EPSILON = 0.001f;

    /**
     * Check for floats that are close enough to zero.
     */
    inline static bool isZero(float value) {
        // Using fabsf is more performant as ARM computes
        // fabsf in a single instruction.
        return fabsf(value) <= NON_ZERO_EPSILON;
    }

    inline static bool isOne(float value) {
        return areEqual(value, 1.0f);
    }

    inline static bool isPositive(float value) { return value >= NON_ZERO_EPSILON; }

    /**
     * Clamps alpha value, and snaps when very near 0 or 1
     */
    inline static float clampAlpha(float alpha) {
        if (alpha <= ALPHA_EPSILON) {
            return 0;
        } else if (alpha >= (1 - ALPHA_EPSILON)) {
            return 1;
        } else {
            return alpha;
        }
    }

    /*
     * Clamps positive tessellation scale values
     */
    inline static float clampTessellationScale(float scale) {
        const float MIN_SCALE = 0.0001;
        const float MAX_SCALE = 1e10;
        if (scale < MIN_SCALE) {
            return MIN_SCALE;
        } else if (scale > MAX_SCALE) {
            return MAX_SCALE;
        }
        return scale;
    }

    /**
     * Returns the number of points (beyond two, the start and end) needed to form a polygonal
     * approximation of an arc, with a given threshold value.
     */
    inline static int divisionsNeededToApproximateArc(float radius, float angleInRads,
                                                      float threshold) {
        const float errConst = (-threshold / radius + 1);
        const float targetCosVal = 2 * errConst * errConst - 1;

        // needed divisions are rounded up from approximation
        return (int)(ceilf(angleInRads / acos(targetCosVal) / 2)) * 2;
    }

    inline static bool areEqual(float valueA, float valueB) { return isZero(valueA - valueB); }

    template <typename T>
    static inline T clamp(T a, T minValue, T maxValue) {
        return std::min(std::max(a, minValue), maxValue);
    }

    inline static float lerp(float v1, float v2, float t) { return v1 + ((v2 - v1) * t); }
};  // class MathUtils

} /* namespace uirenderer */
} /* namespace android */

#endif /* MATHUTILS_H */
