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

#ifndef ANDROID_FRAME_BUFFER_OBJECT_CACHE_H
#define ANDROID_FRAME_BUFFER_OBJECT_CACHE_H

#include "rsObjectBase.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

class Allocation;

class FBOCache {
public:
    FBOCache();
    ~FBOCache();

    void init(Context *rsc);
    void deinit(Context *rsc);

    void bindColorTarget(Context *rsc, Allocation *a, uint32_t slot);
    void bindDepthTarget(Context *, Allocation *a);
    void resetAll(Context *);

    void setup(Context *);
    void updateSize() { mDirty = true; }

    struct Hal {
        mutable void *drv;

        struct State {
            ObjectBaseRef<Allocation> *colorTargets;
            uint32_t colorTargetsCount;
            ObjectBaseRef<Allocation> depthTarget;
        };
        State state;
    };
    Hal mHal;

protected:
    bool mDirty;
    void checkError(Context *);
    void setColorAttachment(Context *rsc);
    void setDepthAttachment(Context *rsc);
    bool renderToFramebuffer();

};

} // renderscript
} // android

#endif //ANDROID_FRAME_BUFFER_OBJECT_CACHE_H
