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

#ifndef ANDROID_HWUI_UV_MAPPER_H
#define ANDROID_HWUI_UV_MAPPER_H

#include "Rect.h"

namespace android {
namespace uirenderer {

/**
 * This class can be used to map UV coordinates from the [0..1]
 * range to other arbitrary ranges. All the methods below assume
 * that the input values lie in the [0..1] range already.
 */
class UvMapper {
public:
    /**
     * Using this constructor is equivalent to not using any mapping at all.
     * UV coordinates in the [0..1] range remain in the [0..1] range.
     */
    UvMapper() : mIdentity(true), mMinU(0.0f), mMaxU(1.0f), mMinV(0.0f), mMaxV(1.0f) {}

    /**
     * Creates a new mapper with the specified ranges for U and V coordinates.
     * The parameter minU must be < maxU and minV must be < maxV.
     */
    UvMapper(float minU, float maxU, float minV, float maxV)
            : mMinU(minU), mMaxU(maxU), mMinV(minV), mMaxV(maxV) {
        checkIdentity();
    }

    /**
     * Returns true if calling the map*() methods has no effect (that is,
     * texture coordinates remain in the 0..1 range.)
     */
    bool isIdentity() const { return mIdentity; }

    /**
     * Changes the U and V mapping ranges.
     * The parameter minU must be < maxU and minV must be < maxV.
     */
    void setMapping(float minU, float maxU, float minV, float maxV) {
        mMinU = minU;
        mMaxU = maxU;
        mMinV = minV;
        mMaxV = maxV;
        checkIdentity();
    }

    /**
     * Maps a single value in the U range.
     */
    void mapU(float& u) const {
        if (!mIdentity) u = lerp(mMinU, mMaxU, u);
    }

    /**
     * Maps a single value in the V range.
     */
    void mapV(float& v) const {
        if (!mIdentity) v = lerp(mMinV, mMaxV, v);
    }

    /**
     * Maps the specified rectangle in place. This method assumes:
     * - left = min. U
     * - top = min. V
     * - right = max. U
     * - bottom = max. V
     */
    void map(Rect& texCoords) const {
        if (!mIdentity) {
            texCoords.left = lerp(mMinU, mMaxU, texCoords.left);
            texCoords.right = lerp(mMinU, mMaxU, texCoords.right);
            texCoords.top = lerp(mMinV, mMaxV, texCoords.top);
            texCoords.bottom = lerp(mMinV, mMaxV, texCoords.bottom);
        }
    }

    /**
     * Maps the specified UV coordinates in place.
     */
    void map(float& u1, float& v1, float& u2, float& v2) const {
        if (!mIdentity) {
            u1 = lerp(mMinU, mMaxU, u1);
            u2 = lerp(mMinU, mMaxU, u2);
            v1 = lerp(mMinV, mMaxV, v1);
            v2 = lerp(mMinV, mMaxV, v2);
        }
    }

    void dump() const {
        ALOGD("mapper[minU=%.2f maxU=%.2f minV=%.2f maxV=%.2f]", mMinU, mMaxU, mMinV, mMaxV);
    }

private:
    static float lerp(float start, float stop, float amount) {
        return start + (stop - start) * amount;
    }

    void checkIdentity() {
        mIdentity = mMinU == 0.0f && mMaxU == 1.0f && mMinV == 0.0f && mMaxV == 1.0f;
    }

    bool mIdentity;
    float mMinU;
    float mMaxU;
    float mMinV;
    float mMaxV;
};

};  // namespace uirenderer
};  // namespace android

#endif  // ANDROID_HWUI_UV_MAPPER_H
