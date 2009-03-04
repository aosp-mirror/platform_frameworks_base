/*
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

#ifndef ANDROID_OPENGLES_SURFACE_H
#define ANDROID_OPENGLES_SURFACE_H

#include <stdint.h>
#include <stddef.h>
#include <sys/types.h>

#include <utils/Atomic.h>
#include <utils/threads.h>
#include <utils/RefBase.h>
#include <utils/KeyedVector.h>
#include <utils/Errors.h>

#include <private/pixelflinger/ggl_context.h>

#include <GLES/gl.h>

#include "Tokenizer.h"
#include "TokenManager.h"


namespace android {

// ----------------------------------------------------------------------------

class EGLTextureObject
{
public:
                    EGLTextureObject();
                   ~EGLTextureObject();

    // protocol for sp<>
    inline  void        incStrong(const void* id) const;
    inline  void        decStrong(const void* id) const;
    inline  uint32_t    getStrongCount() const;

    status_t            setSurface(GGLSurface const* s);
    status_t            reallocate(GLint level,
                            int w, int h, int s,
                            int format, int compressedFormat, int bpr);
    inline  size_t      size() const;
    const GGLSurface&   mip(int lod) const;
    GGLSurface&         editMip(int lod);
    bool                hasMipmaps() const { return mMipmaps!=0; }
    bool                isComplete() const { return mIsComplete; }
    void                copyParameters(const sp<EGLTextureObject>& old);

private:
        status_t        allocateMipmaps();
            void        freeMipmaps();
            void        init();
    mutable int32_t     mCount;
    size_t              mSize;
    GGLSurface          *mMipmaps;
    int                 mNumExtraLod;
    bool                mIsComplete;

public:
    GGLSurface          surface;
    GLenum              wraps;
    GLenum              wrapt;
    GLenum              min_filter;
    GLenum              mag_filter;
    GLenum              internalformat;
    GLint               crop_rect[4];
    GLint               generate_mipmap;
    GLint               direct;
};

void EGLTextureObject::incStrong(const void* id) const {
    android_atomic_inc(&mCount);
}
void EGLTextureObject::decStrong(const void* id) const {
    if (android_atomic_dec(&mCount) == 1) {
        delete this;
    }
}
uint32_t EGLTextureObject::getStrongCount() const {
    return mCount;
}
size_t EGLTextureObject::size() const {
    return mSize;
}

// ----------------------------------------------------------------------------

class EGLSurfaceManager : public TokenManager
{
public:
                EGLSurfaceManager();
                ~EGLSurfaceManager();

    // protocol for sp<>
    inline  void    incStrong(const void* id) const;
    inline  void    decStrong(const void* id) const;
    typedef void    weakref_type;

    sp<EGLTextureObject>    createTexture(GLuint name);
    sp<EGLTextureObject>    removeTexture(GLuint name);
    sp<EGLTextureObject>    replaceTexture(GLuint name);
    void                    deleteTextures(GLsizei n, const GLuint *tokens);
    sp<EGLTextureObject>    texture(GLuint name);

private:
    mutable int32_t                             mCount;
    mutable Mutex                               mLock;
    KeyedVector< GLuint, sp<EGLTextureObject> > mTextures;
};

void EGLSurfaceManager::incStrong(const void* id) const {
    android_atomic_inc(&mCount);
}
void EGLSurfaceManager::decStrong(const void* id) const {
    if (android_atomic_dec(&mCount) == 1) {
        delete this;
    }
}


// ----------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_OPENGLES_SURFACE_H

