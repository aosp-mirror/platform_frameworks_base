/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_HWUI_TEXTURE_H
#define ANDROID_HWUI_TEXTURE_H

#include "GpuMemoryTracker.h"

#include <GLES2/gl2.h>
#include <SkBitmap.h>

namespace android {
namespace uirenderer {

class Caches;
class UvMapper;
class Layer;

/**
 * Represents an OpenGL texture.
 */
class Texture : public GpuMemoryTracker {
public:
    Texture(Caches& caches)
        : GpuMemoryTracker(GpuObjectType::Texture)
        , mCaches(caches)
    { }

    virtual ~Texture() { }

    inline void setWrap(GLenum wrap, bool bindTexture = false, bool force = false,
                GLenum renderTarget = GL_TEXTURE_2D) {
        setWrapST(wrap, wrap, bindTexture, force, renderTarget);
    }

    virtual void setWrapST(GLenum wrapS, GLenum wrapT, bool bindTexture = false,
            bool force = false, GLenum renderTarget = GL_TEXTURE_2D);

    inline void setFilter(GLenum filter, bool bindTexture = false, bool force = false,
                GLenum renderTarget = GL_TEXTURE_2D) {
        setFilterMinMag(filter, filter, bindTexture, force, renderTarget);
    }

    virtual void setFilterMinMag(GLenum min, GLenum mag, bool bindTexture = false,
            bool force = false, GLenum renderTarget = GL_TEXTURE_2D);

    /**
     * Convenience method to call glDeleteTextures() on this texture's id.
     */
    void deleteTexture();

    /**
     * Sets the width, height, and format of the texture along with allocating
     * the texture ID. Does nothing if the width, height, and format are already
     * the requested values.
     *
     * The image data is undefined after calling this.
     */
    void resize(uint32_t width, uint32_t height, GLint format) {
        upload(format, width, height, format, GL_UNSIGNED_BYTE, nullptr);
    }

    /**
     * Updates this Texture with the contents of the provided SkBitmap,
     * also setting the appropriate width, height, and format. It is not necessary
     * to call resize() prior to this.
     *
     * Note this does not set the generation from the SkBitmap.
     */
    void upload(const SkBitmap& source);

    /**
     * Basically glTexImage2D/glTexSubImage2D.
     */
    void upload(GLint internalformat, uint32_t width, uint32_t height,
            GLenum format, GLenum type, const void* pixels);

    /**
     * Wraps an existing texture.
     */
    void wrap(GLuint id, uint32_t width, uint32_t height, GLint format);

    GLuint id() const {
        return mId;
    }

    uint32_t width() const {
        return mWidth;
    }

    uint32_t height() const {
        return mHeight;
    }

    GLint format() const {
        return mFormat;
    }

    /**
     * Generation of the backing bitmap,
     */
    uint32_t generation = 0;
    /**
     * Indicates whether the texture requires blending.
     */
    bool blend = false;
    /**
     * Indicates whether this texture should be cleaned up after use.
     */
    bool cleanup = false;
    /**
     * Optional, size of the original bitmap.
     */
    uint32_t bitmapSize = 0;
    /**
     * Indicates whether this texture will use trilinear filtering.
     */
    bool mipMap = false;

    /**
     * Optional, pointer to a texture coordinates mapper.
     */
    const UvMapper* uvMapper = nullptr;

    /**
     * Whether or not the Texture is marked in use and thus not evictable for
     * the current frame. This is reset at the start of a new frame.
     */
    void* isInUse = nullptr;

private:
    // TODO: Temporarily grant private access to Layer, remove once
    // Layer can be de-tangled from being a dual-purpose render target
    // and external texture wrapper
    friend class Layer;

    // Returns true if the size changed, false if it was the same
    bool updateSize(uint32_t width, uint32_t height, GLint format);
    void resetCachedParams();

    GLuint mId = 0;
    uint32_t mWidth = 0;
    uint32_t mHeight = 0;
    GLint mFormat = 0;

    /* See GLES spec section 3.8.14
     * "In the initial state, the value assigned to TEXTURE_MIN_FILTER is
     * NEAREST_MIPMAP_LINEAR and the value for TEXTURE_MAG_FILTER is LINEAR.
     * s, t, and r wrap modes are all set to REPEAT."
     */
    GLenum mWrapS = GL_REPEAT;
    GLenum mWrapT = GL_REPEAT;
    GLenum mMinFilter = GL_NEAREST_MIPMAP_LINEAR;
    GLenum mMagFilter = GL_LINEAR;

    Caches& mCaches;
}; // struct Texture

class AutoTexture {
public:
    AutoTexture(Texture* texture)
            : texture(texture) {}
    ~AutoTexture() {
        if (texture && texture->cleanup) {
            texture->deleteTexture();
            delete texture;
        }
    }

    Texture* const texture;
}; // class AutoTexture

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_TEXTURE_H
