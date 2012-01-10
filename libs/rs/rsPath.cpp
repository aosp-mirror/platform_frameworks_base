/*
 * Copyright (C) 2011 The Android Open Source Project
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

using namespace android;
using namespace android::renderscript;


Path::Path(Context *rsc) : ObjectBase(rsc) {
}

Path::Path(Context *rsc, RsPathPrimitive pp, bool isStatic,
                      Allocation *vtx, Allocation *loops, float quality)
: ObjectBase(rsc) {

    memset(&mHal, 0, sizeof(mHal));
    mHal.state.quality = quality;
    mHal.state.primitive = pp;

    //LOGE("i1");
    rsc->mHal.funcs.path.initStatic(rsc, this, vtx, loops);

    //LOGE("i2");
}

Path::Path(Context *rsc, uint32_t vertexBuffersCount, uint32_t primitivesCount)
: ObjectBase(rsc) {

}

Path::~Path() {

}


void Path::rasterize(const BezierSegment_t *s, uint32_t num, Allocation *alloc) {

    for (uint32_t i=0; i < num; i++) {

    }

}

void Path::render(Context *rsc) {
}

void Path::serialize(OStream *stream) const {

}

RsA3DClassID Path::getClassId() const {
    return RS_A3D_CLASS_ID_UNKNOWN;
}

namespace android {
namespace renderscript {

RsPath rsi_PathCreate(Context *rsc, RsPathPrimitive pp, bool isStatic,
                      RsAllocation vtx, RsAllocation loops, float quality) {
    return new Path(rsc, pp, isStatic, (Allocation *)vtx, (Allocation *)loops, quality);
}

}
}
