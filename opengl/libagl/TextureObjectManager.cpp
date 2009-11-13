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

#include <stdio.h>
#include <stdlib.h>
#include "context.h"
#include "TextureObjectManager.h"

#include <private/ui/android_natives_priv.h>

namespace android {
// ----------------------------------------------------------------------------

EGLTextureObject::EGLTextureObject()
    : mSize(0)
{
    init();
}

EGLTextureObject::~EGLTextureObject()
{
    if (!direct) {
        if (mSize && surface.data)
            free(surface.data);
        if (mMipmaps)
            freeMipmaps();
    }
}

void EGLTextureObject::init()
{
    memset(&surface, 0, sizeof(surface));
    surface.version = sizeof(surface);
    mMipmaps = 0;
    mNumExtraLod = 0;
    mIsComplete = false;
    wraps = GL_REPEAT;
    wrapt = GL_REPEAT;
    min_filter = GL_LINEAR;
    mag_filter = GL_LINEAR;
    internalformat = 0;
    memset(crop_rect, 0, sizeof(crop_rect));
    generate_mipmap = GL_FALSE;
    direct = GL_FALSE;
#ifdef LIBAGL_USE_GRALLOC_COPYBITS
    try_copybit = false;
#endif // LIBAGL_USE_GRALLOC_COPYBITS
    buffer = 0;
}

void EGLTextureObject::copyParameters(const sp<EGLTextureObject>& old)
{
    wraps = old->wraps;
    wrapt = old->wrapt;
    min_filter = old->min_filter;
    mag_filter = old->mag_filter;
    memcpy(crop_rect, old->crop_rect, sizeof(crop_rect));
    generate_mipmap = old->generate_mipmap;
    direct = old->direct;
}

status_t EGLTextureObject::allocateMipmaps()
{
    // here, by construction, mMipmaps=0 && mNumExtraLod=0

    if (!surface.data)
        return NO_INIT;

    int w = surface.width;
    int h = surface.height;
    const int numLods = 31 - gglClz(max(w,h));
    if (numLods <= 0)
        return NO_ERROR;

    mMipmaps = (GGLSurface*)malloc(numLods * sizeof(GGLSurface));
    if (!mMipmaps)
        return NO_MEMORY;

    memset(mMipmaps, 0, numLods * sizeof(GGLSurface));
    mNumExtraLod = numLods;
    return NO_ERROR;
}

void EGLTextureObject::freeMipmaps()
{
    if (mMipmaps) {
        for (int i=0 ; i<mNumExtraLod ; i++) {
            if (mMipmaps[i].data) {
                free(mMipmaps[i].data);
            }
        }
        free(mMipmaps);
        mMipmaps = 0;
        mNumExtraLod = 0;
    }
}

const GGLSurface& EGLTextureObject::mip(int lod) const
{
    if (lod<=0 || !mMipmaps)
        return surface;
    lod = min(lod-1, mNumExtraLod-1);
    return mMipmaps[lod];
}

GGLSurface& EGLTextureObject::editMip(int lod)
{
    return const_cast<GGLSurface&>(mip(lod));
}

status_t EGLTextureObject::setSurface(GGLSurface const* s)
{
    // XXX: glFlush() on 's'
    if (mSize && surface.data) {
        free(surface.data);
    }
    surface = *s;
    internalformat = 0;
    buffer = 0;

    // we should keep the crop_rect, but it's delicate because
    // the new size of the surface could make it invalid.
    // so for now, we just loose it.
    memset(crop_rect, 0, sizeof(crop_rect));

    // it would be nice if we could keep the generate_mipmap flag,
    // we would have to generate them right now though.
    generate_mipmap = GL_FALSE;

    direct = GL_TRUE;
    mSize = 0;  // we don't own this surface
    if (mMipmaps)
        freeMipmaps();
    mIsComplete = true;
    return NO_ERROR;
}

status_t EGLTextureObject::setImage(android_native_buffer_t* native_buffer)
{
    GGLSurface sur;
    sur.version = sizeof(GGLSurface);
    sur.width = native_buffer->width;
    sur.height= native_buffer->height;
    sur.stride= native_buffer->stride;
    sur.format= native_buffer->format;
    sur.data  = 0;
    setSurface(&sur);
    buffer = native_buffer;
    return NO_ERROR;
}

status_t EGLTextureObject::reallocate(
        GLint level, int w, int h, int s,
        int format, int compressedFormat, int bpr)
{
    const size_t size = h * bpr;
    if (level == 0)
    {
        if (size!=mSize || !surface.data) {
            if (mSize && surface.data) {
                free(surface.data);
            }
            surface.data = (GGLubyte*)malloc(size);
            if (!surface.data) {
                mSize = 0;
                mIsComplete = false;
                return NO_MEMORY;
            }
            mSize = size;
        }
        surface.version = sizeof(GGLSurface);
        surface.width  = w;
        surface.height = h;
        surface.stride = s;
        surface.format = format;
        surface.compressedFormat = compressedFormat;
        if (mMipmaps)
            freeMipmaps();
        mIsComplete = true;
    }
    else
    {
        if (!mMipmaps) {
            if (allocateMipmaps() != NO_ERROR)
                return NO_MEMORY;
        }

        LOGW_IF(level-1 >= mNumExtraLod,
                "specifying mipmap level %d, but # of level is %d",
                level, mNumExtraLod+1);

        GGLSurface& mipmap = editMip(level);
        if (mipmap.data)
            free(mipmap.data);

        mipmap.data = (GGLubyte*)malloc(size);
        if (!mipmap.data) {
            memset(&mipmap, 0, sizeof(GGLSurface));
            mIsComplete = false;
            return NO_MEMORY;
        }

        mipmap.version = sizeof(GGLSurface);
        mipmap.width  = w;
        mipmap.height = h;
        mipmap.stride = s;
        mipmap.format = format;
        mipmap.compressedFormat = compressedFormat;

        // check if the texture is complete
        mIsComplete = true;
        const GGLSurface* prev = &surface;
        for (int i=0 ; i<mNumExtraLod ; i++) {
            const GGLSurface* curr = mMipmaps + i;
            if (curr->format != surface.format) {
                mIsComplete = false;
                break;
            }

            uint32_t w = (prev->width  >> 1) ? : 1;
            uint32_t h = (prev->height >> 1) ? : 1;
            if (w != curr->width || h != curr->height) {
                mIsComplete = false;
                break;
            }
            prev = curr;
        }
    }
    return NO_ERROR;
}

// ----------------------------------------------------------------------------

EGLSurfaceManager::EGLSurfaceManager()
    : TokenManager()
{
}

EGLSurfaceManager::~EGLSurfaceManager()
{
    // everything gets freed automatically here...
}

sp<EGLTextureObject> EGLSurfaceManager::createTexture(GLuint name)
{
    sp<EGLTextureObject> result;

    Mutex::Autolock _l(mLock);
    if (mTextures.indexOfKey(name) >= 0)
        return result; // already exists!

    result = new EGLTextureObject();

    status_t err = mTextures.add(name, result);
    if (err < 0)
        result.clear();

    return result;
}

sp<EGLTextureObject> EGLSurfaceManager::removeTexture(GLuint name)
{
    Mutex::Autolock _l(mLock);
    const ssize_t index = mTextures.indexOfKey(name);
    if (index >= 0) {
        sp<EGLTextureObject> result(mTextures.valueAt(index));
        mTextures.removeItemsAt(index);
        return result;
    }
    return 0;
}

sp<EGLTextureObject> EGLSurfaceManager::replaceTexture(GLuint name)
{
    sp<EGLTextureObject> tex;
    Mutex::Autolock _l(mLock);
    const ssize_t index = mTextures.indexOfKey(name);
    if (index >= 0) {
        const sp<EGLTextureObject>& old = mTextures.valueAt(index);
        const uint32_t refs = old->getStrongCount();
        if (ggl_likely(refs == 1)) {
            // we're the only owner
            tex = old;
        } else {
            // keep the texture's parameters
            tex = new EGLTextureObject();
            tex->copyParameters(old);
            mTextures.removeItemsAt(index);
            mTextures.add(name, tex);
        }
    }
    return tex;
}

void EGLSurfaceManager::deleteTextures(GLsizei n, const GLuint *tokens)
{
    // free all textures
    Mutex::Autolock _l(mLock);
    for (GLsizei i=0 ; i<n ; i++) {
        const GLuint t(*tokens++);
        if (t) {
            mTextures.removeItem(t);
        }
    }
}

sp<EGLTextureObject> EGLSurfaceManager::texture(GLuint name)
{
    Mutex::Autolock _l(mLock);
    const ssize_t index = mTextures.indexOfKey(name);
    if (index >= 0)
        return mTextures.valueAt(index);
    return 0;
}

// ----------------------------------------------------------------------------
}; // namespace android
