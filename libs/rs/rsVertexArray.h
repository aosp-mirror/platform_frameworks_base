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

#ifndef ANDROID_VERTEX_ARRAY_H
#define ANDROID_VERTEX_ARRAY_H


#include "rsObjectBase.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

class ShaderCache;

// An element is a group of Components that occupies one cell in a structure.
class VertexArray
{
public:
    VertexArray();
    virtual ~VertexArray();

    enum AttribName {
        POSITION,
        COLOR,
        NORMAL,
        POINT_SIZE,
        TEXTURE,
        _LAST
    };

    class Attrib {
    public:
        uint32_t buffer;
        uint32_t offset;
        uint32_t type;
        uint32_t size;
        uint32_t stride;
        bool normalized;
        String8 name;

        Attrib();
        void set(const Attrib &);
        void clear();
    };


    void clearAll();
    void clear(AttribName);

    void setActiveBuffer(uint32_t id) {mActiveBuffer = id;}

    void setUser(const Attrib &, uint32_t stride);
    void setPosition(uint32_t size, uint32_t type, uint32_t stride, uint32_t offset);
    void setColor(uint32_t size, uint32_t type, uint32_t stride, uint32_t offset);
    void setNormal(uint32_t type, uint32_t stride, uint32_t offset);
    void setPointSize(uint32_t type, uint32_t stride, uint32_t offset);
    void setTexture(uint32_t size, uint32_t type, uint32_t stride, uint32_t offset);

    void setupGL(const Context *rsc, class VertexArrayState *) const;
    void setupGL2(const Context *rsc, class VertexArrayState *, ShaderCache *) const;
    void logAttrib(uint32_t idx, uint32_t slot) const;

protected:
    uint32_t mActiveBuffer;
    uint32_t mUserCount;
    Attrib mAttribs[RS_MAX_ATTRIBS];
};


class VertexArrayState {
public:
    void init(Context *);

    //VertexArray::Attrib mAttribs[VertexArray::_LAST];
};


}
}
#endif //ANDROID_LIGHT_H



