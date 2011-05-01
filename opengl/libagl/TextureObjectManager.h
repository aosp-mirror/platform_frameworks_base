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
#include <EGL/egl.h>
#include <EGL/eglext.h>

#include "Tokenizer.h"
#include "TokenManager.h"


namespace android {

// ----------------------------------------------------------------------------

class EGLTextureObject : public LightRefBase<EGLTextureObject>
{
public:
                    EGLTextureObject();
                   ~EGLTextureObject();

    status_t    setSurface(GGLSurface const* s);
    status_t    setImage(ANativeWindowBuffer* buffer);
    void        setImageBits(void* vaddr) { surface.data = (GGLubyte*)vaddr; }

    status_t            reallocate(GLint level,
                            int w, int h, int s,
                            int format, int compressedFormat, int bpr);
    inline  size_t      size() const { return mSize; }
    const GGLSurface&   mip(int lod) const;
    GGLSurface&         editMip(int lod);
    bool                hasMipmaps() const { return mMipmaps!=0; }
    bool                isComplete() const { return mIsComplete; }
    void                copyParameters(const sp<EGLTextureObject>& old);

private:
        status_t        allocateMipmaps();
            void        freeMipmaps();
            void        init();
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
    ANativeWindowBuffer* buffer;
};

// ----------------------------------------------------------------------------

class EGLSurfaceManager :
    public LightRefBase<EGLSurfaceManager>,
    public TokenManager
{
public:
                EGLSurfaceManager();
                ~EGLSurfaceManager();

    sp<EGLTextureObject>    createTexture(GLuint name);
    sp<EGLTextureObject>    removeTexture(GLuint name);
    sp<EGLTextureObject>    replaceTexture(GLuint name);
    void                    deleteTextures(GLsizei n, const GLuint *tokens);
    sp<EGLTextureObject>    texture(GLuint name);

private:
    mutable Mutex                               mLock;
    KeyedVector< GLuint, sp<EGLTextureObject> > mTextures;
};

// ----------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_OPENGLES_SURFACE_H

