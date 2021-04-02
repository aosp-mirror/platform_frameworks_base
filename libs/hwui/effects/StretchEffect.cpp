/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include "StretchEffect.h"
#include <SkImageFilter.h>
#include <SkRefCnt.h>
#include <SkRuntimeEffect.h>
#include <SkString.h>
#include <SkSurface.h>
#include <include/effects/SkImageFilters.h>

#include <memory>

namespace android::uirenderer {

static const SkString stretchShader = SkString(R"(
    uniform shader uContentTexture;

    // multiplier to apply to scale effect
    uniform float uMaxStretchIntensity;

    // Maximum percentage to stretch beyond bounds  of target
    uniform float uStretchAffectedDistX;
    uniform float uStretchAffectedDistY;

    // Distance stretched as a function of the normalized overscroll times
    // scale intensity
    uniform float uDistanceStretchedX;
    uniform float uDistanceStretchedY;
    uniform float uDistDiffX;

    // Difference between the peak stretch amount and overscroll amount normalized
    uniform float uDistDiffY;

    // Horizontal offset represented as a ratio of pixels divided by the target width
    uniform float uScrollX;
    // Vertical offset represented as a ratio of pixels divided by the target height
    uniform float uScrollY;

    // Normalized overscroll amount in the horizontal direction
    uniform float uOverscrollX;

    // Normalized overscroll amount in the vertical direction
    uniform float uOverscrollY;
    uniform float viewportWidth; // target height in pixels
    uniform float viewportHeight; // target width in pixels

    float computeOverscrollStart(
        float inPos,
        float overscroll,
        float uStretchAffectedDist,
        float distanceStretched
    ) {
        float offsetPos = uStretchAffectedDist - inPos;
        float posBasedVariation = smoothstep(0., uStretchAffectedDist, offsetPos);
        float stretchIntensity = overscroll * posBasedVariation;
        return distanceStretched - (offsetPos / (1. + stretchIntensity));
    }

    float computeOverscrollEnd(
        float inPos,
        float overscroll,
        float reverseStretchDist,
        float uStretchAffectedDist,
        float distanceStretched
    ) {
        float offsetPos = inPos - reverseStretchDist;
        float posBasedVariation = (smoothstep(0., uStretchAffectedDist, offsetPos));
        float stretchIntensity = (-overscroll) * posBasedVariation;
        return 1 - (distanceStretched - (offsetPos / (1. + stretchIntensity)));
    }

    // Prefer usage of return values over out parameters as it enables
    // SKSL to properly inline method calls and works around potential GPU
    // driver issues on Wembly. See b/182566543 for details
    float computeOverscroll(
        float inPos,
        float overscroll,
        float uStretchAffectedDist,
        float distanceStretched,
        float distanceDiff
    ) {
      float outPos = inPos;
      if (overscroll > 0) {
            if (inPos <= uStretchAffectedDist) {
                outPos = computeOverscrollStart(
                  inPos,
                  overscroll,
                  uStretchAffectedDist,
                  distanceStretched
                );
            } else if (inPos >= distanceStretched) {
                outPos = distanceDiff + inPos;
            }
        }
        if (overscroll < 0) {
            float stretchAffectedDist = 1. - uStretchAffectedDist;
            if (inPos >= stretchAffectedDist) {
                outPos = computeOverscrollEnd(
                  inPos,
                  overscroll,
                  stretchAffectedDist,
                  uStretchAffectedDist,
                  distanceStretched
                );
            } else if (inPos < stretchAffectedDist) {
                outPos = -distanceDiff + inPos;
            }
        }
        return outPos;
    }

    vec4 main(vec2 coord) {
        // Normalize SKSL pixel coordinate into a unit vector
        float inU = coord.x / viewportWidth;
        float inV = coord.y / viewportHeight;
        float outU;
        float outV;
        float stretchIntensity;
        // Add the normalized scroll position within scrolling list
        inU += uScrollX;
        inV += uScrollY;
        outU = inU;
        outV = inV;
        outU = computeOverscroll(
            inU,
            uOverscrollX,
            uStretchAffectedDistX,
            uDistanceStretchedX,
            uDistDiffX
        );
        outV = computeOverscroll(
            inV,
            uOverscrollY,
            uStretchAffectedDistY,
            uDistanceStretchedY,
            uDistDiffY
        );
        coord.x = outU * viewportWidth;
        coord.y = outV * viewportHeight;
        return sample(uContentTexture, coord);
    })");

static const float ZERO = 0.f;

sk_sp<SkImageFilter> StretchEffect::getImageFilter(const sk_sp<SkImage>& snapshotImage) const {
    if (isEmpty()) {
        return nullptr;
    }

    if (mStretchFilter != nullptr) {
        return mStretchFilter;
    }

    float viewportWidth = stretchArea.width();
    float viewportHeight = stretchArea.height();
    float normOverScrollDistX = mStretchDirection.x();
    float normOverScrollDistY = mStretchDirection.y();
    float distanceStretchedX = maxStretchAmountX / (1 + abs(normOverScrollDistX));
    float distanceStretchedY = maxStretchAmountY / (1 + abs(normOverScrollDistY));
    float diffX = distanceStretchedX;
    float diffY = distanceStretchedY;

    if (mBuilder == nullptr) {
        mBuilder = std::make_unique<SkRuntimeShaderBuilder>(getStretchEffect());
    }

    mBuilder->child("uContentTexture") = snapshotImage->makeShader(
            SkTileMode::kClamp, SkTileMode::kClamp, SkSamplingOptions(SkFilterMode::kLinear));
    mBuilder->uniform("uStretchAffectedDistX").set(&maxStretchAmountX, 1);
    mBuilder->uniform("uStretchAffectedDistY").set(&maxStretchAmountY, 1);
    mBuilder->uniform("uDistanceStretchedX").set(&distanceStretchedX, 1);
    mBuilder->uniform("uDistanceStretchedY").set(&distanceStretchedY, 1);
    mBuilder->uniform("uDistDiffX").set(&diffX, 1);
    mBuilder->uniform("uDistDiffY").set(&diffY, 1);
    mBuilder->uniform("uOverscrollX").set(&normOverScrollDistX, 1);
    mBuilder->uniform("uOverscrollY").set(&normOverScrollDistY, 1);
    mBuilder->uniform("uScrollX").set(&ZERO, 1);
    mBuilder->uniform("uScrollY").set(&ZERO, 1);
    mBuilder->uniform("viewportWidth").set(&viewportWidth, 1);
    mBuilder->uniform("viewportHeight").set(&viewportHeight, 1);

    mStretchFilter = SkImageFilters::Shader(mBuilder->makeShader(nullptr, false),
                                            SkRect{0, 0, viewportWidth, viewportHeight});

    return mStretchFilter;
}

sk_sp<SkRuntimeEffect> StretchEffect::getStretchEffect() {
    const static SkRuntimeEffect::Result instance = SkRuntimeEffect::Make(stretchShader);
    return instance.effect;
}

} // namespace android::uirenderer