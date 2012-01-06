/*
 * Copyright (C) 2009 The Android Open Source Project
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

#include "rsContext.h"
#include "rsAnimation.h"


using namespace android;
using namespace android::renderscript;

void Animation::serialize(OStream *stream) const {
}

Animation *Animation::createFromStream(Context *rsc, IStream *stream) {
    return NULL;
}

/*
Animation::Animation(Context *rsc) : ObjectBase(rsc)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;

    mValuesInput = NULL;
    mValuesOutput = NULL;
    mValueCount = 0;
    mInterpolation = RS_ANIMATION_INTERPOLATION_STEP;
    mEdgePre = RS_ANIMATION_EDGE_UNDEFINED;
    mEdgePost = RS_ANIMATION_EDGE_UNDEFINED;
    mInputMin = 0;
    mInputMax = 0;
}

Animation * Animation::create(Context *rsc,
                              const float *inValues, const float *outValues,
                              uint32_t valueCount, RsAnimationInterpolation interp,
                              RsAnimationEdge pre, RsAnimationEdge post)
{
    if (valueCount < 2) {
        rsc->setError(RS_ERROR_BAD_VALUE, "Animations require more than 2 values.");
        return NULL;
    }
    Animation *a = new Animation(rsc);
    if (!a) {
        rsc->setError(RS_ERROR_OUT_OF_MEMORY);
        return NULL;
    }

    float *vin = (float *)malloc(valueCount * sizeof(float));
    float *vout = (float *)malloc(valueCount * sizeof(float));
    a->mValuesInput = vin;
    a->mValuesOutput = vout;
    if (a->mValuesInput == NULL || a->mValuesOutput == NULL) {
        delete a;
        rsc->setError(RS_ERROR_OUT_OF_MEMORY);
        return NULL;
    }

    a->mEdgePre = pre;
    a->mEdgePost = post;
    a->mInterpolation = interp;
    a->mValueCount = valueCount;

    memcpy(vin, inValues, valueCount * sizeof(float));
    memcpy(vout, outValues, valueCount * sizeof(float));
    a->mInputMin = inValues[0];
    a->mInputMax = inValues[0];

    bool needSort = false;
    for (uint32_t ct=1; ct < valueCount; ct++) {
        if (a->mInputMin > vin[ct]) {
            needSort = true;
            a->mInputMin = vin[ct];
        }
        if (a->mInputMax < vin[ct]) {
            a->mInputMax = vin[ct];
        } else {
            needSort = true;
        }
    }

    while (1) {
        bool changed = false;
        for (uint32_t ct=1; ct < valueCount; ct++) {
            if (vin[ct-1] > vin[ct]) {
                float t = vin[ct-1];
                vin[ct-1] = vin[ct];
                vin[ct] = t;
                t = vout[ct-1];
                vout[ct-1] = vout[ct];
                vout[ct] = t;
                changed = true;
            }
        }
        if (!changed) break;
    }

    return a;
}
*/


/////////////////////////////////////////
//

namespace android {
namespace renderscript {

RsAnimation rsi_AnimationCreate(Context *rsc,
                                const float *inValues,
                                const float *outValues,
                                uint32_t valueCount,
                                RsAnimationInterpolation interp,
                                RsAnimationEdge pre,
                                RsAnimationEdge post) {
    //ALOGE("rsi_ElementCreate %i %i %i %i", dt, dk, norm, vecSize);
    Animation *a = NULL;//Animation::create(rsc, inValues, outValues, valueCount, interp, pre, post);
    if (a != NULL) {
        a->incUserRef();
    }
    return (RsAnimation)a;
}


}
}

