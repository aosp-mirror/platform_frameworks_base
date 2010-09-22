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

#ifndef ANDROID_RS_ANIMATION_H
#define ANDROID_RS_ANIMATION_H

#include "rsUtils.h"
#include "rsObjectBase.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {


class Animation : public ObjectBase
{
public:
    ~Animation();

    static Animation * create(Context *rsc,
                              const float *inValues, const float *outValues,
                              uint32_t valueCount, RsAnimationInterpolation,
                              RsAnimationEdge pre, RsAnimationEdge post);

    float eval(float) const;

    virtual void serialize(OStream *stream) const;
    virtual RsA3DClassID getClassId() const { return RS_A3D_CLASS_ID_ANIMATION; }
    static Animation *createFromStream(Context *rsc, IStream *stream);

protected:
    Animation(Context *rsc);



    float evalInRange(float) const;



    const float *mValuesInput;
    const float *mValuesOutput;
    uint32_t mValueCount;
    RsAnimationInterpolation mInterpolation;
    RsAnimationEdge mEdgePre;
    RsAnimationEdge mEdgePost;

    // derived
    float mInputMin;
    float mInputMax;
};




}
}
#endif //ANDROID_STRUCTURED_ELEMENT_H

