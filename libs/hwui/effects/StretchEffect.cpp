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
    uniform float uInverseDistanceStretchedX;
    uniform float uInverseDistanceStretchedY;
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

    // uInterpolationStrength is the intensity of the interpolation.
    // if uInterpolationStrength is 0, then the stretch is constant for all the
    // uStretchAffectedDist. if uInterpolationStrength is 1, then stretch intensity
    // is interpolated based on the pixel position in the uStretchAffectedDist area;
    // The closer we are from the scroll anchor point, the more it stretches,
    // and the other way around.
    uniform float uInterpolationStrength;

    float easeIn(float t, float d) {
        return t * d;
    }

    float computeOverscrollStart(
        float inPos,
        float overscroll,
        float uStretchAffectedDist,
        float uInverseStretchAffectedDist,
        float distanceStretched,
        float interpolationStrength
    ) {
        float offsetPos = uStretchAffectedDist - inPos;
        float posBasedVariation = mix(
                1. ,easeIn(offsetPos, uInverseStretchAffectedDist), interpolationStrength);
        float stretchIntensity = overscroll * posBasedVariation;
        return distanceStretched - (offsetPos / (1. + stretchIntensity));
    }

    float computeOverscrollEnd(
        float inPos,
        float overscroll,
        float reverseStretchDist,
        float uStretchAffectedDist,
        float uInverseStretchAffectedDist,
        float distanceStretched,
        float interpolationStrength,
        float viewportDimension
    ) {
        float offsetPos = inPos - reverseStretchDist;
        float posBasedVariation = mix(
                1. ,easeIn(offsetPos, uInverseStretchAffectedDist), interpolationStrength);
        float stretchIntensity = (-overscroll) * posBasedVariation;
        return viewportDimension - (distanceStretched - (offsetPos / (1. + stretchIntensity)));
    }

    // Prefer usage of return values over out parameters as it enables
    // SKSL to properly inline method calls and works around potential GPU
    // driver issues on Wembly. See b/182566543 for details
    float computeOverscroll(
        float inPos,
        float overscroll,
        float uStretchAffectedDist,
        float uInverseStretchAffectedDist,
        float distanceStretched,
        float distanceDiff,
        float interpolationStrength,
        float viewportDimension
    ) {
      if (overscroll > 0) {
        if (inPos <= uStretchAffectedDist) {
            return computeOverscrollStart(
              inPos,
              overscroll,
              uStretchAffectedDist,
              uInverseStretchAffectedDist,
              distanceStretched,
              interpolationStrength
            );
        } else {
            return distanceDiff + inPos;
        }
      } else if (overscroll < 0) {
        float stretchAffectedDist = viewportDimension - uStretchAffectedDist;
        if (inPos >= stretchAffectedDist) {
            return computeOverscrollEnd(
              inPos,
              overscroll,
              stretchAffectedDist,
              uStretchAffectedDist,
              uInverseStretchAffectedDist,
              distanceStretched,
              interpolationStrength,
              viewportDimension
            );
        } else {
            return -distanceDiff + inPos;
        }
      } else {
        return inPos;
      }
    }

    vec4 main(vec2 coord) {
        float inU = coord.x;
        float inV = coord.y;
        float outU;
        float outV;

        inU += uScrollX;
        inV += uScrollY;
        outU = computeOverscroll(
            inU,
            uOverscrollX,
            uStretchAffectedDistX,
            uInverseDistanceStretchedX,
            uDistanceStretchedX,
            uDistDiffX,
            uInterpolationStrength,
            viewportWidth
        );
        outV = computeOverscroll(
            inV,
            uOverscrollY,
            uStretchAffectedDistY,
            uInverseDistanceStretchedY,
            uDistanceStretchedY,
            uDistDiffY,
            uInterpolationStrength,
            viewportHeight
        );
        coord.x = outU;
        coord.y = outV;
        return uContentTexture.eval(coord);
    })");

static const float ZERO = 0.f;
static const float INTERPOLATION_STRENGTH_VALUE = 0.7f;
static const char CONTENT_TEXTURE[] = "uContentTexture";

sk_sp<SkShader> StretchEffect::getShader(float width, float height,
                                         const sk_sp<SkImage>& snapshotImage,
                                         const SkMatrix* matrix) const {
    if (isEmpty()) {
        return nullptr;
    }

    float normOverScrollDistX = mStretchDirection.x();
    float normOverScrollDistY = mStretchDirection.y();
    float distanceStretchedX = width / (1 + abs(normOverScrollDistX));
    float distanceStretchedY = height / (1 + abs(normOverScrollDistY));
    float inverseDistanceStretchedX = 1.f / width;
    float inverseDistanceStretchedY = 1.f / height;
    float diffX = distanceStretchedX - width;
    float diffY = distanceStretchedY - height;

    if (mBuilder == nullptr) {
        mBuilder = std::make_unique<SkRuntimeShaderBuilder>(getStretchEffect());
    }

    mBuilder->child(CONTENT_TEXTURE) =
            snapshotImage->makeShader(SkTileMode::kClamp, SkTileMode::kClamp,
                                      SkSamplingOptions(SkFilterMode::kLinear), matrix);
    mBuilder->uniform("uInterpolationStrength").set(&INTERPOLATION_STRENGTH_VALUE, 1);
    mBuilder->uniform("uStretchAffectedDistX").set(&width, 1);
    mBuilder->uniform("uStretchAffectedDistY").set(&height, 1);
    mBuilder->uniform("uDistanceStretchedX").set(&distanceStretchedX, 1);
    mBuilder->uniform("uDistanceStretchedY").set(&distanceStretchedY, 1);
    mBuilder->uniform("uInverseDistanceStretchedX").set(&inverseDistanceStretchedX, 1);
    mBuilder->uniform("uInverseDistanceStretchedY").set(&inverseDistanceStretchedY, 1);
    mBuilder->uniform("uDistDiffX").set(&diffX, 1);
    mBuilder->uniform("uDistDiffY").set(&diffY, 1);
    mBuilder->uniform("uOverscrollX").set(&normOverScrollDistX, 1);
    mBuilder->uniform("uOverscrollY").set(&normOverScrollDistY, 1);
    mBuilder->uniform("uScrollX").set(&ZERO, 1);
    mBuilder->uniform("uScrollY").set(&ZERO, 1);
    mBuilder->uniform("viewportWidth").set(&width, 1);
    mBuilder->uniform("viewportHeight").set(&height, 1);

    auto result = mBuilder->makeShader();
    mBuilder->child(CONTENT_TEXTURE) = nullptr;
    return result;
}

sk_sp<SkRuntimeEffect> StretchEffect::getStretchEffect() {
    const static SkRuntimeEffect::Result instance = SkRuntimeEffect::MakeForShader(stretchShader);
    return instance.effect;
}

/**
 * Helper method that maps the input texture position to the stretch position
 * based on the given overscroll value that represents an overscroll from
 * either the top or left
 * @param overscroll current overscroll value
 * @param input normalized input position (can be x or y) on the input texture
 * @return stretched position of the input normalized from 0 to 1
 */
float reverseMapStart(float overscroll, float input) {
    float numerator = (-input * overscroll * overscroll) -
        (2 * input * overscroll) - input;
    float denominator = 1.f + (.3f * overscroll) +
        (.7f * input * overscroll * overscroll) + (.7f * input * overscroll);
    return -(numerator / denominator);
}

/**
 * Helper method that maps the input texture position to the stretch position
 * based on the given overscroll value that represents an overscroll from
 * either the bottom or right
 * @param overscroll current overscroll value
 * @param input normalized input position (can be x or y) on the input texture
 * @return stretched position of the input normalized from 0 to 1
 */
float reverseMapEnd(float overscroll, float input) {
    float numerator = (.3f * overscroll * overscroll) -
        (.3f * input * overscroll * overscroll) +
        (1.3f * input * overscroll) - overscroll - input;
    float denominator = (.7f * input * overscroll * overscroll) -
        (.7f * input * overscroll) - (.7f * overscroll * overscroll) +
        overscroll - 1.f;
    return numerator / denominator;
}

/**
  * Calculates the normalized stretch position given the normalized input
  * position. This handles calculating the overscroll from either the
  * top or left vs bottom or right depending on the sign of the given overscroll
  * value
  *
  * @param overscroll unit vector of overscroll from -1 to 1 indicating overscroll
  * from the bottom or right vs top or left respectively
  * @param normalizedInput the
  * @return
  */
float computeReverseOverscroll(float overscroll, float normalizedInput) {
    float distanceStretched = 1.f / (1.f + abs(overscroll));
    float distanceDiff = distanceStretched - 1.f;
    if (overscroll > 0) {
        float output = reverseMapStart(overscroll, normalizedInput);
        if (output <= 1.0f) {
            return output;
        } else if (output >= distanceStretched){
            return output - distanceDiff;
        }
    }

    if (overscroll < 0) {
        float output = reverseMapEnd(overscroll, normalizedInput);
        if (output >= 0.f) {
            return output;
        } else if (output < 0.f){
            return output + distanceDiff;
        }
    }
    return normalizedInput;
}

float StretchEffect::computeStretchedPositionX(float normalizedX) const {
  return computeReverseOverscroll(mStretchDirection.x(), normalizedX);
}

float StretchEffect::computeStretchedPositionY(float normalizedY) const {
  return computeReverseOverscroll(mStretchDirection.y(), normalizedY);
}

} // namespace android::uirenderer