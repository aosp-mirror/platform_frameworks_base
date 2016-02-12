/*
 * Copyright (C) 2013 The Android Open Source Project
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

#include "Caches.h"
#include "Texture.h"
#include "utils/GLUtils.h"
#include "utils/TraceUtils.h"

#include <utils/Log.h>

#include <SkCanvas.h>

namespace android {
namespace uirenderer {

static int bytesPerPixel(GLint glFormat) {
    switch (glFormat) {
    // The wrapped-texture case, usually means a SurfaceTexture
    case 0:
        return 0;
    case GL_ALPHA:
        return 1;
    case GL_RGB:
        return 3;
    case GL_RGBA:
        return 4;
    case GL_RGBA16F:
        return 16;
    default:
        LOG_ALWAYS_FATAL("UNKNOWN FORMAT %d", glFormat);
    }
}

void Texture::setWrapST(GLenum wrapS, GLenum wrapT, bool bindTexture, bool force,
        GLenum renderTarget) {

    if (force || wrapS != mWrapS || wrapT != mWrapT) {
        mWrapS = wrapS;
        mWrapT = wrapT;

        if (bindTexture) {
            mCaches.textureState().bindTexture(renderTarget, mId);
        }

        glTexParameteri(renderTarget, GL_TEXTURE_WRAP_S, wrapS);
        glTexParameteri(renderTarget, GL_TEXTURE_WRAP_T, wrapT);
    }
}

void Texture::setFilterMinMag(GLenum min, GLenum mag, bool bindTexture, bool force,
        GLenum renderTarget) {

    if (force || min != mMinFilter || mag != mMagFilter) {
        mMinFilter = min;
        mMagFilter = mag;

        if (bindTexture) {
            mCaches.textureState().bindTexture(renderTarget, mId);
        }

        if (mipMap && min == GL_LINEAR) min = GL_LINEAR_MIPMAP_LINEAR;

        glTexParameteri(renderTarget, GL_TEXTURE_MIN_FILTER, min);
        glTexParameteri(renderTarget, GL_TEXTURE_MAG_FILTER, mag);
    }
}

void Texture::deleteTexture() {
    mCaches.textureState().deleteTexture(mId);
    mId = 0;
}

bool Texture::updateSize(uint32_t width, uint32_t height, GLint format) {
    if (mWidth == width && mHeight == height && mFormat == format) {
        return false;
    }
    mWidth = width;
    mHeight = height;
    mFormat = format;
    notifySizeChanged(mWidth * mHeight * bytesPerPixel(mFormat));
    return true;
}

void Texture::resetCachedParams() {
    mWrapS = GL_REPEAT;
    mWrapT = GL_REPEAT;
    mMinFilter = GL_NEAREST_MIPMAP_LINEAR;
    mMagFilter = GL_LINEAR;
}

void Texture::upload(GLint internalformat, uint32_t width, uint32_t height,
        GLenum format, GLenum type, const void* pixels) {
    GL_CHECKPOINT(MODERATE);
    bool needsAlloc = updateSize(width, height, internalformat);
    if (!mId) {
        glGenTextures(1, &mId);
        needsAlloc = true;
        resetCachedParams();
    }
    mCaches.textureState().bindTexture(GL_TEXTURE_2D, mId);
    if (needsAlloc) {
        glTexImage2D(GL_TEXTURE_2D, 0, mFormat, mWidth, mHeight, 0,
                format, type, pixels);
    } else if (pixels) {
        glTexSubImage2D(GL_TEXTURE_2D, 0, mFormat, mWidth, mHeight, 0,
                format, type, pixels);
    }
    GL_CHECKPOINT(MODERATE);
}

static void uploadToTexture(bool resize, GLenum format, GLenum type, GLsizei stride, GLsizei bpp,
        GLsizei width, GLsizei height, const GLvoid * data) {

    const bool useStride = stride != width
            && Caches::getInstance().extensions().hasUnpackRowLength();
    if ((stride == width) || useStride) {
        if (useStride) {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, stride);
        }

        if (resize) {
            glTexImage2D(GL_TEXTURE_2D, 0, format, width, height, 0, format, type, data);
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, format, type, data);
        }

        if (useStride) {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        }
    } else {
        //  With OpenGL ES 2.0 we need to copy the bitmap in a temporary buffer
        //  if the stride doesn't match the width

        GLvoid * temp = (GLvoid *) malloc(width * height * bpp);
        if (!temp) return;

        uint8_t * pDst = (uint8_t *)temp;
        uint8_t * pSrc = (uint8_t *)data;
        for (GLsizei i = 0; i < height; i++) {
            memcpy(pDst, pSrc, width * bpp);
            pDst += width * bpp;
            pSrc += stride * bpp;
        }

        if (resize) {
            glTexImage2D(GL_TEXTURE_2D, 0, format, width, height, 0, format, type, temp);
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, format, type, temp);
        }

        free(temp);
    }
}

static void uploadSkBitmapToTexture(const SkBitmap& bitmap,
        bool resize, GLenum format, GLenum type) {
    uploadToTexture(resize, format, type, bitmap.rowBytesAsPixels(), bitmap.bytesPerPixel(),
            bitmap.width(), bitmap.height(), bitmap.getPixels());
}

static void colorTypeToGlFormatAndType(SkColorType colorType,
        GLint* outFormat, GLint* outType) {
    switch (colorType) {
    case kAlpha_8_SkColorType:
        *outFormat = GL_ALPHA;
        *outType = GL_UNSIGNED_BYTE;
        break;
    case kRGB_565_SkColorType:
        *outFormat = GL_RGB;
        *outType = GL_UNSIGNED_SHORT_5_6_5;
        break;
    // ARGB_4444 and Index_8 are both upconverted to RGBA_8888
    case kARGB_4444_SkColorType:
    case kIndex_8_SkColorType:
    case kN32_SkColorType:
        *outFormat = GL_RGBA;
        *outType = GL_UNSIGNED_BYTE;
        break;
    case kGray_8_SkColorType:
        *outFormat = GL_LUMINANCE;
        *outType = GL_UNSIGNED_BYTE;
        break;
    default:
        LOG_ALWAYS_FATAL("Unsupported bitmap colorType: %d", colorType);
        break;
    }
}

void Texture::upload(const SkBitmap& bitmap) {
    SkAutoLockPixels alp(bitmap);

    if (!bitmap.readyToDraw()) {
        ALOGE("Cannot generate texture from bitmap");
        return;
    }

    ATRACE_FORMAT("Upload %ux%u Texture", bitmap.width(), bitmap.height());

    // We could also enable mipmapping if both bitmap dimensions are powers
    // of 2 but we'd have to deal with size changes. Let's keep this simple
    const bool canMipMap = mCaches.extensions().hasNPot();

    // If the texture had mipmap enabled but not anymore,
    // force a glTexImage2D to discard the mipmap levels
    bool needsAlloc = canMipMap && mipMap && !bitmap.hasHardwareMipMap();
    bool setDefaultParams = false;

    if (!mId) {
        glGenTextures(1, &mId);
        needsAlloc = true;
        setDefaultParams = true;
    }

    GLint format, type;
    colorTypeToGlFormatAndType(bitmap.colorType(), &format, &type);

    if (updateSize(bitmap.width(), bitmap.height(), format)) {
        needsAlloc = true;
    }

    blend = !bitmap.isOpaque();
    mCaches.textureState().bindTexture(mId);

    if (CC_UNLIKELY(bitmap.colorType() == kARGB_4444_SkColorType
            || bitmap.colorType() == kIndex_8_SkColorType)) {
        SkBitmap rgbaBitmap;
        rgbaBitmap.allocPixels(SkImageInfo::MakeN32(mWidth, mHeight,
                bitmap.alphaType()));
        rgbaBitmap.eraseColor(0);

        SkCanvas canvas(rgbaBitmap);
        canvas.drawBitmap(bitmap, 0.0f, 0.0f, nullptr);

        uploadSkBitmapToTexture(rgbaBitmap, needsAlloc, format, type);
    } else {
        uploadSkBitmapToTexture(bitmap, needsAlloc, format, type);
    }

    if (canMipMap) {
        mipMap = bitmap.hasHardwareMipMap();
        if (mipMap) {
            glGenerateMipmap(GL_TEXTURE_2D);
        }
    }

    if (setDefaultParams) {
        setFilter(GL_NEAREST);
        setWrap(GL_CLAMP_TO_EDGE);
    }
}

void Texture::wrap(GLuint id, uint32_t width, uint32_t height, GLint format) {
    mId = id;
    mWidth = width;
    mHeight = height;
    mFormat = format;
    // We're wrapping an existing texture, so don't double count this memory
    notifySizeChanged(0);
}

}; // namespace uirenderer
}; // namespace android
