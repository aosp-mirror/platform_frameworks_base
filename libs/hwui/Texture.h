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
#include "hwui/Bitmap.h"
#include "utils/Color.h"

#include <memory>

#include <math/mat3.h>

#include <ui/ColorSpace.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES3/gl3.h>
#include <SkBitmap.h>

namespace android {

class GraphicBuffer;

namespace uirenderer {

class Caches;
class UvMapper;
class Layer;

/**
 * Represents an OpenGL texture.
 */
class Texture : public GpuMemoryTracker {
public:
    static SkBitmap uploadToN32(const SkBitmap& bitmap, bool hasLinearBlending,
                                sk_sp<SkColorSpace> sRGB);
    static bool hasUnsupportedColorType(const SkImageInfo& info, bool hasLinearBlending);
    static void colorTypeToGlFormatAndType(const Caches& caches, SkColorType colorType,
                                           bool needSRGB, GLint* outInternalFormat,
                                           GLint* outFormat, GLint* outType);

    explicit Texture(Caches& caches) : GpuMemoryTracker(GpuObjectType::Texture), mCaches(caches) {}

    virtual ~Texture() {}

    inline void setWrap(GLenum wrap, bool bindTexture = false, bool force = false) {
        setWrapST(wrap, wrap, bindTexture, force);
    }

    virtual void setWrapST(GLenum wrapS, GLenum wrapT, bool bindTexture = false,
                           bool force = false);

    inline void setFilter(GLenum filter, bool bindTexture = false, bool force = false) {
        setFilterMinMag(filter, filter, bindTexture, force);
    }

    virtual void setFilterMinMag(GLenum min, GLenum mag, bool bindTexture = false,
                                 bool force = false);

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
    void resize(uint32_t width, uint32_t height, GLint internalFormat, GLint format) {
        upload(internalFormat, width, height, format,
               internalFormat == GL_RGBA16F ? GL_HALF_FLOAT : GL_UNSIGNED_BYTE, nullptr);
    }

    /**
     * Updates this Texture with the contents of the provided Bitmap,
     * also setting the appropriate width, height, and format. It is not necessary
     * to call resize() prior to this.
     *
     * Note this does not set the generation from the Bitmap.
     */
    void upload(Bitmap& source);

    /**
     * Basically glTexImage2D/glTexSubImage2D.
     */
    void upload(GLint internalFormat, uint32_t width, uint32_t height, GLenum format, GLenum type,
                const void* pixels);

    /**
     * Wraps an existing texture.
     */
    void wrap(GLuint id, uint32_t width, uint32_t height, GLint internalFormat, GLint format,
              GLenum target);

    GLuint id() const { return mId; }

    uint32_t width() const { return mWidth; }

    uint32_t height() const { return mHeight; }

    GLint format() const { return mFormat; }

    GLint internalFormat() const { return mInternalFormat; }

    GLenum target() const { return mTarget; }

    /**
     * Returns nullptr if this texture does not require color space conversion
     * to sRGB, or a valid pointer to a ColorSpaceConnector if a conversion
     * is required.
     */
    constexpr const ColorSpaceConnector* getColorSpaceConnector() const { return mConnector.get(); }

    constexpr bool hasColorSpaceConversion() const { return mConnector.get() != nullptr; }

    TransferFunctionType getTransferFunctionType() const;

    /**
     * Returns true if this texture uses a linear encoding format.
     */
    constexpr bool isLinear() const { return mIsLinear; }

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
    // TODO: Temporarily grant private access to GlLayer, remove once
    // GlLayer can be de-tangled from being a dual-purpose render target
    // and external texture wrapper
    friend class GlLayer;

    // Returns true if the texture layout (size, format, etc.) changed, false if it was the same
    bool updateLayout(uint32_t width, uint32_t height, GLint internalFormat, GLint format,
                      GLenum target);
    void uploadHardwareBitmapToTexture(GraphicBuffer* buffer);
    void resetCachedParams();

    GLuint mId = 0;
    uint32_t mWidth = 0;
    uint32_t mHeight = 0;
    GLint mFormat = 0;
    GLint mInternalFormat = 0;
    GLenum mTarget = GL_NONE;
    EGLImageKHR mEglImageHandle = EGL_NO_IMAGE_KHR;

    /* See GLES spec section 3.8.14
     * "In the initial state, the value assigned to TEXTURE_MIN_FILTER is
     * NEAREST_MIPMAP_LINEAR and the value for TEXTURE_MAG_FILTER is LINEAR.
     * s, t, and r wrap modes are all set to REPEAT."
     */
    GLenum mWrapS = GL_REPEAT;
    GLenum mWrapT = GL_REPEAT;
    GLenum mMinFilter = GL_NEAREST_MIPMAP_LINEAR;
    GLenum mMagFilter = GL_LINEAR;

    // Indicates whether the content of the texture is in linear space
    bool mIsLinear = false;

    Caches& mCaches;

    std::unique_ptr<ColorSpaceConnector> mConnector;
};  // struct Texture

class AutoTexture {
public:
    explicit AutoTexture(Texture* texture) : texture(texture) {}
    ~AutoTexture() {
        if (texture && texture->cleanup) {
            texture->deleteTexture();
            delete texture;
        }
    }

    Texture* const texture;
};  // class AutoTexture

};  // namespace uirenderer
};  // namespace android

#endif  // ANDROID_HWUI_TEXTURE_H
