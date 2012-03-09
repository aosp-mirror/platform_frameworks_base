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

#ifndef ANDROID_RS_PATH_H
#define ANDROID_RS_PATH_H


#include "rsObjectBase.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

class Path : public ObjectBase {
public:
    struct {
        mutable void * drv;

        struct State {
            RsPathPrimitive primitive;
            float quality;
        };
        State state;
    } mHal;

    Path(Context *);
    Path(Context *, uint32_t vertexBuffersCount, uint32_t primitivesCount);
    Path(Context *, RsPathPrimitive pp, bool isStatic, Allocation *vtx, Allocation *loop, float q);

    ~Path();

    void render(Context *);
    virtual void serialize(OStream *stream) const;
    virtual RsA3DClassID getClassId() const;

private:


    typedef struct {
        float x[4];
        float y[4];
    } BezierSegment_t;

    bool subdivideCheck(const BezierSegment_t *s, float u1, float u2);

    void rasterize(const BezierSegment_t *s, uint32_t num, Allocation *alloc);


};

}
}
#endif //ANDROID_RS_PATH_H



