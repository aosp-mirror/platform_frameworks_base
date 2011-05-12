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

#ifndef ANDROID_RSD_VERTEX_ARRAY_H
#define ANDROID_RSD_VERTEX_ARRAY_H

namespace android {
namespace renderscript {

class Context;

}
}

#include <utils/String8.h>

// An element is a group of Components that occupies one cell in a structure.
class RsdVertexArray {
public:
    class Attrib {
    public:
        uint32_t buffer;
        const uint8_t * ptr;
        uint32_t offset;
        uint32_t type;
        uint32_t size;
        uint32_t stride;
        bool normalized;
        android::String8 name;

        Attrib();
        void clear();
        void set(uint32_t type, uint32_t size, uint32_t stride, bool normalized, uint32_t offset, const char *name);
    };

    RsdVertexArray(const Attrib *attribs, uint32_t numAttribs);
    virtual ~RsdVertexArray();

    void setup(const android::renderscript::Context *rsc) const;
    void logAttrib(uint32_t idx, uint32_t slot) const;

protected:
    void clear(uint32_t index);
    uint32_t mActiveBuffer;
    const uint8_t * mActivePointer;
    uint32_t mCount;

    const Attrib *mAttribs;
};


class RsdVertexArrayState {
public:
    RsdVertexArrayState();
    ~RsdVertexArrayState();
    void init(uint32_t maxAttrs);

    bool *mAttrsEnabled;
    uint32_t mAttrsEnabledSize;
};


#endif //ANDROID_RSD_VERTEX_ARRAY_H



