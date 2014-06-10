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

namespace android {
namespace uirenderer {

class MathUtils {
private:
    static const float gNonZeroEpsilon = 0.001f;
public:
    /**
     * Check for floats that are close enough to zero.
     */
    inline static bool isZero(float value) {
        return (value >= -gNonZeroEpsilon) && (value <= gNonZeroEpsilon);
    }

    inline static bool isPositive(float value) {
        return value >= gNonZeroEpsilon;
    }

    inline static bool areEqual(float valueA, float valueB) {
        return isZero(valueA - valueB);
    }

    inline static int max(int a, int b) {
        return a > b ? a : b;
    }

    inline static int min(int a, int b) {
        return a < b ? a : b;
    }

    inline static float lerp(float v1, float v2, float t) {
        return v1 + ((v2 - v1) * t);
    }
}; // class MathUtils

} /* namespace uirenderer */
} /* namespace android */

#endif /* MATHUTILS_H */
